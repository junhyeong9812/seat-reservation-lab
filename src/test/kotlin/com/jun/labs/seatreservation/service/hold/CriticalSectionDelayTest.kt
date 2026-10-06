package com.jun.labs.seatreservation.service.hold

import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategy
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-004 실험 장치 — 임계 구역 지연이 설정대로 바인딩되고, 선점 경로 안에서(트랜잭션을 쥔 채) 걸린다. */
@TestPropertySource(properties = ["seat.hold.critical-section-delay=300ms"])
class CriticalSectionDelayTest : IntegrationTest() {

    @Autowired lateinit var strategies: List<HoldStrategy>
    @Autowired lateinit var properties: SeatHoldProperties

    @Test
    fun `지연이 바인딩되고 선점 한 번이 그만큼 걸린다`() {
        assertEquals(Duration.ofMillis(300), properties.criticalSectionDelay)
        val seat = createSeat(1)

        val started = System.nanoTime()
        strategies.single { it.type == HoldStrategyType.NONE }.hold(HoldSeatCommand(schedule.id!!, seat.id!!, userId = 1))

        assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(300))
    }

    @Test
    fun `대기형(비관락)은 지연 동안 락을 쥐어 같은 좌석의 경쟁자가 그만큼 기다린 뒤 진다`() {
        val seat = createSeat(1)
        val started = System.nanoTime()

        val outcomes = race(strategies.single { it.type == HoldStrategyType.PESSIMISTIC }, schedule.id!!, seat.id!!, n = 5)

        assertEquals(1, outcomes.count { it == "ok" })
        assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(300), "이긴 쪽의 지연이 진 쪽을 붙잡아야 한다")
    }
}
