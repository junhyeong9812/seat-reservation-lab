package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.domain.ProductSchedule
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.support.CounterReconciler
import com.jun.labs.seatreservation.support.CounterRepairProbe
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.QuotaRow
import com.jun.labs.seatreservation.support.actualOf
import com.jun.labs.seatreservation.support.clearAdr009Tables
import com.jun.labs.seatreservation.support.cntOf
import com.jun.labs.seatreservation.support.countOf
import com.jun.labs.seatreservation.support.deleteQuota
import com.jun.labs.seatreservation.support.driftLogs
import com.jun.labs.seatreservation.support.hikariBorrows
import com.jun.labs.seatreservation.support.holdsAndReservations
import com.jun.labs.seatreservation.support.insertHoldRow
import com.jun.labs.seatreservation.support.insertQuota
import com.jun.labs.seatreservation.support.insertReservationRow
import com.jun.labs.seatreservation.support.insertSeatRow
import com.jun.labs.seatreservation.support.quotaRows
import com.jun.labs.seatreservation.support.rowCount
import com.jun.labs.seatreservation.support.setCnt
import com.jun.labs.seatreservation.support.xminOf
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR-009 후보 C(DB 안 청크 대조) — 명세 §2 복구 불변식·무음 금지·대조 오탐, §5 'C·F는 주입 쌍 100% 복구·기록, 주입 안 한 쌍 변경 0',
 * §10 '마감 회차는 대조에서 제외하고 제외 수를 기록', §11 R2(행 없음 쌍 두 갈래 — 홀드 쪽·예약 쪽).
 * 진입점은 `CounterReconciler.runOnce(source)`(contract.md). 대조 주기는 1h로 둬서 백그라운드 바퀴가 테스트와 겹치지 않게 한다.
 */
abstract class ReconcileScenario : IntegrationTest() {

    // reconciler 프로필(F)은 선점·확정 빈을 띄우지 않을 수 있다 — 그 컨텍스트에서는 쓰지 않는다
    @Autowired(required = false) var holdSeat: HoldSeatUseCase? = null
    @Autowired(required = false) var confirm: ConfirmReservationUseCase? = null
    @Autowired lateinit var reconciler: CounterReconciler
    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var tx: TransactionTemplate

    @BeforeEach
    fun clearLogs() = jdbcTemplate.clearAdr009Tables()

    @AfterEach
    fun clearLogsAfter() = jdbcTemplate.clearAdr009Tables()

    protected val s get() = schedule.id!!
    private var seatNo = 0
    protected fun freeSeat(scheduleId: Long = s) = jdbcTemplate.insertSeatRow(scheduleId, ++seatNo)

    protected fun appHold(userId: Long) = holdSeat!!.hold(HoldSeatCommand(s, freeSeat(), userId))
    protected fun appConfirmed(userId: Long) {
        val h = appHold(userId)
        confirm!!.confirm(ConfirmReservationCommand(holdId = h.holdId, userId = userId, paymentUid = "p-$userId-${h.seatId}"))
    }

    /** 주입한 쌍 하나 — 기대 kind와 주입한 cnt(행 없음 = null). */
    data class Injected(val scheduleId: Long, val userId: Long, val kind: String, val cntBefore: Int?)

    /**
     * 열린 회차(기본 회차, 시작 NOW + 1일)에 앱 경로로 쌍을 만든 뒤 DB 직접 조작으로 세 갈래 어긋남을 넣는다.
     * 행 없음은 두 갈래: 홀드만 있는 쌍 · 확정 예약만 있는 쌍(§11 '예약 쪽 갈래').
     */
    protected fun injectOpen(): List<Injected> {
        appHold(301)                                         // 행 없음(홀드 쪽): 실제 1
        appConfirmed(302)                                    // 행 없음(예약 쪽): 실제 1, 홀드 0
        appConfirmed(303); appHold(303)                      // cnt 작음: 실제 2 → 1
        appHold(304)                                         // cnt 작음: 실제 1 → 0
        appHold(305)                                         // cnt 큼: 실제 1 → 2
        jdbcTemplate.insertQuota(s, 306, 1)                  // cnt 큼: 홀드·예약 없음(실제 0) → 1
        jdbcTemplate.deleteQuota(s, 301)
        jdbcTemplate.deleteQuota(s, 302)
        jdbcTemplate.setCnt(s, 303, 1)
        jdbcTemplate.setCnt(s, 304, 0)
        jdbcTemplate.setCnt(s, 305, 2)
        return listOf(
            Injected(s, 301, "missing", null), Injected(s, 302, "missing", null),
            Injected(s, 303, "below", 1), Injected(s, 304, "below", 0),
            Injected(s, 305, "above", 2), Injected(s, 306, "above", 1),
        )
    }

    /** 주입하지 않은 쌍 — 맞는 카운터(홀드 · 확정 · 확정 + 홀드 · cnt 0 행). */
    protected fun seedUntouched(): List<Long> {
        appHold(401)
        appConfirmed(402)
        appConfirmed(403); appHold(403)
        jdbcTemplate.insertQuota(s, 404, 0)
        return listOf(401L, 402L, 403L, 404L)
    }

    /** 마감된 회차(시작 = NOW - 1h, 앱 Clock 기준 이미 시작) — 어긋난 쌍을 SQL로 만든다(선점 API는 마감이라 쓸 수 없다). */
    protected fun seedClosedWithDrift(): Long {
        val closed = productScheduleRepository.save(ProductSchedule(productId = schedule.productId, startsAt = NOW.minusSeconds(3_600))).id!!
        val seat1 = jdbcTemplate.insertSeatRow(closed, 1, SeatStatus.HELD)
        jdbcTemplate.insertHoldRow(seat1, closed, 501, NOW.minusSeconds(60), NOW.plusSeconds(240)) // 행 없음(실제 1)
        val seat2 = jdbcTemplate.insertSeatRow(closed, 2, SeatStatus.RESERVED)
        jdbcTemplate.insertReservationRow(closed, seat2, 502)
        jdbcTemplate.insertQuota(closed, 502, 2)                                                    // cnt 큼(실제 1)
        return closed
    }
}

@TestPropertySource(
    properties = [
        "seat.hold.counter-drift.reconcile.enabled=true",
        "seat.hold.counter-drift.reconcile.interval=1h",
        // 코드 루프 1 L1-10(사용자 결정): 대조(C)를 켜면 A가 필수(기동 검사). 이 클래스는 만료를 부르지 않아 A는 단언에 영향이 없다
        "seat.hold.counter-drift.expiry-repair=true",
    ],
)
class CounterReconcileAppTest : ReconcileScenario() {

    @Test
    fun `C — 주입한 세 갈래(행 없음 2갈래·작음·큼) 100% 복구 = 실제 값, 쌍마다 기록 1행(reconcile-app · repaired)`() {
        val injected = injectOpen()

        reconciler.runOnce("reconcile-app")

        injected.forEach {
            assertEquals(jdbcTemplate.actualOf(it.scheduleId, it.userId), jdbcTemplate.cntOf(it.scheduleId, it.userId) ?: 0, "복구 뒤 cnt = 실제: $it")
        }
        // 실제 > 0인 쌍은 행이 있어야 한다(행 없음 갈래의 복구 = 행을 다시 만든다)
        listOf(301L, 302L, 303L, 304L, 305L).forEach { assertNotNull(jdbcTemplate.cntOf(s, it), "사용자 $it 쿼터 행") }

        val logs = jdbcTemplate.driftLogs()
        assertEquals(injected.size, logs.size, "주입한 쌍마다 정확히 1행 — $logs")
        injected.forEach { inj ->
            val log = logs.single { it.scheduleId == inj.scheduleId && it.userId == inj.userId }
            assertEquals("reconcile-app", log.source, "$inj")
            assertEquals(inj.kind, log.kind, "$inj")
            assertEquals(inj.cntBefore, log.cntBefore, "$inj")
            assertEquals("repaired", log.action, "$inj")
            assertEquals(jdbcTemplate.actualOf(inj.scheduleId, inj.userId), log.actual, "$inj: 기록한 실제 값")
        }
    }

    @Test
    fun `C — 주입하지 않은 쌍은 변경 0(cnt·xmin 그대로, 기록 없음), 홀드·예약도 그대로`() {
        val untouched = seedUntouched()
        injectOpen()
        val before = jdbcTemplate.quotaRows(s).filter { it.userId in untouched }
        val holdsAndReservations = jdbcTemplate.holdsAndReservations()

        reconciler.runOnce("reconcile-app")

        assertEquals(before, jdbcTemplate.quotaRows(s).filter { it.userId in untouched }, "주입하지 않은 쌍의 행이 바뀌었다(오탐 복구)")
        assertTrue(jdbcTemplate.driftLogs().none { it.userId in untouched }, "주입하지 않은 쌍이 기록됐다: ${jdbcTemplate.driftLogs()}")
        assertEquals(holdsAndReservations, jdbcTemplate.holdsAndReservations(), "복구가 홀드·예약을 바꿨다")
    }

    @Test
    fun `C — 마감 회차는 건드리지 않고(행·기록 없음) 제외 수만 바퀴 기록에 남긴다`() {
        val closed = seedClosedWithDrift()
        val injected = injectOpen()
        val closedBefore: List<QuotaRow> = jdbcTemplate.quotaRows(closed)
        val runs0 = jdbcTemplate.rowCount("counter_reconcile_run")

        reconciler.runOnce("reconcile-app")

        assertEquals(closedBefore, jdbcTemplate.quotaRows(closed), "마감 회차 쿼터 행이 바뀌었다")
        assertEquals(null, jdbcTemplate.cntOf(closed, 501), "마감 회차의 행 없음 쌍을 만들었다")
        assertTrue(jdbcTemplate.driftLogs().none { it.scheduleId == closed }, "마감 회차 쌍이 기록됐다(제외 대상)")
        assertEquals(injected.size, jdbcTemplate.driftLogs().size, "열린 회차 주입 쌍은 그대로 복구")

        assertEquals(runs0 + 1, jdbcTemplate.rowCount("counter_reconcile_run"), "대조 한 바퀴 = 기록 1행")
        val run = jdbcTemplate.queryForList("SELECT * FROM counter_reconcile_run ORDER BY id DESC LIMIT 1").single()
        // 계약은 '제외 회차·쌍 수를 기록'만 정하고 열 이름은 'skipped_schedules 등'으로 열어 둔다 — 회차 수 열 중 하나가 1 이상이면 된다
        val excludedSchedules = run.filterKeys { k -> k.contains("schedules") && (k.contains("skip") || k.contains("exclu") || k.contains("closed")) }
        assertTrue(excludedSchedules.isNotEmpty(), "제외 회차 수 열이 없다: ${run.keys}")
        assertTrue(excludedSchedules.values.any { (it as Number).toLong() >= 1 }, "제외 회차 수가 기록되지 않았다: $run")
    }

    @Test
    fun `C — 멱등 — 복구 뒤 다시 돌면 새 기록 0, cnt 그대로`() {
        injectOpen()
        reconciler.runOnce("reconcile-app")
        val logs = jdbcTemplate.driftLogs().size
        val rows = jdbcTemplate.quotaRows(s)

        reconciler.runOnce("reconcile-app")

        assertEquals(logs, jdbcTemplate.driftLogs().size, "두 번째 바퀴가 또 고쳤다: ${jdbcTemplate.driftLogs()}")
        assertEquals(rows, jdbcTemplate.quotaRows(s))
    }

    @Test
    fun `지표 — seat_hold_counter_drift(reconcile-app)가 kind별 주입 수만큼, reconcile_borrows는 커넥션 빌림마다 정확히 +1`() {
        injectOpen()
        fun drift(kind: String) = meters.countOf("seat.hold.counter.drift", "source" to "reconcile-app", "kind" to kind, "action" to "repaired")
        val m0 = drift("missing"); val b0 = drift("below"); val a0 = drift("above")
        val borrows0 = meters.countOf("seat.hold.counter.reconcile.borrows")
        val hikari0 = meters.hikariBorrows()

        reconciler.runOnce("reconcile-app")

        val hikari = meters.hikariBorrows() - hikari0
        val borrows = meters.countOf("seat.hold.counter.reconcile.borrows") - borrows0
        assertEquals(2.0, drift("missing") - m0)
        assertEquals(2.0, drift("below") - b0)
        assertEquals(2.0, drift("above") - a0)
        assertTrue(hikari > 0, "전제: Hikari acquire 지표가 오른다(지표가 등록돼 있다)")
        assertEquals(hikari, borrows, "대조가 빌린 커넥션 수 = 빌림 지표 증가분(이 사이 다른 쓰기 주체 없음)")
    }

    @Test
    fun `C — 잠금 시간 초과로 못 고친 쌍은 skipped(+SQLSTATE)로 남고, 바퀴는 멈추지 않고 나머지 쌍을 고친다`() {
        val injected = injectOpen()
        val lockedPair = injected.single { it.userId == 305L } // cnt 큼 — 행이 있어 잠글 수 있다
        val skipped0 = meters.countOf("seat.hold.counter.drift", "source" to "reconcile-app", "action" to "skipped")
        val pool = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = pool.submit {
            tx.executeWithoutResult {
                jdbcTemplate.queryForList("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", s, lockedPair.userId)
                locked.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            reconciler.runOnce("reconcile-app") // lock-timeout 기본 1s
        } finally {
            release.countDown()
            holder.get(30, TimeUnit.SECONDS)
            pool.shutdownNow()
        }

        assertEquals(2, jdbcTemplate.cntOf(s, lockedPair.userId), "잠긴 쌍은 고치지 못한다")
        val log = jdbcTemplate.driftLogs().single { it.userId == lockedPair.userId }
        assertEquals("skipped", log.action)
        assertTrue(!log.reason.isNullOrBlank(), "skipped의 reason(SQLSTATE)이 비었다")
        assertEquals(1.0, meters.countOf("seat.hold.counter.drift", "source" to "reconcile-app", "action" to "skipped") - skipped0)
        injected.filter { it != lockedPair }.forEach {
            assertEquals(jdbcTemplate.actualOf(it.scheduleId, it.userId), jdbcTemplate.cntOf(it.scheduleId, it.userId) ?: 0, "나머지 쌍 복구: $it")
        }
    }
}

/**
 * 대조 오탐(명세 §2 · §10 D1) — 테스트 이음새 `CounterRepairProbe`로 탐지와 복구 사이에 쓰기를 끼운다.
 * 주입: 사용자 601은 홀드 1(실제 1)인데 cnt = 2(큼)로 보인다. 탐지 뒤 이음새가 그 쌍에 홀드 1을 더 커밋한다(실제 2) — 다시 세면 맞으므로 고치지 않고 false-positive.
 * 같은 바퀴의 진짜 어긋남(사용자 602)은 그대로 복구 — 이음새가 바퀴를 망가뜨리지 않는다는 대조.
 */
@TestPropertySource(
    properties = [
        "seat.hold.counter-drift.reconcile.enabled=true",
        "seat.hold.counter-drift.reconcile.interval=1h",
        // 코드 루프 1 L1-10(사용자 결정): 대조(C)를 켜면 A가 필수(기동 검사). 이 클래스는 만료를 부르지 않아 A는 단언에 영향이 없다
        "seat.hold.counter-drift.expiry-repair=true",
    ],
)
@Import(CounterReconcileFalsePositiveTest.ProbeConfig::class)
class CounterReconcileFalsePositiveTest : ReconcileScenario() {

    class RecordingProbe : CounterRepairProbe {
        data class Call(val scheduleId: Long, val userId: Long, val observedCnt: Int?, val observedActual: Int)
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        @Volatile var target: kotlin.Pair<Long, Long>? = null
        @Volatile var action: (() -> Unit)? = null
        val fired = AtomicBoolean(false)

        override fun afterDetect(scheduleId: Long, userId: Long, observedCnt: Int?, observedActual: Int) {
            calls += Call(scheduleId, userId, observedCnt, observedActual)
            val a = action ?: return
            if (target == (scheduleId to userId) && fired.compareAndSet(false, true)) {
                // 별도 스레드·자동 커밋으로 — 대조 스레드에 묶인 트랜잭션이 있어도 거기에 섞이지 않고 먼저 커밋된다
                val pool = Executors.newSingleThreadExecutor()
                try { pool.submit(Runnable { a() }).get(10, TimeUnit.SECONDS) } finally { pool.shutdownNow() }
            }
        }

        fun reset() { calls.clear(); target = null; action = null; fired.set(false) }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ProbeConfig {
        @Bean fun recordingProbe(): RecordingProbe = RecordingProbe()
    }

    @Autowired lateinit var probe: RecordingProbe

    @BeforeEach
    fun resetProbe() = probe.reset()

    @Test
    fun `탐지 뒤 쓰기로 다시 세면 맞는 쌍 — 고치지 않고(cnt·xmin 그대로) false-positive로 기록, 진짜 어긋남은 복구`() {
        appHold(601)
        jdbcTemplate.setCnt(s, 601, 2)          // 보이는 어긋남: cnt 2 vs 실제 1
        appHold(602)
        jdbcTemplate.setCnt(s, 602, 0)          // 진짜 어긋남: cnt 0 vs 실제 1
        val spare = jdbcTemplate.insertSeatRow(s, 9_001, SeatStatus.HELD)
        val xmin601 = jdbcTemplate.xminOf(s, 601)
        val fp0 = meters.countOf("seat.hold.counter.drift", "source" to "reconcile-app", "action" to "false-positive")
        probe.target = s to 601L
        probe.action = { jdbcTemplate.insertHoldRow(spare, s, 601, NOW, NOW.plusSeconds(300)) } // 실제 1 → 2

        reconciler.runOnce("reconcile-app")

        assertTrue(probe.fired.get(), "이음새가 불리지 않았다 — 탐지·복구 사이 이음새가 연결되지 않았다: ${probe.calls}")
        val call = probe.calls.first { it.scheduleId == s && it.userId == 601L }
        assertEquals(2, call.observedCnt, "탐지 때 본 cnt")
        assertEquals(1, call.observedActual, "탐지 때 본 실제 값")

        assertEquals(2, jdbcTemplate.cntOf(s, 601), "다시 세면 맞는 쌍을 고쳤다")
        assertEquals(2, jdbcTemplate.actualOf(s, 601))
        assertEquals(xmin601, jdbcTemplate.xminOf(s, 601), "오탐 쌍의 행에 쓰기가 있었다")
        val logs = jdbcTemplate.driftLogs()
        assertEquals("false-positive", logs.single { it.userId == 601L }.action, "$logs")
        assertEquals(1.0, meters.countOf("seat.hold.counter.drift", "source" to "reconcile-app", "action" to "false-positive") - fp0)

        assertEquals(1, jdbcTemplate.cntOf(s, 602), "같은 바퀴의 진짜 어긋남은 복구")
        assertEquals("repaired", logs.single { it.userId == 602L }.action)
        assertTrue(probe.calls.any { it.userId == 602L }, "이음새는 어긋남을 찾은 쌍마다 불린다")
    }

    @Test
    fun `이음새가 아무것도 안 하면 같은 쌍은 복구된다(대조 — 위 오탐 판정이 이음새의 쓰기 때문임을 보인다)`() {
        appHold(611)
        jdbcTemplate.setCnt(s, 611, 2)

        reconciler.runOnce("reconcile-app")

        assertEquals(1, jdbcTemplate.cntOf(s, 611))
        assertEquals("repaired", jdbcTemplate.driftLogs().single { it.userId == 611L }.action)
    }
}

/**
 * 후보 F — 같은 jar의 `reconciler` 프로필(별도 프로세스 흉내: 이 컨텍스트가 그 프로세스). 같은 대조를 source `reconcile-proc`로 기록한다.
 * 이 프로필은 선점 경로를 쓰지 않을 수 있어 쌍을 SQL로 만든다.
 */
@ActiveProfiles("reconciler")
@TestPropertySource(properties = ["seat.hold.counter-drift.reconcile.interval=1h"])
class CounterReconcileProcTest : ReconcileScenario() {

    @Test
    fun `F — 주입한 쌍 복구·기록(reconcile-proc), 주입 안 한 쌍 변경 0`() {
        val st1 = jdbcTemplate.insertSeatRow(s, 1, SeatStatus.HELD)
        jdbcTemplate.insertHoldRow(st1, s, 701, NOW, NOW.plusSeconds(300))       // 행 없음(실제 1)
        val st2 = jdbcTemplate.insertSeatRow(s, 2, SeatStatus.HELD)
        jdbcTemplate.insertHoldRow(st2, s, 702, NOW, NOW.plusSeconds(300))
        jdbcTemplate.insertQuota(s, 702, 2)                                      // cnt 큼(실제 1)
        val st3 = jdbcTemplate.insertSeatRow(s, 3, SeatStatus.RESERVED)
        jdbcTemplate.insertReservationRow(s, st3, 703)
        jdbcTemplate.insertQuota(s, 703, 0)                                      // cnt 작음(실제 1)
        val st4 = jdbcTemplate.insertSeatRow(s, 4, SeatStatus.HELD)
        jdbcTemplate.insertHoldRow(st4, s, 704, NOW, NOW.plusSeconds(300))
        jdbcTemplate.insertQuota(s, 704, 1)                                      // 맞음
        val untouched = jdbcTemplate.quotaRows(s).filter { it.userId == 704L }

        reconciler.runOnce("reconcile-proc")

        listOf(701L, 702L, 703L).forEach { assertEquals(1, jdbcTemplate.cntOf(s, it), "사용자 $it") }
        assertEquals(untouched, jdbcTemplate.quotaRows(s).filter { it.userId == 704L })
        val logs = jdbcTemplate.driftLogs()
        assertEquals(setOf(701L, 702L, 703L), logs.map { it.userId }.toSet(), "$logs")
        assertTrue(logs.all { it.source == "reconcile-proc" && it.action == "repaired" }, "$logs")
        assertEquals(mapOf(701L to "missing", 702L to "above", 703L to "below"), logs.associate { it.userId to it.kind })
    }
}
