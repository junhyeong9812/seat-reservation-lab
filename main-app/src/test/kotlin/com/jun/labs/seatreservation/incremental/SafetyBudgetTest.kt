package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.countOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 대량 시드(SQL) — 회차 [scheduleId]에 사용자 [from]..[to] 쌍마다 좌석 1 · 홀드 1 · cnt [cnt](1이면 맞음, 0이면 below). */
fun IncrementalTestBase.bulkPairs(scheduleId: Long, from: Long, to: Long, cnt: Int, withHold: Boolean = true) {
    if (withHold) {
        jdbcTemplate.update(
            """
            WITH seats AS (
              INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status)
              SELECT ?, 'B', 1, g, 'HELD' FROM generate_series(?::bigint, ?::bigint) g RETURNING id, seat_no
            )
            INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at)
            SELECT id, ?, seat_no, ?, ? FROM seats ORDER BY seat_no
            """.trimIndent(),
            scheduleId, from, to, scheduleId, java.sql.Timestamp.from(IntegrationTest.NOW), java.sql.Timestamp.from(IntegrationTest.NOW.plusSeconds(300)),
        )
    }
    jdbcTemplate.update(
        "INSERT INTO user_hold_quota (schedule_id, user_id, cnt) SELECT ?, g, ? FROM generate_series(?::bigint, ?::bigint) g",
        scheduleId, cnt, from, to,
    )
}

/*
 * 테스트 12 — 안전망 예산(§4.2.5 'DB 시간' · 순서 1~6 · rv41 M5 · Opus M5 · R7 rv41 허용식):
 *   db_ms_total ≤ budget + chunk_ms_max + repair-budget + repair_ms_max + record_ms · repair_pairs ≤ repair-cap ·
 *   db_ms_total = 열린 회차 조회 + 다시 읽기 + 최근 창 + 전체 + 복구 + 기록 · 다시 읽기는 최근 창 몫(앞 절반) · 최근 창이 앞 절반을 먼저 ·
 *   다 보면 남은 예산은 전체 커서로 · 표본 DB 시간은 sample_db_ms에만.
 * (rv43 — §4.2.13 #5/#10 반영) 허용식의 탐지 몫 = max(budget + chunk_ms_max, db_ms_open + budget/2 + 3 × chunk_ms_max) — 갈래(다시 읽기 · 최근 창 · 전체)마다
 *   실행당 첫 블록은 예산과 무관하게 돈다(#10). 합계는 #5 한 읽기만: db_ms_reread는 db_ms_recent에 들지 않는 따로 열.
 * 예산을 30ms로 줄이고 청크를 작게 해 예산이 실제로 멈추게 한다.
 */
@TestPropertySource(
    properties = [
        A_ON, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10,
        "$SAFE.budget=30ms", "$SAFE.recent-id-chunk=50", "seat.hold.counter-drift.reconcile.chunk-size=50",
    ],
)
class SafetyBudgetTest : IncrementalTestBase() {

    private val repairBudgetMs = 1_000L   // repair-budget 기본 1s
    private val repairCap = 500L          // repair-cap 기본

    private fun assertBudgetRow(r: Map<String, Any?>) {
        assertEquals(30L, r.long("budget_ms"), "$r")
        val total = r.long("db_ms_total")
        // (rv43 — §4.2.13 #5/#10 반영) 갈래마다 첫 블록 강제(≤ 3 × chunk_ms_max) — 탐지 몫 = max(budget + c, open + budget/2 + 3c) · +5 = 항마다 ms 내림(1ms 미만씩)
        val budget = r.long("budget_ms")
        val c = r.long("chunk_ms_max")
        val detection = maxOf(budget + c, r.long("db_ms_open") + budget / 2 + 3 * c)
        assertTrue(
            total <= detection + repairBudgetMs + r.long("repair_ms_max") + r.long("record_ms") + 5,
            "R7 허용식 위반: $r",
        )
        assertTrue(r.long("repair_pairs") <= repairCap, "$r")
        // (rv43 — §4.2.13 #5/#10 반영) 합계 = 갈래 합 — #5: db_ms_reread는 따로 열(db_ms_recent에 넣지 않음) · 한 읽기만 허용
        val parts = r.long("db_ms_open") + r.long("db_ms_reread") + r.long("db_ms_recent") + r.long("db_ms_full") + r.long("repair_ms") + r.long("record_ms")
        val tol = 6L                      // 블록마다 ms 반올림
        assertTrue(abs(total - parts) <= tol, "db_ms_total ≠ 열린 회차 + 다시 읽기 + 최근 창 + 전체 + 복구 + 기록: $r")
        assertTrue(r.long("wall_ms") >= 0 && r.long("sample_db_ms") >= 0, "$r")
        assertEquals(SAFETY_APP, r["run_source"], "같은 run_id의 counter_reconcile_run 1행(source safety-app): $r")
    }

    @Test
    fun `12 — 예산 · 허용식 · 합계 · 앞 절반은 최근 창 먼저 · 다 보면 전체 커서로 · 표본은 sample_db_ms에만`() {
        bulkPairs(s, 1, 3_000, cnt = 1)            // 최근 창 id 3,000개(청크 50 → 60청크) — 30ms로는 한 실행에 다 못 본다
        bulkPairs(s, 3_001, 3_005, cnt = 0)        // 어긋남 5(복구 시간이 생기게)
        repeat(3) { sample() }

        val r1 = safetyRun()
        assertBudgetRow(r1)
        assertTrue(r1.long("sample_count") >= 3, "직전 실행 뒤 표본 3회: $r1")
        val half = r1.long("budget_ms") / 2
        val recentSpent = r1.long("db_ms_recent")   // 다시 읽기를 넣은 값이든 뺀 값이든 앞 절반 규칙은 같다
        if (r1.long("recent_hold_cursor") < r1.long("hold_head")) {
            assertTrue(recentSpent + r1.long("db_ms_reread") >= half - 1, "최근 창이 밀려 있으면 앞 절반을 다 쓴다: $r1")
        }
        assertTrue(r1.long("db_ms_recent") <= half + r1.long("chunk_ms_max") + r1.long("db_ms_reread") + 1, "최근 창은 앞 절반 + 청크 1개까지: $r1")
        assertTrue(r1.long("chunks_recent") >= 1, "$r1")

        // 최근 창을 따라잡을 때까지 → 따라잡은 실행은 남은 예산을 전체 커서로 쓴다
        var caughtUp: Map<String, Any?>? = null
        repeat(200) {
            if (caughtUp == null) {
                val r = safetyRun()
                assertBudgetRow(r)
                if (r.long("recent_hold_cursor") >= r.long("hold_head") && r.long("recent_res_cursor") >= r.long("res_head")) caughtUp = r
            }
        }
        val c = checkNotNull(caughtUp) { "최근 창을 200실행 안에 따라잡지 못했다" }
        assertTrue(c.long("chunks_full") >= 1, "최근 창을 다 보면 남은 예산은 전체 커서로: $c")
        assertTrue(c.long("db_ms_full") >= 0)
    }
}

/*
 * 테스트 20 — 복구 상한·이월(§4.2.5 순서 5 · rv41 M5): 한 청크의 어긋난 쌍이 repair-cap보다 많으면 상한까지만 고치고 나머지는 carry_out ·
 * 다음 실행이 탐지보다 먼저 고친다(carry_in) · 대기열이 carry-cap을 넘으면 carry_dropped(지표 포함) · 그 쌍도 결국 고쳐진다(전체 커서).
 */
@TestPropertySource(properties = [A_ON, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10, "$SAFE.repair-cap=2", "$SAFE.carry-cap=3"])
class SafetyCarryTest : IncrementalTestBase() {

    @BeforeEach
    fun drainCarry() {
        // 앞 테스트의 대기열(이미 지운 쌍)을 비운다 — 그 기록은 버린다
        repeat(10) { if (jdbcTemplate.safetyRows().lastOrNull()?.long("carry_out") != 0L) safety.runOnce() }
        listOf("counter_drift_log", "counter_reconcile_run", "counter_safety_run").forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    private fun repairedOnce(users: LongRange) = users.forEach { u ->
        assertEquals(1, jdbcTemplate.logsOf(s, u).count { it.action == "repaired" }, "쌍 $u 정확히 한 번: ${jdbcTemplate.logsOf(s, u)}")
        assertEquals(1, cntOf(s, u))
    }

    @Test
    fun `20 — 어긋난 5쌍 · repair-cap 2 — 2 · 2 · 1로 나눠 고치고, 이월 쌍은 다음 실행 맨 앞`() {
        bulkPairs(s, 2001, 2005, cnt = 0)
        sample()

        val r1 = safetyRun()
        assertEquals(2L, r1.long("repair_pairs"), "$r1")
        assertEquals(3L, r1.long("carry_out"), "$r1")
        assertEquals(0L, r1.long("carry_dropped"))
        val after1 = jdbcTemplate.driftLogs2().map { it.second }.toSet()

        val r2 = safetyRun()
        assertEquals(3L, r2.long("carry_in"), "$r2")
        assertEquals(2L, r2.long("repair_pairs"), "$r2")
        assertEquals(1L, r2.long("carry_out"), "$r2")
        // 이월 복구가 탐지보다 먼저 — r2가 고친 쌍은 r1이 대기열에 넣은 쌍이다
        val r2Fixed = jdbcTemplate.driftLogs2().map { it.second }.toSet() - after1
        assertTrue(r2Fixed.all { it in 2001L..2005L && it !in after1 }, "$r2Fixed")

        val r3 = safetyRun()
        assertEquals(1L, r3.long("carry_in"), "$r3")
        assertEquals(0L, r3.long("carry_out"), "$r3")
        repairedOnce(2001L..2005L)
    }

    @Test
    fun `20 — 대기열이 carry-cap을 넘으면 carry_dropped(지표) · 버린 쌍도 전체 커서가 결국 고친다`() {
        bulkPairs(s, 2101, 2108, cnt = 0)          // 8쌍 → 복구 2 · 대기열 3 · 버림 3
        sample()
        val dropped0 = meters.countOf(M_SAFE_CARRY_DROPPED)

        val r1 = safetyRun()
        assertEquals(2L, r1.long("repair_pairs"))
        assertEquals(3L, r1.long("carry_out"))
        assertEquals(3L, r1.long("carry_dropped"), "$r1")
        assertEquals(3.0, meters.countOf(M_SAFE_CARRY_DROPPED) - dropped0)

        safetyUntil { (2101L..2108L).all { cntOf(s, it) == 1 } }
        repairedOnce(2101L..2108L)
        assertTrue(jdbcTemplate.driftLogs2().all { it.first == SAFETY_APP })
    }

    private fun org.springframework.jdbc.core.JdbcTemplate.driftLogs2(): List<Pair<String, Long>> =
        query("SELECT source, user_id FROM counter_drift_log WHERE action = 'repaired' ORDER BY id") { rs, _ -> rs.getString(1) to rs.getLong(2) }
}
