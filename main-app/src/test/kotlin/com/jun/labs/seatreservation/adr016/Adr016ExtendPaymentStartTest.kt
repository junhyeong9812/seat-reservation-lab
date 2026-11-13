package com.jun.labs.seatreservation.adr016

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.M_EXTEND
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.P_WINDOW
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.adr015.TTL
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * ④ conditional-extend(명세 §1 · 1 · 4 · N16-3 · N16-8) — window 2분 · θ(새 키 payment-min-remaining) 5초 · M 3초. 옛 키는 주지 않는다.
 * 연장 판정 · extend 지표 · 진단 순서는 ADR-015 그대로(키 이름만 바뀜) · 응답에 payBy · payWithinSeconds · extended가 더해진다:
 *   extended(연장함) · already(이미 연장) → extended = true · not_needed → false.
 * 홀드 at NOW → e = NOW + 5분. N16-5 묶음(확정 지표)도 ④에서 여기서 돈다.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        M_EXTEND, P_WINDOW, P16_THETA_5S, P16_MARGIN_3S,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr016ExtendPaymentStartTest : Adr016ConfirmMetricsScenarios("conditional-extend") {

    private fun assert200(r: Resp, hold: Long, expiresAt: Instant, now: Instant, extended: Boolean, why: String) {
        assertEquals(200, r.status, "$why: $r")
        assertEquals(PAY_START_KEYS, r.keys(), "$why — 200 본문 키: $r")
        assertEquals(hold, r.long("holdId"), why)
        assertEquals(expiresAt, r.instant("expiresAt"), "$why — expiresAt")
        assertEquals(expiresAt.minus(MARGIN_3), r.instant("payBy"), "$why — payBy = expiresAt − M(N16-3)")
        assertEquals(expectedPayWithin(expiresAt.minus(MARGIN_3), now), r.long("payWithinSeconds"), "$why — payWithinSeconds")
        assertEquals(extended, r.bool("extended"), "$why — extended")
    }

    /** [before] 대비 extend{[result]}만 +1 */
    private fun assertExtendDelta(before: Map<String, Double>, result: String, why: String) {
        assertEquals(before.mapValues { (k, v) -> if (k == result) v + 1 else v }, extendCounts(), "$why — $M16_EXTEND{result=$result}만 +1(N16-8)")
    }

    @Test
    fun `N16-3 — ④ 선점 201도 payBy · payWithinSeconds를 더한다`() {
        val r = holdHttp(createSeat(1).id!!, x)
        assertEquals(201, r.status, "$r")
        assertEquals(HOLD_KEYS_PAY, r.keys(), "④ 선점 201 본문 키: $r")
        assertEquals(NOW.plus(TTL), r.instant("expiresAt"))
        assertEquals(r.instant("expiresAt").minus(MARGIN_3), r.instant("payBy"))
        assertEquals(297L, r.long("payWithinSeconds"), "5분 − 3초")
    }

    @Test
    fun `N16-8 · N16-3 — extended 200(extended = true · expiresAt = now + window) → already 200(true) · payBy 지나면 payWithinSeconds 0`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val t1 = NOW.plus(Duration.ofMinutes(4)) // 남은 1분 < window · > θ → 연장
        clock.set(t1)
        var e0 = extendCounts()
        val r1 = paymentStart(hold, x)
        val ext = t1.plus(WINDOW_2M)
        assert200(r1, hold, ext, t1, true, "extended")
        assertEquals(117L, r1.long("payWithinSeconds"), "2분 − 3초")
        assertEquals(ext, expiresAtOf(hold)); assertEquals(t1, extendedAtOf(hold))
        assertExtendDelta(e0, "extended", "첫 연장")

        val t2 = NOW.plus(Duration.ofMinutes(5)) // 이미 연장 · payBy 전
        clock.set(t2)
        e0 = extendCounts()
        val r2 = paymentStart(hold, x)
        assert200(r2, hold, ext, t2, true, "already(payBy 전)")
        assertEquals(57L, r2.long("payWithinSeconds"))
        assertExtendDelta(e0, "already", "already")

        val t3 = ext.minusSeconds(1) // payBy(ext − 3초) 지남 · 아직 만료 전 — already는 θ 검사 없음(설계-9.2 재시도)
        clock.set(t3)
        e0 = extendCounts()
        val r3 = paymentStart(hold, x)
        assert200(r3, hold, ext, t3, true, "already(payBy 지남)")
        assertEquals(0L, r3.long("payWithinSeconds"), "payBy가 지나면 0(max(0, …) — 음수 아님)")
        assertTrue(r3.instant("payBy").isBefore(t3), "전제 — payBy < now")
        assertExtendDelta(e0, "already", "already · payBy 지남")
        assertEquals(ext, expiresAtOf(hold), "다시 늘지 않는다")
    }

    @Test
    fun `N16-8 — not_needed 200은 extended = false · 연장 · 표지 없음`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val e = expiresAtOf(hold)
        val t = NOW.plus(Duration.ofMinutes(1)) // 남은 4분 ≥ window
        clock.set(t)
        val e0 = extendCounts()
        val r = paymentStart(hold, x)
        assert200(r, hold, e, t, false, "not_needed")
        assertEquals(237L, r.long("payWithinSeconds"), "4분 − 3초")
        assertEquals(e, expiresAtOf(hold)); assertNull(extendedAtOf(hold))
        assertExtendDelta(e0, "not_needed", "not_needed")
    }

    @Test
    fun `N16-8 — too_late 문턱은 새 키 θ — 남은 = θ 409 HOLD_EXPIRED(too_late) · θ+1ms면 연장`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val e = expiresAtOf(hold)

        clock.set(e.minus(THETA_5))
        var e0 = extendCounts()
        assertRejected(paymentStart(hold, x), 409, "HOLD_EXPIRED", "④ 남은 = θ(5초)")
        assertExtendDelta(e0, "too_late", "남은 = θ")
        assertEquals(e, expiresAtOf(hold)); assertNull(extendedAtOf(hold))

        val t = e.minus(THETA_5).minusMillis(1)
        clock.set(t)
        e0 = extendCounts()
        val r = paymentStart(hold, x)
        assert200(r, hold, t.plus(WINDOW_2M), t, true, "④ 남은 θ+1ms")
        assertExtendDelta(e0, "extended", "남은 θ+1ms")
    }

    @Test
    fun `N16-8 — 진단 404 · 403 · 만료 409는 그대로(extend 지표를 올리지 않는다 · 행 무변경)`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val e = expiresAtOf(hold)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val e0 = extendCounts()
        val before = rowsSnapshot()
        assertRejected(paymentStart(NO_SUCH_HOLD, x), 404, "HOLD_NOT_FOUND", "④ 없음")
        assertRejected(paymentStart(hold, y), 403, "HOLD_NOT_OWNED", "④ 남의 것")
        clock.set(e)
        assertRejected(paymentStart(hold, x), 409, "HOLD_EXPIRED", "④ 만료(expires_at = :now)")
        clock.set(e.plusSeconds(10))
        assertRejected(paymentStart(hold, y), 403, "HOLD_NOT_OWNED", "④ 남의 것이 만료보다 먼저")
        assertEquals(e0, extendCounts(), "진단 거부는 extend 지표를 올리지 않는다(ADR-015 그대로)")
        assertEquals(before, rowsSnapshot())
        assertFalse(holdExists(NO_SUCH_HOLD))
    }
}
