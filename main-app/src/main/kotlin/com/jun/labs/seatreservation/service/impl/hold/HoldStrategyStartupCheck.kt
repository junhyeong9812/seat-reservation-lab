package com.jun.labs.seatreservation.service.impl.hold

import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatConfirmProperties
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
import com.jun.labs.seatreservation.service.impl.drift.IncrementalMethod
import com.jun.labs.seatreservation.service.impl.drift.IncrementalSchemaCheck
import com.jun.labs.seatreservation.service.impl.drift.RECONCILER_PROFILE
import org.flywaydb.core.api.CoreErrorCode
import org.flywaydb.core.api.MigrationVersion
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 거부될 기동은 마이그레이션 **앞**에서 거부한다 — 불변식(범위 한정): 설정 조합·방식 ↔ Flyway 위치(V9·V10)·준비된 트랜잭션으로 거부되는 기동은 DB 스키마를 바꾸지 않는다(ADR-012 기본값 교체 코드 리뷰 RC-8).
 * 범위 밖(마이그레이션 뒤에 거부 — ADR-012 §7-13 ⑦·⑧): V4 unique·V8 counter-trigger 위치 검사 · target으로 앞 버전을 띄운 V5·V7 검사 · Flyway 비표준 경로(반복 마이그레이션 · 손으로 지운 V9 객체 등).
 * 기동 검사가 [ApplicationRunner]로만 돌면 Flyway가 먼저 기본 위치의 V9 트리거·V10을 적용한 뒤에야 거부된다 — 안내대로 키를 고쳐
 * 같은 DB로 다시 띄우면 남은 트리거 때문에 또 거부된다(명세 §10 K2·K4).
 * ① 설정 검사([HoldStrategyStartupCheck.checkConfig]) ② 방식 ↔ Flyway 위치·DB 조건(core [IncrementalSchemaCheck.checkSchema] — 마이그레이션 뒤 검사와 같은 규칙):
 *   지금 DB의 스키마 사실(읽기만 — V9·V10을 이미 적용한 DB 포함 · max_prepared_transactions)에 Flyway가 실제로 쓸 위치(`flyway.configuration.locations` ·
 *   `target`까지)가 더할 객체를 얹어 내다본 스키마로 판정한다. 그래서 이미 V9 트리거·V10을 적용한 DB의 K3(method=trigger · 위치 기본값)는 그대로 뜬다.
 *   Flyway가 스스로 마이그레이션 전에 막을 조합(두 V9 · 다른 V9로 다시 기동 등)은 ②를 건너뛰고 Flyway의 거부로 둔다.
 * Flyway가 꺼진 구성에서는 이 전략이 불리지 않는다 — 그래서 [HoldStrategyStartupCheck.run]이 같은 검사를 마이그레이션 뒤 DB로 다시 한다(검사가 빠지지 않게).
 * 이 빈은 DB 접근 빈(JdbcTemplate 등 — Flyway 초기화에 의존)에 기대지 않는다(순환 의존 없음) — DB는 Flyway의 DataSource로 읽는다.
 */
@Configuration(proxyBeanMethods = false)
class HoldStrategyMigrationGate {
    @Bean
    fun holdStrategyFlywayMigrationStrategy(
        properties: SeatHoldProperties,
        confirm: SeatConfirmProperties,
        environment: Environment,
    ) = FlywayMigrationStrategy { flyway ->
        HoldStrategyStartupCheck.checkConfig(properties, environment, confirm)
        val config = flyway.configuration
        val target = config.target
        // target 앞 버전에서 멈추면 그 위치는 객체를 더하지 않는다(LATEST·null = 끝까지 · current·next 등 버전 없는 특수값은 보수적으로 '더하지 않음')
        fun reaches(version: String) = target == null || target == MigrationVersion.LATEST || (target.version != null && target.isAtLeast(version))
        // ADR-015 §설계-5 규칙 6(③ V14 짝 — 내다본 스키마: 이미 적용된 트리거 + 위치가 더할 트리거) · 규칙 7(T2 — 새 모드는 V13까지 닿아야 한다). Flyway 검증·migrate 앞
        val v14Predicted = HoldStrategyStartupCheck.confirmVersionTriggerExists(JdbcTemplate(config.dataSource)) ||
            (config.locations.any { it.path == HoldStrategyStartupCheck.LOCATION_V14 } && reaches("14"))
        HoldStrategyStartupCheck.checkConfirmMigrationPlan(properties, confirm, reachesV13 = reaches("13"), v14Trigger = v14Predicted)
        // ADR-015 R15-2: 이번 migrate가 V12를 만들지 않으면(이미 이력에 있음 · target · 위치 밖) 지금 DB의 V12 인덱스가 곧 마이그레이션 뒤 사실이다 —
        // INVALID · 모양 다름 · (새 모드) 없음은 run이 아니라 여기서 거부한다(거부된 기동이 V13 등 다른 버전을 적용하지 않게). V12가 이번에 만들어지면 run이 본다
        val pending = flyway.info().pending().mapNotNull { it.version?.version }.toSet()
        if (HoldStrategyStartupCheck.CONFIRMED_SEAT_INDEX_VERSION !in pending) {
            HoldStrategyStartupCheck.checkConfirmedSeatIndex(confirm, HoldStrategyStartupCheck.confirmedSeatIndexState(JdbcTemplate(config.dataSource)), "마이그레이션 앞 DB")
        }
        // ADR-015 R15-11: 같은 원칙의 V13 — 이번 migrate가 V13을 적용하지 않으면(이미 이력에 있음 · target · 위치 밖) 지금 DB의 extended_at 열이 곧 마이그레이션 뒤 사실이다 —
        // 새 모드 + 열 없음은 run이 아니라 여기서 거부한다(거부된 기동이 V14 등 다른 버전을 적용하지 않게). V13이 이번에 적용되면 run이 본다
        if (HoldStrategyStartupCheck.EXTENDED_AT_VERSION !in pending) {
            HoldStrategyStartupCheck.checkExtendedAtColumn(confirm, HoldStrategyStartupCheck.extendedAtColumnExists(JdbcTemplate(config.dataSource)), "마이그레이션 앞 DB")
        }
        // Flyway가 스스로 마이그레이션 전에 막는 조합(같은 버전 둘 — 해석 단계에서 FlywayException · 체크섬·설명 불일치 · 적용됐는데 위치에 없는 버전)은
        // Flyway의 거부로 둔다(조합표 'Flyway 검증' 행 — 다른 V9로 다시 기동 등). 검증은 읽기만 한다. 아직 적용하지 않은 버전(pending)은 migrate가 적용할 몫이라 거부가 아니다
        val flywayRejects = config.isValidateOnMigrate && flyway.validateWithResult().invalidMigrations.orEmpty().any { it.errorDetails?.errorCode !in PENDING }
        if (!flywayRejects) {
            val predicted = IncrementalSchemaCheck.readSchema(JdbcTemplate(config.dataSource))
                .afterLocations(config.locations.map { it.path }, reachesV9 = reaches("9"), reachesV10 = reaches("10"))
            IncrementalSchemaCheck.checkSchema(properties.counterDrift.reconcile, predicted)
        }
        flyway.migrate()
    }

    private companion object {
        val PENDING = setOf(CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED, CoreErrorCode.RESOLVED_REPEATABLE_MIGRATION_NOT_APPLIED)
    }
}

/**
 * 기동 시 전략과 환경이 맞는지 확인하고, 어긋나면 앱을 띄우지 않는다(fail-fast) — 측정 조건이 조용히 섞이지 않게.
 * - 유니크 인덱스(V4)는 UNIQUE 전략에서만 있어야 한다: 다른 전략에 있으면 기준선이 바뀌고, UNIQUE에 없으면 아무것도 막지 않는다.
 * - Redis 전략은 Redis에 닿아야 한다.
 * - 매수 방식(ADR-005)은 좌석 3b 위에서만 쓴다: 좌석을 먼저 잠그는 전략(advisory·JVM·Redis 락)과 섞이면 락 순서가 좌석 → 사용자로 뒤집히고,
 *   SERIALIZABLE의 `SET TRANSACTION`이 첫 문장이 아니게 된다(설계·측정이 3b 고정 — 명세 §2).
 * - ADR-009(설계 §7 · 명세 §10 D4): 쿼터 테이블을 쓰는 방식은 그 테이블이 있어야 한다 · 트리거(V8)는 counter-trigger에서만 ·
 *   후보(A·C·F·정리)와 매수 방식 조합 · 후보를 켜면 V7 기록 테이블 · reconciler 프로필이면 만료 스케줄러 끔.
 * - ADR-012 기본값 교체(§8.9 · 명세 §10 K4~K7): 업무 앱 기본값(application.yml)이 A · 증분(trigger 1초) · 안전망 · 정리 ①을 켜고 C를 끈다 —
 *   검사는 기본값을 조용히 끄지 않고 거부 메시지에 끌 키를 안내한다. 더한 거부: F(external-reconciler) + 증분·안전망(대조 중복) · C + 증분(옛 방식 복귀 착각).
 * - ADR-014(§설계-6 · N7): sweep이 아닌 만료 방식(expiry-mode)은 기본 조합(3b + counter-upsert-precheck)에서만 · 측정 주입(inject-app)과 함께 쓰지 않는다 ·
 *   lazy(B)는 정리 ①(cleanup.mode=schedule)이 켜져 있어야 한다(코드 리뷰 CR14-1 — 업무 앱만 · reconciler 프로필은 제외 CR14-26).
 * - 순서: 설정만으로 판정되는 검사([checkConfig])는 Flyway 마이그레이션 앞에서 먼저 돈다([HoldStrategyMigrationGate]) — 거부된 기동이 스키마를 바꾸지 않게.
 *   DB 상태가 필요한 검사(인덱스·트리거·테이블·Redis·증분 스키마)는 여기 [run]에서 본다.
 */
@Component
class HoldStrategyStartupCheck(
    private val properties: SeatHoldProperties,
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    /** 프로필·만료 스케줄러 키 확인용. 직접 생성(테스트)할 때 없으면 그 두 확인은 기본값(프로필 없음·스케줄러 켬)으로 본다 */
    private val environment: Environment? = null,
    /** ADR-015 확정 손잡이. 직접 생성(테스트)할 때 없으면 기본값(conditional-extend — ADR-016 §결정 · 그 전 conditional — ADR-015 §결정) */
    private val confirm: SeatConfirmProperties = SeatConfirmProperties(),
) : ApplicationRunner {

    /** 설정 검사([checkConfig] — Flyway 앞에서 이미 돌았어도 다시: Flyway가 꺼진 구성·직접 생성 테스트에서 빠지지 않게) → DB 상태가 필요한 검사 */
    override fun run(args: ApplicationArguments) {
        checkConfig(properties, environment, confirm)
        expirySchedulerOffWarning(properties, environment)?.let { log.warn(it) }
        val strategy = properties.strategy
        val uniqueIndex = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Int::class.java,
            UniqueConstraintHoldStrategy.UNIQUE_HOLD_INDEX,
        )!! > 0
        check(uniqueIndex == (strategy == HoldStrategyType.UNIQUE)) {
            "전략 $strategy 와 유니크 인덱스(${UniqueConstraintHoldStrategy.UNIQUE_HOLD_INDEX}) 존재=$uniqueIndex 가 맞지 않는다 " +
                "— UNIQUE 전략은 spring.flyway.locations에 classpath:db/migration-unique를 더하고, 다른 전략은 빼야 한다" + LOCATIONS_LIST_HINT
        }
        if (strategy == HoldStrategyType.REDIS_NX || strategy == HoldStrategyType.REDIS_LOCK) {
            redis.connectionFactory!!.connection.use { check(it.ping() == "PONG") { "Redis 응답 없음" } }
        }
        checkCounterDriftSchema()
        checkConfirmSchema()
    }

    /**
     * ADR-015 §설계-5 DB 상태 검사(마이그레이션 뒤 — Flyway가 꺼진 구성에서도 빠지지 않게): V12 인덱스 · V13 열 · V14 트리거를 DB 사실로 본다.
     * - V12 INVALID · 모양 다름(R15-10) → 모든 모드 거부. 없음 → current는 WARN(기준선 재현 target=V12 앞 마지막 버전(기본 위치만 = 7 · T12)) · 새 모드는 unique-index-optional=true가 아니면 거부(N11)
     * - V13 열 없음 + 새 모드 → 거부(T2 — 모든 새 모드)
     * - V14 트리거: 있으면 낙관락 선점 금지 · optimistic이면 있어야 한다(규칙 6과 같은 뜻)
     */
    private fun checkConfirmSchema() {
        val mode = confirm.mode
        if (checkConfirmedSeatIndex(confirm, confirmedSeatIndexState(jdbc), "DB")) {
            log.warn(
                "확정 유니크 인덱스 $CONFIRMED_SEAT_INDEX(V12)가 없다 — seat.confirm.mode=current라 기동한다(기준선 재현 spring.flyway.target=V12 앞 마지막 버전(기본 위치만 = 7 · T12) · ADR-015 §설계-5). " +
                    "운영이면 V12를 적용하라",
            )
        }
        checkExtendedAtColumn(confirm, extendedAtColumnExists(jdbc), "DB")
        checkConfirmVersionTrigger(properties, confirm, confirmVersionTriggerExists(jdbc), "DB")
    }

    private fun exists(table: String): Boolean =
        jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, table) == true

    /** DB 상태가 필요한 카운터 어긋남 검사 — 쿼터 테이블(V5) · 카운터 트리거(V8) · 기록 테이블(V7) · 증분 스키마(core [IncrementalSchemaCheck.check]) */
    private fun checkCounterDriftSchema() {
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
                "— counter-trigger는 spring.flyway.locations에 classpath:db/migration-trigger를 더하고, 다른 방식은 빼야 한다(앱 증감과 트리거가 겹친다)" + LOCATIONS_LIST_HINT
        }
        val drift = properties.counterDrift
        val reconcile = drift.reconcile
        val f = environment?.activeProfiles?.contains(RECONCILER_PROFILE) == true
        if (drift.expiryRepair || reconcile.enabled || f || drift.cleanup.mode != CleanupMode.NONE || reconcile.incremental.enabled || reconcile.safetyNet.enabled) {
            val missing = LOG_TABLES.filterNot(::exists)
            check(missing.isEmpty()) { "카운터 어긋남 후보·정리를 켰는데 기록 테이블(V7)이 없다: $missing" }
        }
        // ADR-012 §4.2.2 스키마(V9 종류 · 트리거 유무·활성 · V10 · 준비된 트랜잭션) — 매수 방식·후보·프로필 조합은 [checkConfig]가 봤다
        IncrementalSchemaCheck.check(jdbc, reconcile, externalApp = false)
    }

    companion object {
        private val log = LoggerFactory.getLogger(HoldStrategyStartupCheck::class.java)

        /**
         * 코드 리뷰 CR14-17: C(lazy-sweep)·D(derived)는 주기 배치가 만료 홀드 행을 지운다(§설계-3.4) — 업무 앱이 만료 스케줄러를 끄면
         * (seat.hold.expiry-scheduler-enabled=false · reconciler 프로필이 아님) 만료 행은 회수(그 사용자의 다음 선점)로만 지워지고, 선점이 더 오지 않는 사용자의 행은 남는다.
         * 기동은 허용한다(테스트가 스케줄러를 끈다) — WARN 문구를 돌려준다. 해당 없으면 null. [run]이 한 번 로그로 남긴다(Flyway 앞 [checkConfig]는 두 번 불려 거기서는 남기지 않는다)
         */
        fun expirySchedulerOffWarning(properties: SeatHoldProperties, environment: Environment?): String? {
            val mode = properties.expiryMode
            if (mode != ExpiryMode.LAZY_SWEEP && mode != ExpiryMode.DERIVED) return null
            if (environment?.activeProfiles?.contains(RECONCILER_PROFILE) == true) return null
            val scheduler = environment?.getProperty("seat.hold.expiry-scheduler-enabled", Boolean::class.java, true) ?: true
            if (scheduler) return null
            return "만료 방식 $mode(seat.hold.expiry-mode)인데 만료 스케줄러가 꺼져 있다(seat.hold.expiry-scheduler-enabled=false) — 주기 배치가 돌지 않아 " +
                "만료 홀드 행은 회수(그 사용자의 다음 선점)로만 지워진다(마감 회차 · 다시 오지 않는 사용자의 행이 남는다 — ADR-014 §설계-6 · 코드 리뷰 CR14-17). " +
                "운영이면 seat.hold.expiry-scheduler-enabled=true로 띄워라"
        }

        /**
         * 설정(바인딩된 속성 · 활성 프로필)만으로 판정되는 기동 검사 — DB를 보지 않는다. Flyway 마이그레이션 앞([HoldStrategyMigrationGate])과
         * [run] 첫머리에서 부른다(같은 검사 · 결정적이라 두 번 불러도 결과가 같다).
         * @param environment 프로필·만료 스케줄러 키 확인용 — null이면 프로필 없음·스케줄러 켬으로 본다(직접 생성 테스트)
         */
        fun checkConfig(properties: SeatHoldProperties, environment: Environment?, confirm: SeatConfirmProperties = SeatConfirmProperties()) {
            // ADR-015 확정 손잡이 규칙이 먼저 — 새 모드의 조합 위반은 다른 검사 문구보다 앞서 seat.confirm.* 키로 알린다(T3)
            checkConfirmConfig(properties, environment, confirm)
            val strategy = properties.strategy
            check(properties.limitStrategy == UserLimitStrategyType.NONE || strategy == HoldStrategyType.PESSIMISTIC_NOWAIT) {
                "매수 방식 ${properties.limitStrategy}는 좌석 전략 PESSIMISTIC_NOWAIT에서만 쓴다(현재 $strategy) — 락 순서·격리 수준 설정이 이 조합을 전제한다"
            }
            checkExpiryModeConfig(properties, environment)
            checkCounterDriftConfig(properties, environment)
        }

        /**
         * ADR-014 §설계-6(N7): sweep이 아닌 만료 방식은 기본 조합에서만 — 다른 조합에서 조용히 A로 돌지 않게 거부한다(명세 §2 적용 범위).
         * 좌석 3b가 아니면 '홀드를 만들거나 RESERVED로 바꾸는 경로가 좌석 행 잠금을 먼저 쥔다'(§설계-4 (가)의 전제)가 깨지고,
         * counter-trigger는 V8 트리거의 홀드 DELETE −1이 회수 H5의 앱 감소와 겹치며, 선조회가 없는 방식은 H1의 자리가 없다.
         * 측정 주입(inject-app)도 거부한다(Q13) — 주입의 expiry 경로가 A의 감소(ExpiryCounters.decrement) 안에만 있다.
         */
        private fun checkExpiryModeConfig(properties: SeatHoldProperties, environment: Environment?) {
            val mode = properties.expiryMode
            check(properties.expiryBatchSize > 0) { "seat.hold.expiry-batch-size는 1 이상이어야 한다(현재 ${properties.expiryBatchSize})" }
            check(properties.expiryMaxChunksPerTick > 0) {
                "seat.hold.expiry-max-chunks-per-tick는 1 이상이어야 한다(현재 ${properties.expiryMaxChunksPerTick}) — 0이면 C·D 배치가 아무것도 지우지 않는다(코드 리뷰 CR14-2)"
            }
            if (mode == ExpiryMode.SWEEP) return
            check(properties.strategy == HoldStrategyType.PESSIMISTIC_NOWAIT && properties.limitStrategy == COUNTER_UPSERT_PRECHECK) {
                "만료 방식 $mode(seat.hold.expiry-mode)는 좌석 전략 PESSIMISTIC_NOWAIT + 매수 방식 COUNTER_UPSERT_PRECHECK(기본 조합)에서만 쓴다 " +
                    "— 현재 ${properties.strategy} + ${properties.limitStrategy}. seat.hold.strategy=pessimistic-nowait · seat.hold.limit-strategy=counter-upsert-precheck로 두거나 " +
                    "seat.hold.expiry-mode=sweep으로 띄워라(ADR-014 §설계-6)"
            }
            check(!properties.loadtest.injectApp.enabled) {
                "만료 방식 $mode(seat.hold.expiry-mode)와 측정 전용 앱 경로 주입(seat.hold.loadtest.inject-app.enabled=true)은 함께 쓰지 않는다 " +
                    "— 주입 expiry 경로는 sweep의 만료 감소 안에만 있다. inject-app.enabled=false로 두거나 expiry-mode=sweep으로 띄워라(ADR-014 Q13)"
            }
            // 코드 리뷰 CR14-1: B는 주기 배치가 없고 회수는 그 사용자의 다음 선점에서만 일어난다 — 선점이 더는 오지 않는 마감 회차의 만료 홀드는
            // 정리 ①(QuotaCleanup.cleanLazySchedule)만 지운다. 정리를 끄거나 ②(zero)로 두면 그 행과 쿼터 행이 영영 남는다(조용히 쌓이지 않게 거부)
            // 코드 리뷰 CR14-26: reconciler 프로필(F — 대조 전용)은 정리를 업무 앱에 맡겨 cleanup.mode=none이다(application-reconciler.yml) — 이 규칙은 업무 앱에만 건다
            // (판정 기준은 [expirySchedulerOffWarning]과 같다 — 활성 프로필에 reconciler)
            val f = environment?.activeProfiles?.contains(RECONCILER_PROFILE) == true
            val cleanup = properties.counterDrift.cleanup.mode
            check(f || mode != ExpiryMode.LAZY || cleanup == CleanupMode.SCHEDULE) {
                "만료 방식 lazy(seat.hold.expiry-mode)는 주기 배치가 없어 마감 회차의 만료 홀드를 정리 ①만 지운다 — 현재 cleanup.mode=$cleanup. " +
                    "seat.hold.counter-drift.cleanup.mode=schedule로 두거나 seat.hold.expiry-mode=lazy-sweep·derived·sweep으로 띄워라(ADR-014 §설계-3.4 · 코드 리뷰 CR14-1)"
            }
        }

        /**
         * ADR-015 §설계-5 규칙 1~5 + §설계-10 T1 · T8 — 설정(바인딩 값 · 프로필)만으로 판정. 손잡이를 주지 않으면 기본 conditional-extend(ADR-016 §결정 · 그 전 conditional — ADR-015 §결정)라 규칙 1 · 2 · 4 · 5를 받는다 — current를 명시하면(지연 0 · 키 false) 통과한다.
         * 거부는 [IllegalStateException] · 메시지에 위반한 키 이름(T3).
         */
        private fun checkConfirmConfig(properties: SeatHoldProperties, environment: Environment?, confirm: SeatConfirmProperties) {
            val mode = confirm.mode
            val profiles = environment?.activeProfiles.orEmpty().toSet()
            val loadtest = LOADTEST_PROFILE in profiles
            // 규칙 3(T8 — 모드 무관): 확정 지연 주입은 측정 장치
            val delay = confirm.loadtest.delay
            check(!delay.isNegative) { "seat.confirm.loadtest.delay는 0 이상이어야 한다(현재 $delay)" }
            check(delay.isZero || loadtest) {
                "seat.confirm.loadtest.delay=$delay(확정 지연 주입)는 $LOADTEST_PROFILE 프로필에서만 쓴다 — 측정 장치다(ADR-015 §설계-5 규칙 3)"
            }
            // T1: V12 없이 새 모드를 띄우는 키는 테스트 · 측정 프로필에서만(일반 배포에서 인덱스 없음을 가리는 데 쓸 수 없다)
            check(!confirm.uniqueIndexOptional || loadtest || TEST_PROFILE in profiles) {
                "seat.confirm.unique-index-optional=true는 $TEST_PROFILE · $LOADTEST_PROFILE 프로필에서만 받는다(재현 테스트의 'V12 없음' 판 — ADR-015 §설계-10 T1) — " +
                    "현재 프로필 $profiles"
            }
            // ADR-016 설계-9.4(N16-6 — 모드 무관): θ 옛 키는 새 키로 바뀌었다 — Spring은 모르는 키를 조용히 무시하므로 남아 있으면 θ가 0으로 무음 후퇴한다
            check(environment == null || !Binder.get(environment).bind(OLD_MIN_REMAINING_KEY, String::class.java).isBound) {
                "$OLD_MIN_REMAINING_KEY 는 더 쓰지 않는다 — seat.confirm.payment-min-remaining(θ · ① 결제 시작 확인과 ④ 연장이 함께 쓴다)으로 바꿔라(ADR-016 설계-9.4)"
            }
            val theta = confirm.paymentMinRemaining
            val margin = confirm.payByMargin
            check(!theta.isNegative) { "seat.confirm.payment-min-remaining는 0 이상이어야 한다(현재 $theta)" }
            check(!margin.isNegative) { "seat.confirm.pay-by-margin는 0 이상이어야 한다(현재 $margin)" }
            if (mode == ConfirmMode.CURRENT) return
            val key = "seat.confirm.mode=${mode.key()}"
            // ADR-016 설계-9.3: payBy = expiresAt − M — 결제 시작 200은 남은 시간 > θ일 때만 나가므로 M ≤ θ면 payBy가 그 :now 뒤다
            if (mode.paymentStartEnabled) {
                check(margin <= theta) {
                    "$key 는 seat.confirm.pay-by-margin ≤ seat.confirm.payment-min-remaining여야 한다 — 현재 pay-by-margin=$margin · payment-min-remaining=$theta(ADR-016 설계-9.3)"
                }
                // θ ≥ TTL이면 새 홀드도 남은 시간이 θ 이하라 ① 확인은 전부 insufficient · ④ 연장은 전부 too_late가 된다(M ≤ θ라 M < TTL — 선점 201의 payBy도 미래)
                check(theta < properties.ttl) {
                    "$key 는 seat.confirm.payment-min-remaining < seat.hold.ttl이어야 한다 — 현재 payment-min-remaining=$theta · ttl=${properties.ttl}(ADR-016 측정 명세 M16-6)"
                }
            }
            // 규칙 1: B·C·D는 확정 첫머리에서 LazyConfirmProcess로 가므로 손잡이가 조용히 무시된다
            check(properties.expiryMode == ExpiryMode.SWEEP) {
                "$key 는 seat.hold.expiry-mode=sweep(A)에서만 쓴다 — 현재 ${properties.expiryMode}(B·C·D 확정은 ADR-014 최소 가드 그대로). " +
                    "seat.hold.expiry-mode=sweep으로 두거나 seat.confirm.mode=current로 띄워라(ADR-015 §설계-5 규칙 1)"
            }
            // 규칙 2: 새 모드의 정합 논리는 좌석 3b(선점 NOWAIT) · 매수 counter-upsert-precheck(쿼터 행 → 홀드 → 좌석 잠금 순서) 위에 선다
            check(properties.strategy == HoldStrategyType.PESSIMISTIC_NOWAIT && properties.limitStrategy == COUNTER_UPSERT_PRECHECK) {
                "$key 는 seat.hold.strategy=pessimistic-nowait + seat.hold.limit-strategy=counter-upsert-precheck(기본 조합)에서만 쓴다 " +
                    "— 현재 ${properties.strategy} + ${properties.limitStrategy}. 기본 조합으로 두거나 seat.confirm.mode=current로 띄워라(ADR-015 §설계-5 규칙 2)"
            }
            // 규칙 4: ④ 연장 창
            if (mode == ConfirmMode.CONDITIONAL_EXTEND) {
                val window = confirm.paymentWindow
                check(window > java.time.Duration.ZERO && theta < window) {
                    "$key 는 seat.confirm.payment-window > 0 · 0 ≤ seat.confirm.payment-min-remaining < payment-window여야 한다 " +
                        "— 현재 payment-window=$window · payment-min-remaining=$theta(두 키를 명시하라 — ADR-015 §설계-5 규칙 4 · §설계-10 T6 · 키 이름 ADR-016 설계-9.4)"
                }
            }
            // 규칙 5: 주입 이음새(afterExpiryDecrement)는 JPA 배치의 가드 감소에만 있다 — A′와 함께 켜면 주입이 조용히 빠진다
            check(!properties.loadtest.injectApp.enabled) {
                "$key 와 측정 전용 앱 경로 주입(seat.hold.loadtest.inject-app.enabled=true)은 함께 쓰지 않는다 — 주입 expiry 경로는 JPA 배치(current)의 감소 안에만 있다. " +
                    "inject-app.enabled=false로 두거나 seat.confirm.mode=current로 띄워라(ADR-015 §설계-5 규칙 5)"
            }
        }

        /**
         * ADR-015 §설계-5 규칙 6 · 7(T2) — Flyway가 쓸 설정(target · 위치)과 이미 적용된 V14 트리거로 내다본 스키마에서 판정한다([HoldStrategyMigrationGate] — migrate 앞).
         * @param reachesV13 target이 13에 닿는가(target을 주지 않으면 참) · @param v14Trigger 마이그레이션 뒤 V14 트리거가 있을 것인가
         */
        fun checkConfirmMigrationPlan(properties: SeatHoldProperties, confirm: SeatConfirmProperties, reachesV13: Boolean, v14Trigger: Boolean) {
            val mode = confirm.mode
            check(mode == ConfirmMode.CURRENT || reachesV13) {
                "seat.confirm.mode=${mode.key()}는 spring.flyway.target이 13 이상이어야 한다(V12 유니크 인덱스 · V13 extended_at 열) — target 12 이하는 current 기준선 재현 전용이다. " +
                    "target을 풀거나 seat.confirm.mode=current로 띄워라" +
                    "(ADR-015 §설계-5 규칙 7 · §설계-10 T2)"
            }
            checkConfirmVersionTrigger(properties, confirm, v14Trigger, "내다본 스키마")
        }

        /** 규칙 6 · run 검사 공용: V14 트리거가 있으면 낙관락 선점 금지 · optimistic이면 트리거가 있어야 한다 */
        private fun checkConfirmVersionTrigger(properties: SeatHoldProperties, confirm: SeatConfirmProperties, trigger: Boolean, where: String) {
            check(!(trigger && properties.strategy == HoldStrategyType.OPTIMISTIC)) {
                "좌석 버전 트리거(V14 — $CONFIRM_VERSION_TRIGGER · $where)가 있으면 낙관락 선점(seat.hold.strategy=optimistic)을 쓰지 않는다 — 트리거가 먼저 version을 올려 " +
                    "선점이 늘 진다(ADR-015 §3.3 (가)). spring.flyway.locations에서 classpath:$LOCATION_V14 를 빼거나(아직 적용 전일 때만) 다른 좌석 전략으로 띄워라 " +
                    "(seat.confirm.mode=${confirm.mode.key()})"
            }
            check(confirm.mode != ConfirmMode.OPTIMISTIC || trigger) {
                "seat.confirm.mode=optimistic은 좌석 버전 트리거(V14 — $CONFIRM_VERSION_TRIGGER)가 있어야 한다 — $where 에 없다. " +
                    "spring.flyway.locations에 classpath:$LOCATION_V14 를 더하라(목록 전체를 덮어쓴다 — 기본 위치 포함 · ADR-015 §설계-5 규칙 6)"
            }
        }

        /**
         * ADR-015 V12 인덱스의 DB 사실(R15-10 — 관문 · run 공용 단일 판정). [present] = 그 이름의 인덱스가 있다 · [problem] = 있는데 강제력이 없거나 모양이 V12와 다르다
         * (INVALID · 유니크 아님 · 대상 표 ≠ reservation · 열 ≠ (seat_id) · 부분 조건 ≠ status = 'CONFIRMED') — 사유 문구. 없으면 null.
         */
        class ConfirmedSeatIndexState(val present: Boolean, val problem: String?)

        /** 읽기만 — 빈 DB에서도 돈다(to_regclass가 null → 없음) */
        fun confirmedSeatIndexState(jdbc: JdbcTemplate): ConfirmedSeatIndexState {
            val row = jdbc.queryForList(
                """
                SELECT i.indisvalid AS valid, i.indisunique AS uniq, t.relname AS tbl, i.indnatts AS natts, i.indexprs IS NULL AS no_exprs,
                       (SELECT a.attname FROM pg_attribute a WHERE a.attrelid = i.indrelid AND a.attnum = i.indkey[0]) AS col,
                       pg_get_expr(i.indpred, i.indrelid) AS pred
                FROM pg_index i JOIN pg_class t ON t.oid = i.indrelid
                WHERE i.indexrelid = to_regclass(?)
                """.trimIndent(),
                CONFIRMED_SEAT_INDEX,
            ).singleOrNull() ?: return ConfirmedSeatIndexState(present = false, problem = null)
            val problems = buildList {
                if (row["valid"] != true) add("INVALID(indisvalid=false)")
                if (row["uniq"] != true) add("유니크가 아니다(indisunique=false)")
                if (row["tbl"] != "reservation") add("대상 표가 reservation이 아니다(${row["tbl"]})")
                if ((row["natts"] as Number).toInt() != 1 || row["no_exprs"] != true || row["col"] != "seat_id") add("열이 (seat_id)가 아니다(열 수 ${row["natts"]} · 첫 열 ${row["col"]} · 식 열 ${row["no_exprs"] != true})")
                val pred = row["pred"] as String?
                if (pred == null || normalizePredicate(pred) != CONFIRMED_SEAT_PREDICATE) add("부분 조건이 status = 'CONFIRMED'가 아니다($pred)")
            }
            return ConfirmedSeatIndexState(present = true, problem = problems.takeIf { it.isNotEmpty() }?.joinToString(" · "))
        }

        /** pg_get_expr 출력(예: `((status)::text = 'CONFIRMED'::text)`)에서 괄호 · 공백 · 문자열 캐스트를 지운다 — 열 타입(varchar · text)에 따른 표기 차이만 흡수 */
        private fun normalizePredicate(pred: String): String =
            pred.replace("::character varying", "").replace("::text", "").replace("(", "").replace(")", "").replace(" ", "")

        private const val CONFIRMED_SEAT_PREDICATE = "status='CONFIRMED'"

        /**
         * V12 인덱스 판정(관문 · run 공용 — ADR-015 §설계-5 'DB 상태 검사' · N11 · R15-2 · R15-10): 문제 있음 → 모든 모드 거부(사유 포함) ·
         * 없음 + 새 모드 → unique-index-optional=true가 아니면 거부. 돌려주는 값 = '없음 + current'(호출자가 WARN을 남긴다 — run만).
         */
        fun checkConfirmedSeatIndex(confirm: SeatConfirmProperties, state: ConfirmedSeatIndexState, where: String): Boolean {
            val mode = confirm.mode
            check(state.problem == null) {
                "확정 유니크 인덱스 $CONFIRMED_SEAT_INDEX(V12 · $where)가 강제력이 없거나 V12와 모양이 다르다: ${state.problem} (seat.confirm.mode=${mode.key()}) — " +
                    "같은 좌석 CONFIRMED ≤ 1을 DB가 강제하지 못한다. 그 인덱스를 지우고(DROP INDEX CONCURRENTLY) 사전 검사(R0) 뒤 V12 정의 그대로 다시 만들어라" +
                    "(실패한 V12 이력이 남았으면 flyway repair 뒤 다시 적용 — ADR-015 §설계-4 재시도 절차)"
            }
            if (state.present) return false
            if (mode == ConfirmMode.CURRENT) return true
            check(confirm.uniqueIndexOptional) {
                "seat.confirm.mode=${mode.key()}는 확정 유니크 인덱스 $CONFIRMED_SEAT_INDEX(V12 — 유효)가 있어야 한다 — $where 에 없다(N11). " +
                    "기본 위치 classpath:db/migration의 V12를 적용하라(재현 테스트의 'V12 없음' 판만 test·loadtest 프로필에서 seat.confirm.unique-index-optional=true)"
            }
            return false
        }

        /** V13 seat_hold.extended_at 열이 이 DB에 있는가(읽기만 — 빈 DB에서도 돈다 · 관문 · run 공용 단일 판정 — R15-11) */
        fun extendedAtColumnExists(jdbc: JdbcTemplate): Boolean = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'seat_hold' AND column_name = 'extended_at'",
            Int::class.java,
        )!! > 0

        /** V13 열 판정(관문 · run 공용 — ADR-015 §설계-10 T2 · R15-11): 새 모드인데 열이 없으면 거부. current는 열을 쓰지 않는다 */
        fun checkExtendedAtColumn(confirm: SeatConfirmProperties, exists: Boolean, where: String) {
            val mode = confirm.mode
            check(mode == ConfirmMode.CURRENT || exists) {
                "seat.confirm.mode=${mode.key()}는 seat_hold.extended_at 열(V13 — 기본 위치)이 있어야 한다 — $where 에 없다(spring.flyway.target이 13 미만? · V13 이력은 있는데 열이 지워졌으면 " +
                    "열을 V13 정의대로 되돌리거나 flyway repair로 V13 이력을 지운 뒤 다시 적용하라) · ADR-015 §설계-10 T2"
            }
        }

        /** V14 트리거가 이 DB에 있는가(읽기만 — 빈 DB에서도 돈다) */
        fun confirmVersionTriggerExists(jdbc: JdbcTemplate): Boolean = jdbc.queryForObject(
            "SELECT count(*) FROM pg_trigger WHERE tgname = ? AND NOT tgisinternal", Int::class.java, CONFIRM_VERSION_TRIGGER,
        )!! > 0

        private fun ConfirmMode.key() = name.lowercase().replace('_', '-')

        private fun checkCounterDriftConfig(properties: SeatHoldProperties, environment: Environment?) {
            val limit = properties.limitStrategy
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
            val inc = drift.reconcile.incremental
            val safety = drift.reconcile.safetyNet.enabled
            check(!(a || c || f || external) || limit in COUNTER_FAMILY) {
                "카운터 어긋남 후보(A expiry-repair=$a · C reconcile.enabled=$c · F reconciler 프로필=$f · external-reconciler=$external)는 " +
                    "카운터를 유지하는 매수 방식($COUNTER_FAMILY)에서만 쓴다 — 현재 $limit" + DEFAULTS_HINT
            }
            // F(별도 대조 프로세스)를 쓰는 업무 앱(external-reconciler)이 앱 안 대조(C)까지 켜면 같은 DB를 두 프로세스가 대조한다
            check(!(external && c && !f)) {
                "업무 앱에 external-reconciler=true(F)와 대조(C reconcile.enabled=true)가 함께 켜져 있다 — 대조가 두 프로세스에서 겹친다. " +
                    "F를 쓰면 업무 앱은 seat.hold.counter-drift.reconcile.enabled=false로 둬라(업무 앱 기본값은 끔 — application.yml)"
            }
            // ADR-012 명세 §10 K6: F를 쓰는 업무 앱이 업무 앱 기본값(증분·안전망 켬)을 그대로 두면 대조가 두 프로세스에서 겹친다(F = C 전체 대조)
            check(!(external && !f && (inc.enabled || safety))) {
                "업무 앱에 external-reconciler=true(F)와 증분(${IncrementalSchemaCheck.PREFIX}.enabled=${inc.enabled})·" +
                    "안전망(${IncrementalSchemaCheck.SAFETY_PREFIX}.enabled=$safety)이 함께 켜져 있다 — 대조가 두 프로세스에서 겹친다. " +
                    "F를 쓰면 업무 앱은 ${IncrementalSchemaCheck.PREFIX}.enabled=false · ${IncrementalSchemaCheck.SAFETY_PREFIX}.enabled=false를 명시하라(업무 앱 기본값이 둘을 켠다)"
            }
            // ADR-012 명세 §10 K7: C를 켜서 옛 방식으로 되돌린다고 생각했는데 업무 앱 기본값의 증분이 그대로 켜져 있는 경우 — 조용히 둘 다 돌지 않게 거부(옛 방식 조합 안내)
            check(!(c && inc.enabled)) {
                "전체 대조 C(seat.hold.counter-drift.reconcile.enabled=true)와 증분(${IncrementalSchemaCheck.PREFIX}.enabled=true)을 함께 켜지 않는다 — " +
                    "업무 앱 기본값은 증분 + 안전망이다. 옛 방식(ADR-009 C 60초)으로 되돌리려면$OLD_WAY_HINT"
            }
            check(!(a && limit == COUNTER_TRIGGER)) {
                "후보 A(expiry-repair)와 B(counter-trigger)는 함께 쓰지 않는다 — B는 앱이 감소하지 않는다(트리거가 내린다)" +
                    " — A·증분은 업무 앱 기본값이 켠다: counter-trigger는 seat.hold.counter-drift.expiry-repair=false · " +
                    "${IncrementalSchemaCheck.PREFIX}.enabled=false를 명시하라(증분은 A가 필수 — 안전망은 켠 채 둘 수 있다)"
            }
            // 대조(C·F)를 켜면 A 필수(코드 루프 1 L1-10·L1-18, 사용자 결정): 대조는 마감 회차를 보지 않으므로, 마감 회차의 어긋난 쌍이 만료 감소를 실패시키면
            // A 없이는 틱이 계속 롤백된다(만료 정지). counter-trigger는 예외 — 트리거가 마감 회차를 건너뛰어 그 정지 경로가 없다.
            // F 프로세스 자신은 만료를 하지 않으므로 이 검사를 하지 않는다 — 업무 앱이 external-reconciler=true로 같은 검사를 받는다.
            check(!((c || external) && !f && !a && limit != COUNTER_TRIGGER)) {
                "대조(C reconcile.enabled=$c · F external-reconciler=$external)를 켜면 후보 A(seat.hold.counter-drift.expiry-repair=true)가 필수다 " +
                    "— 마감 회차 어긋남이 만료를 멈출 수 있다(counter-trigger는 예외) — 현재 $limit" +
                    " — A를 끄려면 reconcile.enabled=false · external-reconciler=false로 둬라"
            }
            // 카운터 계열이 아닌 방식은 정리만 끄면 뜨는 게 아니다(A·증분·안전망도 그 방식에서 거부된다) — 그 방식에는 기본값 전체를 끄는 안내를 붙인다
            check(cleanup == CleanupMode.NONE || limit in CLEANUP_STRATEGIES) {
                "쿼터 행 정리(cleanup.mode=$cleanup)는 $CLEANUP_STRATEGIES 에서만 쓴다 — 현재 $limit(준비 단계·쿼터 행 락 방식은 행을 다시 만든다)" +
                    if (limit in COUNTER_FAMILY) CLEANUP_HINT else DEFAULTS_HINT
            }
            check(cleanup != CleanupMode.SCHEDULE || properties.salesClose.enabled) {
                "정리 ①(cleanup.mode=schedule)은 판매 마감(sales-close.enabled=true)이 켜져 있어야 한다 — 선점의 마감 판정(회차 행 FOR SHARE)이 정리와의 직렬화 근거다" +
                    CLEANUP_HINT
            }
            checkIncrementalConfig(properties, environment, limit, a, c)
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

        /**
         * ADR-012 §4.2.2 · §4.2.5 · §4.2.7 기동 검사 중 설정만 보는 쪽 — 매수 방식·후보·프로필 조합. 새 손잡이를 하나도 주지 않으면 통과한다.
         * 스키마(V9 종류 · 트리거 유무·활성 · V10 · 준비된 트랜잭션)는 core [IncrementalSchemaCheck.check]가 [run]에서 본다.
         */
        private fun checkIncrementalConfig(
            properties: SeatHoldProperties,
            environment: Environment?,
            limit: UserLimitStrategyType,
            a: Boolean,
            c: Boolean,
        ) {
            val reconcile = properties.counterDrift.reconcile
            val inc = reconcile.incremental
            val safety = reconcile.safetyNet.enabled
            val incKey = IncrementalSchemaCheck.PREFIX
            val safetyKey = IncrementalSchemaCheck.SAFETY_PREFIX
            check(!((inc.enabled || safety) && limit !in COUNTER_FAMILY)) {
                "증분($incKey.enabled=${inc.enabled})·안전망($safetyKey.enabled=$safety)은 카운터를 유지하는 매수 방식($COUNTER_FAMILY)에서만 쓴다 — 현재 $limit" + DEFAULTS_HINT
            }
            // rv41 codex H2: B의 cnt 변경은 V8 quota_add의 UPDATE라 ⓒ 앱 문장이 덮지 못한다 — 커밋된 앱 경로 변경이 후보가 되지 않는다(§3-1)
            check(!(inc.method == IncrementalMethod.STMT && limit == COUNTER_TRIGGER)) {
                "$incKey.method=stmt 와 seat.hold.limit-strategy=counter-trigger는 함께 쓰지 않는다 — V8 quota_add의 UPDATE는 ⓒ 앱 문장이 updated_at을 남기지 못한다(counter-trigger에서는 trigger만)"
            }
            // 증분은 A가 필수(지금 C와 같은 규칙 — 마감 회차 어긋남이 만료를 멈출 수 있다). counter-trigger는 A와 함께 못 켜므로 증분 틱도 못 돈다(§4.2.13 #2 — 표시만 허용)
            check(!(inc.enabled && !a)) {
                "증분($incKey.enabled=true)을 켜면 후보 A(seat.hold.counter-drift.expiry-repair=true)가 필수다 — 현재 $limit · expiry-repair=false" +
                    " (counter-trigger는 A를 켤 수 없어 증분 틱 대신 표시만 — enabled=false) — 증분은 업무 앱 기본값이 켠다: A를 끄려면 $incKey.enabled=false를 명시하라"
            }
            // 안전망은 C를 대신하는 대조 — C와 같은 A 필수 규칙(counter-trigger는 예외 — 트리거가 마감 회차를 건너뛴다)
            check(!(safety && !a && limit != COUNTER_TRIGGER)) {
                "안전망($safetyKey.enabled=true)을 켜면 후보 A(seat.hold.counter-drift.expiry-repair=true)가 필수다(counter-trigger는 예외) — 현재 $limit" +
                    " — 안전망은 업무 앱 기본값이 켠다: A를 끄려면 $safetyKey.enabled=false를 명시하라"
            }
            // 위 K7(C + 증분)과 같은 갈래 — 증분만 끄고 안전망(업무 앱 기본값 켬)을 남긴 옛 방식 복귀
            check(!(safety && c)) {
                "안전망($safetyKey.enabled=true)과 전체 대조 C(seat.hold.counter-drift.reconcile.enabled=true)를 함께 켜지 않는다 — 전체 훑기가 두 개가 된다. " +
                    "안전망을 쓰면 reconcile.enabled=false로 둬라. 옛 방식(ADR-009 C 60초)으로 되돌리려면$OLD_WAY_HINT"
            }
            // rv43 Opus M3: 증분은 조건부 보장(창 상한·재시작·넘침·등록 예외는 보장 밖)이라 그 몫을 받을 전체 훑기(안전망 · C)가 하나는 있어야 한다.
            // 외부 대조(F external-reconciler)는 이 자리의 대안이 아니다 — 업무 앱이 external이면 증분 자체가 위 K6 검사에서 거부된다(F 프로세스는 C 필수)
            check(!(inc.enabled && !safety && !c)) {
                "증분($incKey.enabled=true)만 켜고 전체 훑기를 모두 껐다($safetyKey.enabled=false · seat.hold.counter-drift.reconcile.enabled=false) — " +
                    "증분 보장 밖 쌍을 고칠 곳이 없다. 안전망($safetyKey.enabled=true)을 켜라" +
                    "(seat.hold.counter-drift.external-reconciler=true는 대안이 아니다 — F를 쓰는 업무 앱은 증분·안전망을 모두 끈다)"
            }
            val injectApp = properties.loadtest.injectApp
            val loadtest = environment?.activeProfiles?.contains(LOADTEST_PROFILE) == true
            check(!(injectApp.enabled && !loadtest)) {
                "측정 전용 앱 경로 주입(seat.hold.loadtest.inject-app.enabled=true)은 $LOADTEST_PROFILE 프로필에서만 쓴다 — 프로필 없이 켰다(주입 빈이 없어 아무것도 하지 않는다)"
            }
            // core의 설정 쪽 검사(방식 memory 조합) — 스키마 쪽은 [run]에서 [IncrementalSchemaCheck.check]가 본다
            IncrementalSchemaCheck.checkConfig(reconcile, externalApp = false)
        }

        const val LOADTEST_PROFILE = "loadtest"
        /** ADR-016 설계-9.4 — θ 옛 키(있으면 기동 거부 · N16-6) */
        const val OLD_MIN_REMAINING_KEY = "seat.confirm.extend-min-remaining"
        /** 테스트 실행 프로필(main-app 테스트 클래스패스 config/application.properties가 spring.profiles.include로 더한다) — ADR-015 T1 */
        const val TEST_PROFILE = "test"
        /** ADR-015 V12 — 확정 예약 부분 유니크 인덱스(기본 위치) */
        const val CONFIRMED_SEAT_INDEX = "uq_reservation_confirmed_seat"
        /** 그 인덱스를 만드는 Flyway 버전(V12__reservation_confirmed_seat_unique.sql) */
        const val CONFIRMED_SEAT_INDEX_VERSION = "12"
        /** seat_hold.extended_at 열을 만드는 Flyway 버전(V13 — 기본 위치) */
        const val EXTENDED_AT_VERSION = "13"
        /** ADR-015 V14 — 좌석 상태 변경 버전 트리거(선택 위치) */
        const val CONFIRM_VERSION_TRIGGER = "product_seat_status_version"
        const val LOCATION_V14 = "db/migration-confirm-version"
        /** 쿼터 테이블(user_hold_quota)을 읽거나 쓰는 매수 방식 */
        val QUOTA_TABLE_STRATEGIES = setOf(COUNTER, COUNTER_UPSERT, COUNTER_UPSERT_PRECHECK, COUNTER_TRIGGER, QUOTA_LOCK, QUOTA_NOWAIT, QUOTA_NOWAIT_EARLY)
        /** cnt를 유지하는 매수 방식(카운터 계열) — 단일 출처는 [UserLimitStrategyType.COUNTER_FAMILY] */
        val COUNTER_FAMILY = UserLimitStrategyType.COUNTER_FAMILY
        /** 쿼터 행 정리를 허용하는 방식 — 트랜잭션 안에서 행을 만들거나(upsert) 트리거가 만든다 */
        val CLEANUP_STRATEGIES = setOf(COUNTER_UPSERT, COUNTER_UPSERT_PRECHECK, COUNTER_TRIGGER)
        val LOG_TABLES = listOf("counter_drift_log", "counter_reconcile_run", "counter_cleanup_run")
        /**
         * 카운터 계열이 아닌 매수 방식 거부 안내(명세 §10 K4) — 업무 앱 기본값(ADR-012 · application.yml)이 켠 것을 모두 끈다. 검사 자체는 기본값을 조용히 끄지 않는다
         * (설정·configprops와 실제 동작이 갈리지 않게). method=none · 위치 db/migration은 V9 트리거·V10을 만들지 않는다(그 매수 방식은 증분 표시가 필요 없다)
         */
        private const val DEFAULTS_HINT =
            " — 업무 앱 기본값(ADR-012 — application.yml)이 A·정리 ①·증분(trigger)·안전망을 켠다: 이 방식으로 띄우려면 seat.hold.counter-drift.expiry-repair=false · " +
                "cleanup.mode=none · ${IncrementalSchemaCheck.PREFIX}.enabled=false · ${IncrementalSchemaCheck.SAFETY_PREFIX}.enabled=false · " +
                "${IncrementalSchemaCheck.PREFIX}.method=none · spring.flyway.locations=classpath:db/migration을 명시하라(reconcile.enabled는 기본 끔)"
        /** 정리 조합 거부(카운터 계열의 counter · 판매 마감 끔) 안내 — 정리만 끄면 된다(A·증분·안전망은 이 조합에서 허용된다). 카운터 계열이 아니면 [DEFAULTS_HINT] */
        private const val CLEANUP_HINT =
            " — 정리 ①은 업무 앱 기본값이 켠다: seat.hold.counter-drift.cleanup.mode=none을 명시하라(A·증분·안전망은 켠 채 둘 수 있다)"
        /** 옛 방식(ADR-009 C 60초 · one-shot) 복귀 조합 안내(명세 §10 K2·K3) — C + 증분 · C + 안전망 거부 메시지 끝에 붙는다 */
        private const val OLD_WAY_HINT =
            " seat.hold.counter-drift.reconcile.enabled=true · ${IncrementalSchemaCheck.PREFIX}.enabled=false · ${IncrementalSchemaCheck.PREFIX}.method=none · " +
                "${IncrementalSchemaCheck.SAFETY_PREFIX}.enabled=false · seat.hold.counter-drift.reconcile.detect-shape=one-shot · " +
                "spring.flyway.locations=classpath:db/migration(새 DB — V9·V10을 이미 적용한 DB면 method=trigger · 위치 기본값 그대로)"
        /**
         * 측정 전용 위치(V4 unique · V8 counter-trigger) 거부 안내 — 이 두 검사는 DB 상태로만 판정해 마이그레이션 뒤에 돈다(명세 §10 · 사용자 결정 2026-10-23 '범위 밖으로 기록').
         * 위치를 빠뜨린 기동은 기본 위치의 V9 트리거·V10을 이미 적용했을 수 있다 — 그 DB에는 V4·V8을 나중에 더할 수 없다(버전이 낮다 · ADR-012 §7-13 ⑦)
         */
        private const val LOCATIONS_LIST_HINT =
            " · spring.flyway.locations는 목록 전체를 덮어쓴다 — 기본 위치를 포함한 전체 목록을 명시하라(빠뜨린 채 띄웠으면 V9·V10이 이미 적용됐을 수 있다 — 새 DB로 다시 띄워라)"
        const val HOLD_TRIGGER = "seat_hold_quota"
        const val RESERVATION_TRIGGER = "reservation_quota"
    }
}
