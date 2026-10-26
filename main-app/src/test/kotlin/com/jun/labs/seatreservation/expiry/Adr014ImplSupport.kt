package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.drift.DriftTestSupport
import com.jun.labs.seatreservation.loadtest.LoadtestDataService
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.expiry.SeatMapQuery
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import javax.sql.DataSource

/**
 * ADR-014 구현자 테스트 공용(계약 테스트 Adr014*ContractTest는 blind 워커 몫 — 여기서는 구현이 설계 SQL대로 도는지를 본다).
 * 만료 상태는 DB 기준 상대 시각(`now() ± interval`)으로 행을 직접 써서 만든다 — 운영 SQL에 시각 바꾸기 훅을 두지 않는다(Q10).
 * 컨텍스트(방식)는 하위 클래스의 `@TestPropertySource`가 정한다 — 방식마다 하나: A 끔 고정(IntegrationTest)을 덮어 expiry-repair를 켜고(MISSING 복구 경로) ·
 * 배치 청크를 2로 줄여 진행형 커서를 여러 청크로 돌린다.
 */
abstract class Adr014ImplSupport : DriftTestSupport() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirmUseCase: ConfirmReservationUseCase
    @Autowired lateinit var expireHolds: ExpireHoldsUseCase
    @Autowired lateinit var properties: SeatHoldProperties
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var seatMap: SeatMapQuery
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var redis: StringRedisTemplate

    val mode: ExpiryMode get() = properties.expiryMode
    val s: Long get() = schedule.id!!

    private var nextSeatNo = 1

    fun seat(status: SeatStatus = SeatStatus.AVAILABLE, scheduleId: Long = s): Long = jdbcTemplate.queryForObject(
        "INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'A', 1, ?, ?) RETURNING id",
        Long::class.java, scheduleId, nextSeatNo++, status.name,
    )!!

    /** DB 기준 상대 시각 홀드 행 — [expiresInSeconds] < 0 = 이미 만료(Q10) */
    fun dbHold(seatId: Long, userId: Long, expiresInSeconds: Double, scheduleId: Long = s): Long = jdbcTemplate.queryForObject(
        """
        INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at)
        VALUES (?, ?, ?, now() - interval '300 seconds', now() + make_interval(secs => ?)) RETURNING id
        """.trimIndent(),
        Long::class.java, seatId, scheduleId, userId, expiresInSeconds,
    )!!

    /** 선점이 남긴 저장 상태 흉내 — B·C는 좌석 HELD(H10) · D는 그대로(HELD를 쓰지 않는다) */
    fun markHeld(seatId: Long) {
        if (mode.storesHeld) jdbcTemplate.update("UPDATE product_seat SET status = 'HELD' WHERE id = ?", seatId)
    }

    fun hold(seatId: Long, userId: Long, scheduleId: Long = s): HoldSeatResult = holdSeat.hold(HoldSeatCommand(scheduleId, seatId, userId))

    /** "ok" 또는 에러 코드 이름(nowait 패배는 "SEAT_NOT_AVAILABLE/nowait") */
    fun holdCode(seatId: Long, userId: Long, scheduleId: Long = s): String = try {
        hold(seatId, userId, scheduleId)
        "ok"
    } catch (e: SeatReservationException) {
        e.errorCode.name + if (e.lockNotAvailable) "/nowait" else ""
    }

    fun confirm(holdId: Long, userId: Long) = confirmUseCase.confirm(ConfirmReservationCommand(holdId, userId, "pay-$holdId"))

    fun confirmCode(holdId: Long, userId: Long): String = try {
        confirm(holdId, userId)
        "ok"
    } catch (e: SeatReservationException) {
        e.errorCode.name
    }

    fun status(seatId: Long): String = jdbcTemplate.queryForObject("SELECT status FROM product_seat WHERE id = ?", String::class.java, seatId)!!

    fun holdRows(seatId: Long): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE seat_id = ?", Int::class.java, seatId)!!

    fun activeRows(seatId: Long): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE seat_id = ? AND expires_at > now()", Int::class.java, seatId)!!

    fun holdExistsRow(holdId: Long): Boolean = jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE id = ?", Int::class.java, holdId)!! > 0

    fun reservations(seatId: Long): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM reservation WHERE seat_id = ? AND status = 'CONFIRMED'", Int::class.java, seatId)!!

    fun seatXmin(seatId: Long): String = jdbcTemplate.queryForObject("SELECT xmin::text FROM product_seat WHERE id = ?", String::class.java, seatId)!!

    /** 판정기(측정 대상이 스스로를 채점하지 않게 DB 행을 센다) — loadtest 프로필 없이 직접 만든다 */
    fun judge(): Map<String, Any?> = LoadtestDataService(jdbcTemplate, properties, redis, clock).consistency(20)

    fun mismatch(): Long = (judge()["v_counter_mismatch"] as Number).toLong()

    /** 앱 풀 밖 원시 연결(자동 커밋 끔) — 잠금을 쥔 채 멈춘 상대 트랜잭션 흉내 */
    fun rawConnection(): Connection {
        val h = dataSource.unwrap(HikariDataSource::class.java)
        return DriverManager.getConnection(h.jdbcUrl, h.username, h.password).apply { autoCommit = false }
    }

    fun Connection.pid(): Int = createStatement().use { st -> st.executeQuery("SELECT pg_backend_pid()").use { it.next(); it.getInt(1) } }

    fun Connection.exec(sql: String, vararg args: Any?) {
        prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.execute()
        }
    }

    /** [pid]가 잡은 잠금을 기다리는 세션의 문장들 — [n]개가 될 때까지 */
    fun awaitWaitersOf(pid: Int, n: Int = 1, timeout: Duration = Duration.ofSeconds(20)): List<String> {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val qs = jdbcTemplate.queryForList("SELECT query FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", String::class.java, pid)
            if (qs.size >= n) return qs
            check(System.nanoTime() < deadline) { "세션 $pid 를 기다리는 세션이 $timeout 안에 ${n}개가 되지 않았다(지금 ${qs.size})" }
            Thread.sleep(10)
        }
    }

    /** 이 백엔드 밖에서 잠금을 기다리는 업무 세션이 [n]개가 될 때까지(누가 쥐었는지 모를 때 — 앱 풀의 트랜잭션끼리) */
    fun awaitLockWaiters(n: Int = 1, timeout: Duration = Duration.ofSeconds(20)): List<String> {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val qs = jdbcTemplate.queryForList(
                "SELECT query FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database() AND pid <> pg_backend_pid()", String::class.java,
            )
            if (qs.size >= n) return qs
            check(System.nanoTime() < deadline) { "잠금 대기 세션이 $timeout 안에 ${n}개가 되지 않았다(지금 ${qs.size})" }
            Thread.sleep(10)
        }
    }

    fun deadlocks(): Long = jdbcTemplate.queryForObject(
        "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long::class.java,
    )!!
}

/** 방식별 컨텍스트 — expiry-repair를 켜고(IntegrationTest의 A 끔 고정을 덮는다) 배치 청크 2 */
const val ADR014_REPAIR_ON = "seat.hold.counter-drift.expiry-repair=true"
const val ADR014_SMALL_CHUNK = "seat.hold.expiry-batch-size=2"
/** B(lazy)는 정리 ①이 켜져 있어야 뜬다(코드 리뷰 CR14-1 — IntegrationTest의 정리 끔 고정을 덮는다). 주기는 1시간 — 배경 정리가 테스트 데이터를 고치지 않게(진입점은 테스트가 부른다) */
const val ADR014_LAZY_CLEANUP = "seat.hold.counter-drift.cleanup.mode=schedule"
const val ADR014_LAZY_CLEANUP_IDLE = "seat.hold.counter-drift.cleanup.interval=1h"

@TestPropertySource(properties = ["seat.hold.expiry-mode=lazy", ADR014_REPAIR_ON, ADR014_SMALL_CHUNK, ADR014_LAZY_CLEANUP, ADR014_LAZY_CLEANUP_IDLE])
annotation class LazyMode

@TestPropertySource(properties = ["seat.hold.expiry-mode=lazy-sweep", ADR014_REPAIR_ON, ADR014_SMALL_CHUNK])
annotation class LazySweepMode

@TestPropertySource(properties = ["seat.hold.expiry-mode=derived", ADR014_REPAIR_ON, ADR014_SMALL_CHUNK])
annotation class DerivedMode
