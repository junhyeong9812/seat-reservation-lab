// ADR-017 시나리오 paid-flow17(측정 명세 docs/plans/2026-11-14/adr-017-measurement §1-1 · ADR-017 설계-5 · 8): 결제 · 확정 세션. 셀(base · pg · kill)과 무관하게 같은 세션열이다
// (PG 주입 · 앱 kill은 run17.sh가 건다). 모든 값은 환경변수(run17.sh가 plan17.py --render의 k6.env + BASE_URL · PG_URL · OUT_DIR · CLOCK_OFFSET_MS를 넘긴다 — 기본값에 기대지 않는다).
//   main 고정 도착률 RATE/s · D_ARR_S초 — 세션 sid · 종류 = KIND_CYCLE[sid mod 바퀴 길이](교차 배정):
//     선점 → T(잠) → payment-start(발급 uid · 연장 만료 expiresAt) → [abandon: 끝] → P(잠) → PG pay(R · S) | authorize(D) — k6가 흉내 PG를 직접 부른다 →
//     [noconf: 끝] → 확정:
//       ok      PG 응답 + G 뒤 확정 1회
//       retry   ok와 같게 확정 → 첫 응답을 버리고 R 뒤 같은 uid로 다시 확정(c2)
//       late    payment-start 응답의 expiresAt(연장 만료 — 서버 시계) + L에 확정(I14 — 결제는 마감 안 · 확정이 늦음 → 거부)
//   T · P · G · R · L = (회차, 세션 번호) 시드(lib17 sessionAttrs17 = plan17.py session_attrs).
// 기록: 세션마다 결과 행 하나 = console.log(JSON 한 줄) → run17.sh가 `--log-format=json --console-output=<회차>/sessions17.log`로 파일에 받는다.
//   k6 지표 태그로 세션 값을 달지 않는다(시계열 폭증 — ADR-016 선례). 행 형식 = INTERFACE.md §3(시각은 서버 시계 ms = PC + CLOCK_OFFSET_MS).
// 연결: 다음 같은 서버 요청까지 50초 이상 쉬면 그 앞 요청을 Connection: close(lib14 closeBeforeLongThink) · 세션의 마지막 앱 요청 · PG 요청은 close · noVUConnectionReuse.
// 실패(상태 0 · 5xx · 계약 밖 본문)는 다시 보내지 않고 그 단계에서 세션을 끝낸다(end = 그 단계) — 분류는 outcome17(배수 뒤 DB + PG 원장 · kill 창과 대조).
import exec from 'k6/execution';
import { TREND_STATS, writeSummary } from '../../ADR-009/scenarios/lib/common.js';
import {
  closeBeforeLongThink, closeConn, codeOf17, confirmBody17, confirmReq17, holdBody17, holdReq, kindOf17, payStartReq, pgBody17, pgReq17, psBody17,
  seatOf17, send, sessionAttrs17, sleepUntilSrv, CLOCK_OFFSET_MS, PG_URL,
} from './lib17.js';

const E = (k) => {
  const v = __ENV[k];
  if (v === undefined || v === '') throw new Error(`환경변수 ${k} 없음(run17.sh가 plan17 --render k6.env를 넘긴다)`);
  return v;
};
const N = (k) => Number(E(k));
E('OUT_DIR');   // k6-summary.json 위치(ADR-009 writeSummary)
E('BASE_URL');
E('PG_URL');
E('CLOCK_OFFSET_MS');
const REP = E('REP');
const CAND = E('CAND');
const CELL = E('CELL');
const PG_OP = E('PG_OP');
if (PG_OP !== 'pay' && PG_OP !== 'authorize') throw new Error(`PG_OP ${PG_OP}`);
const PG_OK_STATE = PG_OP === 'pay' ? 'CAPTURED' : 'AUTHORIZED';
const RATE = N('RATE');
const D_ARR_S = N('D_ARR_S');
const PLAN_N = N('N_TOTAL');
const CYCLE = E('KIND_CYCLE').split(',');
for (const k of CYCLE) if (!['ok', 'late', 'noconf', 'abandon', 'retry'].includes(k)) throw new Error(`KIND_CYCLE 종류 ${k}`);
const P = {
  tMin: N('T_MIN_S'), tMax: N('T_MAX_S'), payMedian: N('PAY_MEDIAN_S'), paySigma: N('PAY_SIGMA'), payMax: N('PAY_MAX_S'),
  gMin: N('GAP_MIN_S'), gMax: N('GAP_MAX_S'), rMin: N('RETRY_MIN_S'), rMax: N('RETRY_MAX_S'), lMin: N('LATE_MIN_S'), lMax: N('LATE_MAX_S'),
};
const SCHEDULE = N('SCHEDULE');
const SPARE = N('SEAT_SPARE');
const USER_BASE = N('USER_BASE');
const durMs = Math.floor(D_ARR_S * 1000 - 500 / RATE);

export const options = {
  scenarios: {
    main: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${durMs}ms`, preAllocatedVUs: N('PRE_VUS'), maxVUs: N('MAX_VUS'),
      gracefulStop: `${N('GRACE_S')}s`, exec: 'mainFlow',
    },
  },
  summaryTrendStats: TREND_STATS,
  noVUConnectionReuse: true,
  // url 태그 제외: 요청 URL에 좌석 · 홀드 번호 · uid가 들어가 세션마다 새 시계열이 생긴다(name 태그로 요청 종류를 가른다)
  systemTags: ['status', 'method', 'name', 'group', 'check', 'error', 'error_code', 'scenario', 'expected_response', 'proto', 'tls_version'],
};

export function setup() {
  return { startedAt: Date.now() };
}

const srvNow = () => Date.now() + CLOCK_OFFSET_MS;
const fin = (x) => (Number.isFinite(x) ? x : null);

function call(req) {   // 요청 하나 — 송신 · 수신 서버 시각(ms)과 함께
  const ts = srvNow();
  const res = send(req);
  return { res, ts, tr: srvNow() };
}
function brief(c) {
  return { st: c.res.status, code: codeOf17(c.res), ec: c.res.error_code || 0, ts: c.ts, tr: c.tr };
}
function finish(row, end) {
  row.end = end;
  console.log(JSON.stringify(row));
}

export function mainFlow() {
  const sid = exec.scenario.iterationInTest;
  if (sid >= PLAN_N) return;
  const kind = kindOf17(sid, CYCLE);
  const a = sessionAttrs17(REP, sid, P);
  const { schedule, seat } = seatOf17(sid, SPARE, SCHEDULE);
  const user = USER_BASE + sid;
  const row = {
    k: 's', v: 1, sid, kind, rep: Number(REP), cand: CAND, cell: CELL, user, seat, T: a.T, P: a.P, G: a.G, R: a.R, L: a.L,
    uid: null, at: null, h: null, p: null, g: null, c1: null, c2: null, end: null,
  };
  // ① 선점 — 다음 앱 요청(payment-start)까지 T
  const hc = call(closeBeforeLongThink(holdReq(schedule, seat, user, 'hold'), a.T * 1000));
  const hb = holdBody17(hc.res);
  row.h = Object.assign(brief(hc), { hid: hb.hid, exp: fin(hb.exp) });
  row.h.ok = hc.res.status === 201 && hb.hid !== null && Number.isFinite(hb.exp);
  if (!row.h.ok) { finish(row, 'hold'); return; }
  sleepUntilSrv(hc.tr + a.T * 1000);
  // ② payment-start — 다음 앱 요청: ok · retry = P + G 뒤 확정 · late = 연장 만료 + L(≈ W + L) · noconf · abandon = 없음(마지막 앱 요청)
  const last = kind === 'noconf' || kind === 'abandon';
  const psReq = payStartReq(hb.hid, user, 'pay_start');
  const pc = call(last ? closeConn(psReq) : closeBeforeLongThink(psReq, kind === 'late' ? 60000 : (a.P + a.G) * 1000));
  const pb = psBody17(pc.res);
  row.p = Object.assign(brief(pc), { hid: pb.hid, exp: fin(pb.exp), pb: fin(pb.pb), pws: pb.pws, ext: pb.ext, uid: pb.uid });
  // 본문 계약: paymentUid · expiresAt · payBy · payWithinSeconds · extended + holdId = 요청한 홀드
  row.p.ok = pc.res.status === 200 && pb.uid !== null && Number.isFinite(pb.exp) && Number.isFinite(pb.pb) && pb.pws !== null
    && typeof pb.ext === 'boolean' && pb.hid === hb.hid;
  if (!row.p.ok) { finish(row, 'pay_start'); return; }
  row.uid = pb.uid;
  if (kind === 'abandon') { finish(row, 'abandon'); return; }
  // ③ PG 결제(R · S pay → CAPTURED · D authorize → AUTHORIZED) — P 뒤
  sleepUntilSrv(pc.tr + a.P * 1000);
  const gc = call(closeConn(pgReq17(PG_OP, pb.uid, `pg_${PG_OP}`)));
  const gb = pgBody17(gc.res);
  row.g = { op: PG_OP, st: gc.res.status, code: gb.code, state: gb.state, ec: gc.res.error_code || 0, ts: gc.ts, tr: gc.tr };
  row.g.ok = gc.res.status === 200 && gb.state === PG_OK_STATE;
  if (!row.g.ok) { finish(row, 'pg'); return; }
  if (kind === 'noconf') { finish(row, 'noconf'); return; }
  // ④ 확정 — late = 연장 만료(payment-start 응답 expiresAt) + L · 그 밖 = PG 응답 + G
  row.at = kind === 'late' ? pb.exp + a.L * 1000 : gc.tr + a.G * 1000;
  sleepUntilSrv(row.at);
  const c1req = confirmReq17(hb.hid, user, pb.uid, 'confirm');
  const c1 = call(kind === 'retry' ? c1req : closeConn(c1req));
  row.c1 = Object.assign(brief(c1), confirmBody17(c1.res));
  if (kind === 'retry') {   // 첫 응답을 버리고(클라이언트가 응답을 잃음) 같은 uid로 다시 — 상태와 무관하게 늘 보낸다(N17-3 재생 대조)
    sleepUntilSrv(c1.tr + a.R * 1000);
    const c2 = call(closeConn(confirmReq17(hb.hid, user, pb.uid, 'confirm_retry')));
    row.c2 = Object.assign(brief(c2), confirmBody17(c2.res));
  }
  finish(row, 'done');
}

export function handleSummary(data) {
  data.adr017 = { rep: REP, cand: CAND, cell: CELL, pg_op: PG_OP, rate: RATE, d_arr_s: D_ARR_S, plan_n: PLAN_N, cycle: CYCLE, params: P, spare: SPARE, pg_url: PG_URL };
  return writeSummary(data);
}
