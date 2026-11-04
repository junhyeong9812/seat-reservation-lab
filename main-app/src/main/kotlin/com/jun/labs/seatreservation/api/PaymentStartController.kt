package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.service.ConfirmMode
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
 * ADR-015 ④ `POST /api/holds/{holdId}/payment-start`(§3.4) — 클라이언트가 사용자를 결제 화면으로 보내기 직전에 부른다(첫 호출만 홀드를 연장).
 * `seat.confirm.mode=conditional-extend`에서만 등록한다 — 그 밖의 모드에는 핸들러가 없다(404). reconciler 프로필은 업무 요청을 받지 않는다(지금 컨트롤러와 같다).
 */
@RestController
@Profile("!reconciler")
@Conditional(PaymentStartController.ExtendModeOnly::class)
class PaymentStartController(
    private val paymentStartService: PaymentStartService,
) {

    @PostMapping("/api/holds/{holdId}/payment-start")
    fun paymentStart(
        @PathVariable holdId: Long,
        @RequestHeader(USER_ID_HEADER) userId: Long,
    ): PaymentStartResponse {
        val result = paymentStartService.paymentStart(holdId, userId)
        return PaymentStartResponse(holdId = result.holdId, expiresAt = result.expiresAt)
    }

    /** `seat.confirm.mode`를 SeatConfirmProperties와 같은 규칙(relaxed enum 바인딩)으로 읽는다 — 키 표기(`conditional-extend` · `CONDITIONAL_EXTEND`)와 무관하게 같은 판정 */
    class ExtendModeOnly : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            Binder.get(context.environment).bind("seat.confirm.mode", ConfirmMode::class.java).orElse(ConfirmMode.CURRENT) == ConfirmMode.CONDITIONAL_EXTEND
    }
}

data class PaymentStartResponse(
    val holdId: Long,
    val expiresAt: Instant,
)
