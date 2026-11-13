package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.adr015.Resp
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import java.sql.SQLException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * S 동시성 — 명세 §5 'S 대체 ∥ 만료 배치 ∥ 선점 → 교착 0 · 쿼터 카운터 = 실제 수' · 부록 B '동시성' · ADR-017 설계-4.3 잠금 순서(RD17-4 · RD17-P2) · 설계-5 S · N17-9.
 * 한 판 = 원래 홀드가 만료된 결제 끝난 uid 하나에 대해, CountDownLatch 출발선(Adr017Base.together)에서 동시에:
 *   ① 확정(→ 거부 → S 대체 트랜잭션: intent → 회차 FOR SHARE → 쿼터 쌍 → 원래 홀드 · 좌석 → 새 좌석 SKIP LOCKED → 쿼터 +1)
 *   ② 만료 배치 A′(ExpireHoldsUseCase.expire — 쿼터 → 홀드 → 좌석) — 앞 판들의 만료 홀드까지 함께 닫는다
 *   ③ 같은 사용자 선점(다른 좌석 — 같은 쿼터 쌍)
 *   ④ 다른 사용자 선점(원래 좌석 — 같은 좌석 행)
 *   ⑤ 다른 사용자 선점(대체 후보 좌석 — SKIP LOCKED 대상과 같은 행)
 * 판정(판마다 + 끝): 5xx 0 · 배치 예외 0 · 응답 · 예외 어디에도 deadlock 없음 · PostgreSQL pg_stat_database.deadlocks 증가 0(끝 — 통계 반영 지연 11초 대기) ·
 *   좌석당 CONFIRMED ≤ 1 · 좌석 HELD ⇔ 홀드 행 1 · RESERVED ⇔ CONFIRMED 예약 · 모든 (회차, 사용자) cnt = 홀드 + 확정 예약 ·
 *   intent 결과 하나(SUBSTITUTED + 대체 예약 · 작업 0 | REJECTED + CLOSE 1 · 예약 0).
 *
 * 가정: pg_stat_database 통계는 백엔드가 쉬는 동안 늦어도 10초 안에 반영된다(PostgreSQL 15+ PGSTAT_IDLE_INTERVAL) — 끝에서 11초 기다린 뒤 읽는다.
 *       교착은 PostgreSQL이 한쪽을 40P01로 끊으므로 앱에서는 5xx · 배치 예외로도 드러난다(이중 그물).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        P_SCHED_OFF, DS_SCHEDULER_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        P_LOC_BASE, DS_MODE_SUBSTITUTE,
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class Adr017SConcurrencyTest : Adr017DsBase() {

    private fun deadlocks(): Long {
        jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()")
        return jdbcTemplate.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long::class.java)!!
    }

    private fun isDeadlock(t: Throwable?): Boolean {
        var c = t
        while (c != null) {
            if (c is SQLException && c.sqlState == "40P01") return true
            if (c.message?.contains("deadlock", ignoreCase = true) == true) return true
            c = c.cause
        }
        return false
    }

    /** 회차 전체 정합 — 판마다 · 끝 */
    private fun assertGlobalInvariants(why: String) {
        val multi = jdbcTemplate.queryForList(
            "SELECT seat_id, count(*) AS n FROM reservation WHERE status = 'CONFIRMED' GROUP BY seat_id HAVING count(*) > 1",
        )
        assertTrue(multi.isEmpty(), "$why — 좌석당 CONFIRMED ≤ 1: $multi")

        val heldMismatch = jdbcTemplate.queryForList(
            """
            SELECT s.id, s.status, (SELECT count(*) FROM seat_hold h WHERE h.seat_id = s.id) AS holds
            FROM product_seat s WHERE s.schedule_id = ?
              AND ((s.status = 'HELD') <> ((SELECT count(*) FROM seat_hold h WHERE h.seat_id = s.id) = 1)
                   OR (SELECT count(*) FROM seat_hold h WHERE h.seat_id = s.id) > 1)
            """.trimIndent(),
            schedule.id,
        )
        assertTrue(heldMismatch.isEmpty(), "$why — 좌석 HELD ⇔ 홀드 행 1: $heldMismatch · ${dbState()}")

        val reservedMismatch = jdbcTemplate.queryForList(
            """
            SELECT s.id, s.status FROM product_seat s WHERE s.schedule_id = ?
              AND ((s.status = 'RESERVED') <> EXISTS (SELECT 1 FROM reservation r WHERE r.seat_id = s.id AND r.status = 'CONFIRMED'))
            """.trimIndent(),
            schedule.id,
        )
        assertTrue(reservedMismatch.isEmpty(), "$why — 좌석 RESERVED ⇔ CONFIRMED 예약: $reservedMismatch · ${dbState()}")

        val quota = jdbcTemplate.queryForList(
            """
            WITH users AS (
              SELECT user_id FROM seat_hold WHERE schedule_id = ?
              UNION SELECT user_id FROM reservation WHERE schedule_id = ?
              UNION SELECT user_id FROM user_hold_quota WHERE schedule_id = ?
            )
            SELECT u.user_id,
                   COALESCE((SELECT q.cnt FROM user_hold_quota q WHERE q.schedule_id = ? AND q.user_id = u.user_id), 0) AS cnt,
                   (SELECT count(*) FROM seat_hold h WHERE h.schedule_id = ? AND h.user_id = u.user_id)
                 + (SELECT count(*) FROM reservation r WHERE r.schedule_id = ? AND r.user_id = u.user_id AND r.status = 'CONFIRMED') AS actual
            FROM users u
            """.trimIndent(),
            schedule.id, schedule.id, schedule.id, schedule.id, schedule.id, schedule.id,
        ).filter { (it["cnt"] as Number).toLong() != (it["actual"] as Number).toLong() }
        assertTrue(quota.isEmpty(), "$why — 쿼터 cnt = 실제 (홀드 + 확정 예약) 수(N17-9): $quota · ${dbState()}")
    }

    private fun assertIntentOutcome(uid: String, first: Resp, why: String) {
        val i = intentOrFail(uid)
        val ts = tasks(uid)
        val res = reservationsOfUid(uid)
        val comp = compOrFail(first, why)
        when (i.state) {
            "SUBSTITUTED" -> {
                assertEquals(1, res, "$why — 대체 예약 1")
                assertTrue(ts.isEmpty(), "$why — 대체 성공에 작업 없음(N17-5): $ts")
                assertEquals("SUBSTITUTE", comp.type, "$why — 본문 = DB 결과: $first")
            }
            REJECTED -> {
                assertEquals(0, res, "$why — CLOSE면 예약 0(N17-5)")
                assertEquals(listOf("CLOSE"), ts.map { it.type }, "$why — CLOSE 1")
                assertEquals("CLOSE", comp.type, "$why — 본문 = DB 결과: $first")
            }
            else -> fail("$why — 거부 뒤 intent는 SUBSTITUTED 또는 REJECTED: $i")
        }
        assertEquals(1, rejections(uid).size, "$why — 거부 기록 1(N17-2)")
    }

    @Test
    fun `N17-9 · 명세 §5 — S 대체 ∥ 만료 배치 ∥ 같은 사용자 선점 ∥ 다른 사용자 선점(12판) → 교착 0 · 5xx 0 · 쿼터 = 실제 · 좌석당 CONFIRMED ≤ 1 · HELD ⇔ 홀드`() {
        val dlBefore = deadlocks()
        val all = mutableListOf<Resp>()
        val outcomes = mutableMapOf<String, Int>()
        var seatNo = 0
        repeat(12) { round ->
            clock.set(IntegrationTest.NOW.plus(Duration.ofMinutes(20L * round)))
            val u = 10_000L + round
            val v = 20_000L + round
            val w = 30_000L + round
            val orig = createSeat(++seatNo).id!!
            val mine = createSeat(++seatNo).id!!
            val cand1 = createSeat(++seatNo).id!!
            createSeat(++seatNo) // 대체 후보 2

            val p = startedHold(orig, u)
            pgPayOk(p.uid)
            clock.set(p.expiresAt.plusSeconds(1))

            val batchErr = arrayOfNulls<Throwable>(1)
            val rs = together(
                { confirmHttp(p.holdId, u, p.uid) },
                { runCatching { expire.expire() }.onFailure { batchErr[0] = it }; Resp(200, "{}") },
                { holdHttp(mine, u) },
                { holdHttp(orig, v) },
                { holdHttp(cand1, w) },
            )
            val why = "[판 $round]"
            all += rs
            assertNo5xx(rs, why)
            assertTrue(batchErr[0] == null, "$why — 만료 배치 예외(교착 ${isDeadlock(batchErr[0])}): ${batchErr[0]}")
            assertTrue(rs.none { it.body.contains("deadlock", ignoreCase = true) }, "$why — 응답에 deadlock: $rs")

            val first = rs[0]
            assertTrue(first.status == 409 || first.status == 404, "$why — 확정은 만료 홀드 거부(409 배치 전 · 404 배치 뒤): $first")
            rs.subList(2, 5).forEach { h -> assertTrue(h.status == 201 || h.status in 400..499, "$why — 선점은 201 또는 4xx: $h") }

            assertIntentOutcome(p.uid, first, why)
            assertGlobalInvariants(why)

            // 재요청 재생(N17-3) — 동시 판 뒤에도 같은 결과
            val again = confirmHttp(p.holdId, u, p.uid)
            assertEquals(first.status, again.status, "$why — 재생: $first / $again")
            assertEquals(first.code, again.code, "$why — 재생 코드")
            assertEquals(comp(first), comp(again), "$why — 재생 본문(워커를 돌리지 않았으니 보상 상태도 같다): $first / $again")

            outcomes.merge("${first.status}-${intentOrFail(p.uid).state}", 1, Int::plus)
        }
        // 앞 판 홀드들까지 한 번 더 닫은 뒤 전체 정합
        clock.advance(Duration.ofHours(1))
        expire.expire()
        assertGlobalInvariants("[끝 — 배치 뒤]")

        Thread.sleep(11_000)
        val dlAfter = deadlocks()
        assertEquals(dlBefore, dlAfter, "PostgreSQL 교착(40P01) 0 — pg_stat_database.deadlocks 증가: $dlBefore → $dlAfter")
        println("ADR-017 S 동시성 결과 분포: $outcomes · 응답 상태: ${all.groupingBy { it.status }.eachCount()}")
    }
}
