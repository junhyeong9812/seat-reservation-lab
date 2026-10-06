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
import com.jun.labs.seatreservation.service.UserLimitStrategyType
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

    @Test
    fun `양성 대조 — 락 없음(none)에서 이 경합 장치는 중복을 실제로 만든다`() {
        assertTrue(maxWinsOver5Rounds(NONE) > 1, "NONE: 5라운드 동안 중복이 한 번도 안 났다 — 경합 장치가 경합을 만들지 못한다")
    }

    /**
     * 1b(트랜잭션 안 JVM 락)는 '락 해제 ~ 커밋' 틈에서만 깨진다. 로컬 테스트 DB는 커밋이 빨라 그 틈이 거의 없다 —
     * 실측(2026-10-03): 50명 × 5라운드에서 중복 0. 그래서 여기서는 중복을 요구하지 않고 응답-DB 일치만 본다(H2는 본측정이 판정).
     */
    @Test
    fun `함정(1b) — 중복 여부와 무관하게 응답과 DB는 일치한다`() {
        maxWinsOver5Rounds(HoldStrategyType.JVM_LOCK_IN_TX)
    }

    /** 새 좌석으로 최대 5라운드 경합 — 라운드마다 성공 응답 수 = 홀드 행 수를 단언하고, 중복이 나면 멈춘다. */
    private fun maxWinsOver5Rounds(type: HoldStrategyType): Int {
        var maxWins = 0
        for (round in 1..5) {
            val seat = createSeat(round)
            // 라운드마다 사용자 대역을 바꾼다 — 같은 사용자가 여러 라운드에서 이기면 1인 2매 제한(HOLD_LIMIT_EXCEEDED)에 걸린다
            val outcomes = race(strategy(type), schedule.id!!, seat.id!!, userIdBase = 1_000L * round)

            assertTrue(outcomes.all { it == "ok" || it == ErrorCode.SEAT_NOT_AVAILABLE.name }, "$type: $outcomes")
            val wins = outcomes.count { it == "ok" }
            assertEquals(wins, holdRowsOf(seat.id!!), "$type: 성공 응답 수 = 홀드 행 수")
            maxWins = maxOf(maxWins, wins)
            if (maxWins > 1) break
        }
        return maxWins
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
    fun `설정 기본값은 좌석 3b nowait(ADR-003 결정) · 매수 none`() {
        assertEquals(PESSIMISTIC_NOWAIT, properties.strategy)
        assertEquals(UserLimitStrategyType.NONE, properties.limitStrategy)
    }
}
