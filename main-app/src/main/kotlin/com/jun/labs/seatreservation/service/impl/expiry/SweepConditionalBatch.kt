package com.jun.labs.seatreservation.service.impl.expiry

import com.jun.labs.seatreservation.service.impl.drift.DriftSource
import com.jun.labs.seatreservation.service.impl.drift.ExpiryCounters
import com.jun.labs.seatreservation.service.impl.expiry.LazyExpiryBatch.Companion.MISSING_MISMATCH_METRIC
import com.jun.labs.seatreservation.service.impl.expiry.LazyHoldProcess.Companion.DELETED_METRIC
import com.jun.labs.seatreservation.service.impl.expiry.LazyHoldProcess.Companion.MISSING_METRIC
import com.jun.labs.seatreservation.service.impl.expiry.LazyHoldProcess.Companion.REVERT_METRIC
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-015 A′ 배치(§3.6 B0~B5) — A 만료 배치의 최소 수정. `seat.confirm.mode ≠ current`일 때만 [com.jun.labs.seatreservation.service.impl.ExpireHoldsService]가
 * 틱 트랜잭션 몸통으로 부른다(Q15 — 확정 손잡이에 묶는다). 틱 하나 = 트랜잭션 하나 · 틱 경계 · 지표 · skipped 기록은 호출자 것 그대로.
 * - 시각 = `:now`(앱 Clock — A 규칙). DB now()를 쓰지 않는다(C·D 배치와 다른 점).
 * - 조건부 삭제(B3)가 만료를 다시 확인한다(홀드 행 자신의 열 — 확정 · 연장이 쥔 행을 기다린 뒤 EPQ로 최신 버전을 본다) → 그 사이 확정 · 연장된 홀드는 0행으로 지나가고 틱은 커밋(N5 · N10).
 * - 카운터 감소 = 실제로 지운 행(B3 RETURNING — decrementDeleted). 좌석은 실제로 지운 홀드의 좌석만 · HELD이고 다른 활성 홀드가 없을 때만 되돌린다(B5).
 * - 잠금 순서: 쿼터 행(B2 — 정렬) → 홀드 행(B3) → 좌석 행(B5). 측정 주입(afterExpiryDecrement)은 이 경로에 없다(기동 검사 규칙 5가 함께 켜기를 거부).
 */
@Component
class SweepConditionalBatch(
    private val jdbc: JdbcTemplate,
    private val counters: ExpiryCounters,
    private val meters: MeterRegistry,
) {
    /**
     * 바깥(틱) 트랜잭션 안에서 부른다 — 지운 홀드 행 수 + 지표 기록. 지표(deleted · revert · extension_lost)는 호출자가 틱 커밋 뒤에
     * [Outcome.recordAfterCommit]으로 센다(R15-16 — 롤백된 틱은 세지 않는다 · C·D 배치가 청크 커밋 뒤에 세는 것과 같다).
     */
    fun expireInTx(now: Instant): Outcome {
        val ts = Timestamp.from(now)
        // B1 후보 쌍(잠금 없음 · idx_seat_hold_expires_at)
        val pairs = jdbc.query(
            "SELECT DISTINCT schedule_id, user_id FROM seat_hold WHERE expires_at <= ? ORDER BY schedule_id, user_id",
            { rs, _ -> rs.getLong(1) to rs.getLong(2) }, ts,
        )
        if (pairs.isEmpty()) return Outcome.NONE
        // B2 쌍 잠금(정렬 · 기다림) — 잠근 쌍 P
        val lockedPairs = lockQuotaPairs(jdbc, pairs)
        // B2b MISSING(후보 − P): 지울 id를 읽어 지우기 앞에서 복구(D3과 같다 · 시각만 :now) — expiry-repair=false면 check로 틱 실패(옛 경로와 같은 뜻)
        val missingIds = (pairs - lockedPairs.toSet()).flatMap { (s, u) ->
            val ids = jdbc.queryForList(
                "SELECT id FROM seat_hold WHERE schedule_id = ? AND user_id = ? AND expires_at <= ? ORDER BY id", Long::class.java, s, u, ts,
            )
            if (ids.isNotEmpty()) {
                meters.counter(MISSING_METRIC, "path", PATH_SWEEP).increment()
                counters.repairMissingBeforeDelete(DriftSource.EXPIRY_REPAIR, s, u, ids)
            }
            ids
        }
        // B3 조건부 삭제 = 만료 재확인(잠근 쌍 P로 한정)
        val deleted = mutableListOf<Deleted>()
        if (lockedPairs.isNotEmpty()) {
            deleted += jdbc.query(
                """
                DELETE FROM seat_hold h USING unnest(?::bigint[], ?::bigint[]) AS p(schedule_id, user_id)
                 WHERE h.expires_at <= ? AND h.schedule_id = p.schedule_id AND h.user_id = p.user_id
                RETURNING h.seat_id, h.schedule_id, h.user_id, h.extended_at IS NOT NULL AS extended, h.expires_at > ? AS lost
                """.trimIndent(),
                DELETED_ROW, lockedPairs.map { it.first }.toTypedArray(), lockedPairs.map { it.second }.toTypedArray(), ts, ts,
            )
        }
        val byPair = deleted.groupingBy { it.scheduleId to it.userId }.eachCount()
        // B3b MISSING 쌍 — PairRepair에 넘긴 id로만 · 만료 재확인. 지운 수 ≠ 넘긴 수면 틱 롤백(이미 덮은 cnt가 틀린다 — LazyExpiryBatch.deleteMissing과 같다)
        if (missingIds.isNotEmpty()) {
            val got = jdbc.query(
                """
                DELETE FROM seat_hold WHERE id = ANY(?::bigint[]) AND expires_at <= ?
                RETURNING seat_id, schedule_id, user_id, extended_at IS NOT NULL AS extended, expires_at > ? AS lost
                """.trimIndent(),
                DELETED_ROW, missingIds.toTypedArray(), ts, ts,
            )
            if (got.size != missingIds.size) {
                meters.counter(MISSING_MISMATCH_METRIC).increment()
                throw LazyExpiryBatch.MissingMismatch("A′ MISSING 쌍 홀드 삭제 수 불일치: 넘긴 ${missingIds.size} · 지운 ${got.size} — 틱을 롤백한다")
            }
            deleted += got
        }
        // B4 감소 = B3이 돌려준 쌍별 행 수(P의 쌍만 · 정렬 · 같은 트랜잭션 — MISSING 쌍은 B2b 복구가 감소 예정으로 덮었다)
        byPair.keys.sortedWith(compareBy({ it.first }, { it.second })).forEach { key ->
            counters.decrementDeleted(DriftSource.EXPIRY_REPAIR, key.first, key.second, byPair.getValue(key))
        }
        if (deleted.isEmpty()) return Outcome.NONE
        val revert = revertSeats(deleted.map { it.seatId }.distinct(), ts)
        val extended = deleted.count { it.extended }
        val total = deleted.size
        // A′의 WHERE가 expires_at <= :now라 lost는 구조적으로 0 — 0이 아니면 창이 열린 것이다(무음 아님). 로그는 사건 기록이라 그 자리에서 남긴다(지표만 커밋 뒤)
        val lost = deleted.count { it.lost }
        if (lost > 0) log.error("A′ 배치가 아직 만료 전인 홀드 {}개를 지웠다(설계상 0 — extension_lost · 이 틱이 롤백되면 삭제도 되돌려지고 지표는 세지 않는다)", lost)
        return Outcome(total) {
            if (extended > 0) meters.counter(DELETED_METRIC, "path", PATH_SWEEP, "extended", "true").increment(extended.toDouble())
            if (total > extended) meters.counter(DELETED_METRIC, "path", PATH_SWEEP, "extended", "false").increment((total - extended).toDouble())
            revert.forEach { (result, n) -> meters.counter(REVERT_METRIC, "path", PATH_SWEEP, "result", result).increment(n.toDouble()) }
            if (lost > 0) meters.counter(EXTENSION_LOST_METRIC).increment(lost.toDouble())
        }
    }

    /** 틱 몸통 결과 — [deleted] = 지운 홀드 행 수 · [recordAfterCommit] = 틱 커밋 뒤에 호출자가 부르는 지표 기록(롤백이면 부르지 않는다) */
    class Outcome(val deleted: Int, val recordAfterCommit: () -> Unit) {
        companion object {
            val NONE = Outcome(0) {}
        }
    }

    /**
     * B5 좌석 되돌림 — 실제로 지운 홀드의 좌석만 · HELD일 때만 · 다른 활성 홀드가 없을 때만. 돌려받지 못한 좌석은 잠금 없는 진단 한 문장으로 갈래만 고른다
     * (not_held · kept_active — 둘 다 A 불변식 ② 아래 설계상 0 · 0이 아니면 WARN + 지표 · 틱은 커밋).
     * 돌려주는 값 = 갈래별 좌석 수(`seat.hold.expiry.revert{result}` — 세는 것은 호출자가 커밋 뒤에 · reverted는 0이어도 센다).
     */
    private fun revertSeats(seats: List<Long>, ts: Timestamp): Map<String, Int> {
        val ids = seats.toTypedArray()
        val reverted = jdbc.queryForList(
            """
            UPDATE product_seat st SET status = 'AVAILABLE'
             WHERE st.id = ANY(?::bigint[]) AND st.status = 'HELD'
               AND NOT EXISTS (SELECT 1 FROM seat_hold o WHERE o.seat_id = st.id AND o.expires_at > ?)
            RETURNING st.id
            """.trimIndent(),
            Long::class.java, ids, ts,
        ).toSet()
        val counts = linkedMapOf("reverted" to reverted.size)
        val rest = seats.filterNot { it in reverted }
        if (rest.isEmpty()) return counts
        val statuses = jdbc.query(
            "SELECT id, status FROM product_seat WHERE id = ANY(?::bigint[])",
            { rs, _ -> rs.getLong(1) to rs.getString(2) }, rest.toTypedArray(),
        ).toMap()
        val notHeld = rest.filter { statuses[it] != "HELD" }
        val keptActive = rest - notHeld.toSet()
        if (notHeld.isNotEmpty()) counts["not_held"] = notHeld.size
        if (keptActive.isNotEmpty()) counts["kept_active"] = keptActive.size
        log.warn("A′ 배치가 지운 홀드의 좌석 중 되돌리지 않은 좌석이 있다(A 불변식 ② 아래 설계상 0) — not_held={} kept_active={}", notHeld, keptActive)
        return counts
    }

    private class Deleted(val seatId: Long, val scheduleId: Long, val userId: Long, val extended: Boolean, val lost: Boolean)

    companion object {
        private val log = LoggerFactory.getLogger(SweepConditionalBatch::class.java)
        const val PATH_SWEEP = "sweep"
        /** ④ × 배치 — 아직 만료 전인 홀드를 배치가 지운 수(A′에서 구조적 0 · §3.4) */
        const val EXTENSION_LOST_METRIC = "seat.confirm.extension_lost"
        private val DELETED_ROW = org.springframework.jdbc.core.RowMapper { rs, _ ->
            Deleted(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBoolean(4), rs.getBoolean(5))
        }
    }
}
