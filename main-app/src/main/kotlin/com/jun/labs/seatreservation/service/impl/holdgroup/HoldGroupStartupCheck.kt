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
 * 설정 검사([checkConfig])는 Flyway migrate 앞(HoldStrategyMigrationGate)에서 먼저 돌고, Flyway가 꺼진 구성을 위해 [check]가 한 번 더 한다.
 * 마이그레이션 뒤([run]): 켰는데 hold_group 표(V16)가 없으면 거부 · 껐는데 남은 묶음 홀드가 있으면 WARN(1석 API 거부 검사는 켰을 때만 돈다 — HoldGroupGuard).
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
        val table = jdbc.queryForObject("SELECT to_regclass('hold_group') IS NOT NULL", Boolean::class.java) == true
        check(!properties.enabled || table) {
            "$ENABLED_KEY=true는 hold_group 표(V16 — 기본 위치)가 있어야 한다 — DB에 없다(spring.flyway.target이 16 미만?) · ADR-018 설계-1"
        }
        if (properties.enabled || !table) return
        val left = jdbc.queryForObject("SELECT count(*) FROM seat_hold WHERE group_id IS NOT NULL", Long::class.java)!!
        if (left > 0) {
            log.warn("$ENABLED_KEY=false인데 묶음 홀드 {}행이 남아 있다 — 1석 API의 HOLD_IN_GROUP 거부가 꺼져 있다(만료 배치가 지울 때까지)", left)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HoldGroupStartupCheck::class.java)
        const val ENABLED_KEY = "seat.hold-group.enabled"
        private val COMPENSATION_MODES = setOf(CompensationMode.NONE, CompensationMode.REFUND)

        fun checkConfig(properties: HoldGroupProperties, confirm: SeatConfirmProperties, compensation: CompensationProperties) {
            if (!properties.enabled) return
            check(confirm.mode == ConfirmMode.CONDITIONAL_EXTEND) {
                "$ENABLED_KEY=true는 seat.confirm.mode=conditional-extend에서만 쓴다 — 현재 ${confirm.mode.name.lowercase().replace('_', '-')} " +
                    "(묶음 결제 시작 · 확정은 ④ 연장 + 조건부 확정 위에 선다 · ADR-018 설계-7)"
            }
            check(compensation.mode in COMPENSATION_MODES) {
                "$ENABLED_KEY=true는 seat.compensation.mode=none · refund에서만 쓴다 — 현재 ${compensation.mode.name.lowercase().replace('_', '-')} " +
                    "(묶음 + auth-capture · substitute는 지원하지 않는다 · ADR-018 설계-6 · 7)"
            }
        }
    }
}
