package com.jun.labs.seatreservation.expiry

import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ADR-014 §설계-7 방식 전환 정리(선검증 DS14-2 · 명세 §10 R4) 계약(blind): 전환은 ①(멈추고 바꾸기)만 — 모든 앱을 내리고, T1(남은 만료 홀드 삭제 +
 * 열린 회차 쌍별 감소) · T2(좌석 기준 재계산)를 한 트랜잭션으로 돌린 뒤, 확인 3항목(v_held_without_hold · v_duplicate_hold_seats · v_counter_mismatch)이
 * 0이면 새 방식으로 올린다. T1·T2 SQL은 문서 그대로(Adr014Support.kt) — 이 테스트가 보는 것은 '구현이 쌓은 데이터' 위에서 그 절차가 성립하는가다.
 * 실제 기동(BootTestBase — 앱 시계 = 실제 시계 · 회차 2099). 만료 상태는 앱이 만든 홀드 행의 시각을 DB 기준 상대값으로 고쳐 만든다(Q10).
 */
class Adr014CutoverContractTest : BootTestBase() {

    private val mapper = ObjectMapper()
    private val http = HttpClient.newHttpClient()

    private fun ConfigurableApplicationContext.hold(s: Long, seat: Long, user: Long): Long =
        getBean(HoldSeatUseCase::class.java).hold(HoldSeatCommand(s, seat, user)).holdId

    private fun ConfigurableApplicationContext.tryHold(s: Long, seat: Long, user: Long): String =
        outcomeOf { hold(s, seat, user) }

    private fun ConfigurableApplicationContext.confirm(hold: Long, user: Long) {
        getBean(ConfirmReservationUseCase::class.java).confirm(ConfirmReservationCommand(hold, user, "pay-$hold"))
    }

    private fun ConfigurableApplicationContext.seatMap(s: Long): Map<Long, String> {
        val port = environment.getProperty("local.server.port")
        val res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port/api/schedules/$s/seats")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, res.statusCode(), res.body().take(300))
        return mapper.readTree(res.body()).associate { it.get("seatId").asLong() to it.get("status").asText() }
    }

    /** 앱이 만든 홀드를 DB 기준 1분 전 만료로(Q10 — 운영 SQL에 시각 훅을 두지 않는다). */
    private fun JdbcTemplate.expire(holdId: Long) {
        update("UPDATE seat_hold SET held_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute' WHERE id = ?", holdId)
    }

    private fun cutover(url: String) = DriverManager.getConnection(url, dbUser, dbPassword).use { it.runCutover() }

    private fun JdbcTemplate.assertCutoverChecks(why: String) {
        assertEquals(
            listOf(0, 0, 0), listOf(vHeldWithoutHold(), vDuplicateHoldSeats(), vCounterMismatch()),
            "전환 정리 뒤 확인 3항목(v_held_without_hold · v_duplicate_hold_seats · v_counter_mismatch) — $why",
        )
        assertEquals(0, expiredHoldRows(), "T1 뒤 남은 만료 홀드 행 0 — $why")
        assertEquals(0, activeHoldNotHeld(), "T2 뒤 활성 홀드 좌석은 모두 HELD — $why")
    }

    @Test
    fun `D → B 전환 — T1·T2 뒤 확인 3항목 0 · D가 AVAILABLE로 둔 활성 홀드 좌석이 HELD가 되어 B 좌석 맵·선점이 맞다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        var s = 0L
        var seats = emptyList<Long>()
        boot(url, mapOf(EXPIRY_MODE to "derived")).use { d ->
            s = db.seedSchedule()
            seats = (1..5).map { db.seat(s) }
            d.hold(s, seats[0], 1)                                    // 활성
            db.expire(d.hold(s, seats[1], 2))                         // 만료
            d.confirm(d.hold(s, seats[2], 3), 3)                      // 확정
            db.expire(d.hold(s, seats[3], 4))                         // 만료 위에
            assertEquals("ok", d.tryHold(s, seats[3], 5), "D — 남의 만료 홀드 좌석 재선점")
            assertEquals("AVAILABLE", db.storedStatus(seats[0]), "전제: D는 활성 홀드 좌석을 AVAILABLE로 둔다")
            assertTrue(db.vDuplicateHoldSeats() > 0, "전제: 좌석 4에 만료 + 활성 홀드 행 둘")
        }
        cutover(url)
        db.assertCutoverChecks("D → B")
        assertEquals(listOf("HELD", "AVAILABLE", "RESERVED", "HELD", "AVAILABLE"), seats.map { db.storedStatus(it) })
        assertEquals(listOf(1, 0, 1, 0, 1), (1L..5L).map { db.quotaCnt(s, it) ?: 0 }, "T1 감소는 지운 행 수만큼")
        boot(url, mapOf(EXPIRY_MODE to "lazy")).use { b ->
            assertEquals(
                mapOf(seats[0] to "HELD", seats[1] to "AVAILABLE", seats[2] to "RESERVED", seats[3] to "HELD", seats[4] to "AVAILABLE"),
                b.seatMap(s), "B 좌석 맵 빠른 길(저장 HELD가 활성 홀드를 덮는다 — §3.3)",
            )
            assertEquals("SEAT_NOT_AVAILABLE", b.tryHold(s, seats[0], 9))
            assertEquals("ok", b.tryHold(s, seats[1], 9))
        }
        assertEquals(0, db.vCounterMismatch())
    }

    @Test
    fun `B → A 전환 — T1·T2 뒤 확인 3항목 0 · B가 남긴 거짓 HELD를 A가 다시 판다 · 활성 홀드 좌석은 A도 막는다`() {
        val url = freshDbUrl()
        val db = jdbcFor(url)
        var s = 0L
        var seats = emptyList<Long>()
        boot(url, mapOf(EXPIRY_MODE to "lazy")).use { b ->
            s = db.seedSchedule()
            seats = (1..4).map { db.seat(s) }
            db.expire(b.hold(s, seats[0], 1))                         // 만료 · 저장 HELD(거짓)
            b.hold(s, seats[1], 2)                                    // 활성
            db.expire(b.hold(s, seats[2], 3))                         // 만료 → 같은 사용자의 다른 선점이 회수(행만 삭제)
            assertEquals("ok", b.tryHold(s, seats[3], 3))
            assertTrue(db.vHeldWithoutHold() > 0, "전제: B 회수는 좌석 행을 쓰지 않아 'HELD인데 홀드 행 없음'이 남는다(§8.3)")
        }
        cutover(url)
        db.assertCutoverChecks("B → A")
        assertEquals(listOf("AVAILABLE", "HELD", "AVAILABLE", "HELD"), seats.map { db.storedStatus(it) })
        boot(url, emptyMap()).use { a ->
            assertEquals("ok", a.tryHold(s, seats[0], 9), "A — T2가 고친 좌석")
            assertEquals("ok", a.tryHold(s, seats[2], 9), "A — 회수가 남긴 거짓 HELD였던 좌석")
            assertEquals("SEAT_NOT_AVAILABLE", a.tryHold(s, seats[1], 10), "A — 활성 홀드 좌석")
        }
        assertEquals(0, db.vCounterMismatch())
    }
}
