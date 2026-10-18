package com.jun.labs.seatreservation

import com.jun.labs.seatreservation.service.limit.counterMismatch
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals

/**
 * ADR-008 V6 실제 업그레이드 경로(리뷰 루프 1 C5): 새 DB를 Flyway로 V5까지 올리고(ADR-007까지의 스키마), 기본값 전환 전 데이터를 넣은 뒤
 * Flyway로 최신까지 올린다 — V6가 Flyway 트랜잭션 안에서(LOCK TABLE 포함) 실제로 적용되는 경로. 스프링 컨텍스트 없이 Flyway API로만 돈다.
 * V6BackfillMigrationTest(SQL 파일 재실행)와 달리 Flyway가 V6를 '처음' 적용하는 순간을 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V6FlywayUpgradeTest {

    private val postgres = PostgreSQLContainer("postgres:16")
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun start() {
        postgres.start()
        jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @AfterAll
    fun stop() = postgres.stop()

    private fun flyway(target: MigrationVersion): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .target(target)
        .load()

    private fun rows(sql: String): List<Map<String, Any?>> = jdbc.queryForList(sql)

    @Test
    fun `V5까지 올린 DB에 섞인 데이터를 넣고 최신으로 올리면 V6가 성공하고 카운터 = 실제(홀드·예약은 그대로)`() {
        flyway(MigrationVersion.fromVersion("5")).migrate()
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '6'", Int::class.java))

        // 데이터(ADR-007까지 none·quota 시절에 쌓일 수 있는 모양): 회차 1·2, 좌석 40석
        jdbc.update("INSERT INTO product (name) VALUES ('p')")
        jdbc.update("INSERT INTO product_schedule (product_id, starts_at) VALUES (1, now()), (1, now())")
        jdbc.update("INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) SELECT 1 + (i % 2), 'A', 1, i, 'AVAILABLE' FROM generate_series(1, 40) i")
        // 회차 1 사용자 1: 살아 있는 홀드 1 + 만료됐지만 배치가 아직 안 지운 홀드 1 + 확정 1 + 취소 1 → 3(상한 2 초과 — none 시절)
        jdbc.update(
            "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES " +
                "(2, 1, 1, now(), now() + interval '5 min'), (4, 1, 1, now() - interval '1 hour', now() - interval '55 min')",
        )
        jdbc.update(
            "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES " +
                "(1, 6, 1, 'a', 'CONFIRMED', now()), (1, 8, 1, 'b', 'CANCELED', now())",
        )
        // 회차 2 사용자 1: 홀드 1(쿼터 행 없음 → 새로 생겨야) · 회차 2 사용자 2: 취소만 → 실제 0
        jdbc.update("INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (1, 2, 1, now(), now() + interval '5 min')")
        jdbc.update("INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (2, 3, 2, 'c', 'CANCELED', now())")
        // 회차 1 사용자 3: 확정 2
        jdbc.update(
            "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES " +
                "(1, 10, 3, 'd', 'CONFIRMED', now()), (1, 12, 3, 'e', 'CONFIRMED', now())",
        )
        // 어긋난 기존 쿼터 행: (1,1) 너무 높음 7 · (2,2) 실제 0인데 5 · (1,9) 실제 0·cnt 0 · (1,3) quota-lock 시절 0인데 실제 2
        jdbc.update("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (1, 1, 7), (2, 2, 5), (1, 9, 0), (1, 3, 0)")
        val holdsBefore = rows("SELECT * FROM seat_hold ORDER BY id")
        val reservationsBefore = rows("SELECT * FROM reservation ORDER BY id")
        assertEquals(4L, counterMismatch(jdbc), "전제: 백필 전에는 어긋나 있다")

        flyway(MigrationVersion.LATEST).migrate()

        val v6 = flyway(MigrationVersion.LATEST).info().all().single { it.version == MigrationVersion.fromVersion("6") }
        assertEquals(MigrationState.SUCCESS, v6.state, "flyway info: V6")
        assertEquals("backfill user hold quota", v6.description)
        assertEquals(0L, counterMismatch(jdbc), "v_counter_mismatch")
        assertEquals(holdsBefore, rows("SELECT * FROM seat_hold ORDER BY id"), "홀드는 그대로")
        assertEquals(reservationsBefore, rows("SELECT * FROM reservation ORDER BY id"), "예약은 그대로")
        val cnt = rows("SELECT schedule_id, user_id, cnt FROM user_hold_quota ORDER BY 1, 2")
            .associate { (it["schedule_id"] as Number).toLong() to (it["user_id"] as Number).toLong() to (it["cnt"] as Number).toInt() }
        assertEquals(
            mapOf((1L to 1L) to 3, (1L to 3L) to 2, (1L to 9L) to 0, (2L to 1L) to 1, (2L to 2L) to 0),
            cnt,
            "상한 초과 3 유지 · 너무 높은 값·0 시절 값은 실제로 · 실제 0인 행은 0으로 남김 · 없던 쌍은 생김",
        )
    }
}
