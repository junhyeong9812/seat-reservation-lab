package com.jun.labs.seatreservation.adr018impl

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.service.impl.expiry.LazyExpiryBatch
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_FLYWAY_BASE
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 동시성(G1 · G3 · G5): 겹치는 2석을 두 사용자가 반대 순서로 · 해제 ∥ 만료 배치 · 해제 ∥ 결제 시작.
 * 매 회 새 좌석 · 새 사용자 — 교착(500) 0 · 부분 선점 0 · 카운터 = 홀드 + 예약.
 */
abstract class HoldGroupConcurrencyCases : Adr018ImplBase() {
    private val pool = Executors.newFixedThreadPool(4)

    @AfterEach
    fun stopPool() {
        pool.shutdownNow()
    }

    /** split은 NOWAIT이라 겹친 두 요청 중 하나는 반드시 이긴다. one-shot(SKIP LOCKED)은 둘 다 409도 허용한다(설계-2) */
    protected open val expectWinner = false

    private fun <T> together(vararg blocks: () -> T): List<T> {
        val barrier = CyclicBarrier(blocks.size)
        return blocks.map { b -> pool.submit<T> { barrier.await(10, TimeUnit.SECONDS); b() } }.map { it.get(60, TimeUnit.SECONDS) }
    }

    @Test
    fun `겹치는 2석 반대 순서 — 한 사람이 2석 전부이거나 둘 다 409 · 부분 선점 0 · 교착 0`() {
        var winners = 0
        repeat(ROUNDS) { i ->
            val (a, b) = seatIds(2, from = 10 + i * 2)
            val ua = 1_000L + i * 2
            val ub = ua + 1
            val (ra, rb) = together({ holdGroup(ua, a, b) }, { holdGroup(ub, b, a) })
            for (r in listOf(ra, rb)) assertTrue(r.status == 201 || (r.status == 409 && r.code == "SEAT_NOT_AVAILABLE"), "회 $i: $r")
            assertTrue(!(ra.status == 201 && rb.status == 201), "회 $i: 둘 다 성공할 수 없다 $ra $rb")
            for (user in listOf(ua, ub)) assertTrue(holdsOfUser(user) in setOf(0L, 2L), "회 $i 사용자 $user 홀드 ${holdsOfUser(user)} — 부분 선점")
            if (ra.status == 201 || rb.status == 201) winners++
        }
        assertNoViolations("반대 순서 경합 뒤")
        if (expectWinner) assertTrue(winners > 0, "split — 적어도 한 회는 누군가 2석을 잡는다")
    }

    @Test
    fun `세 사용자 사슬 겹침(1-2 · 2-3 · 3-1) — 교착 0 · 부분 선점 0`() {
        repeat(ROUNDS) { i ->
            val (s1, s2, s3) = seatIds(3, from = 500 + i * 3)
            val base = 5_000L + i * 3
            val rs = together({ holdGroup(base, s1, s2) }, { holdGroup(base + 1, s3, s2) }, { holdGroup(base + 2, s1, s3) })
            rs.forEach { assertTrue(it.status == 201 || (it.status == 409 && it.code == "SEAT_NOT_AVAILABLE"), "회 $i: $it") }
            (0..2).forEach { assertTrue(holdsOfUser(base + it) in setOf(0L, 2L), "회 $i 부분 선점") }
        }
        assertNoViolations("사슬 경합 뒤")
    }

    @Test
    fun `해제 ∥ 만료 배치 — 카운터 이중 감소 없음 · 교착 0`() {
        repeat(ROUNDS) { i ->
            clock.set(NOW)
            val (a, b) = seatIds(2, from = 1_000 + i * 2)
            val user = 9_000L + i
            val group = holdGroupOk(user, a, b)
            clock.set(NOW.plus(Duration.ofMinutes(6)))
            var exp: Result<Int>? = null
            val (rel, _) = together({ releaseGroup(user, group) }, { exp = runCatching { expire.expire() }; R(0, "") })
            assertTrue(rel.status == 204 || rel.status == 404, "회 $i 해제: $rel")
            assertTrue(exp!!.isSuccess, "회 $i 배치: ${exp!!.exceptionOrNull()}")
            assertEquals(0, cnt(user), "회 $i 카운터")
            assertEquals(0L, holdsOfGroup(group))
            assertEquals(listOf("AVAILABLE", "AVAILABLE"), listOf(seatStatus(a), seatStatus(b)))
        }
        assertNoViolations("해제 ∥ 배치 뒤")
    }

    @Test
    fun `해제 ∥ 묶음 결제 시작 — 해제 204면 결제 시작 404 · 결제 시작 200이면 해제 409`() {
        repeat(ROUNDS) { i ->
            val (a, b) = seatIds(2, from = 2_000 + i * 2)
            val user = 12_000L + i
            val group = holdGroupOk(user, a, b)
            val (rel, ps) = together({ releaseGroup(user, group) }, { groupPaymentStart(user, group) })
            val ok = (rel.status == 204 && ps.status == 404) || (ps.status == 200 && rel.status == 409 && rel.code == "PAYMENT_STARTED")
            assertTrue(ok, "회 $i: 해제 $rel · 결제 시작 $ps")
            if (rel.status == 204) assertEquals(0L, q("SELECT count(*) FROM payment_intent WHERE group_id = ?", group), "해제가 이기면 결제 의도 없음")
        }
        assertNoViolations("해제 ∥ 결제 시작 뒤")
    }

    @Test
    fun `해제 ∥ 1석 결제 시작 — 해제 204면 결제 의도 없음`() {
        repeat(ROUNDS) { i ->
            val seat = createSeat(3_000 + i).id!!
            val user = 15_000L + i
            val hold = holdOneOk(user, seat)
            val (rel, ps) = together({ releaseHold(user, hold) }, { holdPaymentStart(user, hold) })
            val ok = (rel.status == 204 && ps.status == 404) || (ps.status == 200 && rel.status == 409 && rel.code == "PAYMENT_STARTED")
            assertTrue(ok, "회 $i: 해제 $rel · 결제 시작 $ps")
        }
        assertEquals(0L, q("SELECT count(*) FROM payment_intent i WHERE NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.id = i.hold_id)"), "홀드 없는 결제 의도 0")
        assertNoViolations("해제 ∥ 1석 결제 시작 뒤")
    }

    @Test
    fun `묶음 결제 시작 ∥ 만료 배치(만료된 묶음) — 결제 시작 409 HOLD_EXPIRED 또는 404 · 500 0 · 배치 정상 · 카운터 = 홀드 + 예약`() {
        repeat(ROUNDS) { i ->
            clock.set(NOW)
            val (a, b) = seatIds(2, from = 4_000 + i * 2)
            val user = 18_000L + i
            val group = holdGroupOk(user, a, b)
            clock.set(NOW.plus(Duration.ofMinutes(6)))
            var exp: Result<Int>? = null
            val (ps, _) = together({ groupPaymentStart(user, group) }, { exp = runCatching { expire.expire() }; R(0, "") })
            assertTrue((ps.status == 409 && ps.code == "HOLD_EXPIRED") || (ps.status == 404 && ps.code == "HOLD_NOT_FOUND"), "회 $i 결제 시작: $ps")
            assertTrue(exp!!.isSuccess, "회 $i 배치: ${exp!!.exceptionOrNull()}")
            assertEquals(0L, holdsOfGroup(group), "회 $i 배치가 묶음을 지웠다")
            assertEquals(0, cnt(user), "회 $i 카운터")
            assertEquals(0L, q("SELECT count(*) FROM payment_intent WHERE group_id = ?", group))
        }
        assertNoViolations("결제 시작 ∥ 배치 뒤")
    }

    @Test
    fun `1석 해제 ∥ 1석 확정(결제 의도 없는 옛 경로) — 204+404 또는 200+404 · 500 0 · 카운터 = 홀드 + 예약`() {
        repeat(ROUNDS) { i ->
            clock.set(NOW)
            val seat = createSeat(5_000 + i).id!!
            val user = 21_000L + i
            val hold = holdOneOk(user, seat)
            val (rel, conf) = together({ releaseHold(user, hold) }, { holdConfirm(user, hold, "legacy-$i") })
            val ok = (rel.status == 204 && conf.status == 404 && conf.code == "HOLD_NOT_FOUND") ||
                (conf.status == 200 && rel.status == 404 && rel.code == "HOLD_NOT_FOUND")
            assertTrue(ok, "회 $i: 해제 $rel · 확정 $conf")
            if (rel.status == 204) {
                assertEquals("AVAILABLE", seatStatus(seat)); assertEquals(0, cnt(user))
            } else {
                assertEquals("RESERVED", seatStatus(seat)); assertEquals(1, cnt(user))
            }
        }
        assertNoViolations("해제 ∥ 확정 뒤")
    }

    @Test
    fun `카운터 행 없는(MISSING) 만료된 1석 홀드 해제 ∥ 만료 배치 — 500 0 · 교착 0 · 배치는 성공 또는 MissingMismatch 롤백 · 카운터 = 홀드 + 예약`() {
        repeat(ROUNDS) { i ->
            clock.set(NOW)
            val seat = createSeat(6_000 + i).id!!
            val user = 24_000L + i
            holdOneOk(user, seat).let { hold ->
                jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", schedule.id, user)
                clock.set(NOW.plus(Duration.ofMinutes(6)))
                var exp: Result<Int>? = null
                val (rel, _) = together({ releaseHold(user, hold) }, { exp = runCatching { expire.expire() }; R(0, "") })
                assertTrue(rel.status == 204 || (rel.status == 404 && rel.code == "HOLD_NOT_FOUND"), "회 $i 해제: $rel")
                // 배치의 MISSING 쌍은 복구에 넘긴 id를 해제가 먼저 지우면 틱을 MissingMismatch로 롤백한다(기존 A′ 규칙 — 다음 틱이 다시 본다). 교착(40P01) 등 그 밖의 실패는 안 된다
                val e = exp!!.exceptionOrNull()
                assertTrue(e == null || e is LazyExpiryBatch.MissingMismatch, "회 $i 배치: $e")
                if (e != null) assertTrue(expire.expire() >= 0, "회 $i 다음 틱")
                assertEquals(0L, holdsOfUser(user), "회 $i 홀드")
                assertEquals("AVAILABLE", seatStatus(seat))
                assertTrue(cnt(user) in setOf(null, 0), "회 $i 카운터 ${cnt(user)}")
            }
        }
        assertNoViolations("MISSING 해제 ∥ 배치 뒤")
    }

    companion object {
        const val ROUNDS = 25
    }
}

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [G_SCHED_OFF, G_COMP_SCHED_OFF, G_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE, G_ENABLED, G_SPLIT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class HoldGroupConcurrencySplitTest : HoldGroupConcurrencyCases() {
    override val expectWinner = true
}

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [G_SCHED_OFF, G_COMP_SCHED_OFF, G_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE, G_ENABLED, G_ONE_SHOT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class HoldGroupConcurrencyOneShotTest : HoldGroupConcurrencyCases()
