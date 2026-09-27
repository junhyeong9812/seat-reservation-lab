package com.jun.labs.seatreservation.domain.repository

import com.jun.labs.seatreservation.domain.ProductSchedule
import org.springframework.data.jpa.repository.JpaRepository

interface ProductScheduleRepository : JpaRepository<ProductSchedule, Long>
