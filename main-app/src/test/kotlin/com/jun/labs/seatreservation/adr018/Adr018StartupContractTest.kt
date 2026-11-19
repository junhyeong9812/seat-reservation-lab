package com.jun.labs.seatreservation.adr018

import com.jun.labs.seatreservation.adr015.MODE_KEY
import com.jun.labs.seatreservation.adr016.THETA_KEY
import com.jun.labs.seatreservation.adr016.WINDOW_KEY
import com.jun.labs.seatreservation.adr017.COMPENSATION_MODE_KEY
import com.jun.labs.seatreservation.adr017.PG_BASE_URL_KEY
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.Test
import kotlin.test.fail

/*
 * 설계-7 기동 검사(blind): `seat.hold-group.enabled=true`면 확정 모드 conditional-extend + 보상 모드 none · refund만 뜬다.
 * 거부 메시지에 `seat.hold-group.enabled`가 들어간다(test-contract §1). enabled=false면 그 조합들도 지금처럼 뜬다(대조).
 * 기동 = BootTestBase(실제 SpringApplication · 부팅마다 새 DB). PG 주소는 닿지 않는 값(기동은 PG를 부르지 않는다 — Adr017PgStartupContractTest와 같다).
 *
 * 해석: conditional(①)은 θ · M을 application.yml 값(1초 · 1초)으로 둔 채 뜬다(yml 머리말 — mode만 바꾸면 뜬다). current는 보상 none을 함께 준다.
 */
class Adr018StartupContractTest : BootTestBase() {

    private val unreachable = "http://127.0.0.1:1"
    private val sched = "seat.compensation.scheduler-enabled" to "false"
    private val pg = PG_BASE_URL_KEY to unreachable
    private val on = HG_ENABLED_KEY to "true"
    private val extend = mapOf<String, String?>(MODE_KEY to "conditional-extend", WINDOW_KEY to "2m", THETA_KEY to "5s")

    private fun boots(why: String, props: Map<String, String?>, failures: MutableList<String>) {
        val err = bootFailure(freshDbUrl(), props)
        if (err != null) failures += "$why: 떠야 하는데 실패 ${causesOf(err).map { "${it::class.simpleName}: ${it.message?.take(200)}" }}"
    }

    private fun rejects(why: String, props: Map<String, String?>, failures: MutableList<String>) {
        runCatching { assertStartupFailure(bootFailure(freshDbUrl(), props), why, listOf(HG_ENABLED_KEY)) }
            .onFailure { failures += "$why: ${it.message?.take(500)}" }
    }

    private fun check(failures: List<String>) {
        if (failures.isNotEmpty()) fail("${failures.size}건:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `설계-7 — enabled=true + conditional-extend + none · refund(split · one-shot)는 뜬다`() {
        val failures = mutableListOf<String>()
        listOf("split", "one-shot").forEach { m ->
            boots("[$m] none", extend + on + (HG_MODE_KEY to m) + (COMPENSATION_MODE_KEY to "none") + sched, failures)
            boots("[$m] refund", extend + on + (HG_MODE_KEY to m) + (COMPENSATION_MODE_KEY to "refund") + pg + sched, failures)
        }
        check(failures)
    }

    @Test
    fun `설계-7 — enabled=true + 확정 모드가 conditional-extend가 아님 → 기동 거부(메시지에 seat-hold-group-enabled)`() {
        val failures = mutableListOf<String>()
        rejects("conditional + none", mapOf(MODE_KEY to "conditional", on, COMPENSATION_MODE_KEY to "none", sched), failures)
        rejects("current + none", mapOf(MODE_KEY to "current", on, COMPENSATION_MODE_KEY to "none", sched), failures)
        rejects("pessimistic + none", mapOf(MODE_KEY to "pessimistic", on, COMPENSATION_MODE_KEY to "none", sched), failures)
        // 대조: 같은 확정 모드라도 enabled=false면 뜬다
        boots("conditional + none + 꺼짐", mapOf(MODE_KEY to "conditional", COMPENSATION_MODE_KEY to "none", sched), failures)
        check(failures)
    }

    @Test
    fun `설계-7 — enabled=true + 보상 auth-capture · substitute → 기동 거부 · 꺼져 있으면 뜬다`() {
        val failures = mutableListOf<String>()
        listOf("auth-capture", "substitute").forEach { c ->
            rejects("conditional-extend + $c", extend + on + (COMPENSATION_MODE_KEY to c) + pg + sched, failures)
            boots("conditional-extend + $c + 꺼짐", extend + (COMPENSATION_MODE_KEY to c) + pg + sched, failures)
        }
        check(failures)
    }
}
