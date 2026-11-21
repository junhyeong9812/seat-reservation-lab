# k6/ADR-018 하네스 계약 (회차 폴더 · 세션 로그 · 덤프 · 분류 · 판정)

> 이 문서가 ADR-018 2단계 하네스의 **연결 계약**이다. 측정 명세는 `docs/plans/2026-11-20/adr-018-measurement/requirement-spec.md`이고, 하네스 설계는 같은 폴더의 `harness-design.md`, 앱 지표 계약은 `test-contract.md`다.
> - 쓰는 쪽: `scenarios/flow18.js`(세션 로그) · `scripts/run18.sh`(회차 폴더 · 덤프 · PG 리셋 · 배수).
> - 읽는 쪽: `scripts/outcome18.py`(세션 분류) · `scripts/summarize18.py`(회차 무효 · 정합 판정 · 캠페인 표) · `scripts/selftest18.py`.
> - 구현은 이 문서를 따른다. 구현과 이 문서가 다르면 구현의 결함이다(바꾸려면 이 문서를 먼저 고친다).
> - 숫자(비율 · 겹침 단계 · 경계 셀 눈금 · 상한)의 출처는 `scripts/plan18.py` 한 곳이다. **측정 전 고정 값이다(`FIXED = True` — 2026-11-20 · .164 스모크 smoke18-164a 뒤 · 사용자 승인 · §11).**
> - 구조 · 무효 규칙 · 회차 격리 · 시각 규약 · PC 링크 검사 · 표본기는 ADR-017 하네스(`k6/ADR-017/INTERFACE.md`)를 그대로 따른다. 이 문서는 **바뀌는 것**을 적고, 같은 것은 "ADR-017 §n과 같다"로 가리킨다.

## 0. 용어 · 단위

- **방식**(`method`):
  - `B0` = 1석 선점 API를 두 번 부른다(좌석 id 오름차순). 둘째가 실패해도 첫 석을 놓지 않는다(TTL로 만료 — 지금 방식).
  - `B1` = B0과 같다. 다만 둘째가 실패하면 `DELETE /api/holds/{holdId}`로 첫 석을 바로 놓는다.
  - `split` · `one-shot` = 묶음 선점 API 한 번(`seat.hold-group.enabled=true` · `seat.hold-group.mode`).
  - B0 · B1 회차는 `seat.hold-group.enabled=false`(앱 기본값)로 띄운다. 1석 해제 API는 이 키와 무관하게 열린다.
- **셀**(`cell`): 본 셀 = 겹침 `low` · `mid` · `high`, 경계 셀 = `edge`(split · one-shot만 · 겹침은 low의 H · 세션 1,000 · 만료 배치 200ms), 동시 경합 셀 = `burst`(split · one-shot만 · §3.4 — 재합의 2026-11-20 · 하네스 설계 §8).
- **회차**: (방식, 셀, rep 1~3) 하나. 본 셀 36 + 경계 셀 6 + 동시 경합 셀 6 = 48회차. 순서 = `plan18.py --order`(열 = 순번 · 회차 · 셀 · 방식).
- **세션 종류**(`kind`): 본 셀 `ok`(확정까지) · `abandon`(2석 선점 뒤 아무것도 안 함) · `release`(2석 선점 뒤 해제). 경계 셀 `edge_confirm` · `edge_ps`(§3.3).
- **좌석 쌍**: 세션마다 `(s, s+1)`이고 **두 석은 늘 같은 줄**이다(한 줄 50석 — `LoadtestDataService.SEATS_PER_ROW` · 리뷰 H1-6). H = 시작 위치 수(인기 구역 크기 — 겹침 단계마다 다름). 시작 위치 번호 i = ⌊U × H⌋ → 줄 r = ⌊i ÷ 49⌋ · 칸 c = i mod 49 · s = r × 50 + c + 1(`plan18.seat_of` = lib18 `seatsOf18`). 한 석씩 어긋난 쌍이 부분 겹침을 만든다.
- **uid**: 결제 시작이 발급한 `paymentUid`. B0 · B1은 좌석마다 하나(1석 결제 시작 2번), 묶음은 묶음마다 하나다.
- **시각**: ADR-017 §0과 같다(서버 시계 epoch ms · k6 행 = PC 시각 + `CLOCK_OFFSET_MS` · DB 덤프 = `extract(epoch …) × 1000` · PG 원장 = ISO).
- **회차 폴더**: `<results-root>/<campaign id>/<method>/<cell>/rep<k>/`. 기본 results-root는 `k6/ADR-018/results`다. 재측정(`--attempt n`)은 앞 시도를 `rep<k>.attempt<n−1>`로 옮긴다.

## 1. 회차 폴더 파일 목록

필수(✔) 파일이 없거나 깨지면 회차 무효다(§7). 표에 없는 파일(표본기 · 로그 · 시드 기록 등)은 run17이 남기던 것을 그대로 남기고, 요약기는 읽지 않는다.

| 파일 | 쓰는 곳 · 시점 | 형식 | 필수 | 읽는 곳 |
|---|---|---|---|---|
| `plan.json` | run18 시작 | `plan18.py --render` 출력 그대로(§2) | ✔ | outcome · summarize |
| `meta.json` | run18 끝 | ADR-017 §6.9와 같다 — `cand` · `kill_*` 대신 `method` · `cell` | ✔(캠페인) | summarize --campaign |
| `status` | run18 끝 | 한 줄: `ok` 또는 `invalid:<사유>,<사유>…` | ✔ | campaign18 · summarize --campaign |
| `harness-reasons.txt` | run18 내내 | 줄마다 하네스 사유 하나(없으면 빈 파일) | ✔ | summarize |
| `sessions18.log.gz` | k6 `--console-output` → gzip | §3 — 줄마다 k6 JSON 로그, `msg` = 세션 행 JSON | ✔ | outcome · summarize |
| `k6-summary.json` | k6 handleSummary | k6 요약 원본 + `adr018` 머리(§6.8) | ✔ | summarize |
| `k6-requests.csv.gz` | k6 `--out csv` | k6 CSV(요청 지연 · 빠진 반복) | ✔ | summarize |
| `pc-link.json` · `timeline-client.jsonl` · `k6-window.json` · `clock-offset.json` | ADR-017과 같다 | ADR-017 §1 · §6.11 | ✔ | summarize(환경) |
| `holds-sample.jsonl` | run18 k6 동안(`hold_sample_s` 주기) | §6.12 — 부하 중 홀드 표본 | ✔ | summarize(G1 · 재고 소진) |
| `app.log.gz` | run18 끝(`docker logs --since` 회차 시작) | §6.13 — 앱 로그 | ✔(경계 셀 — 없음 · 0바이트 · 깨짐 · 기동 머리 줄 `Starting SeatReservationApplication` 없음 = §7 `app-log-missing` 무효 · 다른 셀은 run18 하네스 사유) | summarize(경계 셀 5xx 원인) |
| `inject.json` | run18 PG 리셋 | ADR-017 §6.6 — 단 `faults` 본문은 늘 `{seed, rules: {}}`(PG 주입 없음 — 명세 Q15) | ✔ | summarize |
| `drain.json` | run18 배수 창 | §6.5 | ✔ | summarize |
| `pg-ledger.json` | 배수 뒤 `GET /ledger` | ADR-017 §6.1과 같다 | ✔ | outcome · summarize |
| `db-dump.json` | 배수 뒤(원장 덤프 직후) | §6.2 | ✔ | outcome · summarize |
| `consistency-k6.json` | k6 직후 `/internal/consistency` | §6.3 | ✔ | summarize |
| `consistency.json` | 배수 · 덤프 뒤 `/internal/consistency` | §6.3 | ✔ | summarize |
| `act18-before.json` · `act18-after-k6.json` · `act18-end.json` | k6 직전 · k6 직후 · 덤프 뒤 | §6.4 | ✔ | summarize |
| `app-config.json` | 시드 뒤 | §6.10 | ✔ | summarize(`plan18.py --config-check`) |
| `seed-check.json` | 시드 뒤 | lib15 `seed_check15` 출력 | ✔ | summarize |
| `summary18.json` | `summarize18.py --rep` | §9.2 | — | 캠페인 표 |

덤프 순서는 ADR-017 §1과 같다: **배수 끝 → `pg-ledger.json` → `db-dump.json` → (원장 재조회 대조) → `consistency.json` → `act18-end.json`**. k6 직후에는 `act18-after-k6.json` → `consistency-k6.json`을 뜬다.

## 2. plan.json (plan18 렌더) — 읽는 쪽이 쓰는 키

| 키 | 뜻 |
|---|---|
| `method` · `cell` · `rep` · `fixed` | 회차 식별 · FIXED |
| `overlap` · `scale` · `group` · `metric_mode` | 겹침 단계(경계 셀 = `low`) · `main` \| `edge` · 묶음 API 여부 · `seat.group.hold.rejected{mode}` 값(`split` \| `one_shot` · B = null) |
| `n_total` · `planned{kind: 수}` · `kind_cycle[...]` | 계획 세션 수 · 종류별 계획 수 · 교차 배정 바퀴(`kind = kind_cycle[sid mod len]`) |
| `params{tMin,tMax,payMedian,paySigma,payMax,gMin,gMax,dMin,dMax,eps,psCenter}` | 세션 속성 분포(초) — `plan18.session_attrs(rep, sid, params)`가 기록값을 다시 계산한다 |
| `seed{schedules:1, seats, H, rows, pad, section:"A"}` | 시드(`/internal/reset?schedules=1&seatsPerSchedule=seats`) · 좌석 = 줄 수 × 50 + pad(줄 수 = ⌈H ÷ 49⌉ · 동시 경합 셀 ⌈묶음 수 ÷ 12⌉) · 세션 좌석 = `plan18.seats_for(…)` |
| `order{seq, rep_cells[]}` | 캠페인 순서의 순번 · 그 회차의 셀 묶음 순서(겹침 단계를 회차마다 회전 — 리뷰 H1-5). summary18 `order`로도 남는다 |
| `hold_sample_s` | 부하 중 홀드 표본 주기(본 · 동시 경합 2초 · 경계 1초) |
| `app{ttl_s, batch_s, W_s, theta_s, M_s}` · `app_env` | 앱 눈금(본 셀 = 앱 기본값 · 경계 셀 = 짧게) · compose 환경(run18이 그대로 넘긴다) |
| `k6.env` | k6 환경(run18이 그대로 넘긴다) · `USER_BASE` = 세션 사용자 대역(사용자 = `USER_BASE + sid`) |
| `pg.faults` | `/admin/faults` 본문 `{seed, rules: {}}` |
| `send_lag_max_ms` · `consistency_grace_s` | 예정 송신 허용 지연(§7 env-send-lag) · `/internal/consistency?graceSeconds` |
| `drain{cap_s, poll_s, est_s}` | 배수 창 상한 · 조회 간격 · dry-run 예상 |
| `values` | 고정 자리 값 전부(`p99_limit_ms` · `kind_weights` · `h_factor` · `app` · `amount` …) — 캠페인 회차 사이 대조(§8.3)의 대상 |

## 3. 세션 로그 `sessions18.log.gz`

- 줄 형식 · 깨진 줄 규칙은 ADR-017 §3과 같다(`json.loads(json.loads(line)["msg"])` · `k != "s"` · 비 JSON · `level:"error"`는 깨진 줄 · 맨 끝 찢긴 한 줄은 세지 않는다 — `outcome17.read_sessions`를 그대로 쓴다).
- 세션 하나 = 행 하나다. 세션은 단계가 실패하면 다시 보내지 않고 그 단계에서 끝난다.

### 3.1 세션 행 필드 (`v: 1`)

| 필드 | 형 | 뜻 |
|---|---|---|
| `k` · `v` | `"s"` · 1 | 행 종류 · 판 |
| `sid` | int | 세션 번호(0부터 · `iterationInTest`) |
| `kind` · `rep` · `method` · `cell` | str · int · str · str | 종류(`kind_cycle[sid mod len]`) · 회차 식별(plan과 같아야 한다) |
| `user` | int | `USER_BASE + sid` |
| `seats` | [int, int] | `plan18.seats_for(cell, rep, n_total, sid, U, H)` — 본 · 경계 셀 `(s, s+1)` = `seat_of(U, H)` · 동시 경합 셀 §3.4 |
| `burst` | int | (동시 경합 셀만) 묶음 번호 = `plan18.burst_of(rep, n_total, sid)[0]` |
| `T` · `P` · `G` · `D` · `U` · `X` | float | 시드 속성(`plan18.session_attrs` 값과 \|차\| ≤ 1e-6) — 종류와 무관하게 늘 기록 |
| `hold` | [obj] | 선점 응답(B = 1석 1~2개 · 묶음 = 1개) |
| `rel` | [obj] | 해제 응답(B1의 첫 석 해제 · release 세션) |
| `ps` | [obj] | 결제 시작 응답(B = 1석 1~2개 · 묶음 = 1개) |
| `pay` | [obj] | PG pay 응답(uid마다) |
| `cf` | [obj] | 확정 응답(B = 1석 2개 · 묶음 = 1개) |
| `got` | str | 선점 결과: `both`(2석) · `first`(B — 첫 석만) · `none`(아무것도 없음) |
| `end` | str | 세션이 끝난 자리: `hold` · `partial` · `abandon` · `release` · `pay_start` · `pg` · `done` |

응답 객체 공통: `{op, st, code, ec, ts, tr}`.

- `st` = HTTP 상태(0 = 연결 실패 · 타임아웃) · `code` = 2xx면 `"OK"`, 그 밖은 본문 `code`(없으면 `"NOCODE"`) · `ec` = k6 `error_code` · `ts` · `tr` = 송신 직전 · 수신 직후 서버 ms.
- `op`와 k6 `name` 태그는 같은 값이다: `hold` · `hold_group` · `release` · `release_group` · `pay_start` · `pay_start_group` · `pg_pay` · `confirm` · `confirm_group`.

op별 필드 · `ok` 조건:

| op | 더하는 필드 | `ok` |
|---|---|---|
| `hold` | `seat` · `rj`(헤더 `X-Seatlab-Reject` · 없으면 null) · `hid` · `exp` · `at`(첫 선점만 — 예정 송신 = 스크립트 시작 + sid ÷ 도착률 · 리뷰 H3-5) | `st == 201` · hid 있음 · exp 유한 · 본문 seatId = seat |
| `hold_group` | `seats` · `rj` · `gid` · `hids[]` · `exp` · `cn`(`res.timings.connecting` ms) · `bl`(`blocked` ms) · `at`(동시 경합 셀만) | `st == 201` · gid · exp 있음 · 본문 holds의 seatId 열 = seats · holdId 모두 있음 |
| `release` · `release_group` | `id`(holdId · groupId) | `st == 204` |
| `pay_start` · `pay_start_group` | `id` · `at`(예정 송신 — edge_ps만 · 그 밖 null) · `exp` · `pb` · `pws` · `ext` · `uid` | `st == 200` · uid · exp · pb 유한 · pws 숫자 · ext 불리언 · 본문 holdId(groupId) = id |
| `pg_pay` | `uid` · `state` | `st == 200` · state `CAPTURED` |
| `confirm` · `confirm_group` | `id` · `uid` · `at`(그 세션의 첫 확정만 — 그 밖 null) · `rids[]` · `rseats[]` · `cp{type, status}` | `st == 200` · rids 모두 있음 · rseats = 그 확정의 좌석(B = [s] 또는 [s+1] · 묶음 = [s, s+1]) · (묶음) 본문 status `CONFIRMED` |

### 3.2 `got` · `end`와 목록의 관계 (행 정합 — 어기면 `invalid:row-bad`)

- 모든 셀에서 첫 선점 응답 객체의 `at`(예정 송신)은 유한한 수여야 한다 — 없으면 하네스 기록 결손으로 `invalid:row-bad`(세션 무효 · 리뷰 H3-R5).

`n_ids` = B 2 · 묶음 1. `want_ps` = `ok` 세션 `n_ids` · 경계 세션 1.

| got · end | 있어야 함 | 없어야 함 |
|---|---|---|
| `none` · `hold` | hold 1개(ok 거짓) | rel · ps · pay · cf |
| `first` · `partial` | B만 · hold 2개(첫째 ok · 둘째 ok 거짓) · B1 = rel 1개(op `release` · id = 첫 hid) | B0의 rel · ps · pay · cf |
| `both` · `abandon` | kind abandon · hold n_ids개 모두 ok | rel · ps · pay · cf |
| `both` · `release` | kind release · rel n_ids개(B `release` · 묶음 `release_group`) | ps · pay · cf |
| `both` · `pay_start` | kind ∈ ok · edge_* · ps 1~want_ps개(마지막만 ok 거짓) | rel · pay · cf |
| `both` · `pg` | ps want_ps개 모두 ok · pay 1~ps 수(마지막만 ok 거짓) | rel · cf |
| `both` · `done` | ps want_ps개 · pay = ps 수(모두 ok) · cf = ps 수 | rel |

### 3.3 흐름 (flow18.js — 하네스 설계 §1 · §3 · §4)

- 선점: B = 1석 s → (201이면) 1석 s+1. 둘째 실패 → B0 끝(첫 석 유지) · B1 첫 석 해제 뒤 끝. 묶음 = `seatIds [s, s+1]` 한 번.
- `ok`: 마지막 선점 응답 + T에 결제 시작(B는 hid마다 차례로 · 하나라도 실패하면 끝) → 마지막 결제 시작 응답 + P에 PG pay(uid마다) → 마지막 PG 응답 + G에 확정(B는 hid마다 · 둘 다 보낸다).
- `release`: 마지막 선점 응답 + D에 해제(B = 1석 해제 2번 · 묶음 = 묶음 해제 1번).
- `edge_confirm`: 곧바로 결제 시작 → 곧바로 PG pay → `E + X`에 확정(E = 결제 시작 응답 expiresAt).
- `edge_ps`: `E + psCenter + X`에 결제 시작(E = 선점 응답 expiresAt) → 200이면 곧바로 PG pay → G 뒤 확정.

### 3.4 동시 경합 셀(`burst` — 하네스 설계 §8)

- **묶음**: 세션 번호를 앞에서부터 묶음으로 나눈다. 묶음 k의 크기 = `BURST_SIZE` 범위의 정수(씨앗 `adr018-burst|회차|k` · `plan18.bursts` = lib18 `bursts18`). 마지막 묶음은 세션 수에서 자른다. 평균 도착률은 본 셀과 같다(회차 1,100세션 · 20세션/초).
- **좌석**: 한 줄(50석)에 4석 칸 12개(48석 · 줄 끝 2석은 비움). 묶음 k의 칸 시작 b = ⌊k ÷ 12⌋ × 50 + (k mod 12) × 4 + 1. 세션 좌석 = `(b + o, b + o + 1)` · o = 0(U < 0.5) | 1 — 늘 같은 줄. 그래서 한 묶음의 어느 두 세션이든 b + 1을 함께 노리고(같은 쌍 또는 한 석 어긋난 쌍), 묶음끼리는 좌석이 겹치지 않는다(경합은 묶음 안에서만). 좌석 수 = ⌈묶음 수 ÷ 12⌉ × 50 + 50.
- **같은 순간**: 묶음의 세션은 각자 다른 VU(고정 도착률 반복)에서 돈다. 각 세션은 `exec.scenario.startTime`(스크립트 시작 · 모든 VU 공통 PC ms) + (묶음 마지막 세션 번호 ÷ 도착률 + `BURST_LEAD_S`)초까지 잔 뒤 묶음 선점을 보낸다. 마지막 세션의 반복은 그 시각보다 `BURST_LEAD_S` 앞에 시작한다. `http.batch`는 쓰지 않는다 — 세션마다 그 뒤 흐름(T · P · G 잠)이 달라 한 VU가 묶음을 쥘 수 없다.
- **연결 미리 맺기**(리뷰 H1-8): 잠들기 전에 같은 반복에서 `GET /actuator/metrics/process.uptime`(DB를 쓰지 않는다 — `/actuator/health`는 DB 상태 검사를 타고 liveness 그룹은 probes를 켜지 않아 없다 · 메인 결정 2026-11-20 · k6 name `preconnect` — 지연 · 지표 대조 대상 아님)를 보내 연결을 맺고, 선점은 그 연결을 다시 쓴다(연결 맺기가 송신 폭에 섞이지 않게). 행에 `pre{st, ts, tr, cn}`, 선점 응답에 `cn` · `bl`을 남긴다. 선점의 `cn`이 0이 아니면(새 연결) 그 묶음은 폭 판정에서 '넘음'으로 센다(§7 `burst-spread`).
- 선점 응답 객체에 `at`(묶음 예정 송신 · 서버 ms)을 남긴다. 그 뒤 흐름 · 종류 비율은 본 셀과 같다.
- 연결: 50초 이상 쉬기 전 요청은 `Connection: close`(lib14 `closeBeforeLongThink`) · 세션의 마지막 해제 · 확정 · PG 요청은 close · `noVUConnectionReuse`.

## 4. uid 끝 상태 (배수 뒤)

`outcome18.end_states(db_dump, ledger, amount)`가 **DB intent 또는 PG 원장에 나오는 모든 uid**마다 아래 객체를 만든다.

```
End = {uid, intent_state|None, user_id, group_id|None, hold_id, seat_count|None, res: [{id, seat_id, status, origin}],   # reservation.intent_id = uid
       pg_state|None, refunds:int, captured:bool, amount|None, tombstone:bool, close: Task|None, capture: Task|None,
       rejection: {code, http_status, rejected_ms}|None, shape: str}
```

`shape` — 위에서부터 처음 맞는 줄. `conf` = res 중 CONFIRMED 수 · `sc` = intent `seat_count`(intent 없으면 1) · `amount` = plan `values.amount`.

| # | 조건 | shape |
|---|---|---|
| 1 | conf > sc | `double_reservation` |
| 2 | refunds ≥ 2 | `double_refund` |
| 3 | pg_state = REFUNDED 이고 conf ≥ 1 | `refunded_with_seat` |
| 4 | 0 < conf < sc | `partial_reservation`(묶음의 일부 좌석만 예약) |
| 5 | intent · 원장 있음 · tombstone 아님 · 원장 amount ≠ sc × amount | `amount_mismatch`(묶음 등록 금액 — ADR-018 설계-4) |
| 6 | conf = sc 이고 pg_state = CAPTURED | `seat_paid` |
| 7 | conf = sc (그 밖 pg_state) | `seat_moneyless` |
| 8 | pg_state = REFUNDED | `refunded` |
| 9 | pg_state = CLOSED 이고 captured 거짓 | `closed_unpaid` |
| 10 | pg_state = CAPTURED, 또는 (CLOSED 이고 captured 참 이고 intent 있음) | `orphan`(돈 받고 좌석 없음 · 환불 없음) |
| 11 | pg_state = REGISTERED | `open_unpaid` |
| 12 | intent 있음 · 원장 없음 | `no_ledger` |
| 13 | 그 밖 전부(refund 모드에서 생기지 않는 AUTHORIZED · VOIDED · EXPIRED · 알 수 없는 값 · intent 없는 CLOSED + 결제 흔적) | `no_intent` |

- 기대 0(G1 — §8.1): `double_reservation` · `double_refund` · `refunded_with_seat` · `partial_reservation` · `amount_mismatch` · `seat_moneyless` · `orphan` · `open_unpaid` · `no_ledger` · `no_intent`.

## 5. 세션 분류 결정표 `outcome18.classify(row, ends, plan) → str`

반환값은 범주 하나 또는 결함 문자열이다.

- 범주: `success` · `released` · `abandoned` · `lost` · `partial_kept` · `partial_released` · `ps_rejected` · `pg_rejected` · `confirm_rejected` · `confirm_mixed` · `edge_5xx`
- `invalid:<사유>`: 하네스 무효 — 회차 무효(`invalid:conn-*`는 §7 `k6-conn-error`, 그 밖은 `session-invalid`).
- `fail:<사유>`: 본 셀 5xx — G1 실패(M18-1 '5xx 0').
- `unexpected:<op>-<st>-<code>`: 계약 밖 응답(예: 해제 409 · 결제 시작 403) — G1 실패(보고 대상).
- `integrity:<사유>`: 응답과 끝 상태의 어긋남 — G1 실패.

`ends`는 uid → §4 객체다. 판정 순서 — 위에서부터 처음 맞는 줄:

**A. 행 정합**: §3.2를 어기거나, 필드가 빠졌거나, kind ≠ `kind_cycle[sid mod len]`이거나, rep · method · cell이 plan과 다르면 → `invalid:row-bad`.

**A2. 연결 미리 맺기**(동시 경합 셀 · 리뷰 H2-7): 행의 `pre.st == 0` → `invalid:conn-preconnect`(§7 `k6-conn-error` — 환경) · `pre.st ≠ 200` → `invalid:preconnect`(§7 `session-invalid`).

**B. 연결 오류**(M18-3): 응답 객체 중 하나라도 `st == 0` → `invalid:conn-<op>`(첫째). 요청 타임아웃(k6 `error_code` 1050 — 상태 0)도 여기에 든다(메인 결정 2026-11-20 — 명세 M18-3 그대로 무효).

**C. 앱 5xx**: 앱 요청(op ≠ `pg_pay`) 중 하나라도 `st ≥ 500` → 경계 셀 `edge_5xx`(측정값 — 원인은 §6.13 앱 로그 연결 · 40P01이 아니거나 연결할 수 없으면 G1 실패) · 본 · 동시 경합 셀 `fail:5xx-<op>`.

**D. 선점**(REJ_HOLD = (409, SEAT_NOT_AVAILABLE)):

| got | 응답 | 결과 |
|---|---|---|
| `none` | 마지막 선점 = REJ_HOLD | `lost` |
| `none` · `first` | 실패한 선점 = (409, HOLD_LIMIT_EXCEEDED) | `integrity:hold-limit`(사용자마다 2석 이하 — 한도 초과는 카운터 어긋남) |
| | 실패한 선점이 201(본문 계약 위반 — 묶음 좌석 열 ≠ [s, s+1] · hold 수 ≠ 2(부분 선점) · holdId 결측 · 1석 seatId ≠ 요청) | `integrity:hold-body`(앱 결함 = G1 · 리뷰 H2-5) |
| | 그 밖 | `unexpected:<op>-<st>-<code>` |
| `first` | 둘째 = REJ_HOLD · B0 | `partial_kept` |
| | 둘째 = REJ_HOLD · B1 · 해제 204 | `partial_released` |
| | 둘째 = REJ_HOLD · B1 · 해제 그 밖 | `unexpected:release-<st>-<code>` |

**E. 2석을 다 잡은 세션**(end별):

| end | 응답 조건 | 끝 상태 조건 | 결과 |
|---|---|---|---|
| `abandon` | — | — | `abandoned` |
| `release` | 해제 모두 204 | — | `released` |
| | 그 밖(첫째 실패) | — | `unexpected:<op>-<st>-<code>` |
| `pay_start` | 마지막 결제 시작 ∈ {(409, HOLD_EXPIRED), (404, HOLD_NOT_FOUND)} | 앞서 성공한 결제 시작의 uid(B의 첫째)가 모두 `closed_unpaid` | `ps_rejected` |
| | 위와 같음 | 그 밖 | `integrity:ps-left-<shape>` |
| | 200(본문 계약 위반) | — | `integrity:ps-body`(앱 결함 = G1 · 리뷰 H2-5) |
| | 그 밖 | — | `unexpected:<op>-<st>-<code>` |
| `pg` | 마지막 PG = (409, DEADLINE_PASSED) | 결제 시작 uid가 모두 `closed_unpaid` 또는 `refunded` | `pg_rejected` |
| | 위와 같음 | 그 밖 | `integrity:pg-<shape>` |
| | 그 밖(PG 0 · 5xx · 다른 409 — 사용자 결제엔 주입이 없다) | — | `invalid:pg-<st>-<code>` |
| `done` | 아래 F | | |

**F. `done`의 확정 응답**(cf마다 · REJ = {(404, HOLD_NOT_FOUND), (409, HOLD_EXPIRED), (409, SEAT_NOT_HELD)}):

| 응답 | 끝 상태(그 uid) | 결과 |
|---|---|---|
| ok | shape ≠ `seat_paid` | `integrity:200-<shape>` |
| ok | (rids, rseats) 짝 ≠ 그 uid의 CONFIRMED 예약 (id, seat_id) | `integrity:200-mismatch` |
| ok | 그 밖 | 성공 1 |
| REJ | shape ≠ `refunded` | `integrity:reject-<shape>` |
| REJ | `refunded` | 거부 1 |
| 200인데 ok 거짓 | — | `integrity:confirm-body` |
| 그 밖 | — | `unexpected:<op>-<st>-<code>` |

- 모두 성공 → `success` · 모두 거부 → `confirm_rejected` · 섞임(B만 — 한 석만 산 사용자) → `confirm_mixed`.
- 기대 갈래(본 셀): ok → `success` · abandon → `abandoned` · release → `released` · 선점 실패 → `lost` · B0 첫 석만 → `partial_kept` · B1 첫 석만 → `partial_released`.
- 기대 갈래(경계 셀): edge_confirm → `success` · `confirm_rejected` · `edge_5xx` · edge_ps → E 앞 결제 시작(θ = 0이라 연장 뒤 확정) `success` · `confirm_rejected` · E 뒤 `ps_rejected` · `edge_5xx`.

`metric_outcome(o)`(§7 지표 대조): 2xx = `ok` · 본문 code(`OK` · `NOCODE`가 아님) = 그 code · 그 밖 `status_<st>` · 상태 0 = 세지 않음. 묶음 선점의 본문 결함은 컨트롤러가 본문을 직접 읽어 `INVALID_SEAT_SELECTION`(400 · 본문 code)으로 답하므로 outcome도 `INVALID_SEAT_SELECTION`이다(`status_400` 아님). 1석 해제(`release`)는 묶음을 꺼도 `release_hold`로 세므로 B0 · B1 회차도 이 지표와 대조한다. op → `seat.group.request{op}`: `hold_group → hold` · `release_group → release_group` · `release → release_hold` · `pay_start_group → pay_start` · `confirm_group → confirm`(1석 선점 · 결제 시작 · 확정과 PG는 이 지표에 없다).

## 6. 덤프 · 기록 형식

### 6.1 `pg-ledger.json`

ADR-017 §6.1과 같다(`{payments: [{uid, userId, amount, state, capturedAt, closedAt, refunds, tombstone, …}], ops: {op: 수}}`).

### 6.2 `db-dump.json` — 한 문장(같은 스냅숏 · `scripts/dump18.sql`)

ADR-017 §6.2의 네 표에 다음을 더한다.

- `payment_intent`에 `group_id` · `seat_count`.
- `hold_group`: `[{id, schedule_id, user_id, seat_count, created_ms}]`.
- `seat_hold`: `[{id, seat_id, schedule_id, user_id, group_id, expires_ms}]`(배수 뒤 남은 행 — 정상이면 빈 목록).

여섯 키 중 하나라도 목록이 아니면 §7 `db-dump-missing`.

### 6.3 `consistency-k6.json` · `consistency.json` — `GET /internal/consistency?graceSeconds=<plan consistency_grace_s>` 응답 그대로

- 필수 키(둘 다 · 숫자 — 없거나 숫자가 아니면 §7 `consistency-missing`): ADR-017 §6.3의 `v_*` 10개 · `pay_*` 기대 0 7개(`pay_multi_reservation_intents`는 V16 정의 — 결제 의도당 예약 수 > seat_count) · 묶음 3개 `group_partial_holds` · `group_expiry_split` · `group_partial_reservations`.
- `pay_compensation_mode`(배수 뒤)는 `values.compensation_mode`(`refund`)와 같아야 한다(§7 `consistency-mode-mismatch`).
- k6 직후 스냅숏은 이탈 · 첫 석만 잡은 홀드가 아직 남을 수 있는 시점의 부분 선점 · 만료 갈림을 본다(배수 뒤에는 홀드가 0이라 `group_partial_holds`가 자명하게 0이 된다).

### 6.4 `act18-<점>.json` — 앱 지표 스냅숏(한 줄 JSON · `scripts/actsnap18.py`)

```
{"t", "t_end", "base", "process_start_s": float|null,
 "group_request": {"<op>:<outcome>": n}, "group_rejected": {"<mode>:<cause>": n}, "hold_rejected": {"<cause>": n},
 "deadlock": {"<path>": n}, "expiry_tick": {"<outcome>": n},
 "compensation": {"done": {"<type>:<result>": n}, "retry": {"<type>": n}, "dead": {"<type>": n}, "unresolved": n, "record_failed": n, "sweep_failed": n},
 "failed_names": [..]?}
```

- 지표 이름 · 태그: `seat.group.request{op,outcome}` · `seat.group.hold.rejected{mode,cause}` · `seat.db.deadlock{path}`(지표 계약 §1) · `seat.hold.rejected{cause}`(ADR-014) · `seat.hold.expiry.tick{outcome}` · `seat.compensation.*` · `process.start.time`.
- 값 규칙은 ADR-017 §6.4와 같다(이름이 없으면 0 · 조회 실패 null · 0으로 채우지 않는다 · 실패한 이름은 `failed_names`).
- **필수**(셋 파일 모두): 스칼라 `process_start_s` · `compensation.unresolved` · `.record_failed` · `.sweep_failed`는 숫자, 사전 `group_request` · `group_rejected` · `hold_rejected` · `deadlock` · `expiry_tick` · `compensation.done` · `.retry` · `.dead`는 사전이고 값이 전부 숫자. 어기거나 `error` · `failed_names`가 있으면 §7 `act-missing`.
- 재기동 탐지: `process_start_s`가 before ≠ end면 §7 `app-restarted`(kill 셀이 없다 — 모든 셀).
- 증분 = end − before(키별 · 0인 키는 뺀다). before는 예열 뒤 기준점이라 0이 아니어도 된다.

### 6.5 `drain.json` — 배수 창

ADR-017 §6.5와 같다. 다만:

- 조회에 `holds`(남은 `seat_hold` 행 수)를 더한다: `polls: [{t_ms, pending, open, open_unheld, holds}]`.
- **empty** = `pending = 0` · `open = 0` · `holds = 0`인 조회가 연달아 둘이고, 그 사이 ≥ 안전망 + 워커 주기(5초 + 1초).
- **cap**(상한 `drain.cap_s` — 본 · 동시 경합 셀 240초 · 경계 셀 120초)은 무효가 아니다. 남은 일은 G1(`open_after_drain` · `unfinished_tasks`)에 들어간다. 성공한 조회가 0인 cap만 `drain-poll-failed`.
- 덤프 반복(`dump: {attempts, max, stable}` · `dump-race` · `dump-unstable` · `ledger-recheck-failed`)은 ADR-017과 같다.

### 6.6 `inject.json`

ADR-017 §6.6과 같다. 순서 `reset` → `faults`(본문 = plan `pg.faults` = `{seed, rules: {}}`)가 k6 시작 전 마지막이어야 한다. 마지막 faults 응답의 `seed`가 plan과 다르거나 rc ≠ 0이면 `inject-mismatch`, `rules`가 비어 있지 않으면 `inject-leak`, k6 시작 뒤 reset이 있으면 `pg-reset-during-run`.

### 6.7 (없음 — kill 셀이 없다)

### 6.8 `k6-summary.json`의 `adr018` 머리

`{rep, method, cell, rate, d_arr_s, plan_n, cycle, params, h, pg_url}`. **대조하는 키**(하나라도 다르면 · 파일 · 머리 없음도 `k6-plan-mismatch`): `str(rep) == str(plan.rep)` · `method` · `cell` · `plan_n == plan.n_total` · `cycle == plan.kind_cycle` · `h == plan.seed.H`. 나머지는 기록만.

### 6.9 `meta.json`

ADR-017 §6.9와 같다(`sha` · `harness_hash` = hhash18 · `app_image_id` · `mockpg_image_id` · `local` · `smoke` …). `cand` · `cell` · `kill_planned_at_s` 대신 `method` · `cell`. `summarize18 --rep`은 읽지 않는다. `--campaign`은 사용 회차마다 `sha` · `harness_hash`를 읽는다(§8.3).

### 6.10 `app-config.json`

ADR-017 §6.10과 같은 모양이다. configprops 접두어에 **`seat.hold-group`을 더한다**(lib18 `app_config18`).

- 판정 = `plan18.py --config-check <method> <cell> app-config.json`(exit 0 일치 · 1 불일치 · 2 판정 불가).
- 기대값(`plan18.expected_config(method, cell)`): `seat.confirm`(conditional-extend · W · θ · M · 지연 0) · `seat.hold`(sweep · TTL · 배치 · pessimistic-nowait · counter-upsert-precheck · `maxPerUser` 2) · `seat.hold-group`(`enabled` = 묶음 방식이면 참 · `mode` = SPLIT \| ONE_SHOT — B는 거짓 · SPLIT) · `seat.compensation`(REFUND · 워커 · 안전망 기본값) · `seat.payment` · `spring.flyway` · `server`, `hikaricp.connections.max[0] == 10`, `app_nofile` ≥ 21000. 경계 셀은 TTL · 배치 · W · θ · M이 `plan18.APP["edge"]`다.
- 사유: `seat.hold-group.*` 불일치 = `hold-group-mismatch` · 그 밖 = `config-mismatch` · 파일 없음 · 깨짐 = `app-config-missing`.

### 6.12 `holds-sample.jsonl` — 부하 중 홀드 표본(리뷰 H1-1)

- run18이 k6 시작부터 k6 끝까지 `plan.hold_sample_s` 주기로 가벼운 SQL 한 문장(`lib18.sh hold_sample18` — 같은 스냅숏)을 뜬다. 부하 중 무거운 정합 검사(`/internal/consistency`)는 쓰지 않는다(ADR-001 실측).
- 줄: `{t_ms(DB statement_timestamp), holds, group_holds, groups_live, partial, split, available, free_pairs, q_ms(쿼리 시간), pc_t0_ms, pc_t1_ms}` · 실패 줄은 `{pc_t0_ms, pc_t1_ms, err}`.
  - `partial` = 남은 묶음 홀드 수 ≠ `hold_group.seat_count`인 묶음 수(부분 선점) · `split` = 남은 홀드의 `expires_at`이 둘 이상인 묶음 수 · `available` = AVAILABLE 좌석 수 · `free_pairs` = 노리는 범위 안에서 더 잡을 수 있는 쌍 수(같은 줄 이웃 두 석이 모두 AVAILABLE · 쌍 시작 좌석 ≤ plan `seed.pair_max_start` · `seed.pair_kind` = burst면 4석 칸의 첫째 · 둘째 자리 시작만).
- 요약(`summary18.hold_samples`): `ok_n` · `err_n` · `live_n`(홀드가 남은 표본) · `partial_max` · `split_max` · `holds_max` · `group_holds_max` · `q_ms` 분포 · `first_t_ms` · `last_t_ms` · `stock_out_s`(`free_pairs` = 0인 첫 표본 시각 − k6 시작 · 초 — 메인 판단 2026-11-20 재고 소진 정의 · 끝까지 0이 아니면 null) · `last_free_pairs`(마지막 표본 값 — null일 때 함께 본다) · `free_pairs_min` · `available_min` · `partial_at[]`.
- 성공 표본 = 필수 키(`t_ms` · `holds` · `partial` · `split` · `available` · `free_pairs` · `q_ms`)가 모두 숫자인 줄. `partial_max` · `split_max`는 G1(§8.1)이다.
- 유효 조건(리뷰 H2-1 · 값은 plan `values`): k6 창 [k6 시작, k6 끝](서버 ms) 안 성공 표본 사이 최대 공백(창 양끝 포함 · `max_gap_s`) ≤ plan `hold_sample_gap_factor`(본 · 동시 경합 3 · 경계 5 — 메인 결정 2026-11-20) × `hold_sample_s` · 실패 줄 비율(`fail_ratio` = err · 깨진 줄 ÷ 전체) ≤ `hold_sample_fail_max`(0.10) · 성공 표본 ≥ 1 · 홀드가 남은 표본 ≥ 1. 하나라도 어기면 §7 `hold-sample-missing`. 요약에 `first_t_ms` · `last_t_ms` · `max_gap_s` · `gap_limit_s` · `fail_ratio`를 낸다.

### 6.13 `app.log.gz` — 경계 셀 5xx의 원인 연결(리뷰 H2-3 · 설계 되돌림)

- 집계로 추론하지 않는다(5xx 수 ↔ 교착 지표 빼기 보정은 삭제했다). 응답마다 앱 로그에서 직접 원인을 찾는다.
- 로그 블록 = 시각 머리 줄(`2026-…T…​.mmmZ  LEVEL …` — Spring Boot 기본 형식 · 앱 컨테이너 시계 = 서버 호스트 시계) + 다음 머리 줄 전까지의 줄(스택 · root cause).
- 요청 예외 블록 = ERROR 머리 줄에 `Servlet.service() for servlet`(처리되지 않은 예외를 Tomcat이 남기는 줄 — 요청 경로는 남지 않는다). 40P01 = 그 블록에 `deadlock detected`(PostgreSQL 40P01 문구) 또는 `40P01`이 있음.
- 연결: 경계 셀 앱 5xx 응답(세션 행의 응답 + 행이 남지 않은 CSV 5xx — 리뷰 H3-2)을 **응답 끝(tr) 순**으로(리뷰 H3-4 — 긴 응답이 짧은 응답의 블록을 먼저 가져가지 않게), 응답 창 `[ts − tol, tr + tol]`(tol = `values.app_log_match_tol_ms` 200ms · CSV 5xx는 창 = CSV 끝 시각(초 단위) − 지연 − 1초 ~ 끝 시각 + 1초) 안의 아직 쓰지 않은 가장 이른 요청 예외 블록과 짝짓는다(한 블록은 한 응답에만). 결과 `edge.http_5xx_link = {40p01, other, unlinked, csv_only}`. `edge.http_5xx{op}` · `http_5xx_total`은 세션 행 5xx + 행 없는 CSV 5xx를 한 번씩 센다(리뷰 H3-R2). `unlinked`는 로그가 정상인데 창 안에 블록이 없을 때만 센다(로그가 없으면 `app-log-missing` 무효 — 리뷰 H3-3).
- 판정: `other`(40P01이 아닌 5xx)와 `unlinked`(창 안에 블록이 없거나 로그 파일이 없음)는 G1 실패(§8.1). 조용히 '설명됨'으로 두지 않는다.
- 한계: 로그에 요청 경로가 없어 시각으로만 잇는다. 같은 창에 원인이 다른 5xx가 겹치면 짝이 바뀔 수 있으나, 그때도 40P01이 아닌 블록이 하나 있으면 `other` ≥ 1이라 판정은 같다.

### 6.11 그 밖에 요약기가 읽는 파일

| 파일 | 바탕 회차(status ok) 값 |
|---|---|
| `seed-check.json` | `schedules 1` · `seats = available = plan.seed.seats` · `holds = reservations = quota_rows = 0` · `flyway`에 `"15"`와 `"16"` |
| `k6-window.json` · `clock-offset.json` · `pc-link.json` · `timeline-client.jsonl` | ADR-017 §6.11과 같다 |
| `k6-requests.csv.gz` | 읽는 열: `metric_name` · `metric_value` · `name` · `status`. 지연 행 = `http_req_duration` · status 2xx · name ∈ §3.1 op 9개. 빠진 반복 = `dropped_iterations` 값 합. **name마다 CSV 2xx 수 = 세션 로그 2xx 수**(어기면 `latency-missing`). 열 수가 머리와 다른 행 · 숫자 아닌 값 = `k6-csv-missing` |

## 7. 회차 무효 사유 (`summarize18.py --rep` → `status`)

무효 회차는 판정에 들어가지 않는다(M18-3). status 문자열 조립(하네스 사유 줄 순서 → 요약기 사유 사전순 · 중복 제외)은 ADR-017 §7과 같다.

| 사유 | 조건 |
|---|---|
| `plan-missing` | plan.json 없음 · 깨짐(→ 요약기 exit 2 · run18은 `invalid:summarize-failed`) |
| `harness-reasons-missing` | harness-reasons.txt 없음 |
| `sessions-missing` · `sessions-broken` | 세션 로그 없음 · 깨진 줄 > 0(§3) |
| `sessions-count-mismatch` | 행 수 ≠ `n_total`, 또는 종류별 행 수 ≠ `planned`(**계획 세션 수 = 분류 세션 수** — 조용한 누락 0) |
| `session-dup` | 같은 sid 두 줄 이상 |
| `session-attrs-mismatch` | **시드 대조**: T · P · G · D · U · X가 `plan18.session_attrs`와 1e-6 넘게 다름 · seats ≠ `seats_for(…)` · 두 석이 다른 줄 · user ≠ USER_BASE + sid · kind ≠ 바퀴 · (동시 경합 셀) `burst` ≠ `burst_of(…)` |
| `k6-conn-error` | `invalid:conn-*` 세션 ≥ 1(k6 연결 오류 — dial · reset · timeout · **환경 사유**) |
| `session-invalid` | 그 밖의 `invalid:*` 세션 ≥ 1(세부 = summary18 `sessions.invalid_detail`) |
| `ledger-missing` · `db-dump-missing` · `consistency-missing` · `act-missing` · `app-config-missing` · `seed-check-missing` · `inject-missing` · `drain-missing` | 필수 파일 없음 · 깨짐 · 필수 키 결측(§6) |
| `config-mismatch` · `hold-group-mismatch` | §6.10 |
| `consistency-mode-mismatch` | `pay_compensation_mode` ≠ `refund` |
| `seed-mismatch` | §6.11 seed-check 바탕 값과 다름 |
| `pg-not-reset` | 회차 격리 위반(M18-4): DB intent · hold_group · reservation · seat_hold의 user_id가 세션 사용자가 아님, 또는 원장 uid가 DB intent에도 세션 결제 시작 uid에도 없음 |
| `pg-reset-during-run` · `inject-mismatch` · `inject-leak` | §6.6 |
| `app-restarted` | §6.4 |
| `drain-poll-failed` · `dump-race` · `dump-unstable` · `ledger-recheck-failed` | §6.5(뒤 셋은 run18 하네스 사유) |
| `reject-header-mismatch` | **지표 대조**(하네스 설계 §5): k6 선점 409의 헤더 합 ≠ 증분. 1석 `hold` 409의 `rj`(nowait · rule — 그 밖 값 · 없음은 `bad:<값>`으로 세어 늘 어긋난다) 합 = Δ`hold_rejected`, `hold_group` 409의 `group-<cause>` 합(키 `<metric_mode>:<cause>`) = Δ`group_rejected`. 묶음 409는 Δ`hold_rejected`를 올리지 않아야 한다 |
| `group-request-mismatch` | k6 묶음 · 해제 요청의 `<op>:<outcome>`(§5 `metric_outcome`) 수 ≠ Δ`group_request`(키마다) |

| `burst-spread` | (동시 경합 셀) 묶음 안 선점 송신 폭(`max ts − min ts`)이 `values.burst_spread_max_ms`(5ms)를 넘거나, 잴 수 없거나(구성원 행 · ts 결측), 구성원 선점의 `cn`이 0이 아니거나 없는(새 연결 — 리뷰 H1-8) 묶음의 비율 > `values.burst_spread_bad_frac`(0.1) — 동시성이 성립하지 않음(하네스 설계 §8) |
| `edge-not-sent` | (경계 셀) 흐름마다(edge_confirm 확정 · edge_ps 결제 시작) 예약 창 안(`|ts − 중심| ≤ eps + 1ms`)에 보낸 요청 수가 하나라도 0 — 송신 실패(가정 2의 하네스 쪽 조건). 진입 · straddle은 유효성 조건이 아니다(§8.2 노출량 · 리뷰 H2-3) |
| `app-log-missing` | run18 `logs18`: 앱 로그 파이프 실패 · 압축 검사(`gzip -t`) 실패 · 기동 머리 줄 수(`zcat … | grep -c` — 스트림을 끝까지 읽는다 · pipefail 아래 `grep -q`의 SIGPIPE 오판을 피함 · 리뷰 H3-R3) 0(하네스 사유 · 모든 셀). 요약기: 경계 셀에서 `app.log.gz`가 없거나 비었거나 깨졌거나 기동 머리 줄이 없음(리뷰 H3-3) |
| (`k6-plan-mismatch` 보강) | `adr018.rate` · `d_arr_s`도 plan `rate` · `d_arr_s`와 같아야 한다(리뷰 H3-5) |
| `hold-sample-missing` | §6.12 표본 파일 없음 · 성공 표본 0 · 홀드가 남은 표본 0 · 창 안 최대 공백 > 3 × 주기 · 실패 비율 > 10%(리뷰 H2-1) |
| `k6-exit-<n>` · `k6-exit-unknown` · `k6-plan-mismatch` · `clock-offset-bad` | ADR-017 §7 · §6.8 |
| `k6-csv-missing` · `dropped-iterations` · `latency-missing` | §6.11 |
| `pc-link-missing` · `pc-link-down` · `pc-mem-missing` · `pc-mem-low` | ADR-017 §6.11 — `pc-link-down` · `pc-mem-low`는 **환경 사유** |
| `env-send-lag` | 예정 송신(`at`이 있는 선점(동시 경합 셀) · 결제 시작 · 확정)의 `ts − at`이 `plan.send_lag_max_ms`(본 2000 · 경계 250)를 넘는 요청 ≥ 1, 또는 허용치 없음 — **환경 사유(단독일 때)** |

- **환경 사유 판별**(campaign18 `kind_of` · summarize18 `env_kind`): `pc-link-down` · `pc-mem-low` · `k6-conn-error`가 하나라도 있으면 env(다른 사유와 섞여도 — 연결 오류는 지표 대조도 함께 깨뜨린다), 사유가 전부 `env-send-lag`면 env, 그 밖 무효 · status 없음은 harness, `ok`는 valid. 재측정 상한은 env · harness 따로 자리마다 2번이다(ADR-017과 같다).

## 8. 판정

### 8.1 G1 정합(M18-1 — 회차마다 · 유효 회차 전부)

아래가 전부 0이다(`summary18.g1.items` · 하나라도 0이 아니면 `g1.ok = false` — 회차 status는 ok인 채 판정 실패로 기록).

1. uid 끝 상태(§4 기대 0 shape 10개 — 덤프에 나오는 모든 uid).
2. `dead_tasks` · `unfinished_tasks`(PENDING · IN_PROGRESS) · `open_after_drain`(OPEN intent).
3. `double_sold_seats`: DB 덤프의 CONFIRMED 예약이 2개 이상인 좌석 수(이중 판매).
4. `consistency_end`: 배수 뒤 `pay_*` 기대 0 7개 · 묶음 3개. `consistency_k6`: k6 직후 `pay_multi_reservation_intents` · 묶음 3개(**부분 선점 = `group_partial_holds`**). `consistency_v_sum` · `consistency_k6_v_sum`: 두 스냅숏의 `v_*` 합(**카운터 = 홀드 + 예약 = `v_counter_mismatch`** 포함).
5. 세션 `integrity:*` 수 · `unexpected:*` 수 · `main_5xx`(본 · 동시 경합 셀의 앱 5xx 응답 수 — 경계 셀은 0으로 두고 §8.2 경계 표에 센다) · `deadlock`(본 · 동시 경합 셀의 Δ`seat.db.deadlock` 합 — 설계-2 · 3 '교착이 생기지 않는다' · 경계 셀은 0으로 두고 측정값으로 센다 · 경계 셀의 40P01 0은 무효도 실패도 아니다 — 명세 Q18) · `edge_5xx_non40p01` · `edge_5xx_unlinked`(경계 셀 — §6.13 앱 로그 연결에서 40P01이 아닌 5xx · 연결할 수 없는 5xx · 리뷰 H2-3).
6. 부하 중 홀드 표본(§6.12): `sample_partial`(부분 선점 묶음 수 최대) · `sample_split`(만료 갈림 묶음 수 최대). 동시 경합 셀은 `burst_multi_success`(한 묶음에서 2석 성공 ≥ 2 — 같은 좌석을 둘이 잡음 · 리뷰 H1-10)도 0이어야 한다.
7. record-level: `record_pay_ok_not_in_ledger`(k6 결제 성공인데 원장 captured 아님) · `record_pay_ok_no_intent` · `record_ledger_paid_without_k6_pay` · `record_group_missing`(k6 묶음 201의 groupId가 DB hold_group에 없음) · `record_group_extra`(DB hold_group인데 k6 201이 아님). **결측은 위반이 아니다**(리뷰 H3-1): DB 덤프 · 원장이 없으면 세 `record_pay*` 항목은 null, 세션 행이 결손됐거나(sessions-missing · broken · count-mismatch · dup) 연결 오류(상태 0) 응답이 있으면 k6 ↔ 원장 두 방향 대조(`not_in_ledger` · `paid_without_k6_pay`)와 `group_extra`는 null(확인 불가 — 그 회차는 이미 무효다). 묶음 선점 5xx · 연결 오류가 있으면 `record_group_extra`도 null(확인 불가 · 리뷰 H3-R4 — 0으로 두지 않는다). G1은 결측과 실제 0을 구분한다: 확인 불가로 둔 `record_group_extra` · `record_pay_ok_not_in_ledger` · `record_ledger_paid_without_k6_pay`의 null은 실패로 세지 않고 `g1.unchecked[]`에 적는다(덤프 · 원장 자체가 없으면 `g1.ok`는 거짓).
8. `csv_5xx_without_row`(본 · 동시 경합 셀 — k6 CSV의 앱 요청 5xx 중 세션 행 5xx와 이름 · 끝 시각 ±1초로 짝지어지지 않는 수 · 반복이 끊겨 행이 남지 않은 5xx · 리뷰 H3-2) · `group_cause_bad`(묶음 409 원인 표지 계약 — 허용값 nowait · short · rule 중 방식별 가능 원인(split = nowait · rule · one_shot = short · rule) 밖의 k6 헤더 · 헤더 없음 · 앱 지표 `group_rejected`의 계약 밖 키 수 · 지표 대조와 따로 · 리뷰 H3-6).

`g1.ok`는 dump(원장 · DB)가 있고 위 값이 전부 0일 때만 참이다.

### 8.2 비교 지표(M18-2 · M18-5 — 판정에는 p99 상한만 쓴다)

회차(`summary18.json`):

- `hold`: `n` · `both` · `first` · `none` · `rate = both ÷ n`(2석을 다 잡은 사용자 비율) · `wilson`(95%) · `two_seat_ms`(2석 확보까지의 지연 = 마지막 선점 tr − 첫 선점 ts · got = both 세션 · 보고만 — 메인 결정: B0 · B1의 p99 상한 판정은 요청 단위 API 지연 · §8.3).
- `hold_samples`(§6.12) · `order`(plan `order`).
- `hold.both_fail_pairs`: **경합 쌍** = 좌석 쌍이 겹치고(`|s_i − s_j| ≤ 1`) 선점 창 `[첫 선점 ts, 마지막 선점 tr]`이 겹치는 세션 쌍. `both_fail` = 둘 다 `got ≠ both` · `ratio = both_fail ÷ pairs`.
- `hold.first_only`(B0 · B1만): `n`(got = first) · `held_s` = 첫 석이 묶인 시간 분포(B0 = 선점 응답 expiresAt − 수신 · B1 = 해제 응답 수신 − 선점 응답 수신) · `blocked_sessions` = 그 창 안에 그 좌석을 포함한 선점을 보내 실패한 다른 세션 수(첫 석마다 세션당 한 번).
- `latency`: 단계(`hold` = hold + hold_group · `pay_start` = pay_start + pay_start_group · `confirm` = confirm + confirm_group)별 CSV 2xx 지연 `p50_ms` · `p99_ms`(백분위 = `xs[min(n − 1, ⌊n × q⌋)]` — ADR-017과 같다).
- `outcomes`: 범주별 세션 수 · `refunds_done`(CLOSE DONE · REFUNDED 작업 수).
- `burst`(동시 경합 셀만): `bursts`(묶음 수) · `conn_new_bursts`(선점이 새 연결을 맺은 묶음) · `hold_connecting_ms` · `preconnect_connecting_ms` · `success_hist{"<묶음 안 2석 성공 수>": 묶음 수}`(이상적이면 1) · `all_fail` · `all_fail_ratio`(모두 실패한 묶음 비율) · `multi_success`(2석 성공이 둘 이상인 묶음 — 같은 b + 1을 둘이 잡을 수 없으므로 0이어야 한다 · 정합은 G1이 본다) · `causes{nowait|short|rule}`(k6가 받은 `group-<cause>` 헤더) · `spread_ms{n, p50, p99, max}` · `over` · `over_ratio` · `limit_ms` · `bad_frac` · `bad`.
- `edge`(경계 셀만): `http_5xx{op}` · `http_5xx_total` · `http_5xx_link{40p01, other, unlinked}`(§6.13) · `app_log_errors`(요청 예외 블록 수 · 로그 없음 = null) · `deadlock{path}`(증분 — 5xx와 나란히 보고) · `deadlock_total`(40P01 수) · **`exposure{edge_confirm|edge_ps: {sent, in_window, entered, straddle, contains}}`**(노출량 — `entered` = 송신 `ts ∈ (E − 배치 주기, E]` · `straddle` = `ts < E ≤ tr` · `contains` = 요청 구간 `[ts, tr]`이 E를 품음 · E = edge_confirm 결제 시작 응답 expiresAt(TTL 8 > W 6이라 연장 안 됨 — 선점 만료와 같다) · edge_ps 선점 응답 expiresAt) · `in_window` · `straddle`(합) · `responses{kind: {"<st>:<code>": n}}` · `refunds_done` · `close_by_source`.
  - **문구 규칙: '경계 셀 40P01 0'은 노출량(`exposure` — 진입 · straddle · E를 품은 요청 수)과 함께만 결론에 쓴다.** 노출이 적으면 '0'은 '드물다'의 근거가 아니다.

### 8.3 캠페인(`summarize18.py --campaign`)

- 자리마다 유효 시도 = `status` ok인 가장 늦은 시도(ADR-017과 같다).
- **회차 사이 대조**(M18-7): 사용 회차 전부의 `plan.json values` 전체 · `meta.json sha` · `harness_hash`가 같아야 `runs_consistent = true`. p99 상한은 그 `values.p99_limit_ms`에서 온다(요약기 상수가 아니다).
- `table[method][overlap]`: 3회 합 `sessions` · `both` · `rate` · `wilson95` · `rate_per_rep` · `both_fail_pairs`(합) · `first_only`(B만 — n 합 · 회차별 held p50 · p99 · blocked 합) · `latency[stage]{p50_ms[], p99_ms[], median_p99_ms, limit_ms, over, ok}` · `success` · `confirm_rejected` · `confirm_mixed` · `refunds_done`.
- `burst[method]`: 3회 합 `bursts` · `all_fail` · `all_fail_ratio` · `multi_success` · `success_hist` · `causes` · `over` · `both` · `sessions` · `rate` · `wilson95` · 회차별 `spread_p99_ms[]` · `http_5xx` · `deadlock` · `g1_ok`.
- `edge[method]`: 3회 합 `deadlock{path}` · `deadlock_total` · `http_5xx{op}` · `http_5xx_total` · `http_5xx_link` · `exposure{flow: {…}}` · `refunds_done` · `in_window` · `straddle` · `close_by_source` · 회차별 `latency{stage: {p50_ms[], p99_ms[]}}`(보고만) · `g1_ok`.
- `g1["<method>/<cell>"] = {ok, fail_runs, reps_used}`.
- `table[method][overlap].rate_by_rep{"<rep>": rate}` · **`paired[cell]["<A>-<B>"]`**(본 셀 · 동시 경합 셀 — 리뷰 H1-11) = `{diffs{"<rep>": rate_A − rate_B}, mean, min, max, same_sign}`. 같은 rep 번호의 두 회차는 같은 세션 시드(도착 · 종류 · 좌석)를 쓰므로 짝을 이룬다.
  - **해석 기준**: 세션은 서로 독립이 아니다(같은 좌석을 다투므로 한 세션의 성공이 다른 세션의 실패다). 그래서 합친 Wilson 구간(`wilson95`)은 세션 독립 가정이라 폭이 낙관적이다 — 방식 차이는 짝 차 3개의 부호가 같은지(`same_sign`)와 크기를 함께 본다. 짝 차 부호가 섞이면 '차이를 확인하지 못함'으로 읽는다.
- **고정 값 회차만**(리뷰 H1-9 · M18-7): 사용 회차 중 `plan.fixed`가 참이 아니거나 `smoke` · `over`가 있는 회차가 하나라도 있으면 `unfixed_runs`에 적고, `complete = false` · 모든 방식 `pass = "insufficient"`다(판정된 실패도 판정 불가로 둔다).
- **p99 상한 판정 단위**(메인 결정 2026-11-20): 요청 단위 API 지연(`hold` = 1석 선점 · 묶음 선점 각 요청). B0 · B1의 '2석 확보까지의 지연'은 `hold.two_seat_ms`로 보고만 한다.
- **p99 판정 대상 셀**(리뷰 H2-2): 본 셀 3개 + 동시 경합 셀(split · one-shot — `burst[method].latency{stage: {p99_ms[], limit_ms, over, ok}}`). 경계 셀은 TTL · W · 배치를 바꾼 눈금이라 판정에서 빼고 보고만 한다(`edge[method].latency`).
- **무효 시도의 정합 위반**(리뷰 H2-6 — 메인 정책 '정합성 먼저'): 캠페인은 사용하지 않은 모든 시도(무효 · 앞 시도)의 summary18 `g1`도 읽는다. `g1.ok = false`이고 G1 항목에 0이 아닌 숫자(실제 위반 관측)가 있으면 `g1_in_invalid[]`(`<자리>:<폴더>`)에 적고, 그 방식의 `pass`는 `false`다(결측만으로 거짓인 시도 — 덤프 없음 등 — 는 세지 않는다). 사유에 `dump-race` · `dump-unstable` · `ledger-recheck-failed`가 있는 시도는 덤프가 안정 스냅숏이 아니다 — 덤프에 기대는 항목(끝 상태 shape · record · 배수 뒤 정합)만 확인 불가로 보고, 덤프와 무관하게 본 위반(`integrity_response`(응답 본문 계약 위반 · 한도 초과 응답) · `unexpected` · `main_5xx` · `csv_5xx_without_row` · `sample_partial` · `sample_split` · `group_cause_bad` · `burst_multi_success` · `edge_5xx_*` · `deadlock` · `consistency_k6*`)이 있으면 그대로 `g1_in_invalid`, 없으면 `g1_unchecked_dump[]`에 따로 적는다(리뷰 H3-1 · H3-R1).
- **방식별 `pass`**(정합 먼저 · 그다음 p99 상한):
  - `false` = 그 방식의 셀(본 셀 + 경계 셀 + 동시 경합 셀) 중 G1 실패 회차가 있거나, `g1_in_invalid`에 그 방식이 있거나, 본 · 동시 경합 셀의 어느 회차 단계 p99가 상한을 넘음(판정된 실패가 우선).
  - `"insufficient"` = 유효 회차가 셀마다 3이 아니거나 · `runs_consistent = false`거나 · p99 표본 · 상한이 없음.
  - `true` = 그 밖.
- `complete` = 48자리 모두 사용 회차가 있고 `runs_consistent`이고 `unfixed_runs`가 비어 있음.
- campaign18.sh: 재측정 상한을 다 쓴 뒤에도 무효인 자리가 남으면 끝 집계는 하되 **exit 4**(리뷰 H1-4). `--check`도 유효성까지 본다: 0 = 모든 자리 status ok · 4 = 무효 자리 있음 · 6 = status 없는 자리 있음.
- **기본값 결정(묶음 API를 기본으로 켤지 · split과 one-shot 중 무엇)은 사람이 한다** — 요약기는 이 표만 낸다(하네스 설계 §6).

## 9. outcome18 · summarize18 시그니처 · 출력

### 9.1 `outcome18.py`(모듈 · CLI)

```python
CATEGORIES = ("success", "released", "abandoned", "lost", "partial_kept", "partial_released", "ps_rejected", "pg_rejected",
              "confirm_rejected", "confirm_mixed", "edge_5xx")
SHAPES = (...)       # §4 표 shape 13개 · G1_SHAPES = 기대 0 10개
CONFIRM_REJECT = {(404, "HOLD_NOT_FOUND"), (409, "HOLD_EXPIRED"), (409, "SEAT_NOT_HELD")}
PS_REJECT = {(409, "HOLD_EXPIRED"), (404, "HOLD_NOT_FOUND")}
METRIC_OP = {"hold_group": "hold", "release_group": "release_group", "release": "release_hold", "pay_start_group": "pay_start", "confirm_group": "confirm"}

def read_sessions(path)                               # outcome17.read_sessions 그대로
def end_states(db_dump, ledger, amount) -> dict       # uid → End(§4)
def shape(end, amount) -> str                         # §4 표
def classify(row, ends, plan) -> str                  # §5
def metric_outcome(o) -> str | None                   # §5 끝
def requests(row) -> list                             # 응답 객체 전부(hold → rel → ps → pay → cf)
```

CLI: `outcome18.py <회차 폴더>` → 표준 출력 JSON 한 줄 `{"sessions": [{sid, kind, got, cat}], "counts": {kind: {cat: n}}, "uids": {shape: n}}` · exit 0. 입력이 없으면 exit 2 · `{"error": "<plan|sessions|db-dump|ledger>-missing"}`(검사 순서대로 첫째 하나).

### 9.2 `summarize18.py`

- `--rep <회차 폴더>`: `summary18.json`을 쓰고 표준 출력 마지막 줄에 `ok` 또는 `invalid:<사유,…>` · exit 0. plan이 없으면 exit 2.
- `--campaign <캠페인 폴더>`: `campaign18.json`을 쓰고 표준 출력에 `complete:<true|false>` · 마지막 줄 `pass:<방식별 JSON>` · exit 0.

`summary18.json`:

```
{"method", "cell", "rep", "status", "reasons": [..],
 "sessions": {"n", "planned", "by_kind": {kind: {cat: n}}, "invalid_detail", "integrity", "fail", "unexpected"},
 "uids": {shape: n}, "g1": {"ok", "items": {§8.1}}, "hold": {§8.2}, "latency": {stage: {n, p50_ms, p99_ms}}, "outcomes": {..}, "edge": {..}|null,
 "metric": {"k6_hold_reject", "app_hold_reject", "k6_group_reject", "app_group_reject", "k6_group_request", "app_group_request", "deadlock", "expiry_tick"}|null,
 "record": {"k6_pay_ok", "ledger_paid", "db_intents", "pay_ok_not_in_ledger", "pay_ok_no_intent", "ledger_paid_without_k6_pay",
            "k6_groups", "db_groups", "group_missing", "group_extra", "group_extra_unchecked", "stray": [..≤20], "stray_n"},
 "drain": {"reason", "len_s"}, "send_lag_ms": {"n", "p50", "p99", "max", "over"}, "pc_mem": {..}|null}
```

`campaign18.json`: `{"plan_fixed", "runs_consistent", "complete", "runs": {"<method>/<cell>/rep<k>": {status, attempts, used}}, "g1", "table", "edge", "pass", "note"}`(§8.3).

## 10. 알려진 한계 · 주의 (측정 전 고정에서 볼 것)

1. **경계 셀 θ · M = 0**(메인 보정 2026-11-20): θ = 1초면 E ± 0.5초의 결제 시작이 모두 '남은 시간 ≤ θ' 409가 되어 연장 문장까지 가지 않는다. 그래서 경계 셀만 θ = 0 · M = 0으로 두고(기동 검사 M ≤ θ · θ < TTL · θ < W 통과 — `plan18.startup_problems`), edge_ps는 E + 0 ± 0.5초에 보낸다. 이 값으로 edge_ps가 '연장 문장 ∥ 만료 배치' 경합 창에 실제로 드는지는 로컬 스모크에서 본다(`edge.straddle` · `edge.responses`).
2. **부분 선점 관측 시점**(리뷰 H1-1로 고침): k6 직후 · 배수 뒤 consistency 스냅숏은 홀드가 대부분 사라진 시점이라 부분 선점을 거의 볼 수 없었다. 그래서 k6 동안 가벼운 홀드 표본(§6.12 — 2초 · 경계 1초)을 떠 G1에 넣는다. 표본 사이(수 초)에 생겼다 사라진 부분 선점은 보지 못할 수 있다(표본 한 번은 한 스냅숏이다). 홀드 표본은 본 회차의 홀드가 사는 동안(TTL 5분 · 이탈 홀드) 여러 번 뜨므로, 남아 있는 부분 선점은 잡힌다.
3. **B0 · B1의 앱 설정**: `seat.hold-group.enabled=false`(앱 기본값)로 띄운다. 그래서 1석 경로에 `HOLD_IN_GROUP` 검사 문장이 없다(설계-8) — '지금 방식'과 같은 조건이다.
4. **본 셀 회차 길이**: 최장 세션(ok = T + P + G ≈ 542초)이 정한다. 세션 수를 줄여도 회차는 거의 짧아지지 않는다(`plan18.py --dry-run` 줄이는 안).
5. **5xx와 40P01**(리뷰 H2-3 — 설계 되돌림): 경계 셀 5xx의 원인은 앱 로그로 응답마다 잇는다(§6.13). 40P01과 이어진 5xx만 측정값이고, 그 밖 · 연결 불가는 G1 실패다. `deadlock{group_confirm}`에는 거부 기록 트랜잭션의 40P01(응답 409 — 지표 계약 §1.3)도 들어가므로 deadlock 지표와 5xx 수는 같을 필요가 없다 — 둘을 나란히 보고만 한다.
6. **지표 대조의 전제**: 연결 오류(상태 0) 요청은 서버가 셌는지 모른다 — 그런 회차는 `k6-conn-error`로 이미 무효이고 `group-request-mismatch`가 함께 붙을 수 있다(환경 사유가 우선).

## 11. 고정 값(`plan18.py FIXED = True` — 2026-11-20 · .164 스모크 smoke18-164a 뒤 · 사용자 승인)

아래 값은 .164 스모크(smoke18-164a — 9회차 모두 ok) 뒤 측정 전 고정 커밋에서 정했다(사용자 승인 2026-11-20 · M18-7 — 이 뒤 바꾸면 캠페인 무효). 출처는 모두 `plan18.py` 한 곳이고, 회차 폴더의 `plan.json values`로 남는다.

| 값 | 고정 값 | plan18 이름 |
|---|---|---|
| 세션 수 · 도착률 | 1,100 · 20/s(도착 55초) · 경계 셀 1,000(도착 50초 — 명세 Q18) | `N_SESSIONS` · `EDGE_N_SESSIONS` · `RATE` |
| 동시 경합 묶음 | 크기 2~4(정수 균등) · 칸 4석 · 송신 여유 0.2초 · 송신 폭 문턱 5ms · 넘는 묶음 비율 상한 0.1 | `BURST_SIZE` · `BURST_BLOCK` · `BURST_LEAD_S` · `BURST_SPREAD_MAX_MS` · `BURST_SPREAD_BAD_FRAC` |
| 세션 종류(본 셀) | ok 70 · abandon 15 · release 15(%) | `KIND_WEIGHTS["main"]` |
| 세션 종류(경계 셀) | edge_confirm 1 · edge_ps 1 | `KIND_WEIGHTS["edge"]` |
| 생각 시간 T · 결제 시간 P · 확정 간격 G | ADR-017 ok 분포 그대로: U[30, 240]초 · 로그정규(m 45초 · σ 0.94 · 상한 300초) · U[0, 2]초 | `T_RANGE` · `PAY_*` · `CONF_GAP_RANGE` |
| 해제 지연 D | U[2, 5]초 | `REL_RANGE` |
| 겹침 H(시작 위치 범위) | low = 세션 × 3 · mid × 0.5 · **high × 0.2**(→ 3,300 · 550 · 220 — high는 0.1에서 바꿈: .164 스모크에서 0.1은 재고가 3.5초 만에 소진) · 좌석 = 줄 수 × 50 + 50(줄 수 = ⌈H ÷ 49⌉ — §2 `seed`) | `H_FACTOR` · `SEAT_PAD` |
| 경계 셀 앱 눈금 | TTL 8초 · 배치 0.2초(`200ms` — 명세 Q18) · W 6초 · θ 0초 · M 0초(기동 검사 M ≤ θ · θ < TTL · θ < W 통과 — §10-1) | `APP["edge"]` |
| 경계 송신 | X ~ U[−0.5, +0.5]초 · edge_ps = 선점 만료 E + 0 ± 0.5초(§10-1) | `EDGE_EPS_S` · `EDGE_PS_CENTER_S` |
| p99 허용 상한 | 선점 40 · 결제 시작 40 · 확정 50ms(.164 smoke18-164a — 차가운 표본을 뺀 관측 최대 p99 선점 24.4 · 결제 시작 25.0 · 확정 31.5ms × 약 1.5 · ADR-017 기반 초안은 지움) | `P99_LIMIT_MS` |
| 단계별 첫 5초 지연 표본 제외 | **결제 시작 · 확정 단계만**(`WARMUP_STAGES` — 선점은 전체 표본 · warmup_excluded 0: 차가운 지연은 PG 등록이 끼는 결제 시작에서만 관측됐고 선점은 제외 없이도 9.2~24.4ms로 상한 아래였으며 high 셀은 성공 선점이 첫 몇 초에 몰려 제외하면 표본이 사라진다(164a n 1) — 메인 판단 2026-11-20) 그 단계마다, 그 회차에서 그 단계의 첫 요청 송신 시각(CSV 끝 시각 − 지연의 최솟값 · 초 단위)부터 5초 안에 보낸 요청은 그 단계의 p50 · p99 판정 표본에서 뺀다(사용자 결정 2026-11-20 — 처음 안은 'k6 시작부터 5초'였으나 결제 시작의 첫 요청은 k6 시작 30초 뒤라 차가운 지연이 빠지지 않았다) · 뺀 수 = `latency.<stage>.warmup_excluded`(회차) · 캠페인 표 `latency.<stage>.warmup_excluded[]` — 회차 첫 PG 등록 · JIT 차가운 지연 130~180ms(.164 실측) | `WARMUP_EXCLUDE_S` · `WARMUP_STAGES` |
| 배수 창 상한 | 본 · 동시 경합 240초(메인 판단 2026-11-20 — 48회차 최악 11.85시간 · 고정 때 사용자 확인 · 실측 배수 = summary18 `drain.len_s` · campaign18 `runs.<자리>.drain`) · 경계 120초 | `DRAIN_CAP_S` |
| 예정 송신 허용 지연 | 본 2,000ms · 경계 250ms | `SEND_LAG_MAX_MS` |
| PC 메모리 하한 · 표본 공백 | 4 GiB · 30초(ADR-017 값) | `PC_MEM_RUN_MIN_KB` · `PC_MEM_GAP_MAX_S` |
| 시간 예산 | 12시간 = 최악 기준(배수 상한을 매 회차 다 씀). 48회차 고정 값으로 최악 11.85시간 · 예상 9.23시간 | `BUDGET_HOURS` |
| 재측정 상한 | 자리마다 환경 2 · 하네스 2(ADR-017과 같다) | campaign18.sh |
