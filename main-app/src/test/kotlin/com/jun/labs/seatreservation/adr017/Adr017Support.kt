package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.adr015.Adr015Taps
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.adr016.Adr016Base
import com.jun.labs.seatreservation.adr016.PAY_START_KEYS
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * ADR-017 1단계 task 01 · 02 blind 계약 테스트 공용 장치(구현 diff 미열람 — 명세 + ADR 설계만).
 * 기준 = docs/plans/2026-11-13/adr-017-compensation-impl/requirement-spec.md §1(1~3) · §2(N17-10~12) · §5 · §8(A1 · A2) · §9(task 01 · 02)
 *      + ADR-017 설계-4.1(표) · 4.2(payment-start) · 4.3(확정 흐름) · 설계-5 `none` · 설계-7(N17-1~9) · 설계-9(Q1 · Q3 · Q9) · 설계-10.
 * 새 기능은 외부 계약으로만 건드린다: 설정 키 문자열 · HTTP(상태 · JSON 필드) · JDBC 행 대조(설계-4.1 표 · 열 이름) · 지표 이름.
 * 아직 없는 Kotlin 심볼(새 클래스 · 프로퍼티)에는 기대지 않는다.
 *
 * 열 타입 가정: payment_intent.uid는 UUID일 수도 VARCHAR일 수도 있다(설계-4.1 'uid PK — 서버가 발급하는 UUID') → 비교는 항상 `uid::text = ?`.
 * reservation.intent_id도 BIGINT/UUID(설계-4.1)라 값 비교 대신 'NULL 아님' + payment_intent.reservation_id 연결로 본다.
 */

// ── 손잡이(명세 §1 · 설계-5) ──────────────────────────────────────────────────────────────────
const val COMPENSATION_MODE_KEY = "seat.compensation.mode"
const val PG_BASE_URL_KEY = "seat.payment.pg.base-url"

// ── 지표(설계-4.3 · Q3) ──────────────────────────────────────────────────────────────────────
const val M17_LEGACY = "seat.confirm.legacy"
const val M17_UID_CONFLICT = "seat.confirm.uid_conflict"

// ── 응답 필드(명세 §1 · 2 · 3) ─────────────────────────────────────────────────────────────────
const val PAYMENT_UID = "paymentUid"
const val COMPENSATION = "compensation"
val PAY_START_KEYS_17 = PAY_START_KEYS + PAYMENT_UID
/** 지금(옛 경로) 확정 200 본문 — N17-10: intent 없는 uid는 이 키 그대로 */
val CONFIRM_KEYS = setOf("reservationId", "seatId", "status")
val ERROR_KEYS_17 = setOf("code", "message")

// ── intent 상태(설계-4.1) ─────────────────────────────────────────────────────────────────────
const val OPEN = "OPEN"
const val CONFIRMED = "CONFIRMED"
const val REJECTED = "REJECTED"

data class IntentRow(
    val uid: String,
    val holdId: Long,
    val userId: Long,
    val scheduleId: Long,
    val seatId: Long,
    val section: String?,
    val payBy: Instant?,
    val state: String,
    val resultCode: String?,
    val reservationId: Long?,
    val closedReason: String?,
    val createdAt: Instant?,
)

data class RejectionRow(val uid: String, val code: String, val httpStatus: Int, val rejectedAt: Instant?)

/**
 * Adr016Base(HTTP · 지표 · 행 대조 · 래치) 위에 ADR-017 도우미를 얹는다. 하위 클래스가 `@SpringBootTest(RANDOM_PORT · 고정 키 + 모드)` ·
 * `@Import(TestcontainersConfiguration, ClockConfig, Adr015TapConfig | Adr016SqlTapConfig)`를 단다 — 애너테이션을 기존 ADR-016 테스트와 같게 두어
 * 컨텍스트 캐시를 함께 쓴다.
 *
 * 정리: IntegrationTest.resetData는 새 표를 모른다 → 이 클래스가 테스트 앞뒤로 새 표를 비운다(뒤 = 같은 컨텍스트를 쓰는 다른 테스트의 resetData가
 * FK에 걸리지 않게). 순서 = 보상 작업 → 거부 기록 → 예약(intent_id 참조) → intent.
 */
abstract class Adr017Base : Adr016Base() {

    @BeforeEach
    fun clearAdr017RowsBefore() = clearAdr017Rows()

    @AfterEach
    fun clearAdr017RowsAfter() {
        Adr015Taps.reset() // 혹시 남은 래치를 먼저 푼다(멈춘 트랜잭션이 잠금을 쥔 채 DELETE를 막지 않게)
        clearAdr017Rows()
    }

    private fun clearAdr017Rows() {
        jdbcTemplate.update("DELETE FROM compensation_task")
        jdbcTemplate.update("DELETE FROM confirm_rejection")
        jdbcTemplate.update("DELETE FROM reservation")
        jdbcTemplate.update("DELETE FROM payment_intent")
    }

    // ---- HTTP ----

    /** payment-start 200 → paymentUid(서버 발급 UUID — 설계-4.1) */
    fun startOk(holdId: Long, userId: Long): Pair<String, Resp> {
        val r = paymentStart(holdId, userId)
        assertEquals(200, r.status, "payment-start 준비 실패: $r")
        val uid = assertNotNull(r.json?.get(PAYMENT_UID)?.takeIf { !it.isNull }?.asText(), "payment-start 200에 $PAYMENT_UID 가 없다: $r")
        return uid to r
    }

    fun getHttp(path: String, userId: Long?): Resp {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(60)).GET()
        if (userId != null) b.header(USER_ID_HEADER, userId.toString())
        val res = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Resp(res.statusCode(), res.body())
    }

    /** 같은 순간에 출발하는 블록들(스레드마다 하나 · CountDownLatch 출발선) — 결과는 블록 순서대로 */
    fun <T> together(vararg blocks: () -> T): List<T> {
        val ready = CountDownLatch(blocks.size)
        val go = CountDownLatch(1)
        val fs = blocks.map { b ->
            ctlPool.submit<T> {
                ready.countDown()
                go.await(30, TimeUnit.SECONDS)
                b()
            }
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS), "출발선에 모두 서지 못했다")
        go.countDown()
        return fs.map { it.get(90, TimeUnit.SECONDS) }
    }

    // ---- 응답 본문 ----

    /**
     * intent 경로 본문 — 기존 키는 그대로 있고, 더해질 수 있는 것은 `compensation` 하나(명세 §1 · 3 · I11 '본문 필드 추가만').
     * 모드 none = 보상 작업 없음(설계-5) → compensation이 있다면 null이거나 taskId가 비어 있어야 한다.
     */
    // 가정: none 모드에서 compensation 키가 빠지는지 · null인지 · {taskId:null,…}인지는 명세가 정하지 않았다 — 셋 다 허용하고 '작업 id 없음'만 본다.
    fun assertIntentBodyNoTask(r: Resp, base: Set<String>, why: String) {
        val keys = r.keys()
        assertTrue(keys.containsAll(base), "$why — 기존 키 $base 는 그대로 있어야 한다: $r")
        assertTrue((keys - base).all { it == COMPENSATION }, "$why — 더해지는 키는 $COMPENSATION 뿐이다: $r")
        val comp = r.json?.get(COMPENSATION)
        if (comp != null && !comp.isNull) {
            val taskId = comp.get("taskId")
            assertTrue(taskId == null || taskId.isNull, "$why — 모드 none은 보상 작업이 없다(taskId 없음): $r")
        }
    }

    /** 옛 경로 본문 — 새 필드 없음(N17-10) */
    fun assertLegacyBody(r: Resp, base: Set<String>, why: String) =
        assertEquals(base, r.keys(), "$why — 옛 경로 본문 키는 지금 그대로(N17-10 · compensation 없음): $r")

    // ---- DB ----

    fun intentCount(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM payment_intent", Int::class.java)!!

    fun intentCountOfHold(holdId: Long): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM payment_intent WHERE hold_id = ?", Int::class.java, holdId)!!

    fun intent(uid: String): IntentRow? = jdbcTemplate.query(
        """
        SELECT uid::text AS uid, hold_id, user_id, schedule_id, seat_id, section, pay_by, state, result_code, reservation_id, closed_reason, created_at
        FROM payment_intent WHERE uid::text = ?
        """.trimIndent(),
        { rs, _ ->
            IntentRow(
                uid = rs.getString("uid"),
                holdId = rs.getLong("hold_id"),
                userId = rs.getLong("user_id"),
                scheduleId = rs.getLong("schedule_id"),
                seatId = rs.getLong("seat_id"),
                section = rs.getString("section"),
                payBy = rs.getTimestamp("pay_by")?.toInstant(),
                state = rs.getString("state"),
                resultCode = rs.getString("result_code"),
                reservationId = rs.getObject("reservation_id")?.let { (it as Number).toLong() },
                closedReason = rs.getString("closed_reason"),
                createdAt = rs.getTimestamp("created_at")?.toInstant(),
            )
        },
        uid,
    ).singleOrNull()

    fun intentOrFail(uid: String): IntentRow = assertNotNull(intent(uid), "payment_intent에 uid=$uid 행이 없다")

    fun rejections(uid: String): List<RejectionRow> = jdbcTemplate.query(
        "SELECT uid::text AS uid, code, http_status, rejected_at FROM confirm_rejection WHERE uid::text = ?",
        { rs, _ -> RejectionRow(rs.getString("uid"), rs.getString("code"), rs.getInt("http_status"), rs.getTimestamp("rejected_at")?.toInstant()) },
        uid,
    )

    fun rejectionCount(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM confirm_rejection", Int::class.java)!!

    fun taskCount(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM compensation_task", Int::class.java)!!

    /**
     * 그 uid로 만들어진 예약 수.
     */
    // 가정: intent 경로 확정도 지금처럼 요청 paymentUid를 reservation.payment_uid에 저장한다(F4 '저장만 한다' 유지 — 설계-4.3은 'intent_id 포함'만 더함).
    fun reservationsOfUid(uid: String): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE payment_uid = ?", Int::class.java, uid)!!

    data class ReservationRow(val id: Long, val seatId: Long, val userId: Long, val paymentUid: String, val status: String, val intentIdNull: Boolean, val origin: String?)

    fun reservation(id: Long): ReservationRow = jdbcTemplate.queryForObject(
        "SELECT id, seat_id, user_id, payment_uid, status, intent_id IS NULL AS intent_null, origin FROM reservation WHERE id = ?",
        { rs, _ ->
            ReservationRow(
                rs.getLong("id"), rs.getLong("seat_id"), rs.getLong("user_id"), rs.getString("payment_uid"),
                rs.getString("status"), rs.getBoolean("intent_null"), rs.getString("origin"),
            )
        },
        id,
    )!!

    fun reservationsWithIntent(): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE intent_id IS NOT NULL", Int::class.java)!!

    fun deleteHoldRow(holdId: Long) = assertEquals(1, jdbcTemplate.update("DELETE FROM seat_hold WHERE id = ?", holdId), "준비 — 홀드 행 삭제")

    fun setSeatStatus(seatId: Long, status: String) =
        assertEquals(1, jdbcTemplate.update("UPDATE product_seat SET status = ? WHERE id = ?", status, seatId), "준비 — 좌석 상태 변경")

    fun isUuid(s: String): Boolean = runCatching { UUID.fromString(s) }.isSuccess

    // ---- 끝 상태 대조(한 uid) ----

    /**
     * N17-1 · N17-2 · N17-5(none 판): 한 intent의 끝 상태가 하나의 결과로만 설명된다.
     * - CONFIRMED ⇔ 그 uid 예약 1 · 거부 기록 0 · reservation_id 채움
     * - REJECTED ⇔ 그 uid 예약 0 · 거부 기록 1(code = result_code)
     * - OPEN ⇔ 예약 0 · 거부 기록 0
     * - 모드 none: 보상 작업 0
     */
    fun assertSingleOutcome(uid: String, why: String) {
        val i = intentOrFail(uid)
        val res = reservationsOfUid(uid)
        val rej = rejections(uid)
        assertTrue(!(res > 0 && rej.isNotEmpty()), "$why — N17-5: 예약과 거부 기록이 함께 있다 uid=$uid intent=$i 예약=$res 거부=$rej · ${dbState()}")
        assertTrue(res <= 1, "$why — N17-1: intent당 예약 ≤ 1(지금 $res)")
        when (i.state) {
            CONFIRMED -> {
                assertEquals(1, res, "$why — CONFIRMED면 예약 1")
                assertTrue(rej.isEmpty(), "$why — CONFIRMED면 거부 기록 0")
                assertNotNull(i.reservationId, "$why — CONFIRMED면 reservation_id")
            }
            REJECTED -> {
                assertEquals(0, res, "$why — REJECTED면 예약 0")
                assertEquals(1, rej.size, "$why — REJECTED면 거부 기록 1(N17-2)")
                assertEquals(i.resultCode, rej.single().code, "$why — 거부 기록 code = intent.result_code")
                assertNull(i.reservationId, "$why — REJECTED면 reservation_id 없음")
            }
            OPEN -> {
                assertEquals(0, res, "$why — OPEN이면 예약 0")
                assertTrue(rej.isEmpty(), "$why — OPEN이면 거부 기록 0")
            }
            else -> throw AssertionError("$why — 모드 none · task 01·02 범위에서 생길 수 없는 state=${i.state}(SUBSTITUTED · CLOSING은 모드 ≠ none)")
        }
    }
}
