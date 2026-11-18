package com.jun.labs.seatreservation.api

import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Clock

/**
 * ADR-017 설계-4.4 DEAD 복구(운영자 — `ops/compensation-dead/README.md`): `POST /internal/compensations/{id}/retry`.
 * DEAD → PENDING · attempts 0 · 곧바로 기한(200) / DEAD가 아니면 409 / 없으면 404. PG 호출은 uid 멱등이라 다시 돌려도 결과는 하나다(N17-4).
 */
@RestController
@Profile("!reconciler")
class CompensationAdminController(private val jdbc: JdbcTemplate, private val clock: Clock) {
    @PostMapping("/internal/compensations/{id}/retry")
    fun retry(@PathVariable id: Long): ResponseEntity<Void> {
        val updated = jdbc.update(
            "UPDATE compensation_task SET status = 'PENDING', attempts = 0, next_attempt_at = ?, lease_until = NULL, done_at = NULL WHERE id = ? AND status = 'DEAD'",
            Timestamp.from(clock.instant()), id,
        )
        if (updated == 1) return ResponseEntity.ok().build()
        val exists = jdbc.queryForObject("SELECT count(*) FROM compensation_task WHERE id = ?", Int::class.java, id)!! > 0
        return ResponseEntity.status(if (exists) 409 else 404).build()
    }
}
