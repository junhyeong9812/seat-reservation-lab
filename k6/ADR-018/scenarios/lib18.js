// ADR-018 공통 — ADR-017 lib17.js(→ lib15.js → lib14.js: 결정적 난수 · 1석 선점 · 1석 결제 시작 · 발급 uid 확정 · 흉내 PG 호출 · keep-alive 처방 · 서버 시각 잠들기 ·
//   응답 코드 · 본문 읽기)를 그대로 다시 내보내고, 이 측정에 필요한 것만 더한다:
//   세션 속성(T · P · G · D · U · X — (회차, 세션 번호) 시드 · plan18.py session_attrs와 같은 식 · 같은 순서) · 좌석 쌍(s, s+1) · 묶음 API 4개 · 1석 해제 · 원인 표지 헤더.
// lib17.js · lib15.js · lib14.js는 고치지 않는다(동결 사본에서 import). 행 형식 · 의미 = k6/ADR-018/INTERFACE.md §3.
export {
  BASE_URL, CLOCK_OFFSET_MS, PG_URL, closeBeforeLongThink, closeConn, codeOf17, confirmReq17, holdReq, isoMs, payStartReq, pgBody17, pgReq17, send, sleepUntilSrv,
} from '../../ADR-017/scenarios/lib17.js';
import { BASE_URL } from '../../ADR-017/scenarios/lib17.js';
import { fnv1a, mulberry32, REJECT_HEADER } from '../../ADR-014/scenarios/lib14.js';

// 세션 속성 — 씨앗 'adr018|회차|세션 번호'(plan18.py session_attrs와 같은 식 · 같은 순서 — 종류 · 방식 · 셀과 무관하게 다 뽑는다):
//   T ~ U[tMin, tMax] · P = min(exp(ln m + σ z), payMax)(Box-Muller) · G ~ U[gMin, gMax] · D ~ U[dMin, dMax] · U ~ U[0, 1) · X ~ U[−eps, +eps]
export function sessionAttrs18(rep, sid, p) {
  const r = mulberry32(fnv1a(`adr018|${rep}|${sid}`));
  const T = p.tMin + r() * (p.tMax - p.tMin);
  const u1 = r();
  const u2 = r();
  const z = Math.sqrt(-2.0 * Math.log(1.0 - u1)) * Math.cos(2.0 * Math.PI * u2);
  const P = Math.min(Math.exp(Math.log(p.payMedian) + p.paySigma * z), p.payMax);
  const G = p.gMin + r() * (p.gMax - p.gMin);
  const D = p.dMin + r() * (p.dMax - p.dMin);
  const U = r();
  const X = -p.eps + r() * (2 * p.eps);
  return { T, P, G, D, U, X };
}

// 세션 종류 = 바퀴[sid mod 바퀴 길이](plan18.py kind_cycle)
export function kindOf18(sid, cycle) {
  return cycle[sid % cycle.length];
}

// 좌석 쌍(plan18.py seat_of): 시작 위치 번호 i = ⌊U × H⌋ → 줄 r = ⌊i ÷ 49⌋ · 칸 c = i mod 49 · s = r × 50 + c + 1 · (s, s+1) — 두 석이 늘 같은 줄(한 줄 50석)
export const SEATS_PER_ROW = 50;
export function seatsOf18(U, H) {
  const i = Math.floor(U * H);
  const s = Math.floor(i / (SEATS_PER_ROW - 1)) * SEATS_PER_ROW + (i % (SEATS_PER_ROW - 1)) + 1;
  return [s, s + 1];
}

// 동시 경합 묶음(plan18.py bursts · burst_of · seats_for와 같은 식): 묶음 크기 = lo + ⌊r × (hi − lo + 1)⌋(씨앗 'adr018-burst|회차|묶음 번호') ·
//   세션마다 [묶음 번호, 첫 세션, 마지막 세션]을 돌려주는 표(세션 수만큼 — VU 초기화에서 한 번 만든다)
export function bursts18(rep, nTotal, lo, hi) {
  const bySid = [];
  let first = 0;
  let k = 0;
  while (first < nTotal) {
    const r = mulberry32(fnv1a(`adr018-burst|${rep}|${k}`));
    const size = Math.min(lo + Math.floor(r() * (hi - lo + 1)), nTotal - first);
    for (let i = 0; i < size; i++) bySid.push([k, first, first + size - 1]);
    first += size;
    k += 1;
  }
  return bySid;
}
// 동시 경합 셀 좌석(plan18.py seats_for): 줄마다 칸 ⌊50 ÷ block⌋개 · 칸 k → b = ⌊k ÷ 칸 수⌋ × 50 + (k mod 칸 수) × block + 1 · (b + o, b + o + 1) · o = 0(U < 0.5) | 1
export function burstSeats18(k, U, block) {
  const per = Math.floor(SEATS_PER_ROW / block);
  const b = Math.floor(k / per) * SEATS_PER_ROW + (k % per) * block + 1 + (U < 0.5 ? 0 : 1);
  return [b, b + 1];
}

// 원인 표지 헤더(측정 프로필 — 1석 nowait · rule · 묶음 group-<cause>) — 없으면 null
export function rejectOf18(res) {
  const h = res.headers ? (res.headers[REJECT_HEADER] || res.headers[REJECT_HEADER.toLowerCase()]) : undefined;
  return h === undefined || h === null || h === '' ? null : String(h);
}

const H = (userId, json) => (json ? { 'X-User-Id': String(userId), 'Content-Type': 'application/json' } : { 'X-User-Id': String(userId) });

// 묶음 선점 POST /api/schedules/{scheduleId}/hold-groups {"seatIds": [..]}
export function holdGroupReq(scheduleId, seatIds, userId, name) {
  return {
    method: 'POST', url: `${BASE_URL}/api/schedules/${scheduleId}/hold-groups`, body: JSON.stringify({ seatIds }),
    params: { headers: H(userId, true), tags: { name: name || 'hold_group' }, timeout: '30s' },
  };
}
// 1석 해제 DELETE /api/holds/{holdId}
export function releaseReq(holdId, userId, name) {
  return { method: 'DELETE', url: `${BASE_URL}/api/holds/${holdId}`, body: null, params: { headers: H(userId, false), tags: { name: name || 'release' }, timeout: '30s' } };
}
// 묶음 해제 DELETE /api/hold-groups/{groupId}
export function releaseGroupReq(groupId, userId, name) {
  return {
    method: 'DELETE', url: `${BASE_URL}/api/hold-groups/${groupId}`, body: null,
    params: { headers: H(userId, false), tags: { name: name || 'release_group' }, timeout: '30s' },
  };
}
// 묶음 결제 시작 POST /api/hold-groups/{groupId}/payment-start
export function payStartGroupReq(groupId, userId, name) {
  return {
    method: 'POST', url: `${BASE_URL}/api/hold-groups/${groupId}/payment-start`, body: null,
    params: { headers: H(userId, false), tags: { name: name || 'pay_start_group' }, timeout: '30s' },
  };
}
// 묶음 확정 POST /api/hold-groups/{groupId}/confirm {"paymentUid": "..."}
export function confirmGroupReq(groupId, userId, uid, name) {
  return {
    method: 'POST', url: `${BASE_URL}/api/hold-groups/${groupId}/confirm`, body: JSON.stringify({ paymentUid: uid }),
    params: { headers: H(userId, true), tags: { name: name || 'confirm_group' }, timeout: '30s' },
  };
}

function j(res, path) {
  try {
    const v = res.json(path);
    return v === undefined ? null : v;
  } catch (e) {
    return null;
  }
}
export { j as jsonAt18 };
