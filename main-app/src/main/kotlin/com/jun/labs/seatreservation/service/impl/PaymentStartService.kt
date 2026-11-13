package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.impl.payment.PaymentIntents
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
 * 결제 시작(`POST /api/holds/{holdId}/payment-start` — [com.jun.labs.seatreservation.api.PaymentStartController]). 모드가 갈래를 고른다:
 * - ① conditional = [check] 확인만(ADR-016 설계-9.2 · N16-1 — 어떤 행도 쓰지 않는다 · 읽기 전용 트랜잭션).
 * - ④ conditional-extend = [paymentStart] 첫 연장(ADR-015 §3.4 E1 · N9 — 아래).
 * θ = `seat.confirm.payment-min-remaining`(①과 ④ 공통 — ADR-016 설계-9.4).
 *
 * ADR-015 ④ 결제 시작 = 홀드 첫 연장(§3.4 E1 · N9):
 * - 홀드 행 하나만 쓴다(좌석 · 쿼터 무관). 한 홀드는 많아야 한 번 연장된다(V13 `extended_at` 표지 — E1의 `extended_at IS NULL`).
 * - 판정은 E1 영향 행 수가 하고, 0행일 때만 잠금 없는 진단(E1d)이 응답을 고른다(§설계-3 공통 규칙). 만료 비교는 `:now`(앱 Clock · 요청 시작).
 * - 지표 `seat.confirm.extend{result=extended|already|not_needed|too_late}`(§설계-8.2). 404 · 403 · 만료 409(진단 ①~③)는 태그가 정해져 있지 않아 세지 않는다.
 *   200 갈래(extended · already · not_needed)는 커밋 뒤에만 센다(R15-16 — 롤백 · 커밋 실패면 응답도 200이 아니다). too_late는 409로 거부하며 트랜잭션이
 *   늘 롤백되는 갈래(쓴 것 없음 — 진단 읽기뿐)라 그 자리에서 센다.
 *
 * ADR-017 설계-4.2(I13 — payment-start 필수화): 두 갈래 모두 첫 200에서 결제 의도(`payment_intent`)를 만들고 uid를 돌려준다(같은 트랜잭션).
 * - 같은 홀드의 재호출은 같은 uid · 같은 pay_by를 받는다. ①은 판정을 그대로 다시 한 뒤 기존 intent를 돌려주고, ④는 연장 없이 재생한다([replay] — 홀드가 없거나 남의 것 · 만료면 지금과 같은 404 · 403 · 409).
 * - **연장은 intent를 만들기 전에만 일어난다**(E1의 `NOT EXISTS payment_intent` — RD17-2): PG에 등록되는 deadline(= pay_by)이 확정 가능 기간과 어긋나지 않는다.
 * - ①의 '어떤 행도 쓰지 않는다'(ADR-016 N16-1)는 '홀드 · 좌석 · 쿼터 · 표지를 쓰지 않는다 — intent 행만 만든다'로 바뀐다(ADR-017 명세 §2).
 */
@Service
class PaymentStartService(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val confirmProperties: SeatConfirmProperties,
    private val meters: MeterRegistry,
    private val intents: PaymentIntents,
) {
    @Transactional
    fun paymentStart(holdId: Long, userId: Long): PaymentStartResult {
        val now = clock.instant()
        replay(holdId, userId, now)?.let { return it }
        val window = confirmProperties.paymentWindow
        val minRemaining = confirmProperties.paymentMinRemaining
        // E1 첫 연장 — 내 것 · 활성 · 아직 연장 안 함 · 연장이 실제로 늘리는 경우만(조건이 모두 홀드 행 자신의 열 — 동시 호출은 행 잠금 뒤 EPQ가 다시 본다)
        val extendedExpiresAt = jdbc.query(
            """
            UPDATE seat_hold
               SET expires_at = ?, extended_at = ?
             WHERE id = ? AND user_id = ?
               AND extended_at IS NULL
               AND expires_at > ?
               AND expires_at < ?
               AND NOT EXISTS (SELECT 1 FROM payment_intent i WHERE i.hold_id = seat_hold.id)
            RETURNING expires_at
            """.trimIndent(),
            { rs, _ -> rs.getTimestamp(1).toInstant() },
            Timestamp.from(now.plus(window)), Timestamp.from(now), holdId, userId,
            Timestamp.from(now.plus(minRemaining)), Timestamp.from(now.plus(window)),
        ).singleOrNull()
        if (extendedExpiresAt != null) {
            countAfterCommit(RESULT_EXTENDED)
            return open(holdId, userId, extendedExpiresAt, extended = true, now)
        }
        return diagnose(holdId, userId, now)
    }

    /**
     * ① 결제 시작 확인(ADR-016 설계-3.1 · 9.2): 잠금 없는 읽기 한 번 — 판정은 확정 C1이 다시 한다(200 ≠ 보장 · `extended = false`).
     * 위에서부터 첫 일치: 없음 404 · 남의 것 403 · `expires_at ≤ :now` 409 HOLD_EXPIRED(expired) · `expires_at − :now ≤ θ` 409 HOLD_EXPIRED(insufficient) · 그 밖 200(ok).
     * 시간 부족도 HOLD_EXPIRED다(ADR-015 '새 에러 코드 없음' · 클라이언트 행동이 같다) — 구분은 지표 `seat.confirm.pay_start{result}`로만.
     * 200은 커밋 뒤 · 거부는 그 자리에서 센다(④ [EXTEND_METRIC]와 같은 규칙). `readOnly` — 이 갈래의 쓰기는 오류로 드러난다(N16-1).
     */
    @Transactional
    fun check(holdId: Long, userId: Long): PaymentStartResult {
        val now = clock.instant()
        // ①은 재호출도 판정을 그대로 다시 한다(연장이 없어 intent가 판정을 바꿀 일이 없다) — 200이면 [open]이 기존 intent를 돌려준다
        val row = jdbc.query(
            "SELECT user_id, expires_at FROM seat_hold WHERE id = ?",
            { rs, _ -> rs.getLong(1) to rs.getTimestamp(2).toInstant() }, holdId,
        ).singleOrNull() ?: rejectCheck(PAY_NOT_FOUND, ErrorCode.HOLD_NOT_FOUND)
        val (owner, expiresAt) = row
        if (owner != userId) rejectCheck(PAY_NOT_OWNED, ErrorCode.HOLD_NOT_OWNED)
        if (!expiresAt.isAfter(now)) rejectCheck(PAY_EXPIRED, ErrorCode.HOLD_EXPIRED)
        if (!expiresAt.isAfter(now.plus(confirmProperties.paymentMinRemaining))) rejectCheck(PAY_INSUFFICIENT, ErrorCode.HOLD_EXPIRED)
        afterCommit { meters.counter(PAY_START_METRIC, "result", PAY_OK).increment() }
        return open(holdId, userId, expiresAt, extended = false, now)
    }

    /**
     * ADR-017 설계-4.2 — 이 홀드에 intent가 이미 있으면 판정 · 연장 없이 재생한다(같은 uid · 같은 pay_by).
     * ④ 전용. 홀드 행은 다시 읽는다: 없음 404 · 남의 것 403 · 만료 409(지금 진단과 같은 코드 · θ 검사 없음 — `already`와 같다). intent가 없으면 null.
     * 지표는 지금 진단과 같은 태그로 센다(연장됨 = `already` · 아니면 `not_needed` — intent가 생긴 뒤에는 연장하지 않으므로).
     */
    private fun replay(holdId: Long, userId: Long, now: Instant): PaymentStartResult? {
        val intent = intents.findByHold(holdId) ?: return null
        val row = jdbc.query(
            "SELECT user_id, expires_at, extended_at FROM seat_hold WHERE id = ?",
            { rs, _ -> Triple(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3)) }, holdId,
        ).singleOrNull() ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        val (owner, expiresAt, extendedAt) = row
        if (owner != userId) throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        if (!expiresAt.isAfter(now)) throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        countAfterCommit(if (extendedAt != null) RESULT_ALREADY else RESULT_NOT_NEEDED)
        return PaymentStartResult(holdId, expiresAt, extended = extendedAt != null, paymentUid = intent.uid, payBy = intent.payBy)
    }

    /** 첫 200 — intent를 만들고(같은 트랜잭션) uid · pay_by를 붙인다. 홀드가 그 사이 사라졌으면 404(만료 배치 · 확정과의 경합) */
    private fun open(holdId: Long, userId: Long, expiresAt: Instant, extended: Boolean, now: Instant): PaymentStartResult {
        val intent = intents.openForHold(holdId, userId, expiresAt.minus(confirmProperties.payByMargin), now)
            ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        return PaymentStartResult(holdId, expiresAt, extended, paymentUid = intent.uid, payBy = intent.payBy)
    }

    private fun rejectCheck(result: String, code: ErrorCode): Nothing {
        meters.counter(PAY_START_METRIC, "result", result).increment()
        throw SeatReservationException(code)
    }

    /**
     * E1 0행 진단(E1d — 잠금 없음 · 위에서부터 첫 일치): ① 없음 404 ② 남의 것 403 ③ 만료 409 ④ 이미 연장 200(지금 값) ⑤ 늦음 409
     * ⑥ 남은 시간 ≥ window 200(지금 값 · 표지 안 남김 — Q16) ⑦ 그 밖(설계상 생기지 않는다) ERROR 로그 + `seat.confirm.unexpected` + 409.
     */
    private fun diagnose(holdId: Long, userId: Long, now: Instant): PaymentStartResult {
        val row = jdbc.query(
            "SELECT user_id, expires_at, extended_at FROM seat_hold WHERE id = ?",
            { rs, _ -> Triple(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3)) }, holdId,
        ).singleOrNull() ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        val (owner, expiresAt, extendedAt) = row
        if (owner != userId) throw SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        if (!expiresAt.isAfter(now)) throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        if (extendedAt != null) {
            countAfterCommit(RESULT_ALREADY)
            return open(holdId, userId, expiresAt, extended = true, now)
        }
        if (!expiresAt.isAfter(now.plus(confirmProperties.paymentMinRemaining))) {
            count(RESULT_TOO_LATE) // 거부 갈래 — 예외로 롤백되므로 커밋 뒤가 오지 않는다(위 머리말)
            throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
        }
        if (!expiresAt.isBefore(now.plus(confirmProperties.paymentWindow))) {
            countAfterCommit(RESULT_NOT_NEEDED)
            return open(holdId, userId, expiresAt, extended = false, now)
        }
        // ADR-017: E1의 NOT EXISTS가 0행을 만든 경우 — 다른 호출이 이 사이 intent를 만들었다(경합) → 재생
        replay(holdId, userId, now)?.let { return it }
        meters.counter(AtomicConfirmProcess.UNEXPECTED_METRIC).increment()
        log.error("연장 E1이 0행인데 진단은 '내 것 · 활성 · 연장 전 · 범위 안'이다(설계상 생기지 않는다 — 409 HOLD_EXPIRED로 거부) hold={} user={} expires_at={} now={}", holdId, userId, expiresAt, now)
        throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
    }

    private fun count(result: String) = meters.counter(EXTEND_METRIC, "result", result).increment()

    /** 이 트랜잭션이 커밋된 뒤에만 센다(동기화가 없으면 — 트랜잭션 밖 호출 — 바로 센다) */
    private fun countAfterCommit(result: String) = afterCommit { count(result) }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return action()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
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
        /** ADR-016 설계-9.2 — ① payment-start 확인 결과 */
        const val PAY_START_METRIC = "seat.confirm.pay_start"
        private const val PAY_OK = "ok"
        private const val PAY_INSUFFICIENT = "insufficient"
        private const val PAY_EXPIRED = "expired"
        private const val PAY_NOT_FOUND = "not_found"
        private const val PAY_NOT_OWNED = "not_owned"
    }
}

/** [extended] = 이 홀드의 만료가 결제를 위해 연장돼 있다(④ extended · already) — ①은 늘 false(확인만 · ADR-016 설계-9.2) */
data class PaymentStartResult(
    val holdId: Long,
    val expiresAt: Instant,
    val extended: Boolean,
    /** ADR-017 설계-4.2 — 이 홀드의 결제 uid(서버 발급) */
    val paymentUid: String,
    /** intent에 저장된 결제 마감(= 첫 200의 expiresAt − M) — PG deadline */
    val payBy: Instant,
)
