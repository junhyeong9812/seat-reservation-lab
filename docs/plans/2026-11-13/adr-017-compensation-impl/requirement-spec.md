# 요구사항 명세서 — ADR-017 1단계: 결제 의도 · 거부 기록 · 보상 · 흉내 PG (앱 코드 + mock-pg)

> 작성일: 2026-11-13 · 작업 폴더: `docs/plans/2026-11-13/adr-017-compensation-impl/`
> 설계 정본 = `docs/adr/ADR-017-confirm-failure-compensation.md` 설계-3~7 · **설계-10(리뷰가 바꾼 것 — 위 절에 반영됨)**. 설계 작업 폴더 = `docs/plans/2026-11-13/adr-017-confirm-failure-compensation/`(리뷰 ledger RD17-*).
> 범위 = **main-app + 새 모듈 mock-pg + 테스트**. `k6/ADR-017/` 하네스 · 측정 전 고정 · 캠페인은 2단계 명세(측정 직전).

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문(2026-11-13): "응 구현 명세 써줘" — 설계 리뷰 마감 보고(두 단계 권장) 뒤.
- 앞서 정해진 것(ADR-017 §인터뷰 I1~I14 · §설계-9 Q1~Q9 = 권장안 · 설계 리뷰 RD17-1~13 · P1~P3):
  - 범위 넷(거부 기록 · uid 멱등 · 판정기 고아 · 보상 방식 R · D · S) · 흉내 PG 별도 컨테이너 · 아웃박스 + 워커 · 응답 본문 필드 추가만(새 에러 코드 없음).
  - **I13 payment-start 필수화**: payment-start가 uid를 발급하고 서버가 uid · deadline을 PG에 등록. intent 없는 확정 = 옛 경로(보장 밖).
  - Q1 측정 전 기본 `none` · Q3 다른 hold · user의 같은 uid → 409 SEAT_NOT_HELD · Q5 D 매입 실패 = 예약 유지 · 보고 · Q7 PG 원장 메모리 · Q8 정리 배치 없음 · Q9 5xx는 안전망.
- 이 명세에서 정한 것(사용자 확인 대상 — §승인 상태 아래 질문): §8 기본값 · §9 task 분해 · stakes · 자율성.

## 1. 목표·대상 (필수)

`main-app`과 새 Gradle 모듈 `mock-pg`에서 ADR-017 설계-3~7을 구현한다. 끝 = 아래 전부가 테스트로 증명되고, 손잡이 `seat.compensation.mode = refund | auth-capture | substitute`로 R · D · S가 각각 켜지며, 기본(`none` · PG 주소 없음)에서는 지금 동작 + intent · 거부 기록 · 멱등 · 안전망 탐지만 더해진다.

1. **V15**(`db/migration`): `payment_intent` · `confirm_rejection` · `compensation_task` · `reservation.intent_id UNIQUE NULL` · `reservation.origin`(기본 `HOLD`) — 설계-4.1. 인덱스: `payment_intent(hold_id) UNIQUE` · OPEN intent 안전망 조회용 부분 인덱스 · `compensation_task(status, next_attempt_at)`.
2. **payment-start 필수화**(설계-4.2): ① · ④의 지금 판정 · 연장은 그대로 → 첫 200에서 intent 생성(같은 hold면 기존 uid · payBy 재사용) → PG 주소가 있으면 `POST /intents` 등록(트랜잭션 밖 · 실패면 503 · 재호출 멱등) → 응답에 `paymentUid` 추가.
3. **확정 흐름**(설계-4.3): intent 있는 uid는 intent `FOR UPDATE` 첫 잠금 → ⓑ uid_conflict(409 SEAT_NOT_HELD · 기록 없음) → ⓒ 재생(첫 코드 · 예약 id 고정, 보상 상태는 현재 값) → ⓓ 지금 확정 문장 + state 전이 · 거부는 롤백 뒤 새 트랜잭션에서 재잠금 · 거부 기록 · 후보별 처리. intent 없는 uid = 옛 경로 그대로 + 지표 `seat.confirm.legacy`. 응답 본문 `compensation{taskId, type, status}` 추가(오류 본문 포함) · `GET /api/payments/{uid}`.
4. **mock-pg 모듈**(설계-3): `/intents` · `pay` · `authorize` · `capture` · `close`(상태별 REFUNDED · VOIDED · CLOSED · 미등록은 닫힘 표지) · 조회 · `/ledger` · `/admin/faults` · `/admin/reset` · uid 멱등(처리된 결과만 재생 — 503 기억 안 함) · deadline · `authTtl` 자동 만료 · 주입(지연 · 결과 모름 · 일시 실패, 시드 재현). 메모리 원장. 루트 Dockerfile과 별도 이미지(모듈 Dockerfile).
5. **PG 클라이언트 + 워커**(설계-4.4): RestClient · 타임아웃 · 응답 분류표(종결-성공 · 종결-실패 · 재시도) · `SKIP LOCKED` + 리스 + `lease_until` fencing · 백오프 · DEAD · 관리 API `POST /internal/compensations/{id}/retry` · 지표 `seat.compensation.{done,dead,retry}` · `oldest_pending_seconds`.
6. **안전망**(설계-4.5): OPEN intent 중 홀드 없음 · 만료인 것을 intent 잠금 아래 CLOSING + CLOSE 작업 + 재생 코드(404 · 409) 저장. `none`이면 지표 `seat.compensation.unresolved` · 로그만(작업 · 상태 변경 없음).
7. **후보 R · D · S**(설계-5): R · D 거부 = CLOSE 작업 · D 성공 = 같은 트랜잭션 CAPTURE 작업 · S = 대체 트랜잭션(intent → 회차 FOR SHARE → 쿼터 쌍 → 원래 홀드 · 좌석 → 새 좌석 SKIP LOCKED → 쿼터 +1 · 상한 → 예약 SUBSTITUTE) → 실패면 CLOSE.
8. **기동 검사**: 모드 ≠ none ⇒ `seat.payment.pg.base-url` 필수 · 모드 ≠ none ⇒ 확정 모드 ① 또는 ④(payment-start가 있는 모드) · 워커 · 안전망 손잡이 값 범위.
9. **판정 · 리셋**: `/internal/consistency`에 DB 항목(intent당 예약 ≤ 1 · 거부 기록 ↔ state · 모드 ≠ none에서 REJECTED/CLOSING ↔ CLOSE 작업) · loadtest 리셋 TRUNCATE에 새 표.
10. **문서 · ops**: `ops/compensation-dead/README.md`(DEAD 복구 runbook) · ADR-017 §구현 절(구현 커밋 · 바뀐 계약).

## 2. 경계·불변식 (필수)

- **N17-1~N17-9**(ADR-017 설계-7 리뷰 반영판 그대로 — 이 명세의 판정 기준).
- **N17-10(새 · 호환)** intent 없는 uid의 확정은 지금 코드와 같은 SQL · 같은 판정 · 같은 응답이다(본문에 새 필드 없음). 옛 하네스 · 기존 테스트 픽스처를 바꾸지 않는다.
- **N17-11(새 · 호환)** `current` · ② · ③ 모드와 B · C · D 만료 방식의 외부 계약은 그대로(payment-start 404 · intent 없음).
- **N17-12(새)** 기본 구성(`none` · PG 주소 없음)에서 PG 호출은 0이다. 워커 · PG 등록은 꺼지고, intent 생성 · 거부 기록 · 재생 · 안전망 탐지만 돈다.
- **ADR-016 N16-1 대체(명시)**: ① payment-start는 홀드 · 좌석 · 쿼터 · 표지를 바꾸지 않는다 — **단 intent 행을 만든다**(I13). ADR-016 하네스의 N16-1 쓰기 탐침은 새 이미지에서 이 INSERT를 센다 → ADR-016 · 015 하네스 재측정은 측정 이미지(커밋 앞 APP_TAG)로(ADR-016 결정 '옛 하네스 주의'와 같은 처방 · ADR-017 §구현에 적는다).
- 범위 밖: 만료 배치 · 선점 판정 문장 · 기존 에러 코드 · `k6/` 전체 · 측정.

## 3. 기준소스 (필수)

- 설계: ADR-017 설계-3~7 · 10 · 인터뷰 I1~I14 · Q1~Q9 · 리뷰 ledger(`…/adr-017-confirm-failure-compensation/log.md`).
- 코드(브랜치 `feat/adr-017-confirm-failure-compensation` · base f8f52cc33): `ConfirmReservationService.kt` · `AtomicConfirmProcess.kt` · `PaymentStartService.kt` · `PaymentStartController.kt` · `PayBy.kt` · `ReservationController.kt` · `ApiExceptionHandler.kt` · `ConfirmOutcomeMetrics.kt` · `HoldStrategyStartupCheck.kt` · `SweepConditionalBatch.kt`(잠금 순서) · `UserLimitStrategies.kt`(쿼터) · `SalesCloseGate.kt`(마감) · `drift/CounterDriftScheduling.kt`(전용 스케줄러 패턴) · `LoadtestDataService.kt` · `application.yml`.
- 테스트 기반: `support/IntegrationTest.kt` · `incremental/BootSupport.kt` · `TestcontainersConfiguration.kt`.

## 4. 금지영역 (필수)

- V1~V14 마이그레이션 · 기존 k6 결과(ADR-015 이하 · ADR-016) · `k6/` 하네스 전체(2단계).
- 닫힌 ADR(ADR-016 이하) 문서 수정 금지 — 대체 계약은 ADR-017에만.
- 기존 테스트의 단언 변경 금지(키 명시 · 픽스처 추가만 — ADR-016 기본값 교체 선례). 바뀌는 계약이 있으면 멈추고 보고.
- .164 · .158 · .9 사용 없음(측정 없음). 사용자 컨테이너 · `docker system prune` 금지.
- 커밋 · 주석에 리뷰 출처 · AI attribution 금지. push · PR은 사용자 확인 뒤.

## 5. 검증 방법 (필수)

- **blind 테스트 워커**(구현 diff 미열람 — 이 명세 + ADR 설계만 보고 계약 테스트 설계): N17-1~12 각각 최소 1개.
- 동시성 테스트(필수 — [구현 검증] 이연분):
  - 같은 uid 동시 확정 2개 · 확정 ∥ 안전망 · 확정 ∥ 만료 배치 → '예약 + CLOSE' 0 · 첫 결과 재생.
  - S 대체 ∥ 만료 배치 ∥ 선점 → 교착 0(PostgreSQL deadlock 오류 0) · 쿼터 카운터 = 실제 수.
  - 워커 리스 만료 · 두 워커 → PG 호출 결과 하나 · fencing.
- mock-pg 단위 · 계약 테스트(상태 전이 · 멱등 · deadline · 닫힘 표지 · 503 비기억 · 주입 시드 재현).
- 통합: main-app ↔ mock-pg(테스트에서 mock-pg를 같은 JVM 랜덤 포트로 띄우거나 Testcontainers — task 03에서 결정) — R · D · S 끝 상태 · PG 원장 대조.
- 기동 검사 계약 테스트(BootTestBase).
- 전체 테스트 green(기준: 214클래스 · 1166 · 실패 0 · 건너뜀 24 → 늘어난 수만 차이) + mock-pg 모듈 테스트.
- 스모크: 로컬 docker compose(앱 + DB + mock-pg)로 payment-start → pay → 확정(성공 · 지연 거부) → 워커 CLOSE → `/ledger` 대조 1회.
- 리뷰: **듀얼 리뷰 루프(≤3)** · 보안 스캔 · 머지 전 최소 안전선(core §4).

## 6. stakes (필수)

- 판정: **높음** — 결제(돈 · 환불) · public API 계약 변경(payment-start 필수화 · 응답 필드) · 새 마이그레이션(V15 — 운영 DB 적용) · 동시성(잠금 순서 · 교착) · 새 모듈. 설계 선검증은 설계 리뷰에서 완료(ADR-017 설계-10 · N17-1~9) — 새 불변식 N17-10~12는 호환 불변식이라 기존 강제 위치(지금 확정 경로 · 모드 분기 `ConfirmReservationService.kt:55-59`)로 대체(log 1행).

---

## 7. 자율성 (모드 — stakes와 별개 축)

- [x] auto (권장 — ADR-015 · 016과 같다) — 사용자 선택 2026-11-13
- [ ] lazy

## 8. load-bearing 가정 (1~2개) · 기본값 제안

- **A1** 확정 트랜잭션 맨 앞의 intent `FOR UPDATE`가 지금 C1 · C2의 판정을 바꾸지 않고(EPQ · 진단 그대로), 같은 uid가 아닌 요청 사이에는 기다림을 만들지 않는다 → task 02 착수 직후 동시성 스모크(같은 uid 2개 · 다른 uid 2개)로 실증.
- **A2** 거부 뒤 '롤백 → 새 트랜잭션 재잠금' 사이의 창에서 다른 요청이 CONFIRMED를 만들면 재잠금 쪽이 재생으로 빠진다(이중 결과 0) → 같은 스모크.
- 기본값(사용자 확인 2026-11-13 — 제안대로):
  - PG 클라이언트: 연결 1초 · 읽기 3초.
  - 워커: 주기 1초 · 한 번에 50건 · 리스 30초 · 시도 상한 10 · 백오프 1초 ×2(상한 60초).
  - 안전망: 주기 5초 · 한 번에 200건.
  - mock-pg 포트 8103 · `authTtl` 기본 10분 · 고정 금액 10,000.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | V15 · intent 엔티티 · payment-start uid 발급(PG 등록 없이) · 응답 `paymentUid` | — | 마이그레이션 적용 · 같은 hold 재호출 같은 uid · ① · ④ 기존 테스트 green |
| 02 | 확정 흐름(intent 잠금 · uid_conflict · 재생 · 거부 기록 트랜잭션 · legacy) · 응답 `compensation` · `GET /api/payments/{uid}` · 모드 none | 01 | A1 · A2 스모크 · N17-1 · 3 · 5 · 10 · 11 |
| 03 | mock-pg 모듈(API · 상태 · 멱등 · 닫힘 표지 · 주입 · reset · ledger) · Dockerfile | — | 모듈 테스트 · N17-6 |
| 04 | PG 클라이언트 · payment-start PG 등록 · 워커(분류표 · 리스 · fencing · DEAD · retry API) · R(refund 모드) · 기동 검사 | 02 · 03 | R 끝 상태 · N17-4 · 12 · 리스 동시성 |
| 05 | 안전망(none 탐지 · 모드 ≠ none 종결 · 재생 코드) | 04 | N17-7 · 확정 ∥ 안전망 |
| 06 | D(auth-capture — CAPTURE 작업 · 매입 종결-실패 집계) | 04 | N17-8 · 돈 없는 좌석 지표 |
| 07 | S(substitute — 대체 트랜잭션 · 잠금 순서) | 04 | N17-9 · 교착 0 · 쿼터 정합 |
| 08 | 판정기 · 리셋 · ops runbook · ADR §구현 · 전체 테스트 · 스모크 | 01~07 | §5 전부 |

- 테스트: task마다 blind 테스트 워커(명세 기준) → 구현 → 듀얼 리뷰는 01~08 묶음 루프(≤3).

---

## 승인 상태

- [x] 필수 6칸 전부 기입 (빈 칸 없음)
- [x] 사용자 합의(2026-11-13 — 명세 그대로 · 기본값 제안대로) → SPEC=1 (훅 기록 — Claude 직접 편집 금지)
- [x] 자율성 선택 → MODE=auto

---

## 부록 A — task 03~05 이름 · 연결 계약 (2026-11-13 · 구현 착수 때 메인이 고정 — blind 테스트 워커의 입력)

- **mock-pg 포트 8104**(명세 §8의 8103에서 바꿈 — `reconciler-app/Dockerfile`이 이미 `EXPOSE 8103`). API는 설계-3 표 그대로, 본문 `{payment: {uid, userId, amount, deadline, state, registeredAt, authorizedAt, capturedAt, closedAt, refunds, tombstone}, code}` · 상태 이름 `REGISTERED · AUTHORIZED · CAPTURED · EXPIRED · VOIDED · REFUNDED · CLOSED` · 거부 코드 `UNKNOWN_PAYMENT`(404) · `DEADLINE_PASSED` · `CLOSED` · 상태 이름(409) · 주입 503 `INJECTED_FAILURE`. `/admin/faults` 본문 `{seed, rules: {<op>: {delayMs, failRate, unknownRate, unknownDelayMs}}}` · op = `register · pay · authorize · capture · close`.
- **main-app 설정 키**(기본값 = §8):
  - `seat.payment.pg.base-url`(없음) · `seat.payment.pg.connect-timeout`(1s) · `seat.payment.pg.read-timeout`(3s) · `seat.payment.amount`(10000)
  - `seat.compensation.mode`(none) · `seat.compensation.scheduler-enabled`(true — 테스트 기반 `IntegrationTest`는 false로 핀)
  - `seat.compensation.worker.interval`(1s) · `.batch`(50) · `.lease`(30s) · `.max-attempts`(10) · `.backoff-initial`(1s) · `.backoff-max`(60s)
  - `seat.compensation.sweep.interval`(5s) · `.batch`(200)
- **한 주기 직접 호출(테스트용 빈)**: `com.jun.labs.seatreservation.service.impl.payment.CompensationWorker.runOnce(): Int`(처리한 작업 수) · `CompensationSweep.runOnce(): Int`(닫거나(모드 ≠ none) 탐지한(none) intent 수). 스케줄러는 이 둘을 주기로 부른다.
- **관리 API**: `POST /internal/compensations/{id}/retry` → DEAD면 PENDING · attempts 0 · 200 / DEAD 아니면 409 / 없으면 404.
- **지표**: `seat.compensation.done{type, result}` · `.retry{type}` · `.dead{type}` · `.unresolved`(none 탐지) · `.oldest_pending_seconds`(게이지) · `.record_failed` · `seat.confirm.legacy` · `seat.confirm.uid_conflict`.
- **테스트 연결**: main-app `testImplementation(project(":mock-pg"))` — 테스트가 같은 JVM에서 `SpringApplicationBuilder(MockPgApplication::class.java)`로 랜덤 포트에 띄우고, 원장 시계 빈 `mockPgClock`을 main-app의 `MutableClock`에 위임하는 시계로 바꾼다(앱과 PG가 같은 시각). `seat.payment.pg.base-url`을 그 주소로 준다.
- **PG 등록 시점**: payment-start 첫 200 커밋 뒤(트랜잭션 밖)에 `POST /intents {uid, userId, amount, deadline = pay_by}` — 실패(타임아웃 · 5xx · 연결)면 payment-start 응답 503 · intent는 OPEN 그대로 · 재호출이 다시 등록(멱등). 등록이 409 `CLOSED`면 payment-start 409 `HOLD_EXPIRED`.

## 부록 B — task 06 · 07 이름 · 계약 (2026-11-13 · 메인 고정)

- 응답 `compensation`(오류 본문 · 성공 본문 · 조회 공통): `{taskId?, type, status, reservationId?, seatId?}` — 값 없는 필드는 빠진다. 작업이면 type `CLOSE` · `CAPTURE` + taskId · S 대체 성공이면 type `SUBSTITUTE` · status `DONE` · reservationId · seatId(taskId 없음).
- **D(`seat.compensation.mode=auth-capture`)**: 클라이언트는 PG `authorize` → 확정. 확정 200 본문에 compensation{type CAPTURE · status PENDING} · 같은 트랜잭션에 CAPTURE 작업(source CONFIRM). 워커 capture: AUTHORIZED → CAPTURED(DONE · last_result CAPTURED) · 승인 만료(EXPIRED) 등 409 → DONE · last_result `FAILED_<code>`(돈 없는 좌석 — 예약은 그대로 · Q5) · 지표 `seat.compensation.done{type=CAPTURE, result=FAILED_…}`. 거부 = CLOSE 작업 → PG VOIDED. 같은 intent에 CAPTURE와 CLOSE 공존 0(N17-8 · N17-5).
- **S(`seat.compensation.mode=substitute`)**: 확정 거부 응답 = 원래 거부 코드(I11) + compensation{type SUBSTITUTE, status DONE, reservationId, seatId}. 대체 예약 `reservation.origin = 'SUBSTITUTE'` · `intent_id = uid` · `payment_uid = uid` · 같은 회차 · 같은 section · 좌석 RESERVED · intent state SUBSTITUTED · result_code = 원래 코드 · confirm_rejection 1행 · CLOSE 작업 없음. 같은 section에 빈 좌석이 없거나 · 판매 마감(회차 시작 시각 지남) · 매수 상한이면 → CLOSE 작업(REJECTED) · 대체 예약 없음. 재요청 = 같은 응답 재생. 쿼터 카운터(`user_hold_quota.cnt`)는 대체 뒤 실제 (홀드 + 확정 예약) 수와 같다 — 원래 홀드가 남아 있었으면 옮김(그대로), 만료 배치가 이미 지웠으면 +1. 지원 매수 방식 = counter-upsert · counter-upsert-precheck(그 밖이면 기동 거부).
- **동시성(명세 §5)**: S 대체 ∥ A′ 만료 배치(`ExpireHoldsUseCase.expire()`) ∥ 같은 사용자 · 다른 사용자 선점 → PostgreSQL 교착 오류 0 · 끝 상태 쿼터 = 실제 수 · 같은 좌석 CONFIRMED ≤ 1.
