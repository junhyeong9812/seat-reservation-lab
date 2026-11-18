package com.jun.labs.seatreservation.adr017

import com.fasterxml.jackson.databind.JsonNode
import com.jun.labs.mockpg.MockPgApplication
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
 * ADR-017 task 06(D) · 07(S) blind 계약 테스트 공용 장치(구현 diff 미열람 — 명세 §1 · §2 · §5 · 부록 A · B + ADR-017 설계-3 · 4.3 · 4.4 · 5 · 7 · 10).
 * 외부 계약으로만 본다: 설정 키 · HTTP(앱 · 흉내 PG) · JDBC 행(V15 표 · 열 이름) · 지표 이름/태그.
 * 부록 A가 고정한 테스트용 빈(CompensationWorker · CompensationSweep의 runOnce(): Int)은 **이름으로 리플렉션 호출**한다 —
 * 구현 패키지(service.impl.payment)를 컴파일 의존으로 끌어오지 않는다(blind · 구현 전 컴파일).
 *
 * 흉내 PG 연결(부록 A '테스트 연결'): 같은 JVM에서 SpringApplicationBuilder(MockPgApplication)로 랜덤 포트에 한 번 띄우고(JVM당 1개 · 테스트마다 /admin/reset),
 * 원장 시계 빈 `mockPgClock`을 이 파일의 위임 시계로 바꾼다(BeanPostProcessor로 인스턴스 교체). 위임 대상 = 지금 테스트 컨텍스트의 MutableClock(@BeforeEach에서 묶음).
 *  가정: mockPgClock은 java.time.Clock 타입이다(부록 A '원장 시계 빈 mockPgClock을 … 위임하는 시계로 바꾼다').
 *  가정: 같은 JVM 클래스패스에는 main-app의 application.yml이 앞에 있어 흉내 PG가 그것을 읽을 수 있다 → 흉내 PG 모듈 자신의 application.yml(경로에 mock-pg가 든 것)을
 *        spring.config.location으로 지정하고, main-app 의존(JPA · Flyway · DataSource · Redis) 자동 구성은 뺀다(흉내 PG는 DB가 없다 — mock-pg/build.gradle.kts).
 */

const val DS_WORKER_CLASS = "com.jun.labs.seatreservation.service.impl.payment.CompensationWorker"
const val DS_SWEEP_CLASS = "com.jun.labs.seatreservation.service.impl.payment.CompensationSweep"

// 지표(부록 A)
const val DS_M_DONE = "seat.compensation.done"

// 모드 값(명세 §1 · 부록 B)
const val DS_MODE_AUTH_CAPTURE = "$COMPENSATION_MODE_KEY=auth-capture"
const val DS_MODE_SUBSTITUTE = "$COMPENSATION_MODE_KEY=substitute"
const val DS_SCHEDULER_OFF = "seat.compensation.scheduler-enabled=false"

/** 흉내 PG — JVM에 하나. */
object DsMockPg {

    private val target = AtomicReference<Clock?>(null)

    /** 원장 시계 — 묶인 테스트 시계에 위임(묶기 전에는 IntegrationTest.NOW 고정) */
    val clock: Clock = object : Clock() {
        override fun instant(): Instant = target.get()?.instant() ?: IntegrationTest.NOW
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    fun bindClock(c: Clock) = target.set(c)

    @Volatile private var ctx: ConfigurableApplicationContext? = null

    val baseUrl: String by lazy { start() }

    private fun start(): String {
        val ownYml = Thread.currentThread().contextClassLoader.getResources("application.yml").toList()
            .firstOrNull { it.toString().contains("mock-pg") }
        val excludes = listOf(
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        )
        val args = mutableListOf(
            "--server.port=0",
            "--spring.main.banner-mode=off",
            "--spring.application.name=mock-pg-adr017-ds",
            "--spring.autoconfigure.exclude=" + excludes.joinToString(","),
        )
        args += if (ownYml != null) "--spring.config.location=optional:$ownYml" else "--spring.config.name=mock-pg-adr017-ds-none"
        val swap: BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any = if (beanName == "mockPgClock") clock else bean
        }
        val c = SpringApplicationBuilder(MockPgApplication::class.java)
            .initializers(ApplicationContextInitializer<ConfigurableApplicationContext> { it.beanFactory.addBeanPostProcessor(swap) })
            .run(*args.toTypedArray())
        check(c.containsBean("mockPgClock") && c.getBean("mockPgClock") === clock) {
            "흉내 PG 원장 시계 빈 mockPgClock(부록 A)을 바꿔 끼우지 못했다 — 앱 시계와 PG 시계가 어긋나면 deadline 판정이 틀린다"
        }
        ctx = c
        val port = c.getBean(Environment::class.java).getProperty("local.server.port")
        return "http://localhost:$port"
    }
}

data class DsTask(
    val id: Long,
    val uid: String,
    val type: String,
    val source: String,
    val status: String,
    val attempts: Int,
    val lastResult: String?,
)

/** 응답 본문의 compensation(부록 B — 값 없는 필드는 빠진다) */
data class DsComp(val taskId: Long?, val type: String?, val status: String?, val reservationId: Long?, val seatId: Long?)

/**
 * Adr017Base 위에 흉내 PG · 워커 · 안전망 · 쿼터 대조를 얹는다. 하위 클래스가 `@SpringBootTest(RANDOM_PORT · 고정 키 + 모드)` ·
 * `@Import(TestcontainersConfiguration, ClockConfig)`를 단다. PG 주소는 [DsMockPg]가 띄운 주소를 [PG_BASE_URL_KEY]로 준다.
 */
abstract class Adr017DsBase : Adr017Base() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dsPgUrl(registry: DynamicPropertyRegistry) {
            registry.add(PG_BASE_URL_KEY) { DsMockPg.baseUrl }
        }
    }

    @Autowired lateinit var appCtx: ApplicationContext

    private val pgHttp: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @BeforeEach
    fun dsBindPg() {
        DsMockPg.bindClock(clock)
        val r = pgPost("/admin/reset")
        assertTrue(r.status in 200..299, "흉내 PG 원장 비우기 실패(부록 A /admin/reset): $r")
    }

    // ---- 흉내 PG ----

    fun pgPost(path: String): Resp = pgSend(HttpRequest.newBuilder(URI.create(DsMockPg.baseUrl + path))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.noBody()))

    fun pgGet(path: String): Resp = pgSend(HttpRequest.newBuilder(URI.create(DsMockPg.baseUrl + path)).GET())

    private fun pgSend(b: HttpRequest.Builder): Resp {
        val res = pgHttp.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString())
        return Resp(res.statusCode(), res.body())
    }

    fun pgAuthorizeOk(uid: String) = pgPost("/payments/$uid/authorize").also { assertTrue(it.status in 200..299, "준비 — PG authorize 실패: $it") }

    fun pgPayOk(uid: String) = pgPost("/payments/$uid/pay").also { assertTrue(it.status in 200..299, "준비 — PG pay 실패: $it") }

    /** PG 원장의 그 uid 상태(부록 A 본문 payment.state) */
    fun pgState(uid: String): String? {
        val r = pgGet("/payments/$uid")
        return r.json?.get("payment")?.get("state")?.takeIf { !it.isNull }?.asText()
    }

    // ---- 준비: 선점 → payment-start(uid · PG 등록) ----

    data class Paid(val seatId: Long, val holdId: Long, val uid: String, val user: Long, val expiresAt: Instant)

    /** 선점 → payment-start(서버가 PG에 등록) → 홀드 만료 시각. 결제(pay/authorize)는 부르는 쪽이 한다. */
    fun startedHold(seatId: Long, user: Long): Paid {
        val hold = holdOk(seatId, user)
        val (uid, _) = startOk(hold, user)
        return Paid(seatId, hold, uid, user, expiresAtOf(hold))
    }

    // ---- 워커 · 안전망(부록 A — 이름으로 호출) ----

    private fun runOnce(className: String): Int {
        @Suppress("UNCHECKED_CAST")
        val cls = Class.forName(className) as Class<Any>
        val bean = appCtx.getBean(cls)
        return try {
            (cls.getMethod("runOnce").invoke(bean) as Number).toInt()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    fun workerOnce(): Int = runOnce(DS_WORKER_CLASS)

    fun sweepOnce(): Int = runOnce(DS_SWEEP_CLASS)

    /**
     * 그 uid들의 작업이 모두 DONE · DEAD가 될 때까지 워커를 돌린다. 처리 0이면 백오프(부록 A 초기 1초)를 넘기려 앱 시계 2초 + 실제 1.1초
     * — 가정: next_attempt_at이 앱 시계인지 DB now()인지 명세가 정하지 않았다(둘 다 '도래'가 되게).
     */
    fun drain(vararg uids: String) {
        repeat(20) {
            val open = uids.sumOf { u -> tasks(u).count { it.status != "DONE" && it.status != "DEAD" } }
            if (open == 0) return
            if (workerOnce() == 0) {
                clock.advance(Duration.ofSeconds(2))
                Thread.sleep(1_100)
            }
        }
        fail("워커를 20번 돌려도 작업이 끝나지 않았다: ${uids.associateWith { tasks(it) }}")
    }

    // ---- DB ----

    fun tasks(uid: String): List<DsTask> = jdbcTemplate.query(
        "SELECT id, uid, type, source, status, attempts, last_result FROM compensation_task WHERE uid = ? ORDER BY id",
        { rs, _ ->
            DsTask(rs.getLong("id"), rs.getString("uid"), rs.getString("type"), rs.getString("source"), rs.getString("status"),
                rs.getInt("attempts"), rs.getString("last_result"))
        },
        uid,
    )

    fun tasksOfType(uid: String, type: String): List<DsTask> = tasks(uid).filter { it.type == type }

    /** (회차, 사용자) 실제 수 = 홀드 행 + CONFIRMED 예약(BootSupport.actual과 같은 정의) */
    fun actualOf(user: Long): Int = jdbcTemplate.queryForObject(
        "SELECT ((SELECT count(*) FROM seat_hold WHERE schedule_id = ? AND user_id = ?) + " +
            "(SELECT count(*) FROM reservation WHERE schedule_id = ? AND user_id = ? AND status = 'CONFIRMED'))::int",
        Int::class.java, schedule.id, user, schedule.id, user,
    )!!

    /** 쿼터 정합: cnt(행 없으면 0) = 실제 수 */
    fun assertQuotaMatches(user: Long, why: String) =
        assertEquals(actualOf(user), cntOf(user) ?: 0, "$why — 쿼터 카운터 = 실제 (홀드 + 확정 예약) 수(부록 B · N17-9): ${dbState()}")

    fun seatSection(seatId: Long): String =
        jdbcTemplate.queryForObject("SELECT section FROM product_seat WHERE id = ?", String::class.java, seatId)!!

    fun seatSchedule(seatId: Long): Long =
        jdbcTemplate.queryForObject("SELECT schedule_id FROM product_seat WHERE id = ?", Long::class.java, seatId)!!

    // ---- 응답 ----

    fun comp(r: Resp): DsComp? {
        val c: JsonNode = r.json?.get(COMPENSATION)?.takeIf { !it.isNull } ?: return null
        fun l(f: String) = c.get(f)?.takeIf { !it.isNull }?.asLong()
        fun s(f: String) = c.get(f)?.takeIf { !it.isNull }?.asText()
        return DsComp(l("taskId"), s("type"), s("status"), l("reservationId"), l("seatId"))
    }

    fun compOrFail(r: Resp, why: String): DsComp = assertNotNull(comp(r), "$why — 본문에 $COMPENSATION 이 없다(부록 B): $r")

    fun assertNo5xx(rs: Collection<Resp>, why: String) =
        assertTrue(rs.none { it.status >= 500 }, "$why — 5xx가 있다: ${rs.filter { it.status >= 500 }}")

}
