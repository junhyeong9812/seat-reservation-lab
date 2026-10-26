package com.jun.labs.seatreservation.incremental

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.drift.DriftTestSupport
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.impl.drift.CounterReconciler
import com.jun.labs.seatreservation.support.hikariBorrows
import com.jun.labs.seatreservation.support.insertReservationRow
import com.jun.labs.seatreservation.support.insertSeatRow
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * 테스트 15 — 새 키 미지정 = 이전과 같음(§3-4 범위 명확화 행 ①동작 ②configprops ③렌더 · §4.2 공통 원칙 ①~④ · §4.2.6 등록 시점).
 * 이 컨텍스트는 새 키를 하나도 주지 않는다(IntegrationTest 고정값 — 증분·안전망 끔 · method none · one-shot · 기본 위치(명세 §10 K10) + loadtest 프로필 — configprops를 보려고).
 * 업무 앱 기본값(application.yml — ADR-012 교체 뒤 증분 켬)은 Adr009DefaultsTest가 본다 — 여기서 '미지정'은 IntegrationTest 고정값이다.
 * ③ 하네스 렌더·계획은 하네스 쪽 비교(run.sh)라 여기서 보지 않는다.
 */
@ActiveProfiles("loadtest")
@AutoConfigureMockMvc
class UnchangedDefaultsTest : DriftTestSupport() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var reconciler: CounterReconciler
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var dataSource: DataSource

    @Test
    fun `스키마 — updated_at 열·인덱스·트리거·V10 테이블이 없다(V9·V10 미적용)`() {
        assertFalse(jdbcTemplate.columnExists("user_hold_quota", "updated_at"))
        assertFalse(jdbcTemplate.triggerExists("user_hold_quota_touch"))
        V10_TABLES.forEach { assertFalse(jdbcTemplate.tableExists(it), "$it 가 있다") }
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IN ('9', '10')", Int::class.java))
    }

    @Test
    fun `전체 대조 C — 바퀴 기록·빌림 수가 지금 정의 그대로(회차 1 + 청크 1 + 범위 1 + 행 없음 2 + 복구 1 + 기록 1 = 7)`() {
        quota(schedule.id!!, 1501, 3); rawHold(schedule.id!!, 1501)    // above
        quota(schedule.id!!, 1502, 2); rawHold(schedule.id!!, 1502)    // 맞음(홀드 1 + 확정 1) — 예약 id 범위가 있어야 행 없음 갈래가 2청크
        jdbcTemplate.insertReservationRow(schedule.id!!, jdbcTemplate.insertSeatRow(schedule.id!!, 7_201), 1502)
        val h0 = meters.hikariBorrows()

        val run = reconciler.runOnce("reconcile-app")

        assertEquals(7L, run.borrows, "$run")
        assertEquals(meters.hikariBorrows() - h0, run.borrows.toDouble())
        val row = jdbcTemplate.queryForMap("SELECT source, schedules, chunks, pairs_scanned, orphan_chunks, found, repaired FROM counter_reconcile_run ORDER BY id DESC LIMIT 1")
        assertEquals(mapOf<String, Any?>("source" to "reconcile-app", "schedules" to 1, "chunks" to 1, "pairs_scanned" to 2L, "orphan_chunks" to 2, "found" to 1, "repaired" to 1), row)
    }

    @Test
    fun `C의 탐지 문장 문자열이 한 글자도 바뀌지 않는다(잠금 대기 중 pg_stat_activity — CHUNK_SQL, 물음표 → 위치 인자)`() {
        quota(schedule.id!!, 1511, 1); rawHold(schedule.id!!, 1511)
        val expected = """
            WITH q AS (
              SELECT user_id, cnt FROM user_hold_quota
              WHERE schedule_id = ? AND user_id > ? ORDER BY user_id LIMIT ?),
            x AS (
              SELECT q.user_id, q.cnt,
                     ((SELECT count(*) FROM seat_hold h WHERE h.user_id = q.user_id AND h.schedule_id = ?)
                    + (SELECT count(*) FROM reservation r WHERE r.schedule_id = ? AND r.user_id = q.user_id AND r.status = 'CONFIRMED'))::int AS actual
              FROM q)
            SELECT (SELECT max(user_id) FROM q) AS last_user, (SELECT count(*) FROM q) AS scanned, d.user_id, d.cnt, d.actual
            FROM (SELECT 1) one LEFT JOIN (SELECT * FROM x WHERE cnt <> actual) d ON true
        """.trimIndent().let { var n = 0; it.replace(Regex("\\?")) { "$" + (++n) } }
        val h = dataSource.unwrap(HikariDataSource::class.java)
        val pool = Executors.newSingleThreadExecutor()
        try {
            DriverManager.getConnection(h.jdbcUrl, h.username, h.password).use { lock ->
                lock.autoCommit = false
                lock.exec("LOCK TABLE seat_hold IN ACCESS EXCLUSIVE MODE")
                val running = pool.submit { reconciler.runOnce("reconcile-app") }
                val w = jdbcTemplate.awaitBlockedBy(lock.pid()).single()
                lock.rollback()
                running.get(30, TimeUnit.SECONDS)
                assertEquals(expected.trim(), w.query.trim(), "기본 경로 탐지 문장이 바뀌었다(§4.2 공통 원칙 ②)")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `선점 쿼터 문장에 updated_at이 없다(꺼짐 = 문자열 그대로)`() {
        val s = schedule.id!!
        holdSeat.hold(HoldSeatCommand(s, jdbcTemplate.insertSeatRow(s, 7_001), 1521))
        val h = dataSource.unwrap(HikariDataSource::class.java)
        val pool = Executors.newSingleThreadExecutor()
        try {
            DriverManager.getConnection(h.jdbcUrl, h.username, h.password).use { lock ->
                lock.autoCommit = false
                lock.exec("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", s, 1521L)
                val running = pool.submit<Any> { holdSeat.hold(HoldSeatCommand(s, jdbcTemplate.insertSeatRow(s, 7_002), 1521)) }
                val w = jdbcTemplate.awaitBlockedBy(lock.pid()).single()
                lock.rollback()
                running.get(30, TimeUnit.SECONDS)
                assertFalse("updated_at" in normalizedSql(w.query), w.query)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `새 지표가 하나도 없고, 증분·안전망 스케줄러가 돌지 않는다`() {
        quota(schedule.id!!, 1531, 2); rawHold(schedule.id!!, 1531)
        reconciler.runOnce("reconcile-app")
        holdSeat.hold(HoldSeatCommand(schedule.id!!, jdbcTemplate.insertSeatRow(schedule.id!!, 7_101), 1532))

        // 스케줄러가 (잘못) 기본 주기 1s · 1m으로 돌고 있다면 이 사이 행이 생긴다
        jdbcTemplate.awaitDbTimeAfter(jdbcTemplate.dbNow().plusMillis(2_500))

        val newMeters = meters.meters.map { it.id.name }.filter { n -> NEW_METRIC_PREFIXES.any { n.startsWith(it) } }
        assertTrue(newMeters.isEmpty(), "손잡이 꺼짐인데 새 지표가 있다: $newMeters")
        assertEquals(
            0,
            jdbcTemplate.queryForObject("SELECT count(*) FROM counter_reconcile_run WHERE source LIKE 'inc-%' OR source LIKE 'safety-%'", Int::class.java),
        )
    }

    @Test
    fun `configprops — 기존 경로(counterDrift_reconcile 6키)는 그대로, 새 키는 incremental·safetyNet 아래로만 더해진다`() {
        val body = mockMvc.get("/actuator/configprops").andReturn().response.contentAsString
        val root: JsonNode = ObjectMapper().readTree(body)
        val seatHold = root.path("contexts").elements().asSequence()
            .flatMap { it.path("beans").elements().asSequence() }
            .single { it.path("prefix").asText() == "seat.hold" }
        val reconcile = seatHold.path("properties").path("counterDrift").path("reconcile")
        val keys = reconcile.fieldNames().asSequence().toSet()
        val old = setOf("enabled", "interval", "lockTimeout", "chunkSize", "orphanChunkSize", "detectShape")
        assertTrue(keys.containsAll(old), "기존 키가 사라졌다: $keys")
        assertTrue((keys - old).all { it in setOf("incremental", "safetyNet") }, "새 키가 정해진 자리 밖에 있다: ${keys - old}")
        assertEquals(1000, reconcile.path("chunkSize").asInt())
        assertEquals(10000, reconcile.path("orphanChunkSize").asInt())
        assertEquals("ONE_SHOT", reconcile.path("detectShape").asText())
        assertEquals(false, reconcile.path("enabled").asBoolean(true), "IntegrationTest 고정값(C 끔)")
    }
}
