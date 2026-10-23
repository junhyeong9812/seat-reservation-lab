package com.jun.labs.seatreservation.service.hold

import com.jun.labs.seatreservation.SeatReservationApplication
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ADR-008 명세 §0·§2 — 기본 매수 방식이 none이 아니게 되면, 좌석 전략만 바꾸는 설정은 기동 검사(HoldStrategyStartupCheck)에서 멈춘다.
 * 그래서 좌석 전략을 바꾸는 곳은 limit-strategy=none을 명시해야 뜬다. 실제 SpringApplication으로 띄워 본다(기동 실패는 @SpringBootTest로 단언할 수 없다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultLimitStartupCheckTest {

    private val postgres = PostgreSQLContainer("postgres:16")

    @BeforeAll
    fun startDb() = postgres.start()

    @AfterAll
    fun stopDb() = postgres.stop()

    private val seq = AtomicInteger()

    /**
     * 부팅마다 새 DB — ADR-012 기본값 교체(명세 §10) 뒤 키 없는 부팅은 V9 트리거(user_hold_quota_touch)·V10을 적용한다. 같은 DB를
     * method=none · 위치 db/migration으로 다시 띄우면 Flyway는 그대로 지나가지만 기동 검사(core IncrementalSchemaCheck — method≠trigger인데
     * 트리거가 있다)가 거부한다(테스트 순서에 따라 갈리지 않게).
     */
    private fun boot(vararg extra: String): ConfigurableApplicationContext {
        val name = "deflimit_${seq.incrementAndGet()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        return SpringApplicationBuilder(SeatReservationApplication::class.java).run(
            "--spring.datasource.url=jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$name",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--server.port=0",
            "--seat.hold.expiry-scheduler-enabled=false",
            *extra,
        )
    }

    /** 업무 앱 기본값이 켠 것(ADR-009 A·정리 · ADR-012 증분·안전망 · V9 트리거·V10 위치)을 끈다 — none 기준선(명세 §10 K4 안내 조합) */
    private val adr009OffArgs = arrayOf(
        "--seat.hold.counter-drift.expiry-repair=false", "--seat.hold.counter-drift.reconcile.enabled=false", "--seat.hold.counter-drift.cleanup.mode=none",
        "--seat.hold.counter-drift.reconcile.incremental.enabled=false", "--seat.hold.counter-drift.reconcile.incremental.method=none",
        "--seat.hold.counter-drift.reconcile.safety-net.enabled=false", "--spring.flyway.locations=classpath:db/migration",
    )

    private fun causes(e: Throwable): List<Throwable> = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.toList()

    @Test
    fun `전제 — 환경에 limit-strategy가 없다(미지정 경로를 실제로 탄다)`() {
        assertEquals(null, System.getenv("SEAT_HOLD_LIMIT_STRATEGY"))
        assertEquals(null, System.getProperty("seat.hold.limit-strategy"))
    }

    @Test
    fun `limit-strategy 미지정 + 좌석 전략 pessimistic(3b 아님) — 기동 검사가 앱을 띄우지 않는다`() {
        assumeTrue(System.getenv("SEAT_HOLD_LIMIT_STRATEGY") == null)
        val error = try {
            boot("--seat.hold.strategy=pessimistic").close()
            fail("기본 매수 방식(precheck)과 좌석 전략 pessimistic 조합인데 앱이 떴다")
        } catch (e: Throwable) {
            if (e is AssertionError) throw e
            e
        }
        val check = causes(error).filterIsInstance<IllegalStateException>()
            .firstOrNull { it.message.orEmpty().contains("PESSIMISTIC_NOWAIT") }
        assertTrue(check != null, "기동 검사(매수 방식 × 좌석 전략) 실패가 아니다: ${causes(error).map { "${it::class.simpleName}: ${it.message}" }}")
        assertTrue(
            check.message.orEmpty().contains(UserLimitStrategyType.COUNTER_UPSERT_PRECHECK.name),
            "기본 매수 방식이 precheck로 잡혀 실패해야 한다: ${check.message}",
        )
    }

    @Test
    fun `limit-strategy=none 명시 + 좌석 전략 pessimistic — 뜬다`() {
        // 업무 앱 기본값이 켠 것도 끔 명시(A·정리 ①·증분·안전망 켬 — none과는 기동 검사가 거부한다. 이 테스트의 의미 = 전환 전 none 기준선)
        boot("--seat.hold.strategy=pessimistic", "--seat.hold.limit-strategy=none", *adr009OffArgs).use { ctx ->
            val props = ctx.getBean(SeatHoldProperties::class.java)
            assertEquals(UserLimitStrategyType.NONE, props.limitStrategy)
        }
    }

    @Test
    fun `아무것도 지정하지 않으면(좌석 3b + 기본 매수 방식) 뜨고 precheck가 활성`() {
        boot().use { ctx ->
            assertEquals(UserLimitStrategyType.COUNTER_UPSERT_PRECHECK, ctx.getBean(SeatHoldProperties::class.java).limitStrategy)
        }
    }
}
