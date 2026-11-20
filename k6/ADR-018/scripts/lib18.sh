#!/usr/bin/env bash
# ADR-018 하네스 공통 함수 — ADR-017 lib17.sh(→ lib15 → lib14 → ADR-009 lib.sh: 원격 실행 · 배포 · 이미지 고정 · 기동 · 시드 · 시계 차 · 표본기 · 흉내 PG 호출)를 source 하고
#   (재사용 · 그 파일들은 수정하지 않는다) 이 측정에 필요한 것만 더한다: compose 오버레이 k6/ADR-018/compose.pay18.yml(앱 JSON — 묶음 손잡이 · 경계 셀 눈금) ·
#   앱 설정 수집(seat.hold-group 접두어 추가) · 배수 조회(남은 홀드 수 추가) · DB 덤프(V16 열 · hold_group · seat_hold).
# run18.sh가 source 한다(동결 사본). 필요: LIB9_DIR · LIB14_SRC · LIB15_SRC · LIB17_SRC(ADR-017 scripts — 기본 = 이 파일 기준 ../../ADR-017/scripts).
LIB18_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../ADR-017/scripts/lib17.sh
source "${LIB17_SRC:-$LIB18_DIR/../../ADR-017/scripts}/lib17.sh"
set +e

C18="k6/ADR-018/compose.pay18.yml"
COMPOSE_EXTRA="$COMPOSE_EXTRA -f $C18"   # lib17(tmpfs · ADR-015 · ADR-017 오버레이 — mock-pg 서비스) 뒤 — 앱 JSON을 이 파일이 통째로 다시 쓴다
compose18() { compose14; }
compose_up18() { compose_up17 "$@"; }

# 적용 설정(lib17 app_config17과 같은 모양 — configprops 접두어에 seat.hold-group을 더한다 · INTERFACE §6.10) → app-config.json
app_config18() {   # 인자: 회차폴더
  local dir="$1" props cpu pool containers idx nofile
  props=$(actuator configprops | jq -c '[.contexts[].beans[] | select(.prefix | test("^(seat\\.confirm|seat\\.hold(\\..+)?|seat\\.hold-group|seat\\.compensation|seat\\.payment|spring\\.datasource\\.hikari|spring\\.flyway|server)$"))
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
# 배수 조회 한 번(INTERFACE §6.5 — 한 문장): pending · open · open_unheld · holds(남은 홀드 행 — 만료 배치가 다 지웠는가)
drain_poll18() { db_json <<'SQL'
SELECT json_build_object('pending', (SELECT count(*) FROM compensation_task WHERE status IN ('PENDING', 'IN_PROGRESS')),
  'open', (SELECT count(*) FROM payment_intent WHERE state = 'OPEN'),
  'open_unheld', (SELECT count(*) FROM payment_intent i WHERE i.state = 'OPEN' AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.id = i.hold_id AND h.expires_at > now())),
  'holds', (SELECT count(*) FROM seat_hold));
SQL
}
db_dump18() { db_json < "$LIB18_DIR/dump18.sql"; }
# 부하 중 홀드 표본 한 번(INTERFACE §6.12 · 리뷰 H1-1 — 가벼운 한 문장 · 같은 스냅숏): 남은 홀드 · 묶음 홀드 · 살아 있는 묶음 · 부분 선점 묶음(홀드 수 ≠ seat_count) ·
#   만료 갈림 묶음(expires_at 둘 이상) · AVAILABLE 좌석 · free_pairs(노리는 범위 안에서 더 잡을 수 있는 쌍 — 같은 줄 이웃 두 석이 모두 AVAILABLE ·
#   메인 판단 2026-11-20 재고 소진 정의) · DB 시각 · 쿼리 시간(ms — statement_timestamp부터)
#   인자: 쌍 시작 좌석 최댓값(plan seed.pair_max_start) · 종류(row = 줄 안 모든 시작 · burst = 4석 칸의 첫째 · 둘째 자리만)
hold_sample18() {
  local max="$1" kind="$2" extra=""
  [[ "$max" =~ ^[0-9]+$ && "$kind" =~ ^(row|burst)$ ]] || return 2
  [[ "$kind" == burst ]] && extra="AND (a.id - 1) % 50 < 48 AND ((a.id - 1) % 50) % 4 IN (0, 1)"
  DB_JSON_TIMEOUT=20 db_json <<SQL
WITH g AS (SELECT group_id, count(*) AS n, count(DISTINCT expires_at) AS e FROM seat_hold WHERE group_id IS NOT NULL GROUP BY group_id)
SELECT json_build_object('t_ms', extract(epoch FROM statement_timestamp()) * 1000,
  'holds', (SELECT count(*) FROM seat_hold), 'group_holds', (SELECT coalesce(sum(n), 0) FROM g), 'groups_live', (SELECT count(*) FROM g),
  'partial', (SELECT count(*) FROM g JOIN hold_group hg ON hg.id = g.group_id WHERE g.n <> hg.seat_count),
  'split', (SELECT count(*) FROM g WHERE e > 1),
  'available', (SELECT count(*) FROM product_seat WHERE status = 'AVAILABLE'),
  'free_pairs', (SELECT count(*) FROM product_seat a JOIN product_seat b ON b.id = a.id + 1 AND b.status = 'AVAILABLE'
                 WHERE a.status = 'AVAILABLE' AND a.id <= $max AND (a.id - 1) % 50 < 49 $extra),
  'q_ms', extract(epoch FROM clock_timestamp() - statement_timestamp()) * 1000);
SQL
}
