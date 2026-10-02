package com.jun.labs.seatreservation.service.hold

import com.jun.labs.seatreservation.RedisTestcontainersConfiguration
import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategy
import com.jun.labs.seatreservation.service.impl.hold.RedisNxHoldStrategy
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-003 Redis 전략 — 선점 관문(SET NX, DB가 원천)과 분산락(Redisson). 활성 전략을 redis-nx로 두어 기동 검사의 Redis ping도 돈다. */
@Import(RedisTestcontainersConfiguration::class)
@TestPropertySource(properties = ["seat.hold.strategy=redis-nx"])
class RedisHoldStrategyTest : IntegrationTest() {

    @Autowired lateinit var strategies: List<HoldStrategy>
    @Autowired lateinit var redis: StringRedisTemplate

    private fun strategy(type: HoldStrategyType) = strategies.single { it.type == type }

    @BeforeEach
    fun flushRedis() {
        redis.connectionFactory!!.connection.use { it.serverCommands().flushDb() }
    }

    @ParameterizedTest
    @EnumSource(names = ["REDIS_NX", "REDIS_LOCK"])
    fun `같은 좌석 동시 50명 중 정확히 1명만 이기고 나머지는 409`(type: HoldStrategyType) {
        val seat = createSeat(1)

        val outcomes = race(strategy(type), schedule.id!!, seat.id!!)

        assertEquals(mapOf("ok" to 1, ErrorCode.SEAT_NOT_AVAILABLE.name to 49), outcomes.groupingBy { it }.eachCount(), "$type: $outcomes")
        assertEquals(SeatStatus.HELD, seatStatus(seat))
    }

    @Test
    fun `선점 관문 — 이긴 요청의 키는 홀드 TTL로 남는다`() {
        val seat = createSeat(1)

        strategy(HoldStrategyType.REDIS_NX).hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId = 1))

        val ttl = redis.getExpire("${RedisNxHoldStrategy.KEY_PREFIX}${seat.id}")!!
        assertTrue(ttl in 290..300, "ttl=$ttl")
    }

    @Test
    fun `선점 관문 — DB에서 지면(이미 예약된 좌석) 자기 키를 지워 좌석을 막지 않는다`() {
        val seat = createSeat(1, SeatStatus.RESERVED)

        val e = assertThrows<SeatReservationException> {
            strategy(HoldStrategyType.REDIS_NX).hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId = 1))
        }

        assertEquals(ErrorCode.SEAT_NOT_AVAILABLE, e.errorCode)
        assertNull(redis.opsForValue().get("${RedisNxHoldStrategy.KEY_PREFIX}${seat.id}"))
    }
}
