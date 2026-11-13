package com.jun.labs.seatreservation.adr016

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.TTL
import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ADR-016 §결정(2026-11-10) 기본값 교체 계약 — 확정 키(seat.confirm.*)를 하나도 주지 않으면 ④ conditional-extend + ⓒ payBy로 뜬다:
 *   W(payment-window) 611초 · θ(payment-min-remaining) 1초 · M(pay-by-margin) 1초 — 측정 전 고정 값(ADR-016 §실험 결과 고정 값).
 * 코드 기본값(SeatConfirmProperties)과 application.yml이 같은 값이어야 한다(한쪽만 바뀌면 띄우는 방식에 따라 기본이 갈린다).
 * 범위 밖 구성(B·C·D · 기본 밖 조합 · 주입 · target 12 이하)의 current 명시 강제는 Adr015DefaultModeContractTest가 본다(규칙 그대로).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr016DefaultModeContractTest : Adr016Base() {

    @Autowired
    lateinit var bound: SeatConfirmProperties

    private fun assertDecided(p: SeatConfirmProperties, why: String) {
        assertEquals(ConfirmMode.CONDITIONAL_EXTEND, p.mode, "$why: 확정 모드")
        assertEquals(DEFAULT_W, p.paymentWindow, "$why: payment-window(W)")
        assertEquals(DEFAULT_THETA, p.paymentMinRemaining, "$why: payment-min-remaining(θ)")
        assertEquals(DEFAULT_M, p.payByMargin, "$why: pay-by-margin(M)")
    }

    @Test
    fun `기본값 — 코드 · application_yml · 기동 바인딩 모두 conditional-extend · W 611초 · θ 1초 · M 1초`() {
        assertDecided(SeatConfirmProperties(), "코드 기본값")
        val sources = YamlPropertySourceLoader().load("application.yml", ClassPathResource("application.yml"))
        assertDecided(Binder(ConfigurationPropertySources.from(sources)).bind("seat.confirm", SeatConfirmProperties::class.java).get(), "application.yml")
        assertDecided(bound, "확정 키 없이 띄운 앱")
    }

    @Test
    fun `확정 키 없음 — 선점 201 payBy = expiresAt − 1초 · 결제 시작은 now + 611초로 연장(extended = true)`() {
        val seat = createSeat(1).id!!
        val r = holdHttp(seat, x)
        assertEquals(201, r.status, "$r")
        assertEquals(HOLD_KEYS_PAY, r.keys(), "선점 201 본문 키: $r")
        assertEquals(NOW.plus(TTL), r.instant("expiresAt"))
        assertEquals(r.instant("expiresAt").minus(DEFAULT_M), r.instant("payBy"), "payBy = expiresAt − M")
        assertEquals(299L, r.long("payWithinSeconds"), "5분 − 1초")

        val hold = r.long("holdId")
        val t1 = NOW.plus(Duration.ofMinutes(4)) // 남은 1분 < W · > θ → 연장
        clock.set(t1)
        val p = paymentStart(hold, x)
        assertEquals(200, p.status, "$p")
        assertEquals(PAY_START_KEYS, p.keys(), "payment-start 200 본문 키: $p")
        val ext = t1.plus(DEFAULT_W)
        assertEquals(ext, p.instant("expiresAt"), "expiresAt = now + W")
        assertEquals(ext.minus(DEFAULT_M), p.instant("payBy"), "payBy = expiresAt − M")
        assertEquals(610L, p.long("payWithinSeconds"), "611초 − 1초")
        assertTrue(p.bool("extended"), "extended")
        assertEquals(ext, expiresAtOf(hold), "DB expires_at")
        assertEquals(t1, extendedAtOf(hold), "DB extended_at")
    }

    private companion object {
        val DEFAULT_W: Duration = Duration.ofSeconds(611)
        val DEFAULT_THETA: Duration = Duration.ofSeconds(1)
        val DEFAULT_M: Duration = Duration.ofSeconds(1)
    }
}
