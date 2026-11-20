// ADR-018 시나리오 flow18(측정 명세 docs/plans/2026-11-20/adr-018-measurement · 하네스 설계 §1~4): 인접 2석 세션. 방식(B0 · B1 · split · one-shot)과 셀(low · mid · high · edge)에 따라
// 같은 세션열이 다른 API를 탄다. 모든 값은 환경변수(run18.sh가 plan18.py --render의 k6.env + BASE_URL · PG_URL · OUT_DIR · CLOCK_OFFSET_MS를 넘긴다 — 기본값에 기대지 않는다).
//   main 고정 도착률 RATE/s · D_ARR_S초 — 세션 sid · 종류 = KIND_CYCLE[sid mod 바퀴 길이] · 좌석 (s, s+1) · 같은 줄(lib18 seatsOf18 — i = ⌊U × H⌋ · s = ⌊i ÷ 49⌋ × 50 + i mod 49 + 1):
//   ① 선점
//      B0 · B1 : 1석 선점 s → (성공이면) 1석 선점 s+1. 둘째가 실패하면 B0은 첫 석을 놓지 않고 끝(TTL로 만료) · B1은 해제 API로 첫 석을 바로 놓고 끝.
//      split · one-shot : 묶음 선점 한 번(seatIds [s, s+1]).
//      2석을 다 못 잡으면 세션은 거기서 끝난다.
//   ② 종류별(본 셀)
//      ok      T 뒤 결제 시작(B: 1석 2번 · 묶음 1번) → P 뒤 PG pay(uid마다) → G 뒤 확정(B: 1석 2번 · 묶음 1번)
//      abandon 아무것도 안 함(만료 배치가 정리)
//      release D 뒤 해제(B: 1석 해제 2번 · 묶음: 묶음 해제 1번)
//   ② 종류별(경계 셀 — 묶음만)
//      edge_confirm 바로 결제 시작 → 바로 PG pay → 결제 시작 응답 expiresAt(E) + X에 확정
//      edge_ps      선점 응답 expiresAt(E) + 중심 + X에 결제 시작 → (200이면) 바로 PG pay → G 뒤 확정
//   동시 경합 셀(burst — 묶음만 · 하네스 설계 §8): 세션 2~4개를 한 묶음으로(lib18 bursts18 — 시드) 같은 쌍 또는 한 석 어긋난 쌍에 둔다. 묶음의 모든 세션은
//      스크립트 시작 + (묶음 마지막 세션 번호 ÷ 도착률 + BURST_LEAD_S)에 맞춰 잔 뒤 묶음 선점을 보낸다(각자 다른 VU — 반복 시작이 늦은 마지막 세션도 그 시각 앞에 깨어 있다).
//      평균 도착률은 그대로다. 그 뒤 흐름은 본 셀과 같다.
// 기록: 세션마다 결과 행 하나 = console.log(JSON 한 줄) → run18.sh가 `--log-format=json --console-output=<회차>/sessions18.log`로 받는다. 행 형식 = INTERFACE.md §3.
// 실패(상태 0 · 5xx · 계약 밖 본문)는 다시 보내지 않고 그 단계에서 세션을 끝낸다(end = 그 단계) — 분류는 outcome18(배수 뒤 DB + PG 원장과 대조).
import exec from 'k6/execution';
import { TREND_STATS, writeSummary } from '../../ADR-009/scenarios/lib/common.js';
import {
  BASE_URL, CLOCK_OFFSET_MS, PG_URL, closeBeforeLongThink, closeConn, codeOf17, confirmGroupReq, confirmReq17, holdGroupReq, holdReq, isoMs, jsonAt18 as j,
  bursts18, burstSeats18, kindOf18, payStartGroupReq, payStartReq, pgBody17, pgReq17, rejectOf18, releaseGroupReq, releaseReq, seatsOf18, send, sessionAttrs18, sleepUntilSrv,
} from './lib18.js';

const E = (k) => {
  const v = __ENV[k];
  if (v === undefined || v === '') throw new Error(`환경변수 ${k} 없음(run18.sh가 plan18 --render k6.env를 넘긴다)`);
  return v;
};
const N = (k) => Number(E(k));
E('OUT_DIR');
E('BASE_URL');
E('PG_URL');
E('CLOCK_OFFSET_MS');
const REP = E('REP');
const METHOD = E('METHOD');
const CELL = E('CELL');
if (!['B0', 'B1', 'split', 'one-shot'].includes(METHOD)) throw new Error(`METHOD ${METHOD}`);
if (!['low', 'mid', 'high', 'edge', 'burst'].includes(CELL)) throw new Error(`CELL ${CELL}`);
const GROUP = METHOD === 'split' || METHOD === 'one-shot';
if ((CELL === 'edge' || CELL === 'burst') && !GROUP) throw new Error('경계 · 동시 경합 셀은 split · one-shot만');
const RATE = N('RATE');
const D_ARR_S = N('D_ARR_S');
const PLAN_N = N('N_TOTAL');
const CYCLE = E('KIND_CYCLE').split(',');
const OK_KINDS = CELL === 'edge' ? ['edge_confirm', 'edge_ps'] : ['ok', 'abandon', 'release'];
for (const k of CYCLE) if (!OK_KINDS.includes(k)) throw new Error(`KIND_CYCLE 종류 ${k}(셀 ${CELL})`);
const P = {
  tMin: N('T_MIN_S'), tMax: N('T_MAX_S'), payMedian: N('PAY_MEDIAN_S'), paySigma: N('PAY_SIGMA'), payMax: N('PAY_MAX_S'),
  gMin: N('GAP_MIN_S'), gMax: N('GAP_MAX_S'), dMin: N('REL_MIN_S'), dMax: N('REL_MAX_S'), eps: N('EDGE_EPS_S'), psCenter: N('EDGE_PS_CENTER_S'),
};
const H_SPAN = N('H');
const BURST = E('BURST') === '1';
if (BURST !== (CELL === 'burst')) throw new Error('BURST 환경과 셀이 다르다');
const BURST_BLOCK = N('BURST_BLOCK');
const BURST_LEAD_S = N('BURST_LEAD_S');
const BURSTS = BURST ? bursts18(REP, N('N_TOTAL'), N('BURST_MIN'), N('BURST_MAX')) : null;
const SCHEDULE = N('SCHEDULE');
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
  // url 태그 제외(좌석 · 홀드 · 묶음 번호 · uid가 URL에 들어가 세션마다 새 시계열이 생긴다 — name 태그로 요청 종류를 가른다)
  systemTags: ['status', 'method', 'name', 'group', 'check', 'error', 'error_code', 'scenario', 'expected_response', 'proto', 'tls_version'],
};

const srvNow = () => Date.now() + CLOCK_OFFSET_MS;
const fin = (x) => (Number.isFinite(x) ? x : null);

function call(req) {
  const ts = srvNow();
  const res = send(req);
  return { res, ts, tr: srvNow() };
}
function brief(op, c) {
  return { op, st: c.res.status, code: codeOf17(c.res), ec: c.res.error_code || 0, ts: c.ts, tr: c.tr };
}
function finish(row, end) {
  row.end = end;
  console.log(JSON.stringify(row));
}
const sameSeats = (a, b) => Array.isArray(a) && a.length === b.length && a.every((x, i) => x === b[i]);

// ---- 단계 호출(응답 객체를 행 목록에 넣고 돌려준다) ---------------------------------------------------------------------------
function hold1(row, seat, user, waitMs, at) {
  const c = call(closeBeforeLongThink(holdReq(SCHEDULE, seat, user, 'hold'), waitMs));
  const hid = j(c.res, 'holdId');
  const exp = fin(isoMs(j(c.res, 'expiresAt')));
  const o = Object.assign(brief('hold', c), { seat, rj: rejectOf18(c.res), hid, exp });
  if (at !== undefined) o.at = at;   // 본 셀 첫 선점: 예정 송신(서버 ms — 리뷰 H3-5)
  o.ok = c.res.status === 201 && hid !== null && exp !== null && j(c.res, 'seatId') === seat;
  row.hold.push(o);
  return o;
}
function holdG(row, seats, user, waitMs, at) {
  const c = call(closeBeforeLongThink(holdGroupReq(SCHEDULE, seats, user, 'hold_group'), waitMs));
  const gid = j(c.res, 'groupId');
  const holds = j(c.res, 'holds');
  const hs = Array.isArray(holds) ? holds : [];
  const exp = fin(isoMs(j(c.res, 'expiresAt')));
  const o = Object.assign(brief('hold_group', c), { seats, rj: rejectOf18(c.res), gid, hids: hs.map((h) => (h ? h.holdId : null)), exp });
  if (at !== undefined) o.at = at;   // 동시 경합 셀: 묶음 예정 송신(서버 ms)
  o.cn = c.res.timings ? c.res.timings.connecting : null;   // 연결 맺기 시간(ms) — 동시 경합 셀은 미리 맺은 연결을 다시 써서 0이어야 한다(리뷰 H1-8)
  o.bl = c.res.timings ? c.res.timings.blocked : null;      // 연결 대기 시간(ms)
  o.ok = c.res.status === 201 && gid !== null && exp !== null && sameSeats(hs.map((h) => (h ? h.seatId : null)), seats) && o.hids.every((x) => x !== null);
  row.hold.push(o);
  return o;
}
function rel(row, op, id, user, last) {
  const req = op === 'release' ? releaseReq(id, user, 'release') : releaseGroupReq(id, user, 'release_group');
  const c = call(last ? closeConn(req) : req);
  const o = Object.assign(brief(op, c), { id });
  o.ok = c.res.status === 204;
  row.rel.push(o);
  return o;
}
function ps(row, op, id, user, waitMs, at) {
  const req = op === 'pay_start' ? payStartReq(id, user, 'pay_start') : payStartGroupReq(id, user, 'pay_start_group');
  const c = call(closeBeforeLongThink(req, waitMs));
  const pws = j(c.res, 'payWithinSeconds');
  const uid = j(c.res, 'paymentUid');
  const rid = j(c.res, op === 'pay_start' ? 'holdId' : 'groupId');
  const o = Object.assign(brief(op, c), {
    id, at: at === undefined ? null : at, exp: fin(isoMs(j(c.res, 'expiresAt'))), pb: fin(isoMs(j(c.res, 'payBy'))), pws: typeof pws === 'number' ? pws : null,
    ext: j(c.res, 'extended'), uid: typeof uid === 'string' && uid.length > 0 ? uid : null,
  });
  o.ok = c.res.status === 200 && o.uid !== null && o.exp !== null && o.pb !== null && o.pws !== null && typeof o.ext === 'boolean' && rid === id;
  row.ps.push(o);
  return o;
}
function pay(row, uid) {
  const c = call(closeConn(pgReq17('pay', uid, 'pg_pay')));
  const b = pgBody17(c.res);
  const o = Object.assign(brief('pg_pay', c), { uid, state: b.state });
  o.code = b.code;
  o.ok = c.res.status === 200 && b.state === 'CAPTURED';
  row.pay.push(o);
  return o;
}
function cf(row, op, id, user, uid, seats, at, last) {
  const req = op === 'confirm' ? confirmReq17(id, user, uid, 'confirm') : confirmGroupReq(id, user, uid, 'confirm_group');
  const c = call(last ? closeConn(req) : req);
  let rids;
  let rseats;
  if (op === 'confirm') {
    const r = j(c.res, 'reservationId');
    const s = j(c.res, 'seatId');
    rids = r === null ? [] : [r];
    rseats = s === null ? [] : [s];
  } else {
    const rs = j(c.res, 'reservations');
    const a = Array.isArray(rs) ? rs : [];
    rids = a.map((x) => (x ? x.reservationId : null));
    rseats = a.map((x) => (x ? x.seatId : null));
  }
  const cp = j(c.res, 'compensation');
  const o = Object.assign(brief(op, c), {
    id, uid, at: at === undefined ? null : at, rids, rseats,
    cp: cp && typeof cp === 'object' ? { type: cp.type === undefined ? null : cp.type, status: cp.status === undefined ? null : cp.status } : null,
  });
  o.ok = c.res.status === 200 && rids.every((x) => x !== null) && sameSeats(rseats, seats)
    && (op === 'confirm' || j(c.res, 'status') === 'CONFIRMED');
  row.cf.push(o);
  return o;
}

// ---- 흐름 ---------------------------------------------------------------------------------------------------------------------
function payThenConfirm(row, user, ids, uids, seatsOf, gapS, firstAt) {
  // PG pay(uid마다) → 확정(id마다). firstAt = 첫 확정 예정 송신 시각(null이면 마지막 PG 응답 + G)
  let last = null;
  for (const uid of uids) {
    last = pay(row, uid);
    if (!last.ok) return 'pg';
  }
  const at = firstAt !== null ? firstAt : last.tr + gapS * 1000;
  sleepUntilSrv(at);
  const op = GROUP ? 'confirm_group' : 'confirm';
  for (let i = 0; i < ids.length; i++) cf(row, op, ids[i], user, uids[i], seatsOf[i], i === 0 ? at : null, i === ids.length - 1);
  return 'done';
}

export function mainFlow() {
  const sid = exec.scenario.iterationInTest;
  if (sid >= PLAN_N) return;
  const kind = kindOf18(sid, CYCLE);
  const a = sessionAttrs18(REP, sid, P);
  const bk = BURST ? BURSTS[sid] : null;
  const seats = BURST ? burstSeats18(bk[0], a.U, BURST_BLOCK) : seatsOf18(a.U, H_SPAN);
  const user = USER_BASE + sid;
  const row = {
    k: 's', v: 1, sid, kind, rep: Number(REP), method: METHOD, cell: CELL, user, seats, T: a.T, P: a.P, G: a.G, D: a.D, U: a.U, X: a.X,
    hold: [], rel: [], ps: [], pay: [], cf: [], got: null, end: null,
  };
  if (BURST) row.burst = bk[0];
  // 선점 뒤 다음 앱 요청까지의 대기(ms) — ok = T · release = D · 그 밖 = 0(곧바로 또는 없음)
  const waitAfterHold = kind === 'ok' ? a.T * 1000 : (kind === 'release' ? a.D * 1000 : 0);
  // ① 선점 — 예정 송신(서버 ms): 본 · 경계 셀 = 스크립트 시작 + sid ÷ 도착률(고정 도착률이 이 반복을 시작할 시각 · 리뷰 H3-5) · 동시 경합 셀 = 묶음 시각
  const schedAt = exec.scenario.startTime + (sid / RATE) * 1000 + CLOCK_OFFSET_MS;
  let ids;          // 결제 시작 · 확정 대상(B: holdId 2개 · 묶음: groupId 1개)
  let seatsOf;      // 확정이 돌려줄 좌석(B: [[s], [s+1]] · 묶음: [[s, s+1]])
  let holdTr;
  let holdExp;
  if (GROUP) {
    let at = schedAt;
    if (BURST) {   // 묶음의 세션들이 같은 순간에 보낸다: 스크립트 시작(PC ms — 모든 VU 공통) + (묶음 마지막 세션 ÷ 도착률 + 여유) — 각 VU가 그 시각까지 잔다
      // 대기 전에 같은 반복에서 가벼운 요청으로 연결을 미리 맺는다 — 선점은 그 연결을 다시 쓴다(연결 맺기가 송신 폭에 섞이지 않게 · 리뷰 H1-8).
      //   끝점 = /actuator/metrics/process.uptime(DB를 쓰지 않는다 — /actuator/health는 DB 상태 검사를 탄다 · liveness 그룹은 probes를 켜지 않아 없다 · 메인 결정 2026-11-20)
      const pre = call({ method: 'GET', url: `${BASE_URL}/actuator/metrics/process.uptime`, body: null, params: { headers: {}, tags: { name: 'preconnect' }, timeout: '30s' } });
      row.pre = { st: pre.res.status, ts: pre.ts, tr: pre.tr, cn: pre.res.timings ? pre.res.timings.connecting : null };
      const pcAt = exec.scenario.startTime + (bk[2] / RATE + BURST_LEAD_S) * 1000;
      at = pcAt + CLOCK_OFFSET_MS;
      sleepUntilSrv(at);
    }
    const h = holdG(row, seats, user, waitAfterHold, at);
    if (!h.ok) { row.got = 'none'; finish(row, 'hold'); return; }
    ids = [h.gid];
    seatsOf = [seats];
    holdTr = h.tr;
    holdExp = h.exp;
  } else {
    const h1 = hold1(row, seats[0], user, 0, schedAt);
    if (!h1.ok) { row.got = 'none'; finish(row, 'hold'); return; }
    const h2 = hold1(row, seats[1], user, waitAfterHold);
    if (!h2.ok) {
      row.got = 'first';
      if (METHOD === 'B1') rel(row, 'release', h1.hid, user, true);   // B1: 둘째가 실패하면 첫 석을 바로 놓는다
      finish(row, 'partial');
      return;
    }
    ids = [h1.hid, h2.hid];
    seatsOf = [[seats[0]], [seats[1]]];
    holdTr = h2.tr;
    holdExp = h1.exp;
  }
  row.got = 'both';
  if (kind === 'abandon') { finish(row, 'abandon'); return; }
  if (kind === 'release') {
    sleepUntilSrv(holdTr + a.D * 1000);
    const op = GROUP ? 'release_group' : 'release';
    for (let i = 0; i < ids.length; i++) rel(row, op, ids[i], user, i === ids.length - 1);
    finish(row, 'release');
    return;
  }
  const psOp = GROUP ? 'pay_start_group' : 'pay_start';
  if (kind === 'ok') {
    sleepUntilSrv(holdTr + a.T * 1000);
    const uids = [];
    for (let i = 0; i < ids.length; i++) {
      const p = ps(row, psOp, ids[i], user, i === ids.length - 1 ? (a.P + a.G) * 1000 : 0);
      if (!p.ok) { finish(row, 'pay_start'); return; }
      uids.push(p.uid);
    }
    sleepUntilSrv(row.ps[row.ps.length - 1].tr + a.P * 1000);
    finish(row, payThenConfirm(row, user, ids, uids, seatsOf, a.G, null));
    return;
  }
  if (kind === 'edge_confirm') {   // 바로 결제 시작 → 바로 PG pay → E + X에 확정(E = 결제 시작 응답 expiresAt)
    const p = ps(row, psOp, ids[0], user, 0);
    if (!p.ok) { finish(row, 'pay_start'); return; }
    finish(row, payThenConfirm(row, user, ids, [p.uid], seatsOf, a.G, p.exp + a.X * 1000));
    return;
  }
  // edge_ps: 선점 응답 E + 중심 + X에 결제 시작 → (200이면) 바로 PG pay → G 뒤 확정
  const at = holdExp + (P.psCenter + a.X) * 1000;
  sleepUntilSrv(at);
  const p = ps(row, psOp, ids[0], user, 0, at);
  if (!p.ok) { finish(row, 'pay_start'); return; }
  finish(row, payThenConfirm(row, user, ids, [p.uid], seatsOf, a.G, null));
}

export function handleSummary(data) {
  data.adr018 = { rep: REP, method: METHOD, cell: CELL, rate: RATE, d_arr_s: D_ARR_S, plan_n: PLAN_N, cycle: CYCLE, params: P, h: H_SPAN, pg_url: PG_URL };
  return writeSummary(data);
}
