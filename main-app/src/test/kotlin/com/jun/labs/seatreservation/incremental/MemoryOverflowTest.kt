package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.support.countOf
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 테스트 4·23(memory_overflow) — ⓐ 상한 `memory-cap`(§4.2.2 '상한·넘침'): 크기가 상한 이상이면 넣지 않고
 * ① 지표 memory.overflow ② WARN ③ 안전망 앞당김(다음 실행을 바로 — 10초에 1번까지) ④ counter_inc_gap memory_overflow(10초 요약).
 * 앞당김은 실제 스케줄러가 돌려야 보이므로 안전망을 켜 두고(주기 1h — 첫 정기 실행은 한 주기 뒤, 가정 S5) expedited = true 행을 기다린다.
 * 이 클래스는 넘침을 한 번만 만든다(앞당김 10초 제한이 다른 테스트와 섞이지 않게).
 */
@TestPropertySource(properties = [A_ON, M_MEM, INC_ON, INC_1H, "$INC.memory-cap=2", SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10])
class MemoryOverflowTest : MemoryScenarioBase() {

    private fun await(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "${timeoutMs}ms 안에 $what 가 나타나지 않았다" }
            Thread.sleep(50)
        }
    }

    @Test
    fun `4·23 — 상한을 넘긴 등록 — overflow 지표 · 안전망 앞당김 실행 · counter_inc_gap memory_overflow 행(첫 쌍) · 넘친 쌍은 안전망이 고친다`() {
        drain()
        quota(s, 403, 1)                          // 넘칠 쌍의 어긋남(직접 SQL) — 선점 뒤 cnt 2 · 실제 1(1인 한도 2 — 전제 수정 2026-10-20)
        val overflow0 = meters.countOf(M_INC_MEM_OVERFLOW)
        val expedited0 = meters.countOf(M_SAFE_EXPEDITED)

        appHold(401)
        appHold(402)                              // 집합 = 2 = 상한
        appHold(403)                              // 넘침

        assertTrue(meters.countOf(M_INC_MEM_OVERFLOW) - overflow0 >= 1, "memory.overflow 지표")
        await("앞당김 안전망 실행(counter_safety_run.expedited = true)") { jdbcTemplate.safetyRows().any { it.bool("expedited") } }
        assertTrue(meters.countOf(M_SAFE_EXPEDITED) - expedited0 >= 1, "safety.expedited 지표")

        tick(SRC_MEM)                             // 집합의 401·402만 — 403은 증분 후보가 아니다
        assertTrue(jdbcTemplate.logsOf(s, 403).none { it.source.startsWith("inc-") }, "넘친 쌍을 증분이 고쳤다: ${jdbcTemplate.logsOf(s, 403)}")
        // memory_overflow 행은 '10초 요약마다' 틱 끝 기록으로 나간다(§4.2.2 ④ · §4.2.3 순서 5) — 요약 구간이 닫힐 때까지 틱을 이어 부른다
        await("counter_inc_gap memory_overflow 행", timeoutMs = 25_000) {
            tick(SRC_MEM)
            jdbcTemplate.gapRows("memory_overflow").isNotEmpty()
        }
        val g = jdbcTemplate.gapRows("memory_overflow").first()
        assertEquals(s, g.longOrNull("schedule_id"), "첫 넘친 쌍: $g")
        assertEquals(403L, g.longOrNull("user_id"), "첫 넘친 쌍: $g")
        assertTrue(g.long("pairs") >= 1, "$g")
        assertTrue(g.instant("from_at") != null && g.instant("to_at") != null, "overflow = 10초 요약 구간: $g")
        assertTrue(meters.countOf(M_INC_GAP, "kind" to "memory_overflow") >= 1)

        safetyUntil { cntOf(s, 403) == actualOf(s, 403) }

        assertEquals(1, cntOf(s, 403))
        assertEquals(listOf(SAFETY_APP), jdbcTemplate.logsOf(s, 403).filter { it.action == "repaired" }.map { it.source })
        listOf(401L, 402L).forEach { u -> assertEquals(1, cntOf(s, u)); assertTrue(jdbcTemplate.logsOf(s, u).none { it.action == "repaired" }) }
    }
}
