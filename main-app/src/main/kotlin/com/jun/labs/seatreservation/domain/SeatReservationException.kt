package com.jun.labs.seatreservation.domain

/**
 * [lockNotAvailable] = 좌석 행 `FOR UPDATE NOWAIT`가 55P03으로 진 409(같은 `SEAT_NOT_AVAILABLE` · 응답 계약 그대로).
 * 측정 프로필에서만 응답 헤더 `X-Seatlab-Reject: nowait`로 드러난다(ADR-014 측정-5 M3 · 리뷰 MR-5 — 판정 409 대 배치 잠금 409).
 */
class SeatReservationException(
    val errorCode: ErrorCode,
    val lockNotAvailable: Boolean = false,
    /** ADR-017 — intent 경로 확정 거부의 보상 작업(오류 본문 `compensation`) · 그 밖은 null */
    val compensation: CompensationView? = null,
) : RuntimeException(errorCode.message)

/** ADR-017 설계-4.3 응답 본문의 보상 정보 — 작업이 있을 때만(모드 none이면 없음) */
data class CompensationView(val taskId: Long, val type: String, val status: String)
