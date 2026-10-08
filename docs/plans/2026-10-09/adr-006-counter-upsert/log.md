# 작업 로그 — ADR-006 매수 카운터 한 문장 upsert: counter vs advisory-try 재비교

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-09 (이전) | ADR-005 결정(advisory-try) · 사용자 'counter를 1방 쿼리로 바꿔 ADR-006에서 비교, 다음 단계는 한 칸씩 미루기' → ADR-006~011 → 007~012 · '세 방식을 같은 캠페인에서' | ADR-005 브랜치에서 처리(PR #5) |
| 10-09 01:05 | 브랜치 feat/adr-006-counter-upsert(main 201aabdd) · 인터뷰 1차(하네스 개선 3 전부 · 기본 안 + S3 a20 · 기존 counter 유지 + 새 방식) · requirement-spec 작성 | 사용자 합의 대기 |
| 10-09 (합의) | 사용자 "진행해보자" → spec-approved · mode auto(권장안 그대로) | SPEC=1 MODE=auto |
| 10-09 01:20 | task 02: COUNTER_UPSERT + CounterUpsertUserLimit(acquire 한 문장 upsert, prepare 없음) · 만료 감소 공용 함수 · 판정기·배경 거부 대상에 포함 · 테스트: 기존 계약 묶음 + 새 사용자 동시 10요청 5라운드(성공 정확히 2 = 카운터) → **160/160 green**(가정 1 테스트 수준 실증) | 다음: 하네스(워커) |

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|

## 생략한 검증

- (없음)

## 완료 요약
