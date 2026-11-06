#!/usr/bin/env bash
# ADR-014 PC 링크 끊김 수집(사용자 결정 ② 2026-10-29 · ADR 측정-9 재해석): 회차 시작 ~ 끝 사이 이 PC(k6) 커널 로그에서 '<IF>: Link is Down' 줄을 모아 JSON 한 개로 낸다.
#   pclink14.sh <since_epoch> <until_epoch> [<IF 기본 eno1>]   → 표준 출력 JSON {readable, iface, since_epoch, until_epoch, down, lines, why}
#   요약기(summarize.py --rep)가 pc-link.json을 읽는다: down ≥ 1 → pc-link-down(환경 — 무효 → 재측정 · 환경 상한) · readable이 true가 아니거나 파일이 없으면
#   판정 입력 결측 → pc-link-missing(하네스 — 무효). 링크 끊김을 '없음'으로 넘기지 않도록 못 읽음을 따로 가른다:
#     journalctl rc ≠ 0 · 권한 경고(-q를 쓰지 않는다 — -q는 이 경고를 숨긴다) · 이번 부팅 커널 줄 0개(권한 없는 사용자는 커널 줄을 못 본다) → readable false.
#   sudo 없이 읽힌다(이 PC: 사용자가 adm 그룹 — 2026-10-29 확인). 다른 PC에서 못 읽으면 회차가 모두 pc-link-missing으로 무효가 된다(무음 통과 아님).
set -uo pipefail
since="${1:?since_epoch}" until="${2:?until_epoch}" iface="${3:-${PC_IFACE:-eno1}}"
[[ "$since" =~ ^[0-9]+$ && "$until" =~ ^[0-9]+$ ]] || { echo "since·until은 epoch 초(정수)" >&2; exit 2; }
err="$(mktemp)"; trap 'rm -f "$err"' EXIT
out="$(journalctl -k --no-pager -o short-iso --since "@$since" --until "@$until" 2>"$err")"; rc=$?
nk="$(journalctl -k -b --no-pager -o short-iso -n 5 2>/dev/null | grep -c ' kernel: ')"
readable=true why=""
if (( rc != 0 )); then readable=false why="journalctl rc=$rc: $(head -c 200 "$err")"
elif grep -qiE 'insufficient permissions|no journal files|not seeing messages' "$err"; then readable=false why="권한: $(head -c 200 "$err")"
elif (( nk == 0 )); then readable=false why="이번 부팅 커널 줄 0개(읽기 권한 없음으로 본다)"
fi
lines="$(grep -F -- "$iface: Link is Down" <<< "$out")"
jq -n --argjson r "$readable" --arg i "$iface" --argjson s "$since" --argjson u "$until" --arg l "$lines" --arg w "$why" \
  '{readable: $r, iface: $i, since_epoch: $s, until_epoch: $u, down: ($l | split("\n") | map(select(length > 0)) | length),
    lines: ($l | split("\n") | map(select(length > 0))), why: $w}'
