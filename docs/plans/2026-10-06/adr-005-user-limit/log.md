# 작업 로그 — ADR-005 1인 2매: 같은 사용자 동시 요청

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-06 (오전~17:25) | 인터뷰 1~3차(답변 원문은 requirement-spec §0). 측정 규모 A, 매수 방식 8개, 사용자 → 좌석, 거절 코드 HOLD_LIMIT_EXCEEDED 재사용, 기본 좌석 전략 3b, counter만 만료 감소·에러 순서 변경 허용 | — |
| 10-06 17:25 | 브랜치 feat/adr-005-user-limit(main 4674c3c6) · requirement-spec 작성 | 사용자 합의 대기 |
| 10-06 (합의) | 사용자 "진행해보자" → set-state spec-approved · mode auto(권장안 그대로 진행 지시) | SPEC=1 MODE=auto |
| 10-06 17:43 | task 02·03(코드): 기본 좌석 전략 3b · `UserLimitStrategy` 8종(none·advisory·advisory-try·quota-lock·quota-nowait·counter·serializable·serializable-retry) · V5 `user_hold_quota` · 만료 배치가 실제 지운 홀드로 카운터 감소(counter만) · `ProductSeat.expireHolds`가 지운 홀드를 돌려줌 · 타이머 `seat.hold.limit.acquire`/`check` · 판정기 `v_counter_mismatch`(counter만 항목 생성). 테스트: 기존 68 → 기본값 테스트 1(none→3b, 명세 변경) · 판정기 null 합산 NPE 10(항목을 counter일 때만 내도록 수정) · **quota-nowait 55P03이 JdbcTemplate에서 UncategorizedSQLException으로 번역돼 catch를 지나 500**(테스트가 잡음 → DataAccessException + SQLState로) · flyway target 1 컨텍스트에 쿼터 테이블 없음(정리 조건부). 최종 124/124 green. **가정 1(테스트 수준)**: none 5라운드 안 매수 초과 재현 — 좌석 3b 아래에서도 매수 경합 그대로 · **가정 2**: 8종 모두 같은 좌석 50명 중 1명만 이김. SERIALIZABLE 적용은 SHOW transaction_isolation으로 직접 확인 | 가정 1의 S2 스모크는 하네스 뒤 |
| 10-06 17:47 | 하네스(task 04) Opus 워커 위임(기준 HEAD 83fb4719 — k6/ADR-005 복사·--limit-strategy·S7·S2 가짜 거절·타이머 차분·limit-bench·스모크 5종, 커밋 금지) · 병행: task 01 ADR-005 §1~§6 작성(선택지 8개 코드·SQL, 단계표 ①.3, 가설 H1~H7, 측정 전 검증 6.1 발견 3건) | 워커 회수 대기 |
| 10-06 (워커 회수) | 하네스 회수 — k6/ADR-005(복사 + --limit-strategy·S7·S2 사용자별·타이머 차분·limit-bench·mismatch), .gitignore 1행. 스모크(앱 83fb4719, worktree 배포, 워커 기록 17:58~18:13): S2 none 매수 초과 15(**가정 1 실증**) · S2 counter 0·불일치 0 · S7 none 억울한 좌석 0(U 락 21/100) · S7 counter 0(U 100/100 좌석 락 전 거절) · mismatch 주입 → limit-strategy-mismatch · bench 배경 0 ok. 메인 교차 확인: 5개 status·consistency.json 수치 일치. 서버 폴더 83fb4719-wt-* 4개 생성(컨테이너 없음) | S7 설계는 사용자 결정 |
| 10-06 18:18 | 사용자: S7 'M=20 유지 + M=1 변형 추가'(재합의 — spec §0·§9.2) → 하네스에 셀 S7-m1(cell_base로 S7 계열, k6 M=1) · campaign S7 조건에 S7-m1 · 요약기 두 표. 스모크 S7-m1 none 1회 시작 | 결과 대기 |
| 10-06 18:21 | 스모크 S7-m1 none(앱 899b317f, worktree, 18:20 종료): status ok · **억울한 좌석 77/100**(U 좌석 락 후 롤백 79 · 일반 201 23 · 빈 좌석 77) · 201 − 홀드 행 0 · 일반−U 보낸 시각 −1~2ms. M=20의 0과 대비 — 경쟁자가 적으면 3b 롤백 피해가 직접 드러난다 | 하네스 커밋 → 리뷰 |

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|

## 생략한 검증

- (없음)

## 완료 요약
