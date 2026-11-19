package com.jun.labs.seatreservation.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ADR-018 묶음 홀드(다석 선점) 손잡이(`seat.hold-group.*` — 설계-7). 기본 끔 — 켜야 묶음 API(선점 · 해제 · 결제 시작 · 확정)가 열린다.
 * 켜면 기동 검사가 확정 모드 conditional-extend · 보상 모드 none · refund만 허용한다(HoldGroupStartupCheck). 1석 해제 API는 이 키와 무관하게 열린다.
 */
@ConfigurationProperties("seat.hold-group")
data class HoldGroupProperties(
    val enabled: Boolean = false,
    /** 다석 선점 방식(설계-2 4단계). 기본 split — 기본으로 무엇을 쓸지는 측정 뒤에 정한다 */
    val mode: HoldGroupMode = HoldGroupMode.SPLIT,
)

enum class HoldGroupMode {
    /** 분리형: 좌석 행 id 오름차순 `FOR UPDATE NOWAIT` → 묶음 행 · 홀드 N행 INSERT → 좌석 상태 UPDATE를 따로 보낸다 */
    SPLIT,
    /** 단일 쿼리: 좌석 상태 UPDATE와 홀드 INSERT를 CTE 한 문장으로(`SKIP LOCKED` · 바뀐 행 ≠ N이면 롤백) */
    ONE_SHOT,
}
