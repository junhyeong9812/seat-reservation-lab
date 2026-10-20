package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.service.HoldSeatCommand
import org.junit.jupiter.api.Test
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * 테스트 18 — 측정 전용 앱 경로 주입(§4.2.7 · rv41 M7): loadtest 프로필 + 손잡이 켬에서만 주입 · 주입마다 counter_app_inject 1행(같은 트랜잭션 — 커밋된 주입만) ·
 * 롤백된 선점의 주입 없음 · 주입 쌍은 ⓐⓑⓒ 모두 증분 source로 먼저 고쳐진다 · track-inject=true면 first_candidate_at·first_candidate_source(증분 source)가 채워진다
 * (안전망이 먼저 고친 뒤에도 다음 증분 틱이 채운다) · track-inject=false면 NULL이고 그 UPDATE 문장도 나가지 않는다.
 * every=1이면 모든 쌍이 고른 쌍이다((s × 31 + u) mod 1 = 0). 쌍마다 한 번만 주입한다.
 */
abstract class AppPathInjectScenario(private val source: String) : IncrementalTestBase() {

    private fun injectOf(u: Long, sc: Long = s) = jdbcTemplate.injectRows().filter { it.long("schedule_id") == sc && it.long("user_id") == u }

    @Test
    fun `18 — 선점 경로 주입 — 1행(AH +1 또는 AL −1 · cnt_after · tx_started_at ≤ at) · 쌍마다 한 번 · 증분 source로 먼저 고쳐진다 · 첫 후보 시각`() {
        appHold(1801)
        val rows = injectOf(1801)
        assertEquals(1, rows.size, "주입 1행: ${jdbcTemplate.injectRows()}")
        val r = rows.single()
        assertEquals("hold", r["path"])
        val kind = r["kind"] as String
        assertTrue(kind in setOf("AH", "AL"), "$r")
        assertEquals(if (kind == "AH") 1L else -1L, r.long("delta"), "$r")
        assertEquals(cntOf(s, 1801)!!.toLong(), r.long("cnt_after"), "cnt_after = 주입 뒤 cnt: $r")
        assertEquals(actualOf(s, 1801) + r.long("delta").toInt(), cntOf(s, 1801), "주입이 어긋남을 만든다")
        assertTrue(r.instant("tx_started_at")!! <= r.instant("at")!!, "tx_started_at = 그 트랜잭션의 now() ≤ at(clock_timestamp): $r")
        assertNull(r["first_candidate_at"])

        tick(source)

        assertEquals(actualOf(s, 1801), cntOf(s, 1801))
        val first = jdbcTemplate.logsOf(s, 1801).first { it.action == "repaired" }
        assertEquals(source, first.source, "주입 쌍은 증분 source로 먼저 고쳐진다(R6)")
        val after = injectOf(1801).single()
        assertNotNull(after.instant("first_candidate_at"), "track-inject=true면 첫 후보 시각이 채워진다: $after")
        assertEquals(source, after["first_candidate_source"])
        assertTrue(after.instant("first_candidate_at")!! >= after.instant("at")!!, "후보 시각 = 후보 읽기 직전 DB 시각 > 주입 at: $after")

        appHold(1801)                              // 고친 뒤 같은 쌍의 두 번째 선점(cnt 1 → 2) — 다시 주입하지 않는다
        assertEquals(1, injectOf(1801).size, "쌍마다 한 번만")
        assertEquals(actualOf(s, 1801), cntOf(s, 1801))
    }

    @Test
    fun `18 — δ는 쌍마다 번갈아 — 연속 두 쌍의 kind = AH · AL 하나씩`() {
        appHold(1811)
        appHold(1812)
        val kinds = listOf(1811L, 1812L).map { injectOf(it).single()["kind"] as String }.toSet()
        assertEquals(setOf("AH", "AL"), kinds)
    }

    @Test
    fun `18 — 롤백된 선점(좌석 NOWAIT 실패)의 주입은 없다`() {
        val seat = freeSeat()
        rawConnection().use { lock ->
            lock.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", seat)
            assertTrue(failureOf { holdSeat.hold(HoldSeatCommand(s, seat, 1821)) } != null)
            lock.rollback()
        }
        assertTrue(injectOf(1821).isEmpty(), "롤백된 주입이 남았다: ${injectOf(1821)}")
        assertNull(cntOf(s, 1821))
    }

    @Test
    fun `18 — 만료 경로 주입 — A 가드 감소가 1행일 때 +1(AE) · 증분이 먼저 고친다`() {
        quota(s, 1831, 1)
        rawHold(s, 1831, expiresAt = NOW.minusSeconds(1))     // 직접 SQL 홀드 — 선점 경로 주입을 거치지 않는다
        tick(source)                                           // 준비 행을 창·집합에서 비운다(맞는 상태)

        assertEquals(1, expireHolds.expire())

        val r = injectOf(1831).single()
        assertEquals("expiry", r["path"]); assertEquals("AE", r["kind"]); assertEquals(1L, r.long("delta"))
        assertEquals(1, cntOf(s, 1831), "감소 0 + 주입 1")
        assertEquals(0, actualOf(s, 1831))

        tick(source)

        assertEquals(0, cntOf(s, 1831))
        assertEquals(source, jdbcTemplate.logsOf(s, 1831).first { it.action == "repaired" }.source)
        assertEquals(source, injectOf(1831).single()["first_candidate_source"])
    }

    @Test
    fun `18 — 안전망이 먼저 고친 주입 쌍도 다음 증분 틱이 first_candidate를 채운다(탐지 범위 검증)`() {
        appHold(1841)
        safetyUntil { cntOf(s, 1841) == actualOf(s, 1841) }
        assertEquals(SAFETY_APP, jdbcTemplate.logsOf(s, 1841).first { it.action == "repaired" }.source, "전제: 안전망이 먼저 고쳤다")
        assertNull(injectOf(1841).single()["first_candidate_at"])

        tick(source)

        val r = injectOf(1841).single()
        assertNotNull(r.instant("first_candidate_at"), "$r")
        assertEquals(source, r["first_candidate_source"])
    }
}

private const val INJ_ON = "$INJECT.enabled=true"
private const val INJ_EVERY1 = "$INJECT.every=1"
private const val INJ_PATHS = "$INJECT.paths=hold,expiry"
private const val TRACK_ON = "$INC.track-inject=true"

@ActiveProfiles("loadtest")
@TestPropertySource(properties = [A_ON, M_MEM, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10, INJ_ON, INJ_EVERY1, INJ_PATHS, TRACK_ON])
class AppPathInjectMemoryTest : AppPathInjectScenario(SRC_MEM)

@ActiveProfiles("loadtest")
@TestPropertySource(properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG, INJ_ON, INJ_EVERY1, INJ_PATHS, TRACK_ON])
class AppPathInjectTriggerTest : AppPathInjectScenario(SRC_TRG)

@ActiveProfiles("loadtest")
@TestPropertySource(properties = [A_ON, M_STMT, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_STMT, INJ_ON, INJ_EVERY1, INJ_PATHS, TRACK_ON])
class AppPathInjectStmtTest : AppPathInjectScenario(SRC_STMT)

/** track-inject=false — 열은 NULL이고 후보 시각 UPDATE가 나가지 않는다(counter_app_inject를 ACCESS EXCLUSIVE로 쥔 채 틱이 막힘 없이 끝난다). */
@ActiveProfiles("loadtest")
// (rv43 M3 — 증분만 켜는 조합 금지 · 안전망은 켜되 돌지 않게)
@TestPropertySource(properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG, INJ_ON, INJ_EVERY1, INJ_PATHS])
class AppPathInjectNoTrackTest : IncrementalTestBase() {
    @Test
    fun `18 — track-inject=false — first_candidate 열 NULL · 문장 없음 · 그래도 증분이 고친다`() {
        appHold(1851)
        assertEquals(1, jdbcTemplate.injectRows().size)
        rawConnection().use { lock ->
            lock.exec("LOCK TABLE counter_app_inject IN ACCESS EXCLUSIVE MODE")
            val running = pool.submit { tick(SRC_TRG) }
            try {
                running.join(10)
            } catch (e: java.util.concurrent.TimeoutException) {
                throw AssertionError("track-inject=false인데 틱이 counter_app_inject를 기다린다(문장이 나갔다): " +
                    jdbcTemplate.queryForList("SELECT query FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", String::class.java, lock.pid()))
            } finally {
                lock.rollback()
            }
        }
        assertEquals(actualOf(s, 1851), cntOf(s, 1851))
        val r = jdbcTemplate.injectRows().single()
        assertNull(r["first_candidate_at"]); assertNull(r["first_candidate_source"])
    }
}

/** 손잡이 꺼짐(loadtest 프로필이어도) — 주입 0 · 쿼터 정상. */
@ActiveProfiles("loadtest")
// (rv43 M3 — 증분만 켜는 조합 금지 · 안전망은 켜되 돌지 않게)
@TestPropertySource(properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG])
class AppPathInjectOffTest : IncrementalTestBase() {
    @Test
    fun `18 — inject-app 미지정이면 주입하지 않는다`() {
        appHold(1861); appHold(1862)
        assertTrue(jdbcTemplate.injectRows().isEmpty())
        assertEquals(1, cntOf(s, 1861)); assertEquals(1, cntOf(s, 1862))
    }
}

/** max-pairs — 상한 수만큼만 주입한다(원자적 개수). 이 클래스는 주입을 한 테스트에서만 만든다(개수가 컨텍스트에 쌓인다). */
@ActiveProfiles("loadtest")
// (rv43 M3 — 증분만 켜는 조합 금지 · 안전망은 켜되 돌지 않게)
@TestPropertySource(properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG, INJ_ON, INJ_EVERY1, INJ_PATHS, "$INJECT.max-pairs=2"])
class AppPathInjectMaxPairsTest : IncrementalTestBase() {
    @Test
    fun `18 — max-pairs=2 — 세 쌍 선점에 주입 2행`() {
        appHold(1871); appHold(1872); appHold(1873)
        assertEquals(2, jdbcTemplate.injectRows().size, "${jdbcTemplate.injectRows()}")
    }
}

/** every — 고른 쌍만((s × 31 + u) mod every == 0). */
@ActiveProfiles("loadtest")
// (rv43 M3 — 증분만 켜는 조합 금지 · 안전망은 켜되 돌지 않게)
@TestPropertySource(properties = [A_ON, M_TRG, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_TRG, INJ_ON, "$INJECT.every=50", INJ_PATHS])
class AppPathInjectEveryTest : IncrementalTestBase() {
    @Test
    fun `18 — every=50 — 선택식에 맞는 쌍만 주입된다`() {
        val chosen = (1L..200L).first { u -> (s * 31 + u) % 50 == 0L }
        val notChosen = (1L..200L).first { u -> (s * 31 + u) % 50 != 0L }
        appHold(chosen); appHold(notChosen)
        assertEquals(listOf(chosen), jdbcTemplate.injectRows().map { it.long("user_id") })
    }
}

