# 작업 기록 — seat-reservation-lab ADR-018 2단계 (측정 · 결정)

> 명세: `requirement-spec.md` · 브랜치 `feat/adr-018-adjacent-seats`(1단계 162d92fe6 위) · stakes 높음 · MODE auto

## 타임라인 (시각 | 사건 | 결과)

| 시각 | 사건 | 결과 |
|------|------|------|
| 2026-11-20 00:1x | 사용자 "머지한다는건 어떤거야?" → 1단계 브랜치는 측정 · ADR 결정 전 머지하지 않음 정리 · "왜 164에서 하지않지?" → 앱 · DB는 .164, 1단계 Q7의 .9 · .158은 k6 위치였음을 설명 | 정리 |
| 2026-11-20 00:2x | 인터뷰 AskUserQuestion 4회(16문항) → 명세 초안 · 합의 질문에서 인터뷰 문항의 B1 설명 오류(B1을 '지금 방식'으로 적음 — 실제 지금 방식은 B0)를 정정해 다시 물음 → 사용자 "B0과 B1 둘 다" · "합의, auto로 진행" → 42회차(본 36 + 경계 6) · set-state spec-approved · mode auto | SPEC=1 · MODE=auto |
| 2026-11-20 00:2x | 설계 선검증 판단: 새 동작 불변식 = 지표 추가(관측만 — M18-6 '응답 · 상태 · 잠금 무변경')뿐 · 측정 불변식 M18-1~5는 하네스 판정 규칙(코드베이스 동작 불변식 아님) → 설계 선검증 생략, 기존 강제 위치: 응답 · 잠금 = 1단계 시험(adr018 49 · adr018impl 56) | 생략 기록 |
| 2026-11-20 00:4x | task 01 계약(test-contract.md): seat.group.request{op, outcome} · seat.group.hold.rejected{mode, cause=nowait · short · rule} + 헤더 group-<cause>(loadtest만 · 묶음 409는 seat.hold.rejected 안 올림) · seat.db.deadlock{path} · 파일 경계(adr018metrics blind ∥ adr018metricsimpl) · 구현 워커(Opus · 새) ∥ blind 워커(Opus · 새) 착수 · Gradle은 각자 복사본 | 착수 |
| 2026-11-20 00:5x | 사용자 "응 진행해" → task 02 하네스 설계(harness-design.md: 방식 B0 · B1 · split · one-shot · 겹침 = 인기 구역 크기 H(low 세션×3 · mid ×0.5 · high ×0.1 [초안]) · 인접 쌍 한 석 어긋남 · 세션 ok 70 · abandon 15 · release 15 [초안] · 묶음 = 결제 1건 · B0 · B1 = 좌석마다 결제 · 경계 셀 TTL 8 · 배치 1 · W 6 · θ 1 · M 1초 [초안] · 판정 · 무효 · 지표 대조 · 요약) → 하네스 워커(Opus · 새) 1차(스크립트 · selftest · dry-run) 착수 — task 01과 병렬(지표 이름은 계약으로 고정) · 하네스 시험은 selftest(같은 워커) + 듀얼 리뷰로 보강(별도 blind 워커 생략 — 하네스 자체가 판정 코드라 selftest 갈래 + 리뷰로 대신, 리뷰 때 판정 갈래 누락을 렌즈로 지정) | 착수 |
| 2026-11-20 01:1x | task 01 blind 회수(Opus 241,831 토큰): adr018metrics 4파일 21개 — 구현 작업 트리 21/21 · 기준 SHA로 main을 되돌린 판 19 실패(지표 차이 {} — 그린 위장 아님 확인) · 40P01은 DB 트리거 RAISE … ERRCODE '40P01'로 진짜 PSQLException 주입(경로별 40P01 · 40001 · P0001) · 계약 해석 8(INVALID_SEAT_SELECTION은 outcome=코드 · path release = 해제 API 2개 · 묶음 꺼도 1석 해제는 셈 · 사전 확인의 남의 HELD = rule 등) · 경계: 복사본 git status로 바뀐 main 파일 이름(HoldGroupMetrics.kt · DbDeadlock.kt 외 5)을 봄(내용은 안 봄) · 디스크 / 100%(3.1G) 보고 → 메인이 끝난 복사본 m01-blind 4.5G 삭제 → 7.5G · docker system df: 이미지 회수 가능 12.3G · 볼륨 5.4G(사용자 자원 — prune 금지, 사용자에게 알림) | 회수 |
| 2026-11-20 01:3x | 디스크 정리(사용자 결정 — '우리꺼 다 지우면'): ① 1단계 blind worktree(agent-a929… · 6.7G — 원 저장소가 정본, 파일 차이는 1단계 뒤 수정분) git worktree remove(브랜치 worktree-agent-a929…는 남김) ② 우리 스모크 이미지 4개(adr017smoke-app · pg · pay000smoke-app · mock-pg) docker rmi ③ 마지막 확인 뒤 닫힌 ADR-005 · 006 · 007 · 008 · 009 · 013의 gitignore된 k6-requests*.csv.gz · lb-access.log.gz + .pyc 1,136개 59.45G 삭제(git ignored 목록에서만 선택 · 되돌릴 수 없음 — 요약 · RESULT는 git에 있음) → / 가용 3.1G → 75G(84%) · 다른 프로젝트 이미지 · 볼륨 · 실행 중 컨테이너는 건드리지 않음 | 정리 |
| 2026-11-20 01:3x | task 01 구현 회수(Opus 185,257 토큰): HoldGroupMetrics(인터셉터 — seat.group.request) · DbDeadlock(sqlStateOf == 40P01) · ApiExceptionHandler(묶음 선점이면 seat.group.hold.rejected{mode, cause} + 헤더 group-<cause>, seat.hold.rejected 안 올림) · SeatReservationException.groupShort · HoldGroupService one-shot 행 수 ≠ N에 groupShort · ExpireHoldsService · LazyExpiryBatch 틱 롤백에 expiry_tick · 구현 시험 adr018metricsimpl 28 · 복사본 전체 main-app 1385 중 실패 6(Adr015StartupContractTest — 시험 postgres PANIC No space left on device · 디스크 부족) → 그 클래스 단독 12/12 | 전체 재실행 필요 |
| 2026-11-20 01:3x | task 02 하네스 1차 회수(Opus 477,286 토큰): k6/ADR-018/(INTERFACE · plan18 · flow18 · lib18 · run18 · campaign18 · outcome18 · summarize18 · selftest18 · actsnap18 · dump18 · hhash18 · compose.pay18 · local18) · selftest 603/603 · dry-run 42회차 예상 7.98h · 최악 10.84h · 넘긴 판단 14(B0 · B1은 hold-group 끔 · 좌석 s = 1 + ⌊U × H⌋ · 경합 쌍 · 막힌 세션 정의 · edge 셀 5xx ≤ deadlock 증분 아니면 무효 · 지표 대조를 op:outcome · mode:cause 단위로 · 경계 진입 0이면 무효 등) · 설계와 다른 것: 부분 선점은 k6 직후 스냅숏으로 · edge_ps 중심(E 직전 ±0.5초)은 θ 1초라 결제 시작이 전부 409 → 메인 결정 필요 · 로컬 스모크는 task 01 커밋 SHA 필요(git archive) | 회수 |
| 2026-11-20 02:0x | task 01 듀얼 리뷰 루프 1(OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/m01-rv1 · 미러 _packet spec의 개인 경로 가림 · 스캔 0): codex high F2 ∥ Opus(207,664 토큰) F1 low · open 5 → 채택 3(M1-1 · M1-2 · M1-3) · 계약 §1.3 문장 3곳(사슬 전체 · sweep 틱만 · 확정 거부 기록 40P01 포함) + cause 비교 지표 아님 주석 · 구현 워커 재개 | 수정 중 |
| 2026-11-20 02:3x | task 01 수정 회수(구현 워커 · 누적 197,775 토큰): M1-1 DbDeadlock.isDeadlock — 사슬 전체(순환 방지 seen) · M1-2 LazyExpiryBatch 기준 SHA로 원복 · M1-3 거부 기록 catch에 group_confirm 기록 · 시험(XX000 → 40P01 중첩 · confirm_rejection INSERT만 40P01 → 409 HOLD_EXPIRED 그대로 · group_confirm +1 · 이중 셈 없음) · 복사본 adr018metricsimpl 31 + adr018metrics 21 = 52/0 → 루프 2(codex high ∥ 새 Opus) 착수 · 원 저장소 전체 시험(수정 전 코드) 진행 중 | 리뷰 2 |
| 2026-11-20 02:3x | 하네스 보정 회수(하네스 워커 · 누적 491,310 토큰): 경계 셀만 θ = M = 0 · EDGE_PS_CENTER_S 0 ± 0.5(본 셀은 1초 그대로 · config-check PT0S) · 분류표는 이미 맞음(본문 code 우선 · release_hold 대조) — 단위 갈래 추가 · 경계 셀 요약에 deadlock_total · http_5xx_total · deadlock_not_5xx{path} · selftest 609/609 · dry-run 그대로 | 스모크 대기 |
| 2026-11-20 03:0x | task 01 리뷰 루프 2(OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/m01-rv2): codex high finding 0 · 렌즈 7 verified ∥ Opus finding 0 · 렌즈 8 verified(open 3 — 지원 안 하는 구성의 1석 해제 404를 센다(계약 문구대로) · 구현 시험은 지표를 바로 읽음(ADR-016 선례와 같음) · 메서드 이름 문자열 매핑 2곳(시험이 막음)) → 종료 조건 충족(open 채택 0 · 신규 채택 0 · 대칭: 양쪽 모두 렌즈 verified 기여) · 리뷰 마감 | 리뷰 마감 |
| 2026-11-20 03:1x | 원 저장소 전체(task 01 수정 전 코드 — 컴파일이 수정 도착 전 · 31분 25초): main-app 1406/0(건너뜀 24) · mock-pg 24/0 · reconciler 6/0 · 디스크 정리 뒤 Adr015 기동 실패 재발 없음 → 수정 코드로 최종 전체 실행 착수 | green(수정 전) |
| 2026-11-20 03:5x | 최종 전체(task 01 수정 코드 · 30분 4초): main-app 1409/0(건너뜀 24 · impl 31 · blind 21) · mock-pg 24/0 · reconciler 6/0 → task 01 코드 커밋 1e961a66e | task 01 완료 |

## 리뷰 ledger

| id | 루프 | 출처 | 위치 | 판정 | 상태 |
|----|------|------|------|------|------|
| M1-1 | 1 | codex | DbDeadlock.kt:16 · SqlState.kt:5 | 채택 — 사슬의 첫 SQLSTATE만 봐 안쪽 40P01을 놓침 | fixed(루프 1) |
| M1-2 | 1 | codex · opus | LazyExpiryBatch.kt:90-94 | 채택 — lazy 청크마다 expiry_tick(틱 지표와 1:1 아님 · 측정 구성에서 닿지 않음 · 시험 없음) → 줄 삭제 · 계약을 sweep 틱으로 | fixed(루프 1) |
| M1-3 | 1 | opus(open question O2) | HoldGroupConfirmService.kt:57-65 | 채택 — 거부 기록 트랜잭션의 40P01이 도메인 거부로 삼켜져 경계 셀이 못 셈 → group_confirm에 포함(응답 무변경) | fixed(루프 1) |
| M2-OQ | 2 | codex · opus | — | 필터 단계 실패 미계수(계약대로 · 재현 경로 없음) · 미지원 구성 1석 해제 404 계수(계약 문구대로) · 구현 시험 즉시 읽기(선례 같음) · 메서드 이름 매핑 2곳(시험이 막음) | open question(조치 없음) |
| M1-OQ | 1 | opus | — | O1 split 재확인 409 = rule · one-shot = short(계약대로 — cause는 비교 지표 아님 주석) · O3 묶음 선점 본문 결함 = INVALID_SEAT_SELECTION(하네스 분류표 확인 → 하네스 워커에 전달) · O4 1석 해제가 묶음 꺼도 release_hold(의도 — 해제 API는 ADR-018 것) · O5 1석 API 40P01 미관측(경계 셀은 묶음만 — 범위 맞음) · codex: 미러에 k6 없어 하네스 값 대조 못 함(하네스 리뷰에서) | open question |

## 생략한 검증

- (없음)
