package com.jun.labs.seatreservation.service.impl.drift

import com.jun.labs.seatreservation.service.impl.hold.sqlStateOf
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock

/** 어긋남 모양 — 탐지 때 본 (cnt, 실제 값) 기준. */
enum class DriftKind(val value: String) { MISSING("missing"), BELOW("below"), ABOVE("above") }

/** 쌍 처리 결과. repaired = cnt를 실제 값으로 덮었다 · false-positive = 잠근 뒤 다시 세니 맞았다 · skipped = 복구 트랜잭션이 실패했다. */
enum class DriftAction(val value: String) { REPAIRED("repaired"), FALSE_POSITIVE("false-positive"), SKIPPED("skipped") }

/** `counter_drift_log` 1행과 같은 내용. */
data class DriftOutcome(
    val source: String,
    val scheduleId: Long,
    val userId: Long,
    val kind: DriftKind,
    val cntBefore: Int?,
    val actual: Int,
    val action: DriftAction,
    val reason: String? = null,
)

/** 기록 출처(`counter_drift_log.source`). */
object DriftSource {
    const val EXPIRY_REPAIR = "expiry-repair"
    /** B(counter-trigger) 만료 선잠금이 열린 회차의 쿼터 행 없음을 본 쌍 — 틱이 SR001로 롤백되면 skipped로 남는다 */
    const val EXPIRY_TRIGGER = "expiry-trigger"
    const val RECONCILE_APP = "reconcile-app"
    const val RECONCILE_PROC = "reconcile-proc"
}

/** skipped 사유 — SQLSTATE, 없으면 예외 종류(열 폭 40). main-app의 만료 A(ExpiryCounters)도 부르므로 `public`(ADR-012 §4.2.1). */
fun skipReason(e: Throwable): String = (sqlStateOf(e) ?: e::class.simpleName ?: "unknown").take(40)

/**
 * 쌍 단위 복구(명세 §2 복구 불변식 · 설계 §1): **현재 트랜잭션 안에서** 쿼터 행을 `FOR UPDATE`로 잠근 뒤(행이 없으면 cnt 0으로 만들고 잠근다),
 * 새 문장 하나로 실제 값(홀드 + CONFIRMED 예약)을 세어 다르면 덮어쓰고, 같은 트랜잭션에서 `counter_drift_log` 1행을 남긴다.
 * 홀드·예약은 바꾸지 않는다 — 쓰는 테이블은 쿼터 + 기록뿐이다. 트랜잭션 경계·시간 제한은 호출자가 정한다(A = 만료 트랜잭션, C·F = 쌍마다 트랜잭션 + lock_timeout).
 * 지표는 여기서 올리지 않는다 — 커밋된 뒤에만 올리도록 호출자가 [CounterDriftRecorder.count]를 부른다(롤백된 복구를 세지 않게).
 */
@Component
class PairRepair(
    private val jdbc: JdbcTemplate,
    private val recorder: CounterDriftRecorder,
) {
    /**
     * @param excludeHoldIds 실제 값에서 뺄 홀드(A: 이번 틱에 지울 홀드 — flush 전이라 아직 행이 있다)
     * @param pendingDecrement 이 트랜잭션이 곧 반영할 감소량(A: 지울 홀드 수, C·F: 0). 잠근 cnt = 실제 + 이 값이면 어긋남이 아니었다(오탐).
     * 오탐이어도 A는 원래 하려던 감소를 한다(cnt = 실제) — 결과적으로 정상 감소와 같다.
     */
    fun repairInCurrentTx(
        source: String,
        scheduleId: Long,
        userId: Long,
        kind: DriftKind,
        excludeHoldIds: Collection<Long> = emptyList(),
        pendingDecrement: Int = 0,
    ): DriftOutcome {
        var inserted = false
        val existing = lock(scheduleId, userId)
        if (existing == null && pendingDecrement == 0) {
            // C·F: 행 없음 + 실제 0이면 행 없음 = 0이 곧 맞는 값 — 탐지 뒤 홀드·예약이 사라진 오탐이다. 행을 만들지 않는다(코드 루프 1 L1-8).
            // A(감소 예정 n > 0)는 명세 §2 절차대로 행을 만들고 잠근 뒤 실제 값으로 확정한다(코드 루프 2 L2-3) — 아래 일반 경로
            if (actualOf(jdbc, scheduleId, userId, excludeHoldIds) == 0) {
                return DriftOutcome(source, scheduleId, userId, kind, null, 0, DriftAction.FALSE_POSITIVE).also(recorder::insert)
            }
        }
        val locked = existing ?: run {
            inserted = jdbc.update(
                "INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 0) ON CONFLICT DO NOTHING",
                scheduleId, userId,
            ) == 1
            lock(scheduleId, userId) ?: error("쿼터 행을 만든 직후 잠그지 못했다: 회차 $scheduleId 사용자 $userId")
        }
        val actual = actualOf(jdbc, scheduleId, userId, excludeHoldIds)
        if (locked != actual) {
            jdbc.update("UPDATE user_hold_quota SET cnt = ? WHERE schedule_id = ? AND user_id = ?", actual, scheduleId, userId)
        }
        val action = if (locked == actual + pendingDecrement) DriftAction.FALSE_POSITIVE else DriftAction.REPAIRED
        val outcome = DriftOutcome(
            source = source, scheduleId = scheduleId, userId = userId, kind = kind,
            cntBefore = if (inserted) null else locked, actual = actual, action = action,
        )
        recorder.insert(outcome)
        return outcome
    }

    private fun lock(scheduleId: Long, userId: Long): Int? = jdbc.queryForList(
        "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", Int::class.java, scheduleId, userId,
    ).singleOrNull()

    companion object {
        /** 실제 값 = 홀드 행 + CONFIRMED 예약(판정기·V6와 같은 정의) — 한 문장(한 스냅숏). */
        fun actualOf(jdbc: JdbcTemplate, scheduleId: Long, userId: Long, excludeHoldIds: Collection<Long> = emptyList()): Int {
            // id는 Long이라 리터럴로 넣어도 주입 위험이 없다(배열 바인딩을 피한다)
            val exclude = if (excludeHoldIds.isEmpty()) "" else " AND id NOT IN (${excludeHoldIds.joinToString(",")})"
            return jdbc.queryForObject(
                """
                SELECT ((SELECT count(*) FROM seat_hold WHERE schedule_id = ? AND user_id = ?$exclude)
                      + (SELECT count(*) FROM reservation WHERE schedule_id = ? AND user_id = ? AND status = 'CONFIRMED'))::int
                """.trimIndent(),
                Int::class.java, scheduleId, userId, scheduleId, userId,
            )!!
        }
    }
}

/** `counter_drift_log` 쓰기 + 지표 `seat.hold.counter.drift{source,kind,action}` + 로그. */
@Component
class CounterDriftRecorder(
    private val jdbc: JdbcTemplate,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    /** 현재 트랜잭션(또는 자동 커밋)에 1행. */
    fun insert(o: DriftOutcome) {
        jdbc.update(
            """
            INSERT INTO counter_drift_log (at, source, schedule_id, user_id, kind, cnt_before, actual, action, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Timestamp.from(clock.instant()), o.source, o.scheduleId, o.userId, o.kind.value, o.cntBefore, o.actual, o.action.value, o.reason,
        )
    }

    /** 커밋된 결과만 센다(복구가 롤백되면 그 결과 대신 skipped를 센다). */
    fun count(o: DriftOutcome) {
        meters.counter(DRIFT_METRIC, "source", o.source, "kind", o.kind.value, "action", o.action.value).increment()
        if (o.action == DriftAction.FALSE_POSITIVE) {
            log.info("카운터 오탐({}): 회차 {} 사용자 {} kind={} cnt={} 실제={}", o.source, o.scheduleId, o.userId, o.kind.value, o.cntBefore, o.actual)
        } else {
            log.warn(
                "카운터 어긋남 {}({}): 회차 {} 사용자 {} kind={} cnt={} 실제={} reason={}",
                o.action.value, o.source, o.scheduleId, o.userId, o.kind.value, o.cntBefore, o.actual, o.reason,
            )
        }
    }

    /** 기록 자체가 실패했을 때 — 무음 금지: ERROR 로그 + 지표 두 곳(설계 §1a). */
    fun recordFailed(o: DriftOutcome, e: Throwable) {
        meters.counter(RECORD_FAILED_METRIC, "source", o.source).increment()
        log.error("카운터 어긋남 기록 실패({}): 회차 {} 사용자 {} action={} — {}", o.source, o.scheduleId, o.userId, o.action.value, e.toString())
    }

    companion object {
        private val log = LoggerFactory.getLogger(CounterDriftRecorder::class.java)
        const val DRIFT_METRIC = "seat.hold.counter.drift"
        const val RECORD_FAILED_METRIC = "seat.hold.counter.drift.record_failed"
    }
}
