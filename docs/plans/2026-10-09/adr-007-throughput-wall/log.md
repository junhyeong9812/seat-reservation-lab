# 작업 로그 — ADR-007 counter-upsert 처리량 벽 진단 + 쓰기 없는 선조회 변형

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-09 14:05 | 브랜치 feat/adr-007-throughput-wall(main f838850b) · 인터뷰 3차(진단 4축 전부 · advisory-try 제외(사용자: 억울한 좌석을 해결 못 함) · 변형 = 좌석 선조회 후 upsert, 에러 순서는 명세 순서 · 설정 독립 3조건 · S4 계단 + 고정 2종 · 변형 확인 S2·S7-m1·S3-a20 · 3회 · 약 15h) · requirement-spec 작성 | — |
| 10-09 14:20 | 사용자 "합의 — lazy" → spec-approved · 직후 "auto로 부탁해" → mode auto | SPEC=1 MODE=auto |
| 10-09 14:35 | task 02: COUNTER_UPSERT_PRECHECK + CounterUpsertPrecheckUserLimit(선조회 → upsert → 0행이면 재조회) · upsert 공용 함수 · COUNTER_STRATEGIES 포함 · 테스트: 기존 묶음(limitFirst 분리 — precheck는 좌석 먼저) + 추가 3(쓰기 없음 xmax 0 · 커밋 전 잠긴 좌석 틈 · 없는 좌석) → **173/173 green**. 그린 위장 점검: 선조회 제거 시 2건 실패 → 복원 | 4bf8d7d9 |
| 10-09 14:40 | task 03 하네스 워커(Opus) 위임 — k6/ADR-007(설정 preset·대기 이벤트·고정 도착률·벽 분석·캠페인 108회·판정식 양방향 확인) + 서버 스모크 4종 · task 01: ADR-007 선택지·가설 H1~H6·계획 | 회수 대기 |
| 10-09 14:58 | task 03 회수(Opus·high, 13:05~14:58) — 커밋 c402e12b·01019ad3·53df003d·9a934f76·a8494242. 스모크 a3·b·c·d2 ok. 워커 발견·수정: ①표본 흘려받기가 +240~261초에 멈춤(원인 미확인 — 서버 사본 tee + 회차 끝 복구) ②정리 트랩이 하위 셸에서 돌아 동결 스크립트 삭제 → `$BASHPID == $$` 한정 ③고정 셀 기동 과도로 벽 1초 → 기동 과도 뒤 벽 추가. 워커 compose down이 유휴 ADR-006 스택(seatlab 프로젝트, 범위 안)을 내림. .164 `/tmp/q.sh` 1회 생성·즉시 삭제(범위 밖 경로 — 기록). S4c2000·buf512 실측 스모크는 미실시 | 커밋 5 |
| 10-09 15:00 | 메인 교차 확인(실파일): order.txt 비주석 108행 · (b) pg_applied 1h/16GB, db.log의 checkpoint는 ready 전 shutdown 1건뿐 → 가정 1 성립 · (a3) 05:42:33~50 표본 waits `LWLock:WALWrite` 9/10, checkpointer CheckpointWriteDelay → 가정 2 성립 · 4 스모크 meta status ok. 결정(PM): 고정 셀 벽 = 기동 과도 뒤 벽(문자 그대로의 정의는 기동 과도에서 1초가 나와 판정 불가) — ADR §5.1 기록, 사용자 보고. 새 관측: none도 nockpt 고정 4,200/s에서 153초에 무너짐(WALWrite 최다) — 본측정에서 확인 | 가정 1·2 성립 |

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|

## 생략한 검증

- (없음)

## 완료 요약
