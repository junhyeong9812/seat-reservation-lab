package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("seat.hold")
data class SeatHoldProperties(
    val ttl: Duration,
    val maxPerUser: Int,
    val expiryInterval: Duration,
    /** 같은 좌석 경합 제어(ADR-003). 기본 = ADR-003 결정 3b(NOWAIT). */
    val strategy: HoldStrategyType = HoldStrategyType.PESSIMISTIC_NOWAIT,
    /** 같은 사용자 동시 요청의 매수 제어(ADR-005). 기본 none = 조회 후 비교(동시 요청은 못 막음). */
    val limitStrategy: UserLimitStrategyType = UserLimitStrategyType.NONE,
    /** ADR-004 실험 장치: 1인 2매 확인 뒤·상태 전이 직전에 트랜잭션을 쥔 채 기다리는 시간(외부 호출 흉내). 기본 0 = 기준선. */
    val criticalSectionDelay: Duration = Duration.ZERO,
)
