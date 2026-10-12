package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.loadtest.LoadtestDataService
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategyStartupCheck
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 후보 B(counter-trigger + V8): 앱은 cnt를 쓰지 않고 트리거가 맞춘다 · 한도는 앱이 판정 · 기동 검사 짝. */
@TestPropertySource(
    properties = [
        "seat.hold.limit-strategy=counter-trigger",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-trigger",
    ],
)
class CounterTriggerImplTest : IntegrationTest() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var redis: StringRedisTemplate

    /** 다음 테스트의 정리(DELETE)가 트리거를 타지 않게 — 쿼터·홀드·예약을 TRUNCATE(행 트리거 없음) */
    @AfterEach
    fun truncate() = jdbcTemplate.execute("TRUNCATE reservation, seat_hold, user_hold_quota")

    private fun mismatch(): Long = jdbcTemplate.queryForObject(
        """
        WITH actual AS (SELECT schedule_id, user_id, sum(n) AS total FROM (
            SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
            UNION ALL SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2) u GROUP BY 1, 2)
        SELECT count(*) FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id) WHERE coalesce(q.cnt, 0) <> coalesce(a.total, 0)
        """.trimIndent(), Long::class.java,
    )!!

    @Test
    fun `선점 2 → 3번째 매수 초과(팔린 좌석은 좌석 불가가 먼저) · 확정 · 만료 뒤에도 cnt = 실제`() {
        val s = schedule.id!!
        val a = holdSeat.hold(HoldSeatCommand(s, createSeat(1).id!!, 1L))
        holdSeat.hold(HoldSeatCommand(s, createSeat(2).id!!, 1L))
        assertEquals(2, jdbcTemplate.queryForObject("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = 1", Int::class.java, s))
        val over = assertThrows<SeatReservationException> { holdSeat.hold(HoldSeatCommand(s, createSeat(3).id!!, 1L)) }
        assertEquals(ErrorCode.HOLD_LIMIT_EXCEEDED, over.errorCode)
        val taken = createSeat(4).also { holdSeat.hold(HoldSeatCommand(s, it.id!!, 2L)) }
        assertEquals(ErrorCode.SEAT_NOT_AVAILABLE, assertThrows<SeatReservationException> { holdSeat.hold(HoldSeatCommand(s, taken.id!!, 1L)) }.errorCode)
        confirm.confirm(ConfirmReservationCommand(a.holdId, 1L, "p"))
        assertEquals(0L, mismatch())
        clock.advance(Duration.ofMinutes(6))
        assertEquals(2, expire.expire())
        assertEquals(0L, mismatch())
        assertEquals(1, jdbcTemplate.queryForObject("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = 1", Int::class.java, s))
    }

    @Test
    fun `엄격 — 쿼터 행이 없는 쌍의 홀드 만료는 SR001로 틱 실패(0으로 자르지 않는다)`() {
        val s = schedule.id!!
        holdSeat.hold(HoldSeatCommand(s, createSeat(1).id!!, 5L))
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 5", s)
        clock.advance(Duration.ofMinutes(6))
        val e = assertThrows<Exception> { expire.expire() }
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { (it as? java.sql.SQLException)?.sqlState == "SR001" }, "$e")
    }

    @Test
    fun `기동 검사 — 트리거가 있는데 다른 카운터 방식이면 거부 · A와 B 조합 거부`() {
        val other = HoldStrategyStartupCheck(properties.copy(limitStrategy = UserLimitStrategyType.COUNTER_UPSERT_PRECHECK), jdbcTemplate, redis)
        assertThrows<IllegalStateException> { other.run(DefaultApplicationArguments()) }
        val ab = HoldStrategyStartupCheck(
            properties.copy(counterDrift = properties.counterDrift.copy(expiryRepair = true)), jdbcTemplate, redis,
        )
        assertThrows<IllegalStateException> { ab.run(DefaultApplicationArguments()) }
        HoldStrategyStartupCheck(properties, jdbcTemplate, redis).run(DefaultApplicationArguments()) // 지금 설정은 통과
    }
}

/** 기동 검사 확장(설계 §7) — 기본 스키마(트리거 없음)에서 조합 거부. 판정기 v2(마감 회차 제외). */
class StartupCheckAndJudgeImplTest : IntegrationTest() {
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var redis: StringRedisTemplate

    private fun check(p: SeatHoldProperties, env: MockEnvironment? = null) =
        HoldStrategyStartupCheck(p, jdbcTemplate, redis, env).run(DefaultApplicationArguments())

    @Test
    fun `트리거 없는 counter-trigger · quota 방식 아닌 정리 · 마감 끈 정리 ① · F 프로필에 만료 스케줄러 켬 — 모두 거부`() {
        assertThrows<IllegalStateException> { check(properties.copy(limitStrategy = UserLimitStrategyType.COUNTER_TRIGGER)) }
        val schedule1 = properties.counterDrift.copy(cleanup = properties.counterDrift.cleanup.copy(mode = SeatHoldProperties.CleanupMode.SCHEDULE))
        assertThrows<IllegalStateException> { check(properties.copy(limitStrategy = UserLimitStrategyType.COUNTER, counterDrift = schedule1)) }
        assertThrows<IllegalStateException> { check(properties.copy(counterDrift = schedule1, salesClose = SeatHoldProperties.SalesClose(false))) }
        check(properties.copy(counterDrift = schedule1)) // precheck + 마감 켬 = 통과
        assertThrows<IllegalStateException> { check(properties, MockEnvironment().apply { setActiveProfiles("reconciler") }) }
        val reconcileOn = properties.copy(counterDrift = properties.counterDrift.copy(reconcile = properties.counterDrift.reconcile.copy(enabled = true)))
        check(reconcileOn, MockEnvironment().apply { setActiveProfiles("reconciler"); setProperty("seat.hold.expiry-scheduler-enabled", "false") })
        val c = properties.counterDrift.copy(reconcile = properties.counterDrift.reconcile.copy(enabled = true))
        assertThrows<IllegalStateException> { check(properties.copy(limitStrategy = UserLimitStrategyType.QUOTA_LOCK, counterDrift = c)) }
    }

    @Test
    fun `판정기 v2 — 마감 회차의 어긋난 쌍은 빼고 제외 수를 낸다, 열린 회차 어긋남은 그대로 센다`() {
        val svc = LoadtestDataService(jdbcTemplate, properties, redis, clock)
        val closed = productScheduleRepository.save(com.jun.labs.seatreservation.domain.ProductSchedule(schedule.productId, NOW)).id!!
        jdbcTemplate.update("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, 1, 1), (?, 2, 3)", closed, closed)
        val before = svc.consistency(20)
        assertEquals(0L, (before["v_counter_mismatch"] as Number).toLong())
        assertEquals(1L, (before["counter_excluded_schedules"] as Number).toLong())
        assertEquals(2L, (before["counter_excluded_pairs"] as Number).toLong())
        jdbcTemplate.update("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, 9, 1)", schedule.id)
        assertEquals(1L, (svc.consistency(20)["v_counter_mismatch"] as Number).toLong())
        clock.set(NOW.minusSeconds(1)) // 마감 전이면 그 회차도 센다
        assertEquals(3L, (svc.consistency(20)["v_counter_mismatch"] as Number).toLong())
    }
}
