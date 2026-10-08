# ADR-005 결과 요약 — `smoke3-s7-serializable` · 매수 방식 serializable · 좌석 전략 pessimistic-nowait · 풀 10 · 앱 1대 · 하네스 worktree:45c038b5

> `scripts/summarize.py`가 원시 결과에서 산출 · 측정 SHA `4d02bdfa` · 계획 1단계 × 1셀 × 1회.
> 값 = 중앙값 [최소–최대], n = 정상 회차 수. 정상이 아닌 회차(실패·미측정)는 마지막 열에 모두 표시한다.

## S7 이긴 쪽 롤백 — 좌석마다 2매 보유자 U 1명 + 일반 M명. 억울한 좌석 = 끝 상태 AVAILABLE인데 일반 사용자가 409 SEAT_NOT_AVAILABLE을 받은 좌석

| 단계 | 셀 | n | 대상 좌석 수 | 억울한 좌석 수 | 억울한 409 수 | 빈 좌석 수(끝 상태) | 일반 201 수 | 201 2건+ 좌석 수 | U 201 수(매수 위반) | U 끝 상태 매수>2 | U 코드 분포 | 일반 409 SEAT_NOT_AVAILABLE 수 | 일반 409 HOLD_LIMIT_EXCEEDED 수 | 일반−U 보낸 시각 ms p50 [min–max] | 201 − 홀드 행 | p99 ms | prepare 평균 ms | 매수 acquire 건수 | acquire 평균 ms | check 평균 ms | span 평균 ms(acquire~check) | span 최대 ms(최근 2분 창·예열 섞임 가능) | 40001 수 | 재시도 수 | 비정상 회차 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L4 | S7 | 1 | 100 | 1 | 20 | 1 | 99 | 0 | 0 | 0 | {"SEAT_NOT_AVAILABLE": 76, "HOLD_LIMIT_EXCEEDED": 24} | 1,897 | 4 | 1 [-2–2] | 0 | 71.63 | 0.0009 | 2,465 | 0.5617 | 0.9068 | 1.2569 | 104.324 | 169 | 0 | - |
