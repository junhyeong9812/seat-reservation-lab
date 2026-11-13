package com.jun.labs.seatreservation.service.impl.payment

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.service.CompensationProperties
import com.jun.labs.seatreservation.service.impl.payment.PaymentIntents.State
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock

/**
 * ADR-017 설계-4.5 안전망 — 시간 논증 대신 intent 잠금 아래 판정(RD17-2):
 * OPEN intent 중 홀드가 없거나 만료(`expires_at ≤ now`)인 것을 고르고, intent마다 한 트랜잭션에서 다시 잠가 아직 OPEN이고 홀드가 그대로 없거나 만료면 —
 * 모드 ≠ none: CLOSING · closed_reason SWEEP · 재생 코드(홀드 없음 HOLD_NOT_FOUND · 만료 HOLD_EXPIRED — RD17-P3) + CLOSE 작업(source SWEEP).
 * 모드 none: 상태를 바꾸지 않고 지표 `seat.compensation.unresolved`만 센다(Q1 — 탐지).
 * PG에는 묻지 않는다(판정은 서버 기록만 — PG가 느려도 멈추지 않는다).
 */
@Component
class CompensationSweep(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val intents: PaymentIntents,
    private val tasks: CompensationTasks,
    private val props: CompensationProperties,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    /** @return 닫은(모드 ≠ none) 또는 탐지한(none) intent 수 */
    fun runOnce(): Int {
        val now = clock.instant()
        val candidates = jdbc.queryForList(
            """
            SELECT i.uid FROM payment_intent i
             WHERE i.state = 'OPEN'
               AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.id = i.hold_id AND h.expires_at > ?)
             ORDER BY i.created_at LIMIT ?
            """.trimIndent(),
            String::class.java, Timestamp.from(now), props.sweep.batch,
        )
        return candidates.count { uid -> tx.execute { close(uid) }!! }
    }

    private fun close(uid: String): Boolean {
        val intent = intents.lock(uid) ?: return false
        if (intent.state != State.OPEN) return false
        val now = clock.instant()
        val expiresAt = jdbc.query(
            "SELECT expires_at FROM seat_hold WHERE id = ?", { rs, _ -> rs.getTimestamp(1).toInstant() }, intent.holdId,
        ).singleOrNull()
        if (expiresAt != null && expiresAt.isAfter(now)) return false // 그 사이 살아 있다(④ 연장 등) — 다음 주기
        if (!props.mode.createsTasks) {
            meters.counter(UNRESOLVED_METRIC).increment()
            return true
        }
        val code = if (expiresAt == null) ErrorCode.HOLD_NOT_FOUND else ErrorCode.HOLD_EXPIRED
        jdbc.update(
            "UPDATE payment_intent SET state = ?, closed_reason = ?, result_code = ? WHERE uid = ?",
            State.CLOSING.name, CLOSED_BY_SWEEP, code.name, uid,
        )
        tasks.enqueue(uid, CompensationTasks.Type.CLOSE, CompensationTasks.Source.SWEEP, now)
        return true
    }

    companion object {
        const val UNRESOLVED_METRIC = "seat.compensation.unresolved"
        const val CLOSED_BY_SWEEP = "SWEEP"
    }
}
