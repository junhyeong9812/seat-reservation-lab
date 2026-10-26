package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.support.IntegrationTest
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/*
 * ADR-014 blind 테스트 공용 도구(task 03). 계약 = ADR-014 §설계(2026-10-26 선검증 반영본) · 명세 §2 · §10 R1~R4.
 * 구현 diff를 보지 않고 썼다(기준 origin/main 882fa2ea). 새 클래스·메서드를 직접 부르지 않는다 —
 * HTTP(MockMvc · java.net.http) · JdbcTemplate SQL · 설정 키 · 이미 있는 UseCase 인터페이스(HoldSeat · ConfirmReservation · ExpireHolds)와
 * 이미 있는 QuotaCleanup.runOnce()만 쓴다. 판정은 DB 행을 직접 센다(ADR-001 — 측정 대상이 스스로를 채점하지 않게).
 *
 * ── 계약 표면 가정(설계 문서에 이름·모양이 다 정해지지 않은 곳 — 보고서 '가정' 목록과 같다) ──
 * E1  손잡이 키 `seat.hold.expiry-mode` = sweep · lazy · lazy-sweep · derived(§설계-6 · Q9). 기동 거부는 IllegalStateException이고
 *     메시지에 `expiry-mode`가 있다(§설계-6 '메시지에 바꿀 키를 안내한다').
 * E2  만료 상태는 DB 기준 상대 시각(`now() - interval`)으로 행을 직접 써서 만든다(Q10). 앱 Clock(MutableClock)은 B·C·D 판정에 쓰이지 않는다(N1).
 * E3  좌석 맵 응답 = `[{seatId, section, rowNo, seatNo, status}]` 키 다섯 개뿐 · seatId 오름차순(§3.3 SQL의 ORDER BY id) · 회차 없음 404 ·
 *     좌석 없는 회차 200 [].
 * E4  NOWAIT 패배 · H8 최종 판정 실패는 409 SEAT_NOT_AVAILABLE(지금 계약 그대로 — §3.1 H7·H8).
 * E5  B의 ExpireHoldsUseCase.expire()는 0을 돌려주고 아무것도 바꾸지 않는다(§설계-6 'lazy에서는 할 일이 없다(0 반환)').
 * E6  TTL 5분 · max-per-user 2(application.yml 그대로 — 테스트가 덮지 않는다).
 */

const val EXPIRY_MODE = "seat.hold.expiry-mode"

/** 메인 수정(코드 리뷰 CR14-1): B(lazy)는 이 키가 schedule(정리 ①)이어야 뜬다 — 옛 동작 고정 키(BootTestBase · IntegrationTest)는 none */
const val LAZY_CLEANUP_KEY = "seat.hold.counter-drift.cleanup.mode"

/** 앱 시계 과거 위치 — DB 벽시계보다 이르다(IntegrationTest.NOW). A(앱 Clock)라면 DB 기준 만료 홀드를 '활성'으로 본다. */
val CLOCK_PAST: Instant = IntegrationTest.NOW

/** 앱 시계 미래 위치 — DB 벽시계보다 늦고 회차 시작(2099-12-31)보다 이르다(판매 마감은 앱 Clock — 바꾸지 않는다 §2.1). A라면 DB 기준 활성 홀드를 '만료'로 본다. */
val CLOCK_FUTURE: Instant = Instant.parse("2099-06-01T00:00:00Z")

val TTL: Duration = Duration.ofMinutes(5)
const val MAX_PER_USER = 2

/**
 * 홀드 행을 직접 쓴다 — 시각은 DB 기준 상대값(E2). [expiresIn] = PostgreSQL interval 문자열('-1 minute' = 1분 전 만료 · '5 minutes' = 활성).
 * [quota]면 그 쌍의 cnt도 +1 한다(카운터 = 홀드 행 수 + 확정 — 명세 §10 R2).
 */
fun JdbcTemplate.dbHold(seatId: Long, scheduleId: Long, userId: Long, expiresIn: String, quota: Boolean = true): Long {
    val id = queryForObject(
        """
        INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at)
        VALUES (?, ?, ?, clock_timestamp() + ?::interval - interval '5 minutes', clock_timestamp() + ?::interval) RETURNING id
        """.trimIndent(),
        Long::class.java, seatId, scheduleId, userId, expiresIn, expiresIn,
    )!!
    if (quota) addQuota(scheduleId, userId, 1)
    return id
}

fun JdbcTemplate.addQuota(scheduleId: Long, userId: Long, n: Int) {
    update(
        """
        INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, ?)
        ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = user_hold_quota.cnt + EXCLUDED.cnt
        """.trimIndent(),
        scheduleId, userId, n,
    )
}

/** 확정된 좌석을 직접 만든다 — 예약 CONFIRMED + 좌석 RESERVED + cnt +1. */
fun JdbcTemplate.dbConfirmed(seatId: Long, scheduleId: Long, userId: Long): Long {
    val id = queryForObject(
        "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, ?, 'CONFIRMED', now()) RETURNING id",
        Long::class.java, scheduleId, seatId, userId, "pay-$seatId-$userId",
    )!!
    update("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", seatId)
    addQuota(scheduleId, userId, 1)
    return id
}

fun JdbcTemplate.storedStatus(seatId: Long): String =
    queryForObject("SELECT status FROM product_seat WHERE id = ?", String::class.java, seatId)!!

fun JdbcTemplate.holdRowExists(holdId: Long): Boolean =
    queryForObject("SELECT count(*) FROM seat_hold WHERE id = ?", Int::class.java, holdId)!! > 0

fun JdbcTemplate.holdRowsOfSeat(seatId: Long): Int =
    queryForObject("SELECT count(*) FROM seat_hold WHERE seat_id = ?", Int::class.java, seatId)!!

fun JdbcTemplate.activeHoldsOfSeat(seatId: Long): Int =
    queryForObject("SELECT count(*) FROM seat_hold WHERE seat_id = ? AND expires_at > now()", Int::class.java, seatId)!!

fun JdbcTemplate.activeHoldOf(seatId: Long, userId: Long): Int = queryForObject(
    "SELECT count(*) FROM seat_hold WHERE seat_id = ? AND user_id = ? AND expires_at > now()", Int::class.java, seatId, userId,
)!!

fun JdbcTemplate.expiredHoldRows(): Int =
    queryForObject("SELECT count(*) FROM seat_hold WHERE expires_at <= now()", Int::class.java)!!

fun JdbcTemplate.confirmedOfSeat(seatId: Long): Int = queryForObject(
    "SELECT count(*) FROM reservation WHERE seat_id = ? AND status = 'CONFIRMED'", Int::class.java, seatId,
)!!

fun JdbcTemplate.quotaCnt(scheduleId: Long, userId: Long): Int? = queryForList(
    "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, scheduleId, userId,
).singleOrNull()

/** 행의 마지막 쓰기 트랜잭션 — 같으면 그 사이 아무도 이 좌석 행을 UPDATE하지 않았다(FOR UPDATE 잠금은 xmin을 바꾸지 않는다). */
fun JdbcTemplate.seatXmin(seatId: Long): String =
    queryForObject("SELECT xmin::text FROM product_seat WHERE id = ?", String::class.java, seatId)!!

/**
 * 카운터 정합(명세 §2 · §10 R2 — 대조 정의 그대로): 열린 회차(starts_at > DB now())의 모든 쌍에서 cnt = 홀드 행 수(만료 무관) + CONFIRMED.
 * 어긋난 쌍을 "회차/사용자 cnt≠실제" 문자열로 돌려준다(빈 목록 = 정합).
 */
fun JdbcTemplate.counterMismatches(): List<String> = queryForList(
    """
    WITH actual AS (
        SELECT schedule_id, user_id, sum(n) AS total FROM (
            SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
            UNION ALL
            SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2
        ) u GROUP BY 1, 2
    ),
    pairs AS (
        SELECT schedule_id, user_id, coalesce(q.cnt, 0) AS cnt, coalesce(a.total, 0) AS total
        FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id)
    )
    SELECT p.schedule_id || '/' || p.user_id || ' cnt=' || p.cnt || ' actual=' || p.total
    FROM pairs p JOIN product_schedule s ON s.id = p.schedule_id
    WHERE s.starts_at > now() AND p.cnt <> p.total
    ORDER BY 1
    """.trimIndent(),
    String::class.java,
)

/** 이중 판매(§8.3 1번째 행 · N2): 좌석당 (활성 홀드 + 확정) ≥ 2 또는 확정 ≥ 2인 좌석 id. */
fun JdbcTemplate.doubleSaleSeats(): List<Long> = queryForList(
    """
    SELECT st.id FROM product_seat st
    WHERE (SELECT count(*) FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now())
        + (SELECT count(*) FROM reservation r WHERE r.seat_id = st.id AND r.status = 'CONFIRMED') > 1
       OR (SELECT count(*) FROM reservation r WHERE r.seat_id = st.id AND r.status = 'CONFIRMED') > 1
    ORDER BY st.id
    """.trimIndent(),
    Long::class.java,
)

/** §8.3 7번째 행 '활성 홀드가 있는데 HELD 아님'(B·C 기대 0 · N6). */
fun JdbcTemplate.activeHoldNotHeld(): Int = queryForObject(
    """
    SELECT count(*) FROM product_seat st WHERE st.status <> 'HELD'
      AND EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now())
    """.trimIndent(),
    Int::class.java,
)!!

// ── 방식 전환 정리 · 확인 3항목(§설계-7) ─────────────────────────────────────────────────────────────
// 메인 수정(코드 리뷰 CR14-7): 문서 SQL을 여기 옮겨 적던 것을 운영 SQL 파일(ops/expiry-mode-cutover/ — README 사용 순서)을 읽어 돌리게 바꿨다 —
// 운영자가 돌리는 문장과 이 계약 테스트가 보는 문장이 둘로 갈라지지 않게. 확인 3항목도 같은 파일(02-verify.sql — 판정기 정의 그대로)에서 읽는다.

/** 저장소의 ops/expiry-mode-cutover/[name] — Gradle 테스트 작업 디렉터리(main-app)에서 위로 올라가며 찾는다 */
fun cutoverOpsFile(name: String): File =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "ops/expiry-mode-cutover/$name") }
        .firstOrNull { it.isFile }
        ?: error("ops/expiry-mode-cutover/$name 을 찾지 못했다(user.dir=${System.getProperty("user.dir")})")

/**
 * psql 파일의 문장 목록 — `--` 주석을 지우고 `;`로 나눈 뒤 BEGIN · COMMIT은 뺀다(트랜잭션은 부르는 쪽이 쥔다).
 * [vars] = psql 변수(`-v 이름=값`) — `:이름` 자리를 값으로 바꾼다. 주지 않은 변수는 psql처럼 그대로 남아 문법 오류가 된다.
 */
fun psqlStatements(file: File, vars: Map<String, String> = emptyMap()): List<String> {
    var text = file.readLines().joinToString("\n") { it.substringBefore("--") }
    vars.forEach { (k, v) -> text = text.replace(":$k", v) }
    return text.split(';').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("BEGIN", true) && !it.equals("COMMIT", true) }
}

/** 01-cleanup.sql 변수 quota_touch — 증분 method stmt의 값(README). none · trigger는 빈 문자열 */
const val QUOTA_TOUCH_STMT = ", updated_at = now()"

/** 01-cleanup.sql의 T1 · T2([quotaTouch] = 변수 quota_touch) */
fun cutoverStatements(quotaTouch: String = ""): List<String> =
    psqlStatements(cutoverOpsFile("01-cleanup.sql"), mapOf("quota_touch" to quotaTouch))
        .also { check(it.size == 2) { "01-cleanup.sql은 T1 · T2 두 문장이어야 한다: $it" } }

/** §설계-7 T1(남은 만료 홀드 삭제 + 열린 회차 쌍별 감소) — 운영 파일 그대로(증분 method none — updated_at 절 없음). */
val CUTOVER_T1: String get() = cutoverStatements()[0]

/** §설계-7 T2(좌석 기준 재계산) — 운영 파일 그대로. */
val CUTOVER_T2: String get() = cutoverStatements()[1]

/** 02-verify.sql의 확인 3항목(v_held_without_hold · v_duplicate_hold_seats · v_counter_mismatch). 앱이 내려간 상태에서 DB로 직접 돌리므로 마감 판정 시각 = DB now(). */
fun JdbcTemplate.cutoverVerify(): Map<String, Int> =
    queryForMap(psqlStatements(cutoverOpsFile("02-verify.sql")).single()).mapValues { (_, v) -> (v as Number).toInt() }

fun JdbcTemplate.vHeldWithoutHold(): Int = cutoverVerify().getValue("v_held_without_hold")

fun JdbcTemplate.vDuplicateHoldSeats(): Int = cutoverVerify().getValue("v_duplicate_hold_seats")

/** 판정기 v_counter_mismatch — 마감 회차(starts_at <= 시각)는 뺀다. */
fun JdbcTemplate.vCounterMismatch(): Int = cutoverVerify().getValue("v_counter_mismatch")

/** 정지 상태에서 T1·T2를 한 트랜잭션으로(§설계-7). */
fun Connection.runCutover() {
    val auto = autoCommit
    autoCommit = false
    try {
        createStatement().use { it.executeUpdate(CUTOVER_T1) }
        createStatement().use { it.executeUpdate(CUTOVER_T2) }
        commit()
    } catch (e: Throwable) {
        rollback()
        throw e
    } finally {
        autoCommit = auto
    }
}

/** 블록 결과 = "ok" 또는 에러 코드 이름(그 밖의 예외는 "ERR:…") — HoldRace.kt와 같은 표기. */
inline fun outcomeOf(block: () -> Unit): String = try {
    block(); "ok"
} catch (e: SeatReservationException) {
    e.errorCode.name
} catch (e: AssertionError) {
    throw e
} catch (e: Throwable) {
    "ERR:${e::class.simpleName}:${e.message?.take(160)}"
}

/** [n]개 작업을 래치로 한꺼번에 출발시킨다. 결과는 작업 순서대로. */
fun <T> ExecutorService.together(n: Int, timeoutSeconds: Long = 60, task: (Int) -> T): List<T> {
    val ready = CountDownLatch(n)
    val start = CountDownLatch(1)
    val futures = (0 until n).map { i ->
        submit<T> {
            ready.countDown()
            start.await()
            task(i)
        }
    }
    check(ready.await(timeoutSeconds, TimeUnit.SECONDS)) { "작업 $n 개가 출발선에 서지 못했다" }
    start.countDown()
    return futures.map { it.get(timeoutSeconds, TimeUnit.SECONDS) }
}
