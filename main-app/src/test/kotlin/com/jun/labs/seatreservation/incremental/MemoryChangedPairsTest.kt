package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.support.awaitIdleInTransaction
import com.jun.labs.seatreservation.support.countOf
import com.jun.labs.seatreservation.support.setCnt
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * 테스트 3·5·23(after_commit_error) — ⓐ 메모리 집합(§4.2.2 ⓐ · §3-1 '커밋 뒤에만 등록 · 꺼낸 쌍은 실패하면 다시 넣기').
 * '후보가 됐나'는 증분 틱 기록의 pairs_scanned(= 후보 쌍 수 — §4.2.6)로 본다. 각 단계 전에 틱 하나로 집합을 비운다(drain).
 */
abstract class MemoryScenarioBase : IncrementalTestBase() {
    fun drain(): Long {
        tick(SRC_MEM)
        return jdbcTemplate.lastRunOf(SRC_MEM).long("pairs_scanned")
    }

    fun tickCandidates(): Long {
        tick(SRC_MEM)
        return jdbcTemplate.lastRunOf(SRC_MEM).long("pairs_scanned")
    }
}

/** 등록 예외 이음새(가정 S4) — 켤 때만 그 쌍의 등록에서 던진다. */
class FailingRegistrationProbe : MemoryRegistrationProbe {
    val failFor = AtomicReference<Pair<Long, Long>?>(null)
    override fun beforeRegister(scheduleId: Long, userId: Long) {
        if (failFor.get() == (scheduleId to userId)) throw IllegalStateException("테스트: afterCommit 등록 실패")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FailingRegistrationProbeConfig {
    @Bean fun failingRegistrationProbe() = FailingRegistrationProbe()
}

@TestPropertySource(properties = [A_ON, M_MEM, INC_ON, INC_1H, SAFE_ON, SAFE_1H, SAMPLE_1H, FW_V10])
@Import(FailingRegistrationProbeConfig::class)
class MemoryRegistrationTest : MemoryScenarioBase() {

    @Autowired lateinit var failing: FailingRegistrationProbe

    @BeforeEach
    fun resetProbe() = failing.failFor.set(null)

    @Test
    fun `3 — 커밋된 선점은 등록되고, 롤백된 선점(좌석 NOWAIT 실패 — upsert 뒤)의 쌍은 후보가 아니다`() {
        drain()
        appHold(301)
        assertEquals(1L, tickCandidates(), "커밋된 선점 1건 = 후보 1쌍")

        val seat = freeSeat()
        rawConnection().use { lock ->
            lock.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", seat)
            val err = failureOf { holdSeat.hold(HoldSeatCommand(s, seat, 302)) }
            assertTrue(err != null, "전제: 잠긴 좌석의 선점은 실패한다(NOWAIT)")
            lock.rollback()
        }
        assertNull(cntOf(s, 302), "전제: 롤백되어 쿼터 행이 없다")

        assertEquals(0L, tickCandidates(), "롤백된 선점의 쌍이 후보가 됐다(커밋 전 등록)")
    }

    @Test
    fun `3 — 롤백된 만료(쿼터 감소 뒤 flush 실패)의 쌍은 후보가 아니다`() {
        val h = appHold(311)
        drain()
        clock.advance(Duration.ofMinutes(6))     // TTL 5m 지남
        val seatId = h.seatId
        rawConnection().use { lock ->
            lock.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", seatId)
            val running = pool.submit<Int> { expireHolds.expire() }
            val w = jdbcTemplate.awaitBlockedBy(lock.pid())
            jdbcTemplate.queryForObject("SELECT pg_cancel_backend(?)", Boolean::class.java, w.first().pid)
            assertTrue(failureOf { running.join() } != null, "전제: 만료 틱이 롤백됐다")
            lock.rollback()
        }
        assertEquals(1, cntOf(s, 311), "전제: 감소가 롤백됐다")
        assertTrue(holdExists(h.holdId))

        assertEquals(0L, tickCandidates(), "롤백된 만료의 쌍이 후보가 됐다(커밋 전 등록)")
    }

    @Test
    fun `5 — 탐지 청크가 실패한 꺼냄은 되돌아가 다음 틱 후보에 다시 있고, 그 틱이 고친다(requeued 지표)`() {
        drain()
        appHold(501)
        jdbcTemplate.setCnt(s, 501, 2)            // 직접 SQL — ⓐ는 등록하지 않는다. 등록은 위 선점이 이미 남겼다
        val requeued0 = meters.countOf(M_INC_MEM_REQUEUED)
        rawConnection().use { lock ->
            lock.exec("LOCK TABLE seat_hold IN ACCESS EXCLUSIVE MODE")
            val running = pool.submit { tick(SRC_MEM) }
            jdbcTemplate.cancelWaitersUntilDone(lock.pid(), running)
            runCatching { running.join() }
            lock.rollback()
        }
        assertEquals(2, cntOf(s, 501), "실패한 틱은 고치지 못했다")
        assertTrue(jdbcTemplate.lastRunOf(SRC_MEM).long("errors") >= 1, "실패는 틱 기록 errors에 남는다: ${jdbcTemplate.lastRunOf(SRC_MEM)}")
        assertTrue(meters.countOf(M_INC_MEM_REQUEUED) - requeued0 >= 1, "memory.requeued 지표")

        tick(SRC_MEM)

        assertEquals(1, cntOf(s, 501))
        val l = jdbcTemplate.logsOf(s, 501).single()
        assertEquals(SRC_MEM, l.source)
        assertEquals("repaired", l.action)
    }

    @Test
    fun `5 — 복구가 skipped(잠금 시간 초과)인 꺼냄도 되돌아가 다음 틱이 고친다`() {
        drain()
        appHold(502)
        jdbcTemplate.setCnt(s, 502, 2)
        rawConnection().use { lock ->
            lock.exec("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", s, 502L)
            tick(SRC_MEM)                         // lock-timeout 기본 1s → skipped
            lock.rollback()
        }
        assertEquals(listOf("skipped"), jdbcTemplate.logsOf(s, 502).map { it.action })

        tick(SRC_MEM)

        assertEquals(1, cntOf(s, 502))
        assertEquals(listOf("skipped", "repaired"), jdbcTemplate.logsOf(s, 502).map { it.action })
        assertEquals(SRC_MEM, jdbcTemplate.logsOf(s, 502).last().source)
    }

    @Test
    fun `23 — afterCommit 등록 예외 — 요청은 성공 · counter_inc_gap after_commit_error 행(그 쌍) · 지표 · 그 쌍의 어긋남은 안전망 source로 고쳐진다`() {
        drain()
        quota(s, 2301, 3)                         // 직접 SQL 어긋남(등록 없음) — 선점 뒤 cnt 4 · 실제 1
        failing.failFor.set(s to 2301L)
        val gap0 = meters.countOf(M_INC_GAP, "kind" to "after_commit_error")

        appHold(2301)                             // 예외가 요청 밖으로 새면 여기서 실패한다

        tick(SRC_MEM)
        assertTrue(jdbcTemplate.logsOf(s, 2301).none { it.source == SRC_MEM }, "등록 실패한 쌍을 증분이 봤다")
        val gaps = jdbcTemplate.gapRows("after_commit_error")
        assertTrue(gaps.any { it.longOrNull("schedule_id") == s && it.longOrNull("user_id") == 2301L && it.long("pairs") >= 1 }, "after_commit_error 행: $gaps")
        // source = 증분 source(§4.2.6) — 등록 경로에서 생긴 사건이라 '틱에 넘긴 source'인지 '스케줄러 source(주기 꼬리표)'인지는 계약이 정하지 않는다(모호 — 보고)
        assertTrue(gaps.all { it["source"].toString().startsWith("inc-app-mem-") }, "$gaps")
        assertTrue(meters.countOf(M_INC_GAP, "kind" to "after_commit_error") - gap0 >= 1)

        safetyUntil { cntOf(s, 2301) == actualOf(s, 2301) }

        val repaired = jdbcTemplate.logsOf(s, 2301).filter { it.action == "repaired" }
        assertEquals(listOf(SAFETY_APP), repaired.map { it.source }, "보장 밖 쌍은 안전망이 고친다")
    }
}

/** 테스트 3 '커밋 전에는 후보가 아니다' — 임계 구역 지연(ADR-004 장치 · upsert 뒤 · 커밋 전)으로 선점 트랜잭션을 연 채 틱을 돌린다. */
@TestPropertySource(properties = [A_ON, M_MEM, INC_ON, INC_1H, FW_V10, "seat.hold.critical-section-delay=3s"])
class MemoryCommitTimingTest : MemoryScenarioBase() {

    @Test
    fun `3 — 커밋 전 틱에는 후보가 아니고(지우지 않음), 커밋 뒤 첫 틱이 그 쌍을 후보로 본다`() {
        drain()
        val running = pool.submit<Any> { appHold(321) }
        jdbcTemplate.awaitIdleInTransaction(minIdle = Duration.ofMillis(200), timeout = Duration.ofSeconds(10))

        val before = tickCandidates()             // 선점 트랜잭션은 아직 열려 있다
        running.join()
        val after = tickCandidates()

        assertEquals(0L, before, "커밋 전 쌍이 후보였다(커밋 전 등록)")
        assertEquals(1L, after, "커밋 뒤 첫 틱이 그 쌍을 봐야 한다(커밋 전 틱이 꺼내 지운 경우 0)")
    }
}

/** 테스트 3 '자동 커밋 경로(쿼터 행 준비)도 등록된다' — counter 방식은 트랜잭션 밖에서 cnt 0 행을 만든다(UserLimitStrategies ensureQuotaRow). */
@TestPropertySource(properties = [A_ON, M_MEM, INC_ON, INC_1H, FW_V10, "seat.hold.limit-strategy=counter"])
class MemoryAutocommitPathTest : MemoryScenarioBase() {

    @Test
    fun `3 — 쿼터 행 준비 INSERT(자동 커밋)는 뒤 트랜잭션이 롤백돼도 등록된다`() {
        drain()
        val seat = freeSeat()
        rawConnection().use { lock ->
            lock.exec("SELECT id FROM product_seat WHERE id = ? FOR UPDATE", seat)
            assertTrue(failureOf { holdSeat.hold(HoldSeatCommand(s, seat, 331)) } != null, "전제: 선점 트랜잭션은 실패한다")
            lock.rollback()
        }
        assertEquals(0, cntOf(s, 331), "전제: 준비 INSERT(cnt 0)만 남았다")

        assertEquals(1L, tickCandidates(), "자동 커밋 경로의 쌍이 등록되지 않았다")
    }
}
