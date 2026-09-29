package com.jun.labs.seatreservation.loadtest

import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

@ActiveProfiles("loadtest")
@AutoConfigureMockMvc
class LoadtestEndpointsTest : IntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var loadtestDataService: LoadtestDataService

    @BeforeEach
    fun seed() {
        mockMvc.post("/internal/reset?schedules=1&seatsPerSchedule=100").andExpect { status { isOk() } }
    }

    @Test
    fun `reset은 결정적 시드를 만든다 — 회차 k의 n번째 좌석 id = (k-1)*S + n`() {
        repeat(2) {
            mockMvc.post("/internal/reset?schedules=2&seatsPerSchedule=100").andExpect {
                status { isOk() }
                jsonPath("$.seatsPerRow") { value(50) }
            }
            assertEquals(200, count("SELECT count(*) FROM product_seat WHERE status = 'AVAILABLE'"))
            assertEquals(2, count("SELECT schedule_id FROM product_seat WHERE id = 101"))
            assertEquals(1, count("SELECT seat_no FROM product_seat WHERE id = 101"))
            assertEquals(2, count("SELECT row_no FROM product_seat WHERE id = 51"))
        }
    }

    @Test
    fun `배경 N은 배경 회차에 HELD N석·홀드 N + RESERVED N석·CONFIRMED N을 만들고 위반은 0이다`() {
        mockMvc.post("/internal/reset?schedules=1&seatsPerSchedule=100&backgroundRows=50").andExpect {
            status { isOk() }
            jsonPath("$.backgroundRows") { value(50) }
        }

        assertEquals(200, count("SELECT count(*) FROM product_seat"))
        assertEquals(100, count("SELECT count(*) FROM product_seat WHERE schedule_id = 1 AND status = 'AVAILABLE'"))
        assertEquals(50, count("SELECT count(*) FROM product_seat WHERE schedule_id = 2 AND status = 'HELD'"))
        assertEquals(50, count("SELECT count(*) FROM product_seat WHERE schedule_id = 2 AND status = 'RESERVED'"))
        assertEquals(50, count("SELECT count(*) FROM seat_hold h JOIN product_seat s ON s.id = h.seat_id WHERE s.status = 'HELD'"))
        assertEquals(50, count("SELECT count(*) FROM reservation r JOIN product_seat s ON s.id = r.seat_id WHERE s.status = 'RESERVED'"))
        assertEquals(emptyMap(), violations())
    }

    @Test
    fun `시드 직후에는 위반이 하나도 없다`() {
        assertEquals(emptyMap(), violations())
        mockMvc.get("/internal/consistency").andExpect {
            status { isOk() }
            jsonPath("$.seats") { value(100) }
            jsonPath("$.v_duplicate_hold_seats") { value(0) }
        }
    }

    @Test
    fun `counts는 홀드 행과 확정 예약 수를 센다`() {
        liveHold(seatId = 1, userId = 1)
        liveHold(seatId = 1, userId = 2)
        confirmed(seatId = 2, userId = 3)

        mockMvc.get("/internal/counts").andExpect {
            status { isOk() }
            jsonPath("$.hold_rows") { value(2) }
            jsonPath("$.confirmed") { value(1) }
            jsonPath("$.available") { doesNotExist() }
        }
        sql("UPDATE product_seat SET status = 'HELD' WHERE id = 1")
        sql("UPDATE product_seat SET status = 'RESERVED' WHERE id = 2")
        mockMvc.get("/internal/counts?seatStatus=true").andExpect {
            status { isOk() }
            jsonPath("$.available") { value(98) }
            jsonPath("$.held") { value(1) }
            jsonPath("$.reserved") { value(1) }
        }
    }

    @Test
    fun `같은 좌석의 홀드 2개 → 중복 홀드`() {
        sql("UPDATE product_seat SET status = 'HELD' WHERE id = 1")
        liveHold(seatId = 1, userId = 1)
        liveHold(seatId = 1, userId = 2)

        assertEquals(mapOf("v_duplicate_hold_seats" to 1L, "v_excess_hold_rows" to 1L), violations())
    }

    @Test
    fun `예약 없는 RESERVED → 불일치`() {
        sql("UPDATE product_seat SET status = 'RESERVED' WHERE id = 2")

        assertEquals(mapOf("v_reserved_without_confirmed" to 1L), violations())
    }

    @Test
    fun `같은 좌석의 CONFIRMED 2건 → 중복 확정`() {
        sql("UPDATE product_seat SET status = 'RESERVED' WHERE id = 3")
        confirmed(seatId = 3, userId = 1)
        confirmed(seatId = 3, userId = 2)

        assertEquals(mapOf("v_duplicate_confirmed_seats" to 1L), violations())
    }

    @Test
    fun `RESERVED 아닌 좌석의 CONFIRMED → 불일치`() {
        confirmed(seatId = 4, userId = 1)

        assertEquals(mapOf("v_confirmed_on_unreserved_seat" to 1L), violations())
    }

    @Test
    fun `HELD인데 홀드 없음 → 홀드 없는 HELD (유예와 무관하게 오래된 HELD이기도 하다)`() {
        sql("UPDATE product_seat SET status = 'HELD' WHERE id = 5")

        assertEquals(mapOf("v_held_without_hold" to 1L, "v_stale_held_seats" to 1L), violations())
    }

    @Test
    fun `HELD 아닌 좌석의 홀드 → 불일치`() {
        liveHold(seatId = 6, userId = 1)

        assertEquals(mapOf("v_hold_on_unheld_seat" to 1L), violations())
    }

    @Test
    fun `유예보다 오래전에 만료됐는데 HELD → 오래된 HELD, 유예 안이면 위반 아님`() {
        sql("UPDATE product_seat SET status = 'HELD' WHERE id IN (7, 8)")
        hold(seatId = 7, userId = 1, expiresAtSql = "now() - interval '100 seconds'")
        hold(seatId = 8, userId = 2, expiresAtSql = "now() - interval '5 seconds'")

        assertEquals(mapOf("v_stale_held_seats" to 1L), violations(graceSeconds = 20))
    }

    @Test
    fun `(사용자, 회차)당 홀드 + 확정 3매 → 매수 초과`() {
        sql("UPDATE product_seat SET status = 'HELD' WHERE id IN (9, 10)")
        sql("UPDATE product_seat SET status = 'RESERVED' WHERE id = 11")
        liveHold(seatId = 9, userId = 7)
        liveHold(seatId = 10, userId = 7)
        confirmed(seatId = 11, userId = 7)

        assertEquals(mapOf("v_over_limit_users" to 1L), violations())
    }

    private fun violations(graceSeconds: Long = 20): Map<String, Long> =
        loadtestDataService.consistency(graceSeconds)
            .filterKeys { it.startsWith("v_") }
            .mapValues { (it.value as Number).toLong() }
            .filterValues { it != 0L }

    private fun count(query: String): Int = jdbcTemplate.queryForObject(query, Int::class.java)!!

    private fun sql(statement: String) {
        jdbcTemplate.update(statement)
    }

    private fun liveHold(seatId: Long, userId: Long) = hold(seatId, userId, "now() + interval '5 minutes'")

    private fun hold(seatId: Long, userId: Long, expiresAtSql: String) {
        jdbcTemplate.update(
            "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, 1, ?, now(), $expiresAtSql)",
            seatId, userId,
        )
    }

    private fun confirmed(seatId: Long, userId: Long) {
        jdbcTemplate.update(
            "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (1, ?, ?, 'pay', 'CONFIRMED', now())",
            seatId, userId,
        )
    }
}
