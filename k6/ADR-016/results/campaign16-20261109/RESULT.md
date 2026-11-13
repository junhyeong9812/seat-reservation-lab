# ADR-016 캠페인 결과

- 측정 전 고정: 예
- 결론: **pick** · 고른 후보 ext+c
- 조건: ⓒ 후보의 억울 0은 결제 쪽이 마감(payBy)을 강제할 때만 성립한다(설계-9.3 · M16-3) — 서버 증거가 아니다

| 후보 | 정합 | 모형 | 목표 | 경계 | 억울 상한 | 거부+실패 | G-비용 보고 |
|---|---|---|---|---|---|---|---|
| cond+c | True | True | False | True | 0.0907% | 11.6667% | — |
| cond+b+c | True | True | False | True | 0.0907% | 15.5455% | cost/tw_server_max |
| cond+b | True | True | False | — | 11.1835% | 5.0000% | cost/tw_server_max |
| ext+c | True | True | True | True | 0.0907% | 0.3636% | paid/rehold.never_ratio[ec:nc] |
| ext | True | True | True | — | 0.0907% | 0.3939% | paid/rehold.never_ratio[e:n] |

## 판정 기록

- cond+c: fail — 정합 True · 모형 True · 목표 False · 경계 True · 짝 cond 모형 True
- cond+b+c: fail — 정합 True · 모형 True · 목표 False · 경계 True · 짝 cond+b 모형 True
- cond+b: fail — 정합 True · 모형 True · 목표 False
- ext+c: pass — 정합 True · 모형 True · 목표 True · 경계 True · 짝 ext 모형 True
- G-비용 보고(ext+c): ① 대비 ≫ 지표 ['paid/rehold.never_ratio[ec:nc]']

## G-비용 메모

- p99.pay_start_200: cost ①(cond)은 payment-start를 부르지 않아 기준값이 없다 — 비교에서 뺀다
- 다른 사용자 비용 짝(경계 흐름 제외): ext = paid e 대 ① n, ext+c = paid ec 대 ① nc
- '판정 불가:<지표>' = 유효 3회 범위를 못 만든 지표 — 그 후보는 판정 불가로 다룬다
- G-정합은 회차 단위다 — paid 회차의 실패 자료는 같은 회차의 모든 흐름 후보(예: cond+c · cond+b)를 함께 False로 만든다(같은 서버 · 의도 · OQ-2)

## N16-1 근거(격리 탐침 + 세 겹 보조)

- 주: 격리 탐침(probe16 — payment-start만 흘린 구간의 쓰기 증분 0 · 양성 대조 seat_hold 삽입 = P)
- (a) ① 회차 seat_hold n_tup_upd 증분 0 · pgss UPDATE seat_hold 0
- (b) 허용 목록(plan16.ALLOWED_WRITES) 밖 쓰기 문장 0
- (c) payment-start 5xx 0 — ① 확인은 readOnly 트랜잭션이라 쓰기 시도는 오류(5xx)로 드러난다 · pgss만으로는 문장의 경로(payment-start)를 가를 수 없다

## 모형(CAP 10 · 30분 — 관측 δ̂ · 설명용)

| 정책 | CAP | 억울 | 결제 전 거부 | 결제 실패 | 홀드 수명 p99 |
|---|---|---|---|---|---|
| cond | cap10 | 11.3670% | 0.0000% | 0.2630% | 300초 |
| cond+c | cap10 | 0.0000% | 0.0000% | 11.8270% | 300초 |
| cond+b | cap10 | 9.8870% | 4.3600% | 0.2460% | 300초 |
| cond+b+c | cap10 | 0.0000% | 4.3600% | 10.3790% | 300초 |
| ext | cap10 | 0.0000% | 0.0000% | 0.2630% | 547초 |
| ext+c | cap10 | 0.0000% | 0.0000% | 0.2630% | 547초 |
| cond | cap30 | 11.6250% | 0.0000% | 0.0050% | 300초 |
| cond+c | cap30 | 0.0000% | 0.0000% | 11.8270% | 300초 |
| cond+b | cap30 | 10.1280% | 4.3600% | 0.0050% | 300초 |
| cond+b+c | cap30 | 0.0000% | 4.3600% | 10.3790% | 300초 |
| ext | cap30 | 0.2420% | 0.0000% | 0.0050% | 547초 |
| ext+c | cap30 | 0.0000% | 0.0000% | 0.2480% | 547초 |

유효 회차: {'paid/cond': 3, 'paid/ext': 3, 'race/cond': 3, 'race/ext': 3, 'cost/cond': 3, 'cost/cond+b': 3, 'cost/ext': 3}
