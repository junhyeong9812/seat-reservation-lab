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

- 상태: 설계 초안(2026-11-13 — 인터뷰 1차 I1~I12 · 열린 질문 Q1~Q9 · 설계 리뷰 전 · 작업 폴더 `docs/plans/2026-11-13/adr-017-confirm-failure-compensation/`) — 미측정
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

- 거부를 확정 트랜잭션 밖에 기록하고, 보상을 아웃박스 + 워커로 실행한다. 여기에 PG 원장을 대조하는 안전망을 더한다. 그러면 부분 실패(PG 지연 · 일시 실패 · 앱 죽음 · 같은 uid 재시도)에서도 끝 상태 고아 결제를 0으로 만들 수 있다.
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

- **배포**: 레포 안 새 Gradle 모듈로 만든다. 작은 Spring Boot 앱이다. 측정에서는 별도 컨테이너로 띄운다(포트 8103 — 앱 8101 · reconciler 8102 다음).
- **원장 저장**: 메모리에 둔다. 측정 중에는 흉내 PG를 죽이지 않는다(주입 대상은 앱이다). 끝에서 `GET /ledger`로 덤프한다.
- **API**(모든 변경 호출은 멱등이다 — 같은 키를 다시 부르면 첫 결과를 그대로 돌려준다):

| 호출 | 누가 | 의미 · 규칙 |
|---|---|---|
| `POST /payments/{uid}/pay {userId, amount, deadline}` | 클라이언트(k6) — R · S | 승인과 매입을 한 번에 한다 → CAPTURED. **`now > deadline`이면 409 DEADLINE_PASSED, 결제 없음**(I4 — ADR-016 조건을 닫는다) |
| `POST /payments/{uid}/authorize {userId, amount, deadline}` | 클라이언트 — D | → AUTHORIZED(승인 유효 시간 `authTtl` 뒤 PG가 스스로 취소). deadline 규칙은 위와 같다 |
| `POST /payments/{uid}/capture` | 서버 워커 — D | AUTHORIZED → CAPTURED. 승인이 만료됐거나 취소됐으면 409 |
| `POST /payments/{uid}/void` | 서버 워커 — D | AUTHORIZED → VOIDED(이미 VOIDED면 멱등 200) |
| `POST /payments/{uid}/refund` | 서버 워커 — R · S(대체 실패) · 안전망 | CAPTURED → REFUNDED. 전액 1회만 된다(다시 부르면 멱등 200, 두 번째 환불은 없다) |
| `GET /payments/{uid}` · `GET /payments?status=&before=` | 서버(결과 모름 조회 · 안전망) | 상태 조회 |
| `GET /ledger` · `POST /admin/faults` | 하네스 | 원장 덤프 · 주입 설정 |

- **주입**(호출 종류별 · 회차 시드로 재현):
  - 지연 분포
  - **결과 모름**: 처리는 하고 클라이언트 타임아웃보다 늦게 응답한다
  - **일시 실패**: 처리하지 않고 503을 돌려준다
  - 비율은 측정 전에 고정한다
- **금액**: 좌석 가격 모델이 없다(설계 밖). 좌석당 고정 금액 하나를 쓴다.

## 설계-4 거부 기록 · 보상 아웃박스 · 멱등 (I1 · I10)

### 4.1 표 (V15 — `db/migration` 기본 위치)

- `confirm_rejection(id, payment_uid UNIQUE, hold_id, user_id, seat_id NULL, schedule_id NULL, code, http_status, rejected_at)`
- `compensation_task(id, payment_uid, type{REFUND|VOID|CAPTURE}, source{REJECTION|SWEEP|CONFIRM}, status{PENDING|IN_PROGRESS|DONE|DEAD}, attempts, next_attempt_at, lease_until, last_result, created_at, done_at, UNIQUE(payment_uid, type))`
- `reservation`
  - `payment_uid` 유니크 인덱스를 새로 둔다(이중 확정 0).
  - **기존 데이터에 중복 uid가 있으면 마이그레이션이 실패한다.** 그래서 V12와 같은 배포 전 R0 확인이 필요하다(`ops/`).
  - 대체 좌석 표시 `origin{HOLD|SUBSTITUTE}`를 둔다.

### 4.2 확정 흐름 (모든 후보에 공통)

```
ConfirmFacade(트랜잭션 없음)
  ① 재생 확인: 그 uid의 예약 또는 거부 기록이 있으면 → 첫 결과를 그대로 돌려준다(N17-3)
  ② 확정 트랜잭션(지금 코드 그대로) — 성공이면 D만 같은 트랜잭션에서 CAPTURE 작업을 만든다(N17-8)
  ③ 거부(404 · 403 · 409)면 → 새 트랜잭션에서 거부 기록 + 보상 작업(후보별: R=REFUND · D=VOID · S=대체 시도 → 실패면 REFUND)
     ③이 실패하면(DB 오류 · 앱 죽음) 무음으로 두지 않는다: ERROR 로그 + 지표. 끝 상태는 안전망(4.4)이 메운다
  ④ 응답: 거부 코드는 그대로 둔다(I11) · 본문에 compensation{id, type, status} 추가 · 조회 GET /api/payments/{uid}/compensation
```

- 같은 uid가 동시에 두 번 오면 유니크 인덱스(예약 · 거부 기록)가 둘째를 막는다. 막힌 쪽은 ①을 다시 읽어 재생 응답을 돌려준다. [구현 검증]
- **같은 uid가 다른 hold로 오는 경우**: 열린 질문 Q3.

### 4.3 워커

- 전용 스케줄러를 쓴다(F7 패턴 · 손잡이 `seat.compensation.*`).
- 한 주기의 순서:
  1. `SELECT … WHERE status = PENDING AND next_attempt_at ≤ now FOR UPDATE SKIP LOCKED LIMIT n`
  2. IN_PROGRESS + `lease_until`로 바꾸고 커밋한다.
  3. PG를 부른다(멱등 키 = uid · 트랜잭션 밖).
  4. 결과에 따라 DONE으로 바꾸거나, attempts를 올리고 백오프로 다음 시도를 잡는다.
- **결과 모름**(타임아웃)이면 다음 시도 전에 `GET /payments/{uid}`로 실제 상태를 확인한다.
- **리스가 만료된 IN_PROGRESS**(앱 죽음)는 다시 PENDING으로 돌린다. PG가 멱등이라 두 번 불러도 결과는 하나다(N17-4).
- 시도 상한을 넘으면 DEAD로 둔다. 지표 + ERROR 로그를 남기고, 판정에서는 '보상 미완'으로 센다.

### 4.4 안전망 — PG 원장 대조 (모든 후보)

- **주기**: PG에서 결제됨 상태이면서 `deadline + G`가 지난 uid를 받는다. 그중 CONFIRMED 예약도 없고 보상 작업도 없는 uid에 REFUND 작업(source = SWEEP)을 만든다(N17-7).
- **이 안전망이 덮는 것**:
  - 거부 기록 쓰기 실패
  - 결제 뒤 클라이언트가 확정을 안 보낸 경우
  - 확정 5xx
  - 앱 죽음
- **G의 근거**: 확정 성공은 `expires_at > now`일 때만 생긴다. 그리고 `deadline = payBy = expiresAt − M`이다. 따라서 `G > M + 시계 차`면, 안전망이 환불을 시작한 uid에 늦은 확정이 성공할 수 없다(N17-5). 단 ④의 `already` 재호출과 대체 좌석은 따로 본다 — Q4 [구현 검증].
- D의 승인 고아는 PG가 `authTtl`에 스스로 취소한다. 안전망 대상이 아니다. D의 이점이다.

## 설계-5 후보 흐름 (I5 · I9)

| 후보 | 클라이언트 | 거부 때 | 성공 때 | 고유 위험 |
|---|---|---|---|---|
| **R 즉시 환불** | payment-start → PG `pay`(deadline = payBy) → 확정 | REFUND 작업 | — | 환불이 끝날 때까지 사용자 돈이 빠져 있다 |
| **D 승인 → 확정 → 매입** | payment-start → PG `authorize`(deadline = payBy) → 확정 | VOID 작업 | 같은 트랜잭션에서 CAPTURE 작업 | 매입 실패나 승인 만료로 돈 없는 좌석이 생긴다(Q5) · 서버가 PG 경로를 둘 더 가진다 |
| **S 대체 좌석** | R과 같다 | 거부 기록 트랜잭션 안에서 같은 회차 · 같은 section의 AVAILABLE 좌석을 `FOR UPDATE SKIP LOCKED`로 잡는다 → RESERVED + 예약(origin = SUBSTITUTE, 같은 uid). 좌석이 없으면 REFUND 작업 | — | 1인 매수 상한 · 카운터를 지켜야 한다(N17-9) · 사용자 동의는 가정한다 · 판매 마감 회차는 대체하지 않는다 |

- 손잡이는 `seat.compensation.mode = none | refund | auth-capture | substitute`이다. 거부 기록과 멱등은 모드와 상관없이 켠다(I1).
- 측정 전 기본값: Q1.

## 설계-6 판정기 확장 (I1 '판정기 고아 결제')

- 끝 상태 판정은 **배수(drain) 창 뒤에** 한다. 창은 보상 큐가 비거나 상한 시간이 될 때까지다. 대조 대상은 PG `GET /ledger` 덤프와 DB(reservation · confirm_rejection · compensation_task)다.
- 기대 0인 항목: 고아 결제 · 이중 확정 · 이중 환불 · 환불됐는데 좌석 있음 · DEAD 작업 · 보상 작업 없는 거부 기록(안전망 뒤).
- 보고 항목: 돈 없는 좌석(D) · 보상 완료 시간 분포 · 사용자 돈 잃은 시간 · PG `DEADLINE_PASSED` 수(I4 — 결제 전에 막힌 수) · 안전망이 만든 작업 수(출처별).
- `/internal/consistency`(ADR-001)에는 DB만으로 셀 수 있는 항목을 넣는다(uid당 CONFIRMED ≤ 1 · 거부 기록 ↔ 보상 작업). PG 대조는 하네스 요약기 `summarize17.py`가 맡는다.
- 세션 분류는 `outcome16`을 넓혀 `wronged → wronged_refunded | wronged_voided | wronged_substituted | wronged_open`으로 나눈다. `wronged_open`은 기대 0이다.

## 설계-7 새 동작 불변식 (설계 선검증 대상)

| # | 불변식 |
|---|---|
| N17-1 | 같은 paymentUid의 CONFIRMED 예약은 1개 이하다(V15 유니크) |
| N17-2 | paymentUid가 있는 확정 거부(404 · 403 · 409)는 확정 트랜잭션 밖에서 거부 기록 1행과 보상 작업 1행(S는 대체 예약 또는 REFUND)을 **한 트랜잭션으로** 남긴다. 쓰기 실패는 무음이 아니다(로그 + 지표) |
| N17-3 | 같은 paymentUid 재요청은 첫 결과(성공 본문, 또는 거부 코드 + 보상 본문)를 돌려주고 상태를 바꾸지 않는다 |
| N17-4 | 보상 작업은 (uid, type)당 1행이다. PG 변경 호출은 uid 멱등이다. 그래서 워커가 같은 작업을 두 번 실행해도 PG 결과는 하나다(이중 환불 0) |
| N17-5 | CONFIRMED 예약이 있는 uid에는 REFUND · VOID 작업이 생기지 않는다. 반대로 REFUND · VOID 작업이 있는 uid는 확정에 성공하지 않는다(재생 · 안전망 G) |
| N17-6 | 흉내 PG는 `now > deadline`인 pay · authorize를 거부하고 원장에 남기지 않는다(I4) |
| N17-7 | 안전망: 결제됨 · 예약 없음 · 보상 없음 · `deadline + G` 지남 ⇒ REFUND 작업(source = SWEEP)이 생긴다 |
| N17-8 | D: 확정 성공 ⇔ 같은 트랜잭션의 CAPTURE 작업. 거부 ⇔ VOID 작업 |
| N17-9 | S: 대체 배정은 1인 매수 상한 · 카운터 · 판매 마감을 지킨다. 못 지키면 REFUND로 넘긴다 |

## 설계-8 측정 설계 — 뼈대 (I3 · I7 · I8 · I12 — 고정값과 문턱은 측정 전 고정 커밋에서)

- **보상을 일부러 많이 만든다**: 새 기본값(④ + ⓒ + PG 강제)에서는 억울이 거의 0이라 보상 경로가 돌지 않는다. 그래서 보상 셀은 **ADR-016 n 흐름 구성**으로 한다(① conditional · payment-start 없음 · PG deadline 없음 — 억울 약 11%). 별도로 **기본 셀**(④ + ⓒ + PG deadline)을 둔다. 기본 셀은 I4를 닫았음을 보인다(억울 0 · DEADLINE_PASSED 수).
- **셀 초안**(후보 R · D · S × 아래 × 3회):

| 셀 | 내용 |
|---|---|
| base | 주입 없음 + 같은 uid 재시도(확정 응답을 비율 r로 버리고 다시 보냄 — 모든 셀에 공통) |
| pg | PG 지연 · 결과 모름 · 일시 실패(capture · void · refund 호출) |
| kill | 회차 중 앱 컨테이너 강제 종료 2회 → 재기동(거부 기록 쓰기 · 워커 IN_PROGRESS · 확정 응답 사이의 창) |
| default | 새 기본값 + PG deadline · R 하나만(I4 확인) |

  예상: 9셀 × 3 + 3 = 30회. 회차당 약 20분이면 약 10시간이다(≤ 12시간 — dry-run으로 확인 · I12).
- **고정 규칙 초안**:
  - **G1 정합**(모든 셀 · 모든 회차): 설계-6의 기대 0 항목이 전부 0이다.
  - **G2 보상 완료**: base 셀 p99 ≤ X초, pg · kill 셀 p99 ≤ Y초. X · Y는 Q6.
  - **G3 비용**: cost 셀 DB CPU · 확정 p99가 R 대비 ≫가 아니다. ADR-014~016과 같은 '범위 비겹침이고 중앙값 비 > 1.10'을 쓴다.
  - **고르는 순서**: Q2.
- **하네스**: ADR-016 하네스 위에 `k6/ADR-017/`을 둔다.
  - paid-flow에 PG 호출을 넣는다.
  - compose 오버레이에 mock-pg를 추가한다.
  - drain 창과 PG 덤프를 넣는다.
  - `outcome17`과 `summarize17`을 만든다.
  - 앱 kill 일정을 넣는다.

## 설계-9 열린 질문 (권장안 1개씩)

| # | 질문 | 권장안 |
|---|---|---|
| Q1 | 측정 전 `seat.compensation.mode` 기본값 | `none`(지금 동작을 유지하고, 거부 기록 · 멱등 · 안전망 탐지만 켬) → 측정 뒤 결정 커밋에서 교체(ADR-015 · 016 선례) |
| Q2 | G1~G3을 통과한 후보 사이의 순서 | **D > R > S** — 사용자 돈을 잃지 않는 쪽을 먼저 둔다. S는 동의 가정이 있어 뒤에 둔다. 단 D의 돈 없는 좌석은 G-비용으로 보고하고 사용자 결정에 맡긴다 |
| Q3 | 같은 uid가 다른 hold · user로 온 확정 | 409 `SEAT_NOT_HELD`로 거부하고 기록하지 않는다(첫 uid의 결과를 덮지 않음). 새 코드는 쓰지 않는다(I11). 지표 `seat.confirm.uid_conflict` |
| Q4 | 안전망 G | `G = M + 60초`(시계 차 · 워커 주기를 덮음). ④ `already`(θ 검사 없음)로 연장 안의 재결제가 가능한 점은 deadline이 PG 결제 시각마다 새 payBy가 되는지로 확인한다 [구현 검증] |
| Q5 | D 매입 실패(승인 만료 · 거절) 때 예약 | 예약은 유지한다. 돈 없는 좌석으로 기록 · 지표 · 보고만 하고, 예약 취소 정책은 범위 밖(02 랩 · 운영 정책) |
| Q6 | G2 문턱 X · Y | 스모크 실측 뒤 측정 전 고정 커밋에서 정한다(ADR-016 W · θ 선례). 초안 X = 30초 · Y = 120초 |
| Q7 | 흉내 PG 원장 저장 | 메모리(측정 중 PG는 죽이지 않음) + 끝 덤프 |
| Q8 | 거부 기록 · 보상 작업 정리 | 이번에는 하지 않는다. 행이 쌓이는 비용은 cost 셀에서 보고, 정리 배치는 NEXT로 |
| Q9 | 5xx(`seat.confirm.failed`) 확정 | 거부 기록을 남기지 않는다(커밋 여부를 모름). 안전망이 맡는다 |

## 설계 밖

- 실제 PG 연동과 02 payment-consistency-lab 통합
- 부분 환불 · 좌석 가격 · 사용자 동의 UI
- 대체 좌석의 등급 · 가격 차이
- 보상 표 정리 배치(Q8)
- 매입 실패 뒤 예약 취소 정책(Q5)

## 실험 결과

미측정.

## 결정

미정.
