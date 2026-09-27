#!/usr/bin/env bash
# ADR-001 측정 실행기 (k6 PC에서 실행). 측정 대상 = 로컬 커밋 SHA 하나.
#
#   scripts/run.sh --sha <SHA> [--levels "1 2 4"] [--reps 5] [--cells "S1 S2 S4 S3A S3B S3"] [--id <matrix-id>]
#
# 결과: results/<matrix-id>/L<level>/<cell>/rep<k>/ 에 회차별 원시 결과 전부.
# 실패한 회차도 폴더와 status 파일을 남긴다 (무음 스킵 금지) — MATRIX.log 에 전 회차 결과가 한 줄씩 쌓인다.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
set +e   # 한 회차 실패가 매트릭스 전체를 멈추지 않게 — 실패는 status로 기록한다

SHA="" LEVELS="1 2 4" REPS=5 CELLS="S1 S2 S4 S3A S3B S3" MATRIX_ID=""
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --levels) LEVELS="$2"; shift 2 ;;
    --reps) REPS="$2"; shift 2 ;;
    --cells) CELLS="$2"; shift 2 ;;
    --id) MATRIX_ID="$2"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")"
MATRIX_ID="${MATRIX_ID:-$(date +%Y%m%d-%H%M)-$SHA}"
OUT_ROOT="$ADR_DIR/results/$MATRIX_ID"
mkdir -p "$OUT_ROOT"
MATRIX_LOG="$OUT_ROOT/MATRIX.log"

# ---- 셀 정의: 앱 설정 그룹 · 시드 · k6 스크립트 · k6 환경 -------------------------------------------
# 앱 설정 그룹 — 같은 그룹이면 컨테이너를 재기동하지 않는다
config_of() { case "$1" in S3A|S3B) echo scaled ;; *) echo base ;; esac; }
config_env() {
  case "$1" in
    base)   echo "HOLD_TTL=5m EXPIRY_INTERVAL=10s" ;;
    scaled) echo "HOLD_TTL=30s EXPIRY_INTERVAL=1s" ;;
  esac
}
seed_of() { case "$1" in S4) echo "100 10000" ;; *) echo "1 10000" ;; esac; }
script_of() {
  case "$1" in
    S1) echo s1-same-seat.js ;; S2) echo s2-same-user.js ;; S4) echo s4-throughput.js ;;
    S3|S3A|S3B) echo s3-full-flow.js ;;
  esac
}
# S3 변형 × 이탈률은 셀 이름에 붙인다: S3-a0 / S3-a20 / S3-a50
expand_cells() {
  for c in $CELLS; do
    case "$c" in S3|S3A|S3B) for a in 0 20 50; do echo "$c-a$a"; done ;; *) echo "$c" ;; esac
  done
}
k6_env_of() {
  local cell="$1" base="${1%-a*}" abandon=0
  [[ "$cell" == *-a* ]] && abandon="${cell##*-a}"
  local ab; ab=$(awk "BEGIN{print $abandon/100}")
  case "$base" in
    S3)  echo "RATE=300 TOTAL=200000 THINK_MIN_S=30 THINK_MAX_S=240 TAIL_S=360 ABANDON=$ab" ;;
    S3A) echo "RATE=300 TOTAL=20000 THINK_MIN_S=3 THINK_MAX_S=24 TAIL_S=36 ABANDON=$ab" ;;
    S3B) echo "RATE=3000 TOTAL=200000 THINK_MIN_S=3 THINK_MAX_S=24 TAIL_S=36 ABANDON=$ab" ;;
    *)   echo "" ;;
  esac
}
grace_of() { case "$(config_of "${1%-a*}")" in scaled) echo 2 ;; *) echo 20 ;; esac; }   # 배치 주기 × 2

# ---- 수집 ----------------------------------------------------------------------------------------
snapshot() {  # 인자: 출력폴더 라벨
  local dir="$1" label="$2"
  remote "date -Is; uptime; nproc; free -m; docker stats --no-stream --format '{{json .}}'" > "$dir/server-$label.txt" 2>&1
  { date -Is; uptime; nproc; free -m; } > "$dir/client-$label.txt" 2>&1
}
app_config() {  # 적용된 설정 (비밀번호 등 제외) + JVM이 본 CPU 수 + 풀 크기
  local dir="$1"
  {
    echo '{"configprops":'
    curl -fsS "$BASE_URL/actuator/configprops" | jq -c '[.contexts[].beans[]
        | select(.prefix | test("^(seat\\.hold|spring\\.datasource\\.hikari|server)$"))
        | {prefix, properties: (.properties | with_entries(select(.key | test("password|secret"; "i") | not)))}]'
    for m in system.cpu.count hikaricp.connections.max jvm.memory.max; do
      echo ",\"$m\":$(curl -fsS "$BASE_URL/actuator/metrics/$m" | jq -c '[.measurements[].value]')"
    done
    echo ',"containers":'
    remote "docker inspect seatlab-app-1 seatlab-db-1 --format '{{json .HostConfig}}'" \
      | jq -sc 'map({NanoCpus, Memory})'
    echo '}'
  } > "$dir/app-config.json" 2>>"$dir/errors.log"
}
start_pollers() {  # 인자: 출력폴더 셀 → 백그라운드 PID들을 출력
  # 수집기의 stdout은 /dev/null — $(...)가 백그라운드 프로세스의 파이프를 기다리며 멈추지 않게
  local dir="$1" cell="$2"
  ( while :; do
      remote "echo \"{\\\"t\\\":\\\"\$(date -Is)\\\",\\\"load\\\":\\\"\$(cut -d' ' -f1-3 /proc/loadavg)\\\"}\"; docker stats --no-stream --format '{{json .}}'" \
        >> "$dir/timeline-server.jsonl" 2>/dev/null
      sleep 10
    done ) > /dev/null 2>&1 & echo $!
  ( while :; do
      local p; p=$(pgrep -x k6 | head -1)
      [[ -n "$p" ]] && echo "{\"t\":\"$(date -Is)\",\"k6_pcpu\":$(ps -o pcpu= -p "$p" | tr -d ' '),\"k6_rss_kb\":$(ps -o rss= -p "$p" | tr -d ' '),\"load\":\"$(cut -d' ' -f1-3 /proc/loadavg)\"}" >> "$dir/timeline-client.jsonl"
      sleep 5
    done ) > /dev/null 2>&1 & echo $!
  if [[ "$cell" == S3* ]]; then   # S3만 DB 상태 시계열 — 만료 경합의 모양
    ( while :; do
        consistency | jq -c --arg t "$(date -Is)" '. + {t: $t}' >> "$dir/timeline-db.jsonl" 2>/dev/null
        sleep 5
      done ) > /dev/null 2>&1 & echo $!
  fi
}

# ---- 회차 실행 -------------------------------------------------------------------------------------
run_rep() {  # 인자: level cell rep
  local level="$1" cell="$2" rep="$3" base="${2%-a*}"
  local dir="$OUT_ROOT/L$level/$cell/rep$rep"
  mkdir -p "$dir"
  local started; started=$(date -Is)
  log "L$level $cell rep$rep → $dir"

  wait_health 120 || { echo "health-failed" > "$dir/status"; return 1; }
  reset_db 1 10000 > /dev/null && \
    k6 run --quiet "$ADR_DIR/scenarios/warmup.js" > "$dir/warmup.log" 2>&1
  local seed; seed=$(seed_of "$base")
  reset_db $seed > "$dir/seed.json" || { echo "seed-failed" > "$dir/status"; return 1; }

  snapshot "$dir" before
  app_config "$dir"
  local pids; pids=$(start_pollers "$dir" "$cell")

  local k6env; k6env=$(k6_env_of "$cell")
  local outputs=()
  case "$base" in
    S1|S2) outputs=(--out "csv=$dir/k6-requests.csv.gz") ;;
    *)     export K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_PORT=-1 K6_WEB_DASHBOARD_EXPORT="$dir/k6-dashboard.html" ;;
  esac
  env $k6env BASE_URL="$BASE_URL" OUT_DIR="$dir" \
    k6 run --quiet "${outputs[@]}" "$ADR_DIR/scenarios/$(script_of "$base")" > "$dir/k6-stdout.log" 2>&1
  local k6_exit=$?
  unset K6_WEB_DASHBOARD K6_WEB_DASHBOARD_PORT K6_WEB_DASHBOARD_EXPORT

  local grace; grace=$(grace_of "$cell")
  [[ "$base" == S3* ]] && sleep $(( grace + 2 ))   # 마지막 만료분이 배치로 정리될 시간
  consistency "$grace" > "$dir/consistency.json" 2>>"$dir/errors.log"
  for p in $pids; do kill "$p" 2>/dev/null; done
  snapshot "$dir" after

  local status=ok
  (( k6_exit != 0 )) && status="k6-exit-$k6_exit"
  [[ -s "$dir/consistency.json" ]] || status="${status/ok/}consistency-missing"
  [[ -s "$dir/k6-summary.json" ]] || status="${status/ok/}summary-missing"
  echo "$status" > "$dir/status"
  jq -n --arg sha "$SHA" --arg level "$level" --arg cell "$cell" --arg rep "$rep" \
        --arg started "$started" --arg ended "$(date -Is)" --arg k6env "$k6env" \
        --arg appenv "$(config_env "$(config_of "$base")") APP_CPUS=$level DB_CPUS=$level" \
        --arg status "$status" --argjson k6_exit "$k6_exit" --arg k6 "$(k6 version | head -1)" \
        '{sha:$sha, level:($level|tonumber), cell:$cell, rep:($rep|tonumber), started:$started, ended:$ended,
          k6_env:$k6env, app_env:$appenv, k6_exit:$k6_exit, status:$status, k6_version:$k6}' > "$dir/meta.json"
  echo "$(date -Is) L$level $cell rep$rep $status" >> "$MATRIX_LOG"
}

# ---- 매트릭스 --------------------------------------------------------------------------------------
deploy_sha "$SHA"
log "matrix $MATRIX_ID: levels=[$LEVELS] reps=$REPS cells=[$CELLS]"
for level in $LEVELS; do
  current=""
  for cell in $(expand_cells); do
    cfg=$(config_of "${cell%-a*}")
    if [[ "$cfg" != "$current" ]]; then
      compose_down "$SHA"
      compose_up "$SHA" APP_CPUS="$level" DB_CPUS="$level" $(config_env "$cfg")
      current="$cfg"
    fi
    for rep in $(seq 1 "$REPS"); do run_rep "$level" "$cell" "$rep"; done
  done
  compose_down "$SHA"
done
log "matrix done: $OUT_ROOT"
