package com.jun.labs.seatreservation.adr018default

import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.OLD_DEFAULTS
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.service.HoldGroupProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.web.context.WebServerApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertTrue

/*
 * ADR-018 기본값 교체 — 재합의 6(명세 D7 · D8)의 계약 결함 재현 · 수정 검증.
 * ⑥ Flyway를 끈 구성 + 키 없음 + current + 기본 refund — 첫 거부에 묶음 끄기 안내(D7). 이 경로는 @PostConstruct 검사 순서를 정하지 않는다 —
 *   묶음 검사 · 보상 검사 어느 쪽이 먼저 거부해도 메시지에 끄기 안내가 있어야 한다(보상 메시지의 CURRENT_HINT).
 * ⑦ 키 없음 + 기본 conditional-extend + expiry-mode lazy — ADR-015 규칙 1 거부가 current를 안내할 때 묶음 끄기도 함께(D7).
 * ⑧ seat.hold-group.enabled=on — 바인딩 참이면 컨트롤러도 등록된다(D8).
 */
class Adr018ContractGapTest : BootTestBase() {

    private val prod = "spring.config.location" to "classpath:/application.yml"
    private val pg = "seat.payment.pg.base-url" to "http://127.0.0.1:1"
    private val sched = "seat.compensation.scheduler-enabled" to "false"

    private fun assertOneMessageHasAll(err: Throwable?, vararg keys: String) {
        val chain = generateSequence(err) { it.cause }.take(20).toList()
        assertTrue(
            chain.any { e -> e is IllegalStateException && keys.all { e.message.orEmpty().contains(it) } },
            "한 메시지에 ${keys.toList()}가 모두 있어야 한다: ${chain.map { it.message?.take(400) }}",
        )
    }

    @Test
    fun `⑥ Flyway 끔 + 키 없음 + current + 기본 refund — 첫 거부에 끄기 안내`() {
        val url = freshDbUrl()
        boot(url, OLD_DEFAULTS.mapValues { null } + mapOf(prod, pg, sched)).close() // 기본 구성으로 V16까지 적용 — Flyway를 끈 기동이 스키마 검증을 통과하게
        val err = bootFailure(url, mapOf(prod, pg, sched, "seat.confirm.mode" to "current", "spring.flyway.enabled" to "false"))
        assertStartupFailure(err, "Flyway 끔 + current + refund", listOf("seat.hold-group.enabled=false"))
        assertOneMessageHasAll(err, "seat.hold-group.enabled=false")
    }

    @Test
    fun `⑦ 키 없음 + 기본 확정 + expiry lazy — ADR-015 거부가 current와 함께 끄기도 안내`() {
        val err = bootFailure(freshDbUrl(), mapOf(prod, pg, sched, "seat.hold.expiry-mode" to "lazy"))
        assertStartupFailure(err, "기본 확정 + lazy", listOf("seat.confirm.mode=current"))
        assertOneMessageHasAll(err, "seat.confirm.mode=current", "seat.hold-group.enabled=false")
    }

    @Test
    fun `⑧ enabled=on — 바인딩 참이면 묶음 선점 2xx`() {
        val url = freshDbUrl()
        val props = OLD_DEFAULTS.mapValues { null } + mapOf(prod, pg, sched, "seat.hold-group.enabled" to "on")
        boot(url, props).use { ctx ->
            assertTrue(ctx.getBean(HoldGroupProperties::class.java).enabled, "on은 참으로 바인딩된다(전제)")
            val jdbc = jdbcFor(url)
            val scheduleId = jdbc.seedSchedule()
            val a = jdbc.seat(scheduleId)
            val b = jdbc.seat(scheduleId)
            val port = (ctx as WebServerApplicationContext).webServer.port
            val req = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/schedules/$scheduleId/hold-groups"))
                .timeout(Duration.ofSeconds(30))
                .header(USER_ID_HEADER, "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"seatIds":[$a,$b]}"""))
                .build()
            val r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
            assertTrue(r.statusCode() in 200..299, "enabled=on 묶음 선점: ${r.statusCode()} ${r.body()}")
        }
    }
}
