package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import kotlin.test.assertEquals

/** 후보 A(expiry-repair) — 실패 쌍만 복구, 나머지 정상 만료 · 틱 롤백 시 독립 트랜잭션 skipped(IV-009-5). */
@TestPropertySource(properties = ["seat.hold.counter-drift.expiry-repair=true"])
class ExpiryRepairImplTest : DriftTestSupport() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase

    private fun holdFor(userId: Long) = holdSeat.hold(HoldSeatCommand(schedule.id!!, createSeat(userId.toInt()).id!!, userId))

    @Test
    fun `행 없음·cnt 작음 쌍만 복구·기록하고 나머지 쌍은 정상 감소 — 틱은 성공`() {
        val s = schedule.id!!
        listOf(1L, 2L, 3L).forEach { holdFor(it) }
        holdFor(2L) // 사용자 2는 홀드 2개
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 1", s) // 행 없음
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 1 WHERE schedule_id = ? AND user_id = 2", s) // 실제 2인데 1 → 2개 만료 시 음수 경로
        val ok0 = counter("seat.hold.expiry.tick", "outcome", "ok")
        clock.advance(Duration.ofMinutes(6))

        assertEquals(4, expire.expire())

        assertEquals(null, cntOf(s, 1), "행 없음 + 실제 0(만료 뒤) → 행을 만들지 않는다(없음 = 0, 코드 루프 1 L1-8)")
        assertEquals(0, cntOf(s, 2))
        assertEquals(0, cntOf(s, 3), "정상 쌍은 그대로 감소")
        assertEquals(
            listOf(listOf<Any?>(1L, "missing", "repaired", null, "expiry-repair"), listOf<Any?>(2L, "below", "repaired", null, "expiry-repair")),
            driftLog(),
        )
        assertEquals(1.0, counter("seat.hold.expiry.tick", "outcome", "ok") - ok0)
        assertEquals(0, holdCount())
    }

    @Test
    fun `틱이 롤백되면 시도한 쌍마다 독립 트랜잭션으로 skipped(reason = SQLSTATE) — 복구도 함께 사라진다`() {
        val s = schedule.id!!
        holdFor(1L)
        holdFor(9L)
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 1", s)
        // flush(홀드 DELETE) 때 실패를 만든다 — 사용자 9의 홀드 삭제에서 예외
        jdbcTemplate.execute(
            """
            CREATE FUNCTION test_fail_delete() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN IF OLD.user_id = 9 THEN RAISE EXCEPTION 'test failure'; END IF; RETURN OLD; END ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute("CREATE TRIGGER test_fail_delete BEFORE DELETE ON seat_hold FOR EACH ROW EXECUTE FUNCTION test_fail_delete()")
        val failed0 = counter("seat.hold.expiry.tick", "outcome", "failed")
        try {
            clock.advance(Duration.ofMinutes(6))
            assertThrows<Exception> { expire.expire() }
        } finally {
            jdbcTemplate.execute("DROP TRIGGER test_fail_delete ON seat_hold")
            jdbcTemplate.execute("DROP FUNCTION test_fail_delete()")
        }
        assertEquals(null, cntOf(s, 1), "롤백 — 복구로 만든 행도 사라진다")
        assertEquals(listOf(listOf<Any?>(1L, "missing", "skipped", "P0001", "expiry-repair")), driftLog())
        assertEquals(1.0, counter("seat.hold.expiry.tick", "outcome", "failed") - failed0)
        assertEquals(2, holdCount(), "만료도 롤백")

        // 다음 틱(실패 원인 제거 후)이 같은 쌍을 다시 복구한다
        assertEquals(2, expire.expire())
        assertEquals(null, cntOf(s, 1), "실제 0 — 행 없음 = 0(L1-8)")
        assertEquals("repaired", driftLog().last()[2])
    }
}

/** 키를 끄면(기본) ADR-008 동작 그대로 — 어긋난 쌍 하나가 틱 전체를 롤백한다. */
class ExpiryRepairOffImplTest : DriftTestSupport() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase

    @Test
    fun `끈 상태 — 행 없는 쌍이 있으면 틱 실패, 다른 쌍도 만료되지 않고 기록 없음`() {
        val s = schedule.id!!
        holdSeat.hold(HoldSeatCommand(s, createSeat(1).id!!, 1L))
        holdSeat.hold(HoldSeatCommand(s, createSeat(2).id!!, 2L))
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 1", s)
        clock.advance(Duration.ofMinutes(6))
        assertThrows<IllegalStateException> { expire.expire() }
        assertEquals(2, holdCount())
        assertEquals(1, cntOf(s, 2))
        assertEquals(emptyList(), driftLog())
    }
}
