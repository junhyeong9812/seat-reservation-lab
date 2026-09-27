package com.jun.labs.seatreservation.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

/**
 * Seat 애그리거트 루트. 좌석 상태와 현재 홀드를 함께 소유하고, 상태 검사와 전이는 이 클래스의 메서드로만 일어난다.
 *
 * 불변식(ADR-000 §2.1): 전이는 AVAILABLE → HELD → RESERVED, 만료 시 HELD → AVAILABLE.
 * HELD이면 홀드가 정확히 1개 — 단, 기준선은 이를 DB로 강제하지 않는다. 동시 요청에서 깨지는지는 ADR-002에서 관측한다.
 */
@Entity
@Table(name = "product_seat")
class ProductSeat(
    val scheduleId: Long,
    val section: String,
    val rowNo: Int,
    val seatNo: Int,
    status: SeatStatus = SeatStatus.AVAILABLE,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    var status: SeatStatus = status
        protected set

    // 좌석당 홀드는 1개가 불변식이지만 DB가 막지 않으므로 컬렉션으로 매핑한다 — 중복이 생기면 그대로 드러나게.
    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "seat_id", nullable = false)
    private val holds: MutableList<SeatHold> = mutableListOf()

    fun assertHoldable() {
        if (status != SeatStatus.AVAILABLE) {
            throw SeatReservationException(ErrorCode.SEAT_NOT_AVAILABLE)
        }
    }

    fun hold(userId: Long, now: Instant, ttl: Duration): SeatHold {
        assertHoldable()
        status = SeatStatus.HELD
        val hold = SeatHold(scheduleId = scheduleId, userId = userId, heldAt = now, expiresAt = now.plus(ttl))
        holds.add(hold)
        return hold
    }

    /** 결제 성공 후 확정. 2중 확인: 내 홀드이고 만료 전인가 → 좌석이 HELD인가. */
    fun confirm(holdId: Long, userId: Long, now: Instant) {
        val hold = holds.find { it.id == holdId }
            ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if (hold.userId != userId) {
            throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        }
        if (hold.isExpired(now)) {
            throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        }
        if (status != SeatStatus.HELD) {
            throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
        }
        status = SeatStatus.RESERVED
        holds.remove(hold)
    }

    /** 만료된 홀드만 제거하고, 좌석이 HELD면 AVAILABLE로 되돌린다. 제거한 홀드 수를 돌려준다. */
    fun expireHolds(now: Instant): Int {
        val expired = holds.filter { it.isExpired(now) }
        if (expired.isEmpty()) {
            return 0
        }
        holds.removeAll(expired)
        if (status == SeatStatus.HELD) {
            status = SeatStatus.AVAILABLE
        }
        return expired.size
    }
}
