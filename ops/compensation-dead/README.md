# 보상 작업 DEAD 복구 (ADR-017 설계-4.4 · RD17-5)

보상 워커가 시도 상한(`seat.compensation.worker.max-attempts`, 기본 10)을 넘긴 작업은 `compensation_task.status = 'DEAD'`가 된다.
DEAD는 자동으로 다시 돌지 않는다. **사용자 돈이 묶여 있을 수 있으므로**(환불 · 승인 취소 미완) 운영자가 끝내야 한다.

## 알아차리기

- 지표 `seat.compensation.dead{type}` 증가 · ERROR 로그 `보상 작업이 시도 상한(…)을 넘어 DEAD`.
- 지표 `seat.compensation.oldest_pending_seconds`가 계속 커지면 PENDING이 정체 중이다(PG 장애 · 워커 정지 — DEAD 전 단계).
- 목록:

```sql
SELECT t.id, t.uid, t.type, t.source, t.attempts, t.last_result, t.created_at, i.user_id, i.state
  FROM compensation_task t JOIN payment_intent i ON i.uid = t.uid
 WHERE t.status = 'DEAD' ORDER BY t.created_at;
```

## 판단

1. PG에서 그 uid의 지금 상태를 본다: `GET <pg>/payments/{uid}`.
2. 작업 종류별로 끝난 상태인지 확인한다.

| type | 이미 끝난 PG 상태 | 아직이면 |
|---|---|---|
| CLOSE | `REFUNDED` · `VOIDED` · `CLOSED` | 재시도(아래) |
| CAPTURE | `CAPTURED` | 재시도. 승인이 만료됐으면(`EXPIRED`) 매입은 불가능하다 — 돈 없는 좌석(Q5)으로 기록하고 정책에 따라 처리 |

3. PG가 이미 끝난 상태여도 재시도로 닫는다. PG 호출은 uid 멱등이라 두 번 불러도 결과는 하나다(N17-4). 그래서 작업이 DONE으로 기록된다.

## 재시도

```bash
curl -X POST http://<app>/internal/compensations/<id>/retry   # 200 = PENDING으로 · 409 = DEAD 아님 · 404 = 없음
```

- attempts가 0으로 돌아가고, 다음 워커 주기에 다시 집힌다.
- 원인(PG 주소 · 장애 · 거부 코드 `last_result`)을 먼저 없애라. 그대로면 다시 DEAD가 된다.
- `last_result = REJECTED_…`(CLOSE가 4xx · CAPTURE가 409 밖 4xx를 받음)는 PG 계약 위반 · 원장 불일치 신호다. 재시도 전에 PG 쪽을 확인한다.

## 수동 종결 기록 (재시도로 끝낼 수 없을 때 — 설계-4.4)

PG 쪽에서 직접 처리했거나(수동 환불 등) 재시도해도 같은 결과가 나는 작업(예: PG 원장에 uid가 없어 CAPTURE가 404)은 **DEAD 행만** 손으로 DONE으로 닫는다.
DEAD는 워커가 집지 않고 리스도 없으므로 fencing과 부딪히지 않는다. `last_result`에 `MANUAL_` + 사유를 남긴다(판정 · 집계에서 수동 종결을 가른다).

```sql
UPDATE compensation_task
   SET status = 'DONE', last_result = 'MANUAL_<사유>', done_at = now()
 WHERE id = <id> AND status = 'DEAD';   -- 1행이어야 한다(0이면 이미 DEAD가 아니다 — 다시 본다)
```

## 주의

- 이 엔드포인트는 reconciler를 뺀 **모든 프로필**의 업무 포트에 인증 없이 열린다(loadtest 프로필에서만 열리는 `/internal/reset` 등과 다르다). 운영 망 밖으로 업무 포트를 열 때는 앞단에서 `/internal/*`를 막아라 — PG 호출은 uid 멱등이라 되살려도 돈이 두 번 움직이지는 않는다.
- `compensation_task`의 PENDING · IN_PROGRESS 행은 손으로 고치지 않는다(fencing · 리스 규칙이 깨진다). 손으로 바꾸는 것은 위의 DEAD → DONE 수동 종결뿐이다.
