package com.jun.labs.seatreservation.adr016fix

import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategyStartupCheck
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * ADR-016 코드 리뷰 루프 2 RC2-3 fix verification: 기동 검사 `M ≤ θ`는 결제 시작 모드(① · ④ — [ConfirmMode.paymentStartEnabled])에만 걸린다(명세 §1-6).
 * current · ② · ③은 M > θ여도 설정 검사를 통과한다 — 모드 집합이 넓어지거나 좁아지는 회귀를 잡는다. 설정 검사([HoldStrategyStartupCheck.checkConfig])만 부른다.
 */
class Adr016StartupScopeFixTest {

    private val hold = SeatHoldProperties(ttl = Duration.ofMinutes(5), maxPerUser = 2, expiryInterval = Duration.ofSeconds(10))
    private val margin = Duration.ofSeconds(10)

    private fun confirm(mode: ConfirmMode) = SeatConfirmProperties(
        mode = mode, payByMargin = margin,
        paymentWindow = if (mode == ConfirmMode.CONDITIONAL_EXTEND) Duration.ofMinutes(2) else Duration.ZERO,
    )

    @Test
    fun `M 초과 θ — 결제 시작 모드만 거부 · 나머지는 통과`() {
        assertEquals(setOf(ConfirmMode.CONDITIONAL, ConfirmMode.CONDITIONAL_EXTEND), ConfirmMode.entries.filter { it.paymentStartEnabled }.toSet())
        for (mode in ConfirmMode.entries) {
            if (mode.paymentStartEnabled) {
                val e = assertThrows<IllegalStateException>("[$mode]") { HoldStrategyStartupCheck.checkConfig(hold, null, confirm(mode)) }
                kotlin.test.assertTrue("pay-by-margin" in e.message!!, "[$mode] ${e.message}")
            } else {
                assertDoesNotThrow("[$mode]") { HoldStrategyStartupCheck.checkConfig(hold, null, confirm(mode)) }
            }
        }
    }

    private fun assertEquals(expected: Any, actual: Any) = kotlin.test.assertEquals(expected, actual)
}
