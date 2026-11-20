#!/usr/bin/env bash
# ADR-018 회차 실행기 — (방식 B0 · B1 · split · one-shot) × (셀 low · mid · high · edge · burst) × 회차 번호 하나. ADR-017 run17.sh의 변형(같은 동결 · 해시 · 배포 · 이미지 고정 ·
#   시작 대기 · 시계 차 · PG 리셋 · 배수 · 덤프 순서 — 바뀌는 것만 적는다 · 계약 = k6/ADR-018/INTERFACE.md):
#   내리기(seatlab 컨테이너 · DB 볼륨) → 이미지 확인(앱 · mock-pg · DB · Redis) → 기동(compose.pay18.yml — 묶음 손잡이 · 경계 셀 눈금) → 앱 1개 · mock-pg 확인 →
#   예열(warmup15 · PAY_START 끔 — 옛 경로만 · PG 호출 0) → 시드 reset(회차 1 × seats — V15 · V16 표 포함 TRUNCATE) → PG /admin/reset → /admin/faults(규칙 없음 — 주입 없음) →
#   시드 확인 · 설정 수집 → 표본기 → 시작 대기 → 지표 스냅숏 → k6(flow18 — sessions18.log) → 정합 스냅숏(k6 직후) →
#   배수 창(작업 큐 빔 · OPEN 결제 의도 0 · 남은 홀드 0 + 안전망 한 주기, 또는 상한) → 덤프(원장 → DB → 원장 대조 → 판정기 → 지표) → 로그 → compose 내리기 →
#   판정(summarize18.py --rep → status). kill 셀 · PG 주입은 없다(명세 Q15).
#
#   scripts/run18.sh --sha <SHA> --images <images.json> --method B0|B1|split|one-shot --cell low|mid|high|edge|burst --rep <k> --id <캠페인 id> [--attempt n]
#       [--k6-bin k6] [--worktree] [--results-root <폴더>] [--dry-run] [--simulate] [--smoke [--over '<json>']] [--local]
#   scripts/run18.sh --prepare-image --sha <SHA> --out <images.json> [--worktree] [--local]   (앱 · mock-pg 이미지 빌드(없을 때만) · DB · Redis 받기 → 이미지 ID 고정)
#   scripts/run18.sh --down --sha <SHA> [--worktree] [--local]                               (seatlab 스택 · 볼륨 내리기 — campaign18.sh 끝)
#     --smoke  = 측정 전 고정 값을 정하는 스모크(판정 미사용): FIXED 관문 예외 · id는 smoke18-로 시작 · 캠페인 안에서는 거부 · --over = plan18 --render --over(축소 회차).
#     --local  = 이 PC의 docker로(.164 대신 — 로컬 검증 전용 · --smoke --worktree 필요): ssh를 로컬 셸로 바꾸는 감싸개를 PATH 앞에 둔다(배포 폴더 = $LOCAL18_HOME/<REMOTE_BASE>) ·
#                db 포트 공개를 뺀다(compose.local18.yml). 환경 LOCAL18_HOME(필수 — 배포 폴더 뿌리).
#     결과 = <results-root 기본 k6/ADR-018/results>/<id>/<방식>/<셀>/rep<k>/ · --attempt n(>1)은 앞 시도를 rep<k>.attempt<n−1>로 옮긴다.
#   status: ok · invalid:<사유,…>. plan18.py FIXED가 False면 실제 실행을 거부한다(--dry-run · --simulate · --smoke만).
set -uo pipefail

if [[ "${RUN18_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"
  HH_SRC="$(bash "$src/hhash18.sh" "$repo")" || { echo "하네스 해시 실패($repo)" >&2; exit 2; }
  [[ -z "${HARNESS_HASH18:-}" || "$HH_SRC" == "$HARNESS_HASH18" ]] || { echo "하네스 해시 $HH_SRC ≠ 캠페인 동결본 $HARNESS_HASH18" >&2; exit 2; }
  frozen="$(mktemp -d)"
  mkdir -p "$frozen/k6/ADR-018" "$frozen/k6/ADR-017" "$frozen/k6/ADR-016" "$frozen/k6/ADR-015" "$frozen/k6/ADR-014" "$frozen/k6/ADR-009"
  cp -r "$src" "$frozen/k6/ADR-018/scripts" && cp -r "$repo/k6/ADR-017/scripts" "$frozen/k6/ADR-017/scripts" \
    && cp -r "$repo/k6/ADR-016/scripts" "$frozen/k6/ADR-016/scripts" && cp -r "$repo/k6/ADR-015/scripts" "$frozen/k6/ADR-015/scripts" \
    && cp -r "$repo/k6/ADR-014/scripts" "$frozen/k6/ADR-014/scripts" && cp -r "$repo/k6/ADR-009/scripts" "$frozen/k6/ADR-009/scripts" \
    || { echo "동결 사본 실패" >&2; exit 2; }
  export RUN18_FROZEN=1 FROZEN18="$frozen" HH_SRC ADR18_DIR="${ADR18_DIR:-$(cd "$src/.." && pwd)}" REPO_ROOT="${REPO_ROOT:-$repo}"
  exec bash "$frozen/k6/ADR-018/scripts/run18.sh" "$@"
fi
export LIB9_DIR="$FROZEN18/k6/ADR-009/scripts" LIB14_SRC="$FROZEN18/k6/ADR-014/scripts" LIB15_SRC="$FROZEN18/k6/ADR-015/scripts" LIB17_SRC="$FROZEN18/k6/ADR-017/scripts"
S18="$FROZEN18/k6/ADR-018/scripts" S14="$LIB14_SRC"
DRY=0 SIM=0 LOCAL=0; for a in "$@"; do [[ "$a" == --dry-run ]] && DRY=1; [[ "$a" == --simulate ]] && SIM=1; [[ "$a" == --local ]] && LOCAL=1; done
if (( LOCAL )); then   # 로컬 검증: ssh 감싸개(run17 --local과 같다)
  [[ -n "${LOCAL18_HOME:-}" ]] || { echo "--local은 LOCAL18_HOME(배포 폴더 뿌리)이 필요하다" >&2; exit 2; }
  mkdir -p "$LOCAL18_HOME" "$FROZEN18/bin" && export LOCAL18_HOME="$(cd "$LOCAL18_HOME" && pwd)"
  cat > "$FROZEN18/bin/ssh" <<'WRAP'
#!/usr/bin/env bash
while (( $# )); do case "$1" in -o|-p|-i|-l|-F) shift 2 ;; -*) shift ;; *) break ;; esac; done
shift   # 호스트
cd "$LOCAL18_HOME" || exit 255
exec bash -c "$*"
WRAP
  chmod +x "$FROZEN18/bin/ssh"; export PATH="$FROZEN18/bin:$PATH" SERVER="local@127.0.0.1"
fi
source "$S18/lib18.sh"
set +e
(( LOCAL )) && COMPOSE_EXTRA="$COMPOSE_EXTRA -f k6/ADR-018/compose.local18.yml"

APP_ENV14="" RES_ENV14=""
SHA="" IMAGES="" METHOD="" CELL="" REP="" CID="" ATTEMPT=1 WORKTREE=0 K6_BIN=k6 RESULTS_ROOT="" SMOKE=0 OVER="" PREPARE=0 IMG_OUT="" DOWN=0
PRE_K6_HOLD_S="${PRE_K6_HOLD_S:-70}" PRE_K6_TARGET_S="${PRE_K6_TARGET_S:-87}" PRE_K6_TOL_S="${PRE_K6_TOL_S:-5}" PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-}"   # ADR-015~017 시작 대기 그대로
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;; --images) IMAGES="$2"; shift 2 ;; --method) METHOD="$2"; shift 2 ;; --cell) CELL="$2"; shift 2 ;;
    --rep) REP="$2"; shift 2 ;; --id) CID="$2"; shift 2 ;; --attempt) ATTEMPT="$2"; shift 2 ;; --worktree) WORKTREE=1; shift ;;
    --k6-bin) K6_BIN="$2"; shift 2 ;; --results-root) RESULTS_ROOT="$2"; shift 2 ;; --smoke) SMOKE=1; shift ;; --over) OVER="$2"; shift 2 ;;
    --prepare-image) PREPARE=1; shift ;; --out) IMG_OUT="$2"; shift 2 ;; --down) DOWN=1; shift ;;
    --dry-run|--simulate|--local) shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
cleanup18() {
  [[ "$BASHPID" == "$$" ]] || return 0
  [[ -n "${POLL_PIDS:-}" ]] && stop_pollers14 $POLL_PIDS
  [[ -n "${SAMPLE_PID:-}" ]] && kill "$SAMPLE_PID" 2>/dev/null
  [[ -n "${K6_TREE:-}" && -d "${K6_TREE:-}" ]] && rm -rf "${K6_TREE:?}"
  [[ -n "${FROZEN18:-}" && "$FROZEN18" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "${FROZEN18:?}"
}
trap cleanup18 EXIT
short() { git -C "$REPO_ROOT" rev-parse --short=8 "$1^{commit}" 2>/dev/null; }
now() { date +%s.%N; }
(( LOCAL )) && { (( (SMOKE || PREPARE || DOWN) && WORKTREE )) || { echo "--local은 --worktree와 함께 --smoke · --prepare-image · --down에서만" >&2; exit 2; }; }
mapfile -t HPATHS < <(bash "$S18/hhash18.sh" --paths)
RES_EXCL=(--exclude=k6/ADR-018/results --exclude=k6/ADR-017/results --exclude=k6/ADR-016/results --exclude=k6/ADR-015/results --exclude=__pycache__)

# 배포(커밋 트리 · --worktree면 하네스 경로만 작업트리로 덮음 — run17 같은 규칙)
deploy18() {
  if (( WORKTREE )); then
    remote "test -f $REMOTE_BASE/$DEPLOY_ID/.deployed" 2>/dev/null < /dev/null && return 0
    git -C "$REPO_ROOT" archive --format=tar "$SHA" -- . ":(exclude,glob)k6/*/results/**" | remote "rm -rf $REMOTE_BASE/.tmp-$DEPLOY_ID && mkdir -p $REMOTE_BASE/.tmp-$DEPLOY_ID && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID" \
      && tar -C "$REPO_ROOT" "${RES_EXCL[@]}" -cf - "${HPATHS[@]}" \
         | remote "cd $REMOTE_BASE/.tmp-$DEPLOY_ID && rm -rf ${HPATHS[*]} && cd - > /dev/null && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID && touch $REMOTE_BASE/.tmp-$DEPLOY_ID/.deployed && rm -rf $REMOTE_BASE/$DEPLOY_ID && mv $REMOTE_BASE/.tmp-$DEPLOY_ID $REMOTE_BASE/$DEPLOY_ID"
  else deploy_sha "$SHA"; fi
}
SHA="$(short "${SHA:-HEAD}")"; [[ -n "$SHA" ]] || { echo "SHA 해석 실패" >&2; exit 2; }
HARNESS="sha:$SHA" DEPLOY_ID="$SHA"
(( WORKTREE )) && { HARNESS="worktree:${HH_SRC:0:8}"; DEPLOY_ID="$SHA-wt18-${HH_SRC:0:8}"; }
(( LOCAL )) && remote "mkdir -p $REMOTE_BASE" < /dev/null

# ---- 이미지 준비 · 내리기(run17과 같다 — 앱 · mock-pg 이미지는 같은 SHA에서 빌드) ---------------------------------------------------------
if (( PREPARE )); then
  [[ -n "$IMG_OUT" ]] || { echo "--prepare-image는 --out이 필요하다" >&2; exit 2; }
  (( DRY )) && { echo "deploy $DEPLOY_ID → build seatlab-app:$SHA · seatlab-mockpg:$SHA(없을 때만) → pull postgres:16 redis:7-alpine → $IMG_OUT"; exit 0; }
  deploy18 < /dev/null || { echo "배포 실패" >&2; exit 1; }
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
  (( DRY )) && { echo "down: $(compose18) down -v @ $REMOTE_BASE/$DEPLOY_ID"; exit 0; }
  remote "test -d $REMOTE_BASE/$DEPLOY_ID" < /dev/null || deploy18 < /dev/null || { echo "배포 폴더 없음 · 배포 실패" >&2; exit 1; }
  APP_TAG="$SHA" compose_down14 "$DEPLOY_ID" < /dev/null; exit $?
fi

# ---- 인자 · 계획 ---------------------------------------------------------------------------------------------------------------------
[[ "$METHOD" =~ ^(B0|B1|split|one-shot)$ && "$CELL" =~ ^(low|mid|high|edge|burst)$ && "$REP" =~ ^[1-3]$ && "$ATTEMPT" =~ ^[1-9]$ && -n "$CID" ]] \
  || { echo "--method B0|B1|split|one-shot --cell low|mid|high|edge|burst --rep 1..3 --id <id> 필수" >&2; exit 2; }
[[ ! "$CELL" =~ ^(edge|burst)$ || "$METHOD" =~ ^(split|one-shot)$ ]] || { echo "경계 셀(edge) · 동시 경합 셀(burst)은 split · one-shot만" >&2; exit 2; }
if (( SMOKE )); then
  [[ "${CAMPAIGN18:-0}" != 1 ]] || { echo "--smoke는 캠페인 안에서 쓸 수 없다" >&2; exit 2; }
  [[ "$CID" == smoke18-* ]] || { echo "--smoke의 --id는 smoke18-로 시작해야 한다" >&2; exit 2; }
fi
[[ -z "$OVER" ]] || (( SMOKE )) || { echo "--over는 --smoke 전용" >&2; exit 2; }
PLAN_JSON="$(python3 -I "$S18/plan18.py" --render --method "$METHOD" --cell "$CELL" --rep "$REP" ${OVER:+--over "$OVER"})" || exit 2
(( SMOKE )) && PLAN_JSON="$(jq -c '. + {smoke: true}' <<< "$PLAN_JSON")"
P() { jq -r "$1" <<< "$PLAN_JSON"; }
if (( ! DRY && ! SIM && ! SMOKE )) && [[ "$(P .fixed)" != true ]]; then
  echo "plan18.py FIXED = False — 측정 전 고정 커밋 전이라 실제 실행을 거부한다(--dry-run · --simulate · --smoke만)" >&2; exit 2
fi
PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-$(P .k6.lead_s)}"
EXCL=(':(exclude)k6/ADR-018/results' ':(exclude)k6/ADR-017/results' ':(exclude)k6/ADR-016/results' ':(exclude)k6/ADR-015/results')
if (( WORKTREE )); then
  [[ "${CAMPAIGN18:-0}" != 1 || "${CAMPAIGN18_WORKTREE:-0}" == 1 ]] || { echo "--worktree는 스모크 전용" >&2; exit 2; }
elif [[ -n "${HARNESS_HASH18:-}" ]]; then
  :
elif ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- "${HPATHS[@]}" "${EXCL[@]}" 2>/dev/null \
     || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- "${HPATHS[@]}" "${EXCL[@]}")" ]]; then
  echo "하네스(${HPATHS[*]})가 $SHA와 다르다 — 커밋하거나 --worktree(스모크)" >&2; (( DRY || SIM )) || exit 2
fi
APP_ENV14="$(P '.app_env | to_entries | map("\(.key)=\(.value)") | join(" ")')"
RES_ENV14="${RES_ENV18:-APP_CPUS=4 DB_CPUS=4 APP_MEM=2g APP_JVM_OPTS=-XX:+UseZGC APP_JVM_OPTS2=-XX:+ZGenerational APP_JVM_OPTS3= APP_JVM_OPTS4= \
PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=12g DB_MEM=14g MOCKPG_CPUS=2 MOCKPG_MEM=1g}"   # ADR-017 run17 자원 그대로
K6_ENV_Q=(); while IFS= read -r kv; do K6_ENV_Q+=("$kv"); done < <(P '.k6.env | to_entries[] | "\(.key)=\(.value)"')
K6_SCRIPT="$(P .k6.script)"
OUT_ROOT="${RESULTS_ROOT:-$ADR18_DIR/results}/$CID"; DIR="$OUT_ROOT/$METHOD/$CELL/rep$REP"
SEATS=$(P .seed.seats)
CGRACE=$(P .consistency_grace_s)
if (( DRY )); then
  echo "# dry-run — 방식 $METHOD · 셀 $CELL(겹침 $(P .overlap) · H $(P .seed.H)) · 회차 $REP · 시도 $ATTEMPT · 하네스 $HARNESS · 배포 $DEPLOY_ID · FIXED $(P .fixed)"
  echo "결과 폴더: $DIR"
  echo "compose: $(compose18)"
  echo "env: $(env14)"
  echo "시드: reset 2×10000 → warmup15(PAY_START 끔) → reset 1×$SEATS → PG reset → faults $(P '.pg.faults | tojson')"
  echo "k6: ${K6_ENV_Q[*]} BASE_URL=$BASE_URL PG_URL=$MOCKPG_URL CLOCK_OFFSET_MS=<k6 직전 시계 차> $K6_BIN run --console-output=$DIR/sessions18.log <트리>/$K6_SCRIPT"
  echo "시각: 첫 요청 ${PRE_K6_TARGET_S}±${PRE_K6_TOL_S}초 · 도착 $(P .d_arr_s)초 · k6 약 $(P .k6.est_s)초(grace $(P .grace_s)) · 배수 상한 $(P .drain.cap_s)초 · 회차 추정 $(P .est_s)초(최악 $(P .est_max_s))"
  exit 0
fi
if (( SIM )); then   # campaign18.sh 시험용 — 서버 접속 없음(run17 --simulate와 같다)
  if [[ -e "$DIR" ]]; then
    if (( ATTEMPT > 1 )) && [[ ! -e "$DIR.attempt$((ATTEMPT - 1))" ]]; then mv "$DIR" "$DIR.attempt$((ATTEMPT - 1))"; else echo "$DIR 이미 있다" >&2; exit 2; fi
  fi
  mkdir -p "$DIR"; echo "$PLAN_JSON" | jq . > "$DIR/plan.json"
  st=ok; [[ -n "${SIM_PLAN:-}" && -s "$SIM_PLAN" ]] && { v=$(grep -m1 -F "$REP:$METHOD:$CELL:$ATTEMPT=" "$SIM_PLAN" | cut -d= -f2-); [[ -n "$v" ]] && st="$v"; }
  jq -n --arg sha "$SHA" --arg m "$METHOD" --arg g "$CELL" --argjson r "$REP" --argjson a "$ATTEMPT" --arg hh "${HH_SRC:-}" \
    '{sha: $sha, method: $m, cell: $g, rep: $r, attempt: $a, rerun: ($a > 1), harness_hash: $hh, simulated: true}' > "$DIR/meta.json"
  [[ "$st" == skip ]] || echo "$st" > "$DIR/status"
  echo "$(date -Is) $METHOD $CELL rep$REP attempt$ATTEMPT ${st} (simulated)" >> "$OUT_ROOT/MATRIX.log"
  exit 0
fi
(( $(date -u +%s) < $(date -u -d 2026-12-31T09:00:00Z +%s) )) || { echo "2026-12-31T09:00Z 이후 — 시드 회차가 측정 중 마감될 수 있다" >&2; exit 2; }
[[ -s "$IMAGES" ]] || { echo "--images <images.json>(run18.sh --prepare-image) 필수" >&2; exit 2; }
[[ "$(jq -r .app.sha "$IMAGES")" == "$SHA" ]] || { echo "images.json의 SHA가 --sha $SHA와 다르다" >&2; exit 2; }
APP_TAG="$SHA" APP_ID=$(jq -r .app.id "$IMAGES") MOCKPG_ID=$(jq -r '.mockpg.id // empty' "$IMAGES")
[[ -n "$MOCKPG_ID" ]] || { echo "images.json에 mockpg.id 없음(run18.sh --prepare-image)" >&2; exit 2; }
if [[ -e "$DIR" ]]; then
  if (( ATTEMPT > 1 )) && [[ ! -e "$DIR.attempt$((ATTEMPT - 1))" ]]; then mv "$DIR" "$DIR.attempt$((ATTEMPT - 1))"
  else echo "$DIR 이미 있다 — 덮어쓰지 않는다(재측정은 --attempt n)" >&2; exit 2; fi
fi
mkdir -p "$DIR"; MATRIX_LOG="$OUT_ROOT/MATRIX.log"
echo "$PLAN_JSON" | jq . > "$DIR/plan.json"
cp "$IMAGES" "$DIR/images.json"
STARTED=$(date -Is) STARTED_EPOCH=$(date +%s)
K6_TREE="$(mktemp -d)"
SCEN_PATHS=(k6/ADR-009/scenarios k6/ADR-014/scenarios k6/ADR-015/scenarios k6/ADR-017/scenarios k6/ADR-018/scenarios)
if (( WORKTREE )); then for s in "${SCEN_PATHS[@]}"; do mkdir -p "$K6_TREE/$(dirname "$s")" && cp -r "$REPO_ROOT/$s" "$K6_TREE/$(dirname "$s")/"; done
else git -C "$REPO_ROOT" archive "$SHA" "${SCEN_PATHS[@]}" | tar -x -C "$K6_TREE"; fi
SCEN15="$K6_TREE/k6/ADR-015/scenarios"
HREASONS="$DIR/harness-reasons.txt"; : > "$HREASONS"
hreason() { grep -qxF "$1" "$HREASONS" 2>/dev/null || echo "$1" >> "$HREASONS"; echo "$(date -Is) harness: $1" >> "$DIR/errors.log"; }
snap18() { python3 -I "$S18/actsnap18.py" "$BASE_URL" > "$DIR/act18-$1.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/act18-$1.json"; hreason app-snap-failed; }; }
srv_ms() { echo $(( $(date +%s%3N) + ${OFF_MS:-0} )); }
INJ_EVENTS="$DIR/.inject-events.jsonl"; : > "$INJ_EVENTS"
pg_prepare18() {   # PG reset → faults(규칙 없음 · 시드만) — 기록 = inject.json events(INTERFACE §6.6 · run17 pg_prepare17과 같다)
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
logs18() {
  local c rc1 rc2
  for c in app db mock-pg; do
    remote "docker logs --since '$STARTED' seatlab-$c-1 2>&1" < /dev/null | gzip > "$DIR/$c.log.gz"
    rc1=${PIPESTATUS[0]} rc2=${PIPESTATUS[1]}
    echo "$c pipe=$rc1/$rc2 bytes=$(zcat "$DIR/$c.log.gz" 2>/dev/null | wc -c)" >> "$DIR/logs-status.txt"
    # 앱 로그 수집 실패(리뷰 H3-3): 파이프 실패 · 0바이트 · 기동 머리 줄 없음 = 하네스 사유(경계 셀 5xx 원인 연결이 로그를 쓴다)
    #   pipefail 아래 `zcat | grep -q`는 grep이 먼저 끝나 zcat이 SIGPIPE로 죽으면 정상 로그를 실패로 본다(리뷰 H3-R3) — 압축 검사(gzip -t)를 따로 하고,
    #   머리 줄은 grep -c로 스트림을 끝까지 읽어 센다
    if [[ "$c" == app ]]; then
      local nstart
      nstart=$(zcat "$DIR/app.log.gz" 2>/dev/null | grep -c "Starting SeatReservationApplication")
      if (( rc1 != 0 || rc2 != 0 )) || ! gzip -t "$DIR/app.log.gz" 2>/dev/null || ! [[ "$nstart" =~ ^[0-9]+$ ]] || (( nstart == 0 )); then
        hreason app-log-missing
      fi
    fi
  done
}
finish18() {
  local st="${1:-}"
  [[ -s "$INJ_EVENTS" ]] && inject_write; rm -f "$INJ_EVENTS"
  jq -n --arg sha "$SHA" --arg harness "$HARNESS" --arg m "$METHOD" --arg g "$CELL" --argjson r "$REP" --argjson a "$ATTEMPT" \
        --arg started "$STARTED" --arg ended "$(date -Is)" --arg k6 "$("$K6_BIN" version 2>/dev/null | head -1)" --arg k6sha "$(sha256sum "$(command -v "$K6_BIN")" 2>/dev/null | cut -d' ' -f1)" \
        --arg app "$APP_ENV14" --arg res "$RES_ENV14" --arg k6env "${K6_ENV_Q[*]}" --arg hh "${HH_SRC:-}" --arg img "$APP_ID" --arg mp "$MOCKPG_ID" \
        --argjson seed "${SEED_T:-null}" --argjson k0 "${K6_T0:-null}" --argjson k1 "${K6_END:-null}" --argjson kx "${K6_EXIT:-null}" \
        --argjson pgs "$(P .pg.faults.seed)" --argjson loc "$LOCAL" --argjson sm "$SMOKE" \
    '{sha: $sha, harness: $harness, method: $m, cell: $g, rep: $r, attempt: $a, rerun: ($a > 1), started: $started, ended: $ended,
      k6_version: $k6, k6_sha256: $k6sha, app_env: $app, res_env: $res, k6_env: $k6env, harness_hash: $hh, app_image_id: $img, mockpg_image_id: $mp,
      pg_faults_seed: $pgs, seed_done_epoch: $seed, k6_launch_epoch: $k0, k6_end_epoch: $k1, k6_exit: $kx, local: ($loc == 1), smoke: ($sm == 1)}' > "$DIR/meta.json.tmp" \
    && mv "$DIR/meta.json.tmp" "$DIR/meta.json" || { hreason meta-write-failed; st="invalid:meta-write-failed"; }
  bash "$S14/pclink14.sh" "$STARTED_EPOCH" "$(( $(date +%s) + 1 ))" > "$DIR/pc-link.json" 2>>"$DIR/errors.log" || echo "$(date -Is) pclink14 실패 rc=$?" >> "$DIR/errors.log"
  if [[ -z "$st" ]]; then
    python3 -I "$S18/summarize18.py" --rep "$DIR" > "$DIR/.summarize.out" 2>>"$DIR/errors.log"; local src=$?
    st=$(tail -n 1 "$DIR/.summarize.out"); rm -f "$DIR/.summarize.out"
    (( src == 0 )) && [[ "$st" =~ ^(ok|invalid:) ]] || st="invalid:summarize-failed"
  fi
  echo "$st" > "$DIR/status"
  echo "$(date -Is) $METHOD $CELL rep$REP attempt$ATTEMPT $st" >> "$MATRIX_LOG"
  log "$METHOD $CELL rep$REP → $st"
}
down18() { compose_down14 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || hreason compose-down-failed; }
abort18() {
  hreason "$1"
  logs18
  [[ -n "${POLL_PIDS:-}" ]] && { stop_pollers14 $POLL_PIDS; POLL_PIDS=""; }
  down18
  finish18 "invalid:$1"; exit 0
}

# ---- 기동 · 예열 · 시드 · PG 준비 ------------------------------------------------------------------------------------------------------
log "ADR-018 $METHOD $CELL rep$REP attempt$ATTEMPT → $DIR"
deploy18 < /dev/null || abort18 deploy-failed
compose_down14 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || abort18 compose-down-failed
[[ "$(image_id "seatlab-app:$APP_TAG")" == "$APP_ID" ]] || abort18 cond-image-mismatch
for img in $(jq -r '.aux | keys[]' "$IMAGES"); do [[ "$(image_id "$img")" == "$(jq -r --arg k "$img" '.aux[$k]' "$IMAGES")" ]] || abort18 aux-image-mismatch; done
compose_up18 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || abort18 compose-up-failed
wait_health 240 || abort18 unhealthy
[[ "$(ctr_image seatlab-app-1)" == "$APP_ID" ]] || abort18 cond-image-mismatch
[[ "$(ctr_image seatlab-mock-pg-1)" == "$MOCKPG_ID" ]] || abort18 aux-image-mismatch
remote "docker ps --filter label=com.docker.compose.project=seatlab --filter label=com.docker.compose.service=app --format '{{.Names}}'" < /dev/null > "$DIR/app-instances.txt" 2>>"$DIR/errors.log"
[[ "$(grep -c . "$DIR/app-instances.txt")" == 1 ]] || abort18 app-instances-not-one
pg_ready 120 < /dev/null || abort18 mockpg-unready
reset_with_retry "$DIR" 2 10000 > /dev/null || abort18 reset-failed
BASE_URL="$BASE_URL" "$K6_BIN" run --quiet --out "csv=$DIR/warmup-requests.csv.gz" "$SCEN15/warmup15.js" > "$DIR/warmup.log" 2>&1 < /dev/null   # PAY_START 끔 — 옛 경로만
WARM_RC=$?
python3 -I "$S14/summarize.py" --warmup-status "$DIR/warmup-requests.csv.gz" "$WARM_RC" > "$DIR/warmup-status.json" 2>>"$DIR/errors.log"
(( WARM_RC == 0 )) || abort18 warmup-failed
RESET_TIMEOUT=1800 reset_with_retry "$DIR" 1 "$SEATS" > "$DIR/seed.json" || abort18 seed-failed
SEED_T=$(now)
CLK0=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
OFF_MS=$(jq -r 'if (.offset_s | type) == "number" then (.offset_s * 1000 | round) else "" end' <<< "${CLK0:-null}" 2>/dev/null)
[[ "$OFF_MS" =~ ^-?[0-9]+$ ]] || abort18 clock-offset-bad
pg_prepare18 < /dev/null || abort18 pg-prepare-failed
seed_check15 1 "$SEATS" > "$DIR/seed-check.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/seed-check.json"; hreason seed-check-failed; }
db_state > "$DIR/db-state-seed.json" 2>>"$DIR/errors.log" < /dev/null
app_config18 "$DIR" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/app-config.json"; hreason app-config-failed; }
pg_settings > "$DIR/pg-settings.json" 2>>"$DIR/errors.log" < /dev/null

# ---- 표본기 · 시작 대기 ---------------------------------------------------------------------------------------------------------------
TAG18="$(echo "$CID" | tr '/' '_')-$METHOD-$CELL-rep$REP-$(date +%Y%m%d%H%M%S)-$RANDOM"
SAMPLER_COPY="$REMOTE_BASE/$DEPLOY_ID/.samples/$TAG18.jsonl"
POLL_PIDS="$(start_pollers14 "$DIR" cost 1 | xargs)"
hold_left=$(awk -v s="$SEED_T" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { d = s + h - n; printf "%.3f", (d > 0 ? d : 0) }')
awk -v s="$SEED_T" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { exit !(n - s > h + 1) }' && abort18 pre-k6-overrun
sleep "$hold_left"
table_stats > "$DIR/table-before.json" 2>>"$DIR/errors.log" < /dev/null
snap18 before
app_db_clock > "$DIR/app-db-clock-before.json" 2>>"$DIR/errors.log" < /dev/null
awk -v n="$(now)" -v b="$SEED_T" -v t="$PRE_K6_TARGET_S" -v l="$PRE_K6_TOL_S" -v lead="$PRE_K6_LEAD_S" 'BEGIN { exit !((n - b) + lead > t + l) }' && abort18 pre-k6-overrun
topup=$(awk -v t="$PRE_K6_TARGET_S" -v n="$(now)" -v b="$SEED_T" -v lead="$PRE_K6_LEAD_S" 'BEGIN { d = t - lead - (n - b); printf "%.3f", (d > 0 ? d : 0) }')
sleep "$topup"

# ---- k6 ---------------------------------------------------------------------------------------------------------------------------
CLK_BEFORE=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
OFF_MS=$(jq -r 'if (.offset_s | type) == "number" then (.offset_s * 1000 | round) else "" end' <<< "${CLK_BEFORE:-null}" 2>/dev/null)
[[ "$OFF_MS" =~ ^-?[0-9]+$ ]] || abort18 clock-offset-bad
K6_STARTED=$(date -Is); K6_T0=$(now)
# 부하 중 홀드 표본(리뷰 H1-1 — 홀드가 살아 있는 동안의 부분 선점 · 만료 갈림 · 재고 · k6 끝까지 HOLD_SAMPLE_S 주기)
SAMPLE_S=$(P .hold_sample_s) PAIR_MAX=$(P .seed.pair_max_start) PAIR_KIND=$(P .seed.pair_kind); : > "$DIR/holds-sample.jsonl"
( while :; do
    pc0=$(date +%s%3N); r=$(hold_sample18 "$PAIR_MAX" "$PAIR_KIND" 2>>"$DIR/errors.log" < /dev/null | tr -d '[:space:]'); pc1=$(date +%s%3N)
    if jq -e 'type == "object" and (.partial | type) == "number" and (.free_pairs | type) == "number"' <<< "${r:-null}" > /dev/null 2>&1; then
      jq -c --argjson a "$pc0" --argjson b "$pc1" '. + {pc_t0_ms: $a, pc_t1_ms: $b}' <<< "$r" >> "$DIR/holds-sample.jsonl"
    else jq -nc --argjson a "$pc0" --argjson b "$pc1" --arg e "${r:-}" '{pc_t0_ms: $a, pc_t1_ms: $b, err: $e}' >> "$DIR/holds-sample.jsonl"; fi
    sleep "$SAMPLE_S"
  done ) > /dev/null 2>&1 < /dev/null &
SAMPLE_PID=$!
env "${K6_ENV_Q[@]}" CLOCK_OFFSET_MS="$OFF_MS" BASE_URL="$BASE_URL" PG_URL="$MOCKPG_URL" OUT_DIR="$DIR" \
  "$K6_BIN" run --quiet --log-format=json --console-output="$DIR/sessions18.log" --out "csv=$DIR/k6-requests.csv.gz" "$K6_TREE/$K6_SCRIPT" > "$DIR/k6-stdout.log" 2>&1 < /dev/null
K6_EXIT=$?; K6_END=$(now)
kill "$SAMPLE_PID" 2>/dev/null; wait "$SAMPLE_PID" 2>/dev/null; SAMPLE_PID=""
jq -n --arg s "$K6_STARTED" --arg e "$(date -Is)" --argjson t0 "$K6_T0" --argjson t1 "$K6_END" --argjson rc "$K6_EXIT" --argjson off "$OFF_MS" \
  '{k6_started: $s, k6_ended: $e, k6_t0_epoch: $t0, k6_end_epoch: $t1, exit: $rc, clock_offset_ms_given: $off}' > "$DIR/k6-window.json"
(( K6_EXIT == 0 )) || hreason "k6-exit-$K6_EXIT"
[[ -f "$DIR/sessions18.log" ]] && gzip -f "$DIR/sessions18.log"
CLK_AFTER=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
jq -n --argjson b "${CLK_BEFORE:-null}" --argjson a "${CLK_AFTER:-null}" '{before: $b, after: $a}' > "$DIR/clock-offset.json" 2>/dev/null || echo '{"before":null,"after":null}' > "$DIR/clock-offset.json"
snap18 after-k6
# k6 직후 정합 스냅숏(INTERFACE §6.3 — 이탈 · 첫 석만 잡은 홀드가 아직 남은 시점의 부분 선점 · 만료 갈림)
CONSISTENCY_TIMEOUT=600 consistency "$CGRACE" > "$DIR/consistency-k6.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/consistency-k6.json"; hreason k6-check-failed; }

# ---- 배수 창(INTERFACE §6.5): pending = 0 · open = 0 · holds = 0 조회가 연달아 둘이고 그 사이 ≥ 안전망 + 워커 주기 · 아니면 상한 ---------------------------
CAP_S=$(P .drain.cap_s) POLL_S=$(P .drain.poll_s)
QUIET_MS=$(P '((.values.compensation.sweep.interval_s + .values.compensation.worker.interval_s) * 1000)')
DRAIN_EV="$DIR/.drain-polls.jsonl"; : > "$DRAIN_EV"
D0=$(srv_ms); reason=cap; zero_since=""; zero_n=0
while awk -v e="$K6_END" -v c="$CAP_S" -v t="$(now)" 'BEGIN { exit !(t < e + c) }'; do
  t=$(srv_ms); r=$(drain_poll18 2>>"$DIR/errors.log" < /dev/null | tr -d '[:space:]')
  if jq -e 'type == "object" and (.pending | type) == "number" and (.holds | type) == "number"' <<< "${r:-null}" > /dev/null 2>&1; then
    jq -c --argjson t "$t" '{t_ms: $t} + .' <<< "$r" >> "$DRAIN_EV"
    if [[ "$(jq -r '.pending == 0 and .open == 0 and .holds == 0' <<< "$r")" == true ]]; then
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

# ---- 덤프(순서 고정 — run17과 같다: 원장 → DB → 원장 대조 → 판정기 → 지표) -------------------------------------------------------------
DUMP_TRIES_CAP=5
DUMP_TRIES=1; [[ "$reason" == cap ]] && DUMP_TRIES=$DUMP_TRIES_CAP
dump_n=0 dump_stable=false dump_ok=true
while (( dump_n < DUMP_TRIES )); do
  dump_n=$((dump_n + 1))
  pg_ledger > "$DIR/pg-ledger.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/pg-ledger.json"; hreason ledger-dump-failed; dump_ok=false; break; }
  db_dump18 > "$DIR/db-dump.json" 2>>"$DIR/errors.log" < /dev/null && jq -e 'type == "object"' "$DIR/db-dump.json" > /dev/null 2>&1 \
    || { rm -f "$DIR/db-dump.json"; hreason db-dump-failed; dump_ok=false; break; }
  o1=$(jq '[.ops[]] | add // 0' "$DIR/pg-ledger.json"); o2=""
  for _ in 1 2; do
    o2=$(pg_ledger 2>/dev/null < /dev/null | jq '[.ops[]] | add // 0' 2>/dev/null); [[ "$o2" =~ ^[0-9]+$ ]] && break; o2=""; sleep 2
  done
  [[ -n "$o2" ]] || { hreason ledger-recheck-failed; dump_ok=false; break; }
  [[ "$o1" == "$o2" ]] && { dump_stable=true; break; }
  (( dump_n < DUMP_TRIES )) && sleep 3
done
if $dump_ok && ! $dump_stable; then [[ "$reason" == cap ]] && hreason dump-unstable || hreason dump-race; fi
jq --argjson n "$dump_n" --argjson m "$DUMP_TRIES" --argjson st "$dump_stable" '. + {dump: {attempts: $n, max: $m, stable: $st}}' "$DIR/drain.json" > "$DIR/drain.json.tmp" 2>>"$DIR/errors.log" \
  && mv "$DIR/drain.json.tmp" "$DIR/drain.json" || hreason drain-write-failed
CONSISTENCY_TIMEOUT=600 consistency "$CGRACE" > "$DIR/consistency.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/consistency.json"; hreason end-check-failed; }
snap18 end
table_stats > "$DIR/table-end.json" 2>>"$DIR/errors.log" < /dev/null
app_db_clock > "$DIR/app-db-clock-after.json" 2>>"$DIR/errors.log" < /dev/null

# ---- 수집 · 내리기 · 판정 ------------------------------------------------------------------------------------------------------------
stop_pollers14 $POLL_PIDS; POLL_PIDS=""
remote "pkill -f 'FROM pg_locks WHERE NOT granted' || true; rm -f '$SAMPLER_COPY'" > /dev/null 2>&1 < /dev/null
logs18
gzip -f "$DIR/k6-stdout.log"
down18
finish18
