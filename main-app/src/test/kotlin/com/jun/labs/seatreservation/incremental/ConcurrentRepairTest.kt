package com.jun.labs.seatreservation.incremental

import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 테스트 6 — 증분 · 안전망 · 만료 A가 같은 쌍을 동시에(§3-2 · §4.2.3 불변식 표 2행 · §4.2.5 '만료 A와의 관계').
 * 결정적 동기화: 원시 연결이 그 쌍의 쿼터 행을 FOR UPDATE로 쥔 채 세 주체를 띄우고, 셋 모두 그 행 잠금을 기다리는 것을 pg_blocking_pids로 확인한 뒤 놓는다.
 * 어느 쪽이 먼저 잠금을 얻든: 끝 cnt = 실제 · repaired 정확히 1 · 나머지 기록은 false-positive · skipped 0(잠금 대기 15초).
 *
 * 쌍 (s, 61): 홀드 h1(만료 지남) + h2(유효) → 실제 2, cnt 0(below — 직접 SQL, 트리거가 updated_at 표시).
 * 만료 뒤 실제 = 1. A가 먼저면 가드(cnt ≥ 1) 실패 → A가 복구(expiry-repair). 증분·안전망이 먼저면 cnt := 2 → A는 정상 감소(기록 없음).
 */
@TestPropertySource(
    properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG, "seat.hold.counter-drift.reconcile.lock-timeout=15s"],
)
class ConcurrentRepairTest : IncrementalTestBase() {

    @Test
    fun `6 — 증분 · 안전망 · A 동시 — 끝 cnt = 실제, repaired 1, 나머지 false-positive, 덮어쓴 정답 0`() {
        rawHold(s, 61, expiresAt = NOW.minusSeconds(1))
        rawHold(s, 61, expiresAt = NOW.plusSeconds(300))
        quota(s, 61, 0)
        sample()

        rawConnection().use { lock ->
            lock.exec("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", s, 61L)
            val inc = pool.submit { tick(SRC_TRG) }
            val saf = pool.submit { safety.runOnce() }
            val exp = pool.submit<Int> { expireHolds.expire() }
            // 전제 수정 2026-10-20: 같은 행의 2·3번째 대기자는 pg_blocking_pids에 앞 대기자로 보인다(튜플 잠금 사슬) — 행 잠금 대기 3개로 동기화
            run {
                val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
                while (jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Int::class.java)!! < 3) {
                    check(System.nanoTime() < deadline) { "행 잠금 대기 3개를 20초 안에 못 봄" }; Thread.sleep(10)
                }
            }
            lock.rollback()                        // 잠금을 놓는다 — 셋이 차례로 잠근다
            inc.join(); saf.join(); exp.join()
        }

        assertEquals(1, actualOf(s, 61), "전제: 만료 뒤 실제 1")
        assertEquals(1, cntOf(s, 61), "끝 cnt = 실제")
        val logs = jdbcTemplate.logsOf(s, 61)
        assertEquals(1, logs.count { it.action == "repaired" }, "repaired 정확히 1: $logs")
        assertTrue(logs.filter { it.action != "repaired" }.all { it.action == "false-positive" }, "나머지는 false-positive: $logs")
        assertTrue(logs.none { it.action == "skipped" }, "$logs")
        assertTrue(logs.map { it.source }.toSet().all { it in setOf(SRC_TRG, SAFETY_APP, "expiry-repair") }, "$logs")
        // 덮어쓴 정답 0: false-positive 행은 잠근 뒤 cnt = 실제를 본 행이다
        // (A의 오탐은 감소 예정 수를 더한 비교라 제외 — PairRepair pendingDecrement)
        logs.filter { it.action == "false-positive" && it.source != "expiry-repair" }.forEach { assertEquals(it.cntBefore, it.actual, "false-positive인데 cnt ≠ 실제: $it") }
    }
}
