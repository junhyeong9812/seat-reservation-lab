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
    /**
     * ADR-009 카운터 어긋남 후보(A·C·F)와 쿼터 행 정리. 기본 = ADR-009 결정(명세 §12) A 켬 · C 켬 · 정리 ① schedule(B·F는 기본 아님).
     * 카운터 계열이 아닌 매수 방식(none·quota·advisory 등)은 기동 검사가 이 기본값을 거부한다 — 세 키를 명시해 끈다(ADR-008 none 고정과 같은 방식). counter-trigger(B)는 A를, counter는 정리를 끈다.
     */
    val counterDrift: CounterDrift = CounterDrift(),
) {
    data class SalesClose(
        /** 끄면 회차 조회 문장 자체가 없다(마감 비용 분리 측정용 — 명세 §10 D2). */
        val enabled: Boolean = true,
    )

    data class CounterDrift(
        /** 후보 A — 만료 배치에서 감소가 실패한 쌍만 그 자리에서 실제 값으로 복구한다. 기본 켬(ADR-009 결정). */
        val expiryRepair: Boolean = true,
        /**
         * 업무 앱 쪽 표시: 별도 대조 프로세스(F — reconciler 프로필)가 이 DB를 대조한다. true이고 counter-trigger가 아니면 A가 필수(기동 검사).
         * F 프로세스 자신의 기동 검사로는 업무 앱의 A를 볼 수 없어서 둔다(코드 루프 1 L1-18). 하네스가 F 조건에서 업무 앱에 켠다.
         */
        val externalReconciler: Boolean = false,
        val reconcile: Reconcile = Reconcile(),
        val cleanup: Cleanup = Cleanup(),
    )

    data class Reconcile(
        /** 후보 C — 앱 안 청크 대조(F는 reconciler 프로필이 켠다). 기본 켬(ADR-009 결정 — C는 A가 필수다, 기동 검사). */
        val enabled: Boolean = true,
        val interval: Duration = Duration.ofSeconds(60),
        /** 쌍 복구 트랜잭션의 `SET LOCAL lock_timeout`. */
        val lockTimeout: Duration = Duration.ofSeconds(1),
        /** 쿼터 행 주도 청크 크기(쌍 수 — 설계 E1 k = 1000). */
        val chunkSize: Int = 1_000,
        /** 행 없음 갈래 청크 크기(홀드·예약 id 범위 폭 — 설계 E1 m = 10000). */
        val orphanChunkSize: Int = 10_000,
        /** ① 쿼터 행 청크의 탐지 쿼리 모양(ADR-012 R8 · §4.1). 기본 one-shot = 지금 한 문장(상관 서브쿼리) — ②(행 없음 갈래)·복구는 모양과 무관하다. */
        val detectShape: DetectShape = DetectShape.ONE_SHOT,
    )

    data class Cleanup(
        /** 기본 schedule = 정리 ①(ADR-009 결정 — 판매 마감 켬이 전제, 기동 검사). */
        val mode: CleanupMode = CleanupMode.SCHEDULE,
        val interval: Duration = Duration.ofSeconds(60),
        /** cnt = 0 정리(②)의 청크 크기(쿼터 행 수). */
        val chunkSize: Int = 1_000,
    )

    /** 쿼터 행 정리 방식(명세 §1-3): none · schedule(① 마감 뒤 회차 단위) · zero(② cnt = 0 행). */
    enum class CleanupMode { NONE, SCHEDULE, ZERO }

    /**
     * ① 쿼터 행 청크 탐지 모양(ADR-012 §4.1): one-shot(한 문장 — 자동 커밋) · split(쿼터 행 k개 · 그 k명의 홀드 · 확정 예약을 행으로 읽어 앱이 셈) ·
     * group(같은 대상을 테이블별 `GROUP BY user_id` 개수로 읽어 앱이 합침). split·group은 탐지 전용 읽기 전용 REPEATABLE READ 트랜잭션 하나(빌림 1).
     */
    enum class DetectShape { ONE_SHOT, SPLIT, GROUP }
}
