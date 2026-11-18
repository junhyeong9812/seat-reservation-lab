package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * ADR-017 보상 손잡이(`seat.compensation.*` — 설계-4.4 · 4.5 · 5 · 명세 부록 A). 기본 `none`(Q1 — 측정 뒤 결정 커밋에서 교체):
 * intent · 거부 기록 · 재생 · 안전망 탐지까지만 하고 보상 작업을 만들지 않는다.
 */
@ConfigurationProperties("seat.compensation")
data class CompensationProperties(
    val mode: CompensationMode = CompensationMode.NONE,
    /** 워커 · 안전망 스케줄러(테스트는 끄고 runOnce를 직접 부른다) */
    val schedulerEnabled: Boolean = true,
    val worker: Worker = Worker(),
    val sweep: Sweep = Sweep(),
) {
    data class Worker(
        val interval: Duration = Duration.ofSeconds(1),
        val batch: Int = 50,
        /** 집은 작업의 리스 — 지나면 다른 워커가 다시 집는다(앱 죽음 · RD17-5) */
        val lease: Duration = Duration.ofSeconds(30),
        /** 재시도 분류의 시도 상한 — 넘으면 DEAD */
        val maxAttempts: Int = 10,
        val backoffInitial: Duration = Duration.ofSeconds(1),
        val backoffMax: Duration = Duration.ofSeconds(60),
    )

    data class Sweep(
        val interval: Duration = Duration.ofSeconds(5),
        val batch: Int = 200,
    )
}

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
