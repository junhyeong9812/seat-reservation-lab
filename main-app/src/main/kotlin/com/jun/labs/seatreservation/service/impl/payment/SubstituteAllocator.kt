package com.jun.labs.seatreservation.service.impl.payment

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.SalesCloseGate
import com.jun.labs.seatreservation.service.impl.drift.MemoryChangedPairs
import com.jun.labs.seatreservation.service.impl.limit.upsertQuota
import com.jun.labs.seatreservation.service.impl.limit.upsertSqlFor
import com.jun.labs.seatreservation.service.impl.payment.PaymentIntents.State
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-017 설계-5 S 대체 배정(거부 뒤 · intent 잠금 아래 · 호출자 트랜잭션 안). 잠금 순서는 A′ 만료 배치와 같다(RD17-P2):
 * intent(호출자) → 회차 FOR SHARE(판매 마감) → 쿼터 쌍 → 원래 홀드 → 원래 좌석 → 새 좌석(SKIP LOCKED — 기다리지 않음).
 * 쿼터(카운터 = 홀드 + 확정 수): 원래 홀드가 아직 있으면 그 홀드를 대체 예약으로 **옮긴다**(삭제 −1 · 예약 +1 = 그대로).
 * 원래 홀드가 이미 없으면(배치가 지우며 −1 했다) 선점과 같은 upsert로 +1 · 상한 판정.
 * 하나라도 안 되면 [Unavailable]을 던진다 — 호출자가 이 트랜잭션을 롤백하고 CLOSE로 넘긴다(N17-9).
 * 지원 매수 방식은 counter-upsert · counter-upsert-precheck뿐이다(기동 검사 — 카운터를 앱 문장이 맞추는 방식).
 */
@Component
class SubstituteAllocator(
    private val jdbc: JdbcTemplate,
    private val salesClose: SalesCloseGate,
    private val holdProperties: SeatHoldProperties,
    private val changes: MemoryChangedPairs,
) {
    class Unavailable(reason: String) : RuntimeException(reason)

    data class Allocated(val reservationId: Long, val seatId: Long)

    fun allocate(intent: PaymentIntents.Intent, code: ErrorCode, now: Instant): Allocated {
        // 판매 마감(시작 정각 이후)이면 대체하지 않는다 — SCHEDULE_CLOSED도 대체 실패로 본다
        try {
            salesClose.assertOpenLocked(intent.scheduleId)
        } catch (e: SeatReservationException) {
            throw Unavailable("schedule-closed") // SCHEDULE_CLOSED · 회차 없음(SEAT_NOT_FOUND) — DB 오류는 그대로 올려 호출자가 CLOSE로 넘긴다
        } finally {
            salesClose.clearDecision()
        }
        jdbc.queryForList(
            "SELECT 1 FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", intent.scheduleId, intent.userId,
        )
        val original = jdbc.query(
            "DELETE FROM seat_hold WHERE id = ? RETURNING seat_id", { rs, _ -> rs.getLong(1) }, intent.holdId,
        ).singleOrNull()
        if (original != null) {
            jdbc.update(
                "UPDATE product_seat SET status = 'AVAILABLE' WHERE id = ? AND status = 'HELD' AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = product_seat.id)",
                original,
            )
        }
        val seatId = jdbc.queryForList(
            """
            SELECT id FROM product_seat
             WHERE schedule_id = ? AND section = ? AND status = 'AVAILABLE'
             ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            Long::class.java, intent.scheduleId, intent.section,
        ).singleOrNull() ?: throw Unavailable("no-seat")
        if (original == null) {
            val command = HoldSeatCommand(scheduleId = intent.scheduleId, seatId = seatId, userId = intent.userId)
            if (!upsertQuota(jdbc, upsertSqlFor(holdProperties), command, holdProperties.maxPerUser)) throw Unavailable("limit")
        }
        jdbc.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", seatId)
        val reservationId = jdbc.queryForObject(
            """
            INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at, intent_id, origin)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'SUBSTITUTE') RETURNING id
            """.trimIndent(),
            Long::class.java, intent.scheduleId, seatId, intent.userId, intent.uid, ReservationStatus.CONFIRMED.name, Timestamp.from(now), intent.uid,
        )!!
        jdbc.update(
            "UPDATE payment_intent SET state = ?, result_code = ?, reservation_id = ? WHERE uid = ?",
            State.SUBSTITUTED.name, code.name, reservationId, intent.uid,
        )
        changes.changed(intent.scheduleId, intent.userId)
        return Allocated(reservationId, seatId)
    }
}
