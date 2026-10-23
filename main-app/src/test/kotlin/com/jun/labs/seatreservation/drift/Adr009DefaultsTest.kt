package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.SeatReservationApplication
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.support.deleteQuota
import com.jun.labs.seatreservation.support.insertSeatRow
import com.jun.labs.seatreservation.support.setCnt
import org.springframework.test.annotation.DirtiesContext
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties.CleanupMode
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.impl.drift.CounterDriftScheduling
import com.jun.labs.seatreservation.service.impl.drift.DetectShape
import com.jun.labs.seatreservation.service.impl.drift.IncrementalMethod
import com.jun.labs.seatreservation.incremental.V10_TABLES
import com.jun.labs.seatreservation.incremental.columnExists
import com.jun.labs.seatreservation.incremental.tableExists
import com.jun.labs.seatreservation.incremental.triggerExists
import org.springframework.jdbc.core.JdbcTemplate
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 옛 방식(ADR-009 결정 — 명세 §12) 값 판정 한 곳: A 켬 · C 켬(60s) · 정리 ① schedule · 판매 마감 켬 · external-reconciler 끔 · 증분 none·끔 · 안전망 끔 · one-shot.
 * = 코드 기본값(SeatHoldProperties · core ReconcileProperties — ADR-012 교체 뒤에도 그대로) = ADR-012 명세 §10 K2의 실효 값.
 */
private fun assertAdr009Defaults(p: SeatHoldProperties, where: String) {
    assertEquals(true, p.counterDrift.expiryRepair, "$where: A(expiry-repair) 켬")
    assertEquals(true, p.counterDrift.reconcile.enabled, "$where: C(reconcile.enabled) 켬")
    assertEquals(Duration.ofSeconds(60), p.counterDrift.reconcile.interval, "$where: C 주기 60s")
    assertEquals(CleanupMode.SCHEDULE, p.counterDrift.cleanup.mode, "$where: 정리 ① schedule")
    assertEquals(true, p.salesClose.enabled, "$where: 판매 마감 켬(그대로)")
    assertEquals(false, p.counterDrift.externalReconciler, "$where: external-reconciler 끔(F는 기본 아님)")
    assertEquals(IncrementalMethod.NONE, p.counterDrift.reconcile.incremental.method, "$where: 증분 방식 none")
    assertEquals(false, p.counterDrift.reconcile.incremental.enabled, "$where: 증분 끔")
    assertEquals(false, p.counterDrift.reconcile.safetyNet.enabled, "$where: 안전망 끔")
    assertEquals(DetectShape.ONE_SHOT, p.counterDrift.reconcile.detectShape, "$where: 탐지 모양 one-shot")
}

/**
 * 업무 앱 기본값(ADR-012 §8.9 판정 · 명세 §10 K1 — main-app application.yml) 판정 한 곳: A 켬 · C 끔 · 증분 trigger 켬 1s · 안전망 켬 · split ·
 * 정리 ① schedule · 판매 마감 켬 · external-reconciler 끔.
 */
private fun assertAppDefaults(p: SeatHoldProperties, where: String) {
    assertEquals(true, p.counterDrift.expiryRepair, "$where: A(expiry-repair) 켬")
    assertEquals(false, p.counterDrift.reconcile.enabled, "$where: C(reconcile.enabled) 끔")
    assertEquals(IncrementalMethod.TRIGGER, p.counterDrift.reconcile.incremental.method, "$where: 증분 방식 trigger(ⓑ)")
    assertEquals(true, p.counterDrift.reconcile.incremental.enabled, "$where: 증분 켬")
    assertEquals(Duration.ofSeconds(1), p.counterDrift.reconcile.incremental.interval, "$where: 증분 주기 1s")
    assertEquals(true, p.counterDrift.reconcile.safetyNet.enabled, "$where: 안전망 켬")
    assertEquals(DetectShape.SPLIT, p.counterDrift.reconcile.detectShape, "$where: 탐지 모양 split")
    assertEquals(CleanupMode.SCHEDULE, p.counterDrift.cleanup.mode, "$where: 정리 ① schedule")
    assertEquals(true, p.salesClose.enabled, "$where: 판매 마감 켬(그대로)")
    assertEquals(false, p.counterDrift.externalReconciler, "$where: external-reconciler 끔(F는 기본 아님)")
}

/** 업무 앱 기본 Flyway 위치(명세 §10 K1) — 기본 + V9 트리거 위치 + V10 */
private const val APP_DEFAULT_LOCATIONS = "classpath:db/migration,classpath:db/migration-updated-at-trigger,classpath:db/migration-incremental"

/**
 * 기본값 바인딩(스프링 컨텍스트 없이 — ADR-008 SeatHoldPropertiesDefaultTest와 같은 방식):
 * 코드 기본값 경로(설정 키 없음 — ADR-009 값 그대로, ADR-012 교체는 yml에만) · application.yml 경로(업무 앱 기본값 — ADR-012) · reconciler 프로필 경로.
 */
class Adr009DefaultBindingTest {

    private fun yaml(name: String) = YamlPropertySourceLoader().load(name, ClassPathResource(name))

    @Test
    fun `코드 기본값 — 생성자에 후보·정리를 주지 않으면 ADR-009 값(A 켬 · C 켬 · 정리 ① schedule · 증분·안전망 끔 · one-shot)`() {
        assertAdr009Defaults(SeatHoldProperties(ttl = Duration.ofMinutes(5), maxPerUser = 2, expiryInterval = Duration.ofSeconds(10)), "생성자")
    }

    @Test
    fun `코드 기본값 — counter-drift 키가 없는 소스로 바인딩해도 같다(업무 앱 기본값은 코드가 아니라 application_yml)`() {
        val source = MapConfigurationPropertySource(
            mapOf("seat.hold.ttl" to "5m", "seat.hold.max-per-user" to "2", "seat.hold.expiry-interval" to "10s"),
        )
        assertAdr009Defaults(Binder(source).bind("seat.hold", SeatHoldProperties::class.java).get(), "키 없는 바인딩")
    }

    @Test
    fun `application_yml 경로 — 업무 앱 기본값 = ADR-012(A 켬 · C 끔 · 증분 trigger 1s · 안전망 · split · 정리 ①) · Flyway 위치 V9 트리거 + V10`() {
        val sources = yaml("application.yml")
        val props = Binder(ConfigurationPropertySources.from(sources)).bind("seat.hold", SeatHoldProperties::class.java).get()
        assertAppDefaults(props, "application.yml")
        assertEquals(UserLimitStrategyType.COUNTER_UPSERT_PRECHECK, props.limitStrategy, "매수 방식 기본값(ADR-008) 그대로")
        assertEquals(APP_DEFAULT_LOCATIONS, sources.firstNotNullOfOrNull { it.getProperty("spring.flyway.locations") }?.toString(), "Flyway 위치")
    }

    @Test
    fun `reconciler 프로필 — application_yml 위에 얹으면 C 전체 대조만 켜고 A·정리·증분·안전망은 끈다(명세 §10 K8)`() {
        val env = StandardEnvironment()
        // 프로필 파일이 기본 파일보다 우선(Spring Boot 설정 데이터 순서) — 앞에 둔다
        yaml("application-reconciler.yml").forEach { env.propertySources.addLast(it) }
        yaml("application.yml").forEach { env.propertySources.addLast(it) }
        val props = Binder.get(env).bind("seat.hold", SeatHoldProperties::class.java).get()
        assertEquals(true, props.counterDrift.reconcile.enabled, "F: 대조 켬")
        assertEquals(false, props.counterDrift.expiryRepair, "F: A 끔(만료를 하지 않는다 · counter-trigger와도 뜬다)")
        assertEquals(CleanupMode.NONE, props.counterDrift.cleanup.mode, "F: 정리 끔(업무 앱이 한다)")
        assertEquals(false, props.counterDrift.reconcile.incremental.enabled, "F: 증분 끔")
        assertEquals(false, props.counterDrift.reconcile.safetyNet.enabled, "F: 안전망 끔")
    }
}

/**
 * 업무 앱 기본값 기동(ADR-012 명세 §10 K1~K8 — ADR-009 명세 §12의 키 없는 기동 테스트를 ADR-012 값으로 다시 씀):
 * 설정 없이 띄우면 기동 검사를 통과하고 A·증분·안전망·정리 ① 스케줄러가 돈다(C 스케줄러는 없다). 기본값과 맞지 않는 조합은 기동 검사가 거부하고
 * (정책: 기본값을 조용히 끄지 않는다 — 끄라고 명시해야 뜬다), 안내대로 키를 주면 뜬다.
 * 실제 SpringApplication으로 띄운다(기동 실패는 @SpringBootTest로 단언할 수 없다 — DefaultLimitStartupCheckTest와 같은 방식). 부팅마다 새 DB(K3만 같은 DB 두 번).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Adr009DefaultBootTest {

    private val postgres = PostgreSQLContainer("postgres:16")
    private val seq = AtomicInteger()

    @BeforeAll fun startDb() = postgres.start()
    @AfterAll fun stopDb() = postgres.stop()

    /**
     * 전제(리뷰 F5): 미지정 경로를 실제로 타는지 부팅마다 먼저 확인한다 — 환경·시스템 속성에 seat.hold 키, SPRING_APPLICATION_JSON, 외부 설정 위치(SPRING_CONFIG_*·spring.config.*),
     * Flyway 키(SPRING_FLYWAY*·spring.flyway.*)가 없어야 한다. 있으면 그 값이 '기본값'을 덮었을 수 있다(assert — 조용히 건너뛰지 않는다).
     */
    private fun assertNoOutsideConfig() {
        val env = System.getenv().keys
        val props = System.getProperties().stringPropertyNames()
        assertTrue(env.none { it.uppercase().startsWith("SEAT_HOLD") }, "환경 변수에 SEAT_HOLD*: ${env.filter { it.uppercase().startsWith("SEAT_HOLD") }}")
        assertTrue("SPRING_APPLICATION_JSON" !in env && "spring.application.json" !in props, "SPRING_APPLICATION_JSON / spring.application.json이 있다")
        assertTrue(env.none { it.startsWith("SPRING_CONFIG_") || it.startsWith("SPRING_FLYWAY") }, "환경 변수에 SPRING_CONFIG_*·SPRING_FLYWAY*")
        assertTrue(props.none { it.startsWith("seat.hold") || it.startsWith("spring.config.") || it.startsWith("spring.flyway.") }, "시스템 속성에 seat.hold*·spring.config.*·spring.flyway.*")
    }

    private fun newDb(): String {
        val name = "adr009d_${seq.incrementAndGet()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        return "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$name"
    }

    /** 접속 정보와 포트만 준다 — seat.hold.*·spring.flyway.*는 [extra]가 주는 것 말고 하나도 없다(만료 스케줄러도 기본값 그대로 켬). */
    private fun boot(vararg extra: String, url: String = newDb()): ConfigurableApplicationContext {
        assertNoOutsideConfig()
        return SpringApplicationBuilder(SeatReservationApplication::class.java).run(
            "--spring.datasource.url=$url",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--server.port=0",
            *extra,
        )
    }

    private fun assertBootFails(keyword: String, vararg extra: String): String {
        val error = try {
            boot(*extra).close()
            fail("떴다: ${extra.toList()}")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e
        }
        val chain = causesOf(error)
        assertTrue(chain.none { it is BindException }, "설정 바인딩 실패(기동 검사가 아니다): ${chain.map { "${it::class.simpleName}: ${it.message?.take(200)}" }}")
        val hit = chain.firstOrNull { it is IllegalStateException && it.message.orEmpty().contains(keyword) }
        assertTrue(hit != null, "'$keyword' 기동 검사가 아니다: ${chain.map { "${it::class.simpleName}: ${it.message?.take(300)}" }}")
        return hit.message.orEmpty()
    }

    private fun threadsNamed(prefix: String) = Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith(prefix) }

    /** 스케줄러 스레드 수(같은 JVM의 캐시된 테스트 컨텍스트도 같은 이름을 가진다 — 부팅 전후 차이로 센다) */
    private fun schedulerThreads() = listOf("counterReconcileScheduler-", "counterIncrementalScheduler-", "counterSafetyNetScheduler-", "quotaCleanupScheduler-")
        .associateWith { threadsNamed(it) }

    private fun JdbcTemplate.schema() = mapOf(
        "updated_at" to columnExists("user_hold_quota", "updated_at"),
        "trigger" to triggerExists("user_hold_quota_touch"),
        "V10" to V10_TABLES.all { tableExists(it) },
    )

    private fun JdbcTemplate.runSources(): Set<String> = queryForList("SELECT DISTINCT source FROM counter_reconcile_run", String::class.java).toSet()

    private fun await(what: String, timeout: Duration, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!cond()) {
            if (System.nanoTime() > deadline) fail("$timeout 안에 일어나지 않았다: $what")
            Thread.sleep(200)
        }
    }

    /** K2 옛 방식 복귀 키(새 DB) — 업무 앱 기본값에서 증분·안전망을 끄고 C · one-shot · 기본 위치로 */
    private val oldWay = arrayOf(
        "--seat.hold.counter-drift.reconcile.enabled=true",
        "--seat.hold.counter-drift.reconcile.incremental.enabled=false",
        "--seat.hold.counter-drift.reconcile.incremental.method=none",
        "--seat.hold.counter-drift.reconcile.safety-net.enabled=false",
        "--seat.hold.counter-drift.reconcile.detect-shape=one-shot",
        "--spring.flyway.locations=classpath:db/migration",
    )

    @Test
    fun `K1 설정 없이 기동 — 기동 검사 통과 · 업무 앱 기본값 · V9 트리거·V10 스키마 · 증분·안전망·정리 스레드(C 없음) · 기록 source inc-app-trg-1s·safety-app`() {
        val t0 = schedulerThreads()
        boot().use { ctx ->
            val props = ctx.getBean(SeatHoldProperties::class.java)
            assertAppDefaults(props, "기동")
            assertEquals(UserLimitStrategyType.COUNTER_UPSERT_PRECHECK, props.limitStrategy)
            val db = ctx.getBean(JdbcTemplate::class.java)
            assertEquals(mapOf("updated_at" to true, "trigger" to true, "V10" to true), db.schema(), "스키마 = 기본 위치 + V9 트리거 + V10")
            // 전용 스케줄러 스레드(CounterDriftScheduling) — 작업을 걸면 코어 스레드가 바로 뜬다(첫 실행은 한 주기 뒤)
            val d = schedulerThreads().mapValues { (k, v) -> v - t0.getValue(k) }
            assertEquals(
                mapOf("counterReconcileScheduler-" to 0, "counterIncrementalScheduler-" to 1, "counterSafetyNetScheduler-" to 1, "quotaCleanupScheduler-" to 1), d,
                "스케줄러 스레드(부팅 전후 차이)",
            )
            // 기록 출처: 증분 틱은 1초마다(빈 틱도 1행) · 안전망 첫 실행은 한 주기(1분) 뒤
            await("증분·안전망 기록(inc-app-trg-1s · safety-app)", Duration.ofSeconds(100)) { db.runSources().containsAll(setOf("inc-app-trg-1s", "safety-app")) }
            assertEquals(setOf("inc-app-trg-1s", "safety-app"), db.runSources(), "C(reconcile-app) 바퀴가 없다")
        }
    }

    @Test
    fun `K2 옛 방식 복귀(새 DB) — C 60초 · one-shot · V1~V7 스키마(updated_at·V10 없음) · C 스레드만`() {
        val t0 = schedulerThreads()
        boot(*oldWay).use { ctx ->
            assertAdr009Defaults(ctx.getBean(SeatHoldProperties::class.java), "옛 방식 복귀")
            assertEquals("reconcile-app", ctx.getBean(CounterDriftScheduling::class.java).reconcileSource())
            val db = ctx.getBean(JdbcTemplate::class.java)
            assertEquals(mapOf("updated_at" to false, "trigger" to false, "V10" to false), db.schema())
            assertEquals(0, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version::int > 7", Int::class.java), "V8 이후 미적용")
            val d = schedulerThreads().mapValues { (k, v) -> v - t0.getValue(k) }
            assertEquals(
                mapOf("counterReconcileScheduler-" to 1, "counterIncrementalScheduler-" to 0, "counterSafetyNetScheduler-" to 0, "quotaCleanupScheduler-" to 1), d,
                "스케줄러 스레드(부팅 전후 차이)",
            )
        }
    }

    @Test
    fun `K3 옛 방식 복귀(V9·V10 적용된 DB) — method trigger · 위치 기본값 그대로면 뜨고 C만 돈다(트리거는 남는다)`() {
        val url = newDb()
        boot(url = url).close()   // 업무 앱 기본값으로 V9 트리거·V10 적용
        val t0 = schedulerThreads()
        boot(
            "--seat.hold.counter-drift.reconcile.enabled=true", "--seat.hold.counter-drift.reconcile.incremental.enabled=false",
            "--seat.hold.counter-drift.reconcile.safety-net.enabled=false", "--seat.hold.counter-drift.reconcile.detect-shape=one-shot",
            url = url,
        ).use { ctx ->
            val p = ctx.getBean(SeatHoldProperties::class.java)
            assertEquals(true, p.counterDrift.reconcile.enabled)
            assertEquals(IncrementalMethod.TRIGGER, p.counterDrift.reconcile.incremental.method)
            assertEquals(mapOf("updated_at" to true, "trigger" to true, "V10" to true), ctx.getBean(JdbcTemplate::class.java).schema(), "트리거는 남는다")
            val d = schedulerThreads().mapValues { (k, v) -> v - t0.getValue(k) }
            assertEquals(
                mapOf("counterReconcileScheduler-" to 1, "counterIncrementalScheduler-" to 0, "counterSafetyNetScheduler-" to 0, "quotaCleanupScheduler-" to 1), d,
                "C만 돈다(정리는 기본값 그대로)",
            )
        }
    }

    @Test
    fun `K4 none + 키 없음 — 기동 검사가 거부하고 끌 키(A·정리·증분·안전망·method·위치)를 안내한다(기본값을 조용히 끄지 않는다)`() {
        val msg = assertBootFails("카운터를 유지하는 매수 방식", "--seat.hold.limit-strategy=none")
        listOf(
            "expiry-repair=false", "cleanup.mode=none", "incremental.enabled=false", "safety-net.enabled=false", "incremental.method=none",
            "spring.flyway.locations=classpath:db/migration",
        ).forEach { assertTrue(msg.contains(it), "끄라는 안내에 $it 가 없다: $msg") }
    }

    @Test
    fun `K4 none + 안내한 키 명시 — 뜬다(ADR-001~008 하네스·none 고정 테스트의 경로) · V9·V10 없음`() {
        boot(
            "--seat.hold.limit-strategy=none", "--seat.hold.counter-drift.expiry-repair=false", "--seat.hold.counter-drift.cleanup.mode=none",
            "--seat.hold.counter-drift.reconcile.incremental.enabled=false", "--seat.hold.counter-drift.reconcile.safety-net.enabled=false",
            "--seat.hold.counter-drift.reconcile.incremental.method=none", "--spring.flyway.locations=classpath:db/migration",
        ).use { ctx ->
            val p = ctx.getBean(SeatHoldProperties::class.java)
            assertEquals(UserLimitStrategyType.NONE, p.limitStrategy)
            assertEquals(false, p.counterDrift.expiryRepair)
            assertEquals(false, p.counterDrift.reconcile.enabled)
            assertEquals(CleanupMode.NONE, p.counterDrift.cleanup.mode)
            assertEquals(mapOf("updated_at" to false, "trigger" to false, "V10" to false), ctx.getBean(JdbcTemplate::class.java).schema())
        }
    }

    @Test
    fun `K5 counter-trigger + A 미지정 — A·B 함께 금지로 거부(증분 끔 안내 포함), A·증분 끔 명시면 정리 ①·안전망 기본값 그대로 뜬다`() {
        val trigger = "--spring.flyway.locations=$APP_DEFAULT_LOCATIONS,classpath:db/migration-trigger"
        val msg = assertBootFails("함께 쓰지 않는다", "--seat.hold.limit-strategy=counter-trigger", trigger)
        assertTrue(msg.contains("expiry-repair=false") && msg.contains("incremental.enabled=false"), "A·증분 끄라는 안내가 없다: $msg")
        boot(
            "--seat.hold.limit-strategy=counter-trigger", trigger, "--seat.hold.counter-drift.expiry-repair=false",
            "--seat.hold.counter-drift.reconcile.incremental.enabled=false",
        ).use { ctx ->
            val p = ctx.getBean(SeatHoldProperties::class.java)
            assertEquals(false, p.counterDrift.reconcile.enabled)
            assertEquals(true, p.counterDrift.reconcile.safetyNet.enabled)
            assertEquals(CleanupMode.SCHEDULE, p.counterDrift.cleanup.mode)
        }
    }

    @Test
    fun `counter + 키 없음 — 정리 ①이 거부되고 안내는 정리만 끄라고 한다(A·증분·안전망은 허용)`() {
        val msg = assertBootFails("쿼터 행 정리", "--seat.hold.limit-strategy=counter")
        assertTrue(msg.contains("cleanup.mode=none") && !msg.contains("expiry-repair=false"), "정리만 끄라는 안내가 아니다: $msg")
        boot("--seat.hold.limit-strategy=counter", "--seat.hold.counter-drift.cleanup.mode=none").close()   // 안내대로 하면 A·증분·안전망 켠 채 뜬다
    }

    @Test
    fun `판매 마감 끔 + 키 없음 — 정리 ①이 거부되고 안내는 정리만 끄라고 한다(A·증분·안전망은 허용)`() {
        val msg = assertBootFails("정리 ①(cleanup.mode=schedule)", "--seat.hold.sales-close.enabled=false")
        assertTrue(msg.contains("cleanup.mode=none") && !msg.contains("expiry-repair=false"), "정리만 끄라는 안내가 아니다: $msg")
        boot("--seat.hold.sales-close.enabled=false", "--seat.hold.counter-drift.cleanup.mode=none").close()
    }

    @Test
    fun `K6 업무 앱 external-reconciler + 키 없음(증분·안전망 기본 켬) — 대조 중복으로 거부, 증분·안전망 끔 명시면 뜬다`() {
        val msg = assertBootFails("대조가 두 프로세스에서 겹친다", "--seat.hold.counter-drift.external-reconciler=true")
        assertTrue(msg.contains("incremental.enabled=false") && msg.contains("safety-net.enabled=false"), "끄라는 안내가 없다: $msg")
        // 하나만 켜도 거부(안전망만 남김)
        assertBootFails(
            "대조가 두 프로세스에서 겹친다", "--seat.hold.counter-drift.external-reconciler=true", "--seat.hold.counter-drift.reconcile.incremental.enabled=false",
        )
        boot(
            "--seat.hold.counter-drift.external-reconciler=true", "--seat.hold.counter-drift.reconcile.incremental.enabled=false",
            "--seat.hold.counter-drift.reconcile.safety-net.enabled=false",
        ).use { ctx ->
            val p = ctx.getBean(SeatHoldProperties::class.java)
            assertEquals(true, p.counterDrift.externalReconciler)
            assertEquals(false, p.counterDrift.reconcile.enabled)
            assertEquals(true, p.counterDrift.expiryRepair, "A는 기본값 그대로(F는 A가 필수)")
        }
    }

    @Test
    fun `K7 C만 켬(증분 기본 켬 그대로) — 옛 방식 복귀 착각으로 거부하고 K2 조합을 안내한다`() {
        val msg = assertBootFails("함께 켜지 않는다", "--seat.hold.counter-drift.reconcile.enabled=true")
        assertTrue(msg.contains("incremental.enabled=true"), "막은 조합(증분)이 메시지에 없다: $msg")
        listOf("incremental.enabled=false", "incremental.method=none", "safety-net.enabled=false", "detect-shape=one-shot", "spring.flyway.locations=classpath:db/migration")
            .forEach { assertTrue(msg.contains(it), "옛 방식 안내에 $it 가 없다: $msg") }
    }

    @Test
    fun `K8 reconciler 프로필 + 키 없음 — 뜨고 C 전체 대조만 한다(A·정리·증분·안전망 끔 · 출처 reconcile-proc)`() {
        val t0 = schedulerThreads()
        boot("--spring.profiles.active=reconciler").use { ctx ->
            val p = ctx.getBean(SeatHoldProperties::class.java)
            assertEquals(true, p.counterDrift.reconcile.enabled)
            assertEquals(false, p.counterDrift.expiryRepair)
            assertEquals(CleanupMode.NONE, p.counterDrift.cleanup.mode)
            assertEquals(false, p.counterDrift.reconcile.incremental.enabled)
            assertEquals(false, p.counterDrift.reconcile.safetyNet.enabled)
            assertEquals("reconcile-proc", ctx.getBean(CounterDriftScheduling::class.java).reconcileSource())
            val d = schedulerThreads().mapValues { (k, v) -> v - t0.getValue(k) }
            assertEquals(
                mapOf("counterReconcileScheduler-" to 1, "counterIncrementalScheduler-" to 0, "counterSafetyNetScheduler-" to 0, "quotaCleanupScheduler-" to 0), d,
                "C 스레드만",
            )
        }
    }
}

/**
 * IntegrationTest의 고정(ADR-009 끔 · ADR-012 옛 동작 — 명세 §10 K10)이 의도대로 걸리는지(기존 테스트 의미 보존의 전제):
 * 고정만 있으면 셋 다 끔 + 증분·안전망 끔 · method none · one-shot · V1~V7 스키마, 하위 클래스가 @TestPropertySource로 켜면 그쪽이 이긴다(켜는 기존 테스트가 실제로 켠 채 돈다).
 */
class Adr009PinnedOffTest : IntegrationTest() {
    @Autowired lateinit var properties: SeatHoldProperties

    @Test
    fun `IntegrationTest 기본 컨텍스트 — A 끔 · C 끔 · 정리 끔(ADR-009 전환 전 기본값) · 증분·안전망 끔 · method none · one-shot · V9·V10 없음`() {
        assertEquals(false, properties.counterDrift.expiryRepair)
        assertEquals(false, properties.counterDrift.reconcile.enabled)
        assertEquals(CleanupMode.NONE, properties.counterDrift.cleanup.mode)
        assertEquals(false, properties.counterDrift.reconcile.incremental.enabled)
        assertEquals(IncrementalMethod.NONE, properties.counterDrift.reconcile.incremental.method)
        assertEquals(false, properties.counterDrift.reconcile.safetyNet.enabled)
        assertEquals(DetectShape.ONE_SHOT, properties.counterDrift.reconcile.detectShape)
        assertEquals(false, jdbcTemplate.columnExists("user_hold_quota", "updated_at"))
        assertEquals(false, jdbcTemplate.triggerExists("user_hold_quota_touch"))
        assertEquals(false, jdbcTemplate.tableExists("counter_safety_run"))
    }
}

/** ExpiryRepairImplTest와 같은 속성(컨텍스트 공유) — @TestPropertySource가 IntegrationTest 고정을 덮는다. */
@TestPropertySource(properties = ["seat.hold.counter-drift.expiry-repair=true"])
class Adr009PinOverrideTest : IntegrationTest() {
    @Autowired lateinit var properties: SeatHoldProperties

    @Test
    fun `하위 클래스가 켠 A는 켜진다 — 나머지 고정(C·정리 끔)은 그대로`() {
        assertEquals(true, properties.counterDrift.expiryRepair)
        assertEquals(false, properties.counterDrift.reconcile.enabled)
        assertEquals(CleanupMode.NONE, properties.counterDrift.cleanup.mode)
    }
}

/**
 * ADR-009 기본값(= ADR-012 명세 §10 K2 옛 방식)과 같은 실효 설정(A 켬 · C 켬 · 정리 ① schedule — IntegrationTest 끔 고정을 @TestPropertySource로 덮는다, 주기만 짧게 ·
 * 증분·안전망 끔 · one-shot은 IntegrationTest 고정 그대로)에서 한 바퀴(리뷰 F5):
 * 어긋남 주입(행 삭제 · cnt 낮춤 · cnt 높임) → 만료(A가 고침) → 대조(C가 고침 — 백그라운드 스케줄러) → 회차 마감 → 정리 ①(백그라운드)이 행을 지운다.
 * 순서: C는 열린 회차만 보므로, A가 고칠 어긋남은 **마감한 회차**(s1)에 넣는다 — 열린 회차에 넣으면 짧은 주기의 대조가 만료보다 먼저 고칠 수 있다(경합).
 * 이것이 설계 근거 그대로다(대조는 마감 회차를 보지 않는다 → A 필수). 그래서 실제 순서 = C 단계(열린 s2) → s1 마감 → A 단계(s1) → 정리 ①(s1).
 * 짧은 주기 스케줄러가 다른 테스트 데이터를 건드리지 않게 클래스가 끝나면 컨텍스트를 닫는다(컨텍스트마다 DB 컨테이너가 따로지만 이중 안전).
 */
@TestPropertySource(
    properties = [
        "seat.hold.counter-drift.expiry-repair=true",
        "seat.hold.counter-drift.reconcile.enabled=true",
        "seat.hold.counter-drift.reconcile.interval=300ms",
        "seat.hold.counter-drift.cleanup.mode=schedule",
        "seat.hold.counter-drift.cleanup.interval=300ms",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Adr009DefaultCycleTest : DriftTestSupport() {

    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase

    private var seatNo = 0
    private fun holdOn(scheduleId: Long, userId: Long): Long =
        holdSeat.hold(HoldSeatCommand(scheduleId, jdbcTemplate.insertSeatRow(scheduleId, 50_000 + ++seatNo), userId)).holdId

    private fun await(what: String, timeout: Duration = Duration.ofSeconds(20), cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!cond()) {
            if (System.nanoTime() > deadline) fail("$timeout 안에 일어나지 않았다: $what")
            Thread.sleep(100)
        }
    }

    /** (user_id, kind, source) — action = repaired만 */
    private fun repaired(scheduleId: Long): Set<Triple<Long, String, String>> = jdbcTemplate.query(
        "SELECT user_id, kind, source FROM counter_drift_log WHERE schedule_id = ? AND action = 'repaired'",
        { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getString(3)) }, scheduleId,
    ).toSet()

    private fun quotaRows(scheduleId: Long): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM user_hold_quota WHERE schedule_id = ?", Int::class.java, scheduleId)!!

    @Test
    fun `한 바퀴 — 주입 · 만료(A) · 대조(C) · 마감 · 정리 ①`() {
        assertAdr009Defaults(properties.copy(counterDrift = properties.counterDrift.copy(reconcile = properties.counterDrift.reconcile.copy(interval = Duration.ofSeconds(60)))), "실효 설정(주기 밖)")
        val s1 = newSchedule(NOW.plus(Duration.ofMinutes(10))).id!!   // 곧 마감할 회차 — A 단계·정리 ①
        val s2 = schedule.id!!                                          // 열린 회차(2099) — C 단계
        holdOn(s1, 101); holdOn(s1, 102)                                // 만료될 홀드(NOW + 5분)
        listOf(103L, 104L, 105L).forEach { u ->
            confirm.confirm(ConfirmReservationCommand(holdId = holdOn(s2, u), userId = u, paymentUid = "cycle-$u"))
        }
        listOf(101L, 102L).forEach { assertEquals(1, cntOf(s1, it)) }
        listOf(103L, 104L, 105L).forEach { assertEquals(1, cntOf(s2, it)) }

        // ① C 단계 — 열린 회차 s2: 행 삭제 · cnt 낮춤 · cnt 높임 → 백그라운드 대조가 고친다
        jdbcTemplate.deleteQuota(s2, 105)
        jdbcTemplate.setCnt(s2, 104, 0)
        jdbcTemplate.setCnt(s2, 103, 3)
        val wantC = setOf(Triple(103L, "above", "reconcile-app"), Triple(104L, "below", "reconcile-app"), Triple(105L, "missing", "reconcile-app"))
        await("대조(C)가 s2 세 쌍을 고친다") { repaired(s2) == wantC }
        listOf(103L, 104L, 105L).forEach { assertEquals(actualOf(s2, it), cntOf(s2, it), "C 뒤 s2 사용자 $it cnt = 실제") }

        // ② 마감 — s1 starts_at을 지나게(홀드 만료 시각도 지났다). 대조는 이제 s1을 보지 않는다
        clock.set(NOW.plus(Duration.ofMinutes(10)).plusSeconds(1))
        // ③ A 단계 — 마감한 s1: 행 삭제 · cnt 낮춤 → 만료 배치가 실패 쌍을 그 자리에서 고친다(틱 성공)
        jdbcTemplate.deleteQuota(s1, 101)
        jdbcTemplate.setCnt(s1, 102, 0)
        assertEquals(2, expire.expire(), "만료 2건 — 실패 쌍이 배치를 멈추지 않았다")
        assertEquals(setOf(Triple(101L, "missing", "expiry-repair"), Triple(102L, "below", "expiry-repair")), repaired(s1), "s1 복구 = A만")

        // ④ 정리 ① — 마감했고 홀드가 없는 s1의 쿼터 행을 백그라운드 정리가 지운다. 열린 s2는 그대로
        await("정리 ①이 s1 쿼터 행을 지운다") { quotaRows(s1) == 0 }
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM counter_cleanup_run WHERE mode = 'schedule' AND schedule_id = ? AND rows_deleted = 2", Int::class.java, s1,
            ),
            "정리 기록(s1 두 행 삭제)",
        )
        assertEquals(3, quotaRows(s2), "열린 회차 s2 행은 정리 대상이 아니다")
        listOf(103L, 104L, 105L).forEach { assertEquals(1, cntOf(s2, it)) }
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM counter_drift_log WHERE action = 'skipped' AND coalesce(reason, '') <> 'closed'", Int::class.java), "실패 skipped 0")
    }
}
