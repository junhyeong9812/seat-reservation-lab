package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.ProductSchedule
import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.limit.counterMismatch
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.actualOf
import com.jun.labs.seatreservation.support.cntOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.assertEquals

/**
 * ADR-009 판매 마감 — 명세 §1-2 · §2 '판매 마감'(now >= starts_at이면 선점 거절, 시각 출처 = 앱 Clock, 확정은 막지 않음) · §5.
 * 기본 설정(좌석 3b + counter-upsert-precheck, 마감 기본 켬). 조합 전체의 판정 순서는 SalesCloseOrderTest.
 * ADR-009 축(A·C·정리)은 IntegrationTest가 끔 고정한다(ADR-009 기본값 켬과 다르다).
 */
@AutoConfigureMockMvc
class SalesCloseTest : IntegrationTest() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var mockMvc: MockMvc

    private fun code(c: HoldSeatCommand): String =
        try { holdSeat.hold(c); "ok" } catch (e: SeatReservationException) { e.errorCode.name }

    @Test
    fun `starts_at 직전(1ms 전)은 선점 성공`() {
        clock.set(schedule.startsAt.minusMillis(1))
        assertEquals("ok", code(HoldSeatCommand(schedule.id!!, createSeat(1).id!!, 1L)))
    }

    @Test
    fun `starts_at 정각과 그 뒤는 SCHEDULE_CLOSED — 홀드·카운터를 남기지 않는다`() {
        val seat = createSeat(1)
        clock.set(schedule.startsAt)
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code(HoldSeatCommand(schedule.id!!, seat.id!!, 1L)), "now == starts_at")
        clock.set(schedule.startsAt.plus(Duration.ofHours(3)))
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code(HoldSeatCommand(schedule.id!!, seat.id!!, 1L)), "now > starts_at")
        assertEquals(0, holdCount())
        assertEquals(SeatStatus.AVAILABLE, seatStatus(seat))
        assertEquals(0, jdbcTemplate.cntOf(schedule.id!!, 1L) ?: 0)
    }

    @Test
    fun `HTTP — 마감 회차 선점은 409 SCHEDULE_CLOSED`() {
        val seat = createSeat(1)
        clock.set(schedule.startsAt.plusSeconds(1))
        mockMvc.post("/api/schedules/${schedule.id}/seats/${seat.id}/hold") { header(USER_ID_HEADER, 100) }
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("SCHEDULE_CLOSED") }
            }
    }

    @Test
    fun `마감은 회차별 — 같은 시각에 아직 시작 안 한 다른 회차는 선점 성공`() {
        val later = productScheduleRepository.save(ProductSchedule(productId = schedule.productId, startsAt = schedule.startsAt.plus(Duration.ofDays(1))))
        val seat = productSeatRepository.save(
            com.jun.labs.seatreservation.domain.ProductSeat(scheduleId = later.id!!, section = "A", rowNo = 1, seatNo = 1),
        )
        clock.set(schedule.startsAt.plusSeconds(1))
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code(HoldSeatCommand(schedule.id!!, createSeat(1).id!!, 1L)))
        assertEquals("ok", code(HoldSeatCommand(later.id!!, seat.id!!, 1L)))
    }

    @Test
    fun `마감된 회차의 확정은 성공 — 시작 전에 잡은 홀드의 결제 결과는 반영(서비스·HTTP)`() {
        val seat1 = createSeat(1)
        val seat2 = createSeat(2)
        clock.set(schedule.startsAt.minusSeconds(60)) // TTL 5분 — 시작 뒤 4분까지 살아 있다
        val h1 = holdSeat.hold(HoldSeatCommand(schedule.id!!, seat1.id!!, 7L))
        val h2 = holdSeat.hold(HoldSeatCommand(schedule.id!!, seat2.id!!, 7L))
        clock.set(schedule.startsAt.plusSeconds(60))

        val r = confirm.confirm(ConfirmReservationCommand(holdId = h1.holdId, userId = 7L, paymentUid = "pay-1"))
        assertEquals(ReservationStatus.CONFIRMED, r.status)
        mockMvc.post("/api/holds/${h2.holdId}/confirm") {
            header(USER_ID_HEADER, 7)
            contentType = MediaType.APPLICATION_JSON
            content = """{"paymentUid":"pay-2"}"""
        }.andExpect { status { isOk() } }

        assertEquals(SeatStatus.RESERVED, seatStatus(seat1))
        assertEquals(SeatStatus.RESERVED, seatStatus(seat2))
        assertEquals(jdbcTemplate.actualOf(schedule.id!!, 7L), jdbcTemplate.cntOf(schedule.id!!, 7L))
        assertEquals(0L, counterMismatch(jdbcTemplate))
    }

    @Test
    fun `마감 뒤 확정도 기존 규칙 그대로 — 만료된 홀드는 HOLD_EXPIRED(마감이 확정 규칙을 바꾸지 않는다)`() {
        clock.set(schedule.startsAt.minusSeconds(60))
        val h = holdSeat.hold(HoldSeatCommand(schedule.id!!, createSeat(1).id!!, 8L))
        clock.set(schedule.startsAt.plus(Duration.ofMinutes(10)))
        val e = assertThrows<SeatReservationException> { confirm.confirm(ConfirmReservationCommand(h.holdId, 8L, "pay-x")) }
        assertEquals(ErrorCode.HOLD_EXPIRED, e.errorCode)
    }
}

/** `seat.hold.sales-close.enabled=false` — 마감 판정을 끄면 시작한 회차도 선점 성공(명세 §10 D2 마감 비용 분리 세트). */
@TestPropertySource(properties = ["seat.hold.sales-close.enabled=false"])
class SalesCloseDisabledTest : IntegrationTest() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase

    @Test
    fun `마감 끔 — starts_at 뒤에도 선점 성공`() {
        val seat = createSeat(1)
        clock.set(schedule.startsAt.plus(Duration.ofHours(1)))
        holdSeat.hold(HoldSeatCommand(schedule.id!!, seat.id!!, 1L))
        assertEquals(SeatStatus.HELD, seatStatus(seat))
    }
}
