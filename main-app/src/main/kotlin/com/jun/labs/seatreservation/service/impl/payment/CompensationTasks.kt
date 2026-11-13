package com.jun.labs.seatreservation.service.impl.payment

import com.jun.labs.seatreservation.domain.CompensationView
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-017 설계-4.1 · 4.4 보상 아웃박스(`compensation_task`). (uid, type)당 1행 — 만들기는 `ON CONFLICT DO NOTHING`(N17-4).
 * 호출자의 트랜잭션 안에서 쓴다(intent 잠금 아래 — N17-5).
 */
@Component
class CompensationTasks(private val jdbc: JdbcTemplate) {

    enum class Type { CLOSE, CAPTURE }
    enum class Source { REJECTION, SWEEP, CONFIRM }

    /** 작업을 만든다(이미 있으면 그대로) — 그 uid · type의 작업을 돌려준다 */
    fun enqueue(uid: String, type: Type, source: Source, now: Instant): CompensationView {
        jdbc.update(
            """
            INSERT INTO compensation_task (uid, type, source, status, attempts, next_attempt_at, created_at)
            VALUES (?, ?, ?, 'PENDING', 0, ?, ?)
            ON CONFLICT (uid, type) DO NOTHING
            """.trimIndent(),
            uid, type.name, source.name, Timestamp.from(now), Timestamp.from(now),
        )
        return find(uid, type)!!
    }

    fun find(uid: String, type: Type): CompensationView? = jdbc.query(
        "SELECT id, type, status FROM compensation_task WHERE uid = ? AND type = ?",
        { rs, _ -> CompensationView(rs.getLong(1), rs.getString(2), rs.getString(3)) }, uid, type.name,
    ).singleOrNull()

    /** 응답 · 조회용 — 보상 쪽 작업(CLOSE)을 먼저, 없으면 CAPTURE */
    fun viewFor(uid: String): CompensationView? = find(uid, Type.CLOSE) ?: find(uid, Type.CAPTURE)
}
