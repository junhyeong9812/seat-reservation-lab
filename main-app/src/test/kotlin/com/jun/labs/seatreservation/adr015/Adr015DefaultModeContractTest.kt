package com.jun.labs.seatreservation.adr015

import com.jun.labs.seatreservation.expiry.EXPIRY_MODE
import com.jun.labs.seatreservation.expiry.LAZY_CLEANUP_KEY
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.INJECT
import com.jun.labs.seatreservation.incremental.LOCS_TRG
import com.jun.labs.seatreservation.incremental.incrementalProps
import com.jun.labs.seatreservation.incremental.OLD_DEFAULTS
import com.jun.labs.seatreservation.incremental.tableExists
import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ADR-015 §결정(2026-11-06) 기본값 교체 계약 — `seat.confirm.mode` 기본 = conditional · 정책 (나) 명시 강제(자동 후퇴 없음):
 * 키를 주지 않은 기동은 conditional로 뜨고, 새 모드의 지원 범위 밖(규칙 1 B·C·D 만료 · 규칙 2 기본 밖 조합)은 `seat.confirm.mode=current`를 명시하라고
 * 거부한다(조용히 current로 돌지 않는다 · 마이그레이션 앞 — 스키마 무변경). 같은 설정에 current를 명시하면 뜬다.
 * 기동 = BootTestBase(실제 SpringApplication · 부팅마다 새 DB · 옛 동작 고정 키 OLD_DEFAULTS — 확정 키는 없다).
 */
class Adr015DefaultModeContractTest : BootTestBase() {

    private val appDefaults: Map<String, String?> = OLD_DEFAULTS.mapValues { null }

    private fun noSchema(url: String) = !jdbcFor(url).tableExists("flyway_schema_history")

    /** 거부가 확정 규칙(IllegalStateException)이고 메시지가 `seat.confirm.mode=current`와 [ruleKey]를 함께 짚는다 · 스키마 무변경 */
    private fun assertRejectedNamingCurrent(url: String, props: Map<String, String?>, why: String, ruleKey: String) {
        val err = bootFailure(url, props)
        assertTrue(err != null, "떴다 — $why: 기본 확정 모드가 범위 밖에서 조용히 떴다(자동 후퇴 없음이어야 한다)")
        val chain = causesOf(err)
        val dump = chain.map { "${it::class.simpleName}: ${it.message?.take(300)}" }
        assertTrue(chain.none { it is BindException }, "바인딩 실패 — 기동 검사가 막은 것이 아니다 · $why: $dump")
        assertTrue(
            chain.any { it is IllegalStateException && it.message.orEmpty().let { m -> "seat.confirm.mode=current" in m && "seat.confirm.mode=conditional" in m && ruleKey in m } },
            "확정 규칙 거부(메시지에 seat.confirm.mode=conditional · seat.confirm.mode=current · $ruleKey)가 아니다 · $why: $dump",
        )
        assertTrue(noSchema(url), "$why: 거부된 기동이 스키마를 바꿨다(flyway_schema_history 생김 — 거부가 마이그레이션 뒤)")
    }

    @Test
    fun `기본값 — 코드 · application_yml 모두 conditional`() {
        assertEquals(ConfirmMode.CONDITIONAL, SeatConfirmProperties().mode, "코드 기본값")
        val sources = YamlPropertySourceLoader().load("application.yml", ClassPathResource("application.yml"))
        val bound = Binder(ConfigurationPropertySources.from(sources)).bind("seat.confirm", SeatConfirmProperties::class.java).get()
        assertEquals(ConfirmMode.CONDITIONAL, bound.mode, "application.yml")
    }

    @Test
    fun `확정 키 없음 — 기본 조합 · sweep으로 뜨고 바인딩된 모드는 conditional(옛 동작 고정 키 · 업무 앱 기본값 둘 다)`() {
        listOf("옛 동작 고정" to emptyMap(), "업무 앱 기본값" to appDefaults).forEach { (name, base) ->
            boot(freshDbUrl(), base).use { ctx ->
                assertEquals(ConfirmMode.CONDITIONAL, ctx.getBean(SeatConfirmProperties::class.java).mode, "$name: 확정 모드")
            }
        }
    }

    @Test
    fun `규칙 1 — expiry-mode=lazy(B) + 확정 키 없음은 current 명시를 안내하며 거부 · current를 명시하면 뜬다`() {
        val lazy = mapOf<String, String?>(EXPIRY_MODE to "lazy", LAZY_CLEANUP_KEY to "schedule")
        assertRejectedNamingCurrent(freshDbUrl(), lazy, "lazy + 키 없음", "seat.hold.expiry-mode=sweep")
        boot(freshDbUrl(), lazy + (MODE_KEY to "current")).use { ctx ->
            assertEquals(ConfirmMode.CURRENT, ctx.getBean(SeatConfirmProperties::class.java).mode, "lazy + current: 확정 모드")
        }
    }

    @Test
    fun `규칙 2 — limit-strategy=none(기본 밖 조합) + 확정 키 없음은 current 명시를 안내하며 거부 · current를 명시하면 뜬다`() {
        val none = mapOf<String, String?>("seat.hold.limit-strategy" to "none")
        assertRejectedNamingCurrent(freshDbUrl(), none, "none + 키 없음", "seat.hold.limit-strategy=counter-upsert-precheck")
        boot(freshDbUrl(), none + (MODE_KEY to "current")).use { ctx ->
            assertEquals(ConfirmMode.CURRENT, ctx.getBean(SeatConfirmProperties::class.java).mode, "none + current: 확정 모드")
        }
    }

    @Test
    fun `규칙 5 — 측정 주입(inject-app) + 확정 키 없음은 current 명시를 안내하며 거부 · current를 명시하면 뜬다`() {
        val inject = incrementalProps("trigger", LOCS_TRG, "$INJECT.enabled" to "true", "spring.profiles.active" to "loadtest")
        assertRejectedNamingCurrent(freshDbUrl(), inject, "inject-app + 키 없음", "seat.hold.loadtest.inject-app.enabled")
        boot(freshDbUrl(), inject + (MODE_KEY to "current")).use { ctx ->
            assertEquals(ConfirmMode.CURRENT, ctx.getBean(SeatConfirmProperties::class.java).mode, "inject-app + current: 확정 모드")
        }
    }

    @Test
    fun `규칙 7 — Flyway target 12 이하(V13 앞) + 확정 키 없음은 current 명시를 안내하며 마이그레이션 앞에서 거부 · current를 명시하면 뜬다`() {
        listOf("7", "12").forEach { target ->   // 7 = V12 앞 마지막 기본 위치 버전(T12) · 12 = V13 앞
            val pinned = mapOf<String, String?>("spring.flyway.target" to target)
            assertRejectedNamingCurrent(freshDbUrl(), pinned, "target=$target + 키 없음", "spring.flyway.target")
        }
        boot(freshDbUrl(), mapOf<String, String?>("spring.flyway.target" to "7", MODE_KEY to "current")).use { ctx ->
            assertEquals(ConfirmMode.CURRENT, ctx.getBean(SeatConfirmProperties::class.java).mode, "target=7 + current: 확정 모드")
        }
    }
}
