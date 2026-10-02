# ADR-002 결과 요약 — `c04-noidx-p10-bg1m` · 인덱스 off · 풀 10 · 배경 1000000

> `scripts/summarize.py`가 원시 결과에서 산출 · 측정 SHA `557dff4` · 계획 2단계 × 2셀 × 5회.
> 값 = 중앙값 [최소–최대], n = 정상 회차 수. 정상이 아닌 회차(실패·미측정)는 마지막 열에 모두 표시한다.

## S1 같은 좌석 1,000명 (Q1) — 정합이면 201 = 1

| 단계 | 셀 | n | 201 | 409 | 에러율 | p50 ms | p99 ms | 좌석당 초과 홀드 행 | 201 − 홀드 행 | 비정상 회차 |
|---|---|---|---|---|---|---|---|---|---|---|
| L2 | S1 | 5 | 10 [10–10] | 990 [990–990] | 0 [0–0] | 3,447.32 [1,936.06–3,897.06] | 4,878.49 [3,293.69–5,498.94] | 9 [9–9] | 0 [0–0] | - |
| L4 | S1 | 5 | 10 [10–10] | 990 [990–990] | 0 [0–0] | 330.69 [229.84–602.99] | 1,087.21 [832.46–1,173.46] | 9 [9–9] | 0 [0–0] | - |

## S4 처리량 한계 — 성공 RPS (기준을 처음 넘은 단계의 직전 단계)

| 단계 | 셀 | n | 엄격 p99<500ms·에러<1% | 완화 p99<1s·에러<5% | 포화점 | 목표 미달 단계 수 | k6 미시작(dropped) | 비정상 회차 |
|---|---|---|---|---|---|---|---|---|
| L2 | S4 | 0 | 미측정 | 미측정 | 미측정 | 미측정 | 미측정 | rep1:s4-no-successful-stage,invalid-consistency.json, rep2:s4-no-successful-stage, rep3:s4-no-successful-stage, rep4:s4-no-successful-stage,invalid-consistency.json, rep5:s4-no-successful-stage,invalid-consistency.json |
| L4 | S4 | 0 | 미측정 | 미측정 | 미측정 | 미측정 | 미측정 | rep1:s4-no-successful-stage, rep2:s4-no-successful-stage, rep3:s4-no-successful-stage, rep4:s4-no-successful-stage, rep5:s4-no-successful-stage |
