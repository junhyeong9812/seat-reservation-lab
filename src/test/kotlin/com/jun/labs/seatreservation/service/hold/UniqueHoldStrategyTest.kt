package com.jun.labs.seatreservation.service.hold

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategy
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategyStartupCheck
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals

/** ADR-003 유니크 전략 — V4(db/migration-unique)가 붙은 스키마에서만 돈다. 기동 검사가 전략과 인덱스의 짝을 강제한다. */
@TestPropertySource(
    properties = [
        "seat.hold.strategy=unique",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-unique",
    ],
)
class UniqueHoldStrategyTest : IntegrationTest() {

    @Autowired lateinit var strategies: List<HoldStrategy>
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var redis: StringRedisTemplate

    private val unique get() = strategies.single { it.type == HoldStrategyType.UNIQUE }

    @Test
    fun `같은 좌석 동시 50명 중 정확히 1명만 이기고 나머지는 409`() {
        val seat = createSeat(1)

        val outcomes = race(unique, schedule.id!!, seat.id!!)

        assertEquals(mapOf("ok" to 1, ErrorCode.SEAT_NOT_AVAILABLE.name to 49), outcomes.groupingBy { it }.eachCount(), "$outcomes")
        assertEquals(SeatStatus.HELD, seatStatus(seat))
    }

    @Test
    fun `유니크 인덱스는 좌석당 홀드 2행을 DB에서 거부한다`() {
        val seat = createSeat(1)
        insertHold(seat, userId = 1, heldAt = NOW, expiresAt = NOW.plusSeconds(300))

        assertThrows<DataIntegrityViolationException> {
            insertHold(seat, userId = 2, heldAt = NOW, expiresAt = NOW.plusSeconds(300))
        }
    }

    @Test
    fun `기동 검사 — 유니크 인덱스가 있는데 다른 전략이면 앱을 띄우지 않는다`() {
        val mismatched = HoldStrategyStartupCheck(properties.copy(strategy = HoldStrategyType.NONE), jdbcTemplate, redis)

        assertThrows<IllegalStateException> { mismatched.run(org.springframework.boot.DefaultApplicationArguments()) }
    }
}
