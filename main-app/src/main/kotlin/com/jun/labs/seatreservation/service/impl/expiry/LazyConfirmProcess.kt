package com.jun.labs.seatreservation.service.impl.expiry

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationResult
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.drift.MemoryChangedPairs
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock

/**
 * ADR-014 B·C·D 확정(§설계-3.2 K1~K6) — 만료 판정만 통일한다(명세 §0 · §2). 확정 원자성 일반 해법과 A 확정은 ADR-015 몫이다(Q3).
 * 이 가드(K2·K3)는 B·C·D '이중 판매 0'의 최소 조건이다 — B·C·D는 저장 HELD 위에서도 재선점이 일어나므로, 잠금 없는 확인 → 무조건 UPDATE를 그대로 쓰면
 * '만료 직전 시작한 확정 + 만료 뒤 재선점'이 이중 판매를 만든다(§2.2 시각 역전).
 *
 * 판정 순서(계약 — 선검증 DS14-1): K1(404 · 403) → **K2가 잠근 좌석의 status** → K3 홀드 판정. K2를 K3보다 먼저 보는 이유: RC에서 `FOR UPDATE`는 잠금을 기다렸으면
 * 그 행의 최신 커밋 버전을 돌려주므로 그 사이 커밋된 남의 확정(RESERVED)이 보인다. K3만으로는 못 막는다 — 남의 확정은 자기 홀드 행을 지우므로 '다른 활성 홀드'로 남지 않고,
 * 내 홀드의 `mine_active`는 내 트랜잭션 시작 시각 기준이라 참일 수 있다. A와 '만료됐고 좌석도 RESERVED'의 응답이 다르다(A HOLD_EXPIRED · B·C·D SEAT_NOT_HELD).
 * 잠금 순서: 좌석(기다림) → 홀드 행 → 예약 INSERT(FK 검사 = 회차 · 좌석 `FOR KEY SHARE`). 쿼터 행은 잡지 않는다(지금도 그렇다).
 */
@Component
class LazyConfirmProcess(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val properties: SeatHoldProperties,
    private val clock: Clock,
    private val changes: MemoryChangedPairs,
) {
    fun confirm(command: ConfirmReservationCommand): ConfirmReservationResult = tx.execute { confirmInTx(command) }!!

    private fun confirmInTx(command: ConfirmReservationCommand): ConfirmReservationResult {
        // K1 홀드 찾기(잠금 없음) — 없으면 404 · 남의 것이면 403
        val hold = jdbc.queryForList("SELECT seat_id, schedule_id, user_id FROM seat_hold WHERE id = ?", command.holdId).singleOrNull()
            ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if ((hold["user_id"] as Number).toLong() != command.userId) throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        val seatId = (hold["seat_id"] as Number).toLong()
        val scheduleId = (hold["schedule_id"] as Number).toLong()
        // K2 좌석 잠금(기다린다) — 돌려받은 status를 K3보다 먼저 판정한다. D: RESERVED면 409 · B·C: HELD가 아니면 409(지금 계약 그대로)
        val status = jdbc.queryForObject("SELECT status FROM product_seat WHERE id = ? FOR UPDATE", String::class.java, seatId)!!
        val notHeld = if (properties.expiryMode.storesHeld) status != HELD else status == ExpiryRule.RESERVED
        if (notHeld) throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
        // K3 잠근 뒤 새 문장 + 홀드 행 잠금(회수 H4 · 배치 C4·D4 · 정리 ①의 DELETE가 이 행을 기다리거나 건너뛰게)
        val judged = jdbc.queryForList(
            """
            SELECT h.expires_at > now() AS mine_active,
                   EXISTS (SELECT 1 FROM seat_hold o WHERE o.seat_id = h.seat_id AND o.id <> h.id AND o.expires_at > now()) AS other_active
            FROM seat_hold h WHERE h.id = ? FOR UPDATE OF h
            """.trimIndent(),
            command.holdId,
        ).singleOrNull() ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND) // 그 사이 회수·배치가 지움 — A의 '배치 뒤 확정'과 같은 응답(Q14)
        // 내 홀드 만료 또는 다른 활성 홀드(시각 역전 — 만료 뒤 시작한 재선점이 먼저 커밋했다 · §2.2) → 409
        if (judged["mine_active"] != true || judged["other_active"] == true) throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        // K4~K6 전환(지금과 같은 쓰기) — created_at은 지금처럼 앱 Clock(만료 판정이 아니다)
        jdbc.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", seatId)
        jdbc.update("DELETE FROM seat_hold WHERE id = ?", command.holdId)
        val reservationId = jdbc.queryForObject(
            """
            INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
            Long::class.java, scheduleId, seatId, command.userId, command.paymentUid, ReservationStatus.CONFIRMED.name, Timestamp.from(clock.instant()),
        )!!
        // ADR-012 ⓐ: 확정은 실제 값 쪽만 바꾼다(쿼터 행 그대로) — A 확정과 같은 등록(method ≠ memory면 아무것도 안 함)
        changes.changed(scheduleId, command.userId)
        return ConfirmReservationResult(reservationId = reservationId, seatId = seatId, status = ReservationStatus.CONFIRMED)
    }

    private companion object {
        const val HELD = "HELD"
    }
}
