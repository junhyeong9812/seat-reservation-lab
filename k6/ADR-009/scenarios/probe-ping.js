// ADR-011 저율 측정기(run.sh --probes): 주 k6와 별도 프로세스로 고정 도착률 PROBE_RATE(기본 50)/s `GET /actuator`를 보낸다.
// 대상 = actuator 링크 목록(노출된 엔드포인트 _links JSON, 200) — DB를 거치지 않는다(앱 웹 서버·JVM만). loadtest 프로필이 health·configprops·metrics를 연다.
//   /actuator/health/ping은 404다(스모크 smoke-adr011-probes 실측 · 로컬 기동 확인 2026-10-16 — 이 앱은 health 하위 경로를 내주지 않는다. /actuator는 200).
//   /actuator/health는 DB health indicator(DataSource 검증 쿼리)를 부르므로 쓰지 않는다. 같은 시나리오를 두 곳에서 띄운다:
//   PC 측정기 = PC의 다른 k6 프로세스(--probe-k6-bin — 주 k6와 다른 바이너리) → BASE_URL = 서버 앱 8101
//   서버 측정기 = seatlab compose 안 컨테이너(compose.probe.yml) → BASE_URL = http://app:8101
// 길이 PROBE_DURATION_S는 주 k6 창보다 길게 잡고(run.sh probe_duration_of), 주 k6가 끝나면 신호로 멈춘다(SIGINT·SIGTERM — k6가 정상 종료하며 CSV를 닫는다).
// 요청 기록 시각은 ms(run.sh가 K6_CSV_TIME_FORMAT=unix_milli를 준다 — 주 k6 CSV는 이전처럼 초 단위). 요약은 쓰지 않는다(분석은 CSV — scripts/tail.py).
import http from 'k6/http';

const BASE = __ENV.BASE_URL || 'http://192.168.55.164:8101';
const RATE = Number(__ENV.PROBE_RATE || 50);
const DURATION_S = Number(__ENV.PROBE_DURATION_S || 600);

export const options = {
  discardResponseBodies: true,
  scenarios: {
    ping: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DURATION_S}s`,
      // VU를 미리 잡아 둔다 — 튐 때 VU를 새로 만드는 비용이 측정기 지연에 섞이지 않게(평소 지연 약 1ms × 50/s면 VU 1~2개)
      preAllocatedVUs: 20, maxVUs: 200,
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(99)', 'max'],
};

export default function () {
  http.get(`${BASE}/actuator`, { tags: { name: 'ping' }, timeout: '5s' });   // 요청 이름 태그는 그대로 ping(분석 tail.py)
}

export function handleSummary() {
  return { stdout: 'probe done\n' };
}
