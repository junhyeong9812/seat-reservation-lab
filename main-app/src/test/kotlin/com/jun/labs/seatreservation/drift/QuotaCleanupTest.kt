package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.domain.ProductSchedule
import com.jun.labs.seatreservation.loadtest.LoadtestDataService
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.QuotaCleanup
import com.jun.labs.seatreservation.support.actualOf
import com.jun.labs.seatreservation.support.awaitIdleInTransaction
import com.jun.labs.seatreservation.support.clearAdr009Tables
import com.jun.labs.seatreservation.support.cntOf
import com.jun.labs.seatreservation.support.countOf
import com.jun.labs.seatreservation.support.hikariBorrows
import com.jun.labs.seatreservation.support.holdsAndReservations
import com.jun.labs.seatreservation.support.insertQuota
import com.jun.labs.seatreservation.support.insertSeatRow
import com.jun.labs.seatreservation.support.openCounterMismatch
import com.jun.labs.seatreservation.support.quotaRows
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-009 쿼터 행 정리 2방식 — 명세 §1-3 · §2 '쿼터 행 정리 안전 조건' · §5 · §10(정리 ①은 홀드 0이면 확정 쌍까지 회차 단위로 모두 삭제,
 * 마감 직전 선점과의 경합은 회차 행 잠금으로 직렬화). 진입점 `QuotaCleanup.runOnce()`(contract.md).
 * 판정기(LoadtestDataService — loadtest 프로필)는 열린 회차만 센다(§10) — 정리 뒤 `v_counter_mismatch` 0.
 */
@ActiveProfiles("loadtest")
abstract class CleanupScenario : IntegrationTest() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase
    @Autowired lateinit var cleanup: QuotaCleanup
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var loadtest: LoadtestDataService
    @Autowired lateinit var meters: MeterRegistry

    @BeforeEach
    fun clearLogs() = jdbcTemplate.clearAdr009Tables()

    @AfterEach
    fun clearLogsAfter() = jdbcTemplate.clearAdr009Tables()

    private var seatNo = 0
    protected fun freeSeat(scheduleId: Long) = jdbcTemplate.insertSeatRow(scheduleId, ++seatNo)

    protected fun hold(scheduleId: Long, userId: Long) = holdSeat.hold(HoldSeatCommand(scheduleId, freeSeat(scheduleId), userId))
    protected fun confirmed(scheduleId: Long, userId: Long) {
        val h = hold(scheduleId, userId)
        confirm.confirm(ConfirmReservationCommand(holdId = h.holdId, userId = userId, paymentUid = "p-$scheduleId-$userId-${h.seatId}"))
    }

    protected fun newSchedule(startsAt: java.time.Instant): Long =
        productScheduleRepository.save(ProductSchedule(productId = schedule.productId, startsAt = startsAt)).id!!

    protected fun judgeMismatch(): Long = (loadtest.consistency(20)["v_counter_mismatch"] as Number).toLong()

    protected fun cleanupRuns(): List<Map<String, Any?>> = jdbcTemplate.queryForList("SELECT * FROM counter_cleanup_run ORDER BY id")
}

@TestPropertySource(properties = ["seat.hold.counter-drift.cleanup.mode=schedule"])
class QuotaCleanupScheduleTest : CleanupScenario() {

    @Test
    fun `① schedule — 마감 회차라도 홀드가 남아 있으면 지우지 않는다`() {
        val s1 = schedule.id!!
        confirmed(s1, 1)
        hold(s1, 2) // 아직 배치가 지우지 않은 홀드(만료 시각은 곧 지난다)
        clock.set(schedule.startsAt.plusSeconds(60))
        val before = jdbcTemplate.quotaRows(s1)

        cleanup.runOnce()

        assertEquals(before, jdbcTemplate.quotaRows(s1), "홀드가 남은 회차의 쿼터 행을 지웠다 — 남은 홀드가 만료될 때 감소할 행이 없어진다")
        assertTrue(cleanupRuns().isNotEmpty(), "정리 실행이 기록되지 않았다(무음 금지)")
        assertTrue(cleanupRuns().all { it["mode"] == "schedule" })
        assertEquals(0L, cleanupRuns().sumOf { (it["rows_deleted"] as Number).toLong() })
    }

    @Test
    fun `① schedule — 홀드 0이 되면 확정 쌍·cnt 0 행까지 회차 단위로 모두 지운다, 열린 회차는 홀드 0이어도 그대로, 판정기 0`() {
        val s1 = schedule.id!!
        val s2 = newSchedule(schedule.startsAt.plus(Duration.ofDays(1))) // 같은 시각에 아직 열린 회차
        confirmed(s1, 1)
        hold(s1, 2)
        jdbcTemplate.insertQuota(s1, 3, 0)
        confirmed(s2, 1)                   // 열린 회차 — 홀드 0
        val s2Before = jdbcTemplate.quotaRows(s2)
        val reservations = jdbcTemplate.holdsAndReservations().second
        clock.set(schedule.startsAt.plusSeconds(60))
        expire.expire()                    // s1의 남은 홀드 만료 → s1 홀드 0
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE schedule_id = ?", Int::class.java, s1))

        cleanup.runOnce()

        assertEquals(emptyList(), jdbcTemplate.quotaRows(s1), "마감·홀드 0 회차의 쿼터 행이 남았다")
        assertEquals(s2Before, jdbcTemplate.quotaRows(s2), "열린 회차의 행을 지웠다")
        assertEquals(reservations, jdbcTemplate.holdsAndReservations().second, "정리가 예약을 바꿨다")
        val run = cleanupRuns().single { (it["schedule_id"] as Number?)?.toLong() == s1 }
        assertEquals("schedule", run["mode"])
        assertEquals(3L, (run["rows_deleted"] as Number).toLong())
        assertEquals(0L, (run["holds_seen"] as Number).toLong())
        assertEquals(0L, judgeMismatch(), "정리 뒤 판정기 v_counter_mismatch")
        assertEquals(0L, jdbcTemplate.openCounterMismatch(clock.instant()))
    }

    @Test
    fun `지표 — cleanup_borrows는 커넥션 빌림마다 정확히 +1`() {
        val s1 = schedule.id!!
        confirmed(s1, 1)
        clock.set(schedule.startsAt.plusSeconds(60))
        val borrows0 = meters.countOf("seat.hold.counter.cleanup.borrows")
        val hikari0 = meters.hikariBorrows()

        cleanup.runOnce()

        val hikari = meters.hikariBorrows() - hikari0
        assertTrue(hikari > 0, "전제: Hikari acquire 지표가 오른다")
        assertEquals(hikari, meters.countOf("seat.hold.counter.cleanup.borrows") - borrows0)
    }
}

/**
 * ① schedule × 마감 직전 선점(명세 §10 codex C2 — 회차 행 잠금으로 직렬화). 임계 구역 지연(ADR-004 장치, 1.5s)으로
 * 선점 트랜잭션이 마감 판정을 통과하고 쿼터 행을 올린 뒤(커밋 전) 멈춘 사이에 시계를 시작 뒤로 넘기고 정리를 돌린다.
 * 사용자는 그 회차에 확정 1이 있어 쿼터 행이 이미 있다 — 잠금 없이 '홀드 0 확인 → 회차 행 삭제'를 하면 선점이 커밋된 뒤 그 행이 지워진다(행 없음 어긋남).
 */
@TestPropertySource(properties = ["seat.hold.counter-drift.cleanup.mode=schedule", "seat.hold.critical-section-delay=1500ms"])
class QuotaCleanupScheduleRaceTest : CleanupScenario() {

    @Test
    fun `① schedule — 마감 직전에 시작한 다른 사용자의 선점과 겹치면 지우지 않는다(끝에 cnt = 실제, 행 있음)`() {
        val s1 = schedule.id!!
        val u = 21L
        confirmed(s1, u)
        val seat = freeSeat(s1)
        clock.set(schedule.startsAt.minusSeconds(1))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holding = pool.submit<String> {
                try { holdSeat.hold(HoldSeatCommand(s1, seat, u)); "ok" } catch (e: Throwable) { "ERR:${e::class.simpleName}:${e.message?.take(160)}" }
            }
            jdbcTemplate.awaitIdleInTransaction() // 선점이 지연(sleep) 안 — 마감 판정·쿼터 upsert는 끝났고 커밋 전
            clock.set(schedule.startsAt.plusSeconds(1))
            val cleaning = pool.submit<Unit> { cleanup.runOnce() }

            assertEquals("ok", holding.get(30, TimeUnit.SECONDS), "마감 판정을 통과한 선점은 성공")
            cleaning.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        assertEquals(2, jdbcTemplate.actualOf(s1, u), "확정 1 + 홀드 1")
        assertEquals(2, jdbcTemplate.cntOf(s1, u), "정리가 진행 중 선점과 겹쳐 쿼터 행을 지웠다")
    }
}

@TestPropertySource(properties = ["seat.hold.counter-drift.cleanup.mode=zero"])
class QuotaCleanupZeroTest : CleanupScenario() {

    @Test
    fun `② zero — cnt 0 행만 지우고 cnt가 있는 행은 그대로, 홀드·예약 그대로, 판정기 0`() {
        val s1 = schedule.id!!
        val s2 = newSchedule(schedule.startsAt.plus(Duration.ofDays(1)))
        hold(s1, 1)                          // 만료되면 cnt 0
        hold(s1, 2)                          // 만료되면 cnt 0
        confirmed(s1, 3)                     // cnt 1 유지
        jdbcTemplate.insertQuota(s2, 4, 0)   // 다른 회차의 cnt 0
        hold(s2, 5)                          // 만료되면 cnt 0(다른 회차)
        clock.set(NOW.plus(properties.ttl).plusSeconds(1))
        expire.expire()
        hold(s2, 6)                          // 살아 있는 홀드 cnt 1
        val keep = jdbcTemplate.quotaRows().filter { it.cnt > 0 }
        val zero = jdbcTemplate.quotaRows().filter { it.cnt == 0 }
        assertTrue(zero.size >= 4, "전제: cnt 0 행이 여럿 있다 — $zero")
        val hr = jdbcTemplate.holdsAndReservations()

        cleanup.runOnce()

        assertEquals(keep, jdbcTemplate.quotaRows(), "cnt 0 행만 지워져야 한다")
        assertEquals(hr, jdbcTemplate.holdsAndReservations())
        val runs = cleanupRuns()
        assertTrue(runs.isNotEmpty() && runs.all { it["mode"] == "zero" }, "$runs")
        assertEquals(zero.size.toLong(), runs.sumOf { (it["rows_deleted"] as Number).toLong() })
        assertEquals(0L, judgeMismatch())
        assertEquals(0L, jdbcTemplate.openCounterMismatch(clock.instant()))
    }
}

/**
 * ② zero × 진행 중 upsert(명세 §2 '행 잠금 뒤 다시 확인'). 사용자의 쿼터 행이 cnt 0(지난 홀드 만료)인 상태에서 새 선점이 upsert로 1로 올리고
 * 커밋 전에 멈춘 사이 정리를 돌린다. '0인 행을 고른 뒤 키로 삭제'하면 선점이 커밋된 뒤 cnt 1 행이 지워진다.
 */
@TestPropertySource(properties = ["seat.hold.counter-drift.cleanup.mode=zero", "seat.hold.critical-section-delay=1500ms"])
class QuotaCleanupZeroRaceTest : CleanupScenario() {

    @Test
    fun `② zero — 진행 중인 upsert와 겹치면 지우지 않는다(끝에 cnt 1 = 실제)`() {
        val s1 = schedule.id!!
        val u = 31L
        hold(s1, u)
        clock.set(NOW.plus(properties.ttl).plusSeconds(1))
        expire.expire()
        assertEquals(0, jdbcTemplate.cntOf(s1, u), "전제: cnt 0 행")
        val seat = freeSeat(s1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holding = pool.submit<String> {
                try { holdSeat.hold(HoldSeatCommand(s1, seat, u)); "ok" } catch (e: Throwable) { "ERR:${e::class.simpleName}:${e.message?.take(160)}" }
            }
            jdbcTemplate.awaitIdleInTransaction()
            val cleaning = pool.submit<Unit> { cleanup.runOnce() }

            assertEquals("ok", holding.get(30, TimeUnit.SECONDS))
            cleaning.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, jdbcTemplate.actualOf(s1, u))
        assertEquals(1, jdbcTemplate.cntOf(s1, u), "정리가 진행 중 upsert와 겹쳐 행을 지웠다")
        assertEquals(0L, judgeMismatch())
    }
}
