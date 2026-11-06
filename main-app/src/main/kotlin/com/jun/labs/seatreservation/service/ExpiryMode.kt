package com.jun.labs.seatreservation.service

/**
 * 홀드 만료와 '좌석이 비었다'의 원천(ADR-014). 설정 `seat.hold.expiry-mode`로 기동 시 하나를 고른다 — 키를 주지 않으면 sweep(지금 코드 그대로).
 * B·C·D는 기본 조합(좌석 pessimistic-nowait + 매수 counter-upsert-precheck)에서만 쓰고(기동 검사), 만료 판정·기록은 DB `now()` 하나다(명세 §2 · §10 R1).
 * 방식 사이 전환은 모두 '멈추고 바꾸기'만 허용한다 — 저장 `HELD`의 뜻이 방식마다 달라 한 DB에서 섞이면 판정·좌석 맵이 틀린다(§설계-7 · DS14-2).
 */
enum class ExpiryMode {
    /** A — `HELD` 저장 + 주기 배치(기준선 · 앱 Clock). 비어 있음 = `status = AVAILABLE` */
    SWEEP,
    /** B — `HELD` 저장(힌트) + 선점·확정·조회 때 판정 · 주기 배치 없음(회수는 선점에서만 · 마감 회차는 정리 ①) */
    LAZY,
    /** C — B + 주기 배치(홀드 행 · 카운터 · 좌석 상태) */
    LAZY_SWEEP,
    /** D — 좌석에는 `RESERVED`만 쓰고 홀드는 행에서 유도한다 · 배치는 홀드 행 · 카운터만 */
    DERIVED,
    ;

    /** 저장 `HELD`를 힌트로 쓰는가(B·C — 선점 H10이 쓴다). D는 `HELD`를 쓰지 않는다 */
    val storesHeld: Boolean get() = this == LAZY || this == LAZY_SWEEP
}
