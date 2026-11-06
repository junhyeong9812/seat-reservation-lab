package com.jun.labs.seatreservation.incremental

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.CoreErrorCode
import org.flywaydb.core.api.exception.FlywayValidateException
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * ADR-015 §설계-4 'V12 · V13 × 옛 방식 DB — ADR-013 전환 절차에 outOfOrder 1회' · [구현 검증] #6 — 앱 쪽 계약 테스트(ops/online-v9/test-local.sh 새 경우와 같은 내용).
 * 옛 방식(ADR-009 C — K2) 앱은 spring.flyway.locations = 기본 위치만 → 이 SHA로 띄우면 V1~V7 위에 V12 · V13이 먼저 적용된다.
 * 그 DB에 ADR-013 전환(V9 운영 중 적용 → 앱 첫 기동 V10)을 하면 V9 · V10이 '적용된 V13보다 낮은 미적용'이 된다.
 *  ⓐ V9 기록(운영 중 적용판 기록 ① 자리 — 여기서는 Flyway target 9 · 실제 실행)은 outOfOrder 1회로만 된다(없으면 검증 실패 · 이력 무변경)
 *  ⓑ 이어서 앱을 전체 위치 · out-of-order 없이 띄우면 기동 거부 — 원인 = flyway.migrate()의 검증 실패(FlywayValidateException) · 스키마 무변경(V10 객체 없음 · 이력 · 표 목록 그대로)
 *     관문(HoldStrategyMigrationGate)의 validateWithResult는 이 경우를 PENDING 코드(RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED)로 본다 → 관문은 거부하지 않고, migrate가 적용 전에 거부한다
 *  ⓒ spring.flyway.out-of-order=true로 한 번 띄우면 V10 한 개만 적용
 *  ⓓ 그다음 out-of-order 없이 다시 띄워도 기동한다(순서 밖으로 적용된 V9 · V10 이력을 검증이 받아들인다) · 적용 0
 *  대조: 옛 SHA 앱 DB(V1~V7 — target 7)는 플래그 없이 V9 · V10(· V12 · V13)이 순서대로 적용된다
 */
class Adr015OutOfOrderContractTest : BootTestBase() {

    private val holdGate = "com.jun.labs.seatreservation.service.impl.hold.HoldStrategyMigrationGate"
    private val v10Tables = listOf("counter_safety_run", "counter_app_inject", "counter_inc_gap")

    private fun flyway(url: String, vararg locations: String, target: String? = null, outOfOrder: Boolean = false): Flyway =
        Flyway.configure().dataSource(url, dbUser, dbPassword).locations(*locations).outOfOrder(outOfOrder)
            .apply { if (target != null) target(target) }.load()

    private fun JdbcTemplate.history(): String =
        queryForObject("SELECT string_agg(version || ':' || success, ' ' ORDER BY installed_rank) FROM flyway_schema_history", String::class.java)!!

    private fun JdbcTemplate.v10Objects(): Int = queryForObject(
        "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relname = ANY(?)", Int::class.java, v10Tables.toTypedArray(),
    )!!

    /** 스키마 지문 — public의 모든 관계(표 · 인덱스 · 시퀀스) 이름 · 종류 + 함수 · 트리거 이름 */
    private fun JdbcTemplate.schemaPrint(): String = queryForObject(
        """SELECT (SELECT string_agg(relname || '/' || relkind::text, ',' ORDER BY relname) FROM pg_class WHERE relnamespace = 'public'::regnamespace)
             || '|' || coalesce((SELECT string_agg(proname, ',' ORDER BY proname) FROM pg_proc WHERE pronamespace = 'public'::regnamespace), '')
             || '|' || coalesce((SELECT string_agg(tgname, ',' ORDER BY tgname) FROM pg_trigger WHERE NOT tgisinternal), '')""",
        String::class.java,
    )!!

    private fun Throwable.chain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    /** 옛 방식 K2(이 SHA · 기본 위치만) → V1~V7 + V12 · V13 → ADR-013 전환 V9 기록(outOfOrder 1회) */
    private fun oldStyleDbWithV9(url: String, db: JdbcTemplate) {
        flyway(url, LOC_BASE).migrate()
        assertEquals("1:true 2:true 3:true 5:true 6:true 7:true 12:true 13:true", db.history(), "전제: 기본 위치만 = V1~V7 + V12 · V13")
        val h0 = db.history()
        val e = runCatching { flyway(url, LOC_BASE, LOC_V9_TRG, target = "9").migrate() }.exceptionOrNull()
        assertTrue(e is FlywayValidateException && e.message.orEmpty().contains("Detected resolved migration not applied to database: 9."),
            "ⓐ outOfOrder 없이 V9 기록 → 검증 실패여야 한다(V9 < 적용된 V13): $e")
        assertEquals(h0, db.history(), "ⓐ 검증 실패한 V9 기록은 이력을 바꾸지 않는다")
        assertEquals(listOf("9"), flyway(url, LOC_BASE, LOC_V9_TRG, target = "9", outOfOrder = true).migrate().migrations.map { it.version })
        assertEquals("$h0 9:true", db.history(), "ⓐ outOfOrder 1회 → V9가 V13 뒤 순서로 기록된다")
    }

    @Test
    fun `V12 V13가 먼저 적용된 옛 방식 DB — 플래그 없이 앱 첫 기동은 migrate 검증이 스키마 변경 전에 거부 · out-of-order 1회로 V10만 · 그다음 플래그 없이 다시 기동`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        oldStyleDbWithV9(url, db)
        val h1 = db.history()
        val print1 = db.schemaPrint()

        // 관문이 보는 검증 결과(같은 위치 · out-of-order 없음): 순서 밖 미적용 V10의 오류 코드 = PENDING 묶음(관문이 'Flyway 거부'로 보지 않는다)
        val codes = flyway(url, *LOCS_TRG.split(",").toTypedArray()).validateWithResult().invalidMigrations.orEmpty()
            .map { it.version to it.errorDetails?.errorCode }
        assertEquals(listOf("10" to CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED), codes, "[구현 검증] #6 — 관문 validateWithResult가 보는 오류 코드")

        // ⓑ 전체 위치 · out-of-order 없음 → 기동 거부 · 원인 = migrate 검증 · 스키마 무변경
        val failure = assertNotNull(bootFailure(url, incrementalProps("trigger", LOCS_TRG)), "ⓑ out-of-order 없이 띄우면 거부돼야 한다")
        val validate = failure.chain().filterIsInstance<FlywayValidateException>().firstOrNull()
        assertNotNull(validate, "ⓑ 거부 원인은 Flyway 검증 실패여야 한다: ${failure.chain().map { "${it.javaClass.simpleName}: ${it.message?.take(200)}" }}")
        assertTrue(validate.message.orEmpty().contains("Detected resolved migration not applied to database: 10."), "ⓑ 검증 실패 내용: ${validate.message}")
        assertTrue(validate.stackTrace.any { it.className == Flyway::class.java.name && it.methodName == "migrate" } &&
            validate.stackTrace.any { it.className.startsWith(holdGate) },
            "ⓑ 거부 자리 = 관문(HoldStrategyMigrationGate) 안의 flyway.migrate() 검증: ${validate.stackTrace.take(30).joinToString { "${it.className}.${it.methodName}" }}")
        assertEquals(0, db.v10Objects(), "ⓑ V10 객체 없음")
        assertEquals(h1, db.history(), "ⓑ 이력 무변경")
        assertEquals(print1, db.schemaPrint(), "ⓑ 스키마 무변경(표 · 인덱스 · 함수 · 트리거 목록)")

        // ⓒ out-of-order 1회 → V10 한 개만
        boot(url, incrementalProps("trigger", LOCS_TRG, "spring.flyway.out-of-order" to "true")).close()
        assertEquals("$h1 10:true", db.history(), "ⓒ out-of-order 1회 → V10 한 개만 적용")
        assertEquals(v10Tables.size, db.v10Objects(), "ⓒ V10 객체 3")

        // ⓓ 그다음 out-of-order 없이 다시 → 기동 · 적용 0(순서 밖 V9 · V10 이력을 받아들인다)
        val failAgain = bootFailure(url, incrementalProps("trigger", LOCS_TRG))
        assertNull(failAgain, "ⓓ 플래그 없이 다시 기동해야 한다: ${failAgain?.chain()?.map { "${it.javaClass.simpleName}: ${it.message?.take(200)}" }}")
        assertEquals("$h1 10:true", db.history(), "ⓓ 적용 0 · 이력 그대로")
    }

    @Test
    fun `대조 — 옛 SHA 앱 DB(V1~V7)는 플래그 없이 V9 V10 V12 V13이 순서대로 적용된다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        flyway(url, LOC_BASE, target = "7").migrate()
        boot(url, incrementalProps("trigger", LOCS_TRG)).close()
        assertEquals("1:true 2:true 3:true 5:true 6:true 7:true 9:true 10:true 12:true 13:true", db.history())
    }
}
