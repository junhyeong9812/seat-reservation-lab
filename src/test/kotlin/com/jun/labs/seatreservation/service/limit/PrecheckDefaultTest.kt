package com.jun.labs.seatreservation.service.limit

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType.COUNTER_UPSERT_PRECHECK
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 판정기(`LoadtestDataService` v_counter_mismatch)와 같은 정의 — 카운터 = seat_hold 행 전체 + CONFIRMED 예약. 도메인 코드를 재사용하지 않는다. */
internal const val COUNTER_MISMATCH_SQL = """
    WITH actual AS (
        SELECT schedule_id, user_id, sum(n) AS total FROM (
            SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
            UNION ALL
            SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2
        ) u GROUP BY 1, 2
    )
    SELECT count(*) FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id)
    WHERE coalesce(q.cnt, 0) <> coalesce(a.total, 0)
"""

internal fun counterMismatch(jdbc: JdbcTemplate): Long = jdbc.queryForObject(COUNTER_MISMATCH_SQL, Long::class.java)!!

/**
 * ADR-008 명세 §5 기본값 — 코드 기본값 경로(설정 키 없음)와 application.yml 경로 둘 다 counter-upsert-precheck.
 * 스프링 컨텍스트 없이 바인딩만 본다.
 */
class SeatHoldPropertiesDefaultTest {

    @Test
    fun `코드 기본값 — 생성자에 매수 방식을 주지 않으면 COUNTER_UPSERT_PRECHECK`() {
        val props = SeatHoldProperties(ttl = Duration.ofMinutes(5), maxPerUser = 2, expiryInterval = Duration.ofSeconds(10))
        assertEquals(COUNTER_UPSERT_PRECHECK, props.limitStrategy)
    }

    @Test
    fun `코드 기본값 — limit-strategy 키가 없는 소스로 바인딩하면 COUNTER_UPSERT_PRECHECK`() {
        // 기본값이 없는 필수 값만 준다 — limit-strategy는 없다
        val source = MapConfigurationPropertySource(
            mapOf("seat.hold.ttl" to "5m", "seat.hold.max-per-user" to "2", "seat.hold.expiry-interval" to "10s"),
        )
        val props = Binder(source).bind("seat.hold", SeatHoldProperties::class.java).get()
        assertEquals(COUNTER_UPSERT_PRECHECK, props.limitStrategy)
    }

    @Test
    fun `application_yml 경로 — 실제 application_yml만으로 바인딩하면 COUNTER_UPSERT_PRECHECK`() {
        // 키를 precheck로 두든, 키를 빼고 코드 기본값에 맡기든 결과는 같아야 한다
        val yaml = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
        val props = Binder(ConfigurationPropertySources.from(yaml)).bind("seat.hold", SeatHoldProperties::class.java).get()
        assertEquals(COUNTER_UPSERT_PRECHECK, props.limitStrategy)
    }
}

/**
 * ADR-008 명세 §5 기본값 — 매수 방식 설정 없이(IntegrationTest 기본 컨텍스트 = application.yml 그대로) 뜬 앱.
 * 이 클래스는 limit-strategy를 덮어쓰지 않는다.
 */
class PrecheckDefaultContextTest : IntegrationTest() {

    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var activeLimit: ActiveUserLimit
    @Autowired lateinit var holdSeat: HoldSeatUseCase

    @Test
    fun `설정 없이 기동하면 활성 매수 방식은 COUNTER_UPSERT_PRECHECK`() {
        assertEquals(COUNTER_UPSERT_PRECHECK, properties.limitStrategy)
        assertEquals(COUNTER_UPSERT_PRECHECK, activeLimit.strategy.type)
    }

    @Test
    fun `기본값 — 같은 사용자 동시 10좌석은 정확히 2개 성공, 카운터 = 실제(v_counter_mismatch 0)`() {
        repeat(5) { r ->
            val userId = 81_000L + r
            val seats = (1..10).map { createSeat(r * 100 + it) }
            val pool = Executors.newFixedThreadPool(seats.size)
            val ready = CountDownLatch(seats.size)
            val start = CountDownLatch(1)
            val outcomes = try {
                seats.map { seat ->
                    pool.submit<String> {
                        ready.countDown()
                        start.await()
                        try { holdSeat.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId)); "ok" }
                        catch (e: SeatReservationException) { e.errorCode.name }
                        catch (e: Throwable) { "ERR:${e::class.simpleName}:${e.message?.take(160)}" }
                    }
                }.also { ready.await(); start.countDown() }.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            assertEquals(
                mapOf("ok" to 2, ErrorCode.HOLD_LIMIT_EXCEEDED.name to 8), outcomes.groupingBy { it }.eachCount(),
                "라운드 $r: $outcomes",
            )
            val holds = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM seat_hold WHERE schedule_id = ? AND user_id = ?", Int::class.java, schedule.id, userId,
            )
            assertEquals(2, holds, "라운드 $r: DB 홀드 = 성공 수")
            val cnt = jdbcTemplate.queryForList(
                "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, schedule.id, userId,
            )
            assertEquals(listOf(2), cnt, "라운드 $r: 카운터 = 실제")
            assertEquals(0L, counterMismatch(jdbcTemplate), "라운드 $r: v_counter_mismatch")
        }
        assertTrue(holdCount() == 10, "5라운드 × 2")
    }
}
