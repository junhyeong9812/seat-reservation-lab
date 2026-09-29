# k6/ADR-001 — 정합성 판정 + 부하 하네스

ADR-000 기준선(동시성 제어 없음)을 두드려 **"깨졌다"를 수치로 남기는** 측정 도구와 그 원시 결과 전부.
결론과 표는 [`docs/adr/ADR-001-consistency-oracle.md`](../../docs/adr/ADR-001-consistency-oracle.md)에 있고, 표의 모든 수치는 `results/`의 원시 파일에서 `scripts/summarize.py`로 다시 산출할 수 있다.

## 구성

```
k6 PC (부하 발생 · 오케스트레이터)                 서버 .164 (측정 대상)
┌──────────────────────────────┐   git archive   ┌────────────────────────────────┐
│ scripts/run.sh               │ ──── ssh ─────▶ │ ~/labs/seat-reservation-lab/<SHA>│
│  ├ deploy · compose up/down  │                 │  docker compose -p seatlab       │
│  ├ reset → warmup → reset    │ ──── HTTP ────▶ │   app (cpus=L, 1GB, loadtest)    │
│  ├ k6 run scenarios/*.js     │                 │   db  (cpus=L, 2GB, postgres:16) │
│  ├ 수집기(서버·k6 PC·DB 상태) │ ◀─── 결과 ───── │  /internal/reset · /consistency  │
│  └ results/<id>/L*/<셀>/rep*/ │                 │  /actuator/configprops·metrics   │
└──────────────────────────────┘                 └────────────────────────────────┘
```

- **측정 대상 = 로컬 커밋 하나**: `git archive <SHA>`를 서버에 풀어 빌드한다(GitHub 경유 없음). 결과의 `meta.json`에 SHA가 남는다.
- **판정은 앱이 아니라 DB 사실**: `/internal/consistency`는 도메인 로직을 재사용하지 않고 행을 직접 센다. `v_`로 시작하는 항목이 위반이며 기대값은 모두 0이다.
- loadtest 프로필에서만 `/internal/*`·actuator가 열린다 (기본 실행에는 없다).

## 시나리오

| 셀 | 질문 | 부하 | 정합이라면 |
|----|------|------|-----------|
| S1 | Q1 같은 좌석 | 좌석 1개에 1,000 VU 동시 선점 | 201이 정확히 1건 |
| S2 | 1인 2매 | 사용자 100명 × 서로 다른 좌석 10개 동시 | 사용자당 201 ≤ 2 |
| S3 | Q3·Q7 전체 흐름 | 입장 → 핫스팟 좌석 선택(앞 20%에 70%) → 선점(409면 3회 재시도) → 대기 → 확정/이탈 | 중복 홀드·중복 확정·오래된 HELD·매수 초과 0, RESERVED = CONFIRMED |
| S4 | 자원 한계 | 도착률 50/s에서 ×1.5씩 13단계(30s씩), 요청마다 새 사용자·새 좌석(100만 석) | — (처리량 측정) |

S3 변형 × 이탈률(0/20/50%) — 모양을 좌우하는 비율을 기준으로 나눴다:

| 변형 | TTL | 대기 | 배치 주기 | 입장 속도 | 입장 수 | TTL당 입장 |
|------|-----|------|-----------|-----------|---------|-----------|
| S3 (원본) | 5m | 30s–4m | 10s | 300/s | 200,000 | 90,000 |
| S3A (시간만 1/10) | 30s | 3–24s | 1s | 300/s | 20,000 | 9,000 |
| S3B (시간 1/10 + 입장 ×10) | 30s | 3–24s | 1s | 3,000/s | 200,000 | 90,000 |

S4 한계 = 세 기준을 모두 기록: 엄격(p99 < 500ms · 에러 < 1%) / 완화(p99 < 1s · 에러 < 5%) / 포화점(최대 성공 RPS). 409는 에러가 아니다 — 5xx·타임아웃·연결 실패만 에러.

## 매트릭스

자원 단계 L1/L2/L4 = 앱·DB 각각 CPU 1/2/4 (메모리 고정: 앱 1GB · DB 2GB, 커넥션 풀 10) × 셀 × 5회.
각 회차: 컨테이너 새로 기동 → DB 시드 → 예열(20s) → DB 재시드 → 측정 → (S3는 TTL + 배치 유예만큼 관측 유지) → 판정 → 산출물 검증.

- 측정 대상 = 커밋 하나: 서버의 앱과 k6 시나리오 모두 `--sha`의 커밋에서 꺼낸다(작업트리가 달라도 재현).
- S4는 앱 TTL을 60분으로 두어 만료 배치를 배제하고, 누적 홀드 수를 10초마다 `/internal/counts`로 기록한다.
- S3는 409일 때만 다른 좌석으로 재시도하고(최대 3회), 에러면 그 입장을 중단(`session_error`)한다.
- 같은 결과 ID로 다시 실행하면 거부한다(원시 결과 덮어쓰기 방지).

빌드는 서버의 컨테이너 안에서 하므로 k6 PC에는 k6·jq·python3·ssh 키만 있으면 된다.

```bash
k6/ADR-001/scripts/run.sh --sha <SHA>                              # 전체 (약 하루 이상)
k6/ADR-001/scripts/run.sh --sha <SHA> --levels 1 --reps 1 --cells S1 --id smoke   # 스모크
k6/ADR-001/scripts/summarize.py k6/ADR-001/results/<matrix-id>     # 표 산출
```

## 결과 읽는 법

`results/<matrix-id>/`

| 파일 | 내용 |
|------|------|
| `plan.json` | 실행 전에 남긴 계획(단계·셀·회차) — 요약은 이 계획 기준이라 실행되지 않은 회차가 '미측정'으로 드러난다 |
| `MATRIX.log` | 전 회차 한 줄씩 — 시각 · 단계 · 셀 · 회차 · 상태(`ok` 또는 누적된 실패 사유) |
| `SUMMARY.md` · `summary.json` | `summarize.py` 산출 표 (중앙값 [최소–최대]) |
| `curves/L<n>-<셀>-db.csv` | S3 DB 상태 곡선(누적 확정 포함), 시간축 = (시각 − k6 시작) / TTL |
| `curves/L<n>-<셀>-rehold.csv` | S3 재선점·중복 선점 누적 곡선 — 같은 좌석의 다음 성공이 앞 홀드 만료 후면 재선점, 전이면 중복 |
| `L<n>/<셀>/rep<k>/meta.json` | 측정 SHA · 앱/k6 설정 · 시작·종료 · k6 종료코드 · 상태 |
| `…/k6-summary.json` | k6 요약 원본 (지연 분위수·카운터·S4 단계별 서브메트릭) |
| `…/k6-requests.csv.gz` | 요청 단위 원시 기록 (전 시나리오). S3는 선점 성공마다 `hold_ok_seat`(좌석·만료시각) 포함 |
| `…/k6-dashboard.html` | S3·S4 시계열 집계 (k6 웹 대시보드 내보내기 — 원시 CSV의 보조) |
| `…/consistency.json` | 측정 직후 DB 판정 |
| `…/timeline-db.jsonl` | S3: 5초마다 DB 판정 / S4: 10초마다 누적 홀드 수 — 각 줄에 폴링 소요시간(`poll_ms`) |
| `…/timeline-server.jsonl` · `timeline-client.jsonl` | 서버 컨테이너 전부(운영 서비스 포함)·k6 PC 자원 시계열 — 잡음 판단용 |
| `…/app-config.json` | 실제 적용된 설정(TTL·배치 주기·풀 크기), JVM이 본 CPU 수, 컨테이너 CPU·메모리 제한 |
| `…/server-*.txt` · `client-*.txt` | 측정 전후 호스트 상태 |
| `…/app.log.gz` · `db.log.gz` | 그 회차 구간의 컨테이너 로그 |
| `…/k6-stdout.log.gz` · `recovery.log` · `compose.log` | k6 경고 · 초기화 재시도 · 기동 기록 |
