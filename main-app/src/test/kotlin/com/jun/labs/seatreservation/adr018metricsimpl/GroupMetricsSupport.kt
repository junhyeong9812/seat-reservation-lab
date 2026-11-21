package com.jun.labs.seatreservation.adr018metricsimpl

import com.jun.labs.seatreservation.adr018impl.Adr018ImplBase
import com.jun.labs.seatreservation.adr018impl.R
import com.jun.labs.seatreservation.api.REJECT_HEADER
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.support.countOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals

// ADR-018 2단계 task 01 지표 구현 시험 공용(구현 워커 몫 — blind 시험은 adr018metrics/). 이름 · 태그 · 값은 test-contract.md §1.
const val LOADTEST = "loadtest"

/** 응답 상태 · 본문 code · 거절 원인 헤더 */
class H(val status: Int, val code: String?, val reject: String?) {
    override fun toString() = "HTTP $status code=$code reject=$reject"
}

abstract class GroupMetricsBase : Adr018ImplBase() {
    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var tx: TransactionTemplate

    private val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    /** [send]와 같고 거절 원인 헤더까지 돌려준다 */
    fun call(method: String, path: String, userId: Long?, body: String? = null): H {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
        if (userId != null) b.header(USER_ID_HEADER, userId.toString())
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return H(r.statusCode(), R(r.statusCode(), r.body()).code, r.headers().firstValue(REJECT_HEADER).orElse(null))
    }

    fun groupHold(userId: Long?, vararg seatIds: Long): H =
        call("POST", "/api/schedules/${schedule.id}/hold-groups", userId, """{"seatIds": [${seatIds.joinToString(",")}]}""")

    fun count(name: String, vararg tags: Pair<String, String>): Double = meters.countOf(name, *tags)
    fun request(op: String, outcome: String): Double = count(REQUEST, "op" to op, "outcome" to outcome)
    fun requestTotal(): Double = count(REQUEST)
    fun groupRejected(mode: String, cause: String): Double = count(GROUP_REJECTED, "mode" to mode, "cause" to cause)
    fun groupRejectedTotal(): Double = count(GROUP_REJECTED)
    fun holdRejectedTotal(): Double = count(HOLD_REJECTED)
    fun deadlock(path: String): Double = count(DEADLOCK, "path" to path)
    fun deadlockTotal(): Double = count(DEADLOCK)

    /** [block] 한 번(요청 하나)이 seat.group.request를 [op] · [outcome]에만 정확히 1 올렸는가 */
    fun <T> countsOnce(op: String, outcome: String, block: () -> T): T {
        val before = request(op, outcome)
        val total = requestTotal()
        val result = block()
        assertEquals(1.0, request(op, outcome) - before, "$op/$outcome 증가: $result")
        assertEquals(1.0, requestTotal() - total, "seat.group.request 전체 증가(겹침 없음): $result")
        return result
    }

    /**
     * 시험 동안만 [table]의 [event]에 SQLSTATE [sqlState]로 실패하는 트리거를 건다 — 앱의 실제 문장이 그 SQLSTATE로 실패한다(교착 재현 대신).
     * 같은 DB를 다른 시험 클래스가 함께 쓰므로 끝나면 반드시 지운다.
     */
    fun <T> failing(table: String, event: String, sqlState: String, block: () -> T): T {
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION seatlab_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'seatlab test failure' USING ERRCODE = TG_ARGV[0]; END $$
            """.trimIndent(),
        )
        jdbcTemplate.execute("CREATE TRIGGER seatlab_test_fail BEFORE $event ON $table FOR EACH ROW EXECUTE FUNCTION seatlab_test_fail('$sqlState')")
        try {
            return block()
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS seatlab_test_fail ON $table")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS seatlab_test_fail()")
        }
    }

    companion object {
        const val REQUEST = "seat.group.request"
        const val GROUP_REJECTED = "seat.group.hold.rejected"
        const val HOLD_REJECTED = "seat.hold.rejected"
        const val DEADLOCK = "seat.db.deadlock"
    }
}
