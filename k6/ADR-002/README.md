# k6/ADR-002 — DB 기준선 보정(인덱스·커넥션 풀·규모) 측정

ADR-001 하네스를 복사하고 **조건 축**(인덱스 유무 · 풀 크기 · 배경 규모)과 **S5 쿼리 벤치마크**를 더했다.
시나리오(S1·S2·S3·S4)·판정기·수집·검증 규칙은 ADR-001과 같다 — `k6/ADR-001/README.md` 참고.
결론과 표: [`docs/adr/ADR-002-db-baseline.md`](../../docs/adr/ADR-002-db-baseline.md)

## 조건 축

| 축 | 값 | 적용 방법 |
|----|----|----------|
| 인덱스 | off / on | `FLYWAY_TARGET` — off = `1`(V1까지, ADR-001 스키마), on = `latest`(V2 일반 인덱스 5개) |
| 커넥션 풀 | 10 / 20 / 40 | `POOL_SIZE` → `spring.datasource.hikari.maximum-pool-size` |
| 배경 규모 N | 0 / 10만 / 100만 | `/internal/reset?backgroundRows=N` — 배경 회차에 HELD N석·홀드 N + RESERVED N석·CONFIRMED N |

회차마다 `app-config.json`에 실제 적용값(풀 크기, flyway target)과 **DB에 실제로 있는 인덱스 목록**(`db_indexes`)을 남긴다.

## 실행

```bash
k6/ADR-002/scripts/campaign.sh --sha <SHA> --id <campaign-id>     # 전체 (S5 → 조건 10개), 중단되면 같은 명령으로 이어서
k6/ADR-002/scripts/run.sh --sha <SHA> --index on --pool 20 --bg 0 --cells 'S1 S4' --levels '2 4' --reps 5 --id <id>   # 조건 하나
k6/ADR-002/scripts/s5-bench.sh --sha <SHA> --out <폴더>           # S5만
k6/ADR-002/scripts/summarize.py k6/ADR-002/results/<campaign-id>/<조건>   # 조건별 표
```

조건 목록과 순서는 `scripts/campaign.sh`의 `CONDITIONS`(실행 시 `conditions.txt`로도 남음).

## S5 — 규모별 쿼리 비용

DB 컨테이너 안에서 `pgbench`로 선점 경로 쿼리를 반복 실행한다(앱·HTTP 없음 → 인덱스 효과만 분리).

| 쿼리 | 도메인에서 부르는 곳 |
|------|------------------|
| Q1 | 1인 2매 확인 — 홀드 수 (`countByScheduleIdAndHoldsUserId`) |
| Q2 | 1인 2매 확인 — 확정 수 (`countByScheduleIdAndUserIdAndStatus`) |
| Q3 | 좌석의 홀드 목록 로드 (`ProductSeat.holds`) |
| Q4 | 만료 배치 조회 (`findDistinctByHoldsExpiresAtLessThanEqual`) |

규모 N(0 / 1만 / 10만 / 100만 / 500만) × 인덱스 off·on × 2·4 CPU마다: 실행 계획(`explain-q*.txt`) · 1연결 지연(`q*-c1-latency.log.gz` — 거래별 µs) · 10연결 처리량(`q*-c10.txt`) · 행 수(`rows.json`) · 인덱스(`indexes.json`).

## 결과 폴더

```
results/<campaign-id>/
├── CAMPAIGN.log · conditions.txt · s5.runner.log · <조건>.runner.log
├── s5/L<n>-idx<off|on>/N<규모>/…
└── <조건>/            ← ADR-001과 같은 구조(plan.json · MATRIX.log · L<n>/<셀>/rep<k>/…)
```
