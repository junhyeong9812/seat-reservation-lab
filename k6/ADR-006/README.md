# k6/ADR-006 — 매수 카운터 한 문장 upsert: counter-upsert vs advisory-try 재비교 측정

ADR-005 하네스(`k6/ADR-005/`)를 복사하고 **매수 방식 `counter-upsert`**를 조건 축에 더했다. 그 위에 ADR-005 결과 분석(ADR-005 §7.3 ③·§9)에서 나온 **하네스 개선 3가지**를 넣었다.
시나리오·판정기·수집·검증 규칙은 ADR-001~005와 같다 — `k6/ADR-005/README.md`(지표 정의 표 포함)·`k6/ADR-003/README.md` 참고. 명세: `docs/plans/2026-10-09/adr-006-counter-upsert/requirement-spec.md`(§9.2 매트릭스·§9.3 하네스·§9.4 판정 기준).

## 구성

| 파일 | 역할 |
|------|------|
| `compose.yml` | ADR-005 compose 복사 + DB `command`로 `track_io_timing=on`·`track_wal_io_timing=on`(아래 '타이밍 설정') |
| `scripts/run.sh` | 조건 하나(매수 방식 × 단계 × 셀 × 회차) 실행기. ADR-005 + `counter-upsert` 허용 + 회차마다 `io-before.json`·`io-after.json` |
| `scripts/campaign.sh` | 본측정 캠페인 — 9조건, 회차 우선 + **회차마다 조건 순서 섞기**(`order.txt`), `--dry-run` |
| `scripts/lib.sh` | 원격 함수(배포·기동·조회) + `io_snapshot`(서버 I/O 스냅숏) |
| `scripts/summarize.py` | 조건별 요약(SUMMARY.md·summary.json) — **S4 새 한계 규칙** · 요청당 커넥션 빌림 · I/O 차분 |
| `scripts/compare.py` | 캠페인 교차표(COMPARISON.md) — ADR-005 표 + 빌림·I/O 표 + 회차별 실행 시각·순서 표 |
| `scripts/recompute_s4.py` | ADR-005 캠페인의 S4를 새 규칙으로 다시 계산(원본은 읽기만) → `results/adr005-recomputed-s4/` |
| `scripts/errsplit.py`·`gapcheck.py`·`stalls.py` | ADR-005 그대로(응답 코드 분류·경로 단절 판정·S4 멈춤 구간) |
| `scenarios/` | ADR-005 그대로 |

ADR-005의 끝 단계 DB 벤치(`limit-bench.sh`)는 ADR-006 매트릭스에 없어 뺐다(캠페인 단계·compare 표 모두).

## 조건 축 · 캠페인 (명세 §9.2)

| 축 | 값 |
|----|----|
| 매수 방식 | `none`(대조군) · `advisory-try`(ADR-005 결정) · `counter-upsert`(신규 — 트랜잭션 안 `INSERT … ON CONFLICT DO UPDATE … WHERE cnt + 1 <= 상한` 한 문장) |
| 고정 | 좌석 pessimistic-nowait(3b) · 풀 10 · 앱 1대 · 인덱스 V2 · 배경 0 (ADR-005와 같다) |

| 조건 이름 | 셀 | 단계 | 회차 |
|----------|----|------|------|
| `<방식>-s24` | S2 · S4 | L2 · L4 | 5 |
| `<방식>-s7m1` | S7-m1(2매 보유자 U + 일반 1명) | L4 | 5 |
| `<방식>-s3a20` | S3-a20(선점 → 확정 원본, 이탈 20%) | L4 | 3(`--s3-reps 3`) |

셀 이름을 그대로 넘긴다 — `run.sh expand_cells`는 `S3`만 이탈 0/20/50으로 펼치고 `S3-a20`·`S7-m1`은 그대로 둔다(`cell_base`가 S3·S7 시나리오로 보낸다). 3방식 × 3묶음 = 9조건, 회차 1~3은 9조건 · 4~5는 S3을 뺀 6조건 = 39회 호출.

## 개선 1 — S4 한계 규칙 (목표 미달에서 멈춘다)

정의는 `scripts/summarize.py` 머리말 'S4 한계 규칙'이 정본이다. 요약:

- 완전한 단계(30초를 다 채운 단계)를 계단 순서로 보며 **처음으로 ① p99 없음 ② p99 ≥ 기준 ③ 에러율 ≥ 기준 ④ 목표 미달(실제 도착 RPS < 0.9 × 목표)** 중 하나에 걸린 단계에서 멈추고, 그 직전 단계의 성공 RPS를 한계로 낸다. ④가 새 조건이다 — ADR-005는 ①~③만 봐서, 포화(목표 미달) 단계 뒤에 부하가 줄어 기준을 다시 만족한 단계가 '통과'로 집혔다.
- 멈춘 단계와 사유(`strict_stop` — 예: `12(6,487/s):under-delivered`)를 함께 남긴다. 한 번도 멈추지 않으면 계단 상한(compare.py `≥`).
- **포화점** = 완전한 단계 중 최대 성공 RPS(ADR-005와 같은 정의) · **포화 단계** = 처음으로 목표 미달인 단계(목표·실제 도착·성공 RPS). 새 규칙의 엄격 한계 ≤ 포화점은 항상 성립한다.
- 옛 규칙 값(`strict_legacy`)도 함께 남겨 두 규칙이 갈리는 회차를 드러낸다.

**ADR-005 재계산**(`results/adr005-recomputed-s4/S4-RECOMPUTED.md`, `scripts/recompute_s4.py`): 옛 규칙 재계산값이 ADR-005 summary.json 기록값과 전 회차 같다(대조 불일치 0 — 재계산 경로 검증). 새 규칙에서 값이 바뀐 것은 **serializable L4 4회차뿐**이다 — 옛 673·806·708·662 → 새 1,994·1,972·1,981·1,982(멈춘 단계 = 12번(6,487/s) 목표 미달, 그 단계 성공 약 660~810건/s). 5회차(2,021)와 나머지 9개 방식·L2는 첫 목표 미달 단계가 p99·에러 위반 단계와 같거나 뒤라 값이 같다.
관찰(원시 단계 표): serializable L4에서 4,325/s 단계(11번)는 실제 도착 약 4,300/s로 목표를 냈고 성공은 약 1,994/s(나머지는 409)다 — 목표 미달은 그다음 6,487/s 단계부터다.

## 개선 2 — 조건 순서 섞기 (`campaign.sh`)

- 회차 우선은 그대로(1회차 전 조건 → 2회차 → …). 회차 k 안의 조건 순서 = 조건 이름을 `sha256("<시드>:rep<k>:<조건>")` 오름차순으로 정렬한 순열(시드 `--order-seed`, 기본 `adr006`).
  - 해시 정렬을 고른 이유: 파이썬 `random`의 상태·버전에 기대지 않고, 같은 시드·조건 목록이면 언제 다시 계산해도 같은 순서가 나온다 → `--resume`(같은 `--id`)에서 그대로 재현된다.
- 시작할 때 순서를 계산해 `order.txt`(`회차 회차안순번 전체순번 조건`)에 쓰고, 이미 있으면 다시 계산한 순서와 같은지 대조해 다르면(조건 목록·시드·회차 수 변경) 멈춘다. `conditions.txt`도 같은 방식으로 대조한다. 실행은 `order.txt`를 정본으로 읽는다.
- `CAMPAIGN.log`에 회차마다 `ORDER rep<k>: …` 행. 끝의 비정상 회차 재측정은 순서 밖이다(`re-measure` 행 — 시각으로 본다).
- `--dry-run`: `conditions.txt`·`order.txt`·`CAMPAIGN.log`(DRY-RUN·ORDER 행)만 쓰고 끝(측정·배포 없음, 작업트리 하네스 검사 생략). 실증: `results/smoke-order-dryrun/`.

## 개선 3 — 서버 I/O 지표 (`lib.sh io_snapshot`)

회차마다 k6 **직전·직후**에 SSH 한 번으로 서버에서 읽어 `io-before.json`·`io-after.json`에 남기고, `summarize.py io_delta`가 차분을 낸다.

| 출처 | 내용 |
|------|------|
| `pg_stat_wal`(PG16) | `wal_records`·`wal_fpi`·`wal_bytes`·`wal_buffers_full`·`wal_write`·`wal_sync`·`wal_write_time`·`wal_sync_time`(ms)·`stats_reset` |
| `pg_stat_io`(PG16) | 전 행(backend_type/object/context) — reads·writes·writebacks·extends·fsyncs·hits·evictions + `*_time`(ms). **PG16의 pg_stat_io에는 WAL I/O가 없다**(관계 파일만 — WAL fsync는 pg_stat_wal) |
| `pg_stat_database`(seat) · `pg_stat_bgwriter` | 커밋·롤백 수 · 체크포인트 수(timed+req)·체크포인트 쓰기/동기화 ms |
| `pg_settings` | `track_io_timing`·`track_wal_io_timing`·`wal_sync_method`·`synchronous_commit`·`fsync`·`full_page_writes`·`max_wal_size`·`checkpoint_timeout`·`shared_buffers`·`server_version` 실제 값 |
| `/proc/diskstats` | Docker 데이터 루트(볼륨 `pgdata`가 그 아래)의 파일시스템 원본 → `lsblk -s`로 그 아래 장치 전부. .164 확인(2026-10-09): `/dev/mapper/ubuntu--vg-ubuntu--lv` = `dm-0`(lvm) → `nvme1n1p3`(part) → `nvme1n1`(disk, Samsung SSD 980 500GB, write cache write back). 표는 물리 장치(type disk)를 쓴다 — dm·파티션은 flush 수가 0으로 나온다 |

요약기 지표(정의는 `summarize.py io_delta`):

| 지표 | 정의 |
|------|------|
| 요청당 커넥션 빌림 | Hikari 획득 COUNT 증가분 ÷ 선점 요청(hold URI) COUNT 증가분 — `after-k6.json`(명세 §9.4 ③) |
| WAL fsync 수 · 평균 ms | Δ`wal_sync` · Δ`wal_sync_time` ÷ Δ`wal_sync` (WAL fsync 1회 평균) |
| 커밋/WAL fsync | Δ`xact_commit` ÷ Δ`wal_sync` — 그룹 커밋 정도. 커밋에는 하네스 표본 쿼리(락 표본 0.5초·DB 폴링 — 자동 커밋 SELECT)도 들어간다 |
| WAL MB · 선점당 WAL B | Δ`wal_bytes` · Δ`wal_bytes` ÷ 선점 요청 |
| 체크포인트 수 | Δ(`checkpoints_timed` + `checkpoints_req`) |
| 관계 파일 fsync 평균 ms | pg_stat_io 합 Δ`fsync_time` ÷ Δ`fsyncs`(체크포인터 등 — 0건이면 미측정) |
| 디스크 쓰기 대기 평균 ms | 물리 장치 Δwrite_ms ÷ Δwrites(iostat `w_await`와 같은 정의) |
| 디스크 flush 평균 ms | Δflush_ms ÷ Δflushes(diskstats 행의 19·20번째 열 — 커널 5.5+, .164는 6.8) |
| 디스크 util % · 쓰기 MB | Δio_ms ÷ 스냅숏 간격 · Δwrite_sectors × 512 |

- **통계 반영 대기**: PostgreSQL 백엔드는 누적 통계를 바로 공유 메모리에 내지 않는다(PG15+ — 마지막 반영 1초 뒤, 쉬면 최대 10초 뒤). 그래서 직전 스냅숏은 시드 끝에서 `IO_SETTLE_S`(기본 11초), 직후 스냅숏은 k6 끝에서 `IO_SETTLE_S`가 지난 뒤 읽는다(앞 단계가 이미 그만큼 걸렸으면 기다리지 않는다). 직전 스냅숏은 앱 누적 지표 직전 값보다 먼저 읽어 그 사이 만료 배치의 커넥션 획득이 빌림 수에 섞이지 않게 했다. 결과: 스냅숏 간격 = k6 구간 + 양 끝 대기 — 차분에는 대기 구간(유휴)의 I/O도 들어간다. meta.json `io_settle_s`.
- **디스크 지표는 서버 전체**다 — 같은 디스크를 쓰는 이 실험 밖 프로세스(서버의 다른 컨테이너 등)의 I/O도 섞인다. 방식 간 차이의 원인 판정에는 WAL 지표(이 DB만)를 먼저 본다.
- 회차 판정(run.sh `rep_status`) 추가: `invalid-io-before.json`·`invalid-io-after.json`(파일 없음·깨짐) · `invalid-io-*-content`(`wal_sync` 없음·장치 0개) · `io-timing-off-*`(두 타이밍 설정 중 하나라도 off).

### 타이밍 설정 — 켰다 (ADR-005와 측정 조건이 다르다)

- `track_wal_io_timing`·`track_io_timing`은 기본값 off이고, off면 `wal_write_time`·`wal_sync_time`·pg_stat_io `*_time`이 0이다 → fsync 평균 시간(명세 §9.3)을 낼 수 없다. 그래서 compose의 DB `command`로 둘 다 켰다(스냅숏 `pg.settings`로 실제 값 확인 — 스모크에서 `on`).
- 비용 판단: 켜면 WAL 쓰기·동기화와 블록 I/O마다 시각을 읽는다. .164의 clocksource는 `tsc`(확인 2026-10-09)라 시각 읽기는 커널 진입 없는 vDSO 호출이다. 이 비용을 따로 재지는 않았다(pg_test_timing 미실행) — **작다고 판단했을 뿐 측정으로 확인한 것은 아니다**.
- **ADR-005(타이밍 off)와 측정 조건이 다르다**: ADR-005 수치와 직접 견주지 않는다(명세 §2 — ADR-005 수치는 참고 인용만). ADR-006 세 방식은 모두 같은 설정이라 캠페인 안 비교는 공정하다.
- 그 밖에 ADR-005와 달라진 실행 조건: 회차마다 k6 직전에 최대 `IO_SETTLE_S`(11초) 기다림, 직후 스냅숏을 위해 최대 11초 기다린 뒤 끝 상태(S2·S7 `end-state.json`)를 읽는다(S2·S7 TTL 5분이라 홀드는 그대로).

## 시간 효과 표 (명세 §9.4 ⑥)

`compare.py`의 '회차별 실행 시각·순서' 표: 조건 × 셀 × 회차마다 status · k6 시작 시각 · 회차 안 순번 · 캠페인 전체 순번(order.txt) · 핵심 값(S4 새 엄격 한계·포화점 / S2 p99 / S7-m1 억울한 좌석 / S3 확정 에러율) · 요청당 빌림 · WAL fsync 평균 · 디스크 쓰기 대기·flush 평균 · 체크포인트 수. 같은 방식의 회차 흔들림이 시각·순번·I/O와 같이 움직이는지 보는 원자료다(판정은 ADR-006 문서).

## 실행

```bash
k6/ADR-006/scripts/campaign.sh --sha <SHA> --id <campaign-id> --dry-run          # 순서 계획만(order.txt)
k6/ADR-006/scripts/campaign.sh --sha <SHA> --id <campaign-id>                    # 본측정 — 9조건, 회차 우선 + 순서 섞기, 비정상 회차 재측정
k6/ADR-006/scripts/run.sh --sha <SHA> --limit-strategy counter-upsert --levels 4 --cells 'S7-m1' --reps 5 --id <id>   # 조건 하나
for d in k6/ADR-006/results/<campaign-id>/*/; do [ -f "$d/plan.json" ] && k6/ADR-006/scripts/summarize.py "$d"; done
k6/ADR-006/scripts/errsplit.py k6/ADR-006/results/<campaign-id>                   # 응답 코드 분류·원시 sha256 (compare 전에)
k6/ADR-006/scripts/compare.py k6/ADR-006/results/<campaign-id>                    # 교차표 + 판정기 전수 + 빌림·I/O + 시간 효과
k6/ADR-006/scripts/recompute_s4.py k6/ADR-005/results/20261006-adr005-7acad14b k6/ADR-006/results/adr005-recomputed-s4
```

- 실행하는 하네스 = 기록되는 SHA, `--worktree`(스모크 전용)·`--resume`·재측정·연속 기동 실패 중단은 ADR-005 그대로.
- 요청별 원시 기록(`k6-requests.csv.gz`)은 커밋하지 않는다(`k6/ADR-006/.gitignore`) — `errsplit.py`의 sha256 목록과 요약만. 앱·DB 로그·k6 stdout은 회차마다 gzip(GitHub 100MB 한도).

## 스모크 (2026-10-09, `--worktree`, L4 1회 — 본측정 아님)

앱 = 커밋 `6be8a410`(counter-upsert는 `aebae8f2`), 하네스 = 작업트리(S2·S4 `worktree:90efe15c`, S7-m1 `worktree:054f44d8` — 사이에 README만 고쳤다). 회차 판정 전부 `ok`.

| 결과 폴더 | 확인한 것 | 값 |
|----------|----------|----|
| `results/smoke-s4-counter-upsert` | 요청당 커넥션 빌림(명세 가정 2) · 새 규칙 엄격 한계·포화점 · I/O 기록 | 빌림 **1.0001**(획득 655,847 ÷ 선점 655,796 — 나머지 약 50회는 만료 배치 등) · 엄격 2,866(멈춘 단계 11번 4,325/s: p99 569ms) · 포화점 4,128 · 포화 단계 12번(6,487/s → 실제 도착 2,462/s) · 풀 타임아웃 0 · 데드락 0 · WAL fsync 338,180회 평균 0.744ms · 커밋/fsync 1.95 · 체크포인트 2회 · 물리 디스크 쓰기 대기 0.43ms · flush 평균 0.69ms |
| `results/smoke-s2-counter-upsert` | 매수 정합 | 201 200/상한 200 · 매수 초과(판정기) 0 · `v_counter_mismatch` 0 · 가짜 거절 0 · 201 − 홀드 행 0 · 빌림 1.000 · p50 109 / p99 209ms |
| `results/smoke-s7m1-counter-upsert` | 이긴 쪽 롤백 | 억울한 좌석 0/100 · U 100/100이 HOLD_LIMIT_EXCEEDED(좌석 락 전) · 일반 201 100 · `v_counter_mismatch` 0 · 빌림 1.0025(setup 200 + 측정 200 요청에 획득 401) |
| `results/smoke-order-dryrun` | 순서 섞기 | `--dry-run` 9조건 × 5회(S3는 3회) = 39행 · 같은 `--id`로 다시 실행 → 같은 order.txt · 시드를 바꾸면 '다르다'로 거부(exit 2) |

- 스모크 1회 값이다 — 방식 비교·ADR-005와의 비교에 쓰지 않는다(본측정은 같은 캠페인 안에서만).
- counter-upsert에서도 `seat.hold.limit.prepare`·`check` 타이머 COUNT가 요청 수만큼 나온다 — 앱이 모든 방식에서 prepare·check 호출을 타이머로 감싸고 counter-upsert는 빈 함수라 평균 0.0001ms 안팎(none과 같다). 커넥션 빌림이 1회라는 근거는 Hikari 빌림 수다.
