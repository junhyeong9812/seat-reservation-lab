package com.jun.labs.seatreservation.service.impl.payment

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * ADR-017 설계-4.1 · 4.2 결제 의도(`payment_intent`) — uid 소유 행이자 확정 · 거부 · 보상의 직렬화 지점.
 * payment-start가 홀드마다 하나를 만든다(같은 홀드의 재호출은 같은 uid · 같은 pay_by — `hold_id` 유니크).
 * 모든 쓰기는 호출자의 트랜잭션 안에서 한다.
 */
@Component
class PaymentIntents(private val jdbc: JdbcTemplate) {

    /**
     * V15 표가 있는가 — 기동 검사는 결제 시작 모드(① · ④)에만 V15를 요구한다. 그 밖의 모드는 V15 앞 스키마(기준선 재현 — Flyway target 7 등)로도 뜨고,
     * 그때는 intent가 있을 수 없으므로 조회 없이 옛 경로로 간다(N17-10 · N17-11). 스키마는 기동 중(Flyway) 정해지므로 처음 물을 때 한 번만 본다.
     */
    val available: Boolean by lazy {
        jdbc.queryForObject("SELECT to_regclass('payment_intent') IS NOT NULL", Boolean::class.java) == true
    }

    /** 홀드의 intent(없으면 null) — 잠금 없음 */
    fun findByHold(holdId: Long): Intent? = if (!available) null else jdbc.query(
        "SELECT $COLUMNS FROM payment_intent WHERE hold_id = ?", ROW, holdId,
    ).singleOrNull()

    /** uid의 intent를 잠근다(설계-4.3 ⓐ — 모든 경로의 첫 잠금). 없으면 null(옛 경로) */
    fun lock(uid: String): Intent? = if (!available) null else jdbc.query(
        "SELECT $COLUMNS FROM payment_intent WHERE uid = ? FOR UPDATE", ROW, uid,
    ).singleOrNull()

    /** uid의 intent를 잠그되 남이 쥐고 있으면 건너뛴다(안전망 — 확정 중인 intent는 다음 주기에 본다). 없거나 잠겨 있으면 null */
    fun tryLock(uid: String): Intent? = if (!available) null else jdbc.query(
        "SELECT $COLUMNS FROM payment_intent WHERE uid = ? FOR UPDATE SKIP LOCKED", ROW, uid,
    ).singleOrNull()

    /** uid의 intent(잠금 없음) */
    fun find(uid: String): Intent? = if (!available) null else jdbc.query(
        "SELECT $COLUMNS FROM payment_intent WHERE uid = ?", ROW, uid,
    ).singleOrNull()

    /**
     * 홀드의 intent를 만들거나(첫 호출) 이미 있는 것을 돌려준다. 홀드 행에서 회차 · 좌석 · section을 옮겨 적는다(설계-10 RD17-6).
     * 동시 첫 호출은 `hold_id` 유니크에서 한쪽이 기다렸다가 DO NOTHING → 둘 다 같은 행을 읽는다.
     * 홀드가 그 사이 사라졌으면(만료 배치 · 확정) null.
     */
    fun openForHold(holdId: Long, userId: Long, payBy: Instant, now: Instant): Intent? {
        jdbc.update(
            """
            INSERT INTO payment_intent (uid, hold_id, user_id, schedule_id, seat_id, section, pay_by, state, created_at)
            SELECT ?, h.id, h.user_id, h.schedule_id, h.seat_id, s.section, ?, '${State.OPEN}', ?
              FROM seat_hold h JOIN product_seat s ON s.id = h.seat_id
             WHERE h.id = ? AND h.user_id = ?
            ON CONFLICT (hold_id) DO NOTHING
            """.trimIndent(),
            UUID.randomUUID().toString(), Timestamp.from(payBy), Timestamp.from(now), holdId, userId,
        )
        return findByHold(holdId)
    }

    enum class State { OPEN, CONFIRMED, REJECTED, SUBSTITUTED, CLOSING }

    data class Intent(
        val uid: String,
        val holdId: Long,
        val userId: Long,
        val scheduleId: Long,
        val seatId: Long,
        val section: String,
        val payBy: Instant,
        val state: State,
        val resultCode: String?,
        val reservationId: Long?,
        val closedReason: String?,
    )

    companion object {
        private const val COLUMNS = "uid, hold_id, user_id, schedule_id, seat_id, section, pay_by, state, result_code, reservation_id, closed_reason"
        private val ROW = org.springframework.jdbc.core.RowMapper { rs, _ ->
            Intent(
                uid = rs.getString(1), holdId = rs.getLong(2), userId = rs.getLong(3), scheduleId = rs.getLong(4),
                seatId = rs.getLong(5), section = rs.getString(6), payBy = rs.getTimestamp(7).toInstant(),
                state = State.valueOf(rs.getString(8)), resultCode = rs.getString(9),
                reservationId = rs.getObject(10) as Long?, closedReason = rs.getString(11),
            )
        }
    }
}
