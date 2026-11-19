package com.jun.labs.seatreservation.adr018metrics

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr016.ERROR_KEYS
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_INITIAL
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_MAX
import com.jun.labs.seatreservation.adr017.P17_LEASE
import com.jun.labs.seatreservation.adr017.P17_MAX_ATTEMPTS
import com.jun.labs.seatreservation.adr017.P17_MODE_REFUND
import com.jun.labs.seatreservation.adr017.P17_SCHEDULER_OFF
import com.jun.labs.seatreservation.adr018.HOLD_LIMIT_EXCEEDED
import com.jun.labs.seatreservation.adr018.INVALID_SEAT_SELECTION
import com.jun.labs.seatreservation.adr018.P18_ON
import com.jun.labs.seatreservation.adr018.P18_ONE_SHOT
import com.jun.labs.seatreservation.adr018.P18_SPLIT
import com.jun.labs.seatreservation.adr018.SCHEDULE_CLOSED
import com.jun.labs.seatreservation.adr018.SEAT_NOT_AVAILABLE
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
import kotlin.test.assertEquals

/*
 * test-contract §1.2 · §2 — loadtest 프로필 · 묶음 켬 · refund.
 *   seat.group.hold.rejected{mode, cause} + 헤더 X-Seatlab-Reject: group-<cause> · 상태 · 본문 그대로 · 1석 seat.hold.rejected 무변화.
 *   경합은 다른 연결이 좌석 행 하나를 FOR UPDATE로 쥔 채 보낸다(결정적): split = NOWAIT 패배 → nowait · one-shot = SKIP LOCKED로 바뀐 행 수 < N → short.
 *   그 밖의 선점 409(한도 초과 · 판매 마감 · 사전 확인의 남의 HELD) → rule.
 *   1석 선점의 seat.hold.rejected{cause=nowait|rule}와 헤더 nowait · rule은 그대로(묶음 지표에 섞이지 않음).
 *
 * 해석(모호점 — 보고에 적음):
 *  - 사전 확인(설계-2 2단계 — 두 방식 공통)에서 걸리는 남의 HELD 좌석은 경합이 아니라 rule로 본다(nowait · short는 잠금 경합 갈래).
 *  - 400(INVALID_SEAT_SELECTION)은 '선점 409'가 아니라 헤더 · 거절 지표가 없다.
 * 컨텍스트: split = Adr018ConsistencyTest와 같은 키(캐시 공유) · Adr015TapConfig 없음(loadtest의 실제 AppPathInjectHook과 빈이 둘이 된다 — 그 시험 주석).
 */
abstract class Adr018GroupHoldRejectScenarios(private val modeTag: String, private val contentionCause: String) : Adr018MetricsBase() {

    private val z = 300L

    /** 묶음 선점 409 하나 — 상태 · code · 본문 모양 · 헤더 · 거절 지표 {mode, cause} 1 · 1석 지표 무변화 · 요청 지표 1 */
    private fun rejected(why: String, code: String, cause: String, call: () -> HResp): HResp {
        val g0 = snap(M_GROUP_HOLD_REJECTED, "mode", "cause")
        val h0 = snap(M_HOLD_REJECTED, "cause")
        val r = counted(OP_HOLD, "[$modeTag] $why", code, call)
        assertEquals(409, r.status, "[$modeTag] $why — 상태 그대로 409: $r")
        assertEquals(ERROR_KEYS, r.keys(), "[$modeTag] $why — 본문 모양 그대로 {code, message}: $r")
        assertEquals(listOf("group-$cause"), r.headerValues(REJECT_HEADER), "[$modeTag] $why — 헤더 $REJECT_HEADER: $r")
        val d = awaitDiff(M_GROUP_HOLD_REJECTED, arrayOf("mode", "cause"), g0, 1.0)
        assertEquals(mapOf<TagKey, Double>(listOf<String?>(modeTag, cause) to 1.0), d, "[$modeTag] $why — $M_GROUP_HOLD_REJECTED{mode=$modeTag, cause=$cause}만 1")
        assertEquals(emptyMap(), diff(h0, snap(M_HOLD_REJECTED, "cause")), "[$modeTag] $why — 묶음 409는 1석 $M_HOLD_REJECTED 를 올리지 않는다")
        return r
    }

    @Test
    fun `1·2 경합 — 좌석 하나가 잠겨 있으면 split은 nowait · one-shot은 short · 헤더 group-cause · 무변화 · 풀리면 201(헤더 없음)`() {
        val (a, b) = seats(2)
        rejected("경합(좌석 행 잠김)", SEAT_NOT_AVAILABLE, contentionCause) { withSeatRowLocked(b) { gHold(listOf(a, b), x) } }
        assertEquals(0, holdsOf(x), "[$modeTag] 경합 409 — 홀드 없음")
        assertEquals(0, cnt(x), "[$modeTag] 경합 409 — 카운터 0")
        assertEquals(listOf("AVAILABLE", "AVAILABLE"), seatsStatus(listOf(a, b)), "[$modeTag] 경합 409 — 좌석 그대로")
        assertGroupInvariants("[$modeTag] 경합 뒤")

        val g0 = snap(M_GROUP_HOLD_REJECTED, "mode", "cause")
        val h0 = snap(M_HOLD_REJECTED, "cause")
        val ok = counted(OP_HOLD, "[$modeTag] 잠금 풀린 뒤 201", "ok") { gHold(listOf(a, b), x) }
        assertEquals(201, ok.status, "[$modeTag] $ok")
        assertEquals(emptyList(), ok.headerValues(REJECT_HEADER), "[$modeTag] 201에는 헤더 없음: $ok")
        assertEquals(emptyMap(), diff(g0, snap(M_GROUP_HOLD_REJECTED, "mode", "cause")), "[$modeTag] 201 — 거절 지표 무변화")
        assertEquals(emptyMap(), diff(h0, snap(M_HOLD_REJECTED, "cause")), "[$modeTag] 201 — 1석 거절 지표 무변화")
    }

    @Test
    fun `1·2 rule — 한도 초과 · 사전 확인의 남의 HELD · 판매 마감은 rule · 헤더 group-rule`() {
        val (a, b, c, d, e) = seats(5)
        holdOk(a, y) // y 카운터 1
        rejected("한도 초과", HOLD_LIMIT_EXCEEDED, "rule") { gHold(listOf(b, c), y) }
        rejected("남의 HELD(사전 확인)", SEAT_NOT_AVAILABLE, "rule") { gHold(listOf(a, d), x) }
        clock.set(schedule.startsAt.plusSeconds(1))
        rejected("판매 마감", SCHEDULE_CLOSED, "rule") { gHold(listOf(d, e), z) }
        assertEquals(0, holdsOf(x), "[$modeTag] rule 409 — x 홀드 없음")
    }

    @Test
    fun `1·2 400 INVALID_SEAT_SELECTION은 선점 409가 아니다 — 헤더 없음 · 거절 지표 무변화`() {
        val g0 = snap(M_GROUP_HOLD_REJECTED, "mode", "cause")
        val h0 = snap(M_HOLD_REJECTED, "cause")
        val r = counted(OP_HOLD, "[$modeTag] 빈 선택", INVALID_SEAT_SELECTION) {
            req("POST", "/api/schedules/${schedule.id}/hold-groups", x, """{"seatIds":[]}""")
        }
        assertEquals(400, r.status, "[$modeTag] $r")
        assertEquals(emptyList(), r.headerValues(REJECT_HEADER), "[$modeTag] 400에는 헤더 없음: $r")
        assertEquals(emptyMap(), diff(g0, snap(M_GROUP_HOLD_REJECTED, "mode", "cause")), "[$modeTag] 400 — 묶음 거절 지표 무변화")
        assertEquals(emptyMap(), diff(h0, snap(M_HOLD_REJECTED, "cause")), "[$modeTag] 400 — 1석 거절 지표 무변화")
    }

    @Test
    fun `1석 무변화(loadtest) — 1석 NOWAIT 패배는 seat-hold-rejected{nowait} 1 · 헤더 nowait · 한도 초과는 rule · 묶음 지표 무변화`() {
        val (a, b, c, d) = seats(4)
        val g0 = snap(M_GROUP_HOLD_REJECTED, "mode", "cause")
        val q0 = snap(M_GROUP_REQUEST, "op", "outcome")

        val h0 = snap(M_HOLD_REJECTED, "cause")
        val nw = withSeatRowLocked(a) { hHold(a, x) }
        assertEquals(409, nw.status, "[$modeTag] 1석 NOWAIT 패배 409: $nw")
        assertEquals(SEAT_NOT_AVAILABLE, nw.code, "[$modeTag] 1석 NOWAIT 패배 code: $nw")
        assertEquals(listOf("nowait"), nw.headerValues(REJECT_HEADER), "[$modeTag] 1석 헤더 nowait 그대로: $nw")
        assertEquals(mapOf<TagKey, Double>(listOf<String?>("nowait") to 1.0), awaitDiff(M_HOLD_REJECTED, arrayOf("cause"), h0, 1.0),
            "[$modeTag] 1석 $M_HOLD_REJECTED{cause=nowait} 1")

        holdOk(b, x)
        holdOk(c, x)
        val h1 = snap(M_HOLD_REJECTED, "cause")
        val rule = hHold(d, x)
        assertEquals(409, rule.status, "[$modeTag] 1석 한도 초과 409: $rule")
        assertEquals(HOLD_LIMIT_EXCEEDED, rule.code, "[$modeTag] 1석 한도 초과 code: $rule")
        assertEquals(listOf("rule"), rule.headerValues(REJECT_HEADER), "[$modeTag] 1석 헤더 rule 그대로: $rule")
        assertEquals(mapOf<TagKey, Double>(listOf<String?>("rule") to 1.0), awaitDiff(M_HOLD_REJECTED, arrayOf("cause"), h1, 1.0),
            "[$modeTag] 1석 $M_HOLD_REJECTED{cause=rule} 1")

        val ok = hHold(a, y)
        assertEquals(201, ok.status, "[$modeTag] 1석 201: $ok")
        assertEquals(emptyList(), ok.headerValues(REJECT_HEADER), "[$modeTag] 1석 201에는 헤더 없음: $ok")

        assertEquals(emptyMap(), diff(g0, snap(M_GROUP_HOLD_REJECTED, "mode", "cause")), "[$modeTag] 1석 경로는 $M_GROUP_HOLD_REJECTED 에 들어가지 않는다")
        assertEquals(emptyMap(), diff(q0, snap(M_GROUP_REQUEST, "op", "outcome")), "[$modeTag] 1석 선점은 $M_GROUP_REQUEST 에 들어가지 않는다")
    }
}

@ActiveProfiles("loadtest")
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
        P18_ON, P18_SPLIT,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr018SplitHoldRejectLoadtestTest : Adr018GroupHoldRejectScenarios("split", "nowait")

@ActiveProfiles("loadtest")
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
        P18_ON, P18_ONE_SHOT,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr018OneShotHoldRejectLoadtestTest : Adr018GroupHoldRejectScenarios("one_shot", "short")
