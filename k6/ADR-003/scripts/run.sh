#!/usr/bin/env bash
# ADR-003 측정 실행기 (ADR-002 실행기 + 조건 축: 경합 제어 전략·풀 크기·앱 대수). k6 PC에서 실행. 측정 대상 = 로컬 커밋 SHA 하나 — 서버의 앱과 k6 시나리오 모두 그 커밋에서 꺼낸다.
# 인덱스는 V2 고정(ADR-002 기준선), 배경 0.
#
#   scripts/run.sh --sha <SHA> [--strategy none] [--pool 10] [--apps 1|2] [--levels "2 4"] [--reps 5] [--cells "S1 S4 S3"] [--id <matrix-id>] [--only-rep k]
#   scripts/run.sh --sha <SHA> --id <matrix-id> --resume [--only-rep k]   # 이어서 (계획은 기존 plan.json)
#   --only-rep k: 계획의 회차 중 k번째만 잰다 — 캠페인이 회차 단위로 전 조건을 한 바퀴씩 돌릴 때(조건 간 드리프트 분산)
#
# 결과: results/<matrix-id>/L<level>/<cell>/rep<k>/ 에 회차별 원시 결과 전부.
# 계획(plan.json)을 먼저 남기고, 성공·실패 모든 회차를 MATRIX.log 한 줄 + 회차 폴더 status/meta로 기록한다 (무음 스킵 금지).
set -uo pipefail

# 스크립트 동결: bash는 실행 중에도 파일을 이어 읽는다 — 장시간 매트릭스 도중 작업트리를 고치면 실행이 깨진다(실측).
# 시작하자마자 scripts/ 를 임시 위치로 복사해 그 사본으로 다시 실행한다.
if [[ "${RUN_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"
  frozen="$(mktemp -d)"
  cp -r "$src" "$frozen/scripts"
  # 캠페인(이미 동결된 사본)에서 불렸으면 원래 위치를 이어받는다 — 결과가 임시 폴더로 가지 않게
  export RUN_FROZEN=1 FROZEN_DIR="$frozen" ADR_DIR="${ADR_DIR:-$(cd "$src/.." && pwd)}"
  export REPO_ROOT="${REPO_ROOT:-$(cd "$ADR_DIR/../.." && pwd)}"
  exec bash "$frozen/scripts/run.sh" "$@"
fi
source "$(dirname "$0")/lib.sh"
set +e   # 한 회차 실패가 매트릭스 전체를 멈추지 않게 — 실패는 status로 기록한다

SHA="" LEVELS="2 4" REPS=5 CELLS="S1 S4 S3" MATRIX_ID="" RESUME=0 ONLY_REP=""
STRATEGY=none POOL=10 APPS=1   # ADR-003 조건 축
COND_CLI=""             # 명령줄로 준 조건 — 이어서 실행 시 계획과 다르면 거부
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --levels) LEVELS="$2"; shift 2 ;;
    --reps) REPS="$2"; shift 2 ;;
    --cells) CELLS="$2"; shift 2 ;;
    --id) MATRIX_ID="$2"; shift 2 ;;
    --resume) RESUME=1; shift ;;
    --strategy) STRATEGY="$2"; COND_CLI+=" strategy"; shift 2 ;;
    --pool) POOL="$2"; COND_CLI+=" pool"; shift 2 ;;
    --apps) APPS="$2"; COND_CLI+=" apps"; shift 2 ;;
    --only-rep) ONLY_REP="$2"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
STRATEGIES="none jvm-lock jvm-lock-in-tx conditional-update pessimistic pessimistic-nowait optimistic unique advisory redis-nx redis-lock"
[[ " $STRATEGIES " == *" $STRATEGY "* ]] || { echo "--strategy one of: $STRATEGIES" >&2; exit 2; }
[[ "$APPS" == 1 || "$APPS" == 2 ]] || { echo "--apps 1|2" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
# 실행하는 하네스 = 기록되는 SHA: 작업트리의 k6/ADR-003(results 제외)이 SHA와 다르면 거부한다.
# 캠페인에서 불렸으면 캠페인이 시작 때 이미 확인했다 — 이틀 도는 동안 작업트리를 고쳐도 남은 조건이 멈추지 않게 건너뛴다.
if [[ "${CAMPAIGN_FROZEN:-0}" != 1 ]] && { ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-003 ':(exclude)k6/ADR-003/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-003 ':(exclude)k6/ADR-003/results')" ]]; }; then
  echo "k6/ADR-003 differs from $SHA — commit first so the harness that runs is the one recorded" >&2; exit 2
fi
MATRIX_ID="${MATRIX_ID:-$(date +%Y%m%d-%H%M%S)-$SHA}"
OUT_ROOT="$ADR_DIR/results/$MATRIX_ID"
MATRIX_LOG="$OUT_ROOT/MATRIX.log"
if (( RESUME )); then
  # 이어서 실행: 계획·셀·회차는 기존 plan.json 그대로. 측정 대상(앱·시나리오·compose)이 계획의 SHA와 같을 때만 허용한다
  [[ -f "$OUT_ROOT/plan.json" ]] || { echo "resume: $OUT_ROOT/plan.json 없음" >&2; exit 2; }
  PLAN_SHA="$(jq -r .sha "$OUT_ROOT/plan.json")"
  if ! git -C "$REPO_ROOT" diff --quiet "$PLAN_SHA" "$SHA" -- src build.gradle.kts Dockerfile .dockerignore k6/ADR-003/scenarios k6/ADR-003/compose.yml k6/ADR-003/compose.two-apps.yml k6/ADR-003/nginx.conf; then
    echo "resume: 측정 대상이 계획($PLAN_SHA)과 다르다 — 이어서 잴 수 없음" >&2; exit 2
  fi
  LEVELS="$(jq -r '.levels|join(" ")' "$OUT_ROOT/plan.json")"; REPS="$(jq -r .reps "$OUT_ROOT/plan.json")"
  # 조건도 계획에서 복원한다 — 명령줄로 다른 값을 주면 거부(다른 조건이 한 결과로 섞이지 않게)
  P_STRATEGY="$(jq -r .condition.strategy "$OUT_ROOT/plan.json")"; P_POOL="$(jq -r .condition.pool "$OUT_ROOT/plan.json")"; P_APPS="$(jq -r .condition.apps "$OUT_ROOT/plan.json")"
  for k in $COND_CLI; do
    case "$k" in
      strategy) [[ "$STRATEGY" == "$P_STRATEGY" ]] || { echo "resume: --strategy $STRATEGY ≠ 계획 $P_STRATEGY" >&2; exit 2; } ;;
      pool)     [[ "$POOL" == "$P_POOL" ]] || { echo "resume: --pool $POOL ≠ 계획 $P_POOL" >&2; exit 2; } ;;
      apps)     [[ "$APPS" == "$P_APPS" ]] || { echo "resume: --apps $APPS ≠ 계획 $P_APPS" >&2; exit 2; } ;;
    esac
  done
  STRATEGY="$P_STRATEGY" POOL="$P_POOL" APPS="$P_APPS"
  RESUME_CELLS="$(jq -r '.cells|join(" ")' "$OUT_ROOT/plan.json")"
  echo "$(date -Is) RESUME sha=$SHA plan_sha=$PLAN_SHA (앱·시나리오·compose 동일 확인)" >> "$MATRIX_LOG"
else
  # 기존 결과를 덮어쓰지 않는다 — 근거 보존
  [[ -e "$OUT_ROOT" ]] && { echo "results/$MATRIX_ID already exists — refuse to overwrite" >&2; exit 2; }
  mkdir -p "$OUT_ROOT"
fi
SUMMARIZE="$(dirname "$0")/summarize.py"   # 동결 사본

# k6 시나리오도 측정 SHA에서 꺼낸다 — 작업트리가 바뀌어도 기록된 SHA로 재현된다
# 유니크 전략만 V4(좌석당 홀드 유니크)를 붙인다 — 앱 기동 검사가 전략↔인덱스 짝을 강제하고, 회차 판정이 실제 인덱스를 다시 대조한다
FLYWAY_LOCATIONS=$([[ "$STRATEGY" == unique ]] && echo "classpath:db/migration,classpath:db/migration-unique" || echo "classpath:db/migration")
EXPECTED_INDEXES="idx_reservation_schedule_user,idx_reservation_seat_id,idx_seat_hold_expires_at,idx_seat_hold_seat_id,idx_seat_hold_user_id"
[[ "$STRATEGY" == unique ]] && EXPECTED_INDEXES="idx_reservation_schedule_user,idx_reservation_seat_id,idx_seat_hold_expires_at,idx_seat_hold_seat_id,idx_seat_hold_user_id,uq_seat_hold_seat_id"
# 앱 2대: nginx 오버레이. 앱마다 CPU를 단계의 절반씩(L4 = 2 + 2) — compose_up/down이 이 변수를 쓴다
(( APPS == 2 )) && export COMPOSE_EXTRA="-f k6/ADR-003/compose.two-apps.yml"

K6_TREE="$(mktemp -d)"
# 정리는 이 실행이 만든 임시 경로만 — 동결 사본은 mktemp 경로일 때만 지운다 (작업트리 삭제 방지)
cleanup() {
  rm -rf "$K6_TREE"
  [[ -n "${FROZEN_DIR:-}" && "$FROZEN_DIR" == "${TMPDIR:-/tmp}"/tmp.* && -d "$FROZEN_DIR/scripts" ]] && rm -rf "$FROZEN_DIR"
}
trap cleanup EXIT
git -C "$REPO_ROOT" archive "$SHA" k6/ADR-003/scenarios | tar -x -C "$K6_TREE" \
  || { echo "cannot extract scenarios from $SHA" >&2; exit 2; }
SCENARIOS="$K6_TREE/k6/ADR-003/scenarios"

# ---- 셀 정의 ----------------------------------------------------------------------------------------
# 앱 설정 그룹. S4는 측정 시간(약 7분)보다 긴 TTL로 만료 배치를 배제한다 — 배치가 처리량 측정에 끼어들지 않게.
config_of() { case "$1" in S3A|S3B) echo scaled ;; S4) echo s4 ;; *) echo base ;; esac; }
config_env() {   # + ADR-003 조건(전략·풀)은 모든 그룹에 공통으로 붙는다
  local cond="HOLD_STRATEGY=$STRATEGY FLYWAY_LOCATIONS=$FLYWAY_LOCATIONS POOL_SIZE=$POOL"
  case "$1" in
    base)   echo "HOLD_TTL=5m EXPIRY_INTERVAL=10s $cond" ;;
    scaled) echo "HOLD_TTL=30s EXPIRY_INTERVAL=1s $cond" ;;
    s4)     echo "HOLD_TTL=60m EXPIRY_INTERVAL=10s $cond" ;;
  esac
}
ttl_s_of()   { case "$(config_of "$1")" in scaled) echo 30 ;; s4) echo 3600 ;; *) echo 300 ;; esac; }
grace_of()   { case "$(config_of "$1")" in scaled) echo 2 ;; *) echo 20 ;; esac; }   # 배치 주기 × 2
seed_of()    { case "$1" in S4) echo "200 10000 0" ;; *) echo "1 10000 0" ;; esac; }   # 배경 0. S4는 200만 석 — 16단계 누적 요청(약 197만)이 시드를 넘으면 없는 좌석(404)이 성공률을 깎는다
script_of() {
  case "$1" in
    S1) echo s1-same-seat.js ;; S2) echo s2-same-user.js ;; S4) echo s4-throughput.js ;;
    S3|S3A|S3B) echo s3-full-flow.js ;;
  esac
}
# S3 변형 × 이탈률은 셀 이름에 붙인다: S3-a0 / S3-a20 / S3-a50
expand_cells() {
  if [[ -n "${RESUME_CELLS:-}" ]]; then printf '%s\n' $RESUME_CELLS; return; fi
  for c in $CELLS; do
    case "$c" in S3|S3A|S3B) for a in 0 20 50; do echo "$c-a$a"; done ;; *) echo "$c" ;; esac
  done
}
k6_env_of() {  # 시나리오 파라미터는 기본값에 기대지 않고 전부 명시한다 (meta.json에 그대로 남는다)
  local cell="$1" base="${1%-a*}" abandon=0
  [[ "$cell" == *-a* ]] && abandon="${cell##*-a}"
  local ab; ab=$(awk "BEGIN{print $abandon/100}")
  case "$base" in
    S1)  echo "VUS=1000 SEAT_ID=1" ;;
    S2)  echo "USERS=100 PER_USER=10" ;;
    S3)  echo "RATE=300 TOTAL=200000 SEATS=10000 RETRIES=3 THINK_MIN_S=30 THINK_MAX_S=240 TAIL_S=360 ABANDON=$ab" ;;
    S3A) echo "RATE=300 TOTAL=20000 SEATS=10000 RETRIES=3 THINK_MIN_S=3 THINK_MAX_S=24 TAIL_S=36 ABANDON=$ab" ;;
    S3B) echo "RATE=3000 TOTAL=200000 SEATS=10000 RETRIES=3 THINK_MIN_S=3 THINK_MAX_S=24 TAIL_S=36 ABANDON=$ab" ;;
    S4)  echo "START_RATE=50 FACTOR=1.5 STEPS=16 STEP_S=30" ;;   # ADR-002 13단계(6,487)에서 한계 미관측 → 16단계(약 2.2만/s)
  esac
}

# ---- 수집 --------------------------------------------------------------------------------------------
snapshot() {  # 인자: 출력폴더 라벨
  local dir="$1" label="$2"
  remote "date -Is; uptime; nproc; free -m; docker stats --no-stream --format '{{json .}}'" > "$dir/server-$label.txt" 2>&1
  { date -Is; uptime; nproc; free -m; } > "$dir/client-$label.txt" 2>&1
}
app_config() {  # 적용된 설정 (비밀번호 등 제외) + JVM이 본 CPU 수 + 풀 크기 + 컨테이너 제한
  local dir="$1" props cpu pool mem containers
  props=$(actuator configprops | jq -c '[.contexts[].beans[]
      | select(.prefix | test("^(seat\\.hold|spring\\.datasource\\.hikari|spring\\.flyway|server)$"))
      | {prefix, properties: (.properties | with_entries(select(.key | test("password|secret"; "i") | not)))}]') || return 1
  cpu=$(actuator metrics/system.cpu.count | jq -c '[.measurements[].value]') || return 1
  pool=$(actuator metrics/hikaricp.connections.max | jq -c '[.measurements[].value]') || return 1
  mem=$(actuator metrics/jvm.memory.max | jq -c '[.measurements[].value]') || return 1
  local names="seatlab-app-1 seatlab-db-1 seatlab-redis-1"; (( APPS == 2 )) && names+=" seatlab-app2-1 seatlab-lb-1"
  containers=$(remote "docker inspect $names --format '{{json .Name}} {{json .HostConfig}}'" | jq -Rsc 'split("\n") | map(select(length>0) | split(" ") | {name: (.[0]|fromjson), cpu: (.[1:]|join(" ")|fromjson|.NanoCpus), mem: (.[1:]|join(" ")|fromjson|.Memory)})') || return 1
  local idx; idx=$(db_indexes) || return 1   # DB에 실제로 있는 인덱스 — 인덱스 조건 적용의 근거
  jq -n --argjson p "$props" --argjson c "$cpu" --argjson h "$pool" --argjson m "$mem" --argjson k "$containers" --argjson i "$idx" \
    '{configprops:$p, "system.cpu.count":$c, "hikaricp.connections.max":$h, "jvm.memory.max":$m, containers:$k, db_indexes:$i}' > "$dir/app-config.json"
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
      p=$(pgrep -x k6 | head -1)
      [[ -n "$p" ]] && echo "{\"t\":\"$(date -Is)\",\"k6_pcpu\":$(ps -o pcpu= -p "$p" | tr -d ' '),\"k6_rss_kb\":$(ps -o rss= -p "$p" | tr -d ' '),\"load\":\"$(cut -d' ' -f1-3 /proc/loadavg)\"}" >> "$dir/timeline-client.jsonl"
      sleep 5
    done ) > /dev/null 2>&1 & echo $!
  # 락 대기(ADR-003 ④): 서버 쪽 루프가 0.5초마다 한 줄 — SSH 하나
  lock_sampler >> "$dir/timeline-locks.jsonl" 2>/dev/null < /dev/null & echo $!
  # 커넥션 풀 대기: 앱의 Hikari pending/active, 1초마다(앱 2대면 nginx가 번갈아 보낸다 — 어느 앱인지는 기록하지 않음, 편향)
  ( while :; do
      pend=$(curl -fsS --max-time 2 "$BASE_URL/actuator/metrics/hikaricp.connections.pending" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null)
      act=$(curl -fsS --max-time 2 "$BASE_URL/actuator/metrics/hikaricp.connections.active" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null)
      [[ -n "$pend" && -n "$act" ]] && echo "{\"t\":\"$(date -Is)\",\"pending\":$pend,\"active\":$act}" >> "$dir/timeline-hikari.jsonl"
      sleep 1
    done ) > /dev/null 2>&1 & echo $!
  case "$cell" in
    S3*)   # 좌석 상태 곡선 — DB 직접 가벼운 집계(전체 판정은 회차 끝 1회). poll_ms = ssh 포함 왕복 (간섭 판단용)
      ( while :; do
          t0=$(date +%s%N); j=$(db_counts true); t1=$(date +%s%N)
          [[ -n "$j" ]] && echo "$j" | jq -c --arg t "$(date -Is)" --argjson ms $(( (t1 - t0) / 1000000 )) '. + {t: $t, poll_ms: $ms}' >> "$dir/timeline-db.jsonl"
          sleep 5
        done ) > /dev/null 2>&1 & echo $! ;;
    S4)    # 누적 홀드 수만 — DB 직접 (100만 석 전체 판정은 무거워 부하에 간섭한다)
      ( while :; do
          t0=$(date +%s%N); j=$(db_counts false); t1=$(date +%s%N)
          [[ -n "$j" ]] && echo "$j" | jq -c --arg t "$(date -Is)" --argjson ms $(( (t1 - t0) / 1000000 )) '. + {t: $t, poll_ms: $ms}' >> "$dir/timeline-db.jsonl"
          sleep 10
        done ) > /dev/null 2>&1 & echo $! ;;
  esac
}

# ---- 회차 실행 ---------------------------------------------------------------------------------------
UNHEALTHY_STREAK=0

run_rep() {  # 인자: level cell rep
  local level="$1" cell="$2" rep="$3" base="${2%-a*}"
  local dir="$OUT_ROOT/L$level/$cell/rep$rep"
  mkdir -p "$dir"
  local started; started=$(date -Is)
  local cfg_env; cfg_env="$(config_env "$(config_of "$base")") APP_CPUS=$(app_cpus "$level") DB_CPUS=$level"
  log "L$level $cell rep$rep → $dir"

  # 회차마다 새 컨테이너 — JVM·커넥션 풀·DB 캐시 상태를 회차 사이에 넘기지 않는다
  compose_down "$SHA" >> "$dir/compose.log" 2>&1
  compose_up "$SHA" $cfg_env >> "$dir/compose.log" 2>&1
  if ! wait_health 180 || ! reset_with_retry "$dir" 1 10000 > /dev/null; then
    UNHEALTHY_STREAK=$((UNHEALTHY_STREAK + 1))
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "unhealthy" -1 ""; return 1
  fi
  UNHEALTHY_STREAK=0
  k6 run --quiet "$SCENARIOS/warmup.js" > "$dir/warmup.log" 2>&1
  local seed; seed=$(seed_of "$base")
  if ! reset_with_retry "$dir" $seed > "$dir/seed.json"; then
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "seed-failed" -1 ""; return 1
  fi

  snapshot "$dir" before
  app_config "$dir" 2>>"$dir/errors.log" || echo "app_config failed" >> "$dir/errors.log"
  local pids; pids=$(start_pollers "$dir" "$cell")

  local k6env; k6env=$(k6_env_of "$cell")
  local dash=()
  [[ "$base" != S1 && "$base" != S2 ]] && dash=(K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_PORT=-1 "K6_WEB_DASHBOARD_EXPORT=$dir/k6-dashboard.html")
  local k6_started; k6_started=$(date -Is)
  env $k6env "${dash[@]}" BASE_URL="$BASE_URL" OUT_DIR="$dir" \
    k6 run --quiet --out "csv=$dir/k6-requests.csv.gz" "$SCENARIOS/$(script_of "$base")" > "$dir/k6-stdout.log" 2>&1
  local k6_exit=$?
  local k6_ended; k6_ended=$(date -Is)

  # S3: 마지막 이탈 홀드가 만료되고 배치가 정리할 때까지 관측을 유지한 뒤 판정한다
  local grace; grace=$(grace_of "$base")
  [[ "$base" == S3* ]] && sleep $(( $(ttl_s_of "$base") + grace + 2 ))
  # 최종 판정은 전체 행을 훑는다(S4는 100만 석) — 폴링보다 긴 상한
  CONSISTENCY_TIMEOUT=600 consistency "$grace" > "$dir/consistency.json" 2>>"$dir/errors.log"
  for p in $pids; do kill "$p" 2>/dev/null; done
  # 서버 쪽 락 표본 루프 정리 — SSH가 끊기면 다음 출력에서 끝나지만, 남지 않게 명시적으로(이 실험의 루프만 맞는 패턴)
  remote "pkill -f 'FROM pg_locks WHERE NOT granted' || true" > /dev/null 2>&1
  snapshot "$dir" after

  # 컨테이너 로그 보존 (이번 회차 구간만) · k6 경고 로그 압축
  remote "docker logs --since '$started' seatlab-app-1 2>&1" | gzip > "$dir/app.log.gz"
  remote "docker logs --since '$started' seatlab-db-1 2>&1" | gzip > "$dir/db.log.gz"
  (( APPS == 2 )) && remote "docker logs --since '$started' seatlab-app2-1 2>&1" | gzip > "$dir/app2.log.gz"
  gzip -f "$dir/k6-stdout.log"

  finish_rep "$dir" "$level" "$cell" "$rep" "$started" "$k6_started|$k6_ended" "$(rep_status "$dir" "$base" "$k6_exit" "$k6_started" "$k6_ended")" "$k6_exit" "$k6env"
}

app_cpus() {  # 앱 1대 = 단계 CPU 전부, 앱 2대 = 절반씩(L4 → 2 + 2)
  if (( APPS == 2 )); then echo $(( $1 / 2 )); else echo "$1"; fi
}

# 회차 산출물 검증 — 사유를 누적한다 (하나라도 있으면 실패)
rep_status() {  # 인자: 폴더 base k6_exit k6_started k6_ended
  local dir="$1" base="$2" k6_exit="$3" k6_started="$4" k6_ended="$5" reasons=()
  if (( k6_exit != 0 )); then
    # S4의 99 = 한계 도달로 스스로 멈춤(정상). 단 성공한 단계가 하나도 없으면 측정 실패다
    if [[ "$base" == S4 && "$k6_exit" -eq 99 ]]; then
      python3 "$SUMMARIZE" --s4-check "$dir/k6-summary.json" || reasons+=("s4-no-successful-stage")
    else
      reasons+=("k6-exit-$k6_exit")
    fi
  fi
  local f
  for f in k6-summary.json consistency.json app-config.json seed.json; do
    { [[ -s "$dir/$f" ]] && jq empty "$dir/$f" 2>/dev/null; } || reasons+=("invalid-$f")
  done
  for f in server-before.txt server-after.txt client-before.txt client-after.txt timeline-server.jsonl; do
    [[ -s "$dir/$f" ]] || reasons+=("missing-$f")
  done
  for f in k6-requests.csv.gz app.log.gz db.log.gz k6-stdout.log.gz; do   # 압축 파일은 무결성까지
    { [[ -s "$dir/$f" ]] && gzip -t "$dir/$f" 2>/dev/null; } || reasons+=("corrupt-or-missing-$f")
  done
  # 조건이 실제로 적용됐는지 — 풀 크기와 DB의 실제 인덱스 목록을 계획과 대조한다
  if jq empty "$dir/app-config.json" 2>/dev/null; then
    [[ "$(jq -r '."hikaricp.connections.max"[0] // empty | floor' "$dir/app-config.json")" == "$POOL" ]] || reasons+=("pool-mismatch")
    [[ "$(jq -r '[.db_indexes[].name] | sort | join(",")' "$dir/app-config.json")" == "$EXPECTED_INDEXES" ]] || reasons+=("index-mismatch")
    # 적용된 경합 제어 전략 — 설정 바인딩 결과(enum 이름)를 계획과 대조
    [[ "$(jq -r '.configprops[] | select(.prefix=="seat.hold") | .properties.strategy' "$dir/app-config.json")" == "$(echo "$STRATEGY" | tr 'a-z-' 'A-Z_')" ]] || reasons+=("strategy-mismatch")
  fi
  # 측정 중 k6 PC~서버 경로 단절(ADR-002 A2): 서버 지표 수집 공백이 30초를 넘으면 그 회차는 재측정 대상
  python3 "$(dirname "$0")/gapcheck.py" "$dir/timeline-server.jsonl" "$k6_started" "$k6_ended" 30 2>>"$dir/errors.log" || reasons+=("path-gap")
  if [[ "$base" == S3* || "$base" == S4 ]]; then
    [[ -s "$dir/timeline-db.jsonl" ]] || reasons+=("missing-timeline-db.jsonl")
    [[ -s "$dir/k6-dashboard.html" ]] || reasons+=("missing-k6-dashboard.html")
  fi
  if (( ${#reasons[@]} )); then (IFS=,; echo "${reasons[*]}"); else echo ok; fi
}

reset_with_retry() {  # 인자: 회차폴더 schedules seats — 과부하 직후 적체가 풀릴 때까지 재시도
  local dir="$1"; shift
  local i
  for i in 1 2 3 4 5 6; do
    if reset_db "$@"; then echo "$(date -Is) reset ok attempt=$i" >> "$dir/recovery.log"; return 0; fi
    echo "$(date -Is) reset failed attempt=$i" >> "$dir/recovery.log"
    sleep 10
  done
  return 1
}

finish_rep() {  # 인자: 폴더 level cell rep started "k6_started|k6_ended" status k6_exit k6env — 성공·실패 모두 여기서 기록한다
  local dir="$1" level="$2" cell="$3" rep="$4" started="$5" k6_window="$6" status="$7" k6_exit="$8" k6env="$9" base="${3%-a*}"
  echo "$status" > "$dir/status"
  jq -n --arg sha "$SHA" --arg level "$level" --arg cell "$cell" --arg rep "$rep" \
        --arg started "$started" --arg ended "$(date -Is)" \
        --arg k6_started "${k6_window%%|*}" --arg k6_ended "${k6_window##*|}" --arg k6env "$k6env" \
        --arg appenv "$(config_env "$(config_of "$base")") APP_CPUS=$(app_cpus "$level") DB_CPUS=$level" \
        --arg ttl "$(ttl_s_of "$base")" --arg strategy "$STRATEGY" --arg pool "$POOL" --arg apps "$APPS" \
        --arg status "$status" --argjson k6_exit "$k6_exit" --arg k6 "$(k6 version | head -1)" \
        '{sha:$sha, level:($level|tonumber), cell:$cell, rep:($rep|tonumber), started:$started, ended:$ended,
          k6_started:$k6_started, k6_ended:$k6_ended, ttl_s:($ttl|tonumber),
          strategy:$strategy, pool:($pool|tonumber), apps:($apps|tonumber), bg:0,
          k6_env:$k6env, app_env:$appenv, k6_exit:$k6_exit, status:$status, k6_version:$k6}' > "$dir/meta.json"
  echo "$(date -Is) L$level $cell rep$rep $status" >> "$MATRIX_LOG"
}

# ---- 매트릭스 ----------------------------------------------------------------------------------------
# 계획을 먼저 남긴다 — 요약은 이 계획 기준으로 미측정·누락을 표시한다 (이어서 실행이면 기존 계획 유지)
(( RESUME )) || jq -n --arg sha "$SHA" --arg levels "$LEVELS" --argjson reps "$REPS" --arg cells "$(expand_cells | tr '\n' ' ')" \
  --arg strategy "$STRATEGY" --argjson pool "$POOL" --argjson apps "$APPS" \
  '{sha:$sha, levels:($levels|split(" ")|map(select(.!="")|tonumber)), reps:$reps,
    cells:($cells|split(" ")|map(select(.!=""))), condition:{strategy:$strategy, pool:$pool, apps:$apps}, created:(now|todate)}' > "$OUT_ROOT/plan.json"

deploy_sha "$SHA" || { echo "$(date -Is) ABORT deploy-failed" >> "$MATRIX_LOG"; log "deploy failed"; exit 3; }
log "matrix $MATRIX_ID: levels=[$LEVELS] reps=$REPS cells=[$CELLS]"
for level in $LEVELS; do
  for cell in $(expand_cells); do
    for rep in $(seq 1 "$REPS"); do
      [[ -n "$ONLY_REP" && "$rep" != "$ONLY_REP" ]] && continue
      rdir="$OUT_ROOT/L$level/$cell/rep$rep"
      if (( RESUME )); then
        [[ -f "$rdir/status" ]] && continue                     # 끝난 회차(성공·실패 모두 기록됨)는 건너뛴다
        if [[ -d "$rdir" ]]; then                                  # 끝나지 못한 회차는 근거로 옆에 보존하고 다시 잰다
          mv "$rdir" "$rdir.incomplete-$(date +%Y%m%d%H%M%S)"
          echo "$(date -Is) L$level $cell rep$rep incomplete-dir-preserved" >> "$MATRIX_LOG"
        fi
      fi
      run_rep "$level" "$cell" "$rep"
      FIRST_REP_DONE="${FIRST_REP_DONE:-}"
      if [[ -z "$FIRST_REP_DONE" ]] && (( UNHEALTHY_STREAK > 0 )); then   # 첫 회차부터 못 뜨면 빌드·배포 문제 — 즉시 중단
        echo "$(date -Is) ABORT first-rep-unhealthy" >> "$MATRIX_LOG"; log "first rep unhealthy — abort"
        compose_down "$SHA"; exit 4
      fi
      FIRST_REP_DONE=1
      if (( UNHEALTHY_STREAK >= 3 )); then
        echo "$(date -Is) ABORT unhealthy-3-in-a-row" >> "$MATRIX_LOG"; log "3 consecutive unhealthy — abort"
        compose_down "$SHA"; exit 4
      fi
    done
  done
done
compose_down "$SHA"
log "matrix done: $OUT_ROOT"
