package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.service.impl.expiry.SeatMapEntry
import com.jun.labs.seatreservation.service.impl.expiry.SeatMapQuery
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** ADR-014 좌석 맵(§설계-3.3 · Q11) — 페이지 없음(회차당 1만 석) · 보이는 상태만. 모든 만료 방식에서 연다. */
@RestController
// F(reconciler 프로필 — 대조 전용 프로세스)는 업무 요청을 받지 않는다(코드 루프 1 L1-5)
@Profile("!reconciler")
class SeatMapController(
    private val seatMap: SeatMapQuery,
) {

    @GetMapping("/api/schedules/{scheduleId}/seats")
    fun seats(@PathVariable scheduleId: Long): List<SeatMapEntry> = seatMap.seats(scheduleId)
}
