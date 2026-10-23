package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.drift.CounterReconciler
import com.jun.labs.seatreservation.service.impl.drift.CounterRepairProbe
import com.jun.labs.seatreservation.service.impl.drift.DriftAction
import com.jun.labs.seatreservation.service.impl.drift.ReconcileRun
import com.jun.labs.seatreservation.support.insertReservationRow
import com.jun.labs.seatreservation.support.insertSeatRow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * ADR-012 R8(task 04c) — 탐지 쿼리 모양 동등성 blind 테스트. 계약 = ADR-012 §4.1 'R8 설계' · 명세 §2 '탐지 쿼리 모양' · §5 '탐지 모양 동등성'.
 *
 * 손잡이 계약(구현 전 가정): 설정 `seat.hold.counter-drift.reconcile.detect-shape` = one-shot(코드 기본 · IntegrationTest 고정값) | split(업무 앱 기본값 — ADR-012 교체 뒤) | group,
 * Kotlin = `SeatHoldProperties.counterDrift.reconcile.detectShape`, 타입 `DetectShape { ONE_SHOT, SPLIT, GROUP }`.
 * 타입이 놓인 자리를 가정하는 곳은 아래 별칭 한 줄뿐이다(CleanupMode처럼 SeatHoldProperties 안 중첩 enum으로 가정) — 다르면 이 줄만 고친다.
 *
 * 동등성은 '모양별 컨텍스트(@TestPropertySource)에서 같은 시나리오 → 손으로 계산한 같은 정답'으로 본다(세 모양이 모두 같은 정답과 같으면 서로 같다).
 * 손잡이를 무시해도 정답은 맞으므로, 손잡이가 실제로 쓰였다는 것은 바인딩 단언 + 잠금 대기 중인 탐지 문장의 모양(pg_stat_activity)으로 따로 본다.
 * 대조 스케줄러는 끔(IntegrationTest의 PIN_C_OFF 그대로) — 백그라운드 바퀴가 잠금·빌림 단언에 끼지 않는다. runOnce를 직접 부른다.
 */
typealias DetectShape = com.jun.labs.seatreservation.service.impl.drift.DetectShape

/** 테스트가 켤 때만 움직이는 이음새 — 켜지 않으면 아무것도 하지 않는다. */
class ArmedRepairProbe : CounterRepairProbe {
    @Volatile var target: Pair<Long, Long>? = null
    @Volatile var action: (() -> Unit)? = null
    val fired = AtomicBoolean(false)

    override fun afterDetect(scheduleId: Long, userId: Long, observedCnt: Int?, observedActual: Int) {
        val a = action ?: return
        if (target == (scheduleId to userId) && fired.compareAndSet(false, true)) a()
    }

    fun reset() { target = null; action = null; fired.set(false) }
}

@TestConfiguration(proxyBeanMethods = false)
class ArmedRepairProbeConfig {
    @Bean fun armedRepairProbe(): ArmedRepairProbe = ArmedRepairProbe()
}

abstract class DetectShapeScenario(private val expectedShape: DetectShape) : DriftTestSupport() {

    @Autowired lateinit var reconciler: CounterReconciler
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var probe: ArmedRepairProbe

    private lateinit var pool: ExecutorService

    @BeforeEach
    fun setUpShape() {
        probe.reset()
        pool = Executors.newCachedThreadPool()
    }

    @AfterEach
    fun tearDownShape() {
        probe.reset()
        pool.shutdownNow()
    }

    private var seatNo = 5_000
    private fun seat(scheduleId: Long) = jdbcTemplate.insertSeatRow(scheduleId, ++seatNo)
    private fun reservation(scheduleId: Long, userId: Long, status: String = "CONFIRMED") =
        jdbcTemplate.insertReservationRow(scheduleId, seat(scheduleId), userId, status)

    /** (회차, 사용자, kind, 탐지 때 cnt, 실제 값, 처리) — run.outcomes의 비교용 모양 */
    private fun ReconcileRun.outcomeTuples(): List<List<Any?>> =
        outcomes.map { listOf(it.scheduleId, it.userId, it.kind.value, it.cntBefore, it.actual, it.action.value) }

    private fun ReconcileRun.counters(): Map<String, Number> = mapOf(
        "schedules" to schedules, "chunks" to chunks, "pairsScanned" to pairsScanned, "orphanChunks" to orphanChunks,
        "found" to found, "repaired" to repaired, "falsePositives" to falsePositives, "skipped" to skipped,
        "skippedClosed" to skippedClosed, "skippedSchedules" to skippedSchedules, "skippedPairs" to skippedPairs,
        "skippedChunks" to skippedChunks, "errors" to errors, "borrows" to borrows,
    )

    // ── 0. 손잡이 바인딩 ─────────────────────────────────────────────────────────

    @Test
    fun `손잡이 — 이 컨텍스트의 detectShape 바인딩(지정 안 하면 ONE_SHOT)`() {
        assertEquals(expectedShape, properties.counterDrift.reconcile.detectShape)
        assertEquals(3, properties.counterDrift.reconcile.chunkSize, "전제: 경계를 보려고 k = 3")
    }

    // ── 1. 동등성 — 같은 DB 상태 → 같은 어긋난 쌍 집합·복구 결과·바퀴 카운터 ─────────────

    /**
     * k = 3. S1(기본 회차) 쿼터 행 6개 = k의 배수(3 + 3 + 빈 청크) · S2 5개 = 3 + 마지막 부분 청크 2 · S3 쿼터 행 0개(빈 청크 1개) · SC 마감.
     *
     * S1: 101 cnt 2 / 홀드 1 → above(2,1) · 102 cnt 0 / 홀드 1 + 확정 1 → below(0,2) · 103 cnt 0 / 없음 → 맞음(cnt 0 행)
     *     104 cnt 1 / 확정 1 + 취소 1 → 맞음(비CONFIRMED 안 셈) · 105 cnt 2 / 확정 1 + PENDING 1 → above(2,1)
     *     106 cnt 1 / 홀드는 S2에만 2 → above(1,0)(다른 회차 홀드 안 셈) · 107 쿼터 행 없음 / 홀드 1 → missing(②)
     * S2: 106 cnt 2 / 홀드 2 → 맞음 · 201 cnt 1 / 만료 지난 홀드 1 → 맞음(expires_at으로 거르지 않음) · 202 cnt 3 / 홀드 1 + 확정 1 → above(3,2)
     *     203 cnt 1 / 홀드 2 → below(1,2) · 204 cnt 0 / 없음 → 맞음 · 101 쿼터 행 없음 / 확정 1 → missing(② 예약 갈래)
     * SC(마감): 301 cnt 5 / 없음 · 302 쿼터 행 없음 / 홀드 1 — 둘 다 제외(skippedSchedules 1 · skippedPairs 1)
     */
    private data class Seeded(val s1: Long, val s2: Long, val s3: Long, val sc: Long)

    private fun seedEquivalence(): Seeded {
        val s1 = schedule.id!!
        val s2 = newSchedule(farFuture(1)).id!!
        val s3 = newSchedule(farFuture(2)).id!!
        val sc = newSchedule(NOW.minusSeconds(3_600)).id!!
        check(s1 < s2 && s2 < s3) { "전제: 회차 id 순서 S1 < S2 < S3" }

        quota(s1, 101, 2); rawHold(s1, 101)
        quota(s1, 102, 0); rawHold(s1, 102); reservation(s1, 102)
        quota(s1, 103, 0)
        quota(s1, 104, 1); reservation(s1, 104); reservation(s1, 104, "CANCELLED")
        quota(s1, 105, 2); reservation(s1, 105); reservation(s1, 105, "PENDING")
        quota(s1, 106, 1)
        rawHold(s1, 107)

        quota(s2, 106, 2); rawHold(s2, 106); rawHold(s2, 106)
        quota(s2, 201, 1); rawHold(s2, 201, expiresAt = NOW.minusSeconds(3_600))
        quota(s2, 202, 3); rawHold(s2, 202); reservation(s2, 202)
        quota(s2, 203, 1); rawHold(s2, 203); rawHold(s2, 203)
        quota(s2, 204, 0)
        reservation(s2, 101)

        quota(sc, 301, 5)
        rawHold(sc, 302)
        return Seeded(s1, s2, s3, sc)
    }

    private fun farFuture(days: Long) = SCHEDULE_STARTS_AT.plus(Duration.ofDays(days))

    @Test
    fun `동등성 — 어긋난 쌍 집합(쌍·cnt·실제 값·kind)과 복구 결과가 손 계산 정답과 같다`() {
        val d = seedEquivalence()

        val run = reconciler.runOnce("reconcile-app")

        val expected = listOf(
            listOf(d.s1, 101L, "above", 2, 1, "repaired"),
            listOf(d.s1, 102L, "below", 0, 2, "repaired"),
            listOf(d.s1, 105L, "above", 2, 1, "repaired"),
            listOf(d.s1, 106L, "above", 1, 0, "repaired"),
            listOf(d.s1, 107L, "missing", null, 1, "repaired"),
            listOf(d.s2, 101L, "missing", null, 1, "repaired"),
            listOf(d.s2, 202L, "above", 3, 2, "repaired"),
            listOf(d.s2, 203L, "below", 1, 2, "repaired"),
        )
        assertEquals(expected, run.outcomeTuples(), "shape=$expectedShape run=$run")
        assertEquals(
            expected.map { listOf(it[0], it[1], it[2], it[3], it[4], it[5], null, "reconcile-app") },
            jdbcTemplate.query(
                "SELECT schedule_id, user_id, kind, cnt_before, actual, action, reason, source FROM counter_drift_log ORDER BY schedule_id, user_id",
            ) { rs, _ ->
                listOf<Any?>(rs.getLong(1), rs.getLong(2), rs.getString(3), (rs.getObject(4) as Number?)?.toInt(), rs.getInt(5), rs.getString(6), rs.getString(7), rs.getString(8))
            },
            "기록 1행씩 — 정답 쌍 밖의 쌍(맞는 쌍·마감 회차)은 기록되지 않는다",
        )
        // 복구 뒤: 열린 회차의 모든 쌍 cnt = 실제, 마감 회차는 그대로
        listOf(d.s1 to listOf(101L, 102L, 103L, 104L, 105L, 106L, 107L), d.s2 to listOf(101L, 106L, 201L, 202L, 203L, 204L)).forEach { (s, users) ->
            users.forEach { u -> assertEquals(actualOf(s, u), cntOf(s, u) ?: 0, "회차 $s 사용자 $u") }
        }
        assertEquals(5, cntOf(d.sc, 301L), "마감 회차는 고치지 않는다")
        assertEquals(null, cntOf(d.sc, 302L), "마감 회차의 행 없음 쌍을 만들지 않는다")
    }

    @Test
    fun `동등성 — 바퀴 카운터(chunks·pairsScanned·found·borrows 등)가 손 계산 정답과 같다, borrows = Hikari acquire`() {
        seedEquivalence()
        val acq0 = hikariAcquires()
        val b0 = counter(CounterReconciler.BORROWS_METRIC)

        val run = reconciler.runOnce("reconcile-app")

        val dAcq = hikariAcquires() - acq0
        // chunks: S1 3(3 + 3 + 빈 청크 — k의 배수) + S2 2(3 + 부분 2) + S3 1(쿼터 행 0 → 빈 청크 1) = 6 · pairsScanned 6 + 5 + 0 = 11
        // orphanChunks: 홀드 id 범위 1 + 예약 id 범위 1(m = 10000) · borrows = 회차 1 + 청크 6 + 범위 1 + 행 없음 2 + 복구 8 + 바퀴 기록 1 = 19
        assertEquals(
            mapOf<String, Number>(
                "schedules" to 3, "chunks" to 6, "pairsScanned" to 11L, "orphanChunks" to 2,
                "found" to 8, "repaired" to 8, "falsePositives" to 0, "skipped" to 0,
                "skippedClosed" to 0, "skippedSchedules" to 1, "skippedPairs" to 1L,
                "skippedChunks" to 0, "errors" to 0, "borrows" to 19L,
            ),
            run.counters(),
            "shape=$expectedShape run=$run",
        )
        assertEquals(dAcq, run.borrows, "빌림 = Hikari acquire 증가분(탐지 트랜잭션도 1청크 = 빌림 1)")
        assertEquals(dAcq.toDouble(), counter(CounterReconciler.BORROWS_METRIC) - b0, "빌림 지표 증가분")
        val row = jdbcTemplate.queryForMap("SELECT chunks, pairs_scanned, found, borrows, errors, skipped_chunks FROM counter_reconcile_run ORDER BY id DESC LIMIT 1")
        assertEquals(listOf<Long>(6, 11, 8, 19, 0, 0), row.values.map { (it as Number).toLong() }, "바퀴 기록 1행: $row")
    }

    @Test
    fun `동등성 — 어긋남 0인 회차들도 청크 경계·카운터가 같다(k 배수 회차의 빈 마지막 청크)`() {
        val s1 = schedule.id!!
        (1L..6L).forEach { u -> quota(s1, u, 1); rawHold(s1, u) } // 6 = 2k, 전부 맞음

        val run = reconciler.runOnce("reconcile-app")

        assertEquals(0, run.found, "$run")
        assertEquals(3, run.chunks, "3 + 3 + 빈 청크")
        assertEquals(6L, run.pairsScanned)
        assertEquals(0, run.errors)
        assertTrue(run.outcomes.isEmpty())
    }

    // ── 2. 한 스냅샷 — 탐지 청크의 조회 사이에 커밋된 쓰기를 섞지 않는다 · 손잡이가 실제로 쓰였다 ──────────

    /** [blocker] 세션이 쥔 잠금을 기다리는 다른 세션 1개(pid, 문장, 트랜잭션 시작, 문장 시작). */
    private data class Waiter(val pid: Int, val query: String, val xactStart: Timestamp?, val queryStart: Timestamp?)

    private fun awaitBlockedBy(blocker: Int, timeout: Duration = Duration.ofSeconds(15)): Waiter {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val w = jdbcTemplate.query(
                "SELECT pid, query, xact_start, query_start FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                { rs, _ -> Waiter(rs.getInt(1), rs.getString(2), rs.getTimestamp(3), rs.getTimestamp(4)) },
                blocker,
            ).firstOrNull()
            if (w != null) return w
            check(System.nanoTime() < deadline) { "세션 $blocker 의 잠금을 기다리는 세션이 ${timeout} 안에 나타나지 않았다" }
            Thread.sleep(10)
        }
    }

    private fun Connection.pid(): Int = createStatement().use { st -> st.executeQuery("SELECT pg_backend_pid()").use { it.next(); it.getInt(1) } }

    private fun Connection.exec(sql: String, vararg args: Any) = prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.execute()
    }

    private fun normalized(sql: String) = sql.lowercase().replace(Regex("\\s+"), "")

    /** 잠금 대기 중인 탐지 문장이 이 모양의 문장인가(ADR-012 §4.1 표) — 손잡이를 무시한 구현을 가른다. */
    private fun assertDetectStatementShape(w: Waiter) {
        val q = normalized(w.query)
        assertTrue("seat_hold" in q, "대기 문장은 홀드를 읽는 탐지 문장이어야 한다: ${w.query}")
        when (expectedShape) {
            DetectShape.ONE_SHOT -> {
                assertTrue("user_hold_quota" in q, "one-shot = 쿼터 행 + 상관 서브쿼리 한 문장: ${w.query}")
            }
            DetectShape.SPLIT, DetectShape.GROUP -> {
                assertFalse("user_hold_quota" in q, "split/group의 홀드 조회는 쿼터 테이블을 조인하지 않는다: ${w.query}")
                assertTrue("=any(" in q, "split/group의 홀드 조회 = user_id = ANY(U): ${w.query}")
                if (expectedShape == DetectShape.GROUP) {
                    assertTrue("groupby" in q, "group = GROUP BY user_id 개수: ${w.query}")
                } else {
                    assertFalse("groupby" in q, "split = 행 그대로(GROUP BY 없음): ${w.query}")
                }
                val xs = assertNotNull(w.xactStart); val qs = assertNotNull(w.queryStart)
                assertTrue(xs.before(qs), "split/group의 홀드 조회는 쿼터 조회 뒤 같은 트랜잭션 안의 문장이어야 한다(xact_start $xs < query_start $qs)")
            }
        }
    }

    @Test
    fun `한 스냅샷 — 쿼터 조회 뒤 홀드 조회 전에 커밋된 (홀드 +1 · cnt +1)은 탐지에 섞이지 않는다(오탐 0), 진짜 어긋남은 찾는다`() {
        val s = schedule.id!!
        quota(s, 501, 1); rawHold(s, 501)     // 맞음 — 대기 중 다른 세션이 홀드 +1 · cnt 2로 함께 커밋(여전히 맞음)
        quota(s, 502, 3); rawHold(s, 502)     // 진짜 어긋남 above(3,1)
        val spareSeat = seat(s)

        dataSource.connection.use { lock ->
            lock.autoCommit = false
            lock.exec("LOCK TABLE seat_hold IN ACCESS EXCLUSIVE MODE")
            val running: Future<ReconcileRun> = pool.submit<ReconcileRun> { reconciler.runOnce("reconcile-app") }
            try {
                val w = awaitBlockedBy(lock.pid())
                assertDetectStatementShape(w)
                // 탐지가 홀드 조회에서 기다리는 동안: 501에 홀드 1을 더하고 cnt도 2로 — 커밋 뒤에도 501은 맞다
                lock.exec(
                    "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                    spareSeat, s, 501L, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(300)),
                )
                lock.exec("UPDATE user_hold_quota SET cnt = 2 WHERE schedule_id = ? AND user_id = ?", s, 501L)
                lock.commit()
            } catch (e: Throwable) {
                lock.rollback()
                throw e
            }
            val run = running.get(30, TimeUnit.SECONDS)

            // 스냅샷이 둘로 갈라지면(쿼터 = 커밋 전 cnt 1, 홀드 = 커밋 뒤 2) 501이 below로 잡혀 false-positive가 남는다
            assertEquals(listOf(listOf<Any?>(s, 502L, "above", 3, 1, "repaired")), run.outcomeTuples(), "shape=$expectedShape run=$run")
            assertEquals(0, run.falsePositives)
            assertTrue(jdbcTemplate.queryForList("SELECT 1 FROM counter_drift_log WHERE user_id = 501").isEmpty(), "501이 기록됐다(한 스냅샷 위반)")
            assertEquals(2, cntOf(s, 501L))
            assertEquals(1, cntOf(s, 502L))
        }
    }

    // ── 2b. 복구 트랜잭션은 READ COMMITTED 그대로 ─────────────────────────────────────

    /**
     * 601: cnt 3 / 홀드 1 → above로 탐지. 이음새(탐지 뒤·복구 전)에서 다른 세션이 601 쿼터 행을 FOR UPDATE로 쥐고, 복구 트랜잭션이 그 행 잠금을
     * 기다리기 시작하면(복구 트랜잭션의 첫 문장은 이미 지났다) 홀드 2를 더하고 cnt = 3으로 쓴 뒤 커밋한다(앱 선점 경로 흉내 — 이제 맞음).
     * READ COMMITTED: 잠금 뒤 최신 행(cnt 3) + 새 문장으로 센 실제 3 → false-positive · 쓰기 0.
     * REPEATABLE READ였다면: 동시 갱신된 행의 FOR UPDATE → 40001 skipped(또는 옛 스냅샷으로 실제 1 → cnt를 1로 덮는 오복구).
     */
    @Test
    fun `복구 격리 — 복구 트랜잭션은 READ COMMITTED(잠금 대기 중 커밋된 쓰기를 본다 → false-positive, 40001·오복구 없음)`() {
        val s = schedule.id!!
        quota(s, 601, 3); rawHold(s, 601)
        val seats = listOf(seat(s), seat(s))
        val lockHeld = CountDownLatch(1)
        val writer = AtomicBoolean(false)
        var writerFuture: Future<*>? = null
        probe.target = s to 601L
        probe.action = {
            writerFuture = pool.submit {
                dataSource.connection.use { c ->
                    c.autoCommit = false
                    try {
                        c.exec("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", s, 601L)
                        lockHeld.countDown()
                        awaitBlockedBy(c.pid())
                        seats.forEach { seatId ->
                            c.exec(
                                "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                                seatId, s, 601L, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(300)),
                            )
                        }
                        c.exec("UPDATE user_hold_quota SET cnt = 3 WHERE schedule_id = ? AND user_id = ?", s, 601L)
                        c.commit()
                        writer.set(true)
                    } catch (e: Throwable) {
                        c.rollback()
                        throw e
                    }
                }
            }
            check(lockHeld.await(10, TimeUnit.SECONDS)) { "쓰기 세션이 쿼터 행을 잠그지 못했다" }
        }

        val run = reconciler.runOnce("reconcile-app")
        assertNotNull(writerFuture, "이음새가 불리지 않았다(601을 탐지하지 못했다): $run").get(30, TimeUnit.SECONDS)

        assertTrue(writer.get(), "쓰기 세션이 커밋하지 못했다")
        // 처리 결과의 cnt·실제 = 복구 트랜잭션이 잠근 뒤 본 값(3, 3) — kind는 탐지 때 본 모양(above)
        assertEquals(listOf(listOf<Any?>(s, 601L, "above", 3, 3, "false-positive")), run.outcomeTuples(), "shape=$expectedShape run=$run")
        assertEquals(DriftAction.FALSE_POSITIVE, run.outcomes.single().action, "복구 트랜잭션이 READ COMMITTED가 아니다: ${run.outcomes}")
        assertEquals(0, run.skipped)
        assertEquals(3, cntOf(s, 601L), "다시 세면 맞는 쌍을 덮었다")
        assertEquals(3, actualOf(s, 601L))
        assertEquals(listOf(listOf<Any?>(601L, "above", "false-positive", null, "reconcile-app")), driftLog())
    }

    // ── 3. 실패 — 청크 조회 하나가 실패하면 그 청크를 통째로 버린다(부분 집계로 어긋남을 내지 않는다) ─────────

    /**
     * S1(첫 회차): 701 cnt 5 / 홀드 1(청크 1의 어긋남) · 702·703 맞음 · 704 cnt 9 / 없음(청크 2의 어긋남) · 705 쿼터 행 없음 / 홀드 1(② 몫).
     * S2: 711 cnt 4 / 홀드 1 → above. seat_hold를 ACCESS EXCLUSIVE로 쥐고, S1 첫 청크의 홀드 조회가 기다리면 그 문장을 취소(57014)한다 —
     * one-shot은 한 문장이, split/group은 쿼터 조회(ⓐ)는 끝나고 홀드 조회(ⓑ)가 실패한다.
     * 기대(셋 다 같음): errors 1 · skippedChunks 1 · S1 남은 청크 건너뜀(704도 안 봄) · 701·704 미탐지·그대로 · ②(705)와 S2(711)는 정상.
     */
    @Test
    fun `실패 — 청크의 홀드 조회가 실패하면 그 청크는 버리고 errors·skipped_chunks, 그 회차 남은 청크 건너뜀, 부분 집계 어긋남 0`() {
        val s1 = schedule.id!!
        val s2 = newSchedule(farFuture(1)).id!!
        quota(s1, 701, 5); rawHold(s1, 701)
        quota(s1, 702, 1); rawHold(s1, 702)
        quota(s1, 703, 0)
        quota(s1, 704, 9)
        rawHold(s1, 705)
        quota(s2, 711, 4); rawHold(s2, 711)
        val chunkErrors0 = counter(CounterReconciler.ERRORS_METRIC, "stage", "chunk")
        val skippedChunks0 = counter(CounterReconciler.SKIPPED_CHUNKS_METRIC)

        val run = dataSource.connection.use { lock ->
            lock.autoCommit = false
            lock.exec("LOCK TABLE seat_hold IN ACCESS EXCLUSIVE MODE")
            val running: Future<ReconcileRun> = pool.submit<ReconcileRun> { reconciler.runOnce("reconcile-app") }
            try {
                val w = awaitBlockedBy(lock.pid())
                assertDetectStatementShape(w)
                assertEquals(true, jdbcTemplate.queryForObject("SELECT pg_cancel_backend(?)", Boolean::class.java, w.pid))
            } finally {
                lock.rollback()
            }
            running.get(30, TimeUnit.SECONDS)
        }

        assertEquals(1, run.errors, "shape=$expectedShape run=$run")
        assertEquals(1, run.skippedChunks, "$run")
        assertEquals(1.0, counter(CounterReconciler.ERRORS_METRIC, "stage", "chunk") - chunkErrors0, "errors{stage=chunk}")
        assertEquals(1.0, counter(CounterReconciler.SKIPPED_CHUNKS_METRIC) - skippedChunks0)
        assertEquals(
            listOf(listOf<Any?>(s1, 705L, "missing", null, 1, "repaired"), listOf<Any?>(s2, 711L, "above", 4, 1, "repaired")),
            run.outcomeTuples(),
            "실패한 청크(701)·건너뛴 청크(704)의 쌍은 탐지되지 않는다 — ②·다음 회차는 정상: $run",
        )
        // 실패한 청크는 세지 않는다(완료 청크만) — S2의 청크 1개 · 쌍 1 · borrows = 회차 1 + 실패 청크 1 + S2 청크 1 + 범위 1 + 홀드 행 없음 1 + 복구 2 + 기록 1
        assertEquals(1, run.chunks, "$run")
        assertEquals(1L, run.pairsScanned, "$run")
        assertEquals(8L, run.borrows, "$run")
        assertEquals(5, cntOf(s1, 701L))
        assertEquals(9, cntOf(s1, 704L))
        assertTrue(jdbcTemplate.queryForList("SELECT 1 FROM counter_drift_log WHERE user_id IN (701, 702, 703, 704)").isEmpty())
        assertEquals(
            listOf<Long>(1, 1),
            jdbcTemplate.queryForMap("SELECT errors, skipped_chunks FROM counter_reconcile_run ORDER BY id DESC LIMIT 1").values.map { (it as Number).toLong() },
        )
    }
}

private const val K3 = "seat.hold.counter-drift.reconcile.chunk-size=3"
private const val LOCK_5S = "seat.hold.counter-drift.reconcile.lock-timeout=5s"

@TestPropertySource(properties = [K3, LOCK_5S, "seat.hold.counter-drift.reconcile.detect-shape=one-shot"])
@Import(ArmedRepairProbeConfig::class)
class DetectShapeOneShotTest : DetectShapeScenario(DetectShape.ONE_SHOT)

@TestPropertySource(properties = [K3, LOCK_5S, "seat.hold.counter-drift.reconcile.detect-shape=split"])
@Import(ArmedRepairProbeConfig::class)
class DetectShapeSplitTest : DetectShapeScenario(DetectShape.SPLIT)

@TestPropertySource(properties = [K3, LOCK_5S, "seat.hold.counter-drift.reconcile.detect-shape=group"])
@Import(ArmedRepairProbeConfig::class)
class DetectShapeGroupTest : DetectShapeScenario(DetectShape.GROUP)

/** 손잡이 미지정 = one-shot(IntegrationTest 고정값 — 업무 앱 기본값은 split(명세 §10 K1) · 같은 정답 · 대기 문장이 one-shot 모양). */
@TestPropertySource(properties = [K3, LOCK_5S])
@Import(ArmedRepairProbeConfig::class)
class DetectShapeDefaultTest : DetectShapeScenario(DetectShape.ONE_SHOT)
