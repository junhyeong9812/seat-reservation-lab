package com.jun.labs.seatreservation.service.impl.drift

import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties.CleanupMode
import com.jun.labs.seatreservation.service.impl.SalesCloseGate
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

/** `counter_cleanup_run` 1행. */
data class CleanupRecord(
    val mode: String,
    val scheduleId: Long?,
    val holdsSeen: Int,
    val rowsDeleted: Int,
    val waitedMs: Long,
    val stmtMs: Long,
)

/** 정리 한 번 요약. */
data class CleanupRun(
    val mode: CleanupMode,
    val at: Instant,
    /** ①: 대상 회차 수(마감 + 쿼터 행 남음) · ②: 훑은 회차 수 */
    val schedules: Int,
    val rowsDeleted: Int,
    /** ①: 홀드가 남아 건너뛴 회차 수 */
    val skippedWithHolds: Int,
    /** ①: 트랜잭션이 실패한 회차 수(로그·지표 `seat.hold.counter.cleanup.errors`) */
    val errors: Int,
    /** 이번 실행의 커넥션 빌림 수(기록 INSERT 포함) = 지표 `seat.hold.counter.cleanup.borrows` 증가분 */
    val borrows: Long,
    val records: List<CleanupRecord>,
)

/**
 * 쿼터 행 정리 2방식(명세 §1-3 · §10 · 설계 §6). `seat.hold.counter-drift.cleanup.mode`로 고른다(none이면 아무것도 하지 않는다).
 *
 * ① schedule — 마감 회차(starts_at <= now) 중 쿼터 행이 남은 회차마다 한 트랜잭션: 회차 행 `FOR NO KEY UPDATE`(선점의 마감 판정 `FOR SHARE`와 직렬화 —
 *   진행 중 선점이 있으면 그 커밋을 기다린다) → 새 문장으로 홀드 존재 확인 → 없으면 **그 회차의 쿼터 행 전부**(확정 쌍 포함 — 판정기·대조가 마감 회차를 빼므로
 *   어긋남이 아니다) 삭제 → 기록. 홀드가 남은 회차는 지우지 않는다(남은 홀드의 만료 감소에 행이 있어야 한다).
 * ② zero — 모든 회차의 cnt = 0 행을 청크마다 한 문장으로 지운다. 진행 중 upsert가 같은 행을 1로 올리는 중이면 DELETE가 그 행을 기다렸다가
 *   새 버전으로 `cnt = 0`을 다시 보고(EvalPlanQual) 건너뛴다. 홀드·확정 예약이 있는 쌍은 NOT EXISTS로 남긴다.
 * 커넥션 빌림마다 `seat.hold.counter.cleanup.borrows` +1(대조와 같은 규칙).
 */
@Component
class QuotaCleanup(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val properties: SeatHoldProperties,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private var borrows = 0L

    private fun <T> borrow(block: () -> T): T {
        borrows++
        meters.counter(BORROWS_METRIC).increment()
        return block()
    }

    @Synchronized
    fun runOnce(): CleanupRun {
        borrows = 0
        val mode = properties.counterDrift.cleanup.mode
        val at = clock.instant()
        val run = when (mode) {
            CleanupMode.NONE -> CleanupRun(mode, at, 0, 0, 0, 0, 0, emptyList())
            CleanupMode.SCHEDULE -> bySchedule(at)
            CleanupMode.ZERO -> zeroRows(at)
        }
        if (run.rowsDeleted > 0) meters.counter(DELETED_METRIC, "mode", mode.name.lowercase()).increment(run.rowsDeleted.toDouble())
        return run
    }

    private fun bySchedule(at: Instant): CleanupRun {
        val targets = borrow {
            jdbc.queryForList(
                """
                SELECT s.id FROM product_schedule s
                WHERE s.starts_at <= ? AND EXISTS (SELECT 1 FROM user_hold_quota q WHERE q.schedule_id = s.id)
                ORDER BY s.id
                """.trimIndent(),
                Long::class.java, Timestamp.from(at),
            )
        }
        var errors = 0
        val records = targets.mapNotNull { s ->
            try {
                borrow { tx.execute { cleanSchedule(s) } }
            } catch (e: Exception) {
                // 회차 하나의 실패(교착 등)가 다른 회차의 정리를 막지 않게 — 무음 금지: ERROR 로그 + 지표, 다음 실행이 다시 본다
                errors++
                meters.counter(ERRORS_METRIC, "mode", MODE_SCHEDULE).increment()
                log.error("정리 ①: 회차 {} 실패 — 다음 실행에서 다시 본다: {}", s, e.toString())
                null
            }
        }
        records.filter { it.holdsSeen > 0 }.forEach {
            log.info("정리 ①: 회차 {}에 홀드가 남아 쿼터 행을 지우지 않았다", it.scheduleId)
        }
        return CleanupRun(
            mode = CleanupMode.SCHEDULE, at = at, schedules = targets.size, rowsDeleted = records.sumOf { it.rowsDeleted },
            skippedWithHolds = records.count { it.holdsSeen > 0 }, errors = errors, borrows = borrows, records = records,
        )
    }

    /** 회차 하나 — 트랜잭션 안. 잠근 뒤 회차가 다시 열렸으면(starts_at 변경) 건너뛴다(null, 기록 없음 — 정리 대상이 아니다). */
    private fun cleanSchedule(scheduleId: Long): CleanupRecord? {
        val t0 = System.nanoTime()
        val startsAt = jdbc.queryForList(
            // FOR NO KEY UPDATE(코드 루프 1 L1-9): 선점의 마감 판정 FOR SHARE와는 충돌해 직렬화하고, 홀드·예약 INSERT의 FK 검사(FOR KEY SHARE)는
            // 막지 않는다 — 확정·만료처럼 이 회차에 행을 쓰는 다른 경로가 정리 동안 기다리지 않는다
            "SELECT starts_at FROM product_schedule WHERE id = ? FOR NO KEY UPDATE", Timestamp::class.java, scheduleId,
        ).singleOrNull()
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        if (startsAt == null || !SalesCloseGate.isClosed(startsAt.toInstant(), clock.instant())) {
            log.info("정리 ①: 회차 {}는 잠근 뒤 보니 마감이 아니다(starts_at={}) — 건너뛴다", scheduleId, startsAt)
            return null
        }
        val t1 = System.nanoTime()
        // 잠금 뒤 새 문장(READ COMMITTED) — 그 사이 커밋된 홀드가 보인다
        val holds = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM seat_hold WHERE schedule_id = ?)", Boolean::class.java, scheduleId,
        ) == true
        val deleted = if (holds) 0 else jdbc.update("DELETE FROM user_hold_quota WHERE schedule_id = ?", scheduleId)
        val record = CleanupRecord(
            mode = MODE_SCHEDULE, scheduleId = scheduleId, holdsSeen = if (holds) 1 else 0, rowsDeleted = deleted,
            waitedMs = waitedMs, stmtMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1),
        )
        insertRecord(record)
        return record
    }

    private fun zeroRows(at: Instant): CleanupRun {
        val k = properties.counterDrift.cleanup.chunkSize
        val schedules = borrow { jdbc.queryForList("SELECT id FROM product_schedule ORDER BY id", Long::class.java) }
        var deleted = 0
        var stmtNanos = 0L
        schedules.forEach { s ->
            var after = Long.MIN_VALUE
            while (true) {
                val t0 = System.nanoTime()
                val row = borrow { jdbc.queryForMap(ZERO_SQL, s, after, k, s, after) }
                stmtNanos += System.nanoTime() - t0
                deleted += (row["deleted"] as Number).toInt()
                val scanned = (row["scanned"] as Number).toInt()
                if (scanned < k) break
                after = (row["last_user"] as Number).toLong()
            }
        }
        val record = CleanupRecord(MODE_ZERO, null, 0, deleted, 0, TimeUnit.NANOSECONDS.toMillis(stmtNanos))
        borrow { insertRecord(record) }
        return CleanupRun(CleanupMode.ZERO, at, schedules.size, deleted, 0, 0, borrows, listOf(record))
    }

    private fun insertRecord(r: CleanupRecord) {
        jdbc.update(
            """
            INSERT INTO counter_cleanup_run (at, mode, schedule_id, holds_seen, rows_deleted, waited_ms, stmt_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Timestamp.from(clock.instant()), r.mode, r.scheduleId, r.holdsSeen, r.rowsDeleted, r.waitedMs, r.stmtMs,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(QuotaCleanup::class.java)
        const val BORROWS_METRIC = "seat.hold.counter.cleanup.borrows"
        const val DELETED_METRIC = "seat.hold.counter.cleanup.deleted"
        const val ERRORS_METRIC = "seat.hold.counter.cleanup.errors"
        const val MODE_SCHEDULE = "schedule"
        const val MODE_ZERO = "zero"

        /** ② 한 청크 = 한 문장: 회차의 쿼터 행 k개 범위를 정하고 그 범위의 cnt = 0 행만 지운다. 파라미터: 회차, after, k, 회차, after */
        private val ZERO_SQL = """
            WITH b AS (SELECT user_id FROM user_hold_quota WHERE schedule_id = ? AND user_id > ? ORDER BY user_id LIMIT ?),
            lim AS (SELECT max(user_id) AS last_user, count(*) AS scanned FROM b),
            d AS (
              DELETE FROM user_hold_quota q
              WHERE q.schedule_id = ? AND q.user_id > ? AND q.user_id <= (SELECT last_user FROM lim) AND q.cnt = 0
                AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.schedule_id = q.schedule_id AND h.user_id = q.user_id)
                AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.schedule_id = q.schedule_id AND r.user_id = q.user_id AND r.status = 'CONFIRMED')
              RETURNING 1)
            SELECT (SELECT last_user FROM lim) AS last_user, (SELECT scanned FROM lim) AS scanned, (SELECT count(*) FROM d) AS deleted
        """.trimIndent()
    }
}
