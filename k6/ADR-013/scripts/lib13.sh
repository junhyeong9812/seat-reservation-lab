#!/usr/bin/env bash
# ADR-013 하네스 공통 함수 — ADR-009 lib.sh(원격 실행 · 시계 차 · DB 표본 · 기록 덤프 · 끝 쌍)를 source 하고(§4.6 '공통 함수는 ADR-009 lib.sh에서 시작'),
# 혼재 구간에 필요한 것만 더한다: 슬롯 A·B 기동·내림(H1) · lb 구성원(H2) · 비움 확인(N9) · 앱별 수집(H4) · 촘촘한 락 표본(§4.5-2) ·
# S4c SQL 시드·시드 확인(§4.1) · 기준 DB(seat_ref — 동치 확인·이력 체크섬의 출처) · 끝 검사(§4.7.2) · 주입(ADR-009 inject_drift 복사 — 탐침 회차 제외 · H7).
# run.sh가 source 한다(동결 사본). ADR-009 lib.sh는 수정하지 않는다.
# shellcheck source=../../ADR-009/scripts/lib.sh
source "$LIB9_DIR/lib.sh"
LIB13_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
set +e

C13="k6/ADR-013/compose.cutover.yml"
COMPOSE_TMPFS=""   # run.sh: -f k6/ADR-009/compose.tmpfs.yml(tmpfs preset)
compose13() { echo "docker compose -p seatlab -f $COMPOSE_FILE $COMPOSE_TMPFS -f $C13"; }
svc_of() { [[ "$1" == A ]] && echo app || echo app2; }
ctr_of() { echo "seatlab-$(svc_of "$1")-1"; }
port_of() { [[ "$1" == A ]] && echo 8111 || echo 8112; }
url_of() { echo "http://${SERVER_HOST}:$(port_of "$1")"; }
LB_URL="http://${SERVER_HOST}:8101"

# compose 변수 — 슬롯 상태(SLOT_A_TAG …)는 run.sh가 쥔다. ADR-009 compose.yml의 조건 변수는 빈 값(키 없음 · 경고 없음)으로 늘 넘긴다
ADR9_BLANK="LIMIT_TRIGGER= SALES_CLOSE_OFF= DRIFT_A= DRIFT_EXTERNAL= DRIFT_C_ENABLED= DRIFT_C= DRIFT_CLEANUP= INC_METHOD= INC_ENABLED= INC_INTERVAL= INC_TRACK= SAFETY_ENABLED= APP_DETECT_SHAPE= INJECT_APP= INJECT_APP_PATHS= FLYWAY_LOCATIONS="
slot_env() {
  echo "$ADR9_BLANK SLOT_A_TAG=${SLOT_A_TAG:-none} SLOT_A_APPNAME=${SLOT_A_APPNAME:-seatlab-A} SLOT_A_SHADOW=${SLOT_A_SHADOW:-}" \
       "SLOT_B_TAG=${SLOT_B_TAG:-none} SLOT_B_APPNAME=${SLOT_B_APPNAME:-seatlab-B} SLOT_B_SHADOW=${SLOT_B_SHADOW:-} $COMMON_ENV"
}

compose_down13() {   # 인자: 배포폴더 — 모든 오버레이로 내리고 DB 볼륨·컨테이너가 정말 없는지(ADR-009 compose_down과 같은 확인)
  local id="$1" i
  for i in 1 2 3; do
    remote "cd $REMOTE_BASE/$id && env $(slot_env) docker compose -p seatlab -f $COMPOSE_FILE -f k6/ADR-009/compose.tmpfs.yml -f k6/ADR-009/compose.reconciler.yml -f $PROBE_FILE -f $C13 down -v --remove-orphans" >&2
    if remote "! docker volume inspect seatlab_pgdata > /dev/null 2>&1 && [ -z \"\$(docker ps -aq --filter label=com.docker.compose.project=seatlab)\" ]" >&2; then return 0; fi
    log "compose down13: 볼륨 또는 컨테이너가 남음 — retry in 10s"; sleep 10
  done
  return 1
}

compose_up13() {   # 인자: 배포폴더 서비스… — 빌드·받기 없음(H1) · --wait = DB·Redis healthcheck 통과까지(슬롯 기동은 --no-deps라 DB 준비를 기다리지 않는다)
  local id="$1"; shift
  remote "cd $REMOTE_BASE/$id && env $(slot_env) $(compose13) up -d --no-build --wait $*" >&2
}
slot_up() {   # 인자: 배포폴더 슬롯 — 그 서비스만(--no-deps — DB·lb·다른 슬롯은 건드리지 않는다)
  remote "cd $REMOTE_BASE/$1 && env $(slot_env) $(compose13) up -d --no-build --no-deps --force-recreate $(svc_of "$2")" >&2
}
slot_stop() {   # 인자: 배포폴더 슬롯 — 정상 종료(SIGTERM · 30초) 뒤 컨테이너는 남긴다(로그는 내리기 전에 받았다 — H4)
  remote "cd $REMOTE_BASE/$1 && env $(slot_env) $(compose13) stop -t 30 $(svc_of "$2")" >&2
}
image_id() { remote "docker image inspect --format '{{.Id}}' $1" 2>/dev/null | tr -d '[:space:]'; }   # 인자: 이미지(seatlab-app:<sha8>)
ctr_ip() { remote "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $1" 2>/dev/null | tr -d '[:space:]'; }
ctr_image() { remote "docker inspect -f '{{.Image}}' $1" 2>/dev/null | tr -d '[:space:]'; }

wait_health_url() {   # 인자: URL 시한초 — health UP까지(ADR-009 wait_health와 같은 판정)
  local deadline=$((SECONDS + $2))
  until curl -fsS --max-time 5 "$1/actuator/health" 2>/dev/null | grep -q '"UP"'; do
    (( SECONDS > deadline )) && return 1
    sleep 1
  done
}

# lb 구성원(H2): include 파일을 다시 쓰고 nginx -t 통과 뒤에만 reload — 새 작업자가 뜰 때까지 본다(옛 작업자 pid 집합과 겹치지 않는 '살아 있는' 작업자).
# 인자: 서비스 이름…(빈 인자 = 자리표시 서버만). 출력 한 줄 JSON {test_rc, reload_sent, effective, log_bytes, members}(서버 epoch) · 실패면 1(test 실패면 옛 파일로 되돌린다 — 설정은 그대로)
lb_members() {
  remote "bash -s -- $*" <<'REMOTE'
c=seatlab-lb-1
workers() { docker exec $c sh -c "ps -o pid,args | grep 'nginx: worker process' | grep -v 'shutting down' | grep -v grep | awk '{print \$1}'" | sort | tr '\n' ' '; }
old=$(workers)
members="server 127.0.0.1:9 down;"; for s in "$@"; do members="$members
server $s:8101;"; done
if ! docker exec -i $c sh -c 'M=/etc/nginx/members/members.conf; cp $M $M.bak; cat > $M; if nginx -t > /tmp/nginx-t.log 2>&1; then exit 0; else cp $M.bak $M; exit 3; fi' <<< "$members"; then
  printf '{"test_rc":3,"test_log":"%s","members":"%s"}\n' "$(docker exec $c cat /tmp/nginx-t.log | tr -d '"\\' | tr '\n' ' ' | head -c 400)" "$*"
  exit 3
fi
lb0=$(docker exec $c stat -c %s /var/log/nginx/cut.log 2>/dev/null || echo 0)
ts=$(date +%s.%N); docker exec $c nginx -s reload; rc=$?
te=null
for i in $(seq 1 100); do
  now=$(workers); dis=1
  for p in $now; do case " $old " in *" $p "*) dis=0 ;; esac; done
  if [ -n "$now" ] && [ $dis = 1 ]; then te=$(date +%s.%N); break; fi
  sleep 0.1
done
printf '{"test_rc":0,"reload_rc":%s,"reload_sent":%s,"effective":%s,"log_bytes":%s,"members":"%s","old_workers":"%s","new_workers":"%s"}\n' "$rc" "$ts" "$te" "$lb0" "$*" "$old" "$(workers)"
[ "$rc" = 0 ] && [ "$te" != null ]
REMOTE
}
# 비움 확인(H2 ⓒ · N9): 빼기 reload가 효력을 낸 뒤(effective) 그 앱 주소로 **시작한** 요청 수 — 시작 = $msec − $request_time.
# 인자: ip 효력epoch 그때의로그바이트 → 숫자(실패면 빈 출력). 효력 시각 뒤에 끝난 줄만 그 바이트 뒤에 있으므로 거기서부터만 읽는다
lb_started_after() {
  remote "docker exec seatlab-lb-1 sh -c \"tail -c +$(( $3 + 1 )) /var/log/nginx/cut.log | awk -v ip='$1:8101' -v t='$2' 'index(\\\$3, ip) == 1 && (\\\$1 - \\\$2) > t {n++} END {print n + 0}'\"" 2>/dev/null | tr -d '[:space:]'
}
# 진행 중 요청(그 앱 — 업무 URI만): http.server.requests.active(Spring Boot 장기 작업 타이머) ACTIVE_TASKS 합 · 지표가 없으면 빈 출력(판정 불가 — 기록)
HOLD_URI='/api/schedules/{scheduleId}/seats/{seatId}/hold' CONFIRM_URI='/api/holds/{holdId}/confirm'
app_inflight() {
  local url="$1" n=0 u v names
  names=$(curl -fsS --max-time 5 "$url/actuator/metrics" 2>/dev/null | jq -c '.names' 2>/dev/null) || return 1
  jq -e 'index("http.server.requests.active") != null' <<< "$names" > /dev/null 2>&1 || return 1
  for u in "$HOLD_URI" "$CONFIRM_URI"; do
    v=$(curl -sS --max-time 5 "$url/actuator/metrics/http.server.requests.active?tag=uri:$(sed 's/{/%7B/g; s/}/%7D/g' <<< "$u")" 2>/dev/null \
        | jq -r '[.measurements[]? | select(.statistic == "ACTIVE_TASKS") | .value][0] // 0' 2>/dev/null)
    [[ "$v" =~ ^[0-9.]+$ ]] || v=0
    n=$(awk -v a="$n" -v b="$v" 'BEGIN { print a + b }')
  done
  echo "$n"
}
# 비움 미완료의 원인(§5.3): 그 앱 세션 중 Lock 대기이고 막은 쪽이 마이그레이션(새·그림자 앱 — Flyway는 앱 커넥션 이름)·적용판 세션인 수
drain_lock_cause() {   # 인자: 그 앱의 ApplicationName → 한 줄 JSON
  db_json <<SQL
SELECT json_build_object('lock_wait', count(*), 'blocked_by_migration', count(*) FILTER (WHERE EXISTS (
    SELECT 1 FROM pg_stat_activity b WHERE b.pid = ANY (pg_blocking_pids(a.pid))
      AND (b.application_name LIKE 'seatlab-%-new' OR b.application_name LIKE 'seatlab-%-shadow' OR b.application_name = 'seatlab-online-v9'))))
FROM pg_stat_activity a WHERE a.application_name = '$1' AND a.wait_event_type = 'Lock';
SQL
}

# 앱별 수집(H4 — 내리기 직전): 설정 · 지표 전체 · GC 로그 · docker logs · 컨테이너 정보. 인자: 슬롯 생애(A1 …) 회차폴더 → 실패 사유를 한 줄씩 출력(빈 출력 = 성공)
app_collect() {
  local slot="$1" life="$2" dir="$3" c url lf
  c=$(ctr_of "$slot"); url=$(url_of "$slot")
  python3 -I "$LIB13_DIR/appcollect.py" "$url" "$dir/app-$life-actuator.json" 2>>"$dir/errors.log" || echo "actuator"
  # 원격 읽기가 실패하거나 비면 파일을 남기지 않는다(CR13-4 — 빈 gz가 '로그 있음 · GC 0'으로 읽히지 않게) · 사유 한 줄
  local ps b
  lf="$dir/app-$life-gc.log.gz"
  remote "docker exec $c cat /dev/shm/gc.log" | gzip > "$lf"; ps="${PIPESTATUS[0]}/${PIPESTATUS[1]}"; b=$(zcat "$lf" 2>/dev/null | wc -c)
  echo "$life gc pipe=$ps bytes=$b" >> "$dir/logs-status.txt"
  [[ "$ps" == 0/0 && "$b" -gt 0 ]] || { rm -f "$lf"; echo "gc-log"; }
  lf="$dir/app-$life.log.gz"
  remote "docker logs $c 2>&1" | gzip > "$lf"; ps="${PIPESTATUS[0]}/${PIPESTATUS[1]}"; b=$(zcat "$lf" 2>/dev/null | wc -c)
  echo "$life log pipe=$ps bytes=$b" >> "$dir/logs-status.txt"
  [[ "$ps" == 0/0 && "$b" -gt 0 ]] || { rm -f "$lf"; echo "app-log"; }
  remote "docker inspect $c --format '{{json .State}} {{json .HostConfig.NanoCpus}} {{json .HostConfig.Memory}} {{json .Image}} {{json .Config.Image}}'" > "$dir/app-$life-inspect.txt" 2>>"$dir/errors.log" || echo "inspect"
}

# 촘촘한 락 표본(§4.5-2 · DS-8): 0.2초마다 한 줄 — 서버에서 psql 세션 하나(표준 입력으로 질의를 흘려 넣는다 · 세션 이름 seatlab-harness-locks).
#   lock_wait = wait_event_type Lock 세션 수 · waiters = 그 세션들(pid · 앱 이름 · 막은 pid · 대기 초 · 문장 앞 60자 · 50개까지) · blockers = 막은 세션(앱 이름 · 문장)
#   quota_locks = user_hold_quota 관계 락 (모드, granted)별 수 · cic = pg_stat_progress_create_index(phase · lockers · current_locker_pid · blocks)
#   받는 쪽이 끊기면(ssh 종료) 서버 쪽 루프도 끝난다(kill -0 $PPID — ADR-009 db_sampler와 같은 방식)
MLOCK_SQL="SELECT json_build_object('t', extract(epoch FROM clock_timestamp()), 'lock_wait', (SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'), 'waiters', (SELECT coalesce(json_agg(w), '[]') FROM (SELECT a.pid, a.application_name AS app, pg_blocking_pids(a.pid) AS blk, round(extract(epoch FROM clock_timestamp() - a.query_start)::numeric, 3) AS wait_s, extract(epoch FROM a.query_start) AS qs, left(a.query, 60) AS q FROM pg_stat_activity a WHERE a.wait_event_type = 'Lock' ORDER BY a.query_start LIMIT 50) w), 'blockers', (SELECT coalesce(json_agg(json_build_object('pid', b.pid, 'app', b.application_name, 'q', left(b.query, 60), 'xact_s', round(extract(epoch FROM clock_timestamp() - b.xact_start)::numeric, 3))), '[]') FROM pg_stat_activity b WHERE b.pid IN (SELECT unnest(pg_blocking_pids(x.pid)) FROM pg_stat_activity x WHERE x.wait_event_type = 'Lock')), 'quota_locks', (SELECT coalesce(json_agg(json_build_object('mode', mode, 'granted', granted, 'n', n)), '[]') FROM (SELECT mode, granted, count(*) AS n FROM pg_locks WHERE relation = to_regclass('user_hold_quota') GROUP BY 1, 2) l), 'cic', (SELECT coalesce(json_agg(json_build_object('pid', pid, 'phase', phase, 'lockers_total', lockers_total, 'lockers_done', lockers_done, 'current_locker_pid', current_locker_pid, 'blocks_total', blocks_total, 'blocks_done', blocks_done)), '[]') FROM pg_stat_progress_create_index));"
mlock_sampler() {
  remote "while kill -0 \$PPID 2>/dev/null; do echo \"$MLOCK_SQL\"; sleep 0.2; done | docker exec -i -e PGAPPNAME=seatlab-harness-locks seatlab-db-1 psql -X -q -U seat -d seat -tA"
}

# 앱 슬롯 표본(H4 · §4.5-4 — 앱별 Hikari): 1초마다 두 슬롯의 pending·active(10초마다 힙 사용·업무 요청 진행 중) → timeline-app-<슬롯>.jsonl. 내려간 슬롯은 건너뛴다(응답 없음)
slot_poller() {   # 인자: 회차폴더
  local dir="$1" i=0 s url p a h
  while :; do
    for s in A B; do
      url=$(url_of "$s")
      p=$(curl -fsS --max-time 1 "$url/actuator/metrics/hikaricp.connections.pending" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null) || continue
      a=$(curl -fsS --max-time 1 "$url/actuator/metrics/hikaricp.connections.active" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null)
      h=null
      (( i % 10 == 0 )) && h=$(curl -fsS --max-time 1 "$url/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null)
      [[ -n "$p" ]] && echo "{\"t\":$(date +%s.%N),\"pending\":$p,\"active\":${a:-null},\"heap_used\":${h:-null}}" >> "$dir/timeline-app-$s.jsonl"
    done
    i=$((i + 1)); sleep 1
  done
}

# 초기화 재시도(ADR-009 run.sh reset_with_retry 복사 — lib.sh 밖에 있어 source로 못 쓴다): 과부하 직후 적체가 풀릴 때까지
reset_with_retry() {  # 인자: 회차폴더 schedules seats
  local dir="$1"; shift
  local i
  for i in 1 2 3 4 5 6; do
    if reset_db "$@"; then echo "$(date -Is) reset ok attempt=$i" >> "$dir/recovery.log"; return 0; fi
    echo "$(date -Is) reset failed attempt=$i" >> "$dir/recovery.log"
    sleep 10
  done
  return 1
}

# ---- 시드(§4.1) ---------------------------------------------------------------------------------------------------------------
# S4c: 옛 앱 /internal/reset(회차 1,000 · 1만 석) 뒤 회차 501~999에 쌍마다 쿼터 행 cnt 1 + 홀드 1(held_at = 시드 시각 · expires_at = + 3시간) + 좌석 HELD.
#   사용자 = 20,000,000 + i(i = 그 회차 묶음 안 좌석 순번 — 좌석 id − 5,000,000). 삽입 순서 = PK 순(회차 · 사용자 = 좌석 순). 끝에 VACUUM ANALYZE.
seed_s4c_sql() {   # 인자: from to → 한 줄 JSON {pairs, t_s}
  db_json <<SQL
CREATE TEMP TABLE t0 AS SELECT clock_timestamp() AS t;
INSERT INTO user_hold_quota (schedule_id, user_id, cnt)
  SELECT s.schedule_id, 20000000 + (s.id - (($1 - 1)::bigint * 10000)), 1 FROM product_seat s WHERE s.schedule_id BETWEEN $1 AND $2 ORDER BY s.schedule_id, s.id;
INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at)
  SELECT s.id, s.schedule_id, 20000000 + (s.id - (($1 - 1)::bigint * 10000)), (SELECT t FROM t0), (SELECT t FROM t0) + interval '3 hours'
  FROM product_seat s WHERE s.schedule_id BETWEEN $1 AND $2 ORDER BY s.id;
UPDATE product_seat SET status = 'HELD' WHERE schedule_id BETWEEN $1 AND $2;
SELECT json_build_object('pairs', (SELECT count(*) FROM user_hold_quota WHERE schedule_id BETWEEN $1 AND $2), 't_s', extract(epoch FROM clock_timestamp() - (SELECT t FROM t0)));
SQL
}
vacuum_analyze() { DB_JSON_TIMEOUT=3600 db_json <<< "VACUUM ANALYZE; SELECT json_build_object('ok', true);"; }
# 시드 확인(record-level · 명세 §5 데이터 특칙 · seed-mismatch): 쿼터 행 수 = 홀드 수 = 시드 쌍 수 · HELD 좌석 수 · 표본 100쌍 cnt = 실제 · 전체 끝 불일치 0
seed_check_sql() {   # 인자: 기대쌍수 from to
  DB_JSON_TIMEOUT=1800 db_json <<SQL
WITH actual AS (SELECT schedule_id, user_id, sum(n) AS total FROM (SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
                 UNION ALL SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2) u GROUP BY 1, 2),
pairs AS (SELECT schedule_id, user_id, q.cnt, coalesce(a.total, 0) AS total FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id)),
smp AS (SELECT q.schedule_id, q.user_id FROM user_hold_quota q WHERE q.schedule_id BETWEEN $2 AND $3 ORDER BY hashint8(q.user_id) LIMIT 100)
SELECT json_build_object('expected_pairs', $1,
  'quota_rows', (SELECT count(*) FROM user_hold_quota), 'holds', (SELECT count(*) FROM seat_hold),
  'held_seats', (SELECT count(*) FROM product_seat WHERE status = 'HELD'), 'seats', (SELECT count(*) FROM product_seat),
  'sample_bad', (SELECT count(*) FROM smp JOIN pairs p USING (schedule_id, user_id) WHERE p.cnt IS DISTINCT FROM p.total),
  'sample_n', (SELECT count(*) FROM smp), 'mismatch', (SELECT count(*) FROM pairs WHERE coalesce(cnt, 0) <> total),
  'table_bytes', pg_total_relation_size('user_hold_quota'), 'hold_bytes', pg_total_relation_size('seat_hold'));
SQL
}

# ---- 기준 DB(seat_ref): 같은 SHA 새 앱 Flyway(11.7.2 CLI · 같은 SQL 파일)가 V1~V10을 빈 DB에 적용한 결과 — 적용판 동치 확인(§4.4 단계 4)·이력 체크섬(§4.7.2)의 출처
FLYWAY_IMAGE="${FLYWAY_IMAGE:-flyway/flyway:11.7.2}"
flyway_run_cmd() {   # 인자: 새 SHA 배포폴더 → 원격에서 flyway 인자를 받는 명령 한 줄(로컬 eval로 부른다 — 뒤 인자는 ssh가 원격 셸에 붙인다)
  # 비밀번호(CR13-14): 부르는 셸의 FLYWAY_PASSWORD를 ssh 표준 입력 한 줄로 넘기고 원격 셸이 IFS= read -r로 받아 docker run -e FLYWAY_PASSWORD(값 없이 — 환경에서)로
  #   Flyway CLI 환경변수에 둔다 — 명령 인자·원격 명령 문자열·셸 이력에 값이 없다(ADR-009 external_app_up 방식). printf는 셸 내장(인자 목록에 안 보인다).
  echo "printf '%s\\n' \"\$FLYWAY_PASSWORD\" | ssh -o BatchMode=yes -o ConnectTimeout=10 $SERVER 'IFS= read -r FLYWAY_PASSWORD && export FLYWAY_PASSWORD &&' cd $REMOTE_BASE/$1 '&&' docker run --rm --pull=never --network seatlab_default -e FLYWAY_PASSWORD -v \\\$PWD/main-app/src/main/resources/db:/db:ro $FLYWAY_IMAGE"
}
PSQL_HEAD=$'\\set QUIET on\n\\pset tuples_only on\n\\pset format unaligned\n'
ref_db() {   # 인자: 새SHA배포폴더 회차폴더 → ref-catalog.json · ref-history.json · ref-flyway.log — 실패면 1
  local nd="$1" dir="$2"
  db_scalar "DROP DATABASE IF EXISTS seat_ref" > /dev/null 2>&1; db_scalar "CREATE DATABASE seat_ref" >> "$dir/ref-flyway.log" 2>&1 || return 1
  [[ -n "${EXT_DB_PASSWORD:-}" ]] || { echo "ref_db: 측정 DB 비밀번호 없음(ext_db_password)" >> "$dir/ref-flyway.log"; return 1; }
  ( FLYWAY_PASSWORD="$EXT_DB_PASSWORD"
    eval "$(flyway_run_cmd "$nd") -url=jdbc:postgresql://db:5432/seat_ref -user=seat -locations=filesystem:/db/migration,filesystem:/db/migration-updated-at-trigger,filesystem:/db/migration-incremental migrate" ) >> "$dir/ref-flyway.log" 2>&1 || return 1
  { echo "$PSQL_HEAD"; cat "$OPS_DIR/04-catalog.sql"; } | remote "docker exec -i seatlab-db-1 psql -X -q -U seat -d seat_ref" 2>>"$dir/ref-flyway.log" | tail -1 > "$dir/ref-catalog.json"
  { echo "$PSQL_HEAD"; cat "$OPS_DIR/05-history.sql"; } | remote "docker exec -i seatlab-db-1 psql -X -q -U seat -d seat_ref" 2>>"$dir/ref-flyway.log" | tail -1 > "$dir/ref-history.json"
  remote "docker exec -i seatlab-db-1 psql -X -q -U seat -d seat_ref -tAc \"SELECT coalesce(json_agg(json_build_object('version', version, 'checksum', checksum, 'script', script, 'success', success) ORDER BY installed_rank), '[]') FROM flyway_schema_history\"" > "$dir/ref-history-all.json" 2>>"$dir/ref-flyway.log"
  jq -e '.column != null and .index.valid == true' "$dir/ref-catalog.json" > /dev/null 2>&1 && jq -e 'length == 1' "$dir/ref-history.json" > /dev/null 2>&1
}

# ---- 끝 검사(§4.7.2 · P9 (다)(마)(사) · N11 계수) ------------------------------------------------------------------------------
end_check_sql() {   # 인자: 탐침회차 → 한 줄 JSON
  local pr="$1" has_col
  has_col=$(db_scalar "SELECT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'user_hold_quota'::regclass AND attname = 'updated_at' AND NOT attisdropped)" | tr -d '[:space:]')
  local ua="'updated_at_null', NULL, 'm', NULL, 'm_rows', NULL, 'm_rows_post_v9', NULL"
  [[ "$has_col" == t ]] && ua="'updated_at_null', (SELECT count(*) FROM user_hold_quota WHERE updated_at IS NULL),
  'm', (SELECT attmissingval::text FROM pg_attribute WHERE attrelid = 'user_hold_quota'::regclass AND attname = 'updated_at'),
  'm_rows', (SELECT count(*) FROM user_hold_quota q, mv WHERE q.updated_at = mv.m),
  'm_rows_post_v9', (SELECT count(*) FROM user_hold_quota q, mv WHERE q.updated_at = mv.m
                     AND EXISTS (SELECT 1 FROM seat_hold h WHERE h.schedule_id = q.schedule_id AND h.user_id = q.user_id AND h.held_at > mv.m)
                     AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.schedule_id = q.schedule_id AND h.user_id = q.user_id AND h.held_at <= mv.m))"
  local mv="mv AS (SELECT NULL::timestamptz AS m),"
  [[ "$has_col" == t ]] && mv="mv AS (SELECT (attmissingval::text::timestamptz[])[1] AS m FROM pg_attribute WHERE attrelid = 'user_hold_quota'::regclass AND attname = 'updated_at' AND atthasmissing),"
  local fw="'flyway', NULL"
  [[ "$(db_scalar "SELECT to_regclass('flyway_schema_history') IS NOT NULL" | tr -d '[:space:]')" == t ]] && \
    fw="'flyway', (SELECT coalesce(json_agg(json_build_object('installed_rank', installed_rank, 'version', version, 'script', script, 'checksum', checksum,
                   'execution_time', execution_time, 'success', success, 'installed_on', installed_on, 'installed_by', installed_by) ORDER BY installed_rank), '[]') FROM flyway_schema_history)"
  DB_JSON_TIMEOUT=900 db_json <<SQL
WITH $mv
actual AS (SELECT schedule_id, user_id, sum(n) AS total FROM (SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
           UNION ALL SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2) u GROUP BY 1, 2),
pairs AS (SELECT schedule_id, user_id, q.cnt, coalesce(a.total, 0) AS total FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id)),
open_s AS (SELECT id FROM product_schedule WHERE starts_at > now())
SELECT json_build_object('db_now', extract(epoch FROM clock_timestamp()),
  'mismatch_all', (SELECT count(*) FROM pairs WHERE coalesce(cnt, 0) <> total AND schedule_id IN (SELECT id FROM open_s)),
  'mismatch_probe', (SELECT count(*) FROM pairs WHERE coalesce(cnt, 0) <> total AND schedule_id = $pr),
  'mismatch_pairs', (SELECT coalesce(json_agg(json_build_object('s', schedule_id, 'u', user_id, 'cnt', cnt, 'actual', total)), '[]')
                     FROM (SELECT * FROM pairs WHERE coalesce(cnt, 0) <> total AND schedule_id IN (SELECT id FROM open_s) ORDER BY 1, 2 LIMIT 200) m),
  'over_limit_main', (SELECT count(*) FROM actual WHERE total > 2 AND schedule_id <> $pr),
  'over_limit_probe', (SELECT count(*) FROM actual WHERE total > 2 AND schedule_id = $pr),
  $ua,
  'trigger', (SELECT json_build_object('exists', true, 'enabled', tgenabled::text) FROM pg_trigger WHERE tgname = 'user_hold_quota_touch' AND NOT tgisinternal),
  'index', (SELECT json_build_object('valid', indisvalid, 'ready', indisready) FROM pg_index WHERE indexrelid = to_regclass('idx_user_hold_quota_updated_at')),
  'v10_tables', (SELECT coalesce(json_agg(table_name ORDER BY table_name), '[]') FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_name IN ('counter_safety_run', 'counter_inc_gap', 'counter_app_inject')),
  $fw);
SQL
}

# ---- S3e 주입(ADR-009 lib.sh inject_drift 복사 — 두 곳만 다르다: ① 주 부하 회차만(schedule_id = INJECT_SCHEDULE — 탐침 회차 2의 쌍을 고르지 않는다 · N10)
#      ② V10이 있을 때만 counter_app_inject에 path 'db' 행(H7 — INJECT_TRACK=1을 호출자가 to_regclass로 정한다 · 앱 경로 주입 쌍 제외 조건은 없다(이 측정에 앱 경로 주입 없음)))
inject_drift13() {
  local d="$1" l1="$2" l2="$3" h="$4" excl="${5:-}" held="${INJECT_HELD_MIN_S:-250}" margin="${INJECT_EXP_MARGIN_S:-15}" track="${INJECT_TRACK:-0}" sch="${INJECT_SCHEDULE:-1}" tk="" tj=""
  [[ "$d$l1$l2$h$held$margin$sch" =~ ^[0-9]+$ && "$track" =~ ^[01]$ ]] || return 2
  [[ -z "$excl" || "$excl" =~ ^(\([0-9]+,[0-9]+\),?)+$ ]] || return 2
  if (( track )); then
    tk="
CREATE TEMP TABLE tracked ON COMMIT DROP AS
WITH t AS (INSERT INTO counter_app_inject (at, tx_started_at, path, kind, schedule_id, user_id, delta, cnt_after)
           SELECT clock_timestamp(), now(), 'db', i.kind, i.schedule_id, i.user_id, CASE WHEN i.kind = 'H' THEN 1 ELSE -1 END,
                  CASE WHEN i.kind = 'D' THEN NULL WHEN i.kind = 'H' THEN i.cnt_before + 1 ELSE i.cnt_before - 1 END
           FROM inj i ORDER BY i.kind, i.schedule_id, i.user_id RETURNING 1)
SELECT count(*) AS n FROM t;"
    tj="
  'tracked', (SELECT n FROM tracked),"
  fi
  db_json <<SQL
BEGIN;
CREATE TEMP TABLE inj (kind text, schedule_id bigint, user_id bigint, cnt_before int, held_at timestamptz, expires_at timestamptz) ON COMMIT DROP;
CREATE TEMP TABLE excl (schedule_id bigint, user_id bigint) ON COMMIT DROP;
$( [[ -n "$excl" ]] && echo "INSERT INTO excl VALUES ${excl%,};" )
INSERT INTO inj
SELECT 'D', q.schedule_id, q.user_id, q.cnt, h.held_at, h.expires_at
FROM seat_hold h JOIN user_hold_quota q ON q.schedule_id = h.schedule_id AND q.user_id = h.user_id
JOIN product_schedule s ON s.id = h.schedule_id AND s.starts_at > now()
WHERE q.schedule_id = $sch AND h.held_at <= now() - make_interval(secs => $held) AND h.expires_at >= now() + make_interval(secs => $margin) AND q.cnt = 1
  AND NOT EXISTS (SELECT 1 FROM excl e WHERE e.schedule_id = q.schedule_id AND e.user_id = q.user_id)
ORDER BY h.id LIMIT $d;
INSERT INTO inj
SELECT 'L1', q.schedule_id, q.user_id, q.cnt, h.held_at, h.expires_at
FROM seat_hold h JOIN user_hold_quota q ON q.schedule_id = h.schedule_id AND q.user_id = h.user_id
JOIN product_schedule s ON s.id = h.schedule_id AND s.starts_at > now()
WHERE q.schedule_id = $sch AND h.held_at <= now() - make_interval(secs => $held) AND h.expires_at >= now() + make_interval(secs => $margin) AND q.cnt = 1
  AND NOT EXISTS (SELECT 1 FROM excl e WHERE e.schedule_id = q.schedule_id AND e.user_id = q.user_id)
  AND NOT EXISTS (SELECT 1 FROM inj i WHERE i.schedule_id = q.schedule_id AND i.user_id = q.user_id)
ORDER BY h.id LIMIT $l1;
INSERT INTO inj
SELECT 'L2', q.schedule_id, q.user_id, q.cnt, NULL, NULL
FROM reservation r JOIN user_hold_quota q ON q.schedule_id = r.schedule_id AND q.user_id = r.user_id
JOIN product_schedule s ON s.id = r.schedule_id AND s.starts_at > now()
WHERE q.schedule_id = $sch AND r.status = 'CONFIRMED' AND q.cnt = 1
  AND NOT EXISTS (SELECT 1 FROM seat_hold x WHERE x.schedule_id = q.schedule_id AND x.user_id = q.user_id)
  AND NOT EXISTS (SELECT 1 FROM reservation r2 WHERE r2.schedule_id = q.schedule_id AND r2.user_id = q.user_id AND r2.status = 'CONFIRMED' AND r2.id <> r.id)
  AND NOT EXISTS (SELECT 1 FROM excl e WHERE e.schedule_id = q.schedule_id AND e.user_id = q.user_id)
  AND NOT EXISTS (SELECT 1 FROM inj i WHERE i.schedule_id = q.schedule_id AND i.user_id = q.user_id)
ORDER BY r.id LIMIT $l2;
INSERT INTO inj
SELECT 'H', q.schedule_id, q.user_id, q.cnt, NULL, NULL
FROM user_hold_quota q JOIN product_schedule s ON s.id = q.schedule_id AND s.starts_at > now()
WHERE q.schedule_id = $sch AND q.cnt = 1
  AND NOT EXISTS (SELECT 1 FROM excl e WHERE e.schedule_id = q.schedule_id AND e.user_id = q.user_id)
  AND NOT EXISTS (SELECT 1 FROM inj i WHERE i.schedule_id = q.schedule_id AND i.user_id = q.user_id)
ORDER BY q.schedule_id, q.user_id LIMIT $h;
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
  AND EXISTS (SELECT 1 FROM product_schedule s WHERE s.id = i.schedule_id AND s.starts_at > now()));
DELETE FROM inj i USING dropped d WHERE d.schedule_id = i.schedule_id AND d.user_id = i.user_id;
CREATE TEMP TABLE applied ON COMMIT DROP AS
WITH d AS (DELETE FROM user_hold_quota q USING inj i WHERE i.kind = 'D' AND q.schedule_id = i.schedule_id AND q.user_id = i.user_id RETURNING 1),
     l AS (UPDATE user_hold_quota q SET cnt = q.cnt - 1 FROM inj i WHERE i.kind IN ('L1', 'L2') AND q.schedule_id = i.schedule_id AND q.user_id = i.user_id RETURNING 1),
     h AS (UPDATE user_hold_quota q SET cnt = q.cnt + 1 FROM inj i WHERE i.kind = 'H' AND q.schedule_id = i.schedule_id AND q.user_id = i.user_id RETURNING 1)
SELECT (SELECT count(*) FROM d) AS d, (SELECT count(*) FROM l) AS l, (SELECT count(*) FROM h) AS h;$tk
SELECT json_build_object('at', clock_timestamp(), 'at_epoch', extract(epoch FROM clock_timestamp()), 'tracked_v10', $track,$tj
  'counts', (SELECT json_build_object('D', count(*) FILTER (WHERE kind = 'D'), 'L1', count(*) FILTER (WHERE kind = 'L1'),
                                      'L2', count(*) FILTER (WHERE kind = 'L2'), 'H', count(*) FILTER (WHERE kind = 'H')) FROM inj),
  'applied', (SELECT json_build_object('D', d, 'L', l, 'H', h) FROM applied),
  'dropped_after_lock', (SELECT count(*) FROM dropped),
  'pairs', (SELECT coalesce(json_agg(json_build_object('kind', kind, 'schedule_id', schedule_id, 'user_id', user_id, 'cnt_before', cnt_before,
                                                       'held_at', held_at, 'expires_at', expires_at) ORDER BY kind, schedule_id, user_id), '[]') FROM inj));
COMMIT;
SQL
}
