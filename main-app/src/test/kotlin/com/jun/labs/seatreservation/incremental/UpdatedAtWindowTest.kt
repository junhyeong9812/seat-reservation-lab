package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.support.countOf
import com.jun.labs.seatreservation.support.hikariBorrows
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * 테스트 1·2(ADR-012 §4.2.10) — ⓑⓒ 창(watermark) 규칙: §4.2.3 '문장 1(now · min(xact_start)) → 문장 2(updated_at >= L)' ·
 * L_{k+1} = max(min(t, o) − 1초, t − max-overlap) · 보장 밖(창 상한)은 counter_inc_gap window_clamped + 안전망(사용자 결정 2026-10-20 D1).
 *
 * 쿼터를 바꾸는 '긴 트랜잭션'은 원시 연결로 만든다. ⓑ는 직접 UPDATE를 트리거가 표시하고, ⓒ는 앱 문장과 같은 모양
 * (`cnt = cnt + 1, updated_at = now()` — §4.2.2 ⓒ 1번)을 원시 연결에서 보낸다(창 규칙만 보는 테스트 — 문장 범위는 테스트 11).
 * 어긋남 = 홀드 1 · cnt 1 인 쌍에 cnt + 1(above · 2 vs 1).
 */
abstract class UpdatedAtWindowScenario(private val method: String, private val source: String) : IncrementalTestBase() {

    private fun bumpSql() =
        if (method == "stmt") "UPDATE user_hold_quota SET cnt = cnt + 1, updated_at = now() WHERE schedule_id = ? AND user_id = ?"
        else "UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ?"

    private fun seedCorrect(userId: Long) {
        quota(s, userId, 1)
        rawHold(s, userId)
    }

    private fun assertRepairedByIncrement(userId: Long) {
        val logs = jdbcTemplate.logsOf(s, userId)
        assertEquals(1, logs.size, "쌍 $userId 기록 1행이어야 한다: $logs")
        val l = logs.single()
        assertEquals(source, l.source, "증분 source로 고쳐야 한다: $l")
        assertEquals("above", l.kind)
        assertEquals(2, l.cntBefore)
        assertEquals(1, l.actual)
        assertEquals("repaired", l.action)
        assertEquals(1, cntOf(s, userId))
    }

    @Test
    fun `1 — 틱 k 읽기 전에 시작해 여러 틱에 걸쳐 열어 둔 뒤 커밋한 변경을 커밋 뒤 첫 틱이 잡는다(max-overlap 안)`() {
        seedCorrect(11)
        rawConnection().use { c ->
            c.exec(bumpSql(), s, 11L)              // 트랜잭션 시작 = 이 문장 — updated_at = now() = 시작 시각
            repeat(3) { tick(source) }            // 틱 k, k+1, k+2 — 미커밋이라 보이지 않는다
            assertTrue(jdbcTemplate.logsOf(s, 11).isEmpty(), "미커밋 변경을 고쳤다(또는 맞는 값을 덮었다)")
            assertEquals(1, cntOf(s, 11), "미커밋 변경은 보이지 않아야 한다")
            c.commit()
        }

        tick(source)

        assertRepairedByIncrement(11)
        assertTrue(jdbcTemplate.gapRows("window_clamped").isEmpty(), "60초 안의 트랜잭션은 보장 안 — 창 상한 기록이 없어야 한다")
    }

    @Test
    fun `2 — 겹침 창 — 앞 틱 읽기 전에 시작한 변경과 뒤에 시작한 변경이 틱 사이에 커밋되면 둘 다 다음 틱 후보다`() {
        seedCorrect(21)
        seedCorrect(22)
        rawConnection().use { early ->
            early.exec(bumpSql(), s, 21L)          // 틱 k 읽기 전 시작
            tick(source)                          // 틱 k
            rawConnection().use { late ->
                late.exec(bumpSql(), s, 22L)       // 틱 k 뒤 시작
                late.commit()
            }
            early.commit()
        }

        tick(source)                              // 틱 k+1

        assertRepairedByIncrement(21)
        assertRepairedByIncrement(22)
    }

    @Test
    fun `증분 틱 기록 — 후보 0이어도 1행 · orphan 0 · pairs_scanned = 후보 지표 증가분 · borrows = Hikari 빌림 증가분 = 빌림 지표 증가분`() {
        // 앞 테스트의 쌍이 창에 남지 않게: 틱 하나로 하한을 지금 − 1초 쪽으로 당긴 뒤 1초 넘게 기다린다(열린 트랜잭션 없음 → o 없음)
        tick(source)
        jdbcTemplate.awaitDbTimeAfter(dbNow().plusMillis(1_300))
        tick(source)
        val runs0 = jdbcTemplate.incRuns(source).size

        tick(source)                              // 후보 0
        val empty = jdbcTemplate.lastRunOf(source)
        assertEquals(runs0 + 1, jdbcTemplate.incRuns(source).size, "후보가 0이어도 틱마다 기록 1행(§4.2.3 순서 5)")
        assertEquals(0L, empty.long("pairs_scanned"), "$empty")
        assertEquals(0L, empty.long("found"))

        seedCorrect(31)
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 3${if (method == "stmt") ", updated_at = now()" else ""} WHERE schedule_id = ? AND user_id = ?", s, 31L)
        val hikari0 = meters.hikariBorrows()
        val borrows0 = meters.countOf(M_INC_BORROWS)
        val cand0 = meters.countOf(M_INC_CANDIDATES)
        val ticks0 = meters.countOf(M_INC_TICK)

        tick(source)
        // 전제 수정 2026-10-20: 아래 lastRunOf 조회도 커넥션을 빌리므로 빌림은 틱 직후에 읽는다
        val hikariAfter = meters.hikariBorrows()
        val borrowsAfter = meters.countOf(M_INC_BORROWS)

        val run = jdbcTemplate.lastRunOf(source)
        assertEquals(source, run["source"])
        assertTrue(run.long("pairs_scanned") >= 1, "$run")
        assertEquals(run.long("pairs_scanned").toDouble(), meters.countOf(M_INC_CANDIDATES) - cand0, "pairs_scanned = 후보 쌍 수(지표)")
        assertEquals(0L, run.long("orphan_chunks"), "증분 틱의 orphan_chunks = 0(§4.2.6)")
        assertEquals(0L, run.long("orphan_ms_total"))
        assertEquals(1L, run.long("found"))
        assertEquals(1L, run.long("repaired"))
        assertTrue(run.long("chunks") >= 1)
        assertEquals(1L, run.long("schedules"), "후보의 열린 회차 수")
        val hikari = hikariAfter - hikari0
        assertEquals(hikari, run.long("borrows").toDouble(), "기록 borrows = 이 틱의 커넥션 빌림(기록 INSERT 포함)")
        assertEquals(hikari, borrowsAfter - borrows0, "빌림 지표 증가분")
        assertEquals(1.0, meters.countOf(M_INC_TICK) - ticks0, "틱 타이머 1회")
    }

    @Test
    fun `열린 회차만 — 마감 회차의 후보는 고치지 않고 skipped_schedules·skipped_pairs로 센다`() {
        val closed = newSchedule(NOW.minusSeconds(3_600)).id!!
        quota(closed, 41, 5)                       // 마감 회차 어긋남(실제 0)
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 6${if (method == "stmt") ", updated_at = now()" else ""} WHERE schedule_id = ? AND user_id = ?", closed, 41L)

        tick(source)

        assertEquals(6, cntOf(closed, 41), "마감 회차는 고치지 않는다")
        assertTrue(jdbcTemplate.logsOf(closed, 41).isEmpty())
        val run = jdbcTemplate.lastRunOf(source)
        assertTrue(run.long("skipped_schedules") >= 1, "$run")
        assertTrue(run.long("skipped_pairs") >= 1, "$run")
    }
}


@TestPropertySource(properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG])
class UpdatedAtWindowTriggerTest : UpdatedAtWindowScenario("trigger", SRC_TRG)

@TestPropertySource(properties = [A_ON, M_STMT, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_STMT])
class UpdatedAtWindowStmtTest : UpdatedAtWindowScenario("stmt", SRC_STMT)

/*
 * 보장 밖 반례(테스트 1 후반 · 테스트 2 후반) — max-overlap = 2초로 줄여 '창 상한에 걸린 틱'을 만든다.
 * 상한 판정은 L = t − max-overlap이 min(t, o) − 1초보다 뒤일 때이므로, 트랜잭션 나이가 max-overlap + 1.5초를 넘긴 뒤 틱을 부른다
 * (어느 쪽 경계 해석이든 상한에 걸린다).
 */
abstract class WindowClampScenario(private val method: String, private val source: String) : IncrementalTestBase() {

    private val maxOverlap = Duration.ofSeconds(2)

    private fun bumpSql() =
        if (method == "stmt") "UPDATE user_hold_quota SET cnt = cnt + 1, updated_at = now() WHERE schedule_id = ? AND user_id = ?"
        else "UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ?"

    @Test
    fun `1 보장 밖 — max-overlap보다 오래 열어 둔 변경 — window_clamped 행(구간 o 이상 하한 미만에 그 트랜잭션 시작 시각) · 지표 · 안전망이 고친다(무음 누락 0)`() {
        quota(s, 51, 1); rawHold(s, 51)
        val clamped0 = meters.countOf(M_INC_CLAMPED)
        val txStart: Instant = rawConnection().use { c ->
            c.exec(bumpSql(), s, 51L)
            val start = c.txNow()
            tick(source)
            jdbcTemplate.awaitDbTimeAfter(start.plus(maxOverlap).plusMillis(1_500))
            tick(source)                          // 창 상한에 걸린 틱
            c.commit()
            start
        }
        tick(source)
        tick(source)

        assertTrue(meters.countOf(M_INC_CLAMPED) - clamped0 >= 1, "window_clamped 지표")
        val gaps = jdbcTemplate.gapRows("window_clamped").filter { it["source"] == source }
        assertTrue(gaps.isNotEmpty(), "counter_inc_gap window_clamped 행이 없다(무음 누락): ${jdbcTemplate.gapRows()}")
        assertTrue(
            gaps.any { g -> g.instant("from_at")!! <= txStart && txStart < g.instant("to_at")!! },
            "보장 밖 구간 [o, 하한)에 그 트랜잭션 시작 시각($txStart)이 들어야 한다(하네스가 tx_started_at으로 가른다): $gaps",
        )
        assertTrue(meters.countOf(M_INC_GAP, "kind" to "window_clamped") >= 1, "gap{kind=window_clamped} 지표")

        safetyLap()

        assertEquals(1, cntOf(s, 51), "끝 cnt = 실제(안전망)")
        val repaired = jdbcTemplate.logsOf(s, 51).filter { it.action == "repaired" }
        assertEquals(1, repaired.size, "${jdbcTemplate.logsOf(s, 51)}")
        assertTrue(repaired.single().source in setOf(source, SAFETY_APP), "${repaired.single()}")
    }

    @Test
    fun `2 후반 — max-overlap보다 오래 열린 세션(쓰기 없음)이 있으면 그 틱에 window_clamped 기록 · 그 사이 짧은 트랜잭션의 변경은 그대로 잡는다`() {
        quota(s, 61, 1); rawHold(s, 61)
        rawConnection().use { idle ->
            idle.exec("SELECT 1")                  // xact_start만 남기는 열린 트랜잭션
            val idleStart = idle.txNow()
            jdbcTemplate.awaitDbTimeAfter(idleStart.plus(maxOverlap).plusMillis(1_500))
            val gaps0 = jdbcTemplate.gapRows("window_clamped").size
            tick(source)
            val gaps = jdbcTemplate.gapRows("window_clamped")
            assertEquals(gaps0 + 1, gaps.size, "상한에 걸린 틱 = window_clamped 1행: $gaps")
            val g = gaps.last()
            assertEquals(source, g["source"])
            assertNotNull(g.instant("from_at"))
            assertTrue(g.instant("from_at")!! <= idleStart, "from_at = o(가장 오래된 xact_start): $g, xact_start=$idleStart")
            assertNotNull(g.instant("to_at"), "to_at = 그 틱이 정한 하한")

            rawConnection().use { short ->        // 상한 걸린 동안의 짧은 트랜잭션(60초 안) — 놓치지 않는다
                short.exec(bumpSql(), s, 61L)
                short.commit()
            }
            tick(source)
            idle.rollback()
        }

        assertEquals(1, cntOf(s, 61))
        assertEquals(listOf(source), jdbcTemplate.logsOf(s, 61).filter { it.action == "repaired" }.map { it.source })
    }
}

private const val OVERLAP_2S = "$INC.max-overlap=2s"

@TestPropertySource(properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG, OVERLAP_2S])
class WindowClampTriggerTest : WindowClampScenario("trigger", SRC_TRG)

@TestPropertySource(properties = [A_ON, M_STMT, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_STMT, OVERLAP_2S])
class WindowClampStmtTest : WindowClampScenario("stmt", SRC_STMT)
