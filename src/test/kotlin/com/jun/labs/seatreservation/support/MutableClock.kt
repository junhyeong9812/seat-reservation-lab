package com.jun.labs.seatreservation.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 테스트에서 현재 시각을 고정·이동하기 위한 Clock. */
class MutableClock(private var now: Instant) : Clock() {

    fun set(instant: Instant) {
        now = instant
    }

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    @Volatile private var afterNextRead: Instant? = null

    /** 다음 한 번의 읽기는 [first], 그 뒤 읽기는 [then] — 두 Clock 읽기 사이에 시계가 경계를 넘는 경우를 만든다(ADR-009 코드 루프 3 L3-1). */
    fun setForOneRead(first: Instant, then: Instant) {
        now = first
        afterNextRead = then
    }

    override fun instant(): Instant {
        val read = now
        afterNextRead?.let { now = it; afterNextRead = null }
        return read
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
