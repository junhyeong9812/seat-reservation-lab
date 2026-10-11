# 선점 요청은 어떻게 처리되나 — 트랜잭션·락·매수 제어 동작 방식

> 이 문서는 **지금 코드가 어떻게 동작하는가**만 적는다. 왜 이렇게 골랐는지와 측정 결과는 ADR에 있다.
> - 좌석 경합: ADR-003
> - 1인 매수 제어 10종 비교: ADR-005
> - counter-upsert: ADR-006
> - counter-upsert-precheck와 처리량 벽: ADR-007
>
> 기준 코드: 브랜치 `feat/adr-007-throughput-wall`(2026-10-10). 코드 인용은 실파일 발췌이며, 타이머 세부와 주석 일부는 생략했다.

## 1. 한눈에 보기

```
POST /api/schedules/{scheduleId}/seats/{seatId}/hold
  │
  ├─ HoldSeatService.hold ─────────────── 트랜잭션 밖
  │    ⓐ limit.prepare()        (hasPrepare인 매수 방식만 — 쿼터 행 준비, 커넥션 따로)
  │    ⓑ limit.around { … }     (SERIALIZABLE 재시도용, 나머지는 그냥 실행)
  │
  ├─ 좌석 전략 3b PessimisticNoWaitHoldStrategy
  │    tx.execute { … } ──────────────── 여기서 BEGIN (커넥션 1개)
  │      │
  │      └─ HoldSeatProcess.hold ──────── 트랜잭션 안 (MANDATORY)
  │           ⓒ limit.acquire()         사용자 단위 진입 (락·카운터)
  │           좌석 select … for no key update nowait
  │           seat.assertHoldable()     AVAILABLE 아니면 409 SEAT_NOT_AVAILABLE
  │           ⓓ limit.check()           매수 판정 (COUNT 방식만)
  │           홀드 INSERT · 좌석 HELD · flush
  │    COMMIT ────────────────────────── 락 전부 해제
  │
  └─ 409 변환: 좌석 락 실패(55P03) → SEAT_NOT_AVAILABLE
```

- 좌석 방식은 3b(`pessimistic-nowait`)가 기본이고 측정도 모두 3b로 했다.
- 매수 방식은 설정 `seat.hold.limit-strategy`로 하나를 고른다. 앱 기본값은 `none`이다.

## 2. 매수 방식은 어떻게 끼어드나 — 전략 패턴

매수 방식 클래스들(`service/impl/limit/UserLimitStrategies.kt`)은 `UserLimitStrategy` 인터페이스 구현체다. `override fun`은 정의일 뿐이고 스스로 실행되지 않는다.

1. Spring이 기동할 때 `@Component` 구현체를 전부 빈으로 만든다.
2. `ActiveUserLimit`이 설정과 같은 하나를 고른다.
3. 위 흐름의 정해진 자리 ⓐ~ⓓ에서 그 구현체의 메서드가 불린다.

```kotlin
@Component
class ActiveUserLimit(strategies: List<UserLimitStrategy>, properties: SeatHoldProperties) {
    val strategy: UserLimitStrategy = strategies.single { it.type == properties.limitStrategy }
}

interface UserLimitStrategy {
    val hasPrepare: Boolean get() = false                                 // ⓐ를 할지
    fun prepare(command: HoldSeatCommand) {}                              // ⓐ 트랜잭션 밖
    fun <T> around(command: HoldSeatCommand, block: () -> T): T = block() // ⓑ 트랜잭션 밖
    fun acquire(command: HoldSeatCommand): Boolean = true                 // ⓒ 트랜잭션 안, 좌석 읽기 전
    fun check(command: HoldSeatCommand, entered: Boolean)                 // ⓓ 트랜잭션 안, 좌석 확인 뒤
    fun onHoldsExpired(expired: List<SeatHold>) {}                        // 만료 배치가 부른다
}
```

| 방식 | ⓐ prepare | ⓒ acquire | ⓓ check |
|------|-----------|-----------|---------|
| `none` | — | — | COUNT(홀드) + COUNT(확정) ≥ 2 → 409 |
| `advisory` | — | `pg_advisory_xact_lock(회차, 사용자)` — 기다림 | COUNT |
| `advisory-try` | — | `pg_try_advisory_xact_lock(회차, 사용자)` — 못 잡으면 표시만 | 못 잡았으면 409, 아니면 COUNT |
| `quota-lock` | 쿼터 행 준비 | 쿼터 행 `FOR UPDATE` | COUNT |
| `quota-nowait` | 쿼터 행 준비 | 쿼터 행 `FOR UPDATE SKIP LOCKED` | 못 잡았으면 409, 아니면 COUNT |
| `counter` | 쿼터 행 준비 | `UPDATE … cnt + 1 WHERE cnt + 1 <= 2` = 판정 | — |
| `counter-upsert` | — | `INSERT … ON CONFLICT DO UPDATE … WHERE cnt + 1 <= 2` = 판정 | — |
| `counter-upsert-precheck` | — | 잠금 없는 좌석 조회 → 위 upsert → 0행이면 좌석 재조회 | — |
| `serializable(-retry)` | — | `SET TRANSACTION ISOLATION LEVEL SERIALIZABLE` (ⓑ가 40001 처리) | COUNT |

`-early` 변형(advisory-try-early·quota-nowait-early)은 ⓒ에서 못 잡으면 바로 409를 낸다. 좌석 확인 전에 거절하는 것이다.

COUNT는 도메인 서비스 `HoldLimitPolicy.check`다.

```kotlin
val holdCount = productSeatRepository.countByScheduleIdAndHoldsUserId(scheduleId, userId)
val reservedCount = reservationRepository.countByScheduleIdAndUserIdAndStatus(scheduleId, userId, ReservationStatus.CONFIRMED)
if (holdCount + reservedCount >= maxPerUser) throw SeatReservationException(ErrorCode.HOLD_LIMIT_EXCEEDED)
```

## 3. 트랜잭션 — 앱 트랜잭션 = DB 트랜잭션 하나

Spring 트랜잭션은 별도의 트랜잭션이 아니라 **DB 트랜잭션을 여닫는 장치**다.

1. `tx.execute { … }`(또는 `@Transactional`)가 커넥션 풀(Hikari, 10개)에서 커넥션을 **하나** 빌린다. 그리고 `autoCommit = false`로 바꾼다. DB에는 이것이 `BEGIN`이다.
2. 블록 안의 쿼리는 모두 그 커넥션으로 간다. Spring이 커넥션을 현재 스레드에 묶어 두기 때문이다.
   - `JdbcTemplate`(advisory 락·쿼터 SQL)과 JPA 리포지토리(좌석·COUNT·홀드 저장)도 같은 커넥션을 쓴다.
   - JpaTransactionManager가 트랜잭션의 커넥션을 JdbcTemplate에 노출한다(`DbLockHoldStrategies.kt` advisory 좌석 전략 주석).
3. 정상 종료면 `commit()`(= `COMMIT`), 예외면 `rollback()`(= `ROLLBACK`)이다.

advisory-try 선점 한 건이 DB에서 보이는 모습:

```sql
BEGIN
SELECT pg_try_advisory_xact_lock(5, 42);                -- (회차 5, 사용자 42) 이름표
select … from product_seat … for no key update nowait;  -- 좌석 행 락
select count(…) … seat_hold …;
select count(…) … reservation …;
insert into seat_hold …; update product_seat …;
COMMIT                                                  -- 이름표·좌석 행 락 해제
```

**모든 SELECT가 락을 잡지는 않는다.**
- 그냥 `SELECT`는 잠그지 않는다. READ COMMITTED에서 그 문장 시점에 커밋된 값을 읽기만 한다.
- 락을 잡는 SELECT는 두 종류다.
  - 락 절이 붙은 것: `FOR UPDATE` · `FOR NO KEY UPDATE` · `NOWAIT` · `SKIP LOCKED`
  - 락 함수를 부르는 것: `SELECT pg_try_advisory_xact_lock(…)`
- 그래서 `none`은 같은 COUNT SELECT를 하는데도 같은 사용자의 동시 요청을 못 막는다.

**조건: 판정 구간의 모든 쿼리가 이 트랜잭션 안에 있어야 한다.**
- **같은 클래스 안 호출(self-invocation)은 트랜잭션을 열지 않는다.** `@Transactional`은 Spring 프록시를 거쳐 불릴 때만 동작한다. `this.method()`로 부르면 트랜잭션 없이 쿼리마다 자동 커밋된다. 그러면 `_xact_` 락은 그 한 문장이 끝나는 즉시 풀려 아무것도 막지 못한다. 실패 신호도 없다.
- `REQUIRES_NEW`로 연 안쪽 트랜잭션은 커넥션을 하나 더 빌린 별도 트랜잭션이다. 바깥의 락과 무관하고 따로 커밋된다.
- 트랜잭션 **밖**의 쿼리는 다른 커넥션의 자동 커밋 문장이다. 락과 무관하고 롤백되지도 않는다.
  - ⓐ `prepare`(쿼터 행 준비)가 그렇다. 그래서 `counter`는 새 사용자 요청마다 커넥션을 3번 빌렸다(SELECT · INSERT · 본 트랜잭션).
- `HoldSeatProcess.hold`는 `@Transactional(propagation = Propagation.MANDATORY)`다. 열린 트랜잭션 밖에서 부르면 예외가 나서, 판정 구간이 트랜잭션 밖에서 돌 수 없다.
- 트랜잭션 안에서 예외가 나면 전부 되돌아간다. 매수 판정 409, 좌석 409, DB 오류 모두 해당한다. counter 계열이 ⓒ에서 올린 +1도 함께 원복된다.

## 4. 락의 종류와 범위

| 락 | 거는 곳 | 대상 | 누구끼리 막히나 | 풀리는 때 |
|----|--------|------|--------------|----------|
| 좌석 행 락 | 3b 좌석 조회 `for no key update nowait` | `product_seat` 그 좌석 행 | 같은 좌석을 잡으려는 트랜잭션 — NOWAIT라 기다리지 않고 409 | 트랜잭션 끝 |
| advisory xact 락 | advisory·advisory-try의 ⓒ | 테이블 아님 — PostgreSQL 락 관리자의 키(회차, 사용자) | **같은 키를 묻는 코드끼리만**(지금은 선점의 ⓒ뿐) | 트랜잭션 끝(DB가 자동) |
| 쿼터 행 락 | quota-lock·quota-nowait·counter 계열의 ⓒ | `user_hold_quota`의 (회차, 사용자) 행 | 그 행을 잠그거나 바꾸는 모든 트랜잭션 — 선점·만료 배치(counter 계열의 −1) | 트랜잭션 끝 |

**advisory 락은 약속형 락이다.**
- 테이블·행을 잠그지 않아서 잠글 행이 미리 있을 필요가 없다. 사용자의 첫 요청도 잠근다.
- 대신 같은 키로 같은 락을 묻지 않는 코드는 그냥 지나간다. 확정(`ConfirmReservationService.confirm`)과 만료 배치(`ExpireHoldsService.expire`)는 이 락을 잡지 않는다.

**개수를 늘리는 작업만 사용자 락이 필요하다.**
- 만료·취소처럼 개수를 줄이는 작업은 동시에 일어나도 '2매 초과'를 만들 수 없다. 생길 수 있는 것은 선점이 줄기 직전 개수를 읽고 거절하는 가짜 거절뿐이다.
- 취소 API는 아직 없다(`ReservationStatus.CANCELED` 값만 있다). 만든다면 이렇게 한다.
  - advisory 방식: 사용자 락 없이 가도 된다. 쓴다면 기다리는 `pg_advisory_xact_lock`이어야 한다. `try`면 선점 중인 취소가 409로 거절된다.
  - counter 계열: 카운터 −1 때문에 쿼터 행을 반드시 건드린다. 선점이 행을 쥐고 있으면 기다렸다가 순서대로 반영된다.

## 5. 쿼터 테이블 `user_hold_quota`

```sql
CREATE TABLE user_hold_quota (
    schedule_id BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    cnt         INT    NOT NULL DEFAULT 0 CHECK (cnt >= 0),
    PRIMARY KEY (schedule_id, user_id)
);
```

- **실제 테이블이다(뷰 아님).** (회차, 사용자)마다 한 행에 숫자 하나다. 뷰는 조회 때마다 다시 세는 것이라 잠글 행도, 원자적 +1도 없다.
- **기본 키가 락 단위다.**
  - 한 사용자 = 한 행이라 서로 다른 좌석을 동시에 눌러도 같은 행에서 줄을 선다.
  - 같은 키의 행은 둘 생기지 않는다. 동시 첫 INSERT도 하나만 들어가고, upsert의 나머지는 `ON CONFLICT`로 UPDATE 경로에 간다.
- 쓰는 법:
  - quota-lock·quota-nowait는 **잠글 대상으로만** 쓴다(cnt는 늘 0).
  - counter 계열은 **잠금 + 판정을 한 문장**으로 한다. `cnt + 1 <= 2`일 때만 +1이다. 앞 요청이 그 행을 바꾸는 중이면 기다렸다가 올라간 cnt로 조건을 다시 본다. 그래서 중복 카운트가 없다.
- counter 계열의 `cnt` = 홀드 + 확정 예약 수다. 앱이 직접 맞춘다.
  - 선점: +1(좌석에서 지면 롤백으로 원복)
  - 확정: 그대로
  - 만료: 배치가 지운 수만큼 −1(`decrementCounters` — 행이 없으면 예외, 음수는 CHECK가 막음)
- 감시: 판정기 `v_counter_mismatch`(cnt vs 실제 홀드 + 확정). advisory 계열은 `v_over_limit_users`(실제 홀드 + 확정을 사용자별 COUNT)로 감시한다. 둘 다 `loadtest/LoadtestDataService.kt`에 있다.
- 행은 만료로 지워지지 않고 쌓인다(ADR-009 숙제).

## 6. 락이 안 풀리면? — `_xact_`와 세션 락

- 지금 쓰는 advisory 함수는 모두 `_xact_`다. **트랜잭션이 COMMIT·ROLLBACK으로 끝나면 PostgreSQL이 스스로 푼다.** 앱의 unlock·finally가 없다.
  - 앱 예외 → 롤백 → 해제.
  - 앱 프로세스 사망 → 연결 끊김 → DB가 트랜잭션 중단 → 해제.
- 위험한 경우:
  1. **세션 락**(`pg_advisory_lock`, `xact` 없음) — COMMIT으로 안 풀리고 `pg_advisory_unlock`을 직접 불러야 한다. 커넥션 풀과 쓰면 unlock을 빠뜨린 커넥션이 락을 쥔 채 풀로 돌아가 그 사용자를 계속 막는다. **쓰지 않는다.**
  2. **오래 열린 트랜잭션** — 트랜잭션 안의 느린 작업이나 멈춘 앱이 그 시간만큼 모든 락(좌석·사용자)을 쥔다. 네트워크가 끊기면 DB가 끊김을 알아챌 때까지(TCP keepalive) 남는다.
     - 대비책: `idle_in_transaction_session_timeout` · `statement_timeout` · 트랜잭션 타임아웃.
     - **지금은 아무것도 설정하지 않았다**(PostgreSQL 기본값 = 꺼짐, `application.yml`·compose에 없음).
- 막혔을 때 증상:
  - advisory-try·NOWAIT 계열: 즉시 409라 요청이 쌓이지 않는다.
  - 기다리는 락(advisory·quota-lock·좌석 3a): 요청이 커넥션을 쥔 채 줄을 선다. 풀 10개가 차면 다른 사용자도 느려진다.
- 비교: Redis 분산락(ADR-003 `redis-lock`)은 직접 풀어야 하는 종류다. 그래서 TTL과 finally 해제(`RELEASE_IF_OWNER`), 해제 실패 경고가 붙어 있다.

## 7. 확정·만료는 어떻게 도나

- **확정** `POST /api/holds/{holdId}/confirm` → `ConfirmReservationService.confirm`(`@Transactional`)
  1. 홀드로 좌석을 찾는다.
  2. `seat.confirm`이 2중 확인을 한다: 내 홀드인가 · 만료 전인가 · 좌석이 HELD인가.
  3. 좌석 RESERVED + 예약 CONFIRMED 저장.
  - 사용자 락·매수 방식은 관여하지 않는다. 홀드가 예약으로 바뀔 뿐이라 개수 합은 그대로다.
- **만료** `HoldExpiryScheduler`(`fixedDelay` = `seat.hold.expiry-interval`, 기본 10초) → `ExpireHoldsService.expire`(`@Transactional`)
  1. 만료 시각이 지난 홀드를 가진 좌석을 읽는다.
  2. 홀드를 지우고, 좌석이 HELD면 AVAILABLE로 바꾼다.
  3. `userLimit.strategy.onHoldsExpired(expired)`를 부른다. counter 계열만 카운터를 −1 하고, 나머지는 아무것도 하지 않는다.

## 8. 코드 위치

| 무엇 | 파일 |
|------|------|
| 선점 API | `api/SeatHoldController.kt` |
| 유스케이스(ⓐ·ⓑ) | `service/impl/HoldSeatService.kt` |
| 좌석 전략(트랜잭션 시작) | `service/impl/hold/DbLockHoldStrategies.kt` (3b = `PessimisticNoWaitHoldStrategy`) |
| 선점 규칙(ⓒ·ⓓ) | `service/impl/HoldSeatProcess.kt` |
| 매수 방식 12종·`ActiveUserLimit` | `service/impl/limit/UserLimitStrategies.kt` |
| COUNT 판정 | `domain/HoldLimitPolicy.kt` |
| 좌석 조회·락 쿼리 | `domain/repository/ProductSeatRepository.kt` |
| 확정 | `service/impl/ConfirmReservationService.kt`, `domain/ProductSeat.kt` |
| 만료 | `api/HoldExpiryScheduler.kt`, `service/impl/ExpireHoldsService.kt` |
| 쿼터 테이블 | `resources/db/migration/V5__user_hold_quota.sql` |
| 판정기(감시 쿼리) | `loadtest/LoadtestDataService.kt` |
