package com.jun.labs.seatreservation.service.impl.hold

import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.impl.HoldSeatProcess
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException

/** 2 — 조건부 UPDATE: 규칙 검사를 통과하면 `status='AVAILABLE'` 조건으로 HELD 전이를 원자적으로 시도하고, 0행이면 진 것. */
@Component
class ConditionalUpdateHoldStrategy(
    private val process: HoldSeatProcess,
    private val seats: ProductSeatRepository,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.CONDITIONAL_UPDATE

    override fun hold(command: HoldSeatCommand): HoldSeatResult = tx.execute {
        process.hold(command, beforeMutation = { seat ->
            if (seats.holdIfAvailable(seat.id!!, seat.scheduleId) == 0) seatTaken()
        })
    }!!
}

/** 3a — 비관락: 좌석 행을 FOR UPDATE로 읽는다. 뒤에 온 요청은 앞 트랜잭션의 커밋을 기다렸다가 HELD를 읽고 진다. */
@Component
class PessimisticHoldStrategy(
    private val process: HoldSeatProcess,
    private val seats: ProductSeatRepository,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.PESSIMISTIC

    override fun hold(command: HoldSeatCommand): HoldSeatResult =
        tx.execute { process.hold(command, loadSeat = seats::findForUpdate) }!!
}

/** 3b — 비관락 NOWAIT: 잠겨 있으면 기다리지 않고 바로 진다(409). */
@Component
class PessimisticNoWaitHoldStrategy(
    private val process: HoldSeatProcess,
    private val seats: ProductSeatRepository,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.PESSIMISTIC_NOWAIT

    override fun hold(command: HoldSeatCommand): HoldSeatResult = try {
        tx.execute { process.hold(command, loadSeat = seats::findForUpdateNoWait) }!!
    } catch (e: PessimisticLockingFailureException) {
        // NOWAIT의 '진 것'은 55P03(lock_not_available)뿐 — 데드락(40P01) 등 다른 락 실패는 409로 바꾸지 않고 올린다(무음 변환 금지)
        if (sqlStateOf(e) == LOCK_NOT_AVAILABLE) seatTaken()
        throw e
    }

    companion object {
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}

/** 예외 원인 사슬에서 첫 SQLState. */
internal fun sqlStateOf(e: Throwable): String? =
    generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState }

/**
 * 4 — 낙관락(버전 열): 읽은 버전이 그대로일 때만 올리고, 아니면 진다(재시도 없음 — 좌석은 한 명만 가지면 된다).
 * 버전은 좌석보다 **먼저** 읽는다 — 좌석을 먼저 읽고 버전을 나중에 읽으면 그 사이 커밋된 새 버전을 기준으로 삼아
 * 낡은 AVAILABLE 스냅샷이 통과할 수 있다. 버전을 먼저 읽으면 좌석 스냅샷은 그 버전 이후의 것이고,
 * 이후 상태(HELD)면 규칙 검사에서, 같은 상태면 버전 검사에서 진다.
 */
@Component
class OptimisticHoldStrategy(
    private val process: HoldSeatProcess,
    private val seats: ProductSeatRepository,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.OPTIMISTIC

    override fun hold(command: HoldSeatCommand): HoldSeatResult = tx.execute {
        var expected = 0L
        process.hold(
            command,
            loadSeat = { id, scheduleId ->
                seats.findVersion(id)?.let { version ->
                    expected = version
                    seats.findByIdAndScheduleId(id, scheduleId)
                }
            },
            afterFlush = { seat -> if (seats.bumpVersion(seat.id!!, expected) == 0) seatTaken() },
        )
    }!!
}

/** 5 — 유니크 제약: 좌석당 홀드 1행을 DB가 강제한다(V4, 이 전략에서만 적용). 중복 키 = 진 것. */
@Component
class UniqueConstraintHoldStrategy(
    private val process: HoldSeatProcess,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.UNIQUE

    override fun hold(command: HoldSeatCommand): HoldSeatResult = try {
        tx.execute { process.hold(command) }!!
    } catch (e: DataIntegrityViolationException) {
        val cause = e.mostSpecificCause
        // 23505 = unique_violation. 이 전략이 만든 제약만 '진 것'으로 — 다른 무결성 오류는 그대로 올린다(무음 변환 금지)
        if (cause is SQLException && cause.sqlState == "23505" && cause.message.orEmpty().contains(UNIQUE_HOLD_INDEX)) seatTaken()
        throw e
    }

    companion object {
        const val UNIQUE_HOLD_INDEX = "uq_seat_hold_seat_id"
    }
}

/** 6 — PostgreSQL advisory lock: 좌석 id를 키로 트랜잭션 범위 락을 잡는다(커밋·롤백 때 자동 해제). 행과 무관한 락. */
@Component
class AdvisoryLockHoldStrategy(
    private val process: HoldSeatProcess,
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
) : HoldStrategy {
    override val type = HoldStrategyType.ADVISORY

    override fun hold(command: HoldSeatCommand): HoldSeatResult = tx.execute {
        // JpaTransactionManager가 같은 커넥션을 JdbcTemplate에 노출한다 — 락과 선점이 한 트랜잭션
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", command.seatId)
        process.hold(command)
    }!!
}
