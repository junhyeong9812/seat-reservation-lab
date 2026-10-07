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

좌석 방식은 3b로 고정한다(ADR-003 결정, 앱 기본값 — 매수 방식이 none이 아니면 3b가 아닐 때 기동을 거부한다). 매수 방식은 설정 `seat.hold.limit-strategy`로 고르고, 좌석 전략이 연 **같은 트랜잭션** 안의 두 자리에 끼어든다. 트랜잭션 밖 자리도 둘이다(준비·감싸기).

| 단계 | 하는 일 | 매수 방식이 하는 일 |
|------|--------|-----------------|
| ⓪.1 유스케이스 진입(트랜잭션 밖) | `prepare` | 쿼터 행 준비(L3·L4·L5 — **매 요청**: 일반 SELECT로 있는지 보고 없을 때만 자동 커밋 `INSERT … ON CONFLICT DO NOTHING`). 타이머 `seat.hold.limit.prepare` |
| ⓪.2 | `around` | SERIALIZABLE 충돌 변환·재시도(L6·L7) |
| ①.1·①.2 커넥션 획득·`BEGIN` | 3b 전략의 트랜잭션 시작 | — |
| **①.3 사용자 단위 진입** | `acquire` | L1 advisory 락 · L2 try 락 · L3 쿼터 행 락 · L4 쿼터 행 `SKIP LOCKED` · L5 카운터 +1(=판정) · L6·L7 격리 수준 SERIALIZABLE. 즉시 실패형이 못 잡으면 '진입 못 함'만 기록하고 넘어간다(`-early`는 여기서 바로 409) |
| ② 좌석 읽기 | `SELECT … FOR NO KEY UPDATE NOWAIT`(3b) | — |
| ③ 선점 가능 확인 | AVAILABLE이 아니면 409 `SEAT_NOT_AVAILABLE` | — |
| **④ 매수 판정** | `check` | 진입 못 했으면 409, 아니면 홀드 수 + 확정 예약 수 < 2(L5는 ①.3에서 끝) — 아니면 409 `HOLD_LIMIT_EXCEEDED` |
| ⑤~⑧ 상태 전이·flush | ADR-003 §2.1 그대로 | — |
| ⑨.2 `COMMIT` | | advisory xact 락·쿼터 행 락 해제, SERIALIZABLE 충돌(40001)은 여기서도 날 수 있다 |

- **락 순서 = 사용자 → 좌석**(①.3이 ②보다 먼저). 좌석은 NOWAIT라 사용자 락을 쥔 채 좌석을 기다리는 일이 없다 → 교착 경로가 없다.
- **에러 우선순위 보존**: 락만 먼저 잡고 판정 순서는 그대로(좌석 불가 → 매수 초과). 즉시 실패형(L2·L4)도 진입에 실패하면 좌석을 먼저 확인하고 거절한다 — 대가로 **거절될 요청도 좌석 락(NOWAIT)을 잠깐 잡는다**. **예외**: L5(카운터 갱신이 곧 판정, 사용자 허용)와 비교용 `-early` 변형 2개(L2e·L4e — 좌석 확인 전에 거절, 거절될 요청이 좌석 락을 잡지 않는다; 사용자 재합의 '명세를 보존하고 추가로 확인'). SERIALIZABLE의 40001은 커밋에서도 나므로 순서와 무관하다.
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
- L2와 L2e는 **거절 시점만** 다르다 — L2는 에러 우선순위를 지키는 대신 거절될 요청이 좌석 락을 잡고, L2e는 좌석 락을 잡지 않는 대신 팔린 좌석이어도 '매수 초과'로 답한다. S7(이긴 쪽 롤백)·S2가 이 차이를 잰다.

### 2.5 L3 `quota-lock` — 쿼터 행 `FOR UPDATE`

```kotlin
override fun <T> around(command: HoldSeatCommand, block: () -> T): T { ensureQuotaRow(jdbc, command); return block() }
override fun acquire(command: HoldSeatCommand) {
    jdbc.queryForList("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE", …)
}
```

- 잠글 행이 없던 문제를 **잠글 행을 만들어서** 푼다(`user_hold_quota`, V5). 행은 트랜잭션 밖에서 미리 만든다(`prepare`) — 트랜잭션 안의 `INSERT … ON CONFLICT`는 아직 커밋 안 된 같은 키 행을 만나면 그 트랜잭션이 끝날 때까지 기다려(사용자 첫 요청들) L4의 '기다리지 않음'을 깬다.
- 준비는 **일반 SELECT 먼저**, 없을 때만 INSERT다. `INSERT … ON CONFLICT DO NOTHING`은 이미 있는 행이라도 그 행을 **UPDATE 중인** 트랜잭션(L5의 cnt + 1)이 끝날 때까지 기다린다(특성 테스트로 확인 — §6.1). 그러면 같은 사용자의 대기가 트랜잭션 밖·타이머 밖으로 샌다.
- 대가: 테이블 추가, **매 요청** 트랜잭션 밖 조회 1회(+ 첫 요청이면 INSERT) — 커넥션을 한 번 더 빌린다(S4 처리량 회귀 H3의 원인 후보).

### 2.6 L4 `quota-nowait` · L4e `quota-nowait-early` — 쿼터 행 `FOR UPDATE SKIP LOCKED`

```kotlin
val got = jdbc.queryForList("SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ? FOR UPDATE SKIP LOCKED", …).isNotEmpty()
if (!got && early) limitExceeded()
return got
```

- L2와 같은 의미(즉시 거절, 가짜 거절 있음)를 행 락으로. 행은 `prepare`가 커밋해 두므로 0행 = 다른 트랜잭션이 잠금.
- **원안은 `FOR UPDATE NOWAIT`였다**(명세 인터뷰). NOWAIT는 잠겨 있으면 오류(55P03)를 내고, PostgreSQL은 오류가 난 트랜잭션을 더 쓸 수 없게 만든다 — '진입 못 해도 좌석을 먼저 확인'(에러 우선순위)을 할 수 없어 오류 없이 0행을 돌려주는 SKIP LOCKED로 바꿨다(재합의 2026-10-06). 기다리지 않는다는 의미는 같고, 오류 경로(예외 생성·번역)가 없어진다. L4·L4e 모두 SKIP LOCKED라 둘의 차이는 거절 시점뿐이다.

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
| H1 | L0은 좌석 3b 아래에서도 S2 매수 초과를 낸다(ADR-002와 같은 크기). L1~L7은 전 회차 초과 0 | L0 초과 0(측정 설계 오류) / L1~L7 어느 회차든 초과 > 0 |
| H2 | 가짜 거절(매수 < 2인데 거절)은 즉시 실패형(L2·L4·L2e·L4e)과 L6에서 크고, 대기형(L1·L3)·L5·L7은 0에 가깝다 | 대기형에서 가짜 거절 > 0 / 즉시 실패형에서 0 |
| H3 | S4(사용자마다 1요청 — 같은 사용자 경합 없음)에서 처리량 한계는 대조군과 같다. L3·L4·L5는 쿼터 행 준비 왕복만큼, L6·L7은 SSI 비용만큼 약간 낮을 수 있다 | 계단 2칸 이상 차이 |
| H4 | S2에서 대기형(L1·L3·L5)은 같은 사용자 10요청이 커넥션을 쥐고 줄 서 풀 대기가 커지고, 즉시 실패형은 작다 | 대기형과 즉시 실패형의 획득 대기가 같다 |
| H5 | S7(이미 2매인 사용자 U + 일반 사용자들이 같은 좌석)에서 L0~L4·L6·L7은 U가 좌석 락을 먼저 잡았다가 매수에서 롤백할 때 일반 사용자가 409를 받고 좌석이 비는 일이 생기고, **L5는 U가 좌석 락 전에 지므로 0**. U는 같은 사용자 동시 요청이 없어 진입은 성공하므로 L2e·L4e도 L2·L4와 같다(차이는 S2의 같은 사용자 동시 요청에서) | L5에서 억울한 409 > 0 / 나머지에서 0 |
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
| ⑥′ 40001의 출처(사용자 제기 2026-10-07) | **같은 사용자 충돌**(막아야 할 진짜 위험) vs **다른 사용자 충돌**(막을 필요 없는 비용)을 가른다 — S4는 요청마다 사용자가 달라 40001이 전부 다른 사용자 충돌(설계상 분리). S2는 섞여 있어 충돌 건별로는 못 가르고(앱 카운터는 수만, PostgreSQL 오류에 상대 트랜잭션 정보 없음) 사용자 결과로 가른다 — 매수 초과 0 = 같은 사용자 충돌을 막음, 가짜 거절 > 0 = 다른 사용자 충돌이 사용자에게 닿음. 건별 분리(같은 사용자만 동시에 보내는 셀 등)는 후속 측정 |
| ⑦ 카운터 정합 | S3 끝 상태 `v_counter_mismatch`(L5만) |

## 5. 측정 계획

| 대상 | 시나리오 | 단계 | 회차 |
|------|---------|------|------|
| 매수 방식 10개 | S2(사용자 100 × 좌석 10 동시) · S4(16단계) | L2 · L4 | 5 |
| 매수 방식 10개 | S3 원본 × 이탈 0/20/50 | L4 | 3 |
| 매수 방식 10개 | S7 이긴 쪽 롤백(신규) — 일반 20명(`S7`) · 일반 1명(`S7-m1`, 재합의) | L4 | 5 |
| 매수 확인 쿼리 2개 | DB 직접 벤치(pgbench) — 배경 0 / 10만 / 100만 | L4 | 조합당 1 |

- 좌석 3b·인덱스 V2·풀 10·배경 0·타임아웃 30s 등 나머지는 ADR-003과 같다. 회차 우선 순서. 하네스 `k6/ADR-005/`.
- 약 50~55시간(추정 — ADR-003 실측 비례). DB 벤치는 캠페인 끝에 한 번(`campaign.sh`).
- 알려진 편향(측정 전): SERIALIZABLE의 40001은 좌석 충돌에서도 나서 `HOLD_LIMIT_EXCEEDED`로 섞인다(S7에서 두드러질 수 있음) · L5 카운터 감소는 만료 배치가 실제로 지운 홀드로 하지만, 만료 배치 자체의 동시성(확정과의 경합)은 ADR-006 몫이다.

## 6. 측정 전 검증

### 6.1 구현 중 발견

- **JdbcTemplate의 55P03 번역**: 쿼터 행 NOWAIT(L4)의 '잠겨 있음'(55P03)을 `PessimisticLockingFailureException`으로 잡도록 썼는데, JdbcTemplate은 이것을 `UncategorizedSQLException`으로 번역했다(ADR-003 3b는 JPA 경로라 `PessimisticLockingFailureException`이었다). catch를 지나 500이 됐고 경합 테스트가 잡았다. 같은 SQLState도 **어느 계층을 거치느냐에 따라 다른 예외 타입**이 된다 — 예외 타입이 아니라 SQLState로 가르도록 고쳤다.
- **판정기 항목 null**: `v_counter_mismatch`를 counter가 아닐 때 null로 냈더니 `v_*`를 합산하는 기존 테스트 10개가 NPE로 깨졌다 → counter일 때만 항목을 낸다.
- 기본 좌석 전략 변경(none → 3b)으로 '기본값 = none' 테스트 1개가 깨졌다 — 명세 변경이라 기대값을 바꿨다.
- **`INSERT … ON CONFLICT DO NOTHING`이 UPDATE 중인 행을 기다린다**(코드 리뷰 지적 → 특성 테스트로 확인): 이미 있는 쿼터 행이라도 다른 트랜잭션이 그 행을 UPDATE(L5의 cnt + 1)하고 커밋하지 않았으면, 충돌 검사가 그 트랜잭션의 끝을 기다린다. `FOR UPDATE`(잠금만)는 그렇지 않다. 그래서 L5에서 같은 사용자의 대기가 트랜잭션 밖 준비 단계로 새어 타이머·커넥션 해석이 틀어질 뻔했다 → 준비를 '일반 SELECT 먼저'로 바꾸고, 특성 테스트(`CounterUserLimitTest`)가 이 동작을 고정한다.
- **NOWAIT 오류는 트랜잭션을 버린다**: 에러 우선순위를 지키려면 '진입 못 함'을 기록하고 좌석을 계속 확인해야 하는데, PostgreSQL은 오류가 난 트랜잭션의 다음 문장을 거부한다 → L4를 SKIP LOCKED로(§2.6).

### 6.1.1 코드 리뷰 (中 듀얼 1패스 — Opus ∥ codex)

- 지적 15건(중복 병합) 중 반영: 에러 우선순위(즉시 실패형 — 재합의로 명세 순서 + `-early` 변형), 타이머 구간(span·prepare 추가), ON CONFLICT 대기(위), S7 억울한 409를 코드 무관으로, S3 계획 회차 3, S7 setup의 SERIALIZABLE 충돌 재시도, DB 벤치를 캠페인에 넣고 없으면 '미측정', 40001·재시도 결정적 테스트, '다른 사용자는 막지 않음'을 락을 쥔 채 확인하는 테스트, 매수 방식은 3b에서만(기동 거부), counter + 배경 행 거부, MAX 비교 제외.
- 기록만: V4(유니크 전용 위치)가 V5보다 낮은 번호라 V5까지 적용된 영속 DB에서 유니크 전략으로 바꾸면 Flyway가 거부한다 — 하네스는 회차마다 DB를 지워 측정 영향 없음. 앱 2대에서 만료 배치 두 개가 카운터 감소 UPDATE를 서로 다른 순서로 잡으면 교착할 수 있다 — 이 ADR은 앱 1대.

### 6.2 테스트 (151개 green)

- 매수 방식마다 컨텍스트 하나(`UserLimitStrategyTest` × 10): 같은 사용자 동시 10좌석(5라운드) — **L0은 5라운드 안에 초과가 났고(양성 대조 — 좌석 3b 아래에서도 매수 경합 그대로, 가정 1의 테스트 수준 실증)**, 나머지는 성공·DB 매수 모두 ≤ 2 · 다른 사용자 20명 동시 — 서로 막지 않음(L6·L7은 SSI 충돌 거절 허용) · 같은 좌석 50명 — 8개 모두 1명만 이김(가정 2) · 에러 우선순위(L5만 매수 초과) · 카운터 정합(선점·좌석 실패 롤백·확정·만료) · 격리 수준(`SHOW transaction_isolation`) · 사용자 A가 진입을 쥔 동안 다른 사용자는 끝나고 같은 사용자는 대기형이면 기다리고 즉시 실패형이면 거절(경합 중 에러 우선순위 — L2·L4는 팔린 좌석이면 좌석 불가, `-early`는 매수 초과) · SERIALIZABLE 충돌을 임계 구역 지연으로 결정적으로 만들어 L6 거절 1 + 40001 카운터, L7 재시도로 둘 다 성공 · ON CONFLICT 대기 특성.

## 7. 실험 결과

미측정.

## 8. 결정

미정.
