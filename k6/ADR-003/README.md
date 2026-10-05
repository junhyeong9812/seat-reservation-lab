# k6/ADR-003 — 같은 좌석 경합: 락 방식 비교 측정

ADR-002 하네스를 복사하고 **조건 축**(경합 제어 전략 · 풀 크기 · 앱 대수)과 보강(계단 확장·배포 재시도·경로 단절 판정·락 대기·공정성)을 더했다.
시나리오·판정기·수집·검증 규칙은 ADR-001·002와 같다 — `k6/ADR-001/README.md`, `k6/ADR-002/README.md` 참고.
결론과 표: [`docs/adr/ADR-003-same-seat-contention.md`](../../docs/adr/ADR-003-same-seat-contention.md)

## 조건 축

| 축 | 값 | 적용 방법 |
|----|----|----------|
| 전략 | none · jvm-lock · jvm-lock-in-tx · conditional-update · pessimistic · pessimistic-nowait · optimistic · unique · advisory · redis-nx · redis-lock | `HOLD_STRATEGY` → `seat.hold.strategy`. unique만 `FLYWAY_LOCATIONS`에 `classpath:db/migration-unique`(V4 좌석당 홀드 유니크)를 더한다 |
| 커넥션 풀 | 10 / 20(대기형 3개만) | `POOL_SIZE` |
| 앱 대수 | 1 / 2 | 2 = `compose.two-apps.yml` 오버레이(앱 2대 + nginx 라운드 로빈, 앱마다 CPU = 단계 ÷ 2) |

인덱스는 V2 고정(ADR-002 기준선), 배경 0. Redis는 전략과 무관하게 항상 뜬다(compose 내부망, CPU 1).
회차 판정은 적용된 전략(`strategy-mismatch`)·풀·DB 인덱스 목록을 계획과 대조하고, 측정 중 서버 지표 수집 공백이 30초를 넘으면 `path-gap`으로 표시한다.

## 수집 (ADR-002에 더한 것)

| 파일 | 내용 |
|------|------|
| `timeline-locks.jsonl` | 0.5초마다 DB의 락 미획득 수·Lock 대기 세션·활성 세션·잡힌 advisory 수 (서버 쪽 루프, SSH 하나) |
| `timeline-hikari.jsonl` | 1초마다 Hikari pending·active (앱 2대면 nginx가 번갈아 보냄) |
| S1 `t0` 태그 | 요청을 보낸 시각(ms) — 이긴 요청의 도착 순위(공정성) |
| `app2.log.gz` | 앱 2대 조건의 두 번째 앱 로그 |

S4는 16단계(50 → 약 2.2만 건/s, 1.5배)로 늘렸다 — ADR-002는 13단계(6,487)에서 인덱스 있음 L4의 한계를 관측하지 못했다. 시드는 200만 석(16단계 누적 요청 약 197만).

## S6 경합 강도 스윕 (ADR-003·004, 2026-10-04 추가)

`scenarios/s6-contention.js` — 핫 스트림(동시에 다투는 좌석 K개 × 좌석마다 경쟁자 M=20, 도착률 200→3,200건/s 계단) + 이웃 스트림(경합 없는 좌석 500건/s). 임계 구역 지연은 조건 축 `--delay-ms`(0 / 20) → `seat.hold.critical-section-delay`. 셀 `S6`는 `S6-k1`·`S6-k10`·`S6-k100`으로 펼쳐진다. 시드 20만 석(핫 1~100,000 · 이웃 100,001~). 캠페인: `campaign.sh --sha <SHA> --suite s6 --reps 3 --id <id>`.

## 실행

```bash
k6/ADR-003/scripts/campaign.sh --sha <SHA> --id <campaign-id>     # 전체 — 회차 우선(1회차 전 조건 → 2회차 …), 끝나면 path-gap 회차만 재측정
k6/ADR-003/scripts/run.sh --sha <SHA> --strategy pessimistic --pool 10 --apps 1 --cells 'S1 S4' --levels '2 4' --reps 5 --id <id>   # 조건 하나
k6/ADR-003/scripts/summarize.py k6/ADR-003/results/<campaign-id>/<조건>   # 조건별 표
k6/ADR-003/scripts/compare.py k6/ADR-003/results/<campaign-id>           # 조건 교차표
```

조건 목록은 `scripts/campaign.sh`의 `CONDITIONS`(실행 시 `conditions.txt`로도 남음). 요청별 원시 기록(`k6-requests.csv.gz`)은 커밋하지 않는다 — `errsplit.py`가 남기는 sha256 목록과 요약만(ADR-002 결정 승계).
