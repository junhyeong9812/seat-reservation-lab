package com.jun.labs.seatreservation.service.impl.payment

import com.jun.labs.seatreservation.service.CompensationProperties
import com.jun.labs.seatreservation.service.PaymentProperties
import com.jun.labs.seatreservation.service.impl.payment.PgClient.Call
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * ADR-017 설계-4.4 보상 워커 — 한 주기([runOnce]):
 * ① 리스가 지난 IN_PROGRESS를 PENDING으로 되돌린다(앱 죽음 · RD17-5) — 시도 1회로 세고 결과 모름으로 둔다(PG를 불렀는지 모른다 · 반복 죽음도 상한에서 DEAD)
 * ② `FOR UPDATE SKIP LOCKED LIMIT batch`로 기한이 된 PENDING을 집어 IN_PROGRESS + `lease_until`로 커밋한다
 * ③ 작업마다 PG를 부른다(트랜잭션 밖 · uid 멱등) — 앞 시도가 결과 모름이었으면 먼저 조회한다.
 *   리스 안에 PG 호출(조회 + 호출)이 끝나지 못할 만큼 남았으면 부르지 않고 놓아준다(배치가 리스보다 길어도 다른 워커와 겹쳐 부르지 않게)
 * ④ 결과를 응답 분류표(설계-3)로 쓴다 — 쓰기는 `WHERE id AND status = IN_PROGRESS AND lease_until = 집을 때 값`(fencing: 리스를 잃은 워커의 늦은 쓰기는 0행)
 */
@Component
class CompensationWorker(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val pg: PgClient,
    private val props: CompensationProperties,
    payment: PaymentProperties,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    /** 작업 하나의 PG 시간 상한(결과 모름 조회 + 호출 — 각각 연결 + 읽기) */
    private val pgBudget: Duration = payment.pg.connectTimeout.plus(payment.pg.readTimeout).multipliedBy(2)

    private class Claimed(val id: Long, val uid: String, val type: CompensationTasks.Type, val attempts: Int, val lastResult: String?, val lease: Instant)

    /** @return 이 주기에 결과를 쓴 작업 수(DONE · 재시도 · DEAD) */
    fun runOnce(): Int {
        val now = clock.instant()
        // 회수도 시도 1회다 — 상한에 닿으면 여기서 DEAD(PG 호출 중 매번 죽는 작업이 끝없이 돌지 않게)
        val reclaimed = jdbc.query(
            """
            UPDATE compensation_task
               SET status = CASE WHEN attempts + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                   done_at = CASE WHEN attempts + 1 >= ? THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END,
                   lease_until = NULL, attempts = attempts + 1, last_result = '$UNKNOWN'
             WHERE status = 'IN_PROGRESS' AND lease_until < ?
            RETURNING id, uid, type, status
            """.trimIndent(),
            { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) },
            props.worker.maxAttempts, props.worker.maxAttempts, Timestamp.from(now), Timestamp.from(now),
        )
        if (reclaimed.isNotEmpty()) log.warn("리스가 지난 보상 작업 {}개를 회수했다(앞 워커가 끝내지 못했다)", reclaimed.size)
        reclaimed.filter { it[3] == "DEAD" }.forEach { (id, uid, type) ->
            meters.counter(DEAD_METRIC, "type", type).increment()
            log.error("보상 작업이 시도 상한({})을 넘어 DEAD(리스 회수) — 운영자 복구 필요(ops/compensation-dead) id={} uid={} type={} last={}", props.worker.maxAttempts, id, uid, type, UNKNOWN)
        }
        val claimed = tx.execute { claim(now) }!!
        return claimed.count { handle(it) }
    }

    private fun claim(now: Instant): List<Claimed> {
        val lease = now.plus(props.worker.lease)
        val rows = jdbc.query(
            """
            SELECT id, uid, type, attempts, last_result FROM compensation_task
             WHERE status = 'PENDING' AND next_attempt_at <= ?
             ORDER BY next_attempt_at, id LIMIT ?
             FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            { rs, _ -> Claimed(rs.getLong(1), rs.getString(2), CompensationTasks.Type.valueOf(rs.getString(3)), rs.getInt(4), rs.getString(5), lease) },
            Timestamp.from(now), props.worker.batch,
        )
        if (rows.isEmpty()) return rows
        jdbc.update(
            "UPDATE compensation_task SET status = 'IN_PROGRESS', lease_until = ? WHERE id = ANY(?::bigint[])",
            Timestamp.from(lease), rows.map { it.id }.toTypedArray(),
        )
        return rows
    }

    /** @return 결과를 썼는가(fencing에 진 늦은 쓰기는 false) */
    private fun handle(t: Claimed): Boolean {
        if (!clock.instant().plus(pgBudget).isBefore(t.lease)) return release(t)
        val call = (if (t.lastResult == UNKNOWN) settledByLookup(t) else null) ?: invoke(t)
        val now = clock.instant()
        return when (val outcome = classify(t.type, call)) {
            is Outcome.Done -> finish(t, "DONE", outcome.result, now).also {
                if (it) meters.counter(DONE_METRIC, "type", t.type.name, "result", outcome.result).increment()
                if (it && outcome.moneyless) log.warn("매입 종결-실패 — 돈 없는 좌석(Q5) uid={} result={}", t.uid, outcome.result)
            }
            is Outcome.Retry -> retry(t, outcome.result, now)
        }
    }

    /** 앞 시도가 결과 모름이었으면 지금 상태로 끝났는지 본다(설계-3 분류표 '재시도' 행) */
    private fun settledByLookup(t: Claimed): Call? {
        val got = pg.get(t.uid)
        val done = when (t.type) {
            CompensationTasks.Type.CLOSE -> (got as? Call.Ok)?.state in CLOSED_STATES
            CompensationTasks.Type.CAPTURE -> (got as? Call.Ok)?.state == "CAPTURED"
        }
        return if (done) got else null
    }

    private fun invoke(t: Claimed): Call = when (t.type) {
        CompensationTasks.Type.CLOSE -> pg.close(t.uid)
        CompensationTasks.Type.CAPTURE -> pg.capture(t.uid)
    }

    private sealed interface Outcome {
        data class Done(val result: String, val moneyless: Boolean = false) : Outcome
        data class Retry(val result: String) : Outcome
    }

    /** 설계-3 응답 분류표 */
    private fun classify(type: CompensationTasks.Type, call: Call): Outcome = when (call) {
        is Call.Ok -> when (type) {
            CompensationTasks.Type.CLOSE -> if (call.state in CLOSED_STATES) Outcome.Done(call.state) else Outcome.Retry("STATE_${call.state}")
            CompensationTasks.Type.CAPTURE -> if (call.state == "CAPTURED") Outcome.Done(call.state) else Outcome.Retry("STATE_${call.state}")
        }
        // 처리된 거부 — CLOSE는 미등록에도 닫힘 표지를 남기므로 4xx가 오지 않아야 한다(오면 재시도로 드러낸다) · CAPTURE는 409만 종결-실패(돈 없는 좌석)
        is Call.Rejected -> when (type) {
            CompensationTasks.Type.CAPTURE ->
                // 409 = 처리된 거부(승인 만료 EXPIRED · 취소 CLOSED · 미승인 REGISTERED 등) — 종결-실패(명세 부록 B '등 409' · 돈 없는 좌석)
                if (call.status == 409) Outcome.Done("FAILED_${call.code ?: call.status}", moneyless = true)
                else Outcome.Retry("REJECTED_${call.code ?: call.status}") // 409 밖 4xx(미등록 404 등 원장 불일치) — 재시도 · 상한에서 DEAD로 드러낸다
            CompensationTasks.Type.CLOSE -> Outcome.Retry("REJECTED_${call.code ?: call.status}")
        }
        is Call.Unavailable -> Outcome.Retry(UNKNOWN)
    }

    private fun finish(t: Claimed, status: String, result: String, now: Instant): Boolean = jdbc.update(
        """
        UPDATE compensation_task SET status = ?, last_result = ?, lease_until = NULL, done_at = ?, attempts = attempts + 1
         WHERE id = ? AND status = 'IN_PROGRESS' AND lease_until = ?
        """.trimIndent(),
        status, result, Timestamp.from(now), t.id, Timestamp.from(t.lease),
    ) == 1

    private fun retry(t: Claimed, result: String, now: Instant): Boolean {
        val attempts = t.attempts + 1
        if (attempts >= props.worker.maxAttempts) {
            val wrote = finish(t, "DEAD", result, now)
            if (wrote) {
                meters.counter(DEAD_METRIC, "type", t.type.name).increment()
                log.error("보상 작업이 시도 상한({})을 넘어 DEAD — 운영자 복구 필요(ops/compensation-dead) id={} uid={} type={} last={}", props.worker.maxAttempts, t.id, t.uid, t.type, result)
            }
            return wrote
        }
        val backoff = props.worker.backoffInitial.multipliedBy(1L shl minOf(attempts - 1, 20)).coerceAtMost(props.worker.backoffMax)
        val wrote = jdbc.update(
            """
            UPDATE compensation_task SET status = 'PENDING', attempts = ?, last_result = ?, next_attempt_at = ?, lease_until = NULL
             WHERE id = ? AND status = 'IN_PROGRESS' AND lease_until = ?
            """.trimIndent(),
            attempts, result, Timestamp.from(now.plus(backoff)), t.id, Timestamp.from(t.lease),
        ) == 1
        if (wrote) meters.counter(RETRY_METRIC, "type", t.type.name).increment()
        return wrote
    }

    /** 리스 안에 못 끝낼 작업을 부르지 않고 놓아준다 — 시도로 세지 않는다(PG를 부르지 않았다) */
    private fun release(t: Claimed): Boolean {
        jdbc.update(
            "UPDATE compensation_task SET status = 'PENDING', lease_until = NULL WHERE id = ? AND status = 'IN_PROGRESS' AND lease_until = ?",
            t.id, Timestamp.from(t.lease),
        )
        return false
    }

    private fun Duration.coerceAtMost(max: Duration): Duration = if (this > max) max else this

    companion object {
        private val log = LoggerFactory.getLogger(CompensationWorker::class.java)
        const val DONE_METRIC = "seat.compensation.done"
        const val RETRY_METRIC = "seat.compensation.retry"
        const val DEAD_METRIC = "seat.compensation.dead"
        /** 연결 실패 · 타임아웃 · 5xx — 다음 시도 전에 조회한다 */
        const val UNKNOWN = "UNKNOWN"
        /** close의 종결 상태(환불 · 승인 취소 · 미결제 닫힘) */
        val CLOSED_STATES = setOf("REFUNDED", "VOIDED", "CLOSED")
    }
}
