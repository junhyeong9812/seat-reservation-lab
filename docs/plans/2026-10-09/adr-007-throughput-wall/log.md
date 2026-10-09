# 작업 로그 — ADR-007 counter-upsert 처리량 벽 진단 + 쓰기 없는 선조회 변형

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-09 14:05 | 브랜치 feat/adr-007-throughput-wall(main f838850b) · 인터뷰 3차(진단 4축 전부 · advisory-try 제외(사용자: 억울한 좌석을 해결 못 함) · 변형 = 좌석 선조회 후 upsert, 에러 순서는 명세 순서 · 설정 독립 3조건 · S4 계단 + 고정 2종 · 변형 확인 S2·S7-m1·S3-a20 · 3회 · 약 15h) · requirement-spec 작성 | — |
| 10-09 14:20 | 사용자 "합의 — lazy" → spec-approved · 직후 "auto로 부탁해" → mode auto | SPEC=1 MODE=auto |
| 10-09 14:35 | task 02: COUNTER_UPSERT_PRECHECK + CounterUpsertPrecheckUserLimit(선조회 → upsert → 0행이면 재조회) · upsert 공용 함수 · COUNTER_STRATEGIES 포함 · 테스트: 기존 묶음(limitFirst 분리 — precheck는 좌석 먼저) + 추가 3(쓰기 없음 xmax 0 · 커밋 전 잠긴 좌석 틈 · 없는 좌석) → **173/173 green**. 그린 위장 점검: 선조회 제거 시 2건 실패 → 복원 | 4bf8d7d9 |
| 10-09 14:40 | task 03 하네스 워커(Opus) 위임 — k6/ADR-007(설정 preset·대기 이벤트·고정 도착률·벽 분석·캠페인 108회·판정식 양방향 확인) + 서버 스모크 4종 · task 01: ADR-007 선택지·가설 H1~H6·계획 | 회수 대기 |

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|

## 생략한 검증

- (없음)

## 완료 요약
