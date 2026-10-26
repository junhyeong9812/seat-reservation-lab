# k6/ADR-014 — 홀드 만료 방식(A sweep · B lazy · C lazy-sweep · D derived) 측정 하네스

설계·판정 기준: `docs/adr/ADR-014-hold-expiry.md` **'측정 전 고정 규칙'**(측정-0~12 — 리뷰 MR-1~17 · 결정 MQ1~10 반영) · §설계-8.3(방식별 기대값).
ADR-009 하네스(`k6/ADR-009/scripts/lib.sh` · `scenarios/` · `compose.yml` · `compose.tmpfs.yml` · `compose.probe.yml`)는 **수정하지 않고** 동결 사본에서 source·`-f`로 쓴다(측정-3.6 · ADR-013 선례).
측정 조건(측정-1): 서버 .164 · **앱 1대 · lb 없음**(k6 PC → 앱 8101 직접) · L4 · DB tmpfs 12g(DB 14g) · 앱 2g 세대형 ZGC · 카운터 구성 = 업무 앱 기본값 · 배치 주기 10초 · Flyway = 기본 위치 + `classpath:db/migration-seat-map`(V11) · 주입 없음 · `--probes` 켬 · 이미지 고정.

## 구성

| 파일 | 역할 |
|------|------|
| `scripts/plan14.py` | **계획의 단일 출처** — 셀(주 16 + 비용 4) · 순서(회차 우선 · 묶음 a20-t30 → a50-t30 → a20-t300 → a50-t300 → S4c4200 · 방식 ABCD를 (r + g) mod 4칸 회전) · 공통 난수 씨앗(= 회차 번호) · 셀 렌더(앱 설정 · k6 환경 · 탐침 일정 · 사용자·좌석 대역 · 판정 창) · 기대 설정(`--config-check`) · `--dry-run`(시작 조건 ③) · `--darr-extend`(시작 조건 ⑥ — MR-13 식) |
| `scenarios/s14-flow.js` | 주 셀 한 실행 — 시나리오 `blind`(맹목 300/s) · `map`(지도 10/s — `GET /api/schedules/1/seats` → 보이는 AVAILABLE) · 재방문(이탈 세션이 자기 만료 e + δ에 같은 좌석 — 같은 반복 안) · `pr1`·`pr2`·`pr3`(탐침 회차 2). 고정 도착률 · 공통 난수 · 탐침 송신 직전 ms 기록(MR-2) · 원인 표지 헤더 집계 |
| `scenarios/lib14.js` | 결정적 난수(FNV-1a + mulberry32 — (r, 흐름, 세션 번호)) · 세션 속성 해시(crn — MR-11) · 호출 · `X-Seatlab-Reject` 읽기 · 응답 분류(ADR-009 `common.js` errorCodeOf 재사용) |
| `scenarios/warmup14.js` | 예열 — 선점 + 좌석 맵 + 확정, 회차 1·2, 20초(측정-3.6 · '차가운 JVM') |
| 비용 셀 | `k6/ADR-009/scenarios/s4-throughput.js` 그대로(S4c4200 — 램프 60초 + 30초 × 12 · 시드 200회차) |
| `compose.expiry.yml` | ADR-009 compose 위 오버레이 — 앱 이미지 고정(`seatlab-app:<sha8>` · `build: !reset null` · `pull_policy: never`) · `SPRING_APPLICATION_JSON`을 통째로(만료 방식 · TTL · 배치 주기 · Flyway 위치 · 풀). 카운터 키는 넣지 않는다 |
| `scripts/run.sh` | 회차 하나: 내리기 → 이미지 확인 → 기동 → 예열 → 시드 · 시드 확인 → 표본기 → 시작 대기(87 ± 5초) → k6 · 측정기 → 직후 스냅숏(k6 끝 + 11초) → 수렴(TTL + 22초) → 끝 판정기 · 기록 덤프 → 수집 → `summarize.py --rep`. `--prepare-image` · `--dry-run` · `--simulate` · `--worktree`(스모크) |
| `scripts/lib14.sh` | ADR-009 `lib.sh` source + compose 렌더 · 이미지 ID · 설정 수집 · 시드 확인 · 테이블 통계 · 앱−DB 시각 · 표본기 기동·복구 · 측정기(ADR-009 run.sh probes_start/stop 복사) |
| `scripts/psql-sampler.sh` | 서버 쪽 표본기(표준 입력으로 보낸다 — 배포 불필요): `state` 1초(측정-5 머리 SQL + `sample_ms`) · `h4` 0.5초(`/* seatlab:h4-reclaim */` 활성·Lock 대기 — M10). 시작 시각 격자(간격이 쌓여 늘지 않게) |
| `scripts/actsnap14.py` | 앱 지표 스냅숏 — `seat.hold.rejected{cause}` · `seat.hold.reclaim` · `seat.hold.expiry.{deleted,missing_pairs,skip_locked,revert,chunk,missing_mismatch,tick}` · 정리 ①. 카운터·틱·증분·안전망은 ADR-009 `actsnap.py`가 같은 시점에 |
| `scripts/campaign.sh` | 60자리 · 시작 조건 ⓪~⑦ · `--sha` 동결(git archive · 해시) · 이어서 실행 대조 · 재측정(환경 2번 · 하네스 2번 따로) · 멈춤 규칙 · 루프 안 `run.sh < /dev/null` · 끝 집계 전 계획 대 실행 대조(exit 6) · `--dry-run` · `--simulate` · `--check` |
| `scripts/hhash.sh` | 하네스 경로·해시 단일 출처(캠페인 동결본 대 회차 원본 대조) |
| `scripts/summarize.py` | `--rep`(회차 사유 → status · 지표 M1~M14 · 방식 위반 자료) · 캠페인(성공 · 고르는 규칙 1~4단 · 관문 G1~G4 · 결론 · 가설 자료 · `RESULT.md`) · `--crn-check` · `--early-check` · `--sample-cost` · `--r0`. **판정 입력은 `Inputs` 한 곳에서 읽고 검증** — 결측은 0이 아니라 그 입력의 사유(INPUT_REASON)로 무효 · 캠페인에서 None은 판정 불가 |
| `scripts/selftest.py` | 가짜 원자료 단위 확인(회차 사유 · 판정 불가 · 고르는 규칙 분기) · `--campaign` = `campaign.sh --simulate` 루프 시험(60자리 · stdin · 재측정 상한 · `--check`) |

### compose를 사본이 아니라 오버레이로 둔 이유
앱 1대 · lb 없음이라 ADR-013의 혼재 오버레이(`app2` · lb · nginx)는 쓸 데가 없다. db · redis · 앱 자원 · JVM 옵션 · `ApplicationName=seatlab-app`(대기 표본 `by_src`의 `app` 갈래 — env-stall ①) · tmpfs · 서버 측정기가 **ADR-009 정의 그대로여야** ADR-009·012·013 결과와 조건이 같고, 바꿀 것은 앱 `SPRING_APPLICATION_JSON` 하나와 이미지 고정뿐이다. 사본을 두면 같은 정의가 두 곳에 생긴다. `nginx.conf`는 두지 않는다.

## 셀 · 시간(측정-2 — 값은 plan14.py)

| 셀 | 이탈 | TTL | 생각 시간 | D_arr | gracefulStop | 수렴 | 탐침 칸 | 탐침 수(PR-1 · PR-2 · PR-3) | 회차 추정 |
|---|---|---|---|---|---|---|---|---|---|
| a20-t30 | 0.2 | 30초 | 3–24초 | 240초 | 10초 | 52초 | [10, 185) · 1/s | 175 · 175 · 175 | 약 8분 |
| a50-t30 | 0.5 | 30초 | 3–24초 | 360초 | 10초 | 52초 | [10, 305) · 1/s | 295 · 295 · 295 | 약 10분 |
| a20-t300 | 0.2 | 5분 | 30–240초 | 1,200초 | 330초 | 322초 | [60, 875) · 0.25 · 0.25 · 0.2/s | 204 · 204 · 163 | 약 29분 |
| a50-t300 | 0.5 | 5분 | 30–240초 | 2,220초 | 330초 | 322초 | [60, 1895) | 459 · 459 · 367 | 약 46분 |
| S4c4200 | — | 60분 | — | 램프 60 + 360초 | — | — | — | — | 약 13분 |

합계 60회 · 약 21.3시간(추정) + 재측정 · 스모크. 사용자 대역: 맹목 1,000,000+ · 지도 3,000,000+ · PR-1 5,000,000+ · PR-2 6,000,000+ · PR-3 u 7,000,000+ · v 8,000,000+ · 예열 9,000,000+. 탐침 좌석(회차 2 = 10,001~20,000): PR-1 10,001+ · PR-2 11,001+(3석씩) · PR-3 13,001+.

## 실행 순서(서버 .164 — 메인)

```bash
# 0) 접속 없음
python3 k6/ADR-014/scripts/selftest.py --campaign                      # 요약기·판정 규칙 · 캠페인 루프(서버 없음)
python3 k6/ADR-014/scripts/plan14.py --dry-run                          # 60자리 · 탐침 마지막 요청 < D_arr − 5초(시작 조건 ③)
k6/ADR-014/scripts/campaign.sh --sha <SHA> --dry-run                     # 계획 · 시작 조건 ⓪~⑦ 줄
k6/ADR-014/scripts/run.sh --dry-run --sha <SHA> --method C --cell a50-t30 --rep 1 --id x   # 회차 하나의 명령줄 · 경로
# 1) 이미지 고정(캠페인 전에 한 번)
k6/ADR-014/scripts/run.sh --prepare-image --sha <SHA> --out k6/ADR-014/results/images-<SHA>.json
# 2) 스모크(시작 조건 ①) — 네 방식 × a50-t30 + A·D × S4c4200 (--worktree 가능)
for m in A B C D; do k6/ADR-014/scripts/run.sh --sha <SHA> --images <images.json> --method $m --cell a50-t30 --rep 1 --id smoke14-<날짜>; done
for m in A D; do k6/ADR-014/scripts/run.sh --sha <SHA> --images <images.json> --method $m --cell S4c4200 --rep 1 --id smoke14-<날짜>; done
python3 k6/ADR-014/scripts/summarize.py --sample-cost k6/ADR-014/results/smoke14-<날짜>/a50-t30/*/rep1   # 시작 조건 ⑤ — 표본 비용 c_X · Δc
python3 k6/ADR-014/scripts/summarize.py --r0 k6/ADR-009/results                                        # R₀(과거 S3-a20 L4 tmpfs 3회 묶음 DB CPU 범위의 최소)
python3 k6/ADR-014/scripts/plan14.py --darr-extend '{"a50-t30": <T95 최대>, …}'                        # 시작 조건 ⑥ — 늘릴 셀이 있으면 knobs.json으로
# 3) 캠페인 스모크(시작 조건 ② — 두 자리 이상): 별도 --id로 campaign.sh를 돌리고 두 자리가 실행된 것을 본 뒤 멈춘다
# 4) 캠페인
k6/ADR-014/scripts/campaign.sh --sha <SHA> --images <images.json> --start-conditions <json> [--knobs <knobs.json>]
```

시작 조건 JSON(측정-9 ⓪~⑦ — 판정은 `campaign.sh cond_lines`):
`{"smoke":{"done":true,"dirs":[…]},"campaign_smoke":{"done":true},"pc_check":{"done":true,"docker_ps":"…"},"impl_verify":{"done":true,"R0":…,"delta_c":…,"sample_method":"그대로|변경|분리","env_stall_freq":…,"vu_max":{…}},"concurrency_green":{"done":true,"sha":"<SHA>"}}`
③ dry-run은 JSON이 아니라 `plan14.py --dry-run`을 직접 계산한다. ⑥ knobs(`{"d_arr":{…},"map_rate":…,"max_vus":{…},"state_expired_every_s":1|10}`)는 plan.json에 고정되고 이어서 실행 때 다르면 멈춘다.

## 결과 폴더

```
k6/ADR-014/results/<id>/
  order.txt · plan.json · knobs.json · images.json · start-conditions.{json,log} · CAMPAIGN.log · MATRIX.log · runner.log · crn-check.json · result.json · RESULT.md
  <셀>/<방식 A|B|C|D>/rep<k>/            (재측정 앞 시도 = rep<k>.attempt<n>/)
    plan.json · meta.json · status · rep-summary.json · harness-reasons.txt · errors.log
    k6-requests.csv.gz · k6-summary.json · k6-window.json · pre-k6.json · clock-offset.json · k6-stdout.log.gz · warmup.log · warmup-requests.csv.gz · warmup-status.json
    timeline-seatstate.jsonl(1초 상태 — 주 셀) · timeline-h4.jsonl(0.5초 — 주 셀) · timeline-waits.jsonl · timeline-locks.jsonl(0.5초) · timeline-hikari.jsonl(1초)
    timeline-server.jsonl(docker stats 10초) · timeline-client.jsonl · sampler-recovery.txt
    seed.json · seed-check.json · app-config.json · limit-key.json · pg-settings.json · db-state-{seed,k6}.json · io-{before,after}.json · pg-storage*.json
    table-{before,after,end}.json · act14-{before,window-end,after-k6,end}.json · drift-{before,after-k6,end}.json · drift-db.json · consistency.json · app-db-clock-{before,after}.json
    probe-status.json · probe-check.json · k6-probe-{pc,server}.csv.gz · app.log.gz · db.log.gz · gc.log.gz · gc.json · app-jvm.json · logs-status.txt · gc-status.txt
```

## 회차 status · 사유(측정-9)

`ok`(유효 — 방식 실패는 `rep-summary.json` `violations`) · `invalid:<사유,…>`(하네스 — 재측정). `invalid:env-stall`만이면 환경 상한(자리마다 2번 — 하네스 2번과 따로).

| 사유 | 어디서(입력 → 계산) |
|------|------|
| `pre-k6-mismatch` · `pre-k6-overrun` | `pre-k6.json` seed_done → k6 `setup_data.startedAt`(t0) ∉ 87 ± 5초 · run.sh abort14 |
| `cond-config-mismatch` · `expiry-mode-mismatch` · `ttl-mismatch` · `cond-config-missing` | `app-config.json` → `plan14.config_check`(카운터 = 업무 앱 기본값 · 방식 · TTL · 주기 10초 · Flyway 4위치 · 풀 10) · 판정기 `expiry_mode` |
| `limit-key-present` · `limit-key-missing` | `limit-key.json`(ADR-009 limit_key_check — 매수·카운터 키 0) |
| `seed-mismatch` · `seat-map-index-missing` | `seed-check.json`(회차·좌석 수 · 모두 AVAILABLE · 홀드·예약·쿼터 0 · `idx_product_seat_schedule_id` · Flyway V11) |
| `crn-mismatch` | `summarize.py --crn-check`(같은 (회차, 셀) 방식 사이 crn 합이 다수와 다름 → status에 더함 — campaign.sh가 끝 대조 전에 부른다) |
| `state-sampler-gap` | `timeline-seatstate.jsonl` → `state_metrics`(창 양끝 포함 간격 > 3초 · 수 < 창 초 × 0.95 · 파일 없음) |
| `db-cpu-missing` | `timeline-server.jsonl` → `db_cpu`(DB 행 < ⌈창 ÷ 15⌉ · 공백 > 30초) — 비용 판정 전에 무효 |
| `dropped-iterations` | CSV `dropped_iterations` 창 안(주 흐름 시나리오) |
| `probe-timing-bad` · `probe-incomplete` | CSV `probe` 행 → `probe_metrics`(ε = 송신 ms + 시계 차 − 계획 · 칸별 뺀 비율 > 10% · 시작 수 ≠ 계획 · 판정 요청 안 나감(첫 선점이 5xx 아닌 이유로 실패한 탐침 포함 — 코드 리뷰 CR14-10 · 첫 선점 기록 행 자체가 없는 탐침 — CR14-22) · 판정 불가 응답(판정 요청 응답이 5xx·0 밖의 예상 밖 값 — PR-1·2 h1 ∉ {201, 409} · PR-3 v ∉ {201, 409} · c ∉ {200, 409} — CR14-23) · 탐침 기록 못 읽는 행 · 계획에 없는 칸 · 기록 없는 계획 칸(판정 입력 전수 점검) · 칸 < 계획 절반) |
| `clock-offset-bad` | `clock-offset.json`(앞뒤 · 왕복/2 > 20ms · 앞뒤 차 > 100ms · 없음 · 앞뒤 `rtt_s`가 없거나 음수 — 코드 리뷰 CR14-9) |
| `table-stats-missing` | `table-{before,after,end}.json`(P6 — 비용 셀은 end 없음) |
| `app-metric-missing` | `act14-*.json` 없음·조회 실패 · 409 ≥ 1인데 k6 `rej` 합(rule + nowait) = 0 또는 갈래마다 앱 `seat.hold.rejected` 증분과 다름(MR-17) · 판정 창 끝 스냅숏(`act14-window-end.json`) 시작이 창 끝 뒤 [0, 10]초 밖(코드 리뷰 CR14-13) |
| `end-check-failed` | `consistency.json` 없음 · 정합 문턱에 쓰는 판정기 열(`summarize.py` `CONS_COLS` — CR14-20 · CR14-21로 `active_on_reserved_seats`까지) 하나라도 없거나 숫자가 아님(0으로 채우지 않는다 — 코드 리뷰 CR14-8) · run.sh 판정기 실패 |
| `k6-window-missing` · `harness-reasons-missing` | `k6-window.json` 없음·`exit` 숫자 아님(k6 rc를 모른다) · `harness-reasons.txt` 없음(run.sh가 회차 시작에 빈 파일로 만든다 — 하네스 사유를 모른다) — 판정 입력 전수 점검에서 더함(전에는 무음 통과) |
| `warmup-failed` | 예열 k6 rc ≠ 0(run.sh가 그 자리에서 abort14) · `warmup-status.json` 없음·rc 숫자 아님(코드 리뷰 CR14-14 — 이름:상태 분포도 남긴다) |
| `env-stall` | CSV 초별 응답 0이 5초 이상 연속 → `classify_chunk`: 서버 측정기 무응답 > 5초 ∧ GC 정지 < 100ms ∧ ① 대기 표본(공백 ≤ 1초) `app|Lock:*` 0 ∧ ②③ Hikari(앞·안·뒤) pending 0 · active < 최대 |
| `reconcile-missing` | `drift-db.json` reconcile_runs(시드 뒤 `inc-*` · `safety*` 0 — P8 생존) |
| `gc-log-missing` · `container-log-missing` · `probe-missing` · `path-gap` · `waits-gap` · `drift-*` · `cleanup-errors` · `k6-exit-N` · `schedule-closed-seen` | ADR-009 회차 합격 사유(S3e·S3c·C·F 전용 제외) 대응 — 아래 해석 메모 6 |

판정 자료(사유 아님 — `flags`): `expect-mismatch` · `reject-cause-unknown` · `probe-timing-dropped` · `probe-anomaly` · `h4-marker-unseen` · `h4-sampler-missing`(주 셀인데 `timeline-h4.jsonl` 없음 — 코드 리뷰 CR14-12) · `hikari-broken`(Hikari 표본 깨짐 → 정지 덩어리는 방식 정지) · 비용 셀 dropped.

## 판정 입력 출처 표

판정(회차 무효 사유 · 성공(측정-7) · 고르는 규칙 · 관문(측정-8))에 쓰이는 입력값 전부의 출처와, 빠지거나 예상 밖 값일 때의 처리다(코드 리뷰 CR14-21~24 재발 방지 — 같은 꼴의 결함이 `Inputs` 밖 경로에서 되풀이됐다). 줄 번호는 `scripts/summarize.py`. **규칙**: 새 판정 입력은 이 표에 행을 먼저 더하고, 결측·예상 밖 값이 '무효' 또는 '판정 불가'가 되는지 `selftest.py` `REP_INPUTS`(회차) · `generative_campaign`(캠페인)에 넣는다. `or 0` · `.get(k, 0)` · `except: pass` · None을 거짓으로 쓰는 것은 아래에 '설명 — 판정 무관' 또는 '정의상 0'으로 적힌 곳만 허용한다.

처리 칸 머리: **고침** = 이번 점검(또는 CR14-21~24)에서 무음 0·통과를 막음 · **기존** = 이미 막혀 있었음 · **규칙대로** = 측정 전 규칙이 정한 보수 쪽 처리 · **정의상 0** = 집계 dict(행이 없으면 0건이 그 값의 정의) · **설명 — 판정 무관** = 기록·이름표만(판정에 쓰지 않는다). 예외로 요약기가 죽으면 run.sh `finish14`가 `invalid:summarize-failed`로 둔다(무음 아님).

### 회차 판정(`rep_summary` — 무효 사유 · 방식 위반 자료)

| 입력 | 읽는 함수·줄 | 결측·예상 밖 값일 때 | 근거 규칙 |
|---|---|---|---|
| `plan.json` method · cell · kind · mode · rep · ttl_s · window.from_s·len_s | `Inputs.json` 725 · 값 검사 727-728 | **고침** — rep·ttl_s·window·mode 필수 숫자 · 방식·셀·종류가 계획 밖이면 `plan-missing`(전: KeyError · 'x' 방식에 plan14가 프로세스 종료) | 측정-9 하네스(계획 대 실행) |
| `meta.json` | 726 | 기존 `meta-missing` · rerun/attempt는 설명 — 판정 무관(1108 `(meta or {})`) | 측정-9 |
| `harness-reasons.txt` | 732-737 | **고침** — 없으면 `harness-reasons-missing`(전: 사유 없음으로 통과) | 측정-9 · run.sh:162 |
| `k6-summary.json` setup_data.startedAt(t0) | 738 | **고침** — 숫자 필수(전: 문자열이면 TypeError) → `k6-summary-missing` | 측정-0 판정 창 |
| `clock-offset.json` 앞뒤 offset_s · rtt_s | `clock` 173-182 | 기존(CR14-9) `clock-offset-bad` | 측정-9 · CR14-9 |
| `pre-k6.json` seed_done_epoch | 750-754 | **고침** — 숫자 필수 → `pre-k6-mismatch` | 측정-9(ADR-009 pre-k6) |
| `app-config.json` configprops · 풀 최대 | 756-765 → `plan14.config_check` | **고침** — configprops가 묶음 목록이 아니면 `cond-config-missing`(전: AttributeError) · 값 다름·없음은 기존 비교로 `*-mismatch` | 측정-1 · 측정-9 |
| `warmup-status.json` rc | 767-771 | 기존(CR14-14) `warmup-failed` | 측정-9 재해석 CR14-14 |
| `limit-key.json` env · cmd · drift · drift_cmd · present | 772-774 | **고침** — 네 값 숫자 필수 → `limit-key-missing` · present가 bool false가 아니면 있다고 본다(전: `(drift or 0) > 0`) | 측정-1 · 측정-9 |
| `seed-check.json` 회차·좌석·홀드·예약·쿼터 수 | 775-781 | **고침** — 8열 숫자 필수 → `seed-mismatch`(전: KeyError) · seat_map_index·flyway_v11 ≠ 1은 기존 `seat-map-index-missing` | 측정-9 |
| `logs-status.txt` | 783-785 | 기존 `container-log-missing` | 측정-9(ADR-009) |
| `gc.log.gz` → ADR-009 `gc_summary` | 786-798 · P12 GC 1079-1083 · M["gc"] 936 | 기존 `gc-log-missing` · **고침** — P12 GC 값이 숫자가 아니면 None(→ P12 판정 불가 · 전: `(full or 0) + (stalls or 0)`) | 측정-9 · P12 ②③ |
| GC 정지(safepoint)·stall 줄 시각 머리 | `gc_untimed` 505-516 · 792-798 | **고침(CR14-25)** — 시각을 못 읽는 줄이 하나라도 있으면 `gc-log-missing`(회차 무효 — 판정 입력 결측 규칙 · 전: ADR-009 `gc_summary`가 그 줄의 시각을 None으로 두어 k6 창 밖으로 무음 처리 · ADR-009 하네스는 고치지 않고 같은 정규식으로 따로 센다 · `metrics.gc_untimed_lines`) | 측정-9 · P12 ②③ · env-stall(GC < 100ms) |
| `probe-check.json` ok | 799-801 | **고침** — true만 통과(전: 문자열 'x'도 통과) → `probe-missing` | 측정-9(ADR-009) |
| `k6-requests.csv.gz` | `read_csv` 194 · 803-810 | 기존 `k6-csv-missing`(읽기 예외) | 측정-9 |
| `k6-window.json` exit | 811-813 | **고침** — 없거나 숫자 아니면 `k6-window-missing`(전: `load(..) or {}` · exit None 통과) | 측정-9 `k6-exit-N` |
| `timeline-server.jsonl` DB CPU · 서버 시각 · 앱 메모리 | `server_rows` 461-502 · 815-831 | 기존 `db-cpu-missing` · `path-gap` · **고침** — DB·앱 줄의 CPU·메모리를 못 읽으면 버리지 않고 `db-cpu-missing`(전: `continue`·`pass`로 버리고 평균) | 측정-9 M9(MR-3) · P12 ④ |
| `consistency.json` 정합 문턱 열 | `CONS_COLS` 83-86 · 833 | 기존(CR14-8 · CR14-20) · **고침(CR14-21)** `active_on_reserved_seats` → `end-check-failed` · expiry_mode 다름은 기존 `expiry-mode-mismatch`(984) | 측정-7 재해석 CR14-8 · 20 · 21 |
| `drift-db.json` drift_log_total · drift_log 행 | 834-836 · m6 967-979 | **고침** — total 숫자 필수 · 시각·action(`repaired`·`false-positive`·`skipped`)을 못 읽는 행 → `drift-db-failed`(전: 시각 −inf로 무음 제외 → `repaired`가 줄어 정합 위반이 숨는다) · reconcile_runs 시각 못 읽음 → 빠짐(→ `reconcile-missing` 쪽 — 규칙대로 보수) | 측정-7 정합(`repaired`) · P8 |
| `drift-{before,after-k6,end}.json` tick.ok · drift_record_failed · cleanup.errors | 839-844 | **고침** — 숫자 필수 → `drift-snap-failed`(전: `(차분 or 0) > 0`). ADR-009 actsnap.py는 등록 안 된 지표 = 0 · 조회 실패 = None | 측정-9(ADR-009) |
| `act14-{before,window-end,after-k6,end}.json` rejected.rule · tick.ok · 창 끝 t | 845-861 | **고침** — 두 열 숫자 필수(전: 존재만) · 창 끝 시각은 기존(CR14-13) → `app-metric-missing` | 측정-9 · 측정-5 M11 재해석 CR14-13 |
| 원인 표지 대조(앱 rejected 증분 대 k6 `rej`) | 924-932 | 기존 — 앱 증분 None이면 불일치 → `app-metric-missing` · k6 `rej.get(k, 0)`은 정의상 0(헤더 행 없음 = 0건 — 합 0이면 그 자체로 무효) | 측정-9 MR-17 |
| P5 되돌림 기회 kept_active(배치 + 회수) | 944 | **고침** — 한쪽이라도 None이면 None(전: 회수 쪽 `or 0`) → 캠페인 P5 판정 불가 | 측정-6 P5 |
| P12 틱 실패 · :missingIds 롤백(창 안 증분) | 952-956 · 1073-1074 | 기존 — None → `p12_unknown`(판정 불가) | 측정-5 M11 재해석 CR14-13 |
| `table-{before,after,end}.json` n_tup_upd · deadlocks | 957-964 | **고침** — n_tup_upd 숫자 필수 → `table-stats-missing` · deadlocks 없음은 기존 None → P12 deadlock 판정 불가 | 측정-9 · P6 · P12 |
| `db.log.gz` deadlock 줄 시각 | `_db_log_deadlocks` 1162 | 규칙대로 — 시각을 못 읽으면 None → k6 구간 증분(보수) | 측정-5 M11 재해석 CR14-13 |
| `timeline-hikari.jsonl` pending · active | 862-867 · `classify_chunk` 585-594 | **고침** — 깨진 줄·시각 없는 줄·pending·active 숫자 아님 → env-stall ②③을 세우지 못함 → 방식 정지(전: 깨진 줄을 버리고 env 쪽 · 없는 열은 KeyError) | 측정-9 env-stall 행(MR-6) |
| `timeline-waits.jsonl` 시각 · by_src | 868-877 · `classify_chunk` 572-580 | **고침** — 깨진 줄·시각 못 읽는 줄 → `waits-gap` · 정지 구간 표본에 by_src 없음 → 방식 정지(전: `or {}` → Lock 0 → env 쪽) | 측정-9 env-stall ① |
| `k6-probe-server.csv.gz`(서버 측정기) · 풀 최대 | 880-887 · `classify_chunk` 557-560 · 585-590 | 규칙대로 — 없음·읽기 실패 → 정지 덩어리는 방식(세우지 못함) | 측정-9 env-stall |
| GC 긴 정지 목록(덩어리 구간) | `classify_chunk` 568 `default=0.0` | 정의상 0 — `long_stops` = 50ms 넘는 정지 목록 · 없으면 그 구간 최대 < 50ms < 100ms 문턱 | 측정-9 env-stall(GC < 100ms) |
| 정지 초(초별 완료 응답 수) | `stall_chunks` 543 `per_sec.get(s, 0)` | 정의상 0 — 응답 행이 없는 초 = 정지 초 | 측정-9 · P12 정지 초 |
| `timeline-seatstate.jsonl` t · reserved · held_no_active · active_not_held · multi_active · active_on_reserved | `jsonl` 116-131 · 989-1002 · `state_metrics` 416 | **고침** — 깨진 줄(찢긴 마지막 줄 하나 제외) · 판정 열 없음·숫자 아님 → `state-sampler-gap`(전: 깨진 줄 `except: pass` — 그 초의 위반 표본이 숨는다) · 없음·공백은 기존 | 측정-9 · 측정-7 상태 문턱 |
| T_x(T90·T95) 창 안 미도달 | `state_metrics` 435-437 | 규칙대로 — INF(창 밖 = 가장 나쁨) | 측정-5 M2 |
| CSV `probe` 행(탐침 · M3 · PR-3) | `probe_metrics` 280-412 | **고침** — 못 읽는 행(285-289) · 계획에 없는 칸(294-304) · 기록 없는 계획 칸(389-393) · 송신·계획 시각 못 읽음(335-339) · **CR14-22** 첫 선점 기록 없음(310-319) · **CR14-23** 판정 요청 예상 밖 응답(347-355 · 400 — 분모에서 빼고 `unjudgeable`) → `probe-incomplete` · 5xx 가름 `_err5`(숫자 아닌 상태에서 int() 예외 없음) · 첫 선점·판정 5xx는 기존 방식 M11 | 측정-9 `probe-incomplete` · 재해석 CR14-10 · 22 · 23 |
| CSV `hold_ok` · `confirm_rec`(겹침 · 확정 뒤 201 · N_exp) | `flow_metrics` 602-619 · 1019-1021 | **고침** — exp·seat·sent·rt 못 읽는 행 → `k6-csv-missing`(전: `continue`·`except: pass`로 건너뜀 · rt 없음 `or 0`) | 측정-7 정합(M5) |
| CSV `revisit_rec`(재방문) | `flow_metrics` 645-661 | 설명 — 판정 무관(P13 · M5 설명 — 못 읽는 행은 `unparsed`로 센다) | 측정-6 P13 |
| 정합 항목 묶음(integ) | 1037-1063 | **고침(CR14-21)** `active_on_reserved_end` · **고침** — 표본 열 `.get(k, 0)` 제거(표본 0개 = 이미 무효) · 주 셀인데 흐름·탐침 재구성 없음 → `k6-csv-missing` · 값이 숫자 아님 → `end-check-failed`(전: None이 `if v`에서 '위반 없음'으로 걸러졌다) | 측정-7 정합 |
| 상태 기대값 문턱(5·6·7·8행) | `state_thresholds` 675-690 | **고침** — 표본 열 `.get(k, 0)` 제거 | 측정-7 MR-7 |
| `expect-mismatch`(기대 0 아닌 칸) | `expect_mismatch` 692-707 `or 0` | 설명 — 판정 무관(이름표 — 측정-9 '판정 자료') | 측정-9 · M12 |
| P12 항목 계산 기록 | 1068-1087 `p12_checked` | **고침** — 계산한 항목(0 포함)을 남긴다(캠페인이 '키 없음 = 0'으로 읽지 않게) · 5xx_or_0:<이름>은 정의상 0(CSV 집계) | 측정-6 P12 · 측정-7 |
| P4 · P9 · P6 자료 | 1090-1100 | 기존 — P6 입력 None → `p6 = None`(캠페인 판정 불가) | 측정-6 P4 · P6 · P9 |
| 비용 셀 dropped · 1초 p99 · 느린 초 · crn · 원인 표지 미상 · M14 · H4 표본 · reclaim MAX · 창 밖 증분 | 891-905 · 932 · 1026-1035 · 940 · 950-956 | 설명 — 판정 무관(기록 · 이름표) | 측정-9 '판정 자료' · M10 · M14 |
| `rej` cause 없음 → '?' | `read_csv` 249 | 설명 — 판정 무관(`reject-cause-unknown` 이름표 집계) | 측정-5 M3 |
| 예열 상태 분포(`--warmup-status`) | `warmup_status` 1771-1787 `except: pass` | 설명 — 판정 무관(by_status None · 판정은 rc가 한다) | 측정-9 재해석 CR14-14 |

### 캠페인 판정(`crn_check` · `early_check` · `campaign`)

| 입력 | 읽는 함수·줄 | 결측·예상 밖 값일 때 | 근거 규칙 |
|---|---|---|---|
| 유효 회차 요약의 판정 열(rep · integrity · state · p12 · p12_unknown · p12_checked · 주 셀 T95 · M3 · PR-3 · flow · B·C·D의 P4·P9 · D의 P6) | `summary_gaps` 1304-1332 · 로드 1339-1351 | **고침** — 하나라도 빠지면 그 회차를 유효로 세지 않는다(→ 유효 < 3 → 판정 불가 · RESULT.md에 목록). 전: `.get(..) or {}`로 '없음 = 위반 없음' | 측정-7 '판정 불가면 성공 아님' |
| crn 합(같은 회차·셀 방식 사이) | `crn_check` 1200-1240 | **고침** — 유효인데 crn이 없는 회차 → `crn-mismatch`(전: 대조에서 빠져 무음 통과) | 측정-3.5 · 측정-9 |
| A의 W · PR-1 δ별 결과(조기 확인) | `early_check` 1243-1265 | **고침** — 유효인데 W·by_delta 없음 → exit 1(멈춤 · 전: W None은 무시 · by_delta 없음은 0) | 측정-9 '캠페인 멈춤' |
| P12 항목(셀별 A도 같은 문턱) | `item_viol` 1406-1423 · 키 목록 1424-1425 | **고침** — 고정 항목이 checked에도 unknown에도 없으면 판정 불가 · 고정 항목은 늘 본다 · 비용 셀 probe_errors 제외 | 측정-7 · P12 |
| P7 재선점 기회(넓은 값) | 1440-1450 | **고침** — 값 없는 회차가 있으면 판정 불가(전: `or 0`으로 합) | 측정-6 P7 · 재해석 CR14-19 |
| P7·P9 PR-3 실행 · P5 · P6 | 1444-1472 | 기존(CR14-11 · None → 판정 불가) | 측정-6 · 재해석 CR14-11 |
| P3 유효(A 부당 실패) | 1510-1517 | **고침** — 값이 숫자 아니면 None(전: `or 0`) | 측정-8 G2(b) · P3 |
| G4 · P1 W | 1506 | 기존(숫자 · > 0 검사) | 측정-8 G4 |
| 2단 T95 · M3ᵦ 범위 | `undec2` 1531-1536 | **고침** — 남은 후보 둘 이상에서 범위 None → 2단 판정 불가(전: `lower(None)` = 거짓 — CR14-24와 같은 꼴) · M3ᵦ 분모 0 → None(1382 기존) | 측정-8 2단 |
| 3단 비용 6지표 범위 | `undec3` 1538-1551 | **고침(CR14-24)** — 범위 None → 3단 판정 불가 · **고침** — 표본 비용 분리 여부를 모르면(Δc 또는 A 범위 없음) a50-t300 DB CPU를 빼고 돌린 결과가 다를 때 판정 불가 | 측정-8 3단 · 측정-5 머리 |
| 고르는 규칙 판정 불가 → 결론 | `rivals` · `rank_block` 1611-1615 | **고침(CR14-24 ⓓ)** — 관문 통과 후보 말고 관문을 넘었거나 넘었을 수 있는 후보가 있으면 '판정 불가 → 사용자 보고'(잠정 결과 함께) | 측정-8 재해석 CR14-24 |
| G2 (a)·(b) | `tri` · `g2` 1572-1588 | **고침** — 셋값(참 · 거짓 · None) — 범위 None을 '비겹침 아님'으로 읽지 않는다 · G2 판정 불가 + G3 걸림 없음 → ⓒ 갈래(1608) · A 유지 ②는 G2가 모두 확정 실패일 때만 | 측정-8 G2 · 재해석 CR14-18 꼴 |
| G3 비용 5지표 | `g3` 1591-1597 | 기존(CR14-18 None → 판정 불가) · **고침** — 분리 여부를 모르는 a50-t300 DB CPU의 걸림은 판정 불가로 | 측정-8 G3 · 측정-5 머리 |
| 표본 비용 c_X · Δc · A 범위 | 1395-1403 | **고침** — `sep_unknown`(전: Δc None → '분리 안 함'(거짓)) · result.json `separate_a50t300_db_cpu` = None | 측정-5 머리 |
| 설명 가설 P1 L_A · P2 · P10 · P11 · P13 · 지도 X ÷ A | `hypotheses` 1667-1690 · 1647 | 설명 — 판정 무관(P13 `or 0` 포함 — 측정-7 '설명 가설은 성공에 쓰지 않는다') | 측정-6 · 측정-7 · MQ2 |
| 시작 조건 ⑤ 표본 비용 · R₀ | `sample_cost` 1736 · `r0` 1749 | 설명 — 판정 무관(시작 전 log 기록 — 사람이 정한다) | 측정-5 머리 · 측정-9 시작 조건 ⑤ |

## 해석 메모(구현에서 정한 것 — 메인 확인 필요)

1. **재방문은 시나리오가 아니다** — 이탈 세션이 같은 반복에서 e + δ까지 잔다(VU를 그만큼 쥔다). 그래서 재방문의 `dropped_iterations`는 따로 없고, 흐름별 VU 최대는 k6 `vus`(시나리오 태그 없음) 대신 스모크에서 `iteration_duration` 행으로 재구성해야 한다(#25).
2. **crn 해시** = 흐름마다 세션별 FNV 20비트 해시 두 개의 합 + 세션 수(순서 무관 · 실행된 세션 집합의 함수). '(흐름, 세션 번호) 순 이어 붙임'과 같은 것을 잡되 세션마다 태그를 달지 않는다(시계열 수십만 개 방지).
3. **A의 탐침 nowait도 배치 잠금 409로 센다** — A 배치도 좌석 행을 UPDATE한다(측정-5 M3는 C만 적었다 · B·D의 nowait는 분류 불가 → 판정 409). A는 후보가 아니라 G2(b) 비교(M3 합)에만 쓰인다.
4. **탐침 k6 duration = (계획 수 − 0.5) × 주기** — 정수배면 경계 반복이 하나 더 시작됐다(로컬 흉내 서버 시험). 시작 시각은 계획 그대로.
5. **M11 창**: 데드락 = `db.log`의 `deadlock detected` 줄 중 창 안(시각을 못 읽으면 k6 구간 증분) · 틱 실패 · `:missingIds` 롤백 = k6 직전 → **판정 창 끝 스냅숏**(`act14-window-end.json` — k6 실행 + 창 끝 + 3초) 증분(코드 리뷰 CR14-13 · ADR 측정-5 M11 측정 전 재해석). 창 끝 → 회차 끝 증분(gracefulStop · 수렴)은 `tick_failed_outside` · `missing_mismatch_outside`로 기록만(MR-9). 이 메모의 옛 판(k6 직전 → 직후 — 창보다 넓다)은 대체됐다.
6. **ADR-009 회차 합격 사유 중 구현한 것**: pre-k6 · cond-config · limit-key · gc-log · container-log · drift-snap/db/incomplete/record · cleanup-errors · probe-missing · path-gap · waits-gap · k6-exit · schedule-closed-seen(탐침 코드만 — 주 흐름 409는 본문을 읽지 않는다) · 마감 시각 경계(run.sh). **빠진 것**: `borrow-not-one`(빌림 비 — 앱 지표 조합 필요) · S2·S7 끝 상태 · io-timing-off 등 ADR-006·007 디스크 사유(tmpfs 고정이라 판정에 쓰지 않음).
7. **상태 기대값(§설계-8.3) 0이 아닌 칸의 `expect-mismatch`**는 수렴 뒤 값으로 본다 — A 5행 = 0 · B 8행 > 0 · B 5행 > 0 · C 5행 ≤ 건너뜀(c2 + h4b) · C 6행 ≤ h4b.
8. **P7 판정 불가 '재선점 0'**은 '남의 (만료) 홀드 뒤 같은 좌석 201'(k6 재구성) 3회 · 주 셀 합으로 센다(남은 만료 행 여부는 k6로 알 수 없다). 좁힌 값('재선점 시각 − 앞 만료 < 배치 주기 10초' — `rehold_other_narrow`)도 함께 남긴다(기록 — 판정은 넓은 값). → ADR 측정-5 M5 측정 전 재해석(코드 리뷰 CR14-19)으로 확정.
9. **결론**: 관문을 넘은 후보가 있어도 환경 상한 소진 자리(ⓑ)나 'A도 같은 문턱'으로만 판정 불가인 후보(ⓐ)가 있으면 '판정 불가 → 사용자 보고'(잠정 결과 함께).
10. **H4 표지가 표본에 안 보이면** 무효가 아니라 `h4-marker-unseen` 이름표(측정-5 M10은 app-metric-missing이라 적었다 — ms 단위 문장이라 0.5초 표본이 우연히 놓칠 수 있다. #24 스모크로 정한다). 주 셀인데 표본 파일이 없으면 `h4-sampler-missing` 이름표. → ADR 측정-5 M10 측정 전 재해석(코드 리뷰 CR14-12 — 스모크 #24 뒤 다시 보는 조건 포함)으로 확정.
11. **원인 표지 대조는 같은 값이어야 한다** — gracefulStop이 HTTP 요청 도중을 끊으면 앱만 센 409가 생길 수 있다(스모크에서 차이가 보이면 보고).

## 스모크에서 확인할 것(`[구현 검증]` — 측정-11 #15~#25 · §설계-10 #1·#10·#11)

#15 좌석 맵 응답 크기 · PC 수신량 · k6 PC CPU(지도 JSON 파싱) · #16 상태 표본 `sample_ms`(1초 간격 · 표본 수 0.95) · #17 탐침 ε 분포 · #18 `n_tup_upd` 반영 지연(직후 대 + 60초) · #19 앱 지표 이름·태그(actsnap14 `names_present`) · #20 crn 같음 · #21 a50-t30 T95 · #22 `X-Seatlab-Reject` 헤더 · 지표 증분 일치 · C의 nowait · #23 env-stall DB 쪽 표본 · #24 H4 표지 · #25 docker stats DB 행 간격 · 흐름별 VU 최대 · #11 앱−DB 시각(`app-db-clock-*.json`).
