package com.jun.labs.seatreservation.service

import com.jun.labs.seatreservation.service.impl.drift.ReconcileProperties
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
    /** 측정 전용 장치(ADR-012 §4.2.7 — `seat.hold.loadtest.*`). 기본 전부 끔 — loadtest 프로필에서만 쓴다(기동 검사). */
    val loadtest: Loadtest = Loadtest(),
) {
    data class Loadtest(
        /** 측정 전용 앱 경로 주입(ADR-012 §4.2.7) — 선점·만료 경로 안에서 고른 쌍의 cnt를 일부러 틀리게 쓴다 */
        val injectApp: InjectApp = InjectApp(),
    )

    data class InjectApp(
        val enabled: Boolean = false,
        /** 쌍 선택: (scheduleId × 31 + userId) mod every == 0 */
        val every: Int = 50,
        /** 주입할 쌍 수 상한(원자적 개수) */
        val maxPairs: Int = 60,
        val paths: Set<InjectPath> = setOf(InjectPath.HOLD, InjectPath.EXPIRY),
    )

    /** 주입 경로 — hold(선점 증가 직후 ±1 · AH/AL) · expiry(A 가드 감소 직후 +1 · AE) */
    enum class InjectPath { HOLD, EXPIRY }

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
        /** 대조(C·F) — 타입은 reconcile-core [ReconcileProperties](ADR-012 §4.2.1). 키·configprops 경로는 그대로 `counter-drift.reconcile.*`. */
        val reconcile: ReconcileProperties = ReconcileProperties(),
        val cleanup: Cleanup = Cleanup(),
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
}
