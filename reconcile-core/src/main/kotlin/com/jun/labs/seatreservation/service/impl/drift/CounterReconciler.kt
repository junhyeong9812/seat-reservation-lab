package com.jun.labs.seatreservation.service.impl.drift

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
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
/**
 * 바퀴·틱·실행 하나의 카운터(빌림 · 청크 · 실패) — 전체 대조 C·F · 증분 · 안전망이 같이 쓴다. 지표 이름은 쓰는 쪽이 정한다(기존 지표에 새 일을 섞지 않는다 — §4.2 공통 원칙 ④).
 * 빌릴 때마다 [borrowsMetric] +1 — 자동 커밋 문장 1개 = 1, 트랜잭션 1개 = 1.
 */
internal class DriftPass(
    private val meters: MeterRegistry,
    private val borrowsMetric: String,
    private val errorsMetric: String,
    private val skippedChunksMetric: String?,
    private val label: String,
) : RepairPass {
    var borrows = 0L
    var chunks = 0
    var pairsScanned = 0L
    var orphanChunks = 0
    var skippedChunks = 0
    var errors = 0
    var chunkMsTotal = 0L
    var chunkMsMax = 0L
    var orphanMsTotal = 0L

    /** 바퀴를 끝까지 돌리되 실패를 숨기지 않는다 — run.errors · 지표 `…errors{stage}` · ERROR 로그(코드 루프 1 L1-4·L1-7). */
    fun fail(stage: String, skipChunk: Boolean, message: String, e: Throwable) {
        errors++
        meters.counter(errorsMetric, "stage", stage).increment()
        if (skipChunk) {
            skippedChunks++
            skippedChunksMetric?.let { meters.counter(it).increment() }
        }
        log.error("{} 실패({}): {} — {}", label, stage, message, e.toString())
    }

    override fun <T> borrow(block: () -> T): T {
        borrows++
        meters.counter(borrowsMetric).increment()
        return block()
    }

    override fun recordFailed(message: String, e: Throwable) = fail("record", skipChunk = false, message, e)

    companion object {
        private val log = LoggerFactory.getLogger(CounterReconciler::class.java)
    }
}

/** `counter_reconcile_run` 1행 — 전체 대조 · 증분 틱 · 안전망 실행이 같은 문장으로 쓴다. */
internal fun insertReconcileRun(jdbc: JdbcTemplate, r: ReconcileRun): Long = jdbc.queryForObject(
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

/** 행 없음 갈래 문장(설계 `orphan-v2.sql` · `orphan-res-v2.sql` + 열린 회차 조건 — 명세 §11 N1). 전체 대조 ②와 안전망(최근 창 (가) · 전체 커서 ②)이 같은 문장을 쓴다. */
internal object OrphanQueries {
    /** 파라미터: id 하한(포함), id 상한(미포함), now */
    val HOLD_SQL = """
        SELECT h.schedule_id, h.user_id, count(*) AS n
        FROM seat_hold h
        WHERE h.id >= ? AND h.id < ?
          AND h.schedule_id IN (SELECT id FROM product_schedule WHERE starts_at > ?)
          AND NOT EXISTS (SELECT 1 FROM user_hold_quota q WHERE q.schedule_id = h.schedule_id AND q.user_id = h.user_id)
        GROUP BY 1, 2
    """.trimIndent()

    val RESERVATION_SQL = """
        SELECT r.schedule_id, r.user_id, count(*) AS n
        FROM reservation r
        WHERE r.id >= ? AND r.id < ? AND r.status = 'CONFIRMED'
          AND r.schedule_id IN (SELECT id FROM product_schedule WHERE starts_at > ?)
          AND NOT EXISTS (SELECT 1 FROM user_hold_quota q WHERE q.schedule_id = r.schedule_id AND q.user_id = r.user_id)
        GROUP BY 1, 2
    """.trimIndent()

    /** id 범위 [a, b)의 행 없음 쌍 → (쌍, 개수). 자동 커밋 문장 1개(빌림 1 — 호출자가 센다). */
    fun query(jdbc: JdbcTemplate, hold: Boolean, a: Long, b: Long, now: Timestamp): List<Pair<Pair<Long, Long>, Int>> =
        jdbc.queryForList(if (hold) HOLD_SQL else RESERVATION_SQL, a, b, now).map { r ->
            ((r["schedule_id"] as Number).toLong() to (r["user_id"] as Number).toLong()) to (r["n"] as Number).toInt()
        }
}

/**
 * 후보 C(앱 안)·F(reconciler 프로필 별도 프로세스) — 카운터 청크 대조(설계 §4 v2 두 갈래 · 명세 §11 R2).
 *
 * 대상은 **열린 회차(starts_at > 앱 Clock now)뿐**이다(명세 §10: 마감 회차는 카운터를 쓰지 않는다 — 제외한 회차·쌍 수만 바퀴 기록에 남긴다).
 * ① 쿼터 행 주도 청크: 회차의 쿼터 행을 PK keyset으로 k개 읽고 쌍마다 실제 값을 센다 — [ChunkDetector](모양 손잡이 `detect-shape` · ADR-012 R8 §4.1)에 맡긴다(청크 = 빌림 1).
 * ② 행 없음 쌍: 홀드·CONFIRMED 예약을 PK(id) 범위 청크로 읽어 쿼터 행이 없는 쌍을 찾는다 — **두 갈래 모두 열린 회차만**(명세 §11 N1).
 * 어긋나 보인 쌍마다 [PairRepairRunner](이음새 → 쌍마다 트랜잭션 · lock_timeout · 마감 재확인 · [PairRepair] · 실패하면 skipped).
 * 커넥션은 앱 풀을 공유한다(명세 §10 D8). 빌릴 때마다 `seat.hold.counter.reconcile.borrows` +1 — 자동 커밋 문장 1개 = 1, 트랜잭션 1개 = 1.
 */
@Component
class CounterReconciler(
    private val jdbc: JdbcTemplate,
    private val properties: ReconcileProperties,
    private val detector: ChunkDetector,
    private val runner: PairRepairRunner,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private fun newPass() = DriftPass(meters, BORROWS_METRIC, ERRORS_METRIC, SKIPPED_CHUNKS_METRIC, "대조")

    fun runOnce(source: String): ReconcileRun {
        val cfg = properties
        val pass = newPass()
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

        val found = LinkedHashMap<Pair<Long, Long>, PairObservation>()
        open.forEach { s -> scanQuotaRows(pass, s, cfg.chunkSize, cfg.detectShape, found) }
        if (open.isNotEmpty()) scanOrphans(pass, now, cfg.orphanChunkSize, found)

        // 쌍 복구 — 정렬 순서(만료 A와 같은 잠금 순서)
        val outcomes = found.values.sortedWith(compareBy({ it.scheduleId }, { it.userId })).map { o ->
            runner.repairOne(pass, source, o, cfg.lockTimeout.toMillis())
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
        val id = pass.borrow { insertReconcileRun(jdbc, run) }
        if (run.found > 0 || run.errors > 0) {
            log.warn(
                "대조 바퀴({}): 회차 {} · 쌍 {} · 발견 {} · 복구 {} · 오탐 {} · skipped {} · 마감 제외 {} · 오류 {} · 제외 회차 {}/쌍 {}",
                source, run.schedules, run.pairsScanned, run.found, run.repaired, run.falsePositives, run.skipped, run.skippedClosed, run.errors,
                skippedSchedules, skippedPairs,
            )
        }
        return run.copy(id = id)
    }

    /**
     * ① 쿼터 행 주도 — keyset 청크를 [detector]로(one-shot = 지금 한 문장 · split·group = 탐지 전용 트랜잭션 하나, ADR-012 §4.1).
     * scanned < k면 그 회차 끝. 실패하면 그 회차의 남은 청크를 건너뛴다(부분 집계 없음 — 탐지기가 예외로 끝낸다).
     */
    private fun scanQuotaRows(pass: DriftPass, scheduleId: Long, k: Int, shape: DetectShape, found: MutableMap<Pair<Long, Long>, PairObservation>) {
        var after = Long.MIN_VALUE
        while (true) {
            val t0 = System.nanoTime()
            val chunk = try {
                pass.borrow { detector.detect(scheduleId, QuotaSelector.Keyset(after, k)) }
            } catch (e: Exception) {
                val what = if (shape == DetectShape.ONE_SHOT) "쿼터 청크" else "쿼터 청크(${shape.name.lowercase()})"
                pass.fail("chunk", skipChunk = true, "$what — 회차 $scheduleId 의 남은 청크를 건너뛴다(user_id > $after)", e)
                return
            } finally {
                // chunk_ms = 청크 하나의 벽시계(split·group은 트랜잭션 시작·끝 + 세 조회 + 앱 집계 — §4.1 선검증 M2)
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

    /** ② 행 없음 쌍 — 홀드·CONFIRMED 예약의 id 범위 청크. 열린 회차 조건은 바퀴 시작 시각 그대로(두 갈래 모두). */
    private fun scanOrphans(pass: DriftPass, now: Timestamp, m: Int, found: MutableMap<Pair<Long, Long>, PairObservation>) {
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
        val orphans = LinkedHashMap<Pair<Long, Long>, Int>()
        for ((hold, lo, hi) in listOf(
            Triple(true, bounds["h_min"], bounds["h_max"]),
            Triple(false, bounds["r_min"], bounds["r_max"]),
        )) {
            if (lo == null || hi == null) continue
            var a = (lo as Number).toLong()
            val max = (hi as Number).toLong()
            while (a <= max) {
                val b = a + m
                val t0 = System.nanoTime()
                val rows = try {
                    pass.borrow { OrphanQueries.query(jdbc, hold, a, b, now) }
                } catch (e: Exception) {
                    pass.fail("orphan", skipChunk = true, "행 없음 청크 — 이 갈래의 남은 청크를 건너뛴다(id >= $a)", e)
                    break
                } finally {
                    meters.timer("seat.hold.counter.reconcile.orphan").record(System.nanoTime() - t0, TimeUnit.NANOSECONDS)
                    pass.orphanMsTotal += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                }
                pass.orphanChunks++
                rows.forEach { (key, n) -> orphans.merge(key, n, Int::plus) }
                a = b
            }
        }
        // 쿼터 주도 갈래가 이미 본 쌍(그 사이 행이 생긴 경우)은 그쪽 관측을 쓴다
        orphans.forEach { (k, n) -> found.putIfAbsent(k, PairObservation(k.first, k.second, null, n)) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CounterReconciler::class.java)
        const val BORROWS_METRIC = "seat.hold.counter.reconcile.borrows"
        /** 마감 회차 제외 수(unit = schedules | pairs) — 쌍 skipped(복구 실패)와 이름이 겹치지 않게(코드 루프 1 L1-15) */
        const val EXCLUDED_METRIC = "seat.hold.counter.reconcile.excluded"
        const val ERRORS_METRIC = "seat.hold.counter.reconcile.errors"
        /** 복구 트랜잭션에서 회차가 마감(또는 없음)으로 보인 쌍의 skipped 사유 */
        const val SKIP_CLOSED = "closed"
        const val SKIPPED_CHUNKS_METRIC = "seat.hold.counter.reconcile.skipped_chunks"
    }
}
