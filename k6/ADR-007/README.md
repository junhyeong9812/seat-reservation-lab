# k6/ADR-007 — counter-upsert 처리량 벽 진단 + 쓰기 없는 선조회 변형(counter-upsert-precheck) 측정

ADR-006 하네스(`k6/ADR-006/`)를 복사하고 **DB 설정 축 · 대기 이벤트 표본 · 고정 도착률 셀 · 벽 분석**을 더했다. 매수 방식에 `counter-upsert-precheck`(명세 §1)를 허용했다.
시나리오·판정기·수집·검증 규칙은 ADR-001~006과 같다 — `k6/ADR-006/README.md`(S4 한계 규칙·순서 순환·I/O 지표)·`k6/ADR-005/README.md`(지표 정의) 참고.
명세: `docs/plans/2026-10-09/adr-007-throughput-wall/requirement-spec.md`(§2 경계 · §8 가정 · §9.2 매트릭스 · §9.3 하네스 · §9.4 판정 기준).

## 구성

| 파일 | 역할 |
|------|------|
| `compose.yml` | ADR-006 compose + DB `command`에 `log_checkpoints=on`과 설정 축 3개(`checkpoint_timeout`·`max_wal_size`·`shared_buffers` — 환경변수, 기본 = PostgreSQL 16 기본값) |
| `scripts/run.sh` | 조건 하나 실행기. ADR-006 + `--pg-preset` · `counter-upsert-precheck` 허용 · 셀 `S4c<rate>` · 회차마다 `pg-settings.json`·`timeline-waits.jsonl` · 재측정 판정식 `if`로 정리 |
| `scripts/campaign.sh` | 본측정 캠페인 — 39조건 × 3회 = 117회, 회차 우선 + 균형 순환(`order.txt`), `--dry-run` |
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
   **서버 사본**: 서버 루프가 같은 줄을 배포 폴더 `.samples/<측정 id>-L<단계>-<셀>-rep<k>-<시각>-<난수>.jsonl`(시도마다 새 이름 — 리뷰 R2: 재측정이 옛 사본에 덧붙이지 않게)에 **먼저** 덧붙이고 표준 출력으로 낸다.
   표준 출력 쓰기가 실패하면(받는 쪽이 꺼져 SSH가 닫힘) 또는 부모 sshd 세션이 사라지면 서버 루프가 끝난다(리뷰 R1 — 재연결 뒤 옛 루프가 남지 않게; 로컬 모의·서버 짧은 시험으로 확인).
   회차 끝(`recover_samples`)에 받아서
   흘려받은 줄보다 많으면 `timeline-locks.jsonl`·`timeline-waits.jsonl`을 그 사본으로 다시 만든 뒤 서버 사본을 지운다(지운 뒤에도 남으면 `errors.log` 한 줄 + `sampler-copy-not-removed` 사유 · `sampler-recovery.txt`에 한 줄 — `recovered-from-server locks a→b waits c→d` / `stream-complete` / `server-copy-unavailable`).
   스모크 실측(2026-10-09): S4·S4c 4회 모두 k6 +240~261초 무렵부터 **흘려받기만** 멈췄고 S2는 흘려받은 줄이 0이었다. **원인 = 받는 쪽 awk(이 PC는 mawk 1.3.4)의 파이프 입력 블록 버퍼링**(리뷰 A11):
   mawk는 파이프 입력을 큰 덩어리로 읽어(`fflush`는 출력만 비운다) 줄이 덩어리째 늦게 오고, 회차 끝에 루프를 끄면 마지막 덩어리가 사라졌다
   (0.1초마다 600바이트 줄 재현: mawk 2초 뒤 0줄, `-W interactive` 20줄). SSH는 끊기지 않았다(스모크 errors.log `db_sampler ended` 0건).
   지금은 받는 쪽이 bash `read` 루프(한 바이트씩 읽어 줄마다 바로 쓴다 — 버퍼에 남는 줄이 없다, awk 구현과 무관)다. 서버 사본 복구와
   표본 SSH 재연결(끝나면 1초 뒤 다시 연다 — `errors.log`에 `db_sampler ended …`)은 무해한 보조 장치로 남겼다. 회차마다 `sampler-recovery.txt`의 흘려받은 줄 수와 서버 줄 수가 같아야 한다.
   표본이 나온 뒤에 부하를 거는 대기(최대 15초)가 시간 초과되면 `errors.log`에 `samples-before-load timeout` 한 줄을 남긴다.
   k6 구간 안 표본이 하나도 없으면 `waits-none-in-k6`(짧은 셀 S2·S7은 k6가 몇 초라 30초 공백 판정으로는 못 잡는다 — 리뷰 A10).
   요약 회차표의 대기 이벤트 칸: `표본 없음`(k6 구간 안 표본 0) · `활성 세션 없음(표본 있음)` · 상위 5.
4. **고정 도착률 셀** `S4c4200`·`S4c2000`(아래 표). 앞에 **램프 60초**(50/s → 목표, 선형 — 리뷰 A13)를 두고 그 뒤 12구간 × 30초를 잰다. 요약기는 '한계' 대신 30초 구간표를 낸다.
5. **벽 분석** `wall.py`(아래).
6. **캠페인 매트릭스·순서**(아래). L4만, 전 조건 3회(ADR-006의 `--s3-reps`는 없앴다).
7. **재측정 판정식**: `rep_status`의 복합 조건을 전부 `if`로 묶었다(ADR-006 S3 9회차가 `A || B && C` 우선순위로 잘못 재측정 대상이 된 버그 — fbaaf815). 양방향 확인은 아래 '재측정 판정식 확인'.
8. **시계 차**(리뷰 A3): 벽·k6 구간은 k6 PC 시계, 대기 표본(`clock_timestamp()`)·DB 로그는 서버 시계다. `lib.sh clock_offset`이 k6 바로 앞·뒤에
   열어 둔 SSH 하나로 서버 `date +%s.%N`을 5번 묻고 왕복이 가장 짧은 표본의 `서버 − (보낸 + 받은)/2`를 `clock-offset.json`·`meta.json clock_offset`에 남긴다(`rtt_s` = 오차 상한 × 2).
   wall.py·summarize.py·대기 표본 판정이 그 평균을 서버 시각에서 뺀다. 측정이 없으면 `clock-offset-missing`, |차| > 0.5초면 `clock-offset-large`.
9. **S2·S7 요청 기록 대조**(리뷰 A5): `hold_code` 행(요청 1건당 1행) 수가 선점 요청 수(k6 요약 `hold_duration` count — S7 setup의 U 사전 선점은 둘 다에 없다)와 다르거나 깨진 행(잘림·`code` 태그 없음)이 있으면 `hold-codes-mismatch`(`summarize.py --codes-check`). 요약 표의 비정상 열에도 `codes-error`로 드러난다.
10. **S7 명세 순서 위반의 분모**(리뷰 A7): `u_after_r_201` = U를 보낸 시각이 같은 좌석 R의 201 수신 뒤였던 U 요청 수(기회 — `u_hle_after_r_201`은 그 부분집합) · 기회의 U 코드 분포 · `u_minus_r_sent_ms`(U 보낸 − R 보낸, ms) · `u_minus_r201_recv_ms`(U 보낸 − R 201 수신).
    S7-m1 시나리오는 U를 R보다 1ms 먼저 보내므로(바꾸지 않았다) 기회는 구조적으로 드물다 — ADR-006 S7-m1 10회(none·counter-upsert)는 기회 0이다. counter-upsert(매수 판정이 좌석보다 먼저)가 양성 대조로 비교표에 나란히 나온다.
11. **S7-m1-late**(명세 §9.2·§9.4 ⑥ 재합의 2026-10-09): 그래서 U를 같은 좌석 R의 응답을 받은 뒤 보내는 셀을 더했다 — R이 201인 좌석의 U는 구성상 기회(`order=after_r` 태그로 센다 — ms 시각 추정에 기대지 않는다). 위반 = 그 기회에서 U가 `HOLD_LIMIT_EXCEEDED`. counter-upsert 양성 대조(위반 = 기회), 변형은 0이어야 명세 순서. 기회 0 회차는 `s7late-no-opportunity`(`summarize.py --s7late-check`). compare.py '변형 에러 순서(⑥) — S7-m1-late' 표.

## 셀 · DB 설정(preset) 정의

| 셀 | 시나리오 | k6 환경 | 시드 | 판정 |
|----|---------|---------|------|------|
| `S4` | `s4-throughput.js` 계단 | `START_RATE=50 FACTOR=1.5 STEPS=16 STEP_S=30`(ADR-006과 같음) | 200만 석 | ADR-006 그대로(exit 99 + 성공 단계 있음 = 정상, 새 한계 규칙) |
| `S4c4200` | 같은 시나리오, 고정 | `START_RATE=4200 FACTOR=1 STEPS=12 STEP_S=30 STOP_ON_ERROR=0 RAMP_S=60 RAMP_FROM=50` — 램프 1분 + 6분 | 200만 석(램프 12.75만 + 4,200 × 360 = 151.2만 = 163.95만 < 200만) | 정상 종료만: k6 exit 0 · 12구간 모두 요청 있음 · 실행 시간 ≥ 램프 + 360초(`--fixed-check`, 아니면 `fixed-incomplete`). 목표 미달은 결과 |
| `S4c2000` | 같음 | `START_RATE=2000 …` | 같음 | 같음 |
| `S2`·`S7-m1`·`S3-a20` | ADR-006과 같다 | | | ADR-006 그대로 |
| `S7-m1-late` | `s7-winner-rollback.js` `LATE=1` | S7-m1과 같음 + `LATE=1`: 좌석마다 R 담당 VU가 R을 보내고 **응답을 받은 뒤** 같은 VU에서 U를 보낸다(U 담당 VU는 요청 없음 — VU 수·setup·좌석·사용자·R 시각은 S7-m1과 같다). U 태그 `order=after_r` | S7-m1과 같음 | S7 판정 + 기회(R 201 좌석의 U) 0이면 `s7late-no-opportunity` |

- 고정 셀은 에러율 중단을 끈다(`STOP_ON_ERROR=0` → `hold_error{stage:i}` 임계값이 비어 k6가 멈추지 않는다) — 무너진 뒤에도 6분을 끝까지 걸어 회복·지속을 본다(명세 §9.4 ②).
  TTL 60분(S4와 같은 앱 설정 그룹)이라 측정 중 만료 배치가 없다. 구간 = k6 `stage` 태그(요청 시작 시각 기준 30초, 램프 끝부터).
- **램프**(리뷰 A13, `s4-throughput.js` `RAMP_S`·`RAMP_FROM` — 기본 0 = 램프 없음이라 계단 S4는 ADR-006과 같다): 스모크 smoke-b(none·nockpt, 램프 없음)는 0초부터 4,200/s를 걸어
  기동 과도에 VU가 상한 10,000까지 늘었고, 그 뒤 1~12 구간 모두 `request timeout` 약 1,000~1,700 + `dial i/o timeout` 70~500이 남았다(완료된 요청 p99는 6ms).
  계단 S4는 붕괴(14단계+) 전에 코드 0이 없다. 램프 요청은 stage 태그 `ramp` — 구간표·`--fixed-check`·과도/벽 분석에서 빠지고 요약에 따로(요청·코드 0·dropped) 남는다.
- **status `ok` = 회차가 유효하다**(계획 구간을 다 돌았다)이지 목표를 냈다는 뜻이 아니다 — 고정 셀은 무너짐 자체가 측정값이다(리뷰 A6). 요약·비교표의 구간 칸에
  도착/목표 · dropped(k6가 VU가 없어 시작 못 한 반복, 램프 끝 기준 30초 구간) · p99(코드 0 제외) · 코드 0 비율을 함께 낸다.
- **코드 0 지연**(리뷰 A12): k6는 코드 0(요청 타임아웃·연결 실패·끊김) 요청의 지연을 0에 가깝게 기록한다(smoke-b 코드 0 20,222건 중 18,538건 < 5ms) — 섞으면 p99가 낮게 나온다.
  고정 셀 구간 p99는 원시 CSV에서 코드 0을 빼고 다시 낸다(`summarize.py fixed_csv` — k6 요약 p99도 옆에 남긴다). 계단 S4 한계 규칙은 에러율(< 1%·< 5%)이 그런 단계를 이미 멈추게 해 그대로 두고,
  단계별 코드 0 수는 compare.py '응답 코드 분류 — S4 단계별'에서 본다.
- 누적 37만(ADR-006 벽 시점)에 닿는 시각(계획 도착률 추정): 램프 60초(4,200/s 램프 약 12.75만 · 2,000/s 약 6.15만) 뒤라 4,200/s ≈ 118초, 2,000/s ≈ 214초(시나리오 시작 기준) —
  시간 기준 체크포인트(+약 249초) 전이다(명세 §9.2). **판정은 이 추정이 아니라 회차마다 실제 누적으로 한다**: wall.py `cum370k_s`(누적 201이 37만에 처음 닿은 k6 초 — compare.py 벽 표 마지막 열)와 벽 시각의 누적 선점.

| preset | `checkpoint_timeout` | `max_wal_size` | `shared_buffers` | 뜻 |
|--------|------|------|------|----|
| `default` | 5min | 1GB | 128MB | PostgreSQL 16 기본값 = ADR-006과 같은 DB |
| `nockpt` | 1h | 16GB | 128MB | 측정 중 체크포인트 없음(회차당 WAL 약 0.75GB — 명세 §8 가정 1, 스모크로 실증) |
| `buf512` | 5min | 1GB | 512MB | 공유 버퍼만 키움(C2 버퍼 교체 후보) |

세 조건은 서로 독립이다(조합하지 않는다). 그 밖의 조건(서버·유선·시드·예열·Tomcat·풀 10·인덱스 V2·좌석 3b·타이밍 설정 on)은 ADR-006과 같다.
`shared_buffers`는 mmap 익명 공유 메모리라 `shm_size`(256m)와 무관하고, DB 컨테이너 메모리 한도 2g 안이다.

## 벽 분석 (`wall.py`)

정의는 `scripts/wall.py` 머리말이 정본이다. 요약:

- **나쁜 초** = 그 초의 p99(코드 0 제외) > 200ms **또는** 코드 0 완료가 그 초 완료의 1% 이상(리뷰 A12). **무너진 초(벽)** = 처음 나쁜 초(k6 시작 기준, 요청 완료 시각 초 단위).
  계단은 **첫 단계 뒤 전체**에서 찾고 그 초의 단계(stage 태그 최빈값)를 함께 낸다(리뷰 A1 — ADR-006 `s4_wall.py`의 11단계 [시작, 370초) 창은 밀린 벽·이른 붕괴·벽 없음을 모두 '-'로 냈다).
  ADR-006 비교용 11단계 시작(325~344초 중 초당 완료 ≥ 3,000인 첫 초)·벽 − 그 초도 그대로 낸다. 고정은 램프 뒤 계획 구간 전체.
- **벽 결과**는 '-'로 뭉개지 않는다: `N초(단계/구간 s)` · `무너지지 않음`(창 안에 완료가 있었는데 나쁜 초 없음) · `과도 미종료`(고정 셀에서 5초 연속 정상이 한 번도 없었다 — 처음부터 무너진 상태, 리뷰 A2) · `표본 없음`.
- **고정 셀의 기동 과도**: 램프 없이 예열 직후 곧바로 4,200/s를 걸면 첫 30초가 과도(스모크: p99 11.8초)라 위 정의의 벽이 1초로 나왔다(리뷰 A13으로 램프를 넣어 보통 곧바로 끝난다 — 판정은 그대로 둔다).
  `settle_s`(요청 **시작** 초 기준 나쁘지 않은 초가 5초 연속인 첫 초)와 `wall_after_settle_s`(그 뒤 처음 나쁜 시작 초)를 함께 내고, 정황(누적·체크포인트·대기 이벤트)과 compare.py 표는 과도 뒤 벽을 쓴다.
  완료 초로 묶으면 과도 중에 보낸 요청이 30초 타임아웃으로 끝나는 초가 벽으로 잡힌다. 코드 0은 지연이 틀려 시작 초 묶음에서 빼고, 실패가 드러난 초(완료 초)의 비율로 센다.
- **회차 판정**(run.sh `rep_status`가 `wall.py --rep-check`로 — `wall-check.json`): 벽이 있는데(고정 셀 `과도 미종료`면 처음 나쁜 초 `wall_s` 기준 — `thin_check_s`) 그 ±3초 대기 표본 < 6(0.5초 표본이라 기대 약 12) → `waits-thin-at-wall`(리뷰 A4) ·
  `nockpt`에서 k6 구간과 겹친 체크포인트(shutdown 제외)가 있으면 `checkpoint-during-k6`, DB 로그를 못 읽으면 `checkpoint-check-failed`(리뷰 A14) · wall.py 실패 → `wall-check-failed`.
- 그 시각의 **누적 선점**(벽 초 이전에 완료된 201) · **DB 기동 후 경과**(DB 로그의 k6 시작 전 마지막 `database system is ready to accept connections` 기준) ·
  **체크포인트 진행 중**(DB 로그 `checkpoint starting`~`complete`, shutdown 제외) · **벽 ±3초 대기 이벤트 상위 5**(`timeline-waits.jsonl` 평균 세션 수)와 체크포인터 대기 이벤트.
- k6 구간과 겹친 체크포인트 목록(종류 · +시작~완료)도 낸다 — `nockpt`에서 측정 중 체크포인트가 없었는지(가정 1)의 근거.
- ADR-006 결과로 확인: `k6/ADR-006/results/20261009-adr006-022eca41`에 돌리면 첫 단계 뒤 전체 탐색 + 코드 0 규칙으로도 11단계 시작·무너진 초·차이가 `S4-WALL.md` 30행(counter-upsert 10행 포함) 모두와 같다(아래 '오프라인 확인').

## 캠페인 (명세 §9.2)

| 묶음 | 방식 | preset | 셀 | 회차 | 조건 이름 |
|------|------|--------|----|------|----------|
| 진단 | none · counter-upsert · counter-upsert-precheck | default · nockpt · buf512 | S4 · S4c4200 · S4c2000 | 3 | `<방식>-<preset>-<s4\|s4c4200\|s4c2000>` |
| 변형 확인 | 같음 | default | S2 · S7-m1 · S7-m1-late · S3-a20 | 3 | `<방식>-default-<s2\|s7m1\|s7m1late\|s3a20>` |

- 진단 27조건 × 3 = 81 + 변형 확인 12조건 × 3 = 36 → **117회 호출**(run.sh 한 번 = 조건 하나의 회차 하나, L4). `--dry-run`이 `계획: 조건 39개 × 3회 = 117회 호출`을 낸다.
- 순서 = 회차 우선 + 균형 순환(ADR-006 '개선 2'의 일반화): 묶음 = (preset, 셀 묶음) 12개(S7-m1·S7-m1-late는 한 묶음 — 13개면 어떤 회전 폭으로도 앞·가운데·뒤 1/3을 한 번씩 맡지 못한다). 회차 k = 묶음 순서를 (k−1) × ⌈12 ÷ 3⌉ = 4칸씩 회전 + 묶음 안 방식 순서 (k−1) 회전.
  ADR-006은 묶음이 3개라 1칸씩 돌려도 충분했지만, 12개를 1칸씩 돌리면 3회 동안 묶음 자리가 거의 그대로다. 4칸씩 돌리면 각 묶음이 캠페인의 앞·가운데·뒤 1/3을 한 번씩 맡고, 묶음 목록이 preset을 섞어 두어 한 1/3 안에 세 preset이 고루 든다. 방식은 3 × 3 라틴 방진.
- 검증(`order.txt` 행 수·조건 집합·순번, 기존 `order.txt`·`conditions.txt` 대조)·재측정(최대 2바퀴)·연속 기동 실패 중단은 ADR-006 그대로.

## 실행

```bash
k6/ADR-007/scripts/campaign.sh --sha <SHA> --id <campaign-id> --dry-run       # 계획만(117행)
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
- `wall.py`를 같은 폴더에 돌렸다(고정 셀 과도 처리 추가 뒤 다시 돌려도 같다): counter-upsert 10회차의 11단계 시작·무너진 초·차이가 ADR-006 `S4-WALL.md`와 모두 같다(L4 357·357·358·358·357 등). 함께 나온 값 — 누적 선점 36.6만~37.1만 · DB 기동 후 407~409초 · 10/10 시간 기준 체크포인트(+249~251초 시작, +422~429초 완료) 진행 중.
- `compare.py`를 ADR-006 6조건(none·counter-upsert × s24·s7m1·s3a20) 사본에 돌렸다: 방식 × 설정 표의 default 열 = none 4,300 / 4,300 · counter-upsert 2,866 [2,866–4,162] / 4,143(ADR-006 §5.3 ②와 같다). ADR-006 원본은 읽기만 했다.
