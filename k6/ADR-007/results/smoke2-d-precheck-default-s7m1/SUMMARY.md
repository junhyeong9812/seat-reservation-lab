# ADR-007 결과 요약 — `smoke2-d-precheck-default-s7m1` · 매수 방식 counter-upsert-precheck · DB 설정 default · 좌석 전략 pessimistic-nowait · 풀 10 · 앱 1대 · 하네스 sha:1fdec01e

> `scripts/summarize.py`가 원시 결과에서 산출 · 측정 SHA `1fdec01e` · 계획 1단계 × 1셀 × 1회.
> 값 = 중앙값 [최소–최대], n = 정상 회차 수. 정상이 아닌 회차(실패·미측정)는 마지막 열에 모두 표시한다.

## S7 이긴 쪽 롤백 — 좌석마다 2매 보유자 U 1명 + 일반 M명. 억울한 좌석 = 끝 상태 AVAILABLE인데 일반 사용자가 409 SEAT_NOT_AVAILABLE을 받은 좌석

| 단계 | 셀 | n | 대상 좌석 수 | 억울한 좌석 수 | 억울한 409 수 | 빈 좌석 수(끝 상태) | 일반 201 수 | 201 2건+ 좌석 수 | U 201 수(매수 위반) | U 끝 상태 매수>2 | U 코드 분포 | 일반 409 SEAT_NOT_AVAILABLE 수 | 일반 409 HOLD_LIMIT_EXCEEDED 수 | 일반−U 보낸 시각 ms p50 [min–max] | 201 − 홀드 행 | p99 ms | prepare 평균 ms | 매수 acquire 건수 | acquire 평균 ms | check 평균 ms | span 평균 ms(acquire~check) | span 최대 ms(최근 2분 창·예열 섞임 가능) | 40001 수 | 재시도 수 | 요청당 커넥션 빌림 | WAL fsync 수 | WAL fsync 평균 ms | 커밋/WAL fsync | WAL MB | 선점당 WAL B | 체크포인트 수 | 디스크 쓰기 대기 평균 ms(물리) | 디스크 flush 평균 ms(물리) | 디스크 util %(물리) | 비정상 회차 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L4 | S7-m1 | 1 | 100 | 0 | 0 | 0 | 100 | 0 | 0 | 0 | {"HOLD_LIMIT_EXCEEDED": 100} | 0 | 0 | 0 [-1–2] | 0 | 9.52 | 미측정 | 400 | 0.5615 | 0.0006 | 0.3828 | 19.780 | 0 | 0 | 1.002 | 195 | 0.764 | 2.22 | 0.5 | 1,327 | 0 | 1.292 | 0.462 | 1.1 | - |

## DB 설정 적용값(SHOW)·대기 이벤트 — 회차별

> 대기 이벤트 = timeline-waits.jsonl(0.5초 표본, seat DB의 활성 세션 — 표본 세션 제외) k6 구간 평균 세션 수. `CPU:-` = 대기 없이 실행 중. 표본 = k6 구간 안 표본 수.

| 단계 | 셀 | 회차 | status | preset | checkpoint_timeout | max_wal_size | shared_buffers | 표본 | 대기 이벤트 상위 5(평균 세션) |
|---|---|---|---|---|---|---|---|---|---|
| L4 | S7-m1 | rep1 | ok | default | 5min | 1GB | 128MB | 14 | 활성 세션 없음(표본 있음) |
