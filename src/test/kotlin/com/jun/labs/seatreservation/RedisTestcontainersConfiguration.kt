package com.jun.labs.seatreservation

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer

/** ADR-003 Redis 전략 테스트 전용 — 다른 테스트는 Redis 없이 돈다(Redis 전략이 아니면 연결하지 않는다). */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestcontainersConfiguration {

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)
}
