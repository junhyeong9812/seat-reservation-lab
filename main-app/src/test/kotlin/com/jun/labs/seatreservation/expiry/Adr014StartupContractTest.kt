package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.INJECT
import com.jun.labs.seatreservation.incremental.LOCS_TRG
import com.jun.labs.seatreservation.incremental.LOC_BASE
import com.jun.labs.seatreservation.incremental.LOC_V8
import com.jun.labs.seatreservation.incremental.OLD_DEFAULTS
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.incremental.incrementalProps
import com.jun.labs.seatreservation.incremental.tableExists
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * ADR-014 §설계-6 손잡이 · 기동 검사 계약(blind) — N7: 기본 조합(pessimistic-nowait + counter-upsert-precheck) 밖에서 `expiry-mode ≠ sweep`이면
 * 기동을 거부하고 스키마를 바꾸지 않는다(설정 검사 = 마이그레이션 앞 · checkConfig) · Q13: inject-app과 함께 켜면 거부 · 손잡이를 주지 않으면 지금과 같다.
 * 기동은 BootTestBase(실제 SpringApplication · 부팅마다 새 DB · 옛 동작 고정 키 OLD_DEFAULTS). 거부 판정은 assertStartupFailure(IllegalStateException ·
 * 메시지에 키워드 — E1 `expiry-mode`). '스키마 무변경' = 새 DB에 flyway_schema_history가 생기지 않았다(V1조차 적용되지 않음).
 */
class Adr014StartupContractTest : BootTestBase() {

    private val modes = listOf("lazy", "lazy-sweep", "derived")

    /** 기본 조합이 아닌 조합(지금 기동 검사로는 sweep에서 뜨는 것만 — 대조 테스트가 확인한다). 값 = 덮을 키. */
    private val nonDefaultCombos: Map<String, Map<String, String?>> = mapOf(
        "pessimistic + none" to mapOf("seat.hold.strategy" to "pessimistic", "seat.hold.limit-strategy" to "none"),
        "conditional-update + none" to mapOf("seat.hold.strategy" to "conditional-update", "seat.hold.limit-strategy" to "none"),
        "nowait + none" to mapOf("seat.hold.limit-strategy" to "none"),
        "nowait + counter" to mapOf("seat.hold.limit-strategy" to "counter"),
        "nowait + counter-upsert" to mapOf("seat.hold.limit-strategy" to "counter-upsert"),
        "nowait + counter-trigger" to mapOf(
            "seat.hold.limit-strategy" to "counter-trigger",
            "spring.flyway.locations" to "$LOC_BASE,$LOC_V8",
        ),
        "nowait + quota-lock" to mapOf("seat.hold.limit-strategy" to "quota-lock"),
    )

    private fun noSchema(url: String): Boolean = !jdbcFor(url).tableExists("flyway_schema_history")

    @Test
    fun `대조 — 기본 조합이 아닌 조합도 손잡이 없음 · sweep이면 뜬다(아래 거부가 expiry-mode 때문임을 보인다)`() {
        val failures = mutableListOf<String>()
        nonDefaultCombos.forEach { (name, props) ->
            listOf(null, "sweep").forEach { mode ->
                val err = bootFailure(freshDbUrl(), props + (EXPIRY_MODE to mode))
                if (err != null) failures += "$name + expiry-mode=$mode: ${causesOf(err).map { it.message?.take(200) }}"
            }
        }
        if (failures.isNotEmpty()) fail("떠야 할 조합이 뜨지 않았다:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `N7 — 기본 조합이 아닌 조합에서 lazy · lazy-sweep · derived는 마이그레이션 앞에서 거부한다(메시지에 expiry-mode · 스키마 무변경)`() {
        val failures = mutableListOf<String>()
        modes.forEach { mode ->
            nonDefaultCombos.forEach { (name, props) ->
                val url = freshDbUrl()
                val why = "$name + expiry-mode=$mode"
                val r = runCatching { assertStartupFailure(bootFailure(url, props + (EXPIRY_MODE to mode)), why, listOf("expiry-mode")) }
                r.exceptionOrNull()?.let { failures += "$why: ${it.message?.take(300)}" }
                if (r.isSuccess && !noSchema(url)) failures += "$why: 거부된 기동이 스키마를 바꿨다(flyway_schema_history가 생겼다 — 거부가 마이그레이션 뒤)"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size}건:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `Q13 — expiry-mode ≠ sweep + 측정 주입(inject-app)은 거부한다 · sweep + 주입은 뜬다(대조)`() {
        val inject = incrementalProps("trigger", LOCS_TRG, "$INJECT.enabled" to "true", "spring.profiles.active" to "loadtest")
        boot(freshDbUrl(), inject + (EXPIRY_MODE to "sweep")).use { }
        val failures = mutableListOf<String>()
        modes.forEach { mode ->
            val url = freshDbUrl()
            runCatching { assertStartupFailure(bootFailure(url, inject + (EXPIRY_MODE to mode)), "inject-app + $mode", listOf("expiry-mode", "inject")) }
                .onSuccess { if (!noSchema(url)) failures += "$mode: 거부된 기동이 스키마를 바꿨다" }
                .onFailure { failures += "$mode: ${it.message?.take(300)}" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `기본 조합 + lazy · lazy-sweep · derived는 뜬다(옛 동작 고정 키 · 업무 앱 기본값 둘 다)`() {
        val failures = mutableListOf<String>()
        modes.forEach { mode ->
            listOf("옛 동작 고정" to emptyMap<String, String?>(), "업무 앱 기본값" to OLD_DEFAULTS.mapValues { null }).forEach { (name, base) ->
                bootFailure(freshDbUrl(), base + (EXPIRY_MODE to mode))?.let { failures += "$name + $mode: ${causesOf(it).map { e -> e.message?.take(200) }}" }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `알 수 없는 값은 기동 실패(바인딩 — 조용히 A로 돌지 않는다)`() {
        val err = bootFailure(freshDbUrl(), mapOf(EXPIRY_MODE to "lazyy"))
        assertTrue(err != null, "expiry-mode=lazyy 로 떴다 — 손잡이를 모르는 값으로 조용히 A가 됐다")
        assertTrue(causesOf(err).any { it is BindException }, "바인딩 실패여야 한다: ${causesOf(err).map { it::class.simpleName + ": " + it.message?.take(150) }}")
    }
}
