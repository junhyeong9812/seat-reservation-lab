package com.jun.labs.seatreservation.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.jun.labs.seatreservation.domain.CompensationView
import com.jun.labs.seatreservation.service.impl.payment.CompensationTasks
import com.jun.labs.seatreservation.service.impl.payment.PaymentIntents
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * ADR-017 설계-4.3 결제 상태 조회 `GET /api/payments/{uid}` — 확정 결과와 보상 진행을 본다(본인 것만 · 남의 uid · 없는 uid는 같은 404 — 존재를 드러내지 않는다).
 */
@RestController
@Profile("!reconciler")
class PaymentController(
    private val intents: PaymentIntents,
    private val tasks: CompensationTasks,
) {
    @GetMapping("/api/payments/{uid}")
    fun get(
        @PathVariable uid: String,
        @RequestHeader(USER_ID_HEADER) userId: Long,
    ): ResponseEntity<PaymentView> {
        val intent = intents.find(uid)?.takeIf { it.userId == userId } ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            PaymentView(
                paymentUid = intent.uid, holdId = intent.holdId, state = intent.state.name, payBy = intent.payBy,
                resultCode = intent.resultCode, reservationId = intent.reservationId, compensation = tasks.viewFor(uid),
            ),
        )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PaymentView(
    val paymentUid: String,
    val holdId: Long,
    val state: String,
    val payBy: Instant,
    val resultCode: String?,
    val reservationId: Long?,
    val compensation: CompensationView?,
)
