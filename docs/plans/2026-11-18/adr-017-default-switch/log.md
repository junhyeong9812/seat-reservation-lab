# 작업 기록 — ADR-017 보상 기본값 refund 교체

> 명세: `requirement-spec.md` · 브랜치 `feat/adr-017-measurement`(기준 36512c9ff) · stakes 높음 · MODE auto

## 타임라인 (시각 | 사건 | 결과)

| 시각 | 사건 | 결과 |
|------|------|------|
| 2026-11-18 11:5x | 사용자 R 채택 · 인터뷰(PG 없는 구성 = 명시 강제 · auto) · 명세 작성 | 합의 대기 |
| 2026-11-18 12:12 | 사용자 "응 합의, 진행해줘" → set-state spec-approved · mode auto | SPEC=1 · MODE=auto |
| 2026-11-18 12:12 | 설계 선검증 생략 근거: 새 동작 불변식 없음 — N2 기존 강제 위치 `CompensationScheduling.kt:86-89`(모드 ≠ none ⇒ base-url) · N1은 설정 값 대조 | 생략(근거 기록) |
| 2026-11-18 12:2x | task 01 구현: CompensationProperties 기본 REFUND · application.yml `seat.compensation.mode: refund`(+ 되돌림 · D · S 손잡이 주석) · checkConfig 거부 메시지에 none 명시 안내 · test 프로필 `src/test/resources/application-test.yml` mode none · task 02 blind 워커 병렬 착수(구현 파일 열람 금지) · 영향 조사: 기존 테스트 중 기본 none 전제 1곳(Adr017PaymentStartIntentTest.kt:209 — env 값 none이면 통과 · 단언 무변경 · 테스트 이름의 '기본 구성(mode none)'은 이제 'test 프로필 구성' 뜻) | 빌드 대기 |
| 2026-11-18 12:3x | task 02 회수(blind 워커 · Opus · 60,397 토큰 · 51초): Adr017DefaultModeContractTest ①~⑤ · 금지 파일 미열람 · 모호점 3(SeatHoldProperties 필수 인자 → 기존 시험 값 · yml 바인딩 · `=none` 표기) — 구현과 충돌 없음 | 대상 시험 5/5 |
| 2026-11-18 13:0x | 전체 테스트 `cleanTest test --continue` 26분 37초: main-app 1248/0(건너뜀 24 — 1243 + 새 5) · mock-pg 24/0 · reconciler 6/0 · 60분 넘은 XML 0 | 가정 1 실증(test 프로필 none 한 줄로 PG 없는 기동 전부 green) |
| 2026-11-18 13:1x | 로컬 스모크(PC · 임시 postgres:16 · redis:7 · bootJar): ① 키 없음 · PG 없음 → exit 1 · 거부 메시지 'PG 없이 띄우려면 seat.compensation.mode=none을 명시하라' ② `--seat.compensation.mode=none` → 기동 · `mode=NONE … worker=off` ③ PG 주소만(흉내 PG 18104) → 기동 · `mode=REFUND sweep=PT5S worker=PT1S` · ERROR 0 · 정리(프로세스 0 · 컨테이너 0) | 스모크 3/3 |
| 2026-11-18 13:3x | 리뷰 루프 1(codex medium 3 · Opus 워커 127,086 토큰 medium 3 · low 2 — 미러 scratchpad/rvds/mirror) 종합: 겹침 합쳐 DS1-1~6 · DS1-3(거부된 기동이 migrate 뒤)은 계획 밖 파일 → 사용자 결정(AskUserQuestion) 'Flyway 앞에서 검사' → 명세 Q/A 5 · N6 재합의 기록 | 루프 1 수정 착수 |
| 2026-11-18 13:5x | 루프 1 수정: HoldStrategyMigrationGate가 CompensationScheduling.checkConfig를 migrate 앞에서 부름(reconciler 프로필 제외) · 확정 모드 거부 메시지에 none 안내 · yml 주석(current + none · bootTestRun · migrate 앞 거부) · application-test.yml 주석 · 기존 테스트 라벨 · 주석 3곳(단언 무변경) · 새 Adr017DefaultModeBootTest(spring.config.location으로 test 프로필 우회 — ① 거부 + 스키마 무변경 ② REFUND 바인딩 ③ current 거부 + 스키마 무변경) · 대상 22/22 · red 확인: 게이트 호출을 빼면 ① · ③이 'flyway_schema_history 생김'으로 실패(DS1-3 실재 · 테스트가 잡음) → 원복 | 전체 테스트 재실행 |
| 2026-11-18 14:3x | 전체 테스트(루프 1 수정 뒤) 28분 32초: main-app 1251/0(건너뜀 24 — +3 기동 테스트) · mock-pg 24/0 · reconciler 6/0 · 40분 넘은 XML 0 · 리뷰 루프 2(codex medium 1 · Opus 134,464 토큰 medium 1 · low 4 — 코드 결함 0) → DS2-1~6 전부 채택 · ops runbook 2곳 · ADR §결정 문장 2 · README 기본값 · 테스트 주석 1 · 대상 테스트 6/6 | 루프 3 |
| 2026-11-18 15:0x | 리뷰 루프 3(codex low 1 · Opus 115,162 토큰 low 4 — high · medium 0): DS3-1 README 진행 · DS3-2 확정 모드 거부 메시지 4곳 none 안내(범위 밖 → NEXT) · DS3-3 reconciler 건너뜀 분기 무시험 → 기동 테스트 ④ 추가 · red 확인(조건 빼면 ④ 실패 · entityManagerFactory 생성 실패) · 원복 · DS3-4 README 결정표 · DS3-5 ADR 설계-5 주석 | post-fix 재점검 |
| 2026-11-18 15:3x | post-fix 재점검 1(codex): ④ 전제 단언 부족 · ADR 주석 중복(README 삽입 단언 실패 때 같은 실행의 ADR 블록이 먼저 적용 · 재실행으로 두 번 — 메인 실수) → 둘 다 수정 · post-fix 재점검 2(codex) 신규 finding 0 · 미러 삭제 예정 | 리뷰 마감 → 최종 전체 테스트 |
| 2026-11-18 16:0x | 최종 전체 테스트 25분 34초: main-app 1252/0(건너뜀 24) · mock-pg 24/0 · reconciler 6/0 · 40분 넘은 XML 0 · jar 스모크 재실행: ① 키 없음 → exit 1 · none 안내 · flyway_schema_history 없음(스키마 무변경) ② current + PG → exit 1 · none 안내 · 스키마 무변경 ③ PG만 → REFUND · worker PT1S · ERROR 0 · 정리(JVM 0 · 컨테이너 0) | 검증 완료 |
| 2026-11-18 16:1x | 코드 커밋 335552a04(main 3 · yml · 게이트 · 테스트 5 · ops 2) · ADR · NEXT의 @@CODE_SHA@@ 치환(0 남음 — DS2-6) | docs 커밋 |

## 리뷰 ledger

| id | 루프 | 출처 | 위치 | 판정 | 상태 |
|----|------|------|------|------|------|
| DS1-1 | 1 | opus | CompensationScheduling.kt:96 · application.yml:40 | 채택 — current 명시 경로가 기본 refund와 충돌(test 프로필 none이 가림) · 메시지 none 안내 · 주석 · 기동 테스트 ③ | fixed |
| DS1-2 | 1 | codex · opus | Adr017DefaultModeContractTest | 채택 — 실제 기동 바인딩 면 없음 → Adr017DefaultModeBootTest(test 프로필 우회) | fixed |
| DS1-3 | 1 | opus | CompensationScheduling.kt:50 | 채택(사용자 결정 — Flyway 앞 검사) — 거부된 기동이 migrate 뒤 · red 확인으로 실재 | fixed |
| DS1-4 | 1 | opus | Adr017PaymentStartIntentTest.kt:31 · 205-209 · Adr017PgStartupContractTest.kt:59 | 채택 — '기본 none' 서술 · 라벨(단언 무변경) | fixed |
| DS1-5 | 1 | codex · opus | application-test.yml · application.yml | 채택 — bootTestRun이 test 프로필로 none — 주석으로 명시 | fixed |
| DS1-6 | 1 | codex · opus | ADR-017 §결정 | 채택 — 채택 · 교체 · 옛 하네스 주의 · current + none | fixed |
| DS2-1 | 2 | codex | ADR-017 §결정 | 채택 — D 탈락 사유(규칙 = 지연)와 매입 위험(규칙 밖 고려) 구분 | fixed |
| DS2-2 | 2 | opus | ops/expiry-mode-cutover · ops/confirm-unique-v12 | 채택 — current · PG 없는 배포에 mode=none 안내(ADR-016 교체 때 같은 runbook 갱신 선례) | fixed |
| DS2-3 | 2 | opus | ADR-017 §결정 끝줄 | 채택 — '워커가 켜진다' 잔여 문장 삭제(기동 거부와 모순) | fixed |
| DS2-4 | 2 | opus | README.md:7 | 채택 — 기본값 요약에 refund | fixed |
| DS2-5 | 2 | opus | Adr017PaymentStartIntentTest.kt:206 | 채택 — 낡은 주석 | fixed |
| DS2-6 | 2 | opus | ADR-017 SHA 자리표시자 | 채택 — docs 커밋 전 치환 확인(0 남음) | fixed |
| DS3-1 | 3 | codex · opus | README.md:5 · 결정표 | 채택 — 진행 상황 · ADR-017 행 | fixed |
| DS3-2 | 3 | opus | HoldStrategyStartupCheck.kt:313 · 318 · 331 · 343 | 이월 — 확정 모드 거부 메시지의 current 안내에 none 덧붙임은 범위 밖(단계마다 다음 키 안내 · migrate 앞이라 안전) → NEXT | deferred |
| DS3-3 | 3 | opus | HoldStrategyStartupCheck.kt:63 | 채택 — reconciler 건너뜀 기동 테스트 ④ · red 확인 | fixed |
| DS3-4 | 3 | opus | ADR-017:221 | 채택 — 설계-5 '기본 앱' 서술 한정 | fixed |
| DS3-P1 | post-fix | codex | Adr017DefaultModeBootTest ④ | 채택 — reconciler 활성 · pgEnabled false · 스케줄러 빈 부재 단언 | fixed |
| DS3-P2 | post-fix | codex | ADR-017:221 | 채택 — 중복 주석(메인 실수) 제거 | fixed |

## 생략한 검증

- (없음)

## 완료 요약 (2026-11-18)

- 사용자 채택 R → 보상 기본값 none → refund. 코드 커밋 335552a04.
- 핵심 diff(실파일):
  - `CompensationProperties.kt`: `val mode: CompensationMode = CompensationMode.NONE,` → `val mode: CompensationMode = CompensationMode.REFUND,`
  - `application.yml`: (없음) → `compensation:\n    mode: refund`
  - `HoldStrategyStartupCheck.kt`(HoldStrategyMigrationGate): `if (RECONCILER_PROFILE !in environment.activeProfiles) CompensationScheduling.checkConfig(compensation, payment, confirm, properties)` — migrate 앞
  - `application-test.yml`(신규): `seat.compensation.mode: none`
- 검증: 전체 1252/0/24 · 새 테스트 9(계약 5 · 기동 4 — red 확인 2회) · jar 스모크 3 · 듀얼 리뷰 3루프 + post-fix 재점검 2(마지막 신규 0) · 이월 1(DS3-2 → NEXT).
- 남은 것: push · PR(사용자 확인).
- CS 이슈 아카이브: 0건(설정 기본값 교체 — 새 CS 이슈 없음).
