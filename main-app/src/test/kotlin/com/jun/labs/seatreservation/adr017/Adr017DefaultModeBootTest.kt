package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.adr015.MODE_KEY
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.service.CompensationMode
import com.jun.labs.seatreservation.service.CompensationProperties
import com.jun.labs.seatreservation.service.PaymentProperties
import com.jun.labs.seatreservation.service.impl.drift.RECONCILER_PROFILE
import com.jun.labs.seatreservation.service.impl.payment.CompensationScheduling
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * ADR-017 §결정(2026-11-18 — 기본 refund) 실제 기동 계약. 명세 docs/plans/2026-11-18/adr-017-default-switch/requirement-spec.md(N1 · N2 · N6).
 * 테스트 클래스패스의 'test' 프로필(config/application.properties → application-test.yml)은 보상 모드를 none으로 덮는다 —
 * 여기서는 spring.config.location으로 설정 위치를 main의 application.yml 하나로 바꿔 그 프로필을 빼고, 운영과 같은 기본값으로 띄운다.
 *   ① 보상 키 · PG 주소 없음 → 기동 거부 · 메시지에 `seat.compensation.mode=none` 안내 · Flyway 앞이라 스키마 무변경(N6)
 *   ② PG 주소만 → 기동 · 바인딩된 모드 REFUND(N1 세 번째 면 — 코드 · yml은 Adr017DefaultModeContractTest)
 *   ③ 확정 모드 current + PG 주소(보상 키 없음) → 거부 · none 안내 · 스키마 무변경(current는 결제 시작이 없어 refund와 함께 뜨지 않는다)
 *   ④ reconciler 프로필 + PG 주소 없음 → 뜬다(N5 — 보상 빈이 없는 프로필이라 Flyway 앞 보상 검사도 건너뛴다)
 */
class Adr017DefaultModeBootTest : BootTestBase() {

    /** 'test' 프로필을 빼고 main application.yml만 읽는다 */
    private val prodConfig = "spring.config.location" to "classpath:/application.yml"
    private val pg = PG_BASE_URL_KEY to "http://127.0.0.1:1"
    private val sched = "seat.compensation.scheduler-enabled" to "false"

    private fun schemaUntouched(url: String, why: String) {
        val jdbc = jdbcFor(url)
        assertNull(jdbc.queryForObject("SELECT to_regclass('public.flyway_schema_history')::text", String::class.java), "$why: Flyway 이력 표가 없어야 한다(migrate 앞 거부)")
        assertNull(jdbc.queryForObject("SELECT to_regclass('public.payment_intent')::text", String::class.java), "$why: V15 표가 없어야 한다")
    }

    @Test
    fun `① 보상 키 · PG 주소 없음 — Flyway 앞에서 거부 · none 안내 · 스키마 무변경`() {
        val url = freshDbUrl()
        val err = bootFailure(url, mapOf(prodConfig, sched))
        assertStartupFailure(err, "기본 refund + PG 없음", listOf("seat.compensation.mode=none"))
        schemaUntouched(url, "기본 refund + PG 없음")
    }

    @Test
    fun `② PG 주소만 — test 프로필 없이 기본 모드 REFUND로 뜬다`() {
        boot(freshDbUrl(), mapOf(prodConfig, pg, sched)).use { ctx ->
            assertFalse("test" in ctx.environment.activeProfiles, "test 프로필이 빠져야 운영 기본값을 본다: ${ctx.environment.activeProfiles.toList()}")
            assertEquals(CompensationMode.REFUND, ctx.getBean(CompensationProperties::class.java).mode, "보상 키 없이 띄운 앱의 모드")
        }
    }

    @Test
    fun `③ 확정 current + PG 주소 · 보상 키 없음 — 거부 · none 안내 · 스키마 무변경`() {
        val url = freshDbUrl()
        val err = bootFailure(url, mapOf(prodConfig, pg, sched, MODE_KEY to "current"))
        assertStartupFailure(err, "current + 기본 refund", listOf("seat.compensation.mode=none"))
        schemaUntouched(url, "current + 기본 refund")
    }

    @Test
    fun `④ reconciler 프로필 · PG 주소 없음 — 보상 검사를 건너뛰고 뜬다`() {
        // 위치를 classpath 루트로 바꾼다 — config/application.properties(test include)는 빠지고 application-reconciler.yml은 읽힌다
        val props = mapOf(
            "spring.config.location" to "classpath:/",
            "spring.profiles.active" to "reconciler",
            "seat.hold.counter-drift.reconcile.enabled" to null, // reconciler 프로필은 대조가 켜져 있어야 뜬다 — 옛 동작 고정 키를 빼 yml 값으로
            "seat.hold.counter-drift.reconcile.interval" to "1h",
            sched,
        )
        boot(freshDbUrl(), props).use { ctx ->
            assertFalse("test" in ctx.environment.activeProfiles, "test 프로필이 빠져야 한다: ${ctx.environment.activeProfiles.toList()}")
            assertTrue(RECONCILER_PROFILE in ctx.environment.activeProfiles, "reconciler 프로필: ${ctx.environment.activeProfiles.toList()}")
            assertFalse(ctx.getBean(PaymentProperties::class.java).pgEnabled, "전제: PG 주소 없음(검사를 했다면 거부됐을 구성)")
            assertEquals(CompensationMode.REFUND, ctx.getBean(CompensationProperties::class.java).mode, "모드는 기본 refund 그대로(검사만 건너뜀)")
            assertTrue(ctx.getBeansOfType(CompensationScheduling::class.java).isEmpty(), "reconciler 프로필에는 보상 스케줄러 빈이 없다")
        }
    }
}
