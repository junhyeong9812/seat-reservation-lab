#!/usr/bin/env bash
# ADR-002 S5 — 규모별 쿼리 비용. 앱·HTTP 없이 DB 컨테이너 안에서 pgbench로 선점 경로 쿼리를 반복 실행한다.
#
#   scripts/s5-bench.sh --sha <SHA> --out <폴더> [--levels "2 4"] [--scales "0 10000 100000 1000000 5000000"] [--seconds 15]
#
# 조합: 단계(CPU) × 인덱스(off|on) × 배경 규모 N. 규모 N마다 시드(배경 N) → 실행 계획 → 1연결 지연 → 10연결 처리량.
# 쿼리는 도메인 리포지토리가 부르는 것과 같은 모양이다(아래 Q1~Q4). 측정 사용자·좌석은 배경과 겹치지 않는다.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
set +e

SHA="" OUT="" LEVELS="2 4" SCALES="0 10000 100000 1000000 5000000" SECONDS_PER=15
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --levels) LEVELS="$2"; shift 2 ;;
    --scales) SCALES="$2"; shift 2 ;;
    --seconds) SECONDS_PER="$2"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" && -n "$OUT" ]] || { echo "--sha --out required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
mkdir -p "$OUT"
S5LOG="$OUT/S5.log"

# 선점 경로 쿼리 (도메인 코드가 JPA로 만드는 것과 같은 조건)
#  Q1 1인 2매 확인 — 홀드 수:   ProductSeatRepository.countByScheduleIdAndHoldsUserId
#  Q2 1인 2매 확인 — 확정 수:   ReservationRepository.countByScheduleIdAndUserIdAndStatus
#  Q3 좌석의 홀드 목록 로드:     ProductSeat.holds (seat_id)
#  Q4 만료 배치 조회:            ProductSeatRepository.findDistinctByHoldsExpiresAtLessThanEqual
write_queries() {  # 인자: 좌석 총수
  local seats="$1"
  remote "docker exec -i seatlab-db-1 sh -c 'mkdir -p /tmp/s5 && cat > /tmp/s5/q1.sql'" <<EOF
\set u random(1, 1000000)
SELECT count(ps.id) FROM product_seat ps JOIN seat_hold h ON ps.id = h.seat_id WHERE ps.schedule_id = 1 AND h.user_id = :u;
EOF
  remote "docker exec -i seatlab-db-1 sh -c 'cat > /tmp/s5/q2.sql'" <<EOF
\set u random(1, 1000000)
SELECT count(*) FROM reservation WHERE schedule_id = 1 AND user_id = :u AND status = 'CONFIRMED';
EOF
  remote "docker exec -i seatlab-db-1 sh -c 'cat > /tmp/s5/q3.sql'" <<EOF
\set s random(1, $seats)
SELECT id, schedule_id, user_id, held_at, expires_at FROM seat_hold WHERE seat_id = :s;
EOF
  remote "docker exec -i seatlab-db-1 sh -c 'cat > /tmp/s5/q4.sql'" <<EOF
SELECT DISTINCT ps.id FROM product_seat ps JOIN seat_hold h ON ps.id = h.seat_id WHERE h.expires_at <= now();
EOF
}
explain_sql() {  # 인자: q번호 좌석총수 — 파라미터를 대표값으로 채운 실행 계획
  case "$1" in
    1) echo "SELECT count(ps.id) FROM product_seat ps JOIN seat_hold h ON ps.id = h.seat_id WHERE ps.schedule_id = 1 AND h.user_id = 424242;" ;;
    2) echo "SELECT count(*) FROM reservation WHERE schedule_id = 1 AND user_id = 424242 AND status = 'CONFIRMED';" ;;
    3) echo "SELECT id, schedule_id, user_id, held_at, expires_at FROM seat_hold WHERE seat_id = $(( ($2 + 1) / 2 ));" ;;
    4) echo "SELECT DISTINCT ps.id FROM product_seat ps JOIN seat_hold h ON ps.id = h.seat_id WHERE h.expires_at <= now();" ;;
  esac
}
psql_db() { remote "docker exec -i seatlab-db-1 psql -U seat -d seat -v ON_ERROR_STOP=1 $*"; }

deploy_sha "$SHA" || { echo "$(date -Is) ABORT deploy-failed" >> "$S5LOG"; exit 3; }
echo "$(date -Is) S5 start sha=$SHA levels=[$LEVELS] scales=[$SCALES] seconds=$SECONDS_PER" >> "$S5LOG"
for level in $LEVELS; do
  for index in off on; do
    target=$([[ "$index" == on ]] && echo latest || echo 1)
    combo="L$level-idx$index"
    compose_down "$SHA" > /dev/null 2>&1
    # 만료 배치는 1시간 주기로 사실상 끈다 — 배치 쿼리가 측정 쿼리와 섞이지 않게
    compose_up "$SHA" APP_CPUS="$level" DB_CPUS="$level" FLYWAY_TARGET="$target" POOL_SIZE=10 HOLD_TTL=60m EXPIRY_INTERVAL=1h > /dev/null 2>&1
    if ! wait_health 180; then echo "$(date -Is) $combo unhealthy" >> "$S5LOG"; continue; fi
    for n in $SCALES; do
      dir="$OUT/$combo/N$n"; mkdir -p "$dir"
      t0=$(date +%s)
      if ! RESET_TIMEOUT=2400 reset_db 1 10000 "$n" > "$dir/seed.json" 2> "$dir/errors.log"; then
        echo "$(date -Is) $combo N$n seed-failed" >> "$S5LOG"; echo seed-failed > "$dir/status"; continue
      fi
      seed_s=$(( $(date +%s) - t0 ))
      seats=$(( 10000 + 2 * n ))
      write_queries "$seats"
      db_indexes > "$dir/indexes.json"
      psql_db -tAc "\"SELECT json_build_object('product_seat', (SELECT count(*) FROM product_seat), 'seat_hold', (SELECT count(*) FROM seat_hold), 'reservation', (SELECT count(*) FROM reservation), 'db_size', pg_size_pretty(pg_database_size('seat')))\"" > "$dir/rows.json" 2>> "$dir/errors.log"
      for q in 1 2 3 4; do
        psql_db -c "\"EXPLAIN (ANALYZE, BUFFERS) $(explain_sql $q $seats)\"" > "$dir/explain-q$q.txt" 2>> "$dir/errors.log"
        # 1연결: 쿼리 하나의 지연 분포 (거래 단위 로그 = 지연 µs)
        remote "docker exec seatlab-db-1 sh -c 'rm -rf /tmp/s5/log && mkdir -p /tmp/s5/log && cd /tmp/s5/log && pgbench -U seat -d seat -n -c 1 -j 1 -T $SECONDS_PER -f /tmp/s5/q$q.sql -l --log-prefix=c1 2>&1'" > "$dir/q$q-c1.txt"
        remote "docker exec seatlab-db-1 sh -c 'cat /tmp/s5/log/c1*'" | gzip > "$dir/q$q-c1-latency.log.gz"
        # 10연결: 커넥션 풀 10과 같은 동시성에서의 처리량
        remote "docker exec seatlab-db-1 sh -c 'pgbench -U seat -d seat -n -c 10 -j 2 -T $SECONDS_PER -f /tmp/s5/q$q.sql 2>&1'" > "$dir/q$q-c10.txt"
      done
      jq -n --arg sha "$SHA" --argjson level "$level" --arg index "$index" --argjson n "$n" --argjson seed_s "$seed_s" \
            --argjson seconds "$SECONDS_PER" --arg at "$(date -Is)" \
        '{sha:$sha, level:$level, index:$index, bg:$n, seed_seconds:$seed_s, seconds_per_run:$seconds, finished:$at}' > "$dir/meta.json"
      status=ok
      for q in 1 2 3 4; do grep -q "^tps = " "$dir/q$q-c1.txt" && grep -q "^tps = " "$dir/q$q-c10.txt" || status="pgbench-failed-q$q"; done
      echo "$status" > "$dir/status"
      echo "$(date -Is) $combo N$n $status seed=${seed_s}s" >> "$S5LOG"
    done
  done
done
compose_down "$SHA" > /dev/null 2>&1
echo "$(date -Is) S5 done" >> "$S5LOG"
touch "$OUT/DONE"
