package com.jun.labs.seatreservation.service.impl

import io.micrometer.core.instrument.MeterRegistry
import java.sql.SQLException

/**
 * ADR-018 2단계 교착 관측 `seat.db.deadlock{path}`(모든 프로필) — 예외 원인 사슬 전체에서 SQLSTATE 40P01인 SQLException이 하나라도 있으면 1 올린다
 * (첫 SQLSTATE만 보는 sqlStateOf와 다르다 — 바깥이 다른 SQLSTATE여도 안쪽 40P01을 센다).
 * 관측만 한다: 예외를 삼키거나 바꾸지 않는다(호출자가 그대로 올린다).
 * path = 묶음 API(group_hold · group_pay_start · group_confirm · release — [com.jun.labs.seatreservation.api.HoldGroupMetrics]) ·
 * 묶음 확정 거부 기록 실패(group_confirm — HoldGroupConfirmService) · sweep 만료 틱(expiry_tick — ExpireHoldsService).
 */
object DbDeadlock {
    const val METRIC = "seat.db.deadlock"
    const val SQLSTATE = "40P01"
    const val PATH_EXPIRY_TICK = "expiry_tick"
    const val PATH_GROUP_CONFIRM = "group_confirm"

    fun record(meters: MeterRegistry, path: String, e: Throwable) {
        if (isDeadlock(e)) meters.counter(METRIC, "path", path).increment()
    }

    fun isDeadlock(e: Throwable): Boolean {
        val seen = mutableSetOf<Throwable>() // 순환 사슬 방지
        return generateSequence(e) { it.cause }.takeWhile { seen.add(it) }
            .any { it is SQLException && it.sqlState == SQLSTATE }
    }
}
