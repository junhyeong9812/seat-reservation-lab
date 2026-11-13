package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * ADR-015 확정 원자성 손잡이(`seat.confirm.*` — §설계-5). 기본 = `conditional-extend`(④ + ⓒ — ADR-016 §결정 2026-11-10 · W 611초 · θ 1초 · M 1초 · 그 전 기본 `conditional` — ADR-015 §결정 2026-11-06). `current`(JPA 확정 + JPA 배치)는 명시해야 쓴다.
 * 조합 규칙(규칙 1~7 · §설계-10 T1 · T2 · T8)은 기동 검사(HoldStrategyStartupCheck)가 본다 — 조합 밖이면 마이그레이션 앞에서 거부한다.
 */
@ConfigurationProperties("seat.confirm")
data class SeatConfirmProperties(
    /** 확정 방식. 모르는 값은 바인딩 실패(조용히 current로 돌지 않는다). `mode`는 A 만료 배치도 고른다(current = JPA 배치 · 그 밖 = A′ — Q15) */
    val mode: ConfirmMode = ConfirmMode.CONDITIONAL_EXTEND,
    /** W — ④ 연장 뒤 남는 시간. 기본 611초(ADR-016 §결정 — 측정 전 고정 값 · `model16 --choose`). 0이면 ④는 기동 거부(규칙 4 · T6) */
    val paymentWindow: Duration = Duration.ofSeconds(611),
    /**
     * θ — 결제 시작을 받는 최소 남은 시간(ADR-016 설계-9.4 · 옛 키 `extend-min-remaining`을 대신한다 — 옛 키가 있으면 기동 거부).
     * ① payment-start 확인(`expires_at − :now ≤ θ`면 409)과 ④ 연장(too_late)이 같은 값을 쓴다. 0 ≤ 값(④는 < payment-window — 규칙 4) · < seat.hold.ttl(M16-6). 기본 1초(ADR-016 §결정)
     */
    val paymentMinRemaining: Duration = Duration.ofSeconds(1),
    /** M — 결제 마감 여유(ADR-016 설계-3.3 · 9.3): `payBy = expiresAt − M`. 0 ≤ M ≤ θ(① · ④). 기본 1초(ADR-016 §결정) */
    val payByMargin: Duration = Duration.ofSeconds(1),
    /** 측정 전용 장치 — loadtest 프로필에서만(규칙 3 · T8 모드 무관) */
    val loadtest: Loadtest = Loadtest(),
    /**
     * 새 모드를 V12 인덱스 없이 띄우는 유일한 길(재현 테스트 'V12 없음' 판 — §설계-8.1). test · loadtest 프로필에서만 받는다(T1 — 그 밖은 기동 거부).
     * 인덱스가 있으면 아무 일도 하지 않는다. 기본 false.
     */
    val uniqueIndexOptional: Boolean = false,
) {
    data class Loadtest(
        /** 확정 지연 주입(§설계-8.2). 기본 0 = 없음 */
        val delay: Duration = Duration.ZERO,
    )
}

/** ADR-015 확정 방식(§설계-3) — `seat.confirm.mode` */
enum class ConfirmMode {
    /** 옛 코드 그대로(JPA 확정 · JPA 배치) — 되돌림 · 비교용(B·C·D · 기본 밖 조합 · V12 앞 기준선은 이것을 명시) */
    CURRENT,
    /** ① 조건부 문장 — ADR-015 §결정의 기본(2026-11-06 ~ ADR-016 §결정 2026-11-10) */
    CONDITIONAL,
    /** ② 비관락(FOR UPDATE 뒤 확인 · 전환) */
    PESSIMISTIC,
    /** ③ 낙관락(좌석 버전 — V14 트리거) */
    OPTIMISTIC,
    /** ④ 결제 중 홀드 연장 + ① — 기본(ADR-016 §결정 2026-11-10) */
    CONDITIONAL_EXTEND,
    ;

    /**
     * ADR-016 결제 시작 모드(설계-9.2 · 9.3) — payment-start 등록 · 선점 201의 결제 마감 필드 · 기동 검사 `M ≤ θ`가 모두 이 집합으로 갈린다(한 곳에서만 정한다).
     * current · ② · ③은 밖(N16-7).
     */
    val paymentStartEnabled: Boolean get() = this == CONDITIONAL || this == CONDITIONAL_EXTEND
}
