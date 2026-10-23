#!/usr/bin/env bash
# ADR-013 회차 실행기 — 회차 하나(절차 × 부하 × 회차 번호): 옛 앱 단독 → 시드 → 계획 사건 오케스트레이션(H3) → k6 끝 → 수렴 → 끝 검사 → 수집 → 판정(status · 사유).
#   설계: docs/adr/ADR-013-counter-config-cutover.md §4(특히 §4.1 시드 · §4.2 시각표·절차 · §4.4 적용판 · §4.5 락 표본 · §4.6 H1~H10 · §4.7) · §5(특히 §5.3 사유 · §5.8).
#   계획(시각표 · 단계 · 계획 사건 · 탐침 일정)의 단일 출처 = scripts/plan13.py. 판정(§5.3 사유 분류)의 단일 출처 = scripts/summarize.py --rep.
#   ADR-009 하네스(k6/ADR-009/scripts/lib.sh · scenarios · compose.yml · compose.tmpfs.yml)를 그대로 쓴다(수정하지 않는다 — 동결 사본에서 source).
#
#   scripts/run.sh --prepare-images --sha-old 93dd610a --sha-new <SHA> --out <images.json>
#       캠페인 전에 한 번: 두 SHA를 서버에 풀고 이미지를 빌드해 seatlab-app:<sha8>로 태그(이미 있으면 다시 빌드하지 않는다 — DS-11) ·
#       보조 이미지(flyway/flyway:11.7.2 · nginx:1.27-alpine · postgres:16 · redis:7-alpine)를 받아 둔다 → 이미지 ID를 images.json에 고정.
#   scripts/run.sh --sha <하네스 SHA> --images <images.json> --proc 1..5 --load S4c|S3e --rep <k> --id <캠페인 id> [--attempt n] [--worktree]
#       [--k6-bin k6] [--pg-tmpfs-size 12] [--app-mem 2g] [--app-gc zgc] [--stmt-timeout 600s] [--dry-run]
#     --sha-old 기본 93dd610a · --sha-new 기본 = --sha. 결과 = results/<id>/<부하>/p<절차>/rep<k>/ · --attempt n(>1)은 앞 시도를 rep<k>.attempt<n−1>로 옮긴다(재측정 — 결과 표 'rerun').
#     --worktree(스모크 전용): 앱 이미지는 고정 이미지 그대로, 하네스(k6/ADR-013 · ops/online-v9 · k6/ADR-009)만 작업트리에서.
#     --dry-run: 계획·명령만 출력(서버 접속 없음).
#   status: ok · invalid:<사유,…>(하네스 — 무효 → 재측정) · fail:<사유,…>(절차 원인 — 유효 실패 회차 · P10 반증). 사유 표 = README.
set -uo pipefail

if [[ "${RUN13_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"
  frozen="$(mktemp -d)"
  mkdir -p "$frozen/k6/ADR-013" "$frozen/k6/ADR-009" "$frozen/ops"
  cp -r "$src" "$frozen/k6/ADR-013/scripts" && cp -r "$repo/k6/ADR-009/scripts" "$frozen/k6/ADR-009/scripts" && cp -r "$repo/ops/online-v9" "$frozen/ops/online-v9" \
    || { echo "동결 사본 실패" >&2; exit 2; }
  export RUN13_FROZEN=1 FROZEN13="$frozen" ADR13_DIR="${ADR13_DIR:-$(cd "$src/.." && pwd)}" REPO_ROOT="${REPO_ROOT:-$repo}"
  exec bash "$frozen/k6/ADR-013/scripts/run.sh" "$@"
fi
export LIB9_DIR="$FROZEN13/k6/ADR-009/scripts" OPS_DIR="$FROZEN13/ops/online-v9"
S13="$FROZEN13/k6/ADR-013/scripts"
DRY=0; for a in "$@"; do [[ "$a" == --dry-run ]] && DRY=1; done
source "$S13/lib13.sh"
set +e

SHA="" SHA_OLD=93dd610a SHA_NEW="" IMAGES="" PROC="" LOAD="" REP="" CID="" ATTEMPT=1 WORKTREE=0 K6_BIN=k6 PG_TMPFS_GB=12 APP_MEM=2g APP_GC=zgc
STMT_TIMEOUT=600s PREPARE=0 IMG_OUT="" PRE_K6_HOLD_S="${PRE_K6_HOLD_S:-70}" CALIB="" CALIB_PAIRS=""
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --sha-old) SHA_OLD="$2"; shift 2 ;;
    --sha-new) SHA_NEW="$2"; shift 2 ;;
    --images) IMAGES="$2"; shift 2 ;;
    --proc) PROC="$2"; shift 2 ;;
    --load) LOAD="$2"; shift 2 ;;
    --rep) REP="$2"; shift 2 ;;
    --id) CID="$2"; shift 2 ;;
    --attempt) ATTEMPT="$2"; shift 2 ;;
    --worktree) WORKTREE=1; shift ;;
    --k6-bin) K6_BIN="$2"; shift 2 ;;
    --pg-tmpfs-size) PG_TMPFS_GB="$2"; shift 2 ;;
    --app-mem) APP_MEM="$2"; shift 2 ;;
    --app-gc) APP_GC="$2"; shift 2 ;;
    --stmt-timeout) STMT_TIMEOUT="$2"; shift 2 ;;
    --prepare-images) PREPARE=1; shift ;;
    --calibrate) CALIB="$2"; shift 2 ;;
    --pairs) CALIB_PAIRS="$2"; shift 2 ;;
    --out) IMG_OUT="$2"; shift 2 ;;
    --dry-run) shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
cleanup13() {
  [[ "$BASHPID" == "$$" ]] || return 0
  [[ -n "${BG_PIDS:-}" ]] && for p in $BG_PIDS; do pkill -P "$p" 2>/dev/null; kill "$p" 2>/dev/null; done
  [[ -n "${K6_TREE:-}" && -d "${K6_TREE:-}" ]] && rm -rf "$K6_TREE"
  [[ -n "${FROZEN13:-}" && "$FROZEN13" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "$FROZEN13"
}
trap cleanup13 EXIT
short() { git -C "$REPO_ROOT" rev-parse --short=8 "$1^{commit}" 2>/dev/null; }

# ---- 이미지 준비(H1 · DS-11 — 캠페인 전에 한 번) -----------------------------------------------------------------------------------
if (( PREPARE )); then
  [[ -n "$SHA_NEW" && -n "$IMG_OUT" ]] || { echo "--prepare-images는 --sha-new · --out이 필요하다" >&2; exit 2; }
  o8=$(short "$SHA_OLD") n8=$(short "$SHA_NEW"); [[ -n "$o8" && -n "$n8" ]] || { echo "SHA 해석 실패" >&2; exit 2; }
  (( DRY )) && { echo "deploy $o8 $n8 → build seatlab-app:$o8 · seatlab-app:$n8(없을 때만) → pull $FLYWAY_IMAGE nginx:1.27-alpine postgres:16 redis:7-alpine → $IMG_OUT"; exit 0; }
  deploy_sha "$o8" && deploy_sha "$n8" || { echo "배포 실패" >&2; exit 1; }
  for x in "$o8" "$n8"; do
    if [[ -n "$(image_id "seatlab-app:$x")" ]]; then log "seatlab-app:$x 이미 있음 — 다시 빌드하지 않는다(DS-11)"
    else remote "cd $REMOTE_BASE/$x && docker build -q -t seatlab-app:$x --label org.seatlab.sha=$x ." >&2 || { echo "빌드 실패 $x" >&2; exit 1; }; fi
  done
  for x in "$FLYWAY_IMAGE" nginx:1.27-alpine postgres:16 redis:7-alpine; do
    [[ -n "$(image_id "$x")" ]] || remote "docker pull -q $x" >&2 || { echo "받기 실패 $x" >&2; exit 1; }
  done
  jq -n --arg o "$o8" --arg n "$n8" --arg oi "$(image_id "seatlab-app:$o8")" --arg ni "$(image_id "seatlab-app:$n8")" \
        --arg fw "$FLYWAY_IMAGE" --arg fwi "$(image_id "$FLYWAY_IMAGE")" --arg ngi "$(image_id nginx:1.27-alpine)" \
        --arg pgi "$(image_id postgres:16)" --arg rdi "$(image_id redis:7-alpine)" --arg at "$(date -Is)" \
    '{pinned_at: $at, old: {sha: $o, tag: ("seatlab-app:" + $o), id: $oi}, new: {sha: $n, tag: ("seatlab-app:" + $n), id: $ni},
      aux: {($fw): $fwi, "nginx:1.27-alpine": $ngi, "postgres:16": $pgi, "redis:7-alpine": $rdi}}' > "$IMG_OUT"
  jq -e '[.old.id, .new.id, (.aux[])] | all(test("^sha256:"))' "$IMG_OUT" > /dev/null || { echo "이미지 ID를 다 얻지 못했다 — $IMG_OUT" >&2; exit 1; }
  cat "$IMG_OUT"; exit 0
fi

# ---- 인자 검사 · 하네스 = 기록되는 SHA -------------------------------------------------------------------------------------------------
if [[ -n "$CALIB" ]]; then   # 보정(§4.1 DS-12): 절차 없이 옛 앱 단독 — 회차 자리는 계획 렌더용(S4c · 절차 3 — 사건은 setup만 쓴다)
  [[ "$CALIB" =~ ^(seed|load)$ ]] || { echo "--calibrate seed|load" >&2; exit 2; }
  [[ "$CALIB" == load || "$CALIB_PAIRS" =~ ^[1-9][0-9]*$ ]] || { echo "--calibrate seed는 --pairs <load 보정 DB의 쿼터 행 수>가 필요하다(같은 쌍 수로 비교)" >&2; exit 2; }
  PROC=3 LOAD=S4c REP=1
fi
[[ "$PROC" =~ ^[1-5]$ && "$LOAD" =~ ^(S4c|S3e)$ && "$REP" =~ ^[1-9][0-9]*$ && "$ATTEMPT" =~ ^[1-9]$ && -n "$CID" ]] || { echo "--proc 1..5 --load S4c|S3e --rep k --id <id> 필수" >&2; exit 2; }
[[ "$PG_TMPFS_GB" =~ ^[1-9][0-9]?$ && "$APP_MEM" =~ ^[1-9][0-9]*[mg]$ && "$APP_GC" =~ ^(auto|g1|zgc)$ && "$STMT_TIMEOUT" =~ ^[0-9]+s$ ]] || { echo "손잡이 형식" >&2; exit 2; }
SHA="$(short "${SHA:-HEAD}")" || exit 2; SHA_OLD8="$(short "$SHA_OLD")"; SHA_NEW8="$(short "${SHA_NEW:-$SHA}")"
[[ -n "$SHA" && -n "$SHA_OLD8" && -n "$SHA_NEW8" ]] || { echo "SHA 해석 실패" >&2; exit 2; }
HPATHS=(k6/ADR-013 ops/online-v9 k6/ADR-009/scripts k6/ADR-009/scenarios k6/ADR-009/compose.yml k6/ADR-009/compose.tmpfs.yml)
worktree_hash13() { (cd "$REPO_ROOT" && find "${HPATHS[@]}" -path k6/ADR-013/results -prune -o -name __pycache__ -prune -o -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -c1-8); }
HARNESS="sha:$SHA" DEPLOY_ID="$SHA"
if (( WORKTREE )); then
  [[ "${CAMPAIGN13:-0}" != 1 ]] || { echo "--worktree는 스모크 전용" >&2; exit 2; }
  HARNESS="worktree:$(worktree_hash13)"; DEPLOY_ID="$SHA-wt13-${HARNESS#worktree:}"
elif ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- "${HPATHS[@]}" ':(exclude)k6/ADR-013/results' \
     || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- "${HPATHS[@]}" ':(exclude)k6/ADR-013/results')" ]]; then
  echo "하네스(${HPATHS[*]})가 $SHA와 다르다 — 커밋하거나 --worktree(스모크)" >&2; (( DRY )) || exit 2
fi
PLAN_JSON="$(python3 -I "$S13/plan13.py" --render "$PROC" "$LOAD")" || exit 2
LC() { jq -r ".load_conf.$1" <<< "$PLAN_JSON"; }
K6_S=$(jq -r .timeline.k6_s <<< "$PLAN_JSON"); CONV_S=$(jq -r .timeline.converge_s <<< "$PLAN_JSON"); PROBE_SCH=$(LC probe_schedule)
COMMON_ENV="HOLD_TTL=$(LC ttl) EXPIRY_INTERVAL=$(LC expiry) HOLD_STRATEGY=pessimistic-nowait POOL_SIZE=10 APP_CPUS=4 DB_CPUS=4 LB_CPUS=1 APP_MEM=$APP_MEM \
APP_JVM_OPTS=$(case "$APP_GC" in g1) echo -XX:+UseG1GC ;; zgc) echo -XX:+UseZGC ;; esac) APP_JVM_OPTS2=$([[ "$APP_GC" == zgc ]] && echo -XX:+ZGenerational) APP_JVM_OPTS3= APP_JVM_OPTS4= \
PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=${PG_TMPFS_GB}g DB_MEM=$((PG_TMPFS_GB + 2))g"
COMPOSE_TMPFS="-f k6/ADR-009/compose.tmpfs.yml"
SLOT_A_TAG=$SHA_OLD8 SLOT_A_APPNAME="seatlab-A-$SHA_OLD8-old" SLOT_A_SHADOW="" SLOT_B_TAG=$SHA_NEW8 SLOT_B_APPNAME="seatlab-B-$SHA_NEW8-new" SLOT_B_SHADOW=""
if (( DRY )); then
  echo "# dry-run — 절차 $PROC · $LOAD · 회차 $REP · 하네스 $HARNESS · 옛 $SHA_OLD8 · 새 $SHA_NEW8"
  echo "compose: $(compose13)"; echo "env: $(slot_env)"
  jq -r '.steps[] | "\(.name) @\(.at_s)s: " + ([.actions[] | .op + (if .slot then ":" + .slot else "" end) + (if .role then "(" + .role + ")" else "" end)] | join(" → "))' <<< "$PLAN_JSON"
  jq -r '"끝 상태: alive \(.end_state.alive) · lb \(.end_state.lb) · 탐침 \(.probes | length)개 · 가용성 탐침 \(.avail.rate)/s 좌석 \(.avail.seat_base + 1)~"' <<< "$PLAN_JSON"
  exit 0
fi
[[ -s "$IMAGES" ]] || { echo "--images <images.json>(run.sh --prepare-images) 필수" >&2; exit 2; }
[[ "$(jq -r .old.sha "$IMAGES")" == "$SHA_OLD8" && "$(jq -r .new.sha "$IMAGES")" == "$SHA_NEW8" ]] || { echo "images.json의 SHA가 --sha-old/--sha-new와 다르다" >&2; exit 2; }

OUT_ROOT="$ADR13_DIR/results/$CID"; DIR="$OUT_ROOT/$LOAD/p$PROC/rep$REP"
[[ -n "$CALIB" ]] && DIR="$OUT_ROOT/calibrate-$CALIB"
if [[ -e "$DIR" ]]; then
  if (( ATTEMPT > 1 )) && [[ ! -e "$DIR.attempt$((ATTEMPT - 1))" ]]; then mv "$DIR" "$DIR.attempt$((ATTEMPT - 1))"
  else echo "$DIR 이미 있다 — 덮어쓰지 않는다(재측정은 --attempt n)" >&2; exit 2; fi
fi
mkdir -p "$DIR"; MATRIX_LOG="$OUT_ROOT/MATRIX.log"
echo "$PLAN_JSON" | jq . > "$DIR/plan.json"
cp "$IMAGES" "$DIR/images.json"
STARTED=$(date -Is)
K6_TREE="$(mktemp -d)"
if (( WORKTREE )); then mkdir -p "$K6_TREE/k6/ADR-009" "$K6_TREE/k6/ADR-013" && cp -r "$REPO_ROOT/k6/ADR-009/scenarios" "$K6_TREE/k6/ADR-009/" && cp -r "$REPO_ROOT/k6/ADR-013/scenarios" "$K6_TREE/k6/ADR-013/"
else git -C "$REPO_ROOT" archive "$SHA" k6/ADR-009/scenarios k6/ADR-013/scenarios | tar -x -C "$K6_TREE"; fi
SCEN9="$K6_TREE/k6/ADR-009/scenarios" SCEN13="$K6_TREE/k6/ADR-013/scenarios"
HREASONS="$DIR/harness-reasons.txt"; : > "$HREASONS"
hreason() { echo "$1" >> "$HREASONS"; echo "$(date -Is) harness: $1" >> "$DIR/errors.log"; }
finish13() {   # 판정(summarize --rep) → status · meta. 인자: (선택) 강제 상태
  local st="${1:-}"
  jq -n --arg sha "$SHA" --arg old "$SHA_OLD8" --arg new "$SHA_NEW8" --arg harness "$HARNESS" --argjson proc "$PROC" --arg load "$LOAD" --argjson rep "$REP" \
        --argjson attempt "$ATTEMPT" --arg started "$STARTED" --arg ended "$(date -Is)" --arg k6 "$("$K6_BIN" version 2>/dev/null | head -1)" \
        --argjson t0 "${T0:-null}" --argjson k6end "${K6_END:-null}" --arg env "$COMMON_ENV" --arg tmpfs "$PG_TMPFS_GB" --arg stmt "$STMT_TIMEOUT" \
    '{sha: $sha, sha_old: $old, sha_new: $new, harness: $harness, proc: $proc, load: $load, rep: $rep, attempt: $attempt, rerun: ($attempt > 1),
      started: $started, ended: $ended, k6_version: $k6, t0_pc: $t0, k6_end_pc: $k6end, common_env: $env, pg_tmpfs_gb: ($tmpfs | tonumber), stmt_timeout: $stmt}' > "$DIR/meta.json.tmp" \
    && mv "$DIR/meta.json.tmp" "$DIR/meta.json" || { hreason meta-write-failed; st="invalid:meta-write-failed"; }
  if [[ -z "$st" ]]; then st=$(python3 -I "$S13/summarize.py" --rep "$DIR" 2>>"$DIR/errors.log" | tail -1); [[ "$st" =~ ^(ok|invalid:|fail:) ]] || st="invalid:summarize-failed"; fi
  echo "$st" > "$DIR/status"
  echo "$(date -Is) $LOAD p$PROC rep$REP attempt$ATTEMPT $st" >> "$MATRIX_LOG"
  log "$LOAD p$PROC rep$REP → $st"
}
abort13() { hreason "$1"; finish13 "invalid:$1"; exit 0; }

# ---- 사건 기록(H3 — events.jsonl: PC epoch · 서버 epoch(시계 차) · 사건 · 슬롯 · 생애 · SHA · 역할 · 결과 · 걸린 시간 · 계획 대비 지연) -------------------------------
OFF=0   # 서버 − PC(초). k6 전에 잰 값으로 바꾼다
declare -A LIFE=([A]=0 [B]=0) ROLE=([A]="" [B]="") ALIVE=([A]=0 [B]=0) IP=() SHA_OF=([A]="" [B]="")
LB_SET=""; LAST_EFF="" LAST_BYTES=""
lname() { echo "$1${LIFE[$1]}"; }
ev() {   # 인자: step op slot planned_at t0 t1 result [cause] [detail-json]
  local line
  line=$(jq -nc --arg step "$1" --arg op "$2" --arg slot "$3" --arg life "$( [[ -n "$3" ]] && lname "$3")" --arg role "$( [[ -n "$3" ]] && echo "${ROLE[$3]:-}")" \
        --arg sha "$( [[ -n "$3" ]] && echo "${SHA_OF[$3]:-}")" --argjson pa "${4:-null}" --argjson t0 "$5" --argjson t1 "$6" --arg res "$7" --arg cause "${8:-}" \
        --argjson det "${9:-null}" --argjson off "$OFF" --argjson T0 "${T0:-null}" \
    '{kind: "plan", step: $step, op: $op, slot: (if $slot == "" then null else $slot end), life: (if $life == "" then null else $life end), role: $role, sha: $sha,
      planned_at_s: $pa, start_pc: $t0, end_pc: $t1, start_srv: ($t0 + $off), end_srv: ($t1 + $off), dur_s: ($t1 - $t0),
      rel0: (if $T0 then $t0 - $T0 else null end), rel1: (if $T0 then $t1 - $T0 else null end),
      late_s: (if $T0 and $pa != null then ([0, $t0 - $T0 - $pa] | max) else null end), result: $res,
      cause: (if $cause == "" then null else $cause end), detail: $det}') && printf '%s\n' "$line" >> "$DIR/events.jsonl" \
    || echo "$(date -Is) event-write-failed $1 $2 $3" >> "$DIR/.event-write-failed"
}
evx() { jq -nc --arg kind "$1" --arg op "$2" --argjson t "$(date +%s.%N)" --argjson off "$OFF" --argjson det "${3:-null}" '{kind: $kind, op: $op, t_pc: $t, t_srv: ($t + $off), detail: $det}' >> "$DIR/events.jsonl"; }
TFAIL="$DIR/transition-failed.json"
tfail() {   # 인자: 원인(harness|procedure) 사유 op slot 상세 — 첫 실패만 남긴다(뒤 사건은 그 결과)
  [[ -s "$TFAIL" ]] || jq -n --arg c "$1" --arg r "$2" --arg op "$3" --arg slot "$4" --arg d "$5" '{cause: $c, reason: $r, op: $op, slot: $slot, detail: $d}' > "$TFAIL"
}
now() { date +%s.%N; }

# ---- 사건(행동) — 성공 0 · 실패 1(원인은 tfail) ----------------------------------------------------------------------------------------
IMG_OLD_ID=$(jq -r .old.id "$IMAGES") IMG_NEW_ID=$(jq -r .new.id "$IMAGES")
do_start() {   # 슬롯 역할
  local s="$1" role="$2" t0 t1 tag want got
  t0=$(now)
  [[ "$role" == old ]] && { tag=$SHA_OLD8; want=$IMG_OLD_ID; } || { tag=$SHA_NEW8; want=$IMG_NEW_ID; }
  got=$(image_id "seatlab-app:$tag")
  if [[ "$got" != "$want" ]]; then   # N1 · DS-11 — 기동 직전 슬롯 이미지 ID = plan 값
    ev "$STEP" start "$s" "$PA" "$t0" "$(now)" fail harness "$(jq -nc --arg g "$got" --arg w "$want" '{reason: "cond-image-mismatch", got: $g, want: $w}')"
    tfail harness cond-image-mismatch start "$s" "seatlab-app:$tag $got ≠ $want"; return 1
  fi
  LIFE[$s]=$(( LIFE[$s] + 1 )); ROLE[$s]="$role"; SHA_OF[$s]="$tag"
  if [[ "$s" == A ]]; then SLOT_A_TAG=$tag SLOT_A_APPNAME="seatlab-A-$tag-$role" SLOT_A_SHADOW=$([[ "$role" == shadow ]] && echo 1)
  else SLOT_B_TAG=$tag SLOT_B_APPNAME="seatlab-B-$tag-$role" SLOT_B_SHADOW=$([[ "$role" == shadow ]] && echo 1); fi
  if ! slot_up "$DEPLOY_ID" "$s" >> "$DIR/compose.log" 2>&1; then
    ev "$STEP" start "$s" "$PA" "$t0" "$(now)" fail harness '{"reason":"compose-up-failed"}'; tfail harness compose-up-failed start "$s" ""; return 1
  fi
  t1=$(now); ALIVE[$s]=1
  local img; img=$(ctr_image "$(ctr_of "$s")")
  ev "$STEP" start "$s" "$PA" "$t0" "$t1" ok "" "$(jq -nc --arg img "$img" --arg app "$( [[ $s == A ]] && echo "$SLOT_A_APPNAME" || echo "$SLOT_B_APPNAME")" '{image: $img, app_name: $app}')"
}
do_wait_health() {   # 슬롯 시한
  local s="$1" lim="$2" t0; t0=$(now)
  if wait_health_url "$(url_of "$s")" "$lim"; then ev "$STEP" wait-health "$s" "$PA" "$t0" "$(now)" ok; return 0; fi
  # 절차 원인(§5.3): health 시한 초과 · Flyway 실패 — 로그 끝을 남긴다
  remote "docker logs --tail 200 $(ctr_of "$s") 2>&1" > "$DIR/app-$(lname "$s")-health-fail.log" 2>&1
  ev "$STEP" wait-health "$s" "$PA" "$t0" "$(now)" fail procedure "$(jq -nc --argjson l "$lim" '{reason: "health-timeout", limit_s: $l}')"
  tfail procedure health-timeout wait-health "$s" "시한 ${lim}초"; return 1
}
do_config_check() {   # 슬롯 — H9(기대 설정 대조 — cond-config-mismatch는 하네스)
  local s="$1" t0 f out rc; t0=$(now); f="$DIR/app-$(lname "$s")-config.json"
  curl -fsS --max-time 10 "$(url_of "$s")/actuator/configprops" 2>/dev/null \
    | jq -c '{configprops: [.contexts[].beans[] | select(.prefix | test("^(seat\\.hold|spring\\.flyway)$")) | {prefix, properties}]}' > "$f" 2>/dev/null
  out=$(python3 -I "$S13/plan13.py" --config-check "${ROLE[$s]}" "$f" 2>&1); rc=$?
  if (( rc == 0 )); then ev "$STEP" config-check "$s" "$PA" "$t0" "$(now)" ok; return 0; fi
  ev "$STEP" config-check "$s" "$PA" "$t0" "$(now)" fail harness "$(jq -nc --arg o "$out" '{reason: "cond-config-mismatch", detail: $o}')"
  tfail harness cond-config-mismatch config-check "$s" "$out"; return 1
}
lb_apply() {   # 인자: step op 슬롯 새구성원(서비스 이름 목록) → LAST_EFF · LAST_BYTES
  local step="$1" op="$2" s="$3" members="$4" t0 out rc; t0=$(now)
  out=$(lb_members $members 2>>"$DIR/errors.log"); rc=$?
  if (( rc != 0 )); then
    local why=reload-not-effective; jq -e '.test_rc == 3' <<< "$out" > /dev/null 2>&1 && why=nginx-t-failed
    ev "$step" "$op" "$s" "$PA" "$t0" "$(now)" fail harness "$(jq -nc --arg w "$why" --arg o "$out" '{reason: $w, out: $o}')"
    tfail harness "$why" "$op" "$s" "$out"; return 1   # DS-9 ⓑ — reload 실패·nginx -t 실패는 무음이 아니다
  fi
  LB_SET="$members"; LAST_EFF=$(jq -r .effective <<< "$out"); LAST_BYTES=$(jq -r .log_bytes <<< "$out")
  ev "$step" "$op" "$s" "$PA" "$t0" "$(now)" ok "" "$(jq -c --arg ip "${IP[$s]:-}" '. + {ip: $ip}' <<< "$out")"
}
do_lb_add() {
  local s="$1"; IP[$s]=$(ctr_ip "$(ctr_of "$s")")   # 그 생애의 컨테이너 IP(H2 ⓒ — 비움 확인 · 접근 기록 대조)
  lb_apply "$STEP" lb-add "$s" "$(echo "$LB_SET $(svc_of "$s")" | xargs -n1 | sort -u | xargs)"
}
do_lb_remove() {
  local s="$1"; lb_apply "$STEP" lb-remove "$s" "$(echo "$LB_SET" | xargs -n1 | grep -vx "$(svc_of "$s")" | xargs)"
}
do_drain() {   # 슬롯 — 비움 5초 → 빼기 효력 뒤 그 주소로 시작한 요청 0 · 진행 중 0(최대 5초 × 2번 더) — 아니면 transition-failed(원인 분류 §5.3)
  local s="$1" t0 n inf k=0 det; t0=$(now)
  sleep "$(jq -r .fixed.drain_s "$DIR/plan.json")"
  while :; do
    sleep 1.2   # 접근 기록 비움 주기(flush=1s) 뒤에 읽는다
    n=$(lb_started_after "${IP[$s]}" "$LAST_EFF" "$LAST_BYTES"); inf=$(app_inflight "$(url_of "$s")")
    det=$(jq -nc --arg n "$n" --arg inf "$inf" --argjson k "$k" --arg ip "${IP[$s]}" --arg eff "$LAST_EFF" \
          '{started_after: ($n | tonumber? // null), inflight: ($inf | tonumber? // null), extra_rounds: $k, ip: $ip, effective: ($eff | tonumber? // null)}')
    if [[ "$n" == 0 ]] && [[ -z "$inf" || "$inf" == 0 ]]; then ev "$STEP" drain "$s" "$PA" "$t0" "$(now)" ok "" "$det"; return 0; fi
    (( k >= $(jq -r .fixed.drain_extra_max "$DIR/plan.json") )) && break
    k=$((k + 1)); sleep "$(jq -r .fixed.drain_extra_s "$DIR/plan.json")"
  done
  local lc cause=harness; lc=$(drain_lock_cause "$( [[ $s == A ]] && echo "$SLOT_A_APPNAME" || echo "$SLOT_B_APPNAME")")
  jq -e '.blocked_by_migration > 0' <<< "$lc" > /dev/null 2>&1 && cause=procedure   # 남은 요청이 마이그레이션·적용판 세션 락에 막힘 = 절차
  ev "$STEP" drain "$s" "$PA" "$t0" "$(now)" fail "$cause" "$(jq -c --argjson lc "${lc:-null}" '. + {reason: "drain-incomplete", lock: $lc}' <<< "$det")"
  tfail "$cause" drain-incomplete drain "$s" "$det"; return 1
}
do_collect() {   # 슬롯 — H4(내리기 직전). 수집 실패는 회차를 멈추지 않는다(사건 detail · 요약기가 옛 앱 로그 결측을 하네스 사유로)
  local s="$1" t0 errs; t0=$(now)
  errs=$(app_collect "$s" "$(lname "$s")" "$DIR" | xargs)
  ev "$STEP" collect "$s" "$PA" "$t0" "$(now)" ok "" "$(jq -nc --arg e "$errs" '{errors: ($e | split(" ") | map(select(length > 0)))}')"
}
do_stop() {
  local s="$1" t0; t0=$(now)
  if slot_stop "$DEPLOY_ID" "$s" >> "$DIR/compose.log" 2>&1; then ALIVE[$s]=0; ev "$STEP" stop "$s" "$PA" "$t0" "$(now)" ok
  else ev "$STEP" stop "$s" "$PA" "$t0" "$(now)" fail harness '{"reason":"stop-failed"}'; tfail harness stop-failed stop "$s" ""; return 1; fi
  # §4.7.2 전환 경계 표본(S3e만): 옛 앱(첫 생애)이 내려간 직후 끝 불일치 수 — 새 앱이 넘겨받는 양(기록)
  if [[ "$LOAD" == S3e && "${ROLE[$s]}" == old ]]; then
    db_json <<< "WITH a AS (SELECT schedule_id, user_id, sum(n) AS total FROM (SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2 UNION ALL SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2) u GROUP BY 1, 2)
      SELECT json_build_object('t_db', extract(epoch FROM clock_timestamp()), 'mismatch', (SELECT count(*) FROM user_hold_quota q FULL JOIN a USING (schedule_id, user_id) WHERE coalesce(q.cnt, 0) <> coalesce(a.total, 0) AND schedule_id = 1));" \
      > "$DIR/t2-mismatch.json" 2>>"$DIR/errors.log"
  fi
}
do_pre_apply() {   # §4.4 적용판(④⑤) — 실패 = 절차 원인(pre-apply-failed)
  local t0 rc; t0=$(now)
  bash "$OPS_DIR/apply.sh" --psql "ssh -o BatchMode=yes -o ConnectTimeout=10 $SERVER docker exec -i -e PGAPPNAME=seatlab-online-v9 seatlab-db-1 psql -X -q -U seat -d seat" \
    --flyway "$(flyway_run_cmd "$SHA_NEW8")" --ref-catalog "$DIR/ref-catalog.json" --ref-history "$DIR/ref-history.json" --out "$DIR/pre-apply.json" \
    --stmt-timeout "$STMT_TIMEOUT" >> "$DIR/pre-apply.log" 2>&1; rc=$?
  if (( rc == 0 )); then ev "$STEP" pre-apply "" "$PA" "$t0" "$(now)" ok "" "$(jq -c '{method: .record.method, wall_s, m}' "$DIR/pre-apply.json" 2>/dev/null || echo null)"; return 0; fi
  ev "$STEP" pre-apply "" "$PA" "$t0" "$(now)" fail procedure "$(jq -c '{reason: "pre-apply-failed", fail_step, why: .reason}' "$DIR/pre-apply.json" 2>/dev/null || echo '{"reason":"pre-apply-failed"}')"
  tfail procedure pre-apply-failed pre-apply "" "rc=$rc"; return 1
}
run_action() {   # 인자: op 슬롯 역할
  case "$1" in
    start) do_start "$2" "$3" ;;
    wait-health) do_wait_health "$2" "$( [[ "$STEP" == setup ]] && jq -r .fixed.health_rep_start_s "$DIR/plan.json" || jq -r .fixed.health_in_rep_s "$DIR/plan.json")" ;;
    config-check) do_config_check "$2" ;;
    lb-add) do_lb_add "$2" ;;
    lb-remove) do_lb_remove "$2" ;;
    drain) do_drain "$2" ;;
    collect) do_collect "$2" ;;
    stop) do_stop "$2" ;;
    pre-apply) do_pre_apply ;;
    *) echo "모르는 사건 $1" >> "$DIR/errors.log"; return 1 ;;
  esac
}
run_step() {   # 인자: 단계 JSON
  local st="$1" n i a
  STEP=$(jq -r .name <<< "$st"); PA=$(jq -r '.at_s // "null"' <<< "$st"); n=$(jq '.actions | length' <<< "$st")
  for (( i = 0; i < n; i++ )); do
    a=$(jq -c ".actions[$i]" <<< "$st")
    run_action "$(jq -r .op <<< "$a")" "$(jq -r '.slot // ""' <<< "$a")" "$(jq -r '.role // ""' <<< "$a")" || return 1
  done
}
state_json() {
  jq -nc --arg a "${ALIVE[A]}" --arg b "${ALIVE[B]}" --arg lb "$LB_SET" --arg la "$(lname A)" --arg lbb "$(lname B)" --arg ra "${ROLE[A]}" --arg rb "${ROLE[B]}" \
    '{kind: "end-state", alive: ([(if $a == "1" then "A" else empty end), (if $b == "1" then "B" else empty end)]),
      lb: ($lb | split(" ") | map(select(length > 0) | if . == "app" then "A" else "B" end)), lives: {A: $la, B: $lbb}, roles: {A: $ra, B: $rb}}'
}

# ---- 촘촘한 락 표본 창(§4.5-2) — 한 프로세스로 [첫 창 시작, 마지막 창 끝]을 덮는다(창이 겹치면 이어진다 · 표본 결측은 요약기가 ADR 창 기준으로 센다) ------------
MLOCK_PID=""
mlock_on() { [[ -n "$MLOCK_PID" ]] && return 0; ( mlock_sampler 2>/dev/null | while IFS= read -r l; do printf '%s\n' "$l" >> "$DIR/timeline-migration-locks.jsonl"; done ) & MLOCK_PID=$!; evx sampler mlock-start; }
mlock_off() { [[ -z "$MLOCK_PID" ]] && return 0; pkill -P "$MLOCK_PID" 2>/dev/null; kill "$MLOCK_PID" 2>/dev/null; MLOCK_PID=""; evx sampler mlock-stop; }

# ---- 오케스트레이터(H3 — k6와 나란히 · 계획표를 시각대로) ------------------------------------------------------------------------------------
orchestrate() {
  local nsteps i st at first_win
  nsteps=$(jq '.steps | length' "$DIR/plan.json")
  first_win=$(jq -r --argjson b "$(jq -r .fixed.mlock_before_s "$DIR/plan.json")" '[.steps[] | select(.name == "pre" or .name == "T1") | .at_s - $b] | min' "$DIR/plan.json")
  sleep "$(awk -v t="$T0" -v a="$first_win" -v n="$(now)" 'BEGIN { d = t + a - n; print (d > 0 ? d : 0) }')"; mlock_on
  for (( i = 0; i < nsteps; i++ )); do
    st=$(jq -c ".steps[$i]" "$DIR/plan.json"); at=$(jq -r .at_s <<< "$st")
    sleep "$(awk -v t="$T0" -v a="$at" -v n="$(now)" 'BEGIN { d = t + a - n; print (d > 0 ? d : 0) }')"
    if ! run_step "$st"; then break; fi
  done
  # 표본 창 끝 = 마지막 단계 끝 + 30초(ADR 창 '마지막 lb 넣기 + 30초'·적용판 끝 + 30초를 덮는다 — 더 넓은 쪽)
  sleep "$(jq -r .fixed.mlock_after_s "$DIR/plan.json")"; mlock_off
  state_json >> "$DIR/events.jsonl"
  state_json > "$DIR/.orch-state.json"
}

# ---- 회차 시작: 내리기 · 이미지 확인 · 옛 앱 단독 기동(H6) --------------------------------------------------------------------------------
log "ADR-013 $LOAD p$PROC rep$REP attempt$ATTEMPT → $DIR"
if (( WORKTREE )); then
  remote "test -f $REMOTE_BASE/$DEPLOY_ID/.deployed" 2>/dev/null || {
    git -C "$REPO_ROOT" archive --format=tar "$SHA" -- . ":(exclude,glob)k6/*/results/**" | remote "rm -rf $REMOTE_BASE/.tmp-$DEPLOY_ID && mkdir -p $REMOTE_BASE/.tmp-$DEPLOY_ID && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID" \
    && tar -C "$REPO_ROOT" --exclude=k6/ADR-013/results --exclude=__pycache__ -cf - "${HPATHS[@]}" | remote "cd $REMOTE_BASE/.tmp-$DEPLOY_ID && rm -rf ${HPATHS[*]} && cd - > /dev/null && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID && touch $REMOTE_BASE/.tmp-$DEPLOY_ID/.deployed && rm -rf $REMOTE_BASE/$DEPLOY_ID && mv $REMOTE_BASE/.tmp-$DEPLOY_ID $REMOTE_BASE/$DEPLOY_ID"; } \
    || abort13 deploy-failed
else deploy_sha "$SHA" || abort13 deploy-failed; fi
deploy_sha "$SHA_NEW8" || abort13 deploy-failed   # Flyway CLI 위치(새 SHA의 SQL 파일 — 기준 DB · 적용판 기록 ①)
compose_down13 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 || abort13 compose-down-failed
for kv in "seatlab-app:$SHA_OLD8=$IMG_OLD_ID" "seatlab-app:$SHA_NEW8=$IMG_NEW_ID"; do
  [[ "$(image_id "${kv%%=*}")" == "${kv#*=}" ]] || abort13 cond-image-mismatch
done
for img in $(jq -r '.aux | keys[]' "$IMAGES"); do [[ "$(image_id "$img")" == "$(jq -r --arg k "$img" '.aux[$k]' "$IMAGES")" ]] || abort13 aux-image-mismatch; done
compose_up13 "$DEPLOY_ID" db redis lb >> "$DIR/compose.log" 2>&1 || abort13 compose-up-failed
T0=""
run_step "$(jq -c .setup "$DIR/plan.json")" || abort13 "setup-$(jq -r .reason "$TFAIL" 2>/dev/null)"
BASE_URL="$(url_of A)"   # 초기화·시드 = 슬롯 A의 옛 앱(H6 — DB가 V7이라 옛 초기화로 충분)
# ---- 보정 1회(§4.1 DS-12 · §5.1 · §5.3 시작 조건 ①) — 두 DB를 같은 쌍 수로 만들고 같은 조건(부하 없음)에서 잰다:
#   load = 옛 앱 S4c 30분(4,200/s · 60구간 — 회차 800개)으로 쌓은 DB · seed = 같은 쌍 수(--pairs = load의 쿼터 행 수)를 SQL 시드(회차 1..K)로 만든 DB(VACUUM ANALYZE).
#   잰다: 쿼터 테이블·인덱스 크기 · 옛 C 한 바퀴 시간(부하 없이 완료 바퀴 3개의 중앙) · V9 CREATE INDEX 시간(열 추가 + 인덱스를 한 트랜잭션에서 하고 ROLLBACK — DB를 바꾸지 않는다)
#   → calibrate.json. 비 = summarize.py --calibration <seed> <load>(시작 조건 JSON의 calibration 칸).
if [[ -n "$CALIB" ]]; then
  if [[ "$CALIB" == load ]]; then
    RESET_TIMEOUT=1800 reset_with_retry "$DIR" 800 10000 > "$DIR/seed.json" || abort13 seed-failed
    env START_RATE=4200 FACTOR=1 STEPS=60 STEP_S=30 STOP_ON_ERROR=0 RAMP_S=60 RAMP_FROM=50 BASE_URL="$LB_URL" OUT_DIR="$DIR" "$K6_BIN" run --quiet --out "csv=$DIR/k6-requests.csv.gz" "$SCEN9/s4-throughput.js" > "$DIR/k6-stdout.log" 2>&1 || hreason k6-exit
  else
    k=$(( (CALIB_PAIRS + 9999) / 10000 ))
    RESET_TIMEOUT=1800 reset_with_retry "$DIR" 1000 10000 > "$DIR/seed.json" || abort13 seed-failed
    DB_JSON_TIMEOUT=3600 seed_s4c_sql 1 "$k" > "$DIR/seed-sql.json" 2>>"$DIR/errors.log" || abort13 seed-failed
    vacuum_analyze > "$DIR/seed-vacuum.json" 2>>"$DIR/errors.log" || abort13 seed-failed
  fi
  base=$(db_scalar "SELECT extract(epoch FROM clock_timestamp())" | tr -d '[:space:]')
  for _ in $(seq 1 240); do
    n=$(db_scalar "SELECT count(*) FROM counter_reconcile_run WHERE source = 'reconcile-app' AND started_at >= to_timestamp($base)" | tr -d '[:space:]')
    [[ "$n" =~ ^[0-9]+$ ]] && (( n >= 3 )) && break; sleep 10
  done
  DB_JSON_TIMEOUT=3600 db_json > "$DIR/calibrate.json" 2>>"$DIR/errors.log" <<SQL
CREATE TEMP TABLE r AS SELECT extract(epoch FROM finished_at - started_at) AS s FROM counter_reconcile_run WHERE source = 'reconcile-app' AND started_at >= to_timestamp($base) ORDER BY id LIMIT 3;
CREATE TEMP TABLE sz AS SELECT (SELECT count(*) FROM user_hold_quota) AS pairs, pg_relation_size('user_hold_quota') AS table_bytes, pg_indexes_size('user_hold_quota') AS index_bytes,
  pg_total_relation_size('seat_hold') AS hold_bytes, (SELECT n_dead_tup FROM pg_stat_user_tables WHERE relname = 'user_hold_quota') AS dead_tup;
BEGIN;
ALTER TABLE user_hold_quota ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE TEMP TABLE ci0 AS SELECT clock_timestamp() AS t;
CREATE INDEX idx_user_hold_quota_updated_at ON user_hold_quota (updated_at);
CREATE TEMP TABLE ci1 AS SELECT clock_timestamp() AS t;
SELECT json_build_object('mode', '$CALIB', 'rounds', (SELECT json_agg(s) FROM r), 'c_round_s', (SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY s) FROM r),
  'create_index_s', (SELECT extract(epoch FROM ci1.t - ci0.t) FROM ci0, ci1), 'size', (SELECT row_to_json(sz) FROM sz));
ROLLBACK;
SQL
  jq -e '.c_round_s != null and .create_index_s != null' "$DIR/calibrate.json" > /dev/null 2>&1 || abort13 calibrate-incomplete
  finish13 ok; cat "$DIR/calibrate.json"; exit 0
fi
reset_with_retry "$DIR" 1 10000 > /dev/null || abort13 reset-failed
BASE_URL="$LB_URL" "$K6_BIN" run --quiet "$SCEN9/warmup.js" > "$DIR/warmup.log" 2>&1   # 예열(lb 경유 — 주 부하와 같은 경로)
BASE_URL="$(url_of A)"
RESET_TIMEOUT=1800 reset_with_retry "$DIR" "$(jq -r .load_conf.seed.schedules "$DIR/plan.json")" "$(jq -r .load_conf.seed.seats "$DIR/plan.json")" > "$DIR/seed.json" || abort13 seed-failed
if [[ "$LOAD" == S4c ]]; then
  sf=$(LC seed.sql_from) stt=$(LC seed.sql_to)
  DB_JSON_TIMEOUT=3600 seed_s4c_sql "$sf" "$stt" > "$DIR/seed-sql.json" 2>>"$DIR/errors.log" || abort13 seed-failed
  vacuum_analyze > "$DIR/seed-vacuum.json" 2>>"$DIR/errors.log" || abort13 seed-failed
  seed_check_sql $(( (stt - sf + 1) * 10000 )) "$sf" "$stt" > "$DIR/seed-check.json" 2>>"$DIR/errors.log"
  jq -e --argjson n $(( (stt - sf + 1) * 10000 )) '.quota_rows == $n and .holds == $n and .held_seats == $n and .sample_n == 100 and .sample_bad == 0 and .mismatch == 0' "$DIR/seed-check.json" > /dev/null 2>&1 || abort13 seed-mismatch
else
  seed_check_sql 0 1 0 > "$DIR/seed-check.json" 2>>"$DIR/errors.log"
  jq -e '.quota_rows == 0 and .holds == 0 and .mismatch == 0 and .seats == 20000' "$DIR/seed-check.json" > /dev/null 2>&1 || abort13 seed-mismatch
fi
SEED_DONE=$(now)
ref_db "$SHA_NEW8" "$DIR" || abort13 ref-db-failed   # 기준 DB seat_ref(같은 SHA Flyway — 동치 확인·이력 체크섬)
pg_settings > "$DIR/pg-settings.json" 2>>"$DIR/errors.log"
db_state > "$DIR/db-state-k6.json" 2>>"$DIR/errors.log"

# ---- 표본 수집기 ------------------------------------------------------------------------------------------------------------------------
SAMPLER_COPY="$REMOTE_BASE/$DEPLOY_ID/.samples/$(echo "$CID" | tr '/' '_')-$LOAD-p$PROC-rep$REP-$(date +%Y%m%d%H%M%S)-$RANDOM.jsonl"
BG_PIDS=""
( while :; do remote "echo \"{\\\"t\\\":\\\"\$(date -Is)\\\",\\\"load\\\":\\\"\$(cut -d' ' -f1-3 /proc/loadavg)\\\"}\"; docker stats --no-stream --format '{{json .}}'" >> "$DIR/timeline-server.jsonl" 2>/dev/null; sleep 10; done ) > /dev/null 2>&1 & BG_PIDS+=" $!"
( while :; do db_sampler "$SAMPLER_COPY" 2>/dev/null < /dev/null | while IFS= read -r line; do case "$line" in *'"lock_waiting"'*) printf '%s\n' "$line" >> "$DIR/timeline-locks.jsonl" ;; *'"waits"'*) printf '%s\n' "$line" >> "$DIR/timeline-waits.jsonl" ;; esac; done
    echo "$(date -Is) db_sampler ended — reopen in 1s" >> "$DIR/errors.log"; sleep 1; done ) > /dev/null 2>&1 & BG_PIDS+=" $!"
( slot_poller "$DIR" ) > /dev/null 2>&1 & BG_PIDS+=" $!"
# 시드 끝 → k6 시작 = PRE_K6_HOLD_S(70 — ADR-009 명세 §13 대기점과 같은 값: 옛 C 첫 바퀴가 시드 뒤 한 번 돈다)
sleep "$(awk -v s="$SEED_DONE" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { d = s + h - n; print (d > 0 ? d : 0) }')"
CLK_BEFORE=$(clock_offset 2>>"$DIR/errors.log"); OFF=$(jq -r '.offset_s // 0' <<< "${CLK_BEFORE:-null}" 2>/dev/null); [[ "$OFF" =~ ^-?[0-9.]+$ ]] || OFF=0
# 셋업 사건의 서버 시각을 시계 차로 다시 쓴다(셋업은 시계 차를 재기 전이다 — 서버 시각 = PC + 이 값)
jq -c --argjson off "$OFF" 'if .kind == "plan" then .start_srv = .start_pc + $off | .end_srv = .end_pc + $off else . end' "$DIR/events.jsonl" > "$DIR/events.jsonl.tmp" && mv "$DIR/events.jsonl.tmp" "$DIR/events.jsonl"

# ---- k6 · 탐침 · 주입 · 오케스트레이션 -----------------------------------------------------------------------------------------------------
T0=$(awk -v n="$(now)" 'BEGIN { printf "%.3f", n + 3 }')
echo "{\"t0_pc\":$T0,\"offset_s\":$OFF}" > "$DIR/t0.json"
AV=$(jq -c .avail "$DIR/plan.json")
( sleep "$(awk -v t="$T0" -v n="$(now)" 'BEGIN { d = t - n; print (d > 0 ? d : 0) }')"
  exec env BASE_URL="$LB_URL" AVAIL_RATE="$(jq -r .rate <<< "$AV")" DURATION_S=$((K6_S + 120)) SCHEDULE_ID="$(jq -r .schedule_id <<< "$AV")" SEAT_BASE="$(jq -r .seat_base <<< "$AV")" \
    USER_BASE="$(jq -r .user_base <<< "$AV")" TIMEOUT_S="$(jq -r .timeout_s <<< "$AV")" K6_CSV_TIME_FORMAT=unix_milli \
    "$K6_BIN" run --quiet --out "csv=$DIR/k6-avail.csv.gz" "$SCEN13/avail-probe.js" > "$DIR/avail-probe.log" 2>&1 < /dev/null ) &
AVAIL_PID=$!
python3 -I "$S13/limitprobe.py" --plan "$DIR/plan.json" --t0 "$T0" --base-url "$LB_URL" \
  --sql-cmd "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ControlMaster=auto -o ControlPath=/tmp/seatlab13-%C -o ControlPersist=120 $SERVER docker exec -i -e PGAPPNAME=seatlab-harness seatlab-db-1 psql -X -q -U seat -d seat -tA -v ON_ERROR_STOP=1" \
  --out "$DIR/probe.jsonl" > "$DIR/limitprobe.log" 2>&1 &
PROBE_PID=$!
INJ_PID=""
if [[ "$LOAD" == S3e ]]; then
  ( excl=""
    for r in $(LC inject_rounds); do
      at="${r%%:*}"; IFS=, read -r -a pl <<< "${r#*:}"
      sleep "$(awk -v a="$at" -v t="$T0" -v n="$(now)" 'BEGIN { d = t + a - n; print (d > 0 ? d : 0) }')"
      tr=0; [[ "$(db_scalar "SELECT to_regclass('counter_app_inject') IS NOT NULL" | tr -d '[:space:]')" == t ]] && tr=1   # H7 — V10이 있을 때만 표에도
      out=$(INJECT_TRACK=$tr INJECT_SCHEDULE=1 inject_drift13 "${pl[0]}" "${pl[1]}" "${pl[2]}" "${pl[3]}" "$excl" 2>&1); rc=$?
      if (( rc == 0 )) && jq -e '.pairs | type == "array"' <<< "$out" > /dev/null 2>&1; then
        jq -c --argjson s "$at" --argjson d "${pl[0]}" --argjson l1 "${pl[1]}" --argjson l2 "${pl[2]}" --argjson h "${pl[3]}" '{at_s: $s, ok: true, planned: {D: $d, L1: $l1, L2: $l2, H: $h}} + .' <<< "$out" >> "$DIR/injected.jsonl"
        excl+=$(jq -r '[.pairs[] | "(\(.schedule_id),\(.user_id))"] | join(",")' <<< "$out")","; excl="${excl#,}"
      else
        jq -nc --argjson s "$at" --arg e "$(head -c 2000 <<< "$out")" --argjson rc "$rc" '{at_s: $s, ok: false, rc: $rc, error: $e}' >> "$DIR/injected.jsonl"
      fi
    done ) > /dev/null 2>&1 &
  INJ_PID=$!
fi
orchestrate > "$DIR/orchestrator.log" 2>&1 &
ORCH_PID=$!
sleep "$(awk -v t="$T0" -v n="$(now)" 'BEGIN { d = t - n; print (d > 0 ? d : 0) }')"
K6_STARTED=$(date -Is)
env $(LC k6_env) BASE_URL="$LB_URL" OUT_DIR="$DIR" "$K6_BIN" run --quiet --out "csv=$DIR/k6-requests.csv.gz" "$SCEN9/$(LC script)" > "$DIR/k6-stdout.log" 2>&1
K6_EXIT=$?; K6_END=$(now)
jq -n --arg s "$K6_STARTED" --arg e "$(date -Is)" --argjson t0 "$T0" --argjson t1 "$K6_END" --argjson rc "$K6_EXIT" '{k6_started: $s, k6_ended: $e, t0_pc: $t0, end_pc: $t1, exit: $rc}' > "$DIR/k6-window.json"
(( K6_EXIT == 0 )) || hreason "k6-exit-$K6_EXIT"
kill -INT "$AVAIL_PID" 2>/dev/null; for _ in $(seq 1 60); do kill -0 "$AVAIL_PID" 2>/dev/null || break; sleep 0.5; done; kill -0 "$AVAIL_PID" 2>/dev/null && { kill -KILL "$AVAIL_PID"; hreason avail-probe-killed; }
# 오케스트레이터·탐침 실행기는 스스로 끝난다(마지막 사건 = 시각표 안 · 기동 시한 300초 — §5.1). 끝나지 않으면 하네스 실패로 드러낸다
for _ in $(seq 1 900); do kill -0 "$ORCH_PID" 2>/dev/null || break; sleep 1; done
kill -0 "$ORCH_PID" 2>/dev/null && { pkill -P "$ORCH_PID"; kill "$ORCH_PID"; hreason orchestrator-overrun; }
for _ in $(seq 1 300); do kill -0 "$PROBE_PID" 2>/dev/null || break; sleep 1; done
kill -0 "$PROBE_PID" 2>/dev/null && { kill "$PROBE_PID"; hreason limitprobe-overrun; }
[[ -n "$INJ_PID" ]] && wait "$INJ_PID" 2>/dev/null
[[ -s "$DIR/.orch-state.json" ]] || state_json > "$DIR/.orch-state.json"
[[ -s "$DIR/.event-write-failed" ]] && hreason event-write-failed

# ---- 수렴 → 끝 검사(H6 — 마지막 새 앱) ----------------------------------------------------------------------------------------------------
sleep "$(awk -v e="$K6_END" -v c="$CONV_S" -v n="$(now)" 'BEGIN { d = e + c - n; print (d > 0 ? d : 0) }')"
FINAL=$(jq -r .end_state.final_new "$DIR/plan.json")
wait_health_url "$(url_of "$FINAL")" 5 || { for s in A B; do wait_health_url "$(url_of "$s")" 5 && { FINAL=$s; break; }; done; }
echo "{\"final_app\":\"$FINAL\",\"planned\":\"$(jq -r .end_state.final_new "$DIR/plan.json")\",\"end_check_start_pc\":$(now)}" > "$DIR/final-app.json"
BASE_URL="$(url_of "$FINAL")" CONSISTENCY_TIMEOUT=600 consistency 20 > "$DIR/consistency.json" 2>>"$DIR/errors.log" || { echo "$(date -Is) consistency 실패·시한 — P9 판정 불가(요약기 end-check-timeout)" >> "$DIR/errors.log"; rm -f "$DIR/consistency.json"; }
end_check_sql "$PROBE_SCH" > "$DIR/end-check.json" 2>>"$DIR/errors.log" || hreason end-check-failed
drift_db_dump $( [[ "$(db_scalar "SELECT to_regclass('counter_inc_gap') IS NOT NULL" | tr -d '[:space:]')" == t ]] && echo v10) > "$DIR/drift-db.json" 2>>"$DIR/errors.log" || hreason drift-db-failed
if [[ "$LOAD" == S3e ]]; then
  jq -s --arg r "$(LC inject_rounds)" '{plan: {rounds: ($r | split(" ") | map(split(":") as [$a, $k] | ($k | split(",") | map(tonumber)) as $n | {at_s: ($a | tonumber), D: $n[0], L1: $n[1], L2: $n[2], H: $n[3]}))}, rounds: .}' \
    "$DIR/injected.jsonl" > "$DIR/injected.json" 2>>"$DIR/errors.log" || hreason injected-missing
  end_pairs "$(jq -r '[.rounds[] | select(.ok) | .pairs[] | "(\(.schedule_id),\(.user_id))"] | join(",")' "$DIR/injected.json" 2>/dev/null)" > "$DIR/end-pairs.json" 2>>"$DIR/errors.log" || hreason end-pairs-failed
fi
# ---- 살아 있는 앱 수집(H4 — 회차 끝) · lb 접근 기록 · DB 로그 · 정리 -----------------------------------------------------------------------
for s in $(jq -r '.alive[]' "$DIR/.orch-state.json"); do
  LIFE[$s]=$(jq -r --arg s "$s" '.lives[$s] | ltrimstr($s)' "$DIR/.orch-state.json")
  errs=$(app_collect "$s" "$s${LIFE[$s]}" "$DIR" | xargs); evx final-collect "$s" "$(jq -nc --arg l "$s${LIFE[$s]}" --arg e "$errs" '{life: $l, errors: $e}')"
done
for p in $BG_PIDS; do pkill -P "$p" 2>/dev/null; kill "$p" 2>/dev/null; done; BG_PIDS=""
remote "pkill -f 'FROM pg_locks WHERE NOT granted' || true" > /dev/null 2>&1
remote "cat '$SAMPLER_COPY'" > "$DIR/.sampler-remote.jsonl" 2>/dev/null
if (( $(grep -c '"lock_waiting"' "$DIR/.sampler-remote.jsonl" 2>/dev/null || echo 0) > $(wc -l < "$DIR/timeline-locks.jsonl" 2>/dev/null || echo 0) )); then
  grep '"lock_waiting"' "$DIR/.sampler-remote.jsonl" > "$DIR/timeline-locks.jsonl"; grep '"waits"' "$DIR/.sampler-remote.jsonl" > "$DIR/timeline-waits.jsonl"
fi
rm -f "$DIR/.sampler-remote.jsonl"; remote "rm -f '$SAMPLER_COPY'" > /dev/null 2>&1
remote "docker exec seatlab-lb-1 cat /var/log/nginx/cut.log" | gzip > "$DIR/lb-access.log.gz"; echo "lb access pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]}" >> "$DIR/logs-status.txt"
for c in lb db; do remote "docker logs seatlab-$c-1 2>&1" | gzip > "$DIR/$c.log.gz"; echo "$c log pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]}" >> "$DIR/logs-status.txt"; done
CLK_AFTER=$(clock_offset 2>>"$DIR/errors.log")
jq -n --argjson b "${CLK_BEFORE:-null}" --argjson a "${CLK_AFTER:-null}" '{before: $b, after: $a}' > "$DIR/clock-offset.json" 2>/dev/null || echo '{"before":null,"after":null}' > "$DIR/clock-offset.json"
python3 -I "$S13/plan13.py" --events-check "$DIR" > /dev/null 2>>"$DIR/errors.log"
gzip -f "$DIR/k6-stdout.log"
finish13
