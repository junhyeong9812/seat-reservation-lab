package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.SeatReservationApplication
import com.jun.labs.seatreservation.api.HoldExpiryScheduler
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ADR-009 기동 시 검사(fail-fast) — 명세 §1-4 · §5 · §10 D4: 쿼터 테이블을 쓰는 방식 전부(counter 계열 + quota 계열 + counter-trigger)는
 * 테이블이 없으면 뜨지 않는다 · 트리거 ⇔ counter-trigger · 후보와 매수 방식의 잘못된 조합 · reconciler 프로필이면 만료 스케줄러 끔.
 * 실제 SpringApplication으로 띄운다(기동 실패는 @SpringBootTest로 단언할 수 없다 — DefaultLimitStartupCheckTest와 같은 방식).
 * 부팅마다 새 데이터베이스를 만든다 — 앞 부팅이 적용한 마이그레이션이 Flyway target·위치 조건을 섞지 않게.
 *
 * 실패 단언은 원인 사슬에 설정 바인딩 오류(BindException)가 없을 것을 함께 요구한다 — 구현 전 `counter-trigger` 같은 새 값은
 * 열거형 변환에서 실패하므로, 그 실패를 '기동 검사가 막았다'로 잘못 세지 않게 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CounterDriftStartupCheckTest {

    private val postgres = PostgreSQLContainer("postgres:16")
    private val seq = AtomicInteger()

    @BeforeAll
    fun startDb() = postgres.start()

    @AfterAll
    fun stopDb() = postgres.stop()

    private fun freshDbUrl(): String {
        val name = "boot_${seq.incrementAndGet()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$name"
    }

    /**
     * [props]가 같은 키를 주면 기본값을 덮는다(명령행 인자에 같은 키를 두 번 넣지 않는다). null 값 = 그 기본 키를 빼라.
     * ADR-009 후보·정리(A·C·정리)는 끔이 기본 키다 — 이 클래스의 조합들은 ADR-009 기본값 전환(명세 §12 — A·C·정리 ① 켬) 전에 '그 밖은 끔'으로 쓰였다.
     * reconciler 프로필 경우는 세 키를 null로 빼서 프로필 yml(application-reconciler.yml)이 정하게 한다(명령행이 프로필보다 우선하므로).
     */
    private fun boot(props: Map<String, String?>): ConfigurableApplicationContext {
        val base = mapOf<String, String?>(
            "spring.datasource.url" to freshDbUrl(),
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to postgres.password,
            "server.port" to "0",
            "seat.hold.expiry-scheduler-enabled" to "false",
        ) + ADR009_OFF
        val args = (base + props).filterValues { it != null }.map { (k, v) -> "--$k=$v" }
        return SpringApplicationBuilder(SeatReservationApplication::class.java).run(*args.toTypedArray())
    }

    /**
     * [keyword] 강화(코드 루프 1 L1-11 fix verification): 기동 실패가 **기동 검사의 그 조건** 때문인지 — 원인 사슬의 IllegalStateException 메시지에 키워드가 있어야 한다
     * (다른 이유로 뜨지 못한 것을 '막았다'로 세지 않게).
     */
    private fun assertBootFails(props: Map<String, String?>, why: String, keyword: String) {
        val error = try {
            boot(props).close()
            fail("떴다 — $why: $props")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e
        }
        val chain = causesOf(error)
        assertTrue(
            chain.none { it is BindException },
            "설정 바인딩 실패(기동 검사가 아니다 — 새 설정 값이 아직 없다?) $why: ${chain.map { "${it::class.simpleName}: ${it.message?.take(200)}" }}",
        )
        assertTrue(
            chain.any { it is IllegalStateException && it.message.orEmpty().contains(keyword) },
            "기동 검사의 '$keyword' 조건으로 막힌 것이 아니다 — $why: ${chain.map { "${it::class.simpleName}: ${it.message?.take(300)}" }}",
        )
    }

    private fun assertBoots(props: Map<String, String?>, check: (ConfigurableApplicationContext) -> Unit = {}) {
        boot(props).use(check)
    }

    private val trigger = "classpath:db/migration,classpath:db/migration-trigger"

    private companion object {
        val ADR009_OFF = mapOf<String, String?>(
            "seat.hold.counter-drift.expiry-repair" to "false",
            "seat.hold.counter-drift.reconcile.enabled" to "false",
            "seat.hold.counter-drift.cleanup.mode" to "none",
        )
        /** 세 키를 빼라(앱 기본값·프로필 yml이 정한다) */
        val ADR009_UNSET = ADR009_OFF.mapValues { null }
    }

    // ---- 쿼터 테이블 없음 -----------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["counter", "counter-upsert", "counter-upsert-precheck", "quota-lock", "quota-nowait", "quota-nowait-early"])
    fun `쿼터 테이블 없음(Flyway target 3 — V5 전) + 쿼터 테이블을 쓰는 방식 — 기동 실패`(limit: String) {
        assertBootFails(mapOf("spring.flyway.target" to "3", "seat.hold.limit-strategy" to limit), "쿼터 테이블 없이 $limit", keyword = "쿼터 테이블(user_hold_quota")
    }

    @Test
    fun `쿼터 테이블 없음 + 매수 방식 미지정(기본 counter-upsert-precheck) — 기동 실패`() {
        assertBootFails(mapOf("spring.flyway.target" to "3"), "기본 매수 방식인데 쿼터 테이블 없음", keyword = "쿼터 테이블(user_hold_quota")
    }

    @Test
    fun `쿼터 테이블 없음 + counter-trigger(트리거 위치 포함) — 기동 실패`() {
        assertBootFails(
            mapOf("spring.flyway.target" to "3", "seat.hold.limit-strategy" to "counter-trigger", "spring.flyway.locations" to trigger),
            "쿼터 테이블 없이 counter-trigger",
            keyword = "쿼터 테이블(user_hold_quota",
        )
    }

    @Test
    fun `쿼터 테이블 없음 + none — 뜬다`() {
        assertBoots(mapOf("spring.flyway.target" to "3", "seat.hold.limit-strategy" to "none")) {
            assertEquals(UserLimitStrategyType.NONE, it.getBean(SeatHoldProperties::class.java).limitStrategy)
        }
    }

    // ---- 트리거 ⇔ counter-trigger --------------------------------------------------------------------------------

    @Test
    fun `counter-trigger + 트리거 위치 — 뜬다(아래 실패 단언들의 양성 대조)`() {
        assertBoots(mapOf("seat.hold.limit-strategy" to "counter-trigger", "spring.flyway.locations" to trigger)) {
            assertEquals(UserLimitStrategyType.COUNTER_TRIGGER, it.getBean(SeatHoldProperties::class.java).limitStrategy)
        }
    }

    @Test
    fun `counter-trigger인데 트리거가 없다(위치 미포함) — 기동 실패`() {
        assertBootFails(mapOf("seat.hold.limit-strategy" to "counter-trigger"), "트리거 없이 counter-trigger", keyword = "카운터 트리거")
    }

    @ParameterizedTest
    @ValueSource(strings = ["counter-upsert-precheck", "counter-upsert", "none"])
    fun `트리거가 있는데 counter-trigger가 아니다 — 기동 실패(앱 증감과 트리거가 겹치거나 측정 조건이 섞인다)`(limit: String) {
        assertBootFails(mapOf("seat.hold.limit-strategy" to limit, "spring.flyway.locations" to trigger), "트리거 + $limit", keyword = "카운터 트리거")
    }

    // ---- 후보 × 매수 방식 조합 ----------------------------------------------------------------------------------------

    @Test
    fun `카운터를 유지하지 않는 매수 방식(none) + A(expiry-repair) — 기동 실패`() {
        assertBootFails(
            mapOf("seat.hold.limit-strategy" to "none", "seat.hold.counter-drift.expiry-repair" to "true"),
            "none에는 고칠 카운터 감소가 없다",
            keyword = "카운터를 유지하는 매수 방식",
        )
    }

    @Test
    fun `카운터를 유지하지 않는 매수 방식(none) + C(reconcile) — 기동 실패`() {
        assertBootFails(
            mapOf("seat.hold.limit-strategy" to "none", "seat.hold.counter-drift.reconcile.enabled" to "true"),
            "none에는 대조할 카운터가 없다",
            keyword = "카운터를 유지하는 매수 방식",
        )
    }

    @Test
    fun `기본 매수 방식 + A + C — 뜬다(위 조합 실패의 양성 대조)`() {
        assertBoots(
            mapOf(
                "seat.hold.counter-drift.expiry-repair" to "true",
                "seat.hold.counter-drift.reconcile.enabled" to "true",
                "seat.hold.counter-drift.reconcile.interval" to "1h",
            ),
        )
    }

    // ---- reconciler 프로필(F) ------------------------------------------------------------------------------------

    @Test
    fun `reconciler 프로필 + 만료 스케줄러 켬 — 기동 실패(대조 프로세스가 만료까지 돌면 앱과 이중 만료)`() {
        assertBootFails(
            ADR009_UNSET + mapOf("spring.profiles.active" to "reconciler", "seat.hold.expiry-scheduler-enabled" to "true"),
            "reconciler 프로필에서 만료 스케줄러",
            keyword = "만료 스케줄러를 꺼야 한다",
        )
    }

    @Test
    fun `reconciler 프로필만 주면 뜨고 만료 스케줄러 빈이 없다(프로필이 스케줄러를 끈다)`() {
        assertBoots(ADR009_UNSET + mapOf("spring.profiles.active" to "reconciler", "seat.hold.expiry-scheduler-enabled" to null)) {
            assertTrue(it.getBeansOfType(HoldExpiryScheduler::class.java).isEmpty(), "reconciler 프로필에 만료 스케줄러가 떴다")
        }
    }
}
