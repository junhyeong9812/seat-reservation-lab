// S4 (자원 한계): 도착률을 계단식으로 올리며 선점 성공 경로의 처리량 한계를 잰다.
// 요청마다 새 사용자·새 좌석(200만 석 시드 — ADR-003에서 16단계로 늘려 누적 요청이 100만을 넘는다) → 좌석 경합·매수 초과 없이 순수 처리 용량만 본다.
// 단계별 지표는 stage 태그 서브메트릭으로 요약에 남긴다 (한계 판정은 summarize.py).
import exec from 'k6/execution';
import { hold, TREND_STATS, writeSummary } from './lib/common.js';

const START = Number(__ENV.START_RATE || 50);
const FACTOR = Number(__ENV.FACTOR || 1.5);
const STEPS = Number(__ENV.STEPS || 13);                 // 50 → 약 6,500/s
const STEP_S = Number(__ENV.STEP_S || 30);
// ADR-007 고정 도착률 셀(S4c<rate> — run.sh k6_env_of: FACTOR=1)은 STOP_ON_ERROR=0 — 무너진 뒤에도 6분을 끝까지 걸어 회복·지속을 본다.
// 계단(S4)은 기본값 1 그대로(ADR-006과 같은 중단 규칙)
const STOP_ON_ERROR = (__ENV.STOP_ON_ERROR || '1') !== '0';
// ADR-007 리뷰 A13: 고정 셀 앞 램프 — RAMP_S초 동안 RAMP_FROM/s에서 START/s까지 선형으로 올린 뒤 계획 구간(STEPS × STEP_S)을 건다.
// 0초부터 목표 도착률을 걸면 기동 과도(예열 직후 지연 급등)에 VU가 상한 10,000까지 늘고, 그 뒤 모든 구간에 request timeout·dial i/o timeout이 남았다
// (스모크 smoke-b). 램프 요청은 stage 태그 'ramp' — 요약기 구간표·고정 셀 판정·벽/과도 분석에서 뺀다. 기본 RAMP_S=0 = 램프 없음(계단 S4는 ADR-006과 같다)
const RAMP_S = Number(__ENV.RAMP_S || 0);
const RAMP_FROM = Number(__ENV.RAMP_FROM || START);
const SEATS_PER_SCHEDULE = 10000;

const rates = Array.from({ length: STEPS }, (_, i) => Math.round(START * FACTOR ** i));
const stages = rates.map((r) => ({ target: r, duration: `${STEP_S}s` }));

const thresholds = {};
for (let i = 0; i < STEPS; i++) {
  // 빈 임계값 = 단계별 서브메트릭을 요약에 포함시키기 위한 것 (판정용 아님)
  thresholds[`hold_duration{stage:${i}}`] = [];
  // 한 단계의 에러율이 50%를 넘으면 한계를 지난 것 — 붕괴 뒤의 무의미한 부하를 멈춘다 (k6 종료코드 99)
  // (고정 도착률은 빈 임계값 — 서브메트릭만 남기고 판정·중단하지 않는다)
  thresholds[`hold_error{stage:${i}}`] = STOP_ON_ERROR ? [{ threshold: 'rate<0.5', abortOnFail: true, delayAbortEval: '10s' }] : [];
  thresholds[`hold_201{stage:${i}}`] = [];
}
if (RAMP_S > 0) {   // 램프 구간도 요약에 남긴다(판정·중단 없음 — 구간표 밖)
  thresholds['hold_duration{stage:ramp}'] = [];
  thresholds['hold_error{stage:ramp}'] = [];
  thresholds['hold_201{stage:ramp}'] = [];
}

export const options = {
  scenarios: {
    s4: {
      executor: 'ramping-arrival-rate',
      startRate: RAMP_S > 0 ? RAMP_FROM : START, timeUnit: '1s',
      stages: (RAMP_S > 0 ? [{ target: START, duration: `${RAMP_S}s` }] : [])
        .concat(stages.flatMap((s) => [{ target: s.target, duration: '1s' }, { target: s.target, duration: `${STEP_S - 1}s` }])),
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
  const elapsed = (Date.now() - data.startedAt) / 1000 - RAMP_S;
  const stage = elapsed < 0 ? 'ramp' : Math.min(STEPS - 1, Math.floor(elapsed / STEP_S));
  const seat = exec.scenario.iterationInTest + 1;           // 1..약 197만(16단계 누적) — 시드 200만 석(run.sh seed_of S4)
  const schedule = Math.ceil(seat / SEATS_PER_SCHEDULE);
  hold(schedule, seat, 400000 + exec.scenario.iterationInTest, { stage: String(stage) });
}

export function handleSummary(data) {
  data.stage_rates = rates;
  data.step_seconds = STEP_S;
  data.stop_on_error = STOP_ON_ERROR;
  data.ramp_s = RAMP_S;
  data.ramp_from = RAMP_S > 0 ? RAMP_FROM : null;
  return writeSummary(data);
}
