package com.jun.labs.seatreservation.domain

enum class ErrorCode(val message: String) {
    SEAT_NOT_FOUND("좌석을 찾을 수 없습니다."),
    SEAT_NOT_AVAILABLE("선점할 수 없는 좌석입니다."),
    HOLD_LIMIT_EXCEEDED("1인 최대 선점·예약 매수를 초과했습니다."),
    HOLD_NOT_FOUND("선점 내역을 찾을 수 없습니다."),
    HOLD_NOT_OWNED("본인의 선점이 아닙니다."),
    HOLD_EXPIRED("선점이 만료되었습니다."),
    SEAT_NOT_HELD("선점 상태의 좌석이 아닙니다."),
}
