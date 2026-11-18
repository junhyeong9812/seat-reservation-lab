package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.Resp
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * task 05 — 안전망(설계-4.5 · N17-7 · RD17-P3) · 확정 ∥ 안전망 경합(N17-5 · 명세 §5 동시성) · 모드 refund.
 * 애너테이션은 Adr017PgRefundFlowTest와 같다(컨텍스트 공유).
 * 선점 at NOW → payment-start at NOW → expiresAt = NOW + 611초.
 *
 * 가정(근거 조항):
 *  - 안전망 '오래된 created_at' 조건(설계-4.5 '주기')에 별도 최소 나이 키가 없다(부록 A에 sweep 키는 interval · batch뿐) → 판정은 홀드 없음 · 만료로만 갈린다.
 *  - 안전망의 '만료'는 앱 시계 기준이다(지금 확정 진단과 같은 코드 · 같은 판정 — 설계-4.5 4). 그래서 앱 시계로 홀드를 만료시킨다.
 *  - 안전망이 닫은 intent에는 거부 기록(confirm_rejection)을 만들지 않는다 — 단언하지 않는다(명세 무언급).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_READ_TIMEOUT, P17_CONNECT_TIMEOUT, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr017PgSweepTest : Adr017PgBase() {

    private fun assertSweptClosing(uid: String, code: String, why: String): TaskRow {
        val i = intentOrFail(uid)
        assertEquals(CLOSING, i.state, "$why — state = CLOSING(설계-4.5)")
        assertEquals("SWEEP", i.closedReason, "$why — closed_reason = SWEEP")
        assertEquals(code, i.resultCode, "$why — 재생 코드 저장(RD17-P3)")
        assertNull(i.reservationId, why)
        val t = closeTask(uid)
        assertEquals("SWEEP", t.source, "$why — 작업 출처 SWEEP")
        assertEquals(0, reservationsOfUid(uid), "$why — 예약 없음(N17-5)")
        return t
    }

    @Test
    fun `N17-7 · RD17-P3 — 결제 후 확정 안 보냄 · 홀드 만료 → 안전망 → CLOSING(SWEEP · HOLD_EXPIRED) + CLOSE → 워커 → REFUNDED · 뒤늦은 첫 확정 = 409 HOLD_EXPIRED 재생 + compensation`() {
        val p = started(1, x)
        clock.set(p.expiresAt.plusSeconds(1))
        assertTrue(holdExists(p.holdId), "준비 — 홀드 행은 있고 만료만 됐다")

        assertTrue(sweep.runOnce() >= 1, "안전망 한 주기가 닫았다(N17-7)")
        val t = assertSweptClosing(p.uid, "HOLD_EXPIRED", "만료 홀드")
        assertEquals(1, sweep.runOnce().let { tasks(p.uid).size }, "다시 돌아도 작업 1행")

        runWorkerUntilDone(p.uid)
        val g = Adr017Pg.get(p.uid)
        assertEquals("REFUNDED", g.state, "$g")
        assertEquals(1, g.refunds)

        val late: Resp = confirmHttp(p.holdId, x, p.uid)
        assertEquals(409, late.status, "뒤늦은 첫 확정 = 안전망이 저장한 코드 재생: $late")
        assertEquals("HOLD_EXPIRED", late.code)
        assertCompensation(late, t.id, "CLOSE", setOf("DONE"), "재생 본문")
        assertEquals(CLOSING, intentOrFail(p.uid).state, "재생은 상태를 바꾸지 않는다")
        assertEquals(0, reservationsOfUid(p.uid))
    }

    @Test
    fun `N17-7 · RD17-P3 — 홀드 없음(배치가 지운 모양) → 안전망 → CLOSING(HOLD_NOT_FOUND) · 뒤늦은 첫 확정 = 404 HOLD_NOT_FOUND 재생 + compensation`() {
        val p = started(1, x)
        deleteHoldRow(p.holdId)

        assertTrue(sweep.runOnce() >= 1, "안전망이 닫았다")
        val t = assertSweptClosing(p.uid, "HOLD_NOT_FOUND", "홀드 없음")
        val late = confirmHttp(p.holdId, x, p.uid)
        assertEquals(404, late.status, "$late")
        assertEquals("HOLD_NOT_FOUND", late.code)
        assertCompensation(late, t.id, "CLOSE", setOf("PENDING", "IN_PROGRESS", "DONE"), "재생 본문")
        runWorkerUntilDone(p.uid)
        assertEquals("REFUNDED", pgState(p.uid))
    }

    @Test
    fun `N17-7 — 결제 안 하고 떠난 intent도 안전망이 닫고 PG CLOSED로 끝나 늦은 결제가 막힌다`() {
        val p = started(1, x, pay = false)
        clock.set(p.expiresAt.plusSeconds(1))
        assertTrue(sweep.runOnce() >= 1)
        assertSweptClosing(p.uid, "HOLD_EXPIRED", "미결제")
        runWorkerUntilDone(p.uid)
        assertEquals("CLOSED", pgState(p.uid))
        assertEquals(409, Adr017Pg.pay(p.uid).status, "늦은 결제 차단")
    }

    @Test
    fun `설계-4·5 — 홀드가 살아 있는 OPEN intent는 건드리지 않는다(작업 0 · OPEN) · 그 뒤 확정 200`() {
        val p = started(1, x)
        clock.set(p.expiresAt.minusSeconds(30))
        sweep.runOnce()
        val i = intentOrFail(p.uid)
        assertEquals(OPEN, i.state, "살아 있는 홀드 — 기다린다")
        assertNull(i.closedReason)
        assertTrue(tasks(p.uid).isEmpty(), "작업 0")
        assertEquals("CAPTURED", pgState(p.uid))

        val r = confirmHttp(p.holdId, x, p.uid)
        assertEquals(200, r.status, "$r")
        assertEquals(CONFIRMED, intentOrFail(p.uid).state)
        sweep.runOnce()
        assertTrue(tasks(p.uid).isEmpty(), "CONFIRMED는 안전망 대상이 아니다")
    }

    // ── N17-5 확정 ∥ 안전망 ──────────────────────────────────────────────────────────────────

    /** 한 uid의 끝 상태: '예약 + CLOSE 작업' 공존 0 · 상태별 일관 */
    private fun assertExclusive(uid: String, why: String) {
        val i = intentOrFail(uid)
        val res = reservationsOfUid(uid)
        val ts = tasks(uid)
        assertTrue(!(res > 0 && ts.any { it.type == "CLOSE" }), "$why — N17-5: 예약과 CLOSE 작업 공존 uid=$uid intent=$i 예약=$res 작업=$ts · ${dbState()}")
        assertTrue(res <= 1, "$why — N17-1")
        assertTrue(ts.size <= 1, "$why — 작업 ≤ 1: $ts")
        when (i.state) {
            CONFIRMED -> { assertEquals(1, res, why); assertTrue(ts.isEmpty(), why) }
            CLOSING, REJECTED -> { assertEquals(0, res, why); assertEquals(1, ts.size, "$why — 닫힌 intent는 CLOSE 1") }
            OPEN -> { assertEquals(0, res, why); assertTrue(ts.isEmpty(), why) }
            else -> throw AssertionError("$why — 모드 refund에서 생길 수 없는 state=${i.state}")
        }
    }

    /** 확정(uid마다 하나) + 안전망 두 주기 — 같은 출발선 */
    private fun raceBlocks(ps: List<Pair<Paid, Long>>): List<() -> Any> {
        val blocks = mutableListOf<() -> Any>()
        ps.forEach { (p, u) -> blocks.add { confirmHttp(p.holdId, u, p.uid) } }
        blocks.add { sweep.runOnce() }
        blocks.add { sweep.runOnce() }
        return blocks
    }

    @Test
    fun `N17-5 — 홀드가 살아 있을 때 확정 ∥ 안전망(여러 번) → 예약 + CLOSE 공존 0`() {
        var seatNo = 0
        repeat(3) { round ->
            val users = (1..4).map { 1_000L + round * 10 + it }
            val ps = users.map { u -> started(++seatNo, u) to u }
            clock.set(ps.maxOf { it.first.expiresAt }.minusSeconds(1)) // 살아 있되 경계 직전
            val blocks = raceBlocks(ps)
            together(*blocks.toTypedArray())
            sweep.runOnce()
            ps.forEach { (p, _) -> assertExclusive(p.uid, "라운드 $round") }
        }
    }

    @Test
    fun `N17-5 — 홀드가 만료된 뒤 확정(거부 기록) ∥ 안전망 → CLOSE 작업 정확히 1 · 상태 하나(REJECTED 또는 CLOSING) · 응답 409 HOLD_EXPIRED`() {
        var seatNo = 0
        repeat(3) { round ->
            val users = (1..4).map { 2_000L + round * 10 + it }
            val ps = users.map { u -> started(++seatNo, u) to u }
            clock.set(ps.maxOf { it.first.expiresAt }.plusSeconds(1))
            val blocks = raceBlocks(ps)
            val out = together(*blocks.toTypedArray())
            out.take(ps.size).forEach { r ->
                r as Resp
                assertEquals(409, r.status, "라운드 $round: $r")
                assertEquals("HOLD_EXPIRED", r.code, "첫 결과든 안전망 재생이든 같은 코드: $r")
            }
            ps.forEach { (p, _) ->
                assertExclusive(p.uid, "라운드 $round")
                val i = intentOrFail(p.uid)
                assertTrue(i.state == REJECTED || i.state == CLOSING, "닫혔다: $i")
                assertEquals(if (i.state == REJECTED) 1 else 0, rejections(p.uid).size, "거부 기록은 REJECTED일 때만: $i")
            }
        }
    }
}
