# ADR-009 결과 요약 — `2g-zgc-soak` · 조건 default · 정리 schedule · 매수 방식 counter-upsert-precheck · DB 설정 tmpfs · 좌석 전략 pessimistic-nowait · 풀 10 · 앱 1대 · 하네스 sha:c5117f4b

> `scripts/summarize.py`가 원시 결과에서 산출 · 측정 SHA `c5117f4b` · 계획 1단계 × 1셀 × 2회.
> 값 = 중앙값 [최소–최대], n = 정상 회차 수. 정상이 아닌 회차(실패·미측정)는 마지막 열에 모두 표시한다.

## S4c4200 고정 도착률 — 30초 구간별 (한계 규칙 없음 · 목표 미달 = 실제 도착 < 0.9 × 목표 · 램프 제외)

> status `ok` = 회차가 유효하다(계획 구간을 다 돌았다)는 뜻이지 목표를 냈다는 뜻이 아니다 — 무너짐 자체가 측정값이다(리뷰 A6). 목표 달성은 도착/목표·dropped·코드 0 열로 본다.
> p99(코드 0 제외) = 코드 0(요청 타임아웃·연결 실패·끊김) 행을 뺀 p99 — k6 요약 p99는 코드 0의 0에 가까운 지연을 섞어 낮게 나온다(리뷰 A12, `fixed_csv`). dropped = k6가 VU가 없어 시작하지 못한 반복(구간 = 램프 끝 기준 30초, ±1초).

> L4 · n = 0 · 전체 성공 RPS 미측정 · 전체 도착 RPS 미측정 · 도착/목표 미측정 · 목표 미달 구간 수 미측정 · 첫 목표 미달 구간 미측정 · 가장 나쁜 구간 p99 ms(코드 0 제외) 미측정 · (k6 요약 p99 미측정) · 구간 코드 0 미측정 (미측정) · 구간 dropped 미측정 · k6 dropped 전체(램프 포함) 미측정 · 램프 미측정초 요청 미측정 · 코드 0 미측정 · dropped 미측정 · 풀 타임아웃 미측정 · 커넥션 획득 대기 평균 ms 미측정 · 체크포인트 수 미측정 · 비정상 회차 rep1:invalid-consistency.json,accept-consistency-missing,db-log-fatal, rep2:missing-status

| 구간 | 시작 초(램프 끝 기준) | 목표/s | 실제 도착/s | 도착/목표 | dropped | 성공/s | p99 ms(코드 0 제외) | 코드 0 비율 | 코드 0 수 | k6 요약 p99 ms | 에러율 | 목표 미달 회차 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|

## DB 설정 적용값(SHOW)·대기 이벤트 — 회차별

> 대기 이벤트 = timeline-waits.jsonl(0.5초 표본, seat DB의 활성 세션 — 표본 세션 제외) k6 구간 평균 세션 수. `CPU:-` = 대기 없이 실행 중. 표본 = k6 구간 안 표본 수.

| 단계 | 셀 | 회차 | status | preset | checkpoint_timeout | max_wal_size | shared_buffers | 표본 | 대기 이벤트 상위 5(평균 세션) |
|---|---|---|---|---|---|---|---|---|---|
| L4 | S4c4200 | rep1 | invalid-consistency.json,accept-consistency-missing,db-log-fatal | tmpfs | 5min | 1GB | 128MB | 3289 | CPU:- 0.68; Client:ClientRead 0.56; Timeout:VacuumDelay 0.04; IPC:BgWorkerShutdown 0.01; IO:DataFileRead 0.01 |
| L4 | S4c4200 | rep2 | missing-status | - | - | - | - | 3380 | CPU:- 0.61; Client:ClientRead 0.53; Timeout:VacuumDelay 0.03; IO:DataFileRead 0.01; IPC:BgWorkerShutdown 0.01 |

## ADR-009 회차별 비용 — 조건 적용·마감 판정·WAL·MultiXact·대조(C·F)

> 빌림 = (Δ획득 − Δ대조 빌림 − Δ정리 빌림) ÷ (Δ선점 + Δ확정)(설계 §4d). 마감 판정 = 앱 타이머 seat.hold.sales-close.check(k6 구간). MultiXact ID = mxid_age 차분(datminmxid 불변일 때) · MX SLRU = MultiXactOffset/Member zeroed+written. 대조 = counter_reconcile_run(k6 구간과 겹친 바퀴 — 출처별). 출처별 대기 = by_src 평균 세션(F = application_name, C = 문장 표지 — lib.sh db_sampler).

| 셀 | 회차 | status | 조건 | 정리 | 요청당 빌림 | 선점 p99 ms | 마감 판정 n · 평균 ms | WAL MB | WAL 레코드 | FPI | MultiXact ID | MX SLRU 페이지 | MX 대기 세션 | 대조(출처: 바퀴 · 바퀴 s p50/max · 청크 최대 ms · 빌림 · 오류) | 앱 대조 빌림 | F 빌림 | 틱 실패 | 판정기 제외 회차 · 제외 쌍(쿼터∪실제) | 출처별 대기(평균 세션) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S4c4200 | rep1 | invalid-consistency.json,accept-consistency-missing,db-log-fatal | default | schedule | 1.0000 | 4.71 | 7,670,321.00 · 0.0749 | 8,636.8 | 116,203,732 | 128,897 | 7,203,954 | 30,574 | 0 | reconcile-app: 26 · 13.36/27.06 · 52 · 124663 · 0 | 124,663.00 | - | 0 | - · - | C 0.19; app 1.05; other 0.06 |
| S4c4200 | rep2 | missing-status | - | - | 1.0000 | 1.64 | 7,687,429.00 · 0.0730 | 8,603.7 | 116,440,859 | 123,378 | 7,234,128 | 29,980 | 0 | - | 125,260.00 | - | 0 | - · - | C 0.19; app 0.95; other 0.06 |
