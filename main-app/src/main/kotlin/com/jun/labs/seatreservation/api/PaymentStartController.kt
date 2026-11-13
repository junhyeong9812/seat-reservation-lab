package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.impl.PaymentStartService
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Profile
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * `POST /api/holds/{holdId}/payment-start` — 클라이언트가 사용자를 결제 화면으로 보내기 직전에 부른다(호출은 권고 — 확정이 검사하지 않는다 · ADR-016 설계-9).
 * - ① conditional: 남은 시간 확인만(ADR-016 설계-9.2 — 쓰기 0 · 200 ≠ 보장).
 * - ④ conditional-extend: 첫 호출만 홀드를 연장(ADR-015 §3.4).
 * 두 모드에서만 등록한다 — current · ② · ③에는 핸들러가 없다(404 · N16-7). reconciler 프로필은 업무 요청을 받지 않는다(지금 컨트롤러와 같다).
 * 200 = `{holdId, expiresAt, payBy, payWithinSeconds, extended, paymentUid}`(ADR-016 설계-9.2 · 9.3 · ADR-017 설계-4.2 — payBy는 intent에 저장된 값).
 * ADR-017(I13): 결제는 이 응답의 `paymentUid`로만 한다 — 호출이 권고에서 결제의 필수 단계가 됐다.
 */
@RestController
@Profile("!reconciler")
@Conditional(PaymentStartController.PaymentStartModes::class)
class PaymentStartController(
    private val paymentStartService: PaymentStartService,
    private val confirmProperties: SeatConfirmProperties,
    private val payBy: PayBy,
) {

    @PostMapping("/api/holds/{holdId}/payment-start")
    fun paymentStart(
        @PathVariable holdId: Long,
        @RequestHeader(USER_ID_HEADER) userId: Long,
    ): PaymentStartResponse {
        val result = when (confirmProperties.mode) {
            ConfirmMode.CONDITIONAL_EXTEND -> paymentStartService.paymentStart(holdId, userId)
            ConfirmMode.CONDITIONAL -> paymentStartService.check(holdId, userId)
            else -> error("payment-start는 ${ConfirmMode::paymentStartEnabled.name} 모드에서만 등록된다 — 현재 ${confirmProperties.mode}")
        }
        return PaymentStartResponse(
            holdId = result.holdId, expiresAt = result.expiresAt,
            payBy = result.payBy, payWithinSeconds = payBy.remaining(result.payBy), extended = result.extended,
            paymentUid = result.paymentUid,
        )
    }

    /**
     * `seat.confirm.mode`를 SeatConfirmProperties와 같은 규칙(relaxed enum 바인딩)으로 읽는다 — 키 표기(`conditional-extend` · `CONDITIONAL_EXTEND`)와 무관하게 같은 판정.
     * 키가 없으면 SeatConfirmProperties 기본값(conditional-extend — ADR-016 §결정 · 그 전 conditional — ADR-015 §결정)과 같게 본다.
     */
    class PaymentStartModes : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            Binder.get(context.environment).bind("seat.confirm.mode", ConfirmMode::class.java).orElse(SeatConfirmProperties().mode).paymentStartEnabled
    }
}

data class PaymentStartResponse(
    val holdId: Long,
    val expiresAt: Instant,
    val payBy: Instant,
    val payWithinSeconds: Long,
    val extended: Boolean,
    val paymentUid: String,
)
