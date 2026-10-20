package com.jun.labs.seatreservation.reconciler

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * 테스트 16(외부 앱 쪽 · ADR-012 §4.2.8 · §4.2.1 'reconciler-app에 필요한 것') — 같은 DB 상태에서 외부 앱의 증분·안전망이 앱 안과 같은 쌍을
 * 같은 결과로 고친다(source만 inc-ext-* · safety-ext). 기준 답 = main-app ExternalEquivalenceInAppTest의 EquivalenceScenario.EXPECTED(같은 값을 여기 다시 적는다 —
 * 두 모듈 테스트가 서로를 의존하지 않게).
 * 외부 앱은 Flyway를 끈다(§4.2.1) — 스키마는 업무 앱 마이그레이션 파일로 테스트가 만든다(Gradle 테스트 작업 디렉터리 = reconciler-app/ 기준 상대 경로).
 * 외부 앱은 method=memory · reconcile.enabled=true를 기동 때 거부하고, IncrementalSchemaCheck로 스키마가 방식과 다르면 뜨지 않는다(§4.2.2 조합표 M3 행).
 *
 * 가정(구현자가 맞출 것): ① reconciler-app 테스트 의존성 = spring-boot-starter-test · kotlin-test-junit5 · testcontainers postgresql · flyway-core +
 * flyway-database-postgresql(테스트 스키마용) ② 안전망 빈 이름 = SafetyNetReconciler(main-app 쪽 가정 S2와 같음) · 증분 = core IncrementalReconciler.tick(source)
 * ③ 외부 앱의 reconcile.enabled 기본값은 계약에 없다(core ReconcileProperties의 기본은 true) — 양성 기동은 false를 명시한다.
 */
typealias IncrementalReconciler = com.jun.labs.seatreservation.service.impl.drift.IncrementalReconciler
typealias SafetyNetReconciler = com.jun.labs.seatreservation.service.impl.drift.SafetyNetReconciler

private const val INC = "seat.hold.counter-drift.reconcile.incremental"
private const val SAFE = "seat.hold.counter-drift.reconcile.safety-net"
private const val MIG = "filesystem:../main-app/src/main/resources/db"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalAppEquivalenceTest {

    private val postgres = PostgreSQLContainer("postgres:16")
    private val seq = AtomicInteger()

    @BeforeAll fun start() = postgres.start()
    @AfterAll fun stop() = postgres.stop()

    private fun freshDb(vararg locations: String): Pair<String, JdbcTemplate> {
        val name = "ext_${seq.incrementAndGet()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        val url = "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$name"
        Flyway.configure().dataSource(url, postgres.username, postgres.password).locations(*locations).load().migrate()
        return url to JdbcTemplate(DriverManagerDataSource(url, postgres.username, postgres.password))
    }

    private val trgLocs = arrayOf("$MIG/migration", "$MIG/migration-updated-at-trigger", "$MIG/migration-incremental")
    private val stmtLocs = arrayOf("$MIG/migration", "$MIG/migration-updated-at", "$MIG/migration-incremental")

    private fun boot(url: String, props: Map<String, String?>): ConfigurableApplicationContext {
        val base = mapOf<String, String?>(
            "spring.datasource.url" to url,
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to postgres.password,
            "server.port" to "0",
            "spring.flyway.enabled" to "false",
            "seat.hold.counter-drift.reconcile.enabled" to "false",
            "$INC.enabled" to "true",
            "$INC.interval" to "1h",
            "$SAFE.enabled" to "true",
            "$SAFE.interval" to "1h",
            "$SAFE.sample-interval" to "1h",
        )
        val args = (base + props).filterValues { it != null }.map { (k, v) -> "--$k=$v" }
        return SpringApplicationBuilder(ReconcilerApplication::class.java).run(*args.toTypedArray())
    }

    private fun assertBootFails(url: String, props: Map<String, String?>, why: String, vararg keywords: String) {
        val e = try {
            boot(url, props).close(); fail("떴다 — $why")
        } catch (a: AssertionError) {
            throw a
        } catch (t: Throwable) {
            t
        }
        val chain = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.toList()
        val dump = chain.map { "${it::class.qualifiedName}: ${it.message?.take(300)}" }
        assertTrue(chain.none { it is BindException }, "바인딩 실패 — $why: $dump")
        assertTrue(chain.any { it is IllegalStateException && keywords.any { k -> it.message.orEmpty().contains(k, ignoreCase = true) } }, "기동 검사로 막힌 것이 아니다 — $why: $dump")
    }

    data class Outcome(val userId: Long, val kind: String, val cntBefore: Int?, val actual: Int, val action: String, val byIncrement: Boolean)

    private val expected = listOf(
        Outcome(161, "below", 0, 1, "repaired", byIncrement = true),
        Outcome(162, "above", 2, 1, "repaired", byIncrement = true),
        Outcome(163, "missing", null, 1, "repaired", byIncrement = false),
    )

    /** 앱 안 쪽 시나리오와 같은 DB 상태(회차는 실제 시계 기준 열림 — 2099). */
    private fun JdbcTemplate.seed(stmt: Boolean): Long {
        val product = queryForObject("INSERT INTO product (name) VALUES ('ext') RETURNING id", Long::class.java)!!
        val s = queryForObject("INSERT INTO product_schedule (product_id, starts_at) VALUES (?, TIMESTAMPTZ '2099-12-31 10:00:00+00') RETURNING id", Long::class.java, product)!!
        (161L..164L).forEach { u ->
            val seat = queryForObject("INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'A', 1, ?, 'HELD') RETURNING id", Long::class.java, s, u.toInt())!!
            update("INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, now(), now() + interval '5 minutes')", seat, s, u)
            update("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 1)", s, u)
        }
        val mark = if (stmt) ", updated_at = now()" else ""
        update("UPDATE user_hold_quota SET cnt = 0$mark WHERE schedule_id = ? AND user_id = 161", s)
        update("UPDATE user_hold_quota SET cnt = 2$mark WHERE schedule_id = ? AND user_id = 162", s)
        update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 163", s)
        return s
    }

    private fun runScenario(method: String, locs: Array<String>, incSource: String) {
        val (url, db) = freshDb(*locs)
        val s = db.seed(stmt = method == "stmt")
        boot(url, mapOf("$INC.method" to method)).use { ctx ->
            ctx.getBean(IncrementalReconciler::class.java).tick(incSource)
            val safety = ctx.getBean(SafetyNetReconciler::class.java)
            var n = 0
            while (db.queryForList("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = 163", Int::class.java, s).singleOrNull() != 1) {
                check(n++ < 200) { "외부 앱 안전망이 163을 고치지 못했다" }
                safety.sampleOnce(); safety.runOnce()
            }
        }
        val got = db.query("SELECT user_id, kind, cnt_before, actual, action, source FROM counter_drift_log ORDER BY user_id") { rs, _ ->
            Outcome(rs.getLong(1), rs.getString(2), (rs.getObject(3) as Number?)?.toInt(), rs.getInt(4), rs.getString(5), rs.getString(6) == incSource) to rs.getString(6)
        }
        assertEquals(expected, got.map { it.first }, "앱 안과 같은 쌍 · 같은 결과(손 계산 정답)")
        assertEquals(listOf(incSource, incSource, "safety-ext"), got.map { it.second }, "source만 외부 앱 값")
        assertTrue(db.queryForList("SELECT DISTINCT source FROM counter_reconcile_run", String::class.java).toSet().all { it in setOf(incSource, "safety-ext") })
        (161L..164L).forEach { u ->
            assertEquals(1, db.queryForObject("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, s, u))
        }
    }

    @Test
    fun `16 외부 앱 ⓑ — 증분 inc-ext-trg · 안전망 safety-ext로 앱 안과 같은 쌍을 같은 결과로`() =
        runScenario("trigger", trgLocs, "inc-ext-trg-1s")

    @Test
    fun `16 외부 앱 ⓒ — 증분 inc-ext-stmt · 안전망 safety-ext로 앱 안과 같은 쌍을 같은 결과로`() =
        runScenario("stmt", stmtLocs, "inc-ext-stmt-1s")

    @Test
    fun `16 외부 앱은 method=memory를 기동 때 거부한다`() {
        val (url, _) = freshDb("$MIG/migration", "$MIG/migration-incremental")
        assertBootFails(url, mapOf("$INC.method" to "memory"), "외부 앱 + 메모리 집합", "memory")
    }

    @Test
    fun `16 외부 앱은 reconcile_enabled=true(전체 대조 C)를 기동 때 거부한다`() {
        val (url, _) = freshDb(*trgLocs)
        assertBootFails(url, mapOf("$INC.method" to "trigger", "seat.hold.counter-drift.reconcile.enabled" to "true"), "외부 앱 + 전체 대조", "reconcile")
    }

    @Test
    fun `M3 — 외부 앱은 Flyway 없이 같은 IncrementalSchemaCheck로 스키마를 확인한다(방식과 다르면 기동 실패)`() {
        assertBootFails(freshDb(*stmtLocs).first, mapOf("$INC.method" to "trigger"), "trigger인데 트리거 없음", "user_hold_quota_touch", "trigger", "트리거")
        assertBootFails(freshDb(*trgLocs).first, mapOf("$INC.method" to "stmt"), "stmt인데 트리거 있음", "user_hold_quota_touch", "trigger", "트리거")
        assertBootFails(freshDb("$MIG/migration", "$MIG/migration-updated-at-trigger").first, mapOf("$INC.method" to "trigger"), "V10 없음", "counter_safety_run")
        assertBootFails(freshDb("$MIG/migration", "$MIG/migration-incremental").first, mapOf("$INC.method" to "stmt"), "updated_at 없음", "updated_at")
        boot(freshDb(*trgLocs).first, mapOf("$INC.method" to "trigger")).close()   // 양성 대조
        boot(freshDb(*stmtLocs).first, mapOf("$INC.method" to "stmt")).close()
    }

    @Test
    fun `외부 앱 configprops 경로 — 접두어 seat_hold · counterDrift_reconcile_incremental_method가 업무 앱과 같은 자리(하네스 incremental-config-mismatch의 전제)`() {
        val (url, _) = freshDb(*trgLocs)
        boot(url, mapOf("$INC.method" to "trigger")).use { ctx ->
            val beans = ctx.getBeansWithAnnotation(org.springframework.boot.context.properties.ConfigurationProperties::class.java)
            assertTrue(beans.values.any { it::class.java.getAnnotation(org.springframework.boot.context.properties.ConfigurationProperties::class.java)?.let { a -> a.value == "seat.hold" || a.prefix == "seat.hold" } == true }, "외부 앱 설정 묶음의 접두어 = seat.hold: ${beans.keys}")
        }
    }
}
