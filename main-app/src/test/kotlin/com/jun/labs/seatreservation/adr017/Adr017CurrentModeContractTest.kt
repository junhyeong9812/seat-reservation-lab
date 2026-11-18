package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.M_CURRENT
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * N17-11 — current 모드의 외부 계약은 그대로: payment-start 404(핸들러 없음) · intent 없음 · 확정은 옛 경로(N17-10 본문).
 * 애너테이션 = Adr016CurrentModeContractTest와 같다(컨텍스트 공유). ② · ③은 같은 계약이라 current 하나로 대표한다.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CURRENT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr017CurrentModeContractTest : Adr017Base() {

    @Test
    fun `N17-11 current — payment-start 404(HOLD_NOT_FOUND 아님) · intent 0행`() {
        val hold = holdOk(createSeat(1).id!!, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val r = paymentStart(hold, x)
        assertEquals(404, r.status, "current에는 payment-start가 없다(N17-11): $r")
        assertTrue(r.code != "HOLD_NOT_FOUND", "핸들러 없음 404: $r")
        assertTrue(r.json?.get(PAYMENT_UID) == null)
        assertEquals(0, intentCount(), "current는 intent를 만들지 않는다(N17-11)")
    }

    @Test
    fun `N17-11 · N17-10 current — 확정은 지금 본문 그대로 · intent_id NULL · 새 표 행 0`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val r = confirmHttp(hold, x, "pay-$x")
        assertEquals(200, r.status, "$r")
        assertLegacyBody(r, CONFIRM_KEYS, "current 확정 200")
        assertTrue(reservation(r.long("reservationId")).intentIdNull)
        assertEquals("HOLD", reservation(r.long("reservationId")).origin, "origin 기본 HOLD(JPA 경로에서도 열 기본값)")

        val other = holdOk(createSeat(2).id!!, x)
        clock.set(expiresAtOf(other).plusSeconds(1))
        val rej = confirmHttp(other, x, "pay-$x")
        assertEquals(409, rej.status, "$rej")
        assertLegacyBody(rej, ERROR_KEYS_17, "current 거부")
        assertEquals(0, intentCount())
        assertEquals(0, rejectionCount())
        assertEquals(0, taskCount())
    }
}
