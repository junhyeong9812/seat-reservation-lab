package com.jun.labs.seatreservation.reconciler

import com.jun.labs.seatreservation.service.impl.drift.ReconcileProperties
import org.springframework.boot.actuate.endpoint.SanitizableData
import org.springframework.boot.actuate.endpoint.SanitizingFunction
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

/**
 * 외부 대조 앱(ADR-012 §4.2.1 'reconciler-app에 필요한 것' · §4.2.8). 증분(ⓑ trigger · ⓒ stmt)과 안전망만 돈다 — 정리·만료·전체 대조(C)는 하지 않는다.
 * 빈 스캔 = core의 탐지·복구(`service.impl.drift`) + 이 패키지. 설정 키·configprops 경로는 업무 앱과 같다(`seat.hold.counter-drift.reconcile.*`).
 */
@SpringBootApplication(scanBasePackages = ["com.jun.labs.seatreservation.service.impl.drift", "com.jun.labs.seatreservation.reconciler"])
@EnableConfigurationProperties(ReconcilerAppProperties::class)
class ReconcilerApplication {

    /** 업무 앱 `SeatReservationApplication.clock`과 같다 — 열린 회차 판정·복구 마감 재확인은 이 프로세스 시계(.158)다(§4.2.8 시계 · §7-12) */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** core 빈이 생성자로 받는 대조 설정(업무 앱의 같은 이름 빈과 같은 모양 — rv41 Opus L7) */
    @Bean
    fun reconcileProperties(p: ReconcilerAppProperties): ReconcileProperties = p.counterDrift.reconcile

    /**
     * configprops는 값을 보여 준다(하네스가 두 앱의 방식을 비교한다 — incremental-config-mismatch). 그래도 비밀번호 같은 키는 가린다 —
     * 이 앱의 DB 비밀번호는 환경 변수로만 들어오고 8103으로 새면 안 된다.
     */
    @Bean
    fun secretMask(): SanitizingFunction = SanitizingFunction { d ->
        if (SECRET_KEY.containsMatchIn(d.key)) d.withValue(SanitizableData.SANITIZED_VALUE) else d
    }

    companion object {
        private val SECRET_KEY = Regex("password|secret|credential|token", RegexOption.IGNORE_CASE)
    }
}

fun main(args: Array<String>) {
    runApplication<ReconcilerApplication>(*args)
}
