package com.jun.labs.seatreservation.service.impl.hold

import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.impl.HoldSeatProcess
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** 0 — 락 없음(대조군). ADR-000 기준선 그대로: 트랜잭션 하나에서 읽고 검사하고 쓴다. */
@Component
class NoLockHoldStrategy(
    private val process: HoldSeatProcess,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.NONE

    override fun hold(command: HoldSeatCommand): HoldSeatResult = tx.execute { process.hold(command) }!!
}
