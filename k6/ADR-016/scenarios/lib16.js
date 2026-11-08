// ADR-016 공통 — ADR-015 lib15.js(→ ADR-014 lib14.js: 결정적 난수 · 선점/확정 호출 · keep-alive 처방)를 그대로 다시 내보내고, 이 측정에 필요한 것만 더한다:
//   세션 속성(T · D — (회차, 세션 번호) 시드 · 로그정규 Box-Muller) · ⓒ 하네스 마감(outcome16.deadline과 같은 식) · 응답 본문(payBy · payWithinSeconds) 읽기 · 재선점 오프셋.
// lib15.js · lib14.js는 고치지 않는다(동결 사본에서 import).
export {
  BASE_URL, CLOCK_OFFSET_MS, closeBeforeLongThink, closeConn, confirmCodeOf, confirmReq, holdReq, isoMs, payStartReq, send, sleepUntilSrv,
} from '../../ADR-015/scenarios/lib15.js';
import { fnv1a, isoMs, mulberry32 } from '../../ADR-015/scenarios/lib15.js';

// 세션 속성 — 씨앗 'adr016|회차|세션 번호'(plan16.py session_attrs와 같은 식 · 같은 순서 — 요약기가 기록된 T · D를 다시 계산해 대조한다):
//   T ~ U[tMin, tMax]초 · D = exp(ln m + σ z) · z = √(−2 ln(1 − u1)) cos(2π u2)(Box-Muller) · rehold = r() < ρ · gx = ⌊r() × 격자 수⌋(ⓒ 경계 흐름 격자 번호)
export function sessionAttrs16(rep, sid, p) {
  const r = mulberry32(fnv1a(`adr016|${rep}|${sid}`));
  const T = p.tMin + r() * (p.tMax - p.tMin);
  const u1 = r();
  const u2 = r();
  const z = Math.sqrt(-2.0 * Math.log(1.0 - u1)) * Math.cos(2.0 * Math.PI * u2);
  const D = Math.exp(Math.log(p.dMedian) + p.dSigma * z);
  const rehold = r() < p.rho;
  const gx = Math.floor(r() * p.gridN);
  return { T, D, rehold, gx };
}

// ⓒ 하네스 결제 마감(ms · 서버 시계) = min(마감을 준 응답의 수신 + payWithinSeconds, 절대 payBy − 시계 오차 상한) — outcome16.deadline(DV16-R1)
export function deadline16(srcRecvMs, pws, payByMs, errMs) {
  return Math.min(srcRecvMs + pws * 1000, payByMs - errMs);
}

// 선점 201 · payment-start 200 본문: expiresAt · payBy(ms) · payWithinSeconds · holdId · extended. 못 읽으면 NaN · null
export function body16(res) {
  try {
    const pws = res.json('payWithinSeconds');
    return {
      holdId: res.json('holdId'), expMs: isoMs(res.json('expiresAt')), pbMs: isoMs(res.json('payBy')),
      pws: typeof pws === 'number' ? pws : NaN, extended: res.json('extended'),
    };
  } catch (e) {
    return { holdId: null, expMs: NaN, pbMs: NaN, pws: NaN, extended: null };
  }
}

// 재선점 시도 오프셋(ms — 원래 만료 e 기준): from부터 step 간격으로 untilMs까지
export function reholdOffsets16(from, step, untilMs) {
  const out = [];
  for (let o = from; o <= untilMs; o += step) out.push(o);
  return out;
}
