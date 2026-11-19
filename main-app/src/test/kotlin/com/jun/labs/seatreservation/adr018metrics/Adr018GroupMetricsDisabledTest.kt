package com.jun.labs.seatreservation.adr018metrics

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_INITIAL
import com.jun.labs.seatreservation.adr017.P17_BACKOFF_MAX
import com.jun.labs.seatreservation.adr017.P17_LEASE
import com.jun.labs.seatreservation.adr017.P17_MAX_ATTEMPTS
import com.jun.labs.seatreservation.adr017.P17_MODE_REFUND
import com.jun.labs.seatreservation.adr017.P17_SCHEDULER_OFF
import com.jun.labs.seatreservation.adr018.HOLD_NOT_FOUND
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

/*
 * test-contract §1.1 '핸들러를 찾지 못한 요청(묶음 API를 꺼서 경로가 없는 404 등)은 세지 않는다'.
 * 묶음 기본 끔(enabled=false) — 묶음 API 4개는 핸들러 없음 404 → seat.group.request 무변화.
 * 1석 해제 DELETE /api/holds/{id}는 묶음 설정과 무관하게 열려 있어(1단계 test-contract §1) op=release_hold로 센다 — 같은 404라도 핸들러가 낸 HOLD_NOT_FOUND는 센다.
 * (해석: '모든 프로필'의 op 목록에 release_hold가 있고 제외는 핸들러 없음뿐이라 끈 상태에서도 센다고 읽었다 — 보고에 적음.)
 * 컨텍스트 키 = Adr018DisabledTest와 같다(캐시 공유).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr018GroupMetricsDisabledTest : Adr018MetricsBase() {

    @Test
    fun `1·1 묶음 끔 — 묶음 API 4개의 핸들러 없음 404는 세지 않는다 · 1석 해제(204 · HOLD_NOT_FOUND)는 release_hold로 1씩`() {
        val (a, b) = seats(2)
        val h = holdOk(b, x)
        val q0 = snap(M_GROUP_REQUEST, "op", "outcome")
        val noHandler = listOf(
            gHold(listOf(a), y),
            gRelease(1, x),
            gStart(1, x),
            gConfirm(1, x, "00000000-0000-0000-0000-000000000000"),
        )
        noHandler.forEach { assertEquals(404, it.status, "준비 — 묶음 끔이면 404: $it") }

        // 핸들러가 있는 요청 하나를 뒤에 보내 그 사이 늦게 오른 셈까지 함께 잡는다(차이 합 = 1이어야 한다)
        val r = hRelease(h, x)
        assertEquals(204, r.status, "준비 — 1석 해제 204: $r")
        val d = awaitDiff(M_GROUP_REQUEST, arrayOf("op", "outcome"), q0, 1.0)
        assertEquals(mapOf<TagKey, Double>(listOf<String?>(OP_RELEASE_HOLD, "ok") to 1.0), d,
            "핸들러 없음 404 4건은 0 · 1석 해제 204만 {release_hold, ok} 1")

        counted(OP_RELEASE_HOLD, "다시 해제(핸들러가 낸 404)", HOLD_NOT_FOUND) { hRelease(h, x) }.also {
            assertEquals(404, it.status, "다시 해제 404: $it")
        }
    }
}
