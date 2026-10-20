package com.jun.labs.seatreservation.incremental

import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * 테스트 9 — V9 있음/없음 · §4.2.2 Flyway 위치 조합표의 모든 행(성공 행 포함) · 기동 검사(§4.2.2 · rv41 H2 · M4 · §4.2.5 · §4.2.7).
 * 실패 단언은 원인 사슬에 BindException이 없고(새 키가 바인딩됐다), 기동 검사 실패는 IllegalStateException 메시지에 막은 대상 이름이 있다(가정 S8).
 * 'Flyway가 막는' 행(두 V9 · 다른 V9로 다시 기동)은 FlywayException을 요구한다.
 * '손잡이 미지정 + V9 없음 = 지금 테스트 전부 그대로'는 기존 테스트 스위트 전체가 그대로 통과하는 것으로 본다(여기서는 스키마만).
 */
class SchemaCombinationTest : BootTestBase() {

    private fun JdbcTemplate.schema() = mapOf(
        "updated_at" to columnExists("user_hold_quota", "updated_at"),
        "index" to (queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_user_hold_quota_updated_at'", Int::class.java)!! > 0),
        "trigger" to triggerExists("user_hold_quota_touch"),
        "V10" to V10_TABLES.all { tableExists(it) },
    )

    private fun bootsWith(props: Map<String, String?>, check: (JdbcTemplate) -> Unit = {}) {
        val url = freshDbUrl()
        boot(url, props).use { }
        check(jdbcFor(url))
    }

    private fun fails(props: Map<String, String?>, why: String, vararg keywords: String) =
        assertStartupFailure(bootFailure(freshDbUrl(), props), why, keywords.toList())

    private fun failsByFlyway(props: Map<String, String?>, why: String) =
        assertStartupFailure(bootFailure(freshDbUrl(), props), why, emptyList(), expectFlyway = true)

    private fun locs(vararg l: String) = (listOf(LOC_BASE) + l).joinToString(",")

    // ── 기본 · noc · M0 · M1 ────────────────────────────────────────────────────────────

    @Test
    fun `기본(새 손잡이 없음) — 성공 · V1~V8 지금 스키마(updated_at·인덱스·트리거·V10 테이블 없음 · V9·V10 미적용)`() {
        bootsWith(emptyMap()) { db ->
            assertEquals(mapOf("updated_at" to false, "index" to false, "trigger" to false, "V10" to false), db.schema())
            assertEquals(0, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IN ('9', '10')", Int::class.java))
        }
    }

    // ── 안전망 단독 ──────────────────────────────────────────────────────────────────

    private val safetyOnly = mapOf<String, String?>(
        "seat.hold.counter-drift.expiry-repair" to "true", "$SAFE.enabled" to "true", "$SAFE.interval" to "1h", "$SAFE.sample-interval" to "1h",
    )

    @Test
    fun `안전망 단독(method none) + V10 — 성공(V10 테이블 3개 · V9 없음)`() {
        bootsWith(safetyOnly + ("spring.flyway.locations" to locs(LOC_V10))) { db ->
            assertEquals(mapOf("updated_at" to false, "index" to false, "trigger" to false, "V10" to true), db.schema())
        }
    }

    @Test
    fun `안전망 단독인데 V10 없음 — 기동 실패`() = fails(safetyOnly, "안전망 + counter_safety_run 없음", "counter_safety_run")

    @Test
    fun `안전망 + 전체 대조 C — 기동 실패(전체 훑기 두 개)`() = fails(
        safetyOnly + mapOf("spring.flyway.locations" to locs(LOC_V10), "seat.hold.counter-drift.reconcile.enabled" to "true"),
        "안전망과 C를 함께", "reconcile", "safety", "안전망",
    )

    // ── M2 ⓐ 메모리 ─────────────────────────────────────────────────────────────────

    @Test
    fun `ⓐ + V10 — 성공 · 문장 위치(V9 열)를 더해도 성공(트리거 없음)`() {
        bootsWith(incrementalProps("memory", locs(LOC_V10))) { db -> assertEquals(false, db.schema()["updated_at"]) }
        bootsWith(incrementalProps("memory", locs(LOC_V9_STMT, LOC_V10))) { db ->
            assertEquals(mapOf("updated_at" to true, "index" to true, "trigger" to false, "V10" to true), db.schema())
        }
    }

    @Test
    fun `ⓐ + 트리거 위치 — 기동 실패(그 밖의 방식이면 트리거가 없어야 한다)`() =
        fails(incrementalProps("memory", locs(LOC_V9_TRG, LOC_V10)), "memory + 트리거", "user_hold_quota_touch", "trigger", "트리거")

    @Test
    fun `ⓐ인데 enabled=false(표시만) — 기동 실패`() =
        fails(incrementalProps("memory", locs(LOC_V10), "$INC.enabled" to "false"), "표시만 하는 메모리 집합", "memory", "enabled")

    @Test
    fun `증분 켬 + A(만료 복구) 끔 — 기동 실패`() =
        fails(incrementalProps("trigger", locs(LOC_V9_TRG, LOC_V10), "seat.hold.counter-drift.expiry-repair" to "false"), "증분은 A가 필수", "expiry-repair", "expiryRepair", "만료")

    // ── M2 ⓑ 트리거 ─────────────────────────────────────────────────────────────────

    @Test
    fun `ⓑ + 트리거 위치 + V10 — 성공(열·인덱스·트리거·V10)`() {
        bootsWith(incrementalProps("trigger", locs(LOC_V9_TRG, LOC_V10))) { db ->
            assertEquals(mapOf("updated_at" to true, "index" to true, "trigger" to true, "V10" to true), db.schema())
        }
    }

    @Test
    fun `ⓑ인데 V9 없음 — 기동 실패`() = fails(incrementalProps("trigger", locs(LOC_V10)), "trigger + updated_at 없음", "updated_at", "user_hold_quota_touch")

    @Test
    fun `ⓑ인데 문장 위치 V9(트리거 없음) — 기동 실패`() =
        fails(incrementalProps("trigger", locs(LOC_V9_STMT, LOC_V10)), "trigger + 트리거 없음", "user_hold_quota_touch", "trigger", "트리거")

    @Test
    fun `ⓑ인데 V10 없음 — 기동 실패`() = fails(incrementalProps("trigger", locs(LOC_V9_TRG)), "trigger + V10 없음", "counter_safety_run")

    // ── M2 ⓒ 문장 ───────────────────────────────────────────────────────────────────

    @Test
    fun `ⓒ + 문장 위치 + V10 — 성공(열·인덱스 · 트리거 없음)`() {
        bootsWith(incrementalProps("stmt", locs(LOC_V9_STMT, LOC_V10))) { db ->
            assertEquals(mapOf("updated_at" to true, "index" to true, "trigger" to false, "V10" to true), db.schema())
        }
    }

    @Test
    fun `ⓒ인데 열이 없다 — 기동 실패`() = fails(incrementalProps("stmt", locs(LOC_V10)), "stmt + updated_at 없음", "updated_at")

    @Test
    fun `ⓒ + 트리거 위치 — 기동 실패`() =
        fails(incrementalProps("stmt", locs(LOC_V9_TRG, LOC_V10)), "stmt + 트리거", "user_hold_quota_touch", "trigger", "트리거")

    @Test
    fun `ⓒ인데 V10 없음 — 기동 실패`() = fails(incrementalProps("stmt", locs(LOC_V9_STMT)), "stmt + V10 없음", "counter_safety_run")

    @Test
    fun `ⓒ + limit-strategy=counter-trigger — 기동 실패(rv41 H2 — V8 quota_add의 UPDATE를 덮지 못한다)`() = fails(
        // B는 A와 함께 금지(기존 검사)이고 증분 틱은 A가 필수라, 다른 검사와 겹치지 않게 '표시만'(M3 업무 앱 모양) 구성으로 본다 —
        // H2 검사는 업무 앱이 한다(§4.2.2 · enabled와 무관). 아래 ⓑ 대조가 같은 구성으로 뜨는 것으로 원인을 가른다
        incrementalProps(
            "stmt", locs(LOC_V8, LOC_V9_STMT, LOC_V10),
            "seat.hold.limit-strategy" to "counter-trigger",
            "seat.hold.counter-drift.expiry-repair" to "false",
            "$INC.enabled" to "false", "$SAFE.enabled" to "false",
            "seat.hold.counter-drift.external-reconciler" to "true",
        ),
        "stmt + counter-trigger", "counter-trigger", "COUNTER_TRIGGER", "stmt",
    )

    @Test
    fun `대조 — ⓑ + counter-trigger는 허용 조합이다(H2는 ⓒ만 막는다)`() {
        // counter-trigger(B)는 A와 함께 켤 수 없고(ADR-009 기동 검사), 증분은 A가 필수다 — 그래서 '증분 켬 + B'는 증분 틱 프로세스가 아닌 표시만(enabled=false) 구성으로 본다
        bootsWith(
            incrementalProps(
                "trigger", locs(LOC_V8, LOC_V9_TRG, LOC_V10),
                "seat.hold.limit-strategy" to "counter-trigger",
                "seat.hold.counter-drift.expiry-repair" to "false",
                "$INC.enabled" to "false", "$SAFE.enabled" to "false",
                "seat.hold.counter-drift.external-reconciler" to "true",
            ),
        )
    }

    @Test
    fun `method=none + 트리거 위치 — 기동 실패(트리거 비용이 다른 셀에 새지 않게)`() =
        fails(mapOf("spring.flyway.locations" to locs(LOC_V9_TRG)), "none + 트리거", "user_hold_quota_touch", "trigger", "트리거")

    // ── M3 업무 앱(표시만) ───────────────────────────────────────────────────────────────

    @Test
    fun `M3 업무 앱 — ⓑ·ⓒ 표시만(enabled=false · 안전망 끔 · external-reconciler) — 같은 위치로 성공`() {
        listOf("trigger" to locs(LOC_V9_TRG, LOC_V10), "stmt" to locs(LOC_V9_STMT, LOC_V10)).forEach { (m, l) ->
            bootsWith(
                incrementalProps(m, l, "$INC.enabled" to "false", "$SAFE.enabled" to "false", "seat.hold.counter-drift.external-reconciler" to "true"),
            )
        }
    }

    // ── Flyway가 막는 행 ───────────────────────────────────────────────────────────────

    @Test
    fun `두 V9 위치를 함께 — 기동 실패(같은 버전 V9 두 개)`() =
        failsByFlyway(incrementalProps("trigger", locs(LOC_V9_TRG, LOC_V9_STMT, LOC_V10)), "V9 두 개")

    @Test
    fun `V9를 적용한 DB를 다른 V9 위치로 다시 기동 — 기동 실패(체크섬 검증)`() {
        val url = freshDbUrl()
        boot(url, incrementalProps("trigger", locs(LOC_V9_TRG, LOC_V10))).use { }
        assertStartupFailure(bootFailure(url, incrementalProps("stmt", locs(LOC_V9_STMT, LOC_V10))), "다른 V9로 다시 기동", emptyList(), expectFlyway = true)
    }

    // ── 두 위치의 열·인덱스 정의가 같다(카탈로그 비교) ───────────────────────────────────────────

    private fun JdbcTemplate.v9Definition(): Map<String, Any?> = mapOf(
        "column" to queryForMap(
            "SELECT data_type, is_nullable, column_default FROM information_schema.columns WHERE table_name = 'user_hold_quota' AND column_name = 'updated_at'",
        ),
        "index" to queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_user_hold_quota_updated_at'", String::class.java),
    )

    @Test
    fun `두 V9 위치의 열·인덱스 정의가 같다(timestamptz NOT NULL DEFAULT now() · B-tree)`() {
        val trgUrl = freshDbUrl()
        val stmtUrl = freshDbUrl()
        boot(trgUrl, incrementalProps("trigger", locs(LOC_V9_TRG, LOC_V10))).use { }
        boot(stmtUrl, incrementalProps("stmt", locs(LOC_V9_STMT, LOC_V10))).use { }
        val trg = jdbcFor(trgUrl).v9Definition()
        val stmt = jdbcFor(stmtUrl).v9Definition()
        assertEquals(trg, stmt, "두 V9의 열·인덱스 정의가 다르다")
        @Suppress("UNCHECKED_CAST") val col = trg["column"] as Map<String, Any?>
        assertEquals("timestamp with time zone", col["data_type"])
        assertEquals("NO", col["is_nullable"])
        assertEquals("now()", col["column_default"])
        assertTrue(trg["index"].toString().contains("USING btree (updated_at)"), "${trg["index"]}")
        assertTrue(jdbcFor(trgUrl).triggerExists("user_hold_quota_touch"))
        assertFalse(jdbcFor(stmtUrl).triggerExists("user_hold_quota_touch"))
    }

    // ── 앱 경로 주입 손잡이(§4.2.7) ─────────────────────────────────────────────────────────

    @Test
    fun `inject-app 켬 + loadtest 프로필 없음 — 기동 실패`() =
        fails(mapOf("$INJECT.enabled" to "true"), "주입은 loadtest 프로필 전용", "loadtest", "inject")

    @Test
    fun `inject-app 켬 + loadtest 프로필 — 성공`() =
        bootsWith(incrementalProps("trigger", locs(LOC_V9_TRG, LOC_V10), "$INJECT.enabled" to "true", "spring.profiles.active" to "loadtest"))
}

/** rv41 M4 — 준비된 트랜잭션이 켜진 DB(max_prepared_transactions > 0)에서는 ⓑⓒ 틱 프로세스가 뜨지 않는다. 표시만(enabled=false)은 뜬다(대조). */
class PreparedTransactionsStartupTest : BootTestBase() {

    override fun container(): PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16").withCommand("postgres", "-c", "max_prepared_transactions=5")

    private fun locs(vararg l: String) = (listOf(LOC_BASE) + l).joinToString(",")

    @Test
    fun `ⓑ·ⓒ 틱 프로세스 + max_prepared_transactions 5 — 기동 실패`() {
        listOf("trigger" to locs(LOC_V9_TRG, LOC_V10), "stmt" to locs(LOC_V9_STMT, LOC_V10)).forEach { (m, l) ->
            assertStartupFailure(bootFailure(freshDbUrl(), incrementalProps(m, l)), "$m + 준비된 트랜잭션 허용 DB", listOf("max_prepared_transactions"))
        }
    }

    @Test
    fun `대조 — 같은 DB에서 표시만(enabled=false · 안전망 끔)은 뜬다 · ⓐ도 뜬다(검사 대상은 ⓑⓒ 틱 프로세스)`() {
        boot(freshDbUrl(), incrementalProps("trigger", locs(LOC_V9_TRG, LOC_V10), "$INC.enabled" to "false", "$SAFE.enabled" to "false")).use { }
        boot(freshDbUrl(), incrementalProps("memory", locs(LOC_V10))).use { }
    }
}
