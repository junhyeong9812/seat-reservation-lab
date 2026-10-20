package com.jun.labs.seatreservation.service.impl.drift

/**
 * 측정 전용 앱 경로 주입 이음새(ADR-012 §4.2.7) — loadtest 프로필의 `AppPathInjector`만 빈을 둔다. 빈이 없으면(운영) 호출처가 아무것도 하지 않는다.
 * 정상 경로가 쿼터 행을 1행 바꾼 **직후, 같은 트랜잭션에서** 부른다(선점이 뒤에서 롤백되면 주입도 함께 사라진다).
 */
interface AppPathInjectHook {
    /** 선점 증가(upsert · counter `cnt + 1`)가 1행일 때 */
    fun afterHoldIncrement(scheduleId: Long, userId: Long)

    /** A 가드 감소가 1행일 때 */
    fun afterExpiryDecrement(scheduleId: Long, userId: Long)
}
