package com.jun.labs.seatreservation.adr017

import com.jun.labs.seatreservation.adr015.LOC_CONFIRM_VERSION
import com.jun.labs.seatreservation.adr015.MODE_KEY
import com.jun.labs.seatreservation.adr016.THETA_KEY
import com.jun.labs.seatreservation.adr016.WINDOW_KEY
import com.jun.labs.seatreservation.incremental.BootTestBase
import com.jun.labs.seatreservation.incremental.LOC_BASE
import com.jun.labs.seatreservation.incremental.assertStartupFailure
import com.jun.labs.seatreservation.support.causesOf
import org.junit.jupiter.api.Test
import kotlin.test.fail

/*
 * 명세 §1-8 기동 검사(blind): 모드 ≠ none ⇒ `seat.payment.pg.base-url` 필수 · 모드 ≠ none ⇒ 확정 모드 ① conditional 또는 ④ conditional-extend
 * (payment-start가 있는 모드) · 워커 · 안전망 손잡이 값 범위. 기동 = BootTestBase(실제 SpringApplication · 부팅마다 새 DB).
 * base-url은 닿지 않는 주소를 준다(기동은 PG를 부르지 않는다 — 등록은 payment-start 때 · 워커는 스케줄러 끔).
 *
 * 가정(근거 조항):
 *  - 거부는 기동 검사(IllegalStateException)이고 메시지에 관련 키 이름 중 하나가 있다(ADR-015 · 016 기동 검사 관례 — assertStartupFailure).
 *  - 손잡이 '값 범위'(명세 §1-8)는 batch · max-attempts ≥ 1, interval · lease > 0이다(값 0 거부) — 명세가 경계를 적지 않아 가장 분명한 0만 시험한다.
 *  - 모드 none은 확정 모드와 무관하게 뜬다(N17-11 — current 등 외부 계약 그대로).
 */
class Adr017PgStartupContractTest : BootTestBase() {

    private val unreachable = "http://127.0.0.1:1"
    private val sched = "seat.compensation.scheduler-enabled" to "false"
    private val base = PG_BASE_URL_KEY to unreachable
    private val window = WINDOW_KEY to "2m"

    private val conditionalExtend = mapOf<String, String?>(MODE_KEY to "conditional-extend", window, THETA_KEY to "5s")
    private val conditional = mapOf<String, String?>(MODE_KEY to "conditional")

    private fun check(failures: List<String>) {
        if (failures.isNotEmpty()) fail("${failures.size}건:\n" + failures.joinToString("\n"))
    }

    private fun boots(why: String, props: Map<String, String?>, failures: MutableList<String>) {
        val err = bootFailure(freshDbUrl(), props)
        if (err != null) failures += "$why: 떠야 하는데 실패 ${causesOf(err).map { "${it::class.simpleName}: ${it.message?.take(200)}" }}"
    }

    private fun rejects(why: String, props: Map<String, String?>, kw: List<String>, failures: MutableList<String>) {
        runCatching { assertStartupFailure(bootFailure(freshDbUrl(), props), why, kw) }
            .onFailure { failures += "$why: ${it.message?.take(500)}" }
    }

    @Test
    fun `§1-8 — 모드 ≠ none인데 base-url 없음 → 기동 거부 · 있으면 뜬다(①·④)`() {
        val failures = mutableListOf<String>()
        listOf("refund", "auth-capture", "substitute").forEach { m ->
            rejects(
                "$m + ④ + base-url 없음", conditionalExtend + (COMPENSATION_MODE_KEY to m) + sched,
                listOf(PG_BASE_URL_KEY, "base-url"), failures,
            )
        }
        boots("refund + ④ + base-url", conditionalExtend + (COMPENSATION_MODE_KEY to "refund") + base + sched, failures)
        boots("refund + ① + base-url", conditional + (COMPENSATION_MODE_KEY to "refund") + base + sched, failures)
        boots("none(기본) + base-url 없음", conditionalExtend + sched, failures)
        check(failures)
    }

    @Test
    fun `§1-8 — 모드 ≠ none인데 확정 모드가 ①·④가 아님 → 기동 거부 · 모드 none이면 그 확정 모드로도 뜬다(N17-11)`() {
        val failures = mutableListOf<String>()
        val others = mapOf(
            "current" to mapOf<String, String?>(MODE_KEY to "current"),
            "pessimistic" to mapOf<String, String?>(MODE_KEY to "pessimistic"),
            "optimistic" to mapOf<String, String?>(MODE_KEY to "optimistic", "spring.flyway.locations" to "$LOC_BASE,$LOC_CONFIRM_VERSION"),
        )
        others.forEach { (name, p) ->
            rejects(
                "refund + $name", p + (COMPENSATION_MODE_KEY to "refund") + base + sched,
                listOf(COMPENSATION_MODE_KEY, MODE_KEY, "payment-start"), failures,
            )
            boots("none + $name", p + sched, failures)
        }
        check(failures)
    }

    @Test
    fun `§1-8 — 워커 · 안전망 손잡이 값 0 → 기동 거부`() {
        val failures = mutableListOf<String>()
        val ok = conditionalExtend + (COMPENSATION_MODE_KEY to "refund") + base + sched
        listOf(
            "seat.compensation.worker.batch" to "0",
            "seat.compensation.worker.max-attempts" to "0",
            "seat.compensation.worker.lease" to "0s",
            "seat.compensation.worker.interval" to "0s",
            "seat.compensation.sweep.batch" to "0",
            "seat.compensation.sweep.interval" to "0s",
        ).forEach { (k, v) ->
            rejects("$k=$v", ok + (k to v), listOf(k, "seat.compensation"), failures)
        }
        check(failures)
    }
}
