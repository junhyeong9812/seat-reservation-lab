# ADR-012 효과 지표 — `20261021-adr012eff-71585d53` (--set adr012eff)

> `k6/ADR-009/scripts/tail.py`가 회차 effect.json(summarize.py effect — S3e DB 직접 주입 D·L1·L2·H + 앱 경로 주입 AH·AL·AE)에서 산출. 값만 낸다 — R4·R6 판정은 메인 몫. 종류 칸 = 주입/복구/끝 일치. R4 = 위반/쌍(보장 밖 쌍 포함 — 기준이 다르다: 증분 '봄'·보장 안 = 주기 + 10초 · 그 밖 = 적체 없으면 120초 · 있으면 2 × 바퀴). 적체는 주입 트랜잭션 시작 이전 마지막 안전망 실행의 기록(없으면 뒤 첫 실행 — 'no-prior-run' 쌍 수). 예상 담당 = 안전망의 어느 갈래가 잡을 쌍인가(recent·reread·full-cursor — 기록만, R4 기준 그대로). R6 = 위반/판정(증분 '봄'·보장 안 쌍의 첫 후보 출처 ≠ 그 셀 증분 source) · 판정 불가 = counter_app_inject 행 없음. 정의는 summarize.py 머리말 'ADR-012 추가'·inc_effect.

## 1. 조건별(ok 회차 합)

| 조건 | n(ok) | R4 위반 | R4 판정 불가 | 보장 밖 쌍 | R6 위반 | R6 판정 쌍 | R6 판정 불가 | 끝 주입 쌍 불일치 회차 | 오탐 복구 | unexpected 회차 |
|---|---|---|---|---|---|---|---|---|---|---|
| i-mem-1s-adr012eff | 3 | 0 | 0 | 0 | 0 | 180 | 0 | 0 | 0 | 0 |
| i-mem-5s-adr012eff | 3 | 0 | 0 | 0 | 0 | 180 | 0 | 0 | 0 | 0 |
| i-trg-1s-adr012eff | 3 | 0 | 0 | 0 | 0 | 300 | 0 | 0 | 0 | 0 |
| i-trg-5s-adr012eff | 3 | 0 | 0 | 0 | 0 | 300 | 0 | 0 | 0 | 0 |
| i-stmt-1s-adr012eff | 3 | 0 | 0 | 0 | 0 | 180 | 0 | 0 | 0 | 0 |
| i-stmt-5s-adr012eff | 3 | 0 | 0 | 0 | 0 | 180 | 0 | 0 | 0 | 0 |

## 2. 회차별

| 조건 | 회차 | 상태 | 종류(주입/복구/끝 일치) | 복구 출처 | R4 위반/쌍 | R4 판정 불가 | 보장 밖 | R4 기준별 쌍 | 적체 기준 no-prior-run 쌍 | 예상 담당별 쌍 | R4 위반 예상 담당 | 바퀴 최대 s | R6 위반/판정 | R6 판정 불가 | R6 보장 밖 제외 | 오탐 복구 · 주입 밖 끝 어긋남 | unexpected | 보장 밖 기록(kind 행) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| i-mem-1s-adr012eff | rep1 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-mem-1s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-mem-1s-adr012eff | rep2 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-mem-1s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-mem-1s-adr012eff | rep3 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-mem-1s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-mem-5s-adr012eff | rep1 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-mem-5s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-mem-5s-adr012eff | rep2 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-mem-5s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-mem-5s-adr012eff | rep3 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-mem-5s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-trg-1s-adr012eff | rep1 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-trg-1s, safety-app | 0/120 | 0 | 0 | inc 100; safety-recent 20 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/100 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-trg-1s-adr012eff | rep2 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-trg-1s, safety-app | 0/120 | 0 | 0 | inc 100; safety-recent 20 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/100 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-trg-1s-adr012eff | rep3 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-trg-1s, safety-app | 0/120 | 0 | 0 | inc 100; safety-recent 20 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/100 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-trg-5s-adr012eff | rep1 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-trg-5s, safety-app | 0/120 | 0 | 0 | inc 100; safety-recent 20 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/100 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-trg-5s-adr012eff | rep2 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-trg-5s, safety-app | 0/120 | 0 | 0 | inc 100; safety-recent 20 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/100 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-trg-5s-adr012eff | rep3 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-trg-5s, safety-app | 0/120 | 0 | 0 | inc 100; safety-recent 20 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/100 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-stmt-1s-adr012eff | rep1 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-stmt-1s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-stmt-1s-adr012eff | rep2 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-stmt-1s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-stmt-1s-adr012eff | rep3 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-stmt-1s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-stmt-5s-adr012eff | rep1 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-stmt-5s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-stmt-5s-adr012eff | rep2 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-stmt-5s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |
| i-stmt-5s-adr012eff | rep3 | ok | D 20/20/20 · L1 10/10/10 · L2 10/10/10 · H 20/20/20 · AH 30/30/30 · AL 30/30/30 · AE 0/0/0 | expiry-repair, inc-app-stmt-5s, safety-app | 0/120 | 0 | 0 | inc 60; safety-recent 60 | 0 | None 30; full-cursor 30; recent 60 | - | 0 | 0/60 | 0 | 0 | 0 · 0 | - | restart 1 |

## 누락·의심

- 자동 점검에서 걸린 항목 없음
