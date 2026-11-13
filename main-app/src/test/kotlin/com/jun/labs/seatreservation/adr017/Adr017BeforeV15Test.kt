package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.service.impl.payment.CompensationSweep
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

/**
 * N17-10 · N17-11 — V15 앞 스키마(기준선 재현: current + Flyway target 7 — 기동 검사가 허용하는 조합)에서도
 * 확정은 지금과 같고(payment_intent 표가 없어도 500이 아니다), 결제 조회는 404, 안전망은 아무것도 하지 않는다.
 */
@ActiveProfiles("loadtest")
@TestPropertySource(properties = ["seat.hold.limit-strategy=none", "spring.flyway.target=7", "seat.confirm.mode=current"])
@AutoConfigureMockMvc
class Adr017BeforeV15Test : IntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var sweep: CompensationSweep

    @BeforeEach
    fun seed() {
        mockMvc.post("/internal/reset?schedules=1&seatsPerSchedule=10").andExpect { status { isOk() } }
    }

    @Test
    fun `V15 앞 스키마 — 확정 200 · 결제 조회 404 · 안전망 0`() {
        assertEquals(false, jdbcTemplate.queryForObject("SELECT to_regclass('payment_intent') IS NOT NULL", Boolean::class.java))
        val hold = mockMvc.post("/api/schedules/1/seats/1/hold") { header("X-User-Id", "1") }
            .andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val holdId = Regex("\"holdId\":(\\d+)").find(hold)!!.groupValues[1]

        mockMvc.post("/api/holds/$holdId/confirm") {
            header("X-User-Id", "1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"paymentUid":"pay-1"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CONFIRMED") }
            jsonPath("$.compensation") { doesNotExist() }
        }
        mockMvc.get("/api/payments/pay-1") { header("X-User-Id", "1") }.andExpect { status { isNotFound() } }
        assertEquals(0, sweep.runOnce())
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE payment_uid = 'pay-1'", Int::class.java))
    }
}
