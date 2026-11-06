package com.jun.labs.seatreservation.expiry

import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.api.USER_ID_HEADER
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.incremental.awaitBlockedBy
import com.jun.labs.seatreservation.incremental.awaitDbTimeAfter
import com.jun.labs.seatreservation.incremental.dbNow
import com.jun.labs.seatreservation.incremental.exec
import com.jun.labs.seatreservation.incremental.pid
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpireHoldsUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatResult
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.support.IntegrationTest
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * ADR-014 B·C·D 공통 계약(blind — 구현 diff 미열람 · 기준 882fa2ea). 하위 클래스 셋(lazy · lazy-sweep · derived)이 `@TestPropertySource`로
 * 손잡이만 바꿔 같은 테스트를 돈다. 방식마다 다른 기대는 추상 값 셋([storedHeld] · [expiredOnAvailableSeatCode] · [modeValue])으로만 갈린다.
 * 계약 출처: §설계-2(공통 규칙) · §3.1(선점 H0~H10) · §3.2(확정 K1~K7 · DS14-1 판정 순서) · §3.3(좌석 맵) · §설계-9 N1~N5 · N8 · §설계-10 #12.
 * 구현 전(손잡이 무시 → A 동작)에는 대부분 실패하고, 구현 뒤 통과해야 한다. A 경로 테스트는 Adr014SweepContractTest가 본다.
 */
@AutoConfigureMockMvc
abstract class Adr014ModeContractBase : IntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var confirmUseCase: ConfirmReservationUseCase
    @Autowired lateinit var expireHolds: ExpireHoldsUseCase
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var seatHoldProperties: SeatHoldProperties

    /** 손잡이 값(설정 문자열) — lazy · lazy-sweep · derived */
    abstract val modeValue: String

    /** 활성 홀드가 있는 좌석의 저장 상태 — B·C: HELD(힌트) · D: AVAILABLE(D는 HELD를 쓰지 않는다 — §설계-3 표) */
    abstract val storedHeld: SeatStatus

    /** 내 홀드 만료 + 저장 좌석 AVAILABLE의 확정 응답 — B·C: K2에서 `HELD` 아님 → SEAT_NOT_HELD · D: K2 통과 → K3 HOLD_EXPIRED(§3.2) */
    abstract val expiredOnAvailableSeatCode: String

    protected lateinit var pool: ExecutorService

    protected val s: Long get() = schedule.id!!

    @BeforeEach
    fun startPool() {
        pool = Executors.newCachedThreadPool()
    }

    @AfterEach
    fun stopPool() {
        pool.shutdownNow()
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────────────────

    private var seatSeq = 0

    fun seat(status: SeatStatus = SeatStatus.AVAILABLE): Long = createSeat(++seatSeq, status).id!!

    /** 남(또는 [userId])의 만료 홀드가 있는 좌석 — 저장 상태는 그 방식에서 홀드를 만든 뒤의 값([storedHeld]) */
    fun seatWithExpiredHold(userId: Long): Pair<Long, Long> {
        val seat = seat(storedHeld)
        return seat to jdbcTemplate.dbHold(seat, s, userId, "-1 minute")
    }

    fun seatWithActiveHold(userId: Long): Pair<Long, Long> {
        val seat = seat(storedHeld)
        return seat to jdbcTemplate.dbHold(seat, s, userId, "5 minutes")
    }

    fun tryHold(seatId: Long, userId: Long, scheduleId: Long = s): String =
        outcomeOf { holdSeat.hold(HoldSeatCommand(scheduleId, seatId, userId)) }

    fun hold(seatId: Long, userId: Long): HoldSeatResult = holdSeat.hold(HoldSeatCommand(s, seatId, userId))

    fun tryConfirm(holdId: Long, userId: Long): String =
        outcomeOf { confirmUseCase.confirm(ConfirmReservationCommand(holdId = holdId, userId = userId, paymentUid = "p-$holdId-$userId")) }

    fun cnt(userId: Long): Int? = jdbcTemplate.quotaCnt(s, userId)

    fun assertCounterConsistent(why: String) =
        assertEquals(emptyList(), jdbcTemplate.counterMismatches(), "카운터 = 홀드 행 수 + 확정이 깨졌다 — $why")

    fun assertNoDoubleSale(why: String) =
        assertEquals(emptyList(), jdbcTemplate.doubleSaleSeats(), "이중 판매(좌석당 활성 홀드 + 확정 ≥ 2) — $why")

    /** 앱 풀 밖 원시 연결(자동 커밋 끔) — 다른 트랜잭션이 쥔 잠금을 흉내 낸다. */
    fun raw(): Connection {
        val h = dataSource.unwrap(HikariDataSource::class.java)
        return DriverManager.getConnection(h.jdbcUrl, h.username, h.password).apply { autoCommit = false }
    }

    fun <T> Future<T>.within(seconds: Long = 15): T = get(seconds, TimeUnit.SECONDS)

    data class HttpResult(val status: Int, val code: String?, val body: String)

    private fun result(status: Int, body: String): HttpResult {
        val code = if (body.isBlank()) null else runCatching { objectMapper.readTree(body).get("code")?.asText() }.getOrNull()
        return HttpResult(status, code, body)
    }

    fun httpHold(seatId: Long, userId: Long): HttpResult =
        mockMvc.post("/api/schedules/$s/seats/$seatId/hold") { header(USER_ID_HEADER, userId) }
            .andReturn().response.let { result(it.status, it.contentAsString) }

    fun httpConfirm(holdId: Long, userId: Long): HttpResult =
        mockMvc.post("/api/holds/$holdId/confirm") {
            header(USER_ID_HEADER, userId)
            contentType = MediaType.APPLICATION_JSON
            content = """{"paymentUid":"pay-$holdId-$userId"}"""
        }.andReturn().response.let { result(it.status, it.contentAsString) }

    fun seatMapRaw(scheduleId: Long): HttpResult =
        mockMvc.get("/api/schedules/$scheduleId/seats").andReturn().response.let { HttpResult(it.status, null, it.contentAsString) }

    /** 좌석 맵(E3) → 좌석 id → 보이는 상태. 키 다섯 개 · seatId 오름차순도 함께 단언한다. */
    fun seatMap(scheduleId: Long = s): Map<Long, String> {
        val r = seatMapRaw(scheduleId)
        assertEquals(200, r.status, "좌석 맵 GET /api/schedules/$scheduleId/seats — 200이어야 한다: ${r.body.take(300)}")
        val node = objectMapper.readTree(r.body)
        assertTrue(node.isArray, "좌석 맵 응답은 배열이다(페이지 없음 — Q11): ${r.body.take(300)}")
        val ids = node.map { it.get("seatId").asLong() }
        assertEquals(ids.sorted(), ids, "좌석 맵은 seatId 오름차순이다(§3.3 ORDER BY id)")
        node.forEach { e ->
            assertEquals(setOf("seatId", "section", "rowNo", "seatNo", "status"), e.fieldNames().asSequence().toSet(), "좌석 맵 항목 키(누구의 홀드인지는 내지 않는다): $e")
        }
        return node.associate { it.get("seatId").asLong() to it.get("status").asText() }
    }

    fun holdExpiresAt(holdId: Long): Instant =
        jdbcTemplate.queryForObject("SELECT expires_at FROM seat_hold WHERE id = ?", Timestamp::class.java, holdId)!!.toInstant()

    // ── 손잡이 ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `손잡이 — expiry-mode 값이 SeatHoldProperties에 바인딩된다(새 필드 · 리플렉션 — E1)`() {
        val getter = runCatching { SeatHoldProperties::class.java.getMethod("getExpiryMode") }.getOrNull()
        assertTrue(getter != null, "SeatHoldProperties에 expiryMode 필드가 없다(§설계-6 'SeatHoldProperties의 새 필드')")
        assertEquals(modeValue, getter.invoke(seatHoldProperties).toString().lowercase().replace('_', '-'))
    }

    // ── N1: 만료 판정·기록은 DB now() 하나 ─────────────────────────────────────────────────────

    @Test
    fun `N1 — 앱 시계를 과거·미래로 옮겨도 선점 판정은 DB now() 기준(남의 만료 홀드 좌석 = 비어 있음 · 활성 홀드 좌석 = 409)`() {
        val outcomes = listOf(CLOCK_PAST, CLOCK_FUTURE).mapIndexed { i, at ->
            val (expiredSeat, _) = seatWithExpiredHold(9_000L + i)
            val (activeSeat, _) = seatWithActiveHold(9_100L + i)
            clock.set(at)
            listOf(tryHold(expiredSeat, 100L + i), tryHold(activeSeat, 200L + i))
        }
        assertEquals(listOf(listOf("ok", "SEAT_NOT_AVAILABLE"), listOf("ok", "SEAT_NOT_AVAILABLE")), outcomes, "앱 시계 과거 · 미래")
        assertCounterConsistent("N1 선점")
        assertNoDoubleSale("N1 선점")
    }

    @Test
    fun `N1 — 홀드의 held_at·expires_at과 선점 응답 expiresAt은 DB 시각 + TTL이다(앱 시계 무관)`() {
        clock.set(CLOCK_FUTURE)
        val seat = seat()
        val before = jdbcTemplate.dbNow()
        val r = hold(seat, 100)
        val after = jdbcTemplate.dbNow()
        val slack = Duration.ofSeconds(5)
        assertTrue(
            !r.expiresAt.isBefore(before.plus(TTL).minus(slack)) && !r.expiresAt.isAfter(after.plus(TTL).plus(slack)),
            "응답 expiresAt=${r.expiresAt} 는 DB now()+TTL(${before.plus(TTL)}~${after.plus(TTL)})이어야 한다 — 앱 시계(${CLOCK_FUTURE})+TTL이 아니다",
        )
        val row = holdRow(r.holdId)
        assertEquals(r.expiresAt, row.expiresAt, "응답 expiresAt = RETURNING expires_at(§2.1 #2)")
        assertEquals(TTL, Duration.between(row.heldAt, row.expiresAt), "expires_at = held_at + TTL(같은 문장의 now())")
        assertTrue(!row.heldAt.isBefore(before.minus(slack)) && !row.heldAt.isAfter(after.plus(slack)), "held_at=${row.heldAt} 는 DB 시각이다")
    }

    @Test
    fun `N1·N8 — 확정 판정도 DB now() 기준(앱 시계 과거여도 만료 홀드는 409 HOLD_EXPIRED · 미래여도 활성 홀드는 200)`() {
        val (expiredSeat, expiredHold) = seatWithExpiredHold(100)
        val (activeSeat, activeHold) = seatWithActiveHold(200)
        clock.set(CLOCK_PAST)
        assertEquals("HOLD_EXPIRED", tryConfirm(expiredHold, 100), "DB 기준 만료 홀드 확정(앱 시계 과거)")
        clock.set(CLOCK_FUTURE)
        assertEquals("ok", tryConfirm(activeHold, 200), "DB 기준 활성 홀드 확정(앱 시계 미래)")
        assertEquals("RESERVED", jdbcTemplate.storedStatus(activeSeat))
        assertEquals(storedHeld.name, jdbcTemplate.storedStatus(expiredSeat), "거부된 확정은 좌석을 바꾸지 않는다")
        assertTrue(jdbcTemplate.holdRowExists(expiredHold), "거부된 확정은 홀드 행을 지우지 않는다(트랜잭션 롤백)")
        assertCounterConsistent("확정")
    }

    // ── 가설 2 · N4: 선점 안 회수 → 매수 판정 ─────────────────────────────────────────────────

    @Test
    fun `가설 2 — 자기 좌석 재선점 — 만료된 자기 홀드를 회수하고 카운터를 내린 뒤 통과한다`() {
        val (seat, oldHold) = seatWithExpiredHold(100)
        assertEquals(1, cnt(100), "전제")
        assertEquals("ok", tryHold(seat, 100), "자기 만료 홀드 좌석 재선점")
        assertFalse(jdbcTemplate.holdRowExists(oldHold), "만료된 자기 홀드 행은 회수(삭제)된다(H4)")
        assertEquals(1, jdbcTemplate.activeHoldOf(seat, 100), "새 활성 홀드 1개")
        assertEquals(1, jdbcTemplate.holdRowsOfSeat(seat), "좌석에 홀드 행은 새 것 하나")
        assertEquals(1, cnt(100), "cnt = 1(회수 −1 · 선점 +1)")
        assertCounterConsistent("자기 좌석 재선점")
    }

    @Test
    fun `N4 — 매수 한도는 회수 뒤 판정한다(만료 홀드 2개 = 한도인 사용자가 새 좌석을 잡는다 · 그 뒤 한도는 그대로 막는다)`() {
        val (_, h1) = seatWithExpiredHold(100)
        val (_, h2) = seatWithExpiredHold(100)
        assertEquals(MAX_PER_USER, cnt(100), "전제: cnt = 한도")
        assertEquals("ok", tryHold(seat(), 100), "만료 홀드 2개 + 새 좌석")
        assertFalse(jdbcTemplate.holdRowExists(h1) || jdbcTemplate.holdRowExists(h2), "만료 홀드 2개 모두 회수")
        assertEquals(1, cnt(100))
        assertEquals("ok", tryHold(seat(), 100), "두 번째 새 좌석(cnt 2)")
        assertEquals("HOLD_LIMIT_EXCEEDED", tryHold(seat(), 100), "활성 2개 — 한도")
        assertEquals(MAX_PER_USER, cnt(100))
        assertCounterConsistent("N4")
    }

    @Test
    fun `N4 — 활성 홀드와 확정은 그대로 센다(활성 1 + 확정 1은 막고 · 확정 1 + 만료 1은 통과)`() {
        seatWithActiveHold(100)
        jdbcTemplate.dbConfirmed(seat(), s, 100)
        assertEquals("HOLD_LIMIT_EXCEEDED", tryHold(seat(), 100), "활성 1 + 확정 1")
        jdbcTemplate.dbConfirmed(seat(), s, 200)
        val (_, expired) = seatWithExpiredHold(200)
        assertEquals("ok", tryHold(seat(), 200), "확정 1 + 만료 1 → 회수 뒤 통과")
        assertFalse(jdbcTemplate.holdRowExists(expired))
        assertEquals(MAX_PER_USER, cnt(200))
        assertCounterConsistent("N4 확정 포함")
    }

    @Test
    fun `N3 — 회수 뒤 좌석 잠금(NOWAIT)에서 지면 회수도 함께 롤백된다 · 잠금이 풀리면 같은 요청이 회수하고 통과한다`() {
        val (_, expired) = seatWithExpiredHold(100)
        val target = seat()
        raw().use { r ->
            r.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", target)
            assertEquals("SEAT_NOT_AVAILABLE", pool.submit<String> { tryHold(target, 100) }.within(), "다른 트랜잭션이 좌석을 쥠 → NOWAIT 패배 409(E4)")
            assertTrue(jdbcTemplate.holdRowExists(expired), "실패한 선점의 회수는 남지 않는다(규칙 ③ — 부분 반영 0)")
            assertEquals(1, cnt(100), "실패한 선점은 cnt를 바꾸지 않는다")
            r.rollback()
        }
        assertEquals("ok", tryHold(target, 100), "잠금이 풀린 뒤 같은 선점")
        assertFalse(jdbcTemplate.holdRowExists(expired), "이번에는 회수된다")
        assertEquals(1, cnt(100))
        assertCounterConsistent("N3")
    }

    // ── N2: 이중 판매 0 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `N2 — 남의 만료 홀드가 남은 좌석에 여럿이 동시에 선점해도 활성 홀드는 정확히 1개`() {
        val seats = (1..8).map { seatWithExpiredHold(8_000L + it).first }
        seats.forEachIndexed { k, seat ->
            val results = pool.together(8) { i -> tryHold(seat, 1_000L + k * 100 + i) }
            assertEquals(1, results.count { it == "ok" }, "좌석 $seat 동시 선점 8명 — 성공은 1명: $results")
            assertTrue(results.filter { it != "ok" }.all { it == "SEAT_NOT_AVAILABLE" }, "실패는 모두 409 SEAT_NOT_AVAILABLE(E4): $results")
            assertEquals(1, jdbcTemplate.activeHoldsOfSeat(seat), "좌석 $seat 활성 홀드 수")
        }
        assertNoDoubleSale("동시 선점")
        assertCounterConsistent("동시 선점")
    }

    @Test
    fun `N2 시각 역전 — 만료 직전 시작한 확정이 만료 뒤 커밋된 남의 재선점 뒤에 진행하면 409 HOLD_EXPIRED(다른 활성 홀드 — K3)`() {
        val seat = seat(storedHeld)
        val holdX = jdbcTemplate.dbHold(seat, s, 100, "1500 milliseconds")
        val te = holdExpiresAt(holdX)
        val confirmX: Future<String>
        raw().use { y ->
            // Y = 재선점 트랜잭션: 좌석 행을 쥐고(H7) — 그동안 X의 확정이 좌석 잠금(K2)에서 기다린다
            y.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", seat)
            confirmX = pool.submit<String> { tryConfirm(holdX, 100) }
            jdbcTemplate.awaitBlockedBy(y.pid(), 1, Duration.ofSeconds(10))
            jdbcTemplate.awaitDbTimeAfter(te)
            // 만료 뒤 Y가 새 활성 홀드를 만들고 커밋(B·C는 이미 HELD라 상태를 쓰지 않는다 · D는 상태를 쓰지 않는다 — H10)
            y.exec(
                "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, clock_timestamp(), clock_timestamp() + interval '5 minutes')",
                seat, s, 200L,
            )
            y.exec("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 1)", s, 200L)
            y.commit()
        }
        assertEquals("HOLD_EXPIRED", confirmX.within(), "X 확정은 다른 활성 홀드(Y) 때문에 거부(§2.2 시각 역전 · K3 other_active)")
        assertEquals(0, jdbcTemplate.confirmedOfSeat(seat), "확정 없음")
        assertEquals(1, jdbcTemplate.activeHoldOf(seat, 200), "Y의 홀드는 그대로")
        assertNoDoubleSale("시각 역전")
        assertCounterConsistent("시각 역전")
    }

    @Test
    fun `N2 시각 역전 + 남의 확정(DS14-1) — X 확정 시작 → Y 재선점·확정 커밋 → X는 K2에서 RESERVED를 받아 409 SEAT_NOT_HELD`() {
        val seat = seat(storedHeld)
        val holdX = jdbcTemplate.dbHold(seat, s, 100, "1500 milliseconds")
        val te = holdExpiresAt(holdX)
        val confirmX: Future<String>
        raw().use { y ->
            y.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", seat)
            confirmX = pool.submit<String> { tryConfirm(holdX, 100) }
            jdbcTemplate.awaitBlockedBy(y.pid(), 1, Duration.ofSeconds(10))
            jdbcTemplate.awaitDbTimeAfter(te)
            // Y의 재선점 + 확정이 커밋된 꼴: 좌석 RESERVED · Y 예약 · Y 홀드는 확정에서 지워져 남지 않는다 · X의 만료 홀드는 남는다(Q4)
            y.exec("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", seat)
            y.exec(
                "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, 'pay-y', 'CONFIRMED', now())",
                s, seat, 200L,
            )
            y.exec("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 1)", s, 200L)
            y.commit()
        }
        assertEquals("SEAT_NOT_HELD", confirmX.within(), "K2가 잠근 좌석의 최신 status(RESERVED)를 K3보다 먼저 본다(DS14-1)")
        assertEquals(1, jdbcTemplate.confirmedOfSeat(seat), "확정은 Y 하나")
        assertNoDoubleSale("시각 역전 + 남의 확정")
        assertCounterConsistent("시각 역전 + 남의 확정")
    }

    @Test
    fun `N2 스트레스 — 만료 경계에서 원래 주인의 확정과 남의 선점·확정이 겹쳐도 좌석당 확정 ≤ 1 · 활성 홀드와 확정 공존 0`() {
        val n = 12
        val seats = (1..n).map { seat(storedHeld) }
        val holds = seats.mapIndexed { i, seat -> jdbcTemplate.dbHold(seat, s, 5_000L + i, "${200 + 40 * i} milliseconds") }
        // 첫 홀드의 만료만 기다린다(blind 원래 설계) — 좌석마다 만료가 40ms씩 어긋나 있어 출발 순간 일부는 막 만료되고 일부는 아직 활성이다.
        // 그 경계에서 X 확정과 Y 선점·확정이 겹치는 것이 이 테스트가 재현하려는 것이다. 메인 수정(코드 리뷰 CR14-4): 앞서 '마지막 만료까지 대기'로 바꿨다가
        // 모든 좌석이 만료된 뒤 출발하게 돼 경계 재현이 사라졌다 — 되돌린다. 그래서 어느 쪽이 이기는지는 타이밍에 달렸고(활성이면 X가 정당하게 이긴다) 진행성은 단언하지 않는다
        jdbcTemplate.awaitDbTimeAfter(holdExpiresAt(holds.first()))
        val results = pool.together(2 * n) { k ->
            val i = k / 2
            if (k % 2 == 0) {
                "X:" + tryConfirm(holds[i], 5_000L + i)
            } else {
                // 메인 수정(유지): X 확정이 좌석을 쥔 순간의 NOWAIT 패배(§3.1 H7 — 설계된 409)는 한 번 다시 시도한다 — X 확정이 끝난 뒤의 판정까지 겹침을 넓힌다
                var r = runCatching { hold(seats[i], 6_000L + i) }
                if (r.isFailure && outcomeOf { r.getOrThrow() } == "SEAT_NOT_AVAILABLE") r = runCatching { hold(seats[i], 6_000L + i) }
                "Y:" + (r.getOrNull()?.let { tryConfirm(it.holdId, 6_000L + i) } ?: outcomeOf { r.getOrThrow() })
            }
        }
        assertTrue(results.none { it.contains("ERR:") }, "예상 밖 예외: $results")
        // 불변식만 단언한다(메인 수정 — CR14-4): 진행성(Y:ok가 하나는 있다)은 경계 타이밍에 달려 단언하지 않는다
        seats.forEach { assertTrue(jdbcTemplate.confirmedOfSeat(it) <= 1, "좌석 $it 확정 ≤ 1: $results") }
        assertNoDoubleSale("만료 경계 스트레스: $results")
        assertCounterConsistent("만료 경계 스트레스")
    }

    // ── 확정 판정 순서 · 응답 코드(§3.2 · DS14-1 · N8) ─────────────────────────────────────────

    @Test
    fun `확정 판정 순서 — 404 HOLD_NOT_FOUND → 403 HOLD_NOT_OWNED → 좌석 status → 홀드 만료 · 응답 코드(HTTP)`() {
        // K1: 없음 → 404
        assertEquals(HttpResult(404, "HOLD_NOT_FOUND", ""), httpConfirm(987_654_321, 100).copy(body = ""))
        // K1: 남의 것(만료여도) → 403 — 만료 판정보다 앞
        val (_, othersExpired) = seatWithExpiredHold(300)
        assertEquals(403 to "HOLD_NOT_OWNED", httpConfirm(othersExpired, 100).let { it.status to it.code })
        // 활성 내 홀드 → 200 · 좌석 RESERVED · 홀드 행 삭제 · cnt 그대로(홀드 → 확정)
        val (okSeat, okHold) = seatWithActiveHold(100)
        val ok = httpConfirm(okHold, 100)
        assertEquals(200, ok.status, ok.body)
        assertEquals("CONFIRMED", objectMapper.readTree(ok.body).get("status").asText())
        assertEquals("RESERVED", jdbcTemplate.storedStatus(okSeat))
        assertFalse(jdbcTemplate.holdRowExists(okHold))
        assertEquals(1, cnt(100))
        // 만료 내 홀드 + 좌석은 그 방식의 HELD 저장값 → 409 HOLD_EXPIRED(K3)
        val (_, expired) = seatWithExpiredHold(200)
        assertEquals(409 to "HOLD_EXPIRED", httpConfirm(expired, 200).let { it.status to it.code })
        // 만료 내 홀드 + 좌석 RESERVED(남이 다시 잡아 확정) → 409 SEAT_NOT_HELD(K2가 먼저 — A의 HOLD_EXPIRED와 다르다 §3.2)
        val (soldSeat, staleHold) = seatWithExpiredHold(400)
        jdbcTemplate.dbConfirmed(soldSeat, s, 500)
        assertEquals(409 to "SEAT_NOT_HELD", httpConfirm(staleHold, 400).let { it.status to it.code })
        // 만료 내 홀드 + 저장 AVAILABLE → B·C SEAT_NOT_HELD(K2 `HELD` 아님) · D HOLD_EXPIRED(K2 통과 → K3)
        val avSeat = seat(SeatStatus.AVAILABLE)
        val avHold = jdbcTemplate.dbHold(avSeat, s, 600, "-1 minute")
        assertEquals(409 to expiredOnAvailableSeatCode, httpConfirm(avHold, 600).let { it.status to it.code })
        assertEquals(1, jdbcTemplate.confirmedOfSeat(soldSeat))
        assertCounterConsistent("확정 판정 순서")
        assertNoDoubleSale("확정 판정 순서")
    }

    @Test
    fun `Q14 — 회수가 지운 만료 홀드의 늦은 확정은 404 HOLD_NOT_FOUND`() {
        val (_, expired) = seatWithExpiredHold(100)
        assertEquals("ok", tryHold(seat(), 100), "다른 좌석 선점 — 만료 홀드 회수")
        assertEquals(404 to "HOLD_NOT_FOUND", httpConfirm(expired, 100).let { it.status to it.code })
    }

    @Test
    fun `HTTP 선점 — 남의 만료 홀드 좌석 201(expiresAt = DB 시각 + TTL) · 활성 홀드 좌석 409 SEAT_NOT_AVAILABLE`() {
        val (free, _) = seatWithExpiredHold(900)
        val (taken, _) = seatWithActiveHold(901)
        val r = httpHold(free, 100)
        assertEquals(201, r.status, r.body)
        val expiresAt = Instant.parse(objectMapper.readTree(r.body).get("expiresAt").asText())
        assertTrue(Duration.between(jdbcTemplate.dbNow().plus(TTL), expiresAt).abs() < Duration.ofSeconds(10), "expiresAt=$expiresAt")
        assertEquals(409 to "SEAT_NOT_AVAILABLE", httpHold(taken, 101).let { it.status to it.code })
    }

    // ── N5: 같은 홀드 행 삭제는 cnt를 정확히 한 번 내린다 ───────────────────────────────────────────

    @Test
    fun `N5 — 만료 홀드 2개인 사용자의 동시 선점 2개 — 둘 다 통과 · 회수는 한 번(cnt = 2 = 실제)`() {
        val users = (1..6).map { 700L + it }
        users.forEach { u -> repeat(2) { seatWithExpiredHold(u) } }
        val targets = users.associateWith { listOf(seat(), seat()) }
        val results = pool.together(users.size * 2) { k ->
            val u = users[k / 2]
            tryHold(targets.getValue(u)[k % 2], u)
        }
        assertEquals(List(users.size * 2) { "ok" }, results, "동시 선점 결과")
        users.forEach { u -> assertEquals(MAX_PER_USER, cnt(u), "사용자 $u cnt") }
        assertEquals(0, jdbcTemplate.expiredHoldRows(), "만료 홀드는 모두 회수")
        assertCounterConsistent("동시 회수")
    }

    @Test
    fun `DS14-4 — 회수 H4가 확정(K3)이 쥔 자기 만료 홀드 행을 기다린다 · 확정 성공이면 0행 감소 · 확정 거부면 회수`() {
        // (가) 확정 성공: 확정 트랜잭션이 홀드 행을 쥔 채 커밋(행 삭제 · 예약) → 회수는 0행 → cnt = 예약 1 + 새 홀드 1
        val (seatA, holdA) = seatWithExpiredHold(100)
        val targetA = seat()
        raw().use { k ->
            k.exec("SELECT id FROM seat_hold WHERE id = ? FOR UPDATE", holdA)
            val f = pool.submit<String> { tryHold(targetA, 100) }
            jdbcTemplate.awaitBlockedBy(k.pid(), 1, Duration.ofSeconds(10))
            k.exec("UPDATE product_seat SET status = 'RESERVED' WHERE id = ?", seatA)
            k.exec("DELETE FROM seat_hold WHERE id = ?", holdA)
            k.exec(
                "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, 'pay-k', 'CONFIRMED', now())",
                s, seatA, 100L,
            )
            k.commit()
            assertEquals("ok", f.within(), "회수는 확정이 끝난 뒤 진행")
        }
        assertEquals(2, cnt(100), "확정 1 + 새 홀드 1 — 지워진 행을 다시 빼지 않는다(규칙 ②)")
        // (나) 확정 거부(롤백): 회수가 그 행을 지운다 → cnt = 새 홀드 1
        val (_, holdB) = seatWithExpiredHold(200)
        val targetB = seat()
        raw().use { k ->
            k.exec("SELECT id FROM seat_hold WHERE id = ? FOR UPDATE", holdB)
            val f = pool.submit<String> { tryHold(targetB, 200) }
            jdbcTemplate.awaitBlockedBy(k.pid(), 1, Duration.ofSeconds(10))
            k.rollback()
            assertEquals("ok", f.within())
        }
        assertFalse(jdbcTemplate.holdRowExists(holdB))
        assertEquals(1, cnt(200))
        assertCounterConsistent("회수 ↔ 확정")
        assertNoDoubleSale("회수 ↔ 확정")
    }

    // ── 좌석 맵(§3.3 · Q11) ───────────────────────────────────────────────────────────────

    @Test
    fun `좌석 맵 — 보이는 상태(B·C·D 공통) — 빈 좌석·만료 홀드·홀드 없는 저장 HELD = AVAILABLE · 활성 홀드 = HELD · 확정 = RESERVED · 앱 시계 무관`() {
        val free = seat()
        val (active, _) = seatWithActiveHold(100)
        val (expired, _) = seatWithExpiredHold(200)
        val staleHeld = seat(SeatStatus.HELD)
        val sold = seat()
        jdbcTemplate.dbConfirmed(sold, s, 300)
        val other = productScheduleRepository.save(
            com.jun.labs.seatreservation.domain.ProductSchedule(productId = schedule.productId, startsAt = SCHEDULE_STARTS_AT),
        )
        val otherSeat = jdbcTemplate.queryForObject(
            "INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'B', 2, 1, 'AVAILABLE') RETURNING id", Long::class.java, other.id,
        )!!
        val expected = mapOf(free to "AVAILABLE", active to "HELD", expired to "AVAILABLE", staleHeld to "AVAILABLE", sold to "RESERVED")
        listOf(CLOCK_PAST, CLOCK_FUTURE).forEach { at ->
            clock.set(at)
            assertEquals(expected, seatMap(), "좌석 맵(앱 시계 $at)")
        }
        assertEquals(mapOf(otherSeat to "AVAILABLE"), seatMap(other.id!!), "다른 회차 좌석은 그 회차 맵에만")
        val node = objectMapper.readTree(seatMapRaw(s).body).first { it.get("seatId").asLong() == free }
        assertEquals(listOf("A", "1", "1"), listOf(node.get("section").asText(), node.get("rowNo").asText(), node.get("seatNo").asText()))
    }

    @Test
    fun `좌석 맵 — 없는 회차 404 · 좌석 없는 회차 200 빈 배열(E3)`() {
        assertEquals(404, seatMapRaw(987_654_321).status)
        assertEquals(emptyMap(), seatMap(s))
    }

    @Test
    fun `좌석 맵 — 선점하면 HELD로 보이고 확정하면 RESERVED로 보인다(HTTP 흐름)`() {
        val seat = seat()
        val r = httpHold(seat, 100)
        assertEquals(201, r.status, r.body)
        assertEquals("HELD", seatMap()[seat])
        val holdId = objectMapper.readTree(r.body).get("holdId").asLong()
        assertEquals(200, httpConfirm(holdId, 100).status)
        assertEquals("RESERVED", seatMap()[seat])
    }
}
