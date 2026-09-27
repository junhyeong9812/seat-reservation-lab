package com.jun.labs.seatreservation.service

import java.time.Instant

interface HoldSeatUseCase {
    fun hold(command: HoldSeatCommand): HoldSeatResult
}

data class HoldSeatCommand(
    val scheduleId: Long,
    val seatId: Long,
    val userId: Long,
)

data class HoldSeatResult(
    val holdId: Long,
    val seatId: Long,
    val expiresAt: Instant,
)
