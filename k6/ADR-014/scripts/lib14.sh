#!/usr/bin/env bash
# ADR-014 하네스 공통 함수 — ADR-009 lib.sh(원격 실행 · 배포 · 시계 차 · DB 대기 표본 · 기록 덤프 · I/O 스냅숏 · 서버 측정기)를 source 하고(측정-3.6 '재사용'),
# 이 측정에 필요한 것만 더한다: 기동 손잡이·V11 위치 렌더(compose.expiry.yml) · 이미지 고정 · 앱 설정 수집 · 시드 확인(seat-map-index 포함) · 테이블 통계 스냅숏(P6) ·
# 앱−DB 시각(M14) · 1초 상태 표본 · 0.5초 H4 표지 표본(psql-sampler.sh) · 표본 사본 복구 · 측정기 기동·내림(ADR-009 run.sh probes_start/stop 복사 — run.sh 안에 있어 source로 못 쓴다).
# run.sh가 source 한다(동결 사본). ADR-009 lib.sh는 수정하지 않는다.
# shellcheck source=../../ADR-009/scripts/lib.sh
source "$LIB9_DIR/lib.sh"
LIB14_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
set +e

C14="k6/ADR-014/compose.expiry.yml"
# 서버 측정기(lib.sh probe_server_up)도 같은 -f 묶음을 쓴다(COMPOSE_EXTRA — 프로젝트 모델이 회차 기동과 같게)
COMPOSE_EXTRA="-f k6/ADR-009/compose.tmpfs.yml -f $C14"
compose14() { echo "docker compose -p seatlab -f $COMPOSE_FILE $COMPOSE_EXTRA"; }
# ADR-009 compose.yml의 조건 변수는 빈 값(키 없음 · 경고 없음)으로 늘 넘긴다(ADR-013 lib13.sh ADR9_BLANK와 같다 — 앱 JSON은 오버레이가 통째로 바꾼다)
ADR9_BLANK="LIMIT_TRIGGER= SALES_CLOSE_OFF= DRIFT_A= DRIFT_EXTERNAL= DRIFT_C_ENABLED= DRIFT_C= DRIFT_CLEANUP= INC_METHOD= INC_ENABLED= INC_INTERVAL= INC_TRACK= SAFETY_ENABLED= APP_DETECT_SHAPE= INJECT_APP= INJECT_APP_PATHS="
# 회차 환경(run.sh가 APP_ENV14 — plan.json app_env의 KEY=VALUE — 와 RES_ENV14 — 자원 — 를 정한다)
env14() { echo "$ADR9_BLANK APP_TAG=${APP_TAG:-none} $APP_ENV14 $RES_ENV14"; }

compose_down14() {   # 인자: 배포폴더 — 모든 오버레이로 내리고 DB 볼륨·seatlab 컨테이너가 정말 없는지(ADR-009 compose_down · ADR-013 compose_down13과 같은 확인)
  local id="$1" i
  for i in 1 2 3; do
    remote "cd $REMOTE_BASE/$id && env $(env14) $(compose14) -f k6/ADR-009/compose.reconciler.yml -f $PROBE_FILE down -v --remove-orphans" >&2
    if remote "! docker volume inspect seatlab_pgdata > /dev/null 2>&1 && [ -z \"\$(docker ps -aq --filter label=com.docker.compose.project=seatlab)\" ]" >&2; then return 0; fi
    log "compose down14: 볼륨 또는 컨테이너가 남음 — retry in 10s"; sleep 10
  done
  return 1
}
compose_up14() {   # 인자: 배포폴더 — 빌드·받기 없음(이미지 고정 — 측정-1) · db·redis healthcheck 통과까지 기다린다
  remote "cd $REMOTE_BASE/$1 && env $(env14) $(compose14) up -d --no-build --wait db redis app" >&2
}
image_id() { remote "docker image inspect --format '{{.Id}}' $1" 2>/dev/null | tr -d '[:space:]'; }
ctr_image() { remote "docker inspect -f '{{.Image}}' $1" 2>/dev/null | tr -d '[:space:]'; }

# 초기화 재시도(ADR-009 run.sh reset_with_retry 복사 — lib.sh 밖에 있어 source로 못 쓴다)
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

# 적용된 설정(ADR-009 run.sh app_config와 같은 모양 — 비밀 제외) → app-config.json. 판정은 plan14.py --config-check(요약기 · cond-config-mismatch · expiry-mode-mismatch · ttl-mismatch)
app_config14() {   # 인자: 회차폴더
  local dir="$1" props cpu pool containers idx
  props=$(actuator configprops | jq -c '[.contexts[].beans[] | select(.prefix | test("^(seat\\.hold(\\..+)?|spring\\.datasource\\.hikari|spring\\.flyway|server)$"))
      | {prefix, properties: (.properties | with_entries(select(.key | test("password|secret"; "i") | not)))}]') || return 1
  cpu=$(actuator metrics/system.cpu.count | jq -c '[.measurements[].value]') || return 1
  pool=$(actuator metrics/hikaricp.connections.max | jq -c '[.measurements[].value]') || return 1
  containers=$(remote "docker inspect seatlab-app-1 seatlab-db-1 seatlab-redis-1 --format '{{json .Name}} {{json .HostConfig}} {{json .Image}}'" \
    | jq -Rsc 'split("\n") | map(select(length>0) | split(" ") as $p | {name: ($p[0]|fromjson), cpu: ($p[1:-1]|join(" ")|fromjson|.NanoCpus), mem: ($p[1:-1]|join(" ")|fromjson|.Memory), image: ($p[-1]|fromjson)})') || return 1
  idx=$(db_indexes) || return 1
  jq -n --argjson p "$props" --argjson c "$cpu" --argjson h "$pool" --argjson k "$containers" --argjson i "$idx" \
    '{configprops:$p, "system.cpu.count":$c, "hikaricp.connections.max":$h, containers:$k, db_indexes:$i}' > "$dir/app-config.json"
}

# 시드 확인(record-level — 측정-9 seed-mismatch · seat-map-index-missing): 회차 수 · 좌석 수 · 모두 AVAILABLE · 홀드·예약·쿼터 행 0 · V11 인덱스 · Flyway 이력의 V11
seed_check14() {   # 인자: 기대회차 기대좌석(회차당) → 한 줄 JSON
  db_json <<SQL
SELECT json_build_object('schedules', (SELECT count(*) FROM product_schedule), 'seats', (SELECT count(*) FROM product_seat),
  'available', (SELECT count(*) FROM product_seat WHERE status = 'AVAILABLE'), 'holds', (SELECT count(*) FROM seat_hold),
  'reservations', (SELECT count(*) FROM reservation), 'quota_rows', (SELECT count(*) FROM user_hold_quota),
  'seat_map_index', (SELECT count(*) FROM pg_indexes WHERE tablename = 'product_seat' AND indexname = 'idx_product_seat_schedule_id'),
  'flyway_v11', (SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success),
  'want_schedules', $1, 'want_seats', $(( $1 * $2 )));
SQL
}

# 테이블 통계(P6 · 측정-3.6 — k6 직전 · 직후 · 수렴 대기 끝): product_seat · seat_hold 누적 행 연산
table_stats() {
  db_json <<'SQL'
SELECT json_build_object('t', clock_timestamp(), 't_epoch', extract(epoch FROM clock_timestamp()),
  'tables', (SELECT json_object_agg(relname, json_build_object('n_tup_ins', n_tup_ins, 'n_tup_upd', n_tup_upd, 'n_tup_del', n_tup_del, 'n_tup_hot_upd', n_tup_hot_upd,
                                                             'n_dead_tup', n_dead_tup, 'n_live_tup', n_live_tup))
             FROM pg_stat_user_tables WHERE relname IN ('product_seat', 'seat_hold', 'reservation', 'user_hold_quota')),
  'deadlocks', (SELECT deadlocks FROM pg_stat_database WHERE datname = 'seat'),
  'confirmed', (SELECT count(*) FROM reservation WHERE status = 'CONFIRMED'));
SQL
}

# 앱 시각 − DB 시각(M14 · [구현 검증] #11): 같은 ssh 한 번에 앱 컨테이너 date → DB clock_timestamp → 앱 컨테이너 date. 차 = (앞 + 뒤) ÷ 2 − DB(초) · 왕복 = 뒤 − 앞
app_db_clock() {
  timeout 30 ssh -o BatchMode=yes -o ConnectTimeout=5 "$SERVER" bash -s <<'REMOTE'
a=$(docker exec seatlab-app-1 date +%s.%N 2>/dev/null)
d=$(docker exec seatlab-db-1 psql -U seat -d seat -tAc "SELECT extract(epoch FROM clock_timestamp())" 2>/dev/null | tr -d '[:space:]')
b=$(docker exec seatlab-app-1 date +%s.%N 2>/dev/null)
printf '{"app_before":%s,"db":%s,"app_after":%s}\n' "${a:-null}" "${d:-null}" "${b:-null}"
REMOTE
}

# 서버 쪽 DB 표본기(psql-sampler.sh — 표준 입력으로 보낸다 · host_sampler와 같은 방식). 인자: 사본 주기ms state|h4 만료행틱 세션이름
psql_sampler() {
  remote "bash -s -- '$1' '$2' '$3' '$4' '$5'" < "$LIB14_DIR/psql-sampler.sh"
}
psql_sampler_kill() {   # 인자: 사본 — 그 사본 경로의 서버 루프만(아래 [b] 패턴은 pkill을 띄운 셸 자신과 맞지 않게)
  remote "pkill -f '[b]ash -s -- $1 ' || true" > /dev/null 2>&1
}
# 회차 끝: 서버 사본을 받아 흘려받은 줄보다 많으면 사본으로 다시 만든다(ADR-009 recover_samples와 같은 규칙) · 서버 사본은 지운다(못 지우면 errors.log + 사유 줄)
recover_copy() {   # 인자: 회차폴더 사본 로컬파일이름
  local dir="$1" copy="$2" f="$1/$3" tmp="$1/.copy-remote.jsonl" n r
  if remote "cat '$copy'" > "$tmp" 2>/dev/null && [[ -s "$tmp" ]]; then
    n=$(grep -c '^{' "$f" 2>/dev/null); n=${n:-0}; r=$(grep -c '^{' "$tmp")
    if (( r > n )); then grep '^{' "$tmp" > "$f"; echo "$(date -Is) $3 recovered-from-server $n→$r" >> "$dir/sampler-recovery.txt"
    else echo "$(date -Is) $3 stream-complete $n (server $r)" >> "$dir/sampler-recovery.txt"; fi
  else
    echo "$(date -Is) $3 server-copy-unavailable" >> "$dir/sampler-recovery.txt"
  fi
  remote "cat '$copy.err' 2>/dev/null" > "$dir/.${3%.jsonl}.err" 2>/dev/null; [[ -s "$dir/.${3%.jsonl}.err" ]] && mv "$dir/.${3%.jsonl}.err" "$dir/${3%.jsonl}.err" || rm -f "$dir/.${3%.jsonl}.err"
  if ! remote "rm -f '$copy' '$copy.err' && test ! -e '$copy'" > /dev/null 2>&1; then
    echo "$(date -Is) sampler copy not removed: $copy" >> "$dir/errors.log"; echo "$(date -Is) sampler-copy-not-removed $copy" >> "$dir/sampler-recovery.txt"
  fi
  rm -f "$tmp"
}

# ---- 수집기(ADR-009 run.sh start_pollers의 서버·PC·대기·Hikari 표본 + ADR-014 상태·H4 표본) -------------------------------------------
# 받는 쪽은 bash read 루프(ADR-007 리뷰 A11 — mawk 입력 버퍼링 재발 금지) · 끝나면 1초 뒤 다시 연다(errors.log 한 줄 — 무음 공백 금지)
start_pollers14() {   # 인자: 회차폴더 kind(main|cost) 만료행틱 → 백그라운드 PID들을 출력
  local dir="$1" kind="$2" every="$3" K6_COMM; K6_COMM="$(basename "$K6_BIN" | cut -c1-15)"
  ( while :; do   # 서버 시각 줄 + docker stats(10초 — M9 DB CPU · 측정-4 MR-3)
      remote "echo \"{\\\"t\\\":\\\"\$(date -Is)\\\",\\\"load\\\":\\\"\$(cut -d' ' -f1-3 /proc/loadavg)\\\"}\"; docker stats --no-stream --format '{{json .}}'" >> "$dir/timeline-server.jsonl" 2>>"$dir/.stats.err"
      sleep 10
    done ) > /dev/null 2>&1 < /dev/null & echo $!
  ( while :; do   # k6 PC(5초 — 주 k6 프로세스 CPU · 측정기 k6 제외)
      if [[ -s "$dir/probe-pc.pid" ]]; then p=$(pgrep -x "$K6_COMM" | grep -vxF -f "$dir/probe-pc.pid" | head -1); else p=$(pgrep -x "$K6_COMM" | head -1); fi
      [[ -n "$p" ]] && echo "{\"t\":\"$(date -Is)\",\"k6_pcpu\":$(ps -o pcpu= -p "$p" | tr -d ' '),\"k6_rss_kb\":$(ps -o rss= -p "$p" | tr -d ' '),\"load\":\"$(cut -d' ' -f1-3 /proc/loadavg)\"}" >> "$dir/timeline-client.jsonl"
      sleep 5
    done ) > /dev/null 2>&1 < /dev/null & echo $!
  ( while :; do   # 락·대기 이벤트(0.5초 — lib.sh db_sampler · env-stall ① 업무 세션 Lock 대기)
      db_sampler "$SAMPLER_COPY" 2>/dev/null < /dev/null \
        | while IFS= read -r line; do
            case "$line" in
              *'"lock_waiting"'*) printf '%s\n' "$line" >> "$dir/timeline-locks.jsonl" ;;
              *'"waits"'*) printf '%s\n' "$line" >> "$dir/timeline-waits.jsonl" ;;
            esac
          done
      echo "$(date -Is) db_sampler ended rc=${PIPESTATUS[0]}/${PIPESTATUS[1]} — reopen in 1s" >> "$dir/errors.log"; sleep 1
    done ) > /dev/null 2>&1 < /dev/null & echo $!
  ( while :; do   # Hikari pending·active(1초 — env-stall ②③ · 시각 = PC ms)
      pend=$(curl -fsS --max-time 2 "$BASE_URL/actuator/metrics/hikaricp.connections.pending" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null)
      act=$(curl -fsS --max-time 2 "$BASE_URL/actuator/metrics/hikaricp.connections.active" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null)
      [[ -n "$pend" && -n "$act" ]] && echo "{\"t\":\"$(date -Is)\",\"t_ms\":$(date +%s%3N),\"pending\":$pend,\"active\":$act}" >> "$dir/timeline-hikari.jsonl"
      sleep 1
    done ) > /dev/null 2>&1 < /dev/null & echo $!
  if [[ "$kind" == main ]]; then   # 1초 상태 표본(주 셀만 — 리뷰 MR-1) · 0.5초 H4 표지(주 셀 — M10)
    ( while :; do
        psql_sampler "$STATE_COPY" 1000 state "$every" seatlab-harness-state 2>/dev/null < /dev/null \
          | while IFS= read -r line; do printf '%s\n' "$line" >> "$dir/timeline-seatstate.jsonl"; done
        echo "$(date -Is) state sampler ended — reopen in 1s" >> "$dir/errors.log"; sleep 1
      done ) > /dev/null 2>&1 < /dev/null & echo $!
    ( while :; do
        psql_sampler "$H4_COPY" 500 h4 1 seatlab-harness-h4 2>/dev/null < /dev/null \
          | while IFS= read -r line; do printf '%s\n' "$line" >> "$dir/timeline-h4.jsonl"; done
        echo "$(date -Is) h4 sampler ended — reopen in 1s" >> "$dir/errors.log"; sleep 1
      done ) > /dev/null 2>&1 < /dev/null & echo $!
  fi
}
stop_pollers14() {   # ADR-009 stop_pollers와 같다(루프와 그 자식까지)
  local p kids
  for p in "$@"; do kids=$(pgrep -P "$p" 2>/dev/null); kill "$p" 2>/dev/null; [[ -n "$kids" ]] && kill $kids 2>/dev/null; done
}

# ---- 측정기(ADR-011 --probes — 측정-1 '켬' · env-stall 판정) — ADR-009 run.sh probes_start/probes_stop 복사(값·파일 이름 같다 → tail.py --probe-check 그대로) ----
probes_start14() {   # 인자: 회차폴더 길이초
  local dir="$1"; PROBE_DUR="$2"
  ( t=$(date +%s.%N); probe_server_up "$DEPLOY_ID" "$PROBE_DUR" >> "$dir/probe-server.log" 2>&1; rc=$?; echo "$t $(date +%s.%N) $rc" > "$dir/.probe-server-up" ) > /dev/null 2>&1 &
  PROBE_SRV_JOB=$!
  PROBE_PC_T0=$(date +%s.%N)
  env BASE_URL="$BASE_URL" PROBE_RATE="$PROBE_RATE" PROBE_DURATION_S="$PROBE_DUR" K6_CSV_TIME_FORMAT=unix_milli \
    "$PROBE_K6_BIN" run --quiet --out "csv=$dir/k6-probe-pc.csv.gz" "$SCEN9/probe-ping.js" > "$dir/probe-pc.log" 2>&1 < /dev/null &
  PROBE_PC_PID=$!
  echo "$PROBE_PC_PID" > "$dir/probe-pc.pid"
}
probes_stop14() {   # 인자: 회차폴더 주k6시작epoch 주k6끝epoch → probe-status.json
  local dir="$1" i killed=false pc_rc pc_sig pc_end srv up0="" up1="" uprc="" up_deadline
  up_deadline=$(awk -v t="$PROBE_PC_T0" -v m="${PROBE_UP_TIMEOUT_S:-120}" 'BEGIN { printf "%d", t + m + 10 }')
  while kill -0 "$PROBE_SRV_JOB" 2>/dev/null && (( $(date +%s) < up_deadline )); do sleep 1; done
  if kill -0 "$PROBE_SRV_JOB" 2>/dev/null; then
    pkill -P "$PROBE_SRV_JOB" 2>/dev/null; kill "$PROBE_SRV_JOB" 2>/dev/null
    echo "$(date -Is) probe server up: 시한 초과 — 배경 기동을 끔" >> "$dir/errors.log"
    [[ -s "$dir/.probe-server-up" ]] || echo "$PROBE_PC_T0 $(date +%s.%N) killed" > "$dir/.probe-server-up"
  fi
  wait "$PROBE_SRV_JOB" 2>/dev/null
  pc_sig=$(date +%s.%N)
  kill -INT "$PROBE_PC_PID" 2>/dev/null
  for i in $(seq 1 60); do kill -0 "$PROBE_PC_PID" 2>/dev/null || break; sleep 0.5; done
  if kill -0 "$PROBE_PC_PID" 2>/dev/null; then kill -KILL "$PROBE_PC_PID" 2>/dev/null; killed=true; fi
  wait "$PROBE_PC_PID" 2>/dev/null; pc_rc=$?
  pc_end=$(date +%s.%N)
  srv=$(probe_server_collect "$DEPLOY_ID" "$dir" 2>>"$dir/errors.log") && jq -e . <<< "$srv" > /dev/null 2>&1 || srv=null
  [[ -s "$dir/.probe-server-up" ]] && read -r up0 up1 uprc < "$dir/.probe-server-up"
  rm -f "$dir/.probe-server-up"
  local f; for f in probe-pc.log probe-server.log; do [[ -f "$dir/$f" ]] && gzip -f "$dir/$f"; done
  jq -n --argjson rate "$PROBE_RATE" --argjson dur "$PROBE_DUR" --argjson k0 "$2" --argjson k1 "$3" \
        --arg pbin "$PROBE_K6_BIN" --arg pver "$PROBE_K6_VERSION" --argjson ppid "$PROBE_PC_PID" --argjson pt0 "$PROBE_PC_T0" --argjson psig "$pc_sig" \
        --argjson pend "$pc_end" --argjson prc "$pc_rc" --argjson killed "$killed" \
        --arg image "$PROBE_IMAGE" --arg up0 "$up0" --arg up1 "$up1" --arg uprc "$uprc" --argjson srv "$srv" \
    '{rate: $rate, duration_s: $dur, main: {k6_t0_epoch: $k0, k6_end_epoch: $k1},
      pc: {bin: $pbin, version: $pver, pid: $ppid, start_epoch: $pt0, stop_signal_epoch: $psig, end_epoch: $pend, exit_code: $prc, killed: $killed},
      server: ({image: $image, up_start_epoch: ($up0 | tonumber? // null), up_end_epoch: ($up1 | tonumber? // null), up_rc: ($uprc | tonumber? // null)}
               + (if $srv == null then {collect: null} else $srv end))}' > "$dir/probe-status.json"
}
