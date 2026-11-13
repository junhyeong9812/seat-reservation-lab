package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.M_CONDITIONAL
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.TTL
import com.jun.labs.seatreservation.adr016.Adr016SqlTapConfig
import com.jun.labs.seatreservation.adr016.MARGIN_10
import com.jun.labs.seatreservation.adr016.P16_MARGIN_10S
import com.jun.labs.seatreservation.adr016.P16_THETA_30S
import com.jun.labs.seatreservation.adr016.THETA_30
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * task 01 · 02 — ① conditional(θ 30초 · M 10초 — Adr016ConditionalPaymentStartTest와 같은 애너테이션 · 컨텍스트 공유).
 * ①의 판정(확인만 · 연장 없음)은 그대로이고 첫 200에서 intent를 만든다(설계-4.2 · 명세 §2 'ADR-016 N16-1 대체').
 * 홀드 at NOW → e = NOW + 5분 · payBy = e − 10초.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        M_CONDITIONAL, P16_THETA_30S, P16_MARGIN_10S,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr016SqlTapConfig::class)
class Adr017ConditionalPaymentStartTest : Adr017Base() {

    @Test
    fun `I13 ① — 200에 paymentUid · intent OPEN · pay_by = e − M · 홀드는 그대로(연장 없음) · 재호출 같은 uid · 같은 payBy`() {
        val seat = createSeat(1)
        val hold = holdOk(seat.id!!, x)
        val e = expiresAtOf(hold)
        assertEquals(NOW.plus(TTL), e)

        val (uid, r) = startOk(hold, x)
        assertEquals(PAY_START_KEYS_17, r.keys(), "① 200 본문 = 기존 키 + paymentUid: $r")
        assertTrue(isUuid(uid), "서버 발급 UUID: $uid")
        assertFalse(r.bool("extended"), "①은 연장 없음 그대로")
        assertEquals(e, r.instant("expiresAt"))
        assertEquals(e.minus(MARGIN_10), r.instant("payBy"))
        assertEquals(e, expiresAtOf(hold), "① 홀드 무변경(연장 없음)")

        val i = intentOrFail(uid)
        assertEquals(OPEN, i.state)
        assertEquals(hold, i.holdId)
        assertEquals(x, i.userId)
        assertEquals(schedule.id, i.scheduleId)
        assertEquals(seat.id, i.seatId)
        assertEquals("A", i.section)
        assertEquals(e.minus(MARGIN_10), i.payBy, "pay_by = 응답 payBy")

        clock.set(NOW.plus(Duration.ofMinutes(2))) // 여전히 남은 시간 > θ
        val (uid2, r2) = startOk(hold, x)
        assertEquals(uid, uid2, "같은 hold = 같은 uid")
        assertEquals(r.instant("payBy"), r2.instant("payBy"), "같은 payBy")
        assertEquals(1, intentCount(), "intent 1행 그대로")
    }

    @Test
    fun `I13 ① — 409(남은 시간 ≤ θ · 만료) · 404 · 403 거부는 intent를 만들지 않는다`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val e = expiresAtOf(hold)

        assertEquals(403, paymentStart(hold, y).status)
        assertEquals(404, paymentStart(999_999_999L, x).status)
        clock.set(e.minus(THETA_30)) // 남은 = θ → 409 insufficient(ADR-016 판정 그대로)
        val insufficient = paymentStart(hold, x)
        assertEquals(409, insufficient.status, "$insufficient")
        assertEquals("HOLD_EXPIRED", insufficient.code)
        clock.set(e)
        assertEquals(409, paymentStart(hold, x).status)

        assertEquals(0, intentCount(), "거부된 payment-start는 intent 0")
    }

    @Test
    fun `N17-1 ① — intent uid 확정 성공 → intent CONFIRMED · reservation_id · 예약 intent_id 채움`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val (uid, _) = startOk(hold, x)
        clock.set(NOW.plus(Duration.ofMinutes(1)))
        val r = confirmHttp(hold, x, uid)
        assertEquals(200, r.status, "$r")
        assertIntentBodyNoTask(r, CONFIRM_KEYS, "① intent 확정 200")
        val rid = r.long("reservationId")
        val i = intentOrFail(uid)
        assertEquals(CONFIRMED, i.state)
        assertEquals(rid, i.reservationId)
        assertFalse(reservation(rid).intentIdNull, "intent 경로 예약은 intent_id를 가진다(설계-4.1)")
        assertEquals(0, taskCount(), "모드 none — 보상 작업 0")
    }
}
