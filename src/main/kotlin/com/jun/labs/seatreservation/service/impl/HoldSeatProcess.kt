package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.HoldLimitPolicy
import com.jun.labs.seatreservation.domain.ProductSeat
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.SeatHoldProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * ADR-000 기준선의 선점 규칙: 좌석 애그리거트 로드 → 선점 가능 확인 → 매수 정책 → seat.hold() → flush.
 * 트랜잭션은 열지 않는다(MANDATORY) — 경합 제어 방식마다 락과 트랜잭션의 앞뒤가 달라 전략이 경계를 정한다.
 * 검사는 읽어 온 스냅샷 위에서 일어나므로(check-then-act) 전략 없이는 동시 요청을 막지 못한다.
 */
@Component
class HoldSeatProcess(
    private val productSeatRepository: ProductSeatRepository,
    private val holdLimitPolicy: HoldLimitPolicy,
    private val properties: SeatHoldProperties,
    private val clock: Clock,
) {

    /**
     * [loadSeat]로 좌석을 읽는다(null = 일반 조회, 비관락 전략은 FOR UPDATE 조회).
     * 기본 조회를 기본 인자(`= productSeatRepository::…`)로 두지 않는다 — Kotlin 기본 인자는 호출 쪽에서 계산되는데
     * 호출 대상이 @Transactional CGLIB 프록시라 프록시의 (빈) 필드를 참조해 NPE가 난다. 본문에서 고른다.
     * [beforeMutation]은 규칙 검사를 통과한 뒤·상태를 바꾸기 전에 불린다 — 조건부 UPDATE 전략의 원자적 전이 자리.
     * [afterFlush]는 홀드 INSERT·좌석 UPDATE를 보낸 뒤 불린다 — 낙관락 전략의 버전 검사 자리(JPA @Version과 같은 시점).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun hold(
        command: HoldSeatCommand,
        loadSeat: ((seatId: Long, scheduleId: Long) -> ProductSeat?)? = null,
        beforeMutation: (ProductSeat) -> Unit = {},
        afterFlush: (ProductSeat) -> Unit = {},
    ): HoldSeatResult {
        val load = loadSeat ?: productSeatRepository::findByIdAndScheduleId
        val seat = load(command.seatId, command.scheduleId)
            ?: throw SeatReservationException(ErrorCode.SEAT_NOT_FOUND)
        seat.assertHoldable() // 에러 우선순위: 선점 불가가 매수 초과보다 먼저
        holdLimitPolicy.check(command.scheduleId, command.userId, properties.maxPerUser)
        // ADR-004 실험 장치 — 트랜잭션·커넥션을 쥔 채 느린 작업(결제 사전 확인 등)을 흉내 낸다. 기본 0이면 아무것도 하지 않는다
        if (!properties.criticalSectionDelay.isZero) Thread.sleep(properties.criticalSectionDelay)

        beforeMutation(seat)
        val hold = seat.hold(command.userId, clock.instant(), properties.ttl)
        // 좌석은 이미 영속 상태 — save()는 merge라 새 홀드의 복사본을 영속화한다. flush로 원본 홀드를 INSERT해 id를 받는다.
        productSeatRepository.flush()
        afterFlush(seat)
        return HoldSeatResult(holdId = hold.id!!, seatId = seat.id!!, expiresAt = hold.expiresAt)
    }
}
