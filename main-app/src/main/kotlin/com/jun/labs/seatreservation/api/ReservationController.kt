package com.jun.labs.seatreservation.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.jun.labs.seatreservation.domain.CompensationView
import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.impl.payment.PaymentConfirmService
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
// F(reconciler 프로필 — 대조 전용 프로세스)는 업무 요청을 받지 않는다(코드 루프 1 L1-5)
@Profile("!reconciler")
class ReservationController(
    private val paymentConfirm: PaymentConfirmService,
) {

    /**
     * 결제 성공 후 호출된다 — 결제 자체는 이 랩의 범위 밖(02 payment-consistency-lab · ADR-017은 흉내 PG로 대신한다).
     * ADR-017 설계-4.3: `paymentUid`가 payment-start가 발급한 uid면 intent 경로(멱등 재생 · 거부 기록 · 보상), 아니면 옛 경로(지금과 같다 — N17-10).
     */
    @PostMapping("/api/holds/{holdId}/confirm")
    fun confirm(
        @PathVariable holdId: Long,
        @RequestHeader(USER_ID_HEADER) userId: Long,
        @RequestBody request: ConfirmReservationRequest,
    ): ConfirmReservationResponse {
        val response = paymentConfirm.confirm(
            ConfirmReservationCommand(holdId = holdId, userId = userId, paymentUid = request.paymentUid),
        )
        val result = response.result
        return ConfirmReservationResponse(
            reservationId = result.reservationId,
            seatId = result.seatId,
            status = result.status,
            compensation = response.compensation,
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
    /** ADR-017 — intent 경로에 작업이 있을 때만(D의 CAPTURE) · 옛 경로 본문은 지금 그대로(N17-10) */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val compensation: CompensationView? = null,
)
