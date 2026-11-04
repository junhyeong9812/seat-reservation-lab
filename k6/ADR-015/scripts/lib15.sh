#!/usr/bin/env bash
# ADR-015 하네스 공통 함수 — ADR-014 lib14.sh(→ ADR-009 lib.sh)를 source 하고(재사용 · 두 파일 모두 수정하지 않는다) 이 측정에 필요한 것만 바꾸거나 더한다:
#   compose 오버레이를 k6/ADR-015/compose.confirm.yml로(lib14의 compose14 · compose_down14 · compose_up14가 COMPOSE_EXTRA를 그대로 쓴다) ·
#   앱 설정 수집(seat.confirm 접두어 포함) · 시드 확인(V12 인덱스 유효 · V13 열 · V14 트리거 · 이력) · pg_stat_statements 스냅숏(#15 — 켠 경우만).
# run.sh가 source 한다(동결 사본). 필요: LIB9_DIR(ADR-009 scripts) · LIB14_SRC(ADR-014 scripts).
# shellcheck source=../../ADR-014/scripts/lib14.sh
source "$LIB14_SRC/lib14.sh"
LIB15_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
set +e

C15="k6/ADR-015/compose.confirm.yml"
C14="$C15"                                                  # lib14 dry-run 문구 · 다른 참조가 같은 파일을 가리키게
COMPOSE_EXTRA="-f k6/ADR-009/compose.tmpfs.yml -f $C15"     # 서버 측정기(probe_server_up)도 같은 -f 묶음을 쓴다
compose15() { compose14; }

# 적용된 설정(lib14 app_config14와 같은 모양 — 접두어에 seat.confirm을 더한다) → app-config.json. 판정은 plan15.py --config-check
app_config15() {   # 인자: 회차폴더
  local dir="$1" props cpu pool containers idx nofile
  props=$(actuator configprops | jq -c '[.contexts[].beans[] | select(.prefix | test("^(seat\\.confirm|seat\\.hold(\\..+)?|spring\\.datasource\\.hikari|spring\\.flyway|server)$"))
      | {prefix, properties: (.properties | with_entries(select(.key | test("password|secret"; "i") | not)))}]') || return 1
  cpu=$(actuator metrics/system.cpu.count | jq -c '[.measurements[].value]') || return 1
  pool=$(actuator metrics/hikaricp.connections.max | jq -c '[.measurements[].value]') || return 1
  containers=$(remote "docker inspect seatlab-app-1 seatlab-db-1 seatlab-redis-1 --format '{{json .Name}} {{json .HostConfig}} {{json .Image}}'" \
    | jq -Rsc 'split("\n") | map(select(length>0) | split(" ") as $p | {name: ($p[0]|fromjson), cpu: ($p[1:-1]|join(" ")|fromjson|.NanoCpus), mem: ($p[1:-1]|join(" ")|fromjson|.Memory), image: ($p[-1]|fromjson)})') || return 1
  idx=$(db_indexes) || return 1
  nofile=$(remote "docker exec seatlab-app-1 cat /proc/1/limits" 2>/dev/null | awk '/^Max open files/ { printf "{\"soft\":%s,\"hard\":%s}", $4, $5 }')
  jq -e 'type == "object"' <<< "${nofile:-null}" > /dev/null 2>&1 || nofile=null
  jq -n --argjson p "$props" --argjson c "$cpu" --argjson h "$pool" --argjson k "$containers" --argjson i "$idx" --argjson nf "$nofile" \
    '{configprops:$p, "system.cpu.count":$c, "hikaricp.connections.max":$h, containers:$k, db_indexes:$i, app_nofile:$nf}' > "$dir/app-config.json"
}

# 시드 확인(record-level — seed-mismatch · schema-mismatch): 회차 수 · 좌석 수 · 모두 AVAILABLE · 홀드·예약·쿼터 행 0 ·
#   V12 인덱스(유효 · 유니크 · 부분 조건 — §설계-11 V12 판정과 같은 모양) · V13 열 · V14 트리거(optimistic만 있어야 한다) · 이력 V12 · V13(· V14)
seed_check15() {   # 인자: 기대회차 기대좌석(회차당) → 한 줄 JSON
  db_json <<SQL
SELECT json_build_object('schedules', (SELECT count(*) FROM product_schedule), 'seats', (SELECT count(*) FROM product_seat),
  'available', (SELECT count(*) FROM product_seat WHERE status = 'AVAILABLE'), 'holds', (SELECT count(*) FROM seat_hold),
  'reservations', (SELECT count(*) FROM reservation), 'quota_rows', (SELECT count(*) FROM user_hold_quota),
  'v12_valid', (SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                 WHERE c.relname = 'uq_reservation_confirmed_seat' AND i.indisvalid AND i.indisunique
                   AND i.indrelid = 'reservation'::regclass AND pg_get_expr(i.indpred, i.indrelid) LIKE '%CONFIRMED%'),
  'v13_column', (SELECT count(*) FROM information_schema.columns WHERE table_name = 'seat_hold' AND column_name = 'extended_at'),
  'v14_triggers', (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid WHERE c.relname = 'product_seat' AND NOT t.tgisinternal),
  'flyway', (SELECT json_agg(version ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version IS NOT NULL),
  'want_schedules', $1, 'want_seats', $(( $1 * $2 )));
SQL
}

# pg_stat_statements(#15 — knobs pgss=1일 때만): 확장 만들기 · 0으로 맞추기 · 스냅숏(배치 · 확정 문장만 — 문장 표지 대신 문장 앞머리로 가른다)
pgss_prepare() { db_json <<'SQL'
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SELECT json_build_object('reset', (SELECT true FROM (SELECT pg_stat_statements_reset()) x), 't', clock_timestamp());
SQL
}
pgss_snapshot() { db_json <<'SQL'
SELECT json_build_object('t', clock_timestamp(), 'rows', coalesce((SELECT json_agg(json_build_object('q', left(regexp_replace(query, '\s+', ' ', 'g'), 200),
        'calls', calls, 'rows', rows, 'total_ms', round(total_exec_time::numeric, 3), 'max_ms', round(max_exec_time::numeric, 3)) ORDER BY total_exec_time DESC)
   FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid WHERE d.datname = 'seat' AND calls > 0
     AND query ~* '(seat_hold|product_seat|reservation|user_hold_quota)'), '[]'::json));
SQL
}
