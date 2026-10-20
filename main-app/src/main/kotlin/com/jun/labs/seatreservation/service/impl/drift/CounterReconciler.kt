package com.jun.labs.seatreservation.service.impl.drift

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import com.jun.labs.seatreservation.service.impl.SalesCloseGate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 테스트 이음새(설계 §1b · 명세 §10 D1): 대조가 어긋남을 찾은 뒤, **복구 트랜잭션 시작 전**에 부른다. 빈이 없으면(운영) 아무것도 하지 않는다.
 * 테스트는 빈으로 등록해 그 사이에 다른 커넥션의 쓰기를 끼워 넣는다(오탐 경로).
 */
fun interface CounterRepairProbe {
    fun afterDetect(scheduleId: Long, userId: Long, observedCnt: Int?, observedActual: Int)
}

/** 대조 한 바퀴 요약 = `counter_reconcile_run` 1행 + 쌍 결과. */
data class ReconcileRun(
    val id: Long,
    val source: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val schedules: Int,
    val chunks: Int,
    val pairsScanned: Long,
    val orphanChunks: Int,
    val found: Int,
    val repaired: Int,
    val falsePositives: Int,
    /** 복구 실패로 건너뛴 쌍(다음 바퀴가 다시 본다) — 마감 제외는 [skippedClosed]로 따로 센다 */
    val skipped: Int,
    /** 탐지 뒤 복구 트랜잭션에서 회차 마감을 확인해 쓰지 않은 쌍(skipped reason=closed — 실패가 아니라 마감 제외, 코드 루프 3 L3-4) */
    val skippedClosed: Int,
    val skippedSchedules: Int,
    val skippedPairs: Long,
    val skippedChunks: Int,
    val errors: Int,
    val chunkMsTotal: Long,
    val chunkMsMax: Long,
    val orphanMsTotal: Long,
    /** 이 바퀴의 커넥션 빌림 수(바퀴 기록 INSERT 포함) = 지표 `seat.hold.counter.reconcile.borrows` 증가분 */
    val borrows: Long,
    val outcomes: List<DriftOutcome>,
)

/**
 * 후보 C(앱 안)·F(reconciler 프로필 별도 프로세스) — 카운터 청크 대조(설계 §4 v2 두 갈래 · 명세 §11 R2).
 *
 * 대상은 **열린 회차(starts_at > 앱 Clock now)뿐**이다(명세 §10: 마감 회차는 카운터를 쓰지 않는다 — 제외한 회차·쌍 수만 바퀴 기록에 남긴다).
 * ① 쿼터 행 주도 청크: 회차의 쿼터 행을 PK keyset으로 k개 읽고 쌍마다 실제 값을 인덱스 탐색으로 센다(한 문장 = 한 스냅숏 = 빌림 1).
 * ② 행 없음 쌍: 홀드·CONFIRMED 예약을 PK(id) 범위 청크로 읽어 쿼터 행이 없는 쌍을 찾는다 — **두 갈래 모두 열린 회차만**(명세 §11 N1).
 * 어긋나 보인 쌍마다: [CounterRepairProbe] → 트랜잭션 하나(`SET LOCAL lock_timeout`) 안에서 [PairRepair](잠근 뒤 다시 세어 맞으면 오탐).
 * 실패하면 롤백 뒤 **새 트랜잭션**으로 skipped(reason = SQLSTATE)를 쓴다 — 다음 바퀴가 다시 탐지한다(설계 §1a).
 * 커넥션은 앱 풀을 공유한다(명세 §10 D8). 빌릴 때마다 `seat.hold.counter.reconcile.borrows` +1 — 자동 커밋 문장 1개 = 1, 트랜잭션 1개 = 1.
 * ①의 탐지 모양은 손잡이 `detect-shape`(ADR-012 R8 · §4.1) — split·group은 청크마다 탐지 전용 트랜잭션 하나([detectTx]), 복구는 [tx](READ COMMITTED) 그대로.
 */
@Component
class CounterReconciler(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val properties: ReconcileProperties,
    private val repair: PairRepair,
    private val recorder: CounterDriftRecorder,
    private val probe: ObjectProvider<CounterRepairProbe>,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    /**
     * split·group 탐지 전용(ADR-012 §4.1 · 선검증 M1): 읽기 전용 REPEATABLE READ — 청크의 세 조회가 한 스냅샷을 본다.
     * 주입받은 [tx](앱 공용 — 복구 트랜잭션)는 바꾸지 않는다(복구는 READ COMMITTED여야 잠근 뒤 최신 값을 본다).
     */
    private val detectTx = TransactionTemplate(requireNotNull(tx.transactionManager) { "TransactionTemplate에 트랜잭션 매니저가 없다" }).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    private class Observation(val scheduleId: Long, val userId: Long, val cnt: Int?, var actual: Int)

    private class Pass {
        var borrows = 0L
        var chunks = 0
        var pairsScanned = 0L
        var orphanChunks = 0
        var skippedChunks = 0
        var errors = 0
        var chunkMsTotal = 0L
        var chunkMsMax = 0L
        var orphanMsTotal = 0L
    }

    /** 바퀴를 끝까지 돌리되 실패를 숨기지 않는다 — run.errors · 지표 `…reconcile.errors{stage}` · ERROR 로그(코드 루프 1 L1-4·L1-7). */
    private fun Pass.fail(stage: String, skipChunk: Boolean, message: String, e: Throwable) {
        errors++
        meters.counter(ERRORS_METRIC, "stage", stage).increment()
        if (skipChunk) {
            skippedChunks++
            meters.counter(SKIPPED_CHUNKS_METRIC).increment()
        }
        log.error("대조 실패({}): {} — {}", stage, message, e.toString())
    }

    private fun <T> Pass.borrow(block: () -> T): T {
        borrows++
        meters.counter(BORROWS_METRIC).increment()
        return block()
    }

    fun runOnce(source: String): ReconcileRun {
        val cfg = properties
        val pass = Pass()
        val startedAt = clock.instant()
        val now = Timestamp.from(startedAt)

        // 대상 회차 + 제외 기록(마감 회차 수·그 회차들의 쿼터 행 수 — PK 앞부분 count). 실패하면 이 바퀴는 아무것도 보지 못한 것으로 기록한다
        val schedules = try {
            pass.borrow {
                jdbc.query(
                    """
                    SELECT s.id, s.starts_at > ? AS open,
                           CASE WHEN s.starts_at <= ? THEN (SELECT count(*) FROM user_hold_quota q WHERE q.schedule_id = s.id) ELSE 0 END AS closed_pairs
                    FROM product_schedule s ORDER BY s.id
                    """.trimIndent(),
                    { rs, _ -> Triple(rs.getLong("id"), rs.getBoolean("open"), rs.getLong("closed_pairs")) },
                    now, now,
                )
            }
        } catch (e: Exception) {
            pass.fail("schedules", skipChunk = false, "대상 회차 조회 — 이 바퀴는 대조하지 못했다", e)
            emptyList()
        }
        val open = schedules.filter { it.second }.map { it.first }
        val skippedSchedules = schedules.count { !it.second }
        // skipped_pairs = 마감 회차의 **쿼터 행** 수(PK 앞부분 count). 판정기 counter_excluded_pairs(마감 회차의 쿼터 행 ∪ 실제 값 쌍)와 정의가 다르다 —
        // 정리 ①이 쿼터 행을 지운 회차는 여기서 0이지만 판정기에서는 확정 쌍만큼 남는다(코드 루프 2 L2-9, 이름은 계약이라 그대로)
        val skippedPairs = schedules.sumOf { it.third }

        val found = LinkedHashMap<Pair<Long, Long>, Observation>()
        open.forEach { s ->
            when (cfg.detectShape) {
                DetectShape.ONE_SHOT -> scanQuotaRows(pass, s, cfg.chunkSize, found)
                DetectShape.SPLIT, DetectShape.GROUP -> scanQuotaRowsApp(pass, s, cfg.chunkSize, cfg.detectShape, found)
            }
        }
        if (open.isNotEmpty()) scanOrphans(pass, now, cfg.orphanChunkSize, found)

        // 쌍 복구 — 정렬 순서(만료 A와 같은 잠금 순서)
        val outcomes = found.values.sortedWith(compareBy({ it.scheduleId }, { it.userId })).map { o ->
            repairOne(pass, source, o, cfg.lockTimeout.toMillis())
        }

        meters.counter(EXCLUDED_METRIC, "unit", "schedules").increment(skippedSchedules.toDouble())
        meters.counter(EXCLUDED_METRIC, "unit", "pairs").increment(skippedPairs.toDouble())
        val finishedAt = clock.instant()
        // borrows 정의(코드 루프 1 L1-12): 이 바퀴의 커넥션 빌림 전부 = 아래 바퀴 기록 INSERT **포함**. 지표 `…reconcile.borrows` 증가분·Hikari acquire 증가분과 같다.
        // (V7 열 주석의 '이 기록 INSERT는 빼고'는 낡은 문구다 — V7은 수정 금지라 이 주석과 ADR-009가 정본)
        val borrowsWithRecord = pass.borrows + 1
        val run = ReconcileRun(
            id = 0, source = source, startedAt = startedAt, finishedAt = finishedAt,
            schedules = open.size, chunks = pass.chunks, pairsScanned = pass.pairsScanned, orphanChunks = pass.orphanChunks,
            found = found.size,
            repaired = outcomes.count { it.action == DriftAction.REPAIRED },
            falsePositives = outcomes.count { it.action == DriftAction.FALSE_POSITIVE },
            skipped = outcomes.count { it.action == DriftAction.SKIPPED && it.reason != SKIP_CLOSED },
            skippedClosed = outcomes.count { it.action == DriftAction.SKIPPED && it.reason == SKIP_CLOSED },
            skippedSchedules = skippedSchedules, skippedPairs = skippedPairs, skippedChunks = pass.skippedChunks, errors = pass.errors,
            chunkMsTotal = pass.chunkMsTotal, chunkMsMax = pass.chunkMsMax, orphanMsTotal = pass.orphanMsTotal,
            borrows = borrowsWithRecord, outcomes = outcomes,
        )
        val id = pass.borrow { insertRun(run) }
        if (run.found > 0 || run.errors > 0) {
            log.warn(
                "대조 바퀴({}): 회차 {} · 쌍 {} · 발견 {} · 복구 {} · 오탐 {} · skipped {} · 마감 제외 {} · 오류 {} · 제외 회차 {}/쌍 {}",
                source, run.schedules, run.pairsScanned, run.found, run.repaired, run.falsePositives, run.skipped, run.skippedClosed, run.errors,
                skippedSchedules, skippedPairs,
            )
        }
        return run.copy(id = id)
    }

    /** ① 쿼터 행 주도 — 어긋남이 0이어도 1행(last_user·scanned)을 돌려주는 한 문장. scanned < k면 그 회차 끝. */
    private fun scanQuotaRows(pass: Pass, scheduleId: Long, k: Int, found: MutableMap<Pair<Long, Long>, Observation>) {
        var after = Long.MIN_VALUE
        while (true) {
            val t0 = System.nanoTime()
            val rows = try {
                pass.borrow { jdbc.queryForList(CHUNK_SQL, scheduleId, after, k, scheduleId, scheduleId) }
            } catch (e: Exception) {
                pass.fail("chunk", skipChunk = true, "쿼터 청크 — 회차 $scheduleId 의 남은 청크를 건너뛴다(user_id > $after)", e)
                return
            } finally {
                val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                meters.timer("seat.hold.counter.reconcile.chunk").record(System.nanoTime() - t0, TimeUnit.NANOSECONDS)
                pass.chunkMsTotal += ms
                pass.chunkMsMax = maxOf(pass.chunkMsMax, ms)
            }
            pass.chunks++
            val head = rows.first()
            val scanned = (head["scanned"] as Number).toInt()
            pass.pairsScanned += scanned
            rows.filter { it["user_id"] != null }.forEach { r ->
                val u = (r["user_id"] as Number).toLong()
                found[scheduleId to u] = Observation(scheduleId, u, (r["cnt"] as Number).toInt(), (r["actual"] as Number).toInt())
            }
            if (scanned < k) return
            after = (head["last_user"] as Number).toLong()
        }
    }

    /**
     * ① split·group(ADR-012 §4.1) — 청크마다 [detectTx] 하나(빌림 1): ⓐ 쿼터 행 k개(keyset) ⓑ 그 k명의 홀드 ⓒ 그 k명의 CONFIRMED 예약을 읽고 앱이 센다.
     * 청크 경계·카운터는 one-shot과 같다 — ⓐ가 0행이면 ⓑⓒ 없이 청크 1개로 세고 회차 끝, 다음 커서 = ⓐ의 마지막 user_id.
     * 어느 조회·집계든 실패하면 그 청크는 통째로 버린다(어긋남을 [found]에 넣는 것은 트랜잭션이 끝난 뒤) — 실패 경로는 one-shot과 같다.
     */
    private fun scanQuotaRowsApp(pass: Pass, scheduleId: Long, k: Int, shape: DetectShape, found: MutableMap<Pair<Long, Long>, Observation>) {
        var after = Long.MIN_VALUE
        while (true) {
            val t0 = System.nanoTime()
            val chunk = try {
                pass.borrow { detectTx.execute { detectChunk(scheduleId, after, k, shape) }!! }
            } catch (e: Exception) {
                pass.fail("chunk", skipChunk = true, "쿼터 청크(${shape.name.lowercase()}) — 회차 $scheduleId 의 남은 청크를 건너뛴다(user_id > $after)", e)
                return
            } finally {
                // chunk_ms = 트랜잭션 시작·끝 + 세 조회 + 앱 집계(§4.1 — 선검증 M2)
                val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                meters.timer("seat.hold.counter.reconcile.chunk").record(System.nanoTime() - t0, TimeUnit.NANOSECONDS)
                pass.chunkMsTotal += ms
                pass.chunkMsMax = maxOf(pass.chunkMsMax, ms)
            }
            pass.chunks++
            pass.pairsScanned += chunk.scanned
            chunk.mismatches.forEach { found[scheduleId to it.userId] = it }
            if (chunk.scanned < k) return
            after = chunk.lastUser
        }
    }

    private class DetectedChunk(val scanned: Int, val lastUser: Long, val mismatches: List<Observation>)

    /** 탐지 트랜잭션 안 — ⓐ ⓑ ⓒ + 집계. 메모리는 청크의 사용자 k명 카운터뿐(ⓑⓒ는 U 밖 사용자를 돌려주지 않는다). */
    private fun detectChunk(scheduleId: Long, after: Long, k: Int, shape: DetectShape): DetectedChunk {
        val users = LongArray(k)
        val cnts = IntArray(k)
        var scanned = 0
        jdbc.query(QUOTA_KEYSET_SQL, RowCallbackHandler { rs ->
            users[scanned] = rs.getLong(1)
            cnts[scanned] = rs.getInt(2)
            scanned++
        }, scheduleId, after, k)
        if (scanned == 0) return DetectedChunk(0, after, emptyList())

        val index = HashMap<Long, Int>(scanned * 2)
        for (i in 0 until scanned) index[users[i]] = i
        val actual = LongArray(scanned)
        val (holdSql, reservationSql) = when (shape) {
            DetectShape.SPLIT -> SPLIT_HOLD_SQL to SPLIT_RESERVATION_SQL
            DetectShape.GROUP -> GROUP_HOLD_SQL to GROUP_RESERVATION_SQL
            DetectShape.ONE_SHOT -> error("one-shot은 이 경로를 쓰지 않는다")
        }
        val ids = users.copyOf(scanned).toTypedArray()
        for (sql in listOf(holdSql, reservationSql)) {
            jdbc.query(
                PreparedStatementCreator { con ->
                    con.prepareStatement(sql).apply {
                        // split은 행을 커서로 흘려 읽는다(pgJDBC 기본 = 결과 전체를 한 번에 받음 — 트랜잭션 안이라 커서가 성립한다)
                        if (shape == DetectShape.SPLIT) fetchSize = k
                        setLong(1, scheduleId)
                        setArray(2, con.createArrayOf("bigint", ids))
                    }
                },
                RowCallbackHandler { rs ->
                    val i = index[rs.getLong(1)] ?: error("ⓑⓒ가 청크 밖 사용자 ${rs.getLong(1)}를 돌려줬다")
                    actual[i] += if (shape == DetectShape.GROUP) rs.getLong(2) else 1L
                },
            )
        }
        val mismatches = ArrayList<Observation>()
        for (i in 0 until scanned) {
            // 실제 값은 int로 — 넘치면 오류(one-shot의 `::int`와 같은 실패 → 청크 버림)
            val a = Math.toIntExact(actual[i])
            if (cnts[i] != a) mismatches += Observation(scheduleId, users[i], cnts[i], a)
        }
        return DetectedChunk(scanned, users[scanned - 1], mismatches)
    }

    /** ② 행 없음 쌍 — 홀드·CONFIRMED 예약의 id 범위 청크. 열린 회차 조건은 바퀴 시작 시각 그대로(두 갈래 모두). */
    private fun scanOrphans(pass: Pass, now: Timestamp, m: Int, found: MutableMap<Pair<Long, Long>, Observation>) {
        val bounds = try {
            pass.borrow {
                jdbc.queryForMap(
                    """
                    SELECT (SELECT min(id) FROM seat_hold) AS h_min, (SELECT max(id) FROM seat_hold) AS h_max,
                           (SELECT min(id) FROM reservation) AS r_min, (SELECT max(id) FROM reservation) AS r_max
                    """.trimIndent(),
                )
            }
        } catch (e: Exception) {
            pass.fail("bounds", skipChunk = true, "행 없음 갈래 범위 조회 — 이 바퀴의 행 없음 갈래 전체를 건너뛴다", e)
            return
        }
        val orphans = LinkedHashMap<Pair<Long, Long>, Observation>()
        for ((sql, lo, hi) in listOf(
            Triple(ORPHAN_HOLD_SQL, bounds["h_min"], bounds["h_max"]),
            Triple(ORPHAN_RESERVATION_SQL, bounds["r_min"], bounds["r_max"]),
        )) {
            if (lo == null || hi == null) continue
            var a = (lo as Number).toLong()
            val max = (hi as Number).toLong()
            while (a <= max) {
                val b = a + m
                val t0 = System.nanoTime()
                val rows = try {
                    pass.borrow { jdbc.queryForList(sql, a, b, now) }
                } catch (e: Exception) {
                    pass.fail("orphan", skipChunk = true, "행 없음 청크 — 이 갈래의 남은 청크를 건너뛴다(id >= $a)", e)
                    break
                } finally {
                    meters.timer("seat.hold.counter.reconcile.orphan").record(System.nanoTime() - t0, TimeUnit.NANOSECONDS)
                    pass.orphanMsTotal += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                }
                pass.orphanChunks++
                rows.forEach { r ->
                    val key = (r["schedule_id"] as Number).toLong() to (r["user_id"] as Number).toLong()
                    val n = (r["n"] as Number).toInt()
                    orphans.getOrPut(key) { Observation(key.first, key.second, null, 0) }.actual += n
                }
                a = b
            }
        }
        // 쿼터 주도 갈래가 이미 본 쌍(그 사이 행이 생긴 경우)은 그쪽 관측을 쓴다
        orphans.forEach { (k, v) -> found.putIfAbsent(k, v) }
    }

    private fun repairOne(pass: Pass, source: String, o: Observation, lockTimeoutMs: Long): DriftOutcome {
        val kind = when {
            o.cnt == null -> DriftKind.MISSING
            o.cnt < o.actual -> DriftKind.BELOW
            else -> DriftKind.ABOVE
        }
        return try {
            // 이음새 호출도 try 안 — 이음새가 실패해도 그 쌍은 skipped로 남고 바퀴는 계속된다(코드 루프 2 L2-12)
            probe.ifAvailable { it.afterDetect(o.scheduleId, o.userId, o.cnt, o.actual) }
            val outcome = pass.borrow {
                tx.execute {
                    jdbc.queryForList("SELECT set_config('lock_timeout', ?, true)", "${lockTimeoutMs}ms")
                    // 복구 트랜잭션 안에서 회차 마감을 다시 본다(코드 루프 2 L2-2 — 기준 = 앱 Clock). 탐지와 복구 사이에 마감되고 정리 ①이 쿼터 행을
                    // 지웠을 수 있다 — 그 회차는 카운터를 쓰지 않으므로 행을 되살리지 않고 skipped(reason=closed)만 남긴다.
                    // FOR SHARE: 정리 ①(회차 행 FOR UPDATE)이 진행 중이면 그 커밋을 기다린 뒤 판정한다(lock_timeout 안)
                    val startsAt = jdbc.queryForList(
                        "SELECT starts_at FROM product_schedule WHERE id = ? FOR SHARE", Timestamp::class.java, o.scheduleId,
                    ).singleOrNull()
                    if (startsAt == null || SalesCloseGate.isClosed(startsAt.toInstant(), clock.instant())) {
                        DriftOutcome(source, o.scheduleId, o.userId, kind, o.cnt, o.actual, DriftAction.SKIPPED, SKIP_CLOSED).also(recorder::insert)
                    } else {
                        repair.repairInCurrentTx(source, o.scheduleId, o.userId, kind)
                    }
                }!!
            }
            if (outcome.action == DriftAction.SKIPPED && outcome.reason == SKIP_CLOSED) {
                // 마감 제외는 실패가 아니다 — drift{action=skipped}(실패)에 넣지 않고 마감 제외 지표(쌍)에 더한다(L3-4 재점검). 기록 1행은 위에서 남겼다
                meters.counter(EXCLUDED_METRIC, "unit", "pairs").increment()
                log.info("대조 복구 생략 — 회차 {}가 복구 트랜잭션에서 마감으로 보였다(사용자 {})", o.scheduleId, o.userId)
            } else {
                recorder.count(outcome)
            }
            outcome
        } catch (e: Exception) {
            val skipped = DriftOutcome(source, o.scheduleId, o.userId, kind, o.cnt, o.actual, DriftAction.SKIPPED, skipReason(e))
            try {
                pass.borrow { tx.executeWithoutResult { recorder.insert(skipped) } }
                recorder.count(skipped)
            } catch (recordFailure: Exception) {
                // skipped 기록마저 실패 — 바퀴 결과를 실패로 남긴다(성공 위장 금지): run.errors + 지표 + ERROR 로그
                recorder.recordFailed(skipped, recordFailure)
                pass.fail("record", skipChunk = false, "skipped 기록 실패 — 회차 ${o.scheduleId} 사용자 ${o.userId}", recordFailure)
            }
            skipped
        }
    }

    private fun insertRun(r: ReconcileRun): Long = jdbc.queryForObject(
        """
        INSERT INTO counter_reconcile_run (source, started_at, finished_at, schedules, chunks, pairs_scanned, orphan_chunks, found,
            repaired, false_positives, skipped, skipped_closed, skipped_schedules, skipped_pairs, skipped_chunks, errors, chunk_ms_total, chunk_ms_max,
            orphan_ms_total, borrows)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
        """.trimIndent(),
        Long::class.java,
        r.source, Timestamp.from(r.startedAt), Timestamp.from(r.finishedAt), r.schedules, r.chunks, r.pairsScanned, r.orphanChunks, r.found,
        r.repaired, r.falsePositives, r.skipped, r.skippedClosed, r.skippedSchedules, r.skippedPairs, r.skippedChunks, r.errors, r.chunkMsTotal, r.chunkMsMax,
        r.orphanMsTotal, r.borrows,
    )!!

    companion object {
        private val log = LoggerFactory.getLogger(CounterReconciler::class.java)
        const val BORROWS_METRIC = "seat.hold.counter.reconcile.borrows"
        /** 마감 회차 제외 수(unit = schedules | pairs) — 쌍 skipped(복구 실패)와 이름이 겹치지 않게(코드 루프 1 L1-15) */
        const val EXCLUDED_METRIC = "seat.hold.counter.reconcile.excluded"
        const val ERRORS_METRIC = "seat.hold.counter.reconcile.errors"
        /** 복구 트랜잭션에서 회차가 마감(또는 없음)으로 보인 쌍의 skipped 사유 */
        const val SKIP_CLOSED = "closed"
        const val SKIPPED_CHUNKS_METRIC = "seat.hold.counter.reconcile.skipped_chunks"

        /** 설계 `chunk-v2.sql` — 파라미터: 회차, after, k, 회차(홀드), 회차(예약) */
        private val CHUNK_SQL = """
            WITH q AS (
              SELECT user_id, cnt FROM user_hold_quota
              WHERE schedule_id = ? AND user_id > ? ORDER BY user_id LIMIT ?),
            x AS (
              SELECT q.user_id, q.cnt,
                     ((SELECT count(*) FROM seat_hold h WHERE h.user_id = q.user_id AND h.schedule_id = ?)
                    + (SELECT count(*) FROM reservation r WHERE r.schedule_id = ? AND r.user_id = q.user_id AND r.status = 'CONFIRMED'))::int AS actual
              FROM q)
            SELECT (SELECT max(user_id) FROM q) AS last_user, (SELECT count(*) FROM q) AS scanned, d.user_id, d.cnt, d.actual
            FROM (SELECT 1) one LEFT JOIN (SELECT * FROM x WHERE cnt <> actual) d ON true
        """.trimIndent()

        /** R8 ⓐ(split·group 공통) — 회차의 쿼터 행 keyset k개. 파라미터: 회차, after, k */
        private val QUOTA_KEYSET_SQL = """
            SELECT user_id, cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id > ? ORDER BY user_id LIMIT ?
        """.trimIndent()

        /** R8 ⓑⓒ split — 행 그대로(사용자별로 앱이 센다). 홀드는 expires_at으로 거르지 않는다(one-shot과 같음). 파라미터: 회차, U(bigint[]) */
        private val SPLIT_HOLD_SQL = "SELECT user_id FROM seat_hold WHERE schedule_id = ? AND user_id = ANY(?)"
        private val SPLIT_RESERVATION_SQL = "SELECT user_id FROM reservation WHERE schedule_id = ? AND user_id = ANY(?) AND status = 'CONFIRMED'"

        /** R8 ⓑⓒ group — 같은 조건의 사용자별 개수 */
        private val GROUP_HOLD_SQL = "SELECT user_id, count(*) FROM seat_hold WHERE schedule_id = ? AND user_id = ANY(?) GROUP BY user_id"
        private val GROUP_RESERVATION_SQL =
            "SELECT user_id, count(*) FROM reservation WHERE schedule_id = ? AND user_id = ANY(?) AND status = 'CONFIRMED' GROUP BY user_id"

        /** 설계 `orphan-v2.sql` + 열린 회차 조건(명세 §11 N1) — 파라미터: id 하한, id 상한(미포함), now */
        private val ORPHAN_HOLD_SQL = """
            SELECT h.schedule_id, h.user_id, count(*) AS n
            FROM seat_hold h
            WHERE h.id >= ? AND h.id < ?
              AND h.schedule_id IN (SELECT id FROM product_schedule WHERE starts_at > ?)
              AND NOT EXISTS (SELECT 1 FROM user_hold_quota q WHERE q.schedule_id = h.schedule_id AND q.user_id = h.user_id)
            GROUP BY 1, 2
        """.trimIndent()

        /** 설계 `orphan-res-v2.sql` + 열린 회차 조건(명세 §11 N1) */
        private val ORPHAN_RESERVATION_SQL = """
            SELECT r.schedule_id, r.user_id, count(*) AS n
            FROM reservation r
            WHERE r.id >= ? AND r.id < ? AND r.status = 'CONFIRMED'
              AND r.schedule_id IN (SELECT id FROM product_schedule WHERE starts_at > ?)
              AND NOT EXISTS (SELECT 1 FROM user_hold_quota q WHERE q.schedule_id = r.schedule_id AND q.user_id = r.user_id)
            GROUP BY 1, 2
        """.trimIndent()
    }
}
