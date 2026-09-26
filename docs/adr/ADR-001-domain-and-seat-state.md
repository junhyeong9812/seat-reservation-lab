# ADR-001: 도메인 계층과 좌석 상태의 원천

- 상태: 제안 (Proposed)
- 날짜: 2026-09-27
- 대응 질문: Q1 · Q3 · Q5 · Q6 · Q7 (README Questions)
- 범위: 도메인 계층 · 좌석 상태 모델 · DB 스키마 · 기본 사용자 시나리오
- 범위 밖: 락 방식 비교(→ ADR-002) · 결제 성공 후 전환 실패 보상(→ ADR-006) · 대기열 · 좌석맵 캐시

## 1. 배경

좌석 10,000석, 피크 동접 200,000명(README 채택 기준). 좌석 예매에서 갈리는 지점은 "같은 좌석을 누가 갖는가"와 "잠깐 잡아둔 좌석이 언제 풀리는가"다. 이 둘을 어떤 데이터로 표현하느냐가 이후 모든 질문(경합·만료·조회 불일치·정합성 검증)의 토대가 되므로 락 방식보다 먼저 정한다.

## 2. 결정 요약

| # | 결정 |
|---|------|
| D1 | 도메인은 **상품 > 상품회차 > 상품좌석** 3계층 + **홀드**, **예약** — 총 5개 테이블 |
| D2 | 좌석은 영속 사실만 저장한다: `AVAILABLE` / `RESERVED`. **홀드 여부는 저장하지 않고 조회 시점에 유도한다 (lazy)** |
| D3 | 홀드는 **상태 컬럼이 없다**. 좌석당 최대 1행, 만료된 행은 다음 홀드가 덮어쓴다(upsert). 끝난 홀드는 삭제한다 |
| D4 | 결제 시점의 2중 확인(내 홀드 유효 + 미예약)은 **별도 SELECT 없이 전환 문장의 WHERE 조건으로** 한다 — 확인과 전환은 원자적 |
| D5 | 홀드 TTL = **5분** (README Load Profile 원본 유지) |
| D6 | 1인 최대 2매 = (사용자, 회차) 기준 **활성 홀드 + 확정 예약 COUNT**, 같은 사용자 요청은 **advisory lock으로 직렬화** |
| D7 | 결제 성공 후 전환 실패 시 보상은 **ADR-006으로 이연** |

## 3. 도메인 모델

```
product (상품: 공연)
  1
  │
  N
product_schedule (상품회차: 공연 × 일시)          ← 재고의 단위
  1
  │
  N
product_seat (상품좌석: 회차의 좌석 한 장)         ← 경합 대상, 상태 AVAILABLE / RESERVED
  1                        1
  │                        │
  0..1                     0..N (확정은 0..1)
seat_hold (홀드)          reservation (예약)
  user_id, expires_at       user_id, payment_uid, CONFIRMED / CANCELED
```

- **상품 vs 상품회차**: 같은 A-12라도 회차가 다르면 다른 재고다. 판매 단위는 회차의 좌석이다.
- **물리 좌석(공연장 배치도)은 분리하지 않는다**: 구역·열·번호를 `product_seat`에 직접 둔다. 랩 범위에서 공연장 재사용이 필요 없으므로 계층을 줄였다. 배치도 공유가 필요해지면 `venue_seat`를 분리한다.
- **홀드와 예약을 분리한 이유**: 수명이 다르다. 홀드는 TTL이 있는 일시 점유, 예약은 결제로 확정된 사실이다. 합치면 Q6(결제 성공 + 선점 만료)을 표현할 수 없다.
- **예약 1행 = 좌석 1석**: 2매 결제는 같은 `payment_uid`를 가진 예약 2행이다.

## 4. 좌석 상태 — 원천과 전이

### 4.1 선택지

| 선택지 | "좌석이 비었다"의 판정 | 장점 | 단점 |
|--------|----------------------|------|------|
| A. 좌석에 `HELD` 저장 + sweep 배치가 만료분을 되돌림 | `status = AVAILABLE` | 조회가 단순(좌석 테이블만) | 배치 주기만큼 만료된 좌석이 `HELD`로 남아 **실시간성이 무너진다**. "만료됐는데 HELD" 불일치가 구조적으로 생긴다 |
| B. 좌석에 `HELD` 저장 + 조회 시 만료 판정 (lazy) | `AVAILABLE` 또는 (`HELD` 이고 만료) | 실시간 | 저장된 `HELD`가 거짓일 수 있다 — 상태 컬럼과 홀드 만료 시각 두 곳을 항상 같이 읽어야 한다 |
| C. 혼합 (판정 lazy + 정리 sweep) | B와 같음 | B + 저장값이 결국 정리됨 | 같은 규칙이 판정·배치 두 곳에 생겨 한쪽만 고치는 버그 여지 |
| **D. 좌석엔 영속 사실만, 홀드는 유도 (lazy)** | `status = AVAILABLE` 이고 활성 홀드 없음 | 실시간. **저장된 값이 거짓일 수 없다** | 조회 시 홀드 테이블 조인 필요. 경합 지점이 좌석이 아니라 홀드 행으로 이동 |

### 4.2 선택: D (lazy, 유도)

- 이유 1 — **실시간성**: 만료 판정이 조회 시점의 `now()`로 이뤄지므로 만료 정각에 곧바로 빈 좌석으로 보인다. 배치 지연에 좌석이 묶이지 않는다.
- 이유 2 — **거짓 상태 제거**: `HELD`를 저장하지 않으므로 README Consistency Checks의 "만료됐는데 HELD로 남은 좌석"은 구조적으로 0이다(검증 항목이 아니라 불가능한 상태가 된다).
- 이유 3 — **규칙 한 곳**: 활성 홀드의 정의(`expires_at > now()`)가 한 곳에만 있다.
- 감수한 단점: 좌석맵 조회마다 홀드 조인이 붙는다(Q5 캐시 ADR에서 다룬다). Q1의 경합이 홀드 행 upsert로 옮겨간다.
- 배치의 역할: 정합성과 무관한 **청소(GC)** 뿐이다. D3의 덮어쓰기 모델에서 홀드 행은 좌석당 최대 1개(최대 10,000행)라 청소 없이도 크기가 묶인다.

### 4.3 판정 규칙

- 활성 홀드: `expires_at > now()`. **만료 정각에는 이미 만료**다.
- 시각의 단일 출처는 **DB `now()`**다. 앱 서버 시계는 쓰지 않는다(서버 간 시계 차이 배제). `now()`는 트랜잭션 시작 시각이므로 트랜잭션은 짧게 유지한다.

| 조회 결과 | 조건 |
|-----------|------|
| 예약됨 | `product_seat.status = 'RESERVED'` |
| 선택 불가(홀드) | `AVAILABLE` 이고 활성 홀드 있음 |
| 선택 가능 | `AVAILABLE` 이고 활성 홀드 없음 |

### 4.4 전이

```
                 홀드 생성 (upsert 성공)
  선택 가능 ─────────────────────────────▶ 선택 불가(홀드)
      ▲                                     │   │
      │  만료 (시간 경과 — 쓰기 없음)         │   │ 결제 성공 → 전환 (D4)
      └─────────────────────────────────────┘   ▼
      ▲  사용자 선택 취소 (홀드 DELETE)          예약됨 (RESERVED)
      │                                         │
      └──────────── 예약 취소 (CANCELED + AVAILABLE) ◀┘
```

- 저장 상태가 바뀌는 것은 **전환**과 **예약 취소** 두 경우뿐이다. 홀드의 생성·만료·취소는 좌석 행을 건드리지 않는다.

## 5. DB 스키마 (PostgreSQL)

```sql
CREATE TABLE product (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL
);

CREATE TABLE product_schedule (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT      NOT NULL REFERENCES product (id),
    starts_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE product_seat (
    id           BIGSERIAL PRIMARY KEY,
    schedule_id  BIGINT      NOT NULL REFERENCES product_schedule (id),
    section      VARCHAR(20) NOT NULL,
    row_no       INT         NOT NULL,
    seat_no      INT         NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
                 CHECK (status IN ('AVAILABLE', 'RESERVED')),
    UNIQUE (schedule_id, section, row_no, seat_no)
);

-- 좌석당 최대 1행. 상태 컬럼 없음 — 활성 여부는 expires_at > now()
CREATE TABLE seat_hold (
    seat_id      BIGINT      PRIMARY KEY REFERENCES product_seat (id),
    schedule_id  BIGINT      NOT NULL REFERENCES product_schedule (id),
    user_id      BIGINT      NOT NULL,
    held_at      TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_seat_hold_user ON seat_hold (schedule_id, user_id);

CREATE TABLE reservation (
    id           BIGSERIAL PRIMARY KEY,
    schedule_id  BIGINT      NOT NULL REFERENCES product_schedule (id),
    seat_id      BIGINT      NOT NULL REFERENCES product_seat (id),
    user_id      BIGINT      NOT NULL,
    payment_uid  VARCHAR(64) NOT NULL,
    status       VARCHAR(20) NOT NULL CHECK (status IN ('CONFIRMED', 'CANCELED')),
    created_at   TIMESTAMPTZ NOT NULL,
    canceled_at  TIMESTAMPTZ,
    UNIQUE (payment_uid, seat_id)
);
-- 좌석당 확정 예약은 최대 1건 — 앱 로직이 뚫려도 DB가 막는 최후 방어선
CREATE UNIQUE INDEX uq_reservation_confirmed_seat
    ON reservation (seat_id) WHERE status = 'CONFIRMED';
CREATE INDEX idx_reservation_user ON reservation (schedule_id, user_id);
```

- `seat_hold.schedule_id`는 `product_seat`에서 유도 가능한 중복이지만, D6의 (사용자, 회차) COUNT를 조인 없이 인덱스로 끝내기 위해 둔다.
- 활성 홀드 1개 제약은 시간 조건이라 부분 유니크 인덱스로 표현할 수 없다(인덱스 조건에 `now()` 불가). 그래서 **PK(seat_id)로 행을 1개로 묶고 만료 행을 덮어쓰는** D3 모델을 택했다. 반면 확정 예약의 조건(`status = 'CONFIRMED'`)은 정적이라 부분 유니크 인덱스가 가능하다.

## 6. 사용자 시나리오

```
좌석맵 조회 ──▶ 좌석 선택 ──▶ 결제 클릭 ──▶ 결제 성공 ──▶ 예약 확정
   (4.3 판정)     홀드 생성        홀드 유효 확인      외부 결제       전환 (D4)
                  ← Q1 경합        ← Q6 지점                          실패 시 → ADR-006
```

### 6.1 좌석 선택 = 홀드 생성

한 트랜잭션, 락 순서 고정: **① 사용자 advisory lock → ② 좌석 행 → ③ 홀드 행**.

```sql
BEGIN;
-- ① 같은 사용자·회차 요청만 직렬화 (D6)
SELECT pg_advisory_xact_lock(:scheduleId, :userId);

-- ② 1인 2매 확인: 활성 홀드 + 확정 예약
SELECT (SELECT count(*) FROM seat_hold
         WHERE schedule_id = :scheduleId AND user_id = :userId AND expires_at > now())
     + (SELECT count(*) FROM reservation
         WHERE schedule_id = :scheduleId AND user_id = :userId AND status = 'CONFIRMED');
-- 결과 + 요청 매수 > 2 → 거절, ROLLBACK

-- ③ 좌석이 예약되지 않았음을 확인하며 좌석 행에 공유 락
SELECT 1 FROM product_seat WHERE id = :seatId AND status = 'AVAILABLE' FOR SHARE;
-- 0행 → 이미 예약됨, ROLLBACK

-- ④ 홀드: 빈 좌석이면 INSERT, 만료 홀드면 덮어쓰기, 살아 있는 홀드면 0행
INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at)
VALUES (:seatId, :scheduleId, :userId, now(), now() + interval '5 minutes')
ON CONFLICT (seat_id) DO UPDATE
   SET user_id = EXCLUDED.user_id, held_at = EXCLUDED.held_at, expires_at = EXCLUDED.expires_at
 WHERE seat_hold.expires_at <= now();
-- 영향 행 0 → 다른 사용자가 홀드 중, ROLLBACK
COMMIT;
```

- ③의 공유 락은 **예약된 좌석 위에 홀드가 생기는 경합**을 막는다. 전환(6.2)이 좌석 행을 먼저 잠그므로, 전환 중인 좌석에 대한 홀드는 ③에서 대기했다가 `RESERVED`를 보고 거절된다.
- 2매(연석) 요청은 좌석마다 ③·④를 반복하고 하나라도 실패하면 전체 ROLLBACK(all-or-nothing). 연석 판정·부분 실패의 세부는 Q2 ADR에서 다룬다.

### 6.2 결제 클릭 → 결제 성공 → 전환 (2중 확인)

확인해야 할 것은 두 가지다: **(1) 이 좌석의 홀드가 아직 내 것이고 살아 있다 (2) 좌석이 아직 예약되지 않았다.**

**경합 주의 — 확인과 전환 사이에 상태가 바뀔 수 있다.** SELECT로 확인한 뒤 UPDATE로 전환하면(check-then-act), 두 문장 사이에 홀드가 만료되고 다른 사용자가 그 좌석을 덮어쓸 수 있다. 따라서 **확인은 전환과 원자적으로 묶는다: 별도 확인 SELECT를 두지 않고, 전환 문장의 WHERE 조건이 곧 확인이다.** 각 조건부 문장은 1회 실행하고 영향 행 수로 성공을 판정한다.

```sql
BEGIN;
-- (2) 미예약 확인 + 전환 — 좌석 행을 먼저 잠근다 (락 순서: 좌석 → 홀드)
UPDATE product_seat SET status = 'RESERVED'
 WHERE id = :seatId AND status = 'AVAILABLE';
-- 영향 행 0 → 이미 예약됨, ROLLBACK

-- (1) 내 홀드 유효 확인 + 소비 — 조건에 맞는 행만 지운다
DELETE FROM seat_hold
 WHERE seat_id = :seatId AND user_id = :userId AND expires_at > now();
-- 영향 행 0 → 홀드 만료 또는 남의 것, ROLLBACK

INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at)
VALUES (:scheduleId, :seatId, :userId, :paymentUid, 'CONFIRMED', now());
COMMIT;
```

- 두 조건부 문장이 모두 1행이어야 커밋한다. 하나라도 0행이면 ROLLBACK — 좌석은 원래 상태 그대로다.
- **결제 클릭 시점**에도 같은 (1)·(2) 조건을 읽기 전용으로 확인해 사용자에게 조기에 알릴 수 있지만, 그것은 안내일 뿐 보장이 아니다. 보장은 위 전환 트랜잭션뿐이다.
- 결제는 외부에서 이미 성공했는데 위 트랜잭션이 ROLLBACK되는 경우(결제 도중 홀드 만료 → 다른 사용자가 선점)는 환불 등 **보상이 필요하다 → ADR-006**.

### 6.3 선택 취소 · 예약 취소

```sql
-- 선택 취소: 내 홀드만 지운다 (좌석 행 불변)
DELETE FROM seat_hold WHERE seat_id = :seatId AND user_id = :userId;

-- 예약 취소: 한 트랜잭션, 좌석 → 예약 순
UPDATE product_seat SET status = 'AVAILABLE' WHERE id = :seatId AND status = 'RESERVED';
UPDATE reservation SET status = 'CANCELED', canceled_at = now()
 WHERE seat_id = :seatId AND user_id = :userId AND status = 'CONFIRMED';
```

## 7. 1인 최대 2매 (D6)

- 기준: (사용자, 회차). 셈 = **활성 홀드 + 확정 예약**. 홀드를 세지 않으면 한 사람이 좌석 여러 개를 5분씩 묶어둘 수 있다(매크로 사재기).
- 문제: COUNT 후 INSERT는 check-then-act다. 같은 사용자가 탭 두 개로 동시에 요청하면 READ COMMITTED에서 서로의 미커밋 INSERT가 안 보여 둘 다 통과한다(3매). 잠글 대상이 아직 없는 행이라 행 락으로도 못 막는다.

| 선택지 | 동작 | 단점 |
|--------|------|------|
| **advisory lock (선택)** | `pg_advisory_xact_lock(회차, 사용자)` → COUNT → INSERT | Postgres 전용. 락 키 공간이 DB 전역 — 다른 용도와 키가 겹치지 않게 관리 필요 |
| 쿼터 행 `FOR UPDATE` | (사용자, 회차) 쿼터 행에 행 락 | 테이블 추가, 첫 요청 시 행 생성 자체가 또 경합 |
| 카운터 조건부 UPDATE (`cnt + n <= 2`) | 원자적 증가 | lazy 만료 시 카운터를 내려줄 주체가 없어 값이 어긋난다 |
| SERIALIZABLE | DB가 충돌 감지 후 한쪽 실패 | 재시도 로직 필요, 오픈 시점 경합에서 실패율 급증 |
| 한계로 수용 | 단순 COUNT | 동시 요청 시 3매 이상, 사후 검증으로만 탐지 |

- 선택 이유: COUNT 조회라는 판정 방식을 그대로 두고 **같은 (사용자, 회차) 요청만** 줄 세운다. 다른 사용자끼리는 서로 막지 않아 좌석 경합 경로에 병목을 만들지 않는다.
- `_xact_` 버전: 트랜잭션 종료 시 자동 해제 — 커넥션 풀에 락이 남지 않는다.
- 전환(6.2)·취소(6.3)는 개수를 늘리지 않으므로 advisory lock을 잡지 않는다.

## 8. 불변식

| # | 불변식 | 강제 위치 |
|---|--------|----------|
| I1 | 한 좌석에 활성 홀드는 최대 1개 | `seat_hold` PK + upsert의 `WHERE expires_at <= now()` |
| I2 | 한 좌석에 확정 예약은 최대 1건 | `uq_reservation_confirmed_seat` 부분 유니크 인덱스 + 전환의 조건부 UPDATE |
| I3 | `RESERVED` 좌석 수 = `CONFIRMED` 예약 수 | 전환·취소가 좌석과 예약을 한 트랜잭션에서 함께 바꾼다 |
| I4 | 예약된 좌석에는 활성 홀드가 생기지 않는다 | 6.1 ③ 좌석 공유 락 + 락 순서(좌석 → 홀드) |
| I5 | (사용자, 회차)당 활성 홀드 + 확정 예약 ≤ 2 | advisory lock 직렬화 + COUNT |
| I6 | 만료 판정은 `expires_at > now()` 한 곳, 시각은 DB 기준 | 4.3 |

## 9. 결과

- 좋아지는 것: 저장 상태가 거짓일 수 없다. 만료가 실시간이다. 배치 없이도 정합성이 유지된다.
- 나빠지는 것: 좌석맵 조회에 홀드 조인이 붙는다. 모든 좌석 경로가 락 순서(사용자 → 좌석 → 홀드)를 지켜야 한다 — 순서를 어기면 데드락.
- ADR-002에 주는 제약: Redis `SET NX EX` 방식은 홀드의 원천을 DB 밖으로 옮긴다. 그 경우 I4·I5를 DB 트랜잭션으로 강제할 수 없게 되므로, ADR-002는 이 차이를 비교 항목으로 다뤄야 한다.

## 10. 구현 시 검증할 것

문서만으로 판정할 수 없어 구현 단계의 동시성 테스트로 확인할 항목.

- [ ] 6.1 ③ `FOR SHARE`가 동시 전환과 경합할 때 대기 후 `RESERVED`를 보고 0행이 되는가 (READ COMMITTED 재평가 동작)
- [ ] 만료 직후 원 소유자의 전환과 새 사용자의 덮어쓰기가 동시에 들어올 때 둘 중 하나만 성공하는가
- [ ] 락 순서를 지켰을 때 홀드·전환·취소 혼합 부하에서 데드락이 0건인가
- [ ] 같은 사용자 동시 요청 N개에서 I5가 유지되는가

## 11. 이연 · 후속 ADR

- ADR-002: Q1 락 방식 비교 — 조건부 UPDATE / 비관락 / 낙관락 / Redis SET NX
- ADR-006: 결제 성공 후 전환 실패 시 보상
- Q2 ADR: 연석 판정과 부분 실패
- Q5 ADR: 좌석맵 조회 캐시와 불일치 허용 범위
