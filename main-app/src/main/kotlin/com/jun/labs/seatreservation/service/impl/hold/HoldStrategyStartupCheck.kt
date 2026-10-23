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
import com.jun.labs.seatreservation.service.impl.drift.IncrementalMethod
import com.jun.labs.seatreservation.service.impl.drift.IncrementalSchemaCheck
import com.jun.labs.seatreservation.service.impl.drift.RECONCILER_PROFILE
import org.flywaydb.core.api.CoreErrorCode
import org.flywaydb.core.api.MigrationVersion
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 거부될 기동은 마이그레이션 **앞**에서 거부한다 — 불변식: 기동이 거부되면 DB 스키마는 바뀌지 않는다(ADR-012 기본값 교체 코드 리뷰 RC-8).
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
    fun holdStrategyFlywayMigrationStrategy(properties: SeatHoldProperties, environment: Environment) = FlywayMigrationStrategy { flyway ->
        HoldStrategyStartupCheck.checkConfig(properties, environment)
        val config = flyway.configuration
        val target = config.target
        // target 앞 버전에서 멈추면 그 위치는 객체를 더하지 않는다(LATEST·null = 끝까지 · current·next 등 버전 없는 특수값은 보수적으로 '더하지 않음')
        fun reaches(version: String) = target == null || target == MigrationVersion.LATEST || (target.version != null && target.isAtLeast(version))
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
) : ApplicationRunner {

    /** 설정 검사([checkConfig] — Flyway 앞에서 이미 돌았어도 다시: Flyway가 꺼진 구성·직접 생성 테스트에서 빠지지 않게) → DB 상태가 필요한 검사 */
    override fun run(args: ApplicationArguments) {
        checkConfig(properties, environment)
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
        /**
         * 설정(바인딩된 속성 · 활성 프로필)만으로 판정되는 기동 검사 — DB를 보지 않는다. Flyway 마이그레이션 앞([HoldStrategyMigrationGate])과
         * [run] 첫머리에서 부른다(같은 검사 · 결정적이라 두 번 불러도 결과가 같다).
         * @param environment 프로필·만료 스케줄러 키 확인용 — null이면 프로필 없음·스케줄러 켬으로 본다(직접 생성 테스트)
         */
        fun checkConfig(properties: SeatHoldProperties, environment: Environment?) {
            val strategy = properties.strategy
            check(properties.limitStrategy == UserLimitStrategyType.NONE || strategy == HoldStrategyType.PESSIMISTIC_NOWAIT) {
                "매수 방식 ${properties.limitStrategy}는 좌석 전략 PESSIMISTIC_NOWAIT에서만 쓴다(현재 $strategy) — 락 순서·격리 수준 설정이 이 조합을 전제한다"
            }
            checkCounterDriftConfig(properties, environment)
        }

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
