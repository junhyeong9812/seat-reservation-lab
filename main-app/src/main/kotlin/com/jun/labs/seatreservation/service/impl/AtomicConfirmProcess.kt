package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationResult
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.impl.drift.MemoryChangedPairs
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * ADR-015 A(sweep) 확정 새 모드(§설계-3.1~3.4) — JDBC 문장으로 확인과 전환을 원자적으로 한다. 호출자([ConfirmReservationService.confirm])의 트랜잭션 안에서 돈다
 * (어느 문장이든 0행 → 거부 예외 → 트랜잭션 전체 롤백 — N1). 판정은 영향 행 수가 하고, 0행일 때만 잠금 없는 진단 문장이 에러 코드를 고른다(§설계-3 공통 규칙).
 * 에러 순서는 A와 같다: 404 HOLD_NOT_FOUND → 403 HOLD_NOT_OWNED → 409 HOLD_EXPIRED → 409 SEAT_NOT_HELD. 만료 비교는 `:now`(앱 Clock · 요청 시작) · 만료 정각 = 만료.
 * 잠금 순서(N8 · §3.5): 홀드 행 → 좌석 행 → 예약 INSERT(FK 검사). 쿼터 행은 잡지 않는다. ④(conditional-extend)의 확정 문장은 ①과 같다(Q4).
 * 예약 INSERT의 V12 위반은 current와 같은 매핑(409 SEAT_NOT_HELD + 지표 — N6). `converted_after_expiry`(+ 지연량 `.lateness`)는 clock=db(전환 문장 RETURNING 값) · clock=app 둘 다 커밋 뒤에 기록한다.
 * 측정 지연 주입(`seat.confirm.loadtest.delay` — §설계-8.2)은 모드별 '확인 뒤 · 첫 쓰기 앞' 한 자리 · 0이면 호출 없음.
 */
@Component
class AtomicConfirmProcess(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val changes: MemoryChangedPairs,
    private val meters: MeterRegistry,
    private val confirmProperties: SeatConfirmProperties,
) {
    /** 바깥 트랜잭션 안에서 부른다 — [mode]는 current가 아니어야 한다 */
    fun confirm(mode: ConfirmMode, command: ConfirmReservationCommand): ConfirmReservationResult {
        val now = clock.instant()
        val converted = when (mode) {
            ConfirmMode.CONDITIONAL, ConfirmMode.CONDITIONAL_EXTEND -> conditional(command, now)
            ConfirmMode.PESSIMISTIC -> pessimistic(command, now)
            ConfirmMode.OPTIMISTIC -> optimistic(command, now)
            ConfirmMode.CURRENT -> error("current 확정은 JPA 경로(ConfirmReservationService)가 한다")
        }
        val reservationId = insertReservation(command, converted, now)
        // 관측은 둘 다 커밋 뒤(R15-3 — 롤백된 확정은 세지 않는다). clock=db 지연량 = 전환 문장이 RETURNING으로 준 clock_timestamp() − expires_at(R15-9)
        val expiresAt = converted.expiresAt
        val dbLateness = Duration.ofNanos(converted.lateMicros * 1_000)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                ConfirmReservationService.convertedAfterExpiry(meters, "db", dbLateness)
                ConfirmReservationService.convertedAfterExpiry(meters, "app", Duration.between(expiresAt, clock.instant()))
            }
        })
        // ADR-012 ⓐ: 확정은 실제 값 쪽만 바꾼다(쿼터 행 그대로) — current와 같은 등록(method ≠ memory면 아무것도 안 함)
        changes.changed(converted.scheduleId, command.userId)
        return ConfirmReservationResult(reservationId = reservationId, seatId = converted.seatId, status = ReservationStatus.CONFIRMED)
    }

    /** 전환을 마친 홀드의 사실(예약 INSERT · 지표용) — [lateMicros] = 홀드를 지운 문장의 clock_timestamp() − expires_at(µs · 0 이하 = 만료 전) */
    private class Converted(val seatId: Long, val scheduleId: Long, val expiresAt: Instant, val lateMicros: Long)

    /** ① C1 · C2(④도 같다 — Q4) */
    private fun conditional(command: ConfirmReservationCommand, now: Instant): Converted {
        // 측정 지연 주입 자리 ①④ = `:now` 뒤 · C1 앞(§설계-8.2 · 0이면 아무것도 안 함)
        ConfirmReservationService.loadtestDelay(confirmProperties)
        // C1 홀드 소비 = 확인(내 것 · 만료 전) — 조건이 모두 홀드 행 자신의 열이라 기다린 뒤 EPQ가 최신 버전으로 다시 본다
        val c1 = jdbc.query(
            """
            DELETE FROM seat_hold WHERE id = ? AND user_id = ? AND expires_at > ?
            RETURNING seat_id, schedule_id, expires_at, $LATE_MICROS
            """.trimIndent(),
            { rs, _ -> Converted(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), rs.getLong(4)) },
            command.holdId, command.userId, Timestamp.from(now),
        ).singleOrNull() ?: throw diagnoseHold(command, now)
        // C2 좌석 전환 = 확인(HELD) — 0행이면 409(C1도 함께 롤백)
        if (jdbc.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ? AND status = 'HELD'", c1.seatId) == 0) {
            throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
        }
        return c1
    }

    /**
     * C1 0행 진단(C1d — 잠금 없음): 코드만 고른다(결과는 어느 갈래든 거부). 없음 404 · 남의 것 403 · 만료 409 HOLD_EXPIRED.
     * '내 것 · 활성'은 설계상 생기지 않는다 — ERROR 로그 + 지표 `seat.confirm.unexpected` + 409 HOLD_EXPIRED(무음 아님).
     */
    private fun diagnoseHold(command: ConfirmReservationCommand, now: Instant): SeatReservationException {
        val row = jdbc.query(
            "SELECT user_id, expires_at FROM seat_hold WHERE id = ?",
            { rs, _ -> rs.getLong(1) to rs.getTimestamp(2).toInstant() }, command.holdId,
        ).singleOrNull() ?: return SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if (row.first != command.userId) return SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        if (!row.second.isAfter(now)) return SeatReservationException(ErrorCode.HOLD_EXPIRED)
        meters.counter(UNEXPECTED_METRIC).increment()
        log.error("확정 C1이 0행인데 진단은 '내 것 · 활성'이다(설계상 생기지 않는다 — 409 HOLD_EXPIRED로 거부) hold={} user={} expires_at={} now={}", command.holdId, command.userId, row.second, now)
        return SeatReservationException(ErrorCode.HOLD_EXPIRED)
    }

    /** ② P0 ~ P4 — 잠금 순서 홀드 → 좌석(Q2 · 좌석 → 홀드는 A′ 배치와 고리가 닫힌다) */
    private fun pessimistic(command: ConfirmReservationCommand, now: Instant): Converted {
        // P0 홀드 찾기(잠금 없음) — 남의 홀드 행을 잠그지 않으려고 잠금 전에 404 · 403을 거른다(LazyConfirmProcess K1과 같다)
        val hold = jdbc.query(
            "SELECT seat_id, schedule_id, user_id FROM seat_hold WHERE id = ?",
            { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }, command.holdId,
        ).singleOrNull() ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if (hold.third != command.userId) throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        val (seatId, scheduleId) = hold
        // P1 홀드 행 잠금 + 새 판정 — 그 사이 지워졌으면(배치 · 다른 확정) 404 · 만료 409
        val expiresAt = jdbc.query(
            "SELECT expires_at FROM seat_hold WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.getTimestamp(1).toInstant() }, command.holdId,
        ).singleOrNull() ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if (!expiresAt.isAfter(now)) throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        // P2 좌석 행 잠금 — HELD가 아니면 409
        val status = jdbc.queryForObject("SELECT status FROM product_seat WHERE id = ? FOR UPDATE", String::class.java, seatId)
        if (status != HELD) throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
        // 측정 지연 주입 자리 ② = P2 뒤 · P3 앞(두 행 잠금을 쥔 채 — §설계-8.2 · 0이면 아무것도 안 함)
        ConfirmReservationService.loadtestDelay(confirmProperties)
        // P3 · P4 전환 — 두 행을 쥐었으므로 조건 없이 쓴다
        jdbc.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", seatId)
        val late = jdbc.query(
            "DELETE FROM seat_hold WHERE id = ? RETURNING $LATE_MICROS",
            { rs, _ -> rs.getLong(1) }, command.holdId,
        )
        if (late.size != 1) {
            // 잠근 행이 사라질 수 없다(설계상 생기지 않는다) — 조용히 넘기지 않고 롤백(5xx로 드러남)
            meters.counter(UNEXPECTED_METRIC).increment()
            log.error("확정 P4 홀드 DELETE가 {}행이다(P1이 잠근 행 — 설계상 1행) hold={} seat={}", late.size, command.holdId, seatId)
            error("확정 P4 홀드 DELETE ${late.size}행(hold=${command.holdId}) — 롤백한다")
        }
        return Converted(seatId, scheduleId, expiresAt, late.single())
    }

    /** ③ O1 ~ O3 — 좌석 버전(V14 트리거가 상태 변경마다 올린다) 비교 · 재시도 없음 */
    private fun optimistic(command: ConfirmReservationCommand, now: Instant): Converted {
        // O1 한 문장 = 한 스냅숏(잠금 없음) — A 순서로 판정
        val row = jdbc.query(
            """
            SELECT h.seat_id, h.schedule_id, h.user_id, h.expires_at, s.status, s.version
            FROM seat_hold h JOIN product_seat s ON s.id = h.seat_id WHERE h.id = ?
            """.trimIndent(),
            { rs, _ -> OptimisticRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getLong(6)) },
            command.holdId,
        ).singleOrNull() ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if (row.userId != command.userId) throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        if (!row.expiresAt.isAfter(now)) throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        if (row.status != HELD) throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
        // 측정 지연 주입 자리 ③ = O1 뒤 · O2 앞(§설계-8.2 · 0이면 아무것도 안 함)
        ConfirmReservationService.loadtestDelay(confirmProperties)
        // O2 홀드 삭제 — 0행(그 사이 배치 · 다른 확정) → 404. 순서 홀드 → 좌석(①과 같은 이유)
        val late = jdbc.query(
            "DELETE FROM seat_hold WHERE id = ? RETURNING $LATE_MICROS",
            { rs, _ -> rs.getLong(1) }, command.holdId,
        ).singleOrNull() ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        // O3 버전 비교 전환(트리거가 version을 올린다) — 0행이면 좌석을 다시 읽어 코드를 고른다
        if (jdbc.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ? AND version = ?", row.seatId, row.version) == 0) {
            val status = jdbc.queryForList("SELECT status FROM product_seat WHERE id = ?", String::class.java, row.seatId).singleOrNull()
            if (status == HELD) {
                // 내 홀드 행이 살아 있었는데(O2 1행) 좌석 버전이 바뀌고 HELD — A 불변식 ②(HELD ⇔ 홀드 1개) 아래서는 생기지 않는다(무음 아님)
                meters.counter(UNEXPECTED_METRIC).increment()
                log.error("확정 O3 버전 비교가 0행인데 좌석은 HELD다(설계상 생기지 않는다 — 409 SEAT_NOT_HELD로 거부) hold={} seat={} version={}", command.holdId, row.seatId, row.version)
            }
            throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
        }
        return Converted(row.seatId, row.scheduleId, row.expiresAt, late)
    }

    private class OptimisticRow(val seatId: Long, val scheduleId: Long, val userId: Long, val expiresAt: Instant, val status: String, val version: Long)

    /** C3 · P5 · O4 예약(created_at = :now — current와 같다) — V12 위반은 current와 같은 매핑(N6) */
    private fun insertReservation(command: ConfirmReservationCommand, converted: Converted, now: Instant): Long = try {
        jdbc.queryForObject(
            """
            INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
            Long::class.java, converted.scheduleId, converted.seatId, command.userId, command.paymentUid, ReservationStatus.CONFIRMED.name, Timestamp.from(now),
        )!!
    } catch (e: DataIntegrityViolationException) {
        throw ConfirmReservationService.uniqueViolationOrRethrow(meters, e, command, converted.seatId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AtomicConfirmProcess::class.java)
        private const val HELD = "HELD"
        /**
         * 전환 문장 RETURNING 열 — 그 문장을 실행한 DB 시각(clock_timestamp() · 트랜잭션 시작 now()가 아니다)과 expires_at의 차(µs).
         * clock_timestamp()를 한 번만 불러 '늦음'(> 0)과 지연량이 같은 값에서 나온다(timestamptz 해상도 = µs라 정확하다)
         */
        private const val LATE_MICROS = "(EXTRACT(EPOCH FROM clock_timestamp() - expires_at) * 1000000)::bigint AS late_us"
        /** 설계상 생기지 않는 갈래(무음 아님 — 기대 0) */
        const val UNEXPECTED_METRIC = "seat.confirm.unexpected"
    }
}
