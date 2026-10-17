package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.drift.CounterReconciler
import com.jun.labs.seatreservation.service.impl.drift.CounterRepairProbe
import com.jun.labs.seatreservation.service.impl.drift.DriftAction
import com.jun.labs.seatreservation.service.impl.drift.QuotaCleanup
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 후보 C — 두 갈래 대조·복구·마감 회차 제외 · 잠금 실패 skipped · 빌림 지표 = Hikari acquire(IV-009-6). 기본 매수 방식(precheck). */
@TestPropertySource(properties = ["seat.hold.counter-drift.reconcile.lock-timeout=200ms", "seat.hold.counter-drift.reconcile.chunk-size=2"])
class ReconcilerImplTest : DriftTestSupport() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var reconciler: CounterReconciler
    @Autowired lateinit var tx: TransactionTemplate

    private fun holdFor(userId: Long) = holdSeat.hold(HoldSeatCommand(schedule.id!!, createSeat(userId.toInt()).id!!, userId))

    @Test
    fun `열린 회차의 세 갈래 어긋남(행 없음·작음·큼)을 복구하고, 마감 회차는 제외 수만 남기며, 주입 안 한 쌍은 그대로`() {
        val s = schedule.id!!
        (1L..5L).forEach { holdFor(it) }
        val h5 = holdFor(5L)
        confirm.confirm(ConfirmReservationCommand(h5.holdId, 5L, "p")) // 사용자 5 = 홀드 1 + 확정 1
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 1", s)
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 0 WHERE schedule_id = ? AND user_id = 2", s)
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 2 WHERE schedule_id = ? AND user_id = 3", s)
        val closed = newSchedule(NOW.minusSeconds(60)).id!!
        rawHold(closed, 7L)
        quota(closed, 7L, 0) // 마감 회차 어긋남 — 대조 대상 아님
        val untouched = jdbcTemplate.queryForList("SELECT user_id, cnt, xmin::text FROM user_hold_quota WHERE schedule_id = ? AND user_id IN (4, 5) ORDER BY 1", s)

        val run = reconciler.runOnce("reconcile-app")

        assertEquals(3, run.found, "$run")
        assertEquals(3, run.repaired)
        assertEquals(0, run.falsePositives)
        assertEquals(1, run.skippedSchedules)
        assertEquals(1L, run.skippedPairs)
        assertEquals(4L, run.pairsScanned, "열린 회차의 쿼터 행 4개(1번은 행 없음 — 행 없음 갈래가 찾는다): $run")
        assertEquals(3, run.chunks, "k=2: 2 + 2 + 0")
        listOf(1L, 2L, 3L, 4L, 5L).forEach { u -> assertEquals(actualOf(s, u), cntOf(s, u), "사용자 $u") }
        assertEquals(0, cntOf(closed, 7L), "마감 회차는 고치지 않는다")
        assertEquals(untouched, jdbcTemplate.queryForList("SELECT user_id, cnt, xmin::text FROM user_hold_quota WHERE schedule_id = ? AND user_id IN (4, 5) ORDER BY 1", s))
        assertEquals(
            listOf(listOf<Any?>(1L, "missing", "repaired", null, "reconcile-app"), listOf<Any?>(2L, "below", "repaired", null, "reconcile-app"),
                listOf<Any?>(3L, "above", "repaired", null, "reconcile-app")),
            driftLog(),
        )
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM counter_reconcile_run WHERE repaired = 3 AND skipped_schedules = 1", Int::class.java))
    }

    @Test
    fun `행 없음 갈래 — 확정 예약만 있는 쌍도 찾는다(예약 갈래), 마감 회차의 행 없음 쌍은 보지 않는다`() {
        val s = schedule.id!!
        val h = holdFor(11L)
        confirm.confirm(ConfirmReservationCommand(h.holdId, 11L, "p"))
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 11", s)
        val closed = newSchedule(NOW.minusSeconds(60)).id!!
        rawHold(closed, 12L) // 마감 회차 + 쿼터 행 없음

        val run = reconciler.runOnce("reconcile-app")

        assertEquals(1, run.found, "$run")
        assertEquals(1, cntOf(s, 11L))
        assertEquals(null, cntOf(closed, 12L))
    }

    @Test
    fun `복구 대상 행을 다른 트랜잭션이 쥐고 있으면 lock_timeout 뒤 롤백하고 독립 트랜잭션으로 skipped(55P03)`() {
        val s = schedule.id!!
        holdFor(21L)
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 2 WHERE schedule_id = ? AND user_id = 21", s)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val holder = pool.submit {
            tx.executeWithoutResult {
                jdbcTemplate.queryForList("SELECT 1 FROM user_hold_quota WHERE schedule_id = ? AND user_id = 21 FOR UPDATE", s)
                locked.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        try {
            locked.await(5, TimeUnit.SECONDS)
            val run = reconciler.runOnce("reconcile-app")
            assertEquals(1, run.skipped, "$run")
            assertEquals(DriftAction.SKIPPED, run.outcomes.single().action)
        } finally {
            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            pool.shutdown()
        }
        assertEquals(2, cntOf(s, 21L), "롤백 — 그대로")
        assertEquals(listOf(listOf<Any?>(21L, "above", "skipped", "55P03", "reconcile-app")), driftLog())
        reconciler.runOnce("reconcile-app") // 다음 바퀴가 다시 탐지·복구
        assertEquals(1, cntOf(s, 21L))
    }

    @Test
    fun `IV-009-6 — 대조 한 바퀴의 빌림 지표 증가분 = Hikari acquire 증가분(정확히 같다), 복구·skipped 포함`() {
        val s = schedule.id!!
        (31L..36L).forEach { holdFor(it) }
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 31", s)
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 0 WHERE schedule_id = ? AND user_id = 32", s)
        newSchedule(NOW.minusSeconds(60))
        newSchedule(NOW.plusSeconds(600)) // 빈 열린 회차

        val acq0 = hikariAcquires()
        val b0 = counter(CounterReconciler.BORROWS_METRIC)
        val run = reconciler.runOnce("reconcile-app")
        val dAcq = hikariAcquires() - acq0
        val dBorrow = counter(CounterReconciler.BORROWS_METRIC) - b0

        assertEquals(2, run.repaired, "$run")
        assertTrue(dAcq > 0)
        assertEquals(dAcq.toDouble(), dBorrow, "Δacquire=$dAcq Δborrows=$dBorrow run=$run")
        assertEquals(dAcq, run.borrows)
    }

    @Test
    fun `빌림 1회 계약(ADR-008) — 기본 방식(3b + precheck) 선점 1건 = Hikari acquire 1(마감 판정 FOR SHARE 포함)`() {
        val seat = createSeat(41)
        val acq0 = hikariAcquires()
        holdSeat.hold(HoldSeatCommand(schedule.id!!, seat.id!!, 41L))
        assertEquals(1L, hikariAcquires() - acq0)
    }
}

/** 대조 오탐 이음새(설계 §1b) — 탐지 뒤·복구 트랜잭션 전에 다른 커넥션이 그 쌍을 고치면 false-positive 1행, repaired 0행. */
@Import(ReconcilerProbeImplTest.FixingProbe::class)
class ReconcilerProbeImplTest : DriftTestSupport() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var reconciler: CounterReconciler

    @TestConfiguration(proxyBeanMethods = false)
    class FixingProbe {
        @Bean
        fun fixingProbe(jdbc: JdbcTemplate) = CounterRepairProbe { s, u, _, actual ->
            jdbc.update("UPDATE user_hold_quota SET cnt = ? WHERE schedule_id = ? AND user_id = ?", actual, s, u)
        }
    }

    @Test
    fun `탐지와 잠금 사이에 맞춰지면 고치지 않고 오탐으로 기록`() {
        val s = schedule.id!!
        holdSeat.hold(HoldSeatCommand(s, createSeat(1).id!!, 51L))
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 2 WHERE schedule_id = ? AND user_id = 51", s)

        val run = reconciler.runOnce("reconcile-app")

        assertEquals(1, run.falsePositives, "$run")
        assertEquals(0, run.repaired)
        assertEquals(listOf(listOf<Any?>(51L, "above", "false-positive", null, "reconcile-app")), driftLog())
        assertEquals(1, cntOf(s, 51L))
    }
}

/** 정리 ①(schedule)·②(zero) · 정리 빌림 지표 = Hikari acquire. */
// 주기 실행이 측정(빌림 차분) 중에 끼지 않게 주기를 길게 둔다 — 테스트는 runOnce를 직접 부른다
@TestPropertySource(properties = ["seat.hold.counter-drift.cleanup.mode=schedule", "seat.hold.counter-drift.cleanup.interval=1h"])
class QuotaCleanupImplTest : DriftTestSupport() {

    @Autowired lateinit var cleanup: QuotaCleanup
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var tx: TransactionTemplate

    @Test
    fun `① 홀드가 없는 마감 회차만 확정 쌍까지 전부 지우고, 홀드가 남은 마감 회차와 열린 회차는 그대로 — 빌림 = acquire`() {
        val empty = newSchedule(NOW.minusSeconds(60)).id!!
        quota(empty, 1L, 1); quota(empty, 2L, 0) // 확정 쌍(1)·0 행
        val withHold = newSchedule(NOW.minusSeconds(60)).id!!
        rawHold(withHold, 3L); quota(withHold, 3L, 1)
        quota(schedule.id!!, 4L, 0) // 열린 회차

        val acq0 = hikariAcquires()
        val b0 = counter(QuotaCleanup.BORROWS_METRIC)
        val run = cleanup.runOnce()
        assertEquals((hikariAcquires() - acq0).toDouble(), counter(QuotaCleanup.BORROWS_METRIC) - b0)
        assertEquals(hikariAcquires() - acq0, run.borrows)

        assertEquals(2, run.rowsDeleted, "$run")
        assertEquals(1, run.skippedWithHolds)
        assertEquals(null, cntOf(empty, 1L))
        assertEquals(1, cntOf(withHold, 3L))
        assertEquals(0, cntOf(schedule.id!!, 4L))
        assertEquals(
            listOf(listOf<Any?>(empty, 0, 2), listOf<Any?>(withHold, 1, 0)),
            jdbcTemplate.query("SELECT schedule_id, holds_seen, rows_deleted FROM counter_cleanup_run WHERE mode = 'schedule' ORDER BY schedule_id") { rs, _ ->
                listOf(rs.getLong(1), rs.getInt(2), rs.getInt(3))
            },
        )
    }

    @Test
    fun `② cnt 0이고 실제 0인 행만 지운다 — 홀드·확정이 있는 쌍과 cnt 1 행은 남긴다`() {
        val zero = QuotaCleanup(
            jdbcTemplate, tx,
            properties.copy(
                counterDrift = properties.counterDrift.copy(
                    cleanup = properties.counterDrift.cleanup.copy(mode = SeatHoldProperties.CleanupMode.ZERO, chunkSize = 2),
                ),
            ),
            meters, clock,
        )
        val s = schedule.id!!
        (1L..5L).forEach { quota(s, it, 0) }
        rawHold(s, 3L) // cnt 0인데 실제 1 — 어긋남이지만 정리는 지우지 않는다
        quota(s, 6L, 1)
        val closed = newSchedule(NOW.minusSeconds(60)).id!!
        quota(closed, 1L, 0)

        val run = zero.runOnce()

        assertEquals(5, run.rowsDeleted, "$run")
        assertEquals(listOf(3L to 0, 6L to 1), jdbcTemplate.query("SELECT user_id, cnt FROM user_hold_quota ORDER BY schedule_id, user_id") { rs, _ -> rs.getLong(1) to rs.getInt(2) })
    }
}
