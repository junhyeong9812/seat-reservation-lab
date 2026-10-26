package com.jun.labs.seatreservation.incremental

import io.micrometer.core.instrument.MeterRegistry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * ADR-013 §4.11(설계 선검증 DS-1 — 증분 첫 창과 후보 상한) · §4.8 N11·N12 blind 테스트. 구현 diff를 보지 않고 계약만으로 썼다(기준 16fb81df).
 * 계약 표면(16fb81df 코드에서 가져온 것): 손잡이 접두어 `seat.hold.counter-drift.reconcile.incremental` · 기록 테이블 counter_reconcile_run(pairs_scanned)·
 * counter_inc_gap(kind restart·window_clamped · from_at·to_at·detail) · counter_drift_log(source·action) · 지표 접두어 `seat.hold.counter.reconcile.incremental`
 * · V9 트리거판(ADD COLUMN updated_at … DEFAULT now() · 트리거 user_hold_quota_touch).
 * 판정은 DB 행·지표·기동 로그만 본다(구현 코드를 재사용하지 않는다).
 *
 * 경우(과제 지시 (가)~(마) — ADR §4.11 ⑤ blind 테스트 목록과의 대응):
 *  (가) 기존 행이 있는 V7 DB에 V9 적용 직후 기동 → 첫 틱 후보 = V9 뒤 바뀐 쌍만 · 기존 어긋남은 안전망(safety-app)이 고친다       — ⑤ (가) · N11
 *  (나) V9 앞에 시작해 V9 뒤에 커밋한 변경(표시 < m · 시작 ≥ 첫 하한)은 첫 창에 든다                                       — ⑤ (나) · N11
 *  (다) 후보 > page-size → 여러 틱에 나눠 다 읽고 유실 0 · page_full · carry_ticks                                          — ⑤ (다) · N12
 *  (다-NF1) 이어 읽는 동안 시작·커밋한 짧은 트랜잭션(키셋이 지나친 위치보다 앞선 표시) · 이어 읽기 > max-overlap → 다 읽은 뒤 창이 다시 읽는다 — ⑤ (라) · §4.11 ② NF-1 · N12
 *  (라) 이어 읽는 동안 겹침 상한(max-overlap)을 넘긴 트랜잭션 → window_clamped 행·지표(무음 아님)                              — §4.11 ② 겹침 상한 · N12
 *  (마) attmissingval 없음(VACUUM FULL 뒤) → 제외 없이 지금 문장 그대로 · 기동 WARN · restart detail 'm 없음'               — ⑤ (마) · §4.11 ① 출처가 없을 때
 *  (+) page-size 0 → 기동 검사 거부(§4.11 ② '기동 검사는 양수만 본다')
 *
 * 해석(계약이 모양을 정하지 않은 곳 — 보고에 같은 목록):
 *  H1 restart 행 detail의 m 표기 형식은 정해지지 않았다 → m의 날짜(yyyy-MM-dd · UTC 또는 JVM 기본 시간대)가 detail에 있으면 'm을 더했다'로 본다.
 *  H2 'm 없음'은 계약 문자열 그대로 detail에 있어야 한다. 기동 WARN 문구는 정해지지 않았다 → WARN 줄에 'm 없음'·attmissingval·atthasmissing·'빠른 기본값' 중 하나.
 *  H3 page_full = '찬 페이지 수'. 마지막 찬 페이지 뒤에 남은 행이 없을 때 그 페이지를 셀지(LIMIT n vs n+1 엿보기)는 구현 몫 → 하한만 단언(≥ 찬 페이지 수 − 1).
 *  H4 carry_ticks = '이어 읽기 한 번이 이어진 틱 수 — 끝날 때 분포' → Micrometer DistributionSummary로 보고 기록 1회 이상 · 최대 ≥ 2만 단언.
 *  H5 page-size 0 거부는 다른 기동 검사와 같이 IllegalStateException + 메시지에 키 이름(page-size)(ADR-012 가정 S8). 바인딩 검증(BindException)이면 이 테스트가 실패한다.
 *  H6 첫 틱 '후보 수'는 counter_reconcile_run.pairs_scanned(§4.11 ② '틱 기록 pairs_scanned는 그대로 그 틱 후보 수')로 본다.
 *  H7 (다-NF1)·(라)는 페이지 경계 위치를 쌍 복구 결과로 확인한다(전제 단언) — 페이지 순서 = (updated_at, schedule_id, user_id)(§4.11 ②) · 복구 UPDATE도 트리거 표시를 남겨 다시 후보가 된다.
 *  H8 restart 행 to_at = 첫 하한(§4.11 ① '기동 기록') — (가)(나)의 전제(첫 하한 ≤ m · ≤ T 시작)를 이 값으로 확인한다.
 */
@ExtendWith(OutputCaptureExtension::class)
class Adr013FirstWindowContractTest : BootTestBase() {

    private val pageSizeKey = "$INC.page-size"
    private val pageFullMetric = "seat.hold.counter.reconcile.incremental.page_full"
    private val carryTicksMetric = "seat.hold.counter.reconcile.incremental.carry_ticks"

    // ── 도구 ────────────────────────────────────────────────────────────────────────────

    private fun ConfigurableApplicationContext.tick() {
        getBean(IncrementalReconciler::class.java).tick(SRC_TRG)
    }

    private fun ConfigurableApplicationContext.safetyUntil(max: Int = 300, cond: () -> Boolean) {
        val safety = getBean(SafetyNetReconciler::class.java)
        var n = 0
        while (!cond()) {
            check(n++ < max) { "안전망 ${max}회 안에 조건을 만족하지 못했다" }
            safety.sampleOnce(); safety.runOnce()
        }
    }

    private fun ConfigurableApplicationContext.meters(): MeterRegistry = getBean(MeterRegistry::class.java)

    /** V1~V7만 적용(옛 앱 DB — updated_at·트리거·V10 없음) */
    private fun migrateV7(url: String) {
        Flyway.configure().dataSource(url, dbUser, dbPassword).locations(LOC_BASE).load().migrate()
    }

    /** 빠른 기본값 m(`pg_attribute.attmissingval` — atthasmissing일 때만) */
    private fun JdbcTemplate.missingM(): Instant? = queryForList(
        "SELECT (attmissingval::text::timestamptz[])[1] FROM pg_attribute " +
            "WHERE attrelid = 'user_hold_quota'::regclass AND attname = 'updated_at' AND atthasmissing",
        Timestamp::class.java,
    ).singleOrNull()?.toInstant()

    private fun rawConn(url: String): Connection = DriverManager.getConnection(url, dbUser, dbPassword).apply { autoCommit = false }

    /** 한 트랜잭션으로 쿼터 행 여러 개(같은 updated_at 묶음) */
    private fun insertQuotasInOneTx(url: String, scheduleId: Long, users: List<Long>, cnt: Int): Instant = rawConn(url).use { c ->
        val at = c.txNow()
        users.forEach { u -> c.exec("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, ?)", scheduleId, u, cnt) }
        c.commit()
        at
    }

    private fun JdbcTemplate.repairSources(s: Long, u: Long): List<String> =
        logsOf(s, u).filter { it.action == "repaired" }.map { it.source }

    private fun JdbcTemplate.firstRun(): Map<String, Any?> = incRuns(SRC_TRG).firstOrNull() ?: fail("증분 틱 행(source $SRC_TRG)이 없다")

    private fun dateForms(t: Instant): List<String> =
        listOf(t.atZone(ZoneOffset.UTC).toLocalDate().toString(), t.atZone(ZoneId.systemDefault()).toLocalDate().toString()).distinct()

    // ── (가) ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `가 — 기존 행이 있는 V7 DB에 V9 적용 직후 기동 · 첫 틱 후보는 V9 뒤 바뀐 쌍만(기존 행 m 제외) · 기존 어긋남은 안전망이 고친다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        migrateV7(url)
        val s = db.seedSchedule()
        // 기존 5쌍(V9 앞): 601·602 어긋남(cnt 1 · 실제 0) · 603~605 일치(cnt 1 · 홀드 1)
        db.quota(s, 601, 1); db.quota(s, 602, 1)
        (603L..605L).forEach { u -> db.quota(s, u, 1); db.hold(s, u) }

        boot(url, incrementalProps("trigger", LOCS_TRG)).use { ctx ->
            val m = db.missingM() ?: fail("전제: 기존 행이 있는 표에 V9를 적용했으니 attmissingval이 있어야 한다")
            (601L..605L).forEach { u -> assertEquals(m, db.updatedAtOf(s, u), "전제: 기존 행의 updated_at = m (쌍 $u)") }
            val restart = db.gapRows("restart").lastOrNull() ?: fail("기동 때 restart 행이 없다")
            val firstLower = assertNotNull(restart.instant("to_at"), "restart 행 to_at = 첫 하한: $restart")
            assertTrue(firstLower <= m, "전제: V9가 기동 start-lookback 안 — 첫 하한 $firstLower ≤ m $m")

            // V9 뒤 변경 2개: 기존 행 603을 고침(표시 > m · 어긋남 above) + 새 쌍 606(어긋남)
            db.update("UPDATE user_hold_quota SET cnt = 9 WHERE schedule_id = ? AND user_id = ?", s, 603L)
            db.quota(s, 606, 1)
            assertTrue(db.updatedAtOf(s, 603)!! > m, "전제: V9 뒤 고친 기존 행의 표시 > m")

            ctx.tick()

            assertEquals(2L, db.firstRun().long("pairs_scanned"), "N11: 첫 틱 후보 = V9 뒤 바뀐 쌍 2개(값 m인 기존 5행은 읽지 않는다): ${db.firstRun()}")
            assertEquals(listOf(SRC_TRG), db.repairSources(s, 603), "V9 뒤 고친 기존 행은 첫 틱이 고친다")
            assertEquals(listOf(SRC_TRG), db.repairSources(s, 606), "V9 뒤 새 쌍은 첫 틱이 고친다")
            assertTrue(db.repairSources(s, 601).isEmpty() && db.repairSources(s, 602).isEmpty(),
                "기존 행(값 m)의 어긋남은 증분 보장 밖 — 첫 틱이 고치면 안 된다: ${db.logsOf(s, 601) + db.logsOf(s, 602)}")

            // H1: restart 행 detail에 m
            val detail = restart["detail"]?.toString().orEmpty()
            assertTrue(!detail.contains("m 없음"), "m이 있는데 detail이 'm 없음': $detail")
            assertTrue(dateForms(m).any { detail.contains(it) }, "restart 행 detail에 m($m)을 더해야 한다(§4.11 ① 기동 기록): '$detail'")

            // §4.11 ③ 빠지는 몫 = 안전망 몫(전체 커서)
            ctx.safetyUntil { db.cnt(s, 601) == 0 && db.cnt(s, 602) == 0 }
            assertEquals(listOf(SAFETY_APP), db.repairSources(s, 601))
            assertEquals(listOf(SAFETY_APP), db.repairSources(s, 602))
        }
    }

    // ── (나) ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `나 — V9보다 먼저 시작해 V9가 끝난 뒤 쿼터 행을 고친 트랜잭션(표시 lt m · 시작 ge 첫 하한)은 첫 창에 든다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        migrateV7(url)
        val s = db.seedSchedule()
        (611L..614L).forEach { u -> db.quota(s, u, 1); db.hold(s, u) }   // 기존 4쌍(일치)

        val t = rawConn(url)
        try {
            val tStart = t.txNow()                 // T 시작(쿼터 테이블에 락 없음 — V9 ALTER가 기다리지 않는다)
            boot(url, incrementalProps("trigger", LOCS_TRG)).use { ctx ->
                val m = db.missingM() ?: fail("전제: attmissingval이 있어야 한다")
                assertTrue(tStart < m, "전제: T는 V9보다 먼저 시작했다($tStart < m $m)")
                t.exec("UPDATE user_hold_quota SET cnt = 5 WHERE schedule_id = ? AND user_id = ?", s, 614L)   // V9 뒤 — 트리거 표시 = T 시작
                t.commit()
                assertEquals(tStart, db.updatedAtOf(s, 614), "전제: 표시 = T 시작 시각 < m")
                val firstLower = db.gapRows("restart").last().instant("to_at")!!
                assertTrue(firstLower <= tStart, "전제: T 시작 $tStart ≥ 첫 하한 $firstLower (ADR-012 보장 안)")

                ctx.tick()

                assertEquals(1L, db.firstRun().long("pairs_scanned"), "첫 틱 후보 = T가 고친 쌍 1개(값 m인 기존 3행 제외): ${db.firstRun()}")
                assertEquals(listOf(SRC_TRG), db.repairSources(s, 614), "N11: 표시 < m인 V9 뒤 변경도 첫 틱이 고친다(엄격한 하한 max(…, m) 금지)")
                assertEquals(1, db.cnt(s, 614))
            }
        } finally {
            t.close()
        }
    }

    // ── (다) ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `다 — 후보가 page-size보다 많으면 여러 틱에 나눠 다 읽고 유실 0 · 같은 updated_at 묶음 안 키셋 · page_full · carry_ticks`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        boot(url, incrementalProps("trigger", LOCS_TRG, pageSizeKey to "3")).use { ctx ->
            val s = db.seedSchedule()
            val users = (701L..710L).toList() + listOf(711L, 712L)
            insertQuotasInOneTx(url, s, (701L..710L).toList(), 1)    // 같은 updated_at 10행(어긋남 — 실제 0)
            insertQuotasInOneTx(url, s, listOf(711L, 712L), 1)

            ctx.tick()
            assertEquals(3L, db.firstRun().long("pairs_scanned"), "틱 한 번 = 페이지 하나(page-size 3): ${db.firstRun()}")

            var n = 1
            while (!(users.all { db.cnt(s, it) == 0 } && db.lastRunOf(SRC_TRG).long("pairs_scanned") < 3)) {
                check(n++ < 40) { "40틱 안에 다 읽지 못했다 — 남은 쌍 ${users.filter { db.cnt(s, it) != 0 }}" }
                ctx.tick()
            }

            val runs = db.incRuns(SRC_TRG)
            assertTrue(runs.all { it.long("pairs_scanned") <= 3 }, "어느 틱도 페이지 상한을 넘지 않는다: ${runs.map { it["pairs_scanned"] }}")
            assertTrue(runs.size >= 4, "12쌍을 3개씩 — 4틱 이상: ${runs.size}")
            users.forEach { u -> assertEquals(listOf(SRC_TRG), db.repairSources(s, u), "N12 유실 0 — 쌍 $u 는 증분이 한 번 고친다") }

            val meters = ctx.meters()
            val pageFull = meters.find(pageFullMetric).counter()?.count() ?: fail("지표 $pageFullMetric 가 없다")
            assertTrue(pageFull >= 3.0, "찬 페이지 수(H3 — 12행 ÷ 3 = 4, 마지막 찬 페이지 셈은 구현 몫): $pageFull")
            val carry = meters.find(carryTicksMetric).summary() ?: fail("지표 $carryTicksMetric(분포 — H4)가 없다")
            assertTrue(carry.count() >= 1 && carry.max() >= 2.0, "이어 읽기 1번 이상 끝났고 2틱 이상 이어졌다: count ${carry.count()} max ${carry.max()}")
        }
    }

    @Test
    fun `다 NF-1 — 이어 읽는 동안 시작해 키셋이 지난 위치보다 앞선 표시로 커밋한 짧은 트랜잭션 · 이어 읽기가 max-overlap보다 길어도 다 읽은 뒤 창이 다시 읽는다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        val mo = Duration.ofSeconds(3)
        boot(url, incrementalProps("trigger", LOCS_TRG, pageSizeKey to "2", "$INC.max-overlap" to "${mo.seconds}s")).use { ctx ->
            val s = db.seedSchedule()
            insertQuotasInOneTx(url, s, listOf(800L), 0)           // rX — 일치(cnt 0 · 실제 0)
            insertQuotasInOneTx(url, s, listOf(801L), 1)           // A — 어긋남

            ctx.tick()                                             // 틱 1 = t₀ · 페이지 찼다(800, 801) → 이어 읽기 시작 · 801 복구(새 표시)
            assertEquals(2L, db.firstRun().long("pairs_scanned"), "틱 한 번 = 페이지 하나(page-size 2): ${db.firstRun()}")

            val x = rawConn(url)
            try {
                val xStart = x.txNow()                             // X 시작(t₀ 뒤 · 이어 읽는 중)
                val cUsers = (901L..906L).toList()
                insertQuotasInOneTx(url, s, cUsers, 1)             // C — 표시 > X 시작
                ctx.tick()                                         // 틱 2 — X는 열려 있다(나이 < max-overlap)
                // 페이지가 실제로 걸렸다(아니면 이 시나리오는 이어 읽기를 타지 않아 공허하다 — 그린 위장 방지)
                assertEquals(2L, db.lastRunOf(SRC_TRG).long("pairs_scanned"), "틱 2도 페이지 하나(page-size 2 — 후보 7개 중): ${db.lastRunOf(SRC_TRG)}")
                assertEquals(0, db.cnt(s, 901), "전제(H7): 틱 2의 키셋이 C(표시 > X 시작)에 들어갔다")
                assertTrue(db.gapRows("window_clamped").isEmpty(), "전제: X는 짧은 트랜잭션 — 창 상한에 걸리지 않았다")

                x.exec("UPDATE user_hold_quota SET cnt = 7 WHERE schedule_id = ? AND user_id = ?", s, 800L)   // rX 어긋남 · 표시 = X 시작
                x.commit()
                val p = db.updatedAtOf(s, 901)!!
                assertEquals(xStart, db.updatedAtOf(s, 800), "전제: rX 표시 = X 시작")
                assertTrue(xStart < p, "전제: rX 표시가 키셋이 이미 지난 위치보다 앞(X 시작 $xStart < $p)")

                // 이어 읽기가 X 시작 + max-overlap + 여유를 넘게 이어지게 — 다 읽은 시각 기준 하한(t − max-overlap)이면 rX를 놓친다(NF-1)
                db.awaitDbTimeAfter(xStart.plus(mo).plusSeconds(3))

                var n = 0
                while (db.cnt(s, 800) != 0) {
                    check(n++ < 40) {
                        "N12 위반: 40틱 안에 rX(표시 = X 시작 $xStart)를 다시 읽지 못했다 — 다 읽은 뒤 하한이 X 시작보다 뒤(t₀ 기준이 아니다?) · " +
                            "틱 후보 ${db.incRuns(SRC_TRG).map { it["pairs_scanned"] }}"
                    }
                    ctx.tick()
                }
                assertEquals(listOf(SRC_TRG), db.repairSources(s, 800), "rX는 증분이 고친다(안전망은 돌리지 않았다)")
                cUsers.forEach { u ->
                    while (db.cnt(s, u) != 0 && n++ < 60) ctx.tick()
                    assertEquals(listOf(SRC_TRG), db.repairSources(s, u), "C 쌍 $u 유실 없음")
                }
                assertTrue(db.gapRows("window_clamped").isEmpty(), "max-overlap 안의 트랜잭션뿐 — window_clamped 없음: ${db.gapRows("window_clamped")}")
            } finally {
                x.close()
            }
        }
    }

    // ── (라) ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `라 — 이어 읽는 동안 max-overlap보다 오래 열린 트랜잭션 · 그 틱에 window_clamped 행과 지표(무음 아님) · 나머지 후보 유실 없음`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        boot(url, incrementalProps("trigger", LOCS_TRG, pageSizeKey to "2", "$INC.max-overlap" to "1s")).use { ctx ->
            val s = db.seedSchedule()
            val users = (1001L..1006L).toList()
            insertQuotasInOneTx(url, s, users, 1)

            ctx.tick()                                             // 틱 1 — 페이지 찼다 → 이어 읽기
            assertEquals(2L, db.firstRun().long("pairs_scanned"), "틱 한 번 = 페이지 하나(page-size 2): ${db.firstRun()}")
            assertTrue(db.gapRows("window_clamped").isEmpty(), "전제: 틱 1에는 긴 트랜잭션이 없다")

            val y = rawConn(url)
            try {
                val yStart = y.txNow()
                db.awaitDbTimeAfter(yStart.plusMillis(2_500))      // Y 나이 > max-overlap 1s(+ 여유 1초)
                val before = db.gapRows("window_clamped").size
                ctx.tick()                                         // 틱 2 — 이어 읽는 중 · 하한 후보가 겹침 상한에 걸린다
                val clamped = db.gapRows("window_clamped").drop(before)
                assertTrue(clamped.isNotEmpty(), "N12: 이어 읽는 중 겹침 상한에 걸리면 window_clamped 행(무음 아님): ${db.gapRows(null)}")
                val from = clamped.first().instant("from_at")
                assertNotNull(from, "window_clamped 행 from_at = 가장 오래된 xact_start: ${clamped.first()}")
                assertTrue(Duration.between(yStart, from).abs() <= Duration.ofMillis(1), "from_at = Y 시작($yStart): ${clamped.first()}")
                val metric = ctx.meters().find(M_INC_CLAMPED).counter()?.count() ?: 0.0
                assertTrue(metric >= 1.0, "지표 $M_INC_CLAMPED ≥ 1: $metric")
                y.rollback()
            } finally {
                y.close()
            }

            var n = 0
            while (!users.all { db.cnt(s, it) == 0 }) {
                check(n++ < 40) { "40틱 안에 다 읽지 못했다 — 남은 쌍 ${users.filter { db.cnt(s, it) != 0 }}" }
                ctx.tick()
            }
            users.forEach { u -> assertEquals(listOf(SRC_TRG), db.repairSources(s, u), "쌍 $u 유실 없음") }
        }
    }

    // ── (마) ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `마 — attmissingval 없음(VACUUM FULL 뒤) · 제외 없이 지금 문장 그대로 · 기동 WARN · restart detail 'm 없음'`(output: CapturedOutput) {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        migrateV7(url)
        val s = db.seedSchedule()
        db.quota(s, 1101, 1)                                       // 기존 — 어긋남
        (1102L..1103L).forEach { u -> db.quota(s, u, 1); db.hold(s, u) }
        // start-lookback 10분 — 기동 두 번과 VACUUM FULL 사이 시간이 첫 창 판정에 섞이지 않게(기존 행이 첫 창 안에 든다)
        val props = incrementalProps("trigger", LOCS_TRG, "$INC.start-lookback" to "10m")

        boot(url, props).use { _ -> }                              // V9 적용(기존 행 = m0) 뒤 바로 내림 — 틱 없음
        val m0 = db.missingM() ?: fail("전제: V9 직후 attmissingval이 있어야 한다")
        db.execute("VACUUM FULL user_hold_quota")
        assertNull(db.missingM(), "전제: VACUUM FULL이 빠른 기본값을 지웠다(§4.9-16)")
        (1101L..1103L).forEach { u -> assertEquals(m0, db.updatedAtOf(s, u), "전제: 행에는 m0이 물리로 남는다(쌍 $u)") }
        val restartsBefore = db.gapRows("restart").size
        val logFrom = output.all.length

        boot(url, props).use { ctx ->
            val restart = db.gapRows("restart").drop(restartsBefore).singleOrNull() ?: fail("두 번째 기동의 restart 행 1개: ${db.gapRows("restart")}")
            assertTrue(restart.instant("to_at")!! <= m0, "전제: 기존 행이 첫 창 안(첫 하한 ≤ m0): $restart")
            val detail = restart["detail"]?.toString().orEmpty()
            assertTrue(detail.contains("m 없음"), "restart 행 detail에 'm 없음'(§4.11 ① 출처가 없을 때): '$detail'")

            val warn = output.all.substring(logFrom).lines().filter { line ->
                line.contains("WARN") && listOf("m 없음", "attmissingval", "atthasmissing", "빠른 기본값").any { line.contains(it) }
            }
            assertTrue(warn.isNotEmpty(), "기동 WARN 1행(H2 — m 출처 없음): 로그에 해당 WARN이 없다")

            db.quota(s, 1104, 1)                                   // 기동 뒤 변경 1
            ctx.tick()
            assertEquals(4L, db.firstRun().long("pairs_scanned"), "m이 없으면 제외 없이 지금 문장 그대로 — 기존 3 + 새 1: ${db.firstRun()}")
            assertEquals(listOf(SRC_TRG), db.repairSources(s, 1101), "제외 없음 — 기존 어긋남도 첫 틱이 고친다")
        }
    }

    // ── (+) page-size 기동 검사 ─────────────────────────────────────────────────────────────

    @Test
    fun `page-size 0은 기동 검사가 거부한다(양수만)`() {
        val url = freshDbUrl()
        val err = bootFailure(url, incrementalProps("trigger", LOCS_TRG, pageSizeKey to "0"))
        assertStartupFailure(err, "page-size 0", listOf("page-size"))
    }
}
