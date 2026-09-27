package com.jun.labs.seatreservation.domain.repository

import com.jun.labs.seatreservation.domain.Reservation
import com.jun.labs.seatreservation.domain.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ReservationRepository : JpaRepository<Reservation, Long> {
    fun countByScheduleIdAndUserIdAndStatus(scheduleId: Long, userId: Long, status: ReservationStatus): Long
}
