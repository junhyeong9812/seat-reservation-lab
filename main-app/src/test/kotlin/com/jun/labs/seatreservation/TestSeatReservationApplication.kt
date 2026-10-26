package com.jun.labs.seatreservation

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/** `./gradlew bootTestRun` — Testcontainers PostgreSQL을 띄워 앱을 로컬 실행한다. */
fun main(args: Array<String>) {
    fromApplication<SeatReservationApplication>().with(TestcontainersConfiguration::class).run(*args)
}
