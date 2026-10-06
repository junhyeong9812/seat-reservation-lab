package com.jun.labs.seatreservation.service.impl.hold

import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.HoldSeatProcess
import jakarta.annotation.PreDestroy
import org.redisson.Redisson
import org.slf4j.LoggerFactory
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * 7 — Redis SET NX 선점 관문. 키 `hold:seat:{id}`를 홀드 TTL로 먼저 차지한 요청만 DB 홀드를 저장한다 — DB가 원천.
 * DB 단계가 실패(진 것 포함)하면 자기 토큰일 때만 키를 지운다. 지우기까지 실패하면 키가 TTL 동안 남아
 * 그 좌석은 DB에서 AVAILABLE인데도 선점이 막힌다 — Redis↔DB 부분 실패(ADR-003 ⑤에서 관측).
 */
@Component
class RedisNxHoldStrategy(
    private val process: HoldSeatProcess,
    private val redis: StringRedisTemplate,
    private val properties: SeatHoldProperties,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.REDIS_NX

    override fun hold(command: HoldSeatCommand): HoldSeatResult {
        val key = "$KEY_PREFIX${command.seatId}"
        val token = UUID.randomUUID().toString()
        if (redis.opsForValue().setIfAbsent(key, token, properties.ttl) != true) seatTaken()
        try {
            return tx.execute { process.hold(command) }!!
        } catch (e: Throwable) {
            // 해제 실패가 원래 결과(409·500)를 덮지 않게 — 원 예외를 그대로 던지고 해제 실패는 붙여서·로그로 남긴다(부분 실패 관측)
            try {
                redis.execute(RELEASE_IF_OWNER, listOf(key), token)
            } catch (releaseFailure: Exception) {
                e.addSuppressed(releaseFailure)
                log.warn("redis-nx 키 해제 실패 — 키가 TTL 동안 남아 좌석 {}을 막는다: {}", command.seatId, releaseFailure.toString())
            }
            throw e
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisNxHoldStrategy::class.java)
        const val KEY_PREFIX = "hold:seat:"
        private val RELEASE_IF_OWNER = DefaultRedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long::class.java,
        )
    }
}

/**
 * 8 — Redis 분산락(Redisson). 락이 트랜잭션을 감싼다(해제는 커밋 뒤). 대기는 무제한(비관락과 같은 대기형),
 * 임대 시간은 Redisson watchdog(기본 30초, 보유 중 연장) — 락을 잡은 인스턴스가 죽으면 임대 만료로 풀린다.
 * Redis 클라이언트는 이 전략이 처음 쓰일 때 만든다 — 다른 전략에서는 Redis에 연결하지 않는다.
 */
@Component
class RedisLockHoldStrategy(
    private val process: HoldSeatProcess,
    private val connection: RedisConnectionDetails,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.REDIS_LOCK

    private val client = lazy<RedissonClient> {
        val node = connection.standalone
        Redisson.create(Config().apply { useSingleServer().address = "redis://${node.host}:${node.port}" })
    }

    override fun hold(command: HoldSeatCommand): HoldSeatResult {
        val lock = client.value.getLock("$LOCK_PREFIX${command.seatId}")
        lock.lock()
        try {
            return tx.execute { process.hold(command) }!!
        } finally {
            lock.unlock()
        }
    }

    @PreDestroy
    fun close() {
        if (client.isInitialized()) client.value.shutdown()
    }

    companion object {
        const val LOCK_PREFIX = "lock:seat:"
    }
}
