package com.jun.labs.seatreservation.adr018default

import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.OLD_DEFAULTS
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.service.HoldGroupMode
import com.jun.labs.seatreservation.service.HoldGroupProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.web.context.WebServerApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/*
 * ADR-018 기본값 교체(기본 enabled=true) 실제 기동 계약(blind — 명세만 보고 씀).
 * 명세: docs/plans/2026-11-21/adr-018-default-switch/requirement-spec.md(§2 D2 · D3 · §5 3~7).
 *
 * 'test' 프로필(config/application.properties → application-test.yml)은 구현 뒤 seat.hold-group.enabled=false를 준다(명세 Q/A 2).
 * '키 없음 = 기본값' 시나리오가 그 값을 타지 않게, Adr017DefaultModeBootTest처럼 spring.config.location을 main의 application.yml 하나로 바꿔
 * test 프로필을 뺀다(config/application.properties의 include도 함께 빠진다). 그래서 이 파일은 seat.hold-group.enabled를 6번 말고는 주지 않는다.
 * 테스트 프로필이 주던 보상 none도 빠지므로 보상 모드는 기본 refund — current 시나리오(3 · 6)는 ADR-017 거부를 피하려 보상 none을 명시한다.
 * 기동 = BootTestBase(실제 SpringApplication · 부팅마다 새 DB). PG 주소는 닿지 않는 값(기동은 PG를 부르지 않는다 — Adr017DefaultModeBootTest와 같다).
 * 거부 단언은 메시지에 `seat.hold-group.enabled=false` 문자열이 있는지만 본다(D2 안내 — 다른 문구에 기대지 않는다).
 */
class Adr018DefaultBootTest : BootTestBase() {

    /** 'test' 프로필을 빼고 main application.yml만 읽는다 */
    private val prodConfig = "spring.config.location" to "classpath:/application.yml"
    private val pg = "seat.payment.pg.base-url" to "http://127.0.0.1:1"
    private val sched = "seat.compensation.scheduler-enabled" to "false"
    private val current = "seat.confirm.mode" to "current"
    private val compNone = "seat.compensation.mode" to "none"
    private val optOut = "seat.hold-group.enabled=false"

    private fun rejects(why: String, props: Map<String, String?>) {
        val err = bootFailure(freshDbUrl(), props)
        assertStartupFailure(err, why, listOf(optOut))
    }

    @Test
    fun `3 키 없음 + 확정 current(보상 none) — 기동 거부 · enabled=false 안내`() {
        rejects("키 없음 + current + none", mapOf(prodConfig, sched, current, compNone))
    }

    @Test
    fun `4 키 없음 + 보상 auth-capture — 기동 거부 · enabled=false 안내`() {
        rejects("키 없음 + auth-capture", mapOf(prodConfig, pg, sched, "seat.compensation.mode" to "auth-capture"))
    }

    @Test
    fun `4 키 없음 + 보상 substitute — 기동 거부 · enabled=false 안내`() {
        rejects("키 없음 + substitute", mapOf(prodConfig, pg, sched, "seat.compensation.mode" to "substitute"))
    }

    @Test
    fun `5 키 없음 + 기본 구성(conditional-extend · refund + PG 주소) — 뜬다 · 묶음 선점 엔드포인트가 열린다`() {
        val url = freshDbUrl()
        // 업무 앱 기본값 그대로(옛 동작 고정 키를 모두 빼 application.yml 값으로) — 기본 구성 + PG 주소만 준다
        val props = OLD_DEFAULTS.mapValues { null } + mapOf(prodConfig, pg, sched)
        boot(url, props).use { ctx ->
            assertFalse("test" in ctx.environment.activeProfiles, "test 프로필이 빠져야 운영 기본값을 본다: ${ctx.environment.activeProfiles.toList()}")
            val bound = ctx.getBean(HoldGroupProperties::class.java)
            assertTrue(bound.enabled, "키 없이 띄운 앱의 enabled")
            assertEquals(HoldGroupMode.SPLIT, bound.mode, "키 없이 띄운 앱의 mode")

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
            assertNotEquals(404, r.statusCode(), "묶음 선점 엔드포인트가 매핑돼야 한다: ${r.statusCode()} ${r.body()}")
            assertTrue(r.statusCode() in 200..299, "유효한 좌석 2개의 묶음 선점은 성공해야 한다: ${r.statusCode()} ${r.body()}")
        }
    }

    @Test
    fun `6 enabled=false 명시 + 확정 current(보상 none) — 뜬다`() {
        boot(freshDbUrl(), mapOf(prodConfig, sched, current, compNone, "seat.hold-group.enabled" to "false")).use { ctx ->
            assertFalse("test" in ctx.environment.activeProfiles, "test 프로필이 빠져야 한다: ${ctx.environment.activeProfiles.toList()}")
            assertFalse(ctx.getBean(HoldGroupProperties::class.java).enabled, "명시한 false")
        }
    }

    @Test
    fun `7 키 없음 + flyway target 15(V16 앞) — 기동 거부 · 스키마 무변경`() {
        val url = freshDbUrl()
        val jdbc = jdbcFor(url)
        val tablesBefore = publicTables(jdbc)
        assertTrue(tablesBefore.isEmpty(), "전제: 새 DB는 표가 없다: $tablesBefore")

        val err = bootFailure(url, mapOf(prodConfig, pg, sched, "spring.flyway.target" to "15"))
        assertStartupFailure(err, "키 없음 + target 15", listOf(optOut))

        val history = jdbc.queryForObject("SELECT to_regclass('public.flyway_schema_history')::text", String::class.java)
        if (history != null) {
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long::class.java), "Flyway 이력에 행이 없어야 한다(migrate 앞 거부)")
        }
        assertEquals(tablesBefore, publicTables(jdbc).filter { it != "flyway_schema_history" }, "거부된 기동은 스키마를 바꾸지 않는다")
    }

    private fun publicTables(jdbc: org.springframework.jdbc.core.JdbcTemplate): List<String> =
        jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename", String::class.java)
}
