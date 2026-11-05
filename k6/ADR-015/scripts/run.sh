#!/usr/bin/env bash
# ADR-015 회차 실행기 — 회차 하나(모드 × 셀 × 회차 번호). ADR-014 run.sh의 얇은 변형(같은 순서 · 같은 시작 대기 · 같은 수집 — 바뀌는 것만 적는다):
#   내리기(모든 seatlab 컨테이너 · DB 볼륨 — '모드 전환 = 전 인스턴스 정지 후 새 모드로 기동' 절차가 회차마다 강제된다 · §3.5 모드 혼합 금지) → 이미지 확인 →
#   기동(seat.confirm.mode · delay · ④ 키 · Flyway 최신(V12 · V13 · ③만 V14)) → 앱 인스턴스 1개 확인 → 예열(warmup15) → 시드 · 시드 확인(V12 유효 · V13 · V14) →
#   표본기(서버 · PC · 대기 · Hikari — 상태 · H4 표본은 이 측정에 없다) → 시작 대기(87초) → k6(confirm-contention) · 측정기 → 직후 → 수렴(TTL + 22초) →
#   끝 판정기 · 기록 덤프 → 수집 → 판정(summarize.py --rep → status).
#   계획의 단일 출처 = scripts/plan15.py · 판정 = scripts/summarize.py --rep. ADR-014 · ADR-009 하네스는 수정하지 않고 동결 사본에서 source · import로 쓴다.
#
#   scripts/run.sh --prepare-image --sha <SHA> --out <images.json>        (ADR-014 run.sh --prepare-image와 같다 — 이미지 ID 고정)
#   scripts/run.sh --sha <SHA> --images <images.json> --mode <모드> --cell race|cost --rep <k> --id <캠페인 id> [--attempt n] [--knobs <json>]
#       [--k6-bin k6] [--probe-k6-bin <경로>] [--worktree] [--results-root <폴더>] [--dry-run] [--simulate]
#     결과 = <results-root 기본 k6/ADR-015/results>/<id>/<셀>/<모드>/rep<k>/ · --attempt n(>1)은 앞 시도를 rep<k>.attempt<n−1>로 옮긴다.
#   status: ok(유효 — 모드 실패는 rep-summary.json fail에) · invalid:<사유,…>(하네스 · 환경 — 무효 → 재측정).
set -uo pipefail

if [[ "${RUN15_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"
  HH_SRC="$(bash "$src/hhash.sh" "$repo")" || { echo "하네스 해시 실패($repo)" >&2; exit 2; }
  [[ -z "${HARNESS_HASH15:-}" || "$HH_SRC" == "$HARNESS_HASH15" ]] || { echo "하네스 해시 $HH_SRC ≠ 캠페인 동결본 $HARNESS_HASH15" >&2; exit 2; }
  frozen="$(mktemp -d)"
  mkdir -p "$frozen/k6/ADR-015" "$frozen/k6/ADR-014" "$frozen/k6/ADR-009"
  cp -r "$src" "$frozen/k6/ADR-015/scripts" && cp -r "$repo/k6/ADR-014/scripts" "$frozen/k6/ADR-014/scripts" \
    && cp -r "$repo/k6/ADR-009/scripts" "$frozen/k6/ADR-009/scripts" || { echo "동결 사본 실패" >&2; exit 2; }
  export RUN15_FROZEN=1 FROZEN15="$frozen" HH_SRC ADR15_DIR="${ADR15_DIR:-$(cd "$src/.." && pwd)}" REPO_ROOT="${REPO_ROOT:-$repo}"
  exec bash "$frozen/k6/ADR-015/scripts/run.sh" "$@"
fi
export LIB9_DIR="$FROZEN15/k6/ADR-009/scripts" LIB14_SRC="$FROZEN15/k6/ADR-014/scripts"
S15="$FROZEN15/k6/ADR-015/scripts" S14="$LIB14_SRC"
DRY=0 SIM=0; for a in "$@"; do [[ "$a" == --dry-run ]] && DRY=1; [[ "$a" == --simulate ]] && SIM=1; done
source "$S15/lib15.sh"
set +e

SHA="" IMAGES="" MODE="" CELL="" REP="" CID="" ATTEMPT=1 KNOBS="" WORKTREE=0 K6_BIN=k6 PREPARE=0 IMG_OUT="" RESULTS_ROOT=""
PROBE_K6_BIN="$HOME/.local/opt/k6-v2.2.0/k6" PROBE_IMAGE="${PROBE_IMAGE:-grafana/k6:2.2.0}" PROBE_RATE=50
PRE_K6_HOLD_S="${PRE_K6_HOLD_S:-70}" PRE_K6_TARGET_S="${PRE_K6_TARGET_S:-87}" PRE_K6_TOL_S="${PRE_K6_TOL_S:-5}" PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-}"   # ADR-014 run.sh 그대로
IDLE_AFTER_K6_S=11 WEND_LAG_S=3
ARGS_ALL=("$@")
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --images) IMAGES="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --cell) CELL="$2"; shift 2 ;;
    --rep) REP="$2"; shift 2 ;;
    --id) CID="$2"; shift 2 ;;
    --attempt) ATTEMPT="$2"; shift 2 ;;
    --knobs) KNOBS="$2"; shift 2 ;;
    --worktree) WORKTREE=1; shift ;;
    --k6-bin) K6_BIN="$2"; shift 2 ;;
    --probe-k6-bin) PROBE_K6_BIN="$2"; shift 2 ;;
    --results-root) RESULTS_ROOT="$2"; shift 2 ;;
    --prepare-image) PREPARE=1; shift ;;
    --out) IMG_OUT="$2"; shift 2 ;;
    --dry-run|--simulate) shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
if (( PREPARE )); then   # 이미지 고정은 ADR-014 run.sh와 같은 절차 — 그 스크립트에 넘긴다(동결 사본)
  exec env RUN14_FROZEN=1 FROZEN14="$FROZEN15" ADR14_DIR="$REPO_ROOT/k6/ADR-014" REPO_ROOT="$REPO_ROOT" bash "$S14/run.sh" "${ARGS_ALL[@]}"
fi
cleanup15() {
  [[ "$BASHPID" == "$$" ]] || return 0
  [[ -n "${POLL_PIDS:-}" ]] && stop_pollers14 $POLL_PIDS
  [[ -n "${WEND_PID:-}" ]] && kill "$WEND_PID" 2>/dev/null
  if [[ -n "${PROBE_PC_PID:-}" ]] && kill -0 "$PROBE_PC_PID" 2>/dev/null; then kill -INT "$PROBE_PC_PID" 2>/dev/null; fi
  [[ -n "${K6_TREE:-}" && -d "${K6_TREE:-}" ]] && rm -rf "${K6_TREE:?}"
  [[ -n "${FROZEN15:-}" && "$FROZEN15" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "${FROZEN15:?}"
}
trap cleanup15 EXIT
short() { git -C "$REPO_ROOT" rev-parse --short=8 "$1^{commit}" 2>/dev/null; }
now() { date +%s.%N; }

# ---- 인자 · 계획 ---------------------------------------------------------------------------------------------------------------------
[[ -n "$MODE" && "$CELL" =~ ^(race|cost)$ && "$REP" =~ ^[1-3]$ && "$ATTEMPT" =~ ^[1-9]$ && -n "$CID" ]] || { echo "--mode <모드> --cell race|cost --rep 1..3 --id <id> 필수" >&2; exit 2; }
SHA="$(short "${SHA:-HEAD}")"; [[ -n "$SHA" ]] || { echo "SHA 해석 실패" >&2; exit 2; }
KNOBS_ARG=(); [[ -n "$KNOBS" ]] && KNOBS_ARG=(--knobs "$KNOBS")
PLAN_JSON="$(python3 -I "$S15/plan15.py" --render "$MODE" "$CELL" "$REP" "${KNOBS_ARG[@]}")" || exit 2
P() { jq -r "$1" <<< "$PLAN_JSON"; }
PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-$(P .k6.lead_s)}"
mapfile -t HPATHS < <(bash "$S15/hhash.sh" --paths)
HARNESS="sha:$SHA" DEPLOY_ID="$SHA"
if (( WORKTREE )); then
  [[ "${CAMPAIGN15:-0}" != 1 || "${CAMPAIGN15_WORKTREE:-0}" == 1 ]] || { echo "--worktree는 스모크 전용" >&2; exit 2; }
  HARNESS="worktree:${HH_SRC:0:8}"; DEPLOY_ID="$SHA-wt15-${HH_SRC:0:8}"
elif [[ -n "${HARNESS_HASH15:-}" ]]; then
  :
elif ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- "${HPATHS[@]}" ':(exclude)k6/ADR-015/results' 2>/dev/null \
     || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- "${HPATHS[@]}" ':(exclude)k6/ADR-015/results')" ]]; then
  echo "하네스(${HPATHS[*]})가 $SHA와 다르다 — 커밋하거나 --worktree(스모크)" >&2; (( DRY || SIM )) || exit 2
fi
APP_ENV14="$(P '.app_env | to_entries | map("\(.key)=\(.value)") | join(" ")')"
RES_ENV14="APP_CPUS=4 DB_CPUS=4 APP_MEM=2g APP_JVM_OPTS=-XX:+UseZGC APP_JVM_OPTS2=-XX:+ZGenerational APP_JVM_OPTS3= APP_JVM_OPTS4= \
PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=12g DB_MEM=14g"   # ADR-014 측정-1 자원 그대로(L4 · tmpfs 12g · 2g ZGC)
K6_ENV="$(P '.k6.env | to_entries | map("\(.key)=\(.value)") | join(" ")')"
K6_SCRIPT="$(P .k6.script)"
OUT_ROOT="${RESULTS_ROOT:-$ADR15_DIR/results}/$CID"; DIR="$OUT_ROOT/$CELL/$MODE/rep$REP"
SEED_S=$(P .seed.schedules) SEED_N=$(P .seed.seats) PGSS=$(P .pgss) B3W_MS=$(P '.b3wait_ms // 0')
if (( DRY )); then
  echo "# dry-run — 모드 $MODE · 셀 $CELL · 회차 $REP · 시도 $ATTEMPT · 하네스 $HARNESS · 배포 $DEPLOY_ID"
  echo "결과 폴더: $DIR"
  echo "compose: $(compose15)"
  echo "env: $(env14)"
  echo "모드 전환: compose down -v(seatlab 컨테이너 0 · DB 볼륨 없음 확인) → up(앱 1대 · CONFIRM_MODE=$MODE) → configprops seat.confirm.mode 대조"
  echo "시드: 예열 앞 /internal/reset?schedules=2&seatsPerSchedule=10000 → warmup15.js(PAY_START=$(P .k6.env.PAY_START)) → /internal/reset?schedules=$SEED_S&seatsPerSchedule=$SEED_N"
  echo "표본기: 서버·docker stats 10s · 대기 0.5s · Hikari 1s · pg_stat_statements $([[ $PGSS == 1 ]] && echo 켬 || echo 끔) · B3 대기(② 양성 대조) $([[ $B3W_MS -gt 0 ]] && echo "${B3W_MS}ms" || echo 끔)"
  echo "k6: env $K6_ENV CLOCK_OFFSET_MS=<k6 직전 시계 차> BASE_URL=http://$SERVER_HOST:8101 OUT_DIR=$DIR $K6_BIN run --quiet --out csv=$DIR/k6-requests.csv.gz <k6 트리>/$K6_SCRIPT"
  echo "시각: 시드 끝 → 첫 요청 ${PRE_K6_TARGET_S}±${PRE_K6_TOL_S}초(k6 lead ${PRE_K6_LEAD_S}초) · k6 $(P .k6.duration_s)초 · 창 [0, $(P .window.len_s))초 · 수렴 $(P .converge_s)초 · 회차 추정 $(P .est_s)초"
  exit 0
fi
if (( SIM )); then   # campaign.sh 시험용 — 서버 접속 없음(ADR-014 run.sh --simulate와 같다)
  if [[ -e "$DIR" ]]; then
    if (( ATTEMPT > 1 )) && [[ ! -e "$DIR.attempt$((ATTEMPT - 1))" ]]; then mv "$DIR" "$DIR.attempt$((ATTEMPT - 1))"; else echo "$DIR 이미 있다" >&2; exit 2; fi
  fi
  mkdir -p "$DIR"; echo "$PLAN_JSON" | jq . > "$DIR/plan.json"
  timeout 2 cat > /dev/null 2>&1
  st=ok; [[ -n "${SIM_PLAN:-}" && -s "$SIM_PLAN" ]] && { v=$(grep -m1 "^$REP:$CELL:$MODE:$ATTEMPT=" "$SIM_PLAN" | cut -d= -f2-); [[ -n "$v" ]] && st="$v"; }
  # 시험용 경합 증거(campaign 조기 확인 시험 — 리뷰 M15-11): 'ok;ev=N'이면 status ok + rep-summary.json의 contention_evidence = N
  if [[ "$st" == *";ev="* ]]; then sev="${st##*;ev=}"; st="${st%%;ev=*}"
    jq -n --arg st "$st" --argjson ev "$sev" '{status: $st, metrics: {contention_evidence: $ev}, fail: {}}' > "$DIR/rep-summary.json"; fi
  jq -n --arg sha "$SHA" --arg m "$MODE" --arg c "$CELL" --argjson r "$REP" --argjson a "$ATTEMPT" --arg hh "${HH_SRC:-}" \
    '{sha: $sha, mode: $m, cell: $c, rep: $r, attempt: $a, rerun: ($a > 1), harness_hash: $hh, simulated: true}' > "$DIR/meta.json"
  [[ "$st" == skip ]] || echo "$st" > "$DIR/status"
  echo "$(date -Is) $CELL $MODE rep$REP attempt$ATTEMPT ${st} (simulated)" >> "$OUT_ROOT/MATRIX.log"
  exit 0
fi
(( $(date -u +%s) < $(date -u -d 2026-12-31T09:00:00Z +%s) )) || { echo "2026-12-31T09:00Z 이후 — 시드 회차가 측정 중 마감될 수 있다" >&2; exit 2; }
[[ -s "$IMAGES" ]] || { echo "--images <images.json>(run.sh --prepare-image) 필수" >&2; exit 2; }
[[ "$(jq -r .app.sha "$IMAGES")" == "$SHA" ]] || { echo "images.json의 SHA가 --sha $SHA와 다르다" >&2; exit 2; }
APP_TAG="$SHA" APP_ID=$(jq -r .app.id "$IMAGES")
PROBE_K6_VERSION=$("$PROBE_K6_BIN" version 2>/dev/null | head -1)
if [[ -e "$DIR" ]]; then
  if (( ATTEMPT > 1 )) && [[ ! -e "$DIR.attempt$((ATTEMPT - 1))" ]]; then mv "$DIR" "$DIR.attempt$((ATTEMPT - 1))"
  else echo "$DIR 이미 있다 — 덮어쓰지 않는다(재측정은 --attempt n)" >&2; exit 2; fi
fi
mkdir -p "$DIR"; MATRIX_LOG="$OUT_ROOT/MATRIX.log"
echo "$PLAN_JSON" | jq . > "$DIR/plan.json"
cp "$IMAGES" "$DIR/images.json"
STARTED=$(date -Is) STARTED_EPOCH=$(date +%s)
K6_TREE="$(mktemp -d)"
if (( WORKTREE )); then mkdir -p "$K6_TREE/k6/ADR-009" "$K6_TREE/k6/ADR-014" "$K6_TREE/k6/ADR-015" && cp -r "$REPO_ROOT/k6/ADR-009/scenarios" "$K6_TREE/k6/ADR-009/" \
  && cp -r "$REPO_ROOT/k6/ADR-014/scenarios" "$K6_TREE/k6/ADR-014/" && cp -r "$REPO_ROOT/k6/ADR-015/scenarios" "$K6_TREE/k6/ADR-015/"
else git -C "$REPO_ROOT" archive "$SHA" k6/ADR-009/scenarios k6/ADR-014/scenarios k6/ADR-015/scenarios | tar -x -C "$K6_TREE"; fi
SCEN9="$K6_TREE/k6/ADR-009/scenarios" SCEN15="$K6_TREE/k6/ADR-015/scenarios"
HREASONS="$DIR/harness-reasons.txt"; : > "$HREASONS"
hreason() { echo "$1" >> "$HREASONS"; echo "$(date -Is) harness: $1" >> "$DIR/errors.log"; }
snap15() {   # 인자: 이름 — ADR-014(actsnap14 — 틱 · 거절 원인) · ADR-015(actsnap15) · ADR-009(drift_snap) 앱 지표를 같은 시점에
  python3 -I "$S14/actsnap14.py" "$BASE_URL" > "$DIR/act14-$1.json" 2>>"$DIR/errors.log" < /dev/null || hreason app-snap-failed
  python3 -I "$S15/actsnap15.py" "$BASE_URL" > "$DIR/act15-$1.json" 2>>"$DIR/errors.log" < /dev/null || hreason app-snap-failed
}
finish15() {
  local st="${1:-}"
  jq -n --arg sha "$SHA" --arg harness "$HARNESS" --arg m "$MODE" --arg c "$CELL" --argjson r "$REP" --argjson a "$ATTEMPT" \
        --arg started "$STARTED" --arg ended "$(date -Is)" --arg k6 "$("$K6_BIN" version 2>/dev/null | head -1)" --arg k6sha "$(sha256sum "$(command -v "$K6_BIN")" 2>/dev/null | cut -d' ' -f1)" \
        --arg pk6 "$PROBE_K6_VERSION" --arg app "$APP_ENV14" --arg res "$RES_ENV14" --arg k6env "$K6_ENV" --arg hh "${HH_SRC:-}" --arg img "$APP_ID" \
        --argjson seed "${SEED_T:-null}" --argjson k0 "${K6_T0:-null}" --argjson k1 "${K6_END:-null}" --argjson kx "${K6_EXIT:-null}" \
    '{sha: $sha, harness: $harness, mode: $m, cell: $c, rep: $r, attempt: $a, rerun: ($a > 1), started: $started, ended: $ended,
      k6_version: $k6, k6_sha256: $k6sha, probe_k6_version: $pk6, app_env: $app, res_env: $res, k6_env: $k6env, harness_hash: $hh, app_image_id: $img,
      seed_done_epoch: $seed, k6_launch_epoch: $k0, k6_end_epoch: $k1, k6_exit: $kx}' > "$DIR/meta.json.tmp" \
    && mv "$DIR/meta.json.tmp" "$DIR/meta.json" || { hreason meta-write-failed; st="invalid:meta-write-failed"; }
  bash "$S14/pclink14.sh" "$STARTED_EPOCH" "$(( $(date +%s) + 1 ))" > "$DIR/pc-link.json" 2>>"$DIR/errors.log" || echo "$(date -Is) pclink14 실패 rc=$?" >> "$DIR/errors.log"
  if [[ -z "$st" ]]; then
    python3 -I "$S15/summarize.py" --rep "$DIR" > "$DIR/.summarize.out" 2>>"$DIR/errors.log"; local src=$?
    st=$(tail -n 1 "$DIR/.summarize.out"); rm -f "$DIR/.summarize.out"
    (( src == 0 )) && [[ "$st" =~ ^(ok|invalid:) ]] || st="invalid:summarize-failed"
  fi
  echo "$st" > "$DIR/status"
  echo "$(date -Is) $CELL $MODE rep$REP attempt$ATTEMPT $st" >> "$MATRIX_LOG"
  log "$CELL $MODE rep$REP → $st"
}
abort15() {
  hreason "$1"
  remote "docker logs --since '$STARTED' seatlab-app-1 2>&1" 2>/dev/null | gzip > "$DIR/app.log.gz"
  [[ -n "${POLL_PIDS:-}" ]] && { stop_pollers14 $POLL_PIDS; POLL_PIDS=""; }
  finish15 "invalid:$1"; exit 0
}

# ---- 모드 전환(전 인스턴스 정지 → 새 모드로 기동) · 예열 · 시드 ------------------------------------------------------------------------
log "ADR-015 $CELL $MODE rep$REP attempt$ATTEMPT → $DIR"
BASE_URL="http://${SERVER_HOST}:8101"
if (( WORKTREE )); then
  remote "test -f $REMOTE_BASE/$DEPLOY_ID/.deployed" 2>/dev/null < /dev/null || {
    git -C "$REPO_ROOT" archive --format=tar "$SHA" -- . ":(exclude,glob)k6/*/results/**" | remote "rm -rf $REMOTE_BASE/.tmp-$DEPLOY_ID && mkdir -p $REMOTE_BASE/.tmp-$DEPLOY_ID && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID" \
    && tar -C "$REPO_ROOT" --exclude=k6/ADR-015/results --exclude=__pycache__ -cf - "${HPATHS[@]}" | remote "cd $REMOTE_BASE/.tmp-$DEPLOY_ID && rm -rf ${HPATHS[*]} && cd - > /dev/null && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID && touch $REMOTE_BASE/.tmp-$DEPLOY_ID/.deployed && rm -rf $REMOTE_BASE/$DEPLOY_ID && mv $REMOTE_BASE/.tmp-$DEPLOY_ID $REMOTE_BASE/$DEPLOY_ID"; } \
    || abort15 deploy-failed
else deploy_sha "$SHA" < /dev/null || abort15 deploy-failed; fi
compose_down14 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || abort15 compose-down-failed
[[ "$(image_id "seatlab-app:$APP_TAG")" == "$APP_ID" ]] || abort15 cond-image-mismatch
for img in $(jq -r '.aux | keys[]' "$IMAGES"); do [[ "$(image_id "$img")" == "$(jq -r --arg k "$img" '.aux[$k]' "$IMAGES")" ]] || abort15 aux-image-mismatch; done
compose_up14 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || abort15 compose-up-failed
wait_health 240 || abort15 unhealthy
[[ "$(ctr_image seatlab-app-1)" == "$APP_ID" ]] || abort15 cond-image-mismatch
# 모드 혼합 금지(§3.5 · 15-08 OQ15-1): 이 DB에 붙은 앱 인스턴스 = seatlab 프로젝트의 app 컨테이너 1개뿐이어야 한다(+ DB 세션의 application_name 갈래)
remote "docker ps --filter label=com.docker.compose.project=seatlab --filter label=com.docker.compose.service=app --format '{{.Names}}'" < /dev/null > "$DIR/app-instances.txt" 2>>"$DIR/errors.log"
[[ "$(grep -c . "$DIR/app-instances.txt")" == 1 ]] || abort15 app-instances-not-one
reset_with_retry "$DIR" 2 10000 > /dev/null || abort15 reset-failed
BASE_URL="$BASE_URL" PAY_START="$(P .k6.env.PAY_START)" "$K6_BIN" run --quiet --out "csv=$DIR/warmup-requests.csv.gz" "$SCEN15/warmup15.js" > "$DIR/warmup.log" 2>&1 < /dev/null
WARM_RC=$?
python3 -I "$S14/summarize.py" --warmup-status "$DIR/warmup-requests.csv.gz" "$WARM_RC" > "$DIR/warmup-status.json" 2>>"$DIR/errors.log"
(( WARM_RC == 0 )) || abort15 warmup-failed
RESET_TIMEOUT=1800 reset_with_retry "$DIR" "$SEED_S" "$SEED_N" > "$DIR/seed.json" || abort15 seed-failed
SEED_T=$(now)
seed_check15 "$SEED_S" "$SEED_N" > "$DIR/seed-check.json" 2>>"$DIR/errors.log" < /dev/null || hreason seed-check-failed
db_state > "$DIR/db-state-seed.json" 2>>"$DIR/errors.log" < /dev/null
app_config15 "$DIR" 2>>"$DIR/errors.log" < /dev/null || hreason app-config-failed
limit_key_check seatlab-app-1 > "$DIR/limit-key.json" 2>>"$DIR/errors.log" < /dev/null || hreason limit-key-check-failed
pg_settings > "$DIR/pg-settings.json" 2>>"$DIR/errors.log" < /dev/null
bash "$S14/pccheck14.sh" > "$DIR/pc-check.json" 2>>"$DIR/errors.log" < /dev/null || echo "$(date -Is) pc-check: PC 다른 부하 — $(jq -c .why "$DIR/pc-check.json" 2>/dev/null)" >> "$DIR/errors.log"
if [[ "$PGSS" == 1 ]]; then pgss_prepare > "$DIR/pgss-prepare.json" 2>>"$DIR/errors.log" < /dev/null || hreason pgss-failed; fi

# ---- 표본기(상태 · H4 표본 없음 — kind=cost로 넘겨 lib14가 띄우지 않게) ----------------------------------------------------------------
TAG15="$(echo "$CID" | tr '/' '_')-$CELL-$MODE-rep$REP-$(date +%Y%m%d%H%M%S)-$RANDOM"
SAMPLER_COPY="$REMOTE_BASE/$DEPLOY_ID/.samples/$TAG15.jsonl" B3W_COPY="$REMOTE_BASE/$DEPLOY_ID/.samples/$TAG15-b3wait.jsonl"
POLL_PIDS=$(start_pollers14 "$DIR" cost 1 | xargs)
if (( B3W_MS > 0 )); then   # ② 양성 대조 — B3가 지연 중인 ② 확정을 기다리는 표본(race 셀 다섯 모드 같게 · 리뷰 M15-26). 끝나면 1초 뒤 다시 연다(무음 공백 금지 — errors.log 한 줄)
  B3W_PID=$( ( while :; do
      b3wait_sampler "$B3W_COPY" "$B3W_MS" seatlab-harness-b3 2>/dev/null < /dev/null | while IFS= read -r line; do printf '%s\n' "$line" >> "$DIR/timeline-b3wait.jsonl"; done
      echo "$(date -Is) b3wait sampler ended — reopen in 1s" >> "$DIR/errors.log"; sleep 1
    done ) > /dev/null 2>&1 < /dev/null & echo $! )
  POLL_PIDS="$POLL_PIDS $B3W_PID"
fi
for _ in $(seq 1 30); do [[ -s "$DIR/timeline-locks.jsonl" ]] && break; sleep 0.5; done
[[ -s "$DIR/timeline-locks.jsonl" ]] || echo "$(date -Is) samples-before-load timeout: timeline-locks.jsonl 15s 동안 비어 있음" >> "$DIR/errors.log"
hold_left=$(awk -v s="$SEED_T" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { d = s + h - n; printf "%.3f", (d > 0 ? d : 0) }')
awk -v s="$SEED_T" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { exit !(n - s > h + 1) }' && abort15 pre-k6-overrun
sleep "$hold_left"
io_snapshot > "$DIR/io-before.json" 2>>"$DIR/errors.log" < /dev/null
table_stats > "$DIR/table-before.json" 2>>"$DIR/errors.log" < /dev/null || hreason table-stats-failed
drift_snap app > "$DIR/drift-before.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-snap-failed
snap15 before
if [[ "$PGSS" == 1 ]]; then pgss_prepare > "$DIR/pgss-reset.json" 2>>"$DIR/errors.log" < /dev/null || hreason pgss-failed; fi
app_db_clock > "$DIR/app-db-clock-before.json" 2>>"$DIR/errors.log" < /dev/null
awk -v n="$(now)" -v b="$SEED_T" -v t="$PRE_K6_TARGET_S" -v l="$PRE_K6_TOL_S" -v lead="$PRE_K6_LEAD_S" 'BEGIN { exit !((n - b) + lead > t + l) }' && abort15 pre-k6-overrun
probes_start14 "$DIR" "$(( $(P .k6.duration_s) + 180 ))"
topup=$(awk -v t="$PRE_K6_TARGET_S" -v n="$(now)" -v b="$SEED_T" -v lead="$PRE_K6_LEAD_S" 'BEGIN { d = t - lead - (n - b); printf "%.3f", (d > 0 ? d : 0) }')
sleep "$topup"

# ---- k6 -----------------------------------------------------------------------------------------------------------------------------
CLK_BEFORE=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
OFF_MS=$(jq -r '(.offset_s // 0) * 1000 | round' <<< "${CLK_BEFORE:-null}" 2>/dev/null); [[ "$OFF_MS" =~ ^-?[0-9]+$ ]] || OFF_MS=0
K6_STARTED=$(date -Is); K6_T0=$(now)
( sleep "$(awk -v k="$K6_T0" -v w="$(P '.window.from_s + .window.len_s')" -v l="$WEND_LAG_S" -v lead="$PRE_K6_LEAD_S" -v n="$(now)" 'BEGIN { d = k + lead + w + l - n; printf "%.3f", (d > 0 ? d : 0) }')"
  snap15 window-end ) < /dev/null &
WEND_PID=$!
jq -n --argjson seed "$SEED_T" --argjson k6 "$K6_T0" --argjson hold "$PRE_K6_HOLD_S" --argjson target "$PRE_K6_TARGET_S" --argjson tol "$PRE_K6_TOL_S" --argjson topup "$topup" \
      --argjson lead "$PRE_K6_LEAD_S" --argjson pvus "$(P .k6.pre_vus_total)" \
  '{seed_done_epoch: $seed, k6_launch_epoch: $k6, seed_to_launch_s: (($k6 - $seed) * 1000 | round / 1000), hold_s: $hold, target_s: $target, tol_s: $tol, topup_s: $topup,
    lead_s: $lead, pre_vus_total: $pvus}' > "$DIR/pre-k6.json"
env $K6_ENV CLOCK_OFFSET_MS="$OFF_MS" BASE_URL="$BASE_URL" OUT_DIR="$DIR" "$K6_BIN" run --quiet --out "csv=$DIR/k6-requests.csv.gz" "$K6_TREE/$K6_SCRIPT" > "$DIR/k6-stdout.log" 2>&1 < /dev/null
K6_EXIT=$?; K6_END=$(now)
(( K6_EXIT == 0 )) || kill "$WEND_PID" 2>/dev/null
wait "$WEND_PID" || hreason app-snap-failed; WEND_PID=""
jq -n --arg s "$K6_STARTED" --arg e "$(date -Is)" --argjson t0 "$K6_T0" --argjson t1 "$K6_END" --argjson rc "$K6_EXIT" --argjson off "$OFF_MS" \
  '{k6_started: $s, k6_ended: $e, k6_t0_epoch: $t0, k6_end_epoch: $t1, exit: $rc, clock_offset_ms_given: $off}' > "$DIR/k6-window.json"
(( K6_EXIT == 0 )) || hreason "k6-exit-$K6_EXIT"
probes_stop14 "$DIR" "$K6_T0" "$K6_END"
CLK_AFTER=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
jq -n --argjson b "${CLK_BEFORE:-null}" --argjson a "${CLK_AFTER:-null}" '{before: $b, after: $a}' > "$DIR/clock-offset.json" 2>/dev/null || echo '{"before":null,"after":null}' > "$DIR/clock-offset.json"

# ---- 직후 · 수렴 · 끝 판정 --------------------------------------------------------------------------------------------------------------
sleep "$(awk -v e="$K6_END" -v w="$IDLE_AFTER_K6_S" -v n="$(now)" 'BEGIN { d = e + w - n; print (d > 0 ? d : 0) }')"
table_stats > "$DIR/table-after.json" 2>>"$DIR/errors.log" < /dev/null || hreason table-stats-failed
io_snapshot > "$DIR/io-after.json" 2>>"$DIR/errors.log" < /dev/null
drift_snap app > "$DIR/drift-after-k6.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-snap-failed
snap15 after-k6
if [[ "$PGSS" == 1 ]]; then pgss_snapshot > "$DIR/pgss-after-k6.json" 2>>"$DIR/errors.log" < /dev/null || hreason pgss-failed; fi
CONV=$(P .converge_s)
sleep "$(awk -v e="$K6_END" -v c="$CONV" -v n="$(now)" 'BEGIN { d = e + c - n; print (d > 0 ? d : 0) }')"
table_stats > "$DIR/table-end.json" 2>>"$DIR/errors.log" < /dev/null || hreason table-stats-failed
CONSISTENCY_TIMEOUT=600 consistency 20 > "$DIR/consistency.json" 2>>"$DIR/errors.log" < /dev/null; crc=$?
(( crc == 0 )) || { rm -f "$DIR/consistency.json"; echo "$crc" > "$DIR/consistency.rc"; hreason "end-check-failed"; }
drift_snap app > "$DIR/drift-end.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-snap-failed
snap15 end
if [[ "$PGSS" == 1 ]]; then pgss_snapshot > "$DIR/pgss-end.json" 2>>"$DIR/errors.log" < /dev/null || hreason pgss-failed; fi
drift_db_dump v10 > "$DIR/drift-db.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-db-failed
app_db_clock > "$DIR/app-db-clock-after.json" 2>>"$DIR/errors.log" < /dev/null

# ---- 수집 · 정리 ------------------------------------------------------------------------------------------------------------------------
stop_pollers14 $POLL_PIDS; POLL_PIDS=""
remote "pkill -f 'FROM pg_locks WHERE NOT granted' || true" > /dev/null 2>&1 < /dev/null
(( B3W_MS > 0 )) && psql_sampler_kill "$B3W_COPY" < /dev/null
if remote "cat '$SAMPLER_COPY'" > "$DIR/.sampler-remote.jsonl" 2>/dev/null < /dev/null && [[ -s "$DIR/.sampler-remote.jsonl" ]]; then
  nl=$(wc -l < "$DIR/timeline-locks.jsonl" 2>/dev/null || echo 0); nw=$(wc -l < "$DIR/timeline-waits.jsonl" 2>/dev/null || echo 0)
  rl=$(grep -c '"lock_waiting"' "$DIR/.sampler-remote.jsonl"); rw=$(grep -c '"waits"' "$DIR/.sampler-remote.jsonl")
  if (( rl > nl || rw > nw )); then grep '"lock_waiting"' "$DIR/.sampler-remote.jsonl" > "$DIR/timeline-locks.jsonl"; grep '"waits"' "$DIR/.sampler-remote.jsonl" > "$DIR/timeline-waits.jsonl"
    echo "$(date -Is) waits recovered-from-server locks $nl→$rl waits $nw→$rw" >> "$DIR/sampler-recovery.txt"
  else echo "$(date -Is) waits stream-complete" >> "$DIR/sampler-recovery.txt"; fi
else echo "$(date -Is) waits server-copy-unavailable" >> "$DIR/sampler-recovery.txt"; fi
rm -f "$DIR/.sampler-remote.jsonl"; remote "rm -f '$SAMPLER_COPY'" > /dev/null 2>&1 < /dev/null
(( B3W_MS > 0 )) && recover_copy "$DIR" "$B3W_COPY" timeline-b3wait.jsonl < /dev/null
for c in app db; do
  remote "docker logs --since '$STARTED' seatlab-$c-1 2>&1" < /dev/null | gzip > "$DIR/$c.log.gz"
  echo "$c pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]} bytes=$(zcat "$DIR/$c.log.gz" 2>/dev/null | wc -c)" >> "$DIR/logs-status.txt"
done
remote "docker exec seatlab-app-1 cat /dev/shm/gc.log" < /dev/null | gzip > "$DIR/gc.log.gz"
echo "app pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]} bytes=$(zcat "$DIR/gc.log.gz" 2>/dev/null | wc -c)" >> "$DIR/gc-status.txt"
python3 "$LIB9_DIR/summarize.py" --gc "$DIR" > "$DIR/gc.json" 2>>"$DIR/errors.log" || echo "gc summary failed" >> "$DIR/errors.log"
python3 -I "$LIB9_DIR/tail.py" --probe-check "$DIR" > /dev/null 2>>"$DIR/errors.log"
gzip -f "$DIR/k6-stdout.log"
finish15
