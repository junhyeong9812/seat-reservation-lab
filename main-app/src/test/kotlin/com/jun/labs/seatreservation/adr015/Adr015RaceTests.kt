package com.jun.labs.seatreservation.adr015

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import java.sql.SQLException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 모드 × V12 판 = 컨텍스트 하나씩(컨텍스트마다 자기 PostgreSQL 컨테이너 — TestcontainersConfiguration 빈).
 * 'V12 없음' 판 = 모든 마이그레이션 뒤 V12 인덱스만 DROP(Adr015DropV12Config) + 새 모드는 seat.confirm.unique-index-optional=true(규칙 7 · N11 — §설계-8.1 정정).
 * 같은 애너테이션을 단 다른 클래스(Adr015PaymentStartContractTest · Adr015UniqueIndexContractTest)는 같은 컨텍스트를 함께 쓴다.
 */

// ── current(기준선 — 손잡이 기본값) ───────────────────────────────────────────────────────────────

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CURRENT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015CurrentRaceTest : Adr015RaceScenarios("current", v12 = true)

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CURRENT],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class, Adr015DropV12Config::class)
class Adr015CurrentNoV12RaceTest : Adr015RaceScenarios("current", v12 = false) {

    // ── 대조군(§설계-8.1 — 가드 없는 대조 확정 · 대조 배치가 이중 결과 · 잃음을 실제로 만든다. 0이면 하네스가 무력 → 실패) ─────────────

    /** 대조 FM1: 대조 확정 L-C → 배치(JPA · 끝까지) → Y 선점 → 대조 확정 풂 ⇒ 좌석 RESERVED + Y 활성 홀드 + X 예약(이중 결과 ⓐ) */
    @Test
    fun `대조 FM1 — 가드 없는 확정은 남의 활성 홀드 위에 RESERVED를 만든다`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val g = Adr015Gate("L-C(대조)")
        val cf = controlConfirm(hold, x, g)
        reachedOrFail(g)
        clock.set(NOW.plus(Duration.ofMinutes(6)))
        assertTrue(batchAsync().join().isSuccess)
        assertEquals(201, holdHttp(seat, y).status)
        g.release()
        assertEquals("OK", cf.join().getOrThrow())
        val v = verdict()
        assertTrue(v.getValue("active_on_reserved_seats") >= 1, "대조 FM1 이중 결과 ⓐ ≥ 1이어야 한다(아니면 하네스 무력): $v · ${dbState()}")
        assertTrue(v.getValue("v_hold_on_unheld_seat") >= 1, "대조 FM1 v_hold_on_unheld_seat ≥ 1: $v")
        assertTrue(v.getValue("v_counter_mismatch") >= 1, "대조 FM1 cnt(X) below ≥ 1: $v")
    }

    /** 대조 FM2('V12 없음' 판만): 대조 확정 X1 · X2 L-C → X1 커밋 → X2 ⇒ 같은 좌석 CONFIRMED 2건(이중 결과 ⓑ) */
    @Test
    fun `대조 FM2 — 인덱스 없는 DB에서 가드 없는 이중 확정은 CONFIRMED 2건`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(1)))
        val g1 = Adr015Gate("L-C(대조 X1)"); val g2 = Adr015Gate("L-C(대조 X2)")
        val f1 = controlConfirm(hold, x, g1); reachedOrFail(g1)
        val f2 = controlConfirm(hold, x, g2); reachedOrFail(g2)
        g1.release(); assertEquals("OK", f1.join().getOrThrow())
        g2.release(); assertEquals("OK", f2.join().getOrThrow())
        val v = verdict()
        assertTrue(v.getValue("v_duplicate_confirmed_seats") >= 1, "대조 FM2 이중 결과 ⓑ ≥ 1이어야 한다: $v · ${dbState()}")
    }

    /** 대조 FM3: 대조 확정 L-C → 대조 배치 L-B(대조 — 후보 · 감소 뒤) → 확정 커밋 → 배치 풂 ⇒ 예약 CONFIRMED + 좌석 AVAILABLE(이중 결과 ⓒ) */
    @Test
    fun `대조 FM3 — 가드 없는 배치는 예약 좌석을 AVAILABLE로 덮는다`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(4)))
        val gc = Adr015Gate("L-C(대조)")
        val cf = controlConfirm(hold, x, gc); reachedOrFail(gc)
        val batchNow = NOW.plus(Duration.ofMinutes(6))
        clock.set(batchNow)
        val gb = Adr015Gate("L-B(대조)")
        val bf = controlBatch(batchNow, gb); reachedOrFail(gb)
        gc.release(); assertEquals("OK", cf.join().getOrThrow())
        gb.release(); val b = bf.join().getOrThrow()
        val v = verdict()
        assertEquals(0, b.deleted, "대조 배치는 이미 지워진 홀드를 0행으로 지나간다(행 수 안 봄)")
        assertTrue(v.getValue("v_confirmed_on_unreserved_seat") >= 1, "대조 FM3 이중 결과 ⓒ ≥ 1이어야 한다: $v · ${dbState()}")
        assertTrue(v.getValue("v_counter_mismatch") >= 1, "대조 FM3 감소 = 고른 행(below) ≥ 1: $v")
    }

    /**
     * 대조 FM5: 대조 배치 L-B(대조 — 후보 읽기 · 감소 뒤) → 그 사이 연장 커밋(E1과 같은 효과 — 테스트 JDBC · current에는 API가 없다) → 배치 풂(id로만 지움)
     * ⇒ 연장된 홀드가 지워짐(잃음 ≥ 1) · 확정 404.
     */
    @Test
    fun `대조 FM5 — id로만 지우는 배치는 연장된 홀드를 지운다(잃음 1)`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        val batchNow = NOW.plus(Duration.ofMinutes(5)).plusSeconds(30)
        clock.set(batchNow)
        val gb = Adr015Gate("L-B(대조)")
        val bf = controlBatch(batchNow, gb); reachedOrFail(gb)
        val extendedTo = NOW.plus(Duration.ofMinutes(4)).plus(WINDOW)
        jdbcTemplate.update("UPDATE seat_hold SET expires_at = ? WHERE id = ?", java.sql.Timestamp.from(extendedTo), hold)
        gb.release(); val b = bf.join().getOrThrow()
        assertTrue(b.lost >= 1, "대조 FM5 잃음 ≥ 1이어야 한다(아니면 하네스 무력): $b")
        val cr = confirmHttp(hold, x)
        assertEquals(404, cr.status, "대조 FM5 연장을 잃은 확정은 404: $cr")
    }
}

// ── ① conditional ────────────────────────────────────────────────────────────────────────────

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CONDITIONAL],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015ConditionalRaceTest : Adr015NewModeScenarios("conditional", v12 = true)

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CONDITIONAL, P_INDEX_OPTIONAL],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class, Adr015DropV12Config::class)
class Adr015ConditionalNoV12RaceTest : Adr015NewModeScenarios("conditional", v12 = false)

// ── ② pessimistic ────────────────────────────────────────────────────────────────────────────

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_PESSIMISTIC],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015PessimisticRaceTest : Adr015NewModeScenarios("pessimistic", v12 = true) {

    /**
     * 교착 대조판(§3.2 · §3.5 '② 좌석 → 홀드 판 ↔ 배치 — 닫힌다' · #3 · #9): 테스트 JDBC 트랜잭션 C가 좌석 행 FOR UPDATE → A′ 배치가 B3(홀드 DELETE) 뒤 B5 좌석 UPDATE에서
     * C를 기다리는 것을 관측 → C가 홀드 행 FOR UPDATE ⇒ 고리 → 40P01 ≥ 1. (A′ 배치 의존 — JPA 배치는 좌석 → 홀드라 고리가 닫히지 않는다: 구현 전 red 정상)
     */
    @Test
    fun `교착 대조 — 좌석 → 홀드 순서의 확정 판은 A′ 배치와 40P01`() {
        val seat = createSeat(1).id!!
        val hold = holdOk(seat, x)
        clock.set(NOW.plus(Duration.ofMinutes(6)))
        val states = mutableSetOf<String>()
        dataSource.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("SET LOCAL lock_timeout = '15s'") }
            c.prepareStatement("SELECT status FROM product_seat WHERE id = ? FOR UPDATE").use { it.setLong(1, seat); it.executeQuery().close() }
            val pid = c.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { it.next(); it.getInt(1) } }
            val bf = batchAsync()
            val waited = awaitLockWait("update product_seat", pid)
            try {
                c.prepareStatement("SELECT expires_at FROM seat_hold WHERE id = ? FOR UPDATE").use { it.setLong(1, hold); it.executeQuery().close() }
            } catch (e: SQLException) {
                e.sqlState?.let { states += it }
            }
            c.rollback()
            val br = bf.join()
            states += sqlStates(br.exceptionOrNull())
            assertTrue(waited, "대조판 준비 — 배치가 C의 좌석 행에서 기다리지 않았다")
        }
        assertTrue("40P01" in states, "② 좌석 → 홀드 대조판 × A′ 배치는 40P01 ≥ 1이어야 한다(아니면 하네스 무력 또는 A′ 순서가 홀드 → 좌석이 아님): $states")
    }
}

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_PESSIMISTIC, P_INDEX_OPTIONAL],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class, Adr015DropV12Config::class)
class Adr015PessimisticNoV12RaceTest : Adr015NewModeScenarios("pessimistic", v12 = false)

// ── ③ optimistic(V14 선택 위치) ────────────────────────────────────────────────────────────────

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_OPTIMISTIC, M_OPTIMISTIC],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015OptimisticRaceTest : Adr015NewModeScenarios("optimistic", v12 = true)

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_OPTIMISTIC, M_OPTIMISTIC, P_INDEX_OPTIONAL],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class, Adr015DropV12Config::class)
class Adr015OptimisticNoV12RaceTest : Adr015NewModeScenarios("optimistic", v12 = false)

// ── ④ conditional-extend ─────────────────────────────────────────────────────────────────────

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_EXTEND, P_WINDOW, P_MIN_REMAINING],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr015ExtendRaceTest : Adr015ExtendScenarios(v12 = true)

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_EXTEND, P_WINDOW, P_MIN_REMAINING, P_INDEX_OPTIONAL],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class, Adr015DropV12Config::class)
class Adr015ExtendNoV12RaceTest : Adr015ExtendScenarios(v12 = false)
