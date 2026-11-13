package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * task 04 — 앱 ↔ 흉내 PG · 모드 refund(R) · 기본 확정 모드 ④ conditional-extend(W 611초 · M 1초).
 * 선점 at NOW → payment-start at NOW → expiresAt = NOW + 611초 · payBy = NOW + 610초(Adr017PaymentStartIntentTest와 같은 산식).
 * 다루는 것: payment-start PG 등록(설계-4.2 · 부록 A) · R 흐름(설계-5) · N17-4 · 응답 분류표(설계-3 RD17-11) · DEAD · retry API(RD17-5) · RD17-10.
 *
 * 가정(근거 조항):
 *  - payment-start의 PG 등록 실패 503 본문 code는 명세가 정하지 않았다 → 상태만 본다(부록 A '실패면 payment-start 응답 503').
 *  - 관리 API `/internal/compensations/{id}/retry`는 이 테스트 컨텍스트(기본 프로필)에 있다(부록 A · 설계-4.4 — 프로필 제한 언급 없음).
 *  - 거부 직후 응답 compensation.status는 PENDING이다(작업은 같은 트랜잭션에서 막 생겼다 · 스케줄러 꺼짐) — 넓게 {PENDING}만 허용.
 *  - 지표 태그 값(type=CLOSE 대문자 여부)은 고정되지 않았다 → 이름 전체 합의 증감으로 본다.
 *  - 시도 상한: attempts가 max-attempts에 닿으면 DEAD(또는 넘으면) — 둘 다 허용하되 DEAD 전 시도 수는 max ~ max+1.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_READ_TIMEOUT, P17_CONNECT_TIMEOUT, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr017PgRefundFlowTest : Adr017PgBase() {

    // ── payment-start PG 등록(설계-4.2 · 부록 A) ──────────────────────────────────────────────

    @Test
    fun `I13 · N17-6 — payment-start가 PG에 uid · userId · amount · deadline(= 응답 payBy)을 등록 · 재호출 = 같은 uid 멱등`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val (uid, r) = startOk(hold, x)
        val g = Adr017Pg.get(uid)
        assertEquals(200, g.status, "PG에 등록돼 있어야 한다: $g")
        assertEquals("REGISTERED", g.state)
        assertEquals(r.instant("payBy"), g.instant("deadline"), "deadline = 응답 payBy = intent.pay_by(설계-4.2)")
        assertEquals(intentOrFail(uid).payBy, g.instant("deadline"))
        assertEquals(x, g.payment!!.get("userId").asLong())
        assertEquals(AMOUNT_DEFAULT, g.payment!!.get("amount").asLong(), "seat.payment.amount 기본 10000(부록 A)")

        val (uid2, _) = startOk(hold, x)
        assertEquals(uid, uid2, "같은 hold 재호출 = 같은 uid")
        assertEquals("REGISTERED", Adr017Pg.get(uid).state, "재등록 멱등")
    }

    @Test
    fun `부록 A — PG register 503 → payment-start 503 · intent OPEN · PG 미등록 · 장애 풀고 재호출 = 같은 uid가 등록됨`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        Adr017Pg.failAll("register")
        val failed = paymentStart(hold, x)
        assertEquals(503, failed.status, "PG 등록 실패면 503: $failed")
        val rows = jdbcTemplate.queryForList("SELECT uid::text AS uid, state FROM payment_intent WHERE hold_id = ?", hold)
        assertEquals(1, rows.size, "intent는 커밋돼 있다(등록은 트랜잭션 밖): $rows")
        val uid = rows.single()["uid"] as String
        assertEquals(OPEN, rows.single()["state"])
        assertEquals(404, Adr017Pg.get(uid).status, "PG에는 없다")

        Adr017Pg.clearFaults()
        val (uid2, r2) = startOk(hold, x)
        assertEquals(uid, uid2, "재호출 = 같은 uid(멱등)")
        val g = Adr017Pg.get(uid)
        assertEquals("REGISTERED", g.state, "재호출이 다시 등록했다: $g")
        assertEquals(r2.instant("payBy"), g.instant("deadline"))
    }

    @Test
    fun `RD17-P1 — 등록 전에 닫힌 uid(PG 표지) → payment-start 재호출 409 HOLD_EXPIRED · PG 결제 불가`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        Adr017Pg.failAll("register")
        assertEquals(503, paymentStart(hold, x).status)
        val uid = jdbcTemplate.queryForObject("SELECT uid::text FROM payment_intent WHERE hold_id = ?", String::class.java, hold)!!
        assertEquals(200, Adr017Pg.close(uid).status, "워커 close가 먼저 닿은 모양 — 표지")
        Adr017Pg.clearFaults()

        val r = paymentStart(hold, x)
        assertEquals(409, r.status, "등록 409 CLOSED → payment-start 409 HOLD_EXPIRED(부록 A): $r")
        assertEquals("HOLD_EXPIRED", r.code, "$r")
        assertEquals(409, Adr017Pg.pay(uid).status, "닫힌 uid는 결제할 수 없다")
        assertEquals("CLOSED", pgState(uid))
    }

    // ── R 흐름(설계-5 · N17-2 · N17-3) ─────────────────────────────────────────────────────────

    @Test
    fun `R — 결제 뒤 지연 확정 409 → REJECTED · CLOSE 작업 PENDING · 본문 compensation → 워커 → DONE · PG REFUNDED refunds 1 · 재생 · GET 반영`() {
        val p = started(1, x)
        clock.set(p.expiresAt.plusSeconds(1))
        val doneBefore = total(M17_DONE)

        val r = confirmHttp(p.holdId, x, p.uid)
        assertEquals(409, r.status, "$r")
        assertEquals("HOLD_EXPIRED", r.code, "거부 코드 그대로(I11)")
        val i = intentOrFail(p.uid)
        assertEquals(REJECTED, i.state)
        assertEquals("HOLD_EXPIRED", i.resultCode)
        assertEquals(1, rejections(p.uid).size, "거부 기록 1(N17-2)")
        val t = closeTask(p.uid)
        assertEquals("PENDING", t.status)
        assertEquals("REJECTION", t.source, "출처 = REJECTION(설계-4.1)")
        assertCompensation(r, t.id, "CLOSE", setOf("PENDING"), "거부 본문")
        assertEquals("CAPTURED", pgState(p.uid), "워커 전에는 환불 없음")

        runWorkerUntilDone(p.uid)
        val done = closeTask(p.uid)
        assertEquals("DONE", done.status)
        assertNotNull(done.doneAt, "done_at")
        assertTrue(done.lastResult.orEmpty().contains("REFUNDED"), "last_result = 결과(설계-3 분류표): $done")
        val g = Adr017Pg.get(p.uid)
        assertEquals("REFUNDED", g.state, "$g")
        assertEquals(1, g.refunds)
        assertTrue(total(M17_DONE) >= doneBefore + 1, "$M17_DONE 증가")

        val again = confirmHttp(p.holdId, x, p.uid)
        assertEquals(409, again.status, "재생: $again")
        assertEquals("HOLD_EXPIRED", again.code)
        assertCompensation(again, t.id, "CLOSE", setOf("DONE"), "재생 — 보상 상태는 지금 값(N17-3)")

        val view = getHttp("/api/payments/${p.uid}", x)
        assertEquals(200, view.status, "$view")
        assertEquals(REJECTED, view.json!!.get("state").asText())
        assertEquals("HOLD_EXPIRED", view.json!!.get("resultCode").asText())
        val vc = assertNotNull(view.json!!.get(COMPENSATION), "조회에 compensation: $view")
        assertEquals("DONE", vc.get("status").asText())
        assertEquals("CLOSE", vc.get("type").asText())
        assertEquals(0, reservationsOfUid(p.uid), "환불됐는데 좌석 있음 0")
    }

    @Test
    fun `R — 확정 성공이면 작업 0 · PG CAPTURED 그대로(보상 없음)`() {
        val p = started(1, x)
        val r = confirmHttp(p.holdId, x, p.uid)
        assertEquals(200, r.status, "$r")
        assertEquals(CONFIRMED, intentOrFail(p.uid).state)
        assertTrue(tasks(p.uid).isEmpty(), "R 성공은 작업이 없다(설계-5)")
        worker.runOnce()
        assertEquals("CAPTURED", pgState(p.uid))
        assertEquals(0, Adr017Pg.get(p.uid).refunds)
    }

    @Test
    fun `RD17-10 — 결제 안 한 uid의 거부 → CLOSE → PG CLOSED · 작업 DONE · 그 뒤 PG pay 409(늦은 결제 차단)`() {
        val p = started(1, x, pay = false)
        clock.set(p.expiresAt.plusSeconds(1))
        val r = confirmHttp(p.holdId, x, p.uid)
        assertEquals(409, r.status, "$r")
        runWorkerUntilDone(p.uid)
        assertEquals("CLOSED", pgState(p.uid), "미결제 종결(NOT_PAID)")
        assertTrue(closeTask(p.uid).lastResult.orEmpty().contains("CLOSED"), "${closeTask(p.uid)}")
        clock.set(p.payBy.minusSeconds(5)) // deadline 안이어도
        val late = Adr017Pg.pay(p.uid)
        assertEquals(409, late.status, "닫힌 uid 결제 불가: $late")
        assertEquals("CLOSED", late.code)
    }

    // ── N17-4(작업 1행 · PG 결과 하나) ────────────────────────────────────────────────────────

    @Test
    fun `N17-4 — 워커를 두 번 · 리스 만료 뒤 다시 집어도 refunds 1 · 작업 1행`() {
        val p = started(1, x)
        clock.set(p.expiresAt.plusSeconds(1))
        assertEquals(409, confirmHttp(p.holdId, x, p.uid).status)
        runWorkerUntilDone(p.uid)
        val t = closeTask(p.uid)

        assertEquals(0, worker.runOnce(), "DONE 작업은 다시 집지 않는다")
        assertEquals(1, Adr017Pg.get(p.uid).refunds)

        // 집은 채 죽은 워커(PG 호출 뒤 결과 쓰기 전) 모양 → 리스 만료 → 회수 → PG 재호출(멱등) → DONE
        forceLeaseExpired(t.id)
        runWorkerUntil(6, "리스 만료 작업 회수") { closeTask(p.uid).status == "DONE" }
        val g = Adr017Pg.get(p.uid)
        assertEquals("REFUNDED", g.state)
        assertEquals(1, g.refunds, "이중 환불 0")
        assertEquals(1, tasks(p.uid).size, "작업 1행")

        // 같은 uid로 확정을 또 보내도 작업이 늘지 않는다(UNIQUE(uid, type) · 재생)
        assertEquals(409, confirmHttp(p.holdId, x, p.uid).status)
        assertEquals(1, tasks(p.uid).size)
    }

    @Test
    fun `N17-4 — 두 워커가 동시에 돌아도 작업마다 PG 결과 하나(refunds 1) · 처리 합 = 작업 수`() {
        val users = (1..5).map { 100L + it }
        val ps = users.mapIndexed { i, u -> started(i + 1, u) }
        clock.set(ps.maxOf { it.expiresAt }.plusSeconds(1))
        ps.zip(users).forEach { (p, u) -> assertEquals(409, confirmHttp(p.holdId, u, p.uid).status) }
        assertEquals(5, taskCount())

        val (a, b) = together({ worker.runOnce() }, { worker.runOnce() })
        assertEquals(5, a + b, "SKIP LOCKED — 한 작업은 한 워커만(처리 합 = 5): a=$a b=$b")
        runWorkerUntil(6, "5건 모두 DONE") { jdbcTemplate.queryForObject("SELECT count(*) FROM compensation_task WHERE status <> 'DONE'", Int::class.java) == 0 }
        ps.forEach {
            val g = Adr017Pg.get(it.uid)
            assertEquals("REFUNDED", g.state, "$g")
            assertEquals(1, g.refunds, "이중 환불 0: $g")
        }
        assertEquals(5, taskCount())
    }

    // ── 응답 분류표(설계-3 RD17-11) · DEAD · retry API(RD17-5) ──────────────────────────────────

    @Test
    fun `분류표 재시도 — close 503 → attempts+1 · PENDING · next_attempt_at 뒤로 · 상한 넘으면 DEAD · 지표 · retry API로 PENDING → 성공(RD17-5)`() {
        val p = started(1, x)
        clock.set(p.expiresAt.plusSeconds(1))
        assertEquals(409, confirmHttp(p.holdId, x, p.uid).status)
        val t0 = closeTask(p.uid)
        val retry0 = total(M17_RETRY)
        val dead0 = total(M17_DEAD)

        Adr017Pg.failAll("close")
        worker.runOnce()
        val t1 = closeTask(p.uid)
        assertEquals("PENDING", t1.status, "503 = 재시도: $t1")
        assertEquals(1, t1.attempts, "attempts + 1")
        assertTrue(t1.nextAttemptAt!! > t0.nextAttemptAt!!, "백오프 — next_attempt_at 뒤로: $t0 → $t1")
        assertTrue(total(M17_RETRY) >= retry0 + 1, "$M17_RETRY 증가")
        assertEquals("CAPTURED", pgState(p.uid), "503은 처리 없음")
        assertNotNull(gaugeValue(M17_OLDEST_PENDING), "정체 게이지 $M17_OLDEST_PENDING 등록(설계-4.4)")

        runWorkerUntil(MAX_ATTEMPTS + 3, "시도 상한 → DEAD") { closeTask(p.uid).status == "DEAD" }
        val dead = closeTask(p.uid)
        assertTrue(dead.attempts in MAX_ATTEMPTS..MAX_ATTEMPTS + 1, "DEAD 시점 attempts ≈ max-attempts($MAX_ATTEMPTS): $dead")
        assertEquals(dead0 + 1, total(M17_DEAD), "$M17_DEAD +1")
        assertEquals("CAPTURED", pgState(p.uid))
        assertEquals(0, workerRound(), "DEAD는 워커가 집지 않는다")

        assertEquals(404, adminRetry(999_999_999L).status, "없는 작업 404")
        val rr = adminRetry(dead.id)
        assertEquals(200, rr.status, "DEAD → PENDING: $rr")
        val back = closeTask(p.uid)
        assertEquals("PENDING", back.status)
        assertEquals(0, back.attempts, "attempts 초기화")
        assertEquals(409, adminRetry(dead.id).status, "DEAD 아님 → 409")

        Adr017Pg.clearFaults()
        runWorkerUntilDone(p.uid)
        val g = Adr017Pg.get(p.uid)
        assertEquals("REFUNDED", g.state)
        assertEquals(1, g.refunds)
    }

    @Test
    fun `분류표 결과 모름 — close가 처리되고 응답은 read-timeout 뒤 → 재시도 → 다음 시도에서 확인 후 DONE · 이중 환불 0`() {
        val p = started(1, x)
        clock.set(p.expiresAt.plusSeconds(1))
        assertEquals(409, confirmHttp(p.holdId, x, p.uid).status)

        Adr017Pg.faults(mapOf("close" to mapOf("unknownRate" to 1.0, "unknownDelayMs" to UNKNOWN_DELAY_MS)))
        worker.runOnce()
        val t1 = closeTask(p.uid)
        assertEquals("PENDING", t1.status, "타임아웃 = 재시도(분류표 — DONE · DEAD 아님 · 결과 모름을 성공으로 적지 않는다): $t1")
        assertEquals(1, t1.attempts, "attempts + 1")
        Adr017Pg.awaitState(p.uid, "REFUNDED") // PG는 처리했다

        // 결과 모름이 그대로여도(주입 유지) 다음 시도는 GET으로 확인하고 끝낸다(설계-3 '다음 시도 전에 GET으로 확인')
        runWorkerUntilDone(p.uid)
        val g = Adr017Pg.get(p.uid)
        assertEquals("REFUNDED", g.state)
        assertEquals(1, g.refunds, "이중 환불 0")
        assertEquals("DONE", closeTask(p.uid).status)
        Adr017Pg.clearFaults()
    }
}
