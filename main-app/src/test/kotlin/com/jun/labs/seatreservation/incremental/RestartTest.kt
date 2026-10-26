package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.support.driftLogs
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * 테스트 8(재시작)과 테스트 19 후반(재시작 뒤 진행 위치 없음) — 같은 DB에 앱을 두 번 띄운다(진짜 재시작).
 * ⓐ: 재시작 전 등록은 사라지고 그 어긋남은 안전망이 고친다 · ⓑⓒ: 새 기동 시각 − start-lookback 뒤에 시작한 트랜잭션의 변경은 첫 틱이 잡고
 * 그 앞은 안전망 몫(§2.3 재합의 행) · 두 방식 모두 기동 때 counter_inc_gap kind restart · 안전망 첫 실행 restart = true ·
 * 재시작 전 이월 대기열·진행 위치의 쌍도 결국 고쳐진다(§4.2.5 순서 5 · 커서 의미).
 */
class RestartTest : BootTestBase() {

    private fun ConfigurableApplicationContext.tick(source: String) = getBean(IncrementalReconciler::class.java).tick(source)
    private fun ConfigurableApplicationContext.safetyRun() = getBean(SafetyNetReconciler::class.java).runOnce()
    private fun ConfigurableApplicationContext.sample() = getBean(SafetyNetReconciler::class.java).sampleOnce()

    private fun ConfigurableApplicationContext.safetyUntil(max: Int = 300, cond: () -> Boolean) {
        var n = 0
        while (!cond()) {
            check(n++ < max) { "안전망 ${max}회 안에 조건을 만족하지 못했다" }
            sample(); safetyRun()
        }
    }

    private fun JdbcTemplate.dbNowTs(): Instant = dbNow()

    @Test
    fun `8 ⓐ — 재시작 전 등록은 사라진다 — restart gap 행 · 첫 틱은 못 보고 · 안전망이 고친다 · 안전망 첫 실행 restart = true`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        val props = incrementalProps("memory", LOCS_V10)
        val (s, u) = boot(url, props).use { ctx1 ->
            val s = db.seedSchedule()
            ctx1.getBean(HoldSeatUseCase::class.java).hold(HoldSeatCommand(s, db.seat(s), 801))   // 등록(커밋 뒤)
            db.update("UPDATE user_hold_quota SET cnt = 2 WHERE schedule_id = ? AND user_id = ?", s, 801L)   // 어긋남(집합에 이미 있는 쌍)
            s to 801L
        }                                          // 틱 없이 종료 — 집합을 잃는다
        val restarts0 = db.gapRows("restart").size
        val bootedAt = db.dbNowTs()

        boot(url, props).use { ctx2 ->
            val restarts = db.gapRows("restart")
            assertEquals(restarts0 + 1, restarts.size, "기동 때 restart 행 1개: $restarts")
            assertTrue(restarts.last()["source"].toString().startsWith("inc-app-mem-"), "${restarts.last()}")
            assertTrue(restarts.last().instant("at")!! >= bootedAt.minusSeconds(1), "${restarts.last()}")

            ctx2.tick(SRC_MEM)
            assertTrue(db.logsOf(s, u).isEmpty(), "재시작 전 등록을 새 프로세스가 봤다: ${db.logsOf(s, u)}")

            ctx2.sample(); ctx2.safetyRun()
            val first = db.safetyRows().last()
            assertTrue(first.bool("restart"), "기동 뒤 첫 안전망 실행 restart = true: $first")
            ctx2.safetyUntil { db.cnt(s, u) == 1 }
            ctx2.safetyRun()
            assertTrue(!db.safetyRows().last().bool("restart"), "두 번째 실행부터 restart = false: ${db.safetyRows().last()}")
            assertEquals(listOf(SAFETY_APP), db.logsOf(s, u).filter { it.action == "repaired" }.map { it.source })
        }
    }

    @Test
    fun `8 ⓑ — 새 기동 시각 − start-lookback(60s) 뒤에 시작한 트랜잭션의 변경은 재시작 뒤 첫 틱이 잡는다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        val props = incrementalProps("trigger", LOCS_TRG)
        val s = boot(url, props).use { _ ->
            val s = db.seedSchedule()
            db.quota(s, 802, 1); db.hold(s, 802)
            db.update("UPDATE user_hold_quota SET cnt = 3 WHERE schedule_id = ? AND user_id = ?", s, 802L)   // 트리거가 updated_at 표시
            s
        }
        boot(url, props).use { ctx2 ->
            assertTrue(db.gapRows("restart").isNotEmpty(), "ⓑ도 기동 때 restart 행")
            ctx2.tick(SRC_TRG)
            assertEquals(1, db.cnt(s, 802))
            assertEquals(listOf(SRC_TRG), db.logsOf(s, 802).filter { it.action == "repaired" }.map { it.source })
        }
    }

    @Test
    fun `8 ⓑ 보장 밖 — start-lookback보다 앞의 변경은 첫 틱이 못 보고(restart 행의 to_at이 그 뒤) 안전망이 고친다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        val props = incrementalProps("trigger", LOCS_TRG, "$INC.start-lookback" to "1s")
        val changedAt: Instant
        val s = boot(url, props).use { _ ->
            val s = db.seedSchedule()
            db.quota(s, 803, 1); db.hold(s, 803)
            db.update("UPDATE user_hold_quota SET cnt = 3 WHERE schedule_id = ? AND user_id = ?", s, 803L)
            s
        }
        changedAt = db.updatedAtOf(s, 803)!!
        db.awaitDbTimeAfter(changedAt.plusMillis(2_500))

        boot(url, props).use { ctx2 ->
            val r = db.gapRows("restart").last()
            assertNotNull(r.instant("to_at"), "restart 행의 to_at = 첫 하한: $r")
            assertTrue(r.instant("to_at")!! > changedAt, "첫 하한이 변경보다 뒤 — 그 변경은 보장 밖: $r, 변경 $changedAt")
            ctx2.tick(SRC_TRG)
            assertTrue(db.logsOf(s, 803).none { it.source == SRC_TRG }, "start-lookback 앞의 변경을 첫 틱이 봤다(첫 하한 규칙 위반?)")
            ctx2.safetyUntil { db.cnt(s, 803) == 1 }
            assertEquals(listOf(SAFETY_APP), db.logsOf(s, 803).filter { it.action == "repaired" }.map { it.source })
        }
    }

    @Test
    fun `8 ⓒ — 문장 방식도 같은 첫 하한 규칙(lookback 안의 앱 문장 표시는 첫 틱이 잡는다)`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        val props = incrementalProps("stmt", LOCS_STMT)
        val s = boot(url, props).use { _ ->
            val s = db.seedSchedule()
            db.quota(s, 804, 1); db.hold(s, 804)
            db.update("UPDATE user_hold_quota SET cnt = 0, updated_at = now() WHERE schedule_id = ? AND user_id = ?", s, 804L)   // ⓒ 문장 흉내
            s
        }
        boot(url, props).use { ctx2 ->
            assertTrue(db.gapRows("restart").isNotEmpty(), "ⓒ도 기동 때 restart 행")
            ctx2.tick(SRC_STMT)
            assertEquals(1, db.cnt(s, 804))
            assertEquals(listOf(SRC_STMT), db.logsOf(s, 804).filter { it.action == "repaired" }.map { it.source })
        }
    }

    @Test
    fun `8·19 안전망 — 재시작 전 이월 대기열(repair-cap 1)과 청크 진행 위치(예산 3ms)를 잃어도 그 쌍들은 결국 고쳐진다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        val before = incrementalProps(
            "none", LOCS_V10,
            "$INC.enabled" to "false",
            "$SAFE.repair-cap" to "1",
            "$SAFE.budget" to "3ms",
            "$SAFE.recent-id-chunk" to "1000",
        )
        val pairs = mutableListOf<Pair<Long, Long>>()
        boot(url, before).use { ctx1 ->
            // 회차 30개 × 쌍 1(below: 홀드 1 · cnt 0) — 한 id 청크 안, 회차마다 탐지 1번이라 예산 3ms로는 한 실행에 다 못 한다
            repeat(30) { i ->
                val s = db.seedSchedule()
                db.quota(s, 1900L + i, 0); db.hold(s, 1900L + i)
                pairs += s to 1900L + i
            }
            ctx1.sample(); ctx1.safetyRun()
            val r = db.safetyRows().last()
            assertTrue(r.long("carry_out") >= 1 || pairs.count { (s, u) -> db.cnt(s, u) == 0 } > 1, "전제: 이월 또는 미처리 쌍이 남은 채 끝난다: $r")
        }
        val after = incrementalProps("none", LOCS_V10, "$INC.enabled" to "false")
        boot(url, after).use { ctx2 ->
            ctx2.sample(); ctx2.safetyRun()
            val first = db.safetyRows().last()
            assertTrue(first.bool("restart"), "$first")
            assertEquals(0L, first.long("carry_in"), "재시작하면 대기열은 비어 있다: $first")
            ctx2.safetyUntil { pairs.all { (s, u) -> db.cnt(s, u) == 1 } }
        }
        pairs.forEach { (s, u) ->
            assertEquals(1, db.logsOf(s, u).count { it.action == "repaired" }, "쌍 ($s, $u) 정확히 한 번 복구: ${db.logsOf(s, u)}")
        }
        assertTrue(db.driftLogs().all { it.source == SAFETY_APP })
    }
}
