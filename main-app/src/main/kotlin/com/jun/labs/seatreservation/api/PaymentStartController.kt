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
 * 200 = `{holdId, expiresAt, payBy, payWithinSeconds, extended}`(ADR-016 설계-9.2 · 9.3).
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
            else -> paymentStartService.check(holdId, userId) // 등록 조건상 ① 뿐
        }
        val deadline = payBy.of(result.expiresAt)
        return PaymentStartResponse(
            holdId = result.holdId, expiresAt = result.expiresAt,
            payBy = deadline.payBy, payWithinSeconds = deadline.payWithinSeconds, extended = result.extended,
        )
    }

    /**
     * `seat.confirm.mode`를 SeatConfirmProperties와 같은 규칙(relaxed enum 바인딩)으로 읽는다 — 키 표기(`conditional-extend` · `CONDITIONAL_EXTEND`)와 무관하게 같은 판정.
     * 키가 없으면 SeatConfirmProperties 기본값(conditional — ADR-015 §결정)과 같게 본다.
     */
    class PaymentStartModes : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            Binder.get(context.environment).bind("seat.confirm.mode", ConfirmMode::class.java).orElse(SeatConfirmProperties().mode) in
                setOf(ConfirmMode.CONDITIONAL, ConfirmMode.CONDITIONAL_EXTEND)
    }
}

data class PaymentStartResponse(
    val holdId: Long,
    val expiresAt: Instant,
    val payBy: Instant,
    val payWithinSeconds: Long,
    val extended: Boolean,
)
