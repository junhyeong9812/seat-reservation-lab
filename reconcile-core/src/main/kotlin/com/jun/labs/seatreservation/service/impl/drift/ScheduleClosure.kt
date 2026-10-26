package com.jun.labs.seatreservation.service.impl.drift

import java.time.Instant

/**
 * 회차 마감 판정식(ADR-009 판매 마감) — 순수 함수. 업무 앱의 `SalesCloseGate.isClosed`와 core의 대조 복구 마감 재확인이 함께 쓴다(ADR-012 §4.2.1).
 * `sales-close.enabled`·앱 Clock·도메인 오류는 여기 없다 — 호출자가 시각을 넘긴다.
 */
object ScheduleClosure {
    /** 마감 = now >= starts_at (시작 정각에는 이미 마감). */
    fun isClosed(startsAt: Instant, now: Instant): Boolean = !now.isBefore(startsAt)
}
