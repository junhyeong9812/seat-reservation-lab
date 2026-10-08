package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.Reservation
import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.domain.repository.ReservationRepository
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationResult
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * ADR-000 기준선: 좌석 애그리거트의 확정(2중 확인 포함) + 예약 생성을 같은 트랜잭션에서 한다
 * — 애그리거트 2개를 한 트랜잭션에서 바꾸는 의도된 예외(ADR-000 §2.1).
 * 확인과 전환 사이에 상태가 바뀔 수 있다 — ADR-008(확정 원자성)에서 관측(번호 이동 전 ADR-005).
 */
@Service
class ConfirmReservationService(
    private val productSeatRepository: ProductSeatRepository,
    private val reservationRepository: ReservationRepository,
    private val clock: Clock,
) : ConfirmReservationUseCase {

    @Transactional
    override fun confirm(command: ConfirmReservationCommand): ConfirmReservationResult {
        val now = clock.instant()
        val seat = productSeatRepository.findByHoldsId(command.holdId)
            ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)

        seat.confirm(command.holdId, command.userId, now)

        val reservation = reservationRepository.save(
            Reservation(
                scheduleId = seat.scheduleId,
                seatId = seat.id!!,
                userId = command.userId,
                paymentUid = command.paymentUid,
                status = ReservationStatus.CONFIRMED,
                createdAt = now,
            ),
        )
        return ConfirmReservationResult(
            reservationId = reservation.id!!,
            seatId = seat.id!!,
            status = reservation.status,
        )
    }
}
