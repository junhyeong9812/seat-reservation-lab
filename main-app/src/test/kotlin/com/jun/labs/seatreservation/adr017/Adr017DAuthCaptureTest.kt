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
import java.time.Duration
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * D(auth-capture) 계약 — 명세 부록 B 'D' · ADR-017 설계-3(capture · close · 응답 분류표) · 4.3(성공 = 같은 트랜잭션 CAPTURE) · 4.4(워커) · 설계-5 D ·
 * 설계-7 N17-3 · N17-5 · N17-8 · Q5(매입 실패 = 예약 유지 · 보고).
 * 실제 HTTP(RANDOM_PORT) · 흉내 PG 같은 JVM(Adr017DsSupport) · 확정 모드 = 기본 ④(명세 §1-8: 모드 ≠ none ⇒ ① 또는 ④).
 *
 * 가정(근거):
 *  - 흉내 PG authTtl 기본 = 10분(명세 §8) — '승인 만료'는 시계를 1시간 옮겨 어떤 합리적 authTtl 설정이든 넘긴다.
 *  - 지표 `seat.compensation.done{type, result}`의 result 태그 = 작업의 last_result 값(부록 B 'done{type=CAPTURE, result=FAILED_…}').
 *  - 승인 만료 뒤 capture의 PG 거부 코드는 상태 이름 `EXPIRED`(부록 A '상태 이름(409)') → last_result = `FAILED_EXPIRED`. 접두 `FAILED_`만 강하게, 이름은 EXPIRED 포함으로 본다.
 *  - 확정 거부의 홀드 만료 = 앱 시계를 홀드 expires_at 너머로(만료 배치 전 → 409 HOLD_EXPIRED · F1 진단).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, DS_SCHEDULER_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        P_LOC_BASE, DS_MODE_AUTH_CAPTURE,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr017DAuthCaptureTest : Adr017DsBase() {

    private fun authorized(seatNo: Int, user: Long): Paid {
        val p = startedHold(createSeat(seatNo).id!!, user)
        pgAuthorizeOk(p.uid)
        assertEquals("AUTHORIZED", pgState(p.uid), "준비 — PG 상태")
        return p
    }

    // ── N17-8 성공: 확정 200 + 같은 트랜잭션 CAPTURE 작업 → 워커 → CAPTURED ─────────────────

    @Test
    fun `N17-8 — authorize 뒤 확정 200 · compensation CAPTURE PENDING · CAPTURE 작업 1행(source CONFIRM) · 워커 뒤 PG CAPTURED · DONE`() {
        val p = authorized(1, x)
        val doneBefore = c(DS_M_DONE, "type", "CAPTURE")

        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertEquals(200, r.status, "확정 성공: $r")
        val rid = r.long("reservationId")
        val comp = compOrFail(r, "확정 200")
        assertEquals("CAPTURE", comp.type, "N17-8 — 성공 본문 type CAPTURE: $r")
        assertEquals("PENDING", comp.status, "커밋 직후 작업은 PENDING: $r")

        val ts = tasks(p.uid)
        assertEquals(1, ts.size, "N17-8 · N17-5 — 작업 1행(CAPTURE만 · CLOSE 없음): $ts")
        val t = ts.single()
        assertEquals("CAPTURE", t.type)
        assertEquals("CONFIRM", t.source, "CAPTURE 작업 출처 = CONFIRM(설계-4.1)")
        assertEquals("PENDING", t.status)
        assertEquals(t.id, comp.taskId, "본문 taskId = 작업 id")
        assertEquals(CONFIRMED, intentOrFail(p.uid).state)
        assertEquals(rid, intentOrFail(p.uid).reservationId)
        assertEquals(0, rejectionCount(), "성공에는 거부 기록 없음")
        assertEquals("AUTHORIZED", pgState(p.uid), "워커 전에는 매입 없음")

        drain(p.uid)
        val done = tasks(p.uid).single()
        assertEquals("DONE", done.status, "워커 뒤 DONE: $done")
        assertEquals("CAPTURED", done.lastResult, "last_result = CAPTURED(설계-3 분류표 종결-성공)")
        assertEquals("CAPTURED", pgState(p.uid), "PG 원장 CAPTURED")
        assertEquals(doneBefore + 1, c(DS_M_DONE, "type", "CAPTURE"), "지표 $DS_M_DONE{type=CAPTURE} +1")
        assertEquals("CONFIRMED", reservation(rid).status)
        assertEquals("HOLD", reservation(rid).origin, "D 성공 예약은 origin HOLD")

        // N17-3 — 재요청은 첫 결과(200 · 같은 예약 id) + 지금의 보상 상태(DONE)
        val again = confirmHttp(p.holdId, p.user, p.uid)
        assertEquals(200, again.status, "재요청 재생: $again")
        assertEquals(rid, again.long("reservationId"))
        val c2 = compOrFail(again, "재생")
        assertEquals("CAPTURE", c2.type)
        assertEquals("DONE", c2.status, "N17-3 — 보상 상태는 지금 값: $again")
        assertEquals(1, tasks(p.uid).size, "재생은 작업을 만들지 않는다")
    }

    // ── Q5 매입 종결-실패: 승인 만료 → DONE · FAILED_* · 예약 유지 · 지표 ─────────────────────

    @Test
    fun `N17-8 · Q5 — 승인 만료(시계 1시간 뒤) 워커 capture → DONE · last_result FAILED_ · 예약 유지 · CLOSE 없음 · 지표 done{CAPTURE, FAILED_}`() {
        val p = authorized(1, x)
        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertEquals(200, r.status, "$r")
        val rid = r.long("reservationId")

        clock.advance(Duration.ofHours(1))
        drain(p.uid)

        val t = tasksOfType(p.uid, "CAPTURE").single()
        assertEquals("DONE", t.status, "종결-실패도 DONE(재시도 · DEAD 아님 — 설계-3 분류표): $t")
        val lr = assertNotNull(t.lastResult, "last_result")
        assertTrue(lr.startsWith("FAILED_"), "last_result = FAILED_<code>(부록 B): $lr")
        assertTrue(lr.contains("EXPIRED"), "승인 만료 사유: $lr")
        assertEquals(1.0, c(DS_M_DONE, "type", "CAPTURE", "result", lr), "지표 $DS_M_DONE{type=CAPTURE, result=$lr} 1(돈 없는 좌석 집계)")

        assertEquals("CONFIRMED", reservation(rid).status, "Q5 — 매입 실패여도 예약은 그대로")
        assertEquals("RESERVED", seatStatusOf(p.seatId))
        assertEquals(CONFIRMED, intentOrFail(p.uid).state, "intent state 그대로")
        assertTrue(tasksOfType(p.uid, "CLOSE").isEmpty(), "N17-5 — 매입 실패가 CLOSE를 만들지 않는다(예약 유지)")
        assertTrue(pgState(p.uid) != "CAPTURED", "PG에서 결제됨 아님(돈 없는 좌석): ${pgState(p.uid)}")
    }

    // ── N17-8 거부: CLOSE → VOIDED ─────────────────────────────────────────────────────────

    @Test
    fun `N17-8 — 확정 거부(홀드 만료 409) → compensation CLOSE · CAPTURE 0 · 워커 뒤 PG VOIDED · 재요청 재생`() {
        val p = authorized(1, x)
        clock.set(p.expiresAt.plusSeconds(1))

        val r = confirmHttp(p.holdId, p.user, p.uid)
        assertEquals(409, r.status, "$r")
        assertEquals("HOLD_EXPIRED", r.code, "I11 — 거부 코드 그대로: $r")
        val comp = compOrFail(r, "거부 본문")
        assertEquals("CLOSE", comp.type, "$r")
        assertEquals("PENDING", comp.status, "$r")
        assertNull(comp.reservationId, "거부 본문에 예약 없음")

        val ts = tasks(p.uid)
        assertEquals(listOf("CLOSE"), ts.map { it.type }, "N17-5 — CLOSE만(CAPTURE 공존 0): $ts")
        assertEquals("REJECTION", ts.single().source)
        assertEquals(comp.taskId, ts.single().id)
        assertEquals(REJECTED, intentOrFail(p.uid).state)
        assertEquals(1, rejections(p.uid).size, "N17-2 — 거부 기록 1")
        assertEquals(0, reservationsOfUid(p.uid))

        drain(p.uid)
        val t = tasks(p.uid).single()
        assertEquals("DONE", t.status)
        assertEquals("VOIDED", t.lastResult, "close: AUTHORIZED → VOIDED(설계-3)")
        assertEquals("VOIDED", pgState(p.uid))

        val again = confirmHttp(p.holdId, p.user, p.uid)
        assertEquals(409, again.status, "N17-3 재생: $again")
        assertEquals("HOLD_EXPIRED", again.code)
        assertEquals("DONE", compOrFail(again, "재생").status, "지금의 보상 상태")
        assertEquals(1, tasks(p.uid).size)
    }

    // ── N17-5 · N17-8 동시성 ──────────────────────────────────────────────────────────────

    @Test
    fun `N17-5 · N17-8 — 같은 uid 확정 둘 동시(8회) → 둘 다 200 같은 예약 · CAPTURE 1 · CLOSE 0`() {
        repeat(8) { i ->
            val p = authorized(i + 1, 1_000L + i)
            val (a, b) = together({ confirmHttp(p.holdId, p.user, p.uid) }, { confirmHttp(p.holdId, p.user, p.uid) })
            assertEquals(200, a.status, "[$i] $a")
            assertEquals(200, b.status, "[$i] 늦은 쪽도 재생 200: $b")
            assertEquals(a.long("reservationId"), b.long("reservationId"), "[$i] 같은 예약")
            assertEquals(1, reservationsOfUid(p.uid), "[$i] N17-1")
            assertEquals(listOf("CAPTURE"), tasks(p.uid).map { it.type }, "[$i] CAPTURE 1 · CLOSE 0")
        }
    }

    /**
     * 만료 경계에서 확정 2개(같은 uid) ∥ 시계 경계 넘김 + 만료 배치 ∥ 안전망 한 주기 — 결과가 어느 쪽이든 한 intent에 CAPTURE와 CLOSE가 함께 생기지 않고,
     * 예약 ⇔ CAPTURE · 예약 없음 ⇔ CLOSE이며, 워커 뒤 PG가 그 결과(CAPTURED · VOIDED)와 같다.
     */
    @Test
    fun `N17-5 · N17-8 — 만료 경계(12회) 확정 2 ∥ 만료 배치 ∥ 안전망 → CAPTURE · CLOSE 공존 0 · 예약 ⇔ CAPTURE · PG 대조`() {
        val rnd = Random(17)
        val outcomes = mutableMapOf<String, Int>()
        repeat(12) { i ->
            clock.set(IntegrationTest.NOW.plus(Duration.ofMinutes(30L * i)))
            val p = authorized(i + 1, 2_000L + i)
            clock.set(p.expiresAt.minusMillis(3))
            val jitter = rnd.nextLong(0, 4)
            val rs = together(
                { confirmHttp(p.holdId, p.user, p.uid) },
                { confirmHttp(p.holdId, p.user, p.uid) },
                {
                    Thread.sleep(jitter)
                    clock.set(p.expiresAt.plusMillis(1))
                    expire.expire()
                    Resp(200, "{}")
                },
                { sweepOnce(); Resp(200, "{}") },
            )
            val (a, b) = rs
            assertNo5xx(rs, "[$i]")
            assertEquals(a.status, b.status, "[$i] 같은 uid 두 응답은 같은 결과(N17-3): $a / $b")

            val types = tasks(p.uid).map { it.type }.sorted()
            val res = reservationsOfUid(p.uid)
            assertTrue(!(types.contains("CAPTURE") && types.contains("CLOSE")), "[$i] N17-5 — CAPTURE와 CLOSE 공존: $types · ${dbState()}")
            assertTrue(!(res > 0 && types.contains("CLOSE")), "[$i] N17-5 — 예약과 CLOSE 공존: res=$res $types")
            if (a.status == 200) {
                assertEquals(listOf("CAPTURE"), types, "[$i] 성공 ⇔ CAPTURE 1(N17-8)")
                assertEquals(1, res)
                assertEquals(CONFIRMED, intentOrFail(p.uid).state)
            } else {
                assertEquals(listOf("CLOSE"), types, "[$i] 거부 ⇔ CLOSE 1(N17-8): $a")
                assertEquals(0, res)
                assertEquals("CLOSE", compOrFail(a, "[$i] 거부 본문").type)
                assertTrue(intentOrFail(p.uid).state in setOf(REJECTED, "CLOSING"), "[$i] ${intentOrFail(p.uid)}")
            }
            outcomes.merge(if (a.status == 200) "capture" else "close", 1, Int::plus)

            drain(p.uid)
            assertEquals(if (a.status == 200) "CAPTURED" else "VOIDED", pgState(p.uid), "[$i] PG 원장 = DB 결과")
        }
        println("ADR-017 D 만료 경계 결과 분포: $outcomes")
    }
}
