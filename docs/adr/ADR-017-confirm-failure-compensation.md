# ADR-017: 결제 성공 후 확정 실패 보상 (Q6 후속)
> 번호 이동(2026-10-12, 5차): 운영 중 매수 방식 전환을 새 ADR-010으로 넣으며 기존 ADR-010~015를 011~016으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-12, 4차): 카운터 어긋남 탐지·격리·복구를 새 ADR-009로 넣으며 기존 ADR-009~014를 010~015로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-11, 3차): counter-upsert-precheck 기본 적용·검증을 새 ADR-008로 넣으며 기존 ADR-008~013을 009~014로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-09, 2차): ADR-006 처리량 벽 진단을 새 ADR-007로 넣으며 기존 ADR-007~012를 008~013으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-09): counter 1문장 upsert 재측정을 새 ADR-006으로 넣으며 기존 ADR-006~011을 007~012로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-04): 임계 구역 길이·락 보유 시간을 새 ADR-004로 넣으며 기존 ADR-004~010을 005~011로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-15, 6차): 앱 JVM GC·메모리 기준을 새 ADR-010으로 넣으며 기존 ADR-010~016을 011~017로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-16, 7차): 측정 환경 꼬리 지연 원인을 새 ADR-011로 넣으며 기존 ADR-011~017을 012~018로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-18, 8차): 카운터 증분 대조·Kafka/복제본 대조를 새 ADR-012·013으로 넣으며 기존 ADR-012~018을 014~020으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-23, 9차): Kafka·읽기 복제본 대조(옛 ADR-013)를 착수 조건이 생기는 오픈 폭주 뒤로 보내 ADR-020으로 옮기고, 기존 ADR-014~020을 013~019로 당겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-11-07, 10차): 억울한 사용자 최소화(결제했는데 좌석을 잃음 — 예방 · 보상 설계)를 새 ADR-016으로 넣으며 기존 ADR-016~020을 017~021로 옮겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: 설계 초안(2026-11-13 — 인터뷰 1차 I1~I12 · 열린 질문 Q1~Q9 = 권장안(사용자 2026-11-13) · 설계 리뷰 듀얼 1패스 반영(RD17-1~13 · 사용자 결정 I13 · I14) · post-fix 재점검 반영(RD17-P1~P3) — 설계 리뷰 마감 · 작업 폴더 `docs/plans/2026-11-13/adr-017-confirm-failure-compensation/`) → **1단계 구현 끝(2026-11-14 — 코드 커밋 ca6f0e23b · e6946a799 · e2876b5ce · 6c0d91d79 · 리뷰 3루프 + 수정분 재점검 · 전체 테스트 green)** → **2단계 측정 끝(2026-11-18 — 캠페인 27/27 유효 · 고정 규칙 choice R · 작업 폴더 `docs/plans/2026-11-14/adr-017-measurement/`)** → **결정(2026-11-18 — 사용자 채택 R 즉시 환불 · 앱 기본값 교체 커밋 335552a04 · 작업 폴더 `docs/plans/2026-11-18/adr-017-default-switch/`)**
- 대응 질문: Q6(결제 성공 + 선점 만료) 후속 — ADR-016이 그 일의 **수를 줄였고**(새 기본값 ④ + ⓒ payBy), 이 ADR은 그래도 남는 건의 **보상**과 ADR-016의 조건 'PG가 payBy를 강제'를 닫는다
- 선행: ADR-015(확정 원자성) · ADR-016(억울한 사용자 최소화 — 설계-4가 넘긴 것: 거부 기록 표 · 같은 paymentUid 재시도 멱등 · 판정기 고아 결제 · 보상 방식)

## 인터뷰 (2026-11-13 — 사용자 답)

| # | 질문 | 답 |
|---|---|---|
| I1 | 범위(ADR-016 설계-4가 넘긴 것) | **넷 다**: 거부 기록 표 · 같은 uid 재시도 멱등 · 판정기 고아 결제 · 보상 방식 비교 |
| I2 | 결제(PG) 등장 방식 | **이 랩 안 흉내 PG**(승인 · 매입 · 취소 · 환불 + 지연 · 실패 주입) — 실제 결제는 02 payment-consistency-lab 범위, 경계는 문서로 |
| I3 | 측정 여부 | **.164 캠페인 측정까지** |
| I4 | ADR-016 조건 'PG가 payBy 강제' | **이 ADR에서 닫는다** — 흉내 PG가 payBy 너머 승인을 거부하는 계약 · 그래도 생긴 억울은 보상으로(예방 016 + 보상 017을 한 계약으로) |
| I5 | 보상 방식 후보 | **R 즉시 환불**(지금 흐름 유지 · 거부 시 서버가 환불) · **D 승인 → 확정 → 매입**(실패면 승인 취소 — 서버가 PG를 부름) · **S 대체 좌석**(자동 배정 · 없으면 환불) |
| I6 | 흉내 PG 위치 | **별도 작은 컨테이너**(HTTP — 타임아웃 · 연결 실패가 실제로 생김 · PG 쪽 원장을 따로 대조 · 레포 안 새 Gradle 모듈) |
| I7 | 주입할 부분 실패 | **넷 다**: PG 지연 · 타임아웃 / PG 일시 실패(5xx) / 앱 죽음(거부 커밋 뒤 · 보상 전) / 같은 uid 재시도 |
| I8 | 성공 기준 | **고아 0 + 보상 완료 시간**: 필수 = 끝 상태 '돈 빠짐 · 좌석 없음 · 보상 없음' 0 · 이중 환불 · 이중 확정 0 → 그다음 보상 완료 p99 · 비용(DB CPU)으로 고른다(구체 문턱은 설계에서 제안) |
| I9 | S 대체 좌석 범위 | **같은 회차 · 같은 section 우선** → 없으면 환불(R로 넘김) · 사용자 사전 동의 가정(가격 차이 문제를 피하는 가정) |
| I10 | 보상 실행 시점 | **아웃박스 + 워커**(확정 트랜잭션 밖에서 보상 작업 행 · 배경 워커가 멱등 호출 · 재시도 — 앱 죽음에도 이어감) |
| I11 | 확정 API 응답 계약 | **본문 필드 추가만**(거부 응답 코드는 그대로 — ADR-015 '새 에러 코드 없음' 유지 · 본문에 보상 정보 + 상태 조회 API · D는 확정 요청이 승인 키를 받음) |
| I12 | 측정 시간 예산 | **≤ 12시간**(후보 3 × 주입 셀 × 3회 — 하룻밤 한 번) |
| I13 | (설계 리뷰 뒤 2026-11-13) 결제 계약 | **payment-start 필수화** — payment-start가 결제 uid를 발급하고 서버가 uid · deadline을 PG에 등록한다. ADR-016의 '권고' 계약을 이 ADR이 강화한다. intent 없는 확정은 옛 경로(보상 · 멱등 보장 밖) |
| I14 | (같은 날) 보상 셀에서 거부를 만드는 법 | **결제 후 확정 지연 주입** — PG deadline 강제는 켠 채 결제 성공 뒤 확정을 늦춰 만료를 넘긴다 |

## 문제

결제는 외부에서 이미 성공했는데 확정이 거부되는 경우가 있다. 결제 도중 홀드가 만료되고 다른 사용자가 그 좌석을 선점한 경우다. 사용자는 돈이 빠졌는데 좌석이 없다.

ADR-016은 이런 일이 생기는 수를 줄였다. 새 기본값 ④ + ⓒ에서 억울은 0/3,300이었다. 그래도 0이 되지 않는 길이 남는다.

- 클라이언트가 payment-start를 부르지 않는다(권고일 뿐이다).
- 결제 쪽이 payBy를 강제하지 않는다.
- 결제 뒤 클라이언트가 확정을 보내기 전에 죽는다.
- 확정 응답을 잃고 다시 보낸다.
- 확정이 5xx로 실패한다.

지금은 이런 건이 지표 하나(`seat.confirm.rejected{code}`)로만 남는다. 누가 얼마를 냈는지는 아무 데도 남지 않는다.

## 가설

- 결제 uid를 서버가 발급하고, 그 결제 의도(intent) 행 하나로 확정 · 거부 · 보상을 직렬화한다. 거부는 확정 트랜잭션 밖에 기록하고, 보상은 아웃박스 + 워커로 실행한다. 여기에 intent를 대조하는 안전망을 더한다(설계-10 RD17-1~3). 그러면 부분 실패(PG 지연 · 일시 실패 · 앱 죽음 · 같은 uid 재시도)에서도 끝 상태 고아 결제를 0으로 만들 수 있다.
- 세 보상 방식(R · D · S)은 고아 0을 모두 지킬 수 있다. 차이는 사용자가 돈을 잃은 시간, 보상 완료 시간, 비용에서 난다.
  - D는 확정이 실패해도 돈이 빠지지 않는다. 승인만 묶였다가 풀린다.
  - D에는 대가가 있다. 매입 실패로 '좌석은 있는데 돈이 없는' 업체 손해가 생긴다.

## 설계-1 지금 코드의 사실 (main f8f52cc3)

| # | 사실 | 근거 |
|---|---|---|
| F1 | 기본 확정 = ④ `conditional-extend`(A sweep) → `AtomicConfirmProcess.conditional`. C1에서 `DELETE seat_hold … expires_at > now`가 0행이면 진단해서 404 HOLD_NOT_FOUND · 403 HOLD_NOT_OWNED · 409 HOLD_EXPIRED로 거부한다. C2 좌석 전환이 0행이거나 예약 INSERT가 V12에 걸리면 409 SEAT_NOT_HELD다 | `AtomicConfirmProcess.kt:67-100` · `ConfirmReservationService.kt:131-141` |
| F2 | 확정은 `@Transactional` 하나다. 거부(`SeatReservationException`)는 **전부 롤백**된다. 그래서 거부를 같은 트랜잭션에 기록할 수 없다 | `ConfirmReservationService.kt:55` |
| F3 | 거부 지표는 응답 기준이다. `seat.confirm.rejected{code}`와 `seat.confirm.failed{status}`를 처리기 표지와 afterCompletion에서 센다(롤백과 무관) | `ConfirmOutcomeMetrics.kt:29-39` · `ApiExceptionHandler.kt:30-31,51-60` |
| F4 | 확정 API는 `POST /api/holds/{holdId}/confirm`(헤더 `X-User-Id`)이다. 본문은 `{paymentUid}`, 응답은 `{reservationId, seatId, status}`다. paymentUid는 저장만 한다. `reservation.payment_uid VARCHAR(64) NOT NULL`이고 유니크가 없다. **멱등 처리도 없다** | `ReservationController.kt:21-46` · `AtomicConfirmProcess.kt:177-181` · `V1__init.sql:38` |
| F5 | payment-start 응답은 `{holdId, expiresAt, payBy, payWithinSeconds, extended}`다. 확정 단계는 payment-start를 불렀는지 검사하지 않는다 | `PaymentStartController.kt:19,61-67` · `PayBy.kt:16-28` |
| F6 | 끝 상태 판정기 `/internal/consistency`(loadtest 프로필)에는 payment_uid · 환불 개념이 없다. ADR-016 세션 분류는 `outcome16.py`가 맡는다(success · wronged · pre_reject · pay_fail). paid-flow는 실제 PG 호출 없이 k6 sleep(D)과 판정만 한다. paymentUid는 `pay-${user}-${hid}`다 | `LoadtestDataService.kt:140-183` · `k6/ADR-016/scripts/outcome16.py:25,35-74` · `scenarios/paid-flow.js:112` |
| F7 | 배경 작업 패턴은 두 가지다. 하나는 `@Scheduled` + `@ConditionalOnProperty`이고, 다른 하나는 손잡이가 켜질 때만 만드는 전용 `ThreadPoolTaskScheduler`다. 실패하면 로그를 남기고 다음 주기로 넘어간다. 행 잠금은 `FOR UPDATE SKIP LOCKED`를 쓴다(만료 배치 · 쿼터 정리). 분산 잠금은 없다 | `HoldExpiryScheduler.kt:10-19` · `drift/CounterDriftScheduling.kt:23-114` · `LazyExpiryBatch.kt:118` |
| F8 | 모듈은 main-app · reconcile-core · reconciler-app이다. 루트 Dockerfile은 main-app만 빌드한다. 하네스 compose는 ADR-009 베이스에 오버레이를 쌓는다(`SPRING_APPLICATION_JSON`을 통째로 덮어씀 — 새 키는 오버레이마다 넣어야 한다) | `settings.gradle.kts:4` · `Dockerfile:3-18` · `k6/ADR-016/compose.paid16.yml` |
| F9 | Flyway 기본 위치의 마지막 버전은 V13이다. `db/migration`에 V15를 두면 기본으로 적용된다. V14는 선택 위치(③ 전용)라 번호만 차지한다 | `application.yml:15-16` · 탐색 보고 |
| F10 | 확정이 성공해도 쿼터 행은 그대로이고 실제 값 쪽만 바뀐다. 거부된 홀드는 만료 배치가 풀면서 카운터를 되돌린다 | `ConfirmReservationService.kt` 주석(ADR-012 ⓐ) |

## 설계-2 용어 · 실패 모양 (세는 단위 = paymentUid)

| 용어 | 정의(끝 상태 · 배수 창 뒤) |
|---|---|
| **결제됨** | PG 원장에서 그 uid가 `CAPTURED`(R · S의 결제, D의 매입) |
| **고아 결제** | 결제됨이다. 그런데 같은 uid의 CONFIRMED 예약이 없고, 환불 완료도 없다. **기대 0**(G1) |
| **이중 확정** | 같은 uid의 CONFIRMED 예약이 2개 이상이다. 기대 0 |
| **이중 환불** | PG 원장 환불 합이 결제 금액보다 크다. 또는 같은 uid에 성공한 환불이 2건 이상이다. 기대 0 |
| **환불됐는데 좌석 있음** | 같은 uid가 CONFIRMED 예약과 환불 완료를 함께 가진다. 기대 0 |
| **돈 없는 좌석**(D) | CONFIRMED 예약은 있는데 PG에서 결제됨이 아니다(매입 실패 · 승인 만료). 업체 손해 — G-비용으로 보고한다 |
| **보상 완료 시간** | 거부 시각(거부 기록) 또는 안전망 탐지 시각부터 보상 작업 DONE까지(분포 · p99) |
| **사용자 돈 잃은 시간** | PG 결제 시각부터 환불 완료 시각까지. D는 승인만 묶이므로 0이다(승인 취소 시각은 따로 보고한다) |

## 설계-3 흉내 PG — 새 모듈 `mock-pg` (I2 · I4 · I6)

- **배포**: 레포 안 새 Gradle 모듈로 둔다. 작은 Spring Boot 앱이고, 측정에서는 별도 컨테이너로 띄운다(포트 8103).
- **원장**: 메모리에 둔다. 측정 중 흉내 PG는 죽이지 않는다. 끝에서 `GET /ledger`로 덤프하고, 회차를 시작할 때 `POST /admin/reset`으로 비운다(RD17-12).
- **등록된 uid만 받는다(I4 · I13 — RD17-3)**: 서버가 payment-start에서 uid와 deadline을 등록한다. 클라이언트가 보내는 deadline은 없다. 등록되지 않은 uid로 결제하면 404 `UNKNOWN_PAYMENT`다.
- **API**: 변경 호출은 모두 uid 기준으로 멱등이다. 다시 부르면 **처리된 결과만** 재생하고, 503 일시 실패는 기억하지 않는다(RD17-13).

| 호출 | 누가 | 의미 · 규칙 |
|---|---|---|
| `POST /intents {uid, userId, amount, deadline}` | 서버(payment-start) | uid 등록 → `REGISTERED`. 같은 uid는 멱등. deadline은 서버 payBy. **이미 `CLOSED`(닫힘 표지)인 uid면 409 — 등록되지 않는다**(RD17-P1) |
| `POST /payments/{uid}/pay` | 클라이언트(k6) — R · S | `REGISTERED` → `CAPTURED`(승인과 매입을 한 번에). **`now > deadline`이거나 `CLOSED`면 409, 결제 없음**(I4) |
| `POST /payments/{uid}/authorize` | 클라이언트 — D | `REGISTERED` → `AUTHORIZED`. deadline 규칙은 위와 같다. `authTtl`이 지나면 PG가 스스로 `EXPIRED`로 바꾼다 |
| `POST /payments/{uid}/capture` | 서버 워커 — D | `AUTHORIZED` → `CAPTURED` |
| `POST /payments/{uid}/close` | 서버 워커 — 보상 공통 | **uid를 닫는다**. 상태별 결과: `REGISTERED`·`EXPIRED` → `CLOSED`(NOT_PAID — 뒤늦은 결제도 막힌다 · RD17-10 감사) · `AUTHORIZED` → `VOIDED` · `CAPTURED` → `REFUNDED` · 이미 닫힘 → 멱등 200 · **등록되지 않은 uid → 닫힘 표지 `CLOSED`(NOT_PAID)를 남기고 200**(등록이 늦게 도착해도 결제할 수 없다 — RD17-P1) |
| `GET /payments/{uid}` · `GET /payments?status=&registeredBefore=` | 서버(결과 모름 조회 · 안전망) | 상태 조회 |
| `GET /ledger` · `POST /admin/faults` · `POST /admin/reset` | 하네스 | 원장 덤프 · 주입 설정 · 원장 비우기 |

- **보상 호출을 `close` 하나로 합친다**. 환불·승인 취소·미결제 종결이 한 호출이다. 그래서 R의 REFUND, D의 VOID, 결제 없는 uid의 종결을 워커가 구분하지 않는다. 결과는 PG 상태로 남는다.
- **응답 분류표(RD17-11)** — 워커가 이 표로 작업을 끝내거나 다시 시도한다:

| 분류 | 응답 | 작업 처리 |
|---|---|---|
| 종결-성공 | close 결과 `REFUNDED` · `VOIDED` · `CLOSED` · capture `CAPTURED` · 멱등 200 | DONE(`last_result` = 결과) |
| 종결-실패 | capture 409(`EXPIRED` · `CLOSED` — 승인 만료 · 취소) | DONE(`last_result` = 실패 사유) · D는 **돈 없는 좌석**으로 집계(Q5) |
| 재시도 | 503 · 연결 실패 · 타임아웃(결과 모름 → 다음 시도 전에 `GET`으로 확인) | attempts + 1 · 백오프 · 시도 상한을 넘으면 DEAD |

- **주입**(호출 종류별, 회차 시드로 재현): 지연 분포 · 결과 모름(처리는 하고 타임아웃보다 늦게 응답) · 일시 실패(처리 없이 503). 비율은 측정 전에 고정한다.
- **금액**: 좌석당 고정 금액 하나로 둔다. 가격 모델은 설계 밖이다.

## 설계-4 결제 의도 · 거부 기록 · 보상 아웃박스 · 멱등 (I1 · I10 · I13)

### 4.1 표 (V15 — `db/migration` 기본 위치)

- **`payment_intent`** — uid 소유 행이자 직렬화 지점이다(RD17-1). 열은 다음과 같다.
  - `uid PK`(서버가 발급하는 UUID), `hold_id`, `user_id`, `schedule_id`, `seat_id`, `section`, `pay_by`, `created_at`
  - `state{OPEN | CONFIRMED | REJECTED | SUBSTITUTED | CLOSING}`
  - `result_code NULL`, `reservation_id NULL`, `closed_reason NULL`
  - `(hold_id) UNIQUE` — 같은 홀드의 payment-start를 다시 부르면 같은 uid를 돌려준다.
- **`confirm_rejection(id, uid UNIQUE REFERENCES payment_intent, code, http_status, rejected_at)`** — 거부의 증거다. 홀드·좌석 정보는 intent에 있다.
- **`compensation_task`** — 열은 `id`, `uid`, `type{CLOSE | CAPTURE}`, `source{REJECTION | SWEEP | CONFIRM}`, `status{PENDING | IN_PROGRESS | DONE | DEAD}`, `attempts`, `next_attempt_at`, `lease_until`, `last_result`, `created_at`, `done_at`이다.
  - `UNIQUE(uid, type)`를 두고, INSERT는 `ON CONFLICT DO NOTHING`으로 한다.
- **`reservation.intent_id BIGINT/UUID UNIQUE NULL`**(새 열 — RD17-9).
  - intent 경로의 예약만 값을 가진다. 기존 행과 옛 경로는 NULL이다.
  - `payment_uid` 유니크는 두지 않는다. 그래서 기존 데이터 R0 검사가 필요 없고, 옛 하네스(`pay-${userId}` 재사용)도 그대로 돈다.
  - **옛 경로는 N17-1~5의 보장 밖이다**(감사 RD17-9). 지표 `seat.confirm.legacy`로 센다.
  - 옛 하네스와 픽스처는 바꾸지 않는다. 그것들이 재는 것은 옛 경로다.
- `reservation.origin{HOLD | SUBSTITUTE}`.

### 4.2 payment-start (필수화 — I13)

- 지금의 확인(①)과 연장(④) 동작은 그대로다.
- 바뀌는 점: **첫 성공 응답에서 intent를 만들고 PG에 등록한다**(`POST /intents` — 트랜잭션 밖, 실패하면 503이고 intent는 OPEN 그대로라 재호출이 멱등). 응답에 `paymentUid`를 더한다.
- 같은 홀드로 다시 부르면 같은 uid와 같은 payBy를 돌려준다. ④ `already`도 같다.
- **연장은 intent를 만들기 전에만 일어난다.** 그래서 PG에 등록되는 deadline이 확정 가능 기간과 어긋나지 않는다(RD17-2 반례 b가 성립하지 않는다).
- 홀드 응답 201의 payBy는 안내용으로만 남는다. 결제의 deadline은 intent의 것이다.

### 4.3 확정 흐름

```
POST /api/holds/{holdId}/confirm {paymentUid}
  · intent가 없는 uid → 옛 경로(지금 코드 그대로 · 지표 legacy)
  · intent 경로 — 한 트랜잭션:
    ⓐ SELECT … FROM payment_intent WHERE uid = ? FOR UPDATE   ← 모든 경로의 첫 잠금
    ⓑ intent.hold_id ≠ holdId 또는 intent.user_id ≠ userId → 409 SEAT_NOT_HELD · 기록 없음 · 지표 uid_conflict (Q3)
    ⓒ state ≠ OPEN → 재생: 첫 결과 코드 · 예약 id는 그대로, 보상 상태는 지금 값(N17-3 — 감사 RD17-OQ1로 계약 명시)
    ⓓ OPEN → 지금 확정 문장(C1 · C2 · 예약 INSERT — intent_id 포함)
        성공: state = CONFIRMED · reservation_id · (D) CAPTURE 작업 → 커밋
        거부: 이 트랜잭션은 롤백 → 새 트랜잭션에서 ⓐ를 다시 잡고, state가 아직 OPEN이면:
              거부 기록 + state = REJECTED(result_code) + 후보별 처리(R · D: CLOSE 작업 / S: 대체 시도 → 실패면 CLOSE 작업)
              state가 OPEN이 아니면 → ⓒ 재생
        두 번째 트랜잭션이 실패하면(DB 오류 · 앱 죽음) → ERROR 로그 + 지표 · intent는 OPEN으로 남고 안전망이 맡는다
  · 응답: 거부 코드는 그대로(I11) · 본문에 compensation{taskId, type, status} 추가 · 조회 GET /api/payments/{uid}
```

- **경합 해소**(RD17-1): 같은 uid로 동시에 온 요청은 ⓐ에서 줄을 선다.
  - 먼저 커밋한 쪽이 CONFIRMED를 만들면, 뒤의 요청은 ⓒ에서 재생 응답을 받는다.
  - 거부 기록 트랜잭션도 ⓐ를 다시 잡는다. 그래서 그 사이에 확정이 끝났으면 재생으로 빠진다.
  - D의 CAPTURE와 CLOSE는 state 하나가 갈라 주므로 함께 생기지 않는다.
- **잠금 순서**(감사 RD17-4 · 재점검 RD17-P2): 새 경로는 **intent를 맨 앞에** 잡고, 그 뒤는 **기존 경로의 순서를 그대로** 따른다. 확정은 홀드 → 좌석(지금 C1 · C2)이다. S의 대체 트랜잭션은 A′ 만료 배치와 같은 **쿼터 → 홀드 → 좌석** 순서다(`SweepConditionalBatch.kt:43` 쌍 잠금 → `:58` 홀드 삭제 → `:87` 좌석 되돌림). 만료 배치와 선점은 intent를 잡지 않는다. 배치가 홀드를 지운 뒤 확정이 오면 404 거부로 처리된다. [구현 검증 — 선점(좌석 NOWAIT → 쿼터) · 배치 · S 사이 교착 0을 동시성 테스트로 확인]

### 4.4 워커

- 전용 스케줄러를 쓴다(F7 패턴 · 손잡이 `seat.compensation.*`).
- 한 주기의 순서:
  1. `FOR UPDATE SKIP LOCKED LIMIT n`으로 작업을 집는다.
  2. IN_PROGRESS + `lease_until`로 바꾸고 커밋한다.
  3. PG를 부른다(트랜잭션 밖).
  4. 결과를 `WHERE id = ? AND lease_until = ?`(fencing — RD17-5) 조건으로 쓴다. 판정은 응답 분류표(설계-3)를 따른다.
- 리스가 만료된 IN_PROGRESS는 PENDING으로 되돌린다.
- **DEAD 복구**(RD17-5):
  - 지표 `seat.compensation.dead`와 ERROR 로그를 남긴다.
  - 관리 API `POST /internal/compensations/{id}/retry`(DEAD → PENDING · attempts 초기화)를 둔다.
  - `ops/compensation-dead/README.md` runbook을 둔다(PG 상태 조회 → 재시도 또는 수동 종결 기록).
  - 정체 지표 `seat.compensation.oldest_pending_seconds`를 둔다.

### 4.5 안전망 — intent 대조 (시간 논증 대신 잠금 · RD17-2)

- **주기**: `state = OPEN`이고 `created_at`이 오래된 intent를 고른다. 각 intent에 대해 한 트랜잭션에서 다음을 한다.
  1. ⓐ intent 잠금을 잡는다.
  2. state가 아직 OPEN인지 다시 본다.
  3. 그 홀드가 **없거나 만료**(`expires_at ≤ now`)인지 본다.
  4. 셋 다 맞으면 state = CLOSING(`closed_reason = SWEEP`)으로 바꾸고 CLOSE 작업을 만든다. 이때 **재생할 결과 코드도 저장한다**: 홀드가 없으면 404 `HOLD_NOT_FOUND`, 만료면 409 `HOLD_EXPIRED`(지금 확정의 진단과 같은 코드 — 뒤늦은 첫 확정 요청은 이 코드 + 보상 본문을 받는다 · RD17-P3).
- **확정과 같은 잠금 아래**에서 판정한다. 그래서 '안전망이 닫은 uid가 나중에 확정되는' 경로가 없다.
  - 확정은 ⓒ에서 state ≠ OPEN을 보고 재생한다.
  - 대체 배정도 OPEN일 때만 일어난다.
  - 따라서 늦은 커밋이나 연장과 무관하다.
- **덮는 것**:
  - 결제 뒤 확정을 보내지 않은 클라이언트
  - 거부 기록 쓰기 실패
  - 확정 5xx(Q9)
  - 앱 죽음
  - 결제하지 않고 떠난 intent — CLOSE가 `CLOSED`(NOT_PAID)로 끝나며, 늦은 결제도 막힌다
- **PG에 묻지 않는다.** 판정은 서버 기록만으로 한다. PG가 느려도 안전망 판정은 멈추지 않는다. PG 호출은 워커 몫이다.
- 홀드가 아직 살아 있으면(④ 연장 안) 기다린다. 만료되면 다음 주기에 닫는다.

## 설계-5 후보 흐름 (I5 · I9)

| 후보 | 클라이언트 | 거부 때 | 성공 때 | 고유 위험 |
|---|---|---|---|---|
| **R 즉시 환불** | payment-start(uid 받음) → PG `pay` → 확정 | CLOSE 작업(→ `REFUNDED`) | — | 환불될 때까지 사용자 돈이 빠져 있다 |
| **D 승인 → 확정 → 매입** | payment-start → PG `authorize` → 확정 | CLOSE 작업(→ `VOIDED`) | 같은 트랜잭션에서 CAPTURE 작업 | 매입 종결-실패로 **돈 없는 좌석**이 생긴다(Q5 · 보고) |
| **S 대체 좌석** | R과 같다 | 거부 기록 트랜잭션 안에서 대체 배정(아래) → 실패면 CLOSE 작업 | — | 쿼터 · 매수 상한 · 판매 마감 · 동의 가정 |

- **S 대체 트랜잭션**(RD17-4 · 감사 잠금 순서):
  1. intent 잠금
  2. 회차 FOR SHARE(판매 마감 — 마감이면 대체하지 않는다)
  3. 쿼터 쌍(회차, 사용자) 잠금 — A′ 배치와 같은 첫 잠금(RD17-P2)
  4. 원래 홀드가 아직 있으면 지운다(쿼터 −1) → 원래 좌석을 되돌린다 — 배치와 같은 홀드 → 좌석 순서
  5. intent의 회차 · section에서 AVAILABLE 좌석을 `FOR UPDATE SKIP LOCKED LIMIT 1`로 잡는다(기다리지 않음) → RESERVED
  6. 쿼터 +1과 상한 판정(선점과 같은 매수 전략 · 이미 잠근 쌍)
  7. 예약(origin = SUBSTITUTE · intent_id) · state = SUBSTITUTED · 증분 대조 등록
  - 4~7에서 하나라도 안 되면 이 트랜잭션을 롤백한다. 그다음 새 트랜잭션에서 CLOSE 작업 + state = REJECTED로 넘긴다. [구현 검증]
- **손잡이**: `seat.compensation.mode = none | refund | auth-capture | substitute`.
  - **`none`**(Q1 · 감사 RD17-7): intent · 거부 기록 · 멱등 · 안전망 **탐지**까지 한다.
  - `none`의 안전망은 닫을 intent를 지표(`seat.compensation.unresolved`)와 로그로만 남긴다. CLOSE 작업을 만들지 않고, state도 OPEN 그대로 둔다.
  - PG 등록(`POST /intents`)은 `seat.payment.pg.base-url`이 있을 때만 한다. 없으면 intent만 만든다 — 이 동작은 `none` 구성에서만 뜬다(2026-11-18 결정 뒤 기본은 refund라 PG 주소 없는 기본 앱은 기동 거부 · PG 없이 띄우는 구성은 `none` 명시 — §결정).
  - 워커는 모드 ≠ none이고 base-url이 있을 때만 켠다. 기동 검사로 강제한다.

## 설계-6 판정기 확장 (I1 '판정기 고아 결제')

- 끝 상태는 **배수 창 뒤에** 판정한다. 창은 작업 큐가 비고 안전망 한 주기가 돌 때까지, 또는 상한 시간까지다.
- 대조 대상은 PG `/ledger` 덤프와 DB(payment_intent · reservation · confirm_rejection · compensation_task)다.
- **기대 0(모드 ≠ none)**:
  - 고아 결제(PG `CAPTURED` · intent에 예약 없음 · CLOSE 미완)
  - intent당 예약 2개 이상
  - 환불됐는데 좌석 있음(`REFUNDED`인데 예약 있음)
  - 승인 취소됐는데 좌석 있음
  - DEAD 작업
  - 배수 뒤 OPEN으로 남은 intent 중 홀드가 만료된 것
- **`none` 모드**: 위 항목 대신 고아 결제 수 · 미해결 수를 **보고**만 한다(작업 없음).
- **보고**:
  - 돈 없는 좌석(D 종결-실패)
  - 보상 완료 시간 분포(거부 · SWEEP 시각 → DONE)
  - 사용자 돈 잃은 시간(PG `CAPTURED` → `REFUNDED`)
  - PG 409 deadline 수(I4)
  - 출처별 작업 수
  - uid_conflict · legacy 수
- `/internal/consistency`(ADR-001)에는 DB만으로 셀 수 있는 항목을 넣는다. PG 대조는 하네스 `summarize17.py`가 맡는다.
- 세션 분류는 `outcome16`을 넓혀 `wronged → wronged_refunded | wronged_voided | wronged_substituted | wronged_open`으로 나눈다. `wronged_open`은 기대 0이다.
- 회차 리셋 `TRUNCATE` 목록에 새 표를 넣는다(RD17-12).

## 설계-7 새 동작 불변식 (설계 선검증 대상 — 리뷰 반영판)

| # | 불변식 | 메커니즘 |
|---|---|---|
| N17-1 | **intent 경로에서** uid(= intent)당 예약은 1개 이하다. 옛 경로(intent 없음)는 보장 밖이다 | `reservation.intent_id UNIQUE` + intent 잠금 · state |
| N17-2 | intent 경로의 확정 거부(404 · 403 · 409)는 state를 REJECTED(또는 SUBSTITUTED)로 바꾼다. 이때 거부 기록과(모드 ≠ none이면) CLOSE 작업 또는 대체 예약을 **한 트랜잭션**으로 남긴다. 그 쓰기가 실패하면 intent는 OPEN으로 남고 안전망이 닫는다(무음 아님) | 4.3 · 4.5 |
| N17-3 | 같은 uid로 다시 요청하면, 상태를 바꾸지 않고 **첫 결과 코드 · 예약 id**와 **지금의 보상 상태**를 돌려준다 | intent.state · result_code · reservation_id |
| N17-4 | 보상 작업은 (uid, type)당 1행이다. PG 변경 호출은 uid 멱등이다. 그래서 워커를 다시 실행해도 PG 결과는 하나다 | `UNIQUE` · `ON CONFLICT DO NOTHING` · fencing · PG 멱등(처리된 결과만 재생) |
| N17-5 | 한 intent에서 '예약'과 'CLOSE 작업'은 함께 생기지 않는다. D의 CAPTURE와 CLOSE도 함께 생기지 않는다 | 모든 경로가 intent 잠금 아래에서 state가 OPEN일 때만 하나를 고른다 |
| N17-6 | PG는 등록된 uid만, 서버가 등록한 deadline으로 판정한다. deadline이 지났거나 닫힌 uid의 pay · authorize는 거부하고 원장에 결제로 남기지 않는다 | 설계-3 |
| N17-7 | 모드 ≠ none일 때: OPEN이고 홀드가 없거나 만료된 intent는 안전망 한 주기 안에 CLOSING + CLOSE 작업이 된다 | 4.5 |
| N17-8 | D: 확정 성공이면 같은 트랜잭션에 CAPTURE 작업이 있다. 거부되면 CLOSE 작업이 생긴다(N17-5로 배타) | 4.3 |
| N17-9 | S: 대체 배정은 판매 마감 · 매수 상한 · 쿼터 카운터를 지키고, 만료 배치와 같은 잠금 순서를 따른다. 못 지키면 CLOSE로 넘긴다 | 설계-5 S 트랜잭션 |

## 설계-8 측정 설계 — 뼈대 (I3 · I7 · I8 · I12 · I14 — 고정값과 문턱은 측정 전 고정 커밋에서)

- **거부를 만드는 법(I14)**: PG deadline 강제(새 기본값 ④ + ⓒ)는 켜 둔다. 대신 **결제 성공 뒤 확정 전송을 일부 늦춰** 만료를 넘기게 한다(클라이언트 느림 · 네트워크 흉내).
  - 늦출 비율과 지연 분포는 측정 전에 고정한다.
  - 이렇게 만든 거부는 실제 길(결제는 마감 안에 끝났는데 확정이 늦음)을 흉내 낸다.
  - 일부는 확정을 아예 보내지 않는다(클라이언트 죽음 → 안전망).
  - 결제하지 않고 떠나는 세션(intent만 생김 → NOT_PAID 종결)도 둔다.
- **셀**(후보 R · D · S × 아래 × 3회):

| 셀 | 내용 | 용도 |
|---|---|---|
| base | 주입 없음 + 확정 지연 · 확정 안 보냄 · 같은 uid 재시도(응답을 비율 r로 버리고 다시 보냄) | G1 · G2 · **G3(비용 — 이 셀 기준 · RD17-8)** |
| pg | base + PG 지연 · 결과 모름 · 일시 실패(capture · close) | G1 · G2 |
| kill | base + 회차 중 앱 강제 종료 2회 → 재기동 | G1 · G2(안전망 · 리스 · 거부 기록 쓰기 실패) |

  - 9셀 × 3회 = 27회다. 기본값(④ + ⓒ + PG 강제) 확인은 base 셀 자체가 맡는다. 그래서 따로 default 셀을 두지 않는다.
  - 회차 시간은 dry-run으로 확인한다. **≤ 12시간을 넘으면 측정 전 고정 때 사용자에게 다시 묻는다**(I12).
- **고정 규칙 초안**:
  - **G1 정합**: 모든 셀 · 회차에서 설계-6의 기대 0 항목이 전부 0이다.
  - **G2 보상 완료**: base p99 ≤ X, pg · kill p99 ≤ Y(Q6).
  - **G3 비용**: base 셀의 DB CPU · 확정 p99 · payment-start p99가 R 대비 '범위 비겹침이고 중앙값 비 > 1.10'이 아니다.
  - **고르는 순서**: D > R > S(Q2). D의 돈 없는 좌석 · S의 대체 비율은 G-비용으로 보고한다.
- **하네스**: `k6/ADR-017/`에 둔다. 다음을 만든다.
  - paid-flow: payment-start(uid 받음) → PG pay · authorize → (지연) → 확정
  - mock-pg compose 오버레이
  - 배수 창 · PG 덤프 · PG 리셋
  - `outcome17` · `summarize17`
  - kill 일정

## 설계-9 열린 질문 (권장안 1개씩 — 2026-11-13 사용자 답: Q1~Q9 모두 권장안)

| # | 질문 | 권장안 |
|---|---|---|
| Q1 | 측정 전 `seat.compensation.mode` 기본값 | `none`(지금 동작을 유지하고, 거부 기록 · 멱등 · 안전망 탐지만 켬) → 측정 뒤 결정 커밋에서 교체(ADR-015 · 016 선례) — 리뷰 뒤 의미: 설계-5 '`none`'(작업 없음 · 탐지는 지표만) |
| Q2 | G1~G3을 통과한 후보 사이의 순서 | **D > R > S** — 사용자 돈을 잃지 않는 쪽을 먼저 둔다. S는 동의 가정이 있어 뒤에 둔다. 단 D의 돈 없는 좌석은 G-비용으로 보고하고 사용자 결정에 맡긴다 |
| Q3 | 같은 uid가 다른 hold · user로 온 확정 | 409 `SEAT_NOT_HELD`로 거부하고 기록하지 않는다(첫 uid의 결과를 덮지 않음). 새 코드는 쓰지 않는다(I11). 지표 `seat.confirm.uid_conflict` — 리뷰 뒤: 판정은 intent의 hold · user와 요청 대조(설계-4.3 ⓑ) |
| Q4 | 안전망 G | ~~`G = M + 60초`~~ → **리뷰 뒤 폐기**(RD17-2): 시간 논증 대신 intent 잠금 아래 '홀드 없음 또는 만료' 판정(설계-4.5) |
| Q5 | D 매입 실패(승인 만료 · 거절) 때 예약 | 예약은 유지한다. 돈 없는 좌석으로 기록 · 지표 · 보고만 하고, 예약 취소 정책은 범위 밖(02 랩 · 운영 정책) |
| Q6 | G2 문턱 X · Y | 스모크 실측 뒤 측정 전 고정 커밋에서 정한다(ADR-016 W · θ 선례). 초안 X = 30초 · Y = 120초 |
| Q7 | 흉내 PG 원장 저장 | 메모리(측정 중 PG는 죽이지 않음) + 끝 덤프 |
| Q8 | 거부 기록 · 보상 작업 정리 | 이번에는 하지 않는다. 행이 쌓이는 비용은 base 셀 G3에서 보고, 정리 배치는 NEXT로 |
| Q9 | 5xx(`seat.confirm.failed`) 확정 | 거부 기록을 남기지 않는다(커밋 여부를 모름). 안전망이 맡는다 — 리뷰 뒤: intent가 OPEN으로 남아 있으면 안전망이 닫고, CONFIRMED로 커밋됐으면 재요청이 재생한다 |

## 설계-10 설계 리뷰가 바꾼 것 (2026-11-13 — 설계 선검증 + 듀얼 1패스 · 위 절에 이미 반영)

| id | 지적(요지) | 바꾼 것 |
|---|---|---|
| RD17-1 | uid 직렬화 지점이 없음 — 동시 재시도 · 다른 hold에서 '확정 + 환불', D는 CAPTURE + VOID | `payment_intent` 잠금이 모든 경로의 첫 잠금 · state 하나가 결과를 정함(4.3) |
| RD17-2 | 안전망 `deadline + G`가 확정 가능 기간의 상한이 아님(결제 뒤 ④ 연장 · 늦은 커밋) | 시간 논증을 폐기하고 intent 잠금 아래 '홀드 없음 또는 만료'로 판정(4.5) · 연장은 intent 생성 전에만(4.2) |
| RD17-3 | deadline이 클라이언트 입력이라 I4가 아님 · 보상 셀에 deadline 없음 | payment-start가 uid를 발급하고 서버가 PG에 등록(I13) · 보상 셀은 확정 지연으로(I14) |
| RD17-4 | S가 쿼터 카운터를 깸 · 잠금 순서 | S 트랜잭션 문장 · 순서(intent → 회차 → 좌석 → 쿼터 → 홀드 — 감사 반영) |
| RD17-5 | DEAD 복구 · 리스 fencing 없음 | 관리 API · runbook · 정체 지표 · `lease_until` fencing(4.4) |
| RD17-6 | 404에서 S가 회차를 모름 · hold_id 없어 Q3 구분 불가 | intent가 hold · 회차 · 좌석 · section 보존 |
| RD17-7 | `none`과 N17-2 충돌 · PG 없는 기본 앱 | `none` = 작업 없음 · 탐지는 지표(감사 반영 — 탐지 유지) · PG 등록과 워커는 base-url이 있을 때만 |
| RD17-8 | G3 cost 셀 없음 · default 셀 R만 | G3 = base 셀 · 기본값 확인은 base 셀 자체(27회) |
| RD17-9 | V15 uid 유니크가 기존 데이터 · 옛 하네스 · 픽스처를 깸 | `reservation.intent_id UNIQUE NULL` · 옛 경로는 보장 밖(감사 — N17-1 범위 명시) |
| RD17-10 | 결제 없는 uid 보상 → DEAD · 늦은 결제(감사) | PG `close` 하나로 합침 — 미결제는 `CLOSED`로 이후 결제 차단 |
| RD17-11 | D capture 409 · 자동 취소 분류 없음 | 응답 분류표(종결-성공 · 종결-실패 · 재시도) |
| RD17-12 | 회차 리셋이 새 표 · PG 원장을 안 지움 · uid 반복 | TRUNCATE 목록 · PG `/admin/reset` · 서버 발급 UUID |
| RD17-13 | mock-pg가 503을 기억 | 처리된 결과만 재생 |
| RD17-OQ1 | 재생 저장 단위 | 첫 결과 코드 · 예약 id는 고정, 보상 상태는 지금 값(N17-3 계약 명시 — 감사) |
| RD17-OQ2 | D 승인 키 | 승인 키 = 서버 발급 uid |
| RD17-P1 | (재점검) 등록 지연 중 CLOSE가 먼저 가면 미등록 uid 결과 미정 · 늦은 등록 뒤 결제 가능 | PG close가 미등록 uid에 닫힘 표지를 남기고, 닫힌 uid의 등록은 409 |
| RD17-P2 | (재점검) S 잠금 순서가 A′ 배치(쿼터 → 홀드 → 좌석)와 반대 — 교착 | S를 쿼터 → 홀드 → 좌석으로 · 새 좌석은 SKIP LOCKED · 교착 0은 동시성 테스트 [구현 검증] |
| RD17-P3 | (재점검) 안전망이 닫은 intent에 첫 확정이 오면 재생할 코드가 없음 | 안전망이 홀드 진단 코드(404 · 409)를 result_code로 저장 |

## 설계 밖

- 실제 PG 연동과 02 payment-consistency-lab 통합
- 부분 환불 · 좌석 가격 · 사용자 동의 UI
- 대체 좌석의 등급 · 가격 차이
- 보상 표 정리 배치(Q8)
- 매입 실패 뒤 예약 취소 정책(Q5)

## 구현 (1단계 — 2026-11-13~14 · 작업 폴더 `docs/plans/2026-11-13/adr-017-compensation-impl/` · 명세 부록 A · B)

- **구현 커밋**: ca6f0e23b(V15 · intent · 확정 직렬화 · 거부 기록 · 재생 · 조회) · e6946a799(mock-pg · PG 등록 · 워커 · 안전망 · R · D · S · DEAD 복구 · 판정기) · e2876b5ce(V15 반영 기존 마이그레이션 단언 · 테스트 힙 2g) · 6c0d91d79(리뷰 반영 — V15 앞 스키마 · 워커 리스 · 안전망 · 다른 uid 확정 차단). 재측정 기준 이미지 = 이 커밋들 앞 main(f8f52cc33).

- **코드**: V15(`payment_intent` · `confirm_rejection` · `compensation_task` · `reservation.intent_id` · `origin`) · payment-start intent 생성 · PG 등록 · 확정 facade(`PaymentConfirmService` — intent 잠금 · uid_conflict · 재생 · 거부 기록 · R · D · S) · 워커(`CompensationWorker`) · 안전망(`CompensationSweep`) · 스케줄러 · 기동 검사 · DEAD 복구 API · 결제 조회 API · 판정기 `pay_*` 항목 · 새 모듈 `mock-pg`(포트 8104 — `reconciler-app`이 8103을 쓴다).
- **바뀐 계약(사용자 승인 2026-11-13)**:
  - payment-start 200 본문에 `paymentUid`가 더해졌다. 결제는 이 uid로만 한다(I13 — ADR-016의 '권고'가 필수가 됐다).
  - ADR-016 N16-1 '① payment-start는 아무 행도 쓰지 않는다' → '홀드 · 좌석 · 쿼터 · 표지는 쓰지 않는다 — intent 행 하나만 만든다'.
  - ADR-015 ④ 'not_needed 뒤 호출은 연장' → intent가 생긴 뒤에는 연장하지 않는다(RD17-2 — 남은 시간이 이미 W 이상이라 결제 시간은 W − M 이상 보장).
  - Flyway 기본 위치 마지막 버전 13 → 15. 결제 시작 모드(① · ④)는 target ≥ 15가 필요하다(기동 검사).
- **옛 하네스 주의**: ADR-015 · 016 하네스(N16-1 쓰기 탐침 · payment-start 본문 키 대조)는 이 커밋 뒤 이미지에서 intent INSERT · `paymentUid`를 '예상 밖'으로 본다. 재측정은 측정 이미지(커밋 앞 APP_TAG)로 한다(ADR-016 §결정의 옛 하네스 주의와 같은 처방). 또 payment-start를 부른 홀드는 발급 uid(`paymentUid`)로만 확정된다 — 옛 하네스처럼 `pay-…` uid를 보내면 409 SEAT_NOT_HELD다(기존 테스트 5개는 확정 인자만 발급 uid로 바꿨다 — 사용자 승인 2026-11-14).
- **구현에서 정한 세부(리뷰 반영)**:
  - 옛 경로(intent 없는 uid)도 확정 트랜잭션 앞에 `payment_intent` 조회 1문장(커넥션 1회 더 빌림)이 붙는다 — N17-10의 '같은 SQL'은 확정 트랜잭션 안의 문장을 뜻한다. 옛 경로 비용을 재면 이 차이가 섞인다. V15 앞 스키마(기준선 재현 — current + target 7)에서는 표가 없음을 한 번 보고 조회 없이 옛 경로로 간다.
  - 거부 롤백과 재잠금 사이(A2 창)에 안전망이 먼저 intent를 닫으면, 재잠금 쪽은 안전망이 저장한 코드(HOLD_NOT_FOUND · HOLD_EXPIRED)를 재생한다 — 처음 난 거부 코드와 다를 수 있고 거부 기록은 없다. 이중 결과는 없다(state 하나).
  - payment-start의 지표(`seat.confirm.pay_start{ok}` · `seat.confirm.extend{…}`)는 DB 커밋 뒤에 센다. 그 뒤 PG 등록이 실패하면 응답은 503(또는 409)이지만 그 지표는 이미 올라 있다 — 등록 실패는 `seat.payment.pg.register{result}`로 따로 센다(응답 합 대조 때 뺀다).
  - `payment_intent.schedule_id` · `seat_id`는 FK가 없다(스냅숏 — FK면 intent INSERT가 회차 · 좌석 행에 FOR KEY SHARE를 건다).
  - none 모드 안전망은 잠그지 않고 미해결 수를 센다. `seat.compensation.unresolved`는 주기마다 본 수의 합이다(기울기 ÷ 주기 = 지금 미해결 수). none은 intent를 닫지 않으므로(Q8 — 정리 없음) 이 수와 집계 비용은 시간에 따라 커진다.
  - capture 응답: 409(승인 만료 · 취소 · 미승인 등)는 종결-실패(돈 없는 좌석), 409 밖 4xx(미등록 404 등)는 재시도 → 상한에서 DEAD.
  - 판정기 `pay_rejected_without_task`는 모드 ≠ none일 때만 낸다(none은 작업이 없는 것이 정상). `pay_compensation_mode`를 함께 낸다.
  - ④ 동시 첫 호출 경합: 연장이 필요 없는 요청 A가 intent를 만드는 중(미커밋)에 연장 구간에 들어온 요청 B가 E1로 홀드를 연장할 수 있다(`NOT EXISTS payment_intent`가 A의 미커밋 행을 못 본다). 이때 둘은 A의 intent(uid · pay_by)를 함께 받고 홀드는 연장된다 — pay_by가 홀드 만료보다 **이른** 쪽으로만 어긋난다(PG 마감이 확정 가능 기간 안 — RD17-2의 위험 방향인 '마감 > 만료'는 생기지 않는다). 지표는 B를 `extended`로 센다.
  - 안전망은 후보 intent를 `FOR UPDATE SKIP LOCKED`로 잡는다(확정 중인 intent는 기다리지 않고 다음 주기) · 한 건의 오류는 그 건만 건너뛰고 `seat.compensation.sweep_failed` + ERROR 로그. 후보는 uid 순으로 순회 위치에서 이어 고른다(잠기거나 계속 실패하는 후보가 배치 자리를 막아 뒤 후보가 굶지 않게). 배치 한도(`sweep.batch`)를 넘게 밀리면 N17-7 '한 주기 안에'는 '배치 한도 안에서'다.
  - **intent 있는 홀드 + 다른 uid 확정(사용자 결정 2026-11-13 — 리뷰 R3)**: 홀드가 요청 사용자의 것이고 intent가 있으면 409 SEAT_NOT_HELD · 기록 없음(I13 — 결제는 발급 uid로만). 옛 경로로 확정되면 intent가 OPEN으로 남아 안전망이 그 결제를 닫기 때문(좌석 + 환불). 남는 틈: 이 검사와 옛 확정은 원자적이지 않다 — 같은 홀드의 payment-start 커밋과 다른 uid 확정이 동시에 오면(클라이언트가 자기 요청끼리 경합) 옛 경로로 확정될 수 있다. 발급 uid로 결제하려면 payment-start 응답을 받은 뒤여야 하므로 정상 흐름에서는 생기지 않는다.
  - PG 등록이 이미 닫힌 uid(REFUNDED · VOIDED · CLOSED)를 멱등 200으로 돌려주면 payment-start는 409 HOLD_EXPIRED다(409 CLOSED와 같게).
  - 흉내 PG `/admin/reset`은 원장과 함께 주입 설정도 비운다 — 2단계 하네스는 회차 리셋 뒤 `/admin/faults`를 다시 건다.
- **운영 배포 전**: V15는 `reservation`에 열 둘과 유니크(전부 NULL인 `intent_id`)를 더한다. 표를 다시 쓰지 않지만 유니크 인덱스를 만드는 동안 쓰기가 막힌다. 큰 표면 배포 창에서 적용한다. DEAD 복구 = `ops/compensation-dead/README.md`.

## 실험 결과

> 작업 폴더 `docs/plans/2026-11-14/adr-017-measurement/`(명세 · log · 리뷰 ledger) · 계약 `k6/ADR-017/INTERFACE.md`(§5 결정표 · §8 판정) · 요약 `k6/ADR-017/results/campaign17-20261118/RESULT.md` · `campaign17.json`.

### 결과-1 캠페인 개요

- **캠페인**: `campaign17-20261118`. 하네스 757c17f88(측정 전 고정 커밋 · harness_hash 015a99c2…), 이미지 `k6/ADR-017/results/campaign-prep/images-757c17f8.json`(앱 · 흉내 PG 같은 SHA), 서버 .164, k6 PC는 이 PC.
- **진행**: 2026-11-18 00:26부터 08:17 마지막 회차 끝까지(7시간 51분 — 예상 8.46 · 최악 10.26시간 · 요약기 08:18 끝). 27자리 모두 첫 시도 ok(무효 0 · 재측정 0). 회차 사이 대조(고정 값 · SHA · 하네스 해시)가 모두 같다(`runs_consistent`).
- **시작 조건**: ⓪ 하네스 · 이미지 고정, ① .164 스모크(`results/smoke17-164b` · 5회 ok — 앞선 `smoke17-164a`는 PC 무선 끊김을 링크 점검이 놓쳐 R/base 무효), ② 캠페인 스모크(csmoke17-20261118 · D base ok), ③ dry-run 최악 10.26시간 ≤ 12, ④ k6 PC 점검, ⑤ FIXED, ⑥ 전체 테스트 green(main-app 1243/0 · 건너뜀 24 · mock-pg 24/0 · reconciler 6/0)이 모두 통과했다.
- **고정 값**(측정 전 고정 2026-11-17 — 스모크 초안 그대로 · 사용자 확인):

  | 값 | 내용 |
  |---|---|
  | 세션 | 회차당 1,100 · 20/s · 정상 확정 60 · 지연 확정 20 · 확정 안 보냄 5 · 결제 안 하고 이탈 10 · 같은 uid 재시도 5(%) |
  | 지연 확정 L | 연장 만료(payment-start 응답 `expiresAt`) 뒤 U[0.2, 10]초 |
  | 앱 눈금 | ④ + ⓒ 기본값(W 611초 · θ 1초 · M 1초) · TTL 300초 · 배치 10초 · 워커 1초 · 안전망 5초(배치 200) · 시도 상한 10 |
  | PG 주입(pg 셀) | capture · close에만 — 지연 200ms · 일시 실패 10% · 결과 모름 5%(5초) |
  | kill(kill 셀) | 180초 · 760초에 앱 강제 종료 → 재기동 |
  | 문턱 | G2 base X = 30초 · pg · kill Y = 120초 · G3 중앙값 비 1.10 |

### 결과-2 G1 정합 · G2 보상 완료

- **G1**: 27회차 모두 기대 0 항목이 0이다(uid 끝 상태 · DEAD 작업 · 배수 뒤 OPEN · 미완료 작업 · `pay_*` · `v_*` · integrity · wronged_open · mode_fail). k6 결제 성공 = PG 원장 결제(base · pg 990 · kill 959 ~ 971). 배수는 모두 `empty`(11.7 ~ 17.9초). kill 회차마다 이벤트 2 · 재기동 · 남은 intent 0.
- **G2**(회차 p99 · 초):

  | 후보 | base | pg | kill |
  |---|---|---|---|
  | R | 0.98 · 1.00 · 1.03 | 15.37 · 20.09 · 16.31 | 1.03 · 1.03 · 1.01 |
  | D | 1.02 · 1.00 · 1.01 | 15.21 · 20.39 · 15.00 | 0.97 · 0.93 · 0.94 |
  | S | 1.00 · 0.62 · 1.02 | 15.49 · 14.54 · 14.91 | 1.14 · 1.17 · 1.15 |

  모두 문턱(30 · 120초) 안이다. 표본은 DONE CLOSE 작업뿐이다(S 대체 성공은 빠진다 — INTERFACE §8). pg 셀의 14.5 ~ 20.4초는 결과 모름(흉내 PG 5초 지연 · 앱 읽기 타임아웃 3초 → 다음 시도는 조회 먼저)과 503 뒤 백오프(1초부터) 재시도로 보인다(원인 분해는 재지 않음).
- **attempts**(9회 합): base · kill 전부 1회. pg는 R 1 = 966 · 2 = 170 · 3 = 15 · 4 = 4, D 1 = 2,799 · 2 = 454 · 3 = 43 · 4 = 3 · 5 = 1(capture 포함), S 1 = 835 · 2 = 137 · 3 = 18 · 4 = 2 · 5 = 1. DEAD 0(상한 10).
- **주입 관측**(pg 셀 · 회차마다 `reached` true): close fail 38 ~ 59 · unknown 13 ~ 25, D capture fail 75 ~ 76 · unknown 31 ~ 44.

### 결과-3 G3 비용 (base · 3회 [최소, 최대] · 중앙값 · R 대비 비)

| 지표 | R | D | S |
|---|---|---|---|
| DB CPU 평균(%) | [9.24, 9.46] · 9.43 | [9.26, 9.74] · 9.62 · 1.020 | [9.07, 9.33] · 9.28 · 0.984 |
| 확정 p99(ms) | [9.57, 10.17] · 9.98 | **[11.92, 12.56] · 12.15 · 1.218** | [9.74, 10.48] · 10.02 · 1.004 |
| payment-start p99(ms) | [16.58, 18.36] · 17.16 | [16.69, 17.06] · 16.78 · 0.978 | [16.50, 17.18] · 16.74 · 0.975 |

- D의 확정 p99는 R과 범위가 겹치지 않고 위쪽이며 중앙값 비 1.218 > 1.10이다 → **G3 실패**. D가 R과 다른 점은 성공 확정마다 같은 트랜잭션에서 CAPTURE 작업 행을 쓰고(N17-8 · 캠페인 합 CONFIRM 출처 6,330건) 워커가 비동기로 매입한다는 것이다. 확정 p99 차이는 이 설계 차이와 방향이 같다 — 두 요인(트랜잭션 안 INSERT · 동시 매입 워커 부하)을 나눠 재지는 않았다. DB CPU는 1.02배로 차이가 없다.
- 스모크(`results/smoke17-164b` · 회차별 summary17.json)에서도 같은 방향이었다(확정 p99 R 9.9 · D 12.4ms).

### 결과-4 판정 (요약기 결론 = 고정 규칙 자동)

| 후보 | G1 | G2 | G3 | pass |
|---|---|---|---|---|
| D | 통과 | 통과 | **실패** | false |
| **R** | 통과 | 통과 | 기준 | **true → choice** |
| S | 통과 | 통과 | 통과 | true(순서상 R 뒤) |

- 순서 D → R → S(Q2)에서 D가 G3에 걸려 **R**에서 멈췄다. 후보 선택은 사람 판정 없이 고정 규칙이 끝냈다.

### 결과-5 G-비용 보고 (판정에 넣지 않음 — 사용자 결정 몫)

| 항목 | R | D | S |
|---|---|---|---|
| 돈 없는 좌석(9회 합) | 0 | 0 | 0 |
| 지연 확정 1,980세션(9회 합)의 결과 | 환불 1,954 · app_dead 26 | 승인 취소 1,952 · app_dead 28 | 대체 492 · 환불 1,460 · app_dead 28 |
| 대체 비율 `substituted ÷ (substituted + late 환불)` | — | — | 합 0.252(회차 0.218 ~ 0.301) |
| 결제(승인) → 환불(승인 취소) — 회차 p50 범위 · 회차 p99 범위 · 최대 | 562 ~ 573 · 606.6 ~ 618.2 · 633초 | 563 ~ 575 · 607.4 ~ 618.2 · 619초 | 561 ~ 572 · 606.0 ~ 613.5 · 619초 |
| 출처별 작업 REJECTION · SWEEP · CONFIRM(9회 합) | 491 · 2,999 · 0 | 497 · 2,990 · 6,330 | 0 · 2,995 · 0 |
| PG 409 deadline(9회 합) | 0 | 0 | 0 |
| uid_conflict · legacy(base · pg 6회 — kill 셀은 재기동으로 카운터가 0부터라 미측정) | 0 · 0 | 0 · 0 | 0 · 0 |
| app_dead 세션(kill 3회 합) · shape | 150 · none 82 · refunded 65 · seat_paid 3 | 151 · none 83 · voided 65 · seat_paid 3 | 152 · none 84 · refunded 65 · seat_paid 3 |

- 시간 행의 표본은 REFUNDED · VOIDED uid 전체다(지연 확정 + 확정 안 보냄 + kill 셀 app_dead 중 닫힌 것 · n R 2,511 · D 2,509 · S 2,017).
- **약 10분은 보상이 느려서가 아니다.** 지연 확정은 ④가 연장한 홀드(W 611초)가 끝난 뒤에 보낸다(하네스 정의 — `paid-flow17.js` late = 연장 만료 + L). 서버는 그때까지 확정이 올지 모른다. 그래서 세 후보 모두 결제 시각부터 약 W − 결제 시간(p50 약 9.5분) 뒤에 보상이 시작된다. 시작 뒤 CLOSE 완료는 G2 p99로 base · kill 약 1초, PG 주입 셀 약 14.5 ~ 20.4초다.
- **R · S와 D의 차이는 시간이 아니라 돈의 상태다.** R · S는 그동안 매입된 돈(빠져나간 돈)이 환불을 기다린다. D는 같은 시간 승인 홀드(한도만 묶임)였다가 취소된다. 대신 D는 모든 성공 확정에 매입 작업이 붙고(확정 p99 ×1.22), 매입이 비동기라 PG 승인 만료 · 거절이면 돈 없는 좌석이 생긴다(이번 0 — 흉내 PG 승인 유효 30분 안에서 매입이 다 끝났다. 승인 만료가 짧은 실제 PG에서는 따로 봐야 한다).
- **S의 대체 비율은 이번 구성의 값이다.** S의 REJECTION 출처 작업이 0이다 — 대체가 실패했다면 거부 기록 트랜잭션이 REJECTION 출처 CLOSE를 만든다(`PaymentConfirmService` 거부 처리). 즉 이번 구성(여유 좌석 ≥ 지연 + 확정 안 보냄 · 판매 마감 없음)에서는 안전망(5초 주기)보다 먼저 도착한 지연 확정이 모두 대체에 성공했고, 나머지는 안전망이 먼저 intent를 닫아 환불됐다. 그래서 비율은 L 분포(0.2 ~ 10초)와 안전망 주기에 묶인다. 좌석이 모자라거나 판매 마감 · 매수 상한에 걸리는 구성의 비율은 이번 측정 밖이다.

### 결과-6 측정 한계

- PG 조회(GET) 실패 경로는 주입하지 않았다(흉내 PG GET에 주입 지점 없음 · 앱 변경 금지 — 명세 정정 Q/A 2).
- 흉내 PG 승인 유효 30분 · 매입 실패 결정적 주입 없음 → D의 돈 없는 좌석은 이번 측정이 만들지 못했다(0은 '안 생긴다'의 증거가 아니다).
- PC 링크 점검(pclink14)은 유선 eno1 끊김만 본다(smoke17-164a에서 무선 끊김을 놓쳐 거짓 무효). 캠페인 동안 eno1 carrier 1 · pc-link down 0.
- 서버 한 대(.164) · 앱 한 인스턴스 · 흉내 PG 메모리 원장.

## 결정

- **고정 규칙이 고른 후보 = R 즉시 환불**(`seat.compensation.mode = refund` — 요약기 choice R · 결과-4). **사용자 채택 2026-11-18.**
- **채택 이유(사용자 2026-11-18)**: 환불이 가장 명확하다 — 확정이 안 되면 돈을 돌려준다는 결과 하나뿐이고, 좌석을 원하면 사용자가 다시 고른다. S(대체 좌석)는 사용자가 고르지 않은 좌석 배정(동의 가정) · 다른 대기자가 잡을 빈 좌석 소모 · 쿼터 · 마감 얽힘 때문에 쓰지 않는다(보여 주고 고르게 하면 새 선점 흐름이 필요해 'R + 다시 고르기'와 같아진다). D는 정상 확정마다 매입 작업이 붙고(확정 p99 ×1.22) 비동기 매입이 돈 없는 좌석을 만들 수 있다(서버가 보관하는 것은 결제 uid뿐 — 카드 정보는 PG).
- **대가**(G-비용 보고 · 사용자 결정 몫):
  - 확정이 오지 않은 결제(지연 확정 · 확정 안 보냄)는 연장 홀드가 끝날 때까지(p50 약 9.5분 · 최대 약 10.5분) 매입된 돈이 빠진 채로 기다린 뒤 환불된다. D였다면 같은 시간 승인 홀드였다.
  - 고정 규칙에서 D가 떨어진 사유는 확정 지연 +2.2ms(×1.22) 하나다. 비동기 매입의 돈 없는 좌석 위험은 이번 측정이 만들지 못해(결과-6) 규칙 밖이고, 채택 이유에서 따로 고려했다 — 지연 문턱을 맞춰도 이 위험은 남는다. 이 문턱(1.10)은 측정 전 고정이라 이번 결정에서 바꾸지 않는다. '매입된 돈 대 승인 홀드'를 확정 2ms보다 무겁게 볼지는 실제 PG 연동(02 payment-consistency-lab) 때 다시 볼 질문으로 NEXT에 남긴다.
- **앱 기본값 교체(커밋 335552a04 · 2026-11-18)**: `seat.compensation.mode` 기본 = `refund`(코드 기본값 `CompensationProperties`와 `application.yml` 같은 값 — 계약 테스트 `Adr017DefaultModeContractTest` · 실제 기동 `Adr017DefaultModeBootTest`). D(`auth-capture`) · S(`substitute`)는 측정 · 비교용 손잡이로 남는다.
  - **PG 주소 필수 — 명시 강제(사용자 결정 2026-11-18 · 자동 후퇴 없음)**: refund는 `seat.payment.pg.base-url`이 있어야 기동한다. PG 없이 띄우는 구성은 `seat.compensation.mode: none`(intent · 거부 기록 · 안전망 탐지만)을 명시한다. 안 적으면 기동 검사가 그 안내와 함께 거부한다.
  - **거부된 기동은 스키마를 바꾸지 않는다(사용자 결정 2026-11-18)**: 보상 설정 검사(PG 주소 · 확정 모드)를 Flyway migrate **앞**(`HoldStrategyMigrationGate`)에서도 부른다 — 기본값 교체 뒤 가장 흔한 거부(새 jar + PG 주소 없음)가 V15 등을 적용한 뒤 거부되지 않게(ADR-015 '거부된 기동은 스키마를 바꾸지 않는다'와 같은 관례).
  - **current와 함께 쓰기**: 확정 모드 `current`(ADR-015 범위 밖 구성 — B · C · D 만료 · 기본 밖 조합 · V12 앞 기준선)는 결제 시작(payment-start)이 없어 refund와 함께 뜨지 않는다. current로 띄울 때는 `seat.compensation.mode: none`도 명시한다(거부 메시지가 안내한다).
  - **테스트 · 로컬**: 테스트 클래스패스는 'test' 프로필(`src/test/resources/application-test.yml`)이 none을 준다 — `bootTestRun`도 같다(거기서 refund를 보려면 mode와 PG 주소를 함께 준다). 기존 테스트 단언은 바꾸지 않았다.
  - **되돌림**: 이전 기본으로 돌리려면 `seat.compensation.mode: none` 한 줄(보상 작업 없음 · 탐지만).
  - **옛 하네스 주의**: 이 커밋 뒤 이미지에서는 보상 키 · PG 주소가 없는 옛 하네스(ADR-014 · 015 · 016 compose)가 기동 거부된다. 재측정은 측정 이미지(커밋 앞 APP_TAG — ADR-016 §결정 처방과 같음)로 하거나 `seat.compensation.mode=none`을 더한다. ADR-017 하네스(`compose.pay17.yml`)는 모드를 명시해 영향이 없다.
