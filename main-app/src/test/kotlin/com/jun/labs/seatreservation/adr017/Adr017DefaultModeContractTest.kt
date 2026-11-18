package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.service.CompensationMode
import com.jun.labs.seatreservation.service.CompensationProperties
import com.jun.labs.seatreservation.service.PaymentProperties
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.payment.CompensationScheduling
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ADR-017 §결정(2026-11-18 사용자 채택 R) 기본값 교체 계약 — 보상 키(seat.compensation.mode)를 주지 않으면 refund(R 즉시 환불)로 뜬다.
 * 명세: docs/plans/2026-11-18/adr-017-default-switch/requirement-spec.md(§1 · §2 N1 · N2 · §5 ①~⑤).
 *   N1 코드 기본값(CompensationProperties)과 application.yml이 같은 값(REFUND) — 한쪽만 바뀌면 띄우는 방식에 따라 기본이 갈린다.
 *   N2 모드 ≠ none ⇒ PG base-url 필수 — 자동으로 none으로 내려가지 않고 기동을 거부하며 `seat.compensation.mode=none` 명시를 안내한다.
 * Spring 컨텍스트 없이 기동 검사(CompensationScheduling.checkConfig)를 직접 부른다.
 */
class Adr017DefaultModeContractTest {

    /** SeatHoldProperties는 ttl · maxPerUser · expiryInterval이 필수 — 나머지는 코드 기본값 */
    private val hold = SeatHoldProperties(ttl = Duration.ofMinutes(5), maxPerUser = 4, expiryInterval = Duration.ofSeconds(10))
    private val noPg = PaymentProperties()
    private val withPg = PaymentProperties(pg = PaymentProperties.Pg(baseUrl = "http://localhost:8104"))

    @Test
    fun `① 코드 기본값 — 보상 모드 REFUND`() {
        assertEquals(CompensationMode.REFUND, CompensationProperties().mode, "코드 기본값")
    }

    @Test
    fun `② application_yml 바인딩 — 보상 모드 REFUND(코드 기본값과 같은 값)`() {
        val sources = YamlPropertySourceLoader().load("application.yml", ClassPathResource("application.yml"))
        val bound = Binder(ConfigurationPropertySources.from(sources))
            .bind("seat.compensation", CompensationProperties::class.java).get()
        assertEquals(CompensationMode.REFUND, bound.mode, "application.yml")
        assertEquals(CompensationProperties().mode, bound.mode, "코드 기본값 = application.yml")
    }

    @Test
    fun `③ 기본 설정 + PG 주소 없음 — 기동 거부 · mode=none 명시 안내`() {
        assertTrue(noPg.pg.baseUrl.isNullOrBlank(), "전제: PG 주소 없음")
        val e = assertThrows<IllegalStateException> {
            CompensationScheduling.checkConfig(CompensationProperties(), noPg, SeatConfirmProperties(), hold)
        }
        assertTrue(e.message!!.contains("seat.compensation.mode=none"), "거부 메시지에 none 명시 안내: ${e.message}")
    }

    @Test
    fun `④ 기본 설정 + PG 주소 있음 — 기동 통과`() {
        assertDoesNotThrow {
            CompensationScheduling.checkConfig(CompensationProperties(), withPg, SeatConfirmProperties(), hold)
        }
    }

    @Test
    fun `⑤ mode none 명시 + PG 주소 없음 — 기동 통과`() {
        assertDoesNotThrow {
            CompensationScheduling.checkConfig(CompensationProperties(mode = CompensationMode.NONE), noPg, SeatConfirmProperties(), hold)
        }
    }
}
