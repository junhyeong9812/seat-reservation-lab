package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
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
) : ExpireHoldsUseCase {

    @Transactional
    override fun expire(): Int {
        val now = clock.instant()
        return productSeatRepository.findDistinctByHoldsExpiresAtLessThanEqual(now)
            .sumOf { it.expireHolds(now) }
    }
}
