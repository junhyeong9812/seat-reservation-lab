package com.jun.labs.seatreservation.service.hold

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.HoldStrategyType.NONE
import com.jun.labs.seatreservation.service.HoldStrategyType.PESSIMISTIC
import com.jun.labs.seatreservation.service.HoldStrategyType.PESSIMISTIC_NOWAIT
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategy
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-003 DB 계열 전략(Redis·유니크 제외 — 각각 별도 컨텍스트). 모든 전략 빈은 한 컨텍스트에 있고 기본 활성 전략은 none이다.
 * 판정은 응답과 DB 행을 함께 본다 — 성공 응답 수 = 그 좌석의 홀드 행 수(응답-DB 대조, ADR-001).
 */
class HoldStrategyTest : IntegrationTest() {

    @Autowired lateinit var strategies: List<HoldStrategy>
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var properties: SeatHoldProperties

    private fun strategy(type: HoldStrategyType) = strategies.single { it.type == type }

    private fun holdRowsOf(seatId: Long): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE seat_id = ?", Int::class.java, seatId)!!

    @ParameterizedTest
    @EnumSource(names = ["NONE", "JVM_LOCK", "JVM_LOCK_IN_TX", "CONDITIONAL_UPDATE", "PESSIMISTIC", "PESSIMISTIC_NOWAIT", "OPTIMISTIC", "ADVISORY"])
    fun `응답 계약은 전략과 무관하다 — 빈 좌석 성공, 선점된 좌석 409, 없는 좌석 404`(type: HoldStrategyType) {
        val s = strategy(type)
        val seat = createSeat(1)

        val result = s.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId = 1))
        assertEquals(seat.id, result.seatId)
        assertEquals(SeatStatus.HELD, seatStatus(seat))

        val taken = assertThrows<SeatReservationException> { s.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId = 2)) }
        assertEquals(ErrorCode.SEAT_NOT_AVAILABLE, taken.errorCode)

        val missing = assertThrows<SeatReservationException> { s.hold(HoldSeatCommand(schedule.id!!, seat.id!! + 999, userId = 3)) }
        assertEquals(ErrorCode.SEAT_NOT_FOUND, missing.errorCode)
        assertEquals(1, holdRowsOf(seat.id!!))
    }

    @ParameterizedTest
    @EnumSource(names = ["JVM_LOCK", "CONDITIONAL_UPDATE", "PESSIMISTIC", "PESSIMISTIC_NOWAIT", "OPTIMISTIC", "ADVISORY"])
    fun `경합을 막는 전략 — 같은 좌석 동시 50명 중 정확히 1명만 이기고 나머지는 409, 홀드 행 1`(type: HoldStrategyType) {
        val seat = createSeat(1)

        val outcomes = race(strategy(type), schedule.id!!, seat.id!!)

        assertEquals(mapOf("ok" to 1, ErrorCode.SEAT_NOT_AVAILABLE.name to 49), outcomes.groupingBy { it }.eachCount(), "$type: $outcomes")
        assertEquals(1, holdRowsOf(seat.id!!))
        assertEquals(SeatStatus.HELD, seatStatus(seat))
    }

    @ParameterizedTest
    @EnumSource(names = ["NONE", "JVM_LOCK_IN_TX"])
    fun `막지 않는 전략(대조군·함정) — 중복이 나도 응답과 DB는 일치한다`(type: HoldStrategyType) {
        val seat = createSeat(1)

        val outcomes = race(strategy(type), schedule.id!!, seat.id!!)

        assertTrue(outcomes.all { it == "ok" || it == ErrorCode.SEAT_NOT_AVAILABLE.name }, "$type: $outcomes")
        val wins = outcomes.count { it == "ok" }
        assertTrue(wins >= 1)
        assertEquals(wins, holdRowsOf(seat.id!!), "$type: 성공 응답 수 = 홀드 행 수")
    }

    @Test
    fun `NOWAIT는 잠긴 좌석을 기다리지 않고 바로 409 — 일반 비관락은 기다린다`() {
        val seat = createSeat(1)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = CompletableFuture.runAsync {
            tx.execute {
                productSeatRepository.findForUpdate(seat.id!!, schedule.id!!)
                locked.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        locked.await(5, TimeUnit.SECONDS)
        try {
            val started = System.nanoTime()
            val e = assertThrows<SeatReservationException> {
                strategy(PESSIMISTIC_NOWAIT).hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId = 1))
            }
            assertEquals(ErrorCode.SEAT_NOT_AVAILABLE, e.errorCode)
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "NOWAIT인데 기다렸다")

            val waiting = CompletableFuture.supplyAsync {
                strategy(PESSIMISTIC).hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId = 2))
            }
            Thread.sleep(500)
            assertTrue(!waiting.isDone, "비관락은 앞 트랜잭션이 끝날 때까지 기다려야 한다")
            release.countDown()
            assertEquals(seat.id, waiting.get(10, TimeUnit.SECONDS).seatId) // 앞 트랜잭션은 아무것도 안 바꿨으니 이긴다
        } finally {
            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `선택된 활성 전략은 설정 기본값 none`() {
        assertEquals(NONE, properties.strategy)
    }
}
