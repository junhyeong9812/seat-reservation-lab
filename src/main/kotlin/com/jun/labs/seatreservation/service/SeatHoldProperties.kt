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
    /** 같은 사용자 동시 요청의 매수 제어(ADR-005). 기본 = ADR-008 결정 counter-upsert-precheck(none = 조회 후 비교 — 동시 요청은 못 막음). */
    val limitStrategy: UserLimitStrategyType = UserLimitStrategyType.COUNTER_UPSERT_PRECHECK,
    /** ADR-004 실험 장치: 1인 2매 확인 뒤·상태 전이 직전에 트랜잭션을 쥔 채 기다리는 시간(외부 호출 흉내). 기본 0 = 기준선. */
    val criticalSectionDelay: Duration = Duration.ZERO,
    /** ADR-009 판매 마감(회차 starts_at). 기본 켬(도메인 규칙). */
    val salesClose: SalesClose = SalesClose(),
    /** ADR-009 카운터 어긋남 후보(A·C·F)와 쿼터 행 정리. 기본 모두 끔 = ADR-008 동작 그대로. */
    val counterDrift: CounterDrift = CounterDrift(),
) {
    data class SalesClose(
        /** 끄면 회차 조회 문장 자체가 없다(마감 비용 분리 측정용 — 명세 §10 D2). */
        val enabled: Boolean = true,
    )

    data class CounterDrift(
        /** 후보 A — 만료 배치에서 감소가 실패한 쌍만 그 자리에서 실제 값으로 복구한다. */
        val expiryRepair: Boolean = false,
        val reconcile: Reconcile = Reconcile(),
        val cleanup: Cleanup = Cleanup(),
    )

    data class Reconcile(
        /** 후보 C — 앱 안 청크 대조(F는 reconciler 프로필이 켠다). */
        val enabled: Boolean = false,
        val interval: Duration = Duration.ofSeconds(60),
        /** 쌍 복구 트랜잭션의 `SET LOCAL lock_timeout`. */
        val lockTimeout: Duration = Duration.ofSeconds(1),
        /** 쿼터 행 주도 청크 크기(쌍 수 — 설계 E1 k = 1000). */
        val chunkSize: Int = 1_000,
        /** 행 없음 갈래 청크 크기(홀드·예약 id 범위 폭 — 설계 E1 m = 10000). */
        val orphanChunkSize: Int = 10_000,
    )

    data class Cleanup(
        val mode: CleanupMode = CleanupMode.NONE,
        val interval: Duration = Duration.ofSeconds(60),
        /** cnt = 0 정리(②)의 청크 크기(쿼터 행 수). */
        val chunkSize: Int = 1_000,
    )

    /** 쿼터 행 정리 방식(명세 §1-3): none · schedule(① 마감 뒤 회차 단위) · zero(② cnt = 0 행). */
    enum class CleanupMode { NONE, SCHEDULE, ZERO }
}
