package com.jun.labs.seatreservation.service.impl.hold

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldStrategyType

/**
 * 같은 좌석 경합 제어 방식(ADR-003). 락 획득·트랜잭션 경계·진 쪽 처리를 정하고, 선점 규칙은 HoldSeatProcess에 맡긴다.
 * 진 쪽은 모든 방식에서 기준선과 같은 409 SEAT_NOT_AVAILABLE로 돌려준다 — 응답 계약 불변.
 */
interface HoldStrategy {
    val type: HoldStrategyType

    fun hold(command: HoldSeatCommand): HoldSeatResult
}

internal fun seatTaken(): Nothing = throw SeatReservationException(ErrorCode.SEAT_NOT_AVAILABLE)
