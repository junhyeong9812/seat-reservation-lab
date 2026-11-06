package com.jun.labs.seatreservation.adr015fix

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015Base
import com.jun.labs.seatreservation.adr015.Adr015Gate
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.Adr015Taps
import com.jun.labs.seatreservation.adr015.BATCH_THREAD
import com.jun.labs.seatreservation.adr015.L_S
import com.jun.labs.seatreservation.adr015.M_CONDITIONAL
import com.jun.labs.seatreservation.adr015.M_CURRENT
import com.jun.labs.seatreservation.adr015.M_EXTEND
import com.jun.labs.seatreservation.adr015.M_OPTIMISTIC
import com.jun.labs.seatreservation.adr015.M_PESSIMISTIC
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_LOC_OPTIMISTIC
import com.jun.labs.seatreservation.adr015.P_MIN_REMAINING
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.P_WINDOW
import com.jun.labs.seatreservation.adr015.TICK_METRIC
import com.jun.labs.seatreservation.adr015.TapEvent
import com.jun.labs.seatreservation.adr015.TapKind
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties.CleanupMode
import com.jun.labs.seatreservation.service.impl.drift.CleanupRun
import com.jun.labs.seatreservation.service.impl.drift.QuotaCleanup
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import java.sql.Timestamp
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * ADR-015 구현 리뷰 R15-12(fix verification — blind 파일 무수정 · Adr015Support/Base 도우미는 호출만): §3.5 교착 점검표의 짝 중
 * 실행 증거가 없던 것 — 정리 ①(마감 회차 정리 — 업무 앱 기본 켜짐 · QuotaCleanup.cleanSchedule)과 선점.
 * 지금 ADR-015 레이스 컨텍스트는 모두 PIN_CLEANUP_OFF(seat.hold.counter-drift.cleanup.mode=none)라 정리 ①이 없다 → 여기서는 cleanup.mode=schedule로 켜고
 * (interval=1h — 스케줄러가 테스트 중 스스로 돌지 않게) 정리를 QuotaCleanup.runOnce()로 정리 스레드에서 직접 부른다.
 *
 * 칸(§3.5 짝 · #9):
 *  (a1) 확정 ↔ 정리 ① — 확정이 L-S(좌석 문장 앞 — 새 모드는 홀드 행 · current는 예약 INSERT FK KEY SHARE + 좌석 앞)를 쥔 채 정리 ①이 같은 마감 회차를 정리
 *  (a2) 확정 ↔ 정리 ① — 정리 ①이 회차 행 FOR UPDATE를 쥔 채(L-Q: 홀드 존재 확인 앞) 확정이 진행 → 확정의 예약 INSERT FK 검사가 회차 행에서 기다림(ADR 표의 그 줄)
 *  (b1) A′ ↔ 정리 ① — A′가 쿼터 행 · 홀드 행(B3 지움)을 쥔 채 B5 앞에서 멈춘 동안 정리 ①
 *  (b2) A′ ↔ 정리 ① — 정리 ①이 회차 행을 쥔 채(L-Q) A′ 배치 전체
 *  (c)  A′ ↔ 선점 — Y 선점이 자기 쿼터 행을 쥔 채 좌석 NOWAIT 앞(L-S)에서 멈춘 동안 A′ B2가 그 쿼터 행에서 기다림
 * 기대: 40P01 0 · 계약 응답 코드(확정 200 · 선점 201) · 배치 틱 커밋 · 정리 errors 0 · 판정기 0.
 * 판정기 예외 하나: 정리 ①이 마감 회차의 쿼터 행을 지운 칸(확정 쌍 포함 — ADR-009 정리 ① 정의 · 운영 판정기는 마감 회차를 뺀다)은 v_counter_mismatch를 빼고 본다
 * (Adr015Base.verdict는 '마감 회차 없음' 전제 — 그 칸에서는 쿼터 행이 정리로 지워졌음을 따로 단언한다).
 */

const val P_CLEANUP_SCHEDULE = "seat.hold.counter-drift.cleanup.mode=schedule"
const val P_CLEANUP_INTERVAL_LONG = "seat.hold.counter-drift.cleanup.interval=1h"
const val CLEANUP_THREAD = "adr015-cleanup"

/** L-Q(정리 ① — 회차 행 FOR UPDATE 뒤 · 홀드 존재 확인 앞): 정리 스레드의 `select exists (select 1 from seat_hold …` 앞 */
val L_Q: (TapEvent) -> Boolean = { e -> e.kind == TapKind.SQL && e.thread == CLEANUP_THREAD && e.sql!!.startsWith("select exists (select 1 from seat_hold") }

/** L-B5(A′ — B3 홀드 DELETE 뒤 · B5 좌석 UPDATE 앞 · 쿼터 행 · 홀드 행 잠금을 쥔 채): 배치 스레드의 `update product_seat` 앞 */
val L_B5: (TapEvent) -> Boolean = { e -> e.kind == TapKind.SQL && e.thread == BATCH_THREAD && e.sql!!.startsWith("update product_seat") }

abstract class Adr015CleanupDeadlockScenarios(val mode: String) : Adr015Base() {

    @Autowired lateinit var quotaCleanup: QuotaCleanup
    @Autowired lateinit var holdProperties: SeatHoldProperties

    private val cleanupPool: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, CLEANUP_THREAD) }

    val isCurrent get() = mode == "current"

    @BeforeEach
    fun cleanupOn() {
        // 그린 위장 점검 — 이 컨텍스트는 정리 ①이 정말 켜져 있어야 한다
        assertEquals(CleanupMode.SCHEDULE, quotaCleanupMode(), "[$mode] 정리 ①(cleanup.mode=schedule) 컨텍스트여야 한다")
    }

    private var deadlocks0 = 0L

    @BeforeEach
    fun deadlockBaseline() {
        deadlocks0 = dbDeadlocks()
    }

    /** pg_stat_database.deadlocks(이 DB에서 교착 탐지로 롤백된 수) — 통계는 백엔드가 늦게(≤ 1초) 내보내므로 잠깐 기다린 뒤 읽는다 */
    private fun dbDeadlocks(): Long {
        Thread.sleep(1_100)
        return jdbcTemplate.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long::class.java)!!
    }

    /** 응답 · 예외로 보이지 않는 교착(다른 쪽이 재시도 · 삼킴)까지 — DB 교착 탐지 수가 그대로여야 한다 */
    fun assertNoDbDeadlock(why: String) = assertEquals(deadlocks0, dbDeadlocks(), "[$mode] $why — pg_stat_database.deadlocks 증가(40P01)")

    @AfterEach
    fun cleanupPoolDown() {
        Adr015Taps.reset()
        cleanupPool.shutdown()
        cleanupPool.awaitTermination(40, TimeUnit.SECONDS)
    }

    private fun quotaCleanupMode(): CleanupMode = holdProperties.counterDrift.cleanup.mode

    fun cleanupAsync(): Future<Result<CleanupRun>> = cleanupPool.submit<Result<CleanupRun>> { runCatching { quotaCleanup.runOnce() } }

    /** 회차를 마감으로(starts_at = NOW ≤ 앱 시계) — 홀드는 열린 회차에서 미리 만든다(선점은 마감 회차를 거부) */
    fun closeSchedule() {
        jdbcTemplate.update("UPDATE product_schedule SET starts_at = ? WHERE id = ?", Timestamp.from(NOW), schedule.id)
    }

    fun deadlocks(vararg errs: Throwable?): Set<String> = errs.flatMap { sqlStates(it) }.filter { it == "40P01" }.toSet()

    /** 정리 한 번의 결과 단언 — 실패 0(교착 등은 errors로 센다 · QuotaCleanup.bySchedule) · 대상 회차 1 */
    fun assertCleanupClean(r: Result<CleanupRun>, why: String): CleanupRun {
        assertTrue(r.isSuccess, "[$mode] $why — 정리 예외: ${r.exceptionOrNull()}")
        val run = r.getOrThrow()
        assertEquals(0, run.errors, "[$mode] $why — 정리 ① errors 0(40P01 등 회차 트랜잭션 실패 없음): $run")
        assertEquals(1, run.schedules, "[$mode] $why — 대상 회차 1: $run")
        return run
    }

    /** 판정기 — 정리 ①이 마감 회차의 쿼터 행을 지웠으면 v_counter_mismatch를 빼고 본다(머리말) */
    fun assertVerdictCleanClosed(why: String, quotaDeletedByCleanup: Boolean) {
        val v = verdict().filterKeys { !(quotaDeletedByCleanup && it == "v_counter_mismatch") }
        assertTrue(v.values.all { it == 0L }, "[$mode] $why — 판정기 위반: ${v.filterValues { it != 0L }} · 상태 ${dbState()}")
    }

    // ── (a1) 확정이 L-S를 쥔 채 정리 ① ─────────────────────────────────────────────────────────────

    @Test
    fun `(a1) 확정 ↔ 정리 ① — 확정이 L-S(홀드 행 · current는 예약 FK)를 쥔 채 정리 ①이 같은 마감 회차를 정리해도 40P01 없음`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(1)))
        closeSchedule()

        val ls = Adr015Taps.arm(Adr015Gate("L-S"), L_S)
        val xf = confirmAsync(hold, x)
        reachedOrFail(ls, what = "L-S(확정 — 좌석 문장 앞)")

        val cf = cleanupAsync()
        if (isCurrent) {
            // current: 예약 INSERT(FK 검사 — 회차 KEY SHARE)가 좌석 문장보다 앞이다 → 정리 ①의 회차 FOR UPDATE가 확정을 기다린다
            assertTrue(awaitLockWait("from product_schedule"), "[current] 정리 ①이 확정이 쥔 회차 KEY SHARE에서 기다려야 한다(Lock 대기 관측 실패) · ${dbState()}")
            assertFalse(cf.isDone, "[current] 정리 ①은 확정 커밋 전에 끝나지 않는다")
        } else {
            // 새 모드: 확정은 아직 회차 행을 건드리지 않았다(FK 검사는 예약 INSERT — 좌석 뒤) → 정리 ①은 기다리지 않고, 지워졌지만 커밋 전인 홀드를 보고(RC) 아무것도 지우지 않는다
            val r = runCatching { cf.get(10, TimeUnit.SECONDS) }.getOrElse { e -> throw AssertionError("[$mode] 정리 ①이 확정(L-S)을 기다렸다 — §3.5 '확정 ↔ 정리 ①' 가정과 다르다 · ${dbState()}", e) }
            val run = assertCleanupClean(r, "(a1) 확정 L-S 중 정리")
            assertEquals(1, run.skippedWithHolds, "[$mode] 정리 ①은 확정이 쥔(커밋 전) 홀드를 보고 건너뛴다: $run")
            assertEquals(0, run.rowsDeleted, "[$mode] 정리 ①이 쿼터 행을 지우지 않는다: $run")
        }
        ls.release()
        val xr = xf.done()
        assertEquals(200, xr.status, "[$mode] (a1) 확정 200: $xr")
        val run = assertCleanupClean(cf.get(30, TimeUnit.SECONDS), "(a1)")
        val deleted = run.rowsDeleted > 0
        if (isCurrent) {
            // 확정 커밋 뒤 회차 잠금을 얻은 정리 ①은 새 문장으로 홀드 없음을 보고 그 회차 쿼터 행을 지운다(확정 쌍 포함 — ADR-009 정리 ①)
            assertEquals(0, run.skippedWithHolds, "[current] 확정 커밋 뒤 정리 ①은 홀드를 보지 않는다: $run")
            assertTrue(deleted, "[current] 정리 ①이 마감 회차 쿼터 행을 지운다: $run")
            assertNull(cntOf(x), "[current] 쿼터 행이 지워졌다")
        }
        assertEquals(1, confirmedOn(seat))
        assertEquals("RESERVED", seatStatusOf(seat))
        assertVerdictCleanClosed("(a1)", quotaDeletedByCleanup = deleted)
        assertNoDbDeadlock("(a1)")
    }

    // ── (a2) 정리 ①이 회차 행을 쥔 채 확정 ─────────────────────────────────────────────────────────

    @Test
    fun `(a2) 확정 ↔ 정리 ① — 정리 ①이 회차 행 FOR UPDATE를 쥔 채 확정의 예약 INSERT FK 검사가 기다려도 40P01 없음`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(1)))
        closeSchedule()

        val lq = Adr015Taps.arm(Adr015Gate("L-Q"), L_Q)
        val cf = cleanupAsync()
        reachedOrFail(lq, what = "L-Q(정리 ① — 회차 FOR UPDATE 뒤 · 홀드 확인 앞)")

        val xf = confirmAsync(hold, x)
        // 확정의 예약 INSERT(FK 검사 — 회차 FOR KEY SHARE)가 정리 ①의 회차 FOR UPDATE를 기다린다(새 모드는 홀드 · 좌석 행을 쥔 채 — §3.5 '확정 ↔ 정리 ①' 줄)
        assertTrue(awaitLockWait("insert into reservation"), "[$mode] 확정 예약 INSERT가 정리 ①의 회차 잠금에서 기다려야 한다(Lock 대기 관측 실패) · ${dbState()}")
        assertFalse(xf.isDone, "[$mode] 확정은 정리 ① 커밋 전에 끝나지 않는다")

        lq.release()
        val run = assertCleanupClean(cf.get(30, TimeUnit.SECONDS), "(a2)")
        assertEquals(1, run.skippedWithHolds, "[$mode] 정리 ①은 확정 중(커밋 전)인 홀드를 보고 건너뛴다: $run")
        assertEquals(0, run.rowsDeleted, "[$mode] 쿼터 행을 지우지 않는다: $run")
        val xr = xf.done()
        assertEquals(200, xr.status, "[$mode] (a2) 확정 200(40P01 없음): $xr")
        assertEquals(1, confirmedOn(seat))
        assertEquals("RESERVED", seatStatusOf(seat))
        assertEquals(1, cntOf(x), "[$mode] cnt = 확정 1(쿼터 행 남음)")
        assertVerdictCleanClosed("(a2)", quotaDeletedByCleanup = false)
        assertNoDbDeadlock("(a2)")
    }
}

/** 새 모드(A′ 배치가 도는 모드)만의 칸 — (b) A′ ↔ 정리 ① · (c) A′ ↔ 선점 */
abstract class Adr015CleanupDeadlockNewModeScenarios(mode: String) : Adr015CleanupDeadlockScenarios(mode) {

    @Test
    fun `(b1) A′ ↔ 정리 ① — A′가 쿼터 · 홀드 행을 쥔 채(B5 앞) 정리 ①은 기다리지 않고 건너뛴다 · 40P01 없음`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(6)))   // 홀드 만료(te = NOW + 5분)
        closeSchedule()
        val ok0 = counter(TICK_METRIC, "outcome", "ok") ?: 0.0

        val lb = Adr015Taps.arm(Adr015Gate("L-B5"), L_B5)
        val bf = batchAsync()
        reachedOrFail(lb, what = "L-B5(A′ — B3 뒤 · B5 좌석 UPDATE 앞)")

        val r = runCatching { cleanupAsync().get(10, TimeUnit.SECONDS) }
            .getOrElse { e -> throw AssertionError("[$mode] 정리 ①이 A′ 배치(B5 앞)를 기다렸다 — §3.5 'A′ ↔ 정리 ①' 가정(A′는 회차 행을 잡지 않는다)과 다르다 · ${dbState()}", e) }
        val run = assertCleanupClean(r, "(b1) A′ B5 앞 중 정리")
        assertEquals(1, run.skippedWithHolds, "[$mode] 정리 ①은 A′가 지운(커밋 전) 홀드를 보고 건너뛴다(RC): $run")
        assertEquals(0, run.rowsDeleted)

        lb.release()
        val br = bf.join()
        assertTrue(br.isSuccess, "[$mode] A′ 배치 틱 커밋: ${br.exceptionOrNull()}")
        assertTrue(deadlocks(br.exceptionOrNull()).isEmpty(), "[$mode] 40P01 0")
        assertEquals(ok0 + 1, requireCounter(TICK_METRIC, "outcome", "ok"))
        assertFalse(holdExists(hold))
        assertEquals("AVAILABLE", seatStatusOf(seat))
        assertEquals(0, cntOf(x), "[$mode] 감소 = 지운 행 1(쿼터 행 남음)")
        assertVerdictCleanClosed("(b1)", quotaDeletedByCleanup = false)
        assertNoDbDeadlock("(b1)")
    }

    @Test
    fun `(b2) A′ ↔ 정리 ① — 정리 ①이 회차 행을 쥔 채(L-Q) A′ 배치는 기다리지 않고 커밋 · 뒤의 정리가 쿼터 행을 지운다 · 40P01 없음`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(6)))
        closeSchedule()
        val ok0 = counter(TICK_METRIC, "outcome", "ok") ?: 0.0

        val lq = Adr015Taps.arm(Adr015Gate("L-Q"), L_Q)
        val cf = cleanupAsync()
        reachedOrFail(lq, what = "L-Q(정리 ① — 회차 FOR UPDATE 뒤)")

        val br = runCatching { batchAsync().get(10, TimeUnit.SECONDS) }
            .getOrElse { e -> throw AssertionError("[$mode] A′ 배치가 정리 ①의 회차 잠금을 기다렸다 — §3.5 'A′는 회차 행을 잡지 않는다'와 다르다 · ${dbState()}", e) }
        assertTrue(br.isSuccess, "[$mode] A′ 배치 틱 커밋(정리 ①이 회차를 쥔 동안): ${br.exceptionOrNull()}")
        assertTrue(deadlocks(br.exceptionOrNull()).isEmpty(), "[$mode] 40P01 0")
        assertEquals(ok0 + 1, requireCounter(TICK_METRIC, "outcome", "ok"))

        lq.release()
        val run = assertCleanupClean(cf.get(30, TimeUnit.SECONDS), "(b2)")
        assertEquals(0, run.skippedWithHolds, "[$mode] A′ 커밋 뒤 홀드 없음: $run")
        assertEquals(1, run.rowsDeleted, "[$mode] 정리 ①이 마감 회차 쿼터 행(X — cnt 0)을 지운다: $run")
        assertFalse(holdExists(hold))
        assertEquals("AVAILABLE", seatStatusOf(seat))
        assertNull(cntOf(x))
        assertVerdictCleanClosed("(b2)", quotaDeletedByCleanup = true)
        assertNoDbDeadlock("(b2)")
    }

    @Test
    fun `(c) A′ ↔ 선점 — Y 선점이 쿼터 행을 쥔 채(좌석 NOWAIT 앞) A′ B2가 그 행을 기다려도 40P01 없음 · 선점 201 · 감소 = 지운 행`() {
        val seat1 = createSeat(1).id!!
        val seat2 = createSeat(2).id!!
        val h1 = holdOk(seat1, y)                     // Y의 홀드 — 곧 만료
        clock.set(NOW.plus(Duration.ofMinutes(6)))    // h1 만료(te = NOW + 5분) — 열린 회차(선점 가능)
        val ok0 = counter(TICK_METRIC, "outcome", "ok") ?: 0.0

        // Y의 새 선점: 회차 FOR SHARE → 쿼터 upsert(Y 쿼터 행 잠금) → 좌석 FOR UPDATE NOWAIT 앞(L-S)에서 멈춤
        val ls = Adr015Taps.arm(Adr015Gate("L-S(선점)"), L_S)
        val yf = CompletableFuture.supplyAsync({ holdHttp(seat2, y) }, ctlPool)
        reachedOrFail(ls, what = "L-S(선점 — 쿼터 upsert 뒤 · 좌석 NOWAIT 앞)")

        val bf = batchAsync()
        assertTrue(awaitLockWait("for update of q"), "[$mode] A′ B2가 Y 선점이 쥔 쿼터 행에서 기다려야 한다(Lock 대기 관측 실패) · ${dbState()}")
        assertFalse(bf.isDone, "[$mode] B2가 기다리는 동안 배치 틱은 끝나지 않는다")

        ls.release()
        val yr = yf.get(60, TimeUnit.SECONDS)
        assertEquals(201, yr.status, "[$mode] (c) Y 선점 201(NOWAIT — 기다리지 않음): $yr")
        val br = bf.join()
        assertTrue(br.isSuccess, "[$mode] A′ 배치 틱 커밋: ${br.exceptionOrNull()}")
        assertTrue(deadlocks(br.exceptionOrNull()).isEmpty(), "[$mode] 40P01 0")
        assertEquals(ok0 + 1, requireCounter(TICK_METRIC, "outcome", "ok"))
        assertFalse(holdExists(h1), "[$mode] 만료 홀드 h1은 지워진다")
        assertEquals("AVAILABLE", seatStatusOf(seat1))
        assertEquals("HELD", seatStatusOf(seat2))
        assertEquals(1, cntOf(y), "[$mode] cnt(Y) = 새 홀드 1(선점 +1 · A′ 감소 = 지운 행 1)")
        assertVerdictClean("[$mode] (c)")
        assertNoDbDeadlock("(c)")
    }
}

// ── 컨텍스트: 레이스 컨텍스트(Adr015RaceTests.kt)와 같은 키에서 PIN_CLEANUP_OFF만 정리 ① 켬으로 ─────────────────────────

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, P_CLEANUP_SCHEDULE, P_CLEANUP_INTERVAL_LONG, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CURRENT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015CurrentCleanupDeadlockTest : Adr015CleanupDeadlockScenarios("current")

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, P_CLEANUP_SCHEDULE, P_CLEANUP_INTERVAL_LONG, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CONDITIONAL],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015ConditionalCleanupDeadlockTest : Adr015CleanupDeadlockNewModeScenarios("conditional")

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, P_CLEANUP_SCHEDULE, P_CLEANUP_INTERVAL_LONG, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_PESSIMISTIC],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015PessimisticCleanupDeadlockTest : Adr015CleanupDeadlockNewModeScenarios("pessimistic")

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, P_CLEANUP_SCHEDULE, P_CLEANUP_INTERVAL_LONG, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_OPTIMISTIC, M_OPTIMISTIC],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015OptimisticCleanupDeadlockTest : Adr015CleanupDeadlockNewModeScenarios("optimistic")

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, P_CLEANUP_SCHEDULE, P_CLEANUP_INTERVAL_LONG, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_EXTEND, P_WINDOW, P_MIN_REMAINING],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015ExtendCleanupDeadlockTest : Adr015CleanupDeadlockNewModeScenarios("conditional-extend")
