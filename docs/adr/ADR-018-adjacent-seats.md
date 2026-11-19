# ADR-018: 연석 부분 선점 (Q2)
> 번호 이동(2026-10-12, 5차): 운영 중 매수 방식 전환을 새 ADR-010으로 넣으며 기존 ADR-010~015를 011~016으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-12, 4차): 카운터 불일치 탐지·격리·복구를 새 ADR-009로 넣으며 기존 ADR-009~014를 010~015로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-11, 3차): counter-upsert-precheck 기본 적용·검증을 새 ADR-008로 넣으며 기존 ADR-008~013을 009~014로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-09, 2차): ADR-006 처리량 급락 진단을 새 ADR-007로 넣으며 기존 ADR-007~012를 008~013으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-09): counter 단일 쿼리 upsert 재측정을 새 ADR-006으로 넣으며 기존 ADR-006~011을 007~012로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-04): 임계 구역 길이·락 보유 시간을 새 ADR-004로 넣으며 기존 ADR-004~010을 005~011로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-15, 6차): 앱 JVM GC·메모리 기준을 새 ADR-010으로 넣으며 기존 ADR-010~016을 011~017로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-16, 7차): 측정 환경 테일 레이턴시 원인을 새 ADR-011로 넣으며 기존 ADR-011~017을 012~018로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-18, 8차): 카운터 증분 보정·Kafka/복제본 보정을 새 ADR-012·013으로 넣으며 기존 ADR-012~018을 014~020으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-23, 9차): Kafka·읽기 복제본 보정(옛 ADR-013)을 착수 조건이 생기는 오픈 폭주 뒤로 보내 ADR-020으로 옮기고, 기존 ADR-014~020을 013~019로 당겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-11-07, 10차): 결제하고 좌석을 잃은 사용자 최소화(결제했는데 좌석을 잃음 — 예방 · 보상 설계)를 새 ADR-016으로 넣으며 기존 ADR-016~020을 017~021로 옮겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: 설계 (Design) — 1단계 구현 중, 측정 전
- 대응 질문: Q2 "2연석 중 1석만 선점에 성공했다면?"
- 선행: ADR-003, ADR-005(1인 매수 제한), ADR-016(결제 시작 시 홀드 연장), ADR-017(결제 의도와 보상)

## 문제

베이스라인은 요청 한 번에 1석만 선점한다. 연석(Load Profile의 40%)을 원하는 사용자는 1석 API를 두 번 불러야 한다. 둘째 석을 다른 사용자에게 빼앗기면, 원하지 않는 1석만 잡은 채 남는다. 홀드를 사용자가 놓는 API가 없어서 그 1석은 TTL(5분)이 끝날 때까지 아무도 살 수 없다.

## 재현 (2026-11-18, 로컬)

PC에서 기본 구성(비관락 NOWAIT, 카운터 upsert + 사전 확인, TTL 5분)으로 띄우고, 2석을 두 사용자가 반대 순서로 1석 API를 두 번씩 부르게 했다.

| 조건 | 시도 | 한 사람이 2석 | 두 사람 모두 1석 | 부분 선점 사용자 |
|---|---|---|---|---|
| 경합 없음(1인, 2석) | 20 | 20 | 해당 없음 | 0 |
| 두 사용자 동시 시작 | 200 | 0 | 200 | 400 |
| 둘째 사용자 0~50ms 늦게 시작 | 100 | 74 | 26 | 52 |

두 사용자가 동시에 시작하면 매번 둘 다 1석씩만 잡았다. 각자 첫 석은 바로 잡고, 둘째 석은 상대가 이미 잡아 409를 받는다. DB에는 1석 홀드 452개와 카운터 1인 사용자 452명이 남았다. 정합성 검증기(`/internal/consistency`)의 위반은 0이었다. 부분 선점은 지금 불변식으로는 정상 상태로 보인다는 뜻이다. 해제 경로는 없었다(`DELETE /api/holds/{id}` → 404). 부분 선점된 좌석은 TTL 동안 그대로 묶였다. 마지막 선점 뒤 약 2분 30초에는 600석이 모두 HELD였고, 만료 시각이 지난 뒤 첫 만료 배치에서야 홀드, HELD 좌석, 카운터가 모두 0이 됐다.

## 비교할 방식

측정(2단계)에서 비교할 방식은 네 가지다. 이름은 이 문서 안에서만 쓰는 줄임 이름이다.

| 이름 | 방식 | 부분 선점 |
|---|---|---|
| 베이스라인 | 1석 API를 두 번 부른다. 둘째가 실패하면 첫 석을 TTL까지 잡고 있다 | 생긴다(TTL까지) |
| 해제 후 포기 | 1석 API를 두 번 부르고, 둘째가 실패하면 해제 API로 첫 석을 바로 놓는다 | 잠깐 생긴다(해제까지) |
| 분리형 다석 선점 | 다석 API 한 번. 좌석 행을 id 오름차순으로 `FOR UPDATE NOWAIT` 잠금 → 홀드 INSERT → 좌석 상태 UPDATE를 문장마다 따로 보낸다 | 생기지 않는다 |
| 단일 쿼리 다석 선점 | 다석 API 한 번. 좌석 상태 UPDATE와 홀드 INSERT를 CTE 쿼리 한 문장으로 보낸다 | 생기지 않는다 |

두 다석 방식은 같은 API 뒤에서 설정 옵션 `seat.hold-group.mode`(`split` 또는 `one-shot`)로 고른다. 서버는 고른 좌석이 같은 회차인지와 1인 매수 제한만 본다. 좌석이 실제로 붙어 있는지는 화면이 정하고 서버는 검사하지 않는다.

## 설계

### 설계-1. 묶음 홀드와 스키마(V16)

다석 선점의 결과를 "묶음 홀드"라고 부른다. 묶음 홀드는 좌석마다 홀드 1행을 그대로 쓰고, 그 행들을 `hold_group` 한 행으로 묶는다. 1석 API가 만드는 홀드는 묶음이 없다(`group_id` NULL). 그래서 1석 경로의 쿼리와 측정 계약은 바뀌지 않는다.

```sql
CREATE TABLE hold_group (
    id          BIGSERIAL   PRIMARY KEY,
    schedule_id BIGINT      NOT NULL REFERENCES product_schedule (id),
    user_id     BIGINT      NOT NULL,
    seat_count  INT         NOT NULL CHECK (seat_count >= 1),
    created_at  TIMESTAMPTZ NOT NULL
);
ALTER TABLE seat_hold ADD COLUMN group_id BIGINT NULL REFERENCES hold_group (id);
CREATE INDEX idx_seat_hold_group ON seat_hold (group_id) WHERE group_id IS NOT NULL;

ALTER TABLE payment_intent ADD COLUMN group_id   BIGINT NULL REFERENCES hold_group (id);
ALTER TABLE payment_intent ADD COLUMN seat_count INT    NOT NULL DEFAULT 1;
ALTER TABLE payment_intent ADD CONSTRAINT payment_intent_group_uk UNIQUE (group_id);

ALTER TABLE reservation DROP CONSTRAINT reservation_intent_uk;
ALTER TABLE reservation ADD CONSTRAINT reservation_intent_seat_uk UNIQUE (intent_id, seat_id);
```

- V16은 기본 마이그레이션 위치(`classpath:db/migration`)에 둔다. V1~V15는 고치지 않는다.
- 묶음의 홀드 행은 언제나 같은 `expires_at`을 가진다. 선점 때 한 시각으로 넣고, 결제 시작 때 함께 연장하기 때문이다. 만료 배치는 `expires_at <= now`인 행을 지우므로 묶음은 통째로 만료된다. 행마다 만료를 따로 계산하지 않아도 된다.
- `hold_group` 행은 홀드가 사라져도 남긴다. 결제 의도와 예약이 그 id를 가리키기 때문이다.
- 부하 테스트 데이터 초기화(`LoadtestDataService.reset`)의 `TRUNCATE` 목록에 `hold_group`을 넣는다. `hold_group`이 `product_schedule`을 참조하므로, 목록에 없으면 묶음 API를 쓰지 않아도 V16 뒤의 초기화가 외래키 때문에 실패한다. V15 표와 같이 표가 있을 때만 넣고(`to_regclass`), `RESTART IDENTITY`로 id도 1부터 다시 시작한다.
- `reservation_intent_uk`(결제 의도당 예약 1행 — ADR-017 N17-1)를 (결제 의도, 좌석)당 1행으로 바꾼다. N17-1은 '결제 의도당 예약 수 ≤ `seat_count`'로 바뀐다. 1석 결제 의도는 좌석이 하나라서 의미가 같다. 스키마 모습을 그대로 적어 둔 기존 시험(적용된 Flyway 버전 목록 · 인덱스 집합 · `reservation.intent_id` 단독 유니크)은 V16에 맞춰 고친다(2026-11-19 사용자 합의). 결제 의도당 예약 수가 좌석 수를 넘지 않는지는 정합성 검증기가 센다(설계-7).

### 설계-2. 다석 선점 API

```
POST /api/schedules/{scheduleId}/hold-groups
X-User-Id: 7
{"seatIds": [101, 102]}

201 {"groupId": 3, "expiresAt": "...", "payBy": "...", "payWithinSeconds": 599,
     "holds": [{"holdId": 11, "seatId": 101}, {"holdId": 12, "seatId": 102}]}
```

- `seatIds`는 1개 이상, 1인 한도(`seat.hold.max-per-user`, 기본 2) 이하이고 중복이 없어야 한다. 아니면 400 `INVALID_SEAT_SELECTION`이다.
- `holds`는 좌석 id 오름차순이다. `payBy`와 `payWithinSeconds`는 1석 응답과 같은 규칙으로 붙는다.
- 오류 순서는 1석 API와 같다. 판매 마감(409 `SCHEDULE_CLOSED`) → 좌석 없음(404 `SEAT_NOT_FOUND`, 다른 회차 좌석 포함) → 선점 불가(409 `SEAT_NOT_AVAILABLE`) → 한도 초과(409 `HOLD_LIMIT_EXCEEDED`). 어느 단계에서 실패해도 좌석, 홀드, 카운터, 묶음 행이 하나도 바뀌지 않는다.

트랜잭션 한 개 안의 순서는 다음과 같다.

1. 회차 행을 `FOR SHARE`로 잠그고 판매 마감을 확인한다(1석과 같다).
2. 고른 좌석이 모두 그 회차에 있고 AVAILABLE인지 미리 읽어 확인한다(잠금 없음, 1석의 사전 확인과 같은 역할).
3. 카운터를 `+N` 한 번으로 올린다. `INSERT … VALUES (?, ?, N) ON CONFLICT … DO UPDATE SET cnt = cnt + N WHERE cnt + N <= max`이다. 1석 경로의 `+1` 쿼리는 그대로 둔다.
   - 0행이면 바로 한도 초과로 답하지 않는다. 고른 좌석을 새 문장으로 다시 읽어, 그 사이 팔린 좌석이 있으면 409 `SEAT_NOT_AVAILABLE`, 모두 AVAILABLE이면 409 `HOLD_LIMIT_EXCEEDED`다. 1석의 counter-upsert-precheck(`UserLimitStrategies.kt` `CounterUpsertPrecheckUserLimit.acquire`)와 같은 순서다.
   - 올린 뒤에는 1석과 같이 바뀐 (회차, 사용자)를 불일치 탐지(ADR-009)에 등록한다.
4. 좌석을 잡는다. 여기서 두 방식이 갈린다.
   - **분리형**: `SELECT … FROM product_seat WHERE id = ANY(?) AND schedule_id = ? ORDER BY id FOR UPDATE NOWAIT`. 다른 트랜잭션이 잡고 있으면 바로 55P03이 나고 409 `SEAT_NOT_AVAILABLE`이 된다. 잠근 뒤 모두 AVAILABLE인지 다시 확인한다. 그다음 `hold_group` INSERT, 홀드 N행 INSERT, `UPDATE product_seat SET status = 'HELD' WHERE id = ANY(?)`를 따로 보낸다.
   - **단일 쿼리**: 묶음 행을 넣은 뒤 아래 한 문장을 보낸다. 바뀐 행이 N이 아니면 트랜잭션을 롤백하고 409 `SEAT_NOT_AVAILABLE`이다.

```sql
WITH picked AS (
    SELECT id FROM product_seat
    WHERE id = ANY(?) AND schedule_id = ? AND status = 'AVAILABLE'
    ORDER BY id FOR UPDATE SKIP LOCKED
), held AS (
    UPDATE product_seat s SET status = 'HELD' FROM picked WHERE s.id = picked.id RETURNING s.id
)
INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at, group_id)
SELECT id, ?, ?, ?, ?, ? FROM held
RETURNING id, seat_id
```

두 방식 모두 교착이 생기지 않는다. 분리형은 id 오름차순으로 잠그고 기다리지 않는다. 단일 쿼리는 잠긴 행을 건너뛰고(`SKIP LOCKED`) 바뀐 행 수로 실패를 판단하므로 기다리지 않는다. 겹치는 좌석을 두 사용자가 다른 순서로 요청해도 한 사람은 2석을 다 잡거나, 둘 다 409를 받는다.

### 설계-3. 해제 API

```
DELETE /api/holds/{holdId}           X-User-Id: 7   → 204   (1석 홀드)
DELETE /api/hold-groups/{groupId}    X-User-Id: 7   → 204   (묶음 홀드 전체)
```

- 홀드를 지우고, 좌석을 AVAILABLE로 되돌리고, 카운터를 지운 행 수만큼 내린다. 묶음이면 N만큼 내린다.
- 없는 홀드나 묶음은 404 `HOLD_NOT_FOUND`, 남의 것은 403 `HOLD_NOT_OWNED`다.
- 결제 시작 뒤(결제 의도가 있으면)에는 409 `PAYMENT_STARTED`다. 결제가 진행 중인 좌석을 놓으면 돈만 받고 좌석이 없는 경우가 생기기 때문이다.
- 묶음에 속한 홀드를 `DELETE /api/holds/{holdId}`로 지우려 하면 409 `HOLD_IN_GROUP`이다. 묶음은 통째로만 놓는다.
- 만료됐지만 아직 배치가 지우지 않은 홀드도 해제할 수 있다. 결과는 만료 배치와 같다.
- 해제는 만료 배치(sweep)와 같은 방법으로 카운터를 내리므로, 결제 시작이 있는 확정 모드(`conditional` · `conditional-extend`)에서만 동작한다. 이 두 모드에서만 결제 중 해제를 409로 막을 수 있고, ADR-015 · 016 기동 검사가 `current`가 아닌 모드에 기본 조합(`seat.hold.expiry-mode=sweep` · `seat.hold.strategy=pessimistic-nowait` · `seat.hold.limit-strategy=counter-upsert-precheck`)을 이미 강제하므로 카운터를 내리는 방법도 만료 배치와 맞는다. 그 밖의 구성에서는 두 해제 API 모두 404다. 예를 들어 `counter-trigger`에서는 DB 트리거가 홀드를 지울 때 이미 카운터를 내리므로, 앱이 한 번 더 내리면 카운터가 실제보다 낮아지고 1인 한도를 넘겨 잡을 수 있다. `current`에서는 만료 배치와 확정이 JPA로 좌석 행을 먼저 잠가 위의 잠금 순서가 맞지 않는다. `current` · `pessimistic` · `optimistic`에는 결제 시작이 없어 결제 중 해제를 막을 수 없다.

해제는 다음 순서로 동시 요청과 겹치지 않게 한다. 잠그는 순서는 만료 배치(sweep)와 같은 카운터 행 → 홀드 행이다. sweep은 카운터 행(`user_hold_quota`)을 먼저 `FOR UPDATE`로 잠그고 홀드를 지운다(`SweepConditionalBatch.expireInTx`). 해제가 반대로 홀드를 먼저 잠그면, 만료된 홀드를 해제와 sweep이 동시에 다룰 때 서로를 기다려 교착(40P01)이 난다.

1. 대상 홀드를 잠금 없이 읽어 회차와 소유자를 얻는다. 0행이면 404, 소유자가 요청자가 아니면 403이다.
2. 그 (회차, 사용자)의 카운터 행을 `FOR UPDATE`로 잠근다. 카운터 행이 없으면(ADR-009의 MISSING) 대상 홀드 id를 잠금 없이 읽어 만료 배치와 같은 복구 경로로 카운터 행을 먼저 만들고 잠근다. 홀드를 먼저 잠그고 나중에 카운터 행을 만들면 만료 배치와 순서가 뒤집혀 교착한다. 해제가 이 갈래에서 먼저 지우고 커밋하면, 같은 홀드를 복구 대상으로 읽어 둔 만료 배치는 지운 수가 맞지 않아 그 틱을 롤백한다(기존 배치 규칙 — 확정과 겹칠 때와 같다). 다음 틱이 다시 보므로 교착이나 카운터 불일치는 남지 않는다.
3. 대상 홀드 행을 `FOR UPDATE`로 잠그고 소유자를 다시 확인한다. 그 사이 사라졌으면 404다.
4. 새 문장으로 결제 의도가 있는지 확인한다. 읽기 커밋 격리 수준에서는 문장마다 새 스냅숏을 쓰므로, 앞서 커밋된 결제 시작이 보인다.
5. `DELETE … RETURNING`으로 지운 행 수만큼만 카운터를 내리고 좌석을 되돌린다. 카운터를 내리는 방법은 sweep과 같다(`ExpiryCounters.decrementDeleted` — 카운터 행이 없으면 기존 복구 경로).

결제 시작 쪽은 결제 의도를 넣을 때 홀드 행을 `FOR KEY SHARE`로 읽는다. 해제의 `FOR UPDATE`와 충돌하므로 둘 중 먼저 잡은 쪽이 끝난 뒤에 다른 쪽이 진행한다. 해제가 먼저면 결제 시작은 홀드를 찾지 못해 404가 되고, 결제 시작이 먼저면 해제가 409가 된다. 만료 배치와 확정도 `DELETE … RETURNING`으로 지우므로, 같은 홀드를 두 경로가 동시에 지워도 행을 돌려받은 쪽만 카운터를 내린다.

### 설계-4. 묶음 결제 시작

```
POST /api/hold-groups/{groupId}/payment-start    X-User-Id: 7
200 {"groupId": 3, "expiresAt": "...", "payBy": "...", "payWithinSeconds": 610, "extended": true, "paymentUid": "..."}
```

1석 결제 시작(ADR-016 · `PaymentStartService.paymentStart`)과 같은 순서를 묶음에 적용한다.

1. **재호출**: 이 묶음의 결제 의도(`group_id`)가 이미 있으면 판정과 연장 없이 그 결제 의도를 돌려준다(같은 `paymentUid`, 같은 `payBy`). 묶음의 홀드를 다시 읽어 없으면 404, 남의 것이면 403, 만료됐으면 409 `HOLD_EXPIRED`다(1석 재호출과 같다). 첫 호출이 연장한 뒤에는 `extended_at`이 채워져 아래 연장 문장이 0행이 되므로, 재호출을 먼저 걸러야 같은 결제 의도를 돌려줄 수 있다.
2. **연장**: `UPDATE seat_hold SET expires_at = now + W, extended_at = now WHERE group_id = ? AND user_id = ? AND extended_at IS NULL AND expires_at > now + θ AND expires_at < now + W AND NOT EXISTS (결제 의도)`. `expires_at < now + W`는 연장이 만료를 실제로 늦출 때만 바꾸는 조건이다. 이 조건이 없으면 TTL이 W보다 긴 설정(기동 검사가 막지 않는다)에서 연장이 만료를 앞당긴다. N행을 바꾸면 `extended = true`로 결제 의도를 만든다.
3. **0행이면 원인을 가린다**(1석 진단과 같은 순서): 홀드 없음 404 → 남의 것 403 → 만료 409 `HOLD_EXPIRED` → 남은 시간 θ 이하 409 `HOLD_EXPIRED` → 그 밖(연장이 필요 없음)은 `extended = false`로 결제 의도를 만든다.
4. 바뀐 행이 0도 N도 아니면 묶음의 만료가 갈라진 것이다(설계-7 `group_expiry_split`). 롤백하고 409 `HOLD_EXPIRED`로 거부하며 ERROR 로그를 남긴다.
- 결제 의도는 1개다. `group_id`, `seat_count = N`을 채우고, `hold_id`, `seat_id`, `section`에는 id가 가장 작은 홀드의 값을 넣는다. 그래서 기존 쿼리(만료된 결제 의도 찾기 등)가 `hold_id`로 묶음을 그대로 따라간다.
- PG에는 `N × seat.payment.amount`를 등록한다. 지금 등록 코드(`PgRegistration.register`)는 언제나 `seat.payment.amount` 한 번분을 넘기므로, 금액을 인자로 받게 바꾸고 1석은 `1 × amount`를 넘긴다(1석 동작은 같다). 금액은 결제 의도의 `seat_count`로 계산하므로 재호출에서도 같다. mock PG는 같은 uid의 재등록에서 처음 등록한 금액을 그대로 두므로, 등록 응답의 금액이 넘긴 금액과 다르면 503으로 거부하고 ERROR 로그를 남긴다.
- 환불(CLOSE)은 PG가 등록 금액 전체를 돌려주므로, 묶음 환불은 CLOSE 1건이고 금액은 N석의 합이다.
- 묶음에 속한 홀드로 1석 결제 시작(`/api/holds/{holdId}/payment-start`)을 부르면 409 `HOLD_IN_GROUP`이다.

### 설계-5. 묶음 확정

```
POST /api/hold-groups/{groupId}/confirm    X-User-Id: 7    {"paymentUid": "..."}
200 {"status": "CONFIRMED", "reservations": [{"reservationId": 21, "seatId": 101}, {"reservationId": 22, "seatId": 102}]}
```

- 결제 의도를 잠그고(`FOR UPDATE`) 묶음과 사용자가 맞는지 본다. 모르는 `paymentUid`이거나 결제 의도의 묶음 · 사용자가 요청과 다르면(없는 묶음 포함) 409 `SEAT_NOT_HELD`이고 거부를 기록하지 않는다. 1석 확정에서 같은 사용자가 다른 홀드의 uid를 보낸 경우(ADR-017 Q3)와 같은 규칙이다. 이미 처리된 결제 의도면 저장된 결과를 다시 돌려준다. 1석 재생(`PaymentConfirmService.replay`)은 결제 의도에 저장된 예약 id 1개로 예약 1행만 읽으므로 묶음에는 쓸 수 없다. 묶음 재생은 `reservation`을 `intent_id`로 읽어 좌석 id 순으로 N행을 돌려주고, 읽은 행 수가 `seat_count`와 다르면 500으로 실패시키고 ERROR 로그를 남긴다(정합성 검증기의 `group_partial_reservations`가 같은 상태를 센다).
- 확정은 한 트랜잭션에서 한다. `DELETE FROM seat_hold WHERE id IN (SELECT id FROM seat_hold WHERE group_id = ? AND user_id = ? AND expires_at > now ORDER BY id FOR UPDATE) RETURNING seat_id`가 N행이 아니면 거부한다. 묶음의 홀드 행을 id 순으로 잠그는 것은 해제(id 순 `FOR UPDATE`)와 잠금 순서를 맞춰 교착을 막기 위해서다. `WHERE group_id = ?`만 쓰면 행을 스캔 순서대로 잠근다. 결제 시작의 연장 `UPDATE`도 같은 꼴이다. 만료 배치와는 `expires_at`으로 대상이 갈린다. 확정과 결제 시작은 만료 전 행만, 배치는 만료된 행만 잠근다. 다만 두 경로가 읽는 현재 시각이 조금씩 다르므로, 만료 시각이 그 사이에 걸린 드문 순간에는 같은 묶음 행을 서로 다른 순서(id 순과 배치의 스캔 순)로 잠가 교착(40P01)이 날 수 있다. 실패한 요청은 롤백되고, 확정이 졌다면 결제 의도가 OPEN으로 남는다. 보상 모드가 `refund`면 폴백 스캔이 환불하고, `none`이면 폴백 스캔은 이를 찾아 기록만 한다. 이 남은 위험은 2단계 측정에서 빈도를 본다. 이어서 `UPDATE product_seat SET status = 'RESERVED' WHERE id = ANY(?) AND status = 'HELD'`도 N행이어야 한다. 예약 N행을 넣고 결제 의도를 CONFIRMED로 바꾼다(`reservation_id`에는 가장 작은 예약 id).
- 거부되면 N석 모두 그대로 두고(롤백) 1석과 같은 방식으로 거부를 기록한다. 보상 모드가 refund면 CLOSE 작업 1건을 만든다.
- 카운터는 바꾸지 않는다. 홀드 N행이 예약 N행으로 바뀔 뿐이다(1석과 같다).
- 묶음에 속한 홀드로 1석 확정(`/api/holds/{holdId}/confirm`)을 부르면 409 `HOLD_IN_GROUP`이다.

### 설계-6. 만료와 보상

- 만료 배치(sweep)는 바꾸지 않는다. 묶음의 행은 같은 시각에 만료되므로 함께 지워지고, 카운터는 지운 행 수(N)만큼 내려간다.
- 결제를 시작하고 확정하지 않은 묶음은 1석과 같은 경로로 닫힌다. 보상 sweep이 `hold_id`(가장 작은 홀드)가 사라진 OPEN 결제 의도를 찾아 CLOSE 작업을 만든다.
- 묶음은 보상 모드 `refund`와 `none`에서만 쓸 수 있다. `auth-capture`와 `substitute`는 ADR-017에서 비교용으로 남긴 설정 옵션이고, 대체 좌석을 N석으로 넓히는 규칙은 이번 범위가 아니다.

### 설계-7. 켜는 조건과 정합성 검사

- 설정 옵션 `seat.hold-group.enabled`(기본 `false`)를 켜야 묶음 API(선점, 해제, 결제 시작, 확정)가 열린다. 해제 API `DELETE /api/holds/{holdId}`는 1석에도 쓰므로 이 옵션과 무관하게 열린다. 단 설계-3대로 기본 조합에서만 동작하고, 그 밖의 구성에서는 404다.
- 켰을 때 기동 검사가 다음 조합만 허용한다. 맞지 않으면 Flyway migrate 앞에서 기동을 거부한다.
  - 확정 모드 `conditional-extend`(그래서 만료는 sweep, 홀드 전략은 pessimistic-nowait, 매수 방식은 counter-upsert-precheck — ADR-016 기동 검사가 이미 강제한다)
  - 보상 모드 `none` 또는 `refund`
- 정합성 검증기에 다음 항목을 더한다.
  - `pay_multi_reservation_intents`: 결제 의도당 예약 수가 `seat_count`를 넘는 결제 의도 수(1석이면 지금과 같은 뜻).
  - `group_partial_holds`: 남은 홀드 수가 0도 `seat_count`도 아닌 묶음 수(부분 선점).
  - `group_expiry_split`: 남은 홀드의 `expires_at`이 둘 이상인 묶음 수.
  - `group_partial_reservations`: 결제 의도의 예약 수가 0도 `seat_count`도 아닌 묶음 수.

### 설계-8. 바뀌지 않는 것

- 1석 선점, 결제 시작, 확정의 요청과 응답은 그대로다. 쿼리는 하나만 바뀐다: 1석 결제 시작이 결제 의도를 넣을 때 홀드 행을 `FOR KEY SHARE`로 읽는다(설계-3, 해제와 겹치지 않게). `HOLD_IN_GROUP` 검사 문장은 묶음 API를 켰을 때만 보낸다. 묶음 홀드를 1석 API에 넘겼을 때의 409 `HOLD_IN_GROUP`만 새로 생긴다. 지금은 묶음 홀드가 없으므로 기존 호출에는 영향이 없다.
- 묶음을 켰다가 끌 때는 남은 묶음 홀드가 0인지 먼저 확인한다(`SELECT count(*) FROM seat_hold WHERE group_id IS NOT NULL`). 끈 상태에서는 `HOLD_IN_GROUP` 검사를 하지 않으므로, 남은 묶음 홀드에 1석 결제 시작이나 확정이 들어가 묶음이 쪼개질 수 있다. 기동할 때 남은 묶음 홀드가 있으면 WARN 로그를 남긴다.
- 확정, 보상, 홀드 전략의 기본값은 바꾸지 않는다. 다석 방식(`split` 또는 `one-shot`)과 묶음 API를 기본으로 켤지는 측정 뒤에 정한다.

## 실험 결과

미측정. 측정 계획은 2단계 명세에서 정한다(k6를 .9 또는 .158에서 실행).

## 결정

미정.
