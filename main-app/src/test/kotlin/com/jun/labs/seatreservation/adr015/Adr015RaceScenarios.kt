package com.jun.labs.seatreservation.adr015

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * ADR-015 §설계-8.1 재현 칸 × 모드(blind — 구현 diff 미열람 · 명세 + ADR §설계-2 · 6 · 8.1에서 출발).
 * 순서는 래치(Adr015Support — L-C · L-B/L-B′ · L-E)로 결정적으로 만든다. 확정 · 선점 · 연장은 HTTP(실제 Tomcat · 컨트롤러 · 에러 처리기),
 * 배치는 ExpireHoldsUseCase.expire()를 배치 스레드에서. 시계는 MutableClock(NOW = 2026-09-27T10:00Z · TTL 5분 → te = NOW + 5분).
 *
 * 기대(§설계-2 15-02 실측 · §설계-8.1 '기대'):
 *   current: FM1 진 X 500 · FM2 V12 있음 409 SEAT_NOT_HELD + unique_violation +1 / 없음 500 · FM3 X 200 + 배치 틱 롤백(tick{failed} +1) · 판정기 0
 *   새 모드(①~④ + A′): FM1 404 · FM2 404(V12 유무 무관) · FM3 X 200 + 틱 커밋(tick{ok} +1 · 예외 없음) · FM5(④) 지우지 않음 · 판정기 0 · 5xx 0
 */
abstract class Adr015RaceScenarios(val mode: String, val v12: Boolean) : Adr015Base() {

    val isCurrent get() = mode == "current"

    @BeforeEach
    fun variantPrecondition() {
        // 'V12 없음' 판은 인덱스가 정말 없어야 한다(그린 위장 점검 — 준비가 안 됐으면 그 칸의 결과는 뜻이 없다)
        if (!v12) assertFalse(indexExists(V12_INDEX), "'V12 없음' 판 준비 실패 — $V12_INDEX 가 남아 있다")
    }

    private fun assertNo5xx(vararg rs: Resp) = rs.forEach { assertTrue(it.status < 500, "[$mode] 5xx 금지(N4): $it") }

    // ---- FM1 ----------------------------------------------------------------------------------

    /** FM1(§설계-8.1): X 확정 L-C(:now < te) → 시계 te 뒤로 → 배치 끝까지(커밋) → Y 선점(커밋) → L-C 풂 */
    @Test
    fun `FM1 — X 확정 L-C 뒤 배치 만료 · Y 선점 커밋 · X 진행`() {
        val seat = createSeat(1).id!!
        val holdX = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))

        val lc = Adr015Taps.arm(Adr015Gate("L-C"), L_C)
        val xf = confirmAsync(holdX, x)
        reachedOrFail(lc)

        clock.set(NOW.plus(Duration.ofMinutes(6)))
        val b = batchAsync().join()
        assertTrue(b.isSuccess, "[$mode] FM1 배치는 끝까지 커밋해야 한다(X는 아직 아무 행도 쥐지 않았다): ${b.exceptionOrNull()}")
        val yr = holdHttp(seat, y)
        assertEquals(201, yr.status, "[$mode] FM1 준비 — 배치가 좌석을 풀어 Y가 선점해야 한다: $yr · ${dbState()}")
        val holdY = yr.json!!.get("holdId").asLong()

        lc.release()
        val xr = xf.done()

        if (isCurrent) {
            assertEquals(500, xr.status, "current FM1 = 우연한 가드 → 500(15-02 실측): $xr")
        } else {
            assertEquals(404, xr.status, "[$mode] FM1 진 X = 404(C1 · P1 · O2 0행): $xr")
            assertEquals("HOLD_NOT_FOUND", xr.code, "[$mode] FM1 코드: $xr")
        }
        assertEquals(0, reservationCount(), "[$mode] X 예약 없음: ${dbState()}")
        assertEquals("HELD", seatStatusOf(seat), "[$mode] 좌석은 Y의 HELD(N3)")
        assertTrue(holdExists(holdY), "[$mode] Y 홀드 그대로")
        assertVerdictClean("[$mode] FM1")
    }

    // ---- FM2 ----------------------------------------------------------------------------------

    /** FM2(§설계-8.1): X1 · X2 L-C(같은 홀드) → X1 풂 · 커밋 → X2 풂 */
    @Test
    fun `FM2 — 같은 홀드 이중 확정`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(1)))
        val uv0 = counter(M_UNIQUE_VIOLATION) ?: 0.0

        val lc = Adr015Taps.arm(Adr015Gate("L-C×2", capacity = 2), L_C)
        val x1 = confirmAsync(hold, x, "pay-x1")
        reachedOrFail(lc, 1, "L-C(X1)")
        val x2 = confirmAsync(hold, x, "pay-x2")
        reachedOrFail(lc, 2, "L-C(X2)")

        lc.release(0)
        val r1 = x1.done()
        assertEquals(200, r1.status, "[$mode] FM2 X1 확정 성공: $r1")
        lc.release(1)
        val r2 = x2.done()

        when {
            isCurrent && v12 -> {
                assertEquals(409, r2.status, "current + V12 FM2 = 늦은 INSERT 23505 → 매핑 409(N6 · §설계-4): $r2")
                assertEquals("SEAT_NOT_HELD", r2.code, "current + V12 FM2 코드: $r2")
                assertEquals(uv0 + 1, requireCounter(M_UNIQUE_VIOLATION), "current + V12 FM2 → $M_UNIQUE_VIOLATION +1")
            }
            isCurrent -> assertEquals(500, r2.status, "current · V12 없음 FM2 = 고아 DELETE 0행 → 500(§설계-2 · #13): $r2")
            else -> {
                assertEquals(404, r2.status, "[$mode] FM2 두 번째 = 404(N2 — 홀드 행에서 기다린 뒤 0행 · V12 유무 무관): $r2")
                assertEquals("HOLD_NOT_FOUND", r2.code, "[$mode] FM2 코드: $r2")
                assertEquals(uv0, counter(M_UNIQUE_VIOLATION) ?: 0.0, "[$mode] 새 모드 $M_UNIQUE_VIOLATION 0(기대 0 — §설계-8.1)")
            }
        }
        assertEquals(1, confirmedOn(seat), "[$mode] 같은 좌석 CONFIRMED 1건(N2 · N6): ${dbState()}")
        assertEquals("RESERVED", seatStatusOf(seat))
        assertVerdictClean("[$mode] FM2")
    }

    // ---- FM3 ----------------------------------------------------------------------------------

    /** FM3(§설계-8.1): X 확정 L-C(:now < te) → 시계 te 뒤로 → 배치 L-B(current = JPA 이음새 · 새 모드 = L-B′) → X 풂 · 커밋 → 배치 풂 */
    @Test
    fun `FM3 — 확정이 먼저 커밋 · 배치는 L-B 뒤 진행`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val ok0 = counter(TICK_METRIC, "outcome", "ok") ?: 0.0
        val failed0 = counter(TICK_METRIC, "outcome", "failed") ?: 0.0

        val lc = Adr015Taps.arm(Adr015Gate("L-C"), L_C)
        val xf = confirmAsync(hold, x)
        reachedOrFail(lc)

        clock.set(NOW.plus(Duration.ofMinutes(6)))
        val lb = Adr015Taps.arm(Adr015Gate("L-B"), L_B)
        val bf = batchAsync()
        reachedOrFail(lb, what = if (isCurrent) "L-B(JPA)" else "L-B′(A′ — 배치 스레드의 첫 delete from seat_hold)")

        lc.release()
        val xr = xf.done()
        assertEquals(200, xr.status, "[$mode] FM3 X 확정 성공: $xr")
        lb.release()
        val br = bf.join()

        if (isCurrent) {
            assertTrue(br.isFailure, "current FM3 = 배치 틱 전체 롤백(15-02 실측)")
            assertEquals(failed0 + 1, requireCounter(TICK_METRIC, "outcome", "failed"), "current FM3 tick{failed} +1")
        } else {
            assertTrue(br.isSuccess, "[$mode] FM3 A′ 배치는 그 쌍을 0행으로 지나가고 틱을 커밋한다(N5): ${br.exceptionOrNull()}")
            assertEquals(ok0 + 1, requireCounter(TICK_METRIC, "outcome", "ok"), "[$mode] FM3 tick{ok} +1")
            assertEquals(failed0, counter(TICK_METRIC, "outcome", "failed") ?: 0.0, "[$mode] FM3 tick{failed} 0")
        }
        assertEquals("RESERVED", seatStatusOf(seat), "[$mode] 예약 좌석이 AVAILABLE이 되지 않는다(N5): ${dbState()}")
        assertEquals(1, confirmedOn(seat))
        assertEquals(1, cntOf(x), "[$mode] cnt = 홀드 + 확정(N5 — 감소 = 실제로 지운 행)")
        assertVerdictClean("[$mode] FM3")
    }

    // ---- 확정 직후 선점 ---------------------------------------------------------------------------

    @Test
    fun `확정 커밋 직후 같은 좌석 선점은 409`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(1)))
        val cr = confirmHttp(hold, x)
        assertEquals(200, cr.status, "[$mode] 확정: $cr")
        val yr = holdHttp(seat, y)
        assertEquals(409, yr.status, "[$mode] 예약된 좌석 선점은 409(§설계-2 '확정 커밋 직후 선점'): $yr")
        assertEquals("SEAT_NOT_AVAILABLE", yr.code)
        assertEquals(0, holdCount(), "[$mode] 예약된 좌석 위에 홀드가 생기지 않는다")
        assertVerdictClean("[$mode] 확정 직후 선점")
    }

    // ---- A 에러 순서(ConfirmReservationServiceTest와 같은 계약 · HTTP) ------------------------------------
    // 보존 칸: 손잡이를 모르는 지금 코드(current로 돈다)에서도 green이 정상이다 — 새 모드가 이 순서를 깨면 red가 된다.

    @Test
    fun `A 순서 — 없는 홀드 404`() {
        val r = confirmHttp(9_999_999, x)
        assertEquals(404, r.status, "[$mode] $r"); assertEquals("HOLD_NOT_FOUND", r.code)
    }

    @Test
    fun `A 순서 — 남의 홀드 403 · DB 무변경(만료여도 403이 먼저)`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val r = confirmHttp(hold, y)
        assertEquals(403, r.status, "[$mode] $r"); assertEquals("HOLD_NOT_OWNED", r.code)
        clock.set(NOW.plus(Duration.ofMinutes(6)))
        val r2 = confirmHttp(hold, y)
        assertEquals(403, r2.status, "[$mode] 남의 것 + 만료 → 403(404→403→409 순서): $r2")
        assertEquals("HELD", seatStatusOf(seat)); assertTrue(holdExists(hold)); assertEquals(0, reservationCount())
    }

    @Test
    fun `A 순서 — 만료 정각은 409 HOLD_EXPIRED · DB 무변경(좌석이 HELD가 아니어도 HOLD_EXPIRED가 먼저)`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(TTL))
        val r = confirmHttp(hold, x)
        assertEquals(409, r.status, "[$mode] $r"); assertEquals("HOLD_EXPIRED", r.code)
        assertEquals("HELD", seatStatusOf(seat)); assertTrue(holdExists(hold)); assertEquals(0, reservationCount())

        val seat2 = createSeat(2)
        val h2 = insertHold(seat2, x, NOW, NOW.plus(TTL)) // 좌석 AVAILABLE + 만료 정각
        val r2 = confirmHttp(h2, x)
        assertEquals("HOLD_EXPIRED", r2.code, "[$mode] 만료 + 좌석 HELD 아님 → HOLD_EXPIRED가 먼저: $r2")
    }

    @Test
    fun `A 순서 — 홀드 유효 · 좌석 HELD 아님은 409 SEAT_NOT_HELD · DB 무변경`() {
        val seat = createSeat(1)
        val hold = insertHold(seat, x, NOW, NOW.plus(TTL))
        val r = confirmHttp(hold, x)
        assertEquals(409, r.status, "[$mode] $r"); assertEquals("SEAT_NOT_HELD", r.code)
        assertEquals("AVAILABLE", seatStatusOf(seat.id!!)); assertEquals(1, holdCount()); assertEquals(0, reservationCount())
    }

    @Test
    fun `확정 성공 — 홀드 삭제 · 좌석 RESERVED · 예약 CONFIRMED(created_at = 앱 Clock now)`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val r = confirmHttp(hold, x, "pay-1")
        assertEquals(200, r.status, "[$mode] $r")
        assertEquals("CONFIRMED", r.json!!.get("status").asText())
        assertEquals("RESERVED", seatStatusOf(seat)); assertEquals(0, holdCount())
        val row = jdbcTemplate.queryForMap("SELECT seat_id, user_id, payment_uid, status, created_at FROM reservation WHERE id = ?", r.json!!.get("reservationId").asLong())
        assertEquals(seat, (row["seat_id"] as Number).toLong()); assertEquals(x, (row["user_id"] as Number).toLong())
        assertEquals("pay-1", row["payment_uid"]); assertEquals("CONFIRMED", row["status"])
        assertEquals(clock.instant(), (row["created_at"] as Timestamp).toInstant(), "[$mode] created_at = :now(앱 Clock)")
        assertEquals(1, cntOf(x), "[$mode] 확정은 쿼터 행을 바꾸지 않는다")
        assertVerdictClean("[$mode] 확정 성공")
    }

    /** ④ API는 conditional-extend에서만 등록(§3.4) — 그 밖의 모드는 404(핸들러 없음: 유효한 내 홀드여도) · DB 무변경 */
    @Test
    fun `payment-start API — conditional-extend가 아니면 없다(404)`() {
        if (mode == "conditional-extend") return
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val before = expiresAtOf(hold)
        val r = paymentStart(hold, x)
        assertEquals(404, r.status, "[$mode] payment-start는 이 모드에 없다: $r")
        assertTrue(r.code != "HOLD_NOT_FOUND", "[$mode] 404는 API 없음이어야 한다(유효한 홀드에 HOLD_NOT_FOUND가 아님): $r")
        assertEquals(before, expiresAtOf(hold))
    }
}

/** 새 모드(①~④)만의 칸 — N8 문장 · 잠금 순서 · 교착(권장판 40P01 0) */
abstract class Adr015NewModeScenarios(mode: String, v12: Boolean) : Adr015RaceScenarios(mode, v12) {

    /** N8(§3.5 표): 새 모드 확정의 쓰기/잠금 순서 = 홀드 행 → 좌석 행 → 예약 INSERT(FK). current(INSERT → 좌석 → 홀드)와 다르다. */
    @Test
    fun `N8 — 확정 문장 순서는 홀드 행 → 좌석 행 → 예약 INSERT`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(1)))
        Adr015Taps.recording = true
        val lc = Adr015Taps.arm(Adr015Gate("L-C"), L_C)
        val f = confirmAsync(hold, x)
        reachedOrFail(lc)
        lc.release()
        val r = f.done()
        Adr015Taps.recording = false
        assertEquals(200, r.status, "[$mode] $r")
        val log = Adr015Taps.logOf(lc.threads[0]!!)
        val iHold = log.indexOfFirst { touchesHoldRowForWrite(it) }
        val iSeat = log.indexOfFirst { touchesSeatRowForWrite(it) }
        val iIns = log.indexOfFirst { isReservationInsert(it) }
        assertTrue(iHold >= 0 && iSeat > iHold && iIns > iSeat, "[$mode] 순서 홀드($iHold) → 좌석($iSeat) → INSERT($iIns) 위반 — 확정 스레드 문장:\n  " + log.joinToString("\n  "))
    }

    /**
     * 교착 점검(§3.5 '확정(①~④) ↔ A′ 배치 — 닫히지 않는다' · N8 · #9): 확정이 홀드 행을 쥔 채 좌석 문장 앞(L-S)에서 멈추고 → 배치(A′)가 B3에서 그 홀드 행을
     * 기다리는 것을 관측 → 확정 풂. 기대: 확정 200 · 배치 틱 커밋 · 40P01 0 · 판정기 0.
     */
    @Test
    fun `교착 — 확정이 홀드 행을 쥔 동안 A′ 배치가 기다려도 40P01 없음`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val ok0 = counter(TICK_METRIC, "outcome", "ok") ?: 0.0

        val ls = Adr015Taps.arm(Adr015Gate("L-S"), L_S)
        val xf = confirmAsync(hold, x)
        reachedOrFail(ls, what = "L-S(홀드 행을 쥔 뒤 · 좌석 문장 앞)")

        clock.set(NOW.plus(Duration.ofMinutes(6)))
        val bf = batchAsync()
        val waited = awaitLockWait("delete from seat_hold")
        ls.release()
        val xr = xf.done()
        val br = bf.join()

        assertTrue(waited, "[$mode] 배치가 확정이 쥔 홀드 행에서 기다리지 않았다(확정이 L-S 전에 홀드 행을 잡지 않았거나 배치가 B3을 보내지 않는다) · 확정 $xr · 배치 ${br.exceptionOrNull()}")
        assertEquals(200, xr.status, "[$mode] 확정 성공: $xr")
        assertTrue(br.isSuccess, "[$mode] 배치 틱 커밋: ${br.exceptionOrNull()}")
        assertFalse("40P01" in sqlStates(br.exceptionOrNull()), "[$mode] 40P01 0(N8)")
        assertEquals(ok0 + 1, requireCounter(TICK_METRIC, "outcome", "ok"))
        assertVerdictClean("[$mode] 교착 점검")
    }
}

/** ④ 전용 칸 — FM5(연장을 잃음 · N10) */
abstract class Adr015ExtendScenarios(v12: Boolean) : Adr015NewModeScenarios("conditional-extend", v12) {

    /**
     * FM5(§설계-8.1): 연장 L-E(:now′ < te − minRemaining) → 시계 te 뒤로 → 배치 L-B′ → L-E 풂 · 연장 커밋 → 배치 풂 → 확정.
     * 기대: A′가 연장된 홀드를 지우지 않음(EPQ/새 스냅숏) · extension_lost 0 · 확정 200.
     */
    @Test
    fun `FM5 — 배치 후보 읽기 뒤 연장 커밋 · A′는 지우지 않는다`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val lost0 = counter(M_EXTENSION_LOST) ?: 0.0

        val le = Adr015Taps.arm(Adr015Gate("L-E"), L_E)
        val pf = paymentStartAsync(hold, x)
        reachedOrFail(le, what = "L-E(payment-start E1 앞)")

        val batchNow = NOW.plus(Duration.ofMinutes(5)).plusSeconds(30)
        clock.set(batchNow)
        val lb = Adr015Taps.arm(Adr015Gate("L-B′"), L_B)
        val bf = batchAsync()
        reachedOrFail(lb, what = "L-B′")

        le.release()
        val pr = pf.done()
        assertEquals(200, pr.status, "④ FM5 연장 200: $pr")
        val extended = NOW.plus(Duration.ofMinutes(4)).plus(WINDOW)
        assertEquals(extended, Instant.parse(pr.json!!.get("expiresAt").asText()), "④ 연장 = :now′ + window")
        lb.release()
        val br = bf.join()
        assertTrue(br.isSuccess, "④ FM5 배치 틱 커밋: ${br.exceptionOrNull()}")
        assertTrue(holdExists(hold), "④ FM5 연장된 홀드는 지워지지 않는다(N10): ${dbState()}")
        assertEquals("HELD", seatStatusOf(seat))
        assertEquals(lost0, counter(M_EXTENSION_LOST) ?: 0.0, "④ FM5 $M_EXTENSION_LOST 0")

        val cr = confirmHttp(hold, x)
        assertEquals(200, cr.status, "④ FM5 연장 뒤 확정(원래 te 뒤 · 연장 만료 전) 200: $cr")
        assertVerdictClean("④ FM5")
    }
}
