package com.jun.labs.seatreservation.adr016

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.M_CURRENT
import com.jun.labs.seatreservation.adr015.M_OPTIMISTIC
import com.jun.labs.seatreservation.adr015.M_PESSIMISTIC
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_LOC_OPTIMISTIC
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.adr015.TTL
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
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * N16-5(명세 §1 · 5 · §2 · §5) — 확정 엔드포인트 `POST /api/holds/{holdId}/confirm`만의 응답 지표(모든 모드):
 *   seat.confirm.rejected{code} = 도메인 거부 응답(404 · 403 · 409 — {code, message} 본문) 수 · code별
 *   seat.confirm.failed{status} = 그 밖의 비2xx(400 · 415 · 5xx …) 수 · status별 — 둘은 겹치지 않는다 · 2xx는 어느 쪽에도 없다
 * 선점 · payment-start 거부는 확정 지표에 들어가지 않는다(엔드포인트 한정).
 */
abstract class Adr016ConfirmMetricsScenarios(val mode: String) : Adr016Base() {

    @Test
    fun `N16-5 — 성공 · 404 · 403 · 409 · 400 · 415가 섞인 확정 묶음에서 rejected{code} · failed{status} 증분 = 응답 수`() {
        val s1 = createSeat(1).id!!
        val s2 = createSeat(2).id!!
        val s3 = createSeat(3).id!!
        val hOk = holdOk(s1, x)
        val hBody = holdOk(s2, x) // 400 · 415 대상(유효한 내 홀드 — 본문 · 형식 결함만으로 실패)
        val hOther = holdOk(s3, y)
        val sExp = createSeat(4, SeatStatus.HELD)
        val hExp = insertHold(sExp, x, NOW.minus(TTL).minusSeconds(10), NOW.minusSeconds(10)) // 이미 만료(배치 전) → 409 HOLD_EXPIRED
        val sAvail = createSeat(5)
        val hNotHeld = insertHold(sAvail, y, NOW, NOW.plus(TTL)) // 홀드 유효 · 좌석 AVAILABLE → 409 SEAT_NOT_HELD
        clock.set(NOW.plus(Duration.ofMinutes(1)))

        val rej0 = DOMAIN_CODES.associateWith { c(M_CONFIRM_REJECTED, "code", it) }
        val fail0 = listOf("400", "415").associateWith { c(M_CONFIRM_FAILED, "status", it) }
        val rejTotal0 = total(M_CONFIRM_REJECTED)
        val failTotal0 = total(M_CONFIRM_FAILED)

        // 확정 엔드포인트 밖의 거부 — 확정 지표에 들어가면 안 된다
        val holdReject = holdHttp(s1, y) // 남이 쥔 좌석 선점 → 409(선점 경로 코드)
        assertTrue(holdReject.status == 409, "[$mode] 준비 — 선점 거부 409: $holdReject")
        val psReject = paymentStart(NO_SUCH_HOLD, x) // ① · ④ = 404 HOLD_NOT_FOUND · 그 밖 = 핸들러 없음 404
        assertEquals(404, psReject.status, "[$mode] 준비 — payment-start 404: $psReject")

        val rs = mutableListOf<Pair<String, Resp>>()
        rs += "200" to confirmHttp(hOk, x)
        rs += "404" to confirmHttp(NO_SUCH_HOLD, x)
        rs += "404" to confirmHttp(NO_SUCH_HOLD + 1, y)
        rs += "403" to confirmHttp(hOther, x)
        rs += "409E" to confirmHttp(hExp, x)
        rs += "409S" to confirmHttp(hNotHeld, y)
        rs += "400" to rawPost("/api/holds/$hBody/confirm", x, "application/json", """{"paymentUid":""") // 본문 결함(JSON 깨짐)
        rs += "400" to rawPost("/api/holds/$hBody/confirm", null, "application/json", """{"paymentUid":"pay-h"}""") // X-User-Id 없음
        rs += "415" to rawPost("/api/holds/$hBody/confirm", x, "text/plain", "paymentUid=pay-x") // 미디어 형식
        rs += "200" to confirmHttp(hBody, x, "pay-body") // 결함 요청들이 상태를 바꾸지 않았다 — 그 뒤 정상 확정은 성공

        // 응답 전제(그린 위장 점검 — 묶음이 의도한 모양이 아니면 지표 비교는 뜻이 없다)
        val expectStatus = mapOf("200" to 200, "404" to 404, "403" to 403, "409E" to 409, "409S" to 409, "400" to 400, "415" to 415)
        rs.forEach { (k, r) -> assertEquals(expectStatus.getValue(k), r.status, "[$mode] 묶음 전제 $k: $r") }
        assertEquals("HOLD_EXPIRED", rs.first { it.first == "409E" }.second.code)
        assertEquals("SEAT_NOT_HELD", rs.first { it.first == "409S" }.second.code)
        assertEquals("HOLD_NOT_OWNED", rs.first { it.first == "403" }.second.code)

        // 응답에서 센 값(code · status별)
        val domain = rs.filter { it.second.status in setOf(403, 404, 409) }.groupingBy { it.second.code!! }.eachCount()
        val other = rs.filter { it.second.status >= 300 && it.second.status !in setOf(403, 404, 409) }.groupingBy { it.second.status.toString() }.eachCount()
        assertEquals(mapOf("HOLD_NOT_FOUND" to 2, "HOLD_NOT_OWNED" to 1, "HOLD_EXPIRED" to 1, "SEAT_NOT_HELD" to 1), domain, "[$mode] 묶음 전제(도메인 거부)")
        assertEquals(mapOf("400" to 2, "415" to 1), other, "[$mode] 묶음 전제(그 밖)")

        domain.forEach { (code, n) ->
            assertEquals(rej0.getValue(code) + n, requireCounter(M_CONFIRM_REJECTED, "code", code), "[$mode] $M_CONFIRM_REJECTED{code=$code} 증분 = 응답 $n 건")
        }
        other.forEach { (status, n) ->
            assertEquals(fail0.getValue(status) + n, requireCounter(M_CONFIRM_FAILED, "status", status), "[$mode] $M_CONFIRM_FAILED{status=$status} 증분 = 응답 $n 건")
        }
        assertEquals(rejTotal0 + domain.values.sum(), total(M_CONFIRM_REJECTED),
            "[$mode] rejected 합 = 확정 도메인 거부 응답 수 — 성공 · 400 · 415 · 선점 거부 · payment-start 거부는 들어가지 않는다")
        assertEquals(failTotal0 + other.values.sum(), total(M_CONFIRM_FAILED),
            "[$mode] failed 합 = 그 밖의 비2xx 응답 수 — 404 · 403 · 409 · 성공은 들어가지 않는다(겹치지 않음)")
        listOf("200", "403", "404", "409").forEach { st ->
            assertEquals(0.0, c(M_CONFIRM_FAILED, "status", st), "[$mode] failed{status=$st}는 없어야 한다(도메인 거부 · 성공은 failed가 아니다)")
        }
    }

    companion object {
        val DOMAIN_CODES = listOf("HOLD_NOT_FOUND", "HOLD_NOT_OWNED", "HOLD_EXPIRED", "SEAT_NOT_HELD")
    }
}

/*
 * N16-7(명세 §2 · §5) — current · ② · ③의 외부 계약은 그대로: payment-start 404(핸들러 없음 — 유효한 내 홀드여도) · 선점 201 본문 키 3개.
 * (Adr015RaceScenarios 'conditional-extend가 아니면 404'에서 ①이 빠진 몫 — 명세 §1 · 7)
 */
abstract class Adr016LegacyModeScenarios(mode: String) : Adr016ConfirmMetricsScenarios(mode) {

    @Test
    fun `N16-7 — payment-start는 이 모드에 없다(404 · HOLD_NOT_FOUND 아님) · 행 무변경`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val before = rowsSnapshot()
        val r = paymentStart(hold, x)
        assertEquals(404, r.status, "[$mode] payment-start는 이 모드에 없다: $r")
        assertTrue(r.code != "HOLD_NOT_FOUND", "[$mode] 404는 핸들러 없음이어야 한다(유효한 홀드에 HOLD_NOT_FOUND가 아님): $r")
        assertEquals(before, rowsSnapshot(), "[$mode] 행 무변경")
    }

    @Test
    fun `N16-7 — 선점 201 본문은 holdId · seatId · expiresAt 3개 그대로(payBy · payWithinSeconds 없음)`() {
        val seat = createSeat(1).id!!
        val r = holdHttp(seat, x)
        assertEquals(201, r.status, "[$mode] $r")
        assertEquals(HOLD_KEYS_BASE, r.keys(), "[$mode] 선점 201 본문 키: $r")
        assertEquals(NOW.plus(TTL), r.instant("expiresAt"), "[$mode] expiresAt = :now + TTL")
    }
}

// 컨텍스트 키는 Adr015 같은 모드의 race 테스트와 같다(같은 애너테이션 — 캐시 재사용)

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CURRENT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr016CurrentModeContractTest : Adr016LegacyModeScenarios("current")

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_PESSIMISTIC],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr016PessimisticModeContractTest : Adr016LegacyModeScenarios("pessimistic")

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_OPTIMISTIC, M_OPTIMISTIC],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr016OptimisticModeContractTest : Adr016LegacyModeScenarios("optimistic")
