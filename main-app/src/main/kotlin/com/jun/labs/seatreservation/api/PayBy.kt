package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.service.SeatConfirmProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * ADR-016 결제 마감 시각(설계-3.3 · 9.3 · N16-3): `payBy = expiresAt − M`(M = `seat.confirm.pay-by-margin`) ·
 * `payWithinSeconds = max(0, ⌊(payBy − now) / 1초⌋)` — now = 응답을 만드는 순간의 앱 Clock(판정 시각보다 늦어 작게 나오는 쪽 — 보수적).
 * 상대값은 클라이언트 시계 차를 피하려고 함께 준다. 서버는 알려 주기만 하고, 강제는 결제 쪽(PG 결제 제한 시간)이 한다.
 * ① · ④ 모드에서만 붙인다([enabled]) — current · ② · ③의 응답은 그대로(N16-7).
 */
@Component
class PayBy(
    private val confirm: SeatConfirmProperties,
    private val clock: Clock,
) {
    val enabled: Boolean get() = confirm.mode.paymentStartEnabled

    fun of(expiresAt: Instant): Deadline {
        val payBy = expiresAt.minus(confirm.payByMargin)
        return Deadline(payBy, maxOf(0L, Duration.between(clock.instant(), payBy).seconds))
    }

    data class Deadline(val payBy: Instant, val payWithinSeconds: Long)
}
