package com.jun.labs.seatreservation.incremental

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ADR-013 §4.8 N8 · §4.4 '새 코드'(설계 선검증 DS-3) blind 테스트 — INVALID 인덱스로는 증분이 뜨지 않는다 · 거부는 마이그레이션 앞(스키마 무변경).
 * 구현 diff를 보지 않고 계약만으로 썼다(기준 16fb81df). DB 상태 고정(§4.4 '테스트 DB 상태 고정'):
 *   V1~V7 적용 + V9 객체(열·함수·트리거·인덱스) + V9 이력 기록(success) + V10 미적용 + 인덱스 INVALID(superuser가 pg_index.indisvalid = false — 1순위 방법).
 * 기대: 기본 위치 새 앱 기동 거부(메시지에 인덱스 이름) · 그 뒤 V10 테이블 3개 없음 · Flyway 이력에 V10 행 없음 · 카탈로그 무변경 ·
 *       같은 DB에서 인덱스를 지우고 다시 만들면 기동(V10만 적용).
 * 해석:
 *  H9 '기본 위치 새 앱' = 설정 키 없이 뜨는 업무 앱 기본값(application.yml — 위치 V1~V7 + V9 트리거판 + V10 · 증분 trigger 켬)으로 본다
 *     (BootTestBase의 옛 동작 고정 키를 모두 뺀다 — `OLD_DEFAULTS.mapValues { null }`). 명시 위치(incrementalProps trigger)로도 한 번 더 본다.
 *  H10 V9 이력은 Flyway API(같은 jar · 위치 db/migration + db/migration-updated-at-trigger)로 실제 적용해 만든다 — 객체·이력 행이 V9 파일과 정확히 같다.
 *  H11 '카탈로그 무변경' = public 테이블 목록 · flyway_schema_history 행(버전·체크섬·성공) · 인덱스 정의·indisvalid·indisready · 트리거 활성 · 쿼터 열 목록이 같다.
 */
class Adr013InvalidIndexContractTest : BootTestBase() {

    private val index = "idx_user_hold_quota_updated_at"
    private val appDefaults: Map<String, String?> = OLD_DEFAULTS.mapValues { null }

    private fun prepareInvalidIndexDb(): Pair<String, JdbcTemplate> {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        Flyway.configure().dataSource(url, dbUser, dbPassword).locations(LOC_BASE, LOC_V9_TRG).load().migrate()
        val versions = db.queryForList("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL", String::class.java)
        assertTrue("9" in versions && "10" !in versions, "전제: V9 기록 · V10 미적용: $versions")
        assertTrue(db.columnExists("user_hold_quota", "updated_at") && db.triggerExists("user_hold_quota_touch"), "전제: V9 객체")
        val s = db.seedSchedule()
        db.quota(s, 1201, 1); db.hold(s, 1201)
        db.update("UPDATE pg_index SET indisvalid = false WHERE indexrelid = ?::regclass", index)
        assertEquals(false, db.queryForObject("SELECT indisvalid FROM pg_index WHERE indexrelid = ?::regclass", Boolean::class.java, index), "전제: INVALID")
        return url to db
    }

    private fun JdbcTemplate.catalog(): List<Any?> = listOf(
        queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name", String::class.java),
        queryForList("SELECT installed_rank, version, checksum, success FROM flyway_schema_history ORDER BY installed_rank"),
        queryForList(
            "SELECT pg_get_indexdef(i.indexrelid) AS def, i.indisvalid, i.indisready FROM pg_index i WHERE i.indrelid = 'user_hold_quota'::regclass ORDER BY 1",
        ),
        queryForList("SELECT tgname, tgenabled::text FROM pg_trigger WHERE tgrelid = 'user_hold_quota'::regclass AND NOT tgisinternal ORDER BY tgname"),
        queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'user_hold_quota' ORDER BY column_name", String::class.java),
    )

    private fun assertRejectedBeforeMigration(url: String, db: JdbcTemplate, props: Map<String, String?>, why: String) {
        val before = db.catalog()
        val err = bootFailure(url, props)
        assertStartupFailure(err, "$why — INVALID 인덱스 DB", listOf(index))
        V10_TABLES.forEach { t -> assertTrue(!db.tableExists(t), "$why: 거부된 기동이 V10 테이블 $t 를 만들었다(거부가 마이그레이션 뒤 — DS-3 위반)") }
        assertEquals(
            0,
            db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '10'", Int::class.java),
            "$why: Flyway 이력에 V10 행이 생겼다",
        )
        assertEquals(before, db.catalog(), "$why: 거부된 기동이 카탈로그를 바꿨다(H11)")
    }

    @Test
    fun `N8 — V9 기록 · V10 미적용 · INVALID 인덱스 DB에 기본 위치 새 앱 · 마이그레이션 앞에서 거부(메시지에 인덱스 이름) · 스키마 무변경 · 다시 만들면 V10만 적용하고 뜬다`() {
        val (url, db) = prepareInvalidIndexDb()

        assertRejectedBeforeMigration(url, db, appDefaults, "업무 앱 기본값(H9)")
        assertRejectedBeforeMigration(url, db, incrementalProps("trigger", LOCS_TRG), "명시 위치 trigger")

        // 지우고 다시 만들면 뜬다 — V10만 적용
        db.execute("DROP INDEX $index")
        db.execute("CREATE INDEX $index ON user_hold_quota (updated_at)")
        boot(url, appDefaults).use { _ ->
            V10_TABLES.forEach { t -> assertTrue(db.tableExists(t), "다시 만든 뒤 기동 — V10 테이블 $t") }
            val rows = db.queryForList("SELECT version, success FROM flyway_schema_history WHERE version IN ('9', '10') ORDER BY installed_rank")
            assertEquals(listOf("9", "10"), rows.map { it["version"].toString() }, "V9는 다시 돌지 않고 V10만 더해졌다: $rows")
            assertTrue(rows.all { it["success"] == true }, "$rows")
        }
    }
}
