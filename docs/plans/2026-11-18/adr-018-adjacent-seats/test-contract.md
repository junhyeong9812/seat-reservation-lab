# 테스트 계약 — seat-reservation-lab ADR-018 1단계 (구현 워커 · blind 워커 공통)

> 설계 정본은 `docs/adr/ADR-018-adjacent-seats.md` 설계-1~8(DV-1~7 반영본)과 명세 `requirement-spec.md` §2 G1~G6이다. 여기서는 그 둘이 정하지 않은 시험 접점만 정한다.

## 1. 설정 옵션

| 키 | 값 | 기본 |
|---|---|---|
| `seat.hold-group.enabled` | `true` / `false` | `false` |
| `seat.hold-group.mode` | `split`(분리형) / `one-shot`(단일 쿼리) | `split` |

- `enabled=false`면 묶음 API 4개(`POST /api/schedules/{scheduleId}/hold-groups` · `DELETE /api/hold-groups/{groupId}` · `POST /api/hold-groups/{groupId}/payment-start` · `POST /api/hold-groups/{groupId}/confirm`)는 404다(본문 코드는 단언하지 않는다). `DELETE /api/holds/{holdId}`는 묶음 설정과 무관하다. 해제 API 두 개는 결제 시작이 있는 확정 모드 `conditional` · `conditional-extend`(기본 조합이 함께 강제됨)에서만 동작하고 그 밖의 구성에서는 404다(2026-11-19 추가 — ADR-018 설계-3).
- `enabled=true`의 기동 검사(설계-7): `seat.confirm.mode`가 `conditional-extend`가 아니거나 `seat.compensation.mode`가 `auth-capture` · `substitute`면 기동이 실패한다. 실패 메시지에 `seat.hold-group.enabled`가 들어간다.

## 2. HTTP

- 사용자 헤더는 기존과 같다(`X-User-Id`). 오류 본문은 기존 1석 API와 같은 모양이고 `code`는 `ErrorCode` 이름이다.
- 새 오류 코드: `INVALID_SEAT_SELECTION`(400) · `HOLD_IN_GROUP`(409) · `PAYMENT_STARTED`(409). 나머지는 기존 이름(`SCHEDULE_CLOSED` · `SEAT_NOT_FOUND` · `SEAT_NOT_AVAILABLE` · `HOLD_LIMIT_EXCEEDED` · `HOLD_NOT_FOUND` · `HOLD_NOT_OWNED` · `HOLD_EXPIRED`).
- 묶음 선점 201 본문: `groupId` · `expiresAt` · `payBy` · `payWithinSeconds` · `holds`(`[{holdId, seatId}]`, `seatId` 오름차순). `payBy` · `payWithinSeconds`의 유무 규칙은 1석 선점 응답과 같다.
- `seatIds`가 없음 · 빈 배열 · 중복 · 한도(`seat.hold.max-per-user`, 시험 기본 2) 초과 · 숫자 아님 = 400 `INVALID_SEAT_SELECTION`. 이 검사는 판매 마감 검사보다 먼저다(본문 검사).
- 해제 2개: 성공 204(본문 없음).
- 묶음 결제 시작 200 본문: `groupId` · `expiresAt` · `payBy` · `payWithinSeconds` · `extended` · `paymentUid`. 재호출은 같은 `paymentUid` · `payBy`.
- 묶음 확정 요청 본문 `{"paymentUid": "..."}`. 200 본문: `status`(`CONFIRMED`) · `reservations`(`[{reservationId, seatId}]`, `seatId` 오름차순). 보상 모드 `refund`에서 거부되면 1석 확정 거부와 같은 오류 모양(`compensation` 포함 규칙도 1석과 같다). 재호출은 같은 결과(같은 `reservations`). 모르는 `paymentUid` · 없는 묶음 · 묶음이나 사용자가 다른 결제 의도 = 409 `SEAT_NOT_HELD`, 거부 기록 없음(2026-11-19 추가 — ADR-018 설계-5).

## 3. DB (V16 — 설계-1의 열 이름 그대로)

- `hold_group(id, schedule_id, user_id, seat_count, created_at)` · `seat_hold.group_id` · `payment_intent.group_id` · `payment_intent.seat_count` · 유니크 `reservation (intent_id, seat_id)`.
- 시험은 `JdbcTemplate`으로 이 표들과 기존 표(`product_seat` · `seat_hold` · `user_hold_quota` · `payment_intent` · `reservation` · `compensation_task`)를 읽어 G1~G5를 행 단위로 확인한다.
- 정합성 검증기 `/internal/consistency` 응답에 정수 필드 `pay_multi_reservation_intents` · `group_partial_holds` · `group_expiry_split` · `group_partial_reservations`가 더해진다(정상이면 0). 기존 필드 이름과 뜻은 그대로다.

## 4. 시각 · PG

- 시각은 기존 `IntegrationTest`의 `MutableClock`(`NOW`)을 쓴다. 만료는 시각을 옮긴 뒤 만료 배치를 직접 부른다(기존 시험과 같은 방식 — adr016 · adr017 시험 참고).
- 보상 · PG 금액은 기존 `adr017/Adr017PgSupport.kt` 방식(mock-pg를 같은 JVM에 띄움)으로 본다. 묶음 등록 금액 = `seat_count × seat.payment.amount`. mock-pg의 조회 API로 등록 금액 · CLOSE 상태를 확인한다.

## 5. 파일 경계

- **구현 워커**: `main-app/src/main/**`(V16 포함) · `main-app/src/test/kotlin/com/jun/labs/seatreservation/adr018impl/**` · `LoadtestDataService` 관련 기존 시험의 기대값 조정이 필요하면 보고 뒤. 기존 시험의 단언은 바꾸지 않는다(G6 — 바꿔야 하면 멈추고 보고). `adr018/`(blind)은 열지 않는다.
- **blind 워커**: `main-app/src/test/kotlin/com/jun/labs/seatreservation/adr018/**`만 쓴다. `main-app/src/main/**`(단 `src/main/resources/application.yml`은 읽어도 된다) · `adr018impl/**`은 열지 않는다. 기존 시험 코드(`src/test/**`)와 `mock-pg/**`는 읽어도 된다.
- **메인이 이미 고친 공용 파일**: `support/IntegrationTest.kt` `resetData`가 `hold_group`을 지운다(표가 있을 때만).
- 공통(읽기만): 명세 · ADR-018 · 이 문서 · `build.gradle.kts`.
