package com.jun.labs.seatreservation.adr018

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015Gate
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.Adr015Taps
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.adr015.TapKind
import com.jun.labs.seatreservation.adr015.isHttp
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_INITIAL
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_MAX
import com.jun.labs.seatreservation.adr017.P17_LEASE
import com.jun.labs.seatreservation.adr017.P17_MAX_ATTEMPTS
import com.jun.labs.seatreservation.adr017.P17_MODE_REFUND
import com.jun.labs.seatreservation.adr017.P17_SCHEDULER_OFF
import com.jun.labs.seatreservation.domain.SeatStatus
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
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * G1 전부 아니면 전무 · G2 한도 · G3 교착 없음 — 두 다석 방식(split · one-shot)에서 같은 시나리오를 돈다(설계-2).
 *
 * 해석(모호점 — 보고에 적음):
 *  - payBy · payWithinSeconds는 '1석 선점 응답과 같은 규칙'(설계-2) → 같은 시각에 받은 1석 선점 응답의 값과 같다고 본다(값 산식을 따로 가정하지 않음).
 *  - 실패 '무변화'는 product_seat · seat_hold · user_hold_quota · hold_group · payment_intent · reservation · compensation_task의 전 행 대조로 본다.
 *  - 교착 없음(G3)은 ① 5xx 0 ② 409 code = SEAT_NOT_AVAILABLE ③ 각 요청 응답 시간 < 1초(PostgreSQL deadlock_timeout 기본값 — 교착 탐지는
 *    그만큼 기다린 뒤에야 난다 · NOWAIT · SKIP LOCKED면 기다림 자체가 없다)로 본다. 첫 2회는 예열이라 시간 조건에서 뺀다.
 *  - DV-3 칸의 래치는 카운터 +N 문장이 `insert into user_hold_quota`로 시작한다는 설계-2의 SQL 모양에 기댄다(닿지 않으면 실패로 드러난다).
 */
abstract class Adr018HoldGroupScenarios(private val mode: String) : Adr018Base() {

    // ── G1 성공 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `G1 — 2석 선점 성공 · 모두 HELD · 홀드 2 · 같은 group_id · 같은 expires_at · 카운터 +2 · hold_group 1행 · holds 좌석 오름차순`() {
        val (a, b) = seats(2)
        val single = seat()
        val r = groupHold(listOf(b, a), x) // 내림차순으로 보내도 응답은 오름차순
        val g = parseGroup(r, "[$mode] 묶음 선점")
        assertEquals(GROUP_HOLD_KEYS, r.keys(), "[$mode] 201 본문 키: $r")
        assertEquals(listOf(a, b), r.json!!.get("holds").map { it.get("seatId").asLong() }, "[$mode] holds = seatId 오름차순")
        assertEquals(NOW.plus(TTL_5M), g.expiresAt, "[$mode] expiresAt = 선점 시각 + TTL")

        // payBy · payWithinSeconds = 1석 선점과 같은 규칙(같은 시각의 1석 응답과 같은 값)
        val one = holdHttp(single, y)
        assertEquals(201, one.status, "$one")
        assertEquals(one.json!!.get("payBy")?.asText(), r.json!!.get("payBy")?.asText(), "[$mode] payBy = 1석 규칙")
        assertEquals(one.json!!.get("payWithinSeconds")?.asLong(), r.json!!.get("payWithinSeconds")?.asLong(), "[$mode] payWithinSeconds = 1석 규칙")

        assertEquals(listOf("HELD", "HELD"), seatsStatus(listOf(a, b)), "[$mode] N석 모두 HELD")
        val rows = holdsOfGroup(g.groupId)
        assertEquals(2, rows.size, "[$mode] 묶음 홀드 2행: ${dbState()}")
        assertEquals(listOf(a, b), rows.map { it.seatId })
        assertEquals(g.holdIds, rows.map { it.holdId }, "[$mode] 응답 holdId = 행 id")
        assertTrue(rows.all { it.userId == x }, "[$mode] 모두 요청자 것")
        assertEquals(setOf(g.expiresAt), rows.map { it.expiresAt }.toSet(), "[$mode] 같은 expires_at = 응답 expiresAt")
        assertEquals(2, holdsOf(x), "[$mode] 묶음 밖 홀드 없음")
        assertEquals(2, cnt(x), "[$mode] 카운터 +2")
        assertEquals(1, groupRows(), "[$mode] hold_group 1행")
        assertEquals(GroupRow(schedule.id!!, x, 2), groupRow(g.groupId), "[$mode] hold_group(schedule, user, seat_count)")
        assertGroupInvariants("[$mode]")
        assertVerdictClean("[$mode] 묶음 선점 뒤")
    }

    @Test
    fun `G1 — 1석 묶음도 된다(seatIds 1개 · seat_count 1)`() {
        val a = seat()
        val g = groupHoldOk(listOf(a), x)
        assertEquals(listOf(a), g.seatIds)
        assertEquals(1, cnt(x))
        assertEquals(1, groupRow(g.groupId).seatCount)
        assertEquals("HELD", seatStatusOf(a))
    }

    // ── G1 실패 단계별 무변화 ───────────────────────────────────────────────────────────────

    @Test
    fun `G1 — 판매 마감 409 SCHEDULE_CLOSED · 무변화`() {
        val (a, b) = seats(2)
        clock.set(schedule.startsAt.plusSeconds(1))
        val before = snapshot()
        assertCode(groupHold(listOf(a, b), x), 409, SCHEDULE_CLOSED, "[$mode] 마감")
        assertUnchanged(before, "[$mode] 마감")
    }

    @Test
    fun `G1 — 좌석 없음 · 다른 회차 좌석 404 SEAT_NOT_FOUND · 무변화`() {
        val (a, b) = seats(2)
        val other = otherScheduleSeat()
        val before = snapshot()
        assertCode(groupHold(listOf(a, NO_SUCH_ID), x), 404, SEAT_NOT_FOUND, "[$mode] 없는 좌석")
        assertUnchanged(before, "[$mode] 없는 좌석")
        assertCode(groupHold(listOf(b, other), x), 404, SEAT_NOT_FOUND, "[$mode] 다른 회차 좌석")
        assertUnchanged(before, "[$mode] 다른 회차 좌석")
    }

    @Test
    fun `G1 — 한 석이 남의 HELD · RESERVED면 409 SEAT_NOT_AVAILABLE · 나머지 좌석도 무변화`() {
        val (a, b, c) = seats(3)
        holdOk(b, y)
        jdbcTemplate.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", c)
        val before = snapshot()
        assertCode(groupHold(listOf(a, b), x), 409, SEAT_NOT_AVAILABLE, "[$mode] 남이 쥔 좌석")
        assertUnchanged(before, "[$mode] 남이 쥔 좌석")
        assertCode(groupHold(listOf(c, a), x), 409, SEAT_NOT_AVAILABLE, "[$mode] 팔린 좌석")
        assertUnchanged(before, "[$mode] 팔린 좌석")
        assertEquals("AVAILABLE", seatStatusOf(a))
        assertEquals(0, cnt(x))
    }

    @Test
    fun `G2 — 카운터 1인 사용자의 2석 요청 → 409 HOLD_LIMIT_EXCEEDED · 무변화(부분 증가 없음)`() {
        val (a, b, c) = seats(3)
        holdOk(c, x)
        assertEquals(1, cnt(x), "준비 — 카운터 1")
        val before = snapshot()
        assertCode(groupHold(listOf(a, b), x), 409, HOLD_LIMIT_EXCEEDED, "[$mode] 한도 초과")
        assertUnchanged(before, "[$mode] 한도 초과")
        // 1석 묶음은 한도 안이라 된다
        groupHoldOk(listOf(a), x)
        assertEquals(2, cnt(x))
    }

    @Test
    fun `G2 · 설계-2 — 좌석 불가가 한도 초과보다 먼저(카운터 1 · 고른 좌석 하나가 남의 HELD → SEAT_NOT_AVAILABLE)`() {
        val (a, b, c) = seats(3)
        holdOk(c, x)
        holdOk(b, y)
        val before = snapshot()
        assertCode(groupHold(listOf(a, b), x), 409, SEAT_NOT_AVAILABLE, "[$mode] 오류 순서 — 좌석 불가 → 한도 초과")
        assertUnchanged(before, "[$mode] 오류 순서")
    }

    @Test
    fun `DV-3 — 사전 확인 뒤 +N 0행 사이에 좌석이 팔렸으면 SEAT_NOT_AVAILABLE(한도 초과 아님) · 무변화`() {
        val (a, b, c) = seats(3)
        holdOk(c, x) // 카운터 1 → +2는 0행
        val gate = Adr015Taps.arm(Adr015Gate("counter+N")) { e ->
            e.kind == TapKind.SQL && isHttp(e.thread) && e.sql!!.startsWith("insert into user_hold_quota")
        }
        val f: CompletableFuture<Resp> = CompletableFuture.supplyAsync { groupHold(listOf(a, b), x) }
        reachedOrFail(gate, what = "[$mode] 카운터 +N 문장 앞")
        // x가 사전 확인을 지난 뒤 · +N 앞에서 멈춘 사이 y가 b를 1석으로 잡는다(게이트는 첫 도착 1칸뿐 — y는 지나간다)
        holdOk(b, y)
        val before = snapshot()
        gate.releaseAll()
        val r = f.get(60, java.util.concurrent.TimeUnit.SECONDS)
        assertCode(r, 409, SEAT_NOT_AVAILABLE, "[$mode] DV-3 — +N 0행이면 좌석을 다시 읽어 좌석 불가가 먼저")
        assertUnchanged(before, "[$mode] DV-3")
        assertEquals("AVAILABLE", seatStatusOf(a))
        assertEquals(1, cnt(x))
    }

    @Test
    fun `설계-2 · test-contract §2 — seatIds 없음 · 빈 배열 · 중복 · 한도 초과 개수 · 숫자 아님 = 400 INVALID_SEAT_SELECTION · 판매 마감보다 먼저 · 무변화`() {
        val (a, b, c) = seats(3)
        val bodies = listOf(
            "{}",
            """{"seatIds":[]}""",
            """{"seatIds":[$a,$a]}""",
            """{"seatIds":[$a,$b,$c]}""",
            """{"seatIds":["x"]}""",
        )
        val before = snapshot()
        bodies.forEach { body ->
            assertCode(groupHoldRaw(body, x), 400, INVALID_SEAT_SELECTION, "[$mode] 잘못된 선택 $body")
        }
        assertUnchanged(before, "[$mode] 잘못된 선택")
        clock.set(schedule.startsAt.plusSeconds(1))
        assertCode(groupHoldRaw("""{"seatIds":[]}""", x), 400, INVALID_SEAT_SELECTION, "[$mode] 본문 검사가 마감 검사보다 먼저")
        assertCode(groupHoldRaw("""{"seatIds":[$a,$a]}""", x), 400, INVALID_SEAT_SELECTION, "[$mode] 본문 검사가 마감 검사보다 먼저(중복)")
        assertUnchanged(before, "[$mode] 마감 + 잘못된 선택")
    }

    // ── G3 교착 없음 ───────────────────────────────────────────────────────────────────────

    data class Timed(val r: Resp, val ms: Long)

    private fun timed(block: () -> Resp): () -> Timed = {
        val t0 = System.nanoTime()
        val r = block()
        Timed(r, (System.nanoTime() - t0) / 1_000_000)
    }

    @Test
    fun `G3 — 겹치는 2석을 두 사용자가 반대 순서로 동시 요청 × 30 → 한 사람 2석 또는 둘 다 409 · 부분 상태 0 · 5xx 0 · 기다림 없음`() {
        val failures = mutableListOf<String>()
        var wins = 0
        repeat(30) { i ->
            val (a, b) = seats(2)
            val u1 = 10_000L + i
            val u2 = 20_000L + i
            val (t1, t2) = together(timed { groupHold(listOf(a, b), u1) }, timed { groupHold(listOf(b, a), u2) })
            val rs = listOf(t1.r, t2.r)
            val tag = "[$mode] #$i"
            rs.forEach { r ->
                if (r.status !in setOf(201, 409)) failures += "$tag 201/409 밖: $r"
                if (r.status == 409 && r.code != SEAT_NOT_AVAILABLE) failures += "$tag 409 code: $r"
            }
            // 교착은 위의 201/409 밖(5xx) 조건이 잡는다 — 시간 문턱은 GC · CI 부하의 거짓 실패를 피할 만큼 넉넉히(deadlock_timeout 1초의 5배)
            if (i >= 2) listOf(t1, t2).filter { it.ms >= 5000 }.forEach { failures += "$tag 응답 ${it.ms}ms ≥ 5초(잠금 대기 의심): ${it.r}" }
            val ok = rs.count { it.status == 201 }
            if (ok > 1) failures += "$tag 둘 다 201: $rs"
            val h1 = holdsOf(u1)
            val h2 = holdsOf(u2)
            if (h1 !in setOf(0, 2) || h2 !in setOf(0, 2)) failures += "$tag 부분 선점 u1=$h1 u2=$h2"
            if (cnt(u1) != h1 || cnt(u2) != h2) failures += "$tag 카운터 ≠ 홀드 u1=${cnt(u1)}/$h1 u2=${cnt(u2)}/$h2"
            val st = seatsStatus(listOf(a, b))
            if (ok == 1) {
                wins++
                if (st != listOf("HELD", "HELD")) failures += "$tag 승자 있는데 좌석 $st"
                if (h1 + h2 != 2) failures += "$tag 승자 홀드 수 ${h1 + h2}"
            } else if (st != listOf("AVAILABLE", "AVAILABLE")) {
                failures += "$tag 둘 다 409인데 좌석 $st"
            }
        }
        val partial = groupHoldInvariants()
        if (partial.values.any { it != 0L }) failures += "[$mode] 묶음 불변식 $partial"
        if (groupRows() != wins) failures += "[$mode] hold_group 행 ${groupRows()} ≠ 성공 $wins"
        assertTrue(failures.isEmpty(), "${failures.size}건:\n" + failures.joinToString("\n"))
        assertVerdictClean("[$mode] G3 반복 뒤")
    }

    @Test
    fun `G1 — 묶음(2석) ∥ 1석 선점이 한 좌석에서 겹침 × 20 → 묶음은 2석 또는 0석 · 5xx 0`() {
        val failures = mutableListOf<String>()
        repeat(20) { i ->
            val (a, b) = seats(2)
            val u1 = 30_000L + i
            val u2 = 40_000L + i
            val (g, s) = together({ groupHold(listOf(a, b), u1) }, { holdHttp(b, u2) })
            val tag = "[$mode] #$i"
            if (g.status !in setOf(201, 409) || s.status !in setOf(201, 409)) failures += "$tag 상태: 묶음 $g · 1석 $s"
            if (g.status == 201 && s.status == 201) failures += "$tag 둘 다 b를 잡았다: $g · $s"
            val h1 = holdsOf(u1)
            if (h1 !in setOf(0, 2)) failures += "$tag 묶음 부분 선점 $h1"
            if (cnt(u1) != h1) failures += "$tag 카운터 ${cnt(u1)} ≠ $h1"
            if (g.status != 201 && seatStatusOf(a) != "AVAILABLE") failures += "$tag 묶음 실패인데 a=${seatStatusOf(a)}"
        }
        assertTrue(failures.isEmpty(), "${failures.size}건:\n" + failures.joinToString("\n"))
        assertGroupInvariants("[$mode]")
        assertVerdictClean("[$mode] 묶음 ∥ 1석")
    }

    @Test
    fun `G1 — 이미 묶인 좌석을 남이 묶음으로 재요청하면 409 SEAT_NOT_AVAILABLE · 무변화`() {
        val (a, b) = seats(2)
        groupHoldOk(listOf(a, b), x)
        val before = snapshot()
        assertCode(groupHold(listOf(a, b), y), 409, SEAT_NOT_AVAILABLE, "[$mode] 이미 묶인 좌석")
        assertUnchanged(before, "[$mode] 이미 묶인 좌석")
        assertEquals(SeatStatus.HELD.name, seatStatusOf(b))
    }
}

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
        P18_ON, P18_SPLIT,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr018SplitModeTest : Adr018HoldGroupScenarios("split")

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
        P18_ON, P18_ONE_SHOT,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr018OneShotModeTest : Adr018HoldGroupScenarios("one-shot")
