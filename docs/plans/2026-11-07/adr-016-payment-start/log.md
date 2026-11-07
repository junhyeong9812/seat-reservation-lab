# log — ADR-016 2단계: ① payment-start · 결제 마감 시각 · 확정 거부 지표

> 명세: `requirement-spec.md` · 설계: `docs/adr/ADR-016-paid-seat-loss.md` 설계-9 · 1단계 log: `../adr-016-paid-seat-loss/log.md`

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 11-07 | 사용자 "응 구현 명세로 진행해줘" → 실코드 읽기(PaymentStart* · SeatConfirmProperties · HoldStrategyStartupCheck:205-300 · SeatHold/Reservation 컨트롤러 · ApiExceptionHandler · AtomicConfirmProcess:1-120 · Adr015RaceScenarios:215-245 · 옛 키 참조 전수 grep) | 설계-9.3 `M < θ`는 과강 — `M ≤ θ`로 충분(200 ⇒ 남은 > θ) · 기본 0 · 0 기동 불가 발견 |
| 11-07 | 인터뷰 4문: 범위 = 앱 코드 먼저 · θ=0 · M=0 · `M ≤ θ` · stakes 높음(루프 + blind) · auto | 명세 작성 · 합의 대기 |
| 11-07 | 사용자 "합의 · 진행하자" → set-state SPEC=1 · MODE=auto | task 01 착수 · blind 테스트 워커(task 05) 병렬 |
| 11-07 | blind 테스트 워커(task 05 · general-purpose · Opus · worktree 격리) 착수 | 진행 중 |
| 11-07 | task 01: θ 키 `payment-min-remaining` · `pay-by-margin` · 옛 키 Binder 거부 · θ/M 음수 · `M ≤ θ`(① · ④) · 규칙 4 새 키 · 테스트 3곳 키 이전 · ADR-015 하네스 4파일 키 맞춤 | selftest --campaign exit 0 |
| 11-07 | 가정 1 실증: `Adr016AssumptionProbeTest`(프로퍼티 · 환경 변수 SEAT_CONFIRM_EXTENDMINREMAINING 거부 · 새 키 통과) | 3/3 통과 |
| 11-07 | task 02 · 03 · 04 구현: `PaymentStartService.check`(readOnly) · `PaymentStartModes`(① · ④ 등록 · 키 없음 = 기본 conditional) · `PayBy` · 응답 필드 · `ConfirmOutcomeMetrics`(인터셉터 + ApiExceptionHandler 요청 속성) · `Adr015RaceScenarios` 404 계약을 current · ② · ③으로 | compileTestKotlin 통과 |
| 11-07 | 가정 2 실증: `Adr016ReadOnlyProbeTest`(TransactionTemplate readOnly 쓰기 → DataAccessException · 대조 1행) | 1/1 통과 |
| 11-07 | 기존 전체 테스트(`:main-app:test` · 37분 32초) | 1120 · 실패 0 · 오류 0 · 건너뜀 20 |
| 11-07 | blind 워커 회수(packet: 기준 5ae6c788 → bbc52374 · 5파일 1063줄 · compile exit 0 · 모호 10건 보고 · 미완: 5xx/커밋 실패 failed 칸 블랙박스 불가 · 옛 키 거부의 스키마 무변경 미단언 · 토큰 189,854) → 작업 트리로 가져와 실행 | adr016 9클래스 40건 첫 실행 전부 통과 |
| 11-07 | 그린 위장 점검(변이 2: ① insufficient 경계 `≤θ`→`<θ` · rejected 증가 제거) | 4건 실패로 잡힘(θ 경계 · N16-1 · N16-4 · N16-5) → 원복 cmp 일치 |
| 11-07 | 커밋 ea020de4(feat — 앱 · ADR-015 테스트 · 하네스) · a7b2aacb(test — adr016) | docs 미포함 |
| 11-07 | 로컬 기동 스모크(bootTestRun · loadtest 프로필 · 스크립트 scratchpad smoke16.sh): ① θ30s M10s — 201 payBy = e − 10s · 289초 · payment-start 200 extended=false · 403 · 404 · pay_start{ok,not_found,not_owned}=1 · rejected{HOLD_NOT_OWNED,HOLD_NOT_FOUND}=1 · failed{400}=1 / ④ — not_needed extended=false · extend{not_needed}=1 / current — payment-start 404(핸들러 없음) · 201 키 3개 · 확정 지표 동일 / 옛 키 — Flyway 앞 기동 거부(메시지에 새 키) | 4구성 기대대로 · 프로세스 · 컨테이너 정리 확인 |
| 11-07 | 리뷰 루프 1 packet: base e823b2de5 · 누적 diff 23파일 · untracked 0(k6/*/results/** · .claude/** 제외 추가) · OUT=`/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/rv16-I9TI` · 미러=`/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/mir16-wpvr` · 보안 스캔: 시크릿 패턴 0 · compose 랩 기본 DB 비밀번호 값 17 · 내부 IP 29 → 미러 · packet에서 redact 후 0 | codex 1차 실행은 내 스크립트 결함($RES 미설정 — 결과 경로 빈값)으로 실패 → 재실행 · Opus 워커 병렬 |
| 11-07 | 루프 1 회수: codex(findings 0 · OQ 2) · Opus(낮음 4 · OQ 4 · 토큰 221,348) → 메인 종합 → codex 종합 감사: OQ-2 재분류 후보 1 | OQ-2 유지(근거 ReservationController.kt:21 매핑 제약 없음 → 확정 경로 POST는 늘 핸들러 선택 · 405는 spec §1-5 'POST' 밖 · 컨테이너 파싱 400은 앱 미도달) + 테스트로 고정 |
| 11-07 | 루프 1 수정 7e3f046e0: RC16-1 compose 주석 · RC16-2/3 + OQ-2 fix verification `adr016fix/Adr016MetricsFixTest`(3) · RC16-4 assumeTrue | fix 3/3 · ADR-015 race 10클래스 · adr015fix 통과(①④ 클래스 skipped 1 = 의도) |
| 11-07 | 루프 2(packet 미러 mir16b · base e823b2de5 · 수정 diff 참고 첨부): codex findings 0 · OQ 1(:now 시점) · Opus 낮음 3 · OQ 3(토큰 221,078) | 신규 채택 3 → 수정 |
| 11-07 | 루프 2 수정 1c2d8d5b7: RC2-1 rejected = 표지 ∧ ex 없음 ∧ 상태 ∈ 403·404·409 · RC2-2 `ConfirmMode.paymentStartEnabled` 한 곳 · 컨트롤러 else → error · RC2-3 `Adr016StartupScopeFixTest` | adr016 + adr016fix 11클래스 전부 통과 · RC2-1 변이(옛 판정) → 실패(Accept text/html → 응답 500인데 옛 판정 rejected) — 실제 결함 확인 · 원복 cmp 일치 |
| 11-07 | 두 번째 전체 테스트(루프 2 코드 · 42분) | 1161 · 실패 0 · 오류 0 · 건너뜀 24(기존 20 + assumeTrue ①④ 4) |
| 11-07 | 루프 3(미러 mir16c · 수정 diff 참고): codex findings 0 · 렌즈 8 verified · Opus 낮음 2(L3-1 상태 집합 출처 둘 · L3-2 변수 이름) · OQ 3(토큰 208,907) | 신규 채택 2 → 수정 a271bb92 |
| 11-07 | L3 수정 뒤: 관련 21클래스 80건 통과(adr016* · adr015fix · Adr015ApiContractTests · api) · codex 타깃 재점검 신규 0 · ① 기동 스모크 재실행 = 처음과 같음 | **루프 3에 신규 채택이 있어 높음 종료 조건(신규 0) 미충족 · 최대 3루프 도달 → review unresolved(형식) — 미해소 open finding 0 · 사용자 판단 대기** |
| 11-07 | task 07: ADR-016 설계-9.3 `M ≤ θ` 정정 · '구현 (2단계)' 절 · 구현 검증 대장 IV-016-1 · 2(해소) | docs 커밋 |

## 리뷰 ledger

| ID | 출처 | 위치 | 처리 | 상태 |
|---|---|---|---|---|
| RC16-1 | L1 · opus R16-O1 | k6/ADR-015/compose.confirm.yml:8 | 채택(낮음) — θ '④만 의미' → ① · ④ 공통 | fixed L1 (7e3f046e0) |
| RC16-2 | L1 · opus R16-O2 | PaymentStartService.kt:81 | 채택(낮음 · 테스트) — ① ok 커밋 뒤 세기 무검증 | fixed L1 (Adr016MetricsFixTest) |
| RC16-3 | L1 · opus R16-O3 + codex OQ1 | ConfirmOutcomeMetrics.kt:36 | 채택(낮음 · 테스트) — 5xx · 커밋 실패 failed 무검증 | fixed L1 (Adr016MetricsFixTest) |
| RC16-4 | L1 · opus R16-O4 | Adr015RaceScenarios.kt:232 | 채택(낮음) — 무음 return → assumeTrue | fixed L1 (7e3f046e0) |
| RC16-OQ1 | L1 · opus OQ1 | HoldStrategyStartupCheck.kt:279-308 | open question — θ < TTL 검사 · 측정 전 고정 명세로 | user-deferred 후보(보고) |
| RC16-OQ2 | L1 · opus OQ2 · 감사 재분류 | ConfirmOutcomeMetrics.kt:30 | open question 유지 + 테스트 고정(holdId 'abc' → failed{400}) · 하네스 대조 정의는 다음 명세 | closed |
| RC16-OQ3 | L1 · opus OQ3 | ConfirmOutcomeMetrics.kt:36 | 기록 — 응답 커밋 뒤 예외(작은 JSON 1회) | closed |
| RC16-OQ4 | L1 · opus OQ4 | docs/implementation-verification.md | task 07 — [구현 검증] 해소 기록 | closed(task 07) |
| RC2-1 | L2 · opus | ConfirmOutcomeMetrics.kt:31-35 | 채택(낮음 → 실재 확인) — 거부 표지 뒤 본문 실패(500)를 rejected로 셈 | fixed L2 (1c2d8d5b7) |
| RC2-2 | L2 · opus | PayBy.kt:21 · PaymentStartController.kt:41 · :56 · HoldStrategyStartupCheck.kt:286 | 채택(낮음) — 모드 집합 3곳 · 무음 else | fixed L2 (1c2d8d5b7) |
| RC2-3 | L2 · opus | Adr016StartupContractTest.kt:183-185 | 채택(낮음 · 테스트) — ② · ③ M>θ 칸 없음 | fixed L2 (Adr016StartupScopeFixTest) |
| RC2-OQa | L2 · opus | HoldStrategyStartupCheck.kt:276 | 기록 — 옛 키 목록/맵 모양은 실용 밖 | closed |
| RC2-OQb | L2 · opus | Adr016AssumptionProbeTest.kt:38 | 의도 — 가정 실증용 · 메시지는 blind 테스트가 본다 | closed |
| RC2-OQc | L2 · opus + codex | PaymentStartService.kt:70 | 설계-9.1 판정 시각(연결 얻은 뒤 :now · δ) · ④ E1 · C1과 같은 규칙 | closed |
| L3-1 | L3 · opus | ConfirmOutcomeMetrics.kt:34,48 | 채택(낮음) — 거부 상태 집합 손 베낌 → 처리기가 표지에 상태도 남김 | fixed L3 (a271bb92 · 재점검 신규 0) |
| L3-2 | L3 · opus | PaymentStartService.kt:43,59 | 채택(낮음 · 네이밍) — Instant 변수 `extended` → `extendedExpiresAt` | fixed L3 (a271bb92) |
| L3-OQ1 | L3 · opus | Adr016MetricsFixTest RC2-1 | 기록 — 실측 500(변이 실행에서 확인) · 상태 고정은 Spring 버전 의존이라 두지 않음 | closed |
| L3-OQ2 | L3 · opus | docs/implementation-verification.md | task 07에서 IV-016-1 · 2 해소 등재 | closed |
| L3-OQ3 | L3 · opus | Adr016AssumptionProbeTest.kt:38-42 | 동의 — 메시지 계약은 blind 테스트 | closed |
| RC16-OQ5 | L1 · codex OQ2 | — | 기각 — 실행 검증은 리뷰 범위 밖(메인 실행 결과) | closed |

## 생략한 검증

- (없음)

## 완료 요약

- (미완)
