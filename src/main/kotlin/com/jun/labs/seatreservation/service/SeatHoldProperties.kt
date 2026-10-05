package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("seat.hold")
data class SeatHoldProperties(
    val ttl: Duration,
    val maxPerUser: Int,
    val expiryInterval: Duration,
    val strategy: HoldStrategyType = HoldStrategyType.NONE,
    /** ADR-004 실험 장치: 1인 2매 확인 뒤·상태 전이 직전에 트랜잭션을 쥔 채 기다리는 시간(외부 호출 흉내). 기본 0 = 기준선. */
    val criticalSectionDelay: Duration = Duration.ZERO,
)
