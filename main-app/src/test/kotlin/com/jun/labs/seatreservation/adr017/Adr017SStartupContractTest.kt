package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.adr015.MODE_KEY
import com.jun.labs.seatreservation.adr016.THETA_KEY
import com.jun.labs.seatreservation.adr016.WINDOW_KEY
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.Test
import kotlin.test.fail

/*
 * S 기동 검사 — 명세 부록 B 'S: 지원 매수 방식 = counter-upsert · counter-upsert-precheck(그 밖이면 기동 거부)' · 명세 §1-8(모드 ≠ none ⇒ base-url 필수 · 확정 ① 또는 ④).
 * 기동 = BootTestBase(실제 SpringApplication · 부팅마다 새 DB · OLD_DEFAULTS).
 *
 * 가정(근거): 기존 기동 검사가 이미 '확정 ① · ④ ⇒ 매수 counter-upsert-precheck'를 강제한다(HoldStrategyStartupCheck — ADR-015 설계-5 규칙 2) →
 *  지원 밖 매수 방식은 확정 current로만 뜰 수 있고, 그 조합에서 substitute는 §1-8(① · ④ 필수)로도 막힌다. 그래서 거부 원인을 한 규칙으로 가르지 않고
 *  '기동 검사(IllegalStateException)로 거부 · 바인딩 실패 아님'만 본다. 대조: 같은 매수 · 확정 조합에 모드 none은 뜬다(거부가 substitute 때문).
 *  PG 주소는 기동 때 연결하지 않는다고 본다(설계-4.2 '등록은 payment-start 때') — 닿지 않는 주소를 준다.
 */
class Adr017SStartupContractTest : BootTestBase() {

    private val pgUrl = PG_BASE_URL_KEY to "http://127.0.0.1:9"
    private val schedOff = "seat.compensation.scheduler-enabled" to "false"
    private val extend = mapOf<String, String?>(MODE_KEY to "conditional-extend", WINDOW_KEY to "2m", THETA_KEY to "5s")
    private val keywords = listOf("substitute", "compensation", "limit-strategy", "limitStrategy", "매수", "confirm")

    private fun boots(why: String, props: Map<String, String?>) {
        val err = bootFailure(freshDbUrl(), props)
        if (err != null) fail("$why: 떠야 하는데 실패 ${causesOf(err).map { "${it::class.simpleName}: ${it.message?.take(300)}" }}")
    }

    @Test
    fun `S 기동 — substitute + 기본 매수(counter-upsert-precheck) + 확정 ④ + base-url → 뜬다`() =
        boots("substitute 기본 조합", extend + mapOf(COMPENSATION_MODE_KEY to "substitute", pgUrl, schedOff))

    @Test
    fun `S 기동 — substitute + 매수 none → 거부 · 대조 같은 조합에 모드 none은 뜬다`() {
        val base = mapOf<String, String?>("seat.hold.limit-strategy" to "none", MODE_KEY to "current")
        boots("대조 — 매수 none + 확정 current + 모드 none", base + (COMPENSATION_MODE_KEY to "none"))
        assertStartupFailure(
            bootFailure(freshDbUrl(), base + mapOf(COMPENSATION_MODE_KEY to "substitute", pgUrl, schedOff)),
            "substitute + 매수 none(지원 밖 — 부록 B)", keywords,
        )
    }

    @Test
    fun `S 기동 — substitute + 매수 advisory-try → 거부`() {
        val base = mapOf<String, String?>("seat.hold.limit-strategy" to "advisory-try", MODE_KEY to "current")
        assertStartupFailure(
            bootFailure(freshDbUrl(), base + mapOf(COMPENSATION_MODE_KEY to "substitute", pgUrl, schedOff)),
            "substitute + 매수 advisory-try(지원 밖 — 부록 B)", keywords,
        )
    }
}
