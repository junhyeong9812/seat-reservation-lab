package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class SeatHoldController(
    private val holdSeatUseCase: HoldSeatUseCase,
) {

    @PostMapping("/api/schedules/{scheduleId}/seats/{seatId}/hold")
    @ResponseStatus(HttpStatus.CREATED)
    fun hold(
        @PathVariable scheduleId: Long,
        @PathVariable seatId: Long,
        @RequestHeader(USER_ID_HEADER) userId: Long,
    ): HoldSeatResponse {
        val result = holdSeatUseCase.hold(HoldSeatCommand(scheduleId = scheduleId, seatId = seatId, userId = userId))
        return HoldSeatResponse(holdId = result.holdId, seatId = result.seatId, expiresAt = result.expiresAt)
    }
}

data class HoldSeatResponse(
    val holdId: Long,
    val seatId: Long,
    val expiresAt: Instant,
)
