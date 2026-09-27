package com.jun.labs.seatreservation.service

import com.jun.labs.seatreservation.domain.Reservation
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

class HoldSeatServiceTest : IntegrationTest() {

    @Autowired lateinit var holdSeatUseCase: HoldSeatUseCase

    private val userId = 100L

    @Test
    fun `빈 좌석을 선점하면 좌석은 HELD, 홀드는 TTL 5분으로 생성된다`() {
        val seat = createSeat(1)

        val result = holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId))

        assertEquals(SeatStatus.HELD, seatStatus(seat))
        val hold = holdRow(result.holdId)
        assertEquals(seat.id, hold.seatId)
        assertEquals(userId, hold.userId)
        assertEquals(NOW, hold.heldAt)
        assertEquals(NOW.plus(Duration.ofMinutes(5)), hold.expiresAt)
        assertEquals(hold.expiresAt, result.expiresAt)
    }

    @Test
    fun `이미 HELD인 좌석은 거절되고 DB는 변하지 않는다`() {
        val seat = createSeat(1, SeatStatus.HELD)

        val e = assertThrows<SeatReservationException> {
            holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId))
        }

        assertEquals(ErrorCode.SEAT_NOT_AVAILABLE, e.errorCode)
        assertEquals(SeatStatus.HELD, seatStatus(seat))
        assertEquals(0, holdCount())
    }

    @Test
    fun `RESERVED 좌석은 거절된다`() {
        val seat = createSeat(1, SeatStatus.RESERVED)

        val e = assertThrows<SeatReservationException> {
            holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId))
        }

        assertEquals(ErrorCode.SEAT_NOT_AVAILABLE, e.errorCode)
        assertEquals(0, holdCount())
    }

    @Test
    fun `홀드 1매 + 확정 예약 1매를 가진 사용자의 세 번째 선점은 거절된다`() {
        val held = createSeat(1)
        val reserved = createSeat(2, SeatStatus.RESERVED)
        val third = createSeat(3)
        holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, held.id!!, userId))
        reservationRepository.save(
            Reservation(schedule.id!!, reserved.id!!, userId, "pay-1", ReservationStatus.CONFIRMED, NOW),
        )

        val e = assertThrows<SeatReservationException> {
            holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, third.id!!, userId))
        }

        assertEquals(ErrorCode.HOLD_LIMIT_EXCEEDED, e.errorCode)
        assertEquals(SeatStatus.AVAILABLE, seatStatus(third))
        assertEquals(1, holdCount())
    }

    @Test
    fun `선점 불가 좌석이면서 매수도 초과면 선점 불가가 먼저 보고된다`() {
        val first = createSeat(1)
        val second = createSeat(2)
        val heldByOther = createSeat(3, SeatStatus.HELD)
        holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, first.id!!, userId))
        holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, second.id!!, userId))

        val e = assertThrows<SeatReservationException> {
            holdSeatUseCase.hold(HoldSeatCommand(schedule.id!!, heldByOther.id!!, userId))
        }

        assertEquals(ErrorCode.SEAT_NOT_AVAILABLE, e.errorCode)
    }

    @Test
    fun `다른 회차의 좌석 id로 요청하면 좌석을 찾을 수 없다`() {
        val seat = createSeat(1)

        val e = assertThrows<SeatReservationException> {
            holdSeatUseCase.hold(HoldSeatCommand(schedule.id!! + 999, seat.id!!, userId))
        }

        assertEquals(ErrorCode.SEAT_NOT_FOUND, e.errorCode)
        assertEquals(SeatStatus.AVAILABLE, seatStatus(seat))
    }
}
