package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.service.impl.payment.CompensationSweep
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * task 05 — 모드 none(기본 · PG 주소 없음)의 안전망 = 탐지만(설계-5 `none` · 명세 §1-6 · N17-12).
 * 모드 키 · base-url을 주지 않는다(기본 구성 그대로). 선점 at NOW → payment-start at NOW → expiresAt = NOW + 611초.
 *
 * 가정(근거 조항):
 *  - `seat.compensation.unresolved`는 카운터다(부록 A 지표 목록 · 설계-5 '지표와 로그로만 남긴다' — '증가'). 게이지로 구현됐다면 값 ≥ 탐지 수로 본다.
 *  - CompensationSweep 빈은 모드 none에서도 있다(부록 A 'runOnce = 닫거나(모드 ≠ none) 탐지한(none) intent 수').
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, P17_SCHEDULER_OFF],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr017PgNoneModeSweepTest : Adr017Base() {

    @Autowired lateinit var sweep: CompensationSweep

    private fun unresolved(): Double {
        val counters = meters.find(M17_UNRESOLVED).counters()
        if (counters.isNotEmpty()) return counters.sumOf { it.count() }
        return meters.find(M17_UNRESOLVED).gauge()?.value() ?: 0.0
    }

    private fun prepare(seatNo: Int, user: Long): Triple<Long, String, java.time.Instant> {
        val seat = createSeat(seatNo).id!!
        val hold = holdOk(seat, user)
        val (uid, r) = startOk(hold, user)
        return Triple(hold, uid, r.instant("expiresAt"))
    }

    @Test
    fun `N17-12 · 설계-5 none — 홀드 만료 · 홀드 없음 intent를 안전망이 탐지만 한다(state OPEN 그대로 · 작업 0 · unresolved 증가)`() {
        val (h1, uid1, e1) = prepare(1, x)
        val (h2, uid2, _) = prepare(2, y)
        deleteHoldRow(h2)
        clock.set(e1.plusSeconds(1))
        assertTrue(holdExists(h1))
        val before = unresolved()

        val n = sweep.runOnce()
        assertTrue(n >= 2, "탐지 수 = 2(만료 1 · 없음 1): $n")
        assertTrue(unresolved() >= before + 2, "$M17_UNRESOLVED 증가(${before} → ${unresolved()})")
        listOf(uid1, uid2).forEach { uid ->
            val i = intentOrFail(uid)
            assertEquals(OPEN, i.state, "none — state를 바꾸지 않는다: $i")
            assertNull(i.closedReason)
            assertNull(i.resultCode)
        }
        assertEquals(0, taskCount(), "none — 작업 0")
        assertEquals(0, rejectionCount(), "안전망은 거부 기록을 만들지 않는다")
    }

    @Test
    fun `N17-12 none — 홀드가 살아 있는 intent는 탐지 대상이 아니다(unresolved 그대로)`() {
        val (_, uid, e) = prepare(1, x)
        clock.set(e.minusSeconds(30))
        val before = unresolved()
        assertEquals(0, sweep.runOnce(), "살아 있는 홀드 — 0")
        assertEquals(before, unresolved())
        assertEquals(OPEN, intentOrFail(uid).state)
        assertEquals(0, taskCount())
    }
}
