package com.jun.labs.seatreservation

import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import kotlin.test.assertEquals

/**
 * ADR-002: 인덱스는 V2로 들어오고, 전부 유니크가 아니며, flyway.target=1이면 없다.
 * ADR-015(§설계-4 · Q7): 기본 위치에 V12 확정 예약 부분 유니크 인덱스가 더해졌다 — 기본 실행에는 V2 일반 인덱스 5개 + V12 하나가 있고,
 * '인덱스는 중복을 막지 않는다'(ADR-000 §1 기준선 스키마)는 V12 앞 스키마에서 본다 — 기본 위치만 쓰는 이 테스트에서는 spring.flyway.target=7
 * (ADR-015가 말하는 target=11과 같은 스키마: 기본 위치에는 V11이 없어 target=11은 Flyway가 'No migration with a target version 11'로 거부한다 — failOnMissingTarget 기본 참).
 */
class IndexMigrationTest : IntegrationTest() {

    @Test
    fun `기본 실행은 V2 일반 인덱스 5개 + V12 확정 예약 부분 유니크 인덱스가 있다`() {
        assertEquals(EXPECTED + (V12_INDEX to true), indexes(jdbcTemplate))
    }

    @Nested
    // 기준선 재현(ADR-015 Q7): V12 앞 스키마 — ADR-001·003 조건(같은 좌석 CONFIRMED 2건이 들어가야 이중 확정이 판정기에 드러난다).
    // target=7 = 기본 위치에서 V12 앞의 마지막 버전(target=11은 기본 위치에 V11이 없어 Flyway가 거부 — 위 머리말)
    @TestPropertySource(properties = ["spring.flyway.target=7", "seat.confirm.mode=current"])
    inner class BeforeV12 : IntegrationTest() {

        @Autowired lateinit var jdbc: JdbcTemplate

        @Test
        fun `인덱스는 중복을 막지 않는다 — 같은 좌석 홀드 2행, 같은 좌석 CONFIRMED 2건이 모두 들어간다`() {
            val seat = createSeat(1)
            repeat(2) { i ->
                insertHold(seat, userId = 10L + i, heldAt = NOW, expiresAt = Instant.parse("2026-09-27T10:05:00Z"))
                jdbc.update(
                    "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, 'pay', 'CONFIRMED', now())",
                    schedule.id, seat.id, 20L + i,
                )
            }

            assertEquals(EXPECTED, indexes(jdbc), "V12 앞 — V2 일반 인덱스만(V12 없음)")
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM seat_hold WHERE seat_id = ?", Int::class.java, seat.id))
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM reservation WHERE seat_id = ?", Int::class.java, seat.id))
        }
    }

    @Nested
    // ADR-009 기동 검사: target=1 스키마에는 쿼터 테이블이 없어 기본 매수 방식(counter 계열)으로는 뜨지 않는다 — 기준선(none)을 명시한다
    @TestPropertySource(properties = ["spring.flyway.target=1", "seat.hold.limit-strategy=none", "seat.confirm.mode=current"])
    inner class WithoutIndexes : IntegrationTest() {

        @Autowired lateinit var jdbc: JdbcTemplate

        @Test
        fun `flyway target 1이면 인덱스가 없다 — ADR-001 기준선과 같은 스키마`() {
            assertEquals(emptyMap(), indexes(jdbc))
        }
    }

    companion object {
        const val V12_INDEX = "uq_reservation_confirmed_seat"
        val EXPECTED = mapOf(
            "idx_seat_hold_seat_id" to false,
            "idx_seat_hold_user_id" to false,
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
