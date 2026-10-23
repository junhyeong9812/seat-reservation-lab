# k6/ADR-013 — 운영 중 카운터 대조 구성 전환 측정 하네스(옛 앱 93dd610a → 새 앱 ADR-012 구성 · 5절차 × S4c·S3e × 3)

설계·판정 기준: `docs/adr/ADR-013-counter-config-cutover.md` §4(하네스 H1~H10 · 적용판 §4.4) · §5(가설 P1~P12 · 사유 §5.3 · 성공 §5.4 · 고르는 규칙 §5.5 · **§5.8이 §5.4·§5.3·§4.7.1의 해당 정의를 대신한다**).
ADR-009 하네스(`k6/ADR-009/scripts/lib.sh` · `scenarios/` · `compose.yml` · `compose.tmpfs.yml`)는 **수정하지 않고** 동결 사본에서 source·`-f`로 쓴다(앞 ADR 조건의 렌더·계획이 바뀌지 않게).

## 구성

| 파일 | 역할 |
|------|------|
| `scripts/plan13.py` | **계획의 단일 출처** — 시각표(S4c 1,200초 T1 300 · T2 600 · 수렴 180 / S3e 약 1,027초 T1 360 · T2 540 · 수렴 322) · 절차별 단계(§4.2 표 — 오케스트레이터가 그대로 실행) · 계획 사건 목록·끝 상태(H3) · 탐침 일정(§5.8-1) · 구간 분류(§4.7.1 S5-11) · 경계 판정(DS-10) · 기대 설정(H9) · `--probe-dry-run`(§4.9-20) · `--events-check` |
| `scripts/run.sh` | 회차 하나: 내리기 → 이미지 확인 → 옛 앱 단독(셋업 사건) → 초기화·예열·시드·시드 확인 → 기준 DB(seat_ref) → 표본기 → k6·가용성 탐침·한도 탐침·주입·오케스트레이터 → 수렴 → 끝 검사 → 앱별 수집 → 판정. `--prepare-images`(이미지 고정) · `--calibrate seed|load`(보정 1회) · `--dry-run` |
| `scripts/lib13.sh` | ADR-009 lib.sh를 source + 슬롯 기동·내림 · lb 구성원(include + `nginx -t` + reload + 새 작업자 확인) · 비움 확인 · 앱별 수집 · 촘촘한 락 표본 · S4c SQL 시드·시드 확인 · seat_ref · 끝 검사 · `inject_drift13`(ADR-009 `inject_drift` 복사 — 주 부하 회차만 · V10 있을 때만 기록 표) |
| `scripts/campaign.sh` | 30회 계획·순서(회차 우선 · S4c → S3e · 절차 (k−1)칸 회전) · 시작 조건 ①~⑤ · 재측정(invalid만 · 자리마다 2번) · 멈춤 규칙 · `--dry-run` |
| `scripts/summarize.py` | `--rep`(회차 판정 자료 + §5.3 사유 → status) · 캠페인(P1~P12 자료 · 성공 · §5.5 고르는 규칙 · `RESULT.md` TAIL 표) · `--select` · `--calibration` |
| `scripts/limitprobe.py` | 한도 탐침 실행기(§4.7.1 — H·L × d {75, 30, 5, 1} × 칸 3 = 24 · lb 경유 선점 + SQL 주입 + 판정 직후 스냅숏). k6가 아닌 이유: 선점 사이 DB 주입·스냅숏이 필요하다 |
| `scripts/appcollect.py` | 앱별 수집(H4 — actuator 지표 전체 · configprops · health) |
| `scripts/selftest.py` | 가짜 회차 폴더로 요약기 단위 확인(짝 제외 · 칸별 T ≤ O · (자) 짝 0 칸 판정 불가 · J 모집단 · N 실제 구간 · 결정성 분류 · 예상 밖 응답 · 최소 수 원인 · H4·GC 결측 · (마) 응답 섞인 탐침 · P7 이어 읽기 끝 · 끝 검사 앱 · 고르는 규칙 결정성·순환 · ① 중단) |
| `scenarios/avail-probe.js` | 가용성 탐침(2건/s · 시한 5초 · VU 20/20 · 탐침 회차의 5,001~10,000번째 좌석 · 요청마다 새 사용자) |
| `compose.cutover.yml` | 혼재 오버레이(슬롯 A=`app` · B=`app2` · 이미지 `seatlab-app:<sha8>` · `pull_policy: never` · build 없음 · 포트 8111/8112 · ApplicationName · 그림자 손잡이 · lb) |
| `nginx.conf` | ADR-008 lb 복사 + 구성원 include · 접근 기록(`$msec $request_time $upstream_addr $status`) |
| `../../ops/online-v9/` | 운영 중 적용판(§4.4) — `apply.sh` + `01-column.sql` · `02-function.sql` · `02-trigger.sql` · `03-index.sql` · `04-catalog.sql` · `05-history.sql` |

## 실행 순서(서버 .164 — 메인)

```bash
# 0) 탐침 일정 · 캠페인 계획(접속 없음)
python3 k6/ADR-013/scripts/plan13.py --probe-dry-run              # 경계 탐침 0이어야 시작(§4.9-20)
python3 k6/ADR-013/scripts/plan13.py --probe-dry-run --startup-s 40 --collect-s 5 --stop-s 3 --drain-s 5   # 민감도 — 아래 해석 메모 3
k6/ADR-013/scripts/campaign.sh --sha <SHA> --dry-run
python3 k6/ADR-013/scripts/selftest.py
# 1) 이미지 고정(캠페인 전에 한 번 — DS-11)
k6/ADR-013/scripts/run.sh --prepare-images --sha-old 93dd610a --sha-new <SHA> --out k6/ADR-013/images-<SHA>.json
# 2) 보정 1회(§4.1 DS-12): load 먼저(쿼터 행 수 N) → seed --pairs N → 비
k6/ADR-013/scripts/run.sh --calibrate load --sha <SHA> --images <images.json> --id calib-<날짜>
k6/ADR-013/scripts/run.sh --calibrate seed --pairs <N> --sha <SHA> --images <images.json> --id calib-<날짜>
python3 k6/ADR-013/scripts/summarize.py --calibration results/calib-<날짜>/calibrate-seed/calibrate.json results/calib-<날짜>/calibrate-load/calibrate.json
# 3) 스모크(--worktree 가능) — 회차 하나
k6/ADR-013/scripts/run.sh --sha <SHA> --images <images.json> --proc 4 --load S4c --rep 1 --id smoke13-<날짜> [--worktree]
python3 k6/ADR-013/scripts/summarize.py --rep k6/ADR-013/results/smoke13-<날짜>/S4c/p4/rep1
# 4) 캠페인(시작 조건 JSON: {"calibration":{"c_round_ratio":…,"create_index_ratio":…},"ds13_smoke":{"done":true,…},"impl_verify":{"done":true,…},
#    "smoke_durations":{"startup_s":…,"collect_s":…,"stop_s":…,"drain_s":…}} — 마지막은 스모크 events.jsonl의 기동 → UP · 수집 · 내림 · 비움 실측 최대 초(⑤ dry-run 입력 — 없으면 막음))
#    측정 DB 비밀번호는 명령줄로 넘기지 않는다 — 셸 환경 EXT_DB_PASSWORD 또는 하네스 SHA의 k6/ADR-009/compose.yml POSTGRES_PASSWORD(ADR-009 ext_db_password) → apply.sh 환경 FLYWAY_PASSWORD · 원격 Flyway는 ssh 표준 입력
k6/ADR-013/scripts/campaign.sh --sha <SHA> --images <images.json> --start-conditions <json>
```

## 회차 결과(status) · 사유(§5.3 · §5.8-5)

`ok` · `invalid:<사유,…>`(하네스 — 무효 → 재측정, 자리마다 최대 2번) · `fail:<사유,…>`(절차 원인 — **유효 실패 회차** · P10 반증). 판정 자료(회차 사유 아님)는 `rep-summary.json` `flags`.

| 사유 | 분류 | 어디서 |
|------|------|------|
| `seed-mismatch` · `cond-image-mismatch` · `aux-image-mismatch` · `ref-db-failed` · `deploy-failed` · `compose-*-failed` · `setup-*` · `seed-failed` · `reset-failed` | 하네스(k6 전 — 회차를 시작하지 않는다) | run.sh `abort13` |
| `cond-image-mismatch`(회차 안 기동 직전 · N1) · `cond-config-mismatch`(H9) · `nginx-t-failed` · `reload-not-effective`(DS-9 ⓑ) · `compose-up-failed` · `stop-failed` | 하네스 → `transition-failed:<사유>` | run.sh `do_start` · `do_config_check` · `lb_apply` · `do_stop` |
| `inflight_unknown`(시작한 요청 0인데 진행 중 지표를 끝까지 못 읽음) · `started_after_unknown`(접근 기록 못 읽음) — 0으로 보지 않는다 | 하네스 → `transition-failed:<사유>` | run.sh `do_drain` |
| `drain-incomplete` — 남은 요청이 마이그레이션·적용판 세션 락에 막힘(`cause_class` blocked_by_migration) · 락 대기인데 막은 쪽이 그 밖·원인 질의 실패(`cause_unknown` — 원인을 가르지 못함) | **절차** | run.sh `do_drain`(lib13 `drain_lock_cause`) |
| `drain-incomplete` — 남은 요청이 락 대기 아님(`not_lock_wait`) | 하네스 | 같음 |
| `health-timeout`(300초 · Flyway 실패 포함) · `pre-apply-failed` | **절차** | run.sh `do_wait_health` · `do_pre_apply` |
| 원인 불명 `transition-failed` | 절차 | summarize `rep_summary`(cause ≠ harness → 절차) |
| `transition-failed:events-check`(사건 누락·계획에 없는 사건·순서·끝 상태 — 실패 사건 행 없이) · `event-write-failed` · `orchestrator-overrun` · `meta-write-failed` | 하네스 | summarize · run.sh |
| `baseline-unstable` — [60, 90) S4c 정지 초 > 0 · 5xx·502 > 0 | 하네스(환경) | summarize `k6_metrics` |
| `avail-short-outside-window` — 전환 창 밖 (보낸 + 버린) < 95% | 하네스 | summarize `avail_metrics` |
| `mlock-samples-missing` — ADR 창 표본 결측 > 10% | 하네스 | summarize `mlock_metrics` |
| `p7-data-missing` · `old-app-log-missing:<생애>` · `k6-exit-*` · `*-missing` · `db-password-missing` | 하네스 | summarize · run.sh |
| `app-collect-missing:<생애>:<항목>`(H4 — UP한 생애의 수집 없음 · 수집 사건 detail 오류 · 지표·configprops · 앱 로그) · `gc-log-missing:<생애>`(GC 요약 없음 · 빈 로그 · 창 없음) — 전환 실패 회차는 묻지 않는다 | 하네스 | summarize(lib13 `app_collect`는 읽기 실패·빈 로그면 파일을 남기지 않는다) |
| `final-app-mismatch`(끝 검사 앱 ≠ 계획 마지막 새 앱 — 그 앱이 회차 끝에 health를 못 냄) · `final-app-missing`(하네스) | **절차** | summarize(run.sh `final-app.json`) |
| `probe-nondeterministic`(기계적 분류 = 하네스) | 하네스 — **캠페인이 멈추고 보고**(그 부하 15회 재실행은 사용자 확인) | summarize `probe_metrics` · campaign `run_one` |
| `probe-short` — 실행기 끝 행 없음 · 기록 < 계획 · 주입·스냅숏 SQL 실패 · 실행기 예외 | 하네스 | summarize |
| `probe-short-by-procedure` — 그 밖(사건 지연으로 경계 · 실패 응답) | **절차**(P9 반증 — §5.8-5) | summarize |
| `pre-apply-late` · `pre-apply-blocking-exceeded`(N6b > 3초) · `owner-uncovered-s` · `window_clamped` · `page_full` · `old-app-errors`(P11) · `end-check-timeout`(→ 그 회차 P9 판정 불가) · `probe-anomaly`(예상 밖 판정 응답 → 캠페인 P9 (바) 정합 분류) | 판정 자료(flags) | summarize |

캠페인 멈춤: `probe-nondeterministic` · 하네스 실패 연속 5회 · 끝 요약에서 P8 반증·판정 불가 또는 P7 반증(자동 재실행 없음 — §5.8-5). 하네스를 고치면 새 `--id`로 30회 전체(앞 결과는 superseded로 남긴다).

## §4.6 H1~H10 구현

| H | 구현 |
|---|---|
| H1 혼재 오버레이 · digest 고정 | `compose.cutover.yml`(이미지 태그 · `pull_policy: never` · `build: !reset null`) · `run.sh --prepare-images`(없을 때만 빌드 → `images.json` ID) · 회차 시작 + 기동마다 `image_id` = plan 값(`cond-image-mismatch`) · `up --no-build --no-deps` |
| H2 lb 구성원 | `lib13.sh lb_members`(include 파일 다시 쓰기 → `nginx -t` 실패면 되돌리고 실패 · reload → 새 작업자 pid가 옛 집합과 겹치지 않을 때 효력 시각) · 자리표시 서버 `127.0.0.1:9 down` 늘 · `lb_started_after`(효력 뒤 그 IP로 시작한 요청 — 효력 시각의 기록 바이트부터) · `app_inflight`(업무 URI 진행 중) |
| H3 오케스트레이션 · 사건 기록 · 계획 대 실제 | `run.sh orchestrate`/`run_step`/`do_*` → `events.jsonl` · 실패 `transition-failed.json`(첫 실패 · 원인) · `plan13.py --events-check` → `events-check.json` |
| H4 앱별 수집 | `lib13.sh app_collect`(내리기 직전 + 회차 끝 살아 있는 앱) → `app-<생애>-actuator.json` · `-gc.log.gz` · `.log.gz` · `-inspect.txt` · 1초 표본 `timeline-app-<슬롯>.jsonl` |
| H5 ApplicationName | `seatlab-<슬롯>-<sha8>-<old|shadow|new>` · 적용판 `seatlab-online-v9` · 탐침·주입 `seatlab-harness` · 락 표본 `seatlab-harness-locks` |
| H6 초기화 소유 | 초기화·시드 = 슬롯 A 옛 앱(직접 포트) · 끝 검사(`/internal/consistency`) = 마지막 새 앱(`final-app.json`) |
| H7 S3e 주입 기록 | `inject_drift13` — 차례마다 `to_regclass('counter_app_inject')`로 V10이 있을 때만 표에도 · 주 부하 회차(1)만 |
| H8 계획·meta | 회차 `plan.json`(plan13 렌더) · `images.json` · `meta.json`(SHA 3개 · 시도 · rerun) · 캠페인 `order.txt`·`plan.json`(이어서 실행 대조) |
| H9 기대 설정 | `plan13.config_check`(옛 = A·C 60초·정리 schedule · 증분 묶음 없음 · 위치 db/migration / 그림자 = §4.3 표 / 새 = K1 · 위치 3개) |
| H10 자원 | 앱마다 `APP_CPUS=4 · APP_MEM`(기본 2g ZGC) · DB 4 CPU · tmpfs 12g(DB 14g — `--pg-tmpfs-size`) · lb CPU 1 |

## 해석 메모(구현에서 정한 것 — 메인 확인 필요)

1. **J 모집단**(§5.5 'Pᵣ = 전환 + 단독(새)' · §5.4 J 행 '②③④⑤ 12 · ① 6 이상' — 코드 리뷰 CR13-1로 정정): (d, 종류)마다 전환 칸·단독(새) 칸 탐침 중 **실제 구간이 전환·단독(새)인 판정 탐침**을 같은 (d, 종류) 단독(옛) 칸 판정 탐침과 짝짓는다(O가 빠지면 함께 빠짐 — §5.8-2) · J = Σ(X 틀림 − O 틀림) ÷ X 수(§5.8-3 식을 X로 넓힘). ①은 전환 구간이 '중단'이라 단독(새) 6 + 전환 칸 중 lb 편입 뒤 판정 탐침이다. (마)의 두 수는 응답이 모두 201·409인 탐침만 센다(코드 0·5xx 섞인 탐침은 `unclean_over` — S4c 끝 검사 대조에는 그 스냅숏 > 2 몫을 더한다 · CR13-11). N(P9 (가))은 칸이 아니라 실제 구간 단독(새)으로 센다(CR13-9). (자)는 6칸을 먼저 고정하고 3회 합 짝 0인 칸 = 판정 불가(성공 아님 · CR13-2).
2. **오탐 복구(P9 (라))** = 주입·탐침 밖 쌍의 `repaired` 수(ADR-009 효과 정의 — README ADR-009 '효과 지표'). `false-positive` 기록 수는 출처별로 따로(P4 — 반증 아님).
3. **탐침 경계 민감도(§5.8-0 정정 뒤 — 칸별 첫 오프셋 단독(옛) 30 · 전환 60 · 단독(새) 120초)**: 계획 시각(사건 순간) dry-run은 경계 0이다(§4.9-20 통과).
   사건에 실제 길이를 주면(`plan13.py --probe-dry-run --startup-s <기동 → UP> --collect-s 5 --stop-s 3` · 비움은 `--drain-s` 없으면 5초 — 2026-10-24 다시 잼)
   기동 → UP **≤ 46초 → 경계 0** · 47~50초 → 2(① 두 부하 전환 칸 d75H) · 55초 → 4 · **60초 → 14**(①은 전환 칸 d75H·d75L·d30H · ②~⑤는 전환 칸 d75H — 두 부하 모두) · 70초 → 24.
   정정 전(첫 오프셋 30초)에는 기동 25초에서 ②⑤ 단독(새) 칸 d75·d30 4탐침이 모두 경계였다(N = 0 → 매 회차 구조상 절차 실패) — §5.8-0이 이 위험을 없앴다.
   남은 조건: 캠페인 시작 조건 ⑤가 **스모크 실측 사건 길이**(시작 조건 JSON `smoke_durations` — 아래 실행 순서 4)로 dry-run을 다시 돌려 0일 때만 시작한다(CR13-8 — 값이 없으면 막는다).
   ①의 전환 칸 탐침 중 lb 편입 뒤 판정은 J(전환 + 단독(새))에 든다(해석 메모 1).
4. **촘촘한 락 표본**은 한 프로세스로 [첫 창 시작, 마지막 lb 넣기 + 30초]를 덮는다(④⑤는 적용판 창 시작부터 — ADR 창의 합집합보다 넓다). 결측은 ADR 창 기준으로 센다.
5. **보정**은 두 DB를 같은 쌍 수로 만든다(load = 옛 앱 S4c 30분 · seed = `--pairs`로 같은 수) — 쌍 수가 다르면 비가 규모 차이를 잰다.
6. **한도 탐침 요청 시한**은 30초(주 부하 `common.js`와 같다 — ADR에 값 없음). 실패 응답 탐침은 판정에서 빠진다(다운타임 자료).
7. S3e 단독(옛) 기준 창은 5xx·502만 본다(정지 초는 좌석 소진 구간이라 쓰지 않는다 — DS-6).
