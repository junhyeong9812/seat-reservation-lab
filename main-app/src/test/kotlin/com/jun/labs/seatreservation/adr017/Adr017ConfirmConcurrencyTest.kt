package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015Gate
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.Adr015Taps
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.adr015.TapEvent
import com.jun.labs.seatreservation.adr015.TapKind
import com.jun.labs.seatreservation.adr015.isHttp
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * 동시성 — 명세 §8 A1 · A2 · §5 '같은 uid 동시 확정 2개 · 확정 ∥ 만료 배치 → 예약 + 거부 0 · 첫 결과 재생' · N17-1 · N17-3 · N17-5.
 * 모두 실제 HTTP(RANDOM_PORT) · 모드 none · ④ 기본.
 *
 * 래치(Adr015Taps — DataSource 수준 SQL 탭)로 '잠금을 쥔 채 멈춤'을 만든다. 래치 자리는 설계가 문장을 정한 곳만 쓴다:
 *  - C1 = `DELETE FROM seat_hold …`(설계-4.3 ⓓ '지금 확정 문장' · F1) — intent 잠금(ⓐ) 뒤에 온다.
 *  - 거부 뒤 재잠금 = 같은 요청 스레드의 두 번째 `… payment_intent … FOR UPDATE`(설계-4.3 ⓐ 'SELECT … FROM payment_intent WHERE uid = ? FOR UPDATE'
 *    · '거부: 롤백 → 새 트랜잭션에서 ⓐ를 다시 잡고').
 *  가정: intent 잠금은 설계 문장대로 `payment_intent`를 읽는 FOR UPDATE(또는 FOR NO KEY UPDATE) 문장이다. 다른 모양이면 래치에 닿지 않아
 *        reachedOrFail로 실패한다(조용히 통과하지 않는다).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr017ConfirmConcurrencyTest : Adr017Base() {

    private data class Prepared(val seatId: Long, val holdId: Long, val uid: String, val user: Long)

    private fun prepare(seatNo: Int, user: Long): Prepared {
        val seat = createSeat(seatNo).id!!
        val hold = holdOk(seat, user)
        val (uid, _) = startOk(hold, user)
        return Prepared(seat, hold, uid, user)
    }

    private val isC1: (TapEvent) -> Boolean = { e -> e.kind == TapKind.SQL && isHttp(e.thread) && e.sql!!.startsWith("delete from seat_hold") }

    private fun isIntentLock(s: String) = s.contains("payment_intent") && Adr015Taps.FOR_UPDATE.containsMatchIn(s)

    private fun sameOutcome(a: Resp, b: Resp, why: String) {
        assertEquals(a.status, b.status, "$why — 두 응답 상태가 같아야 한다(N17-3 재생): $a / $b")
        if (a.status == 200) assertEquals(a.long("reservationId"), b.long("reservationId"), "$why — 같은 reservationId")
        else assertEquals(a.code, b.code, "$why — 같은 거부 코드")
    }

    // ── 같은 uid 동시 확정(A1 · N17-1 · N17-3 · N17-5) ────────────────────────────────────────

    @Test
    fun `A1 · N17-1 — 같은 uid 확정 두 개를 동시에(10회) → 예약 1개 · 둘 다 200 같은 reservationId`() {
        repeat(10) { i ->
            val p = prepare(i + 1, 1_000L + i)
            val (r1, r2) = together({ confirmHttp(p.holdId, p.user, p.uid) }, { confirmHttp(p.holdId, p.user, p.uid) })
            assertEquals(200, r1.status, "[$i] $r1")
            assertEquals(200, r2.status, "[$i] 늦은 쪽은 404(홀드 없음)가 아니라 첫 결과 재생 200(설계-4.3 경합 해소): $r2")
            assertEquals(r1.long("reservationId"), r2.long("reservationId"), "[$i] 같은 reservationId")
            assertEquals(1, reservationsOfUid(p.uid), "[$i] 예약 1개(N17-1)")
            assertEquals(0, rejections(p.uid).size, "[$i] 거부 기록 0")
            assertSingleOutcome(p.uid, "[$i]")
        }
        assertEquals(0, taskCount())
    }

    @Test
    fun `A1 — 같은 uid 두 번째 요청은 intent 잠금에서 기다리고 · 첫 요청 커밋 뒤 재생 200`() {
        val p = prepare(1, x)
        val gate = Adr015Taps.arm(Adr015Gate("C1"), isC1)
        val f1 = confirmAsync(p.holdId, x, p.uid)
        try {
            reachedOrFail(gate, what = "첫 요청 C1(intent 잠금을 쥔 채)")
            val f2 = confirmAsync(p.holdId, x, p.uid)
            assertTrue(awaitLockWait("payment_intent"), "두 번째 요청은 payment_intent 행 잠금에서 기다려야 한다(설계-4.3 ⓐ 첫 잠금): ${dbState()}")
            assertFailsWith<TimeoutException>("첫 요청이 멈춘 동안 두 번째는 끝나지 않는다") { f2.get(500, TimeUnit.MILLISECONDS) }
            gate.release()
            val r1 = f1.done()
            val r2 = f2.done()
            assertEquals(200, r1.status, "$r1")
            assertEquals(200, r2.status, "기다린 쪽 = 재생 200(A1 · N17-3): $r2")
            assertEquals(r1.long("reservationId"), r2.long("reservationId"))
            assertEquals(1, reservationsOfUid(p.uid))
            assertSingleOutcome(p.uid, "A1 직렬화")
        } finally {
            gate.releaseAll()
        }
    }

    @Test
    fun `A1 — 다른 uid 두 확정은 서로 기다리지 않는다(첫 요청이 C1에서 멈춘 동안 두 번째가 200으로 끝남)`() {
        val a = prepare(1, x)
        val b = prepare(2, y)
        val gate = Adr015Taps.arm(Adr015Gate("C1"), isC1)
        val fa = confirmAsync(a.holdId, x, a.uid)
        try {
            reachedOrFail(gate, what = "첫 요청 C1")
            val rb = confirmAsync(b.holdId, y, b.uid).get(15, TimeUnit.SECONDS) // 기다림이 생기면 시간 초과로 드러난다
            assertEquals(200, rb.status, "다른 uid는 intent 잠금을 공유하지 않는다(A1): $rb")
            assertEquals(CONFIRMED, intentOrFail(b.uid).state)
            gate.release()
            val ra = fa.done()
            assertEquals(200, ra.status, "$ra")
            assertSingleOutcome(a.uid, "A1 a")
            assertSingleOutcome(b.uid, "A1 b")
        } finally {
            gate.releaseAll()
        }
    }

    @Test
    fun `A1 — 다른 uid 두 개 동시 확정(10회) → 모두 200`() {
        repeat(10) { i ->
            val a = prepare(2 * i + 1, 2_000L + i)
            val b = prepare(2 * i + 2, 3_000L + i)
            val (ra, rb) = together({ confirmHttp(a.holdId, a.user, a.uid) }, { confirmHttp(b.holdId, b.user, b.uid) })
            assertEquals(200, ra.status, "[$i] $ra")
            assertEquals(200, rb.status, "[$i] $rb")
            assertSingleOutcome(a.uid, "[$i] a")
            assertSingleOutcome(b.uid, "[$i] b")
        }
    }

    // ── 거부 → 재잠금 창(A2 · N17-5) ──────────────────────────────────────────────────────────

    @Test
    fun `A2 — 거부 롤백과 재잠금 사이에 같은 uid가 CONFIRMED를 만들면 재잠금 쪽은 재생 200 · 거부 기록 0`() {
        val p = prepare(1, x)
        val e = expiresAtOf(p.holdId)
        clock.set(e.plusSeconds(1)) // 첫 요청은 만료로 거부된다

        // 같은 스레드의 두 번째 intent 잠금 = 거부 기록 트랜잭션의 재잠금(첫 트랜잭션은 이미 롤백 — 잠금 없음)
        val seen = ConcurrentHashMap<String, AtomicInteger>()
        val relock = Adr015Taps.arm(Adr015Gate("re-lock")) { ev ->
            ev.kind == TapKind.SQL && isHttp(ev.thread) && isIntentLock(ev.sql!!) &&
                seen.computeIfAbsent(ev.thread) { AtomicInteger() }.incrementAndGet() == 2
        }
        val f1 = confirmAsync(p.holdId, x, p.uid)
        try {
            reachedOrFail(relock, what = "거부 뒤 재잠금(같은 요청의 두 번째 intent FOR UPDATE)")
            assertTrue(holdExists(p.holdId), "첫 트랜잭션은 롤백 — 홀드 행 그대로(F2)")
            assertEquals(OPEN, intentOrFail(p.uid).state, "재잠금 전 — intent는 아직 OPEN")

            clock.set(e.minusSeconds(60)) // 같은 uid의 다른 요청은 이제 확정할 수 있다
            val r2 = confirmAsync(p.holdId, x, p.uid).get(15, TimeUnit.SECONDS)
            assertEquals(200, r2.status, "창 안의 요청은 잠금 없이 확정(첫 트랜잭션 롤백 뒤): $r2")

            relock.release()
            val r1 = f1.done()
            assertEquals(200, r1.status, "재잠금 쪽은 state ≠ OPEN → 재생(설계-4.3 '그 사이에 확정이 끝났으면 재생' · A2): $r1")
            assertEquals(r2.long("reservationId"), r1.long("reservationId"), "같은 reservationId")
            assertEquals(0, rejections(p.uid).size, "거부 기록 0(이중 결과 0 — A2)")
            assertEquals(CONFIRMED, intentOrFail(p.uid).state)
            assertSingleOutcome(p.uid, "A2")
        } finally {
            relock.releaseAll()
        }
    }

    @Test
    fun `A2 — 거부 기록 트랜잭션이 intent를 쥔 동안 같은 uid 요청은 기다렸다가 같은 거부 코드를 재생`() {
        val p = prepare(1, x)
        clock.set(expiresAtOf(p.holdId).plusSeconds(1))
        val gate = Adr015Taps.arm(Adr015Gate("rejection-insert")) { ev ->
            ev.kind == TapKind.SQL && isHttp(ev.thread) && ev.sql!!.startsWith("insert into confirm_rejection")
        }
        val f1 = confirmAsync(p.holdId, x, p.uid)
        try {
            reachedOrFail(gate, what = "거부 기록 INSERT(재잠금을 쥔 채)")
            val f2 = confirmAsync(p.holdId, x, p.uid)
            assertTrue(awaitLockWait("payment_intent"), "두 번째는 intent 잠금에서 기다린다: ${dbState()}")
            gate.release()
            val r1 = f1.done()
            val r2 = f2.done()
            assertEquals(409, r1.status, "$r1")
            assertEquals("HOLD_EXPIRED", r1.code)
            sameOutcome(r1, r2, "A2 거부 재생")
            assertEquals(1, rejections(p.uid).size, "거부 기록 1행")
            assertSingleOutcome(p.uid, "A2 거부")
        } finally {
            gate.releaseAll()
        }
    }

    // ── 만료 경계 · 만료 배치(N17-5 · 명세 §5) ────────────────────────────────────────────────

    @Test
    fun `N17-5 — 만료 경계에서 같은 uid 동시 2개(+ 시계 넘김 · 절반은 만료 배치) 20회 → 예약과 거부 기록 공존 0 · 두 응답 같은 결과`() {
        val rnd = Random(17)
        val outcomes = mutableMapOf<Int, Int>()
        repeat(20) { i ->
            val p = prepare(i + 1, 5_000L + i)
            val e = expiresAtOf(p.holdId)
            clock.set(e.minusMillis(1)) // 경계 바로 앞
            val flipDelay = rnd.nextLong(0, 4)
            val withBatch = i % 2 == 1
            val results = together<Any?>(
                { confirmHttp(p.holdId, p.user, p.uid) },
                { confirmHttp(p.holdId, p.user, p.uid) },
                {
                    Thread.sleep(flipDelay)
                    clock.set(e.plusSeconds(1)) // 경계 넘김
                    if (withBatch) runCatching { expire.expire() } else null
                },
            )
            val r1 = results[0] as Resp
            val r2 = results[1] as Resp
            assertTrue(r1.status in setOf(200, 404, 409), "[$i] 도메인 응답만(5xx 없음): $r1")
            assertTrue(r2.status in setOf(200, 404, 409), "[$i] $r2")
            sameOutcome(r1, r2, "[$i] batch=$withBatch")
            assertSingleOutcome(p.uid, "[$i] batch=$withBatch")
            val i0 = intentOrFail(p.uid)
            if (r1.status == 200) assertEquals(CONFIRMED, i0.state, "[$i]")
            else {
                assertEquals(REJECTED, i0.state, "[$i]")
                assertEquals(r1.code, i0.resultCode, "[$i] 응답 코드 = result_code")
                assertEquals(r1.status, rejections(p.uid).single().httpStatus, "[$i] 거부 기록 http_status = 응답 상태")
            }
            outcomes.merge(r1.status, 1, Int::plus)
        }
        assertEquals(0, taskCount(), "모드 none — 작업 0")
        // 전체 대조(N17-1 · N17-5)
        val coexist = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM payment_intent p
            WHERE EXISTS (SELECT 1 FROM reservation r WHERE r.payment_uid = p.uid::text)
              AND EXISTS (SELECT 1 FROM confirm_rejection c WHERE c.uid::text = p.uid::text)
            """.trimIndent(),
            Int::class.java,
        )!!
        assertEquals(0, coexist, "예약 + 거부 기록 공존 0(N17-5) — 결과 분포 $outcomes")
        val multi = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM (SELECT intent_id FROM reservation WHERE intent_id IS NOT NULL GROUP BY intent_id HAVING count(*) > 1) t",
            Int::class.java,
        )!!
        assertEquals(0, multi, "intent당 예약 ≤ 1(N17-1)")
    }
}
