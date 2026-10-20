package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.support.deleteQuota
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 테스트 7 — 행 없음(§3-3 · §4.2.3 불변식 표 3행): 증분은 쿼터 행이 있는 쌍만 본다. 지운 쌍은 안전망(최근 창 (가) 또는 전체 ②)이 missing으로 고친다.
 * upsert INSERT로 행이 다시 생긴 쌍(확정 예약이 있는 쌍 — cnt 1 · 실제 홀드 1 + CONFIRMED 1 = 2)은 증분이 below로 고친다(ⓐ 등록 · ⓑⓒ 기본값).
 */
abstract class OrphanRowScenario(private val source: String) : IncrementalTestBase() {

    @Test
    fun `7 — 쿼터 행을 지운 쌍은 증분 기록에 나오지 않고, 안전망이 missing으로 고친다`() {
        appHold(701)
        jdbcTemplate.deleteQuota(s, 701)

        tick(source)
        assertTrue(jdbcTemplate.logsOf(s, 701).isEmpty(), "증분이 행 없음 쌍을 다뤘다: ${jdbcTemplate.logsOf(s, 701)}")
        assertEquals(null, cntOf(s, 701))

        safetyUntil { cntOf(s, 701) == 1 }

        val l = jdbcTemplate.logsOf(s, 701).single()
        assertEquals(SAFETY_APP, l.source)
        assertEquals("missing", l.kind)
        assertEquals(null, l.cntBefore)
        assertEquals("repaired", l.action)
    }

    @Test
    fun `7 — upsert INSERT로 다시 생긴 행(확정 예약이 있는 쌍)은 증분이 실제 값(홀드 + CONFIRMED)으로 고친다`() {
        appConfirmed(702)                          // 확정 1(홀드 0)
        jdbcTemplate.deleteQuota(s, 702)
        appHold(702)                              // upsert INSERT 갈래 → cnt 1, 실제 2

        tick(source)

        assertEquals(2, cntOf(s, 702))
        val l = jdbcTemplate.logsOf(s, 702).single()
        assertEquals(source, l.source)
        assertEquals("below", l.kind)
        assertEquals(1, l.cntBefore)
        assertEquals(2, l.actual)
        assertEquals("repaired", l.action)
    }
}

@TestPropertySource(properties = [A_ON, M_MEM, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10])
class OrphanRowMemoryTest : OrphanRowScenario(SRC_MEM)

@TestPropertySource(properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG])
class OrphanRowTriggerTest : OrphanRowScenario(SRC_TRG)

@TestPropertySource(properties = [A_ON, M_STMT, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_STMT])
class OrphanRowStmtTest : OrphanRowScenario(SRC_STMT)
