package com.jun.labs.seatreservation.service.impl.hold

import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-015 §설계-10 T1: `seat.confirm.unique-index-optional=true`는 test · loadtest 프로필에서만 받는다 — 프로필 없는 환경(일반 배포)에서는 기동 거부.
 * 계약 테스트(adr015 패키지)는 모두 test 프로필 컨텍스트라 이 거부 갈래를 직접 밟지 않는다 — 설정 검사만 단위로 고정한다(DB 없음).
 */
class UniqueIndexOptionalProfileCheckTest {

    @Test
    fun `프로필 없는 환경에서 unique-index-optional=true는 거부한다(메시지에 키 이름)`() {
        val properties = SeatHoldProperties(ttl = Duration.ofMinutes(5), maxPerUser = 2, expiryInterval = Duration.ofSeconds(10))
        val env = MockEnvironment() // 활성 프로필 없음
        val e = assertFailsWith<IllegalStateException> {
            HoldStrategyStartupCheck.checkConfig(properties, env, SeatConfirmProperties(uniqueIndexOptional = true))
        }
        assertTrue(e.message!!.contains("seat.confirm.unique-index-optional"), e.message)
    }
}
