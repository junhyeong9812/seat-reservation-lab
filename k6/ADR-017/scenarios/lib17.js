// ADR-017 공통 — ADR-015 lib15.js(→ ADR-014 lib14.js: 결정적 난수 · 선점 · payment-start 호출 · keep-alive 처방 · 서버 시각 잠들기)를 그대로 다시 내보내고, 이 측정에 필요한 것만 더한다:
//   세션 속성(T · P · G · R · L — (회차, 세션 번호) 시드 · plan17.py session_attrs와 같은 식 · 같은 순서) · 세션 종류(교차 배정 바퀴) · 좌석(여유 좌석 뒤) ·
//   발급 uid 확정 요청(lib14 confirmReq는 `pay-${user}-${hid}`를 보내 intent 경로가 아니다 — ADR-017 §구현 옛 하네스 주의) · 흉내 PG 호출 · 응답 본문 읽기.
// lib15.js · lib14.js는 고치지 않는다(동결 사본에서 import). 행 형식 · 의미 = k6/ADR-017/INTERFACE.md §3.
export {
  BASE_URL, CLOCK_OFFSET_MS, closeBeforeLongThink, closeConn, holdReq, isoMs, payStartReq, send, sleepUntilSrv,
} from '../../ADR-015/scenarios/lib15.js';
import { BASE_URL, fnv1a, isoMs, mulberry32 } from '../../ADR-015/scenarios/lib15.js';

// 흉내 PG 주소(k6 PC에서 본 주소 — run17.sh가 http://<서버>:8104를 넘긴다). 빈 값이면 시나리오가 시작하지 않는다
export const PG_URL = __ENV.PG_URL || '';

// 세션 속성 — 씨앗 'adr017|회차|세션 번호'(plan17.py session_attrs와 같은 식 · 같은 순서 — 종류와 무관하게 다 뽑는다):
//   T ~ U[tMin, tMax] · P = min(exp(ln m + σ z), payMax) · z = √(−2 ln(1 − u1)) cos(2π u2)(Box-Muller) · G ~ U[gMin, gMax] · R ~ U[rMin, rMax] · L ~ U[lMin, lMax]
export function sessionAttrs17(rep, sid, p) {
  const r = mulberry32(fnv1a(`adr017|${rep}|${sid}`));
  const T = p.tMin + r() * (p.tMax - p.tMin);
  const u1 = r();
  const u2 = r();
  const z = Math.sqrt(-2.0 * Math.log(1.0 - u1)) * Math.cos(2.0 * Math.PI * u2);
  const P = Math.min(Math.exp(Math.log(p.payMedian) + p.paySigma * z), p.payMax);
  const G = p.gMin + r() * (p.gMax - p.gMin);
  const R = p.rMin + r() * (p.rMax - p.rMin);
  const L = p.lMin + r() * (p.lMax - p.lMin);
  return { T, P, G, R, L };
}

// 세션 종류 = 바퀴[sid mod 바퀴 길이](plan17.py kind_cycle — 시간 효과와 섞이지 않게)
export function kindOf17(sid, cycle) {
  return cycle[sid % cycle.length];
}

// 좌석 = 여유 좌석 뒤(id spare + sid + 1 · 회차 하나) — 대체 배정이 id 순으로 여유 좌석(1..spare)을 먼저 집는다(plan17.py seat_of)
export function seatOf17(sid, spare, schedule) {
  return { schedule, seat: spare + sid + 1 };
}

// 확정 — 본문 paymentUid = payment-start가 발급한 uid(D의 승인 키도 같은 uid — ADR-017 RD17-OQ2)
export function confirmReq17(holdId, userId, uid, name) {
  return {
    method: 'POST', url: `${BASE_URL}/api/holds/${holdId}/confirm`, body: JSON.stringify({ paymentUid: uid }),
    params: { headers: { 'X-User-Id': String(userId), 'Content-Type': 'application/json' }, tags: { name: name || 'confirm' }, timeout: '30s' },
  };
}

// 흉내 PG 결제 호출(op = pay | authorize) — 본문 없음(uid가 경로 · 서버가 payment-start에서 등록한 deadline으로 판정)
export function pgReq17(op, uid, name) {
  return {
    method: 'POST', url: `${PG_URL}/payments/${encodeURIComponent(uid)}/${op}`, body: null,
    params: { headers: {}, tags: { name: name || `pg_${op}` }, timeout: '30s' },
  };
}

// 응답 코드(앱 · PG 공통): 2xx = 'OK' · 그 밖 = 본문 code(없으면 'NOCODE' — 상태 0 · 본문 없는 5xx 포함). lib15 confirmCodeOf는 200만 OK라 선점 201이 NOCODE가 된다
export function codeOf17(res) {
  if (res.status >= 200 && res.status < 300) return 'OK';
  const c = j(res, 'code');
  return c ? String(c) : 'NOCODE';
}

function j(res, path) {
  try {
    const v = res.json(path);
    return v === undefined ? null : v;
  } catch (e) {
    return null;
  }
}

// 선점 201 본문: holdId · expiresAt(ms)
export function holdBody17(res) {
  return { hid: j(res, 'holdId'), exp: isoMs(j(res, 'expiresAt')) };
}

// payment-start 200 본문: holdId · expiresAt · payBy(ms) · payWithinSeconds · extended · paymentUid
export function psBody17(res) {
  const pws = j(res, 'payWithinSeconds');
  const uid = j(res, 'paymentUid');
  return {
    hid: j(res, 'holdId'), exp: isoMs(j(res, 'expiresAt')), pb: isoMs(j(res, 'payBy')), pws: typeof pws === 'number' ? pws : null,
    ext: j(res, 'extended'), uid: typeof uid === 'string' && uid.length > 0 ? uid : null,
  };
}

// 확정 응답 본문(200 = {reservationId, seatId, status, compensation?} · 거부 = {code, message, compensation?}): rid · seat · rs · cp
export function confirmBody17(res) {
  const cp = j(res, 'compensation');
  return {
    rid: j(res, 'reservationId'), seat: j(res, 'seatId'), rs: j(res, 'status'),
    cp: cp && typeof cp === 'object'
      ? { type: cp.type === undefined ? null : cp.type, status: cp.status === undefined ? null : cp.status, taskId: cp.taskId === undefined ? null : cp.taskId,
        rid: cp.reservationId === undefined ? null : cp.reservationId, seat: cp.seatId === undefined ? null : cp.seatId }
      : null,
  };
}

// 흉내 PG 응답 본문 {payment: {state …}, code}: state · code(200이면 'OK' · 본문 code가 없으면 'NOCODE')
export function pgBody17(res) {
  return { state: j(res, 'payment.state'), code: codeOf17(res) };
}
