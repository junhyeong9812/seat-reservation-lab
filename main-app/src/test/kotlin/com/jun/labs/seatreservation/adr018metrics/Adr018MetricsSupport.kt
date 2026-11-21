package com.jun.labs.seatreservation.adr018metrics

import com.fasterxml.jackson.databind.JsonNode
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.adr018.Adr018Base
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import java.net.URI
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ADR-018 2단계 task 01 — 묶음 경로 최소 지표의 blind 계약 시험 공용 장치(구현 미열람 · 테스트 소스만).
 * 기준 = docs/plans/2026-11-20/adr-018-measurement/test-contract.md §1 · §2 + requirement-spec.md §2 M18-6.
 * 계약 표면만 쓴다: 지표 이름 · 태그(MeterRegistry) · HTTP(상태 · 본문 code · 헤더 X-Seatlab-Reject) · DB 행(JDBC) · 만료 진입점 ExpireHoldsUseCase(기존).
 *
 * 40P01은 실제 교착 대신 **DB 트리거가 그 SQLSTATE로 예외를 던지게** 해서 만든다(PL/pgSQL `RAISE … USING ERRCODE = '40P01'`).
 * 앱이 받는 것은 실제 교착과 같은 PSQLException(sqlState 40P01)이고, 계약의 판정 기준이 '예외 사슬 안의 SQLException.sqlState == 40P01'이라
 * 구현 내부 클래스에 기대지 않고 지표 · HTTP만으로 확인할 수 있다. 같은 자리에 다른 SQLSTATE(40001 · P0001)를 던지면 무변화여야 한다.
 */

// ── 계약 이름(test-contract §1) ─────────────────────────────────────────────────────────────
const val M_GROUP_REQUEST = "seat.group.request"
const val M_GROUP_HOLD_REJECTED = "seat.group.hold.rejected"
const val M_DB_DEADLOCK = "seat.db.deadlock"
/** 기존 1석 선점 거절 지표(loadtest 프로필 — ADR-015~017 하네스가 헤더 합과 대조) */
const val M_HOLD_REJECTED = "seat.hold.rejected"
const val M_TICK = "seat.hold.expiry.tick"
const val REJECT_HEADER = "X-Seatlab-Reject"

const val OP_HOLD = "hold"
const val OP_RELEASE_GROUP = "release_group"
const val OP_RELEASE_HOLD = "release_hold"
const val OP_PAY_START = "pay_start"
const val OP_CONFIRM = "confirm"

const val SQLSTATE_DEADLOCK = "40P01"
/** 대조용 — 교착과 같은 계열(40 — 트랜잭션 롤백)이지만 교착이 아닌 직렬화 실패 */
const val SQLSTATE_SERIALIZATION = "40001"
/** 대조용 — PL/pgSQL 기본 RAISE */
const val SQLSTATE_RAISE = "P0001"

/** 응답 + 헤더(기존 Resp에는 헤더가 없다) */
class HResp(val status: Int, val body: String, private val headers: HttpHeaders) {
    private val r = Resp(status, body)
    val json: JsonNode? get() = r.json
    /** 오류 본문의 code(ErrorCode 이름) — 문자열일 때만 */
    val code: String? get() = r.json?.get("code")?.takeIf { it.isTextual }?.asText()
    fun headerValues(name: String): List<String> = headers.allValues(name)
    fun keys(): Set<String> = r.json?.takeIf { it.isObject }?.fieldNames()?.asSequence()?.toSet() ?: emptySet()
    override fun toString() = "HTTP $status $body · $REJECT_HEADER=${headerValues(REJECT_HEADER)}"
}

/**
 * test-contract §1.1 outcome 규칙을 응답에서 다시 계산한다: 2xx = ok · ErrorCode로 나간 응답(본문 code) = 그 이름 · 그 밖 = status_<코드>.
 * (지표는 시험 대상이고 이 값은 응답에서 나온 기대값이다 — 응답 모양이 계약대로인지는 각 시험이 따로 단언한다.)
 */
fun outcomeOf(r: HResp): String = when {
    r.status in 200..299 -> "ok"
    r.code != null -> r.code!!
    else -> "status_${r.status}"
}

typealias TagKey = List<String?>

/**
 * 묶음 지표 시험 기반 — Adr018Base(흉내 PG · refund · 묶음 HTTP · DB 도우미) 위에 헤더를 보는 HTTP · 지표 차이 · SQLSTATE 주입 · 좌석 행 잠금을 얹는다.
 * 하위 클래스가 `@SpringBootTest(RANDOM_PORT · 고정 키 + P17 + P18)` · `@Import(…)`를 단다(1단계 시험과 같은 키 — 컨텍스트 캐시 공유).
 */
abstract class Adr018MetricsBase : Adr018Base() {

    // ---- HTTP(헤더 포함) ----

    fun req(method: String, path: String, userId: Long?, body: String?, contentType: String? = if (body != null) "application/json" else null): HResp {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(60))
        if (userId != null) b.header(USER_ID_HEADER, userId.toString())
        if (contentType != null) b.header("Content-Type", contentType)
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val res = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return HResp(res.statusCode(), res.body(), res.headers())
    }

    fun seatIdsBody(seatIds: List<Long>) = """{"seatIds":[${seatIds.joinToString(",")}]}"""

    fun gHold(seatIds: List<Long>, userId: Long, scheduleId: Any = schedule.id!!): HResp =
        req("POST", "/api/schedules/$scheduleId/hold-groups", userId, seatIdsBody(seatIds))
    fun gRelease(groupId: Any, userId: Long): HResp = req("DELETE", "/api/hold-groups/$groupId", userId, null)
    fun hRelease(holdId: Any, userId: Long): HResp = req("DELETE", "/api/holds/$holdId", userId, null)
    fun gStart(groupId: Any, userId: Long): HResp = req("POST", "/api/hold-groups/$groupId/payment-start", userId, null)
    fun gConfirm(groupId: Any, userId: Long, uid: String): HResp =
        req("POST", "/api/hold-groups/$groupId/confirm", userId, """{"paymentUid":"$uid"}""")
    fun hHold(seatId: Long, userId: Long): HResp = req("POST", "/api/schedules/${schedule.id}/seats/$seatId/hold", userId, null)

    // ---- 지표 ----

    /** 이름 아래 카운터를 [tagKeys] 값 묶음별로 합친다 */
    fun snap(name: String, vararg tagKeys: String): Map<TagKey, Double> =
        meters.find(name).counters()
            .groupBy { c -> tagKeys.map { c.id.getTag(it) } }
            .mapValues { (_, cs) -> cs.sumOf { it.count() } }

    fun diff(before: Map<TagKey, Double>, after: Map<TagKey, Double>): Map<TagKey, Double> =
        (before.keys + after.keys).associateWith { (after[it] ?: 0.0) - (before[it] ?: 0.0) }.filterValues { it != 0.0 }

    /**
     * 응답을 다 쓴 뒤에 세는 구현이면 응답 직후에는 아직 안 올랐을 수 있다 — 차이 합이 [total]에 닿을 때까지만 잠깐(최대 5초) 기다린다.
     * 시간은 단언에 쓰지 않는다(닿지 않으면 그때의 차이로 정확 비교 단언이 실패한다).
     */
    fun awaitDiff(name: String, tagKeys: Array<out String>, before: Map<TagKey, Double>, total: Double): Map<TagKey, Double> {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var d = diff(before, snap(name, *tagKeys))
        while (d.values.sum() < total && System.nanoTime() < end) {
            Thread.sleep(20)
            d = diff(before, snap(name, *tagKeys))
        }
        return d
    }

    /**
     * §1.1 — 요청 하나를 보내고 `seat.group.request`가 {op, outcome(응답에서 계산)} 하나만 정확히 1 오른 것을 본다(겹침 · 이중 셈 없음).
     * [expectOutcome]이 있으면 응답이 그 outcome이 되는 모양인지도 본다(응답 전제 — 그린 위장 방지).
     */
    fun counted(op: String, why: String, expectOutcome: String? = null, call: () -> HResp): HResp {
        val before = snap(M_GROUP_REQUEST, "op", "outcome")
        val r = call()
        val outcome = outcomeOf(r)
        if (expectOutcome != null) assertEquals(expectOutcome, outcome, "$why — 응답 전제(outcome): $r")
        val d = awaitDiff(M_GROUP_REQUEST, arrayOf("op", "outcome"), before, 1.0)
        assertEquals(
            mapOf<TagKey, Double>(listOf(op, outcome) to 1.0), d,
            "$why — $M_GROUP_REQUEST{op=$op, outcome=$outcome}만 정확히 1 증가해야 한다(요청 하나 = 1 · 다른 태그 무변화): $r",
        )
        return r
    }

    /** 묶음 지표에 들어가면 안 되는 요청(1석 선점 · 결제 시작 · 확정 · 핸들러 없음 404) */
    fun notCounted(why: String, call: () -> HResp): HResp {
        val before = snap(M_GROUP_REQUEST, "op", "outcome")
        val r = call()
        assertEquals(emptyMap(), diff(before, snap(M_GROUP_REQUEST, "op", "outcome")), "$why — $M_GROUP_REQUEST 무변화여야 한다: $r")
        return r
    }

    // ---- SQLSTATE 주입(DB 트리거) ----

    /**
     * [table]의 [event](INSERT · UPDATE · DELETE) 행 앞에서 [sqlState]로 예외를 던지는 트리거를 건 채 [block]을 돌리고 반드시 지운다.
     * [condition]은 트리거 WHEN 절(NEW · OLD 행 조건). 컨텍스트의 스케줄러는 꺼져 있어 그 사이 다른 쓰기는 없다.
     */
    fun <T> withSqlState(table: String, event: String, sqlState: String, condition: String? = null, block: () -> T): T {
        jdbcTemplate.execute(
            "CREATE OR REPLACE FUNCTION m18_raise() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN " +
                "RAISE EXCEPTION 'adr018 metrics injection $sqlState' USING ERRCODE = '$sqlState'; END \$\$",
        )
        jdbcTemplate.execute(
            "CREATE TRIGGER m18_raise BEFORE $event ON $table FOR EACH ROW " +
                (condition?.let { "WHEN ($it) " } ?: "") + "EXECUTE FUNCTION m18_raise()",
        )
        try {
            return block()
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS m18_raise ON $table")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS m18_raise()")
        }
    }

    /**
     * §1.3 — 주입한 SQLSTATE가 40P01이면 `seat.db.deadlock{path}`가 정확히 1, 아니면 무변화. 반환 = 그 사이의 deadlock 차이.
     * (다른 SQLSTATE 쪽은 기다릴 대상이 없어 바로 잰다 — 늦게 오르는 잘못된 셈은 같은 시험의 뒤 단계 차이에 드러난다.)
     */
    fun assertDeadlockDiff(before: Map<TagKey, Double>, sqlState: String, path: String, why: String) {
        val d = if (sqlState == SQLSTATE_DEADLOCK) awaitDiff(M_DB_DEADLOCK, arrayOf("path"), before, 1.0)
        else diff(before, snap(M_DB_DEADLOCK, "path"))
        val want: Map<TagKey, Double> = if (sqlState == SQLSTATE_DEADLOCK) mapOf(listOf<String?>(path) to 1.0) else emptyMap()
        assertEquals(want, d, "$why — SQLSTATE $sqlState: $M_DB_DEADLOCK 차이")
    }

    // ---- 좌석 행 잠금(경합 재현) ----

    /**
     * 다른 연결이 좌석 행을 `FOR UPDATE`로 쥔 채 [block]을 돌린다 — 분리형은 NOWAIT 패배(55P03), 단일 쿼리는 SKIP LOCKED로 바뀐 행 수 < N,
     * 1석 비관락은 NOWAIT 패배가 결정적으로 난다(설계-2). 끝나면 롤백해 잠금을 푼다.
     */
    fun <T> withSeatRowLocked(seatId: Long, block: () -> T): T =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("SELECT id FROM product_seat WHERE id = ? FOR UPDATE").use { ps ->
                    ps.setLong(1, seatId)
                    ps.executeQuery().use { rs -> assertTrue(rs.next(), "잠글 좌석 $seatId 없음") }
                }
                block()
            } finally {
                c.rollback()
            }
        }
}
