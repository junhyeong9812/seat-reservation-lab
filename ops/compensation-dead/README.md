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
- `last_result = REJECTED_…`(CLOSE가 4xx를 받음)는 PG 계약 위반 신호다. 재시도 전에 PG 쪽을 확인한다.

## 주의

- `/internal/*`는 운영 망에서만 열어야 한다(인증 없음 — 이 랩의 다른 internal 엔드포인트와 같다).
- `compensation_task`를 손으로 고치지 않는다(fencing · 리스 규칙이 깨진다). 상태 변경은 retry API로만 한다.
