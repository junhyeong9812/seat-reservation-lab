// S1 (Q1): 같은 좌석 1개에 VU 1,000개가 동시에 선점. 사용자는 모두 다르다.
// 기대(정합): 201이 정확히 1건. 기준선은 check-then-act라 여러 건(상한 = 풀 크기, ADR-002 D3).
import { hold, TREND_STATS, writeSummary } from './lib/common.js';

const VUS = Number(__ENV.VUS || 1000);
const SEAT_ID = Number(__ENV.SEAT_ID || 1);

export const options = {
  scenarios: { s1: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '2m' } },
  summaryTrendStats: TREND_STATS,
};

export default function () {
  // ADR-003 ② 공정성: 요청을 보낸 시각(ms)을 태그로 남긴다 — 이긴 요청이 도착 순서로 몇 번째였는지를 요약기가 계산한다.
  // (k6 CSV의 timestamp는 완료 시각·초 단위라 1,000개가 1~3초에 몰리는 S1에서 순위를 못 가른다)
  hold(1, SEAT_ID, 100000 + __VU, { seat: String(SEAT_ID), user: String(100000 + __VU), t0: String(Date.now()) });
}

export const handleSummary = writeSummary;
