package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * ADR-015 확정 원자성 손잡이(`seat.confirm.*` — §설계-5). 기본 = `conditional`(ADR-015 §결정 2026-11-06). `current`(JPA 확정 + JPA 배치)는 명시해야 쓴다.
 * 조합 규칙(규칙 1~7 · §설계-10 T1 · T2 · T8)은 기동 검사(HoldStrategyStartupCheck)가 본다 — 조합 밖이면 마이그레이션 앞에서 거부한다.
 */
@ConfigurationProperties("seat.confirm")
data class SeatConfirmProperties(
    /** 확정 방식. 모르는 값은 바인딩 실패(조용히 current로 돌지 않는다). `mode`는 A 만료 배치도 고른다(current = JPA 배치 · 그 밖 = A′ — Q15) */
    val mode: ConfirmMode = ConfirmMode.CONDITIONAL,
    /** ④ 연장 뒤 남는 시간. 기본 0 — ④는 이 키를 명시해야 기동한다(규칙 4 · T6 — 앱 기본값은 측정 task가 고정) */
    val paymentWindow: Duration = Duration.ZERO,
    /** ④ 연장을 받는 최소 남은 시간(0 ≤ 값 < payment-window — 규칙 4) */
    val extendMinRemaining: Duration = Duration.ZERO,
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
    /** ① 조건부 문장 — 기본(ADR-015 §결정) */
    CONDITIONAL,
    /** ② 비관락(FOR UPDATE 뒤 확인 · 전환) */
    PESSIMISTIC,
    /** ③ 낙관락(좌석 버전 — V14 트리거) */
    OPTIMISTIC,
    /** ④ 결제 중 홀드 연장 + ① */
    CONDITIONAL_EXTEND,
}
