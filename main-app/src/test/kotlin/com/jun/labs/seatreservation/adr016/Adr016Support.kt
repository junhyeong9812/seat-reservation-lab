package com.jun.labs.seatreservation.adr016

import com.jun.labs.seatreservation.adr015.Adr015Base
import com.jun.labs.seatreservation.adr015.Adr015Taps
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * ADR-016 2단계 blind 계약 테스트 공용 장치(task 05 — 구현 diff 미열람 · 테스트 소스만).
 * 기준 = 명세 docs/plans/2026-11-07/adr-016-payment-start/requirement-spec.md §1 · §2(N16-1~8) · §5 + ADR-016 설계-9(우선) · 설계-4b.
 * 새 기능은 외부 계약으로만 건드린다: 설정 키 문자열 · HTTP(상태 · JSON 필드) · JDBC 행 대조 · MeterRegistry 지표 이름/태그 · 기동 성공/실패.
 * 새 Kotlin 심볼(새 클래스 · 새 프로퍼티)에는 기대지 않는다 — 구현 쪽에 그대로 옮겨 컴파일되게.
 */

// ── 손잡이(명세 §1 · 1~2) ─────────────────────────────────────────────────────────────────────
const val THETA_KEY = "seat.confirm.payment-min-remaining"
const val MARGIN_KEY = "seat.confirm.pay-by-margin"
const val OLD_THETA_KEY = "seat.confirm.extend-min-remaining"
const val OLD_THETA_ENV = "SEAT_CONFIRM_EXTENDMINREMAINING"
const val WINDOW_KEY = "seat.confirm.payment-window"

/** ① θ > 0 판 고정값: θ = 30초 · M = 10초(M ≤ θ) */
const val P16_THETA_30S = "$THETA_KEY=30s"
const val P16_MARGIN_10S = "$MARGIN_KEY=10s"
/** ④ 판 고정값: window 2분(Adr015 P_WINDOW) · θ = 5초 · M = 3초 */
const val P16_THETA_5S = "$THETA_KEY=5s"
const val P16_MARGIN_3S = "$MARGIN_KEY=3s"

val THETA_30: Duration = Duration.ofSeconds(30)
val MARGIN_10: Duration = Duration.ofSeconds(10)
val THETA_5: Duration = Duration.ofSeconds(5)
val MARGIN_3: Duration = Duration.ofSeconds(3)
val WINDOW_2M: Duration = Duration.ofMinutes(2)

// ── 지표(명세 §1 · 3 · 5) ─────────────────────────────────────────────────────────────────────
const val M_PAY_START = "seat.confirm.pay_start"
const val M_CONFIRM_REJECTED = "seat.confirm.rejected"
const val M_CONFIRM_FAILED = "seat.confirm.failed"
const val M16_EXTEND = "seat.confirm.extend"
val PAY_START_RESULTS = listOf("ok", "insufficient", "expired", "not_found", "not_owned")
val EXTEND_RESULTS = listOf("extended", "already", "not_needed", "too_late")

// ── 응답 필드(명세 §1 · 4) ────────────────────────────────────────────────────────────────────
val HOLD_KEYS_BASE = setOf("holdId", "seatId", "expiresAt")
val HOLD_KEYS_PAY = HOLD_KEYS_BASE + setOf("payBy", "payWithinSeconds")
/** ADR-017 설계-4.2(I13) — 결제 uid가 더해졌다 */
val PAY_START_KEYS = setOf("holdId", "expiresAt", "payBy", "payWithinSeconds", "extended", "paymentUid")
val ERROR_KEYS = setOf("code", "message")

/** 없는 홀드 id(시퀀스가 닿지 않는 값) */
const val NO_SUCH_HOLD = 999_999_999L

// ── SQL 탭(읽기 전용 표지 포함) ─────────────────────────────────────────────────────────────────

/** 탭 사건 — [sql]은 정규화 문장(Adr015Taps.norm) · [readOnly]는 그 문장을 보낼 때 연결의 setReadOnly 상태 */
data class SqlEvent(val thread: String, val sql: String, val readOnly: Boolean)

/**
 * ① payment-start의 `@Transactional(readOnly = true)`(명세 §1 · 3)를 JDBC 경계에서 본다: 트랜잭션 관리자가 읽기 전용 트랜잭션을 열면
 * 연결에 `setReadOnly(true)`를 건다(DataSourceUtils.prepareConnectionForTransaction — JPA · JDBC 관리자 공통). 테스트 DataSource 감싸개가
 * 연결마다 그 값을 따라가고, 문장마다 (스레드 · 문장 · 그때의 readOnly)를 남긴다. Adr015TapDataSource와 같은 방식(테스트 BeanPostProcessor).
 */
object Adr016SqlTap {
    @Volatile var recording = false
    val log = CopyOnWriteArrayList<SqlEvent>()

    fun onSql(raw: String, readOnly: Boolean) {
        if (recording) log.add(SqlEvent(Thread.currentThread().name, Adr015Taps.norm(raw), readOnly))
    }

    fun reset() {
        recording = false
        log.clear()
    }
}

class Adr016SqlTapDataSource(target: DataSource) : DelegatingDataSource(target) {
    override fun getConnection(): Connection = wrap(super.getConnection())
    override fun getConnection(username: String, password: String): Connection = wrap(super.getConnection(username, password))

    companion object {
        private fun call(target: Any, m: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
            m.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        private fun wrapStatement(st: Statement, ro: AtomicBoolean): Statement = Proxy.newProxyInstance(
            Adr016SqlTapDataSource::class.java.classLoader, arrayOf(Statement::class.java),
        ) { proxy, m, args ->
            when (m.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch" -> {
                    (args?.firstOrNull() as? String)?.let { Adr016SqlTap.onSql(it, ro.get()) }
                    call(st, m, args)
                }
                else -> call(st, m, args)
            }
        } as Statement

        fun wrap(c: Connection): Connection {
            val ro = AtomicBoolean(runCatching { c.isReadOnly }.getOrDefault(false))
            return Proxy.newProxyInstance(Adr016SqlTapDataSource::class.java.classLoader, arrayOf(Connection::class.java)) { proxy, m, args ->
                when (m.name) {
                    "equals" -> proxy === args!![0]
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Adr016Tap($c)"
                    "setReadOnly" -> {
                        ro.set(args!![0] as Boolean)
                        call(c, m, args)
                    }
                    "prepareStatement", "prepareCall" -> {
                        (args?.firstOrNull() as? String)?.let { Adr016SqlTap.onSql(it, ro.get()) }
                        call(c, m, args)
                    }
                    "createStatement" -> wrapStatement(call(c, m, args) as Statement, ro)
                    else -> call(c, m, args)
                }
            } as Connection
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class Adr016SqlTapConfig {
    @Bean
    fun adr016SqlTapPostProcessor(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
            if (bean is DataSource && bean !is Adr016SqlTapDataSource) Adr016SqlTapDataSource(bean) else bean
    }
}

/** 쓰기 · 행 잠금 문장(정규화 문장 기준) — N16-1 '어떤 행도 쓰지 않는다'의 문장 쪽 증거 */
fun isWriteOrLock(s: String): Boolean =
    s.startsWith("insert") || s.startsWith("update") || s.startsWith("delete") || s.startsWith("merge") ||
        s.startsWith("lock") || s.startsWith("truncate") || Adr015Taps.FOR_UPDATE.containsMatchIn(s) || Regex("\\bfor (key )?share\\b").containsMatchIn(s)

// ── 공용 기반 ────────────────────────────────────────────────────────────────────────────────

/**
 * Adr015Base(HTTP · 지표 · DB 도우미) 위에 ADR-016 도우미를 얹는다. 하위 클래스가 `@SpringBootTest(RANDOM_PORT · 고정 키 + 모드)` ·
 * `@Import(TestcontainersConfiguration, ClockConfig, …)`를 단다.
 */
abstract class Adr016Base : Adr015Base() {

    /** Adr015Base.send는 private — 본문 결함(400) · 미디어 형식(415) · 헤더 없음을 보내려고 따로 둔다 */
    fun rawPost(path: String, userId: Long?, contentType: String?, body: String?): Resp {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(60))
        if (userId != null) b.header(USER_ID_HEADER, userId.toString())
        if (contentType != null) b.header("Content-Type", contentType)
        b.POST(if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply { Resp(it.statusCode(), it.body()) }.get(60, TimeUnit.SECONDS)
    }

    // ---- 응답 ----

    fun Resp.keys(): Set<String> = json?.fieldNames()?.asSequence()?.toSet() ?: emptySet()

    fun Resp.instant(field: String): Instant {
        val n = assertNotNull(json?.get(field), "응답에 $field 가 없다: $this")
        return Instant.parse(n.asText())
    }

    fun Resp.long(field: String): Long {
        val n = assertNotNull(json?.get(field), "응답에 $field 가 없다: $this")
        assertTrue(n.isIntegralNumber, "$field 는 정수여야 한다: $this")
        return n.asLong()
    }

    fun Resp.bool(field: String): Boolean {
        val n = assertNotNull(json?.get(field), "응답에 $field 가 없다: $this")
        assertTrue(n.isBoolean, "$field 는 boolean이어야 한다: $this")
        return n.asBoolean()
    }

    /** payWithinSeconds = max(0, ⌊(payBy − now) / 1초⌋)(명세 §1 · 4) — now = 응답 때 앱 Clock(테스트는 고정 시계라 그 값) */
    fun expectedPayWithin(payBy: Instant, now: Instant): Long = maxOf(0L, Math.floorDiv(Duration.between(now, payBy).toMillis(), 1000L))

    fun assertRejected(r: Resp, status: Int, code: String, why: String) {
        assertEquals(status, r.status, "$why: $r")
        assertEquals(code, r.code, "$why — 코드: $r")
        assertEquals(ERROR_KEYS, r.keys(), "$why — 오류 본문 모양 {code, message} 그대로(명세 §2 범위 밖): $r")
    }

    // ---- 지표 ----

    fun c(name: String, vararg tags: String): Double = counter(name, *tags) ?: 0.0

    fun payStartCounts(): Map<String, Double> = PAY_START_RESULTS.associateWith { c(M_PAY_START, "result", it) }

    fun extendCounts(): Map<String, Double> = EXTEND_RESULTS.associateWith { c(M16_EXTEND, "result", it) }

    /** 이름 아래 모든 카운터 합(태그 무관) */
    fun total(name: String): Double = meters.find(name).counters().sumOf { it.count() }

    /** [before] 대비 [result]만 +1 · 나머지 그대로 */
    fun assertPayStartDelta(before: Map<String, Double>, result: String, why: String) {
        val after = payStartCounts()
        val expected = before.mapValues { (k, v) -> if (k == result) v + 1 else v }
        assertEquals(expected, after, "$why — $M_PAY_START{result=$result}만 +1이어야 한다")
        requireCounter(M_PAY_START, "result", result)
    }

    // ---- DB ----

    /** 홀드 · 좌석 · 쿼터 · 예약 행 전부(모든 열 — 표지 extended_at · updated_at 포함) */
    fun rowsSnapshot(): Map<String, List<Map<String, Any?>>> =
        listOf("seat_hold", "product_seat", "user_hold_quota", "reservation").associateWith { t ->
            jdbcTemplate.queryForList("SELECT * FROM $t ORDER BY 1, 2")
        }

    fun extendedAtOf(holdId: Long): Instant? =
        jdbcTemplate.queryForList("SELECT extended_at FROM seat_hold WHERE id = ?", java.sql.Timestamp::class.java, holdId)
            .singleOrNull()?.toInstant()
}
