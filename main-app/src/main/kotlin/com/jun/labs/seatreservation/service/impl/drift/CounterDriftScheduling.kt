package com.jun.labs.seatreservation.service.impl.drift

import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties.CleanupMode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** F(별도 대조 프로세스) 프로필 이름 — 같은 jar, 대조만 하고 만료 스케줄러를 끈다(application-reconciler.yml). */
const val RECONCILER_PROFILE = "reconciler"

/**
 * 대조(C·F)·정리의 **전용** 스케줄러(설계 §4e) — 기본 스케줄러는 스레드 1개라 대조 바퀴가 만료 틱을 밀어낸다.
 * 기동 검사(ApplicationRunner)가 끝난 뒤(ApplicationReadyEvent)에 시작하고, 첫 실행은 한 주기 뒤다. 실패는 로그로 남기고 다음 주기에 다시 한다.
 */
@Component
class CounterDriftScheduling(
    private val properties: SeatHoldProperties,
    private val reconciler: CounterReconciler,
    private val cleanup: QuotaCleanup,
    private val environment: Environment,
    private val incremental: IncrementalReconciler,
) : DisposableBean {
    private val schedulers = mutableListOf<ThreadPoolTaskScheduler>()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val drift = properties.counterDrift
        if (drift.reconcile.enabled) {
            val source = reconcileSource()
            schedule("counterReconcileScheduler-", drift.reconcile.interval) { reconciler.runOnce(source) }
            log.info("카운터 대조 시작: source={} interval={}", source, drift.reconcile.interval)
        }
        // ADR-012 §4.2.3 — 증분 틱은 incremental.enabled일 때만(새 스케줄러는 손잡이가 켜질 때만 만든다 — §4.2 공통 원칙 ③)
        val inc = drift.reconcile.incremental
        if (inc.enabled) {
            val source = IncrementalReconciler.source(inc, external = false)
            incremental.start(source)
            schedule("counterIncrementalScheduler-", inc.interval) { incremental.tick(source) }
            log.info("카운터 증분 대조 시작: source={} method={} interval={}", source, inc.method, inc.interval)
        }
        if (drift.cleanup.mode != CleanupMode.NONE) {
            schedule("quotaCleanupScheduler-", drift.cleanup.interval) { cleanup.runOnce() }
            log.info("쿼터 행 정리 시작: mode={} interval={}", drift.cleanup.mode, drift.cleanup.interval)
        }
    }

    /** 대조 기록의 출처 — reconciler 프로필(F 별도 프로세스)이면 reconcile-proc, 아니면 reconcile-app(C). */
    fun reconcileSource(): String =
        if (RECONCILER_PROFILE in environment.activeProfiles) DriftSource.RECONCILE_PROC else DriftSource.RECONCILE_APP

    private fun newScheduler(prefix: String) = ThreadPoolTaskScheduler().apply {
        setPoolSize(1)
        setThreadNamePrefix(prefix)
        initialize()
    }.also { schedulers += it }

    private fun schedule(prefix: String, interval: Duration, task: () -> Unit) = fixedDelay(newScheduler(prefix), prefix, interval, task)

    private fun fixedDelay(scheduler: ThreadPoolTaskScheduler, prefix: String, interval: Duration, task: () -> Unit) {
        scheduler.scheduleWithFixedDelay(
            {
                try {
                    task()
                } catch (e: Exception) {
                    log.error("{} 실행 실패 — 다음 주기에 다시 한다", prefix, e)
                }
            },
            Instant.now().plus(interval),
            interval,
        )
    }

    override fun destroy() = schedulers.forEach { it.shutdown() }

    companion object {
        private val log = LoggerFactory.getLogger(CounterDriftScheduling::class.java)
    }
}
