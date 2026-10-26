package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.drift.QuotaCleanup
import com.jun.labs.seatreservation.support.IntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-014 B·C·D 경로(§설계-3.1~3.4) 구현자 테스트 — 방식마다 같은 시나리오를 돌리고, 방식별로 다른 기대값은 [mode]로 가른다.
 * N1(DB 시각) · N3(회수 원자성) · N4(회수 뒤 매수 판정) · N8(만료 뒤 확정 거부) · DS14-1(확정 판정 순서) · Q4(남의 만료 홀드는 남긴다) · 좌석 맵 · 판정기 · 배치 · 정리 ①.
 */
abstract class Adr014ImplPathsTest : Adr014ImplSupport() {

    @Autowired lateinit var meterRegistry: MeterRegistry

    @Test
    fun `선점은 DB 시각으로 쓴다 — held_at은 DB now, expires_at - held_at = TTL, 응답 expiresAt = 행 값(N1)`() {
        val seat = seat()
        val before = dbNow()
        val r = hold(seat, 1)
        val row = holdRow(r.holdId)
        assertTrue(abs(Duration.between(before, row.heldAt).toMillis()) < 5_000, "held_at은 DB 시각이어야 한다(앱 Clock ${clock.instant()}): ${row.heldAt}")
        assertEquals(properties.ttl, Duration.between(row.heldAt, row.expiresAt))
        assertEquals(row.expiresAt, r.expiresAt)
        assertEquals(1, cntOf(s, 1))
        assertEquals(if (mode.storesHeld) "HELD" else "AVAILABLE", status(seat))
    }

    @Test
    fun `앱 Clock을 옮겨도 판정이 같다 — 1시간 뒤로 옮겨도 남은 선점·확정은 DB 기준 활성(N1)`() {
        val seat = seat()
        val r = hold(seat, 1)
        clock.advance(Duration.ofHours(1))
        assertEquals("SEAT_NOT_AVAILABLE", holdCode(seat, 2), "DB 기준 활성 홀드가 있는 좌석")
        assertEquals("ok", confirmCode(r.holdId, 1))
        assertEquals("RESERVED", status(seat))
        assertEquals(0, holdRows(seat))
        assertEquals(1, reservations(seat))
        assertEquals(1, cntOf(s, 1), "확정은 쿼터 행 그대로(홀드 → 예약)")
    }

    @Test
    fun `만료 뒤 확정은 409 HOLD_EXPIRED — 홀드 행·cnt 그대로(N8)`() {
        val seat = seat()
        val h = dbHold(seat, 1, -1.0)
        markHeld(seat)
        quota(s, 1, 1)
        assertEquals("HOLD_EXPIRED", confirmCode(h, 1))
        assertTrue(holdExistsRow(h))
        assertEquals(1, cntOf(s, 1))
        assertEquals(0, reservations(seat))
    }

    @Test
    fun `자기 만료 홀드 좌석을 다시 잡는다 — 회수가 옛 행을 지우고 cnt 1(PR-1 · 가설 2)`() {
        val seat = seat()
        val old = dbHold(seat, 1, -1.0)
        markHeld(seat)
        quota(s, 1, 1)
        val r = hold(seat, 1)
        assertFalse(holdExistsRow(old))
        assertEquals(1, holdRows(seat))
        assertEquals(1, cntOf(s, 1))
        assertEquals(if (mode.storesHeld) "HELD" else "AVAILABLE", status(seat))
        assertEquals("ok", confirmCode(r.holdId, 1))
        assertEquals(0L, mismatch())
    }

    @Test
    fun `만료 홀드 2개로 한도가 찬 사용자도 회수 뒤 통과한다(N4 · PR-2)`() {
        val a = seat()
        val b = seat()
        val c = seat()
        dbHold(a, 1, -1.0)
        dbHold(b, 1, -2.0)
        markHeld(a)
        markHeld(b)
        quota(s, 1, 2)
        assertEquals("ok", holdCode(c, 1))
        assertEquals(1, cntOf(s, 1))
        assertEquals(0, holdRows(a) + holdRows(b))
        // C만 회수한 다른 좌석의 거짓 HELD를 고친다(H4b) · B는 거짓 HELD가 남는다 · D는 HELD를 쓰지 않는다
        val expected = when (mode) {
            ExpiryMode.LAZY -> "HELD"
            else -> "AVAILABLE"
        }
        assertEquals(listOf(expected, expected), listOf(status(a), status(b)))
        assertEquals(0L, mismatch())
        assertTrue(meterRegistry.find("seat.hold.reclaim").timer()!!.count() >= 1, "회수 지표(H3~H5 시간)")
    }

    @Test
    fun `회수 뒤 매수 초과로 지면 회수도 롤백된다 — 만료 행·cnt 그대로(N3)`() {
        val a = seat()
        val b = seat()
        val c = seat()
        val d = seat()
        val expired = dbHold(a, 1, -1.0)
        dbHold(b, 1, 300.0)
        dbHold(c, 1, 300.0)
        listOf(a, b, c).forEach(::markHeld)
        quota(s, 1, 3)
        assertEquals("HOLD_LIMIT_EXCEEDED", holdCode(d, 1))
        assertTrue(holdExistsRow(expired), "회수가 롤백돼야 한다")
        assertEquals(3, cntOf(s, 1))
    }

    @Test
    fun `회수 뒤 NOWAIT로 지면 회수도 롤백된다 — 409 표지는 nowait(N3 · M3)`() {
        val a = seat()
        val d = seat()
        val expired = dbHold(a, 1, -1.0)
        markHeld(a)
        quota(s, 1, 1)
        rawConnection().use { other ->
            other.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", d)
            assertEquals("SEAT_NOT_AVAILABLE/nowait", holdCode(d, 1))
            other.rollback()
        }
        assertTrue(holdExistsRow(expired))
        assertEquals(1, cntOf(s, 1))
    }

    @Test
    fun `남의 만료 홀드 위 재선점 — 남의 행·카운터는 그대로, 그 사용자의 다음 선점이 회수한다(Q4)`() {
        val seat = seat()
        val other = seat()
        val xHold = dbHold(seat, 1, -1.0)
        markHeld(seat)
        quota(s, 1, 1)
        assertEquals("ok", holdCode(seat, 2))
        assertTrue(holdExistsRow(xHold), "남의 만료 행은 지우지 않는다")
        assertEquals(1, cntOf(s, 1))
        assertEquals(1, cntOf(s, 2))
        assertEquals(1, activeRows(seat))
        assertEquals(0L, mismatch())
        assertEquals("ok", holdCode(other, 1))
        assertFalse(holdExistsRow(xHold))
        assertEquals(1, cntOf(s, 1))
        assertEquals(if (mode.storesHeld) "HELD" else "AVAILABLE", status(seat), "회수는 대상이 아닌 좌석의 활성 홀드 좌석을 되돌리지 않는다")
    }

    @Test
    fun `확정 판정 순서 — 좌석이 RESERVED면 내 홀드가 활성이어도 SEAT_NOT_HELD(DS14-1)`() {
        val seat = seat()
        val mine = dbHold(seat, 1, 300.0)
        quota(s, 1, 1)
        jdbcTemplate.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", seat)
        assertEquals("SEAT_NOT_HELD", confirmCode(mine, 1))
    }

    @Test
    fun `같은 좌석에 다른 활성 홀드가 있으면 HOLD_EXPIRED — 시각 역전을 행으로 막는다(K3)`() {
        val seat = seat()
        val mine = dbHold(seat, 1, 300.0)
        dbHold(seat, 2, 300.0)
        markHeld(seat)
        quota(s, 1, 1)
        quota(s, 2, 1)
        assertEquals("HOLD_EXPIRED", confirmCode(mine, 1))
        assertEquals(0, reservations(seat))
    }

    @Test
    fun `확정 404·403 — 없는 홀드 · 남의 홀드`() {
        val seat = seat()
        val r = hold(seat, 1)
        assertEquals("HOLD_NOT_FOUND", confirmCode(r.holdId + 1_000, 1))
        assertEquals("HOLD_NOT_OWNED", confirmCode(r.holdId, 2))
    }

    @Test
    fun `좌석 맵 — 방식별 보이는 상태(§3_3)`() {
        val reserved = seat(SeatStatus.RESERVED)
        val availableActive = seat()
        dbHold(availableActive, 1, 300.0)
        val heldActive = seat(SeatStatus.HELD)
        dbHold(heldActive, 2, 300.0)
        val heldExpired = seat(SeatStatus.HELD)
        dbHold(heldExpired, 3, -1.0)
        val free = seat()
        val shown = seatMap.seats(s).associate { it.seatId to it.status.name }
        val expected = when (mode) {
            // B·C 빠른 길: HELD 행만 홀드를 본다 — 저장 AVAILABLE의 활성 홀드는 AVAILABLE로 보인다(DS14-2 전제 · 방식 전환 정리 몫)
            ExpiryMode.LAZY, ExpiryMode.LAZY_SWEEP -> listOf("RESERVED", "AVAILABLE", "HELD", "AVAILABLE", "AVAILABLE")
            ExpiryMode.DERIVED -> listOf("RESERVED", "HELD", "HELD", "AVAILABLE", "AVAILABLE")
            ExpiryMode.SWEEP -> error("sweep은 Adr014ImplSweepTest")
        }
        assertEquals(expected, listOf(reserved, availableActive, heldActive, heldExpired, free).map { shown.getValue(it) })
        assertEquals(5, shown.size)
    }

    @Test
    fun `판정기 방식별 항목 — 남은 만료 행 · HELD인데 활성 없음 · 활성인데 HELD 아님 · 좌석당 활성 2 · 이중 판매(§8_3)`() {
        val heldExpired = seat(SeatStatus.HELD)
        dbHold(heldExpired, 1, -1.0)
        val availableActive = seat()
        dbHold(availableActive, 2, 300.0)
        val twoActive = seat(SeatStatus.HELD)
        dbHold(twoActive, 3, 300.0)
        dbHold(twoActive, 4, 300.0)
        val r = judge()
        assertEquals(mode.name.lowercase().replace('_', '-'), r["expiry_mode"])
        assertEquals(1L, (r["expired_hold_rows"] as Number).toLong())
        assertEquals(1L, (r["held_no_active"] as Number).toLong())
        assertEquals(1L, (r["active_not_held"] as Number).toLong())
        assertEquals(1L, (r["multi_active_seats"] as Number).toLong())
        assertEquals(1L, (r["double_sold_seats"] as Number).toLong())
        assertEquals(0L, (r["active_on_reserved_seats"] as Number).toLong())
    }

    @Test
    fun `만료 배치 — B는 할 일 없음 · C·D는 만료 행을 지우고 지운 만큼 cnt를 내린다(C는 좌석도 되돌린다 · D는 좌석 행을 쓰지 않는다)`() {
        val seats = (1..5).map { seat() }
        seats.forEach { dbHold(it, 1, -1.0); markHeld(it) }
        quota(s, 1, 5)
        val live = seat()
        dbHold(live, 2, 300.0)
        markHeld(live)
        quota(s, 2, 1)
        val xmins = seats.map(::seatXmin)
        val n = expireHolds.expire()
        if (mode == ExpiryMode.LAZY) {
            assertEquals(0, n)
            assertEquals(5, seats.sumOf(::holdRows))
            return
        }
        assertEquals(5, n, "청크 2 — 진행형 커서로 한 틱에 모두")
        assertEquals(0, seats.sumOf(::holdRows))
        assertEquals(0, cntOf(s, 1))
        assertEquals(1, cntOf(s, 2))
        assertEquals(1, holdRows(live))
        if (mode == ExpiryMode.DERIVED) assertEquals(xmins, seats.map(::seatXmin), "D 배치는 좌석 행을 쓰지 않는다(N6)")
        else assertEquals(List(5) { "AVAILABLE" }, seats.map(::status))
        assertEquals("HELD".takeIf { mode.storesHeld } ?: "AVAILABLE", status(live))
        assertEquals(0L, mismatch())
    }

    @Test
    fun `만료 배치 — 쿼터 행이 없는 쌍(MISSING)은 지우기 앞에서 복구한다(C3b · D3)`() {
        assumeTrue(mode != ExpiryMode.LAZY)
        val a = seat()
        val b = seat()
        dbHold(a, 1, -1.0)
        dbHold(b, 1, -1.0)
        markHeld(a)
        markHeld(b)
        assertEquals(2, expireHolds.expire())
        assertEquals(0, cntOf(s, 1), "행을 만들고(지울 홀드 제외 실제 값 0) 감소하지 않는다")
        assertEquals(listOf(listOf<Any?>(1L, "missing", "repaired", null, "expiry-repair")), driftLog())
        assertEquals(0L, mismatch())
    }

    @Test
    fun `만료 배치 — 가드 감소가 0행이면(cnt가 작다) 그 자리에서 복구한다(DS14-7)`() {
        assumeTrue(mode != ExpiryMode.LAZY)
        val a = seat()
        val b = seat()
        val c = seat()
        dbHold(a, 1, -1.0)
        dbHold(b, 1, -1.0)
        dbHold(c, 1, 300.0)
        listOf(a, b, c).forEach(::markHeld)
        quota(s, 1, 1) // 실제 3인데 1 — below
        expireHolds.expire()
        assertEquals(1, cntOf(s, 1), "남은 활성 1")
        assertEquals(listOf("below"), driftLog().map { it[1] })
        assertEquals(0L, mismatch())
    }

    @Test
    fun `만료 배치 — 잠긴 좌석은 건너뛰고(SKIP LOCKED) 커서가 넘어가 뒤 후보를 계속한다 · 다음 틱이 남은 것을 고른다(DS14-5)`() {
        assumeTrue(mode == ExpiryMode.LAZY_SWEEP)
        val seats = (1..5).map { seat() }
        seats.forEach { dbHold(it, 1, -1.0); markHeld(it) }
        quota(s, 1, 5)
        rawConnection().use { other ->
            other.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", seats[0])
            assertEquals(4, expireHolds.expire())
            other.rollback()
        }
        assertEquals(1, holdRows(seats[0]))
        assertEquals(1, cntOf(s, 1))
        assertEquals("HELD", status(seats[0]))
        assertEquals(1, expireHolds.expire())
        assertEquals(0, cntOf(s, 1))
        assertEquals("AVAILABLE", status(seats[0]))
    }

    @Test
    fun `정리 ① — B는 마감 회차의 만료 홀드 행을 지우고 쿼터 행도 지운다 · 활성 홀드가 있으면 건너뛴다(B1~B3)`() {
        assumeTrue(mode == ExpiryMode.LAZY)
        val closed = newSchedule(IntegrationTest.NOW.minusSeconds(60)).id!!
        val closedWithActive = newSchedule(IntegrationTest.NOW.minusSeconds(60)).id!!
        val a = seat(SeatStatus.HELD, closed)
        dbHold(a, 1, -1.0, closed)
        quota(closed, 1, 1)
        val b = seat(SeatStatus.HELD, closedWithActive)
        dbHold(b, 1, 300.0, closedWithActive)
        quota(closedWithActive, 1, 1)
        val cleanup = QuotaCleanup(
            jdbcTemplate, tx,
            properties.copy(counterDrift = properties.counterDrift.copy(cleanup = SeatHoldProperties.Cleanup(mode = SeatHoldProperties.CleanupMode.SCHEDULE))),
            meterRegistry, clock,
        )
        val run = cleanup.runOnce()
        assertEquals(1, run.rowsDeleted)
        assertEquals(0, holdRows(a))
        assertEquals(null, cntOf(closed, 1))
        assertEquals(1, holdRows(b))
        assertEquals(1, cntOf(closedWithActive, 1))
        assertEquals(1, run.skippedWithHolds)
    }

    @Test
    fun `정상 확정 — 좌석 RESERVED · 홀드 삭제 · 예약 1 · cnt 그대로`() {
        val seat = seat()
        val r = hold(seat, 1)
        val c = confirm(r.holdId, 1)
        assertEquals(seat, c.seatId)
        assertEquals("RESERVED", status(seat))
        assertEquals(0, holdRows(seat))
        assertEquals(1, reservations(seat))
        assertEquals(1, cntOf(s, 1))
        assertEquals("SEAT_NOT_AVAILABLE", holdCode(seat, 2))
        val created = jdbcTemplate.queryForObject("SELECT created_at FROM reservation WHERE seat_id = ?", Timestamp::class.java, seat)!!.toInstant()
        assertEquals(clock.instant(), created, "예약 created_at은 앱 Clock 그대로(만료 판정이 아니다)")
    }

    fun dbNow(): Instant = jdbcTemplate.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)!!.toInstant()
}

@LazyMode
class Adr014ImplPathsLazyTest : Adr014ImplPathsTest()

@LazySweepMode
class Adr014ImplPathsLazySweepTest : Adr014ImplPathsTest()

@DerivedMode
class Adr014ImplPathsDerivedTest : Adr014ImplPathsTest()
