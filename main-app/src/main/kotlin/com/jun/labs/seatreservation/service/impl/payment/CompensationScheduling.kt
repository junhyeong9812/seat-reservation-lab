package com.jun.labs.seatreservation.service.impl.payment

import com.jun.labs.seatreservation.service.CompensationMode
import com.jun.labs.seatreservation.service.CompensationProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.UserLimitStrategyType
import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.PaymentProperties
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * ADR-017 보상 워커 · 안전망 스케줄러(설계-4.4 · 4.5 — CounterDriftScheduling과 같은 '켜질 때만 전용 스케줄러' 패턴).
 * - 기동 검사([check] — 컨텍스트가 뜨기 전): 모드 ≠ none ⇒ PG 주소 필수 · 확정 모드는 payment-start가 있는 ① · ④(intent를 만드는 모드).
 * - 안전망: 늘 돈다(none이면 탐지 지표만 — Q1). 워커: 모드 ≠ none일 때만(PG 호출은 그때만 — N17-12).
 * - `scheduler-enabled=false`면 둘 다 시작하지 않는다(테스트는 runOnce를 직접 부른다). 실패는 로그로 남기고 다음 주기에 다시 한다.
 * - 지표 `seat.compensation.oldest_pending_seconds`(게이지 — 가장 오래 기다린 PENDING의 나이 · 정체 탐지 · RD17-5).
 */
@Component
@Profile("!reconciler")
class CompensationScheduling(
    private val props: CompensationProperties,
    private val payment: PaymentProperties,
    private val confirm: SeatConfirmProperties,
    private val hold: SeatHoldProperties,
    private val worker: CompensationWorker,
    private val sweep: CompensationSweep,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    meters: MeterRegistry,
) : DisposableBean {
    private val schedulers = mutableListOf<ThreadPoolTaskScheduler>()

    init {
        Gauge.builder(OLDEST_PENDING_METRIC) { oldestPendingSeconds() }.register(meters)
    }

    @PostConstruct
    fun check() = checkConfig(props, payment, confirm, hold)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!props.schedulerEnabled) return
        schedule("compensationSweep-", props.sweep.interval) { sweep.runOnce() }
        if (props.mode.createsTasks) schedule("compensationWorker-", props.worker.interval) { worker.runOnce() }
        log.info("보상 스케줄러 시작: mode={} sweep={} worker={}", props.mode, props.sweep.interval, if (props.mode.createsTasks) props.worker.interval else "off")
    }

    private fun schedule(prefix: String, interval: Duration, task: () -> Unit) {
        val s = ThreadPoolTaskScheduler().apply { poolSize = 1; setThreadNamePrefix(prefix); initialize() }
        s.scheduleWithFixedDelay({
            try { task() } catch (e: Exception) { log.error("{} 주기 실패 — 다음 주기에 다시 한다", prefix, e) }
        }, clock.instant().plus(interval), interval)
        schedulers += s
    }

    private fun oldestPendingSeconds(): Double = runCatching {
        jdbc.queryForObject("SELECT min(created_at) FROM compensation_task WHERE status IN ('PENDING', 'IN_PROGRESS')", java.sql.Timestamp::class.java)
            ?.let { Duration.between(it.toInstant(), clock.instant()).seconds.toDouble() } ?: 0.0
    }.getOrDefault(0.0)

    override fun destroy() = schedulers.forEach { it.shutdown() }

    companion object {
        private val log = LoggerFactory.getLogger(CompensationScheduling::class.java)
        const val OLDEST_PENDING_METRIC = "seat.compensation.oldest_pending_seconds"

        fun checkConfig(props: CompensationProperties, payment: PaymentProperties, confirm: SeatConfirmProperties, hold: SeatHoldProperties) {
            val w = props.worker
            check(w.batch > 0 && w.maxAttempts > 0 && !w.lease.isNegative && !w.lease.isZero && w.interval > java.time.Duration.ZERO && !w.backoffInitial.isNegative && w.backoffMax > java.time.Duration.ZERO && w.backoffMax >= w.backoffInitial) {
                "seat.compensation.worker 값 범위: batch > 0 · max-attempts > 0 · lease > 0 · interval > 0 · 0 ≤ backoff-initial ≤ backoff-max · backoff-max > 0 (ADR-017 설계-4.4)"
            }
            check(props.sweep.batch > 0 && props.sweep.interval > java.time.Duration.ZERO) { "seat.compensation.sweep 값 범위: batch > 0 · interval > 0 (ADR-017 설계-4.5)" }
            if (!props.mode.createsTasks) return
            check(payment.pgEnabled) {
                "seat.compensation.mode=${props.mode.name.lowercase().replace('_', '-')}는 seat.payment.pg.base-url이 있어야 한다(보상은 PG를 부른다 · ADR-017 설계-5)"
            }
            // 워커는 리스 안에 못 끝낼 작업을 부르지 않는다 — 작업 하나의 PG 시간 상한보다 리스가 짧으면 아무것도 처리하지 못한다
            val pgBudget = payment.pg.connectTimeout.plus(payment.pg.readTimeout).multipliedBy(2)
            check(w.lease > pgBudget) {
                "seat.compensation.worker.lease(${w.lease})는 PG 시간 상한 2 × (connect-timeout + read-timeout) = $pgBudget 보다 길어야 한다(ADR-017 설계-4.4)"
            }
            check(confirm.mode.paymentStartEnabled) {
                "seat.compensation.mode=${props.mode.name.lowercase().replace('_', '-')}는 결제 시작이 있는 확정 모드(conditional · conditional-extend)여야 한다 — 지금 ${confirm.mode} " +
                    "(intent는 payment-start가 만든다 · ADR-017 설계-4.2)"
            }
            // S는 카운터를 앱 문장으로 맞춘다(홀드 → 대체 예약 옮김 · 없으면 upsert) — 카운터를 그렇게 다루는 매수 방식만(설계-5 · N17-9)
            // S는 판매 마감이면 대체하지 않는다(N17-9) — 마감 판정(회차 FOR SHARE)을 끈 구성에서는 그 판정을 할 수 없다
            check(props.mode != CompensationMode.SUBSTITUTE || hold.salesClose.enabled) {
                "seat.compensation.mode=substitute는 seat.hold.sales-close.enabled=true여야 한다(판매 마감 뒤 대체 금지 — ADR-017 N17-9)"
            }
            check(props.mode != CompensationMode.SUBSTITUTE || hold.limitStrategy in SUBSTITUTE_LIMITS) {
                "seat.compensation.mode=substitute는 seat.hold.limit-strategy가 ${SUBSTITUTE_LIMITS.joinToString(" · ") { it.name.lowercase().replace('_', '-') }} 이어야 한다 — 지금 ${hold.limitStrategy}"
            }
        }

        val SUBSTITUTE_LIMITS = setOf(UserLimitStrategyType.COUNTER_UPSERT, UserLimitStrategyType.COUNTER_UPSERT_PRECHECK)
    }
}
