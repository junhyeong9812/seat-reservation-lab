package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * ADR-000 기준선: 만료 배치(sweep). 만료 시각과 배치 실행 사이 좌석은 HELD로 남는다 — ADR-004에서 관측.
 */
@Service
class ExpireHoldsService(
    private val productSeatRepository: ProductSeatRepository,
    private val clock: Clock,
    private val userLimit: ActiveUserLimit,
) : ExpireHoldsUseCase {

    @Transactional
    override fun expire(): Int {
        val now = clock.instant()
        val expired = productSeatRepository.findDistinctByHoldsExpiresAtLessThanEqual(now)
            .flatMap { it.expireHolds(now) }
        // 매수 카운터(ADR-005 counter)만 실제로 지운 홀드만큼 내린다 — 다른 매수 방식에서는 아무것도 하지 않는다
        userLimit.strategy.onHoldsExpired(expired)
        return expired.size
    }
}
