package com.jun.labs.seatreservation.adr016

import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategyStartupCheck
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertTrue

/**
 * ADR-016 측정 명세 M16-6: 결제 시작 모드(① · ④)는 θ(`seat.confirm.payment-min-remaining`) < TTL(`seat.hold.ttl`)이어야 기동한다.
 * θ ≥ TTL이면 새 홀드도 남은 시간이 θ 이하라 ①은 전부 insufficient · ④는 전부 too_late가 된다. current · ② · ③은 θ를 쓰지 않아 무관 · 기본값(θ 0)은 통과.
 * 설정 검사([HoldStrategyStartupCheck.checkConfig])만 부른다.
 */
class Adr016ThetaTtlStartupTest {

    private val ttl = Duration.ofMinutes(5)
    private val hold = SeatHoldProperties(ttl = ttl, maxPerUser = 2, expiryInterval = Duration.ofSeconds(10))

    // ④ 규칙 4(θ < payment-window)가 먼저 걸리지 않게 창을 TTL보다 크게 둔다
    private fun confirm(mode: ConfirmMode, theta: Duration) = SeatConfirmProperties(
        mode = mode, paymentMinRemaining = theta,
        paymentWindow = if (mode == ConfirmMode.CONDITIONAL_EXTEND) Duration.ofMinutes(20) else Duration.ZERO,
    )

    @Test
    fun `θ = TTL — 결제 시작 모드는 거부 · 메시지에 두 키`() {
        for (mode in ConfirmMode.entries.filter { it.paymentStartEnabled }) {
            val e = assertThrows<IllegalStateException>("[$mode]") { HoldStrategyStartupCheck.checkConfig(hold, null, confirm(mode, ttl)) }
            assertTrue("payment-min-remaining" in e.message!! && "seat.hold.ttl" in e.message!!, "[$mode] ${e.message}")
        }
    }

    @Test
    fun `θ 초과 TTL도 거부`() {
        for (mode in ConfirmMode.entries.filter { it.paymentStartEnabled }) {
            assertThrows<IllegalStateException>("[$mode]") { HoldStrategyStartupCheck.checkConfig(hold, null, confirm(mode, ttl.plusSeconds(1))) }
        }
    }

    @Test
    fun `θ = TTL − 1ms와 기본 θ 0은 통과`() {
        for (mode in ConfirmMode.entries.filter { it.paymentStartEnabled }) {
            assertDoesNotThrow("[$mode]") { HoldStrategyStartupCheck.checkConfig(hold, null, confirm(mode, ttl.minusMillis(1))) }
            assertDoesNotThrow("[$mode] θ 0") { HoldStrategyStartupCheck.checkConfig(hold, null, confirm(mode, Duration.ZERO)) }
        }
    }

    @Test
    fun `결제 시작 모드 밖은 θ ≥ TTL이어도 이 검사로 거부하지 않는다`() {
        for (mode in ConfirmMode.entries.filterNot { it.paymentStartEnabled }) {
            assertDoesNotThrow("[$mode]") { HoldStrategyStartupCheck.checkConfig(hold, null, confirm(mode, ttl.plusSeconds(60))) }
        }
    }
}
