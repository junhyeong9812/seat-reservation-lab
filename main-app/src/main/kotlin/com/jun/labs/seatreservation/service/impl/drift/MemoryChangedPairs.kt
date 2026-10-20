package com.jun.labs.seatreservation.service.impl.drift

import com.jun.labs.seatreservation.service.SeatHoldProperties
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 테스트 이음새(ADR-012 §4.2.13 S4): ⓐ `afterCommit` 안에서 집합에 넣기 직전에 부른다. 빈이 없으면(운영) 아무것도 하지 않는다.
 * 테스트가 일부러 던져 '등록 예외'(보장 밖 — after_commit_error)를 만든다.
 */
fun interface MemoryRegistrationProbe {
    fun beforeRegister(scheduleId: Long, userId: Long)
}

/** 넘침이 생기면 안전망 다음 실행을 당긴다(ADR-012 §4.2.2 ⓐ 상한·넘침 ③) — 안전망 스케줄러가 빈을 둔다. */
fun interface SafetyNetExpediter {
    fun expedite()
}

/**
 * ⓐ 메모리 집합(ADR-012 §4.2.2) — 업무 앱의 모든 쿼터 쓰기 경로(+ 확정)가 [changed]를 부르고, 증분 틱이 [take]로 꺼낸다.
 * - `method ≠ memory`면 [changed]는 바로 돌아온다(동기화 등록도 하지 않는다 — §3-4). 게이지는 method=memory일 때만 등록한다.
 * - **커밋 뒤에만 등록**: 트랜잭션 동기화가 켜져 있으면 그 트랜잭션에 동기화를 하나만 걸고 `afterCommit`에서 넣는다(롤백이면 버린다).
 *   꺼져 있으면(자동 커밋 문장 — 이미 커밋됨) 바로 넣는다. `afterCommit` 안의 예외는 잡아 지표·로그·기록 버퍼로(요청을 실패시키지 않는다).
 * - 상한 `memory-cap`: 크기가 상한 이상이면 넣지 않고 넘침(지표 · WARN 첫 쌍과 10초 요약 · 안전망 앞당김 · 다음 틱에 `memory_overflow` 1행).
 * - 등록 경로에서 DB를 쓰지 않는다 — 보장 밖 기록은 버퍼(상한 1,000건)에 두고 틱이 내보낸다.
 */
@Component
class MemoryChangedPairs(
    properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
    private val probe: ObjectProvider<MemoryRegistrationProbe>,
    private val expediter: ObjectProvider<SafetyNetExpediter>,
    private val meters: MeterRegistry,
) : QuotaChangeListener, CandidateSource {
    private val inc = properties.counterDrift.reconcile.incremental
    private val active = inc.method == IncrementalMethod.MEMORY
    private val set: MutableSet<Pair<Long, Long>> = ConcurrentHashMap.newKeySet()

    // 보장 밖 기록 버퍼(등록 예외) + 넘침 요약 — lock = this
    private val pendingErrors = ArrayList<GapEvent>()
    private var droppedErrors = 0
    private var overflowCount = 0
    private var overflowFirst: Pair<Long, Long>? = null
    private var overflowFrom: Instant? = null
    private var overflowTo: Instant? = null
    private var overflowSinceWarn = 0
    private var lastOverflowWarnNs = 0L
    /** 틱이 쓰지 못한 보장 밖 기록(rv43) — 다음 틱 gaps 맨 앞 */
    private val gapBuffer = GapBuffer()

    init {
        if (active) Gauge.builder(IncrementalReconciler.MEMORY_SIZE_METRIC, set) { it.size.toDouble() }.register(meters)
    }

    override fun supports(method: IncrementalMethod) = method == IncrementalMethod.MEMORY

    override fun changed(scheduleId: Long, userId: Long) {
        if (!active) return
        val key = scheduleId to userId
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            add(key) // 자동 커밋 경로(쿼터 행 준비 INSERT) — 이미 커밋됐다
            return
        }
        @Suppress("UNCHECKED_CAST")
        val pending = TransactionSynchronizationManager.getResource(this) as PendingPairs?
            ?: PendingPairs().also {
                TransactionSynchronizationManager.bindResource(this, it)
                TransactionSynchronizationManager.registerSynchronization(it)
            }
        pending.pairs += key
    }

    /** 트랜잭션 하나의 등록 대기 쌍 — 동기화 하나(§4.2.2 ⓐ '동기화를 하나만'). */
    private inner class PendingPairs : TransactionSynchronization {
        val pairs = LinkedHashSet<Pair<Long, Long>>()

        override fun afterCommit() {
            pairs.forEach { add(it) }
        }

        override fun afterCompletion(status: Int) {
            TransactionSynchronizationManager.unbindResourceIfPossible(this@MemoryChangedPairs)
        }
    }

    private fun add(key: Pair<Long, Long>) {
        try {
            probe.ifAvailable { it.beforeRegister(key.first, key.second) }
        } catch (e: Exception) {
            registrationFailed(key, e)
            return
        }
        if (set.contains(key)) return
        if (set.size >= inc.memoryCap) {
            overflow(key)
            return
        }
        set += key
    }

    private fun registrationFailed(key: Pair<Long, Long>, e: Exception) {
        meters.counter(IncrementalReconciler.ERRORS_METRIC, "stage", "after_commit").increment()
        log.warn("ⓐ 커밋 뒤 등록 실패 — 회차 {} 사용자 {}(보장 밖 — 안전망 몫): {}", key.first, key.second, e.toString())
        val now = Instant.now()
        synchronized(this) {
            if (pendingErrors.size < BUFFER_CAP) {
                pendingErrors += GapEvent(GapEvent.AFTER_COMMIT_ERROR, now, now, key.first, key.second, pairs = 1, detail = e.toString())
            } else {
                droppedErrors++
            }
        }
    }

    private fun overflow(key: Pair<Long, Long>) {
        meters.counter(IncrementalReconciler.MEMORY_OVERFLOW_METRIC).increment()
        val now = Instant.now()
        val nowNs = System.nanoTime()
        var warnFirst = false
        var summary = 0
        synchronized(this) {
            if (overflowCount == 0) {
                overflowFirst = key
                overflowFrom = now
                warnFirst = lastOverflowWarnNs == 0L
            }
            overflowCount++
            overflowTo = now
            overflowSinceWarn++
            if (lastOverflowWarnNs == 0L || nowNs - lastOverflowWarnNs >= WARN_EVERY_NS) {
                summary = overflowSinceWarn
                overflowSinceWarn = 0
                lastOverflowWarnNs = nowNs
            }
        }
        if (warnFirst) log.warn("ⓐ 메모리 집합 넘침(memory-cap {}) — 첫 쌍 회차 {} 사용자 {}: 넘친 쌍은 안전망 몫", inc.memoryCap, key.first, key.second)
        else if (summary > 0) log.warn("ⓐ 메모리 집합 넘침 요약 — 지난 10초 {}쌍(memory-cap {})", summary, inc.memoryCap)
        try {
            expediter.ifAvailable { it.expedite() }
        } catch (e: Exception) {
            log.warn("안전망 앞당김 요청 실패 — {}", e.toString())
        }
    }

    /** 틱 시작 — 키를 하나씩 `remove`로 꺼낸 **뒤에** 탐지가 DB를 읽는다(꺼낸 뒤 커밋된 변경은 다시 등록되어 다음 틱이 본다). */
    override fun take(pass: RepairPass, trackInject: Boolean): CandidateBatch {
        // track-inject: 꺼내기 직전 DB 시각 1회(§4.2.7 — 이 시각보다 앞의 주입만 첫 후보로 채운다)
        val readAt = if (trackInject) pass.borrow { jdbc.queryForObject("SELECT now()", Timestamp::class.java)!!.toInstant() } else null
        val out = ArrayList<Pair<Long, Long>>()
        val it = set.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (set.remove(k)) out += k
        }
        return CandidateBatch(out, readAt, drainGaps())
    }

    private fun drainGaps(): List<GapEvent> = synchronized(this) {
        val gaps = ArrayList<GapEvent>(gapBuffer.drain())
        gaps += pendingErrors
        if (droppedErrors > 0 && gaps.isNotEmpty()) {
            // 버퍼 상한을 넘긴 등록 예외는 개수만 — 다음 행의 pairs에 더한다(§4.2.6)
            val last = gaps.removeAt(gaps.size - 1)
            gaps += last.copy(pairs = last.pairs + droppedErrors)
            droppedErrors = 0
        }
        pendingErrors.clear()
        if (overflowCount > 0) {
            // 넘침 뒤 첫 틱에 1행 — 그 사이 넘친 쌍 수 · 첫 쌍 · 구간(§4.2.13 #4)
            gaps += GapEvent(GapEvent.MEMORY_OVERFLOW, overflowFrom, overflowTo, overflowFirst?.first, overflowFirst?.second, overflowCount, "memory-cap ${inc.memoryCap}")
            overflowCount = 0
            overflowFirst = null
        }
        gaps
    }

    override fun requeue(pairs: Collection<Pair<Long, Long>>) {
        if (pairs.isEmpty()) return
        meters.counter(IncrementalReconciler.MEMORY_REQUEUED_METRIC).increment(pairs.size.toDouble())
        pairs.forEach { k ->
            if (!set.contains(k)) {
                if (set.size >= inc.memoryCap) overflow(k) else set += k
            }
        }
    }

    override fun requeueGaps(gaps: List<GapEvent>): Int = gapBuffer.add(gaps)

    override fun start(): GapEvent {
        // 재시작하면 집합은 사라진다 — 그 사이 변경은 안전망 몫(§2.3 '잃음'). to_at = 이번 기동 DB 시각 · from_at = NULL(이전 프로세스 끝을 모름 — §4.2.13 #13)
        val now = jdbc.queryForObject("SELECT now()", Timestamp::class.java)!!.toInstant()
        return GapEvent(GapEvent.RESTART, null, now, detail = "memory set lost on restart")
    }

    companion object {
        private val log = LoggerFactory.getLogger(MemoryChangedPairs::class.java)
        private const val BUFFER_CAP = 1_000
        private const val WARN_EVERY_NS = 10_000_000_000L
    }
}
