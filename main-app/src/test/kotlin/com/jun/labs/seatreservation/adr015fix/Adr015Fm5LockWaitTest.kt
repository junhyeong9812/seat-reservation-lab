package com.jun.labs.seatreservation.adr015fix

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015Base
import com.jun.labs.seatreservation.adr015.Adr015Gate
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.Adr015Taps
import com.jun.labs.seatreservation.adr015.L_E_COMMIT
import com.jun.labs.seatreservation.adr015.M_EXTEND
import com.jun.labs.seatreservation.adr015.M_EXTENSION_LOST
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_MIN_REMAINING
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.P_WINDOW
import com.jun.labs.seatreservation.adr015.TICK_METRIC
import com.jun.labs.seatreservation.adr015.WINDOW
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR-015 구현 리뷰 R15-7(fix verification — blind 파일 무수정 · 래치 · 탭 도우미는 호출만): FM5 '대기' 변형.
 * blind FM5(Adr015RaceScenarios)는 연장 E1 **앞**에서 멈춰 배치가 후보를 읽은 뒤 연장을 커밋하므로 B3은 기다리지 않고 새 스냅숏을 본다.
 * 여기서는 E1을 **실행한 뒤 커밋 앞**(L_E_COMMIT)에서 멈춘다 → A′ 배치 B3 DELETE가 그 홀드 행 잠금을 기다리는 것을 pg_stat_activity(wait_event_type=Lock ·
 * pg_blocking_pids = 연장 백엔드)로 관측 → 연장 커밋을 풂 → B3이 EPQ로 연장된 expires_at을 다시 보고 지우지 않는다(§3.4 '연장 커밋 전이면 E1의 행 잠금을 B3이 기다린 뒤 같은 재확인').
 * 컨텍스트 키는 Adr015ExtendRaceTest와 같다(같은 애너테이션 — 캐시 재사용).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_EXTEND, P_WINDOW, P_MIN_REMAINING],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015Fm5LockWaitTest : Adr015Base() {

    @Test
    fun `FM5 대기 변형 — B3이 연장 커밋 전 행 잠금을 기다린 뒤 EPQ로 연장된 expires_at을 보고 지우지 않는다`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val lost0 = counter(M_EXTENSION_LOST) ?: 0.0
        val ok0 = counter(TICK_METRIC, "outcome", "ok") ?: 0.0

        // ① 연장 E1 실행 · 커밋 앞에서 멈춤(홀드 행 잠금을 쥔 채)
        val lc = Adr015Taps.arm(Adr015Gate("L-E-COMMIT"), L_E_COMMIT)
        val pf = paymentStartAsync(hold, x)
        reachedOrFail(lc, what = "L-E-COMMIT(payment-start E1 뒤 · 커밋 앞)")
        val extenderPid = jdbcTemplate.queryForList(
            "SELECT pid FROM pg_stat_activity WHERE state = 'idle in transaction' AND (lower(query) LIKE 'update seat_hold%' OR lower(query) LIKE '%from payment_intent where hold_id%')", Int::class.java,
        ).singleOrNull()
        assertNotNull(extenderPid, "연장 백엔드(E1 실행 · 커밋 전 — idle in transaction)가 하나여야 한다")

        // ② 원래 te 뒤 배치(A′) — 후보 읽기는 커밋된 옛 expires_at(te)을 보고, B3 DELETE는 연장 백엔드의 행 잠금에서 기다린다
        clock.set(NOW.plus(Duration.ofMinutes(5)).plusSeconds(30))
        val bf = batchAsync()
        assertTrue(
            awaitLockWait("delete from seat_hold", blockingPid = extenderPid),
            "A′ B3(delete from seat_hold)이 연장 백엔드(pid=$extenderPid)의 잠금을 기다려야 한다(Lock 대기 관측 실패) · ${dbState()}",
        )
        assertFalse(bf.isDone, "B3이 기다리는 동안 배치 틱은 끝나지 않는다")

        // ③ 연장 커밋을 풂 → B3이 EPQ로 연장된 값(:now′ + window = NOW + 6분 > 배치 :now)을 다시 보고 지우지 않는다
        lc.release()
        val pr = pf.done()
        assertEquals(200, pr.status, "연장 200: $pr")
        assertEquals(NOW.plus(Duration.ofMinutes(4)).plus(WINDOW), Instant.parse(pr.json!!.get("expiresAt").asText()), "연장 = :now′ + window")
        val br = bf.join()
        assertTrue(br.isSuccess, "배치 틱 커밋: ${br.exceptionOrNull()}")
        assertEquals(ok0 + 1, requireCounter(TICK_METRIC, "outcome", "ok"), "tick{ok} +1(틱 롤백 없음)")
        assertTrue(holdExists(hold), "연장된 홀드는 지워지지 않는다(N10 — EPQ 갈래): ${dbState()}")
        assertEquals("HELD", seatStatusOf(seat))
        assertEquals(lost0, counter(M_EXTENSION_LOST) ?: 0.0, "$M_EXTENSION_LOST 0")
        assertEquals(1, cntOf(x), "지우지 않았으니 감소도 없다(cnt = 홀드 1)")

        val cr = confirmHttp(hold, x, pr.json!!.get("paymentUid").asText()) // ADR-017: 발급 uid로만 확정
        assertEquals(200, cr.status, "연장 뒤 확정(원래 te 뒤 · 연장 만료 전) 200: $cr")
        assertVerdictClean("FM5 대기 변형")
    }
}
