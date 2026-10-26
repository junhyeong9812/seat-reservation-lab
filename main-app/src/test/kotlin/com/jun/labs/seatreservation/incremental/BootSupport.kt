package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.SeatReservationApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실제 SpringApplication 기동(기동 실패 · 재시작 · 다른 DB 역할) — CounterDriftStartupCheckTest와 같은 방식.
 * 부팅마다(또는 시나리오마다) 새 데이터베이스를 만든다 — 앞 부팅의 마이그레이션이 위치 조건을 섞지 않게.
 * 앱 시계는 실제 시계(Clock.systemUTC)라 회차는 2099년으로 만든다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BootTestBase {

    protected open fun container(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

    protected lateinit var postgres: PostgreSQLContainer<*>
    private val seq = AtomicInteger()

    @BeforeAll
    fun startDb() {
        postgres = container()
        postgres.start()
    }

    @AfterAll
    fun stopDb() = postgres.stop()

    protected open val dbUser: String get() = postgres.username
    protected open val dbPassword: String get() = postgres.password

    fun freshDbUrl(owner: String? = null): String {
        val name = "inc_${javaClass.simpleName.lowercase()}_${seq.incrementAndGet()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name" + (owner?.let { o -> " OWNER $o" } ?: "")) }
        }
        return "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$name"
    }

    fun jdbcFor(url: String, user: String = dbUser, password: String = dbPassword) =
        JdbcTemplate(DriverManagerDataSource(url, user, password))

    /**
     * 업무 앱 기동. [props]의 값 null = 그 기본 키를 빼라(업무 앱 기본값 — application.yml이 정한다).
     * 기본 키 = 옛 동작 고정(IntegrationTest와 같은 뜻 — ADR-009 후보·정리 끔 · ADR-012 증분·안전망 끔 · method none · one-shot · 기본 위치 V1~V7, 명세 §10 K10):
     * 이 테스트들은 업무 앱 기본값이 '손잡이 없음'이던 때 쓰였다 — 각 테스트가 켤 손잡이만 [props]로 준다([incrementalProps]가 방식·위치를 덮는다).
     */
    fun boot(url: String, props: Map<String, String?>, user: String = dbUser, password: String = dbPassword): ConfigurableApplicationContext {
        val base = mapOf<String, String?>(
            "spring.datasource.url" to url,
            "spring.datasource.username" to user,
            "spring.datasource.password" to password,
            "server.port" to "0",
            "seat.hold.expiry-scheduler-enabled" to "false",
        ) + OLD_DEFAULTS
        val args = (base + props).filterValues { it != null }.map { (k, v) -> "--$k=$v" }
        return SpringApplicationBuilder(SeatReservationApplication::class.java).run(*args.toTypedArray())
    }

    fun bootFailure(url: String, props: Map<String, String?>): Throwable? = failureOf { boot(url, props).close() }

    /** 회차(2099 — 실제 시계 기준 열림) 하나와 그 id. */
    fun JdbcTemplate.seedSchedule(): Long {
        val product = queryForObject("INSERT INTO product (name) VALUES ('t') RETURNING id", Long::class.java)!!
        return queryForObject(
            "INSERT INTO product_schedule (product_id, starts_at) VALUES (?, TIMESTAMPTZ '2099-12-31 10:00:00+00') RETURNING id",
            Long::class.java, product,
        )!!
    }

    private val seatSeq = AtomicInteger(30_000)

    fun JdbcTemplate.seat(scheduleId: Long, status: String = "AVAILABLE"): Long = queryForObject(
        "INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'A', 1, ?, ?) RETURNING id",
        Long::class.java, scheduleId, seatSeq.incrementAndGet(), status,
    )!!

    /** 직접 SQL 홀드(유효 — 실제 시계 + 5분) */
    fun JdbcTemplate.hold(scheduleId: Long, userId: Long): Long = queryForObject(
        "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, now(), now() + interval '5 minutes') RETURNING id",
        Long::class.java, seat(scheduleId, "HELD"), scheduleId, userId,
    )!!

    fun JdbcTemplate.quota(scheduleId: Long, userId: Long, cnt: Int) {
        update("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, ?)", scheduleId, userId, cnt)
    }

    fun JdbcTemplate.cnt(scheduleId: Long, userId: Long): Int? =
        queryForList("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, scheduleId, userId).singleOrNull()

    fun JdbcTemplate.actual(scheduleId: Long, userId: Long): Int = queryForObject(
        "SELECT ((SELECT count(*) FROM seat_hold WHERE schedule_id = ? AND user_id = ?) + (SELECT count(*) FROM reservation WHERE schedule_id = ? AND user_id = ? AND status = 'CONFIRMED'))::int",
        Int::class.java, scheduleId, userId, scheduleId, userId,
    )!!
}

/**
 * 옛 동작 고정 키(명세 §10 K10 — [BootTestBase.boot] 기본 키): ADR-009 후보·정리 끔 + ADR-012 증분·안전망 끔 · method none · one-shot · Flyway 기본 위치.
 * 값을 null로 바꾸면(`OLD_DEFAULTS.mapValues { null }`) 업무 앱 기본값(application.yml)으로 뜬다.
 */
val OLD_DEFAULTS = mapOf<String, String?>(
    "seat.hold.counter-drift.expiry-repair" to "false",
    "seat.hold.counter-drift.reconcile.enabled" to "false",
    "seat.hold.counter-drift.cleanup.mode" to "none",
    "$INC.enabled" to "false",
    "$INC.method" to "none",
    "$SAFE.enabled" to "false",
    "seat.hold.counter-drift.reconcile.detect-shape" to "one-shot",
    "spring.flyway.locations" to LOC_BASE,
)

/** 증분·안전망 공통 기동 키(주기 1h — 진입점을 테스트가 부른다, 가정 S5). */
fun incrementalProps(method: String, locations: String, vararg extra: Pair<String, String?>): Map<String, String?> = mapOf<String, String?>(
    "seat.hold.counter-drift.expiry-repair" to "true",
    "$INC.method" to method,
    "$INC.enabled" to "true",
    "$INC.interval" to "1h",
    "$SAFE.enabled" to "true",
    "$SAFE.interval" to "1h",
    "$SAFE.sample-interval" to "1h",
    "spring.flyway.locations" to locations,
) + extra

const val LOCS_V10 = "$LOC_BASE,$LOC_V10"
const val LOCS_TRG = "$LOC_BASE,$LOC_V9_TRG,$LOC_V10"
const val LOCS_STMT = "$LOC_BASE,$LOC_V9_STMT,$LOC_V10"
