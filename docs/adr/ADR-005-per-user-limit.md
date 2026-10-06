# ADR-005: 1인 최대 2매 — 같은 사용자 동시 요청
> 번호 이동(2026-10-04): 임계 구역 길이·락 보유 시간을 새 ADR-004로 넣으며 기존 ADR-004~010을 005~011로 옮겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: 가설 (Hypothesis) — 구현 완료, 하네스·측정 전 검증 중(§6)
- 날짜: 2026-10-06
- 대응: Load Profile "1인 최대 2매" (파생 이슈)
- 선행: ADR-000, ADR-001(판정기), ADR-002(DB 기준선 — S2 before), **ADR-003(좌석 선점 = 3b `pessimistic-nowait` — 결정)**
- 후행: ADR-006(홀드 만료 — 카운터를 내리는 주체), ADR-007(확정 원자성)
- 명세: `docs/plans/2026-10-06/adr-005-user-limit/requirement-spec.md`

## 1. 문제

선점 규칙의 1인 매수 확인은 `(회차, 사용자)의 홀드 수 + 확정 예약 수 조회 → 2 미만인지 비교 → 홀드 저장`이다(조회 후 비교, check-then-act). 같은 사용자가 탭 여러 개나 매크로로 **서로 다른 좌석을** 동시에 누르면 모든 요청이 같은 개수를 읽고 통과한다.

ADR-003의 좌석 락(3b)은 이것을 막지 못한다 — 좌석이 서로 달라 잠그는 행이 다르다. 그리고 막아야 할 대상은 **아직 없는 행**(새로 생길 홀드)이라 기존 행에 거는 락으로는 잡을 수 없다(팬텀). 그래서 '사용자'를 대표하는 무언가를 따로 잠그거나, 개수 자체를 원자적으로 갱신해야 한다.

- before(ADR-002 S2, 인덱스 있음·풀 10, 좌석 경합 제어 없음): 사용자 100명이 각자 좌석 10개를 동시에 → 성공 L2 221 · L4 218(상한 200), **매수 초과 사용자 L2 17 [11–21] · L4 15 [10–17]**.

## 2. 선택지

### 2.1 공통 — 사용자 단위 제어가 끼는 자리

좌석 방식은 3b로 고정한다(ADR-003 결정, 앱 기본값). 매수 방식은 설정 `seat.hold.limit-strategy`로 고르고, 좌석 전략이 연 **같은 트랜잭션** 안의 두 자리에 끼어든다. 트랜잭션 밖에서 감싸는 자리도 하나 있다.

| 단계 | 하는 일 | 매수 방식이 하는 일 |
|------|--------|-----------------|
| ⓪ 유스케이스 진입(트랜잭션 밖) | `around` | 쿼터 행 준비(L3·L4·L5 — 자동 커밋 `INSERT … ON CONFLICT DO NOTHING`), SERIALIZABLE 충돌 변환·재시도(L6·L7) |
| ①.1·①.2 커넥션 획득·`BEGIN` | 3b 전략의 트랜잭션 시작 | — |
| **①.3 사용자 단위 진입** | `acquire` | L1 advisory 락 · L2 try 락 · L3·L4 쿼터 행 락 · L5 카운터 +1(=판정) · L6·L7 격리 수준 SERIALIZABLE |
| ② 좌석 읽기 | `SELECT … FOR NO KEY UPDATE NOWAIT`(3b) | — |
| ③ 선점 가능 확인 | AVAILABLE이 아니면 409 `SEAT_NOT_AVAILABLE` | — |
| **④ 매수 판정** | `check` | 홀드 수 + 확정 예약 수 < 2(L5는 ①.3에서 끝) — 아니면 409 `HOLD_LIMIT_EXCEEDED` |
| ⑤~⑧ 상태 전이·flush | ADR-003 §2.1 그대로 | — |
| ⑨.2 `COMMIT` | | advisory xact 락·쿼터 행 락 해제, SERIALIZABLE 충돌(40001)은 여기서도 날 수 있다 |

- **락 순서 = 사용자 → 좌석**(①.3이 ②보다 먼저). 좌석은 NOWAIT라 사용자 락을 쥔 채 좌석을 기다리는 일이 없다 → 교착 경로가 없다.
- **에러 우선순위 보존**: 락만 먼저 잡고 판정 순서는 그대로(좌석 불가 → 매수 초과). **예외는 L5** — 카운터 갱신이 곧 판정이라 좌석보다 먼저 판정된다(사용자 허용 2026-10-06).
- **응답 계약 불변**: 즉시 실패형의 거절도 기존 `HOLD_LIMIT_EXCEEDED`를 쓴다(사용자 결정). 그래서 '매수가 남았는데 거절됨'(가짜 거절)은 응답 코드로 구분되지 않고, 측정에서 끝 상태 매수로 가른다(§4 ②).
- 앱 코드: `service/impl/limit/UserLimitStrategies.kt`(8개 + `ActiveUserLimit`), `service/impl/HoldSeatProcess.kt`(갈래점 둘 + 타이머), `service/impl/HoldSeatService.kt`(`around`), `service/impl/ExpireHoldsService.kt`(카운터 감소).

### 2.2 L0 `none` — 조회 후 비교(대조군)

```kotlin
override fun check(command: HoldSeatCommand) = policy.check(command.scheduleId, command.userId, properties.maxPerUser)
```

```sql
select count(ps1_0.id) from product_seat ps1_0 left join seat_hold h1_0 on ps1_0.id=h1_0.seat_id where ps1_0.schedule_id=? and h1_0.user_id=?
select count(r1_0.id) from reservation r1_0 where r1_0.schedule_id=? and r1_0.user_id=? and r1_0.status=?
```

같은 사용자의 동시 요청이 모두 같은 개수를 읽는다 — 초과가 나야 정상(양성 대조).

### 2.3 L1 `advisory` — `pg_advisory_xact_lock(회차, 사용자)`

```kotlin
override fun acquire(command: HoldSeatCommand) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", *advisoryKey(command))   // 두 정수 키
}
```

- 같은 사용자의 다음 요청은 앞 트랜잭션이 커밋할 때까지 **커넥션을 쥔 채 기다린다** → 깨어나면 앞 요청의 홀드가 보이는 상태에서 센다.
- 두 정수 키(`int4, int4`)는 ADR-003 advisory 전략의 한 정수 키(좌석 id)와 키 공간이 따로다. 회차·사용자 id가 int 범위를 넘으면 자르지 않고 실패한다(`Math.toIntExact`).

### 2.4 L2 `advisory-try` — `pg_try_advisory_xact_lock(회차, 사용자)`

```kotlin
val got = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean::class.java, *advisoryKey(command))
if (got != true) limitExceeded()
```

- 같은 사용자의 요청이 진행 중이면 기다리지 않고 409. ADR-003의 '기다리지 않는다' 원칙과 같은 방향이지만, **매수가 0이어도** 동시에 누른 두 번째 탭은 거절된다(가짜 거절).

### 2.5 L3 `quota-lock` — 쿼터 행 `FOR UPDATE`

```kotlin
override fun <T> around(command: HoldSeatCommand, block: () -> T): T { ensureQuotaRow(jdbc, command); return block() }
override fun acquire(command: HoldSeatCommand) {
    jdbc.queryForList("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", …)
}
```

- 잠글 행이 없던 문제를 **잠글 행을 만들어서** 푼다(`user_hold_quota`, V5). 행은 트랜잭션 밖에서 미리 만든다 — 트랜잭션 안의 `INSERT … ON CONFLICT`는 아직 커밋 안 된 같은 키 행을 만나면 그 트랜잭션이 끝날 때까지 기다려(사용자 첫 요청들) L4의 NOWAIT 의미를 깬다.
- 대가: 테이블 추가, 사용자 첫 요청마다 자동 커밋 INSERT 1회(커넥션을 한 번 더 빌린다).

### 2.6 L4 `quota-nowait` — 쿼터 행 `FOR UPDATE NOWAIT`

```kotlin
try { jdbc.queryForList("… FOR UPDATE NOWAIT", …) }
catch (e: DataAccessException) { if (sqlStateOf(e) == "55P03") limitExceeded(); throw e }
```

- L2와 같은 의미(즉시 거절, 가짜 거절 있음)를 행 락으로. JdbcTemplate은 55P03을 `UncategorizedSQLException`으로 번역한다 — 예외 타입이 아니라 SQLState로 가른다(§6.1).

### 2.7 L5 `counter` — 카운터 조건부 UPDATE

```kotlin
override fun acquire(command: HoldSeatCommand) {
    val updated = jdbc.update(
        "UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ? AND cnt + 1 <= ?", …, properties.maxPerUser)
    if (updated == 0) limitExceeded()
}
override fun check(command: HoldSeatCommand) {}            // acquire에서 판정 끝 — COUNT 없음
override fun onHoldsExpired(expired: List<SeatHold>) { /* 실제로 지운 홀드만큼 (회차, 사용자)별 cnt − n */ }
```

- ADR-003의 조건부 UPDATE와 같은 원리(READ COMMITTED에서 UPDATE는 커밋된 최신 행으로 조건을 다시 본다). 같은 사용자의 다음 요청은 행 락을 기다렸다가 올라간 cnt로 재평가된다.
- 카운터 = 홀드 + 확정 예약 수: 선점 +1(좌석에서 지면 같은 트랜잭션 롤백으로 원복), 확정은 홀드 → 예약이라 그대로, 만료 −1. **카운터를 내리는 주체가 생긴다**(만료 배치 — counter일 때만, ADR-006과 연결). 어긋나면 `cnt >= 0` 제약이 만료 배치를 실패시켜 드러낸다(무음 보정 없음). 판정기 `v_counter_mismatch`로 끝 상태를 대조한다.
- 매수 초과 사용자는 **좌석 락을 아예 잡지 않는다** — 3b의 '이긴 쪽 롤백' 대가(ADR-003 §8 ①)가 이 방식에서는 생기지 않을 것으로 예상(§3 H5).

### 2.8 L6 `serializable` · L7 `serializable-retry`

```kotlin
override fun acquire(command: HoldSeatCommand) { jdbc.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE") }  // 트랜잭션 첫 문장
override fun <T> around(command: HoldSeatCommand, block: () -> T): T {
    // 40001이면 L6은 바로 409, L7은 최대 3번 다시
}
```

- DB가 '두 트랜잭션이 서로의 결과를 못 보고 둘 다 통과한' 위험 구조를 감지해 한쪽을 실패시킨다(SSI). 격리 수준은 트랜잭션의 첫 문장이어야 한다 — 아니면 PostgreSQL이 오류를 낸다(무음 무시 없음). 적용 여부는 `SHOW transaction_isolation`으로 테스트가 확인한다.
- SSI는 **술어(조회 조건)·페이지 단위**로 충돌을 본다 → 다른 사용자끼리도 충돌할 수 있다(가짜 거절). 그리고 40001은 같은 좌석 충돌에서도 날 수 있어 그것도 `HOLD_LIMIT_EXCEEDED`가 된다(편향 — §5).

### 2.9 요약

| 방식 | 잠그는 것 | 같은 사용자의 다음 요청 | 다른 사용자 | 매수 판정 위치 | 추가 스키마 |
|------|---------|--------------------|-----------|-------------|-----------|
| L0 none | 없음 | 통과(초과) | — | ④ | — |
| L1 advisory | advisory (회차, 사용자) | 커넥션 쥐고 대기 | 안 막음 | ④ | — |
| L2 advisory-try | 같음 | 즉시 409 | 안 막음 | ④ | — |
| L3 quota-lock | 쿼터 행 | 커넥션 쥐고 대기 | 안 막음 | ④ | `user_hold_quota` |
| L4 quota-nowait | 쿼터 행 | 즉시 409 | 안 막음 | ④ | 같음 |
| L5 counter | 쿼터 행(UPDATE) | 행 락 대기 후 재평가 | 안 막음 | **①.3** | 같음 + 만료 감소 |
| L6 serializable | (술어 락) | 충돌 시 409 | **충돌할 수 있음** | ④ | — |
| L7 serializable-retry | 같음 | 충돌 시 최대 3번 다시 | 같음 | ④ | — |

## 3. 가설

| # | 가설 | 틀렸다고 판정할 관측 |
|---|------|------------------|
| H1 | L0은 좌석 3b 아래에서도 S2 매수 초과를 낸다(ADR-002와 같은 크기). L1~L7은 전 회차 초과 0 | L0 초과 0(측정 설계 오류) / L1~L7 어느 회차든 초과 > 0 |
| H2 | 가짜 거절(매수 < 2인데 거절)은 즉시 실패형(L2·L4)과 L6에서 크고, 대기형(L1·L3)·L5·L7은 0에 가깝다 | 대기형에서 가짜 거절 > 0 / 즉시 실패형에서 0 |
| H3 | S4(사용자마다 1요청 — 같은 사용자 경합 없음)에서 처리량 한계는 대조군과 같다. L3·L4·L5는 쿼터 행 준비 왕복만큼, L6·L7은 SSI 비용만큼 약간 낮을 수 있다 | 계단 2칸 이상 차이 |
| H4 | S2에서 대기형(L1·L3·L5)은 같은 사용자 10요청이 커넥션을 쥐고 줄 서 풀 대기가 커지고, 즉시 실패형은 작다 | 대기형과 즉시 실패형의 획득 대기가 같다 |
| H5 | S7(이미 2매인 사용자 U + 일반 사용자들이 같은 좌석)에서 L0~L4·L6·L7은 U가 좌석 락을 먼저 잡았다가 매수에서 롤백할 때 일반 사용자가 409를 받고 좌석이 비는 일이 생기고, **L5는 U가 좌석 락 전에 지므로 0** | L5에서 억울한 409 > 0 / 나머지에서 0 |
| H6 | 매수 확인 비용은 인덱스(`seat_hold.user_id`, `reservation(schedule_id, user_id)`) 덕에 배경 규모에 거의 무관하다(사용자 제기 "인덱스라 큰 차이 없을 것") | 배경 100만에서 지연이 배경 0의 수 배 |
| H7 | S3(만료·확정 포함)에서 L5 카운터는 끝 상태에서 홀드 + 확정 예약과 같다 | `v_counter_mismatch` > 0 |

## 4. 판정 기준

| 기준 | 지표 |
|------|------|
| ① 매수 정합성 | 사용자당 성공 ≤ 2, 판정기 `v_over_limit_users` 0 — 전 회차 |
| ② 가짜 거절 | S2에서 201 < 2인데 `HOLD_LIMIT_EXCEEDED`를 받은 사용자 수 |
| ③ 처리량 회귀 | S4 엄격 한계·커넥션 획득 대기 — 대조군 대비 |
| ④ 이긴 쪽 롤백 | S7에서 끝 상태 AVAILABLE인데 일반 사용자가 409 `SEAT_NOT_AVAILABLE`을 받은 좌석 수·409 수 |
| ⑤ 매수 확인 지연 | 앱 타이머(`seat.hold.limit.acquire`·`check` 평균·최대) · DB 벤치(배경 0/10만/100만, 실행 계획) |
| ⑥ 실패 모드 | 응답 분류(타임아웃·연결 실패·5xx), 데드락, 40001·재시도 수 |
| ⑦ 카운터 정합 | S3 끝 상태 `v_counter_mismatch`(L5만) |

## 5. 측정 계획

| 대상 | 시나리오 | 단계 | 회차 |
|------|---------|------|------|
| 매수 방식 8개 | S2(사용자 100 × 좌석 10 동시) · S4(16단계) | L2 · L4 | 5 |
| 매수 방식 8개 | S3 원본 × 이탈 0/20/50 | L4 | 3 |
| 매수 방식 8개 | S7 이긴 쪽 롤백(신규) | L4 | 5 |
| 매수 확인 쿼리 2개 | DB 직접 벤치(pgbench) — 배경 0 / 10만 / 100만 | L4 | 조합당 1 |

- 좌석 3b·인덱스 V2·풀 10·배경 0·타임아웃 30s 등 나머지는 ADR-003과 같다. 회차 우선 순서. 하네스 `k6/ADR-005/`.
- 약 40~45시간(추정 — ADR-003 실측 비례).
- 알려진 편향(측정 전): SERIALIZABLE의 40001은 좌석 충돌에서도 나서 `HOLD_LIMIT_EXCEEDED`로 섞인다(S7에서 두드러질 수 있음) · L5 카운터 감소는 만료 배치가 실제로 지운 홀드로 하지만, 만료 배치 자체의 동시성(확정과의 경합)은 ADR-006 몫이다.

## 6. 측정 전 검증

### 6.1 구현 중 발견

- **JdbcTemplate의 55P03 번역**: 쿼터 행 NOWAIT(L4)의 '잠겨 있음'(55P03)을 `PessimisticLockingFailureException`으로 잡도록 썼는데, JdbcTemplate은 이것을 `UncategorizedSQLException`으로 번역했다(ADR-003 3b는 JPA 경로라 `PessimisticLockingFailureException`이었다). catch를 지나 500이 됐고 경합 테스트가 잡았다. 같은 SQLState도 **어느 계층을 거치느냐에 따라 다른 예외 타입**이 된다 — 예외 타입이 아니라 SQLState로 가르도록 고쳤다.
- **판정기 항목 null**: `v_counter_mismatch`를 counter가 아닐 때 null로 냈더니 `v_*`를 합산하는 기존 테스트 10개가 NPE로 깨졌다 → counter일 때만 항목을 낸다.
- 기본 좌석 전략 변경(none → 3b)으로 '기본값 = none' 테스트 1개가 깨졌다 — 명세 변경이라 기대값을 바꿨다.

### 6.2 테스트 (124개 green)

- 매수 방식마다 컨텍스트 하나(`UserLimitStrategyTest` × 8): 같은 사용자 동시 10좌석(5라운드) — **L0은 5라운드 안에 초과가 났고(양성 대조 — 좌석 3b 아래에서도 매수 경합 그대로, 가정 1의 테스트 수준 실증)**, 나머지는 성공·DB 매수 모두 ≤ 2 · 다른 사용자 20명 동시 — 서로 막지 않음(L6·L7은 SSI 충돌 거절 허용) · 같은 좌석 50명 — 8개 모두 1명만 이김(가정 2) · 에러 우선순위(L5만 매수 초과) · 카운터 정합(선점·좌석 실패 롤백·확정·만료) · 격리 수준(`SHOW transaction_isolation`).

## 7. 실험 결과

미측정.

## 8. 결정

미정.
