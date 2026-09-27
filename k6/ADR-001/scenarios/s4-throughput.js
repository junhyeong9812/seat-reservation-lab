// S4 (자원 한계): 도착률을 계단식으로 올리며 선점 성공 경로의 처리량 한계를 잰다.
// 요청마다 새 사용자·새 좌석(100만 석 시드) → 좌석 경합·매수 초과 없이 순수 처리 용량만 본다.
// 단계별 지표는 stage 태그 서브메트릭으로 요약에 남긴다 (한계 판정은 summarize.py).
import exec from 'k6/execution';
import { hold, TREND_STATS, writeSummary } from './lib/common.js';

const START = Number(__ENV.START_RATE || 50);
const FACTOR = Number(__ENV.FACTOR || 1.5);
const STEPS = Number(__ENV.STEPS || 13);                 // 50 → 약 6,500/s
const STEP_S = Number(__ENV.STEP_S || 30);
const SEATS_PER_SCHEDULE = 10000;

const rates = Array.from({ length: STEPS }, (_, i) => Math.round(START * FACTOR ** i));
const stages = rates.map((r) => ({ target: r, duration: `${STEP_S}s` }));

const thresholds = {};
for (let i = 0; i < STEPS; i++) {
  // 빈 임계값 = 단계별 서브메트릭을 요약에 포함시키기 위한 것 (판정용 아님)
  thresholds[`hold_duration{stage:${i}}`] = [];
  thresholds[`hold_error{stage:${i}}`] = [];
  thresholds[`hold_201{stage:${i}}`] = [];
}

export const options = {
  scenarios: {
    s4: {
      executor: 'ramping-arrival-rate',
      startRate: START, timeUnit: '1s',
      stages: stages.flatMap((s) => [{ target: s.target, duration: '1s' }, { target: s.target, duration: `${STEP_S - 1}s` }]),
      preAllocatedVUs: Number(__ENV.PRE_VUS || 500), maxVUs: Number(__ENV.MAX_VUS || 10000),
    },
  },
  thresholds,
  summaryTrendStats: TREND_STATS,
};

export function setup() {
  return { startedAt: Date.now(), rates };
}

export default function (data) {
  const stage = Math.min(STEPS - 1, Math.floor((Date.now() - data.startedAt) / 1000 / STEP_S));
  const seat = exec.scenario.iterationInTest + 1;           // 1..1,000,000
  const schedule = Math.ceil(seat / SEATS_PER_SCHEDULE);
  hold(schedule, seat, 400000 + exec.scenario.iterationInTest, { stage: String(stage) });
}

export function handleSummary(data) {
  data.stage_rates = rates;
  data.step_seconds = STEP_S;
  return writeSummary(data);
}
