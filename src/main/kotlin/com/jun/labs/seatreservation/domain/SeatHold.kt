package com.jun.labs.seatreservation.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Seat 애그리거트의 내부 엔티티. 상태 컬럼 없음 — 살아 있는 홀드만 행으로 존재하고,
 * 확정·만료되면 좌석(루트)이 제거한다 (ADR-000 §2.1).
 */
@Entity
@Table(name = "seat_hold")
class SeatHold(
    val scheduleId: Long,
    val userId: Long,
    val heldAt: Instant,
    val expiresAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** 만료 정각에는 이미 만료다. */
    fun isExpired(now: Instant): Boolean = !expiresAt.isAfter(now)
}
