package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.impl.drift.ExpiryCounters
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * ADR-000 기준선: 만료 배치(sweep). 만료 시각과 배치 실행 사이 좌석은 HELD로 남는다 — ADR-013(홀드 만료)에서 관측(번호 이동 전 ADR-004).
 * 틱 하나 = 트랜잭션 하나(지금과 같다). ADR-009: 틱 결과를 지표 `seat.hold.expiry.tick{outcome=ok|failed}`로 남기고,
 * 롤백되면 후보 A가 이 틱에 복구를 시도한 쌍을 독립 트랜잭션으로 skipped 기록한다(예외는 그대로 올린다).
 */
@Service
class ExpireHoldsService(
    private val productSeatRepository: ProductSeatRepository,
    private val clock: Clock,
    private val userLimit: ActiveUserLimit,
    private val counters: ExpiryCounters,
    private val tx: TransactionTemplate,
    private val meters: MeterRegistry,
) : ExpireHoldsUseCase {

    override fun expire(): Int {
        counters.beginTick()
        try {
            val count = try {
                tx.execute {
                    val now = clock.instant()
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
            return count
        } finally {
            counters.endTick()
        }
    }

    companion object {
        const val TICK_METRIC = "seat.hold.expiry.tick"
    }
}
