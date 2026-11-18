# 요구사항 명세서 — ADR-017 보상 기본값 refund 교체

> 작성일: 2026-11-18 · 작업 폴더: `docs/plans/2026-11-18/adr-017-default-switch/` · 브랜치 `feat/adr-017-measurement`(측정 결과 커밋 36512c9ff 위 — ADR-016 기본값 교체 9d2bf21b5와 같은 자리)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문(2026-11-18): "응 R로 채택하고 기본값 교체 진행해줘 D는 매입처리를 하려면 결국 저 정보들을 보관하는 리스크가 있어서 굳이겠구나. 대체 좌석은 안하는게 맞겠구나, 저걸 해버리면 대체좌석을 고른 사람들이 결제하는 사이에 환불되어버리는 일이 있어서, 애초에 대체좌석은 넣으면 안됬네."
  - 메인 정정(같은 턴 보고): D가 서버에 보관하는 것은 결제 uid뿐(카드 정보는 PG) — D 탈락의 실제 이유는 확정 p99 ×1.22 · 비동기 매입의 돈 없는 좌석 위험. S는 거부 트랜잭션 안에서 이미 낸 돈으로 배정해 '결제 사이 환불'은 없다 — 안 쓰는 이유는 동의 가정 · 다른 대기자 좌석 소모 · 쿼터/마감 얽힘. 결론(R 채택 · D · S 기본 아님)은 같다.
- Q/A:
  1. 채택 = **R 즉시 환불**(ADR-017 고정 규칙 choice R · 사용자 채택 2026-11-18).
  2. PG 주소 없는 구성(통합 테스트 · 로컬 실행) = **명시 강제**(권장): 기본 refund · PG 없이 띄우려면 `seat.compensation.mode: none`을 적어야 기동 · 안 적으면 이유를 말하며 기동 거부(ADR-015 current 명시 강제와 같은 방식 — 자동 후퇴 없음). 테스트 프로필은 none 한 줄 · 기본값 계약 테스트 추가.
  3. 자율성 = **auto**.
  - 사용자 보충(같은 턴): "그 대체좌석을 다른 사람이 결제중이라 락인 경우가 생길 수 있으니 심플하게 가는게 맞지않나? … 아니면 그 순간 홀드된 좌석들을 제외하고 보여주면 되려나" → 메인 답: S는 AVAILABLE 좌석만 `FOR UPDATE SKIP LOCKED`로 잡아 결제 중(HELD) · 잠긴 좌석은 이미 빠진다. 보여주고 고르게 하면 새 선점 흐름이 필요해 'R 환불 + 클라이언트 다시 고르기'와 같아진다 → 단순하게 R.
  - 사용자 확인: "그렇기에 환불정책이 가장 명확하지않냐는거야 대체좌석을 쓰지않고" — R은 결과가 하나(돈을 돌려준다) · 좌석을 원하면 사용자가 다시 고른다.
  5. (재합의 2026-11-18 · 리뷰 루프 1 뒤 AskUserQuestion) 거부된 기동이 스키마를 먼저 바꾸는 문제 = **Flyway 앞에서 검사**(권장) — 설정만 보는 보상 검사(PG 주소 · 확정 모드)를 `HoldStrategyMigrationGate` 앞단에서도 부른다. 계획 밖 파일 `HoldStrategyStartupCheck.kt` 1개를 범위에 더한다. 실제 기동 테스트로 '거부 시 스키마 무변경'까지 본다.
  4. D · S 코드는 측정 · 비교 손잡이로 남긴다(메인 제안 — 사용자 요청은 기본값 교체뿐).

---

## 1. 목표·대상 (필수)

seat-reservation-lab `main-app`에서 `seat.compensation.mode`의 기본값이 `none`에서 `refund`가 된다 — 코드 기본값(`CompensationProperties`)과 `application.yml`이 같은 값이고, 보상 키를 주지 않고 PG 주소를 준 앱은 refund로 뜨며, PG 주소 없이 기본으로 띄우면 `mode: none` 명시를 안내하며 기동을 거부한다.

## 2. 경계·불변식 (필수)

- N1 코드 기본값 = `application.yml` 값 = REFUND(한쪽만 바뀌지 않는다 — Adr016DefaultModeContractTest와 같은 3면 대조).
- N2 모드 ≠ none ⇒ PG base-url 필수 — 기존 기동 검사(`CompensationScheduling.checkConfig` · ADR-017 설계-5) 그대로. 자동으로 none으로 내려가지 않는다(무음 무보상 금지). 거부 메시지에 'PG 없이 띄우려면 `seat.compensation.mode=none`'을 넣는다.
- N3 기존 테스트의 단언은 바꾸지 않는다 — PG 없이 띄우는 테스트 경로는 테스트 프로필의 `none` 한 줄로 지금과 같은 모드에서 돈다. 모드를 이미 명시한 테스트(Adr017*)는 그대로.
- N4 refund 모드의 동작(워커 · 안전망 · CLOSE)은 바꾸지 않는다 — 설정 기본값만 바뀐다.
- N6 (Q/A 5) 보상 설정 검사로 거부되는 기동은 스키마를 바꾸지 않는다(Flyway 앞 검사 — ADR-015 '거부된 기동은 스키마를 바꾸지 않는다'와 같은 관례).
- N5 `reconciler` 프로필(CompensationScheduling `@Profile("!reconciler")`)과 옛 하네스(고정 옛 이미지 태그)는 영향 밖.

## 3. 기준소스 (필수)

- ADR-017 §결정 · 결과-4(choice R) · 설계-5(손잡이 · 기동 검사) — `docs/adr/ADR-017-confirm-failure-compensation.md`
- 선례: ADR-016 기본값 교체 커밋 9d2bf21b5(코드 · yml · 계약 테스트 · 되돌림 안내) · ADR-015 current 명시 강제(`Adr015DefaultModeContractTest`)
- 코드: `CompensationProperties.kt` · `CompensationScheduling.kt` · `main-app/src/main/resources/application.yml` · `main-app/src/test/resources/config/application.properties`(이 파일에는 키 하나만 — Adr012DefaultsContractTest 전제)

## 4. 금지영역 (필수)

- 닫힌 ADR · V1~V15 마이그레이션 · mock-pg 코드 · k6 하네스(k6/**) · 측정 결과 폴더.
- R · D · S 동작 코드(워커 · 안전망 · PaymentConfirmService) — 설정 기본값 · 기동 검사 메시지 · 기동 검사를 Flyway 앞에서도 부르는 것(Q/A 5 재합의) 밖은 바꾸지 않는다.
- `main-app/src/test/resources/config/application.properties`에 키를 더하지 않는다(Adr012 전제) — 테스트 프로필 값은 별도 파일.
- 기존 테스트 단언 변경 금지(필요해지면 멈추고 보고).
- push · PR · 원격 브랜치는 사용자 확인 뒤. docs 커밋과 code 커밋 분리.

## 5. 검증 방법 (필수)

- 새 계약 테스트 `Adr017DefaultModeContractTest`(blind 워커가 이 명세만 보고 작성): ① 코드 기본 REFUND ② application.yml 바인딩 REFUND ③ PG 주소 없는 기본 설정 → 기동 검사 거부 · 메시지에 `seat.compensation.mode=none` 안내 ④ PG 주소 있는 기본 설정 → 기동 검사 통과 ⑤ `mode: none` + PG 없음 → 통과.
- 전체 테스트 `./gradlew cleanTest test`(실제 실행 확인 — up-to-date 무실행 금지): 기존 1243/0/24 + 새 테스트, 실패 0.
- 스모크: 로컬에서 앱 jar를 ① 키 없이(PG 없음) 띄워 거부 메시지 확인 ② `--seat.compensation.mode=none`으로 띄워 기동 확인 ③ PG 주소(흉내 PG)를 주고 띄워 `보상 스케줄러 시작: mode=REFUND … worker=PT1S` 로그 확인.
- diff self-review · 듀얼 리뷰 루프(≤ 3) · 반증 질문 1회.

## 6. stakes (필수)

- 판정: **높음** — 결제 보상의 기본 동작(환불 작업 생성)과 기동 계약(PG 없는 기본 기동 거부)을 바꾼다(결제 · public 설정 계약). 변경 크기는 작다(ADR-016 기본값 교체는 중간이었다 — 이번은 결제 경로 기본값이라 한 단계 위로 둔다).
- 설계 선검증: 새 동작 불변식 없음 — N2는 기존 강제 위치 `CompensationScheduling.kt:86-89`(모드 ≠ none ⇒ base-url) 그대로, N1은 설정 값 대조. log에 1행으로 남긴다.

---

## 7. 자율성

- [x] auto (사용자 선택 2026-11-18)
- [ ] lazy

## 8. load-bearing 가정

1. **테스트 프로필 파일 하나로 PG 없는 모든 테스트 기동이 지금 모드(none)로 돈다** — `@SpringBootTest`와 `SpringApplicationBuilder` 기동 모두 'test' 프로필을 include한다(config/application.properties). 착수 직후 기본값 교체 + 프로필 파일만 넣고 전체 테스트로 실증한다. 깨지는 기동 경로가 있으면 멈추고 보고.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 기본값 교체(코드 · yml · 되돌림 안내 주석) · 기동 검사 메시지 · 테스트 프로필 none | 합의 | 컴파일 · 가정 1 전체 테스트 |
| 02 | blind 계약 테스트(명세 §5 ①~⑤ — 구현 diff 미열람) | 합의 | 통과 |
| 03 | 로컬 스모크 3종 · 듀얼 리뷰 루프 · ADR-017 §결정 · NEXT 갱신(docs 커밋 분리) | 01 · 02 | 미해소 0 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1(2026-11-18 — "응 합의, 진행해줘")
- [x] 자율성 선택 → auto(합의 시 기록)
