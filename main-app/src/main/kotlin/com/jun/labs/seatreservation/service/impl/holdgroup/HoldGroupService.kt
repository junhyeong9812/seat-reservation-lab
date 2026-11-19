package com.jun.labs.seatreservation.service.impl.holdgroup

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.HoldGroupMode
import com.jun.labs.seatreservation.service.HoldGroupProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.SalesCloseGate
import com.jun.labs.seatreservation.service.impl.drift.IncrementalMethod
import com.jun.labs.seatreservation.service.impl.drift.MemoryChangedPairs
import com.jun.labs.seatreservation.service.impl.hold.PessimisticNoWaitHoldStrategy
import com.jun.labs.seatreservation.service.impl.hold.sqlStateOf
import com.jun.labs.seatreservation.service.impl.limit.limitExceeded
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/**
 * ADR-018 설계-2 다석 선점 — 고른 N석 전부 아니면 전무(G1). 트랜잭션 하나:
 * 1. 회차 `FOR SHARE` · 판매 마감(1석과 같다) 2. 잠금 없는 좌석 사전 확인(없음 404 · 선점 불가 409)
 * 3. 카운터 `+N` 한 번(0행이면 좌석을 다시 읽어 선점 불가가 먼저 · 아니면 한도 초과 — counter-upsert-precheck와 같은 순서) · 불일치 탐지 등록
 * 4. 좌석 잡기 — split: id 오름차순 `FOR UPDATE NOWAIT` → 묶음 행 · 홀드 N행 · 좌석 UPDATE / one-shot: 묶음 행 → CTE 한 문장(`SKIP LOCKED` · N행이 아니면 롤백).
 * 어느 단계의 거부든 예외로 트랜잭션 전체가 롤백된다(좌석 · 홀드 · 카운터 · 묶음 행 무변경). 기다리는 좌석 잠금이 없어 교착이 생기지 않는다(G3).
 * 기동 검사가 기본 조합(pessimistic-nowait + counter-upsert-precheck · sweep)만 허용하므로 그 조합의 문장을 그대로 쓴다.
 */
@Service
class HoldGroupService(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val salesClose: SalesCloseGate,
    private val holdProperties: SeatHoldProperties,
    private val groupProperties: HoldGroupProperties,
    private val changes: MemoryChangedPairs,
    private val clock: Clock,
) {
    private val upsertSql = if (holdProperties.counterDrift.reconcile.incremental.method == IncrementalMethod.STMT) UPSERT_N_STMT_SQL else UPSERT_N_SQL

    /** [seatIds]는 호출자가 검사했다(1개 이상 · 한도 이하 · 중복 없음 — 400은 판매 마감보다 먼저) */
    fun hold(scheduleId: Long, userId: Long, seatIds: List<Long>): HoldGroupResult {
        val ids = seatIds.sorted()
        return try {
            tx.execute { holdInTx(scheduleId, userId, ids) }!!
        } catch (e: DataAccessException) {
            // split의 NOWAIT 패배(55P03)만 409 — 교착 등 다른 실패는 그대로 올린다(1석 NOWAIT과 같다).
            // JdbcTemplate는 55P03을 PessimisticLockingFailureException으로 옮기지 않는다(UncategorizedSQLException) — SQLSTATE로 본다
            if (sqlStateOf(e) == PessimisticNoWaitHoldStrategy.LOCK_NOT_AVAILABLE) {
                throw SeatReservationException(ErrorCode.SEAT_NOT_AVAILABLE, lockNotAvailable = true)
            }
            throw e
        } finally {
            salesClose.clearDecision()
        }
    }

    private fun holdInTx(scheduleId: Long, userId: Long, ids: List<Long>): HoldGroupResult {
        val n = ids.size
        val idArray = ids.toTypedArray()
        salesClose.assertOpenLocked(scheduleId)
        assertSeatsAvailable(scheduleId, idArray, n)
        if (jdbc.update(upsertSql, scheduleId, userId, n, holdProperties.maxPerUser) != 1) {
            assertSeatsAvailable(scheduleId, idArray, n) // 사전 확인 뒤 팔렸으면 좌석 불가가 먼저
            limitExceeded()
        }
        changes.changed(scheduleId, userId)
        val now = clock.instant()
        val expiresAt = now.plus(holdProperties.ttl)
        val holds = when (groupProperties.mode) {
            HoldGroupMode.SPLIT -> split(scheduleId, userId, idArray, n, now, expiresAt)
            HoldGroupMode.ONE_SHOT -> oneShot(scheduleId, userId, idArray, n, now, expiresAt)
        }
        return HoldGroupResult(holds.first, expiresAt, holds.second.sortedBy { it.seatId })
    }

    private fun split(scheduleId: Long, userId: Long, ids: Array<Long>, n: Int, now: Instant, expiresAt: Instant): Pair<Long, List<GroupHold>> {
        val statuses = jdbc.query(
            "SELECT id, status FROM product_seat WHERE id = ANY(?::bigint[]) AND schedule_id = ? ORDER BY id FOR UPDATE NOWAIT",
            { rs, _ -> rs.getString(2) }, ids, scheduleId,
        )
        if (statuses.size != n || statuses.any { it != SeatStatus.AVAILABLE.name }) throw SeatReservationException(ErrorCode.SEAT_NOT_AVAILABLE)
        val groupId = insertGroup(scheduleId, userId, n, now)
        val holds = jdbc.query(
            """
            INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at, group_id)
            SELECT s, ?, ?, ?, ?, ? FROM unnest(?::bigint[]) AS s
            RETURNING id, seat_id
            """.trimIndent(),
            HOLD_ROW, scheduleId, userId, Timestamp.from(now), Timestamp.from(expiresAt), groupId, ids,
        )
        val updated = jdbc.update("UPDATE product_seat SET status = 'HELD' WHERE id = ANY(?::bigint[])", ids)
        // 잠근 N행이라 N이어야 한다 — 아니면 조용히 넘기지 않고 롤백(5xx로 드러남)
        check(holds.size == n && updated == n) { "분리형 다석 선점: 홀드 ${holds.size}행 · 좌석 ${updated}행 — 요청 ${n}석(잠근 행 — 설계상 같다)" }
        return groupId to holds
    }

    private fun oneShot(scheduleId: Long, userId: Long, ids: Array<Long>, n: Int, now: Instant, expiresAt: Instant): Pair<Long, List<GroupHold>> {
        val groupId = insertGroup(scheduleId, userId, n, now)
        val holds = jdbc.query(
            """
            WITH picked AS (
                SELECT id FROM product_seat
                WHERE id = ANY(?::bigint[]) AND schedule_id = ? AND status = 'AVAILABLE'
                ORDER BY id FOR UPDATE SKIP LOCKED
            ), held AS (
                UPDATE product_seat s SET status = 'HELD' FROM picked WHERE s.id = picked.id RETURNING s.id
            )
            INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at, group_id)
            SELECT id, ?, ?, ?, ?, ? FROM held
            RETURNING id, seat_id
            """.trimIndent(),
            HOLD_ROW, ids, scheduleId, scheduleId, userId, Timestamp.from(now), Timestamp.from(expiresAt), groupId,
        )
        if (holds.size != n) throw SeatReservationException(ErrorCode.SEAT_NOT_AVAILABLE, groupShort = true) // 롤백 — 일부만 바뀐 좌석 · 홀드도 되돌린다
        return groupId to holds
    }

    private fun insertGroup(scheduleId: Long, userId: Long, n: Int, now: Instant): Long = jdbc.queryForObject(
        "INSERT INTO hold_group (schedule_id, user_id, seat_count, created_at) VALUES (?, ?, ?, ?) RETURNING id",
        Long::class.java, scheduleId, userId, n, Timestamp.from(now),
    )!!

    /** 잠금 없는 좌석 확인 — 하나라도 없거나(다른 회차 포함) 404 · AVAILABLE이 아니면 409 */
    private fun assertSeatsAvailable(scheduleId: Long, ids: Array<Long>, n: Int) {
        val statuses = jdbc.queryForList(
            "SELECT status FROM product_seat WHERE id = ANY(?::bigint[]) AND schedule_id = ?", String::class.java, ids, scheduleId,
        )
        if (statuses.size != n) throw SeatReservationException(ErrorCode.SEAT_NOT_FOUND)
        if (statuses.any { it != SeatStatus.AVAILABLE.name }) throw SeatReservationException(ErrorCode.SEAT_NOT_AVAILABLE)
    }

    companion object {
        /** 카운터 +N 한 문장(1석의 +1 문장은 그대로 — UserLimitStrategies) */
        private val UPSERT_N_SQL = """
            INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, ?)
            ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = user_hold_quota.cnt + EXCLUDED.cnt
            WHERE user_hold_quota.cnt + EXCLUDED.cnt <= ?
        """.trimIndent()
        private val UPSERT_N_STMT_SQL = """
            INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, ?)
            ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = user_hold_quota.cnt + EXCLUDED.cnt, updated_at = now()
            WHERE user_hold_quota.cnt + EXCLUDED.cnt <= ?
        """.trimIndent()
        private val HOLD_ROW = org.springframework.jdbc.core.RowMapper { rs, _ -> GroupHold(rs.getLong(1), rs.getLong(2)) }
    }
}

data class GroupHold(val holdId: Long, val seatId: Long)

/** [holds]는 좌석 id 오름차순 */
data class HoldGroupResult(val groupId: Long, val expiresAt: Instant, val holds: List<GroupHold>)
