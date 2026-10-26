package com.jun.labs.seatreservation.service.impl.expiry

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.SeatHoldProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** 좌석 맵 한 칸 — status = 보이는 상태(누구의 홀드인지는 내지 않는다) */
data class SeatMapEntry(val seatId: Long, val section: String, val rowNo: Int, val seatNo: Int, val status: SeatStatus)

/**
 * ADR-014 좌석 맵 조회(§설계-3.3 · Q11) — 방식마다 문장 하나(자동 커밋 · 잠금 없음 · `now()` = 문장 시각). 응답은 조회 시점 스냅숏이다(캐시·조회 구조는 ADR-018 몫).
 * - A: 저장 상태 그대로 · B·C: HELD 행만 활성 홀드를 확인(빠른 길 — 거짓 HELD는 AVAILABLE로 보인다. 저장 HELD가 활성 홀드를 빠짐없이 덮는다는 전제 — DS14-2)
 * - D: RESERVED가 아닌 모든 좌석마다 활성 홀드를 확인
 * 회차 없음 = 404(선점의 '회차 없음'과 같은 코드). 좌석이 0석인 회차는 빈 목록 — 회차 확인 문장은 결과가 비었을 때만 보낸다.
 */
@Component
class SeatMapQuery(
    private val jdbc: JdbcTemplate,
    properties: SeatHoldProperties,
) {
    private val sql = when (properties.expiryMode) {
        ExpiryMode.SWEEP -> SWEEP_SQL
        ExpiryMode.LAZY, ExpiryMode.LAZY_SWEEP -> LAZY_SQL
        ExpiryMode.DERIVED -> DERIVED_SQL
    }

    fun seats(scheduleId: Long): List<SeatMapEntry> {
        val seats = jdbc.query(sql, { rs, _ ->
            SeatMapEntry(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), SeatStatus.valueOf(rs.getString(5)))
        }, scheduleId)
        if (seats.isEmpty() && jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM product_schedule WHERE id = ?)", Boolean::class.java, scheduleId) != true) {
            throw SeatReservationException(ErrorCode.SEAT_NOT_FOUND)
        }
        return seats
    }

    private companion object {
        const val SWEEP_SQL = "SELECT id, section, row_no, seat_no, status FROM product_seat WHERE schedule_id = ? ORDER BY id"
        val LAZY_SQL = """
            SELECT st.id, st.section, st.row_no, st.seat_no,
                   CASE WHEN st.status = 'HELD' AND NOT ${ExpiryRule.ACTIVE_ON_SEAT} THEN 'AVAILABLE' ELSE st.status END
            FROM product_seat st WHERE st.schedule_id = ? ORDER BY st.id
        """.trimIndent()
        val DERIVED_SQL = """
            SELECT st.id, st.section, st.row_no, st.seat_no,
                   CASE WHEN st.status = 'RESERVED' THEN 'RESERVED'
                        WHEN ${ExpiryRule.ACTIVE_ON_SEAT} THEN 'HELD'
                        ELSE 'AVAILABLE' END
            FROM product_seat st WHERE st.schedule_id = ? ORDER BY st.id
        """.trimIndent()
    }
}
