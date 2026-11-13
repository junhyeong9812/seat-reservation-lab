package com.jun.labs.seatreservation.adr016

import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategyStartupCheck
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.time.Duration
import kotlin.test.assertTrue

/**
 * ADR-016 2단계 명세 §8 가정 1(N16-6): θ 옛 키를 프로퍼티 · 환경 변수(relaxed) 두 형태 모두에서 찾아 기동 검사가 거부한다.
 * 컨텍스트 없이 설정 검사([HoldStrategyStartupCheck.checkConfig])만 부른다 — 바인딩 규칙은 Spring Binder 그대로.
 */
class Adr016AssumptionProbeTest {

    private fun env(vararg sources: org.springframework.core.env.PropertySource<*>) = StandardEnvironment().apply {
        propertySources.toList().forEach { propertySources.remove(it.name) } // 실제 OS 환경 변수 · JVM 속성 배제
        sources.forEach { propertySources.addLast(it) }
    }

    private fun check(environment: StandardEnvironment) =
        HoldStrategyStartupCheck.checkConfig(SeatHoldProperties(ttl = Duration.ofMinutes(5), maxPerUser = 2, expiryInterval = Duration.ofSeconds(10)), environment, SeatConfirmProperties())

    @Test
    fun `옛 키 — 프로퍼티 형태면 거부`() {
        val e = assertThrows<IllegalStateException> {
            check(env(MapPropertySource("t", mapOf("seat.confirm.extend-min-remaining" to "5s"))))
        }
        assertTrue("payment-min-remaining" in e.message!!, e.message)
    }

    @Test
    fun `옛 키 — 환경 변수 형태면 거부`() {
        assertThrows<IllegalStateException> {
            check(env(SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, mapOf("SEAT_CONFIRM_EXTENDMINREMAINING" to "5s"))))
        }
    }

    @Test
    fun `옛 키가 없으면 통과(대조)`() {
        assertDoesNotThrow { check(env(MapPropertySource("t", mapOf("seat.confirm.payment-min-remaining" to "5s")))) }
    }
}
