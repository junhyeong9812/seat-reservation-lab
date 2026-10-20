package com.jun.labs.seatreservation.service.impl.drift

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 안전망 — 예산 있는 이어서 훑기 + 최근 2시간 창 우선(ADR-012 §2.1 · §4.2.5 · §4.2.13 #5~#11). 증분이 못 보는 어긋남(§2.3 '못 봄' · 보장 밖)을 잡는다.
 *
 * 실행 한 번([runOnce]): 시작 표본 → 열린 회차 조회 → 이월 복구 → 다시 읽기 → 최근 창 → 전체 커서 → 이번 실행 복구 → 기록
 * (`counter_reconcile_run` 1행 + `counter_safety_run` 1행 — 같은 run_id). 예산 `budget`은 탐지 블록(열린 회차 · 다시 읽기 · 최근 창 · 전체)에,
 * 앞 절반은 다시 읽기 + 최근 창에 쓴다. 예산은 블록(청크) 끝에서만 보고, 갈래마다 실행당 블록 최소 1개(진행이 멈추지 않게 — #10).
 * 복구는 따로 `repair-budget`·`repair-cap`까지, 남은 쌍은 이월 대기열(다음 실행 맨 앞).
 * 커서·표본 고리·진행 위치·대기열은 모두 메모리다(재시작하면 사라진다 — 전체 커서는 처음부터, 최근 창은 기동 때 최대 id부터).
 * id 범위는 모두 (lo, hi] — 커서 = 처리를 마친 마지막 id. 커서는 그 id 청크의 (가) 행 없음 · (나) 탐지가 다 끝난 뒤에만 옮기고,
 * 찾은 쌍은 같은 실행의 복구 단계에서 고치거나 대기열에 넣는다(rv41 M6 — 청크를 지나도 쌍을 잃지 않는다).
 * [runOnce]·[sampleOnce]는 같은 잠금으로 직렬화된다(스케줄러 스레드 하나 + 테스트 호출).
 */
@Component
class SafetyNetReconciler(
    private val jdbc: JdbcTemplate,
    private val properties: ReconcileProperties,
    private val detector: ChunkDetector,
    private val runner: PairRepairRunner,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    /** 기록 source — 업무 앱 `safety-app` · 외부 앱 `safety-ext`(외부 앱 설정이 바꾼다) */
    @Volatile var source: String = SAFETY_APP

    private val cfg get() = properties.safetyNet

    private class Sample(val t: Instant, val max: LongArray, val nodes: LongArray)

    /** 진행 중인 id 청크 하나((lo, hi] — 커서 전진 규칙의 '청크 안 진행 위치') */
    private class ChunkProgress(val kind: Int, val lo: Long, val hi: Long) {
        var orphanDone = false
        var groups: List<Pair<Long, LongArray>>? = null
        var next = 0
    }

    private enum class Branch { REREAD, RECENT, FULL }

    // ---- 메모리 상태(잠금 = this) -------------------------------------------------------------
    private val samples = ArrayDeque<Sample>()
    private var lastNodes: LongArray? = null
    private var sampleNsSinceRun = 0L
    private var sampleCountSinceRun = 0
    private var pendingReset = false
    private var initialized = false
    private var restartPending = true
    private var lastHeads = LongArray(2)

    private val recentCursor = LongArray(2)
    private val recentProgress = arrayOfNulls<ChunkProgress>(2)
    private val recentFailLo = LongArray(2) { Long.MIN_VALUE }
    private val recentFailCount = IntArray(2)
    private val rereadPos = arrayOfNulls<Long>(2)
    private val rereadProgress = arrayOfNulls<ChunkProgress>(2)

    private var fullSchedule: Long? = null
    private var fullAfter = Long.MIN_VALUE
    private var quotaDone = false
    private val fullOrphan = LongArray(2)
    private val lapEnd = LongArray(2)
    private var orphanDone = false
    private var fullFailKey: String? = null
    private var fullFailCount = 0
    private var lapNo = 1
    private var lapStartT: Instant? = null
    private var overdueWarned = false

    private val carry = LinkedHashMap<Pair<Long, Long>, PairObservation>()

    // 게이지 값(처음 실행·표본 때 등록 — 손잡이가 꺼져 있으면 새 이름이 없다, §4.2.6 등록 시점)
    private var metersReady = false
    private val lagIds = AtomicReference(0.0)
    private val cursorAge = AtomicReference(Double.NaN)
    private val lapSecondsGauge = AtomicReference(Double.NaN)
    private val carrySize = AtomicReference(0.0)

    private fun ensureMeters() {
        if (metersReady) return
        metersReady = true
        Gauge.builder(LAG_IDS_METRIC, lagIds) { it.get() }.register(meters)
        Gauge.builder(CURSOR_AGE_METRIC, cursorAge) { it.get() }.register(meters)
        Gauge.builder(LAP_SECONDS_METRIC, lapSecondsGauge) { it.get() }.register(meters)
        Gauge.builder(CARRY_METRIC, carrySize) { it.get() }.register(meters)
    }

    private fun newPass() = DriftPass(meters, BORROWS_METRIC, ERRORS_METRIC, null, "안전망")

    // ---- 표본 ------------------------------------------------------------------------------

    /** id–시각 표본 1회(§4.2.5 — 스케줄러가 `sample-interval`마다 · 테스트가 직접). 실패해도 던지지 않는다(지표·WARN). */
    @Synchronized
    fun sampleOnce() {
        ensureMeters()
        takeSample(newPass())
    }

    private fun takeSample(pass: DriftPass): Sample? {
        val t0 = System.nanoTime()
        return try {
            val row = pass.borrow { jdbc.queryForMap(SAMPLE_SQL) }
            val s = Sample(
                (row["t"] as Timestamp).toInstant(),
                longArrayOf((row["h_max"] as Number?)?.toLong() ?: 0L, (row["r_max"] as Number?)?.toLong() ?: 0L),
                longArrayOf((row["h_node"] as Number?)?.toLong() ?: 0L, (row["r_node"] as Number?)?.toLong() ?: 0L),
            )
            val prev = lastNodes
            if (prev != null && !prev.contentEquals(s.nodes)) resetState("홀드·예약 파일 번호가 바뀌었다(${prev.toList()} → ${s.nodes.toList()} — TRUNCATE·VACUUM FULL 등)")
            lastNodes = s.nodes
            samples.addLast(s)
            prune(s.t)
            s
        } catch (e: Exception) {
            pass.fail("sample", skipChunk = false, "id–시각 표본 실패", e)
            null
        } finally {
            val ns = System.nanoTime() - t0
            sampleNsSinceRun += ns
            sampleCountSinceRun++
            DistributionSummary.builder(SAMPLE_DB_MS_METRIC).register(meters).record(ns / 1e6)
        }
    }

    /** 고리 = 최근 창 + 다시 읽기 + 10분치. 경계보다 오래된 표본은 하나만 남긴다(id_at(경계)용). */
    private fun prune(newest: Instant) {
        val keep = newest.minus(cfg.recentWindow).minus(cfg.reread).minus(Duration.ofMinutes(10))
        while (samples.size >= 2 && !samples.elementAt(1).t.isAfter(keep)) samples.removeFirst()
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    /** 시드 초기화 감지 — 커서 · 표본 고리 · 다시 읽기 진행 · 청크 진행 위치 · 이월 대기열을 함께 비우고 커서를 0으로(rv41 Opus L10). */
    private fun resetState(why: String) {
        log.warn("안전망 초기화 — {}: 커서·표본 고리·진행 위치·이월 대기열 {}쌍을 비우고 커서를 0으로", why, carry.size)
        samples.clear()
        recentCursor.fill(0)
        recentProgress.fill(null)
        recentFailLo.fill(Long.MIN_VALUE)
        recentFailCount.fill(0)
        rereadPos.fill(null)
        rereadProgress.fill(null)
        carry.clear()
        fullSchedule = null
        fullAfter = Long.MIN_VALUE
        quotaDone = false
        orphanDone = false
        fullFailKey = null
        fullFailCount = 0
        lapStartT = null
        overdueWarned = false
        initialized = true
        pendingReset = true
    }

    /** id_at(T) = 시각 ≤ T인 가장 늦은 표본의 최대 id. 없으면 null. */
    private fun idAt(at: Instant, kind: Int): Long? = samples.lastOrNull { !it.t.isAfter(at) }?.max?.get(kind)

    // ---- 기동 -------------------------------------------------------------------------------

    /** 스케줄러 시작 때 1회 — 최근 창 시작점 = 기동 시 최대 id(그 앞의 id는 전체 커서 몫). */
    @Synchronized
    fun start() {
        ensureMeters()
        val s = takeSample(newPass())
        if (s != null && !initialized) {
            initialized = true
            s.max.copyInto(recentCursor)
            s.max.copyInto(lastHeads)
            log.warn("안전망 기동 — 최근 창 시작점 = 기동 시 최대 id(홀드 {} · 예약 {}) · 그 앞의 id는 전체 커서 몫", s.max[HOLD], s.max[RES])
        }
    }

    // ---- 실행 -------------------------------------------------------------------------------

    private inner class Run(val pass: DriftPass, val now: Timestamp) {
        val ns = LongArray(Branch.entries.size)
        var openNs = 0L
        var repairNs = 0L
        var repairMaxNs = 0L
        var recordNs = 0L
        var maxStepNs = 0L
        val chunks = IntArray(Branch.entries.size)
        var detectChunks = 0
        var orphanChunks = 0
        var detectNs = 0L
        var orphanNs = 0L
        var detectMaxNs = 0L
        var pairsScanned = 0L
        var repairPairs = 0
        var carryDropped = 0
        var skipFrom: Long? = null
        var skipTo: Long? = null
        var skipIds = 0L
        var rereadIds = 0L
        var rereadLeft = 0L
        var sampleShort = false
        val found = LinkedHashMap<Pair<Long, Long>, PairObservation>()
        val outcomes = ArrayList<DriftOutcome>()
        var open: List<Long> = emptyList()

        fun frontNs() = ns[Branch.REREAD.ordinal] + ns[Branch.RECENT.ordinal]
        fun detectionNs() = openNs + ns.sum()

        /** 탐지 블록 하나(빌림 1) — 갈래 시간 · 블록 최대 · 갈래별 청크 수 · 바퀴 기록 묶음(chunks/orphan_chunks) */
        fun <T> step(branch: Branch, orphan: Boolean, block: () -> T): T = stepRaw(branch, orphan) { pass.borrow(block) }

        /** 블록 하나 — 빌림은 [block]이 직접 센다(쌍 조회 + 첫 탐지 청크처럼 빌림 2인 블록) */
        fun <T> stepRaw(branch: Branch, orphan: Boolean, block: () -> T): T {
            val t0 = System.nanoTime()
            try {
                return block()
            } finally {
                val d = System.nanoTime() - t0
                ns[branch.ordinal] += d
                maxStepNs = maxOf(maxStepNs, d)
                chunks[branch.ordinal]++
                if (orphan) {
                    orphanChunks++
                    orphanNs += d
                } else {
                    detectChunks++
                    detectNs += d
                    detectMaxNs = maxOf(detectMaxNs, d)
                }
            }
        }

        fun skip(lo: Long, hi: Long) {
            if (hi <= lo) return
            if (skipFrom == null) {
                skipFrom = lo
                skipTo = hi
            }
            skipIds += hi - lo
            meters.counter(SKIPPED_IDS_METRIC).increment((hi - lo).toDouble())
        }
    }

    /** 실행 한 번(§4.2.5 순서). 공개 진입점 — 스케줄러(주기 · 앞당김)가 부르고 테스트도 부른다. */
    @Synchronized
    fun runOnce(expedited: Boolean = false) {
        ensureMeters()
        val wall0 = System.nanoTime()
        val startedAt = clock.instant()
        val pass = newPass()
        val run = Run(pass, Timestamp.from(startedAt))
        if (expedited) meters.counter(EXPEDITED_METRIC).increment()

        // 시작 표본 — 실패하면 직전 표본으로(없으면 시각 없음 — 다시 읽기·최근 창을 건너뛴다)
        val start = takeSample(pass) ?: samples.lastOrNull()
        val t: Instant? = start?.t
        val heads = start?.max?.copyOf() ?: lastHeads.copyOf()
        heads.copyInto(lastHeads)
        val reset = pendingReset.also { pendingReset = false }
        if (!initialized) {
            initialized = true
            heads.copyInto(recentCursor)
            log.warn("안전망 첫 실행 — 최근 창 시작점 = 지금 최대 id(홀드 {} · 예약 {}) · 그 앞의 id는 전체 커서 몫", heads[HOLD], heads[RES])
        }
        val restart = restartPending.also { restartPending = false }
        val carryIn = carry.size

        // 1. 열린 회차(마감 회차의 쿼터 행은 세지 않는다)
        val openOk = try {
            val o0 = System.nanoTime()
            try {
                run.open = pass.borrow { jdbc.queryForList(OPEN_SCHEDULES_SQL, Long::class.java, run.now) }
            } finally {
                run.openNs += System.nanoTime() - o0
            }
            true
        } catch (e: Exception) {
            pass.fail("schedules", skipChunk = false, "열린 회차 조회 — 이 실행은 탐지하지 못했다", e)
            false
        }

        // 2. 이월 복구(들어온 순서)
        if (carry.isNotEmpty()) {
            val queued = carry.values.toList()
            carry.clear()
            queued.forEach { o -> if (!tryRepair(run, o)) enqueue(run, o) }
        }

        if (openOk) {
            if (t != null) {
                val bounds = LongArray(2) { k -> recentBound(run, t, k) }
                reread(run, t, bounds)
                recent(run, bounds, heads)
            }
            full(run, heads, t)
        }

        // 5. 이번 실행 복구(회차, 사용자) — 상한을 넘으면 이월 대기열
        run.found.values.sortedWith(compareBy({ it.scheduleId }, { it.userId })).forEach { o ->
            if (carry.containsKey(o.scheduleId to o.userId)) return@forEach
            if (!tryRepair(run, o)) enqueue(run, o)
        }

        // 6. 기록
        val lapCompleted = quotaDone && orphanDone
        val lapNow = t ?: Instant.now()
        val lapSec = lapStartT?.let { Duration.between(it, lapNow).toMillis() / 1000.0 } ?: 0.0
        val lapNoRow = lapNo
        if (lapCompleted) {
            lapSecondsGauge.set(lapSec)
            log.info("안전망 바퀴 {} 끝 — {}초", lapNo, lapSec)
            lapNo++
            fullSchedule = null
            fullAfter = Long.MIN_VALUE
            quotaDone = false
            orphanDone = false
            lapStartT = null
            overdueWarned = false
        } else if (lapSec > cfg.lapWarn.toMillis() / 1000.0 && !overdueWarned) {
            overdueWarned = true
            meters.counter(LAP_OVERDUE_METRIC).increment()
            log.warn("안전망 바퀴 {} 나이 {}초 > lap-warn {} — 전체 한 바퀴가 늦어지고 있다(오래된 쌍의 탐지 지연)", lapNo, lapSec, cfg.lapWarn)
        }
        val lag = (heads[HOLD] - recentCursor[HOLD]).coerceAtLeast(0) + (heads[RES] - recentCursor[RES]).coerceAtLeast(0)
        lagIds.set(lag.toDouble())
        val age = cursorAgeSeconds(t, heads)
        cursorAge.set(age ?: Double.NaN)
        carrySize.set(carry.size.toDouble())

        val finishedAt = clock.instant()
        val rr = ReconcileRun(
            id = 0, source = source, startedAt = startedAt, finishedAt = finishedAt,
            schedules = run.open.size, chunks = run.detectChunks, pairsScanned = run.pairsScanned, orphanChunks = run.orphanChunks,
            found = run.found.size,
            repaired = run.outcomes.count { it.action == DriftAction.REPAIRED },
            falsePositives = run.outcomes.count { it.action == DriftAction.FALSE_POSITIVE },
            skipped = run.outcomes.count { it.action == DriftAction.SKIPPED && it.reason != CounterReconciler.SKIP_CLOSED },
            skippedClosed = run.outcomes.count { it.action == DriftAction.SKIPPED && it.reason == CounterReconciler.SKIP_CLOSED },
            skippedSchedules = 0, skippedPairs = 0, skippedChunks = pass.skippedChunks, errors = pass.errors,
            chunkMsTotal = ms(run.detectNs), chunkMsMax = ms(run.detectMaxNs), orphanMsTotal = ms(run.orphanNs),
            // 이 실행의 빌림 전부 = 기록 2(바퀴 행 · 안전망 행) 포함
            borrows = pass.borrows + 2, outcomes = run.outcomes,
        )
        val r0 = System.nanoTime()
        val runId = try {
            pass.borrow { insertReconcileRun(jdbc, rr) }
        } catch (e: Exception) {
            meters.counter(ERRORS_METRIC, "stage", "record").increment()
            log.error("안전망 실행 기록 실패(counter_reconcile_run) — {}", e.toString())
            null
        } finally {
            run.recordNs += System.nanoTime() - r0
        }
        val wallMs = ms(System.nanoTime() - wall0)
        val parts = longArrayOf(ms(run.openNs), ms(run.ns[Branch.REREAD.ordinal]), ms(run.ns[Branch.RECENT.ordinal]), ms(run.ns[Branch.FULL.ordinal]), ms(run.repairNs), ms(run.recordNs))
        val dbMsTotal = parts.sum()
        if (runId != null) {
            val s0 = System.nanoTime()
            try {
                pass.borrow {
                    insertSafetyRun(
                        runId, t, dbMsTotal, parts, run, wallMs, carryIn, reset, heads, age, lapNoRow, lapCompleted,
                        if (lapCompleted) lapSec else null, lapSec, expedited, restart,
                    )
                }
            } catch (e: Exception) {
                meters.counter(ERRORS_METRIC, "stage", "record").increment()
                log.error("안전망 실행 기록 실패(counter_safety_run) — {}", e.toString())
            } finally {
                meters.timer(SELF_RECORD_METRIC).record(System.nanoTime() - s0, TimeUnit.NANOSECONDS)
            }
        }
        sampleNsSinceRun = 0
        sampleCountSinceRun = 0
        meters.timer(RUN_METRIC).record(System.nanoTime() - wall0, TimeUnit.NANOSECONDS)
        DistributionSummary.builder(DB_MS_METRIC).register(meters).record(dbMsTotal.toDouble())
        if (wallMs > cfg.wallCap.toMillis()) log.warn("안전망 실행 벽시계 {}ms > wall-cap {} — R7 반증 기록(다시 읽기 폭 근거가 깨질 수 있다)", wallMs, cfg.wallCap)
        if (run.found.isNotEmpty() || pass.errors > 0 || run.carryDropped > 0) {
            log.warn(
                "안전망 실행({}): 발견 {} · 복구 {} · 대기열 {}→{} (버림 {}) · 오류 {} · DB {}ms · 건너뜀 id {}",
                source, run.found.size, run.repairPairs, carryIn, carry.size, run.carryDropped, pass.errors, dbMsTotal, run.skipIds,
            )
        }
    }

    /** 복구 상한(쌍 수 · DB 시간) 안이면 고치고 true. skipped(마감 제외 아님)는 대기열로(§4.2.13 #11). */
    private fun tryRepair(run: Run, o: PairObservation): Boolean {
        if (run.repairPairs >= cfg.repairCap || run.repairNs >= cfg.repairBudget.toNanos()) return false
        val t0 = System.nanoTime()
        val out = try {
            runner.repairOne(run.pass, source, o, properties.lockTimeout.toMillis())
        } finally {
            val d = System.nanoTime() - t0
            run.repairNs += d
            run.repairMaxNs = maxOf(run.repairMaxNs, d)
            run.repairPairs++
        }
        run.outcomes += out
        if (out.action == DriftAction.SKIPPED && out.reason != CounterReconciler.SKIP_CLOSED) enqueue(run, o)
        return true
    }

    private fun enqueue(run: Run, o: PairObservation) {
        val key = o.scheduleId to o.userId
        if (carry.containsKey(key)) return
        if (carry.size >= cfg.carryCap) {
            run.carryDropped++
            meters.counter(CARRY_DROPPED_METRIC).increment()
            return
        }
        carry[key] = o
    }

    /** 최근 창 하한 B = id_at(T − recent-window). 맞는 표본이 없으면 0(건너뛰지 않는다 — 보수) + sample_short. 커서가 B보다 작으면 (c, B] 건너뜀. */
    private fun recentBound(run: Run, t: Instant, kind: Int): Long {
        val b = idAt(t.minus(cfg.recentWindow), kind) ?: run.let { it.sampleShort = true; 0L }
        if (recentCursor[kind] < b) {
            val lo = recentCursor[kind]
            log.warn("안전망 최근 창 경계(recent-window {}) — {} 커서가 밀려 ({}, {}] {}개 id를 건너뛴다(전체 커서 몫)", cfg.recentWindow, KIND_NAMES[kind], lo, b, b - lo)
            run.skip(lo, b)
            recentCursor[kind] = b
            recentProgress[kind] = null
            recentFailCount[kind] = 0
        }
        return b
    }

    /** 다시 읽기 구역 (id_at(T − reread), c] — 최근 창 몫(앞 절반)에서 쓴다. 맞는 표본이 없으면 가장 오래된 표본(더 넓게 — #7), 표본이 없으면 건너뜀. */
    private fun reread(run: Run, t: Instant, bounds: LongArray) {
        val hi = LongArray(2) { recentCursor[it] }
        val lo = LongArray(2)
        val pos = LongArray(2)
        for (k in 0..1) {
            val at = idAt(t.minus(cfg.reread), k) ?: samples.firstOrNull()?.max?.get(k)?.also { run.sampleShort = true }
            if (at == null) {
                run.sampleShort = true
                lo[k] = hi[k]
            } else {
                lo[k] = maxOf(at, bounds[k]) // 2시간 경계 밖은 버린다(전체 커서 몫)
            }
            run.rereadIds += (hi[k] - lo[k]).coerceAtLeast(0)
            val left = rereadPos[k]
            pos[k] = if (left != null && left > lo[k] && left <= hi[k]) left else lo[k]
            if (rereadProgress[k]?.lo != pos[k]) rereadProgress[k] = null
        }
        var steps = 0
        var kind = HOLD
        while (pos[HOLD] < hi[HOLD] || pos[RES] < hi[RES]) {
            if (steps >= 1 && run.frontNs() >= cfg.budget.toNanos() / 2) break
            if (pos[kind] >= hi[kind]) kind = 1 - kind
            val prog = rereadProgress[kind] ?: ChunkProgress(kind, pos[kind], minOf(pos[kind] + cfg.recentIdChunk, hi[kind])).also { rereadProgress[kind] = it }
            val done = try {
                stepChunk(run, prog, Branch.REREAD)
            } catch (e: Exception) {
                run.pass.fail("reread", skipChunk = true, "다시 읽기 청크 ${KIND_NAMES[kind]} (${prog.lo}, ${prog.hi}] — 다음 실행이 같은 곳에서 다시 한다", e)
                break
            }
            steps++
            if (done) {
                pos[kind] = prog.hi
                rereadProgress[kind] = null
                kind = 1 - kind
            }
        }
        for (k in 0..1) {
            run.rereadLeft += (hi[k] - pos[k]).coerceAtLeast(0)
            rereadPos[k] = if (pos[k] < hi[k]) pos[k] else null
        }
    }

    /** 최근 창 — 커서부터 `recent-id-chunk` 폭씩(홀드 · 예약 번갈아), head(실행 시작 표본 max)에서 자른다(#8). */
    private fun recent(run: Run, bounds: LongArray, heads: LongArray) {
        var steps = 0
        var kind = HOLD
        while (recentCursor[HOLD] < heads[HOLD] || recentCursor[RES] < heads[RES]) {
            if (steps >= 1 && run.frontNs() >= cfg.budget.toNanos() / 2) break
            if (recentCursor[kind] >= heads[kind]) kind = 1 - kind
            val c = recentCursor[kind]
            val prog = recentProgress[kind]?.takeIf { it.lo == c } ?: ChunkProgress(kind, c, minOf(c + cfg.recentIdChunk, heads[kind])).also { recentProgress[kind] = it }
            val done = try {
                stepChunk(run, prog, Branch.RECENT)
            } catch (e: Exception) {
                if (recentFailLo[kind] == prog.lo) recentFailCount[kind]++ else {
                    recentFailLo[kind] = prog.lo
                    recentFailCount[kind] = 1
                }
                run.pass.fail("recent", skipChunk = true, "최근 창 청크 ${KIND_NAMES[kind]} (${prog.lo}, ${prog.hi}] ${recentFailCount[kind]}회째 실패", e)
                if (recentFailCount[kind] >= FAIL_SKIP) {
                    // 같은 청크 3회 연속 실패 — 건너뛴 범위로 기록·WARN하고 전진(전체 커서 몫 — 무음 아님 · #9)
                    log.warn("안전망 최근 창 청크 {} ({}, {}] {}회 연속 실패 — 건너뛴다(전체 커서 몫)", KIND_NAMES[kind], prog.lo, prog.hi, FAIL_SKIP)
                    run.skip(prog.lo, prog.hi)
                    recentCursor[kind] = prog.hi
                    recentProgress[kind] = null
                    recentFailCount[kind] = 0
                }
                break // 그 실행의 최근 창 갈래는 거기서 멈춘다
            }
            steps++
            if (done) {
                recentCursor[kind] = prog.hi
                recentProgress[kind] = null
                recentFailCount[kind] = 0
                kind = 1 - kind
            }
        }
    }

    /**
     * id 청크의 다음 블록 하나: (가) 행 없음 → (나) 회차별 사용자 k명씩 `Users` 탐지(첫 탐지 블록이 쌍 조회를 함께 한다 — 예산은 (가)·(나) 탐지 청크 끝에서만 본다). 끝났으면 true.
     * 실패하면 예외 — 진행 위치는 그 블록 앞 그대로(다음 실행이 그 블록부터).
     */
    private fun stepChunk(run: Run, p: ChunkProgress, branch: Branch): Boolean {
        val hold = p.kind == HOLD
        if (!p.orphanDone) {
            val rows = run.step(branch, orphan = true) { OrphanQueries.query(jdbc, hold, p.lo + 1, p.hi + 1, run.now) }
            rows.forEach { (k, n) -> run.found.putIfAbsent(k, PairObservation(k.first, k.second, null, n)) }
            p.orphanDone = true
            return false
        }
        val first = p.groups == null
        run.stepRaw(branch, orphan = false) {
            if (first) {
                val openIds = run.open.toTypedArray()
                val pairs = if (openIds.isEmpty()) emptyList() else run.pass.borrow {
                    jdbc.query(
                        PreparedStatementCreator { con ->
                            con.prepareStatement(if (hold) HOLD_PAIRS_SQL else RES_PAIRS_SQL).apply {
                                setLong(1, p.lo)
                                setLong(2, p.hi)
                                setArray(3, con.createArrayOf("bigint", openIds))
                            }
                        },
                        { rs, _ -> rs.getLong(1) to rs.getLong(2) },
                    )
                }
                p.groups = pairs.groupBy({ it.first }, { it.second }).toSortedMap().flatMap { (s, us) ->
                    us.sorted().chunked(properties.chunkSize).map { s to it.toLongArray() }
                }
            }
            val groups = p.groups!!
            if (p.next < groups.size) {
                val (s, users) = groups[p.next]
                val d = run.pass.borrow { detector.detect(s, QuotaSelector.Users(users)) }
                run.pairsScanned += d.scanned
                d.mismatches.forEach { run.found[it.scheduleId to it.userId] = it }
                p.next++
            }
        }
        return p.next >= p.groups!!.size
    }

    /** 전체 커서(남은 예산 — 블록 최소 1개): ① 쿼터 행 keyset(열린 회차 id 순) ② 행 없음 id 범위(홀드 → 예약)를 블록 하나씩 번갈아. */
    private fun full(run: Run, heads: LongArray, t: Instant?) {
        if (lapStartT == null) {
            lapStartT = t ?: Instant.now()
            heads.copyInto(lapEnd)
            fullOrphan.fill(0)
            quotaDone = false
            orphanDone = heads[HOLD] <= 0 && heads[RES] <= 0
        }
        var steps = 0
        var turnQuota = true
        while (!(quotaDone && orphanDone)) {
            if (steps >= 1 && run.detectionNs() >= cfg.budget.toNanos()) break
            val quota = if (quotaDone) false else if (orphanDone) true else turnQuota
            turnQuota = !quota
            try {
                if (quota) fullQuotaStep(run) else fullOrphanStep(run)
                fullFailKey = null
                fullFailCount = 0
            } catch (e: Exception) {
                val key = if (quota) "q:$fullSchedule:$fullAfter" else "o:${fullOrphan.toList()}"
                if (fullFailKey == key) fullFailCount++ else {
                    fullFailKey = key
                    fullFailCount = 1
                }
                run.pass.fail("full", skipChunk = true, "전체 커서 ${if (quota) "①(회차 $fullSchedule · user_id > $fullAfter)" else "②(id ${fullOrphan.toList()})"} ${fullFailCount}회째 실패", e)
                if (fullFailCount >= FAIL_SKIP) {
                    log.warn("안전망 전체 커서 블록 {}회 연속 실패 — 건너뛴다({}) · 다음 바퀴가 다시 본다", FAIL_SKIP, key)
                    if (quota) nextSchedule(run, fullSchedule) else advanceOrphan(minOf(fullOrphanPos() + properties.orphanChunkSize, lapEnd[fullOrphanKind()]))
                    fullFailKey = null
                    fullFailCount = 0
                }
                break
            }
            steps++
        }
    }

    private fun fullQuotaStep(run: Run) {
        val cur = fullSchedule
        val s = when {
            cur == null -> run.open.firstOrNull()
            cur in run.open -> cur
            else -> run.open.firstOrNull { it > cur }.also { fullAfter = Long.MIN_VALUE }
        }
        if (s == null) {
            quotaDone = true
            return
        }
        fullSchedule = s
        val k = properties.chunkSize
        val d = run.step(Branch.FULL, orphan = false) { detector.detect(s, QuotaSelector.Keyset(fullAfter, k)) }
        run.pairsScanned += d.scanned
        d.mismatches.forEach { run.found[it.scheduleId to it.userId] = it }
        if (d.scanned < k) nextSchedule(run, s) else fullAfter = d.lastUser
    }

    private fun nextSchedule(run: Run, s: Long?) {
        val next = if (s == null) run.open.firstOrNull() else run.open.firstOrNull { it > s }
        if (next == null) quotaDone = true else fullSchedule = next
        fullAfter = Long.MIN_VALUE
    }

    private fun fullOrphanKind() = if (fullOrphan[HOLD] < lapEnd[HOLD]) HOLD else RES
    private fun fullOrphanPos() = fullOrphan[fullOrphanKind()]

    private fun fullOrphanStep(run: Run) {
        val kind = fullOrphanKind()
        val lo = fullOrphan[kind]
        val hi = minOf(lo + properties.orphanChunkSize, lapEnd[kind])
        if (hi > lo) {
            val rows = run.step(Branch.FULL, orphan = true) { OrphanQueries.query(jdbc, kind == HOLD, lo + 1, hi + 1, run.now) }
            rows.forEach { (key, n) -> run.found.putIfAbsent(key, PairObservation(key.first, key.second, null, n)) }
        }
        advanceOrphan(hi)
    }

    private fun advanceOrphan(hi: Long) {
        val kind = fullOrphanKind()
        fullOrphan[kind] = maxOf(fullOrphan[kind], hi)
        if (fullOrphan[HOLD] >= lapEnd[HOLD] && fullOrphan[RES] >= lapEnd[RES]) orphanDone = true
    }

    /** 홀드 커서 id의 나이 — 커서 다음 id가 처음 보인 표본 시각 기준(따라잡았으면 0, 표본이 없으면 null). */
    private fun cursorAgeSeconds(t: Instant?, heads: LongArray): Double? {
        if (t == null) return null
        if (recentCursor[HOLD] >= heads[HOLD]) return 0.0
        val first = samples.firstOrNull { it.max[HOLD] > recentCursor[HOLD] } ?: return null
        return Duration.between(first.t, t).toMillis() / 1000.0
    }

    private fun insertSafetyRun(
        runId: Long, t: Instant?, dbMsTotal: Long, parts: LongArray, run: Run, wallMs: Long, carryIn: Int, reset: Boolean, heads: LongArray,
        age: Double?, lapNoRow: Int, lapCompleted: Boolean, lapSeconds: Double?, lapAge: Double, expedited: Boolean, restart: Boolean,
    ) {
        jdbc.update(
            """
            INSERT INTO counter_safety_run (run_id, db_started_at, budget_ms, db_ms_total, db_ms_open, db_ms_reread, db_ms_recent, db_ms_full,
                repair_ms, repair_ms_max, record_ms, chunk_ms_max, wall_ms, repair_pairs, carry_in, carry_out, carry_dropped,
                chunks_reread, chunks_recent, chunks_full, reread_left, reread_ids, sample_db_ms, sample_count, sample_short, reset,
                recent_hold_cursor, recent_res_cursor, hold_head, res_head, recent_cursor_age_s, recent_skip_from, recent_skip_to, recent_skip_ids,
                full_schedule, full_user, full_orphan_hold, full_orphan_res, lap_no, lap_completed, lap_seconds, lap_age_s, expedited, restart)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            runId, t?.let(Timestamp::from), cfg.budget.toMillis(), dbMsTotal, parts[0], parts[1], parts[2], parts[3],
            parts[4], ms(run.repairMaxNs), parts[5], ms(run.maxStepNs), wallMs, run.repairPairs, carryIn, carry.size, run.carryDropped,
            run.chunks[Branch.REREAD.ordinal], run.chunks[Branch.RECENT.ordinal], run.chunks[Branch.FULL.ordinal], run.rereadLeft, run.rereadIds,
            ms(sampleNsSinceRun), sampleCountSinceRun, run.sampleShort, reset,
            recentCursor[HOLD], recentCursor[RES], heads[HOLD], heads[RES], age, run.skipFrom, run.skipTo, run.skipIds,
            if (lapCompleted) null else fullSchedule, if (lapCompleted || fullAfter == Long.MIN_VALUE) null else fullAfter,
            if (lapCompleted) null else fullOrphan[HOLD], if (lapCompleted) null else fullOrphan[RES],
            lapNoRow, lapCompleted, lapSeconds, lapAge, expedited, restart,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(SafetyNetReconciler::class.java)
        const val SAFETY_APP = "safety-app"
        const val SAFETY_EXT = "safety-ext"
        private const val HOLD = 0
        private const val RES = 1
        private val KIND_NAMES = arrayOf("홀드", "예약")
        private const val FAIL_SKIP = 3
        private const val MAX_SAMPLES = 100_000

        private const val P = "seat.hold.counter.safety"
        const val RUN_METRIC = "$P.run"
        const val DB_MS_METRIC = "$P.db_ms"
        const val BORROWS_METRIC = "$P.borrows"
        const val LAG_IDS_METRIC = "$P.recent.lag_ids"
        const val CURSOR_AGE_METRIC = "$P.recent.cursor_age_seconds"
        const val SKIPPED_IDS_METRIC = "$P.recent.skipped_ids"
        const val LAP_SECONDS_METRIC = "$P.lap_seconds"
        const val LAP_OVERDUE_METRIC = "$P.lap_overdue"
        const val EXPEDITED_METRIC = "$P.expedited"
        const val ERRORS_METRIC = "$P.errors"
        const val CARRY_METRIC = "$P.carry"
        const val CARRY_DROPPED_METRIC = "$P.carry_dropped"
        const val SAMPLE_DB_MS_METRIC = "$P.sample_db_ms"
        const val SELF_RECORD_METRIC = "$P.self_record"

        private fun ms(ns: Long) = TimeUnit.NANOSECONDS.toMillis(ns)

        private val SAMPLE_SQL = """
            SELECT now() AS t, (SELECT max(id) FROM seat_hold) AS h_max, (SELECT max(id) FROM reservation) AS r_max,
                   pg_relation_filenode('seat_hold') AS h_node, pg_relation_filenode('reservation') AS r_node
        """.trimIndent()

        /** 열린 회차(앱 Clock) — 지금 C와 달리 마감 회차의 쿼터 행 수를 세지 않는다(그 count가 마감 회차 행 수에 비례) */
        private const val OPEN_SCHEDULES_SQL = "SELECT id FROM product_schedule WHERE starts_at > ? ORDER BY id"

        /** 최근 창 (나) 행 있음 — id 범위 (lo, hi]의 쌍(열린 회차만). 파라미터: lo, hi, 열린 회차 배열 */
        private const val HOLD_PAIRS_SQL = "SELECT DISTINCT schedule_id, user_id FROM seat_hold WHERE id > ? AND id <= ? AND schedule_id = ANY(?)"
        private const val RES_PAIRS_SQL =
            "SELECT DISTINCT schedule_id, user_id FROM reservation WHERE id > ? AND id <= ? AND status = 'CONFIRMED' AND schedule_id = ANY(?)"
    }
}
