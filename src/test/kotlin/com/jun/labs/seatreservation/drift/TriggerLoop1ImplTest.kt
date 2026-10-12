package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.drift.CounterReconciler
import com.jun.labs.seatreservation.service.impl.drift.QuotaCleanup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 코드 루프 1 — B(counter-trigger) 사용자 결정 확인(L1-1 증가도 엄격 · L1-2 마감 회차 건너뜀 · L1-19 기여 쪽별 판정 · L1-17 B + C 회복).
 * 설정은 blind `CounterTriggerWithReconcileTest`와 같다(컨텍스트 공유). 트리거 기준 시각 = IntegrationTest의 seatlab.counter_now(= NOW).
 */
@TestPropertySource(
    properties = [
        "seat.hold.limit-strategy=counter-trigger",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-trigger",
        "seat.hold.counter-drift.reconcile.enabled=true",
        "seat.hold.counter-drift.reconcile.interval=1h",
    ],
)
class TriggerLoop1ImplTest : DriftTestSupport() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase
    @Autowired lateinit var reconciler: CounterReconciler
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var tx: TransactionTemplate

    @AfterEach
    fun truncate() = jdbcTemplate.execute("TRUNCATE reservation, seat_hold, user_hold_quota")

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private var seatNo = 5_000
    private fun seat(scheduleId: Long, status: SeatStatus = SeatStatus.AVAILABLE): Long = jdbcTemplate.queryForObject(
        "INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'A', 1, ?, ?) RETURNING id",
        Long::class.java, scheduleId, ++seatNo, status.name,
    )!!

    private fun reservation(scheduleId: Long, userId: Long, status: String = "CONFIRMED"): Long = jdbcTemplate.queryForObject(
        "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, 'p', ?, now()) RETURNING id",
        Long::class.java, scheduleId, seat(scheduleId, SeatStatus.RESERVED), userId, status,
    )!!

    @Test
    fun `L1-1 — 증가 경로도 엄격 — 열린 회차에 쿼터 행이 없으면 확정 예약 INSERT·확정 API가 SR001(행을 만들지 않는다)`() {
        val s = schedule.id!!
        val h = holdSeat.hold(HoldSeatCommand(s, seat(s), 1L))
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 1", s)
        assertEquals("SR001", sqlState(assertThrows<Exception> { reservation(s, 2L) }), "행 없는 사용자 2의 CONFIRMED INSERT")
        assertEquals("SR001", sqlState(assertThrows<Exception> { confirm.confirm(ConfirmReservationCommand(h.holdId, 1L, "p")) }))
        assertEquals(null, cntOf(s, 1L))
        assertEquals(null, cntOf(s, 2L))
        assertEquals(1, holdCount(), "확정 롤백")
    }

    @Test
    fun `L1-2 — 정리 ① 뒤(쿼터 행 없음) 마감 회차의 예약 상태 변경은 트리거가 건너뛰어 성공한다`() {
        val closed = newSchedule(NOW.minusSeconds(60)).id!!
        val r = reservation(closed, 7L) // 마감 회차 — 트리거가 반영하지 않는다(행 없음이어도 성공)
        quota(closed, 7L, 1)
        val cleanup = QuotaCleanup(
            jdbcTemplate, tx,
            properties.copy(counterDrift = properties.counterDrift.copy(cleanup = properties.counterDrift.cleanup.copy(mode = SeatHoldProperties.CleanupMode.SCHEDULE))),
            meters, clock,
        )
        assertEquals(1, cleanup.runOnce().rowsDeleted)
        jdbcTemplate.update("UPDATE reservation SET status = 'CANCELED' WHERE id = ?", r)
        jdbcTemplate.update("UPDATE reservation SET status = 'CONFIRMED' WHERE id = ?", r)
        jdbcTemplate.update("DELETE FROM reservation WHERE id = ?", r)
        assertEquals(null, cntOf(closed, 7L), "마감 회차는 행을 만들지도 쓰지도 않는다")
    }

    @Test
    fun `L1-2 — 마감 회차 홀드 만료는 선잠금·트리거 감소를 건너뛰어 쿼터 행이 없어도 틱이 성공한다(만료 정지 없음)`() {
        val closed = newSchedule(NOW.minusSeconds(60)).id!!
        rawHold(closed, 8L, expiresAt = NOW.minusSeconds(1)) // 행 없음 + 마감 회차
        rawHold(closed, 9L, expiresAt = NOW.minusSeconds(1))
        quota(closed, 9L, 0) // 행은 있지만 0 — 감소했다면 CHECK 위반
        val ok0 = counter("seat.hold.expiry.tick", "outcome", "ok")
        assertEquals(2, expire.expire())
        assertEquals(1.0, counter("seat.hold.expiry.tick", "outcome", "ok") - ok0)
        assertEquals(null, cntOf(closed, 8L))
        assertEquals(0, cntOf(closed, 9L))
        assertEquals(emptyList(), driftLog())
    }

    @Test
    fun `L1-19 — 회차 키 변경은 쪽별 판정 — 열린 → 마감은 열린 쪽만 −1, 마감 → 열린은 열린 쪽만 +1(행 없으면 SR001)`() {
        val s = schedule.id!!
        val closed = newSchedule(NOW.minusSeconds(60)).id!!
        // 열린 → 마감
        val h = holdSeat.hold(HoldSeatCommand(s, seat(s), 11L))
        assertEquals(1, cntOf(s, 11L))
        jdbcTemplate.update("UPDATE seat_hold SET schedule_id = ? WHERE id = ?", closed, h.holdId)
        assertEquals(0, cntOf(s, 11L), "열린 쪽 −1")
        assertEquals(null, cntOf(closed, 11L), "마감 쪽은 건너뜀(행 없어도 오류 아님)")
        // 마감 → 열린(열린 쪽 행 있음)
        jdbcTemplate.update("UPDATE seat_hold SET schedule_id = ? WHERE id = ?", s, h.holdId)
        assertEquals(1, cntOf(s, 11L), "열린 쪽 +1")
        // 마감 → 열린(열린 쪽 행 없음) — 엄격
        val h2 = rawHold(closed, 12L)
        assertEquals("SR001", sqlState(assertThrows<Exception> { jdbcTemplate.update("UPDATE seat_hold SET schedule_id = ? WHERE id = ?", s, h2) }))
        // 예약의 CONFIRMED 여부 변경도 쪽별: 마감 회차 예약 CONFIRMED → CANCELED는 행 없이도 성공
        val r = reservation(closed, 13L)
        jdbcTemplate.update("UPDATE reservation SET status = 'CANCELED' WHERE id = ?", r)
        // 마감 회차 CONFIRMED 예약을 열린 회차로 옮기면 열린 쪽 +1만(행 있음)
        val r2 = reservation(closed, 11L)
        jdbcTemplate.update("UPDATE reservation SET schedule_id = ? WHERE id = ?", s, r2)
        assertEquals(2, cntOf(s, 11L))
        assertEquals(actualOf(s, 11L), cntOf(s, 11L))
    }

    @Test
    fun `L1-17 — B 엄격 — 열린 회차 행 없음 쌍의 만료는 틱 실패(tick failed + skipped SR001), C가 복구하면 다음 틱 정상`() {
        val s = schedule.id!!
        holdSeat.hold(HoldSeatCommand(s, seat(s), 21L))
        holdSeat.hold(HoldSeatCommand(s, seat(s), 22L))
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 21", s)
        clock.advance(Duration.ofMinutes(6))
        val failed0 = counter("seat.hold.expiry.tick", "outcome", "failed")
        val e = assertThrows<Exception> { expire.expire() }
        assertEquals("SR001", sqlState(e))
        assertEquals(1.0, counter("seat.hold.expiry.tick", "outcome", "failed") - failed0)
        assertEquals(listOf(listOf<Any?>(21L, "missing", "skipped", "SR001", "expiry-trigger")), driftLog())
        assertEquals(2, holdCount(), "틱 롤백")

        val run = reconciler.runOnce("reconcile-app")
        assertEquals(1, run.repaired, "$run")
        assertEquals(1, cntOf(s, 21L))

        assertEquals(2, expire.expire(), "복구 뒤 틱 정상")
        assertEquals(0, cntOf(s, 21L))
        assertEquals(0, cntOf(s, 22L))
    }
}

/**
 * L1-3 · L1-20 — prepare(counter·quota 계열)는 마감 회차에 쿼터 행을 만들지 않는다(쿼터 행 전체 비교) ·
 * 마감 경계에서 남을 수 있는 cnt 0 행은 판정기·대조에서 빠지고 정리 ①이 지운다(무해).
 */
@TestPropertySource(properties = ["seat.hold.limit-strategy=counter"])
class PrepareCloseImplTest : DriftTestSupport() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var reconciler: CounterReconciler
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var redis: org.springframework.data.redis.core.StringRedisTemplate

    private fun allQuota() = jdbcTemplate.queryForList("SELECT schedule_id, user_id, cnt, xmin::text AS x FROM user_hold_quota ORDER BY 1, 2")

    @Test
    fun `마감·없는 회차 거절 전후 쿼터 행 전체(cnt 0 포함)가 같다 — 열린 회차는 지금처럼 행을 준비`() {
        val s = schedule.id!!
        holdSeat.hold(HoldSeatCommand(s, createSeat(1).id!!, 1L))
        val closed = newSchedule(NOW).id!!
        val closedSeat = productSeatRepository.save(com.jun.labs.seatreservation.domain.ProductSeat(closed, "A", 1, 1)).id!!
        val before = allQuota()
        assertThrows<com.jun.labs.seatreservation.domain.SeatReservationException> { holdSeat.hold(HoldSeatCommand(closed, closedSeat, 2L)) }
        assertThrows<com.jun.labs.seatreservation.domain.SeatReservationException> { holdSeat.hold(HoldSeatCommand(closed + 999, closedSeat, 3L)) }
        assertEquals(before, allQuota(), "거절이 쿼터 행을 남겼다")
        holdSeat.hold(HoldSeatCommand(s, createSeat(2).id!!, 4L))
        assertEquals(1, cntOf(s, 4L), "열린 회차는 준비 + 증가")
    }

    @Test
    fun `L1-20 — 마감 회차에 남은 cnt 0 행은 판정기·대조에서 빠지고 정리 ①이 지운다`() {
        val closed = newSchedule(NOW).id!!
        quota(closed, 5L, 0) // 경계에서 prepare가 남긴 행을 흉내
        val judge = com.jun.labs.seatreservation.loadtest.LoadtestDataService(jdbcTemplate, properties, redis, clock)
        val c = judge.consistency(20)
        assertEquals(0L, (c["v_counter_mismatch"] as Number).toLong())
        assertEquals(1L, (c["counter_excluded_pairs"] as Number).toLong())
        val x = allQuota()
        val run = reconciler.runOnce("reconcile-app")
        assertEquals(0, run.found)
        assertEquals(x, allQuota(), "대조가 마감 회차 행을 건드렸다")
        val cleanup = QuotaCleanup(
            jdbcTemplate, tx,
            properties.copy(counterDrift = properties.counterDrift.copy(cleanup = properties.counterDrift.cleanup.copy(mode = SeatHoldProperties.CleanupMode.SCHEDULE))),
            meters, clock,
        )
        assertEquals(1, cleanup.runOnce().rowsDeleted)
        assertEquals(null, cntOf(closed, 5L))
        assertTrue(allQuota().isEmpty())
    }
}
