// ADR-015 예열(ADR-014 warmup14.js와 같은 자리 · 측정-9 '차가운 JVM'): 선점 + 확정(+ ④면 payment-start)을 회차 1·2에 20초 — 모드별 확정 경로(JPA · JDBC 조건부 문장)를
// 측정 전에 데운다. 좌석 맵은 이 측정에 없어 뺀다. 끝나면 run.sh가 DB를 다시 시드한다(예열의 행·카운터는 남지 않는다). 사용자 9,000,000번 대역(측정 흐름과 겹치지 않음).
import exec from 'k6/execution';
import { confirmReq, holdBody, holdReq, payStartReq, send } from './lib15.js';

const PAY_START = __ENV.PAY_START === '1';

export const options = {
  scenarios: {
    hold: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '20s', preAllocatedVUs: 50, maxVUs: 200, exec: 'holdConfirm' },
  },
  summaryTrendStats: ['avg'],
};

export function holdConfirm() {
  const i = exec.scenario.iterationInTest;
  const schedule = 1 + (i % 2);
  const seat = (schedule - 1) * 10000 + 1 + ((i * 7919) % 10000);
  const user = 9000000 + i;
  const res = send(holdReq(schedule, seat, user, 'warm-hold'));
  if (res.status !== 201 || i % 2 !== 0) return;   // 절반은 확정(남은 절반은 홀드로 남는다 — 배치 경로도 돈다)
  const id = holdBody(res).holdId;
  if (PAY_START) send(payStartReq(id, user, 'warm-pay'));
  send(confirmReq(id, user, 'warm-confirm'));
}

export function handleSummary() { return { stdout: 'warmup15 done\n' }; }
