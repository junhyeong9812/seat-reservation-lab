package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.api.REJECT_HEADER
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.impl.hold.sqlStateOf
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-014 손잡이를 주지 않은 업무 앱(sweep — A) — 기본값 sweep · 좌석 맵 A 문장(저장 상태 그대로) · 판정기 새 항목은 기존 `v_*` 밖 ·
 * 측정 프로필의 선점 409 원인 표지(헤더 + 지표 — 상태 코드·본문은 그대로 · 확정 409에는 없다).
 */
@ActiveProfiles("loadtest")
@AutoConfigureMockMvc
class Adr014ImplSweepTest : Adr014ImplSupport() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var meterRegistry: MeterRegistry

    private fun rejected(cause: String): Double = meterRegistry.find("seat.hold.rejected").tag("cause", cause).counter()?.count() ?: 0.0

    @Test
    fun `키를 주지 않으면 sweep — 청크 기본 500`() {
        assertEquals(ExpiryMode.SWEEP, properties.expiryMode)
        assertEquals(500, properties.expiryBatchSize)
    }

    @Test
    fun `좌석 맵 A — 저장 상태 그대로 · 좌석 순서 · 회차 없음 404`() {
        val a = seat()
        val h = seat(SeatStatus.HELD)
        dbHold(h, 1, -1.0) // 만료됐는데 배치 전 — A는 HELD로 보인다
        val r = seat(SeatStatus.RESERVED)
        mockMvc.get("/api/schedules/$s/seats").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(3) }
            jsonPath("$[0].seatId") { value(a) }
            jsonPath("$[0].section") { value("A") }
            jsonPath("$[0].rowNo") { value(1) }
            jsonPath("$[0].seatNo") { exists() }
            jsonPath("$[0].status") { value("AVAILABLE") }
            jsonPath("$[1].status") { value("HELD") }
            jsonPath("$[2].seatId") { value(r) }
            jsonPath("$[2].status") { value("RESERVED") }
        }
        mockMvc.get("/api/schedules/${s + 999}/seats").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("SEAT_NOT_FOUND") }
        }
    }

    @Test
    fun `좌석이 없는 회차는 빈 목록`() {
        mockMvc.get("/api/schedules/$s/seats").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `측정 프로필 선점 409 — 판정 거절은 rule · NOWAIT 패배는 nowait · 상태와 본문은 그대로 · 지표가 같은 갈래를 센다(MR-5 · #22)`() {
        val seat = seat()
        val ruleBefore = rejected("rule")
        val nowaitBefore = rejected("nowait")
        mockMvc.post("/api/schedules/$s/seats/$seat/hold") { header("X-User-Id", "1") }.andExpect { status { isCreated() } }
        mockMvc.post("/api/schedules/$s/seats/$seat/hold") { header("X-User-Id", "2") }.andExpect {
            status { isConflict() }
            header { string(REJECT_HEADER, "rule") }
            jsonPath("$.code") { value("SEAT_NOT_AVAILABLE") }
            jsonPath("$.message") { value("선점할 수 없는 좌석입니다.") }
        }
        val locked = seat()
        rawConnection().use { other ->
            other.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", locked)
            mockMvc.post("/api/schedules/$s/seats/$locked/hold") { header("X-User-Id", "3") }.andExpect {
                status { isConflict() }
                header { string(REJECT_HEADER, "nowait") }
                jsonPath("$.code") { value("SEAT_NOT_AVAILABLE") }
            }
            other.rollback()
        }
        assertEquals(1.0, rejected("rule") - ruleBefore)
        assertEquals(1.0, rejected("nowait") - nowaitBefore)
    }

    @Test
    fun `확정 409에는 원인 표지가 없다`() {
        val seat = seat()
        val holdId = hold(seat, 1).holdId
        clock.advance(java.time.Duration.ofMinutes(10)) // A는 앱 Clock으로 만료(R1)
        val result = mockMvc.post("/api/holds/$holdId/confirm") {
            header("X-User-Id", "1")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"paymentUid":"p-1"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("HOLD_EXPIRED") }
        }.andReturn()
        assertNull(result.response.getHeader(REJECT_HEADER))
    }

    @Test
    fun `판정기 새 항목은 v_ 접두어 밖 — 기존 v_ 항목 값은 그대로`() {
        val seat = seat()
        dbHold(seat, 1, 300.0) // HELD 아닌 좌석의 활성 홀드 — 기존 v_hold_on_unheld_seat 1 · 새 active_not_held 1
        val r = judge()
        assertEquals("sweep", r["expiry_mode"])
        assertEquals(1L, (r["v_hold_on_unheld_seat"] as Number).toLong())
        assertEquals(1L, (r["active_not_held"] as Number).toLong())
        val newKeys = setOf("double_sold_seats", "multi_active_seats", "held_no_active", "active_not_held", "active_on_reserved_seats", "expired_hold_rows")
        assertEquals(newKeys, r.keys.filter { it in newKeys }.toSet())
    }

    @Test
    fun `방식 전환 확인(ops 02-verify)은 판정기 같은 이름 항목과 같은 값이다(CR14-7)`() {
        val noHold = seat(SeatStatus.HELD) // HELD인데 홀드 행 없음
        val dup = seat(SeatStatus.HELD)
        dbHold(dup, 1, -1.0)
        dbHold(dup, 2, 300.0) // 좌석당 홀드 행 2
        quota(s, 1, 1)
        quota(s, 2, 5) // 실제 1인데 5 — 어긋남
        val verify = jdbcTemplate.cutoverVerify()
        val r = judge()
        assertEquals(listOf("v_held_without_hold", "v_duplicate_hold_seats", "v_counter_mismatch"), verify.keys.toList())
        assertEquals(verify, verify.keys.associateWith { (r.getValue(it) as Number).toInt() }, "판정기와 같은 정의")
        assertEquals(listOf(1, 1, 1), verify.values.toList())
        assertEquals("HELD", status(noHold))
    }

    @Test
    fun `방식 전환 정리(ops 01-cleanup) — 증분 stmt 변형은 감소한 쌍에만 updated_at = now()를 함께 쓴다 · 변수를 주지 않으면 문법 오류(CR14-7)`() {
        val a = seat(SeatStatus.HELD)
        val b = seat(SeatStatus.HELD)
        dbHold(a, 1, -1.0)
        dbHold(b, 2, 300.0)
        quota(s, 1, 1)
        quota(s, 2, 1)
        rawConnection().use { c ->
            // 이 컨텍스트의 스키마(기본 위치 V1~V7)에는 updated_at이 없다 — 이 트랜잭션 안에서만 V9 열을 더하고 끝에 되돌린다
            c.exec("ALTER TABLE user_hold_quota ADD COLUMN updated_at timestamptz NOT NULL DEFAULT TIMESTAMPTZ '2000-01-01 00:00:00+00'")
            val (t1, t2) = cutoverStatements(QUOTA_TOUCH_STMT)
            c.createStatement().use { it.executeUpdate(t1) }
            c.createStatement().use { it.executeUpdate(t2) }
            val touched = c.prepareStatement("SELECT user_id, cnt, updated_at > TIMESTAMPTZ '2000-01-02 00:00:00+00' FROM user_hold_quota WHERE schedule_id = ? ORDER BY user_id").use { ps ->
                ps.setLong(1, s)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) Triple(rs.getLong(1), rs.getInt(2), rs.getBoolean(3)) else null }.toList() }
            }
            assertEquals(listOf(Triple(1L, 0, true), Triple(2L, 1, false)), touched, "감소한 쌍(사용자 1)만 updated_at을 쓴다")
            c.rollback()
        }
        assertEquals(1, cntOf(s, 1), "되돌렸다")
        val raw = psqlStatements(cutoverOpsFile("01-cleanup.sql")).filterNot(::isQuiesceCheck) // 변수 없음 — psql처럼 :quota_touch가 남는다
        val e = runCatching { jdbcTemplate.update(raw[0]) }.exceptionOrNull()
        assertEquals("42601", e?.let(::sqlStateOf), "문법 오류로 멈춘다: $e")
        assertEquals(1, holdRows(a), "아무것도 바뀌지 않았다")
    }

    @Test
    fun `방식 전환 정리(ops 01-cleanup) — 같은 DB에 다른 세션(이 컨텍스트의 Hikari 풀)이 있으면 정지 상태 검사가 55006으로 멈춘다 · 아무것도 바뀌지 않는다(CR14-16)`() {
        val a = seat(SeatStatus.HELD)
        dbHold(a, 1, -1.0)
        quota(s, 1, 1)
        jdbcTemplate.queryForObject("SELECT 1", Int::class.java) // 풀 연결 하나는 열려 있다(다른 세션)
        rawConnection().use { c ->
            assertTrue(c.otherClientSessions() > 0, "전제: 같은 DB에 다른 클라이언트 세션")
            val e = runCatching { c.createStatement().use { it.execute(CUTOVER_QUIESCE_CHECK) } }.exceptionOrNull()
            assertEquals("55006", e?.let(::sqlStateOf), "정지 상태 검사가 멈춘다: $e")
            assertTrue(e!!.message!!.contains("다른 세션"), e.message)
            c.rollback()
        }
        assertEquals(1, holdRows(a), "아무것도 바뀌지 않았다")
        assertEquals(1, cntOf(s, 1))
    }
}

/** 기본 프로필(loadtest 아님)에는 원인 표지가 없다 */
@AutoConfigureMockMvc
class Adr014ImplRejectHeaderDefaultProfileTest : Adr014ImplSupport() {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `기본 프로필 선점 409 — 헤더 없음`() {
        val seat = seat()
        mockMvc.post("/api/schedules/$s/seats/$seat/hold") { header("X-User-Id", "1") }.andExpect { status { isCreated() } }
        val result = mockMvc.post("/api/schedules/$s/seats/$seat/hold") { header("X-User-Id", "2") }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SEAT_NOT_AVAILABLE") }
        }.andReturn()
        assertNull(result.response.getHeader(REJECT_HEADER))
    }
}
