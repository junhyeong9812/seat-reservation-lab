package com.jun.labs.seatreservation.service.impl.hold

import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.impl.HoldSeatProcess
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.locks.ReentrantLock

/**
 * 좌석별 JVM 락. 같은 JVM 안에서만 유효하다 — 앱 인스턴스가 2대면 서로의 락을 모른다(ADR-003 ⑦).
 * ponytail: 좌석마다 락을 만들면 좌석 수만큼 늘어나므로 고정 개수로 나눠 쓴다(striping) — 같은 줄무늬의 다른 좌석끼리 불필요하게 기다린다.
 * 상한: 65,536개. 좌석 수가 훨씬 많고 줄무늬 경합이 지표에 보이면 좌석 키 락 맵(사용 후 제거)으로 바꾼다.
 */
class SeatStripedLocks(stripes: Int = 65_536) {
    private val locks = Array(stripes) { ReentrantLock() }

    fun <T> withLock(seatId: Long, block: () -> T): T {
        val lock = locks[Math.floorMod(seatId.hashCode(), locks.size)]
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/** 1a — 락이 트랜잭션을 감싼다: 커밋이 끝난 뒤에 락을 푼다. 다음 요청은 커밋된 HELD를 읽는다. */
@Component
class JvmLockHoldStrategy(
    private val process: HoldSeatProcess,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.JVM_LOCK
    private val locks = SeatStripedLocks()

    override fun hold(command: HoldSeatCommand): HoldSeatResult =
        locks.withLock(command.seatId) { tx.execute { process.hold(command) }!! }
}

/**
 * 1b — 함정 재현: 락을 트랜잭션 **안**에서 잡고 푼다. 락을 푼 뒤에 커밋하므로, 그 사이에 들어온 요청은
 * 아직 커밋되지 않은 HELD 대신 커밋된 AVAILABLE을 읽고 통과한다(@Transactional 메서드 안의 synchronized와 같은 구조).
 */
@Component
class JvmLockInTxHoldStrategy(
    private val process: HoldSeatProcess,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.JVM_LOCK_IN_TX
    private val locks = SeatStripedLocks()

    override fun hold(command: HoldSeatCommand): HoldSeatResult =
        tx.execute { locks.withLock(command.seatId) { process.hold(command) } }!!
}
