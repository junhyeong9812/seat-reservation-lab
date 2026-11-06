package com.jun.labs.seatreservation.adr015

import com.jun.labs.seatreservation.expiry.EXPIRY_MODE
import com.jun.labs.seatreservation.expiry.LAZY_CLEANUP_KEY
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.INJECT
import com.jun.labs.seatreservation.incremental.LOCS_TRG
import com.jun.labs.seatreservation.incremental.LOC_BASE
import com.jun.labs.seatreservation.incremental.LOC_V8
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.incremental.columnExists
import com.jun.labs.seatreservation.incremental.incrementalProps
import com.jun.labs.seatreservation.incremental.tableExists
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * ADR-015 §설계-5 기동 검사 계약(blind) — N7 · N11: `seat.confirm.mode ≠ current`인데 조합 밖이면(규칙 1~7) 기동을 거부하고 스키마를 바꾸지 않는다
 * (설정 규칙은 flyway.migrate() 앞 · DB 사실 규칙은 run). 허용 조합은 뜨고 V12 · V13(기본 위치) · V14(③ 선택 위치)가 생긴다.
 * 기동 = BootTestBase(실제 SpringApplication · 부팅마다 새 DB · 옛 동작 고정 키 OLD_DEFAULTS · Flyway 기본 위치).
 * 거부 판정 = assertStartupFailure(BindException 아님 · IllegalStateException · 메시지에 'confirm' — 메시지 문구는 ADR이 정하지 않아 가장 느슨한 키워드).
 * '스키마 무변경' = 새 DB면 flyway_schema_history가 생기지 않음 · 미리 마이그레이션한 DB면 이력 행 · product_seat 트리거 · V12 인덱스(유효)가 거부 전과 같음.
 */
class Adr015StartupContractTest : BootTestBase() {

    private val kw = listOf("confirm")
    private val locOpt = "$LOC_BASE,$LOC_CONFIRM_VERSION"
    private val windowKeys = mapOf("seat.confirm.payment-window" to "2m", "seat.confirm.extend-min-remaining" to "5s")

    /** 새 모드 넷의 '허용' 기본 키(기본 조합 · sweep · ③ 위치 · ④ window) */
    private val newModes: Map<String, Map<String, String?>> = mapOf(
        "conditional" to mapOf(MODE_KEY to "conditional"),
        "pessimistic" to mapOf(MODE_KEY to "pessimistic"),
        "optimistic" to mapOf(MODE_KEY to "optimistic", "spring.flyway.locations" to locOpt),
        "conditional-extend" to mapOf(MODE_KEY to "conditional-extend") + windowKeys,
    )

    private fun noSchema(url: String) = !jdbcFor(url).tableExists("flyway_schema_history")

    private fun JdbcTemplate.snapshot(): Map<String, Any?> = mapOf(
        "history" to queryForList("SELECT installed_rank, version, checksum, success FROM flyway_schema_history ORDER BY installed_rank"),
        "seat_triggers" to queryForList("SELECT tgname FROM pg_trigger WHERE tgrelid = 'product_seat'::regclass AND NOT tgisinternal ORDER BY 1", String::class.java),
        "v12" to queryForList("SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass(?)", Boolean::class.java, V12_INDEX),
    )

    private fun JdbcTemplate.v12Valid(): Boolean =
        queryForList("SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass(?)", Boolean::class.java, V12_INDEX).singleOrNull() == true

    private fun JdbcTemplate.seatTriggers(): Int =
        queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid = 'product_seat'::regclass AND NOT tgisinternal", Int::class.java)!!

    /** 새 DB에서 거부 + 스키마 무변경(flyway_schema_history 없음 — 거부가 마이그레이션 앞) */
    private fun rejectFresh(why: String, props: Map<String, String?>, failures: MutableList<String>) {
        val url = freshDbUrl()
        runCatching { assertStartupFailure(bootFailure(url, props), why, kw) }
            .onSuccess { if (!noSchema(url)) failures += "$why: 거부된 기동이 스키마를 바꿨다(flyway_schema_history 생김 — 거부가 마이그레이션 뒤)" }
            .onFailure { failures += "$why: ${it.message?.take(300)}" }
    }

    /** 미리 마이그레이션한 DB에서 거부 + 이력 · 트리거 · V12 무변경 */
    private fun rejectOn(url: String, why: String, props: Map<String, String?>, failures: MutableList<String>) {
        val before = jdbcFor(url).snapshot()
        runCatching { assertStartupFailure(bootFailure(url, props), why, kw) }
            .onSuccess { val after = jdbcFor(url).snapshot(); if (after != before) failures += "$why: 거부 뒤 스키마가 바뀌었다\n  전 $before\n  뒤 $after" }
            .onFailure { failures += "$why: ${it.message?.take(300)}" }
    }

    private fun bootsOrRecord(why: String, url: String, props: Map<String, String?>, failures: MutableList<String>): Boolean {
        val err = bootFailure(url, props)
        if (err != null) failures += "$why: 떠야 하는데 실패 ${causesOf(err).map { it.message?.take(200) }}"
        return err == null
    }

    private fun check(failures: List<String>) {
        if (failures.isNotEmpty()) fail("${failures.size}건:\n" + failures.joinToString("\n"))
    }

    // ── 허용 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `허용 — 손잡이 없음(기본 conditional) · current 명시는 뜨고 V12 유효 · V13 열이 기본 위치로 생긴다 · V14 트리거 없음`() {
        val failures = mutableListOf<String>()
        listOf<Map<String, String?>>(emptyMap(), mapOf(MODE_KEY to "current")).forEach { p ->
            val url = freshDbUrl()
            if (bootsOrRecord("mode $p", url, p, failures)) {
                val db = jdbcFor(url)
                if (!db.v12Valid()) failures += "mode $p: $V12_INDEX 없음/INVALID(V12 기본 위치)"
                if (!db.columnExists("seat_hold", "extended_at")) failures += "mode $p: seat_hold.extended_at 없음(V13 기본 위치)"
                if (db.seatTriggers() != 0) failures += "mode $p: product_seat 트리거가 있다(V14는 선택 위치)"
            }
        }
        check(failures)
    }

    @Test
    fun `허용 — 새 모드 넷은 기본 조합에서 뜬다(V12 유효 · ④ V13 · ③ V14 트리거가 상태 변경에만 version을 올린다)`() {
        val failures = mutableListOf<String>()
        newModes.forEach { (name, p) ->
            val url = freshDbUrl()
            if (!bootsOrRecord(name, url, p, failures)) return@forEach
            val db = jdbcFor(url)
            if (!db.v12Valid()) failures += "$name: V12 없음/INVALID"
            if (name == "conditional-extend" && !db.columnExists("seat_hold", "extended_at")) failures += "$name: V13 extended_at 없음"
            if (name == "optimistic") {
                if (db.seatTriggers() == 0) failures += "$name: V14 트리거 없음"
                else {
                    val s = db.seedSchedule()
                    val seat = db.seat(s, "AVAILABLE")
                    val v0 = db.queryForObject("SELECT version FROM product_seat WHERE id = ?", Long::class.java, seat)!!
                    db.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", seat)
                    val v1 = db.queryForObject("SELECT version FROM product_seat WHERE id = ?", Long::class.java, seat)!!
                    db.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", seat)
                    val v2 = db.queryForObject("SELECT version FROM product_seat WHERE id = ?", Long::class.java, seat)!!
                    if (v1 != v0 + 1 || v2 != v1) failures += "$name: V14 트리거 — 상태 변경 +1 · 같은 값은 그대로여야 한다(v0=$v0 v1=$v1 v2=$v2)"
                }
            }
        }
        check(failures)
    }

    @Test
    fun `알 수 없는 모드 값은 기동 실패(바인딩 — 조용히 current로 돌지 않는다)`() {
        val err = bootFailure(freshDbUrl(), mapOf(MODE_KEY to "conditionall"))
        assertTrue(err != null, "$MODE_KEY=conditionall 로 떴다 — 모르는 값이 조용히 current가 됐다")
        assertTrue(causesOf(err).any { it is BindException }, "바인딩 실패여야 한다: ${causesOf(err).map { it::class.simpleName + ": " + it.message?.take(150) }}")
    }

    // ── 규칙 1 · 2 ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `규칙 1 — 새 모드 + expiry-mode ≠ sweep은 마이그레이션 앞에서 거부(대조 — current는 뜬다)`() {
        val failures = mutableListOf<String>()
        val lazyModes = mapOf<String, Map<String, String?>>(
            "lazy" to mapOf(EXPIRY_MODE to "lazy", LAZY_CLEANUP_KEY to "schedule"),
            "lazy-sweep" to mapOf(EXPIRY_MODE to "lazy-sweep"),
            "derived" to mapOf(EXPIRY_MODE to "derived"),
        )
        lazyModes.forEach { (lm, lp) ->
            bootsOrRecord("대조 current + $lm", freshDbUrl(), lp + (MODE_KEY to "current"), failures)
            newModes.forEach { (name, p) -> rejectFresh("$name + expiry-mode=$lm", p + lp, failures) }
        }
        check(failures)
    }

    @Test
    fun `규칙 2 — 새 모드 + 기본 조합 밖(좌석 전략 · 매수 방식)은 마이그레이션 앞에서 거부`() {
        val failures = mutableListOf<String>()
        val combos = mapOf<String, Map<String, String?>>(
            "pessimistic + none" to mapOf("seat.hold.strategy" to "pessimistic", "seat.hold.limit-strategy" to "none"),
            "nowait + counter" to mapOf("seat.hold.limit-strategy" to "counter"),
            "nowait + counter-trigger" to mapOf("seat.hold.limit-strategy" to "counter-trigger", "spring.flyway.locations" to "$LOC_BASE,$LOC_V8"),
            "nowait + quota-lock" to mapOf("seat.hold.limit-strategy" to "quota-lock"),
        )
        combos.forEach { (cn, cp) ->
            newModes.forEach { (name, p) ->
                val locs = listOfNotNull(cp["spring.flyway.locations"], p["spring.flyway.locations"]).flatMap { it.split(",") }.distinct()
                val merged = p + cp + (if (locs.isEmpty()) emptyMap() else mapOf("spring.flyway.locations" to locs.joinToString(",")))
                rejectFresh("$name + $cn", merged, failures)
            }
        }
        check(failures)
    }

    // ── 규칙 3 · 4 · 5 ───────────────────────────────────────────────────────────────────────

    @Test
    fun `규칙 3 — loadtest delay는 loadtest 프로필에서만(대조 — loadtest 프로필이면 뜬다)`() {
        val failures = mutableListOf<String>()
        val delay = "seat.confirm.loadtest.delay" to "50ms"
        rejectFresh("current + delay(프로필 없음)", mapOf(MODE_KEY to "current", delay), failures)
        rejectFresh("conditional + delay(프로필 없음)", mapOf(MODE_KEY to "conditional", delay), failures)
        bootsOrRecord("대조 loadtest 프로필 + delay", freshDbUrl(), incrementalProps("trigger", LOCS_TRG, "spring.profiles.active" to "loadtest", delay), failures)
        check(failures)
    }

    @Test
    fun `규칙 4 — conditional-extend는 window 0 초과 · 0 ≤ min-remaining 미만 window`() {
        val failures = mutableListOf<String>()
        val base = mapOf<String, String?>(MODE_KEY to "conditional-extend")
        rejectFresh("window=0s", base + mapOf("seat.confirm.payment-window" to "0s", "seat.confirm.extend-min-remaining" to "0s"), failures)
        rejectFresh("min = window", base + mapOf("seat.confirm.payment-window" to "2m", "seat.confirm.extend-min-remaining" to "2m"), failures)
        rejectFresh("min > window", base + mapOf("seat.confirm.payment-window" to "2m", "seat.confirm.extend-min-remaining" to "3m"), failures)
        rejectFresh("min < 0", base + mapOf("seat.confirm.payment-window" to "2m", "seat.confirm.extend-min-remaining" to "-1s"), failures)
        bootsOrRecord("대조 min=0 · window=2m", freshDbUrl(), base + mapOf("seat.confirm.payment-window" to "2m", "seat.confirm.extend-min-remaining" to "0s"), failures)
        check(failures)
    }

    @Test
    fun `규칙 5 — 새 모드 + 측정 주입(inject-app)은 거부(대조 — current + 주입은 뜬다)`() {
        val failures = mutableListOf<String>()
        val inject = incrementalProps("trigger", LOCS_TRG, "$INJECT.enabled" to "true", "spring.profiles.active" to "loadtest")
        bootsOrRecord("대조 current + inject", freshDbUrl(), inject + (MODE_KEY to "current"), failures)
        newModes.forEach { (name, p) ->
            val locs = (LOCS_TRG.split(",") + listOfNotNull(p["spring.flyway.locations"]).flatMap { it.split(",") }).distinct().joinToString(",")
            rejectFresh("$name + inject-app", inject + p + ("spring.flyway.locations" to locs), failures)
        }
        check(failures)
    }

    // ── 규칙 6(③ V14 짝) ──────────────────────────────────────────────────────────────────────

    @Test
    fun `규칙 6 — optimistic은 V14 위치가 있어야 하고 · 그 위치가 있으면 낙관락 선점 금지(마이그레이션 앞)`() {
        val failures = mutableListOf<String>()
        val optStrategy = mapOf("seat.hold.strategy" to "optimistic", "seat.hold.limit-strategy" to "none")
        rejectFresh("ⓐ optimistic · 위치 없음", mapOf(MODE_KEY to "optimistic"), failures)
        bootsOrRecord("대조 낙관락 선점 · 위치 없음 · current", freshDbUrl(), optStrategy + (MODE_KEY to "current"), failures)
        rejectFresh("ⓑ 위치 + 낙관락 선점 · current", optStrategy + mapOf(MODE_KEY to "current", "spring.flyway.locations" to locOpt), failures)

        // ⓒ V1~V13 적용 DB에 위치 + 낙관락 선점 → 거부 · V14가 적용되지 않음(이력 · 트리거 무변경)
        val urlC = freshDbUrl()
        if (bootsOrRecord("ⓒ 준비 — 기본 위치 current", urlC, mapOf(MODE_KEY to "current"), failures)) {
            rejectOn(urlC, "ⓒ 위치 추가 + 낙관락 선점", optStrategy + mapOf(MODE_KEY to "current", "spring.flyway.locations" to locOpt), failures)
            if (jdbcFor(urlC).seatTriggers() != 0) failures += "ⓒ 거부된 기동이 V14 트리거를 만들었다"
        }

        // ⓓ V14 적용 DB: 되돌리기(모드 current · 위치 남김)는 뜬다 · 낙관락 선점으로 다시 띄우면 거부(DB 사실)
        val urlD = freshDbUrl()
        if (bootsOrRecord("ⓓ 준비 — optimistic", urlD, newModes.getValue("optimistic"), failures)) {
            bootsOrRecord("ⓓ 되돌리기 — current · 위치 남김", urlD, mapOf(MODE_KEY to "current", "spring.flyway.locations" to locOpt), failures)
            rejectOn(urlD, "ⓓ V14 있는 DB + 낙관락 선점", optStrategy + mapOf(MODE_KEY to "current", "spring.flyway.locations" to locOpt), failures)
        }
        check(failures)
    }

    // ── 규칙 7 · N11(V12 없음) · run 검사 ──────────────────────────────────────────────────────────

    @Test
    fun `규칙 7 — 새 모드 + target 12 미만(키 없음)은 마이그레이션 앞에서 거부`() {
        val failures = mutableListOf<String>()
        newModes.forEach { (name, p) -> rejectFresh("$name + target=11", p + ("spring.flyway.target" to "11"), failures) }
        check(failures)
    }

    @Test
    fun `N11 — 인덱스만 지운 DB에서 새 모드는 키 없으면 거부 · unique-index-optional=true면 뜬다 · current는 뜬다(WARN)`() {
        val failures = mutableListOf<String>()
        val url = freshDbUrl()
        if (bootsOrRecord("준비 — 기본 위치 current", url, mapOf(MODE_KEY to "current"), failures)) {
            val db = jdbcFor(url)
            if (!db.v12Valid()) failures += "준비 — V12가 없다(V12 미적용)"
            else {
                db.execute("DROP INDEX $V12_INDEX")
                rejectOn(url, "conditional · 인덱스 없음 · 키 없음", mapOf(MODE_KEY to "conditional"), failures)
                bootsOrRecord("conditional · 인덱스 없음 · 키 있음", url, mapOf(MODE_KEY to "conditional", "seat.confirm.unique-index-optional" to "true"), failures)
                bootsOrRecord("current · 인덱스 없음", url, mapOf(MODE_KEY to "current"), failures)
            }
        }
        check(failures)
    }

    @Test
    fun `run 검사 — V12 INVALID면 모든 모드 거부 · conditional-extend는 V13 없으면 거부`() {
        val failures = mutableListOf<String>()
        val url = freshDbUrl()
        if (bootsOrRecord("준비 — 기본 위치 current", url, mapOf(MODE_KEY to "current"), failures)) {
            val db = jdbcFor(url)
            if (!db.v12Valid()) failures += "준비 — V12가 없다(V12 미적용)"
            else {
                db.update("UPDATE pg_index SET indisvalid = false WHERE indexrelid = ?::regclass", V12_INDEX)
                rejectOn(url, "current · V12 INVALID", mapOf(MODE_KEY to "current"), failures)
                rejectOn(url, "conditional · V12 INVALID", mapOf(MODE_KEY to "conditional"), failures)
            }
        }
        runCatching { assertStartupFailure(bootFailure(freshDbUrl(), newModes.getValue("conditional-extend") + ("spring.flyway.target" to "12")), "conditional-extend + target=12(V13 없음)", kw) }
            .onFailure { failures += "conditional-extend + target=12: ${it.message?.take(300)}" }
        check(failures)
    }
}
