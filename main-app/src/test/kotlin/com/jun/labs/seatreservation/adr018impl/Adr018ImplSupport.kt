package com.jun.labs.seatreservation.adr018impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.support.IntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

// ADR-018 구현 시험 공용(구현 워커 몫 — 블라인드 시험은 adr018/). 키 이름은 test-contract.md §1.
const val G_ENABLED = "seat.hold-group.enabled=true"
const val G_SPLIT = "seat.hold-group.mode=split"
const val G_ONE_SHOT = "seat.hold-group.mode=one-shot"
const val G_A_ON = "seat.hold.counter-drift.expiry-repair=true"
const val G_SCHED_OFF = "seat.hold.expiry-scheduler-enabled=false"
const val G_COMP_SCHED_OFF = "seat.compensation.scheduler-enabled=false"

class R(val status: Int, val body: String) {
    val json: JsonNode? = runCatching { ObjectMapper().readTree(body) }.getOrNull()
    val code: String? get() = json?.get("code")?.asText()
    override fun toString() = "HTTP $status $body"
}

/** HTTP · DB 도우미 — 하위 클래스가 `@SpringBootTest(RANDOM_PORT · 고정 키 …)` · `@Import(TestcontainersConfiguration, ClockConfig)`를 단다 */
abstract class Adr018ImplBase : IntegrationTest() {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var expire: ExpireHoldsUseCase

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    fun send(method: String, path: String, userId: Long?, body: String? = null): R {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
        if (userId != null) b.header(USER_ID_HEADER, userId.toString())
        val pub = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        b.method(method, pub)
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return R(r.statusCode(), r.body())
    }

    fun holdGroup(userId: Long, vararg seatIds: Long, scheduleId: Long = schedule.id!!): R =
        send("POST", "/api/schedules/$scheduleId/hold-groups", userId, """{"seatIds": [${seatIds.joinToString(",")}]}""")

    fun holdGroupOk(userId: Long, vararg seatIds: Long): Long {
        val r = holdGroup(userId, *seatIds)
        assertEquals(201, r.status, "묶음 선점 준비: $r")
        return r.json!!.get("groupId").asLong()
    }

    fun holdOne(userId: Long, seatId: Long): R = send("POST", "/api/schedules/${schedule.id}/seats/$seatId/hold", userId)
    fun holdOneOk(userId: Long, seatId: Long): Long = holdOne(userId, seatId).also { assertEquals(201, it.status, "1석 선점 준비: $it") }.json!!.get("holdId").asLong()

    fun releaseGroup(userId: Long, groupId: Long) = send("DELETE", "/api/hold-groups/$groupId", userId)
    fun releaseHold(userId: Long, holdId: Long) = send("DELETE", "/api/holds/$holdId", userId)
    fun groupPaymentStart(userId: Long, groupId: Long) = send("POST", "/api/hold-groups/$groupId/payment-start", userId)
    fun groupConfirm(userId: Long, groupId: Long, uid: String) = send("POST", "/api/hold-groups/$groupId/confirm", userId, """{"paymentUid":"$uid"}""")
    fun holdPaymentStart(userId: Long, holdId: Long) = send("POST", "/api/holds/$holdId/payment-start", userId)
    fun holdConfirm(userId: Long, holdId: Long, uid: String) = send("POST", "/api/holds/$holdId/confirm", userId, """{"paymentUid":"$uid"}""")

    fun seatIds(n: Int, from: Int = 1): List<Long> = (from until from + n).map { createSeat(it).id!! }

    fun q(sql: String, vararg args: Any?): Long = jdbcTemplate.queryForObject(sql, Long::class.java, *args)!!
    fun cnt(userId: Long): Int? = jdbcTemplate.queryForList(
        "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, schedule.id, userId,
    ).singleOrNull()
    fun holdsOfUser(userId: Long): Long = q("SELECT count(*) FROM seat_hold WHERE user_id = ?", userId)
    fun holdsOfGroup(groupId: Long): Long = q("SELECT count(*) FROM seat_hold WHERE group_id = ?", groupId)
    fun seatStatus(seatId: Long): String = jdbcTemplate.queryForObject("SELECT status FROM product_seat WHERE id = ?", String::class.java, seatId)!!
    fun groupExpiries(groupId: Long): List<Instant> = jdbcTemplate.queryForList(
        "SELECT DISTINCT expires_at FROM seat_hold WHERE group_id = ?", Timestamp::class.java, groupId,
    ).map { it.toInstant() }

    /** 묶음 정합성 항목(정합성 검증기 설계-7과 같은 정의) + 카운터 ≠ 홀드 + 확정 예약 */
    fun violations(): Map<String, Long> = mapOf(
        "group_partial_holds" to q(
            """SELECT count(*) FROM (SELECT group_id, count(*) n FROM seat_hold WHERE group_id IS NOT NULL GROUP BY group_id) h
               JOIN hold_group g ON g.id = h.group_id WHERE h.n <> g.seat_count""",
        ),
        "group_expiry_split" to q("SELECT count(*) FROM (SELECT group_id FROM seat_hold WHERE group_id IS NOT NULL GROUP BY group_id HAVING count(DISTINCT expires_at) > 1) x"),
        "held_without_hold" to q("SELECT count(*) FROM product_seat s WHERE s.status = 'HELD' AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = s.id)"),
        "hold_on_unheld" to q("SELECT count(*) FROM seat_hold h JOIN product_seat s ON s.id = h.seat_id WHERE s.status <> 'HELD'"),
        "counter_mismatch" to q(
            """SELECT count(*) FROM user_hold_quota q WHERE q.cnt <>
               (SELECT count(*) FROM seat_hold h WHERE h.schedule_id = q.schedule_id AND h.user_id = q.user_id)
             + (SELECT count(*) FROM reservation r WHERE r.schedule_id = q.schedule_id AND r.user_id = q.user_id AND r.status = 'CONFIRMED')""",
        ),
    )

    fun assertNoViolations(why: String) = assertEquals(violations().mapValues { 0L }, violations(), why)
}
