package com.jun.labs.seatreservation.adr015

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.impl.drift.AppPathInjectHook
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import io.micrometer.core.instrument.MeterRegistry
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * ADR-015 task 15-03 — blind 계약 테스트 공용 장치(테스트 소스만 · main 무변경 · 구현 전에 컴파일).
 * 기준 = 명세(docs/plans/2026-11-04/adr-015-confirm-atomicity/requirement-spec.md) + ADR-015 §설계-0~8.1.
 * 새 모드는 외부 계약으로만 건드린다: 프로퍼티(seat.confirm.*) · HTTP API · DB 상태(JDBC) · 지표 이름 · 기동 성공/실패.
 *
 * 래치(§설계-8.1)는 **DataSource 수준 SQL 탭**이다 — 새 모드는 JDBC 문장(§설계-3 '구현 형태')이라 Hibernate StatementInspector(15-02)로는 보이지 않는다.
 * 테스트 BeanPostProcessor가 DataSource 빈을 감싸 `prepareStatement` · `Statement.execute*` · `commit`을 가로챈다(Hibernate · JdbcTemplate 공통).
 * 래치는 (스레드 · 문장 접두) 조건으로 한 번(또는 정한 횟수) 멈춘다 — 대소문자 · 공백 · 주석 무관. 구현이 그 문장을 보내지 않으면 래치에 닿지 않고
 * [Adr015Gate.awaitArrived]가 시간 안에 거짓 → 테스트 실패(조용히 통과하지 않는다).
 */

// ── 손잡이 · 고정 키 ─────────────────────────────────────────────────────────────────────────
const val MODE_KEY = "seat.confirm.mode"
const val P_SCHED_OFF = "seat.hold.expiry-scheduler-enabled=false"
/** L-B(JPA) 이음새(AppPathInjectHook.afterExpiryDecrement)가 불리는 조건 — expiry-repair=true · 가드 감소 1행(§설계-8.1 표) */
const val P_A_ON = "seat.hold.counter-drift.expiry-repair=true"
const val LOC_BASE_ONLY = "classpath:db/migration"
const val LOC_CONFIRM_VERSION = "classpath:db/migration-confirm-version"
const val P_LOC_BASE = "spring.flyway.locations=$LOC_BASE_ONLY"
const val P_LOC_OPTIMISTIC = "spring.flyway.locations=$LOC_BASE_ONLY,$LOC_CONFIRM_VERSION"
/** ④ 손잡이(§3.4) — 테스트 고정값: window 2분 · 연장 하한 5초(TTL 5분은 application.yml 그대로) */
const val P_WINDOW = "seat.confirm.payment-window=2m"
const val P_MIN_REMAINING = "seat.confirm.payment-min-remaining=5s"
const val P_INDEX_OPTIONAL = "seat.confirm.unique-index-optional=true"

const val M_CURRENT = "$MODE_KEY=current"
const val M_CONDITIONAL = "$MODE_KEY=conditional"
const val M_PESSIMISTIC = "$MODE_KEY=pessimistic"
const val M_OPTIMISTIC = "$MODE_KEY=optimistic"
const val M_EXTEND = "$MODE_KEY=conditional-extend"

// ── 계약 이름(§설계-3 · §3.4 · §3.6 · §설계-4) ────────────────────────────────────────────────────
const val V12_INDEX = "uq_reservation_confirmed_seat"
const val TICK_METRIC = "seat.hold.expiry.tick"
const val M_UNIQUE_VIOLATION = "seat.confirm.unique_violation"
const val M_UNEXPECTED = "seat.confirm.unexpected"
const val M_EXTEND_RESULT = "seat.confirm.extend"
const val M_EXTENSION_LOST = "seat.confirm.extension_lost"

val TTL: Duration = Duration.ofMinutes(5)
val WINDOW: Duration = Duration.ofMinutes(2)
val MIN_REMAINING: Duration = Duration.ofSeconds(5)

const val BATCH_THREAD = "adr015-batch"

// ── SQL 탭 · 래치 ───────────────────────────────────────────────────────────────────────────────

enum class TapKind { SQL, COMMIT, EXPIRY_DECREMENT }

/** 탭 사건 — [sql]은 정규화된 문장(소문자 · 공백 하나 · 주석 제거) · [txSql]은 COMMIT 때 그 트랜잭션에서 이 스레드가 보낸 문장 */
class TapEvent(val kind: TapKind, val thread: String, val sql: String?, val txSql: List<String> = emptyList())

/** 래치 하나 — [capacity]개 스레드까지 멈춘다(도착 순서 = 칸 번호). 30초 넘게 안 풀리면 그냥 진행한다(테스트가 시간 초과로 드러난다). */
class Adr015Gate(val name: String, val capacity: Int = 1) {
    private val slots = List(capacity) { CountDownLatch(1) }
    private val taken = AtomicInteger()
    private val arrivedN = AtomicInteger()
    val threads = arrayOfNulls<String>(capacity)
    val sqls = arrayOfNulls<String>(capacity)

    fun tryPark(ev: TapEvent): Boolean {
        val i = taken.getAndIncrement()
        if (i >= capacity) return false
        threads[i] = ev.thread
        sqls[i] = ev.sql ?: ev.kind.name
        arrivedN.incrementAndGet()
        slots[i].await(30, TimeUnit.SECONDS)
        return true
    }

    fun awaitArrived(n: Int = 1, seconds: Long = 10): Boolean {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (arrivedN.get() < n) {
            if (System.nanoTime() > end) return false
            Thread.sleep(10)
        }
        return true
    }

    fun release(i: Int = 0) = slots[i].countDown()
    fun releaseAll() = slots.forEach { it.countDown() }
}

object Adr015Taps {
    private class Arm(val gate: Adr015Gate, val match: (TapEvent) -> Boolean)

    private val arms = CopyOnWriteArrayList<Arm>()
    private val sinceTx = ThreadLocal.withInitial { mutableListOf<String>() }

    @Volatile var recording = false
    /** (스레드, 정규화 문장) — [recording] 동안만 */
    val log = CopyOnWriteArrayList<Pair<String, String>>()

    private val COMMENT_BLOCK = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val WS = Regex("\\s+")
    val FOR_UPDATE = Regex("\\bfor (no key )?update\\b")

    fun norm(sql: String): String = sql.replace(COMMENT_BLOCK, " ")
        .lines().filterNot { it.trim().startsWith("--") }.joinToString(" ")
        .replace(WS, " ").trim().lowercase()

    fun arm(gate: Adr015Gate, match: (TapEvent) -> Boolean): Adr015Gate = gate.also { arms.add(Arm(it, match)) }

    fun onSql(raw: String) {
        val s = norm(raw)
        val t = Thread.currentThread().name
        if (recording) log.add(t to s)
        sinceTx.get().add(s)
        fire(TapEvent(TapKind.SQL, t, s))
    }

    fun onCommit() {
        val list = sinceTx.get().toList()
        sinceTx.get().clear()
        fire(TapEvent(TapKind.COMMIT, Thread.currentThread().name, null, list))
    }

    fun onRollback() = sinceTx.get().clear()

    fun onExpiryDecrement() = fire(TapEvent(TapKind.EXPIRY_DECREMENT, Thread.currentThread().name, null))

    private fun fire(ev: TapEvent) {
        for (a in arms) if (a.match(ev) && a.gate.tryPark(ev)) return
    }

    fun reset() {
        arms.forEach { it.gate.releaseAll() }
        arms.clear()
        recording = false
        log.clear()
    }

    fun logOf(thread: String): List<String> = log.filter { it.first == thread }.map { it.second }
}

// 문장 판별(정규화된 문장 기준)
fun isHttp(t: String) = t.startsWith("http-nio")
fun touchesHoldRowForWrite(s: String) = s.startsWith("delete from seat_hold") ||
    (s.startsWith("select") && s.contains("from seat_hold") && Adr015Taps.FOR_UPDATE.containsMatchIn(s))
fun touchesSeatRowForWrite(s: String) = s.startsWith("update product_seat") ||
    (s.startsWith("select") && s.contains("from product_seat") && Adr015Taps.FOR_UPDATE.containsMatchIn(s))
fun isReservationInsert(s: String) = s.startsWith("insert into reservation")

/**
 * L-C(§설계-8.1 — 확정 '확인 뒤 · 첫 쓰기 앞'): HTTP 스레드의 첫 `insert into reservation`(current — 15-02와 같은 자리) ·
 * `delete from seat_hold`(① C1 · ③ O2 · ④) · `select … from seat_hold … for update`(② P1) 앞. 모드와 무관한 한 규칙이다.
 * ②는 ADR 표의 'P2 뒤 P3 앞'(잠금을 쥔 채)이 아니라 P1 앞이다 — 쥔 채 멈추면 FM1의 배치가 그 홀드 행에서 기다려 순서를 만들 수 없다(packet에 기록).
 */
val L_C: (TapEvent) -> Boolean = { e -> e.kind == TapKind.SQL && isHttp(e.thread) && e.sql!!.let { isReservationInsert(it) || touchesHoldRowForWrite(it) } }

/** L-S(교착 칸 전용): 확정이 홀드 행을 쥔 뒤 · 좌석 행 쓰기/잠금 앞 — HTTP 스레드의 첫 `update product_seat` · `select … from product_seat … for update` */
val L_S: (TapEvent) -> Boolean = { e -> e.kind == TapKind.SQL && isHttp(e.thread) && touchesSeatRowForWrite(e.sql!!) }

/** L-B(JPA · current) = 기존 이음새 afterExpiryDecrement · L-B′(A′) = 배치 스레드의 첫 `delete from seat_hold`(B3) 앞 — 먼저 오는 쪽 */
val L_B: (TapEvent) -> Boolean = { e ->
    e.thread == BATCH_THREAD && (e.kind == TapKind.EXPIRY_DECREMENT || (e.kind == TapKind.SQL && e.sql!!.startsWith("delete from seat_hold")))
}

/** L-E(④ '`:now` 뒤 · E1 앞'): HTTP 스레드의 `update seat_hold … expires_at …` 앞 */
val L_E: (TapEvent) -> Boolean = { e -> e.kind == TapKind.SQL && isHttp(e.thread) && e.sql!!.let { it.startsWith("update seat_hold") && it.contains("expires_at") } }

/** E1 커밋 앞(동시 두 첫 호출 칸) — HTTP 스레드가 이 트랜잭션에서 `update seat_hold`를 보낸 뒤의 commit */
val L_E_COMMIT: (TapEvent) -> Boolean = { e -> e.kind == TapKind.COMMIT && isHttp(e.thread) && e.txSql.any { it.startsWith("update seat_hold") } }

class Adr015TapDataSource(target: DataSource) : DelegatingDataSource(target) {
    override fun getConnection(): Connection = tapConnection(super.getConnection())
    override fun getConnection(username: String, password: String): Connection = tapConnection(super.getConnection(username, password))

    companion object {
        private fun call(target: Any, m: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
            m.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        fun tapStatement(st: Statement): Statement = Proxy.newProxyInstance(
            Adr015TapDataSource::class.java.classLoader, arrayOf(Statement::class.java),
        ) { proxy, m, args ->
            when (m.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch" -> {
                    (args?.firstOrNull() as? String)?.let { Adr015Taps.onSql(it) }
                    call(st, m, args)
                }
                else -> call(st, m, args)
            }
        } as Statement

        fun tapConnection(c: Connection): Connection = Proxy.newProxyInstance(
            Adr015TapDataSource::class.java.classLoader, arrayOf(Connection::class.java),
        ) { proxy, m, args ->
            when (m.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Adr015Tap($c)"
                "prepareStatement", "prepareCall" -> {
                    (args?.firstOrNull() as? String)?.let { Adr015Taps.onSql(it) }
                    call(c, m, args)
                }
                "createStatement" -> tapStatement(call(c, m, args) as Statement)
                "commit" -> {
                    Adr015Taps.onCommit()
                    call(c, m, args)
                }
                "rollback" -> {
                    if (args.isNullOrEmpty()) Adr015Taps.onRollback()
                    call(c, m, args)
                }
                "close" -> {
                    Adr015Taps.onRollback()
                    call(c, m, args)
                }
                else -> call(c, m, args)
            }
        } as Connection
    }
}

/** SQL 탭(DataSource 감싸기) + L-B(JPA) 이음새 빈. 래치 테스트 컨텍스트가 모두 가져온다. */
@TestConfiguration(proxyBeanMethods = false)
class Adr015TapConfig {
    @Bean
    fun adr015TapPostProcessor(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
            if (bean is DataSource && bean !is Adr015TapDataSource) Adr015TapDataSource(bean) else bean
    }

    @Bean
    fun adr015ExpiryHook(): AppPathInjectHook = object : AppPathInjectHook {
        override fun afterHoldIncrement(scheduleId: Long, userId: Long) = Unit
        override fun afterExpiryDecrement(scheduleId: Long, userId: Long) = Adr015Taps.onExpiryDecrement()
    }
}

/**
 * 'V12 없음' 판(§설계-8.1 정정): 모든 마이그레이션 뒤 테스트 준비 단계가 V12 인덱스만 DROP. 앱 기동 안 Flyway migrate 끝(AFTER_MIGRATE)에 지운다 —
 * 앱의 마이그레이션 뒤 DB 검사(`run`)가 '인덱스 없음 + unique-index-optional=true'를 보게(인덱스가 아직 없으면 IF EXISTS로 지나간다 · 준비 단언은 각 테스트 @BeforeEach).
 */
@TestConfiguration(proxyBeanMethods = false)
class Adr015DropV12Config {
    @Bean
    fun adr015DropV12AfterMigrate(): Callback = object : Callback {
        override fun supports(event: Event, context: Context?) = event == Event.AFTER_MIGRATE
        override fun canHandleInTransaction(event: Event, context: Context?) = true
        override fun handle(event: Event, context: Context) {
            context.connection.createStatement().use { it.execute("DROP INDEX IF EXISTS $V12_INDEX") }
        }
        override fun getCallbackName() = "adr015DropV12"
    }
}

// ── 응답 · 공용 기반 ─────────────────────────────────────────────────────────────────────────────

class Resp(val status: Int, val body: String) {
    val json: JsonNode? = runCatching { ObjectMapper().readTree(body) }.getOrNull()
    val code: String? get() = json?.get("code")?.asText()
    override fun toString() = "HTTP $status $body"
}

/**
 * 래치 테스트 공용 — [IntegrationTest](데이터 초기화 · MutableClock · 좌석 생성) 위에 HTTP · 판정기 · 지표 · 대조 확정/배치를 얹는다.
 * 하위 클래스가 `@SpringBootTest(RANDOM_PORT · 고정 키 + 모드)` · `@Import(TestcontainersConfiguration, ClockConfig, Adr015TapConfig[, Adr015DropV12Config])`를 단다.
 */
abstract class Adr015Base : IntegrationTest() {

    @Autowired lateinit var expire: ExpireHoldsUseCase
    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var dataSource: DataSource
    @LocalServerPort var port: Int = 0

    protected val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    protected val x = 100L
    protected val y = 200L

    private val batchPool: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, BATCH_THREAD) }
    private val ctlNo = AtomicInteger()
    protected val ctlPool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "adr015-ctl-" + ctlNo.incrementAndGet()) }

    @BeforeEach
    fun tapsReset() = Adr015Taps.reset()

    @AfterEach
    fun tapsCleanup() {
        Adr015Taps.reset()
        batchPool.shutdown()
        ctlPool.shutdown()
        batchPool.awaitTermination(40, TimeUnit.SECONDS)
        ctlPool.awaitTermination(40, TimeUnit.SECONDS)
    }

    // ---- HTTP ----

    private fun send(path: String, userId: Long, body: String?): CompletableFuture<Resp> {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(60))
            .header(USER_ID_HEADER, userId.toString())
            .header("Content-Type", "application/json")
            .POST(if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.sendAsync(b, HttpResponse.BodyHandlers.ofString()).thenApply { Resp(it.statusCode(), it.body()) }
    }

    fun holdHttp(seatId: Long, userId: Long): Resp = send("/api/schedules/${schedule.id}/seats/$seatId/hold", userId, null).get(60, TimeUnit.SECONDS)

    fun holdOk(seatId: Long, userId: Long): Long {
        val r = holdHttp(seatId, userId)
        assertEquals(201, r.status, "선점 준비 실패: $r")
        return r.json!!.get("holdId").asLong()
    }

    fun confirmAsync(holdId: Long, userId: Long, pay: String = "pay-$userId"): CompletableFuture<Resp> =
        send("/api/holds/$holdId/confirm", userId, """{"paymentUid":"$pay"}""")

    fun confirmHttp(holdId: Long, userId: Long, pay: String = "pay-$userId"): Resp = confirmAsync(holdId, userId, pay).get(60, TimeUnit.SECONDS)

    fun paymentStartAsync(holdId: Long, userId: Long): CompletableFuture<Resp> = send("/api/holds/$holdId/payment-start", userId, null)

    fun paymentStart(holdId: Long, userId: Long): Resp = paymentStartAsync(holdId, userId).get(60, TimeUnit.SECONDS)

    fun CompletableFuture<Resp>.done(): Resp = get(60, TimeUnit.SECONDS)

    // ---- 래치 ----

    fun reachedOrFail(g: Adr015Gate, n: Int = 1, what: String = g.name) =
        assertTrue(g.awaitArrived(n), "$what 래치에 닿지 않았다(${n}개 기대) — 구현이 그 자리의 문장을 보내지 않았거나 순서가 다르다")

    /** 배치를 [BATCH_THREAD]에서 돌린다 — 결과 또는 예외 */
    fun batchAsync(): Future<Result<Int>> = batchPool.submit<Result<Int>> { runCatching { expire.expire() } }

    fun <T> Future<T>.join(): T = get(60, TimeUnit.SECONDS)

    // ---- DB ----

    fun reservationCount(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM reservation", Int::class.java)!!
    fun confirmedOn(seatId: Long): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE seat_id = ? AND status = 'CONFIRMED'", Int::class.java, seatId)!!
    fun seatStatusOf(seatId: Long): String = jdbcTemplate.queryForObject("SELECT status FROM product_seat WHERE id = ?", String::class.java, seatId)!!
    fun cntOf(userId: Long): Int? = jdbcTemplate.queryForList(
        "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, schedule.id, userId,
    ).singleOrNull()
    fun expiresAtOf(holdId: Long): Instant = jdbcTemplate.queryForObject(
        "SELECT expires_at FROM seat_hold WHERE id = ?", Timestamp::class.java, holdId,
    )!!.toInstant()
    fun indexExists(name: String): Boolean =
        jdbcTemplate.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Int::class.java, name)!! > 0

    fun dbState(): String {
        val seats = jdbcTemplate.queryForList("SELECT id, status FROM product_seat ORDER BY id")
        val holds = jdbcTemplate.queryForList("SELECT id, seat_id, user_id, expires_at FROM seat_hold ORDER BY id")
        val res = jdbcTemplate.queryForList("SELECT id, seat_id, user_id, status FROM reservation ORDER BY id")
        val quota = jdbcTemplate.queryForList("SELECT schedule_id, user_id, cnt FROM user_hold_quota ORDER BY user_id")
        return "seats=$seats holds=$holds reservations=$res quota=$quota"
    }

    /** 잠금 대기 관측 — 정규화한 문장에 [likeFragment]가 든 백엔드가 Lock 대기([blockingPid]가 있으면 그 pid에 막힘) */
    fun awaitLockWait(likeFragment: String, blockingPid: Int? = null, seconds: Long = 10): Boolean {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < end) {
            val base = "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                "AND lower(regexp_replace(query, '\\s+', ' ', 'g')) LIKE ?"
            val n = if (blockingPid == null) jdbcTemplate.queryForObject(base, Int::class.java, "%$likeFragment%")!!
            else jdbcTemplate.queryForObject("$base AND ? = ANY(pg_blocking_pids(pid))", Int::class.java, "%$likeFragment%", blockingPid)!!
            if (n > 0) return true
            Thread.sleep(20)
        }
        return false
    }

    // ---- 지표 ----

    /** 이름(+태그) 카운터 합 — 등록된 카운터가 없으면 null('없음') */
    fun counter(name: String, vararg tags: String): Double? {
        val cs = meters.find(name).tags(*tags).counters()
        return if (cs.isEmpty()) null else cs.sumOf { it.count() }
    }

    /** 늘어나야 하는 지표 — 사건 뒤에도 등록되지 않았으면 '없음'으로 실패 */
    fun requireCounter(name: String, vararg tags: String): Double =
        assertNotNull(counter(name, *tags), "지표 $name${tags.toList()}가 없다(이름 · 태그 계약 — §설계-3 · §3.4 · §3.6)")

    // ---- 판정기 ----

    /**
     * 끝 판정기(LoadtestDataService.consistency · expiryStates — LoadtestDataService.kt:140-234)를 같은 정의로 다시 센다(loadtest 프로필 빈이라 컨텍스트에 없다 — 15-02와 같다).
     * 차이: '활성'은 DB now() 대신 앱 Clock(테스트 시계 2026-09-27 고정 — §설계-0 A 규칙). 시간 의존 항목(v_stale_held_seats · held_no_active · expired_hold_rows)은
     * 기대값이 0이 아니거나 유예에 기대므로 뺀다. 마감 회차 없음(회차 2099).
     */
    fun verdict(): Map<String, Long> = jdbcTemplate.queryForMap(
        """
        WITH confirmed_per_seat AS (SELECT seat_id, count(*) AS c FROM reservation WHERE status = 'CONFIRMED' GROUP BY seat_id),
        hold_per_seat AS (SELECT seat_id, count(*) AS c FROM seat_hold GROUP BY seat_id),
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
          (SELECT count(*) FROM hold_per_seat WHERE c > 1) AS v_duplicate_hold_seats,
          (SELECT count(*) FROM confirmed_per_seat WHERE c > 1) AS v_duplicate_confirmed_seats,
          (SELECT count(*) FROM product_seat s WHERE s.status = 'RESERVED'
             AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.seat_id = s.id AND r.status = 'CONFIRMED')) AS v_reserved_without_confirmed,
          (SELECT count(*) FROM reservation r JOIN product_seat s ON s.id = r.seat_id
             WHERE r.status = 'CONFIRMED' AND s.status <> 'RESERVED') AS v_confirmed_on_unreserved_seat,
          (SELECT count(*) FROM product_seat s WHERE s.status = 'HELD'
             AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = s.id)) AS v_held_without_hold,
          (SELECT count(DISTINCT h.seat_id) FROM seat_hold h JOIN product_seat s ON s.id = h.seat_id WHERE s.status <> 'HELD') AS v_hold_on_unheld_seat,
          (SELECT count(*) FROM actual WHERE total > 2) AS v_over_limit_users,
          (SELECT count(*) FROM a FULL JOIN confirmed_per_seat c USING (seat_id) WHERE coalesce(a.n, 0) + coalesce(c.c, 0) >= 2) AS double_sold_seats,
          (SELECT count(*) FROM a WHERE n > 1) AS multi_active_seats,
          (SELECT count(*) FROM a JOIN product_seat s ON s.id = a.seat_id WHERE s.status <> 'HELD') AS active_not_held,
          (SELECT count(*) FROM a JOIN product_seat s ON s.id = a.seat_id WHERE s.status = 'RESERVED') AS active_on_reserved_seats,
          (SELECT count(*) FROM pairs WHERE cnt <> total) AS v_counter_mismatch
        """.trimIndent(),
        Timestamp.from(clock.instant()),
    ).mapValues { (it.value as Number).toLong() }

    fun assertVerdictClean(why: String) {
        val v = verdict()
        assertTrue(v.values.all { it == 0L }, "$why — 판정기 위반: ${v.filterValues { it != 0L }} · 상태 ${dbState()}")
    }

    fun sqlStates(e: Throwable?): Set<String> =
        generateSequence(e) { it.cause.takeIf { c -> c !== it } }.mapNotNull { (it as? SQLException)?.sqlState }.toSet()

    // ---- 대조 확정 · 대조 배치(§설계-0 · §설계-8.1 — 테스트 소스에만 · 가드 없음) ----

    /**
     * 대조 확정: 지금 A와 같은 확인(없음 → 내 것 → 만료(:now) → HELD) → [gate](L-C 대조) → 무조건 JDBC 쓰기(예약 INSERT · 홀드 DELETE · 좌석 RESERVED) · 행 수를 보지 않음.
     * 결과 = "OK" 또는 거부 코드 이름.
     */
    fun controlConfirm(holdId: Long, userId: Long, gate: Adr015Gate?): Future<Result<String>> = ctlPool.submit<Result<String>> {
        runCatching {
            dataSource.connection.use { c ->
                c.autoCommit = false
                try {
                    val now = clock.instant()
                    val row = c.prepareStatement(
                        "SELECT h.user_id, h.expires_at, h.seat_id, h.schedule_id, s.status FROM seat_hold h JOIN product_seat s ON s.id = h.seat_id WHERE h.id = ?",
                    ).use { ps ->
                        ps.setLong(1, holdId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) null
                            else listOf(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getLong(3), rs.getLong(4), rs.getString(5))
                        }
                    }
                    val verdictCode = when {
                        row == null -> "HOLD_NOT_FOUND"
                        row[0] != userId -> "HOLD_NOT_OWNED"
                        !(row[1] as Instant).isAfter(now) -> "HOLD_EXPIRED"
                        row[4] != "HELD" -> "SEAT_NOT_HELD"
                        else -> null
                    }
                    if (verdictCode != null) {
                        c.rollback(); return@use verdictCode
                    }
                    gate?.tryPark(TapEvent(TapKind.SQL, Thread.currentThread().name, "control-confirm"))
                    val seatId = row!![2] as Long
                    c.prepareStatement(
                        "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, ?, 'CONFIRMED', ?)",
                    ).use { ps ->
                        ps.setLong(1, row[3] as Long); ps.setLong(2, seatId); ps.setLong(3, userId); ps.setString(4, "ctl-$userId")
                        ps.setTimestamp(5, Timestamp.from(now)); ps.executeUpdate()
                    }
                    c.prepareStatement("DELETE FROM seat_hold WHERE id = ?").use { it.setLong(1, holdId); it.executeUpdate() }
                    c.prepareStatement("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?").use { it.setLong(1, seatId); it.executeUpdate() }
                    c.commit()
                    "OK"
                } catch (e: Throwable) {
                    c.rollback(); throw e
                }
            }
        }
    }

    data class ControlBatchResult(val candidates: Int, val deleted: Int, val lost: Int, val extended: Int)

    /**
     * 대조 배치: 후보 = `expires_at <= :now` → 후보 수만큼 쌍별 감소 → [gate](L-B 대조) → `DELETE … WHERE id = ANY(:ids)`(만료 재확인 없음 · 행 수 안 봄)
     * → `UPDATE product_seat SET status='AVAILABLE' WHERE id = ANY(:seats)`(상태 조건 없음). RETURNING은 A′와 같은 열(잃음 셈 · extended는 V13 열이 있을 때만).
     */
    fun controlBatch(now: Instant, gate: Adr015Gate?): Future<Result<ControlBatchResult>> = ctlPool.submit<Result<ControlBatchResult>> {
        runCatching {
            dataSource.connection.use { c ->
                c.autoCommit = false
                try {
                    val ts = Timestamp.from(now)
                    val cand = mutableListOf<LongArray>() // id, seat, schedule, user
                    c.prepareStatement("SELECT id, seat_id, schedule_id, user_id FROM seat_hold WHERE expires_at <= ? ORDER BY id").use { ps ->
                        ps.setTimestamp(1, ts)
                        ps.executeQuery().use { rs -> while (rs.next()) cand += longArrayOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }
                    }
                    cand.groupBy { it[2] to it[3] }.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (k, v) ->
                        c.prepareStatement("UPDATE user_hold_quota SET cnt = cnt - ? WHERE schedule_id = ? AND user_id = ?").use { ps ->
                            ps.setInt(1, v.size); ps.setLong(2, k.first); ps.setLong(3, k.second); ps.executeUpdate()
                        }
                    }
                    gate?.tryPark(TapEvent(TapKind.SQL, Thread.currentThread().name, "control-batch"))
                    val hasExt = c.prepareStatement(
                        "SELECT count(*) FROM information_schema.columns WHERE table_name = 'seat_hold' AND column_name = 'extended_at'",
                    ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 } }
                    var deleted = 0; var lost = 0; var extended = 0
                    c.prepareStatement(
                        "DELETE FROM seat_hold WHERE id = ANY(?) RETURNING expires_at > ? AS lost" + (if (hasExt) ", extended_at IS NOT NULL AS extended" else ", false AS extended"),
                    ).use { ps ->
                        ps.setArray(1, c.createArrayOf("bigint", cand.map { it[0] }.toTypedArray()))
                        ps.setTimestamp(2, ts)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                deleted++
                                if (rs.getBoolean(1)) lost++
                                if (rs.getBoolean(2)) extended++
                            }
                        }
                    }
                    c.prepareStatement("UPDATE product_seat SET status = 'AVAILABLE' WHERE id = ANY(?)").use { ps ->
                        ps.setArray(1, c.createArrayOf("bigint", cand.map { it[1] }.distinct().toTypedArray()))
                        ps.executeUpdate()
                    }
                    c.commit()
                    ControlBatchResult(cand.size, deleted, lost, extended)
                } catch (e: Throwable) {
                    c.rollback(); throw e
                }
            }
        }
    }

    fun failIfNot(cond: Boolean, msg: () -> String) {
        if (!cond) fail(msg())
    }
}
