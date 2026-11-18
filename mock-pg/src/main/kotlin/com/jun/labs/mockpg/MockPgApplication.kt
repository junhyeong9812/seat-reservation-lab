package com.jun.labs.mockpg

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Duration

/** ADR-017 설계-3 흉내 PG — 메모리 원장(측정 중 죽이지 않는다 · 끝에서 /ledger로 덤프 · 회차마다 /admin/reset) */
@SpringBootApplication
@ConfigurationPropertiesScan
class MockPgApplication {
    /** 원장 시계 — 같은 JVM에서 띄우는 테스트는 이 빈을 바꿔 앱 시계와 맞춘다 */
    @Bean
    fun mockPgClock(): Clock = Clock.systemUTC()
}

@ConfigurationProperties("mock-pg")
data class MockPgProperties(
    /**
     * 승인 유효 시간 — 기본 30분(명세 §8의 10분에서 바꿈: ④ 연장 W 611초보다 짧으면 결제창에서 받은 승인이 홀드 연장보다 먼저 만료된다.
     * 결제 시작 ~ 연장 만료 최대 T + W ≈ 851초를 넘게 둔다 · 실제 PG는 며칠 단위). 측정 값은 2단계 측정 전 고정에서 다시 정한다.
     */
    val authTtl: Duration = Duration.ofMinutes(30),
)

fun main(args: Array<String>) {
    runApplication<MockPgApplication>(*args)
}
