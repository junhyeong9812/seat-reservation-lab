package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class ReservationController(
    private val confirmReservationUseCase: ConfirmReservationUseCase,
) {

    /** 결제 성공 후 호출된다 — 결제 자체는 이 랩의 범위 밖(02 payment-consistency-lab). */
    @PostMapping("/api/holds/{holdId}/confirm")
    fun confirm(
        @PathVariable holdId: Long,
        @RequestHeader(USER_ID_HEADER) userId: Long,
        @RequestBody request: ConfirmReservationRequest,
    ): ConfirmReservationResponse {
        val result = confirmReservationUseCase.confirm(
            ConfirmReservationCommand(holdId = holdId, userId = userId, paymentUid = request.paymentUid),
        )
        return ConfirmReservationResponse(
            reservationId = result.reservationId,
            seatId = result.seatId,
            status = result.status,
        )
    }
}

data class ConfirmReservationRequest(
    val paymentUid: String,
)

data class ConfirmReservationResponse(
    val reservationId: Long,
    val seatId: Long,
    val status: ReservationStatus,
)
