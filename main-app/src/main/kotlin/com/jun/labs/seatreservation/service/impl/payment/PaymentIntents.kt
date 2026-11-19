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
     * 홀드가 그 사이 사라졌으면(만료 배치 · 확정 · 해제) null.
     * 홀드 행을 `FOR KEY SHARE`로 읽는다(ADR-018 설계-3) — 해제의 홀드 행 `FOR UPDATE`와 충돌해 둘 중 먼저 잡은 쪽이 끝난 뒤에 다른 쪽이 진행한다
     * (해제가 먼저면 여기서 홀드를 찾지 못해 null → 404 · 이쪽이 먼저면 해제가 결제 의도를 보고 409).
     */
    fun openForHold(holdId: Long, userId: Long, payBy: Instant, now: Instant): Intent? {
        jdbc.update(
            """
            INSERT INTO payment_intent (uid, hold_id, user_id, schedule_id, seat_id, section, pay_by, state, created_at)
            SELECT ?, h.id, h.user_id, h.schedule_id, h.seat_id, s.section, ?, '${State.OPEN}', ?
              FROM seat_hold h JOIN product_seat s ON s.id = h.seat_id
             WHERE h.id = ? AND h.user_id = ?
               FOR KEY SHARE OF h
            ON CONFLICT (hold_id) DO NOTHING
            """.trimIndent(),
            UUID.randomUUID().toString(), Timestamp.from(payBy), Timestamp.from(now), holdId, userId,
        )
        return findByHold(holdId)
    }

    // ---- ADR-018 묶음 결제 의도(V16 — group_id · seat_count) ----------------------------------------------------------

    /** 묶음의 결제 의도(없으면 null) — 잠금 없음 */
    fun findByGroup(groupId: Long): GroupIntent? = jdbc.query(
        "SELECT $GROUP_COLUMNS FROM payment_intent WHERE group_id = ?", GROUP_ROW, groupId,
    ).singleOrNull()

    /** uid의 결제 의도를 잠근다(묶음 확정의 첫 잠금) — 없으면 null. 1석 결제 의도면 [GroupIntent.groupId]가 null */
    fun lockForGroup(uid: String): GroupIntent? = jdbc.query(
        "SELECT $GROUP_COLUMNS FROM payment_intent WHERE uid = ? FOR UPDATE", GROUP_ROW, uid,
    ).singleOrNull()

    /**
     * 묶음의 결제 의도를 만들거나(첫 호출) 이미 있는 것을 돌려준다(ADR-018 설계-4). `hold_id` · `seat_id` · `section`은 id가 가장 작은 홀드의 값 · `seat_count = N`.
     * 묶음의 홀드 행을 모두 id 순으로 `FOR KEY SHARE`로 읽는다(해제와 직렬화 — [openForHold]와 같은 이유). 홀드가 그 사이 사라졌으면 null.
     * 동시 첫 호출은 `group_id` 유니크에서 한쪽이 기다렸다가 DO NOTHING → 둘 다 같은 행을 읽는다.
     */
    fun openForGroup(groupId: Long, userId: Long, seatCount: Int, payBy: Instant, now: Instant): GroupIntent? {
        jdbc.update(
            """
            INSERT INTO payment_intent (uid, hold_id, user_id, schedule_id, seat_id, section, pay_by, state, created_at, group_id, seat_count)
            SELECT ?, h.id, h.user_id, h.schedule_id, h.seat_id, s.section, ?, '${State.OPEN}', ?, ?, ?
              FROM (SELECT id, user_id, schedule_id, seat_id FROM seat_hold WHERE group_id = ? AND user_id = ? ORDER BY id FOR KEY SHARE) h
              JOIN product_seat s ON s.id = h.seat_id
             ORDER BY h.id LIMIT 1
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            UUID.randomUUID().toString(), Timestamp.from(payBy), Timestamp.from(now), groupId, seatCount, groupId, userId,
        )
        return findByGroup(groupId)
    }

    data class GroupIntent(
        val uid: String,
        val holdId: Long,
        val userId: Long,
        val scheduleId: Long,
        val groupId: Long?,
        val seatCount: Int,
        val payBy: Instant,
        val state: State,
        val resultCode: String?,
    )

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
        private const val GROUP_COLUMNS = "uid, hold_id, user_id, schedule_id, group_id, seat_count, pay_by, state, result_code"
        private val GROUP_ROW = org.springframework.jdbc.core.RowMapper { rs, _ ->
            GroupIntent(
                uid = rs.getString(1), holdId = rs.getLong(2), userId = rs.getLong(3), scheduleId = rs.getLong(4),
                groupId = rs.getObject(5) as Long?, seatCount = rs.getInt(6), payBy = rs.getTimestamp(7).toInstant(),
                state = State.valueOf(rs.getString(8)), resultCode = rs.getString(9),
            )
        }
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
