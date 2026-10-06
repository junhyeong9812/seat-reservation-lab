#!/usr/bin/env bash
# ADR-005 ⑤ 매수 확인 지연 — DB 직접 벤치(ADR-002 s5-bench.sh 방식). 앱·HTTP 없이 DB 컨테이너 안에서 pgbench로 매수 확인 쿼리 2개를 잰다.
#
#   scripts/limit-bench.sh --sha <SHA> --out <폴더> [--level 4] [--scales "0 100000 1000000"] [--seconds 15] [--worktree]
#
# 규모 N마다: 시드(/internal/reset backgroundRows=N — 배경 회차 2에 홀드 N·확정 N, 사용자 대역 9e9·8e9) → 측정 사용자 준비(앱 API) →
#   쿼리 2개 × 사용자 2명마다 실행 계획(EXPLAIN ANALYZE, BUFFERS) + 1연결 지연(pgbench -c 1, 거래 로그).
# 쿼리 = Hibernate가 실제로 내는 SQL(ADR-003 §2.1 ④ 원문):
#   qh 홀드 수:     select count(ps1_0.id) from product_seat ps1_0 left join seat_hold h1_0 on ps1_0.id=h1_0.seat_id where ps1_0.schedule_id=? and h1_0.user_id=?
#   qr 확정 예약 수: select count(r1_0.id) from reservation r1_0 where r1_0.schedule_id=? and r1_0.user_id=? and r1_0.status=?
#   pgbench -M prepared(앱 JDBC도 준비된 문장) — 회차·사용자는 바인드 변수, status는 pgbench 변수가 문자열을 못 담아 리터럴 'CONFIRMED'(편향 — README).
# 측정 사용자(배경 대역이 아니라 S2 측정 사용자와 같은 대역 200000+, 회차 1):
#   has  = 200001 — 앱 API로 좌석 1·2 선점 후 좌석 2 확정 → 홀드 1 + 확정 1(한도 도달 사용자)
#   none = 200002 — 아무것도 없음(첫 선점 사용자)
# 만료 배치는 1시간 주기·TTL 60m로 사실상 끈다 — 배치 쿼리가 측정과 섞이지 않고, has의 홀드가 측정 중 만료되지 않게.
set -uo pipefail
if [[ "${CAMPAIGN_FROZEN:-0}" != 1 && "${LB_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; frozen="$(mktemp -d)"; cp -r "$src" "$frozen/scripts"
  export LB_FROZEN=1 ADR_DIR="$(cd "$src/.." && pwd)"; export REPO_ROOT="$(cd "$ADR_DIR/../.." && pwd)"
  exec bash "$frozen/scripts/limit-bench.sh" "$@"
fi
source "$(dirname "$0")/lib.sh"
set +e

SHA="" OUT="" LEVEL=4 SCALES="0 100000 1000000" SECONDS_PER=15 WORKTREE=0
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --level) LEVEL="$2"; shift 2 ;;
    --scales) SCALES="$2"; shift 2 ;;
    --seconds) SECONDS_PER="$2"; shift 2 ;;
    --worktree) WORKTREE=1; shift ;;   # 스모크 전용 — run.sh와 같은 뜻(앱 = SHA, 하네스 = 작업트리)
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" && -n "$OUT" ]] || { echo "--sha --out required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
HARNESS="sha:$SHA" DEPLOY_ID="$SHA"
if (( WORKTREE )); then
  HARNESS="worktree:$(worktree_harness_hash)"; DEPLOY_ID="$SHA-wt-${HARNESS#worktree:}"
elif ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-005 ':(exclude)k6/ADR-005/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-005 ':(exclude)k6/ADR-005/results')" ]]; then
  echo "k6/ADR-005 differs from $SHA — commit first (스모크는 --worktree)" >&2; exit 2
fi
mkdir -p "$OUT"
LBLOG="$OUT/LIMIT-BENCH.log"
U_HAS=200001 U_NONE=200002

if [[ -f "$OUT/plan.json" ]]; then   # 이어서: 계획과 인자가 같을 때만(결과 섞임 방지)
  [[ "$(jq -r .sha "$OUT/plan.json")" == "$SHA" && "$(jq -r .harness "$OUT/plan.json")" == "$HARNESS" && "$(jq -r .level "$OUT/plan.json")" == "$LEVEL" \
     && "$(jq -r '.scales|join(" ")' "$OUT/plan.json")" == "$SCALES" && "$(jq -r .seconds_per_run "$OUT/plan.json")" == "$SECONDS_PER" ]] \
    || { echo "limit-bench resume: 인자가 계획($OUT/plan.json)과 다르다" >&2; exit 2; }
else
  jq -n --arg sha "$SHA" --arg harness "$HARNESS" --argjson level "$LEVEL" --arg scales "$SCALES" --argjson seconds "$SECONDS_PER" \
        --argjson has "$U_HAS" --argjson none "$U_NONE" \
    '{sha:$sha, harness:$harness, level:$level, scales:($scales|split(" ")|map(select(.!="")|tonumber)), seconds_per_run:$seconds,
      users:{has:$has, none:$none}, queries:["qh","qr"], created:(now|todate)}' > "$OUT/plan.json"
fi

sql_of() {  # 인자: 쿼리 사용자id 방식(bench|explain) — bench는 pgbench 변수(:u)로 바인드, explain은 리터럴
  local q="$1" u="$2" mode="$3" uv
  [[ "$mode" == bench ]] && uv=":u" || uv="$u"
  case "$q" in
    qh) echo "select count(ps1_0.id) from product_seat ps1_0 left join seat_hold h1_0 on ps1_0.id=h1_0.seat_id where ps1_0.schedule_id=1 and h1_0.user_id=$uv;" ;;
    qr) echo "select count(r1_0.id) from reservation r1_0 where r1_0.schedule_id=1 and r1_0.user_id=$uv and r1_0.status='CONFIRMED';" ;;
  esac
}
write_bench_files() {
  local q u uid
  remote "docker exec seatlab-db-1 sh -c 'rm -rf /tmp/lb && mkdir -p /tmp/lb'"
  for q in qh qr; do
    for u in has none; do
      uid=$([[ "$u" == has ]] && echo $U_HAS || echo $U_NONE)
      printf '\\set u %s\n%s\n' "$uid" "$(sql_of $q $uid bench)" | remote "docker exec -i seatlab-db-1 sh -c 'cat > /tmp/lb/$q-$u.sql'"
    done
  done
}
psql_db() { remote "docker exec -i seatlab-db-1 psql -U seat -d seat -v ON_ERROR_STOP=1 $*"; }

# 측정 사용자 준비 — 앱 API로(카운터·홀드 모양이 실제 경로와 같게). has: 좌석 1·2 선점 → 좌석 2 확정
prepare_users() {  # 인자: 폴더
  local dir="$1" h1 h2 c
  h1=$(curl -fsS --max-time 10 -X POST -H "X-User-Id: $U_HAS" "$BASE_URL/api/schedules/1/seats/1/hold") || return 1
  h2=$(curl -fsS --max-time 10 -X POST -H "X-User-Id: $U_HAS" "$BASE_URL/api/schedules/1/seats/2/hold") || return 1
  c=$(curl -fsS --max-time 10 -X POST -H "X-User-Id: $U_HAS" -H 'Content-Type: application/json' \
        -d "{\"paymentUid\":\"lb-$U_HAS\"}" "$BASE_URL/api/holds/$(jq -r .holdId <<< "$h2")/confirm") || return 1
  jq -n --argjson h1 "$h1" --argjson h2 "$h2" --argjson c "$c" '{hold1:$h1, hold2:$h2, confirm:$c}' > "$dir/users-prepare.json"
}

deploy_ok=0
if (( WORKTREE )); then deploy_worktree "$SHA" "$DEPLOY_ID" && deploy_ok=1; else deploy_sha "$SHA" && deploy_ok=1; fi
(( deploy_ok )) || { echo "$(date -Is) ABORT deploy-failed" >> "$LBLOG"; exit 3; }
echo "$(date -Is) limit-bench start sha=$SHA harness=$HARNESS level=$LEVEL scales=[$SCALES] seconds=$SECONDS_PER" >> "$LBLOG"
FAILED=0
pending=0
for n in $SCALES; do [[ "$(cat "$OUT/N$n/status" 2>/dev/null)" == ok ]] || pending=1; done
if (( pending )); then
  compose_down "$DEPLOY_ID" > /dev/null 2>&1
  compose_up "$DEPLOY_ID" APP_CPUS="$LEVEL" DB_CPUS="$LEVEL" POOL_SIZE=10 HOLD_TTL=60m EXPIRY_INTERVAL=1h SEAT_HOLD_LIMIT_STRATEGY=none > "$OUT/compose.log" 2>&1
  if ! wait_health 180; then
    echo "$(date -Is) unhealthy" >> "$LBLOG"
    for n in $SCALES; do mkdir -p "$OUT/N$n"; [[ "$(cat "$OUT/N$n/status" 2>/dev/null)" == ok ]] || echo unhealthy > "$OUT/N$n/status"; done
    FAILED=1; pending=0
  fi
fi
(( pending )) && for n in $SCALES; do
  dir="$OUT/N$n"
  [[ "$(cat "$dir/status" 2>/dev/null)" == ok ]] && continue
  [[ -d "$dir" ]] && mv "$dir" "$dir.incomplete-$(date +%Y%m%d%H%M%S)"
  mkdir -p "$dir"
  t0=$(date +%s)
  if ! RESET_TIMEOUT=2400 reset_db 1 10000 "$n" > "$dir/seed.json" 2> "$dir/errors.log"; then
    echo "$(date -Is) N$n seed-failed" >> "$LBLOG"; echo seed-failed > "$dir/status"; FAILED=1; continue
  fi
  seed_s=$(( $(date +%s) - t0 ))
  if ! prepare_users "$dir" 2>> "$dir/errors.log"; then
    echo "$(date -Is) N$n prepare-users-failed" >> "$LBLOG"; echo prepare-users-failed > "$dir/status"; FAILED=1; continue
  fi
  # 통계 갱신 — reset의 ANALYZE는 측정 사용자 준비 전이라 그 뒤 행이 통계에 없다(실행 계획이 준비 전 통계로 고정되지 않게)
  psql_db -c "\"ANALYZE product_seat, seat_hold, reservation\"" > /dev/null 2>> "$dir/errors.log"
  # 측정 사용자 상태를 DB에서 확인(앱 응답이 아니라 DB 사실) — has = 홀드 1·확정 1, none = 0·0
  psql_db -tAc "\"SELECT json_build_object('has_holds', (SELECT count(*) FROM seat_hold WHERE schedule_id = 1 AND user_id = $U_HAS), 'has_confirmed', (SELECT count(*) FROM reservation WHERE schedule_id = 1 AND user_id = $U_HAS AND status = 'CONFIRMED'), 'none_holds', (SELECT count(*) FROM seat_hold WHERE user_id = $U_NONE), 'none_confirmed', (SELECT count(*) FROM reservation WHERE user_id = $U_NONE), 'product_seat', (SELECT count(*) FROM product_seat), 'seat_hold', (SELECT count(*) FROM seat_hold), 'reservation', (SELECT count(*) FROM reservation), 'db_size', pg_size_pretty(pg_database_size('seat')))\"" > "$dir/rows.json" 2>> "$dir/errors.log"
  db_indexes > "$dir/indexes.json" 2>> "$dir/errors.log"
  write_bench_files
  for q in qh qr; do
    for u in has none; do
      uid=$([[ "$u" == has ]] && echo $U_HAS || echo $U_NONE)
      psql_db -c "\"EXPLAIN (ANALYZE, BUFFERS) $(sql_of $q $uid explain)\"" > "$dir/explain-$q-$u.txt" 2>> "$dir/errors.log"
      remote "docker exec seatlab-db-1 sh -c 'rm -rf /tmp/lb/log && mkdir -p /tmp/lb/log && cd /tmp/lb/log && pgbench -U seat -d seat -n -M prepared -c 1 -j 1 -T $SECONDS_PER -f /tmp/lb/$q-$u.sql -l --log-prefix=c1 2>&1'" > "$dir/$q-$u-c1.txt"
      remote "docker exec seatlab-db-1 sh -c 'cat /tmp/lb/log/c1*'" | gzip > "$dir/$q-$u-c1-latency.log.gz"
    done
  done
  jq -n --arg sha "$SHA" --arg harness "$HARNESS" --argjson level "$LEVEL" --argjson n "$n" --argjson seed_s "$seed_s" --argjson seconds "$SECONDS_PER" --arg at "$(date -Is)" \
    '{sha:$sha, harness:$harness, level:$level, bg:$n, seed_seconds:$seed_s, seconds_per_run:$seconds, finished:$at}' > "$dir/meta.json"
  reasons=()
  [[ "$(jq -r '[.[].name] | sort | join(",")' "$dir/indexes.json" 2>/dev/null)" == "idx_reservation_schedule_user,idx_reservation_seat_id,idx_seat_hold_expires_at,idx_seat_hold_seat_id,idx_seat_hold_user_id" ]] || reasons+=("index-mismatch")
  # 행 수: 배경 N(홀드 N + 확정 N, 좌석 2N) + 측정 사용자 has의 홀드 1·확정 1
  jq -e --argjson n "$n" '.seat_hold == $n + 1 and .reservation == $n + 1 and .product_seat == 10000 + 2 * $n
                          and .has_holds == 1 and .has_confirmed == 1 and .none_holds == 0 and .none_confirmed == 0' "$dir/rows.json" > /dev/null 2>&1 || reasons+=("rows-mismatch")
  for q in qh qr; do
    for u in has none; do
      grep -q "Execution Time" "$dir/explain-$q-$u.txt" 2>/dev/null || reasons+=("explain-$q-$u")
      grep -q "^tps = " "$dir/$q-$u-c1.txt" 2>/dev/null || reasons+=("pgbench-$q-$u")
      [[ "$(zcat "$dir/$q-$u-c1-latency.log.gz" 2>/dev/null | head -c 1 | wc -c)" == 1 ]] || reasons+=("latency-log-$q-$u")
    done
  done
  if (( ${#reasons[@]} )); then status=$(IFS=,; echo "${reasons[*]}"); FAILED=1; else status=ok; fi
  echo "$status" > "$dir/status"
  echo "$(date -Is) N$n $status seed=${seed_s}s" >> "$LBLOG"
done
compose_down "$DEPLOY_ID" > /dev/null 2>&1
if (( FAILED )); then
  echo "$(date -Is) limit-bench finished with failures — DONE 미기록(다시 부르면 실패·누락 규모만 다시 잰다)" >> "$LBLOG"; exit 1
fi
echo "$(date -Is) limit-bench done" >> "$LBLOG"
touch "$OUT/DONE"
