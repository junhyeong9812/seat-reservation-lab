# 작업 기록 — seat-reservation-lab ADR-018 묶음 API 기본값 교체

> 명세: `requirement-spec.md`(합의 2026-11-21 · SPEC=1 · auto) · 브랜치 `feat/adr-018-default-switch`(origin/main 960204b65 — #18 병합 위)

## 타임라인 (시각 | 사건 | 결과)

| 시각 | 사건 | 결과 |
|------|------|------|
| 2026-11-21 15:1x | 사용자 "merge 커밋으로 병합하고 기본값 교체 작업 시작하자" → PR #18 merge 커밋 960204b65 · 브랜치 feat/adr-018-default-switch(origin/main) | 병합 · 착수 |
| 2026-11-21 15:1x | 사전 조사: `HoldGroupProperties.enabled = false` · application.yml `enabled: false` · `HoldGroupController` @ConditionalOnProperty(true) · 기동 검사(conditional-extend · 보상 none/refund · V16 표) · 테스트 중 confirm.mode=current 지정 33파일 · flyway.target 지정 8파일 · hold-group.enabled 지정 5파일 · application-test.yml은 보상 none 한 줄 · ADR-018은 병합돼 닫힘(수정 금지) | 인터뷰 준비 |
| 2026-11-21 15:2x | 인터뷰 AskUserQuestion(4문항): 명시 강제 · 테스트 프로필 false · 기록 = README · NEXT · 작업 폴더(ADR-018 닫힘) · 높음 · auto · PR까지 → 명세 작성 · 메인 제안 V16 계획 검사(Flyway 앞) 포함 합의 "합의, auto로 진행" · set-state spec-approved · mode auto | SPEC=1 · MODE=auto |
| 2026-11-21 15:2x | 설계 선검증 판단: 새 동작 불변식 = D3(V16 계획 · 표 검사 Flyway 앞) — 선례 `HoldStrategyStartupCheck.checkPaymentIntentPlan` · `checkPaymentIntentTable`(V15)과 같은 꼴이라 선검증 대신 blind 테스트 7번으로 확인 · D1 · D2 기존 강제 위치 `HoldGroupStartupCheck.checkConfig` · `HoldStrategyMigrationGate` | 생략 기록 |
| 2026-11-21 15:3x | task 02 blind 워커(Opus · 새 · worktree 기준 960204b65 · 쓰기 adr018default/만 · 원 저장소는 명세만) 착수 ∥ task 01 메인 구현: HoldGroupProperties enabled = true · application.yml true · checkConfig 메시지에 DISABLE_HINT · checkPlan(reachesV16) · checkTable(관문 · run 공용) · 관문에 V16 계획 · 표 검사(reconciler 제외) · application-test.yml enabled: false → 전체 cleanTest test(가정 1) 착수 | 시험 중 |
| 2026-11-21 16:1x | 전체 cleanTest test(36분 · blind 테스트 복사 전 컴파일): main-app 1409/0(건너뜀 24) · mock-pg 24/0 · reconciler 6/0 → **가정 1 실증**(테스트 프로필 한 줄로 기존 테스트 전부 지금 구성) | green |
| 2026-11-21 16:2x | task 02 blind 회수(Opus 99,821 토큰 · worktree agent-a30be… 기준 960204b65): adr018default 2파일 8건(Contract 2 · Boot 6) · 기준 SHA 7 실패('기본 false라서' — 컴파일 · 환경 아님) · 6 통과 · 테스트 프로필 회피 = spring.config.location=classpath:/application.yml(Adr017DefaultModeBootTest 방식) · 3 · 6은 보상 none 명시(ADR-017 거부 회피) · 7은 target 15 · history 0행 · public 표 무변경 → 원 저장소 복사 · 구현 코드에서 8/8 통과 | 통과 |
| 2026-11-21 16:3x | 로컬 스모크(앱 jar · postgres:16 임시 컨테이너 25437): ② 키 없음 + current + none → exit 1 · 'seat.hold-group.enabled=true는 seat.confirm.mode=conditional-extend에서만 … · 묶음 없이 띄우려면 seat.hold-group.enabled=false' · 거부 뒤 public 표 0(스키마 무변경) ③ + enabled=false → Started 23.3초 · hold-groups 404(꺼짐) ① 키 없음 + PG 주소 → Started 22.1초 · 묶음 선점 201(hold 2 · 좌석 HELD 2) · 정리(앱 PID 종료 · 컨테이너 삭제 — pkill -f가 자기 셸을 맞혀 한 번 끊김, PID로 정리) | 스모크 통과 |
| 2026-11-21 16:4x | 코드 커밋 4a7a9cf24 · 리뷰 루프 1 packet(OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/ds-rv1 · base 960204b65 · README 포함 · 미러 main-app · reconcile-core · reconciler-app · mock-pg · docs/adr · README · 스캔 오탐만) → codex high ∥ Opus 착수 | 리뷰 1 |
| 2026-11-21 17:0x | 리뷰 루프 1 회수: codex high F2(중간 2) ∥ Opus(142,895 토큰) F3(중간 1 · 낮음 2) + OQ 4 → 메인 종합(R1-1~5) → codex 종합 감사(medium): 'A F1(R1-5) 후보는 오류 — §5-3 · D2는 첫 거부 메시지에 안내 요구, 기존 단언은 포함 검사라 함께 넣으면 D4와 충돌 없음' → R1-5 채택으로 올림 | 수정 중 |
| 2026-11-21 17:1x | 수정: R1-1 HoldGroupController matchIfMissing = true(수정 검증 ① — hold-group 블록 뺀 yml 기동 · 묶음 선점 2xx · 수정 전 코드에서 404로 실패 확인) · R1-2 수정 검증 ②(V16 이력 + hold_group DROP → '마이그레이션 앞 DB' + 안내 · 이력 행 수 그대로) · R1-3 주석 2곳 · R1-4 README 진행 상황 · 질문 표 · 옛 하네스 안내 · R1-5 관문 순서(묶음 검사 → 보상 검사) + 결제 시작 없는 확정 모드 + 보상 ≠ none이면 묶음 메시지에 보상 none 안내 함께(수정 검증 ③ — 한 메시지에 두 안내 · 스키마 무변경) → 전체 cleanTest test | 시험 중 |
| 2026-11-21 17:5x | 전체 cleanTest test(37분 42초): main-app 1420/0(건너뜀 24 · 1409 + blind 8 + 수정 검증 3) · mock-pg 24/0 · reconciler 6/0 → 수정 커밋 8f4acf614 · 리뷰 루프 2 packet(OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/ds-rv2 · 누적 diff base 960204b65 + 루프 1 수정 diff 참고 · 스캔 오탐 1(ADR-007 'disk-measurement')) → codex high ∥ Opus(새) 착수 | 리뷰 2 |
| 2026-11-21 18:3x | 리뷰 루프 2 회수: codex high F2(중간 2) ∥ Opus(161,227 토큰) F3(낮음 3) + OQ 4 → **정지 규칙**: '키 없이 띄운 기동의 첫 거부에 enabled=false 안내 없음'이 경로만 바꿔 네 번째(R1-5 보상 먼저 → C2-F1 target 14 V15 먼저 · O2-F1 yml 되돌림 주석 · O2-F2 Flyway 꺼짐 @PostConstruct 순서) — 검사마다 자기 키만 안내하는 구조가 원인 → 사용자 결정(AskUserQuestion) '묶음 검사를 맨 앞에' | 재설계 |
| 2026-11-21 18:4x | 수정: 관문 맨 앞에 묶음 checkConfig + checkPlan(V16 target — reaches를 위로) · 그 뒤 ADR-015 checkConfig · 보상 · V12/V13/V15 · checkTable(pending 아닐 때 — 제자리) · yml confirm 주석에 'current로 띄울 때 seat.hold-group.enabled: false도' · 수정 검증 ④(키 없음 + target 14 → 한 메시지에 target · 끄기 안내 · 스키마 무변경) · Flyway 꺼짐 경로의 두 번 거부는 수용(아래 C2-OQ) → 전체 cleanTest test | 시험 중 |
| 2026-11-21 19:2x | 전체 cleanTest test(34분 7초): main-app 1421/0(건너뜀 24) · mock-pg 24/0 · reconciler 6/0 → 수정 커밋 6a7635f9d · 리뷰 루프 3(상한) packet(OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/ds-rv3 · 스캔 오탐 1 같음) → codex high ∥ Opus(새) 착수 | 리뷰 3 |
| 2026-11-21 20:0x | 리뷰 루프 3(상한) 회수: codex high 코드 finding 0(NEXT 기록 1 — C2-F2와 같아 기각) ∥ Opus(168,653 토큰) F2(중간 1 · 낮음 1) + OQ 4 → 채택 2(O3-F1 · O3-F2) · 종료 조건 불충족(신규 채택 2) → review unresolved · 수정(테스트 1 · README 1구절 — 코드 무변경) · adr018default 13 + Adr017DefaultModeBootTest 4 통과 · post-fix 재점검(codex medium — OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/ds-pf) 'clean' · 커밋 11f38fb82 | 리뷰 상한 · 병합 판단 사용자 |
| 2026-11-21 20:1x | 최종 코드(6a7635f9d · 11f38fb82는 시험만) 로컬 스모크 재실행(postgres:16 임시 25438): ② 키 없음 + current(보상 기본 refund) → exit 1 · 한 메시지에 '묶음 없이 띄우려면 seat.hold-group.enabled=false · 결제 시작이 없는 확정 모드는 seat.compensation.mode=none도' · 표 0 ③ current + none + enabled=false → Started 17.9초 · hold-groups 404 ① 키 없음 + PG 주소 → Started 18.0초 · 묶음 선점 201 · 정리(앱 0 · 컨테이너 삭제) | 스모크 통과 |
| 2026-11-21 20:2x | docs: README(진행 상황 · 질문 표 · 적용 방식 행 · ADR 표 · 옛 하네스 안내) · NEXT N00d(기본값 교체 · 되돌림 · 옛 하네스 · 측정 기준선 변경 · 이월 B) · 측정 로그 1행 · 이 완료 요약 | 문서 |

## 리뷰 ledger

| id | 루프 | 출처 | 위치 | 판정 | 상태 |
|----|------|------|------|------|------|
| R1-1 | 1 | opus | HoldGroupController.kt:34 | 채택 — matchIfMissing 없음 → 설정 위치를 대체해 키가 없으면 검사 · Guard는 켬, API 404(§1 · D1) | fixed(루프 1) |
| R1-2 | 1 | opus | HoldStrategyStartupCheck.kt:97-99 | 채택(부분) — migrate 앞 checkTable 분기 미실행 → 수정 검증 테스트 ②. Flyway 꺼짐 run 경로는 이번 diff 전부터 있던 검사라 추가 안 함 | fixed(루프 1) |
| R1-3 | 1 | opus | HoldGroupDisabledTest.kt:24 · application-test.yml:3 | 채택 — 낡은 주석(기본 false · bootTestRun) | fixed(루프 1) |
| R1-4 | 1 | codex · opus(OQ1) | README.md:6 · 13 · 47 | 채택 — ADR-018 '예정' 표시 · 옛 하네스 안내 없음(D6) | fixed(루프 1) |
| R1-5 | 1 | codex · 감사 | HoldStrategyStartupCheck.kt:66 | 채택(감사로 후보 → 채택) — 키 없음 + current + 기본 refund면 보상 검사가 먼저 거부해 첫 메시지에 enabled=false 안내 없음(§5-3 · D2) → 묶음 검사 먼저 + 보상 none 안내 함께 | fixed(루프 1) |
| C2-F1 · O2-F1 · O2-F2 | 2 | codex · opus | HoldStrategyStartupCheck.kt:90 · application.yml:43 · CompensationScheduling.kt:99 | 채택(재설계 — R1-5와 같은 결함 유형 · 정지 규칙 → 사용자 결정 '묶음 검사를 맨 앞에') — 관문 맨 앞 묶음 설정 · 계획 검사 + yml 주석. Flyway 꺼짐 @PostConstruct 순서는 정하지 않음(수용 — 두 번째 기동에서 안내) | fixed(루프 2) |
| C2-F2 | 2 | codex | packet-diff | 기각 — NEXT · 작업 기록은 리뷰 packet에서 일부러 뺌(review.md ⓪) · docs 커밋에서 갱신 | — |
| O2-F3 | 2 | opus | Adr017DefaultModeBootTest.kt:56-61 | 채택(기록만) — ③의 거부 출처가 묶음 검사로 바뀜(단언 그대로 · D4). ADR-017 보상 검사의 current 거부는 Adr017PgStartupContractTest.kt:67(test 프로필)이 계속 본다 | 기록 |
| O3-F1 | 3 | opus | Adr017DefaultModeBootTest.kt:55-61 | 채택 — 관문 순서 변경 뒤 ③이 묶음 검사로 녹색(그린 위장) · 운영 기본 구성의 ADR-017 current + refund 거부 경로 미실행(O2-F3 기록의 'test 프로필 테스트가 본다'는 이 경로를 대신하지 못함) → 수정 검증 ⑤(묶음 끔 + current + 기본 refund · ADR-017 고유 문구 · 스키마 무변경) | fixed(루프 3 뒤 — post-fix clean) |
| O3-F2 | 3 | opus | README.md:50 | 채택 — 옛 하네스 안내가 current만(auth-capture · substitute · target < 16 빠짐 · D6) | fixed(루프 3 뒤) |
| C3-F1 · O3-OQ | 3 | codex · opus | — | NEXT 기록(C2-F2와 같음 — 기각) · 관문 순서 변경 범위(사용자 결정 루프 2) · checkPlan 보수 거부 · enabled=on(후보 B) | 정리 |
| C2-OQ · O2-OQ | 2 | codex · opus | — | target=current · V16 적용 DB + target<16 거부 = 선례(checkPaymentIntentPlan)와 같음(의도) · enabled=on/yes/1은 바인딩 참이나 @ConditionalOnProperty havingValue=true와 불일치 — 이번 변경 전부터 있던 동작 → 후보(B) · 실행 증거는 log에 | open question(정리) |
| R1-OQ | 1 | opus | — | 1석 Guard 질의 추가 = 측정 기준선 조건 변경 → NEXT에 · target ≤ 12 다단 거부(각 단계 안내 있음 — 의도) · V16 적용 DB + target<16 거부(선례 checkPaymentIntentPlan과 같음 — 의도) | open question(정리) |

## 생략한 검증

- (없음)

## 완료 요약 (2026-11-21)

- 범위: `seat.hold-group.enabled` 기본 false → true(코드 · yml · 컨트롤러 matchIfMissing) · 맞지 않는 구성(확정 ≠ conditional-extend · 보상 auth-capture · substitute · V16 앞)은 거부하고 `seat.hold-group.enabled=false` 안내 · Flyway 앞 관문 맨 앞에서 묶음 설정 · V16 계획 검사(거부된 기동은 스키마 무변경) · V16 표 검사(이력 있음 · 표 없음)도 migrate 앞 · test 프로필 false · README · NEXT.
- 커밋(code): 4a7a9cf24(기본값 · 검사 · blind 8) · 8f4acf614(matchIfMissing · 순서 · 수정 검증 3) · 6a7635f9d(관문 맨 앞 · yml 주석 · 수정 검증 1) · 11f38fb82(ADR-017 경로 시험) / docs는 따로.
- 검증: blind 계약 8(기준 SHA 7 실패 · 구현 8 통과) · 수정 검증 5(① 수정 전 코드 404로 실패 확인) · 전체 cleanTest test 3회(마지막 main-app 1421/0/24 · mock-pg 24/0 · reconciler 6/0 — 11f38fb82는 시험 1개 추가 뒤 adr018default · Adr017DefaultModeBootTest 17/0) · 로컬 스모크 3종 2회 · 듀얼 리뷰 3루프(상한) + 종합 감사 + post-fix clean · 정지 규칙 1회(첫 거부 안내 결함 4번째 → 사용자 결정 '묶음 검사를 맨 앞에').
- 리뷰 상태: 루프 3에서 신규 채택 2(테스트 · README — 코드 무변경)를 고치고 post-fix clean. 종료 조건(신규 채택 0)은 루프 3에서 충족하지 못해 **review unresolved** — 병합 판단은 사용자.
- 남은 것(이월 B): Flyway 꺼짐 구성의 검사 순서 · ADR-015 규칙 1 거부의 안내 · enabled=on/yes/1 조건 불일치(기존부터).
- CS 이슈 아카이브: study-note 쓰기 금지 → 보류(후보: '기본값을 바꿀 때 기동 검사가 여럿이면 먼저 걸린 검사의 안내만 나간다 — 바뀐 기본값의 검사를 관문 맨 앞에').

핵심 diff (실파일 복사):

```kotlin
// main-app/.../service/HoldGroupProperties.kt — before(960204b65) → after
    val enabled: Boolean = false,
    val enabled: Boolean = true,
```

```kotlin
// main-app/.../api/HoldGroupController.kt — before → after
@ConditionalOnProperty(name = ["seat.hold-group.enabled"], havingValue = "true")
@ConditionalOnProperty(name = ["seat.hold-group.enabled"], havingValue = "true", matchIfMissing = true)
```

```kotlin
// main-app/.../service/impl/hold/HoldStrategyStartupCheck.kt — 관문 맨 앞(after)
        if (RECONCILER_PROFILE !in environment.activeProfiles) {
            HoldGroupStartupCheck.checkConfig(holdGroup, confirm, compensation)
            HoldGroupStartupCheck.checkPlan(holdGroup, reachesV16 = reaches(HoldGroupStartupCheck.HOLD_GROUP_VERSION))
        }
```

