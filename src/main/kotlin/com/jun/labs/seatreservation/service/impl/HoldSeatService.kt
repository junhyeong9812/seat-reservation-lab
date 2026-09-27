package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.HoldLimitPolicy
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * ADR-000 기준선: 좌석 애그리거트 로드 → 매수 정책 → seat.hold() → 저장.
 * 검사는 읽어 온 스냅샷 위에서 일어나므로(check-then-act) 동시 요청은 막지 못한다 — ADR-002·003에서 관측.
 */
@Service
class HoldSeatService(
    private val productSeatRepository: ProductSeatRepository,
    private val holdLimitPolicy: HoldLimitPolicy,
    private val properties: SeatHoldProperties,
    private val clock: Clock,
) : HoldSeatUseCase {

    @Transactional
    override fun hold(command: HoldSeatCommand): HoldSeatResult {
        val seat = productSeatRepository.findByIdAndScheduleId(command.seatId, command.scheduleId)
            ?: throw SeatReservationException(ErrorCode.SEAT_NOT_FOUND)
        seat.assertHoldable() // 에러 우선순위: 선점 불가가 매수 초과보다 먼저
        holdLimitPolicy.check(command.scheduleId, command.userId, properties.maxPerUser)

        val hold = seat.hold(command.userId, clock.instant(), properties.ttl)
        // 좌석은 이미 영속 상태 — save()는 merge라 새 홀드의 복사본을 영속화한다. flush로 원본 홀드를 INSERT해 id를 받는다.
        productSeatRepository.flush()
        return HoldSeatResult(holdId = hold.id!!, seatId = seat.id!!, expiresAt = hold.expiresAt)
    }
}
