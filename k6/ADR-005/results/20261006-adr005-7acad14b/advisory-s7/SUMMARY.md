# ADR-005 결과 요약 — `advisory-s7` · 매수 방식 advisory · 좌석 전략 pessimistic-nowait · 풀 10 · 앱 1대 · 하네스 sha:7acad14b

> `scripts/summarize.py`가 원시 결과에서 산출 · 측정 SHA `7acad14b` · 계획 1단계 × 2셀 × 5회.
> 값 = 중앙값 [최소–최대], n = 정상 회차 수. 정상이 아닌 회차(실패·미측정)는 마지막 열에 모두 표시한다.

## S7 이긴 쪽 롤백 — 좌석마다 2매 보유자 U 1명 + 일반 M명. 억울한 좌석 = 끝 상태 AVAILABLE인데 일반 사용자가 409 SEAT_NOT_AVAILABLE을 받은 좌석

| 단계 | 셀 | n | 대상 좌석 수 | 억울한 좌석 수 | 억울한 409 수 | 빈 좌석 수(끝 상태) | 일반 201 수 | 201 2건+ 좌석 수 | U 201 수(매수 위반) | U 끝 상태 매수>2 | U 코드 분포 | 일반 409 SEAT_NOT_AVAILABLE 수 | 일반 409 HOLD_LIMIT_EXCEEDED 수 | 일반−U 보낸 시각 ms p50 [min–max] | 201 − 홀드 행 | p99 ms | prepare 평균 ms | 매수 acquire 건수 | acquire 평균 ms | check 평균 ms | span 평균 ms(acquire~check) | span 최대 ms(최근 2분 창·예열 섞임 가능) | 40001 수 | 재시도 수 | 비정상 회차 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L4 | S7 | 5 | 100 [100–100] | 0 [0–1] | 0 [0–20] | 0 [0–1] | 100 [99–100] | 0 [0–0] | 0 [0–0] | 0 [0–0] | {"SEAT_NOT_AVAILABLE": 82, "HOLD_LIMIT_EXCEEDED": 18}; {"SEAT_NOT_AVAILABLE": 77, "HOLD_LIMIT_EXCEEDED": 23}; {"SEAT_NOT_AVAILABLE": 77, "HOLD_LIMIT_EXCEEDED": 23}; {"SEAT_NOT_AVAILABLE": 78, "HOLD_LIMIT_EXCEEDED": 22}; {"HOLD_LIMIT_EXCEEDED": 26, "SEAT_NOT_AVAILABLE": 74} | 1,900 [1,900–1,901] | 0 [0–0] | 1 [-1–2]; 1 [-5–2]; 1 [-2–3]; 1 [-4–2]; 1 [-3–6] | 0 [0–0] | 52.42 [25.86–86.63] | 0.0008 [0.0008–0.0009] | 2,300 [2,300–2,300] | 0.4168 [0.3983–0.8125] | 1.1619 [0.9701–1.3940] | 1.3174 [1.1425–1.3851] | 96.555 [67.002–103.371] | 0 [0–0] | 0 [0–0] | - |
| L4 | S7-m1 | 5 | 100 [100–100] | 67 [65–72] | 67 [65–72] | 67 [65–72] | 33 [28–35] | 0 [0–0] | 0 [0–0] | 0 [0–0] | {"HOLD_LIMIT_EXCEEDED": 74, "SEAT_NOT_AVAILABLE": 26}; {"HOLD_LIMIT_EXCEEDED": 74, "SEAT_NOT_AVAILABLE": 26}; {"HOLD_LIMIT_EXCEEDED": 74, "SEAT_NOT_AVAILABLE": 26}; {"HOLD_LIMIT_EXCEEDED": 69, "SEAT_NOT_AVAILABLE": 31}; {"HOLD_LIMIT_EXCEEDED": 68, "SEAT_NOT_AVAILABLE": 32} | 67 [65–72] | 0 [0–0] | 1 [0–2]; 1 [-1–2]; 1 [0–2]; 0 [-1–2]; 1 [0–3] | 0 [0–0] | 17.73 [17.40–23.34] | 0.0010 [0.0008–0.0010] | 400 [400–400] | 0.2794 [0.2648–0.3158] | 1.0347 [0.9342–1.1005] | 1.4716 [1.4012–1.5307] | 58.709 [33.102–100.413] | 0 [0–0] | 0 [0–0] | - |
