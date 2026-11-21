package com.jun.labs.seatreservation.adr018

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr017.Adr017Pg
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_INITIAL
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_MAX
import com.jun.labs.seatreservation.adr017.P17_LEASE
import com.jun.labs.seatreservation.adr017.P17_MAX_ATTEMPTS
import com.jun.labs.seatreservation.adr017.P17_MODE_REFUND
import com.jun.labs.seatreservation.adr017.P17_SCHEDULER_OFF
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
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * 설계-4 DV-2(TTL > W에서 연장이 만료를 앞당기지 않음) · 설계-7(enabled=false면 묶음 API 404 · 해제 1석은 열림 · 정합성 새 4필드 0).
 */

/** TTL 20분 > W 611초 — 기동 검사가 막지 않는 구성(설계-4 2) */
const val P18_TTL_20M = "seat.hold.ttl=20m"

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
        P18_ON, P18_SPLIT, P18_TTL_20M,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr018TtlLongerThanWindowTest : Adr018Base() {

    @Test
    fun `DV-2 — TTL 20분이 W 611초보다 긴 구성에서 묶음 결제 시작은 만료를 앞당기지 않는다 · extended=false · intent 1 · 재호출 같은 uid`() {
        val (a, b) = seats(2)
        val g = groupHoldOk(listOf(a, b), x)
        assertEquals(NOW.plus(Duration.ofMinutes(20)), g.expiresAt, "준비 — 선점 만료 = now + TTL 20분")
        val (uid, r) = groupStartOk(g.groupId, x)
        assertFalse(r.bool("extended"), "연장이 만료를 늦추지 않으면 바꾸지 않는다(extended=false): $r")
        assertEquals(g.expiresAt, r.instant("expiresAt"), "응답 expiresAt = 원래 만료")
        val rows = holdsOfGroup(g.groupId)
        assertTrue(rows.all { it.expiresAt == g.expiresAt }, "N행 만료가 앞당겨지지 않았다: $rows")
        assertTrue(rows.all { it.extendedAt == null }, "연장 표지 없음: $rows")
        assertEquals(1, intentsOfGroup(g.groupId).size)
        assertEquals(2, intentsOfGroup(g.groupId).single().seatCount)
        assertEquals(2 * AMOUNT, Adr017Pg.get(uid).payment!!.get("amount").asLong())
        val (uid2, r2) = groupStartOk(g.groupId, x)
        assertEquals(uid, uid2)
        assertEquals(r.instant("payBy"), r2.instant("payBy"))
        assertTrue(holdsOfGroup(g.groupId).all { it.expiresAt == g.expiresAt })
    }

    @Test
    fun `DV-2 — 남은 시간이 W보다 짧아진 뒤의 묶음 결제 시작은 N행을 now + W로 연장한다`() {
        val (a, b) = seats(2)
        val g = groupHoldOk(listOf(a, b), x)
        val t = g.expiresAt.minus(Duration.ofMinutes(5)) // 남은 5분 < W 611초 · > θ
        clock.set(t)
        val (_, r) = groupStartOk(g.groupId, x)
        assertTrue(r.bool("extended"), "$r")
        assertEquals(t.plus(W_611), r.instant("expiresAt"))
        assertTrue(holdsOfGroup(g.groupId).all { it.expiresAt == t.plus(W_611) && it.extendedAt != null })
    }
}

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr018DisabledTest : Adr018Base() {

    @Test
    fun `설계-7 — enabled 기본(false)이면 묶음 API 4개는 404 · 좌석 · 홀드 무변화`() {
        val (a, b) = seats(2)
        val h = holdOk(b, x)
        val before = snapshot()
        assertEquals(404, groupHold(listOf(a), y).status, "묶음 선점")
        assertEquals(404, releaseGroup(1, x).status, "묶음 해제")
        assertEquals(404, groupStart(1, x).status, "묶음 결제 시작")
        assertEquals(404, groupConfirm(1, x, "00000000-0000-0000-0000-000000000000").status, "묶음 확정")
        assertUnchanged(before, "꺼짐")
        assertTrue(holdExists(h))
    }

    @Test
    fun `설계-7 — enabled=false여도 1석 해제 DELETE api-holds는 열린다(204 · 좌석 AVAILABLE · 카운터 −1)`() {
        val a = seat()
        val h = holdOk(a, x)
        val r = releaseHold(h, x)
        assertEquals(204, r.status, "$r")
        assertEquals("AVAILABLE", seatStatusOf(a))
        assertEquals(0, cnt(x))
        assertCode(releaseHold(h, x), 404, HOLD_NOT_FOUND, "다시 해제")
    }
}

/**
 * 정합성 검증기 `/internal/consistency`(loadtest 프로필) — 새 4필드가 정수로 있고 정상 흐름(묶음 선점 · 결제 시작 · 확정 · 해제 · 만료 · 1석)에서 0.
 * 시간 의존 항목(유예 · DB now())은 보지 않는다 — 새 4필드만 본다.
 * 구성은 LoadtestEndpointsTest와 같이 시험 훅 · SQL 탭 없이(아래 @Import 주석).
 */
@ActiveProfiles("loadtest")
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
        P18_ON, P18_SPLIT,
    ],
)
// Adr015TapConfig를 들이지 않는다 — loadtest 프로필이 실제 AppPathInjectHook(appPathInjector)를 만들어 그 시험 훅(adr015ExpiryHook)과 빈이 둘이 된다
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr018ConsistencyTest : Adr018Base() {

    private fun assertNewFieldsZero(why: String) {
        val r = getHttp("/internal/consistency", null)
        assertEquals(200, r.status, "$why: $r")
        NEW_CONSISTENCY_FIELDS.forEach { f ->
            val n = assertNotNull(r.json?.get(f), "$why — 필드 $f 없음: $r")
            assertTrue(n.isIntegralNumber, "$why — $f 정수: $r")
            assertEquals(0L, n.asLong(), "$why — $f = 0: $r")
        }
    }

    @Test
    fun `설계-7 — 새 4필드(pay_multi_reservation_intents · group_partial_holds · group_expiry_split · group_partial_reservations)는 정상 흐름에서 0`() {
        val (a, b, c, d, e) = seats(5)
        assertNewFieldsZero("빈 상태")

        val g1 = groupHoldOk(listOf(a, b), x)
        val single = holdOk(e, 300L)
        assertNewFieldsZero("묶음 선점 + 1석 선점")

        val (uid, _) = groupStartOk(g1.groupId, x)
        assertNewFieldsZero("묶음 결제 시작(N행 연장)")
        assertEquals(200, Adr017Pg.pay(uid).status)
        assertEquals(200, groupConfirm(g1.groupId, x, uid).status)
        assertNewFieldsZero("묶음 확정(예약 N)")

        val (uid1, _) = startOk(single, 300L)
        assertEquals(200, Adr017Pg.pay(uid1).status)
        assertEquals(200, confirmHttp(single, 300L, uid1).status)
        assertNewFieldsZero("1석 확정(seat_count 1)")

        val g2 = groupHoldOk(listOf(c, d), y)
        assertNoGroupRowsLeftPartial()
        assertEquals(204, releaseGroup(g2.groupId, y).status)
        assertNewFieldsZero("묶음 해제")

        val g3 = groupHoldOk(listOf(c, d), y)
        clock.set(g3.expiresAt.plusSeconds(1))
        expire.expire()
        assertNewFieldsZero("묶음 만료")
    }

    private fun assertNoGroupRowsLeftPartial() = assertGroupInvariants("검증기 시험 중간")
}
