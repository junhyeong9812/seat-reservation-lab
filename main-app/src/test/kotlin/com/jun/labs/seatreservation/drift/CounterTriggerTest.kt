package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import com.jun.labs.seatreservation.service.limit.counterMismatch
import com.jun.labs.seatreservation.support.CounterReconciler
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.actualOf
import com.jun.labs.seatreservation.support.clearAdr009Tables
import com.jun.labs.seatreservation.support.cntOf
import com.jun.labs.seatreservation.support.deleteQuota
import com.jun.labs.seatreservation.support.driftLogs
import com.jun.labs.seatreservation.support.insertHoldRow
import com.jun.labs.seatreservation.support.insertQuota
import com.jun.labs.seatreservation.support.insertReservationRow
import com.jun.labs.seatreservation.support.insertSeatRow
import com.jun.labs.seatreservation.support.setCnt
import com.jun.labs.seatreservation.support.truncateHoldsReservationsQuota
import com.jun.labs.seatreservation.support.xminOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-009 후보 B(DB 트리거) — 명세 §1 B · §5 'B는 트리거 경로에서 어긋남이 생기지 않고(앱 증감 없이 cnt = 실제), DB를 직접 조작한 어긋남은 C·F와 조합할 때만 복구' ·
 * §10 D3(한도 판정은 앱 · 트리거는 엄격 — 행 없음·음수 → 오류, 0으로 자르지 않음 · 카운터 기여가 바뀌지 않는 UPDATE는 건너뜀).
 * 매수 방식 `counter-trigger` + 선택 위치 `classpath:db/migration-trigger`(contract.md).
 */
abstract class TriggerScenario : IntegrationTest() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase
    @Autowired lateinit var properties: SeatHoldProperties

    protected val s get() = schedule.id!!
    private var seatNo = 0
    protected fun freeSeat() = jdbcTemplate.insertSeatRow(s, ++seatNo)

    @BeforeEach
    fun clearLogs() = jdbcTemplate.clearAdr009Tables()

    /** 어긋남을 남긴 채 끝나도 다음 테스트의 resetData(행 단위 DELETE)가 엄격 트리거에 걸리지 않게 행 트리거 없이 비운다. */
    @AfterEach
    fun truncate() {
        jdbcTemplate.truncateHoldsReservationsQuota()
        jdbcTemplate.clearAdr009Tables()
    }

    protected fun code(c: HoldSeatCommand): String =
        try { holdSeat.hold(c); "ok" } catch (e: SeatReservationException) { e.errorCode.name }
}

@TestPropertySource(
    properties = [
        "seat.hold.limit-strategy=counter-trigger",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-trigger",
    ],
)
class CounterTriggerTest : TriggerScenario() {

    @Autowired lateinit var activeLimit: ActiveUserLimit

    @Test
    fun `활성 매수 방식 = COUNTER_TRIGGER`() {
        assertEquals(UserLimitStrategyType.COUNTER_TRIGGER, properties.limitStrategy)
        assertEquals(UserLimitStrategyType.COUNTER_TRIGGER, activeLimit.strategy.type)
    }

    @Test
    fun `정상 경로 — 선점·좌석 실패 롤백·확정·만료 뒤 cnt = 실제(앱이 따로 올리면 2배가 되어 드러난다)`() {
        val u = 801L
        val a = holdSeat.hold(HoldSeatCommand(s, freeSeat(), u))
        assertEquals(1, jdbcTemplate.cntOf(s, u), "선점 1건 = cnt 1(트리거 + 앱 증감이면 2)")
        val taken = freeSeat().also { holdSeat.hold(HoldSeatCommand(s, it, 802L)) }
        assertEquals(ErrorCode.SEAT_NOT_AVAILABLE.name, code(HoldSeatCommand(s, taken, u)))
        assertEquals(1, jdbcTemplate.cntOf(s, u), "좌석에서 진 요청은 롤백")
        holdSeat.hold(HoldSeatCommand(s, freeSeat(), u))
        assertEquals(2, jdbcTemplate.cntOf(s, u))
        confirm.confirm(ConfirmReservationCommand(holdId = a.holdId, userId = u, paymentUid = "p-801"))
        assertEquals(2, jdbcTemplate.cntOf(s, u), "확정: 홀드 → 예약(기여 그대로)")
        clock.set(NOW.plus(properties.ttl).plusSeconds(1))
        expire.expire()
        assertEquals(1, jdbcTemplate.actualOf(s, u))
        assertEquals(1, jdbcTemplate.cntOf(s, u), "만료 −1")
        assertEquals(0, jdbcTemplate.cntOf(s, 802L))
        assertEquals(0L, counterMismatch(jdbcTemplate))
    }

    @Test
    fun `한도 판정은 앱 — 같은 사용자 동시 10좌석은 정확히 2개 성공, 나머지 HOLD_LIMIT_EXCEEDED, cnt = 2`() {
        repeat(3) { r ->
            val u = 810L + r
            val seats = (1..10).map { freeSeat() }
            val pool = Executors.newFixedThreadPool(10)
            val ready = CountDownLatch(10)
            val start = CountDownLatch(1)
            val outcomes = try {
                seats.map { st ->
                    pool.submit<String> {
                        ready.countDown(); start.await()
                        try { holdSeat.hold(HoldSeatCommand(s, st, u)); "ok" }
                        catch (e: SeatReservationException) { e.errorCode.name }
                        catch (e: Throwable) { "ERR:${e::class.simpleName}:${e.message?.take(160)}" }
                    }
                }.also { ready.await(); start.countDown() }.map { it.get(60, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow() }
            assertEquals(mapOf("ok" to 2, ErrorCode.HOLD_LIMIT_EXCEEDED.name to 8), outcomes.groupingBy { it }.eachCount(), "라운드 $r: $outcomes")
            assertEquals(2, jdbcTemplate.cntOf(s, u))
        }
        assertEquals(0L, counterMismatch(jdbcTemplate))
    }

    @Test
    fun `DB가 고친다 — 앱을 거치지 않은 홀드·예약 INSERT·DELETE·상태 변경도 같은 트랜잭션에서 cnt에 반영`() {
        val u = 820L
        holdSeat.hold(HoldSeatCommand(s, freeSeat(), u)) // 쿼터 행이 생긴 상태에서 시작(행 없음 INSERT 경로의 엄격성은 명세가 정하지 않는다)
        val seat2 = jdbcTemplate.insertSeatRow(s, 9_002, SeatStatus.HELD)
        val h2 = jdbcTemplate.insertHoldRow(seat2, s, u, NOW, NOW.plusSeconds(300))
        assertEquals(2, jdbcTemplate.cntOf(s, u), "홀드 INSERT +1")
        jdbcTemplate.update("DELETE FROM seat_hold WHERE id = ?", h2)
        assertEquals(1, jdbcTemplate.cntOf(s, u), "홀드 DELETE −1")
        val seat3 = jdbcTemplate.insertSeatRow(s, 9_003, SeatStatus.RESERVED)
        val r = jdbcTemplate.insertReservationRow(s, seat3, u, "CONFIRMED")
        assertEquals(2, jdbcTemplate.cntOf(s, u), "CONFIRMED 예약 INSERT +1")
        jdbcTemplate.update("UPDATE reservation SET status = 'CANCELED' WHERE id = ?", r)
        assertEquals(1, jdbcTemplate.cntOf(s, u), "CONFIRMED → CANCELED −1")
        jdbcTemplate.update("UPDATE reservation SET status = 'CONFIRMED' WHERE id = ?", r)
        assertEquals(2, jdbcTemplate.cntOf(s, u), "CANCELED → CONFIRMED +1")
        assertEquals(jdbcTemplate.actualOf(s, u), jdbcTemplate.cntOf(s, u))
        val canceled = jdbcTemplate.insertReservationRow(s, jdbcTemplate.insertSeatRow(s, 9_004), u, "CANCELED")
        assertEquals(2, jdbcTemplate.cntOf(s, u), "CANCELED 예약 INSERT는 기여 없음")
        jdbcTemplate.update("DELETE FROM reservation WHERE id = ?", canceled)
        assertEquals(2, jdbcTemplate.cntOf(s, u))
        assertEquals(0L, counterMismatch(jdbcTemplate))
    }

    @Test
    fun `카운터 기여가 바뀌지 않는 UPDATE는 건너뛴다 — 쿼터 행에 쓰기 없음(xmin 그대로)`() {
        val u = 830L
        val h = holdSeat.hold(HoldSeatCommand(s, freeSeat(), u))
        val seat2 = jdbcTemplate.insertSeatRow(s, 9_010, SeatStatus.RESERVED)
        val r = jdbcTemplate.insertReservationRow(s, seat2, u, "CONFIRMED")
        val x = jdbcTemplate.xminOf(s, u)

        jdbcTemplate.update("UPDATE seat_hold SET expires_at = expires_at + interval '1 second' WHERE id = ?", h.holdId)
        jdbcTemplate.update("UPDATE reservation SET payment_uid = 'changed' WHERE id = ?", r)
        jdbcTemplate.update("UPDATE reservation SET status = 'CONFIRMED' WHERE id = ?", r)

        assertEquals(2, jdbcTemplate.cntOf(s, u))
        assertEquals(x, jdbcTemplate.xminOf(s, u), "기여가 그대로인 UPDATE가 쿼터 행을 썼다")
    }

    @Test
    fun `트리거는 엄격 — 쿼터 행이 없는데 홀드를 지우면 오류(홀드 그대로), 음수가 되면 오류(0으로 자르지 않음)`() {
        val missingUser = 840L
        val h1 = holdSeat.hold(HoldSeatCommand(s, freeSeat(), missingUser))
        jdbcTemplate.deleteQuota(s, missingUser)
        assertThrows<Exception>("행 없음인데 감소가 조용히 넘어갔다") { jdbcTemplate.update("DELETE FROM seat_hold WHERE id = ?", h1.holdId) }
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE id = ?", Int::class.java, h1.holdId), "오류로 롤백")
        assertEquals(null, jdbcTemplate.cntOf(s, missingUser), "행을 만들어 0으로 덮지 않는다")

        val belowUser = 841L
        val h2 = holdSeat.hold(HoldSeatCommand(s, freeSeat(), belowUser))
        jdbcTemplate.setCnt(s, belowUser, 0)
        assertThrows<Exception>("음수 감소가 조용히 넘어갔다") { jdbcTemplate.update("DELETE FROM seat_hold WHERE id = ?", h2.holdId) }
        assertEquals(0, jdbcTemplate.cntOf(s, belowUser), "0으로 자르지 않고 오류")
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE id = ?", Int::class.java, h2.holdId))
    }

    @Test
    fun `B 단독 — DB 직접 조작한 어긋남(쿼터 행만 바꿈)은 트리거가 고치지 않는다(C·F와 조합할 때만 복구 — 기록용 대조)`() {
        val u = 850L
        holdSeat.hold(HoldSeatCommand(s, freeSeat(), u))
        jdbcTemplate.setCnt(s, u, 2)
        holdSeat.hold(HoldSeatCommand(s, freeSeat(), 851L)) // 다른 쌍의 정상 쓰기
        assertEquals(2, jdbcTemplate.cntOf(s, u), "쿼터 행 직접 조작은 그대로 남는다")
        assertEquals(1, jdbcTemplate.actualOf(s, u))
    }
}

/** B + C — DB 직접 조작한 어긋남(세 갈래)은 대조가 복구한다. 복구 뒤에도 트리거 경로는 계속 맞다. */
@TestPropertySource(
    properties = [
        "seat.hold.limit-strategy=counter-trigger",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-trigger",
        "seat.hold.counter-drift.reconcile.enabled=true",
        "seat.hold.counter-drift.reconcile.interval=1h",
    ],
)
class CounterTriggerWithReconcileTest : TriggerScenario() {

    @Autowired lateinit var reconciler: CounterReconciler

    @Test
    fun `B + C — 행 없음·작음·큼 주입을 복구·기록하고, 복구 뒤 선점·만료도 cnt = 실제`() {
        holdSeat.hold(HoldSeatCommand(s, freeSeat(), 861L))
        holdSeat.hold(HoldSeatCommand(s, freeSeat(), 862L)); holdSeat.hold(HoldSeatCommand(s, freeSeat(), 862L))
        holdSeat.hold(HoldSeatCommand(s, freeSeat(), 863L))
        jdbcTemplate.deleteQuota(s, 861L)       // 행 없음(실제 1)
        jdbcTemplate.setCnt(s, 862L, 1)         // 작음(실제 2)
        jdbcTemplate.setCnt(s, 863L, 2)         // 큼(실제 1)
        jdbcTemplate.insertQuota(s, 864L, 1)    // 큼(실제 0)

        reconciler.runOnce("reconcile-app")

        listOf(861L, 862L, 863L, 864L).forEach {
            assertEquals(jdbcTemplate.actualOf(s, it), jdbcTemplate.cntOf(s, it) ?: 0, "사용자 $it")
        }
        assertEquals(1, jdbcTemplate.cntOf(s, 861L), "행 없음 쌍은 행을 다시 만든다")
        val logs = jdbcTemplate.driftLogs()
        assertEquals(
            mapOf(861L to "missing", 862L to "below", 863L to "above", 864L to "above"),
            logs.associate { it.userId to it.kind }, "$logs",
        )
        assertTrue(logs.all { it.action == "repaired" && it.source == "reconcile-app" }, "$logs")

        // 복구한 행 위에서 트리거 경로가 계속 맞는다(행 없음이었던 쌍의 만료 감소 포함)
        holdSeat.hold(HoldSeatCommand(s, freeSeat(), 863L))
        clock.set(NOW.plus(properties.ttl).plusSeconds(1))
        expire.expire()
        assertEquals(0L, counterMismatch(jdbcTemplate))
    }
}
