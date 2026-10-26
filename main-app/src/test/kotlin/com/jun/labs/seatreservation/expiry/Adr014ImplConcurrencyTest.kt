package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.drift.QuotaCleanup
import com.jun.labs.seatreservation.service.impl.expiry.LazyHoldProcess
import com.jun.labs.seatreservation.support.IntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-014 동시성 테스트(§설계-9 N2 · N5 · N6 · §설계-10 #12 — 측정-9 시작 조건 ⑦).
 * 시각 역전은 '먼저 시작한 트랜잭션(작은 now())이 늦게 진행'으로 만든다: 테스트가 연 바깥 트랜잭션에서 now()를 고정한 뒤 기다렸다가
 * 유스케이스를 부른다(B·C·D 경로는 TransactionTemplate · @Transactional 전파 REQUIRED라 그 트랜잭션에 참여한다). 측정 지연 손잡이는 쓰지 않는다(Q13).
 * 교착 점검은 `pg_stat_database.deadlocks` 증분 0과 예외 SQLSTATE로 본다.
 */
abstract class Adr014ImplConcurrencyTest : Adr014ImplSupport() {

    @Autowired lateinit var meterRegistry: MeterRegistry

    private lateinit var pool: ExecutorService
    private var deadlocksBefore = 0L

    @BeforeEach
    fun startPool() {
        pool = Executors.newCachedThreadPool()
        deadlocksBefore = deadlocks()
    }

    @AfterEach
    fun stopPool() {
        pool.shutdownNow()
        pool.awaitTermination(10, TimeUnit.SECONDS)
    }

    private fun assertNoDeadlock() = assertEquals(deadlocksBefore, deadlocks(), "40P01(교착)이 났다")

    private fun <T> async(block: () -> T): Future<T> = pool.submit(Callable { block() })

    private fun code(block: () -> Unit): String = try {
        block()
        "ok"
    } catch (e: SeatReservationException) {
        e.errorCode.name
    }

    /**
     * 테스트가 연 바깥 트랜잭션 — now()를 먼저 고정하고([started]), [go]를 기다렸다가 [body]를 부른 뒤 [done]을 알리고 [release]까지 커밋을 미룬다.
     * body가 업무 예외로 끝나면 롤백한다. 결과 = "ok" 또는 에러 코드.
     */
    private fun pausedTx(
        started: CountDownLatch = CountDownLatch(1),
        go: CountDownLatch = CountDownLatch(0),
        done: CountDownLatch = CountDownLatch(1),
        release: CountDownLatch = CountDownLatch(0),
        body: () -> Unit,
    ): Future<String> = async {
        tx.execute { status ->
            jdbcTemplate.queryForObject("SELECT now()", Timestamp::class.java)
            started.countDown()
            go.await(30, TimeUnit.SECONDS)
            val result = code(body)
            done.countDown()
            release.await(30, TimeUnit.SECONDS)
            if (result != "ok") status.setRollbackOnly()
            result
        }!!
    }

    private fun expiresAt(holdId: Long): Instant =
        jdbcTemplate.queryForObject("SELECT expires_at FROM seat_hold WHERE id = ?", Timestamp::class.java, holdId)!!.toInstant()

    private fun awaitDbAfter(t: Instant) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (jdbcTemplate.queryForObject("SELECT clock_timestamp() > ?", Boolean::class.java, Timestamp.from(t)) != true) {
            check(System.nanoTime() < deadline) { "DB 시계가 $t 를 지나지 않았다" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `같은 좌석 동시 선점 50명 — 남의 만료 홀드 위에서도 활성 홀드는 1개(N2)`() {
        val seat = seat()
        dbHold(seat, 9_999, -1.0)
        markHeld(seat)
        quota(s, 9_999, 1)
        val ready = CountDownLatch(50)
        val start = CountDownLatch(1)
        val results = (0 until 50).map { i ->
            async {
                ready.countDown()
                start.await()
                holdCode(seat, 1_000L + i)
            }
        }
        ready.await()
        start.countDown()
        val counts = results.map { it.get(60, TimeUnit.SECONDS) }.groupingBy { it.substringBefore('/') }.eachCount()
        assertEquals(1, counts["ok"], "$counts")
        assertEquals(setOf("ok", "SEAT_NOT_AVAILABLE"), counts.keys, "$counts")
        assertEquals(1, activeRows(seat))
        assertEquals(0L, (judge()["double_sold_seats"] as Number).toLong())
        assertEquals(0L, mismatch())
        assertNoDeadlock()
    }

    @Test
    fun `시각 역전 + 남의 확정 — 만료 직전 시작한 확정은 K2에서 RESERVED를 받아 SEAT_NOT_HELD(DS14-1 · N2)`() {
        val seat = seat()
        val xHold = dbHold(seat, 1, 1.5)
        markHeld(seat)
        quota(s, 1, 1)
        val started = CountDownLatch(1)
        val go = CountDownLatch(1)
        val x = pausedTx(started = started, go = go) { confirm(xHold, 1) }
        started.await(10, TimeUnit.SECONDS) // X의 now() < 만료 시각
        awaitDbAfter(expiresAt(xHold).plusMillis(100))
        val y = hold(seat, 2) // 만료 뒤 Y 재선점 — H8이 X의 홀드를 만료로 본다
        confirm(y.holdId, 2) // Y 확정 커밋(X의 홀드는 Y 기준 만료라 other_active = false)
        go.countDown()
        assertEquals("SEAT_NOT_HELD", x.get(30, TimeUnit.SECONDS))
        assertEquals(1, reservations(seat), "이중 판매 0")
        assertEquals(0L, (judge()["double_sold_seats"] as Number).toLong())
        assertNoDeadlock()
    }

    @Test
    fun `시각 역전 + 재선점 — 만료 직전 시작한 확정은 K3의 '다른 활성 홀드'로 HOLD_EXPIRED(§2_2)`() {
        val seat = seat()
        val xHold = dbHold(seat, 1, 1.5)
        markHeld(seat)
        quota(s, 1, 1)
        val started = CountDownLatch(1)
        val go = CountDownLatch(1)
        val x = pausedTx(started = started, go = go) { confirm(xHold, 1) }
        started.await(10, TimeUnit.SECONDS)
        awaitDbAfter(expiresAt(xHold).plusMillis(100))
        val y = hold(seat, 2)
        go.countDown()
        assertEquals("HOLD_EXPIRED", x.get(30, TimeUnit.SECONDS))
        assertEquals(0, reservations(seat))
        assertEquals("ok", confirmCode(y.holdId, 2))
        assertEquals(1, reservations(seat))
        assertNoDeadlock()
    }

    @Test
    fun `확정 K3 ↔ 회수 H4(같은 사용자) — 회수가 확정 끝을 기다린 뒤 0행으로 받고 교착 없음 · H4 표지가 pg_stat_activity에 보인다(DS14-4 · #12 · #24)`() {
        val s1 = seat()
        val s2 = seat()
        val h1 = dbHold(s1, 1, 1.5)
        markHeld(s1)
        quota(s, 1, 1)
        val done = CountDownLatch(1)
        val release = CountDownLatch(1)
        val x = pausedTx(done = done, release = release) { confirm(h1, 1) } // 만료 전 확정 — 커밋을 미룬다(h1 행 잠금 유지)
        done.await(10, TimeUnit.SECONDS)
        awaitDbAfter(expiresAt(h1).plusMillis(100))
        val reclaim = async { holdCode(s2, 1) } // H2가 h1(커밋 전 삭제 — 아직 보인다)을 만료로 보고 H4에서 기다린다
        val waiting = awaitLockWaiters()
        assertTrue(waiting.any { it.contains(LazyHoldProcess.H4_MARK) }, "H4 표지가 대기 문장에 보여야 한다: $waiting")
        release.countDown()
        assertEquals("ok", x.get(30, TimeUnit.SECONDS))
        assertEquals("ok", reclaim.get(30, TimeUnit.SECONDS))
        assertEquals(2, cntOf(s, 1), "예약 1 + 새 홀드 1 — 확정이 지운 h1은 감소하지 않는다(규칙 ②)")
        assertEquals(0L, mismatch())
        assertNoDeadlock()
    }

    @Test
    fun `배치 ↔ 확정이 쥔 홀드 행 — C는 좌석을 건너뛰고 D는 D4에서 기다린 뒤 0행 · 교착 없음(#12)`() {
        assumeTrue(mode != ExpiryMode.LAZY)
        val seat = seat()
        val h = dbHold(seat, 1, 1.5)
        markHeld(seat)
        quota(s, 1, 1)
        val done = CountDownLatch(1)
        val release = CountDownLatch(1)
        val x = pausedTx(done = done, release = release) { confirm(h, 1) }
        done.await(10, TimeUnit.SECONDS)
        awaitDbAfter(expiresAt(h).plusMillis(100))
        val batch = async { expireHolds.expire() }
        if (mode == ExpiryMode.DERIVED) awaitLockWaiters() else assertEquals(0, batch.get(10, TimeUnit.SECONDS), "C는 C2 SKIP LOCKED로 건너뛴다")
        release.countDown()
        assertEquals("ok", x.get(30, TimeUnit.SECONDS))
        assertEquals(0, batch.get(30, TimeUnit.SECONDS))
        assertEquals(1, cntOf(s, 1), "예약 1 — 배치가 내리지 않았다")
        assertEquals(1, reservations(seat))
        assertEquals(0L, mismatch())
        assertNoDeadlock()
    }

    @Test
    fun `배치 MISSING 복구 ↔ 같은 쌍 선점 — 복구가 선점 끝을 기다리고 missingIds 불일치로 청크를 롤백한다(무음 아님 · DS14-5 · #12)`() {
        assumeTrue(mode != ExpiryMode.LAZY)
        val a = seat() // AVAILABLE로 둔다 — C의 H4b가 이 좌석을 잠그지 않게(배치 C2가 잡아 C3b까지 가도록)
        val b = seat()
        dbHold(a, 1, -1.0)
        val done = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = pausedTx(done = done, release = release) { hold(b, 1) } // H3 MISSING → 행 생성·잠금(커밋 전)
        done.await(10, TimeUnit.SECONDS)
        val mismatchBefore = meterRegistry.find("seat.hold.expiry.missing_mismatch").counter()?.count() ?: 0.0
        val batch = async { expireHolds.expire() }
        awaitLockWaiters() // 배치 C3b·D3의 PairRepair INSERT가 선점의 커밋 전 행을 기다린다
        release.countDown()
        assertEquals("ok", holder.get(30, TimeUnit.SECONDS))
        assertEquals(0, batch.get(30, TimeUnit.SECONDS))
        assertEquals(1.0, (meterRegistry.find("seat.hold.expiry.missing_mismatch").counter()?.count() ?: 0.0) - mismatchBefore)
        assertEquals(1, cntOf(s, 1))
        assertEquals(0L, mismatch())
        assertTrue(driftLog().any { it[2] == "skipped" }, "롤백된 청크의 복구 시도는 skipped로 남는다: ${driftLog()}")
        assertNoDeadlock()
    }

    @Test
    fun `C 배치는 방금 재선점된 좌석을 되돌리지 않는다 — 잠긴 동안 건너뛰고 뒤 틱은 활성 홀드를 보고 HELD 유지(N6 · 가설 3)`() {
        assumeTrue(mode == ExpiryMode.LAZY_SWEEP)
        val seat = seat()
        dbHold(seat, 1, -1.0)
        markHeld(seat)
        quota(s, 1, 1)
        val done = CountDownLatch(1)
        val release = CountDownLatch(1)
        val y = pausedTx(done = done, release = release) { hold(seat, 2) }
        done.await(10, TimeUnit.SECONDS)
        assertEquals(0, expireHolds.expire(), "선점이 쥔 좌석은 건너뛴다")
        release.countDown()
        assertEquals("ok", y.get(30, TimeUnit.SECONDS))
        val keptBefore = meterRegistry.find("seat.hold.expiry.revert").tags("path", "batch", "result", "kept_active").counter()?.count() ?: 0.0
        assertEquals(1, expireHolds.expire())
        assertEquals("HELD", status(seat))
        assertEquals(1, activeRows(seat))
        assertEquals(0, cntOf(s, 1))
        assertEquals(1, cntOf(s, 2))
        assertEquals(0L, (judge()["active_not_held"] as Number).toLong())
        assertEquals(1.0, (meterRegistry.find("seat.hold.expiry.revert").tags("path", "batch", "result", "kept_active").counter()?.count() ?: 0.0) - keptBefore)
        assertNoDeadlock()
    }

    @Test
    fun `동시 회수·배치 반복 — 같은 홀드를 두 번 빼지 않는다(N5)`() {
        assumeTrue(mode != ExpiryMode.LAZY)
        repeat(12) { round ->
            val u = 100L + round
            val olds = (1..3).map { seat() }
            olds.forEach { dbHold(it, u, -1.0); markHeld(it) }
            quota(s, u, 3)
            val target = seat()
            val start = CountDownLatch(1)
            val h = async { start.await(); holdCode(target, u) }
            val b = async { start.await(); expireHolds.expire() }
            start.countDown()
            assertEquals("ok", h.get(30, TimeUnit.SECONDS), "round $round")
            b.get(30, TimeUnit.SECONDS)
            expireHolds.expire()
            assertEquals(1, cntOf(s, u), "round $round")
            assertEquals(0L, mismatch(), "round $round")
        }
        assertNoDeadlock()
    }

    @Test
    fun `B 정리 ① ↔ 확정이 쥔 홀드 행 — SKIP LOCKED로 기다리지 않고 쿼터 행 삭제를 미룬다 · 다음 실행이 지운다(DS14-3 · #12)`() {
        assumeTrue(mode == ExpiryMode.LAZY)
        val closed = newSchedule(IntegrationTest.NOW.minusSeconds(60)).id!!
        val seat = seat(SeatStatus.HELD, closed)
        val h = dbHold(seat, 1, -1.0, closed)
        quota(closed, 1, 1)
        val cleanup = cleanup()
        rawConnection().use { k3 ->
            k3.exec("SELECT id FROM seat_hold WHERE id = ? FOR UPDATE", h) // 확정 K3 흉내
            val run = async { cleanup.runOnce() }.get(10, TimeUnit.SECONDS) // 기다리면 시간 초과
            assertEquals(0, run.rowsDeleted)
            assertEquals(1, run.skippedWithHolds)
            k3.exec(
                "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, 1, 'p', 'CONFIRMED', now())", closed, seat,
            ) // K7 FK 검사 — 정리가 이미 끝나 기다리지 않는다
            k3.rollback()
        }
        assertTrue(holdExistsRow(h))
        assertEquals(1, cntOf(closed, 1))
        assertEquals(1, cleanup.runOnce().rowsDeleted)
        assertEquals(0, holdRows(seat))
        assertNoDeadlock()
    }

    @Test
    fun `대조 — 정리 ①이 SKIP LOCKED 없이 홀드를 지우면 확정 K3·K7과 고리가 생긴다(40P01 · DS14-3)`() {
        assumeTrue(mode == ExpiryMode.LAZY)
        val closed = newSchedule(IntegrationTest.NOW.minusSeconds(60)).id!!
        val seat = seat(SeatStatus.HELD, closed)
        val h = dbHold(seat, 1, -1.0, closed)
        rawConnection().use { k3 ->
            k3.exec("SELECT id FROM seat_hold WHERE id = ? FOR UPDATE", h)
            val cleaner = async {
                rawConnection().use { c ->
                    try {
                        c.exec("SELECT starts_at FROM product_schedule WHERE id = ? FOR UPDATE", closed)
                        c.exec("DELETE FROM seat_hold WHERE id = ?", h) // K3가 쥔 행을 기다린다
                        c.rollback()
                        "ok"
                    } catch (e: SQLException) {
                        c.rollback()
                        e.sqlState
                    }
                }
            }
            awaitWaitersOf(k3.pid())
            val confirmer = try {
                k3.exec(
                    "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, 1, 'p', 'CONFIRMED', now())", closed, seat,
                )
                "ok"
            } catch (e: SQLException) {
                e.sqlState
            } finally {
                k3.rollback()
            }
            val states = setOf(confirmer, cleaner.get(30, TimeUnit.SECONDS))
            assertTrue("40P01" in states, "고리가 있어야 한다(설계가 SKIP LOCKED로 막는 자리): $states")
        }
    }

    private fun cleanup() = QuotaCleanup(
        jdbcTemplate, tx,
        properties.copy(counterDrift = properties.counterDrift.copy(cleanup = SeatHoldProperties.Cleanup(mode = SeatHoldProperties.CleanupMode.SCHEDULE))),
        meterRegistry, clock,
    )
}

@LazyMode
class Adr014ImplConcurrencyLazyTest : Adr014ImplConcurrencyTest()

@LazySweepMode
class Adr014ImplConcurrencyLazySweepTest : Adr014ImplConcurrencyTest()

@DerivedMode
class Adr014ImplConcurrencyDerivedTest : Adr014ImplConcurrencyTest()
