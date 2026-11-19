package com.jun.labs.seatreservation.adr018impl

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_A_OFF
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_FLYWAY_BASE
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals

/**
 * 해제 API는 기본 조합(sweep + pessimistic-nowait + counter-upsert-precheck)에서만 동작한다 — counter-trigger(V8 트리거가 카운터를 내린다)에서는 404 ·
 * 홀드 · 카운터 · 좌석 무변화(앱이 한 번 더 내리면 이중 감소로 한도가 뚫린다).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        G_SCHED_OFF, G_COMP_SCHED_OFF, PIN_A_OFF, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        "seat.hold.limit-strategy=counter-trigger",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-trigger",
        "seat.confirm.mode=current",
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class HoldReleaseUnsupportedTest : Adr018ImplBase() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase

    /** 다음 시험의 정리(DELETE)가 트리거를 타지 않게 — 기존 counter-trigger 시험과 같다 */
    @AfterEach
    fun truncate() = jdbcTemplate.execute("TRUNCATE reservation, seat_hold, user_hold_quota")

    @Test
    fun `counter-trigger — 1석 해제 404 · 홀드 · 카운터 · 좌석 그대로`() {
        val seat = createSeat(1).id!!
        val hold = holdSeat.hold(HoldSeatCommand(schedule.id!!, seat, 7L)).holdId
        assertEquals(1, cnt(7L))
        assertEquals(404 to "HOLD_NOT_FOUND", releaseHold(7L, hold).let { it.status to it.code })
        assertEquals(1L, holdsOfUser(7L))
        assertEquals(1, cnt(7L))
        assertEquals("HELD", seatStatus(seat))
    }
}

/** 확정 모드 current(JPA 확정 · 배치 — 좌석 → 홀드 잠금 · 결제 시작 없음) + 기본 조합: 1석 해제 404 · 무변화 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        G_SCHED_OFF, G_COMP_SCHED_OFF, PIN_A_OFF, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        PIN_FLYWAY_BASE, "seat.confirm.mode=current", "seat.compensation.mode=none",
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class HoldReleaseCurrentModeTest : Adr018ImplBase() {
    @Test
    fun `current — 1석 해제 404 · 홀드 · 카운터 · 좌석 그대로`() {
        val seat = createSeat(1).id!!
        val hold = holdOneOk(7L, seat)
        assertEquals(1, cnt(7L))
        assertEquals(404 to "HOLD_NOT_FOUND", releaseHold(7L, hold).let { it.status to it.code })
        assertEquals(1L, holdsOfUser(7L))
        assertEquals(1, cnt(7L))
        assertEquals("HELD", seatStatus(seat))
    }
}

/** 확정 모드 pessimistic(결제 시작 없음 — 결제 중 해제 거부가 서지 않는다) + 기본 조합: 1석 해제 404 · 무변화 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        G_SCHED_OFF, G_COMP_SCHED_OFF, PIN_A_OFF, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT,
        PIN_FLYWAY_BASE, "seat.confirm.mode=pessimistic", "seat.compensation.mode=none",
    ],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class HoldReleasePessimisticModeTest : Adr018ImplBase() {
    @Test
    fun `pessimistic — 1석 해제 404 · 홀드 · 카운터 · 좌석 그대로`() {
        val seat = createSeat(1).id!!
        val hold = holdOneOk(7L, seat)
        assertEquals(1, cnt(7L))
        assertEquals(404 to "HOLD_NOT_FOUND", releaseHold(7L, hold).let { it.status to it.code })
        assertEquals(1L, holdsOfUser(7L))
        assertEquals(1, cnt(7L))
        assertEquals("HELD", seatStatus(seat))
    }
}
