# k6/ADR-007 — counter-upsert 처리량 벽 진단 + 쓰기 없는 선조회 변형(counter-upsert-precheck) 측정

ADR-006 하네스(`k6/ADR-006/`)를 복사하고 **DB 설정 축 · 대기 이벤트 표본 · 고정 도착률 셀 · 벽 분석**을 더했다. 매수 방식에 `counter-upsert-precheck`(명세 §1)를 허용했다.
시나리오·판정기·수집·검증 규칙은 ADR-001~006과 같다 — `k6/ADR-006/README.md`(S4 한계 규칙·순서 순환·I/O 지표)·`k6/ADR-005/README.md`(지표 정의) 참고.
명세: `docs/plans/2026-10-09/adr-007-throughput-wall/requirement-spec.md`(§2 경계 · §8 가정 · §9.2 매트릭스 · §9.3 하네스 · §9.4 판정 기준).

## 구성

| 파일 | 역할 |
|------|------|
| `compose.yml` | ADR-006 compose + DB `command`에 `log_checkpoints=on`과 설정 축 3개(`checkpoint_timeout`·`max_wal_size`·`shared_buffers` — 환경변수, 기본 = PostgreSQL 16 기본값) |
| `scripts/run.sh` | 조건 하나 실행기. ADR-006 + `--pg-preset` · `counter-upsert-precheck` 허용 · 셀 `S4c<rate>` · 회차마다 `pg-settings.json`·`timeline-waits.jsonl` · 재측정 판정식 `if`로 정리 |
| `scripts/campaign.sh` | 본측정 캠페인 — 36조건 × 3회 = 108회, 회차 우선 + 균형 순환(`order.txt`), `--dry-run` |
| `scripts/lib.sh` | ADR-006 + `db_sampler`(락 표본 + 대기 이벤트를 psql 호출 하나로) · `pg_settings`(SHOW 값) |
| `scripts/summarize.py` | ADR-006 + 고정 도착률 30초 구간표 · DB 설정 적용값·대기 이벤트 회차표 · S7 명세 순서 위반(`s7_order`) · `--fixed-check` |
| `scripts/wall.py` | 벽 분석(ADR-006 `s4_wall.py` 일반화) — 계단·고정 모두 |
| `scripts/compare.py` | ADR-006 표 + 방식 × 설정 S4 표 · 고정 셀 구간표 · 벽 분석 표 · S3-a20 쓰기 비용 · 변형 정합·에러 순서 · 실패 모드 |
| `scripts/errsplit.py`·`gapcheck.py`·`stalls.py` | ADR-006 그대로(`stalls.py`는 S4c도 본다) |
| `scenarios/` | ADR-006 그대로 + `s4-throughput.js`에 `STOP_ON_ERROR`(기본 1 = ADR-006과 같음) |

ADR-006의 `recompute_s4.py`(ADR-005 재계산 전용)는 뺐다.

## ADR-006 대비 바뀐 점

1. **매수 방식** `counter-upsert-precheck` 허용(run.sh `LIMIT_STRATEGIES`). 판정기 `v_counter_mismatch`는 앱(`LoadtestDataService.COUNTER_STRATEGIES`)이 낸다 — 하네스는 consistency.json에 그 키가 있으면 표에 낸다(방식 이름으로 거르지 않는다).
2. **DB 설정 축**(아래 표). 회차마다 SHOW 값(`current_setting` — 단위가 붙은 형태)을 `pg-settings.json`과 `meta.json`(`pg_preset`·`pg_env`·`pg_applied`)에 남긴다.
   preset과 다르면 status에 `pg-preset-mismatch-<설정>`, `log_checkpoints`가 on이 아니면 `log-checkpoints-off`, 파일이 없으면 `invalid-pg-settings.json`.
3. **대기 이벤트 표본**(`timeline-waits.jsonl`): 기존 락 표본과 **같은 0.5초 주기·같은 SSH·같은 psql 호출**(`UNION ALL` 둘째 행) — 프로세스·연결을 늘리지 않는다. 한 줄 =
   `{"t", "waits": {"<wait_event_type>:<wait_event>": seat DB 활성 세션 수(표본 세션 제외, 대기 없음 = "CPU:-")}, "bg": {"checkpointer": "IO:DataFileWrite", …}, "bgwriter": pg_stat_bgwriter 누적값}`.
   PG16에는 `pg_stat_checkpointer`가 없다(PG17+) — 체크포인트 수·버퍼는 `pg_stat_bgwriter`, 진행 중 여부는 `bg.checkpointer`와 DB 로그. k6 구간 안 표본 공백 > 30초면 `waits-gap`, 파일이 비면 `missing-timeline-waits.jsonl`.
4. **고정 도착률 셀** `S4c4200`·`S4c2000`(아래 표). 요약기는 '한계' 대신 30초 구간표를 낸다.
5. **벽 분석** `wall.py`(아래).
6. **캠페인 매트릭스·순서**(아래). L4만, 전 조건 3회(ADR-006의 `--s3-reps`는 없앴다).
7. **재측정 판정식**: `rep_status`의 복합 조건을 전부 `if`로 묶었다(ADR-006 S3 9회차가 `A || B && C` 우선순위로 잘못 재측정 대상이 된 버그 — fbaaf815). 양방향 확인은 아래 '재측정 판정식 확인'.

## 셀 · DB 설정(preset) 정의

| 셀 | 시나리오 | k6 환경 | 시드 | 판정 |
|----|---------|---------|------|------|
| `S4` | `s4-throughput.js` 계단 | `START_RATE=50 FACTOR=1.5 STEPS=16 STEP_S=30`(ADR-006과 같음) | 200만 석 | ADR-006 그대로(exit 99 + 성공 단계 있음 = 정상, 새 한계 규칙) |
| `S4c4200` | 같은 시나리오, 고정 | `START_RATE=4200 FACTOR=1 STEPS=12 STEP_S=30 STOP_ON_ERROR=0` — 6분 | 200만 석(4,200 × 360 = 151.2만 < 200만) | 정상 종료만: k6 exit 0 · 12구간 모두 요청 있음 · 실행 시간 ≥ 360초(`--fixed-check`, 아니면 `fixed-incomplete`). 목표 미달은 결과 |
| `S4c2000` | 같음 | `START_RATE=2000 …` | 같음 | 같음 |
| `S2`·`S7-m1`·`S3-a20` | ADR-006과 같다 | | | ADR-006 그대로 |

- 고정 셀은 에러율 중단을 끈다(`STOP_ON_ERROR=0` → `hold_error{stage:i}` 임계값이 비어 k6가 멈추지 않는다) — 무너진 뒤에도 6분을 끝까지 걸어 회복·지속을 본다(명세 §9.4 ②).
  TTL 60분(S4와 같은 앱 설정 그룹)이라 측정 중 만료 배치가 없다. 구간 = k6 `stage` 태그(요청 시작 시각 기준 30초).
- 누적 37만(ADR-006 벽 시점)에 닿는 시각: 4,200/s ≈ 88초, 2,000/s ≈ 185초(k6 시작 기준) — 시간 기준 체크포인트(+약 249초) 전이다(명세 §9.2).

| preset | `checkpoint_timeout` | `max_wal_size` | `shared_buffers` | 뜻 |
|--------|------|------|------|----|
| `default` | 5min | 1GB | 128MB | PostgreSQL 16 기본값 = ADR-006과 같은 DB |
| `nockpt` | 1h | 16GB | 128MB | 측정 중 체크포인트 없음(회차당 WAL 약 0.75GB — 명세 §8 가정 1, 스모크로 실증) |
| `buf512` | 5min | 1GB | 512MB | 공유 버퍼만 키움(C2 버퍼 교체 후보) |

세 조건은 서로 독립이다(조합하지 않는다). 그 밖의 조건(서버·유선·시드·예열·Tomcat·풀 10·인덱스 V2·좌석 3b·타이밍 설정 on)은 ADR-006과 같다.
`shared_buffers`는 mmap 익명 공유 메모리라 `shm_size`(256m)와 무관하고, DB 컨테이너 메모리 한도 2g 안이다.

## 벽 분석 (`wall.py`)

정의는 `scripts/wall.py` 머리말이 정본이다. 요약:

- **무너진 초** = 초별 p99 > 200ms가 처음 나온 초(k6 시작 기준, 요청 완료 시각 초 단위).
  계단은 ADR-006 `s4_wall.py`와 같은 창(11단계 시작 = 325~344초 중 초당 완료 ≥ 3,000인 첫 초 → 그 초부터 370초 전), 고정은 0초부터 계획 시간 전까지 전체. 무너지지 않으면 '-'.
- 그 시각의 **누적 선점**(벽 초 이전에 완료된 201) · **DB 기동 후 경과**(DB 로그의 k6 시작 전 마지막 `database system is ready to accept connections` 기준) ·
  **체크포인트 진행 중**(DB 로그 `checkpoint starting`~`complete`, shutdown 제외) · **벽 ±3초 대기 이벤트 상위 5**(`timeline-waits.jsonl` 평균 세션 수)와 체크포인터 대기 이벤트.
- k6 구간과 겹친 체크포인트 목록(종류 · +시작~완료)도 낸다 — `nockpt`에서 측정 중 체크포인트가 없었는지(가정 1)의 근거.
- ADR-006 결과로 확인: `k6/ADR-006/results/20261009-adr006-022eca41/counter-upsert-s24`에 돌리면 11단계 시작·무너진 초·차이가 `S4-WALL.md`의 counter-upsert 10행과 모두 같다(아래 '오프라인 확인').

## 캠페인 (명세 §9.2)

| 묶음 | 방식 | preset | 셀 | 회차 | 조건 이름 |
|------|------|--------|----|------|----------|
| 진단 | none · counter-upsert · counter-upsert-precheck | default · nockpt · buf512 | S4 · S4c4200 · S4c2000 | 3 | `<방식>-<preset>-<s4\|s4c4200\|s4c2000>` |
| 변형 확인 | 같음 | default | S2 · S7-m1 · S3-a20 | 3 | `<방식>-default-<s2\|s7m1\|s3a20>` |

- 진단 27조건 × 3 = 81 + 변형 확인 9조건 × 3 = 27 → **108회 호출**(run.sh 한 번 = 조건 하나의 회차 하나, L4). `--dry-run`이 `계획: 조건 36개 × 3회 = 108회 호출`을 낸다.
- 순서 = 회차 우선 + 균형 순환(ADR-006 '개선 2'의 일반화): 묶음 = (preset, 셀) 12개. 회차 k = 묶음 순서를 (k−1) × ⌈12 ÷ 3⌉ = 4칸씩 회전 + 묶음 안 방식 순서 (k−1) 회전.
  ADR-006은 묶음이 3개라 1칸씩 돌려도 충분했지만, 12개를 1칸씩 돌리면 3회 동안 묶음 자리가 거의 그대로다. 4칸씩 돌리면 각 묶음이 캠페인의 앞·가운데·뒤 1/3을 한 번씩 맡고, 묶음 목록이 preset을 섞어 두어 한 1/3 안에 세 preset이 고루 든다. 방식은 3 × 3 라틴 방진.
- 검증(`order.txt` 행 수·조건 집합·순번, 기존 `order.txt`·`conditions.txt` 대조)·재측정(최대 2바퀴)·연속 기동 실패 중단은 ADR-006 그대로.

## 실행

```bash
k6/ADR-007/scripts/campaign.sh --sha <SHA> --id <campaign-id> --dry-run       # 계획만(108행)
k6/ADR-007/scripts/campaign.sh --sha <SHA> --id <campaign-id>                 # 본측정
k6/ADR-007/scripts/run.sh --sha <SHA> --limit-strategy counter-upsert --pg-preset nockpt --levels 4 --cells S4c4200 --reps 1 --id <id>   # 조건 하나
for d in k6/ADR-007/results/<campaign-id>/*/; do [ -f "$d/plan.json" ] && k6/ADR-007/scripts/summarize.py "$d"; done
k6/ADR-007/scripts/wall.py k6/ADR-007/results/<campaign-id> --json k6/ADR-007/results/<campaign-id>/wall.json > k6/ADR-007/results/<campaign-id>/WALL.md
k6/ADR-007/scripts/errsplit.py k6/ADR-007/results/<campaign-id>
k6/ADR-007/scripts/compare.py k6/ADR-007/results/<campaign-id>                # wall.json·errsplit.json을 읽는다
```

- 요청별 원시 기록(`k6-requests.csv.gz`)은 커밋하지 않는다(repo `.gitignore` · `k6/ADR-007/.gitignore`) — `errsplit.py`의 sha256 목록과 요약만.
- 서버 배포는 `results/` 제외(`lib.sh deploy_sha` — `k6/*/results/**`), 스모크 `--worktree`도 `k6/ADR-007/results` 제외.

## 재측정 판정식 확인 (오프라인, 2026-10-09)

`rep_status`·`preset_env`만 떼어 ADR-006 회차 사본(심링크 — 고치는 파일만 실제 파일로 바꿔서)에 돌렸다. 판정식마다 의도적 실패 입력과 정상 입력 양쪽 — 32건 모두 기대대로:

| 판정식 | 정상 입력 → 사유 없음 | 의도적 실패 입력 → 사유 있음 |
|--------|------|------|
| 빌림 범위(`if [[ base != S3* ]] && jq …`) | S4 정상 · S3 정상 · S3 분모 20배(S3는 제외라 여전히 없음) | S4 분모 2배(빌림 0.5) → `invalid-borrow-ratio` · 같은 입력 S4c → 있음 |
| S7 좌석 끝 상태(`if [[ base == S7 ]] && ! jq …`) | S7 정상 · S2(좌석 없음이 정상) | S7 `per_seat = {}` → `invalid-end-state.json-seats` |
| preset 대조 | default·128MB · nockpt·1h·16GB | buf512인데 128MB → `pg-preset-mismatch-shared_buffers` · nockpt인데 5min·1GB → `…-checkpoint_timeout`·`…-max_wal_size`(shared_buffers는 없음) · log_checkpoints off · 파일 없음 |
| k6 종료 | S4 exit 99 + 성공 단계 있음 → `k6-exit-99`·`s4-no-successful-stage` 없음 | S4c exit 99 → `k6-exit-99` |
| 고정 셀 정상 종료(`--fixed-check`) | 12구간 · 365초 · S4(계단)는 검사 안 함 | 300초 · 한 구간 요청 0 → `fixed-incomplete` |
| 대기 표본 | 1초 간격 표본 | 가운데 60초 빠짐 → `waits-gap` · 빈 파일 → `missing-timeline-waits.jsonl`(공백 검사는 안 붙음) |
| S4c 필수 파일 | S2는 timeline-db 불필요 | S4c 빈 timeline-db → `missing-timeline-db.jsonl` |

## 요약기·벽 분석 오프라인 확인 (2026-10-09)

- `summarize.py`를 ADR-006 `counter-upsert-s24`(L2·L4 × S2·S4 × 5회) 사본(plan.json 복사 + 회차 폴더 심링크)에 돌렸다: SUMMARY.md의 S2·S4 행이 ADR-006 SUMMARY.md와 바이트 단위로 같고(새 'DB 설정·대기 이벤트' 표만 늘었다), summary.json의 회차별 `limits`·`stages`·빌림·WAL·S2 판정이 모두 같다.
- `wall.py`를 같은 폴더에 돌렸다: counter-upsert 10회차의 11단계 시작·무너진 초·차이가 ADR-006 `S4-WALL.md`와 모두 같다(L4 357·357·358·358·357 등). 함께 나온 값 — 누적 선점 36.6만~37.1만 · DB 기동 후 407~409초 · 10/10 시간 기준 체크포인트(+249~251초 시작, +422~429초 완료) 진행 중.
- `compare.py`를 ADR-006 6조건(none·counter-upsert × s24·s7m1·s3a20) 사본에 돌렸다: 방식 × 설정 표의 default 열 = none 4,300 / 4,300 · counter-upsert 2,866 [2,866–4,162] / 4,143(ADR-006 §5.3 ②와 같다). ADR-006 원본은 읽기만 했다.
