#!/usr/bin/env bash
# ADR-001 원격 실행 공통 함수. k6 PC에서 source 해서 쓴다.
set -euo pipefail

SERVER="${SERVER:-jun@192.168.55.164}"
SERVER_HOST="${SERVER#*@}"
REMOTE_BASE="${REMOTE_BASE:-labs/seat-reservation-lab}"   # 서버 홈 기준 — 이 경로 밖은 쓰지 않는다
BASE_URL="${BASE_URL:-http://${SERVER_HOST}:8101}"
COMPOSE_FILE="k6/ADR-001/compose.yml"
# run.sh가 동결 사본에서 실행될 때는 원래 위치를 환경변수로 넘겨받는다
ADR_DIR="${ADR_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
REPO_ROOT="${REPO_ROOT:-$(cd "$ADR_DIR/../.." && pwd)}"

log() { echo "[$(date '+%F %T')] $*" >&2; }

remote() { ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 "$SERVER" "$@"; }

# 측정 대상 = 로컬 커밋 하나. 그 트리를 그대로 서버에 푼다 (GitHub 경유 없음).
deploy_sha() {
  local sha="$1"
  if remote "test -f $REMOTE_BASE/$sha/.deployed"; then return 0; fi
  log "deploy $sha → $SERVER:$REMOTE_BASE/$sha"
  git -C "$REPO_ROOT" archive --format=tar "$sha" | remote "mkdir -p $REMOTE_BASE/$sha && tar -x -C $REMOTE_BASE/$sha && touch $REMOTE_BASE/$sha/.deployed"
}

# 자원 단계·설정을 환경변수로 넘겨 기동. 인자: sha, 나머지는 KEY=VALUE
compose_up() {
  local sha="$1"; shift
  log "compose up ($sha) $*"
  remote "cd $REMOTE_BASE/$sha && env $* docker compose -p seatlab -f $COMPOSE_FILE up -d --build --force-recreate" >&2
}

compose_down() {
  local sha="$1"
  log "compose down ($sha)"
  remote "cd $REMOTE_BASE/$sha && docker compose -p seatlab -f $COMPOSE_FILE down -v --remove-orphans" >&2 || true
}

wait_health() {
  local deadline=$((SECONDS + ${1:-300}))
  until curl -fsS --max-time 5 "$BASE_URL/actuator/health" 2>/dev/null | grep -q '"UP"'; do
    if (( SECONDS > deadline )); then log "health timeout"; return 1; fi
    sleep 2
  done
}

# 모든 HTTP 호출에 상한을 둔다 — 응답 없는 서버에 매달려 무인 실행이 기록 없이 멈추지 않게.
reset_db() {  # 인자: schedules seatsPerSchedule
  curl -fsS --max-time 120 -X POST "$BASE_URL/internal/reset?schedules=$1&seatsPerSchedule=$2"
}

consistency() {  # 인자: graceSeconds(선택) · 환경 CONSISTENCY_TIMEOUT(기본 60s — 부하 중 폴링용)
  curl -fsS --max-time "${CONSISTENCY_TIMEOUT:-60}" "$BASE_URL/internal/consistency${1:+?graceSeconds=$1}"
}

counts() {  # 인자: seatStatus(true|false, 선택)
  curl -fsS --max-time 10 "$BASE_URL/internal/counts${1:+?seatStatus=$1}"
}

actuator() {  # 인자: 경로
  curl -fsS --max-time 10 "$BASE_URL/actuator/$1"
}
