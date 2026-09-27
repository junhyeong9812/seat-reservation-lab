package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("seat.hold")
data class SeatHoldProperties(
    val ttl: Duration,
    val maxPerUser: Int,
    val expiryInterval: Duration,
)
