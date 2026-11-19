package com.jun.labs.seatreservation.service.impl.holdgroup

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.impl.payment.PaymentIntents
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/**
 * ADR-018 설계-4 묶음 결제 시작 — 1석 ④ 결제 시작(PaymentStartService.paymentStart)과 같은 순서를 묶음에 적용한다(기동 검사가 conditional-extend만 허용).
 * 1. 재호출: 이 묶음의 결제 의도가 있으면 판정 · 연장 없이 돌려준다(홀드 없음 404 · 남의 것 403 · 만료 409).
 * 2. 연장: 묶음의 아직 만료되지 않은 홀드 행만 id 순으로 잠그고(해제 · 확정과 같은 순서) 조건이 맞는 행만 `expires_at = now + W`.
 *    만료된 행(`expires_at <= now`)은 잠그지 않는다(만료된 묶음은 0행 → 진단 → 409). 만료 배치와 엇갈리지 않는 것은 두 경로의 now가 같을 때만이다 —
 *    앱 시계로 따로 읽은 now가 달라 만료 시각 경계(이쪽 now < E ≤ 배치 now)에서는 배치(물리 순 DELETE)와 엇갈려 40P01이 날 수 있다(드묾 · 롤백으로 회복).
 *    `expires_at < now + W` = 연장이 만료를 실제로 늦출 때만. N행이면 extended = true.
 * 3. 0행이면 원인 진단(없음 404 → 남의 것 403 → 만료 409 → 남은 시간 θ 이하 409 → 그 밖은 extended = false).
 * 4. 0도 N도 아니면 묶음의 만료가 갈라진 것 — 롤백 · 409 HOLD_EXPIRED · ERROR 로그.
 * 결제 의도는 1개(group_id · seat_count = N · hold_id 등은 id가 가장 작은 홀드) — 연장은 결제 의도를 만들기 전에만 일어난다.
 */
@Service
class HoldGroupPaymentStartService(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val confirmProperties: SeatConfirmProperties,
    private val intents: PaymentIntents,
) {
    @Transactional
    fun paymentStart(groupId: Long, userId: Long): HoldGroupPaymentStart {
        val now = clock.instant()
        replay(groupId, userId, now)?.let { return it }
        val seatCount = jdbc.queryForList("SELECT seat_count FROM hold_group WHERE id = ?", Int::class.java, groupId).singleOrNull()
            ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        val window = confirmProperties.paymentWindow
        val extended = jdbc.query(
            """
            UPDATE seat_hold
               SET expires_at = ?, extended_at = ?
             WHERE id IN (SELECT id FROM seat_hold WHERE group_id = ? AND expires_at > ? ORDER BY id FOR UPDATE)
               AND user_id = ?
               AND extended_at IS NULL
               AND expires_at > ?
               AND expires_at < ?
               AND NOT EXISTS (SELECT 1 FROM payment_intent i WHERE i.group_id = ?)
            RETURNING expires_at
            """.trimIndent(),
            { rs, _ -> rs.getTimestamp(1).toInstant() },
            Timestamp.from(now.plus(window)), Timestamp.from(now), groupId, Timestamp.from(now), userId,
            Timestamp.from(now.plus(confirmProperties.paymentMinRemaining)), Timestamp.from(now.plus(window)), groupId,
        )
        return when (extended.size) {
            seatCount -> open(groupId, userId, seatCount, extended.first(), extended = true, now)
            0 -> diagnose(groupId, userId, seatCount, now)
            else -> split(groupId, userId, "연장 ${extended.size}행 · 묶음 ${seatCount}석")
        }
    }

    /** 재호출 — 결제 의도가 있으면 지금 홀드로 404 · 403 · 만료 409만 본다(θ 검사 없음 — 1석 재생과 같다) */
    private fun replay(groupId: Long, userId: Long, now: Instant): HoldGroupPaymentStart? {
        val intent = intents.findByGroup(groupId) ?: return null
        val hold = holdsOrReject(groupId, groupHolds(groupId), userId, now, intent.seatCount)
        return HoldGroupPaymentStart(groupId, hold.expiresAt, extended = hold.extendedAt != null, paymentUid = intent.uid, payBy = intent.payBy, seatCount = intent.seatCount)
    }

    private fun diagnose(groupId: Long, userId: Long, seatCount: Int, now: Instant): HoldGroupPaymentStart {
        val hold = holdsOrReject(groupId, groupHolds(groupId), userId, now, seatCount)
        if (hold.extendedAt != null) return open(groupId, userId, seatCount, hold.expiresAt, extended = true, now)
        if (!hold.expiresAt.isAfter(now.plus(confirmProperties.paymentMinRemaining))) throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        if (!hold.expiresAt.isBefore(now.plus(confirmProperties.paymentWindow))) {
            return open(groupId, userId, seatCount, hold.expiresAt, extended = false, now)
        }
        // 연장의 NOT EXISTS가 0행을 만든 경우 — 다른 호출이 그 사이 결제 의도를 만들었다(경합) → 재생
        replay(groupId, userId, now)?.let { return it }
        log.error("묶음 연장이 0행인데 진단은 '내 것 · 활성 · 연장 전 · 범위 안'이다(설계상 생기지 않는다 — 409 HOLD_EXPIRED) group={} user={} expires_at={} now={}", groupId, userId, hold.expiresAt, now)
        throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
    }

    private fun open(groupId: Long, userId: Long, seatCount: Int, expiresAt: Instant, extended: Boolean, now: Instant): HoldGroupPaymentStart {
        val intent = intents.openForGroup(groupId, userId, seatCount, expiresAt.minus(confirmProperties.payByMargin), now)
            ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        return HoldGroupPaymentStart(groupId, expiresAt, extended, paymentUid = intent.uid, payBy = intent.payBy, seatCount = intent.seatCount)
    }

    private class Hold(val userId: Long, val expiresAt: Instant, val extendedAt: Instant?)

    private fun groupHolds(groupId: Long): List<Hold> = jdbc.query(
        "SELECT user_id, expires_at, extended_at FROM seat_hold WHERE group_id = ?",
        { rs, _ -> Hold(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3)?.toInstant()) }, groupId,
    )

    /**
     * 없음 404 · 남의 것 403 · 만료 409 · 남은 홀드 수 ≠ [seatCount](부분 묶음 — 설계상 0) 409 · 만료가 갈라짐(설계상 0) 409 — 통과하면 묶음 대표 행(모두 같은 값).
     * 부분 묶음으로 결제 의도를 만들거나 재생하면 남은 좌석보다 많은 금액이 PG에 등록된다 — 결제 의도를 만들기 · 돌려주기 전에 거부한다(설계-4 부분 결과 거부).
     */
    private fun holdsOrReject(groupId: Long, holds: List<Hold>, userId: Long, now: Instant, seatCount: Int): Hold {
        if (holds.isEmpty()) throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if (holds.any { it.userId != userId }) throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        if (holds.any { !it.expiresAt.isAfter(now) }) throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        if (holds.size != seatCount) split(groupId, userId, "남은 홀드 ${holds.size}행 · 묶음 ${seatCount}석")
        if (holds.map { it.expiresAt to it.extendedAt }.distinct().size > 1) split(groupId, userId, "홀드 만료 ${holds.map { it.expiresAt }.distinct()}")
        return holds.first()
    }

    private fun split(groupId: Long?, userId: Long, detail: String): Nothing {
        log.error("묶음 홀드가 부분이거나 만료가 갈라졌다(설계상 생기지 않는다 — 롤백 · 409 HOLD_EXPIRED) group={} user={} {}", groupId, userId, detail)
        throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
    }

    companion object {
        private val log = LoggerFactory.getLogger(HoldGroupPaymentStartService::class.java)
    }
}

data class HoldGroupPaymentStart(
    val groupId: Long,
    val expiresAt: Instant,
    val extended: Boolean,
    val paymentUid: String,
    val payBy: Instant,
    /** PG 등록 금액 = seat_count × seat.payment.amount(재호출에서도 결제 의도의 값 — 같은 금액) */
    val seatCount: Int,
)
