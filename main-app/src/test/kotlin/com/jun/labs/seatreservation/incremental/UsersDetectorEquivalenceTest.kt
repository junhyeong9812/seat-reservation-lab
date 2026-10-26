package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.drift.DriftTestSupport
import com.jun.labs.seatreservation.service.impl.drift.ChunkDetector
import com.jun.labs.seatreservation.service.impl.drift.QuotaSelector
import com.jun.labs.seatreservation.support.insertReservationRow
import com.jun.labs.seatreservation.support.insertSeatRow
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 테스트 17 — 탐지 모양 동등성(증분 — §4.2.4 'Users' 선택자 · §4.1 계약을 Users로 넓힌 것):
 * 같은 스냅숏에서 Users 선택자의 one-shot·split·group이 같은 어긋난 쌍 집합(쌍 · cnt · 실제 값)을 낸다 · 쿼터 행 없는 사용자는 결과에 없다(scanned = 찾은 행 수).
 * 동등성 = 모양별 컨텍스트에서 같은 시나리오 → 손 계산 같은 정답(DetectShapeEquivalenceTest와 같은 방식). ChunkDetector는 손잡이에 맞는 빈 하나(가정 S7).
 * API(계약 §4.2.4 코드 그대로): detect(scheduleId, QuotaSelector.Users(LongArray)) → DetectedChunk(scanned, lastUser, mismatches: List<PairObservation(scheduleId, userId, cnt, actual)>).
 */
abstract class UsersDetectorScenario : DriftTestSupport() {

    @Autowired lateinit var detector: ChunkDetector
    @Autowired lateinit var dataSource: DataSource

    private var seatNo = 8_000
    private fun reservation(sc: Long, u: Long, status: String = "CONFIRMED") =
        jdbcTemplate.insertReservationRow(sc, jdbcTemplate.insertSeatRow(sc, ++seatNo), u, status)

    private fun detect(sc: Long, vararg users: Long): Pair<Int, Set<List<Any?>>> {
        val chunk = detector.detect(sc, QuotaSelector.Users(users.sortedArray()))
        return chunk.scanned to chunk.mismatches.map { listOf(it.scheduleId, it.userId, it.cnt, it.actual) }.toSet()
    }

    @Test
    fun `17 — Users 선택 — 어긋난 쌍 집합 · scanned = 쿼터 행이 있는 사용자 수 · 행 없는 사용자 제외`() {
        val s1 = schedule.id!!
        val s2 = newSchedule(SCHEDULE_STARTS_AT.plusSeconds(86_400)).id!!
        quota(s1, 101, 2); rawHold(s1, 101)                                     // above (2, 1)
        quota(s1, 102, 0); rawHold(s1, 102); reservation(s1, 102)               // below (0, 2)
        quota(s1, 103, 0)                                                       // 맞음 — cnt 0 행
        quota(s1, 104, 1); reservation(s1, 104); reservation(s1, 104, "CANCELLED")   // 맞음 — 비CONFIRMED 안 셈
        quota(s1, 105, 2); reservation(s1, 105); reservation(s1, 105, "PENDING")     // above (2, 1)
        quota(s1, 106, 1); quota(s2, 106, 2); rawHold(s2, 106); rawHold(s2, 106)     // s1 above (1, 0) — 다른 회차 홀드 안 셈
        rawHold(s1, 107)                                                        // 쿼터 행 없음 — 결과에 없다(행 없음 갈래 몫)
        quota(s1, 109, 1); rawHold(s1, 109, expiresAt = NOW.minusSeconds(3_600))     // 맞음 — expires_at으로 거르지 않음

        val (scanned, mismatches) = detect(s1, 101, 102, 103, 104, 105, 106, 107, 108, 109)

        assertEquals(7, scanned, "쿼터 행이 있는 사용자 = 101~106 · 109")
        assertEquals(
            setOf(listOf<Any?>(s1, 101L, 2, 1), listOf<Any?>(s1, 102L, 0, 2), listOf<Any?>(s1, 105L, 2, 1), listOf<Any?>(s1, 106L, 1, 0)),
            mismatches,
        )
        assertEquals(0 to emptySet<List<Any?>>(), detect(s1, 107, 108), "행 없는 사용자만 고르면 빈 결과")
        assertEquals(1 to emptySet<List<Any?>>(), detect(s2, 106), "s2의 106은 맞다(2 vs 2)")
    }

    @Test
    fun `17 — 한 스냅숏 — 쿼터 조회 뒤 홀드 조회 전에 커밋된 (홀드 +1 · cnt +1)은 섞이지 않는다(오탐 0)`() {
        val sc = schedule.id!!
        quota(sc, 501, 1); rawHold(sc, 501)
        quota(sc, 502, 3); rawHold(sc, 502)                                     // 진짜 어긋남
        val spare = jdbcTemplate.insertSeatRow(sc, ++seatNo)
        val h = dataSource.unwrap(HikariDataSource::class.java)
        val pool = Executors.newSingleThreadExecutor()
        try {
            DriverManager.getConnection(h.jdbcUrl, h.username, h.password).use { lock ->
                lock.autoCommit = false
                lock.exec("LOCK TABLE seat_hold IN ACCESS EXCLUSIVE MODE")
                val running = pool.submit<Pair<Int, Set<List<Any?>>>> { detect(sc, 501, 502) }
                jdbcTemplate.awaitBlockedBy(lock.pid(), timeout = Duration.ofSeconds(15))
                lock.exec(
                    "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                    spare, sc, 501L, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(300)),
                )
                lock.exec("UPDATE user_hold_quota SET cnt = 2 WHERE schedule_id = ? AND user_id = ?", sc, 501L)
                lock.commit()
                val (_, mismatches) = running.get(30, TimeUnit.SECONDS)
                assertEquals(setOf(listOf<Any?>(sc, 502L, 3, 1)), mismatches, "스냅숏이 갈라지면 501이 (1, 2)로 잡힌다")
            }
        } finally {
            pool.shutdownNow()
        }
        assertTrue(jdbcTemplate.queryForList("SELECT 1 FROM counter_drift_log").isEmpty(), "탐지는 고치지 않는다(기록 없음)")
    }
}

@TestPropertySource(properties = ["seat.hold.counter-drift.reconcile.detect-shape=one-shot"])
class UsersDetectorOneShotTest : UsersDetectorScenario()

@TestPropertySource(properties = ["seat.hold.counter-drift.reconcile.detect-shape=split"])
class UsersDetectorSplitTest : UsersDetectorScenario()

@TestPropertySource(properties = ["seat.hold.counter-drift.reconcile.detect-shape=group"])
class UsersDetectorGroupTest : UsersDetectorScenario()
