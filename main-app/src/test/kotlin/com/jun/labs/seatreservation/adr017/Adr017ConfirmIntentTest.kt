package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.adr016.NO_SUCH_HOLD
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * task 02 — 확정 흐름(설계-4.3) · 모드 none(기본 · 설계-5) · ④ conditional-extend(기본 모드).
 * intent 있는 uid: ⓐ intent 잠금 → ⓑ hold · user 대조(409 SEAT_NOT_HELD · 기록 없음 — Q3) → ⓒ state ≠ OPEN이면 재생 → ⓓ 지금 확정 문장
 *   (성공: CONFIRMED · reservation_id / 거부: 롤백 뒤 새 트랜잭션에서 거부 기록 + REJECTED(result_code)).
 * intent 없는 uid: 옛 경로 그대로(N17-10 · 지표 seat.confirm.legacy).
 * payment-start at NOW → 홀드 expires_at = NOW + 611초.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr017ConfirmIntentTest : Adr017Base() {

    private data class Prepared(val seatId: Long, val holdId: Long, val uid: String)

    private fun prepare(seatNo: Int, user: Long): Prepared {
        val seat = createSeat(seatNo).id!!
        val hold = holdOk(seat, user)
        val (uid, _) = startOk(hold, user)
        return Prepared(seat, hold, uid)
    }

    private fun assertIntentRejected(r: Resp, status: Int, code: String, why: String) {
        assertEquals(status, r.status, "$why: $r")
        assertEquals(code, r.code, "$why — 거부 코드는 그대로(I11 · 새 코드 없음): $r")
        assertIntentBodyNoTask(r, ERROR_KEYS_17, why)
    }

    // ── 성공 · 재생(N17-1 · N17-3) ─────────────────────────────────────────────────────────────

    @Test
    fun `N17-1 — intent uid 확정 성공 → 200 · intent CONFIRMED · reservation_id · 예약 intent_id 채움 · origin HOLD · 거부 기록 0 · 작업 0`() {
        val p = prepare(1, x)
        clock.set(NOW.plusSeconds(60))
        val r = confirmHttp(p.holdId, x, p.uid)
        assertEquals(200, r.status, "$r")
        assertIntentBodyNoTask(r, CONFIRM_KEYS, "intent 경로 200")
        assertEquals(p.seatId, r.long("seatId"))
        assertEquals("CONFIRMED", r.json!!.get("status").asText())

        val rid = r.long("reservationId")
        val i = intentOrFail(p.uid)
        assertEquals(CONFIRMED, i.state, "state = CONFIRMED(설계-4.3 ⓓ)")
        assertEquals(rid, i.reservationId, "intent.reservation_id = 응답 reservationId")
        val res = reservation(rid)
        assertFalse(res.intentIdNull, "예약 intent_id 채움(설계-4.1 · 4.3 ⓓ 'intent_id 포함')")
        assertEquals("HOLD", res.origin, "origin = HOLD(기본)")
        assertEquals(p.uid, res.paymentUid, "payment_uid = 요청 uid")
        assertEquals(1, reservationsOfUid(p.uid))
        assertEquals("RESERVED", seatStatusOf(p.seatId))
        assertFalse(holdExists(p.holdId), "홀드 소비(지금 확정 문장 그대로)")
        assertTrue(rejections(p.uid).isEmpty(), "성공에 거부 기록 없음")
        assertEquals(0, taskCount(), "모드 none — 보상 작업 0(설계-5)")
        assertSingleOutcome(p.uid, "성공 뒤")
    }

    @Test
    fun `N17-1 — reservation_intent_id UNIQUE가 같은 intent의 두 번째 예약을 DB에서 막는다`() {
        val p = prepare(1, x)
        val r = confirmHttp(p.holdId, x, p.uid)
        assertEquals(200, r.status, "$r")
        val rid = r.long("reservationId")
        // 같은 intent_id로 둘째 예약(CANCELED — V12 확정 좌석 유니크와 무관하게) 시도
        assertFailsWith<DataAccessException>("intent_id UNIQUE(N17-1 메커니즘)") {
            jdbcTemplate.update(
                """
                INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at, intent_id, origin)
                SELECT schedule_id, seat_id, user_id, payment_uid, 'CANCELED', created_at, intent_id, origin FROM reservation WHERE id = ?
                """.trimIndent(),
                rid,
            )
        }
        assertEquals(1, reservationsOfUid(p.uid))
    }

    @Test
    fun `N17-3 — 같은 uid 재요청 = 첫 결과 재생(200 · 같은 reservationId) · 예약 1개 · 상태 무변경`() {
        val p = prepare(1, x)
        val first = confirmHttp(p.holdId, x, p.uid)
        assertEquals(200, first.status, "$first")
        val before = intentOrFail(p.uid)

        clock.set(NOW.plusSeconds(120))
        val again = confirmHttp(p.holdId, x, p.uid)
        assertEquals(200, again.status, "재생 — 홀드가 이미 없어도 404가 아니라 첫 결과(설계-4.3 ⓒ): $again")
        assertIntentBodyNoTask(again, CONFIRM_KEYS, "재생 200")
        assertEquals(first.long("reservationId"), again.long("reservationId"), "같은 reservationId")
        assertEquals(first.long("seatId"), again.long("seatId"))
        assertEquals(1, reservationsOfUid(p.uid), "예약 1개(N17-1)")
        assertEquals(1, reservationCount())
        assertEquals(before, intentOrFail(p.uid), "재생은 intent를 바꾸지 않는다(N17-3)")
        assertSingleOutcome(p.uid, "재생 뒤")
    }

    // ── 거부 기록 · 재생(N17-2 · N17-3) ────────────────────────────────────────────────────────

    @Test
    fun `N17-2 · N17-3 — 홀드 만료 409 HOLD_EXPIRED → 거부 기록 1행 · intent REJECTED · result_code · 같은 uid 재요청 = 같은 코드 재생(시계를 되돌려도)`() {
        val p = prepare(1, x)
        val e = expiresAtOf(p.holdId)
        clock.set(e.plusSeconds(1))

        val r = confirmHttp(p.holdId, x, p.uid)
        assertIntentRejected(r, 409, "HOLD_EXPIRED", "만료 거부")
        val rej = rejections(p.uid)
        assertEquals(1, rej.size, "거부 기록 1행(설계-4.3 거부 · N17-2): $rej")
        assertEquals("HOLD_EXPIRED", rej.single().code)
        assertEquals(409, rej.single().httpStatus)
        val i = intentOrFail(p.uid)
        assertEquals(REJECTED, i.state, "state = REJECTED")
        assertEquals("HOLD_EXPIRED", i.resultCode, "result_code = 거부 코드")
        assertNull(i.reservationId)
        assertEquals(0, reservationsOfUid(p.uid))
        assertEquals(0, taskCount(), "모드 none — CLOSE 작업 없음(설계-5 none)")

        // 재생: 홀드가 다시 '살아 있는' 시각이어도 첫 결과(409 HOLD_EXPIRED)를 돌려주고 상태를 바꾸지 않는다
        clock.set(e.minusSeconds(60))
        assertTrue(holdExists(p.holdId), "준비 — 거부는 롤백이라 홀드 행은 남아 있다(F2)")
        val again = confirmHttp(p.holdId, x, p.uid)
        assertIntentRejected(again, 409, "HOLD_EXPIRED", "재생(N17-3 — 첫 결과 코드 고정)")
        assertTrue(holdExists(p.holdId), "재생은 확정 문장을 다시 돌리지 않는다 — 홀드 그대로")
        assertEquals("HELD", seatStatusOf(p.seatId))
        assertEquals(0, reservationCount(), "재생은 예약을 만들지 않는다")
        assertEquals(1, rejections(p.uid).size, "거부 기록은 여전히 1행(uid UNIQUE)")
        assertEquals(i, intentOrFail(p.uid), "intent 무변경")
        assertSingleOutcome(p.uid, "만료 거부 재생 뒤")
    }

    @Test
    fun `N17-2 · N17-3 — 홀드 삭제 404 HOLD_NOT_FOUND → 거부 기록 · REJECTED · 같은 uid 재생 404`() {
        val p = prepare(1, x)
        deleteHoldRow(p.holdId) // 만료 배치가 지운 뒤와 같은 모양(설계-4.3 '배치가 홀드를 지운 뒤 확정이 오면 404')

        val r = confirmHttp(p.holdId, x, p.uid)
        assertIntentRejected(r, 404, "HOLD_NOT_FOUND", "홀드 없음 거부")
        val rej = rejections(p.uid).single()
        assertEquals("HOLD_NOT_FOUND", rej.code)
        assertEquals(404, rej.httpStatus)
        val i = intentOrFail(p.uid)
        assertEquals(REJECTED, i.state)
        assertEquals("HOLD_NOT_FOUND", i.resultCode)

        val again = confirmHttp(p.holdId, x, p.uid)
        assertIntentRejected(again, 404, "HOLD_NOT_FOUND", "재생")
        assertEquals(1, rejectionCount())
        assertEquals(0, taskCount())
        assertSingleOutcome(p.uid, "404 재생 뒤")
    }

    @Test
    fun `N17-2 — C2 좌석 전환 실패 409 SEAT_NOT_HELD도 거부 기록을 남긴다(Q3 uid 충돌과 달리)`() {
        val p = prepare(1, x)
        setSeatStatus(p.seatId, "AVAILABLE") // C2 0행 → 409 SEAT_NOT_HELD(F1)

        val r = confirmHttp(p.holdId, x, p.uid)
        assertIntentRejected(r, 409, "SEAT_NOT_HELD", "C2 거부")
        assertEquals("SEAT_NOT_HELD", rejections(p.uid).single().code, "확정 문장 거부 = 기록(N17-2)")
        assertEquals(REJECTED, intentOrFail(p.uid).state)
        assertTrue(holdExists(p.holdId), "거부는 롤백 — C1 홀드 삭제도 되돌려진다")
        assertSingleOutcome(p.uid, "C2 거부 뒤")
    }

    // ── uid 충돌(Q3 · 설계-4.3 ⓑ) ─────────────────────────────────────────────────────────────

    @Test
    fun `Q3 — 같은 사용자의 다른 hold로 같은 uid → 409 SEAT_NOT_HELD · 기록 없음 · 다른 홀드 그대로 · 첫 uid의 결과를 덮지 않음`() {
        val p = prepare(1, x)
        val seat2 = createSeat(2).id!!
        val hold2 = holdOk(seat2, x)
        val conflict0 = total(M17_UID_CONFLICT)

        val r = confirmHttp(hold2, x, p.uid)
        assertEquals(409, r.status, "$r")
        assertEquals("SEAT_NOT_HELD", r.code, "새 코드 없이 SEAT_NOT_HELD(Q3 · I11)")
        assertEquals(0, rejectionCount(), "uid 충돌은 거부 기록을 남기지 않는다(Q3)")
        assertEquals(OPEN, intentOrFail(p.uid).state, "intent는 OPEN 그대로")
        assertTrue(holdExists(hold2), "다른 홀드는 소비되지 않는다(ⓑ는 확정 문장 앞)")
        assertEquals("HELD", seatStatusOf(seat2))
        assertEquals(0, reservationCount())
        assertEquals(conflict0 + 1, total(M17_UID_CONFLICT), "지표 $M17_UID_CONFLICT +1(Q3)")

        // 첫 hold로는 정상 확정
        val ok = confirmHttp(p.holdId, x, p.uid)
        assertEquals(200, ok.status, "$ok")
        assertEquals(CONFIRMED, intentOrFail(p.uid).state)
        assertSingleOutcome(p.uid, "충돌 뒤 정상 확정")
    }

    @Test
    fun `Q3 — 다른 사용자가 같은 hold · 같은 uid로 확정 → 409 SEAT_NOT_HELD(403 아님) · 기록 없음`() {
        val p = prepare(1, x)
        val r = confirmHttp(p.holdId, y, p.uid)
        assertEquals(409, r.status, "ⓑ user 대조가 확정 문장(403 진단) 앞(설계-4.3): $r")
        assertEquals("SEAT_NOT_HELD", r.code)
        assertEquals(0, rejectionCount())
        assertEquals(OPEN, intentOrFail(p.uid).state)
        assertTrue(holdExists(p.holdId))
    }

    @Test
    fun `Q3 — 이미 CONFIRMED인 uid를 다른 hold로 보내면 재생이 아니라 409 SEAT_NOT_HELD(ⓑ가 ⓒ 앞)`() {
        val p = prepare(1, x)
        val first = confirmHttp(p.holdId, x, p.uid)
        assertEquals(200, first.status, "$first")
        val hold2 = holdOk(createSeat(2).id!!, x)

        val r = confirmHttp(hold2, x, p.uid)
        assertEquals(409, r.status, "$r")
        assertEquals("SEAT_NOT_HELD", r.code)
        assertTrue(holdExists(hold2))
        assertEquals(1, reservationCount())
        assertEquals(CONFIRMED, intentOrFail(p.uid).state)
        assertEquals(0, rejectionCount())
    }

    // ── 옛 경로(N17-10) ───────────────────────────────────────────────────────────────────────

    @Test
    fun `N17-10 — intent 없는 uid(pay-…) 확정 = 지금과 같은 200 본문 키 · intent_id NULL · origin HOLD · 새 표 행 0 · 지표 legacy`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val legacy0 = total(M17_LEGACY)

        val r = confirmHttp(hold, x, "pay-$x")
        assertEquals(200, r.status, "$r")
        assertLegacyBody(r, CONFIRM_KEYS, "옛 경로 200")
        val res = reservation(r.long("reservationId"))
        assertTrue(res.intentIdNull, "옛 경로 예약 intent_id = NULL(설계-4.1)")
        assertEquals("HOLD", res.origin)
        assertEquals("pay-$x", res.paymentUid)
        assertEquals(0, intentCount(), "옛 경로는 intent를 만들지 않는다")
        assertEquals(0, rejectionCount())
        assertEquals(0, taskCount())
        assertEquals(legacy0 + 1, total(M17_LEGACY), "지표 $M17_LEGACY +1(설계-4.3)")
    }

    @Test
    fun `N17-10 — payment-start를 불렀어도 클라이언트가 자기 uid를 보내면 옛 경로 · intent는 OPEN 그대로`() {
        val p = prepare(1, x)
        val r = confirmHttp(p.holdId, x, "pay-$x")
        assertEquals(200, r.status, "$r")
        assertLegacyBody(r, CONFIRM_KEYS, "옛 경로 200")
        assertTrue(reservation(r.long("reservationId")).intentIdNull, "옛 경로 예약 intent_id = NULL")
        assertEquals(OPEN, intentOrFail(p.uid).state, "intent는 건드리지 않는다(OPEN — 안전망 몫)")
        assertEquals(0, reservationsWithIntent())
    }

    @Test
    fun `N17-10 — intent 없는 uid의 거부는 지금 본문 code · message 그대로 · 거부 기록 없음`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)

        val notFound = confirmHttp(NO_SUCH_HOLD, x, "pay-$x")
        assertEquals(404, notFound.status, "$notFound")
        assertEquals("HOLD_NOT_FOUND", notFound.code)
        assertLegacyBody(notFound, ERROR_KEYS_17, "옛 경로 404")

        val notOwned = confirmHttp(hold, y, "pay-$y")
        assertEquals(403, notOwned.status, "옛 경로는 403 진단 그대로: $notOwned")
        assertEquals("HOLD_NOT_OWNED", notOwned.code)
        assertLegacyBody(notOwned, ERROR_KEYS_17, "옛 경로 403")

        clock.set(expiresAtOf(hold).plusSeconds(1))
        val expired = confirmHttp(hold, x, "pay-$x")
        assertEquals(409, expired.status, "$expired")
        assertEquals("HOLD_EXPIRED", expired.code)
        assertLegacyBody(expired, ERROR_KEYS_17, "옛 경로 409")

        assertEquals(0, rejectionCount(), "옛 경로 거부는 기록 없음(보장 밖 — 설계-4.1)")
        assertEquals(0, intentCount())
        assertEquals(0, taskCount())
    }

    @Test
    fun `N17-10 — 옛 하네스처럼 같은 pay-uid를 두 hold에 다시 써도 두 예약 모두 성공(payment_uid 유니크 없음)`() {
        val h1 = holdOk(createSeat(1).id!!, x)
        val h2 = holdOk(createSeat(2).id!!, x)
        val r1 = confirmHttp(h1, x, "pay-$x")
        val r2 = confirmHttp(h2, x, "pay-$x")
        assertEquals(200, r1.status, "$r1")
        assertEquals(200, r2.status, "옛 경로는 uid 멱등이 없다 — 지금과 같다(설계-4.1 · RD17-9): $r2")
        assertTrue(r1.long("reservationId") != r2.long("reservationId"))
        assertEquals(2, reservationsOfUid("pay-$x"))
    }

    // ── 모드 none(Q1 · 설계-5) ─────────────────────────────────────────────────────────────────

    @Test
    fun `Q1 none — 성공 · 만료 거부 · 404 거부 · 재생 · uid 충돌이 섞여도 compensation_task 0행 · intent는 CLOSING · SUBSTITUTED가 되지 않는다`() {
        val a = prepare(1, 301L)
        val b = prepare(2, 302L)
        val c = prepare(3, 303L)
        deleteHoldRow(c.holdId)

        assertEquals(200, confirmHttp(a.holdId, 301L, a.uid).status)
        assertEquals(404, confirmHttp(c.holdId, 303L, c.uid).status)
        clock.set(expiresAtOf(b.holdId).plusSeconds(1))
        assertEquals(409, confirmHttp(b.holdId, 302L, b.uid).status)
        assertEquals(200, confirmHttp(a.holdId, 301L, a.uid).status)
        assertEquals(409, confirmHttp(b.holdId, 302L, b.uid).status)
        assertEquals(409, confirmHttp(b.holdId, 301L, a.uid).status) // uid 충돌

        assertEquals(0, taskCount(), "모드 none — 보상 작업 0(설계-5 · Q1)")
        val states = jdbcTemplate.queryForList("SELECT state FROM payment_intent", String::class.java).toSet()
        assertTrue(states.all { it in setOf(OPEN, CONFIRMED, REJECTED) }, "none에서 생기는 state는 OPEN · CONFIRMED · REJECTED뿐: $states")
        listOf(a, b, c).forEach { assertSingleOutcome(it.uid, "none 혼합") }
    }

    // ── 조회(명세 §1 · 3 · task 02) ────────────────────────────────────────────────────────────

    // 가정: GET /api/payments/{uid}의 본문 모양 · 헤더 요구는 명세가 정하지 않았다 — 200이고 본문 어딘가에 intent의 state 값이 있는 것만 본다.
    //       없는 uid는 404로 가정한다(지금 API의 '없음 = 404' 관례).
    @Test
    fun `조회 — GET api payments uid는 확정 뒤 200 · 본문에 state(CONFIRMED) · 없는 uid는 404`() {
        val p = prepare(1, x)
        assertEquals(200, confirmHttp(p.holdId, x, p.uid).status)
        val g = getHttp("/api/payments/${p.uid}", x)
        assertEquals(200, g.status, "$g")
        assertTrue(g.body.contains(CONFIRMED), "조회 본문에 state: $g")

        val none = getHttp("/api/payments/00000000-0000-0000-0000-000000000000", x)
        assertEquals(404, none.status, "없는 uid: $none")
    }
}
