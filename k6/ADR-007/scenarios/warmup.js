// 매 회 측정 전 JIT·커넥션 풀 예열. 끝나면 scripts/run.sh가 DB를 다시 시드한다.
import { hold } from './lib/common.js';
export const options = { scenarios: { w: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '20s', preAllocatedVUs: 50, maxVUs: 200 } }, summaryTrendStats: ['avg'] };
export default function () {
  hold(1, 1 + Math.floor(Math.random() * 10000), 900000 + Math.floor(Math.random() * 100000));
}
export function handleSummary() { return { stdout: 'warmup done\n' }; }
