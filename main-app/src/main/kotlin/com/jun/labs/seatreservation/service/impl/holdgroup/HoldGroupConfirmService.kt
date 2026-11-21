package com.jun.labs.seatreservation.service.impl.holdgroup

import com.jun.labs.seatreservation.domain.CompensationView
import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.httpStatusCode
import com.jun.labs.seatreservation.service.CompensationProperties
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.impl.ConfirmReservationService
import com.jun.labs.seatreservation.service.impl.DbDeadlock
import com.jun.labs.seatreservation.service.impl.drift.MemoryChangedPairs
import com.jun.labs.seatreservation.service.impl.payment.CompensationTasks
import com.jun.labs.seatreservation.service.impl.payment.PaymentConfirmService
import com.jun.labs.seatreservation.service.impl.payment.PaymentIntents
import com.jun.labs.seatreservation.service.impl.payment.PaymentIntents.State
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/**
 * ADR-018 설계-5 묶음 확정 — 1석 결제 의도 경로(PaymentConfirmService)와 같은 틀을 묶음에 적용한다(보상 모드는 none · refund만 — 기동 검사).
 * 한 트랜잭션: ⓐ 결제 의도 `FOR UPDATE` → ⓑ 묶음 · 사용자 불일치면 409 SEAT_NOT_HELD · 기록 없음 → ⓒ 이미 처리됐으면 재생(예약 N행을 좌석 id 순으로 —
 * 읽은 수 ≠ seat_count면 500 · ERROR) → ⓓ 묶음 홀드 N행 소비(id 순 잠금 · 내 것 · 만료 전) · 좌석 N행 RESERVED · 예약 N행 · CONFIRMED.
 * ⓓ의 잠금은 만료 전 행만 id 순으로 — 만료 배치와 엇갈리지 않는 것은 두 경로의 now가 같을 때만이다. 앱 시계로 따로 읽은 now가 달라 만료 시각 경계
 * (이쪽 now < E ≤ 배치 now)에서는 배치(물리 순 DELETE)와 엇갈려 40P01이 날 수 있다 — 드물고 롤백으로 회복되며, 결제 의도는 OPEN으로 남는다 — 보상 모드 refund면 안전망이 환불하고, none이면 안전망은 탐지만 한다(ADR-018 설계-5 남은 위험).
 * 어느 문장이든 N행이 아니면 거부(롤백 — N석 모두 그대로) → 새 트랜잭션에서 다시 잠가 아직 OPEN이면 거부 기록 + REJECTED + (refund) CLOSE 작업 1건.
 * 카운터는 바꾸지 않는다(홀드 N행 → 예약 N행 — 1석과 같다).
 */
@Service
class HoldGroupConfirmService(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val intents: PaymentIntents,
    private val tasks: CompensationTasks,
    private val compensation: CompensationProperties,
    private val changes: MemoryChangedPairs,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    fun confirm(groupId: Long, userId: Long, paymentUid: String): List<GroupReservation> {
        val rejected = try {
            return tx.execute { confirmInTx(groupId, userId, paymentUid) }!!
        } catch (e: Replayed) {
            throw e.rejection
        } catch (e: SeatReservationException) {
            e
        } catch (e: UidConflict) {
            meters.counter(PaymentConfirmService.UID_CONFLICT_METRIC).increment()
            throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
        }
        val outcome = try {
            tx.execute { recordRejection(paymentUid, rejected.errorCode) }!!
        } catch (e: Replayed) {
            throw e.rejection
        } catch (e: RuntimeException) {
            meters.counter(PaymentConfirmService.RECORD_FAILED_METRIC).increment()
            // ADR-018 2단계 — 기록 실패가 40P01이면 교착으로도 센다(응답은 아래 거부 그대로 · 예외가 밖으로 안 나가 인터셉터는 다시 세지 않는다)
            DbDeadlock.record(meters, DbDeadlock.PATH_GROUP_CONFIRM, e)
            log.error("묶음 확정 거부 기록에 실패했다 — 결제 의도는 OPEN으로 남고 안전망이 닫는다 uid={} code={}", paymentUid, rejected.errorCode, e)
            throw rejected
        }
        // 기록 트랜잭션이 커밋된 뒤에 거부로 답한다(트랜잭션 안에서 던지면 기록이 롤백된다)
        return outcome.fold({ it }, { throw it })
    }

    private fun confirmInTx(groupId: Long, userId: Long, uid: String): List<GroupReservation> {
        val intent = intents.lockForGroup(uid) ?: throw UidConflict()
        if (intent.groupId != groupId || intent.userId != userId) throw UidConflict()
        if (intent.state != State.OPEN) return replay(intent)
        val n = intent.seatCount
        val now = clock.instant()
        val seats = jdbc.query(
            """
            DELETE FROM seat_hold
             WHERE id IN (SELECT id FROM seat_hold WHERE group_id = ? AND user_id = ? AND expires_at > ? ORDER BY id FOR UPDATE)
            RETURNING seat_id
            """.trimIndent(),
            { rs, _ -> rs.getLong(1) }, groupId, userId, Timestamp.from(now),
        ).sorted()
        if (seats.size != n) throw diagnose(groupId, userId, now, seats.size, n)
        val seatArray = seats.toTypedArray()
        if (jdbc.update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ANY(?::bigint[]) AND status = 'HELD'", seatArray) != n) {
            throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
        }
        val reservations = try {
            jdbc.query(
                """
                INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at, intent_id)
                SELECT ?, s, ?, ?, ?, ?, ? FROM unnest(?::bigint[]) AS s
                RETURNING id, seat_id
                """.trimIndent(),
                { rs, _ -> GroupReservation(rs.getLong(1), rs.getLong(2)) },
                intent.scheduleId, userId, uid, ReservationStatus.CONFIRMED.name, Timestamp.from(now), uid, seatArray,
            )
        } catch (e: DataIntegrityViolationException) {
            throw ConfirmReservationService.uniqueViolationOrRethrow(meters, e, ConfirmReservationCommand(intent.holdId, userId, uid), null)
        }
        jdbc.update(
            "UPDATE payment_intent SET state = ?, reservation_id = ? WHERE uid = ?",
            State.CONFIRMED.name, reservations.minOf { it.reservationId }, uid,
        )
        changes.changed(intent.scheduleId, userId)
        return reservations.sortedBy { it.seatId }
    }

    /** 홀드 소비가 N행이 아니다 — 잠금 없는 진단으로 코드만 고른다(없음 404 · 남의 것 403 · 만료 409 · 그 밖 409 + ERROR) */
    private fun diagnose(groupId: Long, userId: Long, now: Instant, got: Int, n: Int): SeatReservationException {
        val rows = jdbc.query(
            "SELECT user_id, expires_at FROM seat_hold WHERE group_id = ?",
            { rs, _ -> rs.getLong(1) to rs.getTimestamp(2).toInstant() }, groupId,
        )
        if (got == 0 && rows.isEmpty()) return SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        if (rows.any { it.first != userId }) return SeatReservationException(ErrorCode.HOLD_NOT_OWNED)
        if (rows.any { !it.second.isAfter(now) }) return SeatReservationException(ErrorCode.HOLD_EXPIRED)
        log.error("묶음 확정 홀드 소비가 {}행 · 묶음 {}석 — 남은 홀드 {}행(설계상 생기지 않는다 — 409 HOLD_EXPIRED) group={} user={}", got, n, rows.size, groupId, userId)
        return SeatReservationException(ErrorCode.HOLD_EXPIRED)
    }

    /** 거부 뒤 새 트랜잭션 — 다시 잠가 OPEN이면 기록, 아니면 재생 */
    private fun recordRejection(uid: String, code: ErrorCode): Either {
        val intent = intents.lockForGroup(uid)!!
        if (intent.state != State.OPEN) return Either(replay(intent), null)
        val now = clock.instant()
        jdbc.update(
            "INSERT INTO confirm_rejection (uid, code, http_status, rejected_at) VALUES (?, ?, ?, ?)",
            uid, code.name, code.httpStatusCode(), Timestamp.from(now),
        )
        jdbc.update("UPDATE payment_intent SET state = ?, result_code = ? WHERE uid = ?", State.REJECTED.name, code.name, uid)
        val task: CompensationView? = if (compensation.mode.createsTasks) tasks.enqueue(uid, CompensationTasks.Type.CLOSE, CompensationTasks.Source.REJECTION, now) else null
        return Either(null, SeatReservationException(code, compensation = task))
    }

    /** 기록 트랜잭션의 결과 — 재생된 확정([confirmed]) 또는 이번에 기록한 거부([rejection]) */
    private class Either(val confirmed: List<GroupReservation>?, val rejection: SeatReservationException?) {
        fun fold(ok: (List<GroupReservation>) -> List<GroupReservation>, fail: (SeatReservationException) -> Nothing): List<GroupReservation> =
            confirmed?.let(ok) ?: fail(rejection!!)
    }

    /** ⓒ 재생 — 확정이면 예약 N행(좌석 id 순), 거부 · 안전망 종결이면 첫 결과 코드 + 보상 상태(지금 값) */
    private fun replay(intent: PaymentIntents.GroupIntent): List<GroupReservation> {
        if (intent.state != State.CONFIRMED) {
            throw Replayed(SeatReservationException(ErrorCode.valueOf(intent.resultCode!!), compensation = tasks.viewFor(intent.uid)))
        }
        val rows = jdbc.query(
            "SELECT id, seat_id FROM reservation WHERE intent_id = ? ORDER BY seat_id",
            { rs, _ -> GroupReservation(rs.getLong(1), rs.getLong(2)) }, intent.uid,
        )
        if (rows.size != intent.seatCount) {
            log.error("묶음 확정 재생: 결제 의도의 예약 {}행 · seat_count {}(group_partial_reservations) uid={}", rows.size, intent.seatCount, intent.uid)
            error("묶음 확정 재생: 예약 ${rows.size}행 ≠ seat_count ${intent.seatCount} (uid=${intent.uid})")
        }
        return rows
    }

    private class UidConflict : RuntimeException(null, null, false, false)

    private class Replayed(val rejection: SeatReservationException) : RuntimeException(null, null, false, false)

    companion object {
        private val log = LoggerFactory.getLogger(HoldGroupConfirmService::class.java)
    }
}

data class GroupReservation(val reservationId: Long, val seatId: Long)
