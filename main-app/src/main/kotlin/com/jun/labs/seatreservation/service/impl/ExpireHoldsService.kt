package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.drift.ExpiryCounters
import com.jun.labs.seatreservation.service.impl.expiry.LazyExpiryBatch
import com.jun.labs.seatreservation.service.impl.expiry.SweepConditionalBatch
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * ADR-000 기준선: 만료 배치(sweep). 만료 시각과 배치 실행 사이 좌석은 HELD로 남는다 — ADR-014(홀드 만료)에서 관측(번호 이동 전 ADR-004).
 * 틱 하나 = 트랜잭션 하나(지금과 같다). ADR-009: 틱 결과를 지표 `seat.hold.expiry.tick{outcome=ok|failed}`로 남기고,
 * 롤백되면 후보 A가 이 틱에 복구를 시도한 쌍을 독립 트랜잭션으로 skipped 기록한다(예외는 그대로 올린다).
 * ADR-014: 만료 방식이 sweep(A)이 아니면 [LazyExpiryBatch](C·D 청크 배치 · B는 할 일 없음)로 간다 — sweep이면 아래 경로 그대로다.
 * ADR-015(Q15): 확정 손잡이 `seat.confirm.mode`가 배치도 고른다 — current면 아래 JPA 배치 그대로 · 그 밖이면 트랜잭션 몸통만 A′([SweepConditionalBatch] — 조건부 DELETE · 지운 만큼 감소)로.
 * 틱 경계 · 지표 · skipped 기록은 공용이다.
 */
@Service
class ExpireHoldsService(
    private val productSeatRepository: ProductSeatRepository,
    private val clock: Clock,
    private val userLimit: ActiveUserLimit,
    private val counters: ExpiryCounters,
    private val tx: TransactionTemplate,
    private val meters: MeterRegistry,
    private val properties: SeatHoldProperties,
    private val lazyBatch: LazyExpiryBatch,
    private val confirmProperties: SeatConfirmProperties,
    private val conditionalBatch: SweepConditionalBatch,
) : ExpireHoldsUseCase {

    override fun expire(): Int {
        if (properties.expiryMode != ExpiryMode.SWEEP) return lazyBatch.expire()
        counters.beginTick()
        try {
            var sweepMetrics: () -> Unit = {} // A′ 지표 — 틱 커밋 뒤에만 센다(R15-16 · current는 아무것도 안 함)
            val count = try {
                tx.execute {
                    val now = clock.instant()
                    if (confirmProperties.mode != ConfirmMode.CURRENT) {
                        return@execute conditionalBatch.expireInTx(now).also { sweepMetrics = it.recordAfterCommit }.deleted
                    }
                    val expired = productSeatRepository.findDistinctByHoldsExpiresAtLessThanEqual(now)
                        .flatMap { it.expireHolds(now) }
                    // 매수 카운터(ADR-005 counter 계열)만 실제로 지운 홀드만큼 내린다(B는 트리거가 내리고 앱은 쿼터 행만 먼저 잠근다) — 다른 매수 방식에서는 아무것도 하지 않는다
                    userLimit.strategy.onHoldsExpired(expired)
                    expired.size
                }!!
            } catch (e: Throwable) {
                // 롤백 처리는 트랜잭션 실패에만 — 커밋 뒤 단계(지표·afterCommit)의 예외를 롤백으로 세지 않는다(코드 루프 2 L2-7)
                meters.counter(TICK_METRIC, "outcome", "failed").increment()
                counters.afterRollback(e)
                throw e
            }
            meters.counter(TICK_METRIC, "outcome", "ok").increment()
            counters.afterCommit()
            sweepMetrics()
            return count
        } finally {
            counters.endTick()
        }
    }

    companion object {
        const val TICK_METRIC = "seat.hold.expiry.tick"
    }
}
