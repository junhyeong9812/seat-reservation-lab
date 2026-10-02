# ADR-000: 좌석 예약 도메인과 순수 구현 (baseline)

> 번호 변경(2026-09-29): 구 ADR-002~009 → ADR-003~010. 새 ADR-002 = DB 기준선 보정(인덱스·커넥션 풀). 이 문서의 번호 참조는 새 번호로 갱신했다.

- 상태: 채택 (Accepted)
- 날짜: 2026-09-27
- 성격: **기준선**. 동시성 제어 없이 "기능이 되는" 가장 평범한 구현을 만든다. 이후 ADR-001~은 이 기준선에서 문제를 관측하고 하나씩 해결한다.
- 아키텍처: 레이어드(api / service / domain) + DDD 애그리거트 (§2.1)

## 1. 왜 기준선부터 만드는가

해법을 먼저 정하면 ADR의 "선택 이유"가 추론만 남는다. 이 랩은 다음 순서를 따른다.

```
ADR-000 순수 구현 ──▶ k6 시나리오로 문제 관측 ──▶ ADR-00X: 선택지별 실험·실측 ──▶ 해결 확인
   (기능은 됨)          (수치로 깨짐을 보임)          (같은 시나리오·같은 판정 기준)
```

기준선의 두 가지 원칙:

- **평범할 것**: 평범한 개발자가 JPA로 처음 짜는 코드. `@Transactional` 안에서 **조회 → 애플리케이션에서 비교 → 저장**. 허수아비 코드(일부러 망가뜨린 코드)가 아니다.
- **문제를 가리지 않을 것**: DB는 조회·저장만 한다. 스키마에는 서로게이트 PK와 FK만 둔다. 중복을 막는 유니크 제약·부분 인덱스·조건부 쿼리·락은 두지 않는다 — 그것들은 이후 ADR의 **해결책 후보**다. 기준선에 넣으면 문제가 재현되지 않는다.

## 2. 도메인 모델

```
product (상품: 공연)
  1
  │
  N
product_schedule (상품회차: 공연 × 일시)        ← 재고의 단위
  1
  │
  N
product_seat (상품좌석)                         ← 상태 AVAILABLE / HELD / RESERVED
  1                       1
  │                       │
  0..N                    0..N
seat_hold (홀드)         reservation (예약)
  user_id, expires_at      user_id, payment_uid, CONFIRMED / CANCELED
```

| 결정 | 내용 | 이유 |
|------|------|------|
| 상품 vs 회차 | 판매 단위는 **회차의 좌석**이다 | 같은 A-12도 회차가 다르면 다른 재고 |
| 물리 좌석 | 공연장 배치도를 분리하지 않고 구역·열·번호를 `product_seat`에 둔다 | 랩 범위에서 공연장 재사용 불필요 |
| 홀드 vs 예약 분리 | 홀드는 TTL이 있는 일시 점유, 예약은 결제로 확정된 사실 | 수명이 다르다. 합치면 Q6(결제 성공 + 선점 만료)을 표현할 수 없다 |
| 좌석 상태 | 좌석에 `AVAILABLE` / `HELD` / `RESERVED`를 **저장**한다 | 가장 직관적인 모델. 만료 후 `HELD` 잔존 문제는 ADR-005에서 관측 |
| 홀드 상태 | 상태 컬럼 없음. 확정·만료되면 **행을 삭제**한다 | 살아 있는 홀드만 행으로 존재 |
| 예약 상태 | `CONFIRMED` / `CANCELED` | 예약 1행 = 좌석 1석. 결제 uid를 가진다 |

- 기준선에서 `seat_hold`·`reservation`의 좌석 대비 카디널리티가 `0..N`인 것은 **의도**다. 설계상 좌석당 활성 홀드·확정 예약은 1개여야 하지만, 그것을 스키마로 강제하지 않는다. 깨지는지 관측하는 것이 목적이다.

### 2.1 애그리거트

애그리거트 = **한 트랜잭션에서 함께 일관돼야 하는 것의 경계**. 이후 ADR의 락은 이 경계를 보호하는 수단이다.

```
┌──────────┐   ┌───────────┐   ┌──────────────────────────┐   ┌───────────────────┐
│ Product  │   │ Schedule  │   │ Seat (루트)               │   │ Reservation (루트) │
│          │◀──│ productId │◀──│ scheduleId               │◀──│ seatId            │
│          │   │ startsAt  │   │ position (구역·열·번호)    │   │ scheduleId        │
└──────────┘   └───────────┘   │ status                   │   │ userId            │
                               │ └─ SeatHold (내부, 0..1)  │   │ paymentUid        │
                               │      userId, heldAt,     │   │ status            │
                               │      expiresAt           │   └───────────────────┘
                               └──────────────────────────┘
                   ◀── = 다른 애그리거트 루트를 ID로 참조
```

| 애그리거트 | 루트 | 내부 | 지키는 불변식 | 경계를 이렇게 그은 이유 |
|-----------|------|------|--------------|----------------------|
| **Product** | `Product` | — | 상품 정보 | 판매 중에 거의 변하지 않는 카탈로그 |
| **Schedule** | `ProductSchedule` | — | 회차 정보 | 좌석이 회차를 참조한다. 다른 애그리거트는 **루트만** ID로 참조하므로 Product 안에 두지 않는다 |
| **Seat** | `ProductSeat` | `SeatHold` | ① 전이는 `AVAILABLE → HELD → RESERVED`, 만료 시 `HELD → AVAILABLE`만 ② `HELD`이면 현재 홀드가 정확히 1개, 아니면 0개 ③ 확정은 홀더 본인이, 만료 전에만 | 좌석 상태와 홀드는 항상 함께 바뀐다. 홀드를 분리하면 "HELD ⇔ 홀드 존재"가 두 애그리거트에 걸쳐 어느 쪽도 지키지 못한다 |
| **Reservation** | `Reservation` | — | `CONFIRMED → CANCELED` | 결제 이후의 별도 수명(취소·환불). 좌석에 넣으면 취소 이력까지 좌석이 떠안는다 |

- **회차는 좌석을 품지 않는다**: 회차당 좌석 10,000개를 한 애그리거트로 묶으면 모든 선점이 회차 하나를 두고 경합한다. **경합 단위는 좌석 하나**다.
- **홀드 저장**: 애그리거트 내부 엔티티지만 `seat_hold` 테이블에 따로 둔다(좌석 루트가 생명주기를 소유 — 확정·만료 시 루트가 제거). DB는 좌석당 홀드 행 수를 제한하지 않는다 — 불변식 ②가 동시 요청에서 깨지는지 관측하기 위해서다.
- **리포지토리는 루트마다 하나**: `ProductRepository` · `ProductScheduleRepository` · `ProductSeatRepository` · `ReservationRepository`. 홀드 전용 리포지토리는 없다 — 홀드는 좌석을 통해서만 바뀐다.

#### 애그리거트 하나로 지킬 수 없는 규칙

| 규칙 | 걸친 애그리거트 | 기준선 처리 | 감수한 것 |
|------|---------------|------------|----------|
| 확정 = 좌석 `RESERVED` + 예약 생성 | Seat, Reservation | **같은 트랜잭션**에서 둘 다 바꾼다 — "트랜잭션 하나에 애그리거트 하나" 원칙의 **의도된 예외** | Q7 불변식(`RESERVED` 수 = `CONFIRMED` 수)이 원자성을 요구하므로 최종 일관성(도메인 이벤트)보다 우선했다. 대가: 두 애그리거트의 락이 한 트랜잭션에 묶인다 |
| 1인 최대 2매 | 여러 Seat + Reservation | 도메인 서비스 `HoldLimitPolicy`가 조회해 판정 | 어느 애그리거트도 보호하지 않으므로 동시 요청에 무방비 → ADR-004 |

- 애그리거트가 규칙을 가져도 **동시성 문제는 남는다**: `seat.hold()`의 상태 검사는 트랜잭션이 읽어 온 스냅샷 위에서 일어난다. 두 트랜잭션이 각자 `AVAILABLE`을 읽으면 둘 다 통과한다. 애그리거트는 "무엇이 일관돼야 하는가"를 정하고, "동시에 어떻게 지키는가"는 ADR-003 이후가 정한다.

## 3. 상태 전이

```
             선점 (hold)                         확정 (confirm)
 AVAILABLE ──────────────▶ HELD ──────────────────────────▶ RESERVED
     ▲                      │      + 홀드 삭제 + 예약 CONFIRMED
     │   만료 배치 (sweep)   │
     └──────────────────────┘
          + 홀드 삭제
```

| 전이 | 트리거 | 기준선 절차 (한 트랜잭션) |
|------|--------|--------------------------|
| 선점 | `POST` 선점 API | 좌석 애그리거트 로드 → `seat.assertHoldable()` (`AVAILABLE` 검사 — 에러 우선순위상 매수 초과보다 먼저) → `HoldLimitPolicy`로 (홀드 수 + 확정 예약 수) < 2 확인 → `seat.hold(userId, now, ttl)` (`HELD` + 홀드 생성) → 저장 |
| 확정 | `POST` 확정 API (결제 성공 후 호출) | 홀드 id로 좌석 애그리거트 로드 → `seat.confirm(userId, now)` (내부에서 **2중 확인**: 내 홀드·만료 전 + `HELD` → `RESERVED` + 홀드 제거) → `Reservation` 생성·저장 |
| 만료 | `@Scheduled` 배치 | 만료 홀드를 가진 좌석 로드 → 각 `seat.expireHold(now)` (만료된 홀드만 제거 + `AVAILABLE`) |

- 상태 검사와 전이는 **애그리거트 메서드 안**에 있다. 서비스(UseCase)는 로드 → 메서드 호출 → 저장 순서만 조율한다. 상태는 외부에서 직접 바꿀 수 없다.
- 그래도 확인은 **읽어 온 스냅샷 위에서의 비교**다(check-then-act). 확인과 저장 사이에 다른 요청이 끼어들 수 있다 — 기준선은 이를 막지 않는다(ADR-003·004·006에서 관측).
- 만료 판정: `expiresAt <= now` 이면 만료. 시각은 애플리케이션 `Clock`에서 얻는다(테스트에서 고정 가능).

## 4. 규칙

| 규칙 | 값 | 설정 |
|------|----|------|
| 홀드 TTL | 5분 (README Load Profile 원본) | `seat.hold.ttl` |
| 1인 최대 매수 | (사용자, 회차)당 2매 = 홀드 + 확정 예약 | `seat.hold.max-per-user` |
| 만료 배치 주기 | 기본 10초 | `seat.hold.expiry-interval` |
| 1회 선점 매수 | 1석 (연석은 ADR-008) | — |

## 5. DB 스키마 (Flyway `V1__init.sql`)

```sql
CREATE TABLE product (
    id    BIGSERIAL    PRIMARY KEY,
    name  VARCHAR(200) NOT NULL
);

CREATE TABLE product_schedule (
    id          BIGSERIAL   PRIMARY KEY,
    product_id  BIGINT      NOT NULL REFERENCES product (id),
    starts_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE product_seat (
    id           BIGSERIAL   PRIMARY KEY,
    schedule_id  BIGINT      NOT NULL REFERENCES product_schedule (id),
    section      VARCHAR(20) NOT NULL,
    row_no       INT         NOT NULL,
    seat_no      INT         NOT NULL,
    status       VARCHAR(20) NOT NULL
);

CREATE TABLE seat_hold (
    id           BIGSERIAL   PRIMARY KEY,
    seat_id      BIGINT      NOT NULL REFERENCES product_seat (id),
    schedule_id  BIGINT      NOT NULL REFERENCES product_schedule (id),
    user_id      BIGINT      NOT NULL,
    held_at      TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE reservation (
    id           BIGSERIAL   PRIMARY KEY,
    schedule_id  BIGINT      NOT NULL REFERENCES product_schedule (id),
    seat_id      BIGINT      NOT NULL REFERENCES product_seat (id),
    user_id      BIGINT      NOT NULL,
    payment_uid  VARCHAR(64) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);
```

- 의도적으로 **없는 것**: `seat_hold(seat_id)` 유니크, 확정 예약 부분 유니크, `status` CHECK, 조회용 인덱스. 인덱스 부재가 성능 측정을 왜곡하면 해당 ADR에서 V2 이후로 추가하고 before/after를 기록한다.

## 6. API

사용자 식별은 헤더 `X-User-Id` (인증은 이 랩의 범위 밖).

| 메서드 | 경로 | 요청 | 성공 | 실패 |
|--------|------|------|------|------|
| POST | `/api/schedules/{scheduleId}/seats/{seatId}/hold` | — | `201` `{holdId, seatId, expiresAt}` | `404` 좌석 없음 · `409` 선점 불가(`SEAT_NOT_AVAILABLE`) · `409` 매수 초과(`HOLD_LIMIT_EXCEEDED`) |
| POST | `/api/holds/{holdId}/confirm` | `{paymentUid}` | `200` `{reservationId, seatId, status}` | `404` 홀드 없음 · `403` 남의 홀드 · `409` 만료(`HOLD_EXPIRED`) · `409` 좌석 상태 불일치(`SEAT_NOT_HELD`) |

- 결제 자체는 이 랩의 범위 밖(→ 02 payment-consistency-lab). 확정 API는 "결제가 성공했다"는 결과로 `paymentUid`를 받는다.

## 7. 코드 구조 (레이어드 + DDD 전술 패턴)

```
com.jun.labs.seatreservation
├── api/                     컨트롤러 · 요청/응답 DTO · 예외 → HTTP 매핑 · 만료 스케줄러(진입점)
├── service/                 UseCase 인터페이스 · 커맨드/결과
│   └── impl/                UseCase 구현체 — 애그리거트 로드 → 도메인 메서드 호출 → 저장 (조율만)
└── domain/                  애그리거트(루트·내부 엔티티) · 값 객체 · 상태 enum
    │                        도메인 서비스(HoldLimitPolicy) · 도메인 예외
    └── repository/          애그리거트 루트별 Spring Data JPA 인터페이스
```

- 의존 방향: `api → service → domain`. 도메인은 서비스·API를 모른다 — 그래서 도메인 예외(`ErrorCode` 포함)는 `domain`에 둔다.
- 쿼리는 쓰지 않는다. 파생 쿼리 메서드(`findBy…`, `countBy…`)와 애그리거트 저장만 쓴다.

## 8. 기준선이 일부러 남겨 둔 약점 → 가설 ADR

| 약점 | 가설 ADR |
|------|---------|
| 판정 기준 없이는 "해결됐다"를 말할 수 없다 | [ADR-001](ADR-001-consistency-oracle.md) 정합성 판정·관측 하네스 (Q7) |
| 같은 좌석 동시 선점 시 check-then-act | [ADR-003](ADR-003-same-seat-contention.md) (Q1) |
| 같은 사용자 동시 요청 시 매수 COUNT 경합 | [ADR-004](ADR-004-per-user-limit.md) |
| 저장된 `HELD`와 배치 만료의 지연 | [ADR-005](ADR-005-hold-expiry.md) (Q3) |
| 확정 시 2중 확인과 전환 사이 경합 | [ADR-006](ADR-006-confirm-atomicity.md) (Q6) |
| 결제는 성공했는데 확정 실패 | [ADR-007](ADR-007-confirm-failure-compensation.md) (Q6) |
| 연석 부분 선점 | [ADR-008](ADR-008-adjacent-seats.md) (Q2) |
| 좌석맵 조회와 실제 상태 불일치 | [ADR-009](ADR-009-seat-map-query.md) (Q5) |
| 오픈 정각 트래픽 폭주 | [ADR-010](ADR-010-open-spike.md) (Q4) |
