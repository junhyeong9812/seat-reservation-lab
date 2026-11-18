package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.TTL
import com.jun.labs.seatreservation.adr016.NO_SUCH_HOLD
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
import org.springframework.core.env.Environment
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * task 01 — V15 · payment-start 필수화(설계-4.2) · ④ conditional-extend(기본 모드 · 확정 키 없음 → W 611초 · θ 1초 · M 1초).
 * 기본 구성 = seat.compensation.mode 없음(none) · seat.payment.pg.base-url 없음(명세 §1 · N17-12).
 * 애너테이션 = Adr016DefaultModeContractTest와 같다(컨텍스트 캐시 공유).
 * 홀드 at NOW → e0 = NOW + 5분 · payment-start at NOW → 남은 5분 < W → expiresAt = NOW + 611초 · payBy = NOW + 610초.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr017PaymentStartIntentTest : Adr017Base() {

    @Autowired
    lateinit var env: Environment

    // ── V15(명세 §1 · 1 · 설계-4.1) ───────────────────────────────────────────────────────────

    private fun columns(table: String): Set<String> = jdbcTemplate.queryForList(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ?", String::class.java, table,
    ).toSet()

    private data class Idx(val cols: List<String>, val unique: Boolean, val partial: Boolean)

    private fun indexes(table: String): List<Idx> = jdbcTemplate.query(
        """
        SELECT array_to_string(ARRAY(
                 SELECT a.attname FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                 JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum ORDER BY k.ord), ',') AS cols,
               i.indisunique AS uniq, i.indpred IS NOT NULL AS partial
        FROM pg_index i WHERE i.indrelid = CAST(? AS regclass)
        """.trimIndent(),
        { rs, _ -> Idx(rs.getString("cols").split(',').filter { it.isNotEmpty() }, rs.getBoolean("uniq"), rs.getBoolean("partial")) },
        table,
    )

    private fun hasUnique(table: String, vararg cols: String) =
        indexes(table).any { it.unique && !it.partial && it.cols.toSet() == cols.toSet() }

    @Test
    fun `V15 — 새 표 셋 · reservation 새 열 둘 · 유니크(hold_id · uid · (uid, type) · intent_id) · 인덱스가 기본 위치 마이그레이션으로 생긴다`() {
        val applied = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '15' AND success", Int::class.java,
        )!!
        assertEquals(1, applied, "V15가 기본 위치(db/migration)에서 적용돼야 한다(명세 §1 · 1)")

        val intentCols = setOf(
            "uid", "hold_id", "user_id", "schedule_id", "seat_id", "section", "pay_by", "state",
            "result_code", "reservation_id", "closed_reason", "created_at",
        )
        assertTrue(columns("payment_intent").containsAll(intentCols), "payment_intent 열(설계-4.1): ${columns("payment_intent")}")
        assertTrue(
            columns("confirm_rejection").containsAll(setOf("id", "uid", "code", "http_status", "rejected_at")),
            "confirm_rejection 열(설계-4.1): ${columns("confirm_rejection")}",
        )
        assertTrue(
            columns("compensation_task").containsAll(
                setOf("id", "uid", "type", "source", "status", "attempts", "next_attempt_at", "lease_until", "last_result", "created_at", "done_at"),
            ),
            "compensation_task 열(설계-4.1): ${columns("compensation_task")}",
        )
        assertTrue(columns("reservation").containsAll(setOf("intent_id", "origin")), "reservation 새 열 intent_id · origin(설계-4.1)")

        assertTrue(hasUnique("payment_intent", "uid"), "payment_intent(uid) PK/유니크: ${indexes("payment_intent")}")
        assertTrue(hasUnique("payment_intent", "hold_id"), "payment_intent(hold_id) UNIQUE(명세 §1 · 1): ${indexes("payment_intent")}")
        assertTrue(indexes("payment_intent").any { it.partial }, "OPEN intent 안전망 조회용 부분 인덱스(명세 §1 · 1): ${indexes("payment_intent")}")
        assertTrue(hasUnique("confirm_rejection", "uid"), "confirm_rejection(uid) UNIQUE(설계-4.1): ${indexes("confirm_rejection")}")
        assertTrue(hasUnique("compensation_task", "uid", "type"), "compensation_task UNIQUE(uid, type)(설계-4.1 · N17-4): ${indexes("compensation_task")}")
        assertTrue(
            indexes("compensation_task").any { it.cols == listOf("status", "next_attempt_at") },
            "compensation_task(status, next_attempt_at) 인덱스(명세 §1 · 1): ${indexes("compensation_task")}",
        )
        assertTrue(hasUnique("reservation", "intent_id"), "reservation.intent_id UNIQUE(N17-1 · RD17-9): ${indexes("reservation")}")
        assertTrue(indexes("reservation").none { it.unique && it.cols == listOf("payment_uid") }, "payment_uid 유니크는 두지 않는다(설계-4.1 · RD17-9)")

        val nullable = jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'reservation' AND column_name = 'intent_id'",
            String::class.java,
        )
        assertEquals("YES", nullable, "reservation.intent_id는 NULL 허용(기존 행 · 옛 경로 = NULL — 설계-4.1)")
        val originDefault = jdbcTemplate.queryForObject(
            "SELECT column_default FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'reservation' AND column_name = 'origin'",
            String::class.java,
        )
        assertTrue(originDefault?.contains("HOLD") == true, "reservation.origin 기본값 HOLD(명세 §1 · 1): $originDefault")
    }

    // ── payment-start 필수화(설계-4.2) ────────────────────────────────────────────────────────

    @Test
    fun `I13 ④ — 첫 200에 paymentUid(서버 발급 UUID) · intent 1행 OPEN · hold · user · schedule · seat · section · pay_by 채움`() {
        val seat = createSeat(1)
        val hold = holdOk(seat.id!!, x)
        val (uid, r) = startOk(hold, x)

        assertEquals(PAY_START_KEYS_17, r.keys(), "payment-start 200 본문 = 기존 키 + paymentUid(명세 §1 · 2): $r")
        assertTrue(isUuid(uid), "paymentUid = 서버 발급 UUID(설계-4.1): $uid")
        assertTrue(uid.length <= 64, "reservation.payment_uid VARCHAR(64)에 들어가야 한다: $uid")
        assertTrue(r.bool("extended"), "④ 연장은 그대로(설계-4.2)")
        assertEquals(NOW.plusSeconds(611), r.instant("expiresAt"), "④ 연장 = now + W 그대로")

        assertEquals(1, intentCount(), "intent 1행")
        val i = intentOrFail(uid)
        assertEquals(OPEN, i.state, "첫 state = OPEN")
        assertEquals(hold, i.holdId, "hold_id")
        assertEquals(x, i.userId, "user_id")
        assertEquals(schedule.id, i.scheduleId, "schedule_id")
        assertEquals(seat.id, i.seatId, "seat_id")
        assertEquals("A", i.section, "section(좌석의 구역 — S 대체가 쓴다 · RD17-6)")
        assertEquals(r.instant("payBy"), i.payBy, "pay_by = 응답 payBy(결제 deadline은 intent의 것 — 설계-4.2)")
        assertNull(i.resultCode, "OPEN에 result_code 없음")
        assertNull(i.reservationId, "OPEN에 reservation_id 없음")
        assertNull(i.closedReason, "OPEN에 closed_reason 없음")
        // 연장이 intent 생성 전에만 일어난다(설계-4.2 RD17-2): intent pay_by는 연장된 홀드 기준
        assertEquals(expiresAtOf(hold).minus(Duration.ofSeconds(1)), i.payBy, "pay_by = 연장된 expires_at − M")
    }

    @Test
    fun `I13 ④ — 같은 hold 재호출(already) = 같은 uid · 같은 payBy · intent 1행 그대로`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val (uid1, r1) = startOk(hold, x)
        val before = intentOrFail(uid1)

        clock.set(NOW.plusSeconds(30))
        val (uid2, r2) = startOk(hold, x)
        assertEquals(uid1, uid2, "같은 hold = 같은 uid(설계-4.2 · payment_intent(hold_id) UNIQUE)")
        assertEquals(r1.instant("payBy"), r2.instant("payBy"), "같은 payBy(④ already 포함 — 설계-4.2)")
        // (메인 정정 — blind 가정 오류) `extended`는 '이 홀드의 만료가 연장돼 있다'(ADR-016 N16-8 — already = true)이다. 다시 늘지 않음은 expiresAt으로 본다
        assertTrue(r2.bool("extended"), "already = 연장돼 있음(true · ADR-016 N16-8)")
        assertEquals(r1.instant("expiresAt"), r2.instant("expiresAt"), "두 번째는 다시 늘지 않는다")
        assertEquals(1, intentCount(), "intent 1행 그대로")
        assertEquals(1, intentCountOfHold(hold))
        assertEquals(before, intentOrFail(uid1), "재호출이 intent 행을 바꾸지 않는다")

        clock.set(NOW.plusSeconds(400)) // 원래 TTL(5분)을 넘었지만 연장된 홀드는 살아 있다
        val (uid3, r3) = startOk(hold, x)
        assertEquals(uid1, uid3, "세 번째도 같은 uid")
        assertEquals(r1.instant("payBy"), r3.instant("payBy"), "세 번째도 같은 payBy")
        assertEquals(1, intentCount())
    }

    @Test
    fun `I13 — 다른 hold는 다른 uid · intent도 따로`() {
        val h1 = holdOk(createSeat(1).id!!, x)
        val h2 = holdOk(createSeat(2).id!!, y)
        val (u1, _) = startOk(h1, x)
        val (u2, _) = startOk(h2, y)
        assertNotEquals(u1, u2, "hold마다 uid")
        assertEquals(2, intentCount())
        assertEquals(h1, intentOrFail(u1).holdId)
        assertEquals(h2, intentOrFail(u2).holdId)
    }

    @Test
    fun `I13 — payment-start 거부(404 없음 · 403 남의 것 · 409 만료)는 intent를 만들지 않는다`() {
        val hold = holdOk(createSeat(1).id!!, x)

        val notFound = paymentStart(NO_SUCH_HOLD, x)
        assertEquals(404, notFound.status, "$notFound")
        assertEquals("HOLD_NOT_FOUND", notFound.code)
        assertTrue(notFound.json?.get(PAYMENT_UID) == null, "거부 본문에 paymentUid 없음: $notFound")

        val notOwned = paymentStart(hold, y)
        assertEquals(403, notOwned.status, "$notOwned")
        assertEquals("HOLD_NOT_OWNED", notOwned.code)

        clock.set(NOW.plus(TTL).plusSeconds(1))
        val expired = paymentStart(hold, x)
        assertEquals(409, expired.status, "$expired")
        assertEquals("HOLD_EXPIRED", expired.code)

        assertEquals(0, intentCount(), "거부된 payment-start는 intent를 만들지 않는다(설계-4.2 '첫 성공 응답에서')")
        assertEquals(0, intentCountOfHold(hold))
    }

    @Test
    fun `N17-12 — 기본 구성(mode none · PG 주소 없음)에서 payment-start는 PG 없이 200 · 보상 작업 0`() {
        // 기본 구성 전제: 두 키가 없다(테스트 속성도 주지 않는다)
        assertTrue(env.getProperty(PG_BASE_URL_KEY).isNullOrBlank(), "기본 구성은 $PG_BASE_URL_KEY 없음(명세 §1 · N17-12)")
        val mode = env.getProperty(COMPENSATION_MODE_KEY)
        assertTrue(mode == null || mode == "none", "기본 $COMPENSATION_MODE_KEY = none(Q1): $mode")

        val hold = holdOk(createSeat(1).id!!, x)
        // PG 주소가 없으니 등록 시도 자체가 없어야 한다 — 있으면 연결 실패 → 503(설계-4.2 '실패면 503')
        val r = paymentStart(hold, x)
        assertEquals(200, r.status, "PG 주소 없음 = 등록 없이 intent만 만들고 200(설계-5 none 문단): $r")
        assertTrue(r.json?.get(PAYMENT_UID)?.asText()?.let(::isUuid) == true, "paymentUid: $r")
        assertEquals(1, intentCount())
        assertEquals(0, taskCount(), "모드 none = 보상 작업 0(설계-5)")
    }
}
