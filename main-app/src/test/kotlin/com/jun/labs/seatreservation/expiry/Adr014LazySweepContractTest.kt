package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.incremental.exec
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * ADR-014 C(lazy-sweep — 판정 lazy + 정리 sweep) 계약(blind). 공통 계약은 [Adr014ModeContractBase].
 * C만의 것: 배치 C1~C6(§3.4) — 좌석 FOR UPDATE SKIP LOCKED → 쌍 잠금 → 만료 행 삭제 · 지운 만큼 감소 → 잠근 좌석만 활성 홀드가 없을 때 AVAILABLE ·
 * 회수 H4b(회수한 다른 좌석의 거짓 HELD를 SKIP LOCKED로 고친다 — Q6) · 가설 3(배치가 재선점된 좌석을 되돌리지 않는다 — §설계-5 · N6) · N5(회수 ∥ 배치 이중 감소 0).
 */
@TestPropertySource(properties = ["$EXPIRY_MODE=lazy-sweep"])
class Adr014LazySweepContractTest : Adr014ModeContractBase() {

    override val modeValue = "lazy-sweep"
    override val storedHeld = SeatStatus.HELD
    override val expiredOnAvailableSeatCode = "SEAT_NOT_HELD"

    @Test
    fun `C 배치 — 만료 행 삭제 · 지운 만큼 감소 · 거짓 HELD 되돌림 · 재선점된 좌석(활성 홀드)은 HELD 유지 · 판정은 DB now()(앱 시계 과거·미래)`() {
        val (s1, h1) = seatWithExpiredHold(100)                 // 만료만 → AVAILABLE로
        val (s2, h2) = seatWithExpiredHold(200)                 // 만료(200) + 재선점 활성(300) → HELD 유지
        val h3 = jdbcTemplate.dbHold(s2, s, 300, "5 minutes")
        val (s3, h4) = seatWithActiveHold(400)                   // 활성만 → 그대로
        clock.set(CLOCK_PAST)
        expireHolds.expire()
        assertFalse(jdbcTemplate.holdRowExists(h1) || jdbcTemplate.holdRowExists(h2), "만료 행 삭제(앱 시계 과거여도 — N1)")
        assertTrue(jdbcTemplate.holdRowExists(h3) && jdbcTemplate.holdRowExists(h4), "활성 행은 남는다")
        assertEquals(listOf("AVAILABLE", "HELD", "HELD"), listOf(s1, s2, s3).map { jdbcTemplate.storedStatus(it) })
        assertEquals(listOf(0, 0, 1, 1), listOf(100L, 200L, 300L, 400L).map { cnt(it) })
        clock.set(CLOCK_FUTURE)
        expireHolds.expire()
        assertTrue(jdbcTemplate.holdRowExists(h3) && jdbcTemplate.holdRowExists(h4), "앱 시계 미래여도 DB 기준 활성 행은 남는다(N1)")
        assertEquals(listOf("HELD", "HELD"), listOf(s2, s3).map { jdbcTemplate.storedStatus(it) })
        assertEquals(0, jdbcTemplate.activeHoldNotHeld(), "활성 홀드가 있는데 HELD 아님 = 0(§8.3 · N6)")
        assertCounterConsistent("C 배치")
    }

    @Test
    fun `C 배치 C2 — 잠긴 좌석(선점·확정 중)은 기다리지 않고 건너뛴다 · 다음 틱이 정리한다`() {
        val (locked, lockedHold) = seatWithExpiredHold(100)
        val (free, freeHold) = seatWithExpiredHold(200)
        raw().use { r ->
            r.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", locked)
            pool.submit<Int> { expireHolds.expire() }.within(10)
            assertTrue(jdbcTemplate.holdRowExists(lockedHold), "잠긴 좌석의 만료 행은 이번 틱에 지우지 않는다(SKIP LOCKED)")
            assertEquals("HELD", jdbcTemplate.storedStatus(locked))
            assertEquals(1, cnt(100))
            assertFalse(jdbcTemplate.holdRowExists(freeHold), "잠기지 않은 좌석은 정리")
            assertEquals("AVAILABLE", jdbcTemplate.storedStatus(free))
            r.rollback()
        }
        expireHolds.expire()
        assertFalse(jdbcTemplate.holdRowExists(lockedHold), "다음 틱이 정리")
        assertEquals("AVAILABLE", jdbcTemplate.storedStatus(locked))
        assertEquals(0, cnt(100))
        assertCounterConsistent("C SKIP LOCKED")
    }

    @Test
    fun `C 회수 H4b — 회수한 다른 좌석의 거짓 HELD를 고친다 · 그 좌석이 잠겨 있으면 기다리지 않고 건너뛴다`() {
        val (other, expired) = seatWithExpiredHold(100)
        assertEquals("ok", tryHold(seat(), 100))
        assertFalse(jdbcTemplate.holdRowExists(expired))
        assertEquals("AVAILABLE", jdbcTemplate.storedStatus(other), "H4b — 회수한 좌석의 거짓 HELD → AVAILABLE")

        val (lockedOther, expired2) = seatWithExpiredHold(200)
        val target = seat()
        raw().use { r ->
            r.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", lockedOther)
            assertEquals("ok", pool.submit<String> { tryHold(target, 200) }.within(10), "H4b는 SKIP LOCKED — 선점이 기다리지 않는다")
            r.rollback()
        }
        assertFalse(jdbcTemplate.holdRowExists(expired2), "회수 자체는 된다")
        assertEquals("HELD", jdbcTemplate.storedStatus(lockedOther), "잠겨서 건너뛴 좌석은 거짓 HELD로 남는다(§8.3 'H4b 건너뜀분')")
        assertCounterConsistent("C H4b")
    }

    @Test
    fun `가설 3 · N6 — 배치 틱이 도는 동안 재선점된 좌석을 AVAILABLE로 되돌리지 않는다(스트레스)`() {
        val seats = (1..40).map { seatWithExpiredHold(10_000L + it).first }
        val stop = AtomicBoolean(false)
        val ticker = pool.submit<Int> {
            var ticks = 0
            while (!stop.get()) {
                expireHolds.expire(); ticks++
            }
            ticks
        }
        val results = pool.together(seats.size) { i ->
            var r = ""
            repeat(50) {
                r = tryHold(seats[i], 20_000L + i)
                if (r == "ok") return@together r
                Thread.sleep(5)
            }
            r
        }
        stop.set(true)
        assertTrue(ticker.within(30) > 0, "배치 틱이 돌았다")
        expireHolds.expire()
        assertEquals(List(seats.size) { "ok" }, results, "재선점은 모두 결국 성공(NOWAIT 패배만 재시도)")
        seats.forEachIndexed { i, seat ->
            assertEquals(1, jdbcTemplate.activeHoldOf(seat, 20_000L + i), "좌석 $seat 재선점 홀드")
            assertEquals("HELD", jdbcTemplate.storedStatus(seat), "좌석 $seat 는 활성 홀드가 있으니 HELD여야 한다(배치가 되돌렸다 — 가설 3)")
        }
        assertEquals(0, jdbcTemplate.activeHoldNotHeld(), "§8.3 '활성 홀드가 있는데 HELD 아님' = 0")
        assertEquals(0, jdbcTemplate.expiredHoldRows(), "마지막 틱 뒤 남은 만료 행 0")
        assertNoDoubleSale("배치 ∥ 재선점")
        assertCounterConsistent("배치 ∥ 재선점")
    }

    @Test
    fun `N5 — 회수와 배치가 동시에 같은 만료 행을 노려도 cnt는 정확히 한 번 내려간다`() {
        val users = (1..20).map { 30_000L + it }
        users.forEach { u -> repeat(2) { seatWithExpiredHold(u) } }
        val targets = users.associateWith { seat() }
        val stop = AtomicBoolean(false)
        val ticker = pool.submit<Unit> { while (!stop.get()) expireHolds.expire() }
        val results = pool.together(users.size) { i -> tryHold(targets.getValue(users[i]), users[i]) }
        stop.set(true)
        ticker.within(30)
        expireHolds.expire()
        assertEquals(List(users.size) { "ok" }, results)
        users.forEach { u -> assertEquals(1, cnt(u), "사용자 $u cnt = 새 홀드 1") }
        assertEquals(0, jdbcTemplate.expiredHoldRows())
        assertCounterConsistent("회수 ∥ 배치")
    }
}
