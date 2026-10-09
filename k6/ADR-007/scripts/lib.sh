#!/usr/bin/env bash
# ADR-007 원격 실행 공통 함수(ADR-006 lib.sh 복사 + 대기 이벤트 표본 db_sampler · DB 설정 실제 값 pg_settings). k6 PC에서 source 해서 쓴다.
set -euo pipefail

SERVER="${SERVER:-jun@192.168.55.164}"
SERVER_HOST="${SERVER#*@}"
REMOTE_BASE="${REMOTE_BASE:-labs/seat-reservation-lab}"   # 서버 홈 기준 — 이 경로 밖은 쓰지 않는다
BASE_URL="${BASE_URL:-http://${SERVER_HOST}:8101}"
COMPOSE_FILE="k6/ADR-007/compose.yml"
# 앱 2대 조건이면 run.sh가 COMPOSE_EXTRA="-f k6/ADR-007/compose.two-apps.yml"을 넘긴다(ADR-007 캠페인은 쓰지 않는다)
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

# 스모크 전용(--worktree): 앱은 커밋 <sha> 그대로, 하네스(k6/ADR-007 — results 제외)만 작업트리에서 덮어 푼다.
# 배포 폴더 이름 = <sha>-wt-<하네스 트리 해시 8자리> — 커밋 배포(<sha>)와 섞이지 않고, 하네스가 바뀌면 새 폴더가 된다.
# 본측정(캠페인)은 이 경로를 쓰지 않는다 — 기록되는 SHA = 실행한 하네스 원칙(run.sh가 캠페인에서 --worktree를 거부).
worktree_harness_hash() {
  (cd "$REPO_ROOT" && find k6/ADR-007 -path k6/ADR-007/results -prune -o -name __pycache__ -prune -o -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -c1-8)
}
deploy_worktree() {  # 인자: sha 배포폴더이름
  local sha="$1" id="$2" i
  for i in 1 2 3; do
    if remote "test -f $REMOTE_BASE/$id/.deployed" 2>/dev/null; then return 0; fi
    log "deploy $sha + worktree k6/ADR-007 → $SERVER:$REMOTE_BASE/$id (attempt $i)"
    if { git -C "$REPO_ROOT" archive --format=tar "$sha" -- . ":(exclude,glob)k6/*/results/**" | remote "rm -rf $REMOTE_BASE/.tmp-$id && mkdir -p $REMOTE_BASE/.tmp-$id && tar -x -C $REMOTE_BASE/.tmp-$id"; } \
       && tar -C "$REPO_ROOT" --exclude=k6/ADR-007/results --exclude=__pycache__ -cf - k6/ADR-007 \
          | remote "rm -rf $REMOTE_BASE/.tmp-$id/k6/ADR-007 && tar -x -C $REMOTE_BASE/.tmp-$id && touch $REMOTE_BASE/.tmp-$id/.deployed && rm -rf $REMOTE_BASE/$id && mv $REMOTE_BASE/.tmp-$id $REMOTE_BASE/$id"; then
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
  remote "cd $REMOTE_BASE/$sha && docker compose -p seatlab -f $COMPOSE_FILE -f k6/ADR-007/compose.two-apps.yml down -v --remove-orphans" >&2 || true
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
# 출력 1: {"t":…, "lock_waiting":락 미획득 수, "lock_wait_sessions":wait_event_type=Lock 세션, "active":활성 세션, "advisory_held":잡힌 advisory 수}
# ADR-007 대기 이벤트(명세 §9.3): 같은 주기·같은 SSH·같은 psql 호출 하나에 UNION ALL로 둘째 행을 붙인다(폴러 부하를 늘리지 않게 — 프로세스·연결 추가 없음).
# 출력 2: {"t":…, "waits":{"<wait_event_type>:<wait_event>": 세션 수}, "bg":{"<backend_type>": "<type>:<event>"}, "bgwriter":{…}}
#   waits    = seat DB의 활성 클라이언트 세션(state='active', 이 표본 세션 자신 제외)의 대기 이벤트별 개수. 대기 없음(CPU에서 실행 중) = "CPU:-"
#   bg       = 백그라운드 프로세스(checkpointer·walwriter·bgwriter 등 — state가 없어 waits에 안 든다)의 현재 대기 이벤트. 체크포인터가 쓰는 중이면
#              IO:DataFileWrite·Timeout:CheckpointWriteDelay 등, 쉬면 Activity:CheckpointerMain — 체크포인트 진행을 0.5초 분해능으로 본다
#   bgwriter = pg_stat_bgwriter 누적값(PG16 — 체크포인트 수·버퍼. pg_stat_checkpointer는 PG17부터라 없다). 체크포인터는 이 통계를 체크포인트 끝 등에
#              몰아 반영하므로 진행 중 여부는 bg·DB 로그(log_checkpoints)로 본다
# 받는 쪽(run.sh start_pollers)이 키로 나눠 timeline-locks.jsonl·timeline-waits.jsonl에 쓴다.
# 인자: 서버 쪽 사본 경로(REMOTE_BASE 아래 — run.sh가 정한다). 서버도 같은 줄을 그 파일에 덧붙인다(tee -a) — 흘려받기가 도중에 끊겨도
# 회차 끝에 서버 사본으로 timeline-locks/waits를 다시 만든다(run.sh recover_samples). 스모크에서 흘려받기만 k6 +240~261초에 멈춘 원인은 받는 쪽 mawk의
# 파이프 입력 블록 버퍼링이었다(리뷰 A11 — run.sh start_pollers가 bash read 루프로 바꿨다). 서버 사본 복구는 보조 장치로 남긴다.
db_sampler() {
  local copy="$1"
  remote "mkdir -p \"\$(dirname $copy)\" && while :; do docker exec seatlab-db-1 psql -U seat -d seat -tAc \"SELECT row_to_json(s) FROM (SELECT clock_timestamp() AS t, (SELECT count(*) FROM pg_locks WHERE NOT granted) AS lock_waiting, (SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock') AS lock_wait_sessions, (SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND datname = 'seat') AS active, (SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted) AS advisory_held) s UNION ALL SELECT row_to_json(w) FROM (SELECT clock_timestamp() AS t, (SELECT coalesce(json_object_agg(k, n), '{}') FROM (SELECT coalesce(wait_event_type, 'CPU') || ':' || coalesce(wait_event, '-') AS k, count(*) AS n FROM pg_stat_activity WHERE state = 'active' AND datname = 'seat' AND pid <> pg_backend_pid() GROUP BY 1) a) AS waits, (SELECT coalesce(json_object_agg(backend_type, coalesce(wait_event_type, 'CPU') || ':' || coalesce(wait_event, '-')), '{}') FROM pg_stat_activity WHERE backend_type IN ('checkpointer', 'walwriter', 'background writer', 'autovacuum worker')) AS bg, (SELECT row_to_json(b) FROM (SELECT checkpoints_timed, checkpoints_req, checkpoint_write_time, checkpoint_sync_time, buffers_checkpoint, buffers_clean, buffers_backend, buffers_backend_fsync, buffers_alloc FROM pg_stat_bgwriter) b) AS bgwriter) w\" 2>/dev/null | tee -a $copy; sleep 0.5; done"
}

# ADR-007 시계 차(리뷰 A3): 서버 시계 − k6 PC 시계(초). 벽 시각·k6 구간은 PC 시계(k6 CSV·date -Is), 대기 표본(clock_timestamp())·DB 로그는 서버 시계다.
# SSH 연결 하나를 열어 둔 채(접속·인증 시간이 왕복에 섞이지 않게) 줄 하나를 보내고 서버가 date +%s.%N으로 답하게 5번 묻는다 —
# 왕복이 가장 짧은 표본의 offset = 서버 시각 − (보낸 시각 + 받은 시각) / 2 (오차 ≤ 그 왕복 / 2 — rtt_s로 함께 남긴다). 출력 한 줄 JSON, 실패면 비어 있다
clock_offset() {
  python3 - "$SERVER" <<'PY'
import json, subprocess, sys, time
p = subprocess.Popen(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", sys.argv[1], "while read -r _; do date +%s.%N; done"],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
best = None
try:
    for _ in range(5):
        t0 = time.time(); p.stdin.write("x\n"); p.stdin.flush()
        line = p.stdout.readline(); t1 = time.time()
        if not line.strip():
            break
        s = float(line)
        if best is None or t1 - t0 < best[1]:
            best = (s - (t0 + t1) / 2, t1 - t0, t0)
except (OSError, ValueError):
    best = None
finally:
    try:
        p.stdin.close(); p.wait(timeout=10)
    except Exception:
        p.kill()
if best:
    print(json.dumps({"offset_s": round(best[0], 4), "rtt_s": round(best[1], 4), "at": best[2]}))
PY
}

# ADR-007 DB 설정 실제 값(명세 §9.3 — SHOW와 같은 값: current_setting은 단위가 붙은 사람이 읽는 형태, 예 5min·1GB·128MB) — 한 줄 JSON
pg_settings() {
  db_scalar "SELECT json_build_object('checkpoint_timeout', current_setting('checkpoint_timeout'), 'max_wal_size', current_setting('max_wal_size'), 'shared_buffers', current_setting('shared_buffers'), 'log_checkpoints', current_setting('log_checkpoints'), 'track_io_timing', current_setting('track_io_timing'), 'track_wal_io_timing', current_setting('track_wal_io_timing'), 'server_version', current_setting('server_version'))"
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

# ADR-006 서버 I/O 스냅숏(ADR-006 명세 §9.3 — 그대로): k6 직전·직후에 한 번씩 — 요약기(summarize.py io_delta)가 차분을 낸다.
# SSH 한 번에 서버에서 (1) PostgreSQL 누적 통계 (2) /proc/diskstats(DB 데이터가 있는 파일시스템의 장치와 그 아래 물리 장치)를 함께 읽는다.
#   pg.wal      = pg_stat_wal 한 행(PG16: wal_records·wal_fpi·wal_bytes·wal_buffers_full·wal_write·wal_sync·wal_write_time·wal_sync_time ms)
#   pg.io       = pg_stat_io 전 행(키 = backend_type/object/context — reads·writes·extends·fsyncs·writebacks·hits·evictions + *_time ms).
#                 PG16의 pg_stat_io에는 WAL I/O가 없다(관계 파일만 — WAL은 pg_stat_wal)
#   pg.db       = pg_stat_database(seat)의 커밋·롤백·블록 · pg.bgwriter = pg_stat_bgwriter(PG16: 체크포인트 수·쓰기/동기화 시간 포함)
#   pg.settings = 타이밍·WAL 설정 실제 값(track_io_timing·track_wal_io_timing이 off면 시간 열이 0 — 회차 판정 io-timing-off)
#   disk        = 장치별 /proc/diskstats 필드(커널 문서 iostats.rst 순서) — 서버 전체 I/O(이 실험 밖 프로세스 포함)
# DB 장치 = Docker 데이터 루트(볼륨 pgdata가 그 아래)의 파일시스템 원본 → lsblk -s로 그 아래 장치(LVM이면 dm → 파티션 → 디스크)까지.
# 출력 한 줄 JSON(실패하면 비거나 깨진 JSON — rep_status가 invalid-io-*로 드러낸다)
io_snapshot() {
  timeout 30 ssh -o BatchMode=yes -o ConnectTimeout=5 "$SERVER" bash -s <<'REMOTE'
set -euo pipefail
src=$(findmnt -n -o SOURCE --target "$(docker info -f '{{.DockerRootDir}}')")
devs=$(lsblk -s -no KNAME "$src" | tr '\n' ' ')
devj=$(lsblk -s -no KNAME,TYPE "$src" | awk '{printf "%s{\"name\":\"%s\",\"type\":\"%s\"}", (n++ ? "," : ""), $1, $2}'); devj="[$devj]"
t0=$(date +%s.%N)
pg=$(docker exec -i seatlab-db-1 psql -U seat -d seat -tAq -v ON_ERROR_STOP=1 <<'SQL'
SELECT json_build_object(
  'now', now(),
  'wal', (SELECT row_to_json(w) FROM pg_stat_wal w),
  'io', (SELECT json_object_agg(backend_type || '/' || object || '/' || context,
           json_build_object('reads', reads, 'read_time', read_time, 'writes', writes, 'write_time', write_time,
                             'writebacks', writebacks, 'writeback_time', writeback_time, 'extends', extends, 'extend_time', extend_time,
                             'fsyncs', fsyncs, 'fsync_time', fsync_time, 'hits', hits, 'evictions', evictions)) FROM pg_stat_io),
  'db', (SELECT json_build_object('xact_commit', xact_commit, 'xact_rollback', xact_rollback, 'blks_read', blks_read, 'blks_hit', blks_hit)
         FROM pg_stat_database WHERE datname = 'seat'),
  'bgwriter', (SELECT row_to_json(b) FROM pg_stat_bgwriter b),
  'settings', (SELECT json_object_agg(name, setting) FROM pg_settings
               WHERE name IN ('track_io_timing', 'track_wal_io_timing', 'wal_sync_method', 'synchronous_commit', 'fsync',
                              'full_page_writes', 'wal_level', 'max_wal_size', 'checkpoint_timeout', 'shared_buffers', 'server_version')));
SQL
)
disk=$(awk -v devs=" $devs " 'index(devs, " " $3 " ") {
  printf "%s\"%s\":{\"reads\":%s,\"read_merges\":%s,\"read_sectors\":%s,\"read_ms\":%s,\"writes\":%s,\"write_merges\":%s,\"write_sectors\":%s,\"write_ms\":%s,\"in_flight\":%s,\"io_ms\":%s,\"weighted_ms\":%s,\"flushes\":%s,\"flush_ms\":%s}",
    (n++ ? "," : ""), $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, ($19 == "" ? "null" : $19), ($20 == "" ? "null" : $20) }' /proc/diskstats)
t1=$(date +%s.%N)
printf '{"t0":%s,"t1":%s,"fs_source":"%s","devices":%s,"pg":%s,"disk":{%s}}\n' "$t0" "$t1" "$src" "$devj" "$pg" "$disk"
REMOTE
}
