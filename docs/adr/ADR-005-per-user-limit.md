# ADR-005: 1인 최대 2매 — 같은 사용자 동시 요청
> 번호 이동(2026-10-09): counter 1문장 upsert 재측정을 새 ADR-006으로 넣으며 기존 ADR-006~011을 007~012로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-04): 임계 구역 길이·락 보유 시간을 새 ADR-004로 넣으며 기존 ADR-004~010을 005~011로 옮겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: 결정됨(Accepted) — 2026-10-09, 매수 제어 기본 = L2(advisory-try)(§8). counter 재검토는 ADR-006
- 날짜: 2026-10-06
- 대응: Load Profile "1인 최대 2매" (파생 이슈)
- 선행: ADR-000, ADR-001(판정기), ADR-002(DB 기준선 — S2(사용자 100명 × 각자 좌석 10개 동시) before), **ADR-003(좌석 선점 = 3b `pessimistic-nowait` — 결정)**
- 후행: ADR-007(홀드 만료 — 카운터를 내리는 주체), ADR-008(확정 원자성)
- 명세: `docs/plans/2026-10-06/adr-005-user-limit/requirement-spec.md`

## 1. 문제

선점 규칙의 1인 매수 확인은 `(회차, 사용자)의 홀드 수 + 확정 예약 수 조회 → 2 미만인지 비교 → 홀드 저장`이다(조회 후 비교, check-then-act). 같은 사용자가 탭 여러 개나 매크로로 **서로 다른 좌석을** 동시에 누르면 모든 요청이 같은 개수를 읽고 통과한다.

ADR-003의 좌석 락(3b)은 이것을 막지 못한다 — 좌석이 서로 달라 잠그는 행이 다르다. 그리고 막아야 할 대상은 **아직 없는 행**(새로 생길 홀드)이라 기존 행에 거는 락으로는 잡을 수 없다(팬텀). 그래서 '사용자'를 대표하는 무언가를 따로 잠그거나, 개수 자체를 원자적으로 갱신해야 한다.

- before(ADR-002 S2(사용자 100명 × 각자 좌석 10개 동시), 인덱스 있음·풀 10, 좌석 경합 제어 없음): 사용자 100명이 각자 좌석 10개를 동시에 → 성공 L2(앱·DB CPU 2개) 221 · L4(앱·DB CPU 4개) 218(상한 200), **매수 초과 사용자 L2 17 [11–21] · L4 15 [10–17]**.

## 2. 선택지

### 2.1 공통 — 사용자 단위 제어가 끼는 자리

좌석 방식은 3b로 고정한다(ADR-003 결정, 앱 기본값 — 매수 방식이 none이 아니면 3b가 아닐 때 기동을 거부한다). 매수 방식은 설정 `seat.hold.limit-strategy`로 고르고, 좌석 전략이 연 **같은 트랜잭션** 안의 두 자리에 끼어든다. 트랜잭션 밖 자리도 둘이다(준비·감싸기).

| 단계 | 하는 일 | 매수 방식이 하는 일 |
|------|--------|-----------------|
| ⓪.1 유스케이스 진입(트랜잭션 밖) | `prepare` | 쿼터 행 준비(L3·L4·L5(quota-lock·quota-nowait·counter) — **매 요청**: 일반 SELECT로 있는지 보고 없을 때만 자동 커밋 `INSERT … ON CONFLICT DO NOTHING`). 타이머 `seat.hold.limit.prepare` |
| ⓪.2 | `around` | SERIALIZABLE 충돌 변환·재시도(L6·L7(serializable·serializable-retry)) |
| ①.1·①.2 커넥션 획득·`BEGIN` | 3b 전략의 트랜잭션 시작 | — |
| **①.3 사용자 단위 진입** | `acquire` | L1 advisory 락 · L2 try 락 · L3 쿼터 행 락 · L4 쿼터 행 `SKIP LOCKED` · L5 카운터 +1(=판정) · L6·L7(serializable·serializable-retry) 격리 수준 SERIALIZABLE. 즉시 실패형이 못 잡으면 '진입 못 함'만 기록하고 넘어간다(`-early`는 여기서 바로 409) |
| ② 좌석 읽기 | `SELECT … FOR NO KEY UPDATE NOWAIT`(3b) | — |
| ③ 선점 가능 확인 | AVAILABLE이 아니면 409 `SEAT_NOT_AVAILABLE` | — |
| **④ 매수 판정** | `check` | 진입 못 했으면 409, 아니면 홀드 수 + 확정 예약 수 < 2(L5(카운터 조건부 UPDATE)는 ①.3에서 끝) — 아니면 409 `HOLD_LIMIT_EXCEEDED` |
| ⑤~⑧ 상태 전이·flush | ADR-003 §2.1 그대로 | — |
| ⑨.2 `COMMIT` | | advisory xact 락·쿼터 행 락 해제, SERIALIZABLE 충돌(40001)은 여기서도 날 수 있다 |

- **락 순서 = 사용자 → 좌석**(①.3이 ②보다 먼저). 좌석은 NOWAIT라 사용자 락을 쥔 채 좌석을 기다리는 일이 없다 → 교착 경로가 없다.
- **에러 우선순위 보존**: 락만 먼저 잡고 판정 순서는 그대로(좌석 불가 → 매수 초과). 즉시 실패형(L2·L4(advisory-try·quota-nowait))도 진입에 실패하면 좌석을 먼저 확인하고 거절한다 — 대가로 **거절될 요청도 좌석 락(NOWAIT)을 잠깐 잡는다**. **예외**: L5(카운터 갱신이 곧 판정, 사용자 허용)와 비교용 `-early` 변형 2개(L2e·L4e(advisory-try-early·quota-nowait-early) — 좌석 확인 전에 거절, 거절될 요청이 좌석 락을 잡지 않는다; 사용자 재합의 '명세를 보존하고 추가로 확인'). SERIALIZABLE의 40001은 커밋에서도 나므로 순서와 무관하다.
- **타이머**: `prepare`(⓪.1) · `acquire`(①.3) · `check`(④의 판정 호출) · `span`(①.3 시작 ~ ④ 끝 — 그 사이 좌석 읽기·확인 포함, 명세 §9.1 '실 락 획득부터 판정까지'). 하네스는 k6 전후 차분(건수·평균)을 쓴다. MAX는 최근 약 2분 창이라 짧은 셀에서는 직전 예열이 섞여 비교에 쓰지 않는다.
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

### 2.4 L2 `advisory-try` · L2e `advisory-try-early` — `pg_try_advisory_xact_lock(회차, 사용자)`

```kotlin
override fun acquire(command: HoldSeatCommand): Boolean {
    val got = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean::class.java, *advisoryKey(command)) == true
    if (!got && early) limitExceeded()      // L2e: 좌석 확인 전에 거절
    return got                              // L2: 좌석 확인 뒤 check에서 거절
}
override fun check(command: HoldSeatCommand, entered: Boolean) {
    if (!entered) limitExceeded()
    policy.check(command.scheduleId, command.userId, properties.maxPerUser)
}
```

- 같은 사용자의 요청이 진행 중이면 기다리지 않고 409. ADR-003의 '기다리지 않는다' 원칙과 같은 방향이지만, **매수가 0이어도** 동시에 누른 두 번째 탭은 거절된다(가짜 거절).
- L2(advisory-try)와 L2e(advisory-try, 좌석 확인 전 거절)는 **거절 시점만** 다르다 — L2는 에러 우선순위를 지키는 대신 거절될 요청이 좌석 락을 잡고, L2e는 좌석 락을 잡지 않는 대신 팔린 좌석이어도 '매수 초과'로 답한다. S7(이긴 쪽 롤백)·S2(사용자 100명 × 각자 좌석 10개 동시)가 이 차이를 잰다.

### 2.5 L3 `quota-lock` — 쿼터 행 `FOR UPDATE`

```kotlin
override fun <T> around(command: HoldSeatCommand, block: () -> T): T { ensureQuotaRow(jdbc, command); return block() }
override fun acquire(command: HoldSeatCommand) {
    jdbc.queryForList("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", …)
}
```

- 잠글 행이 없던 문제를 **잠글 행을 만들어서** 푼다(`user_hold_quota`, V5). 행은 트랜잭션 밖에서 미리 만든다(`prepare`) — 트랜잭션 안의 `INSERT … ON CONFLICT`는 아직 커밋 안 된 같은 키 행을 만나면 그 트랜잭션이 끝날 때까지 기다려(사용자 첫 요청들) L4(쿼터 행 SKIP LOCKED)의 '기다리지 않음'을 깬다.
- 준비는 **일반 SELECT 먼저**, 없을 때만 INSERT다. `INSERT … ON CONFLICT DO NOTHING`은 이미 있는 행이라도 그 행을 **UPDATE 중인** 트랜잭션(L5(카운터 조건부 UPDATE)의 cnt + 1)이 끝날 때까지 기다린다(특성 테스트로 확인 — §6.1). 그러면 같은 사용자의 대기가 트랜잭션 밖·타이머 밖으로 샌다.
- 대가: 테이블 추가, **매 요청** 트랜잭션 밖 조회 1회(+ 첫 요청이면 INSERT) — 커넥션을 한 번 더 빌린다(S4(도착률 계단, 요청마다 새 사용자·좌석) 처리량 회귀 H3(S4 처리량은 대조군과 같음)의 원인 후보).

### 2.6 L4 `quota-nowait` · L4e `quota-nowait-early` — 쿼터 행 `FOR UPDATE SKIP LOCKED`

```kotlin
val got = jdbc.queryForList("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE SKIP LOCKED", …).isNotEmpty()
if (!got && early) limitExceeded()
return got
```

- L2(advisory-try)와 같은 의미(즉시 거절, 가짜 거절 있음)를 행 락으로. 행은 `prepare`가 커밋해 두므로 0행 = 다른 트랜잭션이 잠금.
- **원안은 `FOR UPDATE NOWAIT`였다**(명세 인터뷰). NOWAIT는 잠겨 있으면 오류(55P03)를 내고, PostgreSQL은 오류가 난 트랜잭션을 더 쓸 수 없게 만든다 — '진입 못 해도 좌석을 먼저 확인'(에러 우선순위)을 할 수 없어 오류 없이 0행을 돌려주는 SKIP LOCKED로 바꿨다(재합의 2026-10-06). 기다리지 않는다는 의미는 같고, 오류 경로(예외 생성·번역)가 없어진다. L4·L4e(quota-nowait·quota-nowait-early) 모두 SKIP LOCKED라 둘의 차이는 거절 시점뿐이다.

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
- 카운터 = 홀드 + 확정 예약 수: 선점 +1(좌석에서 지면 같은 트랜잭션 롤백으로 원복), 확정은 홀드 → 예약이라 그대로, 만료 −1. **카운터를 내리는 주체가 생긴다**(만료 배치 — counter일 때만, ADR-007과 연결). 어긋나면 `cnt >= 0` 제약이 만료 배치를 실패시켜 드러낸다(무음 보정 없음). 판정기 `v_counter_mismatch`로 끝 상태를 대조한다.
- 매수 초과 사용자는 **좌석 락을 아예 잡지 않는다** — 3b의 '이긴 쪽 롤백' 대가(ADR-003 §8 ①)가 이 방식에서는 생기지 않을 것으로 예상(§3 H5(S7에서 L5만 억울한 좌석 0)).

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
| L2 advisory-try | 같음 | 즉시 409(좌석 확인 뒤) | 안 막음 | ④ | — |
| L2e advisory-try-early | 같음 | 즉시 409(좌석 확인 전) | 안 막음 | **①.3** | — |
| L3 quota-lock | 쿼터 행 | 커넥션 쥐고 대기 | 안 막음 | ④ | `user_hold_quota` |
| L4 quota-nowait | 쿼터 행(SKIP LOCKED) | 즉시 409(좌석 확인 뒤) | 안 막음 | ④ | 같음 |
| L4e quota-nowait-early | 같음 | 즉시 409(좌석 확인 전) | 안 막음 | **①.3** | 같음 |
| L5 counter | 쿼터 행(UPDATE) | 행 락 대기 후 재평가 | 안 막음 | **①.3** | 같음 + 만료 감소 |
| L6 serializable | (술어 락) | 충돌 시 409 | **충돌할 수 있음** | ④ | — |
| L7 serializable-retry | 같음 | 충돌 시 최대 3번 다시 | 같음 | ④ | — |

## 3. 가설

| # | 가설 | 틀렸다고 판정할 관측 |
|---|------|------------------|
| H1 | L0(제어 없음)은 좌석 3b 아래에서도 S2(사용자 100명 × 각자 좌석 10개 동시) 매수 초과를 낸다(ADR-002와 같은 크기). L1~L7(advisory~serializable-retry)은 전 회차 초과 0 | L0(제어 없음) 초과 0(측정 설계 오류) / L1~L7(advisory~serializable-retry) 어느 회차든 초과 > 0 |
| H2 | 가짜 거절(매수 < 2인데 거절)은 즉시 실패형(L2·L4·L2e·L4e(advisory-try·quota-nowait·advisory-try-early·quota-nowait-early))과 L6(SERIALIZABLE)에서 크고, 대기형(L1·L3(advisory·quota-lock))·L5·L7(counter·serializable-retry)은 0에 가깝다 | 대기형에서 가짜 거절 > 0 / 즉시 실패형에서 0 |
| H3 | S4(사용자마다 1요청 — 같은 사용자 경합 없음)에서 처리량 한계는 대조군과 같다. L3·L4·L5(quota-lock·quota-nowait·counter)는 쿼터 행 준비 왕복만큼, L6·L7(serializable·serializable-retry)은 SSI 비용만큼 약간 낮을 수 있다 | 계단 2칸 이상 차이 |
| H4 | S2(사용자 100명 × 각자 좌석 10개 동시)에서 대기형(L1·L3·L5(advisory·quota-lock·counter))은 같은 사용자 10요청이 커넥션을 쥐고 줄 서 풀 대기가 커지고, 즉시 실패형은 작다 | 대기형과 즉시 실패형의 획득 대기가 같다 |
| H5 | S7(이미 2매인 사용자 U + 일반 사용자들이 같은 좌석)에서 L0~L4·L6·L7(none~quota-nowait·serializable·serializable-retry)은 U가 좌석 락을 먼저 잡았다가 매수에서 롤백할 때 일반 사용자가 409를 받고 좌석이 비는 일이 생기고, **L5(카운터 조건부 UPDATE)는 U가 좌석 락 전에 지므로 0**. U는 같은 사용자 동시 요청이 없어 진입은 성공하므로 L2e·L4e(advisory-try-early·quota-nowait-early)도 L2·L4(advisory-try·quota-nowait)와 같다(차이는 S2(사용자 100명 × 각자 좌석 10개 동시)의 같은 사용자 동시 요청에서) | L5(카운터 조건부 UPDATE)에서 억울한 409 > 0 / 나머지에서 0 |
| H6 | 매수 확인 비용은 인덱스(`seat_hold.user_id`, `reservation(schedule_id, user_id)`) 덕에 배경 규모에 거의 무관하다(사용자 제기 "인덱스라 큰 차이 없을 것") | 배경 100만에서 지연이 배경 0의 수 배 |
| H7 | S3(만료·확정 포함)에서 L5 카운터는 끝 상태에서 홀드 + 확정 예약과 같다 | `v_counter_mismatch` > 0 |

## 4. 판정 기준

| 기준 | 지표 |
|------|------|
| ① 매수 정합성 | 사용자당 성공 ≤ 2, 판정기 `v_over_limit_users` 0 — 전 회차 |
| ② 가짜 거절 | S2(사용자 100명 × 각자 좌석 10개 동시)에서 201 < 2인데 `HOLD_LIMIT_EXCEEDED`를 받은 사용자 수 |
| ③ 처리량 회귀 | S4(도착률 계단, 요청마다 새 사용자·좌석) 엄격 한계·커넥션 획득 대기 — 대조군 대비 |
| ④ 이긴 쪽 롤백 | S7(2매 보유자 + 일반 20명이 같은 좌석)에서 끝 상태 AVAILABLE인데 일반 사용자가 409 `SEAT_NOT_AVAILABLE`을 받은 좌석 수·409 수 |
| ⑤ 매수 확인 지연 | 앱 타이머(`seat.hold.limit.acquire`·`check` 평균·최대) · DB 벤치(배경 0/10만/100만, 실행 계획) |
| ⑥ 실패 모드 | 응답 분류(타임아웃·연결 실패·5xx), 데드락, 40001·재시도 수 |
| ⑥′ 40001의 출처(사용자 제기 2026-10-07) | **같은 사용자 충돌**(막아야 할 진짜 위험) vs **다른 사용자 충돌**(막을 필요 없는 비용)을 가른다 — S4(도착률 계단, 요청마다 새 사용자·좌석)는 요청마다 사용자가 달라 40001이 전부 다른 사용자 충돌(설계상 분리). S2(사용자 100명 × 각자 좌석 10개 동시)는 섞여 있어 충돌 건별로는 못 가르고(앱 카운터는 수만, PostgreSQL 오류에 상대 트랜잭션 정보 없음) 사용자 결과로 가른다 — 매수 초과 0 = 같은 사용자 충돌을 막음, 가짜 거절 > 0 = 다른 사용자 충돌이 사용자에게 닿음. 건별 분리(같은 사용자만 동시에 보내는 셀 등)와 **40001 원인별 집계**(PostgreSQL 메시지로 읽기-쓰기 의존 'read/write dependencies' — SSI 술어 락 vs 쓰기-쓰기 'concurrent update' — 같은 행 갱신, 그리고 난 문장·커밋 시점)는 후속 측정 |
| ⑦ 카운터 정합 | S3(입장→선점→확정/이탈 전체 흐름) 끝 상태 `v_counter_mismatch`(L5(카운터 조건부 UPDATE)만) |

## 5. 측정 계획

| 대상 | 시나리오 | 단계 | 회차 |
|------|---------|------|------|
| 매수 방식 10개 | S2(사용자 100 × 좌석 10 동시) · S4(16단계) | L2 · L4(앱·DB CPU 2·4개) | 5 |
| 매수 방식 10개 | S3(입장→선점→확정/이탈 전체 흐름) 원본 × 이탈 0/20/50 | L4(앱·DB CPU 4개) | 3 |
| 매수 방식 10개 | S7 이긴 쪽 롤백(신규) — 일반 20명(`S7`) · 일반 1명(`S7-m1`, 재합의) | L4(앱·DB CPU 4개) | 5 |
| 매수 확인 쿼리 2개 | DB 직접 벤치(pgbench) — 배경 0 / 10만 / 100만 | L4(앱·DB CPU 4개) | 조합당 1 |

- 좌석 3b·인덱스 V2·풀 10·배경 0·타임아웃 30s 등 나머지는 ADR-003과 같다. 회차 우선 순서. 하네스 `k6/ADR-005/`.
- 약 50~55시간(추정 — ADR-003 실측 비례). DB 벤치는 캠페인 끝에 한 번(`campaign.sh`).
- 알려진 편향(측정 전): SERIALIZABLE의 40001은 좌석 충돌에서도 나서 `HOLD_LIMIT_EXCEEDED`로 섞인다(S7(2매 보유자 + 일반 20명이 같은 좌석)에서 두드러질 수 있음) · L5 카운터 감소는 만료 배치가 실제로 지운 홀드로 하지만, 만료 배치 자체의 동시성(확정과의 경합)은 ADR-007 몫이다.

## 6. 측정 전 검증

### 6.1 구현 중 발견

- **JdbcTemplate의 55P03 번역**: 쿼터 행 NOWAIT(L4(quota-nowait))의 '잠겨 있음'(55P03)을 `PessimisticLockingFailureException`으로 잡도록 썼는데, JdbcTemplate은 이것을 `UncategorizedSQLException`으로 번역했다(ADR-003 3b는 JPA 경로라 `PessimisticLockingFailureException`이었다). catch를 지나 500이 됐고 경합 테스트가 잡았다. 같은 SQLState도 **어느 계층을 거치느냐에 따라 다른 예외 타입**이 된다 — 예외 타입이 아니라 SQLState로 가르도록 고쳤다.
- **판정기 항목 null**: `v_counter_mismatch`를 counter가 아닐 때 null로 냈더니 `v_*`를 합산하는 기존 테스트 10개가 NPE로 깨졌다 → counter일 때만 항목을 낸다.
- 기본 좌석 전략 변경(none → 3b)으로 '기본값 = none' 테스트 1개가 깨졌다 — 명세 변경이라 기대값을 바꿨다.
- **`INSERT … ON CONFLICT DO NOTHING`이 UPDATE 중인 행을 기다린다**(코드 리뷰 지적 → 특성 테스트로 확인): 이미 있는 쿼터 행이라도 다른 트랜잭션이 그 행을 UPDATE(L5(카운터 조건부 UPDATE)의 cnt + 1)하고 커밋하지 않았으면, 충돌 검사가 그 트랜잭션의 끝을 기다린다. `FOR UPDATE`(잠금만)는 그렇지 않다. 그래서 L5(카운터 조건부 UPDATE)에서 같은 사용자의 대기가 트랜잭션 밖 준비 단계로 새어 타이머·커넥션 해석이 틀어질 뻔했다 → 준비를 '일반 SELECT 먼저'로 바꾸고, 특성 테스트(`CounterUserLimitTest`)가 이 동작을 고정한다.
- **NOWAIT 오류는 트랜잭션을 버린다**: 에러 우선순위를 지키려면 '진입 못 함'을 기록하고 좌석을 계속 확인해야 하는데, PostgreSQL은 오류가 난 트랜잭션의 다음 문장을 거부한다 → L4(quota-nowait)를 SKIP LOCKED로(§2.6).

### 6.1.1 코드 리뷰 (中 듀얼 1패스 — Opus ∥ codex)

- 지적 15건(중복 병합) 중 반영: 에러 우선순위(즉시 실패형 — 재합의로 명세 순서 + `-early` 변형), 타이머 구간(span·prepare 추가), ON CONFLICT 대기(위), S7(2매 보유자 + 일반 20명이 같은 좌석) 억울한 409를 코드 무관으로, S3(입장→선점→확정/이탈 전체 흐름) 계획 회차 3, S7 setup의 SERIALIZABLE 충돌 재시도, DB 벤치를 캠페인에 넣고 없으면 '미측정', 40001·재시도 결정적 테스트, '다른 사용자는 막지 않음'을 락을 쥔 채 확인하는 테스트, 매수 방식은 3b에서만(기동 거부), counter + 배경 행 거부, MAX 비교 제외.
- 기록만: V4(유니크 전용 위치)가 V5보다 낮은 번호라 V5까지 적용된 영속 DB에서 유니크 전략으로 바꾸면 Flyway가 거부한다 — 하네스는 회차마다 DB를 지워 측정 영향 없음. 앱 2대에서 만료 배치 두 개가 카운터 감소 UPDATE를 서로 다른 순서로 잡으면 교착할 수 있다 — 이 ADR은 앱 1대.

### 6.2 테스트 (151개 green)

- 매수 방식마다 컨텍스트 하나(`UserLimitStrategyTest` × 10): 같은 사용자 동시 10좌석(5라운드) — **L0(제어 없음)은 5라운드 안에 초과가 났고(양성 대조 — 좌석 3b 아래에서도 매수 경합 그대로, 가정 1의 테스트 수준 실증)**, 나머지는 성공·DB 매수 모두 ≤ 2 · 다른 사용자 20명 동시 — 서로 막지 않음(L6·L7(serializable·serializable-retry)은 SSI 충돌 거절 허용) · 같은 좌석 50명 — 8개 모두 1명만 이김(가정 2) · 에러 우선순위(L5(카운터 조건부 UPDATE)만 매수 초과) · 카운터 정합(선점·좌석 실패 롤백·확정·만료) · 격리 수준(`SHOW transaction_isolation`) · 사용자 A가 진입을 쥔 동안 다른 사용자는 끝나고 같은 사용자는 대기형이면 기다리고 즉시 실패형이면 거절(경합 중 에러 우선순위 — L2·L4(advisory-try·quota-nowait)는 팔린 좌석이면 좌석 불가, `-early`는 매수 초과) · SERIALIZABLE 충돌을 임계 구역 지연으로 결정적으로 만들어 L6(SERIALIZABLE) 거절 1 + 40001 카운터, L7(SERIALIZABLE + 재시도) 재시도로 둘 다 성공 · ON CONFLICT 대기 특성.

## 7. 실험 결과

> 경로는 repo 루트 기준. `R` = `k6/ADR-005/results/20261006-adr005-7acad14b`. 표는 `R/COMPARISON.md`·`R/comparison.json`(`scripts/compare.py`, errsplit 뒤 생성)과 조건별 `SUMMARY.md`·`summary.json`(`scripts/summarize.py`)에서 옮겼다. 값 = 중앙값 [최소–최대], n = 쓴 회차/전체.

### 7.1 측정 경과

| 항목 | 값 |
|------|----|
| 캠페인 | `20261006-adr005-7acad14b` — 2026-10-06 19:05 ~ 10-08 21:38 (약 50.5시간), SHA 7acad14b(**이력 정리로 현재 `627dbfaf`** — 코드·하네스 내용은 같고 커밋 SHA만 바뀜, 아래 §10 대응표) |
| 조건 × 회차 | 30조건(매수 방식 10 × {s24(S2·S4, L2·L4) · s3(S3, L4) · s7(S7·S7-m1, L4)}), 회차 우선. 정상 회차 **390** = S2·S4(같은 사용자 10좌석·처리량 계단) 200 + S3(입장→선점→확정/이탈 전체 흐름) 90 + S7(2매 보유자 + 일반 20명이 같은 좌석)·S7-m1(2매 보유자 + 일반 1명) 100 — 계획과 같다 |
| 비정상 회차 | 측정 경로 단절(`path-gap`) 4회(모두 S3 — counter a20(이탈 20%) r1 · quota-nowait-early a0(이탈 0%) r2 · serializable-retry a0 r2 · serializable a20 r2) → 끝에 재측정 1바퀴에서 모두 ok(원 회차는 `.path-gap-*`로 보존). 최종 비정상 0 |
| DB 벤치 | 배경 0 / 10만 / 100만 — 10-08 21:33 ~ 21:38 |
| 경로 | 유선(ADR-003과 같음 — L4(앱·DB CPU 4개) S4(도착률 계단, 요청마다 새 사용자·좌석) 약 4,300건/s 상한, 원인 미확정) |

### 7.2 결과 요약

| 가설 | 판정 | 핵심 수치 |
|------|------|---------|
| H1 L0(제어 없음)은 S2(사용자 100명 × 각자 좌석 10개 동시) 매수 초과를 내고 L1~L7(advisory~serializable-retry)은 0 | **채택** | L0(제어 없음) 매수 초과 사용자 L2(앱·DB CPU 2개) 15 [14–18](ADR-002 L2 17 [11–21]과 같은 크기) · L4(앱·DB CPU 4개) 22 [12–25](ADR-002 L4 15 [10–17]보다 큼 — 좌석 3b 아래에서도 매수 경합 그대로). 나머지 9개는 S2·S3·S7(같은 사용자 10좌석·전체 흐름·이긴 쪽 롤백) 전 회차 0. 판정기 전수: 대조군 뺀 351회 위반 0 |
| H2 가짜 거절은 즉시 실패형·L6(SERIALIZABLE)에서 크고, 대기형·L5·L7(counter·serializable-retry)은 0에 가깝다 | **부분 기각** | **L6 serializable만 크다 — L2(앱·DB CPU 2개) 69 [66–76] · L4(앱·DB CPU 4개) 69 [51–73]명(100명 중)**. L7(SERIALIZABLE + 재시도) 2~3명. 즉시 실패형 L2·L4·L2e·L4e(advisory-try·quota-nowait·advisory-try-early·quota-nowait-early)는 0 [0–2] — 단 S2(사용자 100명 × 각자 좌석 10개 동시)는 사용자당 10요청 · 상한 2라 여유가 커 즉시 실패형의 가짜 거절을 잡기 어려운 설계다(§7.3 ②) |
| H3 S4(도착률 계단, 요청마다 새 사용자·좌석) 처리량은 대조군과 같다(쿼터 계열은 약간 낮을 수 있음) | **advisory 계열 채택 · 쿼터 계열·SERIALIZABLE 기각** | advisory 계열(L1·L2·L2e(advisory·advisory-try·advisory-try-early))은 대조군과 같다(L2(앱·DB CPU 2개) 2,866 · L4(앱·DB CPU 4개) 4,300). **쿼터 행 계열은 요청당 커넥션을 3번 빌리고 회귀했다** — quota-lock·quota-nowait·counter는 초기 회차 L2 2,866(=대조군) · L4 2,866(대조군 4,300의 한 칸 아래)에서 뒤 회차 L2·L4 1,911(L4 기준 두 칸 아래, −56%)로 커졌고, quota-nowait-early는 처음부터 대부분 1,911이었다. **SERIALIZABLE은 포화점 L2 1,974 · L4 1,994**(retry L2 1,263 · L4 1,843) |
| H4 S2(사용자 100명 × 각자 좌석 10개 동시)에서 대기형은 줄 서 풀 대기가 커지고 즉시 실패형은 작다 | **부분 기각** | 사전 지표(커넥션 획득 대기)로 보면 L2(앱·DB CPU 2개)에서는 대기형 advisory 773ms > advisory-try 467ms지만 L4(앱·DB CPU 4개)에서는 57 vs 65ms로 차이가 없거나 뒤집히고, 대조군(대기 없음)도 724 / 58ms로 advisory와 같다. 대기형으로 묶은 **counter가 가장 작다(55 / 16ms)** |
| H5 S7(2매 보유자 + 일반 20명이 같은 좌석)에서 L5(카운터 조건부 UPDATE)만 억울한 좌석 0 | **채택(M=1에서)** | `S7-m1`: **counter 0**, 나머지 63~81석(100석 중). `-early`는 일반형과 같다. `S7`(M=20)은 전 방식 0~1석 — 경쟁자가 많으면 늦게 온 사람이 가져가 피해가 흡수된다 |
| H6 매수 확인 비용은 배경 규모에 거의 무관 | **채택** | DB 벤치 1연결: 홀드 수 0.030 / 0.030 / 0.031ms, 예약 수 0.025 / 0.028 / 0.028ms(배경 0 / 10만 / 100만). 배경이 있으면 인덱스(`idx_seat_hold_user_id`·`idx_reservation_schedule_user`)를 탄다 |
| H7 S3(입장→선점→확정/이탈 전체 흐름)에서 L5 카운터 = 홀드 + 확정 예약 | **채택** | `v_counter_mismatch` 이탈 0/20/50 × 3회 전부 0 |

### 7.3 판단 기준별 분석

#### ① 매수 정합성

**결론**: 제어 없음(L0)만 매수를 넘겼고, 9개 방식은 모든 시나리오·회차에서 1인 2매를 지켰다.

**근거**: S2(사용자 100명 × 각자 좌석 10개 동시) 매수 초과 사용자(판정기 `v_over_limit_users`) — L0(제어 없음) L2(앱·DB CPU 2개) 15 [14–18] · L4(앱·DB CPU 4개) 22 [12–25], 나머지 0. 응답 쪽(201 > 2인 사용자)과 판정기가 회차마다 같다(응답≠끝 상태 사용자 0, 201 − 홀드 행 0). 판정기 전수(`R/COMPARISON.md` '판정기 전수'): 대조군(L0(제어 없음))을 뺀 정상 회차 351회 위반 합 0. 양성 확인 — L0(제어 없음) S2(사용자 100명 × 각자 좌석 10개 동시)는 L2·L4(앱·DB CPU 2·4개) 각 5/5회 위반.

**편향·한계**: S2(사용자 100명 × 각자 좌석 10개 동시)는 사용자 100명 × 10좌석 한 형태다. 같은 사용자의 요청 수·도착 간격이 다르면 초과 크기는 달라지지만, 막는 방식의 정합성(0)은 구조상 같다.

#### ② 가짜 거절 — 매수가 남았는데 거절

**결론**: 가짜 거절은 **SERIALIZABLE(L6)에서만 크다** — 사용자 100명 중 약 69명이 2매를 못 채웠다(잃은 성공 약 98건 — 201 102 / 상한 200). 재시도(L7)는 2~3명으로 줄였다. 즉시 실패형은 0~2명이지만, **이 셀은 즉시 실패형의 가짜 거절을 잡기 어려운 설계**다.

**근거**(S2(사용자 100명 × 각자 좌석 10개 동시), 가짜 거절 사용자 = 끝 상태 매수 < 2인데 `HOLD_LIMIT_EXCEEDED`를 받은 사용자):

| 방식 | L2(앱·DB CPU 2개) | L4(앱·DB CPU 4개) | 201 수(상한 200) |
|------|----|----|----------------|
| serializable | **69 [66–76]** | **69 [51–73]** | 102 / 99 |
| serializable-retry | 2 [0–3] | 3 [0–6] | 198 / 196 |
| advisory-try · -early | 0 · 0 | 0 [0–2] · 0 [0–1] | 200 |
| quota-nowait · -early | 0 · 0 | 0 · 0 | 200 |
| advisory · quota-lock · counter | 0 | 0 | 200 |

- L6(SERIALIZABLE)의 잃은 성공 약 98건이 같은 사용자 충돌에서 왔는지 다른 사용자 충돌에서 왔는지는 **이 셀로 가를 수 없다**(§4 ⑥′(40001의 출처) — 충돌 건별 상대 정보 없음). 재시도 없는 SSI에서는 같은 사용자 요청끼리의 충돌만으로도 두 번째로 성공했어야 할 요청이 중단될 수 있다. 간접 근거: 같은 겹침에서 try-lock 방식은 가짜 거절이 0이었다 — 같은 사용자 동시 요청만으로는 이만큼 잃지 않는다는 쪽이지만 결정적이지 않다. 다른 사용자 충돌이 실제로 대량으로 나는 것은 S4(도착률 계단, 요청마다 새 사용자·좌석)에서 확인된다(⑥′(40001의 출처)).
- 즉시 실패형이 0에 가까운 이유(**추정**): 사용자당 10요청 · 상한 2라 여유가 크다 — 같은 사용자 10요청 중 앞 요청이 커밋한 뒤 도착한 요청이 진입해 2매를 채운다. 요청 수가 상한과 같으면(2요청) 결과가 다를 수 있다(미측정 — §9).

#### ③ 처리량 회귀 — 다른 사용자까지 느려지나

**결론**: advisory 계열은 처리량을 잃지 않는다. **쿼터 행 계열(quota-lock·quota-nowait·-early·counter)은 회귀하고, 그 크기가 캠페인 뒤쪽 회차에서 커졌다.** 원인은 판정 자체가 아니라 **요청마다 트랜잭션 밖에서 쿼터 행을 확인·생성하는 준비 단계(prepare)** — S4(도착률 계단, 요청마다 새 사용자·좌석)에서 요청당 커넥션을 3번 빌린다. SERIALIZABLE은 포화점이 약 1,974~1,994건/s로 낮다.

**근거 1 — 요청당 커넥션 빌림 수**(`after-k6.json` Hikari 획득 COUNT ÷ 선점 요청 수, L4(앱·DB CPU 4개) S4(도착률 계단, 요청마다 새 사용자·좌석) rep1): none 1.00 · advisory-try 1.00 · serializable 1.00 · **quota-lock 3.00 · counter 3.00**. S4(도착률 계단, 요청마다 새 사용자·좌석)는 요청마다 새 사용자라 prepare가 `SELECT`(행 없음) → `INSERT`(자동 커밋) → 본 트랜잭션으로 세 번 빌린다. 획득 1회당 대기는 쿼터 계열이 오히려 낮지만(약 23ms vs 대조군 34~46ms) 3배 하면 prepare 타이머 약 70ms와 맞는다.

**근거 2 — S4(도착률 계단, 요청마다 새 사용자·좌석) 엄격 한계·포화점**(건/s — 요청마다 다른 사용자라 같은 사용자 경합은 없다):

| 방식 | L2(앱·DB CPU 2개) 엄격 한계(회차별) | L4(앱·DB CPU 4개) 엄격 한계(회차별) | 포화점 L2 / L4(앱·DB CPU 2/4개) | 커넥션 획득 대기 평균 ms L2 / L4(앱·DB CPU 2/4개) |
|------|------------------|------------------|-------------|---------------------------|
| none | 2,866 ×5 | 4,300 ×5 | 3,579 / ≥4,300 | 45.5 / 34.1 |
| advisory-try | 2,866 ×4 · 2,859 ×1 | 4,300 ×5 | 3,597 / ≥4,300 | 45.1 / 33.5 |
| advisory | 2,866 ×4 · 2,845 ×1 | 4,300 ×4 · 1,274 ×1 | 3,461 / ≥4,300 | 48.6 / 34.4 |
| quota-lock | 2,866 ×2 → **1,911 ×3** | 2,866 ×2 → **1,911 ×3** | 2,686 / 2,657 | 23.5 / 23.0 |
| quota-nowait | 2,866 ×2 → **1,911 ×3** | 2,866 ×2 → **1,911 ×3** | 2,540 / 2,483 | 23.9 / 24.0 |
| quota-nowait-early | **1,911 ×5** | 1,911 ×4 · 2,866 ×1 | 2,697 / 2,634 | 23.6 / 23.2 |
| counter | 2,866 ×2 · 2,838 → **1,911 ×2** | 2,866 ×1 → **1,911 ×4** | 2,838 / 2,568 | 22.1 / 23.1 |
| serializable | 1,974 | (엄격 한계 산출 불가 — 아래) | **1,974 / 1,994** | 68.6 / 24.3 |
| serializable-retry | 1,263 [1,262–1,847] | 1,843 | 1,263 / 1,843 | 93.8 / 22.2 |

- **시간에 따른 갈림**(quota-nowait-early는 L2(앱·DB CPU 2개) 전 회차·L4(앱·DB CPU 4개) 첫 회차부터 1,911이라 이 갈림에서 뺀다): quota-lock·quota-nowait는 1·2회차(10-06 20시 ~ 10-07 11시)에 L2 2,866이었다가 3회차(10-08 00시) 이후 1,911이다. counter는 10-07 11시경부터 1,911이 나온다. 같은 기간 none·advisory 계열은 흔들리지 않았다. 쿼터 계열만 요청당 자동 커밋 INSERT(커밋 1회 더 — WAL fsync)가 있어 서버 디스크·I/O 상태 변화에 더 민감했을 것으로 **추정**한다(원인 미측정 — 캠페인 중 서버 상태는 디스크 사용률만 확인). 회귀의 **크기**는 이 측정으로 확정하지 않는다 — 초기 회차에서도 L4(앱·DB CPU 4개)는 대조군보다 한 칸 아래(2,866 vs 4,300)였다는 것까지가 확실한 하한이다.
- **SERIALIZABLE의 L4(앱·DB CPU 4개) 엄격 한계(708 등)는 지표 산출이 만든 값이다**: 4,325 단계에서 성공 약 1,994건/s(목표 미달)로 포화된 뒤, 다음 6,487 단계의 성공(약 670건/s)이 p99 < 500ms를 만족해 '통과'로 집혔다(`summarize.py` s4 한계 규칙이 목표 미달 단계를 막지 않음). 실제 성공 상한은 포화점 약 1,994건/s(L2(앱·DB CPU 2개)와 같음)다. 하네스 개선 항목(§9).
- SERIALIZABLE은 요청마다 사용자가 달라도 40001이 수십만 건이다(S4(도착률 계단, 요청마다 새 사용자·좌석) L4(앱·DB CPU 4개) 615,532). 거절도 409라 성공 처리량이 낮아진다.

**편향·한계**: L4(앱·DB CPU 4개) S4(도착률 계단, 요청마다 새 사용자·좌석) 상한 4,300은 측정 경로 상한(ADR-003 §6.3) — 그 위의 차이는 판정하지 않는다. 쿼터 계열의 회귀는 **이번 구현의 prepare 비용**이 섞인 값이다 — 쿼터 행을 미리 만들어도 요청마다 `SELECT` 확인이 남으므로 회귀가 사라지지 않는다. prepare를 없애려면 판정을 트랜잭션 안 한 문장으로 바꿔야 한다(예: counter의 `INSERT … ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = cnt + 1 WHERE cnt + 1 <= 2` — 미측정, §9).

#### ④ 이긴 쪽 롤백 — 좌석이 비었는데 진 사람

**결론**: 경쟁자가 1명일 때(`S7-m1`) 2매 보유자 U가 좌석 락을 잡았다가 매수에서 롤백하면 일반 사용자가 409를 받고 좌석이 빈다 — **counter만 0석**이다. counter는 매수 판정이 좌석보다 먼저라 U가 좌석 락을 잡지 않는다. 경쟁자가 20명이면(`S7`) 전 방식이 0~1석 — 늦게 온 사람이 가져간다.

**근거**(억울한 좌석 수 / 100석):

| 방식 | S7(M=20) | S7-m1(M=1) | S7-m1(2매 보유자 + 일반 1명) U의 응답(HLE = HOLD_LIMIT_EXCEEDED 매수 초과 · SNA = SEAT_NOT_AVAILABLE 좌석 불가) |
|------|---------|-----------|--------------|
| none · advisory · advisory-try · -early | 0~1 | 67~68 | HLE(HOLD_LIMIT_EXCEEDED) 70~74 · SNA(SEAT_NOT_AVAILABLE) 26~30 |
| quota-lock · quota-nowait · -early | 0 | **79~81** | HLE(HOLD_LIMIT_EXCEEDED) 100 |
| serializable · -retry | 1 | 63~65 | HLE(HOLD_LIMIT_EXCEEDED) 66~70 |
| **counter** | **0** | **0** | HLE(HOLD_LIMIT_EXCEEDED) 100(좌석 락 전) |

- `-early` 변형은 일반형과 같다 — U는 같은 사용자 동시 요청이 없어 진입이 성공하고, 좌석 락을 잡은 뒤 매수에서 진다. -early의 이점은 **같은 사용자 동시 요청**에만 해당한다.
- 쿼터 계열이 79~81로 더 큰 것은 편향이다: 일반 사용자는 첫 요청이라 prepare(SELECT + INSERT)를 하고 들어와 U보다 늦어진다 → U가 100/100석에서 좌석 락을 먼저 잡았다.
- counter가 없애는 것은 3b 대가 ①(ADR-003 §8) 중 **매수 초과로 인한 롤백**뿐이다 — DB 오류 등 다른 원인의 롤백은 그대로다.

**편향·한계**: U와 일반 사용자의 시차(LEAD 1ms)·k6 발송 흩어짐(−1~3ms)에 결과가 민감하다. 비율 자체보다 '0이냐 아니냐'(counter vs 나머지)가 판정 근거다. S7-m1(2매 보유자 + 일반 1명)은 피해가 드러나도록 재합의로 넣은 조건이라, 경쟁자가 많은 원 설계(S7(2매 보유자 + 일반 20명이 같은 좌석))에서는 방식 간 차이가 없다.

#### ⑤ 매수 확인 지연

**결론**: 매수 확인 쿼리는 배경 100만 행에서도 약 0.03ms다(H6(매수 확인 비용은 규모 무관) 채택 — 사용자 예상 "인덱스라 큰 차이 없을 것"과 같다). 앱 안에서도 경합 없는 S4(도착률 계단, 요청마다 새 사용자·좌석)의 판정 구간(span)은 0.1~0.7ms(SERIALIZABLE 0.4~1.8ms)다. S2(사용자 100명 × 각자 좌석 10개 동시) L2(앱·DB CPU 2개)에서 check가 15~23ms로 큰 것은 쿼리 비용이 아니라 동시 요청이 몰린 대기로 **추정**한다(CPU·락 대기 분해 미측정).

**근거**: DB 벤치(`R/limit-bench/`, pgbench `-M prepared` 1연결) — 위 §7.2 H6(매수 확인 비용은 규모 무관). 실행 계획: 배경 0은 행이 적어 Seq Scan, 배경 10만·100만은 Index Scan. 앱 타이머는 §7.3 ③.

**편향·한계**: pgbench는 `status`를 문자열 상수로 넣는다(앱은 바인딩). 앱 타이머 MAX는 최근 2분 창이라 쓰지 않았다. **S2(사용자 100명 × 각자 좌석 10개 동시)의 p50·p99에는 트랜잭션 밖 prepare 시간이 섞여 있다** — S2 L2(앱·DB CPU 2개) prepare 평균 quota-lock 462 · quota-nowait 370 · -early 401 · counter 75ms(풀 대기열인지 같은 사용자 대기가 밖으로 샌 것인지 미분해).

#### ⑥ 실패 모드 · ⑥′ 40001의 출처

**결론**: 10개 방식 모두 **5xx 0, 데드락 0, Hikari 풀 타임아웃 0**(전 회차 측정). 실패는 대부분 S4(도착률 계단, 요청마다 새 사용자·좌석) 과부하 단계의 타임아웃·연결 실패이고, 그 밖에 S2(사용자 100명 × 각자 좌석 10개 동시) 연결 끊김 1건(counter L2(앱·DB CPU 2개) rep3)과 S3(입장→선점→확정/이탈 전체 흐름) 확정 기타 오류 2건(advisory·counter a20(이탈 20%) rep1)이 있다. SERIALIZABLE의 40001은 **S4(도착률 계단, 요청마다 새 사용자·좌석)에서 수십만 건 — 같은 사용자 경합이 없는 시나리오라 전부 다른 사용자 충돌**이다.

**근거**: 응답 분류 합계(정상 390회 — path-gap으로 보존된 4회·S7(2매 보유자 + 일반 20명이 같은 좌석) setup 제외, `R/comparison.json` errsplit) — 2xx 51,394,145 · 409 72,864,682 · 타임아웃(0-1050) 626,059 · 연결 실패(0-1211) 166,603 · 연결 끊김(0-1220) 4,741 · 기타(0-1000) 2 · **5xx 0**. 40001(앱 카운터 차분): S4(도착률 계단, 요청마다 새 사용자·좌석) L6(SERIALIZABLE) 176,715 / 615,532 · L7(SERIALIZABLE + 재시도) 232,024 / 1,235,828, S2(사용자 100명 × 각자 좌석 10개 동시) L6 802 / 799 · L7 1,480 / 1,659.

- ⑥′(40001의 출처): S4(도착률 계단, 요청마다 새 사용자·좌석)의 40001은 설계상 전부 다른 사용자 충돌이다. 같은 행을 둘이 고치는 일이 없으므로(사용자·좌석 모두 다름) 원인은 읽기-쓰기 의존(SSI 술어 락)으로 **추정**한다 — 메시지별 집계는 미측정(§9).

#### ⑦ 카운터 정합

**결론·근거**: S3(선점 → 확정·이탈 → 만료)에서 counter의 `v_counter_mismatch`는 이탈 0/20/50 × 3회 전부 0이다 — 만료 배치가 실제로 지운 홀드만큼 내리는 구현이 맞다. S3(입장→선점→확정/이탈 전체 흐름) 확정 수는 방식 간 같다(a0(이탈 0%) 10,000 · a20(이탈 20%) 약 9,700~9,760 · a50(이탈 50%) 약 8,020~8,120), 단 **serializable a50 7,803 · retry 7,953**으로 약간 낮다.

### 7.4 공통 편향

- **측정 경로 상한**: L4(앱·DB CPU 4개) S4(도착률 계단, 요청마다 새 사용자·좌석) 약 4,300건/s(ADR-003 §6.3).
- **쿼터 계열의 prepare**는 '매 요청 트랜잭션 밖 확인·생성'이라는 이번 구현의 비용이다 — 방식의 본질 비용과 섞여 있다(§7.3 ③).
- **고정 순서 캠페인의 시간 변화**: 회차 우선이지만 조건 순서는 회차마다 같다 — 쿼터 계열 S4(도착률 계단, 요청마다 새 사용자·좌석)가 회차에 따라 둘로 갈렸다(§7.3 ③). 서버 I/O 상태는 기록하지 않았다.
- **S7(2매 보유자 + 일반 20명이 같은 좌석)의 시차 민감도**(§7.3 ④) · **S2(사용자 100명 × 각자 좌석 10개 동시)의 즉시 실패형 민감도**(§7.3 ②).
- 앱 1대·풀 10·좌석 3b 고정.

## 8. 결정

**결정(2026-10-09 사용자 확정)** — **매수 제어 기본은 L2 `advisory-try`** 로 한다(현 실측 기준). **L5 `counter`는 조건부 후보**로 두고, prepare를 없앤 형태(트랜잭션 안 한 문장 upsert)를 **ADR-006**에서 advisory-try·none과 같은 캠페인으로 다시 잰다 — 그 결과에 따라 기본이 counter로 바뀔 수 있다.

| 기준 | advisory-try | counter(이번 구현) | advisory(대기) | quota-lock · quota-nowait | serializable · -retry |
|------|-------------|------------------|---------------|-------------------------|---------------------|
| ① 매수 정합성 | 0 | 0 | 0 | 0 | 0 |
| ② 가짜 거절(S2(사용자 100명 × 각자 좌석 10개 동시), 100명 중) | 0~2(민감도 낮은 셀) | 0 | 0 | 0 | **69** · 2~3 |
| ③ S4(도착률 계단, 요청마다 새 사용자·좌석) 엄격 한계 L2 / L4(앱·DB CPU 2/4개) (serializable은 포화점) | **2,866 / 4,300**(= 대조군) | 2,838 / 1,911(회차 따라 2,866~1,911) | 2,866 / 4,300 | 2,866→1,911 / 2,866→1,911 | 포화점 1,974 / 1,994 · 1,263 / 1,843 |
| ④ S7-m1(2매 보유자 + 일반 1명) 억울한 좌석 | 67 | **0** | 67 | 79~81 | 63~65 |
| S2(사용자 100명 × 각자 좌석 10개 동시) L2(앱·DB CPU 2개) p50 / p99 ms | 980 / 2,265 | **258 / 593** | 2,167 / 3,788 | 2,046 / 3,506 · 1,854 / 2,951 | 2,700 / 4,742 · 5,272 / 7,481 |
| 추가 스키마·경로 | 없음 | 쿼터 테이블 + 만료 배치 감소 | 없음 | 쿼터 테이블 | 없음 |
| 에러 우선순위 | 보존 | 매수 먼저(허용) | 보존 | 보존 | 커밋 시 충돌 |

- **왜 advisory-try인가**: 측정한 기준(①~③(매수 정합성~처리량 회귀), ④(이긴 쪽 롤백)의 M=20)에서 대조군 대비 손해가 없는 방식이다 — 매수 정합 0, 처리량 대조군과 같음, 스키마 추가 없음, 에러 우선순위 보존. 같은 사용자의 동시 요청은 기다리지 않고 바로 거절해 ADR-003의 '기다리지 않는다' 원칙과도 같은 방향이다. **대가**: ④(이긴 쪽 롤백) S7-m1(2매 보유자 + 일반 1명)에서 제어 없음과 같다(67석) — 3b의 '이긴 쪽 롤백' 피해(매수 초과 원인)는 그대로 남는다. 즉시 실패형의 가짜 거절은 S2(사용자 100명 × 각자 좌석 10개 동시) 설계상 민감도가 낮아(요청 10 · 상한 2) 요청 수 = 상한인 경우는 미측정이다.
- **counter를 조건부로 두는 이유**: 매수 초과 사용자가 좌석 락을 잡지 않는 유일한 방식이라 ④(이긴 쪽 롤백 피해)를 없애고(S7-m1(2매 보유자 + 일반 1명) 0석), 같은 사용자 동시 요청의 지연·획득 대기도 가장 작다. 그러나 이번 구현은 S4(도착률 계단, 요청마다 새 사용자·좌석)에서 요청당 커넥션을 3번 빌려 처리량이 회귀했고(L4(앱·DB CPU 4개) 2,866 → 1,911, 회차에 따라 커짐), 쿼터 행을 미리 만드는 것만으로는 요청별 확인 SELECT가 남아 해결되지 않는다. 판정을 트랜잭션 안 한 문장(`INSERT … ON CONFLICT DO UPDATE … WHERE cnt + 1 <= 2`)으로 바꿔 빌림을 1회로 만든 뒤 S4·S2(처리량 계단·같은 사용자 10좌석)·S7-m1(2매 보유자 + 일반 1명)을 다시 재서, 회귀가 대조군 수준이면 기본을 counter로 바꾸는 것을 검토한다. 그 밖의 대가: 만료 배치가 카운터를 내려야 함(ADR-007), 쿼터 테이블, 매수 초과 사용자가 팔린 좌석을 누르면 '매수 초과'로 답함.
- **advisory(대기형)**: 측정상 advisory-try와 거의 같다(S4(도착률 계단, 요청마다 새 사용자·좌석) 동일, S2(사용자 100명 × 각자 좌석 10개 동시) L4(앱·DB CPU 4개) 지연 동일 — L2(앱·DB CPU 2개) p99만 3,788 vs 2,265). 같은 사용자 요청이 커넥션을 쥔 채 기다린다는 점에서 ADR-003 원칙과 반대 방향이라 기본으로 두지 않는다. counter도 행 락 대기형이지만 대기 대상이 같은 사용자의 한 문장 UPDATE뿐이라 짧다(S2(사용자 100명 × 각자 좌석 10개 동시) 획득 대기 55ms vs advisory 773ms).
- **quota-lock · quota-nowait(-early)**: 에러 우선순위 보존·카운터 유지 경로 없음이라는 장점이 있지만, counter와 같은 prepare 비용(처리량 회귀)을 지면서 ④(이긴 쪽 롤백) 이점이 없다(79~81석).
- **serializable · -retry**: 가짜 거절 69명(재시도 없음), 포화점 약 2,000건/s, S4(도착률 계단, 요청마다 새 사용자·좌석)에서 다른 사용자끼리 충돌 수십만 건 — 제외.
- **-early 변형**: 일반형과 측정 차이가 없다(같은 사용자 동시 요청 외에는 같은 경로).

## 9. 다음 ADR로 넘기는 것

| 받는 곳 | 내용 |
|--------|------|
| **ADR-006 counter 1문장 upsert 재측정**(사용자 2026-10-09) | counter를 트랜잭션 안 한 문장 upsert로 바꿔 요청당 커넥션 빌림 1회 — S4·S2(처리량 계단·같은 사용자 10좌석)·S7-m1(2매 보유자 + 일반 1명) 재측정. 쿼터 계열의 시간에 따른 회귀(회차 3 이후 1,911)를 **조건 순서를 섞어** 다시 재고, 서버 I/O(fsync 지연) 지표를 함께 수집 |
| ADR-007 홀드 만료 | (counter를 채택하면) 만료 배치가 카운터를 내린다 — 만료 지연·실패 시 카운터가 커져 사용자가 덜 잡는 경로, 앱 2대에서 만료 배치 두 개의 감소 순서 교착 · 3b NOWAIT와 만료 배치의 행 락 충돌(ADR-003 §8 ③(만료·확정이 행 락을 쥔 동안에도 409)) · try-advisory 비교(NEXT N3) |
| ADR-008 확정 원자성 | 확정은 홀드 → 예약이라 카운터 변화 없음(이번 구현) — 확정 실패·보상 경로가 생기면 카운터 갱신 필요 |
| 후속 측정 | 40001 원인별 집계(읽기-쓰기 의존 vs 같은 행 갱신)·같은 사용자만 동시에 보내는 셀(§4 ⑥′(40001의 출처)) · **즉시 실패형의 가짜 거절이 요청 수 = 상한(2)일 때** · S7(2매 보유자 + 일반 20명이 같은 좌석) 시차 변화 · S2(사용자 100명 × 각자 좌석 10개 동시) prepare 시간의 분해 |
| 하네스 | S4(도착률 계단, 요청마다 새 사용자·좌석) 엄격 한계 규칙이 목표 미달 단계 이후를 '통과'로 집지 않게(SERIALIZABLE 708 같은 값 — §7.3 ③) · 배포에서 측정 결과 제외(서버 디스크 100% 사고 — 작업 로그) · 캠페인 중 서버 I/O 지표(디스크 지연·fsync) 수집 |

## 10. 사용한 파일 — 위치와 설명

> 경로는 repo 루트 기준. 앱 패키지 `src/main/kotlin/com/jun/labs/seatreservation/` = `…/`.

| 파일 | 설명 |
|------|------|
| `…/service/UserLimitStrategyType.kt` | 매수 방식 10개 열거(`seat.hold.limit-strategy`) |
| `…/service/impl/limit/UserLimitStrategies.kt` | 10개 구현 + `ActiveUserLimit` — prepare·acquire·check·around·onHoldsExpired |
| `…/service/impl/HoldSeatProcess.kt` | 갈래점(①.3 acquire · ④ check), 타이머 acquire·check·span |
| `…/service/impl/HoldSeatService.kt` | prepare(타이머) · around(SERIALIZABLE 변환·재시도) |
| `…/service/impl/ExpireHoldsService.kt` · `…/domain/ProductSeat.kt` | 만료 배치가 실제로 지운 홀드를 매수 방식에 넘김(counter만 감소) |
| `…/service/impl/hold/HoldStrategyStartupCheck.kt` | 매수 방식 ≠ none이면 좌석 3b만 기동 |
| `…/loadtest/LoadtestDataService.kt` | 판정기 `v_counter_mismatch`(counter만) · counter + 배경 행 거부 |
| `src/main/resources/db/migration/V5__user_hold_quota.sql` | 쿼터 테이블 |
| `src/test/kotlin/com/jun/labs/seatreservation/service/limit/UserLimitStrategyTest.kt` | 방식 10개 계약·경합·에러 순서·격리 수준·SERIALIZABLE 충돌·ON CONFLICT 특성 |
| `k6/ADR-005/` | 하네스(README — 지표 정의·S7(2매 보유자 + 일반 20명이 같은 좌석) 설계) · `scenarios/s2-same-user.js` · `s7-winner-rollback.js` · `scripts/run.sh`·`lib.sh`·`campaign.sh`·`summarize.py`·`compare.py`·`errsplit.py`·`limit-bench.sh` |
| `R/COMPARISON.md` · `comparison.json` · `errsplit.json` · `CAMPAIGN.log` · `limit-bench/` | 교차표(판정기 전수·응답 분류 포함)·응답 분류 원자료·캠페인 기록·DB 벤치 — §7 전부 |
| `R/<조건>/SUMMARY.md` · `summary.json` · `L*/<셀>/rep*/` | 조건별 요약·회차 원시(end-state.json·after-k6.json 등) |
| `docs/plans/2026-10-06/adr-005-user-limit/requirement-spec.md` · `log.md` | 합의 명세(재합의 3건) · 작업 로그·리뷰 ledger |

### 10.1 커밋 SHA 대응표 (2026-10-09 이력 정리)

push 전 이력에 100MB를 넘는 DB 벤치 원시 파일(pgbench 디버그 출력 `limit-bench/N*/q*-c1.txt` 16개, 약 1.5GB)이 들어 있어 GitHub가 거부했다 → push 안 된 커밋에서만 그 파일을 빼는 이력 정리(`git filter-branch --index-filter`, 사용자 승인) 후 gzip 본(5.9MB, 원본과 sha256 일치)을 새 커밋으로 넣었다. 코드·하네스·다른 결과 파일은 바뀌지 않았고 SHA만 바뀌었다. 결과 폴더의 `meta.json`·`plan.json`·로그에 적힌 옛 SHA는 이 표로 읽는다.

| 옛 SHA(결과·문서에 기록) | 새 SHA | 커밋 |
|------|------|------|
| `27bc12e2` | `27bc12e2` | feat(seat-reservation-lab): ADR-005 1인 매수 제어 8종 + 기본 좌석 전략 3b |
| `83fb4719` | `83fb4719` | docs(seat-reservation-lab): ADR-005 명세·작업 로그, ADR-003 로그 마감 |
| `899b317f` | `899b317f` | docs(seat-reservation-lab): ADR-005 선택지 8개·가설·측정 계획·측정 전 검증 |
| `2e4d83ea` | `255f21b7` | feat(seat-reservation-lab): ADR-005 하네스 — 매수 방식 축·S7 이긴 쪽 롤백(M=20·M=1) |
| `eb990eca` | `e4f621f8` | docs(seat-reservation-lab): ADR-005 S7 M=1 변형 재합의·하네스 스모크 기록 |
| `a89741e9` | `28285dfa` | fix(seat-reservation-lab): ADR-005 리뷰 반영 — 명세 순서·-early 변형·span 타이머·ON |
| `cd8682b2` | `52a1b7f7` | docs(seat-reservation-lab): ADR-005 리뷰 반영 — 재합의(명세 순서·-early·SKIP LOCK |
| `4e506390` | `5872131c` | fix(seat-reservation-lab): ADR-005 span 타이머 — 진입 직후부터, 거절로 끝나도 기록 |
| `4d02bdfa` | `588fda3c` | fix(seat-reservation-lab): ADR-005 하네스 — 배포에서 측정 결과 제외, -early 방식 허용 |
| `7acad14b` | `627dbfaf` | docs(seat-reservation-lab): ADR-005 로그 — 서버 디스크 100% 사고·정리·재점검 |
| `07559348` | `23e54143` | chore(seat-reservation-lab): ADR-005 리뷰 후 재스모크 결과 |
| `cb862dee` | `9d7f7911` | docs(seat-reservation-lab): ADR-005 재스모크·본측정 시작 기록 |
| `555e38f9` | `52655c19` | docs(seat-reservation-lab): ADR-005 판정 기준 — 40001의 출처(같은 사용자 vs 다른 사용자 |
| `38f91bc6` | `566dc1a5` | docs(seat-reservation-lab): ADR-005 후속 — 40001 원인별 집계 |
| `ee47cb93` | `e9315ee4` | docs(seat-reservation-lab): README — ADR별 본측정 기간·걸린 시간 |
| `a153fb59` | `94d25a15` | docs(seat-reservation-lab): ADR-005 본측정 종료 기록 |
| `3dbec9c1` | `42e78161` | docs(seat-reservation-lab): ADR-005 결과·결정 제안(counter, 차선 advisory-try) |
| `9f4c6801` | `4d02abf2` | chore(seat-reservation-lab): ADR-005 측정 결과 — 390회 + DB 벤치 |
| `beb56b03` | `b8225007` | docs(seat-reservation-lab): ADR-005 결과 리뷰 반영 — 기본 제안 advisory-try, cou |
| `ca5073d8` | `835b7ef8` | docs(seat-reservation-lab): ADR-005 재점검 반영 — 회차별 값·예외·포화점 표기 |
| `8509e0df` | `ff95ca16` | docs(seat-reservation-lab): ADR 약어마다 괄호 설명(ADR-000~005·README), 낡은 NEX |
| `d5971007` | `d4282444` | docs(seat-reservation-lab): ADR-005 HLE·SNA 약어 정의 |
| `c8ee85f4` | `cebf7071` | chore(seat-reservation-lab): 코드 주석의 ADR 번호를 현재 번호로 |
| `6be4ef0f` | `65ca0647` | docs(seat-reservation-lab): ADR-005 확정(advisory-try), 새 ADR-006(counte |
| `b1eacf4a` | `9210e14b` | docs(seat-reservation-lab): ADR-005 사이클 마감 — NEXT·측정 기록·완료 요약 |
