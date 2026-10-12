package com.jun.labs.seatreservation.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * 테스트에서 현재 시각을 고정·이동하기 위한 Clock. 다른 스레드(동시성 테스트의 정리·선점 스레드)가 옮긴 시각이 바로 보이도록
 * 상태를 AtomicReference 하나로 둔다(ADR-009 재점검 — 스레드 간 가시성).
 */
class MutableClock(now: Instant) : Clock() {

    /** 지금 시각 + (있으면) 다음 읽기 뒤에 바뀔 시각 — 둘을 함께 바꿔 읽기·이동이 서로 끼어들어도 어긋나지 않게 */
    private data class State(val now: Instant, val afterNextRead: Instant? = null)

    private val state = AtomicReference(State(now))

    fun set(instant: Instant) {
        state.set(State(instant))
    }

    fun advance(duration: Duration) {
        state.updateAndGet { State(it.now.plus(duration)) }
    }

    /** 다음 한 번의 읽기는 [first], 그 뒤 읽기는 [then] — 두 Clock 읽기 사이에 시계가 경계를 넘는 경우를 만든다(ADR-009 코드 루프 3 L3-1). */
    fun setForOneRead(first: Instant, then: Instant) {
        state.set(State(first, then))
    }

    override fun instant(): Instant =
        state.getAndUpdate { s -> s.afterNextRead?.let { State(it) } ?: s }.now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
