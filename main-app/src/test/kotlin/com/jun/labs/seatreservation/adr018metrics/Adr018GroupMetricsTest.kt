package com.jun.labs.seatreservation.adr018metrics

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr016.M_CONFIRM_FAILED
import com.jun.labs.seatreservation.adr016.M_CONFIRM_REJECTED
import com.jun.labs.seatreservation.adr016.M16_EXTEND
import com.jun.labs.seatreservation.adr017.Adr017Pg
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_INITIAL
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_MAX
import com.jun.labs.seatreservation.adr017.P17_LEASE
import com.jun.labs.seatreservation.adr017.P17_MAX_ATTEMPTS
import com.jun.labs.seatreservation.adr017.P17_MODE_REFUND
import com.jun.labs.seatreservation.adr017.P17_SCHEDULER_OFF
import com.jun.labs.seatreservation.adr018.HOLD_EXPIRED
import com.jun.labs.seatreservation.adr018.HOLD_IN_GROUP
import com.jun.labs.seatreservation.adr018.HOLD_LIMIT_EXCEEDED
import com.jun.labs.seatreservation.adr018.HOLD_NOT_FOUND
import com.jun.labs.seatreservation.adr018.HOLD_NOT_OWNED
import com.jun.labs.seatreservation.adr018.INVALID_SEAT_SELECTION
import com.jun.labs.seatreservation.adr018.NO_SUCH_ID
import com.jun.labs.seatreservation.adr018.P18_ON
import com.jun.labs.seatreservation.adr018.P18_SPLIT
import com.jun.labs.seatreservation.adr018.PAYMENT_STARTED
import com.jun.labs.seatreservation.adr018.SCHEDULE_CLOSED
import com.jun.labs.seatreservation.adr018.SEAT_NOT_AVAILABLE
import com.jun.labs.seatreservation.adr018.SEAT_NOT_HELD
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * test-contract §2 — 기본 프로필(loadtest 아님) · 묶음 켬 · split · refund(흉내 PG).
 *   §1.1 seat.group.request{op, outcome}: op 5개마다 2xx · 도메인 거부 · 400(경로 인자 · 본문 결함)에서 정확히 1 · outcome 값 · 이중 셈 없음.
 *   §1.2 loadtest가 아닌 프로필: 묶음 409에 X-Seatlab-Reject 헤더 없음 · seat.group.hold.rejected 없음(합 0).
 *   §1.3 seat.db.deadlock{path}: group_hold · group_pay_start · group_confirm · release · expiry_tick — 40P01이면 1 · 다른 SQLSTATE면 무변화 ·
 *        그 요청도 seat.group.request에 응답대로 1(커밋 · 롤백 무관) · 만료 틱은 seat.hold.expiry.tick{failed}와 함께.
 *   1석 경로 무변화: 1석 선점 · 결제 시작 · 확정은 seat.group.request에 들어가지 않고 seat.confirm.*는 지금 규칙대로 오른다.
 *
 * 해석(모호점 — 보고에 적음):
 *  - 'ErrorCode로 나간 응답' = 오류 본문에 code 문자열이 있는 응답. 400 INVALID_SEAT_SELECTION도 ErrorCode라 outcome = INVALID_SEAT_SELECTION(status_400 아님).
 *  - 경로 인자 형식 오류(`/api/hold-groups/abc/...`)는 핸들러가 고른 뒤의 400 → status_400(1석 확정의 OQ-2 선례 — Adr016MetricsFixTest).
 *    본문 결함(JSON 깨짐)은 묶음 선점이 INVALID_SEAT_SELECTION으로 낼 수도 있어(1단계 '숫자 아님' 규칙) 상태 400만 고정하고 outcome은 응답에서 계산한다.
 *  - path=release는 해제 API 두 개(묶음 · 1석) 모두다(group_ 접두가 없다).
 *  - 40P01 요청의 응답 상태는 '지금과 같다'(계약)라 고정하지 않는다 — 2xx가 아니고 쓰기가 롤백됐는지만 본다.
 * 컨텍스트 키 = Adr018GroupLifecycleTest와 같다(캐시 공유).
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
class Adr018GroupMetricsTest : Adr018MetricsBase() {

    private val z = 300L

    private fun assertStatus(r: HResp, status: Int, why: String) = assertEquals(status, r.status, "$why: $r")

    // ── §1.1 op = hold ─────────────────────────────────────────────────────────────────────

    @Test
    fun `1·1 hold — 201 ok · SEAT_NOT_AVAILABLE · HOLD_LIMIT_EXCEEDED · INVALID_SEAT_SELECTION · SCHEDULE_CLOSED · 400(경로 인자 · 사용자 헤더 없음 · 본문 결함) 각 1`() {
        val (a, b, c, d, e) = seats(5)
        assertStatus(counted(OP_HOLD, "201", "ok") { gHold(listOf(a, b), x) }, 201, "묶음 선점 201")
        // 남의 HELD 좌석이 섞임(사전 확인) — y는 아직 홀드 0
        counted(OP_HOLD, "남의 HELD", SEAT_NOT_AVAILABLE) { gHold(listOf(b, c), y) }.also { assertStatus(it, 409, "좌석 불가") }
        holdOk(c, y) // y 카운터 1 → 2석 묶음은 한도 초과
        counted(OP_HOLD, "한도 초과", HOLD_LIMIT_EXCEEDED) { gHold(listOf(d, e), y) }.also { assertStatus(it, 409, "한도 초과") }
        counted(OP_HOLD, "빈 선택", INVALID_SEAT_SELECTION) { req("POST", "/api/schedules/${schedule.id}/hold-groups", z, """{"seatIds":[]}""") }
            .also { assertStatus(it, 400, "INVALID_SEAT_SELECTION은 400 도메인 거부") }
        counted(OP_HOLD, "경로 인자(scheduleId 숫자 아님)", "status_400") { gHold(listOf(d), z, scheduleId = "abc") }
        counted(OP_HOLD, "사용자 헤더 없음", "status_400") { req("POST", "/api/schedules/${schedule.id}/hold-groups", null, seatIdsBody(listOf(d))) }
        counted(OP_HOLD, "본문 결함(JSON 깨짐)") { req("POST", "/api/schedules/${schedule.id}/hold-groups", z, """{"seatIds":[""") }
            .also { assertStatus(it, 400, "본문 결함 400") }
        clock.set(schedule.startsAt.plusSeconds(1))
        counted(OP_HOLD, "판매 마감", SCHEDULE_CLOSED) { gHold(listOf(d), z) }.also { assertStatus(it, 409, "마감") }
        assertGroupInvariants("hold 묶음 뒤")
    }

    // ── §1.1 op = release_group ────────────────────────────────────────────────────────────

    @Test
    fun `1·1 release_group — 204 ok · HOLD_NOT_OWNED · HOLD_NOT_FOUND · PAYMENT_STARTED · 400 경로 인자 각 1`() {
        val (a, b, c, d) = seats(4)
        val g = groupHoldOk(listOf(a, b), x)
        counted(OP_RELEASE_GROUP, "남의 묶음", HOLD_NOT_OWNED) { gRelease(g.groupId, y) }.also { assertStatus(it, 403, "남의 것") }
        counted(OP_RELEASE_GROUP, "없는 묶음", HOLD_NOT_FOUND) { gRelease(NO_SUCH_ID, x) }.also { assertStatus(it, 404, "없음") }
        counted(OP_RELEASE_GROUP, "경로 인자", "status_400") { gRelease("abc", x) }
        counted(OP_RELEASE_GROUP, "204", "ok") { gRelease(g.groupId, x) }.also { assertStatus(it, 204, "해제") }
        val g2 = groupHoldOk(listOf(c, d), x)
        groupStartOk(g2.groupId, x)
        counted(OP_RELEASE_GROUP, "결제 시작 뒤", PAYMENT_STARTED) { gRelease(g2.groupId, x) }.also { assertStatus(it, 409, "결제 시작 뒤") }
    }

    // ── §1.1 op = release_hold ─────────────────────────────────────────────────────────────

    @Test
    fun `1·1 release_hold — 204 ok · HOLD_IN_GROUP · HOLD_NOT_FOUND · HOLD_NOT_OWNED · 400 경로 인자 각 1`() {
        val (a, b, c) = seats(3)
        val g = groupHoldOk(listOf(a, b), x)
        counted(OP_RELEASE_HOLD, "묶음 홀드", HOLD_IN_GROUP) { hRelease(g.holdIds.first(), x) }.also { assertStatus(it, 409, "묶음 홀드") }
        counted(OP_RELEASE_HOLD, "없는 홀드", HOLD_NOT_FOUND) { hRelease(NO_SUCH_ID, x) }.also { assertStatus(it, 404, "없음") }
        counted(OP_RELEASE_HOLD, "경로 인자", "status_400") { hRelease("abc", x) }
        val h = holdOk(c, y)
        counted(OP_RELEASE_HOLD, "남의 홀드", HOLD_NOT_OWNED) { hRelease(h, x) }.also { assertStatus(it, 403, "남의 것") }
        counted(OP_RELEASE_HOLD, "204", "ok") { hRelease(h, y) }.also { assertStatus(it, 204, "해제") }
    }

    // ── §1.1 op = pay_start ────────────────────────────────────────────────────────────────

    @Test
    fun `1·1 pay_start — 200 ok(첫 호출 · 재호출) · HOLD_NOT_OWNED · HOLD_EXPIRED · 없는 묶음 · 400 경로 인자 각 1`() {
        val (a, b, c, d) = seats(4)
        val g = groupHoldOk(listOf(a, b), x)
        counted(OP_PAY_START, "남의 묶음", HOLD_NOT_OWNED) { gStart(g.groupId, y) }.also { assertStatus(it, 403, "남의 것") }
        counted(OP_PAY_START, "경로 인자", "status_400") { gStart("abc", x) }
        counted(OP_PAY_START, "없는 묶음") { gStart(NO_SUCH_ID, x) }.also {
            assertStatus(it, 404, "없는 묶음")
            assertTrue(it.code != null, "없는 묶음은 도메인 거부(ErrorCode)여야 한다 — 핸들러 없음 404가 아니다: $it")
        }
        counted(OP_PAY_START, "첫 호출 200", "ok") { gStart(g.groupId, x) }.also { assertStatus(it, 200, "결제 시작") }
        counted(OP_PAY_START, "재호출 200", "ok") { gStart(g.groupId, x) }.also { assertStatus(it, 200, "재호출") }
        val g2 = groupHoldOk(listOf(c, d), y)
        clock.set(g2.expiresAt.plusSeconds(1))
        counted(OP_PAY_START, "만료 뒤", HOLD_EXPIRED) { gStart(g2.groupId, y) }.also { assertStatus(it, 409, "만료") }
    }

    // ── §1.1 op = confirm ──────────────────────────────────────────────────────────────────

    @Test
    fun `1·1 confirm — 200 ok(첫 호출 · 재호출) · SEAT_NOT_HELD · HOLD_EXPIRED · 400(본문 결함 · 경로 인자) 각 1`() {
        val (a, b, c, d) = seats(4)
        val g = groupHoldOk(listOf(a, b), x)
        val (uid, _) = groupStartOk(g.groupId, x)
        assertEquals(200, Adr017Pg.pay(uid).status, "준비 — PG 결제")
        counted(OP_CONFIRM, "모르는 uid", SEAT_NOT_HELD) { gConfirm(g.groupId, x, UUID.randomUUID().toString()) }.also { assertStatus(it, 409, "모르는 uid") }
        counted(OP_CONFIRM, "본문 결함(JSON 깨짐)") { req("POST", "/api/hold-groups/${g.groupId}/confirm", x, """{"paymentUid":""") }
            .also { assertStatus(it, 400, "본문 결함") }
        counted(OP_CONFIRM, "경로 인자", "status_400") { gConfirm("abc", x, uid) }
        counted(OP_CONFIRM, "200", "ok") { gConfirm(g.groupId, x, uid) }.also { assertStatus(it, 200, "확정") }
        counted(OP_CONFIRM, "재호출 200", "ok") { gConfirm(g.groupId, x, uid) }.also { assertStatus(it, 200, "재호출") }

        val g2 = groupHoldOk(listOf(c, d), y)
        val (uid2, s2) = groupStartOk(g2.groupId, y)
        assertEquals(200, Adr017Pg.pay(uid2).status, "준비 — PG 결제 2")
        clock.set(s2.instant("expiresAt").plusSeconds(1))
        counted(OP_CONFIRM, "만료 뒤(refund 거부)", HOLD_EXPIRED) { gConfirm(g2.groupId, y, uid2) }.also { assertStatus(it, 409, "만료 거부") }
    }

    // ── §1.2 loadtest가 아닌 프로필 ─────────────────────────────────────────────────────────

    @Test
    fun `1·2 loadtest 아님 — 묶음 409(경합 · 한도)와 1석 409에 X-Seatlab-Reject 헤더 없음 · seat-group-hold-rejected 없음 · seat-hold-rejected 무변화`() {
        val (a, b, c, d, e) = seats(5)
        val h0 = snap(M_HOLD_REJECTED, "cause")
        val r1 = counted(OP_HOLD, "경합(좌석 행 잠김)", SEAT_NOT_AVAILABLE) { withSeatRowLocked(b) { gHold(listOf(a, b), x) } }
        assertStatus(r1, 409, "경합 409")
        assertEquals(emptyList(), r1.headerValues(REJECT_HEADER), "loadtest 아님 — 묶음 409에 헤더 없음: $r1")
        holdOk(c, y)
        val r2 = counted(OP_HOLD, "한도 초과", HOLD_LIMIT_EXCEEDED) { gHold(listOf(d, e), y) }
        assertEquals(emptyList(), r2.headerValues(REJECT_HEADER), "loadtest 아님 — 한도 초과에 헤더 없음: $r2")
        val r3 = notCounted("1석 선점 경합") { withSeatRowLocked(a) { hHold(a, z) } }
        assertStatus(r3, 409, "1석 경합 409")
        assertEquals(emptyList(), r3.headerValues(REJECT_HEADER), "loadtest 아님 — 1석 409에도 헤더 없음(1석과 같은 규칙): $r3")
        assertEquals(0.0, meters.find(M_GROUP_HOLD_REJECTED).counters().sumOf { it.count() }, "loadtest 아님 — $M_GROUP_HOLD_REJECTED 없음(합 0)")
        assertEquals(emptyMap(), diff(h0, snap(M_HOLD_REJECTED, "cause")), "loadtest 아님 — $M_HOLD_REJECTED 무변화")
        assertEquals(0, holdsOf(x), "경합 409 — x 홀드 없음")
    }

    // ── §1.3 seat.db.deadlock ──────────────────────────────────────────────────────────────

    private val injected = listOf(SQLSTATE_SERIALIZATION, SQLSTATE_DEADLOCK, SQLSTATE_RAISE)

    @Test
    fun `1·3 group_hold — 홀드 INSERT가 40P01이면 deadlock{group_hold} 1 · 다른 SQLSTATE는 무변화 · 요청은 응답대로 1 · 롤백`() {
        injected.forEachIndexed { i, st ->
            val (a, b) = seats(2)
            val u = 1_000L + i
            val d0 = snap(M_DB_DEADLOCK, "path")
            val r = counted(OP_HOLD, "group_hold $st") {
                withSqlState("seat_hold", "INSERT", st, "NEW.group_id IS NOT NULL") { gHold(listOf(a, b), u) }
            }
            assertTrue(r.status !in 200..299, "주입 $st — 묶음 선점은 실패해야 한다: $r")
            assertDeadlockDiff(d0, st, "group_hold", "group_hold")
            assertEquals(0, holdsOf(u), "주입 $st — 롤백(홀드 없음)")
            assertEquals(0, cnt(u), "주입 $st — 롤백(카운터 0)")
            assertEquals(listOf("AVAILABLE", "AVAILABLE"), seatsStatus(listOf(a, b)), "주입 $st — 좌석 그대로")
        }
    }

    @Test
    fun `1·3 group_pay_start — 결제 의도 INSERT가 40P01이면 deadlock{group_pay_start} 1 · 다른 SQLSTATE는 무변화 · 요청은 응답대로 1`() {
        injected.forEachIndexed { i, st ->
            val (a, b) = seats(2)
            val u = 2_000L + i
            val g = groupHoldOk(listOf(a, b), u)
            val d0 = snap(M_DB_DEADLOCK, "path")
            val r = counted(OP_PAY_START, "group_pay_start $st") {
                withSqlState("payment_intent", "INSERT", st, "NEW.group_id IS NOT NULL") { gStart(g.groupId, u) }
            }
            assertTrue(r.status !in 200..299, "주입 $st — 결제 시작은 실패해야 한다: $r")
            assertDeadlockDiff(d0, st, "group_pay_start", "group_pay_start")
            assertEquals(emptyList(), intentsOfGroup(g.groupId), "주입 $st — 결제 의도 없음(롤백)")
        }
    }

    @Test
    fun `1·3 group_confirm — 예약 INSERT가 40P01이면 deadlock{group_confirm} 1 · 다른 SQLSTATE는 무변화 · 요청은 응답대로 1 · 홀드 그대로`() {
        injected.forEachIndexed { i, st ->
            val (a, b) = seats(2)
            val u = 3_000L + i
            val g = groupHoldOk(listOf(a, b), u)
            val (uid, _) = groupStartOk(g.groupId, u)
            assertEquals(200, Adr017Pg.pay(uid).status, "준비 — PG 결제")
            val d0 = snap(M_DB_DEADLOCK, "path")
            val r = counted(OP_CONFIRM, "group_confirm $st") {
                withSqlState("reservation", "INSERT", st) { gConfirm(g.groupId, u, uid) }
            }
            assertTrue(r.status !in 200..299, "주입 $st — 확정은 실패해야 한다: $r")
            assertDeadlockDiff(d0, st, "group_confirm", "group_confirm")
            assertEquals(2, holdsOfGroup(g.groupId).size, "주입 $st — 롤백(묶음 홀드 2행 그대로)")
            assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE seat_id IN (?, ?)", Int::class.java, a, b), "주입 $st — 예약 없음")
        }
    }

    @Test
    fun `1·3 release — 묶음 해제 · 1석 해제의 홀드 DELETE가 40P01이면 deadlock{release} 1 · 다른 SQLSTATE는 무변화 · 요청은 응답대로 1`() {
        injected.forEachIndexed { i, st ->
            val (a, b, c) = seats(3)
            val u = 4_000L + i
            val v = 5_000L + i
            val g = groupHoldOk(listOf(a, b), u)
            val h = holdOk(c, v)

            val d0 = snap(M_DB_DEADLOCK, "path")
            val r = counted(OP_RELEASE_GROUP, "release(group) $st") { withSqlState("seat_hold", "DELETE", st) { gRelease(g.groupId, u) } }
            assertTrue(r.status !in 200..299, "주입 $st — 묶음 해제는 실패해야 한다: $r")
            assertDeadlockDiff(d0, st, "release", "release(묶음 해제)")
            assertEquals(2, holdsOfGroup(g.groupId).size, "주입 $st — 롤백(묶음 홀드 그대로)")

            val d1 = snap(M_DB_DEADLOCK, "path")
            val r1 = counted(OP_RELEASE_HOLD, "release(hold) $st") { withSqlState("seat_hold", "DELETE", st) { hRelease(h, v) } }
            assertTrue(r1.status !in 200..299, "주입 $st — 1석 해제는 실패해야 한다: $r1")
            assertDeadlockDiff(d1, st, "release", "release(1석 해제)")
            assertTrue(holdExists(h), "주입 $st — 롤백(1석 홀드 그대로)")
        }
    }

    @Test
    fun `1·3 expiry_tick — 만료 틱이 40P01로 롤백되면 deadlock{expiry_tick} 1과 tick{failed} 1 · 다른 SQLSTATE는 tick{failed}만`() {
        injected.forEachIndexed { i, st ->
            val (a, b) = seats(2)
            val u = 6_000L + i
            clock.set(NOW)
            val g = groupHoldOk(listOf(a, b), u)
            clock.set(g.expiresAt.plusSeconds(1))
            val d0 = snap(M_DB_DEADLOCK, "path")
            val t0 = snap(M_TICK, "outcome")
            val res = withSqlState("seat_hold", "DELETE", st) { runCatching { expire.expire() } }
            assertTrue(res.isFailure, "주입 $st — 만료 틱은 롤백(예외)이어야 한다: $res")
            assertEquals(1.0, diff(t0, snap(M_TICK, "outcome"))[listOf<String?>("failed")], "주입 $st — $M_TICK{failed} +1(그대로 함께 오른다)")
            assertDeadlockDiff(d0, st, "expiry_tick", "expiry_tick")
            assertEquals(2, holdsOfGroup(g.groupId).size, "주입 $st — 롤백(홀드 그대로)")
            // 정리 — 다음 반복이 이 홀드를 다시 만나지 않게(주입 없이 틱 성공)
            expire.expire()
        }
    }

    // ── 1석 경로 무변화 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `1석 무변화 — 1석 선점 · 결제 시작 · 확정은 seat-group-request에 없고 confirm-extend · confirm-rejected · confirm-failed는 지금 규칙대로`() {
        val (a, b) = seats(2)
        val q0 = snap(M_GROUP_REQUEST, "op", "outcome")
        val g0 = snap(M_GROUP_HOLD_REJECTED, "mode", "cause")

        val h = holdOk(a, x)
        // ④(conditional-extend) 결제 시작의 기존 지표 = seat.confirm.extend{result}(Adr016ExtendPaymentStartTest N16-8) — 남은 5분 < W 611초 → extended
        val e0 = extendCounts()
        val (uid, _) = startOk(h, x)
        assertEquals(e0.mapValues { (k, v) -> if (k == "extended") v + 1 else v }, extendCounts(), "1석 결제 시작 200 — $M16_EXTEND{extended}만 +1")
        assertEquals(200, Adr017Pg.pay(uid).status, "준비 — PG 결제")

        val rej0 = snap(M_CONFIRM_REJECTED, "code")
        val fail0 = snap(M_CONFIRM_FAILED, "status")
        assertEquals(200, confirmHttp(h, x, uid).status, "1석 확정 200")
        assertEquals(emptyMap(), diff(rej0, snap(M_CONFIRM_REJECTED, "code")), "1석 확정 200 — rejected 무변화")
        assertEquals(emptyMap(), diff(fail0, snap(M_CONFIRM_FAILED, "status")), "1석 확정 200 — failed 무변화")

        val rejected = confirmHttp(NO_SUCH_ID, x, UUID.randomUUID().toString())
        assertTrue(rejected.status in setOf(403, 404, 409) && rejected.code != null, "준비 — 1석 확정 도메인 거부: $rejected")
        assertEquals(mapOf<TagKey, Double>(listOf<String?>(rejected.code) to 1.0), diff(rej0, snap(M_CONFIRM_REJECTED, "code")),
            "1석 확정 도메인 거부 — $M_CONFIRM_REJECTED{code=${rejected.code}}만 +1")
        assertEquals(emptyMap(), diff(fail0, snap(M_CONFIRM_FAILED, "status")), "1석 확정 도메인 거부 — failed 무변화")

        val bad = rawPost("/api/holds/abc/confirm", x, "application/json", """{"paymentUid":"p"}""")
        assertEquals(400, bad.status, "준비 — 1석 확정 경로 인자 400: $bad")
        assertEquals(mapOf<TagKey, Double>(listOf<String?>("400") to 1.0), diff(fail0, snap(M_CONFIRM_FAILED, "status")),
            "1석 확정 400 — $M_CONFIRM_FAILED{status=400}만 +1")

        assertEquals(201, holdHttp(b, y).status, "1석 선점 201")
        assertEquals(409, holdHttp(b, x).status, "1석 선점 409(남의 좌석)")

        assertEquals(emptyMap(), diff(q0, snap(M_GROUP_REQUEST, "op", "outcome")), "1석 선점 · 결제 시작 · 확정은 $M_GROUP_REQUEST 에 들어가지 않는다")
        assertEquals(emptyMap(), diff(g0, snap(M_GROUP_HOLD_REJECTED, "mode", "cause")), "1석 경로는 $M_GROUP_HOLD_REJECTED 에 들어가지 않는다")
    }
}
