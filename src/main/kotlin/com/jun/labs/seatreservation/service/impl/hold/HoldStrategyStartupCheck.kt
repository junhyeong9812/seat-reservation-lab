package com.jun.labs.seatreservation.service.impl.hold

import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties.CleanupMode
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.UserLimitStrategyType.COUNTER
import com.jun.labs.seatreservation.service.UserLimitStrategyType.COUNTER_TRIGGER
import com.jun.labs.seatreservation.service.UserLimitStrategyType.COUNTER_UPSERT
import com.jun.labs.seatreservation.service.UserLimitStrategyType.COUNTER_UPSERT_PRECHECK
import com.jun.labs.seatreservation.service.UserLimitStrategyType.QUOTA_LOCK
import com.jun.labs.seatreservation.service.UserLimitStrategyType.QUOTA_NOWAIT
import com.jun.labs.seatreservation.service.UserLimitStrategyType.QUOTA_NOWAIT_EARLY
import com.jun.labs.seatreservation.service.impl.drift.RECONCILER_PROFILE
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 기동 시 전략과 환경이 맞는지 확인하고, 어긋나면 앱을 띄우지 않는다(fail-fast) — 측정 조건이 조용히 섞이지 않게.
 * - 유니크 인덱스(V4)는 UNIQUE 전략에서만 있어야 한다: 다른 전략에 있으면 기준선이 바뀌고, UNIQUE에 없으면 아무것도 막지 않는다.
 * - Redis 전략은 Redis에 닿아야 한다.
 * - 매수 방식(ADR-005)은 좌석 3b 위에서만 쓴다: 좌석을 먼저 잠그는 전략(advisory·JVM·Redis 락)과 섞이면 락 순서가 좌석 → 사용자로 뒤집히고,
 *   SERIALIZABLE의 `SET TRANSACTION`이 첫 문장이 아니게 된다(설계·측정이 3b 고정 — 명세 §2).
 * - ADR-009(설계 §7 · 명세 §10 D4): 쿼터 테이블을 쓰는 방식은 그 테이블이 있어야 한다 · 트리거(V8)는 counter-trigger에서만 ·
 *   후보(A·C·F·정리)와 매수 방식 조합 · 후보를 켜면 V7 기록 테이블 · reconciler 프로필이면 만료 스케줄러 끔.
 */
@Component
class HoldStrategyStartupCheck(
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    /** 프로필·만료 스케줄러 키 확인용. 직접 생성(테스트)할 때 없으면 그 두 확인은 기본값(프로필 없음·스케줄러 켬)으로 본다 */
    private val environment: Environment? = null,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val strategy = properties.strategy
        val uniqueIndex = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Int::class.java,
            UniqueConstraintHoldStrategy.UNIQUE_HOLD_INDEX,
        )!! > 0
        check(uniqueIndex == (strategy == HoldStrategyType.UNIQUE)) {
            "전략 $strategy 와 유니크 인덱스(${UniqueConstraintHoldStrategy.UNIQUE_HOLD_INDEX}) 존재=$uniqueIndex 가 맞지 않는다 " +
                "— UNIQUE 전략은 spring.flyway.locations에 classpath:db/migration-unique를 더하고, 다른 전략은 빼야 한다"
        }
        check(properties.limitStrategy == UserLimitStrategyType.NONE || strategy == HoldStrategyType.PESSIMISTIC_NOWAIT) {
            "매수 방식 ${properties.limitStrategy}는 좌석 전략 PESSIMISTIC_NOWAIT에서만 쓴다(현재 $strategy) — 락 순서·격리 수준 설정이 이 조합을 전제한다"
        }
        if (strategy == HoldStrategyType.REDIS_NX || strategy == HoldStrategyType.REDIS_LOCK) {
            redis.connectionFactory!!.connection.use { check(it.ping() == "PONG") { "Redis 응답 없음" } }
        }
        checkCounterDrift()
    }

    private fun exists(table: String): Boolean =
        jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, table) == true

    private fun checkCounterDrift() {
        val limit = properties.limitStrategy
        val quotaTable = exists("user_hold_quota")
        check(limit !in QUOTA_TABLE_STRATEGIES || quotaTable) {
            "매수 방식 $limit 는 쿼터 테이블(user_hold_quota — V5)이 있어야 한다 — 없다(spring.flyway.target 등으로 앞 버전 스키마를 띄웠으면 limit-strategy=none을 명시하라)"
        }
        // 트리거 2개(홀드·예약)가 모두 있어야 B다 — 하나만 있으면 카운터가 한쪽 변경만 따라간다(코드 루프 1 L1-14)
        val triggers = jdbc.queryForObject(
            "SELECT count(DISTINCT tgname) FROM pg_trigger WHERE tgname IN ('$HOLD_TRIGGER', '$RESERVATION_TRIGGER') AND NOT tgisinternal", Int::class.java,
        )!!
        check(triggers == 0 || triggers == 2) {
            "카운터 트리거(V8 — $HOLD_TRIGGER·$RESERVATION_TRIGGER)가 하나만 있다($triggers) — 둘 다 있거나 둘 다 없어야 한다"
        }
        val trigger = triggers == 2
        check(trigger == (limit == COUNTER_TRIGGER)) {
            "매수 방식 $limit 와 카운터 트리거(V8 — $HOLD_TRIGGER·$RESERVATION_TRIGGER) 존재=$trigger 가 맞지 않는다 " +
                "— counter-trigger는 spring.flyway.locations에 classpath:db/migration-trigger를 더하고, 다른 방식은 빼야 한다(앱 증감과 트리거가 겹친다)"
        }

        // 코드 루프 2 L2-0: B는 판매 마감을 끌 수 없다 — 앱이 마감 회차 선점을 받는데 트리거는 그 회차 증가를 건너뛰어 한도 판정이 우회된다
        check(!(limit == COUNTER_TRIGGER && !properties.salesClose.enabled)) {
            "매수 방식 counter-trigger는 판매 마감(sales-close.enabled=true)을 꺼서는 안 된다 — 트리거가 마감 회차의 증가를 건너뛰어 한도 판정이 우회된다"
        }

        val drift = properties.counterDrift
        val a = drift.expiryRepair
        val c = drift.reconcile.enabled
        val f = environment?.activeProfiles?.contains(RECONCILER_PROFILE) == true
        val external = drift.externalReconciler
        val cleanup = drift.cleanup.mode
        check(!(a || c || f || external) || limit in COUNTER_FAMILY) {
            "카운터 어긋남 후보(A expiry-repair=$a · C reconcile.enabled=$c · F reconciler 프로필=$f · external-reconciler=$external)는 " +
                "카운터를 유지하는 매수 방식($COUNTER_FAMILY)에서만 쓴다 — 현재 $limit" + DEFAULTS_HINT
        }
        // F(별도 대조 프로세스)를 쓰는 업무 앱(external-reconciler)이 앱 안 대조(C)까지 켜면 같은 DB를 두 프로세스가 대조한다 — ADR-009 기본값이 C를 켜므로 끄라고 명시해야 한다
        check(!(external && c && !f)) {
            "업무 앱에 external-reconciler=true(F)와 대조(C reconcile.enabled=true)가 함께 켜져 있다 — 대조가 두 프로세스에서 겹친다. " +
                "F를 쓰면 업무 앱은 seat.hold.counter-drift.reconcile.enabled=false를 명시하라(ADR-009 기본값이 C를 켠다)"
        }
        check(!(a && limit == COUNTER_TRIGGER)) {
            "후보 A(expiry-repair)와 B(counter-trigger)는 함께 쓰지 않는다 — B는 앱이 감소하지 않는다(트리거가 내린다)" +
                " — A는 ADR-009 기본값이 켠다: counter-trigger는 seat.hold.counter-drift.expiry-repair=false를 명시하라"
        }
        // 대조(C·F)를 켜면 A 필수(코드 루프 1 L1-10·L1-18, 사용자 결정): 대조는 마감 회차를 보지 않으므로, 마감 회차의 어긋난 쌍이 만료 감소를 실패시키면
        // A 없이는 틱이 계속 롤백된다(만료 정지). counter-trigger는 예외 — 트리거가 마감 회차를 건너뛰어 그 정지 경로가 없다.
        // F 프로세스 자신은 만료를 하지 않으므로 이 검사를 하지 않는다 — 업무 앱이 external-reconciler=true로 같은 검사를 받는다.
        check(!((c || external) && !f && !a && limit != COUNTER_TRIGGER)) {
            "대조(C reconcile.enabled=$c · F external-reconciler=$external)를 켜면 후보 A(seat.hold.counter-drift.expiry-repair=true)가 필수다 " +
                "— 마감 회차 어긋남이 만료를 멈출 수 있다(counter-trigger는 예외) — 현재 $limit" +
                " — C는 ADR-009 기본값이 켠다: A를 끄려면 reconcile.enabled=false도 명시하라"
        }
        check(cleanup == CleanupMode.NONE || limit in CLEANUP_STRATEGIES) {
            "쿼터 행 정리(cleanup.mode=$cleanup)는 $CLEANUP_STRATEGIES 에서만 쓴다 — 현재 $limit(준비 단계·쿼터 행 락 방식은 행을 다시 만든다)" + CLEANUP_HINT
        }
        check(cleanup != CleanupMode.SCHEDULE || properties.salesClose.enabled) {
            "정리 ①(cleanup.mode=schedule)은 판매 마감(sales-close.enabled=true)이 켜져 있어야 한다 — 선점의 마감 판정(회차 행 FOR SHARE)이 정리와의 직렬화 근거다" +
                CLEANUP_HINT
        }
        if (a || c || f || cleanup != CleanupMode.NONE) {
            val missing = LOG_TABLES.filterNot(::exists)
            check(missing.isEmpty()) { "카운터 어긋남 후보·정리를 켰는데 기록 테이블(V7)이 없다: $missing" }
        }
        if (f) {
            val expiryScheduler = environment!!.getProperty("seat.hold.expiry-scheduler-enabled", Boolean::class.java, true)
            check(!expiryScheduler) {
                "$RECONCILER_PROFILE 프로필(후보 F — 대조 전용 프로세스)은 만료 스케줄러를 꺼야 한다(seat.hold.expiry-scheduler-enabled=false) — 앱과 만료가 겹친다"
            }
            check(c) {
                "$RECONCILER_PROFILE 프로필(후보 F)인데 대조가 꺼져 있다(seat.hold.counter-drift.reconcile.enabled=false) — 아무것도 하지 않는 프로세스가 뜬다(코드 루프 1 L1-13)"
            }
        }
    }

    companion object {
        /** 쿼터 테이블(user_hold_quota)을 읽거나 쓰는 매수 방식 */
        val QUOTA_TABLE_STRATEGIES = setOf(COUNTER, COUNTER_UPSERT, COUNTER_UPSERT_PRECHECK, COUNTER_TRIGGER, QUOTA_LOCK, QUOTA_NOWAIT, QUOTA_NOWAIT_EARLY)
        /** cnt를 유지하는 매수 방식(카운터 계열) — 단일 출처는 [UserLimitStrategyType.COUNTER_FAMILY] */
        val COUNTER_FAMILY = UserLimitStrategyType.COUNTER_FAMILY
        /** 쿼터 행 정리를 허용하는 방식 — 트랜잭션 안에서 행을 만들거나(upsert) 트리거가 만든다 */
        val CLEANUP_STRATEGIES = setOf(COUNTER_UPSERT, COUNTER_UPSERT_PRECHECK, COUNTER_TRIGGER)
        val LOG_TABLES = listOf("counter_drift_log", "counter_reconcile_run", "counter_cleanup_run")
        /** ADR-009 기본값 전환(명세 §12) 뒤 조합 거부 안내 — 검사 자체는 바꾸지 않는다(기본값을 조용히 끄지 않는다: 설정·configprops와 실제 동작이 갈리지 않게) */
        private const val DEFAULTS_HINT =
            " — ADR-009 기본값이 A·C·정리 ①을 켠다: 이 방식으로 띄우려면 seat.hold.counter-drift.expiry-repair=false · reconcile.enabled=false · cleanup.mode=none을 명시하라"
        /** 정리 조합 거부(counter · 판매 마감 끔) 안내 — 정리만 끄면 된다(A·C는 이 조합에서 허용된다) */
        private const val CLEANUP_HINT =
            " — 정리 ①은 ADR-009 기본값이 켠다: seat.hold.counter-drift.cleanup.mode=none을 명시하라(A·C는 켠 채 둘 수 있다)"
        const val HOLD_TRIGGER = "seat_hold_quota"
        const val RESERVATION_TRIGGER = "reservation_quota"
    }
}
