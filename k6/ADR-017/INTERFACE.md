# k6/ADR-017 하네스 계약 (회차 폴더 · 세션 로그 · 덤프 · 분류 · 판정)

> 이 문서가 ADR-017 2단계 하네스의 **연결 계약**이다. 측정 명세는 `docs/plans/2026-11-14/adr-017-measurement/requirement-spec.md`이고, 설계는 ADR-017 설계-2 · 3 · 6 · 8을 따른다.
> - 쓰는 쪽: `scenarios/paid-flow17.js`(세션 로그) · `scripts/run17.sh`(회차 폴더 · 덤프 · 주입 · kill · 배수).
> - 읽는 쪽: `scripts/outcome17.py`(세션 분류) · `scripts/summarize17.py`(회차 무효 · 캠페인 판정) · `scripts/selftest17.py`.
> - 구현은 이 문서를 따른다. 구현과 이 문서가 다르면 구현의 결함이다(바꾸려면 이 문서를 먼저 고친다).
> - 숫자(비율 · 문턱 · 주입 · 시각)의 출처는 `scripts/plan17.py`다. 이 문서의 숫자는 지금 자리표시 값이다(`FIXED = False` — 측정 전 고정 커밋에서 확정 · M17-2).
> - 표의 **[초안]**은 측정 전 고정 전에 메인 · 사용자가 확정할 자리다. 테스트 사례는 [초안] 값도 이 문서대로 쓴다.

## 0. 용어 · 단위

- **후보**: `R` = `refund` · `D` = `auth-capture` · `S` = `substitute`(앱 손잡이 `seat.compensation.mode`).
- **셀**: `base`(주입 없음) · `pg`(PG 보상 호출 주입) · `kill`(앱 SIGKILL 2회 → 곧바로 재기동).
- **회차**: (후보, 셀, rep 1~3) 하나. 27회차의 순서 = `plan17.py --order`.
- **세션 종류**(`kind`): `ok` 정상 확정 · `late` 지연 확정 · `noconf` 확정 안 보냄 · `abandon` 결제 안 하고 이탈 · `retry` 같은 uid 재시도.
- **uid**: payment-start가 발급한 `paymentUid`(서버 UUID). DB · PG 원장 · 세션 행을 잇는 키다.
- **시각**: 따로 적지 않으면 **서버 시계 epoch ms**(숫자 · 소수 허용)다.
  - k6 행: PC 시각 + `CLOCK_OFFSET_MS`.
  - DB 덤프: `extract(epoch …) × 1000`.
  - PG 원장: ISO-8601 문자열(`Z` · 소수 최대 9자리) — 읽는 쪽이 ms로 바꾼다. 흉내 PG는 앱과 같은 서버에서 돈다(같은 시계).
- **회차 폴더**: `<results-root>/<campaign id>/<cand>/<cell>/rep<k>/`. 기본 results-root는 `k6/ADR-017/results`다. 재측정(`--attempt n`)은 앞 시도를 `rep<k>.attempt<n−1>`로 옮긴다(run16 규칙 그대로).

## 1. 회차 폴더 파일 목록

필수(✔) 파일이 없거나 깨지면 회차 무효다. 무효 사유는 §7에 있다. 아래 밖의 파일은 ADR-016 run16이 남기던 것을 그대로 남긴다(표본기 · 로그 · 시드 확인 등). 이름 · 형식은 run16과 같고, 요약기는 §7에 적힌 것만 읽는다.

| 파일 | 쓰는 곳 · 시점 | 형식 | 필수 | 읽는 곳 |
|---|---|---|---|---|
| `plan.json` | run17 시작 | `plan17.py --render` 출력 그대로(§2) | ✔ | outcome · summarize |
| `meta.json` | run17 끝 | §6.9 | ✔ | summarize |
| `status` | run17 끝 | 한 줄: `ok` 또는 `invalid:<사유>,<사유>…` | ✔ | campaign17 · summarize --campaign |
| `harness-reasons.txt` | run17 내내 | 줄마다 하네스 사유 하나(없으면 빈 파일) | ✔ | summarize(그대로 무효 사유로 옮김) |
| `sessions17.log.gz` | k6 `--console-output` → gzip | §3 — 줄마다 k6 JSON 로그, `msg` = 세션 행 JSON | ✔ | outcome · summarize |
| `k6-summary.json` | k6 handleSummary | k6 요약 원본 + `adr017` 머리(§6.8) | ✔ | summarize |
| `k6-requests.csv.gz` | k6 `--out csv` | k6 CSV(요청 시간 — G3 p99 · 빠진 반복 dropped_iterations) | ✔ | summarize |
| `pc-link.json` | run17 끝(`pclink14.sh` — ADR-016 run16과 같다) | `{readable, iface, since_epoch, until_epoch, down, lines, why}` | ✔ | summarize(환경) |
| `timeline-client.jsonl` | 표본기(5초 — k6 PC) | 줄마다 `{"t": ISO, "k6_pcpu", "k6_rss_kb", "load", "mem_avail_kb"}`(lib14 client_line14) | ✔ | summarize(환경) |
| `k6-window.json` | run17 k6 직후 | run16과 같다: `k6_t0_epoch` · `k6_end_epoch`(PC 초) · `exit` · `clock_offset_ms_given` | ✔ | summarize |
| `clock-offset.json` | run17 | run16과 같다: `{before, after}` | ✔ | summarize |
| `inject.json` | run17 PG 리셋 · 주입 때마다 | §6.6 | ✔ | summarize |
| `kill.json` | run17(kill 셀만) | §6.7 | ✔(kill) | outcome · summarize |
| `drain.json` | run17 배수 창 | §6.5 | ✔ | summarize |
| `pg-ledger.json` | 배수 뒤 `GET /ledger` | §6.1 | ✔ | outcome · summarize |
| `db-dump.json` | 배수 뒤(원장 덤프 직후) | §6.2 | ✔ | outcome · summarize |
| `consistency.json` | 배수 뒤 `/internal/consistency` | §6.3 | ✔ | summarize |
| `act17-before.json` · `act17-after-k6.json` · `act17-end.json` | k6 직전 · k6 직후 · 배수 뒤 | §6.4 | ✔ | summarize |
| `app-config.json` | 시드 뒤 | §6.10 | ✔ | summarize(`plan17.py --config-check`) |
| `seed-check.json` | 시드 뒤 | lib15 `seed_check15` 출력 | ✔ | summarize |
| `timeline-server.jsonl` | 표본기 | run16과 같다(docker stats — DB CPU) | ✔(base) | summarize(G3) |
| `summary17.json` | `summarize17.py --rep` | §9.2 | — | campaign 판정 |

덤프 순서는 고정이다: **배수 끝 → `pg-ledger.json` → `db-dump.json` → `consistency.json` → `act17-end.json`**. 원장을 DB보다 먼저 뜨고, DB 덤프 뒤 원장을 한 번 더 읽어 ops 합을 대조한다(같으면 안정 스냅숏 — 절차와 사유는 §6.5 '덤프 반복').

## 2. plan.json (plan17 렌더) — 읽는 쪽이 쓰는 키

| 키 | 뜻 |
|---|---|
| `cand` · `mode` · `cell` · `rep` · `fixed` | 회차 식별 · 앱 모드 이름 · FIXED |
| `n_total` · `planned{kind: 수}` · `kind_cycle[...]` | 계획 세션 수 · 종류별 계획 수 · 교차 배정 바퀴(`kind = kind_cycle[sid mod len]`) |
| `params{tMin,tMax,payMedian,paySigma,payMax,gMin,gMax,rMin,rMax,lMin,lMax}` | 세션 속성 분포(초) — `plan17.session_attrs(rep, sid, params)`가 기록값을 다시 계산한다 |
| `seed{schedules:1, seats, spare, section:"A"}` | 시드(`/internal/reset?schedules=1&seatsPerSchedule=seats`) · 세션 좌석 = `spare + sid + 1` |
| `pg.faults` | `/admin/faults` 본문(§6.6) · `pg.op` = `pay`(R · S) \| `authorize`(D) |
| `kill` | kill 셀이면 `{at_s:[…], signal:"KILL", restart:"immediate", container:"seatlab-app-1"}`, 아니면 `null` |
| `drain{cap_s, poll_s, est_s}` | 배수 창 상한 · 조회 간격 · dry-run 예상 |
| `values` | 고정 자리 값 전부(`g2_x_s` · `g2_y_s` · `kind_weights` · `pg_rule` …) |
| `app_env` · `k6.env` | compose · k6 환경(run17이 그대로 넘긴다) |
| `k6.env.USER_BASE` | 세션 사용자 대역(문자열 · 정수로 읽는다 — 지금 `"1000000"`) · 세션 사용자 = `USER_BASE + sid` |
| `rate` · `d_arr_s` | 도착률 · 도착 구간(초) — k6-summary `adr017.rate` · `d_arr_s`는 이 값을 그대로 적는다(대조하지 않는다 — §6.8) |

## 3. 세션 로그 `sessions17.log.gz`

- 줄마다 k6 JSON 로그 한 줄이다(`--log-format=json`): `{"level":"info","msg":"<세션 행 JSON 문자열>","source":"console","time":"…"}`. 읽는 법은 `json.loads(json.loads(line)["msg"])`다.
- `msg`가 JSON이 아니거나 `k != "s"`인 줄은 **깨진 줄**이다.
  - 단, 파일 맨 끝 찢긴 한 줄은 세지 않는다. 그 세션은 수 대조가 잡는다(ADR-016 read_sessions와 같다).
  - k6 오류 로그(`level:"error"` — 스크립트 예외)도 깨진 줄로 센다.
- 세션 하나 = 행 하나다. 세션은 단계가 실패하면 다시 보내지 않고 그 단계에서 끝난다.

### 3.1 세션 행 필드 (`v: 1`)

| 필드 | 형 | 뜻 |
|---|---|---|
| `k` | `"s"` | 행 종류 |
| `v` | 1 | 행 형식 판 |
| `sid` | int | 세션 번호(0부터 · `iterationInTest`) |
| `kind` | str | §0 종류(`kind_cycle[sid mod len]`과 같아야 한다) |
| `rep` · `cand` · `cell` | int · str · str | 회차 식별(plan과 같아야 한다) |
| `user` · `seat` | int | 사용자 = `USER_BASE + sid` · 좌석 id = `spare + sid + 1`(회차 1) |
| `T` · `P` · `G` · `R` · `L` | float(초) | 시드 속성(`plan17.session_attrs` 값과 \|차\| ≤ 1e-6) — 종류와 무관하게 늘 기록 |
| `uid` | str \| null | payment-start 200의 `paymentUid`(그 전에 끝나면 null) |
| `at` | float \| null | 확정 예정 송신 시각(late = `p.exp + L×1000` · ok · retry = `g.tr + G×1000`) · 확정을 안 보내면 null |
| `h` | obj | 선점 응답: `{st, code, ec, ts, tr, hid, exp, ok}` |
| `p` | obj \| null | payment-start 응답: `{st, code, ec, ts, tr, hid, exp, pb, pws, ext, uid, ok}` |
| `g` | obj \| null | PG 결제 응답: `{op, st, code, state, ec, ts, tr, ok}` |
| `c1` | obj \| null | 첫 확정 응답: `{st, code, ec, ts, tr, rid, seat, rs, cp}` |
| `c2` | obj \| null | 재확정 응답(retry만 — 형식은 c1과 같다) |
| `end` | str | 세션이 끝난 자리: `hold` · `pay_start` · `abandon` · `pg` · `noconf` · `done` |

응답 객체 공통:

- `st` = HTTP 상태다. 0이면 연결 실패 · 타임아웃이다.
- `code`:
  - 2xx면 `"OK"`다.
  - 그 밖은 응답 본문의 `code`다(앱 `ErrorResponse.code` · PG `Body.code`). 없으면 `"NOCODE"`다.
- `ec` = k6 `error_code`다(0 = 없음 · 1050 = 요청 타임아웃).
- `ts` · `tr` = 송신 직전 · 수신 직후 서버 ms다.

단계별 필드:

- **선점** `h`:
  - `hid` = holdId, `exp` = expiresAt(ms)다.
  - `ok` = `st == 201` · hid 있음 · exp 유한.
- **payment-start** `p`:
  - `exp` = 연장 만료(④ — 응답 expiresAt), `pb` = payBy, `pws` = payWithinSeconds, `ext` = extended, `uid` = paymentUid다.
  - `ok` = `st == 200` · uid 있음 · exp · pb 유한 · pws 숫자 · ext 불리언 · `hid == h.hid`.
- **PG** `g`:
  - `op` = `pay` | `authorize`, `state` = 본문 `payment.state`다.
  - `ok` = `st == 200` · state가 `CAPTURED`(pay) 또는 `AUTHORIZED`(authorize).
- **확정** `c1` · `c2`:
  - 200 본문에서 `rid` = reservationId, `seat` = seatId, `rs` = status를 읽는다. 거부 본문이면 셋 다 null이다.
  - `cp` = `compensation` 객체 `{type, status, taskId, rid, seat}`이고, 없으면 null이다.
    - type은 `CLOSE` · `CAPTURE` · `SUBSTITUTE`다.
    - S 대체 성공이면 `type SUBSTITUTE` · `status DONE` · rid · seat가 있다(1단계 명세 부록 B).

### 3.2 `end`와 앞 필드의 관계 (행 정합 — 어기면 `invalid:row-bad`)

| end | 있어야 함 | 없어야 함(null) |
|---|---|---|
| `hold` | `h`(`h.ok` 거짓) | p · g · c1 · c2 · uid |
| `pay_start` | `h.ok` · `p`(`p.ok` 거짓) | g · c1 · c2 · uid |
| `abandon` | kind = abandon · `p.ok` · uid | g · c1 · c2 |
| `pg` | `p.ok` · `g`(`g.ok` 거짓) · uid | c1 · c2 |
| `noconf` | kind = noconf · `g.ok` | c1 · c2 |
| `done` | kind ∈ {ok, late, retry} · `g.ok` · `c1` · `at` | retry가 아니면 `c2` |

- retry는 `done`이면 `c2`가 있어야 한다.
- abandon · noconf 세션이 앞 단계에서 실패하면 end는 그 단계(`hold` · `pay_start` · `pg`)다.

## 4. uid 끝 상태 (배수 뒤 — M17-1 · 설계-2)

`outcome17.end_states(db_dump, ledger)`가 **DB intent 또는 PG 원장에 나오는 모든 uid**마다 아래 객체를 만든다.

```
End = {
  uid, intent_state|None, closed_reason|None, result_code|None,
  res: [{id, status, origin}],         # reservation.intent_id = uid인 행 전부
  pg_state|None, refunds:int, captured:bool(capturedAt ≠ null), authorized:bool(authorizedAt ≠ null), tombstone:bool,
  close: Task|None, capture: Task|None,  # compensation_task(type별 · uid당 ≤ 1 — UNIQUE)
  rejection: {code, http_status, rejected_ms}|None,
  shape: str
}
Task = {id, source, status, attempts, last_result, created_ms, done_ms|None}
```

`shape` 결정 — 위에서부터 처음 맞는 줄. `conf` = `res` 중 status `CONFIRMED` 수이고, `conf_hold` · `conf_sub` = 그중 origin `HOLD` · `SUBSTITUTE` 수다.

| # | 조건 | shape |
|---|---|---|
| 1 | conf ≥ 2 | `double_reservation` |
| 2 | refunds ≥ 2 | `double_refund` |
| 3 | pg_state = `REFUNDED` 이고 conf ≥ 1 | `refunded_with_seat` |
| 4 | pg_state = `VOIDED` 이고 conf ≥ 1 | `voided_with_seat` |
| 5 | conf_sub = 1 | `substituted`(pg_state = CAPTURED일 때만 정상 — 아니면 `substituted_unpaid`) |
| 6 | conf_hold = 1 이고 pg_state = `CAPTURED` | `seat_paid` |
| 7 | conf_hold = 1 (그 밖 pg_state — AUTHORIZED · EXPIRED · CLOSED · REGISTERED · 원장 없음) | `seat_moneyless` |
| 8 | pg_state = `REFUNDED` | `refunded` |
| 9 | pg_state = `VOIDED` | `voided` |
| 10 | pg_state = `CLOSED` 이고 captured · authorized 모두 거짓 | `closed_unpaid` |
| 11 | pg_state = `CAPTURED`, 또는 (pg_state = `CLOSED` 이고 captured 참 이고 intent 있음) | `orphan`(고아 결제 — 돈 빠짐 · 좌석 없음 · 환불 없음. CLOSED + 결제 흔적은 흉내 PG에서 생기지 않는 상태지만 생기면 고아로 센다) |
| 12 | pg_state ∈ {`AUTHORIZED`, `EXPIRED`}, 또는 (pg_state = `CLOSED` 이고 authorized 참 · captured 거짓 이고 intent 있음) | `auth_open`(D 승인이 묶인 채 남음 — EXPIRED는 PG가 스스로 풀었지만 보상이 닫지 않음) |
| 13 | pg_state = `REGISTERED` | `open_unpaid`(결제 없음 · 아직 닫히지 않음) |
| 14 | intent 있음 · 원장 항목 없음(pg_state = None) | `no_ledger` |
| 15 | 그 밖 전부 — intent 없는 CLOSED + 결제 흔적(captured 또는 authorized) · 알 수 없는 pg_state 값 | `no_intent` |

- 존재 여부: intent 있음 = `intent_state ≠ None`, 원장 있음 = `pg_state ≠ None`이다.
- 줄 14 · 15가 실제로 닿는 경우:
  - `no_ledger` = intent는 있는데 원장에 그 uid가 없고, CONFIRMED 예약도 없다(예약이 있으면 줄 7 `seat_moneyless`).
  - `no_intent` = intent가 없고 원장 상태가 CLOSED인데 결제 흔적(capturedAt · authorizedAt)이 있는 uid, 또는 알려진 7개 상태(REGISTERED · AUTHORIZED · CAPTURED · EXPIRED · VOIDED · REFUNDED · CLOSED) 밖의 pg_state다.
  - 그 밖에 **원장에만 있고 intent가 없는 uid는 pg_state 줄(8~13)로 분류된다**(예: 예열 잔재 REGISTERED → `open_unpaid`, CAPTURED → `orphan`). 그런 uid는 §7 `pg-not-reset`이 따로 잡는다.
- **intent state 값**(1단계 코드 — `PaymentIntents.State`): 워커는 intent를 바꾸지 않는다.
  - 안전망이 닫은 intent는 작업이 DONE이 된 뒤에도 `CLOSING`(closed_reason `SWEEP` · result_code = 재생 코드 `HOLD_NOT_FOUND` 또는 `HOLD_EXPIRED`)으로 남는다.
  - 확정 거부는 `REJECTED`, 대체 성공은 `SUBSTITUTED`, 확정 성공은 `CONFIRMED`다(`CompensationSweep.kt:98` · `PaymentConfirmService.kt:101,122` · `SubstituteAllocator.kt:79`).

## 5. 세션 분류 결정표 `outcome17.classify(row, end, cand, kills, plan) → str` [초안]

반환값은 범주 하나 또는 결함 문자열이다.

- 범주: `success` · `not_paid` · `pre_reject` · `wronged_refunded` · `wronged_voided` · `wronged_substituted` · `wronged_open` · `app_dead`
- `invalid:<사유>`: 하네스 무효 — 회차 무효.
- `mode_fail:<사유>`: 후보 실패 — 보고하고 G1 실패로 센다.
- `integrity:<사유>`: 응답과 끝 상태의 어긋남 — G1 실패(M17-1).

`end`는 그 세션 uid의 §4 객체다. uid가 없으면 None이다. `kills`는 kill 창 목록 `[(lo_ms, hi_ms)]`다(§6.7 · kill 셀이 아니면 빈 목록).

판정 순서 — 위에서부터 처음 맞는 줄:

**A. 행 정합**: §3.2를 어기거나, `kind` ≠ `kind_cycle[sid mod len]`이거나, rep · cand · cell이 plan과 다르면 → `invalid:row-bad`.

**B. 앱 죽음**: 아래 둘을 모두 만족하면 → `app_dead`.
- 세션의 **마지막 응답 객체**(end 단계의 응답 — `hold`면 h, `pay_start`면 p, `done`이면 아래 **판정 대상 확정 응답** `c`)가 앱 호출이다. PG 응답 `g`는 앱 호출이 아니라 제외한다.
- **판정 대상 확정 응답** `c`(B · D 공통): 미확정 = `st == 0` 또는 `st ≥ 500`(커밋 여부를 모름)이다.
  - retry가 아니면 `c = c1`.
  - retry면: c1이 미확정이면 `c = c1` · c1이 확정이고 c2가 미확정이면 `c = c2` · 둘 다 확정이면 `c = c1`(D에서 재생 대조 뒤).
- 그 응답이 `st == 0` 또는 `st ≥ 500`이고, `[ts, tr]`이 kill 창 하나와 겹친다(`ts ≤ hi` 이고 `tr ≥ lo`).
- end가 `abandon` · `noconf` · `pg`면 **마지막 응답 객체가 없다**(abandon · noconf는 앱 응답이 정상으로 끝났고, pg는 PG 응답이라 앱 호출이 아니다). 그래서 B는 적용하지 않고 C로 간다.

`app_dead`는 무효가 아니다. 끝 상태는 G1이 uid로 따로 본다(§8). `app_dead` 세션의 uid도 G1 대상이다.

**C. end별 갈래**(B에 안 걸린 세션):

- 표의 `<shape>` 자리에는 end가 None이면 문자열 `none`이 들어간다. 예: abandon인데 uid가 덤프에 없으면 `integrity:abandon-none`.
- 호출자(outcome17 CLI · summarize17)는 `end = end_states[row.uid]`로 넘긴다. `pay_start` 세션은 행 정합(§3.2)상 `row.uid = null`이라 **end가 늘 None** → 거부 쌍이면 늘 `pre_reject`다.
  - 표의 `closed_unpaid` · `open_unpaid` · `integrity:pre-reject-<shape>` 줄은 classify를 직접 부르며 end를 넘기는 경우(시험)에만 닿는다.

| end | 응답 조건 | end.shape | 결과 |
|---|---|---|---|
| `hold` | `h.st == 0` · `ec == 1050` | — | `mode_fail:hold-timeout` |
| | `h.st == 0`(그 밖) | — | `invalid:hold-code0` |
| | `h.st ≥ 500` | — | `mode_fail:hold-5xx` |
| | 그 밖(409 등 — 좌석은 세션 전용이라 생기면 안 됨) | — | `invalid:hold-<st>-<code>` |
| `pay_start` | (st, code) ∈ {(409, HOLD_EXPIRED), (404, HOLD_NOT_FOUND)} | None · `closed_unpaid` · `open_unpaid` | `pre_reject` |
| | 위와 같음 | 그 밖 | `integrity:pre-reject-<shape>` |
| | `st == 503` | — | `mode_fail:pay-start-503`(PG 등록 실패) |
| | `st == 200`(본문 계약 위반) | — | `mode_fail:pay-start-body` |
| | `st == 0` · `ec == 1050` | — | `mode_fail:pay-start-timeout` |
| | `st == 0` | — | `invalid:pay-start-code0` |
| | 그 밖 | — | `mode_fail:pay-start-<st>-<code>` |
| `abandon` | — | `closed_unpaid` | `not_paid` |
| | — | 그 밖 | `integrity:abandon-<shape>` |
| `pg` | `g.st == 409` · `code == DEADLINE_PASSED` | `closed_unpaid` · `open_unpaid` | `pre_reject` |
| | 위와 같음 | 그 밖 | `integrity:pg-deadline-<shape>` |
| | 그 밖(0 · 5xx · 다른 409 · 200인데 state가 다름 — 사용자 결제엔 주입이 없다) | — | `invalid:pg-<st>-<code>` |
| `noconf` | — | `refunded` | `wronged_refunded` |
| | — | `voided` | `wronged_voided` |
| | — | `orphan` · `auth_open` | `wronged_open` |
| | — | 그 밖(`seat_*` · `substituted` 등 — 확정을 안 보냈는데 좌석) | `integrity:noconf-<shape>` |
| `done` | 판정 대상 확정 응답 `c`(B 절) | | (아래 D) |

**D. `done`의 확정 응답**: `c` = 판정 대상 확정 응답(B 절). retry는 **c1 · c2가 둘 다 확정 응답일 때만** 재생을 대조한다 — `(c2.st, c2.code, c2.rid) ≠ (c1.st, c1.code, c1.rid)`면 → `integrity:retry-replay-mismatch`(N17-3).

- c1이 미확정이면: kill 창과 겹치면 B에서 `app_dead`, 아니면 아래 표 10~12줄(c1 기준 `mode_fail:confirm-timeout` · `invalid:confirm-code0` · `mode_fail:confirm-5xx`)이다. c2는 세션 분류에 쓰지 않는다.
- c1만 확정 · c2가 미확정이면: c2 기준으로 B(kill 창 → `app_dead`) → 표 10~12줄이다.
- 어느 경우든 그 uid의 끝 상태(돈 · 좌석)는 세션 분류와 따로 §4 shape로 G1에 들어간다(DB + 원장 — 응답이 아니라 끝 상태가 정본).

표의 REJ = 거부 쌍 {(404, HOLD_NOT_FOUND), (409, HOLD_EXPIRED), (409, SEAT_NOT_HELD)}이다. 위에서부터 처음 맞는 줄을 쓴다.

| # | c.st · c.code | end.shape | 추가 조건 | 결과 |
|---|---|---|---|---|
| 1 | 200 | `seat_paid` | `c.rid` = 그 uid의 CONFIRMED 예약 id | `success` |
| 2 | 200 | `seat_moneyless` | cand = D · `c.rid` = 그 uid의 CONFIRMED 예약 id | `success`(돈 없는 좌석 — G-비용) |
| 3 | 200 | `seat_paid` · `seat_moneyless` | (1 · 2에 안 맞음 — rid 다름 · R · S의 moneyless) | `integrity:200-mismatch` |
| 4 | 200 | 그 밖 | — | `integrity:200-<shape>` |
| 5 | REJ | `refunded` | cand ∈ {R, S} | `wronged_refunded` |
| 6 | REJ | `voided` | cand = D | `wronged_voided` |
| 7 | REJ | `substituted` | cand = S · `c.cp.type == SUBSTITUTE` · `c.cp.rid` = 그 SUBSTITUTE 예약 id | `wronged_substituted` |
| 8 | REJ | `orphan` · `auth_open` | — | `wronged_open` |
| 9 | REJ | 그 밖(5~7의 cand · 응답 조건 불일치 포함 — R의 voided · 응답이 SUBSTITUTE가 아닌데 대체 예약 · `seat_*` · `closed_unpaid` …) | — | `integrity:reject-<shape>` |
| 10 | `st == 0` · `ec == 1050` | — | — | `mode_fail:confirm-timeout` |
| 11 | `st == 0` | — | — | `invalid:confirm-code0` |
| 12 | `st ≥ 500` | — | — | `mode_fail:confirm-5xx` |
| 13 | 그 밖 | — | — | `mode_fail:confirm-<st>-<code>` |

- 기대 갈래(base · 앱 죽음 없음):
  - ok · retry → `success`.
  - late → R `wronged_refunded` · D `wronged_voided` · S `wronged_substituted`(좌석 없음 · 안전망이 먼저 닫음 → `wronged_refunded`).
  - noconf → R · S `wronged_refunded` · D `wronged_voided`.
  - abandon → `not_paid`.
- 기대 밖이어도 결정표 결과가 범주면 그 범주로 센다(무효 아님). 요약기는 종류 × 범주 표로 보고한다. late가 `success`면 §7 M17-6 검사가 따로 본다.

## 6. 덤프 · 기록 형식

### 6.1 `pg-ledger.json` — `GET http://<서버>:8104/ledger` 응답 그대로

```
{"payments": [{"uid", "userId"|null, "amount"|null, "deadline"|null, "state", "registeredAt", "authorizedAt"|null,
               "capturedAt"|null, "closedAt"|null, "refunds": int, "tombstone": bool}, …(uid 순)],
 "ops": {"register"|"pay"|"authorize"|"capture"|"close": 처리 시도 수}}
```

- `state` 값: `REGISTERED · AUTHORIZED · CAPTURED · EXPIRED · VOIDED · REFUNDED · CLOSED`.
  - 승인 만료(EXPIRED)는 읽을 때 판정한다.
- `ops`는 주입 판정 **뒤** 처리된 호출 수다. 503 주입은 처리 앞에서 끊겨 세지 않는다. 결과 모름은 처리됐으므로 센다.

### 6.2 `db-dump.json` — 한 문장(같은 스냅숏)

```sql
SELECT json_build_object('t_ms', extract(epoch FROM clock_timestamp()) * 1000,
  'payment_intent', coalesce((SELECT json_agg(json_build_object('uid', uid, 'hold_id', hold_id, 'user_id', user_id, 'schedule_id', schedule_id,
      'seat_id', seat_id, 'section', section, 'pay_by_ms', extract(epoch FROM pay_by) * 1000, 'state', state, 'result_code', result_code,
      'reservation_id', reservation_id, 'closed_reason', closed_reason, 'created_ms', extract(epoch FROM created_at) * 1000) ORDER BY uid) FROM payment_intent), '[]'::json),
  'reservation', coalesce((SELECT json_agg(json_build_object('id', id, 'schedule_id', schedule_id, 'seat_id', seat_id, 'user_id', user_id,
      'payment_uid', payment_uid, 'status', status, 'intent_id', intent_id, 'origin', origin, 'created_ms', extract(epoch FROM created_at) * 1000) ORDER BY id)
      FROM reservation), '[]'::json),
  'confirm_rejection', coalesce((SELECT json_agg(json_build_object('id', id, 'uid', uid, 'code', code, 'http_status', http_status,
      'rejected_ms', extract(epoch FROM rejected_at) * 1000) ORDER BY id) FROM confirm_rejection), '[]'::json),
  'compensation_task', coalesce((SELECT json_agg(json_build_object('id', id, 'uid', uid, 'type', type, 'source', source, 'status', status,
      'attempts', attempts, 'next_attempt_ms', extract(epoch FROM next_attempt_at) * 1000, 'lease_until_ms', extract(epoch FROM lease_until) * 1000,
      'last_result', last_result, 'created_ms', extract(epoch FROM created_at) * 1000, 'done_ms', extract(epoch FROM done_at) * 1000) ORDER BY id)
      FROM compensation_task), '[]'::json));
```

- 값 · 열 뜻은 V15(`db/migration/V15__*.sql`)와 같다. NULL 시각은 JSON `null`이다.
- 값 범위:
  - `payment_intent.state` ∈ OPEN · CONFIRMED · REJECTED · SUBSTITUTED · CLOSING
  - `closed_reason` ∈ null · SWEEP …
  - `compensation_task.type` ∈ CLOSE · CAPTURE
  - `source` ∈ REJECTION · SWEEP · CONFIRM
  - `status` ∈ PENDING · IN_PROGRESS · DONE · DEAD
  - `last_result`: REFUNDED · VOIDED · CLOSED · CAPTURED · `FAILED_<code>` …
  - `reservation.origin` ∈ HOLD · SUBSTITUTE
  - `intent_id`: 옛 경로 = null

### 6.3 `consistency.json` — `GET /internal/consistency?graceSeconds=20` 응답 그대로

- `v_*`는 ADR-001 좌석 정합 항목이다(기대 0). **필수 키 10개**: `v_duplicate_hold_seats` · `v_excess_hold_rows` · `v_duplicate_confirmed_seats` · `v_reserved_without_confirmed` · `v_confirmed_on_unreserved_seat` · `v_held_without_hold` · `v_hold_on_unheld_seat` · `v_stale_held_seats` · `v_over_limit_users` · `v_counter_mismatch`.
- 필수 v_* 10개와 아래 기대 0 `pay_*` 7개 중 하나라도 없거나 숫자(JSON number)가 아니면 → §7 `consistency-missing`(0으로 채우지 않는다).
- `pay_*`는 ADR-017 항목이다(`LoadtestDataService.paymentStates`):
  - 기대 0: `pay_multi_reservation_intents` · `pay_rejection_state_mismatch` · `pay_reserved_with_close` · `pay_capture_and_close` · `pay_rejected_without_task` · `pay_dead_tasks` · `pay_open_unheld_intents`.
  - 보고: `pay_intents` · `pay_open_intents` · `pay_pending_tasks` · `pay_moneyless_reservations` · `pay_substitute_reservations`.
  - `pay_compensation_mode`는 후보 모드 이름(`refund` · `auth-capture` · `substitute`)과 같아야 한다.

### 6.4 `act17-<점>.json` — 앱 지표 스냅숏(한 줄 JSON)

점은 `before`(k6 직전) · `after-k6` · `end`(덤프 뒤)다.

```
{"t": ISO, "t_end": ISO, "base": url, "process_start_s": float|null,          # process.start.time(앱 재기동 탐지)
 "compensation": {"done": {"<type>:<result>": n}, "retry": {"<type>": n}, "dead": {"<type>": n},
                  "unresolved": n, "oldest_pending_seconds": x, "record_failed": n, "sweep_failed": n},
 "confirm": {"legacy": n, "uid_conflict": n, "rejected": {"<code>": n}, "failed": {"<status>": n},
             "pay_start": {"<result>": n}, "extend": {"<result>": n}},
 "pg_register": {"ok": n, "rejected": n, "unavailable": n},
 "failed_names": [..]?}
```

- 지표 이름 · 태그는 main-app 상수를 따른다:
  - `seat.compensation.done{type,result}` · `.retry{type}` · `.dead{type}` · `.unresolved` · `.oldest_pending_seconds` · `.record_failed` · `.sweep_failed`
  - `seat.confirm.legacy` · `.uid_conflict` · `.rejected{code}` · `.failed{status}` · `.pay_start{result}` · `.extend{result}`
  - `seat.payment.pg.register{result}`
- 값 규칙은 actsnap14와 같다: 이름이 없으면 0, 조회 실패면 null(0으로 채우지 않는다). 실패한 이름은 `failed_names`에 둔다. `process_start_s`는 지표가 없으면 null이다.
- **필수 지표**(셋 파일 모두): 스칼라 `process_start_s` · `compensation.unresolved` · `.oldest_pending_seconds` · `.record_failed` · `.sweep_failed` · `confirm.legacy` · `.uid_conflict`는 숫자, 사전 `compensation.done` · `.retry` · `.dead` · `confirm.rejected` · `.failed` · `.pay_start` · `.extend` · `pg_register`는 사전이고 값이 전부 숫자.
  - 하나라도 어기거나 `error` 키가 있거나 `failed_names`가 비어 있지 않으면 → §7 `act-missing`.
- **재기동 탐지**(`process_start_s` — before ↔ end):
  - kill 셀: 서로 달라야 한다(kill 증거 조건 — §6.7).
  - base · pg 셀: 서로 다르면 → §7 `app-restarted`.
- kill 셀에서는 재기동으로 카운터가 0부터 다시 센다. 그래서 증분 대조(§7 `app-metric-mismatch`)를 하지 않는다.

### 6.5 `drain.json` — 배수 창(설계-6)

```
{"start_ms", "end_ms", "reason": "empty"|"cap", "cap_s", "poll_s",
 "polls": [{"t_ms", "pending": n, "open": n, "open_unheld": n}]}
```

- 조회 SQL(한 문장):
  - `pending` = compensation_task 중 status IN (PENDING, IN_PROGRESS)
  - `open` = payment_intent 중 state = OPEN
  - `open_unheld` = OPEN이면서 살아 있는 홀드가 없는 것(consistency `pay_open_unheld_intents`와 같은 식)
- **empty**: `pending = 0` 이고 `open = 0`인 조회가 연달아 둘이고, 그 둘 사이가 ≥ 안전망 주기 + 워커 주기(5초 + 1초)다.
- **cap**: k6 끝 뒤 `cap_s`가 지나도 empty가 아니다 → **무효가 아니다**. 그대로 덤프 · 판정기 · 판정을 하고, 남은 OPEN intent(`open_after_drain`)와 미완료 작업(`unfinished_tasks` — status PENDING · IN_PROGRESS, CLOSE와 D의 CAPTURE 모두)을 G1 실패 항목으로 센다(§8).
  - 무효는 하네스 쪽 증거가 있을 때만: reason = cap인데 성공한 조회(`err` 없고 `pending`이 숫자)가 하나도 없으면 → §7 `drain-poll-failed`.
- **덤프 반복**(run17 — 결과는 drain.json에 `dump: {attempts, max, stable}`로 덧붙는다): 한 시도 = 원장 덤프 → DB 덤프 → 원장 재조회(실패면 1회 재시도)이고, 덤프 원장과 재조회의 ops 합이 같으면 `stable = true`로 멈춘다.
  - empty: `max = 1`. 다르면 §7 `dump-race`(배수가 끝났는데 PG가 움직였다).
  - cap: 워커가 아직 PG를 부를 수 있어 `max = 5`번까지(3초 간격) 반복한다. 끝까지 다르면 §7 `dump-unstable`.
  - 재조회가 재시도 뒤에도 실패하면 §7 `ledger-recheck-failed`(경합과 구분).
  - 판정은 마지막 시도의 원장 · DB 덤프로 한다.
- cap 회차는 덤프 뒤에도 워커가 일할 수 있어 지표 증분(§6.4)과 DB가 어긋날 수 있다. 그래서 `app-metric-mismatch`를 대조하지 않고 summary17 `app_metric`에 보고만 한다(§7).

### 6.6 `inject.json` — PG 리셋 · 주입 기록

```
{"cell", "events": [{"at": "reset"|"faults", "t_ms", "rc": 0, "http": 204|200, "request": {…}|null, "response": {…}|null}]}
```

- 순서: (예열 · 시드 reset 뒤) `reset` → `faults`(본문 = plan `pg.faults`). 이 순서가 k6 시작 전 마지막이어야 한다.
  - mock-pg `/admin/reset`은 주입 설정도 비운다. 그래서 reset 뒤에는 늘 faults를 다시 건다.
  - k6 시작 뒤 reset이 있으면 §7 `pg-reset-during-run`이다.
- `faults.response` = `/admin/faults`가 돌려준 적용 설정(mock-pg `Faults.Config` — `{seed, rules:{op:{delayMs, failRate, unknownRate, unknownDelayMs}}}`).
  - 이 값이 plan `pg.faults`와 다르면 `inject-mismatch`다.
- base · kill 셀도 `rules: {}`로 faults를 건다. 규칙이 남아 있으면 `inject-leak`이다.

### 6.7 `kill.json` (kill 셀만)

```
{"planned_at_s": [..], "k6_t0_ms": 서버 ms(k6 실행 시각), "events": [
   {"n": 1, "planned_ms", "kill_ms", "kill_rc", "start_ms", "start_rc", "ready_ms"|null, "ready_ok": bool, "started_at": "<docker StartedAt>"}]}
```

- `kill_ms` = `docker kill -s KILL seatlab-app-1` 직전, `start_ms` = `docker start` 직전이다.
- `ready_ms` = 그 뒤 actuator health UP을 처음 본 시각이다.
- **kill 창**(outcome B) = `[kill_ms − 1000, (ready_ms 또는 start_ms + 120000) + 1000]`이다. 여유 1초는 `outcome17.KILL_TOL_MS`다.
- 유효 조건(하나라도 어기면 `kill-not-observed`):
  - events 2개 · `kill_rc = start_rc = 0` · `ready_ok` 둘 다 · `started_at` 둘이 다르다.
  - 시각: 허용 오차 `tol` = plan `values.kill_tol_ms`(§11 — 키가 없으면 실패).
    - `|kill.k6_t0_ms − k6 시작(서버 ms = k6-window k6_t0_epoch × 1000 + clock_offset_ms_given)| ≤ tol`.
    - events와 plan `kill.at_s`의 개수가 같고, 차례로 `|planned_ms − (k6_t0_ms + at_s × 1000)| ≤ tol` · `|kill_ms − planned_ms| ≤ tol`.
    - `kill_ms`가 k6 창 안이다(k6 시작 ≤ kill_ms ≤ k6 끝 — k6_end_epoch × 1000 + clock_offset_ms_given).
  - act17 `process_start_s`가 before와 end에서 다르다(§6.4).

### 6.8 `k6-summary.json`의 `adr017` 머리

`{rep, cand, cell, pg_op, rate, d_arr_s, plan_n, cycle, params, spare, pg_url}`. 값은 k6 환경(= plan `k6.env`)에서 온다 — `rate` · `d_arr_s` = plan `rate` · `d_arr_s`.

- **대조하는 키만**(하나라도 다르면 `k6-plan-mismatch` · 파일 · 머리 없음도 같다): `str(rep) == str(plan.rep)` · `cand` · `cell` · `plan_n == plan.n_total` · `cycle == plan.kind_cycle` · `spare == plan.seed.spare`.
- `pg_op` · `rate` · `d_arr_s` · `params` · `pg_url`은 기록만 한다.

### 6.9 `meta.json`

run16 meta 키에 다음을 더한다.

- 더하는 키: `cand` · `cell` · `pg_faults_seed` · `kill_planned_at_s` · `mockpg_image_id` · `local` · `smoke`
- `config` 키는 없다. `harness_hash`는 hhash17이다.
- **`summarize17 --rep`은 meta.json을 읽지 않는다**(없어도 회차 판정에 영향 없음). campaign17.sh가 끝에 `harness_hash`를 동결본 해시와 대조한다.
- `summarize17 --campaign`은 사용 회차마다 meta.json의 `sha` · `harness_hash`를 읽는다(§8 회차 사이 대조).

### 6.10 `app-config.json`

lib15 `app_config15`와 같은 모양이다. 다만 configprops 접두어에 **`seat.compensation` · `seat.payment`를 더한다**. 판정은 `plan17.py --config-check <cand> <cell> app-config.json`(exit 0 일치 · 1 불일치 · 2 판정 불가)이다.

```
{"configprops": [{"prefix": "<접두어>", "properties": {<중첩 객체 — actuator configprops 그대로>}}, …],
 "hikaricp.connections.max": [10], "app_nofile": {"soft": n, "hard": n},
 "system.cpu.count": [n], "containers": [...], "db_indexes": [...]}       # 마지막 셋은 기록만
```

- 판정이 읽는 것: configprops의 접두어 `seat.confirm` · `seat.hold` · `seat.compensation` · `seat.payment` · `spring.flyway` · `server`의 기대 키(`plan17.expected_config(cand)` — 점 경로를 중첩 객체로 따라간다 · enum은 대문자 `_` · Duration은 ISO `PT…`), `hikaricp.connections.max[0] == 10`, `app_nofile.soft · hard ≥ 21000`.
- 시험용 바탕 = `{"configprops": [{"prefix": p, "properties": <expected_config(cand)[p]의 점 경로를 중첩으로 편 것>} …], "hikaricp.connections.max": [10], "app_nofile": {"soft": 65536, "hard": 65536}}`.
- `config-check` 사유 `cond-config-mismatch` → 회차 사유 `config-mismatch`, `cond-config-missing` → `app-config-missing`, `compensation-mode-mismatch`는 그대로다.

### 6.11 그 밖에 요약기가 읽는 파일(형식 · 읽는 키)

| 파일 | 형식 · 읽는 키 | 바탕 회차(status ok) 값 |
|---|---|---|
| `seed-check.json` | lib15 `seed_check15` 한 줄 JSON: `schedules` · `seats` · `available` · `holds` · `reservations` · `quota_rows` · `flyway`(성공한 버전 문자열 목록) | `schedules 1` · `seats = available = plan.seed.seats` · `holds = reservations = quota_rows = 0` · `flyway`에 `"15"` 포함 |
| `k6-window.json` | `{k6_t0_epoch, k6_end_epoch(PC 초), exit, clock_offset_ms_given(ms)}` | `exit 0` · `clock_offset_ms_given` 숫자. 파일 없음 = `k6-exit-unknown` |
| `clock-offset.json` | `{before: {offset_s, rtt_s, at}|null, after: {…}|null}` — offset_s = 서버 − PC(초) | `before`가 객체 · 그리고 `k6-window.clock_offset_ms_given`이 숫자. 하나라도 아니면 `clock-offset-bad` |
| `act17-*.json` | §6.4 — 읽는 키: `compensation.done`(합) · `compensation.retry`(합) · `confirm.rejected`(합) · `confirm.uid_conflict` · `confirm.legacy`. `error` 키가 있으면 결측 | **before는 기준점**(예열의 옛 경로 확정 등 앞선 값이 있을 수 있다 — 0이 아니어도 된다) · 판정은 **증분(end − before)**으로 본다: `done` 증분 = DB DONE 작업 수 · `rejected` 증분 ≥ confirm_rejection 행 수(cap 회차는 보고만) · pg 셀 `retry` 증분은 주입 관측 조건(§7 inject-not-observed) |
| `pg-ledger.json` `ops` | 사전 · 값은 0 이상 정수(불리언 아님). 하한: `register` ≥ 원장 항목 중 `tombstone` 거짓인 수 · 결제 op(R · S `pay`, D `authorize`) ≥ `g.ok` 세션 수 · `close` ≥ DONE CLOSE 작업 수 · `capture` ≥ DONE CAPTURE 작업 수(하한이 0이면 키가 없어도 된다 — mock-pg는 부른 op만 키를 만든다) | 어기면 `ledger-missing`(모든 셀) |
| `consistency.json` | §6.3 — 읽는 키: `pay_compensation_mode` · §6.3 기대 0 `pay_*` 7개 · 숫자인 `v_*` 전부(합) | 모드 = plan `mode` · 나머지 0 |
| `drain.json` | §6.5 — 읽는 키: `reason` · `start_ms` · `end_ms` | `reason "empty"` |
| `harness-reasons.txt` | 줄마다 사유 | 빈 파일. 파일이 없으면 `harness-reasons-missing` |
| `timeline-server.jsonl`(base) | 서버 시각 줄 `{"t": ISO(서버 시계), "load": "…"}` 뒤에 docker stats 줄 `{"Name": "seatlab-db-1", "CPUPerc": "12.3%", "MemUsage": "100MiB / 1GiB", …}`(10초마다) | DB CPU = ADR-014 `server_rows(path, offset_s)` → `db_cpu(rows, k6_t0_epoch, k6_end_epoch)`. 줄 시각 = `ts(t) − offset_s`(PC 초). 창 안 `seatlab-db-1` 줄 ≥ ⌈창 ÷ 15초⌉ · 이웃 간격(창 양끝 포함) ≤ 30초 · `seatlab-app-1` 줄은 MemUsage를 읽을 수 있어야 한다(못 읽으면 broken → 결측) |
| `k6-requests.csv.gz`(모든 셀) | k6 CSV — 읽는 열: `metric_name` · `metric_value` · `name` · `status`. 지연 행 = `metric_name == http_req_duration` · `status == "200"` · `name ∈ {confirm, pay_start}`(retry의 c2는 `confirm_retry`라 들지 않는다). 빠진 반복 = `metric_name == dropped_iterations`의 값 합 | (base) confirm 200 수 = 세션 행 중 `c1.st == 200` 수 · pay_start 200 수 = `p.st == 200` 수 · 모든 셀 dropped 합 0. 파일 없음 · 깨짐 = `k6-csv-missing` |
| `pc-link.json` | 읽는 키: `readable` · `down` | `readable true` · `down 0`. 파일 없음 · readable ≠ true · down이 숫자 아님 = `pc-link-missing`, down > 0 = `pc-link-down` |
| `timeline-client.jsonl` | 읽는 키: `t`(ISO) · `mem_avail_kb`. 창 = [k6_t0_epoch, k6_end_epoch](PC 초 · 양끝 포함) | 창 안 줄 ≥ 1 · 못 읽는 줄 0(맨 끝 찢긴 한 줄 제외) · 이웃 간격(창 양끝 포함) ≤ `values.pc_mem_gap_max_s` → 아니면 `pc-mem-missing`. 최솟값 < `values.pc_mem_run_min_kb` = `pc-mem-low` |

## 7. 회차 무효 사유 (`summarize17.py --rep` → `status`) [초안]

무효 회차는 판정에 들어가지 않는다(M17-3).

- status 문자열 = `invalid:` + **[하네스 사유(`harness-reasons.txt` 줄 순서 · 중복 줄 하나로)] + [요약기 사유(사전순 정렬 · 하네스 사유와 같은 것은 빼고)]**를 쉼표로 이은 것이다. 사유가 없으면 `ok`다.
- `summary17.json.reasons`도 같은 순서의 목록이다.
- dump(원장 · DB) 중 하나라도 없으면 모든 세션의 분류는 `invalid:dump-missing`이 된다(그래서 `session-invalid`도 함께 붙는다).

| 사유 | 조건 |
|---|---|
| `plan-missing` | plan.json 없음 · 깨짐(→ 요약기 exit 2) |
| `sessions-missing` · `sessions-broken` | 세션 로그 없음 · 깨진 줄 > 0(§3) |
| `sessions-count-mismatch` | 행 수 ≠ `n_total`, 또는 종류별 행 수 ≠ `planned` |
| `session-dup` | 같은 sid 두 줄 이상 |
| `session-attrs-mismatch` | T · P · G · R · L이 `plan17.session_attrs(rep, sid, params)`와 1e-6 넘게 다름 · 좌석 · 사용자 · 종류 규칙 어긋남 |
| `session-invalid` | `invalid:*` 세션 ≥ 1(세부 = summary17 `invalid_detail`) |
| `ledger-missing` · `db-dump-missing` · `consistency-missing` · `act-missing` · `app-config-missing` · `seed-check-missing` · `inject-missing` · `drain-missing` · `kill-missing` | 필수 파일 없음 · 깨짐(§1) · `consistency-missing`은 필수 키 결측 · 숫자 아님(§6.3)도 · `act-missing`은 필수 지표 null · failed_names(§6.4)도 · `ledger-missing`은 `ops` 형식 · 하한 위반(§6.11)도 |
| `config-mismatch` · `compensation-mode-mismatch` | `plan17 --config-check` exit 1(사유 그대로) |
| `consistency-mode-mismatch` | `pay_compensation_mode` ≠ 후보 모드 |
| `seed-mismatch` | 시드 확인: 회차 1 · 좌석 `seed.seats` · 모두 AVAILABLE · 홀드 · 예약 · 쿼터 0 · flyway에 15 있음 |
| `pg-not-reset` | 원장 · DB에 세션 uid가 아닌 uid가 있다(예열 · 앞 회차 잔재 — M17-4). 세션 uid = 행의 `uid` ∪ `p.uid` ∪ **이어진 uid**. 이어진 uid(셀 무관): end=`pay_start`이고 `p`가 미확정(st 0 또는 ≥ 500 — payment-start는 intent 커밋 뒤 PG 등록을 하므로 503 · 응답 유실이어도 intent가 남을 수 있다)인 세션마다, DB intent 중 `hold_id = h.hid` · `user_id = user`인 것의 uid. 그런 uid의 shape는 G1에 그대로 들어간다. 이은 수 = summary17 `record.ps_linked_uids`, 그중 세션이 `app_dead`인 수 = `kill.left_intents`(kill 셀) |
| `pg-reset-during-run` · `inject-mismatch` · `inject-leak` | §6.6 |
| `inject-not-observed` | pg 셀인데 **주입 결정 수 하한 = 0**. 계산: 적용된 faults(inject.json 마지막 faults 응답 · 없으면 plan `pg.faults`)의 규칙마다(failRate · unknownRate가 둘 다 0인 op는 뺀다) `java.util.Random(seed × 31 + op.hashCode())`(Java 64비트 곱 · String.hashCode)의 `nextDouble` 열을 차례로 걷는다 — `x < failRate` = FAIL(처리 안 함), `x < failRate + unknownRate` = UNKNOWN(처리함), 그 밖 = PROCEED(처리함). 처리한 수가 원장 `ops[op]`에 닿으면 멈춘다. 만난 FAIL + UNKNOWN의 op 합이 0이면 이 사유(마지막 처리 뒤 FAIL은 세지 않는다 — 하한). 근거: mock-pg는 변경 호출마다 주입 결정을 먼저 하고, 처리로 간 호출만 원장 ops를 1 늘린다(멱등 재생 · 404 포함 · 조회 GET은 결정도 ops도 없다). **그리고 관측 조건**(AND — 둘 다 있어야 통과): act17 `compensation.retry` 증분(end − before) > 0 또는 attempts > 1인 작업 ≥ 1. 결정 수 > 0이어도 관측이 없으면 이 사유 |
| `inject-undecidable` | 위 걷기가 `ops[op] × 100 + 10,000`걸음 안에 처리 수에 닿지 못함(적용 설정과 원장이 어긋남) |
| `kill-not-observed` | kill 셀 §6.7 유효 조건 실패(시각 대조 · 재기동 탐지 포함) |
| `app-restarted` | base · pg 셀인데 act17 `process_start_s`가 before ≠ end(§6.4) |
| `g2-time-missing` | G2 대상 작업(CLOSE · DONE · source REJECTION 또는 SWEEP) 중 `done_ms`가 숫자가 아니거나, REJECTION인데 그 uid의 `confirm_rejection.rejected_ms`가 없거나, SWEEP인데 `created_ms`가 없음(대체하지 않는다 — 표본 수 = 대상 작업 수) |
| `drain-poll-failed` | drain.json reason = cap이고 성공한 배수 조회가 0(§6.5). **reason = cap 자체는 무효가 아니다**(남은 일 = G1 `open_after_drain` · `unfinished_tasks`) |
| `dump-race` | (배수 empty) 덤프 원장 ≠ 재조회 원장 ops 합 — run17 harness 사유(§6.5 덤프 반복) |
| `dump-unstable` | (배수 cap) 덤프 반복 5번 모두 원장이 움직였다 — 안정 스냅숏 없음 · run17 harness 사유 · 회차 무효(재측정 대상 — 하네스) |
| `ledger-recheck-failed` | 두 번째 원장 조회가 1회 재시도 뒤에도 실패(경합과 구분 — harness 사유) |
| `no-late-rejection` | **M17-6**: late 세션 중 c1이 거부 응답(§5 D 거부 쌍)인 수 = 0 |
| `no-sweep-close` | **M17-6**: noconf 세션 uid 중 CLOSE 작업 source = SWEEP · **status = DONE**인 수(안전망 종결 수) = 0 |
| `app-metric-mismatch` | (kill 셀 밖 · 배수 empty 회차만) `act17-end − act17-before`의 `compensation.done` 합 ≠ DB의 DONE 작업 수, 또는 `confirm.rejected` 증분 < confirm_rejection 행 수. 배수 cap 회차는 같은 값을 summary17 `app_metric`(`checked: false`)에 보고만 한다 |
| `k6-exit-<n>` · `k6-exit-unknown` · `k6-plan-mismatch` · `clock-offset-bad` | k6 종료 코드 ≠ 0 · k6-window.json 없음 · §6.8 · §6.11 |
| `harness-reasons-missing` | harness-reasons.txt 없음 |
| `db-cpu-missing` · `latency-missing` | (base 셀만) G3 입력 결측 — ADR-014 `db_cpu` missing · CSV에 confirm · pay_start 200 표본 0 · **CSV 200 수 ≠ 세션 로그 성공 수**(§6.11) |
| `k6-csv-missing` | k6-requests.csv.gz 없음 · 깨짐(모든 셀) — 열 수가 머리와 다른 행 · 지연 행(`http_req_duration` 200 · confirm · pay_start)이나 `dropped_iterations` 행의 `metric_value` 결측 · 숫자 아님 포함(무음으로 건너뛰지 않는다) |
| `dropped-iterations` | CSV `dropped_iterations` 합 > 0(모든 셀 — ADR-016 선례) |
| `pc-link-missing` · `pc-link-down` | §6.11 pc-link.json — **pc-link-down은 환경 사유** |
| `pc-mem-missing` · `pc-mem-low` | §6.11 timeline-client.jsonl — **pc-mem-low는 환경 사유** |
| `env-send-lag` | 확정 송신 지연(`c1.ts − at`, end=done 세션)이 `values.send_lag_max_ms`를 넘는 세션 ≥ 1, 또는 그 값이 plan에 없음 — **환경 사유(정지 계열)**. 분포 = summary17 `send_lag_ms` |

- **환경 사유 판별**(campaign17 `kind_of` · summarize17 `env_kind` — campaign16 우선순위): 사유 목록에 `pc-link-down` 또는 `pc-mem-low`가 하나라도 있으면 env(다른 사유와 섞여도), 사유가 전부 정지 계열(`env-stall` · `env-send-lag`)이면 env, 그 밖 무효 · status 없음은 harness, `ok`는 valid. 재측정 상한은 env · harness 따로 자리마다 2번이다.
- `env-stall`(ADR-016의 응답 정지 덩어리 분류)은 이식하지 않았다 — 그 분류는 ADR-011 측정기(k6 탐침 PC · 서버) · GC 로그 · 대기 표본을 함께 보는데 run17은 측정기를 돌리지 않는다. 세션 일정이 밀린 정지는 `env-send-lag`가 잡는다. 이름은 판별 규칙에 남겨 둔다.

## 8. 판정 (회차 · 캠페인) [초안 — 문턱 · 묶는 법은 측정 전 고정에서 확정]

**G1 정합**(회차마다 · 유효 회차 전부 · 모드 ≠ none): 아래가 전부 0이다.

1. uid 끝 상태 수(§4 shape — 세션 uid뿐 아니라 덤프에 나오는 모든 uid):
   - 기대 0: `orphan` · `double_reservation` · `double_refund` · `refunded_with_seat` · `voided_with_seat` · `auth_open` · `open_unpaid` · `substituted_unpaid` · `no_ledger` · `no_intent`
   - R · S의 `seat_moneyless`도 기대 0이다(클라이언트가 결제 뒤에만 확정한다). D의 `seat_moneyless`는 G-비용이다.
2. DEAD 작업 수(compensation_task status = DEAD).
3. 배수 뒤 OPEN intent 수(`open_after_drain` — drain empty와 같다 · 그래도 덤프로 다시 센다) · **미완료 작업 수**(`unfinished_tasks` — status PENDING · IN_PROGRESS, CLOSE · CAPTURE 모두 — 배수 상한에 닿으면 여기로 온다).
4. consistency `pay_*` 기대 0 항목 · `v_*` 위반 합.
5. 세션 `integrity:*` 수 · `wronged_open` 수 · `mode_fail:*` 수.

**G2 보상 완료**(회차 p99 — 후보 × 셀):

- 표본은 CLOSE 작업 중 status DONE · source ∈ {REJECTION, SWEEP}이다.
- 시간 = `done_ms − 시작`이다. 시작은 REJECTION이면 그 uid의 `confirm_rejection.rejected_ms`, SWEEP이면 작업 `created_ms`다.
- 통과 조건: base 셀은 유효 회차마다 p99 ≤ X(`g2_x_s`), pg · kill 셀은 p99 ≤ Y(`g2_y_s`).
- **X · Y의 출처 = 사용한 회차들의 `plan.json values.g2_x_s · g2_y_s`**(측정 전 고정 값이 plan17 한 곳에서 회차 폴더로만 온다 — 요약기 상수가 아니다). **회차 사이 대조**(M17-2): 사용 회차 전부의 `plan.json values` 전체 · `meta.json sha` · `meta.json harness_hash`가 같아야 한다. 하나라도 다르거나 없으면 모든 후보 `pass = "insufficient"` · `choice null` · campaign17 `runs_consistent = false`다.
- **백분위 계산법**(p50 · p90 · p99 — G2 · G3 지연 · 돈 잃은 시간 공통): 오름차순 정렬 `xs`에서 `xs[min(n − 1, ⌊n × q⌋)]`(ADR-014 summarize `p99`와 같은 규칙 — 보간 없음). n = 0이면 null.
- 표본이 0이면(회차 p99 = null) **판정 불가**다. 캠페인 `g2.<cand>.<cell>.no_sample = true`이고, 그 후보는 다른 판정된 실패(G1 실패 · G2 문턱 초과 · G3 실패)가 없으면 `pass = "insufficient"`다(판정된 실패가 있으면 `false`).
- S의 대체 성공(SUBSTITUTE — 거부 트랜잭션 안에서 끝남)은 표본에 넣지 않고 따로 보고한다.

**G3 비용**(base 셀 · R 대비):

- 지표는 셋이다.
  - DB CPU 평균: ADR-014 `db_cpu(timeline-server, k6 t0, k6 끝)`.
  - 확정 p99: `http_req_duration{name=confirm, status=200}`.
  - payment-start p99: `{name=pay_start, status=200}`.
- 후보마다 3회의 [최소, 최대] 범위와 중앙값을 낸다.
- `much_higher` = R과 범위가 안 겹치고, 후보 범위가 위쪽이고, 중앙값 비 > 1.10이다. 하나라도 much_higher면 G3 실패다. R은 늘 통과다.
  - 범위는 **닫힌 구간** [최소, 최대]다. 맞닿으면(후보 최소 = R 최대) 겹침이다. 즉 비겹침 · 위쪽 = `후보 최소 > R 최대`(엄격).
  - 중앙값 = 3개의 가운데 값(`statistics.median`) · 비 = 후보 중앙값 ÷ R 중앙값 · `> 1.10`도 엄격.
  - 지표 값이 셋 다 숫자가 아니면 그 후보 G3 `ok = false`다.

**순서**:

- 유효 회차가 셀마다 3이 아니면(재측정 상한 뒤) 그 후보는 `insufficient`다(판정 불가). **R의 pass가 `"insufficient"`이면(회차 부족 · G2 표본 0 등 이유 무관) G3 기준이 없으므로 모든 후보가 insufficient다.**
- `choice`: D → R → S 순서로 본다. `pass = true`면 그 후보를 고르고 멈춘다. `false`면 다음 후보로 간다. **`"insufficient"`면 멈추고 `choice = null`이다**(위 후보가 판정 불가인데 아래 후보로 내려가지 않는다 — ADR-016 undetermined 선례).
  - 예: D insufficient · R 통과 → `null`. D 실패 · R 통과 → `R`. D · R 실패 · S 통과 → `S`. 모두 실패 → `null`.

**G-비용 보고**(판정에 넣지 않는다):

- D 돈 없는 좌석 수(`seat_moneyless`)
- S 대체 비율 = `substituted ÷ (substituted + late의 wronged_refunded)`
- 사용자 돈 잃은 시간(R · S): REFUNDED uid의 `closedAt − capturedAt` 분포(D는 0 — 승인 취소 시각 분포는 따로)
- PG 409 deadline 수(`g.code == DEADLINE_PASSED` 세션 + 원장 deadline 거부는 ops로 못 가르니 세션 기준)
- 출처별 작업 수(REJECTION · SWEEP · CONFIRM)
- attempts 분포
- uid_conflict · legacy 증분
- `app_dead` 세션 수 · 그 uid의 shape 분포(`cost.app_dead_shapes` — uid 없으면 `none`)
- D 승인 취소까지 시간: VOIDED uid의 `closedAt − authorizedAt` 분포(`cost.void_time_s` — 초 · {n, p50, p99, max})

## 9. outcome17 · summarize17 시그니처 · 출력

### 9.1 `outcome17.py`(모듈 · CLI)

```python
CATEGORIES = ("success", "not_paid", "pre_reject", "wronged_refunded", "wronged_voided", "wronged_substituted", "wronged_open", "app_dead")
SHAPES = (...)                      # §4 표의 shape 이름 전부
CONFIRM_REJECT = frozenset({(404, "HOLD_NOT_FOUND"), (409, "HOLD_EXPIRED"), (409, "SEAT_NOT_HELD")})
PAY_START_REJECT = frozenset({(409, "HOLD_EXPIRED"), (404, "HOLD_NOT_FOUND")})
K6_TIMEOUT_ERROR = 1050
KILL_TOL_MS = 1000

def iso_ms(s: str | None) -> float | None             # PG 원장 ISO → epoch ms
def read_sessions(path) -> tuple[list[dict], int] | None   # (행 목록, 깨진 줄 수) · 파일 없음 = None — §3 규칙
def end_states(db_dump: dict, ledger: dict) -> dict[str, dict]   # uid → End(§4 — shape 포함)
def shape(end: dict) -> str                             # §4 표(end_states가 부른다 · 단독 시험용)
def kill_windows(kill: dict | None) -> list[tuple[float, float]]  # §6.7 · None → []
def classify(row: dict, end: dict | None, cand: str, kills: list, plan: dict) -> str   # §5
```

CLI: `outcome17.py <회차 폴더>` → 표준 출력 JSON 한 줄, exit 0. 입력이 없으면 exit 2이고 `{"error": "<사유>"}`를 낸다.

- `<사유>`는 파일 이름이 아니라 §7 사유 이름이다. 검사 순서대로 첫째 하나만 낸다: `plan-missing`(plan.json) → `sessions-missing`(sessions17.log.gz · 없으면 sessions17.log) → `db-dump-missing` → `ledger-missing` → `kill-missing`(kill 셀만).

```
{"sessions": [{"sid", "kind", "uid", "cat", "shape"}...(sid 순)],
 "counts": {"<kind>": {"<cat 또는 결함 문자열>": n}},
 "uids": {"<shape>": n}}            # 덤프의 모든 uid(세션 밖 포함)
```

### 9.2 `summarize17.py`

- `summarize17.py --rep <회차 폴더>`:
  - `<폴더>/summary17.json`을 쓰고, 표준 출력 **마지막 줄**에 `ok` 또는 `invalid:<사유,…>`를 낸다.
  - exit 0이다(판정함). 판정할 수 없으면 exit 2다(plan 없음 등 — run17은 `invalid:summarize-failed`로 적는다).
- `summarize17.py --campaign <캠페인 폴더>`:
  - `<폴더>/campaign17.json`을 쓰고, 표준 출력 마지막 줄에 `choice:<R|D|S|none>`을 낸다. exit 0이다.
  - 각 회차의 유효 시도는 `status` = ok인 **가장 늦은 시도**다(`rep<k>` 또는 `rep<k>.attempt<n>`).

`summary17.json`:

```
{"cand", "cell", "rep", "status": "ok"|"invalid:…", "reasons": [..],
 "sessions": {"n": 행 수, "planned": {...}, "by_kind": {"<kind>": {"<cat>": n}}, "invalid_detail": {"<invalid:…>": n},
              "integrity": {"<integrity:…>": n}, "mode_fail": {"<mode_fail:…>": n}},
 "uids": {"<shape>": n},
 "g1": {"ok": bool, "items": {"orphan": n, "double_reservation": n, ..., "dead_tasks": n, "open_after_drain": n,
                               "consistency_pay": {"<pay_*>": n}, "consistency_v_sum": n, "integrity": n, "wronged_open": n, "mode_fail": n}},
 "g2": {"n": n, "p50_s", "p90_s", "p99_s", "max_s", "by_source": {"REJECTION": {"n", "p99_s"}, "SWEEP": {...}}, "substitute_n": n},
 "g3": {"db_cpu_mean": x|null, "confirm_p99_ms": x|null, "pay_start_p99_ms": x|null},      # base 셀만 값 · 그 밖 null
 "m17_6": {"late_rejections": n, "noconf_sweep_closes": n},
 "cost": {"moneyless": n, "substitute_ratio": x|null, "money_lost_s": {"n", "p50", "p99", "max"}, "pg_deadline_409": n,
          "tasks_by_source": {...}, "attempts": {"<k>": n}, "uid_conflict": n|null, "legacy": n|null, "app_dead": n},
 "drain": {"reason", "len_s"}, "kill": {"events": n, "restarted": bool, "timing_ok": bool, "valid": bool}|null}
```

- 덧붙는 키(위 형식에 더해질 뿐 — 기존 키는 그대로): `g1.items`에 shape 이름 · `seat_moneyless`(R · S만 셈 · D는 0) · record-level 대조 `record_pay_ok_not_in_ledger` · `record_pay_ok_no_intent` · `record_ledger_paid_without_k6_pay`(모두 기대 0 — k6 결제 성공 세션 ↔ 원장 결제 흔적 ↔ DB intent)가 있다.
- 최상위 `record` = `{k6_pay_ok, ledger_paid, db_intents, pay_ok_not_in_ledger, pay_ok_no_intent, ledger_paid_without_k6_pay}`(보고).
- 최상위 `inject` = pg 셀이면 `{"<op>": {"processed", "fail", "unknown", "reached": bool}}`(§7 inject-not-observed의 걷기 결과 — `processed` = 걷는 동안 만난 처리(PROCEED · UNKNOWN) 수. `reached`면 원장 `ops[op]`와 같고, 상한에서 멈추면 그보다 작다), 그 밖 셀 · 원장 없음은 null.
- `cost.void_time_s` · `cost.app_dead_shapes` · `kill.left_intents`(§7 pg-not-reset — 이어진 uid 중 세션이 app_dead인 수).
- `record.ps_linked_uids`(§7 이어진 uid 수) · `g1.items.unfinished_tasks`(§8).
- `kill`: `restarted` = 재기동 사실(StartedAt 둘이 다름 · process_start_s before ≠ end) · `timing_ok` = §6.7 시각 대조 · `valid` = §6.7 유효 조건 전체(거짓이면 kill-not-observed).
- 최상위 `app_metric` = `{done_delta, db_done, rejected_delta, rejections, mismatch, checked}`(kill 셀 · act 결측이면 null · `checked = false` = cap 회차라 대조 안 함).
- 최상위 `send_lag_ms` = `{n, p50, p99, max, over}`(ms — end=done 세션의 `c1.ts − at` · over = 허용치 초과 수 · 허용치 없음이면 null) · `pc_mem` = `{min_kb, n, gap_max_s}` 또는 null.
- campaign17 `g2.<cand>.<cell>`에 `no_sample`(표본 0 회차 있음) · `over`(문턱 초과 회차 있음)가 더해진다.
- `g1.ok` = items의 값이 전부 0(사전이면 그 안의 값이 전부 0)이고 dump가 있음.

`campaign17.json`:

```
{"plan_fixed": bool, "runs_consistent": bool, "runs": {"<cand>/<cell>/rep<k>": {"status", "attempts": n, "used": "<폴더 이름>"}},
 "g1": {"<cand>": {"ok": bool, "fail_runs": [..]}},
 "g2": {"<cand>": {"<cell>": {"p99_s": [..], "limit_s": x, "ok": bool}}},
 "g3": {"<cand>": {"<metric>": {"range": [lo, hi], "median": x, "ratio_vs_R": x, "much_higher": bool}, "ok": bool}},
 "pass": {"<cand>": true|false|"insufficient"}, "order": ["D", "R", "S"], "choice": "<cand>"|null, "cost": {"<cand>": {…회차 합 · 분포}}}
```

## 10. 알려진 한계 · 주의 (구현 · 측정 전 고정에서 볼 것)

1. **PG 주입은 capture · close만 — 확정**(사용자 결정 2026-11-14 · 명세 §0 Q/A 2 정정 완료 — 조회 GET 주입은 mock-pg 변경이 필요해 하지 않는다): mock-pg의 `GET /payments/{uid}`는 주입 단계(`Faults`)를 거치지 않는다. 주입 지점은 변경 호출(register · pay · authorize · capture · close)뿐이다.
   - 명세 §0 '조회 GET 주입'은 앱(mock-pg) 변경 없이는 할 수 없다. 그래서 pg 셀은 capture · close만 주입한다.
   - 결과 모름(unknownRate)이 생기면 워커의 다음 시도가 GET 조회를 하므로 조회 경로 자체는 탄다.
2. **section 하나 — 확정**(사용자 결정 2026-11-14): `/internal/reset`은 모든 좌석을 section `A`로 만든다. 그래서 'section 고르게'는 자명하게 성립하고(한 section), S 대체는 같은 회차의 여유 좌석에서 집는다.
   - 좌석 배치는 여유 좌석(id 1..spare)을 앞에, 세션 좌석을 뒤에 둔다. 그러면 대체(`ORDER BY id … SKIP LOCKED`)가 아직 오지 않은 세션의 좌석을 빼앗지 않는다.
3. **예열**: ADR-015 `warmup15.js`(PAY_START=1)는 payment-start 뒤 `pay-…` uid로 확정한다. 지금 이미지에서는 409 SEAT_NOT_HELD이고, intent · PG 등록이 남는다.
   - run17은 PAY_START=0 예열을 쓰거나, 예열 뒤 시드 reset(TRUNCATE — V15 표 포함)과 PG reset을 반드시 한다(§7 `pg-not-reset`이 잡는다).
4. **지연 확정의 갈래**: late 확정이 연장 만료 뒤 5초(안전망 주기) 안에 오면 REJECTION 경로(R · D CLOSE · S 대체)로 간다. 늦으면 안전망이 먼저 닫아 SWEEP 경로(재생 코드 · S도 환불)로 간다.
   - L 분포(`late_range` — 지금 U[0.2, 10]초)가 두 출처의 몫과 S 대체 비율을 정한다. 측정 전 고정에서 정할 값이다.
5. **kill 셀 지표**: 재기동으로 앱 카운터가 0부터 다시 센다. 지표 증분 대조는 kill 셀에서 하지 않는다(§6.4).
6. **결정적 일정**: 회차 시간은 최장 세션(late = T + W 611초 + L)이 정한다. 세션 수를 줄여도 회차는 거의 짧아지지 않는다(`plan17.py --dry-run` 줄이는 안).

## 11. 측정 전 고정 대상(자리표시 — `plan17.py FIXED = False`)

아래 값은 .164 스모크 뒤 측정 전 고정 커밋에서 정한다(M17-2 — 그 뒤 바꾸면 캠페인 무효). 출처는 모두 `plan17.py` 한 곳이고, 회차 폴더의 `plan.json values`로 남는다.

| 값 | 지금 자리표시 | plan17 이름 |
|---|---|---|
| 세션 수 · 도착률 | 1,100 · 20/s | `N_SESSIONS` · `RATE` |
| 세션 종류 비율 | ok 60 · late 20 · noconf 5 · abandon 10 · retry 5(%) | `KIND_WEIGHTS` |
| 생각 시간 T · 결제 시간 P | U[30, 240]초 · 로그정규(m 45초 · σ 0.94 · 상한 300초) | `T_RANGE` · `PAY_*` |
| 확정 간격 G · 재확정 간격 R | U[0, 2]초 · U[0, 2]초 | `CONF_GAP_RANGE` · `RETRY_GAP_RANGE` |
| **지연 분포 L**(연장 만료 뒤) | U[0.2, 10]초 | `LATE_RANGE` |
| 좌석 배수 | 세션 × 1.5 | `SEAT_FACTOR` |
| PG 주입(pg 셀 · capture · close) | delay 200ms · fail 10% · unknown 5% · unknownDelay 5,000ms | `PG_RULE` · `PG_FAULT_OPS` |
| **kill 시각** | k6 시작 뒤 180초 · 760초 | `KILL_AT_S` |
| kill 시각 허용 오차 | 5,000ms(계획 ↔ 실제 kill · k6 시작 대조 — §6.7) | `KILL_TOL_MS`(`values.kill_tol_ms`) |
| 확정 송신 지연 허용치 | 2,000ms(`c1.ts − at` — 넘으면 env-send-lag) | `SEND_LAG_MAX_MS`(`values.send_lag_max_ms`) |
| k6 PC 메모리 하한 · 표본 공백 상한 | 4 GiB · 30초(ADR-014 plan14 값 복사) | `PC_MEM_RUN_MIN_KB` · `PC_MEM_GAP_MAX_S`(`values.pc_mem_run_min_kb` · `pc_mem_gap_max_s`) |
| **배수 창 상한** | **300초**(사용자 결정 2026-11-14 — 600초에서 줄임) | `DRAIN_CAP_S` |
| 시간 예산 | **12시간 = 최악 기준**(배수 상한을 매 회차 다 쓴 합 — plan17 dry-run `problems`와 campaign17 시작 조건 ③이 최악 ≤ 12시간을 검사 · 사용자 결정 2026-11-14) | `BUDGET_HOURS` |
| G2 문턱 X · Y | 30초 · 120초 | `G2_X_S` · `G2_Y_S` |
| 재측정 상한 | 자리마다 환경 2 · 하네스 2(ADR-015와 같다) | campaign17.sh |
| 이미지 고정 | 측정 SHA의 앱 · mock-pg · DB · Redis 이미지 ID | `run17.sh --prepare-image` → images.json |
| 워커 · 안전망 손잡이 | 앱 기본값(워커 1초 · 50 · 리스 30초 · 시도 10 · 백오프 1~60초 / 안전망 5초 · 200) — 값 기록만 | `COMP`(configprops 대조) |

