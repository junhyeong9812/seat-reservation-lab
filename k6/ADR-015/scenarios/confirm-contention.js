// ADR-015 시나리오 confirm-contention(§설계-8.2 · 측정 전 고정 규칙 초안 측정-3): 만료 경계 확정 + 같은 홀드 이중 확정 + 재선점 VU + (④) payment-start.
//   모든 값은 환경변수로 받는다(run.sh가 scripts/plan15.py --render의 k6.env를 그대로 넘긴다 — 기본값에 기대지 않는다). 시나리오 = 흐름(CSV scenario 열):
//   main   고정 도착률 RATE/s · D_ARR_S초 — 세션 sid: 자기 좌석 선점 → (④면 e − PAY_START_BEFORE_E_S에 payment-start) → 서버 시각 e + c에 확정
//          (dup 세션은 같은 홀드로 확정 두 개를 동시에 — http.batch). c ~ U[−(DELAY + C_MARGIN), +C_MARGIN] ms(공통 난수).
//          ④ 경계 흐름(xb 세션 — 비율 EXT_BOUNDARY_F · 리뷰 M15-5): payment-start 200이 준 연장 만료 e′ 기준으로 서버 시각 e′ + c에 확정(④에서도 경합 창이 열린다).
//          xb가 아닌 ④ 세션은 e + c에 확정(연장이 지켜 주는가 — ext_then_lost). payment-start가 200이 아니면 xb 세션은 확정하지 않는다(pay 분포가 G-F로 드러낸다).
//   rehold 같은 도착률 · 같은 세션 번호 — rehold 속성인 세션만: 그 세션의 좌석을 다른 사용자가 기준 만료(e · ④ xb는 e′ ≈ e + 창 − 리드) 둘레에서 선점 시도 —
//          ① 조밀 구간(REHOLD_DENSE_* — 경합 창 '만료 직후 ~ 확정 쓰기' 안 · 리뷰 M15-6) ② 성긴 구간(e + REHOLD_FROM부터 REHOLD_STEP 간격으로 e + REHOLD_UNTIL까지)
//          (201이면 멈추고 이탈 — 확정하지 않는다). 시작 시각을 main과 같게 두어 'e ≈ 반복 시작 + TTL'로 맞춘다(서버가 준 e는 main만 안다).
// 기록(요약기 입력 — CSV extra_tags):
//   conf{cb, dup, st, code, seat, fl, ts, te, e}  확정 응답마다 — cb = pre | win | post(lib15 cBucket — 계획 오프셋 · 기준 만료 대비 — 설명용) · dup = 0(단독) | 1 · 2(짝의 첫째 · 둘째) ·
//                                   code = 본문 code(상태 무관 — lib15 confirmCodeOf · 리뷰 M15-21) · fl = b(기준 e) | x(④ 경계 흐름 — e′) ·
//                                   ts · te = 송신 · 응답 끝 서버 시각(ms — PC 시각 + CLOCK_OFFSET_MS · 요약기가 clock-offset.json 오차로 보정) ·
//                                   e = 그 확정의 기준 만료(서버 expiresAt ms — 선점 201 · ④ 경계는 payment-start 200 — 요약기 양성 대조 · 잘못된 거부 · 리뷰 M15-26 · M15-19)
//   sess{fl, n}                     확정할 세션마다(선점 201 · 본문 정상 뒤 — fl = 계획 흐름 · n = 보낼 확정 수 1 | 2): 요약기가 흐름별 conf 수와 대조(리뷰 M15-23)
//   conf_skip{fl, n}                ④ 경계 흐름 세션이 연장을 못 받아 확정을 건너뜀(pay 비200 · 본문 결함 — 요약기: sess n 합 = conf 수 + conf_skip n 합 · 흐름별)
//   pay_body_bad{fl}                ④ payment-start 200인데 본문 expiresAt을 못 읽음(§3.4 200 {holdId, expiresAt} 계약 위반 — 요약기 G-F · 리뷰 M15-23)
//   rehold_ok{seat, k, ph, t}       재선점 201(k = 몇 번째 시도 · ph = d 조밀 | s 성긴 · t = 응답 끝 서버 시각 ms) — 요약기가 같은 좌석 확정 200과 짝지어 'API 수준 이중 결과'를,
//                                   같은 좌석 확정의 [ts, te] 안이면 'FM1 겹침'을 센다
//   pay{st, code, fl}               ④ payment-start 응답마다 · ext_then_lost(④: 연장 뒤 만료 − 1초 앞에 보낸 확정이 404 · 409 HOLD_EXPIRED — §3.4 · 기대 0)
//   hold_fail{st, code}             main 첫 선점이 201이 아닌 세션(요약기는 4xx · 본문 없음만 하네스 무효로 — 5xx · 시간 초과는 err의 hold 몫(모드 실패 G-F) · 리뷰 M15-12)
// 판정 창 = [t0, t0 + D_ARR_S) — t0 = setup 반환 직전 PC 시각(k6-summary setup_data.startedAt — ADR-014와 같은 정의)
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { TREND_STATS, writeSummary } from '../../ADR-009/scenarios/lib/common.js';
import {
  cBucket, closeBeforeLongThink, codeOf, confirmCodeOf, confirmReq, holdBody, holdReq, isoMs, payStartReq, recordSession, reholdOffsets, seatOf, send, sessionAttrs15, sleepUntilSrv, CLOCK_OFFSET_MS,
} from './lib15.js';

const E = (k) => {
  const v = __ENV[k];
  if (v === undefined || v === '') throw new Error(`환경변수 ${k} 없음(run.sh가 plan15 --render k6.env를 넘긴다)`);
  return v;
};
const N = (k) => Number(E(k));
const REP = E('REP');
const RATE = N('RATE');
const D_ARR_S = N('D_ARR_S');
const TTL_S = N('TTL_S');
const DELAY_MS = N('DELAY_MS');
const PAY_START = E('PAY_START') === '1';
const PAY_LEAD_MS = N('PAY_START_BEFORE_E_S') * 1000;
const PAY_WINDOW_MS = N('PAY_WINDOW_S') * 1000;                     // ④ payment-window(연장 만료 e′ ≈ payment-start 시각 + 창 — 재선점 기준 · keep-alive 처방)
const P = { delayMs: DELAY_MS, marginMs: N('C_MARGIN_MS'), fDup: N('F_DUP'), rho: N('RHO'), fExtB: PAY_START ? N('EXT_BOUNDARY_F') : 0 };
const REHOLD = {
  from: N('REHOLD_FROM_MS'), step: N('REHOLD_STEP_MS'), untilMs: N('REHOLD_UNTIL_S') * 1000,
  denseFrom: N('REHOLD_DENSE_FROM_MS'), denseStep: N('REHOLD_DENSE_STEP_MS'), denseUntil: N('REHOLD_DENSE_UNTIL_MS'),
};
const REHOLD_OFFS = reholdOffsets(REHOLD);
const DENSE_N = REHOLD.denseStep > 0 ? REHOLD_OFFS.filter((o) => o <= REHOLD.denseUntil).length : 0;
const SEATS_PER = N('SEATS_PER_SCHEDULE');
const USER_MAIN = N('USER_MAIN');
const USER_REHOLD = N('USER_REHOLD');
// 세션 수 = round(D_arr × 도착률) — 끝 경계 반복은 요청 없이 끝낸다(ADR-014 s14-flow mainPlanN과 같은 처방 · 두 시나리오의 세션 번호가 갈리지 않게)
const PLAN_N = Math.round(D_ARR_S * RATE);
const durMs = Math.floor(D_ARR_S * 1000 - 500 / RATE);
const GRACE = `${N('GRACE_S')}s`;

export const options = {
  scenarios: {
    main: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${durMs}ms`, preAllocatedVUs: N('PRE_VUS_MAIN'), maxVUs: N('MAX_VUS_MAIN'), gracefulStop: GRACE, exec: 'mainFlow' },
    rehold: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${durMs}ms`, preAllocatedVUs: N('PRE_VUS_REHOLD'), maxVUs: N('MAX_VUS_REHOLD'), gracefulStop: GRACE, exec: 'reholdFlow' },
  },
  summaryTrendStats: TREND_STATS,
};

const conf = new Counter('conf');
const reholdOk = new Counter('rehold_ok');
const pay = new Counter('pay');
const extThenLost = new Counter('ext_then_lost');
const holdFail = new Counter('hold_fail');
const sess = new Counter('sess');
const payBodyBad = new Counter('pay_body_bad');
const confSkip = new Counter('conf_skip');

export function setup() {
  return { startedAt: Date.now() };
}

const srvNow = () => Date.now() + CLOCK_OFFSET_MS;

function confirmTags(res, cb, dup, seat, fl, ts, te, e) {
  return { cb, dup: String(dup), st: String(res.status), code: confirmCodeOf(res), seat: String(seat), fl, ts: String(ts), te: String(te), e: String(e) };
}

export function mainFlow() {
  const sid = exec.scenario.iterationInTest;
  if (sid >= PLAN_N) return;
  const a = sessionAttrs15(REP, sid, P);
  recordSession('main', sid, { abandon: a.dup, revisit: a.rehold, think_ms: a.c_ms, delta_ms: 0, seatSeed: 0 });   // crn 해시(lib14 — 같은 이름 칸에 이 측정의 속성을 넣는다)
  const { schedule, seat } = seatOf(sid, SEATS_PER);
  const user = USER_MAIN + sid;
  const thinkMs = TTL_S * 1000 + a.c_ms;
  const h = send(closeBeforeLongThink(holdReq(schedule, seat, user, 'hold'), thinkMs));
  if (h.status !== 201) { holdFail.add(1, { st: String(h.status), code: codeOf(h) }); return; }
  const b = holdBody(h);
  if (!Number.isFinite(b.expMs) || !b.holdId) { holdFail.add(1, { st: 'body', code: 'NOBODY' }); return; }
  const cb = cBucket(a.c_ms, DELAY_MS);
  sess.add(1, { fl: PAY_START && a.xb ? 'x' : 'b', n: a.dup ? '2' : '1' });   // 계획 확정 수(흐름별 — 리뷰 M15-23)
  let extendedMs = NaN;
  let refMs = b.expMs;
  let fl = 'b';
  if (PAY_START) {
    sleepUntilSrv(b.expMs - PAY_LEAD_MS);
    // xb 세션은 payment-start 뒤 약 창(2분)을 잔다 — 그 앞 요청에 Connection: close(keep-alive 처방 · 측정-1)
    const ps = send(closeBeforeLongThink(payStartReq(b.holdId, user), (a.xb ? PAY_WINDOW_MS : PAY_LEAD_MS) + a.c_ms));
    pay.add(1, { st: String(ps.status), code: ps.status === 200 ? 'OK' : codeOf(ps), fl: a.xb ? 'x' : 'b' });
    if (ps.status === 200) {
      try { extendedMs = isoMs(ps.json('expiresAt')); } catch (e) { extendedMs = NaN; }
      if (!Number.isFinite(extendedMs)) payBodyBad.add(1, { fl: a.xb ? 'x' : 'b' });   // 200 본문 계약 위반 — 무음으로 확정을 건너뛰지 않는다(리뷰 M15-23)
    }
    if (a.xb) {
      // 연장이 없으면 경계 흐름이 성립하지 않는다 — 확정을 건너뛰되 무음이 아니다: pay 비200 · pay_body_bad(G-F)가 드러내고 conf_skip이 sess 대 conf 차를 맞춘다(리뷰 M15-23)
      if (!Number.isFinite(extendedMs)) { confSkip.add(1, { fl: 'x', n: a.dup ? '2' : '1' }); return; }
      refMs = extendedMs;
      fl = 'x';
    }
  }
  sleepUntilSrv(refMs + a.c_ms);
  const sentSrv = srvNow();
  if (a.dup) {
    const rs = http.batch([confirmReq(b.holdId, user, 'confirm'), confirmReq(b.holdId, user, 'confirm')]);
    const te = srvNow();
    rs.forEach((r, i) => conf.add(1, confirmTags(r, cb, i + 1, seat, fl, sentSrv, te, refMs)));
    // 짝의 둘째 404는 N2(한 홀드는 한 번만 확정 — §설계-6)의 정상 결과다 — 짝 중 하나라도 200이면 홀드를 잃지 않았다(로컬 스모크 15-09: 짝 진 쪽 11건을 잃음으로 잘못 셌다)
    if (!rs.some((r) => r.status === 200)) lostCheck(rs[0], extendedMs, sentSrv);
  } else {
    const r = send(confirmReq(b.holdId, user, 'confirm'));
    conf.add(1, confirmTags(r, cb, 0, seat, fl, sentSrv, srvNow(), refMs));
    lostCheck(r, extendedMs, sentSrv);
  }
}

// ④: 연장이 준 만료 − 1초보다 앞에 보낸 확정이 404 · 409 HOLD_EXPIRED면 '연장을 잃음'(§3.4 클라이언트 쪽 — 서버 extension_lost와 교차 확인)
function lostCheck(r, extendedMs, sentSrv) {
  if (!PAY_START || !Number.isFinite(extendedMs) || sentSrv >= extendedMs - 1000) return;
  const code = r.status === 200 ? 'OK' : codeOf(r);
  if (r.status === 404 || code === 'HOLD_EXPIRED') extThenLost.add(1, { st: String(r.status), code });
}

export function reholdFlow() {
  const sid = exec.scenario.iterationInTest;
  if (sid >= PLAN_N) return;
  const a = sessionAttrs15(REP, sid, P);
  if (!a.rehold) return;
  const { schedule, seat } = seatOf(sid, SEATS_PER);
  const user = USER_REHOLD + sid;
  const t0 = Date.now();
  // 기준 만료 ≈ 반복 시작 + TTL(④ xb 세션은 e′ ≈ e − 리드 + 창 — payment-start가 e − 리드에 연장한다)
  const refMs = TTL_S * 1000 + (P.fExtB > 0 && a.xb ? PAY_WINDOW_MS - PAY_LEAD_MS : 0);
  for (let k = 1; k <= REHOLD_OFFS.length; k++) {
    const wait = (t0 + refMs + REHOLD_OFFS[k - 1] - Date.now()) / 1000;
    if (wait > 0) sleep(wait);
    const r = send(holdReq(schedule, seat, user, 'rehold'));
    if (r.status === 201) { reholdOk.add(1, { seat: String(seat), k: String(k), ph: k <= DENSE_N ? 'd' : 's', t: String(srvNow()) }); return; }
  }
}

export function handleSummary(data) {
  data.adr015 = { rep: REP, rate: RATE, d_arr_s: D_ARR_S, ttl_s: TTL_S, delay_ms: DELAY_MS, plan_n: PLAN_N, pay_start: PAY_START, ext_boundary_f: P.fExtB,
    rehold_offsets_ms: REHOLD_OFFS, rehold_dense_n: DENSE_N };
  return writeSummary(data);
}
