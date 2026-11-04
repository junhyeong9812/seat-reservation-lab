#!/usr/bin/env bash
# ops/online-v9 로컬 테스트(ADR-013 §4.8 N5 · N7 · 코드 리뷰 CR13-22 · CR13-26) — 로컬 docker PostgreSQL 16 컨테이너 하나(seatlab-ov9-test-<pid>)에서 돌고 끝에 지운다.
#   서버(.164)에 접속하지 않는다. 다른 컨테이너는 건드리지 않는다(이름으로만 만들고 지운다).
#
#   bash ops/online-v9/test-local.sh [--keep]        exit 0 = 모든 확인 통과 · 1 = 실패 있음 · 2 = 환경(docker·이미지 없음) · 3 = 실패 없음 + 스킵 있음(Flyway 라이브러리 없음)
#
# Flyway: 앱과 같은 Flyway 11.7.2 라이브러리(로컬 Gradle 캐시의 flyway-core · flyway-database-postgresql · postgresql JDBC · jackson)로
#   단일 파일 Java 실행기를 돌린다(JDK 21 — 'java Foo.java'). 그 jar가 없으면 SQL 파일을 버전 순서로 psql에 넣고 이력 표를 Flyway 체크섬(CRC32 · 줄 단위)으로
#   직접 쓰는 대체 경로로 간다 — 그때는 'Flyway가 V10만 적용' 확인을 못 하고(한계로 출력) ref도 Flyway가 만든 것이 아니다.
#
# 확인:
#   ok 경로 — ref DB = Flyway V1~V10(빈 DB) · 대상 DB = Flyway V1~V7 + 쿼터 행 20,000(옛 앱 상태) → apply.sh --record direct
#     ⓐ exit 0 · result ok · 동치 ok(N5) ⓑ 대상 V9 이력 1행 · success · 체크섬 = ref = V9 파일 체크섬 ⓒ 기존 행 updated_at NULL 0 · UPDATE가 updated_at을 바꾼다(트리거)
#     ⓓ 인덱스 유효 ⓔ 이어서 Flyway(전체 위치)를 돌리면 검증을 통과하고 **V10**(+ 이 SHA의 V12 · V13 — 순서 안)만 적용한다 · max_applied 7 → -outOfOrder 없음
#   실패 주입 — 같은 옛 앱 상태 DB(쿼터 행 400,000)에 --stmt-timeout 1ms → exit 1 · fail_step 3 · V9 이력 0(N7) · INVALID 인덱스 없음
#   같은 트랜잭션 확인(CR13-26) — 이력 표에 체크섬을 바꾸는 BEFORE INSERT 트리거를 심은 DB → 기록 ② 실패 · V9 이력 0(ROLLBACK)
#   옛 앱 상태 = ADR-015 앞 SHA의 앱(V1~V7) — 이 SHA의 기본 위치에는 V12 · V13이 있어 target 7로 만든다(ADR-015 §설계-4 · T12). ref · ⓔ의 V12 · V13도 그래서 함께 적용된다.
#   ADR-015 §설계-4 'V12 · V13 × 옛 방식 DB'(outOfOrder 1회 — [구현 검증] #6): 대상 DB = Flyway V1~V7 + V12 · V13(이 SHA를 기본 위치만으로 띄운 옛 방식 앱)
#     → apply.sh --record direct · --record cli(Flyway 라이브러리가 있을 때 — CLI 인자를 받는 같은 11.7.2 라이브러리 실행기 · 없으면 스킵을 출력) 두 판
#     ⓐ exit 0 · state0.max_applied = 13 · knobs.out_of_order_cli = true · (cli) 기록 ① 인자에 -outOfOrder=true
#     ⓑ 이어서 전체 위치 Flyway를 outOfOrder 없이 → 검증 실패 · V10 객체 없음 · 이력 무변경(스키마 무변경)
#     ⓒ outOfOrder 1회 → V10 한 개만 적용 ⓓ 그다음 outOfOrder 없이 다시 → 검증 통과(순서 밖 V9 · V10 이력을 받아들인다) · 적용 0
#     ⓑ~ⓓ와 cli 판은 Flyway 라이브러리(java 경로)에서만 — SQL 대체 경로면 SKIP을 출력하고 exit 3(무음 스킵 금지 · green 아님 · 아래 SKIPS)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"; MIG="$REPO/main-app/src/main/resources/db"
KEEP=0; [[ "${1:-}" == --keep ]] && KEEP=1
NAME="seatlab-ov9-test-$$" PW="ov9-local-test-only" IMG="${PG_IMAGE:-postgres:16}"
W="$(mktemp -d)"
cleanup() {
  if (( KEEP )); then echo "남김: 컨테이너 $NAME · 작업 폴더 $W"; return; fi
  docker rm -f "$NAME" > /dev/null 2>&1; rm -rf "$W"
  docker ps -a --format '{{.Names}}' | grep -qx "$NAME" && echo "경고: 컨테이너 $NAME 이 남았다" >&2
}
trap cleanup EXIT
FAILS=0
check() { if [[ "$2" == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1 — 기대 [$3] · 실제 [$2]"; FAILS=$((FAILS + 1)); fi; }

command -v docker > /dev/null && docker image inspect "$IMG" > /dev/null 2>&1 || { echo "docker 또는 이미지 $IMG 없음(받지 않는다)" >&2; exit 2; }
docker run -d --name "$NAME" -e POSTGRES_USER=seat -e POSTGRES_PASSWORD="$PW" -e POSTGRES_DB=seat -p 127.0.0.1::5432 "$IMG" > /dev/null || exit 2
for _ in $(seq 1 60); do docker exec "$NAME" pg_isready -U seat -d seat -q 2>/dev/null && break; sleep 0.5; done
sleep 1; docker exec "$NAME" pg_isready -U seat -d seat -q || { echo "PG 준비 안 됨" >&2; exit 2; }
PORT=$(docker port "$NAME" 5432/tcp | head -1 | sed 's/.*://')
q() { docker exec -i "$NAME" psql -X -q -U seat -d "$1" -v ON_ERROR_STOP=1 -tA; }   # 인자: DB — 표준 입력 SQL → 결과
newdb() { q seat <<< "CREATE DATABASE $1;" > /dev/null; }
echo "# 컨테이너 $NAME($IMG) · 127.0.0.1:$PORT · PG $(q seat <<< 'SHOW server_version;')"

# ---- Flyway 실행기(앱과 같은 11.7.2 라이브러리) — 없으면 SQL 대체 경로 ------------------------------------------------------------------
G="$HOME/.gradle/caches/modules-2/files-2.1"
jar() { find "$G/$1/$2/$3" -name "$2-$3.jar" 2>/dev/null | head -1; }
CP=""; FW_MODE=sql
if command -v java > /dev/null; then
  for x in "org.flywaydb flyway-core 11.7.2" "org.flywaydb flyway-database-postgresql 11.7.2" "org.postgresql postgresql 42.7.7" \
           "com.fasterxml.jackson.core jackson-databind 2.19.2" "com.fasterxml.jackson.core jackson-core 2.19.2" "com.fasterxml.jackson.core jackson-annotations 2.19.2" \
           "com.fasterxml.jackson.dataformat jackson-dataformat-toml 2.19.2" "com.fasterxml.jackson.datatype jackson-datatype-jsr310 2.19.2"; do
    # shellcheck disable=SC2086
    j=$(jar $x); [[ -n "$j" ]] || { CP=""; break; }; CP="${CP:+$CP:}$j"
  done
  [[ -n "$CP" ]] && FW_MODE=java
fi
cat > "$W/FlywayRun.java" <<'JAVA'
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
public class FlywayRun {
  // 인자: jdbcUrl user locations(쉼표) [target('' = 끝까지)] [outOfOrder true|false] — 비밀번호 = 환경변수 FLYWAY_PASSWORD. 출력: APPLIED <버전 …> · 오류면 ERROR <예외> 한 줄 + exit 1
  public static void main(String[] a) {
    try {
      var c = Flyway.configure().dataSource(a[0], a[1], System.getenv("FLYWAY_PASSWORD")).locations(a[2].split(","));
      if (a.length > 3 && !a[3].isEmpty()) c = c.target(a[3]);
      if (a.length > 4) c = c.outOfOrder(Boolean.parseBoolean(a[4]));
      MigrateResult r = c.load().migrate();
      StringBuilder sb = new StringBuilder("APPLIED");
      r.migrations.forEach(m -> sb.append(' ').append(m.version));
      System.out.println(sb);
    } catch (Exception e) {
      System.out.println("ERROR " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replace('\n', ' '));
      System.exit(1);
    }
  }
}
JAVA
# apply.sh 기록 ①(--record cli)의 --flyway 자리: Flyway CLI 인자(-key=value … migrate)를 받아 같은 11.7.2 라이브러리로 돌린다(공식 CLI 이미지는 받지 않는다 — 한계: CLI 배포판 자체는 아니다)
cat > "$W/FlywayCli.java" <<'JAVA'
import org.flywaydb.core.Flyway;
import java.util.HashMap;
public class FlywayCli {
  public static void main(String[] a) {
    var conf = new HashMap<String, String>();
    String cmd = null;
    for (String x : a) {
      if (x.startsWith("-") && x.contains("=")) { int i = x.indexOf('='); conf.put("flyway." + x.substring(1, i), x.substring(i + 1)); }
      else cmd = x;
    }
    conf.put("flyway.password", System.getenv("FLYWAY_PASSWORD"));
    try {
      var f = Flyway.configure().configuration(conf).load();
      if (!"migrate".equals(cmd)) throw new IllegalArgumentException("지원하지 않는 명령 " + cmd);
      var r = f.migrate();
      StringBuilder sb = new StringBuilder("APPLIED");
      r.migrations.forEach(m -> sb.append(' ').append(m.version));
      System.out.println(sb);
    } catch (Exception e) {
      System.out.println("ERROR " + e.getClass().getName() + ": " + String.valueOf(e.getMessage()).replace('\n', ' '));
      System.exit(1);
    }
  }
}
JAVA
# Flyway 체크섬(대체 경로): 줄 단위 CRC32(줄바꿈 제외 · 첫 줄 BOM 제거) → 부호 있는 32비트
fw_checksum() { python3 -I -c 'import sys,zlib
c=0
for i,l in enumerate(open(sys.argv[1],"rb").read().decode("utf-8").splitlines()):
    if i==0: l=l.lstrip("﻿")
    c=zlib.crc32(l.encode("utf-8"),c)
print(c-(1<<32) if c>=(1<<31) else c)' "$1"; }
flyway() {   # 인자: DB 위치(쉼표 — db/ 아래 폴더 이름) [target('' = 끝까지)] [outOfOrder true|false — java 경로만] → APPLIED <버전 …>
  local db="$1" locs="$2" target="${3:-}" ooo="${4:-false}"
  if [[ "$FW_MODE" == java ]]; then
    local l=""; for x in ${locs//,/ }; do l="${l:+$l,}filesystem:$MIG/$x"; done
    FLYWAY_PASSWORD="$PW" java -cp "$CP" "$W/FlywayRun.java" "jdbc:postgresql://127.0.0.1:$PORT/$db" seat "$l" "$target" "$ooo" 2>"$W/fw-$db.err" | tail -1
    return "${PIPESTATUS[0]}"
  fi
  # 대체: 버전 순서로 SQL 적용 + 이력 행(Flyway 표 모양 — 11.x)
  local files f v rank applied="APPLIED"
  files=$(for x in ${locs//,/ }; do ls "$MIG/$x"/V*__*.sql; done | awk -F/ '{f=$NF; v=f; sub(/^V/,"",v); sub(/__.*/,"",v); print v" "$0}' | sort -n | awk '{print $2}')
  q "$db" <<< "CREATE TABLE IF NOT EXISTS flyway_schema_history (installed_rank int PRIMARY KEY, version varchar(50), description varchar(200) NOT NULL, type varchar(20) NOT NULL,
    script varchar(1000) NOT NULL, checksum int, installed_by varchar(100) NOT NULL, installed_on timestamp NOT NULL DEFAULT now(), execution_time int NOT NULL, success boolean NOT NULL);" > /dev/null
  for f in $files; do
    v=$(basename "$f" | sed -E 's/^V([0-9]+)__.*/\1/'); [[ -n "$target" ]] && (( v > target )) && continue
    [[ "$(q "$db" <<< "SELECT count(*) FROM flyway_schema_history WHERE version = '$v';")" == 0 ]] || continue
    docker exec -i "$NAME" psql -X -q -1 -U seat -d "$db" -v ON_ERROR_STOP=1 -tA < "$f" > /dev/null || { echo "ERROR sql $f"; return 1; }   # -1: Flyway처럼 파일 하나 = 트랜잭션 하나(V6 LOCK TABLE)
    rank=$(q "$db" <<< "SELECT coalesce(max(installed_rank), 0) + 1 FROM flyway_schema_history;")
    q "$db" <<< "INSERT INTO flyway_schema_history VALUES ($rank, '$v', '$(basename "$f" .sql | sed -E 's/^V[0-9]+__//; s/_/ /g')', 'SQL', '$(basename "$f")', $(fw_checksum "$f"), 'seat', now(), 0, true);" > /dev/null
    applied="$applied $v"
  done
  echo "$applied"
}
echo "# Flyway 경로: $FW_MODE$( [[ $FW_MODE == sql ]] && echo ' — 한계: Flyway 라이브러리 없음 · ref를 SQL 파일 순서 적용으로 만들고 체크섬은 직접 계산 · V10만 적용 확인 불가')"
PSQL_HEAD=$'\\set QUIET on\n\\pset tuples_only on\n\\pset format unaligned\n'
ALL_LOCS="migration,migration-updated-at-trigger,migration-incremental"
seed_old() {   # 인자: DB 쌍수 [target(기본 7 — ADR-015 앞 옛 앱 · '' = 이 SHA의 기본 위치 끝까지 = V1~V7 + V12 · V13)] — 옛 앱 상태(기본 위치만) + 쿼터 행
  newdb "$1"; flyway "$1" migration "${3-7}" > "$W/$1-flyway.txt" || { echo "Flyway 기본 위치 실패($1): $(cat "$W/$1-flyway.txt" "$W/fw-$1.err" 2>/dev/null | tail -3)"; return 1; }
  q "$1" <<< "INSERT INTO user_hold_quota (schedule_id, user_id, cnt) SELECT 1 + (g % 50), g, 1 FROM generate_series(1, $2) g; ANALYZE user_hold_quota;" > /dev/null
}
apply_to() {   # 인자: DB out [apply.sh 인자 …] → exit
  local db="$1" out="$2"; shift 2
  bash "$HERE/apply.sh" --psql "docker exec -i -e PGAPPNAME=seatlab-online-v9 $NAME psql -X -q -U seat -d $db" \
    --ref-catalog "$W/ref-catalog.json" --ref-history "$W/ref-history.json" --out "$out" "$@" > "$W/$db-apply.log" 2>&1
}

# ---- ref(Flyway V1~V10 빈 DB) --------------------------------------------------------------------------------------------------------------
newdb seat_ref
r=$(flyway seat_ref "$ALL_LOCS"); echo "ref Flyway: $r"
{ echo "$PSQL_HEAD"; cat "$HERE/04-catalog.sql"; } | q seat_ref | tail -1 > "$W/ref-catalog.json"
{ echo "$PSQL_HEAD"; cat "$HERE/05-history.sql"; } | q seat_ref | tail -1 > "$W/ref-history.json"
check "ref = V1~V10 + V12 · V13 적용(V4·V8·V11 선택 위치 없음)" "$r" "APPLIED 1 2 3 5 6 7 9 10 12 13"
check "ref V9 체크섬 = V9 파일 Flyway 체크섬" "$(jq -r '.[0].checksum' "$W/ref-history.json")" "$(fw_checksum "$MIG/migration-updated-at-trigger/V9__quota_updated_at_trigger.sql")"

# ---- ok 경로: 행 있는 옛 앱 상태 DB → apply.sh --record direct -------------------------------------------------------------------------------
seed_old seat_ok 20000 || exit 1
check "옛 앱 상태 = V1~V7(target 7)" "$(cat "$W/seat_ok-flyway.txt")" "APPLIED 1 2 3 5 6 7"
apply_to seat_ok "$W/ok.json" --record direct; rc=$?
echo "--- apply.sh(ok) 출력 끝:"; tail -3 "$W/seat_ok-apply.log"; jq -c '{result, fail_step, reason, equivalence: .equivalence.ok, method: .record.method, m: .m, steps: (.steps | map_values(.tries // .skipped))}' "$W/ok.json"
check "ⓐ apply.sh exit 0" "$rc" "0"
check "ⓐ result ok · 동치 ok(N5)" "$(jq -r '"\(.result) \(.equivalence.ok)"' "$W/ok.json")" "ok true"
h=$({ echo "$PSQL_HEAD"; cat "$HERE/05-history.sql"; } | q seat_ok | tail -1)
check "ⓑ V9 이력 1행 · success" "$(jq -r 'length as $n | "\($n) \(.[0].success)"' <<< "$h")" "1 true"
check "ⓑ V9 이력 체크섬 = ref = V9 파일" "$(jq -r '.[0].checksum' <<< "$h")" "$(jq -r '.[0].checksum' "$W/ref-history.json")"
check "ⓑ V9 이력 script·description = ref" "$(jq -c '.[0] | [.script, .description, .type]' <<< "$h")" "$(jq -c '.[0] | [.script, .description, .type]' "$W/ref-history.json")"
check "ⓒ 기존 행 updated_at NULL 0 · 행 20,000" "$(q seat_ok <<< "SELECT count(*) FILTER (WHERE updated_at IS NULL) || ' ' || count(*) FROM user_hold_quota;")" "0 20000"
check "ⓒ UPDATE가 updated_at을 바꾼다(트리거)" "$(q seat_ok <<< "SELECT updated_at FROM user_hold_quota WHERE user_id = 7 \\gset
SELECT pg_sleep(0.05); UPDATE user_hold_quota SET cnt = cnt WHERE user_id = 7; SELECT (updated_at > :'updated_at')::text FROM user_hold_quota WHERE user_id = 7;" | tail -1)" "true"
check "ⓐ max_applied 7 → -outOfOrder 붙이지 않음 · 앱 첫 기동 out-of-order 불필요" "$(jq -c '[.state0.max_applied, .knobs.out_of_order_cli, .next.app_first_boot_out_of_order]' "$W/ok.json")" "[7,false,false]"
check "ⓓ 인덱스 유효(indisvalid · indisready)" "$(q seat_ok <<< "SELECT indisvalid AND indisready FROM pg_index WHERE indexrelid = to_regclass('idx_user_hold_quota_updated_at');")" "t"
if [[ "$FW_MODE" == java ]]; then
  r=$(flyway seat_ok "$ALL_LOCS"); echo "적용판 뒤 Flyway(전체 위치): $r $(tail -c 300 "$W/fw-seat_ok.err" 2>/dev/null | grep -i error)"
  check "ⓔ 적용판 뒤 Flyway 전체 위치 → 검증 통과 · V10(+ 이 SHA의 V12 · V13 — 순서 안)만 적용" "$r" "APPLIED 10 12 13"
  check "ⓔ 이력 = V1~V7 · V9(직접) · V10 · V12 · V13 · 모두 success" "$(q seat_ok <<< "SELECT string_agg(version || ':' || success, ' ' ORDER BY installed_rank) FROM flyway_schema_history;")" "1:true 2:true 3:true 5:true 6:true 7:true 9:true 10:true 12:true 13:true"
else
  echo "한계: ⓔ 'Flyway가 V10만 적용' — Flyway 라이브러리 없음(대체 SQL 경로)으로 확인하지 못했다"
fi

# ---- 실패 주입: statement_timeout 아주 짧게 → 단계 3 실패 · 이력 0 · INVALID 없음(N7) ---------------------------------------------------------
seed_old seat_fail 400000 || exit 1
apply_to seat_fail "$W/fail.json" --record direct --stmt-timeout 1ms; rc=$?
echo "--- apply.sh(실패 주입) 출력 끝:"; tail -3 "$W/seat_fail-apply.log"; jq -c '{result, fail_step, reason, idx: .steps["3"] | {tries, drops, failed, drops_detail: [.drops_detail[]? | {result, gone}]}}' "$W/fail.json"
check "실패 주입 exit 1" "$rc" "1"
check "실패 주입 result failed · fail_step 3" "$(jq -r '"\(.result) \(.fail_step)"' "$W/fail.json")" "failed 3"
check "실패 주입 V9 이력 0(N7 — 기록하지 않음)" "$(q seat_fail <<< "SELECT count(*) FROM flyway_schema_history WHERE version = '9';")" "0"
check "실패 주입 INVALID 인덱스 없음(지웠다)" "$(q seat_fail <<< "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'idx_user_hold_quota_updated_at' OR NOT i.indisvalid;")" "0"
check "실패 주입 history_after_fail = []" "$(jq -c '.record.history_after_fail' "$W/fail.json")" "[]"

# ---- 같은 트랜잭션 확인(CR13-26): 이력 체크섬을 바꾸는 트리거 → 기록 ② ROLLBACK · 이력 0 -----------------------------------------------------
seed_old seat_vf 1000 || exit 1
q seat_vf <<< "CREATE FUNCTION t_ov9_bad() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN IF NEW.version = '9' THEN NEW.checksum := NEW.checksum + 1; END IF; RETURN NEW; END \$\$;
CREATE TRIGGER ov9_bad BEFORE INSERT ON flyway_schema_history FOR EACH ROW EXECUTE FUNCTION t_ov9_bad();" > /dev/null
apply_to seat_vf "$W/vf.json" --record direct; rc=$?
echo "--- apply.sh(같은 트랜잭션 확인) 출력 끝:"; tail -2 "$W/seat_vf-apply.log"; jq -c '{result, fail_step, reason, direct: .record.direct | {ok, in_tx_verify_failed, in_tx_v9_rows}}' "$W/vf.json"
check "같은 트랜잭션 확인 실패 → exit 1 · fail_step 4" "$rc $(jq -r .fail_step "$W/vf.json")" "1 4"
check "같은 트랜잭션 확인 실패 → V9 이력 0(ROLLBACK — 남지 않는다)" "$(q seat_vf <<< "SELECT count(*) FROM flyway_schema_history WHERE version = '9';")" "0"

# ---- ADR-015 §설계-4 'V12 · V13 × 옛 방식 DB': V1~V7 + V12 · V13 → apply.sh(outOfOrder 1회) → 앱 위치 Flyway ⓑ~ⓓ ----------------------------------------
SKIPS=0
hist_str() { q "$1" <<< "SELECT string_agg(version || ':' || success, ' ' ORDER BY installed_rank) FROM flyway_schema_history;"; }
v10_objs() { q "$1" <<< "SELECT count(*) FROM pg_class WHERE relname IN ('counter_safety_run', 'counter_app_inject', 'counter_inc_gap') AND relnamespace = 'public'::regnamespace;"; }
ooo_case() {   # 인자: direct|cli
  local mode="$1" db="seat_ooo_$1" extra=() rc h0 r
  seed_old "$db" 1000 "" || { FAILS=$((FAILS + 1)); return; }
  check "[$mode] 대상 = V1~V7 + V12 · V13(이 SHA 기본 위치만 — 옛 방식 K2)" "$(cat "$W/$db-flyway.txt")" "APPLIED 1 2 3 5 6 7 12 13"
  if [[ "$mode" == cli ]]; then
    extra=(--flyway "java -cp $CP $W/FlywayCli.java" --flyway-url "jdbc:postgresql://127.0.0.1:$PORT/$db?ApplicationName=seatlab-online-v9"
           --locations "filesystem:$MIG/migration,filesystem:$MIG/migration-updated-at-trigger")
  fi
  if [[ "$mode" == cli ]]; then   # 대조: 같은 기록 ① 인자에서 -outOfOrder=true만 빼면 Flyway가 검증에서 멈춘다(플래그가 필요한 까닭 · 이력 무변경)
    h0=$(hist_str "$db")
    r=$(FLYWAY_PASSWORD="$PW" java -cp "$CP" "$W/FlywayCli.java" -url="jdbc:postgresql://127.0.0.1:$PORT/$db" -user=seat \
        -locations="filesystem:$MIG/migration,filesystem:$MIG/migration-updated-at-trigger" -target=9 -skipExecutingMigrations=true migrate 2>/dev/null | tail -1)
    echo "[cli] 대조(-outOfOrder 없음): ${r:0:200}"
    check "[cli] 대조 — -outOfOrder 없이 기록 ① → 검증 실패(V9 = 적용된 V13보다 낮은 미적용) · 이력 무변경" "$(grep -c 'Validate failed: .*Detected resolved migration not applied to database: 9\.' <<< "$r") $(hist_str "$db")" "1 $h0"
  fi
  FLYWAY_PASSWORD="$PW" apply_to "$db" "$W/ooo-$mode.json" --record "$mode" "${extra[@]}"; rc=$?
  echo "--- apply.sh(V12 · V13 × 옛 방식 · $mode) 출력 끝:"; tail -2 "$W/$db-apply.log"
  jq -c '{result, fail_step, reason, max_applied: .state0.max_applied, ooo: .knobs.out_of_order_cli, next, method: .record.method, cli: (.record.cli // null | if . then {rc, class, args} else null end)}' "$W/ooo-$mode.json"
  check "[$mode] ⓐ apply.sh exit 0 · result ok · 기록 방법 $mode" "$rc $(jq -r '"\(.result) \(.record.method)"' "$W/ooo-$mode.json")" "0 ok $mode"
  check "[$mode] ⓐ state0.max_applied 13 · out_of_order_cli true · 앱 첫 기동 out-of-order 필요" "$(jq -c '[.state0.max_applied, .knobs.out_of_order_cli, .next.app_first_boot_out_of_order]' "$W/ooo-$mode.json")" "[13,true,true]"
  if [[ "$mode" == cli ]]; then
    check "[cli] ⓐ 기록 ① 인자에 -outOfOrder=true · -target=9 · -skipExecutingMigrations=true" "$(jq -c '.record.cli.args | [index("-outOfOrder=true") != null, index("-target=9") != null, index("-skipExecutingMigrations=true") != null, .[-1]]' "$W/ooo-$mode.json")" '[true,true,true,"migrate"]'
  fi
  check "[$mode] ⓐ 이력 = V1~V7 · V12 · V13 · V9(순서 밖 · 체크섬 = ref)" "$(hist_str "$db") $(q "$db" <<< "SELECT checksum FROM flyway_schema_history WHERE version = '9';")" "1:true 2:true 3:true 5:true 6:true 7:true 12:true 13:true 9:true $(jq -r '.[0].checksum' "$W/ref-history.json")"
  h0=$(hist_str "$db")
  r=$(flyway "$db" "$ALL_LOCS"); echo "[$mode] 전체 위치 · outOfOrder 없음: $r"
  check "[$mode] ⓑ outOfOrder 없이 전체 위치 → 검증 실패(V10 = 적용된 V13보다 낮은 미적용)" "$(grep -c 'FlywayValidateException: Validate failed: .*Detected resolved migration not applied to database: 10\.' <<< "$r")" "1"
  check "[$mode] ⓑ V10 객체 없음 · 이력 무변경(스키마 무변경)" "$(v10_objs "$db") $(hist_str "$db")" "0 $h0"
  r=$(flyway "$db" "$ALL_LOCS" "" true); echo "[$mode] 전체 위치 · outOfOrder 1회: $r"
  check "[$mode] ⓒ outOfOrder 1회 → V10 한 개만 적용 · V10 객체 3" "$r $(v10_objs "$db")" "APPLIED 10 3"
  r=$(flyway "$db" "$ALL_LOCS"); echo "[$mode] 전체 위치 · outOfOrder 없이 다시: $r $(grep -i error "$W/fw-$db.err" | tail -c 300)"
  check "[$mode] ⓓ outOfOrder 없이 다시 → 검증 통과 · 적용 0(순서 밖 V9 · V10 이력을 받아들인다 — [구현 검증] #6)" "$r" "APPLIED"
  check "[$mode] ⓓ 이력 = … V13 · V9 · V10 · 모두 success" "$(hist_str "$db")" "1:true 2:true 3:true 5:true 6:true 7:true 12:true 13:true 9:true 10:true"
}
if [[ "$FW_MODE" == java ]]; then
  ooo_case direct
  ooo_case cli
else
  echo "SKIP ADR-015 'V12 · V13 × 옛 방식 DB' 경우 전부(direct · cli · ⓑ~ⓓ) — Flyway 라이브러리 없음(대체 SQL 경로는 outOfOrder 검증을 흉내 내지 못한다)"; SKIPS=$((SKIPS + 1))
fi

echo; echo "$FAILS 실패 · $SKIPS 스킵(Flyway 경로 $FW_MODE)"
(( FAILS > 0 )) && exit 1
(( SKIPS > 0 )) && exit 3   # 실패는 없지만 확인하지 못한 경우가 있다(green 아님)
exit 0
