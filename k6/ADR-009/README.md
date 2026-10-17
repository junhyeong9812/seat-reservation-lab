# k6/ADR-009 — 카운터 어긋남 후보(A·B·C·F)·판매 마감·쿼터 행 정리 측정 하네스

ADR-008 하네스(`k6/ADR-008/`)를 복사하고 **조건 축 `--cond`**, **정리 축 `--cleanup`**, 셀 **S3e(효과 — 어긋남 주입)**·**S3c(마감 임박)**,
카운터 어긋남 수집(V7 기록 테이블·앱 지표)과 조건 인지 회차 합격 판정을 더했다.
명세: `docs/plans/2026-10-12/adr-009-counter-drift/requirement-spec.md` §5·§10·§11 · 설계: 같은 폴더 `design-v2.md` §9(하네스)·§10(매트릭스).
시나리오·판정기·수집·검증 규칙은 ADR-005~008과 같다 — `k6/ADR-008/README.md`(합격 사유)·`k6/ADR-007/README.md`(셀·DB 설정·벽 분석) 참고.

## 구성

| 파일 | 역할 |
|------|------|
| `compose.yml` | ADR-008 compose + 조건 키를 **조건이 켤 때만** 넣는 `${VAR:+…}` 조각(머리말) · 앱 커넥션 `ApplicationName=seatlab-app` |
| `compose.reconciler.yml` | F 오버레이 — 같은 이미지, 프로필 `loadtest,reconciler`, 포트 8102, CPU 1, `ApplicationName=seatlab-reconciler`, JSON은 대조 주기·풀·Flyway만 |
| `compose.tmpfs.yml` | ADR-007 그대로(데이터 디렉터리 tmpfs · DB 메모리 8g) |
| `scripts/run.sh` | 조건 하나 실행기(ADR-008 + 아래 '바뀐 점') |
| `scripts/campaign.sh` | 확정 측정 캠페인 84회(아래) · `--dry-run` = 계획·순서·예상 시간만 |
| `scripts/lib.sh` | ADR-008 + F 오버레이 내리기 · 대기 표본 출처 구분(`by_src`) · I/O 스냅숏 MultiXact(`pg_stat_slru`·`mxid_age`)·쿼터 테이블 통계 · 주입(`inject_drift`)·끝 쌍(`end_pairs`)·V7 덤프·마감 임박 셀 함수 |
| `scripts/actsnap.py` | **새로 씀** — 앱(·F) 지표 스냅숏(틱·어긋남·대조·정리·마감 판정·Hikari) |
| `scripts/summarize.py` | ADR-008 + 조건 인지 `--accept-check <회차> <셀> <조건> <정리>` · `--effect` · `--runs-in-window` · 빌림 식 · MultiXact·출처별 대기·효과·마감 표 |
| `scripts/cfgkeys.py`·`wall.py`·`wdrop.py`·`gapcheck.py`·`host-sampler.sh` | ADR-008 그대로 |
| `scenarios/` | ADR-008 S2·S3·S4·예열 + `hold_closed` 계수(모든 셀) · S3c의 시각 붙은 선점·확정 코드 행(`CLOSE_AT_MS`) |

## 조건 축(`--cond`) · 정리 축(`--cleanup`)

ADR-009 결정(명세 §12 — task 07a)으로 앱 기본값이 A 켬 · C 켬 · 정리 ① schedule이 됐다. 그래서 `default` 밖의 조건은 세 키
(`counter-drift.expiry-repair` · `counter-drift.reconcile.enabled` · `counter-drift.cleanup.mode`)를 **늘 명시**한다 — 아래 표에 없는 쪽은 끔(`false` · `false` · `--cleanup` 값, 기본 `none`)으로 넣어
전환 전과 같은 실효 설정으로 뜬다(렌더 대조: 전환 전·후 조건별 실효 값 동일).

| 조건 | 업무 앱에 넣는 키(세 키 밖은 키 없음 = 앱 기본값) | 그 밖 |
|------|------|------|
| `control` | 세 키 끔 (후보 끔 · 판매 마감 켬 — ADR-008 대조군과 같은 실효 설정) | |
| `control-noclose` | `sales-close.enabled=false` | 마감 판정 비용 분리(명세 §10 D2) |
| `A` | `counter-drift.expiry-repair=true` | |
| `A+C` | A + `counter-drift.reconcile.enabled=true` · `interval=60s` | 앱 풀 공유 |
| `A+F` | A + `counter-drift.external-reconciler=true` | `compose.reconciler.yml`(대조 60s) |
| `B` | `limit-strategy=counter-trigger` | `spring.flyway.locations += classpath:db/migration-trigger`(V8) |
| `B+C` | B + reconcile 60s(A 없이 — 명세 §11) | 효과 셀만 |
| `default` | **없음** — 앱 기본값(A 켬 · C 켬 60s · 정리 ① schedule · 마감 켬 · counter-upsert-precheck). `--cleanup`은 받지 않는다 | 기본값 스모크(task 07a). 키가 없다는 증거 = `limit-key.json` 앱 `drift`·`drift_cmd` 0(아니면 `drift-key-present`) · 효과 셀 기대 = A+C |

- `--cleanup none|schedule|zero` → `counter-drift.cleanup.mode`(마감 임박 셀 S3c). `default` 밖에서는 `none`도 키로 넣는다.
- 한계: `default` 조건의 키 없음 확인(`drift`·`drift_cmd`)은 앱 컨테이너 **환경변수·명령줄만** 본다 — 프로필 yml(`application-*.yml`)이 세 키를 바꾸는 경우는 configprops 대조(`cond-config-mismatch` — 기대 = A 켬 · C 켬 · SCHEDULE)가 잡는다.
- 매수 방식 키는 **B·B+C에서만** 넣는다. 그 밖은 ADR-008처럼 넣지 않고 기본값(`counter-upsert-precheck`)을 configprops로 대조한다.
  `limit-key.json`의 기대 개수가 조건마다 다르다(앱 환경 등장 수 B = 1 · 나머지 0 · F 컨테이너 늘 0).
- 적용 확인(회차마다): configprops `seat.hold.counterDrift`·`salesClose` = 조건 기대(`cond-config-mismatch`) · F 컨테이너 configprops(`reconciler-config-mismatch`) ·
  `pg_trigger` 두 이름 ⇔ B(`trigger-mismatch`).
  F 컨테이너는 `application-reconciler.yml`이 A·정리를 끈다(대조 전용 — `reconciler-config-mismatch`가 A 끔·정리 NONE도 본다).

## 셀

| 셀 | 시나리오 | ADR-009에서 더한 것 |
|----|----------|------|
| `S4c4200` | S4 고정 도착률 4,200/s(디스크 · `--pg-preset tmpfs`) | — |
| `S2` | 사용자 100명 × 좌석 10개 동시 | — |
| `S3-a20` | 입장→선점→확정, 이탈 20% | — |
| `S3e-a20` | S3-a20 + 어긋남 주입 | 라운드 계획 `INJECT_ROUNDS`(기본 270·600초 — control은 둘째 라운드 L2·H만)마다 한 트랜잭션 주입 → `injected.json` · 끝 쌍 상태 `end-pairs.json` · `effect.json` |
| `S3c-a20` | S3-a20 흐름 | k6 바로 전에 회차 1 `starts_at` = 지금 + 601초(`close.json`) · 마감 5초 전 표본 `pre-close.json` · 끝 정리 상태 `close-end.json`. 마감 뒤 확정 성공은 실제로 생긴다 — 실측: 스모크 S3c zero(b3351b59) 마감 뒤 보낸 확정 101건 전부 OK · S3c schedule(728c236c 작업트리) 140건 전부 OK — N-1(마감 뒤 확정 성공이 생길 수 없다는 정적 추론) 기각 근거 |

### 주입(설계 §9 — `lib.sh inject_drift`)

| 종류 | 대상(열린 회차·cnt 1·종류끼리·이전 주입과 겹치지 않음, 결정적 순서) | 회당 | 드러나는 경로 |
|------|------|------|------|
| D 쿼터 행 삭제 | 반드시 만료될 홀드: `held_at <= now − 250s`(THINK_MAX 240 + 여유) · `expires_at >= now + 15s` | 10 | 만료 감소 행 없음(정지) |
| L1 cnt − 1 | 같은 '만료될' 쌍 | 5 | 만료 감소 CHECK 23514(정지) |
| L2 cnt − 1 | CONFIRMED 1 · 홀드 0 쌍 | 5 | 조용함(대조만) |
| H cnt + 1 | 아무 cnt 1 쌍 | 10 | 조용함 |

- **주입 시각은 270·600초**다. 설계 §9 v1의 '+420초'에서는 '만료될 홀드' 후보가 0이다: S3-a20은 첫 약 40초에 좌석 10,000석이 다 잡히고,
  다음 선점은 이탈 홀드가 만료되는 약 300~350초에야 생긴다. 그래서 +420초에 나이 250~285초인 홀드가 없다(첫 물결은 +250~325초, 둘째 물결은 +560~635초에 후보가 있다).
- **control의 둘째 라운드(+600초)는 D·L1을 뺀다(H-1)**: 첫 라운드의 D·L1이 만료를 멈춰 둘째 물결 선점이 생기지 않으므로 '만료될' 후보가 0이다. 라운드별 계획은 plan.json `knobs.INJECT_ROUNDS`·injected.json `plan.rounds`.
- 고른 쌍은 (회차, 사용자) 순서로 `FOR UPDATE` 잠근 뒤 조건을 다시 보고(바뀐 쌍은 `dropped_after_lock`), 종류별 영향 행 수(`applied`)를 기록 수와 대조한다(H-7 — `inject-mismatch`).
- 주입·마감 손잡이(`INJECT_ROUNDS`·`INJECT_HELD_MIN_S`·`INJECT_EXP_MARGIN_S`·`CLOSE_LEAD_S`)는 plan.json `knobs`에 남고, 이어서 실행 때 다른 값이면 거부한다(H-15).
- 주입 쌍은 `injected.json`(종류·회차·사용자·주입 시각 — DB `clock_timestamp()`).
- 해석 주의(H-18): 첫 라운드의 D·L1은 보통 A(expiry-repair)가 대조보다 먼저 고친다 — C·F의 탐지 시간 비교는 L2·H로 한다. control의 DB_URL에는 ADR-008과 달리 `ApplicationName=seatlab-app`이 붙는다. `by_src`는 표본 순간 문장 기준 근사다.

### 효과 지표(`effect.json` — `summarize.py --effect`)

- 쌍마다: 주입 뒤 첫 기록(탐지)·첫 `repaired`(복구)까지 초, 출처, 끝 상태(cnt = 실제?). 종류별 요약.
- 오탐 복구 = 주입 밖 쌍의 `repaired`(기대 0 — 결정 기준 ②) · `false-positive` 수.
- skipped는 **실패(reason ≠ closed)와 마감 제외(reason = closed — L3-4)를 나눈다**. 바퀴 기록 `skipped`·`skipped_closed`도 따로.
- 만료 정지: 틱 `seat.hold.expiry.tick{outcome=failed}` 증가(k6 직전 → 회차 끝) · 끝 `v_stale_held_seats`.
- 대조: `counter_reconcile_run` 출처별 바퀴 수·바퀴 초 p50/max·청크 최대 ms·빌림·오류.
- 조건별 기대(`unexpected`가 비어야 한다):
  - control: 틱 실패 > 0(주입이 정지 경로에 닿았다) · 주입 쌍 기록 0
  - control: + 틱 실패 원인(app.log 스케줄 예외)이 주입 원인(missing-row·23514)뿐
  - A: D·L1 전부 `expiry-repair`가 복구 · 끝 D·L1 일치 · 틱 실패 0 · L2·H 복구 0(A는 못 본다)
  - A+C·A+F: 60쌍 전부 복구 · 주입 쌍마다 끝 cnt = 실제 · 복구 출처 ⊆ {expiry-repair, 그 대조} · L2·H는 대조만 · 틱 실패 0
  - B+C: 60쌍 전부 복구 · 끝 일치 · 틱 실패는 일대일로만 허용(H-10·N-3): 실패 틱마다 app.log 스케줄 예외(시각)가 있고 그 ±1초 안에 주입 쌍의 expiry-trigger skipped(SR001·23514, 주입 → 복구 구간 안)가 있어야 한다. 설명 안 되는 틱 → unexpected, 로그 예외 수 ≠ 틱 실패 수 또는 로그를 못 읽음 → effect-check-failed
  - A·A+C·A+F·B+C 공통(H-3): 틱 성공 > 0 · 끝 오래된 HELD 0. 주입 쌍의 끝 상태가 빠지면 effect-check-failed(H-6)
  - 오탐 복구에는 주입 쌍이 주입 **전에** repaired된 것도 넣는다(H-17)
- **control·A의 남은 어긋남과 만료 정지는 결과이지 하네스 실패가 아니다** — 효과 셀의 `counter-mismatch`는 판정기 값이 아니라 **주입 밖 쌍**의 끝 어긋남으로 판정한다.

## 시작 상태 — k6 전 대기(명세 §13 교란 수정 + 리뷰 H1~H5·R1~R10)

용어(기준점): **시드 끝** = `/internal/reset`(시드 + ANALYZE)이 반환한 시각(k6 PC 시계) · **DB 기동** = `pg_postmaster_start_time()`(회차마다 컨테이너·볼륨을 새로 띄운다) ·
**k6 시작** = k6 프로세스를 띄운 시각(`k6_start_epoch`) · **첫 요청** = k6 첫 반복 시각(S4c `setup_data.startedAt` ms, 그 밖 CSV 1초 분해능). ADR 문서 §7.6의 '시작 대기'는 시드 끝 기준이다.

- **교란**: 옛 하네스는 대조 조건(A+C·A+F)만 시드 뒤 첫 대조 바퀴를 기다렸다(`wait_reconcile_run` — 실측 30~43초). 시드 끝 → k6 시작이 대조 없는 조건 16~18초 · 대조 조건 43~58초로 달라
  k6 시작이 DB autovacuum과 다르게 겹쳤고, tmpfs의 control·A·B에만 k6 26~36초 '램프 붕괴'(p99 최대 약 4.2초)가 났다(캠페인 20261013-adr009-285ba256 실측).
- **선택 = 고정 대기(fixed-after-seed)**: 모든 조건이 시드 끝 뒤 대기점 `PRE_K6_HOLD_S`(70초)까지 기다리고(대조 조건은 첫 바퀴를 본 뒤 남은 만큼), 준비 단계를 거쳐
  시드 끝 → **첫 요청**이 `PRE_K6_TARGET_S`(87초)가 되게 맞춘다(`PRE_K6_LEAD_S` 1.2초 = 맞춤 sleep 뒤 첫 요청까지). 판정 `pre-k6-mismatch` = |시드 끝 → 첫 요청 − 87| > `PRE_K6_TOL_S`(5초).
  대기점에서 첫 바퀴를 아직 못 봤거나, 준비를 마친 시각이 이미 목표 + 허용을 넘으면 **k6 전에 멈춘다**(`pre-k6-overrun` — 하네스 실패, 재측정 대상. `reconcile-missing`(결과 사유)로 남기지 않는다).
  네 값은 plan.json `knobs`·`start_state`(이어서 실행 때 대조).
- **명세 §13과 다른 점(사유)**: ① 목표 77 → **87초**(리뷰 H1·R5 — 첫 바퀴 대기의 이론 최대 = 주기 60초 + 바퀴 시간 + 폴링 3초를 대기점 70초가 덮게. 77초 대기점 60초로는 첫 바퀴가 늦은 회차가 넘친다) ·
  ② GC 로그 `gc` → **`gc*,safepoint`**(짧은 벽 원인 확인에는 정지 시간 = safepoint가 필요하다 — Serial은 Young·Full이 한 정지로 묶인다).
- **한계(리뷰 H2)**: 시드 끝 → k6와 첫 대조 바퀴 → k6는 **함께 맞출 수 없다**(바퀴는 앱 기동 + 주기에 묶인다). 시드 끝 기준으로 맞추고, 바퀴 위상(시드 끝 ~ k6 끝 바퀴의 k6_t0 기준 시작·끝 ·
  k6 전 완료 수 · k6 구간 첫 바퀴)은 `pre-k6.json reconcile_phase`·meta에 기록만 한다. 비교는 S4c p99를 **램프(0~60초)·램프 밖(60~420초)으로 나눠**(summarize fixed.p99_ramp/steady_nz_ms) 본다.
- **대기 중 DB 상태(리뷰 R2)**: 시드 끝·k6 직전에 `db-state-seed.json`·`db-state-k6.json`(DB 기동 시각 · product_seat·seat_hold·reservation·user_hold_quota의 autovacuum·autoanalyze·vacuum·analyze 수와 last_* ·
  n_dead_tup·n_ins_since_vacuum · 도는 autovacuum worker · 체크포인트 수 — PG16 pg_stat_bgwriter). 차와 DB 기동 → k6 시작은 `pre-k6.json db_during_wait`·`db_start_to_k6_s`.
- **ANALYZE는 더하지 않았다**: 시드 직후 ANALYZE는 원래 있다(`/internal/reset` → `LoadtestDataService.analyze`, 모든 조건 공통). 교란 원인은 시점 차라 VACUUM 같은 상태 변경도 넣지 않았다.
- **예열 약화(리뷰 R9)**: 예열(warmup 20초) 뒤 k6까지 약 90초 이상 걸려 Tomcat이 유휴 스레드를 내린다(기본 유휴 60초) — 예열 효과가 줄지만 **모든 조건에 같다**.
- **GC 로그(리뷰 H3·R3·R4·R7)**: `-Xlog:async -Xlog:gc*,safepoint:file=/dev/shm/gc.log:time,uptime,level,tags:filecount=0`(비동기 쓰기 · 컨테이너 메모리 파일 · 회전 끔 — F도 같다).
  회차 끝 `gc.log.gz`·`reconciler-gc.log.gz`, 요약 `gc.json` — **정지 = GC VM 작업 safepoint 한 번**(Serial은 Young이 승격 실패로 같은 safepoint 안에서 Full로 이어지면 GC id 둘이 한 정지 —
  실측 smoke3 control: GC id 2,019 · 정지 2,009 · 묶인 정지 10, 최대 정지 108.7ms(GC 끝 줄 기준 103.8ms)). 로그 양 `log_bytes`·`lines`. 수집기는 고르지 않는다(기본 선택 Serial — 기록만).
  F면 reconciler 수집기 줄도 있어야 한다(`gc-log-missing`).

## 회차 합격(정확히 `ok`만 정상) — ADR-008 사유 + 아래

| 사유 | 조건 | 셀 |
|------|------|----|
| `borrow-not-one`·`borrow-missing` | 빌림 = (Δ획득 − Δ`reconcile.borrows` − Δ`cleanup.borrows`) ÷ (Δ선점 + Δ확정)이 [0.98, 1.02] 밖 · 계산 불가(설계 §4d). F는 별도 풀이라 앱 수치 밖(F 빌림은 기록만). 대조·정리 빌림은 획득 앞뒤로 두 번 읽어 같을 때만 쓴다(H-11). redis-nx '진 것' 경로는 측정 범위 밖 | 전부 |
| `limit-key-present`·`limit-key-missing`·`limit-key-check-failed` | 앱 환경 매수 방식 키 등장 수 ≠ 조건 기대(B·B+C = 1, 그 밖 0) · F 컨테이너 > 0 · 파일 없음/컨테이너 수 다름 | 전부 |
| `cond-config-mismatch`·`reconciler-config-mismatch` | configprops가 조건 기대와 다름 | 전부 |
| `trigger-mismatch`·`trigger-check-failed` | `pg_trigger` 두 이름 ⇔ B·B+C | 전부 |
| `counter-judge-vacuous`·`counter-excluded-missing` | 판정기 제외 회차 ≥ 시드 회차 수(부하 대상 회차가 전부 판정에서 빠짐 — 명세 §11 N3) · 항목 없음 | S3c 밖 |
| `schedule-closed-seen` | 선점 409 본문 `SCHEDULE_CLOSED` > 0(k6 `hold_closed`) — 시드 회차 2026-12-31 19:00+09 시한폭탄 | S3c 밖 |
| `reconcile-missing`·`reconcile-errors` | C·F인데 시드 **뒤에 시작한** 첫 바퀴가 없음(H-14) 또는 (S2 밖) k6 구간에서 바퀴 결손 — 시작 간격 > 주기 + 앞 바퀴 실행 시간 + 10초(fixedDelay — N-2, 앞·끝단 포함) · 바퀴 기록 errors·skipped_chunks > 0 또는 지표 reconcile.errors 증가 > 0(H-13). reconcile-missing도 결과 사유(재측정 안 함 — H-16) | C·F |
| `pre-k6-mismatch`·`pre-k6-overrun`·`gc-log-missing` | 시드 끝 → 첫 요청이 87 ± 5초 밖(명세 §13 · H5) · k6 전 맞춤 실패로 멈춤(H1 — 재측정 대상) · GC 로그 수집 실패·0바이트·수집기 줄 없음(F는 reconciler도) | 전부 |
| `drift-snap-failed`·`drift-db-failed`·`drift-db-incomplete`·`drift-record-failed`·`cleanup-errors`·`container-log-missing` | 지표 스냅숏·V7 덤프 실패 · counter_drift_log를 페이지로 모았는데 총수보다 적음(H-4·N-5 — 수집 실패, 재측정 대상) · `drift.record_failed` > 0 · 정리 errors > 0 · docker logs 파이프 실패·빈 로그(H-12) | 전부 |
| `inject-failed`·`inject-short`·`inject-mismatch`·`effect-check-failed`·`effect-unexpected`·`false-repair` | 주입 실패 · 라운드 계획보다 적게 고름(조용한 절단 금지) · 영향 행 수 ≠ 기록 수 · effect.json 없음 · 조건별 기대와 다름 · 주입 밖 repaired > 0 | S3e |
| `close-leak`·`close-wrong-code`·`close-no-hold-before` | 마감 뒤 보낸 선점(PC 시각 + 시계 차 > starts_at + 오차)이 201 · `SCHEDULE_CLOSED` 밖 코드(H-2) · 마감 전 330초 안 성공 선점 표본 0(H-2 — 시나리오가 S3c 성공 선점을 모두 남긴다. 약 350~610초에는 빈 좌석이 없어 마감 직전 30초에는 성공이 없다) | S3c |
| `confirm-after-close-failed` · `close-tick-failed` | 마감 뒤 확정이 `OK`·`HOLD_EXPIRED` 밖 · 틱 실패 증가 > 0(H-9 — 주입 없는 셀) | S3c |
| `cleanup1-early`·`cleanup1-incomplete`·`cleanup2-left` | 정리 기록 holds_seen > 0인데 지움 · (schedule, H-9) 끝 홀드 ≠ 0 · 홀드 0 마감 회차 쿼터 행 > 0 · rows_deleted > 0 기록 없음 · (zero) 끝 cnt 0·실제 0 행 > 0 · 지운 기록 없음 | S3c |
| `pre-close-mismatch` | 마감 5초 전 표본(회차 1 — 판정기 v2와 같은 정의, 한 문장) 어긋남 > 0 | S3c |
| `close-no-hold-after`·`close-no-confirm-after`·`close-check-failed`·`close-not-applied` | 판정 기회 없음(마감 뒤 선점 0 · 마감 뒤 확정 성공 0 — H-5) · 입력 없음·표본이 starts_at − 5초 ± (2초 + 시계 오차) 밖(H-8) · starts_at을 못 넣음 | S3c |

- V7 기록 테이블은 시드 초기화가 비우지 않는다. 회차마다 DB 볼륨을 새로 만들므로 그 회차의 행뿐이고, 요약기는 k6 구간(서버 시계 = PC + 시계 차)으로 다시 거른다.
- 제외 쌍의 두 정의(L2-9): 판정기 `counter_excluded_pairs` = 마감 회차의 (쿼터 행 ∪ 실제 값 쌍) · 대조 바퀴 `skipped_pairs` = 마감 회차의 쿼터 행 수. 표에서 이름을 나눠 적는다.
- S2는 k6 구간이 몇 초라 C·F 바퀴가 구간에 거의 겹치지 않는다(H-14 — S2의 C·F 비용 비교 한계, ADR 기록).
- `reconcile.excluded{unit=pairs}` 지표는 바퀴마다 skipped_pairs를 더하고 마감 제외 skipped도 1씩 더하는 누적 계수다 — 마감 제외 쌍 수는 DB 기록으로 센다.

## 비용 지표(L1-16·L2-11 · IV-009-7)

- WAL: `pg_stat_wal` 차분(바이트·레코드·FPI) — ADR-006 io 스냅숏.
- MultiXact: `pg_stat_slru`(MultiXactOffset·Member) 차분 · `mxid_age(datminmxid)` 차분(datminmxid가 같을 때 = 새 MultiXact ID 수).
  스모크 control S2: 선점 1,000건에 새 MultiXact ID 993개(마감 판정 `FOR SHARE` + FK KEY SHARE).
- 대기: `timeline-waits.jsonl` `by_src` — F = `application_name`, **C는 앱 풀을 같이 쓰므로 application_name으로는 가를 수 없어 문장 표지로 가른다**(청크 `cnt <> actual` · 행 없음 `NOT EXISTS (… user_hold_quota q …)` · 바퀴 기록 · 복구 `lock_timeout`). 표본 순간의 문장 기준이라 근사다.
- 마감 판정: 앱 타이머 `seat.hold.sales-close.check`(k6 구간 건수·평균).

## 캠페인(설계 §10 · 명세 §11 측정 조건 갱신)

- **`--set cost-remeasure`(명세 §13)**: {control, A, A+C, A+F} × {S4c4200 디스크 · S4c4200 tmpfs · S2 · S3-a20} × 3 = 48회(예상 약 9.4시간 — 시작 대기 87초 반영).
  순서는 회차 우선 + 균형 회전(묶음 4개·묶음 안 조건 4개를 회차마다 1칸씩). 판정은 이 캠페인 회차끼리만(옛 캠페인과 섞지 않는다). `--set main`(기본)의 conditions.txt·order.txt는 명세 §13 전과 바이트가 같다(dry-run 확인) —
  그 예상분 열은 옛 대기 기준이라, 시작 대기를 반영한 보정 예상은 dry-run 끝 줄에 따로 낸다.

- 비용 {control, A, B, A+C, A+F} × {S4c4200 디스크 · S4c4200 tmpfs · S2 · S3-a20} × 3 = 60 · 마감 끔 control-noclose × S4c4200 디스크 × 3 = 3 ·
  효과 {control, A, A+C, A+F, B+C} × S3e-a20 × 3 = 15 · 마감 임박 S3c-a20 × {schedule, zero} × 3 = 6 → **84회, 예상 약 19.2시간**(재측정 제외 — `--dry-run`이 셈).
- 순서: 회차 우선 + 균형 회전(campaign.sh 머리말). 재측정은 ADR-008 규칙 + ADR-009 결과 사유(ACCEPT_RE)·효과 셀은 effect.json 위반으로.
- 시한: run.sh가 2026-12-31T09:00Z부터 회차를 거부한다(`seed-time-bomb`).

```bash
k6/ADR-009/scripts/campaign.sh --sha <SHA> --id <id> --dry-run          # 계획만(84행 · 예상 시간)
k6/ADR-009/scripts/campaign.sh --sha <SHA> [--id <id>]                  # 확정 측정(하네스 = 커밋된 SHA)
k6/ADR-009/scripts/campaign.sh --set cost-remeasure --sha <SHA> [--id <id>] [--dry-run]   # 명세 §13 비용 셀 재측정 48회
k6/ADR-009/scripts/run.sh --sha <SHA> --cond A+F --cells S2 --reps 1 --id <id>   # 조건 하나(스모크 — 커밋 전이면 --worktree)
python3 k6/ADR-009/scripts/summarize.py k6/ADR-009/results/<id>/<조건>
```
