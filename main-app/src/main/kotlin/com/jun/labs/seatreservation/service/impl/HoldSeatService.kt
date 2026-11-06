package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.expiry.LazyHoldProcess
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategy
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

/**
 * 선점 유스케이스 — 설정된 경합 제어 방식(ADR-003)에 위임한다.
 * 락 획득·트랜잭션 경계·진 쪽 처리는 방식마다 달라 전략이 책임지고, 선점 규칙 자체는 [HoldSeatProcess] 하나다.
 * 매수 제어 방식(ADR-005)은 트랜잭션 밖에서 쿼터 행을 준비하고(타이머 `seat.hold.limit.prepare`), 전략 호출 전체를 감싼다(SERIALIZABLE 충돌 변환·재시도).
 * ADR-014: 만료 방식이 sweep(A)이 아니면 B·C·D 선점([LazyHoldProcess] — 기본 조합 전용 · 기동 검사)으로 간다. sweep이면 지금 경로 그대로다.
 */
@Service
class HoldSeatService(
    strategies: List<HoldStrategy>,
    properties: SeatHoldProperties,
    private val userLimit: ActiveUserLimit,
    private val meters: MeterRegistry,
    /** ADR-014 B·C·D 선점 — 직접 생성(테스트)할 때는 없다(sweep 전용) */
    private val lazyHold: LazyHoldProcess? = null,
) : HoldSeatUseCase {

    private val strategy: HoldStrategy = strategies.single { it.type == properties.strategy }
    private val lazy = properties.expiryMode != ExpiryMode.SWEEP

    override fun hold(command: HoldSeatCommand): HoldSeatResult {
        if (lazy) return checkNotNull(lazyHold) { "만료 방식 B·C·D인데 선점 경로(LazyHoldProcess)가 없다" }.hold(command)
        val limit = userLimit.strategy
        if (limit.hasPrepare) meters.timer("seat.hold.limit.prepare").record(Runnable { limit.prepare(command) })
        return limit.around(command) { strategy.hold(command) }
    }
}
