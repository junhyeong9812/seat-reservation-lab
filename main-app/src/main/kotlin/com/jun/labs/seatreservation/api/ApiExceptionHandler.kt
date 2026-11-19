package com.jun.labs.seatreservation.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.jun.labs.seatreservation.domain.CompensationView
import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.httpStatusCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.HoldGroupProperties
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategyStartupCheck
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerMapping

const val USER_ID_HEADER = "X-User-Id"

/** ADR-014 측정 전용 선점 409 원인 표지(측정-5 M3 · 리뷰 MR-5) — loadtest 프로필에서만 붙는다 */
const val REJECT_HEADER = "X-Seatlab-Reject"

@RestControllerAdvice
class ApiExceptionHandler(
    environment: Environment,
    private val meters: MeterRegistry,
    holdGroup: HoldGroupProperties,
) {
    /** 묶음 선점 거절 지표의 mode 태그 — 설정값은 기동 뒤 바뀌지 않으므로 그 요청을 처리한 방식이다(split · one_shot) */
    private val groupMode = holdGroup.mode.name.lowercase()
    private val loadtest = HoldStrategyStartupCheck.LOADTEST_PROFILE in environment.activeProfiles

    @ExceptionHandler(SeatReservationException::class)
    fun handle(e: SeatReservationException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        // ADR-016 확정 거부 지표의 표지(ConfirmOutcomeMetrics가 확정 엔드포인트에서만 센다 — N16-5)
        val status = e.errorCode.httpStatus()
        request.setAttribute(ConfirmOutcomeMetrics.REJECTED_CODE_ATTR, e.errorCode.name)
        request.setAttribute(ConfirmOutcomeMetrics.REJECTED_STATUS_ATTR, status.value())
        val response = ResponseEntity.status(status)
        rejectCause(e)?.let { cause ->
            if (isGroupHold(request)) {
                // ADR-018 2단계 — 묶음 선점의 409는 1석 지표 seat.hold.rejected에 섞지 않는다(헤더 값도 group-<cause>)
                val groupCause = if (e.groupShort) GROUP_SHORT else cause
                meters.counter(GROUP_REJECTED_METRIC, "mode", groupMode, "cause", groupCause).increment()
                response.header(REJECT_HEADER, "group-$groupCause")
            } else {
                meters.counter(REJECTED_METRIC, "cause", cause).increment()
                response.header(REJECT_HEADER, cause)
            }
        }
        return response.body(ErrorResponse(code = e.errorCode.name, message = e.errorCode.message, compensation = e.compensation))
    }

    /**
     * 측정 프로필의 선점 409 원인(상태 코드·본문 `{code, message}`는 그대로): nowait = 좌석 행 NOWAIT 패배(55P03) · rule = 그 밖의 선점 거절
     * (저장 HELD · 활성 홀드 · 매수 초과 · 마감). 같은 갈래를 지표 `seat.hold.rejected{cause}`로 센다 — 하네스가 k6 헤더 합과 대조한다.
     * 선점 경로 409 코드에만 붙인다(확정 409 — HOLD_EXPIRED·SEAT_NOT_HELD — 에는 없다). 다른 프로필은 null(헤더·지표 없음).
     */
    private fun rejectCause(e: SeatReservationException): String? = when {
        !loadtest || e.errorCode !in HOLD_REJECT_CODES -> null
        e.lockNotAvailable -> "nowait"
        else -> "rule"
    }

    /** 이 예외가 묶음 선점(`POST /api/schedules/{scheduleId}/hold-groups`)에서 났는가 — 핸들러 매핑이 남긴 처리 메서드로 본다 */
    private fun isGroupHold(request: HttpServletRequest): Boolean =
        (request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) as? HandlerMethod)
            ?.let { it.beanType == HoldGroupController::class.java && it.method.name == GROUP_HOLD_METHOD } == true

    private fun ErrorCode.httpStatus(): HttpStatus = HttpStatus.valueOf(httpStatusCode())

    companion object {
        const val REJECTED_METRIC = "seat.hold.rejected"
        /** ADR-018 2단계 묶음 선점 409 원인 `{mode, cause}` — cause = nowait(split 55P03) · short(one-shot 바뀐 행 < N) · rule(그 밖) */
        const val GROUP_REJECTED_METRIC = "seat.group.hold.rejected"
        private const val GROUP_SHORT = "short"
        private const val GROUP_HOLD_METHOD = "hold"
        /** 선점 경로의 409 코드 — 원인 표지 대상 */
        val HOLD_REJECT_CODES = setOf(ErrorCode.SEAT_NOT_AVAILABLE, ErrorCode.HOLD_LIMIT_EXCEEDED, ErrorCode.SCHEDULE_CLOSED)
    }
}

data class ErrorResponse(
    val code: String,
    val message: String,
    /** ADR-017 설계-4.3 — intent 경로 확정 거부에서 보상 작업이 있을 때만(I11 — 코드는 그대로 · 본문 필드 추가) */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val compensation: CompensationView? = null,
)
