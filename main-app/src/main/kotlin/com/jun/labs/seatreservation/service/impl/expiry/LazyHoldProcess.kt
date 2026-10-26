package com.jun.labs.seatreservation.service.impl.expiry

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.SalesCloseGate
import com.jun.labs.seatreservation.service.impl.drift.ExpiryCounters
import com.jun.labs.seatreservation.service.impl.drift.MemoryChangedPairs
import com.jun.labs.seatreservation.service.impl.hold.PessimisticNoWaitHoldStrategy
import com.jun.labs.seatreservation.service.impl.hold.seatLockNotAvailable
import com.jun.labs.seatreservation.service.impl.hold.sqlStateOf
import com.jun.labs.seatreservation.service.impl.limit.limitExceeded
import com.jun.labs.seatreservation.service.impl.limit.upsertQuota
import com.jun.labs.seatreservation.service.impl.limit.upsertSqlFor
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp

/**
 * ADR-014 B·C·D 선점(§설계-3.1 H0~H10) — 기본 조합(좌석 3b + counter-upsert-precheck)에서만 불린다(기동 검사). JDBC 문장만 쓴다:
 * A의 JPA 애그리거트(ProductSeat)는 메모리 안 `status`·앱 Clock 판정이라 A의 의미를 담고 있고, 같은 트랜잭션에서 엔티티와 JDBC 쓰기를 섞으면
 * 낡은 상태를 flush할 수 있다(§3.1 '구현 형태'). A 경로(HoldSeatProcess)는 건드리지 않는다.
 *
 * 잠금 순서: 회차(공유 — H0) → 자기 쿼터 행(H3 · H6) → 자기 만료 홀드 행(H4) → (C만: 회수한 다른 좌석 · SKIP LOCKED — H4b) → 대상 좌석 행(NOWAIT — H7) → 새 홀드 행(H9).
 * 남의 쿼터 행은 잡지 않는다(Q4). 기다리는 잠금은 회차 · 자기 쿼터 행 · 회수 H4가 지우려는 자기 만료 홀드 행 셋뿐이다(DS14-4).
 * 에러 순서 = 마감 → 좌석(H1) → 매수(H6) — A와 같다. 회수는 H1 뒤 · H6 앞(명세 '회수하고 카운터를 내린 뒤 판정' · N4).
 * H3~H10이 한 트랜잭션이라 뒤 단계에서 지면 회수도 함께 롤백된다(규칙 ③ · N3).
 */
@Component
class LazyHoldProcess(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val properties: SeatHoldProperties,
    private val salesClose: SalesCloseGate,
    private val counters: ExpiryCounters,
    private val changes: MemoryChangedPairs,
    private val meters: MeterRegistry,
) {
    private val upsertSql = upsertSqlFor(properties)
    private val ttlSeconds = properties.ttl.toMillis() / 1_000.0

    /**
     * 선점 트랜잭션 하나를 복구 수집 구간(ExpiryCounters 틱 경계 — A·C·D 배치와 같은 장치)으로 감싼다(코드 리뷰 CR14-5): 회수(H3 · H5)가 PairRepair로
     * 복구·기록한 뒤 선점이 뒤에서 지면(H7 55P03 · 매수 초과 · 좌석 불가 등) 복구와 기록이 함께 롤백된다 — 그 쌍을 독립 트랜잭션으로 skipped(reason = SQLSTATE
     * 또는 예외 종류) 기록하고 지표 `seat.hold.counter.drift{source=hold-reclaim, action=skipped}`를 올린다. 커밋되면 복구 결과를 그때 센다.
     */
    fun hold(command: HoldSeatCommand): HoldSeatResult {
        counters.beginTick()
        try {
            val result = try {
                tx.execute { holdInTx(command) }!!
            } catch (e: Exception) {
                counters.afterRollback(e, ROLLBACK_WHAT)
                // H7 NOWAIT의 '진 것'은 55P03뿐 — 데드락(40P01) 등 다른 락 실패는 409로 바꾸지 않고 올린다(A 3b와 같다 — DbLockHoldStrategies.kt).
                // JdbcTemplate은 55P03을 PessimisticLockingFailureException이 아니라 UncategorizedSQLException으로 바꾼다(JPA 경로와 다르다 — 스모크로 확인) — 예외 종류가 아니라 SQLSTATE로 가른다
                if (e is DataAccessException && sqlStateOf(e) == PessimisticNoWaitHoldStrategy.LOCK_NOT_AVAILABLE) seatLockNotAvailable()
                throw e
            }
            counters.afterCommit()
            return result
        } finally {
            counters.endTick()
        }
    }

    private fun holdInTx(command: HoldSeatCommand): HoldSeatResult {
        val s = command.scheduleId
        val u = command.userId
        try {
            salesClose.assertOpenLocked(s) // H0 마감(앱 Clock — 마감은 만료 규칙이 아니다 · §2.1 표 마지막 행)
            assertFree(command) // H1 잠금 없는 선조회 — 걸러내기일 뿐, 최종 판정은 H8
            // H2 회수 탐침(잠금 없음) — 남은 만료 홀드가 없으면(대부분의 요청) H3~H5를 건너뛴다
            val reclaimable = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM seat_hold WHERE schedule_id = ? AND user_id = ? AND expires_at <= now())", Boolean::class.java, s, u,
            ) == true
            if (reclaimable) reclaim(command)
            // H6 매수 판정 = 지금 upsert 그대로 — 0행이면 H1을 다시 보고(그 사이 남이 잡았으면 좌석 불가가 먼저) 매수 초과
            if (!upsertQuota(jdbc, upsertSql, command, properties.maxPerUser)) {
                assertFree(command)
                limitExceeded()
            }
            changes.changed(s, u) // ⓐ 등록(커밋 뒤) — method ≠ memory면 바로 돌아온다
            // H7 좌석 잠금(기다리지 않는다 — 55P03이면 409)
            jdbc.queryForList("SELECT id FROM product_seat WHERE id = ? AND schedule_id = ? FOR UPDATE NOWAIT", command.seatId, s)
            // H8 잠근 뒤 새 문장으로 최종 판정(§2.3) — 잠금과 판정을 한 문장으로 하면 막 커밋된 남의 활성 홀드를 못 본다(D는 좌석 행을 쓰지 않아 EPQ도 못 잡는다)
            assertFree(command)
            // ADR-004 실험 장치 — A와 같은 자리(규칙 검사 뒤 · 상태 전이 직전). 기본 0이면 아무것도 하지 않는다
            if (!properties.criticalSectionDelay.isZero) Thread.sleep(properties.criticalSectionDelay)
            // H9 홀드 = 늘 새 행(새 id — 안전망 최근 창이 id 범위로 본다 · §2.5) · 시각은 DB now()
            val row = jdbc.queryForMap(
                """
                INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at)
                VALUES (?, ?, ?, now(), now() + make_interval(secs => ?)) RETURNING id, expires_at
                """.trimIndent(),
                command.seatId, s, u, ttlSeconds,
            )
            // H10 (B·C만) 힌트 쓰기 — 이미 HELD(남은 거짓 HELD)면 쓰지 않는다. D는 HELD를 쓰지 않는다
            if (properties.expiryMode.storesHeld) {
                jdbc.update("UPDATE product_seat SET status = 'HELD' WHERE id = ? AND status = 'AVAILABLE'", command.seatId)
            }
            return HoldSeatResult(
                holdId = (row["id"] as Number).toLong(), seatId = command.seatId, expiresAt = (row["expires_at"] as Timestamp).toInstant(),
            )
        } finally {
            salesClose.clearDecision() // 판정 시각은 이 선점에서만 쓴다(HoldSeatProcess와 같다)
        }
    }

    /** H1 · H8 — 없는 좌석 404 · 비어 있지 않으면(RESERVED 또는 활성 홀드) 409. 거짓 HELD(B·C)와 자기 만료 홀드는 비어 있음으로 본다 */
    private fun assertFree(command: HoldSeatCommand) {
        val state = jdbc.queryForList(ExpiryRule.SEAT_STATE_SQL, command.seatId, command.scheduleId).singleOrNull()
            ?: throw SeatReservationException(ErrorCode.SEAT_NOT_FOUND)
        if (!ExpiryRule.isFree(state["status"] as String, state["active"] as Boolean)) throw SeatReservationException(ErrorCode.SEAT_NOT_AVAILABLE)
    }

    /** H3~H5(+ C의 H4b) — 그 (회차, 사용자)의 남은 만료 홀드를 지우고 지운 수만큼 cnt를 내린다(규칙 ①~③). 남의 만료 홀드는 지우지 않는다(Q4) */
    private fun reclaim(command: HoldSeatCommand) {
        val s = command.scheduleId
        val u = command.userId
        val sample = Timer.start(meters)
        // H3 쌍 잠금(규칙 ①) — 행이 없으면(MISSING) H4 앞에서 PairRepair로 행을 만들고 잠근다(DS14-8)
        val locked = jdbc.queryForList("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", s, u)
        if (locked.isEmpty()) {
            meters.counter(MISSING_METRIC, "path", PATH_RECLAIM).increment()
            counters.repairMissingBeforeDelete(ExpiryCounters.SOURCE_HOLD_RECLAIM, s, u, emptyList())
        }
        // H4 회수 — 지운 행만 센다(규칙 ②). 주석 표지는 DB 대기 표본(pg_stat_activity.query)에서 회수 문장만 세려는 것(측정-5 M10 · MR-13 — 의미 무변경)
        val reclaimed = jdbc.queryForList(
            "$H4_MARK DELETE FROM seat_hold WHERE schedule_id = ? AND user_id = ? AND expires_at <= now() RETURNING id, seat_id", s, u,
        )
        if (properties.expiryMode == ExpiryMode.LAZY_SWEEP) {
            fixReclaimedSeats(reclaimed.map { (it["seat_id"] as Number).toLong() }.filter { it != command.seatId }.distinct())
        }
        // H5 감소(n = H4 행 수) — 0행이면 복구(§2.4) · 문장은 ExpiryCounters의 방식별 가드 문장(DS14-6)
        counters.decrementDeleted(ExpiryCounters.SOURCE_HOLD_RECLAIM, s, u, reclaimed.size)
        meters.counter(DELETED_METRIC, "path", PATH_RECLAIM).increment(reclaimed.size.toDouble())
        sample.stop(meters.timer(RECLAIM_METRIC))
    }

    /**
     * H4b(C만 · Q6) — 회수한 홀드의 다른 좌석(대상 좌석 제외)의 거짓 HELD를 고친다. 기다리지 않는다(SKIP LOCKED) — 선점·확정 중인 좌석은 건너뛴다
     * (그 좌석은 홀드 행이 없어져 C 배치가 다시 못 고른다 — 판정기 'HELD인데 홀드 행 없음'이 그 몫이다 · §설계-8.3). 되돌리기는 잠근 뒤 새 문장(§2.3).
     */
    private fun fixReclaimedSeats(seatIds: List<Long>) {
        if (seatIds.isEmpty()) return
        val held = seatIds.toTypedArray()
        val lockedSeats = jdbc.queryForList(
            "SELECT id FROM product_seat WHERE id = ANY(?::bigint[]) AND status = 'HELD' FOR UPDATE SKIP LOCKED", Long::class.java, held,
        )
        val heldCount = jdbc.queryForObject("SELECT count(*) FROM product_seat WHERE id = ANY(?::bigint[]) AND status = 'HELD'", Int::class.java, held)!!
        meters.counter(SKIP_LOCKED_METRIC, "stage", "h4b").increment((heldCount - lockedSeats.size).coerceAtLeast(0).toDouble())
        if (lockedSeats.isEmpty()) return
        revertSeats(jdbc, meters, PATH_RECLAIM, lockedSeats)
    }

    companion object {
        /** H4 회수 문장 표지(측정-5 M10 · 리뷰 MR-13) — 배치 문장(C4 · D4 · B2)에는 붙이지 않는다 */
        const val H4_MARK = "/* seatlab:h4-reclaim */"
        const val RECLAIM_METRIC = "seat.hold.reclaim"
        const val DELETED_METRIC = "seat.hold.expiry.deleted"
        const val MISSING_METRIC = "seat.hold.expiry.missing_pairs"
        const val SKIP_LOCKED_METRIC = "seat.hold.expiry.skip_locked"
        const val REVERT_METRIC = "seat.hold.expiry.revert"
        const val PATH_RECLAIM = "reclaim"
        const val PATH_BATCH = "batch"
        /** 롤백 로그의 트랜잭션 이름(ExpiryCounters.afterRollback) */
        private const val ROLLBACK_WHAT = "선점(회수)"
    }
}

/**
 * C의 좌석 상태 되돌리기(H4b · C6 공용 — §2.3 · N6): [locked]는 이 트랜잭션이 이미 잠근 좌석이어야 한다. 잠근 뒤 새 문장으로 활성 홀드가 없는 HELD만 AVAILABLE로.
 * 한 문장(CTE)으로 되돌린 수와 '활성 홀드 때문에 되돌리지 않은 HELD 좌석 수'(되돌림 기회 — P5의 판정 불가 조건)를 함께 센다 —
 * 바깥 SELECT는 문장 시작 스냅숏이라 되돌리기 전 상태를 보지만, 되돌린 좌석은 활성 홀드가 없어 기회 수에 들지 않는다.
 */
internal fun revertSeats(jdbc: JdbcTemplate, meters: MeterRegistry, path: String, locked: List<Long>): Int {
    val ids = locked.toTypedArray()
    val row = jdbc.queryForMap(
        """
        WITH r AS (
          UPDATE product_seat st SET status = 'AVAILABLE'
           WHERE st.id = ANY(?::bigint[]) AND st.status = 'HELD' AND NOT ${ExpiryRule.ACTIVE_ON_SEAT}
          RETURNING st.id)
        SELECT (SELECT count(*) FROM r) AS reverted,
               (SELECT count(*) FROM product_seat st WHERE st.id = ANY(?::bigint[]) AND st.status = 'HELD' AND ${ExpiryRule.ACTIVE_ON_SEAT}) AS kept_active
        """.trimIndent(),
        ids, ids,
    )
    val reverted = (row["reverted"] as Number).toInt()
    meters.counter(LazyHoldProcess.REVERT_METRIC, "path", path, "result", "reverted").increment(reverted.toDouble())
    meters.counter(LazyHoldProcess.REVERT_METRIC, "path", path, "result", "kept_active").increment((row["kept_active"] as Number).toDouble())
    return reverted
}
