package com.jun.labs.seatreservation.adr018impl

import com.jun.labs.seatreservation.incremental.BootTestBase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 설계-7 기동 검사 — 켰을 때 확정 conditional-extend · 보상 none · refund만. 거부는 Flyway migrate 앞(스키마 무변경) · 메시지에 키 이름 */
class HoldGroupStartupTest : BootTestBase() {

    private fun messages(t: Throwable): String = generateSequence(t) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    private fun assertRejectedBeforeMigrate(props: Map<String, String?>, why: String) {
        val url = freshDbUrl()
        val failure = assertNotNull(bootFailure(url, props), "$why — 기동이 실패해야 한다")
        assertTrue("seat.hold-group.enabled" in messages(failure), "$why — 메시지에 키 이름: ${messages(failure)}")
        assertEquals(false, jdbcFor(url).queryForObject("SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean::class.java), "$why — migrate 앞에서 거부")
    }

    @Test
    fun `켜고 확정 모드가 conditional-extend가 아니면 거부`() = assertRejectedBeforeMigrate(
        mapOf("seat.hold-group.enabled" to "true", "seat.confirm.mode" to "conditional", "seat.compensation.mode" to "none"),
        "conditional",
    )

    @Test
    fun `켜고 보상 모드가 auth-capture · substitute면 거부`() {
        for (mode in listOf("auth-capture", "substitute")) {
            assertRejectedBeforeMigrate(
                mapOf(
                    "seat.hold-group.enabled" to "true", "seat.compensation.mode" to mode, "seat.payment.pg.base-url" to "http://localhost:1",
                    "seat.hold.counter-drift.expiry-repair" to "true",
                ),
                mode,
            )
        }
    }

    @Test
    fun `켜고 conditional-extend · none이면 뜨고 V16이 적용된다 · 끄면(기본) 그대로 뜬다`() {
        val url = freshDbUrl()
        assertNull(bootFailure(url, mapOf("seat.hold-group.enabled" to "true", "seat.hold-group.mode" to "one-shot", "seat.compensation.mode" to "none")))
        assertEquals(true, jdbcFor(url).queryForObject("SELECT to_regclass('hold_group') IS NOT NULL", Boolean::class.java))
        assertNull(bootFailure(freshDbUrl(), mapOf("seat.compensation.mode" to "none")))
    }
}
