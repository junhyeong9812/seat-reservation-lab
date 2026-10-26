package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.incremental.awaitBlockedBy
import com.jun.labs.seatreservation.incremental.exec
import com.jun.labs.seatreservation.incremental.pid
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * ADR-014 D(derived — 좌석엔 영속 사실만 · 홀드는 유도) 계약(blind). 공통 계약은 [Adr014ModeContractBase].
 * D만의 것: 좌석 status는 AVAILABLE·RESERVED만 쓴다(옛 HELD는 '예약 아님'으로 읽는다 — §설계-3 표) · 선점은 좌석을 잠그기만 한다(H10 없음) ·
 * 배치 D1~D5는 행 + 카운터만 · 좌석 문장 없음(§3.4 · N6) · D4가 확정 K3이 쥔 홀드 행을 기다려도 고리 없음 · 이중 감소 없음(§3.4 · §설계-10 #12) ·
 * §설계-10 #2 재현(RC에서 잠금과 하위 질의를 한 문장으로 하면 막 커밋된 활성 홀드를 못 본다 — D 후보 (가)의 '잠근 뒤 새 문장' 근거).
 */
@TestPropertySource(properties = ["$EXPIRY_MODE=derived"])
class Adr014DerivedContractTest : Adr014ModeContractBase() {

    override val modeValue = "derived"
    override val storedHeld = SeatStatus.AVAILABLE
    override val expiredOnAvailableSeatCode = "HOLD_EXPIRED"

    @Test
    fun `D — 선점은 좌석 행을 쓰지 않는다(AVAILABLE 그대로 · xmin 무변경) · 확정은 RESERVED를 쓴다`() {
        val seat = seat()
        val xmin = jdbcTemplate.seatXmin(seat)
        val r = hold(seat, 100)
        assertEquals("AVAILABLE", jdbcTemplate.storedStatus(seat), "D는 HELD를 쓰지 않는다")
        assertEquals(xmin, jdbcTemplate.seatXmin(seat), "D 선점은 좌석 행을 잠그기만 한다(UPDATE 없음)")
        assertEquals("HELD", seatMap()[seat], "보이는 상태는 활성 홀드로 유도")
        assertEquals("ok", tryConfirm(r.holdId, 100))
        assertEquals("RESERVED", jdbcTemplate.storedStatus(seat))
        assertCounterConsistent("D 선점·확정")
    }

    @Test
    fun `D — 옛 데이터 HELD 좌석은 '예약 아님'으로 읽는다(활성 홀드 없으면 선점 가능 · 있으면 409)`() {
        val legacyFree = seat(SeatStatus.HELD)
        val legacyTaken = seat(SeatStatus.HELD)
        jdbcTemplate.dbHold(legacyTaken, s, 900, "5 minutes")
        assertEquals("ok", tryHold(legacyFree, 100))
        assertEquals("SEAT_NOT_AVAILABLE", tryHold(legacyTaken, 200))
        assertEquals("HELD", seatMap()[legacyTaken])
        assertCounterConsistent("D 옛 HELD")
    }

    @Test
    fun `D 배치 — 만료 행 삭제 · 지운 만큼 감소 · 좌석 행은 쓰지 않는다(xmin 무변경 · 옛 HELD도 그대로) · 판정은 DB now()`() {
        val (s1, h1) = seatWithExpiredHold(100)
        val s2 = seat(SeatStatus.HELD)
        val h2 = jdbcTemplate.dbHold(s2, s, 200, "-1 minute")
        val (s3, h3) = seatWithActiveHold(300)
        val xmins = listOf(s1, s2, s3).map { jdbcTemplate.seatXmin(it) }
        clock.set(CLOCK_PAST)
        expireHolds.expire()
        assertFalse(jdbcTemplate.holdRowExists(h1) || jdbcTemplate.holdRowExists(h2), "만료 행 삭제(앱 시계 과거여도 — N1)")
        assertTrue(jdbcTemplate.holdRowExists(h3))
        assertEquals(listOf(0, 0, 1), listOf(100L, 200L, 300L).map { cnt(it) })
        clock.set(CLOCK_FUTURE)
        expireHolds.expire()
        assertTrue(jdbcTemplate.holdRowExists(h3), "앱 시계 미래여도 DB 기준 활성 행은 남는다(N1)")
        assertEquals(xmins, listOf(s1, s2, s3).map { jdbcTemplate.seatXmin(it) }, "D 배치는 좌석 행을 쓰지 않는다(N6)")
        assertEquals(listOf("AVAILABLE", "HELD", "AVAILABLE"), listOf(s1, s2, s3).map { jdbcTemplate.storedStatus(it) })
        assertEquals(mapOf(s1 to "AVAILABLE", s2 to "AVAILABLE", s3 to "HELD"), seatMap())
        assertCounterConsistent("D 배치")
    }

    @Test
    fun `D 배치 — 좌석 잠금과 무관하다(잠긴 좌석의 만료 행도 기다리지 않고 지운다 — 좌석을 잠그지 않는다)`() {
        val (locked, h) = seatWithExpiredHold(100)
        raw().use { r ->
            r.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", locked)
            pool.submit<Int> { expireHolds.expire() }.within(10)
            assertFalse(jdbcTemplate.holdRowExists(h), "D 배치는 좌석을 잠그지 않으므로 잠긴 좌석의 만료 행도 지운다")
            assertEquals(0, cnt(100))
            r.rollback()
        }
        assertCounterConsistent("D 배치 · 잠긴 좌석")
    }

    @Test
    fun `D4 ↔ 확정 K3 — 배치 삭제가 확정이 쥔 홀드 행을 기다린다 · 확정 성공이면 그 행은 감소하지 않는다(이중 감소 0 · 고리 없음)`() {
        val (seat, h) = seatWithExpiredHold(100)
        raw().use { k ->
            k.exec("SELECT id FROM seat_hold WHERE id = ? FOR UPDATE", h)
            val tick = pool.submit<Int> { expireHolds.expire() }
            jdbcTemplate.awaitBlockedBy(k.pid(), 1, Duration.ofSeconds(10))
            k.exec("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", seat)
            k.exec("DELETE FROM seat_hold WHERE id = ?", h)
            k.exec(
                "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, 'pay-k', 'CONFIRMED', now())",
                s, seat, 100L,
            )
            k.commit()
            tick.within(15)
        }
        assertEquals(1, cnt(100), "확정 1 — 확정이 지운 행을 배치가 다시 빼지 않는다(규칙 ②)")
        assertCounterConsistent("D4 ↔ K3")
        assertNoDoubleSale("D4 ↔ K3")
    }

    @Test
    fun `N5 — D 회수와 D 배치가 동시에 돌아도 cnt는 정확히 한 번 내려간다`() {
        val users = (1..20).map { 40_000L + it }
        users.forEach { u -> repeat(2) { seatWithExpiredHold(u) } }
        val targets = users.associateWith { seat() }
        val stop = AtomicBoolean(false)
        val ticker = pool.submit<Unit> { while (!stop.get()) expireHolds.expire() }
        val results = pool.together(users.size) { i -> tryHold(targets.getValue(users[i]), users[i]) }
        stop.set(true)
        ticker.within(30)
        expireHolds.expire()
        assertEquals(List(users.size) { "ok" }, results)
        users.forEach { u -> assertEquals(1, cnt(u), "사용자 $u cnt") }
        assertEquals(0, jdbcTemplate.expiredHoldRows())
        assertCounterConsistent("D 회수 ∥ 배치")
    }

    @Test
    fun `설계-10 #2 재현 — RC에서 FOR UPDATE와 같은 문장의 하위 질의는 막 커밋된 홀드를 못 본다 · 잠근 뒤 새 문장은 본다(PostgreSQL 동작 — 구현 무관)`() {
        val seat = seat()
        raw().use { a ->
            raw().use { b ->
                a.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", seat)
                a.exec(
                    "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, 1, now(), now() + interval '5 minutes')",
                    seat, s,
                )
                val oneStatement = pool.submit<Boolean> {
                    b.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now()) FROM product_seat st WHERE st.id = ? FOR UPDATE",
                    ).use { ps -> ps.setLong(1, seat); ps.executeQuery().use { it.next(); it.getBoolean(1) } }
                }
                jdbcTemplate.awaitBlockedBy(a.pid(), 1, Duration.ofSeconds(10))
                a.commit()
                assertFalse(oneStatement.within(), "한 문장(잠금 + 하위 질의)은 문장 시작 스냅숏 — 막 커밋된 활성 홀드를 못 본다(§2.3)")
                val newStatement = b.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = ? AND h.expires_at > now())",
                ).use { ps -> ps.setLong(1, seat); ps.executeQuery().use { it.next(); it.getBoolean(1) } }
                assertTrue(newStatement, "잠근 뒤 새 문장(RC 새 스냅숏)은 본다 — H8 · K3 · C6을 새 문장으로 나눈 근거")
                b.rollback()
            }
        }
    }
}
