// ADR-014 예열(측정-3.6 · 측정-9 '차가운 JVM'): 선점 + 좌석 맵 + 확정을 회차 1·2에 20초 — 지금 예열(ADR-009 warmup.js)은 선점뿐이라 지도 GET과 B·C·D의 JDBC 경로
// (선조회 · 회수 · 확정 가드)를 측정 전에 데우지 못한다. 끝나면 run.sh가 DB를 다시 시드한다(예열의 행·카운터는 남지 않는다). 사용자 9,000,000번 대역(주 흐름·탐침과 겹치지 않음).
import http from 'k6/http';
import exec from 'k6/execution';
import { holdBody, holdReq, confirmReq, send, BASE_URL } from './lib14.js';

export const options = {
  scenarios: {
    hold: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '20s', preAllocatedVUs: 50, maxVUs: 200, exec: 'holdConfirm' },
    map: { executor: 'constant-arrival-rate', rate: 4, timeUnit: '1s', duration: '20s', preAllocatedVUs: 4, maxVUs: 20, exec: 'seatMap' },
  },
  summaryTrendStats: ['avg'],
};

export function holdConfirm() {
  const i = exec.scenario.iterationInTest;
  const schedule = 1 + (i % 2);
  const seat = (schedule - 1) * 10000 + 1 + ((i * 7919) % 10000);
  const user = 9000000 + i;
  const res = send(holdReq(schedule, seat, user, 'warm-hold'));
  if (res.status === 201 && i % 2 === 0) send(confirmReq(holdBody(res).holdId, user, 'warm-confirm'));   // 절반은 확정(확정 경로 · 남은 절반은 홀드로 남는다)
}

export function seatMap() {
  const i = exec.scenario.iterationInTest;
  http.get(`${BASE_URL}/api/schedules/${1 + (i % 2)}/seats`, { tags: { name: 'warm-seatmap' }, timeout: '30s' });
}

export function handleSummary() { return { stdout: 'warmup14 done\n' }; }
