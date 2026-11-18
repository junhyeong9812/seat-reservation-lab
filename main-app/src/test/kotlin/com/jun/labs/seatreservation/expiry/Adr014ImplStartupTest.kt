package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.CONFIRM_CURRENT
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategyStartupCheck
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-014 §설계-6 기동 검사(N7) — 설정만 보는 검사(checkConfig — 마이그레이션 앞)와 실제 기동(거부된 기동은 스키마를 바꾸지 않는다) ·
 * V11 좌석 맵 인덱스는 선택 위치(db/migration-seat-map)에만 있다(DS14-OQ1).
 */
class Adr014ImplStartupTest : BootTestBase() {

    private val base = SeatHoldProperties(ttl = Duration.ofMinutes(5), maxPerUser = 2, expiryInterval = Duration.ofSeconds(10))

    /** ADR-015 §결정(나): B·C·D · 기본 밖 조합은 current 명시 — 이 클래스는 ADR-014 검사 문구를 본다(확정 규칙이 먼저 거부하지 않게) */
    private val current = SeatConfirmProperties(mode = ConfirmMode.CURRENT)

    private fun check(p: SeatHoldProperties) = HoldStrategyStartupCheck.checkConfig(p, null, current)

    @Test
    fun `checkConfig — 기본 조합이면 네 방식 모두 통과한다`() {
        ExpiryMode.entries.forEach { check(base.copy(expiryMode = it)) }
    }

    @Test
    fun `checkConfig — 기본 조합이 아닌데 sweep이 아니면 거부한다(바꿀 키 안내)`() {
        val cases = listOf(
            base.copy(strategy = HoldStrategyType.PESSIMISTIC, limitStrategy = UserLimitStrategyType.NONE),
            base.copy(strategy = HoldStrategyType.CONDITIONAL_UPDATE, limitStrategy = UserLimitStrategyType.NONE),
            base.copy(limitStrategy = UserLimitStrategyType.COUNTER_UPSERT),
            base.copy(limitStrategy = UserLimitStrategyType.COUNTER_TRIGGER),
            base.copy(limitStrategy = UserLimitStrategyType.NONE),
        )
        listOf(ExpiryMode.LAZY, ExpiryMode.LAZY_SWEEP, ExpiryMode.DERIVED).forEach { mode ->
            cases.forEach { p ->
                val e = assertFailsWith<IllegalStateException>("$mode ${p.strategy} ${p.limitStrategy}") { check(p.copy(expiryMode = mode)) }
                assertTrue(e.message!!.contains("seat.hold.expiry-mode"), e.message)
                assertTrue(e.message!!.contains("counter-upsert-precheck"), e.message)
            }
        }
    }

    @Test
    fun `checkConfig — sweep이 아닌데 inject-app이 켜지면 거부한다(Q13) · sweep은 지금 검사 그대로`() {
        val inject = base.loadtest.copy(injectApp = base.loadtest.injectApp.copy(enabled = true))
        listOf(ExpiryMode.LAZY, ExpiryMode.LAZY_SWEEP, ExpiryMode.DERIVED).forEach { mode ->
            val e = assertFailsWith<IllegalStateException> { check(base.copy(expiryMode = mode, loadtest = inject)) }
            assertTrue(e.message!!.contains("inject-app"), e.message)
            assertTrue(e.message!!.contains("expiry-mode"), e.message)
        }
        // sweep + inject-app은 지금처럼 프로필 검사가 본다(프로필 없음 → 거부) — 새 검사 문구가 아니다
        val e = assertFailsWith<IllegalStateException> { check(base.copy(loadtest = inject)) }
        assertTrue(!e.message!!.contains("expiry-mode"), e.message)
    }

    @Test
    fun `C·D + 만료 스케줄러 끔(reconciler 프로필 아님) → 경고 문구(기동은 허용) · A·B · 스케줄러 켬 · reconciler 프로필은 경고 없음(CR14-17)`() {
        fun env(scheduler: String?, vararg profiles: String) = MockEnvironment().apply {
            scheduler?.let { setProperty("seat.hold.expiry-scheduler-enabled", it) }
            setActiveProfiles(*profiles)
        }
        listOf(ExpiryMode.LAZY_SWEEP, ExpiryMode.DERIVED).forEach { mode ->
            val p = base.copy(expiryMode = mode)
            val w = HoldStrategyStartupCheck.expirySchedulerOffWarning(p, env("false"))
            assertTrue(w != null && w.contains("seat.hold.expiry-scheduler-enabled") && w.contains("$mode"), "$mode: $w")
            check(p) // 기동 검사는 거부하지 않는다
            assertNull(HoldStrategyStartupCheck.expirySchedulerOffWarning(p, env("true")), "$mode 스케줄러 켬")
            assertNull(HoldStrategyStartupCheck.expirySchedulerOffWarning(p, env(null)), "$mode 키 없음 = 켬(matchIfMissing)")
            assertNull(HoldStrategyStartupCheck.expirySchedulerOffWarning(p, env("false", "reconciler")), "$mode reconciler 프로필")
            assertNull(HoldStrategyStartupCheck.expirySchedulerOffWarning(p, null), "$mode 환경 없음(직접 생성) = 켬")
        }
        listOf(ExpiryMode.SWEEP, ExpiryMode.LAZY).forEach { mode ->
            assertNull(HoldStrategyStartupCheck.expirySchedulerOffWarning(base.copy(expiryMode = mode), env("false")), "$mode")
        }
    }

    @Test
    fun `checkConfig — 청크 크기는 1 이상`() {
        assertFailsWith<IllegalStateException> { check(base.copy(expiryBatchSize = 0)) }
    }

    @Test
    fun `checkConfig — 틱당 청크 상한은 1 이상(CR14-2)`() {
        listOf(0, -1).forEach { n ->
            val e = assertFailsWith<IllegalStateException>("상한 $n") { check(base.copy(expiryMaxChunksPerTick = n)) }
            assertTrue(e.message!!.contains("seat.hold.expiry-max-chunks-per-tick"), e.message)
        }
        check(base.copy(expiryMaxChunksPerTick = 1))
        assertEquals(100, base.expiryMaxChunksPerTick, "코드 기본값")
    }

    @Test
    fun `checkConfig — lazy는 정리 ①(cleanup schedule)이 없으면 거부한다(바꿀 키 안내) · 다른 방식은 정리와 무관(CR14-1)`() {
        listOf(SeatHoldProperties.CleanupMode.NONE, SeatHoldProperties.CleanupMode.ZERO).forEach { cleanupMode ->
            val p = base.copy(counterDrift = base.counterDrift.copy(cleanup = SeatHoldProperties.Cleanup(mode = cleanupMode)))
            val e = assertFailsWith<IllegalStateException>("lazy + $cleanupMode") { check(p.copy(expiryMode = ExpiryMode.LAZY)) }
            assertTrue(e.message!!.contains("seat.hold.counter-drift.cleanup.mode=schedule"), e.message)
            assertTrue(e.message!!.contains("seat.hold.expiry-mode"), e.message)
            // 정리 ②(zero)는 카운터 계열 조합에서 지금 검사로 통과한다 — 거부는 lazy 때문이다
            listOf(ExpiryMode.SWEEP, ExpiryMode.LAZY_SWEEP, ExpiryMode.DERIVED).forEach { check(p.copy(expiryMode = it)) }
        }
        check(base.copy(expiryMode = ExpiryMode.LAZY)) // 코드 기본값 = schedule
    }

    @Test
    fun `기동 — lazy + 정리 끔은 마이그레이션 앞에서 거부되고 스키마를 바꾸지 않는다 · 정리 ①을 켜면 뜬다(CR14-1)`() {
        val url = freshDbUrl()
        val error = bootFailure(url, CONFIRM_CURRENT + mapOf("seat.hold.expiry-mode" to "lazy")) // 옛 동작 고정 키 = cleanup.mode=none
        assertStartupFailure(error, "lazy + cleanup none", listOf("seat.hold.counter-drift.cleanup.mode=schedule로"))
        assertEquals(false, jdbcFor(url).queryForObject("SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean::class.java))
        boot(freshDbUrl(), CONFIRM_CURRENT + mapOf("seat.hold.expiry-mode" to "lazy", "seat.hold.counter-drift.cleanup.mode" to "schedule")).close()
    }

    @Test
    fun `checkConfig — lazy + 정리 끔은 reconciler 프로필이면 통과 · 업무 프로필(프로필 없음·loadtest)이면 거부(CR14-26)`() {
        val p = base.copy(expiryMode = ExpiryMode.LAZY, counterDrift = base.counterDrift.copy(cleanup = SeatHoldProperties.Cleanup(mode = SeatHoldProperties.CleanupMode.NONE)))
        fun env(vararg profiles: String) = MockEnvironment().apply { setActiveProfiles(*profiles) }
        // reconciler 프로필은 만료 스케줄러를 꺼야 하고(F 검사) 대조를 켜야 한다(L1-13) — 그 두 검사가 대신 거부하지 않게 둘 다 맞춘다
        val f = p.copy(counterDrift = p.counterDrift.copy(reconcile = p.counterDrift.reconcile.copy(enabled = true)))
        HoldStrategyStartupCheck.checkConfig(f, env("reconciler").apply { setProperty("seat.hold.expiry-scheduler-enabled", "false") }, current)
        listOf(env(), null).forEach { e ->
            val error = assertFailsWith<IllegalStateException>("업무 프로필 $e") { HoldStrategyStartupCheck.checkConfig(p, e, current) }
            assertTrue(error.message!!.contains("seat.hold.counter-drift.cleanup.mode=schedule"), error.message)
        }
    }

    @Test
    fun `기동 — reconciler 프로필 + lazy + 정리 끔(F 기본값)은 뜬다 · 같은 키의 업무 앱은 거부된다(CR14-26)`() {
        // 업무 앱 기본값으로 띄운다(옛 동작 고정 키를 뺀다) — F의 정리·대조·증분·안전망은 application-reconciler.yml이 정한다(cleanup none · C 켬)
        boot(freshDbUrl(), OLD_DEFAULTS_OFF + CONFIRM_CURRENT + mapOf("spring.profiles.active" to "reconciler", "seat.hold.expiry-mode" to "lazy")).use { ctx ->
            val props = ctx.getBean(SeatHoldProperties::class.java)
            assertEquals(SeatHoldProperties.CleanupMode.NONE, props.counterDrift.cleanup.mode, "전제: F는 정리 끔")
            assertEquals(ExpiryMode.LAZY, props.expiryMode, "전제: lazy")
        }
        val url = freshDbUrl()
        val error = bootFailure(url, OLD_DEFAULTS_OFF + CONFIRM_CURRENT + mapOf("seat.hold.expiry-mode" to "lazy", "seat.hold.counter-drift.cleanup.mode" to "none"))
        assertStartupFailure(error, "업무 앱 + lazy + cleanup none", listOf("seat.hold.counter-drift.cleanup.mode=schedule로"))
        assertEquals(false, jdbcFor(url).queryForObject("SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean::class.java))
    }

    @Test
    fun `기동 — 기본 조합 밖 lazy는 마이그레이션 앞에서 거부되고 스키마를 바꾸지 않는다(N7)`() {
        val url = freshDbUrl()
        val error = bootFailure(url, CONFIRM_CURRENT + mapOf("seat.hold.expiry-mode" to "lazy", "seat.hold.strategy" to "pessimistic", "seat.hold.limit-strategy" to "none"))
        assertStartupFailure(error, "기본 조합 밖 lazy", listOf("seat.hold.expiry-mode"))
        assertEquals(false, jdbcFor(url).queryForObject("SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean::class.java))
    }

    @Test
    fun `기동 — derived + loadtest 프로필 + inject-app은 거부한다(Q13)`() {
        val url = freshDbUrl()
        val error = bootFailure(
            url,
            CONFIRM_CURRENT + mapOf("seat.hold.expiry-mode" to "derived", "spring.profiles.active" to "loadtest", "seat.hold.loadtest.inject-app.enabled" to "true"),
        )
        assertStartupFailure(error, "derived + inject-app", listOf("inject-app"))
        assertEquals(false, jdbcFor(url).queryForObject("SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean::class.java))
    }

    @Test
    fun `V11 — 선택 위치를 더해야만 좌석 맵 인덱스가 생긴다 · 기본 위치 목록에는 없다(DS14-OQ1)`() {
        val withV11 = freshDbUrl()
        boot(withV11, CONFIRM_CURRENT + mapOf("seat.hold.expiry-mode" to "derived", "spring.flyway.locations" to "classpath:db/migration,classpath:db/migration-seat-map")).close()
        assertEquals(1, jdbcFor(withV11).queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_product_seat_schedule_id'", Int::class.java))
        // ADR-015: 기본 위치에 V12 · V13이 더해지고 ADR-017이 V15를 더해 최대 버전은 15 — V11 적용은 이력 행으로 본다
        assertEquals("15", jdbcFor(withV11).queryForObject("SELECT max(version::int)::text FROM flyway_schema_history", String::class.java))
        assertEquals(1, jdbcFor(withV11).queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success", Int::class.java))

        val defaults = freshDbUrl()
        boot(defaults, OLD_DEFAULTS_OFF).close() // 업무 앱 기본값(application.yml 위치 목록)
        assertEquals(0, jdbcFor(defaults).queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_product_seat_schedule_id'", Int::class.java))
    }

    private companion object {
        /** BootTestBase 옛 동작 고정 키를 모두 빼 업무 앱 기본값으로 띄운다 */
        val OLD_DEFAULTS_OFF = com.jun.labs.seatreservation.incremental.OLD_DEFAULTS.mapValues { null }
    }
}
