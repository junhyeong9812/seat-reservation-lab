package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.support.deleteQuota
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals

/*
 * 테스트 16(앱 안 쪽) — 외부 앱 동등성의 기준 답. 같은 시나리오·같은 손 계산 정답을 reconciler-app의 ExternalAppEquivalenceTest가
 * 외부 앱으로 돌린다(source만 inc-ext-* · safety-ext). 두 쪽이 같은 정답과 같으면 서로 같다(DetectShapeEquivalenceTest와 같은 방식).
 *
 * 시나리오(직접 SQL — 외부 앱 쪽에는 업무 앱 경로가 없다. ⓒ는 업무 앱 문장을 흉내 내 updated_at = now()를 함께 쓴다):
 *   161 cnt 1 · 홀드 1 → cnt 0(below) · 162 cnt 1 · 홀드 1 → cnt 2(above) · 163 홀드 1 · 쿼터 행 삭제(missing — 안전망 몫) · 164 맞음
 * 정답: 증분 = [(161, below, 0, 1), (162, above, 2, 1)] · 안전망 = [(163, missing, null, 1)] · 164 기록 없음.
 */
object EquivalenceScenario {
    data class Outcome(val userId: Long, val kind: String, val cntBefore: Int?, val actual: Int, val action: String, val byIncrement: Boolean)

    val EXPECTED = listOf(
        Outcome(161, "below", 0, 1, "repaired", byIncrement = true),
        Outcome(162, "above", 2, 1, "repaired", byIncrement = true),
        Outcome(163, "missing", null, 1, "repaired", byIncrement = false),
    )

    fun mutationSql(stmt: Boolean, cnt: Int): String =
        "UPDATE user_hold_quota SET cnt = $cnt${if (stmt) ", updated_at = now()" else ""} WHERE schedule_id = ? AND user_id = ?"
}

abstract class InAppEquivalenceScenario(private val stmt: Boolean, private val incSource: String) : IncrementalTestBase() {

    @Test
    fun `16 앱 안 — 같은 DB 상태에서 증분이 161·162, 안전망이 163을 손 계산 정답대로 고친다`() {
        (161L..164L).forEach { u -> quota(s, u, 1); rawHold(s, u) }
        jdbcTemplate.update(EquivalenceScenario.mutationSql(stmt, 0), s, 161L)
        jdbcTemplate.update(EquivalenceScenario.mutationSql(stmt, 2), s, 162L)
        jdbcTemplate.deleteQuota(s, 163)

        tick(incSource)
        safetyUntil { cntOf(s, 163) == 1 }

        val got = jdbcTemplate.query(
            "SELECT user_id, kind, cnt_before, actual, action, source FROM counter_drift_log ORDER BY user_id",
        ) { rs, _ ->
            EquivalenceScenario.Outcome(rs.getLong(1), rs.getString(2), (rs.getObject(3) as Number?)?.toInt(), rs.getInt(4), rs.getString(5), rs.getString(6) == incSource) to rs.getString(6)
        }
        assertEquals(EquivalenceScenario.EXPECTED, got.map { it.first })
        assertEquals(listOf(incSource, incSource, SAFETY_APP), got.map { it.second })
        (161L..164L).forEach { u -> assertEquals(1, cntOf(s, u)) }
    }
}

@TestPropertySource(properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG])
class InAppEquivalenceTriggerTest : InAppEquivalenceScenario(stmt = false, incSource = SRC_TRG)

@TestPropertySource(properties = [A_ON, M_STMT, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_STMT])
class InAppEquivalenceStmtTest : InAppEquivalenceScenario(stmt = true, incSource = SRC_STMT)
