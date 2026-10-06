package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategy
import org.springframework.stereotype.Service

/**
 * 선점 유스케이스 — 설정된 경합 제어 방식(ADR-003)에 위임한다.
 * 락 획득·트랜잭션 경계·진 쪽 처리는 방식마다 달라 전략이 책임지고, 선점 규칙 자체는 [HoldSeatProcess] 하나다.
 */
@Service
class HoldSeatService(
    strategies: List<HoldStrategy>,
    properties: SeatHoldProperties,
) : HoldSeatUseCase {

    private val strategy: HoldStrategy = strategies.single { it.type == properties.strategy }

    override fun hold(command: HoldSeatCommand): HoldSeatResult = strategy.hold(command)
}
