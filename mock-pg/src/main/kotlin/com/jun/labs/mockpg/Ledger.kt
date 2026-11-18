package com.jun.labs.mockpg

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-017 설계-3 원장 — uid마다 상태 하나(uid 단위로 직렬화 · `synchronized(entry)`).
 * 상태: REGISTERED → (pay) CAPTURED | (authorize) AUTHORIZED → (capture) CAPTURED · (authTtl) EXPIRED.
 * close: REGISTERED · EXPIRED → CLOSED(미결제) · AUTHORIZED → VOIDED · CAPTURED → REFUNDED · 미등록 uid → 닫힘 표지 CLOSED(RD17-P1) · 이미 닫힘 → 그대로.
 * 모든 변경 호출은 uid 멱등이다 — 같은 호출을 다시 하면 지금 상태를 그대로 돌려준다(처리된 결과만 — 503은 [Faults]가 처리 앞에서 내므로 기억되지 않는다 · RD17-13).
 */
@Component
class Ledger(private val clock: Clock, private val props: MockPgProperties) {
    private val entries = ConcurrentHashMap<String, Entry>()
    private val ops = ConcurrentHashMap<String, AtomicLong>()

    enum class State { REGISTERED, AUTHORIZED, CAPTURED, EXPIRED, VOIDED, REFUNDED, CLOSED }

    class Entry(val uid: String, val userId: Long?, val amount: Long?, val deadline: Instant?, val registeredAt: Instant) {
        @Volatile var state: State = State.REGISTERED
        @Volatile var authorizedAt: Instant? = null
        @Volatile var capturedAt: Instant? = null
        @Volatile var closedAt: Instant? = null
        /** 실제로 일어난 환불 수(이중 환불 판정 — 기대 ≤ 1) */
        @Volatile var refunds: Int = 0
        /** 미등록 uid에 남긴 닫힘 표지인가(RD17-P1) */
        @Volatile var tombstone: Boolean = false

        fun view() = PaymentView(uid, userId, amount, deadline, state.name, registeredAt, authorizedAt, capturedAt, closedAt, refunds, tombstone)
    }

    /** 결과 — [code]가 있으면 거부(409 · 404) */
    data class Result(val view: PaymentView?, val status: Int, val code: String? = null)

    fun register(uid: String, userId: Long, amount: Long, deadline: Instant): Result {
        count("register")
        val now = clock.instant()
        val e = entries.computeIfAbsent(uid) { Entry(uid, userId, amount, deadline, now) }
        synchronized(e) {
            if (e.state == State.CLOSED) return Result(e.view(), 409, "CLOSED")
            return ok(e)
        }
    }

    fun pay(uid: String): Result = settle(uid, "pay", State.CAPTURED)

    fun authorize(uid: String): Result = settle(uid, "authorize", State.AUTHORIZED)

    /** pay · authorize 공통 — REGISTERED에서만 · deadline 안에서만(N17-6) */
    private fun settle(uid: String, op: String, to: State): Result {
        count(op)
        val e = entries[uid] ?: return Result(null, 404, "UNKNOWN_PAYMENT")
        synchronized(e) {
            expireIfDue(e)
            if (e.state == to) return ok(e) // 멱등 재생
            if (e.state != State.REGISTERED) return Result(e.view(), 409, e.state.name)
            val now = clock.instant()
            if (e.deadline == null || now.isAfter(e.deadline)) return Result(e.view(), 409, "DEADLINE_PASSED")
            e.state = to
            if (to == State.CAPTURED) e.capturedAt = now else e.authorizedAt = now
            return ok(e)
        }
    }

    fun capture(uid: String): Result {
        count("capture")
        val e = entries[uid] ?: return Result(null, 404, "UNKNOWN_PAYMENT")
        synchronized(e) {
            expireIfDue(e)
            return when (e.state) {
                State.CAPTURED -> ok(e)
                State.AUTHORIZED -> { e.state = State.CAPTURED; e.capturedAt = clock.instant(); ok(e) }
                else -> Result(e.view(), 409, e.state.name)
            }
        }
    }

    fun close(uid: String): Result {
        count("close")
        val now = clock.instant()
        val e = entries.computeIfAbsent(uid) { Entry(uid, null, null, null, now).also { it.tombstone = true; it.state = State.CLOSED; it.closedAt = now } }
        synchronized(e) {
            expireIfDue(e)
            when (e.state) {
                State.REGISTERED, State.EXPIRED -> { e.state = State.CLOSED; e.closedAt = now }
                State.AUTHORIZED -> { e.state = State.VOIDED; e.closedAt = now }
                State.CAPTURED -> { e.state = State.REFUNDED; e.closedAt = now; e.refunds += 1 }
                State.VOIDED, State.REFUNDED, State.CLOSED -> Unit
            }
            return ok(e)
        }
    }

    fun get(uid: String): PaymentView? = entries[uid]?.let { e -> synchronized(e) { expireIfDue(e); e.view() } }

    fun list(state: String?, registeredBefore: Instant?): List<PaymentView> =
        entries.values.map { e -> synchronized(e) { expireIfDue(e); e.view() } }
            .filter { state == null || it.state == state }
            .filter { registeredBefore == null || it.registeredAt.isBefore(registeredBefore) }

    fun dump(): LedgerDump = LedgerDump(list(null, null).sortedBy { it.uid }, ops.mapValues { it.value.get() })

    fun reset() {
        entries.clear()
        ops.clear()
    }

    /** 승인 만료(설계-3 authTtl) — 읽을 때 늦게 판정한다 */
    private fun expireIfDue(e: Entry) {
        val at = e.authorizedAt ?: return
        if (e.state == State.AUTHORIZED && !clock.instant().isBefore(at.plus(props.authTtl))) e.state = State.EXPIRED
    }

    private fun ok(e: Entry) = Result(e.view(), 200)

    private fun count(op: String) = ops.computeIfAbsent(op) { AtomicLong() }.incrementAndGet()
}

data class PaymentView(
    val uid: String,
    val userId: Long?,
    val amount: Long?,
    val deadline: Instant?,
    val state: String,
    val registeredAt: Instant,
    val authorizedAt: Instant?,
    val capturedAt: Instant?,
    val closedAt: Instant?,
    val refunds: Int,
    val tombstone: Boolean,
)

data class LedgerDump(val payments: List<PaymentView>, val ops: Map<String, Long>)
