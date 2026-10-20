package com.jun.labs.seatreservation

import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.drift.ReconcileProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class SeatReservationApplication {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** reconcile-core 빈이 생성자로 받는 대조 설정 — 키는 `seat.hold.counter-drift.reconcile.*` 그대로(ADR-012 §4.2.1). */
    @Bean
    fun reconcileProperties(p: SeatHoldProperties): ReconcileProperties = p.counterDrift.reconcile
}

fun main(args: Array<String>) {
    runApplication<SeatReservationApplication>(*args)
}
