#!/usr/bin/env bash
# ADR-008 측정 실행기 (ADR-007 실행기 복사 — 매수 방식을 주입하지 않고 앱 기본값으로 띄운다 · 기본값 출처 증거 limit-key.json · 회차 합격 판정 summarize.py --accept-check).
#   매수 방식 축 없음: --limit-strategy·--inject-limit-strategy를 받지 않는다. LIMIT_STRATEGY는 '기대 기본값'(counter-upsert-precheck — ADR-008 결정)이고 회차마다 configprops와 대조한다(limit-strategy-mismatch).
#   plan·meta에 limit_source=default. 앱 컨테이너 환경에 매수 방식 키가 있으면 limit-key-present(lib.sh limit_key_check — 환경 전체는 기록하지 않는다).
#   회차 합격(명세 §5): 매수 초과·v_counter_mismatch·억울한 좌석(S7-m1)·명세 순서 위반(S7-m1-late)·빌림 1.0(모든 셀 — 리뷰 루프 1 C1) — 하나라도 어긋나면 그 사유로 ok가 아니다.
# 그 앞: ADR-007 측정 실행기 (ADR-006 실행기 복사 + 매수 방식 counter-upsert-precheck + DB 설정 축 --pg-preset + 대기 이벤트 표본 timeline-waits.jsonl + 고정 도착률 셀 S4c<rate>). k6 PC에서 실행. 측정 대상 = 로컬 커밋 SHA 하나 — 서버의 앱과 k6 시나리오 모두 그 커밋에서 꺼낸다.
# 인덱스는 V2 고정(ADR-002 기준선), 배경 0. 좌석 전략은 pessimistic-nowait(ADR-003 결정 3b) 고정이 기본 — 적용 값은 회차마다 대조한다.
#
#   scripts/run.sh --sha <SHA> [--limit-strategy none] [--pg-preset default] [--strategy pessimistic-nowait] [--pool 10] [--levels "2 4"] [--reps 5] [--cells "S2 S4 S3 S7"] [--id <matrix-id>] [--only-rep k]
#   --pg-preset default|nockpt|buf512 — DB 기동 인자(ADR-007 설정 축, preset_env). 회차마다 실제 값을 SHOW로 읽어 pg-settings.json·meta.json에 남기고 preset과 다르면 status에 사유
#   --pg-preset tmpfs — (ADR-007 후속 디스크 원인) default와 같은 PG 설정 + 데이터 디렉터리를 tmpfs 볼륨에(compose.tmpfs.yml) · DB 메모리 한도 8g. 적용 확인 = pg-storage.json(tmpfs-not-applied)
#   ADR-007 후속: 모든 회차에 호스트 디스크 표본(timeline-host.jsonl 1초 · iostat-host.txt — lib.sh host_sampler)과 pg-storage.json(DB 크기·WAL·저장 위치)이 붙는다
#   셀 S4c4200·S4c2000 = S4 시나리오를 고정 도착률(램프 60초 50/s → 그 값/s 뒤 30초 × 12구간 = 6분, FACTOR=1)로 — 판정은 정상 종료만(한계 규칙 없음)
#   스모크 전용: --worktree — 앱은 <SHA> 그대로, 하네스(k6/ADR-008)는 작업트리에서(커밋 전 실증). meta·plan에 harness=worktree:<해시>로 남는다. 캠페인에서는 거부.
#               --inject-limit-strategy <값> — (--worktree에서만) 앱에 계획과 다른 매수 방식을 주입해 limit-strategy-mismatch 판정을 실증한다
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

SHA="" LEVELS="2 4" REPS=5 CELLS="S2 S4 S3 S7" MATRIX_ID="" RESUME=0 ONLY_REP=""
STRATEGY=pessimistic-nowait POOL=10 APPS=1 DELAY_MS=0   # ADR-003 조건 축 (+ ADR-004 임계 구역 지연) — ADR-005는 좌석 3b 고정
LIMIT_STRATEGY=counter-upsert-precheck   # ADR-008: 주입하지 않는다 — 앱 기본값으로 떠야 하는 값(대조 기준). 축이 아니다
PG_PRESET=default       # ADR-007 조건 축: DB 설정(preset_env)
WORKTREE=0   # 스모크 전용(머리말)
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
    --delay-ms) DELAY_MS="$2"; COND_CLI+=" delay"; shift 2 ;;
    --limit-strategy|--inject-limit-strategy) echo "$1: ADR-008은 매수 방식을 주입하지 않는다(앱 기본값 — 머리말)" >&2; exit 2 ;;
    --pg-preset) PG_PRESET="$2"; COND_CLI+=" pg"; shift 2 ;;
    --worktree) WORKTREE=1; shift ;;
    --only-rep) ONLY_REP="$2"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
STRATEGIES="none jvm-lock jvm-lock-in-tx conditional-update pessimistic pessimistic-nowait optimistic unique advisory redis-nx redis-lock"
[[ " $STRATEGIES " == *" $STRATEGY "* ]] || { echo "--strategy one of: $STRATEGIES" >&2; exit 2; }
[[ "$APPS" == 1 || "$APPS" == 2 ]] || { echo "--apps 1|2" >&2; exit 2; }
[[ "$DELAY_MS" =~ ^[0-9]+$ ]] || { echo "--delay-ms <정수 ms>" >&2; exit 2; }
# (ADR-007의 LIMIT_STRATEGIES 검사 없음 — 매수 방식은 고정 기대값)
# ADR-007 DB 설정 축(명세 §9.2): 세 조건은 서로 독립 — 기본(PostgreSQL 16 기본값) · 체크포인트 없음(간격 1h·WAL 상한 16GB — 회차당 WAL 약 0.75GB라
# 시간·WAL 기준 체크포인트가 측정 중에 돌지 않는다는 가정, 명세 §8 가정 1 — 회차마다 pg_stat_bgwriter 차분·DB 로그로 확인) · 공유 버퍼 512MB.
# 값은 compose db.command의 환경변수로 넘기고(이름 = compose.yml), 대조(rep_status pg-preset-mismatch-*)는 SHOW 형태(current_setting) 그대로 한다.
preset_env() {
  case "$1" in
    default) echo "PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB" ;;
    nockpt)  echo "PG_CHECKPOINT_TIMEOUT=1h PG_MAX_WAL_SIZE=16GB PG_SHARED_BUFFERS=128MB" ;;
    buf512)  echo "PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=512MB" ;;
    # ADR-007 후속(디스크 원인 C5 — 2026-10-10 명세 §2): PG 설정은 default 그대로 + tmpfs 크기·DB 메모리 한도(compose.tmpfs.yml이 읽는다 — 대조 루프는 PG_* 세 키만 본다)
    tmpfs)   echo "PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=6g DB_MEM=8g" ;;
    *) return 1 ;;
  esac
}
preset_env "$PG_PRESET" > /dev/null || { echo "--pg-preset one of: default nockpt buf512 tmpfs" >&2; exit 2; }
if (( WORKTREE )); then
  [[ "${CAMPAIGN_FROZEN:-0}" != 1 ]] || { echo "--worktree는 스모크 전용 — 캠페인에서는 커밋된 하네스만" >&2; exit 2; }
  (( ! RESUME )) || { echo "--worktree와 --resume은 함께 쓸 수 없다(작업트리 하네스는 이어서 잴 근거가 없다)" >&2; exit 2; }
fi
# ADR-008: 앱에 주입하는 매수 방식은 없다(compose에 키가 없다). APPLIED_LIMIT은 meta injected_limit_strategy(늘 null)를 ADR-007 형식대로 남기려고 기대값과 같게 둔다
APPLIED_LIMIT="$LIMIT_STRATEGY"
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
# 실행하는 하네스 = 기록되는 SHA: 작업트리의 k6/ADR-008(results 제외)가 SHA와 다르면 거부한다.
# 캠페인에서 불렸으면 캠페인이 시작 때 이미 확인했다 — 이틀 도는 동안 작업트리를 고쳐도 남은 조건이 멈추지 않게 건너뛴다.
# --worktree(스모크)는 이 검사 대신 하네스 트리 해시를 기록한다.
HARNESS="sha:$SHA" DEPLOY_ID="$SHA"
if (( WORKTREE )); then
  HARNESS="worktree:$(worktree_harness_hash)"; DEPLOY_ID="$SHA-wt-${HARNESS#worktree:}"
elif [[ "${CAMPAIGN_FROZEN:-0}" != 1 ]] && { ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-008 ':(exclude)k6/ADR-008/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-008 ':(exclude)k6/ADR-008/results')" ]]; }; then
  echo "k6/ADR-008 differs from $SHA — commit first so the harness that runs is the one recorded (스모크는 --worktree)" >&2; exit 2
fi
MATRIX_ID="${MATRIX_ID:-$(date +%Y%m%d-%H%M%S)-$SHA}"
OUT_ROOT="$ADR_DIR/results/$MATRIX_ID"
MATRIX_LOG="$OUT_ROOT/MATRIX.log"
if (( RESUME )); then
  # 이어서 실행: 계획·셀·회차는 기존 plan.json 그대로. 측정 대상(앱·시나리오·compose)이 계획의 SHA와 같을 때만 허용한다
  [[ -f "$OUT_ROOT/plan.json" ]] || { echo "resume: $OUT_ROOT/plan.json 없음" >&2; exit 2; }
  PLAN_SHA="$(jq -r .sha "$OUT_ROOT/plan.json")"
  if ! git -C "$REPO_ROOT" diff --quiet "$PLAN_SHA" "$SHA" -- src build.gradle.kts Dockerfile .dockerignore settings.gradle.kts gradle gradlew main-app reconcile-core reconciler-app k6/ADR-008/scenarios k6/ADR-008/compose.yml k6/ADR-008/compose.two-apps.yml k6/ADR-008/compose.tmpfs.yml k6/ADR-008/nginx.conf; then
    echo "resume: 측정 대상이 계획($PLAN_SHA)과 다르다 — 이어서 잴 수 없음" >&2; exit 2
  fi
  LEVELS="$(jq -r '.levels|join(" ")' "$OUT_ROOT/plan.json")"; REPS="$(jq -r .reps "$OUT_ROOT/plan.json")"
  # 조건도 계획에서 복원한다 — 명령줄로 다른 값을 주면 거부(다른 조건이 한 결과로 섞이지 않게)
  P_STRATEGY="$(jq -r .condition.strategy "$OUT_ROOT/plan.json")"; P_POOL="$(jq -r .condition.pool "$OUT_ROOT/plan.json")"; P_APPS="$(jq -r .condition.apps "$OUT_ROOT/plan.json")"
  P_DELAY="$(jq -r '.condition.delay_ms // 0' "$OUT_ROOT/plan.json")"; P_LIMIT="$(jq -r .condition.limit_strategy "$OUT_ROOT/plan.json")"
  P_PG="$(jq -r '.condition.pg_preset // "default"' "$OUT_ROOT/plan.json")"
  for k in $COND_CLI; do
    case "$k" in
      strategy) [[ "$STRATEGY" == "$P_STRATEGY" ]] || { echo "resume: --strategy $STRATEGY ≠ 계획 $P_STRATEGY" >&2; exit 2; } ;;
      pool)     [[ "$POOL" == "$P_POOL" ]] || { echo "resume: --pool $POOL ≠ 계획 $P_POOL" >&2; exit 2; } ;;
      apps)     [[ "$APPS" == "$P_APPS" ]] || { echo "resume: --apps $APPS ≠ 계획 $P_APPS" >&2; exit 2; } ;;
      delay)    [[ "$DELAY_MS" == "$P_DELAY" ]] || { echo "resume: --delay-ms $DELAY_MS ≠ 계획 $P_DELAY" >&2; exit 2; } ;;
      limit)    [[ "$LIMIT_STRATEGY" == "$P_LIMIT" ]] || { echo "resume: --limit-strategy $LIMIT_STRATEGY ≠ 계획 $P_LIMIT" >&2; exit 2; } ;;
      pg)       [[ "$PG_PRESET" == "$P_PG" ]] || { echo "resume: --pg-preset $PG_PRESET ≠ 계획 $P_PG" >&2; exit 2; } ;;
    esac
  done
  STRATEGY="$P_STRATEGY" POOL="$P_POOL" APPS="$P_APPS" DELAY_MS="$P_DELAY" LIMIT_STRATEGY="$P_LIMIT" APPLIED_LIMIT="$P_LIMIT" PG_PRESET="$P_PG"
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
(( APPS == 2 )) && export COMPOSE_EXTRA="-f k6/ADR-008/compose.two-apps.yml"
# ADR-007 후속 tmpfs preset: DB 볼륨을 tmpfs로(오버레이). compose_down은 오버레이 없이도 같은 볼륨(pgdata)을 지운다(down -v)
[[ "$PG_PRESET" == tmpfs ]] && export COMPOSE_EXTRA="${COMPOSE_EXTRA:-} -f k6/ADR-008/compose.tmpfs.yml"

K6_TREE="$(mktemp -d)"
# 정리는 이 실행이 만든 임시 경로만 — 동결 사본은 mktemp 경로일 때만 지운다 (작업트리 삭제 방지)
cleanup() {
  # 이 실행의 최상위 셸에서만 지운다 — 서브셸이 EXIT 트랩을 물려받아 실행 중에 동결 사본을 지우는 일이 없게(ADR-007 스모크: 회차 도중 동결 사본이 사라져
  # gapcheck.py를 못 찾았다 — 원인 미확정, 방어). 누가 언제 지웠는지 남긴다
  [[ "$BASHPID" == "$$" ]] || return 0
  echo "[$(date '+%F %T')] cleanup pid=$BASHPID frozen=${FROZEN_DIR:-} k6_tree=$K6_TREE" >&2
  rm -rf "$K6_TREE"
  [[ -n "${FROZEN_DIR:-}" && "$FROZEN_DIR" == "${TMPDIR:-/tmp}"/tmp.* && -d "$FROZEN_DIR/scripts" ]] && rm -rf "$FROZEN_DIR"
}
trap cleanup EXIT
if (( WORKTREE )); then   # 스모크: 작업트리 시나리오의 사본(실행 중 수정에 흔들리지 않게)
  mkdir -p "$K6_TREE/k6/ADR-008" && cp -r "$REPO_ROOT/k6/ADR-008/scenarios" "$K6_TREE/k6/ADR-008/" \
    || { echo "cannot copy worktree scenarios" >&2; exit 2; }
else
  git -C "$REPO_ROOT" archive "$SHA" k6/ADR-008/scenarios | tar -x -C "$K6_TREE" \
    || { echo "cannot extract scenarios from $SHA" >&2; exit 2; }
fi
SCENARIOS="$K6_TREE/k6/ADR-008/scenarios"

# ---- 셀 정의 ----------------------------------------------------------------------------------------
# 앱 설정 그룹. S4는 측정 시간(약 7분)보다 긴 TTL로 만료 배치를 배제한다 — 배치가 처리량 측정에 끼어들지 않게.
config_of() { case "$1" in S3A|S3B) echo scaled ;; S4|S4c*|S6*) echo s4 ;; *) echo base ;; esac; }   # S6·S4c(고정 도착률)도 측정 중 만료 배치를 배제(TTL 60m)
config_env() {   # + ADR-003 조건(전략·풀)은 모든 그룹에 공통으로 붙는다
  # ADR-007: DB 설정 축(preset_env)도 모든 그룹에 붙는다 — compose db.command가 읽는다(meta.json app_env에 그대로 남는다)
  # ADR-008: SEAT_HOLD_LIMIT_STRATEGY를 넘기지 않는다 — compose가 읽지도 않지만 meta app_env에 거짓 값을 남기지 않게
  local cond="HOLD_STRATEGY=$STRATEGY FLYWAY_LOCATIONS=$FLYWAY_LOCATIONS POOL_SIZE=$POOL CRITICAL_SECTION_DELAY=${DELAY_MS}ms $(preset_env "$PG_PRESET")"
  case "$1" in
    base)   echo "HOLD_TTL=5m EXPIRY_INTERVAL=10s $cond" ;;
    scaled) echo "HOLD_TTL=30s EXPIRY_INTERVAL=1s $cond" ;;
    s4)     echo "HOLD_TTL=60m EXPIRY_INTERVAL=10s $cond" ;;
  esac
}
ttl_s_of()   { case "$(config_of "$1")" in scaled) echo 30 ;; s4) echo 3600 ;; *) echo 300 ;; esac; }
grace_of()   { case "$(config_of "$1")" in scaled) echo 2 ;; *) echo 20 ;; esac; }   # 배치 주기 × 2
seed_of()    { case "$1" in S4|S4c*) echo "200 10000 0" ;; S6*) echo "20 10000 0" ;; *) echo "1 10000 0" ;; esac; }   # S6: 핫 1~100,000 + 이웃 100,001~ (7.5만)   # 배경 0. S4는 200만 석 — 16단계 누적 요청(약 197만)이 시드를 넘으면 없는 좌석(404)이 성공률을 깎는다
script_of() {
  case "$1" in
    S1) echo s1-same-seat.js ;; S2) echo s2-same-user.js ;; S4|S4c*) echo s4-throughput.js ;; S7) echo s7-winner-rollback.js ;;
    S3|S3A|S3B) echo s3-full-flow.js ;;
    S6*) echo s6-contention.js ;;
  esac
}
# 셀 이름 → 시나리오 종류: S3-a20 → S3, S7-m1 → S7(경쟁자 수 변형 — ADR-005 사용자 재합의 2026-10-06)
cell_base() { local b="${1%-a*}"; echo "${b%-m*}"; }
# S3 변형 × 이탈률은 셀 이름에 붙인다: S3-a0 / S3-a20 / S3-a50
expand_cells() {
  if [[ -n "${RESUME_CELLS:-}" ]]; then printf '%s\n' $RESUME_CELLS; return; fi
  for c in $CELLS; do
    case "$c" in S3|S3A|S3B) for a in 0 20 50; do echo "$c-a$a"; done ;; S6) for k in 1 10 100; do echo "S6-k$k"; done ;; *) echo "$c" ;; esac
  done
}
k6_env_of() {  # 시나리오 파라미터는 기본값에 기대지 않고 전부 명시한다 (meta.json에 그대로 남는다)
  local cell="$1" base="$(cell_base "$1")" abandon=0
  [[ "$cell" == *-a* ]] && abandon="${cell##*-a}"
  local ab; ab=$(awk "BEGIN{print $abandon/100}")
  case "$base" in
    S1)  echo "VUS=1000 SEAT_ID=1" ;;
    S2)  echo "USERS=100 PER_USER=10" ;;
    S3)  echo "RATE=300 TOTAL=200000 SEATS=10000 RETRIES=3 THINK_MIN_S=30 THINK_MAX_S=240 TAIL_S=360 ABANDON=$ab" ;;
    S3A) echo "RATE=300 TOTAL=20000 SEATS=10000 RETRIES=3 THINK_MIN_S=3 THINK_MAX_S=24 TAIL_S=36 ABANDON=$ab" ;;
    S3B) echo "RATE=3000 TOTAL=200000 SEATS=10000 RETRIES=3 THINK_MIN_S=3 THINK_MAX_S=24 TAIL_S=36 ABANDON=$ab" ;;
    S4)  echo "START_RATE=50 FACTOR=1.5 STEPS=16 STEP_S=30" ;;   # ADR-002 13단계(6,487)에서 한계 미관측 → 16단계(약 2.2만/s)
    # ADR-007 고정 도착률(명세 §9.2 — 지속성·누적 vs 시각): 같은 시나리오를 FACTOR=1로 — 12구간 × 30초 = 6분, 구간 = stage 태그(요약기 30초 구간표).
    # 리뷰 A13: 앞에 램프 60초(계단 시작 도착률 50/s → 목표, 선형 — stage 태그 'ramp', 구간표·판정·벽 분석 밖)를 둔다. 0초부터 목표를 걸면 기동 과도에 VU가 10,000까지 늘어
    # 6분 내내 request timeout·dial i/o timeout이 남았다(스모크 smoke-b). 좌석 = 반복 번호라 누적 요청 = 램프 (50 + 4,200) ÷ 2 × 60 = 12.75만 + 4,200 × 360 = 151.2만
    # = 163.95만 < 시드 200만 석(seed_of). 에러율 중단(abortOnFail)은 끈다 — 무너진 뒤의 회복·지속을 끝까지 본다
    S4c*) echo "START_RATE=${base#S4c} FACTOR=1 STEPS=12 STEP_S=30 STOP_ON_ERROR=0 RAMP_S=60 RAMP_FROM=50" ;;
    S6-k*) echo "K=${base#S6-k} M=20 START_RATE=200 FACTOR=2 STEPS=5 STEP_S=30 NEIGHBOR_RATE=500" ;;   # 핫 200→3,200/s + 이웃 500/s
    S7)  local m=20 late=0 c="$cell"; [[ "$c" == *-late ]] && { late=1; c="${c%-late}"; }   # ADR-007 S7-m1-late = S7-m1 + U를 R 응답 뒤에(시나리오 LATE)
         [[ "$c" == *-m* ]] && m="${c##*-m}"
         echo "SEATS=100 M=$m LEAD_MS=1 GAP_MS=50 START_DELAY_MS=3000$( ((late)) && echo ' LATE=1')" ;;   # ADR-005 이긴 쪽 롤백 — 설계 근거 README. S7-m1 = 일반 1명(빈 좌석 비율을 직접)
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
limit_keys() {  # 인자: 회차폴더 → limit-key.json = 앱마다 lib.sh limit_key_check 한 줄의 배열(앱 2대면 2개)
  local dir="$1" arr="[]" c line names="seatlab-app-1"
  (( APPS == 2 )) && names+=" seatlab-app2-1"
  for c in $names; do
    line=$(limit_key_check "$c") || return 1
    arr=$(jq -c --argjson x "$line" '. + [$x]' <<< "$arr") || return 1
  done
  echo "$arr" > "$dir/limit-key.json"
}
# 표본 서버 사본(lib.sh db_sampler) — 회차마다 하나, 배포 폴더 아래 .samples/(REMOTE_BASE 밖은 쓰지 않는다)
sampler_copy_of() {  # 인자: level cell rep — 시도마다 다른 이름(시각 + 난수, 리뷰 R2): 재측정·이어서 실행이 옛 사본에 덧붙이지 않게
  echo "$REMOTE_BASE/$DEPLOY_ID/.samples/$(echo "$MATRIX_ID" | tr '/' '_')-L$1-$2-rep$3-$(date +%Y%m%d%H%M%S)-$RANDOM.jsonl"
}
# 회차 끝: 서버 사본을 받아 timeline-locks/waits를 다시 만든다(흘려받은 줄보다 많을 때만 — 흘려받기 공백 복구). 결과는 sampler-recovery.txt에 한 줄,
# 서버 사본은 지운다(서버 디스크 — 회차당 약 1MB). 사본을 못 받으면 흘려받은 파일 그대로(공백은 rep_status waits-gap이 드러낸다)
recover_samples() {  # 인자: 회차폴더
  local dir="$1" tmp="$1/.sampler-remote.jsonl" nl nw rl rw
  if remote "cat '$SAMPLER_COPY'" > "$tmp" 2>/dev/null && [[ -s "$tmp" ]]; then
    nl=$(wc -l < "$dir/timeline-locks.jsonl" 2>/dev/null || echo 0); nw=$(wc -l < "$dir/timeline-waits.jsonl" 2>/dev/null || echo 0)
    rl=$(grep -c '"lock_waiting"' "$tmp"); rw=$(grep -c '"waits"' "$tmp")
    if (( rl > nl || rw > nw )); then
      grep '"lock_waiting"' "$tmp" > "$dir/timeline-locks.jsonl"; grep '"waits"' "$tmp" > "$dir/timeline-waits.jsonl"
      echo "$(date -Is) recovered-from-server locks $nl→$rl waits $nw→$rw" >> "$dir/sampler-recovery.txt"
    else
      echo "$(date -Is) stream-complete locks $nl (server $rl) waits $nw (server $rw)" >> "$dir/sampler-recovery.txt"
    fi
  else
    echo "$(date -Is) server-copy-unavailable" >> "$dir/sampler-recovery.txt"
  fi
  # 서버 사본 지우기(서버 디스크) — 받지 못했어도 남은 것이 있으면 지운다. 지운 뒤에도 남아 있거나 확인이 실패하면 errors.log 한 줄 +
  # sampler-recovery.txt에 sampler-copy-not-removed(rep_status 사유 — 리뷰 R2: 지우기 실패가 무음으로 서버에 쌓이지 않게)
  if ! remote "rm -f '$SAMPLER_COPY' && test ! -e '$SAMPLER_COPY'" > /dev/null 2>&1; then
    echo "$(date -Is) sampler copy not removed: $SAMPLER_COPY" >> "$dir/errors.log"
    echo "$(date -Is) sampler-copy-not-removed $SAMPLER_COPY" >> "$dir/sampler-recovery.txt"
  fi
  rm -f "$tmp"
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
  # 락 대기(ADR-003 ④) + 대기 이벤트(ADR-007): 서버 쪽 루프가 0.5초마다 두 줄 — SSH 하나·psql 호출 하나(lib.sh db_sampler).
  # 줄의 키로 나눠 쓴다(락 = lock_waiting, 대기 이벤트 = waits). 줄마다 바로 파일에 쓴다 — 아래 '표본이 나온 뒤 부하' 대기가 파일 크기를 본다.
  # 받는 쪽은 bash `read` 루프다: bash는 파이프 입력을 한 바이트씩 읽어 줄이 오는 즉시 쓰고, 프로세스가 꺼질 때 버퍼에 남는 줄이 없다.
  # (ADR-007 리뷰 A11: 이전의 awk는 이 PC에서 mawk 1.3.4라 파이프 **입력**을 큰 덩어리로 블록 버퍼링했다 — fflush는 출력만 비운다. 줄이 덩어리째 늦게 오고
  #  회차 끝에 루프를 끄면 마지막 덩어리가 사라졌다 = 스모크의 '흘려받기가 k6 +240~261초에 멈춤'·'S2 0줄'. 0.1초마다 600바이트 줄 재현: mawk 2초 뒤 0줄 · 이 루프 20줄+.
  #  스모크 errors.log에 'db_sampler ended'는 0건 — SSH가 끊긴 것이 아니었다.)
  # SSH가 끝나면 1초 뒤 다시 연다 — 위 원인과 무관한 무해한 보조 장치로 남긴다. 끝날 때마다 errors.log에 한 줄(무음 공백 금지 — 공백이 30초를 넘으면 rep_status waits-gap).
  # 서버 사본 복구(recover_samples)도 보조 장치로 유지한다. 정리는 아래 stop_pollers가 루프와 그 자식(ssh·read 루프)을 함께 끈다
  ( while :; do
      db_sampler "$SAMPLER_COPY" 2>/dev/null < /dev/null \
        | while IFS= read -r line; do
            case "$line" in
              *'"lock_waiting"'*) printf '%s\n' "$line" >> "$dir/timeline-locks.jsonl" ;;
              *'"waits"'*) printf '%s\n' "$line" >> "$dir/timeline-waits.jsonl" ;;
            esac
          done
      echo "$(date -Is) db_sampler ended rc=${PIPESTATUS[0]}/${PIPESTATUS[1]} — reopen in 1s" >> "$dir/errors.log"
      sleep 1
    done ) > /dev/null 2>&1 & echo $!
  # ADR-007 후속 호스트 디스크 표본(lib.sh host_sampler — 서버 1초 루프, 받는 쪽은 위와 같은 bash read 루프: mawk 버퍼링 재발 금지·리뷰 A11).
  # 서버 사본(HOST_COPY)·iostat 사본(HOST_IOSTAT)은 회차 끝 host_recover가 받는다. SSH가 끝나면 1초 뒤 다시 연다(errors.log 한 줄 — 무음 공백 금지)
  ( while :; do
      host_sampler "$HOST_COPY" "$HOST_IOSTAT" 2>/dev/null \
        | while IFS= read -r line; do printf '%s\n' "$line" >> "$dir/timeline-host.jsonl"; done
      echo "$(date -Is) host_sampler ended rc=${PIPESTATUS[0]}/${PIPESTATUS[1]} — reopen in 1s" >> "$dir/errors.log"
      sleep 1
    done ) > /dev/null 2>&1 & echo $!
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
    S4|S4c*|S6*)    # 누적 홀드 수만 — DB 직접 (전체 판정은 무거워 부하에 간섭한다)
      ( while :; do
          t0=$(date +%s%N); j=$(db_counts false); t1=$(date +%s%N)
          [[ -n "$j" ]] && echo "$j" | jq -c --arg t "$(date -Is)" --argjson ms $(( (t1 - t0) / 1000000 )) '. + {t: $t, poll_ms: $ms}' >> "$dir/timeline-db.jsonl"
          sleep 10
        done ) > /dev/null 2>&1 & echo $! ;;
  esac
}

# 수집기 정리: 루프(서브셸)를 먼저 끄고 그 자식(진행 중인 ssh·awk·curl 등)도 끈다 — 루프만 끄면 표본 루프의 ssh·awk가 고아로 남는다(ADR-007 재시작 루프)
stop_pollers() {
  local p kids
  for p in "$@"; do
    kids=$(pgrep -P "$p" 2>/dev/null)
    kill "$p" 2>/dev/null
    [[ -n "$kids" ]] && kill $kids 2>/dev/null
  done
}

# ---- 회차 실행 ---------------------------------------------------------------------------------------
UNHEALTHY_STREAK=0

run_rep() {  # 인자: level cell rep
  local level="$1" cell="$2" rep="$3" base="$(cell_base "$2")"
  local dir="$OUT_ROOT/L$level/$cell/rep$rep"
  mkdir -p "$dir"
  local started; started=$(date -Is)
  [[ -n "$LIMIT_CONFIG" ]] && echo "$LIMIT_CONFIG" > "$dir/limit-config.json"   # ADR-008 설정 파일 정적 검사(회차 전 한 번 — 위) 사본
  local cfg_env; cfg_env="$(config_env "$(config_of "$base")") APP_CPUS=$(app_cpus "$level") DB_CPUS=$level"
  log "L$level $cell rep$rep → $dir"

  # 회차마다 새 컨테이너 — JVM·커넥션 풀·DB 캐시 상태를 회차 사이에 넘기지 않는다
  # ADR-007 후속(리뷰 rv8 A2): 내리기가 끝내 실패하면(DB 볼륨·컨테이너가 남음) 이 회차를 띄우지 않는다 — 이전 회차의 볼륨(tmpfs 포함)을 이어 쓰지 않게
  if ! compose_down "$DEPLOY_ID" >> "$dir/compose.log" 2>&1; then
    UNHEALTHY_STREAK=$((UNHEALTHY_STREAK + 1))
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "compose-down-failed" -1 ""; return 1
  fi
  # 기동 실패를 넘기면 이전 회차의 컨테이너가 남아 health가 UP이 되고 다른 조건의 앱을 재게 된다(스모크 실측) — 실패로 끊는다
  if ! compose_up "$DEPLOY_ID" $cfg_env >> "$dir/compose.log" 2>&1; then
    UNHEALTHY_STREAK=$((UNHEALTHY_STREAK + 1))
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "compose-up-failed" -1 ""; return 1
  fi
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
  local seed_done=$SECONDS

  snapshot "$dir" before
  app_config "$dir" 2>>"$dir/errors.log" || echo "app_config failed" >> "$dir/errors.log"
  # ADR-008 기본값 출처 증거: 앱 컨테이너 환경에 매수 방식 키가 없는가(개수만 — 환경 전체는 남기지 않는다). 실패하면 파일이 없어 rep_status limit-key-check-failed
  limit_keys "$dir" 2>>"$dir/errors.log" || echo "limit_keys failed" >> "$dir/errors.log"
  # ADR-007 DB 설정 실제 값(SHOW) — preset 대조는 rep_status, 기록은 meta.json pg_applied에도
  pg_settings > "$dir/pg-settings.json" 2>>"$dir/errors.log" || echo "pg_settings failed" >> "$dir/errors.log"
  SAMPLER_COPY="$(sampler_copy_of "$level" "$cell" "$rep")"
  HOST_COPY="${SAMPLER_COPY%.jsonl}-host.jsonl" HOST_IOSTAT="${SAMPLER_COPY%.jsonl}-iostat.txt"   # ADR-007 후속 호스트 표본 서버 사본(같은 시도 이름)
  # 표본 장치 = DB 볼륨(Docker 데이터 루트)의 장치 사슬(리뷰 rv8 A4 — lib.sh host_devices). 못 정하면 기본값(HOST_DEVS)으로 돌고 rep_status가 드러낸다
  if host_devices docker > "$dir/host-devices.json" 2>>"$dir/errors.log" && jq -e '.sampled | length > 0' "$dir/host-devices.json" > /dev/null 2>&1; then
    HOST_DEVS="$(jq -r '.sampled | join(" ")' "$dir/host-devices.json")"
  else
    echo "$(date -Is) host_devices failed — sampler uses default devices: $HOST_DEVS" >> "$dir/errors.log"
  fi
  local pids; pids=$(start_pollers "$dir" "$cell")
  # 락 표본이 실제로 나오기 시작한 뒤 부하를 건다 — S1은 2초 안팎이라 표본보다 먼저 끝나면 0만 남는다(리뷰 지적).
  # 15초 안에 안 나오면 그대로 부하를 걸되 errors.log에 한 줄 남긴다(ADR-007 리뷰 A10 — 무음 금지. 짧은 셀은 rep_status waits-none-in-k6가 회차를 실패로 드러낸다)
  local w waited=0; for w in $(seq 1 30); do [[ -s "$dir/timeline-locks.jsonl" ]] && { waited=1; break; }; sleep 0.5; done
  (( waited )) || echo "$(date -Is) samples-before-load timeout: timeline-locks.jsonl 15s 동안 비어 있음 — 표본 없이 부하 시작" >> "$dir/errors.log"
  # ADR-007 후속: 호스트 표본도 나온 뒤에(최대 10초 — 보통 위 대기 중에 이미 나온다). 시간 초과는 errors.log 한 줄 — 공백은 rep_status host-sampler-thin이 드러낸다
  waited=0; for w in $(seq 1 20); do [[ -s "$dir/timeline-host.jsonl" ]] && { waited=1; break; }; sleep 0.5; done
  (( waited )) || echo "$(date -Is) host-samples-before-load timeout: timeline-host.jsonl 10s 동안 비어 있음" >> "$dir/errors.log"
  # ADR-006 I/O 스냅숏(직전): PostgreSQL 백엔드는 누적 통계를 바로 공유 메모리에 내지 않는다(PG15+ — 마지막 반영 1초 뒤 · 쉬면 최대 10초 뒤).
  # 시드(대량 INSERT)의 통계가 부하 구간 차분에 섞이지 않게 시드 끝에서 IO_SETTLE_S(기본 11초)가 지난 뒤 읽는다(앱 누적 지표 직전 값보다 먼저 — 그 사이 만료 배치의 커넥션 획득이 빌림 수에 섞이지 않게) — 앞 단계가 이미 그만큼 걸렸으면 기다리지 않는다
  local wait_io=$(( ${IO_SETTLE_S:-11} - (SECONDS - seed_done) )); (( wait_io > 0 )) && sleep "$wait_io"
  io_snapshot > "$dir/io-before.json" 2>>"$dir/errors.log" || echo "io_snapshot before failed" >> "$dir/errors.log"
  local deadlocks0; deadlocks0=$(db_scalar "SELECT deadlocks FROM pg_stat_database WHERE datname='seat'" 2>/dev/null)
  # 누적 지표의 부하 직전 값 — 예열(선점 약 2,000건)이 섞이지 않게 차분으로 본다(재점검 지적)
  app_counters "$dir/before-k6.json" 2>>"$dir/errors.log"

  local k6env; k6env=$(k6_env_of "$cell")
  # ADR-007 리뷰 A3: 서버 − PC 시계 차를 k6 바로 앞·바로 뒤에 잰다(lib.sh clock_offset) — wall.py·summarize.py가 서버 시계 시각(대기 표본·DB 로그)을 PC 시계로 옮긴다
  local clk_before clk_after; clk_before=$(clock_offset 2>>"$dir/errors.log")
  local dash=()
  [[ "$base" != S1 && "$base" != S2 && "$base" != S7 ]] && dash=(K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_PORT=-1 "K6_WEB_DASHBOARD_EXPORT=$dir/k6-dashboard.html")
  local k6_started; k6_started=$(date -Is)
  env $k6env "${dash[@]}" BASE_URL="$BASE_URL" OUT_DIR="$dir" \
    k6 run --quiet --out "csv=$dir/k6-requests.csv.gz" "$SCENARIOS/$(script_of "$base")" > "$dir/k6-stdout.log" 2>&1
  local k6_exit=$?
  local k6_ended; k6_ended=$(date -Is)
  local k6_done=$SECONDS
  clk_after=$(clock_offset 2>>"$dir/errors.log")
  jq -n --argjson b "${clk_before:-null}" --argjson a "${clk_after:-null}" '{before:$b, after:$a}' > "$dir/clock-offset.json" \
    || echo '{"before":null,"after":null}' > "$dir/clock-offset.json"
  after_k6 "$dir" "$deadlocks0" 2>>"$dir/errors.log" || echo "after_k6 failed" >> "$dir/errors.log"
  # ADR-006 I/O 스냅숏(직후): 같은 이유로 k6 끝에서 IO_SETTLE_S가 지난 뒤(after_k6 조회 시간을 빼고 남은 만큼만 기다린다)
  wait_io=$(( ${IO_SETTLE_S:-11} - (SECONDS - k6_done) )); (( wait_io > 0 )) && sleep "$wait_io"
  io_snapshot > "$dir/io-after.json" 2>>"$dir/errors.log" || echo "io_snapshot after failed" >> "$dir/errors.log"
  # ADR-007 후속: DB 저장 위치·크기(모든 preset 기록 — tmpfs면 rep_status가 tmpfs 적용을 대조)
  pg_storage > "$dir/pg-storage.json" 2>>"$dir/errors.log" || echo "pg_storage failed" >> "$dir/errors.log"
  # ADR-005 끝 상태(DB 사실): S2 사용자별 매수 · S7 대상 좌석별 상태·보유자 — 가짜 거절·이긴 쪽 롤백 판정의 근거
  [[ "$base" == S2 || "$base" == S7 ]] && { end_state "$dir" "$base" "$k6env" 2>>"$dir/errors.log" || echo "end_state failed" >> "$dir/errors.log"; }

  # S3: 마지막 이탈 홀드가 만료되고 배치가 정리할 때까지 관측을 유지한 뒤 판정한다
  local grace; grace=$(grace_of "$base")
  [[ "$base" == S3* ]] && sleep $(( $(ttl_s_of "$base") + grace + 2 ))
  # 최종 판정은 전체 행을 훑는다(S4는 100만 석) — 폴링보다 긴 상한
  CONSISTENCY_TIMEOUT=600 consistency "$grace" > "$dir/consistency.json" 2>>"$dir/errors.log"
  stop_pollers $pids
  # 서버 쪽 락 표본 루프 정리 — SSH가 끊기면 다음 출력에서 끝나지만, 남지 않게 명시적으로(이 실험의 루프만 맞는 패턴)
  remote "pkill -f 'FROM pg_locks WHERE NOT granted' || true" > /dev/null 2>&1
  recover_samples "$dir"
  host_sampler_kill "$HOST_COPY"
  host_recover "$dir" "$HOST_COPY" "$HOST_IOSTAT"
  snapshot "$dir" after

  # 컨테이너 로그 보존 (이번 회차 구간만) · k6 경고 로그 압축
  remote "docker logs --since '$started' seatlab-app-1 2>&1" | gzip > "$dir/app.log.gz"
  remote "docker logs --since '$started' seatlab-db-1 2>&1" | gzip > "$dir/db.log.gz"
  (( APPS == 2 )) && remote "docker logs --since '$started' seatlab-app2-1 2>&1" | gzip > "$dir/app2.log.gz"
  gzip -f "$dir/k6-stdout.log"

  finish_rep "$dir" "$level" "$cell" "$rep" "$started" "$k6_started|$k6_ended" "$(rep_status "$dir" "$base" "$k6_exit" "$k6_started" "$k6_ended")" "$k6_exit" "$k6env"
}

# 부하 직후 누적 지표(ADR-003 ④⑤⑦) — 0.5~1초 표본이 놓치는 짧은 버스트를 누적값으로 본다. 앱 2대면 앱마다(lb 안에서 직접).
#   acquire: Hikari 커넥션 획득 대기 timer(COUNT·TOTAL_TIME 누적 / MAX는 최근 약 2분 창) · timeouts: 획득 타임아웃 수
#   hold_requests: 그 앱이 처리한 선점 요청 수(앱 2대에서 두 앱 모두 받았는지) · deadlocks_delta: 부하 중 DB 데드락 수
# 앱별 누적 카운터 한 벌을 파일로 — 부하 직전·직후에 한 번씩. 과부하 직후 앱이 적체를 푸는 중이면 응답이 늦어 실패할 수 있어 조회마다 재시도한다.
actuator_retry() {  # 인자: 앱 경로 — 성공한 JSON 또는 빈 출력
  local i out
  for i in 1 2 3 4 5 6; do
    out=$(app_actuator "$1" "$2" 2>/dev/null) && [[ -n "$out" ]] && { echo "$out"; return 0; }
    sleep 10
  done
  return 1
}
# ADR-005 앱 지표: 매수 제어 구간 타이머(prepare 트랜잭션 밖 쿼터 행 준비 · acquire · check · span = acquire 시작 ~ check 끝 — COUNT·TOTAL_TIME 누적 초, MAX 최근 창)와 SERIALIZABLE 카운터.
# Micrometer는 처음 기록될 때 지표를 만든다 — 지표 목록에 이름이 없으면 '아직 0'(카운터 0 · 타이머 0건). 목록 조회 자체가 실패하면 null(미측정).
limit_metrics() {  # 인자: 앱 → {"prepare"|"acquire"|"check"|"span":{COUNT,TOTAL_TIME,MAX},"serialization_failure":n,"retry":n} 또는 null
  local a="$1" names out='{}' m key v
  names=$(actuator_retry "$a" metrics | jq -c '.names') || { echo null; return; }
  [[ -n "$names" && "$names" != null ]] || { echo null; return; }
  for m in prepare acquire check span; do
    if jq -e --arg n "seat.hold.limit.$m" 'index($n) != null' <<< "$names" > /dev/null; then
      v=$(actuator_retry "$a" "metrics/seat.hold.limit.$m" | jq -c '[.measurements[] | {(.statistic): .value}] | add') || v=null
    else
      v='{"COUNT":0,"TOTAL_TIME":0,"MAX":0}'
    fi
    out=$(jq -c --arg k "$m" --argjson v "${v:-null}" '. + {($k): $v}' <<< "$out")
  done
  for key in serialization_failure retry; do
    if jq -e --arg n "seat.hold.limit.$key" 'index($n) != null' <<< "$names" > /dev/null; then
      v=$(actuator_retry "$a" "metrics/seat.hold.limit.$key" | jq -c '[.measurements[] | select(.statistic=="COUNT") | .value][0]') || v=null
    else
      v=0
    fi
    out=$(jq -c --arg k "$key" --argjson v "${v:-null}" '. + {($k): $v}' <<< "$out")
  done
  echo "$out"
}

# 선점 요청 수(요청당 커넥션 빌림의 분모 — 명세 §9.4 ③). 인자: 앱 → 숫자 또는 null.
#   0은 '아직 기록 없음'이 지표 목록에서 확인될 때만: http.server.requests가 목록에 없거나, 있는데 uri 태그 값에 선점 URI가 없을 때
#   (그 URI로 요청이 한 번도 없으면 태그 조회가 404). 그 밖의 실패(목록·태그 조회 실패, 값 아님)는 null — 0으로 채우면 직전 값이 0이 되어
#   차분이 누적값이 되고 빌림이 조용히 낮게 나온다(ADR-006 리뷰 지적). 조회마다 actuator_retry(과부하 직후 늦은 응답).
HOLD_URI='/api/schedules/{scheduleId}/seats/{seatId}/hold'
# ADR-008(리뷰 루프 2 D1): 확정 요청도 커넥션을 빌린다(S3) — 요청당 빌림의 분모에 넣으려고 같은 방식으로 센다. 시나리오가 보내는 앱 요청은 선점·확정 둘뿐이다
# (scenarios/lib/common.js http.post 2곳 — /internal·/actuator는 run.sh가 부하 밖에서 부르고, 그중 DB를 쓰는 /internal은 k6 직전·직후 스냅숏 사이에 없다)
CONFIRM_URI='/api/holds/{holdId}/confirm'
uri_requests() {  # 인자: 앱 URI템플릿 → 그 URI의 http.server.requests COUNT · 0(기록 없음 확인) · null(조회 실패) — 정의는 위 머리말(hold_requests)
  local a="$1" uri="$2" names base v
  names=$(actuator_retry "$a" metrics | jq -c '.names') || { echo null; return; }
  jq -e 'type == "array"' <<< "$names" > /dev/null 2>&1 || { echo null; return; }
  jq -e 'index("http.server.requests") != null' <<< "$names" > /dev/null || { echo 0; return; }
  base=$(actuator_retry "$a" metrics/http.server.requests) || { echo null; return; }
  jq -e '.availableTags | type == "array"' <<< "$base" > /dev/null 2>&1 || { echo null; return; }
  jq -e --arg u "$uri" '[.availableTags[] | select(.tag == "uri") | .values[]] | index($u) != null' <<< "$base" > /dev/null || { echo 0; return; }
  v=$(actuator_retry "$a" "metrics/http.server.requests?tag=uri:$(sed 's/{/%7B/g; s/}/%7D/g' <<< "$uri")" \
        | jq -c '[.measurements[] | select(.statistic=="COUNT") | .value][0]') || { echo null; return; }
  jq -e 'type == "number"' <<< "$v" > /dev/null 2>&1 && echo "$v" || echo null
}
hold_requests() { uri_requests "$1" "$HOLD_URI"; }
confirm_requests() { uri_requests "$1" "$CONFIRM_URI"; }

app_counters() {  # 인자: 출력 파일
  local out="$1" arr="[]" a apps="app"
  (( APPS == 2 )) && apps="app app2"
  for a in $apps; do
    local st lst pool acq tmo req lim props
    props=$(actuator_retry "$a" configprops)
    st=$(jq -c '[.contexts[].beans[] | select(.prefix=="seat.hold") | .properties.strategy][0]' <<< "$props")
    lst=$(jq -c '[.contexts[].beans[] | select(.prefix=="seat.hold") | .properties.limitStrategy][0]' <<< "$props")
    lim=$(limit_metrics "$a")
    pool=$(actuator_retry "$a" metrics/hikaricp.connections.max | jq -c '.measurements[0].value')
    acq=$(actuator_retry "$a" metrics/hikaricp.connections.acquire | jq -c '[.measurements[] | {(.statistic): .value}] | add')
    tmo=$(actuator_retry "$a" metrics/hikaricp.connections.timeout | jq -c '.measurements[0].value')
    req=$(hold_requests "$a")   # 숫자 · 0(기록 없음 확인) · null(조회 실패) — 위 함수
    local creq; creq=$(confirm_requests "$a")   # ADR-008 D1 — 같은 규칙
    arr=$(jq -c --arg a "$a" --argjson st "${st:-null}" --argjson ls "${lst:-null}" --argjson p "${pool:-null}" --argjson q "${acq:-null}" --argjson t "${tmo:-null}" --argjson r "${req:-null}" --argjson l "${lim:-null}" --argjson cr "${creq:-null}" \
          '. + [{app:$a, strategy:$st, limit_strategy:$ls, pool:$p, acquire:$q, timeouts:$t, hold_requests:$r, confirm_requests:$cr, limit:$l}]' <<< "$arr")
  done
  echo "$arr" > "$out"
}

after_k6() {  # 인자: 폴더 부하 전 deadlocks — 부하 중 증가분(직후 − 직전)을 남긴다. MAX는 누적이 아니라 최근 약 2분 창 그대로
  local dir="$1" d0="$2" arr
  app_counters "$dir/after-k6.raw.json"
  arr=$(jq -c --slurpfile b "$dir/before-k6.json" '[.[] as $x | ($b[0] // [] | map(select(.app == $x.app))[0]) as $y |
          $x + {acquire: (if $x.acquire and $y.acquire then {COUNT: ($x.acquire.COUNT - $y.acquire.COUNT), TOTAL_TIME: ($x.acquire.TOTAL_TIME - $y.acquire.TOTAL_TIME), MAX: $x.acquire.MAX} else null end),
                timeouts: (if $x.timeouts != null and $y.timeouts != null then $x.timeouts - $y.timeouts else null end),
                limit: (if $x.limit and $y.limit
                           and ([("prepare","acquire","check","span")] | all(. as $m | $x.limit[$m] and $y.limit[$m]))
                           and $x.limit.serialization_failure != null and $y.limit.serialization_failure != null
                           and $x.limit.retry != null and $y.limit.retry != null then
                          (reduce ("prepare","acquire","check","span") as $m ({};
                              . + {($m): {COUNT: ($x.limit[$m].COUNT - $y.limit[$m].COUNT), TOTAL_TIME: ($x.limit[$m].TOTAL_TIME - $y.limit[$m].TOTAL_TIME), MAX: $x.limit[$m].MAX}})
                           + {serialization_failure: ($x.limit.serialization_failure - $y.limit.serialization_failure),
                              retry: ($x.limit.retry - $y.limit.retry)}) else null end),
                hold_requests: (if $y and $x.hold_requests != null and $y.hold_requests != null then $x.hold_requests - $y.hold_requests else null end),
                confirm_requests: (if $y and $x.confirm_requests != null and $y.confirm_requests != null then $x.confirm_requests - $y.confirm_requests else null end)}]' "$dir/after-k6.raw.json") || arr="[]"
  local d1 redis
  d1=$(db_scalar "SELECT deadlocks FROM pg_stat_database WHERE datname='seat'")
  redis=$(remote "docker exec seatlab-redis-1 sh -c 'redis-cli info memory | grep ^used_memory: | cut -d: -f2; redis-cli dbsize'" | tr -d '\r' | tr '\n' ' ')
  jq -n --argjson apps "$arr" --arg d0 "$d0" --arg d1 "$d1" --arg redis "$redis" \
    '{apps:$apps, deadlocks_delta:(if ($d0|test("^[0-9]+$")) and ($d1|test("^[0-9]+$")) then ($d1|tonumber)-($d0|tonumber) else null end),
      redis:{used_memory:($redis|split(" ")[0]|tonumber? // null), keys:($redis|split(" ")[1]|tonumber? // null)}}' > "$dir/after-k6.json"
}

# ADR-005 끝 상태(DB 직접 — 앱 판정기와 별도로 사용자·좌석 단위가 필요하다). 인자: 폴더 셀 k6env
#   S2: 측정 회차(1)의 사용자별 홀드 수 + 확정 예약 수 → {"per_user": {"<user>": n}}
#   S7: + 대상 좌석(1..SEATS)별 상태·홀드 보유자 → {"per_seat": {"<seat>": {"status":…, "holders":[…]}}}
end_state() {
  local dir="$1" base="$2" k6env="$3" seats pu ps
  pu=$(db_scalar "SELECT coalesce(json_object_agg(user_id, n), '{}') FROM (SELECT user_id, sum(n) AS n FROM (SELECT user_id, count(*) AS n FROM seat_hold WHERE schedule_id = 1 GROUP BY 1 UNION ALL SELECT user_id, count(*) FROM reservation WHERE schedule_id = 1 AND status = 'CONFIRMED' GROUP BY 1) u GROUP BY 1) t") || return 1
  ps='null'
  if [[ "$base" == S7 ]]; then
    seats=$(sed -n 's/.*SEATS=\([0-9]*\).*/\1/p' <<< "$k6env")
    [[ -n "$seats" ]] || return 1
    ps=$(db_scalar "SELECT coalesce(json_object_agg(s.id, json_build_object('status', s.status, 'holders', coalesce((SELECT json_agg(h.user_id ORDER BY h.id) FROM seat_hold h WHERE h.seat_id = s.id), '[]'))), '{}') FROM product_seat s WHERE s.schedule_id = 1 AND s.id <= $seats") || return 1
  fi
  jq -n --argjson pu "$pu" --argjson ps "$ps" '{per_user: $pu} + (if $ps == null then {} else {per_seat: $ps} end)' > "$dir/end-state.json"
}

app_cpus() {  # 앱 1대 = 단계 CPU 전부, 앱 2대 = 절반씩(L4 → 2 + 2)
  if (( APPS == 2 )); then echo $(( $1 / 2 )); else echo "$1"; fi
}

# 회차 산출물 검증 — 사유를 누적한다 (하나라도 있으면 실패)
# ADR-007: 복합 조건은 전부 if로 묶는다 — `A || B && C`는 `(A || B) && C`로 묶여 ADR-006에서 S3 회차에 늘 사유가 붙었다(fbaaf815).
# 한 줄 `조건 || reasons+=(…)`는 '조건이 거짓이면 사유' 하나뿐인 경우에만 남겼다. 판정식마다 양방향 오프라인 확인 기록은 README '재측정 판정식'.
rep_status() {  # 인자: 폴더 base k6_exit k6_started k6_ended
  local dir="$1" base="$2" k6_exit="$3" k6_started="$4" k6_ended="$5" reasons=()
  if (( k6_exit != 0 )); then
    # S4(계단)의 99 = 한계 도달로 스스로 멈춤(정상). 단 성공한 단계가 하나도 없으면 측정 실패다.
    # 고정 도착률(S4c*)은 에러율 중단을 껐으므로(k6_env_of STOP_ON_ERROR=0) 0이 아닌 종료는 전부 실패다
    if [[ "$base" == S4 && "$k6_exit" -eq 99 ]]; then
      if ! python3 "$SUMMARIZE" --s4-check "$dir/k6-summary.json"; then reasons+=("s4-no-successful-stage"); fi
    else
      reasons+=("k6-exit-$k6_exit")
    fi
  fi
  # 고정 도착률: 정상 종료 = 계획 구간(12 × 30초) 전부에 요청이 있고 k6가 계획 시간을 다 돌았다. 목표 미달은 결과이지 실패가 아니다(요약기 구간표)
  if [[ "$base" == S4c* ]]; then
    if ! python3 "$SUMMARIZE" --fixed-check "$dir/k6-summary.json"; then reasons+=("fixed-incomplete"); fi
  fi
  local f
  for f in k6-summary.json consistency.json app-config.json seed.json after-k6.json io-before.json io-after.json pg-settings.json; do
    if ! { [[ -s "$dir/$f" ]] && jq empty "$dir/$f" 2>/dev/null; }; then reasons+=("invalid-$f"); fi
  done
  if [[ "$base" == S2 || "$base" == S7 ]]; then
    if ! { [[ -s "$dir/end-state.json" ]] && jq -e '.per_user | type == "object"' "$dir/end-state.json" > /dev/null 2>&1; }; then reasons+=("invalid-end-state.json"); fi
    if [[ "$base" == S7 ]] && ! jq -e '.per_seat | length > 0' "$dir/end-state.json" > /dev/null 2>&1; then reasons+=("invalid-end-state.json-seats"); fi
  fi
  # ADR-006 I/O 스냅숏: 시간 열(fsync 평균 등)은 타이밍 설정이 켜져 있어야 의미가 있다(compose db.command) — 꺼져 있으면 0이 '빠름'으로 읽힌다
  local io
  for io in io-before.json io-after.json; do
    jq -e '.pg.wal.wal_sync != null and (.disk | length) > 0' "$dir/$io" > /dev/null 2>&1 || reasons+=("invalid-$io-content")
    jq -e '.pg.settings.track_wal_io_timing == "on" and .pg.settings.track_io_timing == "on"' "$dir/$io" > /dev/null 2>&1 || reasons+=("io-timing-off-$io")
  done
  # ADR-007 DB 설정 축: 실제 값(SHOW — pg-settings.json)이 preset과 같은가. 키마다 사유 하나(어느 설정이 어긋났는지 status에서 보이게)
  if jq empty "$dir/pg-settings.json" 2>/dev/null && [[ -s "$dir/pg-settings.json" ]]; then
    local kv key want got
    for kv in $(preset_env "$PG_PRESET"); do
      case "${kv%%=*}" in
        PG_CHECKPOINT_TIMEOUT) key=checkpoint_timeout ;; PG_MAX_WAL_SIZE) key=max_wal_size ;; PG_SHARED_BUFFERS) key=shared_buffers ;; *) continue ;;
      esac
      want="${kv#*=}"; got="$(jq -r --arg k "$key" '.[$k] // empty' "$dir/pg-settings.json")"
      if [[ "$got" != "$want" ]]; then reasons+=("pg-preset-mismatch-$key"); fi
    done
    if [[ "$(jq -r '.log_checkpoints // empty' "$dir/pg-settings.json")" != on ]]; then reasons+=("log-checkpoints-off"); fi
  fi
  # 수집기는 오류를 버리고 돈다 — 파일이 비면 조용히 실패한 것이므로 회차 실패로 드러낸다(락 표본 SQL 오류가 빈 파일만 남긴 스모크 실측)
  for f in server-before.txt server-after.txt client-before.txt client-after.txt timeline-server.jsonl timeline-locks.jsonl timeline-waits.jsonl timeline-hikari.jsonl; do
    [[ -s "$dir/$f" ]] || reasons+=("missing-$f")
  done
  for f in k6-requests.csv.gz app.log.gz db.log.gz k6-stdout.log.gz; do   # 압축 파일은 무결성까지
    if ! { [[ -s "$dir/$f" ]] && gzip -t "$dir/$f" 2>/dev/null; }; then reasons+=("corrupt-or-missing-$f"); fi
  done
  # 조건이 실제로 적용됐는지 — 풀 크기와 DB의 실제 인덱스 목록을 계획과 대조한다
  if jq empty "$dir/app-config.json" 2>/dev/null; then
    [[ "$(jq -r '."hikaricp.connections.max"[0] // empty | floor' "$dir/app-config.json")" == "$POOL" ]] || reasons+=("pool-mismatch")
    [[ "$(jq -r '[.db_indexes[].name] | sort | join(",")' "$dir/app-config.json")" == "$EXPECTED_INDEXES" ]] || reasons+=("index-mismatch")
    # 적용된 경합 제어 전략 — 설정 바인딩 결과(enum 이름)를 계획과 대조
    [[ "$(jq -r '.configprops[] | select(.prefix=="seat.hold") | .properties.strategy' "$dir/app-config.json")" == "$(echo "$STRATEGY" | tr 'a-z-' 'A-Z_')" ]] || reasons+=("strategy-mismatch")
    # 임계 구역 지연(ADR-004) — Duration 바인딩 결과(ISO-8601, 예: PT0.02S / PT0S)와 대조
    [[ "$(jq -r '.configprops[] | select(.prefix=="seat.hold") | .properties.criticalSectionDelay' "$dir/app-config.json")" == "$(python3 -c "print('PT%gS' % ($DELAY_MS/1000))")" ]] || reasons+=("delay-mismatch")
    # ADR-005 매수 제어 방식 — 계획 값(LIMIT_STRATEGY)과 바인딩 결과(enum 이름) 대조. 주입 값(APPLIED_LIMIT)이 아니라 계획과 비교해야 주입 실수가 잡힌다
    [[ "$(jq -r '.configprops[] | select(.prefix=="seat.hold") | .properties.limitStrategy' "$dir/app-config.json")" == "$(echo "$LIMIT_STRATEGY" | tr 'a-z-' 'A-Z_')" ]] || reasons+=("limit-strategy-mismatch")
  fi
  # ADR-008 기본값 출처(명세 §5): 앱 컨테이너 환경에 매수 방식 키가 없어야 한다(limit-key.json — 앱마다 한 줄, lib.sh limit_key_check).
  # 키가 있으면 limit-key-present(기본값이 아니라 주입으로 떴을 수 있다), 파일이 없거나 앱 수와 다르면 limit-key-check-failed
  if jq -e --argjson n "$APPS" 'type == "array" and length == $n and all(.[]; (.present | type) == "boolean")' "$dir/limit-key.json" > /dev/null 2>&1; then
    if jq -e 'any(.[]; .present)' "$dir/limit-key.json" > /dev/null; then reasons+=("limit-key-present"); fi
  else
    reasons+=("limit-key-check-failed")
  fi
  # ADR-008 설정 파일 정적 검사(리뷰 루프 1 C4): limit-set-by-profile · limit-default-not-precheck · spring-config-override, 확인 실패면 limit-config-check-failed
  if jq -e '.reasons | type == "array"' "$dir/limit-config.json" > /dev/null 2>&1; then
    local lcr; for lcr in $(jq -r '.reasons[]' "$dir/limit-config.json"); do reasons+=("$lcr"); done
  else
    reasons+=("limit-config-check-failed")
  fi
  # 런타임 쪽(리뷰 루프 1 C8): 앱 컨테이너 환경에 SPRING_CONFIG_*가 있으면 외부 설정 위치 — 정적 검사와 같은 사유(한 번만)
  if jq -e 'type == "array" and any(.[]; (.spring_config // 0) > 0)' "$dir/limit-key.json" > /dev/null 2>&1 && [[ " ${reasons[*]} " != *" spring-config-override "* ]]; then
    reasons+=("spring-config-override")
  fi
  # ADR-008 회차 합격(명세 §5 — summarize.py --accept-check: 매수 초과 · v_counter_mismatch · 억울한 좌석(S7-m1) · 명세 순서 위반(S7-m1-late) · 빌림 1.0(모든 셀 — 리뷰 루프 1 C1)).
  # 사유는 한 줄에 하나(exit 1). 낸 사유는 종료 코드와 무관하게 모두 붙인다(리뷰 루프 1 C6 — 판정 실패가 이미 찾은 사유를 지우지 않게).
  # exit 0인데 사유가 있거나, exit 1인데 사유가 없거나, 그 밖의 종료(파이썬 자체 실패 등)면 accept-check-failed — 조용히 ok가 되지 않게
  local acc acc_rc r
  acc=$(python3 "$SUMMARIZE" --accept-check "$dir" "$(basename "$(dirname "$dir")")" 2>>"$dir/errors.log"); acc_rc=$?
  for r in $acc; do reasons+=("$r"); done
  if { [[ "$acc_rc" == 0 && -n "$acc" ]] || [[ "$acc_rc" == 1 && -z "$acc" ]] || [[ "$acc_rc" != 0 && "$acc_rc" != 1 ]]; } \
     && [[ " ${reasons[*]} " != *" accept-check-failed "* ]]; then
    reasons+=("accept-check-failed")
  fi
  # 앱마다 계획한 전략·풀로 떠서 부하 중 선점 요청을 실제로 받았는가(예열 제외 증가분) — 앱 2대에서 한 대만 살아도 nginx가 다른 쪽으로 몰아
  # '1대 측정'이 되는 것을 잡는다. 지표 조회 자체가 실패한 것(null)은 불일치와 따로 표시한다
  if jq empty "$dir/after-k6.json" 2>/dev/null; then
    [[ "$(jq '[.apps[] | select(.strategy == null or .limit_strategy == null or .acquire == null or .timeouts == null or .limit == null)] | length' "$dir/after-k6.json")" == 0 ]] \
      || reasons+=("after-k6-fetch-failed")
    local want; want="$(echo "$STRATEGY" | tr 'a-z-' 'A-Z_')"
    local want_l; want_l="$(echo "$LIMIT_STRATEGY" | tr 'a-z-' 'A-Z_')"
    [[ "$(jq --arg s "$want" --arg l "$want_l" --argjson p "$POOL" '[.apps[] | select(.strategy==$s and .limit_strategy==$l and (.pool|floor)==$p and (.hold_requests // 0) > 0)] | length' "$dir/after-k6.json")" == "$APPS" ]] \
      || reasons+=("apps-mismatch")
    # ADR-006 요청당 커넥션 빌림(명세 §9.4 ③): 분모(선점 요청 수)가 직전·직후 모두 실제로 읽혔나 — null(조회 실패)이면 빌림을 낼 수 없다
    local hf
    for hf in before-k6.json after-k6.raw.json; do
      jq -e --argjson n "$APPS" 'length == $n and all(.[]; .hold_requests | type == "number")' "$dir/$hf" > /dev/null 2>&1 || reasons+=("hold-requests-missing-$hf")
    done
    # 빌림 = Σ획득 증가분 ÷ Σ선점 증가분이 0.9 ~ 4 밖이면 지표가 어긋난 것(분모가 누적값·다른 URI 등). ADR-005 실측: 1방식 1.00~1.02,
    # 다중 커넥션 방식(counter·quota-*) 2.1~3.0, serializable-retry 최대 2.72 — 상한 4는 그 위 여유. 계산 불가(획득 null·선점 0)는 위 사유들이 드러낸다
    # S3는 확정 요청도 커넥션을 빌리는데 분모는 선점 요청만이라 '선점당 빌림'으로 읽을 수 없다 — 범위 검사는 S2·S4·S4c·S7 계열에만(ADR-006 재점검 N1)
    # (`A || B && C`는 `(A || B) && C`라 S3에서 늘 사유가 붙었다 — if로 묶는다, fbaaf815)
    if [[ "$base" != S3* ]] && jq -e '[.apps[].acquire.COUNT] as $a | [.apps[].hold_requests] as $h
           | if ($a | all(type == "number")) and ($h | all(type == "number")) and ($h | add) > 0
             then (($a | add) / ($h | add)) as $r | ($r < 0.9 or $r > 4) else false end' "$dir/after-k6.json" > /dev/null 2>&1; then
      reasons+=("invalid-borrow-ratio")
    fi
  fi
  # 측정 중 k6 PC~서버 경로 단절(ADR-002 A2): 서버 지표 수집 공백이 30초를 넘으면 그 회차는 재측정 대상
  if ! python3 "$(dirname "$0")/gapcheck.py" "$dir/timeline-server.jsonl" "$k6_started" "$k6_ended" 30 2>>"$dir/errors.log"; then reasons+=("path-gap"); fi
  # ADR-007 시계 차(리뷰 A3 — lib.sh clock_offset, k6 앞·뒤): 대기 표본·DB 로그(서버 시계)를 PC 시계로 옮기는 값. 둘 중 하나라도 없으면 clock-offset-missing,
  # |차| > 0.5초면 clock-offset-large(벽 ±3초 창에 비해 크다 — 시계 동기화부터 본다). 평균을 아래 표본 판정에 쓴다(없으면 0)
  local clk_off=0
  if jq -e '(.before.offset_s | type == "number") and (.after.offset_s | type == "number")' "$dir/clock-offset.json" > /dev/null 2>&1; then
    clk_off=$(jq -r '(.before.offset_s + .after.offset_s) / 2' "$dir/clock-offset.json")
    if jq -e '[.before.offset_s, .after.offset_s] | any(. > 0.5 or . < -0.5)' "$dir/clock-offset.json" > /dev/null 2>&1; then reasons+=("clock-offset-large"); fi
  else
    reasons+=("clock-offset-missing")
  fi
  # ADR-007 대기 이벤트 표본: k6 구간 안에서 표본 사이가 30초를 넘으면 벽 시각의 대기 분포를 낼 수 없다(같은 판정기 — '{"t"'로 시작하는 줄의 t, 서버 시각 − 시계 차) → waits-gap.
  # k6 구간 안 표본이 하나도 없으면 waits-none-in-k6(리뷰 A10 — S2·S7은 k6가 몇 초라 30초 공백 판정으로는 못 잡는다; gapcheck exit 3).
  # 파일이 비어 있으면 위 missing-timeline-waits.jsonl이 이미 드러낸다
  if [[ -s "$dir/timeline-waits.jsonl" ]]; then
    python3 "$(dirname "$0")/gapcheck.py" "$dir/timeline-waits.jsonl" "$k6_started" "$k6_ended" 30 "$clk_off" 1 2>>"$dir/errors.log"
    case $? in
      0) ;;
      1) reasons+=("waits-gap") ;;
      3) reasons+=("waits-none-in-k6") ;;
      *) reasons+=("waits-check-failed") ;;
    esac
  fi
  # 서버 표본 사본을 지우지 못했으면(recover_samples — 리뷰 R2) 사유
  if grep -qs 'sampler-copy-not-removed' "$dir/sampler-recovery.txt"; then reasons+=("sampler-copy-not-removed"); fi
  # ADR-007 S7-m1-late(명세 §9.4 ⑥): 기회(R 201 뒤에 보낸 U)가 0이면 그 회차로는 명세 순서를 판정할 수 없다 — 실패
  if [[ "$(basename "$(dirname "$dir")")" == *-late ]] && ! python3 "$SUMMARIZE" --s7late-check "$dir" 2>>"$dir/errors.log"; then reasons+=("s7late-no-opportunity"); fi
  # ADR-007 리뷰 A5: S2·S7 hold_code 행(요청 1건당 1행)이 선점 요청 수와 같고 깨진 행이 없나 — 빠지면 가짜 거절·명세 순서 위반이 조용히 줄어든다
  if [[ "$base" == S2 || "$base" == S7 ]]; then
    if ! python3 "$SUMMARIZE" --codes-check "$dir" 2>>"$dir/errors.log"; then reasons+=("hold-codes-mismatch"); fi
  fi
  # ADR-007 벽 판정(wall.py --rep-check — 코드 0 처리(리뷰 A12)가 들어간 같은 벽 정의):
  #   벽이 있는데 벽 ±3초 대기 표본이 6개 미만(0.5초 표본이라 기대 약 12) → waits-thin-at-wall(리뷰 A4 — 벽 시각 대기 분포를 믿을 수 없다)
  #   nockpt 조건에서 k6 구간과 겹친 체크포인트(shutdown 제외)가 있으면 checkpoint-during-k6(리뷰 A14 — 명세 §8 가정 1이 그 회차에서 깨졌다), DB 로그를 못 읽었으면 checkpoint-check-failed
  if [[ "$base" == S4 || "$base" == S4c* ]]; then
    local wj
    if wj=$(python3 "$(dirname "$0")/wall.py" --rep-check "$dir" "$k6_started" "$k6_ended" 2>>"$dir/errors.log") && jq -e . <<< "$wj" > /dev/null 2>&1; then
      echo "$wj" > "$dir/wall-check.json"
      # 기준 초 = 벽, 고정 셀 '과도 미종료'면 처음 나쁜 초(wall.py thin_check_s — 리뷰 A4 잔여)
      if jq -e '.thin_check_samples != null and .thin_check_samples < 6' <<< "$wj" > /dev/null; then reasons+=("waits-thin-at-wall"); fi
      if [[ "$PG_PRESET" == nockpt ]]; then
        if jq -e '.checkpoints_during_k6 == null' <<< "$wj" > /dev/null; then
          reasons+=("checkpoint-check-failed")
        elif jq -e '.checkpoints_during_k6 | length > 0' <<< "$wj" > /dev/null; then
          reasons+=("checkpoint-during-k6")
        fi
      fi
    else
      reasons+=("wall-check-failed")
    fi
  fi
  if [[ "$base" == S3* || "$base" == S4 || "$base" == S4c* || "$base" == S6* ]]; then
    [[ -s "$dir/timeline-db.jsonl" ]] || reasons+=("missing-timeline-db.jsonl")
    [[ -s "$dir/k6-dashboard.html" ]] || reasons+=("missing-k6-dashboard.html")
  fi
  # ADR-007 후속(디스크 원인 — 2026-10-10 명세 §5 · 리뷰 rv8 A1·A4): 표본 장치(host-devices.json — 디스크 모델이 EXPECTED_DISK_MODEL이어야 한다).
  # 파일이 없거나 깨지면 invalid-host-devices.json, 모델·장치가 다르면 host-device-unexpected
  local hdisk=""
  if jq -e '(.disk | type == "string") and (.sampled | type == "array")' "$dir/host-devices.json" > /dev/null 2>&1; then
    hdisk="$(jq -r .disk "$dir/host-devices.json")"
    if ! jq -e --arg m "$EXPECTED_DISK_MODEL" '.model == $m and (.disk as $d | .sampled | any(. == $d))' "$dir/host-devices.json" > /dev/null 2>&1; then reasons+=("host-device-unexpected"); fi
  else
    reasons+=("invalid-host-devices.json")
  fi
  # 호스트 표본(서버 시계 − 시계 차): 그 디스크 누적값이 든 표본이 k6 구간 초의 90% 미만이거나 최대 공백 > 2.5초면 host-sampler-thin(파일 없음·빈 파일 포함 — wdrop.py --coverage)
  if ! python3 "$(dirname "$0")/wdrop.py" --coverage "$dir/timeline-host.jsonl" "$k6_started" "$k6_ended" "$clk_off" 0.9 "${hdisk:-nvme1n1}" 2>>"$dir/errors.log"; then reasons+=("host-sampler-thin"); fi
  # iostat 교차 확인 사본이 없거나 그 디스크 행이 없으면 host-iostat-missing
  if ! grep -qs "^${hdisk:-nvme1n1} " "$dir/iostat-host.txt"; then reasons+=("host-iostat-missing"); fi
  # 호스트 표본 서버 사본을 지우지 못했으면(lib.sh host_recover) 사유
  if grep -qs 'host-copy-not-removed' "$dir/host-recovery.txt"; then reasons+=("host-copy-not-removed"); fi
  # tmpfs preset: 데이터 디렉터리가 실제로 tmpfs인가(컨테이너 안 stat -f — pg-storage.json). 파일이 없거나 깨져도 확인 불가 = 미적용
  if [[ "$PG_PRESET" == tmpfs ]]; then
    if ! jq -e '.fstype == "tmpfs"' "$dir/pg-storage.json" > /dev/null 2>&1; then reasons+=("tmpfs-not-applied"); fi
    # 회차 끝 tmpfs 남은 공간이 TMPFS_MIN_AVAIL(기본 512MiB) 미만이면 — 크기를 넘기 직전이었다(명세 §8 가정 2, 리뷰 rv8 A3)
    if jq -e '.fstype == "tmpfs"' "$dir/pg-storage.json" > /dev/null 2>&1 \
       && ! jq -e --argjson min "${TMPFS_MIN_AVAIL:-536870912}" '(.df_bytes.avail | type == "number") and .df_bytes.avail >= $min' "$dir/pg-storage.json" > /dev/null 2>&1; then
      reasons+=("tmpfs-low-space")
    fi
  else
    # 반대 방향(리뷰 rv8 A2): 디스크 preset인데 데이터 디렉터리가 tmpfs면(이전 tmpfs 볼륨을 이어 씀 등) disk-preset-on-tmpfs, 저장 위치를 못 읽었으면 storage-check-failed
    if ! jq -e '.fstype | type == "string" and length > 0' "$dir/pg-storage.json" > /dev/null 2>&1; then
      reasons+=("storage-check-failed")
    elif jq -e '.fstype == "tmpfs"' "$dir/pg-storage.json" > /dev/null 2>&1; then
      reasons+=("disk-preset-on-tmpfs")
    fi
  fi
  # DB 메모리 초과 종료(모든 preset — 리뷰 rv8 A3): memory.events oom_kill > 0 또는 OOMKilled
  if jq -e '.oom_killed == true or ((.cgroup.oom_kill // 0) > 0)' "$dir/pg-storage.json" > /dev/null 2>&1; then reasons+=("db-oom-kill"); fi
  # DB 로그의 치명 신호(모든 preset): PANIC · 백엔드가 신호로 죽음 · 공간 없음
  if zgrep -qsE 'PANIC|terminated by signal|No space left on device' "$dir/db.log.gz" 2>/dev/null; then reasons+=("db-log-fatal"); fi
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
  local dir="$1" level="$2" cell="$3" rep="$4" started="$5" k6_window="$6" status="$7" k6_exit="$8" k6env="$9" base="$(cell_base "$3")"
  echo "$status" > "$dir/status"
  jq -n --arg sha "$SHA" --arg level "$level" --arg cell "$cell" --arg rep "$rep" \
        --arg started "$started" --arg ended "$(date -Is)" \
        --arg k6_started "${k6_window%%|*}" --arg k6_ended "${k6_window##*|}" --arg k6env "$k6env" \
        --arg appenv "$(config_env "$(config_of "$base")") APP_CPUS=$(app_cpus "$level") DB_CPUS=$level" \
        --arg ttl "$(ttl_s_of "$base")" --arg strategy "$STRATEGY" --arg pool "$POOL" --arg apps "$APPS" --arg delay "$DELAY_MS" \
        --arg limit "$LIMIT_STRATEGY" --arg applied "$APPLIED_LIMIT" --arg harness "$HARNESS" \
        --arg applied_seat "$(jq -r '.configprops[]? | select(.prefix=="seat.hold") | .properties.strategy' "$dir/app-config.json" 2>/dev/null)" \
        --arg applied_lim "$(jq -r '.configprops[]? | select(.prefix=="seat.hold") | .properties.limitStrategy' "$dir/app-config.json" 2>/dev/null)" \
        --arg status "$status" --argjson k6_exit "$k6_exit" --arg k6 "$(k6 version | head -1)" \
        --arg pg_preset "$PG_PRESET" --arg pg_env "$(preset_env "$PG_PRESET")" \
        --argjson pg_applied "$( { jq -ec . "$dir/pg-settings.json" 2>/dev/null || echo null; } | head -1)" \
        --argjson clock "$( { jq -ec . "$dir/clock-offset.json" 2>/dev/null || echo null; } | head -1)" \
        '{sha:$sha, level:($level|tonumber), cell:$cell, rep:($rep|tonumber), started:$started, ended:$ended,
          k6_started:$k6_started, k6_ended:$k6_ended, ttl_s:($ttl|tonumber),
          strategy:$strategy, pool:($pool|tonumber), apps:($apps|tonumber), delay_ms:($delay|tonumber), bg:0,
          limit_strategy:$limit, limit_source:"default", injected_limit_strategy:(if $applied != $limit then $applied else null end), harness:$harness,
          applied:{strategy:$applied_seat, limit_strategy:$applied_lim},
          k6_env:$k6env, app_env:$appenv, k6_exit:$k6_exit, status:$status, k6_version:$k6,
          io_settle_s:(($ENV.IO_SETTLE_S // "11")|tonumber),
          pg_preset:$pg_preset, pg_env:$pg_env, pg_applied:$pg_applied, clock_offset:$clock}' > "$dir/meta.json"
  echo "$(date -Is) L$level $cell rep$rep $status" >> "$MATRIX_LOG"
}

# ---- 매트릭스 ----------------------------------------------------------------------------------------
# 계획을 먼저 남긴다 — 요약은 이 계획 기준으로 미측정·누락을 표시한다 (이어서 실행이면 기존 계획 유지)
(( RESUME )) || jq -n --arg sha "$SHA" --arg levels "$LEVELS" --argjson reps "$REPS" --arg cells "$(expand_cells | tr '\n' ' ')" \
  --arg strategy "$STRATEGY" --argjson pool "$POOL" --argjson apps "$APPS" --argjson delay "$DELAY_MS" --arg limit "$LIMIT_STRATEGY" --arg harness "$HARNESS" \
  --arg pg "$PG_PRESET" \
  '{sha:$sha, harness:$harness, levels:($levels|split(" ")|map(select(.!="")|tonumber)), reps:$reps,
    cells:($cells|split(" ")|map(select(.!=""))), condition:{limit_strategy:$limit, limit_source:"default", pg_preset:$pg, strategy:$strategy, pool:$pool, apps:$apps, delay_ms:$delay}, created:(now|todate)}' > "$OUT_ROOT/plan.json"

# ADR-008 설정 파일 정적 검사(리뷰 루프 1 C4 — lib.sh limit_config_check): 측정 대상 앱 SHA의 프로필 설정이 매수 방식을 덮지 않는가 ·
# application.yml이 counter-upsert-precheck인가 · 띄울 compose에 SPRING_CONFIG_*가 없는가. 회차 전에 한 번 — 결과는 회차마다 limit-config.json으로 남고
# rep_status가 사유로 낸다(확인 자체가 실패하면 limit-config-check-failed). compose는 실제로 띄울 사본: 작업트리 스모크면 작업트리, 아니면 SHA에서 꺼낸 것
LC_TREE="$(mktemp -d)"
lc_files=()
for f in k6/ADR-008/compose.yml $(sed -n 's/-f \([^ ]*\)/\1/gp' <<< "${COMPOSE_EXTRA:-}"); do
  mkdir -p "$LC_TREE/$(dirname "$f")"
  if (( WORKTREE )); then cp "$REPO_ROOT/$f" "$LC_TREE/$f" 2>/dev/null; else git -C "$REPO_ROOT" show "$SHA:$f" > "$LC_TREE/$f" 2>/dev/null; fi
  lc_files+=("$LC_TREE/$f")
done
LIMIT_CONFIG="$(limit_config_check "$SHA" "${lc_files[@]}" 2>/dev/null)" || LIMIT_CONFIG=""
rm -rf "$LC_TREE"
if [[ -n "$LIMIT_CONFIG" ]]; then echo "$LIMIT_CONFIG" > "$OUT_ROOT/limit-config.json"; fi
echo "$(date -Is) limit-config $(if [[ -n "$LIMIT_CONFIG" ]]; then jq -c .reasons <<< "$LIMIT_CONFIG"; else echo check-failed; fi)" >> "$MATRIX_LOG"
# 캠페인(리뷰 루프 2 D4): 정적 검사가 실패했거나 사유가 하나라도 있으면 배포 전에 멈춘다(exit 6) — 회차마다 같은 사유로 실패할 것이 정해져 있다.
# campaign.sh는 run.sh가 0이 아니면 캠페인 전체를 멈춘다(재측정하지 않는다). 작업트리 스모크(--worktree — 음성 대조 fb181a30)와 캠페인 밖 단독 실행은
# 그대로 회차를 돌려 회차 status에 사유를 남긴다(음성 대조는 그 사유를 보려고 돈다)
if [[ "${CAMPAIGN_FROZEN:-0}" == 1 ]] && { [[ -z "$LIMIT_CONFIG" ]] || jq -e '.reasons | length > 0' <<< "$LIMIT_CONFIG" > /dev/null; }; then
  echo "$(date -Is) ABORT limit-config $(if [[ -n "$LIMIT_CONFIG" ]]; then jq -c .reasons <<< "$LIMIT_CONFIG"; else echo check-failed; fi) — 캠페인에서는 배포 전에 멈춘다" >> "$MATRIX_LOG"
  log "limit-config check failed or has reasons — abort before deploy (campaign)"
  exit 6
fi

if (( WORKTREE )); then
  deploy_worktree "$SHA" "$DEPLOY_ID" || { echo "$(date -Is) ABORT deploy-failed" >> "$MATRIX_LOG"; log "deploy failed"; exit 3; }
else
  deploy_sha "$SHA" || { echo "$(date -Is) ABORT deploy-failed" >> "$MATRIX_LOG"; log "deploy failed"; exit 3; }
fi
log "matrix $MATRIX_ID: levels=[$LEVELS] reps=$REPS cells=[$CELLS] pg_preset=$PG_PRESET"
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
      # 첫 회차부터 못 뜨면 빌드·배포 문제 — 즉시 중단. 캠페인에서는 매 호출이 회차 하나라 '첫 회차'가 매번이다 —
      # 일시 실패 한 번에 캠페인 전체가 멈추지 않게 캠페인이 연속 실패를 따로 센다(campaign.sh)
      if [[ -z "$FIRST_REP_DONE" && "${CAMPAIGN_FROZEN:-0}" != 1 ]] && (( UNHEALTHY_STREAK > 0 )); then
        echo "$(date -Is) ABORT first-rep-unhealthy" >> "$MATRIX_LOG"; log "first rep unhealthy — abort"
        { compose_down "$DEPLOY_ID" || echo "$(date -Is) compose-down-failed-at-abort" >> "$MATRIX_LOG"; }; exit 4
      fi
      FIRST_REP_DONE=1
      if (( UNHEALTHY_STREAK >= 3 )); then
        echo "$(date -Is) ABORT unhealthy-3-in-a-row" >> "$MATRIX_LOG"; log "3 consecutive unhealthy — abort"
        { compose_down "$DEPLOY_ID" || echo "$(date -Is) compose-down-failed-at-abort" >> "$MATRIX_LOG"; }; exit 4
      fi
    done
  done
done
compose_down "$DEPLOY_ID" || { echo "$(date -Is) compose-down-failed-at-end" >> "$MATRIX_LOG"; log "compose down failed at end"; }
log "matrix done: $OUT_ROOT"
