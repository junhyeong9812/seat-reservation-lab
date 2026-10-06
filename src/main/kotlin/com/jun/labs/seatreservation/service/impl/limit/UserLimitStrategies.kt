package com.jun.labs.seatreservation.service.impl.limit

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.HoldLimitPolicy
import com.jun.labs.seatreservation.domain.SeatHold
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.impl.hold.sqlStateOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 1인 최대 매수 제어 방식(ADR-005). 좌석 경합 방식(ADR-003 전략)이 연 트랜잭션 안에서 두 자리에 끼어든다:
 * - [acquire] — 트랜잭션 시작 직후·좌석 읽기 전(락 순서: 사용자 → 좌석)
 * - [check] — 지금의 매수 판정 자리(좌석 선점 가능 확인 뒤 — 에러 우선순위 보존)
 * [around]는 트랜잭션 **밖**에서 좌석 전략 호출 전체를 감싼다(쿼터 행 준비, SERIALIZABLE 충돌 변환·재시도).
 * 진 쪽은 모두 기존 계약의 `HOLD_LIMIT_EXCEEDED`(409)다 — 매수가 남았는데 거절된 경우도 같은 코드(사용자 결정, 측정에서 끝 상태 매수로 구분).
 */
interface UserLimitStrategy {
    val type: UserLimitStrategyType
    fun <T> around(command: HoldSeatCommand, block: () -> T): T = block()
    fun acquire(command: HoldSeatCommand) {}
    fun check(command: HoldSeatCommand)

    /** 만료 배치가 실제로 지운 홀드 — 카운터 방식만 쓴다(같은 트랜잭션). */
    fun onHoldsExpired(expired: List<SeatHold>) {}
}

internal fun limitExceeded(): Nothing = throw SeatReservationException(ErrorCode.HOLD_LIMIT_EXCEEDED)

/** advisory 두 정수 키 — 한 정수 키(ADR-003 advisory 전략의 좌석 id)와 키 공간이 따로다. int 범위를 넘으면 조용히 자르지 않고 실패. */
private fun advisoryKey(command: HoldSeatCommand) = arrayOf(Math.toIntExact(command.scheduleId), Math.toIntExact(command.userId))

/** L0 — 조회 후 비교(기준선). 같은 사용자의 동시 요청은 막지 못한다 — 대조군. */
@Component
class NoUserLimit(private val policy: HoldLimitPolicy, private val properties: SeatHoldProperties) : UserLimitStrategy {
    override val type = UserLimitStrategyType.NONE
    override fun check(command: HoldSeatCommand) = policy.check(command.scheduleId, command.userId, properties.maxPerUser)
}

/** L1 — `pg_advisory_xact_lock(회차, 사용자)`: 같은 사용자의 다음 요청은 앞 트랜잭션이 끝날 때까지 기다린 뒤 센다. */
@Component
class AdvisoryUserLimit(
    private val policy: HoldLimitPolicy,
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
) : UserLimitStrategy {
    override val type = UserLimitStrategyType.ADVISORY
    override fun acquire(command: HoldSeatCommand) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", *advisoryKey(command))
    }
    override fun check(command: HoldSeatCommand) = policy.check(command.scheduleId, command.userId, properties.maxPerUser)
}

/** L2 — `pg_try_advisory_xact_lock(회차, 사용자)`: 같은 사용자의 요청이 진행 중이면 기다리지 않고 거절. */
@Component
class AdvisoryTryUserLimit(
    private val policy: HoldLimitPolicy,
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
) : UserLimitStrategy {
    override val type = UserLimitStrategyType.ADVISORY_TRY
    override fun acquire(command: HoldSeatCommand) {
        val got = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean::class.java, *advisoryKey(command))
        if (got != true) limitExceeded()
    }
    override fun check(command: HoldSeatCommand) = policy.check(command.scheduleId, command.userId, properties.maxPerUser)
}

/**
 * 쿼터 행 준비 — 트랜잭션 밖(자동 커밋)에서 미리 만든다. 트랜잭션 안의 `INSERT … ON CONFLICT`는 아직 커밋 안 된
 * 같은 키 행을 만나면 그 트랜잭션이 끝날 때까지 기다려 NOWAIT의 의미를 깬다(사용자 첫 요청들).
 */
private fun ensureQuotaRow(jdbc: JdbcTemplate, command: HoldSeatCommand) {
    jdbc.update(
        "INSERT INTO user_hold_quota (schedule_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        command.scheduleId, command.userId,
    )
}

/** L3 — (회차, 사용자) 쿼터 행 `FOR UPDATE`: 같은 사용자의 다음 요청은 행 락을 기다린 뒤 센다. */
@Component
class QuotaLockUserLimit(
    private val policy: HoldLimitPolicy,
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
) : UserLimitStrategy {
    override val type = UserLimitStrategyType.QUOTA_LOCK
    override fun <T> around(command: HoldSeatCommand, block: () -> T): T {
        ensureQuotaRow(jdbc, command)
        return block()
    }
    override fun acquire(command: HoldSeatCommand) {
        jdbc.queryForList(
            "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE",
            command.scheduleId, command.userId,
        ).ifEmpty { error("쿼터 행 없음 — around에서 만들어야 한다") }
    }
    override fun check(command: HoldSeatCommand) = policy.check(command.scheduleId, command.userId, properties.maxPerUser)
}

/** L4 — 같은 쿼터 행 `FOR UPDATE NOWAIT`: 잠겨 있으면 기다리지 않고 거절(55P03만 — 다른 락 실패는 그대로 올린다). */
@Component
class QuotaNoWaitUserLimit(
    private val policy: HoldLimitPolicy,
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
) : UserLimitStrategy {
    override val type = UserLimitStrategyType.QUOTA_NOWAIT
    override fun <T> around(command: HoldSeatCommand, block: () -> T): T {
        ensureQuotaRow(jdbc, command)
        return block()
    }
    override fun acquire(command: HoldSeatCommand) {
        try {
            jdbc.queryForList(
                "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE NOWAIT",
                command.scheduleId, command.userId,
            ).ifEmpty { error("쿼터 행 없음 — around에서 만들어야 한다") }
        } catch (e: DataAccessException) {
            // JdbcTemplate은 55P03을 PessimisticLockingFailureException이 아니라 UncategorizedSQLException으로 번역한다(JPA 경로와 다름 — 테스트로 확인).
            // 그래서 예외 타입이 아니라 SQLState로 가른다
            if (sqlStateOf(e) == LOCK_NOT_AVAILABLE) limitExceeded()
            throw e
        }
    }
    override fun check(command: HoldSeatCommand) = policy.check(command.scheduleId, command.userId, properties.maxPerUser)

    companion object {
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}

/**
 * L5 — 카운터 조건부 UPDATE: `cnt + 1 <= 최대`일 때만 올린다(0행 = 거절). 매수 판정이 곧 갱신이라 COUNT가 없고,
 * **좌석 판정보다 먼저 판정된다**(사용자 허용 — 매수 초과인 사용자가 팔린 좌석을 누르면 `HOLD_LIMIT_EXCEEDED`).
 * 카운터 = 홀드 + 확정 예약 수: 선점 +1(좌석 실패면 같은 트랜잭션 롤백으로 원복), 확정은 홀드 → 예약이라 그대로, 만료 −1.
 */
@Component
class CounterUserLimit(
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
) : UserLimitStrategy {
    override val type = UserLimitStrategyType.COUNTER
    override fun <T> around(command: HoldSeatCommand, block: () -> T): T {
        ensureQuotaRow(jdbc, command)
        return block()
    }
    override fun acquire(command: HoldSeatCommand) {
        val updated = jdbc.update(
            "UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ? AND cnt + 1 <= ?",
            command.scheduleId, command.userId, properties.maxPerUser,
        )
        if (updated == 0) limitExceeded()
    }
    override fun check(command: HoldSeatCommand) {} // acquire에서 판정 끝

    override fun onHoldsExpired(expired: List<SeatHold>) {
        // 실제로 지운 홀드에서 센다 — 만료 대상 조회를 따로 하면 그 사이 확정된 홀드까지 내릴 수 있다
        expired.groupingBy { it.scheduleId to it.userId }.eachCount().forEach { (key, n) ->
            val updated = jdbc.update(
                "UPDATE user_hold_quota SET cnt = cnt - ? WHERE schedule_id = ? AND user_id = ?",
                n, key.first, key.second,
            )
            // 카운터 행이 없으면 카운터가 이미 어긋난 것 — 조용히 넘기지 않는다(cnt < 0은 CHECK 제약이 막는다)
            check(updated == 1) { "카운터 행 없음: 회차 ${key.first} 사용자 ${key.second}" }
        }
    }
}

/**
 * L6·L7 — SERIALIZABLE: 트랜잭션의 첫 문장으로 격리 수준을 올린다(첫 문장이 아니면 PostgreSQL이 오류를 낸다 — 무음 무시 없음).
 * 직렬화 충돌(40001)은 쿼리·커밋 어디서든 날 수 있어 [around](트랜잭션 밖)에서 받는다. L6은 바로 거절, L7은 최대 [retries]번 다시 한다.
 * 한계: 40001은 좌석 행 충돌(같은 좌석)에서도 날 수 있다 — 그것도 `HOLD_LIMIT_EXCEEDED`가 된다(ADR-005 편향).
 */
abstract class SerializableUserLimit(
    private val policy: HoldLimitPolicy,
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
    private val meters: MeterRegistry,
    private val retries: Int,
) : UserLimitStrategy {
    override fun <T> around(command: HoldSeatCommand, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: RuntimeException) {
                if (sqlStateOf(e) != SERIALIZATION_FAILURE) throw e
                meters.counter("seat.hold.limit.serialization_failure").increment()
                if (attempt >= retries) limitExceeded()
                attempt++
                meters.counter("seat.hold.limit.retry").increment()
            }
        }
    }
    override fun acquire(command: HoldSeatCommand) {
        jdbc.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE")
    }
    override fun check(command: HoldSeatCommand) = policy.check(command.scheduleId, command.userId, properties.maxPerUser)

    companion object {
        const val SERIALIZATION_FAILURE = "40001"
    }
}

@Component
class SerializableNoRetryUserLimit(policy: HoldLimitPolicy, properties: SeatHoldProperties, jdbc: JdbcTemplate, meters: MeterRegistry) :
    SerializableUserLimit(policy, properties, jdbc, meters, retries = 0) {
    override val type = UserLimitStrategyType.SERIALIZABLE
}

@Component
class SerializableRetryUserLimit(policy: HoldLimitPolicy, properties: SeatHoldProperties, jdbc: JdbcTemplate, meters: MeterRegistry) :
    SerializableUserLimit(policy, properties, jdbc, meters, retries = 3) {
    override val type = UserLimitStrategyType.SERIALIZABLE_RETRY
}

/** 기동 설정으로 고른 매수 방식 하나. */
@Component
class ActiveUserLimit(strategies: List<UserLimitStrategy>, properties: SeatHoldProperties) {
    val strategy: UserLimitStrategy = strategies.single { it.type == properties.limitStrategy }
}
