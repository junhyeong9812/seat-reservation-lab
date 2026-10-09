// S7 (ADR-005 ④ 이긴 쪽 롤백): 좌석마다 '이미 2매를 가진 사용자' U 1명 + 일반 사용자 M명이 같은 좌석을 거의 동시에 선점한다.
// U가 좌석 락을 먼저 잡고 매수 판정에서 지면(롤백) 그 사이 NOWAIT로 진 일반 사용자는 409 SEAT_NOT_AVAILABLE을 받는다 —
// 좌석이 끝내 비었는데(AVAILABLE) 진 일반 사용자가 '억울한 409'다. 판정은 끝 상태(run.sh end-state.json) × 요청 기록(hold_code)으로 summarize.py가 한다.
//
// 배치(회차 1, 시드 10,000석):
//   대상 좌석 1..SEATS · U_i = 700000 + i 가 setup()에서 앱 API로 좌석 SEATS + 2i − 1, SEATS + 2i 를 미리 선점(2매 — counter 방식도 세도록 DB 직접 삽입 금지)
//   일반 사용자 = 710000 + (i − 1)·M + j (j = 1..M) — 모두 다른 사용자라 1인 2매에 걸리지 않는다
// 시차: 좌석 i의 경주 시각 = 시작 + (i − 1)·GAP_MS. U는 그 시각에, 일반 사용자 M명은 LEAD_MS 뒤에 보낸다(근거 README §S7).
//   좌석마다 경주를 GAP_MS씩 떼어 서로 겹치지 않게 한다 — 동시 부하(S2)가 아니라 롤백 현상만 본다.
// 요청 기록: hold_code 행에 role(U|R)·seat·user·code와 t0(보낸 시각 ms)·lag(예정 시각 대비 늦음 ms)를 남긴다.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, hold, TREND_STATS, writeSummary } from './lib/common.js';

const SEATS = Number(__ENV.SEATS || 100);
const M = Number(__ENV.M || 20);
const LEAD_MS = Number(__ENV.LEAD_MS || 1);
const GAP_MS = Number(__ENV.GAP_MS || 50);
const START_DELAY_MS = Number(__ENV.START_DELAY_MS || 3000);
const U_BASE = 700000;
const R_BASE = 710000;

export const options = {
  scenarios: { s7: { executor: 'per-vu-iterations', vus: SEATS * (M + 1), iterations: 1, maxDuration: '2m' } },
  summaryTrendStats: TREND_STATS,
  setupTimeout: '120s',
};

const setupHold201 = new Counter('setup_hold_201');   // U의 사전 선점 성공 수(= 2·SEATS여야 한다) — 201 − 홀드 행 대조에 더한다

export function setup() {
  // U마다 2석을 앱 API로 선점. 하나라도 201이 아니면 회차를 실패시킨다(전제가 깨진 측정을 남기지 않게).
  // 같은 사용자의 두 요청을 동시에 보내지 않는다 — 즉시 실패형(advisory-try·quota-nowait·serializable)이 둘 중 하나를 거절한다.
  // 그래서 1차(모든 U의 첫 좌석)를 다 끝낸 뒤 2차(둘째 좌석)를 보낸다. 같은 차수 안에서는 사용자가 모두 달라 병렬로 보낸다.
  for (const nth of [1, 0]) {   // 좌석 SEATS + 2i − 1, 그다음 SEATS + 2i
    const reqs = [];
    for (let i = 1; i <= SEATS; i++) {
      reqs.push(['POST', `${BASE_URL}/api/schedules/1/seats/${SEATS + 2 * i - nth}/hold`, null,
        { headers: { 'X-User-Id': String(U_BASE + i) }, tags: { name: 'setup_hold' }, timeout: '30s' }]);
    }
    for (let k = 0; k < reqs.length; k += 50) {
      http.batch(reqs.slice(k, k + 50)).forEach((r, n) => {
        // setup은 측정 대상이 아니다 — SERIALIZABLE은 다른 사용자끼리도 충돌(40001 → 409 HOLD_LIMIT_EXCEEDED)할 수 있어 그 409만 순차로 다시 보낸다(최대 5번)
        let res = r;
        for (let t = 0; res.status === 409 && res.body && res.body.includes('HOLD_LIMIT_EXCEEDED') && t < 5; t++) {
          res = http.request(...reqs[k + n]);
        }
        if (res.status !== 201) throw new Error(`setup hold nth=${nth} #${k + n} → ${res.status} ${res.body}`);
        setupHold201.add(1);
      });
    }
  }
  return { startAt: Date.now() + START_DELAY_MS };
}

export default function (data) {
  const v = __VU - 1;
  const seat = Math.floor(v / (M + 1)) + 1;
  const r = v % (M + 1);
  const isU = r === 0;
  const userId = isU ? U_BASE + seat : R_BASE + (seat - 1) * M + r;
  const target = data.startAt + (seat - 1) * GAP_MS + (isU ? 0 : LEAD_MS);
  const wait = target - Date.now();
  if (wait > 0) sleep(wait / 1000);
  const t0 = Date.now();
  hold(1, seat, userId, { role: isU ? 'U' : 'R', seat: String(seat), user: String(userId), t0: String(t0), lag: String(t0 - target) }, { code: true });
}

export function handleSummary(data) {
  data.s7 = { seats: SEATS, m: M, lead_ms: LEAD_MS, gap_ms: GAP_MS, u_base: U_BASE, r_base: R_BASE };
  return writeSummary(data);
}
