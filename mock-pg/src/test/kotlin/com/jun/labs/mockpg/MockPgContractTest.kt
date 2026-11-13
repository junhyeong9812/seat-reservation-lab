package com.jun.labs.mockpg

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * ADR-017 task 03 — 흉내 PG 계약(blind — 구현 미열람 · 명세 부록 A + ADR-017 설계-3 · 설계-7 N17-6 · 설계-10 RD17-10 · 13 · P1만 보고 썼다).
 * 외부 계약으로만 본다: HTTP 경로 · 상태 코드 · 본문 {payment: {uid, userId, amount, deadline, state, registeredAt, authorizedAt, capturedAt, closedAt, refunds, tombstone}, code}.
 * 원장 시계: 빈 `mockPgClock`(부록 A)을 테스트 시계로 바꾼다(BeanPostProcessor로 인스턴스 교체 — 정의 순서와 무관).
 *
 * 가정(근거 조항):
 *  - `mockPgClock`은 java.time.Clock 타입이다(부록 A '원장 시계 빈 mockPgClock을 … 위임하는 시계로 바꾼다').
 *  - 성공 응답 상태는 2xx(200 또는 201) — 부록 A가 성공 코드를 정하지 않았다(close 멱등만 '200' — 설계-3 표). 그래서 close 성공은 200으로, 나머지는 2xx로 본다.
 *  - deadline · 시각 필드는 ISO-8601 Instant 문자열이다(Jackson 기본 · 앱 응답 payBy와 같은 꼴).
 *  - `GET /payments/{uid}`는 주입 대상이 아니다(부록 A op = register · pay · authorize · capture · close).
 *  - `POST /admin/faults`는 주입 규칙을 통째로 바꾸고, 같은 seed로 다시 설정하면 난수 흐름이 처음부터 다시 시작한다(설계-3 '회차 시드로 재현').
 *  - 상태 이름 409의 code는 지금 상태 이름이다(부록 A '거부 코드 … 상태 이름(409)').
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["mock-pg.auth-ttl=1m"])
@Import(MockPgContractTest.ClockSwap::class)
class MockPgContractTest {

    @LocalServerPort var port: Int = 0

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val om = jacksonObjectMapper()

    // ── 시계 교체 ─────────────────────────────────────────────────────────────────────────────

    class TestPgClock(start: Instant) : Clock() {
        private val now = AtomicReference(start)
        fun set(i: Instant) = now.set(i)
        fun advance(d: Duration) { now.updateAndGet { it.plus(d) } }
        override fun instant(): Instant = now.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ClockSwap {
        @Bean
        fun mockPgClockSwap(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (beanName == "mockPgClock") PG_CLOCK else bean
        }
    }

    companion object {
        val T0: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val PG_CLOCK = TestPgClock(T0)
        const val AMOUNT = 10_000L
        val AUTH_TTL: Duration = Duration.ofMinutes(1)
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────────────────────

    class R(val status: Int, val body: String, val json: JsonNode?) {
        val code: String? get() = json?.get("code")?.takeIf { !it.isNull }?.asText()
        val payment: JsonNode? get() = json?.get("payment")?.takeIf { !it.isNull }
        val state: String? get() = payment?.get("state")?.asText()
        fun field(name: String): JsonNode? = payment?.get(name)?.takeIf { !it.isNull }
        fun instant(name: String): Instant? = field(name)?.let { Instant.parse(it.asText()) }
        val refunds: Int get() = field("refunds")?.asInt() ?: 0
        val tombstone: Boolean get() = field("tombstone")?.asBoolean() ?: false
        override fun toString() = "HTTP $status $body"
    }

    private fun send(method: String, path: String, body: String?, timeout: Duration = Duration.ofSeconds(30)): R {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(timeout)
        if (method == "GET") b.GET()
        else b.header("Content-Type", "application/json")
            .POST(if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val res = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return R(res.statusCode(), res.body(), runCatching { om.readTree(res.body()) }.getOrNull())
    }

    private fun post(path: String, body: String? = null, timeout: Duration = Duration.ofSeconds(30)) = send("POST", path, body, timeout)

    fun newUid(): String = UUID.randomUUID().toString()

    fun register(uid: String, deadline: Instant = T0.plusSeconds(600), userId: Long = 7L): R =
        post("/intents", om.writeValueAsString(mapOf("uid" to uid, "userId" to userId, "amount" to AMOUNT, "deadline" to deadline.toString())))

    fun registerOk(uid: String, deadline: Instant = T0.plusSeconds(600)): R =
        register(uid, deadline).also { assertTrue(it.status in 200..299, "준비 — 등록 실패: $it") }

    fun pay(uid: String) = post("/payments/$uid/pay")
    fun authorize(uid: String) = post("/payments/$uid/authorize")
    fun capture(uid: String) = post("/payments/$uid/capture")
    fun close(uid: String) = post("/payments/$uid/close")
    fun get(uid: String) = send("GET", "/payments/$uid", null)
    fun ledger() = send("GET", "/ledger", null)
    fun reset() = post("/admin/reset")

    fun faults(seed: Long, rules: Map<String, Map<String, Number>>): R =
        post("/admin/faults", om.writeValueAsString(mapOf("seed" to seed, "rules" to rules))).also {
            assertTrue(it.status in 200..299, "준비 — 주입 설정 실패: $it")
        }

    fun clearFaults() = faults(1, emptyMap())

    fun assert2xx(r: R, why: String) = assertTrue(r.status in 200..299, "$why: $r")

    fun assertReject(r: R, status: Int, code: String, why: String) {
        assertEquals(status, r.status, "$why: $r")
        assertEquals(code, r.code, "$why — code: $r")
    }

    fun stateOf(uid: String): String? = get(uid).state

    @BeforeEach
    fun clean() {
        PG_CLOCK.set(T0)
        clearFaults()
        assert2xx(reset(), "준비 — /admin/reset")
    }

    // ── 장치 대조 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `장치 대조 — 원장 시계가 테스트 시계다(registeredAt = 테스트 시각) · 아니면 아래 시간 계약은 뜻이 없다`() {
        val uid = newUid()
        PG_CLOCK.set(T0.plusSeconds(17))
        val r = registerOk(uid)
        assertEquals(T0.plusSeconds(17), r.instant("registeredAt") ?: get(uid).instant("registeredAt"), "mockPgClock 교체가 닿지 않았다: $r")
    }

    // ── 등록(설계-3 표 /intents) ───────────────────────────────────────────────────────────────

    @Test
    fun `등록 — REGISTERED · uid userId amount deadline 그대로 · refunds 0 · tombstone false · GET도 같다`() {
        val uid = newUid()
        val deadline = T0.plusSeconds(610)
        val r = registerOk(uid, deadline)
        assertEquals("REGISTERED", r.state, "$r")
        assertEquals(uid, r.field("uid")?.asText())
        assertEquals(7L, r.field("userId")?.asLong())
        assertEquals(AMOUNT, r.field("amount")?.asLong())
        assertEquals(deadline, r.instant("deadline"), "deadline = 서버가 준 값(N17-6)")
        assertEquals(0, r.refunds)
        assertFalse(r.tombstone)
        val g = get(uid)
        assertEquals(200, g.status, "$g")
        assertEquals("REGISTERED", g.state)
        assertEquals(deadline, g.instant("deadline"))
    }

    @Test
    fun `등록 멱등 — 같은 uid 재등록 = 2xx · 상태 · registeredAt 그대로`() {
        val uid = newUid()
        val first = registerOk(uid)
        PG_CLOCK.advance(Duration.ofSeconds(5))
        val again = register(uid)
        assert2xx(again, "재등록 멱등")
        assertEquals("REGISTERED", again.state)
        assertEquals(first.instant("registeredAt"), get(uid).instant("registeredAt"), "재등록이 원장을 다시 쓰지 않는다")
    }

    // ── pay(R · S) ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `pay — REGISTERED → CAPTURED · capturedAt = 지금 · 다시 pay = 멱등(같은 결과 · capturedAt 그대로)`() {
        val uid = newUid()
        registerOk(uid)
        PG_CLOCK.advance(Duration.ofSeconds(30))
        val r = pay(uid)
        assert2xx(r, "pay")
        assertEquals("CAPTURED", r.state, "$r")
        assertEquals(T0.plusSeconds(30), get(uid).instant("capturedAt"))

        PG_CLOCK.advance(Duration.ofSeconds(30))
        val again = pay(uid)
        assert2xx(again, "pay 멱등")
        assertEquals("CAPTURED", again.state)
        assertEquals(T0.plusSeconds(30), get(uid).instant("capturedAt"), "멱등 — 두 번째 결제가 원장에 남지 않는다")
        assertEquals(0, get(uid).refunds)
    }

    @Test
    fun `N17-6 — deadline 지나면 pay · authorize 409 DEADLINE_PASSED · 원장에 결제 없음(REGISTERED · capturedAt authorizedAt 없음)`() {
        val uid = newUid()
        val deadline = T0.plusSeconds(60)
        registerOk(uid, deadline)
        PG_CLOCK.set(deadline.plusSeconds(1))

        assertReject(pay(uid), 409, "DEADLINE_PASSED", "deadline 뒤 pay")
        assertReject(authorize(uid), 409, "DEADLINE_PASSED", "deadline 뒤 authorize")
        val g = get(uid)
        assertEquals("REGISTERED", g.state, "결제로 남기지 않는다: $g")
        assertEquals(null, g.field("capturedAt"), "capturedAt 없음: $g")
        assertEquals(null, g.field("authorizedAt"), "authorizedAt 없음: $g")
    }

    // 가정: 설계-3 표 'now > deadline이면 409' — deadline과 같은 순간은 아직 허용이다.
    @Test
    fun `N17-6 경계 — now = deadline이면 pay 허용(now 가 deadline보다 클 때만 거부)`() {
        val uid = newUid()
        val deadline = T0.plusSeconds(60)
        registerOk(uid, deadline)
        PG_CLOCK.set(deadline)
        val r = pay(uid)
        assert2xx(r, "deadline 순간 pay")
        assertEquals("CAPTURED", r.state)
    }

    @Test
    fun `N17-6 — 미등록 uid의 pay · authorize · capture = 404 UNKNOWN_PAYMENT · 원장에 생기지 않음`() {
        val uid = newUid()
        assertReject(pay(uid), 404, "UNKNOWN_PAYMENT", "미등록 pay")
        assertReject(authorize(uid), 404, "UNKNOWN_PAYMENT", "미등록 authorize")
        assertReject(capture(uid), 404, "UNKNOWN_PAYMENT", "미등록 capture")
        assertEquals(404, get(uid).status, "원장에 행이 생기지 않는다")
        assertFalse(ledger().body.contains(uid), "ledger에도 없다")
    }

    // ── authorize · capture(D) ────────────────────────────────────────────────────────────────

    @Test
    fun `authorize — REGISTERED → AUTHORIZED · auth-ttl 안은 그대로 · 지나면 PG가 스스로 EXPIRED`() {
        val uid = newUid()
        registerOk(uid)
        val r = authorize(uid)
        assert2xx(r, "authorize")
        assertEquals("AUTHORIZED", r.state)
        assertEquals(T0, get(uid).instant("authorizedAt"))
        assert2xx(authorize(uid), "authorize 멱등")

        PG_CLOCK.set(T0.plus(AUTH_TTL).minusSeconds(1))
        assertEquals("AUTHORIZED", stateOf(uid), "auth-ttl 안")
        PG_CLOCK.set(T0.plus(AUTH_TTL).plusSeconds(1))
        assertEquals("EXPIRED", stateOf(uid), "auth-ttl(${AUTH_TTL}) 지나면 EXPIRED — 호출 없이(설계-3 '스스로')")
    }

    @Test
    fun `capture — AUTHORIZED → CAPTURED · 다시 capture 멱등 · REGISTERED는 409 상태 이름`() {
        val uid = newUid()
        registerOk(uid)
        assertReject(capture(uid), 409, "REGISTERED", "승인 없는 capture — 상태 이름 409(부록 A)")
        assert2xx(authorize(uid), "authorize")
        PG_CLOCK.advance(Duration.ofSeconds(10))
        val c = capture(uid)
        assert2xx(c, "capture")
        assertEquals("CAPTURED", c.state)
        assertEquals(T0.plusSeconds(10), get(uid).instant("capturedAt"))
        val again = capture(uid)
        assert2xx(again, "capture 멱등")
        assertEquals("CAPTURED", again.state)
    }

    @Test
    fun `capture 종결-실패 — EXPIRED면 409 EXPIRED(분류표 RD17-11) · 결제로 남지 않음`() {
        val uid = newUid()
        registerOk(uid)
        assert2xx(authorize(uid), "authorize")
        PG_CLOCK.set(T0.plus(AUTH_TTL).plusSeconds(1))
        assertReject(capture(uid), 409, "EXPIRED", "만료 승인 capture")
        assertEquals("EXPIRED", stateOf(uid))
        assertEquals(null, get(uid).field("capturedAt"))
    }

    // 가정: 취소된 승인(VOIDED)의 capture 409 code는 설계-3 분류표('CLOSED — 취소') 또는 부록 A(상태 이름 VOIDED) 둘 중 하나 — 둘 다 허용.
    @Test
    fun `capture 종결-실패 — 승인 취소(VOIDED) 뒤 capture 409 · 결제 없음`() {
        val uid = newUid()
        registerOk(uid)
        assert2xx(authorize(uid), "authorize")
        assertEquals(200, close(uid).status)
        val c = capture(uid)
        assertEquals(409, c.status, "$c")
        assertTrue(c.code in setOf("VOIDED", "CLOSED"), "$c")
        assertEquals("VOIDED", stateOf(uid))
    }

    // ── close(보상 공통 — 설계-3) ─────────────────────────────────────────────────────────────

    @Test
    fun `close — REGISTERED → CLOSED · 다시 close = 같은 상태 200 · closedAt 그대로`() {
        val uid = newUid()
        registerOk(uid)
        PG_CLOCK.advance(Duration.ofSeconds(3))
        val c = close(uid)
        assertEquals(200, c.status, "$c")
        assertEquals("CLOSED", c.state)
        assertEquals(T0.plusSeconds(3), get(uid).instant("closedAt"))
        assertFalse(get(uid).tombstone, "등록된 uid의 CLOSED는 닫힘 표지가 아니다")
        PG_CLOCK.advance(Duration.ofSeconds(3))
        val again = close(uid)
        assertEquals(200, again.status, "$again")
        assertEquals("CLOSED", again.state)
        assertEquals(T0.plusSeconds(3), get(uid).instant("closedAt"), "멱등 — closedAt 그대로")
    }

    @Test
    fun `close — AUTHORIZED → VOIDED · 다시 close = VOIDED · refunds 0`() {
        val uid = newUid()
        registerOk(uid)
        assert2xx(authorize(uid), "authorize")
        assertEquals("VOIDED", close(uid).state)
        val again = close(uid)
        assertEquals(200, again.status)
        assertEquals("VOIDED", again.state)
        assertEquals(0, get(uid).refunds)
    }

    @Test
    fun `close — CAPTURED → REFUNDED · refunds 1 · 다시 close(여러 번) = REFUNDED · refunds 1 그대로(이중 환불 0 — N17-4)`() {
        val uid = newUid()
        registerOk(uid)
        assert2xx(pay(uid), "pay")
        val c = close(uid)
        assertEquals(200, c.status, "$c")
        assertEquals("REFUNDED", c.state)
        assertEquals(1, get(uid).refunds)
        repeat(3) {
            val again = close(uid)
            assertEquals(200, again.status, "$again")
            assertEquals("REFUNDED", again.state)
        }
        assertEquals(1, get(uid).refunds, "환불은 한 번만")
    }

    @Test
    fun `close — EXPIRED → CLOSED`() {
        val uid = newUid()
        registerOk(uid)
        assert2xx(authorize(uid), "authorize")
        PG_CLOCK.set(T0.plus(AUTH_TTL).plusSeconds(1))
        assertEquals("EXPIRED", stateOf(uid))
        val c = close(uid)
        assertEquals(200, c.status, "$c")
        assertEquals("CLOSED", c.state)
    }

    @Test
    fun `RD17-10 — CLOSED(NOT_PAID) 뒤 pay · authorize = 409 CLOSED · 결제 없음`() {
        val uid = newUid()
        registerOk(uid)
        assertEquals("CLOSED", close(uid).state)
        assertReject(pay(uid), 409, "CLOSED", "닫힌 uid pay")
        assertReject(authorize(uid), 409, "CLOSED", "닫힌 uid authorize")
        assertEquals("CLOSED", stateOf(uid))
        assertEquals(null, get(uid).field("capturedAt"))
    }

    @Test
    fun `RD17-P1 — 미등록 uid close = 200 · 닫힘 표지(CLOSED · tombstone true) · 그 뒤 등록 409 CLOSED · pay 409 CLOSED · 표지 그대로`() {
        val uid = newUid()
        val c = close(uid)
        assertEquals(200, c.status, "미등록 close도 200(설계-3): $c")
        assertEquals("CLOSED", c.state)
        assertTrue(c.tombstone, "닫힘 표지: $c")
        val g = get(uid)
        assertEquals(200, g.status, "표지는 조회된다: $g")
        assertEquals("CLOSED", g.state)
        assertTrue(g.tombstone)

        assertReject(register(uid), 409, "CLOSED", "늦게 도착한 등록")
        assertReject(pay(uid), 409, "CLOSED", "표지 uid pay")
        assertReject(authorize(uid), 409, "CLOSED", "표지 uid authorize")
        val after = get(uid)
        assertEquals("CLOSED", after.state)
        assertTrue(after.tombstone)
        assertEquals(null, after.field("capturedAt"))
        assertEquals(200, close(uid).status, "표지 다시 close = 멱등 200")
    }

    // ── 주입(설계-3 · RD17-13) ────────────────────────────────────────────────────────────────

    @Test
    fun `RD17-13 — pay failRate 1 → 503 INJECTED_FAILURE · 원장 무변경 · 주입 풀면 같은 uid pay 처리(503 비기억)`() {
        val uid = newUid()
        registerOk(uid)
        faults(11, mapOf("pay" to mapOf("failRate" to 1.0)))
        repeat(2) { assertReject(pay(uid), 503, "INJECTED_FAILURE", "주입 실패 ${it + 1}") }
        assertEquals("REGISTERED", stateOf(uid), "처리 없이 503")
        assert2xx(register(newUid()), "주입은 op별 — register는 영향 없음")

        clearFaults()
        val r = pay(uid)
        assert2xx(r, "503을 기억하지 않는다")
        assertEquals("CAPTURED", r.state)
    }

    @Test
    fun `RD17-13 — register · close failRate 1 → 503 · 처리 없음 · 풀면 처리`() {
        val uid = newUid()
        faults(12, mapOf("register" to mapOf("failRate" to 1.0)))
        assertReject(register(uid), 503, "INJECTED_FAILURE", "주입 register")
        assertEquals(404, get(uid).status, "등록되지 않았다")
        clearFaults()
        registerOk(uid)
        assert2xx(pay(uid), "pay")

        faults(13, mapOf("close" to mapOf("failRate" to 1.0)))
        assertReject(close(uid), 503, "INJECTED_FAILURE", "주입 close")
        assertEquals("CAPTURED", stateOf(uid), "환불 없음")
        assertEquals(0, get(uid).refunds)
        clearFaults()
        assertEquals("REFUNDED", close(uid).state)
        assertEquals(1, get(uid).refunds)
    }

    @Test
    fun `결과 모름 — unknownRate 1 → 처리는 됨(GET으로 확인) · 응답은 unknownDelayMs 뒤 · 재호출 = 같은 결과(환불 1)`() {
        val uid = newUid()
        registerOk(uid)
        assert2xx(pay(uid), "pay")
        faults(14, mapOf("close" to mapOf("unknownRate" to 1.0, "unknownDelayMs" to 1500)))
        val started = System.nanoTime()
        val late = runCatching { post("/payments/$uid/close", null, Duration.ofMillis(300)) }
        assertTrue(
            late.exceptionOrNull() is HttpTimeoutException || Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(300),
            "결과 모름 = 클라이언트 타임아웃(300ms)보다 늦게 응답: $late",
        )
        assertEquals("REFUNDED", awaitState(uid, "REFUNDED"), "처리는 했다(GET은 주입 대상 아님)")
        clearFaults()
        val again = close(uid)
        assertEquals(200, again.status)
        assertEquals("REFUNDED", again.state)
        assertEquals(1, get(uid).refunds, "재호출이 다시 환불하지 않는다")
    }

    @Test
    fun `지연 주입 — delayMs 만큼 늦게 응답하고 처리한다`() {
        val uid = newUid()
        faults(15, mapOf("register" to mapOf("delayMs" to 400)))
        val t = System.nanoTime()
        registerOk(uid)
        assertTrue(Duration.ofNanos(System.nanoTime() - t) >= Duration.ofMillis(380), "delayMs 400")
        assertEquals("REGISTERED", stateOf(uid))
    }

    @Test
    fun `시드 재현 — 같은 seed · 같은 순서의 호출이면 주입 결과 순서가 같다(failRate 0_5)`() {
        val uids = (1..24).map { "seed-${it}-${UUID.randomUUID()}" }
        fun runSeq(): List<Int> {
            faults(4242, mapOf("register" to mapOf("failRate" to 0.5)))
            return uids.map { register(it).status }
        }
        val a = runSeq()
        clearFaults()
        assert2xx(reset(), "reset")
        val b = runSeq()
        assertEquals(a, b, "같은 seed → 같은 주입 순서")
        assertTrue(503 in a && a.any { it in 200..299 }, "failRate 0.5가 실제로 섞였다: $a")
    }

    // ── 하네스(/ledger · /admin/reset) ────────────────────────────────────────────────────────

    // 가정: /ledger 본문 모양은 부록 A가 정하지 않았다 — 등록된 uid와 상태 이름이 본문에 나오는 것만 본다.
    @Test
    fun `ledger — 등록 · 결제 · 환불한 uid가 상태와 함께 덤프된다`() {
        val a = newUid()
        val b = newUid()
        registerOk(a)
        registerOk(b)
        assert2xx(pay(b), "pay")
        assertEquals("REFUNDED", close(b).state)
        val l = ledger()
        assertEquals(200, l.status, "$l")
        assertTrue(l.body.contains(a) && l.body.contains(b), "두 uid: $l")
        assertTrue(l.body.contains("REGISTERED") && l.body.contains("REFUNDED"), "상태: $l")
    }

    @Test
    fun `admin reset — 원장 · 닫힘 표지를 비운다(같은 uid 다시 등록 가능)`() {
        val a = newUid()
        val t = newUid()
        registerOk(a)
        assertEquals(200, close(t).status) // 표지
        assert2xx(reset(), "reset")
        assertEquals(404, get(a).status, "원장 비움")
        assertEquals(404, get(t).status, "표지도 비움")
        assertFalse(ledger().body.contains(a), "ledger 비움")
        val again = register(t)
        assert2xx(again, "표지가 지워졌으니 등록된다")
        assertEquals("REGISTERED", again.state)
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────────────────────

    private fun awaitState(uid: String, want: String, timeout: Duration = Duration.ofSeconds(10)): String? {
        val end = System.nanoTime() + timeout.toNanos()
        var last: String? = null
        while (System.nanoTime() < end) {
            last = stateOf(uid)
            if (last == want) return last
            Thread.sleep(50)
        }
        fail("uid=$uid 상태가 $want 가 되지 않았다(마지막 $last)")
    }
}
