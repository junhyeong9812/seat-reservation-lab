package com.jun.labs.seatreservation

import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import kotlin.test.assertEquals

/** ADR-002: 인덱스는 V2로 들어오고, 전부 유니크가 아니며, flyway.target=1이면 없다. */
class IndexMigrationTest : IntegrationTest() {

    @Test
    fun `기본 실행은 V2까지 적용되어 일반 인덱스 5개가 있다`() {
        assertEquals(EXPECTED, indexes(jdbcTemplate))
    }

    @Test
    fun `인덱스는 중복을 막지 않는다 — 같은 좌석 홀드 2행, 같은 좌석 CONFIRMED 2건이 모두 들어간다`() {
        val seat = createSeat(1)
        repeat(2) { i ->
            insertHold(seat, userId = 10L + i, heldAt = NOW, expiresAt = Instant.parse("2026-09-27T10:05:00Z"))
            jdbcTemplate.update(
                "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, 'pay', 'CONFIRMED', now())",
                schedule.id, seat.id, 20L + i,
            )
        }

        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE seat_id = ?", Int::class.java, seat.id))
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE seat_id = ?", Int::class.java, seat.id))
    }

    @Nested
    @TestPropertySource(properties = ["spring.flyway.target=1"])
    inner class WithoutIndexes : IntegrationTest() {

        @Autowired lateinit var jdbc: JdbcTemplate

        @Test
        fun `flyway target 1이면 인덱스가 없다 — ADR-001 기준선과 같은 스키마`() {
            assertEquals(emptyMap(), indexes(jdbc))
        }
    }

    companion object {
        val EXPECTED = mapOf(
            "idx_seat_hold_seat_id" to false,
            "idx_seat_hold_schedule_user" to false,
            "idx_seat_hold_expires_at" to false,
            "idx_reservation_schedule_user" to false,
            "idx_reservation_seat_id" to false,
        )

        /** 기본키를 뺀 사용자 인덱스 이름 → 유니크 여부 */
        fun indexes(jdbc: JdbcTemplate): Map<String, Boolean> = jdbc.query(
            """
            SELECT c.relname AS name, i.indisunique AS uniq
            FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_class t ON t.oid = i.indrelid
            WHERE t.relname IN ('seat_hold', 'reservation', 'product_seat') AND NOT i.indisprimary
            """.trimIndent(),
        ) { rs, _ -> rs.getString("name") to rs.getBoolean("uniq") }.toMap()
    }
}
