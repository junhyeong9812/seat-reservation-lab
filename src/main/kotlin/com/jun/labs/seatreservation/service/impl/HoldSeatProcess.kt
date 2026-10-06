package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.ProductSeat
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * ADR-000 기준선의 선점 규칙: (사용자 단위 매수 제어 진입 — ADR-005) → 좌석 애그리거트 로드 → 선점 가능 확인 → 매수 판정 → seat.hold() → flush.
 * 트랜잭션은 열지 않는다(MANDATORY) — 경합 제어 방식마다 락과 트랜잭션의 앞뒤가 달라 전략이 경계를 정한다.
 * 검사는 읽어 온 스냅샷 위에서 일어나므로(check-then-act) 전략 없이는 동시 요청을 막지 못한다.
 */
@Component
class HoldSeatProcess(
    private val productSeatRepository: ProductSeatRepository,
    private val userLimit: ActiveUserLimit,
    private val meters: MeterRegistry,
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
        val limit = userLimit.strategy
        // 락 순서: 사용자 → 좌석(ADR-005). 사용자 락·카운터는 좌석을 읽기 전에, 매수 판정은 좌석 확인 뒤에(에러 우선순위 보존 — counter만 예외)
        // 타이머: acquire(사용자 단위 진입) · check(매수 판정 호출) · span(acquire 시작 ~ 매수 판정 끝 — 그 사이 좌석 읽기·확인 포함, 명세 §9.1 '실 락 획득부터 판정까지')
        val spanStart = System.nanoTime()
        var entered = true
        meters.timer("seat.hold.limit.acquire").record(Runnable { entered = limit.acquire(command) })
        val load = loadSeat ?: productSeatRepository::findByIdAndScheduleId
        val seat = load(command.seatId, command.scheduleId)
            ?: throw SeatReservationException(ErrorCode.SEAT_NOT_FOUND)
        seat.assertHoldable() // 에러 우선순위: 선점 불가가 매수 초과보다 먼저
        meters.timer("seat.hold.limit.check").record(Runnable { limit.check(command, entered) })
        meters.timer("seat.hold.limit.span").record(System.nanoTime() - spanStart, java.util.concurrent.TimeUnit.NANOSECONDS)
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
