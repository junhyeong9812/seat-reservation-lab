package com.jun.labs.seatreservation.service

/**
 * 같은 사용자의 동시 선점이 1인 최대 매수를 넘지 않게 하는 방식(ADR-005). 설정 `seat.hold.limit-strategy`로 기동 시 하나를 고른다.
 * 좌석 경합 방식(ADR-003, [HoldStrategyType])과 별개 축이다 — 사용자 락은 좌석 락보다 먼저 잡는다.
 */
enum class UserLimitStrategyType {
    NONE,
    ADVISORY,
    ADVISORY_TRY,
    /** 같고, 거절을 좌석 확인 전에(에러 순서 변경 — 거절될 요청이 좌석 락을 잡지 않는다) */
    ADVISORY_TRY_EARLY,
    QUOTA_LOCK,
    QUOTA_NOWAIT,
    QUOTA_NOWAIT_EARLY,
    COUNTER,
    /** ADR-006 — 카운터를 트랜잭션 안 한 문장 upsert로(트랜잭션 밖 준비 없음) */
    COUNTER_UPSERT,
    SERIALIZABLE,
    SERIALIZABLE_RETRY,
}
