package com.jun.labs.seatreservation.service.impl.payment

import com.jun.labs.seatreservation.domain.CompensationView
import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.httpStatusCode
import com.jun.labs.seatreservation.service.CompensationMode
import com.jun.labs.seatreservation.service.CompensationProperties
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationResult
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.impl.payment.PaymentIntents.State
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock

/**
 * ADR-017 설계-4.3 확정 흐름(트랜잭션 없음 — 단계마다 자기 트랜잭션).
 * - intent 없는 uid = 옛 경로: 지금 확정 그대로([ConfirmReservationUseCase]) · 지표 `seat.confirm.legacy`(N17-10 — 보장 밖).
 *   단 홀드에 이미 intent가 있으면(payment-start를 불렀다) 그 uid로만 확정한다 — 다른 uid는 409 SEAT_NOT_HELD · 기록 없음(Q3과 대칭 · I13).
 *   옛 경로로 확정되면 intent가 OPEN으로 남아 안전망이 그 결제를 닫는다(좌석 + 환불).
 * - intent 경로 = 한 트랜잭션: ⓐ intent `FOR UPDATE`(모든 경로의 첫 잠금) → ⓑ hold · user 불일치면 409 SEAT_NOT_HELD · 기록 없음(Q3)
 *   → ⓒ state ≠ OPEN이면 재생(첫 결과 코드 · 예약 id 고정 · 보상 상태는 지금 값 — N17-3) → ⓓ 지금 확정(같은 트랜잭션에 합류) + state CONFIRMED · reservation.intent_id.
 * - ⓓ 거부: 그 트랜잭션은 롤백 → 새 트랜잭션에서 ⓐ를 다시 잡고, 아직 OPEN이면 거부 기록 + REJECTED + (모드 ≠ none) CLOSE 작업 · OPEN이 아니면 재생(A2).
 *   두 번째 트랜잭션이 실패하면 무음이 아니다(ERROR 로그 + 지표 `seat.compensation.record_failed`) — intent는 OPEN으로 남고 안전망이 닫는다(N17-2).
 * - 5xx(도메인 거부가 아닌 예외)는 기록하지 않는다(Q9 — 안전망 몫).
 */
@Service
class PaymentConfirmService(
    private val confirmUseCase: ConfirmReservationUseCase,
    private val intents: PaymentIntents,
    private val tasks: CompensationTasks,
    private val substitutes: SubstituteAllocator,
    private val compensation: CompensationProperties,
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    fun confirm(command: ConfirmReservationCommand): ConfirmResponse {
        if (intents.find(command.paymentUid) == null) {
            // 남의 홀드면 옛 경로가 지금처럼 403으로 거부한다(진단 그대로) — 내 홀드에 intent가 있을 때만 막는다
            if (intents.findByHold(command.holdId)?.userId == command.userId) {
                meters.counter(UID_CONFLICT_METRIC).increment()
                throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
            }
            meters.counter(LEGACY_METRIC).increment()
            return ConfirmResponse(confirmUseCase.confirm(command), compensation = null)
        }
        val rejected = try {
            return tx.execute { confirmIntent(command) }!!
        } catch (e: Replayed) {
            throw e.rejection // 이미 닫힌 intent의 거부 재생 — 재잠금 · 기록 트랜잭션이 필요 없다(상태를 바꾸지 않는 ⓒ)
        } catch (e: SeatReservationException) {
            e // ⓓ의 새 거부 — 아래 재잠금이 아직 OPEN이면 기록하고, 그 사이 닫혔으면 재생한다(A2)
        } catch (e: UidConflict) {
            meters.counter(UID_CONFLICT_METRIC).increment()
            throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
        }
        // S: 대체 배정을 먼저 한 트랜잭션에서 해 보고(성공이면 SUBSTITUTED), 안 되면 그 트랜잭션을 버리고 CLOSE로 넘긴다(설계-5 · N17-9)
        if (compensation.mode == CompensationMode.SUBSTITUTE) {
            try {
                return tx.execute { substitute(command.paymentUid, rejected.errorCode) }!!.orThrow()
            } catch (e: SeatReservationException) {
                throw e // 재잠금에서 본 재생(이미 닫힌 intent) · 대체 성공의 거부 응답
            } catch (e: SubstituteAllocator.Unavailable) {
                log.info("대체 좌석 배정 실패({}) — CLOSE로 넘긴다 uid={}", e.message, command.paymentUid)
            } catch (e: RuntimeException) {
                // 교착 · 잠금 대기 초과 등 DB 오류 — 대체 트랜잭션은 롤백됐다. 무음 500이 아니라 CLOSE로 넘긴다(설계-5 '실패 → 새 트랜잭션에서 CLOSE')
                log.error("대체 좌석 배정 중 오류 — CLOSE로 넘긴다 uid={} code={}", command.paymentUid, rejected.errorCode, e)
            }
        }
        val outcome = try {
            tx.execute { recordRejection(command.paymentUid, rejected.errorCode) }!!
        } catch (e: RuntimeException) {
            meters.counter(RECORD_FAILED_METRIC).increment()
            log.error("확정 거부 기록에 실패했다 — intent는 OPEN으로 남고 안전망이 닫는다(N17-2) uid={} code={}", command.paymentUid, rejected.errorCode, e)
            throw rejected
        }
        return outcome.orThrow()
    }

    /** ⓐ~ⓓ — 거부는 예외로 나가 이 트랜잭션을 롤백한다 */
    private fun confirmIntent(command: ConfirmReservationCommand): ConfirmResponse {
        val intent = intents.lock(command.paymentUid)!!
        if (intent.holdId != command.holdId || intent.userId != command.userId) throw UidConflict()
        if (intent.state != State.OPEN) {
            return when (val outcome = replay(intent)) {
                is Outcome.Confirmed -> outcome.response
                is Outcome.Rejected -> throw Replayed(SeatReservationException(outcome.code, compensation = outcome.task))
            }
        }
        val result = confirmUseCase.confirm(command)
        jdbc.update("UPDATE reservation SET intent_id = ? WHERE id = ?", intent.uid, result.reservationId)
        jdbc.update(
            "UPDATE payment_intent SET state = ?, reservation_id = ? WHERE uid = ?",
            State.CONFIRMED.name, result.reservationId, intent.uid,
        )
        // D: 확정 성공 ⇔ 같은 트랜잭션의 CAPTURE 작업(N17-8)
        val capture = if (compensation.mode == CompensationMode.AUTH_CAPTURE) {
            tasks.enqueue(intent.uid, CompensationTasks.Type.CAPTURE, CompensationTasks.Source.CONFIRM, clock.instant())
        } else {
            null
        }
        return ConfirmResponse(result, compensation = capture)
    }

    /** 거부 뒤 새 트랜잭션 — 재잠금 · OPEN이면 기록, 아니면 재생 */
    private fun recordRejection(uid: String, code: ErrorCode): Outcome {
        val intent = intents.lock(uid)!!
        if (intent.state != State.OPEN) return replay(intent)
        val now = clock.instant()
        jdbc.update(
            "INSERT INTO confirm_rejection (uid, code, http_status, rejected_at) VALUES (?, ?, ?, ?)",
            uid, code.name, code.httpStatusCode(), Timestamp.from(now),
        )
        jdbc.update("UPDATE payment_intent SET state = ?, result_code = ? WHERE uid = ?", State.REJECTED.name, code.name, uid)
        val task = if (compensation.mode.createsTasks) tasks.enqueue(uid, CompensationTasks.Type.CLOSE, CompensationTasks.Source.REJECTION, now) else null
        return Outcome.Rejected(code, task)
    }

    /** S — 재잠금 · OPEN이면 거부 기록 + 대체 배정(실패면 [SubstituteAllocator.Unavailable]로 이 트랜잭션째 버린다) */
    private fun substitute(uid: String, code: ErrorCode): Outcome {
        val intent = intents.lock(uid)!!
        if (intent.state != State.OPEN) return replay(intent)
        val now = clock.instant()
        jdbc.update(
            "INSERT INTO confirm_rejection (uid, code, http_status, rejected_at) VALUES (?, ?, ?, ?)",
            uid, code.name, code.httpStatusCode(), Timestamp.from(now),
        )
        val got = substitutes.allocate(intent, code, now)
        return Outcome.Rejected(code, substituted(got.reservationId, got.seatId))
    }

    private fun substituted(reservationId: Long, seatId: Long) =
        CompensationView(taskId = null, type = SUBSTITUTE, status = "DONE", reservationId = reservationId, seatId = seatId)

    /** 조회 · 재생 공용 — 그 intent의 보상 정보(지금 값). 대체 성공이면 SUBSTITUTE, 아니면 작업(CLOSE 먼저 · 없으면 CAPTURE) */
    fun compensationView(intent: PaymentIntents.Intent): CompensationView? {
        if (intent.state == State.SUBSTITUTED) {
            val seatId = jdbc.queryForObject("SELECT seat_id FROM reservation WHERE id = ?", Long::class.java, intent.reservationId)!!
            return substituted(intent.reservationId!!, seatId)
        }
        return tasks.viewFor(intent.uid)
    }

    /** ⓒ 재생 — 첫 결과 코드 · 예약 id는 intent에서, 보상 상태는 지금 값 */
    private fun replay(intent: PaymentIntents.Intent): Outcome {
        if (intent.state == State.CONFIRMED) {
            val seatId = jdbc.queryForObject("SELECT seat_id FROM reservation WHERE id = ?", Long::class.java, intent.reservationId)!!
            return Outcome.Confirmed(ConfirmResponse(ConfirmReservationResult(intent.reservationId!!, seatId, ReservationStatus.CONFIRMED), tasks.find(intent.uid, CompensationTasks.Type.CAPTURE)))
        }
        return Outcome.Rejected(ErrorCode.valueOf(intent.resultCode!!), compensationView(intent))
    }

    private sealed interface Outcome {
        fun orThrow(): ConfirmResponse
        data class Confirmed(val response: ConfirmResponse) : Outcome { override fun orThrow() = response }
        data class Rejected(val code: ErrorCode, val task: CompensationView?) : Outcome {
            override fun orThrow(): Nothing = throw SeatReservationException(code, compensation = task)
        }
    }

    /** ⓑ 다른 hold · user의 같은 uid(Q3) — 기록 없이 409 */
    private class UidConflict : RuntimeException()

    /** ⓒ 이미 닫힌 intent의 거부 재생 — 새 거부와 갈라 곧바로 응답한다 */
    private class Replayed(val rejection: SeatReservationException) : RuntimeException(null, null, false, false)

    companion object {
        private val log = LoggerFactory.getLogger(PaymentConfirmService::class.java)
        const val LEGACY_METRIC = "seat.confirm.legacy"
        const val UID_CONFLICT_METRIC = "seat.confirm.uid_conflict"
        const val RECORD_FAILED_METRIC = "seat.compensation.record_failed"
        const val SUBSTITUTE = "SUBSTITUTE"
    }
}

/** 확정 성공 응답(intent 경로 D 모드에서만 compensation = CAPTURE 작업) */
data class ConfirmResponse(val result: ConfirmReservationResult, val compensation: CompensationView?)
