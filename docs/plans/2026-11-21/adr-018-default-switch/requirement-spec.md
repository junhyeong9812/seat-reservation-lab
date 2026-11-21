# 요구사항 명세서 — ADR-018 묶음 API 기본값 교체(`seat.hold-group.enabled` 기본 true)

> 작성일: 2026-11-21 · 작업 폴더: `docs/plans/2026-11-21/adr-018-default-switch/` · 브랜치 `feat/adr-018-default-switch`(origin/main 960204b65 — #18 병합 위)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문(2026-11-21): "merge 커밋으로 병합하고 기본값 교체 작업 시작하자" — ADR-018 §결정 후속 "기본값 교체(`seat.hold-group.enabled` 기본 true)는 별도 작업으로 한다. 설정 · 기동 검사 · 시험 명시화 · 운영 문서를 ADR-016 · 017의 기본값 교체와 같은 방식으로 바꾼다."
- Q/A (2026-11-21 · AskUserQuestion 1회 · 4문항):
  1. 맞지 않는 구성(확정 모드 ≠ conditional-extend · 보상 auth-capture · substitute · V16 앞 스키마) = **명시 강제** — 기동 거부 + 메시지에 `seat.hold-group.enabled=false` 안내(ADR-015 current · ADR-017 refund와 같은 방식 · 자동으로 꺼지지 않음).
  2. 기존 테스트 = **테스트 프로필에서 끔** — `application-test.yml`에 `seat.hold-group.enabled: false` 한 줄(ADR-017 보상 none과 같은 방식). 켠 경로는 adr018 · adr018impl · adr018metrics · adr018metricsimpl과 새 기본값 계약 테스트가 본다.
  3. 기록 위치 = **README · NEXT · 이 작업 폴더** — ADR-018은 #18로 병합돼 닫혔으므로 본문을 고치지 않는다.
  4. stakes · 자율성 · 발행 = **높음 · auto · PR까지**(결과 보고 뒤 push · PR 생성까지 허가 · 병합은 따로 묻는다).
- 재합의(2026-11-21 · 리뷰 3루프 뒤 · AskUserQuestion 2회):
  5. 루프 2 정지 규칙 → **묶음 검사를 관문 맨 앞에**(묶음 설정 · V16 계획 검사를 Flyway 앞 관문의 다른 검사보다 먼저).
  6. PR #19 병합 전 남은 계약 결함 3개를 **이 PR에서 수정**(사용자: "다 수정해야되는 이슈들이잖아 이건 동작이나 계약에 문제가 있다는거 아니야?"):
     - ① Flyway를 끈 구성: 묶음 기동 검사(@PostConstruct)가 보상 기동 검사(@PostConstruct)보다 먼저 돈다(빈 순서를 정함) — 계획 밖 파일 `CompensationScheduling.kt`.
     - ② ADR-015 확정 모드 거부 메시지의 'seat.confirm.mode=current로 띄워라'와 ADR-017 보상 거부의 'current 등 … none도'에 `seat.hold-group.enabled=false`도 함께 안내 — 계획 밖 파일 `HoldStrategyStartupCheck.kt`(메시지) · `CompensationScheduling.kt`(메시지).
     - ③ 묶음 API 컨트롤러 등록 조건을 설정 바인딩과 같은 규칙으로(Boolean 바인딩 — on · yes · 1 포함 · 키 없으면 true).
     - 검증: 수정마다 재현 테스트(수정 전 실패 확인) · 전체 테스트 · 스모크 · 리뷰는 고친 부분만 재점검.
- 메인 제안(합의 때 확인): V16 계획 검사를 Flyway 앞에 둔다 — 기본값이 켬이 되면 `spring.flyway.target` < 16 구성이 V16 앞까지 마이그레이션한 뒤 거부된다(지금 검사는 마이그레이션 뒤 `run`). ADR-015 · 017의 '거부된 기동은 스키마를 바꾸지 않는다' 관례(`checkPaymentIntentPlan` 선례)를 따른다.

---

## 1. 목표·대상 (필수)

seat-reservation-lab `main-app`에서 `seat.hold-group.enabled`의 기본값이 `false`에서 `true`가 된다. 코드 기본값(`HoldGroupProperties`)과 `application.yml`이 같은 값이고, 키를 주지 않은 앱은 묶음 API 4개(선점 · 해제 · 결제 시작 · 확정)를 split 방식으로 연다. 맞지 않는 구성을 키 없이 띄우면 `seat.hold-group.enabled=false` 명시를 안내하며 기동을 거부한다. `seat.hold-group.mode` 기본값은 split 그대로다.

## 2. 경계·불변식 (필수)

- D1 코드 기본값 = `application.yml` 값 = true · mode 기본 = SPLIT(한쪽만 바뀌지 않는다 — Adr016 · 017 DefaultModeContractTest와 같은 대조).
- D2 명시 강제: 켠 상태(기본 포함)에서 확정 모드 ≠ conditional-extend · 보상 모드 ∉ {none, refund} → 기동 거부(기존 강제 위치 `HoldGroupStartupCheck.checkConfig`). 자동으로 꺼지지 않는다. 거부 메시지에 '묶음 없이 띄우려면 `seat.hold-group.enabled=false`'를 넣는다.
- D3 V16 앞 스키마(`spring.flyway.target` < 16 · V16 위치 밖)에서 켠 기동은 Flyway migrate 앞에서 거부한다 — 거부된 기동은 스키마를 바꾸지 않는다. 이미 적용된 DB에 표가 없는 경우(Flyway 꺼짐 등)는 지금처럼 `run`이 거부한다.
- D4 기존 테스트의 단언은 바꾸지 않는다 — 테스트 프로필의 `enabled: false` 한 줄로 지금과 같은 구성에서 돈다. 이미 `seat.hold-group.enabled`를 명시한 테스트(5파일)는 그대로.
- D5 묶음 · 1석 경로의 동작(잠금 · 상태 전이 · 응답)은 바꾸지 않는다 — 설정 기본값 · 거부 메시지 · V16 계획 검사만 바뀐다. 1석 결제 시작 · 확정의 HOLD_IN_GROUP 검사가 기본으로 돈다는 것은 설계-8대로의 결과다.
- D7 (재합의 6) 키 없이 띄운 기동이 맞지 않는 구성으로 거부될 때, 어느 기동 경로(Flyway 켬 · 끔)든 첫 거부 메시지에 `seat.hold-group.enabled=false` 안내가 있다. current를 안내하는 기존 거부 메시지도 묶음 끄기를 함께 안내한다.
- D8 (재합의 6) 묶음 API 컨트롤러가 등록되는 조건 = `HoldGroupProperties.enabled` 바인딩 값(같은 변환 규칙 · 같은 기본값). 둘이 갈리는 값이 없다.
- D6 `reconciler` 프로필은 영향 밖(기동 검사가 보지 않는다). 측정 이미지(옛 SHA 태그)를 쓰는 옛 하네스는 영향 밖 — 새 이미지로 확정 모드 current인 옛 하네스를 돌리면 거부되므로 README · NEXT에 적는다.

## 3. 기준소스 (필수)

- ADR-018 §결정 · 설계-7(켜는 조건) · 설계-8 — `docs/adr/ADR-018-adjacent-seats.md`(닫힘 — 읽기만).
- 선례: ADR-017 기본값 교체(`docs/plans/2026-11-18/adr-017-default-switch/` · `Adr017DefaultModeContractTest` · `Adr017DefaultModeBootTest` · Flyway 앞 검사) · ADR-016 기본값 교체(`Adr016DefaultModeContractTest`).
- 코드: `HoldGroupProperties.kt` · `HoldGroupStartupCheck.kt` · `HoldStrategyStartupCheck.kt`(`HoldStrategyMigrationGate`) · `main-app/src/main/resources/application.yml` · `main-app/src/test/resources/application-test.yml` · `config/application.properties`(키를 더하지 않는다 — Adr012 전제).

## 4. 금지영역 (필수)

- 닫힌 ADR(ADR-018 포함) · V1~V16 마이그레이션 · mock-pg · reconciler · k6 하네스(k6/**) · 측정 결과 폴더.
- 재합의 6으로 더한 범위: `CompensationScheduling.kt`(빈 순서 · 거부 메시지 안내) · `HoldStrategyStartupCheck.kt`의 current 안내 메시지 · 컨트롤러 등록 조건. 판정 조건 · 응답 · 상태 전이는 바꾸지 않는다.
- 묶음 · 1석 동작 코드(HoldGroupService · 결제 시작 · 확정 · 해제 · Guard 판정) — 설정 기본값 · 기동 검사 메시지 · V16 계획 검사 밖은 바꾸지 않는다.
- `config/application.properties`에 키를 더하지 않는다.
- 기존 테스트 단언 변경 금지(필요해지면 멈추고 보고).
- 병합은 사용자 확인 뒤. docs 커밋과 code 커밋 분리. AI 표기 금지.
- .164 · .9 · .158 상주 컨테이너 · PC NIC · `docker system prune` · sudo · study-note.

## 5. 검증 방법 (필수)

- 새 계약 테스트(blind 워커가 이 명세만 보고 작성 — 구현 diff 미열람):
  1. 코드 기본 enabled = true · mode = SPLIT
  2. `application.yml` 바인딩 true · split
  3. 키 없음 + 확정 모드 current → 거부 · 메시지에 `seat.hold-group.enabled=false`
  4. 키 없음 + 보상 auth-capture 또는 substitute → 거부 · 같은 안내
  5. 키 없음 + 기본 구성(conditional-extend · refund + PG 주소) → 통과 · 묶음 선점 엔드포인트가 열림
  6. `enabled=false` + current → 통과
  7. 키 없음 + `spring.flyway.target` < 16 → 거부 · `flyway_schema_history`가 비거나 그대로(스키마 무변경)
- 전체 테스트 `./gradlew cleanTest test`(실행 확인 — up-to-date 무실행 금지): 기존 main-app 1409/0/24 + 새 테스트, 실패 0 · mock-pg 24/0 · reconciler 6/0.
- 스모크(로컬 앱 jar · postgres 컨테이너): ① 키 없이 PG 주소 주고 기동 → 묶음 선점 201 ② 키 없이 `--seat.confirm.mode=current --seat.compensation.mode=none` → 거부 메시지 확인 ③ ②에 `--seat.hold-group.enabled=false` → 기동.
- diff self-review · 듀얼 리뷰 루프(≤ 3) · 반증 질문 1회.

## 6. stakes (필수)

- 판정: **높음**(사용자 지정과 같음) — 묶음 API 4개가 기본으로 열리고(public API 계약), 기동 계약(맞지 않는 구성의 기본 기동 거부)이 바뀐다. 변경 크기는 작다.
- 설계 선검증: 새 동작 불변식 = D3(V16 계획 검사 — 기존 `checkPaymentIntentPlan` 패턴의 V16판). D1 · D2는 기존 강제 위치(`HoldGroupStartupCheck.checkConfig` · `HoldStrategyMigrationGate`) 그대로. D3는 선례가 코드에 있어 선검증 대신 blind 테스트 7번으로 확인한다(log 1행).

---

## 7. 자율성

- [x] auto (사용자 선택 2026-11-21 — '높음 · auto · PR까지')
- [ ] lazy

## 8. load-bearing 가정

1. **테스트 프로필 한 줄(`enabled: false`)로 기존 테스트 기동이 모두 지금 구성으로 돈다** — 'test' 프로필은 config/application.properties가 모든 테스트 기동에 include한다(ADR-017에서 실증). 착수 직후 기본값 교체 + 프로필 줄만 넣고 전체 테스트로 실증한다. 깨지는 기동 경로(프로필을 안 타는 SpringApplicationBuilder 등)가 있으면 멈추고 보고.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 기본값 교체(코드 · yml · 되돌림 안내 주석) · 거부 메시지 안내 · V16 계획 검사(Flyway 앞) · 테스트 프로필 false | 합의 | 컴파일 · 가정 1 전체 테스트 |
| 02 | blind 계약 테스트(§5 1~7) | 합의 | 통과 · 기준 SHA에서 실패(그린 위장 아님) |
| 03 | 로컬 스모크 3종 · 듀얼 리뷰 루프 · README · NEXT · 측정 로그(docs 커밋 분리) · push · PR | 01 · 02 | 미해소 0 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1(2026-11-21 — "합의, auto로 진행" · V16 계획 검사 포함)
- [x] 자율성 선택 → auto(인터뷰 답)
