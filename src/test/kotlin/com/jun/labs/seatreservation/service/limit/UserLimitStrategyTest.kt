package com.jun.labs.seatreservation.service.limit

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.UserLimitStrategyType.COUNTER
import com.jun.labs.seatreservation.service.UserLimitStrategyType.NONE
import com.jun.labs.seatreservation.service.UserLimitStrategyType.SERIALIZABLE
import com.jun.labs.seatreservation.service.UserLimitStrategyType.SERIALIZABLE_RETRY
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import org.springframework.transaction.support.TransactionTemplate
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.test.context.TestPropertySource as Props
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-005 매수 방식 계약 — 방식마다 컨텍스트 하나(기동 설정으로 고르므로). 좌석 방식은 기본값(3b nowait).
 * 모든 요청은 선점 유스케이스(트랜잭션 밖 [around] 포함)로 보낸다. 판정은 응답과 DB 행을 함께 본다.
 */
abstract class UserLimitStrategyTest(private val type: UserLimitStrategyType) : IntegrationTest() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var activeLimit: ActiveUserLimit
    @Autowired lateinit var tx: TransactionTemplate

    private val serializable get() = type == SERIALIZABLE || type == SERIALIZABLE_RETRY

    /** [requests]개를 동시에 보낸다. 결과 = "ok" 또는 에러 코드 이름(그 밖의 예외는 "ERR:…"). */
    private fun concurrently(requests: List<HoldSeatCommand>): List<String> {
        val pool = Executors.newFixedThreadPool(requests.size)
        val ready = CountDownLatch(requests.size)
        val start = CountDownLatch(1)
        try {
            val futures = requests.map { command ->
                pool.submit<String> {
                    ready.countDown()
                    start.await()
                    try {
                        holdSeat.hold(command)
                        "ok"
                    } catch (e: SeatReservationException) {
                        e.errorCode.name
                    } catch (e: Throwable) {
                        "ERR:${e::class.simpleName}:${e.message?.take(160)}"
                    }
                }
            }
            ready.await()
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** DB 사실: 사용자의 홀드 + 확정 예약 수(판정기와 같은 정의). */
    private fun ownedBy(userId: Long): Int = jdbcTemplate.queryForObject(
        """
        SELECT (SELECT count(*) FROM seat_hold WHERE schedule_id = ? AND user_id = ?)
             + (SELECT count(*) FROM reservation WHERE schedule_id = ? AND user_id = ? AND status = 'CONFIRMED')
        """.trimIndent(),
        Int::class.java, schedule.id, userId, schedule.id, userId,
    )!!

    private fun counterOf(userId: Long): Int? = jdbcTemplate.queryForList(
        "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, schedule.id, userId,
    ).singleOrNull()

    /** 같은 사용자가 서로 다른 좌석 [n]개를 동시에 — 라운드마다 새 좌석·새 사용자. 라운드별 (성공 수, DB 매수). */
    private fun sameUserRounds(rounds: Int, n: Int = 10): List<Pair<Int, Int>> = (1..rounds).map { r ->
        val userId = 9_000L + r
        val seats = (1..n).map { createSeat(r * 100 + it) }
        val outcomes = concurrently(seats.map { HoldSeatCommand(schedule.id!!, it.id!!, userId) })
        assertTrue(outcomes.none { it.startsWith("ERR") }, "$type: $outcomes")
        outcomes.count { it == "ok" } to ownedBy(userId)
    }

    @Test
    fun `활성 매수 방식이 설정과 같다`() {
        assertEquals(type, properties.limitStrategy)
    }

    @Test
    fun `acquire 뒤 트랜잭션 격리 수준 — SERIALIZABLE 방식만 serializable, 나머지는 read committed`() {
        val cmd = HoldSeatCommand(schedule.id!!, 1, userId = 3L)
        activeLimit.strategy.prepare(cmd)
        val level = tx.execute { status ->
            activeLimit.strategy.acquire(cmd)
            status.setRollbackOnly()
            jdbcTemplate.queryForObject("SHOW transaction_isolation", String::class.java)
        }
        assertEquals(if (serializable) "serializable" else "read committed", level, "$type")
    }

    @Test
    fun `같은 사용자 동시 10좌석 — 대조군(none)은 5라운드 안에 초과가 나고, 나머지는 성공·DB 매수 모두 2 이하`() {
        val rounds = sameUserRounds(5)
        rounds.forEach { (ok, owned) -> assertEquals(ok, owned, "$type: 응답 성공 수 = DB 매수 — $rounds") }
        if (type == NONE) {
            assertTrue(rounds.any { it.first > properties.maxPerUser }, "NONE: 5라운드 동안 초과가 한 번도 안 났다 — 경합 장치가 매수 경합을 만들지 못한다 $rounds")
        } else {
            assertTrue(rounds.all { it.first <= properties.maxPerUser }, "$type: 매수 초과 $rounds")
        }
    }

    @Test
    fun `다른 사용자끼리는 서로 막지 않는다 — 20명이 각자 다른 좌석을 동시에`() {
        val seats = (1..20).map { createSeat(it) }
        val outcomes = concurrently(seats.mapIndexed { i, s -> HoldSeatCommand(schedule.id!!, s.id!!, userId = 5_000L + i) })
        if (serializable) {
            // SSI는 술어 단위라 다른 사용자끼리도 충돌(40001 → 거절)할 수 있다 — 그 비율은 본측정이 잰다. 여기서는 정합만 본다
            assertTrue(outcomes.all { it == "ok" || it == ErrorCode.HOLD_LIMIT_EXCEEDED.name }, "$type: $outcomes")
        } else {
            assertEquals(List(20) { "ok" }, outcomes, "$type")
        }
    }

    @Test
    fun `사용자 A가 사용자 단위 진입을 쥔 동안 — 다른 사용자 B는 바로 끝나고, 같은 사용자는 대기형이면 기다리고 즉시 실패형이면 바로 거절`() {
        val a = HoldSeatCommand(schedule.id!!, createSeat(1).id!!, userId = 21L)
        val b = HoldSeatCommand(schedule.id!!, createSeat(2).id!!, userId = 22L)
        val a2 = HoldSeatCommand(schedule.id!!, createSeat(3).id!!, userId = 21L)
        val takenSeat = createSeat(4).also { holdSeat.hold(HoldSeatCommand(schedule.id!!, it.id!!, userId = 23L)) }
        val a3 = HoldSeatCommand(schedule.id!!, takenSeat.id!!, userId = 21L) // 같은 사용자 + 이미 팔린 좌석
        val limit = activeLimit.strategy
        limit.prepare(a)
        val pool = Executors.newFixedThreadPool(2)
        fun submit(c: HoldSeatCommand) = pool.submit<String> {
            try { holdSeat.hold(c); "ok" } catch (e: SeatReservationException) { e.errorCode.name }
        }
        try {
            tx.execute { status ->
                limit.acquire(a)
                assertEquals("ok", submit(b).get(5, TimeUnit.SECONDS), "$type: 다른 사용자가 막혔다")
                val same = submit(a2)
                when (type) {
                    UserLimitStrategyType.ADVISORY, UserLimitStrategyType.QUOTA_LOCK, COUNTER ->
                        assertThrows<TimeoutException>("$type: 같은 사용자가 기다리지 않았다") { same.get(1, TimeUnit.SECONDS) }
                    UserLimitStrategyType.ADVISORY_TRY, UserLimitStrategyType.QUOTA_NOWAIT -> {
                        assertEquals(ErrorCode.HOLD_LIMIT_EXCEEDED.name, same.get(5, TimeUnit.SECONDS), "$type")
                        // 경합 중 에러 우선순위(명세 §2): 진입 못 해도 좌석을 먼저 확인 — 팔린 좌석이면 좌석 불가
                        assertEquals(ErrorCode.SEAT_NOT_AVAILABLE.name, submit(a3).get(5, TimeUnit.SECONDS), "$type")
                    }
                    UserLimitStrategyType.ADVISORY_TRY_EARLY, UserLimitStrategyType.QUOTA_NOWAIT_EARLY -> {
                        assertEquals(ErrorCode.HOLD_LIMIT_EXCEEDED.name, same.get(5, TimeUnit.SECONDS), "$type")
                        // -early: 좌석 확인 전에 거절 — 팔린 좌석이어도 매수 초과
                        assertEquals(ErrorCode.HOLD_LIMIT_EXCEEDED.name, submit(a3).get(5, TimeUnit.SECONDS), "$type")
                    }
                    else -> {} // none·SERIALIZABLE은 같은 사용자를 이 자리에서 막지 않는다(판정은 매수 COUNT·커밋 시 충돌)
                }
                status.setRollbackOnly()
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `같은 좌석 50명 — 좌석 3b가 그대로 1명만 이기게 한다(매수 방식을 끼워도)`() {
        val seat = createSeat(1)
        val outcomes = concurrently((0 until 50).map { HoldSeatCommand(schedule.id!!, seat.id!!, userId = 1_000L + it) })
        val counts = outcomes.groupingBy { it }.eachCount()
        assertEquals(1, counts["ok"], "$type: $counts")
        val allowed = if (serializable) setOf("ok", ErrorCode.SEAT_NOT_AVAILABLE.name, ErrorCode.HOLD_LIMIT_EXCEEDED.name)
        else setOf("ok", ErrorCode.SEAT_NOT_AVAILABLE.name)
        assertTrue(counts.keys.all { it in allowed }, "$type: $counts")
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE seat_id = ?", Int::class.java, seat.id))
    }

    @Test
    fun `에러 우선순위(순차) — 매수가 찬 사용자가 이미 선점된 좌석을 누르면 좌석 불가, counter만 매수 초과`() {
        val userId = 7L
        holdSeat.hold(HoldSeatCommand(schedule.id!!, createSeat(1).id!!, userId))
        holdSeat.hold(HoldSeatCommand(schedule.id!!, createSeat(2).id!!, userId))
        val taken = createSeat(3).also { holdSeat.hold(HoldSeatCommand(schedule.id!!, it.id!!, userId = 8L)) }

        val e = assertThrows<SeatReservationException> { holdSeat.hold(HoldSeatCommand(schedule.id!!, taken.id!!, userId)) }
        // counter는 갱신이 곧 판정이라 좌석보다 먼저 판정된다(사용자 허용). 순차 호출이라 -early 변형도 진입은 성공 → 좌석 불가
        val expected = if (type == COUNTER) ErrorCode.HOLD_LIMIT_EXCEEDED else ErrorCode.SEAT_NOT_AVAILABLE
        assertEquals(expected, e.errorCode, "$type")

        val free = createSeat(4)
        val over = assertThrows<SeatReservationException> { holdSeat.hold(HoldSeatCommand(schedule.id!!, free.id!!, userId)) }
        assertEquals(ErrorCode.HOLD_LIMIT_EXCEEDED, over.errorCode, "$type")
        assertEquals(2, ownedBy(userId))
    }

    @Test
    fun `카운터 — 선점·좌석 실패 롤백·확정·만료 뒤에도 카운터 = 홀드 + 확정 예약(counter만 유지)`() {
        val userId = 11L
        val a = holdSeat.hold(HoldSeatCommand(schedule.id!!, createSeat(1).id!!, userId))
        val taken = createSeat(2).also { holdSeat.hold(HoldSeatCommand(schedule.id!!, it.id!!, userId = 12L)) }
        assertThrows<SeatReservationException> { holdSeat.hold(HoldSeatCommand(schedule.id!!, taken.id!!, userId)) } // 좌석에서 짐
        holdSeat.hold(HoldSeatCommand(schedule.id!!, createSeat(3).id!!, userId))
        confirm.confirm(ConfirmReservationCommand(holdId = a.holdId, userId = userId, paymentUid = "p-1"))
        clock.set(NOW.plus(properties.ttl).plusSeconds(1))
        expire.expire()

        assertEquals(1, ownedBy(userId)) // 확정 1 + 만료된 홀드 0
        if (type == COUNTER) {
            assertEquals(ownedBy(userId), counterOf(userId), "카운터 = 홀드 + 확정 예약")
            assertEquals(0, counterOf(12L), "사용자 12의 홀드도 만료 → 0")
        }
    }
}

@TestPropertySource(properties = ["seat.hold.limit-strategy=none"])
class NoUserLimitTest : UserLimitStrategyTest(NONE)

@TestPropertySource(properties = ["seat.hold.limit-strategy=advisory"])
class AdvisoryUserLimitTest : UserLimitStrategyTest(UserLimitStrategyType.ADVISORY)

@TestPropertySource(properties = ["seat.hold.limit-strategy=advisory-try"])
class AdvisoryTryUserLimitTest : UserLimitStrategyTest(UserLimitStrategyType.ADVISORY_TRY)

@TestPropertySource(properties = ["seat.hold.limit-strategy=advisory-try-early"])
class AdvisoryTryEarlyUserLimitTest : UserLimitStrategyTest(UserLimitStrategyType.ADVISORY_TRY_EARLY)

@TestPropertySource(properties = ["seat.hold.limit-strategy=quota-nowait-early"])
class QuotaNoWaitEarlyUserLimitTest : UserLimitStrategyTest(UserLimitStrategyType.QUOTA_NOWAIT_EARLY)

@TestPropertySource(properties = ["seat.hold.limit-strategy=quota-lock"])
class QuotaLockUserLimitTest : UserLimitStrategyTest(UserLimitStrategyType.QUOTA_LOCK)

@TestPropertySource(properties = ["seat.hold.limit-strategy=quota-nowait"])
class QuotaNoWaitUserLimitTest : UserLimitStrategyTest(UserLimitStrategyType.QUOTA_NOWAIT)

@TestPropertySource(properties = ["seat.hold.limit-strategy=counter"])
class CounterUserLimitTest : UserLimitStrategyTest(COUNTER) {
    /** 특성 테스트(PostgreSQL 동작): 이 동작 때문에 쿼터 행 준비를 'SELECT 먼저'로 했다(UserLimitStrategies.ensureQuotaRow). */
    @Test
    fun `INSERT … ON CONFLICT DO NOTHING은 UPDATE 중인 행을 기다리고, prepare(SELECT 먼저)는 기다리지 않는다`() {
        val cmd = HoldSeatCommand(schedule.id!!, 1, userId = 31L)
        activeLimit.strategy.prepare(cmd)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val raw = tx.execute { status ->
                activeLimit.strategy.acquire(cmd) // cnt + 1 — 커밋 전
                val raw = pool.submit<Int> {
                    jdbcTemplate.update("INSERT INTO user_hold_quota (schedule_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", cmd.scheduleId, cmd.userId)
                }
                assertThrows<TimeoutException>("ON CONFLICT가 기다리지 않았다 — 특성이 바뀌었으면 ensureQuotaRow 근거를 다시 보라") { raw.get(1, TimeUnit.SECONDS) }
                pool.submit { activeLimit.strategy.prepare(cmd) }.get(1, TimeUnit.SECONDS) // 기다리지 않아야 한다
                status.setRollbackOnly()
                raw
            }!!
            assertEquals(0, raw.get(5, TimeUnit.SECONDS)) // 롤백 뒤 풀려나 '이미 있음'으로 끝난다
        } finally {
            pool.shutdownNow()
        }
    }
}

@TestPropertySource(properties = ["seat.hold.limit-strategy=serializable"])
class SerializableUserLimitTest : UserLimitStrategyTest(SERIALIZABLE)

@TestPropertySource(properties = ["seat.hold.limit-strategy=serializable-retry"])
class SerializableRetryUserLimitTest : UserLimitStrategyTest(SERIALIZABLE_RETRY)

/**
 * SERIALIZABLE 충돌(40001) 경로를 만든다 — 임계 구역 지연(ADR-004 장치, 매수 판정 뒤 1초)으로 같은 사용자의 두 트랜잭션이 서로의 홀드를 못 본 채
 * (두 요청의 시작 차이가 1초보다 작으면 둘 다 판정을 마친 뒤 쓴다 — 동기화 hook이 없어 시간 여유로 보장. 실패해도 '40001 0회' 단언이 드러낸다)
 * 매수를 세고 쓰게 한다. L6은 하나가 거절되고, L7은 다시 해서 둘 다 성공한다(두 번째 시도는 첫 홀드를 보고 센다).
 */
abstract class SerializableConflictTest(private val retry: Boolean) : IntegrationTest() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var meters: MeterRegistry

    private fun count(name: String) = meters.find(name).counter()?.count() ?: 0.0

    @Test
    fun `같은 사용자 두 요청이 겹치면 40001 — L6은 하나 거절, L7은 재시도로 둘 다 성공`() {
        val failures0 = count("seat.hold.limit.serialization_failure")
        val retries0 = count("seat.hold.limit.retry")
        val seats = listOf(createSeat(1), createSeat(2))
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val outcomes = try {
            seats.map { seat ->
                pool.submit<String> {
                    start.await()
                    try { holdSeat.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId = 41L)); "ok" }
                    catch (e: SeatReservationException) { e.errorCode.name }
                    catch (e: Throwable) { "ERR:${e::class.simpleName}:${e.message?.take(160)}" }
                }
            }.also { start.countDown() }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertTrue(count("seat.hold.limit.serialization_failure") - failures0 >= 1, "40001이 한 번도 안 났다 — 이 테스트가 경로를 실행하지 못했다: $outcomes")
        if (retry) {
            assertEquals(listOf("ok", "ok"), outcomes, "L7")
            assertTrue(count("seat.hold.limit.retry") - retries0 >= 1, "L7: 재시도 카운터")
        } else {
            assertEquals(listOf(ErrorCode.HOLD_LIMIT_EXCEEDED.name, "ok"), outcomes.sorted(), "L6")
            assertEquals(0.0, count("seat.hold.limit.retry") - retries0, "L6은 재시도하지 않는다")
        }
        assertEquals(outcomes.count { it == "ok" }, holdCount(), "응답 성공 수 = 홀드 행")
    }
}

@Props(properties = ["seat.hold.limit-strategy=serializable", "seat.hold.critical-section-delay=1s"])
class SerializableNoRetryConflictTest : SerializableConflictTest(retry = false)

@Props(properties = ["seat.hold.limit-strategy=serializable-retry", "seat.hold.critical-section-delay=1s"])
class SerializableRetryConflictTest : SerializableConflictTest(retry = true)
