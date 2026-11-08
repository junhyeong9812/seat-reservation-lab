#!/usr/bin/env bash
# ADR-016 회차 실행기 — paid · cost 회차 하나(셀 × 구성 × 회차 번호). ADR-015 run.sh의 얇은 변형(같은 순서 · 같은 시작 대기 · 같은 수집 — 바뀌는 것만 적는다):
#   내리기(seatlab 컨테이너 · DB 볼륨) → 이미지 확인 → 기동(① · ④ · θ · W · M — compose.paid16.yml) → 앱 1개 확인 → 예열(warmup15 · payment-start 켬) → 시드 · 시드 확인 →
#   표본기(서버 · PC · 대기 · Hikari · PC TIME_WAIT 2초) → 시작 대기(87초) → k6(paid-flow — 세션 행 = sessions16.log) · 측정기 → 직후 → 수렴(max(TTL, W) + 배치 + 여유) →
#   끝 판정기 · 끝 상태 reservation(payment_uid · 상태) · pg_stat_statements · table_stats · 앱 지표(actsnap16) → 수집 → 판정(summarize16.py --rep → status).
#   race 셀은 이 파일이 아니라 ADR-015 run.sh(--results-root)가 돈다(campaign16.sh). 계획 = plan16.py · 라이브러리 = ADR-015 lib15.sh → lib14.sh → ADR-009 lib.sh(동결 사본 source).
#
#   scripts/run16.sh --sha <SHA> --images <images.json> --cell paid|cost --config cond|cond+b|ext --rep <k> --id <캠페인 id> [--attempt n]
#       [--k6-bin k6] [--probe-k6-bin <경로>] [--worktree] [--results-root <폴더>] [--dry-run] [--simulate]
#     결과 = <results-root 기본 k6/ADR-016/results>/<id>/<셀>/<구성>/rep<k>/ · --attempt n(>1)은 앞 시도를 rep<k>.attempt<n−1>로 옮긴다.
#   status: ok · invalid:<사유,…>. plan16.py FIXED가 False면 실제 실행을 거부한다(--dry-run · --simulate만).
set -uo pipefail

if [[ "${RUN16_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"
  HH_SRC="$(bash "$src/hhash16.sh" "$repo")" || { echo "하네스 해시 실패($repo)" >&2; exit 2; }
  [[ -z "${HARNESS_HASH16:-}" || "$HH_SRC" == "$HARNESS_HASH16" ]] || { echo "하네스 해시 $HH_SRC ≠ 캠페인 동결본 $HARNESS_HASH16" >&2; exit 2; }
  frozen="$(mktemp -d)"
  mkdir -p "$frozen/k6/ADR-016" "$frozen/k6/ADR-015" "$frozen/k6/ADR-014" "$frozen/k6/ADR-009"
  cp -r "$src" "$frozen/k6/ADR-016/scripts" && cp -r "$repo/k6/ADR-015/scripts" "$frozen/k6/ADR-015/scripts" \
    && cp -r "$repo/k6/ADR-014/scripts" "$frozen/k6/ADR-014/scripts" && cp -r "$repo/k6/ADR-009/scripts" "$frozen/k6/ADR-009/scripts" \
    || { echo "동결 사본 실패" >&2; exit 2; }
  export RUN16_FROZEN=1 FROZEN16="$frozen" HH_SRC ADR16_DIR="${ADR16_DIR:-$(cd "$src/.." && pwd)}" REPO_ROOT="${REPO_ROOT:-$repo}"
  exec bash "$frozen/k6/ADR-016/scripts/run16.sh" "$@"
fi
export LIB9_DIR="$FROZEN16/k6/ADR-009/scripts" LIB14_SRC="$FROZEN16/k6/ADR-014/scripts"
S16="$FROZEN16/k6/ADR-016/scripts" S15="$FROZEN16/k6/ADR-015/scripts" S14="$LIB14_SRC"
DRY=0 SIM=0; for a in "$@"; do [[ "$a" == --dry-run ]] && DRY=1; [[ "$a" == --simulate ]] && SIM=1; done
source "$S15/lib15.sh"
set +e
C16="k6/ADR-016/compose.paid16.yml"
COMPOSE_EXTRA="$COMPOSE_EXTRA -f $C16"   # lib15(tmpfs · ADR-015 오버레이) 뒤에 M 오버레이 — compose14 · probe_compose가 호출 때 읽는다

SHA="" IMAGES="" CELL="" CONFIG="" REP="" CID="" ATTEMPT=1 WORKTREE=0 K6_BIN=k6 RESULTS_ROOT=""
PROBE_K6_BIN="$HOME/.local/opt/k6-v2.2.0/k6" PROBE_IMAGE="${PROBE_IMAGE:-grafana/k6:2.2.0}" PROBE_RATE=50
PRE_K6_HOLD_S="${PRE_K6_HOLD_S:-70}" PRE_K6_TARGET_S="${PRE_K6_TARGET_S:-87}" PRE_K6_TOL_S="${PRE_K6_TOL_S:-5}" PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-}"   # ADR-015 run.sh 그대로
IDLE_AFTER_K6_S=11 WEND_LAG_S=3 TW_EVERY_S=2
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;; --images) IMAGES="$2"; shift 2 ;; --cell) CELL="$2"; shift 2 ;; --config) CONFIG="$2"; shift 2 ;;
    --rep) REP="$2"; shift 2 ;; --id) CID="$2"; shift 2 ;; --attempt) ATTEMPT="$2"; shift 2 ;; --worktree) WORKTREE=1; shift ;;
    --k6-bin) K6_BIN="$2"; shift 2 ;; --probe-k6-bin) PROBE_K6_BIN="$2"; shift 2 ;; --results-root) RESULTS_ROOT="$2"; shift 2 ;;
    --dry-run|--simulate) shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
cleanup16() {
  [[ "$BASHPID" == "$$" ]] || return 0
  [[ -n "${POLL_PIDS:-}" ]] && stop_pollers14 $POLL_PIDS
  [[ -n "${WEND_PID:-}" ]] && kill "$WEND_PID" 2>/dev/null
  if [[ -n "${PROBE_PC_PID:-}" ]] && kill -0 "$PROBE_PC_PID" 2>/dev/null; then kill -INT "$PROBE_PC_PID" 2>/dev/null; fi
  [[ -n "${K6_TREE:-}" && -d "${K6_TREE:-}" ]] && rm -rf "${K6_TREE:?}"
  [[ -n "${FROZEN16:-}" && "$FROZEN16" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "${FROZEN16:?}"
}
trap cleanup16 EXIT
short() { git -C "$REPO_ROOT" rev-parse --short=8 "$1^{commit}" 2>/dev/null; }
now() { date +%s.%N; }

# ---- 인자 · 계획 ---------------------------------------------------------------------------------------------------------------------
[[ "$CELL" =~ ^(paid|cost)$ && "$CONFIG" =~ ^(cond|cond\+b|ext)$ && "$REP" =~ ^[1-3]$ && "$ATTEMPT" =~ ^[1-9]$ && -n "$CID" ]] \
  || { echo "--cell paid|cost --config cond|cond+b|ext --rep 1..3 --id <id> 필수(race는 ADR-015 run.sh)" >&2; exit 2; }
PLAN_JSON="$(python3 -I "$S16/plan16.py" --render --cell "$CELL" --config "$CONFIG" --rep "$REP")" || exit 2
P() { jq -r "$1" <<< "$PLAN_JSON"; }
if (( ! DRY && ! SIM )) && [[ "$(P .fixed)" != true ]]; then   # SHA · 서버보다 먼저(리뷰 L1-2 — 거부는 어떤 원격 · git 동작보다 앞)
  echo "plan16.py FIXED = False — 측정 전 고정 커밋 전이라 실제 실행을 거부한다(--dry-run · --simulate만)" >&2; exit 2
fi
SHA="$(short "${SHA:-HEAD}")"; [[ -n "$SHA" ]] || { echo "SHA 해석 실패" >&2; exit 2; }
PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-$(P .k6.lead_s)}"
mapfile -t HPATHS < <(bash "$S16/hhash16.sh" --paths)
HARNESS="sha:$SHA" DEPLOY_ID="$SHA"
EXCL=(':(exclude)k6/ADR-016/results' ':(exclude)k6/ADR-015/results')
if (( WORKTREE )); then
  [[ "${CAMPAIGN16:-0}" != 1 || "${CAMPAIGN16_WORKTREE:-0}" == 1 ]] || { echo "--worktree는 스모크 전용" >&2; exit 2; }
  HARNESS="worktree:${HH_SRC:0:8}"; DEPLOY_ID="$SHA-wt16-${HH_SRC:0:8}"
elif [[ -n "${HARNESS_HASH16:-}" ]]; then
  :
elif ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- "${HPATHS[@]}" "${EXCL[@]}" 2>/dev/null \
     || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- "${HPATHS[@]}" "${EXCL[@]}")" ]]; then
  echo "하네스(${HPATHS[*]})가 $SHA와 다르다 — 커밋하거나 --worktree(스모크)" >&2; (( DRY || SIM )) || exit 2
fi
APP_ENV14="$(P '.app_env | to_entries | map("\(.key)=\(.value)") | join(" ")')"
RES_ENV14="APP_CPUS=4 DB_CPUS=4 APP_MEM=2g APP_JVM_OPTS=-XX:+UseZGC APP_JVM_OPTS2=-XX:+ZGenerational APP_JVM_OPTS3= APP_JVM_OPTS4= \
PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=12g DB_MEM=14g"   # ADR-015 run.sh 자원 그대로
K6_ENV_Q=()   # k6 환경(FLOWS는 JSON이라 env 낱말로 넘긴다 — 공백 없음)
while IFS= read -r kv; do K6_ENV_Q+=("$kv"); done < <(P '.k6.env | to_entries[] | "\(.key)=\(.value)"')
K6_SCRIPT="$(P .k6.script)"
OUT_ROOT="${RESULTS_ROOT:-$ADR16_DIR/results}/$CID"; DIR="$OUT_ROOT/$CELL/$CONFIG/rep$REP"
SEED_S=$(P .seed.schedules) SEED_N=$(P .seed.seats)
if (( DRY )); then
  echo "# dry-run — 셀 $CELL · 구성 $CONFIG · 회차 $REP · 시도 $ATTEMPT · 하네스 $HARNESS · 배포 $DEPLOY_ID · FIXED $(P .fixed)"
  echo "결과 폴더: $DIR"
  echo "compose: $(compose14)"
  echo "env: $(env14)"
  echo "기동 검사: $(P '.startup | "M \(.M) ≤ θ \(.theta) < \(.limit) → \(if .ok then "통과" else "위반 \(.why)" end)"')"
  echo "시드: /internal/reset?schedules=2&seatsPerSchedule=10000 → warmup15.js(PAY_START=1) → /internal/reset?schedules=$SEED_S&seatsPerSchedule=$SEED_N"
  echo "N16-1 격리 탐침: $( (( $(P '.probe16.holds // 0') > 0 )) && echo "예열 뒤 reset 1×10000 → 홀드 $(P .probe16.holds)개 → 스냅숏 → payment-start만 $(P .probe16.holds)회 → 스냅숏 → probe16.json" || echo "없음(④)")"
  echo "표본기: 서버·docker stats 10s · 대기 0.5s · Hikari 1s · PC TIME_WAIT ${TW_EVERY_S}s · pg_stat_statements 켬"
  echo "k6: ${K6_ENV_Q[*]} CLOCK_OFFSET_MS=<k6 직전 시계 차> CLOCK_ERR_MS=<왕복/2 + $(P .k6.env.CLOCK_ERR_MARGIN_MS)> $K6_BIN run --quiet --log-format=json --console-output=$DIR/sessions16.log --out csv=$DIR/k6-requests.csv.gz <k6 트리>/$K6_SCRIPT"
  echo "시각: 첫 요청 ${PRE_K6_TARGET_S}±${PRE_K6_TOL_S}초(lead ${PRE_K6_LEAD_S}초) · 도착 $(P .d_arr_s)초 · k6 약 $(P .k6.est_s)초(grace $(P .grace_s)) · 창 $(P '.window | "\(.kind) \(.from_s)+\(.len_s)"')초 · 수렴 $(P .converge_s)초 · 회차 추정 $(P .est_s)초"
  echo "VU: 미리 $(P .k6.pre_vus | jq -c .) · 최대 $(P .k6.max_vus | jq -c .)"
  exit 0
fi
if (( SIM )); then   # campaign16.sh 시험용 — 서버 접속 없음(ADR-015 run.sh --simulate와 같다)
  if [[ -e "$DIR" ]]; then
    if (( ATTEMPT > 1 )) && [[ ! -e "$DIR.attempt$((ATTEMPT - 1))" ]]; then mv "$DIR" "$DIR.attempt$((ATTEMPT - 1))"; else echo "$DIR 이미 있다" >&2; exit 2; fi
  fi
  mkdir -p "$DIR"; echo "$PLAN_JSON" | jq . > "$DIR/plan.json"
  timeout 2 cat > /dev/null 2>&1
  st=ok; [[ -n "${SIM_PLAN:-}" && -s "$SIM_PLAN" ]] && { v=$(grep -m1 -F "$REP:$CELL:$CONFIG:$ATTEMPT=" "$SIM_PLAN" | cut -d= -f2-); [[ -n "$v" ]] && st="$v"; }
  jq -n --arg sha "$SHA" --arg c "$CELL" --arg g "$CONFIG" --argjson r "$REP" --argjson a "$ATTEMPT" --arg hh "${HH_SRC:-}" \
    '{sha: $sha, cell: $c, config: $g, rep: $r, attempt: $a, rerun: ($a > 1), harness_hash: $hh, simulated: true}' > "$DIR/meta.json"
  [[ "$st" == skip ]] || echo "$st" > "$DIR/status"
  echo "$(date -Is) $CELL $CONFIG rep$REP attempt$ATTEMPT ${st} (simulated)" >> "$OUT_ROOT/MATRIX.log"
  exit 0
fi
(( $(date -u +%s) < $(date -u -d 2026-12-31T09:00:00Z +%s) )) || { echo "2026-12-31T09:00Z 이후 — 시드 회차가 측정 중 마감될 수 있다" >&2; exit 2; }
[[ -s "$IMAGES" ]] || { echo "--images <images.json>(ADR-015 run.sh --prepare-image) 필수" >&2; exit 2; }
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
SCEN_PATHS=(k6/ADR-009/scenarios k6/ADR-014/scenarios k6/ADR-015/scenarios k6/ADR-016/scenarios)
if (( WORKTREE )); then for s in "${SCEN_PATHS[@]}"; do mkdir -p "$K6_TREE/$(dirname "$s")" && cp -r "$REPO_ROOT/$s" "$K6_TREE/$(dirname "$s")/"; done
else git -C "$REPO_ROOT" archive "$SHA" "${SCEN_PATHS[@]}" | tar -x -C "$K6_TREE"; fi
SCEN9="$K6_TREE/k6/ADR-009/scenarios" SCEN15="$K6_TREE/k6/ADR-015/scenarios"
HREASONS="$DIR/harness-reasons.txt"; : > "$HREASONS"
hreason() { echo "$1" >> "$HREASONS"; echo "$(date -Is) harness: $1" >> "$DIR/errors.log"; }
snap16() {   # 인자: 이름 — ADR-014(actsnap14) · ADR-015(actsnap15 — 틱 · 연장 · 5xx) · ADR-016(actsnap16 — pay_start · rejected · failed) 같은 시점
  python3 -I "$S14/actsnap14.py" "$BASE_URL" > "$DIR/act14-$1.json" 2>>"$DIR/errors.log" < /dev/null || hreason app-snap-failed
  python3 -I "$S15/actsnap15.py" "$BASE_URL" > "$DIR/act15-$1.json" 2>>"$DIR/errors.log" < /dev/null || hreason app-snap-failed
  python3 -I "$S16/actsnap16.py" "$BASE_URL" > "$DIR/act16-$1.json" 2>>"$DIR/errors.log" < /dev/null || hreason app-snap-failed
}
# 끝 상태 reservation(record-level — 명세 결정표 '끝 상태 DB'): payment_uid · 상태 · 좌석
reservation_dump() { db_json <<'SQL'
SELECT json_build_object('t', clock_timestamp(), 'rows', coalesce((SELECT json_agg(json_build_object('u', payment_uid, 's', status, 'seat', seat_id)) FROM reservation), '[]'::json));
SQL
}
# ADR-016 전용 pgss 쓰기 문장 덤프(N16-1 (b) 입력 — 리뷰 L2-3): 표 필터 없음 · 문장 전문 · 쓰기 동사(INSERT · UPDATE · DELETE · MERGE · WITH)로 시작하는 문장만.
#   lib15 pgss_snapshot(표 이름 필터 · 200자)은 고치지 않고 그대로 따로 남긴다(설명용)
pgss16_writes() { db_json <<'SQL'
SELECT json_build_object('t', clock_timestamp(), 'rows', coalesce((SELECT json_agg(json_build_object('q', regexp_replace(query, '\s+', ' ', 'g'), 'calls', calls, 'rows', rows)
        ORDER BY calls DESC) FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
   WHERE d.datname = 'seat' AND calls > 0 AND query ~* '^\s*(insert|update|delete|merge|with)\M'), '[]'::json));
SQL
}
# N16-1 격리 탐침(리뷰 L2-9 — 경로별 증거): ① check 회차(plan16 probe16.holds > 0)만. 예열 뒤 · 본 시드 전에 — 초기화(1 × 10000) → pgss 0 →
#   기준 스냅숏(table_stats) → 홀드 P개(탐침 사용자 대역) → 스냅숏(table_stats · pgss16_writes) → payment-start만 P회(다른 요청 없음) → 스냅숏 → probe16.json.
#   스냅숏마다 앞에서 PROBE_WAIT_S(≥ 12초 = PG16 PGSTAT_IDLE_INTERVAL 10초 + 여유 — 리뷰 L3-3)를 기다린다: 표 통계는 세션이 쉴 때 늦게 반영된다.
#   양성 대조 = 기준 → 전 스냅숏의 seat_hold n_tup_ins 증분 = P(반영이 끝났다는 증거 — 아니면 요약기가 탐침 불성립으로 본다).
#   요약기가 두 스냅숏의 쓰기 증분 0을 본다(seat_hold · product_seat · user_hold_quota · reservation n_tup_* · pgss 쓰기 문장 rows). 뒤의 본 시드 reset이 상태를 지운다.
probe16() {   # 인자: 회차폴더
  local dir="$1" n ub i hid x st t0 tb ta pb pa ids=() codes=() w=12
  n=$(P .probe16.holds) ub=$(P .probe16.user_base)
  reset_with_retry "$dir" 1 10000 > /dev/null || return 1
  pgss_prepare > /dev/null || return 1
  sleep "$w"; t0=$(table_stats) || return 1
  for ((i = 1; i <= n; i++)); do
    hid=$(curl -fsS --max-time 10 -X POST -H "X-User-Id: $(( ub + i ))" "$BASE_URL/api/schedules/1/seats/$i/hold" 2>/dev/null | jq -r '.holdId // empty' 2>/dev/null)
    [[ "$hid" =~ ^[0-9]+$ ]] && ids+=("$i:$hid")
  done
  sleep "$w"
  tb=$(table_stats) && pb=$(pgss16_writes) || return 1
  for x in "${ids[@]}"; do
    st=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST -H "X-User-Id: $(( ub + ${x%%:*} ))" "$BASE_URL/api/holds/${x#*:}/payment-start")
    codes+=("$st")
  done
  sleep "$w"
  ta=$(table_stats) && pa=$(pgss16_writes) || return 1
  jq -n --argjson t0 "$t0" --argjson w "$w" --argjson tb "$tb" --argjson ta "$ta" --argjson pb "$pb" --argjson pa "$pa" \
        --argjson holds "$(printf '%s\n' "${ids[@]}" | jq -Rsc 'split("\n") | map(select(length > 0))')" \
        --argjson codes "$(printf '%s\n' "${codes[@]}" | jq -Rsc 'split("\n") | map(select(length > 0))')" \
    '{wait_s: $w, holds: $holds, pay_codes: $codes, table_base: $t0, table_before: $tb, table_after: $ta, pgss_before: $pb, pgss_after: $pa}' > "$dir/probe16.json"
}
hold_rows() { db_json <<'SQL'
SELECT json_build_object('t', clock_timestamp(), 'holds', (SELECT count(*) FROM seat_hold), 'held_seats', (SELECT count(*) FROM product_seat WHERE status = 'HELD'));
SQL
}
# PC TIME_WAIT 표본기(설계-9 R16-2 · 명세 cost 지표): 2초마다 이 PC의 TIME_WAIT 소켓 — 전체 · 서버(SERVER_HOST:8101) 대상
# ss가 실패하면 0으로 세지 않고 그 표본에 err를 남긴다(요약기 → tw-missing · 리뷰 L1-6). 서버 대상 = dst <IP> dport = :8101(호스트 이름이면 IPv4로 풀어 둔다)
TW_IP="$SERVER_HOST"; [[ "$TW_IP" =~ ^[0-9.]+$ ]] || TW_IP="$(getent ahostsv4 "$SERVER_HOST" 2>/dev/null | awk 'NR == 1 { print $1 }')"
tw_sampler() {   # 인자: 회차폴더 → 백그라운드 PID
  ( while :; do
      a=$(ss -Htan state time-wait 2>/dev/null); ra=$?
      s=$( [[ -n "$TW_IP" ]] && ss -Htan state time-wait dst "$TW_IP" dport = :8101 2>/dev/null ); rs=$?; [[ -n "$TW_IP" ]] || rs=99
      if (( ra == 0 && rs == 0 )); then
        echo "{\"t\":\"$(date -Is)\",\"t_ms\":$(date +%s%3N),\"tw_all\":$(grep -c . <<< "$a"),\"tw_server\":$(grep -c . <<< "$s")}" >> "$1/timeline-tw.jsonl"
      else
        echo "{\"t\":\"$(date -Is)\",\"t_ms\":$(date +%s%3N),\"err\":\"ss rc $ra/$rs\"}" >> "$1/timeline-tw.jsonl"
      fi
      sleep "$TW_EVERY_S"
    done ) > /dev/null 2>&1 < /dev/null & echo $!
}
finish16() {
  local st="${1:-}"
  jq -n --arg sha "$SHA" --arg harness "$HARNESS" --arg c "$CELL" --arg g "$CONFIG" --argjson r "$REP" --argjson a "$ATTEMPT" \
        --arg started "$STARTED" --arg ended "$(date -Is)" --arg k6 "$("$K6_BIN" version 2>/dev/null | head -1)" --arg k6sha "$(sha256sum "$(command -v "$K6_BIN")" 2>/dev/null | cut -d' ' -f1)" \
        --arg pk6 "$PROBE_K6_VERSION" --arg app "$APP_ENV14" --arg res "$RES_ENV14" --arg k6env "${K6_ENV_Q[*]}" --arg hh "${HH_SRC:-}" --arg img "$APP_ID" \
        --argjson seed "${SEED_T:-null}" --argjson k0 "${K6_T0:-null}" --argjson k1 "${K6_END:-null}" --argjson kx "${K6_EXIT:-null}" \
    '{sha: $sha, harness: $harness, cell: $c, config: $g, rep: $r, attempt: $a, rerun: ($a > 1), started: $started, ended: $ended,
      k6_version: $k6, k6_sha256: $k6sha, probe_k6_version: $pk6, app_env: $app, res_env: $res, k6_env: $k6env, harness_hash: $hh, app_image_id: $img,
      seed_done_epoch: $seed, k6_launch_epoch: $k0, k6_end_epoch: $k1, k6_exit: $kx}' > "$DIR/meta.json.tmp" \
    && mv "$DIR/meta.json.tmp" "$DIR/meta.json" || { hreason meta-write-failed; st="invalid:meta-write-failed"; }
  bash "$S14/pclink14.sh" "$STARTED_EPOCH" "$(( $(date +%s) + 1 ))" > "$DIR/pc-link.json" 2>>"$DIR/errors.log" || echo "$(date -Is) pclink14 실패 rc=$?" >> "$DIR/errors.log"
  if [[ -z "$st" ]]; then
    python3 -I "$S16/summarize16.py" --rep "$DIR" > "$DIR/.summarize.out" 2>>"$DIR/errors.log"; local src=$?
    st=$(tail -n 1 "$DIR/.summarize.out"); rm -f "$DIR/.summarize.out"
    (( src == 0 )) && [[ "$st" =~ ^(ok|invalid:) ]] || st="invalid:summarize-failed"
  fi
  echo "$st" > "$DIR/status"
  echo "$(date -Is) $CELL $CONFIG rep$REP attempt$ATTEMPT $st" >> "$MATRIX_LOG"
  log "$CELL $CONFIG rep$REP → $st"
}
abort16() {
  hreason "$1"
  remote "docker logs --since '$STARTED' seatlab-app-1 2>&1" 2>/dev/null | gzip > "$DIR/app.log.gz"
  [[ -n "${POLL_PIDS:-}" ]] && { stop_pollers14 $POLL_PIDS; POLL_PIDS=""; }
  finish16 "invalid:$1"; exit 0
}

# ---- 기동(전 인스턴스 정지 → 새 구성) · 예열 · 시드 --------------------------------------------------------------------------------------
log "ADR-016 $CELL $CONFIG rep$REP attempt$ATTEMPT → $DIR"
BASE_URL="http://${SERVER_HOST}:8101"
if (( WORKTREE )); then
  remote "test -f $REMOTE_BASE/$DEPLOY_ID/.deployed" 2>/dev/null < /dev/null || {
    git -C "$REPO_ROOT" archive --format=tar "$SHA" -- . ":(exclude,glob)k6/*/results/**" | remote "rm -rf $REMOTE_BASE/.tmp-$DEPLOY_ID && mkdir -p $REMOTE_BASE/.tmp-$DEPLOY_ID && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID" \
    && tar -C "$REPO_ROOT" --exclude=k6/ADR-016/results --exclude=k6/ADR-015/results --exclude=__pycache__ -cf - "${HPATHS[@]}" | remote "cd $REMOTE_BASE/.tmp-$DEPLOY_ID && rm -rf ${HPATHS[*]} && cd - > /dev/null && tar -x -C $REMOTE_BASE/.tmp-$DEPLOY_ID && touch $REMOTE_BASE/.tmp-$DEPLOY_ID/.deployed && rm -rf $REMOTE_BASE/$DEPLOY_ID && mv $REMOTE_BASE/.tmp-$DEPLOY_ID $REMOTE_BASE/$DEPLOY_ID"; } \
    || abort16 deploy-failed
else deploy_sha "$SHA" < /dev/null || abort16 deploy-failed; fi
compose_down14 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || abort16 compose-down-failed
[[ "$(image_id "seatlab-app:$APP_TAG")" == "$APP_ID" ]] || abort16 cond-image-mismatch
for img in $(jq -r '.aux | keys[]' "$IMAGES"); do [[ "$(image_id "$img")" == "$(jq -r --arg k "$img" '.aux[$k]' "$IMAGES")" ]] || abort16 aux-image-mismatch; done
compose_up14 "$DEPLOY_ID" >> "$DIR/compose.log" 2>&1 < /dev/null || abort16 compose-up-failed
wait_health 240 || abort16 unhealthy
[[ "$(ctr_image seatlab-app-1)" == "$APP_ID" ]] || abort16 cond-image-mismatch
remote "docker ps --filter label=com.docker.compose.project=seatlab --filter label=com.docker.compose.service=app --format '{{.Names}}'" < /dev/null > "$DIR/app-instances.txt" 2>>"$DIR/errors.log"
[[ "$(grep -c . "$DIR/app-instances.txt")" == 1 ]] || abort16 app-instances-not-one
reset_with_retry "$DIR" 2 10000 > /dev/null || abort16 reset-failed
BASE_URL="$BASE_URL" PAY_START=1 "$K6_BIN" run --quiet --out "csv=$DIR/warmup-requests.csv.gz" "$SCEN15/warmup15.js" > "$DIR/warmup.log" 2>&1 < /dev/null
WARM_RC=$?
python3 -I "$S14/summarize.py" --warmup-status "$DIR/warmup-requests.csv.gz" "$WARM_RC" > "$DIR/warmup-status.json" 2>>"$DIR/errors.log"
(( WARM_RC == 0 )) || abort16 warmup-failed
if (( $(P '.probe16.holds // 0') > 0 )); then probe16 "$DIR" < /dev/null 2>>"$DIR/errors.log" || { rm -f "$DIR/probe16.json"; hreason probe16-failed; }; fi
RESET_TIMEOUT=1800 reset_with_retry "$DIR" "$SEED_S" "$SEED_N" > "$DIR/seed.json" || abort16 seed-failed
SEED_T=$(now)
seed_check15 "$SEED_S" "$SEED_N" > "$DIR/seed-check.json" 2>>"$DIR/errors.log" < /dev/null || hreason seed-check-failed
db_state > "$DIR/db-state-seed.json" 2>>"$DIR/errors.log" < /dev/null
app_config15 "$DIR" 2>>"$DIR/errors.log" < /dev/null || hreason app-config-failed
limit_key_check seatlab-app-1 > "$DIR/limit-key.json" 2>>"$DIR/errors.log" < /dev/null || hreason limit-key-check-failed
pg_settings > "$DIR/pg-settings.json" 2>>"$DIR/errors.log" < /dev/null
bash "$S14/pccheck14.sh" > "$DIR/pc-check.json" 2>>"$DIR/errors.log" < /dev/null || echo "$(date -Is) pc-check: PC 다른 부하 — $(jq -c .why "$DIR/pc-check.json" 2>/dev/null)" >> "$DIR/errors.log"
pgss_prepare > "$DIR/pgss-prepare.json" 2>>"$DIR/errors.log" < /dev/null || hreason pgss-failed

# ---- 표본기(상태 · H4 표본 없음 — kind=cost) + PC TIME_WAIT -----------------------------------------------------------------------------
TAG16="$(echo "$CID" | tr '/' '_')-$CELL-$CONFIG-rep$REP-$(date +%Y%m%d%H%M%S)-$RANDOM"
SAMPLER_COPY="$REMOTE_BASE/$DEPLOY_ID/.samples/$TAG16.jsonl"
POLL_PIDS="$(start_pollers14 "$DIR" cost 1 | xargs) $(tw_sampler "$DIR")"
for _ in $(seq 1 30); do [[ -s "$DIR/timeline-locks.jsonl" ]] && break; sleep 0.5; done
[[ -s "$DIR/timeline-locks.jsonl" ]] || echo "$(date -Is) samples-before-load timeout: timeline-locks.jsonl 15s 동안 비어 있음" >> "$DIR/errors.log"
hold_left=$(awk -v s="$SEED_T" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { d = s + h - n; printf "%.3f", (d > 0 ? d : 0) }')
awk -v s="$SEED_T" -v h="$PRE_K6_HOLD_S" -v n="$(now)" 'BEGIN { exit !(n - s > h + 1) }' && abort16 pre-k6-overrun
sleep "$hold_left"
io_snapshot > "$DIR/io-before.json" 2>>"$DIR/errors.log" < /dev/null
table_stats > "$DIR/table-before.json" 2>>"$DIR/errors.log" < /dev/null || hreason table-stats-failed
drift_snap app > "$DIR/drift-before.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-snap-failed
snap16 before
pgss_prepare > "$DIR/pgss-reset.json" 2>>"$DIR/errors.log" < /dev/null || hreason pgss-failed
app_db_clock > "$DIR/app-db-clock-before.json" 2>>"$DIR/errors.log" < /dev/null
awk -v n="$(now)" -v b="$SEED_T" -v t="$PRE_K6_TARGET_S" -v l="$PRE_K6_TOL_S" -v lead="$PRE_K6_LEAD_S" 'BEGIN { exit !((n - b) + lead > t + l) }' && abort16 pre-k6-overrun
probes_start14 "$DIR" "$(( $(P '.k6.est_s') + 180 ))"
topup=$(awk -v t="$PRE_K6_TARGET_S" -v n="$(now)" -v b="$SEED_T" -v lead="$PRE_K6_LEAD_S" 'BEGIN { d = t - lead - (n - b); printf "%.3f", (d > 0 ? d : 0) }')
sleep "$topup"

# ---- k6 -----------------------------------------------------------------------------------------------------------------------------
CLK_BEFORE=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
# 시계 차를 못 읽으면 0으로 채우지 않고 회차를 시작하지 않는다(리뷰 L1-13)
OFF_MS=$(jq -r 'if (.offset_s | type) == "number" then (.offset_s * 1000 | round) else "" end' <<< "${CLK_BEFORE:-null}" 2>/dev/null)
[[ "$OFF_MS" =~ ^-?[0-9]+$ ]] || abort16 clock-offset-bad
# ⓒ 마감 계산 err(ms) = 직전 표본 왕복/2 올림 + 여유(plan16 CLOCK_ERR_MARGIN_MS). 표본이 없으면 시계 차를 모른다 → 회차를 시작하지 않는다(0으로 채우지 않음)
ERR_MS=$(jq -r --argjson m "$(P .k6.env.CLOCK_ERR_MARGIN_MS)" 'if (.rtt_s | type) == "number" then ((.rtt_s * 500 | ceil) + $m) else "" end' <<< "${CLK_BEFORE:-null}" 2>/dev/null)
[[ "$ERR_MS" =~ ^[0-9]+$ ]] || abort16 clock-offset-bad
K6_STARTED=$(date -Is); K6_T0=$(now)
( sleep "$(awk -v k="$K6_T0" -v w="$(P '.window.from_s + .window.len_s')" -v l="$WEND_LAG_S" -v lead="$PRE_K6_LEAD_S" -v n="$(now)" 'BEGIN { d = k + lead + w + l - n; printf "%.3f", (d > 0 ? d : 0) }')"
  snap16 window-end; hold_rows > "$DIR/hold-rows-wend.json" 2>>"$DIR/errors.log" ) < /dev/null &
WEND_PID=$!
jq -n --argjson seed "$SEED_T" --argjson k6 "$K6_T0" --argjson hold "$PRE_K6_HOLD_S" --argjson target "$PRE_K6_TARGET_S" --argjson tol "$PRE_K6_TOL_S" --argjson topup "$topup" \
      --argjson lead "$PRE_K6_LEAD_S" --argjson pvus "$(P .k6.pre_vus_total)" \
  '{seed_done_epoch: $seed, k6_launch_epoch: $k6, seed_to_launch_s: (($k6 - $seed) * 1000 | round / 1000), hold_s: $hold, target_s: $target, tol_s: $tol, topup_s: $topup,
    lead_s: $lead, pre_vus_total: $pvus}' > "$DIR/pre-k6.json"
env "${K6_ENV_Q[@]}" CLOCK_OFFSET_MS="$OFF_MS" CLOCK_ERR_MS="$ERR_MS" BASE_URL="$BASE_URL" OUT_DIR="$DIR" \
  "$K6_BIN" run --quiet --log-format=json --console-output="$DIR/sessions16.log" --out "csv=$DIR/k6-requests.csv.gz" "$K6_TREE/$K6_SCRIPT" > "$DIR/k6-stdout.log" 2>&1 < /dev/null
K6_EXIT=$?; K6_END=$(now)
(( K6_EXIT == 0 )) || kill "$WEND_PID" 2>/dev/null
wait "$WEND_PID" || hreason app-snap-failed; WEND_PID=""
jq -n --arg s "$K6_STARTED" --arg e "$(date -Is)" --argjson t0 "$K6_T0" --argjson t1 "$K6_END" --argjson rc "$K6_EXIT" --argjson off "$OFF_MS" --argjson err "$ERR_MS" \
  '{k6_started: $s, k6_ended: $e, k6_t0_epoch: $t0, k6_end_epoch: $t1, exit: $rc, clock_offset_ms_given: $off, clock_err_ms_given: $err}' > "$DIR/k6-window.json"
(( K6_EXIT == 0 )) || hreason "k6-exit-$K6_EXIT"
[[ -f "$DIR/sessions16.log" ]] && gzip -f "$DIR/sessions16.log"
probes_stop14 "$DIR" "$K6_T0" "$K6_END"
CLK_AFTER=$(clock_offset 2>>"$DIR/errors.log" < /dev/null)
jq -n --argjson b "${CLK_BEFORE:-null}" --argjson a "${CLK_AFTER:-null}" '{before: $b, after: $a}' > "$DIR/clock-offset.json" 2>/dev/null || echo '{"before":null,"after":null}' > "$DIR/clock-offset.json"

# ---- 직후 · 수렴 · 끝 판정 --------------------------------------------------------------------------------------------------------------
sleep "$(awk -v e="$K6_END" -v w="$IDLE_AFTER_K6_S" -v n="$(now)" 'BEGIN { d = e + w - n; print (d > 0 ? d : 0) }')"
table_stats > "$DIR/table-after.json" 2>>"$DIR/errors.log" < /dev/null || hreason table-stats-failed
io_snapshot > "$DIR/io-after.json" 2>>"$DIR/errors.log" < /dev/null
drift_snap app > "$DIR/drift-after-k6.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-snap-failed
snap16 after-k6
pgss_snapshot > "$DIR/pgss-after-k6.json" 2>>"$DIR/errors.log" < /dev/null || hreason pgss-failed
CONV=$(P .converge_s)
sleep "$(awk -v e="$K6_END" -v c="$CONV" -v n="$(now)" 'BEGIN { d = e + c - n; print (d > 0 ? d : 0) }')"
table_stats > "$DIR/table-end.json" 2>>"$DIR/errors.log" < /dev/null || hreason table-stats-failed
CONSISTENCY_TIMEOUT=600 consistency 20 > "$DIR/consistency.json" 2>>"$DIR/errors.log" < /dev/null; crc=$?
(( crc == 0 )) || { rm -f "$DIR/consistency.json"; echo "$crc" > "$DIR/consistency.rc"; hreason "end-check-failed"; }
reservation_dump > "$DIR/reservations.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/reservations.json"; hreason reservation-dump-failed; }
drift_snap app > "$DIR/drift-end.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-snap-failed
snap16 end
pgss_snapshot > "$DIR/pgss-end.json" 2>>"$DIR/errors.log" < /dev/null || hreason pgss-failed
pgss16_writes > "$DIR/pgss16-end.json" 2>>"$DIR/errors.log" < /dev/null || { rm -f "$DIR/pgss16-end.json"; hreason pgss-failed; }
drift_db_dump v10 > "$DIR/drift-db.json" 2>>"$DIR/errors.log" < /dev/null || hreason drift-db-failed
app_db_clock > "$DIR/app-db-clock-after.json" 2>>"$DIR/errors.log" < /dev/null

# ---- 수집 · 정리 ------------------------------------------------------------------------------------------------------------------------
stop_pollers14 $POLL_PIDS; POLL_PIDS=""
remote "pkill -f 'FROM pg_locks WHERE NOT granted' || true" > /dev/null 2>&1 < /dev/null
if remote "cat '$SAMPLER_COPY'" > "$DIR/.sampler-remote.jsonl" 2>/dev/null < /dev/null && [[ -s "$DIR/.sampler-remote.jsonl" ]]; then
  nl=$(wc -l < "$DIR/timeline-locks.jsonl" 2>/dev/null || echo 0); nw=$(wc -l < "$DIR/timeline-waits.jsonl" 2>/dev/null || echo 0)
  rl=$(grep -c '"lock_waiting"' "$DIR/.sampler-remote.jsonl"); rw=$(grep -c '"waits"' "$DIR/.sampler-remote.jsonl")
  if (( rl > nl || rw > nw )); then grep '"lock_waiting"' "$DIR/.sampler-remote.jsonl" > "$DIR/timeline-locks.jsonl"; grep '"waits"' "$DIR/.sampler-remote.jsonl" > "$DIR/timeline-waits.jsonl"
    echo "$(date -Is) waits recovered-from-server locks $nl→$rl waits $nw→$rw" >> "$DIR/sampler-recovery.txt"
  else echo "$(date -Is) waits stream-complete" >> "$DIR/sampler-recovery.txt"; fi
else echo "$(date -Is) waits server-copy-unavailable" >> "$DIR/sampler-recovery.txt"; fi
rm -f "$DIR/.sampler-remote.jsonl"; remote "rm -f '$SAMPLER_COPY'" > /dev/null 2>&1 < /dev/null
for c in app db; do
  remote "docker logs --since '$STARTED' seatlab-$c-1 2>&1" < /dev/null | gzip > "$DIR/$c.log.gz"
  echo "$c pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]} bytes=$(zcat "$DIR/$c.log.gz" 2>/dev/null | wc -c)" >> "$DIR/logs-status.txt"
done
remote "docker exec seatlab-app-1 cat /dev/shm/gc.log" < /dev/null | gzip > "$DIR/gc.log.gz"
echo "app pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]} bytes=$(zcat "$DIR/gc.log.gz" 2>/dev/null | wc -c)" >> "$DIR/gc-status.txt"
python3 "$LIB9_DIR/summarize.py" --gc "$DIR" > "$DIR/gc.json" 2>>"$DIR/errors.log" || echo "gc summary failed" >> "$DIR/errors.log"
python3 -I "$LIB9_DIR/tail.py" --probe-check "$DIR" > /dev/null 2>>"$DIR/errors.log"
gzip -f "$DIR/k6-stdout.log"
finish16
