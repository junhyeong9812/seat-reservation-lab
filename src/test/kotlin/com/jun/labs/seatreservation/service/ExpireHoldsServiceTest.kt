package com.jun.labs.seatreservation.service

import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.test.assertEquals

class ExpireHoldsServiceTest : IntegrationTest() {

    @Autowired lateinit var holdSeatUseCase: HoldSeatUseCase
    @Autowired lateinit var expireHoldsUseCase: ExpireHoldsUseCase

    @Test
    fun `만료된 홀드만 정리하고 살아 있는 홀드는 건드리지 않는다`() {
        val expiredSeat = createSeat(1)
        val liveSeat = createSeat(2)
        val expiredHold = holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, expiredSeat.id!!, 100L))
        clock.advance(Duration.ofMinutes(1))
        val liveHold = holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, liveSeat.id!!, 200L))
        clock.advance(Duration.ofMinutes(4)) // 첫 홀드는 만료 정각, 둘째는 1분 남음

        val expiredCount = expireHoldsUseCase.expire()

        assertEquals(1, expiredCount)
        assertEquals(SeatStatus.AVAILABLE, seatStatus(expiredSeat))
        assertEquals(false, holdExists(expiredHold.holdId))
        assertEquals(SeatStatus.HELD, seatStatus(liveSeat))
        assertEquals(true, holdExists(liveHold.holdId))
    }

    @Test
    fun `만료된 홀드가 없으면 아무것도 바꾸지 않는다`() {
        val seat = createSeat(1)
        holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, seat.id!!, 100L))

        assertEquals(0, expireHoldsUseCase.expire())
        assertEquals(SeatStatus.HELD, seatStatus(seat))
        assertEquals(1, holdCount())
    }
}
