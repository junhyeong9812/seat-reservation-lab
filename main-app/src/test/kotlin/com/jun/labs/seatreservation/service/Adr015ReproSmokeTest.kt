package com.jun.labs.seatreservation.service

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.service.impl.ExpireHoldsService
import com.jun.labs.seatreservation.service.impl.drift.AppPathInjectHook
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_FLYWAY_BASE
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import io.micrometer.core.instrument.MeterRegistry
import org.hibernate.resource.jdbc.spi.StatementInspector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 래치 하나 — 멈추는 쪽이 [reached]를 내리고 [release]를 기다린다. 30초 넘게 안 풀리면 테스트 실패로 드러나게 그냥 진행한다.
 */
class Adr015Gate {
    val reached = CountDownLatch(1)
    val release = CountDownLatch(1)
    fun park() {
        reached.countDown()
        release.await(30, TimeUnit.SECONDS)
    }
}

/**
 * ADR-015 task 15-02 재현 스모크 #1용 래치 상태(테스트 전용 · main 무변경).
 * - L-C(`current`): Hibernate StatementInspector가 `insert into reservation` 준비 직전에 멈춘다 = `seat.confirm` 뒤 · save의 INSERT 앞
 *   (IDENTITY라 save 자리에서 INSERT가 나간다 — 그 SQL이 준비되는 순간이 곧 ConfirmReservationService.kt:47과 :49 사이의 끝).
 * - L-B(JPA): 기존 이음새 `AppPathInjectHook.afterExpiryDecrement`(expiry-repair=true · 가드 감소 1행).
 */
object Adr015Latch {
    @Volatile var lc: Adr015Gate? = null
    @Volatile var lb: Adr015Gate? = null
    @Volatile var recording = false
    val sql = CopyOnWriteArrayList<String>()

    fun onSql(s: String) {
        if (recording) sql.add("${Thread.currentThread().name} | $s")
        val g = lc
        if (g != null && s.trimStart().lowercase().startsWith("insert into reservation")) {
            lc = null
            g.park()
        }
    }

    fun onExpiryDecrement() {
        val g = lb ?: return
        lb = null
        g.park()
    }

    fun reset() {
        lc = null; lb = null; recording = false; sql.clear()
    }
}

/** `spring.jpa.properties.hibernate.session_factory.statement_inspector`로 Hibernate가 이름으로 만든다(무인자 생성자). */
class Adr015SqlLatch : StatementInspector {
    override fun inspect(sql: String): String {
        Adr015Latch.onSql(sql)
        return sql
    }
}

/**
 * ADR-015 task 15-02 — 재현 스모크 #1(load-bearing 가정 실증). `current`(A sweep JPA 확정 · JPA 배치) 그대로에서
 * FM1 · FM3 끼어들기를 래치로 만들어 예외 · 응답 · 롤백 · 끝 DB 상태 · SQL 순서를 관찰하고, EPQ 한 칸을 순수 JDBC로 본다.
 * 관찰값은 `[ADR015]` 줄로 표준 출력에 남긴다(먼저 출력 · 그다음 단언).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "seat.hold.expiry-scheduler-enabled=false",
        // L-B(JPA) 이음새가 불리는 조건(ExpiryCounters.kt — expiry-repair=true · 가드 감소 1행)
        "seat.hold.counter-drift.expiry-repair=true",
        PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE,
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.jun.labs.seatreservation.service.Adr015SqlLatch",
        "logging.level.org.hibernate.SQL=DEBUG",
        "logging.level.org.hibernate.orm.jdbc.bind=TRACE",
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015ReproSmokeTest.HookConfig::class)
class Adr015ReproSmokeTest : IntegrationTest() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase
    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var pg: PostgreSQLContainer<*>
    @LocalServerPort var port: Int = 0

    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r).apply { name = "adr015-" + (++threadNo) } }
    private var threadNo = 0
    private val x = 100L
    private val y = 200L

    @TestConfiguration(proxyBeanMethods = false)
    class HookConfig {
        @Bean
        fun adr015Hook(): AppPathInjectHook = object : AppPathInjectHook {
            override fun afterHoldIncrement(scheduleId: Long, userId: Long) = Unit
            override fun afterExpiryDecrement(scheduleId: Long, userId: Long) = Adr015Latch.onExpiryDecrement()
        }
    }

    @BeforeEach
    fun pgLogOn() {
        Adr015Latch.reset()
        // 서버 쪽 실행 순서(JDBC 문장 포함) — 컨테이너 로그에서 표지 사이를 잘라 본다
        jdbcTemplate.execute("ALTER SYSTEM SET log_statement = 'all'")
        jdbcTemplate.execute("ALTER SYSTEM SET log_min_error_statement = 'error'")
        jdbcTemplate.execute("ALTER SYSTEM SET log_line_prefix = '%m [%p] xid=%x '")
        jdbcTemplate.queryForObject("SELECT pg_reload_conf()", Boolean::class.java)
    }

    @AfterEach
    fun pgLogOff() {
        Adr015Latch.reset()
        jdbcTemplate.execute("ALTER SYSTEM RESET log_statement")
        jdbcTemplate.execute("ALTER SYSTEM RESET log_min_error_statement")
        jdbcTemplate.execute("ALTER SYSTEM RESET log_line_prefix")
        jdbcTemplate.queryForObject("SELECT pg_reload_conf()", Boolean::class.java)
        pool.shutdownNow()
    }

    // ---- 관찰 도구 --------------------------------------------------------------------------------

    private fun out(msg: String) = println("[ADR015] $msg")

    private fun chain(e: Throwable): String = generateSequence(e) { it.cause }.joinToString("\n    <- ") {
        val state = (it as? SQLException)?.sqlState?.let { s -> " [SQLSTATE $s]" } ?: ""
        "${it.javaClass.name}$state: ${it.message?.lineSequence()?.firstOrNull()}"
    }

    private fun marker(name: String) {
        jdbcTemplate.queryForObject("SELECT '$name'::text", String::class.java)
    }

    /** 컨테이너 로그에서 표지 사이 + 표지 SELECT·log 토글·pg_stat_activity 폴링 줄을 뺀 서버 실행 순서 */
    private fun pgLogBetween(from: String, to: String): List<String> {
        val lines = pg.logs.lines()
        val a = lines.indexOfLast { it.contains("SELECT '$from'") }
        val b = lines.indexOfLast { it.contains("SELECT '$to'") }
        if (a < 0 || b < 0 || b <= a) return listOf("(표지 못 찾음 a=$a b=$b)")
        return lines.subList(a + 1, b).filter { l ->
            listOf("statement:", "execute", "ERROR", "DETAIL", "STATEMENT", "LOG:  duration").any { l.contains(it) } &&
                !l.contains("pg_stat_activity") && !l.contains("SELECT 1")
        }
    }

    private fun tick(outcome: String): Double =
        meters.find(ExpireHoldsService.TICK_METRIC).tag("outcome", outcome).counter()?.count() ?: 0.0

    private fun dbState(): String {
        val seats = jdbcTemplate.queryForList("SELECT id, status FROM product_seat ORDER BY id")
        val holds = jdbcTemplate.queryForList("SELECT id, seat_id, user_id, expires_at FROM seat_hold ORDER BY id")
        val res = jdbcTemplate.queryForList("SELECT id, seat_id, user_id, status FROM reservation ORDER BY id")
        val quota = jdbcTemplate.queryForList("SELECT schedule_id, user_id, cnt FROM user_hold_quota ORDER BY user_id")
        return "seats=$seats\n    holds=$holds\n    reservations=$res\n    quota=$quota"
    }

    /**
     * 판정기(LoadtestDataService.consistency — loadtest 프로필 빈이라 이 컨텍스트에 없다)의 해당 항목을 같은 정의로 다시 센다.
     * 차이 하나: `active_on_reserved_seats`·`double_sold_seats`의 '활성'은 DB now() 대신 **앱 Clock**(테스트 시계는 2026-09-27 고정이라 DB now()로는 모두 만료).
     */
    private fun verdict(): Map<String, Any?> = jdbcTemplate.queryForMap(
        """
        WITH confirmed_per_seat AS (SELECT seat_id, count(*) AS c FROM reservation WHERE status = 'CONFIRMED' GROUP BY seat_id),
        a AS (SELECT seat_id, count(*) AS n FROM seat_hold WHERE expires_at > ? GROUP BY seat_id),
        actual AS (
            SELECT schedule_id, user_id, sum(n) AS total FROM (
                SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
                UNION ALL
                SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2
            ) u GROUP BY 1, 2
        ),
        pairs AS (SELECT coalesce(q.cnt, 0) AS cnt, coalesce(a.total, 0) AS total FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id))
        SELECT
          (SELECT count(*) FROM confirmed_per_seat WHERE c > 1) AS v_duplicate_confirmed_seats,
          (SELECT count(*) FROM product_seat s WHERE s.status = 'RESERVED'
             AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.seat_id = s.id AND r.status = 'CONFIRMED')) AS v_reserved_without_confirmed,
          (SELECT count(*) FROM reservation r JOIN product_seat s ON s.id = r.seat_id
             WHERE r.status = 'CONFIRMED' AND s.status <> 'RESERVED') AS v_confirmed_on_unreserved_seat,
          (SELECT count(*) FROM product_seat s WHERE s.status = 'HELD'
             AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = s.id)) AS v_held_without_hold,
          (SELECT count(DISTINCT h.seat_id) FROM seat_hold h JOIN product_seat s ON s.id = h.seat_id WHERE s.status <> 'HELD') AS v_hold_on_unheld_seat,
          (SELECT count(*) FROM a JOIN product_seat s ON s.id = a.seat_id WHERE s.status = 'RESERVED') AS active_on_reserved_seats_appclock,
          (SELECT count(*) FROM a FULL JOIN confirmed_per_seat c USING (seat_id) WHERE coalesce(a.n, 0) + coalesce(c.c, 0) >= 2) AS double_sold_seats_appclock,
          (SELECT count(*) FROM pairs WHERE cnt <> total) AS v_counter_mismatch
        """.trimIndent(),
        Timestamp.from(clock.instant()),
    )

    private fun <T> await(f: Future<T>): Result<T> = runCatching { f.get(30, TimeUnit.SECONDS) }.let { r ->
        r.exceptionOrNull()?.let { e -> if (e is java.util.concurrent.ExecutionException) Result.failure(e.cause!!) else r } ?: r
    }

    private fun reached(g: Adr015Gate, what: String) =
        assertTrue(g.reached.await(15, TimeUnit.SECONDS), "$what 래치에 닿지 않았다")

    // ---- FM1 -------------------------------------------------------------------------------------

    /**
     * FM1(서비스 직접 호출 — 예외 체인): X 확정 L-C(:now < te) → 시계 te 뒤로 → 배치 끝까지(커밋) → Y 선점(커밋) → L-C 풂.
     */
    @Test
    fun `FM1 서비스 직접 — X 확정 L-C 뒤 배치 만료 커밋 · Y 선점 커밋 · X 진행`() {
        val seat = createSeat(1)
        val holdX = holdSeat.hold(HoldSeatCommand(schedule.id!!, seat.id!!, x))
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        out("FM1-svc 시작 te=${holdX.expiresAt} X :now=${clock.instant()}")
        marker("fm1svc-begin")
        Adr015Latch.recording = true

        val lc = Adr015Gate().also { Adr015Latch.lc = it }
        val xf = pool.submit<ConfirmReservationResult> { confirm.confirm(ConfirmReservationCommand(holdX.holdId, x, "pay-x")) }
        reached(lc, "L-C")

        clock.set(NOW.plus(Duration.ofMinutes(6)))
        val ok0 = tick("ok"); val failed0 = tick("failed")
        val n = expire.expire()
        out("FM1-svc 배치 expire()=$n tick ok +${tick("ok") - ok0} failed +${tick("failed") - failed0}")
        val holdY = holdSeat.hold(HoldSeatCommand(schedule.id!!, seat.id!!, y))
        out("FM1-svc Y 선점 holdId=${holdY.holdId} · 배치·Y 뒤 상태:\n    ${dbState()}")

        lc.release.countDown()
        val xr = await(xf)
        Adr015Latch.recording = false
        marker("fm1svc-end")

        val xe = xr.exceptionOrNull()
        out("FM1-svc X 결과: " + (xr.getOrNull()?.toString() ?: "예외\n    ${chain(xe!!)}"))
        out("FM1-svc 끝 상태:\n    ${dbState()}")
        out("FM1-svc 판정기: ${verdict()}")
        out("FM1-svc Hibernate SQL(준비 순서):\n    " + Adr015Latch.sql.joinToString("\n    "))
        out("FM1-svc PG 실행 로그:\n    " + pgLogBetween("fm1svc-begin", "fm1svc-end").joinToString("\n    "))

        assertNotNull(xe, "가정 1: X는 예외로 끝나야 한다")
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation", Long::class.java), "X 롤백 — 예약 0")
        assertEquals(true, holdExists(holdY.holdId), "Y 홀드 그대로")
    }

    /** FM1(HTTP 경유 — 응답 코드): 실제 Tomcat + 컨트롤러 · 같은 순서. */
    @Test
    fun `FM1 HTTP — 진 X 확정의 응답 코드`() {
        val seat = createSeat(1)
        val holdX = holdSeat.hold(HoldSeatCommand(schedule.id!!, seat.id!!, x))
        clock.set(NOW.plus(Duration.ofMinutes(4)))

        val lc = Adr015Gate().also { Adr015Latch.lc = it }
        val http = HttpClient.newHttpClient()
        val req = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/holds/${holdX.holdId}/confirm"))
            .header(USER_ID_HEADER, x.toString()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"paymentUid":"pay-x"}""")).build()
        val xf = pool.submit<HttpResponse<String>> { http.send(req, HttpResponse.BodyHandlers.ofString()) }
        reached(lc, "L-C(HTTP)")

        clock.set(NOW.plus(Duration.ofMinutes(6)))
        expire.expire()
        val holdY = holdSeat.hold(HoldSeatCommand(schedule.id!!, seat.id!!, y))
        lc.release.countDown()
        val resp = await(xf).getOrThrow()

        out("FM1-http X 응답: status=${resp.statusCode()} body=${resp.body()}")
        out("FM1-http 끝 상태:\n    ${dbState()}")
        out("FM1-http 판정기: ${verdict()}")

        assertEquals(500, resp.statusCode(), "가정 1: 진 X는 500")
        assertEquals(true, holdExists(holdY.holdId))
    }

    // ---- FM3 -------------------------------------------------------------------------------------

    /**
     * FM3: X 확정 L-C(:now < te) → 시계 te 뒤로 → 배치 L-B(JPA — 후보 읽기 · 가드 감소 뒤 · flush 앞) → L-C 풂 · X 커밋 → 배치 풂.
     */
    @Test
    fun `FM3 — X 확정이 먼저 커밋 · 배치는 L-B에서 flush`() {
        val seat = createSeat(1)
        val holdX = holdSeat.hold(HoldSeatCommand(schedule.id!!, seat.id!!, x))
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        out("FM3 시작 te=${holdX.expiresAt} X :now=${clock.instant()} · 시작 상태:\n    ${dbState()}")
        marker("fm3-begin")
        Adr015Latch.recording = true

        val lc = Adr015Gate().also { Adr015Latch.lc = it }
        val xf = pool.submit<ConfirmReservationResult> { confirm.confirm(ConfirmReservationCommand(holdX.holdId, x, "pay-x")) }
        reached(lc, "L-C")

        clock.set(NOW.plus(Duration.ofMinutes(6)))
        val ok0 = tick("ok"); val failed0 = tick("failed")
        val lb = Adr015Gate().also { Adr015Latch.lb = it }
        val bf = pool.submit<Int> { expire.expire() }
        reached(lb, "L-B(JPA)")
        out("FM3 배치 L-B 도달(가드 감소 뒤 · flush 앞)")

        lc.release.countDown()
        val xr = await(xf)
        out("FM3 X 결과: " + (xr.getOrNull()?.toString() ?: "예외\n    ${chain(xr.exceptionOrNull()!!)}"))
        out("FM3 X 커밋 뒤(배치 L-B 대기 중) 상태:\n    ${dbState()}")

        lb.release.countDown()
        val br = await(bf)
        Adr015Latch.recording = false
        marker("fm3-end")

        val be = br.exceptionOrNull()
        out("FM3 배치 결과: " + (br.getOrNull()?.let { "expire()=$it" } ?: "예외\n    ${chain(be!!)}"))
        out("FM3 tick 지표: ok +${tick("ok") - ok0} failed +${tick("failed") - failed0}")
        out("FM3 끝 상태:\n    ${dbState()}")
        out("FM3 판정기: ${verdict()}")
        out("FM3 Hibernate SQL(준비 순서):\n    " + Adr015Latch.sql.joinToString("\n    "))
        out("FM3 PG 실행 로그:\n    " + pgLogBetween("fm3-begin", "fm3-end").joinToString("\n    "))

        assertTrue(xr.isSuccess, "X 확정은 성공")
        assertNotNull(be, "배치는 예외(틱 롤백)")
        assertEquals(1.0, tick("failed") - failed0)
    }

    // ---- EPQ -------------------------------------------------------------------------------------

    /**
     * EPQ 한 칸(순수 JDBC · RC): 커넥션 1이 홀드 행을 바꾸고 미커밋 → 커넥션 2의 조건부 DELETE가 기다림 → 커넥션 1 커밋 → 커넥션 2 영향 행.
     * 갈래 (a) 조건 열(expires_at)을 늘림 → 기대 0행 · (b) 대조: 조건과 무관한 열(held_at)만 바꿈 → 기대 1행(기다린 뒤 0이 항상 나오는 게 아님을 보인다).
     */
    @Test
    fun `EPQ — 잠긴 행을 기다린 조건부 DELETE는 최신 버전으로 WHERE를 다시 본다`() {
        val seat = createSeat(1)
        val te = NOW.plus(Duration.ofMinutes(5))
        val now = Timestamp.from(NOW.plus(Duration.ofMinutes(6)))
        val results = mutableMapOf<String, Int>()
        for ((label, update) in listOf(
            "a-expires_at-extended" to "UPDATE seat_hold SET expires_at = expires_at + interval '10 minutes' WHERE id = ?",
            "b-held_at-only" to "UPDATE seat_hold SET held_at = held_at + interval '1 second' WHERE id = ?",
        )) {
            jdbcTemplate.update("DELETE FROM seat_hold")
            val holdId = insertHold(seat, x, NOW, te)
            dataSource.connection.use { c1 ->
                c1.autoCommit = false
                c1.transactionIsolation = java.sql.Connection.TRANSACTION_READ_COMMITTED
                val u = c1.prepareStatement(update).apply { setLong(1, holdId) }.executeUpdate()
                val pid1 = c1.createStatement().executeQuery("SELECT pg_backend_pid()").let { it.next(); it.getInt(1) }
                val df = pool.submit<Int> {
                    dataSource.connection.use { c2 ->
                        c2.autoCommit = true
                        c2.prepareStatement("DELETE FROM seat_hold WHERE id = ? AND expires_at <= ?").apply {
                            setLong(1, holdId); setTimestamp(2, now)
                        }.executeUpdate()
                    }
                }
                // 커넥션 2가 커넥션 1(pid1) 때문에 잠금 대기 중인지 확인
                var waiting = false
                repeat(100) {
                    if (!waiting) {
                        waiting = jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)) AND query LIKE 'DELETE FROM seat_hold%'",
                            Int::class.java, pid1,
                        )!! > 0
                        if (!waiting) Thread.sleep(50)
                    }
                }
                val doneBeforeCommit = df.isDone
                c1.commit()
                val deleted = df.get(15, TimeUnit.SECONDS)
                results[label] = deleted
                out("EPQ $label: c1 UPDATE=$u · c2 잠금 대기 관측=$waiting · c1 커밋 전 c2 끝남=$doneBeforeCommit · c2 DELETE 영향 행=$deleted · 남은 행=${holdExists(holdId)}")
                assertTrue(waiting, "$label: 커넥션 2가 기다려야 한다")
            }
        }
        assertEquals(0, results["a-expires_at-extended"], "가정 2: 늘어난 expires_at을 다시 보고 지우지 않는다")
        assertEquals(1, results["b-held_at-only"], "대조: 조건과 무관한 변경이면 지운다")
    }
}
