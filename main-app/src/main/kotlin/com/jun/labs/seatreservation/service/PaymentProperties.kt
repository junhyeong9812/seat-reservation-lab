package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** ADR-017 결제 연동 손잡이(`seat.payment.*` — 명세 부록 A). PG 주소가 없으면 PG 호출은 0이다(N17-12) */
@ConfigurationProperties("seat.payment")
data class PaymentProperties(
    val pg: Pg = Pg(),
    /** 좌석당 고정 금액(가격 모델은 설계 밖 — 설계-3) */
    val amount: Long = 10_000,
) {
    data class Pg(
        val baseUrl: String? = null,
        val connectTimeout: Duration = Duration.ofSeconds(1),
        val readTimeout: Duration = Duration.ofSeconds(3),
    )

    val pgEnabled: Boolean get() = !pg.baseUrl.isNullOrBlank()
}
