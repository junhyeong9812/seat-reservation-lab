#!/usr/bin/env bash
# ADR-014 서버 쪽 DB 표본기(측정-3.6 '1초 상태 표본기' · 측정-5 M10 'H4 회수 문장 표지'). **서버에서** 돈다 —
#   k6 PC가 `ssh <서버> "bash -s -- <사본> <주기 ms> <state|h4> <만료 행 주기(틱)> <세션 이름>" < psql-sampler.sh`로 보낸다(lib14.sh psql_sampler — ADR-009 host-sampler.sh와 같은 방식:
#   서버에 배포할 것이 없고, 동결 사본 그대로라 기록된 하네스와 같다).
#   psql 세션 하나(PGAPPNAME = <세션 이름> — 대기 표본 by_src에서 'other' 갈래 · 업무 세션 'app'과 섞이지 않는다)에 주기마다 문장을 흘려 넣는다(ADR-013 mlock_sampler 방식 —
#   표본마다 docker exec를 띄우지 않는다). 주기는 시작 시각 기준 격자(next += 주기 — 질의 시간이 쌓여 간격이 늘지 않게 · 측정-9 '표본 수 < 창 초 × 0.95'를 지키려고).
#   state: 측정-5 머리 SQL(회차 1 · 1초) + sample_ms(질의 실행 시간 — 서버 쪽: 바로 앞 문장이 잰 clock_timestamp부터 이 문장 투영 시각까지 · 리뷰 MR-1).
#          <만료 행 주기> = 1이면 expired_rows를 같은 문장에 · 10이면 1초 문장에서 빼고 10틱마다 따로 한 줄({"kind":"expired"} — 측정-5 머리 '표본 방식 변경')
#   h4:    pg_stat_activity의 회수 전용 문장 표지(/* seatlab:h4-reclaim */ — LazyHoldProcess.H4_MARK) 활성 수 · 그중 Lock 대기 수(리뷰 MR-13 · [구현 검증] #24)
# 끝: 표준 출력(SSH → k6 PC) 쓰기가 실패하거나 부모(sshd 세션)가 사라지면 끝낸다(ADR-009 db_sampler · host-sampler.sh와 같은 규칙). 줄은 표준 출력보다 <사본>에 먼저 쓴다
#     — 흘려받기가 끊겨도 회차 끝에 사본으로 복구한다(lib14.sh recover_copy).
set -u
export LC_ALL=C
copy="$1" period_ms="$2" kind="$3" every="$4" app="$5"
mkdir -p "$(dirname "$copy")" || exit 1
parent=$PPID
Q0='SELECT (extract(epoch FROM clock_timestamp()) * 1000)::numeric(20,3) AS q0 \gset'
EXP_COL=", (SELECT count(*) FROM seat_hold WHERE schedule_id = 1 AND expires_at <= now()) AS expired_rows"
[[ "$every" == 1 ]] || EXP_COL=""
case "$kind" in
  state)
    Q="SELECT row_to_json(s) FROM (SELECT clock_timestamp() AS t, now() AS db_now,
       count(*) FILTER (WHERE st.status = 'RESERVED') AS reserved,
       count(*) FILTER (WHERE st.status <> 'RESERVED' AND a.n IS NULL) AS free_by_rule,
       count(*) FILTER (WHERE st.status = 'HELD' AND a.n IS NULL) AS held_no_active,
       count(*) FILTER (WHERE a.n IS NOT NULL AND st.status <> 'HELD') AS active_not_held,
       count(*) FILTER (WHERE a.n > 1) AS multi_active,
       count(*) FILTER (WHERE a.n IS NOT NULL AND st.status = 'RESERVED') AS active_on_reserved $EXP_COL,
       round(extract(epoch FROM clock_timestamp()) * 1000 - :q0, 3) AS sample_ms
     FROM product_seat st
     LEFT JOIN (SELECT seat_id, count(*) AS n FROM seat_hold WHERE schedule_id = 1 AND expires_at > now() GROUP BY seat_id) a ON a.seat_id = st.id
     WHERE st.schedule_id = 1) s;"
    QE="SELECT row_to_json(e) FROM (SELECT clock_timestamp() AS t, 'expired' AS kind, (SELECT count(*) FROM seat_hold WHERE schedule_id = 1 AND expires_at <= now()) AS expired_rows,
       round(extract(epoch FROM clock_timestamp()) * 1000 - :q0, 3) AS sample_ms) e;" ;;
  h4)
    Q="SELECT row_to_json(h) FROM (SELECT clock_timestamp() AS t,
       count(*) FILTER (WHERE query LIKE '%seatlab:h4-reclaim%') AS h4_active,
       count(*) FILTER (WHERE query LIKE '%seatlab:h4-reclaim%' AND wait_event_type = 'Lock') AS h4_lock
     FROM pg_stat_activity WHERE datname = 'seat' AND state = 'active' AND pid <> pg_backend_pid()) h;"
    QE="" ;;
  *) echo "kind: state|h4" >&2; exit 2 ;;
esac
Q=${Q//$'\n'/ }
QE=${QE//$'\n'/ }
ticker() {
  local i=0 now next us d
  next=$(( ${EPOCHREALTIME/./} / 1000 ))
  while kill -0 "$parent" 2> /dev/null; do
    printf '%s\n%s\n' "$Q0" "$Q" || return 0
    if [[ -n "$QE" && "$every" != 1 ]] && (( i % every == 0 )); then printf '%s\n%s\n' "$Q0" "$QE" || return 0; fi
    i=$(( i + 1 ))
    next=$(( next + period_ms ))
    now=$(( ${EPOCHREALTIME/./} / 1000 ))
    d=$(( next - now ))
    if (( d > 0 )); then printf -v us '%d.%03d' $(( d / 1000 )) $(( d % 1000 )); sleep "$us"; else next=$now; fi
  done
}
ticker | docker exec -i -e PGAPPNAME="$app" seatlab-db-1 psql -X -q -U seat -d seat -tA 2>> "$copy.err" \
  | while IFS= read -r l; do
      case "$l" in
        '{'*) printf '%s\n' "$l" >> "$copy"; printf '%s\n' "$l" || exit 0 ;;
      esac
    done
