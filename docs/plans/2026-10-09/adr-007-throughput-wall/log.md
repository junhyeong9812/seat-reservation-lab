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

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| A1 | 1 | opus F1 | k6/ADR-007/scripts/wall.py:124-127 | 채택 — 계단 벽 창이 11단계 [시작,370)에 고정 → 밀린 벽·이른 붕괴·벽 없음이 모두 '-' | open | |
| A2 | 1 | codex F2 + opus F4 | wall.py:131-132 · compare.py:338-344 | 채택 — 과도 미종료(settle None)가 '벽 없음'과 같은 '-' | open | |
| A3 | 1 | opus F3 | wall.py:148 · lib.sh:132 | 채택 — 벽 시각(PC 시계)과 표본·DB 로그(서버 시계) 시계 차 미측정 | open | |
| A4 | 1 | codex F3 | run.sh:595 · wall.py:92 | 채택 — 벽 ±3초 표본 부족이 회차 판정에 없음 | open | |
| A5 | 1 | codex F4 | summarize.py:303 | 채택 — S2·S7 hold_code 행 수를 요청 수와 대조하지 않음 | open | |
| A6 | 1 | codex F1 | summarize.py:157 | 부분 채택 — status ok는 회차 유효성(붕괴 자체가 측정값)이라 유지, 비교표에 드롭·달성률 표기 | open | |
| A7 | 1 | opus F2 | summarize.py:398-437 · s7-winner-rollback.js:63 | 채택 — S7 순서 위반 지표에 기회 수(분모) 없음, H6 반증 기준이 틈 포함 지표에 묶임 | open | |
| A8 | 1 | opus F5 | UserLimitStrategyTest.kt:338-389 | 채택 — precheck에 동시 새 사용자 경합(카운터=성공 수)·prepare 타이머 0 계약 없음 | open | |
| A9 | 1 | opus F6 | results/smoke-*/pg-settings.json | 채택 — buf512 실측 스모크 없음 | open | |
| A10 | 1 | opus F7 | run.sh:346 · gapcheck.py:28-29 · summarize.py:838 | 채택 — 짧은 셀 k6 구간 표본 0 미검출·대기 시간 초과 무기록·'미측정' 표기 혼동 | open | |
| A11 | 1 | opus OQ1 → main-synthesis | run.sh:271 | 채택 — 흘려받기 정지 원인 = mawk 파이프 입력 블록 버퍼링(종료 시 버퍼 손실) | open | |
| A12 | 1 | opus OQ3 → main-synthesis | wall.py:115-117 · summarize 구간표 | 채택 — status 0(타임아웃) 지연이 ≈0으로 기록돼 초별 p99 과소 | open | |
| A13 | 1 | opus OQ4 → main-synthesis | scenarios/s4-throughput.js(S4c) · run.sh | 채택 — 고정 셀 0초부터 목표 도착률 → VU 10,000·타임아웃 지속, 고정 셀 측정 오염 | open | |
| A14 | 1 | codex OQ | wall.py:137 · run.sh 회차 판정 | 채택 — nockpt 회차마다 k6 구간 체크포인트 0을 판정에 넣지 않음 | open | |
| R1 | 1 | opus OQ2 | run.sh:269-275 | 기각 — 스모크 errors.log 'db_sampler ended' 0건(재연결 미발생), A11 수정 후 더 드묾 | — | |
| R2 | 1 | opus OQ6 | run.sh:229-231 | 기각 — recover_samples가 사본을 지우고 분석은 k6 구간으로 거른다(wall.py:86, gapcheck) | — | |
| Q1 | 1 | opus OQ5 | wall.py:13-17 | open question → 사용자 보고(고정 셀 벽 = 과도 뒤 벽 — PM 결정, A13 램프 도입 후 재판단) | — | |

## 생략한 검증

- (없음)

## 완료 요약
