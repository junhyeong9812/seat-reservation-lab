# 요구사항 명세서 (requirement-spec) — ADR-007 counter-upsert 처리량 벽 진단 + 쓰기 없는 선조회 변형

> 작성일: 2026-10-09 · 작업 폴더: `docs/plans/2026-10-09/adr-007-throughput-wall/` · 브랜치: `feat/adr-007-throughput-wall`(main `f838850b` — ADR-006 PR #6 병합 — 에서 분기)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문(2026-10-09): "처리량이 무너지는 이유를 찾고 이게 해결되면 바꾸는 방향으로 가보자" · "이걸 7번으로 해야겠네" → ADR-006 결정(advisory-try 유지 + 진단 후 전환), 새 ADR-007, 기존 007~012 → 008~013(완료, 820a4e5f).
- 인터뷰 1차:
  - 진단 축 → **네 가지 전부**: 대기 이벤트 샘플링 · 체크포인트 설정 축 · shared_buffers 128MB vs 512MB · 고정 도착률(누적 vs 시간·지속성)
  - 대상 방식 → 사용자: "try는 이걸 한다고 억울한 좌석이 해결되는 건 아니지 않아?" → advisory-try 제외(처리량은 none과 같음 — ADR-005·006)
  - 쓰기 비용 변형 → **이번에 함께**
  - 반복 → **3회**
- 인터뷰 2차:
  - 변형 설계 → **좌석 상태 선조회 후 upsert**(좌석 잠금 뒤 upsert 안은 억울한 좌석이 되살아나 제외)
  - 대상 → **counter-upsert + 변형 + none**
  - 설정 조합 → **독립 3조건**(기본 · 체크포인트 없음 · shared_buffers 512MB)
  - 부하 → **계단 S4 + 고정 2종**(4,200/s · 2,000/s, 각 6분), L4만
- 인터뷰 3차:
  - 변형 확인 시나리오 → **S2·S7-m1·S3-a20 × 3**
  - 변형 에러 순서 '팔렸으면 좌석 먼저, 아니면 매수 먼저' → **허용 안 함** → 재질문 → **명세 순서(좌석 먼저)에 맞춤**
  - 시간 → **약 15시간 그대로**

---

## 1. 목표·대상 (필수)

`seat-reservation-lab`에서 다음을 같은 캠페인으로 실측하면 끝이다.

- **(가) 진단**: counter-upsert가 S4 4,325건/s에서 26~27초 뒤 무너지는 원인을 대기 이벤트와 설정 축으로 가린다. 후보는 C1 체크포인트·WAL · C2 버퍼 교체 · C3 확장 락 · C4 인덱스 끝 페이지 경합이다.
- **(나) 변형**: 새 매수 방식 `counter-upsert-precheck`가 쓰기 비용을 줄이면서 counter-upsert의 장점을 유지하는지 확인한다. 장점은 억울한 좌석 0·매수 정합 0이다.

결과는 `docs/adr/ADR-007-counter-upsert-throughput-wall.md`에 기록한다. ADR-006 §6 전환 규칙에 따라 매수 제어 기본을 counter-upsert(또는 변형)로 바꿀지 판정한다.

| 방식(`seat.hold.limit-strategy`) | 설명 |
|------|------|
| `none` | 대조군 |
| `counter-upsert` | ADR-006 그대로 |
| `counter-upsert-precheck` (신규) | 아래 순서 |

`counter-upsert-precheck`의 처리 순서:

1. 사용자 단위 진입(acquire)에서 좌석 상태를 **잠금 없는 일반 SELECT**로 읽는다. 없으면 `SEAT_NOT_FOUND`, AVAILABLE이 아니면 쓰기 없이 `SEAT_NOT_AVAILABLE`이다.
2. AVAILABLE이면 counter-upsert와 같은 한 문장 upsert를 한다.
3. upsert가 0행이면 좌석을 **다시 읽는다**. 팔렸으면 `SEAT_NOT_AVAILABLE`, 아니면 `HOLD_LIMIT_EXCEEDED`다.
4. 이어서 기존 3b 좌석 잠금 흐름이다. 잠금에 실패하면 `SEAT_NOT_AVAILABLE`이고, 같은 트랜잭션 롤백으로 +1이 원복된다.

## 2. 경계·불변식 (필수)

- **좌석 3b·응답 계약·에러 코드는 그대로**다. 기존 매수 방식 11개의 동작은 바꾸지 않는다. 변형은 새 설정값이다. 앱 기본값(좌석 3b·매수 none)도 그대로다.
- **변형의 에러 순서 = 명세 순서(좌석 불가 → 매수 초과)**. 커밋된 좌석 상태 기준이다.
  - 남는 틈은 다른 트랜잭션이 커밋 전에 쥐고 있는 좌석이다. 잠금 없는 읽기로는 보이지 않아 `HOLD_LIMIT_EXCEEDED`가 나올 수 있다.
  - 이 틈은 테스트와 문서에 명시한다. 측정에서는 S2·S7-m1 응답 코드로 관측한다.
- **변형도 매수 초과 사용자가 좌석 락을 잡지 않는다** → 억울한 좌석 0이 설계 목표다. 락 순서는 사용자(쿼터 행) → 좌석이다.
- 카운터 정의·만료 감소·판정기 `v_counter_mismatch`·배경 행 거부는 counter·counter-upsert와 같다. 변형에도 적용한다.
- **설정 축은 DB 기동 인자만 바꾼다**: `checkpoint_timeout`·`max_wal_size`·`shared_buffers`. 나머지 측정 조건은 ADR-006과 같다(서버·유선·시드·예열·Tomcat·풀 10·인덱스 V2·좌석 3b·`track_io_timing`·`track_wal_io_timing` on).
- **대기 이벤트 샘플링은 측정을 흔들지 않아야 한다**. 기존 DB 폴러와 같은 주기·연결을 쓴다. 샘플링 쿼리 비용을 스모크에서 확인한다(none S4 엄격 한계·포화점이 ADR-006과 같은 계단).
- 비교는 같은 캠페인 안에서만 한다. ADR-006 수치는 참고 인용이다.
- 판정·근거 보존 규칙은 ADR-001~006 그대로다: DB 사실 기준, 표는 스크립트 산출, 미측정 표기, 요청별 CSV는 sha256만, 큰 원시 파일은 gzip·커밋 제외.

## 3. 기준소스 (필수)

- `docs/adr/ADR-006-counter-upsert.md`(§5.3 ② 벽 분석·원인 후보, §6 전환 규칙, §7 진단 4축), `docs/adr/ADR-007-counter-upsert-throughput-wall.md`(가설 초안)
- 코드 기준: main `f838850b` — `service/impl/limit/UserLimitStrategies.kt`(CounterUpsertUserLimit) · `service/impl/HoldSeatProcess.kt` · `UserLimitStrategyType.kt` · `loadtest/LoadtestDataService.kt` · `domain/ProductSeat.kt`(assertHoldable)
- 하네스 기준: `k6/ADR-006/`(복사해 `k6/ADR-007/`로)
- 환경: ADR-006과 같다(.164 서버, k6 PC 유선)

## 4. 금지영역 (필수)

- 기존 매수 방식 11개·좌석 전략 11개의 동작 변경, 응답 계약 변경
- ADR-001~006의 원시 결과·하네스 수정(읽기만 — 새 하네스는 `k6/ADR-007/`)
- .164의 기존 컨테이너·디렉터리와 `~/labs/seat-reservation-lab/` 밖 경로. 호스트 포트는 8101·15432만 쓴다. 서버 디스크 사용률을 감시한다(배포 크기 유지).
- 자격증명 탐색 · `main` 직접 커밋 · 사용자 확인 없는 push · 다른 서버(.9·.158) 사용

## 5. 검증 방법 (필수)

- **변형 테스트(Testcontainers)**: counter-upsert 계약 묶음을 그대로 적용한다.
  - 같은 사용자 동시 10좌석 초과 0 · 다른 사용자 비차단 · 같은 좌석 50명 1명 · 카운터 정합(선점·좌석 실패 롤백·확정·만료) · 새 사용자 동시 INSERT 경합 · 격리 수준
  - 추가 ①: **팔린 좌석은 쓰기 없이 SEAT_NOT_AVAILABLE** — 쿼터 행이 생기지 않고 upsert가 실행되지 않는다(새 사용자 행 없음 · 기존 행 xmax = 0 — 롤백된 쓰기 흔적 없음).
  - 추가 ②: **매수가 찬 사용자 + 팔린 좌석 → SEAT_NOT_AVAILABLE**(명세 순서).
  - 추가 ③: 매수가 찬 사용자 + 빈 좌석 → HOLD_LIMIT_EXCEEDED이고 좌석 락을 잡지 않는다.
  - 추가 ④: 남는 틈(커밋 전 잠긴 좌석 → LIMIT) 특성 테스트.
- **하네스 스모크**:
  - 대기 이벤트 표본이 회차마다 기록된다.
  - 설정 축 3조건이 실제 DB에 적용됐다(`SHOW`로 회차 기록).
  - 고정 도착률 시나리오가 목표 도착률을 낸다.
  - 샘플링 부하가 none S4 결과를 바꾸지 않는다.
  - 변형 빌림 1.000이다.
- **본측정**: 계획 대비 누락 0, 비정상 회차 재측정. 재측정 판정식은 스모크에서 의도적 실패·정상 회차로 양방향 확인한다(ADR-006 교훈).
- ADR-007 표는 원시 결과에서 스크립트로 재산출한다.

## 6. stakes (필수)

- 판정: **중간**. 근거는 동시성(새 판정 순서·선조회 경합), 실험 DB 한정, 응답 계약 불변, 에러 순서 변경 없음(명세 순서)이다.
- 리뷰: 코드·하네스 듀얼 1패스, 결과 문서 듀얼 1패스.

---

## 7. 자율성

- [x] auto (기본 권장 — 합의 후 Claude 자율 실행) — 사용자 "auto로 부탁해"(lazy 선택 직후 변경)
- [ ] lazy (매 diff 이해 게이트 — 학습·OSS)

## 8. load-bearing 가정 (1~2개)

1. **체크포인트 없음 조건에서 측정 중 체크포인트가 실제로 돌지 않는다**: `checkpoint_timeout` 1h·`max_wal_size` 16GB이고, 회차당 WAL은 약 0.75GB다. 스모크에서 `pg_stat_bgwriter`·DB 로그로 실증한다. 돌면 C1을 가를 수 없다.
2. **대기 이벤트 샘플링이 벽 시각을 1초 분해능으로 보여 준다**: `pg_stat_activity` 폴링, active 세션의 wait_event 분포. 스모크에서 ADR-006 조건(기본 설정 counter-upsert S4)의 벽 근처 표본이 비어 있지 않은지 실증한다.

## 9. 설계

### 9.1 앱

- `UserLimitStrategyType.COUNTER_UPSERT_PRECHECK` + `CounterUpsertPrecheckUserLimit`.
  - acquire: 좌석 선조회(`SELECT status FROM product_seat WHERE id = ? AND schedule_id = ?`) → upsert → 0행이면 좌석 재조회.
  - check 없음. `onHoldsExpired`는 공용 감소 함수다.
- 판정기 `COUNTER_STRATEGIES`에 추가한다(카운터 불일치 판정·배경 행 거부·TRUNCATE).

### 9.2 측정 매트릭스 (L4, 좌석 3b, 풀 10, 배경 0)

| 묶음 | 방식 | 설정 | 부하 | 회차 |
|------|------|------|------|------|
| 진단 | none · counter-upsert · counter-upsert-precheck | 기본(`checkpoint_timeout` 5min·`max_wal_size` 1GB·`shared_buffers` 128MB) · 체크포인트 없음(1h·16GB) · 버퍼 512MB | S4 계단(ADR-006과 같음) · 고정 4,200/s 6분 · 고정 2,000/s 6분 | 3 |
| 변형 확인 | 같음 | 기본 | S2 · S7-m1 · S3-a20 | 3 |

- 진단 81회 + 변형 확인 27회. 추정 약 15시간이다.
  - 고정 4,200/s는 누적 37만에 약 88초에 닿는다(k6 시작 기준 — 시간 기준 체크포인트 +249초 전).
  - 2,000/s는 약 185초에 닿는다. 두 시점이 체크포인트 시각과 겹치지 않아 누적과 시간을 가른다.

### 9.3 하네스 (`k6/ADR-007/` — ADR-006 복사 + 추가)

- **DB 설정 축**: compose `db.command`에 `checkpoint_timeout`·`max_wal_size`·`shared_buffers`를 환경변수로 넣고(기본값 = PostgreSQL 기본), 회차마다 `SHOW` 결과를 `meta.json`에 남긴다.
- **대기 이벤트**: DB 폴러에서 active 세션의 `(wait_event_type, wait_event)` 개수와 `pg_stat_bgwriter`·`pg_stat_checkpointer` 상태를 초 단위로 `timeline-waits.jsonl`에 기록한다. 체크포인트 시작·완료는 DB 로그(`log_checkpoints`)에서 정렬한다.
- **고정 도착률**: S4 시나리오를 환경변수로 고정 도착률로 돌린다(FACTOR=1). 셀 이름 `S4c4200`·`S4c2000`. 요약기는 30초 구간별 처리량·p99를 낸다.
- **벽 분석**: `s4_wall.py`를 일반화한다 — 계단·고정 모두에서 처음 무너진 초, 그 시각의 누적 선점·DB 기동 후 경과·체크포인트 진행 여부·대기 이벤트 상위.
- 조건 순서: 회차마다 균형 순환(ADR-006 방식).
- 재측정 판정식: 쉘 `if`로 묶고 스모크에서 양방향 확인한다.

### 9.4 판정 기준

| 기준 | 지표 |
|------|------|
| ① 벽 원인 | 벽 시각의 대기 이벤트 분포 · 체크포인트 진행 여부 · 설정 축별 벽 유무·시각 · 고정 도착률에서 누적 37만 vs 시각 |
| ② 지속성 | 고정 4,200/s 6분 동안 처리량·p99 — 벽이 일시적인가 상한인가 |
| ③ 처리량 | S4 엄격 한계·포화점 — 설정별 3방식 |
| ④ 변형 쓰기 비용 | S3-a20 WAL·디스크 쓰기 — 변형 vs counter-upsert vs none |
| ⑤ 변형 정합·롤백 피해 | S2·S3·S7-m1 매수 초과 0 · `v_counter_mismatch` 0 · S7-m1 억울한 좌석 |
| ⑥ 변형 에러 순서 | S2·S7-m1 응답 코드 — 명세 순서 위반(팔린 좌석에 LIMIT) 건수 |
| ⑦ 실패 모드 | 5xx·데드락·풀 타임아웃 |

### 9.5 문서

- `docs/adr/ADR-007-counter-upsert-throughput-wall.md`: 가설 → 측정 계획 → 측정 전 검증 → 결과 → 결정(ADR-006 §6 전환 규칙 적용). README 결정 기록 갱신.

## 10. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | ADR-007 가설·계획 갱신 | — | 명세와 일치 |
| 02 | 변형 + 테스트 | — | 추가 ①~④ 포함 green, 기존 테스트 green |
| 03 | `k6/ADR-007` 하네스(설정 축·대기 이벤트·고정 도착률·벽 분석) | 02 | 스모크 통과, 가정 1·2 실증 |
| 04 | 코드·하네스 듀얼 리뷰 1패스 + 수정 | 03 | ledger |
| 05 | 본측정 → 재측정 | 04 | 누락 0 |
| 06 | 결과 분석·ADR-007 결과/결정·README · 결과 문서 듀얼 리뷰 · 커밋·push | 05 | 표 재산출 가능 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1 (2026-10-09 "합의")
- [x] 자율성 선택 → MODE=auto
