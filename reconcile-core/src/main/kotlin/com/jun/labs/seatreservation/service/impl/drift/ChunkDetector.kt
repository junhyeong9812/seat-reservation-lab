package com.jun.labs.seatreservation.service.impl.drift

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * ① 쿼터 행 청크를 고르는 법(ADR-012 §4.2.4).
 * [Keyset] = 회차의 쿼터 행 PK keyset k개(전체 대조 ① · 안전망 전체 커서 ①) · [Users] = 정렬된 사용자 목록(크기 ≤ k — 증분 · 안전망 최근 창).
 */
sealed interface QuotaSelector {
    data class Keyset(val afterUser: Long, val k: Int) : QuotaSelector
    class Users(val users: LongArray) : QuotaSelector
}

/** 탐지가 본 쌍 하나 — cnt = null은 쿼터 행 없음(행 없음 갈래), actual = 홀드 + CONFIRMED. */
data class PairObservation(val scheduleId: Long, val userId: Long, val cnt: Int?, val actual: Int)

/** 청크 하나의 결과 — scanned = 찾은 쿼터 행 수 · lastUser = 다음 keyset 커서(0행이면 받은 커서 그대로) · mismatches = 어긋난 쌍. */
class DetectedChunk(val scanned: Int, val lastUser: Long, val mismatches: List<PairObservation>)

/**
 * 청크 탐지(ADR-012 §4.2.4 계약 — 모양 무관): 청크 하나 = 한 스냅숏 = 빌림 1(one-shot은 한 문장, split·group은 탐지 전용 읽기 전용 REPEATABLE READ 트랜잭션) ·
 * 실패하면 예외(부분 결과 없음) · [QuotaSelector.Users]에서 쿼터 행이 없는 사용자는 결과에 없다 · 실제 값 = 홀드 + CONFIRMED · `toIntExact`.
 * 빌림 수는 호출자가 센다(이 함수는 커넥션을 정확히 한 번 빌린다). [QuotaSelector.Users]는 비어 있으면 안 된다(호출자가 거른다).
 */
interface ChunkDetector {
    fun detect(scheduleId: Long, selector: QuotaSelector): DetectedChunk
}

/** 모양 손잡이 `detect-shape`에 맞는 탐지기 하나를 빈으로 둔다 — 전체 대조·증분·안전망이 같은 빈을 쓴다(R8 승자를 값으로 고르면 끝). */
@Configuration(proxyBeanMethods = false)
class ChunkDetectorConfig {
    @Bean
    fun chunkDetector(jdbc: JdbcTemplate, tx: TransactionTemplate, properties: ReconcileProperties): ChunkDetector = when (properties.detectShape) {
        DetectShape.ONE_SHOT -> OneShotDetector(jdbc)
        DetectShape.SPLIT -> SplitDetector(jdbc, tx)
        DetectShape.GROUP -> GroupDetector(jdbc, tx)
    }
}

/** one-shot — 한 문장(자동 커밋). Keyset = 지금 `CHUNK_SQL` 그대로(기본 경로 문장 무변경), Users = `q` CTE만 `user_id = ANY(?)`로 바뀐 새 문장. */
class OneShotDetector(private val jdbc: JdbcTemplate) : ChunkDetector {
    override fun detect(scheduleId: Long, selector: QuotaSelector): DetectedChunk {
        val (rows, after) = when (selector) {
            is QuotaSelector.Keyset -> jdbc.queryForList(CHUNK_SQL, scheduleId, selector.afterUser, selector.k, scheduleId, scheduleId) to selector.afterUser
            is QuotaSelector.Users -> {
                require(selector.users.isNotEmpty()) { "빈 사용자 청크" }
                val ids = selector.users.toTypedArray()
                jdbc.query(
                    PreparedStatementCreator { con ->
                        con.prepareStatement(CHUNK_USERS_SQL).apply {
                            setLong(1, scheduleId)
                            setArray(2, con.createArrayOf("bigint", ids))
                            setLong(3, scheduleId)
                            setLong(4, scheduleId)
                        }
                    },
                    org.springframework.jdbc.core.ColumnMapRowMapper(),
                ) to Long.MIN_VALUE
            }
        }
        val head = rows.first()
        val scanned = (head["scanned"] as Number).toInt()
        val mismatches = rows.filter { it["user_id"] != null }.map { r ->
            PairObservation(scheduleId, (r["user_id"] as Number).toLong(), (r["cnt"] as Number).toInt(), (r["actual"] as Number).toInt())
        }
        val last = (head["last_user"] as Number?)?.toLong() ?: after
        return DetectedChunk(scanned, last, mismatches)
    }

    companion object {
        /** 설계 `chunk-v2.sql` — 파라미터: 회차, after, k, 회차(홀드), 회차(예약). **기본 경로 문장 — 한 글자도 바꾸지 않는다**(ADR-012 §4.2 공통 원칙 ②) */
        internal val CHUNK_SQL = """
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
        """.trimIndent()

        /** ADR-012 §4.2.4 Users — `CHUNK_SQL`의 `q` CTE만 사용자 배열로. 파라미터: 회차, U(bigint[]), 회차(홀드), 회차(예약) */
        internal val CHUNK_USERS_SQL = """
            WITH q AS (
              SELECT user_id, cnt FROM user_hold_quota
              WHERE schedule_id = ? AND user_id = ANY(?) ORDER BY user_id),
            x AS (
              SELECT q.user_id, q.cnt,
                     ((SELECT count(*) FROM seat_hold h WHERE h.user_id = q.user_id AND h.schedule_id = ?)
                    + (SELECT count(*) FROM reservation r WHERE r.schedule_id = ? AND r.user_id = q.user_id AND r.status = 'CONFIRMED'))::int AS actual
              FROM q)
            SELECT (SELECT max(user_id) FROM q) AS last_user, (SELECT count(*) FROM q) AS scanned, d.user_id, d.cnt, d.actual
            FROM (SELECT 1) one LEFT JOIN (SELECT * FROM x WHERE cnt <> actual) d ON true
        """.trimIndent()
    }
}

/**
 * split·group 공통(ADR-012 §4.1) — 청크마다 탐지 전용 트랜잭션 하나(빌림 1): ⓐ 쿼터 행(keyset k개 또는 사용자 배열) ⓑ 그 사용자들의 홀드 ⓒ CONFIRMED 예약을 읽고 앱이 센다.
 * ⓐ가 0행이면 ⓑⓒ 없이 끝(청크 1개로 센다) · 다음 커서 = ⓐ의 마지막 user_id · 어느 조회·집계든 실패하면 예외(부분 결과 없음).
 * 주입받은 [tx](앱 공용 — 복구 트랜잭션)는 바꾸지 않는다 — 탐지 전용 템플릿을 따로 둔다(복구는 READ COMMITTED여야 잠근 뒤 최신 값을 본다).
 */
abstract class AppAggregateDetector(
    private val jdbc: JdbcTemplate,
    tx: TransactionTemplate,
    private val shape: DetectShape,
) : ChunkDetector {
    private val detectTx = TransactionTemplate(requireNotNull(tx.transactionManager) { "TransactionTemplate에 트랜잭션 매니저가 없다" }).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    override fun detect(scheduleId: Long, selector: QuotaSelector): DetectedChunk = detectTx.execute { detectInTx(scheduleId, selector) }!!

    /** 탐지 트랜잭션 안 — ⓐ ⓑ ⓒ + 집계. 메모리는 청크의 사용자 카운터뿐(ⓑⓒ는 U 밖 사용자를 돌려주지 않는다). */
    private fun detectInTx(scheduleId: Long, selector: QuotaSelector): DetectedChunk {
        val users = ArrayList<Long>()
        val cnts = ArrayList<Int>()
        val after: Long
        val fetch: Int
        when (selector) {
            is QuotaSelector.Keyset -> {
                after = selector.afterUser
                fetch = selector.k
                jdbc.query(QUOTA_KEYSET_SQL, RowCallbackHandler { rs ->
                    users += rs.getLong(1)
                    cnts += rs.getInt(2)
                }, scheduleId, selector.afterUser, selector.k)
            }
            is QuotaSelector.Users -> {
                require(selector.users.isNotEmpty()) { "빈 사용자 청크" }
                after = Long.MIN_VALUE
                fetch = selector.users.size
                val ids = selector.users.toTypedArray()
                jdbc.query(
                    PreparedStatementCreator { con ->
                        con.prepareStatement(QUOTA_USERS_SQL).apply {
                            setLong(1, scheduleId)
                            setArray(2, con.createArrayOf("bigint", ids))
                        }
                    },
                    RowCallbackHandler { rs ->
                        users += rs.getLong(1)
                        cnts += rs.getInt(2)
                    },
                )
            }
        }
        val scanned = users.size
        if (scanned == 0) return DetectedChunk(0, after, emptyList())

        val index = HashMap<Long, Int>(scanned * 2)
        for (i in 0 until scanned) index[users[i]] = i
        val actual = LongArray(scanned)
        val (holdSql, reservationSql) = when (shape) {
            DetectShape.SPLIT -> SPLIT_HOLD_SQL to SPLIT_RESERVATION_SQL
            DetectShape.GROUP -> GROUP_HOLD_SQL to GROUP_RESERVATION_SQL
            DetectShape.ONE_SHOT -> error("one-shot은 이 경로를 쓰지 않는다")
        }
        val ids = users.toTypedArray()
        for (sql in listOf(holdSql, reservationSql)) {
            jdbc.query(
                PreparedStatementCreator { con ->
                    con.prepareStatement(sql).apply {
                        // split은 행을 커서로 흘려 읽는다(pgJDBC 기본 = 결과 전체를 한 번에 받음 — 트랜잭션 안이라 커서가 성립한다)
                        if (shape == DetectShape.SPLIT) fetchSize = fetch
                        setLong(1, scheduleId)
                        setArray(2, con.createArrayOf("bigint", ids))
                    }
                },
                RowCallbackHandler { rs ->
                    val i = index[rs.getLong(1)] ?: error("ⓑⓒ가 청크 밖 사용자 ${rs.getLong(1)}를 돌려줬다")
                    actual[i] += if (shape == DetectShape.GROUP) rs.getLong(2) else 1L
                },
            )
        }
        val mismatches = ArrayList<PairObservation>()
        for (i in 0 until scanned) {
            // 실제 값은 int로 — 넘치면 오류(one-shot의 `::int`와 같은 실패 → 청크 버림)
            val a = Math.toIntExact(actual[i])
            if (cnts[i] != a) mismatches += PairObservation(scheduleId, users[i], cnts[i], a)
        }
        return DetectedChunk(scanned, users[scanned - 1], mismatches)
    }

    companion object {
        /** R8 ⓐ(split·group 공통) — 회차의 쿼터 행 keyset k개. 파라미터: 회차, after, k */
        private val QUOTA_KEYSET_SQL = """
            SELECT user_id, cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id > ? ORDER BY user_id LIMIT ?
        """.trimIndent()

        /** ADR-012 §4.2.4 Users ⓐ — 사용자 배열. 파라미터: 회차, U(bigint[]) */
        private const val QUOTA_USERS_SQL = "SELECT user_id, cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ANY(?) ORDER BY user_id"

        /** R8 ⓑⓒ split — 행 그대로(사용자별로 앱이 센다). 홀드는 expires_at으로 거르지 않는다(one-shot과 같음). 파라미터: 회차, U(bigint[]) */
        private const val SPLIT_HOLD_SQL = "SELECT user_id FROM seat_hold WHERE schedule_id = ? AND user_id = ANY(?)"
        private const val SPLIT_RESERVATION_SQL = "SELECT user_id FROM reservation WHERE schedule_id = ? AND user_id = ANY(?) AND status = 'CONFIRMED'"

        /** R8 ⓑⓒ group — 같은 조건의 사용자별 개수 */
        private const val GROUP_HOLD_SQL = "SELECT user_id, count(*) FROM seat_hold WHERE schedule_id = ? AND user_id = ANY(?) GROUP BY user_id"
        private const val GROUP_RESERVATION_SQL =
            "SELECT user_id, count(*) FROM reservation WHERE schedule_id = ? AND user_id = ANY(?) AND status = 'CONFIRMED' GROUP BY user_id"
    }
}

class SplitDetector(jdbc: JdbcTemplate, tx: TransactionTemplate) : AppAggregateDetector(jdbc, tx, DetectShape.SPLIT)

class GroupDetector(jdbc: JdbcTemplate, tx: TransactionTemplate) : AppAggregateDetector(jdbc, tx, DetectShape.GROUP)
