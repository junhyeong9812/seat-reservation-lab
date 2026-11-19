package com.jun.labs.seatreservation.adr018

import com.jun.labs.seatreservation.adr015.Adr015Taps
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.adr017.Adr017PgBase
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.domain.ProductSchedule
import com.jun.labs.seatreservation.domain.ProductSeat
import org.junit.jupiter.api.AfterEach
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * ADR-018 1단계 task 03 — blind 계약 테스트 공용 장치(구현 미열람 · 테스트 소스만).
 * 기준 = 명세 docs/plans/2026-11-18/adr-018-adjacent-seats/requirement-spec.md §2 G1~G6 · §5
 *      + ADR-018 설계-1~8 + 시험 접점 계약 test-contract.md(설정 키 · HTTP · DB · 파일 경계).
 * 새 기능은 외부 계약으로만 건드린다: 설정 키 문자열 · HTTP(상태 · JSON 필드 · 오류 code 문자열) · JDBC 행 대조(설계-1 열 이름).
 * 아직 없는 Kotlin 심볼(새 클래스 · 프로퍼티 · ErrorCode 값)에는 기대지 않는다.
 *
 * 컨텍스트: Adr017PgBase(흉내 PG 같은 JVM · 보상 refund) 위 — 묶음은 refund · none에서만 켜진다(설계-7). 확정 모드는 기본 conditional-extend
 * (W 611초 · θ 1초 · M 1초 — application.yml) · TTL 5분 · 1인 한도 2.
 */

// ── 설정 키(test-contract §1) ───────────────────────────────────────────────────────────────
const val HG_ENABLED_KEY = "seat.hold-group.enabled"
const val HG_MODE_KEY = "seat.hold-group.mode"
const val P18_ON = "$HG_ENABLED_KEY=true"
const val P18_OFF = "$HG_ENABLED_KEY=false"
const val P18_SPLIT = "$HG_MODE_KEY=split"
const val P18_ONE_SHOT = "$HG_MODE_KEY=one-shot"

// ── 오류 코드(test-contract §2 — 문자열로만) ─────────────────────────────────────────────────
const val INVALID_SEAT_SELECTION = "INVALID_SEAT_SELECTION"
const val HOLD_IN_GROUP = "HOLD_IN_GROUP"
const val PAYMENT_STARTED = "PAYMENT_STARTED"
const val SCHEDULE_CLOSED = "SCHEDULE_CLOSED"
const val SEAT_NOT_FOUND = "SEAT_NOT_FOUND"
const val SEAT_NOT_AVAILABLE = "SEAT_NOT_AVAILABLE"
const val HOLD_LIMIT_EXCEEDED = "HOLD_LIMIT_EXCEEDED"
const val HOLD_NOT_FOUND = "HOLD_NOT_FOUND"
const val HOLD_NOT_OWNED = "HOLD_NOT_OWNED"
const val HOLD_EXPIRED = "HOLD_EXPIRED"
/** 모르는 uid · 없는 묶음 · 묶음/사용자가 다른 결제 의도(설계-5 · test-contract §2 — 2026-11-19 추가) */
const val SEAT_NOT_HELD = "SEAT_NOT_HELD"

// ── 응답 키(test-contract §2) ────────────────────────────────────────────────────────────────
val GROUP_HOLD_KEYS = setOf("groupId", "expiresAt", "payBy", "payWithinSeconds", "holds")
val GROUP_START_KEYS = setOf("groupId", "expiresAt", "payBy", "payWithinSeconds", "extended", "paymentUid")
val GROUP_CONFIRM_KEYS = setOf("status", "reservations")

/** 시험 기본값(application.yml) */
val TTL_5M: Duration = Duration.ofMinutes(5)
val W_611: Duration = Duration.ofSeconds(611)
const val AMOUNT = 10_000L
const val NO_SUCH_ID = 999_999_999L

/** 신설 정합성 필드(test-contract §3) */
val NEW_CONSISTENCY_FIELDS = listOf("pay_multi_reservation_intents", "group_partial_holds", "group_expiry_split", "group_partial_reservations")

data class GroupHold(val groupId: Long, val expiresAt: Instant, val holds: Map<Long, Long>, val resp: Resp) {
    /** seatId 오름차순의 holdId */
    val holdIds: List<Long> get() = holds.toSortedMap().values.toList()
    val seatIds: List<Long> get() = holds.keys.sorted()
}

/**
 * 묶음 시험 기반. 하위 클래스는 `@SpringBootTest(RANDOM_PORT · 고정 키 + P17 refund 키 + P18_*)` ·
 * `@Import(TestcontainersConfiguration, IntegrationTest.ClockConfig, Adr015TapConfig)`를 단다(같은 키끼리 컨텍스트 공유).
 */
abstract class Adr018Base : Adr017PgBase() {

    private val seatNo = AtomicInteger(0)

    /** 같은 컨텍스트를 쓰는 다른 시험의 회차 삭제가 hold_group FK에 걸리지 않게 — 뒤에서 묶음 관련 행을 비운다(표가 있을 때만) */
    @AfterEach
    fun clearAdr018Rows() {
        Adr015Taps.reset()
        if (jdbcTemplate.queryForObject("SELECT to_regclass('hold_group') IS NOT NULL", Boolean::class.java) == true) {
            jdbcTemplate.update("DELETE FROM compensation_task")
            jdbcTemplate.update("DELETE FROM confirm_rejection")
            jdbcTemplate.update("DELETE FROM reservation")
            jdbcTemplate.update("DELETE FROM payment_intent")
            jdbcTemplate.update("DELETE FROM seat_hold")
            jdbcTemplate.update("DELETE FROM hold_group")
        }
    }

    fun seat(status: com.jun.labs.seatreservation.domain.SeatStatus = com.jun.labs.seatreservation.domain.SeatStatus.AVAILABLE): Long =
        createSeat(seatNo.incrementAndGet(), status).id!!

    fun seats(n: Int): List<Long> = List(n) { seat() }

    /** 다른 회차와 그 좌석 하나(좌석 없음 · 다른 회차 칸) */
    fun otherScheduleSeat(): Long {
        val other = productScheduleRepository.save(ProductSchedule(productId = schedule.productId, startsAt = schedule.startsAt))
        return productSeatRepository.save(ProductSeat(scheduleId = other.id!!, section = "B", rowNo = 1, seatNo = 1)).id!!
    }

    // ---- HTTP ----

    fun call(method: String, path: String, userId: Long?, body: String?): Resp {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(60))
        if (userId != null) b.header(USER_ID_HEADER, userId.toString())
        if (body != null) b.header("Content-Type", "application/json")
        val pub = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        b.method(method, pub)
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply { Resp(it.statusCode(), it.body()) }.get(60, TimeUnit.SECONDS)
    }

    fun groupHoldRaw(body: String, userId: Long, scheduleId: Long = schedule.id!!): Resp =
        call("POST", "/api/schedules/$scheduleId/hold-groups", userId, body)

    fun groupHold(seatIds: List<Long>, userId: Long): Resp =
        groupHoldRaw("""{"seatIds":[${seatIds.joinToString(",")}]}""", userId)

    fun groupHoldOk(seatIds: List<Long>, userId: Long): GroupHold = parseGroup(groupHold(seatIds, userId), "묶음 선점 준비")

    fun parseGroup(r: Resp, why: String): GroupHold {
        assertEquals(201, r.status, "$why: $r")
        val holds = assertNotNull(r.json?.get("holds"), "$why — holds 없음: $r")
        val m = LinkedHashMap<Long, Long>()
        holds.forEach { m[it.get("seatId").asLong()] = it.get("holdId").asLong() }
        return GroupHold(r.long("groupId"), r.instant("expiresAt"), m, r)
    }

    fun releaseHold(holdId: Long, userId: Long): Resp = call("DELETE", "/api/holds/$holdId", userId, null)
    fun releaseGroup(groupId: Long, userId: Long): Resp = call("DELETE", "/api/hold-groups/$groupId", userId, null)
    fun groupStart(groupId: Long, userId: Long): Resp = call("POST", "/api/hold-groups/$groupId/payment-start", userId, null)
    fun groupConfirm(groupId: Long, userId: Long, uid: String): Resp =
        call("POST", "/api/hold-groups/$groupId/confirm", userId, """{"paymentUid":"$uid"}""")

    fun groupStartOk(groupId: Long, userId: Long): Pair<String, Resp> {
        val r = groupStart(groupId, userId)
        assertEquals(200, r.status, "묶음 결제 시작 준비: $r")
        return r.json!!.get("paymentUid").asText() to r
    }

    fun assertCode(r: Resp, status: Int, code: String, why: String) {
        assertEquals(status, r.status, "$why: $r")
        assertEquals(code, r.code, "$why — code: $r")
    }

    // ---- DB ----

    /** 좌석 · 홀드 · 카운터 · 묶음 · 결제 의도 · 예약 · 보상 작업 행 전부 — '무변화' 대조용 */
    fun snapshot(): Map<String, List<Map<String, Any?>>> =
        listOf("product_seat", "seat_hold", "user_hold_quota", "hold_group", "payment_intent", "reservation", "compensation_task")
            .associateWith { t -> jdbcTemplate.queryForList("SELECT * FROM $t ORDER BY 1, 2") }

    fun assertUnchanged(before: Map<String, List<Map<String, Any?>>>, why: String) =
        assertEquals(before, snapshot(), "$why — 좌석 · 홀드 · 카운터 · 묶음 행이 하나도 바뀌지 않아야 한다(G1)")

    fun cnt(userId: Long): Int = cntOf(userId) ?: 0

    fun holdsOf(userId: Long): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE user_id = ?", Int::class.java, userId)!!

    fun groupRows(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM hold_group", Int::class.java)!!

    data class GroupRow(val scheduleId: Long, val userId: Long, val seatCount: Int)

    fun groupRow(groupId: Long): GroupRow = jdbcTemplate.queryForObject(
        "SELECT schedule_id, user_id, seat_count FROM hold_group WHERE id = ?",
        { rs, _ -> GroupRow(rs.getLong(1), rs.getLong(2), rs.getInt(3)) },
        groupId,
    )!!

    data class GroupHoldRow(val holdId: Long, val seatId: Long, val userId: Long, val expiresAt: Instant, val extendedAt: Instant?)

    fun holdsOfGroup(groupId: Long): List<GroupHoldRow> = jdbcTemplate.query(
        "SELECT id, seat_id, user_id, expires_at, extended_at FROM seat_hold WHERE group_id = ? ORDER BY seat_id",
        { rs, _ ->
            GroupHoldRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5)?.toInstant())
        },
        groupId,
    )

    data class GroupIntentRow(val uid: String, val holdId: Long, val seatId: Long, val seatCount: Int, val state: String, val reservationId: Long?)

    fun intentsOfGroup(groupId: Long): List<GroupIntentRow> = jdbcTemplate.query(
        "SELECT uid::text AS uid, hold_id, seat_id, seat_count, state, reservation_id FROM payment_intent WHERE group_id = ?",
        { rs, _ ->
            GroupIntentRow(
                rs.getString("uid"), rs.getLong("hold_id"), rs.getLong("seat_id"), rs.getInt("seat_count"), rs.getString("state"),
                rs.getObject("reservation_id")?.let { (it as Number).toLong() },
            )
        },
        groupId,
    )

    fun seatsStatus(ids: List<Long>): List<String> = ids.map { seatStatusOf(it) }

    /**
     * 설계-7의 묶음 항목 중 홀드 행만으로 정의되는 둘을 같은 정의로 센다(판정기는 loadtest 프로필 빈이라 이 컨텍스트에 없다).
     * group_partial_holds: 남은 홀드 수가 0도 seat_count도 아닌 묶음 · group_expiry_split: 남은 홀드 expires_at이 둘 이상인 묶음.
     */
    fun groupHoldInvariants(): Map<String, Long> = jdbcTemplate.queryForMap(
        """
        SELECT
          (SELECT count(*) FROM hold_group g
             WHERE (SELECT count(*) FROM seat_hold h WHERE h.group_id = g.id) NOT IN (0, g.seat_count)) AS group_partial_holds,
          (SELECT count(*) FROM (SELECT group_id FROM seat_hold WHERE group_id IS NOT NULL GROUP BY group_id
             HAVING count(DISTINCT expires_at) > 1) s) AS group_expiry_split
        """.trimIndent(),
    ).mapValues { (it.value as Number).toLong() }

    fun assertGroupInvariants(why: String) {
        val v = groupHoldInvariants()
        assertTrue(v.values.all { it == 0L }, "$why — 묶음 불변식 위반 $v · ${dbState()}")
    }

    fun ts(i: Instant): Timestamp = Timestamp.from(i)
}
