package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.service.CompensationMode
import com.jun.labs.seatreservation.service.CompensationProperties
import com.jun.labs.seatreservation.service.PaymentProperties
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.payment.CompensationScheduling
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 리뷰 반영 확인 — R(refund): 리스 회수는 시도 1회 · 결과 모름(다음 시도 전 조회) */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_READ_TIMEOUT, P17_CONNECT_TIMEOUT, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr017ReviewFixRefundTest : Adr017PgBase() {

    @Test
    fun `리스 회수 = 시도 1회 · 결과 모름 → 같은 주기에 조회 뒤 close · 환불 1`() {
        val p = started(1, x)
        clock.set(p.expiresAt.plusSeconds(1))
        assertEquals(409, confirmHttp(p.holdId, x, p.uid).status)
        val t = closeTask(p.uid)
        forceLeaseExpired(t.id) // 집은 채 죽은 워커(PG를 불렀는지 모른다)

        worker.runOnce() // ① 회수(attempts +1 · UNKNOWN) → ② 다시 집음 → 조회(CAPTURED — 아직 안 닫힘) → close → DONE(attempts +1)
        val done = closeTask(p.uid)
        assertEquals("DONE", done.status, "$done")
        assertEquals(2, done.attempts, "회수 1 + 처리 1: $done")
        assertEquals("REFUNDED", Adr017Pg.get(p.uid).state)
        assertEquals(1, Adr017Pg.get(p.uid).refunds, "이중 환불 0")
    }

    @Test
    fun `리스 회수로 시도 상한에 닿으면 DEAD(PG 호출 중 매번 죽는 작업이 끝없이 돌지 않는다)`() {
        val p = started(1, x)
        clock.set(p.expiresAt.plusSeconds(1))
        assertEquals(409, confirmHttp(p.holdId, x, p.uid).status)
        val t = closeTask(p.uid)
        jdbcTemplate.update("UPDATE compensation_task SET attempts = ? WHERE id = ?", MAX_ATTEMPTS - 1, t.id)
        forceLeaseExpired(t.id)

        worker.runOnce()
        val dead = closeTask(p.uid)
        assertEquals("DEAD", dead.status, "$dead")
        assertEquals(MAX_ATTEMPTS, dead.attempts)
        assertEquals("CAPTURED", Adr017Pg.get(p.uid).state, "DEAD 작업은 PG를 부르지 않았다")
    }
}

/** 리뷰 반영 확인 — 안전망 순회: 맨 앞 후보가 잠겨(또는 계속 실패해) 있어도 배치 1에서 뒤 후보가 다음 주기에 닫힌다 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE,
        P17_MODE_REFUND, P17_SCHEDULER_OFF, P17_READ_TIMEOUT, P17_CONNECT_TIMEOUT, P17_MAX_ATTEMPTS, P17_BACKOFF_INITIAL, P17_BACKOFF_MAX, P17_LEASE,
        "seat.compensation.sweep.batch=1",
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr017ReviewFixSweepTest : Adr017PgBase() {

    @Test
    fun `맨 앞 후보가 잠겨 있어도 다음 주기에 뒤 후보를 닫는다(후보 기아 없음)`() {
        val a = started(1, x)
        val b = started(2, y)
        clock.set(maxOf(a.expiresAt, b.expiresAt).plusSeconds(1))
        val (first, second) = listOf(a.uid, b.uid).sorted()
        dataSource.connection.use { c ->
            c.autoCommit = false
            c.prepareStatement("SELECT 1 FROM payment_intent WHERE uid = ? FOR UPDATE").use { it.setString(1, first); it.executeQuery() }
            sweep.runOnce() // 순회 1 — 맨 앞(first)은 잠겨 건너뜀
            sweep.runOnce() // 순회 2 — 그 뒤(second)
            assertEquals(1, tasks(second).size, "뒤 후보가 닫혔다(CLOSE 작업)")
            assertEquals(0, tasks(first).size, "잠긴 후보는 아직")
            c.rollback()
        }
        sweep.runOnce()
        sweep.runOnce()
        assertEquals(1, tasks(first).size, "잠금이 풀린 뒤 앞 후보도 닫힌다")
    }
}

/** 리뷰 반영 확인 — D(auth-capture): capture의 예상 밖 4xx(미등록 404)는 종결이 아니라 재시도(상한에서 DEAD로 드러난다) */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, DS_SCHEDULER_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        P_LOC_BASE, DS_MODE_AUTH_CAPTURE,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr017ReviewFixCaptureTest : Adr017DsBase() {

    @Test
    fun `CAPTURE 404 UNKNOWN_PAYMENT → PENDING 재시도(DONE 아님)`() {
        val p = startedHold(createSeat(1).id!!, x)
        pgAuthorizeOk(p.uid)
        assertEquals(200, confirmHttp(p.holdId, p.user, p.uid).status)
        pgPost("/admin/reset") // PG 원장에서 uid가 사라진 모양(원장 불일치)

        workerOnce()
        val t = tasksOfType(p.uid, "CAPTURE").single()
        assertEquals("PENDING", t.status, "예상 밖 4xx는 종결이 아니다: $t")
        assertTrue(t.lastResult!!.startsWith("REJECTED_"), "$t")
    }

    @Test
    fun `CAPTURE 409 REGISTERED(승인 없이 확정) → 부록 B '등 409' 종결-실패 DONE FAILED_REGISTERED(돈 없는 좌석)`() {
        val p = startedHold(createSeat(1).id!!, x)
        assertEquals(200, confirmHttp(p.holdId, p.user, p.uid).status)

        workerOnce()
        val t = tasksOfType(p.uid, "CAPTURE").single()
        assertEquals("DONE", t.status, "$t")
        assertEquals("FAILED_REGISTERED", t.lastResult)
    }
}

/** 리뷰 반영 확인 — 기동 검사(보상 손잡이 조합) */
class Adr017ReviewFixStartupCheckTest {
    private val hold = SeatHoldProperties(ttl = Duration.ofMinutes(5), maxPerUser = 4, expiryInterval = Duration.ofSeconds(10))
    private val pg = PaymentProperties(pg = PaymentProperties.Pg(baseUrl = "http://pg:8104"))

    @Test
    fun `리스가 PG 시간 상한(2 × (connect + read)) 이하면 기동 거부`() {
        val short = CompensationProperties(mode = CompensationMode.REFUND, worker = CompensationProperties.Worker(lease = Duration.ofSeconds(8)))
        val e = assertThrows<IllegalStateException> { CompensationScheduling.checkConfig(short, pg, SeatConfirmProperties(), hold) }
        assertTrue(e.message!!.contains("lease"), e.message)
        assertDoesNotThrow { CompensationScheduling.checkConfig(CompensationProperties(mode = CompensationMode.REFUND), pg, SeatConfirmProperties(), hold) }
    }

    @Test
    fun `substitute는 판매 마감 판정을 끈 구성에서 기동 거부`() {
        val off = hold.copy(salesClose = SeatHoldProperties.SalesClose(enabled = false))
        val e = assertThrows<IllegalStateException> {
            CompensationScheduling.checkConfig(CompensationProperties(mode = CompensationMode.SUBSTITUTE), pg, SeatConfirmProperties(), off)
        }
        assertTrue(e.message!!.contains("sales-close"), e.message)
    }
}
