package com.jun.labs.seatreservation.service.impl.drift

import com.jun.labs.seatreservation.domain.SeatHold
import com.jun.labs.seatreservation.service.SeatHoldProperties
import org.slf4j.LoggerFactory
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
) {
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
            val updated = jdbc.update(
                "UPDATE user_hold_quota SET cnt = cnt - ? WHERE schedule_id = ? AND user_id = ? AND cnt >= ?",
                n, key.first, key.second, n,
            )
            if (updated == 0) repairPair(key.first, key.second, holds)
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
            val updated = jdbc.update(
                "UPDATE user_hold_quota SET cnt = cnt - ? WHERE schedule_id = ? AND user_id = ?",
                n, key.first, key.second,
            )
            // 카운터 행이 없으면 카운터가 이미 어긋난 것 — 조용히 넘기지 않는다(cnt < 0은 CHECK 제약이 막는다)
            // 한계: 어긋난 쌍 하나가 이 만료 배치 트랜잭션 전체를 실패시킨다 — 후보 A(expiry-repair)를 켜면 그 쌍만 복구한다(ADR-009)
            check(updated == 1) { "카운터 행 없음: 회차 ${key.first} 사용자 ${key.second}" }
        }
    }

    // ---- 틱 경계(ExpireHoldsService가 트랜잭션 밖에서 부른다) ----------------------------------------------

    fun beginTick() = tick.set(Tick())

    /** 만료 트랜잭션이 커밋됐다 — 복구 결과(같은 트랜잭션에 기록됨)를 지표·로그로. */
    fun afterCommit() {
        val current = tick.get() ?: return
        current.outcomes.forEach(recorder::count)
    }

    /** 만료 트랜잭션이 롤백됐다 — 복구도 기록도 함께 사라졌다. 시도한 쌍마다 독립 트랜잭션으로 skipped를 남긴다(다음 틱이 같은 홀드를 다시 고른다). */
    fun afterRollback(failure: Throwable) {
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
        log.warn("만료 틱 롤백({}) — 복구 시도 쌍 {}개를 skipped로 기록", reason, skipped.size)
    }

    fun endTick() = tick.remove()

    companion object {
        private val log = LoggerFactory.getLogger(ExpiryCounters::class.java)
    }
}
