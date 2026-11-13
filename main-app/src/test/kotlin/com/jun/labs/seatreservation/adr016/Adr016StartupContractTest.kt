package com.jun.labs.seatreservation.adr016

import com.jun.labs.seatreservation.SeatReservationApplication
import com.jun.labs.seatreservation.adr015.LOC_CONFIRM_VERSION
import com.jun.labs.seatreservation.adr015.MODE_KEY
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.LOC_BASE
import com.jun.labs.seatreservation.incremental.OLD_DEFAULTS
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.incremental.failureOf
import com.jun.labs.seatreservation.incremental.tableExists
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.context.ApplicationListener
import org.springframework.core.Ordered
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import kotlin.test.fail

/*
 * ADR-016 기동 계약(blind — 명세 §1 · 1 · 2 · 6 · N16-6 · §5 '기동 검사'). 기동 = BootTestBase(실제 SpringApplication · 부팅마다 새 DB · OLD_DEFAULTS).
 *  - N16-6: 옛 키 seat.confirm.extend-min-remaining이 Environment 어느 출처에든 있으면 어떤 모드에서도(값과 무관) 기동 거부 · 메시지에 옛 키 이름과 새 키.
 *    두 형태 — 프로퍼티(명령줄 인자 `--seat.confirm.extend-min-remaining=…`) · 환경 변수 `SEAT_CONFIRM_EXTENDMINREMAINING`.
 *    환경 변수는 JVM 안에서 바꿀 수 없어, Boot가 만든 Environment의 'systemEnvironment' 출처(SystemEnvironmentPropertySource — relaxed 매핑 대상)를
 *    System.getenv() + 테스트 변수로 바꿔 끼운다. 바꿔 끼우는 시점 = ApplicationEnvironmentPreparedEvent 최우선 리스너(EnvironmentPostProcessor들보다 앞 —
 *    구현이 어느 단계에서 옛 키를 찾든 보인다). 장치 대조: 같은 길로 SEAT_CONFIRM_MODE=잘못된 값을 주면 바인딩이 실패해야 한다(환경 변수가 실제로 읽힌다).
 *  - 기동 검사: θ ≥ 0 · M ≥ 0(모드 무관) · M ≤ θ(① · ④) · ④ 규칙 4는 새 키(window > 0 · 0 ≤ θ < window).
 *    설정 규칙 거부는 ADR-015 N7 관례대로 마이그레이션 앞(새 DB에 flyway_schema_history 없음)으로 본다.
 */
class Adr016StartupContractTest : BootTestBase() {

    private val window = WINDOW_KEY to "2m"
    private val locOpt = "$LOC_BASE,$LOC_CONFIRM_VERSION"

    /** 모드별 '허용' 기본 키(ADR-015 기동 계약과 같은 조합) — ④는 window 2분 + 새 키 θ · ①은 명시(기본은 ADR-016 §결정으로 ④) */
    private val modes: Map<String, Map<String, String?>> = mapOf(
        "conditional" to mapOf(MODE_KEY to "conditional"),
        "current" to mapOf(MODE_KEY to "current"),
        "pessimistic" to mapOf(MODE_KEY to "pessimistic"),
        "optimistic" to mapOf(MODE_KEY to "optimistic", "spring.flyway.locations" to locOpt),
        "conditional-extend" to mapOf(MODE_KEY to "conditional-extend", window, THETA_KEY to "5s"),
    )

    private fun check(failures: List<String>) {
        if (failures.isNotEmpty()) fail("${failures.size}건:\n" + failures.joinToString("\n"))
    }

    private fun noSchema(url: String) = !jdbcFor(url).tableExists("flyway_schema_history")

    private fun bootsOrRecord(why: String, props: Map<String, String?>, failures: MutableList<String>) {
        val err = bootFailure(freshDbUrl(), props)
        if (err != null) failures += "$why: 떠야 하는데 실패 ${causesOf(err).map { it.message?.take(200) }}"
    }

    /** 설정 규칙 거부 — 기동 검사(IllegalStateException · 메시지에 [kw] 중 하나) + 마이그레이션 앞 */
    private fun rejectFresh(why: String, props: Map<String, String?>, kw: List<String>, failures: MutableList<String>) {
        val url = freshDbUrl()
        runCatching { assertStartupFailure(bootFailure(url, props), why, kw) }
            .onSuccess { if (!noSchema(url)) failures += "$why: 거부된 기동이 스키마를 바꿨다(flyway_schema_history 생김 — 거부가 마이그레이션 뒤)" }
            .onFailure { failures += "$why: ${it.message?.take(400)}" }
    }

    /** 옛 키 거부 — 떴으면 실패 · 원인 사슬 어딘가의 메시지에 옛 키(점 · 환경 변수 표기 어느 쪽이든)와 새 키 이름 */
    private fun oldKeyRejected(why: String, err: Throwable?, failures: MutableList<String>) {
        if (err == null) { failures += "$why: 떴다 — 옛 키가 조용히 무시됐다(θ 무음 후퇴 · N16-6)"; return }
        val msgs = causesOf(err).map { it.message.orEmpty() }
        val ok = msgs.any { m ->
            m.contains(THETA_KEY) && (m.contains("extend-min-remaining", ignoreCase = true) || m.contains("EXTENDMINREMAINING", ignoreCase = true))
        }
        if (!ok) failures += "$why: 기동은 실패했지만 메시지에 옛 키 · 새 키($THETA_KEY) 이름이 없다: ${causesOf(err).map { "${it::class.simpleName}: ${it.message?.take(200)}" }}"
    }

    // ── 환경 변수 주입 기동 ────────────────────────────────────────────────────────────────────

    private class SystemEnvInjector(private val extra: Map<String, String>) : ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
        override fun getOrder() = Ordered.HIGHEST_PRECEDENCE
        override fun onApplicationEvent(event: ApplicationEnvironmentPreparedEvent) {
            val name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
            val merged = HashMap<String, Any>(System.getenv()).apply { putAll(extra) }
            event.environment.propertySources.replace(name, SystemEnvironmentPropertySource(name, merged))
        }
    }

    /** [BootTestBase.boot]과 같은 기본 키(명령줄 인자) + 환경 변수 [env] */
    private fun bootFailureWithEnv(url: String, props: Map<String, String?>, env: Map<String, String>): Throwable? = failureOf {
        val base = mapOf<String, String?>(
            "spring.datasource.url" to url,
            "spring.datasource.username" to dbUser,
            "spring.datasource.password" to dbPassword,
            "server.port" to "0",
            "seat.hold.expiry-scheduler-enabled" to "false",
        ) + OLD_DEFAULTS
        val args = (base + props).filterValues { it != null }.map { (k, v) -> "--$k=$v" }
        SpringApplicationBuilder(SeatReservationApplication::class.java)
            .listeners(SystemEnvInjector(env))
            .run(*args.toTypedArray())
            .close()
    }

    // ── N16-6 옛 키 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `N16-6 — 옛 키(프로퍼티 형태)가 있으면 모든 모드에서 기동 거부 · 값과 무관 · 새 키를 함께 줘도 거부`() {
        val failures = mutableListOf<String>()
        modes.forEach { (name, p) ->
            oldKeyRejected("$name + $OLD_THETA_KEY=5s", bootFailure(freshDbUrl(), p + (OLD_THETA_KEY to "5s")), failures)
        }
        oldKeyRejected("current + $OLD_THETA_KEY=0s(값 0)", bootFailure(freshDbUrl(), mapOf(MODE_KEY to "current", OLD_THETA_KEY to "0s")), failures)
        oldKeyRejected("conditional + 옛 키 0s + 새 키 0s", bootFailure(freshDbUrl(), mapOf(MODE_KEY to "conditional", OLD_THETA_KEY to "0s", THETA_KEY to "0s", MARGIN_KEY to "0s")), failures)
        oldKeyRejected(
            "conditional-extend + window + 옛 키만(새 키 없음)",
            bootFailure(freshDbUrl(), mapOf(MODE_KEY to "conditional-extend", window, OLD_THETA_KEY to "5s")), failures,
        )
        check(failures)
    }

    @Test
    fun `N16-6 — 옛 키(환경 변수 SEAT_CONFIRM_EXTENDMINREMAINING)가 있으면 기동 거부 · 장치 대조(환경 변수 바인딩이 실제로 읽힌다)`() {
        val failures = mutableListOf<String>()
        // 장치 대조 — 같은 주입 길의 환경 변수가 바인딩에 닿는다(닿지 않으면 아래 거부 판정은 뜻이 없다)
        val probe = bootFailureWithEnv(freshDbUrl(), emptyMap(), mapOf("SEAT_CONFIRM_MODE" to "conditionall"))
        if (probe == null || causesOf(probe).none { it is BindException }) {
            failures += "장치 대조 실패 — 주입한 환경 변수 SEAT_CONFIRM_MODE=conditionall이 바인딩에 닿지 않았다: ${probe?.let { causesOf(it).map { e -> e::class.simpleName } }}"
        }
        bootsOrRecordEnv("장치 대조 — 새 키 환경 변수(SEAT_CONFIRM_PAYMENTMINREMAINING=2s)는 뜬다(기본 M 1초 ≤ θ)", mapOf("SEAT_CONFIRM_PAYMENTMINREMAINING" to "2s"), failures)

        oldKeyRejected("conditional-extend(기본) + env $OLD_THETA_ENV=5s", bootFailureWithEnv(freshDbUrl(), emptyMap(), mapOf(OLD_THETA_ENV to "5s")), failures)
        oldKeyRejected("current + env $OLD_THETA_ENV=0s", bootFailureWithEnv(freshDbUrl(), mapOf(MODE_KEY to "current"), mapOf(OLD_THETA_ENV to "0s")), failures)
        oldKeyRejected(
            "conditional-extend + env $OLD_THETA_ENV=5s",
            bootFailureWithEnv(freshDbUrl(), modes.getValue("conditional-extend"), mapOf(OLD_THETA_ENV to "5s")), failures,
        )
        check(failures)
    }

    private fun bootsOrRecordEnv(why: String, env: Map<String, String>, failures: MutableList<String>) {
        val err = bootFailureWithEnv(freshDbUrl(), emptyMap(), env)
        if (err != null) failures += "$why: 떠야 하는데 실패 ${causesOf(err).map { it.message?.take(200) }}"
    }

    // ── 기동 검사 — 음수 · M ≤ θ ─────────────────────────────────────────────────────────────

    @Test
    fun `기동 검사 — θ 음수 · M 음수는 모드 무관 거부`() {
        val failures = mutableListOf<String>()
        listOf("conditional", "current", "conditional-extend").forEach { name ->
            val p = modes.getValue(name)
            rejectFresh("$name + θ=-1s", p + (THETA_KEY to "-1s"), listOf(THETA_KEY), failures)
            rejectFresh("$name + M=-1s", p + (MARGIN_KEY to "-1s"), listOf(MARGIN_KEY), failures)
        }
        check(failures)
    }

    @Test
    fun `기동 검사 — M 초과 θ는 ① · ④에서 거부(기본 θ 1초에 M만 줘도 거부)`() {
        val failures = mutableListOf<String>()
        val kw = listOf(MARGIN_KEY, THETA_KEY)
        rejectFresh("① θ=5s · M=6s", mapOf(MODE_KEY to "conditional", THETA_KEY to "5s", MARGIN_KEY to "6s"), kw, failures)
        rejectFresh("① θ 기본 1초 · M=2s", mapOf(MODE_KEY to "conditional", MARGIN_KEY to "2s"), kw, failures)
        rejectFresh("④ 기본(키 없음) · M=2s", mapOf(MARGIN_KEY to "2s"), kw, failures)
        rejectFresh("① 명시 conditional · θ=5s · M=5001ms", mapOf(MODE_KEY to "conditional", THETA_KEY to "5s", MARGIN_KEY to "5001ms"), kw, failures)
        rejectFresh("④ window 2m · θ=5s · M=6s", modes.getValue("conditional-extend") + (MARGIN_KEY to "6s"), kw, failures)
        check(failures)
    }

    @Test
    fun `기동 검사 — M = θ는 통과(① · ④ · 0 = 0 포함)`() {
        val failures = mutableListOf<String>()
        bootsOrRecord("① θ=5s · M=5s", mapOf(MODE_KEY to "conditional", THETA_KEY to "5s", MARGIN_KEY to "5s"), failures)
        bootsOrRecord("① θ=0s · M=0s(명시)", mapOf(MODE_KEY to "conditional", THETA_KEY to "0s", MARGIN_KEY to "0s"), failures)
        bootsOrRecord("① M < θ(θ=30s · M=10s)", mapOf(MODE_KEY to "conditional", THETA_KEY to "30s", MARGIN_KEY to "10s"), failures)
        bootsOrRecord("④ window 2m · θ=5s · M=5s", modes.getValue("conditional-extend") + (MARGIN_KEY to "5s"), failures)
        check(failures)
    }

    /**
     * 명세 §1 · 6은 `M ≤ θ`를 '(① · ④)'로 한정한다 — current는 payBy를 내지 않으므로(N16-7) M > θ여도 뜬다는 추론 칸(명세 직접 문장은 아님 · 보고에 명시).
     * 음수 검사(모드 무관)와 달리 이 칸이 실패하면 구현이 '모드 무관'으로 넓힌 것 — 명세와 다르니 판정 대상.
     */
    @Test
    fun `기동 검사(추론) — current는 M 초과 θ여도 뜬다(M ≤ θ는 ① · ④ 한정)`() {
        val failures = mutableListOf<String>()
        bootsOrRecord("current · θ=0 · M=10s", mapOf(MODE_KEY to "current", MARGIN_KEY to "10s"), failures)
        check(failures)
    }

    // ── ④ 규칙 4(새 키) ───────────────────────────────────────────────────────────────────────

    @Test
    fun `규칙 4(새 키) — conditional-extend는 window 0 초과 · 0 ≤ payment-min-remaining 미만 window`() {
        val failures = mutableListOf<String>()
        // 기본 M 1초(ADR-016 §결정)는 θ 0에서 M ≤ θ(설계-9.3)로 먼저 거부된다 — 규칙 4만 보도록 M 0을 고정한다
        val base = mapOf<String, String?>(MODE_KEY to "conditional-extend", MARGIN_KEY to "0s")
        val kw = listOf(WINDOW_KEY, THETA_KEY)
        rejectFresh("window=0s · θ=0s", base + mapOf(WINDOW_KEY to "0s", THETA_KEY to "0s"), kw, failures)
        rejectFresh("θ = window", base + mapOf(WINDOW_KEY to "2m", THETA_KEY to "2m"), kw, failures)
        rejectFresh("θ > window", base + mapOf(WINDOW_KEY to "2m", THETA_KEY to "3m"), kw, failures)
        rejectFresh("θ < 0", base + mapOf(WINDOW_KEY to "2m", THETA_KEY to "-1s"), kw, failures)
        bootsOrRecord("대조 window 키 없음(기본 611초 — ADR-016 §결정)", base, failures)
        bootsOrRecord("대조 θ 키 없음(기본 1초) · window=2m", base + mapOf(WINDOW_KEY to "2m"), failures)
        bootsOrRecord("대조 θ=0s · window=2m", base + mapOf(WINDOW_KEY to "2m", THETA_KEY to "0s"), failures)
        bootsOrRecord("대조 θ=119s · window=2m", base + mapOf(WINDOW_KEY to "2m", THETA_KEY to "119s"), failures)
        check(failures)
    }
}
