package com.jun.labs.seatreservation.service.hold

import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 같은 좌석에 서로 다른 사용자 [n]명이 동시에 선점한다. 결과 = 요청별 "ok" 또는 에러 코드 이름(그 밖의 예외는 "ERR:…"). */
fun race(strategy: HoldStrategy, scheduleId: Long, seatId: Long, n: Int = 50): List<String> {
    val pool = Executors.newFixedThreadPool(n)
    val ready = CountDownLatch(n)
    val start = CountDownLatch(1)
    try {
        val futures = (0 until n).map { i ->
            pool.submit<String> {
                ready.countDown()
                start.await()
                try {
                    strategy.hold(HoldSeatCommand(scheduleId, seatId, userId = 1_000L + i))
                    "ok"
                } catch (e: SeatReservationException) {
                    e.errorCode.name
                } catch (e: Throwable) {
                    "ERR:${e::class.simpleName}:${e.message?.take(120)}"
                }
            }
        }
        ready.await()
        start.countDown()
        return futures.map { it.get(60, TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
    }
}
