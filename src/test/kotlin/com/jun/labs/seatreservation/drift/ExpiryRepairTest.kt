package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.api.HoldExpiryScheduler
import com.jun.labs.seatreservation.domain.ProductSeat
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.actualOf
import com.jun.labs.seatreservation.support.clearAdr009Tables
import com.jun.labs.seatreservation.support.cntOf
import com.jun.labs.seatreservation.support.countOf
import com.jun.labs.seatreservation.support.deleteQuota
import com.jun.labs.seatreservation.support.driftLogs
import com.jun.labs.seatreservation.support.holdsAndReservations
import com.jun.labs.seatreservation.support.rowCount
import com.jun.labs.seatreservation.support.setCnt
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ADR-009 후보 A(쌍 단위 견딤) — 명세 §2 'A의 범위'·'무음 금지'·§5 테스트 첫 줄.
 * 어긋남 세 갈래를 DB 직접 조작으로 만든다: 행 없음 · cnt 작음(감소하면 음수 / 조용히 남음) · cnt 큼.
 * 감소가 실패하는 것은 '행 없음'과 '음수'뿐이다 — A는 그 쌍만 고치고 기록하며, 조용한 쌍·큰 쌍은 지금처럼 감소만 한다(A의 범위 밖 — C가 맡는다).
 *
 * 만료 스케줄러 빈을 띄워 둔다(주기 1h — 기동 때 1회 빈 DB에서 돌고 끝) — 틱 지표 `seat.hold.expiry.tick`을 스케줄러 진입점으로 재기 위해서다.
 * 나머지 단언은 기존 진입점 `ExpireHoldsUseCase.expire()`를 직접 부른다.
 */
@SpringBootTest(properties = ["seat.hold.expiry-scheduler-enabled=true", "seat.hold.expiry-interval=1h"])
abstract class ExpiryDriftScenario(private val type: UserLimitStrategyType) : IntegrationTest() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase
    @Autowired lateinit var scheduler: HoldExpiryScheduler
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var meters: MeterRegistry

    @BeforeEach
    fun clearLogs() = jdbcTemplate.clearAdr009Tables()

    @AfterEach
    fun clearLogsAfter() = jdbcTemplate.clearAdr009Tables()

    private var seatNo = 0
    protected fun seat(): ProductSeat = createSeat(++seatNo)
    protected val s get() = schedule.id!!

    /** 시나리오 안의 쌍. [expiring] = 만료될 홀드 좌석, [confirmed] = 확정 예약 좌석(만료와 무관하게 남는 기여). */
    data class DriftPair(val userId: Long, val expiring: List<ProductSeat>, val confirmed: List<ProductSeat>)

    private fun pair(userId: Long, holds: Int, confirms: Int): DriftPair {
        val confirmed = (1..confirms).map { seat().also { st ->
            val h = holdSeat.hold(HoldSeatCommand(s, st.id!!, userId))
            confirm.confirm(ConfirmReservationCommand(holdId = h.holdId, userId = userId, paymentUid = "p-$userId-${st.id}"))
        } }
        val expiring = (1..holds).map { seat().also { st -> holdSeat.hold(HoldSeatCommand(s, st.id!!, userId)) } }
        return DriftPair(userId, expiring, confirmed)
    }

    // 쌍 구성(모두 열린 회차 — 마감과 무관하게 만료만 본다). 사용자 매수 상한 2 안에서 만든다.
    protected lateinit var missing: DriftPair      // 행 없음: 확정 1 + 만료 홀드 1, 쿼터 행 삭제 → 감소 대상 행 없음(실패)
    protected lateinit var negative: DriftPair     // cnt 작음·음수: 확정 1 + 만료 홀드 1(실제 2), cnt = 0 → 0 - 1 < 0(실패)
    protected lateinit var silent: DriftPair       // cnt 작음·조용함: 확정 1 + 만료 홀드 1(실제 2), cnt = 1 → 1 - 1 = 0(성공, 실제 1과 어긋난 채)
    protected lateinit var above: DriftPair        // cnt 큼: 만료 홀드 1(실제 1), cnt = 2 → 2 - 1 = 1(성공, 실제 0과 어긋난 채)
    protected lateinit var normalA: DriftPair      // 정상: 만료 홀드 2
    protected lateinit var normalB: DriftPair      // 정상: 확정 1 + 만료 홀드 1

    protected fun seedAndInject() {
        missing = pair(101, holds = 1, confirms = 1)
        negative = pair(102, holds = 1, confirms = 1)
        silent = pair(103, holds = 1, confirms = 1)
        above = pair(104, holds = 1, confirms = 0)
        normalA = pair(105, holds = 2, confirms = 0)
        normalB = pair(106, holds = 1, confirms = 1)
        // 전제: 주입 전 카운터 = 실제(앱 경로로 만들었다)
        listOf(missing, negative, silent, above, normalA, normalB).forEach {
            assertEquals(jdbcTemplate.actualOf(s, it.userId), jdbcTemplate.cntOf(s, it.userId), "$type 전제: 주입 전 cnt = 실제 (사용자 ${it.userId})")
        }
        jdbcTemplate.deleteQuota(s, missing.userId)
        jdbcTemplate.setCnt(s, negative.userId, 0)
        jdbcTemplate.setCnt(s, silent.userId, 1)
        jdbcTemplate.setCnt(s, above.userId, 2)
        clock.set(NOW.plus(properties.ttl).plusSeconds(1)) // 홀드 전부 만료 시각 지남(회차 시작 NOW + 1일 전 — 마감 아님)
    }

    protected fun allExpiringSeats(vararg pairs: DriftPair) = pairs.flatMap { it.expiring }
    protected fun holdRowsOf(userId: Long): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE schedule_id = ? AND user_id = ?", Int::class.java, s, userId)!!
    protected fun reservations() = jdbcTemplate.holdsAndReservations().second
}

/** A 켬 — 매수 방식은 카운터를 유지하는 앱 증감 방식 세 가지(B는 트리거라 따로 — CounterTriggerTest). */
abstract class ExpiryRepairOnTest(private val type: UserLimitStrategyType) : ExpiryDriftScenario(type) {

    @Test
    fun `A 켬 — 활성 매수 방식이 설정과 같다`() {
        assertEquals(type, properties.limitStrategy)
    }

    @Test
    fun `A — 실패 쌍(행 없음·음수)이 있어도 만료 전역 정지 0, 나머지 쌍은 이 틱에서 지금과 같이 만료·감소`() {
        seedAndInject()
        val reservationsBefore = reservations()

        expire.expire() // 예외 없이 끝나야 한다(지금은 check(updated == 1)·CHECK (cnt >= 0)이 배치 전체를 롤백)

        // 실패하지 않은 쌍: 좌석 AVAILABLE · 홀드 삭제 · 감소 = 지금과 같음
        allExpiringSeats(silent, above, normalA, normalB).forEach {
            assertEquals(SeatStatus.AVAILABLE, seatStatus(it), "$type: 좌석 ${it.id} — 실패 쌍 때문에 만료가 멈췄다")
        }
        listOf(silent, above, normalA, normalB).forEach { assertEquals(0, holdRowsOf(it.userId), "$type: 사용자 ${it.userId} 홀드") }
        assertEquals(0, jdbcTemplate.cntOf(s, normalA.userId), "$type: 정상 쌍 감소")
        assertEquals(1, jdbcTemplate.cntOf(s, normalB.userId), "$type: 정상 쌍 감소(확정 1 남음)")
        // A의 범위 밖: 감소가 성공한 어긋난 쌍은 고치지 않는다 — 지금과 같이 n만큼만 내린다
        assertEquals(0, jdbcTemplate.cntOf(s, silent.userId), "$type: 조용한 쌍은 A가 고치지 않는다(1 - 1)")
        assertEquals(1, jdbcTemplate.actualOf(s, silent.userId))
        assertEquals(1, jdbcTemplate.cntOf(s, above.userId), "$type: 큰 쌍은 A가 고치지 않는다(2 - 1)")
        assertEquals(0, jdbcTemplate.actualOf(s, above.userId))
        // 확정 예약은 만료·복구 어느 쪽도 건드리지 않는다
        assertEquals(reservationsBefore, reservations(), "$type: 예약 행이 바뀌었다")
        allExpiringSeats(missing, negative).forEach { st ->
            assertTrue(seatStatus(st) in setOf(SeatStatus.AVAILABLE, SeatStatus.HELD), "$type: 실패 쌍 좌석 ${st.id} = ${seatStatus(st)}")
        }
    }

    @Test
    fun `A — 실패 쌍은 실제 값으로 복구되고(0으로 자르지 않는다), 다음 틱까지 가도 그 쌍 홀드도 만료된다`() {
        seedAndInject()
        expire.expire()
        expire.expire() // 실패 쌍을 그 틱에서 만료까지 하든, 고치고 다음 틱에 만료하든 둘 다 허용 — 두 틱 뒤 끝 상태로 판정

        listOf(missing, negative).forEach { p ->
            assertEquals(0, holdRowsOf(p.userId), "$type: 사용자 ${p.userId}의 만료 홀드가 두 틱 뒤에도 남았다")
            p.expiring.forEach { assertEquals(SeatStatus.AVAILABLE, seatStatus(it), "$type: 좌석 ${it.id}") }
            // 확정 1이 남으므로 실제 = 1. 행 없음 쌍도 행이 다시 생겨야 하고, 음수 쌍은 0으로 잘리면 안 된다
            assertEquals(1, jdbcTemplate.actualOf(s, p.userId))
            assertEquals(1, jdbcTemplate.cntOf(s, p.userId), "$type: 사용자 ${p.userId} cnt = 실제")
        }
    }

    @Test
    fun `A — 무음 금지 — 실패 쌍마다 기록 테이블 1행(expiry-repair · repaired)·지표 +1, 감소가 성공한 쌍은 A 기록 없음`() {
        seedAndInject()
        val missing0 = meters.countOf("seat.hold.counter.drift", "source" to "expiry-repair", "kind" to "missing", "action" to "repaired")
        val below0 = meters.countOf("seat.hold.counter.drift", "source" to "expiry-repair", "kind" to "below", "action" to "repaired")

        expire.expire()
        expire.expire()

        val logs = jdbcTemplate.driftLogs().filter { it.source == "expiry-repair" }
        assertEquals(setOf(missing.userId, negative.userId), logs.map { it.userId }.toSet(), "$type: A 기록 대상 = 실패 쌍만 — $logs")
        assertEquals(2, logs.size, "$type: 실패 쌍마다 정확히 1행 — $logs")
        val m = logs.single { it.userId == missing.userId }
        assertEquals(s, m.scheduleId)
        assertEquals("missing", m.kind)
        assertNull(m.cntBefore, "행 없음 쌍의 cnt_before = NULL")
        assertEquals("repaired", m.action)
        val n = logs.single { it.userId == negative.userId }
        assertEquals("below", n.kind)
        assertEquals(0, n.cntBefore, "주입한 cnt")
        assertEquals("repaired", n.action)
        assertEquals(1.0, meters.countOf("seat.hold.counter.drift", "source" to "expiry-repair", "kind" to "missing", "action" to "repaired") - missing0)
        assertEquals(1.0, meters.countOf("seat.hold.counter.drift", "source" to "expiry-repair", "kind" to "below", "action" to "repaired") - below0)
    }

    @Test
    fun `A — 어긋남이 없으면 지금과 같다(기록 0, 전부 만료·감소, 카운터 = 실제)`() {
        val users = listOf(201L, 202L, 203L)
        val seats = users.map { u -> seat().also { holdSeat.hold(HoldSeatCommand(s, it.id!!, u)) } }
        clock.set(NOW.plus(properties.ttl).plusSeconds(1))

        assertEquals(3, expire.expire())

        seats.forEach { assertEquals(SeatStatus.AVAILABLE, seatStatus(it)) }
        users.forEach { assertEquals(0, jdbcTemplate.cntOf(s, it)) }
        assertEquals(emptyList(), jdbcTemplate.driftLogs())
    }

    @Test
    fun `지표 — 실패 쌍이 있어도 스케줄러 틱은 outcome=ok로 끝난다`() {
        seedAndInject()
        val ok0 = meters.countOf("seat.hold.expiry.tick", "outcome" to "ok")
        val failed0 = meters.countOf("seat.hold.expiry.tick", "outcome" to "failed")

        scheduler.expire()

        assertEquals(1.0, meters.countOf("seat.hold.expiry.tick", "outcome" to "ok") - ok0, "$type: tick ok +1")
        assertEquals(0.0, meters.countOf("seat.hold.expiry.tick", "outcome" to "failed") - failed0, "$type: tick failed 0")
    }
}

@TestPropertySource(properties = ["seat.hold.limit-strategy=counter-upsert-precheck", "seat.hold.counter-drift.expiry-repair=true"])
class ExpiryRepairPrecheckTest : ExpiryRepairOnTest(UserLimitStrategyType.COUNTER_UPSERT_PRECHECK)

@TestPropertySource(properties = ["seat.hold.limit-strategy=counter-upsert", "seat.hold.counter-drift.expiry-repair=true"])
class ExpiryRepairUpsertTest : ExpiryRepairOnTest(UserLimitStrategyType.COUNTER_UPSERT)

@TestPropertySource(properties = ["seat.hold.limit-strategy=counter", "seat.hold.counter-drift.expiry-repair=true"])
class ExpiryRepairCounterTest : ExpiryRepairOnTest(UserLimitStrategyType.COUNTER)

/**
 * 대조군 — A 끔(기본값). 명세 §2 '기본값 외 동작 불변': 지금과 같이 어긋난 쌍 하나가 만료 배치 전체를 롤백한다.
 * 이 클래스는 주입이 실제로 감소 실패를 만든다는 것도 보인다(위 A 테스트가 공허하지 않다는 근거).
 */
@TestPropertySource(properties = ["seat.hold.limit-strategy=counter-upsert-precheck"])
class ExpiryRepairOffTest : ExpiryDriftScenario(UserLimitStrategyType.COUNTER_UPSERT_PRECHECK) {

    @Test
    fun `A 끔 — 실패 쌍 하나가 배치 전체를 멈춘다(지금 동작 그대로 · 아무 홀드도 만료되지 않음 · 기록 없음)`() {
        seedAndInject()
        val before = jdbcTemplate.holdsAndReservations()
        try {
            expire.expire()
            fail("A를 껐는데 만료 배치가 끝났다 — 주입이 감소 실패를 만들지 못했거나 기본값 동작이 바뀌었다")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            // 예상: 행 없음 check 실패 또는 CHECK (cnt >= 0) 위반
        }
        assertEquals(before, jdbcTemplate.holdsAndReservations(), "롤백 — 홀드·예약 그대로")
        allExpiringSeats(silent, above, normalA, normalB).forEach { assertEquals(SeatStatus.HELD, seatStatus(it)) }
        assertEquals(null, jdbcTemplate.cntOf(s, missing.userId), "끔이면 복구하지 않는다")
        assertEquals(0, jdbcTemplate.cntOf(s, negative.userId))
        assertEquals(0, jdbcTemplate.rowCount("counter_drift_log"))
    }

    @Test
    fun `지표 — A 끔이면 스케줄러 틱이 outcome=failed로 기록된다`() {
        seedAndInject()
        val ok0 = meters.countOf("seat.hold.expiry.tick", "outcome" to "ok")
        val failed0 = meters.countOf("seat.hold.expiry.tick", "outcome" to "failed")

        runCatching { scheduler.expire() } // 스케줄러가 예외를 삼키든 던지든 지표만 본다

        assertEquals(1.0, meters.countOf("seat.hold.expiry.tick", "outcome" to "failed") - failed0, "tick failed +1")
        assertEquals(0.0, meters.countOf("seat.hold.expiry.tick", "outcome" to "ok") - ok0, "tick ok 0")
    }
}
