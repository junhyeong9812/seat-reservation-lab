package com.jun.labs.seatreservation.service

/** 같은 좌석 경합을 막는 방식(ADR-003). 설정 `seat.hold.strategy`로 기동 시 하나를 고른다 — 회차마다 한 방식. */
enum class HoldStrategyType {
    NONE,
    JVM_LOCK,
    JVM_LOCK_IN_TX,
    CONDITIONAL_UPDATE,
    PESSIMISTIC,
    PESSIMISTIC_NOWAIT,
    OPTIMISTIC,
    UNIQUE,
    ADVISORY,
    REDIS_NX,
    REDIS_LOCK,
}
