// ADR-014 주 셀 한 실행(측정-3.1 · 3.2): 주 회차(회차 1) 맹목 흐름 + 지도 흐름(+ 이탈 뒤 재방문) · 탐침 회차(회차 2) PR-1 · PR-2 · PR-3 — k6 여러 시나리오 한 실행.
//   모든 값은 환경변수로 받는다(run.sh가 scripts/plan14.py --render의 k6.env를 그대로 넘긴다 — 기본값에 기대지 않는다). 시나리오 이름 = 흐름(CSV scenario 열):
//   blind(맹목 — 고정 도착률 BLIND_RATE/s · D_ARR_S초) · map(지도 — MAP_RATE/s · D_ARR_S초) · pr1 · pr2 · pr3(탐침 — PR_START_S부터 고정 간격 PRn_PERIOD_MS로 계획 수만큼).
//   재방문은 흐름이지만 시나리오가 아니다 — 이탈한 세션이 같은 반복 안에서 자기 만료 시각 e + δ에 같은 좌석을 다시 누른다(같은 사용자 · 측정-3.1).
//   그래서 재방문은 dropped_iterations가 따로 없다(반복이 이미 시작된 세션의 이어짐) — 요약기 해석 메모.
// 공통 난수(측정-3.5): 세션 속성은 (REP, 흐름, 세션 번호 = exec.scenario.iterationInTest)의 결정적 난수(lib14 sessionAttrs). 실제 실행된 세션의 속성 해시 = crn_n · crn_h1 · crn_h2 행(MR-11).
// 기록(요약기 입력 — CSV extra_tags):
//   hold_ok      주 흐름 선점 201마다: seat · exp(서버가 준 만료 ISO) · u(사용자) · kind(first | revisit) · fate(confirm | abandon — 세션 시작에서 정한 이탈 여부)
//   confirm_rec  주 흐름 확정마다: seat · exp(그 홀드) · st · code · sent(보내기 직전 PC ms) · rt(응답 ms)
//   revisit_rec  재방문 선점마다: seat · e(앞 홀드 만료 ISO) · u · sent · st · code · rej(409면 원인 표지)
//   probe        탐침 요청마다(리뷰 MR-2 — 보내기 직전 PC ms): pr · pid(탐침 순번) · d(δ 또는 δc) · step(h0 · h0a · h0b · h1 · u0 · v · c) · st · code · rej ·
//                sent · plan(계획 서버 ms — 판정 요청만) · e(그 단계가 받은 만료 서버 ms — 첫 선점) · rt
//   rej{cause}   선점 409 원인 표지 헤더 집계(세션 끝 · 탐침은 요청마다) · map_soldout(지도에서 보이는 AVAILABLE 0 — '매진 관찰')
// 판정 창 = [t0, t0 + D_ARR_S) — t0 = setup 반환 직전 PC 시각(k6-summary setup_data.startedAt — 시나리오 시각 0 · 첫 요청)
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { TREND_STATS, writeSummary } from '../../ADR-009/scenarios/lib/common.js';
import {
  closeBeforeLongThink, closeConn, codeOf, confirmReq, flushTally, holdBody, holdReq, mulberry32, newTally, recordSession, rej, rejectCause, send, sessionAttrs, sleepUntilSrv, BASE_URL,
} from './lib14.js';

const E = (k) => {
  const v = __ENV[k];
  if (v === undefined || v === '') throw new Error(`환경변수 ${k} 없음(run.sh가 plan14 --render k6.env를 넘긴다)`);
  return v;
};
const N = (k) => Number(E(k));
const REP = E('REP');
const D_ARR_S = N('D_ARR_S');
const TTL_S = N('TTL_S');
const P = { abandon: N('ABANDON'), rho: N('RHO'), thinkMin: N('THINK_MIN_S'), thinkMax: N('THINK_MAX_S'), revisitMax: N('REVISIT_MAX_S') };
const SEATS = N('SEATS');
const HOT_RATIO = N('HOT_RATIO');
const HOT_SHARE = N('HOT_SHARE');
const RETRIES = N('RETRIES');
const USER_BLIND = N('USER_BLIND');
const USER_MAP = N('USER_MAP');
const PROBES = E('PROBES') === '1';
const PR_SCHEDULE = PROBES ? N('PR_SCHEDULE') : 2;
const DELTAS = PROBES ? E('PR_DELTAS').split(',').map(Number) : [];
const DELTAS_C = PROBES ? E('PR_DELTAS_C').split(',').map(Number) : [];
const V_AFTER_MS = PROBES ? N('V_AFTER_MS') : 0;
const MAIN_SCHEDULE = 1;

// 주 흐름 길이 = D_arr − 주기의 절반(ms 내림). D_arr가 주기의 정수배면 끝 경계(t0 + D_arr)에 반복 하나가 더 시작될 수도 안 될 수도 있어
// 세션 수가 방식 사이에 갈렸다(스모크 10-29 a50-t30 map 3,601 대 3,600 → crn 합이 다름 · 탐침의 (계획 수 − 0.5) × 주기와 같은 처방).
const mainDurMs = (ratePerS) => Math.floor(D_ARR_S * 1000 - 500 / ratePerS);
// 그래도 끝 경계의 타이머와 duration 만료가 함께 준비되면(부하로 수 ms 늦은 고루틴) k6 select가 무작위로 하나를 고른다 — 주기의 절반이 1.7ms인
// 맹목에서 스모크 B가 108,001 세션(A·C·D 108,000)이었다. 계획 세션 수(round(D_arr × 도착률)) 밖의 반복은 요청도 crn 기록도 없이 끝낸다(창 밖 — t0 + D_arr 뒤).
const mainPlanN = (ratePerS) => Math.round(D_ARR_S * ratePerS);
const PLAN_N_BLIND = mainPlanN(N('BLIND_RATE'));
const PLAN_N_MAP = mainPlanN(N('MAP_RATE'));
const scenarios = {
  blind: {
    executor: 'constant-arrival-rate', rate: N('BLIND_RATE'), timeUnit: '1s', duration: `${mainDurMs(N('BLIND_RATE'))}ms`,
    preAllocatedVUs: N('PRE_VUS_BLIND'), maxVUs: N('MAX_VUS_BLIND'), gracefulStop: `${N('GRACE_STOP_S')}s`, exec: 'blind',
  },
  map: {
    executor: 'constant-arrival-rate', rate: N('MAP_RATE') * 1000, timeUnit: '1000s', duration: `${mainDurMs(N('MAP_RATE'))}ms`,   // 소수 도착률(측정-9 ⑥ 줄임)도 정수 rate로
    preAllocatedVUs: N('PRE_VUS_MAP'), maxVUs: N('MAX_VUS_MAP'), gracefulStop: `${N('GRACE_STOP_S')}s`, exec: 'mapFlow',
  },
};
if (PROBES) {
  for (const pr of ['pr1', 'pr2', 'pr3']) {
    scenarios[pr] = {
      executor: 'constant-arrival-rate', rate: 1, timeUnit: `${N(`${pr.toUpperCase()}_PERIOD_MS`)}ms`,
      startTime: `${N('PR_START_S')}s`, duration: `${N(`${pr.toUpperCase()}_DUR_MS`)}ms`,   // (계획 수 − 0.5) × 주기 — plan14 probe_plan
      // 탐침 하나 = TTL + δ(≤ 20초) + 여유 — 창 끝에서 끊기지 않게(마지막 시작 < D_ARR − TTL − 25)
      preAllocatedVUs: N('PRE_VUS_PROBE'), maxVUs: N('MAX_VUS_PROBE'), gracefulStop: `${TTL_S + 60}s`, exec: pr,
    };
  }
}
export const options = { scenarios, summaryTrendStats: TREND_STATS };

const holdOk = new Counter('hold_ok');
const confirmRec = new Counter('confirm_rec');
const revisitRec = new Counter('revisit_rec');
const probeRec = new Counter('probe');
const mapSoldout = new Counter('map_soldout');

export function setup() {
  return { startedAt: Date.now() };
}

function hotSeat(r) {
  const hot = Math.floor(SEATS * HOT_RATIO);
  return r() < HOT_SHARE ? 1 + Math.floor(r() * hot) : hot + 1 + Math.floor(r() * (SEATS - hot));
}
const seatRng = (seed) => mulberry32(seed);   // 좌석 고르기 전용 열(세션 씨앗 seatSeed — 응답에 따라 몇 번 뽑는지만 갈린다)

// 201 뒤(맹목·지도 공통 — 측정-3.1): 이탈이면 끝(재방문 여부에 따라 e + δ에 같은 좌석 한 번) · 아니면 생각 시간 뒤 확정
function afterHold(res, seat, user, a, tally, kind) {
  flushTally(tally);   // 잠들기 전에(lib14 flushTally 머리말)
  const b = holdBody(res);
  holdOk.add(1, { seat: String(seat), exp: b.exp, u: String(user), kind, fate: a.abandon && kind === 'first' ? 'abandon' : 'confirm' });
  if (a.abandon && kind === 'first') {
    if (!a.revisit || !Number.isFinite(b.expMs)) return;
    sleepUntilSrv(b.expMs + a.delta_ms);
    const sent = Date.now();
    const r2 = send(closeBeforeLongThink(holdReq(MAIN_SCHEDULE, seat, user), a.think_ms));   // 201이면 생각 시간 뒤 확정(lib14 closeBeforeLongThink)
    let cause = '';
    if (r2.status === 409) { cause = rejectCause(r2); tally[cause] += 1; }
    revisitRec.add(1, { seat: String(seat), e: b.exp, u: String(user), sent: String(sent), st: String(r2.status), code: codeOf(r2), rej: cause });
    if (r2.status === 201) afterHold(r2, seat, user, a, tally, 'revisit');
    return;
  }
  sleep(a.think_ms / 1000);
  const sent = Date.now();
  const c = send(confirmReq(b.holdId, user));
  confirmRec.add(1, { seat: String(seat), exp: b.exp, st: String(c.status), code: codeOf(c), sent: String(sent), rt: String(Math.round(c.timings.duration)) });
}

export function blind() {
  const sid = exec.scenario.iterationInTest;
  if (sid >= PLAN_N_BLIND) return;   // 끝 경계 반복(위 mainPlanN)
  const user = USER_BLIND + sid;
  const a = sessionAttrs(REP, 'blind', sid, P);
  recordSession('blind', sid, a);
  const r = seatRng(a.seatSeed);
  const tried = new Set();
  const tally = newTally();
  try {
    for (let attempt = 0; attempt <= RETRIES; attempt++) {
      let seat = hotSeat(r);
      while (tried.has(seat)) seat = hotSeat(r);
      tried.add(seat);
      const res = send(a.abandon ? holdReq(MAIN_SCHEDULE, seat, user) : closeBeforeLongThink(holdReq(MAIN_SCHEDULE, seat, user), a.think_ms));   // 이탈 세션은 확정이 없다
      if (res.status === 409) { tally[rejectCause(res)] += 1; flushTally(tally); continue; }   // 409만 다른 좌석으로(S3 그대로) · 다음 요청 전에 낸다(lib14 flushTally 머리말)
      if (res.status !== 201) return;                                          // 5xx·코드 0 — 세션 중단(S3 그대로 · 요약기 M11)
      afterHold(res, seat, user, a, tally, 'first');
      return;
    }
  } finally {
    flushTally(tally);
  }
}

// 지도 흐름(측정-3.1): 좌석 맵 → 보이는 AVAILABLE 중 고르게 → 선점 → 409면 지도를 한 번 더 읽고 다른 AVAILABLE → 선점 → 409면 끝. 보이는 AVAILABLE 0개 = 매진 관찰
function availableSeats(res) {
  if (res.status !== 200) return null;
  let list;
  try { list = JSON.parse(res.body); } catch (e) { return null; }
  const out = [];
  for (let i = 0; i < list.length; i++) if (list[i].status === 'AVAILABLE') out.push(list[i].seatId);
  return out;
}
export function mapFlow() {
  const sid = exec.scenario.iterationInTest;
  if (sid >= PLAN_N_MAP) return;     // 끝 경계 반복(위 mainPlanN)
  const user = USER_MAP + sid;
  const a = sessionAttrs(REP, 'map', sid, P);
  recordSession('map', sid, a);
  const r = seatRng(a.seatSeed);
  const tally = newTally();
  let prev = -1;
  try {
    for (let look = 0; look < 2; look++) {
      const m = http.get(`${BASE_URL}/api/schedules/${MAIN_SCHEDULE}/seats`, { tags: { name: 'seatmap' }, timeout: '30s' });
      const av = availableSeats(m);
      if (av === null) return;                                           // 지도 실패(5xx·코드 0) — 요약기 M11
      const pool = av.filter((s) => s !== prev);
      if (pool.length === 0) { mapSoldout.add(1); return; }
      const seat = pool[Math.floor(r() * pool.length)];
      prev = seat;
      const res = send(a.abandon ? holdReq(MAIN_SCHEDULE, seat, user) : closeBeforeLongThink(holdReq(MAIN_SCHEDULE, seat, user), a.think_ms));   // 이탈 세션은 확정이 없다
      if (res.status === 409) { tally[rejectCause(res)] += 1; flushTally(tally); continue; }   // 다음 좌석 맵 요청 전에 낸다(lib14 flushTally 머리말)
      if (res.status !== 201) return;
      afterHold(res, seat, user, a, tally, 'first');
      return;
    }
  } finally {
    flushTally(tally);
  }
}

// ---- 탐침(측정-3.2 — 회차 2 · 주 부하가 건드리지 않는다) ----------------------------------------------------------------------
// 탐침 요청은 모두 closeConn(Connection: close — lib14 closeConn 머리말 · 사용자 결정 2026-10-29). sent는 그대로 보내기 직전 ms(새 연결 수립은 sent 뒤 — LAN 왕복 1회)
function precord(pr, pid, d, step, res, sent, plan, eMs, cause) {
  probeRec.add(1, {
    pr, pid: String(pid), d: String(d), step, st: String(res.status), code: codeOf(res), rej: cause || '', sent: String(sent),
    plan: plan === null ? '' : String(Math.round(plan)), e: Number.isFinite(eMs) ? String(Math.round(eMs)) : '', rt: String(Math.round(res.timings.duration)),
  });
}
function probeHold(pr, pid, d, step, seat, user, plan) {
  const sent = Date.now();
  const res = send(closeConn(holdReq(PR_SCHEDULE, seat, user)));
  let cause = '';
  if (res.status === 409) { cause = rejectCause(res); rej.add(1, { cause }); }
  const b = res.status === 201 ? holdBody(res) : { expMs: NaN, holdId: null };
  precord(pr, pid, d, step, res, sent, plan, b.expMs, cause);
  return { res, b };
}

// PR-1 자기 좌석: u가 s 선점(e) → 확정 안 함 → 서버 e + δ에 s 다시 선점
export function pr1() {
  const i = exec.scenario.iterationInTest;
  const d = DELTAS[i % DELTAS.length];
  const seat = N('PR1_SEAT0') + i;
  const user = N('PR1_USER0') + i;
  const h0 = probeHold('pr1', i, d, 'h0', seat, user, null);
  if (h0.res.status !== 201 || !Number.isFinite(h0.b.expMs)) return;
  const plan = h0.b.expMs + d * 1000;
  sleepUntilSrv(plan);
  probeHold('pr1', i, d, 'h1', seat, user, plan);
}

// PR-2 한도: u가 s1·s2 선점(cnt 2 = 한도) → 서버 max(e1, e2) + δ에 새 좌석 s3
export function pr2() {
  const i = exec.scenario.iterationInTest;
  const d = DELTAS[i % DELTAS.length];
  const s0 = N('PR2_SEAT0') + 3 * i;
  const user = N('PR2_USER0') + i;
  const a = probeHold('pr2', i, d, 'h0a', s0, user, null);
  if (a.res.status !== 201 || !Number.isFinite(a.b.expMs)) return;
  const b = probeHold('pr2', i, d, 'h0b', s0 + 1, user, null);
  if (b.res.status !== 201 || !Number.isFinite(b.b.expMs)) return;
  const plan = Math.max(a.b.expMs, b.b.expMs) + d * 1000;
  sleepUntilSrv(plan);
  probeHold('pr2', i, d, 'h1', s0 + 2, user, plan);
}

// PR-3 API 계약 양성 대조(리뷰 MR-8): u가 s 선점(e) → 경쟁자 v가 서버 e + 0.3초에 s 선점 · u는 e + δc에 확정.
//   δc = +0.3은 v와 같은 순간 — http.batch로 둘을 함께 보낸다(같은 송신 시각). 나머지는 계획 시각 순서대로 차례로 보낸다
export function pr3() {
  const i = exec.scenario.iterationInTest;
  const dc = DELTAS_C[i % DELTAS_C.length];
  const seat = N('PR3_SEAT0') + i;
  const u = N('PR3U_USER0') + i;
  const v = N('PR3V_USER0') + i;
  const h0 = probeHold('pr3', i, dc, 'u0', seat, u, null);
  if (h0.res.status !== 201 || !Number.isFinite(h0.b.expMs)) return;
  const tv = h0.b.expMs + V_AFTER_MS;
  const tc = h0.b.expMs + dc * 1000;
  const doConfirm = () => {
    const sent = Date.now();
    const c = send(closeConn(confirmReq(h0.b.holdId, u)));
    precord('pr3', i, dc, 'c', c, sent, tc, NaN, '');
  };
  if (Math.round(tc) === Math.round(tv)) {
    sleepUntilSrv(tv);
    const sent = Date.now();
    const [rv, rc] = http.batch([closeConn(holdReq(PR_SCHEDULE, seat, v)), closeConn(confirmReq(h0.b.holdId, u))]);
    let cause = '';
    if (rv.status === 409) { cause = rejectCause(rv); rej.add(1, { cause }); }
    precord('pr3', i, dc, 'v', rv, sent, tv, NaN, cause);
    precord('pr3', i, dc, 'c', rc, sent, tc, NaN, '');
    return;
  }
  if (tc < tv) {
    sleepUntilSrv(tc);
    doConfirm();
    sleepUntilSrv(tv);
    probeHold('pr3', i, dc, 'v', seat, v, tv);
  } else {
    sleepUntilSrv(tv);
    probeHold('pr3', i, dc, 'v', seat, v, tv);
    sleepUntilSrv(tc);
    doConfirm();
  }
}

export function handleSummary(data) {
  data.adr014 = { rep: REP, d_arr_s: D_ARR_S, ttl_s: TTL_S, probes: PROBES };
  return writeSummary(data);
}
