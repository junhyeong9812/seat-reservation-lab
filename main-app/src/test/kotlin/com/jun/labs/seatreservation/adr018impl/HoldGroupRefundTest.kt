package com.jun.labs.seatreservation.adr018impl

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr017.Adr017Pg
import com.jun.labs.seatreservation.service.impl.payment.CompensationSweep
import com.jun.labs.seatreservation.service.impl.payment.CompensationWorker
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_FLYWAY_BASE
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import kotlin.test.assertEquals

/** 묶음 + 보상 refund + 흉내 PG(같은 JVM — Adr017Pg): 등록 금액 = N × 금액 · 거부 = CLOSE 1건 · 결제 시작 뒤 방치 = 안전망 CLOSE 1건 · 1석 금액 그대로 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        G_SCHED_OFF, G_COMP_SCHED_OFF, G_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE,
        G_ENABLED, G_SPLIT, "seat.compensation.mode=refund",
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class HoldGroupRefundTest : Adr018ImplBase() {
    @Autowired lateinit var worker: CompensationWorker
    @Autowired lateinit var sweep: CompensationSweep

    private val u = 7L

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun pg(r: DynamicPropertyRegistry) {
            r.add("seat.payment.pg.base-url") { Adr017Pg.baseUrl }
        }
    }

    @BeforeEach
    fun pgClean() {
        Adr017Pg.appClock.set(clock)
        Adr017Pg.clearFaults()
        Adr017Pg.reset()
    }

    @Test
    fun `등록 금액 = 2 × 10000 · 재호출 같은 금액 · 1석은 10000`() {
        val (a, b, c) = seatIds(3)
        val group = holdGroupOk(u, a, b)
        val uid = groupPaymentStart(u, group).also { assertEquals(200, it.status, "$it") }.json!!.get("paymentUid").asText()
        assertEquals(20_000L, Adr017Pg.get(uid).payment!!.get("amount").asLong())
        assertEquals(200, groupPaymentStart(u, group).status, "재호출 — 같은 금액 등록이라 통과")
        val hold = holdOneOk(8L, c)
        val one = holdPaymentStart(8L, hold).json!!.get("paymentUid").asText()
        assertEquals(10_000L, Adr017Pg.get(one).payment!!.get("amount").asLong())
    }

    @Test
    fun `결제 뒤 확정 거부 — CLOSE 작업 1건 · 응답 compensation · 워커가 PG 환불(금액 N석 합)`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        val uid = groupPaymentStart(u, group).json!!.get("paymentUid").asText()
        assertEquals("CAPTURED", Adr017Pg.pay(uid).state)
        clock.advance(Duration.ofSeconds(612))
        val r = groupConfirm(u, group, uid)
        assertEquals(409 to "HOLD_EXPIRED", r.status to r.code, "$r")
        assertEquals("CLOSE", r.json!!.get("compensation").get("type").asText())
        assertEquals(1L, q("SELECT count(*) FROM compensation_task WHERE uid = ?", uid))
        worker.runOnce()
        Adr017Pg.awaitState(uid, "REFUNDED")
        assertEquals(1, Adr017Pg.get(uid).refunds)
        assertEquals(20_000L, Adr017Pg.get(uid).payment!!.get("amount").asLong())
    }

    private fun registerOps(): Long = Adr017Pg.ledger().json?.get("ops")?.get("register")?.asLong() ?: 0L

    @Test
    fun `부분 묶음(홀드 1행을 지움) — 첫 결제 시작 409 HOLD_EXPIRED · 결제 의도 0 · PG 등록 0 · 연장도 롤백`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        jdbcTemplate.update("DELETE FROM seat_hold WHERE id = (SELECT max(id) FROM seat_hold WHERE group_id = ?)", group)
        val r = groupPaymentStart(u, group)
        assertEquals(409 to "HOLD_EXPIRED", r.status to r.code, "$r")
        assertEquals(0L, q("SELECT count(*) FROM payment_intent"))
        assertEquals(0L, registerOps(), "PG 등록 호출 0")
        assertEquals(0L, q("SELECT count(*) FROM seat_hold WHERE group_id = ? AND extended_at IS NOT NULL", group), "연장 롤백")
    }

    @Test
    fun `부분 묶음 — TTL이 결제 창보다 길어 연장이 필요 없는 진단 갈래도 409(N석 결제 의도를 만들지 않는다)`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        // 남은 시간을 결제 창(W) 이상으로 — 연장 문장은 0행이고 진단이 '연장 필요 없음' 갈래로 간다
        jdbcTemplate.update("UPDATE seat_hold SET expires_at = ? WHERE group_id = ?", java.sql.Timestamp.from(NOW.plusSeconds(3_600)), group)
        jdbcTemplate.update("DELETE FROM seat_hold WHERE id = (SELECT max(id) FROM seat_hold WHERE group_id = ?)", group)
        val r = groupPaymentStart(u, group)
        assertEquals(409 to "HOLD_EXPIRED", r.status to r.code, "$r")
        assertEquals(0L, q("SELECT count(*) FROM payment_intent"))
        assertEquals(0L, registerOps())
    }

    @Test
    fun `부분 묶음 — 재호출(결제 의도 있음)도 409 · 결제 의도 그대로 · PG 재등록 없음`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        assertEquals(200, groupPaymentStart(u, group).status)
        assertEquals(1L, registerOps())
        jdbcTemplate.update("DELETE FROM seat_hold WHERE id = (SELECT max(id) FROM seat_hold WHERE group_id = ?)", group)
        val r = groupPaymentStart(u, group)
        assertEquals(409 to "HOLD_EXPIRED", r.status to r.code, "$r")
        assertEquals(1L, q("SELECT count(*) FROM payment_intent"))
        assertEquals("OPEN", jdbcTemplate.queryForObject("SELECT state FROM payment_intent WHERE group_id = ?", String::class.java, group))
        assertEquals(1L, registerOps(), "재호출이 PG에 다시 등록하지 않는다")
    }

    @Test
    fun `결제 시작 뒤 방치 — 만료 배치가 묶음을 지우면 안전망이 CLOSE 1건`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        val uid = groupPaymentStart(u, group).json!!.get("paymentUid").asText()
        clock.advance(Duration.ofSeconds(612))
        assertEquals(2, expire.expire())
        assertEquals(1, sweep.runOnce())
        assertEquals(1L, q("SELECT count(*) FROM compensation_task WHERE uid = ? AND type = 'CLOSE'", uid))
        assertEquals("CLOSING", jdbcTemplate.queryForObject("SELECT state FROM payment_intent WHERE uid = ?", String::class.java, uid))
    }
}
