package com.jun.labs.seatreservation.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "reservation")
class Reservation(
    val scheduleId: Long,
    val seatId: Long,
    val userId: Long,
    val paymentUid: String,
    @Enumerated(EnumType.STRING)
    var status: ReservationStatus,
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
