// ADR-015 공통 — ADR-014 lib14.js(결정적 난수 · 세션 해시 · 선점/확정 호출 · 응답 분류 · keep-alive 처방)를 그대로 다시 내보내고, 이 측정에 필요한 것만 더한다:
//   ④ `POST /api/holds/{holdId}/payment-start`(§3.4 · PaymentStartController) 호출 · 세션 속성(확정 오프셋 c · 이중 확정 · 재선점 VU) · 좌석/사용자 대역 · 오프셋 묶음 이름.
// lib14.js는 고치지 않는다(동결 사본에서 import — ADR-014 하네스 수정 금지).
// keep-alive(ADR-014 §결정-3 · 06c): 생각 시간 ≥ 50초면 그 앞 요청을 Connection: close로(lib14 closeBeforeLongThink) — 이 측정의 생각 시간은 약 TTL(30초)이라
//   지금 값에서는 붙지 않지만, TTL을 늘려도 같은 EOF가 5xx 지표를 흐리지 않게 모든 '잠들기 앞' 요청에 건다(초안 측정-3.6).
export {
  BASE_URL, CLOCK_OFFSET_MS, KEEPALIVE_SAFE_MS, closeBeforeLongThink, closeConn, codeOf, confirmReq, crnH1, crnH2, crnN, fnv1a, holdBody, holdReq, isoMs,
  mulberry32, recordSession, send, sleepUntilSrv,
} from '../../ADR-014/scenarios/lib14.js';
import { BASE_URL, fnv1a, mulberry32 } from '../../ADR-014/scenarios/lib14.js';

export function payStartReq(holdId, userId, name) {
  return {
    method: 'POST', url: `${BASE_URL}/api/holds/${holdId}/payment-start`, body: null,
    params: { headers: { 'X-User-Id': String(userId) }, tags: { name: name || 'pay_start' }, timeout: '30s' },
  };
}

// 세션 속성(공통 난수 — 씨앗 (REP, 'adr015', 세션 번호)): main · rehold 두 시나리오가 같은 세션 번호로 같은 속성을 다시 뽑는다(재선점 VU가 그 세션의 좌석 · 짝 여부를 안다).
//   c_ms(확정 송신 = 서버 만료 e + c) ~ U[−(delay + margin), +margin] · dup(같은 홀드 이중 확정) · rehold(그 좌석을 노리는 재선점 VU 붙임) ·
//   xb(④ 경계 흐름 — payment-start가 준 연장 만료 e′ 기준으로 확정 · 리뷰 M15-5). 뽑는 순서는 앞 셋 뒤에 xb를 더했다(앞 셋의 값은 그대로 — fExtB 0이면 xb는 늘 거짓).
export function sessionAttrs15(rep, sid, p) {
  const r = mulberry32(fnv1a(`adr015|${rep}|${sid}`));
  const c_ms = Math.round(-(p.delayMs + p.marginMs) + r() * (p.delayMs + 2 * p.marginMs));
  const dup = r() < p.fDup;
  const rehold = r() < p.rho;
  const xb = r() < (p.fExtB || 0);
  return { c_ms, dup, rehold, xb };
}

// 재선점 시도 오프셋(ms — 기준 만료 시각 e 또는 ④ 경계 흐름의 e′ 기준 · 리뷰 M15-6):
//   ① 조밀 구간 [denseFrom, denseUntil] denseStep 간격(경합 창 = 만료 직후 ~ 확정 쓰기 — 지연 폭 안 · step 0이면 없음) →
//   ② 성긴 구간 from부터 step 간격으로 untilMs까지(배치가 풀어 준 좌석을 노리는 경쟁자 — 앞 15-09 설계). 오름차순 · 중복 없음.
export function reholdOffsets(d) {
  const out = [];
  if (d.denseStep > 0) for (let o = d.denseFrom; o <= d.denseUntil; o += d.denseStep) out.push(o);
  const last = out.length ? out[out.length - 1] : -Infinity;
  for (let o = d.from; o <= d.untilMs; o += d.step) if (o > last) out.push(o);
  return out;
}

// 확정 응답 분류(리뷰 M15-21 — ADR-015 §설계-3 공통 에러 계약 · N4): 200 = OK · 그 밖은 상태와 무관하게 본문 code를 읽는다(없으면 NOCODE).
//   ADR-009 errorCodeOf(lib14 codeOf)는 409만 본문을 읽어 404 · 403이 'HTTP404' · 'HTTP403'이 된다 — 엉뚱한 404(본문 없음 · 다른 code)도 계약 안으로 세던 것을 막는다.
//   요약기가 (HTTP 상태, code) 쌍을 허용 목록과 대조한다(st 태그와 짝). ADR-009 · ADR-014 파일은 고치지 않는다
export function confirmCodeOf(res) {
  if (res.status === 200) return 'OK';
  try {
    const c = res.json('code');
    if (c) return String(c);
  } catch (e) { /* 본문이 JSON이 아니다 */ }
  return 'NOCODE';
}

// 오프셋 묶음(요약기가 응답 분포를 가르는 이름 — 초안 측정-5 · ④ 경계 흐름은 e′ 기준으로 같은 이름):
//   pre  = c < −delay(지연을 다 자도 만료 전에 쓴다 — 경합 창 밖)
//   win  = −delay ≤ c < 0(만료 전 확인 · 만료 뒤 쓰기 — 경합 창 · converted_after_expiry가 생기는 자리)
//   post = c ≥ 0(만료 뒤 보낸 확정 — 409 HOLD_EXPIRED 또는 404가 계약 · ④는 연장돼 200)
export function cBucket(c_ms, delayMs) {
  if (c_ms >= 0) return 'post';
  return c_ms < -delayMs ? 'pre' : 'win';
}

// 좌석 = 세션마다 하나(겹치지 않음) — 회차 k = 1 + ⌊sid ÷ 회차당 좌석⌋ · 좌석 id = (k − 1) × 회차당 좌석 + (sid mod 회차당 좌석) + 1(LoadtestDataService.reset 좌석 id 규칙)
export function seatOf(sid, seatsPer) {
  const k = 1 + Math.floor(sid / seatsPer);
  return { schedule: k, seat: (k - 1) * seatsPer + (sid % seatsPer) + 1 };
}
