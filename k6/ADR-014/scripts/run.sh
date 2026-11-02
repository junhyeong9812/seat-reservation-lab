#!/usr/bin/env bash
# ADR-014 회차 실행기 — 회차 하나(방식 × 셀 × 회차 번호): 내리기 → 이미지 확인 → 기동(손잡이 · V11 위치) → 예열 → 시드 · 시드 확인 → 표본기 → 시작 대기(87초) →
#   k6(주 흐름 + 탐침 · 또는 비용 셀 S4c) · 측정기 → 직후 스냅숏 → 수렴 대기 → 끝 판정기 · 기록 덤프 → 수집 → 판정(status — summarize.py --rep).
#   기준: docs/adr/ADR-014-hold-expiry.md '측정 전 고정 규칙'(측정-1 조건 · 측정-3 부하 · 측정-3.6 하네스 · 측정-5 원자료 · 측정-9 회차 사유).
#   계획(셀 · 손잡이 · k6 환경 · 탐침 일정)의 단일 출처 = scripts/plan14.py. 판정(회차 사유 · 지표)의 단일 출처 = scripts/summarize.py --rep.
#   ADR-009 하네스(k6/ADR-009/scripts/lib.sh · scenarios · compose.yml · compose.tmpfs.yml · compose.probe.yml)는 수정하지 않고 동결 사본에서 source·-f로 쓴다.
#
#   scripts/run.sh --prepare-image --sha <SHA> --out <images.json>
#       캠페인 전에 한 번: SHA를 서버에 풀고 seatlab-app:<sha8>를 빌드(이미 있으면 다시 빌드하지 않는다) · 보조 이미지(postgres:16 · redis:7-alpine · 서버 측정기 PROBE_IMAGE)를
#       받아 둔다 → 이미지 ID를 images.json에 고정(측정-1 '이미지 고정' · ADR-013 DS-11).
#   scripts/run.sh --sha <SHA> --images <images.json> --method A|B|C|D --cell <셀> --rep <k> --id <캠페인 id> [--attempt n] [--knobs <json>]
#       [--k6-bin k6] [--probe-k6-bin <경로>] [--worktree] [--results-root <폴더>] [--dry-run] [--simulate]
#     결과 = <results-root 기본 k6/ADR-014/results>/<id>/<셀>/<방식>/rep<k>/ · --attempt n(>1)은 앞 시도를 rep<k>.attempt<n−1>로 옮긴다(재측정 — 결과 표 'rerun').
#     --worktree(스모크 전용): 앱 이미지는 고정 이미지 그대로, 하네스(k6/ADR-014 · k6/ADR-009 하네스 경로)만 작업트리에서.
#     --dry-run: 계획 · 명령줄 · 경로만 출력(서버 접속 없음). --simulate: 서버 없이 회차 폴더에 plan·meta·status만 쓴다(campaign.sh 루프·계획 대 실행 대조 시험용 —
#       SIM_PLAN=<파일>의 '<회차>:<셀>:<방식>:<시도>=<status>' 줄로 status를 정하고 'skip'이면 status를 쓰지 않는다 · 표준 입력을 읽어 ssh의 표준 입력 소비를 흉내 낸다).
#   status: ok(유효 — 방식 실패는 rep-summary.json violations에) · invalid:<사유,…>(하네스 — 무효 → 재측정). env-stall만으로 무효면 invalid:env-stall(환경 상한 — 측정-9 MR-10).
set -uo pipefail

if [[ "${RUN14_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"   # repo = 이 스크립트가 든 트리(작업트리 또는 campaign.sh의 동결본)
  HH_SRC="$(bash "$src/hhash.sh" "$repo")" || { echo "하네스 해시 실패($repo)" >&2; exit 2; }
  [[ -z "${HARNESS_HASH14:-}" || "$HH_SRC" == "$HARNESS_HASH14" ]] || { echo "하네스 해시 $HH_SRC ≠ 캠페인 동결본 $HARNESS_HASH14" >&2; exit 2; }
  frozen="$(mktemp -d)"
  mkdir -p "$frozen/k6/ADR-014" "$frozen/k6/ADR-009"
  cp -r "$src" "$frozen/k6/ADR-014/scripts" && cp -r "$repo/k6/ADR-009/scripts" "$frozen/k6/ADR-009/scripts" || { echo "동결 사본 실패" >&2; exit 2; }
  export RUN14_FROZEN=1 FROZEN14="$frozen" HH_SRC ADR14_DIR="${ADR14_DIR:-$(cd "$src/.." && pwd)}" REPO_ROOT="${REPO_ROOT:-$repo}"
  exec bash "$frozen/k6/ADR-014/scripts/run.sh" "$@"
fi
export LIB9_DIR="$FROZEN14/k6/ADR-009/scripts"
S14="$FROZEN14/k6/ADR-014/scripts"
DRY=0 SIM=0; for a in "$@"; do [[ "$a" == --dry-run ]] && DRY=1; [[ "$a" == --simulate ]] && SIM=1; done
source "$S14/lib14.sh"
set +e

SHA="" IMAGES="" METHOD="" CELL="" REP="" CID="" ATTEMPT=1 KNOBS="" WORKTREE=0 K6_BIN=k6 PREPARE=0 IMG_OUT="" RESULTS_ROOT=""
PROBE_K6_BIN="$HOME/.local/opt/k6-v2.2.0/k6"   # ADR-011 측정기 k6(주 k6와 다른 바이너리 — ADR-009 run.sh 기본값과 같다)
PROBE_IMAGE="${PROBE_IMAGE:-grafana/k6:2.2.0}" PROBE_RATE=50
PRE_K6_HOLD_S="${PRE_K6_HOLD_S:-70}" PRE_K6_TARGET_S="${PRE_K6_TARGET_S:-87}" PRE_K6_TOL_S="${PRE_K6_TOL_S:-5}" PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-}"   # ADR-009 시작 대기(측정-3.6 재사용)
# PRE_K6_LEAD_S(k6 실행 → 첫 요청) 기본값 = 계획 k6.lead_s(plan14 k6_lead_s — 미리 만든 VU 수에 비례 · 명세 R5: 옛 고정 1.2초로는 VU 13,300 칸이 93.2초였다)
IDLE_AFTER_K6_S=11   # P6 '직후' 스냅숏 = k6 마지막 응답 뒤 업무 요청이 10초 넘게 쉰 뒤(리뷰 MR-12)
WEND_LAG_S=3         # 판정 창 끝 앱 지표 스냅숏 = k6 실행 + k6 lead(PRE_K6_LEAD_S) + 창 끝 + 3초(t0 = k6 실행 뒤 약 lead초 — 요약기가 시작 시각이 창 끝 뒤 [0, 10]초인지 본다 · 코드 리뷰 CR14-13 ·
                     # lead를 더하지 않으면 미리 만든 VU 15,300 칸(lead 5.81초 · 실측 4.29초)에서 창 끝 1.07초 앞에 찍혔다 — confirm14-20261103 a50-t300 D)
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --images) IMAGES="$2"; shift 2 ;;
    --method) METHOD="$2"; shift 2 ;;
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
cleanup14() {
  [[ "$BASHPID" == "$$" ]] || return 0
  [[ -n "${POLL_PIDS:-}" ]] && stop_pollers14 $POLL_PIDS
  [[ -n "${WEND_PID:-}" ]] && kill "$WEND_PID" 2>/dev/null
  if [[ -n "${PROBE_PC_PID:-}" ]] && kill -0 "$PROBE_PC_PID" 2>/dev/null; then kill -INT "$PROBE_PC_PID" 2>/dev/null; fi
  [[ -n "${K6_TREE:-}" && -d "${K6_TREE:-}" ]] && rm -rf "$K6_TREE"
  [[ -n "${FROZEN14:-}" && "$FROZEN14" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "$FROZEN14"
}
trap cleanup14 EXIT
short() { git -C "$REPO_ROOT" rev-parse --short=8 "$1^{commit}" 2>/dev/null; }
now() { date +%s.%N; }

# ---- 이미지 준비(측정-1 '이미지 고정' — 캠페인 전에 한 번) -----------------------------------------------------------------------------
if (( PREPARE )); then
  [[ -n "$IMG_OUT" ]] || { echo "--prepare-image는 --out이 필요하다" >&2; exit 2; }
  s8=$(short "${SHA:-HEAD}") || { echo "SHA 해석 실패" >&2; exit 2; }
  (( DRY )) && { echo "deploy $s8 → build seatlab-app:$s8(없을 때만) → pull postgres:16 redis:7-alpine $PROBE_IMAGE → $IMG_OUT"; exit 0; }
  deploy_sha "$s8" || { echo "배포 실패" >&2; exit 1; }
  if [[ -n "$(image_id "seatlab-app:$s8")" ]]; then log "seatlab-app:$s8 이미 있음 — 다시 빌드하지 않는다"
  else remote "cd $REMOTE_BASE/$s8 && docker build -q -t seatlab-app:$s8 --label org.seatlab.sha=$s8 ." >&2 || { echo "빌드 실패 $s8" >&2; exit 1; }; fi
  for x in postgres:16 redis:7-alpine "$PROBE_IMAGE"; do [[ -n "$(image_id "$x")" ]] || remote "docker pull -q $x" >&2 || { echo "받기 실패 $x" >&2; exit 1; }; done
  jq -n --arg s "$s8" --arg id "$(image_id "seatlab-app:$s8")" --arg pg "$(image_id postgres:16)" --arg rd "$(image_id redis:7-alpine)" \
        --arg pi "$PROBE_IMAGE" --arg pid "$(image_id "$PROBE_IMAGE")" --arg at "$(date -Is)" \
    '{pinned_at: $at, app: {sha: $s, tag: ("seatlab-app:" + $s), id: $id}, aux: {"postgres:16": $pg, "redis:7-alpine": $rd, ($pi): $pid}}' > "$IMG_OUT"
  jq -e '[.app.id, (.aux[])] | all(test("^sha256:"))' "$IMG_OUT" > /dev/null || { echo "이미지 ID를 다 얻지 못했다 — $IMG_OUT" >&2; exit 1; }
  cat "$IMG_OUT"; exit 0
fi

# ---- 인자 · 계획 ---------------------------------------------------------------------------------------------------------------------
[[ "$METHOD" =~ ^[ABCD]$ && -n "$CELL" && "$REP" =~ ^[1-3]$ && "$ATTEMPT" =~ ^[1-9]$ && -n "$CID" ]] || { echo "--method A|B|C|D --cell <셀> --rep 1..3 --id <id> 필수" >&2; exit 2; }
SHA="$(short "${SHA:-HEAD}")"; [[ -n "$SHA" ]] || { echo "SHA 해석 실패" >&2; exit 2; }
KNOBS_ARG=(); [[ -n "$KNOBS" ]] && KNOBS_ARG=(--knobs "$KNOBS")
PLAN_JSON="$(python3 -I "$S14/plan14.py" --render "$METHOD" "$CELL" "$REP" "${KNOBS_ARG[@]}")" || exit 2
P() { jq -r "$1" <<< "$PLAN_JSON"; }
KIND=$(P .kind)
PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-$(P .k6.lead_s)}"; [[ "$PRE_K6_LEAD_S" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "k6 lead($PRE_K6_LEAD_S) 숫자 아님 — plan14 k6.lead_s" >&2; exit 2; }
mapfile -t HPATHS < <(bash "$S14/hhash.sh" --paths)
HARNESS="sha:$SHA" DEPLOY_ID="$SHA"
if (( WORKTREE )); then
  [[ "${CAMPAIGN14:-0}" != 1 || "${CAMPAIGN14_WORKTREE:-0}" == 1 ]] || { echo "--worktree는 스모크 전용" >&2; exit 2; }
  HARNESS="worktree:${HH_SRC:0:8}"; DEPLOY_ID="$SHA-wt14-${HH_SRC:0:8}"
elif [[ -n "${HARNESS_HASH14:-}" ]]; then
  :   # 캠페인: 원본 = --sha의 git archive 동결본이고 해시를 위에서 대조했다
elif ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- "${HPATHS[@]}" ':(exclude)k6/ADR-014/results' 2>/dev/null \
     || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- "${HPATHS[@]}" ':(exclude)k6/ADR-014/results')" ]]; then
  echo "하네스(${HPATHS[*]})가 $SHA와 다르다 — 커밋하거나 --worktree(스모크)" >&2; (( DRY || SIM )) || exit 2
fi
APP_ENV14="$(P '.app_env | to_entries | map("\(.key)=\(.value)") | join(" ")') HOLD_STRATEGY=pessimistic-nowait CRITICAL_SECTION_DELAY=0s"
# 자원(측정-1): L4(앱·DB CPU 4) · tmpfs 12g(DB 메모리 14g) · 앱 2g · 세대형 ZGC — ADR-013 README H10과 같은 값
RES_ENV14="APP_CPUS=4 DB_CPUS=4 APP_MEM=2g APP_JVM_OPTS=-XX:+UseZGC APP_JVM_OPTS2=-XX:+ZGenerational APP_JVM_OPTS3= APP_JVM_OPTS4= \
PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=12g DB_MEM=14g"
K6_ENV="$(P '.k6.env | to_entries | map("\(.key)=\(.value)") | join(" ")')"
K6_SCRIPT="$(P .k6.script)"
OUT_ROOT="${RESULTS_ROOT:-$ADR14_DIR/results}/$CID"; DIR="$OUT_ROOT/$CELL/$METHOD/rep$REP"
SEED_S=$(P .seed.schedules) SEED_N=$(P .seed.seats)
EVERY=$(P '.samplers.state_expired_every_s // 1')
if (( DRY )); then
  echo "# dry-run — 방식 $METHOD($(P .mode)) · 셀 $CELL · 회차 $REP · 시도 $ATTEMPT · 하네스 $HARNESS · 배포 $DEPLOY_ID · 종류 $KIND"
  echo "결과 폴더: $DIR"
  echo "compose: $(compose14)"
  echo "env: $(env14)"
  echo "시드: /internal/reset?schedules=$SEED_S&seatsPerSchedule=$SEED_N(예열 앞 2 × 10000 → warmup14.js → 다시 시드)"
  echo "표본기: 서버·docker stats 10s · 대기 0.5s(db_sampler) · Hikari 1s$([[ $KIND == main ]] && echo " · 상태 1s(만료 행 ${EVERY}틱) · H4 표지 0.5s")"
  echo "k6: env $K6_ENV CLOCK_OFFSET_MS=<k6 직전 시계 차> BASE_URL=http://$SERVER_HOST:8101 OUT_DIR=$DIR $K6_BIN run --quiet --out csv=$DIR/k6-requests.csv.gz <k6 트리>/$K6_SCRIPT"
  echo "측정기: PC $PROBE_K6_BIN probe-ping.js 50/s · 서버 $PROBE_IMAGE(compose.probe.yml)"
  echo "PC 링크: 회차 끝에 pclink14.sh <회차 시작> <끝> → pc-link.json(journalctl -k '${PC_IFACE:-eno1}: Link is Down' — 지금 읽기: $(bash "$S14/pclink14.sh" "$(( $(date +%s) - 60 ))" "$(date +%s)" | jq -r '"readable \(.readable) \(.why)"'))"
  echo "시각: 시드 끝 → 첫 요청 ${PRE_K6_TARGET_S}±${PRE_K6_TOL_S}초(k6 lead ${PRE_K6_LEAD_S}초 — 미리 만든 VU $(P .k6.pre_vus_total)) · k6 $(P .k6.duration_s)초 · 창 끝 스냅숏 k6 실행 + lead ${PRE_K6_LEAD_S} + $(P '.window.from_s + .window.len_s') + ${WEND_LAG_S}초 · 직후 스냅숏 k6 끝 + ${IDLE_AFTER_K6_S}초 · 수렴 $(P .converge_s)초 · 판정 창 $(P '.window | "\(.kind) [\(.from_s), \(.from_s + .len_s))초"')"
  [[ "$KIND" == main ]] && P '.probes | "탐침: 칸 [\(.start_s), \(.end_s))초 · 수 \(.count) · 마지막 요청 \(.last_request_s) < D_arr − 5"'
  exit 0
fi
if (( SIM )); then   # campaign.sh 시험용(머리말) — 서버 접속 없음
  if [[ -e "$DIR" ]]; then
    if (( ATTEMPT > 1 )) && [[ ! -e "$DIR.attempt$((ATTEMPT - 1))" ]]; then mv "$DIR" "$DIR.attempt$((ATTEMPT - 1))"; else echo "$DIR 이미 있다" >&2; exit 2; fi
  fi
  mkdir -p "$DIR"; echo "$PLAN_JSON" | jq . > "$DIR/plan.json"
  timeout 2 cat > /dev/null 2>&1   # ssh·docker가 표준 입력을 읽는 것을 흉내 낸다(ADR-013 §6.1 — 루프 here-string을 먹는다)
  st=ok; [[ -n "${SIM_PLAN:-}" && -s "$SIM_PLAN" ]] && { v=$(grep -m1 "^$REP:$CELL:$METHOD:$ATTEMPT=" "$SIM_PLAN" | cut -d= -f2-); [[ -n "$v" ]] && st="$v"; }
  jq -n --arg sha "$SHA" --arg m "$METHOD" --arg c "$CELL" --argjson r "$REP" --argjson a "$ATTEMPT" --arg hh "${HH_SRC:-}" \
    '{sha: $sha, method: $m, cell: $c, rep: $r, attempt: $a, rerun: ($a > 1), harness_hash: $hh, simulated: true}' > "$DIR/meta.json"
  [[ "$st" == skip ]] || echo "$st" > "$DIR/status"
  echo "$(date -Is) $CELL $METHOD rep$REP attempt$ATTEMPT ${st} (simulated)" >> "$OUT_ROOT/MATRIX.log"
  exit 0
fi
# 마감 없음(측정-1): 시드 회차 starts_at = 2026-12-31 19:00+09(LoadtestDataService.kt:72) = 2026-12-31T10:00Z — 그 1시간 앞(09:00Z)부터 회차를 거부한다(ADR-009 run.sh:186과 같은 경계)
(( $(date -u +%s) < $(date -u -d 2026-12-31T09:00:00Z +%s) )) || { echo "2026-12-31T09:00Z 이후 — 시드 회차가 측정 중 마감될 수 있다(측정-1)" >&2; exit 2; }
[[ -s "$IMAGES" ]] || { echo "--images <images.json>(run.sh --prepare-image) 필수" >&2; exit 2; }
[[ "$(jq -r .app.sha "$IMAGES")" == "$SHA" ]] || { echo "images.json의 SHA($(jq -r .app.sha "$IMAGES"))가 --sha $SHA와 다르다" >&2; exit 2; }
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
if (( WORKTREE )); then mkdir -p "$K6_TREE/k6/ADR-009" "$K6_TREE/k6/ADR-014" && cp -r "$REPO_ROOT/k6/ADR-009/scenarios" "$K6_TREE/k6/ADR-009/" && cp -r "$REPO_ROOT/k6/ADR-014/scenarios" "$K6_TREE/k6/ADR-014/"
else git -C "$REPO_ROOT" archive "$SHA" k6/ADR-009/scenarios k6/ADR-014/scenarios | tar -x -C "$K6_TREE"; fi
SCEN9="$K6_TREE/k6/ADR-009/scenarios" SCEN14="$K6_TREE/k6/ADR-014/scenarios"
HREASONS="$DIR/harness-reasons.txt"; : > "$HREASONS"
hreason() { echo "$1" >> "$HREASONS"; echo "$(date -Is) harness: $1" >> "$DIR/errors.log"; }
finish14() {   # 판정(summarize --rep) → status · meta. 인자: (선택) 강제 상태
  local st="${1:-}"
  jq -n --arg sha "$SHA" --arg harness "$HARNESS" --arg m "$METHOD" --arg mode "$(P .mode)" --arg c "$CELL" --argjson r "$REP" --argjson a "$ATTEMPT" \
        --arg started "$STARTED" --arg ended "$(date -Is)" --arg k6 "$("$K6_BIN" version 2>/dev/null | head -1)" --arg k6sha "$(sha256sum "$(command -v "$K6_BIN")" 2>/dev/null | cut -d' ' -f1)" \
        --arg pk6 "$PROBE_K6_VERSION" --arg app "$APP_ENV14" --arg res "$RES_ENV14" --arg k6env "$K6_ENV" --arg hh "${HH_SRC:-}" --arg img "$APP_ID" \
        --argjson seed "${SEED_T:-null}" --argjson k0 "${K6_T0:-null}" --argjson k1 "${K6_END:-null}" --argjson kx "${K6_EXIT:-null}" \
    '{sha: $sha, harness: $harness, method: $m, mode: $mode, cell: $c, rep: $r, attempt: $a, rerun: ($a > 1), started: $started, ended: $ended,
      k6_version: $k6, k6_sha256: $k6sha, probe_k6_version: $pk6, app_env: $app, res_env: $res, k6_env: $k6env, harness_hash: $hh, app_image_id: $img,
      seed_done_epoch: $seed, k6_launch_epoch: $k0, k6_end_epoch: $k1, k6_exit: $kx}' > "$DIR/meta.json.tmp" \
    && mv "$DIR/meta.json.tmp" "$DIR/meta.json" || { hreason meta-write-failed; st="invalid:meta-write-failed"; }
  # PC 링크 끊김(사용자 결정 ② 2026-10-29 — 회차 시작 ~ 지금의 이 PC 커널 로그 'eno1: Link is Down'): 요약기가 pc-link-down(환경) · 못 읽음 = pc-link-missing으로 판정
  bash "$S14/pclink14.sh" "$STARTED_EPOCH" "$(( $(date +%s) + 1 ))" > "$DIR/pc-link.json" 2>>"$DIR/errors.log" || echo "$(date -Is) pclink14 실패 rc=$?" >> "$DIR/errors.log"
  if [[ -z "$st" ]]; then st=$(python3 -I "$S14/summarize.py" --rep "$DIR" 2>>"$DIR/errors.log" | tail -1); [[ "$st" =~ ^(ok|invalid:) ]] || st="invalid:summarize-failed"; fi
  echo "$st" > "$DIR/status"
  echo "$(date -Is) $CELL $METHOD rep$REP attempt$ATTEMPT $st" >> "$MATRIX_LOG"
  log "$CELL $METHOD rep$REP → $st"
}
abort14() {   # k6 전 하네스 실패 — 앱 로그를 남기고 끝낸다(다음 회차 앞 compose_down14가 정리)
  hreason "$1"
  remote "docker logs --since '$STARTED' seatlab-app-1 2>&1" 2>/dev/null | gzip > "$DIR/app.log.gz"
  [[ -n "${POLL_PIDS:-}" ]] && { stop_pollers14 $POLL_PIDS; POLL_PIDS=""; }
  finish14 "invalid:$1"; exit 0
}

# ---- 기동 · 예열 · 시드 ---------------------------------------------------------------------------------------------------------------
log "ADR-014 $CELL $METHOD rep$REP attempt$ATTEMPT → $DIR"
BASE_URL="http://${SERVER_HOST}:8101"
if (( WORKTREE )); then
  remote "test -f $REMOTE_BASE/$DEPLOY_ID/.deployed" 2>/dev/null < /dev/null || {
    git -C "$REPO_ROOT" archive --format=tar "$SHA" -- . ":(exclude,glob)k6/*/results/**" | remote "rm -rf $REMOTE_BASE/.tmp-$DEPLOY_ID && mkdir -p $REMOTE_BASE/.tmp-$DEPLOY_ID && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID" \
    && tar -C "$REPO_ROOT" --exclude=k6/ADR-014/results --exclude=__pycache__ -cf - "${HPATHS[@]}" | remote "cd $REMOTE_BASE/.tmp-$DEPLOY_ID && rm -rf ${HPATHS[*]} && cd - > /dev/null && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID && touch $REMOTE_BASE/.tmp-$DEPLOY_ID/.deployed && rm -rf $REMOTE_BASE/$DEPLOY_ID && mv $REMOTE_BASE/.tmp-$DEPLOY_ID $REMOTE_BASE/$DEPLOY_ID"; } \
    || abort14 deploy-failed
else deploy_sha "$SHA" < /dev/null || abort14 deploy-failed; fi
compose_down14 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || abort14 compose-down-failed
[[ "$(image_id "seatlab-app:$APP_TAG")" == "$APP_ID" ]] || abort14 cond-image-mismatch
for img in $(jq -r '.aux | keys[]' "$IMAGES"); do [[ "$(image_id "$img")" == "$(jq -r --arg k "$img" '.aux[$k]' "$IMAGES")" ]] || abort14 aux-image-mismatch; done
compose_up14 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || abort14 compose-up-failed
wait_health 180 || abort14 unhealthy
[[ "$(ctr_image seatlab-app-1)" == "$APP_ID" ]] || abort14 cond-image-mismatch
reset_with_retry "$DIR" 2 10000 > /dev/null || abort14 reset-failed
BASE_URL="$BASE_URL" "$K6_BIN" run --quiet --out "csv=$DIR/warmup-requests.csv.gz" "$SCEN14/warmup14.js" > "$DIR/warmup.log" 2>&1 < /dev/null   # 예열(측정-3.6 — 선점 + 지도 + 확정, 회차 1·2)
WARM_RC=$?
# 예열 rc · 요청 이름:상태 분포(코드 리뷰 CR14-14) — rc ≠ 0이면 데워지지 않은 앱이 부하를 받는다 → 하네스 무효(요약기도 warmup-status.json으로 같은 사유를 낸다)
python3 -I "$S14/summarize.py" --warmup-status "$DIR/warmup-requests.csv.gz" "$WARM_RC" > "$DIR/warmup-status.json" 2>>"$DIR/errors.log"
(( WARM_RC == 0 )) || abort14 warmup-failed
RESET_TIMEOUT=1800 reset_with_retry "$DIR" "$SEED_S" "$SEED_N" > "$DIR/seed.json" || abort14 seed-failed
SEED_T=$(now)
seed_check14 "$SEED_S" "$SEED_N" > "$DIR/seed-check.json" 2>>"$DIR/errors.log" < /dev/null || hreason seed-check-failed
db_state > "$DIR/db-state-seed.json" 2>>"$DIR/errors.log" < /dev/null
app_config14 "$DIR" 2>>"$DIR/errors.log" < /dev/null || hreason app-config-failed
limit_key_check seatlab-app-1 > "$DIR/limit-key.json" 2>>"$DIR/errors.log" < /dev/null || hreason limit-key-check-failed
pg_settings > "$DIR/pg-settings.json" 2>>"$DIR/errors.log" < /dev/null
# k6 PC 다른 부하(명세 R5 ② — 이 PC만 읽는다): 회차마다 기록 · 판정은 회차 중 MemAvailable 하한(요약기 pc-mem-low) · 어겨도 여기서 멈추지 않는다(이름표 pc-busy-before)
bash "$S14/pccheck14.sh" > "$DIR/pc-check.json" 2>>"$DIR/errors.log" < /dev/null || echo "$(date -Is) pc-check: PC 다른 부하 — $(jq -c .why "$DIR/pc-check.json" 2>/dev/null)" >> "$DIR/errors.log"

# ---- 표본기 -------------------------------------------------------------------------------------------------------------------------
TAG14="$(echo "$CID" | tr '/' '_')-$CELL-$METHOD-rep$REP-$(date +%Y%m%d%H%M%S)-$RANDOM"
SAMPLER_COPY="$REMOTE_BASE/$DEPLOY_ID/.samples/$TAG14.jsonl" STATE_COPY="$REMOTE_BASE/$DEPLOY_ID/.samples/$TAG14-state.jsonl" H4_COPY="$REMOTE_BASE/$DEPLOY_ID/.samples/$TAG14-h4.jsonl"
POLL_PIDS=$(start_pollers14 "$DIR" "$KIND" "$EVERY" | xargs)
for _ in $(seq 1 30); do [[ -s "$DIR/timeline-locks.jsonl" ]] && break; sleep 0.5; done
[[ -s "$DIR/timeline-locks.jsonl" ]] || echo "$(date -Is) samples-before-load timeout: timeline-locks.jsonl 15s 동안 비어 있음" >> "$DIR/errors.log"
if [[ "$KIND" == main ]]; then
  for _ in $(seq 1 30); do [[ -s "$DIR/timeline-seatstate.jsonl" ]] && break; sleep 0.5; done
  [[ -s "$DIR/timeline-seatstate.jsonl" ]] || echo "$(date -Is) state-samples-before-load timeout: 15s 동안 비어 있음" >> "$DIR/errors.log"
fi
# 시작 대기(ADR-009 명세 §13 — 시드 끝 → 첫 요청 87 ± 5초): 대기점 70초까지 기다린 뒤 k6 직전 스냅숏 → 남은 만큼 맞춤
hold_left=$(awk -v s="$SEED_T" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { d = s + h - n; printf "%.3f", (d > 0 ? d : 0) }')
awk -v s="$SEED_T" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { exit !(n - s > h + 1) }' && abort14 pre-k6-overrun
sleep "$hold_left"
io_snapshot > "$DIR/io-before.json" 2>>"$DIR/errors.log" < /dev/null
table_stats > "$DIR/table-before.json" 2>>"$DIR/errors.log" < /dev/null || hreason table-stats-failed
drift_snap app > "$DIR/drift-before.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-snap-failed
python3 -I "$S14/actsnap14.py" "$BASE_URL" > "$DIR/act14-before.json" 2>>"$DIR/errors.log" < /dev/null || hreason app-snap-failed
db_state > "$DIR/db-state-k6.json" 2>>"$DIR/errors.log" < /dev/null
app_db_clock > "$DIR/app-db-clock-before.json" 2>>"$DIR/errors.log" < /dev/null
now2=$(now)
awk -v n="$now2" -v b="$SEED_T" -v t="$PRE_K6_TARGET_S" -v l="$PRE_K6_TOL_S" -v lead="$PRE_K6_LEAD_S" 'BEGIN { exit !((n - b) + lead > t + l) }' && abort14 pre-k6-overrun
probes_start14 "$DIR" "$(( $(P .k6.duration_s) + 180 ))"
topup=$(awk -v t="$PRE_K6_TARGET_S" -v n="$(now)" -v b="$SEED_T" -v lead="$PRE_K6_LEAD_S" 'BEGIN { d = t - lead - (n - b); printf "%.3f", (d > 0 ? d : 0) }')
sleep "$topup"

# ---- k6 -----------------------------------------------------------------------------------------------------------------------------
CLK_BEFORE=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
OFF_MS=$(jq -r '(.offset_s // 0) * 1000 | round' <<< "${CLK_BEFORE:-null}" 2>/dev/null); [[ "$OFF_MS" =~ ^-?[0-9]+$ ]] || OFF_MS=0
K6_STARTED=$(date -Is); K6_T0=$(now)
# 판정 창 끝 앱 지표 스냅숏(코드 리뷰 CR14-13 — P12의 틱 실패 · :missingIds 롤백을 창 안 증분으로 센다 · 측정-5 M11 MR-9). k6와 함께 돌고 k6 뒤 wait로 거둔다
( sleep "$(awk -v k="$K6_T0" -v w="$(P '.window.from_s + .window.len_s')" -v l="$WEND_LAG_S" -v lead="$PRE_K6_LEAD_S" -v n="$(now)" 'BEGIN { d = k + lead + w + l - n; printf "%.3f", (d > 0 ? d : 0) }')"
  python3 -I "$S14/actsnap14.py" "$BASE_URL" > "$DIR/act14-window-end.json" 2>>"$DIR/errors.log" ) < /dev/null &
WEND_PID=$!
jq -n --argjson seed "$SEED_T" --argjson k6 "$K6_T0" --argjson hold "$PRE_K6_HOLD_S" --argjson target "$PRE_K6_TARGET_S" --argjson tol "$PRE_K6_TOL_S" --argjson topup "$topup" \
      --argjson lead "$PRE_K6_LEAD_S" --argjson pvus "$(P .k6.pre_vus_total)" \
  '{seed_done_epoch: $seed, k6_launch_epoch: $k6, seed_to_launch_s: (($k6 - $seed) * 1000 | round / 1000), hold_s: $hold, target_s: $target, tol_s: $tol, topup_s: $topup,
    lead_s: $lead, pre_vus_total: $pvus}' > "$DIR/pre-k6.json"
env $K6_ENV CLOCK_OFFSET_MS="$OFF_MS" BASE_URL="$BASE_URL" OUT_DIR="$DIR" "$K6_BIN" run --quiet --out "csv=$DIR/k6-requests.csv.gz" "$K6_TREE/$K6_SCRIPT" > "$DIR/k6-stdout.log" 2>&1 < /dev/null
K6_EXIT=$?; K6_END=$(now)
(( K6_EXIT == 0 )) || kill "$WEND_PID" 2>/dev/null   # k6가 실패로 일찍 끝났으면 창 끝까지 기다리지 않는다(k6-exit-N · app-metric-missing으로 무효)
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
python3 -I "$S14/actsnap14.py" "$BASE_URL" > "$DIR/act14-after-k6.json" 2>>"$DIR/errors.log" < /dev/null || hreason app-snap-failed
pg_storage > "$DIR/pg-storage.json" 2>>"$DIR/errors.log" < /dev/null
CONV=$(P .converge_s)
sleep "$(awk -v e="$K6_END" -v c="$CONV" -v n="$(now)" 'BEGIN { d = e + c - n; print (d > 0 ? d : 0) }')"
table_stats > "$DIR/table-end.json" 2>>"$DIR/errors.log" < /dev/null || hreason table-stats-failed
CONSISTENCY_TIMEOUT=600 consistency 20 > "$DIR/consistency.json" 2>>"$DIR/errors.log" < /dev/null; crc=$?
(( crc == 0 )) || { rm -f "$DIR/consistency.json"; echo "$crc" > "$DIR/consistency.rc"; hreason "end-check-failed"; }
drift_snap app > "$DIR/drift-end.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-snap-failed
python3 -I "$S14/actsnap14.py" "$BASE_URL" > "$DIR/act14-end.json" 2>>"$DIR/errors.log" < /dev/null || hreason app-snap-failed
drift_db_dump v10 > "$DIR/drift-db.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-db-failed
app_db_clock > "$DIR/app-db-clock-after.json" 2>>"$DIR/errors.log" < /dev/null
pg_storage > "$DIR/pg-storage-end.json" 2>>"$DIR/errors.log" < /dev/null

# ---- 수집 · 정리 ------------------------------------------------------------------------------------------------------------------------
stop_pollers14 $POLL_PIDS; POLL_PIDS=""
remote "pkill -f 'FROM pg_locks WHERE NOT granted' || true" > /dev/null 2>&1 < /dev/null
if [[ "$KIND" == main ]]; then psql_sampler_kill "$STATE_COPY" < /dev/null; psql_sampler_kill "$H4_COPY" < /dev/null; fi
# 대기 표본 서버 사본(ADR-009 recover_samples와 같은 규칙 — 한 사본에서 두 파일)
if remote "cat '$SAMPLER_COPY'" > "$DIR/.sampler-remote.jsonl" 2>/dev/null < /dev/null && [[ -s "$DIR/.sampler-remote.jsonl" ]]; then
  nl=$(wc -l < "$DIR/timeline-locks.jsonl" 2>/dev/null || echo 0); nw=$(wc -l < "$DIR/timeline-waits.jsonl" 2>/dev/null || echo 0)
  rl=$(grep -c '"lock_waiting"' "$DIR/.sampler-remote.jsonl"); rw=$(grep -c '"waits"' "$DIR/.sampler-remote.jsonl")
  if (( rl > nl || rw > nw )); then grep '"lock_waiting"' "$DIR/.sampler-remote.jsonl" > "$DIR/timeline-locks.jsonl"; grep '"waits"' "$DIR/.sampler-remote.jsonl" > "$DIR/timeline-waits.jsonl"
    echo "$(date -Is) waits recovered-from-server locks $nl→$rl waits $nw→$rw" >> "$DIR/sampler-recovery.txt"
  else echo "$(date -Is) waits stream-complete" >> "$DIR/sampler-recovery.txt"; fi
else echo "$(date -Is) waits server-copy-unavailable" >> "$DIR/sampler-recovery.txt"; fi
rm -f "$DIR/.sampler-remote.jsonl"; remote "rm -f '$SAMPLER_COPY'" > /dev/null 2>&1 < /dev/null
if [[ "$KIND" == main ]]; then recover_copy "$DIR" "$STATE_COPY" timeline-seatstate.jsonl < /dev/null; recover_copy "$DIR" "$H4_COPY" timeline-h4.jsonl < /dev/null; fi
for c in app db; do
  remote "docker logs --since '$STARTED' seatlab-$c-1 2>&1" < /dev/null | gzip > "$DIR/$c.log.gz"
  echo "$c pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]} bytes=$(zcat "$DIR/$c.log.gz" 2>/dev/null | wc -c)" >> "$DIR/logs-status.txt"
done
remote "docker exec seatlab-app-1 cat /dev/shm/gc.log" < /dev/null | gzip > "$DIR/gc.log.gz"
echo "app pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]} bytes=$(zcat "$DIR/gc.log.gz" 2>/dev/null | wc -c)" >> "$DIR/gc-status.txt"
python3 "$LIB9_DIR/summarize.py" --gc "$DIR" > "$DIR/gc.json" 2>>"$DIR/errors.log" || echo "gc summary failed" >> "$DIR/errors.log"
amem="$(remote "docker inspect --format '{{.HostConfig.Memory}}' seatlab-app-1" 2>/dev/null < /dev/null | tr -d '[:space:]')"
ajto="$(remote "docker exec seatlab-app-1 sh -c 'printf %s \"\$JAVA_TOOL_OPTIONS\"'" 2>/dev/null < /dev/null)"
jq -n --arg mem "$amem" --arg jto "$ajto" '{mem_bytes:($mem|tonumber? // null), java_tool_options:$jto, want_mem:"2g", want_gc:"zgc"}' > "$DIR/app-jvm.json"
python3 -I "$LIB9_DIR/tail.py" --probe-check "$DIR" > /dev/null 2>>"$DIR/errors.log"   # 측정기 정상(probe-check.json — 요약기 probe-missing)
gzip -f "$DIR/k6-stdout.log"
finish14
