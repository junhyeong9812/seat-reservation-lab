package com.jun.labs.seatreservation.service.impl.payment

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.PaymentProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * ADR-017 설계-4.2 · 명세 부록 A — payment-start 200 직전에 PG에 uid · deadline(= intent pay_by)을 등록한다(트랜잭션 밖).
 * PG 주소가 없으면 아무것도 하지 않는다(N17-12). 실패(타임아웃 · 5xx · 연결)면 503 — intent는 OPEN 그대로라 재호출이 다시 등록한다(멱등).
 * 409 CLOSED(안전망 · 거부가 이미 닫은 uid — RD17-P1)면 결제할 수 없으므로 409 HOLD_EXPIRED.
 */
@Component
class PgRegistration(
    private val payment: PaymentProperties,
    private val pg: PgClient,
    private val meters: MeterRegistry,
) {
    /** 좌석 [seatCount]석의 결제 금액 — `seat.payment.amount` × 좌석 수(1석 = 지금 금액 그대로 · 묶음 = N석의 합 — ADR-018 설계-4) */
    fun amountFor(seatCount: Int): Long = Math.multiplyExact(payment.amount, seatCount.toLong())

    /**
     * [amount]를 등록한다(ADR-018 설계-4 — 금액은 호출자가 결제 의도의 좌석 수로 정한다). 흉내 PG는 같은 uid의 재등록에서 처음 금액을 그대로 두므로
     * 응답의 금액이 [amount]와 다르면 503으로 거부하고 ERROR 로그를 남긴다(응답에 금액이 없으면 확인하지 않는다).
     */
    fun register(uid: String, userId: Long, deadline: Instant, amount: Long) {
        if (!payment.pgEnabled) return
        when (val call = pg.register(uid, userId, amount, deadline)) {
            is PgClient.Call.Ok -> {
                // 이미 닫힌 uid(환불 · 승인 취소 — 거부 뒤 워커가 끝냄)는 멱등 200으로 온다 — 결제할 수 없으므로 409 CLOSED와 같게(RD17-P1)
                if (call.state in CompensationWorker.CLOSED_STATES) {
                    meters.counter(METRIC, "result", "rejected").increment()
                    throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
                }
                if (call.amount != null && call.amount != amount) {
                    meters.counter(METRIC, "result", "amount_mismatch").increment()
                    log.error("PG 등록 금액이 요청 금액과 다르다(같은 uid의 앞 등록 금액이 남았다) uid={} requested={} registered={}", uid, amount, call.amount)
                    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PG 등록 금액 불일치")
                }
                meters.counter(METRIC, "result", "ok").increment()
            }
            is PgClient.Call.Rejected -> {
                meters.counter(METRIC, "result", "rejected").increment()
                if (call.code == "CLOSED") throw SeatReservationException(ErrorCode.HOLD_EXPIRED)
                log.error("PG 등록이 예상 밖으로 거부됐다 uid={} status={} code={}", uid, call.status, call.code)
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PG 등록 거부")
            }
            is PgClient.Call.Unavailable -> {
                meters.counter(METRIC, "result", "unavailable").increment()
                log.warn("PG 등록 실패(재호출로 다시 등록) uid={} reason={}", uid, call.reason)
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PG 등록 실패")
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PgRegistration::class.java)
        const val METRIC = "seat.payment.pg.register"
    }
}
