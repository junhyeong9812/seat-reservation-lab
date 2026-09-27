package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

@AutoConfigureMockMvc
class SeatHoldApiTest : IntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `선점 201 → 다른 사용자 선점 409 → 확정 200 (HTTP 경로 스모크)`() {
        val seat = createSeat(1)
        val holdPath = "/api/schedules/${schedule.id}/seats/${seat.id}/hold"

        val holdBody = mockMvc.post(holdPath) { header(USER_ID_HEADER, 100) }
            .andExpect {
                status { isCreated() }
                jsonPath("$.seatId") { value(seat.id!!) }
                jsonPath("$.expiresAt") { value("2026-09-27T10:05:00Z") }
            }
            .andReturn().response.contentAsString
        val holdId = Regex("\"holdId\":(\\d+)").find(holdBody)!!.groupValues[1]

        mockMvc.post(holdPath) { header(USER_ID_HEADER, 200) }
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("SEAT_NOT_AVAILABLE") }
            }

        mockMvc.post("/api/holds/$holdId/confirm") {
            header(USER_ID_HEADER, 100)
            contentType = MediaType.APPLICATION_JSON
            content = """{"paymentUid":"pay-1"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.seatId") { value(seat.id!!) }
            jsonPath("$.status") { value("CONFIRMED") }
        }

        assertEquals(SeatStatus.RESERVED, seatStatus(seat))
    }

    @Test
    fun `loadtest 프로필이 없으면 내부 엔드포인트와 actuator가 없다`() {
        mockMvc.post("/internal/reset").andExpect { status { isNotFound() } }
        mockMvc.get("/internal/consistency").andExpect { status { isNotFound() } }
        mockMvc.get("/actuator/health").andExpect { status { isNotFound() } }
    }

    @Test
    fun `없는 좌석은 404`() {
        mockMvc.post("/api/schedules/${schedule.id}/seats/9999/hold") { header(USER_ID_HEADER, 100) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("SEAT_NOT_FOUND") }
            }
    }

    @Test
    fun `남의 홀드 확정은 403`() {
        val seat = createSeat(1)
        val holdBody = mockMvc.post("/api/schedules/${schedule.id}/seats/${seat.id}/hold") {
            header(USER_ID_HEADER, 100)
        }.andReturn().response.contentAsString
        val holdId = Regex("\"holdId\":(\\d+)").find(holdBody)!!.groupValues[1]

        mockMvc.post("/api/holds/$holdId/confirm") {
            header(USER_ID_HEADER, 200)
            contentType = MediaType.APPLICATION_JSON
            content = """{"paymentUid":"pay-1"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("HOLD_NOT_OWNED") }
        }
    }
}
