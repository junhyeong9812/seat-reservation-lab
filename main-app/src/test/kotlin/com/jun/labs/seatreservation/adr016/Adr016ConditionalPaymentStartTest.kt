package com.jun.labs.seatreservation.adr016

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.M_CONDITIONAL
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.adr015.TTL
import com.jun.labs.seatreservation.adr015.isHttp
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * ① payment-start(명세 §1 · 3 · 4 · N16-1 · 2 · 3 · 4) — `seat.confirm.mode=conditional` · θ = 30초 · M = 10초.
 * 판정(위에서부터 첫 일치 · :now = 앱 Clock): 없음 404 not_found → 남의 것 403 not_owned → expires_at ≤ :now 409 HOLD_EXPIRED expired →
 * expires_at − :now ≤ θ 409 HOLD_EXPIRED insufficient → 그 밖 200 ok. 200 = {holdId, expiresAt, payBy, payWithinSeconds, extended=false}.
 * 홀드 at NOW → e = NOW + 5분(TTL). 읽기 전용 · 쓰기 0은 행 전후 대조 + JDBC 경계 탭(Adr016SqlTap — setReadOnly 상태 · 문장)으로 본다.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        M_CONDITIONAL, P16_THETA_30S, P16_MARGIN_10S,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr016SqlTapConfig::class)
class Adr016ConditionalPaymentStartTest : Adr016Base() {

    @BeforeEach
    fun tapReset() = Adr016SqlTap.reset()

    private fun psStatements(block: () -> Resp): Pair<Resp, List<SqlEvent>> {
        Adr016SqlTap.log.clear()
        Adr016SqlTap.recording = true
        val r = try { block() } finally { Adr016SqlTap.recording = false }
        return r to Adr016SqlTap.log.filter { isHttp(it.thread) }
    }

    private fun assert200Body(r: Resp, hold: Long, e: Instant, now: Instant, why: String) {
        assertEquals(200, r.status, "$why: $r")
        assertEquals(PAY_START_KEYS, r.keys(), "$why — 200 본문 키: $r")
        assertEquals(hold, r.long("holdId"), why)
        assertEquals(e, r.instant("expiresAt"), "$why — expiresAt = 지금 expires_at(① 확인만 — 연장 없음)")
        assertEquals(e.minus(MARGIN_10), r.instant("payBy"), "$why — payBy = expiresAt − M(N16-3)")
        assertEquals(expectedPayWithin(e.minus(MARGIN_10), now), r.long("payWithinSeconds"), "$why — payWithinSeconds = ⌊(payBy − now)/1초⌋")
        assertFalse(r.bool("extended"), "$why — ①은 extended = false(확인 ≠ 보장)")
    }

    // ── θ 경계 · 판정 순서 ───────────────────────────────────────────────────────────────────

    @Test
    fun `θ 경계 — 남은 θ+1ms 200 ok · 남은 = θ 409 insufficient · expires_at = now 409 expired · 그 뒤 expired`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val e = expiresAtOf(hold)
        assertEquals(NOW.plus(TTL), e, "준비 — e = NOW + TTL")

        var p0 = payStartCounts()
        val tOk = e.minus(THETA_30).minusMillis(1)
        clock.set(tOk)
        val ok = paymentStart(hold, x)
        assert200Body(ok, hold, e, tOk, "남은 θ+1ms")
        assertEquals(20L, ok.long("payWithinSeconds"), "남은 θ+1ms — payWithin = ⌊(30.001 − 10)초⌋ = 20")
        assertPayStartDelta(p0, "ok", "남은 θ+1ms")

        p0 = payStartCounts()
        clock.set(e.minus(THETA_30))
        assertRejected(paymentStart(hold, x), 409, "HOLD_EXPIRED", "남은 = θ(경계 포함 거부 — N16-2)")
        assertPayStartDelta(p0, "insufficient", "남은 = θ")

        p0 = payStartCounts()
        clock.set(e.minusMillis(1))
        assertRejected(paymentStart(hold, x), 409, "HOLD_EXPIRED", "남은 1ms(0 < 남은 ≤ θ)")
        assertPayStartDelta(p0, "insufficient", "남은 1ms")

        p0 = payStartCounts()
        clock.set(e)
        assertRejected(paymentStart(hold, x), 409, "HOLD_EXPIRED", "expires_at = :now")
        assertPayStartDelta(p0, "expired", "expires_at = :now(만료가 insufficient보다 먼저)")

        p0 = payStartCounts()
        clock.set(e.plusSeconds(1))
        assertRejected(paymentStart(hold, x), 409, "HOLD_EXPIRED", "만료 1초 뒤(배치 전 — 행 남음)")
        assertPayStartDelta(p0, "expired", "만료 1초 뒤")
        assertEquals(e, expiresAtOf(hold), "어느 호출도 expires_at을 바꾸지 않는다")
    }

    @Test
    fun `판정 순서 — 없음 404 not_found · 남의 것 403 not_owned(만료 · θ 미만이어도 소유 검사가 먼저)`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val e = expiresAtOf(hold)

        var p0 = payStartCounts()
        assertRejected(paymentStart(NO_SUCH_HOLD, x), 404, "HOLD_NOT_FOUND", "없는 홀드")
        assertPayStartDelta(p0, "not_found", "없는 홀드")

        for ((label, at) in listOf("유효" to NOW.plusSeconds(60), "θ 미만" to e.minusSeconds(5), "만료" to e.plusSeconds(1))) {
            clock.set(at)
            p0 = payStartCounts()
            assertRejected(paymentStart(hold, y), 403, "HOLD_NOT_OWNED", "남의 홀드 · $label")
            assertPayStartDelta(p0, "not_owned", "남의 홀드 · $label")
        }
    }

    // ── 응답 필드(N16-3) ─────────────────────────────────────────────────────────────────────

    @Test
    fun `N16-3 — 200 payBy = expiresAt − M · payWithinSeconds = ⌊(payBy − now)÷1초⌋(소수 버림)`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val e = expiresAtOf(hold)
        val t = NOW.plus(Duration.ofMinutes(1)).plusMillis(300)
        clock.set(t)
        val r = paymentStart(hold, x)
        assert200Body(r, hold, e, t, "+1분 300ms")
        assertEquals(229L, r.long("payWithinSeconds"), "(5분 − 10초 − 1분 300ms) = 229.7초 → 229")
        assertTrue(!r.instant("payBy").isAfter(r.instant("expiresAt")), "payBy ≤ expiresAt")
    }

    @Test
    fun `N16-3 — ① 선점 201은 holdId · seatId · expiresAt + payBy · payWithinSeconds`() {
        val seat = createSeat(1).id!!
        val r = holdHttp(seat, x)
        assertEquals(201, r.status, "$r")
        assertEquals(HOLD_KEYS_PAY, r.keys(), "① 선점 201 본문 키: $r")
        val e = r.instant("expiresAt")
        assertEquals(NOW.plus(TTL), e)
        assertEquals(e.minus(MARGIN_10), r.instant("payBy"), "payBy = expiresAt − M")
        assertEquals(290L, r.long("payWithinSeconds"), "payWithinSeconds = 5분 − 10초 = 290")

        // 시계가 초 경계 밖(ms)일 때도 같은 앱 Clock 기준(expiresAt · payBy · now가 같은 시계)
        val seat2 = createSeat(2).id!!
        clock.set(NOW.plusMillis(700))
        val r2 = holdHttp(seat2, x)
        assertEquals(201, r2.status, "$r2")
        assertEquals(NOW.plusMillis(700).plus(TTL), r2.instant("expiresAt"))
        assertEquals(r2.instant("expiresAt").minus(MARGIN_10), r2.instant("payBy"))
        assertEquals(290L, r2.long("payWithinSeconds"))
    }

    // ── N16-1 쓰기 0 · 읽기 전용 ───────────────────────────────────────────────────────────────

    @Test
    fun `N16-1 — 200 · 거부 모두 행 무변경 · HTTP 스레드 쓰기 · 잠금 문장 0 · seat_hold 읽기는 읽기 전용 연결`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val e = expiresAtOf(hold)
        val cases = listOf(
            Triple("ok", NOW.plusSeconds(60), x to hold),
            Triple("insufficient", e.minus(THETA_30), x to hold),
            Triple("not_owned", NOW.plusSeconds(60), y to hold),
            Triple("not_found", NOW.plusSeconds(60), x to NO_SUCH_HOLD),
            Triple("expired", e, x to hold),
        )
        val expectStatus = mapOf("ok" to 200, "insufficient" to 409, "not_owned" to 403, "not_found" to 404, "expired" to 409)
        for ((label, at, who) in cases) {
            clock.set(at)
            val before = rowsSnapshot()
            val (r, sql) = psStatements { paymentStart(who.second, who.first) }
            assertEquals(expectStatus.getValue(label), r.status, "$label: $r")
            assertEquals(before, rowsSnapshot(), "$label — 홀드 · 좌석 · 쿼터 · 예약 행(표지 extended_at 포함) 무변경(N16-1)")
            assertTrue(sql.none { isWriteOrLock(it.sql) }, "$label — HTTP 스레드 쓰기 · 잠금 문장 0이어야 한다(잠금 없는 SELECT만): ${sql.map { it.sql }}")
            val holdReads = sql.filter { it.sql.contains("from seat_hold") }
            assertTrue(holdReads.isNotEmpty(), "$label — seat_hold 읽기가 탭에 보이지 않는다(탭 미장착?): ${sql.map { it.sql }}")
            assertTrue(holdReads.all { it.readOnly }, "$label — seat_hold 읽기는 읽기 전용 트랜잭션 연결(setReadOnly(true))에서: $holdReads")
        }
        assertNull(extendedAtOf(hold), "① 확인은 표지 extended_at을 남기지 않는다")
        assertEquals("HELD", seatStatusOf(seat)); assertEquals(1, cntOf(x))
    }

    // ── N16-4 거부 뒤 확정 결과 = 거부 없는 판 ──────────────────────────────────────────────────

    /** 확정 결과 요약(상태 · 코드 · 좌석 · 홀드 유무 · 그 좌석 CONFIRMED 수) */
    private fun outcome(r: Resp, seat: Long, hold: Long) = listOf(r.status, r.code, seatStatusOf(seat), holdExists(hold), confirmedOn(seat))

    /** 같은 시각 · 같은 주인의 홀드 둘(A = payment-start 거부를 먼저 받음 · B = 대조) — 확정 결과가 같아야 한다 */
    private fun pairCase(label: String, at: Instant, rejecter: Long, expectStatus: Int, expectCode: String, confirmExpect: Int) {
        val sA = createSeat(1).id!!; val sB = createSeat(2).id!!
        val hA = holdOk(sA, x); val hB = holdOk(sB, x)
        clock.set(at)
        val before = rowsSnapshot()
        assertRejected(paymentStart(hA, rejecter), expectStatus, expectCode, "$label 거부")
        assertEquals(before, rowsSnapshot(), "$label — 거부는 상태를 바꾸지 않는다(N16-4)")
        val rA = confirmHttp(hA, x, "pay-a")
        val rB = confirmHttp(hB, x, "pay-b")
        assertEquals(confirmExpect, rB.status, "$label — 대조 확정 전제: $rB")
        assertEquals(outcome(rB, sB, hB), outcome(rA, sA, hA), "$label — 거부 뒤 확정 결과 = 거부 없는 판(N16-4)")
    }

    @Test
    fun `N16-4 — 403 not_owned 뒤 주인 확정은 거부 없는 판과 같다(200)`() =
        pairCase("not_owned", NOW.plusSeconds(60), y, 403, "HOLD_NOT_OWNED", 200)

    @Test
    fun `N16-4 — 409 insufficient(남은 = θ) 뒤 확정은 거부 없는 판과 같다(아직 유효 — 200)`() =
        pairCase("insufficient", NOW.plus(TTL).minus(THETA_30), x, 409, "HOLD_EXPIRED", 200)

    @Test
    fun `N16-4 — 409 expired 뒤 확정은 거부 없는 판과 같다(409 HOLD_EXPIRED)`() =
        pairCase("expired", NOW.plus(TTL), x, 409, "HOLD_EXPIRED", 409)

    @Test
    fun `N16-4 — 404 not_found 뒤 확정은 거부 없는 판과 같다(확정된 홀드 — 다시 확정 404)`() {
        val sA = createSeat(1).id!!; val sB = createSeat(2).id!!
        val hA = holdOk(sA, x); val hB = holdOk(sB, x)
        clock.set(NOW.plusSeconds(60))
        assertEquals(200, confirmHttp(hA, x, "pay-a").status); assertEquals(200, confirmHttp(hB, x, "pay-b").status)
        val before = rowsSnapshot()
        assertRejected(paymentStart(hA, x), 404, "HOLD_NOT_FOUND", "확정된 홀드 payment-start")
        assertEquals(before, rowsSnapshot(), "404 거부는 상태를 바꾸지 않는다")
        val rA = confirmHttp(hA, x, "pay-a2")
        val rB = confirmHttp(hB, x, "pay-b2")
        assertEquals(404, rB.status, "대조 전제: $rB")
        assertEquals(outcome(rB, sB, hB), outcome(rA, sA, hA), "404 거부 뒤 확정 = 거부 없는 판")
    }

    @Test
    fun `N16-2 · N16-4 — 200 뒤에도 홀드는 e에 만료된다(확인 ≠ 보장) · e 전 확정은 200`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val e = expiresAtOf(hold)
        clock.set(NOW.plusSeconds(60))
        assertEquals(200, paymentStart(hold, x).status)
        assertEquals(e, expiresAtOf(hold), "200은 expires_at을 늘리지 않는다")
        clock.set(e.minusMillis(1))
        assertEquals(200, confirmHttp(hold, x).status, "e 직전 확정은 성공")
    }
}

/*
 * ① θ = 0 판(명세 §5 'θ > 0 판과 θ = 0 판') — θ · M = 0 · 0 명시(기본은 ADR-016 §결정으로 1초 · 1초). 남은 시간 = θ(0)은 곧 expires_at = :now → expired가 먼저. N16-5 묶음(확정 지표)도 ①에서 여기서 돈다.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CONDITIONAL, "$THETA_KEY=0s", "$MARGIN_KEY=0s"],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr016ConditionalZeroThetaTest : Adr016ConfirmMetricsScenarios("conditional") {

    @Test
    fun `θ = 0 경계 — expires_at = now 409 expired(insufficient 아님) · 1ms 남으면 200 · payBy = expiresAt · payWithinSeconds 0`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val e = expiresAtOf(hold)

        var p0 = payStartCounts()
        clock.set(e)
        assertRejected(paymentStart(hold, x), 409, "HOLD_EXPIRED", "θ=0 · expires_at = :now")
        assertPayStartDelta(p0, "expired", "θ=0 · 남은 0 = 만료")

        p0 = payStartCounts()
        clock.set(e.minusMillis(1))
        val r = paymentStart(hold, x)
        assertEquals(200, r.status, "θ=0 · 남은 1ms > θ: $r")
        assertEquals(PAY_START_KEYS, r.keys(), "$r")
        assertEquals(e, r.instant("expiresAt"))
        assertEquals(e, r.instant("payBy"), "M = 0 → payBy = expiresAt")
        assertEquals(0L, r.long("payWithinSeconds"), "⌊1ms/1초⌋ = 0")
        assertFalse(r.bool("extended"))
        assertPayStartDelta(p0, "ok", "θ=0 · 남은 1ms")

        p0 = payStartCounts()
        clock.set(e.minusSeconds(1).minusMillis(1))
        assertEquals(1L, paymentStart(hold, x).long("payWithinSeconds"), "⌊1.001초⌋ = 1")
        assertPayStartDelta(p0, "ok", "θ=0 · 남은 1.001초")

        p0 = payStartCounts()
        clock.set(e.plusSeconds(30))
        assertRejected(paymentStart(hold, x), 409, "HOLD_EXPIRED", "θ=0 · 만료 뒤")
        assertPayStartDelta(p0, "expired", "θ=0 · 만료 뒤")
        assertEquals(e, expiresAtOf(hold))
    }

    @Test
    fun `θ = 0 · M = 0 명시 — 선점 201 payBy = expiresAt · payWithinSeconds = TTL 초`() {
        val r = holdHttp(createSeat(1).id!!, x)
        assertEquals(201, r.status, "$r")
        assertEquals(HOLD_KEYS_PAY, r.keys(), "① 선점 201 본문 키(기본값): $r")
        assertEquals(r.instant("expiresAt"), r.instant("payBy"), "M = 0")
        assertEquals(TTL.seconds, r.long("payWithinSeconds"))
    }

    @Test
    fun `θ = 0 — ① payment-start가 등록돼 있다(옛 계약 '① 404'가 200으로 바뀜 · 명세 §1 · 7)`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val before = rowsSnapshot()
        val r = paymentStart(hold, x)
        assertEquals(200, r.status, "① payment-start 200: $r")
        assertEquals(hold, r.long("holdId"))
        assertEquals(before, rowsSnapshot(), "N16-1 행 무변경")
    }
}
