package com.jun.labs.seatreservation.expiry

import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ADR-014 A(sweep — 기준선) 계약(blind): 손잡이를 주지 않으면 지금과 같다(명세 §2 '기존 동작 보존' · §설계-6 '기본 SWEEP') · `sweep`을 명시해도 같다 ·
 * A는 앱 Clock 그대로(명세 §10 R1) · 좌석 맵은 모든 방식에 있고 A는 저장 상태를 그대로 보인다(§3.3 A 문장).
 * 두 하위 클래스(키 없음 · sweep 명시)가 같은 테스트를 돈다.
 */
@AutoConfigureMockMvc
abstract class Adr014SweepContractBase : IntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var expireHolds: ExpireHoldsUseCase
    @Autowired lateinit var seatHoldProperties: SeatHoldProperties

    private val s: Long get() = schedule.id!!

    @Test
    fun `손잡이 기본값 = SWEEP(새 필드 · 리플렉션 — E1)`() {
        val getter = runCatching { SeatHoldProperties::class.java.getMethod("getExpiryMode") }.getOrNull()
        assertTrue(getter != null, "SeatHoldProperties에 expiryMode 필드가 없다(§설계-6)")
        assertEquals("sweep", getter.invoke(seatHoldProperties).toString().lowercase())
    }

    @Test
    fun `A 그대로 — 선점 expiresAt = 앱 시계 + TTL · 배치 전 저장 HELD 좌석은 409 · 앱 시계 기준 배치 뒤 다시 잡힌다`() {
        val seat = createSeat(1)
        mockMvc.post("/api/schedules/$s/seats/${seat.id}/hold") { header(USER_ID_HEADER, 100) }.andExpect {
            status { isCreated() }
            jsonPath("$.expiresAt") { value(NOW.plus(TTL).toString()) }
        }
        clock.advance(TTL)
        mockMvc.post("/api/schedules/$s/seats/${seat.id}/hold") { header(USER_ID_HEADER, 200) }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SEAT_NOT_AVAILABLE") }
        }
        assertEquals(1, expireHolds.expire(), "A 배치는 앱 시계로 만료를 고른다(R1)")
        assertEquals(SeatStatus.AVAILABLE, seatStatus(seat))
        holdSeat.hold(HoldSeatCommand(s, seat.id!!, 200))
        assertEquals(SeatStatus.HELD, seatStatus(seat))
    }

    @Test
    fun `A 좌석 맵 — 저장 상태 그대로(배치 전 만료 홀드 좌석은 HELD로 보인다)`() {
        val free = createSeat(1)
        val held = createSeat(2)
        holdSeat.hold(HoldSeatCommand(s, held.id!!, 100))
        clock.advance(TTL.plus(Duration.ofMinutes(1)))       // 앱 시계로 만료 — 배치 전
        val sold = createSeat(3, SeatStatus.RESERVED)
        val r = mockMvc.get("/api/schedules/$s/seats").andReturn().response
        assertEquals(200, r.status, r.contentAsString)
        val node = objectMapper.readTree(r.contentAsString)
        assertEquals(
            listOf(free.id to "AVAILABLE", held.id to "HELD", sold.id to "RESERVED"),
            node.map { it.get("seatId").asLong() to it.get("status").asText() },
        )
        assertEquals(setOf("seatId", "section", "rowNo", "seatNo", "status"), node.first().fieldNames().asSequence().toSet())
        mockMvc.get("/api/schedules/987654321/seats").andExpect { status { isNotFound() } }
    }
}

/** 손잡이 키 없음 — IntegrationTest와 같은 설정(+ MockMvc). */
class Adr014SweepDefaultContractTest : Adr014SweepContractBase()

/** `seat.hold.expiry-mode=sweep` 명시. */
@TestPropertySource(properties = ["$EXPIRY_MODE=sweep"])
class Adr014SweepExplicitContractTest : Adr014SweepContractBase()
