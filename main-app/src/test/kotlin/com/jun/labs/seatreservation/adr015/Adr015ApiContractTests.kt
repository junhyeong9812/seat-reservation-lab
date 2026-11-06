package com.jun.labs.seatreservation.adr015

import com.jun.labs.seatreservation.TestcontainersConfiguration
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
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * ④ `POST /api/holds/{holdId}/payment-start` 계약(§3.4 · N9 · Q16) — Adr015ExtendRaceTest와 같은 컨텍스트(window 2분 · 연장 하한 5초 · TTL 5분).
 * 홀드 at NOW → te = NOW + 5분. 첫 연장은 expires_at = :now + window · extended_at = :now. 실패 응답 본문 = {code, message}(지금 처리기).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_EXTEND, P_WINDOW, P_MIN_REMAINING],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015PaymentStartContractTest : Adr015Base() {

    private fun extendedAt(holdId: Long): Instant? = jdbcTemplate.queryForObject(
        "SELECT extended_at FROM seat_hold WHERE id = ?", Timestamp::class.java, holdId,
    )?.toInstant()

    private fun body(r: Resp): Pair<Long, Instant> = r.json!!.get("holdId").asLong() to Instant.parse(r.json!!.get("expiresAt").asText())

    private fun extendCount(result: String) = counter(M_EXTEND_RESULT, "result", result) ?: 0.0

    @Test
    fun `첫 호출 200 + 연장 · 좌석 · 쿼터 무변경 · extend{extended} +1`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val at = NOW.plus(Duration.ofMinutes(4))
        clock.set(at)
        val e0 = extendCount("extended")
        val r = paymentStart(hold, x)
        assertEquals(200, r.status, "$r")
        assertEquals(hold to at.plus(WINDOW), body(r), "200 {holdId, expiresAt = :now + window}")
        assertEquals(at.plus(WINDOW), expiresAtOf(hold))
        assertEquals(at, extendedAt(hold), "표지 extended_at = :now(V13)")
        assertEquals("HELD", seatStatusOf(seat)); assertEquals(1, cntOf(x), "연장은 좌석 · 쿼터를 바꾸지 않는다(N9)")
        assertEquals(e0 + 1, requireCounter(M_EXTEND_RESULT, "result", "extended"))
    }

    @Test
    fun `재호출 200 · 같은 expiresAt(다시 늘지 않음) · extend{already} · 최대 수명 ≤ TTL + window`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val at = NOW.plus(Duration.ofMinutes(4))
        clock.set(at)
        assertEquals(200, paymentStart(hold, x).status)
        val a0 = extendCount("already")
        for (later in listOf(Duration.ofMinutes(4).plusSeconds(30), Duration.ofMinutes(5).plusSeconds(50))) {
            clock.set(NOW.plus(later))
            val r = paymentStart(hold, x)
            assertEquals(200, r.status, "재호출 at +$later: $r")
            assertEquals(at.plus(WINDOW), body(r).second, "재호출은 지금 expires_at(첫 연장 값) — 다시 늘지 않는다(N9 첫 연장만)")
        }
        assertEquals(at.plus(WINDOW), expiresAtOf(hold)); assertEquals(at, extendedAt(hold))
        assertEquals(a0 + 2, requireCounter(M_EXTEND_RESULT, "result", "already"))
        val maxLife = jdbcTemplate.queryForObject(
            "SELECT extract(epoch FROM max(expires_at - held_at))::bigint FROM seat_hold WHERE extended_at IS NOT NULL", Long::class.java,
        )!!
        assertTrue(maxLife <= TTL.plus(WINDOW).seconds, "최대 수명 ${maxLife}s ≤ TTL + window(N9)")
    }

    /** 동시 두 첫 호출: 호출 1을 E1 뒤 커밋 앞에서 멈춤(행 잠금 쥔 채) → 호출 2(다른 :now)가 E1에서 기다리는 것을 관측 → 호출 1 커밋 ⇒ 연장 1회 · 둘 다 같은 값 */
    @Test
    fun `동시 두 첫 호출 — 연장 1회 · 둘 다 200 같은 expiresAt`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val at1 = NOW.plus(Duration.ofMinutes(4))
        clock.set(at1)
        val e0 = extendCount("extended"); val a0 = extendCount("already")
        val gc = Adr015Taps.arm(Adr015Gate("E1 커밋 앞"), L_E_COMMIT)
        val f1 = paymentStartAsync(hold, x)
        reachedOrFail(gc, what = "호출 1 E1 커밋 앞")
        clock.set(at1.plusSeconds(30))
        val f2 = paymentStartAsync(hold, x)
        val waited = awaitLockWait("update seat_hold")
        gc.release()
        val r1 = f1.done(); val r2 = f2.done()
        assertTrue(waited, "호출 2가 호출 1의 행 잠금에서 기다리지 않았다(동시 칸 준비 실패) · $r1 · $r2")
        assertEquals(200, r1.status, "$r1"); assertEquals(200, r2.status, "$r2")
        assertEquals(at1.plus(WINDOW), body(r1).second); assertEquals(at1.plus(WINDOW), body(r2).second, "호출 2는 첫 연장 값(EPQ → 진단 ④)")
        assertEquals(at1.plus(WINDOW), expiresAtOf(hold)); assertEquals(at1, extendedAt(hold))
        assertEquals(e0 + 1, requireCounter(M_EXTEND_RESULT, "result", "extended")); assertEquals(a0 + 1, requireCounter(M_EXTEND_RESULT, "result", "already"))
    }

    /** Q16: 남은 시간 ≥ window면 200 + 지금 expires_at · 표지를 남기지 않음 → 뒤의 재호출이 연장을 받는다 */
    @Test
    fun `not_needed — 남은 시간이 window 이상이면 200 · 지금 값 · extended_at NULL 유지 · 뒤 호출은 연장`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val te = NOW.plus(TTL)
        val n0 = extendCount("not_needed")
        for (at in listOf(NOW.plus(Duration.ofMinutes(1)), te.minus(WINDOW))) { // 4분 남음 · 정확히 window 남음(⑥ >=)
            clock.set(at)
            val r = paymentStart(hold, x)
            assertEquals(200, r.status, "at $at: $r")
            assertEquals(te, body(r).second, "not_needed = 지금 expires_at")
            assertEquals(te, expiresAtOf(hold)); assertNull(extendedAt(hold), "not_needed는 표지를 남기지 않는다(Q16)")
        }
        assertEquals(n0 + 2, requireCounter(M_EXTEND_RESULT, "result", "not_needed"))
        val at = NOW.plus(Duration.ofMinutes(4))
        clock.set(at)
        val r = paymentStart(hold, x)
        assertEquals(200, r.status); assertEquals(at.plus(WINDOW), body(r).second, "표지가 없어 뒤 호출이 연장을 받는다")
        assertEquals(at, extendedAt(hold))
    }

    @Test
    fun `too_late — 남은 시간 ≤ 연장 하한이면 409 HOLD_EXPIRED · DB 무변경 · extend{too_late}`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val te = NOW.plus(TTL)
        val t0 = extendCount("too_late")
        for (at in listOf(te.minus(MIN_REMAINING), te.minusSeconds(3))) {
            clock.set(at)
            val r = paymentStart(hold, x)
            assertEquals(409, r.status, "at $at: $r"); assertEquals("HOLD_EXPIRED", r.code)
            assertEquals(te, expiresAtOf(hold)); assertNull(extendedAt(hold))
        }
        assertEquals(t0 + 2, requireCounter(M_EXTEND_RESULT, "result", "too_late"))
    }

    @Test
    fun `만료 409 HOLD_EXPIRED — 만료 정각 · 연장했어도 지난 홀드`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(TTL))
        val r = paymentStart(hold, x)
        assertEquals(409, r.status, "$r"); assertEquals("HOLD_EXPIRED", r.code)

        val seat2 = createSeat(2).id!!
        clock.set(NOW)
        val h2 = holdOk(seat2, x)
        val at = NOW.plus(Duration.ofMinutes(4))
        clock.set(at); assertEquals(200, paymentStart(h2, x).status)
        clock.set(at.plus(WINDOW))
        val r2 = paymentStart(h2, x)
        assertEquals(409, r2.status, "연장했어도 이미 지난 홀드는 409(진단 ③이 ④보다 먼저): $r2"); assertEquals("HOLD_EXPIRED", r2.code)
    }

    @Test
    fun `남의 것 403 · 없음 404 · DB 무변경`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val r = paymentStart(hold, y)
        assertEquals(403, r.status, "$r"); assertEquals("HOLD_NOT_OWNED", r.code)
        assertEquals(NOW.plus(TTL), expiresAtOf(hold)); assertNull(extendedAt(hold))
        val r2 = paymentStart(9_999_999, x)
        assertEquals(404, r2.status, "$r2"); assertEquals("HOLD_NOT_FOUND", r2.code)
    }

    @Test
    fun `연장 뒤 확정 — 원래 te 뒤 · 연장 만료 전이면 200`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        assertEquals(200, paymentStart(hold, x).status)
        clock.set(NOW.plus(Duration.ofMinutes(5)).plusSeconds(30))
        val cr = confirmHttp(hold, x)
        assertEquals(200, cr.status, "$cr")
        assertEquals("RESERVED", seatStatusOf(seat))
        assertVerdictClean("연장 뒤 확정")
    }
}

/*
 * V12 최후 방어선(§설계-4 · N6) — Adr015CurrentRaceTest와 같은 컨텍스트(current · 기본 위치 = V12 있음).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CURRENT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015UniqueIndexContractTest : Adr015Base() {

    private fun insertConfirmed(seatId: Long, userId: Long, status: String = "CONFIRMED") = jdbcTemplate.update(
        "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, 'pay', ?, now())",
        schedule.id, seatId, userId, status,
    )

    @Test
    fun `V12 정의 — 기본 위치 · reservation(seat_id) WHERE status = CONFIRMED 부분 유니크 · 유효`() {
        val def = jdbcTemplate.queryForList(
            "SELECT pg_get_indexdef(i.indexrelid) || ' valid=' || i.indisvalid FROM pg_index i WHERE i.indexrelid = to_regclass(?)", String::class.java, V12_INDEX,
        ).singleOrNull() ?: fail("$V12_INDEX 가 없다(V12 미적용)")
        val d = def.lowercase()
        assertTrue("unique" in d && "(seat_id)" in d && "where" in d && "confirmed" in d && "valid=true" in d, "V12 정의: $def")
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '12' AND success", Int::class.java))
    }

    @Test
    fun `같은 좌석 CONFIRMED 2건 직접 INSERT → 23505(인덱스 이름 일치)`() {
        val seat = createSeat(1, com.jun.labs.seatreservation.domain.SeatStatus.RESERVED).id!!
        insertConfirmed(seat, 10)
        val e = runCatching { insertConfirmed(seat, 11) }.exceptionOrNull()
        val sql = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        assertTrue(e is DataIntegrityViolationException && sql?.sqlState == "23505", "두 번째 CONFIRMED는 23505여야 한다: $e")
        assertTrue(sql!!.message.orEmpty().contains(V12_INDEX), "위반 인덱스 이름: ${sql.message}")
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE seat_id = ?", Int::class.java, seat))
    }

    @Test
    fun `CANCELED 이력은 막지 않는다(부분 인덱스)`() {
        val seat = createSeat(1, com.jun.labs.seatreservation.domain.SeatStatus.RESERVED).id!!
        insertConfirmed(seat, 10, "CANCELED")
        insertConfirmed(seat, 11, "CANCELED")
        insertConfirmed(seat, 12)
        assertEquals(3, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE seat_id = ?", Int::class.java, seat))
    }
}
