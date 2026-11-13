// ADR-016 1단계 재현(06c 방식 · 반복 '사이' 유휴): constant-arrival-rate — 한 반복 = POST(선점 흉내) 뒤 짧은 생각(< 서버 유휴 한도) 뒤 POST(확정 흉내).
//   반복이 끝난 VU는 다음 반복까지 쉰다(유휴 ≈ 미리 만든 VU / 도착률 ± 흔들림). 서버 keep-alive를 짧게(2초) 두어 그 경계를 자주 밟게 한다.
//   CLOSE_LAST=1이면 반복의 마지막 요청에 Connection: close(처방).
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
const BASE = __ENV.BASE || 'http://127.0.0.1:18743';
const RATE = parseInt(__ENV.RATE || '150'), VUS = parseInt(__ENV.VUS || '330');
const CLOSE_LAST = __ENV.CLOSE_LAST === '1';
const EARLY = parseFloat(__ENV.EARLY || '0');   // 이 비율의 반복은 첫 요청 뒤 바로 끝난다(선점 실패 · 재선점 201처럼 '마지막인지 보낼 때 모르는' 종료 — close를 못 붙인다)
const eofFirst = new Counter('eof_first'), eofLast = new Counter('eof_last'), earlyExit = new Counter('early_exit');
const firstNew = new Counter('first_newconn'), firstReused = new Counter('first_reused');
const lastNew = new Counter('last_newconn'), lastReused = new Counter('last_reused');   // 반복 안 재사용이 남는지(처방이 반복 안을 바꾸지 않는지)
export const options = { scenarios: { s: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DUR || '4m',
  preAllocatedVUs: VUS, maxVUs: VUS, gracefulStop: '30s' } }, summaryTrendStats: ['min', 'max'], discardResponseBodies: true,
  noVUConnectionReuse: __ENV.NOVU === '1' };   // 비교 처방(나): 반복 사이 연결 재사용 끔
const J = { headers: { 'Content-Type': 'application/json' } };
const JC = { headers: { 'Content-Type': 'application/json', Connection: 'close' } };
export default function () {
  const a = http.post(`${BASE}/hold`, '{}', J);
  if (a.status === 0) { eofFirst.add(1); return; }
  if (a.timings.connecting > 0) firstNew.add(1); else firstReused.add(1);
  if (Math.random() < EARLY) { earlyExit.add(1); return; }
  sleep(Math.random() * 0.4);
  const b = http.post(`${BASE}/confirm`, '{}', CLOSE_LAST ? JC : J);
  if (b.status === 0) eofLast.add(1); else if (b.timings.connecting > 0) lastNew.add(1); else lastReused.add(1);
}
