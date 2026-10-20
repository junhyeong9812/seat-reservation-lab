package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.support.countOf
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * 테스트 13 — 안전망 이어서 훑기(§4.2.5 순서 4 · 표 lap_*): 여러 번 돌리면 전체 커서가 모든 열린 회차의 쿼터 행 · 행 없음 범위를 한 번씩 지나
 * 바퀴가 끝나고 lap_seconds가 남는다. 바퀴 나이가 lap-warn을 넘으면 경고 기록(지표 lap_overdue · lap_age_s)이 남는다.
 * 전체 커서만 볼 수 있는 어긋남 = 홀드·예약 없이 cnt만 큰 쌍(최근 창은 홀드·예약 id로만 쌍을 찾는다 — §4.2.5 순서 3).
 */
@TestPropertySource(
    properties = [
        A_ON, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10,
        "$SAFE.budget=10ms", "seat.hold.counter-drift.reconcile.chunk-size=10",
        "seat.hold.counter-drift.reconcile.orphan-chunk-size=100000", "$SAFE.lap-warn=1s",
    ],
)
class SafetyLapTest : IncrementalTestBase() {

    @Test
    fun `13 — 여러 실행에 걸쳐 전체 커서가 세 회차의 쿼터 행을 모두 지나 바퀴가 끝난다 · lap_seconds · 전체 커서만 볼 어긋남 전부 복구`() {
        val schedules = listOf(s, newSchedule(SCHEDULE_STARTS_AT.plusSeconds(86_400)).id!!, newSchedule(SCHEDULE_STARTS_AT.plusSeconds(172_800)).id!!)
        schedules.forEachIndexed { i, sc ->
            bulkPairs(sc, 1, 200, cnt = 0, withHold = false)          // 맞는 쌍(cnt 0 · 실제 0) 200
            bulkPairs(sc, 1_001L + i * 100, 1_040L + i * 100, cnt = 1, withHold = false)   // 어긋남 above(1 vs 0) 40
        }
        val closed = newSchedule(NOW.minusSeconds(3_600)).id!!
        bulkPairs(closed, 1, 5, cnt = 7, withHold = false)          // 마감 회차 — 바퀴 대상이 아니다

        val lapRow = safetyLap(max = 1_000)

        val rows = jdbcTemplate.safetyRows()
        assertTrue(rows.size > 1, "예산 10ms · 청크 10이면 한 실행으로 바퀴를 끝낼 수 없다 — 이어서 훑어야 한다: ${rows.size}")
        assertNotNull(lapRow.longOrNull("lap_seconds"), "끝난 바퀴의 lap_seconds: $lapRow")
        assertTrue(lapRow.long("lap_seconds") >= 0)
        assertTrue(lapRow.long("lap_no") >= 1, "$lapRow")
        schedules.forEachIndexed { i, sc ->
            (1_001L + i * 100..1_040L + i * 100).forEach { u ->
                assertEquals(0, cntOf(sc, u), "회차 $sc 사용자 $u — 바퀴가 지나갔는데 안 고쳐졌다")
                assertEquals(listOf(SAFETY_APP), jdbcTemplate.logsOf(sc, u).map { it.source })
            }
        }
        assertEquals(7, cntOf(closed, 1), "마감 회차는 고치지 않는다")
        // 전체 커서 위치는 바퀴 동안 회차 id 순으로만 나아간다(되감기 없음 — 바퀴가 끝난 행에서만 처음으로)
        val lapRuns = rows.takeWhile { !it.bool("lap_completed") }.mapNotNull { it.longOrNull("full_schedule") }
        assertEquals(lapRuns, lapRuns.sorted(), "전체 커서가 바퀴 중에 되감겼다: $lapRuns")
        assertEquals(1, rows.count { it.bool("lap_completed") }, "바퀴는 한 번 끝났다")
        val next = safetyRun()
        assertTrue(next.long("lap_no") > lapRow.long("lap_no") || !next.bool("lap_completed"), "바퀴가 끝나면 커서를 처음으로 돌려 다음 바퀴: $next")
    }

    @Test
    fun `13 — 진행 중 바퀴 나이가 lap-warn(1s)을 넘으면 lap_overdue 지표 · lap_age_s`() {
        bulkPairs(s, 1, 2_000, cnt = 0, withHold = false)           // 200청크 — 10ms로는 몇 실행에 못 끝난다
        val overdue0 = meters.countOf(M_SAFE_LAP_OVERDUE)
        val r1 = safetyRun()
        if (r1.bool("lap_completed")) safetyRun()                   // 앞 테스트의 바퀴 끝이 걸렸으면 새 바퀴를 시작시킨다
        jdbcTemplate.awaitDbTimeAfter(dbNow().plusMillis(1_500))

        val r = safetyRun()

        assertTrue(!r.bool("lap_completed"), "전제: 바퀴 진행 중: $r")
        assertTrue(r.long("lap_age_s") >= 1, "진행 중 바퀴 나이: $r")
        assertTrue(meters.countOf(M_SAFE_LAP_OVERDUE) - overdue0 >= 1, "lap-warn 초과 경고 지표(1회)")
    }
}
