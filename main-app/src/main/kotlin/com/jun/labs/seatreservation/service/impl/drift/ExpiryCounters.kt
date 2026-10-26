package com.jun.labs.seatreservation.service.impl.drift

import com.jun.labs.seatreservation.domain.SeatHold
import com.jun.labs.seatreservation.service.SeatHoldProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.TransactionDefinition

/**
 * 만료 배치가 실제로 지운 홀드만큼 (회차, 사용자)별 카운터를 내린다 — counter·counter-upsert·counter-upsert-precheck 공용.
 *
 * - `seat.hold.counter-drift.expiry-repair=false`(기본): ADR-008까지와 같다 — 쌍마다 `cnt = cnt - n`, 행이 없으면 실패(배치 전체 롤백), 음수는 CHECK가 막는다.
 * - `true`(후보 A — ADR-009): 쌍을 정렬하고 가드 감소(`cnt >= n`)를 한다. 0행인 쌍만 같은 트랜잭션에서 실제 값으로 복구·기록하고([PairRepair]),
 *   나머지 쌍의 만료는 그대로 진행한다. 만료 트랜잭션 안이라 시간 제한을 두지 않는다(설계 §1a). 그래도 틱이 롤백되면(40P01 등)
 *   이 틱에 복구를 시도한 쌍마다 **독립 트랜잭션**으로 skipped(reason = SQLSTATE)를 쓴다 — 틱 단위 수집은 [beginTick]·[afterCommit]·[afterRollback].
 *   savepoint로 실패한 쌍만 건너뛰는 안은 기각(그 쌍의 홀드는 flush로 지워지는데 cnt를 못 고쳐 어긋남 모양을 조용히 바꾼다 — 설계 §1a).
 */
@Component
class ExpiryCounters(
    private val jdbc: JdbcTemplate,
    private val properties: SeatHoldProperties,
    private val repair: PairRepair,
    private val recorder: CounterDriftRecorder,
    tx: TransactionTemplate,
    private val changes: MemoryChangedPairs,
    /** 측정 전용 앱 경로 주입(ADR-012 §4.2.7 — loadtest 프로필에만 빈) */
    private val inject: ObjectProvider<AppPathInjectHook>,
) {
    /** ADR-012 ⓒ 3(method=stmt) — 감소 문장이 `updated_at = now()`를 함께 쓴다. 아니면 지금 문자열 그대로 */
    private val stmt = properties.counterDrift.reconcile.incremental.method == IncrementalMethod.STMT
    private val guardSql = if (stmt) GUARD_STMT_SQL else GUARD_SQL
    private val legacySql = if (stmt) LEGACY_STMT_SQL else LEGACY_SQL

    /** skipped 기록용 — 롤백된 만료 트랜잭션과 독립(REQUIRES_NEW: 호출 시점에 바깥 트랜잭션이 없어도 같은 동작). */
    private val independentTx = TransactionTemplate(tx.transactionManager!!).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** 이 틱에 복구를 시도한 쌍(탐지 때 본 값) · 이 틱의 복구 결과(커밋 뒤 지표). 스레드별 — 만료 틱은 한 스레드에서 돈다. */
    private class Tick {
        val attempted = mutableListOf<DriftOutcome>()
        val outcomes = mutableListOf<DriftOutcome>()
    }
    private val tick = ThreadLocal<Tick>()

    fun decrement(expired: List<SeatHold>) {
        if (!properties.counterDrift.expiryRepair) return legacyDecrement(expired)
        val byPair = expired.groupBy { it.scheduleId to it.userId }
        // 정렬: 같은 쿼터 행들을 다른 트랜잭션(다른 틱·대조 복구)과 같은 순서로 잠근다
        byPair.keys.sortedWith(compareBy({ it.first }, { it.second })).forEach { key ->
            val holds = byPair.getValue(key)
            val n = holds.size
            val updated = jdbc.update(guardSql, n, key.first, key.second, n)
            if (updated == 0) repairPair(key.first, key.second, holds)
            else {
                changes.changed(key.first, key.second) // ⓐ 등록(커밋 뒤) — 복구 갈래는 PairRepair가 등록한다
                inject.ifAvailable { it.afterExpiryDecrement(key.first, key.second) }
            }
        }
    }

    private fun repairPair(scheduleId: Long, userId: Long, holds: List<SeatHold>) {
        val ids = holds.map { it.id!! }
        // 탐지 때 본 값(잠금 없음) — 틱이 롤백되면 skipped 기록에 쓴다. 실제 값은 이번에 지울 홀드를 뺀 값
        val observed = jdbc.queryForList(
            "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, scheduleId, userId,
        ).singleOrNull()
        val observedActual = PairRepair.actualOf(jdbc, scheduleId, userId, ids)
        val expected = observedActual + holds.size
        val kind = when {
            observed == null -> DriftKind.MISSING
            observed > expected -> DriftKind.ABOVE
            else -> DriftKind.BELOW // 가드(cnt >= n)가 실패했다 = cnt가 너무 작다
        }
        val current = tick.get()
        current?.attempted?.add(
            DriftOutcome(DriftSource.EXPIRY_REPAIR, scheduleId, userId, kind, observed, observedActual, DriftAction.SKIPPED),
        )
        val outcome = repair.repairInCurrentTx(
            DriftSource.EXPIRY_REPAIR, scheduleId, userId, kind, excludeHoldIds = ids, pendingDecrement = holds.size,
        )
        if (current != null) current.outcomes.add(outcome) else recorder.count(outcome) // 틱 밖 호출(직접 호출)은 바로 센다
    }

    /** ADR-008까지의 감소 — 키를 끄면 이 경로 그대로다(순서·실패 방식 포함). */
    private fun legacyDecrement(expired: List<SeatHold>) {
        // 실제로 지운 홀드에서 센다 — 만료 대상 조회를 따로 하면 그 사이 확정된 홀드까지 내릴 수 있다
        expired.groupingBy { it.scheduleId to it.userId }.eachCount().forEach { (key, n) ->
            val updated = jdbc.update(legacySql, n, key.first, key.second)
            // 카운터 행이 없으면 카운터가 이미 어긋난 것 — 조용히 넘기지 않는다(cnt < 0은 CHECK 제약이 막는다)
            // 한계: 어긋난 쌍 하나가 이 만료 배치 트랜잭션 전체를 실패시킨다 — 후보 A(expiry-repair)를 켜면 그 쌍만 복구한다(ADR-009)
            check(updated == 1) { "카운터 행 없음: 회차 ${key.first} 사용자 ${key.second}" }
            changes.changed(key.first, key.second)
        }
    }

    /**
     * B(counter-trigger) 만료 선잠금이 열린 회차에서 쿼터 행 없음([observedCnt] = null → missing) 또는 cnt < 지울 홀드 수(below)를 봤다 —
     * flush 때 트리거가 SR001·23514로 틱을 롤백한다(엄격, 사용자 결정). 그 쌍을 이 틱의 시도 목록에 올려 롤백 시 skipped(reason = SQLSTATE)로
     * 남긴다(무음 금지 — L1-17 · L2-5). 대조 C·F가 복구하면 다음 틱이 다시 돈다.
     */
    fun noteTriggerAttempt(scheduleId: Long, userId: Long, observedCnt: Int?) {
        val actual = PairRepair.actualOf(jdbc, scheduleId, userId)
        val kind = if (observedCnt == null) DriftKind.MISSING else DriftKind.BELOW
        tick.get()?.attempted?.add(
            DriftOutcome(DriftSource.EXPIRY_TRIGGER, scheduleId, userId, kind, observedCnt, actual, DriftAction.SKIPPED),
        )
    }

    // ---- ADR-014 B·C·D — 행을 지운 뒤의 감소(회수 H5 · 배치 C5·D5) · 지우기 앞의 MISSING 복구(H3 · C3b · D3) ------------------------

    /**
     * ADR-014 §2.4 규칙 ②·③: 이 트랜잭션의 `DELETE … RETURNING`이 실제로 지운 [n]행만큼 쌍의 cnt를 내린다. 호출 전에 그 쌍의 쿼터 행을 잠가 둔다(규칙 ①).
     * 문장·등록은 A([decrement])와 같다(선검증 DS14-6 — stmt면 `updated_at = now()` · 1행이면 ⓐ 등록). 측정 주입(afterExpiryDecrement)은 부르지 않는다
     * (B·C·D와 주입은 기동 거부 — Q13). 가드가 0행이면 그 자리에서 복구한다 — 행을 이미 지웠으므로 excludeHoldIds는 비우고 pendingDecrement = [n](DS14-7).
     * expiry-repair=false면 A의 옛 경로처럼 실패한다(트랜잭션 롤백 — [legacyDecrement]와 같은 뜻).
     */
    fun decrementDeleted(source: String, scheduleId: Long, userId: Long, n: Int) {
        if (n == 0) return
        if (!properties.counterDrift.expiryRepair) {
            check(jdbc.update(legacySql, n, scheduleId, userId) == 1) { "카운터 행 없음: 회차 $scheduleId 사용자 $userId" }
            changes.changed(scheduleId, userId)
            return
        }
        if (jdbc.update(guardSql, n, scheduleId, userId, n) == 1) {
            changes.changed(scheduleId, userId) // ⓐ 등록(커밋 뒤) — 복구 갈래는 PairRepair가 등록한다
            return
        }
        val observed = jdbc.queryForList(
            "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, scheduleId, userId,
        ).singleOrNull()
        val actual = PairRepair.actualOf(jdbc, scheduleId, userId)
        val kind = when {
            observed == null -> DriftKind.MISSING
            observed > actual + n -> DriftKind.ABOVE
            else -> DriftKind.BELOW
        }
        tick.get()?.attempted?.add(DriftOutcome(source, scheduleId, userId, kind, observed, actual, DriftAction.SKIPPED))
        countCommitted(repair.repairInCurrentTx(source, scheduleId, userId, kind, pendingDecrement = n))
    }

    /**
     * ADR-014 §2.4 · §3.1 H3(DS14-8) · §3.4 C3b·D3(DS14-5): 쿼터 행이 없는 쌍(MISSING)은 홀드를 지우기 **앞에서** 복구한다 — PairRepair가 행을 만들고 잠가
     * (INSERT한 행은 커밋까지 이 트랜잭션이 쥔다) 규칙 ①이 MISSING 쌍에도 서게 한다(행 없이 지우면 그 사이 같은 사용자의 upsert가 만든 cnt를 감소가 내려 무음 below).
     * [deletingHoldIds] = 이 트랜잭션이 곧 지울 홀드 — 배치는 실제 값에서 빼고 그 수를 감소 예정으로 넘긴다(그 쌍은 C5·D5 감소를 하지 않는다 — A와 같다).
     * 회수(H3)는 빈 목록 — 지울 행까지 센 값으로 덮고 감소는 H5가 한다. expiry-repair=false면 A의 옛 경로처럼 실패한다.
     */
    fun repairMissingBeforeDelete(source: String, scheduleId: Long, userId: Long, deletingHoldIds: List<Long>) {
        check(properties.counterDrift.expiryRepair) { "카운터 행 없음: 회차 $scheduleId 사용자 $userId" }
        val actual = PairRepair.actualOf(jdbc, scheduleId, userId, deletingHoldIds)
        tick.get()?.attempted?.add(DriftOutcome(source, scheduleId, userId, DriftKind.MISSING, null, actual, DriftAction.SKIPPED))
        countCommitted(
            repair.repairInCurrentTx(
                source, scheduleId, userId, DriftKind.MISSING, excludeHoldIds = deletingHoldIds, pendingDecrement = deletingHoldIds.size,
            ),
        )
    }

    /**
     * 복구 결과는 커밋된 것만 센다 — 틱 안(C·D 배치 청크 · B·C·D 선점 — 둘 다 트랜잭션 밖에서 [beginTick]으로 감싼다)이면 커밋 뒤 [afterCommit]이 센다.
     * 회수 복구는 선점이 뒤에서 실패하면(NOWAIT 패배 · 매수 초과 등) 함께 롤백된다(규칙 ③) — [afterRollback]이 시도한 쌍을 독립 트랜잭션으로 skipped 기록한다
     * (A·C·D 틱 롤백과 같은 처리 — 코드 리뷰 CR14-5). 그 쌍은 다음 선점·배치·대조가 다시 본다. 틱 밖(직접 호출)은 바로 센다.
     */
    private fun countCommitted(outcome: DriftOutcome) {
        val current = tick.get() ?: return recorder.count(outcome)
        current.outcomes.add(outcome)
    }

    // ---- 틱 경계(트랜잭션 밖에서 부른다 — ExpireHoldsService(A) · LazyExpiryBatch(C·D 청크) · LazyHoldProcess(B·C·D 선점 — CR14-5)) ----------

    fun beginTick() = tick.set(Tick())

    /** 만료 트랜잭션이 커밋됐다 — 복구 결과(같은 트랜잭션에 기록됨)를 지표·로그로. */
    fun afterCommit() {
        val current = tick.get() ?: return
        current.outcomes.forEach(recorder::count)
    }

    /**
     * 만료 트랜잭션(또는 회수를 담은 선점 트랜잭션 — [what])이 롤백됐다 — 복구도 기록도 함께 사라졌다.
     * 시도한 쌍마다 독립 트랜잭션으로 skipped를 남긴다(다음 틱 · 다음 선점이 같은 홀드를 다시 고른다).
     */
    fun afterRollback(failure: Throwable, what: String = "만료 틱") {
        val current = tick.get() ?: return
        if (current.attempted.isEmpty()) return
        val reason = skipReason(failure)
        val skipped = current.attempted.map { it.copy(reason = reason) }
        try {
            independentTx.executeWithoutResult { skipped.forEach(recorder::insert) }
            skipped.forEach(recorder::count)
        } catch (e: Exception) {
            skipped.forEach { recorder.recordFailed(it, e) }
        }
        log.warn("{} 롤백({}) — 복구 시도 쌍 {}개를 skipped로 기록", what, reason, skipped.size)
    }

    fun endTick() = tick.remove()

    companion object {
        private val log = LoggerFactory.getLogger(ExpiryCounters::class.java)
        /** ADR-014 선점 회수(H3~H5)의 복구 기록 출처(`counter_drift_log.source`) — C·D 배치는 A와 같은 [DriftSource.EXPIRY_REPAIR] */
        const val SOURCE_HOLD_RECLAIM = "hold-reclaim"
        private const val GUARD_SQL = "UPDATE user_hold_quota SET cnt = cnt - ? WHERE schedule_id = ? AND user_id = ? AND cnt >= ?"
        private const val GUARD_STMT_SQL = "UPDATE user_hold_quota SET cnt = cnt - ?, updated_at = now() WHERE schedule_id = ? AND user_id = ? AND cnt >= ?"
        private const val LEGACY_SQL = "UPDATE user_hold_quota SET cnt = cnt - ? WHERE schedule_id = ? AND user_id = ?"
        private const val LEGACY_STMT_SQL = "UPDATE user_hold_quota SET cnt = cnt - ?, updated_at = now() WHERE schedule_id = ? AND user_id = ?"
    }
}
