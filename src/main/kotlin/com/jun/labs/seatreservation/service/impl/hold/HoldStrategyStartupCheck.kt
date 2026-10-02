package com.jun.labs.seatreservation.service.impl.hold

import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatHoldProperties
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 기동 시 전략과 환경이 맞는지 확인하고, 어긋나면 앱을 띄우지 않는다(fail-fast) — 측정 조건이 조용히 섞이지 않게.
 * - 유니크 인덱스(V4)는 UNIQUE 전략에서만 있어야 한다: 다른 전략에 있으면 기준선이 바뀌고, UNIQUE에 없으면 아무것도 막지 않는다.
 * - Redis 전략은 Redis에 닿아야 한다.
 */
@Component
class HoldStrategyStartupCheck(
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val strategy = properties.strategy
        val uniqueIndex = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Int::class.java,
            UniqueConstraintHoldStrategy.UNIQUE_HOLD_INDEX,
        )!! > 0
        check(uniqueIndex == (strategy == HoldStrategyType.UNIQUE)) {
            "전략 $strategy 와 유니크 인덱스(${UniqueConstraintHoldStrategy.UNIQUE_HOLD_INDEX}) 존재=$uniqueIndex 가 맞지 않는다 " +
                "— UNIQUE 전략은 spring.flyway.locations에 classpath:db/migration-unique를 더하고, 다른 전략은 빼야 한다"
        }
        if (strategy == HoldStrategyType.REDIS_NX || strategy == HoldStrategyType.REDIS_LOCK) {
            redis.connectionFactory!!.connection.use { check(it.ping() == "PONG") { "Redis 응답 없음" } }
        }
    }
}
