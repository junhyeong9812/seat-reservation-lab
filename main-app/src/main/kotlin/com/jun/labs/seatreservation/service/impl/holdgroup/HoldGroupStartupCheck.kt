package com.jun.labs.seatreservation.service.impl.holdgroup

import com.jun.labs.seatreservation.service.CompensationMode
import com.jun.labs.seatreservation.service.CompensationProperties
import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.HoldGroupProperties
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-018 설계-7 기동 검사. `seat.hold-group.enabled=true`면:
 * - 확정 모드 conditional-extend(그래서 만료 sweep · pessimistic-nowait · counter-upsert-precheck — ADR-016 기동 검사가 이미 강제한다)
 * - 보상 모드 none · refund(묶음 + auth-capture · substitute는 지원하지 않는다)
 * 기본 켬(ADR-018 §결정 2026-11-21)이라 맞지 않는 구성은 자동으로 끄지 않고 거부한다 — 거부 메시지마다 [DISABLE_HINT]를 안내한다.
 * 설정 검사([checkConfig])와 V16 계획 검사([checkPlan] — target이 16 앞이면 거부)는 Flyway migrate 앞(HoldStrategyMigrationGate)에서 먼저 돈다 — 거부된 기동이 스키마를 바꾸지 않게.
 * 이번 migrate가 V16을 적용하지 않으면(이미 이력에 있음 · 위치 밖) 지금 DB의 표가 곧 마이그레이션 뒤 사실이라 그 표 검사([checkTable])도 migrate 앞에서 한다.
 * Flyway가 꺼진 구성을 위해 [check]가 설정 검사를, [run]이 표 검사를 한 번 더 한다. 껐는데 남은 묶음 홀드가 있으면 WARN(1석 API 거부 검사는 켰을 때만 돈다 — HoldGroupGuard).
 * reconciler 프로필은 업무 요청을 받지 않으므로 보지 않는다.
 */
@Component
@Profile("!reconciler")
class HoldGroupStartupCheck(
    private val properties: HoldGroupProperties,
    private val confirm: SeatConfirmProperties,
    private val compensation: CompensationProperties,
    private val jdbc: JdbcTemplate,
) : ApplicationRunner {

    @PostConstruct
    fun check() = checkConfig(properties, confirm, compensation)

    override fun run(args: ApplicationArguments) {
        val table = tableExists(jdbc)
        checkTable(properties, table, "DB")
        if (properties.enabled || !table) return
        val left = jdbc.queryForObject("SELECT count(*) FROM seat_hold WHERE group_id IS NOT NULL", Long::class.java)!!
        if (left > 0) {
            log.warn("$ENABLED_KEY=false인데 묶음 홀드 {}행이 남아 있다 — 1석 API의 HOLD_IN_GROUP 거부가 꺼져 있다(만료 배치가 지울 때까지)", left)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HoldGroupStartupCheck::class.java)
        const val ENABLED_KEY = "seat.hold-group.enabled"
        const val HOLD_GROUP_VERSION = "16"
        /** 기본 켬이라 맞지 않는 구성을 띄우는 길 — 거부 메시지마다 붙인다 */
        const val DISABLE_HINT = "묶음 없이 띄우려면 $ENABLED_KEY=false"
        private val COMPENSATION_MODES = setOf(CompensationMode.NONE, CompensationMode.REFUND)

        fun checkConfig(properties: HoldGroupProperties, confirm: SeatConfirmProperties, compensation: CompensationProperties) {
            if (!properties.enabled) return
            check(confirm.mode == ConfirmMode.CONDITIONAL_EXTEND) {
                "$ENABLED_KEY=true는 seat.confirm.mode=conditional-extend에서만 쓴다 — 현재 ${confirm.mode.name.lowercase().replace('_', '-')} " +
                    "(묶음 결제 시작 · 확정은 ④ 연장 + 조건부 확정 위에 선다 · ADR-018 설계-7) · $DISABLE_HINT" +
                    // 관문은 이 검사를 보상 검사보다 먼저 돈다 — 결제 시작이 없는 확정 모드는 보상 refund 등과도 함께 뜨지 않으니(ADR-017 설계-5) 그 안내도 함께 낸다
                    if (!confirm.mode.paymentStartEnabled && compensation.mode != CompensationMode.NONE) " · 결제 시작이 없는 확정 모드는 seat.compensation.mode=none도 함께 준다" else ""
            }
            check(compensation.mode in COMPENSATION_MODES) {
                "$ENABLED_KEY=true는 seat.compensation.mode=none · refund에서만 쓴다 — 현재 ${compensation.mode.name.lowercase().replace('_', '-')} " +
                    "(묶음 + auth-capture · substitute는 지원하지 않는다 · ADR-018 설계-6 · 7) · $DISABLE_HINT"
            }
        }

        /** 켰는데 이번 Flyway target이 V16에 닿지 않으면 거부(migrate 앞 — 관문 전용) */
        fun checkPlan(properties: HoldGroupProperties, reachesV16: Boolean) {
            check(!properties.enabled || reachesV16) {
                "$ENABLED_KEY=true는 spring.flyway.target이 $HOLD_GROUP_VERSION 이상이어야 한다(hold_group · ADR-018 설계-1) — " +
                    "target을 풀거나, 앞 버전 스키마 재현이면 $DISABLE_HINT"
            }
        }

        /** 켰는데 hold_group 표(V16)가 없으면 거부(관문 · run 공용) */
        fun checkTable(properties: HoldGroupProperties, exists: Boolean, where: String) {
            check(!properties.enabled || exists) {
                "$ENABLED_KEY=true는 hold_group 표(V16 — 기본 위치)가 있어야 한다 — ${where}에 없다(spring.flyway.target이 $HOLD_GROUP_VERSION 미만?) · ADR-018 설계-1 · $DISABLE_HINT"
            }
        }

        fun tableExists(jdbc: JdbcTemplate) = jdbc.queryForObject("SELECT to_regclass('hold_group') IS NOT NULL", Boolean::class.java) == true
    }
}
