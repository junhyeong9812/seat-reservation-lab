// S3 (Q3·Q7): 입장 → 좌석 선택(핫스팟) → 선점 → (대기) → 확정 또는 이탈. 좌석 10,000석, 입장 TOTAL명.
// 변형은 환경변수로: 원본(TTL 5m) / 축소 A(시간 1/10, 입장 속도 유지) / 축소 B(시간 1/10, 입장 ×10).
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { confirm, hold, TREND_STATS, writeSummary } from './lib/common.js';

const RATE = Number(__ENV.RATE || 300);                 // 초당 입장
const TOTAL = Number(__ENV.TOTAL || 200000);            // 전체 입장 (README 20:1)
const SEATS = Number(__ENV.SEATS || 10000);
const HOT_RATIO = 0.2;                                  // 앞 20% 구역에
const HOT_SHARE = 0.7;                                  // 요청 70%
const RETRIES = Number(__ENV.RETRIES || 3);             // 409면 다른 좌석으로 재시도
const ABANDON = Number(__ENV.ABANDON || 0);             // 이탈률 0 / 0.2 / 0.5
const THINK_MIN = Number(__ENV.THINK_MIN_S || 30);      // 확정 전 대기(초) — TTL 안
const THINK_MAX = Number(__ENV.THINK_MAX_S || 240);
const TAIL_S = Number(__ENV.TAIL_S || 360);             // 입장 종료 후 남은 확정·만료를 기다리는 시간

const arrivalWindowS = Math.ceil(TOTAL / RATE);

export const options = {
  scenarios: {
    s3: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: `${arrivalWindowS}s`,
      preAllocatedVUs: Number(__ENV.PRE_VUS || 2000), maxVUs: Number(__ENV.MAX_VUS || 14000),
      gracefulStop: `${TAIL_S}s`,
    },
  },
  summaryTrendStats: TREND_STATS,
};

const sessionConfirmed = new Counter('session_confirmed');
const sessionAbandoned = new Counter('session_abandoned');
const sessionGaveUp = new Counter('session_gave_up');     // 409만 받다가 재시도를 다 씀
const sessionError = new Counter('session_error');       // 5xx·타임아웃 등으로 중단 (재시도하지 않는다)
// 선점 성공마다 좌석과 서버가 준 만료 시각을 남긴다 — 요약기가 같은 좌석의 다음 성공을
// "앞 홀드 만료 후 = 재선점" / "만료 전 = 중복 선점"으로 가른다 (CSV extra_tags: seat=..&exp=..)
const holdOkSeat = new Counter('hold_ok_seat');

function pickSeat() {
  const hot = Math.floor(SEATS * HOT_RATIO);
  return Math.random() < HOT_SHARE
    ? 1 + Math.floor(Math.random() * hot)
    : hot + 1 + Math.floor(Math.random() * (SEATS - hot));
}

export default function () {
  const userId = 300000 + exec.scenario.iterationInTest;
  const tried = new Set();
  for (let attempt = 0; attempt <= RETRIES; attempt++) {
    let seat = pickSeat();
    while (tried.has(seat)) seat = pickSeat();
    tried.add(seat);
    const res = hold(1, seat, userId);
    if (res.status === 409) continue;                                    // 409만 다른 좌석으로 재시도
    if (res.status !== 201) { sessionError.add(1); return; }
    holdOkSeat.add(1, { seat: String(seat), exp: res.json('expiresAt') });
    if (Math.random() < ABANDON) { sessionAbandoned.add(1); return; }   // 결제 없이 떠남 → 만료 대상
    sleep(THINK_MIN + Math.random() * (THINK_MAX - THINK_MIN));
    const c = confirm(res.json('holdId'), userId, `pay-${userId}`);
    if (c.status === 200) sessionConfirmed.add(1);
    return;
  }
  sessionGaveUp.add(1);
}

export const handleSummary = writeSummary;
