# ops/online-v9 — 운영 중 V9 적용판(ADR-013 §4.4) · 운영 절차

`apply.sh`는 V9(updated_at 열 · 트리거 · 인덱스)를 쓰기를 막지 않고 먼저 깔고, 카탈로그가 Flyway V9와 같을 때만 V9 이력을 기록한다(단계 · 인자 · 출력은 `apply.sh` 머리말이 정본).
이 폴더는 Flyway 위치가 아니다(클래스패스 밖 — 앱이 실행하지 않는다). 로컬 확인 = `bash ops/online-v9/test-local.sh`(exit 0 = 모두 통과 · 3 = 스킵 있음 — green 아님).

| 파일 | 하는 일 | 쓰기 |
|---|---|---|
| `apply.sh` | 단계 0 사전 확인 → 1 열 → 2 함수 · 트리거 → 3 인덱스 → 4 동치 확인 + 기록(① Flyway CLI · ② 직접) | 스키마(V9 객체) · `flyway_schema_history` |
| `01-column.sql` ~ `05-history.sql` | 단계별 SQL(apply.sh가 부른다) | — |
| `06-history-diff-recover.sql` | 기록 ① history-diff 실패 뒤 남은 이력 행 복구 틀(미리보기 ROLLBACK 기본) | `flyway_schema_history` 행 삭제만 |
| `test-local.sh` | 로컬 docker PG 16 하나로 ok 경로 · 실패 주입 · outOfOrder · history-diff 복구까지 확인 | 자기 컨테이너만 |

`psql`은 `-X -v ON_ERROR_STOP=1`로 돌린다. 비밀번호는 명령줄에 쓰지 않는다(`PGPASSWORD` 환경 · `.pgpass` · Flyway는 `FLYWAY_PASSWORD` 환경).

## 앱 첫 기동 out-of-order 1회(ADR-015 §설계-11 'ADR-013 §4.4 절차와의 관계' — 정본은 그 글머리)

닫힌 ADR-013은 고치지 않고 ADR-015 §설계-11과 여기에 둔다.

- 옛 방식 DB에 V12 · V13이 이미 있으면(`apply.sh` 단계 0 `state0.max_applied > 9`) 적용판 기록 ①이 `-outOfOrder=true`를 **한 번** 붙인다(자동 — `knobs.out_of_order_cli`).
- 이어지는 ADR-013 ④ · ⑤의 T1 새 앱(또는 그림자 앱) **첫 기동**은 `apply.sh` 출력 `next.app_first_boot_out_of_order`가 참일 때(`max_applied > 10`)
  **그 한 번만 `SPRING_FLYWAY_OUT_OF_ORDER=true`** 로 띄운다.
- 그 기동 뒤 V10 이력(success)을 확인하고(`SELECT version, success FROM flyway_schema_history WHERE version = '10'`) **다음 기동부터 플래그를 뺀다**.
- 플래그 없이 띄우면 FlywayValidateException으로 기동이 멈춘다(스키마 무변경 — ADR-015 `[구현 검증]` #6 · `test-local.sh` ⓑ). 멈췄으면 플래그를 주고 다시 띄우면 된다.
- ①~③(앱이 V9 · V10을 적용하는 단계)도 `max_applied > 10`이면 같은 1회 플래그가 필요하다.

## 기록 ① 뒤 history-diff 실패(ADR-015 R15-6 · R15-15)

**증상**: `apply.sh` exit 1 · `fail_step` 4 · `record.cli.class` = `history-diff` · 사유 '이력 차이 [...] ≠ ["9"] · 기록 ①(트랜잭션 밖)이 쓴 행은 남아 있다'.
Flyway CLI(`-skipExecutingMigrations=true` · outOfOrder 판이면 `-outOfOrder=true`)가 V9 말고 다른 미적용 버전도 **실행 없이** '적용됨'으로 적었다 — 보통 `--locations`가
틀렸거나(원격 위치 내용이 이 저장소와 어긋남 — 단계 0의 `pending_le9` 사전 확인은 이 저장소의 같은 이름 폴더를 본다) 위치에 다른 버전 파일이 섞였다.
그 행은 남는다 — **앱을 띄우지 말고**(앱 기동 검사 · Flyway가 객체 없는 '적용됨' 행을 믿는다) 아래를 먼저 한다.

### 확인할 것

1. **남은 행 목록** = out JSON의 `record.cli.history_diff`(installed_rank · version · success) — 이번 기록 ①이 새로 쓴 행 전부다.
   `jq -c '.record.cli.history_diff' <out.json>` · 지금 이력: `SELECT installed_rank, version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank`.
   실행 전 이력과 비교한다(그 차이가 history_diff와 같아야 한다 — 다르면 그 사이 다른 Flyway 실행이 있었다 · 멈추고 원인부터).
2. **각 버전의 스키마 객체 실제 유무** — `history_diff`의 버전마다 그 버전 파일(`script` 이름 · 기록 ①의 `record.cli.args` `-locations`)이 만드는 객체가 DB에 있는가:
   - V9: 있다(apply.sh 단계 1~3이 만들고 단계 4 동치 확인을 통과한 뒤에만 기록 ①을 부른다 — `equivalence.ok` = true).
   - 그 밖(예: V4 `uq_seat_hold_seat_id` · V8 트리거): `-skipExecutingMigrations`라 **없는 것이 정상**이다. 예: `SELECT to_regclass('uq_seat_hold_seat_id')`(V4) ·
     `SELECT tgname FROM pg_trigger WHERE tgname IN ('seat_hold_quota', 'reservation_quota')`(V8). 파일을 열어 CREATE 대상마다 확인한다.
   - 그 버전이 지금 구성에 **속하는가**(예: V4 = UNIQUE 전략 전용 · V8 = counter-trigger 전용 — 기동 검사가 다른 전략에서 그 객체를 거부한다).
3. 그 사이 쓰기: 이력 행만 남았고 앱은 아직 그 행을 읽지 않았다(앱을 띄우지 않았다면) — 데이터 변경은 없다.

### 재개 방법

**A(권장) — 이번 기록 ①이 쓴 행을 모두 지우고 apply.sh를 고친 `--locations`로 다시**:
1. 미리보기(기본 ROLLBACK): `psql -X -v ON_ERROR_STOP=1 -v versions='{<history_diff 버전 전부 — V9 포함>}' -f ops/online-v9/06-history-diff-recover.sql`
   — 지울 행 · 입력 확인(버전마다 1행 · 이력 맨 끝 연속 행) · 남은 이력(`HISTORY …` = 실행 전 이력)을 본다.
2. 같은 명령에 `-v commit=1` → `RECOVER COMMIT`. 이력 = 기록 ① 실행 전(V9 객체는 그대로).
3. `--locations`를 고친다(기본 위치 + V9 위치만 — V10 빼고 · 이 저장소의 같은 SHA 내용) → `apply.sh`를 다시 돌린다.
   단계 1~3은 '있음'으로 건너뛰고(`skipped`) 단계 4가 동치를 다시 보고 V9 한 행만 기록한다(history_diff = ["9"] · exit 0). 그 뒤 위 '앱 첫 기동 out-of-order 1회'.
   - V9 행을 남기고 다른 행만 지우는 것도 틀은 받는다(이력 맨 끝 연속이면) — 하지만 V9 행의 기록 · 확인을 apply.sh가 다시 하지 않으므로 A의 전부 지우기를 권한다.

**B — 그 버전을 실제로 적용하는 길**(그 버전이 지금 구성에 속할 때만 — 예: UNIQUE 전략 DB에 V4가 빠져 있었다):
그 버전 파일을 그대로 한 트랜잭션으로 실행한다(`psql -X -1 -v ON_ERROR_STOP=1 -f <그 V 파일>` — 체크섬은 이미 그 파일 것으로 기록됐다 · 파일을 바꾸지 않는다).
객체를 확인한 뒤 같은 SHA의 앱 위치 목록에 그 위치를 넣어 띄운다(빠뜨리면 Flyway 검증이 '적용됐는데 위치에 없는 버전'으로 멈춘다).
구성에 속하지 않는 버전이면 B를 쓰지 않는다(A).

로컬 실측: `test-local.sh` 'R15-6 ②' 뒤 'R15-15' 단계 — 이력 [.. 13, 4, 9] → 미리보기(무변경) · 잘못된 입력 거부 둘(V13 · V4만 — 이력 맨 끝 연속이 아님) · `versions='{4,9}'` COMMIT(이력 = 실행 전) ·
고친 `--locations`로 apply.sh 다시(exit 0 · 이력 = 실행 전 + V9).
