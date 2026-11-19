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
import kotlin.test.assertEquals

/** 기본(seat.hold-group.enabled=false): 묶음 API 4개 404 · 1석 해제는 열림 · 부하 초기화가 V16 뒤에도 돈다 · 정합성 항목 4개 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [G_SCHED_OFF, G_COMP_SCHED_OFF, PIN_A_OFF, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class HoldGroupDisabledTest : Adr018ImplBase() {
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var redis: StringRedisTemplate

    @Test
    fun `묶음 API 404 · 1석 해제 204(카운터 -1 · 좌석 AVAILABLE)`() {
        val (a, b) = seatIds(2)
        assertEquals(404, holdGroup(7, a, b).status)
        assertEquals(404, releaseGroup(7, 1).status)
        assertEquals(404, groupPaymentStart(7, 1).status)
        assertEquals(404, groupConfirm(7, 1, "x").status)
        val hold = holdOneOk(7, a)
        assertEquals(1, cnt(7))
        assertEquals(204, releaseHold(7, hold).status)
        assertEquals(0, cnt(7))
        assertEquals("AVAILABLE", seatStatus(a))
        assertNoViolations("해제 뒤")
    }

    @Test
    fun `1석 결제 시작 뒤 해제 409 PAYMENT_STARTED`() {
        val a = createSeat(1).id!!
        val hold = holdOneOk(7, a)
        assertEquals(200, holdPaymentStart(7, hold).status)
        assertEquals(409 to "PAYMENT_STARTED", releaseHold(7, hold).let { it.status to it.code })
    }

    @Test
    fun `정합성 검증기 — 묶음 항목 4개가 0 · 부하 초기화가 hold_group을 비운다`() {
        val a = createSeat(1).id!!
        // 묶음 하나를 직접 만든다(묶음 API는 꺼져 있다) — 예약 1행 · 묶음 행 1
        val group = q("INSERT INTO hold_group (schedule_id, user_id, seat_count, created_at) VALUES (?, 7, 1, ?) RETURNING id", schedule.id, Timestamp.from(NOW))
        jdbcTemplate.update(
            "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at, group_id) VALUES (?, ?, 7, ?, ?, ?)",
            a, schedule.id, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(300)), group,
        )
        jdbcTemplate.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", a)
        val svc = LoadtestDataService(jdbcTemplate, properties, redis, clock)
        val judged = svc.consistency(20)
        for (k in listOf("pay_multi_reservation_intents", "group_partial_holds", "group_expiry_split", "group_partial_reservations")) {
            assertEquals(0L, (judged[k] as Number).toLong(), "$k: $judged")
        }
        svc.reset(1, 10)
        assertEquals(0L, q("SELECT count(*) FROM hold_group"))
        assertEquals(1L, q("SELECT nextval('hold_group_id_seq')"), "RESTART IDENTITY")
    }
}
