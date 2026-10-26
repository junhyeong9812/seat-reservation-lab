// ADR-014 공통(측정-3): 결정적 난수(공통 난수 — 측정-3.5) · 세션 속성 기록(crn 해시 — 리뷰 MR-11) · 선점·확정·좌석 맵 호출과 응답 분류 · 원인 표지 헤더 집계(측정-5 M3).
// 호출·분류 규칙은 ADR-009 공통(scenarios/lib/common.js — 409는 에러가 아니다 · 5xx·코드 0만 에러 · 409 본문 code)을 그대로 쓰고(errorCodeOf · TREND_STATS · writeSummary),
// 응답 헤더 X-Seatlab-Reject(측정 프로필에서만 — ApiExceptionHandler.REJECT_HEADER)를 읽어야 해서 http 호출만 여기서 한다.
// 시각: Date.now() = PC 시계 ms. 서버 시각 = PC + CLOCK_OFFSET_MS(run.sh가 k6 직전에 잰 서버 − PC — 일정 맞추기에만 쓴다. 판정은 요약기가 clock-offset.json으로 다시 한다).
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { errorCodeOf } from '../../ADR-009/scenarios/lib/common.js';

export const BASE_URL = __ENV.BASE_URL || 'http://192.168.55.164:8101';
export const CLOCK_OFFSET_MS = Number(__ENV.CLOCK_OFFSET_MS || 0);
export const REJECT_HEADER = 'X-Seatlab-Reject';

// ---- 결정적 난수(측정-3.5) ----------------------------------------------------------------------------------------------
// FNV-1a 32비트(문자열 → 씨앗) · mulberry32(씨앗 → [0, 1) 열). 씨앗 = (회차 번호 r, 흐름, 세션 번호) — 같은 r의 네 방식이 같은 속성 열을 받는다.
export function fnv1a(str, basis) {
  let h = (basis === undefined ? 0x811c9dc5 : basis) >>> 0;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}
export function mulberry32(seed) {
  let a = seed >>> 0;
  return function next() {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// 세션 속성 — 응답과 무관하게 세션 시작에서 모두 뽑는다(쓰지 않게 된 속성도 기록 — 리뷰 MR-11).
//   abandon(이탈 여부) · revisit(재방문 여부 — 이탈한 세션에만 쓰인다) · think_ms(생각 시간) · delta_ms(재방문 δ ~ U[0, REVISIT_MAX_S]) · seatSeed(좌석 고르기 씨앗)
export function sessionAttrs(rep, flow, sid, p) {
  const r = mulberry32(fnv1a(`adr014|${rep}|${flow}|${sid}`));
  const abandon = r() < p.abandon;
  const revisit = r() < p.rho;
  const think_ms = Math.floor((p.thinkMin + r() * (p.thinkMax - p.thinkMin)) * 1000);
  const delta_ms = Math.floor(r() * p.revisitMax * 1000);
  const seatSeed = Math.floor(r() * 4294967296) >>> 0;
  return { abandon, revisit, think_ms, delta_ms, seatSeed };
}

// 실제로 실행된 세션의 속성 해시(리뷰 MR-11 — 요약기가 씨앗에서 다시 계산한 열이 아니라 세션이 시작 직후 스스로 남긴 기록).
// (흐름, 세션 번호) 순 이어 붙임 대신 세션별 해시 두 개(20비트)를 흐름마다 더한다 — 합은 순서와 무관하게 '실행된 세션 집합 + 속성'의 함수이고(빠진·겹친 세션 · 다른 속성은 합을 바꾼다),
// 세션마다 Counter 행 3개라 시계열 수가 늘지 않는다(세션마다 태그를 달면 시계열이 수십만 개가 된다). 합 < 2^53(세션 ≤ 2^33개)이라 float64 합이 정확하다.
export const crnN = new Counter('crn_n');
export const crnH1 = new Counter('crn_h1');
export const crnH2 = new Counter('crn_h2');
export function recordSession(flow, sid, a) {
  const s = `${flow}|${sid}|${a.abandon ? 1 : 0}|${a.revisit ? 1 : 0}|${a.think_ms}|${a.delta_ms}|${a.seatSeed}`;
  crnN.add(1);
  crnH1.add(fnv1a(s) & 0xfffff);
  crnH2.add(fnv1a(s, 0x9dc5811c) & 0xfffff);
}

// ---- 시각 ----------------------------------------------------------------------------------------------------------------
// 응답 expiresAt(Instant ISO — 'Z' · 소수 0~9자리)을 epoch ms(소수 포함)로. goja Date.parse가 3자리 넘는 소수를 못 읽을 수 있어 직접 푼다. 못 읽으면 NaN
export function isoMs(s) {
  const m = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d+))?Z$/.exec(String(s || ''));
  if (!m) return NaN;
  const base = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
  const frac = m[7] ? Number(`0.${m[7]}`) * 1000 : 0;
  return base + frac;
}
// 서버 시각 targetSrvMs(epoch ms)까지 잔다(PC 시각 = 서버 − 시계 차). 이미 지났으면 바로 돌아온다
export function sleepUntilSrv(targetSrvMs) {
  const d = (targetSrvMs - CLOCK_OFFSET_MS - Date.now()) / 1000;
  if (d > 0) sleep(d);
}

// ---- 호출 -----------------------------------------------------------------------------------------------------------------
// 원인 표지 집계(측정-5 M3 ③): 선점 409마다 헤더 값(rule · nowait · 없으면 missing · 그 밖 값은 other)을 세션 안에서 세고, 잠들기(생각 시간 · 재방문 대기) 전에
//   0이 아닌 갈래만 한 행씩(rej{cause}) 낸다 — gracefulStop이 잠든 세션을 끊어도 그 앞 409의 표지가 사라지지 않게(세션 끝에만 내면 TTL 30초 칸에서 끊긴 세션 몫이 빠진다).
// 요약기가 k6 실행 전체 합을 앱 지표 seat.hold.rejected{cause} 증분과 대조한다(app-metric-missing — 409가 1건 이상인 회차만 · 리뷰 MR-17)
export const rej = new Counter('rej');
export function newTally() { return { rule: 0, nowait: 0, missing: 0, other: 0 }; }
export function flushTally(t) {   // 낸 갈래는 0으로 — 여러 번 불러도 두 번 세지 않는다
  for (const k of ['rule', 'nowait', 'missing', 'other']) if (t[k] > 0) { rej.add(t[k], { cause: k }); t[k] = 0; }
}
export function rejectCause(res) {
  const h = res.headers ? (res.headers[REJECT_HEADER] || res.headers[REJECT_HEADER.toLowerCase()]) : undefined;
  if (h === undefined || h === null || h === '') return 'missing';
  return h === 'rule' || h === 'nowait' ? h : 'other';
}

export function holdReq(scheduleId, seatId, userId, name) {
  return {
    method: 'POST', url: `${BASE_URL}/api/schedules/${scheduleId}/seats/${seatId}/hold`, body: null,
    params: { headers: { 'X-User-Id': String(userId) }, tags: { name: name || 'hold' }, timeout: '30s' },
  };
}
export function confirmReq(holdId, userId, name) {
  return {
    method: 'POST', url: `${BASE_URL}/api/holds/${holdId}/confirm`, body: JSON.stringify({ paymentUid: `pay-${userId}-${holdId}` }),
    params: { headers: { 'X-User-Id': String(userId), 'Content-Type': 'application/json' }, tags: { name: name || 'confirm' }, timeout: '30s' },
  };
}
export function send(req) {
  return http.request(req.method, req.url, req.body, req.params);
}
// 응답 분류(ADR-009 common.js errorCodeOf 그대로 — 201 OK · 409 본문 code · 그 밖 HTTP<상태>) + 확정 200 = OK
export function codeOf(res) {
  if (res.status === 200) return 'OK';
  return errorCodeOf(res);
}
export function holdBody(res) {
  try {
    return { holdId: res.json('holdId'), expMs: isoMs(res.json('expiresAt')), exp: String(res.json('expiresAt')) };
  } catch (e) {
    return { holdId: null, expMs: NaN, exp: '' };
  }
}
