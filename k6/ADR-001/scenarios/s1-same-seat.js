// S1 (Q1): 같은 좌석 1개에 VU 1,000개가 동시에 선점. 사용자는 모두 다르다.
// 기대(정합): 201이 정확히 1건. 기준선은 check-then-act라 여러 건이 예상된다(ADR-002 가설).
import { hold, TREND_STATS, writeSummary } from './lib/common.js';

const VUS = Number(__ENV.VUS || 1000);
const SEAT_ID = Number(__ENV.SEAT_ID || 1);

export const options = {
  scenarios: { s1: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '2m' } },
  summaryTrendStats: TREND_STATS,
};

export default function () {
  hold(1, SEAT_ID, 100000 + __VU);
}

export const handleSummary = writeSummary;
