package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.loadtest.LoadtestDataService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.sql.Timestamp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 테스트 22 — 시드 초기화(§4.2.5 '커서 의미' · rv41 Opus L10): 안전망이 돈 뒤 TRUNCATE … RESTART IDENTITY(LoadtestDataService.reset) + 다시 넣기를 하면
 * 다음 표본·실행이 reset = true를 남기고 커서 · 표본 고리 · 진행 위치 · 이월 대기열이 비어, 새 데이터의 어긋남을 최근 창이 잡는다
 * (옛 id 표본으로 범위를 잘못 고르지 않는다 — 건너뜀 0).
 * 대기열이 비었다는 관측: 초기화 전 대기열의 (옛 회차, 사용자)가 초기화 뒤 복구·기록되지 않는다 · carry_in = 0.
 */
@ActiveProfiles("loadtest")
@TestPropertySource(properties = [A_ON, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10, "$SAFE.repair-cap=1"])
class SeedResetTest : IncrementalTestBase() {

    @Autowired lateinit var loadtestData: LoadtestDataService

    @Test
    fun `22 — 초기화 뒤 첫 실행 reset = true · carry_in 0 · 건너뜀 0 · 새 데이터의 어긋남은 최근 창이 잡는다`() {
        // 초기화 전: 어긋남 3 → 이월 2
        bulkPairs(s, 100, 160, cnt = 1)            // 맞는 쌍 61 — 옛 id가 새 id보다 커지게
        bulkPairs(s, 1, 3, cnt = 0)
        sample()
        val pre = safetyRun()
        assertEquals(2L, pre.long("carry_out"), "전제: 대기열 2: $pre")
        val oldSchedule = s
        val oldCursor = pre.long("recent_hold_cursor")

        loadtestData.reset(1, 20)                  // TRUNCATE … RESTART IDENTITY — id가 1부터 다시
        val newSchedule = jdbcTemplate.queryForObject("SELECT id FROM product_schedule", Long::class.java)!!
        val seats = jdbcTemplate.queryForList("SELECT id FROM product_seat WHERE schedule_id = ? ORDER BY id LIMIT 3", Long::class.java, newSchedule)
        seats.forEachIndexed { i, seat ->
            jdbcTemplate.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", seat)
            jdbcTemplate.update(
                "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                seat, newSchedule, 2201L + i, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(300)),
            )
            quota(newSchedule, 2201L + i, 0)       // below
        }
        val newHead = jdbcTemplate.queryForObject("SELECT max(id) FROM seat_hold", Long::class.java)!!
        assertTrue(newHead < oldCursor, "전제: 새 id가 옛 커서보다 작다(최대 id 비교만으로는 초기화를 못 안다): new=$newHead old=$oldCursor")
        jdbcTemplate.update("DELETE FROM counter_drift_log")

        sample()
        val r = safetyRun()

        assertTrue(r.bool("reset"), "초기화를 감지해 reset = true: $r")
        assertEquals(0L, r.long("carry_in"), "대기열이 비어야 한다: $r")
        assertEquals(0L, r.long("recent_skip_ids"), "옛 표본으로 건너뜀 범위를 고르지 않는다: $r")
        assertTrue(r.long("recent_hold_cursor") <= newHead, "커서는 0부터 다시: $r")
        // 옛 대기열 = (옛 회차, 사용자 1~3 중 2) — 초기화 뒤 회차 id가 같을 수 있어(RESTART IDENTITY) 사용자로 가른다
        assertTrue(jdbcTemplate.driftLogs2().none { it.second in 1L..3L }, "옛 대기열의 쌍을 다뤘다(대기열을 비우지 않음): ${jdbcTemplate.driftLogs2()} 옛 회차=$oldSchedule")

        safetyUntil(max = 10) { (2201L..2203L).all { cntOf(newSchedule, it) == 1 } }
        (2201L..2203L).forEach { u -> assertEquals(listOf(SAFETY_APP), jdbcTemplate.logsOf(newSchedule, u).map { it.source }) }
        assertTrue(jdbcTemplate.safetyRows().drop(jdbcTemplate.safetyRows().indexOfFirst { it.bool("reset") } + 1).none { it.bool("reset") }, "reset은 한 번만")
    }

    private fun org.springframework.jdbc.core.JdbcTemplate.driftLogs2(): List<Pair<Long, Long>> =
        query("SELECT schedule_id, user_id FROM counter_drift_log") { rs, _ -> rs.getLong(1) to rs.getLong(2) }
}
