package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.service.impl.DbDeadlock
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * ADR-018 2단계 묶음 · 해제 API 결과 지표(모든 프로필) — [ConfirmOutcomeMetrics]와 같은 틀(응답 기준 · 커밋/롤백 무관 · 요청당 정확히 1).
 * - `seat.group.request{op, outcome}`: op = hold · release_group · release_hold · pay_start · confirm.
 *   outcome = 2xx `ok` · 도메인 거부(처리기 표지 그대로 나간 응답) 그 ErrorCode 이름 · 그 밖의 비2xx `status_<코드>`(처리되지 않은 예외는 500).
 * - `seat.db.deadlock{path}`: 처리되지 않은 예외의 사슬에 40P01이 있으면 1(응답은 그대로 — 위 지표에는 그 응답대로 센다).
 * 핸들러를 찾기 전에 끝난 요청(묶음 API를 꺼서 경로 없음 404 등)은 세지 않는다.
 */
@Component
class HoldGroupMetrics(
    private val meters: MeterRegistry,
) : HandlerInterceptor, WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(this)
    }

    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
        val op = (handler as? HandlerMethod)?.let(::opOf) ?: return
        val status = if (ex != null) 500 else response.status
        val code = request.getAttribute(ConfirmOutcomeMetrics.REJECTED_CODE_ATTR) as String?
        val outcome = when {
            status in 200..299 -> OK
            code != null && ex == null && status == request.getAttribute(ConfirmOutcomeMetrics.REJECTED_STATUS_ATTR) -> code
            else -> "status_$status"
        }
        meters.counter(REQUEST_METRIC, "op", op, "outcome", outcome).increment()
        if (ex != null) DbDeadlock.record(meters, DEADLOCK_PATHS.getValue(op), ex)
    }

    private fun opOf(handler: HandlerMethod): String? = when (handler.beanType) {
        HoldGroupController::class.java -> GROUP_OPS[handler.method.name]
        HoldReleaseController::class.java -> if (handler.method.name == "release") OP_RELEASE_HOLD else null
        else -> null
    }

    companion object {
        const val REQUEST_METRIC = "seat.group.request"
        const val OK = "ok"
        const val OP_HOLD = "hold"
        const val OP_RELEASE_HOLD = "release_hold"
        /** [HoldGroupController] 메서드 이름 → op */
        private val GROUP_OPS = mapOf(
            "hold" to OP_HOLD, "release" to "release_group", "paymentStart" to "pay_start", "confirm" to "confirm",
        )
        /** op → `seat.db.deadlock`의 path(해제 둘은 release 하나로) */
        private val DEADLOCK_PATHS = mapOf(
            OP_HOLD to "group_hold", "release_group" to "release", OP_RELEASE_HOLD to "release",
            "pay_start" to "group_pay_start", "confirm" to DbDeadlock.PATH_GROUP_CONFIRM,
        )
    }
}
