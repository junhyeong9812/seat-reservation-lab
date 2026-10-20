package com.jun.labs.seatreservation.incremental

import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertTrue

/*
 * §4.2.6 source 꼬리표(보조 — 테스트 계약 15·16의 source 전제): 스케줄러가 도는 틱의 source는 interval에서 만든다(정수 초면 Ns, 아니면 Nms) ·
 * 안전망은 safety-app · 후보가 0이어도 틱마다 counter_reconcile_run 1행(§4.2.3 순서 5 — 틱 결손을 하네스가 볼 수 있게).
 * 이 클래스만 실제 스케줄러를 짧은 주기로 돌린다.
 */
@TestPropertySource(
    properties = [A_ON, M_TRG, INC_ON, "$INC.interval=200ms", SAFE_ON, "$SAFE.interval=300ms", SAMPLE_1H, FW_TRG],
)
class SchedulerSourceTest : IncrementalTestBase() {

    @Test
    fun `스케줄러 틱 source = inc-app-trg-200ms · 안전망 = safety-app · 빈 틱도 기록`() {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            val inc = jdbcTemplate.queryForObject("SELECT count(*) FROM counter_reconcile_run WHERE source = 'inc-app-trg-200ms'", Int::class.java)!!
            val saf = jdbcTemplate.queryForObject("SELECT count(*) FROM counter_reconcile_run WHERE source = 'safety-app'", Int::class.java)!!
            if (inc >= 3 && saf >= 1) break
            check(System.currentTimeMillis() < deadline) {
                "스케줄러 기록이 없다: ${jdbcTemplate.queryForList("SELECT DISTINCT source FROM counter_reconcile_run", String::class.java)}"
            }
            Thread.sleep(100)
        }
        assertTrue(
            jdbcTemplate.queryForList("SELECT DISTINCT source FROM counter_reconcile_run", String::class.java).all { it in setOf("inc-app-trg-200ms", "safety-app") },
        )
    }
}
