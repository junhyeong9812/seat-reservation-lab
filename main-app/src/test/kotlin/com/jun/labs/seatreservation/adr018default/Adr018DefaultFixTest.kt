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
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * ADR-018 기본값 교체 — 리뷰 수정 검증(fix verification — blind 계약 테스트 Adr018DefaultBootTest와 별개).
 * ① 설정 파일에 seat.hold-group 키가 아예 없는 기동(설정 위치 대체)도 코드 기본값(켬)대로 묶음 API가 열린다 — 검사 · Guard와 엔드포인트가 갈리지 않는다.
 * ② V16이 이미 이력에 있는데 hold_group 표가 없는 DB(이번 migrate가 V16을 적용하지 않음)는 migrate 앞에서 거부한다 — 이력 행 수 그대로.
 * ③ 키 없음 + 확정 current + 기본 보상 refund + PG 주소 — 첫 거부에 묶음 끄기 안내와 보상 none 안내가 함께 나온다 · 스키마 무변경.
 */
class Adr018DefaultFixTest : BootTestBase() {

    private val pg = "seat.payment.pg.base-url" to "http://127.0.0.1:1"
    private val sched = "seat.compensation.scheduler-enabled" to "false"

    /** main application.yml에서 seat.hold-group 블록만 뺀 사본 — 다른 기본값은 그대로 */
    private fun ymlWithoutHoldGroup(): String {
        val lines = javaClass.getResource("/application.yml")!!.readText().lines()
        val start = lines.indexOfFirst { it == "  hold-group:" }
        assertTrue(start > 0, "application.yml에 seat.hold-group 블록이 있어야 한다(전제)")
        val end = (start + 1 until lines.size).first { !lines[it].startsWith("    ") }
        val file = Files.createTempFile("app-no-hold-group", ".yml")
        Files.writeString(file, (lines.subList(0, start) + lines.subList(end, lines.size)).joinToString("\n"))
        assertFalse("hold-group:" in Files.readString(file), "사본에 hold-group 키가 없어야 한다")
        return "file:$file"
    }

    @Test
    fun `① 설정에 hold-group 키가 없으면 — 기본 켬 · 묶음 선점 2xx`() {
        val url = freshDbUrl()
        val props = OLD_DEFAULTS.mapValues { null } + mapOf("spring.config.location" to ymlWithoutHoldGroup(), pg, sched)
        boot(url, props).use { ctx ->
            assertFalse(ctx.environment.containsProperty("seat.hold-group.enabled"), "키가 환경에 없어야 한다")
            assertTrue(ctx.getBean(HoldGroupProperties::class.java).enabled, "코드 기본값 켬")
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
            assertTrue(r.statusCode() in 200..299, "키 없는 기동의 묶음 선점: ${r.statusCode()} ${r.body()}")
        }
    }

    @Test
    fun `② V16 이력은 있는데 hold_group 표가 없으면 — migrate 앞에서 거부 · 이력 그대로`() {
        val url = freshDbUrl()
        val prod = OLD_DEFAULTS.mapValues { null } + mapOf("spring.config.location" to "classpath:/application.yml", pg, sched)
        boot(url, prod).close() // 기본 구성으로 V16까지 적용
        val jdbc = jdbcFor(url)
        jdbc.execute("DROP TABLE hold_group CASCADE")
        val historyBefore = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long::class.java)

        val err = bootFailure(url, prod)
        assertStartupFailure(err, "V16 이력 + 표 없음", listOf("마이그레이션 앞 DB", "seat.hold-group.enabled=false"))
        assertOneMessageHasAll(err, "마이그레이션 앞 DB", "seat.hold-group.enabled=false")
        assertEquals(historyBefore, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long::class.java), "거부된 기동은 이력을 바꾸지 않는다")
    }

    @Test
    fun `③ 키 없음 + current + 기본 refund — 첫 거부에 두 안내 · 스키마 무변경`() {
        val url = freshDbUrl()
        val props = mapOf("spring.config.location" to "classpath:/application.yml", pg, sched, "seat.confirm.mode" to "current")
        val err = bootFailure(url, props)
        assertStartupFailure(err, "키 없음 + current + refund", listOf("seat.hold-group.enabled=false", "seat.compensation.mode=none"))
        assertOneMessageHasAll(err, "seat.hold-group.enabled=false", "seat.compensation.mode=none")
        val jdbc = jdbcFor(url)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'", Long::class.java), "거부된 기동은 스키마를 바꾸지 않는다")
    }

    /** assertStartupFailure는 키워드 중 하나만 본다 — 한 기동 검사 메시지에 모두 있는지 따로 본다 */
    private fun assertOneMessageHasAll(err: Throwable?, vararg keys: String) {
        val chain = generateSequence(err) { it.cause }.take(20).toList()
        assertTrue(
            chain.any { e -> e is IllegalStateException && keys.all { e.message.orEmpty().contains(it) } },
            "한 메시지에 ${keys.toList()}가 모두 있어야 한다: ${chain.map { it.message?.take(300) }}",
        )
    }
}
