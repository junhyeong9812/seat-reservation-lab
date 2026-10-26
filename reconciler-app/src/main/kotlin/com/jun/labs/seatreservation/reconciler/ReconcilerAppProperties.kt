package com.jun.labs.seatreservation.reconciler

import com.jun.labs.seatreservation.service.impl.drift.ReconcileProperties
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 외부 앱 설정 묶음 — 업무 앱 `SeatHoldProperties`와 **같은 접두어·같은 키**(ADR-012 §4.2.1)라 configprops의 `prefix == "seat.hold"` ·
 * `.counterDrift.reconcile.*` 경로가 업무 앱과 같다(하네스 incremental-config-mismatch가 두 앱을 같은 jq/파이썬 경로로 읽는다).
 * 업무 앱에만 있는 `expiryRepair`·`cleanup` 등은 없다.
 */
@ConfigurationProperties("seat.hold")
data class ReconcilerAppProperties(
    val counterDrift: CounterDrift = CounterDrift(),
) {
    data class CounterDrift(
        val reconcile: ReconcileProperties = ReconcileProperties(),
    )
}
