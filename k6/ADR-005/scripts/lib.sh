#!/usr/bin/env bash
# ADR-005 원격 실행 공통 함수(ADR-003 lib.sh 복사 + 작업트리 하네스 배포(스모크 전용)·앱 지표 조회). k6 PC에서 source 해서 쓴다.
set -euo pipefail

SERVER="${SERVER:-jun@192.168.55.164}"
SERVER_HOST="${SERVER#*@}"
REMOTE_BASE="${REMOTE_BASE:-labs/seat-reservation-lab}"   # 서버 홈 기준 — 이 경로 밖은 쓰지 않는다
BASE_URL="${BASE_URL:-http://${SERVER_HOST}:8101}"
COMPOSE_FILE="k6/ADR-005/compose.yml"
# 앱 2대 조건이면 run.sh가 COMPOSE_EXTRA="-f k6/ADR-005/compose.two-apps.yml"을 넘긴다(ADR-005 캠페인은 쓰지 않는다)
COMPOSE_EXTRA="${COMPOSE_EXTRA:-}"
# run.sh가 동결 사본에서 실행될 때는 원래 위치를 환경변수로 넘겨받는다
ADR_DIR="${ADR_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
REPO_ROOT="${REPO_ROOT:-$(cd "$ADR_DIR/../.." && pwd)}"

log() { echo "[$(date '+%F %T')] $*" >&2; }

remote() { ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 "$SERVER" "$@"; }

# 측정 대상 = 로컬 커밋 하나. 그 트리를 그대로 서버에 푼다 (GitHub 경유 없음).
# 재시도: k6 PC~서버 경로가 수십 초씩 끊긴 적이 있다(ADR-002 — 배포 한 번 실패에 캠페인이 멈춤). 풀기는 임시 폴더에 하고
# 끝난 뒤 이름을 바꿔 .deployed를 남긴다 — 도중에 끊겨도 반쯤 풀린 트리를 '배포됨'으로 보지 않는다.
deploy_sha() {
  local sha="$1" i
  for i in 1 2 3 4 5 6; do
    if remote "test -f $REMOTE_BASE/$sha/.deployed" 2>/dev/null; then return 0; fi
    log "deploy $sha → $SERVER:$REMOTE_BASE/$sha (attempt $i)"
    # 측정 결과(k6/*/results — 커밋된 원시 결과가 GB 단위)는 서버에 필요 없다. 넣으면 배포마다 3GB+가 쌓여 서버 디스크를 채운다(실측 2026-10-06: 100% → 배포 실패)
    if git -C "$REPO_ROOT" archive --format=tar "$sha" -- . ":(exclude,glob)k6/*/results/**" \
       | remote "rm -rf $REMOTE_BASE/.tmp-$sha && mkdir -p $REMOTE_BASE/.tmp-$sha && tar -x -C $REMOTE_BASE/.tmp-$sha && touch $REMOTE_BASE/.tmp-$sha/.deployed && rm -rf $REMOTE_BASE/$sha && mv $REMOTE_BASE/.tmp-$sha $REMOTE_BASE/$sha"; then
      return 0
    fi
    log "deploy attempt $i failed — retry in $((i * 30))s"
    sleep $((i * 30))
  done
  return 1
}

# 스모크 전용(--worktree): 앱은 커밋 <sha> 그대로, 하네스(k6/ADR-005 — results 제외)만 작업트리에서 덮어 푼다.
# 배포 폴더 이름 = <sha>-wt-<하네스 트리 해시 8자리> — 커밋 배포(<sha>)와 섞이지 않고, 하네스가 바뀌면 새 폴더가 된다.
# 본측정(캠페인)은 이 경로를 쓰지 않는다 — 기록되는 SHA = 실행한 하네스 원칙(run.sh가 캠페인에서 --worktree를 거부).
worktree_harness_hash() {
  (cd "$REPO_ROOT" && find k6/ADR-005 -path k6/ADR-005/results -prune -o -name __pycache__ -prune -o -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -c1-8)
}
deploy_worktree() {  # 인자: sha 배포폴더이름
  local sha="$1" id="$2" i
  for i in 1 2 3; do
    if remote "test -f $REMOTE_BASE/$id/.deployed" 2>/dev/null; then return 0; fi
    log "deploy $sha + worktree k6/ADR-005 → $SERVER:$REMOTE_BASE/$id (attempt $i)"
    if { git -C "$REPO_ROOT" archive --format=tar "$sha" -- . ":(exclude,glob)k6/*/results/**" | remote "rm -rf $REMOTE_BASE/.tmp-$id && mkdir -p $REMOTE_BASE/.tmp-$id && tar -x -C $REMOTE_BASE/.tmp-$id"; } \
       && tar -C "$REPO_ROOT" --exclude=k6/ADR-005/results --exclude=__pycache__ -cf - k6/ADR-005 \
          | remote "rm -rf $REMOTE_BASE/.tmp-$id/k6/ADR-005 && tar -x -C $REMOTE_BASE/.tmp-$id && touch $REMOTE_BASE/.tmp-$id/.deployed && rm -rf $REMOTE_BASE/$id && mv $REMOTE_BASE/.tmp-$id $REMOTE_BASE/$id"; then
      return 0
    fi
    log "worktree deploy attempt $i failed — retry in 20s"; sleep 20
  done
  return 1
}

# 자원 단계·설정을 환경변수로 넘겨 기동. 인자: sha, 나머지는 KEY=VALUE
compose_up() {   # SSH 순간 단절에 회차가 통째로 실패하지 않게 3번까지(--force-recreate라 다시 해도 같은 결과)
  local sha="$1"; shift
  local i
  for i in 1 2 3; do
    log "compose up ($sha) $* (attempt $i)"
    remote "cd $REMOTE_BASE/$sha && env $* docker compose -p seatlab -f $COMPOSE_FILE $COMPOSE_EXTRA up -d --build --force-recreate" >&2 && return 0
    sleep 20
  done
  return 1
}

compose_down() {
  local sha="$1"
  log "compose down ($sha)"
  # 앱 2대 오버레이까지 함께 지정해야 app2·lb도 내려간다 — 다음 회차가 1대 조건이어도 남지 않게(--remove-orphans가 보조)
  remote "cd $REMOTE_BASE/$sha && docker compose -p seatlab -f $COMPOSE_FILE -f k6/ADR-005/compose.two-apps.yml down -v --remove-orphans" >&2 || true
}

wait_health() {
  local deadline=$((SECONDS + ${1:-300}))
  until curl -fsS --max-time 5 "$BASE_URL/actuator/health" 2>/dev/null | grep -q '"UP"'; do
    if (( SECONDS > deadline )); then log "health timeout"; return 1; fi
    sleep 2
  done
}

# 모든 HTTP 호출에 상한을 둔다 — 응답 없는 서버에 매달려 무인 실행이 기록 없이 멈추지 않게.
reset_db() {  # 인자: schedules seatsPerSchedule [backgroundRows] — 배경 100만이면 행 400만을 넣으므로 상한을 넉넉히
  curl -fsS --max-time "${RESET_TIMEOUT:-900}" -X POST "$BASE_URL/internal/reset?schedules=$1&seatsPerSchedule=$2&backgroundRows=${3:-0}"
}

db_indexes() {  # 실제로 DB에 있는 사용자 인덱스(기본키 제외) — 인덱스 조건이 제대로 적용됐는지의 근거
  timeout 15 ssh -o BatchMode=yes -o ConnectTimeout=5 "$SERVER" \
    "docker exec seatlab-db-1 psql -U seat -d seat -tAc \"SELECT coalesce(json_agg(json_build_object('name', c.relname, 'unique', i.indisunique) ORDER BY c.relname), '[]') FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_class t ON t.oid = i.indrelid WHERE t.relname IN ('seat_hold','reservation','product_seat') AND NOT i.indisprimary\""
}

consistency() {  # 인자: graceSeconds(선택) · 환경 CONSISTENCY_TIMEOUT(기본 60s — 부하 중 폴링용)
  curl -fsS --max-time "${CONSISTENCY_TIMEOUT:-60}" "$BASE_URL/internal/consistency${1:+?graceSeconds=$1}"
}

counts() {  # 인자: seatStatus(true|false, 선택)
  curl -fsS --max-time 10 "$BASE_URL/internal/counts${1:+?seatStatus=$1}"
}

# 부하 중 폴링은 앱을 거치지 않고 DB 컨테이너에 직접 묻는다 — 앱이 포화돼 요청이 적체돼도 곡선이 끊기지 않게(실측: L1 S3A에서 앱 경유 폴링 대부분 유실)
db_counts() {  # 인자: seatStatus(true|false)
  local seat_sql=""
  [[ "${1:-false}" == true ]] && seat_sql=", (SELECT count(*) FILTER (WHERE status='AVAILABLE') FROM product_seat) AS available, (SELECT count(*) FILTER (WHERE status='HELD') FROM product_seat) AS held, (SELECT count(*) FILTER (WHERE status='RESERVED') FROM product_seat) AS reserved"
  timeout 15 ssh -o BatchMode=yes -o ConnectTimeout=5 "$SERVER" \
    "docker exec seatlab-db-1 psql -U seat -d seat -tAc \"SELECT row_to_json(t) FROM (SELECT (SELECT count(*) FROM seat_hold) AS hold_rows, (SELECT count(*) FROM reservation WHERE status='CONFIRMED') AS confirmed, now() AS checked_at $seat_sql) t\""
}

actuator() {  # 인자: 경로
  curl -fsS --max-time 10 "$BASE_URL/actuator/$1"
}

# 락 대기 표본(ADR-003 ④): 서버에서 0.5초마다 DB의 락 대기·활성 세션을 묻고 한 줄씩 흘려보낸다 — SSH 연결 하나로(표본마다 SSH를 열면 S1 몇 초 사이에 표본이 안 나온다).
# (하위 쿼리 별칭을 열 이름 t와 다르게 — 같으면 row_to_json(t)이 열을 가리켜 매번 오류였다, 스모크 실측)
# 출력: {"t":…, "lock_waiting":락 미획득 수, "lock_wait_sessions":wait_event_type=Lock 세션, "active":활성 세션, "advisory_held":잡힌 advisory 수}
lock_sampler() {
  remote "while :; do docker exec seatlab-db-1 psql -U seat -d seat -tAc \"SELECT row_to_json(s) FROM (SELECT clock_timestamp() AS t, (SELECT count(*) FROM pg_locks WHERE NOT granted) AS lock_waiting, (SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock') AS lock_wait_sessions, (SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND datname = 'seat') AS active, (SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted) AS advisory_held) s\" 2>/dev/null; sleep 0.5; done"
}

# 앱별 actuator 조회(ADR-003 앱 2대): nginx를 거치면 두 앱 중 하나만 읽힌다 — lb 컨테이너 안에서 앱 이름으로 직접 묻는다.
# 인자: 앱 서비스 이름(app|app2) 경로. 앱 1대 조건이면 BASE_URL로.
app_actuator() {
  if [[ "${COMPOSE_EXTRA:-}" == *two-apps* ]]; then
    remote "docker exec seatlab-lb-1 wget -qO- -T 10 http://$1:8101/actuator/$2"
  else
    actuator "$2"
  fi
}

db_scalar() {  # 인자: SQL — DB 컨테이너에서 한 값
  timeout 15 ssh -o BatchMode=yes -o ConnectTimeout=5 "$SERVER" "docker exec seatlab-db-1 psql -U seat -d seat -tAc \"$1\""
}
