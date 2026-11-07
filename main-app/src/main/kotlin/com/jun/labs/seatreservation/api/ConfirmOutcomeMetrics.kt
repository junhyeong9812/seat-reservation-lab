package com.jun.labs.seatreservation.api

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * ADR-016 확정 결과 지표(설계-4 · 9.5 · N16-5) — 확정 엔드포인트(`POST /api/holds/{holdId}/confirm`)의 비2xx 응답을 모든 모드에서 센다(운영에서도 — ADR-017 입력).
 * - `seat.confirm.rejected{code}` = 도메인 거부(SeatReservationException → 404 · 403 · 409). 응답 기준 · 커밋/롤백 무관.
 *   [ApiExceptionHandler]가 요청 속성([REJECTED_CODE_ATTR] · [REJECTED_STATUS_ATTR])으로 표시하고 그 상태 그대로 나간 것만 — 상태 코드로 추측하지 않는다.
 * - `seat.confirm.failed{status}` = 그 밖의 비2xx(본문 결함 400 · 헤더 없음 400 · 415 · 커밋 실패 등 5xx). 처리되지 않은 예외는 500으로 센다
 *   (afterCompletion 시점에는 컨테이너가 아직 상태를 정하지 않았다).
 * 두 지표는 겹치지 않는다. 핸들러를 찾기 전에 끝난 요청(경로 없음 등)은 확정 요청으로 보지 않는다.
 */
@Component
class ConfirmOutcomeMetrics(
    private val meters: MeterRegistry,
) : HandlerInterceptor, WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(this)
    }

    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
        if (handler !is HandlerMethod || handler.beanType != ReservationController::class.java || handler.method.name != CONFIRM_METHOD) return
        val status = if (ex != null) 500 else response.status
        // 표지가 있어도 응답이 처리기가 고른 상태 그대로 나갔을 때만 rejected — 오류 본문을 못 쓴 경우(406 · 500 등)는 failed(겹치지 않음 · N16-5).
        // 상태는 처리기가 표지에 함께 남긴 값이다(ErrorCode → 상태 매핑의 출처는 ApiExceptionHandler 하나)
        val code = request.getAttribute(REJECTED_CODE_ATTR) as String?
        if (code != null && ex == null && status == request.getAttribute(REJECTED_STATUS_ATTR)) {
            meters.counter(REJECTED_METRIC, "code", code).increment()
            return
        }
        if (status !in 200..299) meters.counter(FAILED_METRIC, "status", status.toString()).increment()
    }

    companion object {
        const val REJECTED_METRIC = "seat.confirm.rejected"
        const val FAILED_METRIC = "seat.confirm.failed"
        /** [ApiExceptionHandler]가 도메인 거부 코드 · 고른 응답 상태(Int)를 남기는 요청 속성 */
        const val REJECTED_CODE_ATTR = "seatlab.domainRejectedCode"
        const val REJECTED_STATUS_ATTR = "seatlab.domainRejectedStatus"
        private const val CONFIRM_METHOD = "confirm"
    }
}
