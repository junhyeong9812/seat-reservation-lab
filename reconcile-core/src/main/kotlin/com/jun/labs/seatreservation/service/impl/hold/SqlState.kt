package com.jun.labs.seatreservation.service.impl.hold

import java.sql.SQLException

/** 예외 원인 사슬에서 첫 SQLState. main-app(좌석·매수 전략)과 core(복구 skipped 사유)가 함께 쓴다 — 그래서 core에 두고 `public`이다(ADR-012 §4.2.1). */
fun sqlStateOf(e: Throwable): String? =
    generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState }
