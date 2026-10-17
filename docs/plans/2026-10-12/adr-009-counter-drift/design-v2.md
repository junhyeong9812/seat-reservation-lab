# ADR-009 설계 선검증 v2 (task 01) — 카운터 어긋남 탐지·격리·복구 · 판매 마감 · 쿼터 행 정리 · 기동 검사

- 판정 기준: `docs/plans/2026-10-12/adr-009-counter-drift/requirement-spec.md`. **§10 재합의가 본문보다 우선한다.** 배경은 ADR-008 §3·§6.
- repo `seat-reservation-lab`, 브랜치 `feat/adr-009-counter-drift`, HEAD `8f4740ae`. v1 시작 시점 `4b137670`과 비교해 src·k6 변경은 0이다.
- 이전 판: `design-v1.md`, `reconcile-v1.sql`, `V8__counter_trigger-v1.sql`.
- v2 실험: 스크래치 컨테이너 `adr009-design-pg2`(postgres:16, shared_buffers 128MB = 하네스 기본값). 실험이 끝난 뒤 지웠다.
  - 스크립트: `exp2.py`, `e1_pass.py`, `e1_pass_v2.py`, `pgb.sh` + `pgbench/`, `o1.py`
  - 출력: `exp2-out.txt`, `e1-explain.txt`, `e1-explain-v2.txt`, `e1-pass-out.txt`, `e1-pass-v2-out.txt`, `pgb-out.txt`, `o1-out.txt`
  - v1 실험 출력(`exp-out-*.txt`, `exp-b-out.txt`)은 그대로 근거로 쓴다.

## v2 변경 요약

| 지적·결정 | 반영 | 절 |
|---|---|---|
| §10 마감 회차의 카운터 · codex C1 | 대조 C·F와 판정기 `v_counter_mismatch`에서 마감 회차(`starts_at <= now`)를 뺀다. 제외한 회차·쌍 수를 기록한다. 남은 홀드의 감소 실패는 A가 맡는다 | §4·§5a |
| C2 | 정리 ①을 회차 행 잠금으로 직렬화한다. 선점의 마감 판정은 `FOR SHARE`, 정리는 `FOR UPDATE`로 잡은 뒤 홀드 0을 다시 확인한다. 실험 C2a~d | §5·§6 |
| C3 | 정리 ①이 확정 쌍까지 지운 뒤 판정기 v2 값이 0인지 확인했다. 실험 C3 | §6 |
| E1 | 실제 청크 문장을 S4c4200 끝 상태 축척(162.3만 쌍)에서 다시 쟀다. **v1 청크는 한 바퀴 155초, 청크 최대 1.0초**였다. v2(쿼터 주도 + 행 없음 분리)는 11.2초 + 3.8초이고, 주입한 7쌍을 모두 잡았다 | §4 |
| E2 · M1 | B 선잠금은 '교착만' 해결한다고 명시했다. Hibernate 행 수 기대값까지 흉내 낸 6가지 경우를 비교하고, B가 지는 쪽을 바꾸는지 판단했다 | §3 |
| B1 | 트리거에서 기여(CONFIRMED 여부·회차·사용자)가 같은 UPDATE는 바로 반환한다. cnt 0 주입 상태에서 v1은 23514, v2는 ok | §3 |
| O1 | 복구가 실패하면 롤백한 뒤 독립 트랜잭션으로 `skipped`를 기록하고, A·C·F 각각 재시도 경계를 둔다. 실험 O1 | §1 |
| §10 D2·D6·D4·D8·D1 | 마감 끔 키 · 마감 임박 셀 · 기동 검사 범위(quota 계열 포함) · C 풀 공유와 빌림 차감 · 오탐 테스트 이음새 | §5·§9·§7·§4·§4 |
| §10 측정 시간 | 84회, 약 18.7시간 | §10 |

---

## 0. 코드에서 확인한 전제

v1과 같다(`design-v1.md` §0, F1~F15). v2에서 더한 사실은 아래와 같다.

| # | 사실 | 근거 |
|---|---|---|
| F16 | `seat_hold.schedule_id`와 `reservation.schedule_id`는 `product_schedule(id)`를 참조하는 FK다. 그래서 홀드·예약 INSERT는 회차 행에 **FOR KEY SHARE**를 이미 잡는다(PG RI 검사). 정리 ①의 `FOR UPDATE`는 이 잠금과 충돌한다 | `V1__init.sql:24-41` |
| F17 | 확정이 좌석을 찾는 `findByHoldsId`와 만료가 좌석을 찾는 `findDistinctByHoldsExpiresAtLessThanEqual`은 둘 다 잠금 없는 조회다. 좌석 엔티티에는 version이 매핑되어 있지 않다 | `ProductSeatRepository.kt:16,18` · `V3__add_seat_version.sql:1-2` |
| F18 | 확정의 예약 저장은 IDENTITY라 `save()` 때 바로 INSERT된다. 좌석 UPDATE와 홀드 DELETE(orphanRemoval)는 커밋 때 flush된다 | `Reservation.kt:23-24` · `ProductSeat.kt:40-42` · `ConfirmReservationService.kt:36` |
| F19 | S4·S4c의 좌석은 반복 번호 순이다(좌석 = 반복 + 1, 회차 = ⌈좌석/10,000⌉). 그래서 **어느 순간이든 요청이 거의 한 회차에 몰린다**. 4,200건/s면 회차 하나를 약 2.4초 동안 채운다. S3는 회차가 하나다 | `s4-throughput.js:60-61` · `s3-full-flow.js:52` |
| F20 | S4c4200 끝 상태는 홀드 116만~163만 행이다(ADR-007 실측: 디스크 rep1 1,160,633 · rep2 1,152,504, tmpfs rep1 1,625,380 · rep2 1,620,449). 사용자 1명이 홀드 1개를 가지므로 쿼터 행 수도 같다 | `k6/ADR-007/results/20261010-dc-b943a3f0/counter-upsert-precheck-*-s4c4200/L4/S4c4200/rep*/consistency.json` |

---

## 1. 쌍 단위 복구 트랜잭션

v1 §1의 설계와 증명은 그대로다. 근거는 E4·E5와 반례 E6이다. 문장 순서는 아래와 같다.

1. (행이 없을 때만) `INSERT … cnt 0 ON CONFLICT DO NOTHING`
2. `FOR UPDATE`
3. 새 문장 하나로 실제 값을 센다.
4. 다르면 UPDATE하고, 같은 트랜잭션에서 기록한다.

v2에서는 아래 두 가지를 더한다.

### 1a. 실패 경계 (O1)

| 경로 | 시간 제한 | 실패 시 | 기록 | 재시도 |
|---|---|---|---|---|
| C·F(쌍마다 트랜잭션) | `SET LOCAL lock_timeout = reconcile.lock-timeout`(기본 1s) | 롤백(복구 쓰기와 같은 트랜잭션의 기록이 함께 사라진다) | **새 트랜잭션**에서 `counter_drift_log(action='skipped', reason=SQLSTATE)` 1행 + 지표 `action=skipped` | 다음 바퀴가 같은 쌍을 다시 탐지한다(어긋남이 남아 있으므로). 이력은 skipped 행이 쌓인 것으로 본다 |
| C·F 기록 자체 실패 | — | ERROR 로그 + 지표 `seat.hold.counter.drift.record_failed` | 무음 금지 — 로그와 지표 두 곳 | 같음 |
| C·F 청크 문장 실패 | 대조 문장은 `statement_timeout`을 두지 않는다(측정 대상) | 그 회차의 남은 청크를 건너뛴다 | 바퀴 기록 `errors`·`skipped_chunks` + 로그 | 다음 바퀴 |
| A(만료 트랜잭션 안) | **두지 않는다.** 만료 트랜잭션 전체에 걸리는 제한이라, 경합 때문에 틱을 실패시키지 않는다 | 예외(40P01 등)가 나면 만료 틱 전체가 롤백된다. 지금과 같은 결과이고 복구도 함께 사라진다 | `HoldExpiryScheduler`가 예외를 받아 `seat.hold.expiry.tick{outcome=failed}`를 올리고, **새 트랜잭션**에서 그 틱에 A가 시도한 쌍마다 `skipped(reason=SQLSTATE)`를 쓴다. 쌍 목록은 A 컴포넌트가 틱마다 모아 예외에 실어 보낸다 | 다음 틱이 같은 만료 홀드를 다시 고른다 |

- 실험 O1(진행 중 선점이 그 행을 쥔 상태에서 복구):

  ```
  [O1 lock_timeout → rollback → independent skipped record] repair=55P03 after 304ms · session_usable_after_rollback=True · log=[('reconcile-app', 'above', 'skipped', '55P03')] · quota_unchanged=[(5,)]
  ```

- A에 savepoint를 두어 실패한 쌍만 건너뛰는 안은 기각한다. 그 쌍의 홀드는 flush에서 지워지는데 cnt를 고치지 못하므로, 어긋남의 모양을 조용히 바꾼다.

### 1b. 오탐 테스트 이음새 (§10 D1)

- `fun interface CounterRepairProbe { fun afterDetect(scheduleId: Long, userId: Long, observedCnt: Int?, observedActual: Int) }`
- `CounterRepair`가 `ObjectProvider<CounterRepairProbe>`로 받는다. 탐지 뒤·복구 트랜잭션 시작 전에 부른다.
- 운영 설정에는 빈이 없으므로 아무것도 하지 않는다.
- 테스트는 `@TestConfiguration`으로 프로브를 등록한다. 프로브는 다른 커넥션에서 그 쌍을 고친다. 그러면 잠근 뒤 다시 센 값이 맞아 `false-positive` 1행, `repaired` 0행이 남는다.
- 자연 경로에서는 오탐이 생기지 않는다(v1 §4).

---

## 2. A — 만료 배치 쌍 단위 견딤

v1 §2와 같다: 정렬 → 가드 감소 `cnt >= n` → 0행이면 같은 트랜잭션에서 복구(이번에 지울 홀드는 빼고 센다). 실패 경계는 §1a를 따른다.

- §10에 따라 **마감 회차에서도 A는 그대로 돈다**. 남은 홀드의 감소 실패는 A가 맡는다.
- 정리 ①은 홀드가 0인 회차만 지운다. 그래서 A가 감소할 행을 정리가 먼저 지우는 일은 없다(§6 C2a).

---

## 3. B — DB 트리거

v1 §3에 §10 D3을 적용했다.

- 한도 판정은 앱에서 한다.
- 트리거는 엄격하다(SR001·CHECK).
- B 방식에서만 만료가 쿼터 행을 먼저 잠근다.

### 3a. 기여 불변 UPDATE 건너뛰기 (B1)

- 기여 = (CONFIRMED 여부, 회차, 사용자).
- 예약 UPDATE가 둘 다 CONFIRMED가 아니거나, 둘 다 CONFIRMED이고 키가 같으면 `RETURN NULL`로 바로 반환한다.
- 홀드는 키가 같으면 바로 반환한다.
- 초안: `V8__counter_trigger.sql`(v2). 늘리는 쪽(+1)을 먼저 적용하므로 행 없음 오류는 −1 쪽에서만 난다.
- 실험(주입: 확정 1인데 cnt 0):

  ```
  [B1 no-op updates on seat_b1] confirmed_to_confirmed=23514 · canceled_move=ok · hold_same_keys=ok · quota=[(1, 7, 0)]
  [B1 no-op updates on seat_b] confirmed_to_confirmed=ok · canceled_move=ok · hold_same_keys=ok · quota=[(1, 7, 0)]
  ```

  v1(`seat_b1`)은 `status = 'CONFIRMED'` → `'CONFIRMED'` 갱신이 −1·+1을 써서 CHECK 23514로 실패했다. v2(`seat_b`)는 쓰지 않는다. cnt 0이 그대로 남는 것은 의도다(주입한 어긋남은 대조가 고친다).

### 3b. 확정↔만료 경합 — 선점잠금은 '교착만' 해결한다 (E2 · M1 · §10 범위 밖)

- 위험(§10, ADR-013로 넘김): 확정과 만료가 같은 홀드를 다룰 때 좌석 행을 잠그지 않고, version도 없다(F17). 그래서 서로의 커밋을 덮어쓸 수 있다. v1 실험 B2의 끝 상태 'CONFIRMED + AVAILABLE'이 이것이다. 이 위험은 **B 이전부터 있었다**.
- 실험 M1: v1 B2는 Hibernate가 DELETE의 영향 행 수를 확인하는 것(EntityDeleteAction의 행 수 기대값 1 → 다르면 StaleStateException → 롤백)을 빼고 흉내 냈다. M1은 이것을 넣어 6가지 경우를 비교했다(`exp2.py m1_confirm_vs_expire`).
  - 확정 C: 예약 INSERT → [flush] 좌석 UPDATE → 홀드 DELETE(1행 기대)
  - 만료 E: [counter-upsert는 감소, B는 선점잠금 또는 없음] → [flush] 좌석 UPDATE → 홀드 DELETE(1행 기대)
  - 순서 두 가지: C-first(C가 예약 INSERT를 마친 뒤 E 시작), E-first(E가 flush를 마친 뒤 C)

```
[M1 seat(counter-upsert) C-first] expire={'r': 'commit'} · expire_blocked=False · confirm={'r': 'ROLLBACK RuntimeError:StaleState(delete 0)'} · confirm_blocked=False · end=seat=AVAILABLE confirmed=0 cnt=0 actual=0 · invariant_ok=True
[M1 seat_b(B no-prelock) C-first] expire={'r': 'ROLLBACK DeadlockDetected:40P01'} · expire_blocked=True · confirm={'r': 'commit'} · confirm_blocked=False · end=seat=RESERVED confirmed=1 cnt=1 actual=1 · invariant_ok=True
[M1 seat_b(B prelock) C-first] expire={'r': 'ROLLBACK RuntimeError:StaleState(delete 0)'} · expire_blocked=True · confirm={'r': 'commit'} · confirm_blocked=False · end=seat=RESERVED confirmed=1 cnt=1 actual=1 · invariant_ok=True
[M1 seat(counter-upsert) E-first] expire={'r': 'commit'} · expire_blocked=False · confirm={'r': 'ROLLBACK RuntimeError:StaleState(delete 0)'} · confirm_blocked=True · end=seat=AVAILABLE confirmed=0 cnt=0 actual=0 · invariant_ok=True
[M1 seat_b(B no-prelock) E-first] expire={'r': 'commit'} · expire_blocked=False · confirm={'r': 'ROLLBACK RuntimeError:StaleState(delete 0)'} · confirm_blocked=True · end=seat=AVAILABLE confirmed=0 cnt=0 actual=0 · invariant_ok=True
[M1 seat_b(B prelock) E-first] expire={'r': 'commit'} · expire_blocked=False · confirm={'r': 'ROLLBACK RuntimeError:StaleState(delete 0)'} · confirm_blocked=True · end=seat=AVAILABLE confirmed=0 cnt=0 actual=0 · invariant_ok=True
```

판단:

1. 같은 홀드를 두 트랜잭션이 지우면 늦은 쪽의 DELETE가 0행이 되고 그 트랜잭션이 롤백된다. 이것이 있으면 여섯 경우 모두 불변식(좌석 RESERVED ⇔ CONFIRMED, cnt = 실제)이 지켜졌다.
   - 'CONFIRMED + AVAILABLE'은 이 확인이 없을 때(v1 B2) 생긴다.
   - 그래서 지금 정합을 지키는 것은 좌석 잠금이 아니라 **Hibernate의 DELETE 행 수 확인 하나**다.
   - 실제 Hibernate에서 orphanRemoval DELETE가 행 수 기대값으로 실행되는지는 이 실험이 증명하지 않는다(SQL 흉내). 단방향 `@OneToMany @JoinColumn(nullable=false)`의 제거 순서와 null-out UPDATE 유무도 마찬가지다. `[구현 검증]` → ADR-013.
2. **B 선잠금은 교착(40P01, deadlock_timeout 1s 대기 뒤 만료 쪽 중단)만 없앤다.** 정합 문제는 해결하지도 만들지도 않는다.
3. **B를 켜면 C-first에서 지는 쪽이 바뀐다.**
   - counter-upsert: 확정이 진다(StaleState → 500, 좌석 AVAILABLE).
   - B: 확정이 예약 INSERT 때부터 쿼터 행을 쥔다. 선잠금 중인 만료가 그 커밋까지 기다렸다가 진다. 결과는 **만료 틱 전체 롤백**이다. 그 틱에서 다른 쌍의 만료까지 한 주기(10s) 늦어진다. 이 사용자의 확정은 성공한다.
   - E-first는 세 경우가 같다(확정이 진다).
   - 빈도: 경합의 조건(만료 경계 몇 ms 안의 확정)은 B와 무관하다. 그래서 빈도는 같다고 본다.
   - 결과의 비용: B에서는 '한 사용자 500' 대신 '전역 1틱 지연'이 된다. 만료 쪽에는 나빠지고 그 사용자에게는 나아진다.
   - 측정: B 회차의 `seat.hold.expiry.tick{outcome=failed}`와 확정 500 수를 대조군과 비교한다(S3-a20).
4. B 선잠금이 없으면 C-first가 교착이다. 만료 쪽이 중단되면 틱이 롤백된다(결과는 3과 같고 1s가 더 늦다). 선잠금을 두는 결정(§10)은 그대로다.

### 3c. 그 밖

- v1 §3과 같다: 판정 위치 비교, B4 비용 근사(WAL +17%), `COUNTER_STRATEGIES`에 B를 더함, B 테스트 정리는 쿼터 테이블 먼저 TRUNCATE.

---

## 4. C·F — 청크 대조 (E1 재측정 · §10 C1·D8·D1)

### 4a. v1 청크의 실패 (E1)

- 데이터: S4c4200 tmpfs 끝 상태 축척(F20)
  - 회차 200, 좌석 200만
  - 홀드·쿼터 각 1,623,000행(사용자 = 400000 + i, 회차 = i/10000 + 1, 회차 1~162 각 10,000쌍, 163은 3,000쌍)
  - DB 713MB, `VACUUM ANALYZE` 뒤
- v1 청크 문장(`reconcile-v1.sql` — 경계 계산 포함) 단건 EXPLAIN(회차 100 가운데 청크, `e1-explain.txt`)은 `Execution Time: 6.896 ms`, `Buffers: shared hit=36`이었다. 문제가 없어 보였다.
- 그런데 **한 바퀴**는 아래와 같았다.

  ```
  [E1 full pass k=1000] chunks=1660 pairs_scanned=1623000 mismatches=0 total_s=155.04 avg_chunk_ms=93.40 max_chunk_ms=1005.7
  [E1 full pass k=1000] chunks=1660 pairs_scanned=1623000 mismatches=0 total_s=161.02 avg_chunk_ms=97.00 max_chunk_ms=920.7
  [E1 full pass k=5000] chunks=362 pairs_scanned=1623000 mismatches=0 total_s=115.92 avg_chunk_ms=320.22 max_chunk_ms=703.6
  ```

- 원인: 회차의 첫 청크(lo = −∞)와 끝 청크(hi = +∞)는 seat_hold 쪽 `idx_seat_hold_user_id` 범위가 **다른 회차의 사용자 전부**를 덮는다. 그래서 회차마다 최대 160만 행을 훑고 `schedule_id`로 거른다.
  - v1 설계의 '인덱스 범위로 충분' 판단은 틀렸다(단건 가운데 청크만 쟀다).

### 4b. v2 대조 = 두 갈래 (`reconcile.sql` v2, `chunk-v2.sql`, `orphan-v2.sql`)

1. **쿼터 행 주도 청크**
   - 회차 s의 쿼터 행을 PK keyset으로 k개 읽는다(`user_id > after ORDER BY user_id LIMIT k`).
   - 쌍마다 실제 값을 인덱스 탐색으로 센다: `idx_seat_hold_user_id` = 사용자 탐색 + `schedule_id` 필터, `idx_reservation_schedule_user`.
   - 범위 스캔이 없다. 한 문장이고, 어긋남이 0이어도 1행을 돌려준다.
2. **행 없음 쌍**
   - 홀드·CONFIRMED 예약을 PK(id) 범위 청크로 읽고, 쿼터 PK로 `NOT EXISTS`를 확인한다. 열린 회차만 본다.

```
-- 단건 EXPLAIN (e1-explain-v2.txt): 쿼터 청크(회차 100, after 1395000, k 1000) / 행 없음 청크(id 800000~810000)
 Index Scan using user_hold_quota_pkey … rows=1000 · Index Scan using idx_seat_hold_user_id on seat_hold h_1 … loops=1000 · Buffers: shared hit=4003 read=13 · Execution Time: 11.645 ms
 Bitmap Index Scan on seat_hold_pkey … rows=10000 · Index Only Scan using user_hold_quota_pkey … loops=10000 · Buffers: shared hit=30061 read=73 · Execution Time: 29.715 ms
-- 한 바퀴 (e1-pass-v2-out.txt)
[E1v2 quota-driven pass k=1000] chunks=1823 pairs=1623000 total_s=11.15 avg_chunk_ms=6.12 max_chunk_ms=14.0 found=[]
[E1v2 orphan pass m=10000] chunks=163 total_s=3.80 avg_chunk_ms=23.30 max_chunk_ms=29.2 found=[]
-- 어긋남 주입 7쌍(회차 최소·최대 사용자 포함): 행 삭제 (1,400000)·(100,1399999)·(150,1895000) · cnt −1 (5,445000)·(162,2015000) · cnt +1 (7,465000)·(163,2021999)
[E1v2 quota-driven pass k=1000] chunks=1820 pairs=1622997 total_s=11.38 avg_chunk_ms=6.25 max_chunk_ms=12.4 found=[(5, 445000, 0, 1), (7, 465000, 2, 1), (162, 2015000, 0, 1), (163, 2021999, 2, 1)]
[E1v2 orphan pass m=10000] chunks=163 total_s=3.77 avg_chunk_ms=23.15 max_chunk_ms=33.0 found=[('hold', 1, 400000, 1), ('hold', 100, 1399999, 1), ('hold', 150, 1895000, 1)]
```

- 부하가 없을 때 S4c 끝 상태 한 바퀴 ≈ 15초(DB 시간), 청크 최대 33ms.
  - 대조 주기 기본값은 **60s**(바퀴 15초 + 부하 중 늘어날 여유)로 둔다.
  - S3(회차 1, 쌍 1~2만)은 한 바퀴 < 0.2초로 추정한다.
  - 부하 중 값은 측정한다(`[구현 검증]`).
- 행 없음 갈래의 비용은 열린 회차의 홀드 행 수에 비례한다. 쿼터 행이 아니다.
- 빈 회차(164~200)도 청크 1문장씩 든다.

### 4c. 마감 회차 제외와 기록 (§10 · C1)

- 대상 회차는 `starts_at > now(앱 Clock)`인 회차뿐이다. v1의 'starts_at + ttl + 2 × 주기' 유예는 없앴다(§10: 마감 회차는 카운터를 쓰지 않는다).
- 행 없음 갈래도 `schedule_id = ANY(:open)`.
- 기록:
  - 바퀴 기록 `counter_reconcile_run`에 `skipped_schedules`(마감 회차 수)와 `skipped_pairs`(그 회차들의 쿼터 행 수 — PK 앞부분 index-only count, 정리 ①이 지운 회차는 0)를 쓴다.
  - 지표 `seat.hold.counter.reconcile.skipped{unit=schedules|pairs}`.
  - 쌍 단위 `counter_drift_log`에는 쓰지 않는다(쌍마다 쓰면 마감 회차마다 수만 행이 쌓인다).

### 4d. 커넥션 빌림 (§10 D8 — 앱 풀 공유)

- C는 앱 Hikari 풀을 같이 쓴다.
- 지표 `seat.hold.counter.reconcile.borrows`: 커넥션을 한 번 빌릴 때마다 정확히 +1이다. JdbcTemplate 자동 커밋 문장 1개 = 1, TransactionTemplate 실행 1개 = 1(복구·skipped 기록 각각). 바퀴의 회차 목록 조회도 1이다.
  - 정리(`seat.hold.counter.cleanup.borrows`)도 같은 규칙이다.
  - A는 만료 트랜잭션 안이라 빌림이 늘지 않는다.
- 검증 테스트: 요청 없이 대조 한 바퀴를 돌린 뒤 Δ`hikaricp.connections.acquire` COUNT = Δ`reconcile.borrows`(정확히 같음).
- 하네스: `borrow_per_request = (Δacquire − Δreconcile.borrows − Δcleanup.borrows) ÷ (Δhold + Δconfirm)`. 범위 [0.98, 1.02]는 그대로다.
  - F는 별도 프로세스·별도 풀이라 앱 수치에 들어가지 않는다.
  - F 컨테이너의 빌림 수는 참고로 기록한다.

### 4e. 그 밖

v1 §4와 같다.

- 전용 스케줄러 `counterReconcileScheduler`. 기본 스케줄러는 스레드 1개라 만료 틱이 밀린다.
- F 프로필 `reconciler`: 포트 8102, 만료 스케줄러 끔, 매수 방식 키 없음.
- 지표: Timer `…reconcile.chunk`·`…reconcile.orphan`, 컨테이너별 CPU + JVM `process.cpu.usage`.

---

## 5. 판매 마감 (C2 · §10 D2)

### 판정 문장

`HoldSeatProcess.hold` 트랜잭션 안의 첫 문장으로 아래를 실행한다. SERIALIZABLE은 `SET TRANSACTION` 다음이다(v1 §5의 `acquireFirst`).

```sql
SELECT starts_at FROM product_schedule WHERE id = ? FOR SHARE
```

- `!clock.instant().isBefore(startsAt)`이면 `SCHEDULE_CLOSED`(409)다.
- 회차가 없으면 지금처럼 `SEAT_NOT_FOUND`(404)다.

### C2 확인

| 항목 | 결과 | 근거(실험 원문) |
|---|---|---|
| 정리 ①이 진행 중 선점을 기다린 뒤 지우지 않는다(E8 반례: 다른 사용자 7의 홀드가 진행 중일 때 사용자 8의 확정 행을 지운 경우) | 정리가 회차 행 `FOR UPDATE`에서 막히고, 선점 커밋 뒤 홀드 1을 보고 **0행 삭제**. 7·8 행 모두 남음 | `[C2a cleanup① vs in-flight hold (FOR SHARE/FOR UPDATE)] hold_closed_at_check=False · closed_now=True · cleanup_blocked=True · cleanup={'r': {'holds': 1, 'deleted': 0}} · quota_after=[(7, 1), (8, 1)]` |
| 정리가 회차 행을 쥔 동안 들어온 선점 | 마감 판정이 기다렸다가 정리 커밋 뒤 '마감'을 읽는다(→ `SCHEDULE_CLOSED`, 행을 다시 만들지 않음) | `[C2b late hold vs cleanup①] cleanup={'holds': 0, 'deleted': 1} · hold_check_blocked=True · hold_check_closed={'r': True}` |
| 선점끼리(FOR SHARE ×2)와 FK KEY SHARE | 서로 막지 않는다 | `[C2c two FOR SHARE + FK] second_blocked=False · second={'r': (False, 1)}` |
| 3b NOWAIT·precheck 순서 | 마감 FOR SHARE → 좌석 선조회 → upsert → 좌석 NOWAIT. 다른 선점이 좌석을 쥐면 NOWAIT가 바로 55P03을 낸다(마감 잠금이 대기를 만들지 않음) | `[C2d close FOR SHARE + NOWAIT order] other_blocked=False · other={'r': '55P03 after 0.7ms'}` |
| 회차 행 관리 UPDATE | FOR SHARE를 쥔 선점이 끝날 때까지 기다린다(지금 앱에는 회차 UPDATE 경로가 없다 — 기록만) | `[C2a' FOR SHARE vs schedule UPDATE] update_blocked=True · update={'r': 1}` |

- 커넥션 빌림: 같은 트랜잭션 안의 1문장이라 빌림 수는 그대로다(요청당 1).
- 마감 판정 대기가 생기는 경우는 둘뿐이다. 정리 ①이 그 회차 행을 쥔 짧은 동안(이때 회차는 이미 마감 — 거절될 요청), 그리고 관리 UPDATE.
- 교착은 없다.
  - 선점: 회차(공유) → 쿼터 → 좌석(NOWAIT).
  - 정리: 회차(배타) → 쿼터 행 삭제.
  - 정리가 기다릴 수 있는 쿼터 행 잠금 주체: 만료는 홀드 0이 확인된 회차에는 없다. 그 사이 만료가 진행 중이면 미커밋 DELETE가 아직 홀드로 보여 정리가 건너뛴다. 확정은 FK 때문에 회차 행에서 정리보다 먼저 막힌다.

### 비용 (선점당 공유 잠금 — §10 '측정에 넣는다')

- 회차 행은 FK 검사로 이미 KEY SHARE가 잡힌다(F16). 공유 잠금자 여럿이면 MultiXact를 쓴다. 마감 판정은 그 잠금을 트랜잭션 앞으로 당기고 더 센 모드로 잡는다.
- **S3·S4c는 요청이 한 회차 행에 몰린다(F19).** 가장 나쁜 경우다.
- 단일 PC 마이크로벤치(pgbench, 클라이언트 32, 15초, 변형: base = 마감 판정 없음 · plain = 잠금 없는 조회 · share = FOR SHARE · kshare = FOR KEY SHARE, 회차 1개 hot / 200개 spread, `pgb-out.txt`):

  ```
  rep=1 hot base tps=5805 · hot plain tps=5735 · hot share tps=3525 (wal_B_per_tx 998/997/1007)
  rep=2 hot base tps=2039 · hot plain tps=3074 · hot share tps=2107
  order=rev rep=1 hot share 1631 · plain 2487 · base 2562 / rep=2 hot share 1896 · plain 2092 · base 2878
  order=k rep=1 hot kshare 2049 · base 1815 · share 1873 · kshare 1863 / rep=2 hot kshare 2586 · base 2384 · share 1836 · kshare 1966
  spread: share/base 0.68 · 0.91 · 0.88 · 0.89 · 0.73 · 0.75
  ```

  - 해석: 이 PC는 CPU 제한이 없고 다른 작업과 공유한다. 측정 순서에 따라 tps가 2~3배 흔들린다. 그래서 **방향만** 본다.
  - hot에서 share가 base보다 낮은 경우가 많다(0.61·1.03·0.64·0.66·1.03·0.77). WAL은 +1~7%다.
  - KEY SHARE가 FOR SHARE보다 싸다는 증거는 없다(소음 안).
  - → 처리량 영향은 0~40% 범위로 열어 두고, **§9 마감 끔 대조 세트로 하네스에서 잰다**(`[구현 검증]`).
- 대안(측정 뒤 사용자 결정 후보, 지금은 §10대로 FOR SHARE):
  - FOR KEY SHARE: 정리 ①의 FOR UPDATE와 똑같이 충돌하므로 직렬화 효과가 같고, FK 검사와 같은 모드다.
  - 정리 ① 쪽만 잠금을 무겁게 하는 다른 직렬화(예: 회차별 advisory lock).

### 마감 끔 키 (§10 D2)

- `seat.hold.sales-close.enabled: true`(기본 켬).
- 끄면 회차 조회 문장 자체가 없다. FOR SHARE도 없고, 지금 코드 경로와 같다.
- 정리 ①은 마감 판정이 꺼져 있으면 기동 검사에서 거부한다. 직렬화 근거가 사라지기 때문이다.

### 테스트 · 하네스 영향

v1 §5 표와 같다(지금 영향 0, 2026-12-31 10:00Z 시한, `schedule-closed-seen` 사유). 마감 임박 셀에서는 이 사유를 끈다(§9).

---

## 5a. 판정기 `v_counter_mismatch` v2 (§10 · C1)

### 바꾸는 내용

`LoadtestDataService.consistency`를 바꾼다.

- 마감 회차(`starts_at <= :now`)의 쌍을 뺀다. 열린 회차는 지금 정의 그대로다.
- `:now`는 앱 `Clock`이다. `LoadtestDataService`에 `Clock`을 주입한다. 마감 판정과 같은 출처이고, 테스트에서는 MutableClock이다.
- 응답 필드 둘을 더한다: `counter_excluded_schedules`, `counter_excluded_pairs`(마감 회차의 쿼터 행 ∪ 실제 값 쌍).
- SQL 초안: `reconcile.sql` 끝 · `exp2.py JUDGE_SQL`.

### 실험 C3

정리 ①이 확정 쌍 3 + cnt 0 행 1을 지운 뒤를 봤다.

```
[C3 cleanup① deletes confirmed pairs → judge] before_open=(v2, all, excl_sched, excl_pairs)=(0, 0, 0, 0) · cleanup={'holds': 0, 'deleted': 4} · after_closed=(0, 3, 1, 3) · plus_open_drift=(1, 4, 1, 3)
```

- 마감 회차를 빼면 0이고, 빼지 않으면 3(지운 확정 쌍 수)이다. 제외 회차 1, 쌍 3이다.
- 열린 회차에 어긋남을 하나 넣으면 v2가 1을 잡는다. 제외가 판정을 통째로 끄지는 않는다.

### ADR-001~008 하네스 결과 해석에 주는 영향

- **ADR-001~003**: 매수 방식 none이라 이 항목을 내지 않는다(`LoadtestDataService.kt:164`). 영향이 없다.
- **ADR-005~008**: 이 항목을 내는 하네스다.
  - 시드 회차가 2026-12-31 19:00+09(F13)이라, 그 전 날짜의 측정에서는 제외 회차가 0이다. 값은 지금 정의와 **같다**.
  - 이미 커밋된 결과 폴더는 옛 앱이 만든 값이라 바뀌지 않는다.
  - 판정 규칙(`counter-mismatch`·`counter-mismatch-missing` — `k6/ADR-008/README.md:47`)은 키 존재와 값만 보므로 그대로 동작한다. 새 필드는 요약기가 무시한다(dict 키).
- **2026-12-31 10:00Z 이후 옛 하네스를 다시 돌리면**: 선점이 모두 `SCHEDULE_CLOSED`가 되고, 판정기는 모든 회차를 빼서 0을 낸다. 조용히 '합격'처럼 보인다.
  - ADR-009 하네스에는 사유 `counter-judge-vacuous`를 둔다: 제외 회차 = 전체 회차이고 마감 임박 셀이 아닐 때.
  - 옛 하네스(ADR-005~008)에는 손대지 않는다. 그 하네스들은 `schedule-closed`로 선점 201이 0이 되어 다른 사유(처리량·`s2-check` 등)로 드러나지만, 확인은 하지 않았다. ADR-009 문서에 기록한다.
- 테스트 도우미 `counterMismatch`(`V6FlywayUpgradeTest.kt:3` import — 테스트 쪽 별도 함수)는 판정기가 아니므로 바꾸지 않는다.

---

## 6. 쿼터 행 정리 2방식 (§10 · C2·C3)

공통 조건은 v1과 같다.

- 설정: `seat.hold.counter-drift.cleanup.mode: none | schedule | zero`, 전용 스케줄러.
- 허용 방식: counter-upsert·precheck·trigger만.

### ① 회차 단위

- 대상: 마감 회차(`starts_at <= now`) 중 쿼터 행이 남은 회차.
- 회차마다 한 트랜잭션에서 실행한다.
  1. 회차 행 `FOR UPDATE`
  2. 새 문장으로 `EXISTS(seat_hold WHERE schedule_id = s)`
  3. 없으면 `DELETE FROM user_hold_quota WHERE schedule_id = s`(**확정 쌍까지 전부** — §10)
  4. 기록 `counter_cleanup_run(schedule_id, mode, holds_seen, rows_deleted, waited_ms, at)`
- v1의 '마감 + 유예'와 쌍별 잠금·재확인(E8)은 회차 행 직렬화로 바꿨다. E8 방식은 '홀드가 남은 쌍만 남기고 다른 쌍을 지우는' 반례가 있어 §10 회차 단위 규칙과 맞지 않는다.
- 근거: C2a(진행 중 선점이 있으면 0행 삭제) · C2b(정리 중 선점은 마감을 읽음) · C3(확정 쌍을 지워도 판정기 v2 = 0).
- 실패 모드:
  - 홀드가 남은 회차는 건너뛴다. 기록에 holds_seen > 0으로 남는다.
  - 만료가 멈춘 회차는 정리되지 않는다. 바퀴마다 같은 기록이 쌓이는 것으로 드러난다.
  - `EXISTS`는 회차 인덱스가 없어 seat_hold 순차 스캔이다. 홀드 0이면 끝까지 훑는다. S4c 끝 상태 233MB면 수백 ms다. 그동안 회차 행을 배타로 쥐므로 그 회차의 확정(FK)이 기다린다. 마감 회차의 확정은 마감 전 홀드에서만 오므로, 홀드 0이 확인되는 시점에는 확정할 홀드가 없다. 대기 대상은 사실상 없다.
  - B에서는 지운 회차의 예약 취소가 SR001이 된다. 지금 취소 경로는 없다(기록만).

### ② cnt = 0

- v1과 같다: 한 문장 `DELETE … cnt = 0` + NOT EXISTS 가드. 근거는 E2·E2b·E3.
- 열린 회차와 마감 회차 모두 돈다.
- 마감 회차에 남는 확정 쌍(cnt > 0)은 지우지 않는다. 판정기·대조가 그 회차를 빼므로 어긋남 판정에는 영향이 없다.

### 비교 지표 (마감 임박 셀 — §9)

- 지운 행 수와 시각
- 정리 문장 시간
- `pg_stat_user_tables`(user_hold_quota): `n_dead_tup`·`autovacuum_count`·`n_tup_del`
- 쿼터 테이블 크기(끝)
- WAL 차분
- 정리 대기(`waited_ms`)
- 마감 판정 대기(선점 p99, 마감 직후 구간)

---

## 7. 기동 검사 (§10 D4)

`HoldStrategyStartupCheck`에 아래를 확인하게 한다.

1. 쿼터 테이블을 쓰는 방식 전부(counter·counter-upsert·counter-upsert-precheck·counter-trigger·quota-lock·quota-nowait·quota-nowait-early)에서 `to_regclass('user_hold_quota')`가 있어야 한다.
2. 트리거 ⇔ counter-trigger.
3. 후보와 매수 방식 조합:
   - A·C·F 켬 → counter 계열.
   - B + A는 거부.
   - 정리 → counter-upsert·precheck·trigger.
   - 정리 ① → `sales-close.enabled=true`.
   - A·C·F·정리 켬 → V7 기록 테이블.
4. `reconciler` 프로필 → `expiry-scheduler-enabled=false`.

영향과 테스트는 v1 §7과 같다. `IndexMigrationTest.WithoutIndexes`에 `limit-strategy=none`을 더한다(§10: 기존 테스트 수정 1곳).

---

## 8. 마이그레이션

| 파일 | 위치 | 내용 |
|---|---|---|
| `V7__counter_drift_log.sql` | `db/migration` | `counter_drift_log`(v1 열 + `reason VARCHAR(40) NULL` — skipped의 SQLSTATE) · `counter_reconcile_run`(v1 열 + `skipped_pairs`·`skipped_chunks`·`errors`·`orphan_ms_total`) · **`counter_cleanup_run`**(id, at, mode, schedule_id NULL, holds_seen, rows_deleted, waited_ms, stmt_ms) |
| `V8__counter_trigger.sql` | `db/migration-trigger`(선택 위치) | v2 초안(기여 불변 UPDATE 건너뛰기) |

새 인덱스는 없다. v2 대조도 기존 인덱스만으로 E1 측정값이 나왔다. 그 밖은 v1 §8과 같다.

---

## 9. ADR-009 하네스 계획 (v1 §9 + §10)

조건 주입·주입기·효과 지표·F 오버레이는 v1과 같다. 바뀌거나 더해진 것은 아래와 같다.

- **빌림 판정**: §4d 식(대조·정리 빌림을 뺀다). `after-k6.json`에 앱별로 `reconcile_borrows`·`cleanup_borrows` 차분을 더한다.
- **마감 끔 대조 세트**(§10 D2):
  - 조건 `control-noclose` = control + `sales-close.enabled=false`, S4c4200 디스크 3회.
  - 비교 대상은 ADR-009 control S4c4200 디스크(마감 판정 비용 = FOR SHARE 포함)다.
- **마감 임박 셀 `S3c-a20`**(§10 D6):
  - S3-a20과 같은 시나리오와 앱 설정이고, 조건은 control + `cleanup.mode=schedule|zero` 각 3회다.
  - 시드 회차 `starts_at`: run.sh가 **k6 시작 바로 전**에 `UPDATE product_schedule SET starts_at = <k6 시작 예정 + 600s>`를 DB에 직접 실행하고, 그 값을 `meta.json`에 남긴다. reset 인자로 넣으면 시드~k6 사이 준비 시간(측정마다 다름)이 오차가 된다.
  - 흐름: 도착 0~667s 중 600s 이후 약 2만 명은 `SCHEDULE_CLOSED`를 받는다. S3는 409를 받으면 다른 좌석으로 최대 3번 다시 시도한다 → 거절 요청 약 8만 건. 확정은 마감 뒤에도 계속된다(확정 성공 = §2 '확정은 막지 않음'의 부하 확인). 마지막 홀드 만료가 약 +900s → 정리 ① 실행.
  - 이 셀의 합격 사유:
    - `schedule-closed-seen`·`counter-judge-vacuous`는 끈다.
    - `close-leak`: 마감 시각 뒤에 보낸 선점의 201 > 0. k6 CSV 시각과 starts_at을 서버 시계 차(`clock-offset.json`)로 보정한다.
    - `confirm-after-close-failed`: 마감 뒤 확정의 409·5xx 중 `HOLD_EXPIRED`가 아닌 것 > 0.
    - `cleanup1-early`: 정리 기록에 holds_seen > 0인데 rows_deleted > 0.
    - `cleanup1-incomplete`: 끝에 마감 회차의 쿼터 행 > 0이고 홀드 0(①).
    - `cleanup2-left`: 끝에 cnt 0이고 실제 0인 행 > 0(②).
    - `pre-close-mismatch`: 마감 5초 전 대조 문장(§4b, 읽기만, 회차 1개 < 0.2초)으로 잰 어긋남 > 0. 끝의 판정기는 그 회차를 빼므로 마감 전 정합은 이 표본이 증거다.
- **효과 셀**: v1과 같다(control·A·C·F·B + C × S3-a20). 시드는 2026-12-31이라 마감이 없다.
- `counter-judge-vacuous`: 제외 회차 = 전체 회차이고 마감 임박 셀이 아니면 사유가 된다(§5a).

---

## 10. 측정 매트릭스 · 시간 (다시 계산)

1회 소요(실측, v1 §10과 같은 출처): S2 1.3분, S3-a20 21.8분, S4c4200 9.6분.

| 묶음 | 회차 | 시간 |
|---|---|---|
| 비용: 5조건 × {S4c4200 디스크 3 · S4c4200 tmpfs 3 · S2 3 · S3-a20 3} | 60 | 5 × (28.8 + 28.8 + 3.9 + 65.4) = 634.5분 |
| 마감 끔 대조: control-noclose × S4c4200 디스크 3 | 3 | 28.8분 |
| 효과: {control, A, C, F, B + C} × S3-a20 3 | 15 | 327분 |
| 마감 임박: S3c-a20 × {①, ②} × 3 | 6 | 130.8분(1회 = S3-a20과 같은 길이 — 도착·TAIL·TTL 대기가 같다) |
| **합계** | **84** | **1,121분 ≈ 18.7시간**(§10 '약 19시간'과 맞음) + 재측정·스모크 |

- 시한: 효과·비용 셀은 시드 2026-12-31 10:00Z 전에 끝나야 한다(§5a).

---

## 남은 사용자 결정 (v2에서 새로 생긴 것)

- **R1** 마감 판정 잠금 모드. §10은 `FOR SHARE`다. 정리 ①과의 직렬화 효과는 `FOR KEY SHARE`도 같고, FK와 같은 모드다. 마이크로벤치는 소음 안이라 우열을 가리지 못했다.
  - 제안: §10대로 FOR SHARE로 측정하고, 마감 끔 대조에서 비용이 크면(예: S4c4200 처리량 −10% 이상) KEY SHARE를 다시 정한다.
- **R2** v1 대조 설계(회차 범위 스캔)가 틀렸다. v2는 두 갈래(쿼터 주도 + 행 없음 PK 범위)다. 명세 §1 C의 '회차 단위로(또는 (회차, 사용자) 키 범위로)' 안에 들고, 탐지 범위(행 없음 포함)도 같다.
  - 바뀌는 것은 '행 없음' 갈래가 홀드·예약 id 범위로 돈다는 점이다. 확인만 받으면 된다.
- **R3** 대조 주기 기본값 60s(S4c 끝 상태 한 바퀴 ≈ 15초 실측 기반). 효과 셀(S3)의 탐지 시간은 주기에 비례한다. 효과 셀은 30s로 둘지 결정이 필요하다(비용 셀과 같은 값을 쓰면 비교가 단순하다).

## [구현 검증] 이연 목록 (메인이 프로젝트 대장에 등재해야 유효)

1. 실제 Hibernate에서 확정·만료 경합 때 orphanRemoval DELETE가 행 수 기대값(1)으로 실행되는지 — StaleState로 정합을 지키는가(§3b, ADR-013).
2. 마감 판정 `FOR SHARE`의 선점 처리량·p99 비용(S4c4200 마감 끔 대조 — §5, 마이크로벤치는 방향만).
3. 부하 중 대조 한 바퀴 시간과 청크 최대(§4b — 부하 없는 S4c 끝 상태 15초).
4. 정리 ①의 `EXISTS(seat_hold …)` 순차 스캔 시간과 회차 행 배타 잠금 보유 시간(§6).
5. A 실패 시 쌍 목록을 예외에 실어 독립 트랜잭션으로 skipped를 기록하는 경로(§1a — 앱 경로, SQL은 O1로 확인).
6. 대조 빌림 지표 = Hikari acquire 차분 정확 일치(§4d 검증 테스트).
7. v1에서 넘어온 것: savepoint subtrans 절벽(이제 A가 savepoint를 쓰지 않으므로 해당 없음 — 삭제 후보), compose `profiles:` 서비스 down 동작(오버레이로 회피 — 해당 없음), C 대조 세션 DB 쪽 구분.
