package com.jun.labs.seatreservation.service.impl.drift

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** 증분 보장 밖 사건 1건 = `counter_inc_gap` 1행(ADR-012 §4.2.6). source는 쓰는 틱(또는 기동)의 source다. */
data class GapEvent(
    val kind: String,
    val fromAt: Instant?,
    val toAt: Instant?,
    val scheduleId: Long? = null,
    val userId: Long? = null,
    val pairs: Int = 0,
    val detail: String? = null,
    /** 이미 지표 `…incremental.gap{kind}`에 센 사건을 되돌려 다시 쓰는 중(rv43 — 지표를 두 번 세지 않는다) */
    val requeued: Boolean = false,
) {
    companion object {
        const val WINDOW_CLAMPED = "window_clamped"
        const val RESTART = "restart"
        const val MEMORY_OVERFLOW = "memory_overflow"
        const val AFTER_COMMIT_ERROR = "after_commit_error"
        const val HIDDEN_SESSIONS = "hidden_sessions"
        /** ⓑⓒ 재시도 쌍(실패해 다시 볼 쌍)이 상한 memory-cap을 넘어 버려졌다 — 그 쌍은 안전망 몫(rv43) */
        const val RETRY_OVERFLOW = "retry_overflow"
    }
}

/**
 * 틱(또는 기동)이 쓰지 못한 보장 밖 기록을 다음 틱으로 넘기는 버퍼(rv43 — 기록 실패가 행을 잃지 않게). 상한 [CAP] — 넘친 건수를 돌려준다(호출자가 WARN).
 * 두 후보 출처가 하나씩 들고 [CandidateSource.take]의 gaps 앞에 내보낸다.
 */
class GapBuffer {
    private val pending = ArrayList<GapEvent>()

    @Synchronized
    fun add(gaps: Collection<GapEvent>): Int {
        var dropped = 0
        gaps.forEach { if (pending.size < CAP) pending += it else dropped++ }
        return dropped
    }

    @Synchronized
    fun drain(): List<GapEvent> = ArrayList(pending).also { pending.clear() }

    companion object {
        const val CAP = 1_000
    }
}

/** 후보 한 번 — [readAt] = 후보 읽기 직전 DB 시각(track-inject의 첫 후보 시각 · ⓐ는 track-inject일 때만 잰다). */
class CandidateBatch(val pairs: List<Pair<Long, Long>>, val readAt: Instant?, val gaps: List<GapEvent>)

/**
 * 증분 틱이 '바뀐 쌍'을 받는 곳(ADR-012 §4.2.1 · §4.2.3). 구현: [UpdatedAtCandidates](ⓑⓒ — core) · 메모리 집합(ⓐ — 업무 앱).
 * 빌림은 [pass]로 센다(틱 기록 borrows · 지표).
 */
interface CandidateSource {
    fun supports(method: IncrementalMethod): Boolean

    /** 틱 스케줄러 시작 때 1회 — 첫 하한 등 상태를 잡고 `restart` 사건을 돌려준다(이 프로세스 앞의 변경은 보장 밖 — §2.3 재합의 행). */
    fun start(): GapEvent

    fun take(pass: RepairPass, trackInject: Boolean): CandidateBatch

    /**
     * 꺼낸 쌍의 탐지·복구가 끝나기 전에 실패했다(청크 실패 · 복구 skipped · 회차 조회 실패) — 다음 틱 후보에 다시 넣는다(§3-1).
     * ⓑⓒ도 하한이 이미 앞으로 갔으므로 창이 다시 읽어 주지 않는다 — 같은 방식으로 메모리에 들고 다음 틱에 더한다(재시작하면 사라짐 — 안전망 몫).
     */
    fun requeue(pairs: Collection<Pair<Long, Long>>)

    /** 틱(또는 기동)이 쓰지 못한 보장 밖 기록 — 다음 [take]의 gaps로 다시 내보낸다(rv43 · [GapBuffer]). 상한으로 버린 건수를 돌려준다. */
    fun requeueGaps(gaps: List<GapEvent>): Int
}

/**
 * 증분 틱(ADR-012 §4.2.3 '증분 틱 한 번'): 후보 쌍 → 열린 회차만 → 회차별 사용자 k명씩 [ChunkDetector] `Users` → 어긋난 쌍을 [PairRepairRunner] →
 * `counter_reconcile_run` 1행(후보 0이어도) + 보장 밖 기록(`counter_inc_gap`, 같은 빌림) → (track-inject) 첫 후보 시각.
 * 공개 진입점 [tick] — 스케줄러가 부르고 테스트도 부른다. 지표는 처음 쓸 때 등록된다(틱이 돌지 않으면 새 이름이 없다 — §4.2.6 등록 시점).
 */
@Component
class IncrementalReconciler(
    private val jdbc: JdbcTemplate,
    private val properties: ReconcileProperties,
    private val detector: ChunkDetector,
    private val runner: PairRepairRunner,
    private val sources: ObjectProvider<CandidateSource>,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private fun candidateSource(): CandidateSource {
        val method = properties.incremental.method
        return sources.orderedStream().filter { it.supports(method) }.findFirst()
            .orElseThrow { IllegalStateException("증분 방식 $method 의 후보 출처가 없다(${IncrementalSchemaCheck.PREFIX}.method)") }
    }

    /** 틱 스케줄러 시작 때 1회 — 기동 INFO 1행 + `restart` 행(실패해도 기동은 계속 — WARN·지표). */
    fun start(source: String) {
        val cs = candidateSource()
        val event = cs.start()
        log.info("증분 대조 기동({}): 방식 {} — 이 기동 앞의 변경은 안전망 몫(보장 밖 구간 {} ~ {})", source, properties.incremental.method, event.fromAt, event.toAt)
        val failed = try {
            jdbc.execute(ConnectionCallback { con -> writeGaps(JdbcTemplate(SingleConnectionDataSource(con, true)), source, listOf(event)) })!!
        } catch (e: Exception) {
            // 커넥션을 빌리지 못했다 — 행을 쓰지 못했고 지표도 아직 세지 않았다
            meters.counter(ERRORS_METRIC, "stage", "gap").increment()
            log.warn("증분 기동 restart 기록 실패({}) — 다음 틱에 다시 쓴다: {}", source, e.toString())
            listOf(event)
        }
        requeueGaps(cs, source, failed)
    }

    fun tick(source: String): ReconcileRun {
        val cfg = properties
        val inc = cfg.incremental
        val t0 = System.nanoTime()
        val pass = DriftPass(meters, BORROWS_METRIC, ERRORS_METRIC, null, "증분 틱")
        val startedAt = clock.instant()
        val cs = candidateSource()

        // 1. 후보
        val readStart = System.nanoTime()
        val batch = try {
            cs.take(pass, inc.trackInject)
        } catch (e: Exception) {
            pass.fail("candidates", skipChunk = false, "후보 읽기 — 이 틱은 후보를 받지 못했다(ⓑⓒ는 하한을 옮기지 않아 다음 틱이 같은 창을 읽는다)", e)
            CandidateBatch(emptyList(), null, emptyList())
        }
        pass.chunkMsTotal += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readStart)
        val pairs = batch.pairs.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        meters.counter(CANDIDATES_METRIC).increment(pairs.size.toDouble())
        pass.pairsScanned = pairs.size.toLong()
        val requeue = LinkedHashSet<Pair<Long, Long>>()

        // 2. 열린 회차만(앱 Clock — 지금 C와 같은 기준)
        val bySchedule = pairs.groupBy({ it.first }, { it.second })
        val open: Set<Long> = if (bySchedule.isEmpty()) emptySet() else try {
            val ids = bySchedule.keys.toTypedArray()
            pass.borrow {
                jdbc.query(
                    PreparedStatementCreator { con ->
                        con.prepareStatement(OPEN_SCHEDULES_SQL).apply {
                            setArray(1, con.createArrayOf("bigint", ids))
                            setTimestamp(2, Timestamp.from(startedAt))
                        }
                    },
                    { rs, _ -> rs.getLong(1) },
                ).toSet()
            }
        } catch (e: Exception) {
            pass.fail("schedules", skipChunk = false, "후보 회차 조회 — 이 틱은 후보를 대조하지 못했다", e)
            requeue += pairs
            null
        } ?: emptySet()
        val failedSchedules = requeue.isNotEmpty()
        val closed = if (failedSchedules) emptyMap() else bySchedule.filterKeys { it !in open }
        val skippedSchedules = closed.size
        val skippedPairs = closed.values.sumOf { it.size }.toLong()

        // 3. 회차별 사용자 k명씩 탐지
        val found = ArrayList<PairObservation>()
        for ((s, users) in bySchedule) {
            if (s !in open) continue
            for (chunk in users.chunked(cfg.chunkSize)) {
                val ct0 = System.nanoTime()
                try {
                    val d = pass.borrow { detector.detect(s, QuotaSelector.Users(chunk.toLongArray())) }
                    pass.chunks++
                    found += d.mismatches
                } catch (e: Exception) {
                    pass.fail("chunk", skipChunk = true, "증분 탐지 청크 — 회차 $s 사용자 ${chunk.size}명(그 쌍은 다음 틱이 다시 본다)", e)
                    chunk.forEach { u -> requeue += s to u }
                } finally {
                    val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ct0)
                    pass.chunkMsTotal += ms
                    pass.chunkMsMax = maxOf(pass.chunkMsMax, ms)
                }
            }
        }

        // 4. 복구(회차, 사용자 순)
        val outcomes = found.sortedWith(compareBy({ it.scheduleId }, { it.userId })).map { o ->
            runner.repairOne(pass, source, o, cfg.lockTimeout.toMillis()).also { out ->
                if (out.action == DriftAction.SKIPPED && out.reason != CounterReconciler.SKIP_CLOSED) requeue += o.scheduleId to o.userId
            }
        }
        if (requeue.isNotEmpty()) cs.requeue(requeue)

        // 5. 기록(+ 보장 밖 기록 — 같은 빌림) · 6. track-inject
        val track = inc.trackInject && pairs.isNotEmpty() && batch.readAt != null
        val finishedAt = clock.instant()
        val run = ReconcileRun(
            id = 0, source = source, startedAt = startedAt, finishedAt = finishedAt,
            schedules = open.size, chunks = pass.chunks, pairsScanned = pass.pairsScanned, orphanChunks = 0,
            found = found.size,
            repaired = outcomes.count { it.action == DriftAction.REPAIRED },
            falsePositives = outcomes.count { it.action == DriftAction.FALSE_POSITIVE },
            skipped = outcomes.count { it.action == DriftAction.SKIPPED && it.reason != CounterReconciler.SKIP_CLOSED },
            skippedClosed = outcomes.count { it.action == DriftAction.SKIPPED && it.reason == CounterReconciler.SKIP_CLOSED },
            skippedSchedules = skippedSchedules, skippedPairs = skippedPairs, skippedChunks = pass.skippedChunks, errors = pass.errors,
            chunkMsTotal = pass.chunkMsTotal, chunkMsMax = pass.chunkMsMax, orphanMsTotal = 0,
            // 이 틱의 빌림 전부 = 아래 기록 1 + (track-inject) 1 포함 — 지표 borrows 증가분·Hikari acquire 증가분과 같다
            borrows = pass.borrows + 1 + (if (track) 1 else 0), outcomes = outcomes,
        )
        // 기록 실패는 틱을 실패시키지 않는다(rv43): 바퀴 행 실패 = errors{stage=record} · 보장 밖 행 실패 = errors{stage=gap} + 다음 틱에 다시 쓴다
        var gapsLeft: List<GapEvent> = emptyList()
        val id: Long? = try {
            pass.borrow {
                jdbc.execute(ConnectionCallback { con ->
                    val single = JdbcTemplate(SingleConnectionDataSource(con, true))
                    // 자동 커밋 — 바퀴 행 INSERT가 실패해도 아래 보장 밖 행은 따로 쓴다
                    val runId = try {
                        insertReconcileRun(single, run)
                    } catch (e: Exception) {
                        recordFailed(source, e)
                        null
                    }
                    gapsLeft = writeGaps(single, source, batch.gaps)
                    runId
                })
            }
        } catch (e: Exception) {
            // 커넥션을 빌리지 못했다 — 바퀴 행도 보장 밖 행도 쓰지 못했다(보장 밖 행은 지표도 아직 세지 않았다)
            recordFailed(source, e)
            if (batch.gaps.isNotEmpty()) meters.counter(ERRORS_METRIC, "stage", "gap").increment()
            gapsLeft = batch.gaps
            null
        }
        requeueGaps(cs, source, gapsLeft)
        if (track) {
            try {
                pass.borrow { markFirstCandidates(pairs, batch.readAt!!, source) }
            } catch (e: Exception) {
                meters.counter(ERRORS_METRIC, "stage", "inject").increment()
                log.warn("증분 틱({}) 첫 후보 시각 기록 실패 — {}", source, e.toString())
            }
        }
        meters.timer(TICK_METRIC).record(System.nanoTime() - t0, TimeUnit.NANOSECONDS)
        if (run.found > 0 || run.errors > 0) {
            log.warn(
                "증분 틱({}): 후보 {} · 회차 {} · 발견 {} · 복구 {} · 오탐 {} · skipped {} · 오류 {} · 마감 제외 회차 {}/쌍 {}",
                source, run.pairsScanned, run.schedules, run.found, run.repaired, run.falsePositives, run.skipped, run.errors, skippedSchedules, skippedPairs,
            )
        }
        return run.copy(id = id ?: 0)
    }

    private fun recordFailed(source: String, e: Exception) {
        meters.counter(ERRORS_METRIC, "stage", "record").increment()
        log.error("증분 틱({}) 기록 실패(counter_reconcile_run) — {}", source, e.toString())
    }

    /** 쓰지 못한 보장 밖 기록을 후보 출처 버퍼로 되돌린다(다음 틱이 다시 쓴다 — rv43). 버퍼 상한으로 버린 건은 WARN. */
    private fun requeueGaps(cs: CandidateSource, source: String, gaps: List<GapEvent>) {
        if (gaps.isEmpty()) return
        val dropped = cs.requeueGaps(gaps)
        if (dropped > 0) log.warn("증분 보장 밖 기록 {}건을 되돌림 버퍼 상한({})으로 버렸다({}) — 행은 남지 않는다", dropped, GapBuffer.CAP, source)
    }

    /**
     * 기록 실패는 지표 `…incremental.errors{stage=gap}` + WARN — 틱을 실패시키지 않는다(§4.2.6). 쓰지 못한 사건을 돌려준다(호출자가 다음 틱으로 되돌린다 — rv43).
     * 지표 `…gap{kind}`는 사건마다 한 번 — 되돌려 다시 쓰는 사건([GapEvent.requeued])은 세지 않는다.
     */
    private fun writeGaps(single: JdbcTemplate, source: String, gaps: List<GapEvent>): List<GapEvent> {
        val failed = ArrayList<GapEvent>()
        for (g in gaps) {
            if (!g.requeued) meters.counter(GAP_METRIC, "kind", g.kind).increment()
            try {
                single.update(
                    """
                    INSERT INTO counter_inc_gap (at, source, kind, from_at, to_at, schedule_id, user_id, pairs, detail)
                    VALUES (now(), ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    source, g.kind, g.fromAt?.let(Timestamp::from), g.toAt?.let(Timestamp::from), g.scheduleId, g.userId, g.pairs, g.detail?.take(200),
                )
            } catch (e: Exception) {
                meters.counter(ERRORS_METRIC, "stage", "gap").increment()
                log.warn("증분 보장 밖 기록 실패({} · {}) — 다음 틱에 다시 쓴다 — {}: {}", source, g.kind, e.toString(), g)
                failed += g.copy(requeued = true)
            }
        }
        return failed
    }

    /** ADR-012 §4.2.7 첫 후보 시각 — 맞든 틀리든 후보에 오른 주입 쌍에 채운다(인자 시각 = 후보 읽기 직전 DB 시각). */
    private fun markFirstCandidates(pairs: List<Pair<Long, Long>>, readAt: Instant, source: String) {
        val ss = pairs.map { it.first }.toTypedArray()
        val us = pairs.map { it.second }.toTypedArray()
        jdbc.update { con ->
            con.prepareStatement(FIRST_CANDIDATE_SQL).apply {
                setTimestamp(1, Timestamp.from(readAt))
                setString(2, source)
                setTimestamp(3, Timestamp.from(readAt))
                setArray(4, con.createArrayOf("bigint", ss))
                setArray(5, con.createArrayOf("bigint", us))
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(IncrementalReconciler::class.java)
        private const val P = "seat.hold.counter.reconcile.incremental"
        const val TICK_METRIC = "$P.tick"
        const val BORROWS_METRIC = "$P.borrows"
        const val CANDIDATES_METRIC = "$P.candidates"
        const val WINDOW_CLAMPED_METRIC = "$P.window_clamped"
        const val ERRORS_METRIC = "$P.errors"
        const val HIDDEN_SESSIONS_METRIC = "$P.hidden_sessions"
        const val GAP_METRIC = "$P.gap"
        const val MEMORY_SIZE_METRIC = "$P.memory.size"
        const val MEMORY_OVERFLOW_METRIC = "$P.memory.overflow"
        const val MEMORY_REQUEUED_METRIC = "$P.memory.requeued"
        /** ADR-013 §4.11 ④ — 찬 페이지 수(카운터) · 이어 읽기 한 번이 이어진 틱 수(끝날 때 분포) */
        const val PAGE_FULL_METRIC = "$P.page_full"
        const val CARRY_TICKS_METRIC = "$P.carry_ticks"

        /** 후보의 열린 회차(앱 Clock) — 파라미터: 회차 배열, now */
        private const val OPEN_SCHEDULES_SQL = "SELECT id FROM product_schedule WHERE id = ANY(?) AND starts_at > ?"

        private const val FIRST_CANDIDATE_SQL =
            "UPDATE counter_app_inject SET first_candidate_at = ?, first_candidate_source = ? WHERE first_candidate_at IS NULL AND at < ? " +
                "AND (schedule_id, user_id) IN (SELECT * FROM unnest(?::bigint[], ?::bigint[]))"

        /** source 꼬리표(§4.2.6): 정수 초면 `Ns`, 아니면 `Nms` */
        fun intervalTag(d: Duration): String = if (d.toMillis() % 1000 == 0L) "${d.toMillis() / 1000}s" else "${d.toMillis()}ms"

        fun methodTag(m: IncrementalMethod): String = when (m) {
            IncrementalMethod.MEMORY -> "mem"
            IncrementalMethod.TRIGGER -> "trg"
            IncrementalMethod.STMT -> "stmt"
            IncrementalMethod.NONE -> "none"
        }

        /** 증분 source(§4.2.6 표) — `inc-{app|ext}-{mem|trg|stmt}-{주기}` */
        fun source(p: IncrementalProperties, external: Boolean): String =
            "inc-${if (external) "ext" else "app"}-${methodTag(p.method)}-${intervalTag(p.interval)}"
    }
}

/**
 * ⓑⓒ `updated_at` 창(ADR-012 §4.2.3 · §4.2.13 #1 · ADR-013 §4.11). 시계는 모두 DB 시계 — 문장 두 개를 **이 순서로**(각각 자동 커밋 — 빌림 2):
 * ① `now()`·가장 오래된 다른 세션 `xact_start`(o)·숨은 세션 수(h) ② `updated_at >= L_k` — 키셋 페이지(행 수 상한 page-size · `(updated_at, schedule_id, user_id)` 순).
 * 틱 k의 다음 하한 후보 `next_k = max(min(t, o) − 1초, t − max-overlap − 1초)` · h > 0이면 `t − max-overlap − 1초`(보수 하한) · 첫 하한 = 기동 시 `now() − start-lookback`.
 * - 페이지가 덜 찼다(다 읽음): `L = max(min(pending, next_k), t₀ − max-overlap − 1초)` — t₀ = 이 이어 읽기를 시작한 틱의 문장 1 시각(이어 읽기가 없으면 t₀ = t · pending 없음 → `L = next_k` — ADR-012 규칙 그대로).
 * - 페이지가 찼다(덜 읽음): L을 옮기지 않고 키셋 위치 p를 들고 다음 틱이 p 뒤부터 잇는다 · `pending = min(pending, next_k)`(ADR-013 §4.11 ② · N12).
 * - ADR-013 §4.11 ①(N11): 기동 때 읽은 m(`pg_attribute.attmissingval` — V9 `ADD COLUMN … DEFAULT now()`가 기존 행에 준 값)이 있고 L ≤ m이면
 *   문장 ②를 두 인덱스 범위(A `L ≤ updated_at < m` → B `updated_at > m`)로 나눠 값 m인 행(V9 앞 기존 행)을 읽지 않는다.
 * ② 가 실패하면 L · p · pending을 옮기지 않는다(다음 틱이 같은 페이지를 다시 읽는다 — 유실 없음). p · pending은 메모리 — 재시작하면 새 첫 하한부터.
 */
@Component
class UpdatedAtCandidates(
    private val jdbc: JdbcTemplate,
    private val properties: ReconcileProperties,
    private val meters: MeterRegistry,
) : CandidateSource {
    @Volatile private var lower: Instant? = null
    @Volatile private var hiddenGapAt: Long? = null
    /** 기동 때 읽은 빠른 기본값 m(없으면 null — 제외 없음). 그 뒤 표가 다시 쓰여 카탈로그 값이 사라져도 행에는 m이 남으므로 기억한 값으로 계속 뺀다(§4.11 ① 읽는 때) */
    @Volatile private var missingValue: Instant? = null
    /** 이어 읽기 상태(§4.11 ② — 틱 스케줄러 한 스레드만 take를 부른다): 키셋 위치 p · 최솟값 pending · t₀ · 이어진 틱 수 · 겹침 상한 넘김 WARN 여부 */
    @Volatile private var carry: Carry? = null
    /** 실패해 다시 볼 쌍(다음 틱 후보에 더한다 — 상한 memory-cap, 넘치면 버리고 WARN + 다음 틱에 `retry_overflow` 1행: 안전망 몫) */
    private val retry = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long, Long>>()
    private val gapBuffer = GapBuffer()

    /** 키셋 위치 = 마지막으로 읽은 행의 정렬 키 */
    private data class Key(val updatedAt: Timestamp, val scheduleId: Long, val userId: Long)

    private class Carry(val p: Key, val pending: Instant, val t0: Instant, val ticks: Int, val warned: Boolean)

    override fun requeueGaps(gaps: List<GapEvent>): Int = gapBuffer.add(gaps)

    override fun requeue(pairs: Collection<Pair<Long, Long>>) {
        val cap = properties.incremental.memoryCap
        var dropped = 0
        var first: Pair<Long, Long>? = null
        pairs.forEach {
            if (retry.size < cap || retry.contains(it)) retry += it else {
                if (first == null) first = it
                dropped++
            }
        }
        val f = first ?: return
        log.warn("증분 재시도 쌍 {}개를 상한(memory-cap {})으로 버렸다 — 첫 쌍 회차 {} 사용자 {} · 안전망 몫", dropped, cap, f.first, f.second)
        // 버린 사실을 남긴다(rv43) — 다음 틱 기록과 같은 빌림으로 counter_inc_gap 1행 + 지표 gap{kind=retry_overflow}
        val now = Instant.now()
        val lost = gapBuffer.add(listOf(GapEvent(GapEvent.RETRY_OVERFLOW, now, now, f.first, f.second, dropped, "memory-cap $cap")))
        if (lost > 0) log.warn("증분 retry_overflow 기록을 되돌림 버퍼 상한({})으로 버렸다", GapBuffer.CAP)
    }

    override fun supports(method: IncrementalMethod) = method == IncrementalMethod.TRIGGER || method == IncrementalMethod.STMT

    override fun start(): GapEvent {
        val now = jdbc.queryForObject("SELECT now()", Timestamp::class.java)!!.toInstant()
        val first = now.minus(properties.incremental.startLookback)
        // §4.11 ① 출처 = 카탈로그의 빠른 기본값. 없으면(표 재작성 뒤 등) 제외 없이 ADR-012 문장 그대로 + WARN + restart 행 detail 'm 없음'
        val (m, why) = try {
            val rows = jdbc.query(MISSING_VALUE_SQL) { rs, _ -> rs.getTimestamp(1)?.toInstant() }
            rows.firstOrNull() to null
        } catch (e: Exception) {
            null to e.toString()
        }
        missingValue = m
        lower = first
        carry = null
        val lookback = "first lower = now − start-lookback ${properties.incremental.startLookback}"
        val detail = if (m != null) "$lookback · m $m" else "$lookback · m 없음"
        if (m == null) {
            log.warn(
                "증분 기동 — user_hold_quota.updated_at의 빠른 기본값 m(pg_attribute.attmissingval)이 없다{} — 첫 창에서 V9 앞 기존 행을 빼지 않는다(첫 하한 {} · 후보는 page-size {} 로 나눠 읽는다) · " +
                    "같은 updated_at 묶음이 크면 틱 비용이 page-size로 묶이지 않는다(인덱스 단일 열 — 키셋 (updated_at, schedule_id, user_id) 비교가 묶음 안을 훑고 정렬한다)",
                why?.let { " (읽기 실패: $it)" } ?: "", first, properties.incremental.pageSize,
            )
        }
        // 보장 밖 구간 = 기동 전 마지막 틱 뒤(모름 — NULL) ~ 첫 하한
        return GapEvent(GapEvent.RESTART, null, first, detail = detail)
    }

    override fun take(pass: RepairPass, trackInject: Boolean): CandidateBatch {
        val inc = properties.incremental
        val head = pass.borrow { jdbc.queryForMap(WATERMARK_SQL) }
        val t = (head["t"] as Timestamp).toInstant()
        val o = (head["o"] as Timestamp?)?.toInstant()
        val h = (head["h"] as Number).toLong()
        val from = lower ?: t.minus(inc.startLookback)
        val c = carry
        val pageSize = inc.pageSize
        // 실패하면 여기서 던진다 — L · p · pending을 옮기지 않는다
        val rows = pass.borrow { readPage(from, missingValue, c?.p, pageSize) }
        val read = rows.map { it.scheduleId to it.userId }
        val again = ArrayList<Pair<Long, Long>>()
        retry.toList().forEach { if (retry.remove(it)) again += it }
        val pairs = if (again.isEmpty()) read else read + again
        val floor = t.minus(inc.maxOverlap).minus(MARGIN)
        val natural = minOf(t, o ?: t).minus(MARGIN)
        val next = if (h > 0) floor else maxOf(natural, floor)
        val gaps = ArrayList<GapEvent>()
        if (o != null && natural.isBefore(floor)) {
            // 겹침 상한에 걸린 틱 — 시작이 [o, next)인 트랜잭션의 변경은 보장 밖(사용자 결정 2026-10-20 D1). 하네스는 tx_started_at으로 가른다
            meters.counter(IncrementalReconciler.WINDOW_CLAMPED_METRIC).increment()
            val ageMs = Duration.between(o, t).toMillis()
            log.warn("증분 창 상한(max-overlap {}) — 가장 오래된 xact_start {}(나이 {}ms) · 하한 {} — 그보다 앞에 시작한 트랜잭션의 변경은 안전망 몫", inc.maxOverlap, o, ageMs, next)
            gaps += GapEvent(GapEvent.WINDOW_CLAMPED, o, next, detail = "oldest xact_start age ${ageMs}ms > max-overlap ${inc.maxOverlap.toMillis()}ms")
        }
        if (h > 0) {
            meters.counter(IncrementalReconciler.HIDDEN_SESSIONS_METRIC).increment()
            val nowNs = System.nanoTime()
            val last = hiddenGapAt
            if (last == null || nowNs - last >= HIDDEN_REPEAT.toNanos()) {
                hiddenGapAt = nowNs
                log.warn("증분 숨은 세션 {}개(다른 역할 — xact_start 안 보임) — 이 틱 하한을 t − max-overlap({})로 내린다(보수 하한)", h, next)
                gaps += GapEvent(GapEvent.HIDDEN_SESSIONS, next, t, pairs = 0, detail = "hidden sessions $h — conservative lower bound")
            }
        }
        val pending = c?.let { minOf(it.pending, next) } ?: next
        val t0 = c?.t0 ?: t
        val ticks = (c?.ticks ?: 0) + 1
        if (rows.size >= pageSize) {
            // 페이지가 찼다(덜 읽음) — 하한을 옮기지 않고 p 뒤부터 잇는다(§4.11 ②)
            meters.counter(IncrementalReconciler.PAGE_FULL_METRIC).increment()
            val overlong = Duration.between(t0, t) > inc.maxOverlap
            if (overlong && c?.warned != true) {
                log.warn("증분 이어 읽기가 max-overlap({})보다 오래 이어진다 — 시작 {} · {}틱째 · page-size {} — 다 읽은 뒤 하한은 t₀ − max-overlap 앞으로 가지 않는다", inc.maxOverlap, t0, ticks, pageSize)
            }
            carry = Carry(rows.last(), pending, t0, ticks, warned = (c?.warned == true) || overlong)
        } else {
            // 다 읽었다 — L = max(min(pending, next_k), t₀ − max-overlap − 1초)(재점검 NF-1)
            val guard = t0.minus(inc.maxOverlap).minus(MARGIN)
            if (pending.isBefore(guard)) {
                // next_k마다 자기 t_k의 겹침 상한을 이미 지키므로(t_k ≥ t₀) 정상 경로에서는 오지 않는다 — 오면 무음으로 자르지 않는다
                meters.counter(IncrementalReconciler.WINDOW_CLAMPED_METRIC).increment()
                log.warn("증분 이어 읽기 끝 하한 {}이 겹침 상한 t₀ − max-overlap({})보다 앞 — 잘랐다(그 앞에 시작한 트랜잭션의 변경은 안전망 몫)", pending, guard)
                gaps += GapEvent(GapEvent.WINDOW_CLAMPED, pending, guard, detail = "carry pending before t0 − max-overlap ${inc.maxOverlap.toMillis()}ms")
            }
            lower = maxOf(pending, guard)
            if (c != null) DistributionSummary.builder(IncrementalReconciler.CARRY_TICKS_METRIC).register(meters).record(ticks.toDouble())
            carry = null
        }
        // 앞 틱이 쓰지 못한 보장 밖 기록이 먼저(rv43)
        return CandidateBatch(pairs, t, gapBuffer.drain() + gaps)
    }

    /**
     * 문장 ② 한 페이지(빌림 1 — 두 범위여도 같은 커넥션). [after] 뒤부터 `(updated_at, schedule_id, user_id)` 순으로 최대 [limit]행.
     * m이 있고 L ≤ m이면 범위 A(`L ≤ updated_at < m`)를 먼저 읽고 모자란 몫만큼 범위 B(`updated_at > m`)를 잇는다 — `updated_at <> m` 걸러내기는 m 묶음을 다 훑어 쓰지 않는다.
     */
    private fun readPage(from: Instant, m: Instant?, after: Key?, limit: Int): List<Key> = jdbc.execute(ConnectionCallback { con ->
        val out = ArrayList<Key>()
        fun range(lowerSql: String, lowerArg: Timestamp, upperSql: String, upperArg: Timestamp?, key: Key?) {
            val sql = buildString {
                append("SELECT updated_at, schedule_id, user_id FROM user_hold_quota WHERE ").append(lowerSql)
                if (upperArg != null) append(" AND ").append(upperSql)
                if (key != null) append(" AND (updated_at, schedule_id, user_id) > (?, ?, ?)")
                append(" ORDER BY updated_at, schedule_id, user_id LIMIT ?")
            }
            con.prepareStatement(sql).use { ps ->
                var i = 0
                ps.setTimestamp(++i, lowerArg)
                if (upperArg != null) ps.setTimestamp(++i, upperArg)
                if (key != null) {
                    ps.setTimestamp(++i, key.updatedAt)
                    ps.setLong(++i, key.scheduleId)
                    ps.setLong(++i, key.userId)
                }
                ps.setInt(++i, limit - out.size)
                ps.executeQuery().use { rs -> while (rs.next()) out += Key(rs.getTimestamp(1), rs.getLong(2), rs.getLong(3)) }
            }
        }
        val fromTs = Timestamp.from(from)
        if (m == null || from.isAfter(m)) {
            range("updated_at >= ?", fromTs, "", null, after)
        } else {
            val mTs = Timestamp.from(m)
            // 키셋 위치가 이미 B(> m)에 있으면 A는 다 읽었다
            if (after == null || after.updatedAt.toInstant().isBefore(m)) range("updated_at >= ?", fromTs, "updated_at < ?", mTs, after)
            if (out.size < limit) range("updated_at > ?", mTs, "", null, after?.takeIf { it.updatedAt.toInstant().isAfter(m) })
        }
        out
    })!!

    companion object {
        private val log = LoggerFactory.getLogger(UpdatedAtCandidates::class.java)
        /** 트랜잭션 시작 시각이 pg_stat_activity에 늦게 보이는 틈의 여유 `[구현 검증]` */
        private val MARGIN: Duration = Duration.ofSeconds(1)
        private val HIDDEN_REPEAT: Duration = Duration.ofMinutes(10)

        /**
         * 문장 1 — 문장 2보다 **먼저** 보낸다(한 문장에 묶으면 스냅숏과 pg_stat_activity 읽기 사이 커밋이 양쪽에서 빠질 수 있다).
         * `[구현 검증]` 12(PostgreSQL 16 실측): 권한 없는 다른 역할 세션은 state·xact_start뿐 아니라 backend_type도 NULL로 보인다 —
         * 숨은 세션 수 h는 `backend_type IS NULL`도 센다(ADR §4.2.3 문장의 `backend_type = 'client backend'`만으로는 h가 늘 0).
         * 접속 역할 자기 세션은 숨지 않는다 — 아직 접속 중이라 state가 NULL인 자기 역할 세션은 h에서 뺀다(rv43 · usesysid가 NULL이면 센다 — 보수).
         */
        private val WATERMARK_SQL = """
            SELECT now() AS t,
                   (SELECT min(xact_start) FROM pg_stat_activity WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid() AND xact_start IS NOT NULL) AS o,
                   (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND (backend_type = 'client backend' OR backend_type IS NULL) AND pid <> pg_backend_pid() AND state IS NULL
                      AND usesysid IS DISTINCT FROM (SELECT oid FROM pg_roles WHERE rolname = current_user)) AS h
        """.trimIndent()

        /** ADR-013 §4.11 ① m — 이 스키마 user_hold_quota.updated_at의 빠른 기본값(`atthasmissing = false`면 행 없음 → m 없음) */
        private val MISSING_VALUE_SQL = """
            SELECT (a.attmissingval::text::timestamptz[])[1]
            FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = 'user_hold_quota' AND n.nspname = current_schema() AND a.attname = 'updated_at' AND NOT a.attisdropped AND a.atthasmissing
        """.trimIndent()
    }
}
