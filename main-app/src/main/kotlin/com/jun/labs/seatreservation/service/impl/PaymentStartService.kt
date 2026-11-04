package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/**
 * ADR-015 ④ 결제 시작 = 홀드 첫 연장(§3.4 E1 · N9) — `seat.confirm.mode=conditional-extend`에서만 API([com.jun.labs.seatreservation.api.PaymentStartController])가 부른다.
 * - 홀드 행 하나만 쓴다(좌석 · 쿼터 무관). 한 홀드는 많아야 한 번 연장된다(V13 `extended_at` 표지 — E1의 `extended_at IS NULL`).
 * - 판정은 E1 영향 행 수가 하고, 0행일 때만 잠금 없는 진단(E1d)이 응답을 고른다(§설계-3 공통 규칙). 만료 비교는 `:now`(앱 Clock · 요청 시작).
 * - 지표 `seat.confirm.extend{result=extended|already|not_needed|too_late}`(§설계-8.2). 404 · 403 · 만료 409(진단 ①~③)는 태그가 정해져 있지 않아 세지 않는다.
 *   200 갈래(extended · already · not_needed)는 커밋 뒤에만 센다(R15-16 — 롤백 · 커밋 실패면 응답도 200이 아니다). too_late는 409로 거부하며 트랜잭션이
 *   늘 롤백되는 갈래(쓴 것 없음 — 진단 읽기뿐)라 그 자리에서 센다.
 */
@Service
class PaymentStartService(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val confirmProperties: SeatConfirmProperties,
    private val meters: MeterRegistry,
) {
    @Transactional
    fun paymentStart(holdId: Long, userId: Long): PaymentStartResult {
        val now = clock.instant()
        val window = confirmProperties.paymentWindow
        val minRemaining = confirmProperties.extendMinRemaining
        // E1 첫 연장 — 내 것 · 활성 · 아직 연장 안 함 · 연장이 실제로 늘리는 경우만(조건이 모두 홀드 행 자신의 열 — 동시 호출은 행 잠금 뒤 EPQ가 다시 본다)
        val extended = jdbc.query(
            """
            UPDATE seat_hold
               SET expires_at = ?, extended_at = ?
             WHERE id = ? AND user_id = ?
               AND extended_at IS NULL
               AND expires_at > ?
               AND expires_at < ?
            RETURNING expires_at
            """.trimIndent(),
            { rs, _ -> rs.getTimestamp(1).toInstant() },
            Timestamp.from(now.plus(window)), Timestamp.from(now), holdId, userId,
            Timestamp.from(now.plus(minRemaining)), Timestamp.from(now.plus(window)),
        ).singleOrNull()
        if (extended != null) {
            countAfterCommit(RESULT_EXTENDED)
            return PaymentStartResult(holdId, extended)
        }
        return PaymentStartResult(holdId, diagnose(holdId, userId, now))
    }

    /**
     * E1 0행 진단(E1d — 잠금 없음 · 위에서부터 첫 일치): ① 없음 404 ② 남의 것 403 ③ 만료 409 ④ 이미 연장 200(지금 값) ⑤ 늦음 409
     * ⑥ 남은 시간 ≥ window 200(지금 값 · 표지 안 남김 — Q16) ⑦ 그 밖(설계상 생기지 않는다) ERROR 로그 + `seat.confirm.unexpected` + 409.
     */
    private fun diagnose(holdId: Long, userId: Long, now: Instant): Instant {
        val row = jdbc.query(
            "SELECT user_id, expires_at, extended_at FROM seat_hold WHERE id = ?",
            { rs, _ -> Triple(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3)) }, holdId,
        ).singleOrNull() ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        val (owner, expiresAt, extendedAt) = row
        if (owner != userId) throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        if (!expiresAt.isAfter(now)) throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        if (extendedAt != null) {
            countAfterCommit(RESULT_ALREADY)
            return expiresAt
        }
        if (!expiresAt.isAfter(now.plus(confirmProperties.extendMinRemaining))) {
            count(RESULT_TOO_LATE) // 거부 갈래 — 예외로 롤백되므로 커밋 뒤가 오지 않는다(위 머리말)
            throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        }
        if (!expiresAt.isBefore(now.plus(confirmProperties.paymentWindow))) {
            countAfterCommit(RESULT_NOT_NEEDED)
            return expiresAt
        }
        meters.counter(AtomicConfirmProcess.UNEXPECTED_METRIC).increment()
        log.error("연장 E1이 0행인데 진단은 '내 것 · 활성 · 연장 전 · 범위 안'이다(설계상 생기지 않는다 — 409 HOLD_EXPIRED로 거부) hold={} user={} expires_at={} now={}", holdId, userId, expiresAt, now)
        throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
    }

    private fun count(result: String) = meters.counter(EXTEND_METRIC, "result", result).increment()

    /** 이 트랜잭션이 커밋된 뒤에만 센다(동기화가 없으면 — 트랜잭션 밖 호출 — 바로 센다) */
    private fun countAfterCommit(result: String) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return count(result)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = count(result)
        })
    }

    companion object {
        private val log = LoggerFactory.getLogger(PaymentStartService::class.java)
        /** ADR-015 §3.4 · §설계-8.2 — `payment-start` 결과 */
        const val EXTEND_METRIC = "seat.confirm.extend"
        private const val RESULT_EXTENDED = "extended"
        private const val RESULT_ALREADY = "already"
        private const val RESULT_TOO_LATE = "too_late"
        private const val RESULT_NOT_NEEDED = "not_needed"
    }
}

data class PaymentStartResult(
    val holdId: Long,
    val expiresAt: Instant,
)
