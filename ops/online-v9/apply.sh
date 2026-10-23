#!/usr/bin/env bash
# ADR-013 §4.4 운영 중 적용판 — V9(updated_at 열 · 트리거 · 인덱스)를 쓰기를 막지 않고 먼저 깔고, 카탈로그가 Flyway V9와 같을 때만 V9 이력을 기록한다.
#   V9 파일(main-app/src/main/resources/db/migration-updated-at-trigger/V9__quota_updated_at_trigger.sql)은 바꾸지 않는다 — 이력 체크섬 = 파일 체크섬.
#   이 폴더는 Flyway 위치가 아니다(클래스패스 밖 — 앱이 실수로 실행할 수 없다 · §4.10 #5).
#
#   ops/online-v9/apply.sh --psql "<psql 명령>" --ref-catalog <json> --ref-history <json> --out <pre-apply.json>
#       [--flyway "<flyway 명령>"] [--locations "filesystem:/db/migration,filesystem:/db/migration-updated-at-trigger"] [--flyway-url <jdbc url>]
#       [--record auto|cli|direct] [--lock-timeout 2s] [--retries 30] [--stmt-timeout 600s] [--index-retries 2]
#   --psql      표준 입력으로 SQL을 받는 psql 명령(마지막 단어가 psql 인자를 받을 수 있게 — 예: ssh <서버> docker exec -i -e PGAPPNAME=seatlab-online-v9 seatlab-db-1 psql -X -q -U seat -d seat).
#               세션 이름은 호출자가 정한다(하네스 = seatlab-online-v9 · H5). 출력 형식은 이 스크립트가 \pset으로 정한다.
#   --ref-catalog · --ref-history   같은 SHA 새 앱 Flyway(V1~V10)를 빈 DB에 적용한 뒤 04-catalog.sql · 05-history.sql의 결과(하네스가 seat_ref DB로 만든다).
#   --flyway    기록 방법 ①의 Flyway CLI(11.7.2 공식 이미지 — 뒤에 Flyway 인자가 붙는다). 없으면 ①을 건너뛰고 ② 직접 기록(--record auto일 때).
#               비밀번호는 명령줄로 넘기지 않는다(CR13-14): **환경변수 FLYWAY_PASSWORD 필수**(--flyway가 있을 때 — 없으면 멈춘다 · 기본값 없음).
#               이 명령은 그 값을 Flyway에 환경으로 넘겨야 한다(예: docker run -e FLYWAY_PASSWORD … — Flyway CLI가 FLYWAY_PASSWORD를 읽는다 ·
#               원격이면 ssh 표준 입력 — 하네스 lib13.sh flyway_run_cmd). 사용자 = FLYWAY_USER(기본 seat — 비밀 아님).
#               psql에 비밀번호가 필요하면 --psql 명령이 PGPASSWORD 환경을 쓴다(명령줄 금지).
#   --locations Flyway CLI 위치 = 새 SHA의 **전체 위치 중 V10 뺀 것**(기본 위치 + V9 트리거 위치 — DS-4 · target=9).
#
# 단계(§4.4 표):
#   0 사전 확인: 열·함수·트리거·인덱스(유효)·V9 이력·소유자 — 있는 단계는 건너뛴다('이어서'). V9 이력이 이미 있으면 멈춘다(already-recorded).
#   1 열: BEGIN · SET LOCAL lock_timeout · 01-column.sql · COMMIT — lock_timeout(55P03)에 걸리면 1초 쉬고 다시(최대 --retries). 시도마다 대기·결과를 남긴다.
#   2 함수·트리거: 한 트랜잭션 · 같은 시한·재시도(02-function.sql + 02-trigger.sql).
#   3 인덱스: RESET lock_timeout · SET statement_timeout(DS-8) · 03-index.sql(CONCURRENTLY — 자동 커밋) → indisvalid AND indisready 확인 —
#     아니면 DROP INDEX CONCURRENTLY 뒤 다시(최대 --index-retries) · 그래도 아니면 기록하지 않고 멈춘다.
#   4 동치 확인 + 기록: 04-catalog.sql(열 정의·기본값 식 · 인덱스 정의·유효 · 함수 정의 · 트리거 정의·활성)이 ref와 같아야 기록한다(N5 · N7 — 다르면 기록하지 않는다).
#     빼는 값(DS-14): atthasmissing·attmissingval(m — 기록만) · OID 등.
#     기록 ① Flyway CLI: migrate -target=9 -skipExecutingMigrations=true -locations=<전체 위치 중 V10 뺀 것>.
#       실패 원인 분류: 검증 오류(Validate failed · checksum mismatch · 해석 못 한 적용 버전) = 위치·파일이 틀림 → 멈춘다(②로 가지 않는다 — ②도 같은 이력에서 실패한다) ·
#       판 제한(유료판 기능 요구 — Flyway 11.7.2 고정 문구·예외 이름으로만: FlywayEditionUpgradeRequiredException '<판> upgrade required: <기능> is not supported by <판>' ·
#       FlywayRedgateEditionRequiredException 'Redgate Edition Required: <기능> is not supported by OSS Edition' · 옛 FlywayTeamsUpgradeRequiredException) → ② ·
#       그 밖 = 멈춘다(분류 불가 — 숨기지 않는다). 판 이름 낱말만으로는 분류하지 않는다(시작 배너 'Flyway OSS Edition …'이 늘 찍힌다 — CR13-7).
#     기록 ② 직전: 같은 트랜잭션(이력 표 SHARE ROW EXCLUSIVE)에서 V9 이력 0을 다시 확인 — 있으면 넣지 않고 멈춘다(① 일부 기록과 겹치지 않게 — CR13-7).
#     실패하면(어느 단계든 · 이력 표가 있을 때) V9 이력을 다시 읽어 record.history_after_fail에 남긴다.
#     기록 ② 직접: ref V9 이력 행(version · description · type · script · checksum)을 installed_rank = 다음 번호 · success = true로 넣는다
#       (Flyway 내부 표 직접 쓰기 — Flyway 버전에 묶이는 한계).
#     기록 뒤 확인: V9 행 1개 · success · 체크섬 = ref.
# 결과: --out JSON(단계별 시도·벽시계·lock_timeout 수 · m · 동치 · 기록 방법·분류 · result ok|failed · fail_step · reason). exit 0 = 기록까지 끝 · 1 = 실패(기록하지 않음 — N7) · 2 = 인자.
set -uo pipefail

PSQL="" FLYWAY="" REF_CAT="" REF_HIST="" OUT="" RECORD=auto LOCK_TIMEOUT=2s RETRIES=30 STMT_TIMEOUT=600s INDEX_RETRIES=2
LOCATIONS="filesystem:/db/migration,filesystem:/db/migration-updated-at-trigger"
FLYWAY_URL="jdbc:postgresql://db:5432/seat?ApplicationName=seatlab-online-v9"
FLYWAY_USER="${FLYWAY_USER:-seat}"
while (( $# )); do
  case "$1" in
    --psql) PSQL="$2"; shift 2 ;;
    --flyway) FLYWAY="$2"; shift 2 ;;
    --ref-catalog) REF_CAT="$2"; shift 2 ;;
    --ref-history) REF_HIST="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --record) RECORD="$2"; shift 2 ;;
    --lock-timeout) LOCK_TIMEOUT="$2"; shift 2 ;;
    --retries) RETRIES="$2"; shift 2 ;;
    --stmt-timeout) STMT_TIMEOUT="$2"; shift 2 ;;
    --index-retries) INDEX_RETRIES="$2"; shift 2 ;;
    --locations) LOCATIONS="$2"; shift 2 ;;
    --flyway-url) FLYWAY_URL="$2"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$PSQL" && -n "$REF_CAT" && -n "$REF_HIST" && -n "$OUT" ]] || { echo "--psql · --ref-catalog · --ref-history · --out 필수" >&2; exit 2; }
[[ "$RECORD" =~ ^(auto|cli|direct)$ ]] || { echo "--record auto|cli|direct" >&2; exit 2; }
[[ "$RECORD" != cli || -n "$FLYWAY" ]] || { echo "--record cli는 --flyway가 필요하다" >&2; exit 2; }
[[ -z "$FLYWAY" || -n "${FLYWAY_PASSWORD:-}" ]] || { echo "--flyway는 환경변수 FLYWAY_PASSWORD가 필요하다(명령줄·기본값 없음)" >&2; exit 2; }
[[ "$LOCK_TIMEOUT" =~ ^[0-9]+(ms|s)$ && "$STMT_TIMEOUT" =~ ^[0-9]+(ms|s|min)$ && "$RETRIES$INDEX_RETRIES" =~ ^[0-9]+$ ]] || { echo "시한·재시도 형식" >&2; exit 2; }
jq -e '.column != null and .index != null and .function != null and .trigger != null' "$REF_CAT" > /dev/null 2>&1 || { echo "--ref-catalog: V9 객체 4종이 든 카탈로그가 아니다" >&2; exit 2; }
jq -e 'type == "array" and length == 1 and .[0].version == "9" and .[0].success == true' "$REF_HIST" > /dev/null 2>&1 || { echo "--ref-history: V9 성공 행 1개가 아니다" >&2; exit 2; }
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
HEAD=$'\\set QUIET on\n\\pset tuples_only on\n\\pset format unaligned\n\\set VERBOSITY verbose\n'

psql_run() { bash -c "$PSQL" <<< "$HEAD$1" 2>"$W/err"; }   # 표준 출력 = 결과 줄 · 오류는 $W/err
now() { date +%s.%N; }
fail() {   # 인자: 단계 사유 — 기록하지 않고 끝낸다(N7). 이력 표가 있으면 실패 뒤 V9 이력을 다시 읽어 남긴다(CR13-7 — 기록 ①이 일부 쓰고 실패했는지 드러낸다)
  FAIL_STEP="$1" REASON="$2"
  if [[ "${HIST_TABLE:-false}" == true ]]; then
    local hf; hf=$(psql_run "$(cat "$HERE/05-history.sql")" | tail -1)
    jq -e 'type == "array"' <<< "$hf" > /dev/null 2>&1 || hf=$(jq -nc --arg e "$(head -c 300 "$W/err")" '{read_failed: $e}')
    REC=$(jq -c --argjson h "$hf" '(. // {}) + {history_after_fail: $h}' <<< "${REC:-null}")
  fi
  finish failed; exit 1
}
STEPS='{}'; M=null; EQUIV=null; REC=null; STATE0=null; FAIL_STEP=null REASON=null; T_START=$(now)
step_put() { STEPS=$(jq -c --arg k "$1" --argjson v "$2" '. + {($k): $v}' <<< "$STEPS"); }
finish() {
  jq -n --arg result "$1" --argjson t0 "$T_START" --argjson t1 "$(now)" --argjson state0 "$STATE0" --argjson steps "$STEPS" --argjson m "$M" \
        --argjson equiv "$EQUIV" --argjson rec "$REC" --arg fstep "$FAIL_STEP" --arg reason "$REASON" \
        --arg lt "$LOCK_TIMEOUT" --arg st "$STMT_TIMEOUT" --argjson rt "$RETRIES" --argjson ir "$INDEX_RETRIES" --arg mode "$RECORD" --arg loc "$LOCATIONS" \
    '{tool: "ops/online-v9/apply.sh", result: $result, start_pc: $t0, end_pc: $t1, wall_s: ($t1 - $t0), state0: $state0, steps: $steps, m: $m,
      equivalence: $equiv, record: $rec, fail_step: (if $fstep == "null" then null else $fstep end), reason: (if $reason == "null" then null else $reason end),
      knobs: {lock_timeout: $lt, retries: $rt, stmt_timeout: $st, index_retries: $ir, record: $mode, locations: $loc}}' > "$OUT"
}

# ---- 0 사전 확인 ------------------------------------------------------------------------------------------------------------
s0=$(psql_run "SELECT json_build_object(
  'col', EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'user_hold_quota'::regclass AND attname = 'updated_at' AND NOT attisdropped),
  'fn', EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE p.proname = 'trg_quota_touch' AND n.nspname = current_schema()),
  'trg', EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'user_hold_quota_touch' AND NOT tgisinternal),
  'idx', to_regclass('idx_user_hold_quota_updated_at') IS NOT NULL,
  'idx_valid', (SELECT indisvalid AND indisready FROM pg_index WHERE indexrelid = to_regclass('idx_user_hold_quota_updated_at')),
  'hist_table', to_regclass('flyway_schema_history') IS NOT NULL,
  'owner_ok', (SELECT pg_get_userbyid(relowner) = current_user FROM pg_class WHERE oid = 'user_hold_quota'::regclass),
  'role', current_user, 'db_now', extract(epoch FROM clock_timestamp()));" | tail -1)
jq -e 'type == "object"' <<< "$s0" > /dev/null 2>&1 || { STATE0=null; fail 0 "사전 확인 실패: $(head -c 300 "$W/err")"; }
STATE0="$s0"
[[ "$(jq -r .owner_ok <<< "$s0")" == true ]] || fail 0 "접속 역할 $(jq -r .role <<< "$s0")이 user_hold_quota 소유자가 아니다"
HIST_TABLE=$(jq -r .hist_table <<< "$s0")
[[ "$HIST_TABLE" == true ]] || fail 0 "flyway_schema_history 없음 — Flyway 이력이 있는 DB가 아니다(baseline 경로는 다루지 않는다 · §4.4 (마))"
h0=$(psql_run "$(cat "$HERE/05-history.sql")" | tail -1)
jq -e 'type == "array"' <<< "$h0" > /dev/null 2>&1 || fail 0 "V9 이력 조회 실패: $(head -c 300 "$W/err")"
if (( $(jq 'length' <<< "$h0") > 0 )); then fail 0 "already-recorded — V9 이력이 이미 있다($h0)"; fi

# ---- 1·2: lock_timeout + 재시도 트랜잭션 ---------------------------------------------------------------------------------
lock_tx() {   # 인자: 단계 이름 · SQL 몸통(트랜잭션 안) → STEPS[단계] · 성공 0 / 실패 1
  local name="$1" body="$2" i out tag t0 t1 st atts='[]' lt=0 pc0 pc1 w0; w0=$(now)
  for (( i = 1; i <= RETRIES; i++ )); do
    pc0=$(now)
    out=$(psql_run "BEGIN;
SET LOCAL lock_timeout = '$LOCK_TIMEOUT';
SELECT 'T0 ' || extract(epoch FROM clock_timestamp());
$body
\\if :ERROR
ROLLBACK;
SELECT 'FAIL ' || extract(epoch FROM clock_timestamp()) || ' ' || :'LAST_ERROR_SQLSTATE';
\\else
COMMIT;
SELECT 'OK ' || extract(epoch FROM clock_timestamp());
\\endif")
    pc1=$(now)
    t0=$(awk '$1 == "T0" {print $2}' <<< "$out" | tail -1)
    tag=$(awk '$1 == "OK" || $1 == "FAIL" {print $1}' <<< "$out" | tail -1)
    t1=$(awk '$1 == "OK" || $1 == "FAIL" {print $2}' <<< "$out" | tail -1)
    st=$(awk '$1 == "FAIL" {print $3}' <<< "$out" | tail -1)
    atts=$(jq -c --argjson t0 "${t0:-null}" --argjson t1 "${t1:-null}" --arg tag "${tag:-none}" --arg st "$st" --argjson pc0 "$pc0" --argjson pc1 "$pc1" \
           --arg err "$(head -c 300 "$W/err")" \
      '. + [{t0_db: $t0, t1_db: $t1, wait_s: (if $t0 and $t1 then $t1 - $t0 else null end), result: $tag, sqlstate: (if $st == "" then null else $st end),
             start_pc: $pc0, end_pc: $pc1} + (if $tag == "OK" then {} else {err: $err} end)]' <<< "$atts")
    if [[ "$tag" == OK ]]; then
      step_put "$name" "$(jq -c --argjson w0 "$w0" --argjson w1 "$(now)" '{attempts: ., tries: length, lock_timeouts: map(select(.sqlstate == "55P03")) | length, start_pc: $w0, end_pc: $w1, wall_s: ($w1 - $w0)}' <<< "$atts")"
      return 0
    fi
    if [[ "$st" == 55P03 ]]; then lt=$((lt + 1)); sleep 1; continue; fi   # lock_timeout만 다시 한다 — 그 밖의 오류는 곧바로 실패
    break
  done
  step_put "$name" "$(jq -c --argjson w0 "$w0" --argjson w1 "$(now)" '{attempts: ., tries: length, lock_timeouts: map(select(.sqlstate == "55P03")) | length, start_pc: $w0, end_pc: $w1, wall_s: ($w1 - $w0), failed: true}' <<< "$atts")"
  return 1
}

if [[ "$(jq -r .col <<< "$s0")" == true ]]; then step_put 1 '{"skipped": "exists"}'
else lock_tx 1 "$(cat "$HERE/01-column.sql")" || fail 1 "열 추가 실패(lock_timeout 재시도 $RETRIES번 소진 또는 오류)"; fi
M=$(psql_run "SELECT json_build_object('atthasmissing', atthasmissing, 'attmissingval', attmissingval::text) FROM pg_attribute WHERE attrelid = 'user_hold_quota'::regclass AND attname = 'updated_at';" | tail -1)
jq -e 'type == "object"' <<< "$M" > /dev/null 2>&1 || M=null   # m은 기록만(§4.11 — 판정은 카탈로그 값)

if [[ "$(jq -r .trg <<< "$s0")" == true ]]; then step_put 2 '{"skipped": "exists"}'
else
  body="$( [[ "$(jq -r .fn <<< "$s0")" == true ]] || cat "$HERE/02-function.sql"; cat "$HERE/02-trigger.sql")"
  lock_tx 2 "$body" || fail 2 "함수·트리거 생성 실패"
fi

# ---- 3 인덱스(CONCURRENTLY) -------------------------------------------------------------------------------------------------
idx_valid() { psql_run "SELECT coalesce((SELECT (indisvalid AND indisready)::text FROM pg_index WHERE indexrelid = to_regclass('idx_user_hold_quota_updated_at')), 'none');" | tail -1; }
DROPS='[]'
drop_idx() {   # INVALID 인덱스 지우기(DROP INDEX CONCURRENTLY) → to_regclass로 정말 없는지 확인해 DROPS에 실제 상태를 남긴다(CR13-12). 남았으면 1
  local pc0 out gone; pc0=$(now)
  out=$(psql_run "RESET lock_timeout; SET statement_timeout = '$STMT_TIMEOUT'; DROP INDEX CONCURRENTLY IF EXISTS idx_user_hold_quota_updated_at;
\\if :ERROR
SELECT 'FAIL ' || :'LAST_ERROR_SQLSTATE';
\\else
SELECT 'OK';
\\endif
SELECT 'GONE ' || (to_regclass('idx_user_hold_quota_updated_at') IS NULL)::text;")
  gone=$(awk '$1 == "GONE" {print $2}' <<< "$out" | tail -1)
  DROPS=$(jq -c --arg r "$(awk '$1 == "OK" || $1 == "FAIL" {print $0}' <<< "$out" | tail -1)" --arg g "${gone:-unknown}" --argjson pc0 "$pc0" --argjson pc1 "$(now)" \
          --arg err "$(head -c 300 "$W/err")" '. + [{result: (if $r == "" then "none" else $r end), gone: (if $g == "true" then true elif $g == "false" then false else null end),
          start_pc: $pc0, end_pc: $pc1} + (if $g == "true" then {} else {err: $err} end)]' <<< "$DROPS")
  [[ "$gone" == true ]]
}
if [[ "$(jq -r .idx_valid <<< "$s0")" == true ]]; then step_put 3 '{"skipped": "valid-exists"}'
else
  atts='[]' drops=0 w0=$(now) ok=0 dropfail=0
  if [[ "$(jq -r .idx <<< "$s0")" == true ]]; then   # 이어서: 남은 INVALID 인덱스 — 지우고 다시
    drops=$((drops + 1))
    drop_idx || { step_put 3 "$(jq -nc --argjson d "$DROPS" '{drops_detail: $d, failed: true}')"; fail 3 "남은 INVALID 인덱스 DROP 뒤에도 인덱스가 있다 — 만들지 않고 멈춘다"; }
  fi
  for (( i = 0; i <= INDEX_RETRIES; i++ )); do
    pc0=$(now)
    out=$(psql_run "RESET lock_timeout;
SET statement_timeout = '$STMT_TIMEOUT';
SELECT 'T0 ' || extract(epoch FROM clock_timestamp());
$(cat "$HERE/03-index.sql")
\\if :ERROR
SELECT 'FAIL ' || extract(epoch FROM clock_timestamp()) || ' ' || :'LAST_ERROR_SQLSTATE';
\\else
SELECT 'OK ' || extract(epoch FROM clock_timestamp());
\\endif")
    pc1=$(now); v=$(idx_valid)
    t0=$(awk '$1 == "T0" {print $2}' <<< "$out" | tail -1); tag=$(awk '$1 == "OK" || $1 == "FAIL" {print $1}' <<< "$out" | tail -1)
    t1=$(awk '$1 == "OK" || $1 == "FAIL" {print $2}' <<< "$out" | tail -1); st=$(awk '$1 == "FAIL" {print $3}' <<< "$out" | tail -1)
    atts=$(jq -c --argjson t0 "${t0:-null}" --argjson t1 "${t1:-null}" --arg tag "${tag:-none}" --arg st "$st" --arg v "$v" --argjson pc0 "$pc0" --argjson pc1 "$pc1" \
           --arg err "$(head -c 300 "$W/err")" \
      '. + [{t0_db: $t0, t1_db: $t1, build_s: (if $t0 and $t1 then $t1 - $t0 else null end), result: $tag, sqlstate: (if $st == "" then null else $st end),
             valid: $v, start_pc: $pc0, end_pc: $pc1} + (if $tag == "OK" then {} else {err: $err} end)]' <<< "$atts")
    if [[ "$v" == true ]]; then ok=1; break; fi
    # INVALID(statement_timeout 끊김 포함) 또는 없음 — 지우고 다시(§4.4 단계 3). DROP 뒤 인덱스가 남으면 다시 만들지 않고 멈춘다(CR13-12)
    drops=$((drops + 1))
    drop_idx || { dropfail=1; break; }
  done
  step_put 3 "$(jq -c --argjson w0 "$w0" --argjson w1 "$(now)" --argjson drops "$drops" --argjson dd "$DROPS" --argjson ok "$ok" \
    '{attempts: ., tries: length, drops: $drops, drops_detail: $dd, start_pc: $w0, end_pc: $w1, wall_s: ($w1 - $w0)} + (if $ok == 1 then {} else {failed: true} end)' <<< "$atts")"
  (( dropfail )) && fail 3 "INVALID 인덱스 DROP 뒤에도 인덱스가 있다(to_regclass) — 기록하지 않음 · 인덱스 상태는 drops_detail"
  (( ok )) || fail 3 "인덱스가 유효해지지 않았다(시도 $((INDEX_RETRIES + 1))번) — INVALID 인덱스는 지웠다(drops_detail gone) · 기록하지 않음"
fi

# ---- 4 동치 확인 ---------------------------------------------------------------------------------------------------------------
cat4=$(psql_run "$(cat "$HERE/04-catalog.sql")" | tail -1)
jq -e 'type == "object"' <<< "$cat4" > /dev/null 2>&1 || fail 4 "카탈로그 조회 실패: $(head -c 300 "$W/err")"
EQUIV=$(jq -c --slurpfile r "$REF_CAT" '
  def cmp($k): {key: $k, ok: (.[$k] == $r[0][$k]), got: .[$k], want: $r[0][$k]};
  [cmp("column"), cmp("index"), cmp("function"), cmp("trigger")] as $c
  | {ok: ($c | all(.ok)), diffs: [$c[] | select(.ok | not)], volatile: .volatile, ref_volatile: $r[0].volatile, owner_ok: .owner_ok}' <<< "$cat4")
[[ "$(jq -r .ok <<< "$EQUIV")" == true ]] || fail 4 "동치 불일치 — $(jq -c '[.diffs[].key]' <<< "$EQUIV") · 기록하지 않음(N5·N7)"

# ---- 4 기록 ------------------------------------------------------------------------------------------------------------------
want_ck=$(jq -r '.[0].checksum' "$REF_HIST")
verify_hist() { psql_run "$(cat "$HERE/05-history.sql")" | tail -1; }
rec_cli() {
  local args=(-url="$FLYWAY_URL" -user="$FLYWAY_USER" -locations="$LOCATIONS" -target=9 -skipExecutingMigrations=true migrate)
  local pc0 pc1 rc out cls
  pc0=$(now); out=$(export FLYWAY_PASSWORD; eval "$FLYWAY $(printf '%q ' "${args[@]}")" 2>&1); rc=$?; pc1=$(now)   # 비밀번호 = 환경(CR13-14)
  cls=ok
  if (( rc != 0 )); then
    if grep -qiE 'Validate failed|checksum mismatch|Detected applied migration not resolved|Detected resolved migration not applied|VALIDATE_ERROR' <<< "$out"; then cls=validate-error
    elif grep -qE 'FlywayEditionUpgradeRequiredException|FlywayRedgateEditionRequiredException|FlywayTeamsUpgradeRequiredException| upgrade required: .+ is not supported by |Redgate Edition Required: .+ is not supported by ' <<< "$out"; then cls=edition-limit
    else cls=unknown; fi
  fi
  REC=$(jq -nc --argjson rc "$rc" --arg cls "$cls" --arg tail "$(tail -c 1500 <<< "$out")" --argjson pc0 "$pc0" --argjson pc1 "$pc1" \
        '{cli: {rc: $rc, class: $cls, output_tail: $tail, start_pc: $pc0, end_pc: $pc1}}')
  [[ "$cls" == ok ]]
}
rec_direct() {
  local r v ver desc typ scr ck pc0 pc1 out
  r=$(jq -c '.[0]' "$REF_HIST"); ver=$(jq -r .version <<< "$r"); desc=$(jq -r .description <<< "$r"); typ=$(jq -r .type <<< "$r"); scr=$(jq -r .script <<< "$r"); ck=$(jq -r .checksum <<< "$r")
  [[ "$ck" =~ ^-?[0-9]+$ && "$ver" == 9 ]] || return 1
  for v in "$desc" "$typ" "$scr"; do [[ "$v" != *'$v13$'* ]] || return 1; done
  pc0=$(now)
  # 넣기 직전 V9 이력 0 재확인(CR13-7): 같은 트랜잭션 · 표 락 뒤 — 있으면 넣지 않고 ROLLBACK(ALREADY n)
  out=$(psql_run "\\set ON_ERROR_STOP on
BEGIN;
LOCK TABLE flyway_schema_history IN SHARE ROW EXCLUSIVE MODE;
SELECT count(*) AS v9_rows, count(*) > 0 AS v9_exists FROM flyway_schema_history WHERE version = '9' \\gset
\\if :v9_exists
ROLLBACK;
SELECT 'ALREADY ' || :v9_rows;
\\else
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
SELECT coalesce(max(installed_rank), 0) + 1, '9', \$v13\$$desc\$v13\$, \$v13\$$typ\$v13\$, \$v13\$$scr\$v13\$, $ck, current_user, 0, true FROM flyway_schema_history;
COMMIT;
SELECT 'OK';
\\endif"); local rc=$?; pc1=$(now)
  local already; already=$(awk '$1 == "ALREADY" {print $2}' <<< "$out" | tail -1)
  REC=$(jq -c --argjson rc "$rc" --arg out "$out" --arg al "$already" --arg err "$(head -c 300 "$W/err")" --argjson pc0 "$pc0" --argjson pc1 "$pc1" \
        '(. // {}) + {direct: {rc: $rc, ok: ($out | test("(^|\n)OK$")), precheck_v9_rows: (if $al == "" then 0 else ($al | tonumber) end), err: $err, start_pc: $pc0, end_pc: $pc1,
                                limit: "Flyway 내부 표 직접 쓰기 — Flyway 버전에 묶인다"}}' <<< "${REC:-null}")
  [[ -z "$already" ]] || return 3
  (( rc == 0 )) && grep -qx OK <<< "$out"
}
method=""
case "$RECORD" in
  cli) rec_cli && method=cli || fail 4 "기록 ① 실패($(jq -r .cli.class <<< "$REC")) — ②로 넘어가지 않는다(--record cli)" ;;
  direct) rec_direct && method=direct || fail 4 "기록 ② 실패($(jq -r 'if .direct.precheck_v9_rows > 0 then "넣기 직전 V9 이력 \(.direct.precheck_v9_rows)행 — 넣지 않음" else "rc \(.direct.rc)" end' <<< "$REC"))" ;;
  auto)
    if [[ -n "$FLYWAY" ]] && rec_cli; then method=cli
    elif [[ -z "$FLYWAY" ]] || [[ "$(jq -r .cli.class <<< "$REC")" == edition-limit ]]; then
      rec_direct && method=direct || fail 4 "기록 ② 실패(① $( [[ -z "$FLYWAY" ]] && echo '없음' || echo 'edition-limit') · $(jq -r 'if .direct.precheck_v9_rows > 0 then "넣기 직전 V9 이력 \(.direct.precheck_v9_rows)행 — 넣지 않음" else "rc \(.direct.rc)" end' <<< "$REC"))"
    else
      fail 4 "기록 ① 실패 — $(jq -r .cli.class <<< "$REC")(검증 오류·분류 불가는 ②로 넘어가지 않는다 · §4.4)"
    fi ;;
esac
h1=$(verify_hist)
REC=$(jq -c --arg m "$method" --argjson h "${h1:-null}" --arg want "$want_ck" \
      '. + {method: $m, history: $h, verify_ok: (($h | type) == "array" and ($h | length) == 1 and $h[0].success == true and ($h[0].checksum | tostring) == $want)}' <<< "$REC")
[[ "$(jq -r .verify_ok <<< "$REC")" == true ]] || fail 4 "기록 뒤 확인 실패 — V9 이력 $h1 · 기대 체크섬 $want_ck"
finish ok
exit 0
