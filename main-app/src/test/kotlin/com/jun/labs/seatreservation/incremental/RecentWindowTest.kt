package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.countOf
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.sql.Timestamp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun IncrementalTestBase.maxHoldId(): Long = jdbcTemplate.queryForObject("SELECT coalesce(max(id), 0) FROM seat_hold", Long::class.java)!!

/** 홀드 1행을 직접 넣는다(원시 연결 — 커밋은 호출자). 반환 = 홀드 id */
private fun IncrementalTestBase.insertHoldOn(c: java.sql.Connection, scheduleId: Long, userId: Long): Long {
    val seat = freeSeat(scheduleId)
    return c.prepareStatement(
        "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?) RETURNING id",
    ).use { ps ->
        ps.setLong(1, seat); ps.setLong(2, scheduleId); ps.setLong(3, userId)
        ps.setTimestamp(4, Timestamp.from(IntegrationTest.NOW)); ps.setTimestamp(5, Timestamp.from(IntegrationTest.NOW.plusSeconds(300)))
        ps.executeQuery().use { it.next(); it.getLong(1) }
    }
}

/*
 * 테스트 14 · 19(앞부분) — 최근 창(§4.2.5 순서 2·3 · 표기 (lo, hi] · 커서 전진 규칙).
 * 시간 손잡이를 줄인다: recent-window 4s · reread 2s(창 안) · 예산 5ms · 최근 창 id 청크 10 · 탐지 k 100.
 * 표본은 테스트가 sampleOnce()로 찍는다(가정 S3 — 주기 1h). 잴 수 없는 DB 시각 경계는 awaitDbTimeAfter로 넘긴다.
 * 전체 커서가 대상 쌍을 먼저 보지 않게 '앞 회차'(기본 회차 · id가 작다)에 쿼터 행 3,000을 깔아 둔다 — 실행당 전체 몫은 몇 청크뿐이다.
 */
@TestPropertySource(
    properties = [
        A_ON, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10,
        "$SAFE.recent-window=4s", "$SAFE.reread=2s", "$SAFE.budget=5ms", "$SAFE.recent-id-chunk=10",
        "seat.hold.counter-drift.reconcile.chunk-size=100",
    ],
)
class RecentWindowTest : IncrementalTestBase() {

    private fun catchUp() {
        sample()
        safetyUntil(max = 500) { jdbcTemplate.safetyRows().lastOrNull()?.let { it.long("recent_hold_cursor") >= it.long("hold_head") } ?: false }
    }

    @Test
    fun `14 — 커서가 경계(recent-window) 밖이면 lo 초과 hi 이하 건너뛴 범위 기록 · recent_skip_ids = hi − lo · 지표 · 그 범위의 어긋남은 전체 커서가 고친다`() {
        catchUp()
        val lo = jdbcTemplate.safetyRows().last().long("recent_hold_cursor")
        val target = newSchedule(SCHEDULE_STARTS_AT.plusSeconds(86_400)).id!!
        repeat(25) { i -> rawHold(target, 1400L + i); quota(target, 1400L + i, if (i == 0) 0 else 1) }   // 1400만 below
        val hi = maxHoldId()
        sample()                                   // T1: max = hi
        val t1 = dbNow()
        jdbcTemplate.awaitDbTimeAfter(t1.plusMillis(4_500))
        sample()
        val skipped0 = meters.countOf(M_SAFE_SKIPPED_IDS)

        val r = safetyRun()

        assertEquals(lo, r.long("recent_skip_from"), "lo = 처리를 마친 커서(제외): $r")
        assertEquals(hi, r.long("recent_skip_to"), "hi = 경계 id(포함): $r")
        assertEquals(hi - lo, r.long("recent_skip_ids"), "(lo, hi] 표기: $r")
        assertEquals((hi - lo).toDouble(), meters.countOf(M_SAFE_SKIPPED_IDS) - skipped0, "recent.skipped_ids 지표")
        assertTrue(r.long("recent_hold_cursor") >= hi, "커서는 경계로 건너뛴다: $r")

        safetyUntil(max = 2_000) { cntOf(target, 1400) == 1 }   // 건너뛴 범위 = 전체 커서 몫(무음 아님)
        assertEquals(listOf(SAFETY_APP), jdbcTemplate.logsOf(target, 1400).map { it.source })
    }

    @Test
    fun `14 — 앞 실행이 지나간 뒤 늦게 커밋한 작은 id 행(다시 읽기 폭 안)은 그 뒤 실행이 잡는다 — 그 사이 실행이 더 끼어도 같다`() {
        bulkPairs(s, 1, 3_000, cnt = 0, withHold = false)        // 앞 회차 채움(전체 커서가 대상 회차에 늦게 닿게)
        val target = newSchedule(SCHEDULE_STARTS_AT.plusSeconds(86_400)).id!!
        quota(target, 1451, 0)                     // Q: 늦게 커밋할 홀드의 쌍 — 커밋 뒤 below(0 vs 1)
        quota(target, 1452, 1)                     // R: 맞는 쌍(먼저 커밋되는 더 큰 id)
        catchUp()
        sample()                                   // S0: max = m0
        val s0 = dbNow()
        jdbcTemplate.awaitDbTimeAfter(s0.plusMillis(2_300))   // S0이 'T − reread'보다 앞이 되게 — 다시 읽기 하한 = m0

        rawConnection().use { late ->
            val small = insertHoldOn(late, target, 1451)     // 할당 a — 미커밋
            rawHold(target, 1452)                             // 더 큰 id — 커밋
            sample()                                          // S1: max = 큰 id(작은 id는 안 보임)
            val r1 = safetyRun()
            assertTrue(r1.long("recent_hold_cursor") > small, "전제: 커서가 미커밋 작은 id를 지나갔다: $r1 small=$small")
            safetyRun()                                       // 앞당김처럼 실행이 하나 더 끼어도
            assertEquals(0, cntOf(target, 1451), "전제: 아직 미커밋")
            late.commit()
        }

        val r3 = safetyRun()                       // 할당 뒤 2초(reread) 안의 실행

        assertEquals(1, cntOf(target, 1451), "늦게 커밋한 작은 id 행을 다시 읽기가 잡지 못했다: $r3")
        assertEquals(listOf(SAFETY_APP), jdbcTemplate.logsOf(target, 1451).map { it.source })
        assertTrue(r3.long("reread_ids") >= 1, "다시 읽기 구역 크기: $r3")
        assertTrue(r3.long("chunks_reread") >= 1, "$r3")
    }

    @Test
    fun `19 — 청크가 실패하면 커서는 그대로 · 다음 실행이 같은 청크를 다시 · 3회 연속 실패면 건너뛴 범위를 기록하고 전진`() {
        // 앞 테스트가 지운 행 때문에 max(id)가 시퀀스보다 뒤처지지 않게 맞는 홀드 1행을 먼저 둔다(커서 = 시퀀스 끝 — 새 id가 바로 다음 청크)
        rawHold(s, 1899); quota(s, 1899, 1)
        catchUp()
        val c0 = jdbcTemplate.safetyRows().last().long("recent_hold_cursor")
        val target = newSchedule(SCHEDULE_STARTS_AT.plusSeconds(86_400)).id!!
        repeat(10) { i -> rawHold(target, 1900L + i); quota(target, 1900L + i, 0) }   // 한 id 청크(폭 10) 안의 below 10쌍
        val head = maxHoldId()
        assertEquals(c0 + 10, head, "전제: 새 id 10개가 연속(한 청크)")
        sample()
        val errors0 = meters.countOf(M_SAFE_ERRORS)
        val skipped0 = meters.countOf(M_SAFE_SKIPPED_IDS)

        val cursors = mutableListOf<Map<String, Any?>>()
        rawConnection().use { lock ->
            lock.exec("LOCK TABLE seat_hold IN ACCESS EXCLUSIVE MODE")
            repeat(3) {
                val running = pool.submit { safety.runOnce() }
                jdbcTemplate.cancelWaitersUntilDone(lock.pid(), running)
                runCatching { running.join() }
                cursors += jdbcTemplate.safetyRows().last()
            }
            lock.rollback()
        }

        assertEquals(c0, cursors[0].long("recent_hold_cursor"), "실패한 청크는 커서를 옮기지 않는다: ${cursors[0]}")
        assertEquals(c0, cursors[1].long("recent_hold_cursor"), "${cursors[1]}")
        assertTrue(cursors[2].long("recent_hold_cursor") >= c0 + 10, "3회 연속 실패 뒤 전진: ${cursors[2]}")
        assertTrue(meters.countOf(M_SAFE_ERRORS) - errors0 >= 3, "safety.errors{stage} 지표")
        // 건너뛴 범위의 기록 자리는 계약이 열 이름을 정하지 않는다(경계 건너뜀 열과 같은지) — 열 또는 지표 중 하나(모호 — 보고)
        assertTrue(
            cursors[2].long("recent_skip_ids") >= 10 || meters.countOf(M_SAFE_SKIPPED_IDS) - skipped0 >= 10,
            "3회 실패로 건너뛴 범위가 기록되지 않았다(무음): ${cursors[2]}",
        )

        safetyUntil(max = 3_000) { (1900L..1909L).all { cntOf(target, it) == 1 } }   // 건너뛴 범위 = 전체 커서 몫
        (1900L..1909L).forEach { u -> assertEquals(1, jdbcTemplate.logsOf(target, u).count { it.action == "repaired" }) }
    }
}

/*
 * 테스트 19 — 예산으로 id 청크 중간에서 멈추면 다음 실행이 그 위치에서 잇는다: 커서는 '청크 시작' 또는 '청크 끝'만 가진다(중간 값 없음) ·
 * 그 청크의 모든 쌍이 정확히 한 번 고쳐진다(빠짐 0 · 중복 0). 한 id 청크(폭 30) 안에 회차 30개 × 쌍 1 — (나) 탐지가 회차마다 1번이라
 * 예산 3ms(앞 절반 1.5ms)로는 한 실행에 끝낼 수 없다(왕복 30회 이상).
 */
@TestPropertySource(
    properties = [
        A_ON, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10,
        "$SAFE.budget=3ms", "$SAFE.recent-id-chunk=30", "$SAFE.reread=1ms",
    ],
)
class RecentWindowResumeTest : IncrementalTestBase() {

    @Test
    fun `19 — 청크 중간에서 멈춘 실행은 커서를 옮기지 않고, 다음 실행들이 이어서 끝낸다 — 쌍마다 복구 정확히 1`() {
        rawHold(s, 1949); quota(s, 1949, 1)        // 커서를 시퀀스 끝에 맞춘다(위 19와 같은 이유)
        sample()
        safetyUntil(max = 500) { jdbcTemplate.safetyRows().lastOrNull()?.let { it.long("recent_hold_cursor") >= it.long("hold_head") } ?: false }
        val c0 = jdbcTemplate.safetyRows().last().long("recent_hold_cursor")
        val pairs = (0 until 30).map { i ->
            val sc = newSchedule(SCHEDULE_STARTS_AT.plusSeconds(3_600L * (i + 1))).id!!
            rawHold(sc, 1950L + i); quota(sc, 1950L + i, 0)
            sc to 1950L + i
        }
        val head = jdbcTemplate.queryForObject("SELECT max(id) FROM seat_hold", Long::class.java)!!
        assertEquals(c0 + 30, head, "전제: 한 청크(폭 30)의 연속 id")
        sample()

        val runs = mutableListOf<Map<String, Any?>>()
        while (runs.isEmpty() || runs.last().long("recent_hold_cursor") < head) {
            check(runs.size < 200) { "200실행 안에 청크를 끝내지 못했다" }
            runs += safetyRun()
        }

        assertTrue(runs.size > 1, "전제: 예산 3ms로 한 실행에 끝났다 — 청크 중간 멈춤을 만들지 못했다")
        runs.forEach { r ->
            val c = r.long("recent_hold_cursor")
            assertTrue(c == c0 || c >= head, "커서가 청크 중간 값으로 옮겨졌다(커서 전진 규칙 위반): $c (c0=$c0, 끝=$head)")
        }
        pairs.forEach { (sc, u) ->
            assertEquals(1, cntOf(sc, u), "빠짐: ($sc, $u)")
            assertEquals(1, jdbcTemplate.logsOf(sc, u).count { it.action == "repaired" }, "중복 또는 빠짐: ${jdbcTemplate.logsOf(sc, u)}")
        }
    }
}
