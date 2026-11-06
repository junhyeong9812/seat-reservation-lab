package com.jun.labs.seatreservation.service.impl.expiry

/**
 * ADR-014 B·C·D의 만료 판정 단일 규칙(§설계-2.1 · N1) — 활성 = `expires_at > now()`(DB `now()` = 그 트랜잭션의 시작 시각) · 만료 = `expires_at <= now()`.
 * 선점·확정·좌석 맵·배치·회수가 이 조각을 쓴다. 앱 Clock으로 만료를 판정·기록하지 않는다(명세 §2 — A는 예외 §10 R1).
 * 비어 있음(B·C·D 공통) = `status <> 'RESERVED'` 이고 활성 홀드 없음 — 저장 `HELD`·`AVAILABLE`을 판정에 쓰지 않는다.
 */
internal object ExpiryRule {
    /** 좌석 `st`에 활성 홀드가 있는가(별칭 `st` = product_seat) */
    const val ACTIVE_ON_SEAT = "EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now())"

    /** H1 · H8 — 좌석 상태와 활성 홀드 유무(잠금 없음 · H8은 좌석을 잠근 **뒤의 새 문장**이라 잠금을 쥐었던 트랜잭션의 커밋이 보인다 — §2.3) */
    const val SEAT_STATE_SQL = "SELECT st.status, $ACTIVE_ON_SEAT AS active FROM product_seat st WHERE st.id = ? AND st.schedule_id = ?"

    const val RESERVED = "RESERVED"

    fun isFree(status: String, active: Boolean): Boolean = status != RESERVED && !active
}
