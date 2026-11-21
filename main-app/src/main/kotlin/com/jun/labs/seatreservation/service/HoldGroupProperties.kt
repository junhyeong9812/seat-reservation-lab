package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ADR-018 묶음 홀드(다석 선점) 설정(`seat.hold-group.*` — 설계-7). 기본 켬(ADR-018 §결정 2026-11-21 — 묶음 API 기본 켬 · split) — 묶음 API(선점 · 해제 · 결제 시작 · 확정)가 열린다.
 * 켜면 기동 검사가 확정 모드 conditional-extend · 보상 모드 none · refund · V16 스키마만 허용한다(HoldGroupStartupCheck — 맞지 않으면 자동으로 끄지 않고 거부).
 * 되돌림 · 맞지 않는 구성(current 등)으로 띄우기: `seat.hold-group.enabled=false`. 1석 해제 API는 이 키와 무관하게 열린다.
 */
@ConfigurationProperties("seat.hold-group")
data class HoldGroupProperties(
    val enabled: Boolean = true,
    /** 다석 선점 방식(설계-2 4단계). 기본 split(ADR-018 §결정 — one-shot은 비교용으로 남긴다) */
    val mode: HoldGroupMode = HoldGroupMode.SPLIT,
)

enum class HoldGroupMode {
    /** 분리형: 좌석 행 id 오름차순 `FOR UPDATE NOWAIT` → 묶음 행 · 홀드 N행 INSERT → 좌석 상태 UPDATE를 따로 보낸다 */
    SPLIT,
    /** 단일 쿼리: 좌석 상태 UPDATE와 홀드 INSERT를 CTE 한 문장으로(`SKIP LOCKED` · 바뀐 행 ≠ N이면 롤백) */
    ONE_SHOT,
}
