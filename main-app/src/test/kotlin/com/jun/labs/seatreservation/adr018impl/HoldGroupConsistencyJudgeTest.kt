package com.jun.labs.seatreservation.adr018impl

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.loadtest.LoadtestDataService
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_A_OFF
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_FLYWAY_BASE
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 정합성 검증기의 묶음 항목 4개(설계-7)가 위반을 실제로 센다 — 위반 행을 하나씩 직접 만들고 그 항목만 1인지 본다.
 * pay_multi_reservation_intents = 결제 의도당 예약 수 > seat_count · group_partial_holds = 남은 홀드 수가 0도 seat_count도 아닌 묶음 ·
 * group_expiry_split = 남은 홀드의 expires_at이 둘 이상인 묶음 · group_partial_reservations = 예약 수가 0도 seat_count도 아닌 묶음 결제 의도.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [G_SCHED_OFF, G_COMP_SCHED_OFF, PIN_A_OFF, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class HoldGroupConsistencyJudgeTest : Adr018ImplBase() {
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var redis: StringRedisTemplate

    private val fields = listOf("pay_multi_reservation_intents", "group_partial_holds", "group_expiry_split", "group_partial_reservations")
    private val user = 7L

    private fun judged(): Map<String, Long> {
        val all = LoadtestDataService(jdbcTemplate, properties, redis, clock).consistency(20)
        return fields.associateWith { (all[it] as Number).toLong() }
    }

    private fun only(field: String) = fields.associateWith { if (it == field) 1L else 0L }

    private fun group(seatCount: Int): Long =
        q("INSERT INTO hold_group (schedule_id, user_id, seat_count, created_at) VALUES (?, ?, ?, ?) RETURNING id", schedule.id, user, seatCount, Timestamp.from(NOW))

    private fun hold(seatId: Long, groupId: Long?, expiresAt: Instant = NOW.plusSeconds(300)): Long {
        jdbcTemplate.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", seatId)
        return q(
            "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at, group_id) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
            seatId, schedule.id, user, Timestamp.from(NOW), Timestamp.from(expiresAt), groupId,
        )
    }

    private fun intent(uid: String, holdId: Long, seatId: Long, groupId: Long?, seatCount: Int, state: String = "CONFIRMED") {
        jdbcTemplate.update(
            """
            INSERT INTO payment_intent (uid, hold_id, user_id, schedule_id, seat_id, section, pay_by, state, created_at, group_id, seat_count)
            VALUES (?, ?, ?, ?, ?, 'A', ?, ?, ?, ?, ?)
            """.trimIndent(),
            uid, holdId, user, schedule.id, seatId, Timestamp.from(NOW.plusSeconds(300)), state, Timestamp.from(NOW), groupId, seatCount,
        )
    }

    private fun reservation(seatId: Long, uid: String) {
        jdbcTemplate.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", seatId)
        jdbcTemplate.update(
            "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at, intent_id) VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?)",
            schedule.id, seatId, user, uid, Timestamp.from(NOW), uid,
        )
    }

    @Test
    fun `정상 묶음(홀드 2 · 같은 만료)과 정상 묶음 확정(예약 2)은 모두 0`() {
        val (a, b, c, d) = seatIds(4)
        val g1 = group(2)
        hold(a, g1); hold(b, g1)
        val g2 = group(2)
        intent("u-ok", holdId = 999_001, seatId = c, groupId = g2, seatCount = 2)
        reservation(c, "u-ok"); reservation(d, "u-ok")
        assertEquals(fields.associateWith { 0L }, judged())
    }

    @Test
    fun `pay_multi_reservation_intents — 1석 결제 의도(seat_count 1)에 예약 2행`() {
        val (a, b) = seatIds(2)
        intent("u-multi", holdId = 999_002, seatId = a, groupId = null, seatCount = 1)
        reservation(a, "u-multi"); reservation(b, "u-multi")
        assertEquals(only("pay_multi_reservation_intents"), judged())
    }

    @Test
    fun `group_partial_holds — seat_count 2 묶음에 홀드 1행`() {
        val a = createSeat(1).id!!
        hold(a, group(2))
        assertEquals(only("group_partial_holds"), judged())
    }

    @Test
    fun `group_expiry_split — 묶음 홀드 2행의 만료가 다르다`() {
        val (a, b) = seatIds(2)
        val g = group(2)
        hold(a, g, NOW.plusSeconds(300)); hold(b, g, NOW.plusSeconds(611))
        assertEquals(only("group_expiry_split"), judged())
    }

    @Test
    fun `group_partial_reservations — seat_count 2 묶음 결제 의도에 예약 1행`() {
        val a = createSeat(1).id!!
        intent("u-part", holdId = 999_003, seatId = a, groupId = group(2), seatCount = 2)
        reservation(a, "u-part")
        assertEquals(only("group_partial_reservations"), judged())
    }
}
