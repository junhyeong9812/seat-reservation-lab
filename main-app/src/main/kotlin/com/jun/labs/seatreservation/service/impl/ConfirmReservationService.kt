package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.Reservation
import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.domain.repository.ReservationRepository
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationResult
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.drift.MemoryChangedPairs
import com.jun.labs.seatreservation.service.impl.expiry.LazyConfirmProcess
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * ADR-000 기준선: 좌석 애그리거트의 확정(2중 확인 포함) + 예약 생성을 같은 트랜잭션에서 한다
 * — 애그리거트 2개를 한 트랜잭션에서 바꾸는 의도된 예외(ADR-000 §2.1).
 * 확인과 전환 사이에 상태가 바뀔 수 있다 — ADR-015(확정 원자성)에서 관측(번호 이동 전 ADR-005).
 * ADR-014: 만료 방식이 sweep(A)이 아니면 B·C·D 확정([LazyConfirmProcess] — 좌석 잠금 뒤 DB now()로 판정)으로 간다. A 확정은 바꾸지 않는다(Q3).
 */
@Service
class ConfirmReservationService(
    private val productSeatRepository: ProductSeatRepository,
    private val reservationRepository: ReservationRepository,
    private val clock: Clock,
    private val userLimit: ActiveUserLimit,
    private val changes: MemoryChangedPairs,
    private val properties: SeatHoldProperties,
    private val lazyConfirm: LazyConfirmProcess,
) : ConfirmReservationUseCase {

    @Transactional
    override fun confirm(command: ConfirmReservationCommand): ConfirmReservationResult {
        if (properties.expiryMode != ExpiryMode.SWEEP) return lazyConfirm.confirm(command)
        // ADR-009 L2-4: counter-trigger만 트리거 기준 시각(앱 Clock)을 이 트랜잭션에 넣는다 — 다른 매수 방식은 아무 문장도 보내지 않는다
        userLimit.strategy.beforeConfirm()
        val now = clock.instant()
        val seat = productSeatRepository.findByHoldsId(command.holdId)
            ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)

        seat.confirm(command.holdId, command.userId, now)

        val reservation = reservationRepository.save(
            Reservation(
                scheduleId = seat.scheduleId,
                seatId = seat.id!!,
                userId = command.userId,
                paymentUid = command.paymentUid,
                status = ReservationStatus.CONFIRMED,
                createdAt = now,
            ),
        )
        // ADR-012 ⓐ: 확정은 실제 값 쪽만 바꾼다(쿼터 행 그대로) — 앱 경로라 그 쌍을 등록한다(§2.3 '앱 경로면 봄'). method ≠ memory면 아무것도 안 함
        changes.changed(seat.scheduleId, command.userId)
        return ConfirmReservationResult(
            reservationId = reservation.id!!,
            seatId = seat.id!!,
            status = reservation.status,
        )
    }
}
