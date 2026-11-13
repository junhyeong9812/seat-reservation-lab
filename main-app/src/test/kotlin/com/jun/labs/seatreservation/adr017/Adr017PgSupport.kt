package com.jun.labs.seatreservation.adr017

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.mockpg.MockPgApplication
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.service.impl.payment.CompensationSweep
import com.jun.labs.seatreservation.service.impl.payment.CompensationWorker
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * ADR-017 task 04 · 05 blind 계약 테스트 공용 장치 — 앱 ↔ 흉내 PG(구현 미열람 · 명세 §1(4~6 · 8) · §2 · §5 · 부록 A + ADR-017 설계-3 · 4.2 · 4.4 · 4.5 · 5 · 7 · 10).
 * 부록 A '테스트 연결': mock-pg를 같은 JVM 랜덤 포트로 띄우고, 원장 시계 빈 `mockPgClock`을 앱 MutableClock에 위임하는 시계로 바꾼다.
 *
 * 가정(근거 조항):
 *  - `mockPgClock`은 java.time.Clock이다(부록 A). 교체는 그 이름의 빈 인스턴스를 BeanPostProcessor로 바꿔 끼운다(정의 덮어쓰기 설정 불필요).
 *  - mock-pg는 JVM에 하나만 띄우고(컨텍스트 캐시와 무관), 위임 대상은 테스트마다 그 컨텍스트의 MutableClock으로 바꾼다.
 *  - main-app 테스트 클래스패스에는 JPA · Flyway · Redis 자동 구성과 main-app의 application.yml이 있다 — mock-pg 컨텍스트가 그것을 집지 않게
 *    설정 파일 이름을 바꾸고(spring.config.name) DB · Redis 자동 구성을 뺀다. mock-pg 키(auth-ttl)는 명시로 준다(부록 A 기본 10분).
 *  - 작업 행 시각(next_attempt_at)이 앱 시계인지 DB now()인지 명세가 정하지 않았다 → 워커를 다시 돌릴 때 앱 시계를 백오프 상한보다 더 옮기고,
 *    실제 시간도 백오프 상한만큼 흘려 둘 다 '도래'가 되게 한다([workerRound]).
 */
object Adr017Pg {
    /** 지금 위임 대상(테스트 @BeforeEach가 그 컨텍스트의 MutableClock으로 바꾼다) */
    val appClock = AtomicReference<Clock>(Clock.systemUTC())

    class DelegatingClock : Clock() {
        override fun instant(): Instant = appClock.get().instant()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private val ctx: ConfigurableApplicationContext by lazy {
        SpringApplicationBuilder(MockPgApplication::class.java)
            .initializers(
                ApplicationContextInitializer<ConfigurableApplicationContext> { c ->
                    c.beanFactory.addBeanPostProcessor(object : BeanPostProcessor {
                        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                            if (beanName == "mockPgClock") DelegatingClock() else bean
                    })
                },
            )
            .properties(
                "spring.config.name=adr017-mock-pg-none",
                "server.port=0",
                "spring.main.banner-mode=off",
                "spring.application.name=mock-pg-test",
                "mock-pg.auth-ttl=10m",
                "spring.autoconfigure.exclude=" + listOf(
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                    "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                    "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                ).joinToString(","),
            )
            .run()
    }

    val baseUrl: String get() = "http://localhost:" + ctx.environment.getProperty("local.server.port")

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val om = ObjectMapper()

    class PgResp(val status: Int, val body: String) {
        val json: JsonNode? = runCatching { ObjectMapper().readTree(body) }.getOrNull()
        val code: String? get() = json?.get("code")?.takeIf { !it.isNull }?.asText()
        val payment: JsonNode? get() = json?.get("payment")?.takeIf { !it.isNull }
        val state: String? get() = payment?.get("state")?.asText()
        val refunds: Int get() = payment?.get("refunds")?.takeIf { !it.isNull }?.asInt() ?: 0
        fun instant(name: String): Instant? = payment?.get(name)?.takeIf { !it.isNull }?.let { Instant.parse(it.asText()) }
        override fun toString() = "PG $status $body"
    }

    private fun send(method: String, path: String, body: String?): PgResp {
        val b = HttpRequest.newBuilder(URI.create("$baseUrl$path")).timeout(Duration.ofSeconds(30))
        if (method == "GET") b.GET()
        else b.header("Content-Type", "application/json")
            .POST(if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return PgResp(r.statusCode(), r.body())
    }

    fun get(uid: String) = send("GET", "/payments/$uid", null)
    fun pay(uid: String) = send("POST", "/payments/$uid/pay", null)
    fun close(uid: String) = send("POST", "/payments/$uid/close", null)
    fun ledger() = send("GET", "/ledger", null)
    fun reset() = send("POST", "/admin/reset", null).also { assertTrue(it.status in 200..299, "PG reset: $it") }

    fun faults(rules: Map<String, Map<String, Number>>, seed: Long = 1) =
        send("POST", "/admin/faults", om.writeValueAsString(mapOf("seed" to seed, "rules" to rules)))
            .also { assertTrue(it.status in 200..299, "PG faults: $it") }

    fun clearFaults() = faults(emptyMap())

    fun failAll(op: String) = faults(mapOf(op to mapOf("failRate" to 1.0)))

    fun awaitState(uid: String, want: String, timeout: Duration = Duration.ofSeconds(10)) {
        val end = System.nanoTime() + timeout.toNanos()
        var last: PgResp? = null
        while (System.nanoTime() < end) {
            last = get(uid)
            if (last.state == want) return
            Thread.sleep(50)
        }
        fail("PG uid=$uid 상태가 $want 가 되지 않았다: $last")
    }
}

// ── 키(부록 A) ──────────────────────────────────────────────────────────────────────────────
const val P17_SCHEDULER_OFF = "seat.compensation.scheduler-enabled=false"
const val P17_MODE_REFUND = "$COMPENSATION_MODE_KEY=refund"
/** 결과 모름 시험용 — 읽기 타임아웃을 짧게(unknownDelayMs가 이보다 길다) */
const val P17_READ_TIMEOUT = "seat.payment.pg.read-timeout=700ms"
const val P17_CONNECT_TIMEOUT = "seat.payment.pg.connect-timeout=500ms"
const val P17_MAX_ATTEMPTS = "seat.compensation.worker.max-attempts=3"
const val P17_BACKOFF_INITIAL = "seat.compensation.worker.backoff-initial=200ms"
const val P17_BACKOFF_MAX = "seat.compensation.worker.backoff-max=500ms"
const val P17_LEASE = "seat.compensation.worker.lease=30s"
const val MAX_ATTEMPTS = 3
val BACKOFF_MAX: Duration = Duration.ofMillis(500)
const val UNKNOWN_DELAY_MS = 2_000

// ── 지표(부록 A) ────────────────────────────────────────────────────────────────────────────
const val M17_DONE = "seat.compensation.done"
const val M17_RETRY = "seat.compensation.retry"
const val M17_DEAD = "seat.compensation.dead"
const val M17_UNRESOLVED = "seat.compensation.unresolved"
const val M17_OLDEST_PENDING = "seat.compensation.oldest_pending_seconds"

const val CLOSING = "CLOSING"
const val AMOUNT_DEFAULT = 10_000L

data class TaskRow(
    val id: Long,
    val uid: String,
    val type: String,
    val source: String?,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Instant?,
    val leaseUntil: Instant?,
    val lastResult: String?,
    val doneAt: Instant?,
)

/**
 * 앱 ↔ 흉내 PG 통합 기반. 하위 클래스는 `@SpringBootTest(RANDOM_PORT · 고정 키 + P17_MODE_REFUND + P17_SCHEDULER_OFF + …)` ·
 * `@Import(TestcontainersConfiguration, IntegrationTest.ClockConfig)`를 단다. `seat.payment.pg.base-url`은 이 클래스의 [DynamicPropertySource]가 준다.
 */
abstract class Adr017PgBase : Adr017Base() {

    @Autowired lateinit var worker: CompensationWorker
    @Autowired lateinit var sweep: CompensationSweep

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun pgBaseUrl(r: DynamicPropertyRegistry) {
            r.add(PG_BASE_URL_KEY) { Adr017Pg.baseUrl }
        }
    }

    @BeforeEach
    fun pgClean() {
        Adr017Pg.appClock.set(clock)
        Adr017Pg.clearFaults()
        Adr017Pg.reset()
    }

    data class Paid(val seatId: Long, val holdId: Long, val uid: String, val payBy: Instant, val expiresAt: Instant)

    /** 좌석 → 선점 → payment-start(PG 등록) → (pay면) PG 결제 */
    fun started(seatNo: Int, user: Long, pay: Boolean = true): Paid {
        val seat = createSeat(seatNo).id!!
        val hold = holdOk(seat, user)
        val (uid, r) = startOk(hold, user)
        if (pay) {
            val p = Adr017Pg.pay(uid)
            assertTrue(p.status in 200..299 && p.state == "CAPTURED", "준비 — PG 결제 실패: $p")
        }
        return Paid(seat, hold, uid, r.instant("payBy"), r.instant("expiresAt"))
    }

    // ---- 작업 행 ----

    fun tasks(uid: String): List<TaskRow> = jdbcTemplate.query(
        """
        SELECT id, uid::text AS uid, type, source, status, attempts, next_attempt_at, lease_until, last_result, done_at
        FROM compensation_task WHERE uid::text = ? ORDER BY id
        """.trimIndent(),
        { rs, _ ->
            TaskRow(
                id = rs.getLong("id"), uid = rs.getString("uid"), type = rs.getString("type"), source = rs.getString("source"),
                status = rs.getString("status"), attempts = rs.getInt("attempts"),
                nextAttemptAt = rs.getTimestamp("next_attempt_at")?.toInstant(), leaseUntil = rs.getTimestamp("lease_until")?.toInstant(),
                lastResult = rs.getString("last_result"), doneAt = rs.getTimestamp("done_at")?.toInstant(),
            )
        },
        uid,
    )

    fun closeTask(uid: String): TaskRow {
        val ts = tasks(uid)
        assertEquals(1, ts.size, "uid=$uid 작업은 1행(UNIQUE(uid, type) · N17-4): $ts")
        assertEquals("CLOSE", ts.single().type, "작업 type = CLOSE: $ts")
        return ts.single()
    }

    fun taskById(id: Long): TaskRow = assertNotNull(
        jdbcTemplate.queryForList("SELECT uid::text FROM compensation_task WHERE id = ?", String::class.java, id).singleOrNull()
            ?.let { u -> tasks(u).single { it.id == id } },
        "작업 id=$id 없음",
    )

    // ---- 워커 · 안전망 ----

    /** 한 바퀴: 앱 시계를 백오프 상한보다 더 옮기고 · 실제 시간도 그만큼 흘린 뒤(시각 출처 가정 — 머리말) runOnce */
    fun workerRound(): Int {
        clock.advance(BACKOFF_MAX.plusSeconds(1))
        Thread.sleep(BACKOFF_MAX.toMillis() + 50)
        return worker.runOnce()
    }

    /** [pred]가 참이 될 때까지 워커를 돌린다(최대 [rounds]) — 첫 바퀴는 시계를 옮기지 않는다 */
    fun runWorkerUntil(rounds: Int = 6, why: String, pred: () -> Boolean) {
        worker.runOnce()
        var n = 1
        while (!pred() && n < rounds) {
            workerRound()
            n++
        }
        assertTrue(pred(), "$why — 워커 $rounds 바퀴 안에 끝나지 않았다 · 작업=${jdbcTemplate.queryForList("SELECT * FROM compensation_task")}")
    }

    fun runWorkerUntilDone(uid: String, rounds: Int = 6) =
        runWorkerUntil(rounds, "uid=$uid CLOSE 작업 DONE") { tasks(uid).singleOrNull()?.status == "DONE" }

    // ---- 응답 · 관리 API ----

    /** 본문 compensation{taskId, type, status}(명세 §1 · 3) */
    fun assertCompensation(r: Resp, taskId: Long, type: String, statusIn: Set<String>, why: String) {
        val c = assertNotNull(r.json?.get(COMPENSATION)?.takeIf { !it.isNull }, "$why — 본문에 $COMPENSATION 이 없다: $r")
        assertEquals(taskId, c.get("taskId")?.asLong(), "$why — taskId: $r")
        assertEquals(type, c.get("type")?.asText(), "$why — type: $r")
        assertTrue(c.get("status")?.asText() in statusIn, "$why — status ∈ $statusIn: $r")
    }

    fun adminRetry(id: Long): Resp = rawPost("/internal/compensations/$id/retry", null, null, null)

    fun pgState(uid: String): String? = Adr017Pg.get(uid).state

    /** 강제로 '집은 채 죽은 워커' 모양(IN_PROGRESS · 리스 만료) — 리스 회수 경로를 연다 */
    fun forceLeaseExpired(taskId: Long) = assertEquals(
        1,
        jdbcTemplate.update(
            "UPDATE compensation_task SET status = 'IN_PROGRESS', lease_until = ?, done_at = NULL WHERE id = ?",
            Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")), taskId,
        ),
        "준비 — 리스 만료 모양",
    )

    fun gaugeValue(name: String): Double? = meters.find(name).gauge()?.value()
}
