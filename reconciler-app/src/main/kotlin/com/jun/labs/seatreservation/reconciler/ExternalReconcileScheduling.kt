package com.jun.labs.seatreservation.reconciler

import com.jun.labs.seatreservation.service.impl.drift.IncrementalReconciler
import com.jun.labs.seatreservation.service.impl.drift.ReconcileProperties
import com.jun.labs.seatreservation.service.impl.drift.SafetyNetReconciler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 외부 앱 스케줄러(ADR-012 §4.2.1 `ExternalReconcileScheduling` — 업무 앱 `CounterDriftScheduling`과 같은 단일 스레드 고정 지연).
 * 기동 검사(ApplicationRunner)가 끝난 뒤(ApplicationReadyEvent) 시작하고 첫 실행은 한 주기 뒤다. 실패는 로그로 남기고 다음 주기에 다시 한다.
 * source는 외부 앱 값(§4.2.6) — 증분 `inc-ext-<trg|stmt>-<주기>` · 안전망 `safety-ext`. 앞당김(ⓐ 넘침)은 없다(외부 앱에는 메모리 집합이 없다).
 */
@Component
class ExternalReconcileScheduling(
    private val properties: ReconcileProperties,
    private val incremental: IncrementalReconciler,
    private val safety: SafetyNetReconciler,
) : DisposableBean {
    private val schedulers = mutableListOf<ThreadPoolTaskScheduler>()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        safety.source = SafetyNetReconciler.SAFETY_EXT
        val inc = properties.incremental
        if (inc.enabled) {
            val source = IncrementalReconciler.source(inc, external = true)
            incremental.start(source)
            val scheduler = newScheduler("externalIncrementalScheduler-")
            fixedDelay(scheduler, "externalIncrementalScheduler-", inc.interval) { incremental.tick(source) }
            log.info("외부 증분 대조 시작: source={} method={} interval={}", source, inc.method, inc.interval)
        }
        val net = properties.safetyNet
        if (net.enabled) {
            safety.start()
            // 표본 · 실행이 같은 스레드(겹쳐 돌지 않는다 — §4.2.5 스레드)
            val scheduler = newScheduler("externalSafetyNetScheduler-")
            fixedDelay(scheduler, "externalSafetyNetSample-", net.sampleInterval) { safety.sampleOnce() }
            fixedDelay(scheduler, "externalSafetyNetScheduler-", net.interval) { safety.runOnce() }
            log.info("외부 안전망 시작: source={} interval={} budget={} recent-window={}", safety.source, net.interval, net.budget, net.recentWindow)
        }
    }

    private fun newScheduler(prefix: String) = ThreadPoolTaskScheduler().apply {
        setPoolSize(1)
        setThreadNamePrefix(prefix)
        initialize()
    }.also { schedulers += it }

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
        private val log = LoggerFactory.getLogger(ExternalReconcileScheduling::class.java)
    }
}
