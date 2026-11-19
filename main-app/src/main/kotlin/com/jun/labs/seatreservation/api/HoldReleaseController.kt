package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.service.impl.holdgroup.HoldReleaseService
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * ADR-018 설계-3 1석 홀드 해제 `DELETE /api/holds/{holdId}` → 204. 묶음 설정과 무관하게 언제나 열린다(1석에도 쓴다).
 * 없음 404 · 남의 것 403 · 묶음에 속한 홀드 409 HOLD_IN_GROUP · 결제 시작 뒤 409 PAYMENT_STARTED.
 */
@RestController
@Profile("!reconciler")
class HoldReleaseController(private val release: HoldReleaseService) {

    @DeleteMapping("/api/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun release(@PathVariable holdId: Long, @RequestHeader(USER_ID_HEADER) userId: Long) = release.releaseHold(holdId, userId)
}
