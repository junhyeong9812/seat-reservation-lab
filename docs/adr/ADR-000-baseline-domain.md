# ADR-000: 좌석 예약 도메인과 순수 구현 (baseline)
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

> 번호 변경(2026-09-29): 구 ADR-002~009 → ADR-003~010. 새 ADR-002 = DB 기준선 보정(인덱스·커넥션 풀). 이 문서의 번호 참조는 새 번호로 갱신했다.

> 정정(2026-10-12): 판매 기간(언제까지 선점할 수 있는가)을 정의하지 않았다 — 회차 `starts_at`은 저장만 되고 어디서도 검사하지 않았다. 공연 시작 시각에 선점을 마감하는 규칙은 ADR-009에서 추가한다.

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
| 확정 = 좌석 `RESERVED` + 예약 생성 | Seat, Reservation | **같은 트랜잭션**에서 둘 다 바꾼다 — "트랜잭션 하나에 애그리거트 하나" 원칙의 **의도된 예외** | Q7(종료 후 좌석 상태 정합성) 불변식(`RESERVED` 수 = `CONFIRMED` 수)이 원자성을 요구하므로 최종 일관성(도메인 이벤트)보다 우선했다. 대가: 두 애그리거트의 락이 한 트랜잭션에 묶인다 |
| 1인 최대 2매 | 여러 Seat + Reservation | 도메인 서비스 `HoldLimitPolicy`가 조회해 판정 | 어느 애그리거트도 보호하지 않으므로 동시 요청에 무방비 → ADR-005 |

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
- 그래도 확인은 **읽어 온 스냅샷 위에서의 비교**다(check-then-act). 확인과 저장 사이에 다른 요청이 끼어들 수 있다 — 기준선은 이를 막지 않는다(ADR-003·005·008에서 관측).
- 만료 판정: `expiresAt <= now` 이면 만료. 시각은 애플리케이션 `Clock`에서 얻는다(테스트에서 고정 가능).

## 4. 규칙

| 규칙 | 값 | 설정 |
|------|----|------|
| 홀드 TTL | 5분 (README Load Profile 원본) | `seat.hold.ttl` |
| 1인 최대 매수 | (사용자, 회차)당 2매 = 홀드 + 확정 예약 | `seat.hold.max-per-user` |
| 만료 배치 주기 | 기본 10초 | `seat.hold.expiry-interval` |
| 1회 선점 매수 | 1석 (연석은 ADR-017) | — |

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
| 판정 기준 없이는 "해결됐다"를 말할 수 없다 | [ADR-001](ADR-001-consistency-oracle.md) 정합성 판정·관측 하네스 (Q7(종료 후 좌석 상태 정합성)) |
| 같은 좌석 동시 선점 시 check-then-act | [ADR-003](ADR-003-same-seat-contention.md) (Q1(같은 좌석 1,000명 동시 선점)) |
| 트랜잭션·락 안의 느린 작업(락 보유 시간) | [ADR-004](ADR-004-critical-section-hold-time.md) |
| 같은 사용자 동시 요청 시 매수 COUNT 경합 | [ADR-005](ADR-005-per-user-limit.md) |
| 저장된 `HELD`와 배치 만료의 지연 | [ADR-014](ADR-014-hold-expiry.md) (Q3(선점 후 결제 이탈)) |
| 확정 시 2중 확인과 전환 사이 경합 | [ADR-015](ADR-015-confirm-atomicity.md) (Q6(결제 성공 + 선점 만료)) |
| 결제는 성공했는데 확정 실패 | [ADR-016](ADR-016-confirm-failure-compensation.md) (Q6(결제 성공 + 선점 만료)) |
| 연석 부분 선점 | [ADR-017](ADR-017-adjacent-seats.md) (Q2(연석 부분 선점)) |
| 좌석맵 조회와 실제 상태 불일치 | [ADR-018](ADR-018-seat-map-query.md) (Q5(좌석맵↔실제 상태 불일치)) |
| 오픈 정각 트래픽 폭주 | [ADR-019](ADR-019-open-spike.md) (Q4(오픈 정각 20만 명 유입)) |

## 9. 실측 결과 전체 (2026-10-19 보강)

> ADR-000은 기능 구현 ADR이다. **부하 측정은 하지 않았다** — 기준선의 첫 부하 실측은 ADR-001(캠페인 `20260928-full-9ee71d5`)이다. 이 절은 ADR-000이 채택 판정에 쓴 검증 결과만 모은다. 결과 파일이 없는 검증이라 숫자의 출처는 작업 log다.

### 9.1 검증 경과

| 항목 | 값 |
|------|-----|
| 대상 커밋 | `add53303`(코드) · `ec807364`(문서) — 2026-09-27 20:50, 브랜치 `feat/adr-000-baseline` → PR #1 병합 `c5f68b69`(09-28 06:13) |
| 환경 | Testcontainers `postgres:16`(로컬 Docker 29), JDK 21 · Kotlin 2.2.20 · Boot 3.5.5 |
| 단계 | ① 절차형 구현(트랜잭션 스크립트) → ② DDD 애그리거트 리팩토링(명세 §10 재합의) |

### 9.2 테스트·뮤테이션·스모크

| 시각 | 검증 | 결과 |
|------|------|------|
| 09-27 20:11 | contextLoads + Flyway V1 적용 + JPA `ddl-auto=validate` | 통과 (Testcontainers 1.21.4로 올린 뒤) |
| 20:13 | 절차형 구현 테스트 | **16개 green** |
| 20:14 | 뮤테이션 2건: 좌석 상태 비교 제거 / 만료 경계 `isAfter`→`isBefore` | 3개 실패 / 1개 실패 — 테스트가 규칙을 실제로 검증함. 소스 복구(diff 0) |
| 20:17 | HTTP 스모크(bootTestRun, TTL 20s·배치 5s) | 선점 201 → 다른 사용자 409 → 확정 200(RESERVED·CONFIRMED). 만료 11:17:05 → 배치 해제 11:17:08(UTC) |
| 20:36 | 리팩토링 전 특성테스트(홀드 단언을 JdbcTemplate DB 수준으로 이전 + 에러 우선순위 테스트 추가) | **17개 green** (baseline) |
| 20:39 | 애그리거트 전환 1차 | **10 red** — `hold.id` null(관리 중 좌석에 `saveAndFlush` → merge가 새 홀드의 복사본을 영속화) |
| 20:40 | `save` 대신 `flush()` | 17개 green |
| 20:42 | 뮤테이션 3건: `assertHoldable` 무력화 / `isExpired` 경계 / 에러 우선순위 뒤집기 | 5 red / 3 red / 1 red — 새 위치에서도 규칙이 검증됨. 소스 복구 |
| 20:44 | 계약 표면 대조 | V1 무변경(mtime 20:06), API 매핑·헤더·상태 코드 동일 — diff 0 |
| 20:46 | 리팩토링 후 HTTP 스모크 | 리팩토링 전과 같은 경로·응답 |
| 20:49 | 애그리거트 단위 테스트 `ProductSeatTest` 5개 추가(Spring·DB 없음) | **전체 22개 green** |

출처: `docs/plans/2026-09-27/adr-000-baseline/log.md` 타임라인. 시각은 log 기준(KST).

- 의도된 비불변식(동시 요청의 중복 선점·매수 초과)은 이 단계에서 검증하지 않았다. 단일 요청 테스트만 있다 — 동시성 깨짐의 실측은 ADR-001 §3(같은 좌석 성공 10, 1인 2매 초과 사용자 11~19).

## 10. 의사결정 기록 (2026-10-19 보강)

> 시각은 KST. 작업 log에 없는 행은 대화 기록 시각을 KST로 바꿔 적었다. 사용자 결정은 원문이다.

| 시각 | 근거 | 결정 | 고르지 않은 것 | 누가 |
|------|------|------|----------------|------|
| 09-27 05:47~06:31 | (측정 없음) 도메인 설계 대화 | 처음에는 해법까지 정한 도메인 문서를 먼저 썼다 — 구 ADR-001 "도메인 계층과 좌석 상태의 원천"(커밋 `ae99571a`): 좌석에 HELD 저장 안 함(홀드 목록으로 유도), 조건부 업데이트 1회 쿼리, 같은 사용자 직렬화는 advisory lock | — | 사용자: "좋아 advisory lock으로 가고 ADR-001 초안 작성해줘." |
| 09-27 19:04 | 해법을 먼저 정하면 선택 이유가 추론만 남는다(§1) | **해법 선결정을 접고 기준선부터 만든다.** 동시성 제어 없는 순수 구현(ADR-000) → k6로 문제 관측 → ADR별 해결 | 구 ADR-001(해법 선결정) 유지 | 사용자: "우선 질문 내용과 지금 너가 1번에서 처리했던 쿼리들 있잖아? 그거 전부 ADR 후순위 투두로 바꾸자. … 기본구조는 당연히 불가능함을 보이고 … 그에 따른 이슈로 ADR-0x로 하나씩 해결해나가는게 맞지않나?" |
| 09-27 19:12 | 같은 대화 — 메인이 "좌석 상태 원천" 두 안을 제시 | ① 좌석 상태 원천 = **직관 모델(좌석에 HELD 저장 + `@Scheduled` 배치 만료)** — 05:47의 "좌석은 HELD를 갖지 않는다"를 **되돌렸다**. 만료 후 HELD 잔존을 관측하고 lazy 유도는 이후 ADR(현 ADR-014)에서 실측 근거와 함께 도입한다 ② 구 ADR-001 삭제, 쟁점을 가설 ADR-001~009로 나열 | ① lazy 유도로 시작(실측 근거가 남지 않음) ② 구 문서를 BACKLOG로 보존 | 사용자: "권장으로 처리하자. 아니야. 그냥 그 문서는 삭제되었다고 치고, 000부터 하나씩 진행하고 위 쟁점과 질문에 있는 내용을 순차적으로 ADR-00X형태로 리스트업하고 각 질문들에 대한 가설들로 쭉 ADR문서를 작성해놓자." |
| 09-27 (인터뷰) | 명세 §0 Q/A | 범위 = 선점 + 만료 + 확정, PostgreSQL + Spring Data JPA 파생 메서드만, Flyway, TTL 5분(README 원본 — 초기 대화의 10분 대신), 1인 2매 = 홀드 + 확정 예약(앱에서 COUNT), stakes 낮음, MODE=auto | 네이티브/JPQL 쿼리, 락·유니크 제약(이후 ADR의 후보) | 사용자 인터뷰 응답(명세 §0) |
| 09-27 20:06 | 명세 합의 | 구현 착수 | — | 사용자: "좋아 그대로 진행해볼래?" |
| 09-27 20:09 | contextLoads 실패: Testcontainers 1.21.3 → Docker 29 "client version 1.32 is too old" (§9.2) | Testcontainers 1.21.4로 올림(load-bearing 가정 1이 깨져 즉시 수정) | Docker 버전 낮추기 | 메인 판단 — 웹 확인 |
| 09-27 20:19 | self-review: datasource 기본값 `localhost:5432`가 금지영역(study-pg18 컨테이너)과 겹침 | 기본값 제거(환경변수 필수) | 기본값 유지 | 메인 판단 |
| 09-27 20:22~20:40 | 사용자 지적: 리포지토리를 직접 부르는 절차형은 DDD가 아니다 | **애그리거트를 먼저 명시하고 리팩토링**: Seat 루트 ⊃ SeatHold(`seat_hold` 테이블 유지), Reservation 별도 루트, 확정은 같은 트랜잭션(원칙의 의도된 예외 — §2.1), 1인 2매는 도메인 서비스 `HoldLimitPolicy`. 명세 §10 재합의(외부 관찰 동작 보존) | 트랜잭션 스크립트 유지 / 홀드를 별도 애그리거트 / 확정을 도메인 이벤트(최종 일관성) | 사용자: "우선 잠시만 지금 이 구조가 DDD방식이 맞아? DDD방식이 아닌 거 같은데," → "000은 어그레이트가 어떤건지 명시하는게 우선이겠구나." → "좋아 그 범위로 리팩토링 진행해줘" |
| 09-27 20:40 | 애그리거트 전환 10 red(§9.2) | `saveAndFlush(seat)` 대신 `flush()` — cascade PERSIST가 원본 홀드를 INSERT | — | 메인 판단 |
| 09-27 20:49 | 변경 표에 약속한 애그리거트 단위 테스트가 빠짐 | `ProductSeatTest` 5개 추가 | — | 메인 판단(누락 발견) |
| 09-27 20:50 | 22 green · 계약 표면 diff 0 | 코드·문서 커밋을 나눠 push | — | 사용자: "좋아 나눠서 커밋하고 푸시해줘" |
| 09-28 05:44 | 사이클 마감(NEXT·measurement-log) | study-note 이슈 카드 2장(Testcontainers API 하한, JPA save→merge 복사) + 작업 기록, 본 저장소는 PR 병합(명세 §11 재합의) | — | 사용자: "스터디 노트에 이슈는 정리 진행하고 스터디 노트에 이슈 커밋 해놓자 이건 필요할 꺼 같아. 그리고 작업 기록도 스터디 노트에 기록하자. 그리고 메인에는 PR작성 후 병합처리하자" |
| 09-28 06:22 | ADR-000에 부하 측정이 없음 | 기준선 실측은 **ADR-001로 넘긴다**(판정기·k6 하네스와 함께) | ADR-000 안에서 부하 측정 | 사용자: "우선 ADR-000자체 부하테스트 진행했어?" → "응 ADR-001 인터뷰 진행하자" |

출처: `docs/plans/2026-09-27/adr-000-baseline/log.md`·`requirement-spec.md` §0·§10·§11, 대화 기록(09-26 20:47Z ~ 09-27 21:23Z), `git log`(커밋 시각).

- **다음 ADR로 넘긴 것**: 동시성 문제 전부(§8 표) — 첫 실측은 ADR-001.
