package com.jun.labs.seatreservation.domain

import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.domain.repository.ReservationRepository
import org.springframework.stereotype.Component

/**
 * 1인 최대 매수 — 여러 Seat와 Reservation에 걸친 규칙이라 어느 애그리거트도 스스로 지킬 수 없어 도메인 서비스로 둔다.
 * 조회 후 비교(check-then-act)라 같은 사용자의 동시 요청은 막지 못한다 — ADR-005(1인 매수 제어)에서 관측(문서 번호 이동 전 ADR-003·004로 적혀 있던 것을 정정).
 */
@Component
class HoldLimitPolicy(
    private val productSeatRepository: ProductSeatRepository,
    private val reservationRepository: ReservationRepository,
) {

    fun check(scheduleId: Long, userId: Long, maxPerUser: Int) {
        val holdCount = productSeatRepository.countByScheduleIdAndHoldsUserId(scheduleId, userId)
        val reservedCount = reservationRepository.countByScheduleIdAndUserIdAndStatus(
            scheduleId, userId, ReservationStatus.CONFIRMED,
        )
        if (holdCount + reservedCount >= maxPerUser) {
            throw SeatReservationException(ErrorCode.HOLD_LIMIT_EXCEEDED)
        }
    }
}
