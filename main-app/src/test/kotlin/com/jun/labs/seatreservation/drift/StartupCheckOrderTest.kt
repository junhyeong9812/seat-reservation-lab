package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.SeatReservationApplication
import com.jun.labs.seatreservation.support.causesOf
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
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
 * ADR-012 기본값 교체 코드 리뷰 — 기동 검사 순서와 트리거 활성(명세 §10 K4·K7 · §2 유실 없음).
 * - 설정만으로 판정되는 거부는 Flyway 마이그레이션 앞에서 난다: 거부된 기동이 기본 위치의 V9 트리거·V10을 남기지 않아,
 *   안내 키로 같은 DB를 다시 띄우면 뜬다(남은 트리거로 또 거부되지 않는다).
 * - Flyway가 꺼진 구성에서도 같은 설정 검사가 ApplicationRunner에서 돈다(검사가 빠지지 않는다).
 * - method=trigger는 활성 트리거만 인정한다(DISABLE TRIGGER면 거부).
 * - 코드 리뷰 RC-8: 방식 ↔ Flyway 위치(조합표 §4.2.2)·준비된 트랜잭션(max_prepared_transactions)도 마이그레이션 앞에서 판정한다 — 거부된 기동은 빈 DB를 빈 채로 둔다.
 * 실제 SpringApplication 기동 — 기동 실패는 @SpringBootTest로 단언할 수 없다(Adr012DefaultsContractTest와 같은 방식).
 */

private const val DRIFT = "seat.hold.counter-drift"
private const val REC = "$DRIFT.reconcile"
private const val INC_K = "$REC.incremental"
private const val SAFE_K = "$REC.safety-net"
private const val TOUCH_TRIGGER = "user_hold_quota_touch"
private val V10_TABLES = listOf("counter_safety_run", "counter_inc_gap", "counter_app_inject")

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StartupCheckOrderTest {

    private val postgres = PostgreSQLContainer("postgres:16")
    private val seq = AtomicInteger()

    @BeforeAll fun startDb() = postgres.start()
    @AfterAll fun stopDb() = postgres.stop()

    private fun freshDbUrl(pg: PostgreSQLContainer<*> = postgres): String {
        val name = "startorder_${seq.incrementAndGet()}"
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        return "jdbc:postgresql://${pg.host}:${pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$name"
    }

    // 두 컨테이너(기본 · max_prepared_transactions>0)의 접속 계정은 같다(Testcontainers 기본 test/test)
    private fun jdbc(url: String) = JdbcTemplate(DriverManagerDataSource(url, postgres.username, postgres.password))

    private fun boot(url: String, vararg extra: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(SeatReservationApplication::class.java).run(
            "--spring.datasource.url=$url",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--server.port=0",
            *extra,
        )

    /** 기동이 기동 검사(IllegalStateException)로 끝나고 메시지에 [keys]가 모두 있다 — 메시지를 돌려준다 */
    private fun assertRejected(url: String, extra: Array<String>, vararg keys: String): String {
        val error = try {
            boot(url, *extra).close()
            fail("기동이 거부되지 않았다: ${extra.toList()}")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e
        }
        val chain = causesOf(error)
        val hit = chain.firstOrNull { it is IllegalStateException && keys.all { k -> it.message.orEmpty().contains(k) } }
        assertTrue(hit != null, "기동 검사 메시지에 ${keys.toList()}가 모두 있지 않다: ${chain.map { "${it::class.simpleName}: ${it.message?.take(400)}" }}")
        return hit.message.orEmpty()
    }

    private fun JdbcTemplate.hasTable(table: String): Boolean =
        queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, table) == true

    private fun JdbcTemplate.hasTouchTrigger(): Boolean = hasTable("user_hold_quota") && queryForObject(
        "SELECT count(*) FROM pg_trigger WHERE tgrelid = 'user_hold_quota'::regclass AND tgname = '$TOUCH_TRIGGER' AND NOT tgisinternal",
        Int::class.java,
    )!! > 0

    /** 거부된 기동이 V9(트리거)·V10을 남기지 않았다 — 마이그레이션 기록이 없거나 최대 버전 ≤ 7 */
    private fun assertNoV9V10(url: String, where: String) {
        val db = jdbc(url)
        if (db.hasTable("flyway_schema_history")) {
            val max = db.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE version IS NOT NULL", Int::class.java)
            assertTrue(max == null || max <= 7, "$where: 마이그레이션 최대 버전 $max — V9·V10이 적용됐다")
        }
        assertTrue(!db.hasTouchTrigger(), "$where: 트리거 $TOUCH_TRIGGER 가 생겼다")
        V10_TABLES.forEach { assertTrue(!db.hasTable(it), "$where: V10 테이블 $it 가 생겼다") }
    }

    /** 거부된 기동이 빈 DB를 빈 채로 뒀다 — 테이블이 하나도 없다(flyway_schema_history 포함) · 트리거·V10 없음 */
    private fun assertUntouched(url: String, where: String) {
        val tables = jdbc(url).queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String::class.java)
        assertTrue(tables.isEmpty(), "$where: 거부된 기동이 스키마를 바꿨다 — 테이블 $tables")
        assertNoV9V10(url, where)
    }

    /** 명세 §10 K4 안내 조합(A·정리·증분·안전망 끔 + method none + 위치 db/migration) */
    private val k4Keys = arrayOf(
        "--seat.hold.limit-strategy=none",
        "--$DRIFT.expiry-repair=false", "--$DRIFT.cleanup.mode=none",
        "--$INC_K.enabled=false", "--$SAFE_K.enabled=false", "--$INC_K.method=none",
        "--spring.flyway.locations=classpath:db/migration",
    )

    /** 명세 §10 K2 옛 방식 복귀 조합 */
    private val k2Keys = arrayOf(
        "--$REC.enabled=true", "--$INC_K.enabled=false", "--$INC_K.method=none", "--$SAFE_K.enabled=false",
        "--$REC.detect-shape=one-shot", "--spring.flyway.locations=classpath:db/migration",
    )

    @Test
    fun `K4 none만 주고 기동 — 마이그레이션 전에 거부(V9·V10 없음) · 같은 DB를 안내 키로 다시 띄우면 뜬다`() {
        val url = freshDbUrl()
        assertRejected(url, arrayOf("--seat.hold.limit-strategy=none"), "expiry-repair", "cleanup.mode", "incremental.enabled", "safety-net.enabled")
        assertNoV9V10(url, "K4 거부 뒤")
        boot(url, *k4Keys).use { ctx -> assertTrue(ctx.isActive) }
        assertTrue(!jdbc(url).hasTouchTrigger(), "K4 재기동: 트리거 없음")
    }

    @Test
    fun `K7 C와 증분을 함께 켜고 기동 — 마이그레이션 전에 거부 · 같은 DB를 K2 키로 다시 띄우면 뜬다`() {
        val url = freshDbUrl()
        assertRejected(url, arrayOf("--$REC.enabled=true", "--$INC_K.enabled=true"), "reconcile.enabled", "incremental.enabled")
        assertNoV9V10(url, "K7 거부 뒤")
        boot(url, *k2Keys).use { ctx -> assertTrue(ctx.isActive) }
    }

    @Test
    fun `Flyway가 꺼진 구성 — 같은 설정 검사가 ApplicationRunner에서 돌아 거부한다`() {
        val url = freshDbUrl()
        boot(url, *k4Keys).close() // V1~V7 스키마 준비(JPA validate가 통과하게)
        assertRejected(url, arrayOf("--seat.hold.limit-strategy=none", "--spring.flyway.enabled=false"), "expiry-repair", "safety-net.enabled")
    }

    @Test
    fun `method=trigger인데 트리거가 비활성(DISABLE TRIGGER) — 기본 기동 거부`() {
        val url = freshDbUrl()
        boot(url, "--seat.hold.expiry-scheduler-enabled=false").close() // 키 없이(K1) — V9 트리거·V10 적용
        val db = jdbc(url)
        assertTrue(db.hasTouchTrigger(), "전제: 키 없는 기동이 트리거를 만들었다")
        db.execute("ALTER TABLE user_hold_quota DISABLE TRIGGER $TOUCH_TRIGGER")
        assertEquals("D", db.queryForObject("SELECT tgenabled::text FROM pg_trigger WHERE tgname = '$TOUCH_TRIGGER'", String::class.java))
        val message = assertRejected(url, arrayOf("--seat.hold.expiry-scheduler-enabled=false"), "$INC_K.method=trigger", TOUCH_TRIGGER)
        assertTrue(message.contains("비활성"), message)
        db.execute("ALTER TABLE user_hold_quota ENABLE TRIGGER $TOUCH_TRIGGER")
        boot(url, "--seat.hold.expiry-scheduler-enabled=false").use { ctx -> assertTrue(ctx.isActive) } // 다시 켜면 뜬다
    }

    @Test
    fun `K2에서 위치만 빠뜨림 — 마이그레이션 전에 거부(빈 DB 그대로 · K3 안내) · 같은 DB를 위치 준 K2로 다시 띄우면 뜬다`() {
        val url = freshDbUrl()
        val withoutLocations = k2Keys.filterNot { it.startsWith("--spring.flyway.locations") }.toTypedArray()
        val message = assertRejected(url, withoutLocations, "$INC_K.method=none", TOUCH_TRIGGER)
        assertUntouched(url, "위치 뺀 K2 거부 뒤")
        assertTrue(message.contains("K3") && message.contains("method=trigger"), "남은 트리거 거부에 K3 안내가 없다: $message")
        boot(url, *k2Keys).use { ctx -> assertTrue(ctx.isActive) }
        assertTrue(!jdbc(url).hasTouchTrigger(), "K2 재기동: 트리거 없음")
    }

    @Test
    fun `method=stmt·memory + 기본 위치(V9 트리거판) — 마이그레이션 전에 거부(빈 DB 그대로)`() {
        for (method in listOf("stmt", "memory")) {
            val url = freshDbUrl()
            assertRejected(url, arrayOf("--$INC_K.method=$method"), "$INC_K.method=$method", TOUCH_TRIGGER)
            assertUntouched(url, "method=$method 거부 뒤")
        }
    }

    @Test
    fun `max_prepared_transactions 0 아닌 DB에 키 없이(K1) — 마이그레이션 전에 거부(빈 DB 그대로) · 같은 DB를 K2로 띄우면 뜬다`() {
        PostgreSQLContainer("postgres:16").withCommand("postgres", "-c", "fsync=off", "-c", "max_prepared_transactions=10").use { pg ->
            pg.start()
            val url = freshDbUrl(pg)
            assertEquals("10", jdbc(url).queryForObject("SHOW max_prepared_transactions", String::class.java), "전제: 준비된 트랜잭션이 켜진 DB")
            assertRejected(url, emptyArray(), "max_prepared_transactions")
            assertUntouched(url, "max_prepared_transactions 거부 뒤")
            boot(url, *k2Keys).use { ctx -> assertTrue(ctx.isActive) } // 증분 틱을 돌지 않는 옛 방식은 그 DB에서 뜬다
        }
    }

    @Test
    fun `두 V9 위치를 함께 — Flyway가 마이그레이션 전에 막는다(빈 DB 그대로)`() {
        val url = freshDbUrl()
        val error = try {
            boot(url, "--spring.flyway.locations=classpath:db/migration,classpath:db/migration-updated-at-trigger,classpath:db/migration-updated-at,classpath:db/migration-incremental").close()
            fail("두 V9 위치로 기동이 됐다")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e
        }
        assertTrue(causesOf(error).any { it is FlywayException }, "Flyway 거부가 아니다: ${causesOf(error).map { "${it::class.simpleName}: ${it.message?.take(200)}" }}")
        assertUntouched(url, "두 V9 거부 뒤")
    }
}
