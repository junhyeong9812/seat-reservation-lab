package com.jun.labs.seatreservation.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.holdgroup.GroupHold
import com.jun.labs.seatreservation.service.impl.holdgroup.GroupReservation
import com.jun.labs.seatreservation.service.impl.holdgroup.HoldGroupConfirmService
import com.jun.labs.seatreservation.service.impl.holdgroup.HoldGroupPaymentStartService
import com.jun.labs.seatreservation.service.impl.holdgroup.HoldGroupService
import com.jun.labs.seatreservation.service.impl.holdgroup.HoldReleaseService
import com.jun.labs.seatreservation.service.impl.payment.PgRegistration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * ADR-018 묶음 홀드 API(설계-2 · 3 · 4 · 5) — `seat.hold-group.enabled=true`일 때만 등록한다(끄면 네 경로 모두 404 — 설계-7).
 * 키가 없으면 등록한다(matchIfMissing — 코드 기본값 [com.jun.labs.seatreservation.service.HoldGroupProperties.enabled] = true와 같게. ADR-018 §결정 기본 켬).
 * 1석 해제(`DELETE /api/holds/{holdId}`)는 [HoldReleaseController](언제나 열림).
 */
@RestController
@Profile("!reconciler")
@ConditionalOnProperty(name = ["seat.hold-group.enabled"], havingValue = "true", matchIfMissing = true)
class HoldGroupController(
    private val holdGroups: HoldGroupService,
    private val release: HoldReleaseService,
    private val paymentStart: HoldGroupPaymentStartService,
    private val confirm: HoldGroupConfirmService,
    private val pgRegistration: PgRegistration,
    private val holdProperties: SeatHoldProperties,
    private val payBy: PayBy,
    private val mapper: ObjectMapper,
) {
    /** 본문 `{"seatIds": [..]}` — 없음 · 빈 배열 · 중복 · 한도 초과 · 숫자 아님은 400 INVALID_SEAT_SELECTION(판매 마감 검사보다 먼저 — 본문 검사) */
    @PostMapping("/api/schedules/{scheduleId}/hold-groups")
    @ResponseStatus(HttpStatus.CREATED)
    fun hold(
        @PathVariable scheduleId: Long,
        @RequestHeader(USER_ID_HEADER) userId: Long,
        @RequestBody(required = false) body: String?,
    ): HoldGroupResponse {
        val result = holdGroups.hold(scheduleId, userId, seatIds(body))
        val deadline = if (payBy.enabled) payBy.of(result.expiresAt) else null
        return HoldGroupResponse(
            groupId = result.groupId, expiresAt = result.expiresAt,
            payBy = deadline?.payBy, payWithinSeconds = deadline?.payWithinSeconds, holds = result.holds,
        )
    }

    @DeleteMapping("/api/hold-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun release(@PathVariable groupId: Long, @RequestHeader(USER_ID_HEADER) userId: Long) = release.releaseGroup(groupId, userId)

    /** 200 = 1석 결제 시작과 같은 키(holdId 대신 groupId). PG에는 seat_count × seat.payment.amount를 등록한다(커밋 뒤 · 매 호출 — 멱등) */
    @PostMapping("/api/hold-groups/{groupId}/payment-start")
    fun paymentStart(@PathVariable groupId: Long, @RequestHeader(USER_ID_HEADER) userId: Long): HoldGroupPaymentStartResponse {
        val result = paymentStart.paymentStart(groupId, userId)
        pgRegistration.register(result.paymentUid, userId, result.payBy, pgRegistration.amountFor(result.seatCount))
        return HoldGroupPaymentStartResponse(
            groupId = result.groupId, expiresAt = result.expiresAt, payBy = result.payBy,
            payWithinSeconds = payBy.remaining(result.payBy), extended = result.extended, paymentUid = result.paymentUid,
        )
    }

    @PostMapping("/api/hold-groups/{groupId}/confirm")
    fun confirm(
        @PathVariable groupId: Long,
        @RequestHeader(USER_ID_HEADER) userId: Long,
        @RequestBody request: ConfirmReservationRequest,
    ): HoldGroupConfirmResponse =
        HoldGroupConfirmResponse(status = CONFIRMED, reservations = confirm.confirm(groupId, userId, request.paymentUid))

    private fun seatIds(body: String?): List<Long> {
        val node: JsonNode? = body?.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() }
        val array = node?.get("seatIds")?.takeIf { it.isArray } ?: invalid()
        val ids = array.map { if (it.isIntegralNumber && it.canConvertToLong()) it.asLong() else invalid() }
        if (ids.isEmpty() || ids.size > holdProperties.maxPerUser || ids.toSet().size != ids.size) invalid()
        return ids
    }

    private fun invalid(): Nothing = throw SeatReservationException(ErrorCode.INVALID_SEAT_SELECTION)

    companion object {
        const val CONFIRMED = "CONFIRMED"
    }
}

/** [payBy] · [payWithinSeconds]는 1석 선점 응답과 같은 규칙(결제 시작 모드에서만 — 없으면 키가 없다) */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class HoldGroupResponse(
    val groupId: Long,
    val expiresAt: Instant,
    val payBy: Instant? = null,
    val payWithinSeconds: Long? = null,
    /** 좌석 id 오름차순 */
    val holds: List<GroupHold>,
)

data class HoldGroupPaymentStartResponse(
    val groupId: Long,
    val expiresAt: Instant,
    val payBy: Instant,
    val payWithinSeconds: Long,
    val extended: Boolean,
    val paymentUid: String,
)

data class HoldGroupConfirmResponse(
    val status: String,
    /** 좌석 id 오름차순 */
    val reservations: List<GroupReservation>,
)
