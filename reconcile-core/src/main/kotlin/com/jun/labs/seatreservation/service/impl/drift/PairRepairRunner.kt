package com.jun.labs.seatreservation.service.impl.drift

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock

/** 쌍 복구 한 번이 커넥션을 빌릴 때 세는 곳(바퀴·틱·실행마다 다르다) + skipped 기록마저 실패했을 때 그 바퀴를 실패로 남기는 곳. */
interface RepairPass {
    fun <T> borrow(block: () -> T): T
    fun recordFailed(message: String, e: Throwable)
}

/**
 * 어긋나 보인 쌍 하나의 복구(ADR-012 §4.2.1 — 지금 `CounterReconciler.repairOne`을 떼어 낸 것): 전체 대조 C·F · 증분 · 안전망이 같이 쓴다.
 * [CounterRepairProbe] → 트랜잭션 하나(`SET LOCAL lock_timeout`) 안에서 회차 마감 재확인(`FOR SHARE` · 앱 Clock) → [PairRepair].
 * 실패하면 롤백 뒤 **새 트랜잭션**으로 skipped(reason = SQLSTATE)를 쓴다. 쌍마다 트랜잭션 — 여러 쌍을 한 트랜잭션에서 잠그지 않는다(§3-2).
 */
@Component
class PairRepairRunner(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val repair: PairRepair,
    private val recorder: CounterDriftRecorder,
    private val probe: ObjectProvider<CounterRepairProbe>,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    fun repairOne(pass: RepairPass, source: String, o: PairObservation, lockTimeoutMs: Long): DriftOutcome {
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
                    if (startsAt == null || ScheduleClosure.isClosed(startsAt.toInstant(), clock.instant())) {
                        DriftOutcome(source, o.scheduleId, o.userId, kind, o.cnt, o.actual, DriftAction.SKIPPED, CounterReconciler.SKIP_CLOSED).also(recorder::insert)
                    } else {
                        repair.repairInCurrentTx(source, o.scheduleId, o.userId, kind)
                    }
                }!!
            }
            if (outcome.action == DriftAction.SKIPPED && outcome.reason == CounterReconciler.SKIP_CLOSED) {
                // 마감 제외는 실패가 아니다 — drift{action=skipped}(실패)에 넣지 않고 마감 제외 지표(쌍)에 더한다(L3-4 재점검). 기록 1행은 위에서 남겼다
                meters.counter(CounterReconciler.EXCLUDED_METRIC, "unit", "pairs").increment()
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
                pass.recordFailed("skipped 기록 실패 — 회차 ${o.scheduleId} 사용자 ${o.userId}", recordFailure)
            }
            skipped
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PairRepairRunner::class.java)
    }
}
