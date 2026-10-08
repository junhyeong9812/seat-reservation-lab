// S6 (ADR-003·004): 경합 강도 스윕 — 같은 좌석 경합이 '계속 이어지는' 구간에서 락 방식의 차이를 본다.
// 핫 스트림: 동시에 다투는 좌석 K개, 좌석마다 경쟁자 M명. 요청 i는 좌석 1 + (i % K) + K·⌊i / (K·M)⌋ —
//   K개 좌석이 M명씩 다 받으면 다음 K개로 넘어간다(좌석마다 이긴 1명은 실제 선점 경로를 끝까지 탄다 → 임계 구역 지연이 걸린다).
//   도착률 계단(기본 200 → 3,200건/s, 30초씩). 좌석 공급: 200,000석 시드 중 앞 100,000(1 ~ 100,000).
// 이웃 스트림: 경합 없는 다른 좌석(100,001 ~)에 일정 도착률(기본 500건/s) — 핫 경합이 상관없는 요청으로 번지는지.
// 사용자는 요청마다 다르다(1인 2매 제한에 걸리지 않게). 지표 태그: stream(hot|neighbor) · stage.
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { hold, TREND_STATS, writeSummary } from './lib/common.js';

const K = Number(__ENV.K || 1);
const M = Number(__ENV.M || 20);
const START = Number(__ENV.START_RATE || 200);
const FACTOR = Number(__ENV.FACTOR || 2);
const STEPS = Number(__ENV.STEPS || 5);
const STEP_S = Number(__ENV.STEP_S || 30);
const NEIGHBOR_RATE = Number(__ENV.NEIGHBOR_RATE || 500);
const SEATS_PER_SCHEDULE = 10000;
const HOT_SEATS = 100000;          // 핫 스트림이 쓸 수 있는 좌석 수 — 넘으면 이웃 범위를 침범하므로 계단 합이 K·M과 함께 이 안에 들어오게 고른다

const rates = Array.from({ length: STEPS }, (_, i) => Math.round(START * FACTOR ** i));
const totalS = STEPS * STEP_S;

const thresholds = {};
for (let i = 0; i < STEPS; i++) {
  for (const stream of ['hot', 'neighbor']) {
    // 빈 임계값 = 단계·스트림별 서브메트릭을 요약에 넣기 위한 것(판정용 아님). S6는 중단하지 않는다 — 붕괴 모양 자체가 결과
    thresholds[`hold_duration{stage:${i},stream:${stream}}`] = [];
    thresholds[`hold_error{stage:${i},stream:${stream}}`] = [];
    thresholds[`hold_201{stage:${i},stream:${stream}}`] = [];
    thresholds[`hold_409{stage:${i},stream:${stream}}`] = [];
  }
}

export const options = {
  scenarios: {
    hot: {
      executor: 'ramping-arrival-rate', exec: 'hot', startRate: START, timeUnit: '1s',
      stages: rates.flatMap((r) => [{ target: r, duration: '1s' }, { target: r, duration: `${STEP_S - 1}s` }]),
      preAllocatedVUs: Number(__ENV.PRE_VUS || 500), maxVUs: Number(__ENV.MAX_VUS || 8000),
    },
    neighbor: {
      executor: 'constant-arrival-rate', exec: 'neighbor', rate: NEIGHBOR_RATE, timeUnit: '1s', duration: `${totalS}s`,
      preAllocatedVUs: 200, maxVUs: 2000,
    },
  },
  thresholds,
  summaryTrendStats: TREND_STATS,
};

// 일시 중복 검출(S3와 같은 방식): 핫 좌석에서 이긴 홀드의 좌석·만료 시각을 요청 기록에 남긴다 — 끝 상태 판정기가 놓치는 '잠깐 생긴 중복'
const holdOkSeat = new Counter('hold_ok_seat');

export function setup() {
  return { startedAt: Date.now() };
}

const stageOf = (data) => String(Math.min(STEPS - 1, Math.floor((Date.now() - data.startedAt) / 1000 / STEP_S)));
const scheduleOf = (seat) => Math.ceil(seat / SEATS_PER_SCHEDULE);

export function hot(data) {
  const i = exec.scenario.iterationInTest;
  const seat = 1 + (i % K) + K * Math.floor(i / (K * M));
  if (seat > HOT_SEATS) return;   // 좌석 공급을 넘으면 보내지 않는다(이웃 범위 보호) — 계단 설계로 닿지 않게 한다
  const res = hold(scheduleOf(seat), seat, 2000000 + i, { stream: 'hot', stage: stageOf(data) });
  if (res.status === 201) holdOkSeat.add(1, { seat: String(seat), exp: res.json('expiresAt') });
}

export function neighbor(data) {
  const seat = HOT_SEATS + 1 + exec.scenario.iterationInTest;
  hold(scheduleOf(seat), seat, 5000000 + exec.scenario.iterationInTest, { stream: 'neighbor', stage: stageOf(data) });
}

export function handleSummary(data) {
  data.stage_rates = rates;
  data.step_seconds = STEP_S;
  data.s6 = { K, M, neighbor_rate: NEIGHBOR_RATE };
  return writeSummary(data);
}
