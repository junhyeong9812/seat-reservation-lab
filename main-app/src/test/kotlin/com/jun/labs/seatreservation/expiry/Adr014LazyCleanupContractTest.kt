package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.incremental.exec
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.QuotaCleanup
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterEach
import com.jun.labs.seatreservation.support.clearAdr009Tables
import com.jun.labs.seatreservation.support.insertSeatRow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * ADR-014 B 정리 ①(마감 회차 — §3.4 B1~B3 · Q7 · 선검증 DS14-3) 계약(blind).
 * B에는 주기 배치가 없어 마감 회차의 남은 만료 홀드 때문에 쿼터 행이 영영 안 지워진다 → B의 정리 ①은 회차 FOR UPDATE 뒤
 *   B1 활성 홀드가 있으면 건너뛴다 · B2 만료 홀드를 FOR UPDATE SKIP LOCKED로 고른 행만 지운다(감소 없음 — 마감 회차) ·
 *   B3 남은 홀드가 있으면(건너뛴 행) 쿼터 행 삭제를 다음 실행으로 미룬다 · 없으면 그 회차 쿼터 행을 같은 트랜잭션에서 지운다.
 * 진입점은 이미 있는 QuotaCleanup.runOnce()(ADR-009 — cleanup.mode=schedule). 마감 판정은 앱 Clock 그대로(§2.1 표 '바꾸지 않는다').
 */
@TestPropertySource(properties = ["$EXPIRY_MODE=lazy", "seat.hold.counter-drift.cleanup.mode=schedule", "seat.confirm.mode=current"])
class Adr014LazyCleanupContractTest : IntegrationTest() {

    @Autowired lateinit var cleanup: QuotaCleanup
    @Autowired lateinit var dataSource: DataSource

    private lateinit var pool: ExecutorService

    @BeforeEach
    fun startPool() {
        pool = Executors.newCachedThreadPool()
    }

    @AfterEach
    fun stopPool() {
        pool.shutdownNow()
    }

    private fun raw(): Connection {
        val h = dataSource.unwrap(HikariDataSource::class.java)
        return DriverManager.getConnection(h.jdbcUrl, h.username, h.password).apply { autoCommit = false }
    }

    private var closed: Long = 0
    private var seatNo = 50_000

    @BeforeEach
    fun closedSchedule() {
        jdbcTemplate.clearAdr009Tables()
        // 앱 시계(NOW)로 이미 시작한 회차 = 마감(DB 시계로도 지났다)
        closed = jdbcTemplate.queryForObject(
            "INSERT INTO product_schedule (product_id, starts_at) VALUES (?, ?) RETURNING id",
            Long::class.java, schedule.productId, Timestamp.from(CLOCK_PAST.minusSeconds(3600)),
        )!!
    }

    private fun closedHold(userId: Long, expiresIn: String): Long {
        val seat = jdbcTemplate.insertSeatRow(closed, ++seatNo, SeatStatus.HELD)
        return jdbcTemplate.dbHold(seat, closed, userId, expiresIn)
    }

    private fun holdsOf(scheduleId: Long): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE schedule_id = ?", Int::class.java, scheduleId)!!

    private fun quotaRowsOf(scheduleId: Long): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM user_hold_quota WHERE schedule_id = ?", Int::class.java, scheduleId)!!

    @Test
    fun `B 정리 ① — 마감 회차에 만료 홀드만 남았으면 홀드 행과 쿼터 행을 지운다 · 열린 회차는 건드리지 않는다`() {
        closedHold(100, "-1 minute")
        closedHold(100, "-2 minutes")
        closedHold(200, "-1 minute")
        val openSeat = jdbcTemplate.insertSeatRow(schedule.id!!, 1, SeatStatus.HELD)
        val openExpired = jdbcTemplate.dbHold(openSeat, schedule.id!!, 300, "-1 minute")
        val run = cleanup.runOnce()
        assertEquals(0, run.errors, "정리 실패 없음: $run")
        assertEquals(0, holdsOf(closed), "마감 회차의 만료 홀드 행 삭제(B2)")
        assertEquals(0, quotaRowsOf(closed), "홀드가 남지 않았으니 쿼터 행 삭제(B3)")
        assertTrue(jdbcTemplate.holdRowExists(openExpired), "열린 회차의 만료 홀드는 정리 ① 대상이 아니다")
        assertEquals(1, jdbcTemplate.quotaCnt(schedule.id!!, 300))
    }

    @Test
    fun `B 정리 ① — 마감 회차에 활성 홀드가 있으면 건너뛴다(B1 — 아무것도 지우지 않는다)`() {
        closedHold(100, "-1 minute")
        closedHold(200, "5 minutes")
        cleanup.runOnce()
        assertEquals(2, holdsOf(closed))
        assertEquals(2, quotaRowsOf(closed))
    }

    @Test
    fun `B 정리 ① DS14-3 — 다른 트랜잭션(확정 K3)이 쥔 만료 홀드 행은 기다리지 않고 건너뛰고 쿼터 행 삭제를 다음 실행으로 미룬다`() {
        val held = closedHold(100, "-1 minute")
        val other = closedHold(200, "-1 minute")
        raw().use { k ->
            k.exec("SELECT id FROM seat_hold WHERE id = ? FOR UPDATE", held)
            val run = pool.submit<Any> { cleanup.runOnce() }.get(10, TimeUnit.SECONDS)
            assertTrue(jdbcTemplate.holdRowExists(held), "쥔 행은 SKIP LOCKED로 건너뛴다")
            assertFalse(jdbcTemplate.holdRowExists(other), "쥐지 않은 만료 행은 지운다: $run")
            assertEquals(2, quotaRowsOf(closed), "건너뛴 행이 남았으니 쿼터 행 삭제는 미룬다(B3)")
            k.rollback()
        }
        cleanup.runOnce()
        assertEquals(0, holdsOf(closed), "다음 실행이 남은 행을 지운다")
        assertEquals(0, quotaRowsOf(closed), "그리고 쿼터 행도")
    }
}
