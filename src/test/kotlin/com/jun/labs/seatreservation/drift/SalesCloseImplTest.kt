package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.RedisTestcontainersConfiguration
import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.impl.HoldSeatProcess
import com.jun.labs.seatreservation.service.impl.HoldSeatService
import com.jun.labs.seatreservation.service.impl.SalesCloseGate
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategy
import com.jun.labs.seatreservation.service.impl.hold.PessimisticNoWaitHoldStrategy
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import com.jun.labs.seatreservation.service.impl.limit.UserLimitStrategy
import com.jun.labs.seatreservation.support.IntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import kotlin.test.assertEquals

private fun code(block: () -> Unit): String = try { block(); "ok" } catch (e: SeatReservationException) { e.errorCode.name }

/** 판매 마감 기본 동작(기본 방식 3b + precheck): 직전 성공 · 정각 마감 · 마감 뒤 확정 성공. */
class SalesCloseImplTest : IntegrationTest() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase

    @Test
    fun `starts_at 직전 성공 · 정각부터 SCHEDULE_CLOSED · 마감 전 홀드의 확정은 마감 뒤에도 성공`() {
        val startsAt = NOW.plusSeconds(120)
        val s = productScheduleRepository.save(com.jun.labs.seatreservation.domain.ProductSchedule(schedule.productId, startsAt)).id!!
        fun seat(n: Int) = productSeatRepository.save(com.jun.labs.seatreservation.domain.ProductSeat(s, "A", 1, n)).id!!
        clock.set(startsAt.minusMillis(1))
        val hold = holdSeat.hold(HoldSeatCommand(s, seat(1), 1L))
        clock.set(startsAt)
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code { holdSeat.hold(HoldSeatCommand(s, seat(2), 2L)) })
        clock.advance(Duration.ofSeconds(60))
        confirm.confirm(ConfirmReservationCommand(hold.holdId, 1L, "p"))
        assertEquals(1, reservationRepository.count())
    }
}

/**
 * 명세 §11 N2 — 판정 순서 마감 → 좌석 → 매수가 실제 호출 경로에서 지켜지는가.
 * 좌석 전략: 전략 빈을 직접 부른다(매수 none). 매수 방식: 실제 HoldSeatService(트랜잭션 밖 prepare·around) + 3b를 방식마다 조립한다.
 */
@TestPropertySource(properties = ["seat.hold.limit-strategy=none"])
class SalesCloseOrderImplTest : IntegrationTest() {
    @Autowired lateinit var strategies: List<HoldStrategy>
    @Autowired lateinit var limits: List<UserLimitStrategy>
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var salesClose: SalesCloseGate

    private var closedSchedule = 0L
    private var soldSeat = 0L

    @BeforeEach
    fun closed() {
        closedSchedule = productScheduleRepository.save(com.jun.labs.seatreservation.domain.ProductSchedule(schedule.productId, NOW)).id!!
        soldSeat = productSeatRepository.save(com.jun.labs.seatreservation.domain.ProductSeat(closedSchedule, "A", 1, 1, SeatStatus.HELD)).id!!
    }

    @ParameterizedTest
    @EnumSource(names = ["NONE", "JVM_LOCK", "JVM_LOCK_IN_TX", "CONDITIONAL_UPDATE", "PESSIMISTIC", "PESSIMISTIC_NOWAIT", "OPTIMISTIC", "ADVISORY"])
    fun `좌석 전략마다 — 마감 회차의 팔린 좌석은 SCHEDULE_CLOSED(좌석 불가보다 먼저)`(type: HoldStrategyType) {
        val s = strategies.single { it.type == type }
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code { s.hold(HoldSeatCommand(closedSchedule, soldSeat, 1L)) }, "$type")
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code { s.hold(HoldSeatCommand(closedSchedule, 999_999L, 1L)) }, "$type: 없는 좌석보다도 먼저")
    }

    @ParameterizedTest
    @EnumSource(UserLimitStrategyType::class)
    fun `매수 방식마다(실제 유스케이스 경로) — 마감 회차는 좌석·매수 판정보다 먼저 SCHEDULE_CLOSED, 열린 회차의 팔린 좌석은 그대로`(type: UserLimitStrategyType) {
        val props = properties.copy(limitStrategy = type)
        val active = ActiveUserLimit(limits, props)
        val process = HoldSeatProcess(productSeatRepository, active, meters, props, clock, salesClose)
        val service = HoldSeatService(listOf(PessimisticNoWaitHoldStrategy(process, productSeatRepository, tx)), props, active, meters)
        // 매수가 찬 사용자(쿼터·카운터 행 + 실제 홀드 2) — 매수 판정이 먼저 났다면 HOLD_LIMIT_EXCEEDED
        val user = 77L
        repeat(2) { i ->
            val seat = productSeatRepository.save(com.jun.labs.seatreservation.domain.ProductSeat(closedSchedule, "A", 2, i, SeatStatus.HELD))
            insertHoldFor(seat.id!!, closedSchedule, user)
        }
        jdbcTemplate.update("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 2)", closedSchedule, user)
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code { service.hold(HoldSeatCommand(closedSchedule, soldSeat, user)) }, "$type")
        val free = productSeatRepository.save(com.jun.labs.seatreservation.domain.ProductSeat(closedSchedule, "A", 3, 1)).id!!
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code { service.hold(HoldSeatCommand(closedSchedule, free, user)) }, "$type")
        assertEquals(SeatStatus.AVAILABLE, productSeatRepository.findById(free).orElseThrow().status)
        assertEquals(2, jdbcTemplate.queryForObject("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, closedSchedule, user))
    }

    private fun insertHoldFor(seatId: Long, scheduleId: Long, userId: Long) {
        jdbcTemplate.update(
            "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            seatId, scheduleId, userId, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW.plusSeconds(300)),
        )
    }

    @Test
    fun `sales-close 끔 — 마감 판정 문장이 없다(마감 회차도 좌석 판정 그대로)`() {
        val off = SalesCloseGate(jdbcTemplate, properties.copy(salesClose = SeatHoldProperties.SalesClose(enabled = false)), clock, meters)
        tx.executeWithoutResult { off.assertOpenLocked(closedSchedule) } // 던지지 않는다
        val s = strategies.single { it.type == HoldStrategyType.PESSIMISTIC_NOWAIT }
        assertThrows<SeatReservationException> { s.hold(HoldSeatCommand(closedSchedule, soldSeat, 1L)) }
    }
}

/** Redis 선점 관문(트랜잭션을 열기 전에 '진 것'이 나는 경로)도 마감이 먼저 — RedisHoldStrategyTest와 같은 설정(컨텍스트 공유). */
@Import(RedisTestcontainersConfiguration::class)
@TestPropertySource(properties = ["seat.hold.strategy=redis-nx", "seat.hold.limit-strategy=none"])
class RedisSalesCloseOrderImplTest : IntegrationTest() {
    @Autowired lateinit var strategies: List<HoldStrategy>
    @Autowired lateinit var redis: StringRedisTemplate

    @Test
    fun `관문 키가 이미 있는(진) 요청 · 키를 처음 잡은 요청 모두 마감 회차면 SCHEDULE_CLOSED, 처음 잡은 키는 해제`() {
        redis.connectionFactory!!.connection.use { it.serverCommands().flushDb() }
        val closed = productScheduleRepository.save(com.jun.labs.seatreservation.domain.ProductSchedule(schedule.productId, NOW)).id!!
        val seat = productSeatRepository.save(com.jun.labs.seatreservation.domain.ProductSeat(closed, "A", 1, 1)).id!!
        val nx = strategies.single { it.type == HoldStrategyType.REDIS_NX }
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code { nx.hold(HoldSeatCommand(closed, seat, 1L)) })
        assertEquals(null, redis.opsForValue().get("hold:seat:$seat"), "DB 단계에서 진 요청은 키를 지운다")
        redis.opsForValue().set("hold:seat:$seat", "other")
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code { nx.hold(HoldSeatCommand(closed, seat, 2L)) })
        val lock = strategies.single { it.type == HoldStrategyType.REDIS_LOCK }
        assertEquals(ErrorCode.SCHEDULE_CLOSED.name, code { lock.hold(HoldSeatCommand(closed, seat, 3L)) })
    }
}
