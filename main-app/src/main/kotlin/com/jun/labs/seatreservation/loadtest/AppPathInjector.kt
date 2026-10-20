package com.jun.labs.seatreservation.loadtest

import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties.InjectPath
import com.jun.labs.seatreservation.service.impl.drift.AppPathInjectHook
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 측정 전용 앱 경로 주입(ADR-012 §4.2.7 — 버그 흉내). loadtest 프로필에만 빈이 있고, 손잡이 `seat.hold.loadtest.inject-app.enabled`가 켜졌을 때만 주입한다.
 * - 쌍 선택: `(scheduleId × 31 + userId) mod every == 0` · 쌍마다 한 번(주입한 쌍 집합 · 원자적 개수 ≤ `max-pairs` — 롤백되면 되돌린다).
 * - 선점 경로: 증가 직후 같은 트랜잭션에서 δ = +1(AH — cnt > 실제) · −1(AL — 결과 = 실제 − 1 ≥ 0)을 쌍마다 번갈아(AH부터 — §4.2.13 #15).
 * - 만료 경로: A 가드 감소가 1행일 때 +1(AE — 번갈아에 끼지 않는다). −1은 쓰지 않는다(CHECK 23514로 만료 틱 전체가 롤백된다).
 * - 주입 문장은 `updated_at`을 직접 쓰지 않는다 — 표시는 정상 경로가 이미 남겼다(ⓐ 등록 · ⓒ 문장 · ⓑ 트리거가 같은 트랜잭션 시각으로 덮음).
 * - 기록: 같은 트랜잭션에서 `counter_app_inject` 1행(at = clock_timestamp() · tx_started_at = now()) — 커밋된 주입만 남는다.
 */
@Component
@Profile("loadtest")
class AppPathInjector(
    properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
) : AppPathInjectHook {
    private val cfg = properties.loadtest.injectApp
    private val injected: MutableSet<Pair<Long, Long>> = ConcurrentHashMap.newKeySet()
    private val count = AtomicInteger()
    private val holdTurn = AtomicLong()

    private fun chosen(s: Long, u: Long) = cfg.every > 0 && Math.floorMod(s * 31 + u, cfg.every.toLong()) == 0L

    override fun afterHoldIncrement(scheduleId: Long, userId: Long) {
        if (!cfg.enabled || InjectPath.HOLD !in cfg.paths || !chosen(scheduleId, userId)) return
        if (!reserve(scheduleId to userId)) return
        val ah = holdTurn.getAndIncrement() % 2 == 0L
        inject("hold", if (ah) "AH" else "AL", scheduleId, userId, if (ah) 1 else -1)
    }

    override fun afterExpiryDecrement(scheduleId: Long, userId: Long) {
        if (!cfg.enabled || InjectPath.EXPIRY !in cfg.paths || !chosen(scheduleId, userId)) return
        if (!reserve(scheduleId to userId)) return
        inject("expiry", "AE", scheduleId, userId, 1)
    }

    /** 쌍마다 한 번 · 상한 `max-pairs`. 이 트랜잭션이 롤백되면 자리를 되돌린다(주입 행도 함께 사라졌다). */
    private fun reserve(key: Pair<Long, Long>): Boolean {
        if (!injected.add(key)) return false
        if (count.incrementAndGet() > cfg.maxPairs) {
            count.decrementAndGet()
            injected.remove(key)
            return false
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        injected.remove(key)
                        count.decrementAndGet()
                    }
                }
            })
        }
        return true
    }

    private fun inject(path: String, kind: String, s: Long, u: Long, delta: Int) {
        val cntAfter = jdbc.queryForList(
            "UPDATE user_hold_quota SET cnt = cnt + ? WHERE schedule_id = ? AND user_id = ? RETURNING cnt", Int::class.java, delta, s, u,
        ).singleOrNull() ?: error("주입 대상 쿼터 행이 없다: 회차 $s 사용자 $u")
        jdbc.update(
            """
            INSERT INTO counter_app_inject (at, tx_started_at, path, kind, schedule_id, user_id, delta, cnt_after)
            VALUES (clock_timestamp(), now(), ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            path, kind, s, u, delta, cntAfter,
        )
        log.info("측정 전용 앱 경로 주입 {}({}): 회차 {} 사용자 {} δ={} → cnt {}", kind, path, s, u, delta, cntAfter)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AppPathInjector::class.java)
    }
}
