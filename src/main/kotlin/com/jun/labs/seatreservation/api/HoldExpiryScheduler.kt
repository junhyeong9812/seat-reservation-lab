package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 만료 배치의 진입점. 테스트에서는 `seat.hold.expiry-scheduler-enabled=false`로 끄고 UseCase를 직접 호출한다. */
@Component
@ConditionalOnProperty("seat.hold.expiry-scheduler-enabled", havingValue = "true", matchIfMissing = true)
class HoldExpiryScheduler(
    private val expireHoldsUseCase: ExpireHoldsUseCase,
) {

    @Scheduled(fixedDelayString = "\${seat.hold.expiry-interval}")
    fun expire() {
        expireHoldsUseCase.expire()
    }
}
