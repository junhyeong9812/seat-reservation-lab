package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.domain.SeatStatus
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * ADR-014 B(lazy — HELD 저장 + 조회 시 만료 판정) 계약(blind). 공통 계약은 [Adr014ModeContractBase].
 * B만의 것: 주기 배치 없음(§3.4 — expire()는 0 · E5) · 회수는 홀드 행과 카운터만 고치고 좌석 행은 쓰지 않는다(§3.4 · §8.3 'HELD인데 홀드 행 없음 > 0 가능') ·
 * 선점은 힌트 HELD를 쓴다(H10). 정리 ①(마감 회차)은 Adr014LazyCleanupContractTest.
 */
// 메인 수정(코드 리뷰 CR14-1): B는 정리 ①(cleanup.mode=schedule)이 켜져 있어야 뜬다 — IntegrationTest의 정리 끔 고정을 덮는다(주기 1시간 — 배경 정리가 테스트 데이터를 고치지 않게)
@TestPropertySource(properties = ["$EXPIRY_MODE=lazy", "seat.hold.counter-drift.cleanup.mode=schedule", "seat.hold.counter-drift.cleanup.interval=1h"])
class Adr014LazyContractTest : Adr014ModeContractBase() {

    override val modeValue = "lazy"
    override val storedHeld = SeatStatus.HELD
    override val expiredOnAvailableSeatCode = "SEAT_NOT_HELD"

    @Test
    fun `B — 주기 배치가 없다 — expire()는 0을 돌려주고 만료 홀드·좌석·카운터를 바꾸지 않는다(앱 시계를 미래로 옮겨도)`() {
        val (seat1, h1) = seatWithExpiredHold(100)
        val (seat2, h2) = seatWithActiveHold(200)
        clock.set(CLOCK_FUTURE)
        assertEquals(0, expireHolds.expire(), "B의 만료 유스케이스는 할 일이 없다(E5)")
        assertTrue(jdbcTemplate.holdRowExists(h1) && jdbcTemplate.holdRowExists(h2), "홀드 행 그대로")
        assertEquals(listOf("HELD", "HELD"), listOf(jdbcTemplate.storedStatus(seat1), jdbcTemplate.storedStatus(seat2)))
        assertEquals(listOf(1, 1), listOf(cnt(100), cnt(200)))
    }

    @Test
    fun `B — 선점은 힌트 HELD를 쓴다(H10) · 남의 만료 홀드 좌석 재선점은 이미 HELD라 그대로`() {
        val free = seat()
        assertEquals("ok", tryHold(free, 100))
        assertEquals("HELD", jdbcTemplate.storedStatus(free), "H10 — AVAILABLE이면 HELD")
        val (stale, oldHold) = seatWithExpiredHold(200)
        assertEquals("ok", tryHold(stale, 300))
        assertEquals("HELD", jdbcTemplate.storedStatus(stale))
        assertTrue(jdbcTemplate.holdRowExists(oldHold), "남의 만료 홀드 행은 남긴다(Q4 — 남의 쿼터 행을 잡지 않는다)")
        assertEquals(1, cnt(200), "남의 cnt는 건드리지 않는다")
        assertCounterConsistent("B 재선점")
    }

    @Test
    fun `B — 회수는 다른 좌석의 저장 HELD를 고치지 않는다(거짓 HELD 남음) · 그래도 좌석 맵은 AVAILABLE · 누구나 다시 잡는다`() {
        val (other, expired) = seatWithExpiredHold(100)
        assertEquals("ok", tryHold(seat(), 100), "다른 좌석 선점 → 회수")
        assertFalse(jdbcTemplate.holdRowExists(expired))
        assertEquals("HELD", jdbcTemplate.storedStatus(other), "B 회수는 좌석 행을 쓰지 않는다(H4b는 C만)")
        assertEquals("AVAILABLE", seatMap()[other], "거짓 HELD는 좌석 맵에서 AVAILABLE(B 빠른 길)")
        assertEquals("ok", tryHold(other, 400), "비어 있음 = RESERVED 아님 ∧ 활성 홀드 없음 — 저장 HELD를 판정에 쓰지 않는다(§2.1)")
        assertCounterConsistent("B 회수")
    }
}
