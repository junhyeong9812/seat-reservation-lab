package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.RedisTestcontainersConfiguration
import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.truncateHoldsReservationsQuota
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-009 판정 순서 마감 → 좌석 → 매수(명세 §2 · §11 'N1~N3: 모든 좌석 전략·매수 방식의 실제 호출 경로에서 통합 테스트로 보장').
 * 기동 검사가 허용하는 조합 전부 = (좌석 전략 11개 × 매수 none) + (좌석 3b × 매수 방식 12개, counter-trigger 포함). 조합마다 컨텍스트 하나.
 * 요청은 선점 유스케이스(HoldSeatUseCase — 트랜잭션 밖 prepare·around 포함, 실제 호출 경로)로만 보낸다.
 *
 * 공허하지 않음: 각 거절 단언 앞에서 **마감 전** 같은 상황이 좌석·매수 오류를 낸다는 것을 먼저 보인다 — 마감 판정이 없거나 순서가 틀리면 그 오류가 그대로 나와 실패한다.
 */
abstract class SalesCloseOrderTest(private val seatType: HoldStrategyType, private val limitType: UserLimitStrategyType) : IntegrationTest() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var tx: TransactionTemplate

    private val label get() = "$seatType × $limitType"
    private val s get() = schedule.id!!
    private var seatNo = 0
    private fun seat(): Long = createSeat(++seatNo).id!!

    private fun code(c: HoldSeatCommand): String =
        try { holdSeat.hold(c); "ok" }
        catch (e: SeatReservationException) { e.errorCode.name }
        catch (e: Throwable) { "ERR:${e::class.simpleName}:${e.message?.take(160)}" }

    private fun close() = clock.set(schedule.startsAt)

    /** 거절이 아무것도 남기지 않았는지 — 홀드 행 수와 0이 아닌 쿼터 행(quota-* · counter의 prepare는 cnt 0 행을 만들 수 있어 0행은 뺀다). */
    private fun footprint(): kotlin.Pair<Int, List<Map<String, Any?>>> =
        holdCount() to jdbcTemplate.queryForList("SELECT schedule_id, user_id, cnt FROM user_hold_quota WHERE cnt <> 0 ORDER BY 1, 2")

    /** 매수가 찬 사용자: 마감 전에 2매를 잡고, 마감 전 3번째는 HOLD_LIMIT_EXCEEDED임을 확인한다(상태가 실제로 '찬' 상태라는 근거). */
    private fun fullUser(userId: Long): Long {
        repeat(properties.maxPerUser) { assertEquals("ok", code(HoldSeatCommand(s, seat(), userId)), "$label 전제: 마감 전 선점") }
        assertEquals(ErrorCode.HOLD_LIMIT_EXCEEDED.name, code(HoldSeatCommand(s, seat(), userId)), "$label 전제: 매수가 찼다")
        return userId
    }

    /** 다른 사용자가 마감 전에 선점한 좌석. 마감 전 다른 사용자(매수 남음)는 SEAT_NOT_AVAILABLE임을 확인한다. */
    private fun takenSeat(): Long {
        val st = seat()
        assertEquals("ok", code(HoldSeatCommand(s, st, 9_999L)), "$label 전제")
        assertEquals(ErrorCode.SEAT_NOT_AVAILABLE.name, code(HoldSeatCommand(s, st, 9_998L)), "$label 전제: 마감 전에는 좌석 불가")
        return st
    }

    @AfterEach
    fun cleanForTriggerSchema() {
        // counter-trigger 컨텍스트에서도 다음 resetData가 엄격 트리거에 걸리지 않게(이 클래스는 어긋남을 만들지 않지만 안전하게)
        if (limitType == UserLimitStrategyType.COUNTER_TRIGGER) jdbcTemplate.truncateHoldsReservationsQuota()
    }

    @Test
    fun `조합 확인 — 활성 좌석 전략·매수 방식이 설정과 같다`() {
        assertEquals(seatType, properties.strategy)
        assertEquals(limitType, properties.limitStrategy)
    }

    @Test
    fun `마감 전 직전 시각 — 빈 좌석 선점 성공(대조)`() {
        clock.set(schedule.startsAt.minusMillis(1))
        assertEquals("ok", code(HoldSeatCommand(s, seat(), 1L)), label)
    }

    @Test
    fun `마감 후 빈 좌석 — SCHEDULE_CLOSED, 홀드·카운터 흔적 없음`() {
        val free = seat()
        val before = footprint()
        close()
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code(HoldSeatCommand(s, free, 1L)), label)
        assertEquals(before, footprint(), "$label: 거절이 흔적을 남겼다")
    }

    @Test
    fun `마감 → 좌석 — 이미 선점된 좌석·없는 좌석도 SCHEDULE_CLOSED`() {
        val taken = takenSeat()
        val before = footprint()
        close()
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code(HoldSeatCommand(s, taken, 2L)), "$label: 선점된 좌석")
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code(HoldSeatCommand(s, 999_999L, 2L)), "$label: 없는 좌석")
        assertEquals(before, footprint(), "$label: 거절이 흔적을 남겼다")
    }

    @Test
    fun `마감 → 매수 — 매수가 찬 사용자도 빈 좌석·선점된 좌석에서 SCHEDULE_CLOSED`() {
        val user = fullUser(3L)
        val taken = takenSeat()
        val free = seat()
        val before = footprint()
        close()
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code(HoldSeatCommand(s, free, user)), "$label: 찬 사용자 + 빈 좌석")
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code(HoldSeatCommand(s, taken, user)), "$label: 찬 사용자 + 선점된 좌석")
        assertEquals(before, footprint(), "$label: 거절이 흔적을 남겼다")
    }

    @Test
    fun `마감 → 좌석(잠금) — 다른 트랜잭션이 좌석 행을 잠근 채여도 기다리거나 좌석 오류를 내지 않고 SCHEDULE_CLOSED(매수 찬 사용자 포함)`() {
        val user = fullUser(4L)
        val locked = seat()
        val pool = Executors.newFixedThreadPool(1)
        try {
            // 마감 전 대조(3b만): 잠긴 좌석은 NOWAIT에서 바로 좌석 불가 — 이 자리에서 마감 뒤에 SCHEDULE_CLOSED가 나와야 순서가 '마감 먼저'다
            if (seatType == HoldStrategyType.PESSIMISTIC_NOWAIT) {
                tx.executeWithoutResult { st ->
                    jdbcTemplate.queryForList("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", locked)
                    val r = pool.submit<String> { code(HoldSeatCommand(s, locked, 5L)) }.get(10, TimeUnit.SECONDS)
                    assertEquals(ErrorCode.SEAT_NOT_AVAILABLE.name, r, "$label 전제: 마감 전 잠긴 좌석은 좌석 불가")
                    st.setRollbackOnly()
                }
            }
            val before = footprint()
            close()
            tx.executeWithoutResult { st ->
                jdbcTemplate.queryForList("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", locked)
                // 대기형 좌석 전략(비관락·조건부 UPDATE 등)이 마감 전에 좌석을 기다리면 여기서 시간 초과로 드러난다
                val other = pool.submit<String> { code(HoldSeatCommand(s, locked, 6L)) }.get(10, TimeUnit.SECONDS)
                val full = pool.submit<String> { code(HoldSeatCommand(s, locked, user)) }.get(10, TimeUnit.SECONDS)
                assertEquals(ErrorCode.SCHEDULE_CLOSED.name, other, "$label: 매수 남은 사용자")
                assertEquals(ErrorCode.SCHEDULE_CLOSED.name, full, "$label: 매수 찬 사용자")
                st.setRollbackOnly()
            }
            assertEquals(before, footprint(), "$label: 거절이 흔적을 남겼다")
        } finally {
            // 시간 초과로 실패한 경우 기다리던 선점은 잠금이 풀린 뒤(롤백) 끝난다 — 끝날 때까지 기다려 다음 테스트의 resetData와 겹치지 않게
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `마감 거절은 성공한 홀드를 건드리지 않는다 — 마감 전 홀드는 그대로 HELD`() {
        val st = seat()
        assertEquals("ok", code(HoldSeatCommand(s, st, 7L)))
        close()
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code(HoldSeatCommand(s, seat(), 7L)), label)
        assertEquals(1, holdCount())
        assertTrue(jdbcTemplate.queryForObject("SELECT status FROM product_seat WHERE id = ?", String::class.java, st) == "HELD")
    }
}

// ---- 좌석 전략 × 매수 none (매수 방식은 좌석 3b에서만 — 기동 검사) ----------------------------------------------------------

@TestPropertySource(properties = ["seat.hold.strategy=none", "seat.hold.limit-strategy=none"])
class SalesCloseOrderSeatNoneTest : SalesCloseOrderTest(HoldStrategyType.NONE, UserLimitStrategyType.NONE)

@TestPropertySource(properties = ["seat.hold.strategy=jvm-lock", "seat.hold.limit-strategy=none"])
class SalesCloseOrderSeatJvmLockTest : SalesCloseOrderTest(HoldStrategyType.JVM_LOCK, UserLimitStrategyType.NONE)

@TestPropertySource(properties = ["seat.hold.strategy=jvm-lock-in-tx", "seat.hold.limit-strategy=none"])
class SalesCloseOrderSeatJvmLockInTxTest : SalesCloseOrderTest(HoldStrategyType.JVM_LOCK_IN_TX, UserLimitStrategyType.NONE)

@TestPropertySource(properties = ["seat.hold.strategy=conditional-update", "seat.hold.limit-strategy=none"])
class SalesCloseOrderSeatConditionalUpdateTest : SalesCloseOrderTest(HoldStrategyType.CONDITIONAL_UPDATE, UserLimitStrategyType.NONE)

@TestPropertySource(properties = ["seat.hold.strategy=pessimistic", "seat.hold.limit-strategy=none"])
class SalesCloseOrderSeatPessimisticTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC, UserLimitStrategyType.NONE)

@TestPropertySource(properties = ["seat.hold.strategy=pessimistic-nowait", "seat.hold.limit-strategy=none"])
class SalesCloseOrderSeatNowaitTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.NONE)

@TestPropertySource(properties = ["seat.hold.strategy=optimistic", "seat.hold.limit-strategy=none"])
class SalesCloseOrderSeatOptimisticTest : SalesCloseOrderTest(HoldStrategyType.OPTIMISTIC, UserLimitStrategyType.NONE)

@TestPropertySource(properties = ["seat.hold.strategy=advisory", "seat.hold.limit-strategy=none"])
class SalesCloseOrderSeatAdvisoryTest : SalesCloseOrderTest(HoldStrategyType.ADVISORY, UserLimitStrategyType.NONE)

@TestPropertySource(
    properties = [
        "seat.hold.strategy=unique", "seat.hold.limit-strategy=none",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-unique",
    ],
)
class SalesCloseOrderSeatUniqueTest : SalesCloseOrderTest(HoldStrategyType.UNIQUE, UserLimitStrategyType.NONE)

abstract class SalesCloseOrderRedisTest(seatType: HoldStrategyType) : SalesCloseOrderTest(seatType, UserLimitStrategyType.NONE) {
    @Autowired lateinit var redis: StringRedisTemplate

    @BeforeEach
    fun flushRedis() {
        redis.connectionFactory!!.connection.use { it.serverCommands().flushDb() }
    }
}

@Import(RedisTestcontainersConfiguration::class)
@TestPropertySource(properties = ["seat.hold.strategy=redis-nx", "seat.hold.limit-strategy=none"])
class SalesCloseOrderSeatRedisNxTest : SalesCloseOrderRedisTest(HoldStrategyType.REDIS_NX)

@Import(RedisTestcontainersConfiguration::class)
@TestPropertySource(properties = ["seat.hold.strategy=redis-lock", "seat.hold.limit-strategy=none"])
class SalesCloseOrderSeatRedisLockTest : SalesCloseOrderRedisTest(HoldStrategyType.REDIS_LOCK)

// ---- 좌석 3b × 매수 방식 -----------------------------------------------------------------------------------------------

@TestPropertySource(properties = ["seat.hold.limit-strategy=advisory"])
class SalesCloseOrderLimitAdvisoryTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.ADVISORY)

@TestPropertySource(properties = ["seat.hold.limit-strategy=advisory-try"])
class SalesCloseOrderLimitAdvisoryTryTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.ADVISORY_TRY)

@TestPropertySource(properties = ["seat.hold.limit-strategy=advisory-try-early"])
class SalesCloseOrderLimitAdvisoryTryEarlyTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.ADVISORY_TRY_EARLY)

@TestPropertySource(properties = ["seat.hold.limit-strategy=quota-lock"])
class SalesCloseOrderLimitQuotaLockTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.QUOTA_LOCK)

@TestPropertySource(properties = ["seat.hold.limit-strategy=quota-nowait"])
class SalesCloseOrderLimitQuotaNowaitTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.QUOTA_NOWAIT)

@TestPropertySource(properties = ["seat.hold.limit-strategy=quota-nowait-early"])
class SalesCloseOrderLimitQuotaNowaitEarlyTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.QUOTA_NOWAIT_EARLY)

@TestPropertySource(properties = ["seat.hold.limit-strategy=counter"])
class SalesCloseOrderLimitCounterTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.COUNTER)

@TestPropertySource(properties = ["seat.hold.limit-strategy=counter-upsert"])
class SalesCloseOrderLimitCounterUpsertTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.COUNTER_UPSERT)

@TestPropertySource(properties = ["seat.hold.limit-strategy=counter-upsert-precheck"])
class SalesCloseOrderLimitPrecheckTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.COUNTER_UPSERT_PRECHECK)

@TestPropertySource(properties = ["seat.hold.limit-strategy=serializable"])
class SalesCloseOrderLimitSerializableTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.SERIALIZABLE)

@TestPropertySource(properties = ["seat.hold.limit-strategy=serializable-retry"])
class SalesCloseOrderLimitSerializableRetryTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.SERIALIZABLE_RETRY)

@TestPropertySource(
    properties = [
        "seat.hold.limit-strategy=counter-trigger",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-trigger",
    ],
)
class SalesCloseOrderLimitCounterTriggerTest : SalesCloseOrderTest(HoldStrategyType.PESSIMISTIC_NOWAIT, UserLimitStrategyType.COUNTER_TRIGGER)
