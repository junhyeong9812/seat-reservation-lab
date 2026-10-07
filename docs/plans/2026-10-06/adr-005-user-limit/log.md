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
| 10-06 18:22 | task 05 中 듀얼 1패스(코드+하네스) 시작 — packet: git diff 4674c3c6..HEAD(로그·NEXT·measurement·ADR-005 results 제외) + spec + related-raw, 미러 = src·docs/adr·k6/ADR-003 스크립트·k6/ADR-005(+스모크 요약 파일), `$OUT=scratchpad/rv5`. 보안 스캔 0건 | codex(medium) ∥ Opus |
| 10-06 (리뷰 회수) | codex 4건 · Opus 11건 + OQ 3 회수. 메인 재현: 즉시 실패형의 좌석 전 거절(코드 :67·:118) · 타이머가 좌석 구간 누락(HoldSeatProcess) · S7 억울한 409가 SNA만(summarize :276) · S3 plan reps 5(campaign :62) · ensureQuotaRow가 매 요청(around 매번) — 확인. ON CONFLICT 대기는 특성 테스트로 확인(아래) | 사용자 결정 1건(에러 순서) |
| 10-06 (사용자) | 에러 순서: 처음 '즉시 실패형도 예외 허용' 선택 → 곧바로 "명세를 보존하고 위 내용은 추가로 확인하는게 맞지 않나?" → 해석 확인 질문 → **"명세 순서로 고치고, 먼저 거절하는 변형을 추가 측정"**(재합의: 방식 10개, quota-nowait 계열 SKIP LOCKED) | spec §0·§1·§2·§9 갱신 |
| 10-06 18:48 | 수정(앱): prepare 단계 분리(타이머 prepare) · ensureQuotaRow 'SELECT 먼저' · span 타이머 · acquire→Boolean/check(entered) — L2·L4 명세 순서, -early 2개, L4 계열 SKIP LOCKED · 기동 검사(매수 방식 ≠ none이면 좌석 3b만) · counter + 배경 행 거부. 테스트: 40001 결정적(L6 거절 1·L7 재시도 성공) · 진입 쥔 동안 다른 사용자 통과/같은 사용자 대기·거절·경합 중 에러 순서 · ON CONFLICT 대기 특성 → **151/151 green**(중간 실패: 격리 수준 테스트가 prepare 미호출 3건 — 테스트 수정). 수정(하네스): 방식 10개 · S3 plan reps 3 · S7 억울한 409 코드 무관 + 코드별 · S7 setup 409 HLE 순차 재시도 5 · 타이머 4종 차분, MAX 교차표 제외 · 캠페인 끝 limit-bench, 없으면 '미측정'+problem. 문서: ADR-005 §2·§3·§5·§6.1·6.1.1, README | 다음: 재스모크 → codex post-fix 재점검 |
| 10-06 (재스모크) | 재스모크 루프(a89741e9) 시작 → advisory-try exit 3: **서버 디스크 100%**(98G, 여유 0) — 배포가 git archive 전체(커밋된 k6/*/results, 회당 약 3~3.5G)를 SHA 폴더마다 복사해 ~/labs/seat-reservation-lab이 53G. advisory-try-early exit 2(run.sh 허용 목록 누락 — codex 재점검 F1과 같은 원인) → 루프 중단(TaskStop) | 다른 서비스 영향 가능 — 사용자 확인 |
| 10-06 (재점검) | codex post-fix 재점검: C3·C4·O1·O2·O6·O7·O9·O10·OQ1 해소 · **C1 미해소(F1 run.sh 허용 목록에 -early 없음)** · **C2 미해소(F2 span이 409에서 기록 안 됨·락 대기 포함)** · O5 부분(F3 300ms 지연 의존) → 앱: span을 진입 직후 시작 + finally 기록, 40001 테스트 지연 1s — 151/151(4e506390) | 하네스 F1은 루프 종료 뒤 수정 |
| 10-06 18:58 | 사용자 승인 "23개 전부 삭제" → 삭제 직전 재확인(경로 /home/jun/labs/seat-reservation-lab, 목록: SHA 24개 + 실패 배포 .tmp 1개 — 승인 때 23개라 말한 것과 개수 차이 고지) → 삭제 → **디스크 44%(여유 53G)**. 재발 방지: lib.sh 두 배포 함수에서 `:(exclude,glob)k6/*/results/**` — 배포 크기 3.5GB → 1.3MB. run.sh 허용 목록에 -early 2개 | 재스모크 다시 |
| 10-06 (재스모크 2) | 4d02bdfa(앱 = 4e506390 + 하네스 수정), worktree: S2 L4 advisory-try·advisory-try-early·quota-nowait·quota-nowait-early 4개 ok — 매수 초과 0 · 201 − 홀드 0 · HLE 800 · 가짜 거절 0 · span 평균 1.6~3.1ms · **quota 계열 prepare 평균 약 60ms**(트랜잭션 밖 커넥션 1회 더 — S2 버스트에서 풀 대기로 보임) / S7 L4 serializable ok — setup 재시도로 완료, 40001 169, 억울한 좌석 1(409 20). 서버 디스크 44%, 배포 폴더 1.8M | 본측정 준비 완료 |
| 10-06 19:06 | **본측정 시작**: campaign.sh --sha 7acad14b --id 20261006-adr005-7acad14b — 유닛 seatlab-adr005(ManagedOOMPreference=avoid, Restart=on-failure 180s·6h 5회), 30조건(방식 10 × s24·s3·s7), 회차 우선, 끝에 limit-bench | 약 50~55h 추정 · 사용자 linger 꺼짐(로그아웃 시 정지 위험 — ADR-002 이후 그대로) |
| 10-07 18:2x | 진행 확인(1회차 30/30, 2회차 15/30, ok 146 · path-gap 1 — counter-s3 S3-a20 rep1, 끝에 재측정). 1회차 serializable S4 40001: L2 179,053 · L4 625,787(retry: 242,634 · 1,235,828) — S4는 같은 사용자 경합이 없어 전부 다른 사용자 충돌. 사용자 제기 '다른 사용자끼리 충돌인지 1명 사용자 충돌인지 중요' → ADR-005 §4에 ⑥′ 추가(측정 무변경 — 캠페인 진행 중) | 건별 분리는 후속 측정 후보 |
| 10-07 (사용자 질문) | '셀렉트가 아닌 업데이트 쿼리 과정의 충돌?' → 40001은 ① 읽기-쓰기 의존(SSI — 매수 SELECT × 홀드 INSERT) ② 쓰기-쓰기(같은 좌석 행) 두 원인. S4는 ①로 추정(측정에 원인 기록 없음) → 후속: 메시지별 집계를 ADR-005 §4 ⑥′에 추가 | 측정 무변경 |

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| C1 | 1 | codex·opus | UserLimitStrategies.kt L2·L4 acquire | 채택 — 즉시 실패형이 좌석 확인 전 거절(명세 §2 위반) | fixed(재합의: 명세 순서 + -early 변형) | 1 |
| C2 | 1 | codex·opus(OQ2) | HoldSeatProcess.kt 타이머 | 채택 — 좌석 구간이 어느 타이머에도 없음 | fixed(span·prepare) | 1 |
| C3 | 1 | codex·opus | summarize.py S7 wronged_409 | 채택 — SNA만 셈(L6·L7 과소) | fixed(코드 무관 + 코드별) | 1 |
| C4 | 1 | codex | campaign.sh S3 reps | 채택 — plan 5 vs 실행 3 | fixed | 1 |
| O1 | 1 | opus | ensureQuotaRow ON CONFLICT | 채택 — UPDATE 중인 행을 기다림(특성 테스트로 확인) | fixed(SELECT 먼저 + 특성 테스트) | 1 |
| O2 | 1 | opus | s7 setup 병렬 + serializable | 채택 — 40001로 setup throw 반복 가능 | fixed(HLE 순차 재시도 5) — 재스모크 대상 | 1 |
| O5 | 1 | opus | 테스트 — L7 재시도·L6 40001 경로 | 채택 | fixed(결정적 충돌 테스트) | 1 |
| O6 | 1 | opus | 테스트 — 다른 사용자 비차단 | 채택 — 전역 직렬화여도 통과(그린 위장) | fixed(락 쥔 채 확인) | 1 |
| O7 | 1 | opus | run.sh MAX 창 | 채택 — 예열 혼입 | fixed(교차표 제외·README) | 1 |
| O8 | 1 | opus | ADR §2.5 '첫 요청' | 채택 — 매 요청 | fixed | 1 |
| O9 | 1 | opus | compare.py limit-bench | 채택 — 캠페인에 없고 무음 생략 | fixed(캠페인 끝 실행 + 미측정·problem) | 1 |
| O10 | 1 | opus | LoadtestDataService 배경 × counter | 채택 — 거짓 위반 잠복 | fixed(거부) | 1 |
| O11 | 1 | opus | V4/V5 번호 | 기록만 — 측정 영향 없음(회차마다 DB 삭제) | user-deferred 아님·ADR 기록 | — |
| OQ1 | 1 | opus | HoldStrategyStartupCheck | 채택 — 3b 외 조합 차단 | fixed | 1 |
| OQ3 | 1 | opus | 앱 2대 만료 교착 | 범위 밖(앱 1대) — ADR 기록 | — | — |

## 생략한 검증

- (없음)

## 완료 요약
