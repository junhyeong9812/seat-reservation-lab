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
    private val safety: SafetyNetReconciler,
) : DisposableBean, SafetyNetExpediter {
    private val schedulers = mutableListOf<ThreadPoolTaskScheduler>()
    /** 안전망 스케줄러(표본 · 실행 · 앞당김이 같은 스레드 — 겹쳐 돌지 않는다). 손잡이가 꺼져 있으면 없다 */
    @Volatile private var safetyScheduler: ThreadPoolTaskScheduler? = null
    private val lastExpediteNs = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)

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
        // ADR-012 §4.2.5 — 안전망: 단일 스레드에 ① id–시각 표본(sample-interval) ② 실행(interval + 앞당김)
        val net = drift.reconcile.safetyNet
        if (net.enabled) {
            safety.source = SafetyNetReconciler.SAFETY_APP
            safety.start()
            val scheduler = newScheduler("counterSafetyNetScheduler-")
            fixedDelay(scheduler, "counterSafetyNetSample-", net.sampleInterval) { safety.sampleOnce() }
            fixedDelay(scheduler, "counterSafetyNetScheduler-", net.interval) { safety.runOnce() }
            safetyScheduler = scheduler
            log.info("카운터 안전망 시작: source={} interval={} budget={} recent-window={}", safety.source, net.interval, net.budget, net.recentWindow)
        }
        if (drift.cleanup.mode != CleanupMode.NONE) {
            schedule("quotaCleanupScheduler-", drift.cleanup.interval) { cleanup.runOnce() }
            log.info("쿼터 행 정리 시작: mode={} interval={}", drift.cleanup.mode, drift.cleanup.interval)
        }
    }

    /** 대조 기록의 출처 — reconciler 프로필(F 별도 프로세스)이면 reconcile-proc, 아니면 reconcile-app(C). */
    fun reconcileSource(): String =
        if (RECONCILER_PROFILE in environment.activeProfiles) DriftSource.RECONCILE_PROC else DriftSource.RECONCILE_APP

    /**
     * 넘침(ⓐ) 때 안전망 다음 실행을 바로 1회(§4.2.2 ⓐ ③ · §4.2.5 — 10초에 1번까지). 같은 스레드라 진행 중 실행과 겹치지 않는다.
     * 앞당김은 실행을 당길 뿐 넘친 쌍을 지정해 고치지 않는다.
     */
    override fun expedite() {
        val scheduler = safetyScheduler ?: return
        val now = System.nanoTime()
        val last = lastExpediteNs.get()
        if (last != Long.MIN_VALUE && now - last < EXPEDITE_EVERY_NS) return
        if (!lastExpediteNs.compareAndSet(last, now)) return
        scheduler.execute {
            try {
                safety.runOnce(expedited = true)
            } catch (e: Exception) {
                log.error("안전망 앞당김 실행 실패 — 다음 주기에 다시 한다", e)
            }
        }
    }

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
        private const val EXPEDITE_EVERY_NS = 10_000_000_000L
    }
}
