package com.jun.labs.seatreservation.service

import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.test.assertEquals

class ConfirmReservationServiceTest : IntegrationTest() {

    @Autowired lateinit var holdSeatUseCase: HoldSeatUseCase
    @Autowired lateinit var confirmReservationUseCase: ConfirmReservationUseCase

    private val userId = 100L
    private val otherUserId = 200L

    @Test
    fun `유효한 내 홀드를 확정하면 좌석 RESERVED, 홀드 삭제, 예약 CONFIRMED`() {
        val seat = createSeat(1)
        val hold = holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId))
        clock.advance(Duration.ofMinutes(4))

        val result = confirmReservationUseCase.confirm(ConfirmReservationCommand(hold.holdId, userId, "pay-1"))

        assertEquals(SeatStatus.RESERVED, seatStatus(seat))
        assertEquals(0, holdCount())
        val reservation = reservationRepository.findById(result.reservationId).orElseThrow()
        assertEquals(ReservationStatus.CONFIRMED, reservation.status)
        assertEquals(seat.id, reservation.seatId)
        assertEquals(userId, reservation.userId)
        assertEquals("pay-1", reservation.paymentUid)
        assertEquals(clock.instant(), reservation.createdAt)
    }

    @Test
    fun `없는 홀드는 거절된다`() {
        val e = assertThrows<SeatReservationException> {
            confirmReservationUseCase.confirm(ConfirmReservationCommand(9_999, userId, "pay-1"))
        }

        assertEquals(ErrorCode.HOLD_NOT_FOUND, e.errorCode)
    }

    @Test
    fun `남의 홀드는 거절되고 DB는 변하지 않는다`() {
        val seat = createSeat(1)
        val hold = holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId))

        val e = assertThrows<SeatReservationException> {
            confirmReservationUseCase.confirm(ConfirmReservationCommand(hold.holdId, otherUserId, "pay-1"))
        }

        assertEquals(ErrorCode.HOLD_NOT_OWNED, e.errorCode)
        assertUnchanged(seat.id!!, hold.holdId)
    }

    @Test
    fun `만료 정각의 홀드는 만료로 거절된다`() {
        val seat = createSeat(1)
        val hold = holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId))
        clock.advance(Duration.ofMinutes(5))

        val e = assertThrows<SeatReservationException> {
            confirmReservationUseCase.confirm(ConfirmReservationCommand(hold.holdId, userId, "pay-1"))
        }

        assertEquals(ErrorCode.HOLD_EXPIRED, e.errorCode)
        assertUnchanged(seat.id!!, hold.holdId)
    }

    @Test
    fun `홀드는 유효하지만 좌석이 HELD가 아니면 거절된다`() {
        val seat = createSeat(1, SeatStatus.AVAILABLE)
        val holdId = insertHold(seat, userId, NOW, NOW.plus(Duration.ofMinutes(5)))

        val e = assertThrows<SeatReservationException> {
            confirmReservationUseCase.confirm(ConfirmReservationCommand(holdId, userId, "pay-1"))
        }

        assertEquals(ErrorCode.SEAT_NOT_HELD, e.errorCode)
        assertEquals(SeatStatus.AVAILABLE, seatStatus(seat))
        assertEquals(1, holdCount())
        assertEquals(0, reservationRepository.count())
    }

    private fun assertUnchanged(seatId: Long, holdId: Long) {
        assertEquals(SeatStatus.HELD, productSeatRepository.findById(seatId).orElseThrow().status)
        assertEquals(true, holdExists(holdId))
        assertEquals(0, reservationRepository.count())
    }
}
