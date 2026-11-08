// ADR-016 시나리오 paid-flow(측정 명세 §1-3 · 설계-9.6): paid · cost 셀 세션. 모든 값은 환경변수(run16.sh가 plan16.py --render의 k6.env를 넘긴다 — 기본값에 기대지 않는다).
//   main   고정 도착률 RATE/s · D_ARR_S초 — 세션 sid · 흐름 = FLOW_CYCLE[sid mod 바퀴 길이](교차 배정 — 시간 효과가 흐름과 섞이지 않게):
//          선점 → T(잠) → (ⓑ · ④면 payment-start) → D(잠) → 확정 1회. T · D는 (회차, 세션 번호) 시드(lib16 sessionAttrs16).
//          D > CAP = 결제 실패(확정을 보내지 않음) · ⓒ 켬 = 하네스 마감(lib16 deadline16 — outcome16.deadline과 같은 식)을 넘으면 결제 실패.
//          ⓒ 경계 흐름(bd): D 대신 하네스 마감 − x(격자 GRID_MS — x < 0 = 마감 뒤라 보내지 않고 결제 실패로 셈 · CAP 미적용 — 마감 검사 전용 흐름)에 확정.
//   rehold 같은 도착률 · 같은 세션 번호 — 재선점 속성 세션만(ρ): 다른 사용자가 원래 만료 e(≈ 반복 시작 + TTL) 둘레(REHOLD_* 오프셋)에서 같은 좌석을 선점 시도 · 201이면 멈춘다.
// 기록:
//   paid 셀 — 세션 결과 행(요약기 입력): console.log(JSON 한 줄) → run16.sh가 `--log-format=json --console-output=<회차>/sessions16.log`로 파일에 받는다.
//     k6 지표(Counter 태그)로 세션마다 시각 · 값을 달면 태그 조합마다 시계열이 생겨 k6 메모리 · CSV가 세션 수만큼 커진다 → 지표 밖 파일로 뺀다(handleSummary에 묶지 않음 —
//     반복이 끊겨도 그때까지의 행이 남고, 요약기가 흐름별 계획 세션 수와 대조해 누락을 무효로 잡는다).
//     행(k='s'): fl · sid · T · D(초) · gx(경계 x ms) · uid(paymentUid) · ek(마감 계산 err ms) · dl(하네스 마감 ms) · pf(cap | deadline) · th(생각 끝) · tp(결제 시작) ·
//       h · p · c = 선점 · payment-start · 확정 응답 {st, code, ec(error_code), ts(송신), tr(수신) — 서버 시계 ms(PC + CLOCK_OFFSET_MS), exp · pb · pws · hid · ext · ok(본문)}
//     행(k='r'): 재선점 sid · n(보낸 시도) · got(201) · t(201 수신 ms)
//   cost 셀 — 세션 행 없음 · 집계 계수만: Counter s16{fl, out}(out = hold:<st>:<code> · pay:<st>:<code> · cap · dl · conf:<st>:<code> — 낮은 기수) · r16{got}.
// 연결: 50초 이상 쉬기 앞 요청은 Connection: close(lib14 closeBeforeLongThink) · 확정 · 재선점 마지막 시도 = 반복 끝 요청 close · noVUConnectionReuse(ADR-016 설계-5 처방).
// 판정 창 t0 = setup 반환 직전 PC 시각(k6-summary setup_data.startedAt — ADR-014 · 015와 같은 정의).
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { TREND_STATS, writeSummary } from '../../ADR-009/scenarios/lib/common.js';
import { seatOf } from '../../ADR-015/scenarios/lib15.js';
import {
  body16, closeBeforeLongThink, closeConn, confirmCodeOf, confirmReq, deadline16, holdReq, payStartReq, reholdOffsets16, send, sessionAttrs16, sleepUntilSrv,
  CLOCK_OFFSET_MS,
} from './lib16.js';

const E = (k) => {
  const v = __ENV[k];
  if (v === undefined || v === '') throw new Error(`환경변수 ${k} 없음(run16.sh가 plan16 --render k6.env를 넘긴다)`);
  return v;
};
const N = (k) => Number(E(k));
E('OUT_DIR');   // k6-summary.json 위치(ADR-009 writeSummary) — 기본 '.'에 기대지 않는다(리뷰 L2-6)
const REP = E('REP');
const CELL = E('CELL');
const PAID = CELL === 'paid';
const EXT = E('MODE') === 'conditional-extend';
const RATE = N('RATE');
const D_ARR_S = N('D_ARR_S');
const TTL_MS = N('TTL_S') * 1000;
const CAP_S = N('CAP_S');
const FLOWS = JSON.parse(E('FLOWS'));
const CYCLE = E('FLOW_CYCLE').split(',');
const GRID = E('GRID_MS').split(',').map(Number);
const ERR_MS = N('CLOCK_ERR_MS');
const P = { tMin: N('T_MIN_S'), tMax: N('T_MAX_S'), dMedian: N('D_MEDIAN_S'), dSigma: N('D_SIGMA'), rho: N('RHO'), gridN: GRID.length };
const REHOLD_OFFS = reholdOffsets16(N('REHOLD_FROM_MS'), N('REHOLD_STEP_MS'), N('REHOLD_UNTIL_S') * 1000);
const SEATS_PER = N('SEATS_PER_SCHEDULE');
const USER_MAIN = N('USER_MAIN');
const USER_REHOLD = N('USER_REHOLD');
const PLAN_N = Math.round(D_ARR_S * RATE);
const durMs = Math.floor(D_ARR_S * 1000 - 500 / RATE);
const GRACE = `${N('GRACE_S')}s`;
for (const f of CYCLE) if (!FLOWS[f]) throw new Error(`FLOW_CYCLE의 흐름 ${f}가 FLOWS에 없음`);

export const options = {
  scenarios: {
    main: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${durMs}ms`, preAllocatedVUs: N('PRE_VUS_MAIN'), maxVUs: N('MAX_VUS_MAIN'), gracefulStop: GRACE, exec: 'mainFlow' },
    rehold: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${durMs}ms`, preAllocatedVUs: N('PRE_VUS_REHOLD'), maxVUs: N('MAX_VUS_REHOLD'), gracefulStop: GRACE, exec: 'reholdFlow' },
  },
  summaryTrendStats: TREND_STATS,
  noVUConnectionReuse: true,
  // url 태그 제외: 요청 URL에 좌석 · 홀드 번호가 들어가 세션마다 새 시계열이 생긴다(name 태그로 요청 종류를 가른다)
  systemTags: ['status', 'method', 'name', 'group', 'check', 'error', 'error_code', 'scenario', 'expected_response', 'proto', 'tls_version'],
};

const s16 = new Counter('s16');
const r16 = new Counter('r16');

export function setup() {
  return { startedAt: Date.now() };
}

const srvNow = () => Date.now() + CLOCK_OFFSET_MS;

function call(req) {   // 요청 하나 — 송신 · 수신 서버 시각(ms)과 함께
  const ts = srvNow();
  const res = send(req);
  return { res, ts, tr: srvNow() };
}
function brief(c, withBody) {
  const o = { st: c.res.status, code: confirmCodeOf(c.res), ec: c.res.error_code || 0, ts: c.ts, tr: c.tr };
  if (withBody) {
    const b = body16(c.res);
    Object.assign(o, { hid: b.holdId, exp: b.expMs, pb: b.pbMs, pws: b.pws, ext: b.extended });
  }
  return o;
}
function finish(row, out) {
  if (PAID) console.log(JSON.stringify(row));
  else s16.add(1, { fl: row.fl, out });
}

export function mainFlow() {
  const sid = exec.scenario.iterationInTest;
  if (sid >= PLAN_N) return;
  const fl = CYCLE[sid % CYCLE.length];
  const f = FLOWS[fl];
  const callsPs = f.b === 1 || EXT;
  const a = sessionAttrs16(REP, sid, P);
  const x = f.bd ? GRID[a.gx] : null;
  const { schedule, seat } = seatOf(sid, SEATS_PER);
  const user = USER_MAIN + sid;
  const row = { k: 's', fl, sid, T: a.T, D: a.D, gx: x, ek: ERR_MS, uid: null, dl: null, pf: null, th: null, tp: null, h: null, p: null, c: null };
  // 선점 뒤 쉬는 시간: ⓑ · ④ = T · 그 밖 = T + D(경계 흐름은 마감까지 — 늘 50초 넘음)
  const gapMs = (callsPs ? a.T : a.T + (f.bd ? CAP_S : a.D)) * 1000;
  const hc = call(closeBeforeLongThink(holdReq(schedule, seat, user, 'hold'), gapMs));
  row.h = brief(hc, hc.res.status === 201);
  const hb = row.h;
  row.h.ok = hc.res.status === 201 && !!hb.hid && Number.isFinite(hb.exp) && Number.isFinite(hb.pb) && Number.isFinite(hb.pws);
  if (!row.h.ok) { finish(row, `hold:${hb.st}:${hb.code}`); return; }
  row.uid = `pay-${user}-${hb.hid}`;   // lib14 confirmReq와 같은 paymentUid
  sleepUntilSrv(hc.tr + a.T * 1000);
  row.th = srvNow();
  let src = { tr: hc.tr, pws: hb.pws, pb: hb.pb };
  let tp = row.th;
  if (callsPs) {
    const pc = call(closeBeforeLongThink(payStartReq(hb.hid, user, 'pay_start'), (f.bd ? CAP_S : a.D) * 1000));
    row.p = brief(pc, pc.res.status === 200);
    if (pc.res.status !== 200) { finish(row, `pay:${row.p.st}:${row.p.code}`); return; }
    // 본문 계약: expiresAt · payBy · payWithinSeconds · extended + holdId = 요청한 홀드(리뷰 L3-5 — 다른 홀드 응답을 정상으로 넘기지 않는다)
    row.p.ok = Number.isFinite(row.p.exp) && Number.isFinite(row.p.pb) && Number.isFinite(row.p.pws) && typeof row.p.ext === 'boolean'
      && row.p.hid !== null && row.p.hid !== undefined && row.p.hid === hb.hid;
    if (!row.p.ok) { finish(row, 'pay:200:body'); return; }
    src = { tr: pc.tr, pws: row.p.pws, pb: row.p.pb };
    tp = pc.tr;
  }
  row.tp = tp;
  let sendAt;
  if (f.bd) {
    row.dl = deadline16(src.tr, src.pws, src.pb, ERR_MS);
    if (x < 0) { row.pf = 'deadline'; finish(row, 'dl'); return; }   // 마감 뒤 격자 — 보내지 않는다(대조 계수)
    sendAt = row.dl - x;
  } else {
    if (a.D > CAP_S) { row.pf = 'cap'; finish(row, 'cap'); return; }
    if (f.c) {
      row.dl = deadline16(src.tr, src.pws, src.pb, ERR_MS);
      if (tp + a.D * 1000 > row.dl) { row.pf = 'deadline'; finish(row, 'dl'); return; }
    }
    sendAt = tp + a.D * 1000;
  }
  sleepUntilSrv(sendAt);
  const cc = call(closeConn(confirmReq(hb.hid, user, 'confirm')));   // 반복 끝 요청 — 서버가 먼저 닫는다
  row.c = brief(cc, false);
  finish(row, `conf:${row.c.st}:${row.c.code}`);
}

export function reholdFlow() {
  const sid = exec.scenario.iterationInTest;
  if (sid >= PLAN_N) return;
  const a = sessionAttrs16(REP, sid, P);
  if (!a.rehold) return;
  const { schedule, seat } = seatOf(sid, SEATS_PER);
  const user = USER_REHOLD + sid;
  const t0 = Date.now();
  let got = false;
  let t = null;
  let k = 0;
  for (k = 1; k <= REHOLD_OFFS.length; k++) {
    const wait = (t0 + TTL_MS + REHOLD_OFFS[k - 1] - Date.now()) / 1000;
    if (wait > 0) sleep(wait);
    const req = holdReq(schedule, seat, user, 'rehold');
    const r = send(k === REHOLD_OFFS.length ? closeConn(req) : req);
    if (r.status === 201) { got = true; t = srvNow(); break; }
  }
  if (PAID) console.log(JSON.stringify({ k: 'r', sid, n: Math.min(k, REHOLD_OFFS.length), got, t }));
  else r16.add(1, { got: got ? '1' : '0' });
}

export function handleSummary(data) {
  data.adr016 = { rep: REP, cell: CELL, ext: EXT, rate: RATE, d_arr_s: D_ARR_S, plan_n: PLAN_N, cycle: CYCLE, grid_ms: GRID, err_ms: ERR_MS, rehold_offsets_ms: REHOLD_OFFS };
  return writeSummary(data);
}
