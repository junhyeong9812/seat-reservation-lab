package com.jun.labs.seatreservation.domain

/** 도메인 에러 → HTTP 상태(단일 출처 — 응답 처리기와 ADR-017 거부 기록이 같은 값을 쓴다) */
fun ErrorCode.httpStatusCode(): Int = when (this) {
    ErrorCode.SEAT_NOT_FOUND, ErrorCode.HOLD_NOT_FOUND -> 404
    ErrorCode.HOLD_NOT_OWNED -> 403
    ErrorCode.SEAT_NOT_AVAILABLE,
    ErrorCode.HOLD_LIMIT_EXCEEDED,
    ErrorCode.HOLD_EXPIRED,
    ErrorCode.SEAT_NOT_HELD,
    ErrorCode.SCHEDULE_CLOSED,
    -> 409
}
