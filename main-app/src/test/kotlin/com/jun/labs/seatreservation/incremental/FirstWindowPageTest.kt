package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.service.impl.drift.IncrementalSchemaCheck
import com.jun.labs.seatreservation.support.causesOf
import io.micrometer.core.instrument.MeterRegistry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * ADR-013 §4.11(증분 첫 창 · 후보 상한)과 §4.4 N8(INVALID 인덱스 거부)의 구현자 테스트 — blind 테스트(§4.11 ⑤)는 따로 쓴다.
 * 기존 데이터가 있는 DB에 V9를 '막' 적용한 상태가 필요해 Flyway를 테스트가 직접 돌린 뒤(V1~V7 → 행 → V9) 업무 앱을 띄운다(앱 Flyway는 V10만 적용).
 */
class FirstWindowPageTest : BootTestBase() {

    private val metricsPrefix = "seat.hold.counter.reconcile.incremental"

    private fun ConfigurableApplicationContext.tick() = getBean(IncrementalReconciler::class.java).tick(SRC_TRG)
    private fun ConfigurableApplicationContext.meters() = getBean(MeterRegistry::class.java)
    private fun ConfigurableApplicationContext.counter(name: String) = meters().find(name).counter()?.count() ?: 0.0

    private fun ConfigurableApplicationContext.safetyUntil(max: Int = 300, cond: () -> Boolean) {
        val safety = getBean(SafetyNetReconciler::class.java)
        var n = 0
        while (!cond()) {
            check(n++ < max) { "안전망 ${max}회 안에 조건을 만족하지 못했다" }
            safety.sampleOnce(); safety.runOnce()
        }
    }

    private fun migrate(url: String, vararg locations: String) {
        Flyway.configure().dataSource(url, dbUser, dbPassword).locations(*locations).load().migrate()
    }

    private fun raw(url: String) = DriverManager.getConnection(url, dbUser, dbPassword).apply { autoCommit = false }

    private fun JdbcTemplate.bump(s: Long, u: Long) =
        update("UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ?", s, u)

    private fun JdbcTemplate.hasMissing(): Boolean = queryForObject(
        "SELECT atthasmissing FROM pg_attribute WHERE attrelid = 'user_hold_quota'::regclass AND attname = 'updated_at'", Boolean::class.java,
    ) == true

    /** V1~V7 + 쿼터 행(맞는 쌍 [ok]개 · 어긋난 쌍 [bad]개 — cnt 2 · 홀드 1) + V9 트리거판. 반환 = (회차, 맞는 사용자, 어긋난 사용자) */
    private fun seedThenV9(url: String, ok: Int, bad: Int): Triple<Long, List<Long>, List<Long>> {
        migrate(url, LOC_BASE)
        val db = jdbcFor(url)
        val s = db.seedSchedule()
        val oks = (1..ok).map { 5_000L + it }.onEach { u -> db.quota(s, u, 1); db.hold(s, u) }
        val bads = (1..bad).map { 6_000L + it }.onEach { u -> db.quota(s, u, 2); db.hold(s, u) }
        migrate(url, LOC_BASE, LOC_V9_TRG)
        return Triple(s, oks, bads)
    }

    @Test
    fun `가 — V9 앞 기존 행은 첫 창에서 빠진다(후보 = V9 뒤 바뀐 쌍만) · restart 행에 m · 기존 어긋남은 안전망이 고친다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        val (s, oks, bads) = seedThenV9(url, ok = 6, bad = 2)
        assertTrue(db.hasMissing(), "전제: 기존 행이 있는 표에 V9 → atthasmissing = true")

        boot(url, incrementalProps("trigger", LOCS_TRG)).use { ctx ->
            val restart = db.gapRows("restart").last()
            val detail = restart["detail"].toString()
            assertTrue(detail.contains(" m ") && !detail.contains("m 없음"), "restart 행 detail에 m: $restart")
            db.bump(s, oks.first())                                    // V9 뒤 바뀐 쌍 1개(어긋남 — cnt 2 · 홀드 1)
            ctx.tick()
            val first = db.lastRunOf(SRC_TRG)
            assertEquals(1L, first.long("pairs_scanned"), "첫 틱 후보 = V9 뒤 바뀐 쌍만(기존 ${oks.size + bads.size}행 제외): $first")
            assertEquals(1, db.cnt(s, oks.first()))
            bads.forEach { u -> assertTrue(db.logsOf(s, u).none { it.source == SRC_TRG }, "기존 행의 어긋남을 증분이 읽었다: ${db.logsOf(s, u)}") }

            ctx.safetyUntil { bads.all { u -> db.cnt(s, u) == 1 } }
            bads.forEach { u -> assertEquals(listOf(SAFETY_APP), db.logsOf(s, u).filter { it.action == "repaired" }.map { it.source }) }
        }
    }

    @Test
    fun `나 — V9보다 먼저 시작해 V9 뒤에 쿼터 행을 고친 트랜잭션(표시 lt m)은 첫 틱 후보에 든다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        migrate(url, LOC_BASE)
        val s = db.seedSchedule()
        val others = (1..4).map { 7_000L + it }.onEach { u -> db.quota(s, u, 1); db.hold(s, u) }
        val x = 7_100L
        db.quota(s, x, 1); db.hold(s, x)
        raw(url).use { early ->
            early.exec("SELECT 1")                                      // V9 트랜잭션보다 먼저 시작(쿼터 테이블 락 없음)
            val start = early.txNow()
            migrate(url, LOC_BASE, LOC_V9_TRG)
            early.exec("UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ?", s, x)   // 트리거 값 = 시작 시각 < m
            early.commit()
            val m = db.queryForObject(
                "SELECT (attmissingval::text::timestamptz[])[1] FROM pg_attribute WHERE attrelid = 'user_hold_quota'::regclass AND attname = 'updated_at'",
                java.sql.Timestamp::class.java,
            )!!.toInstant()
            assertEquals(start, db.updatedAtOf(s, x), "전제: 표시 = 그 트랜잭션 시작 시각")
            assertTrue(start < m, "전제: 표시 < m ($start · $m)")
        }

        boot(url, incrementalProps("trigger", LOCS_TRG)).use { ctx ->
            ctx.tick()
            val first = db.lastRunOf(SRC_TRG)
            assertEquals(1L, first.long("pairs_scanned"), "첫 틱 후보 = 그 쌍만(값 m인 기존 ${others.size}행 제외): $first")
            assertEquals(1, db.cnt(s, x))
            assertEquals(listOf(SRC_TRG), db.logsOf(s, x).filter { it.action == "repaired" }.map { it.source })
        }
    }

    @Test
    fun `다 — 후보가 page-size보다 많으면 여러 틱에 나눠 다 읽는다 · 유실 0 · page_full · carry_ticks`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        boot(url, incrementalProps("trigger", LOCS_TRG, "$INC.page-size" to "2")).use { ctx ->
            val s = db.seedSchedule()
            val users = (1..5).map { 8_000L + it }.onEach { u -> db.quota(s, u, 2); db.hold(s, u) }   // 어긋난 쌍 5개(INSERT 표시)
            ctx.tick()
            assertEquals(2L, db.lastRunOf(SRC_TRG).long("pairs_scanned"), "틱 한 번 = 페이지 1개(상한 2)")
            var n = 1
            while (users.any { u -> db.cnt(s, u) != 1 }) {
                check(n++ < 20) { "20틱 안에 다 읽지 못했다: ${users.map { db.cnt(s, it) }}" }
                ctx.tick()
            }
            repeat(6) { ctx.tick() }                                       // 이어 읽기를 끝까지(고친 행의 새 표시까지 다시 읽고 빈다)
            users.forEach { u -> assertEquals(listOf(SRC_TRG), db.logsOf(s, u).filter { it.action == "repaired" }.map { it.source }, "쌍 $u 증분이 한 번") }
            assertTrue(db.incRuns(SRC_TRG).all { it.long("pairs_scanned") <= 2 }, "틱마다 후보 ≤ page-size: ${db.incRuns(SRC_TRG).map { it["pairs_scanned"] }}")
            assertTrue(ctx.counter("$metricsPrefix.page_full") >= 2, "page_full 카운터")
            val carry = ctx.meters().find("$metricsPrefix.carry_ticks").summary()
            assertTrue(carry != null && carry.count() >= 1 && carry.max() >= 3, "carry_ticks 분포(이어 읽기 한 번 ≥ 3틱): ${carry?.count()} · ${carry?.max()}")
            assertTrue(db.gapRows("window_clamped").isEmpty(), "${db.gapRows()}")
        }
    }

    @Test
    fun `라 — 이어 읽는 동안 키셋이 지나친 위치보다 앞선 시작 시각으로 늦게 커밋한 트랜잭션은 다 읽은 뒤 창이 다시 읽는다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        boot(url, incrementalProps("trigger", LOCS_TRG, "$INC.page-size" to "2")).use { ctx ->
            val s = db.seedSchedule()
            val x = 9_000L
            val later = (1..3).map { 9_100L + it }
            (later + x).forEach { u -> db.quota(s, u, 1); db.hold(s, u) }
            // 시드 INSERT 표시를 창 밖으로(빈 틱이 나올 때까지)
            var guard = 0
            do {
                ctx.tick(); db.awaitDbTimeAfter(db.dbNow().plusMillis(1_300)); ctx.tick()
                check(guard++ < 10)
            } while (db.lastRunOf(SRC_TRG).long("pairs_scanned") != 0L)

            raw(url).use { tx ->
                tx.exec("UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ?", s, x)   // 표시 = tX(미커밋)
                later.forEach { u -> db.bump(s, u) }                            // 표시 > tX(커밋)
                ctx.tick()                                                      // 페이지 참(2) — o = tX
                assertEquals(2L, db.lastRunOf(SRC_TRG).long("pairs_scanned"))
                tx.commit()                                                     // 키셋 위치보다 앞선 표시로 늦은 커밋
            }
            // 이어 읽기 틱 사이를 1초(MARGIN) 넘게 벌린다 — 다 읽은 틱의 next_k(t − 1초)만 쓰면 x(tX)를 넘는다(최솟값 규칙이 있어야 다시 읽는다)
            val pause = { db.awaitDbTimeAfter(db.dbNow().plusMillis(1_300)) }
            pause(); ctx.tick()                                                 // p 뒤부터 — x는 지나쳤다
            assertTrue(db.logsOf(s, x).isEmpty(), "전제: 이어 읽기 중에는 키셋이 x를 지나쳤다: ${db.logsOf(s, x)}")
            var n = 0
            while (db.cnt(s, x) != 1) {
                check(n++ < 12) { "다 읽은 뒤 창이 x를 다시 읽지 않았다(유실): ${db.incRuns(SRC_TRG).map { it["pairs_scanned"] }}" }
                pause(); ctx.tick()
            }
            assertEquals(listOf(SRC_TRG), db.logsOf(s, x).filter { it.action == "repaired" }.map { it.source })
            assertTrue(db.gapRows("window_clamped").isEmpty(), "max-overlap 안 — 창 상한 기록 없음: ${db.gapRows()}")
        }
    }

    @Test
    fun `마 — atthasmissing = false DB는 제외 없이 기동 · restart 행 'm 없음' · 첫 틱이 기존 행을 읽는다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        val (s, oks, bads) = seedThenV9(url, ok = 3, bad = 1)
        db.execute("VACUUM FULL user_hold_quota")
        assertFalse(db.hasMissing(), "전제: 표 재작성 뒤 atthasmissing = false")

        boot(url, incrementalProps("trigger", LOCS_TRG)).use { ctx ->
            val restart = db.gapRows("restart").last()
            assertTrue(restart["detail"].toString().contains("m 없음"), "$restart")
            ctx.tick()
            assertEquals((oks.size + bads.size).toLong(), db.lastRunOf(SRC_TRG).long("pairs_scanned"), "제외 없음 — 기존 행이 첫 창 안")
            bads.forEach { u -> assertEquals(listOf(SRC_TRG), db.logsOf(s, u).filter { it.action == "repaired" }.map { it.source }) }
        }
    }

    @Test
    fun `N8 — V9 기록 + V10 미적용 + INVALID 인덱스 → 마이그레이션 전 거부 · 스키마 무변경 · 지우고 다시 만들면 V10만 적용해 뜬다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        migrate(url, LOC_BASE, LOC_V9_TRG)                                    // V1~V7 + V9(이력 success) · V10 없음
        db.update("UPDATE pg_index SET indisvalid = false WHERE indexrelid = '${IncrementalSchemaCheck.UPDATED_AT_INDEX}'::regclass")
        fun catalog() = db.queryForList(
            "SELECT c.relname, c.relkind::text, i.indisvalid, i.indisready FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "LEFT JOIN pg_index i ON i.indexrelid = c.oid WHERE n.nspname = 'public' ORDER BY c.relname",
        ) + db.queryForList("SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank") +
            db.queryForList("SELECT tgname::text, tgenabled::text FROM pg_trigger WHERE NOT tgisinternal ORDER BY tgname")
        val before = catalog()

        val error = bootFailure(url, incrementalProps("trigger", LOCS_TRG))
        assertStartupFailure(error, "INVALID 인덱스로 증분이 떴다", listOf(IncrementalSchemaCheck.UPDATED_AT_INDEX))
        assertTrue(causesOf(error!!).any { it.message.orEmpty().contains("INVALID") }, "메시지에 INVALID")
        V10_TABLES.forEach { t -> assertFalse(db.tableExists(t), "거부된 기동이 V10($t)을 적용했다") }
        assertEquals(0, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '10'", Int::class.java))
        assertEquals(before, catalog(), "거부된 기동이 카탈로그를 바꿨다")

        db.execute("DROP INDEX ${IncrementalSchemaCheck.UPDATED_AT_INDEX}")
        db.execute("CREATE INDEX ${IncrementalSchemaCheck.UPDATED_AT_INDEX} ON user_hold_quota (updated_at)")
        boot(url, incrementalProps("trigger", LOCS_TRG)).use { _ ->
            V10_TABLES.forEach { t -> assertTrue(db.tableExists(t), "V10($t)") }
            assertEquals(listOf("10"), db.queryForList("SELECT version FROM flyway_schema_history WHERE installed_rank > (SELECT max(installed_rank) FROM flyway_schema_history WHERE version = '9')", String::class.java))
        }
    }

    @Test
    fun `page-size 0은 기동 거부(양수만)`() {
        val url = freshDbUrl()
        val error = bootFailure(url, incrementalProps("trigger", LOCS_TRG, "$INC.page-size" to "0"))
        assertStartupFailure(error, "page-size 0으로 떴다", listOf("page-size"))
        assertFalse(jdbcFor(url).tableExists("flyway_schema_history"), "설정 거부는 마이그레이션 앞")
    }
}
