package com.jun.labs.seatreservation.adr018metricsimpl

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr018impl.G_A_ON
import com.jun.labs.seatreservation.adr018impl.G_COMP_SCHED_OFF
import com.jun.labs.seatreservation.adr018impl.G_ENABLED
import com.jun.labs.seatreservation.adr018impl.G_ONE_SHOT
import com.jun.labs.seatreservation.adr018impl.G_SCHED_OFF
import com.jun.labs.seatreservation.adr018impl.G_SPLIT
import com.jun.labs.seatreservation.service.impl.DbDeadlock
import com.jun.labs.seatreservation.service.impl.ExpireHoldsService
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_FLYWAY_BASE
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.dao.CannotAcquireLockException
import org.springframework.test.context.ActiveProfiles
import java.sql.SQLException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 1.1 seat.group.request — op마다 2xx · 도메인 거부 · 비2xx(400)에서 정확히 1(그 op · outcome에만) */
abstract class GroupRequestCases : GroupMetricsBase() {
    private val u = 7L
    private val other = 8L

    @Test
    fun `hold — ok · 도메인 거부(코드 이름) · 본문 결함은 INVALID_SEAT_SELECTION · 헤더 없음 400은 status_400`() {
        val (a, b, c) = seatIds(3)
        assertEquals(201, countsOnce("hold", "ok") { groupHold(u, a, b) }.status)
        assertEquals(409, countsOnce("hold", "SEAT_NOT_AVAILABLE") { groupHold(other, b, c) }.status)
        assertEquals(400, countsOnce("hold", "INVALID_SEAT_SELECTION") { groupHold(other, c, c) }.status)
        assertEquals(400, countsOnce("hold", "status_400") { groupHold(null, c) }.status)
    }

    @Test
    fun `release_group · release_hold — ok 204 · 거부 코드 · status_400`() {
        val (a, b, c) = seatIds(3)
        val group = holdGroupOk(u, a, b)
        val holdInGroup = q("SELECT min(id) FROM seat_hold WHERE group_id = ?", group)
        assertEquals(409, countsOnce("release_hold", "HOLD_IN_GROUP") { call("DELETE", "/api/holds/$holdInGroup", u) }.status)
        assertEquals(403, countsOnce("release_group", "HOLD_NOT_OWNED") { call("DELETE", "/api/hold-groups/$group", other) }.status)
        assertEquals(400, countsOnce("release_group", "status_400") { call("DELETE", "/api/hold-groups/$group", null) }.status)
        assertEquals(204, countsOnce("release_group", "ok") { call("DELETE", "/api/hold-groups/$group", u) }.status)
        val single = holdOneOk(other, c)
        assertEquals(400, countsOnce("release_hold", "status_400") { call("DELETE", "/api/holds/$single", null) }.status)
        assertEquals(204, countsOnce("release_hold", "ok") { call("DELETE", "/api/holds/$single", other) }.status)
        assertEquals(404, countsOnce("release_hold", "HOLD_NOT_FOUND") { call("DELETE", "/api/holds/$single", other) }.status)
    }

    @Test
    fun `pay_start · confirm — ok · 거부 코드 · 본문 결함 status_400`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        assertEquals(404, countsOnce("pay_start", "HOLD_NOT_FOUND") { call("POST", "/api/hold-groups/${group + 999}/payment-start", u) }.status)
        assertEquals(400, countsOnce("pay_start", "status_400") { call("POST", "/api/hold-groups/$group/payment-start", null) }.status)
        countsOnce("pay_start", "ok") { assertEquals(200, call("POST", "/api/hold-groups/$group/payment-start", u).status) }
        val uid = jdbcTemplate.queryForObject("SELECT uid FROM payment_intent WHERE group_id = ?", String::class.java, group)!!
        assertEquals(400, countsOnce("confirm", "status_400") { call("POST", "/api/hold-groups/$group/confirm", u, "not json") }.status)
        assertEquals(409, countsOnce("confirm", "SEAT_NOT_HELD") { call("POST", "/api/hold-groups/$group/confirm", u, """{"paymentUid":"nope"}""") }.status)
        assertEquals(200, countsOnce("confirm", "ok") { call("POST", "/api/hold-groups/$group/confirm", u, """{"paymentUid":"$uid"}""") }.status)
        // 재호출(재생)도 요청 하나 = 1
        assertEquals(200, countsOnce("confirm", "ok") { call("POST", "/api/hold-groups/$group/confirm", u, """{"paymentUid":"$uid"}""") }.status)
    }

    @Test
    fun `1석 API(선점 · 결제 시작 · 확정)는 seat_group_request를 올리지 않고 seat_confirm 지표는 그대로 오른다`() {
        val seat = createSeat(50).id!!
        val total = requestTotal()
        val confirmRejected = count("seat.confirm.rejected")
        val hold = holdOneOk(u, seat)
        assertEquals(409, call("POST", "/api/schedules/${schedule.id}/seats/$seat/hold", other).status)
        assertEquals(200, holdPaymentStart(u, hold).status)
        assertTrue(holdConfirm(u, hold, "wrong-uid").status in 400..499, "1석 확정 도메인 거부")
        assertEquals(1.0, count("seat.confirm.rejected") - confirmRejected, "1석 확정 거부는 지금처럼 센다")
        assertEquals(0.0, requestTotal() - total, "1석 경로는 묶음 지표에 없다")
    }

    @Test
    fun `40P01로 실패한 묶음 API — 응답 500 그대로 · status_500 · seat_db_deadlock 경로별 1`() {
        val (a, b, c, d) = seatIds(4)
        val dl = deadlockTotal()
        fun delta(path: String, before: Double, block: () -> Unit) {
            block()
            assertEquals(1.0, deadlock(path) - before, "seat.db.deadlock{path=$path}")
        }
        delta("group_hold", deadlock("group_hold")) {
            failing("hold_group", "INSERT", DbDeadlock.SQLSTATE) {
                assertEquals(500, countsOnce("hold", "status_500") { groupHold(u, a, b) }.status)
            }
        }
        assertEquals(0L, holdsOfUser(u), "롤백 — 홀드 없음")
        val group = holdGroupOk(u, a, b)
        delta("release", deadlock("release")) {
            failing("seat_hold", "DELETE", DbDeadlock.SQLSTATE) {
                assertEquals(500, countsOnce("release_group", "status_500") { call("DELETE", "/api/hold-groups/$group", u) }.status)
            }
        }
        delta("group_pay_start", deadlock("group_pay_start")) {
            failing("seat_hold", "UPDATE", DbDeadlock.SQLSTATE) {
                assertEquals(500, countsOnce("pay_start", "status_500") { call("POST", "/api/hold-groups/$group/payment-start", u) }.status)
            }
        }
        assertEquals(200, call("POST", "/api/hold-groups/$group/payment-start", u).status)
        val uid = jdbcTemplate.queryForObject("SELECT uid FROM payment_intent WHERE group_id = ?", String::class.java, group)!!
        delta("group_confirm", deadlock("group_confirm")) {
            failing("reservation", "INSERT", DbDeadlock.SQLSTATE) {
                assertEquals(500, countsOnce("confirm", "status_500") { call("POST", "/api/hold-groups/$group/confirm", u, """{"paymentUid":"$uid"}""") }.status)
            }
        }
        val single = holdOneOk(other, c)
        delta("release", deadlock("release")) { // 1석 해제도 path=release
            failing("seat_hold", "DELETE", DbDeadlock.SQLSTATE) {
                assertEquals(500, countsOnce("release_hold", "status_500") { call("DELETE", "/api/holds/$single", other) }.status)
            }
        }
        assertEquals(5.0, deadlockTotal() - dl)
        // 다른 SQLSTATE의 500은 교착으로 세지 않는다
        failing("hold_group", "INSERT", "P0001") {
            assertEquals(500, countsOnce("hold", "status_500") { groupHold(other, d) }.status)
        }
        assertEquals(5.0, deadlockTotal() - dl)
        assertNoViolations("실패 주입 뒤")
    }

    @Test
    fun `묶음 확정 거부 기록 트랜잭션이 40P01 — 응답은 기존 거부 그대로 · group_confirm 1 · request는 그 코드로 1(이중 셈 없음)`() {
        val (a, b) = seatIds(2)
        val group = holdGroupOk(u, a, b)
        assertEquals(200, call("POST", "/api/hold-groups/$group/payment-start", u).status)
        val uid = jdbcTemplate.queryForObject("SELECT uid FROM payment_intent WHERE group_id = ?", String::class.java, group)!!
        clock.set(NOW.plus(Duration.ofHours(1))) // 홀드 만료 → 확정 거부(HOLD_EXPIRED) → 거부 기록 트랜잭션
        val dl = deadlockTotal()
        val confirmDl = deadlock(DbDeadlock.PATH_GROUP_CONFIRM)
        val r = failing("confirm_rejection", "INSERT", DbDeadlock.SQLSTATE) {
            countsOnce("confirm", "HOLD_EXPIRED") { call("POST", "/api/hold-groups/$group/confirm", u, """{"paymentUid":"$uid"}""") }
        }
        assertEquals(409 to "HOLD_EXPIRED", r.status to r.code)
        assertEquals(1.0, deadlock(DbDeadlock.PATH_GROUP_CONFIRM) - confirmDl)
        assertEquals(1.0, deadlockTotal() - dl, "다른 path로 겹쳐 세지 않는다")
        assertEquals("OPEN", jdbcTemplate.queryForObject("SELECT state FROM payment_intent WHERE uid = ?", String::class.java, uid), "기록 실패 — 결제 의도는 OPEN(기존 동작)")
    }

    @Test
    fun `만료 틱이 40P01로 롤백 — expiry_tick 1 · tick failed도 1 · 다른 SQLSTATE는 failed만`() {
        val (a, b) = seatIds(2)
        holdGroupOk(u, a, b)
        clock.set(NOW.plus(Duration.ofMinutes(6)))
        val failed = count(ExpireHoldsService.TICK_METRIC, "outcome" to "failed")
        val tick = deadlock(DbDeadlock.PATH_EXPIRY_TICK)
        failing("seat_hold", "DELETE", DbDeadlock.SQLSTATE) { assertTrue(runCatching { expire.expire() }.isFailure, "틱 롤백") }
        assertEquals(1.0, deadlock(DbDeadlock.PATH_EXPIRY_TICK) - tick)
        assertEquals(1.0, count(ExpireHoldsService.TICK_METRIC, "outcome" to "failed") - failed)
        failing("seat_hold", "DELETE", "P0001") { assertTrue(runCatching { expire.expire() }.isFailure) }
        assertEquals(1.0, deadlock(DbDeadlock.PATH_EXPIRY_TICK) - tick, "40P01이 아니면 세지 않는다")
        assertEquals(2.0, count(ExpireHoldsService.TICK_METRIC, "outcome" to "failed") - failed)
        assertEquals(2, expire.expire(), "트리거를 지운 뒤 다음 틱은 정상")
    }
}

/** 1.2 seat.group.hold.rejected · 헤더(loadtest) — 공통: 규칙 409는 rule · 1석 지표 무변화 · 1석 409는 지금 그대로 */
abstract class GroupRejectLoadtestCases : GroupRequestCases() {
    abstract val mode: String
    private val u = 21L
    private val other = 22L

    private fun assertGroupReject(cause: String, block: () -> H): H {
        val before = groupRejected(mode, cause)
        val total = groupRejectedTotal()
        val single = holdRejectedTotal()
        val r = block()
        assertEquals(409, r.status, "$r")
        assertEquals("group-$cause", r.reject, "$r")
        assertEquals(1.0, groupRejected(mode, cause) - before, "$mode/$cause: $r")
        assertEquals(1.0, groupRejectedTotal() - total, "한 갈래에만: $r")
        assertEquals(0.0, holdRejectedTotal() - single, "묶음 선점은 seat.hold.rejected를 올리지 않는다: $r")
        return r
    }

    @Test
    fun `규칙 409 — 좌석 선점 불가 · 한도 초과 · 판매 마감은 rule`() {
        val (a, b, c, d) = seatIds(4)
        holdOneOk(other, b)
        assertEquals("SEAT_NOT_AVAILABLE", assertGroupReject("rule") { groupHold(u, a, b) }.code)
        holdOneOk(u, c)
        assertEquals("HOLD_LIMIT_EXCEEDED", assertGroupReject("rule") { groupHold(u, a, d) }.code)
        clock.set(SCHEDULE_STARTS_AT.plusSeconds(1))
        assertEquals("SCHEDULE_CLOSED", assertGroupReject("rule") { groupHold(other, a, d) }.code)
    }

    @Test
    fun `묶음 선점 2xx · 400 · 404에는 헤더 · 거절 지표 없음`() {
        val (a, b) = seatIds(2)
        val total = groupRejectedTotal()
        for (r in listOf(groupHold(u, a, b), groupHold(other, a, a), groupHold(other, a + 999, b + 999))) assertNull(r.reject, "$r")
        assertEquals(0.0, groupRejectedTotal() - total)
    }

    @Test
    fun `1석 선점 409는 지금 그대로 — seat_hold_rejected rule · 헤더 rule · 묶음 거절 지표 무변화`() {
        val seat = createSeat(60).id!!
        holdOneOk(u, seat)
        val rule = count(HOLD_REJECTED, "cause" to "rule")
        val group = groupRejectedTotal()
        val r = call("POST", "/api/schedules/${schedule.id}/seats/$seat/hold", other)
        assertEquals(409 to "rule", r.status to r.reject)
        assertEquals(1.0, count(HOLD_REJECTED, "cause" to "rule") - rule)
        assertEquals(0.0, groupRejectedTotal() - group)
    }

    /** 좌석 [locked]의 행을 다른 트랜잭션이 쥔 채 [block]을 보낸다 */
    protected fun <T> whileLocked(locked: Long, block: () -> T): T = tx.execute {
        jdbcTemplate.queryForList("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", Long::class.java, locked)
        block()
    }!!

    protected val user get() = u
}

@ActiveProfiles(LOADTEST)
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [G_SCHED_OFF, G_COMP_SCHED_OFF, G_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE, G_ENABLED, G_SPLIT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class GroupMetricsLoadtestSplitTest : GroupRejectLoadtestCases() {
    override val mode = "split"

    @Test
    fun `split NOWAIT 패배 — nowait · 헤더 group-nowait · 409 SEAT_NOT_AVAILABLE`() {
        val (a, b) = seatIds(2)
        val before = groupRejected(mode, "nowait")
        val single = holdRejectedTotal()
        val r = whileLocked(b) { groupHold(user, a, b) }
        assertEquals(Triple(409, "SEAT_NOT_AVAILABLE", "group-nowait"), Triple(r.status, r.code, r.reject))
        assertEquals(1.0, groupRejected(mode, "nowait") - before)
        assertEquals(0.0, holdRejectedTotal() - single)
        assertEquals(0L, holdsOfUser(user))
    }
}

@ActiveProfiles(LOADTEST)
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [G_SCHED_OFF, G_COMP_SCHED_OFF, G_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE, G_ENABLED, G_ONE_SHOT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class GroupMetricsLoadtestOneShotTest : GroupRejectLoadtestCases() {
    override val mode = "one_shot"

    @Test
    fun `one-shot 바뀐 행 수 부족 — short · 헤더 group-short · 409 SEAT_NOT_AVAILABLE`() {
        val (a, b) = seatIds(2)
        val before = groupRejected(mode, "short")
        val single = holdRejectedTotal()
        val r = whileLocked(b) { groupHold(user, a, b) } // SKIP LOCKED가 b를 건너뛴다 → 1행 < 2
        assertEquals(Triple(409, "SEAT_NOT_AVAILABLE", "group-short"), Triple(r.status, r.code, r.reject))
        assertEquals(1.0, groupRejected(mode, "short") - before)
        assertEquals(0.0, holdRejectedTotal() - single)
        assertEquals(0L, holdsOfUser(user))
        assertEquals(listOf("AVAILABLE", "AVAILABLE"), listOf(seatStatus(a), seatStatus(b)))
    }
}

/** loadtest가 아닌 프로필 — seat.group.request는 세고, 묶음 거절 지표 · 헤더는 없다 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [G_SCHED_OFF, G_COMP_SCHED_OFF, G_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, PIN_FLYWAY_BASE, G_ENABLED, G_SPLIT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
class GroupMetricsDefaultProfileTest : GroupRequestCases() {
    @Test
    fun `묶음 선점 409 — 헤더 없음 · seat_group_hold_rejected 없음 · seat_hold_rejected 없음`() {
        val (a, b) = seatIds(2)
        holdOneOk(9L, b)
        val group = groupRejectedTotal()
        val single = holdRejectedTotal()
        val r = countsOnce("hold", "SEAT_NOT_AVAILABLE") { groupHold(10L, a, b) }
        assertEquals(409, r.status)
        assertNull(r.reject)
        assertEquals(0.0, groupRejectedTotal() - group)
        assertEquals(0.0, holdRejectedTotal() - single)
    }
}

/** 1.3 예외 사슬 판정 단위 */
class DbDeadlockTest {
    @Test
    fun `사슬 어디든 SQLException 40P01이면 1(앞선 SQLSTATE가 달라도) · 다른 SQLSTATE · SQLException 없음은 0`() {
        val meters = SimpleMeterRegistry()
        DbDeadlock.record(meters, "group_hold", CannotAcquireLockException("x", SQLException("deadlock", "40P01")))
        DbDeadlock.record(meters, "group_hold", RuntimeException(IllegalStateException(SQLException("deadlock", "40P01"))))
        DbDeadlock.record(meters, "group_hold", CannotAcquireLockException("x", SQLException("nowait", "55P03")))
        // 앞선 SQLSTATE가 다른 중첩 — 바깥 XX000 · 안쪽 40P01이어도 센다(사슬 전체)
        DbDeadlock.record(meters, "group_hold", CannotAcquireLockException("x", SQLException("outer", "XX000", SQLException("deadlock", "40P01"))))
        DbDeadlock.record(meters, "group_hold", IllegalStateException("no sql"))
        assertEquals(3.0, meters.counter(DbDeadlock.METRIC, "path", "group_hold").count())
        assertEquals(1, meters.find(DbDeadlock.METRIC).counters().size)
    }
}
