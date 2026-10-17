package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 코드 루프 3 L3-1 — B(counter-trigger) 선점은 마감 판정에 쓴 시각을 트리거 기준 시각으로 그대로 쓴다(Clock을 두 번 읽지 않는다).
 * 시계가 판정 읽기 직후 경계를 넘어도(MutableClock.setForOneRead) 판정이 '열림'이면 트리거도 증가한다.
 * 설정은 TriggerLoop1·2ImplTest와 같다(컨텍스트 공유).
 */
@TestPropertySource(
    properties = [
        "seat.hold.limit-strategy=counter-trigger",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-trigger",
        "seat.hold.counter-drift.reconcile.enabled=true",
        "seat.hold.counter-drift.reconcile.interval=1h",
    ],
)
class TriggerLoop3ImplTest : DriftTestSupport() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase

    @AfterEach
    fun truncate() = jdbcTemplate.execute("TRUNCATE reservation, seat_hold, user_hold_quota")

    @Test
    fun `판정 직후 시계가 마감을 넘어도 판정이 열림이면 트리거도 +1 — 한도 판정이 실제 홀드를 본다`() {
        val startsAt = Instant.parse("2099-01-01T00:00:00Z") // 벽시계로는 열린 회차(직접 쓰기 폴백과 무관)
        val z = newSchedule(startsAt).id!!
        val seat = jdbcTemplate.queryForObject(
            "INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'A', 1, 1, 'AVAILABLE') RETURNING id",
            Long::class.java, z,
        )!!
        // 선점 경로의 첫 Clock 읽기 = 마감 판정 → 열림(경계 1ms 전). 그 뒤 읽기는 마감 뒤(시계가 경계를 넘었다)
        clock.setForOneRead(startsAt.minusMillis(1), startsAt.plusSeconds(60))
        holdSeat.hold(HoldSeatCommand(z, seat, 1L))
        assertEquals(1, cntOf(z, 1L), "트리거가 다른 시각(마감 뒤)으로 판정해 증가를 건너뛰었다")
        assertEquals(actualOf(z, 1L), cntOf(z, 1L))
    }
}
