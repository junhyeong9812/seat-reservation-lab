package com.jun.labs.seatreservation.adr015fix

import com.jun.labs.seatreservation.adr015.MODE_KEY
import com.jun.labs.seatreservation.adr015.V12_INDEX
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.incremental.columnExists
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-015 구현 리뷰 R15-2 · R15-10(fix verification): V12 인덱스 판정이 `flyway.migrate()` **앞**(관문)에서 돈다 — 이번 기동이 V12를 만들지 않을 때.
 * - R15-2: V12 이력은 있는데 인덱스가 없음/INVALID + V13 미적용인 DB → 새 모드(없음) · 모든 모드(INVALID)가 마이그레이션 앞에서 거부 → V13(extended_at)이 생기지 않는다(스키마 · 이력 무변경).
 * - R15-10: 같은 이름이지만 모양이 다른 인덱스(비유니크 · 부분 조건 없음)는 INVALID와 같은 취급 — 모든 모드 거부 · 메시지에 사유.
 * 대조: 모양이 V12 그대로인 손으로 만든 인덱스는 통과(정규화가 표기 차이를 흡수).
 */
class Adr015IndexGateTest : BootTestBase() {

    private val kw = listOf("confirm")
    private val conditional = mapOf<String, String?>(MODE_KEY to "conditional")
    private val current = mapOf<String, String?>(MODE_KEY to "current")

    private fun JdbcTemplate.history(): List<Map<String, Any?>> =
        queryForList("SELECT installed_rank, version, checksum, success FROM flyway_schema_history ORDER BY installed_rank")

    private fun reasonOf(err: Throwable?): String = if (err == null) "(떴다)" else causesOf(err).joinToString(" | ") { it.message.orEmpty().take(400) }

    /** 기본 위치 · target=12로 준비 — V1~V7 + V12(이력 success) · V13 미적용 */
    private fun preparedAt12(): Pair<String, JdbcTemplate> {
        val url = freshDbUrl()
        boot(url, current + ("spring.flyway.target" to "12")).close()
        val db = jdbcFor(url)
        assertTrue(db.history().any { it["version"] == "12" && it["success"] == true }, "준비: V12 이력 success")
        assertFalse(db.columnExists("seat_hold", "extended_at"), "준비: V13 미적용(extended_at 없음)")
        return url to db
    }

    @Test
    fun `R15-2 — V12 이력 있고 인덱스 DROP · V13 미적용 DB에서 새 모드는 마이그레이션 앞에서 거부(extended_at 그대로 없음 · 이력 무변경)`() {
        val (url, db) = preparedAt12()
        db.execute("DROP INDEX $V12_INDEX")
        val before = db.history()
        val err = bootFailure(url, conditional)
        assertStartupFailure(err, "conditional · V12 이력 있음 · 인덱스 없음", kw)
        assertTrue(reasonOf(err).contains("마이그레이션 앞 DB"), "관문(마이그레이션 앞)에서 거부해야 한다: ${reasonOf(err)}")
        assertFalse(db.columnExists("seat_hold", "extended_at"), "거부된 기동이 V13을 적용했다(스키마 변경)")
        assertEquals(before, db.history(), "거부된 기동이 이력을 바꿨다")
        // 대조: current는 같은 DB에서 뜬다(인덱스 없음 = WARN) — V13은 그때 적용된다
        assertNull(bootFailure(url, current), "current는 인덱스 없음에서 뜬다(WARN)")
        assertTrue(db.columnExists("seat_hold", "extended_at"))
    }

    @Test
    fun `R15-2 — V12 이력 있고 인덱스 INVALID · V13 미적용 DB는 current도 마이그레이션 앞에서 거부(extended_at 그대로 없음)`() {
        val (url, db) = preparedAt12()
        db.update("UPDATE pg_index SET indisvalid = false WHERE indexrelid = ?::regclass", V12_INDEX)
        val before = db.history()
        listOf(current, conditional).forEach { p ->
            val err = bootFailure(url, p)
            assertStartupFailure(err, "$p · V12 INVALID", kw)
            assertTrue(reasonOf(err).contains("INVALID"), "사유에 INVALID: ${reasonOf(err)}")
            assertFalse(db.columnExists("seat_hold", "extended_at"), "$p: 거부된 기동이 V13을 적용했다")
            assertEquals(before, db.history(), "$p: 거부된 기동이 이력을 바꿨다")
        }
    }

    @Test
    fun `R15-10 — 같은 이름 비유니크 · 부분 조건 없는 인덱스는 모든 모드 거부(사유) · V12 그대로 다시 만든 인덱스는 통과`() {
        val url = freshDbUrl()
        boot(url, current).close()   // V1~V7 · V12 · V13
        val db = jdbcFor(url)
        val shapes = mapOf(
            "비유니크" to "CREATE INDEX $V12_INDEX ON reservation (seat_id) WHERE status = 'CONFIRMED'" to "유니크가 아니다",
            "부분 조건 없음" to "CREATE UNIQUE INDEX $V12_INDEX ON reservation (seat_id)" to "부분 조건",
            "다른 열" to "CREATE UNIQUE INDEX $V12_INDEX ON reservation (user_id) WHERE status = 'CONFIRMED'" to "열이 (seat_id)",
        )
        shapes.forEach { (shape, reason) ->
            val (name, ddl) = shape
            db.execute("DROP INDEX IF EXISTS $V12_INDEX")
            db.execute(ddl)
            val before = db.history()
            listOf(current, conditional).forEach { p ->
                val err = bootFailure(url, p)
                assertStartupFailure(err, "$name · $p", kw)
                assertTrue(reasonOf(err).contains(reason), "$name · $p: 사유 '$reason' 포함: ${reasonOf(err)}")
                assertEquals(before, db.history(), "$name · $p: 이력 무변경")
            }
        }
        // 대조: V12 정의 그대로(손으로) — 관문 · run 모두 통과
        db.execute("DROP INDEX IF EXISTS $V12_INDEX")
        db.execute("CREATE UNIQUE INDEX $V12_INDEX ON reservation (seat_id) WHERE status = 'CONFIRMED'")
        assertNull(bootFailure(url, conditional), "V12와 같은 모양은 뜬다")
    }
}
