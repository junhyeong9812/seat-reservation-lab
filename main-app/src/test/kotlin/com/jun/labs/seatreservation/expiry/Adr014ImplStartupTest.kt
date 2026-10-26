package com.jun.labs.seatreservation.expiry

import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.HoldStrategyType
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategyStartupCheck
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-014 §설계-6 기동 검사(N7) — 설정만 보는 검사(checkConfig — 마이그레이션 앞)와 실제 기동(거부된 기동은 스키마를 바꾸지 않는다) ·
 * V11 좌석 맵 인덱스는 선택 위치(db/migration-seat-map)에만 있다(DS14-OQ1).
 */
class Adr014ImplStartupTest : BootTestBase() {

    private val base = SeatHoldProperties(ttl = Duration.ofMinutes(5), maxPerUser = 2, expiryInterval = Duration.ofSeconds(10))

    private fun check(p: SeatHoldProperties) = HoldStrategyStartupCheck.checkConfig(p, null)

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
    fun `checkConfig — 청크 크기는 1 이상`() {
        assertFailsWith<IllegalStateException> { check(base.copy(expiryBatchSize = 0)) }
    }

    @Test
    fun `기동 — 기본 조합 밖 lazy는 마이그레이션 앞에서 거부되고 스키마를 바꾸지 않는다(N7)`() {
        val url = freshDbUrl()
        val error = bootFailure(url, mapOf("seat.hold.expiry-mode" to "lazy", "seat.hold.strategy" to "pessimistic", "seat.hold.limit-strategy" to "none"))
        assertStartupFailure(error, "기본 조합 밖 lazy", listOf("seat.hold.expiry-mode"))
        assertEquals(false, jdbcFor(url).queryForObject("SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean::class.java))
    }

    @Test
    fun `기동 — derived + loadtest 프로필 + inject-app은 거부한다(Q13)`() {
        val url = freshDbUrl()
        val error = bootFailure(
            url,
            mapOf("seat.hold.expiry-mode" to "derived", "spring.profiles.active" to "loadtest", "seat.hold.loadtest.inject-app.enabled" to "true"),
        )
        assertStartupFailure(error, "derived + inject-app", listOf("inject-app"))
        assertEquals(false, jdbcFor(url).queryForObject("SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean::class.java))
    }

    @Test
    fun `V11 — 선택 위치를 더해야만 좌석 맵 인덱스가 생긴다 · 기본 위치 목록에는 없다(DS14-OQ1)`() {
        val withV11 = freshDbUrl()
        boot(withV11, mapOf("seat.hold.expiry-mode" to "derived", "spring.flyway.locations" to "classpath:db/migration,classpath:db/migration-seat-map")).close()
        assertEquals(1, jdbcFor(withV11).queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_product_seat_schedule_id'", Int::class.java))
        assertEquals("11", jdbcFor(withV11).queryForObject("SELECT max(version::int)::text FROM flyway_schema_history", String::class.java))

        val defaults = freshDbUrl()
        boot(defaults, OLD_DEFAULTS_OFF).close() // 업무 앱 기본값(application.yml 위치 목록)
        assertEquals(0, jdbcFor(defaults).queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_product_seat_schedule_id'", Int::class.java))
    }

    private companion object {
        /** BootTestBase 옛 동작 고정 키를 모두 빼 업무 앱 기본값으로 띄운다 */
        val OLD_DEFAULTS_OFF = com.jun.labs.seatreservation.incremental.OLD_DEFAULTS.mapValues { null }
    }
}
