package com.jun.labs.seatreservation.drift

import com.jun.labs.seatreservation.domain.ProductSchedule
import com.jun.labs.seatreservation.support.IntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Instant

/** ADR-009 구현 확인용 공통 도우미(구현자 최소 테스트 — 계약 테스트는 blind 워커 몫). 기록 테이블은 매 테스트 비운다. */
abstract class DriftTestSupport : IntegrationTest() {

    @Autowired lateinit var meters: MeterRegistry

    @BeforeEach
    fun clearDriftTables() {
        jdbcTemplate.update("DELETE FROM counter_drift_log")
        jdbcTemplate.update("DELETE FROM counter_reconcile_run")
        jdbcTemplate.update("DELETE FROM counter_cleanup_run")
    }

    fun newSchedule(startsAt: Instant): ProductSchedule =
        productScheduleRepository.save(ProductSchedule(productId = schedule.productId, startsAt = startsAt))

    private var seatNo = 1_000

    /** 좌석 + 홀드 1행(직접 삽입). 반환 = 홀드 id */
    fun rawHold(scheduleId: Long, userId: Long, expiresAt: Instant = NOW.plusSeconds(300)): Long {
        val seatId = jdbcTemplate.queryForObject(
            "INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'A', 1, ?, 'HELD') RETURNING id",
            Long::class.java, scheduleId, ++seatNo,
        )!!
        return jdbcTemplate.queryForObject(
            "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long::class.java, seatId, scheduleId, userId, Timestamp.from(expiresAt.minusSeconds(300)), Timestamp.from(expiresAt),
        )!!
    }

    fun quota(scheduleId: Long, userId: Long, cnt: Int) {
        jdbcTemplate.update(
            "INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, ?) ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = EXCLUDED.cnt",
            scheduleId, userId, cnt,
        )
    }

    fun cntOf(scheduleId: Long, userId: Long): Int? = jdbcTemplate.queryForList(
        "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, scheduleId, userId,
    ).singleOrNull()

    fun actualOf(scheduleId: Long, userId: Long): Int = jdbcTemplate.queryForObject(
        """
        SELECT ((SELECT count(*) FROM seat_hold WHERE schedule_id = ? AND user_id = ?)
              + (SELECT count(*) FROM reservation WHERE schedule_id = ? AND user_id = ? AND status = 'CONFIRMED'))::int
        """.trimIndent(),
        Int::class.java, scheduleId, userId, scheduleId, userId,
    )!!

    /** (user_id, kind, action, reason) */
    fun driftLog(): List<List<Any?>> = jdbcTemplate.query(
        "SELECT user_id, kind, action, reason, source FROM counter_drift_log ORDER BY user_id, id",
    ) { rs, _ -> listOf(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }

    fun counter(name: String, vararg tags: String): Double = meters.find(name).tags(*tags).counter()?.count() ?: 0.0

    /** Hikari 커넥션 획득 수(풀 하나) */
    fun hikariAcquires(): Long = meters.find("hikaricp.connections.acquire").timer()?.count()
        ?: error("hikaricp.connections.acquire 지표가 없다")
}
