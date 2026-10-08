# ADR-005: 1인 최대 2매 — 같은 사용자 동시 요청
> 번호 이동(2026-10-04): 임계 구역 길이·락 보유 시간을 새 ADR-004로 넣으며 기존 ADR-004~010을 005~011로 옮겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: 측정 완료 · 결정 제안(§8, 사용자 확인 대기) — 2026-10-08
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
| ⑥′ 40001의 출처(사용자 제기 2026-10-07) | **같은 사용자 충돌**(막아야 할 진짜 위험) vs **다른 사용자 충돌**(막을 필요 없는 비용)을 가른다 — S4는 요청마다 사용자가 달라 40001이 전부 다른 사용자 충돌(설계상 분리). S2는 섞여 있어 충돌 건별로는 못 가르고(앱 카운터는 수만, PostgreSQL 오류에 상대 트랜잭션 정보 없음) 사용자 결과로 가른다 — 매수 초과 0 = 같은 사용자 충돌을 막음, 가짜 거절 > 0 = 다른 사용자 충돌이 사용자에게 닿음. 건별 분리(같은 사용자만 동시에 보내는 셀 등)와 **40001 원인별 집계**(PostgreSQL 메시지로 읽기-쓰기 의존 'read/write dependencies' — SSI 술어 락 vs 쓰기-쓰기 'concurrent update' — 같은 행 갱신, 그리고 난 문장·커밋 시점)는 후속 측정 |
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

> 경로는 repo 루트 기준. `R` = `k6/ADR-005/results/20261006-adr005-7acad14b`. 표는 `R/COMPARISON.md`·`R/comparison.json`(`scripts/compare.py`, errsplit 뒤 생성)과 조건별 `SUMMARY.md`·`summary.json`(`scripts/summarize.py`)에서 옮겼다. 값 = 중앙값 [최소–최대], n = 쓴 회차/전체.

### 7.1 측정 경과

| 항목 | 값 |
|------|----|
| 캠페인 | `20261006-adr005-7acad14b` — 2026-10-06 19:05 ~ 10-08 21:38 (약 50.5시간), SHA 7acad14b |
| 조건 × 회차 | 30조건(매수 방식 10 × {s24 · s3 · s7}), 회차 우선. 정상 회차 **390** = S2·S4 200 + S3 90 + S7·S7-m1 100 — 계획과 같다 |
| 비정상 회차 | 측정 경로 단절(`path-gap`) 4회(모두 S3 — counter a20 r1 · quota-nowait-early a0 r2 · serializable-retry a0 r2 · serializable a20 r2) → 끝에 재측정 1바퀴에서 모두 ok(원 회차는 `.path-gap-*`로 보존). 최종 비정상 0 |
| DB 벤치 | 배경 0 / 10만 / 100만 — 10-08 21:33 ~ 21:38 |
| 경로 | 유선(ADR-003과 같음 — L4 S4 약 4,300건/s 상한, 원인 미확정) |

### 7.2 결과 요약

| 가설 | 판정 | 핵심 수치 |
|------|------|---------|
| H1 L0은 S2 매수 초과를 내고 L1~L7은 0 | **채택** | L0 매수 초과 사용자 L2 15 [14–18] · L4 22 [12–25](ADR-002 15~17과 같은 크기 — 좌석 3b 아래에서도 매수 경합 그대로). 나머지 9개는 S2·S3·S7 전 회차 0. 판정기 전수: 대조군 뺀 351회 위반 0 |
| H2 가짜 거절은 즉시 실패형·L6에서 크고, 대기형·L5·L7은 0에 가깝다 | **부분 기각** | **L6 serializable만 크다 — L2 69 [66–76] · L4 69 [51–73]명(100명 중)**. L7 2~3명. 즉시 실패형 L2·L4·L2e·L4e는 0 [0–2] — 같은 사용자 10요청 중 앞 요청이 커밋한 뒤 도착한 요청이 진입해 2매를 채운 것으로 **추정**(§7.3 ②). 대기형·L5 0 |
| H3 S4 처리량은 대조군과 같다(쿼터 계열은 약간 낮을 수 있음) | **부분 기각** | advisory 계열(L1·L2·L2e)은 L2 2,866 · L4 4,300 = 대조군. **쿼터 행 계열(L3·L4·L4e·L5)은 대부분 회차 1,911(한 단계 아래 — 약 −33%)**. **SERIALIZABLE은 L6 L2 1,974 · L4 708 [662–2,021], L7 L2 1,263 · L4 1,843** |
| H4 S2에서 대기형은 줄 서 풀 대기가 커지고 즉시 실패형은 작다 | **채택(지연으로)** | S2 L2 p99: 대기형 advisory 3,788 · quota-lock 3,506ms vs 즉시 실패형 advisory-try 2,265 · quota-nowait 2,951ms. 단 **counter가 가장 빠르다(p50 258 · p99 593ms)** — 매수 초과 요청이 COUNT도 좌석 락도 없이 UPDATE 0행으로 끝난다 |
| H5 S7에서 L5만 억울한 좌석 0 | **채택(M=1에서)** | `S7-m1`: **counter 0**, 나머지 63~81석(100석 중). `-early`는 일반형과 같다(U는 같은 사용자 동시 요청이 없어 진입이 성공). `S7`(M=20)은 전 방식 0~1석 — 경쟁자가 많으면 늦게 온 사람이 가져가 피해가 흡수된다 |
| H6 매수 확인 비용은 배경 규모에 거의 무관 | **채택** | DB 벤치 1연결: 홀드 수 0.030 / 0.030 / 0.031ms, 예약 수 0.025 / 0.028 / 0.028ms(배경 0 / 10만 / 100만). 배경이 있으면 인덱스(`idx_seat_hold_user_id`·`idx_reservation_schedule_user`)를 탄다 |
| H7 S3에서 L5 카운터 = 홀드 + 확정 예약 | **채택** | `v_counter_mismatch` 이탈 0/20/50 × 3회 전부 0 |

### 7.3 판단 기준별 분석

#### ① 매수 정합성

**결론**: 제어 없음(L0)만 매수를 넘겼고, 9개 방식은 모든 시나리오·회차에서 1인 2매를 지켰다.

**근거**: S2 매수 초과 사용자(판정기 `v_over_limit_users`) — L0 L2 15 [14–18] · L4 22 [12–25], 나머지 0. 응답 쪽(201 > 2인 사용자)과 판정기가 회차마다 같다(응답≠끝 상태 사용자 0, 201 − 홀드 행 0). 판정기 전수(`R/COMPARISON.md` '판정기 전수'): 대조군(L0)을 뺀 정상 회차 351회 위반 합 0. 양성 확인 — L0 S2는 L2·L4 각 5/5회 위반.

**편향·한계**: S2는 사용자 100명 × 10좌석 한 형태다. 같은 사용자의 요청 수·도착 간격이 다르면 초과 크기는 달라지지만, 막는 방식의 정합성(0)은 구조상 같다.

#### ② 가짜 거절 — 매수가 남았는데 거절

**결론**: 가짜 거절은 **SERIALIZABLE(L6)에서만 크다** — 사용자 100명 중 약 69명이 2매를 못 채웠다. 재시도(L7)는 2~3명으로 줄였다. 즉시 실패형(advisory-try·quota-nowait와 -early)은 예상과 달리 0~2명이다.

**근거**(S2, 가짜 거절 사용자 = 끝 상태 매수 < 2인데 `HOLD_LIMIT_EXCEEDED`를 받은 사용자):

| 방식 | L2 | L4 | 201 수(상한 200) |
|------|----|----|----------------|
| serializable | **69 [66–76]** | **69 [51–73]** | 102 / 99 |
| serializable-retry | 2 [0–3] | 3 [0–6] | 198 / 196 |
| advisory-try · -early | 0 · 0 | 0 [0–2] · 0 [0–1] | 200 |
| quota-nowait · -early | 0 · 0 | 0 · 0 | 200 |
| advisory · quota-lock · counter | 0 | 0 | 200 |

- L6의 거절 대부분은 같은 사용자 충돌이 아니라 **다른 사용자와의 충돌**이다(§7.3 ⑥′). 40001이 S2 L2 802 · L4 799회.
- 즉시 실패형이 0에 가까운 이유(추정): 같은 사용자의 10요청은 k6에서 동시에 출발하지만 서버 진입 시각이 퍼져, 앞 요청이 커밋한 뒤 도착한 요청은 진입에 성공해 매수를 세고 통과한다. 10요청 중 2개만 통과하면 되므로 가짜 거절로 남기 어렵다. 요청 수가 2개(정확히 상한)면 결과가 다를 수 있다(미측정).

#### ③ 처리량 회귀 — 다른 사용자까지 느려지나

**결론**: advisory 계열은 처리량을 잃지 않는다. **쿼터 행 계열(quota-lock·quota-nowait·-early·counter)은 S4 엄격 한계가 한 단계 낮다(1,911 — 약 −33%)**. 원인은 사용자 판정 자체가 아니라 **요청마다 트랜잭션 밖에서 쿼터 행을 확인하는 준비 단계(prepare)** 다. SERIALIZABLE은 크게 낮다(L6 L4 708).

**근거**(S4 엄격 한계 건/s — 요청마다 다른 사용자라 같은 사용자 경합은 없다):

| 방식 | L2 | L4 | prepare 평균 ms | span 평균 ms | 40001 |
|------|----|----|----------------|-------------|-------|
| none | 2,866 [2,866–2,866] | 4,300 [4,300–4,300] | 0.000 | 0.63 / 0.36 | 0 |
| advisory · advisory-try · -early | 2,866 | 4,300(advisory 1회 1,274) | 0.000 | 0.30~0.69 | 0 |
| quota-lock · quota-nowait · -early | **1,911**(5회 중 2~3회 1,911) | **1,911** | **약 70** | 0.27~0.31 | 0 |
| counter | 2,838 [1,911–2,866] | **1,911** | **약 67~70** | 0.08 | 0 |
| serializable | **1,974** | **708 [662–2,021]** | 0.000 | 1.39 / 0.50 | 176,715 / 615,532 |
| serializable-retry | **1,263** | **1,843** | 0.000 | 1.76 / 0.39 | 232,024 / 1,235,828(재시도 196,943 / 983,330) |

- 쿼터 계열의 판정 구간(span)은 오히려 짧다(0.08~0.31ms). 늘어난 것은 prepare다 — 매 요청 자동 커밋 SELECT를 위해 **커넥션을 한 번 더 빌린다**. 풀 10에서 커넥션 획득이 요청당 두 번이 되어 부하 구간 평균 약 70ms가 됐다(획득 대기). 앱 CPU 최대도 낮다(약 150% vs 대조군 약 207%) — CPU가 아니라 커넥션 앞에서 막혔다.
- SERIALIZABLE은 요청마다 사용자가 달라도 40001이 수십만 건이다(S4 L4 615,532). 거절도 409라 성공 처리량이 무너졌다.

**편향·한계**: L4 S4 상한 4,300은 측정 경로 상한(ADR-003 §6.3) — 그 위의 차이는 판정하지 않는다. 쿼터 행을 미리 만들어 두면(예: 회원 가입·첫 회차 진입 시) prepare가 사라져 회귀가 없어질 것으로 **추정**한다(미측정 — §9).

#### ④ 이긴 쪽 롤백 — 좌석이 비었는데 진 사람

**결론**: 경쟁자가 1명일 때(`S7-m1`) 2매 보유자 U가 좌석 락을 잡았다가 매수에서 롤백하면 일반 사용자가 409를 받고 좌석이 빈다 — **counter만 0석**이다. counter는 매수 판정이 좌석보다 먼저라 U가 좌석 락을 잡지 않는다. 경쟁자가 20명이면(`S7`) 전 방식이 0~1석 — 늦게 온 사람이 가져간다.

**근거**(억울한 좌석 수 / 100석):

| 방식 | S7(M=20) | S7-m1(M=1) | S7-m1 U의 응답 |
|------|---------|-----------|--------------|
| none · advisory · advisory-try · -early | 0~1 | 67~68 | HLE 70~74 · SNA 26~30 |
| quota-lock · quota-nowait · -early | 0 | **79~81** | HLE 100 |
| serializable · -retry | 1 | 63~65 | HLE 66~70 |
| **counter** | **0** | **0** | HLE 100(좌석 락 전) |

- `-early` 변형은 일반형과 같다 — U는 같은 사용자 동시 요청이 없어 진입이 성공하고, 좌석 락을 잡은 뒤 매수에서 진다. '거절될 요청이 좌석 락을 잡지 않는다'는 -early의 이점은 **같은 사용자 동시 요청**에만 해당한다.
- 쿼터 계열이 79~81로 더 큰 것은 편향이다: 일반 사용자는 첫 요청이라 prepare에서 쿼터 행 INSERT를 하고 들어와 U보다 늦어진다 → U가 100/100석에서 좌석 락을 먼저 잡았다(U의 응답이 모두 HLE).

**편향·한계**: U와 일반 사용자의 시차(LEAD 1ms)·k6 발송 흩어짐(−1~3ms)에 결과가 민감하다. 비율 자체보다 '0이냐 아니냐'(counter vs 나머지)가 판정 근거다.

#### ⑤ 매수 확인 지연

**결론**: 매수 확인 쿼리는 배경 100만 행에서도 약 0.03ms다(H6 채택 — 사용자 예상 "인덱스라 큰 차이 없을 것"과 같다). 앱 안에서도 경합 없는 S4의 판정 구간(span)은 0.1~0.7ms다. S2 L2에서 check가 15~23ms로 큰 것은 쿼리 비용이 아니라 CPU 2개에서 1,000요청이 몰린 대기다.

**근거**: DB 벤치(`R/limit-bench/`, pgbench `-M prepared` 1연결) — 위 §7.2 H6. 실행 계획: 배경 0은 행이 적어 Seq Scan, 배경 10만·100만은 Index Scan(`idx_seat_hold_user_id`·`idx_reservation_schedule_user`). 앱 타이머는 §7.3 ③ 표.

**편향·한계**: pgbench는 `status`를 문자열 상수로 넣는다(앱은 바인딩). 앱 타이머 MAX는 최근 2분 창이라 쓰지 않았다.

#### ⑥ 실패 모드 · ⑥′ 40001의 출처

**결론**: 10개 방식 모두 5xx 0이다. 실패는 S4 과부하 단계의 타임아웃·연결 실패뿐(ADR-003과 같은 모양). SERIALIZABLE의 40001은 **S4에서 수십만 건 — 같은 사용자 경합이 없는 시나리오라 전부 다른 사용자 충돌**이다.

**근거**: 응답 분류 합계(`R/errsplit.json`) — 2xx 51,394,145 · 409 72,864,682 · 타임아웃(0-1050) 626,059 · 연결 실패(0-1211) 166,603 · 연결 끊김(0-1220) 4,741 · 기타 2 · **5xx 0**. 40001(앱 카운터 차분): S4 L6 176,715 / 615,532 · L7 232,024 / 1,235,828, S2 L6 약 800 · L7 약 1,500~1,700.

- ⑥′: S4의 40001은 설계상 전부 다른 사용자 충돌이다. 같은 행을 둘이 고치는 일이 없으므로(사용자·좌석 모두 다름) 원인은 읽기-쓰기 의존(SSI 술어 락 — 매수 SELECT가 읽은 범위에 다른 트랜잭션의 INSERT)으로 **추정**한다 — 메시지별 집계는 미측정(§9).

#### ⑦ 카운터 정합

**결론·근거**: S3(선점 → 확정·이탈 → 만료)에서 counter의 `v_counter_mismatch`는 이탈 0/20/50 × 3회 전부 0이다 — 만료 배치가 실제로 지운 홀드만큼 내리는 구현이 맞다. S3 확정 수는 방식 간 같다(a0 10,000 · a20 약 9,720~9,754 · a50 약 8,020~8,120), 단 **serializable a50 7,803 · retry 7,953**으로 약간 낮다(거절된 선점이 확정 기회를 줄임).

### 7.4 공통 편향

- **측정 경로 상한**: L4 S4 약 4,300건/s(ADR-003 §6.3).
- **쿼터 계열의 prepare**는 '매 요청 트랜잭션 밖 확인'이라는 이번 구현의 비용이다 — 방식의 본질 비용과 섞여 있다(§7.3 ③).
- **S7의 시차 민감도**(§7.3 ④).
- 앱 1대·풀 10·좌석 3b 고정. 매수 방식과 좌석 방식의 다른 조합은 재지 않았다.

## 8. 결정

**제안(2026-10-08, 사용자 확인 대기)** — 매수 제어 기본 방식을 **L5 `counter`(카운터 조건부 UPDATE)** 로 하고, **쿼터 행을 미리 만드는 배치로 바꿔 처리량 회귀를 없앤 뒤 확정**한다. 그 전까지의 차선은 **L2 `advisory-try`** 다.

| 기준 | counter | advisory-try | advisory(대기) | serializable(-retry) |
|------|---------|-------------|---------------|-------------------|
| ① 매수 정합성 | 0 | 0 | 0 | 0 |
| ② 가짜 거절(S2, 100명 중) | 0 | 0~2 | 0 | **69** (retry 2~3) |
| ③ S4 한계 L2 / L4 | **1,911 계열**(prepare 때문) | 2,866 / 4,300 | 2,866 / 4,300 | **1,974 / 708** (retry 1,263 / 1,843) |
| ④ S7-m1 억울한 좌석 | **0** | 67 | 67 | 63~65 |
| S2 L2 p50 / p99 ms | **258 / 593** | 980 / 2,265 | 2,167 / 3,788 | 2,700 / 4,742 |
| 추가 스키마·경로 | 쿼터 테이블 + 만료 배치 감소 | 없음 | 없음 | 없음 |
| 에러 우선순위 | 매수 먼저(허용) | 보존 | 보존 | 커밋 시 충돌 |

- **왜 counter인가**: 정합성·가짜 거절은 advisory 계열과 같고, **매수 초과 사용자가 좌석 락을 아예 잡지 않는 유일한 방식**이라 ADR-003 3b의 대가 ①(이긴 쪽 롤백으로 좌석이 비는데 다른 사람이 짐)을 없앤다 — S7-m1 0석 vs 63~81석. COUNT가 없어 같은 사용자 동시 요청의 지연도 가장 짧다. ADR-003의 원칙("기다리는 동안 다른 좌석을 놓치는 사람이 없어야 한다")과 같은 방향이다.
- **counter의 대가**: ① 이번 구현의 S4 처리량 −33% — 요청마다 트랜잭션 밖에서 쿼터 행을 확인하느라 커넥션을 두 번 빌린 탓(판정 자체는 0.08ms로 가장 짧다). 행을 미리 만들면 사라질 것으로 추정 — 확정 전 재측정. ② 카운터를 내리는 주체(만료 배치)가 생긴다 — 만료가 늦거나 실패하면 카운터가 실제보다 커서 사용자가 덜 잡는다(정합성 쪽으로는 안전, 가용성 쪽 손해). ADR-006(만료)이 이 경로를 잰다. ③ 쿼터 테이블 1개. ④ 매수 초과 사용자가 팔린 좌석을 누르면 '좌석 불가'가 아니라 '매수 초과'로 답한다.
- **차선 advisory-try**: 스키마·처리량 비용이 없고 가짜 거절도 사실상 0이다. 대신 S7-m1의 롤백 피해는 제어 없음과 같다(67석).
- **제외**: serializable(가짜 거절 69명, S4 L4 708 — 다른 사용자끼리 충돌), serializable-retry(처리량 회귀), quota-lock·quota-nowait(-early)(counter보다 나은 점 없이 같은 prepare 비용, S7-m1 79~81), advisory 대기형(같은 사용자 요청이 커넥션을 쥔 채 줄 섬 — S2 p99 3,788), -early 변형(일반형과 차이 없음 — 같은 사용자 동시 요청 외에는 같다).

## 9. 다음 ADR로 넘기는 것

| 받는 곳 | 내용 |
|--------|------|
| **ADR-005 후속(확정 전)** | counter의 쿼터 행을 미리 만드는 배치(가입·회차 첫 진입·시드)로 prepare를 없애고 S4·S2·S7-m1 재측정 — 처리량 회귀가 사라지는지 |
| ADR-006 홀드 만료 | counter는 만료 배치가 카운터를 내린다 — 만료 지연·실패 시 카운터가 커져 사용자가 덜 잡는 경로, 앱 2대에서 만료 배치 두 개의 감소 순서 교착(OQ3), 3b NOWAIT와 만료 배치의 행 락 충돌(ADR-003 §8 ③) · try-advisory 비교(NEXT N3) |
| ADR-007 확정 원자성 | 확정은 홀드 → 예약이라 카운터 변화 없음(이번 구현) — 확정 실패·보상 경로가 생기면 카운터 갱신 필요 |
| 후속 측정 | 40001 원인별 집계(읽기-쓰기 의존 vs 같은 행 갱신)·같은 사용자만 동시에 보내는 셀(§4 ⑥′) · 즉시 실패형의 가짜 거절이 요청 수 = 상한(2)일 때 · S7 시차 변화 |
| 하네스 | 배포에서 측정 결과 제외(서버 디스크 100% 사고 — §6.1.1 뒤 로그) · prepare 같은 트랜잭션 밖 단계가 커넥션을 한 번 더 빌리는 비용을 하네스가 따로 보여 주게 됨(prepare 타이머) |

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
| `k6/ADR-005/` | 하네스(README — 지표 정의·S7 설계) · `scenarios/s2-same-user.js` · `s7-winner-rollback.js` · `scripts/run.sh`·`lib.sh`·`campaign.sh`·`summarize.py`·`compare.py`·`errsplit.py`·`limit-bench.sh` |
| `R/COMPARISON.md` · `comparison.json` · `errsplit.json` · `CAMPAIGN.log` · `limit-bench/` | 교차표(판정기 전수·응답 분류 포함)·응답 분류 원자료·캠페인 기록·DB 벤치 — §7 전부 |
| `R/<조건>/SUMMARY.md` · `summary.json` · `L*/<셀>/rep*/` | 조건별 요약·회차 원시(end-state.json·after-k6.json 등) |
| `docs/plans/2026-10-06/adr-005-user-limit/requirement-spec.md` · `log.md` | 합의 명세(재합의 3건) · 작업 로그·리뷰 ledger |
