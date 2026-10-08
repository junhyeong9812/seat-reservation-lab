# ADR-006: 매수 카운터를 한 문장 upsert로 — counter vs advisory-try 재비교
> 번호 이동(2026-10-09): counter 1문장 upsert 재측정을 새 ADR-006으로 넣으며 기존 ADR-006~011을 007~012로 옮겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: 가설 (Hypothesis) — 명세 인터뷰 전
- 날짜: 2026-10-09
- 선행: **ADR-005(1인 매수 제어 10종 — 결정 L2(advisory-try) 기본, L5(counter) 조건부)**, ADR-003(좌석 3b(FOR UPDATE NOWAIT))
- 후행: ADR-007(홀드 만료 — 카운터를 내리는 주체)

## 1. 문제

ADR-005에서 L5 `counter`(카운터 조건부 UPDATE)는 매수 정합성·가짜 거절이 advisory 계열과 같고, **매수 초과 사용자가 좌석 락을 아예 잡지 않는 유일한 방식**이었다(S7-m1(2매 보유자 + 일반 1명) 억울한 좌석 0석 vs 63~81석). 같은 사용자 동시 요청의 지연도 가장 짧았다(S2(사용자 100명 × 각자 좌석 10개 동시) L2(앱·DB CPU 2개) p50 258ms vs advisory-try 980ms).

그러나 이번 구현은 S4(도착률 계단, 요청마다 새 사용자·좌석)에서 처리량이 회귀했다 — L4(앱·DB CPU 4개) 엄격 한계 4,300 → 1,911(−56%), 회차에 따라 커짐. 원인은 판정이 아니라 **트랜잭션 밖 준비 단계(prepare)**: 요청마다 쿼터 행 확인 `SELECT` → 없으면 `INSERT`(자동 커밋) → 본 트랜잭션으로 **커넥션을 3번 빌렸다**(ADR-005 §7.3 ③).

질문: 판정을 **트랜잭션 안 한 문장**으로 바꿔 빌림을 1번으로 만들면, counter의 장점을 유지한 채 처리량이 대조군 수준으로 돌아오는가? 돌아오면 ADR-005의 기본을 counter로 바꿀 근거가 된다.

## 2. 선택지 (초안 — 인터뷰에서 확정)

| 방식 | 설명 |
|------|------|
| none | 제어 없음(대조군) |
| advisory-try | ADR-005 결정 — `pg_try_advisory_xact_lock(회차, 사용자)`, 좌석 확인 뒤 거절 |
| **counter-upsert** | 트랜잭션 안 한 문장: `INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 1) ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = user_hold_quota.cnt + 1 WHERE user_hold_quota.cnt + 1 <= ?` — 영향 행 1 = 통과, 0 = 매수 초과(409). prepare 없음 |
| (후보) counter(ADR-005 구현) | prepare가 원인인지 같은 캠페인에서 가르려면 함께 잰다 — 인터뷰에서 결정 |

- counter-upsert의 동시성: 같은 사용자 동시 요청은 그 행의 락에서 줄을 서고, `ON CONFLICT DO UPDATE`는 최신 행 버전으로 `WHERE`를 다시 본다(READ COMMITTED). 첫 요청 두 개가 동시에 INSERT하면 하나는 기다렸다가 UPDATE 경로로 간다.
- 좌석에서 지면 같은 트랜잭션 롤백으로 +1이 되돌아간다. 만료 배치의 −1은 ADR-005 구현 그대로.

## 3. 가설 (초안)

| # | 가설 | 틀렸다고 판정할 관측 |
|---|------|------------------|
| H1 | counter-upsert는 요청당 커넥션 빌림 1회다 | Hikari 획득 COUNT ÷ 선점 요청 > 1 |
| H2 | counter-upsert의 S4 엄격 한계는 none·advisory-try와 같다 | 계단 한 칸 이상 낮음 |
| H3 | counter-upsert는 ADR-005 counter의 장점(S7-m1 억울한 좌석 0, S2 지연 최소, 매수 정합 0)을 유지한다 | S7-m1 > 0 / 매수 초과 > 0 |
| H4 | 조건 순서를 섞으면 ADR-005에서 보인 시간에 따른 회귀가 방식 간에 고르게 퍼진다(시간 효과와 방식 효과 분리) | 같은 방식의 회차 간 차이가 방식 간 차이보다 큼 |

## 4. 측정 계획 (초안 — 사용자 2026-10-09 "세 방식을 같은 캠페인에서")

| 대상 | 시나리오 | 단계 | 회차 |
|------|---------|------|------|
| none · advisory-try · counter-upsert (+ 후보 counter) | S2 · S4 | L2 · L4 | 5 |
| 같음 | S7-m1 | L4 | 5 |

- 조건 순서를 회차마다 섞는다. 서버 디스크 지연(fsync) 지표를 함께 수집한다(ADR-005 §9 하네스).
- S4 엄격 한계 규칙의 목표 미달 처리(ADR-005 §7.3 ③)를 먼저 고친다.
- 약 6시간(세 방식 기준 추정).

## 5. 실험 결과

미측정.

## 6. 결정

미정.
