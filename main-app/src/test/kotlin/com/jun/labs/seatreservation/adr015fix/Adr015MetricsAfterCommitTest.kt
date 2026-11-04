package com.jun.labs.seatreservation.adr015fix

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015Base
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.M_EXTEND
import com.jun.labs.seatreservation.adr015.M_EXTEND_RESULT
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_MIN_REMAINING
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.P_WINDOW
import com.jun.labs.seatreservation.adr015.TICK_METRIC
import com.jun.labs.seatreservation.adr015.WINDOW
import com.jun.labs.seatreservation.service.impl.PaymentStartService
import com.jun.labs.seatreservation.service.impl.expiry.LazyHoldProcess
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-015 구현 리뷰 R15-16(fix verification — blind 파일 무수정 · 도우미는 호출만): A′ 배치 지표(`seat.hold.expiry.deleted{path=sweep}` ·
 * `seat.hold.expiry.revert{path=sweep}` · extension_lost)와 payment-start 200 갈래 지표(`seat.confirm.extend{extended|already}`)는 커밋 뒤에만 센다.
 * 롤백은 두 가지로 만든다 — ① 커밋 단계 실패 주입(DEFERRABLE INITIALLY DEFERRED 제약 트리거가 COMMIT에서 예외 — 몸통의 모든 문장 · 옛 코드의 지표 증가가
 * 끝난 뒤 커밋만 실패) ② payment-start는 바깥 트랜잭션에 합류시킨 뒤 setRollbackOnly. 대조: 트리거를 지운 뒤 같은 동작은 커밋되고 지표가 오른다.
 * 컨텍스트 키는 Adr015Fm5LockWaitTest와 같다(같은 애너테이션 — 캐시 재사용).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_EXTEND, P_WINDOW, P_MIN_REMAINING],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015MetricsAfterCommitTest : Adr015Base() {

    @Autowired lateinit var paymentStartService: PaymentStartService
    @Autowired lateinit var txManager: PlatformTransactionManager

    private fun c(name: String, vararg tags: String) = counter(name, *tags) ?: 0.0

    private fun sweepCounts() = mapOf(
        "deleted" to c(LazyHoldProcess.DELETED_METRIC, "path", "sweep"),
        "revert" to c(LazyHoldProcess.REVERT_METRIC, "path", "sweep"),
    )

    /** seat_hold의 [event](DELETE · UPDATE) 행이 있으면 COMMIT에서 실패하는 지연 제약 트리거를 건 채 [block]을 돌리고 반드시 지운다 */
    private fun <T> withCommitFailure(event: String, block: () -> T): T {
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION r15_16_fail() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'R15-16 commit failure injection'; END \$\$")
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER r15_16_fail_commit AFTER $event ON seat_hold DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION r15_16_fail()")
        try {
            return block()
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS r15_16_fail_commit ON seat_hold")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS r15_16_fail()")
        }
    }

    @Test
    fun `R15-16 A′ — 틱이 B3 뒤 커밋에서 실패해 롤백되면 deleted · revert{sweep}은 그대로 · 성공 틱이면 오른다`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(5)).plusSeconds(30)) // TTL 5분 뒤 — 만료
        val s0 = sweepCounts()
        val failed0 = c(TICK_METRIC, "outcome", "failed")

        // ① 커밋 실패 — B3 DELETE · B4 감소 · B5 좌석 되돌림까지 몸통은 다 돌고 COMMIT만 실패
        val r = withCommitFailure("DELETE") { runCatching { expire.expire() } }
        assertTrue(r.isFailure, "커밋 실패 주입 틱은 예외로 끝나야 한다: $r")
        assertEquals(failed0 + 1, c(TICK_METRIC, "outcome", "failed"), "tick{failed} +1")
        assertTrue(holdExists(hold), "롤백 — 홀드는 남는다: ${dbState()}")
        assertEquals("HELD", seatStatusOf(seat), "롤백 — 좌석 HELD")
        assertEquals(s0, sweepCounts(), "롤백된 틱의 deleted · revert{sweep}은 세지 않는다(R15-16)")

        // ② 대조 — 트리거 없이 같은 틱이 커밋되면 오른다
        assertEquals(1, expire.expire(), "성공 틱이 홀드 1개를 지운다")
        assertEquals(s0.getValue("deleted") + 1, c(LazyHoldProcess.DELETED_METRIC, "path", "sweep", "extended", "false"), "성공 틱 deleted{sweep,false} +1")
        assertEquals(s0.getValue("revert") + 1, c(LazyHoldProcess.REVERT_METRIC, "path", "sweep", "result", "reverted"), "성공 틱 revert{sweep,reverted} +1")
        assertEquals("AVAILABLE", seatStatusOf(seat))
        assertVerdictClean("R15-16 A′")
    }

    @Test
    fun `R15-16 payment-start — 연장이 커밋되지 않으면(바깥 롤백 · 커밋 실패) extend{extended · already}는 그대로 · 커밋되면 오른다`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4))) // 남은 1분 < window 2분 · > 하한 5초 → 연장 대상
        val te = expiresAtOf(hold)
        fun ext() = c(M_EXTEND_RESULT, "result", "extended")
        fun already() = c(M_EXTEND_RESULT, "result", "already")
        val e0 = ext()
        val tx = TransactionTemplate(txManager)

        // ① 바깥 트랜잭션에 합류(REQUIRED) → 롤백
        tx.executeWithoutResult { st -> paymentStartService.paymentStart(hold, x); st.setRollbackOnly() }
        assertEquals(e0, ext(), "바깥 롤백 — extend{extended}는 세지 않는다")
        assertEquals(te, expiresAtOf(hold), "바깥 롤백 — expires_at 그대로")

        // ② 커밋 단계 실패(E1 UPDATE 뒤 COMMIT에서 예외) — HTTP 응답은 200이 아니다
        val failed = withCommitFailure("UPDATE") { paymentStart(hold, x) }
        assertNotEquals(200, failed.status, "커밋 실패면 200이 아니다: $failed")
        assertEquals(e0, ext(), "커밋 실패 — extend{extended}는 세지 않는다")
        assertEquals(te, expiresAtOf(hold), "커밋 실패 — expires_at 그대로")

        // ③ 대조 — 커밋되면 +1
        val ok = paymentStart(hold, x)
        assertEquals(200, ok.status, "연장 200: $ok")
        assertEquals(NOW.plus(Duration.ofMinutes(4)).plus(WINDOW), expiresAtOf(hold), "연장 = :now + window")
        assertEquals(e0 + 1, ext(), "커밋된 연장 extend{extended} +1")

        // ④ 진단 갈래 already(200)도 같은 규칙 — 바깥 롤백이면 그대로 · 커밋되면 +1
        val a0 = already()
        tx.executeWithoutResult { st -> paymentStartService.paymentStart(hold, x); st.setRollbackOnly() }
        assertEquals(a0, already(), "바깥 롤백 — extend{already}는 세지 않는다")
        assertEquals(200, paymentStart(hold, x).status)
        assertEquals(a0 + 1, already(), "커밋된 already +1")
        assertEquals(e0 + 1, ext(), "already는 extended를 올리지 않는다")
    }
}
