package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.impl.ExpireHoldsService
import com.jun.labs.seatreservation.service.impl.expiry.LazyExpiryBatch
import com.jun.labs.seatreservation.service.impl.hold.sqlStateOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-014 §설계-2.4(코드 리뷰 CR14-6): `seat.hold.counter-drift.expiry-repair=false`면 B·C·D의 회수·배치도 A의 옛 경로(ExpiryCounters.legacyDecrement)처럼
 * 실패한다 — 복구하지 않고 트랜잭션을 롤백한다(회수 = 선점 실패 · 배치 = 그 청크만 롤백). 컨텍스트는 IntegrationTest의 A 끔 고정 그대로(ADR014_REPAIR_ON 없음).
 */
abstract class Adr014ImplRepairOffTest : Adr014ImplSupport() {

    @Test
    fun `회수 — 쿼터 행이 없으면(MISSING) 선점이 실패한다(업무 예외가 아니다 — 500 계열) · 홀드 행·쿼터 행·기록 그대로`() {
        val a = seat()
        val d = seat()
        val expired = dbHold(a, 1, -1.0)
        markHeld(a)
        val e = assertFailsWith<IllegalStateException> { hold(d, 1) }
        assertFalse(e is SeatReservationException)
        assertTrue(e.message!!.contains("카운터 행 없음"), e.message)
        assertTrue(holdExistsRow(expired), "회수 롤백")
        assertEquals(null, cntOf(s, 1), "복구하지 않는다(행을 만들지 않는다)")
        assertEquals(0, holdRows(d))
        assertEquals(emptyList(), driftLog())
    }

    @Test
    fun `회수 — cnt가 지운 수보다 작으면(가드 감소라면 0행) 옛 감소가 CHECK로 실패한다 · 선점 실패 · 그대로`() {
        val a = seat()
        val d = seat()
        val expired = dbHold(a, 1, -1.0)
        markHeld(a)
        quota(s, 1, 0) // 실제 1인데 0 — 옛 감소 cnt - 1 = -1
        val e = assertFailsWith<DataAccessException> { hold(d, 1) }
        assertEquals("23514", sqlStateOf(e), "V5 CHECK(cnt >= 0)")
        assertTrue(holdExistsRow(expired))
        assertEquals(0, cntOf(s, 1))
        assertEquals(0, holdRows(d))
        assertEquals(emptyList(), driftLog())
    }

    @Test
    fun `배치 — MISSING 쌍이 든 청크는 롤백되고 다음 청크는 계속한다 · 청크·틱 failed(C·D)`() {
        assumeTrue(mode != ExpiryMode.LAZY)
        val seats = (1..3).map { seat() }
        seats.forEachIndexed { i, seat -> dbHold(seat, 1L + i, -1.0); markHeld(seat) }
        quota(s, 2, 1)
        quota(s, 3, 1) // 사용자 1은 쿼터 행 없음 — 청크 2개 중 첫 청크(좌석·쌍 1·2)에 든다
        val chunkFailed = counter(LazyExpiryBatch.CHUNK_METRIC, "outcome", "failed")
        val tickFailed = counter(ExpireHoldsService.TICK_METRIC, "outcome", "failed")
        assertEquals(1, expireHolds.expire(), "둘째 청크(사용자 3)만 지운다")
        assertEquals(listOf(1, 1, 0), seats.map(::holdRows), "첫 청크는 함께 롤백 — 사용자 2의 행도 남는다")
        assertEquals(listOf(null, 1, 0), (1L..3L).map { cntOf(s, it) })
        assertEquals(1.0, counter(LazyExpiryBatch.CHUNK_METRIC, "outcome", "failed") - chunkFailed)
        assertEquals(1.0, counter(ExpireHoldsService.TICK_METRIC, "outcome", "failed") - tickFailed)
        assertEquals(emptyList(), driftLog(), "복구 기록 없음")
        if (mode == ExpiryMode.LAZY_SWEEP) assertEquals(listOf("HELD", "HELD", "AVAILABLE"), seats.map(::status), "좌석 되돌림도 롤백")
    }

    @Test
    fun `배치 — cnt가 지운 수보다 작으면 옛 감소가 CHECK로 실패해 청크가 롤백된다(C·D)`() {
        assumeTrue(mode != ExpiryMode.LAZY)
        val a = seat()
        dbHold(a, 1, -1.0)
        markHeld(a)
        quota(s, 1, 0)
        val chunkFailed = counter(LazyExpiryBatch.CHUNK_METRIC, "outcome", "failed")
        assertEquals(0, expireHolds.expire())
        assertEquals(1, holdRows(a))
        assertEquals(0, cntOf(s, 1))
        assertEquals(1.0, counter(LazyExpiryBatch.CHUNK_METRIC, "outcome", "failed") - chunkFailed)
    }
}

@TestPropertySource(properties = ["seat.hold.expiry-mode=lazy", ADR014_SMALL_CHUNK, ADR014_LAZY_CLEANUP, ADR014_LAZY_CLEANUP_IDLE])
class Adr014ImplRepairOffLazyTest : Adr014ImplRepairOffTest()

@TestPropertySource(properties = ["seat.hold.expiry-mode=lazy-sweep", ADR014_SMALL_CHUNK])
class Adr014ImplRepairOffLazySweepTest : Adr014ImplRepairOffTest()

@TestPropertySource(properties = ["seat.hold.expiry-mode=derived", ADR014_SMALL_CHUNK])
class Adr014ImplRepairOffDerivedTest : Adr014ImplRepairOffTest()
