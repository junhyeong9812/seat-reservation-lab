package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.SeatReservationApplication
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties.CleanupMode
import com.jun.labs.seatreservation.service.impl.drift.DetectShape
import com.jun.labs.seatreservation.service.impl.drift.IncrementalMethod
import com.jun.labs.seatreservation.service.impl.drift.SafetyNetReconciler
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * ADR-012 task 09 기본값 교체 계약(명세 docs/plans/2026-10-18/adr-012-incremental-reconcile/requirement-spec.md §10 K1~K8·K10) — blind 테스트.
 * 구현 diff를 보지 않고 계약만으로 썼다. 손잡이 이름·스레드 이름·V9/V10 객체 이름은 교체 전(9ab4f768) 코드의 계약 표면에서 가져왔다
 * (ReconcileProperties.kt · CounterDriftScheduling.kt · V9__quota_updated_at_trigger.sql · V10__counter_safety_run.sql).
 * 실제 SpringApplication 기동(기동 실패는 @SpringBootTest로 단언할 수 없다 — Adr009DefaultBootTest와 같은 방식). 부팅마다 새 데이터베이스(K3만 예외 — 계약이 '적용된 기존 DB').
 * 실패 단언은 '기동 검사(IllegalStateException)가 기동을 끝낸다' + 메시지에 핵심 키 이름이 있다까지만 본다(문구 전체를 고정하지 않는다).
 */

private const val DRIFT = "seat.hold.counter-drift"
private const val REC = "$DRIFT.reconcile"
private const val INC_K = "$REC.incremental"
private const val SAFE_K = "$REC.safety-net"
private const val LOC_BASE = "classpath:db/migration"
private const val LOC_V8 = "classpath:db/migration-trigger"
private const val LOC_V9_TRG = "classpath:db/migration-updated-at-trigger"
private const val LOC_V10 = "classpath:db/migration-incremental"
/** K1 계약의 기본 위치(`db/migration` + V9 ⓑ + V10)를 명시한 값 — 교체 전 코드에서도 같은 스키마로 띄우려고 쓴다 */
private const val LOCS_NEW_DEFAULT = "$LOC_BASE,$LOC_V9_TRG,$LOC_V10"

private const val T_RECONCILE = "counterReconcileScheduler-"
private const val T_INCREMENTAL = "counterIncrementalScheduler-"
private const val T_SAFETY = "counterSafetyNetScheduler-"
private const val T_CLEANUP = "quotaCleanupScheduler-"

private val V10_TABLES = listOf("counter_safety_run", "counter_inc_gap", "counter_app_inject")

/** K2 옛 방식 복귀 키 조합(계약 원문 그대로) */
private val K2_KEYS = arrayOf(
    "--$REC.enabled=true",
    "--$INC_K.enabled=false",
    "--$INC_K.method=none",
    "--$SAFE_K.enabled=false",
    "--$REC.detect-shape=one-shot",
    "--spring.flyway.locations=$LOC_BASE",
)

/** K3 = K2에서 method=trigger · 위치 기본값 유지(spring.flyway.locations를 주지 않는다) */
private val K3_KEYS = arrayOf(
    "--$REC.enabled=true",
    "--$INC_K.enabled=false",
    "--$INC_K.method=trigger",
    "--$SAFE_K.enabled=false",
    "--$REC.detect-shape=one-shot",
)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Adr012DefaultsContractTest {

    private val postgres = PostgreSQLContainer("postgres:16")
    private val seq = AtomicInteger()

    @BeforeAll fun startDb() = postgres.start()
    @AfterAll fun stopDb() = postgres.stop()

    // ---------------------------------------------------------------- 기동 도구

    /** 미지정 경로를 실제로 타는지(Adr009DefaultBootTest 리뷰 F5와 같은 전제) — 밖의 설정이 '기본값'을 덮었으면 조용히 넘어가지 않는다. */
    private fun assertNoOutsideConfig() {
        val env = System.getenv().keys
        val props = System.getProperties().stringPropertyNames()
        assertTrue(env.none { it.uppercase().startsWith("SEAT_HOLD") }, "환경 변수에 SEAT_HOLD*")
        assertTrue(env.none { it.uppercase().startsWith("SPRING_FLYWAY") }, "환경 변수에 SPRING_FLYWAY*")
        assertTrue("SPRING_APPLICATION_JSON" !in env && "spring.application.json" !in props, "SPRING_APPLICATION_JSON이 있다")
        assertTrue(env.none { it.startsWith("SPRING_CONFIG_") } && env.none { it.startsWith("SPRING_PROFILES") }, "환경 변수에 SPRING_CONFIG_*·SPRING_PROFILES*")
        assertTrue(props.none { it.startsWith("seat.hold") || it.startsWith("spring.config.") || it.startsWith("spring.flyway") || it.startsWith("spring.profiles") }, "시스템 속성에 설정 키")
    }

    private fun freshDbUrl(): String {
        val name = "adr012k_${seq.incrementAndGet()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        return "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$name"
    }

    private fun jdbc(url: String) = JdbcTemplate(DriverManagerDataSource(url, postgres.username, postgres.password))

    private class Booted(val ctx: ConfigurableApplicationContext, val url: String, val newThreads: List<String>) : AutoCloseable {
        fun threads(prefix: String) = newThreads.count { it.startsWith(prefix) }
        val props: SeatHoldProperties get() = ctx.getBean(SeatHoldProperties::class.java)
        override fun close() = ctx.close()
    }

    /** 접속 정보·포트만 준다 — seat.hold.*·flyway·프로필은 [extra]가 주는 것뿐. 스레드는 기동 전 스냅숏과의 차이(같은 JVM의 다른 컨텍스트 스레드를 세지 않는다). */
    private fun boot(vararg extra: String, url: String = freshDbUrl()): Booted {
        assertNoOutsideConfig()
        val before = Thread.getAllStackTraces().keys.toSet()
        val ctx = SpringApplicationBuilder(SeatReservationApplication::class.java).run(
            "--spring.datasource.url=$url",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--server.port=0",
            *extra,
        )
        val added = Thread.getAllStackTraces().keys.filter { it.isAlive && it !in before }.map { it.name }
        return Booted(ctx, url, added)
    }

    /** 기동이 기동 검사(IllegalStateException)로 끝나고 그 메시지에 [keys]가 모두 있다. 바인딩 실패·다른 이유의 실패는 실패로 본다. 메시지를 돌려준다. */
    private fun assertBootRejected(extra: Array<String>, vararg keys: String): String {
        val error = try {
            boot(*extra).close()
            fail("기동이 거부되지 않았다: ${extra.toList()}")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e
        }
        val chain = causesOf(error)
        val dump = chain.map { "${it::class.simpleName}: ${it.message?.take(400)}" }
        assertTrue(chain.none { it is BindException }, "설정 바인딩 실패(기동 검사가 아니다): $dump")
        val hits = chain.filter { it is IllegalStateException && keys.all { k -> it.message.orEmpty().contains(k) } }
        assertTrue(hits.isNotEmpty(), "기동 검사 메시지에 ${keys.toList()}가 모두 있지 않다: $dump")
        return hits.first().message.orEmpty()
    }

    // ---------------------------------------------------------------- 스키마 조회

    private fun JdbcTemplate.hasColumn(table: String, column: String): Boolean = queryForObject(
        "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
        Int::class.java, table, column,
    )!! > 0

    /** 해석: 인덱스 이름(ADR §4.2.2 idx_user_hold_quota_updated_at)이 아니라 'user_hold_quota.updated_at 열을 키로 쓰는 인덱스가 있다'로 본다 */
    private fun JdbcTemplate.hasUpdatedAtIndex(): Boolean = queryForObject(
        """
        SELECT count(*) FROM pg_index i
        JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
        WHERE i.indrelid = 'user_hold_quota'::regclass AND a.attname = 'updated_at'
        """.trimIndent(),
        Int::class.java,
    )!! > 0

    private fun JdbcTemplate.hasTouchTrigger(): Boolean = queryForObject(
        "SELECT count(*) FROM pg_trigger WHERE tgrelid = 'user_hold_quota'::regclass AND tgname = 'user_hold_quota_touch' AND NOT tgisinternal",
        Int::class.java,
    )!! > 0

    private fun JdbcTemplate.hasTable(table: String): Boolean =
        queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, table) == true

    private fun JdbcTemplate.runRows(source: String): Int =
        queryForObject("SELECT count(*) FROM counter_reconcile_run WHERE source = ?", Int::class.java, source)!!

    private fun await(what: String, timeout: Duration, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!cond()) {
            if (System.nanoTime() > deadline) fail("$timeout 안에 일어나지 않았다: $what")
            Thread.sleep(200)
        }
    }

    /** K2·K3·K8 공통 — C만 돈다(증분·안전망 스레드 없음) */
    private fun assertOnlyC(b: Booted, where: String) {
        assertEquals(1, b.threads(T_RECONCILE), "$where: C 대조 스케줄러 스레드 1 (새 스레드 ${b.newThreads})")
        assertEquals(0, b.threads(T_INCREMENTAL), "$where: 증분 스케줄러 스레드 없음 (새 스레드 ${b.newThreads})")
        assertEquals(0, b.threads(T_SAFETY), "$where: 안전망 스케줄러 스레드 없음 (새 스레드 ${b.newThreads})")
        val r = b.props.counterDrift.reconcile
        assertEquals(true, r.enabled, "$where: C 켬")
        assertEquals(false, r.incremental.enabled, "$where: 증분 끔")
        assertEquals(false, r.safetyNet.enabled, "$where: 안전망 끔")
    }

    // ---------------------------------------------------------------- K1

    @Test
    fun `K1 키 없이 기동 — V9 트리거·V10 스키마 · A 켬 C 끔 · 증분 trigger 1s · 안전망 켬 · split · 증분·안전망 스레드만 돈다`() {
        boot().use { b ->
            val db = jdbc(b.url)
            // 스키마 — Flyway 위치 = db/migration + migration-updated-at-trigger(V9 ⓑ) + migration-incremental(V10)
            assertTrue(db.hasColumn("user_hold_quota", "updated_at"), "K1: user_hold_quota.updated_at 열")
            assertTrue(db.hasUpdatedAtIndex(), "K1: updated_at 인덱스")
            assertTrue(db.hasTouchTrigger(), "K1: 트리거 user_hold_quota_touch")
            V10_TABLES.forEach { assertTrue(db.hasTable(it), "K1: V10 테이블 $it") }

            // 바인딩 실효값
            val p = b.props
            val d = p.counterDrift
            assertEquals(true, d.expiryRepair, "K1: A(expiry-repair) 켬")
            assertEquals(false, d.reconcile.enabled, "K1: C(reconcile.enabled) 끔")
            assertEquals(IncrementalMethod.TRIGGER, d.reconcile.incremental.method, "K1: incremental.method=trigger")
            assertEquals(true, d.reconcile.incremental.enabled, "K1: incremental.enabled=true")
            assertEquals(Duration.ofSeconds(1), d.reconcile.incremental.interval, "K1: incremental.interval=1s")
            assertEquals(true, d.reconcile.safetyNet.enabled, "K1: safety-net.enabled=true")
            assertEquals(DetectShape.SPLIT, d.reconcile.detectShape, "K1: detect-shape=split")
            assertEquals(CleanupMode.SCHEDULE, d.cleanup.mode, "K1: 정리 schedule")
            assertEquals(false, d.externalReconciler, "K1: external-reconciler 끔")

            // 스레드 — 증분·안전망·정리는 있고 C는 없다
            assertEquals(1, b.threads(T_INCREMENTAL), "K1: 증분 스케줄러 스레드 (새 스레드 ${b.newThreads})")
            assertEquals(1, b.threads(T_SAFETY), "K1: 안전망 스케줄러 스레드 (새 스레드 ${b.newThreads})")
            assertEquals(1, b.threads(T_CLEANUP), "K1: 정리 스케줄러 스레드 (새 스레드 ${b.newThreads})")
            assertEquals(0, b.threads(T_RECONCILE), "K1: C 대조 스케줄러 스레드 없음 (새 스레드 ${b.newThreads})")

            // 기록 source — 증분 틱(1초 주기, 첫 실행은 한 주기 뒤)이 inc-app-trg-1s 행을 남긴다(후보 0이어도 쓴다 — ADR §4.2.3 순서 5).
            await("counter_reconcile_run에 source inc-app-trg-1s 행", Duration.ofSeconds(20)) { db.runRows("inc-app-trg-1s") > 0 }
            // safety-app — 안전망 기본 주기(1분)를 기다리지 않고, 기동된 안전망 빈의 source를 보고 실행 한 번을 직접 불러 기록 행을 본다(K1 실효값은 그대로)
            val safetyNet = b.ctx.getBean(SafetyNetReconciler::class.java)
            assertEquals("safety-app", safetyNet.source, "K1: 안전망 기록 source")
            safetyNet.runOnce()
            assertTrue(db.runRows("safety-app") > 0, "K1: counter_reconcile_run에 source safety-app 행")
            assertEquals(0, db.runRows("reconcile-app"), "K1: C 기록(reconcile-app) 없음")
        }
    }

    // ---------------------------------------------------------------- K2

    @Test
    fun `K2 옛 방식 복귀(새 DB) — 기동 · V1~V7 스키마(updated_at·V10 없음) · C만 · one-shot 60s`() {
        boot(*K2_KEYS).use { b ->
            val db = jdbc(b.url)
            assertTrue(!db.hasColumn("user_hold_quota", "updated_at"), "K2: updated_at 열 없음")
            assertTrue(!db.hasTouchTrigger(), "K2: 트리거 없음")
            V10_TABLES.forEach { assertTrue(!db.hasTable(it), "K2: V10 테이블 $it 없음") }
            assertOnlyC(b, "K2")
            val r = b.props.counterDrift.reconcile
            assertEquals(DetectShape.ONE_SHOT, r.detectShape, "K2: detect-shape one-shot")
            assertEquals(IncrementalMethod.NONE, r.incremental.method, "K2: method none")
            assertEquals(Duration.ofSeconds(60), r.interval, "K2: C 60초(ADR-009 기본값)")
            assertEquals(true, b.props.counterDrift.expiryRepair, "K2: A 켬(ADR-009 기본값과 같음)")
        }
    }

    // ---------------------------------------------------------------- K3

    @Test
    fun `K3 옛 방식 복귀(V9·V10 적용된 기존 DB) — 같은 DB를 K3 키로 재기동하면 뜨고 C만 돈다(트리거는 남음)`() {
        // 준비: 키 없이(K1 경로) 한 번 띄워 V9 ⓑ·V10이 적용된 DB를 만든다 — 계약의 '기존 DB'
        val url = boot().use { it.url }
        val db = jdbc(url)
        assertTrue(db.hasColumn("user_hold_quota", "updated_at") && db.hasTouchTrigger() && V10_TABLES.all { db.hasTable(it) },
            "K3 전제: 키 없이 기동한 DB에 V9(updated_at 열·트리거)·V10이 적용되어 있다")

        boot(*K3_KEYS, url = url).use { b ->
            assertOnlyC(b, "K3")
            assertTrue(db.hasTouchTrigger(), "K3: 트리거는 남는다(ADR §7 기록)")
        }
    }

    // ---------------------------------------------------------------- K4

    /** K4 안내 키(A·정리·증분·안전망) */
    private val k4HintKeys = arrayOf("expiry-repair", "cleanup.mode", "incremental.enabled", "safety-net.enabled")

    /** 해석: 계약 '그 키들 + method=none + 위치 db/migration' — C는 새 기본값이 끔이라 명시하지 않는다 */
    private fun k4OffKeys(strategy: String) = arrayOf(
        "--seat.hold.limit-strategy=$strategy",
        "--$DRIFT.expiry-repair=false",
        "--$DRIFT.cleanup.mode=none",
        "--$INC_K.enabled=false",
        "--$SAFE_K.enabled=false",
        "--$INC_K.method=none",
        "--spring.flyway.locations=$LOC_BASE",
    )

    @Test
    fun `K4 none + 키 없음 — 거부하고 A·정리·증분·안전망 끌 키를 안내한다`() {
        assertBootRejected(arrayOf("--seat.hold.limit-strategy=none"), *k4HintKeys)
    }

    @Test
    fun `K4 advisory + 키 없음 — 거부하고 A·정리·증분·안전망 끌 키를 안내한다`() {
        assertBootRejected(arrayOf("--seat.hold.limit-strategy=advisory"), *k4HintKeys)
    }

    @Test
    fun `K4 none + 끌 키 + method none + 위치 db_migration — 뜨고 대조·증분·안전망이 돌지 않는다`() {
        boot(*k4OffKeys("none")).use { b ->
            assertEquals(0, b.threads(T_RECONCILE) + b.threads(T_INCREMENTAL) + b.threads(T_SAFETY), "K4: 대조 스레드 없음 (새 스레드 ${b.newThreads})")
            assertTrue(!jdbc(b.url).hasColumn("user_hold_quota", "updated_at"), "K4: 위치 db/migration — updated_at 없음")
        }
    }

    @Test
    fun `K4 advisory + 끌 키 + method none + 위치 db_migration — 뜬다`() {
        boot(*k4OffKeys("advisory")).use { b ->
            assertEquals(0, b.threads(T_RECONCILE) + b.threads(T_INCREMENTAL) + b.threads(T_SAFETY), "K4: 대조 스레드 없음 (새 스레드 ${b.newThreads})")
        }
    }

    // ---------------------------------------------------------------- K5

    private val counterTrigger = "--seat.hold.limit-strategy=counter-trigger"

    @Test
    fun `K5 counter-trigger + 키 없음 — A·B 함께 금지로 거부, 안내에 A 끔과 증분 끔이 함께 있다`() {
        // 해석: 위치는 계약 기본 위치에 V8을 더해 준다(counter-trigger는 V8이 있어야 한다 — 위치가 빠져 다른 검사로 거부되지 않게)
        assertBootRejected(arrayOf(counterTrigger, "--spring.flyway.locations=$LOC_BASE,$LOC_V8,$LOC_V9_TRG,$LOC_V10"), "expiry-repair", "incremental.enabled")
    }

    @Test
    fun `K5 counter-trigger + A 끔 + 증분 켠 채(기본값) — 거부`() {
        assertBootRejected(
            arrayOf(counterTrigger, "--$DRIFT.expiry-repair=false", "--spring.flyway.locations=$LOC_BASE,$LOC_V8,$LOC_V9_TRG,$LOC_V10"),
            "incremental.enabled",
        )
    }

    @Test
    fun `K5 counter-trigger + A 끔 + 증분 끔(나머지 기본값 · 위치 기본 + V8) — 뜨고 증분 스레드가 없다`() {
        // 해석: 계약 '지금 규칙 유지 + 증분 끔 필요' → 필요한 키 = A 끔 + 증분 끔(+ V8 위치). method(trigger 표시만)·안전망은 기본값 그대로 둔다
        //       (안전망의 A 필수 규칙은 counter-trigger 예외 — 교체 전 HoldStrategyStartupCheck 규칙 유지)
        boot(counterTrigger, "--$DRIFT.expiry-repair=false", "--$INC_K.enabled=false", "--spring.flyway.locations=$LOC_BASE,$LOC_V8,$LOC_V9_TRG,$LOC_V10").use { b ->
            assertEquals(0, b.threads(T_INCREMENTAL), "K5: 증분 스레드 없음 (새 스레드 ${b.newThreads})")
            assertEquals(false, b.props.counterDrift.expiryRepair)
        }
    }

    @Test
    fun `K5 counter-trigger + 옛 조건 키 전부(A 끔 · 증분 끔 · method none · 안전망 끔 · 위치 base + V8) — 뜬다`() {
        boot(
            counterTrigger, "--$DRIFT.expiry-repair=false", "--$INC_K.enabled=false", "--$INC_K.method=none", "--$SAFE_K.enabled=false",
            "--spring.flyway.locations=$LOC_BASE,$LOC_V8",
        ).use { b ->
            assertEquals(0, b.threads(T_INCREMENTAL) + b.threads(T_SAFETY), "K5: 증분·안전망 스레드 없음 (새 스레드 ${b.newThreads})")
        }
    }

    // ---------------------------------------------------------------- K6

    /**
     * external-reconciler(F 사용 업무 앱)인데 증분 또는 안전망이 켜짐 → 거부. C는 명시로 끈다(교체 전 'external + C' 거부가 대신 걸려 그린 위장이 되지 않게),
     * 위치는 V9 ⓑ·V10을 명시한다(스키마 검사가 대신 거부하지 않게). 해석: 메시지 핵심 키 = external-reconciler + 켜진 쪽 키.
     */
    @Test
    fun `K6 external-reconciler + 증분 켬 — 거부(대조 중복)`() {
        assertBootRejected(
            arrayOf(
                "--$DRIFT.external-reconciler=true", "--$REC.enabled=false",
                "--$INC_K.enabled=true", "--$INC_K.method=trigger", "--$SAFE_K.enabled=false",
                "--spring.flyway.locations=$LOCS_NEW_DEFAULT",
            ),
            "external-reconciler", "incremental.enabled",
        )
    }

    @Test
    fun `K6 external-reconciler + 안전망 켬 — 거부(대조 중복)`() {
        assertBootRejected(
            arrayOf(
                "--$DRIFT.external-reconciler=true", "--$REC.enabled=false",
                "--$INC_K.enabled=false", "--$INC_K.method=trigger", "--$SAFE_K.enabled=true",
                "--spring.flyway.locations=$LOCS_NEW_DEFAULT",
            ),
            "external-reconciler", "safety-net.enabled",
        )
    }

    // ---------------------------------------------------------------- K7

    @Test
    fun `K7 reconcile_enabled=true + incremental_enabled=true — 거부(옛 방식 복귀 착각 · K2 조합 안내)`() {
        // 안전망은 끈다 — 교체 전 '안전망 + C' 거부가 대신 걸려 그린 위장이 되지 않게. 위치는 V9 ⓑ·V10 명시.
        assertBootRejected(
            arrayOf(
                "--$REC.enabled=true", "--$INC_K.enabled=true", "--$INC_K.method=trigger", "--$SAFE_K.enabled=false",
                "--spring.flyway.locations=$LOCS_NEW_DEFAULT",
            ),
            "reconcile.enabled", "incremental.enabled",
        )
    }

    // ---------------------------------------------------------------- K8

    @Test
    fun `K8 reconciler 프로필 + 키 없음 — 뜨고 C 전체 대조만(증분·안전망 끔)`() {
        boot("--spring.profiles.active=reconciler").use { b ->
            assertOnlyC(b, "K8")
            assertEquals(false, b.props.counterDrift.expiryRepair, "K8: F는 A 끔(그대로)")
        }
    }
}

/**
 * K10 — 통합 테스트 공통 고정(IntegrationTest)은 옛 동작(K2 쪽) 고정: 증분·안전망 끔 · method none · one-shot · V9·V10 없는 스키마.
 * 해석: C·A·정리는 기존 IntegrationTest 고정(끔) 그대로를 기대한다 — 여기서는 새 손잡이 쪽만 본다. 교체 전에는 당연히 통과한다(교체 뒤 고정이 빠지면 깨지는 회귀 경계).
 */
class Adr012DefaultsContractTestPinned : IntegrationTest() {
    @Autowired lateinit var properties: SeatHoldProperties

    @Test
    fun `K10 IntegrationTest 기본 컨텍스트 — 증분·안전망 끔 · method none · one-shot · updated_at·V10 없음`() {
        val r = properties.counterDrift.reconcile
        assertEquals(false, r.incremental.enabled, "K10: 증분 끔")
        assertEquals(IncrementalMethod.NONE, r.incremental.method, "K10: method none")
        assertEquals(false, r.safetyNet.enabled, "K10: 안전망 끔")
        assertEquals(DetectShape.ONE_SHOT, r.detectShape, "K10: detect-shape one-shot")
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'user_hold_quota' AND column_name = 'updated_at'",
                Int::class.java,
            ),
            "K10: updated_at 열 없음(위치 db/migration)",
        )
        V10_TABLES.forEach {
            assertEquals(false, jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, it), "K10: V10 테이블 $it 없음")
        }
    }
}
