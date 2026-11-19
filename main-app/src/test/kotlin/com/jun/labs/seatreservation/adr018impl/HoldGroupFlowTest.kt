package com.jun.labs.seatreservation.adr018impl

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_FLYWAY_BASE
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 묶음 선점 · 해제 · 결제 시작 · 확정 · 만료 흐름(설계-2 ~ 6) — 두 방식(split · one-shot)에 같은 시험을 돌린다 */
abstract class HoldGroupFlowCases : Adr018ImplBase() {
    private val u = 7L
    private val other = 8L

    @Test
    fun `선점 201 — 홀드 N행 · 같은 만료 · 좌석 HELD · 카운터 +N · 응답 좌석 id 오름차순`() {
        val (a, b) = seatIds(2)
        val r = holdGroup(u, b, a)
        assertEquals(201, r.status, "$r")
        val group = r.json!!.get("groupId").asLong()
        val holds = r.json!!.get("holds").map { it.get("seatId").asLong() to it.get("holdId").asLong() }
        assertEquals(listOf(a, b), holds.map { it.first }, "holds는 seatId 오름차순")
        assertTrue(r.json!!.has("payBy") && r.json!!.has("payWithinSeconds"), "결제 시작 모드라 payBy가 붙는다: $r")
        assertEquals(2L, holdsOfGroup(group))
        assertEquals(1, groupExpiries(group).size)
        assertEquals(NOW.plus(Duration.ofMinutes(5)), groupExpiries(group).single())
        assertEquals(listOf("HELD", "HELD"), listOf(seatStatus(a), seatStatus(b)))
        assertEquals(2, cnt(u))
        assertEquals(2L, q("SELECT seat_count FROM hold_group WHERE id = ?", group))
        assertNoViolations("선점 뒤")
    }

    @Test
    fun `본문 검사 400 — 없음 · 빈 · 중복 · 한도 초과 · 숫자 아님 · 판매 마감보다 먼저`() {
        val (a, b, c) = seatIds(3)
        clock.set(SCHEDULE_STARTS_AT.plusSeconds(1)) // 마감 뒤에도 본문 검사가 먼저
        for (body in listOf(null, "{}", """{"seatIds": []}""", """{"seatIds": [$a, $a]}""", """{"seatIds": [$a, $b, $c]}""", """{"seatIds": ["x"]}""", """{"seatIds": [1.5]}""", "not json")) {
            val r = send("POST", "/api/schedules/${schedule.id}/hold-groups", u, body)
            assertEquals(400 to "INVALID_SEAT_SELECTION", r.status to r.code, "본문 $body: $r")
        }
        assertEquals(409 to "SCHEDULE_CLOSED", holdGroup(u, a, b).let { it.status to it.code })
        assertEquals(0, q("SELECT count(*) FROM hold_group").toInt())
    }

    @Test
    fun `거부 순서와 무변경 — 좌석 없음(다른 회차) 404 · 선점 불가 409 · 한도 초과 409`() {
        val (a, b, c) = seatIds(3)
        val otherSchedule = productScheduleRepository.save(com.jun.labs.seatreservation.domain.ProductSchedule(productId = schedule.productId, startsAt = SCHEDULE_STARTS_AT))
        val foreign = productSeatRepository.save(com.jun.labs.seatreservation.domain.ProductSeat(scheduleId = otherSchedule.id!!, section = "A", rowNo = 1, seatNo = 1, status = com.jun.labs.seatreservation.domain.SeatStatus.AVAILABLE)).id!!
        assertEquals(404 to "SEAT_NOT_FOUND", holdGroup(u, a, foreign).let { it.status to it.code })
        holdOneOk(other, b)
        assertEquals(409 to "SEAT_NOT_AVAILABLE", holdGroup(u, a, b).let { it.status to it.code })
        holdOneOk(u, c)
        val d = createSeat(9).id!!
        assertEquals(409 to "HOLD_LIMIT_EXCEEDED", holdGroup(u, a, d).let { it.status to it.code })
        assertEquals("AVAILABLE", seatStatus(a))
        assertEquals("AVAILABLE", seatStatus(d))
        assertEquals(1, cnt(u))
        assertEquals(0, q("SELECT count(*) FROM hold_group").toInt())
        assertNoViolations("거부 뒤")
    }

    @Test
    fun `해제 — 묶음 204(좌석 AVAILABLE · 카운터 -N) · 1석 204(-1) · 남의 것 403 · 없음 404 · 묶음의 홀드를 1석 해제 409 HOLD_IN_GROUP`() {
        val (a, b, c) = seatIds(3)
        val group = holdGroupOk(u, a, b)
        val holdInGroup = q("SELECT min(id) FROM seat_hold WHERE group_id = ?", group)
        assertEquals(409 to "HOLD_IN_GROUP", releaseHold(u, holdInGroup).let { it.status to it.code })
        assertEquals(403 to "HOLD_NOT_OWNED", releaseGroup(other, group).let { it.status to it.code })
        assertEquals(404 to "HOLD_NOT_FOUND", releaseGroup(u, 999_999).let { it.status to it.code })
        assertEquals(204, releaseGroup(u, group).status)
        assertEquals(listOf("AVAILABLE", "AVAILABLE"), listOf(seatStatus(a), seatStatus(b)))
        assertEquals(0, cnt(u))
        assertEquals(404, releaseGroup(u, group).status, "두 번째 해제는 404")

        val one = holdOneOk(other, c)
        assertEquals(403, releaseHold(u, one).status)
        assertEquals(204, releaseHold(other, one).status)
        assertEquals("AVAILABLE", seatStatus(c))
        assertEquals(0, cnt(other))
        assertEquals(404, releaseHold(other, one).status)
        assertNoViolations("해제 뒤")
    }

    @Test
    fun `만료됐지만 배치가 지우지 않은 묶음도 해제된다 — 결과는 만료 배치와 같다`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        clock.advance(Duration.ofMinutes(6))
        assertEquals(204, releaseGroup(u, group).status)
        assertEquals(0, cnt(u))
        assertEquals(0, expire.expire(), "배치가 더 지울 것이 없다(카운터 이중 감소 없음)")
        assertEquals(0, cnt(u))
        assertNoViolations("만료 뒤 해제")
    }

    @Test
    fun `결제 시작 — 모든 좌석 연장 · 결제 의도 1개(seat_count N) · 재호출 같은 uid · 시작 뒤 해제 409 · 1석 API에 묶음 홀드 409`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        val r = groupPaymentStart(u, group)
        assertEquals(200, r.status, "$r")
        assertEquals(true, r.json!!.get("extended").asBoolean())
        assertEquals(group, r.json!!.get("groupId").asLong())
        val uid = r.json!!.get("paymentUid").asText()
        assertEquals(listOf(NOW.plusSeconds(611)), groupExpiries(group), "W 611초로 함께 연장")
        assertEquals(2L, q("SELECT seat_count FROM payment_intent WHERE group_id = ?", group))
        assertEquals(q("SELECT min(id) FROM seat_hold WHERE group_id = ?", group), q("SELECT hold_id FROM payment_intent WHERE group_id = ?", group))
        val again = groupPaymentStart(u, group)
        assertEquals(uid to r.json!!.get("payBy").asText(), again.json!!.get("paymentUid").asText() to again.json!!.get("payBy").asText(), "재호출: $again")
        assertEquals(403, groupPaymentStart(other, group).status)
        assertEquals(409 to "PAYMENT_STARTED", releaseGroup(u, group).let { it.status to it.code })
        val minHold = q("SELECT min(id) FROM seat_hold WHERE group_id = ?", group)
        val maxHold = q("SELECT max(id) FROM seat_hold WHERE group_id = ?", group)
        assertEquals(409 to "HOLD_IN_GROUP", holdPaymentStart(u, maxHold).let { it.status to it.code })
        assertEquals(409 to "HOLD_IN_GROUP", holdConfirm(u, minHold, uid).let { it.status to it.code })
        assertEquals(409 to "HOLD_IN_GROUP", holdConfirm(u, maxHold, "legacy-uid").let { it.status to it.code })
        assertEquals(2L, holdsOfGroup(group), "거부는 아무것도 바꾸지 않는다")
        assertEquals(1L, q("SELECT count(*) FROM payment_intent"))
    }

    @Test
    fun `확정 — N석 RESERVED · 예약 N행(같은 intent) · 카운터 그대로 · 재호출 같은 결과 · 1석 API 재생도 HOLD_IN_GROUP`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        val uid = groupPaymentStart(u, group).json!!.get("paymentUid").asText()
        val minHold = q("SELECT min(id) FROM seat_hold WHERE group_id = ?", group)
        val r = groupConfirm(u, group, uid)
        assertEquals(200, r.status, "$r")
        assertEquals("CONFIRMED", r.json!!.get("status").asText())
        assertEquals(listOf(a, b), r.json!!.get("reservations").map { it.get("seatId").asLong() })
        assertEquals(listOf("RESERVED", "RESERVED"), listOf(seatStatus(a), seatStatus(b)))
        assertEquals(2L, q("SELECT count(*) FROM reservation WHERE intent_id = ?", uid))
        assertEquals(0L, holdsOfGroup(group))
        assertEquals(2, cnt(u))
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject("SELECT state FROM payment_intent WHERE uid = ?", String::class.java, uid))
        val again = groupConfirm(u, group, uid)
        assertEquals(r.body, again.body, "재호출은 같은 결과")
        assertEquals(409 to "SEAT_NOT_HELD", groupConfirm(other, group, uid).let { it.status to it.code })
        assertEquals(409 to "HOLD_IN_GROUP", holdConfirm(u, minHold, uid).let { it.status to it.code })
        assertNoViolations("확정 뒤")
    }

    @Test
    fun `확정 거부(만료) — N석 모두 그대로 · REJECTED 1건 · 그 뒤 배치가 N행 · 카운터 -N`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        val uid = groupPaymentStart(u, group).json!!.get("paymentUid").asText()
        clock.advance(Duration.ofSeconds(612))
        val r = groupConfirm(u, group, uid)
        assertEquals(409 to "HOLD_EXPIRED", r.status to r.code, "$r")
        assertEquals(2L, holdsOfGroup(group))
        assertEquals(0L, q("SELECT count(*) FROM reservation"))
        assertEquals("REJECTED", jdbcTemplate.queryForObject("SELECT state FROM payment_intent WHERE uid = ?", String::class.java, uid))
        assertEquals(1L, q("SELECT count(*) FROM confirm_rejection WHERE uid = ?", uid))
        assertEquals(409 to "HOLD_EXPIRED", groupConfirm(u, group, uid).let { it.status to it.code }, "재생")
        assertEquals(2, expire.expire())
        assertEquals(0, cnt(u))
        assertEquals(listOf("AVAILABLE", "AVAILABLE"), listOf(seatStatus(a), seatStatus(b)))
        assertNoViolations("만료 뒤")
    }

    @Test
    fun `만료 배치 — 결제 시작 안 한 묶음은 통째로 지워진다`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        clock.advance(Duration.ofMinutes(5))
        assertEquals(2, expire.expire())
        assertEquals(0L, holdsOfGroup(group))
        assertEquals(0, cnt(u))
        assertEquals(1L, q("SELECT count(*) FROM hold_group"), "묶음 행은 남는다")
        assertEquals(404, groupPaymentStart(u, group).status)
    }

    @Test
    fun `결제 시작 0행 진단 — 남은 시간 θ 이하 409 · 만료 409`() {
        val (a, b, c, d) = seatIds(4)
        val g1 = holdGroupOk(u, a, b)
        clock.advance(Duration.ofMinutes(5).minusMillis(500))
        assertEquals(409 to "HOLD_EXPIRED", groupPaymentStart(u, g1).let { it.status to it.code })
        clock.set(NOW)
        val g2 = holdGroupOk(other, c, d)
        clock.advance(Duration.ofMinutes(5))
        assertEquals(409 to "HOLD_EXPIRED", groupPaymentStart(other, g2).let { it.status to it.code })
        assertEquals(0L, q("SELECT count(*) FROM payment_intent"))
        assertNotEquals(0L, holdsOfGroup(g2))
    }
}

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [G_SCHED_OFF, G_COMP_SCHED_OFF, G_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE, G_ENABLED, G_SPLIT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class HoldGroupFlowSplitTest : HoldGroupFlowCases()

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [G_SCHED_OFF, G_COMP_SCHED_OFF, G_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE, G_ENABLED, G_ONE_SHOT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class HoldGroupFlowOneShotTest : HoldGroupFlowCases()
