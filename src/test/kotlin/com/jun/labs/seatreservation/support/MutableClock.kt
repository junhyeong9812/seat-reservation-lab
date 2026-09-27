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

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
