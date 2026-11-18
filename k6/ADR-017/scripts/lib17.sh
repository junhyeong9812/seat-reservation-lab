#!/usr/bin/env bash
# ADR-017 하네스 공통 함수 — ADR-015 lib15.sh(→ ADR-014 lib14.sh → ADR-009 lib.sh: 원격 실행 · 배포 · 이미지 고정 · 기동 · 시드 · 시계 차 · 표본기 · 동결 사본)를 source 하고
#   (재사용 · 그 파일들은 수정하지 않는다) 이 측정에 필요한 것만 더한다: compose 오버레이 k6/ADR-017/compose.pay17.yml(앱 JSON · 흉내 PG 서비스) · 흉내 PG 호출(상태 · 리셋 · 주입 · 원장).
# run17.sh가 source 한다(동결 사본). 필요: LIB9_DIR(ADR-009 scripts) · LIB14_SRC(ADR-014 scripts) · LIB15 위치 = 이 파일 기준 ../../ADR-015/scripts.
# 흉내 PG 주소: k6 PC에서 본 주소 = MOCKPG_URL(기본 http://<SERVER_HOST>:8104 — 앱 컨테이너는 compose 망 안 http://mock-pg:8104를 쓴다 · plan17 PG_BASE_URL_APP).
LIB17_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../ADR-015/scripts/lib15.sh
source "${LIB15_SRC:-$LIB17_DIR/../../ADR-015/scripts}/lib15.sh"
set +e

C17="k6/ADR-017/compose.pay17.yml"
COMPOSE_EXTRA="$COMPOSE_EXTRA -f $C17"   # lib15(tmpfs · ADR-015 오버레이) 뒤 — compose14 · compose_down14 · probe_compose가 호출 때 읽는다
MOCKPG_URL="${MOCKPG_URL:-http://${SERVER_HOST}:8104}"
compose17() { compose14; }

# 기동(이미지 고정 · 빌드 없음): db · redis · mock-pg(상태 확인) · app — app은 mock-pg healthy 뒤에 뜬다(compose.pay17.yml depends_on)
compose_up17() {   # 인자: 배포폴더
  remote "cd $REMOTE_BASE/$1 && env $(env14) $(compose14) up -d --no-build --wait db redis mock-pg app" >&2
}

# 흉내 PG(모든 호출에 상한 — 응답 없는 서버에 매달리지 않게)
pg_ready() {   # 인자: 최대초 — 없는 uid 조회가 404면 준비됨
  local deadline=$((SECONDS + ${1:-120})) code
  while :; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$MOCKPG_URL/payments/__health" 2>/dev/null)
    [[ "$code" == 404 ]] && return 0
    (( SECONDS > deadline )) && { log "mock-pg 준비 시간 초과($MOCKPG_URL — 마지막 $code)"; return 1; }
    sleep 2
  done
}
pg_reset() { curl -fsS --max-time 30 -o /dev/null -w '%{http_code}' -X POST "$MOCKPG_URL/admin/reset"; }   # 204 — 원장 · 호출 수 · 주입 설정을 모두 비운다
pg_set_faults() {   # 인자: Faults.Config JSON(plan17 render .pg.faults) → 표준 출력 = mock-pg가 돌려준 적용 설정(그대로 기록)
  curl -fsS --max-time 30 -X POST -H 'Content-Type: application/json' --data "$1" "$MOCKPG_URL/admin/faults"
}
pg_ledger() { curl -fsS --max-time 120 "$MOCKPG_URL/ledger"; }   # {payments:[…], ops:{op: 수}}

# ---- 회차 수집(run17.sh) ---------------------------------------------------------------------------------------------------------
# 적용 설정(lib15 app_config15와 같은 모양 — configprops 접두어에 seat.compensation · seat.payment를 더한다 · INTERFACE §6.10) → app-config.json
app_config17() {   # 인자: 회차폴더
  local dir="$1" props cpu pool containers idx nofile
  props=$(actuator configprops | jq -c '[.contexts[].beans[] | select(.prefix | test("^(seat\\.confirm|seat\\.hold(\\..+)?|seat\\.compensation|seat\\.payment|spring\\.datasource\\.hikari|spring\\.flyway|server)$"))
      | {prefix, properties: (.properties | with_entries(select(.key | test("password|secret"; "i") | not)))}]') || return 1
  cpu=$(actuator metrics/system.cpu.count | jq -c '[.measurements[].value]') || return 1
  pool=$(actuator metrics/hikaricp.connections.max | jq -c '[.measurements[].value]') || return 1
  containers=$(remote "docker inspect seatlab-app-1 seatlab-db-1 seatlab-redis-1 seatlab-mock-pg-1 --format '{{json .Name}} {{json .HostConfig}} {{json .Image}}'" \
    | jq -Rsc 'split("\n") | map(select(length>0) | split(" ") as $p | {name: ($p[0]|fromjson), cpu: ($p[1:-1]|join(" ")|fromjson|.NanoCpus), mem: ($p[1:-1]|join(" ")|fromjson|.Memory), image: ($p[-1]|fromjson)})') || return 1
  idx=$(db_indexes) || return 1
  nofile=$(remote "docker exec seatlab-app-1 cat /proc/1/limits" 2>/dev/null | awk '/^Max open files/ { printf "{\"soft\":%s,\"hard\":%s}", $4, $5 }')
  jq -e 'type == "object"' <<< "${nofile:-null}" > /dev/null 2>&1 || nofile=null
  jq -n --argjson p "$props" --argjson c "$cpu" --argjson h "$pool" --argjson k "$containers" --argjson i "$idx" --argjson nf "$nofile" \
    '{configprops:$p, "system.cpu.count":$c, "hikaricp.connections.max":$h, containers:$k, db_indexes:$i, app_nofile:$nf}' > "$dir/app-config.json"
}
# 배수 조회 한 번(INTERFACE §6.5 — 한 문장): pending · open · open_unheld
drain_poll17() { db_json <<'SQL'
SELECT json_build_object('pending', (SELECT count(*) FROM compensation_task WHERE status IN ('PENDING', 'IN_PROGRESS')),
  'open', (SELECT count(*) FROM payment_intent WHERE state = 'OPEN'),
  'open_unheld', (SELECT count(*) FROM payment_intent i WHERE i.state = 'OPEN' AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.id = i.hold_id AND h.expires_at > now())));
SQL
}
db_dump17() { db_json < "$LIB17_DIR/dump17.sql"; }
# 앱 kill · 재기동(INTERFACE §6.7 — SIGKILL · 같은 컨테이너를 곧바로 docker start)
app_kill17() { remote "docker kill -s KILL seatlab-app-1" > /dev/null 2>&1; }
app_start17() { remote "docker start seatlab-app-1" > /dev/null 2>&1; }
app_started_at17() { remote "docker inspect -f '{{.State.StartedAt}}' seatlab-app-1" 2>/dev/null | tr -d '[:space:]'; }
