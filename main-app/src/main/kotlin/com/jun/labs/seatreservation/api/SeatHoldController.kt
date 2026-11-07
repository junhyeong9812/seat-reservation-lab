package com.jun.labs.seatreservation.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
// F(reconciler 프로필 — 대조 전용 프로세스)는 업무 요청을 받지 않는다(코드 루프 1 L1-5)
@Profile("!reconciler")
class SeatHoldController(
    private val holdSeatUseCase: HoldSeatUseCase,
    private val payBy: PayBy,
) {

    @PostMapping("/api/schedules/{scheduleId}/seats/{seatId}/hold")
    @ResponseStatus(HttpStatus.CREATED)
    fun hold(
        @PathVariable scheduleId: Long,
        @PathVariable seatId: Long,
        @RequestHeader(USER_ID_HEADER) userId: Long,
    ): HoldSeatResponse {
        val result = holdSeatUseCase.hold(HoldSeatCommand(scheduleId = scheduleId, seatId = seatId, userId = userId))
        // ADR-016 설계-9.3: 결제 마감 시각은 ① · ④에서만 — 그 밖의 모드는 본문 키 3개 그대로(N16-7)
        val deadline = if (payBy.enabled) payBy.of(result.expiresAt) else null
        return HoldSeatResponse(
            holdId = result.holdId, seatId = result.seatId, expiresAt = result.expiresAt,
            payBy = deadline?.payBy, payWithinSeconds = deadline?.payWithinSeconds,
        )
    }
}

/** [payBy] · [payWithinSeconds]는 ① · ④ 모드에서만 있다(없으면 JSON에 키가 없다 — ADR-016 설계-9.3) */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class HoldSeatResponse(
    val holdId: Long,
    val seatId: Long,
    val expiresAt: Instant,
    val payBy: Instant? = null,
    val payWithinSeconds: Long? = null,
)
