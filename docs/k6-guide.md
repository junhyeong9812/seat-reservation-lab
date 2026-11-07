# k6 안내서 — 이 저장소에서 쓴 문법·설정

> 대상: `k6/ADR-001` ~ `k6/ADR-015`의 시나리오(`scenarios/*.js`)와 실행기(`scripts/run.sh` · `plan*.py`).
> 코드 조각은 모두 실파일에서 복사했다(`경로:줄`). 일반 설명에는 k6 공식 문서 링크를 달았고, 이 문서를 쓰며 확인하지 못한 동작은 **확인 필요**로 표시했다.
> k6 버전: 이 PC의 `k6 version` = v2.3.0, 측정기용 바이너리 `~/.local/opt/k6-v2.2.0/k6` = v2.2.0. 과거 캠페인이 어느 버전으로 돌았는지는 회차별 `meta.json`의 `k6_version` 칸이 정본이다(이 문서는 그 칸을 읽지 않았다).

## 목차

1. [실행 모델 — 생명주기와 VU(가상 사용자)·반복](#1-실행-모델--생명주기와-vu가상-사용자반복)
2. [실행기(executor)와 옵션](#2-실행기executor와-옵션)
3. [HTTP — 요청·params·응답 객체](#3-http--요청params응답-객체)
4. [연결 재사용과 keep-alive](#4-연결-재사용과-keep-alive)
5. [사용자 지표·태그·요약·임계값](#5-사용자-지표태그요약임계값)
6. [환경변수·k6/execution·sleep·시각 맞추기](#6-환경변수k6executionsleep시각-맞추기)
7. [실행 방법 — run.sh가 k6를 부르는 방식](#7-실행-방법--runsh가-k6를-부르는-방식)
8. [이 저장소에서 겪은 함정](#8-이-저장소에서-겪은-함정)
9. [확인 필요 목록](#9-확인-필요-목록)

---

## 1. 실행 모델 — 생명주기와 VU(가상 사용자)·반복

k6 스크립트는 네 단계로 돈다(공식: <https://grafana.com/docs/k6/latest/using-k6/test-lifecycle/>).

| 단계 | 무엇 | 몇 번 도나 | 이 저장소 |
|---|---|---|---|
| init 코드 | 파일 최상단(`import`·`const`·`export const options`·지표 생성) | VU마다 한 번(+ 옵션을 읽으려 한 번 더) | 환경변수 읽기·지표 생성·계획 수 계산 |
| `setup()` | 부하 전에 한 번 | 테스트당 1회 | 시작 시각 `startedAt` 반환 |
| `default` 또는 `exec`가 가리키는 함수 | VU 코드 — 한 번 실행 = **반복(iteration)** 1회 | 실행기가 정한 만큼 | 세션 하나 = 반복 하나 |
| `teardown(data)` | 부하 뒤 한 번 | 1회 | **이 저장소에서는 쓰지 않았다**(grep 0건) |
| `handleSummary(data)` | 끝 요약 데이터를 받아 파일·stdout으로 낸다 | 1회 | `k6-summary.json` 저장 |

- **VU와 반복**: VU는 독립된 JS 실행 환경(자기 전역 변수·연결 풀을 가진 가상 사용자)이고, 반복은 그 VU가 VU 함수를 한 번 끝까지 실행하는 것이다. VU는 반복을 끝내면 다음 반복을 받는다 — 그래서 "세션 하나 = 반복 하나"로 짜면, 세션이 길수록(생각 시간·`sleep`) VU가 더 많이 필요하다(§2.3).
- **`setup` 반환값**은 VU 함수와 `teardown`의 첫 인자로 넘어간다. 이 저장소는 판정 창의 기준 시각으로 쓴다.

```js
// k6/ADR-015/scenarios/confirm-contention.js:82-84
export function setup() {
  return { startedAt: Date.now() };
}
```

```js
// k6/ADR-001/scenarios/s4-throughput.js:38-47
export function setup() {
  return { startedAt: Date.now(), rates };
}

export default function (data) {
  const stage = Math.min(STEPS - 1, Math.floor((Date.now() - data.startedAt) / 1000 / STEP_S));
  const seat = exec.scenario.iterationInTest + 1;           // 1..1,000,000
  const schedule = Math.ceil(seat / SEATS_PER_SCHEDULE);
  hold(schedule, seat, 400000 + exec.scenario.iterationInTest, { stage: String(stage) });
}
```

- `setup()`의 반환값은 요약 데이터의 `setup_data`에도 남는다 — 요약기가 판정 창 t0로 읽는다(`k6/ADR-014/scenarios/s14-flow.js:14` 주석 "t0 = setup 반환 직전 PC 시각(k6-summary setup_data.startedAt)").
- **`handleSummary`**: 반환 객체의 키 = 출력 대상(파일 경로 또는 `stdout`), 값 = 내용. 이 함수를 정의하면 기본 끝 요약 출력이 대체된다(공식: <https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/>).

```js
// k6/ADR-009/scenarios/lib/common.js:80-86
// 요약은 JSON 원본 그대로 저장한다 (표는 summarize.py가 이 파일에서 산출).
export function writeSummary(data) {
  return {
    [`${OUT_DIR}/k6-summary.json`]: JSON.stringify(data, null, 2),
    stdout: `k6 done: iterations=${data.metrics.iterations ? data.metrics.iterations.values.count : 0}\n`,
  };
}
```

`data`에 계획 값을 덧붙여 요약 파일 하나에 같이 남기는 방식도 쓴다(`k6/ADR-015/scenarios/confirm-contention.js:168-172` — `data.adr015 = { rep, rate, ... }`). 예열·측정기 시나리오는 요약을 버리고 한 줄만 낸다(`k6/ADR-001/scenarios/warmup.js:7` `return { stdout: 'warmup done\n' }`).

---

## 2. 실행기(executor)와 옵션

`options.scenarios` 아래 이름마다 실행기 하나를 둔다. 한 실행에 여러 시나리오를 함께 돌릴 수 있고(ADR-014·015), 시나리오마다 `exec`로 다른 함수를 부른다(공식: <https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/>).

이 저장소에서 실제로 쓴 실행기는 셋이다(`grep "executor:"` 전수): **per-vu-iterations · constant-arrival-rate · ramping-arrival-rate**. `constant-vus` · `shared-iterations` · `ramping-vus`는 쓰지 않았다.

### 2.1 per-vu-iterations — "N명이 한 번씩 동시에"

VU 수와 VU당 반복 수를 고정한다. 동시 경합 실험(S1 같은 좌석 · S2 같은 사용자 · S7)에 썼다.

```js
// k6/ADR-001/scenarios/s1-same-seat.js:8-15
export const options = {
  scenarios: { s1: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '2m' } },
  summaryTrendStats: TREND_STATS,
};

export default function () {
  hold(1, SEAT_ID, 100000 + __VU, { seat: String(SEAT_ID), user: String(100000 + __VU) });
}
```

- `vus` = VU 수, `iterations` = VU마다 반복 수, `maxDuration` = 이 시나리오의 최대 길이(넘으면 남은 반복은 버려진다 — 아래 2.4).
- `__VU`(1부터 시작하는 VU 번호)로 사용자·좌석을 VU마다 다르게 준다(`k6/ADR-001/scenarios/s2-same-user.js:14` `200000 + Math.floor((__VU - 1) / PER_USER)`).
- VU 1,000개가 "동시에" 시작하지만 요청이 서버에 정확히 같은 순간 닿는다는 보장은 아니다 — 시작 동기화의 정밀도는 **확인 필요**.

### 2.2 constant-arrival-rate — "초당 R개 반복을 시작"

반복 **시작 속도**를 고정한다(열린 모델 — 응답이 느려져도 도착은 줄지 않는다). 이 저장소의 주력이다(공식: <https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/>).

```js
// k6/ADR-015/scenarios/confirm-contention.js:61-66
export const options = {
  scenarios: {
    main: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${durMs}ms`, preAllocatedVUs: N('PRE_VUS_MAIN'), maxVUs: N('MAX_VUS_MAIN'), gracefulStop: GRACE, exec: 'mainFlow' },
    rehold: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${durMs}ms`, preAllocatedVUs: N('PRE_VUS_REHOLD'), maxVUs: N('MAX_VUS_REHOLD'), gracefulStop: GRACE, exec: 'reholdFlow' },
  },
```

| 옵션 | 뜻 | 이 저장소의 쓰임 |
|---|---|---|
| `rate` · `timeUnit` | `timeUnit`마다 `rate`개 반복 시작 | `timeUnit: '1s'`가 기본. 소수 도착률은 `rate: MAP_RATE * 1000, timeUnit: '1000s'`로 정수화(`s14-flow.js:61`). 고정 간격 탐침은 `rate: 1, timeUnit: '<주기>ms'`(`s14-flow.js:68`) |
| `duration` | 도착을 거는 시간 | ms 단위 문자열도 쓴다(`${durMs}ms`) — 끝 경계 처리는 §8.3 |
| `preAllocatedVUs` | 시작 전에 미리 만들어 둘 VU 수 | 부족하면 반복을 버린다(2.4) |
| `maxVUs` | 실행 중 더 만들 수 있는 상한 | 부족할 때 k6가 VU를 늦게 만든다 — 그 사이 반복은 버려진다(`plan14.py:71` 주석) |
| `gracefulStop` | `duration`이 끝난 뒤 진행 중 반복이 끝나길 기다리는 시간. 지나면 반복을 끊는다 | 창 끝에 시작한 세션(TTL + 재선점)이 끝날 시간 — `plan15.py:56` `GRACE_S = TTL_S + REHOLD_UNTIL_S + 7` |
| `exec` | 이 시나리오가 부를 export 함수 이름(없으면 `default`) | `mainFlow` · `reholdFlow` · `blind` · `mapFlow` · `pr1`… |
| `startTime` | 테스트 시작 뒤 이 시나리오를 늦게 시작 | ADR-014 탐침 `startTime: \`${N('PR_START_S')}s\``(`s14-flow.js:69`) |

gracefulStop 공식: <https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/graceful-stop/>. 끊긴 반복은 `finally`가 돌지 않을 수 있다는 관찰이 있다 — §8.6.

### 2.3 ramping-arrival-rate — 계단식 도착률 (S4 처리량 한계)

`startRate`에서 `stages`의 `{target, duration}`을 따라 도착률을 바꾼다(공식: <https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-arrival-rate/>). 이 저장소는 계단마다 "1초 만에 올리고 나머지는 평탄"으로 만든다.

```js
// k6/ADR-001/scenarios/s4-throughput.js:25-33
export const options = {
  scenarios: {
    s4: {
      executor: 'ramping-arrival-rate',
      startRate: START, timeUnit: '1s',
      stages: stages.flatMap((s) => [{ target: s.target, duration: '1s' }, { target: s.target, duration: `${STEP_S - 1}s` }]),
      preAllocatedVUs: Number(__ENV.PRE_VUS || 500), maxVUs: Number(__ENV.MAX_VUS || 10000),
    },
  },
```

### 2.4 버린 반복(dropped_iterations)과 ADR-015의 VU 크기 고치기

공식 문서(<https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/>): 도착률 실행기는 **빈 VU가 없으면 그 반복을 버리고** `dropped_iterations`를 센다. per-vu-iterations는 `maxDuration` 전에 다 못 끝내면 버린다.

필요한 VU 수 ≈ 도착률 × 반복 수명(초). ADR-015는 세션 수명이 TTL(30초) 이상이라 VU가 많이 든다. 스모크에서 rehold 흐름이 반복을 버렸고(`docs/adr/ADR-015-confirm-atomicity.md:935` "계획 회차 rep1~3의 … 최대 동시 세션(④ race rehold 1038 · 1039 · 1014)이 옛 미리 만든 VU 1000을 넘었다"), 커밋 29e09aef에서 계산식을 바꿨다.

```python
# k6/ADR-015/scripts/plan15.py:64-68
LIFE_S = {"main": TTL_S + 1, "rehold": TTL_S + REHOLD_UNTIL_S}                         # 흐름별 세션 수명(초) — main ≈ TTL + 1초 · rehold ≈ TTL + 재선점 끝 13초
PRE_VUS_HEADROOM = 1.3                                                                   # 미리 만든 VU = 평균 동시 세션(도착률 × 수명 × ρ) × 1.3 — k6는 빈 VU가 없으면 그 반복을 버리고(dropped) VU를 새로 만든다.
                                                                                          #   스모크 smoke15-20261105: rehold 45/도착률(여유 약 5%) → ④ race 평균 약 980 대 1000 · cost 최소 20 → 누락 13 · 29(G-H)
PRE_VUS_MIN_S = 1                                                                        # 최소 = 도착률 × 1초(cost rehold는 ρ = 0이라 즉시 끝나도 k6 정지 순간 한 번에 몰린다)
MAX_VUS_FACTOR = 1.5
```

```python
# k6/ADR-015/scripts/plan15.py:159-163
    longer = xb * (PAYMENT_WINDOW_S - PAY_START_BEFORE_E_S)
    rho = RHO[cell]
    pre = {f: math.ceil(PRE_VUS_HEADROOM * c["rate"] * (v + longer) * (rho if f == "rehold" else 1)) for f, v in LIFE_S.items()}
    pre = {f: max(v, 20, math.ceil(c["rate"] * PRE_VUS_MIN_S)) for f, v in pre.items()}
    mx = {f: math.ceil(v * MAX_VUS_FACTOR) for f, v in pre.items()}
```

즉 `PRE_VUS = ceil(1.3 × 도착률 × (수명 + ④ 연장분) × ρ(rehold만))`, 하한 `max(20, 도착률 × 1초)`, `MAX_VUS = 1.5 × PRE_VUS`.
ADR-015에서 dropped가 치명적인 이유: main·rehold 두 시나리오가 **같은 반복 번호로 같은 세션 속성**을 다시 뽑기 때문에, 한쪽이 반복을 버리면 둘이 어긋난다 → 회차 무효(`docs/adr/ADR-015-confirm-atomicity.md:622`, 판정 `:692` `dropped-iterations`). 요약기는 CSV에서 시나리오별로 센다(`k6/ADR-015/scripts/summarize.py:140-141`).
부작용: VU가 많아지면 VU 하나가 반복 사이에 오래 쉰다 → §4.3의 EOF로 이어졌다(`ADR-015-confirm-atomicity.md:936`).

ADR-013 가용성 탐침은 반대로 `preAllocatedVUs = maxVUs = 20`으로 고정해, 버린 반복을 **실패 신호**로 센다(`k6/ADR-013/scenarios/avail-probe.js:2` 주석).

---

## 3. HTTP — 요청·params·응답 객체

### 3.1 http.post · http.get · http.request · http.batch

```js
// k6/ADR-001/scenarios/lib/common.js:22-27
export function hold(scheduleId, seatId, userId, tags = {}) {
  const res = http.post(`${BASE_URL}/api/schedules/${scheduleId}/seats/${seatId}/hold`, null, {
    headers: { 'X-User-Id': String(userId) },
    tags: { name: 'hold', ...tags },
    timeout: '30s',
  });
```

- `http.post(url, body, params)` · `http.get(url, params)`(`s14-flow.js:160` 좌석 맵). 본문 없는 POST는 `null`.
- ADR-014부터는 요청을 **객체로 먼저 만들고** 보낸다 — 보내기 전에 헤더를 덧붙일 수 있게(§4).

```js
// k6/ADR-014/scenarios/lib14.js:92-97 · 122-124
export function holdReq(scheduleId, seatId, userId, name) {
  return {
    method: 'POST', url: `${BASE_URL}/api/schedules/${scheduleId}/seats/${seatId}/hold`, body: null,
    params: { headers: { 'X-User-Id': String(userId) }, tags: { name: name || 'hold' }, timeout: '30s' },
  };
}
export function send(req) {
  return http.request(req.method, req.url, req.body, req.params);
}
```

- `http.batch([...])`: 여러 요청을 **병렬로** 보내고 응답 배열을 같은 순서로 돌려준다(공식: <https://grafana.com/docs/k6/latest/javascript-api/k6-http/batch/>). 같은 홀드에 확정 두 개를 동시에(이중 확정) 보낼 때 쓴다. 객체 형식 `{method, url, body, params}`을 그대로 받는다.

```js
// k6/ADR-015/scenarios/confirm-contention.js:130-133
  if (a.dup) {
    const rs = http.batch([confirmReq(b.holdId, user, 'confirm'), confirmReq(b.holdId, user, 'confirm')]);
    const te = srvNow();
    rs.forEach((r, i) => conf.add(1, confirmTags(r, cb, i + 1, seat, fl, sentSrv, te, refMs, latestMs)));
```

  배치 안 두 요청이 서버에 닿는 시각 차는 측정하지 않았다 — "같은 송신 시각"으로 기록만 한다(`s14-flow.js:225`). 동시 연결 수 상한(`batch` · `batchPerHost` 옵션)은 기본값 그대로다(`k6 inspect` 출력에서 `null`).

### 3.2 params — headers · tags · timeout

공식: <https://grafana.com/docs/k6/latest/javascript-api/k6-http/params/>

- `headers`: 사용자 식별 `X-User-Id`, JSON 본문이면 `Content-Type`, 연결 처방 `Connection: close`(§4).
- `tags.name`: 요청 이름. 공식 문서의 URL 묶기(<https://grafana.com/docs/k6/latest/using-k6/http-requests/>)처럼 동적 URL이 시계열을 폭증시키지 않게 이름을 고정한다. 이 저장소 주석: "name 태그는 k6가 URL 열까지 덮어쓴다(시계열 수 억제). 요청 대상 좌석·사용자는 호출부가 tags로 따로 남긴다"(`k6/ADR-001/scenarios/lib/common.js:21`).
- `timeout`: 요청 시한. 주 흐름 `'30s'`, 측정기 `'5s'`(`k6/ADR-009/scenarios/probe-ping.js:28`). 시한을 넘으면 status 0 · error_code 1050(§3.3).

### 3.3 응답 객체

공식: <https://grafana.com/docs/k6/latest/javascript-api/k6-http/response/>

| 필드 | 이 저장소의 쓰임 |
|---|---|
| `res.status` | 201 선점 · 200 확정 · 409 비즈니스 거절 · **0 = 응답 없음**(연결 실패·EOF·시한 초과) |
| `res.timings.duration` | 요청 지연(ms) — Trend에 넣거나 태그 `rt`로 남김. 공식 정의상 보내기+기다리기+받기(연결 수립 제외 — **확인 필요**: 이 문서에서 공식 페이지 문구를 직접 대조하지 않았다) |
| `res.timings.connecting` | TCP 연결 수립 시간. `> 0`이면 새 연결, `0`이면 재사용 연결 — 재현 스크립트가 이걸로 갈랐다(`gap.js:21`) |
| `res.json('code')` | 본문 JSON의 한 필드. 본문이 JSON이 아니면 예외 → `try/catch` |
| `res.body` | 원문 문자열 — 대량 409에서 JSON 파싱 비용을 피하려 `indexOf`로 찾는다(`k6/ADR-009/scenarios/lib/common.js:56`) |
| `res.headers[...]` | 원인 표지 헤더 `X-Seatlab-Reject` — 대소문자 둘 다 본다(`lib14.js:87`) |
| `res.error` · `res.error_code` | **스크립트에서는 읽지 않는다**(grep 0건). CSV의 `error_code` 열로 요약기가 읽는다(`k6/ADR-015/scripts/summarize.py:115`) |

```js
// k6/ADR-009/scenarios/lib/common.js:29-41
export function errorCodeOf(res) {
  if (res.status === 201) return 'OK';
  if (res.status === 409) {
    try {
      const c = res.json('code');
      if (c) return String(c);
    } catch (e) { /* 본문이 JSON이 아니면 아래 */ }
    return '409-NOCODE';
  }
  return `HTTP${res.status}`;
}

const isError = (res) => res.status === 0 || res.status >= 500;
```

분류 규칙(ADR-001부터): **409는 에러가 아니다 — 5xx·시한 초과·연결 실패(status 0)만 에러**(`k6/ADR-001/scenarios/lib/common.js:1`).

**error_code**(공식: <https://grafana.com/docs/k6/latest/javascript-api/error-codes/>): `1000` = "A generic error that isn't any of the ones listed below", `1050` = "The HTTP request has timed out", `1220` = 상대가 연결을 리셋. 공식 표에는 EOF 전용 코드가 없어, EOF는 1000(일반 오류)으로 나온다. 이 저장소는 그래서 **status 0 + error_code 1000 = EOF 류 하네스 문제**, **1050 = 시간 초과(서버 쪽 신호)**로 따로 센다:

```python
# k6/ADR-015/scripts/summarize.py:39
TIMEOUT_CODE = "1050"   # k6 error_code — 요청 시간 초과(30초 — 5xx와 따로 센다 · 그 밖의 코드 0은 하네스 EOF 류)
```

### 3.4 discardResponseBodies

`true`면 응답 본문을 버려 메모리·CPU를 아낀다(`res.body`가 비게 된다). 본문을 읽을 필요가 없는 측정기·재현에만 켰다.

```js
// k6/ADR-009/scenarios/probe-ping.js:15-16
export const options = {
  discardResponseBodies: true,
```

ADR-013 탐침은 명시적으로 `discardResponseBodies: false`(`avail-probe.js:22`). 주 시나리오는 409 본문 code·`holdId`·`expiresAt`을 읽어야 해서 끄지 않는다(기본값 false). 요청마다 `responseType`으로 되살리는 방식은 쓰지 않았다.

---

## 4. 연결 재사용과 keep-alive

### 4.1 배경 — 왜 EOF가 나나

k6는 VU마다 연결 풀을 갖고 HTTP keep-alive로 연결을 다시 쓴다. ADR-014 원인 조사 06c(`docs/adr/ADR-014-hold-expiry.md:993`)가 확인한 것:

1. 서버(Tomcat 10.1.44 · Spring Boot 3.5.5 기본)는 유휴 **60초**(검사 약 1초 간격 → 60~61초)에 keep-alive 연결을 닫는다.
2. k6(v2.2.0 · Go net/http)는 클라이언트 쪽 유휴 한도가 없어, 서버가 닫는 순간의 연결을 다시 쓸 수 있다.
3. **재사용 연결에 이미 쓴 POST는 재시도하지 않는다**(Idempotency-Key 헤더가 없으면 — 비멱등 요청) → `EOF`가 status 0 · error_code 1000으로 그대로 남는다. 실패한 POST는 서버 서블릿에 닿지 않았다.

(②③은 06c의 재현·조사 결론이다. Go Transport의 재시도 규칙 자체는 이 문서에서 Go 소스로 다시 확인하지 않았다 — **확인 필요**.)

### 4.2 처방 ① — `Connection: close` 머리(반복 안의 긴 쉼)

`Connection: close`를 단 요청은 응답 뒤 연결을 닫으므로 **다음 요청은 늘 새 연결**이다.

```js
// k6/ADR-014/scenarios/lib14.js:108-121
export function closeConn(req) {
  req.params.headers.Connection = 'close';
  return req;
}
...
export const KEEPALIVE_SAFE_MS = 50000;
export function closeBeforeLongThink(req, thinkMs) {
  return thinkMs >= KEEPALIVE_SAFE_MS ? closeConn(req) : req;
}
```

- `closeConn`: 탐침 요청 전부(탐침 VU는 TTL + δ 넘게 쉰다 — `lib14.js:104-107`, 2026-10-29 결정).
- `closeBeforeLongThink`: 생각 시간이 50초(서버 60초보다 10초 여유) 이상이면 **그 앞 요청**에 close를 단다 → 생각 시간 뒤 확정은 새 연결로 간다(`lib14.js:112-117`, 06c · eb4d073e). 대가: 새 연결 수립 1회(LAN 약 0.5ms).
- ADR-015는 지금 생각 시간이 약 TTL(30초)이라 붙지 않지만, TTL을 늘려도 안전하게 "잠들기 앞" 요청 모두에 건다(`k6/ADR-015/scenarios/lib15.js:4-5`).

### 4.3 처방 ② — `noVUConnectionReuse`(반복 사이의 쉼)

공식(<https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/#no-vu-connection-reuse>): "whether k6 should reuse TCP connections between iterations of a VU." — `true`면 **반복 사이** 연결 재사용을 끈다(반복 안 재사용은 유지). 비교: `noConnectionReuse`는 keep-alive 자체를 끈다(이 저장소 미사용).

ADR-015 캠페인 무효 6회가 모두 `k6-code0`(선점·재선점 1~2건 EOF · 확정 0건)였다(`docs/adr/ADR-016-paid-seat-loss.md:69`). 원인: 반복을 마친 VU가 다음 반복까지 60초 넘게 쉬고, 그 연결 위의 **반복 첫 요청**이 EOF. ADR-016 1단계에서 옵션 한 줄을 넣었다.

```js
// k6/ADR-015/scenarios/confirm-contention.js:67-70
  // 반복 사이 연결 재사용 끔(ADR-016 설계-5 · 재현 `results/_evidence-20261107/16-eof-repro/`): 반복을 마친 VU가 다음 반복까지 서버 유휴 한도(Tomcat 60초)를
  //   넘게 쉬면 그 연결 위 첫 요청(선점 · 재선점)이 EOF로 끊겼다(ADR-015 결과-4 · 무효 6). 반복 끝에 연결을 닫아 모든 종료 경로(조기 종료 · 재선점 201 포함)를 덮는다.
  //   반복 안 재사용은 그대로(긴 생각 시간 앞은 closeBeforeLongThink). 대가: 반복 첫 요청은 늘 새 연결 — 선점 p99에 연결 수립이 들어간다(대조군도 같은 하네스로 다시 잰다).
  noVUConnectionReuse: true,
```

재현 스크립트(서버 keep-alive를 2초로 줄여 경계를 자주 밟게 함):

```js
// k6/ADR-015/results/_evidence-20261107/16-eof-repro/gap.js:13-24
export const options = { scenarios: { s: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DUR || '4m',
  preAllocatedVUs: VUS, maxVUs: VUS, gracefulStop: '30s' } }, summaryTrendStats: ['min', 'max'], discardResponseBodies: true,
  noVUConnectionReuse: __ENV.NOVU === '1' };   // 비교 처방(나): 반복 사이 연결 재사용 끔
...
export default function () {
  const a = http.post(`${BASE}/hold`, '{}', J);
  if (a.status === 0) { eofFirst.add(1); return; }
  if (a.timings.connecting > 0) firstNew.add(1); else firstReused.add(1);
  sleep(Math.random() * 0.4);
  const b = http.post(`${BASE}/confirm`, '{}', CLOSE_LAST ? JC : J);
  if (b.status === 0) eofLast.add(1); else if (b.timings.connecting > 0) lastNew.add(1); else lastReused.add(1);
}
```

결과(`docs/plans/2026-11-07/adr-016-paid-seat-loss/log.md:18-20`): 150/s · 2분에서 VUS 330 = EOF 0(유휴가 한도 밑) · VUS 600 = 0 · **VUS 400 = 반복 첫 요청 EOF 14 · 9건(2회)**. 같은 VUS 400에서 (가) 반복 끝 `Connection: close` = 0 · 0, (나) `noVUConnectionReuse` = 0 · 0, 반복 안 둘째 요청은 두 처방 모두 재사용 4501/4501(반복 안 동작 무변경). (가)는 "마지막 요청"을 보낼 때 알아야 해 조기 종료 경로를 못 덮으므로 (나)를 골랐다(`log.md:20`).

정리 — 유휴가 생기는 자리별 처방:

| 쉬는 자리 | 처방 | 위치 |
|---|---|---|
| 반복 **안**, 생각 시간 ≥ 50초 | 그 앞 요청에 `Connection: close` | `lib14.js` `closeBeforeLongThink` |
| 탐침 요청 전부 | `Connection: close` | `lib14.js` `closeConn` |
| 반복 **사이**(VU 대기) | `noVUConnectionReuse: true` | `confirm-contention.js:70` |

---

## 5. 사용자 지표·태그·요약·임계값

### 5.1 지표 종류

공식: <https://grafana.com/docs/k6/latest/using-k6/metrics/create-custom-metrics/>. 이 저장소가 쓴 것은 **Counter · Rate · Trend** 셋이다(Gauge 미사용).

```js
// k6/ADR-001/scenarios/lib/common.js:8-12
export const holdCreated = new Counter('hold_201');
export const holdConflict = new Counter('hold_409');
export const holdOther = new Counter('hold_other');
export const holdError = new Rate('hold_error');          // 5xx·타임아웃·연결 실패 비율
export const holdDuration = new Trend('hold_duration', true);
```

- `Counter.add(n, tags)`: 합. `Rate.add(bool, tags)`: 참 비율. `Trend.add(값, tags)`: 분포(백분위). `new Trend(name, true)`의 둘째 인자 = 시간 값 표시.
- 지표는 **init 코드에서** 만든다(VU 함수 안에서 만들 수 없다 — 공식 문서 규칙).

### 5.2 태그로 원시 자료 남기기 — Counter 1행 = 사건 1건

ADR-005 이후의 핵심 기법: `http_req_*` 행에는 응답을 본 **뒤** 태그를 붙일 수 없으므로(`k6/ADR-009/scenarios/lib/common.js:21`), 사건마다 `Counter.add(1, {태그...})`를 한 번 불러 **CSV 한 행**을 만든다. 태그에 좌석·만료 시각·응답 code·보낸 시각을 문자열로 싣는다.

```js
// k6/ADR-014/scenarios/s14-flow.js:109-112
  sleep(a.think_ms / 1000);
  const sent = Date.now();
  const c = send(confirmReq(b.holdId, user));
  confirmRec.add(1, { seat: String(seat), exp: b.exp, st: String(c.status), code: codeOf(c), sent: String(sent), rt: String(Math.round(c.timings.duration)) });
```

CSV 출력에서 `name` · `status` · `scenario` 같은 기본 태그는 제 열을 갖고, 나머지 사용자 태그는 `extra_tags` 열에 `k=v&k=v`로 들어간다. 요약기는 이렇게 푼다:

```python
# k6/ADR-015/scripts/summarize.py:100-101
def parse_tags(s):
    return dict(kv.split("=", 1) for kv in s.split("&") if "=" in kv) if s else {}
```

(요약기가 쓰는 열: `metric_name, timestamp, metric_value, name, scenario, status, extra_tags`, 있으면 `error_code` — `summarize.py:114-115`.) 태그 값에 `&`·`=`가 들어가면 깨진다 — 현재 값(숫자·ISO 시각·code)에는 없다.

**주의 — 시계열 폭발**: 태그 값 조합마다 시계열이 생긴다. 세션마다 고유 태그를 다는 대신, 세션 속성은 해시를 Counter **값**으로 더해 3행으로 줄인다:

```js
// k6/ADR-014/scenarios/lib14.js:47-58 (요지)
// 세션마다 Counter 행 3개라 시계열 수가 늘지 않는다(세션마다 태그를 달면 시계열이 수십만 개가 된다). 합 < 2^53(세션 ≤ 2^33개)이라 float64 합이 정확하다.
export function recordSession(flow, sid, a) {
  const s = `${flow}|${sid}|${a.abandon ? 1 : 0}|${a.revisit ? 1 : 0}|${a.think_ms}|${a.delta_ms}|${a.seatSeed}`;
  crnN.add(1);
  crnH1.add(fnv1a(s) & 0xfffff);
  crnH2.add(fnv1a(s, 0x9dc5811c) & 0xfffff);
}
```

요청 수가 큰 S4는 원시 code 행을 끈다(`k6/ADR-009/scenarios/lib/common.js:44` `opts.code = true면 … S2·S7만 켠다`).

### 5.3 summaryTrendStats

Trend 지표 요약에 낼 통계를 고른다(공식 `#summary-trend-stats`).

```js
// k6/ADR-009/scenarios/lib/common.js:78
export const TREND_STATS = ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'];
```

예열은 `['avg']`만, 측정기는 `['avg', 'p(50)', 'p(99)', 'max']`(`probe-ping.js:24`).

### 5.4 thresholds — 서브메트릭 노출 + 조기 중단

공식: <https://grafana.com/docs/k6/latest/using-k6/thresholds/>. S4 계단에서 두 용도로 썼다.

```js
// k6/ADR-001/scenarios/s4-throughput.js:16-23
const thresholds = {};
for (let i = 0; i < STEPS; i++) {
  // 빈 임계값 = 단계별 서브메트릭을 요약에 포함시키기 위한 것 (판정용 아님)
  thresholds[`hold_duration{stage:${i}}`] = [];
  // 한 단계의 에러율이 50%를 넘으면 한계를 지난 것 — 붕괴 뒤의 무의미한 부하를 멈춘다 (k6 종료코드 99)
  thresholds[`hold_error{stage:${i}}`] = [{ threshold: 'rate<0.5', abortOnFail: true, delayAbortEval: '10s' }];
  thresholds[`hold_201{stage:${i}}`] = [];
}
```

- `지표{태그:값}` = 태그로 거른 서브메트릭. **빈 배열 임계값**은 판정 없이 그 서브메트릭을 끝 요약에 나오게 하는 용도.
- `abortOnFail: true` + `delayAbortEval: '10s'`: 조건이 깨지면(10초 유예 뒤) 테스트를 멈춘다 → 종료 코드 99. 실행기는 S4의 99를 "한계 도달로 스스로 멈춤(정상)"으로 읽는다(`k6/ADR-009/scripts/run.sh:1227-1229`).
- 고정 도착률 셀은 끝까지 보려고 끈다(`k6/ADR-009/scenarios/s4-throughput.js:30` `STOP_ON_ERROR ? [...] : []`).
- `check()`·`group()`은 쓰지 않았다.

---

## 6. 환경변수·k6/execution·sleep·시각 맞추기

### 6.1 `__ENV`

`k6 run` 앞의 셸 환경변수(또는 `-e K=V`)가 `__ENV`로 들어온다(공식: <https://grafana.com/docs/k6/latest/using-k6/environment-variables/>). 이 저장소는 `-e`를 쓰지 않고 **`env K=V ... k6 run`**으로 넘긴다(§7).

초기 ADR은 기본값을 두었지만(`Number(__ENV.VUS || 1000)`), ADR-014부터는 **기본값에 기대지 않고 없으면 실패**한다 — 계획(`plan*.py --render`)과 실행이 갈리지 않게:

```js
// k6/ADR-015/scenarios/confirm-contention.js:32-37
const E = (k) => {
  const v = __ENV[k];
  if (v === undefined || v === '') throw new Error(`환경변수 ${k} 없음(run.sh가 plan15 --render k6.env를 넘긴다)`);
  return v;
};
const N = (k) => Number(E(k));
```

init 코드에서 던진 예외는 테스트 시작 전에 실행을 멈춘다(종료 코드 값은 **확인 필요**).

### 6.2 `__VU` · `k6/execution`

- `__VU`: 현재 VU 번호(1부터). per-vu-iterations에서 VU마다 다른 사용자·좌석(§2.1).
- `exec.scenario.iterationInTest`: 이 시나리오 안에서 **모든 VU를 통틀어 고유한 반복 번호**(0부터) — 도착률 실행기에서 세션 번호로 쓴다(공식: <https://grafana.com/docs/k6/latest/javascript-api/k6-execution/>).

```js
// k6/ADR-015/scenarios/confirm-contention.js:94-100
export function mainFlow() {
  const sid = exec.scenario.iterationInTest;
  if (sid >= PLAN_N) return;
  const a = sessionAttrs15(REP, sid, P);
  recordSession('main', sid, { abandon: a.dup, revisit: a.rehold, think_ms: a.c_ms, delta_ms: 0, seatSeed: 0 });   // crn 해시(lib14 — 같은 이름 칸에 이 측정의 속성을 넣는다)
  const { schedule, seat } = seatOf(sid, SEATS_PER);
  const user = USER_MAIN + sid;
```

`iterationInTest`는 시나리오별로 따로 센다 — main·rehold 두 시나리오가 같은 sid로 같은 세션을 가리키는 설계가 이에 기댄다(ADR-015). 버린 반복이 있으면 그 번호는 비고 두 시나리오가 어긋날 수 있다(§2.4). `Math.random()` 대신 sid를 씨앗으로 한 결정적 난수(FNV-1a + mulberry32 — `lib14.js:16-33`)로 같은 회차의 여러 방식이 같은 세션 열을 받게 한다.

### 6.3 sleep

`sleep(초)`는 VU를 그 시간 동안 멈춘다(소수 초 가능). 생각 시간(`s14-flow.js:109` `sleep(a.think_ms / 1000)`)과 "특정 시각까지 기다리기"에 쓴다. 자는 동안에도 VU를 점유하므로 §2.4의 VU 계산에 들어간다.

### 6.4 서버 시각 맞추기 — CLOCK_OFFSET_MS

k6는 PC 시계(`Date.now()`)로 돈다. 만료 경계 실험은 **서버 시각** 기준으로 요청을 보내야 해서, 실행기가 k6 직전에 서버 − PC 시계 차를 재서 넘긴다.

```bash
# k6/ADR-015/scripts/run.sh:238-239
CLK_BEFORE=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
OFF_MS=$(jq -r '(.offset_s // 0) * 1000 | round' <<< "${CLK_BEFORE:-null}" 2>/dev/null); [[ "$OFF_MS" =~ ^-?[0-9]+$ ]] || OFF_MS=0
```

`clock_offset`(`k6/ADR-009/scripts/lib.sh:160-174`)는 ssh로 서버 `date +%s.%N`을 5번 받아 왕복이 가장 짧은 표본의 `서버 − (t0 + t1)/2`를 쓴다. 시나리오 쪽:

```js
// k6/ADR-014/scenarios/lib14.js:69-73
// 서버 시각 targetSrvMs(epoch ms)까지 잔다(PC 시각 = 서버 − 시계 차). 이미 지났으면 바로 돌아온다
export function sleepUntilSrv(targetSrvMs) {
  const d = (targetSrvMs - CLOCK_OFFSET_MS - Date.now()) / 1000;
  if (d > 0) sleep(d);
}
```

기록용 서버 시각은 `const srvNow = () => Date.now() + CLOCK_OFFSET_MS;`(`confirm-contention.js:86`). 이 값은 **일정 맞추기에만** 쓰고, 판정은 요약기가 `clock-offset.json`(k6 앞·뒤 측정)으로 다시 보정한다(`lib14.js:4`). 서버가 준 `expiresAt`은 소수 자릿수가 3을 넘을 수 있어 `Date.parse` 대신 직접 푼다(`lib14.js:61-68` `isoMs` — goja(k6의 JS 엔진) Date.parse 한계는 주석의 "못 읽을 수 있어" 수준 — **확인 필요**).

---

## 7. 실행 방법 — run.sh가 k6를 부르는 방식

### 7.1 주 측정

```bash
# k6/ADR-015/scripts/run.sh:248
env $K6_ENV CLOCK_OFFSET_MS="$OFF_MS" BASE_URL="$BASE_URL" OUT_DIR="$DIR" "$K6_BIN" run --quiet --out "csv=$DIR/k6-requests.csv.gz" "$K6_TREE/$K6_SCRIPT" > "$DIR/k6-stdout.log" 2>&1 < /dev/null
```

- `$K6_ENV` = `plan15.py --render`가 만든 `k6.env`를 `K=V K=V`로 펼친 것(`run.sh:94`).
- `--quiet`: 진행 표시 끔. `--out csv=<파일>.csv.gz`: 모든 지표 표본을 CSV(gzip)로 — 요약기의 원시 입력(공식: <https://grafana.com/docs/k6/latest/results-output/real-time/csv/>).
- `OUT_DIR`은 시나리오의 `handleSummary`가 `k6-summary.json`을 쓸 폴더.
- 시나리오는 작업트리가 아니라 **측정 SHA에서 꺼낸 임시 트리**(`git archive "$SHA" … | tar -x -C "$K6_TREE"`, `run.sh:142`)에서 돌린다 — 기록된 SHA와 실제 실행물이 같게.
- 종료 코드 `K6_EXIT=$?`를 받아 메타에 남긴다(`run.sh:249`).

### 7.2 예열·측정기·대시보드

```bash
# k6/ADR-015/scripts/run.sh:196 (예열)
BASE_URL="$BASE_URL" PAY_START="$(P .k6.env.PAY_START)" "$K6_BIN" run --quiet --out "csv=$DIR/warmup-requests.csv.gz" "$SCEN15/warmup15.js" > "$DIR/warmup.log" 2>&1 < /dev/null
```

```bash
# k6/ADR-014/scripts/lib14.sh:185-186 (측정기 — 다른 k6 바이너리 · 배경 실행)
  env BASE_URL="$BASE_URL" PROBE_RATE="$PROBE_RATE" PROBE_DURATION_S="$PROBE_DUR" K6_CSV_TIME_FORMAT=unix_milli \
    "$PROBE_K6_BIN" run --quiet --out "csv=$dir/k6-probe-pc.csv.gz" "$SCEN9/probe-ping.js" > "$dir/probe-pc.log" 2>&1 < /dev/null &
```

- `K6_CSV_TIME_FORMAT=unix_milli`: CSV `timestamp`를 ms로(기본은 초 단위 — 주 k6 CSV는 초, `probe-ping.js:8` 주석).
- 측정기는 주 k6보다 길게 걸고 신호(SIGINT·SIGTERM)로 멈춘다 — "k6가 정상 종료하며 CSV를 닫는다"(`probe-ping.js:7`).
- 웹 대시보드 HTML 내보내기(ADR-001·009 S3·S4):

```bash
# k6/ADR-001/scripts/run.sh:193-196
  [[ "$base" != S1 && "$base" != S2 ]] && dash=(K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_PORT=-1 "K6_WEB_DASHBOARD_EXPORT=$dir/k6-dashboard.html")
  local k6_started; k6_started=$(date -Is)
  env $k6env "${dash[@]}" BASE_URL="$BASE_URL" OUT_DIR="$dir" \
    k6 run --quiet --out "csv=$dir/k6-requests.csv.gz" "$SCENARIOS/$(script_of "$base")" > "$dir/k6-stdout.log" 2>&1
```

  (`K6_WEB_DASHBOARD_PORT=-1`의 정확한 의미 — 서버를 열지 않음으로 추정 — **확인 필요**. 공식: <https://grafana.com/docs/k6/latest/results-output/web-dashboard/>.)

### 7.3 k6 inspect — 옵션만 확인(부하 없음)

`k6 inspect <스크립트>`는 init 코드를 평가해 최종 옵션 JSON을 출력한다. 하네스 점검에서 "`k6 inspect` 다섯 모드 × 두 셀 EXIT 0"처럼 렌더된 환경변수로 스크립트가 뜨는지 확인하는 데 썼다(`docs/adr/ADR-015-confirm-atomicity.md:887`, `docs/plans/2026-10-26/adr-014-hold-expiry/log.md:26`). 이 문서를 쓰며 돌린 예:

```text
$ cd k6/ADR-001/scenarios && k6 inspect s1-same-seat.js
{ ...
  "scenarios": {
    "s1": { "executor": "per-vu-iterations", ..., "vus": 1000, "iterations": 1, "maxDuration": "2m0s" }
  }, ... }
```

ADR-014·015 시나리오는 환경변수가 없으면 init에서 예외를 던지므로 `env $K6_ENV k6 inspect …`로 줘야 한다(§6.1).

---

## 8. 이 저장소에서 겪은 함정

1. **버린 반복(dropped_iterations)** — 도착률 실행기는 빈 VU가 없으면 반복을 버린다. ADR-015 rehold 동시 세션 1038이 미리 만든 VU 1000을 넘어 스모크 무효 → VU = 평균 동시 세션 × 1.3(29e09aef). `plan15.py:64-68`, `ADR-015-confirm-atomicity.md:903,935`. ADR-014도 "모자라면 실행 중 VU를 늦게 만들어 그 사이 반복을 버린다"(`plan14.py:71`).
2. **keep-alive EOF(반복 안)** — 생각 시간 ≈ 60초면 서버가 닫는 순간의 연결을 다시 써 확정이 status 0 · code 1000. `closeBeforeLongThink`로 고침. ADR-014 06c(`docs/adr/ADR-014-hold-expiry.md:993`), `lib14.js:112-121`.
3. **keep-alive EOF(반복 사이)** — VU가 많아 반복 사이 유휴가 길어지자 반복 첫 요청이 EOF. `noVUConnectionReuse: true`. `ADR-016-paid-seat-loss.md:67-72`, `confirm-contention.js:67-70`, `gap.js`.
4. **비멱등 POST는 재시도되지 않는다** — 재사용 연결에서 끊긴 POST는 k6(Go net/http)가 다시 보내지 않는다(Idempotency-Key 없음). 그래서 EOF가 결과에 그대로 남고, 서버에는 도달하지 않았다. 06c(`docs/adr/ADR-014-hold-expiry.md:993`). 처방도 "재시도 추가"가 아니라 "연결을 새로"다.
5. **처방의 대가 — 지연 비교 금지** — 두 처방 모두 반복 첫 요청에 연결 수립이 붙어 선점 p99가 바뀐다 → 앞 ADR 수치와 직접 비교하지 않고 대조군을 같은 하네스로 다시 잰다(`confirm-contention.js:69`, `ADR-016-paid-seat-loss.md:72`).
6. **gracefulStop이 끊은 반복은 `finally`가 안 돌 수 있다** — 세션 끝에만 집계를 내면 끊긴 세션 몫이 빠졌다(스모크 smoke14net-20261028: 409 2,138 중 149 누락). 잠들기·다음 요청 **전에** 집계를 낸다(`flushTally`). `lib14.js:76-85`. (k6 내부에서 끊긴 반복의 `finally` 동작 자체는 관찰 기반 — **확인 필요**.)
7. **끝 경계 반복 1개 차이** — `duration`이 주기의 정수배면 끝 경계에 반복 하나가 더 시작될 수도 아닐 수도 있어 방식 사이 세션 수가 갈렸다(map 3,601 대 3,600, 맹목 108,001 대 108,000). duration = D − 주기/2로 줄이고, 계획 수 밖 반복은 요청 없이 끝낸다. `s14-flow.js:47-54,117`, `confirm-contention.js:56-58,96`.
8. **소수 도착률** — `rate`가 정수여야 해 `rate × 1000 / timeUnit '1000s'`로 바꿨다(`s14-flow.js:61`). (`rate`가 정수만 받는지는 주석 근거 — **확인 필요**.)
9. **name 태그와 시계열** — 동적 URL은 `tags.name`으로 묶고, 좌석·사용자는 따로 태그(`common.js:21`). 세션마다 고유 태그는 시계열 수십만 개 → 해시 합 Counter 3행(`lib14.js:47-58`).
10. **응답 뒤 태그 불가** — `http_req_*`에는 응답을 본 뒤 태그를 못 붙여 별도 Counter 행(`hold_code` 등)으로 남긴다(`k6/ADR-009/scenarios/lib/common.js:20-22`).
11. **기본값에 기대면 계획과 실행이 갈린다** — ADR-014부터 환경변수 없으면 예외(`confirm-contention.js:32-37`).
12. **본문 code 분류 범위** — `errorCodeOf`는 409만 본문을 읽어 404·403이 `HTTP404`가 됐다 → 확정은 상태와 무관하게 본문 code를 읽는 `confirmCodeOf`(`lib15.js:42-52`, 리뷰 M15-21).
13. **시작 과도에 VU 폭증** — 예열 직후 0초부터 목표 도착률을 걸면 VU가 상한 10,000까지 늘고 request timeout·dial i/o timeout이 남았다 → 고정 셀 앞 램프(`k6/ADR-009/scenarios/s4-throughput.js:14-16`).
14. **미리 만든 VU가 많으면 k6 시작이 늦다** — "k6 실행 → 첫 요청" 지연이 VU 수에 비례(옛 고정 1.2초로는 VU 13,300 칸이 93.2초) → `k6_lead_s = 0.15 + 0.37ms × VU`(`k6/ADR-014/scripts/run.sh:40`, `plan15.py:69`).
15. **S4의 종료 코드 99는 정상** — `abortOnFail` 중단(`ADR-009/scripts/run.sh:1227-1229`). 다른 셀의 비0 종료는 실패.
16. **stdin** — 모든 k6 호출 끝에 `< /dev/null`. ADR-014 점검 기록에 "`< /dev/null` 제거 → 5회 뒤 exit 6"(`docs/plans/2026-10-26/adr-014-hold-expiry/log.md:26`). 원인은 이 문서에서 확인하지 않았다 — **확인 필요**.

---

## 9. 확인 필요 목록

- 과거 캠페인의 주 k6 버전(회차 `meta.json` `k6_version` 칸 — 이 문서는 읽지 않음).
- per-vu-iterations에서 VU들이 첫 요청을 보내는 시각의 동기화 정밀도.
- `res.timings.duration`이 연결 수립 시간을 빼는지의 공식 정의(공식 response 페이지와 직접 대조 안 함).
- Go net/http가 재사용 연결의 비멱등 요청을 재시도하지 않는 규칙(06c 결론을 인용 — Go 소스 미확인).
- gracefulStop이 끊은 반복에서 JS `finally`가 실행되지 않는 k6 내부 동작(관찰 기반).
- `rate`가 정수만 받는지(코드 주석 근거).
- init 코드 예외 시 k6 종료 코드 값.
- goja `Date.parse`가 3자리 넘는 소수 초를 못 읽는지.
- `K6_WEB_DASHBOARD_PORT=-1`의 정확한 의미.
- `< /dev/null`을 빼면 exit 6이 나는 원인.
