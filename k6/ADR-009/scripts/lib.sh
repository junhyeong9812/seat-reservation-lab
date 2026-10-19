#!/usr/bin/env bash
# ADR-009 원격 실행 공통 함수(ADR-008 lib.sh 복사 + F 오버레이 내리기 · 대기 표본 출처 구분 by_src · I/O 스냅숏 MultiXact(pg_stat_slru·mxid) · 카운터 어긋남 수집·주입·마감 임박 셀 함수 — 파일 끝).
# 그 앞: ADR-008 원격 실행 공통 함수(ADR-007 lib.sh 복사 — 하네스 경로만 k6/ADR-008. 그 앞: ADR-006 lib.sh 복사 + 대기 이벤트 표본 db_sampler · DB 설정 실제 값 pg_settings). k6 PC에서 source 해서 쓴다.
set -euo pipefail

SERVER="${SERVER:-jun@192.168.55.164}"
SERVER_HOST="${SERVER#*@}"
REMOTE_BASE="${REMOTE_BASE:-labs/seat-reservation-lab}"   # 서버 홈 기준 — 이 경로 밖은 쓰지 않는다
BASE_URL="${BASE_URL:-http://${SERVER_HOST}:8101}"
RECONCILER_URL="${RECONCILER_URL:-http://${SERVER_HOST}:8102}"   # ADR-009 F(A+F) 대조 프로세스 — compose.reconciler.yml
COMPOSE_FILE="k6/ADR-009/compose.yml"
# ADR-009: 앱 2대 조건은 없다(--apps 없음). A+F면 run.sh가 COMPOSE_EXTRA="-f k6/ADR-009/compose.reconciler.yml", tmpfs면 compose.tmpfs.yml을 더한다
COMPOSE_EXTRA="${COMPOSE_EXTRA:-}"
# ADR-011 서버 측정기 오버레이 — compose_up에는 넣지 않는다(회차 시작에 함께 뜨지 않게). probe_server_up이 주 k6 직전에 이 서비스 하나만 띄우고, compose_down은 늘 함께 내린다
PROBE_FILE="k6/ADR-009/compose.probe.yml"
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

# 스모크 전용(--worktree): 앱은 커밋 <sha> 그대로, 하네스(k6/ADR-009 — results 제외)만 작업트리에서 덮어 푼다.
# 배포 폴더 이름 = <sha>-wt-<하네스 트리 해시 8자리> — 커밋 배포(<sha>)와 섞이지 않고, 하네스가 바뀌면 새 폴더가 된다.
# 본측정(캠페인)은 이 경로를 쓰지 않는다 — 기록되는 SHA = 실행한 하네스 원칙(run.sh가 캠페인에서 --worktree를 거부).
worktree_harness_hash() {
  (cd "$REPO_ROOT" && find k6/ADR-009 -path k6/ADR-009/results -prune -o -name __pycache__ -prune -o -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -c1-8)
}
deploy_worktree() {  # 인자: sha 배포폴더이름
  local sha="$1" id="$2" i
  for i in 1 2 3; do
    if remote "test -f $REMOTE_BASE/$id/.deployed" 2>/dev/null; then return 0; fi
    log "deploy $sha + worktree k6/ADR-009 → $SERVER:$REMOTE_BASE/$id (attempt $i)"
    if { git -C "$REPO_ROOT" archive --format=tar "$sha" -- . ":(exclude,glob)k6/*/results/**" | remote "rm -rf $REMOTE_BASE/.tmp-$id && mkdir -p $REMOTE_BASE/.tmp-$id && tar -x -C $REMOTE_BASE/.tmp-$id"; } \
       && tar -C "$REPO_ROOT" --exclude=k6/ADR-009/results --exclude=__pycache__ -cf - k6/ADR-009 \
          | remote "rm -rf $REMOTE_BASE/.tmp-$id/k6/ADR-009 && tar -x -C $REMOTE_BASE/.tmp-$id && touch $REMOTE_BASE/.tmp-$id/.deployed && rm -rf $REMOTE_BASE/$id && mv $REMOTE_BASE/.tmp-$id $REMOTE_BASE/$id"; then
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
  local sha="$1" i
  # ADR-007 후속(리뷰 rv8 A2): 3번까지 다시 하고, 내린 뒤 DB 볼륨(seatlab_pgdata — tmpfs 회차 뒤 다음 회차가 같은 볼륨을 이어 쓰지 않게)과
  # DB 컨테이너가 정말 없는지 확인한다. 끝내 남으면 1(run.sh가 그 회차를 compose-down-failed로 끊는다 — 다음 회차는 이 확인을 다시 통과해야 뜬다)
  for i in 1 2 3; do
    log "compose down ($sha) (attempt $i)"
    # ADR-009: F 오버레이(reconciler 컨테이너)까지 함께 지정해야 내려간다 — 다음 회차가 F가 아니어도 남지 않게(--remove-orphans가 보조)
    # ADR-011: 서버 측정기 오버레이(compose.probe.yml — probe 컨테이너)도 함께 지정한다 — 회차 끝 수거가 실패해도 다음 회차 앞에서 내려간다
    remote "cd $REMOTE_BASE/$sha && docker compose -p seatlab -f $COMPOSE_FILE -f k6/ADR-009/compose.reconciler.yml -f $PROBE_FILE down -v --remove-orphans" >&2
    # DB 볼륨·db 컨테이너에 더해 reconciler 컨테이너도 없어야 한다(F 회차 뒤 다음 회차에 대조 프로세스가 남아 같은 DB를 고치지 않게) · ADR-011 probe 컨테이너도
    if remote "! docker volume inspect seatlab_pgdata > /dev/null 2>&1 && [ -z \"\$(docker ps -aq --filter label=com.docker.compose.project=seatlab --filter label=com.docker.compose.service=db)\" ] && [ -z \"\$(docker ps -aq --filter label=com.docker.compose.project=seatlab --filter label=com.docker.compose.service=reconciler)\" ] && [ -z \"\$(docker ps -aq --filter label=com.docker.compose.project=seatlab --filter label=com.docker.compose.service=probe)\" ]" >&2; then
      return 0
    fi
    log "compose down: seatlab_pgdata 볼륨 또는 db 컨테이너가 남았거나 확인 실패 — retry in 10s"; sleep 10
  done
  return 1
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
# 출력 2: {"t":…, "waits":{"<wait_event_type>:<wait_event>": 세션 수}, "bg":{"<backend_type>": "<type>:<event>"}, "bgwriter":{…}, "by_src":{"<출처>|<type>:<event>": 세션 수}}
#   by_src(ADR-009 IV-009-7 · L1-16/L2-11) = 같은 활성 세션을 출처로 나눈 것: F = application_name seatlab-reconciler(별도 컨테이너) ·
#            C = 앱 풀을 같이 쓰는 대조 세션 — application_name이 앱과 같아 문장 표지로 가른다(청크 'cnt <> actual' · 행 없음 NOT EXISTS · 바퀴 기록 · 복구 lock_timeout) ·
#            cleanup = 정리 ①② 문장 · app = 그 밖의 seatlab-app 세션 · other = 하네스 psql 등. 표지는 표본 순간 실행 중인 문장 기준(복구 트랜잭션의 다른 문장은 app으로 셀 수 있다 — 근사)
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
  # 서버 쪽 루프(리뷰 R1): 표본을 변수로 받아 ① 서버 사본에 먼저 덧붙이고 ② 표준 출력(SSH → k6 PC)으로 낸다. ②가 실패하면(받는 쪽이 꺼져 SSH가 닫힘 — EPIPE·SIGPIPE)
  # 루프가 끝난다 — 재연결 뒤 옛 루프가 남아 같은 사본에 계속 쓰지 않게. 표본이 비어 ②가 없는 동안에도 부모(sshd 세션)가 사라지면 끝낸다(kill -0 $PPID).
  # 사본은 ②보다 먼저 쓰므로 끊긴 순간까지의 줄이 서버에 남는다
  remote "mkdir -p \"\$(dirname $copy)\" && while kill -0 \$PPID 2>/dev/null; do o=\$(docker exec seatlab-db-1 psql -U seat -d seat -tAc \"SELECT row_to_json(s) FROM (SELECT clock_timestamp() AS t, (SELECT count(*) FROM pg_locks WHERE NOT granted) AS lock_waiting, (SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock') AS lock_wait_sessions, (SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND datname = 'seat') AS active, (SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted) AS advisory_held) s UNION ALL SELECT row_to_json(w) FROM (SELECT clock_timestamp() AS t, (SELECT coalesce(json_object_agg(k, n), '{}') FROM (SELECT coalesce(wait_event_type, 'CPU') || ':' || coalesce(wait_event, '-') AS k, count(*) AS n FROM pg_stat_activity WHERE state = 'active' AND datname = 'seat' AND pid <> pg_backend_pid() GROUP BY 1) a) AS waits, (SELECT coalesce(json_object_agg(backend_type, coalesce(wait_event_type, 'CPU') || ':' || coalesce(wait_event, '-')), '{}') FROM pg_stat_activity WHERE backend_type IN ('checkpointer', 'walwriter', 'background writer', 'autovacuum worker')) AS bg, (SELECT row_to_json(b) FROM (SELECT checkpoints_timed, checkpoints_req, checkpoint_write_time, checkpoint_sync_time, buffers_checkpoint, buffers_clean, buffers_backend, buffers_backend_fsync, buffers_alloc FROM pg_stat_bgwriter) b) AS bgwriter, (SELECT coalesce(json_object_agg(k, n), '{}') FROM (SELECT CASE WHEN application_name = 'seatlab-reconciler' THEN 'F' WHEN query LIKE '%cnt <> actual%' OR query LIKE '%FROM user_hold_quota q WHERE q.schedule_id = h.schedule_id AND q.user_id = h.user_id%' OR query LIKE '%FROM user_hold_quota q WHERE q.schedule_id = r.schedule_id AND q.user_id = r.user_id%' OR query LIKE '%counter_reconcile_run%' OR query LIKE '%lock_timeout%' THEN 'C' WHEN query LIKE '%DELETE FROM user_hold_quota%' OR query LIKE '%counter_cleanup_run%' THEN 'cleanup' WHEN application_name = 'seatlab-app' THEN 'app' ELSE 'other' END || '|' || coalesce(wait_event_type, 'CPU') || ':' || coalesce(wait_event, '-') AS k, count(*) AS n FROM pg_stat_activity WHERE state = 'active' AND datname = 'seat' AND pid <> pg_backend_pid() GROUP BY 1) bs) AS by_src) w\" 2>/dev/null); if [ -n \"\$o\" ]; then printf '%s\\n' \"\$o\" >> $copy; printf '%s\\n' \"\$o\" || break; fi; sleep 0.5; done"
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

# 앱별 actuator 조회. 인자: 앱 서비스 이름(app|reconciler) 경로.
app_actuator() {   # ADR-009: 앱 2대 없음 — app은 BASE_URL, reconciler(F 컨테이너)는 RECONCILER_URL(8102)
  case "$1" in
    reconciler) curl -fsS --max-time 10 "$RECONCILER_URL/actuator/$2" ;;
    *) actuator "$2" ;;
  esac
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
#   pg.slru     = (ADR-009 L1-16·L2-11) pg_stat_slru 전 행(MultiXactOffset·MultiXactMember 등) — 마감 판정 FOR SHARE가 회차 행에 공유 잠금자를 늘려 MultiXact를 쓰는지
#   pg.mxid     = seat DB의 datminmxid·mxid_age(datminmxid) — datminmxid가 같으면 age 차분 = 그 사이 새로 쓴 MultiXact ID 수(바뀌었으면 차분 무효 — 요약기 표시)
#   pg.quota_table = user_hold_quota의 pg_stat_user_tables 누적값·크기(정리 비교 — 설계 §6)
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
  'slru', (SELECT json_object_agg(name, json_build_object('blks_zeroed', blks_zeroed, 'blks_hit', blks_hit, 'blks_read', blks_read, 'blks_written', blks_written,
                                                          'blks_exists', blks_exists, 'flushes', flushes, 'truncates', truncates)) FROM pg_stat_slru),
  'mxid', (SELECT json_build_object('datminmxid', datminmxid::text, 'age', mxid_age(datminmxid)) FROM pg_database WHERE datname = 'seat'),
  'quota_table', (SELECT json_build_object('n_tup_ins', n_tup_ins, 'n_tup_upd', n_tup_upd, 'n_tup_del', n_tup_del, 'n_tup_hot_upd', n_tup_hot_upd, 'n_dead_tup', n_dead_tup,
                                           'autovacuum_count', autovacuum_count, 'bytes', pg_total_relation_size('user_hold_quota'))
                  FROM pg_stat_user_tables WHERE relname = 'user_hold_quota'),
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

# ---- ADR-007 후속: 디스크 원인(C5 — docs/plans/2026-10-10/adr-007-disk-cause 명세 §2·§3·§9 task 01) ----------------------------------------
# 호스트 디스크 표본(host-sampler.sh — 서버 /proc/diskstats·/proc/meminfo·/proc/pressure/io 1초 + iostat -x 교차). 스크립트는 표준 입력으로 보낸다(서버에 배포 불필요 —
# 동결 사본 그대로라 기록된 하네스와 같다). 인자: 서버 쪽 사본 경로(jsonl) · iostat 사본 경로(txt) — 둘 다 REMOTE_BASE 아래. 장치 = HOST_DEVS(기본 nvme1n1 dm-0 —
# DB 볼륨이 있는 / = LVM ubuntu-lv = dm-0 → nvme1n1, 명세 §0 서버 확인. nvme0n1(Windows 디스크)은 읽지도 않는다 — 명세 §4)
LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_DEVS="${HOST_DEVS:-nvme1n1 dm-0}"   # 기본값 — 회차·fio마다 host_devices가 실제 장치로 바꾼다(리뷰 rv8 A4)
EXPECTED_DISK_MODEL="${EXPECTED_DISK_MODEL:-Samsung SSD 980 500GB}"   # 명세 §0 서버 확인 — 다르면 rep_status host-device-unexpected
# 표본 장치 정하기(리뷰 rv8 A4 — io_snapshot과 같은 방법): 대상 경로의 파일시스템 원본 → lsblk -s로 그 아래 장치 사슬(LVM dm → 파티션 → 디스크).
# 인자: docker(= Docker 데이터 루트 — DB 볼륨이 그 아래) 또는 서버 홈 기준 경로(fio-tmp). 한 줄 JSON:
#   {"target","fs_source","chain":[{"name","type"}],"sampled":[사슬 장치 이름 — 표본 대상],"disk":"<TYPE disk 장치>","model":"/sys/block/<disk>/device/model"}
# 모델은 사슬의 디스크 하나만 읽는다(다른 디스크는 읽지 않는다 — 명세 §4)
host_devices() {
  timeout 30 ssh -o BatchMode=yes -o ConnectTimeout=5 "$SERVER" bash -s -- "$1" <<'REMOTE'
set -uo pipefail
if [ "$1" = docker ]; then p=$(docker info -f '{{.DockerRootDir}}'); else p="$HOME/$1"; fi
src=$(findmnt -n -o SOURCE --target "$p") || exit 1
chain=$(lsblk -s -no KNAME,TYPE "$src") || exit 1
disk=$(printf '%s\n' "$chain" | awk '$2 == "disk" {print $1; exit}')
[ -n "$disk" ] || exit 1
model=$(sed 's/[[:space:]]*$//' "/sys/block/$disk/device/model" 2>/dev/null)
cj=$(printf '%s\n' "$chain" | awk 'NF {printf "%s{\"name\":\"%s\",\"type\":\"%s\"}", (n++ ? "," : ""), $1, $2}')
sj=$(printf '%s\n' "$chain" | awk 'NF {printf "%s\"%s\"", (n++ ? "," : ""), $1}')
printf '{"target":"%s","fs_source":"%s","chain":[%s],"sampled":[%s],"disk":"%s","model":"%s"}\n' "$p" "$src" "$cj" "$sj" "$disk" "$model"
REMOTE
}
host_sampler() {
  remote "bash -s -- '$1' '$2' $HOST_DEVS" < "$LIB_DIR/host-sampler.sh"
}
# 서버 쪽 호스트 표본 루프 정리(이 사본 경로의 루프만 — [b] 패턴은 pkill을 띄운 셸 자신의 명령줄과 맞지 않게). 루프가 끝나면 trap이 iostat도 끈다
host_sampler_kill() {
  remote "pkill -f '[b]ash -s -- $1 ' || true" > /dev/null 2>&1
}
# 회차(또는 fio) 끝: 서버 사본을 받아 흘려받은 줄보다 많으면 timeline-host.jsonl을 사본으로 다시 만든다(db_sampler recover_samples와 같은 규칙).
# iostat 사본은 그대로 받아 iostat-host.txt로. 결과 한 줄씩 host-recovery.txt — recovered-from-server a→b / stream-complete a (server b) / server-copy-unavailable ·
# iostat-copied n줄 / iostat-copy-unavailable. 서버 사본 둘은 지운다 — 지운 뒤에도 남거나 확인이 실패하면 errors.log 한 줄 + host-copy-not-removed(rep_status 사유)
# 인자: 출력폴더 사본 iostat사본
host_recover() {
  local dir="$1" copy="$2" io="$3" tmp="$1/.host-remote.jsonl" n r
  if remote "cat '$copy'" > "$tmp" 2>/dev/null && [[ -s "$tmp" ]]; then
    n=$(grep -c '^{"t"' "$dir/timeline-host.jsonl" 2>/dev/null); n=${n:-0}
    r=$(grep -c '^{"t"' "$tmp")
    if (( r > n )); then
      grep '^{"t"' "$tmp" > "$dir/timeline-host.jsonl"
      echo "$(date -Is) recovered-from-server host $n→$r" >> "$dir/host-recovery.txt"
    else
      echo "$(date -Is) stream-complete host $n (server $r)" >> "$dir/host-recovery.txt"
    fi
  else
    echo "$(date -Is) server-copy-unavailable" >> "$dir/host-recovery.txt"
  fi
  if remote "cat '$io'" > "$dir/iostat-host.txt" 2>/dev/null && [[ -s "$dir/iostat-host.txt" ]]; then
    echo "$(date -Is) iostat-copied $(wc -l < "$dir/iostat-host.txt") lines" >> "$dir/host-recovery.txt"
  else
    echo "$(date -Is) iostat-copy-unavailable" >> "$dir/host-recovery.txt"
  fi
  if ! remote "rm -f '$copy' '$io' && test ! -e '$copy' && test ! -e '$io'" > /dev/null 2>&1; then
    echo "$(date -Is) host sampler copy not removed: $copy $io" >> "$dir/errors.log"
    echo "$(date -Is) host-copy-not-removed $copy $io" >> "$dir/host-recovery.txt"
  fi
  rm -f "$tmp"
}

# DB 저장 위치·크기(명세 §5 tmpfs 확인 · §8 가정 2) — 한 줄 JSON. 모든 preset에서 회차 끝(k6 뒤)에 읽는다(기록만 — 판정은 tmpfs에서만: rep_status tmpfs-not-applied).
#   data_directory(SHOW) · fstype(컨테이너 안 stat -f — tmpfs면 "tmpfs") · findmnt(SOURCE FSTYPE SIZE USED OPTIONS — 없으면 빈 값) · df_bytes(size used avail)
#   db_bytes = pg_database_size('seat') · wal_bytes = pg_ls_waldir() 합 · cgroup = 컨테이너 memory.current·memory.peak·memory.max(바이트)·shmem(memory.stat — tmpfs 페이지)
#   cgroup.oom_kill = memory.events oom_kill(리뷰 rv8 A3) · oom_killed = docker inspect .State.OOMKilled
pg_storage() {
  timeout 60 ssh -o BatchMode=yes -o ConnectTimeout=5 "$SERVER" bash -s <<'REMOTE'
set -uo pipefail
c=seatlab-db-1
q() { docker exec "$c" psql -U seat -d seat -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
dd=$(q "SHOW data_directory")
fs=$(docker exec "$c" stat -f -c %T "$dd" 2>/dev/null | tr -d '[:space:]')
fm=$(docker exec "$c" findmnt -n -o SOURCE,FSTYPE,SIZE,USED,OPTIONS --target "$dd" 2>/dev/null | head -1 | tr -s ' ' | tr -d '"')
df=$(docker exec "$c" df -B1 --output=size,used,avail "$dd" 2>/dev/null | tail -1 | tr -s ' ' | sed 's/^ //')
db=$(q "SELECT pg_database_size('seat')")
wal=$(q "SELECT coalesce(sum(size), 0) FROM pg_ls_waldir()")
cg() { docker exec "$c" cat "/sys/fs/cgroup/$1" 2>/dev/null | tr -d '[:space:]'; }
cur=$(cg memory.current); peak=$(cg memory.peak); max=$(cg memory.max)
oomk=$(docker exec "$c" cat /sys/fs/cgroup/memory.events 2>/dev/null | sed -n 's/^oom_kill //p')
shm=$(docker exec "$c" cat /sys/fs/cgroup/memory.stat 2>/dev/null | sed -n 's/^shmem //p')
oom=$(docker inspect -f '{{.State.OOMKilled}}' "$c" 2>/dev/null)
num() { [[ "$1" =~ ^[0-9]+$ ]] && echo "$1" || echo null; }
set -- $df
printf '{"t":"%s","data_directory":"%s","fstype":"%s","findmnt":"%s","df_bytes":{"size":%s,"used":%s,"avail":%s},"db_bytes":%s,"wal_bytes":%s,"cgroup":{"current":%s,"peak":%s,"max":"%s","shmem":%s,"oom_kill":%s},"oom_killed":%s}\n' \
  "$(date -Is)" "$dd" "$fs" "$fm" "$(num "${1:-}")" "$(num "${2:-}")" "$(num "${3:-}")" "$(num "$db")" "$(num "$wal")" \
  "$(num "$cur")" "$(num "$peak")" "$max" "$(num "$shm")" "$(num "$oomk")" "$([[ "$oom" == true || "$oom" == false ]] && echo "$oom" || echo null)"
REMOTE
}

# ---- ADR-008 기본값 출처 증거(명세 §5 — 매수 방식을 주입하지 않고 떴는가) ----------------------------------------------------------------
# 앱 컨테이너에서 매수 방식 설정 흔적을 **개수만** 센다 — 값은 기록하지 않는다(환경에 DB_PASSWORD가 있다). 세는 일은 서버에서 하고 숫자만 받는다.
#   정규식 = 대소문자 무시 limit[._-]?strategy (relaxed binding 이름 SEAT_HOLD_LIMIT_STRATEGY·SEAT_HOLD_LIMITSTRATEGY, -Dseat.hold.limit-strategy=,
#            SPRING_APPLICATION_JSON의 limit-strategy·limitStrategy 키 모두 걸린다 — 리뷰 루프 1 C8)
#   env  = 환경변수 줄 전체(이름=값 — JAVA_TOOL_OPTIONS·SPRING_APPLICATION_JSON·SPRING_CONFIG_* 포함)에서의 등장 수
#   cmd  = .Path · .Args · .Config.Entrypoint · .Config.Cmd 에서의 등장 수(--seat.hold.limit-strategy= 같은 명령줄 인자)
#   spring_config = 이름이 SPRING_CONFIG_로 시작하는 환경변수 수(외부 설정 파일 위치 — 그 파일의 내용은 볼 수 없으므로 있는 것 자체를 드러낸다)
#   drift · drift_cmd = (ADR-009 기본값 조건 증거 — task 07a) 같은 줄들에서 counter[._-]?drift · sales[._-]?close 등장 수(환경 · 명령줄). 값은 역시 세지 않는다
# 인자: 컨테이너 이름 → 한 줄 JSON {"container","env","cmd","spring_config","drift","drift_cmd","present"} (조회 실패면 1 — run.sh가 limit-key-check-failed로 드러낸다)
limit_key_check() {
  local c="$1" out e m sc d dm
  out=$(remote "bash -s -- '$c'" <<'SH'
c="$1"
docker inspect "$c" > /dev/null 2>&1 || exit 3
docker inspect "$c" --format '{{range .Config.Env}}E {{.}}{{println}}{{end}}{{range .Config.Entrypoint}}C {{.}}{{println}}{{end}}{{range .Config.Cmd}}C {{.}}{{println}}{{end}}C {{.Path}}{{println}}{{range .Args}}C {{.}}{{println}}{{end}}' \
  | awk '{ l = tolower(substr($0, 3)); n = gsub(/limit[._-]?strategy/, "", l); k = gsub(/counter[._-]?drift|sales[._-]?close/, "", l)
           if (substr($0, 1, 2) == "E ") { e += n; d += k; if (substr($0, 3, 14) == "SPRING_CONFIG_") s++ } else { m += n; dm += k } }
         END { printf "%d %d %d %d %d\n", e, m, s, d, dm }'
SH
) || return 1
  read -r e m sc d dm <<< "$out"
  [[ "$e" =~ ^[0-9]+$ && "$m" =~ ^[0-9]+$ && "$sc" =~ ^[0-9]+$ && "$d" =~ ^[0-9]+$ && "$dm" =~ ^[0-9]+$ ]] || return 1
  jq -nc --arg c "$c" --argjson e "$e" --argjson m "$m" --argjson s "$sc" --argjson d "$d" --argjson dm "$dm" \
    '{container:$c, env:$e, cmd:$m, spring_config:$s, drift:$d, drift_cmd:$dm, present:(($e + $m) > 0)}'
}

# ---- ADR-008 설정 파일 정적 검사(리뷰 루프 1 C4 — 회차 전에 한 번, 측정 대상 앱 SHA 기준) -------------------------------------------------
# 매수 방식은 src/main/resources/application.yml만 정할 수 있고 그 값은 counter-upsert-precheck여야 한다. 프로필 설정(loadtest 등)이 덮으면 '기본값'을 잰 것이 아니다.
#   ① <SHA>의 src/main/resources(새 구조는 main-app/src/main/resources) 아래 application*.{yml,yaml,properties}(하위 폴더 포함) 중 application.yml이 아닌 파일에서 limit[._-]?strategy(대소문자 무시, 주석 제외)가
#      한 번이라도 나오면 limit-set-by-profile
#   ② application.yml: 그 정규식이 나오는 (주석 아닌) 줄이 정확히 하나이고 그 줄이 `limit-strategy: counter-upsert-precheck`가 아니면 limit-default-not-precheck
#   ③ 실제로 띄울 compose 파일들(인자)의 주석 아닌 줄에 SPRING_CONFIG_가 있으면 spring-config-override(외부 설정 위치 — 내용을 볼 수 없다)
#   ④ (리뷰 루프 2 D2) 같은 설정 파일들(application.yml 포함)에 spring.config.import · spring.config.location · spring.config.additional-location ·
#      spring.profiles.active · spring.profiles.include · spring.profiles.group.*가 있으면 spring-config-import — 다른 파일·프로필이 끼어들 수 있다
#      (YAML 중첩·문서 여러 개·relaxed 이름까지 펼쳐 보는 cfgkeys.py. 파싱 실패면 확인 실패)
# 인자: sha compose파일... (compose 파일은 로컬 경로 — 작업트리 스모크면 작업트리, 아니면 SHA에서 꺼낸 사본) → 한 줄 JSON {"sha","files","application_yml","spring_config","reasons"}
limit_config_check() {
  local sha="$1"; shift
  local f files="[]" n k app_lines app_ok=false sc=0 reasons=() cf res=src/main/resources core=()
  # 저장소 구조(task 04b): 그 SHA에 main-app/src/main/resources가 있으면 그쪽(새 구조), 없으면 src/main/resources(옛 구조)
  [[ -n "$(git -C "$REPO_ROOT" ls-tree -d --name-only "$sha" -- main-app/src/main/resources 2>/dev/null)" ]] && res=main-app/src/main/resources core=(reconcile-core/src/main/resources)
  # 새 구조: reconcile-core도 main-app 런타임 classpath에 올라가므로 그 자원(application*)도 같이 본다(04b 리뷰)
  strip() { case "$1" in *.properties) grep -vE '^[[:space:]]*[#!]' ;; *) sed -E 's/(^|[[:space:]])#.*$//' ;; esac; }
  while IFS= read -r f; do
    [[ "$(basename "$f")" =~ ^application.*\.(ya?ml|properties)$ ]] || continue
    n=$(git -C "$REPO_ROOT" show "$sha:$f" | strip "$f" | grep -ciE 'limit[._-]?strategy')
    k=$(git -C "$REPO_ROOT" show "$sha:$f" | python3 -I "$LIB_DIR/cfgkeys.py" "$f" 2>/dev/null) || return 1
    [[ "$k" =~ ^[0-9]+$ ]] || return 1
    (( k == 0 )) || reasons+=("spring-config-import")
    files=$(jq -c --arg f "$f" --argjson n "${n:-0}" --argjson k "$k" '. + [{path:$f, matches:$n, config_keys:$k}]' <<< "$files")
    if [[ "$f" == "$res/application.yml" ]]; then
      app_lines=$(git -C "$REPO_ROOT" show "$sha:$f" | strip "$f" | grep -iE 'limit[._-]?strategy')
      if [[ "$(grep -c . <<< "$app_lines")" == 1 ]] && grep -qE '^[[:space:]]*limit-strategy:[[:space:]]*counter-upsert-precheck[[:space:]]*$' <<< "$app_lines"; then app_ok=true; fi
    elif (( n > 0 )); then
      reasons+=("limit-set-by-profile")
    fi
  done < <(git -C "$REPO_ROOT" ls-tree -r --name-only "$sha" -- "$res" ${core[@]+"${core[@]}"}) || return 1
  [[ "$files" != "[]" ]] || return 1   # 설정 파일을 하나도 못 찾으면 확인 실패(조용히 통과하지 않게)
  [[ "$app_ok" == true ]] || reasons+=("limit-default-not-precheck")
  for cf in "$@"; do
    [[ -s "$cf" ]] || return 1
    sc=$(( sc + $(sed -E 's/(^|[[:space:]])#.*$//' "$cf" | grep -c 'SPRING_CONFIG_') ))
  done
  (( sc == 0 )) || reasons+=("spring-config-override")
  jq -nc --arg sha "$sha" --argjson files "$files" --argjson ok "$app_ok" --argjson sc "$sc" --arg r "$(printf '%s\n' "${reasons[@]}" | sort -u | grep . | tr '\n' ' ')" \
    '{sha:$sha, files:$files, application_yml:{precheck:$ok}, spring_config:$sc, reasons:($r | split(" ") | map(select(. != "")))}'
}

# ---- ADR-009 카운터 어긋남(명세 §5·§10·§11 · 설계 §9) --------------------------------------------------------------------------------
# DB 한 번 묻기 — SQL은 표준 입력(여러 문장·트랜잭션 가능, ON_ERROR_STOP), 마지막 SELECT가 한 줄 JSON을 낸다. -q라 BEGIN·INSERT 등 명령 태그는 나오지 않는다.
# 실패(접속·SQL 오류·시간 초과)면 0이 아닌 종료 — 호출자가 사유로 드러낸다
db_json() {
  timeout "${DB_JSON_TIMEOUT:-120}" ssh -o BatchMode=yes -o ConnectTimeout=5 "$SERVER" "docker exec -i seatlab-db-1 psql -U seat -d seat -tAq -v ON_ERROR_STOP=1"
}

# 앱 지표 스냅숏(actsnap.py — 틱·어긋남·대조·정리·마감 판정·Hikari). 인자: app|reconciler → 한 줄 JSON(실패면 1)
drift_snap() {
  local url="$BASE_URL"; [[ "$1" == reconciler ]] && url="$RECONCILER_URL"
  python3 -I "$LIB_DIR/actsnap.py" "$url"
}

# 카운터 트리거(V8 — B) 존재: {"triggers":[이름…]} — 기동 검사와 같은 두 이름만 본다
db_triggers() {
  db_json <<'SQL'
SELECT json_build_object('triggers', (SELECT coalesce(json_agg(DISTINCT tgname), '[]') FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ('seat_hold_quota', 'reservation_quota')));
SQL
}

# 대조 첫 바퀴 기다리기(C·F — reconcile-missing의 근거): counter_reconcile_run에 source 행이 생길 때까지(최대 인자 초). 한 줄 JSON {"source","runs","waited_s"}
wait_reconcile_run() {  # 인자: source(reconcile-app|reconcile-proc) 최대초
  local src="$1" max="$2" t0=$SECONDS n=0 base
  # H-14: 부른 시각(= 시드 직후, DB 시계) 뒤에 시작한 바퀴만 센다 — 예열·시드 전 바퀴로 통과하지 않게
  base=$(db_scalar "SELECT extract(epoch FROM clock_timestamp())" 2>/dev/null | tr -d '[:space:]')
  [[ "$base" =~ ^[0-9.]+$ ]] || { printf '{"source":"%s","runs":null,"waited_s":0,"error":"baseline"}\n' "$src"; return 1; }
  while (( SECONDS - t0 <= max )); do
    n=$(db_scalar "SELECT count(*) FROM counter_reconcile_run WHERE source = '$src' AND started_at >= to_timestamp($base)" 2>/dev/null | tr -d '[:space:]')
    [[ "$n" =~ ^[0-9]+$ ]] && (( n > 0 )) && break
    sleep 3
  done
  [[ "$n" =~ ^[0-9]+$ ]] || n=null
  printf '{"source":"%s","runs":%s,"waited_s":%d,"after_epoch":%s}\n' "$src" "$n" $((SECONDS - t0)) "$base"
}

# V7 기록 테이블 전부(회차마다 DB 볼륨을 새로 만들므로 이 회차의 행뿐이다 — 시드 초기화는 V7을 비우지 않으므로 요약기가 k6 구간으로 다시 거른다).
# counter_drift_log는 페이지로 전부(N-5 — 총 수 함께). 시각은 DB(서버) 시계
drift_db_dump() {
  # N-5: counter_drift_log는 id 순 페이지(DRIFT_PAGE, 기본 20,000행)로 끝까지 모은다 — 총수(먼저 셈)보다 적게 모이면 호출자(rep_status)가 drift-db-incomplete(수집 실패)
  local head page last=0 n tmp; tmp=$(mktemp)
  head=$(db_json <<'SQL'
SELECT json_build_object(
  'db_now', clock_timestamp(),
  'drift_log_total', (SELECT count(*) FROM counter_drift_log),
  'reconcile_runs', (SELECT coalesce(json_agg(r ORDER BY r.id), '[]') FROM counter_reconcile_run r),
  'cleanup_runs', (SELECT coalesce(json_agg(c ORDER BY c.id), '[]') FROM counter_cleanup_run c));
SQL
) || { rm -f "$tmp"; return 1; }
  echo '[]' > "$tmp"
  local pages=0
  while :; do
    page=$(db_json <<SQL
SELECT coalesce(json_agg(d ORDER BY d.id), '[]') FROM (SELECT * FROM counter_drift_log WHERE id > $last ORDER BY id LIMIT ${DRIFT_PAGE:-20000}) d;
SQL
) || { rm -f "$tmp"; return 1; }
    n=$(jq 'length' <<< "$page") || { rm -f "$tmp"; return 1; }
    pages=$((pages + 1))
    (( n > 0 )) && { jq -c --argjson p "$page" '. + $p' "$tmp" > "$tmp.n" && mv "$tmp.n" "$tmp"; last=$(jq '.[-1].id' <<< "$page"); }
    (( n < ${DRIFT_PAGE:-20000} )) && break
  done
  jq -c --slurpfile l "$tmp" --argjson pages "$pages" '. + {drift_log: $l[0], drift_log_pages: $pages}' <<< "$head"
  rm -f "$tmp"
}

# 어긋남 주입(설계 §9 주입기 — 부하 중 DB 직접 조작, 한 트랜잭션). 인자: D L1 L2 H 제외쌍("(s,u),(s,u)" 또는 빈 값)
#   D  쿼터 행 삭제     — 반드시 만료될 홀드(held_at <= now − INJECT_HELD_MIN_S(기본 250 = THINK_MAX 240 + 여유: 확정하는 세션은 240초 안에 확정한다) ·
#                         expires_at >= now + INJECT_EXP_MARGIN_S(기본 15: 만료 배치가 아직 쓸지 않음))의 쌍 → 만료 감소 행 없음(정지 경로)
#   L1 cnt − 1(음수)   — 같은 '만료될' 쌍 → 만료 감소가 CHECK(23514)에 걸림(정지 경로)
#   L2 cnt − 1(조용함) — CONFIRMED 1 · 홀드 0인 쌍(1 → 0) → A는 못 본다(대조만)
#   H  cnt + 1          — 아무 cnt 1 쌍 → 조용함
#   모두 열린 회차(starts_at > now)의 cnt = 1 쌍, 종류끼리·이전 주입과 겹치지 않는다. 고르는 순서는 결정적(D·L1 = 홀드 id · L2 = 예약 id · H = (회차, 사용자)).
#   고른 뒤 (회차, 사용자) 순서로 FOR UPDATE 잠그고 조건을 다시 본다(H-7 — 바뀐 쌍은 dropped_after_lock). 변경은 상대값(cnt ± 1), 종류별 영향 행 수(applied)를 함께 낸다.
#   출력 한 줄 JSON(+ applied·dropped_after_lock) {"at","at_epoch","counts":{…},"pairs":[{kind,schedule_id,user_id,cnt_before,held_at,expires_at}]}
inject_drift() {
  local d="$1" l1="$2" l2="$3" h="$4" excl="${5:-}" held="${INJECT_HELD_MIN_S:-250}" margin="${INJECT_EXP_MARGIN_S:-15}"
  [[ "$d$l1$l2$h$held$margin" =~ ^[0-9]+$ ]] || return 2
  [[ -z "$excl" || "$excl" =~ ^(\([0-9]+,[0-9]+\),?)+$ ]] || return 2
  db_json <<SQL
BEGIN;
CREATE TEMP TABLE inj (kind text, schedule_id bigint, user_id bigint, cnt_before int, held_at timestamptz, expires_at timestamptz) ON COMMIT DROP;
CREATE TEMP TABLE excl (schedule_id bigint, user_id bigint) ON COMMIT DROP;
$( [[ -n "$excl" ]] && echo "INSERT INTO excl VALUES ${excl%,};" )
INSERT INTO inj
SELECT 'D', q.schedule_id, q.user_id, q.cnt, h.held_at, h.expires_at
FROM seat_hold h JOIN user_hold_quota q ON q.schedule_id = h.schedule_id AND q.user_id = h.user_id
JOIN product_schedule s ON s.id = h.schedule_id AND s.starts_at > now()
WHERE h.held_at <= now() - make_interval(secs => $held) AND h.expires_at >= now() + make_interval(secs => $margin) AND q.cnt = 1
  AND NOT EXISTS (SELECT 1 FROM excl e WHERE e.schedule_id = q.schedule_id AND e.user_id = q.user_id)
ORDER BY h.id LIMIT $d;
INSERT INTO inj
SELECT 'L1', q.schedule_id, q.user_id, q.cnt, h.held_at, h.expires_at
FROM seat_hold h JOIN user_hold_quota q ON q.schedule_id = h.schedule_id AND q.user_id = h.user_id
JOIN product_schedule s ON s.id = h.schedule_id AND s.starts_at > now()
WHERE h.held_at <= now() - make_interval(secs => $held) AND h.expires_at >= now() + make_interval(secs => $margin) AND q.cnt = 1
  AND NOT EXISTS (SELECT 1 FROM excl e WHERE e.schedule_id = q.schedule_id AND e.user_id = q.user_id)
  AND NOT EXISTS (SELECT 1 FROM inj i WHERE i.schedule_id = q.schedule_id AND i.user_id = q.user_id)
ORDER BY h.id LIMIT $l1;
INSERT INTO inj
SELECT 'L2', q.schedule_id, q.user_id, q.cnt, NULL, NULL
FROM reservation r JOIN user_hold_quota q ON q.schedule_id = r.schedule_id AND q.user_id = r.user_id
JOIN product_schedule s ON s.id = r.schedule_id AND s.starts_at > now()
WHERE r.status = 'CONFIRMED' AND q.cnt = 1
  AND NOT EXISTS (SELECT 1 FROM seat_hold x WHERE x.schedule_id = q.schedule_id AND x.user_id = q.user_id)
  AND NOT EXISTS (SELECT 1 FROM reservation r2 WHERE r2.schedule_id = q.schedule_id AND r2.user_id = q.user_id AND r2.status = 'CONFIRMED' AND r2.id <> r.id)
  AND NOT EXISTS (SELECT 1 FROM excl e WHERE e.schedule_id = q.schedule_id AND e.user_id = q.user_id)
  AND NOT EXISTS (SELECT 1 FROM inj i WHERE i.schedule_id = q.schedule_id AND i.user_id = q.user_id)
ORDER BY r.id LIMIT $l2;
INSERT INTO inj
SELECT 'H', q.schedule_id, q.user_id, q.cnt, NULL, NULL
FROM user_hold_quota q JOIN product_schedule s ON s.id = q.schedule_id AND s.starts_at > now()
WHERE q.cnt = 1
  AND NOT EXISTS (SELECT 1 FROM excl e WHERE e.schedule_id = q.schedule_id AND e.user_id = q.user_id)
  AND NOT EXISTS (SELECT 1 FROM inj i WHERE i.schedule_id = q.schedule_id AND i.user_id = q.user_id)
ORDER BY q.schedule_id, q.user_id LIMIT $h;
-- H-7: 고른 쌍의 쿼터 행을 앱과 같은 순서((회차, 사용자))로 잠근 뒤 조건을 다시 본다 — 고른 뒤 잠그기 전에 바뀐 쌍(확정·만료·cnt 변경)은 뺀다(dropped_after_lock)
CREATE TEMP TABLE locked ON COMMIT DROP AS
SELECT q.schedule_id, q.user_id, q.cnt FROM user_hold_quota q JOIN inj i ON i.schedule_id = q.schedule_id AND i.user_id = q.user_id
ORDER BY q.schedule_id, q.user_id FOR UPDATE OF q;
CREATE TEMP TABLE dropped ON COMMIT DROP AS SELECT i.* FROM inj i WHERE NOT (
  EXISTS (SELECT 1 FROM locked l WHERE l.schedule_id = i.schedule_id AND l.user_id = i.user_id AND l.cnt = 1)
  AND CASE i.kind
    WHEN 'L2' THEN NOT EXISTS (SELECT 1 FROM seat_hold x WHERE x.schedule_id = i.schedule_id AND x.user_id = i.user_id)
               AND (SELECT count(*) FROM reservation r WHERE r.schedule_id = i.schedule_id AND r.user_id = i.user_id AND r.status = 'CONFIRMED') = 1
    WHEN 'H' THEN true
    ELSE EXISTS (SELECT 1 FROM seat_hold x WHERE x.schedule_id = i.schedule_id AND x.user_id = i.user_id
                 AND x.held_at <= now() - make_interval(secs => $held) AND x.expires_at >= now() + make_interval(secs => $margin))
  END
  -- N-4: 최초 선정 조건 전체 — 열린 회차(모든 종류) · D·L1은 '만료될' 홀드(held_at 최소 나이 · 만료 여유)
  AND EXISTS (SELECT 1 FROM product_schedule s WHERE s.id = i.schedule_id AND s.starts_at > now()));
DELETE FROM inj i USING dropped d WHERE d.schedule_id = i.schedule_id AND d.user_id = i.user_id;
-- 변경 + 종류별 영향 행 수(기록한 수와 대조 — rep_status inject-mismatch)
CREATE TEMP TABLE applied ON COMMIT DROP AS
WITH d AS (DELETE FROM user_hold_quota q USING inj i WHERE i.kind = 'D' AND q.schedule_id = i.schedule_id AND q.user_id = i.user_id RETURNING 1),
     l AS (UPDATE user_hold_quota q SET cnt = q.cnt - 1 FROM inj i WHERE i.kind IN ('L1', 'L2') AND q.schedule_id = i.schedule_id AND q.user_id = i.user_id RETURNING 1),
     h AS (UPDATE user_hold_quota q SET cnt = q.cnt + 1 FROM inj i WHERE i.kind = 'H' AND q.schedule_id = i.schedule_id AND q.user_id = i.user_id RETURNING 1)
SELECT (SELECT count(*) FROM d) AS d, (SELECT count(*) FROM l) AS l, (SELECT count(*) FROM h) AS h;
SELECT json_build_object('at', clock_timestamp(), 'at_epoch', extract(epoch FROM clock_timestamp()),
  'counts', (SELECT json_build_object('D', count(*) FILTER (WHERE kind = 'D'), 'L1', count(*) FILTER (WHERE kind = 'L1'),
                                      'L2', count(*) FILTER (WHERE kind = 'L2'), 'H', count(*) FILTER (WHERE kind = 'H')) FROM inj),
  'applied', (SELECT json_build_object('D', d, 'L', l, 'H', h) FROM applied),
  'dropped_after_lock', (SELECT count(*) FROM dropped),
  'pairs', (SELECT coalesce(json_agg(json_build_object('kind', kind, 'schedule_id', schedule_id, 'user_id', user_id, 'cnt_before', cnt_before,
                                                       'held_at', held_at, 'expires_at', expires_at) ORDER BY kind, schedule_id, user_id), '[]') FROM inj));
COMMIT;
SQL
}

# 회차 끝 쌍 상태(효과 셀): 주입한 쌍마다 cnt(행 없음 = null)·실제 값 + 열린 회차 전체의 어긋난 쌍(판정기 v2와 같은 정의, 5,000쌍까지·총 수).
# 인자: 주입 쌍 "(s,u),(s,u)"(빈 값 가능) → 한 줄 JSON
end_pairs() {
  local inj="${1:-}"
  [[ -z "$inj" || "$inj" =~ ^(\([0-9]+,[0-9]+\),?)+$ ]] || return 2
  db_json <<SQL
WITH inj(schedule_id, user_id) AS (VALUES (0::bigint, 0::bigint)${inj:+,${inj%,}}),
actual AS (
  SELECT schedule_id, user_id, sum(n) AS total FROM (
    SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
    UNION ALL
    SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2) u GROUP BY 1, 2),
pairs AS (SELECT schedule_id, user_id, q.cnt, coalesce(a.total, 0) AS total FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id)),
open_s AS (SELECT id FROM product_schedule WHERE starts_at > now()),
mm AS (SELECT * FROM pairs WHERE coalesce(cnt, 0) <> total AND schedule_id IN (SELECT id FROM open_s))
SELECT json_build_object('db_now', clock_timestamp(),
  'injected', (SELECT coalesce(json_agg(json_build_object('schedule_id', i.schedule_id, 'user_id', i.user_id,
      'cnt', (SELECT q.cnt FROM user_hold_quota q WHERE q.schedule_id = i.schedule_id AND q.user_id = i.user_id),
      'actual', coalesce((SELECT a.total FROM actual a WHERE a.schedule_id = i.schedule_id AND a.user_id = i.user_id), 0))), '[]') FROM inj i WHERE i.schedule_id <> 0),
  'mismatch_total', (SELECT count(*) FROM mm),
  'mismatch', (SELECT coalesce(json_agg(json_build_object('schedule_id', schedule_id, 'user_id', user_id, 'cnt', cnt, 'actual', total)), '[]')
               FROM (SELECT * FROM mm ORDER BY schedule_id, user_id LIMIT 5000) m));
SQL
}

# ---- 마감 임박 셀(S3c — 설계 §9 · 명세 §10 D6) ---------------------------------------------------------------------------------------------
# 시드 회차(1)의 starts_at = 지금(DB) + 인자 초. run.sh가 k6 바로 전에 부른다(인자 = 600 + k6 시작까지 예상 지연) → 한 줄 JSON(실제 넣은 값 · 넣은 시각 · 바뀐 행 수)
close_set_starts() {
  [[ "$1" =~ ^[0-9]+(\.[0-9]+)?$ ]] || return 2
  db_json <<SQL
WITH u AS (UPDATE product_schedule SET starts_at = date_trunc('milliseconds', clock_timestamp() + make_interval(secs => $1)) WHERE id = 1 RETURNING starts_at)
SELECT json_build_object('starts_at', (SELECT starts_at FROM u), 'starts_at_epoch', (SELECT extract(epoch FROM starts_at) FROM u),
                         'set_at', clock_timestamp(), 'set_at_epoch', extract(epoch FROM clock_timestamp()), 'updated', (SELECT count(*) FROM u));
SQL
}

# 마감 5초 전 표본(pre-close-mismatch): 회차 1의 (쿼터 행 ∪ 실제 값 쌍) 중 cnt ≠ 실제 — 한 문장(한 스냅숏, 읽기만). 판정기 v2와 같은 정의를 마감 회차에 한정
pre_close_sample() {
  db_json <<'SQL'
WITH a AS (SELECT user_id, sum(n) AS n FROM (SELECT user_id, count(*) AS n FROM seat_hold WHERE schedule_id = 1 GROUP BY 1
                                              UNION ALL SELECT user_id, count(*) FROM reservation WHERE schedule_id = 1 AND status = 'CONFIRMED' GROUP BY 1) u GROUP BY 1),
q AS (SELECT user_id, cnt FROM user_hold_quota WHERE schedule_id = 1)
SELECT json_build_object('t', clock_timestamp(), 't_epoch', extract(epoch FROM clock_timestamp()),
                         'starts_at_epoch', (SELECT extract(epoch FROM starts_at) FROM product_schedule WHERE id = 1),
                         'pairs', count(*), 'mismatches', count(*) FILTER (WHERE coalesce(q.cnt, 0) <> coalesce(a.n, 0)))
FROM q FULL JOIN a USING (user_id);
SQL
}

# 마감 임박 셀 끝 상태(정리 ①·② 판정·비교 지표 — 설계 §6)
close_end() {
  db_json <<'SQL'
SELECT json_build_object('db_now', clock_timestamp(),
  'closed_schedules', (SELECT count(*) FROM product_schedule WHERE starts_at <= now()),
  'holds', (SELECT count(*) FROM seat_hold),
  'quota_rows', (SELECT count(*) FROM user_hold_quota),
  'closed_quota_rows', (SELECT count(*) FROM user_hold_quota q JOIN product_schedule s ON s.id = q.schedule_id WHERE s.starts_at <= now()),
  'closed_quota_rows_no_holds', (SELECT count(*) FROM user_hold_quota q JOIN product_schedule s ON s.id = q.schedule_id
                                 WHERE s.starts_at <= now() AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.schedule_id = s.id)),
  'zero_rows_actual0', (SELECT count(*) FROM user_hold_quota q WHERE q.cnt = 0
                          AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.schedule_id = q.schedule_id AND h.user_id = q.user_id)
                          AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.schedule_id = q.schedule_id AND r.user_id = q.user_id AND r.status = 'CONFIRMED')),
  'quota_bytes', pg_total_relation_size('user_hold_quota'),
  'quota_stats', (SELECT row_to_json(t) FROM (SELECT n_tup_ins, n_tup_upd, n_tup_del, n_dead_tup, autovacuum_count, vacuum_count
                                              FROM pg_stat_user_tables WHERE relname = 'user_hold_quota') t));
SQL
}

# ---- 명세 §13 시작 상태 기록(리뷰 R2): k6 전 대기 동안 DB 상태 — 시드 끝·k6 직전에 한 번씩(db-state-seed.json · db-state-k6.json) --------------------
#   postmaster_start = pg_postmaster_start_time()(DB 기동 시각 — 'DB 기동 → k6 시작'의 기준) · 주요 테이블 autovacuum/autoanalyze/vacuum/analyze 수와 last_* 시각 ·
#   n_dead_tup · n_mod_since_analyze · n_ins_since_vacuum · 지금 도는 autovacuum worker(대상 문장 앞부분) · 체크포인트 수(PG16 pg_stat_bgwriter — pg_stat_checkpointer는 PG17부터)
db_state() {
  db_json <<'SQL'
SELECT json_build_object('t', clock_timestamp(), 't_epoch', extract(epoch FROM clock_timestamp()),
  'postmaster_start', pg_postmaster_start_time(), 'postmaster_start_epoch', extract(epoch FROM pg_postmaster_start_time()),
  'tables', (SELECT json_object_agg(relname, json_build_object('autovacuum_count', autovacuum_count, 'autoanalyze_count', autoanalyze_count,
                 'vacuum_count', vacuum_count, 'analyze_count', analyze_count, 'last_autovacuum', last_autovacuum, 'last_autoanalyze', last_autoanalyze,
                 'last_vacuum', last_vacuum, 'last_analyze', last_analyze, 'n_live_tup', n_live_tup, 'n_dead_tup', n_dead_tup,
                 'n_mod_since_analyze', n_mod_since_analyze, 'n_ins_since_vacuum', n_ins_since_vacuum))
             FROM pg_stat_user_tables WHERE relname IN ('product_seat', 'seat_hold', 'reservation', 'user_hold_quota')),
  'autovacuum_workers', (SELECT coalesce(json_agg(left(query, 120)), '[]') FROM pg_stat_activity WHERE backend_type = 'autovacuum worker'),
  'checkpoints', (SELECT json_build_object('timed', checkpoints_timed, 'req', checkpoints_req, 'write_time', checkpoint_write_time, 'sync_time', checkpoint_sync_time)
                  FROM pg_stat_bgwriter));
SQL
}

# ---- ADR-011 서버 측정기(compose.probe.yml — run.sh --probes) ----------------------------------------------------------------------------
# 주 k6 직전에 probe 서비스 하나만 띄운다(--no-deps: 앱·DB는 건드리지 않는다). 이미지는 받지 않는다(compose pull_policy: never — 없으면 기동 실패 → probe-missing).
# 인자: 배포폴더 길이(초). PROBE_IMAGE·PROBE_RATE는 run.sh가 정한다(plan.json condition)
# 리뷰 L3: compose_up과 같은 -f 묶음(COMPOSE_FILE + COMPOSE_EXTRA — tmpfs·F 오버레이) 뒤에 probe 오버레이 — 프로젝트 모델이 회차 기동과 같다.
# 리뷰(codex 중간): ssh 한 번에 시한 PROBE_UP_TIMEOUT_S(120초) — 넘으면 124(timeout)로 끝나 probe-check가 probe-missing(run.sh probes_stop이 두 측정기를 정리한다)
remote_t() { local t="$1"; shift; timeout "$t" ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 "$SERVER" "$@"; }
probe_compose() { echo "docker compose -p seatlab -f $COMPOSE_FILE $COMPOSE_EXTRA -f $PROBE_FILE"; }
probe_server_up() {
  remote_t "${PROBE_UP_TIMEOUT_S:-120}" "cd $REMOTE_BASE/$1 && env PROBE_IMAGE='$PROBE_IMAGE' PROBE_RATE=$PROBE_RATE PROBE_DURATION_S=$2 $(probe_compose) up -d --no-deps --no-build probe"
}
# 회차 끝: SIGTERM으로 멈추고(k6가 정상 종료하며 CSV를 닫는다 — 종료 코드 105) 결과 CSV·로그를 받은 뒤 컨테이너를 지운다.
# 인자: 배포폴더 회차폴더 → probe-server.json 한 줄 {stop_rc, exit_code, started_at, finished_at, cp_rc, csv_bytes, log_rc, rm_rc}(서버 시계 — docker 기록)
probe_server_collect() {
  local id="$1" dir="$2" stop_rc st cp_rc log_rc rm_rc ec sa fa ss
  # 각 ssh에 시한(stop -t 30 + 여유) — 수거가 회차를 붙잡지 않게. 실패는 각 rc로 남고 probe-check가 probe-missing으로 드러낸다
  remote_t 90 "cd $REMOTE_BASE/$id && $(probe_compose) stop -t 30 probe" >> "$dir/probe-server.log" 2>&1; stop_rc=$?
  st=$(remote_t 30 "docker inspect -f '{{.State.ExitCode}} {{.State.StartedAt}} {{.State.FinishedAt}} {{.State.Status}}' seatlab-probe-1" 2>>"$dir/probe-server.log")
  remote_t 120 "docker cp seatlab-probe-1:/tmp/k6-probe-server.csv.gz -" 2>>"$dir/probe-server.log" | tar -xO > "$dir/k6-probe-server.csv.gz" 2>>"$dir/probe-server.log"
  cp_rc=$(( PIPESTATUS[0] + PIPESTATUS[1] ))
  remote_t 60 "docker logs seatlab-probe-1 2>&1" >> "$dir/probe-server.log" 2>&1; log_rc=$?
  remote_t 90 "cd $REMOTE_BASE/$id && $(probe_compose) rm -f -s probe" >> "$dir/probe-server.log" 2>&1; rm_rc=$?
  read -r ec sa fa ss <<< "$st"
  jq -nc --argjson stop "$stop_rc" --arg ec "${ec:-}" --arg sa "${sa:-}" --arg fa "${fa:-}" --arg ss "${ss:-}" --argjson cp "$cp_rc" \
        --argjson bytes "$(stat -c %s "$dir/k6-probe-server.csv.gz" 2>/dev/null || echo 0)" --argjson log "$log_rc" --argjson rm "$rm_rc" \
    '{stop_rc:$stop, exit_code:($ec|tonumber? // null), started_at:$sa, finished_at:$fa, state:$ss, cp_rc:$cp, csv_bytes:$bytes, log_rc:$log, rm_rc:$rm}'
}
