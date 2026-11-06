#!/usr/bin/env bash
# ADR-015 [구현 검증] #5 — V12(확정 예약 부분 유니크 인덱스) 운영 크기 생성 시간 · reservation 쓰기 차단 길이(측정 전 고정 규칙 초안 측정-2 '#5 단계').
#   크기 N(plan15 --v12 rows — 메인 결정 D-5)마다 반복 3회: 새 DB(V1~V13 — 앱 current로 한 번 띄워 Flyway 적용 뒤 앱 정지) → 인덱스 DROP →
#   예약 N행(CONFIRMED · 좌석마다 1행) 시드 · ANALYZE → ① 트랜잭션 안 CREATE UNIQUE INDEX(V12 파일과 같은 문장 — Flyway 기본 = 스크립트마다 트랜잭션 하나)를
#   세션 A로 돌리는 동안 300ms 뒤 세션 B가 예약 1행 INSERT(쓰기 하나) → B가 기다린 시간 = 쓰기 차단 길이 · A 시간 = 생성 시간 →
#   ② 인덱스 DROP 뒤 CREATE UNIQUE INDEX CONCURRENTLY(같은 B 탐침 — 막히지 않아야 한다) → 끝에 indisvalid 확인(둘 다).
#   결과 = <out>/v12-<N>-rep<r>.json 한 줄씩 → summarize.py --v12 <out>(판정: 가장 큰 N의 차단 최대 ≤ 2,000ms면 트랜잭션 안 판 유지 · 넘으면 CONCURRENTLY 판 후속).
#
#   scripts/v12-build.sh --sha <SHA> --images <images.json> --out <폴더> [--knobs <json>]   # 서버(.164) — run.sh와 같은 배포 · compose(회차 사이에 돌리지 않는다)
#   scripts/v12-build.sh --local-db <컨테이너> --out <폴더> [--rows N] [--reps k]            # 로컬 시험 — 이미 V1~V13이 적용된 로컬 PG 컨테이너에 직접(배포 · compose 없음)
#   scripts/v12-build.sh --dry-run [--knobs <json>]                                          # 계획 · SQL만 출력
set -uo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
SHA="" IMAGES="" OUT="" KNOBS="" LOCAL_DB="" ROWS_ARG="" REPS_ARG="" DRY=0
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;; --images) IMAGES="$2"; shift 2 ;; --out) OUT="$2"; shift 2 ;; --knobs) KNOBS="$2"; shift 2 ;;
    --local-db) LOCAL_DB="$2"; shift 2 ;; --rows) ROWS_ARG="$2"; shift 2 ;; --reps) REPS_ARG="$2"; shift 2 ;; --dry-run) DRY=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
KN=(); [[ -n "$KNOBS" ]] && KN=(--knobs "$KNOBS")
VP="$(python3 -I "$SRC/plan15.py" --v12 "${KN[@]}")" || exit 2
ROWS="${ROWS_ARG:-$(jq -r '.rows | join(" ")' <<< "$VP")}" REPS="${REPS_ARG:-$(jq -r .reps <<< "$VP")}"
PER=$(jq -r .seats_per_schedule <<< "$VP") PROBE_MS=$(jq -r .probe_after_ms <<< "$VP") STMT=$(jq -r .statement <<< "$VP") IDX=$(jq -r .index <<< "$VP")

seed_sql() {   # 인자: N — 좌석 N + 1,000(탐침 여분) · 예약 N(CONFIRMED · 좌석마다 1) · 인덱스는 지운 채로(빠른 시드 · 측정 대상 문장이 처음부터 만든다)
  local n="$1" sch=$(( ($1 + 1000 + PER - 1) / PER ))
  cat <<SQL
DROP INDEX IF EXISTS $IDX;
TRUNCATE reservation, seat_hold, product_seat, product_schedule, product, user_hold_quota RESTART IDENTITY;
INSERT INTO product (name) VALUES ('v12');
INSERT INTO product_schedule (product_id, starts_at) SELECT 1, TIMESTAMPTZ '2026-12-31 19:00:00+09' FROM generate_series(1, $sch);
INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status)
  SELECT 1 + (i - 1) / $PER, 'V', (i - 1) / 50 + 1, (i - 1) % 50 + 1, CASE WHEN i <= $n THEN 'RESERVED' ELSE 'AVAILABLE' END FROM generate_series(1, $n + 1000) i ORDER BY i;
INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at)
  SELECT 1 + (i - 1) / $PER, i, 7000000000 + i, 'v12-' || i, 'CONFIRMED', now() FROM generate_series(1, $n) i;
ANALYZE product_seat, reservation;
SELECT json_build_object('rows', (SELECT count(*) FROM reservation), 'table_bytes', pg_table_size('reservation'), 'seats', (SELECT count(*) FROM product_seat));
SQL
}
probe_sql() {   # 인자: 좌석 id — 쓰기 하나(예약 INSERT)의 시작 · 끝 시각(DB clock_timestamp) · 끝나면 지운다(다음 단계가 같은 상태에서)
  cat <<SQL
SELECT extract(epoch FROM clock_timestamp());
INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at)
  SELECT schedule_id, id, 7999999999, 'v12-probe', 'CONFIRMED', now() FROM product_seat WHERE id = $1;
SELECT extract(epoch FROM clock_timestamp());
DELETE FROM reservation WHERE payment_uid = 'v12-probe';
SQL
}
build_sql() {   # 인자: tx | concurrently
  if [[ "$1" == tx ]]; then printf 'BEGIN;\nSELECT extract(epoch FROM clock_timestamp());\n%s;\nSELECT extract(epoch FROM clock_timestamp());\nCOMMIT;\n' "$STMT"
  else printf 'SELECT extract(epoch FROM clock_timestamp());\n%s;\nSELECT extract(epoch FROM clock_timestamp());\n' "${STMT/CREATE UNIQUE INDEX/CREATE UNIQUE INDEX CONCURRENTLY}"; fi
}
valid_sql() { echo "SELECT json_build_object('valid', coalesce((SELECT i.indisvalid AND i.indisunique FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = '$IDX'), false));"; }
if (( DRY )); then
  echo "# v12-build 계획: 크기 [$ROWS] × 반복 $REPS · 탐침 ${PROBE_MS}ms 뒤 · 판정 문턱 $(jq -r .block_max_ms <<< "$VP")ms"
  echo "## 시드(N=1000 예)"; seed_sql 1000; echo "## 트랜잭션 안"; build_sql tx; echo "## CONCURRENTLY"; build_sql concurrently; echo "## 탐침(좌석 1001)"; probe_sql 1001; exit 0
fi
[[ -n "$OUT" ]] || { echo "--out 필수" >&2; exit 2; }
mkdir -p "$OUT"
# 계획한 크기 · 반복(요약기가 완전성을 대조한다 — 리뷰 M15-8: 관측 파일 중 가장 큰 N이 아니라 계획한 가장 큰 N으로 판정 · 빠진 자리는 판정 불가)
jq --arg rows "$ROWS" --argjson reps "$REPS" '. + {rows: ($rows | split(" ") | map(select(length > 0) | tonumber)), reps: $reps}' <<< "$VP" > "$OUT/v12-plan.json" \
  || { echo "v12-plan.json 쓰기 실패" >&2; exit 2; }
if [[ -n "$LOCAL_DB" ]]; then
  psql_run() { docker exec -i "$LOCAL_DB" psql -U seat -d seat -tAq -v ON_ERROR_STOP=1; }
  prepare_db() { :; }
  finish_db() { :; }
else
  [[ -n "$SHA" && -s "$IMAGES" ]] || { echo "--sha · --images 필수(서버)" >&2; exit 2; }
  export LIB9_DIR="$SRC/../../ADR-009/scripts" LIB14_SRC="$SRC/../../ADR-014/scripts" REPO_ROOT="${REPO_ROOT:-$(cd "$SRC/../../.." && pwd)}"
  source "$SRC/lib15.sh"; set +e
  SHA=$(git -C "$REPO_ROOT" rev-parse --short=8 "$SHA^{commit}") || exit 2
  APP_TAG="$SHA"; DEPLOY_ID="$SHA"
  APP_ENV14="$(python3 -I "$SRC/plan15.py" --render current cost 1 | jq -r '.app_env | to_entries | map("\(.key)=\(.value)") | join(" ")')"
  RES_ENV14="APP_CPUS=4 DB_CPUS=4 APP_MEM=2g APP_JVM_OPTS=-XX:+UseZGC APP_JVM_OPTS2=-XX:+ZGenerational APP_JVM_OPTS3= APP_JVM_OPTS4= PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=12g DB_MEM=14g"
  psql_run() { timeout 1800 ssh -o BatchMode=yes -o ConnectTimeout=5 "$SERVER" "docker exec -i seatlab-db-1 psql -U seat -d seat -tAq -v ON_ERROR_STOP=1"; }
  prepare_db() {   # 새 DB(V1~V13) → 앱 정지(다른 쓰기 없음 — 탐침 B만 쓴다)
    deploy_sha "$SHA" < /dev/null || return 1
    compose_down14 "$DEPLOY_ID" >> "$OUT/compose.log" 2>&1 < /dev/null || return 1
    [[ "$(image_id "seatlab-app:$APP_TAG")" == "$(jq -r .app.id "$IMAGES")" ]] || return 1
    compose_up14 "$DEPLOY_ID" >> "$OUT/compose.log" 2>&1 < /dev/null || return 1
    wait_health 240 || return 1
    remote "docker stop seatlab-app-1" >> "$OUT/compose.log" 2>&1 < /dev/null
  }
  finish_db() { compose_down14 "$DEPLOY_ID" >> "$OUT/compose.log" 2>&1 < /dev/null; }
fi
nums2() { grep -E '^[0-9]+(\.[0-9]+)?$' | head -2 | paste -sd' '; }   # 두 시각(epoch 초)
measure() {   # 인자: tx | concurrently 좌석 → {"build_ms", "probe_ms", "probe_blocked_ms"}
  local kind="$1" seat="$2" ta tb a0 a1 b0 b1 rc
  ta=$(mktemp) tb=$(mktemp)
  ( build_sql "$kind" | psql_run > "$ta" 2>&1 ) & local pa=$!
  sleep "$(awk -v m="$PROBE_MS" 'BEGIN { printf "%.3f", m / 1000 }')"
  ( probe_sql "$seat" | psql_run > "$tb" 2>&1 ) & local pb=$!
  wait "$pa"; rc=$?; wait "$pb"; rc=$(( rc + $? ))
  read -r a0 a1 <<< "$(nums2 < "$ta")"; read -r b0 b1 <<< "$(nums2 < "$tb")"
  if (( rc != 0 )) || [[ -z "$a1" || -z "$b1" ]]; then echo "{\"error\":$(jq -Rs . < "$ta" | head -c 2000),\"probe_error\":$(jq -Rs . < "$tb" | head -c 2000)}"; rm -f "$ta" "$tb"; return 1; fi
  rm -f "$ta" "$tb"
  # 탐침이 기다린 시간 = 탐침 문장 시간 · 차단 겹침 = 탐침 시작이 생성 구간 안이었나(아니면 탐침이 늦게 떠 측정 무효)
  awk -v a0="$a0" -v a1="$a1" -v b0="$b0" -v b1="$b1" 'BEGIN { printf "{\"build_ms\":%.1f,\"probe_ms\":%.1f,\"probe_started_inside\":%s,\"probe_blocked_ms\":%.1f}", (a1-a0)*1000, (b1-b0)*1000, (b0 >= a0 && b0 <= a1) ? "true" : "false", (b1-b0)*1000 }'
}
rc_all=0
for n in $ROWS; do
  for r in $(seq 1 "$REPS"); do
    f="$OUT/v12-$n-rep$r.json"; [[ -s "$f" ]] && { echo "있음 — 건너뜀 $f"; continue; }
    prepare_db || { echo "{\"rows\":$n,\"rep\":$r,\"error\":\"prepare-failed\"}" > "$f"; rc_all=1; continue; }
    # 시드 종료 코드 · 실측 행 수(리뷰 M15-7 — 시드가 일부 · 전부 실패해도 작은 표에서 생성 시간이 짧게 나와 '유지'로 가지 않게): pipefail이라 psql rc가 그대로 온다
    seed_out=$(seed_sql "$n" | psql_run 2>&1); seed_rc=$?
    seed=$(tail -n 1 <<< "$seed_out"); jq -e 'type == "object"' <<< "$seed" > /dev/null 2>&1 || seed=null
    (( seed_rc == 0 )) || { rc_all=1; echo "$(date -Is) v12 시드 실패 rows=$n rep=$r rc=$seed_rc: $(tail -c 500 <<< "$seed_out")" >&2; }
    tx=$(measure tx $(( n + 1 ))) || rc_all=1
    vtx=$(valid_sql | psql_run 2>/dev/null)
    echo "DROP INDEX $IDX;" | psql_run > /dev/null 2>&1
    cc=$(measure concurrently $(( n + 2 ))) || rc_all=1
    vcc=$(valid_sql | psql_run 2>/dev/null)
    jq -nc --argjson n "$n" --argjson r "$r" --arg at "$(date -Is)" --argjson seed "${seed:-null}" --argjson src "$seed_rc" --argjson tx "${tx:-null}" --argjson cc "${cc:-null}" \
          --argjson vtx "${vtx:-null}" --argjson vcc "${vcc:-null}" --arg stmt "$STMT" \
      '{rows: $n, rep: $r, at: $at, seed: $seed, seed_rc: $src, statement: $stmt, tx: ($tx + {valid: ($vtx.valid // false)}), concurrently: ($cc + {valid: ($vcc.valid // false)})}' > "$f" \
      || { echo "{\"rows\":$n,\"rep\":$r,\"error\":\"json\"}" > "$f"; rc_all=1; }
    cat "$f"
  done
done
finish_db
python3 -I "$SRC/summarize.py" --v12 "$OUT" || rc_all=1
exit "$rc_all"
