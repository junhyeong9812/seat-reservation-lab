package com.jun.labs.seatreservation.loadtest

import com.jun.labs.seatreservation.service.SeatHoldProperties
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 부하 측정 도구(ADR-001) — 도메인이 아니다. 판정은 도메인 로직을 재사용하지 않고 DB 행을 직접 센다:
 * 측정 대상이 스스로를 채점하지 않게 하기 위해서다.
 */
@Service
@Profile("loadtest")
class LoadtestDataService(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: SeatHoldProperties,
) {

    /**
     * 결정적 시드로 초기화한다. 회차 k의 n번째 좌석 id = (k - 1) * seatsPerSchedule + n.
     * 좌석은 한 줄 50석으로 배치하고, id가 작을수록 앞자리다(핫스팟 = 앞 20%).
     */
    @Transactional
    fun reset(schedules: Int, seatsPerSchedule: Int): ResetResult {
        require(schedules in 1..1_000 && seatsPerSchedule in 1..100_000) { "시드 범위 초과" }
        jdbcTemplate.execute(
            "TRUNCATE reservation, seat_hold, product_seat, product_schedule, product RESTART IDENTITY",
        )
        jdbcTemplate.update("INSERT INTO product (name) VALUES ('loadtest')")
        jdbcTemplate.update(
            """
            INSERT INTO product_schedule (product_id, starts_at)
            SELECT 1, TIMESTAMPTZ '2026-12-31 19:00:00+09' FROM generate_series(1, ?)
            """.trimIndent(),
            schedules,
        )
        jdbcTemplate.update(
            """
            INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status)
            SELECT sch, 'A', (i - 1) / $SEATS_PER_ROW + 1, (i - 1) % $SEATS_PER_ROW + 1, 'AVAILABLE'
            FROM generate_series(1, ?) sch, generate_series(1, ?) i
            ORDER BY sch, i
            """.trimIndent(),
            schedules, seatsPerSchedule,
        )
        return ResetResult(schedules = schedules, seatsPerSchedule = seatsPerSchedule, seatsPerRow = SEATS_PER_ROW)
    }

    /** 통계를 갱신한다 — 시드 직후 실행 계획이 매 회 같도록. 트랜잭션 밖에서 호출한다. */
    fun analyze() {
        jdbcTemplate.execute("ANALYZE product, product_schedule, product_seat, seat_hold, reservation")
    }

    /**
     * DB 사실 기준 정합성 판정 (ADR-001 판정 기준). 모든 "위반" 항목의 기대값은 0이다.
     * @param graceSeconds 만료 배치 주기를 넘겨 판정하기 위한 유예 — 이보다 오래전에 만료됐는데 HELD면 위반.
     */
    fun consistency(graceSeconds: Long): Map<String, Any?> {
        val limit = properties.maxPerUser
        val result = jdbcTemplate.queryForMap(
            """
            WITH per_user AS (
                SELECT schedule_id, user_id, sum(n) AS total FROM (
                    SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
                    UNION ALL
                    SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2
                ) u GROUP BY 1, 2
            ),
            hold_per_seat AS (SELECT seat_id, count(*) AS c FROM seat_hold GROUP BY seat_id),
            confirmed_per_seat AS (
                SELECT seat_id, count(*) AS c FROM reservation WHERE status = 'CONFIRMED' GROUP BY seat_id
            )
            SELECT
              (SELECT count(*) FROM product_seat)                                   AS seats,
              (SELECT count(*) FROM product_seat WHERE status = 'AVAILABLE')        AS available,
              (SELECT count(*) FROM product_seat WHERE status = 'HELD')             AS held,
              (SELECT count(*) FROM product_seat WHERE status = 'RESERVED')         AS reserved,
              (SELECT count(*) FROM seat_hold)                                      AS hold_rows,
              (SELECT count(*) FROM reservation WHERE status = 'CONFIRMED')         AS confirmed,
              (SELECT count(*) FROM hold_per_seat WHERE c > 1)                      AS v_duplicate_hold_seats,
              (SELECT coalesce(sum(c - 1), 0) FROM hold_per_seat WHERE c > 1)       AS v_excess_hold_rows,
              (SELECT count(*) FROM confirmed_per_seat WHERE c > 1)                 AS v_duplicate_confirmed_seats,
              (SELECT count(*) FROM product_seat s WHERE s.status = 'RESERVED'
                 AND NOT EXISTS (SELECT 1 FROM reservation r
                                 WHERE r.seat_id = s.id AND r.status = 'CONFIRMED'))  AS v_reserved_without_confirmed,
              (SELECT count(*) FROM reservation r JOIN product_seat s ON s.id = r.seat_id
                 WHERE r.status = 'CONFIRMED' AND s.status <> 'RESERVED')             AS v_confirmed_on_unreserved_seat,
              (SELECT count(*) FROM product_seat s WHERE s.status = 'HELD'
                 AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = s.id))    AS v_held_without_hold,
              (SELECT count(DISTINCT h.seat_id) FROM seat_hold h JOIN product_seat s ON s.id = h.seat_id
                 WHERE s.status <> 'HELD')                                            AS v_hold_on_unheld_seat,
              (SELECT count(*) FROM product_seat s WHERE s.status = 'HELD'
                 AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = s.id
                                 AND h.expires_at > now() - make_interval(secs => ?))) AS v_stale_held_seats,
              (SELECT count(*) FROM per_user WHERE total > ?)                        AS v_over_limit_users,
              (SELECT coalesce(max(total), 0) FROM per_user)                         AS max_per_user,
              now()                                                                  AS checked_at
            """.trimIndent(),
            graceSeconds.toDouble(), limit,
        )
        return linkedMapOf<String, Any?>("grace_seconds" to graceSeconds, "max_per_user_limit" to limit) + result
    }

    /** 부하 중 가벼운 폴링용 — 홀드·확정 행 수만 센다 (S4 누적 홀드 수 기록). */
    fun counts(): Map<String, Any?> = jdbcTemplate.queryForMap(
        """
        SELECT (SELECT count(*) FROM seat_hold) AS hold_rows,
               (SELECT count(*) FROM reservation WHERE status = 'CONFIRMED') AS confirmed,
               now() AS checked_at
        """.trimIndent(),
    )

    data class ResetResult(val schedules: Int, val seatsPerSchedule: Int, val seatsPerRow: Int)

    companion object {
        const val SEATS_PER_ROW = 50
    }
}
