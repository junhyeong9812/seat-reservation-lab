package com.jun.labs.seatreservation.service.impl.holdgroup

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.HoldGroupProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-018 묶음 홀드의 스키마 유무와 1석 API 거부(설계-4 · 5 · 8 — 묶음에 속한 홀드를 1석 결제 시작 · 확정에 넘기면 409 HOLD_IN_GROUP).
 * 1석 결제 시작 · 확정의 검사는 `seat.hold-group.enabled=true`일 때만 문장을 보낸다 — 끄면 묶음 홀드가 생기지 않으므로 1석 경로는 지금 그대로다(설계-8).
 * 켰다가 끈 뒤 남은 묶음 홀드는 기동 검사가 WARN으로 알린다([HoldGroupStartupCheck]).
 * 묶음 소속(`group_id`)은 홀드 행을 넣을 때 정해지고 바뀌지 않으므로 잠금 없이 읽어도 된다.
 */
@Component
class HoldGroupGuard(
    private val jdbc: JdbcTemplate,
    private val properties: HoldGroupProperties,
) {
    /** V16 표가 있는가 — 스키마는 기동 중(Flyway) 정해지므로 처음 물을 때 한 번만 본다(V16 앞 스키마 — target — 에서는 group_id 열이 없다) */
    val available: Boolean by lazy {
        jdbc.queryForObject("SELECT to_regclass('hold_group') IS NOT NULL", Boolean::class.java) == true
    }

    /**
     * 요청자의 홀드가 묶음에 속했거나(아직 남은 홀드) 그 홀드의 결제 의도가 묶음 결제 의도면 409 HOLD_IN_GROUP.
     * 남의 홀드 · 없는 홀드는 여기서 거르지 않는다(1석 경로가 지금처럼 403 · 404로 답한다).
     */
    fun rejectIfGrouped(holdId: Long, userId: Long) {
        if (!properties.enabled || !available) return
        val grouped = jdbc.queryForObject(
            """
            SELECT EXISTS (SELECT 1 FROM seat_hold WHERE id = ? AND user_id = ? AND group_id IS NOT NULL)
                OR EXISTS (SELECT 1 FROM payment_intent WHERE hold_id = ? AND user_id = ? AND group_id IS NOT NULL)
            """.trimIndent(),
            Boolean::class.java, holdId, userId, holdId, userId,
        ) == true
        if (grouped) throw SeatReservationException(ErrorCode.HOLD_IN_GROUP)
    }
}
