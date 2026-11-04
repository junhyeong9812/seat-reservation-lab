package com.jun.labs.seatreservation.adr015fix

import com.jun.labs.seatreservation.adr015.LOC_BASE_ONLY
import com.jun.labs.seatreservation.adr015.LOC_CONFIRM_VERSION
import com.jun.labs.seatreservation.adr015.MODE_KEY
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.impl.ConfirmReservationService
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * ADR-015 구현 리뷰 R15-1 · R15-3 · R15-9(fix verification).
 * - R15-1: `seat.confirm.loadtest.delay`(loadtest 프로필)가 모드별 자리(§설계-8.2 — '확인 뒤 · 첫 쓰기 앞')에서 실제로 잔다: 응답 시간 ≥ delay ·
 *   자는 동안 DB = 쓰기 0(홀드 행 있음 · 좌석 HELD · 예약 0) · 잠금은 ②만(P1 · P2 뒤 — 홀드 · 좌석 행 NOWAIT 실패) · 그 밖 모드는 잠금 없음.
 *   대조: delay 0이면 응답이 그 시간보다 짧다.
 * - R15-3 · R15-9: 홀드가 지연 중에 만료되게(expires_at = DB now() + 300ms · delay 1s) → `:now` 판정은 통과 · 전환은 만료 뒤 →
 *   성공이면 converted_after_expiry{clock} +1 · 지연량 Timer max > 0(db는 새 모드만). 바깥 트랜잭션이 롤백하면 둘 다 그대로(커밋 뒤에만 센다).
 * 기동 = 실제 SpringApplication(BootTestBase · 실제 Clock) · loadtest 프로필.
 */
class Adr015DelayAndLatenessTest : BootTestBase() {

    private val delay = Duration.ofMillis(1000)
    private val user = 100L

    private fun props(mode: String, delay: Duration): Map<String, String?> = mapOf(
        MODE_KEY to mode,
        "spring.profiles.active" to "loadtest",
        "seat.confirm.loadtest.delay" to "${delay.toMillis()}ms",
    ) + (if (mode == "optimistic") mapOf("spring.flyway.locations" to "$LOC_BASE_ONLY,$LOC_CONFIRM_VERSION") else emptyMap())

    /** 좌석 HELD + 그 좌석의 홀드(DB now() + [ttlMs]) — (holdId, seatId) */
    private fun JdbcTemplate.heldSeat(ttlMs: Long): Pair<Long, Long> {
        val s = seedSchedule()
        val seat = seat(s, "HELD")
        val hold = queryForObject(
            "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, now(), now() + make_interval(secs => ? / 1000.0)) RETURNING id",
            Long::class.java, seat, s, user, ttlMs,
        )!!
        return hold to seat
    }

    /** 다른 연결에서 그 행을 NOWAIT로 잠가 본다(자동 커밋 — 바로 풀린다). 잠겨 있으면 false */
    private fun JdbcTemplate.lockable(table: String, id: Long): Boolean = try {
        queryForList("SELECT 1 FROM $table WHERE id = ? FOR UPDATE NOWAIT", id); true
    } catch (e: Exception) {
        if (generateSequence<Throwable>(e) { it.cause }.any { (it as? SQLException)?.sqlState == "55P03" }) false else throw e
    }

    private fun MeterRegistry.count(name: String, clockTag: String): Double =
        find(name).tag("clock", clockTag).counters().sumOf { it.count() }

    private fun MeterRegistry.lateMaxMs(clockTag: String): Double =
        find(ConfirmReservationService.CONVERTED_AFTER_EXPIRY_LATENESS_METRIC).tag("clock", clockTag).timer()?.max(TimeUnit.MILLISECONDS) ?: 0.0

    private fun confirm(ctx: ConfigurableApplicationContext, hold: Long) =
        ctx.getBean(ConfirmReservationUseCase::class.java).confirm(ConfirmReservationCommand(hold, user, "pay-$hold"))

    @Test
    fun `R15-1 — 지연 주입이 모드별 자리에서 잔다(쓰기 앞 · ②만 잠금을 쥔 채) · delay 0이면 자지 않는다`() {
        val failures = mutableListOf<String>()
        listOf("current", "conditional", "pessimistic", "optimistic", "conditional-extend").forEach { mode ->
            val url = freshDbUrl()
            val extra = if (mode == "conditional-extend") mapOf("seat.confirm.payment-window" to "2m", "seat.confirm.extend-min-remaining" to "5s") else emptyMap()
            boot(url, props(mode, delay) + extra).use { ctx ->
                val db = jdbcFor(url)
                val (hold, seat) = db.heldSeat(ttlMs = 300_000)
                val t0 = System.nanoTime()
                val f = CompletableFuture.supplyAsync { confirm(ctx, hold) }
                Thread.sleep(400)   // 자는 중(delay 1s)
                val mid = mapOf(
                    "hold" to db.queryForObject("SELECT count(*) FROM seat_hold WHERE id = ?", Int::class.java, hold),
                    "seat" to db.queryForObject("SELECT status FROM product_seat WHERE id = ?", String::class.java, seat),
                    "reservations" to db.queryForObject("SELECT count(*) FROM reservation WHERE seat_id = ?", Int::class.java, seat),
                    "holdLockable" to db.lockable("seat_hold", hold),
                    "seatLockable" to db.lockable("product_seat", seat),
                )
                val locked = mode == "pessimistic"
                val want = mapOf("hold" to 1, "seat" to "HELD", "reservations" to 0, "holdLockable" to !locked, "seatLockable" to !locked)
                if (mid != want) failures += "$mode: 자는 동안 상태 $mid ≠ 기대 $want"
                f.get(30, TimeUnit.SECONDS)
                val ms = Duration.ofNanos(System.nanoTime() - t0).toMillis()
                if (ms < delay.toMillis()) failures += "$mode: 응답 ${ms}ms < delay ${delay.toMillis()}ms(주입이 일어나지 않았다)"
                if (db.queryForObject("SELECT count(*) FROM reservation WHERE seat_id = ? AND status = 'CONFIRMED'", Int::class.java, seat) != 1) failures += "$mode: 확정 예약 1건이 아니다"
            }
        }
        // 대조: delay 0(loadtest 프로필 · 키 0) — 자지 않는다
        val url = freshDbUrl()
        boot(url, props("conditional", Duration.ZERO)).use { ctx ->
            val (hold, _) = jdbcFor(url).heldSeat(ttlMs = 300_000)
            val t0 = System.nanoTime()
            confirm(ctx, hold)
            val ms = Duration.ofNanos(System.nanoTime() - t0).toMillis()
            if (ms >= delay.toMillis()) failures += "delay 0: 응답 ${ms}ms ≥ ${delay.toMillis()}ms"
        }
        if (failures.isNotEmpty()) fail("${failures.size}건:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `R15-3 · R15-9 — converted_after_expiry는 커밋 뒤에만 센다(롤백이면 그대로) · 늦은 성공은 +1 · 지연량 max 0 초과`() {
        val failures = mutableListOf<String>()
        listOf("conditional", "pessimistic", "optimistic", "current").forEach { mode ->
            val url = freshDbUrl()
            boot(url, props(mode, delay)).use { ctx ->
                val db = jdbcFor(url)
                val meters = ctx.getBean(MeterRegistry::class.java)
                val tx = TransactionTemplate(ctx.getBean(PlatformTransactionManager::class.java))
                val clocks = if (mode == "current") listOf("app") else listOf("db", "app")
                fun counts() = clocks.associateWith { meters.count(ConfirmReservationService.CONVERTED_AFTER_EXPIRY_METRIC, it) }

                // ① 늦은 전환(지연 중 만료) + 바깥 트랜잭션 롤백 → 세지 않는다
                val (h1, s1) = db.heldSeat(ttlMs = 300)
                val c0 = counts()
                tx.executeWithoutResult { st -> confirm(ctx, h1); st.setRollbackOnly() }
                if (counts() != c0) failures += "$mode 롤백: 카운터가 올랐다 $c0 → ${counts()}"
                if (db.queryForObject("SELECT count(*) FROM reservation WHERE seat_id = ?", Int::class.java, s1) != 0) failures += "$mode 롤백: 예약이 남았다"

                // ② 늦은 전환 성공 → 시계마다 +1 · 지연량 max > 0
                val (h2, _) = db.heldSeat(ttlMs = 300)
                confirm(ctx, h2)
                val c1 = counts()
                clocks.forEach { c ->
                    if (c1.getValue(c) != c0.getValue(c) + 1) failures += "$mode 성공 clock=$c: ${c0.getValue(c)} → ${c1.getValue(c)}(+1 기대)"
                    val max = meters.lateMaxMs(c)
                    if (max <= 0.0) failures += "$mode 성공 clock=$c: 지연량 max $max ms(> 0 기대)"
                }
                if (mode == "current" && meters.find(ConfirmReservationService.CONVERTED_AFTER_EXPIRY_METRIC).tag("clock", "db").counters().isNotEmpty()) {
                    failures += "current: clock=db 지표가 생겼다(current는 SQL 무변경 — app만)"
                }

                // ③ 만료 전 전환(넉넉한 TTL) → 세지 않는다
                val (h3, _) = db.heldSeat(ttlMs = 300_000)
                confirm(ctx, h3)
                if (counts() != c1) failures += "$mode 만료 전 성공: 카운터가 올랐다 $c1 → ${counts()}"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size}건:\n" + failures.joinToString("\n"))
    }
}
