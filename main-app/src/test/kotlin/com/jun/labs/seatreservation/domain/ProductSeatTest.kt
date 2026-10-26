package com.jun.labs.seatreservation.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/** Seat 애그리거트의 불변식 — DB·Spring 없이 도메인 메서드만으로 검증한다. */
class ProductSeatTest {

    private val now = Instant.parse("2026-09-27T10:00:00Z")
    private val ttl = Duration.ofMinutes(5)

    private fun seat(status: SeatStatus = SeatStatus.AVAILABLE) =
        ProductSeat(scheduleId = 1, section = "A", rowNo = 1, seatNo = 1, status = status)

    /** 영속화 없이 확정을 검증하려고 홀드 id를 직접 채운다(실제로는 flush 시 DB가 발급). */
    private fun ProductSeat.holdWithId(userId: Long, id: Long = 10): SeatHold =
        hold(userId, now, ttl).also { it.id = id }

    @Test
    fun `선점하면 HELD가 되고 홀드의 만료 시각은 now + ttl`() {
        val seat = seat()

        val hold = seat.hold(userId = 1, now = now, ttl = ttl)

        assertEquals(SeatStatus.HELD, seat.status)
        assertEquals(now.plus(ttl), hold.expiresAt)
    }

    @Test
    fun `AVAILABLE이 아니면 선점할 수 없다`() {
        listOf(SeatStatus.HELD, SeatStatus.RESERVED).forEach { status ->
            val e = assertThrows<SeatReservationException> { seat(status).hold(1, now, ttl) }
            assertEquals(ErrorCode.SEAT_NOT_AVAILABLE, e.errorCode)
        }
    }

    @Test
    fun `확정하면 RESERVED가 되고 홀드가 제거된다`() {
        val seat = seat()
        val hold = seat.holdWithId(userId = 1)

        seat.confirm(hold.id!!, userId = 1, now = now.plus(Duration.ofMinutes(4)))

        assertEquals(SeatStatus.RESERVED, seat.status)
        assertEquals(0, seat.expireHolds(now.plus(Duration.ofDays(1))).size) // 남은 홀드 없음
    }

    @Test
    fun `확정의 2중 확인 순서 — 홀드 없음, 남의 홀드, 만료`() {
        val seat = seat()
        val hold = seat.holdWithId(userId = 1)

        assertEquals(ErrorCode.HOLD_NOT_FOUND, assertThrows<SeatReservationException> {
            seat.confirm(holdId = 999, userId = 1, now = now)
        }.errorCode)
        // 남의 홀드이면서 만료 — 소유 확인이 먼저
        assertEquals(ErrorCode.HOLD_NOT_OWNED, assertThrows<SeatReservationException> {
            seat.confirm(hold.id!!, userId = 2, now = now.plus(ttl))
        }.errorCode)
        assertEquals(ErrorCode.HOLD_EXPIRED, assertThrows<SeatReservationException> {
            seat.confirm(hold.id!!, userId = 1, now = now.plus(ttl))
        }.errorCode)
        assertEquals(SeatStatus.HELD, seat.status)
    }

    @Test
    fun `만료 전이면 expireHolds는 아무것도 바꾸지 않고, 만료 정각부터 AVAILABLE로 되돌린다`() {
        val seat = seat()
        seat.hold(userId = 1, now = now, ttl = ttl)

        assertEquals(0, seat.expireHolds(now.plus(ttl).minusSeconds(1)).size)
        assertEquals(SeatStatus.HELD, seat.status)

        assertEquals(1, seat.expireHolds(now.plus(ttl)).size)
        assertEquals(SeatStatus.AVAILABLE, seat.status)
    }
}
