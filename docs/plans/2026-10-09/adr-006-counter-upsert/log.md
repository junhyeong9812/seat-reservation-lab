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
| 10-09 (노트북 디스크) | 사용자 '용량 많이 잡아먹는 원본 다 삭제하고 있지? 1.1기가밖에 없다는데' → 확인: 노트북 468G 중 여유 1.9G(100%), 원인 1위 = 커밋 안 하고 로컬에 둔 요청별 원시 기록 k6-requests.csv.gz 32.2GB(ADR-001 3.0·002 7.8·003 13.0·005 8.5). 서버는 52G 여유 | 사용자 선택: 끝난 ADR(001~003)만 삭제 |
| 10-09 (삭제) | 삭제 직전 확인(repo 경로·브랜치, git 추적 파일 제외 — ADR-001 본측정 181·ADR-002 4는 커밋돼 있어 남김): **untracked 1,031개 20.8GB 삭제**(ADR-001 스모크 19 · ADR-002 364 · ADR-003 648 — 본측정분은 requests-sha256.txt 커밋됨, 스모크분은 목록 없음). 목록은 scratchpad/deleted-csv-manifest-20261009.txt. 노트북 여유 22G(96%), git status 삭제 0 | ADR-005 원시 기록은 유지 |
| 10-09 (하네스 수정 회수) | 워커 6건 수정(오프라인 검증 — dry-run 39행·재현·가짜 python3 실패 exit 2·hold_requests 가짜 actuator·스모크 재요약 값 동일·recompute 바이트 동일) → 커밋 e88ec7bf. 실서버 확인: S2 L4 none 스모크 ok, hold_requests 1000 = 획득 1000(URI 태그 가정 실증) | — |
| 10-09 02:24 | codex post-fix 재점검: R1~R8 해소, 신규 N1(S3 빌림 분자에 확정 섞임 → invalid-borrow-ratio 오판 가능) → S3 제외로 수정. 中 규정상 재점검 반복 없음 | 리뷰 종료 → 본측정 |
| 10-09 02:24 | **본측정 시작**: campaign.sh --sha 022eca41 --id 20261009-adr006-022eca41 — 유닛 seatlab-adr006(Restart=on-failure), 9조건 × 회차 우선 · 균형 순환 순서 | 약 9h 추정 · 노트북 여유 22G · linger 꺼짐(로그아웃 시 정지) |
| 10-09 11:25 | 본측정 39칸 전부 exit=0 → 재측정 1라운드 9회차(S3 전부, invalid-borrow-ratio) 시작 | 오판 — run.sh `[[ S3 ]] \|\| jq … && reasons+=`가 `(A\|\|B)&&C`라 S3에서 늘 사유가 붙음(N1 수정이 반대로 동작). 측정값 자체는 정상 |
| 10-09 11:49 | 사용자 선택 '중단 후 원본 복원' → 유닛 정지, advisory-try-s3a20 rep1(재측정 완료)·rep2(부분)를 `_aborted-remeasure-20261009/`로, `.retry-*` 원본을 rep1·rep2로 되돌림 | 삭제 없음 |
| 10-09 11:51 | run.sh if 블록으로 수정(fbaaf815) — S3·ratio 5 → 0건, S4·ratio 5 → 1건 확인. S3 9회차 status를 ok로 정정(status.orig·meta.status_corrected·CAMPAIGN.log 행 남김). 다른 사유 없었음 | 84회차 전부 ok |
| 10-09 11:55 | summarize·errsplit 9조건 + compare 실행 → COMPARISON.md | 오류 0 · 분석 착수 |
| 10-09 12:40 | 분석: counter-upsert S4 L4 엄격 한계 2,866(3/5)·포화점 4,143 vs 4,300. 초 단위로 보면 11단계(4,325/s)를 26~27초 p99 5~17ms로 버티다 10회 전부 같은 초에 붕괴(L2=L4 → CPU 무관, 누적 약 37만 건). L2에서는 none·advisory-try보다 높음(4,121 vs 3,674·3,533, CPU 150% vs 206%). I/O: WAL +21%·extends +44%·backend 쓰기 +27%. 원인 미확정(대기 이벤트 미수집) → s4_wall.py(0a87aadd)·결과 커밋(3cb27e2d, 원시 CSV 제외 .gitignore) | ADR-006 §5·§6(제안 A 조건부) 초안 → 문서 듀얼 1패스 |
| 10-09 13:10 | 결과 문서 中 듀얼 1패스(packet base 5ac1bdbc, OUT=scratchpad/rv6d, 미러 33MB·원시 CSV/로그 제외, 보안 스캔 오탐만): codex 4 + OQ 2 · Opus 11 + OQ 6 → 중복 병합 D1~D11 전부 채택. 메인 확인: 쿼터 행 DELETE 없음(UserLimitStrategies.kt decrementCounters), 체크포인트 완료 +429 vs none +458(rep2 db.log) | **권장 변경: A(조건부) → 지금 B + 진단 후 전환 규칙** |

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| R1 | 1 | codex·opus | HoldSeatService.kt:29 prepare 타이머 | 채택 — 준비 없는 방식도 기록(명세 §5) | fixed(hasPrepare) | 1 |
| R2 | 1 | opus | UserLimitStrategyTest upsert 경합 출발선 | 채택 | fixed(ready 래치 + 롤백 테스트) | 1 |
| R3 | 1 | codex | compare.py·recompute_s4.py S4 포함 판정 | 채택 — 복합 실패 혼입 | fixed | 1 |
| R4 | 1 | codex | summarize.py 요청 0 단계 | 채택 | fixed | 1 |
| R5 | 1 | opus | run.sh hold_requests 분모 | 채택 — 빌림 무음 왜곡 | fixed | 1 |
| R6 | 1 | opus | compare.py 재측정 순번 | 채택 | fixed | 1 |
| R7 | 1 | opus | campaign.sh order.txt 행 수 | 채택 — 0조건 성공 종료 | fixed | 1 |
| R8 | 1 | opus(OQ) | 순서 균형 | 채택 — none이 s24 첫 번째 0회 | fixed(균형 순환) | 1 |
| N1 | post-fix | codex | k6/ADR-006/scripts/run.sh:493 S3 빌림 범위 | 채택 | fixed | post-fix |
| D1 | doc-1 | codex F1·opus F1 | ADR-006 §5.3②·§6 '11단계 마지막 4초에만' | 채택 — 벽 지속 기간 관측 불가, 12단계 성공 2,605 < 2,803 | fixed | doc-1 |
| D2 | doc-1 | opus F2 | UserLimitStrategies.kt decrementCounters(DELETE 없음) | 채택 — 쿼터 행은 만료로 안 줄어듦, '최악 조건' 구도 수정 | fixed | doc-1 |
| D3 | doc-1 | opus F3 | db.log checkpoint 시각·checkpoint_write_time | 채택 — 체크포인트 진행 중·counter-upsert만 가속, 후보 추가 | fixed | doc-1 |
| D4 | doc-1 | codex F3·opus F4 | ADR-006 H4 | 채택 — 지표별 판정, 부분 채택 | fixed | doc-1 |
| D5 | doc-1 | codex F2·opus F5 | HoldSeatProcess.kt span(acquire 뒤·거절 미기록) | 채택 | fixed | doc-1 |
| D6 | doc-1 | opus F6 | ⑦ '5회 합' → 10회 합 | 채택 | fixed | doc-1 |
| D7 | doc-1 | opus F7 | rep2 초 기준 혼용 | 채택 | fixed | doc-1 |
| D8 | doc-1 | opus F8 | COMPARISON.md S3 I/O(WAL ×2.8) 누락 | 채택 — ⑥·§6 표 | fixed | doc-1 |
| D9 | doc-1 | opus F9 | §4.1 ADR-005 serializable 재계산 미보고 | 채택 | fixed | doc-1 |
| D10 | doc-1 | opus F10 | §6 권장 분기 없음 | 채택 — 전환 규칙 명시, 권장 B 우선 | fixed | doc-1 |
| D11 | doc-1 | opus F11·codex F4 | '누적 상태' 단정·§7 버퍼 이분법 | 채택 — 누적/시간 교락 명시, 진단 4축 | fixed | doc-1 |
| OQ | doc-1 | opus·codex | 초 단위 원시 CSV 미커밋(재현은 로컬 원본 필요) | spec §2 허용(sha256) — 기록만 | — | — |

## 생략한 검증

- (없음)

## 완료 요약
