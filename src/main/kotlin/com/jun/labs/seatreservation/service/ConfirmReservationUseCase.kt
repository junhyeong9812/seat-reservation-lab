package com.jun.labs.seatreservation.service

import com.jun.labs.seatreservation.domain.ReservationStatus

interface ConfirmReservationUseCase {
    fun confirm(command: ConfirmReservationCommand): ConfirmReservationResult
}

data class ConfirmReservationCommand(
    val holdId: Long,
    val userId: Long,
    val paymentUid: String,
)

data class ConfirmReservationResult(
    val reservationId: Long,
    val seatId: Long,
    val status: ReservationStatus,
)
