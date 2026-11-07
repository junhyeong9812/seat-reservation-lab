package com.jun.labs.seatreservation.adr016fix

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015Base
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.M_CONDITIONAL
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr016.Adr016Base
import com.jun.labs.seatreservation.service.impl.PaymentStartService
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-016 2단계 코드 리뷰 루프 1 fix verification(blind 파일 무수정 — 도우미는 호출만):
 * - RC16-2: ① `seat.confirm.pay_start{result=ok}`는 커밋 뒤에만 센다(명세 §1-3) — 바깥 트랜잭션 롤백이면 그대로 · 커밋되면 +1.
 * - RC16-3: 확정 커밋 실패(5xx)는 `seat.confirm.failed{status=500}`로 세고 `rejected`는 그대로(명세 §1-5 · N16-5).
 * - OQ-2 고정: 확정 경로 POST는 인자 변환이 실패해도 핸들러가 선택된 뒤라 `failed{status=400}`으로 센다(매핑에 consumes · params · headers 제약 없음).
 * - RC2-1(루프 2): 도메인 거부 표지가 있어도 오류 본문을 못 쓰면(Accept: text/html) 실제 응답 상태로 센다 — rejected와 failed 중 정확히 하나 · 상태와 맞는 쪽.
 * 컨텍스트 키는 Adr016ReadOnlyProbeTest와 같다(캐시 재사용).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CONDITIONAL],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr016MetricsFixTest : Adr016Base() {

    @Autowired lateinit var paymentStartService: PaymentStartService
    @Autowired lateinit var txManager: PlatformTransactionManager
    @Autowired lateinit var meterRegistry: MeterRegistry

    private fun cnt(name: String, vararg tags: String) = counter(name, *tags) ?: 0.0
    private fun tot(name: String) = meterRegistry.find(name).counters().sumOf { it.count() }

    @Test
    fun `RC16-2 ① pay_start ok — 바깥 롤백이면 그대로 · 커밋되면 +1`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val ok0 = cnt("seat.confirm.pay_start", "result", "ok")
        TransactionTemplate(txManager).executeWithoutResult { st -> paymentStartService.check(hold, x); st.setRollbackOnly() }
        assertEquals(ok0, cnt("seat.confirm.pay_start", "result", "ok"), "바깥 롤백 — ok는 세지 않는다")
        assertEquals(200, paymentStart(hold, x).status)
        assertEquals(ok0 + 1, cnt("seat.confirm.pay_start", "result", "ok"), "커밋된 확인 ok +1")
    }

    @Test
    fun `RC16-3 확정 커밋 실패 — failed{500} +1 · rejected 그대로`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val f0 = cnt("seat.confirm.failed", "status", "500")
        val rej0 = tot("seat.confirm.rejected")
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION rc16_3_fail() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'RC16-3 commit failure injection'; END \$\$")
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER rc16_3_fail_commit AFTER DELETE ON seat_hold DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION rc16_3_fail()")
        val r = try {
            confirmHttp(hold, x)
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS rc16_3_fail_commit ON seat_hold")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS rc16_3_fail()")
        }
        assertEquals(500, r.status, "커밋 실패 확정은 500: $r")
        assertTrue(holdExists(hold), "롤백 — 홀드는 남는다")
        assertEquals(f0 + 1, cnt("seat.confirm.failed", "status", "500"), "failed{500} +1")
        assertEquals(rej0, tot("seat.confirm.rejected"), "커밋 실패는 도메인 거부가 아니다")
    }

    @Test
    fun `OQ-2 확정 경로 인자 변환 실패(holdId 숫자 아님) — failed{400} +1`() {
        val f0 = cnt("seat.confirm.failed", "status", "400")
        val rej0 = tot("seat.confirm.rejected")
        val r = rawPost("/api/holds/abc/confirm", x, "application/json", """{"paymentUid":"p"}""")
        assertEquals(400, r.status, "$r")
        assertEquals(f0 + 1, cnt("seat.confirm.failed", "status", "400"))
        assertEquals(rej0, tot("seat.confirm.rejected"))
    }

    @Test
    fun `RC2-1 거부 표지 뒤 본문을 못 써도 실제 응답 상태로 하나만 센다(Accept text-html)`() {
        val rej0 = tot("seat.confirm.rejected")
        val fail0 = tot("seat.confirm.failed")
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:$port/api/holds/9999999/confirm"))
            .timeout(java.time.Duration.ofSeconds(60))
            .header(com.jun.labs.seatreservation.api.USER_ID_HEADER, x.toString())
            .header("Content-Type", "application/json").header("Accept", "text/html")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("""{"paymentUid":"p"}""")).build()
        val status = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()
        val dRej = tot("seat.confirm.rejected") - rej0
        val dFail = tot("seat.confirm.failed") - fail0
        assertEquals(1.0, dRej + dFail, "정확히 하나만 센다(status=$status · rejected +$dRej · failed +$dFail)")
        if (status in setOf(403, 404, 409)) assertEquals(1.0, dRej, "도메인 거부 상태로 나갔으면 rejected(status=$status)")
        else {
            assertEquals(1.0, dFail, "본문을 못 써 $status 로 나갔으면 failed")
            assertTrue(cnt("seat.confirm.failed", "status", status.toString()) >= 1.0, "failed{$status} 태그로 센다")
        }
    }
}
