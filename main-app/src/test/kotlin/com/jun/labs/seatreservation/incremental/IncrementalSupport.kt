package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.drift.DriftTestSupport
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.support.DriftLog
import com.jun.labs.seatreservation.support.causesOf
import com.jun.labs.seatreservation.support.driftLogs
import com.jun.labs.seatreservation.support.insertSeatRow
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * ADR-012 §4.2.10 blind 테스트 공용 도구(task 06). 계약 = ADR-012 §2.1~§3(2026-10-20 재합의 줄 포함) · §4.2 · §5 R4~R7 · 명세 §2·§5.
 * 판정은 구현 코드를 재사용하지 않고 DB 행(counter_reconcile_run · counter_drift_log · counter_safety_run · counter_inc_gap · counter_app_inject)·
 * 지표·기동 결과만 본다.
 *
 * ── 계약 표면 가정(구현자가 맞춰야 하는 이음새 — 계약에 이름이 없거나 모양이 정해지지 않은 것) ──
 * S1  `IncrementalReconciler.tick(source: String)` — 계약 §4.2.3 그대로(반환값은 쓰지 않는다). 이 별칭이 패키지를 가정한다(core는 패키지 유지 — §4.2.1).
 * S2  안전망 빈의 클래스 이름 = `SafetyNetReconciler`(계약은 '안전망 `runOnce()`'만 정한다). 공개 함수 `runOnce()`(반환값은 쓰지 않는다).
 * S3  `SafetyNetReconciler.sampleOnce()` — id–시각 표본 1회(§4.2.5 '표본'). 표본 주기(sample-interval)를 1h로 두고 테스트가 직접 부른다.
 * S4  테스트 이음새 `MemoryRegistrationProbe`(fun interface `beforeRegister(scheduleId, userId)`) — ⓐ `afterCommit` 안에서 집합에 넣기 직전에 부른다
 *     (빈이 없으면 아무것도 안 함 — `CounterRepairProbe`와 같은 `ObjectProvider.ifAvailable`). 테스트 23 '`afterCommit`에서 일부러 실패'용.
 * S5  새 스케줄러(증분·안전망·표본)의 첫 실행은 **한 주기 뒤**다(`CounterDriftScheduling.kt`와 같은 방식). 테스트는 주기를 1h로 두고 진입점을 직접 부른다.
 * S6  `CounterRepairProbe.afterDetect`는 증분·안전망 복구(`PairRepairRunner` = 지금 `repairOne`)에서도 불린다 — 테스트 6이 쓴다.
 * S7  `ChunkDetector`는 `detect-shape` 손잡이에 맞는 구현 하나가 빈으로 주입된다(테스트 17).
 * S8  기동 검사(`IncrementalSchemaCheck` 등)의 실패는 `IllegalStateException`이고, 메시지에 막은 대상의 이름(키·객체 이름 중 하나 — 아래 각 단언의 후보 목록)이 있다.
 * 별칭 두 줄(S2·S4)만 이름을 가정한다 — 다르면 이 두 줄만 고친다.
 */
typealias IncrementalReconciler = com.jun.labs.seatreservation.service.impl.drift.IncrementalReconciler
typealias SafetyNetReconciler = com.jun.labs.seatreservation.service.impl.drift.SafetyNetReconciler
typealias MemoryRegistrationProbe = com.jun.labs.seatreservation.service.impl.drift.MemoryRegistrationProbe

// ── 손잡이(계약 §4.2.2 · §4.2.5 · §4.2.7 — 키 이름 그대로) ─────────────────────────────────────
const val INC = "seat.hold.counter-drift.reconcile.incremental"
const val SAFE = "seat.hold.counter-drift.reconcile.safety-net"
const val INJECT = "seat.hold.loadtest.inject-app"

/** 증분이 켜지면 A(만료 복구)가 필수(§4.2.2 기동 검사) — IntegrationTest의 A 끔 고정을 덮는다. */
const val A_ON = "seat.hold.counter-drift.expiry-repair=true"
const val INC_ON = "$INC.enabled=true"
const val INC_1H = "$INC.interval=1h"
const val SAFE_ON = "$SAFE.enabled=true"
const val SAFE_1H = "$SAFE.interval=1h"
const val SAMPLE_1H = "$SAFE.sample-interval=1h"
const val M_MEM = "$INC.method=memory"
const val M_TRG = "$INC.method=trigger"
const val M_STMT = "$INC.method=stmt"

// ── Flyway 위치(계약 §4.2.2 조합표) ─────────────────────────────────────────────────────────
const val LOC_BASE = "classpath:db/migration"
const val LOC_V8 = "classpath:db/migration-trigger"
const val LOC_V9_STMT = "classpath:db/migration-updated-at"
const val LOC_V9_TRG = "classpath:db/migration-updated-at-trigger"
const val LOC_V10 = "classpath:db/migration-incremental"
const val FW_V10 = "spring.flyway.locations=$LOC_BASE,$LOC_V10"
const val FW_TRG = "spring.flyway.locations=$LOC_BASE,$LOC_V9_TRG,$LOC_V10"
const val FW_STMT = "spring.flyway.locations=$LOC_BASE,$LOC_V9_STMT,$LOC_V10"
/** ⓐ 셀에 문장 위치(V9 열·인덱스)를 더한 구성 — 조합표 'ⓐ + 문장 위치 = 성공(결과 같음)' */
const val FW_MEM_WITH_V9 = "spring.flyway.locations=$LOC_BASE,$LOC_V9_STMT,$LOC_V10"

// ── source(계약 §4.2.6 표) ──────────────────────────────────────────────────────────────
const val SRC_MEM = "inc-app-mem-1s"
const val SRC_TRG = "inc-app-trg-1s"
const val SRC_STMT = "inc-app-stmt-1s"
const val SAFETY_APP = "safety-app"
const val SAFETY_EXT = "safety-ext"

// ── 지표(계약 §4.2.6 표 — 이름 그대로) ───────────────────────────────────────────────────────
const val M_INC_TICK = "seat.hold.counter.reconcile.incremental.tick"
const val M_INC_BORROWS = "seat.hold.counter.reconcile.incremental.borrows"
const val M_INC_CANDIDATES = "seat.hold.counter.reconcile.incremental.candidates"
const val M_INC_CLAMPED = "seat.hold.counter.reconcile.incremental.window_clamped"
const val M_INC_ERRORS = "seat.hold.counter.reconcile.incremental.errors"
const val M_INC_MEM_OVERFLOW = "seat.hold.counter.reconcile.incremental.memory.overflow"
const val M_INC_MEM_REQUEUED = "seat.hold.counter.reconcile.incremental.memory.requeued"
const val M_INC_HIDDEN = "seat.hold.counter.reconcile.incremental.hidden_sessions"
const val M_INC_GAP = "seat.hold.counter.reconcile.incremental.gap"
const val M_SAFE_BORROWS = "seat.hold.counter.safety.borrows"
const val M_SAFE_SKIPPED_IDS = "seat.hold.counter.safety.recent.skipped_ids"
const val M_SAFE_LAP_OVERDUE = "seat.hold.counter.safety.lap_overdue"
const val M_SAFE_EXPEDITED = "seat.hold.counter.safety.expedited"
const val M_SAFE_ERRORS = "seat.hold.counter.safety.errors"
const val M_SAFE_CARRY_DROPPED = "seat.hold.counter.safety.carry_dropped"
/** 새 지표 이름의 접두어 — 손잡이 꺼짐이면 하나도 없어야 한다(§4.2.6 등록 시점 · 테스트 15) */
val NEW_METRIC_PREFIXES = listOf("seat.hold.counter.reconcile.incremental", "seat.hold.counter.safety")

/** V10(선택 위치) 테이블 3개 — §4.2.5 · §4.2.6 · §4.2.7 */
val V10_TABLES = listOf("counter_safety_run", "counter_inc_gap", "counter_app_inject")

// ── 행 읽기 도우미(열 이름 = 계약 표 그대로) ───────────────────────────────────────────────────

fun Map<String, Any?>.long(k: String): Long = (this[k] as Number?)?.toLong() ?: error("열 $k 가 NULL이거나 없다: $this")
fun Map<String, Any?>.longOrNull(k: String): Long? = (this[k] as Number?)?.toLong()
fun Map<String, Any?>.bool(k: String): Boolean = this[k] as Boolean? ?: error("열 $k 가 NULL이거나 없다: $this")
fun Map<String, Any?>.instant(k: String): Instant? = when (val v = this[k]) {
    null -> null
    is Timestamp -> v.toInstant()
    is OffsetDateTime -> v.toInstant()
    is Instant -> v
    else -> error("열 $k 의 형식을 모른다: ${v::class}")
}

fun JdbcTemplate.tableExists(t: String): Boolean = queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, t) == true

fun JdbcTemplate.columnExists(table: String, column: String): Boolean = queryForObject(
    "SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?", Int::class.java, table, column,
)!! > 0

fun JdbcTemplate.triggerExists(name: String): Boolean = queryForObject(
    "SELECT count(*) FROM pg_trigger WHERE tgname = ? AND NOT tgisinternal", Int::class.java, name,
)!! > 0

fun JdbcTemplate.incRuns(source: String): List<Map<String, Any?>> =
    queryForList("SELECT * FROM counter_reconcile_run WHERE source = ? ORDER BY id", source)

fun JdbcTemplate.lastRunOf(source: String): Map<String, Any?> =
    incRuns(source).lastOrNull() ?: error("source=$source 의 counter_reconcile_run 행이 없다")

/** 안전망 실행 기록 = counter_safety_run + 같은 run_id의 counter_reconcile_run(§4.2.5 순서 6). */
fun JdbcTemplate.safetyRows(): List<Map<String, Any?>> = queryForList(
    """
    SELECT s.*, r.source AS run_source, r.found AS run_found, r.repaired AS run_repaired, r.chunks AS run_chunks,
           r.orphan_chunks AS run_orphan_chunks, r.skipped_pairs AS run_skipped_pairs, r.errors AS run_errors
    FROM counter_safety_run s LEFT JOIN counter_reconcile_run r ON r.id = s.run_id ORDER BY s.run_id
    """.trimIndent(),
)

fun JdbcTemplate.gapRows(kind: String? = null): List<Map<String, Any?>> =
    queryForList("SELECT * FROM counter_inc_gap WHERE (?::text IS NULL OR kind = ?) ORDER BY id", kind, kind)

fun JdbcTemplate.injectRows(): List<Map<String, Any?>> = queryForList("SELECT * FROM counter_app_inject ORDER BY id")

fun JdbcTemplate.logsOf(scheduleId: Long, userId: Long): List<DriftLog> =
    driftLogs().filter { it.scheduleId == scheduleId && it.userId == userId }

fun JdbcTemplate.updatedAtOf(scheduleId: Long, userId: Long): Instant? = queryForList(
    "SELECT updated_at FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Timestamp::class.java, scheduleId, userId,
).singleOrNull()?.toInstant()

/** DB 벽시계(문장 시각) */
fun JdbcTemplate.dbNow(): Instant = queryForObject("SELECT clock_timestamp()", Timestamp::class.java)!!.toInstant()

/** DB 시계가 [t]를 지날 때까지 기다린다 — 창·표본 규칙이 DB 시계 기준이라 '시간이 흐름' 자체가 조건인 곳에서만 쓴다. */
fun JdbcTemplate.awaitDbTimeAfter(t: Instant, timeout: Duration = Duration.ofSeconds(30)) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (!(queryForObject("SELECT clock_timestamp() > ?", Boolean::class.java, Timestamp.from(t)) ?: false)) {
        check(System.nanoTime() < deadline) { "DB 시계가 $t 를 ${timeout} 안에 지나지 않았다" }
        Thread.sleep(20)
    }
}

fun normalizedSql(sql: String) = sql.lowercase().replace(Regex("\\s+"), "")

// ── 원시 연결(앱 풀 밖 — Hikari 빌림 지표에 섞이지 않게) ───────────────────────────────────────

fun Connection.exec(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { ps ->
    args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
    if (ps.execute()) 0 else ps.updateCount
}

fun Connection.pid(): Int = createStatement().use { st -> st.executeQuery("SELECT pg_backend_pid()").use { it.next(); it.getInt(1) } }

/** 이 트랜잭션의 시작 시각(`now()`) — 쓰기 문장이 남긴 updated_at(= now())과 같은 값 */
fun Connection.txNow(): Instant = createStatement().use { st ->
    st.executeQuery("SELECT now()").use { it.next(); it.getTimestamp(1).toInstant() }
}

fun Connection.queryLong(sql: String, vararg args: Any?): Long = prepareStatement(sql).use { ps ->
    args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
    ps.executeQuery().use { it.next(); it.getLong(1) }
}

data class Waiter(val pid: Int, val query: String)

/** [blocker] 세션의 잠금을 기다리는 세션이 [n]개 이상이 될 때까지(DetectShapeEquivalenceTest와 같은 동기화). */
fun JdbcTemplate.awaitBlockedBy(blocker: Int, n: Int = 1, timeout: Duration = Duration.ofSeconds(20)): List<Waiter> {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
        val ws = query(
            "SELECT pid, query FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)) ORDER BY pid",
            { rs, _ -> Waiter(rs.getInt(1), rs.getString(2)) },
            blocker,
        )
        if (ws.size >= n) return ws
        check(System.nanoTime() < deadline) { "세션 $blocker 의 잠금을 기다리는 세션이 ${timeout} 안에 ${n}개가 되지 않았다(지금 ${ws.size})" }
        Thread.sleep(10)
    }
}

/** [future]가 끝날 때까지 [blocker]를 기다리는 문장을 모두 취소한다(그 블록 실패를 만든다). 취소한 수를 돌려준다. */
fun JdbcTemplate.cancelWaitersUntilDone(blocker: Int, future: Future<*>, timeout: Duration = Duration.ofSeconds(30)): Int {
    val deadline = System.nanoTime() + timeout.toNanos()
    var cancelled = 0
    while (!future.isDone) {
        query("SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", { rs, _ -> rs.getInt(1) }, blocker).forEach { p ->
            if (queryForObject("SELECT pg_cancel_backend(?)", Boolean::class.java, p) == true) cancelled++
        }
        check(System.nanoTime() < deadline) { "실행이 ${timeout} 안에 끝나지 않았다" }
        Thread.sleep(10)
    }
    return cancelled
}

/**
 * ADR-012 증분·안전망 테스트 바탕. 컨텍스트(손잡이)는 하위 클래스의 `@TestPropertySource`가 정한다.
 * 기록 테이블은 매 테스트 비운다(DriftTestSupport가 V7 3개, 여기서 V10 3개).
 */
abstract class IncrementalTestBase : DriftTestSupport() {

    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirmUseCase: ConfirmReservationUseCase
    @Autowired lateinit var expireHolds: ExpireHoldsUseCase
    @Autowired lateinit var appContext: ApplicationContext

    protected lateinit var pool: ExecutorService

    val incremental: IncrementalReconciler get() = appContext.getBean(IncrementalReconciler::class.java)
    val safety: SafetyNetReconciler get() = appContext.getBean(SafetyNetReconciler::class.java)

    protected val s: Long get() = schedule.id!!

    @BeforeEach
    fun clearV10Tables() {
        V10_TABLES.forEach { t -> if (jdbcTemplate.tableExists(t)) jdbcTemplate.update("DELETE FROM $t") }
        pool = Executors.newCachedThreadPool()
    }

    @AfterEach
    fun stopPool() {
        pool.shutdownNow()
    }

    private var seatSeq = 20_000
    fun freeSeat(scheduleId: Long = s): Long = jdbcTemplate.insertSeatRow(scheduleId, ++seatSeq)

    fun appHold(userId: Long, scheduleId: Long = s): HoldSeatResult = holdSeat.hold(HoldSeatCommand(scheduleId, freeSeat(scheduleId), userId))

    fun appConfirmed(userId: Long, scheduleId: Long = s) {
        val h = appHold(userId, scheduleId)
        confirmUseCase.confirm(ConfirmReservationCommand(holdId = h.holdId, userId = userId, paymentUid = "p-$userId-${h.seatId}"))
    }

    fun tick(source: String) {
        incremental.tick(source)
    }

    fun safetyRun(): Map<String, Any?> {
        safety.runOnce()
        return jdbcTemplate.safetyRows().last()
    }

    fun sample() {
        safety.sampleOnce()
    }

    /** 안전망을 [cond]가 참이 될 때까지 돌린다(최대 [max]회). 돌린 횟수를 돌려준다. */
    fun safetyUntil(max: Int = 200, cond: () -> Boolean): Int {
        var n = 0
        while (!cond()) {
            check(n < max) { "안전망 ${max}회 안에 조건을 만족하지 못했다" }
            sample()
            safety.runOnce()
            n++
        }
        return n
    }

    /** 바퀴 하나가 끝날 때까지(이 호출 뒤 새 행 중 lap_completed = true). 그 바퀴를 끝낸 행을 돌려준다. */
    fun safetyLap(max: Int = 500): Map<String, Any?> {
        val before = jdbcTemplate.safetyRows().size
        safetyUntil(max) { jdbcTemplate.safetyRows().drop(before).any { it.bool("lap_completed") } }
        return jdbcTemplate.safetyRows().drop(before).first { it.bool("lap_completed") }
    }

    /** 앱 풀 밖 원시 연결(자동 커밋 끔). */
    fun rawConnection(): Connection {
        val h = dataSource.unwrap(HikariDataSource::class.java)
        return DriverManager.getConnection(h.jdbcUrl, h.username, h.password).apply { autoCommit = false }
    }

    fun dbNow(): Instant = jdbcTemplate.dbNow()

    fun gauge(name: String): Double? = meters.find(name).gauge()?.value()
}

/** 기동 실패 단언 — 원인 사슬에 BindException 없음 + 기대 예외 + 메시지 후보 중 하나(가정 S8). */
fun assertStartupFailure(error: Throwable?, why: String, keywords: List<String>, expectFlyway: Boolean = false) {
    if (error == null) fail("떴다 — $why")
    val chain = causesOf(error)
    val dump = chain.map { "${it::class.qualifiedName}: ${it.message?.take(300)}" }
    assertTrue(chain.none { it is BindException }, "설정 바인딩 실패(새 키·값이 아직 없다?) — 기동 검사가 막은 것이 아니다 · $why: $dump")
    if (expectFlyway) {
        assertTrue(
            chain.any { generateSequence<Class<*>>(it.javaClass) { c -> c.superclass }.any { c -> c.name == "org.flywaydb.core.api.FlywayException" } },
            "Flyway가 막아야 한다 · $why: $dump",
        )
        return
    }
    assertTrue(
        chain.any { e -> e is IllegalStateException && keywords.any { k -> e.message.orEmpty().contains(k, ignoreCase = true) } },
        "기동 검사(IllegalStateException · 메시지에 $keywords 중 하나)로 막힌 것이 아니다 · $why: $dump",
    )
}

/** 실패할 것으로 기대하는 블록의 예외(없으면 null). */
inline fun failureOf(block: () -> Unit): Throwable? = try {
    block(); null
} catch (e: AssertionError) {
    throw e
} catch (e: Throwable) {
    e
}

fun Future<*>.join(seconds: Long = 30): Any? = get(seconds, TimeUnit.SECONDS)
