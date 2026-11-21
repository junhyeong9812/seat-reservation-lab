package com.jun.labs.seatreservation.service.impl.holdgroup

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.impl.drift.ExpiryCounters
import com.jun.labs.seatreservation.service.impl.expiry.lockQuotaPairs
import com.jun.labs.seatreservation.service.impl.payment.PaymentIntents
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock

/**
 * ADR-018 설계-3 해제 — 1석 홀드(`DELETE /api/holds/{holdId}`)와 묶음 홀드 전체(`DELETE /api/hold-groups/{groupId}`).
 * 홀드를 지우고 좌석을 AVAILABLE로 되돌리고 카운터를 지운 행 수만큼 내린다(만료됐지만 아직 배치가 지우지 않은 홀드도 — 결과는 만료 배치와 같다).
 * 잠금 순서는 만료 배치(SweepConditionalBatch)와 같은 카운터 행 → 홀드 행이다(반대면 만료된 홀드를 두고 배치와 교착):
 * 1. 대상 홀드를 잠금 없이 읽어 회차 · 소유자(없음 404 · 남의 것 403 · 1석 API에 묶음 홀드면 409 HOLD_IN_GROUP)
 * 2. 그 (회차, 사용자) 카운터 행 `FOR UPDATE` — 행이 없으면(MISSING) 지울 홀드 id를 잠금 없이 읽어 홀드 잠금 앞에서 복구한다(배치 B2b와 같다)
 * 3. 홀드 행 `FOR UPDATE`(id 순) · 소유자 재확인(사라졌거나 복구에 넘긴 집합과 다르면 404)
 * 4. 새 문장으로 결제 의도 확인(있으면 409 PAYMENT_STARTED — 결제 시작은 의도를 넣을 때 홀드 행을 FOR KEY SHARE로 읽어 3과 직렬화된다)
 * 5. `DELETE … RETURNING` 행 수만큼 카운터 감소(ExpiryCounters.decrementDeleted — MISSING 쌍은 2의 복구가 감소 예정으로 덮었다) · 좌석 되돌림.
 * 만료 배치 · 확정도 `DELETE … RETURNING`으로 지우므로 같은 홀드를 두 경로가 지워도 행을 돌려받은 쪽만 카운터를 내린다(G5).
 * 결제 시작이 있는 확정 모드(conditional · conditional-extend — 기동 검사가 기본 조합을 강제)에서만 동작하고, 그 밖의 구성에서는 404다([assertSupported]) —
 * 위 순서는 앱이 카운터를 내리고 좌석 행 잠금이 홀드 생성을 지키는 조합을 전제한다(counter-trigger는 트리거가 또 내리고 · 카운터 없는 방식은 행이 없다).
 */
@Service
class HoldReleaseService(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val counters: ExpiryCounters,
    private val intents: PaymentIntents,
    private val groups: HoldGroupGuard,
    private val clock: Clock,
    confirmProperties: SeatConfirmProperties,
) {
    /**
     * 결제 시작이 있는 확정 모드(conditional · conditional-extend)에서만 '결제 중 해제 409'가 성립한다 —
     * current가 아닌 확정 모드는 ADR-015 기동 검사(규칙 1 · 2)가 sweep · pessimistic-nowait · counter-upsert-precheck를 이미 강제한다.
     */
    private val supported = confirmProperties.mode.paymentStartEnabled

    /** 기본 조합이 아니면 해제 경로가 없는 것으로 답한다(404 — 묶음 API를 끈 것과 같은 취급). 아무 문장도 보내지 않는다 */
    private fun assertSupported() {
        if (!supported) throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
    }

    fun releaseHold(holdId: Long, userId: Long) {
        assertSupported()
        inTick { releaseHoldInTx(holdId, userId) }
    }

    fun releaseGroup(groupId: Long, userId: Long) {
        // 조합 검사 없음 — 묶음 API는 seat.hold-group.enabled=true일 때만 열리고, 그때 기동 검사가 conditional-extend(→ 기본 조합)를 강제한다
        inTick { releaseGroupInTx(groupId, userId) }
    }

    private fun releaseHoldInTx(holdId: Long, userId: Long) {
        val groupCol = if (groups.available) "group_id" else "NULL::bigint"
        val row = jdbc.query(
            "SELECT schedule_id, user_id, $groupCol FROM seat_hold WHERE id = ?",
            { rs, _ -> Target(rs.getLong(1), rs.getLong(2), rs.getObject(3) as Long?) }, holdId,
        ).singleOrNull() ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if (row.userId != userId) throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        if (row.groupId != null) throw SeatReservationException(ErrorCode.HOLD_IN_GROUP)
        val quotaLocked = lockQuotaPairs(jdbc, listOf(row.scheduleId to userId)).isNotEmpty()
        if (!quotaLocked) repairMissing(row.scheduleId, userId, listOf(holdId))
        val locked = jdbc.query("SELECT id, user_id FROM seat_hold WHERE id = ? FOR UPDATE", LOCKED_ROW, holdId)
        assertLocked(locked, userId, if (quotaLocked) null else listOf(holdId))
        val started = intents.available &&
            jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM payment_intent WHERE hold_id = ?)", Boolean::class.java, holdId) == true
        if (started) throw SeatReservationException(ErrorCode.PAYMENT_STARTED)
        delete(row.scheduleId, userId, locked.map { it.first }, quotaLocked)
    }

    private fun releaseGroupInTx(groupId: Long, userId: Long) {
        val row = jdbc.query(
            "SELECT schedule_id, user_id FROM seat_hold WHERE group_id = ? ORDER BY id LIMIT 1",
            { rs, _ -> Target(rs.getLong(1), rs.getLong(2), groupId) }, groupId,
        ).singleOrNull() ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if (row.userId != userId) throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        val quotaLocked = lockQuotaPairs(jdbc, listOf(row.scheduleId to userId)).isNotEmpty()
        val repaired = if (quotaLocked) null else {
            jdbc.queryForList("SELECT id FROM seat_hold WHERE group_id = ? ORDER BY id", Long::class.java, groupId)
                .also { repairMissing(row.scheduleId, userId, it) }
        }
        val locked = jdbc.query("SELECT id, user_id FROM seat_hold WHERE group_id = ? ORDER BY id FOR UPDATE", LOCKED_ROW, groupId)
        assertLocked(locked, userId, repaired)
        val started = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM payment_intent WHERE group_id = ?)", Boolean::class.java, groupId) == true
        if (started) throw SeatReservationException(ErrorCode.PAYMENT_STARTED)
        delete(row.scheduleId, userId, locked.map { it.first }, quotaLocked)
    }

    /**
     * 카운터 행이 없는 쌍(MISSING) — 만료 배치 B2b와 같은 순서로 홀드 행을 잠그기 **앞에서** 복구한다(복구가 쿼터 행을 만들어 잠근다 → 그다음 홀드 잠금).
     * 순서를 뒤집으면 만료된 홀드의 해제와 배치(쿼터 → 홀드)가 서로를 기다려 교착한다. [holdIds] = 잠금 없이 읽은 지울 홀드(id 순) — 감소 예정으로 넘긴다.
     */
    private fun repairMissing(scheduleId: Long, userId: Long, holdIds: List<Long>) {
        if (holdIds.isEmpty()) throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        counters.repairMissingBeforeDelete(SOURCE_HOLD_RELEASE, scheduleId, userId, holdIds)
    }

    /**
     * 잠근 홀드 확인 — 없음 404 · 남의 것 403. [repaired](MISSING 복구에 넘긴 id)가 있으면 잠근 집합이 같아야 한다 —
     * 그 사이 다른 경로(배치 · 확정)가 지웠으면 404로 롤백한다(복구도 함께 되돌려지고 시도한 쌍은 skipped로 남는다).
     */
    private fun assertLocked(locked: List<Pair<Long, Long>>, userId: Long, repaired: List<Long>?) {
        if (locked.isEmpty()) throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if (locked.any { it.second != userId }) throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        if (repaired != null && locked.map { it.first } != repaired) throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
    }

    /** 잠근 홀드 행만 지운다 — 카운터 행이 없던 쌍은 앞에서 복구했고(감소 예정으로 덮음) 감소는 하지 않는다(만료 배치 B2b · B4와 같다) */
    private fun delete(scheduleId: Long, userId: Long, holdIds: List<Long>, quotaLocked: Boolean) {
        val seats = jdbc.queryForList(
            "DELETE FROM seat_hold WHERE id = ANY(?::bigint[]) RETURNING seat_id", Long::class.java, holdIds.toTypedArray(),
        )
        check(seats.size == holdIds.size) { "해제: 잠근 홀드 ${holdIds.size}행 중 ${seats.size}행만 지웠다(설계상 같다) — 롤백한다" }
        if (quotaLocked) counters.decrementDeleted(SOURCE_HOLD_RELEASE, scheduleId, userId, seats.size)
        // 좌석 되돌림 — HELD이고 다른 활성 홀드가 없을 때만(만료 배치 B5와 같은 조건)
        jdbc.update(
            """
            UPDATE product_seat st SET status = 'AVAILABLE'
             WHERE st.id = ANY(?::bigint[]) AND st.status = 'HELD'
               AND NOT EXISTS (SELECT 1 FROM seat_hold o WHERE o.seat_id = st.id AND o.expires_at > ?)
            """.trimIndent(),
            seats.toTypedArray(), Timestamp.from(clock.instant()),
        )
    }

    /** 카운터 복구 기록의 커밋 · 롤백 처리는 만료 틱과 같은 경계로(롤백되면 시도한 쌍을 skipped로 남긴다) */
    private fun inTick(block: () -> Unit) {
        counters.beginTick()
        try {
            try {
                tx.executeWithoutResult { block() }
            } catch (e: Throwable) {
                counters.afterRollback(e, "해제") // 거부(4xx)는 복구 앞에서 나므로 시도한 쌍이 없어 아무것도 쓰지 않는다
                throw e
            }
            counters.afterCommit()
        } finally {
            counters.endTick()
        }
    }

    private class Target(val scheduleId: Long, val userId: Long, val groupId: Long?)

    companion object {
        /** 해제의 카운터 복구 기록 출처(`counter_drift_log.source`) */
        const val SOURCE_HOLD_RELEASE = "hold-release"
        private val LOCKED_ROW = org.springframework.jdbc.core.RowMapper { rs, _ -> rs.getLong(1) to rs.getLong(2) }
    }
}
