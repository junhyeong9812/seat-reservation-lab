package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.impl.drift.CounterReconciler
import com.jun.labs.seatreservation.service.impl.drift.CounterRepairProbe
import com.jun.labs.seatreservation.service.impl.drift.QuotaCleanup
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategyStartupCheck
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun sqlStateOf(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

/**
 * 코드 루프 2 — B(counter-trigger): L2-4 트리거 기준 시각 = 앱 Clock(선점·확정·만료) · 직접 DB 쓰기는 now() 폴백 · L2-5 cnt < n 만료 · L2-0 기동 검사.
 * 설정은 TriggerLoop1ImplTest·blind CounterTriggerWithReconcileTest와 같다(컨텍스트 공유).
 * 마감 경계는 '앱 시계와 DB 벽시계가 서로 다르게 보는' 회차로 만든다 — 앱이 기준 시각을 넣지 않았다면 결과가 달라져 드러난다.
 */
@TestPropertySource(
    properties = [
        "seat.hold.limit-strategy=counter-trigger",
        "spring.flyway.locations=classpath:db/migration,classpath:db/migration-trigger",
        "seat.hold.counter-drift.reconcile.enabled=true",
        "seat.hold.counter-drift.reconcile.interval=1h",
    ],
)
class TriggerLoop2ImplTest : DriftTestSupport() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase
    @Autowired lateinit var reconciler: CounterReconciler
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var redis: StringRedisTemplate

    @AfterEach
    fun truncate() = jdbcTemplate.execute("TRUNCATE reservation, seat_hold, user_hold_quota")

    private var seatNo = 7_000
    private fun seat(scheduleId: Long): Long = jdbcTemplate.queryForObject(
        "INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'A', 1, ?, 'AVAILABLE') RETURNING id",
        Long::class.java, scheduleId, ++seatNo,
    )!!

    /** 벽시계(DB now())로는 이미 마감, 앱 시계(NOW)로는 열린 회차 */
    private fun wallClosedAppOpen() = newSchedule(NOW.plusSeconds(600)).id!!

    /** 벽시계로는 열림(2099), 앱 시계를 그 뒤로 옮기면 마감 */
    private fun farSchedule(): Pair<Long, Instant> = newSchedule(Instant.parse("2099-01-01T00:00:00Z")).let { it.id!! to it.startsAt }

    @Test
    fun `L2-4 선점 — 트리거는 앱 시계로 판정한다(벽시계로는 마감인 회차도 앱 시계로 열려 있으면 +1) · 빌림 1회`() {
        val x = wallClosedAppOpen()
        val st = seat(x)
        val acq0 = hikariAcquires()
        holdSeat.hold(HoldSeatCommand(x, st, 1L))
        assertEquals(1L, hikariAcquires() - acq0, "B 선점 1건 = 커넥션 빌림 1(기준 시각 설정은 같은 트랜잭션 문장)")
        assertEquals(1, cntOf(x, 1L), "DB now()로 판정했다면 증가를 건너뛰어 0")
        assertEquals(actualOf(x, 1L), cntOf(x, 1L))
    }

    @Test
    fun `L2-4 확정 — 앱 시계를 마감 뒤로 옮기면 트리거도 마감으로 본다(행 없어도 확정 성공 — 벽시계 기준이면 SR001)`() {
        val (z, startsAt) = farSchedule()
        clock.set(startsAt.minusSeconds(60))
        val h = holdSeat.hold(HoldSeatCommand(z, seat(z), 2L))
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 2", z)
        clock.set(startsAt.plusSeconds(60)) // 홀드는 아직 유효(TTL 5분), 회차는 앱 시계로 마감
        confirm.confirm(ConfirmReservationCommand(h.holdId, 2L, "p"))
        assertEquals(null, cntOf(z, 2L), "마감 회차 — 트리거가 건너뛰어 행을 만들지도 요구하지도 않는다")
    }

    @Test
    fun `L2-4 만료 — 앱 시계를 마감 뒤로 옮기면 트리거도 마감으로 본다(행 없어도 틱 성공 — 벽시계 기준이면 SR001)`() {
        val (z, startsAt) = farSchedule()
        clock.set(startsAt.minusSeconds(60))
        holdSeat.hold(HoldSeatCommand(z, seat(z), 3L))
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = 3", z)
        clock.set(startsAt.plusSeconds(600))
        assertEquals(1, expire.expire())
        assertEquals(emptyList(), driftLog(), "마감 회차는 선잠금 시도 목록에도 오르지 않는다")
    }

    @Test
    fun `L2-4 폴백 — 기준 시각 설정이 없는 직접 DB 쓰기는 DB now()로 판정한다`() {
        val x = wallClosedAppOpen()
        rawHold(x, 4L) // 벽시계로 마감 → 건너뜀(행 없어도 오류 아님)
        assertEquals(null, cntOf(x, 4L))
        val s = schedule.id!!
        assertEquals("SR001", sqlStateOf(assertThrows<Exception> { rawHold(s, 5L) }), "벽시계로 열린 회차 + 행 없음 → 엄격")
    }

    @Test
    fun `L2-5 — 열린 회차 cnt가 지울 홀드 수보다 작으면 틱 실패(23514), skipped below 1행, C 복구 뒤 다음 틱 정상`() {
        val s = schedule.id!!
        holdSeat.hold(HoldSeatCommand(s, seat(s), 6L))
        holdSeat.hold(HoldSeatCommand(s, seat(s), 6L))
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 1 WHERE schedule_id = ? AND user_id = 6", s)
        clock.advance(Duration.ofMinutes(6))
        val failed0 = counter("seat.hold.expiry.tick", "outcome", "failed")
        assertEquals("23514", sqlStateOf(assertThrows<Exception> { expire.expire() }))
        assertEquals(1.0, counter("seat.hold.expiry.tick", "outcome", "failed") - failed0)
        assertEquals(listOf(listOf<Any?>(6L, "below", "skipped", "23514", "expiry-trigger")), driftLog())
        assertEquals(1, reconciler.runOnce("reconcile-app").repaired)
        assertEquals(2, cntOf(s, 6L))
        assertEquals(2, expire.expire())
        assertEquals(0, cntOf(s, 6L))
    }

    @Test
    fun `L2-0 — counter-trigger + 판매 마감 끔은 기동 거부`() {
        val off = HoldStrategyStartupCheck(properties.copy(salesClose = SeatHoldProperties.SalesClose(enabled = false)), jdbcTemplate, redis)
        val e = assertThrows<IllegalStateException> { off.run(DefaultApplicationArguments()) }
        assertTrue(e.message.orEmpty().contains("판매 마감"), e.message)
        assertEquals(UserLimitStrategyType.COUNTER_TRIGGER, properties.limitStrategy)
    }
}

/** 이음새 동작을 테스트마다 바꿀 수 있게 하는 프로브. */
class SwitchableProbe : CounterRepairProbe {
    @Volatile var action: ((Long, Long) -> Unit)? = null
    override fun afterDetect(scheduleId: Long, userId: Long, observedCnt: Int?, observedActual: Int) {
        action?.invoke(scheduleId, userId)
    }
}

/**
 * L2-2 — 탐지와 복구 사이에 회차가 마감되고 정리 ①이 회차 행을 쥔 채 쿼터 행을 지우면, 복구는 회차 행 FOR SHARE에서 기다렸다가
 * 마감을 읽고 skipped(closed)로 끝난다(행을 되살리지 않는다). L2-12 — 이음새가 실패해도 그 쌍만 skipped, 바퀴는 계속.
 */
@Import(ReconcileCloseRaceImplTest.ProbeConfig::class)
@TestPropertySource(properties = ["seat.hold.counter-drift.reconcile.lock-timeout=10s"])
class ReconcileCloseRaceImplTest : DriftTestSupport() {
    @Autowired lateinit var reconciler: CounterReconciler
    @Autowired lateinit var probe: SwitchableProbe
    @Autowired lateinit var tx: TransactionTemplate

    @TestConfiguration(proxyBeanMethods = false)
    class ProbeConfig {
        @Bean fun switchableProbe() = SwitchableProbe()
    }

    @AfterEach
    fun resetProbe() { probe.action = null }

    private var seatNo = 8_000
    private fun confirmed(scheduleId: Long, userId: Long) {
        val seatId = jdbcTemplate.queryForObject(
            "INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'A', 1, ?, 'RESERVED') RETURNING id",
            Long::class.java, scheduleId, ++seatNo,
        )
        jdbcTemplate.update(
            "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, 'p', 'CONFIRMED', now())",
            scheduleId, seatId, userId,
        )
    }

    @Test
    fun `정리 ①이 회차 행을 쥔 동안 복구는 FOR SHARE에서 기다렸다가 정리 커밋 뒤의 마감을 읽고 skipped(closed) — 쿼터 행이 되살아나지 않는다`() {
        // 코드 루프 3 L3-2로 다시 씀: 이음새는 시계를 옮기지 않는다. 정리 스레드가 회차 행 FOR UPDATE → 홀드 0 확인 → 쿼터 행 DELETE를 한 뒤
        // **커밋 직전에** 시계를 마감 뒤로 옮긴다 — 복구가 회차 행 FOR SHARE로 정리 커밋을 기다리지 않으면 아직 '열림'으로 읽고 쿼터 행 잠금에서
        // 기다렸다가(지워진 행) 행을 다시 만든다(되살아남) → 이 테스트가 실패한다(FOR SHARE를 지운 코드로 빨강 확인함).
        val startsAt = NOW.plusSeconds(600)
        val x = newSchedule(startsAt).id!!
        confirmed(x, 1L)
        quota(x, 1L, 2) // 실제 1 — 큼
        val pool = Executors.newSingleThreadExecutor()
        val holding = CountDownLatch(1)
        var cleanup: java.util.concurrent.Future<*>? = null
        probe.action = { s, _ ->
            cleanup = pool.submit {
                tx.executeWithoutResult {
                    // 정리 ①과 같은 문장: 회차 행 FOR UPDATE → 홀드 0 확인 → 회차 쿼터 행 전부 삭제
                    jdbcTemplate.queryForList("SELECT starts_at FROM product_schedule WHERE id = ? FOR UPDATE", s)
                    check(jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM seat_hold WHERE schedule_id = ?)", Boolean::class.java, s) == false)
                    jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ?", s)
                    holding.countDown()
                    Thread.sleep(700) // 복구가 이 사이에 회차 행(또는 쿼터 행)에서 기다리게
                    clock.set(startsAt.plusSeconds(1)) // 커밋 직전에 마감
                }
            }
            holding.await(5, TimeUnit.SECONDS)
        }
        val run = try {
            reconciler.runOnce("reconcile-app")
        } finally {
            cleanup?.get(10, TimeUnit.SECONDS)
            pool.shutdown()
        }
        assertEquals(1, run.skippedClosed, "$run")
        assertEquals(0, run.skipped, "마감 제외는 실패 skipped로 세지 않는다(L3-4)")
        assertEquals(0, run.repaired)
        assertEquals(null, cntOf(x, 1L), "마감 회차 쿼터 행이 되살아났다")
        assertEquals(listOf(listOf<Any?>(1L, "above", "skipped", "closed", "reconcile-app")), driftLog())
        assertEquals(
            listOf(0, 1),
            jdbcTemplate.queryForList("SELECT skipped, skipped_closed FROM counter_reconcile_run WHERE id = ?", run.id).single().values.map { (it as Number).toInt() },
            "바퀴 기록도 실패 skipped와 마감 제외를 따로 센다",
        )
    }

    @Test
    fun `L2-12 — 이음새가 실패해도 그 쌍만 skipped, 같은 바퀴의 다른 쌍은 복구`() {
        val s = schedule.id!!
        confirmed(s, 2L); quota(s, 2L, 3)
        confirmed(s, 3L); quota(s, 3L, 3)
        probe.action = { _, u -> if (u == 2L) error("probe failure") }
        val run = reconciler.runOnce("reconcile-app")
        assertEquals(1, run.skipped, "$run")
        assertEquals(1, run.repaired)
        assertEquals(3, cntOf(s, 2L))
        assertEquals(1, cntOf(s, 3L))
    }
}

/** L2-8 — 정리 ② 청크 실패도 errors 지표(mode=zero)·부분 기록을 남긴다. */
class ZeroCleanupErrorsImplTest : DriftTestSupport() {
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var tx: TransactionTemplate

    @Test
    fun `청크 실패 — 실행은 끝나고 errors·지표·기록 1행(그때까지 지운 수)`() {
        val zero = QuotaCleanup(
            jdbcTemplate, tx,
            properties.copy(counterDrift = properties.counterDrift.copy(cleanup = properties.counterDrift.cleanup.copy(mode = SeatHoldProperties.CleanupMode.ZERO))),
            meters, clock,
        )
        quota(schedule.id!!, 1L, 0)
        val e0 = counter(QuotaCleanup.ERRORS_METRIC, "mode", "zero")
        jdbcTemplate.execute("ALTER TABLE reservation RENAME TO reservation_x")
        val run = try { zero.runOnce() } finally { jdbcTemplate.execute("ALTER TABLE reservation_x RENAME TO reservation") }
        assertEquals(1, run.errors, "$run")
        assertEquals(1.0, counter(QuotaCleanup.ERRORS_METRIC, "mode", "zero") - e0)
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM counter_cleanup_run WHERE mode = 'zero' AND rows_deleted = 0", Int::class.java))
        assertEquals(0, cntOf(schedule.id!!, 1L), "실패한 청크는 지우지 않았다")
    }
}
