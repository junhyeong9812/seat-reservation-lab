# 작업 로그 — ADR-006 매수 카운터 한 문장 upsert: counter vs advisory-try 재비교

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-09 (이전) | ADR-005 결정(advisory-try) · 사용자 'counter를 1방 쿼리로 바꿔 ADR-006에서 비교, 다음 단계는 한 칸씩 미루기' → ADR-006~011 → 007~012 · '세 방식을 같은 캠페인에서' | ADR-005 브랜치에서 처리(PR #5) |
| 10-09 01:05 | 브랜치 feat/adr-006-counter-upsert(main 201aabdd) · 인터뷰 1차(하네스 개선 3 전부 · 기본 안 + S3 a20 · 기존 counter 유지 + 새 방식) · requirement-spec 작성 | 사용자 합의 대기 |
| 10-09 (합의) | 사용자 "진행해보자" → spec-approved · mode auto(권장안 그대로) | SPEC=1 MODE=auto |
| 10-09 01:20 | task 02: COUNTER_UPSERT + CounterUpsertUserLimit(acquire 한 문장 upsert, prepare 없음) · 만료 감소 공용 함수 · 판정기·배경 거부 대상에 포함 · 테스트: 기존 계약 묶음 + 새 사용자 동시 10요청 5라운드(성공 정확히 2 = 카운터) → **160/160 green**(가정 1 테스트 수준 실증) | 다음: 하네스(워커) |
| 10-09 01:28~01:48 (워커 기록) | 하네스 회수(Opus): k6/ADR-006(9조건, 순서 = sha256(seed:rep:조건) 정렬·order.txt, I/O 스냅샷 k6 전후 + 11s 안정화, track_io_timing·track_wal_io_timing on — ADR-005와 측정 조건 다름 명시, S4 규칙 '실도착 < 0.9 × 목표'에서 멈춤) · 스모크(앱 6be8a410 + worktree): S4 L4 counter-upsert ok **빌림 1.0001**(가정 2 실증) · 엄격 2,866 · 포화점 4,128 / S2 ok 초과 0·불일치 0·빌림 1.000 / S7-m1 ok 억울한 좌석 0 · 순서 dry-run 재현. ADR-005 S4 재계산: serializable L4만 바뀜(4/5회 673~806 → 1,972~1,994, 중앙 708 → 1,982). 메인 교차 확인: 세 스모크 status·빌림·consistency 일치. 서버 디스크 44 → 45% | ADR-005 §7.3 ③ 문구 정정 필요(4,325 단계는 목표 미달 아님 — 6,487부터) |
| 10-09 01:50 | 하네스 커밋 · 다음: 코드·하네스 中 듀얼 1패스 | — |
| 10-09 (코드 리뷰) | 中 듀얼 1패스(미러 scratchpad/rv6): codex 3 · Opus 5 + OQ 5. 중복 병합 8건 채택 | 앱 2건 메인 수정 · 하네스 6건 워커 위임 |
| 10-09 02:05 | 앱 수정: hasPrepare(준비 없는 방식은 prepare 호출·타이머 생략 — 명세 §5 'prepare 타이머 0건') · upsert 경합 테스트에 ready 출발선 · 첫 요청 롤백 시 카운터 테스트 · prepare 0건 테스트 → **162/162 green**(9d225fbd). 하네스 6건(S4 포함 판정·요청 0 단계·빌림 분모 재시도·재측정 순번·order.txt 행 수·순서 균형 순환) Opus 워커 위임 | 회수 대기 |

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| R1 | 1 | codex·opus | HoldSeatService.kt:29 prepare 타이머 | 채택 — 준비 없는 방식도 기록(명세 §5) | fixed(hasPrepare) | 1 |
| R2 | 1 | opus | UserLimitStrategyTest upsert 경합 출발선 | 채택 | fixed(ready 래치 + 롤백 테스트) | 1 |
| R3 | 1 | codex | compare.py·recompute_s4.py S4 포함 판정 | 채택 — 복합 실패 혼입 | 위임 | — |
| R4 | 1 | codex | summarize.py 요청 0 단계 | 채택 | 위임 | — |
| R5 | 1 | opus | run.sh hold_requests 분모 | 채택 — 빌림 무음 왜곡 | 위임 | — |
| R6 | 1 | opus | compare.py 재측정 순번 | 채택 | 위임 | — |
| R7 | 1 | opus | campaign.sh order.txt 행 수 | 채택 — 0조건 성공 종료 | 위임 | — |
| R8 | 1 | opus(OQ) | 순서 균형 | 채택 — none이 s24 첫 번째 0회 | 위임(균형 순환) | — |

## 생략한 검증

- (없음)

## 완료 요약
