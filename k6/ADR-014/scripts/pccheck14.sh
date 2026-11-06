#!/usr/bin/env bash
# ADR-014 k6 PC 다른 부하 점검(명세 §10 R5 ② · ADR 측정-9 시작 조건 ④ 재해석 2026-10-30): 이 PC(k6를 돌리는 PC — 서버 .164가 아니다)를 읽기만 한다.
#   pccheck14.sh   → 표준 출력 JSON {ok, why[], busy[], containers_other[], load1, nproc, mem_avail_kb, limits} · exit 0 = 통과 · 1 = 막음(판정 불가 포함)
#   재합의 R6(2026-11-03 — 사용자 '우리는 부하를 만들기만 한다'): ① 다른 컨테이너 ② load는 막지 않고 busy[]에 기록만(이름표 pc-busy-before) —
#   비교 지표는 서버(.164) 쪽이고 k6가 부하를 못 만드는 경우는 회차 판정(dropped-iterations · 탐침 송신 오차 · pc-mem-low)이 무효 → 재측정으로 잡는다. ③ MemAvailable · 판정 불가만 막는다.
#   문턱은 plan14.py --pc-limits(단일 출처 — PC_LOAD1_MAX · PC_MEM_START_MIN_KB): ① docker ps의 실행 중 컨테이너 중 compose 프로젝트가 seatlab이 아닌 것 0개
#   ② /proc/loadavg 1분 load ≤ 문턱 ③ /proc/meminfo MemAvailable ≥ 문턱. docker를 못 읽거나 값을 못 읽으면 막는다(판정 불가 = 시작하지 않음 — '부하 없음'으로 넘기지 않는다).
#   campaign.sh cond_lines(시작 · 이어서 실행 때마다)가 막음 판정에 쓰고, run.sh가 회차마다 pc-check.json으로 남긴다(회차 판정에는 쓰지 않는다 — 이름표 pc-busy-before).
#   다른 프로세스·컨테이너를 멈추거나 바꾸지 않는다(사용자 몫). 시험용: PC_LOADAVG_FILE · PC_MEMINFO_FILE로 읽을 파일을 바꾼다(selftest R5 ②).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
lim="$(python3 -I "$here/plan14.py" --pc-limits)" || { echo '{"ok":false,"why":["plan14 --pc-limits 실패"]}'; exit 1; }
why=() busy=()
ps_out="$(docker ps --format '{{.Names}}\t{{.Label "com.docker.compose.project"}}' 2>&1)"; ps_rc=$?
others="[]"
if (( ps_rc != 0 )); then why+=("docker ps 실패 rc=$ps_rc: ${ps_out:0:160}")
else
  others="$(awk -F'\t' 'NF && $2 != "seatlab" { print $1 }' <<< "$ps_out" | jq -Rsc 'split("\n") | map(select(length > 0))')"
  n=$(jq length <<< "$others"); (( n == 0 )) || busy+=("seatlab 밖 실행 중 컨테이너 $n개: $(jq -r 'join(" ")' <<< "$others")")
fi
load1="$(cut -d' ' -f1 "${PC_LOADAVG_FILE:-/proc/loadavg}" 2>/dev/null)"
[[ "$load1" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { why+=("1분 load 못 읽음"); load1=null; }
mem="$(awk '/^MemAvailable:/ { print $2 }' "${PC_MEMINFO_FILE:-/proc/meminfo}" 2>/dev/null)"
[[ "$mem" =~ ^[0-9]+$ ]] || { why+=("MemAvailable 못 읽음"); mem=null; }
lmax=$(jq -r .load1_max <<< "$lim"); mmin=$(jq -r .mem_start_min_kb <<< "$lim")
[[ "$load1" != null ]] && awk -v a="$load1" -v b="$lmax" 'BEGIN { exit !(a > b) }' && busy+=("1분 load $load1 > $lmax")
[[ "$mem" != null ]] && (( mem < mmin )) && why+=("MemAvailable $(( mem / 1024 ))MiB < $(( mmin / 1024 ))MiB")
bj="$(printf '%s\n' "${busy[@]}" | jq -Rsc 'split("\n") | map(select(length > 0))')"
printf '%s\n' "${why[@]}" | jq -Rsc --argjson b "$bj" --argjson o "$others" --argjson l "$load1" --argjson m "$mem" --argjson n "$(nproc 2>/dev/null || echo null)" --argjson lim "$lim" --arg at "$(date -Is)" \
  'split("\n") | map(select(length > 0)) as $w | {ok: ($w | length == 0), why: $w, busy: $b, containers_other: $o, load1: $l, nproc: $n, mem_avail_kb: $m, limits: $lim, at: $at}'
(( ${#why[@]} == 0 ))
