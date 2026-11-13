package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ADR-017 보상 손잡이(`seat.compensation.*` — 설계-5). 기본 `none`(Q1 — 측정 뒤 결정 커밋에서 교체):
 * intent · 거부 기록 · 재생 · 안전망 탐지까지만 하고 보상 작업을 만들지 않는다.
 */
@ConfigurationProperties("seat.compensation")
data class CompensationProperties(
    val mode: CompensationMode = CompensationMode.NONE,
)

enum class CompensationMode {
    /** 보상 작업 없음 — 탐지 · 지표만(Q1) */
    NONE,
    /** R 즉시 환불 — 거부 = CLOSE 작업 */
    REFUND,
    /** D 승인 → 확정 → 매입 — 성공 = CAPTURE 작업 · 거부 = CLOSE 작업 */
    AUTH_CAPTURE,
    /** S 대체 좌석 — 거부 = 대체 배정 · 실패면 CLOSE 작업 */
    SUBSTITUTE,
    ;

    val createsTasks: Boolean get() = this != NONE
}
