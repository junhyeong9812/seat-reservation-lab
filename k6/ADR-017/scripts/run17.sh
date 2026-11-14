#!/usr/bin/env bash
# ADR-017 회차 실행기 — (후보 R · D · S) × (셀 base · pg · kill) × 회차 번호 하나. ADR-016 run16.sh의 변형(같은 동결 · 해시 · 배포 · 이미지 고정 · 시작 대기 · 시계 차 —
#   바뀌는 것만 적는다 · 계약 = k6/ADR-017/INTERFACE.md):
#   내리기(seatlab 컨테이너 · DB 볼륨) → 이미지 확인(앱 · mock-pg · DB · Redis) → 기동(compose.pay17.yml — 후보 모드 · PG 주소) → 앱 1개 · mock-pg 확인 →
#   예열(warmup15 · PAY_START 끔 — 옛 경로만 · PG 호출 0) → 시드 reset(회차 1 × seats — V15 표 포함 TRUNCATE) → PG /admin/reset → /admin/faults(셀 주입 · base · kill = 규칙 없음) →
#   시드 확인 · 설정 수집 → 표본기(서버 docker stats · PC · 대기 · Hikari) → 시작 대기 → 지표 스냅숏 → k6(paid-flow17 — sessions17.log) ∥ kill 일정(kill 셀) →
#   배수 창(작업 큐 빔 + 안전망 한 주기, 또는 상한) → 덤프(원장 → DB → 원장 대조 → 판정기 → 지표) → 로그 → compose 내리기 → 판정(summarize17.py --rep → status).
#
#   scripts/run17.sh --sha <SHA> --images <images.json> --cand R|D|S --cell base|pg|kill --rep <k> --id <캠페인 id> [--attempt n]
#       [--k6-bin k6] [--worktree] [--results-root <폴더>] [--dry-run] [--simulate] [--smoke [--over '<json>']] [--local]
#   scripts/run17.sh --prepare-image --sha <SHA> --out <images.json> [--worktree] [--local]   (앱 · mock-pg 이미지 빌드(없을 때만) · DB · Redis 받기 → 이미지 ID 고정)
#   scripts/run17.sh --down --sha <SHA> [--worktree] [--local]                               (seatlab 스택 · 볼륨 내리기 — campaign17.sh 끝)
#     --smoke  = 측정 전 고정 값을 정하는 스모크(판정 미사용): FIXED 관문 예외 · id는 smoke17-로 시작 · 캠페인 안에서는 거부 · --over = plan17 --render --over(축소 회차).
#     --local  = 이 PC의 docker로(.164 대신 — 로컬 검증 전용 · --smoke --worktree 필요): ssh를 로컬 셸로 바꾸는 감싸개를 PATH 앞에 둔다(배포 폴더 = $LOCAL17_HOME/<REMOTE_BASE>) ·
#                db 포트 공개를 뺀다(compose.local17.yml). 환경 LOCAL17_HOME(필수 — 배포 폴더 뿌리).
#     결과 = <results-root 기본 k6/ADR-017/results>/<id>/<후보>/<셀>/rep<k>/ · --attempt n(>1)은 앞 시도를 rep<k>.attempt<n−1>로 옮긴다.
#   status: ok · invalid:<사유,…>. plan17.py FIXED가 False면 실제 실행을 거부한다(--dry-run · --simulate · --smoke만).
set -uo pipefail

if [[ "${RUN17_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"
  HH_SRC="$(bash "$src/hhash17.sh" "$repo")" || { echo "하네스 해시 실패($repo)" >&2; exit 2; }
  [[ -z "${HARNESS_HASH17:-}" || "$HH_SRC" == "$HARNESS_HASH17" ]] || { echo "하네스 해시 $HH_SRC ≠ 캠페인 동결본 $HARNESS_HASH17" >&2; exit 2; }
  frozen="$(mktemp -d)"
  mkdir -p "$frozen/k6/ADR-017" "$frozen/k6/ADR-016" "$frozen/k6/ADR-015" "$frozen/k6/ADR-014" "$frozen/k6/ADR-009"
  cp -r "$src" "$frozen/k6/ADR-017/scripts" && cp -r "$repo/k6/ADR-016/scripts" "$frozen/k6/ADR-016/scripts" \
    && cp -r "$repo/k6/ADR-015/scripts" "$frozen/k6/ADR-015/scripts" && cp -r "$repo/k6/ADR-014/scripts" "$frozen/k6/ADR-014/scripts" \
    && cp -r "$repo/k6/ADR-009/scripts" "$frozen/k6/ADR-009/scripts" || { echo "동결 사본 실패" >&2; exit 2; }
  export RUN17_FROZEN=1 FROZEN17="$frozen" HH_SRC ADR17_DIR="${ADR17_DIR:-$(cd "$src/.." && pwd)}" REPO_ROOT="${REPO_ROOT:-$repo}"
  exec bash "$frozen/k6/ADR-017/scripts/run17.sh" "$@"
fi
export LIB9_DIR="$FROZEN17/k6/ADR-009/scripts" LIB14_SRC="$FROZEN17/k6/ADR-014/scripts" LIB15_SRC="$FROZEN17/k6/ADR-015/scripts"
S17="$FROZEN17/k6/ADR-017/scripts" S14="$LIB14_SRC"
DRY=0 SIM=0 LOCAL=0; for a in "$@"; do [[ "$a" == --dry-run ]] && DRY=1; [[ "$a" == --simulate ]] && SIM=1; [[ "$a" == --local ]] && LOCAL=1; done
if (( LOCAL )); then   # 로컬 검증: ssh 감싸개(옵션 · 호스트를 버리고 배포 뿌리에서 bash -c) — timeout · python subprocess가 부르는 ssh도 이것을 탄다
  [[ -n "${LOCAL17_HOME:-}" ]] || { echo "--local은 LOCAL17_HOME(배포 폴더 뿌리)이 필요하다" >&2; exit 2; }
  mkdir -p "$LOCAL17_HOME" "$FROZEN17/bin" && export LOCAL17_HOME="$(cd "$LOCAL17_HOME" && pwd)"
  cat > "$FROZEN17/bin/ssh" <<'WRAP'
#!/usr/bin/env bash
while (( $# )); do case "$1" in -o|-p|-i|-l|-F) shift 2 ;; -*) shift ;; *) break ;; esac; done
shift   # 호스트
cd "$LOCAL17_HOME" || exit 255
exec bash -c "$*"
WRAP
  chmod +x "$FROZEN17/bin/ssh"; export PATH="$FROZEN17/bin:$PATH" SERVER="local@127.0.0.1"
fi
source "$S17/lib17.sh"
set +e
(( LOCAL )) && COMPOSE_EXTRA="$COMPOSE_EXTRA -f k6/ADR-017/compose.local17.yml"

APP_ENV14="" RES_ENV14=""   # --prepare-image · --down에서도 env14가 읽는다(set -u)
SHA="" IMAGES="" CAND="" CELL="" REP="" CID="" ATTEMPT=1 WORKTREE=0 K6_BIN=k6 RESULTS_ROOT="" SMOKE=0 OVER="" PREPARE=0 IMG_OUT="" DOWN=0
PRE_K6_HOLD_S="${PRE_K6_HOLD_S:-70}" PRE_K6_TARGET_S="${PRE_K6_TARGET_S:-87}" PRE_K6_TOL_S="${PRE_K6_TOL_S:-5}" PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-}"   # ADR-015 · 016 시작 대기 그대로
KILL_READY_MAX_S=120
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;; --images) IMAGES="$2"; shift 2 ;; --cand) CAND="$2"; shift 2 ;; --cell) CELL="$2"; shift 2 ;;
    --rep) REP="$2"; shift 2 ;; --id) CID="$2"; shift 2 ;; --attempt) ATTEMPT="$2"; shift 2 ;; --worktree) WORKTREE=1; shift ;;
    --k6-bin) K6_BIN="$2"; shift 2 ;; --results-root) RESULTS_ROOT="$2"; shift 2 ;; --smoke) SMOKE=1; shift ;; --over) OVER="$2"; shift 2 ;;
    --prepare-image) PREPARE=1; shift ;; --out) IMG_OUT="$2"; shift 2 ;; --down) DOWN=1; shift ;;
    --dry-run|--simulate|--local) shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
cleanup17() {
  [[ "$BASHPID" == "$$" ]] || return 0
  [[ -n "${POLL_PIDS:-}" ]] && stop_pollers14 $POLL_PIDS
  [[ -n "${KILL_PID:-}" ]] && kill "$KILL_PID" 2>/dev/null
  [[ -n "${K6_TREE:-}" && -d "${K6_TREE:-}" ]] && rm -rf "${K6_TREE:?}"
  [[ -n "${FROZEN17:-}" && "$FROZEN17" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "${FROZEN17:?}"
}
trap cleanup17 EXIT
short() { git -C "$REPO_ROOT" rev-parse --short=8 "$1^{commit}" 2>/dev/null; }
now() { date +%s.%N; }
(( LOCAL )) && { (( (SMOKE || PREPARE || DOWN) && WORKTREE )) || { echo "--local은 --worktree와 함께 --smoke · --prepare-image · --down에서만" >&2; exit 2; }; }
mapfile -t HPATHS < <(bash "$S17/hhash17.sh" --paths)

# 배포(커밋 트리 · --worktree면 하네스 경로만 작업트리로 덮음 — run16 같은 규칙)
deploy17() {
  if (( WORKTREE )); then
    remote "test -f $REMOTE_BASE/$DEPLOY_ID/.deployed" 2>/dev/null < /dev/null && return 0
    git -C "$REPO_ROOT" archive --format=tar "$SHA" -- . ":(exclude,glob)k6/*/results/**" | remote "rm -rf $REMOTE_BASE/.tmp-$DEPLOY_ID && mkdir -p $REMOTE_BASE/.tmp-$DEPLOY_ID && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID" \
      && tar -C "$REPO_ROOT" --exclude=k6/ADR-017/results --exclude=k6/ADR-016/results --exclude=k6/ADR-015/results --exclude=__pycache__ -cf - "${HPATHS[@]}" \
         | remote "cd $REMOTE_BASE/.tmp-$DEPLOY_ID && rm -rf ${HPATHS[*]} && cd - > /dev/null && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID && touch $REMOTE_BASE/.tmp-$DEPLOY_ID/.deployed && rm -rf $REMOTE_BASE/$DEPLOY_ID && mv $REMOTE_BASE/.tmp-$DEPLOY_ID $REMOTE_BASE/$DEPLOY_ID"
  else deploy_sha "$SHA"; fi
}
SHA="$(short "${SHA:-HEAD}")"; [[ -n "$SHA" ]] || { echo "SHA 해석 실패" >&2; exit 2; }
HARNESS="sha:$SHA" DEPLOY_ID="$SHA"
(( WORKTREE )) && { HARNESS="worktree:${HH_SRC:0:8}"; DEPLOY_ID="$SHA-wt17-${HH_SRC:0:8}"; }
(( LOCAL )) && remote "mkdir -p $REMOTE_BASE" < /dev/null

# ---- 이미지 준비 · 내리기 ------------------------------------------------------------------------------------------------------------
if (( PREPARE )); then
  [[ -n "$IMG_OUT" ]] || { echo "--prepare-image는 --out이 필요하다" >&2; exit 2; }
  (( DRY )) && { echo "deploy $DEPLOY_ID → build seatlab-app:$SHA · seatlab-mockpg:$SHA(없을 때만) → pull postgres:16 redis:7-alpine → $IMG_OUT"; exit 0; }
  deploy17 < /dev/null || { echo "배포 실패" >&2; exit 1; }
  if [[ -n "$(image_id "seatlab-app:$SHA")" ]]; then log "seatlab-app:$SHA 이미 있음 — 다시 빌드하지 않는다"
  else remote "cd $REMOTE_BASE/$DEPLOY_ID && docker build -q -t seatlab-app:$SHA --label org.seatlab.sha=$SHA ." >&2 < /dev/null || { echo "앱 빌드 실패 $SHA" >&2; exit 1; }; fi
  if [[ -n "$(image_id "seatlab-mockpg:$SHA")" ]]; then log "seatlab-mockpg:$SHA 이미 있음 — 다시 빌드하지 않는다"
  else remote "cd $REMOTE_BASE/$DEPLOY_ID && docker build -q -f mock-pg/Dockerfile -t seatlab-mockpg:$SHA --label org.seatlab.sha=$SHA ." >&2 < /dev/null || { echo "mock-pg 빌드 실패 $SHA" >&2; exit 1; }; fi
  for x in postgres:16 redis:7-alpine; do [[ -n "$(image_id "$x")" ]] || remote "docker pull -q $x" >&2 < /dev/null || { echo "받기 실패 $x" >&2; exit 1; }; done
  jq -n --arg s "$SHA" --arg id "$(image_id "seatlab-app:$SHA")" --arg mp "$(image_id "seatlab-mockpg:$SHA")" --arg pg "$(image_id postgres:16)" --arg rd "$(image_id redis:7-alpine)" \
        --arg at "$(date -Is)" \
    '{pinned_at: $at, app: {sha: $s, tag: "seatlab-app:\($s)", id: $id}, mockpg: {tag: "seatlab-mockpg:\($s)", id: $mp},
      aux: {"postgres:16": $pg, "redis:7-alpine": $rd, ("seatlab-mockpg:" + $s): $mp}}' > "$IMG_OUT"
  jq -e '[.app.id, .mockpg.id, (.aux[])] | all(test("^sha256:"))' "$IMG_OUT" > /dev/null || { echo "이미지 ID 비어 있음 — $IMG_OUT" >&2; exit 1; }
  cat "$IMG_OUT"; exit 0
fi
if (( DOWN )); then
  (( DRY )) && { echo "down: $(compose17) down -v @ $REMOTE_BASE/$DEPLOY_ID"; exit 0; }
  remote "test -d $REMOTE_BASE/$DEPLOY_ID" < /dev/null || deploy17 < /dev/null || { echo "배포 폴더 없음 · 배포 실패" >&2; exit 1; }
  APP_TAG="$SHA" compose_down14 "$DEPLOY_ID" < /dev/null; exit $?
fi

# ---- 인자 · 계획 ---------------------------------------------------------------------------------------------------------------------
[[ "$CAND" =~ ^(R|D|S)$ && "$CELL" =~ ^(base|pg|kill)$ && "$REP" =~ ^[1-3]$ && "$ATTEMPT" =~ ^[1-9]$ && -n "$CID" ]] \
  || { echo "--cand R|D|S --cell base|pg|kill --rep 1..3 --id <id> 필수" >&2; exit 2; }
if (( SMOKE )); then
  [[ "${CAMPAIGN17:-0}" != 1 ]] || { echo "--smoke는 캠페인 안에서 쓸 수 없다" >&2; exit 2; }
  [[ "$CID" == smoke17-* ]] || { echo "--smoke의 --id는 smoke17-로 시작해야 한다" >&2; exit 2; }
fi
[[ -z "$OVER" ]] || (( SMOKE )) || { echo "--over는 --smoke 전용" >&2; exit 2; }
PLAN_JSON="$(python3 -I "$S17/plan17.py" --render --cand "$CAND" --cell "$CELL" --rep "$REP" ${OVER:+--over "$OVER"})" || exit 2
(( SMOKE )) && PLAN_JSON="$(jq -c '. + {smoke: true}' <<< "$PLAN_JSON")"
P() { jq -r "$1" <<< "$PLAN_JSON"; }
if (( ! DRY && ! SIM && ! SMOKE )) && [[ "$(P .fixed)" != true ]]; then
  echo "plan17.py FIXED = False — 측정 전 고정 커밋 전이라 실제 실행을 거부한다(--dry-run · --simulate · --smoke만)" >&2; exit 2
fi
PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-$(P .k6.lead_s)}"
EXCL=(':(exclude)k6/ADR-017/results' ':(exclude)k6/ADR-016/results' ':(exclude)k6/ADR-015/results')
if (( WORKTREE )); then
  [[ "${CAMPAIGN17:-0}" != 1 || "${CAMPAIGN17_WORKTREE:-0}" == 1 ]] || { echo "--worktree는 스모크 전용" >&2; exit 2; }
elif [[ -n "${HARNESS_HASH17:-}" ]]; then
  :
elif ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- "${HPATHS[@]}" "${EXCL[@]}" 2>/dev/null \
     || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- "${HPATHS[@]}" "${EXCL[@]}")" ]]; then
  echo "하네스(${HPATHS[*]})가 $SHA와 다르다 — 커밋하거나 --worktree(스모크)" >&2; (( DRY || SIM )) || exit 2
fi
APP_ENV14="$(P '.app_env | to_entries | map("\(.key)=\(.value)") | join(" ")')"
RES_ENV14="${RES_ENV17:-APP_CPUS=4 DB_CPUS=4 APP_MEM=2g APP_JVM_OPTS=-XX:+UseZGC APP_JVM_OPTS2=-XX:+ZGenerational APP_JVM_OPTS3= APP_JVM_OPTS4= \
PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=12g DB_MEM=14g MOCKPG_CPUS=2 MOCKPG_MEM=1g}"   # ADR-016 run16 자원 + mock-pg
K6_ENV_Q=(); while IFS= read -r kv; do K6_ENV_Q+=("$kv"); done < <(P '.k6.env | to_entries[] | "\(.key)=\(.value)"')
K6_SCRIPT="$(P .k6.script)"
OUT_ROOT="${RESULTS_ROOT:-$ADR17_DIR/results}/$CID"; DIR="$OUT_ROOT/$CAND/$CELL/rep$REP"
SEATS=$(P .seed.seats)
if (( DRY )); then
  echo "# dry-run — 후보 $CAND($(P .mode)) · 셀 $CELL · 회차 $REP · 시도 $ATTEMPT · 하네스 $HARNESS · 배포 $DEPLOY_ID · FIXED $(P .fixed)"
  echo "결과 폴더: $DIR"
  echo "compose: $(compose17)"
  echo "env: $(env14)"
  echo "시드: reset 2×10000 → warmup15(PAY_START 끔) → reset 1×$SEATS → PG reset → faults $(P '.pg.faults | tojson')"
  echo "kill: $(P '.kill // "없음" | tojson')"
  echo "k6: ${K6_ENV_Q[*]} BASE_URL=$BASE_URL PG_URL=$MOCKPG_URL CLOCK_OFFSET_MS=<k6 직전 시계 차> $K6_BIN run --console-output=$DIR/sessions17.log <트리>/$K6_SCRIPT"
  echo "시각: 첫 요청 ${PRE_K6_TARGET_S}±${PRE_K6_TOL_S}초 · 도착 $(P .d_arr_s)초 · k6 약 $(P .k6.est_s)초(grace $(P .grace_s)) · 배수 상한 $(P .drain.cap_s)초 · 회차 추정 $(P .est_s)초(최악 $(P .est_max_s))"
  exit 0
fi
if (( SIM )); then   # campaign17.sh 시험용 — 서버 접속 없음(run16 --simulate와 같다)
  if [[ -e "$DIR" ]]; then
    if (( ATTEMPT > 1 )) && [[ ! -e "$DIR.attempt$((ATTEMPT - 1))" ]]; then mv "$DIR" "$DIR.attempt$((ATTEMPT - 1))"; else echo "$DIR 이미 있다" >&2; exit 2; fi
  fi
  mkdir -p "$DIR"; echo "$PLAN_JSON" | jq . > "$DIR/plan.json"
  st=ok; [[ -n "${SIM_PLAN:-}" && -s "$SIM_PLAN" ]] && { v=$(grep -m1 -F "$REP:$CAND:$CELL:$ATTEMPT=" "$SIM_PLAN" | cut -d= -f2-); [[ -n "$v" ]] && st="$v"; }
  jq -n --arg sha "$SHA" --arg c "$CAND" --arg g "$CELL" --argjson r "$REP" --argjson a "$ATTEMPT" --arg hh "${HH_SRC:-}" \
    '{sha: $sha, cand: $c, cell: $g, rep: $r, attempt: $a, rerun: ($a > 1), harness_hash: $hh, simulated: true}' > "$DIR/meta.json"
  [[ "$st" == skip ]] || echo "$st" > "$DIR/status"
  echo "$(date -Is) $CAND $CELL rep$REP attempt$ATTEMPT ${st} (simulated)" >> "$OUT_ROOT/MATRIX.log"
  exit 0
fi
(( $(date -u +%s) < $(date -u -d 2026-12-31T09:00:00Z +%s) )) || { echo "2026-12-31T09:00Z 이후 — 시드 회차가 측정 중 마감될 수 있다" >&2; exit 2; }
[[ -s "$IMAGES" ]] || { echo "--images <images.json>(run17.sh --prepare-image) 필수" >&2; exit 2; }
[[ "$(jq -r .app.sha "$IMAGES")" == "$SHA" ]] || { echo "images.json의 SHA가 --sha $SHA와 다르다" >&2; exit 2; }
APP_TAG="$SHA" APP_ID=$(jq -r .app.id "$IMAGES") MOCKPG_ID=$(jq -r '.mockpg.id // empty' "$IMAGES")
[[ -n "$MOCKPG_ID" ]] || { echo "images.json에 mockpg.id 없음(run17.sh --prepare-image)" >&2; exit 2; }
if [[ -e "$DIR" ]]; then
  if (( ATTEMPT > 1 )) && [[ ! -e "$DIR.attempt$((ATTEMPT - 1))" ]]; then mv "$DIR" "$DIR.attempt$((ATTEMPT - 1))"
  else echo "$DIR 이미 있다 — 덮어쓰지 않는다(재측정은 --attempt n)" >&2; exit 2; fi
fi
mkdir -p "$DIR"; MATRIX_LOG="$OUT_ROOT/MATRIX.log"
echo "$PLAN_JSON" | jq . > "$DIR/plan.json"
cp "$IMAGES" "$DIR/images.json"
STARTED=$(date -Is) STARTED_EPOCH=$(date +%s)
K6_TREE="$(mktemp -d)"
SCEN_PATHS=(k6/ADR-009/scenarios k6/ADR-014/scenarios k6/ADR-015/scenarios k6/ADR-017/scenarios)
if (( WORKTREE )); then for s in "${SCEN_PATHS[@]}"; do mkdir -p "$K6_TREE/$(dirname "$s")" && cp -r "$REPO_ROOT/$s" "$K6_TREE/$(dirname "$s")/"; done
else git -C "$REPO_ROOT" archive "$SHA" "${SCEN_PATHS[@]}" | tar -x -C "$K6_TREE"; fi
SCEN15="$K6_TREE/k6/ADR-015/scenarios"
HREASONS="$DIR/harness-reasons.txt"; : > "$HREASONS"
hreason() { grep -qxF "$1" "$HREASONS" 2>/dev/null || echo "$1" >> "$HREASONS"; echo "$(date -Is) harness: $1" >> "$DIR/errors.log"; }
snap17() { python3 -I "$S17/actsnap17.py" "$BASE_URL" > "$DIR/act17-$1.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/act17-$1.json"; hreason app-snap-failed; }; }
srv_ms() { echo $(( $(date +%s%3N) + ${OFF_MS:-0} )); }
INJ_EVENTS="$DIR/.inject-events.jsonl"; : > "$INJ_EVENTS"
pg_prepare17() {   # PG reset → faults(셀 주입) — 기록 = inject.json events(INTERFACE §6.6)
  local t code body resp rc
  t=$(srv_ms); code=$(pg_reset 2>>"$DIR/errors.log"); rc=$?
  jq -nc --argjson t "$t" --argjson rc "$rc" --arg c "$code" '{at: "reset", t_ms: $t, rc: $rc, http: ($c | tonumber? // null), request: null, response: null}' >> "$INJ_EVENTS"
  (( rc == 0 )) && [[ "$code" == 204 ]] || return 1
  body="$(P '.pg.faults | tojson')"
  t=$(srv_ms); resp=$(pg_set_faults "$body" 2>>"$DIR/errors.log"); rc=$?
  jq -nc --argjson t "$t" --argjson rc "$rc" --argjson rq "$body" --arg rs "$resp" \
    '{at: "faults", t_ms: $t, rc: $rc, http: (if $rc == 0 then 200 else null end), request: $rq, response: ($rs | fromjson? // null)}' >> "$INJ_EVENTS"
  (( rc == 0 ))
}
inject_write() { jq -s --arg c "$CELL" '{cell: $c, events: .}' "$INJ_EVENTS" > "$DIR/inject.json" 2>>"$DIR/errors.log" || hreason inject-write-failed; }
logs17() {
  for c in app db mock-pg; do
    remote "docker logs --since '$STARTED' seatlab-$c-1 2>&1" < /dev/null | gzip > "$DIR/$c.log.gz"
    echo "$c pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]} bytes=$(zcat "$DIR/$c.log.gz" 2>/dev/null | wc -c)" >> "$DIR/logs-status.txt"
  done
}
finish17() {
  local st="${1:-}"
  [[ -s "$INJ_EVENTS" ]] && inject_write; rm -f "$INJ_EVENTS"
  jq -n --arg sha "$SHA" --arg harness "$HARNESS" --arg c "$CAND" --arg g "$CELL" --argjson r "$REP" --argjson a "$ATTEMPT" \
        --arg started "$STARTED" --arg ended "$(date -Is)" --arg k6 "$("$K6_BIN" version 2>/dev/null | head -1)" --arg k6sha "$(sha256sum "$(command -v "$K6_BIN")" 2>/dev/null | cut -d' ' -f1)" \
        --arg app "$APP_ENV14" --arg res "$RES_ENV14" --arg k6env "${K6_ENV_Q[*]}" --arg hh "${HH_SRC:-}" --arg img "$APP_ID" --arg mp "$MOCKPG_ID" \
        --argjson seed "${SEED_T:-null}" --argjson k0 "${K6_T0:-null}" --argjson k1 "${K6_END:-null}" --argjson kx "${K6_EXIT:-null}" \
        --argjson pgs "$(P .pg.faults.seed)" --argjson kp "$(P '.kill.at_s // null')" --argjson loc "$LOCAL" --argjson sm "$SMOKE" \
    '{sha: $sha, harness: $harness, cand: $c, cell: $g, rep: $r, attempt: $a, rerun: ($a > 1), started: $started, ended: $ended,
      k6_version: $k6, k6_sha256: $k6sha, app_env: $app, res_env: $res, k6_env: $k6env, harness_hash: $hh, app_image_id: $img, mockpg_image_id: $mp,
      pg_faults_seed: $pgs, kill_planned_at_s: $kp, seed_done_epoch: $seed, k6_launch_epoch: $k0, k6_end_epoch: $k1, k6_exit: $kx,
      local: ($loc == 1), smoke: ($sm == 1)}' > "$DIR/meta.json.tmp" \
    && mv "$DIR/meta.json.tmp" "$DIR/meta.json" || { hreason meta-write-failed; st="invalid:meta-write-failed"; }
  if [[ -z "$st" ]]; then
    python3 -I "$S17/summarize17.py" --rep "$DIR" > "$DIR/.summarize.out" 2>>"$DIR/errors.log"; local src=$?
    st=$(tail -n 1 "$DIR/.summarize.out"); rm -f "$DIR/.summarize.out"
    (( src == 0 )) && [[ "$st" =~ ^(ok|invalid:) ]] || st="invalid:summarize-failed"
  fi
  echo "$st" > "$DIR/status"
  echo "$(date -Is) $CAND $CELL rep$REP attempt$ATTEMPT $st" >> "$MATRIX_LOG"
  log "$CAND $CELL rep$REP → $st"
}
down17() { compose_down14 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || hreason compose-down-failed; }   # 회차 끝 내리기(캠페인 사이 · 끝 — 스택을 남기지 않는다)
abort17() {
  hreason "$1"
  logs17
  [[ -n "${POLL_PIDS:-}" ]] && { stop_pollers14 $POLL_PIDS; POLL_PIDS=""; }
  [[ -n "${KILL_PID:-}" ]] && { kill "$KILL_PID" 2>/dev/null; KILL_PID=""; }
  down17
  finish17 "invalid:$1"; exit 0
}

# ---- 기동 · 예열 · 시드 · PG 준비 ------------------------------------------------------------------------------------------------------
log "ADR-017 $CAND $CELL rep$REP attempt$ATTEMPT → $DIR"
deploy17 < /dev/null || abort17 deploy-failed
compose_down14 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || abort17 compose-down-failed
[[ "$(image_id "seatlab-app:$APP_TAG")" == "$APP_ID" ]] || abort17 cond-image-mismatch
for img in $(jq -r '.aux | keys[]' "$IMAGES"); do [[ "$(image_id "$img")" == "$(jq -r --arg k "$img" '.aux[$k]' "$IMAGES")" ]] || abort17 aux-image-mismatch; done
compose_up17 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || abort17 compose-up-failed
wait_health 240 || abort17 unhealthy
[[ "$(ctr_image seatlab-app-1)" == "$APP_ID" ]] || abort17 cond-image-mismatch
[[ "$(ctr_image seatlab-mock-pg-1)" == "$MOCKPG_ID" ]] || abort17 aux-image-mismatch
remote "docker ps --filter label=com.docker.compose.project=seatlab --filter label=com.docker.compose.service=app --format '{{.Names}}'" < /dev/null > "$DIR/app-instances.txt" 2>>"$DIR/errors.log"
[[ "$(grep -c . "$DIR/app-instances.txt")" == 1 ]] || abort17 app-instances-not-one
pg_ready 120 < /dev/null || abort17 mockpg-unready
reset_with_retry "$DIR" 2 10000 > /dev/null || abort17 reset-failed
BASE_URL="$BASE_URL" "$K6_BIN" run --quiet --out "csv=$DIR/warmup-requests.csv.gz" "$SCEN15/warmup15.js" > "$DIR/warmup.log" 2>&1 < /dev/null   # PAY_START 끔 — 옛 경로만(intent · PG 등록 없음)
WARM_RC=$?
python3 -I "$S14/summarize.py" --warmup-status "$DIR/warmup-requests.csv.gz" "$WARM_RC" > "$DIR/warmup-status.json" 2>>"$DIR/errors.log"
(( WARM_RC == 0 )) || abort17 warmup-failed
RESET_TIMEOUT=1800 reset_with_retry "$DIR" 1 "$SEATS" > "$DIR/seed.json" || abort17 seed-failed
SEED_T=$(now)
CLK0=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)   # PG 기록 시각용 시계 차(k6 직전에 다시 잰다)
OFF_MS=$(jq -r 'if (.offset_s | type) == "number" then (.offset_s * 1000 | round) else "" end' <<< "${CLK0:-null}" 2>/dev/null)
[[ "$OFF_MS" =~ ^-?[0-9]+$ ]] || abort17 clock-offset-bad
pg_prepare17 < /dev/null || abort17 pg-prepare-failed
seed_check15 1 "$SEATS" > "$DIR/seed-check.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/seed-check.json"; hreason seed-check-failed; }
db_state > "$DIR/db-state-seed.json" 2>>"$DIR/errors.log" < /dev/null
app_config17 "$DIR" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/app-config.json"; hreason app-config-failed; }
pg_settings > "$DIR/pg-settings.json" 2>>"$DIR/errors.log" < /dev/null

# ---- 표본기 · 시작 대기 ---------------------------------------------------------------------------------------------------------------
TAG17="$(echo "$CID" | tr '/' '_')-$CAND-$CELL-rep$REP-$(date +%Y%m%d%H%M%S)-$RANDOM"
SAMPLER_COPY="$REMOTE_BASE/$DEPLOY_ID/.samples/$TAG17.jsonl"
POLL_PIDS="$(start_pollers14 "$DIR" cost 1 | xargs)"
hold_left=$(awk -v s="$SEED_T" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { d = s + h - n; printf "%.3f", (d > 0 ? d : 0) }')
awk -v s="$SEED_T" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { exit !(n - s > h + 1) }' && abort17 pre-k6-overrun
sleep "$hold_left"
table_stats > "$DIR/table-before.json" 2>>"$DIR/errors.log" < /dev/null
snap17 before
app_db_clock > "$DIR/app-db-clock-before.json" 2>>"$DIR/errors.log" < /dev/null
awk -v n="$(now)" -v b="$SEED_T" -v t="$PRE_K6_TARGET_S" -v l="$PRE_K6_TOL_S" -v lead="$PRE_K6_LEAD_S" 'BEGIN { exit !((n - b) + lead > t + l) }' && abort17 pre-k6-overrun
topup=$(awk -v t="$PRE_K6_TARGET_S" -v n="$(now)" -v b="$SEED_T" -v lead="$PRE_K6_LEAD_S" 'BEGIN { d = t - lead - (n - b); printf "%.3f", (d > 0 ? d : 0) }')
sleep "$topup"

# ---- k6 ∥ kill 일정 -----------------------------------------------------------------------------------------------------------------
CLK_BEFORE=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
OFF_MS=$(jq -r 'if (.offset_s | type) == "number" then (.offset_s * 1000 | round) else "" end' <<< "${CLK_BEFORE:-null}" 2>/dev/null)
[[ "$OFF_MS" =~ ^-?[0-9]+$ ]] || abort17 clock-offset-bad
K6_STARTED=$(date -Is); K6_T0=$(now); K6_T0_MS=$(awk -v t="$K6_T0" -v o="$OFF_MS" 'BEGIN { printf "%.0f", t * 1000 + o }')
KILL_EV="$DIR/.kill-events.jsonl"; KILL_PID=""
if [[ "$CELL" == kill ]]; then
  : > "$KILL_EV"
  ( n=0
    for at in $(P '.kill.at_s[]'); do
      n=$((n + 1))
      d=$(awk -v k="$K6_T0" -v a="$at" -v t="$(now)" 'BEGIN { x = k + a - t; printf "%.3f", (x > 0 ? x : 0) }'); sleep "$d"
      pms=$(awk -v k="$K6_T0" -v a="$at" -v o="$OFF_MS" 'BEGIN { printf "%.0f", (k + a) * 1000 + o }')
      kms=$(srv_ms); app_kill17; krc=$?
      sms=$(srv_ms); app_start17; src=$?
      rms=null; ok=false
      for _ in $(seq 1 $((KILL_READY_MAX_S * 2))); do
        curl -fsS --max-time 2 "$BASE_URL/actuator/health" 2>/dev/null | grep -q '"UP"' && { rms=$(srv_ms); ok=true; break; }
        sleep 0.5
      done
      jq -nc --argjson n "$n" --argjson p "$pms" --argjson k "$kms" --argjson kr "$krc" --argjson s "$sms" --argjson sr "$src" --argjson r "$rms" --argjson ok "$ok" \
        --arg sa "$(app_started_at17)" '{n: $n, planned_ms: $p, kill_ms: $k, kill_rc: $kr, start_ms: $s, start_rc: $sr, ready_ms: $r, ready_ok: $ok, started_at: $sa}' >> "$KILL_EV"
    done ) < /dev/null > /dev/null 2>>"$DIR/errors.log" &
  KILL_PID=$!
fi
env "${K6_ENV_Q[@]}" CLOCK_OFFSET_MS="$OFF_MS" BASE_URL="$BASE_URL" PG_URL="$MOCKPG_URL" OUT_DIR="$DIR" \
  "$K6_BIN" run --quiet --log-format=json --console-output="$DIR/sessions17.log" --out "csv=$DIR/k6-requests.csv.gz" "$K6_TREE/$K6_SCRIPT" > "$DIR/k6-stdout.log" 2>&1 < /dev/null
K6_EXIT=$?; K6_END=$(now)
if [[ -n "$KILL_PID" ]]; then   # kill 일정이 아직 남았으면(k6가 일찍 끝남) 기다리지 않는다 — 남은 kill은 없던 것으로(kill-not-observed)
  last_at=$(P '.kill.at_s | max')
  while kill -0 "$KILL_PID" 2>/dev/null && awk -v k="$K6_T0" -v a="$last_at" -v m="$KILL_READY_MAX_S" -v t="$(now)" 'BEGIN { exit !(t < k + a + m + 5) }'; do sleep 1; done
  kill "$KILL_PID" 2>/dev/null; wait "$KILL_PID" 2>/dev/null; KILL_PID=""
  jq -s --argjson pa "$(P '.kill.at_s')" --argjson t0 "$K6_T0_MS" '{planned_at_s: $pa, k6_t0_ms: $t0, events: .}' "$KILL_EV" > "$DIR/kill.json" 2>>"$DIR/errors.log" || hreason kill-write-failed
  rm -f "$KILL_EV"
  wait_health "$KILL_READY_MAX_S" || hreason app-not-back-after-kill
fi
jq -n --arg s "$K6_STARTED" --arg e "$(date -Is)" --argjson t0 "$K6_T0" --argjson t1 "$K6_END" --argjson rc "$K6_EXIT" --argjson off "$OFF_MS" \
  '{k6_started: $s, k6_ended: $e, k6_t0_epoch: $t0, k6_end_epoch: $t1, exit: $rc, clock_offset_ms_given: $off}' > "$DIR/k6-window.json"
(( K6_EXIT == 0 )) || hreason "k6-exit-$K6_EXIT"
[[ -f "$DIR/sessions17.log" ]] && gzip -f "$DIR/sessions17.log"
CLK_AFTER=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
jq -n --argjson b "${CLK_BEFORE:-null}" --argjson a "${CLK_AFTER:-null}" '{before: $b, after: $a}' > "$DIR/clock-offset.json" 2>/dev/null || echo '{"before":null,"after":null}' > "$DIR/clock-offset.json"
snap17 after-k6

# ---- 배수 창(INTERFACE §6.5): pending = 0 · open = 0 조회가 연달아 둘이고 그 사이 ≥ 안전망 + 워커 주기 · 아니면 상한 ---------------------------
CAP_S=$(P .drain.cap_s) POLL_S=$(P .drain.poll_s)
QUIET_MS=$(P '((.values.compensation.sweep.interval_s + .values.compensation.worker.interval_s) * 1000)')
DRAIN_EV="$DIR/.drain-polls.jsonl"; : > "$DIR/.drain-polls.jsonl"
D0=$(srv_ms); reason=cap; zero_since=""; zero_n=0
while awk -v e="$K6_END" -v c="$CAP_S" -v t="$(now)" 'BEGIN { exit !(t < e + c) }'; do
  t=$(srv_ms); r=$(drain_poll17 2>>"$DIR/errors.log" < /dev/null | tr -d '[:space:]')
  if jq -e 'type == "object" and (.pending | type) == "number"' <<< "${r:-null}" > /dev/null 2>&1; then
    jq -c --argjson t "$t" '{t_ms: $t} + .' <<< "$r" >> "$DRAIN_EV"
    if [[ "$(jq -r '.pending == 0 and .open == 0' <<< "$r")" == true ]]; then
      [[ -n "$zero_since" ]] || zero_since=$t; zero_n=$((zero_n + 1))
      if (( zero_n >= 2 && t - zero_since >= QUIET_MS )); then reason=empty; break; fi
    else zero_since=""; zero_n=0; fi
  else
    jq -nc --argjson t "$t" --arg e "${r:-}" '{t_ms: $t, err: $e}' >> "$DRAIN_EV"; zero_since=""; zero_n=0
  fi
  sleep "$POLL_S"
done
jq -s --argjson s "$D0" --argjson e "$(srv_ms)" --arg r "$reason" --argjson c "$CAP_S" --argjson p "$POLL_S" \
  '{start_ms: $s, end_ms: $e, reason: $r, cap_s: $c, poll_s: $p, polls: .}' "$DRAIN_EV" > "$DIR/drain.json" 2>>"$DIR/errors.log" || hreason drain-write-failed
rm -f "$DRAIN_EV"

# ---- 덤프(순서 고정 — INTERFACE §1: 원장 → DB → 원장 대조 → 판정기 → 지표) -------------------------------------------------------------
pg_ledger > "$DIR/pg-ledger.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/pg-ledger.json"; hreason ledger-dump-failed; }
db_dump17 > "$DIR/db-dump.json" 2>>"$DIR/errors.log" < /dev/null && jq -e 'type == "object"' "$DIR/db-dump.json" > /dev/null 2>&1 || { rm -f "$DIR/db-dump.json"; hreason db-dump-failed; }
if [[ -s "$DIR/pg-ledger.json" ]]; then   # 덤프 사이 원장 변화(dump-race) — 처리 호출 수 합 대조
  o1=$(jq '[.ops[]] | add // 0' "$DIR/pg-ledger.json"); o2=$(pg_ledger 2>/dev/null < /dev/null | jq '[.ops[]] | add // 0' 2>/dev/null)
  [[ -n "$o2" && "$o1" == "$o2" ]] || hreason dump-race
fi
CONSISTENCY_TIMEOUT=600 consistency 20 > "$DIR/consistency.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/consistency.json"; hreason end-check-failed; }
snap17 end
table_stats > "$DIR/table-end.json" 2>>"$DIR/errors.log" < /dev/null
app_db_clock > "$DIR/app-db-clock-after.json" 2>>"$DIR/errors.log" < /dev/null

# ---- 수집 · 내리기 · 판정 ------------------------------------------------------------------------------------------------------------
stop_pollers14 $POLL_PIDS; POLL_PIDS=""
remote "pkill -f 'FROM pg_locks WHERE NOT granted' || true; rm -f '$SAMPLER_COPY'" > /dev/null 2>&1 < /dev/null
logs17
gzip -f "$DIR/k6-stdout.log"
down17
finish17
