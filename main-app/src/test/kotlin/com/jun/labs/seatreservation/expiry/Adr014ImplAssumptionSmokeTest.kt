package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.service.ExpiryMode
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 명세 §8 load-bearing 가정 스모크(착수 직후 — 그 위에 쌓기 전에).
 * - §8-1: DB `now()`는 트랜잭션 시작에 고정된다 — 긴 트랜잭션 안의 판정은 벽시계(`clock_timestamp()`)보다 늦게 만료를 본다(보수 쪽 · §설계-2.2).
 * - §8-2 · §설계-10 #2: RC에서 `FOR UPDATE` + 같은 문장 하위 질의는 잠금을 쥐었던 트랜잭션이 막 커밋한 홀드를 못 본다(좌석 행을 쓰지 않는 D에서는 EPQ도 못 잡는다) ·
 *   잠근 뒤 새 문장(H8)은 본다 — (가) '좌석 행 잠금 + 새 행 추가'의 근거. 실제 D 경로의 동시 선점은 Adr014ImplConcurrencyDerivedTest가 본다.
 */
@DerivedMode
class Adr014ImplAssumptionSmokeTest : Adr014ImplSupport() {

    @Test
    fun `8-1 — now()는 트랜잭션 안에서 고정 · clock_timestamp()와의 차 = 트랜잭션이 앞에서 쓴 시간`() {
        val seat = seat()
        // 자동 커밋으로 '지금 + 300ms'에 만료되는 홀드
        val h = jdbcTemplate.queryForObject(
            "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, 1, now(), clock_timestamp() + interval '300 milliseconds') RETURNING id",
            Long::class.java, seat, s,
        )!!
        rawConnection().use { c ->
            val first = c.prepareStatement("SELECT now(), clock_timestamp()").executeQuery().use { it.next(); it.getTimestamp(1) to it.getTimestamp(2) }
            Thread.sleep(600)
            val (now2, clock2, activeByNow, activeByClock) = c.prepareStatement(
                "SELECT now(), clock_timestamp(), expires_at > now(), expires_at > clock_timestamp() FROM seat_hold WHERE id = $h",
            ).executeQuery().use { rs -> rs.next(); listOf<Any>(rs.getTimestamp(1), rs.getTimestamp(2), rs.getBoolean(3), rs.getBoolean(4)) }
            c.rollback()
            assertEquals(first.first, now2, "now()는 트랜잭션 시작 시각에 고정")
            val gapMs = (clock2 as Timestamp).time - (now2 as Timestamp).time
            assertTrue(gapMs >= 600, "clock_timestamp() − now() = 앞에서 쓴 시간: ${gapMs}ms")
            assertTrue(activeByNow as Boolean, "now() 기준으로는 아직 활성(벽시계로는 만료) — 보수 쪽(회수 안 됨 · 좌석 409)")
            assertFalse(activeByClock as Boolean)
        }
    }

    @Test
    fun `8-2 · #2 — 잠금과 하위 질의를 한 문장으로 하면 막 커밋된 남의 활성 홀드를 못 본다 · 잠근 뒤 새 문장은 본다`() {
        assertEquals(ExpiryMode.DERIVED, mode)
        val seat = seat()
        val pool = Executors.newSingleThreadExecutor()
        try {
            rawConnection().use { x ->
                // X = D 선점 흉내: 좌석 행을 잠그기만 하고(쓰지 않는다) 새 활성 홀드를 넣는다 — 아직 커밋 전
                x.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", seat)
                x.exec("INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, 1, now(), now() + interval '5 minutes')", seat, s)
                val xPid = x.pid()
                // Y = 잠금 + 판정을 한 문장으로(설계가 금지한 꼴)
                val y = pool.submit<Pair<Boolean, Boolean>> {
                    rawConnection().use { c ->
                        val oneStatement = c.prepareStatement(
                            "SELECT ${com.jun.labs.seatreservation.service.impl.expiry.ExpiryRule.ACTIVE_ON_SEAT} FROM product_seat st WHERE st.id = ? FOR UPDATE",
                        ).apply { setLong(1, seat) }.executeQuery().use { it.next(); it.getBoolean(1) }
                        val newStatement = c.prepareStatement(
                            "SELECT ${com.jun.labs.seatreservation.service.impl.expiry.ExpiryRule.ACTIVE_ON_SEAT} FROM product_seat st WHERE st.id = ?",
                        ).apply { setLong(1, seat) }.executeQuery().use { it.next(); it.getBoolean(1) }
                        c.rollback()
                        oneStatement to newStatement
                    }
                }
                awaitWaitersOf(xPid)
                x.commit()
                val (oneStatement, newStatement) = y.get(20, TimeUnit.SECONDS)
                assertFalse(oneStatement, "한 문장: 문장 시작 스냅숏의 하위 질의 — X의 홀드를 못 본다(두 번째 활성 홀드가 생길 자리)")
                assertTrue(newStatement, "잠근 뒤 새 문장(H8): X의 커밋이 보인다")
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
