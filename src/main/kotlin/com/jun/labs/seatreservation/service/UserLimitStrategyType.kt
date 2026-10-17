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
    /** ADR-007 — counter-upsert 앞에 잠금 없는 좌석 상태 선조회: 팔린 좌석은 쓰기 없이 거절, 에러 순서는 명세 순서(좌석 먼저) */
    COUNTER_UPSERT_PRECHECK,
    /** ADR-009 후보 B — cnt는 DB 트리거(V8, db/migration-trigger)가 고치고, 앱은 쿼터 행을 잠가 한도만 판정한다. 에러 순서는 precheck와 같다(좌석 먼저) */
    COUNTER_TRIGGER,
    SERIALIZABLE,
    SERIALIZABLE_RETRY,
    ;

    companion object {
        /** cnt를 유지하는 방식(카운터 계열) — 판정기·배경 행 거부·기동 검사의 단일 출처(ADR-009 코드 루프 1 L1-14) */
        val COUNTER_FAMILY: Set<UserLimitStrategyType> = setOf(COUNTER, COUNTER_UPSERT, COUNTER_UPSERT_PRECHECK, COUNTER_TRIGGER)
    }
}
