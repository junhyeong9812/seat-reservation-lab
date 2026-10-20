package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.service.HoldSeatCommand
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 테스트 10 — ⓑ 직접 SQL: 앱을 거치지 않은 `cnt − 1`·`cnt + 1`을 ⓑ 증분이 잡는다(inc-app-trg-*). ⓒ·ⓐ 증분은 잡지 않는다(안전망이 잡는다) — §2.3 표 2행.
 * 준비: 맞는 쌍을 만든 뒤 '창을 비운다'(틱 → 1초 넘게 기다림 → 틱: 하한이 준비 시각 뒤로 간다 · ⓐ는 집합을 비운다). 그 뒤 직접 SQL.
 */
abstract class DirectSqlScenario(private val source: String, private val incrementSees: Boolean) : IncrementalTestBase() {

    @Test
    fun `10 — 앱 밖 직접 UPDATE(cnt − 1 · cnt + 1)`() {
        quota(s, 1001, 1); rawHold(s, 1001)
        quota(s, 1002, 1); rawHold(s, 1002)
        tick(source)
        jdbcTemplate.awaitDbTimeAfter(dbNow().plusMillis(1_300))
        tick(source)

        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = cnt - 1 WHERE schedule_id = ? AND user_id = ?", s, 1001L)
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ?", s, 1002L)
        tick(source)

        if (incrementSees) {
            assertEquals(listOf("below" to 0, "above" to 2), listOf(1001L, 1002L).map { u -> jdbcTemplate.logsOf(s, u).single().let { it.kind to it.cntBefore!! } })
            listOf(1001L, 1002L).forEach { u ->
                val l = jdbcTemplate.logsOf(s, u).single()
                assertEquals(source, l.source); assertEquals("repaired", l.action); assertEquals(1, cntOf(s, u))
            }
        } else {
            assertTrue(jdbcTemplate.logsOf(s, 1001).isEmpty() && jdbcTemplate.logsOf(s, 1002).isEmpty(), "이 방식의 증분은 직접 SQL을 보지 못해야 한다(§2.3)")
            assertEquals(0, cntOf(s, 1001)); assertEquals(2, cntOf(s, 1002))
            safetyUntil { cntOf(s, 1001) == 1 && cntOf(s, 1002) == 1 }
            listOf(1001L, 1002L).forEach { u -> assertEquals(listOf(SAFETY_APP), jdbcTemplate.logsOf(s, u).map { it.source }) }
        }
    }
}

@TestPropertySource(properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG])
class DirectSqlTriggerTest : DirectSqlScenario(SRC_TRG, incrementSees = true)

@TestPropertySource(properties = [A_ON, M_STMT, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_STMT])
class DirectSqlStmtTest : DirectSqlScenario(SRC_STMT, incrementSees = false)

@TestPropertySource(properties = [A_ON, M_MEM, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10])
class DirectSqlMemoryTest : DirectSqlScenario(SRC_MEM, incrementSees = false)

/*
 * 테스트 11 — ⓒ 문장 범위(§4.2.2 ⓒ 1~5): method=stmt면 선점·만료·복구 뒤 updated_at이 그 트랜잭션 시각(문장 전후 DB 시각 사이)이다.
 * method ≠ stmt(열이 있는 ⓐ 셀 — 조합표 'ⓐ + 문장 위치')면 그 문장들이 updated_at을 바꾸지 않는다.
 * 잠금 대기 중인 선점 문장(pg_stat_activity)에 updated_at이 있는지도 본다 — 꺼짐이면 문자열이 그대로(§4.2 공통 원칙 ②).
 */
abstract class StmtScopeScenario(private val stmt: Boolean) : IncrementalTestBase() {

    private fun assertMarked(u: Long, before: Instant, after: Instant, prev: Instant, what: String) {
        val at = jdbcTemplate.updatedAtOf(s, u)!!
        if (stmt) {
            assertTrue(at in before..after, "$what 뒤 updated_at = 그 트랜잭션 시각이어야 한다: $at ∉ [$before, $after]")
        } else {
            assertEquals(prev, at, "$what 이 updated_at을 바꿨다(method ≠ stmt)")
        }
    }

    @Test
    fun `11 — 선점(upsert DO UPDATE) · 만료(가드 감소) · 복구(PairRepair UPDATE)`() {
        appHold(1101)                              // INSERT 갈래 — 기본값
        var prev = jdbcTemplate.updatedAtOf(s, 1101)!!

        var b = dbNow(); appHold(1101); var a = dbNow()
        assertEquals(2, cntOf(s, 1101))
        assertMarked(1101, b, a, prev, "선점")
        prev = jdbcTemplate.updatedAtOf(s, 1101)!!

        clock.advance(Duration.ofMinutes(6))
        b = dbNow(); assertEquals(2, expireHolds.expire()); a = dbNow()
        assertEquals(0, cntOf(s, 1101))
        assertMarked(1101, b, a, prev, "만료 감소")
        prev = jdbcTemplate.updatedAtOf(s, 1101)!!

        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 2 WHERE schedule_id = ? AND user_id = ?", s, 1101L)   // 트리거 없는 셀 — 표시 없음
        assertEquals(prev, jdbcTemplate.updatedAtOf(s, 1101), "전제: 직접 SQL은 표시하지 않는다")
        b = dbNow(); safetyUntil { cntOf(s, 1101) == 0 }; a = dbNow()
        assertEquals("repaired", jdbcTemplate.logsOf(s, 1101).last().action)
        assertMarked(1101, b, a, prev, "복구")
    }

    @Test
    fun `11 — 잠금 대기 중인 선점 upsert 문장에 updated_at = now()가 있는가(stmt만)`() {
        appHold(1102)
        rawConnection().use { lock ->
            lock.exec("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", s, 1102L)
            val running = pool.submit<Any> { holdSeat.hold(HoldSeatCommand(s, freeSeat(), 1102)) }
            val w = jdbcTemplate.awaitBlockedBy(lock.pid()).single()
            lock.rollback()
            running.join()
            val q = normalizedSql(w.query)
            assertTrue("user_hold_quota" in q && "cnt+1" in q, "대기 문장은 선점 쿼터 문장이어야 한다: ${w.query}")
            assertEquals(stmt, "updated_at=now()" in q, "선점 문장의 updated_at 표시(stmt=$stmt): ${w.query}")
        }
    }
}

@TestPropertySource(properties = [A_ON, M_STMT, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_STMT])
class StmtScopeStmtTest : StmtScopeScenario(stmt = true)

@TestPropertySource(properties = [A_ON, M_MEM, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_MEM_WITH_V9])
class StmtScopeMemoryWithV9Test : StmtScopeScenario(stmt = false)

/** counter 방식의 `cnt + 1` 문장(§4.2.2 ⓒ 1번) — 준비 INSERT는 기본값, 두 번째 선점의 UPDATE가 표시한다. */
@TestPropertySource(properties = [A_ON, M_STMT, INC_ON, INC_1H, FW_STMT, "seat.hold.limit-strategy=counter"])
class StmtScopeCounterStrategyTest : IncrementalTestBase() {
    @Test
    fun `11 — counter 선점 UPDATE cnt + 1 도 updated_at = 그 트랜잭션 시각`() {
        appHold(1111)
        val b = dbNow(); appHold(1111); val a = dbNow()
        assertEquals(2, cntOf(s, 1111))
        val at = jdbcTemplate.updatedAtOf(s, 1111)!!
        assertTrue(at in b..a, "$at ∉ [$b, $a]")
    }
}
