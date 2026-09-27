package com.jun.labs.seatreservation

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
}

fun main(args: Array<String>) {
    runApplication<SeatReservationApplication>(*args)
}
