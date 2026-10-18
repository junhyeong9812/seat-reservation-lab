package com.jun.labs.seatreservation.domain.repository

import com.jun.labs.seatreservation.domain.ProductSeat
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import java.time.Instant

interface ProductSeatRepository : JpaRepository<ProductSeat, Long> {
    fun findByIdAndScheduleId(id: Long, scheduleId: Long): ProductSeat?

    fun findByHoldsId(holdId: Long): ProductSeat?

    fun findDistinctByHoldsExpiresAtLessThanEqual(now: Instant): List<ProductSeat>

    /** (회차, 사용자)가 가진 홀드 수 — 좌석·홀드 조인 행 수. */
    fun countByScheduleIdAndHoldsUserId(scheduleId: Long, userId: Long): Long

    // ---- ADR-003 경합 제어 전략 전용 -------------------------------------------------------------

    /** 비관락 — 좌석 행 `SELECT … FOR UPDATE`. 먼저 잡은 트랜잭션이 끝날 때까지 기다린다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ProductSeat s where s.id = :id and s.scheduleId = :scheduleId")
    fun findForUpdate(id: Long, scheduleId: Long): ProductSeat?

    /** 비관락 NOWAIT — 이미 잠겨 있으면 기다리지 않고 실패한다(Hibernate: 락 타임아웃 0 = NOWAIT). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select s from ProductSeat s where s.id = :id and s.scheduleId = :scheduleId")
    fun findForUpdateNoWait(id: Long, scheduleId: Long): ProductSeat?

    /** 조건부 UPDATE — AVAILABLE일 때만 HELD로. 영향 행 수 1 = 이김, 0 = 이미 누군가 가져감. */
    @Modifying
    @Query(
        "UPDATE product_seat SET status = 'HELD' WHERE id = :id AND schedule_id = :scheduleId AND status = 'AVAILABLE'",
        nativeQuery = true,
    )
    fun holdIfAvailable(id: Long, scheduleId: Long): Int

    /** 낙관락 — 버전 열은 엔티티에 매핑하지 않는다(V3 주석). */
    @Query("SELECT version FROM product_seat WHERE id = :id", nativeQuery = true)
    fun findVersion(id: Long): Long?

    /** 낙관락 — 읽은 버전 그대로일 때만 올린다. 0 = 그 사이 누군가 바꿨다. */
    @Modifying
    @Query("UPDATE product_seat SET version = version + 1 WHERE id = :id AND version = :expected", nativeQuery = true)
    fun bumpVersion(id: Long, expected: Long): Int
}
