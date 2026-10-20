package com.jun.labs.seatreservation.service.impl.drift

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
) {
    companion object {
        const val WINDOW_CLAMPED = "window_clamped"
        const val RESTART = "restart"
        const val MEMORY_OVERFLOW = "memory_overflow"
        const val AFTER_COMMIT_ERROR = "after_commit_error"
        const val HIDDEN_SESSIONS = "hidden_sessions"
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
        val event = candidateSource().start()
        log.info("증분 대조 기동({}): 방식 {} — 이 기동 앞의 변경은 안전망 몫(보장 밖 구간 {} ~ {})", source, properties.incremental.method, event.fromAt, event.toAt)
        try {
            jdbc.execute(ConnectionCallback { con -> writeGaps(JdbcTemplate(SingleConnectionDataSource(con, true)), source, listOf(event)) })
        } catch (e: Exception) {
            meters.counter(ERRORS_METRIC, "stage", "gap").increment()
            log.warn("증분 기동 restart 기록 실패({}) — {}", source, e.toString())
        }
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
        val id = pass.borrow {
            jdbc.execute(ConnectionCallback { con ->
                val single = JdbcTemplate(SingleConnectionDataSource(con, true))
                val runId = insertReconcileRun(single, run)
                writeGaps(single, source, batch.gaps)
                runId
            })!!
        }
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
        return run.copy(id = id)
    }

    /** 기록 실패는 지표 `…incremental.errors{stage=gap}` + WARN — 틱을 실패시키지 않는다(§4.2.6). */
    private fun writeGaps(single: JdbcTemplate, source: String, gaps: List<GapEvent>) {
        for (g in gaps) {
            meters.counter(GAP_METRIC, "kind", g.kind).increment()
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
                log.warn("증분 보장 밖 기록 실패({} · {}) — {}: {}", source, g.kind, e.toString(), g)
            }
        }
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
 * ⓑⓒ `updated_at` 창(ADR-012 §4.2.3 · §4.2.13 #1). 시계는 모두 DB 시계 — 문장 두 개를 **이 순서로**(각각 자동 커밋 — 빌림 2):
 * ① `now()`·가장 오래된 다른 세션 `xact_start`(o)·숨은 세션 수(h) ② `updated_at >= L_k`.
 * 다음 하한 `L_{k+1} = max(min(t, o) − 1초, t − max-overlap − 1초)` · h > 0이면 `t − max-overlap − 1초`(보수 하한) · 첫 하한 = 기동 시 `now() − start-lookback`.
 * ② 가 실패하면 하한을 옮기지 않는다(다음 틱이 같은 창을 다시 읽는다 — 유실 없음).
 */
@Component
class UpdatedAtCandidates(
    private val jdbc: JdbcTemplate,
    private val properties: ReconcileProperties,
    private val meters: MeterRegistry,
) : CandidateSource {
    @Volatile private var lower: Instant? = null
    @Volatile private var hiddenGapAt: Long? = null
    /** 실패해 다시 볼 쌍(다음 틱 후보에 더한다 — 상한 memory-cap, 넘치면 버리고 WARN: 안전망 몫) */
    private val retry = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long, Long>>()

    override fun requeue(pairs: Collection<Pair<Long, Long>>) {
        var dropped = 0
        pairs.forEach { if (retry.size < properties.incremental.memoryCap) retry += it else dropped++ }
        if (dropped > 0) log.warn("증분 재시도 쌍 {}개를 상한(memory-cap {})으로 버렸다 — 안전망 몫", dropped, properties.incremental.memoryCap)
    }

    override fun supports(method: IncrementalMethod) = method == IncrementalMethod.TRIGGER || method == IncrementalMethod.STMT

    override fun start(): GapEvent {
        val now = jdbc.queryForObject("SELECT now()", Timestamp::class.java)!!.toInstant()
        val first = now.minus(properties.incremental.startLookback)
        lower = first
        // 보장 밖 구간 = 기동 전 마지막 틱 뒤(모름 — NULL) ~ 첫 하한
        return GapEvent(GapEvent.RESTART, null, first, detail = "first lower = now − start-lookback ${properties.incremental.startLookback}")
    }

    override fun take(pass: RepairPass, trackInject: Boolean): CandidateBatch {
        val inc = properties.incremental
        val head = pass.borrow { jdbc.queryForMap(WATERMARK_SQL) }
        val t = (head["t"] as Timestamp).toInstant()
        val o = (head["o"] as Timestamp?)?.toInstant()
        val h = (head["h"] as Number).toLong()
        val from = lower ?: t.minus(inc.startLookback)
        val read = pass.borrow {
            jdbc.query(CANDIDATES_SQL, { rs, _ -> rs.getLong(1) to rs.getLong(2) }, Timestamp.from(from))
        }
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
        lower = next
        return CandidateBatch(pairs, t, gaps)
    }

    companion object {
        private val log = LoggerFactory.getLogger(UpdatedAtCandidates::class.java)
        /** 트랜잭션 시작 시각이 pg_stat_activity에 늦게 보이는 틈의 여유 `[구현 검증]` */
        private val MARGIN: Duration = Duration.ofSeconds(1)
        private val HIDDEN_REPEAT: Duration = Duration.ofMinutes(10)

        /** 문장 1 — 문장 2보다 **먼저** 보낸다(한 문장에 묶으면 스냅숏과 pg_stat_activity 읽기 사이 커밋이 양쪽에서 빠질 수 있다) */
        private val WATERMARK_SQL = """
            SELECT now() AS t,
                   (SELECT min(xact_start) FROM pg_stat_activity WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid() AND xact_start IS NOT NULL) AS o,
                   (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid() AND state IS NULL) AS h
        """.trimIndent()

        private const val CANDIDATES_SQL = "SELECT schedule_id, user_id FROM user_hold_quota WHERE updated_at >= ? ORDER BY schedule_id, user_id"
    }
}
