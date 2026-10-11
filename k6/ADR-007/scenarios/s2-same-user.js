// S2 (1인 2매): 사용자 USERS명이 각자 서로 다른 좌석 PER_USER개를 동시에 선점.
// 기대(정합): 사용자당 201 최대 2건. 기준선(none)은 COUNT 후 INSERT라 초과가 예상된다.
// ADR-005: 요청마다 응답 본문의 에러 코드를 hold_code 행(user·seat·code 태그)으로 남긴다 — 사용자별 성공 수·가짜 거절(summarize.py)의 근거.
import { hold, TREND_STATS, writeSummary } from './lib/common.js';

const USERS = Number(__ENV.USERS || 100);
const PER_USER = Number(__ENV.PER_USER || 10);

export const options = {
  scenarios: { s2: { executor: 'per-vu-iterations', vus: USERS * PER_USER, iterations: 1, maxDuration: '2m' } },
  summaryTrendStats: TREND_STATS,
};

export default function () {
  const userId = 200000 + Math.floor((__VU - 1) / PER_USER);
  // 좌석은 VU마다 다르다 — 좌석 경합이 아니라 사용자 매수 경합만 본다. 원시 CSV에 좌석·사용자를 남긴다
  hold(1, __VU, userId, { seat: String(__VU), user: String(userId) }, { code: true });
}

export const handleSummary = writeSummary;
