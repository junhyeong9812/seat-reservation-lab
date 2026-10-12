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

| 조건 | 업무 앱에 넣는 키(그 밖은 키 없음 = 앱 기본값) | 그 밖 |
|------|------|------|
| `control` | — (후보 끔 · 판매 마감 켬 — ADR-008 대조군과 같은 JSON) | |
| `control-noclose` | `sales-close.enabled=false` | 마감 판정 비용 분리(명세 §10 D2) |
| `A` | `counter-drift.expiry-repair=true` | |
| `A+C` | A + `counter-drift.reconcile.enabled=true` · `interval=60s` | 앱 풀 공유 |
| `A+F` | A + `counter-drift.external-reconciler=true` | `compose.reconciler.yml`(대조 60s) |
| `B` | `limit-strategy=counter-trigger` | `spring.flyway.locations += classpath:db/migration-trigger`(V8) |
| `B+C` | B + reconcile 60s(A 없이 — 명세 §11) | 효과 셀만 |

- `--cleanup none|schedule|zero` → `counter-drift.cleanup.mode`(마감 임박 셀 S3c).
- 매수 방식 키는 **B·B+C에서만** 넣는다. 그 밖은 ADR-008처럼 넣지 않고 기본값(`counter-upsert-precheck`)을 configprops로 대조한다.
  `limit-key.json`의 기대 개수가 조건마다 다르다(앱 환경 등장 수 B = 1 · 나머지 0 · F 컨테이너 늘 0).
- 적용 확인(회차마다): configprops `seat.hold.counterDrift`·`salesClose` = 조건 기대(`cond-config-mismatch`) · F 컨테이너 configprops(`reconciler-config-mismatch`) ·
  `pg_trigger` 두 이름 ⇔ B(`trigger-mismatch`).

## 셀

| 셀 | 시나리오 | ADR-009에서 더한 것 |
|----|----------|------|
| `S4c4200` | S4 고정 도착률 4,200/s(디스크 · `--pg-preset tmpfs`) | — |
| `S2` | 사용자 100명 × 좌석 10개 동시 | — |
| `S3-a20` | 입장→선점→확정, 이탈 20% | — |
| `S3e-a20` | S3-a20 + 어긋남 주입 | k6 시작 + `INJECT_AT`(기본 270·600초)마다 한 트랜잭션 주입 → `injected.json` · 끝 쌍 상태 `end-pairs.json` · `effect.json` |
| `S3c-a20` | S3-a20 흐름 | k6 바로 전에 회차 1 `starts_at` = 지금 + 601초(`close.json`) · 마감 5초 전 표본 `pre-close.json` · 끝 정리 상태 `close-end.json` |

### 주입(설계 §9 — `lib.sh inject_drift`)

| 종류 | 대상(열린 회차·cnt 1·종류끼리·이전 주입과 겹치지 않음, 결정적 순서) | 회당 | 드러나는 경로 |
|------|------|------|------|
| D 쿼터 행 삭제 | 반드시 만료될 홀드: `held_at <= now − 250s`(THINK_MAX 240 + 여유) · `expires_at >= now + 15s` | 10 | 만료 감소 행 없음(정지) |
| L1 cnt − 1 | 같은 '만료될' 쌍 | 5 | 만료 감소 CHECK 23514(정지) |
| L2 cnt − 1 | CONFIRMED 1 · 홀드 0 쌍 | 5 | 조용함(대조만) |
| H cnt + 1 | 아무 cnt 1 쌍 | 10 | 조용함 |

- **주입 시각은 270·600초**다. 설계 §9 v1의 '+420초'에서는 '만료될 홀드' 후보가 0이다: S3-a20은 첫 약 40초에 좌석 10,000석이 다 잡히고,
  다음 선점은 이탈 홀드가 만료되는 약 300~350초에야 생긴다. 그래서 +420초에 나이 250~285초인 홀드가 없다(첫 물결은 +250~325초, 둘째 물결은 +560~635초에 후보가 있다).
- 주입 쌍은 `injected.json`(종류·회차·사용자·주입 시각 — DB `clock_timestamp()`).

### 효과 지표(`effect.json` — `summarize.py --effect`)

- 쌍마다: 주입 뒤 첫 기록(탐지)·첫 `repaired`(복구)까지 초, 출처, 끝 상태(cnt = 실제?). 종류별 요약.
- 오탐 복구 = 주입 밖 쌍의 `repaired`(기대 0 — 결정 기준 ②) · `false-positive` 수.
- skipped는 **실패(reason ≠ closed)와 마감 제외(reason = closed — L3-4)를 나눈다**. 바퀴 기록 `skipped`·`skipped_closed`도 따로.
- 만료 정지: 틱 `seat.hold.expiry.tick{outcome=failed}` 증가(k6 직전 → 회차 끝) · 끝 `v_stale_held_seats`.
- 대조: `counter_reconcile_run` 출처별 바퀴 수·바퀴 초 p50/max·청크 최대 ms·빌림·오류.
- 조건별 기대(`unexpected`가 비어야 한다):
  - control: 틱 실패 > 0(주입이 정지 경로에 닿았다) · 주입 쌍 기록 0
  - A: D·L1 전부 `expiry-repair`가 복구 · 틱 실패 0 · L2·H 복구 0(A는 못 본다)
  - A+C·A+F: 60쌍 전부 복구 · 끝 일치 · L2·H 복구 출처 = reconcile-app / reconcile-proc · 틱 실패 0
  - B+C: 60쌍 전부 복구 · 끝 일치 · 틱 실패는 기록만(명세 §11 B 예외)
- **control·A의 남은 어긋남과 만료 정지는 결과이지 하네스 실패가 아니다** — 효과 셀의 `counter-mismatch`는 판정기 값이 아니라 **주입 밖 쌍**의 끝 어긋남으로 판정한다.

## 회차 합격(정확히 `ok`만 정상) — ADR-008 사유 + 아래

| 사유 | 조건 | 셀 |
|------|------|----|
| `borrow-not-one`·`borrow-missing` | 빌림 = (Δ획득 − Δ`reconcile.borrows` − Δ`cleanup.borrows`) ÷ (Δ선점 + Δ확정)이 [0.98, 1.02] 밖 · 계산 불가(설계 §4d). F는 별도 풀이라 앱 수치 밖(F 빌림은 기록만). redis-nx '진 것' 경로는 측정 범위 밖 | 전부 |
| `limit-key-present`·`limit-key-missing`·`limit-key-check-failed` | 앱 환경 매수 방식 키 등장 수 ≠ 조건 기대(B·B+C = 1, 그 밖 0) · F 컨테이너 > 0 · 파일 없음/컨테이너 수 다름 | 전부 |
| `cond-config-mismatch`·`reconciler-config-mismatch` | configprops가 조건 기대와 다름 | 전부 |
| `trigger-mismatch`·`trigger-check-failed` | `pg_trigger` 두 이름 ⇔ B·B+C | 전부 |
| `counter-judge-vacuous`·`counter-excluded-missing` | 판정기 제외 회차 ≥ 시드 회차 수(부하 대상 회차가 전부 판정에서 빠짐 — 명세 §11 N3) · 항목 없음 | S3c 밖 |
| `schedule-closed-seen` | 선점 409 본문 `SCHEDULE_CLOSED` > 0(k6 `hold_closed`) — 시드 회차 2026-12-31 19:00+09 시한폭탄 | S3c 밖 |
| `reconcile-missing`·`reconcile-errors` | C·F인데 시드 뒤 첫 바퀴가 없음 또는 (S2 밖) k6 구간에 그 출처 바퀴 없음 · 바퀴 기록 errors·skipped_chunks > 0 | C·F |
| `drift-snap-failed`·`drift-db-failed`·`drift-record-failed`·`cleanup-errors` | 지표 스냅숏·V7 덤프 실패 · `drift.record_failed` > 0 · 정리 errors > 0 | 전부 |
| `inject-failed`·`inject-short`·`effect-check-failed`·`effect-unexpected`·`false-repair` | 주입 실패 · 계획보다 적게 고름(조용한 절단 금지) · effect.json 없음 · 조건별 기대와 다름 · 주입 밖 repaired > 0 | S3e |
| `close-leak` | 마감 뒤 보낸 선점(PC 시각 + 시계 차 > starts_at + 오차)이 201 | S3c |
| `confirm-after-close-failed` | 마감 뒤 확정이 `OK`·`HOLD_EXPIRED` 밖 | S3c |
| `cleanup1-early`·`cleanup1-incomplete`·`cleanup2-left` | 정리 기록 holds_seen > 0인데 지움 · (schedule) 끝에 홀드 0 마감 회차 쿼터 행 > 0 · (zero) 끝에 cnt 0·실제 0 행 > 0 | S3c |
| `pre-close-mismatch` | 마감 5초 전 표본(회차 1 — 판정기 v2와 같은 정의, 한 문장) 어긋남 > 0 | S3c |
| `close-no-hold-after`·`close-no-confirm-after`·`close-check-failed`·`close-not-applied` | 판정 기회 없음(마감 뒤 선점·확정 0) · 입력 없음·표본이 마감 뒤 · starts_at을 못 넣음 | S3c |

- V7 기록 테이블은 시드 초기화가 비우지 않는다. 회차마다 DB 볼륨을 새로 만들므로 그 회차의 행뿐이고, 요약기는 k6 구간(서버 시계 = PC + 시계 차)으로 다시 거른다.
- 제외 쌍의 두 정의(L2-9): 판정기 `counter_excluded_pairs` = 마감 회차의 (쿼터 행 ∪ 실제 값 쌍) · 대조 바퀴 `skipped_pairs` = 마감 회차의 쿼터 행 수. 표에서 이름을 나눠 적는다.
- `reconcile.excluded{unit=pairs}` 지표는 바퀴마다 skipped_pairs를 더하고 마감 제외 skipped도 1씩 더하는 누적 계수다 — 마감 제외 쌍 수는 DB 기록으로 센다.

## 비용 지표(L1-16·L2-11 · IV-009-7)

- WAL: `pg_stat_wal` 차분(바이트·레코드·FPI) — ADR-006 io 스냅숏.
- MultiXact: `pg_stat_slru`(MultiXactOffset·Member) 차분 · `mxid_age(datminmxid)` 차분(datminmxid가 같을 때 = 새 MultiXact ID 수).
  스모크 control S2: 선점 1,000건에 새 MultiXact ID 993개(마감 판정 `FOR SHARE` + FK KEY SHARE).
- 대기: `timeline-waits.jsonl` `by_src` — F = `application_name`, **C는 앱 풀을 같이 쓰므로 application_name으로는 가를 수 없어 문장 표지로 가른다**(청크 `cnt <> actual` · 행 없음 `NOT EXISTS (… user_hold_quota q …)` · 바퀴 기록 · 복구 `lock_timeout`). 표본 순간의 문장 기준이라 근사다.
- 마감 판정: 앱 타이머 `seat.hold.sales-close.check`(k6 구간 건수·평균).

## 캠페인(설계 §10 · 명세 §11 측정 조건 갱신)

- 비용 {control, A, B, A+C, A+F} × {S4c4200 디스크 · S4c4200 tmpfs · S2 · S3-a20} × 3 = 60 · 마감 끔 control-noclose × S4c4200 디스크 × 3 = 3 ·
  효과 {control, A, A+C, A+F, B+C} × S3e-a20 × 3 = 15 · 마감 임박 S3c-a20 × {schedule, zero} × 3 = 6 → **84회, 예상 약 19.2시간**(재측정 제외 — `--dry-run`이 셈).
- 순서: 회차 우선 + 균형 회전(campaign.sh 머리말). 재측정은 ADR-008 규칙 + ADR-009 결과 사유(ACCEPT_RE)·효과 셀은 effect.json 위반으로.
- 시한: run.sh가 2026-12-31T09:00Z부터 회차를 거부한다(`seed-time-bomb`).

```bash
k6/ADR-009/scripts/campaign.sh --sha <SHA> --id <id> --dry-run          # 계획만(84행 · 예상 시간)
k6/ADR-009/scripts/campaign.sh --sha <SHA> [--id <id>]                  # 확정 측정(하네스 = 커밋된 SHA)
k6/ADR-009/scripts/run.sh --sha <SHA> --cond A+F --cells S2 --reps 1 --id <id>   # 조건 하나(스모크 — 커밋 전이면 --worktree)
python3 k6/ADR-009/scripts/summarize.py k6/ADR-009/results/<id>/<조건>
```
