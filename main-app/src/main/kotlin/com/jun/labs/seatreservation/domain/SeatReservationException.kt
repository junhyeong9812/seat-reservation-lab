package com.jun.labs.seatreservation.domain

/**
 * [lockNotAvailable] = 좌석 행 `FOR UPDATE NOWAIT`가 55P03으로 진 409(같은 `SEAT_NOT_AVAILABLE` · 응답 계약 그대로).
 * 측정 프로필에서만 응답 헤더 `X-Seatlab-Reject: nowait`로 드러난다(ADR-014 측정-5 M3 · 리뷰 MR-5 — 판정 409 대 배치 잠금 409).
 */
class SeatReservationException(val errorCode: ErrorCode, val lockNotAvailable: Boolean = false) : RuntimeException(errorCode.message)
