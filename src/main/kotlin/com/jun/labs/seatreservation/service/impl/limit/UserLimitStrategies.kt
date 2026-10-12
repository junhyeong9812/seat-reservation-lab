package com.jun.labs.seatreservation.service.impl.limit

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.HoldLimitPolicy
import com.jun.labs.seatreservation.domain.SeatHold
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.impl.drift.ExpiryCounters
import com.jun.labs.seatreservation.service.impl.hold.sqlStateOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 1인 최대 매수 제어 방식(ADR-005). 좌석 경합 방식(ADR-003 전략)이 연 트랜잭션 안에서 두 자리에 끼어든다:
 * - [acquire] — 트랜잭션 시작 직후·좌석 읽기 전(락 순서: 사용자 → 좌석)
 * - [check] — 지금의 매수 판정 자리(좌석 선점 가능 확인 뒤 — 에러 우선순위 보존)
 * [prepare]는 트랜잭션 **밖**에서 먼저 한 번(쿼터 행 준비), [around]는 트랜잭션 밖에서 좌석 전략 호출 전체를 감싼다(SERIALIZABLE 충돌 변환·재시도).
 * 진 쪽은 모두 기존 계약의 `HOLD_LIMIT_EXCEEDED`(409)다 — 매수가 남았는데 거절된 경우도 같은 코드(사용자 결정, 측정에서 끝 상태 매수로 구분).
 * 에러 우선순위: 좌석 불가 → 매수 초과(명세 §2 — 즉시 실패형도 거절을 좌석 확인 뒤로 미룬다). 예외: counter(갱신이 곧 판정)와
 * `-early` 변형(advisory-try-early·quota-nowait-early — 좌석 확인 전에 거절, 거절될 요청이 좌석 락을 잡지 않는다; 사용자 2026-10-06 '추가로 확인').
 * SERIALIZABLE의 40001은 어느 문장에서든(커밋 포함) 나므로 순서와 무관하다.
 */
interface UserLimitStrategy {
    val type: UserLimitStrategyType
    /** 트랜잭션 밖 준비 단계가 있는가 — 없으면 유스케이스가 prepare 호출·타이머를 건너뛴다(ADR-006: 준비 없는 방식의 prepare 타이머 0건) */
    val hasPrepare: Boolean get() = false
    /** [acquire]가 트랜잭션 첫 문장이어야 하는가(SERIALIZABLE의 `SET TRANSACTION`) — 그러면 판매 마감 판정(ADR-009)을 acquire 뒤에 한다. acquire에 판정이 없는 방식만 true */
    val acquireFirst: Boolean get() = false
    fun prepare(command: HoldSeatCommand) {}
    fun <T> around(command: HoldSeatCommand, block: () -> T): T = block()
    /** 사용자 단위 진입. false = 같은 사용자의 다른 요청이 진행 중(즉시 실패형) — 거절은 [check]에서 한다(좌석 확인 뒤, 에러 우선순위 보존). */
    fun acquire(command: HoldSeatCommand): Boolean = true
    fun check(command: HoldSeatCommand, entered: Boolean)

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
    override fun check(command: HoldSeatCommand, entered: Boolean) {
        if (!entered) limitExceeded()
        policy.check(command.scheduleId, command.userId, properties.maxPerUser)
    }
}

/** L1 — `pg_advisory_xact_lock(회차, 사용자)`: 같은 사용자의 다음 요청은 앞 트랜잭션이 끝날 때까지 기다린 뒤 센다. */
@Component
class AdvisoryUserLimit(
    private val policy: HoldLimitPolicy,
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
) : UserLimitStrategy {
    override val type = UserLimitStrategyType.ADVISORY
    override fun acquire(command: HoldSeatCommand): Boolean {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", *advisoryKey(command))
        return true
    }
    override fun check(command: HoldSeatCommand, entered: Boolean) {
        if (!entered) limitExceeded()
        policy.check(command.scheduleId, command.userId, properties.maxPerUser)
    }
}

/**
 * L2 — `pg_try_advisory_xact_lock(회차, 사용자)`: 같은 사용자의 요청이 진행 중이면 기다리지 않고 거절.
 * [early] = false(명세 순서): 못 잡아도 좌석은 확인한 뒤 [check]에서 거절 — 팔린 좌석이면 `SEAT_NOT_AVAILABLE`. 대가: 거절될 요청도 좌석 락(NOWAIT)을 잠깐 잡는다.
 * [early] = true: 좌석 확인 전에 바로 거절 — 좌석 락을 잡지 않는다(에러 순서가 바뀐다).
 */
abstract class AdvisoryTryUserLimitBase(
    private val policy: HoldLimitPolicy,
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
    private val early: Boolean,
) : UserLimitStrategy {
    override fun acquire(command: HoldSeatCommand): Boolean {
        val got = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean::class.java, *advisoryKey(command)) == true
        if (!got && early) limitExceeded()
        return got
    }
    override fun check(command: HoldSeatCommand, entered: Boolean) {
        if (!entered) limitExceeded()
        policy.check(command.scheduleId, command.userId, properties.maxPerUser)
    }
}

@Component
class AdvisoryTryUserLimit(policy: HoldLimitPolicy, properties: SeatHoldProperties, jdbc: JdbcTemplate) :
    AdvisoryTryUserLimitBase(policy, properties, jdbc, early = false) {
    override val type = UserLimitStrategyType.ADVISORY_TRY
}

@Component
class AdvisoryTryEarlyUserLimit(policy: HoldLimitPolicy, properties: SeatHoldProperties, jdbc: JdbcTemplate) :
    AdvisoryTryUserLimitBase(policy, properties, jdbc, early = true) {
    override val type = UserLimitStrategyType.ADVISORY_TRY_EARLY
}

/**
 * 쿼터 행 준비 — 트랜잭션 밖(자동 커밋)에서 미리 만든다. 트랜잭션 안의 `INSERT … ON CONFLICT`는 아직 커밋 안 된
 * 같은 키 행을 만나면 그 트랜잭션이 끝날 때까지 기다려 NOWAIT의 의미를 깬다(사용자 첫 요청들).
 * 먼저 일반 SELECT로 있는지 본다 — `INSERT … ON CONFLICT DO NOTHING`은 이미 있는 행이라도 그 행을 **UPDATE 중인** 트랜잭션(counter의 cnt + 1)이
 * 끝날 때까지 기다린다(테스트로 확인). 그러면 같은 사용자의 대기가 트랜잭션 밖·타이머 밖으로 새어 나간다. 일반 SELECT는 기다리지 않는다.
 */
private fun ensureQuotaRow(jdbc: JdbcTemplate, command: HoldSeatCommand) {
    val exists = jdbc.queryForList(
        "SELECT 1 FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", command.scheduleId, command.userId,
    ).isNotEmpty()
    if (exists) return
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
    override val hasPrepare = true
    override fun prepare(command: HoldSeatCommand) = ensureQuotaRow(jdbc, command)
    override fun acquire(command: HoldSeatCommand): Boolean {
        jdbc.queryForList(
            "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE",
            command.scheduleId, command.userId,
        ).ifEmpty { error("쿼터 행 없음 — prepare에서 만들어야 한다") }
        return true
    }
    override fun check(command: HoldSeatCommand, entered: Boolean) {
        if (!entered) limitExceeded()
        policy.check(command.scheduleId, command.userId, properties.maxPerUser)
    }
}

/**
 * L4 — 같은 쿼터 행을 기다리지 않고 잠근다: 잠겨 있으면 거절. **`FOR UPDATE SKIP LOCKED`** 를 쓴다 — 합의 때의 `NOWAIT`는 잠겨 있으면
 * 오류(55P03)를 내고 PostgreSQL은 오류가 난 트랜잭션을 더 쓸 수 없게 만든다(aborted). 명세 순서(거절을 좌석 확인 뒤로)를 지키려면 트랜잭션이 살아 있어야 해서
 * 오류 없이 '0행'을 돌려주는 SKIP LOCKED로 바꿨다(기다리지 않는다는 의미는 같다). 행은 [prepare]가 커밋해 두므로 0행 = 다른 트랜잭션이 잠금.
 * [early] 의미는 L2와 같다. 두 변형 모두 SKIP LOCKED라 메커니즘 차이 없이 '거절 시점'만 다르다.
 */
abstract class QuotaNoWaitUserLimitBase(
    private val policy: HoldLimitPolicy,
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
    private val early: Boolean,
) : UserLimitStrategy {
    override val hasPrepare = true
    override fun prepare(command: HoldSeatCommand) = ensureQuotaRow(jdbc, command)
    override fun acquire(command: HoldSeatCommand): Boolean {
        val got = jdbc.queryForList(
            "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE SKIP LOCKED",
            command.scheduleId, command.userId,
        ).isNotEmpty()
        if (!got && early) limitExceeded()
        return got
    }
    override fun check(command: HoldSeatCommand, entered: Boolean) {
        if (!entered) limitExceeded()
        policy.check(command.scheduleId, command.userId, properties.maxPerUser)
    }
}

@Component
class QuotaNoWaitUserLimit(policy: HoldLimitPolicy, properties: SeatHoldProperties, jdbc: JdbcTemplate) :
    QuotaNoWaitUserLimitBase(policy, properties, jdbc, early = false) {
    override val type = UserLimitStrategyType.QUOTA_NOWAIT
}

@Component
class QuotaNoWaitEarlyUserLimit(policy: HoldLimitPolicy, properties: SeatHoldProperties, jdbc: JdbcTemplate) :
    QuotaNoWaitUserLimitBase(policy, properties, jdbc, early = true) {
    override val type = UserLimitStrategyType.QUOTA_NOWAIT_EARLY
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
    private val counters: ExpiryCounters,
) : UserLimitStrategy {
    override val type = UserLimitStrategyType.COUNTER
    override val hasPrepare = true
    override fun prepare(command: HoldSeatCommand) = ensureQuotaRow(jdbc, command)
    override fun acquire(command: HoldSeatCommand): Boolean {
        val updated = jdbc.update(
            "UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ? AND cnt + 1 <= ?",
            command.scheduleId, command.userId, properties.maxPerUser,
        )
        if (updated == 0) limitExceeded()
        return true
    }
    override fun check(command: HoldSeatCommand, entered: Boolean) {} // acquire에서 판정 끝

    // 만료 감소 — 공용(ExpiryCounters: 기본은 ADR-008까지와 같고, 후보 A를 켜면 실패 쌍만 복구)
    override fun onHoldsExpired(expired: List<SeatHold>) = counters.decrement(expired)
}

/**
 * ADR-006 — 카운터를 트랜잭션 안 **한 문장 upsert**로: 행이 없으면 cnt = 1로 만들고, 있으면 `cnt + 1 <= 최대`일 때만 올린다.
 * 영향 행 1 = 통과, 0 = 거절. ADR-005 counter의 트랜잭션 밖 준비(prepare — 요청당 커넥션 3회)가 없다 → 빌림 1회.
 * READ COMMITTED에서 `ON CONFLICT DO UPDATE`는 충돌 행을 잠그고 최신 버전으로 WHERE를 다시 본다 — 같은 사용자 동시 요청은 이 행에서 줄을 선다.
 * 첫 요청 둘이 동시에 INSERT로 오면 하나는 상대 커밋을 기다렸다가 UPDATE 경로로 간다. 카운터 정의·만료 감소·에러 순서는 counter와 같다.
 */
@Component
class CounterUpsertUserLimit(
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
    private val counters: ExpiryCounters,
) : UserLimitStrategy {
    override val type = UserLimitStrategyType.COUNTER_UPSERT
    override fun acquire(command: HoldSeatCommand): Boolean {
        if (!upsertQuota(jdbc, command, properties.maxPerUser)) limitExceeded()
        return true
    }
    override fun check(command: HoldSeatCommand, entered: Boolean) {} // acquire에서 판정 끝
    override fun onHoldsExpired(expired: List<SeatHold>) = counters.decrement(expired)
}

/** 한 문장 upsert — true = 올렸다(통과), false = 상한(0행). counter-upsert·counter-upsert-precheck 공용. */
private fun upsertQuota(jdbc: JdbcTemplate, command: HoldSeatCommand, maxPerUser: Int): Boolean = jdbc.update(
    """
    INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 1)
    ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = user_hold_quota.cnt + 1
    WHERE user_hold_quota.cnt + 1 <= ?
    """.trimIndent(),
    command.scheduleId, command.userId, maxPerUser,
) == 1

/**
 * ADR-007 — counter-upsert 앞에 **잠금 없는** 좌석 상태 선조회를 둔다. ADR-006 S3에서 실패 시도(대부분 이미 팔린 좌석)마다
 * upsert + 롤백이 WAL을 남겼다(none의 2.8배) — 팔린 좌석은 쓰기 없이 거절한다. 매수 초과 사용자는 여전히 좌석 락을 잡기 전에 거절된다.
 * 에러 순서 = 명세 순서(좌석 불가 → 매수 초과, 사용자 2026-10-09): upsert가 0행이면 좌석을 다시 읽어 그 사이 팔렸으면 좌석 불가로 답한다.
 * 남는 틈: 다른 트랜잭션이 커밋 전에 쥔 좌석은 잠금 없는 읽기에 AVAILABLE로 보여, 매수가 찬 사용자에게는 `HOLD_LIMIT_EXCEEDED`가 난다
 * (잠금 없이는 볼 수 없다 — 잠그면 매수 초과자가 좌석 락을 잡아 억울한 좌석이 되살아난다).
 */
@Component
class CounterUpsertPrecheckUserLimit(
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
    private val counters: ExpiryCounters,
) : UserLimitStrategy {
    override val type = UserLimitStrategyType.COUNTER_UPSERT_PRECHECK
    override fun acquire(command: HoldSeatCommand): Boolean {
        assertSeatAvailable(jdbc, command)
        if (!upsertQuota(jdbc, command, properties.maxPerUser)) {
            assertSeatAvailable(jdbc, command) // 선조회 뒤 팔렸으면 좌석 불가가 먼저
            limitExceeded()
        }
        return true
    }
    override fun check(command: HoldSeatCommand, entered: Boolean) {} // acquire에서 판정 끝
    override fun onHoldsExpired(expired: List<SeatHold>) = counters.decrement(expired)
}

/** 잠금 없는 좌석 상태 선조회 — 없는 좌석 404, AVAILABLE이 아니면 409. counter-upsert-precheck·counter-trigger 공용. */
private fun assertSeatAvailable(jdbc: JdbcTemplate, command: HoldSeatCommand) {
    val status = jdbc.queryForList(
        "SELECT status FROM product_seat WHERE id = ? AND schedule_id = ?", String::class.java,
        command.seatId, command.scheduleId,
    ).singleOrNull() ?: throw SeatReservationException(ErrorCode.SEAT_NOT_FOUND)
    if (status != SeatStatus.AVAILABLE.name) throw SeatReservationException(ErrorCode.SEAT_NOT_AVAILABLE)
}

/**
 * ADR-009 후보 B — cnt는 DB 트리거(V8 `db/migration-trigger`)가 홀드·예약 행 변경과 같은 트랜잭션에서 고친다. 앱은 cnt를 쓰지 않는다(중복 증감 없음).
 * 한도 판정은 앱에서 한다(명세 §10 D3 — 트리거 거절은 억울한 좌석을 되살린다): 좌석 선조회(precheck와 같다) → 쿼터 행 `FOR UPDATE`
 * (없으면 cnt 0으로 만들고 잠근다 — 같은 사용자 첫 요청들도 이 행에서 줄을 선다) → `cnt + 1 <= 최대`가 아니면 좌석을 다시 보고 매수 초과.
 * 행 잠금은 커밋까지 유지되고 그 사이 홀드 INSERT의 트리거가 cnt + 1을 쓰므로, 다음 요청은 늘어난 cnt를 본다. 에러 순서 = precheck(좌석 먼저).
 * 만료: 트리거가 홀드 DELETE마다 −1(행이 없으면 SR001 — 엄격, 0으로 자르지 않음). **이 방식에서만** 만료가 쿼터 행을 먼저(정렬 순서로) 잠근다 —
 * 확정(예약 INSERT 트리거로 쿼터 행 먼저)과 같은 잠금 순서가 되어 확정↔만료 교착을 없앤다(설계 §3b — 교착만 해결, 확정 원자성은 ADR-011).
 */
@Component
class CounterTriggerUserLimit(
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
) : UserLimitStrategy {
    override val type = UserLimitStrategyType.COUNTER_TRIGGER
    override fun acquire(command: HoldSeatCommand): Boolean {
        assertSeatAvailable(jdbc, command)
        val cnt = lockQuotaRow(command)
        if (cnt + 1 > properties.maxPerUser) {
            assertSeatAvailable(jdbc, command) // 잠금을 기다린 사이 팔렸으면 좌석 불가가 먼저
            limitExceeded()
        }
        return true
    }
    override fun check(command: HoldSeatCommand, entered: Boolean) {} // acquire에서 판정 끝

    override fun onHoldsExpired(expired: List<SeatHold>) {
        expired.map { it.scheduleId to it.userId }.distinct().sortedWith(compareBy({ it.first }, { it.second })).forEach { (s, u) ->
            // 행이 없으면 잠글 것도 없다 — flush 때 트리거가 SR001로 틱을 실패시킨다(엄격 — 대조 C·F가 고친다)
            jdbc.queryForList("SELECT 1 FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", s, u)
        }
    }

    private fun lockQuotaRow(command: HoldSeatCommand): Int {
        selectForUpdate(command)?.let { return it }
        jdbc.update(
            "INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 0) ON CONFLICT DO NOTHING",
            command.scheduleId, command.userId,
        )
        return selectForUpdate(command) ?: error("쿼터 행을 만든 직후 잠그지 못했다: $command")
    }

    private fun selectForUpdate(command: HoldSeatCommand): Int? = jdbc.queryForList(
        "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", Int::class.java,
        command.scheduleId, command.userId,
    ).singleOrNull()
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
    override val acquireFirst = true
    override fun acquire(command: HoldSeatCommand): Boolean {
        jdbc.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE")
        return true
    }
    override fun check(command: HoldSeatCommand, entered: Boolean) {
        if (!entered) limitExceeded()
        policy.check(command.scheduleId, command.userId, properties.maxPerUser)
    }

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
