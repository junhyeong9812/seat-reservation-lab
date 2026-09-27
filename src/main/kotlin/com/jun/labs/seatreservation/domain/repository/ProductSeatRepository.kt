package com.jun.labs.seatreservation.domain.repository

import com.jun.labs.seatreservation.domain.ProductSeat
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface ProductSeatRepository : JpaRepository<ProductSeat, Long> {
    fun findByIdAndScheduleId(id: Long, scheduleId: Long): ProductSeat?

    fun findByHoldsId(holdId: Long): ProductSeat?

    fun findDistinctByHoldsExpiresAtLessThanEqual(now: Instant): List<ProductSeat>

    /** (회차, 사용자)가 가진 홀드 수 — 좌석·홀드 조인 행 수. */
    fun countByScheduleIdAndHoldsUserId(scheduleId: Long, userId: Long): Long
}
