// ADR-013 가용성 탐침(§4.7.3 DS-6 · §5.1 고정값): 주 k6와 다른 프로세스 — 일정 도착률 선점을 lb로 보내 요청마다 보낸 시각·코드·지연을 남긴다.
//   도착률 2건/s · 요청 시한 5초 · preAllocatedVUs 20 · maxVUs 20(§5.1 — 모든 요청이 시한까지 막혀도 VU가 모자라지 않는다 → 버린 반복(dropped_iterations)은 D2 실패로 센다)
//   좌석 = SEAT_BASE + 반복 번호 + 1(전용 범위 — 한도 탐침 좌석과 겹치지 않는다: 탐침 회차의 5,001~10,000번째 좌석) · 사용자 = USER_BASE + 반복 번호(요청마다 새 사용자 — 한도에 닿지 않는다)
//   기록: 요청마다 카운터 avail_req 1행(태그 t0 = 보낸 PC 시각 ms · code = 상태 · dur = 지연 ms) — CSV 행 시각(K6_CSV_TIME_FORMAT=unix_milli)은 받은 시각.
//   길이 DURATION_S는 주 k6보다 길게 주고 run.sh가 주 k6 끝에 신호로 멈춘다(ADR-009 probe-ping.js와 같은 방식).
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://192.168.55.164:8101';
const RATE = Number(__ENV.AVAIL_RATE || 2);
const DURATION_S = Number(__ENV.DURATION_S || 1500);
const SCHEDULE = Number(__ENV.SCHEDULE_ID);
const SEAT_BASE = Number(__ENV.SEAT_BASE);
const USER_BASE = Number(__ENV.USER_BASE || 80000000);
const SEAT_MAX = Number(__ENV.SEAT_MAX || 5000);
const TIMEOUT = `${Number(__ENV.TIMEOUT_S || 5)}s`;

const availReq = new Counter('avail_req');

export const options = {
  discardResponseBodies: false,
  scenarios: {
    avail: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DURATION_S}s`, preAllocatedVUs: 20, maxVUs: 20 },
  },
  summaryTrendStats: ['avg', 'p(99)', 'max'],
};

export default function () {
  const i = exec.scenario.iterationInTest;
  if (i >= SEAT_MAX) { availReq.add(1, { t0: String(Date.now()), code: 'seat-exhausted', dur: '0' }); return; }   // 좌석을 다시 쓰지 않는다(무음 아님 — 요약기가 센다)
  const t0 = Date.now();
  const res = http.post(`${BASE}/api/schedules/${SCHEDULE}/seats/${SEAT_BASE + i + 1}/hold`, null, {
    headers: { 'X-User-Id': String(USER_BASE + i) }, tags: { name: 'avail' }, timeout: TIMEOUT,
  });
  availReq.add(1, { t0: String(t0), code: String(res.status), dur: String(Math.round(res.timings.duration)) });
}

export function handleSummary() {
  return { stdout: 'avail-probe done\n' };
}
