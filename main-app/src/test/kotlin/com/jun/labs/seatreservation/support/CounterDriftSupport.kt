package com.jun.labs.seatreservation.support

import com.jun.labs.seatreservation.domain.SeatStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Statistic
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/*
 * ADR-009 blind 테스트 공용 도구. 판정은 도메인 코드를 재사용하지 않고 DB 행을 직접 센다(ADR-001 — 측정 대상이 스스로를 채점하지 않게).
 *
 * 계약 표면(contract.md)은 진입점·이음새의 **클래스 이름**만 정하고 패키지는 정하지 않는다. 아래 별칭 세 줄이 패키지를 가정하는 유일한 자리다 —
 * 구현 패키지가 다르면 이 세 줄만 고친다.
 */
typealias CounterReconciler = com.jun.labs.seatreservation.service.impl.drift.CounterReconciler
typealias QuotaCleanup = com.jun.labs.seatreservation.service.impl.drift.QuotaCleanup
typealias CounterRepairProbe = com.jun.labs.seatreservation.service.impl.drift.CounterRepairProbe

/** V7 기록 테이블(contract.md) — IntegrationTest.resetData가 지우지 않으므로 ADR-009 테스트가 앞뒤로 비운다. */
val ADR009_LOG_TABLES = listOf("counter_drift_log", "counter_reconcile_run", "counter_cleanup_run")

fun JdbcTemplate.clearAdr009Tables() {
    ADR009_LOG_TABLES.forEach { t ->
        if (queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, t) == true) update("DELETE FROM $t")
    }
}

/** 트리거(B)가 있는 스키마에서 어긋남을 남긴 채 끝난 테스트가 다음 테스트의 resetData(행 단위 DELETE → 엄격 트리거 오류)를 깨지 않게 — TRUNCATE는 행 트리거를 부르지 않는다. */
fun JdbcTemplate.truncateHoldsReservationsQuota() {
    execute("TRUNCATE reservation, seat_hold, user_hold_quota")
}

fun JdbcTemplate.insertSeatRow(scheduleId: Long, seatNo: Int, status: SeatStatus = SeatStatus.AVAILABLE): Long = queryForObject(
    "INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'A', 1, ?, ?) RETURNING id",
    Long::class.java, scheduleId, seatNo, status.name,
)!!

fun JdbcTemplate.insertHoldRow(seatId: Long, scheduleId: Long, userId: Long, heldAt: Instant, expiresAt: Instant): Long = queryForObject(
    "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?) RETURNING id",
    Long::class.java, seatId, scheduleId, userId, Timestamp.from(heldAt), Timestamp.from(expiresAt),
)!!

fun JdbcTemplate.insertReservationRow(scheduleId: Long, seatId: Long, userId: Long, status: String = "CONFIRMED"): Long = queryForObject(
    "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, ?, ?, now()) RETURNING id",
    Long::class.java, scheduleId, seatId, userId, "pay-$seatId-$userId", status,
)!!

fun JdbcTemplate.cntOf(scheduleId: Long, userId: Long): Int? = queryForList(
    "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, scheduleId, userId,
).singleOrNull()

/** 행의 마지막 쓰기 트랜잭션 — 같으면 그 사이 아무도 이 행을 UPDATE하지 않았다(FOR UPDATE 잠금은 xmin을 바꾸지 않는다). */
fun JdbcTemplate.xminOf(scheduleId: Long, userId: Long): String? = queryForList(
    "SELECT xmin::text FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", String::class.java, scheduleId, userId,
).singleOrNull()

/** 판정기와 같은 카운터 정의: seat_hold 행 전체(만료 전·후 구분 없음) + CONFIRMED 예약. */
fun JdbcTemplate.actualOf(scheduleId: Long, userId: Long): Int = queryForObject(
    """
    SELECT (SELECT count(*) FROM seat_hold WHERE schedule_id = ? AND user_id = ?)
         + (SELECT count(*) FROM reservation WHERE schedule_id = ? AND user_id = ? AND status = 'CONFIRMED')
    """.trimIndent(),
    Int::class.java, scheduleId, userId, scheduleId, userId,
)!!

fun JdbcTemplate.setCnt(scheduleId: Long, userId: Long, cnt: Int) {
    check(update("UPDATE user_hold_quota SET cnt = ? WHERE schedule_id = ? AND user_id = ?", cnt, scheduleId, userId) == 1) {
        "주입 전제: 쿼터 행이 있어야 한다 ($scheduleId, $userId)"
    }
}

fun JdbcTemplate.deleteQuota(scheduleId: Long, userId: Long) {
    check(update("DELETE FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", scheduleId, userId) == 1) {
        "주입 전제: 쿼터 행이 있어야 한다 ($scheduleId, $userId)"
    }
}

fun JdbcTemplate.insertQuota(scheduleId: Long, userId: Long, cnt: Int) {
    update("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, ?)", scheduleId, userId, cnt)
}

data class QuotaRow(val scheduleId: Long, val userId: Long, val cnt: Int, val xmin: String)

fun JdbcTemplate.quotaRows(scheduleId: Long? = null): List<QuotaRow> = query(
    "SELECT schedule_id, user_id, cnt, xmin::text AS x FROM user_hold_quota WHERE (?::bigint IS NULL OR schedule_id = ?) ORDER BY 1, 2",
    { rs, _ -> QuotaRow(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4)) },
    scheduleId, scheduleId,
)

/** 복구는 홀드·예약을 바꾸지 않는다(명세 §2) — 전후 비교용 전체 스냅샷. */
fun JdbcTemplate.holdsAndReservations(): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> =
    queryForList("SELECT * FROM seat_hold ORDER BY id") to queryForList("SELECT * FROM reservation ORDER BY id")

/** 판정기 v_counter_mismatch의 정의를 열린 회차(starts_at > [now])로 좁힌 것(명세 §10, contract.md 판정기 응답). */
fun JdbcTemplate.openCounterMismatch(now: Instant): Long = queryForObject(
    """
    WITH open_s AS (SELECT id FROM product_schedule WHERE starts_at > ?),
    actual AS (
        SELECT schedule_id, user_id, sum(n) AS total FROM (
            SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
            UNION ALL
            SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2
        ) u GROUP BY 1, 2
    )
    SELECT count(*) FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id)
    WHERE schedule_id IN (SELECT id FROM open_s) AND coalesce(q.cnt, 0) <> coalesce(a.total, 0)
    """.trimIndent(),
    Long::class.java, Timestamp.from(now),
)!!

data class DriftLog(
    val source: String,
    val scheduleId: Long,
    val userId: Long,
    val kind: String,
    val cntBefore: Int?,
    val actual: Int,
    val action: String,
    val reason: String?,
)

fun JdbcTemplate.driftLogs(): List<DriftLog> = query(
    "SELECT source, schedule_id, user_id, kind, cnt_before, actual, action, reason FROM counter_drift_log ORDER BY id",
) { rs, _ ->
    DriftLog(
        source = rs.getString("source"),
        scheduleId = rs.getLong("schedule_id"),
        userId = rs.getLong("user_id"),
        kind = rs.getString("kind"),
        cntBefore = (rs.getObject("cnt_before") as Number?)?.toInt(),
        actual = rs.getInt("actual"),
        action = rs.getString("action"),
        reason = rs.getString("reason"),
    )
}

fun JdbcTemplate.rowCount(table: String): Int = queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

/**
 * 이름·태그로 찾은 미터들의 COUNT 합 — Counter·Timer·FunctionCounter 어느 것으로 구현돼도 같은 값(계약은 이름·태그만 정한다).
 * 없으면 0.
 */
fun MeterRegistry.countOf(name: String, vararg tags: Pair<String, String>): Double {
    var search = find(name)
    tags.forEach { (k, v) -> search = search.tag(k, v) }
    return search.meters().sumOf { m -> m.measure().filter { it.statistic == Statistic.COUNT }.sumOf { it.value } }
}

/** HikariCP가 커넥션을 내줄 때마다 1씩 오르는 지표(acquire 타이머)의 합 — 빌림 지표의 외부 기준. */
fun MeterRegistry.hikariBorrows(): Double = countOf("hikaricp.connections.acquire")

/**
 * 다른 세션이 트랜잭션을 연 채 [minIdle] 이상 멈춰 있을 때까지 기다린다 — 임계 구역 지연(ADR-004 장치)의 Thread.sleep 안에 들어갔다는 신호.
 * 문장 사이의 짧은 'idle in transaction'에 속지 않게 머문 시간을 함께 본다.
 */
fun JdbcTemplate.awaitIdleInTransaction(minIdle: Duration = Duration.ofMillis(300), timeout: Duration = Duration.ofSeconds(15)) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
        val n = queryForObject(
            """
            SELECT count(*) FROM pg_stat_activity
            WHERE datname = current_database() AND pid <> pg_backend_pid()
              AND state = 'idle in transaction' AND state_change < clock_timestamp() - make_interval(secs => ?)
            """.trimIndent(),
            Int::class.java, minIdle.toMillis() / 1000.0,
        )!!
        if (n >= 1) return
        check(System.nanoTime() < deadline) { "트랜잭션을 연 채 멈춘 세션이 ${timeout}안에 나타나지 않았다" }
        Thread.sleep(20)
    }
}

/** 기동 실패 예외의 원인 사슬. */
fun causesOf(e: Throwable): List<Throwable> = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.toList()
