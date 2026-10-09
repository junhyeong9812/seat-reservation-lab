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
| 10-09 15:10 | task 04 듀얼 1패스 시작 — packet base f838850b, `$OUT`=scratchpad/rv7(packet·mirror·results), 제외 k6/*/results/**(스모크 4종 meta·status·wall·summary만 미러 포함). 보안 스캔: DB_PASSWORD 환경변수 자리·UUID token 변수(오탐), 사설 LAN IP 192.168.55.164·45.29(기존 tracked·이미 공개 — 통과) · Opus 워커 ∥ codex(medium) 병렬 | 리뷰 중 |
| 10-09 15:35 | ① 회수: codex(medium) finding 4·OQ 1 · Opus(high) finding 7·OQ 6 → ② 메인 종합(실파일 재현): A1~A14 채택, 기각 2(아래 ledger). 메인 재현으로 OQ 3건을 결함으로 확정 — mawk 입력 블록 버퍼링(로컬 재현: 일반 awk는 2초 뒤 0줄, `-W interactive`는 20줄; 스모크 errors.log 'db_sampler ended' 0건 → SSH 끊김 가설은 틀렸다) · k6 status 0 지연 ≈0(smoke-b 20,222건 중 18,538건 < 5ms) · 고정 셀 기동 과도로 VU 10,000 → 구간마다 request timeout 1,000~1,700 + dial i/o timeout 지속(계단 S4는 붕괴 전 0). 기전 가설(Tomcat max-connections 기본 8192 < VU 10,000)은 미검증. ③ 종합 감사는 수정 후 재점검과 함께 codex에 1회 | 수정 위임 |
| 10-09 15:45 | task 04 수정 워커(Opus·high) 위임 — A1~A14(A8은 테스트 전용 커밋, 나머지 k6/ADR-007만) + 서버 스모크 smoke2-*(none·nockpt·S4c4200 램프 후 / counter-upsert·nockpt·S4 / precheck·buf512·S2 / precheck·default·S7-m1). A13 후에도 붕괴 전 status 0이 남으면 추가 수정 없이 보고하게 함 | 회수 대기 |
| 10-09 16:50 | task 04 수정 회수(Opus·high, 15:30~16:48) — 22c9b14c(테스트 A8)·3ba178e0(분석)·1fdec01e(수집·판정). 175/175 green(메인 재확인: test-results xml 합계 175·실패 0) · 판정식 양방향 60/60 · dry-run 108 · ADR-006 wall 30/30·SUMMARY 동일. **사고**: 워커 판정식 테스트가 ADR-006 tracked errors.log 3개에 줄을 덧붙임(추가만) → `git checkout --` 3파일 복원, 메인 확인 `git status k6/ADR-006` clean. 스모크 smoke2 a~e 전부 ok(메인 재확인). 메인 재현: smoke2-a 30초 구간별 status 0 = 0이 150초 구간까지, 붕괴 뒤 구간마다 1,808(= 10,000 VU − 8,192 → Tomcat max-connections 가설 강화, 미검증) · wall-check: a 162초(유지 3구간) · b(counter-upsert·nockpt·S4) 358초 11단계, k6 중 체크포인트 0 → H1 반대 신호(1회) · e(counter-upsert·기본·4,200/s) 146초, wal 체크포인트 +75~205 진행 중. **S7-m1 기회 수 0**(smoke2-d와 ADR-006 10회 전부 — U가 R의 201보다 늘 먼저 감) → spec ⑥·H6를 지금 시나리오로 판정할 수 없음 → 사용자 결정 필요 | A1~A14 fixed(재점검 대기) |
| 10-09 16:55 | post-fix 타깃 재점검 + 종합 감사 codex 1회(medium, rv7b 미러 — fix diff·1패스 원문·채택표) 백그라운드 | 진행 중 |
| 10-09 17:00 | post-fix 재점검 + 종합 감사 회수(codex) — A1~A3·A5·A6·A8~A14 fixed, A4 부분(과도 미종료 분기), A7 기회 0, 신규 N1(램프 뒤 누적 시각 설명), 감사: R1·R2 기각 근거 불충분 → 채택으로 번복. 사용자 결정(AskUserQuestion): **"늦게 보내는 셀 추가"** → spec 재합의 기록(§9.2·⑥, 117회), ADR-007 §4·§5·§5.1 갱신 | 재합의 |
| 10-09 17:05 | task 04 fix2 같은 워커에 이어 위임 — S7-m1-late · A4 잔여 · N1 · R1·R2 보강 + smoke3(S7-m1-late × 3방식, S2 1) | 회수 대기 |
| 10-09 18:39 | fix2 회수(같은 워커) — 952d2cf3: S7-m1-late(같은 VU가 R 응답 뒤 U · 기회는 구성상 `order=after_r`) · A4 잔여 · N1(`cum370k_s`) · R1(원격 루프 `kill -0 $PPID`·출력 실패 시 종료) · R2(사본 이름 시도마다·삭제 실패 사유). 오프라인: dry-run 117 · 판정식 69/69(픽스처 복사 — 심링크 금지) · ADR-006 wall 30/30·SUMMARY 동일. smoke3 4종 ok. 메인 재확인: summary.json 기회/위반 none 100/0 · counter-upsert 100/100 · precheck 100/0, ADR-006 tracked 변경 0 | — |
| 10-09 18:45 | post-fix 재점검 2(codex, rv7c) — A4·A7·N1·R1·R2 fixed, 신규 N2(회전 균형). 메인 전수 계산: 13묶음은 폭 1~12 어느 것도 균형 불가, 12묶음 × 4는 가능 → S7-m1+S7-m1-late 한 묶음(ea8b85cc). dry-run 117, 12묶음 전부 앞·가운데·뒤 각 1회, 조건 39 × 3. N2는 메인 수정 — 1줄 범위라 별도 재점검 없이 dry-run 검산으로 확인. codex OQ(SSH 비정상 종료 현장 재현)는 원격 시험 1회(워커)로 갈음 | 리뷰 종료 |
| 10-09 18:50 | **본측정 시작**: `systemd-run --user --unit seatlab-adr007 -p Restart=on-failure` campaign.sh --sha ea8b85cc --id 20261009-adr007-ea8b85cc · 39조건 × 3 = 117 · linger 꺼짐(로그아웃 시 정지) · 노트북 여유 19G(ADR-006 84회 1.6G). S4c2000은 본측정 첫 회차가 첫 실측(스모크 없음) — 첫 회차 결과 확인 예정 | 약 15h 추정 |

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| A1 | 1 | opus F1 | k6/ADR-007/scripts/wall.py:124-127 | 채택 — 계단 벽 창이 11단계 [시작,370)에 고정 → 밀린 벽·이른 붕괴·벽 없음이 모두 '-' | fixed |  1 |
| A2 | 1 | codex F2 + opus F4 | wall.py:131-132 · compare.py:338-344 | 채택 — 과도 미종료(settle None)가 '벽 없음'과 같은 '-' | fixed |  1 |
| A3 | 1 | opus F3 | wall.py:148 · lib.sh:132 | 채택 — 벽 시각(PC 시계)과 표본·DB 로그(서버 시계) 시계 차 미측정 | fixed |  1 |
| A4 | 1 | codex F3 | run.sh:595 · wall.py:92 | 채택 — 벽 ±3초 표본 부족이 회차 판정에 없음 | fixed |  1b |
| A5 | 1 | codex F4 | summarize.py:303 | 채택 — S2·S7 hold_code 행 수를 요청 수와 대조하지 않음 | fixed |  1 |
| A6 | 1 | codex F1 | summarize.py:157 | 부분 채택 — status ok는 회차 유효성(붕괴 자체가 측정값)이라 유지, 비교표에 드롭·달성률 표기 | fixed |  1 |
| A7 | 1 | opus F2 | summarize.py:398-437 · s7-winner-rollback.js:63 | 채택 — S7 순서 위반 지표에 기회 수(분모) 없음, H6 반증 기준이 틈 포함 지표에 묶임 | fixed |  1b |
| A8 | 1 | opus F5 | UserLimitStrategyTest.kt:338-389 | 채택 — precheck에 동시 새 사용자 경합(카운터=성공 수)·prepare 타이머 0 계약 없음 | fixed |  1 |
| A9 | 1 | opus F6 | results/smoke-*/pg-settings.json | 채택 — buf512 실측 스모크 없음 | fixed |  1 |
| A10 | 1 | opus F7 | run.sh:346 · gapcheck.py:28-29 · summarize.py:838 | 채택 — 짧은 셀 k6 구간 표본 0 미검출·대기 시간 초과 무기록·'미측정' 표기 혼동 | fixed |  1 |
| A11 | 1 | opus OQ1 → main-synthesis | run.sh:271 | 채택 — 흘려받기 정지 원인 = mawk 파이프 입력 블록 버퍼링(종료 시 버퍼 손실) | fixed |  1 |
| A12 | 1 | opus OQ3 → main-synthesis | wall.py:115-117 · summarize 구간표 | 채택 — status 0(타임아웃) 지연이 ≈0으로 기록돼 초별 p99 과소 | fixed |  1 |
| A13 | 1 | opus OQ4 → main-synthesis | scenarios/s4-throughput.js(S4c) · run.sh | 채택 — 고정 셀 0초부터 목표 도착률 → VU 10,000·타임아웃 지속, 고정 셀 측정 오염 | fixed |  1 |
| A14 | 1 | codex OQ | wall.py:137 · run.sh 회차 판정 | 채택 — nockpt 회차마다 k6 구간 체크포인트 0을 판정에 넣지 않음 | fixed |  1 |
| R1 | 1 | opus OQ2 | run.sh:269-275 · lib.sh:132-135 | 기각 → **감사로 번복: 채택** — 0건 관측은 실패 경로 안전성을 입증하지 못함(원격 루프 종료 조건 없음) | fixed |  1b |
| R2 | 1 | opus OQ6 | run.sh:229-250 | 기각 → **감사로 번복: 채택** — 사본 삭제 실패가 무시되고 경로가 재사용됨 | fixed |  1b |
| N1 | 1 | codex post-fix | run.sh:199-204 · README.md:71 | 채택 — 램프 뒤에도 누적 37만 도달을 88·185초로 설명(계획 기준 약 118·214초) | fixed |  1b |
| N2 | 1b | codex post-fix 2 | campaign.sh:91-98 | 채택 — 13묶음 × 5칸 회전은 묶음마다 앞·가운데·뒤 1/3을 한 번씩 맡지 못함(어떤 폭도 불가 — 메인 전수 계산) → S7 두 셀을 한 묶음으로, 12 × 4칸 | fixed | 1b |
| Q1 | 1 | opus OQ5 | wall.py:13-17 | open question → 사용자 보고(고정 셀 벽 = 과도 뒤 벽 — PM 결정, A13 램프 도입 후 재판단) | — | |

## 생략한 검증

- (없음)

## 완료 요약
