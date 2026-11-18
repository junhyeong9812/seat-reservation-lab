package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.domain.ProductSeat
import com.jun.labs.seatreservation.domain.SeatStatus
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
import java.sql.Timestamp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * S(substitute) 계약 — 명세 부록 B 'S' · ADR-017 설계-5 S 대체 트랜잭션(1~7 · 실패면 CLOSE) · 설계-4.3(I11 거부 코드 그대로) · 설계-7 N17-2 · N17-3 · N17-5 · N17-9.
 * 실제 HTTP(RANDOM_PORT) · 흉내 PG 같은 JVM · 확정 모드 기본 ④ · 매수 방식 기본 counter-upsert-precheck · max-per-user 2(application.yml).
 * 거부를 만드는 두 길:
 *  - 409 HOLD_EXPIRED = 앱 시계를 홀드 expires_at 너머로(만료 배치 전 — 원래 홀드 행이 남아 있음)
 *  - 404 HOLD_NOT_FOUND = 그 뒤 만료 배치(ExpireHoldsUseCase.expire — A′)가 홀드를 지움(쿼터 −1)
 *
 * 가정(근거):
 *  - 대체 좌석은 '같은 회차 · 같은 section의 AVAILABLE 좌석'이다(설계-5 S 5). 409 길에서는 4에서 되돌린 원래 좌석도 AVAILABLE이 되므로 그 좌석이 다시 고를 수 있다 —
 *    그래서 어떤 좌석인지는 단언하지 않고 '같은 section · RESERVED · 예약 seat_id = 본문 seatId'만 본다.
 *  - 대체 좌석 상태 = RESERVED(부록 B '좌석 RESERVED'), 예약 status = CONFIRMED(지금 확정 예약과 같은 값 — 판정기 · 쿼터 실제 수 정의가 CONFIRMED를 센다).
 *  - S 대체 성공 뒤 PG 결제는 그대로 CAPTURED(CLOSE 작업 없음 — 부록 B).
 *  - S 실패(CLOSE)의 intent state = REJECTED(부록 B 'CLOSE 작업(REJECTED)'), 작업 source = REJECTION.
 *  - `GET /api/payments/{uid}`(명세 §1-3)는 200 + 본문에 compensation(부록 B '조회 공통'). 헤더 X-User-Id는 소유자 id로 준다(요구 여부 미정 — 주면 어느 쪽이든 맞다).
 *  - 판매 마감은 회차 starts_at ≤ 앱 시계(SalesCloseGate — 앱 Clock). 테스트는 starts_at을 직접 당겨 만든다(V8 트리거 위치는 쓰지 않음 — P_LOC_BASE).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, DS_SCHEDULER_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        P_LOC_BASE, DS_MODE_SUBSTITUTE,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr017SSubstituteTest : Adr017DsBase() {

    private fun seatIn(section: String, seatNo: Int): Long =
        productSeatRepository.save(ProductSeat(scheduleId = schedule.id!!, section = section, rowNo = 1, seatNo = seatNo, status = SeatStatus.AVAILABLE)).id!!

    /** 선점 → payment-start → PG pay(R과 같은 클라이언트 흐름 — 설계-5 표) */
    private fun paid(seatId: Long, user: Long): Paid = startedHold(seatId, user).also { pgPayOk(it.uid) }

    private fun expireBatchOk() = assertTrue(expire.expire() >= 0)

    /** 대체 성공 끝 상태(부록 B) — 반환 = 대체 예약 id */
    private fun assertSubstituted(r: Resp, p: Paid, code: String, status: Int, why: String): Long {
        assertEquals(status, r.status, "$why — I11 원래 거부 상태 그대로: $r")
        assertEquals(code, r.code, "$why — 원래 거부 코드 그대로: $r")
        val comp = compOrFail(r, why)
        assertEquals("SUBSTITUTE", comp.type, "$why: $r")
        assertEquals("DONE", comp.status, "$why: $r")
        assertNull(comp.taskId, "$why — 대체 성공은 작업 없음(taskId 빠짐): $r")
        val rid = assertNotNull(comp.reservationId, "$why — reservationId: $r")
        val sid = assertNotNull(comp.seatId, "$why — seatId: $r")

        val res = reservation(rid)
        assertEquals("SUBSTITUTE", res.origin, "$why — origin SUBSTITUTE")
        assertEquals(sid, res.seatId, "$why — 예약 좌석 = 본문 seatId")
        assertEquals(p.user, res.userId)
        assertEquals(p.uid, res.paymentUid, "$why — payment_uid = uid")
        assertFalse(res.intentIdNull, "$why — intent_id = uid")
        assertEquals("CONFIRMED", res.status)
        assertEquals(schedule.id, seatSchedule(sid), "$why — 같은 회차")
        assertEquals(seatSection(p.seatId), seatSection(sid), "$why — 같은 section")
        assertEquals("RESERVED", seatStatusOf(sid), "$why — 대체 좌석 RESERVED")

        val i = intentOrFail(p.uid)
        assertEquals("SUBSTITUTED", i.state, "$why — intent SUBSTITUTED")
        assertEquals(code, i.resultCode, "$why — result_code = 원래 코드")
        assertEquals(rid, i.reservationId, "$why — intent.reservation_id")
        assertEquals(1, rejections(p.uid).size, "$why — confirm_rejection 1(N17-2)")
        assertEquals(code, rejections(p.uid).single().code)
        assertEquals(status, rejections(p.uid).single().httpStatus)
        assertTrue(tasks(p.uid).isEmpty(), "$why — CLOSE 작업 없음(N17-5): ${tasks(p.uid)}")
        assertEquals(1, reservationsOfUid(p.uid), "$why — N17-1")
        assertFalse(holdExists(p.holdId), "$why — 원래 홀드 행은 없다")
        return rid
    }

    /** 대체 실패 → CLOSE(부록 B) */
    private fun assertClosed(r: Resp, p: Paid, code: String, status: Int, why: String) {
        assertEquals(status, r.status, "$why: $r")
        assertEquals(code, r.code, "$why: $r")
        val comp = compOrFail(r, why)
        assertEquals("CLOSE", comp.type, "$why — 대체 못 하면 CLOSE: $r")
        assertNotNull(comp.taskId, "$why — CLOSE 작업 id: $r")
        assertNull(comp.reservationId, "$why: $r")
        val ts = tasks(p.uid)
        assertEquals(listOf("CLOSE"), ts.map { it.type }, "$why: $ts")
        assertEquals("REJECTION", ts.single().source)
        assertEquals(REJECTED, intentOrFail(p.uid).state, "$why — intent REJECTED")
        assertEquals(code, intentOrFail(p.uid).resultCode)
        assertEquals(1, rejections(p.uid).size, "$why — N17-2")
        assertEquals(0, reservationsOfUid(p.uid), "$why — 대체 예약 없음(N17-5)")
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE origin = 'SUBSTITUTE'", Int::class.java), "$why — SUBSTITUTE 예약 0")
    }

    // ── 대체 성공 ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `N17-9 — 409 거부(배치 전) → 원래 코드 + SUBSTITUTE · 대체 예약 · 같은 section · CLOSE 0 · PG CAPTURED · 쿼터 정합 · 재요청 재생 · 조회`() {
        val s1 = seatIn("A", 1)
        seatIn("A", 2)
        seatIn("B", 3)
        val p = paid(s1, x)
        clock.set(p.expiresAt.plusSeconds(1))

        val r = confirmHttp(p.holdId, p.user, p.uid)
        val rid = assertSubstituted(r, p, "HOLD_EXPIRED", 409, "409 길")
        assertEquals(1, cntOf(x) ?: 0, "원래 홀드를 옮김 → cnt 그대로 1")
        assertQuotaMatches(x, "409 길")
        assertEquals("CAPTURED", pgState(p.uid), "대체 성공은 결제를 그대로 둔다")
        assertEquals(1, intentCount())

        // N17-3 재생 — 상태를 바꾸지 않고 같은 응답
        val before = rowsSnapshot()
        val again = confirmHttp(p.holdId, p.user, p.uid)
        assertEquals(409, again.status, "재생: $again")
        assertEquals("HOLD_EXPIRED", again.code)
        val c2 = compOrFail(again, "재생")
        assertEquals(DsComp(null, "SUBSTITUTE", "DONE", rid, r.json!!.get(COMPENSATION).get("seatId").asLong()), c2, "재생 = 같은 대체 결과")
        assertEquals(before, rowsSnapshot(), "재생은 행을 바꾸지 않는다")
        assertEquals(1, rejections(p.uid).size)

        val g = getHttp("/api/payments/${p.uid}", p.user)
        assertEquals(200, g.status, "조회(명세 §1-3): $g")
        assertEquals("SUBSTITUTE", compOrFail(g, "조회").type, "조회 본문 compensation 공통(부록 B): $g")
    }

    @Test
    fun `N17-9 — 404 거부(배치가 홀드를 지운 뒤) → SUBSTITUTE · 쿼터 +1 = 실제 수`() {
        val s1 = seatIn("A", 1)
        seatIn("A", 2)
        val p = paid(s1, x)
        clock.set(p.expiresAt.plusSeconds(1))
        expireBatchOk()
        assertFalse(holdExists(p.holdId), "준비 — 배치가 홀드를 지웠다")
        assertEquals(0, cntOf(x) ?: 0, "준비 — 배치가 쿼터를 되돌렸다")

        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertSubstituted(r, p, "HOLD_NOT_FOUND", 404, "404 길")
        assertEquals(1, cntOf(x) ?: 0, "배치가 지운 뒤 대체 → +1")
        assertQuotaMatches(x, "404 길")
    }

    @Test
    fun `N17-9 쿼터 정합 — 409 길에 같은 사용자의 다른 살아 있는 홀드가 있어도 cnt = 홀드 + 확정 예약`() {
        val s1 = seatIn("A", 1)
        seatIn("A", 2)
        val s3 = seatIn("A", 3)
        val p = paid(s1, x)
        clock.set(p.expiresAt.minusSeconds(10))
        holdOk(s3, x) // 살아 있는 두 번째 홀드(cnt 2 — 상한)
        assertEquals(2, cntOf(x))
        clock.set(p.expiresAt.plusSeconds(1))

        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertSubstituted(r, p, "HOLD_EXPIRED", 409, "409 길 + 다른 홀드")
        assertEquals(2, actualOf(x), "홀드 1 + 대체 예약 1")
        assertQuotaMatches(x, "409 길 + 다른 홀드")
    }

    // ── 대체 실패 → CLOSE ──────────────────────────────────────────────────────────────────

    @Test
    fun `N17-9 — 같은 section에 빈 좌석 없음 → CLOSE(REJECTED) · 대체 없음 · 다른 section 좌석 그대로 · 워커 뒤 PG REFUNDED`() {
        val s1 = seatIn("A", 1)
        val b = seatIn("B", 2)
        val p = paid(s1, x)
        clock.set(p.expiresAt.plusSeconds(1))
        expireBatchOk()
        holdOk(s1, y) // 원래 좌석은 다른 사용자가 가져갔다 → section A에 AVAILABLE 없음

        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertClosed(r, p, "HOLD_NOT_FOUND", 404, "빈 좌석 없음")
        assertEquals("AVAILABLE", seatStatusOf(b), "다른 section 좌석은 쓰지 않는다")
        assertEquals("HELD", seatStatusOf(s1), "다른 사용자의 홀드 그대로")
        assertQuotaMatches(x, "빈 좌석 없음 x")
        assertQuotaMatches(y, "빈 좌석 없음 y")

        drain(p.uid)
        assertEquals("REFUNDED", pgState(p.uid), "close: CAPTURED → REFUNDED(설계-3)")
        assertEquals("REFUNDED", tasks(p.uid).single().lastResult)

        val again = confirmHttp(p.holdId, p.user, p.uid)
        assertEquals(404, again.status, "재생: $again")
        assertEquals("CLOSE", compOrFail(again, "재생").type)
        assertEquals("DONE", compOrFail(again, "재생").status)
    }

    @Test
    fun `N17-9 — 판매 마감(회차 starts_at 지남) → CLOSE · 대체 없음 · 원래 홀드 그대로(롤백) · 쿼터 정합`() {
        val s1 = seatIn("A", 1)
        val s2 = seatIn("A", 2)
        val p = paid(s1, x)
        val at = p.expiresAt.plusSeconds(10)
        jdbcTemplate.update("UPDATE product_schedule SET starts_at = ? WHERE id = ?", Timestamp.from(at.minusSeconds(5)), schedule.id)
        clock.set(at)

        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertClosed(r, p, "HOLD_EXPIRED", 409, "판매 마감")
        assertEquals("AVAILABLE", seatStatusOf(s2), "대체 좌석을 잡지 않는다")
        assertTrue(holdExists(p.holdId), "대체 트랜잭션 롤백 — 원래 홀드는 배치 몫으로 남는다(설계-5 S '롤백')")
        assertQuotaMatches(x, "판매 마감")
    }

    @Test
    fun `N17-9 — 매수 상한(배치 뒤 같은 사용자가 이미 2매) → CLOSE · 대체 없음 · 쿼터 2 그대로`() {
        val s1 = seatIn("A", 1)
        val s2 = seatIn("A", 2)
        val s3 = seatIn("A", 3)
        val s4 = seatIn("A", 4)
        val p = paid(s1, x)
        clock.set(p.expiresAt.plusSeconds(1))
        expireBatchOk()
        holdOk(s2, x)
        holdOk(s3, x)
        assertEquals(2, cntOf(x), "준비 — 상한 2 도달")

        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertClosed(r, p, "HOLD_NOT_FOUND", 404, "매수 상한")
        assertEquals("AVAILABLE", seatStatusOf(s4), "대체 좌석을 잡지 않는다(롤백)")
        assertEquals(2, cntOf(x), "상한 판정 실패는 쿼터를 바꾸지 않는다")
        assertQuotaMatches(x, "매수 상한")
        assertNotEquals("RESERVED", seatStatusOf(s1))
    }
}
