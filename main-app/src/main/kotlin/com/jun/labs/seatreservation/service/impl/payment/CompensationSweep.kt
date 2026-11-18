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
 * 모드 none: 상태를 바꾸지 않는다(Q1 — 탐지만). 잠금 없는 집계 한 문장으로 지금 미해결 수를 세어 지표 `seat.compensation.unresolved`에 더하고
 *   WARN 로그를 남긴다 — 카운터는 '주기마다 본 미해결 수'의 합이다(같은 intent를 주기마다 다시 센다: 기울기 ÷ 주기 = 지금 미해결 수).
 *   none은 상태를 바꾸지 않으므로 잠가 볼 이유가 없고, LIMIT 배치로 돌면 가장 오래된 batch건만 매번 다시 보게 된다.
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
        if (!intents.available) return 0 // V15 앞 스키마 — intent가 없다
        val now = clock.instant()
        if (!props.mode.createsTasks) return detectOnly(now)
        // 순회 위치(uid 순)에서 이어 고르고, 모자라면 처음부터 채운다 — 잠겨 있거나 계속 실패하는 후보가 매 주기 배치 자리를 차지해
        // 뒤 후보가 영영 안 뽑히는 일을 막는다(한 주기에 배치 이하면 전부 본다)
        val after = cursor.get()
        val candidates = unclosed(now, after, props.sweep.batch).toMutableList()
        if (candidates.size < props.sweep.batch && after != null) {
            candidates += unclosed(now, null, props.sweep.batch - candidates.size).filter { it !in candidates }
        }
        cursor.set(if (candidates.size < props.sweep.batch) null else candidates.last())
        // 후보마다 따로 — 잠긴 intent(확정 중)는 기다리지 않고 다음 주기로, 한 건의 오류가 주기의 나머지를 막지 않게
        return candidates.count { uid ->
            try {
                tx.execute { close(uid) }!!
            } catch (e: RuntimeException) {
                meters.counter(SWEEP_FAILED_METRIC).increment()
                log.error("안전망이 intent 하나를 닫지 못했다 — 다음 주기에 다시 본다 uid={}", uid, e)
                false
            }
        }
    }

    private val lastUnresolved = java.util.concurrent.atomic.AtomicInteger(0)
    private val cursor = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private fun unclosed(now: java.time.Instant, afterUid: String?, limit: Int): List<String> = jdbc.queryForList(
        """
        SELECT i.uid FROM payment_intent i
         WHERE i.state = 'OPEN'
           AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.id = i.hold_id AND h.expires_at > ?)
           AND (CAST(? AS VARCHAR) IS NULL OR i.uid > ?)
         ORDER BY i.uid LIMIT ?
        """.trimIndent(),
        String::class.java, Timestamp.from(now), afterUid, afterUid, limit,
    )

    private fun detectOnly(now: java.time.Instant): Int {
        val unresolved = jdbc.queryForObject(
            """
            SELECT count(*) FROM payment_intent i
             WHERE i.state = 'OPEN'
               AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.id = i.hold_id AND h.expires_at > ?)
            """.trimIndent(),
            Int::class.java, Timestamp.from(now),
        )!!
        if (unresolved > 0) meters.counter(UNRESOLVED_METRIC).increment(unresolved.toDouble())
        // 로그는 수가 바뀔 때만(none은 상태를 안 바꿔 같은 수가 주기마다 다시 나온다 — 5초마다 같은 WARN을 남기지 않는다)
        if (unresolved != lastUnresolved.getAndSet(unresolved) && unresolved > 0) {
            log.warn("보상 모드 none — 홀드가 없거나 만료된 OPEN intent {}개(결제됐다면 돈만 받은 상태 · 작업은 만들지 않는다)", unresolved)
        }
        return unresolved
    }

    private fun close(uid: String): Boolean {
        val intent = intents.tryLock(uid) ?: return false
        if (intent.state != State.OPEN) return false
        val now = clock.instant()
        val expiresAt = jdbc.query(
            "SELECT expires_at FROM seat_hold WHERE id = ?", { rs, _ -> rs.getTimestamp(1).toInstant() }, intent.holdId,
        ).singleOrNull()
        if (expiresAt != null && expiresAt.isAfter(now)) return false // 그 사이 살아 있다(④ 연장 등) — 다음 주기
        val code = if (expiresAt == null) ErrorCode.HOLD_NOT_FOUND else ErrorCode.HOLD_EXPIRED
        jdbc.update(
            "UPDATE payment_intent SET state = ?, closed_reason = ?, result_code = ? WHERE uid = ?",
            State.CLOSING.name, CLOSED_BY_SWEEP, code.name, uid,
        )
        tasks.enqueue(uid, CompensationTasks.Type.CLOSE, CompensationTasks.Source.SWEEP, now)
        return true
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(CompensationSweep::class.java)
        const val UNRESOLVED_METRIC = "seat.compensation.unresolved"
        const val SWEEP_FAILED_METRIC = "seat.compensation.sweep_failed"
        const val CLOSED_BY_SWEEP = "SWEEP"
    }
}
