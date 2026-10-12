package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.SeatHoldProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/**
 * ADR-009 판매 마감: 회차의 `starts_at`이 지나면(`now >= starts_at`, 앱 Clock) 선점을 거절한다(`SCHEDULE_CLOSED` 409). 확정은 막지 않는다.
 * 에러 판정 순서 = 마감 → 좌석 → 매수(명세 §2). 회차가 없으면 지금처럼 `SEAT_NOT_FOUND`(404).
 * `seat.hold.sales-close.enabled=false`면 아무 문장도 보내지 않는다(지금 코드 경로와 같다).
 */
@Component
class SalesCloseGate(
    private val jdbc: JdbcTemplate,
    private val properties: SeatHoldProperties,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    val enabled: Boolean get() = properties.salesClose.enabled

    /**
     * 선점 트랜잭션 안의 판정 — 회차 행을 `FOR SHARE`로 읽는다(명세 §10·§11 R1). 정리 ①(회차 행 `FOR UPDATE` 뒤 홀드 0 재확인)과 직렬화된다:
     * 정리가 회차 행을 쥔 동안 온 선점은 기다렸다가 정리 커밋 뒤의 값을 읽고, 진행 중인 선점이 있으면 정리가 그 커밋을 기다린 뒤 홀드를 본다.
     * 같은 트랜잭션의 한 문장이라 커넥션 빌림 수는 그대로다(요청당 1).
     */
    fun assertOpenLocked(scheduleId: Long) {
        if (!enabled) return
        val sample = io.micrometer.core.instrument.Timer.start(meters)
        try {
            val startsAt = jdbc.queryForList(
                "SELECT starts_at FROM product_schedule WHERE id = ? FOR SHARE", Timestamp::class.java, scheduleId,
            ).singleOrNull()
            assertOpen(startsAt)
        } finally {
            sample.stop(meters.timer("seat.hold.sales-close.check", "lock", "share"))
        }
    }

    /**
     * 잠금 없는 판정 — 트랜잭션을 열기 전에 다른 판정(예: Redis 선점 관문의 '진 것')이 먼저 나는 경로에서 순서(마감 → 좌석)를 지키는 데만 쓴다.
     * 그 경로는 홀드를 만들지 않으므로 직렬화가 필요 없다. 자동 커밋 문장 1개(그 경로는 원래 커넥션을 빌리지 않았다 — 빌림 0 → 1).
     */
    fun assertOpenUnlocked(scheduleId: Long) {
        if (!enabled) return
        val startsAt = jdbc.queryForList(
            "SELECT starts_at FROM product_schedule WHERE id = ?", Timestamp::class.java, scheduleId,
        ).singleOrNull() ?: return // 회차 없음은 마감 판정이 아니다 — 그 경로의 원래 판정(좌석)을 그대로 둔다
        if (isClosed(startsAt.toInstant(), clock.instant())) throw SeatReservationException(ErrorCode.SCHEDULE_CLOSED)
    }

    private fun assertOpen(startsAt: Timestamp?) {
        val at = startsAt ?: throw SeatReservationException(ErrorCode.SEAT_NOT_FOUND)
        if (isClosed(at.toInstant(), clock.instant())) throw SeatReservationException(ErrorCode.SCHEDULE_CLOSED)
    }

    companion object {
        /** 마감 = now >= starts_at (시작 정각에는 이미 마감). */
        fun isClosed(startsAt: Instant, now: Instant): Boolean = !now.isBefore(startsAt)
    }
}
