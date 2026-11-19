package com.jun.labs.seatreservation.adr018

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.adr017.Adr017Pg
import com.jun.labs.seatreservation.adr017.CONFIRMED
import com.jun.labs.seatreservation.adr017.OPEN
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_INITIAL
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_MAX
import com.jun.labs.seatreservation.adr017.P17_LEASE
import com.jun.labs.seatreservation.adr017.P17_MAX_ATTEMPTS
import com.jun.labs.seatreservation.adr017.P17_MODE_REFUND
import com.jun.labs.seatreservation.adr017.P17_SCHEDULER_OFF
import com.jun.labs.seatreservation.adr017.REJECTED
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * G4 묶음 결제(payment-start · 확정 · intent · 보상) · G5 해제 · 만료(설계-3~6) — 다석 방식과 무관한 생명주기라 split 한 판에서만 돈다
 * (Adr018SplitModeTest와 같은 키 · 같은 컨텍스트).
 *
 * 해석(모호점 — 보고에 적음):
 *  - 묶음 결제 시작의 payBy · payWithinSeconds · expiresAt은 1석 결제 시작과 같은 규칙(설계-4 '같은 순서를 묶음에 적용') → 같은 시각의 1석 결제 시작 응답과 같은 값.
 *  - 해제 뒤 hold_group 행은 남는다(설계-1 '홀드가 사라져도 남긴다').
 *  - 확정 성공 본문은 {status, reservations}에 compensation 키가 더해질 수 있다(1석 intent 경로 관례) — 그 밖 키는 없다.
 *  - 묶음 확정 거부의 code는 1석과 같다(만료 뒤 = HOLD_EXPIRED). 홀드 1행을 직접 지운 비정상 묶음의 거부 code는 정해지지 않아 4xx만 본다.
 *  - 확정 거부 뒤 '좌석 그대로'는 홀드 N행 · HELD N석이 남는다는 뜻(롤백) — 만료 배치가 지울 때까지.
 *  - reservation.intent_id 타입을 모르므로(Adr017Support와 같은 이유) '같은 intent' = 예약 N행의 intent_id가 하나이고 NULL이 아님으로 본다.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
        P18_ON, P18_SPLIT,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr018GroupLifecycleTest : Adr018Base() {

    private fun assertNoContent(r: Resp, why: String) {
        assertEquals(204, r.status, "$why: $r")
        assertTrue(r.body.isEmpty(), "$why — 204 본문 없음: $r")
    }

    // ── G5 해제 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `G5 — 1석 해제 204 · 좌석 AVAILABLE · 카운터 −1 · 홀드 행 삭제 · 다시 해제 404`() {
        val (a, b) = seats(2)
        val h = holdOk(a, x)
        holdOk(b, x)
        assertEquals(2, cnt(x))
        assertNoContent(releaseHold(h, x), "1석 해제")
        assertEquals("AVAILABLE", seatStatusOf(a))
        assertEquals("HELD", seatStatusOf(b), "다른 홀드는 그대로")
        assertEquals(1, cnt(x), "카운터 −1")
        assertTrue(!holdExists(h), "홀드 행 삭제")
        assertCode(releaseHold(h, x), 404, HOLD_NOT_FOUND, "이미 해제된 홀드")
        assertVerdictClean("1석 해제 뒤")
        // 놓은 좌석은 바로 다른 사용자가 잡는다(부분 선점 해소의 목적)
        holdOk(a, y)
    }

    @Test
    fun `G5 — 묶음 해제 204 · 좌석 모두 AVAILABLE · 카운터 −N · 홀드 행 삭제 · hold_group 행은 남음 · 다시 해제 404`() {
        val (a, b) = seats(2)
        val g = groupHoldOk(listOf(a, b), x)
        assertNoContent(releaseGroup(g.groupId, x), "묶음 해제")
        assertEquals(listOf("AVAILABLE", "AVAILABLE"), seatsStatus(listOf(a, b)))
        assertEquals(0, cnt(x), "카운터 −2")
        assertEquals(0, holdsOf(x))
        assertEquals(1, groupRows(), "hold_group 행은 남긴다(설계-1)")
        assertCode(releaseGroup(g.groupId, x), 404, HOLD_NOT_FOUND, "이미 해제된 묶음")
        assertVerdictClean("묶음 해제 뒤")
        groupHoldOk(listOf(a, b), y)
    }

    @Test
    fun `G5 — 없는 홀드 · 없는 묶음 404 HOLD_NOT_FOUND · 남의 것 403 HOLD_NOT_OWNED · 무변화`() {
        val (a, b, c) = seats(3)
        val h = holdOk(c, y) // 1석은 y · 묶음은 x — 한 사용자에 3석을 몰면 한도 2에 걸린다
        val g = groupHoldOk(listOf(a, b), x)
        val before = snapshot()
        assertCode(releaseHold(NO_SUCH_ID, x), 404, HOLD_NOT_FOUND, "없는 홀드")
        assertCode(releaseGroup(NO_SUCH_ID, x), 404, HOLD_NOT_FOUND, "없는 묶음")
        assertCode(releaseHold(h, x), 403, HOLD_NOT_OWNED, "남의 홀드")
        assertCode(releaseGroup(g.groupId, y), 403, HOLD_NOT_OWNED, "남의 묶음")
        assertUnchanged(before, "4xx 해제")
    }

    @Test
    fun `G5 — 결제 시작 뒤 해제 409 PAYMENT_STARTED(1석 · 묶음) · 무변화`() {
        val (a, b, c) = seats(3)
        val h = holdOk(c, x)
        assertEquals(200, paymentStart(h, x).status)
        val g = groupHoldOk(listOf(a, b), y)
        groupStartOk(g.groupId, y)
        val before = snapshot()
        assertCode(releaseHold(h, x), 409, PAYMENT_STARTED, "1석 결제 시작 뒤")
        assertCode(releaseGroup(g.groupId, y), 409, PAYMENT_STARTED, "묶음 결제 시작 뒤")
        assertUnchanged(before, "결제 시작 뒤 해제")
    }

    @Test
    fun `G5 — 묶음에 속한 홀드를 1석 해제로 → 409 HOLD_IN_GROUP · 무변화`() {
        val (a, b) = seats(2)
        val g = groupHoldOk(listOf(a, b), x)
        val before = snapshot()
        g.holdIds.forEach { assertCode(releaseHold(it, x), 409, HOLD_IN_GROUP, "묶음 홀드 1석 해제") }
        assertUnchanged(before, "HOLD_IN_GROUP")
    }

    @Test
    fun `G5 — 만료됐지만 배치가 아직 지우지 않은 홀드 · 묶음도 해제된다(결과 = 만료 배치와 같음)`() {
        val (a, b, c) = seats(3)
        val h = holdOk(c, x)
        val g = groupHoldOk(listOf(a, b), y)
        clock.set(NOW.plus(TTL_5M).plusSeconds(1))
        assertTrue(holdExists(h), "준비 — 만료됐지만 행은 남음")
        assertNoContent(releaseHold(h, x), "만료된 1석 해제")
        assertNoContent(releaseGroup(g.groupId, y), "만료된 묶음 해제")
        assertEquals(listOf("AVAILABLE", "AVAILABLE", "AVAILABLE"), seatsStatus(listOf(a, b, c)))
        assertEquals(0, cnt(x))
        assertEquals(0, cnt(y))
        assertEquals(0, holdCount())
        // 뒤이은 만료 배치가 카운터를 또 내리지 않는다
        expire.expire()
        assertEquals(0, cnt(x))
        assertEquals(0, cnt(y))
        assertVerdictClean("만료 홀드 해제 뒤")
    }

    @Test
    fun `G5 — 만료된 홀드의 해제 ∥ 만료 배치 동시 × 20 → 카운터 이중 감소 0 · 교착 0 · 해제 204 또는 404`() {
        val failures = mutableListOf<String>()
        var base = NOW
        repeat(20) { i ->
            clock.set(base)
            val (a, b, c) = seats(3)
            val u1 = 50_000L + i
            val u2 = 60_000L + i
            val g = groupHoldOk(listOf(a, b), u1)
            val h = holdOk(c, u2)
            clock.set(base.plus(TTL_5M).plusSeconds(1))
            val out = together<Any?>(
                { releaseGroup(g.groupId, u1) },
                { releaseHold(h, u2) },
                { runCatching { expire.expire() } },
            )
            val tag = "#$i"
            (out[0] as Resp).let { if (it.status !in setOf(204, 404)) failures += "$tag 묶음 해제: $it" }
            (out[1] as Resp).let { if (it.status !in setOf(204, 404)) failures += "$tag 1석 해제: $it" }
            (out[2] as Result<*>).exceptionOrNull()?.let { failures += "$tag 만료 배치 예외: $it" }
            if (cnt(u1) != 0 || cnt(u2) != 0) failures += "$tag 카운터 u1=${cnt(u1)} u2=${cnt(u2)}(0이어야 — 이중 감소면 음수)"
            if (holdsOf(u1) + holdsOf(u2) != 0) failures += "$tag 홀드가 남음"
            val st = seatsStatus(listOf(a, b, c))
            if (st.any { it != "AVAILABLE" }) failures += "$tag 좌석 $st"
            base = base.plus(Duration.ofMinutes(10))
        }
        assertTrue(failures.isEmpty(), "${failures.size}건:\n" + failures.joinToString("\n"))
        assertGroupInvariants("해제 ∥ 배치")
    }

    @Test
    fun `G5 — 묶음 해제 ∥ 묶음 결제 시작 동시 × 15 → 해제 204 + 시작 404 또는 시작 200 + 해제 409 · 둘 다 성공 0`() {
        val failures = mutableListOf<String>()
        repeat(15) { i ->
            val (a, b) = seats(2)
            val u = 70_000L + i
            val g = groupHoldOk(listOf(a, b), u)
            val (rel, st) = together({ releaseGroup(g.groupId, u) }, { groupStart(g.groupId, u) })
            val tag = "#$i"
            val intents = intentsOfGroup(g.groupId).size
            when {
                rel.status == 204 && st.status == 200 -> failures += "$tag 해제와 결제 시작이 모두 성공: $rel · $st"
                rel.status == 204 -> {
                    if (st.status != 404) failures += "$tag 해제 뒤 시작은 404여야: $st"
                    if (intents != 0) failures += "$tag 해제됐는데 intent $intents"
                    if (cnt(u) != 0 || seatsStatus(listOf(a, b)).any { it != "AVAILABLE" }) failures += "$tag 해제 뒤 상태 ${dbState()}"
                }
                st.status == 200 -> {
                    if (rel.status != 409 || rel.code != PAYMENT_STARTED) failures += "$tag 시작 뒤 해제는 409 PAYMENT_STARTED: $rel"
                    if (intents != 1) failures += "$tag 시작했는데 intent $intents"
                    if (holdsOfGroup(g.groupId).size != 2 || cnt(u) != 2) failures += "$tag 시작 뒤 상태 ${dbState()}"
                }
                else -> failures += "$tag 둘 다 실패: $rel · $st"
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size}건:\n" + failures.joinToString("\n"))
    }

    // ── G4 묶음 결제 시작 ───────────────────────────────────────────────────────────────────

    @Test
    fun `G4 · DV-1 — 묶음 결제 시작 200 · N행 연장(같은 expires_at) · intent 1(group_id · seat_count N · 가장 작은 홀드) · 재호출 같은 uid · payBy · PG 금액 N × amount`() {
        val (a, b) = seats(2)
        val c = seat()
        val g = groupHoldOk(listOf(b, a), x)
        val one = holdOk(c, y)
        val (uid, r) = groupStartOk(g.groupId, x)
        assertEquals(GROUP_START_KEYS, r.keys(), "200 본문 키: $r")
        assertEquals(g.groupId, r.long("groupId"))
        assertTrue(r.bool("extended"), "TTL 5분 < W 611초 → 연장")
        assertTrue(isUuid(uid), "paymentUid = 서버 발급 UUID: $uid")

        // 1석 결제 시작과 같은 규칙(같은 시각)
        val oneStart = paymentStart(one, y)
        assertEquals(200, oneStart.status, "$oneStart")
        assertEquals(oneStart.instant("expiresAt"), r.instant("expiresAt"), "expiresAt = 1석 규칙")
        assertEquals(oneStart.instant("payBy"), r.instant("payBy"), "payBy = 1석 규칙")
        assertEquals(oneStart.long("payWithinSeconds"), r.long("payWithinSeconds"), "payWithinSeconds = 1석 규칙")
        assertEquals(NOW.plus(W_611), r.instant("expiresAt"), "연장 = now + W")

        val rows = holdsOfGroup(g.groupId)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.expiresAt == r.instant("expiresAt") }, "N행 모두 같은 연장 만료: $rows")
        assertTrue(rows.all { it.extendedAt != null }, "N행 모두 extended_at: $rows")

        val intents = intentsOfGroup(g.groupId)
        assertEquals(1, intents.size, "묶음당 intent 1: $intents")
        val i = intents.single()
        assertEquals(uid, i.uid)
        assertEquals(2, i.seatCount, "seat_count = N")
        assertEquals(g.holdIds.min(), i.holdId, "hold_id = 가장 작은 홀드")
        assertEquals(rows.first { it.holdId == g.holdIds.min() }.seatId, i.seatId, "seat_id = 그 홀드의 좌석")
        assertEquals(OPEN, i.state)

        val pg = Adr017Pg.get(uid)
        assertEquals(200, pg.status, "PG 등록: $pg")
        assertEquals(2 * AMOUNT, pg.payment!!.get("amount").asLong(), "DV-7 — PG 등록 금액 = N × amount")
        assertEquals(r.instant("payBy"), pg.instant("deadline"), "deadline = payBy")
        assertEquals(AMOUNT, Adr017Pg.get(oneStart.json!!.get("paymentUid").asText()).payment!!.get("amount").asLong(), "1석은 1 × amount 그대로")

        // 재호출(DV-1) — 시계를 옮겨도 같은 uid · 같은 payBy · 연장 없음
        clock.advance(Duration.ofSeconds(30))
        val (uid2, r2) = groupStartOk(g.groupId, x)
        assertEquals(uid, uid2, "재호출 = 같은 paymentUid")
        assertEquals(r.instant("payBy"), r2.instant("payBy"), "재호출 = 같은 payBy")
        assertEquals(1, intentsOfGroup(g.groupId).size, "intent는 여전히 1")
        assertEquals(rows.map { it.expiresAt }, holdsOfGroup(g.groupId).map { it.expiresAt }, "재호출은 다시 연장하지 않는다")
        assertEquals(2 * AMOUNT, Adr017Pg.get(uid).payment!!.get("amount").asLong(), "재호출 등록 금액도 N × amount")
        assertGroupInvariants("묶음 결제 시작 뒤")
    }

    @Test
    fun `G4 — 묶음 결제 시작 404 · 403 · 만료 409 HOLD_EXPIRED · 무변화`() {
        val (a, b) = seats(2)
        val g = groupHoldOk(listOf(a, b), x)
        val before = snapshot()
        assertCode(groupStart(NO_SUCH_ID, x), 404, HOLD_NOT_FOUND, "없는 묶음")
        assertCode(groupStart(g.groupId, y), 403, HOLD_NOT_OWNED, "남의 묶음")
        clock.set(g.expiresAt.plusSeconds(1))
        assertCode(groupStart(g.groupId, x), 409, HOLD_EXPIRED, "만료 묶음")
        assertUnchanged(before, "결제 시작 거부")
    }

    @Test
    fun `G4 — 묶음 홀드에 1석 payment-start · 1석 확정 → 409 HOLD_IN_GROUP · 무변화`() {
        val (a, b) = seats(2)
        val g = groupHoldOk(listOf(a, b), x)
        val before = snapshot()
        g.holdIds.forEach { h ->
            assertCode(paymentStart(h, x), 409, HOLD_IN_GROUP, "묶음 홀드 1석 결제 시작")
            assertCode(confirmHttp(h, x, "pay-in-group"), 409, HOLD_IN_GROUP, "묶음 홀드 1석 확정")
        }
        assertUnchanged(before, "HOLD_IN_GROUP")
    }

    // ── G4 묶음 확정 ───────────────────────────────────────────────────────────────────────

    @Test
    fun `G4 · DV-6 — 묶음 확정 200 · 예약 N(좌석 오름차순 · 같은 intent) · 좌석 RESERVED · 홀드 0 · 카운터 그대로 · intent CONFIRMED · 재호출 같은 reservations`() {
        val (a, b) = seats(2)
        val g = groupHoldOk(listOf(a, b), x)
        val (uid, _) = groupStartOk(g.groupId, x)
        assertEquals(200, Adr017Pg.pay(uid).status, "PG 결제")

        val r = groupConfirm(g.groupId, x, uid)
        assertEquals(200, r.status, "묶음 확정: $r")
        assertTrue(r.keys().containsAll(GROUP_CONFIRM_KEYS) && (r.keys() - GROUP_CONFIRM_KEYS).all { it == "compensation" }, "본문 키: $r")
        assertEquals("CONFIRMED", r.json!!.get("status").asText())
        val res = r.json!!.get("reservations").map { it.get("seatId").asLong() to it.get("reservationId").asLong() }
        assertEquals(listOf(a, b), res.map { it.first }, "reservations = seatId 오름차순")

        val dbRes = jdbcTemplate.queryForList("SELECT id, seat_id, user_id, status FROM reservation ORDER BY seat_id")
        assertEquals(2, dbRes.size, "예약 N행: $dbRes")
        assertEquals(res.map { it.second }, dbRes.map { (it["id"] as Number).toLong() }, "응답 reservationId = 행 id")
        assertTrue(dbRes.all { (it["user_id"] as Number).toLong() == x && it["status"] == "CONFIRMED" }, "$dbRes")
        assertEquals(
            1, jdbcTemplate.queryForObject("SELECT count(DISTINCT intent_id) FROM reservation WHERE intent_id IS NOT NULL", Int::class.java),
            "예약 N행이 같은 intent",
        )
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE intent_id IS NULL", Int::class.java))
        assertEquals(listOf("RESERVED", "RESERVED"), seatsStatus(listOf(a, b)))
        assertEquals(0, holdsOf(x), "홀드 N행 → 예약 N행")
        assertEquals(2, cnt(x), "카운터 그대로")
        val i = intentsOfGroup(g.groupId).single()
        assertEquals(CONFIRMED, i.state)
        assertEquals(res.minOf { it.second }, i.reservationId, "reservation_id = 가장 작은 예약 id")
        assertTrue(tasks(uid).isEmpty(), "성공은 보상 작업 없음")

        val again = groupConfirm(g.groupId, x, uid)
        assertEquals(200, again.status, "재호출: $again")
        assertEquals(r.json!!.get("reservations"), again.json!!.get("reservations"), "재호출 = 같은 reservations")
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation", Int::class.java), "재호출이 예약을 늘리지 않는다")
        assertVerdictClean("묶음 확정 뒤")
        assertGroupInvariants("묶음 확정 뒤")
    }

    @Test
    fun `G4 · 설계-5 — 묶음 확정 — 모르는 uid · 없는 묶음 · 남의 묶음 = 409 SEAT_NOT_HELD · 거부 기록 없음 · 무변화`() {
        val (a, b, c, d) = seats(4)
        val g = groupHoldOk(listOf(a, b), x)
        val (uid, _) = groupStartOk(g.groupId, x)
        val gy = groupHoldOk(listOf(c, d), y)
        val (uidY, _) = groupStartOk(gy.groupId, y)
        val before = snapshot()
        val rejBefore = rejectionCount()
        assertCode(groupConfirm(g.groupId, x, "00000000-0000-0000-0000-000000000000"), 409, SEAT_NOT_HELD, "모르는 paymentUid")
        assertCode(groupConfirm(NO_SUCH_ID, x, uid), 409, SEAT_NOT_HELD, "없는 묶음")
        assertCode(groupConfirm(g.groupId, y, uid), 409, SEAT_NOT_HELD, "사용자가 다른 결제 의도")
        assertCode(groupConfirm(g.groupId, x, uidY), 409, SEAT_NOT_HELD, "묶음이 다른 결제 의도")
        assertEquals(rejBefore, rejectionCount(), "거부 기록 없음")
        assertUnchanged(before, "SEAT_NOT_HELD")
        assertEquals(listOf(OPEN, OPEN), listOf(intentsOfGroup(g.groupId).single().state, intentsOfGroup(gy.groupId).single().state), "결제 의도 그대로")
    }

    @Test
    fun `G4 — 확정 거부(만료 뒤) 409 HOLD_EXPIRED · N석 모두 그대로 · 예약 0 · intent REJECTED · CLOSE 작업 1건 · 환불 1회 · 금액 N × amount`() {
        val (a, b) = seats(2)
        val g = groupHoldOk(listOf(a, b), x)
        val (uid, start) = groupStartOk(g.groupId, x)
        assertEquals(200, Adr017Pg.pay(uid).status)
        clock.set(start.instant("expiresAt").plusSeconds(1))

        val r = groupConfirm(g.groupId, x, uid)
        assertCode(r, 409, HOLD_EXPIRED, "만료 뒤 확정")
        assertEquals(listOf("HELD", "HELD"), seatsStatus(listOf(a, b)), "N석 모두 그대로(롤백)")
        assertEquals(2, holdsOfGroup(g.groupId).size, "홀드 N행 그대로")
        assertEquals(0, reservationCount(), "예약 0")
        assertEquals(2, cnt(x), "카운터 그대로")
        assertEquals(REJECTED, intentsOfGroup(g.groupId).single().state)
        val t = closeTask(uid)
        assertEquals("PENDING", t.status)
        assertCompensation(r, t.id, "CLOSE", setOf("PENDING"), "거부 본문")

        runWorkerUntilDone(uid)
        val pg = Adr017Pg.get(uid)
        assertEquals("REFUNDED", pg.state, "$pg")
        assertEquals(1, pg.refunds, "CLOSE 1건 · 환불 1회")
        assertEquals(2 * AMOUNT, pg.payment!!.get("amount").asLong(), "환불 대상 금액 = N석 합")
        assertEquals(1, tasks(uid).size, "작업 1행")

        val again = groupConfirm(g.groupId, x, uid)
        assertCode(again, 409, HOLD_EXPIRED, "재호출 = 같은 거부")
        assertEquals(1, tasks(uid).size)

        expire.expire()
        assertEquals(listOf("AVAILABLE", "AVAILABLE"), seatsStatus(listOf(a, b)), "만료 배치가 묶음째 지운다")
        assertEquals(0, cnt(x), "카운터 −N")
        assertVerdictClean("거부 · 만료 뒤")
    }

    @Test
    fun `G4 — 묶음 홀드가 1행 빠진 비정상 묶음의 확정은 전부 거부(예약 0 · 남은 홀드 · 좌석 그대로)`() {
        val (a, b) = seats(2)
        val g = groupHoldOk(listOf(a, b), x)
        val (uid, _) = groupStartOk(g.groupId, x)
        assertEquals(200, Adr017Pg.pay(uid).status)
        // 비정상 모양을 직접 만든다(b의 홀드만 사라짐 — 설계-5 'DELETE … RETURNING이 N행이 아니면 거부')
        assertEquals(1, jdbcTemplate.update("DELETE FROM seat_hold WHERE id = ?", g.holds[b]!!))
        val r = groupConfirm(g.groupId, x, uid)
        assertTrue(r.status in 400..499, "부분 묶음 확정은 4xx 거부: $r")
        assertEquals(0, reservationCount(), "예약 0 — 부분 확정 없음")
        assertEquals("HELD", seatStatusOf(a), "남은 좌석 그대로")
        assertEquals(1, holdsOfGroup(g.groupId).size, "남은 홀드 그대로")
    }

    // ── 만료 · 안전망 ──────────────────────────────────────────────────────────────────────

    @Test
    fun `설계-6 — 묶음은 통째 만료 · 카운터 −N · 만료 전에는 그대로`() {
        val (a, b, c) = seats(3)
        val g = groupHoldOk(listOf(a, b), x)
        clock.set(g.expiresAt.minusSeconds(1))
        expire.expire()
        assertEquals(2, holdsOfGroup(g.groupId).size, "만료 전 그대로")
        assertEquals(2, cnt(x))
        val later = groupHoldOk(listOf(c), y) // 더 늦게 만료되는 묶음
        clock.set(g.expiresAt)
        expire.expire()
        assertEquals(0, holdsOfGroup(g.groupId).size, "만료 시각 = 묶음 통째 삭제")
        assertEquals(listOf("AVAILABLE", "AVAILABLE"), seatsStatus(listOf(a, b)))
        assertEquals(0, cnt(x), "카운터 −2")
        assertEquals(1, holdsOfGroup(later.groupId).size, "다른 묶음은 그대로")
        assertEquals(1, cnt(y))
        assertGroupInvariants("만료 뒤")
        assertVerdictClean("만료 뒤")
    }

    @Test
    fun `설계-6 — 결제 시작 · 결제 뒤 확정 없이 만료된 묶음 → 안전망이 CLOSE 1건 → 환불 1회(금액 N × amount)`() {
        val (a, b) = seats(2)
        val g = groupHoldOk(listOf(a, b), x)
        val (uid, start) = groupStartOk(g.groupId, x)
        assertEquals(200, Adr017Pg.pay(uid).status)
        clock.set(start.instant("expiresAt").plusSeconds(1))
        expire.expire()
        assertEquals(0, holdsOfGroup(g.groupId).size)
        assertEquals(0, cnt(x), "카운터 −N")
        assertTrue(sweep.runOnce() >= 1, "안전망이 묶음 intent를 닫는다")
        assertEquals(1, tasks(uid).size, "CLOSE 1건")
        assertEquals("CLOSE", tasks(uid).single().type)
        sweep.runOnce()
        assertEquals(1, tasks(uid).size, "다시 돌아도 1건")
        runWorkerUntilDone(uid)
        val pg = Adr017Pg.get(uid)
        assertEquals("REFUNDED", pg.state, "$pg")
        assertEquals(1, pg.refunds)
        assertEquals(2 * AMOUNT, pg.payment!!.get("amount").asLong())
        assertEquals(0, reservationCount())
    }

}
