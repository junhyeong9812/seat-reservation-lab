package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.SeatReservationApplication
import com.jun.labs.seatreservation.api.ReservationController
import com.jun.labs.seatreservation.api.SeatHoldController
import com.jun.labs.seatreservation.loadtest.LoadtestController
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.impl.drift.CounterDriftScheduling
import com.jun.labs.seatreservation.service.impl.drift.CounterReconciler
import com.jun.labs.seatreservation.support.awaitIdleInTransaction
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * L1-6(명세 §8 가정 1) — A: 커밋 전 선점(쿼터 행을 새로 INSERT 중)이 진행 중일 때 만료 복구가 그 커밋을 기다렸다가 다시 세어 cnt = 실제.
 * 임계 구역 지연 1s(ADR-004 장치)로 선점 트랜잭션을 쿼터 행을 쥔 채 멈춰 둔다.
 */
@TestPropertySource(properties = ["seat.hold.counter-drift.expiry-repair=true", "seat.hold.critical-section-delay=1s"])
class ExpiryRepairConcurrencyImplTest : DriftTestSupport() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirm: ConfirmReservationUseCase
    @Autowired lateinit var expire: ExpireHoldsUseCase

    @Test
    fun `행 없음 쌍 복구가 진행 중 선점(행 INSERT 커밋 전)과 겹쳐도 끝나면 cnt = 실제`() {
        val s = schedule.id!!
        val u = 31L
        val a = holdSeat.hold(HoldSeatCommand(s, createSeat(1).id!!, u))
        confirm.confirm(ConfirmReservationCommand(a.holdId, u, "p"))           // 확정 1
        holdSeat.hold(HoldSeatCommand(s, createSeat(2).id!!, u))              // 만료될 홀드 1
        jdbcTemplate.update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", s, u)
        clock.advance(Duration.ofMinutes(6))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val inFlight = pool.submit<Long> { holdSeat.hold(HoldSeatCommand(s, createSeat(3).id!!, u)).holdId } // upsert INSERT(cnt 1) 뒤 1s 멈춤
            jdbcTemplate.awaitIdleInTransaction()
            assertEquals(1, expire.expire())
            inFlight.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdown()
        }
        assertEquals(2, actualOf(s, u), "확정 1 + 진행 중이던 홀드 1")
        assertEquals(actualOf(s, u), cntOf(s, u))
        assertEquals("repaired", driftLog().single()[2])
    }
}

/** L1-6 — C: 커밋 전 선점(cnt + 1 중)과 겹친 복구가 잠금을 기다린 뒤 다시 세어 cnt = 실제. */
@TestPropertySource(properties = ["seat.hold.critical-section-delay=1s", "seat.hold.counter-drift.reconcile.lock-timeout=10s"])
class ReconcileConcurrencyImplTest : DriftTestSupport() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var reconciler: CounterReconciler

    @Test
    fun `cnt 작음 쌍 복구가 진행 중 선점과 겹쳐도 끝나면 cnt = 실제`() {
        val s = schedule.id!!
        val u = 41L
        holdSeat.hold(HoldSeatCommand(s, createSeat(1).id!!, u))
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 0 WHERE schedule_id = ? AND user_id = ?", s, u)
        val pool = Executors.newSingleThreadExecutor()
        val run = try {
            val inFlight = pool.submit<Long> { holdSeat.hold(HoldSeatCommand(s, createSeat(2).id!!, u)).holdId }
            jdbcTemplate.awaitIdleInTransaction()
            reconciler.runOnce("reconcile-app").also { inFlight.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
        assertEquals(1, run.repaired, "$run")
        assertEquals(2, actualOf(s, u))
        assertEquals(2, cntOf(s, u))
    }
}

/** L1-4 · L1-7 — 대조 실패를 숨기지 않는다: 회차 조회·청크·범위 조회·skipped 기록 실패가 run.errors·지표로 남는다. */
@TestPropertySource(properties = ["seat.hold.counter-drift.reconcile.lock-timeout=200ms"])
class ReconcileErrorsImplTest : DriftTestSupport() {
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var reconciler: CounterReconciler
    @Autowired lateinit var tx: org.springframework.transaction.support.TransactionTemplate

    private fun errors(stage: String) = counter(CounterReconciler.ERRORS_METRIC, "stage", stage)

    @Test
    fun `회차 조회 실패 — 바퀴 기록 errors 1, 지표 stage=schedules`() {
        val e0 = errors("schedules")
        jdbcTemplate.execute("ALTER TABLE product_schedule RENAME TO product_schedule_x")
        val run = try { reconciler.runOnce("reconcile-app") } finally { jdbcTemplate.execute("ALTER TABLE product_schedule_x RENAME TO product_schedule") }
        assertEquals(1, run.errors, "$run")
        assertEquals(1.0, errors("schedules") - e0)
        assertEquals(1, jdbcTemplate.queryForObject("SELECT errors FROM counter_reconcile_run WHERE id = ?", Int::class.java, run.id))
    }

    @Test
    fun `청크·범위 조회 실패 — errors 2, skipped_chunks 2(쿼터 청크 + 행 없음 범위)`() {
        val s = schedule.id!!
        holdSeat.hold(HoldSeatCommand(s, createSeat(1).id!!, 51L))
        val c0 = errors("chunk"); val b0 = errors("bounds")
        jdbcTemplate.execute("ALTER TABLE seat_hold RENAME TO seat_hold_x")
        val run = try { reconciler.runOnce("reconcile-app") } finally { jdbcTemplate.execute("ALTER TABLE seat_hold_x RENAME TO seat_hold") }
        assertEquals(2, run.errors, "$run")
        assertEquals(2, run.skippedChunks)
        assertEquals(1.0, errors("chunk") - c0)
        assertEquals(1.0, errors("bounds") - b0)
    }

    @Test
    fun `skipped 기록마저 실패하면 바퀴 결과가 실패(errors·지표 stage=record), 성공 위장 없음`() {
        val s = schedule.id!!
        holdSeat.hold(HoldSeatCommand(s, createSeat(1).id!!, 61L))
        jdbcTemplate.update("UPDATE user_hold_quota SET cnt = 2 WHERE schedule_id = ? AND user_id = 61", s)
        jdbcTemplate.execute("ALTER TABLE counter_drift_log ADD CONSTRAINT test_no_skipped CHECK (action <> 'skipped') NOT VALID")
        val r0 = errors("record")
        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val run = try {
            val holder = pool.submit {
                tx.executeWithoutResult {
                    jdbcTemplate.queryForList("SELECT 1 FROM user_hold_quota WHERE schedule_id = ? AND user_id = 61 FOR UPDATE", s)
                    locked.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            locked.await(5, TimeUnit.SECONDS)
            reconciler.runOnce("reconcile-app").also { release.countDown(); holder.get(10, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            pool.shutdown()
            jdbcTemplate.execute("ALTER TABLE counter_drift_log DROP CONSTRAINT test_no_skipped")
        }
        assertEquals(1, run.skipped, "$run")
        assertEquals(1, run.errors)
        assertEquals(1.0, errors("record") - r0)
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM counter_drift_log", Int::class.java))
    }
}

/**
 * L1-5 · L1-10 · L1-13 · L1-18 — 실제 SpringApplication 기동: F 프로필은 업무 컨트롤러가 없고 출처가 reconcile-proc ·
 * F인데 대조 끔이면 실패 · C 또는 external-reconciler인데 A 없음이면 실패(counter-trigger 예외) · F 프로세스는 A 검사를 하지 않는다.
 * ADR-009 기본값 전환(명세 §12 — A·C·정리 ① 켬) 뒤: 업무 앱 조합은 '그 밖은 끔'(전환 전 기본값)을 명시한다. F 프로필은 프로필 yml이 A·정리를 끈다(명시 없음).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Loop1BootImplTest {
    private val postgres = PostgreSQLContainer("postgres:16")
    private val seq = AtomicInteger()

    @BeforeAll fun startDb() = postgres.start()
    @AfterAll fun stopDb() = postgres.stop()

    private fun boot(vararg extra: String): ConfigurableApplicationContext {
        val name = "loop1_${seq.incrementAndGet()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        return SpringApplicationBuilder(SeatReservationApplication::class.java).run(
            "--spring.datasource.url=jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$name",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--server.port=0",
            "--seat.hold.counter-drift.reconcile.interval=1h",
            *extra,
        )
    }

    private fun assertBootFails(keyword: String, vararg extra: String) {
        val error = try {
            boot("--seat.hold.expiry-scheduler-enabled=false", *extra).close()
            fail("떴다: ${extra.toList()}")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e
        }
        assertTrue(
            causesOf(error).any { it is IllegalStateException && it.message.orEmpty().contains(keyword) },
            "'$keyword' 기동 검사가 아니다: ${causesOf(error).map { "${it::class.simpleName}: ${it.message?.take(200)}" }}",
        )
    }

    @Test
    fun `F 프로필(+loadtest) — 업무 컨트롤러·부하 시드 컨트롤러가 없고, 대조 출처는 reconcile-proc, A 없이도 뜬다`() {
        boot("--spring.profiles.active=reconciler,loadtest").use { ctx ->
            assertTrue(ctx.getBeansOfType(SeatHoldController::class.java).isEmpty())
            assertTrue(ctx.getBeansOfType(ReservationController::class.java).isEmpty())
            assertTrue(ctx.getBeansOfType(LoadtestController::class.java).isEmpty())
            assertEquals("reconcile-proc", ctx.getBean(CounterDriftScheduling::class.java).reconcileSource())
        }
    }

    @Test
    fun `업무 앱(C + A) — 컨트롤러가 있고 출처는 reconcile-app`() {
        boot("--seat.hold.expiry-scheduler-enabled=false", "--seat.hold.counter-drift.reconcile.enabled=true", "--seat.hold.counter-drift.expiry-repair=true", "--seat.hold.counter-drift.cleanup.mode=none").use { ctx ->
            assertEquals(1, ctx.getBeansOfType(SeatHoldController::class.java).size)
            assertEquals("reconcile-app", ctx.getBean(CounterDriftScheduling::class.java).reconcileSource())
        }
    }

    @Test
    fun `F 프로필인데 대조 끔 — 기동 실패`() =
        assertBootFails("대조가 꺼져 있다", "--spring.profiles.active=reconciler", "--seat.hold.counter-drift.reconcile.enabled=false")

    @Test
    fun `C인데 A 없음 — 기동 실패`() =
        assertBootFails("후보 A(seat.hold.counter-drift.expiry-repair=true)가 필수", "--seat.hold.counter-drift.reconcile.enabled=true", "--seat.hold.counter-drift.expiry-repair=false", "--seat.hold.counter-drift.cleanup.mode=none")

    @Test
    fun `external-reconciler인데 A 없음 — 기동 실패, A를 켜면 뜬다`() {
        assertBootFails("후보 A(seat.hold.counter-drift.expiry-repair=true)가 필수", "--seat.hold.counter-drift.external-reconciler=true", "--seat.hold.counter-drift.expiry-repair=false", "--seat.hold.counter-drift.reconcile.enabled=false", "--seat.hold.counter-drift.cleanup.mode=none")
        boot("--seat.hold.expiry-scheduler-enabled=false", "--seat.hold.counter-drift.external-reconciler=true", "--seat.hold.counter-drift.expiry-repair=true", "--seat.hold.counter-drift.reconcile.enabled=false", "--seat.hold.counter-drift.cleanup.mode=none").close()
    }

    @Test
    fun `counter-trigger는 예외 — C·external-reconciler에 A 없이 뜬다`() {
        // C와 external-reconciler는 따로 띄운다 — 업무 앱에서 둘을 함께 켜는 것은 대조 중복으로 거부된다(task 07a 리뷰 F1 기동 검사). 각각 A 없이 뜨는지가 이 테스트의 뜻
        val trigger = arrayOf(
            "--seat.hold.expiry-scheduler-enabled=false", "--seat.hold.limit-strategy=counter-trigger",
            "--spring.flyway.locations=classpath:db/migration,classpath:db/migration-trigger",
            "--seat.hold.counter-drift.expiry-repair=false", "--seat.hold.counter-drift.cleanup.mode=none",
        )
        boot(*trigger, "--seat.hold.counter-drift.reconcile.enabled=true").close()
        boot(*trigger, "--seat.hold.counter-drift.external-reconciler=true", "--seat.hold.counter-drift.reconcile.enabled=false").close()
    }
}
