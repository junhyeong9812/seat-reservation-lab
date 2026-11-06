package com.jun.labs.seatreservation.service.impl.expiry

import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.ExpireHoldsService
import com.jun.labs.seatreservation.service.impl.drift.DriftSource
import com.jun.labs.seatreservation.service.impl.drift.ExpiryCounters
import com.jun.labs.seatreservation.service.impl.expiry.LazyHoldProcess.Companion.DELETED_METRIC
import com.jun.labs.seatreservation.service.impl.expiry.LazyHoldProcess.Companion.MISSING_METRIC
import com.jun.labs.seatreservation.service.impl.expiry.LazyHoldProcess.Companion.PATH_BATCH
import com.jun.labs.seatreservation.service.impl.expiry.LazyHoldProcess.Companion.SKIP_LOCKED_METRIC
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR-014 C·D 만료 배치(§설계-3.4 C1~C6 · D1~D5) — 틱 하나 = 청크 트랜잭션 여러 개(A의 '틱 = 트랜잭션 하나'와 다르다). B는 주기 배치가 없다(0 반환 · 틱 지표만).
 * - 진행형 커서(선검증 DS14-5): 청크 하나가 실패해도(40P01 · MISSING 수 불일치 등) 커서를 그 청크 뒤로 옮겨 다음 청크를 계속한다 —
 *   앞쪽 후보가 같은 틱의 진행을 막지 않는다. 후보 끝에 닿은 틱은 커서를 버린다 — 남은 것(건너뜀 · 실패)은 다음 틱이 처음부터 다시 고른다.
 * - 틱당 청크 상한(`seat.hold.expiry-max-chunks-per-tick` · 코드 리뷰 CR14-2): 닿으면 틱을 끝내고 커서를 다음 틱에 넘긴다 — 다음 틱은 처음이 아니라
 *   그 커서 뒤부터 이어 간다(늘 처음부터 시작하면 앞쪽에서 계속 건너뛰는·실패하는 후보가 상한을 다 써서 뒤쪽 후보가 영영 밀린다). 넘긴 커서는 이 빈(앱 1대)의 것이다.
 * - 규칙 ①~③(§2.4): 쌍 잠금(정렬) → 지운 행 수만큼 감소 · 삭제와 감소는 같은 청크 트랜잭션. MISSING 쌍은 지우기 앞에서 복구한다(C3b · D3).
 * - C 잠금 순서: 좌석(SKIP LOCKED — 기다리지 않음) → 쿼터 행(정렬 — 기다림) → 홀드 행. D는 좌석 문장이 없다(N6 — 배치가 좌석 행을 쓰지 않는다).
 * - 지표: 청크 `seat.hold.expiry.chunk{outcome}` · 틱 `seat.hold.expiry.tick{outcome}`(청크가 하나라도 실패하면 failed — 예외는 올리지 않는다) ·
 *   지운 행 · MISSING 쌍 · SKIP LOCKED 건너뜀(C2) · `:missingIds` 불일치 롤백 · 되돌림/되돌림 기회(C6) · 청크 상한에 닿은 틱 수 `seat.hold.expiry.chunk_cap_reached`.
 */
@Component
class LazyExpiryBatch(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val properties: SeatHoldProperties,
    private val counters: ExpiryCounters,
    private val meters: MeterRegistry,
) {
    private val mode = properties.expiryMode

    /** 청크 상한에 닿은 틱이 넘긴 커서(CR14-2) — null = 다음 틱은 처음부터 */
    private val carried = AtomicReference<Cursor?>(null)

    /** 틱 하나 — 지운 홀드 행 수를 돌려준다 */
    fun expire(): Int {
        if (mode == ExpiryMode.LAZY) {
            meters.counter(ExpireHoldsService.TICK_METRIC, "outcome", "ok").increment()
            return 0
        }
        var deleted = 0
        var failed = 0
        var chunks = 0
        val cursor = carried.getAndSet(null) ?: Cursor()
        while (true) {
            if (chunks >= properties.expiryMaxChunksPerTick) {
                // 상한 — 남은 후보는 다음 틱이 이 커서 뒤부터 이어 본다(무음 금지: 지표 + 로그 — 계속 닿으면 배치가 만료를 따라가지 못하는 것이다)
                carried.set(cursor)
                meters.counter(CHUNK_CAP_METRIC).increment()
                log.warn("만료 배치({}) 틱당 청크 상한 {}에 닿았다 — 남은 후보는 다음 틱이 이어서 본다(이번 틱 지운 행 {})", mode, chunks, deleted)
                break
            }
            val before = cursor.copy()
            val result = runChunk(cursor) ?: break
            chunks++ // 청크 트랜잭션 하나(성공 · 실패 · 잠근 좌석 0 모두) — 후보가 없어 끝난 시도는 세지 않는다
            if (result >= 0) {
                deleted += result
                continue
            }
            failed++
            if (cursor == before) break // 후보를 고르기 전에 실패했다(커서가 그대로) — 같은 청크를 되풀이하지 않고 틱을 끝낸다(다음 틱이 다시 본다)
        }
        meters.counter(ExpireHoldsService.TICK_METRIC, "outcome", if (failed == 0) "ok" else "failed").increment()
        return deleted
    }

    /** 진행형 커서 — C = 앞 청크 후보의 최대 seat_id · D = 앞 청크 후보의 마지막 (회차, 사용자). 틱 안에서 살고, 청크 상한에 닿은 틱만 다음 틱에 넘긴다(CR14-2) */
    private data class Cursor(var seat: Long = 0, var schedule: Long = 0, var user: Long = 0)

    /** 청크 하나(트랜잭션 하나) — null = 후보 없음(틱 끝) · 음수 = 실패(롤백 · 커서는 이미 앞으로 갔다) · 그 밖 = 지운 행 수 */
    private fun runChunk(cursor: Cursor): Int? {
        var empty = false
        counters.beginTick()
        try {
            val n = try {
                tx.execute {
                    val r = if (mode == ExpiryMode.LAZY_SWEEP) chunkC(cursor) else chunkD(cursor)
                    if (r == null) empty = true
                    r ?: 0
                }!!
            } catch (e: Exception) {
                // 청크 하나의 실패가 같은 틱의 다른 청크를 막지 않게 — 무음 금지: ERROR 로그 + 지표 · 복구 시도 쌍은 독립 트랜잭션으로 skipped(A 틱 롤백과 같은 처리)
                meters.counter(CHUNK_METRIC, "outcome", "failed").increment()
                if (e is MissingMismatch) meters.counter(MISSING_MISMATCH_METRIC).increment()
                counters.afterRollback(e)
                log.error("만료 배치({}) 청크 실패 — 커서 뒤로 넘어가 다음 청크를 계속한다(남은 것은 다음 틱): {}", mode, e.toString())
                return -1
            }
            if (empty) return null
            meters.counter(CHUNK_METRIC, "outcome", "ok").increment()
            counters.afterCommit()
            meters.counter(DELETED_METRIC, "path", PATH_BATCH).increment(n.toDouble())
            return n
        } finally {
            counters.endTick()
        }
    }

    /** C1~C6 — 후보 = 만료 홀드가 있는 좌석 */
    private fun chunkC(cursor: Cursor): Int? {
        // C1 후보 좌석(idx_seat_hold_expires_at — V2) · 진행형 커서
        val candidates = jdbc.queryForList(
            "SELECT DISTINCT seat_id FROM seat_hold WHERE expires_at <= now() AND seat_id > ? ORDER BY seat_id LIMIT ?",
            Long::class.java, cursor.seat, properties.expiryBatchSize,
        )
        if (candidates.isEmpty()) return null
        cursor.seat = candidates.last() // 잠그지 못했거나 이 청크가 실패해도 커서는 그 뒤로 간다(DS14-5)
        // C2 좌석 잠금 — 선점·확정 중인 좌석은 건너뛴다(홀드 행을 지우지 않았으므로 다음 틱의 C1에 다시 나온다)
        val locked = jdbc.queryForList(
            "SELECT id FROM product_seat WHERE id = ANY(?::bigint[]) ORDER BY id FOR UPDATE SKIP LOCKED", Long::class.java, candidates.toTypedArray(),
        )
        meters.counter(SKIP_LOCKED_METRIC, "stage", "c2").increment((candidates.size - locked.size).toDouble())
        if (locked.isEmpty()) return 0
        val seats = locked.toTypedArray()
        // 잠긴 좌석 L 위에는 새 홀드가 생기지 않고(선점은 좌석 잠금 뒤 INSERT) 트랜잭션 안 now()는 고정이라 아래 문장 사이에 만료 행이 늘지 않는다
        val pairs = jdbc.query(
            "SELECT DISTINCT schedule_id, user_id FROM seat_hold WHERE seat_id = ANY(?::bigint[]) AND expires_at <= now() ORDER BY schedule_id, user_id",
            { rs, _ -> rs.getLong(1) to rs.getLong(2) }, seats,
        )
        // C3 쌍 잠금(규칙 ① — 청크 전체 쌍을 정렬해 한 번에) — 잠근 쌍 P
        val lockedPairs = lockPairs(pairs)
        // C3b MISSING(후보 쌍 − P) — 지우기 앞에서 복구(지울 홀드 id를 실제 값에서 빼고 그 수를 감소 예정으로)
        val missingIds = (pairs - lockedPairs.toSet()).flatMap { (s, u) ->
            val ids = jdbc.queryForList(
                "SELECT id FROM seat_hold WHERE seat_id = ANY(?::bigint[]) AND expires_at <= now() AND schedule_id = ? AND user_id = ? ORDER BY id",
                Long::class.java, seats, s, u,
            )
            repairMissing(s, u, ids)
            ids
        }
        // C4 삭제(규칙 ②) — 잠근 쌍 P로 한정 · MISSING 쌍은 PairRepair에 넘긴 id로만
        val deletedByPair = deleteForPairs(lockedPairs, seats)
        val deleted = deletedByPair.values.sum() + deleteMissing(missingIds)
        // C5 쌍별 가드 감소(P의 쌍만)
        decrement(deletedByPair)
        // C6 좌석 상태 — 잠근 좌석만 · 잠근 뒤 새 문장(§2.3)
        revertSeats(jdbc, meters, PATH_BATCH, locked)
        return deleted
    }

    /** D1~D5 — 후보 = 만료 홀드가 있는 (회차, 사용자) · 좌석 문장 없음 */
    private fun chunkD(cursor: Cursor): Int? {
        // D1 후보 쌍 — 진행형 커서
        val pairs = jdbc.query(
            """
            SELECT DISTINCT schedule_id, user_id FROM seat_hold
             WHERE expires_at <= now() AND (schedule_id, user_id) > (?, ?)
             ORDER BY schedule_id, user_id LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.getLong(1) to rs.getLong(2) }, cursor.schedule, cursor.user, properties.expiryBatchSize,
        )
        if (pairs.isEmpty()) return null
        pairs.last().let { (s, u) -> cursor.schedule = s; cursor.user = u }
        // D2 쌍 잠금(규칙 ① — 정렬 · 기다림)
        val lockedPairs = lockPairs(pairs)
        // D3 MISSING(후보 − P) — C3b와 같다
        val missingIds = (pairs - lockedPairs.toSet()).flatMap { (s, u) ->
            val ids = jdbc.queryForList(
                "SELECT id FROM seat_hold WHERE schedule_id = ? AND user_id = ? AND expires_at <= now() ORDER BY id", Long::class.java, s, u,
            )
            repairMissing(s, u, ids)
            ids
        }
        // D4 삭제 — 잠근 쌍과 지울 행의 연결 = (schedule_id, user_id) 조인. P에 없는 쌍의 행은 이 문장이 지우지 않는다.
        // 확정 K3가 쥔 홀드 행(시각 역전 확정)은 기다린다 — 고리는 없다(확정은 쿼터 행을 잡지 않는다 · DS14-4)
        val deletedByPair = deleteForPairs(lockedPairs, null)
        val deleted = deletedByPair.values.sum() + deleteMissing(missingIds)
        // D5 = C5
        decrement(deletedByPair)
        return deleted
    }

    /** C3 · D2 — 후보 쌍 중 쿼터 행이 있는 쌍을 정렬 순서로 잠근다(기다림). 돌려받은 쌍 = P */
    private fun lockPairs(pairs: List<Pair<Long, Long>>): List<Pair<Long, Long>> = lockQuotaPairs(jdbc, pairs)

    /** C4 · D4 첫 문장 — 잠근 쌍의 만료 홀드를 지우고 쌍별 행 수를 돌려준다(규칙 ②) */
    private fun deleteForPairs(lockedPairs: List<Pair<Long, Long>>, seats: Array<Long>?): Map<Pair<Long, Long>, Int> {
        if (lockedPairs.isEmpty()) return emptyMap()
        val args = listOfNotNull<Any>(lockedPairs.map { it.first }.toTypedArray(), lockedPairs.map { it.second }.toTypedArray(), seats)
        val seatFilter = if (seats != null) "h.seat_id = ANY(?::bigint[]) AND " else "" // C4만 잠근 좌석 L로 한정한다
        return jdbc.query(
            """
            DELETE FROM seat_hold h USING unnest(?::bigint[], ?::bigint[]) AS p(schedule_id, user_id)
             WHERE ${seatFilter}h.expires_at <= now() AND h.schedule_id = p.schedule_id AND h.user_id = p.user_id
            RETURNING h.schedule_id, h.user_id
            """.trimIndent(),
            { rs, _ -> rs.getLong(1) to rs.getLong(2) }, *args.toTypedArray(),
        ).groupingBy { it }.eachCount()
    }

    /** C4 · D4 둘째 문장 — MISSING 쌍은 PairRepair에 넘긴 id로만 지운다. 넘긴 수보다 적게 지우면(그 사이 다른 트랜잭션이 지움 — 이미 덮은 cnt가 틀린다) 청크 롤백 */
    private fun deleteMissing(missingIds: List<Long>): Int {
        if (missingIds.isEmpty()) return 0
        val got = jdbc.queryForList("DELETE FROM seat_hold WHERE id = ANY(?::bigint[]) RETURNING id", Long::class.java, missingIds.toTypedArray()).size
        if (got != missingIds.size) throw MissingMismatch("MISSING 쌍 홀드 삭제 수 불일치: 넘긴 ${missingIds.size} · 지운 $got — 청크를 롤백한다")
        return got
    }

    private fun repairMissing(scheduleId: Long, userId: Long, ids: List<Long>) {
        if (ids.isEmpty()) return // 그 사이 지워졌다(쿼터 행도 없음) — 지울 것도 고칠 것도 없다
        meters.counter(MISSING_METRIC, "path", PATH_BATCH).increment()
        counters.repairMissingBeforeDelete(DriftSource.EXPIRY_REPAIR, scheduleId, userId, ids)
    }

    /** C5 · D5 — 쌍 정렬 순서로 가드 감소(방식별 문장 · 등록 — DS14-6). 0행이면 그 자리에서 복구 */
    private fun decrement(deletedByPair: Map<Pair<Long, Long>, Int>) {
        deletedByPair.keys.sortedWith(compareBy({ it.first }, { it.second })).forEach { key ->
            counters.decrementDeleted(DriftSource.EXPIRY_REPAIR, key.first, key.second, deletedByPair.getValue(key))
        }
    }

    /** `:missingIds` 삭제 수 불일치 — 청크 롤백 사유(무음 금지 · 지표 [MISSING_MISMATCH_METRIC]) */
    class MissingMismatch(message: String) : IllegalStateException(message)

    companion object {
        private val log = LoggerFactory.getLogger(LazyExpiryBatch::class.java)
        const val CHUNK_METRIC = "seat.hold.expiry.chunk"
        const val MISSING_MISMATCH_METRIC = "seat.hold.expiry.missing_mismatch"
        /** 틱당 청크 상한에 닿은 틱 수(CR14-2) */
        const val CHUNK_CAP_METRIC = "seat.hold.expiry.chunk_cap_reached"
    }
}

/**
 * 쌍 잠금(ADR-014 §2.4 규칙 ① — C3 · D2 · ADR-015 A′ B2 공용): 후보 쌍 중 쿼터 행이 있는 쌍을 정렬 순서로 잠근다(기다림). 돌려받은 쌍 = P(잠근 쌍).
 * 후보에 있는데 P에 없는 쌍 = MISSING(쿼터 행 없음).
 */
internal fun lockQuotaPairs(jdbc: JdbcTemplate, pairs: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
    if (pairs.isEmpty()) return emptyList()
    return jdbc.query(
        """
        SELECT q.schedule_id, q.user_id FROM user_hold_quota q
          JOIN unnest(?::bigint[], ?::bigint[]) AS c(schedule_id, user_id)
            ON q.schedule_id = c.schedule_id AND q.user_id = c.user_id
         ORDER BY q.schedule_id, q.user_id FOR UPDATE OF q
        """.trimIndent(),
        { rs, _ -> rs.getLong(1) to rs.getLong(2) }, pairs.map { it.first }.toTypedArray(), pairs.map { it.second }.toTypedArray(),
    )
}
