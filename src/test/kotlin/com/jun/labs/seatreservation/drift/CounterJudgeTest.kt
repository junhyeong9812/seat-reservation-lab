package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.domain.ProductSchedule
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.loadtest.LoadtestDataService
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.insertHoldRow
import com.jun.labs.seatreservation.support.insertQuota
import com.jun.labs.seatreservation.support.insertReservationRow
import com.jun.labs.seatreservation.support.insertSeatRow
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import kotlin.test.assertEquals

/**
 * ADR-009 판정기(LoadtestDataService consistency) — 명세 §10 '마감된 회차는 판정기 v_counter_mismatch에서 제외, 제외 회차·쌍 수 기록' ·
 * contract.md: counter 계열이면 v_counter_mismatch는 열린 회차(starts_at > 앱 Clock now)만 센다, 새 필드 counter_excluded_schedules·counter_excluded_pairs.
 * 기본 매수 방식(counter-upsert-precheck). 데이터는 SQL로 직접 넣는다(판정기 단위 시험).
 */
@ActiveProfiles("loadtest")
class CounterJudgeTest : IntegrationTest() {

    @Autowired lateinit var loadtest: LoadtestDataService

    private fun judge(): Map<String, Any?> = loadtest.consistency(20)
    private fun Map<String, Any?>.long(key: String): Long = (this[key] as Number?)?.toLong() ?: error("판정기 응답에 $key 없음: $keys")

    private var seatNo = 0

    /** 쌍 하나: 홀드 [holds]개 + 확정 [confirmed]개를 넣고 쿼터 행을 [cnt]로(null = 행 없음). */
    private fun pair(scheduleId: Long, userId: Long, holds: Int, confirmed: Int, cnt: Int?) {
        repeat(holds) {
            val st = jdbcTemplate.insertSeatRow(scheduleId, ++seatNo, SeatStatus.HELD)
            jdbcTemplate.insertHoldRow(st, scheduleId, userId, NOW, NOW.plus(Duration.ofDays(30))) // 만료 판정과 섞이지 않게 먼 만료
        }
        repeat(confirmed) {
            val st = jdbcTemplate.insertSeatRow(scheduleId, ++seatNo, SeatStatus.RESERVED)
            jdbcTemplate.insertReservationRow(scheduleId, st, userId)
        }
        if (cnt != null) jdbcTemplate.insertQuota(scheduleId, userId, cnt)
    }

    private fun closedSchedule(): Long =
        productScheduleRepository.save(ProductSchedule(productId = schedule.productId, startsAt = NOW.minusSeconds(3_600))).id!!

    @Test
    fun `열린 회차만 있고 맞으면 — v_counter_mismatch 0, 제외 0·0`() {
        pair(schedule.id!!, 1, holds = 1, confirmed = 1, cnt = 2)
        val r = judge()
        assertEquals(0L, r.long("v_counter_mismatch"))
        assertEquals(0L, r.long("counter_excluded_schedules"))
        assertEquals(0L, r.long("counter_excluded_pairs"))
    }

    @Test
    fun `열린 회차의 어긋남은 그대로 잡는다 — 행 없음·작음·큼 각 1`() {
        val s = schedule.id!!
        pair(s, 1, holds = 1, confirmed = 0, cnt = null)
        pair(s, 2, holds = 1, confirmed = 1, cnt = 1)
        pair(s, 3, holds = 1, confirmed = 0, cnt = 2)
        pair(s, 4, holds = 1, confirmed = 0, cnt = 1) // 맞음
        assertEquals(3L, judge().long("v_counter_mismatch"))
    }

    @Test
    fun `마감 회차(앱 Clock 기준 시작 지남)의 어긋남은 빼고 제외 회차·쌍 수를 채운다`() {
        pair(schedule.id!!, 1, holds = 1, confirmed = 0, cnt = 2)       // 열린 회차 어긋남 1
        val closed = closedSchedule()
        pair(closed, 11, holds = 1, confirmed = 1, cnt = 3)             // 마감 회차 — 행·실제 양쪽에 있는 쌍 2개(둘 다 어긋남)
        pair(closed, 12, holds = 1, confirmed = 0, cnt = 0)

        val r = judge()

        assertEquals(1L, r.long("v_counter_mismatch"), "열린 회차만 센다")
        assertEquals(1L, r.long("counter_excluded_schedules"))
        assertEquals(2L, r.long("counter_excluded_pairs"))
    }

    @Test
    fun `시각의 출처는 앱 Clock — 같은 데이터에서 시계를 시작 뒤로 넘기면 그 회차가 제외로 옮겨 간다`() {
        val s = schedule.id!!
        pair(s, 1, holds = 1, confirmed = 0, cnt = 2)
        pair(s, 2, holds = 0, confirmed = 1, cnt = 0) // 행·실제 양쪽에 있게 — 제외 쌍 수의 정의(쿼터 행 기준/합집합/어긋난 쌍)와 무관하게 2
        assertEquals(2L, judge().long("v_counter_mismatch"), "시작 전 — 센다")

        clock.set(schedule.startsAt)  // now >= starts_at → 마감(열린 회차 = starts_at > now)
        val r = judge()

        assertEquals(0L, r.long("v_counter_mismatch"), "시작 뒤 — 뺀다")
        assertEquals(1L, r.long("counter_excluded_schedules"))
        assertEquals(2L, r.long("counter_excluded_pairs"))
    }
}
