#!/usr/bin/env bash
# ADR-015 서버 쪽 B3 대기 표본기(측정 전 고정 규칙 초안 측정-0 '경합 증거' ② · 리뷰 M15-26). **서버에서** 돈다 —
#   k6 PC가 `ssh <서버> "bash -s -- <사본> <주기 ms> <세션 이름>" < b3wait-sampler.sh`로 보낸다(lib15.sh b3wait_sampler — ADR-014 psql-sampler.sh와 같은 방식:
#   서버에 배포할 것이 없고 동결 사본 그대로다). ADR-014 psql-sampler.sh는 고치지 않는다(kind가 state | h4뿐이라 이 파일을 따로 둔다).
#   psql 세션 하나(PGAPPNAME = <세션 이름>)에 주기마다 문장을 흘려 넣는다(시작 시각 기준 격자 — 질의 시간이 쌓여 간격이 늘지 않게 · psql-sampler.sh ticker와 같다).
# 표본 한 줄(JSON): t(DB clock_timestamp) · b3_lock = A′ 배치 B3(SweepConditionalBatch.kt:58-62 `DELETE FROM seat_hold h USING unnest(` — 앞머리 정규식)가 Lock 대기인 세션 수 ·
#   b3_on_p2 = 그중 막은 세션(pg_blocking_pids)이 'idle in transaction'이고 마지막 문장이 ② P2(`SELECT status FROM product_seat WHERE id = $1 FOR UPDATE` —
#   AtomicConfirmProcess.kt:118 — 그 바로 뒤가 지연 주입 :121)인 수 = '배치가 지연 중인 ② 확정의 홀드 잠금을 기다린다'(② 경합 증거 — 진 쪽이 배치다).
# 끝: 표준 출력(SSH → k6 PC) 쓰기가 실패하거나 부모(sshd 세션)가 사라지면 끝낸다. 줄은 표준 출력보다 <사본>에 먼저 쓴다(회차 끝 lib14 recover_copy로 복구).
set -u
export LC_ALL=C
copy="$1" period_ms="$2" app="$3"
mkdir -p "$(dirname "$copy")" || exit 1
parent=$PPID
B3RE='^[[:space:]]*DELETE[[:space:]]+FROM[[:space:]]+seat_hold[[:space:]]+h[[:space:]]+USING[[:space:]]+unnest[(]'
P2RE='^[[:space:]]*SELECT[[:space:]]+status[[:space:]]+FROM[[:space:]]+product_seat[[:space:]]+WHERE[[:space:]]+id[[:space:]]*=[[:space:]]*[$]1[[:space:]]+FOR[[:space:]]+UPDATE'
Q="SELECT row_to_json(s) FROM (SELECT clock_timestamp() AS t,
   (SELECT count(*) FROM pg_stat_activity w WHERE w.datname = 'seat' AND w.wait_event_type = 'Lock' AND w.query ~ '$B3RE') AS b3_lock,
   (SELECT count(DISTINCT w.pid) FROM pg_stat_activity w CROSS JOIN LATERAL unnest(pg_blocking_pids(w.pid)) AS bp(pid) JOIN pg_stat_activity b ON b.pid = bp.pid
     WHERE w.datname = 'seat' AND w.wait_event_type = 'Lock' AND w.query ~ '$B3RE' AND b.state = 'idle in transaction' AND b.query ~ '$P2RE') AS b3_on_p2) s;"
Q=${Q//$'\n'/ }
ticker() {
  local now next us d
  next=$(( ${EPOCHREALTIME/./} / 1000 ))
  while kill -0 "$parent" 2> /dev/null; do
    printf '%s\n' "$Q" || return 0
    next=$(( next + period_ms ))
    now=$(( ${EPOCHREALTIME/./} / 1000 ))
    d=$(( next - now ))
    if (( d > 0 )); then printf -v us '%d.%03d' $(( d / 1000 )) $(( d % 1000 )); sleep "$us"; else next=$now; fi
  done
}
ticker | ${B3WAIT_PSQL:-docker exec -i -e PGAPPNAME="$app" seatlab-db-1 psql -X -q -U seat -d seat -tA} 2>> "$copy.err" \
  | while IFS= read -r l; do
      case "$l" in
        '{'*) printf '%s\n' "$l" >> "$copy"; printf '%s\n' "$l" || exit 0 ;;
      esac
    done
