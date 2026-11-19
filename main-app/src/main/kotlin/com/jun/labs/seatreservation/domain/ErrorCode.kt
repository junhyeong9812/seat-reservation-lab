package com.jun.labs.seatreservation.domain

enum class ErrorCode(val message: String) {
    SEAT_NOT_FOUND("좌석을 찾을 수 없습니다."),
    SEAT_NOT_AVAILABLE("선점할 수 없는 좌석입니다."),
    HOLD_LIMIT_EXCEEDED("1인 최대 선점·예약 매수를 초과했습니다."),
    HOLD_NOT_FOUND("선점 내역을 찾을 수 없습니다."),
    HOLD_NOT_OWNED("본인의 선점이 아닙니다."),
    HOLD_EXPIRED("선점이 만료되었습니다."),
    SEAT_NOT_HELD("선점 상태의 좌석이 아닙니다."),
    /** ADR-009 판매 마감 — 회차 시작 시각(starts_at)이 지나 선점할 수 없다. 확정은 막지 않는다. */
    SCHEDULE_CLOSED("판매가 마감된 회차입니다."),
    /** ADR-018 다석 선점 — 좌석 목록이 없음 · 빈 목록 · 중복 · 1인 한도 초과 · 숫자 아님 */
    INVALID_SEAT_SELECTION("좌석 선택이 올바르지 않습니다."),
    /** ADR-018 묶음 홀드에 속한 홀드를 1석 API로 다루려 했다 — 묶음은 묶음 API로만 다룬다 */
    HOLD_IN_GROUP("묶음 선점에 속한 선점입니다."),
    /** ADR-018 결제를 시작한 홀드는 해제할 수 없다 */
    PAYMENT_STARTED("결제가 시작된 선점입니다."),
}
