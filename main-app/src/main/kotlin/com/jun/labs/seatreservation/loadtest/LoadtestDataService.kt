package com.jun.labs.seatreservation.loadtest

import com.jun.labs.seatreservation.service.CompensationProperties
import com.jun.labs.seatreservation.service.CompensationMode
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Clock

/** 카운터를 유지하는 매수 방식(ADR-005 counter · ADR-006 counter-upsert · ADR-007 precheck · ADR-009 counter-trigger) — 판정기·배경 행 거부 대상 */
private val COUNTER_STRATEGIES = UserLimitStrategyType.COUNTER_FAMILY

/**
 * 부하 측정 도구(ADR-001) — 도메인이 아니다. 판정은 도메인 로직을 재사용하지 않고 DB 행을 직접 센다:
 * 측정 대상이 스스로를 채점하지 않게 하기 위해서다.
 */
@Service
@Profile("loadtest")
class LoadtestDataService(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: SeatHoldProperties,
    private val redis: StringRedisTemplate,
    /** 판정기 v2(ADR-009)의 마감 기준 시각 — 선점의 마감 판정과 같은 출처(앱 Clock) */
    private val clock: Clock,
    /** 측정 전용 앱 경로 주입(ADR-012 §4.2.7 — 같은 loadtest 프로필). 직접 생성(판정기 테스트)할 때는 없다 */
    private val injector: AppPathInjector? = null,
    /** ADR-017 보상 모드 — pay_rejected_without_task는 모드 ≠ none에서만 기대 0이다(직접 생성하면 없다 → 모드 모름 · 항목은 그대로 낸다) */
    private val compensation: CompensationProperties? = null,
) {

    /**
     * 결정적 시드로 초기화한다. 회차 k의 n번째 좌석 id = (k - 1) * seatsPerSchedule + n.
     * 좌석은 한 줄 50석으로 배치하고, id가 작을수록 앞자리다(핫스팟 = 앞 20%).
     */
    @Transactional
    fun reset(schedules: Int, seatsPerSchedule: Int, backgroundRows: Int = 0): ResetResult {
        require(schedules in 1..1_000 && seatsPerSchedule in 1..100_000) { "시드 범위 초과" }
        require(backgroundRows in 0..5_000_000) { "배경 규모 범위 초과" }
        // 배경 홀드·예약은 쿼터 행 없이 넣으므로 counter에서는 v_counter_mismatch가 거짓 위반을 낸다 — 섞지 않는다(ADR-005)
        require(backgroundRows == 0 || properties.limitStrategy !in COUNTER_STRATEGIES) { "counter 매수 방식에서는 배경 행을 시드하지 않는다" }
        // ADR-017 V15 표는 회차 · 좌석 · 예약과 FK로 묶여 함께 비운다(RD17-12 — 회차마다 섞이지 않게). V15 앞 스키마(target 7 등)에는 없다
        val v15 = jdbcTemplate.queryForObject("SELECT to_regclass('payment_intent') IS NOT NULL", Boolean::class.java) == true
        jdbcTemplate.execute(
            "TRUNCATE reservation, seat_hold, product_seat, product_schedule, product, user_hold_quota" +
                (if (v15) ", payment_intent, confirm_rejection, compensation_task" else "") + " RESTART IDENTITY",
        )
        // ADR-012 rv44 RG-1: 초기화 앞(예열)의 앱 경로 주입 기록·상태를 비운다 — 측정의 max-pairs·effect.json에 예열 주입이 섞이지 않게.
        // counter_app_inject는 V10(선택 위치 migration-incremental)에만 있다
        if (jdbcTemplate.queryForObject("SELECT to_regclass('counter_app_inject') IS NOT NULL", Boolean::class.java) == true) {
            jdbcTemplate.execute("TRUNCATE counter_app_inject RESTART IDENTITY")
        }
        // 주입 상태는 초기화가 커밋된 뒤에 비운다(롤백되면 옛 기록·상태가 그대로 맞물린다)
        injector?.let { inj ->
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() = inj.resetState()
                })
            } else {
                inj.resetState()
            }
        }
        // ADR-003 Redis 전략: 선점 관문 키·분산락이 이전 시드의 좌석 id로 남아 있으면 새 시드의 같은 id 좌석을 막는다.
        // Redis는 이 실험 전용(compose 내부)이라 DB 전체를 비운다. Redis 전략이 아니면 Redis에 연결하지 않는다.
        if (properties.strategy == HoldStrategyType.REDIS_NX || properties.strategy == HoldStrategyType.REDIS_LOCK) {
            redis.connectionFactory!!.connection.use { it.serverCommands().flushDb() }
        }
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
        if (backgroundRows > 0) seedBackground(schedules, seatsPerSchedule, backgroundRows)
        return ResetResult(
            schedules = schedules, seatsPerSchedule = seatsPerSchedule, seatsPerRow = SEATS_PER_ROW,
            backgroundRows = backgroundRows,
        )
    }

    /**
     * ADR-002 규모 축: 측정 회차와 다른 배경 회차(id = schedules + 1)에 좌석 2N을 만든다 —
     * 앞 N석은 HELD + 홀드 1개(먼 미래 만료), 뒤 N석은 RESERVED + CONFIRMED 1건. 사용자는 측정 사용자와 겹치지 않는 숫자 대역.
     * 판정 위반 0으로 시작하고, 측정 회차의 동작은 그대로 둔 채 홀드·예약 테이블의 행 수만 늘린다.
     */
    private fun seedBackground(schedules: Int, seatsPerSchedule: Int, n: Int) {
        val bgSchedule = schedules + 1
        val firstSeatId = schedules.toLong() * seatsPerSchedule + 1   // RESTART IDENTITY + 정렬 삽입이라 id가 이어진다
        jdbcTemplate.update(
            "INSERT INTO product_schedule (product_id, starts_at) VALUES (1, TIMESTAMPTZ '2026-12-31 19:00:00+09')",
        )
        jdbcTemplate.update(
            """
            INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status)
            SELECT ?, 'BG', (i - 1) / $SEATS_PER_ROW + 1, (i - 1) % $SEATS_PER_ROW + 1,
                   CASE WHEN i <= ? THEN 'HELD' ELSE 'RESERVED' END
            FROM generate_series(1, ?) i
            ORDER BY i
            """.trimIndent(),
            bgSchedule, n, 2 * n,
        )
        jdbcTemplate.update(
            """
            INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at)
            SELECT ? + i - 1, ?, $BG_HOLD_USER_BASE + i, now(), TIMESTAMPTZ '2099-01-01 00:00:00+00'
            FROM generate_series(1, ?) i
            """.trimIndent(),
            firstSeatId, bgSchedule, n,
        )
        jdbcTemplate.update(
            """
            INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at)
            SELECT ?, ? + ? + i - 1, $BG_RESERVATION_USER_BASE + i, 'bg-' || i, 'CONFIRMED', now()
            FROM generate_series(1, ?) i
            """.trimIndent(),
            bgSchedule, firstSeatId, n, n,
        )
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
        val base = linkedMapOf<String, Any?>("grace_seconds" to graceSeconds, "max_per_user_limit" to limit) + result + expiryStates() + paymentStates()
        // ADR-005 counter: 카운터 = 홀드 + 확정 예약 수여야 한다. 다른 매수 방식은 cnt를 유지하지 않으므로(쿼터 행은 0) 항목 자체를 내지 않는다
        if (properties.limitStrategy !in COUNTER_STRATEGIES) return base
        // ADR-009 판정기 v2(명세 §10): 마감 회차(starts_at <= 앱 Clock now)는 카운터를 쓰지 않는다 — 그 회차의 쌍은 빼고, 뺀 회차·쌍 수를 함께 낸다.
        // 마감 회차가 없으면 값은 v1과 같다. 회차 행이 없는 쌍(쿼터 행만 남은 경우)은 마감으로 보지 않는다 — 빼지 않고 센다(조용히 빠지지 않게)
        val counter = jdbcTemplate.queryForMap(
            """
            WITH closed_s AS (SELECT id FROM product_schedule WHERE starts_at <= ?),
            actual AS (
                SELECT schedule_id, user_id, sum(n) AS total FROM (
                    SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
                    UNION ALL
                    SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2
                ) u GROUP BY 1, 2
            ),
            pairs AS (
                SELECT schedule_id, coalesce(q.cnt, 0) AS cnt, coalesce(a.total, 0) AS total
                FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id)
            )
            SELECT
              (SELECT count(*) FROM pairs WHERE cnt <> total AND schedule_id NOT IN (SELECT id FROM closed_s)) AS v_counter_mismatch,
              (SELECT count(*) FROM closed_s)                                                                AS counter_excluded_schedules,
              -- counter_excluded_pairs = 마감 회차의 (쿼터 행 ∪ 실제 값 쌍) 수. 대조 기록 skipped_pairs(마감 회차의 쿼터 행 수)와 정의가 다르다(L2-9 — 이름은 계약)
              (SELECT count(*) FROM pairs WHERE schedule_id IN (SELECT id FROM closed_s))                     AS counter_excluded_pairs
            """.trimIndent(),
            Timestamp.from(clock.instant()),
        )
        return base + counter
    }

    /**
     * ADR-017 설계-6 결제 · 보상 항목(DB만으로 셀 수 있는 것 — PG 원장 대조는 하네스 요약기 몫). V15 앞 스키마에서는 내지 않는다.
     * 기존 `v_*` 합계 규칙에 섞지 않게 `pay_` 접두어 — 기대 0: pay_multi_reservation_intents(N17-1) · pay_rejection_state_mismatch(N17-2) ·
     * pay_reserved_with_close(N17-5 — 예약이 있는 intent에 CLOSE 작업) · pay_capture_and_close(N17-8) · pay_dead_tasks(모드 ≠ none · 배수 뒤) ·
     * pay_rejected_without_task(모드 ≠ none — 대체 아닌 거부 · 안전망 종결에 CLOSE 작업 없음 · 모드 none이면 작업이 없는 것이 정상이라 이 항목을 빼고 낸다). 보고: 나머지 · pay_compensation_mode.
     */
    private fun paymentStates(): Map<String, Any?> {
        if (jdbcTemplate.queryForObject("SELECT to_regclass('payment_intent') IS NOT NULL", Boolean::class.java) != true) return emptyMap()
        val mode = compensation?.mode
        val states = jdbcTemplate.queryForMap(
            """
            SELECT
              (SELECT count(*) FROM payment_intent)                                                                            AS pay_intents,
              (SELECT count(*) FROM payment_intent WHERE state = 'OPEN')                                                       AS pay_open_intents,
              (SELECT count(*) FROM payment_intent i WHERE i.state = 'OPEN'
                 AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.id = i.hold_id AND h.expires_at > now()))                  AS pay_open_unheld_intents,
              (SELECT count(*) FROM (SELECT intent_id FROM reservation WHERE intent_id IS NOT NULL GROUP BY intent_id HAVING count(*) > 1) x) AS pay_multi_reservation_intents,
              (SELECT count(*) FROM payment_intent i WHERE (i.state IN ('REJECTED', 'SUBSTITUTED')) <>
                 EXISTS (SELECT 1 FROM confirm_rejection r WHERE r.uid = i.uid))                                               AS pay_rejection_state_mismatch,
              (SELECT count(*) FROM payment_intent i WHERE EXISTS (SELECT 1 FROM reservation r WHERE r.intent_id = i.uid)
                 AND EXISTS (SELECT 1 FROM compensation_task t WHERE t.uid = i.uid AND t.type = 'CLOSE'))                      AS pay_reserved_with_close,
              (SELECT count(*) FROM payment_intent i WHERE EXISTS (SELECT 1 FROM compensation_task t WHERE t.uid = i.uid AND t.type = 'CAPTURE')
                 AND EXISTS (SELECT 1 FROM compensation_task t WHERE t.uid = i.uid AND t.type = 'CLOSE'))                      AS pay_capture_and_close,
              (SELECT count(*) FROM payment_intent i WHERE i.state IN ('REJECTED', 'CLOSING')
                 AND NOT EXISTS (SELECT 1 FROM compensation_task t WHERE t.uid = i.uid AND t.type = 'CLOSE'))                  AS pay_rejected_without_task,
              (SELECT count(*) FROM compensation_task WHERE status IN ('PENDING', 'IN_PROGRESS'))                              AS pay_pending_tasks,
              (SELECT count(*) FROM compensation_task WHERE status = 'DEAD')                                                   AS pay_dead_tasks,
              (SELECT count(*) FROM compensation_task WHERE type = 'CAPTURE' AND status = 'DONE' AND last_result LIKE 'FAILED%') AS pay_moneyless_reservations,
              (SELECT count(*) FROM reservation WHERE origin = 'SUBSTITUTE')                                                   AS pay_substitute_reservations
            """.trimIndent(),
        )
        if (mode == CompensationMode.NONE) states.remove("pay_rejected_without_task")
        return linkedMapOf<String, Any?>("pay_compensation_mode" to (mode?.name?.lowercase()?.replace('_', '-') ?: "unknown")) + states
    }

    /**
     * ADR-014 §설계-8.3 방식별 상태 항목 — 활성 = `expires_at > DB now()`(B·C·D의 만료 판정 단일 규칙 · 측정-5 상태 표본과 같은 정의).
     * 기대값이 방식마다 달라(§8.3 표) `v_` 접두어를 붙이지 않는다 — `v_*`는 모든 방식에서 기대값 0인 기존 위반 항목이고, 그 정의는 바꾸지 않는다
     * (A·B·C·D 공통 기대 0인 이중 판매 행도 기존 `v_*` 합계 규칙에 섞지 않게 같은 이름 규칙을 쓴다). 기대값 대조는 하네스(요약기) 몫이다.
     * - double_sold_seats: 좌석당 (활성 홀드 + 확정) ≥ 2(1행 — 모든 방식 0) · multi_active_seats: 좌석당 활성 홀드 > 1(4행의 방식별 정의 — 0)
     * - held_no_active: HELD 저장인데 활성 홀드 없음(5행 — A 묶임 · B·C 거짓 HELD · D 0) · active_not_held: 활성 홀드가 있는데 HELD 아님(7행 — A·B·C 0 · D 제외)
     * - active_on_reserved_seats: 활성 홀드 + RESERVED(상태 표본 `active_on_reserved`와 같은 정의 — 0) · expired_hold_rows: 남은 만료 홀드 행(8행 — B만 > 0)
     */
    private fun expiryStates(): Map<String, Any?> = linkedMapOf<String, Any?>("expiry_mode" to properties.expiryMode.name.lowercase().replace('_', '-')) +
        jdbcTemplate.queryForMap(
            """
            WITH a AS (SELECT seat_id, count(*) AS n FROM seat_hold WHERE expires_at > now() GROUP BY seat_id),
            c AS (SELECT seat_id, count(*) AS n FROM reservation WHERE status = 'CONFIRMED' GROUP BY seat_id)
            SELECT
              (SELECT count(*) FROM a FULL JOIN c USING (seat_id) WHERE coalesce(a.n, 0) + coalesce(c.n, 0) >= 2)       AS double_sold_seats,
              (SELECT count(*) FROM a WHERE n > 1)                                                                      AS multi_active_seats,
              (SELECT count(*) FROM product_seat s WHERE s.status = 'HELD' AND NOT EXISTS (SELECT 1 FROM a WHERE a.seat_id = s.id)) AS held_no_active,
              (SELECT count(*) FROM a JOIN product_seat s ON s.id = a.seat_id WHERE s.status <> 'HELD')                 AS active_not_held,
              (SELECT count(*) FROM a JOIN product_seat s ON s.id = a.seat_id WHERE s.status = 'RESERVED')              AS active_on_reserved_seats,
              (SELECT count(*) FROM seat_hold WHERE expires_at <= now())                                                AS expired_hold_rows
            """.trimIndent(),
        )

    /**
     * 부하 중 가벼운 폴링용 — 전체 판정(consistency)은 조인·반조인이 많아 부하 중 DB와 경합한다(실측: L1에서 58s).
     * 홀드·확정 행 수만 세고, withSeatStatus면 좌석 상태별 개수(좌석 테이블 1회 스캔)를 더한다.
     */
    fun counts(withSeatStatus: Boolean): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        result += jdbcTemplate.queryForMap(
            """
            SELECT (SELECT count(*) FROM seat_hold) AS hold_rows,
                   (SELECT count(*) FROM reservation WHERE status = 'CONFIRMED') AS confirmed,
                   now() AS checked_at
            """.trimIndent(),
        )
        if (withSeatStatus) {
            result += jdbcTemplate.queryForMap(
                """
                SELECT count(*) FILTER (WHERE status = 'AVAILABLE') AS available,
                       count(*) FILTER (WHERE status = 'HELD')      AS held,
                       count(*) FILTER (WHERE status = 'RESERVED')  AS reserved
                FROM product_seat
                """.trimIndent(),
            )
        }
        return result
    }

    data class ResetResult(val schedules: Int, val seatsPerSchedule: Int, val seatsPerRow: Int, val backgroundRows: Int)

    companion object {
        const val SEATS_PER_ROW = 50
        const val BG_HOLD_USER_BASE = 9_000_000_000L
        const val BG_RESERVATION_USER_BASE = 8_000_000_000L
    }
}
