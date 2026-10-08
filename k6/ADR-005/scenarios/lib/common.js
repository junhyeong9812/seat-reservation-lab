// 공통(ADR-001과 동일): 선점·확정 호출과 응답 분류. 409(비즈니스 거절)는 에러가 아니다 — 5xx·타임아웃·연결 실패만 에러.
// ADR-005: 선택적으로 응답 본문의 에러 코드를 요청 기록에 남긴다(hold의 opts.code — 아래).
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://192.168.55.164:8101';
export const OUT_DIR = __ENV.OUT_DIR || '.';

export const holdCreated = new Counter('hold_201');
export const holdConflict = new Counter('hold_409');
export const holdOther = new Counter('hold_other');
export const holdError = new Rate('hold_error');          // 5xx·타임아웃·연결 실패 비율
export const holdDuration = new Trend('hold_duration', true);
export const confirmOk = new Counter('confirm_200');
export const confirmConflict = new Counter('confirm_409');
export const confirmOther = new Counter('confirm_other');
export const confirmError = new Rate('confirm_error');
export const confirmDuration = new Trend('confirm_duration', true);
// ADR-005: 요청 1건당 1행 — 태그 code = OK(201) · 409 본문의 code(SEAT_NOT_AVAILABLE·HOLD_LIMIT_EXCEEDED …) · 그 밖은 HTTP<상태>.
// http_req_* 행에는 응답을 본 뒤 태그를 붙일 수 없어서 따로 센다. 호출부 태그(user·seat·role 등)를 그대로 함께 남긴다.
export const holdCode = new Counter('hold_code');

export function errorCodeOf(res) {
  if (res.status === 201) return 'OK';
  if (res.status === 409) {
    try {
      const c = res.json('code');
      if (c) return String(c);
    } catch (e) { /* 본문이 JSON이 아니면 아래 */ }
    return '409-NOCODE';
  }
  return `HTTP${res.status}`;
}

const isError = (res) => res.status === 0 || res.status >= 500;

// 주의: name 태그는 k6가 URL 열까지 덮어쓴다(시계열 수 억제). 요청 대상 좌석·사용자는 호출부가 tags로 따로 남긴다.
// opts.code = true면 hold_code 행(위)을 남긴다 — S2·S7만 켠다(S4는 요청 수가 커 원시 기록을 불리지 않게).
export function hold(scheduleId, seatId, userId, tags = {}, opts = {}) {
  const res = http.post(`${BASE_URL}/api/schedules/${scheduleId}/seats/${seatId}/hold`, null, {
    headers: { 'X-User-Id': String(userId) },
    tags: { name: 'hold', ...tags },
    timeout: '30s',
  });
  holdDuration.add(res.timings.duration, tags);
  holdError.add(isError(res), tags);
  if (res.status === 201) holdCreated.add(1, tags);
  else if (res.status === 409) holdConflict.add(1, tags);
  else holdOther.add(1, { status: String(res.status), ...tags });
  if (opts.code) holdCode.add(1, { ...tags, code: errorCodeOf(res) });
  return res;
}

export function confirm(holdId, userId, paymentUid, tags = {}) {
  const res = http.post(`${BASE_URL}/api/holds/${holdId}/confirm`, JSON.stringify({ paymentUid }), {
    headers: { 'X-User-Id': String(userId), 'Content-Type': 'application/json' },
    tags: { name: 'confirm', ...tags },
    timeout: '30s',
  });
  confirmDuration.add(res.timings.duration, tags);
  confirmError.add(isError(res), tags);
  if (res.status === 200) confirmOk.add(1, tags);
  else if (res.status === 409) confirmConflict.add(1, tags);
  else confirmOther.add(1, { status: String(res.status), ...tags });
  return res;
}

export const TREND_STATS = ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'];

// 요약은 JSON 원본 그대로 저장한다 (표는 summarize.py가 이 파일에서 산출).
export function writeSummary(data) {
  return {
    [`${OUT_DIR}/k6-summary.json`]: JSON.stringify(data, null, 2),
    stdout: `k6 done: iterations=${data.metrics.iterations ? data.metrics.iterations.values.count : 0}\n`,
  };
}
