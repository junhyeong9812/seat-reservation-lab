#!/usr/bin/env bash
# 사용: run.sh <tag> <CLOSE_LAST 0|1> [k6 env KEY=VAL ...] — 서버(Repro.java · keep-alive 2초)를 띄우고 gap.js 1회 · 끝나면 서버 종료
#   실행 조건(태그 · env · k6 버전 · gap.js sha256)을 conditions.tsv에 한 줄 · TIME_WAIT 최댓값(2초 표본 — 서버 쪽 sport · 클라이언트 쪽 dport)을 같은 줄에
set -euo pipefail
D="$(cd "$(dirname "$0")" && pwd)"; TAG="$1"; CL="$2"; shift 2; PORT=18743
W="$(mktemp -d)"; trap 'kill "${SP:-}" "${MP:-}" 2>/dev/null || true; rm -rf "${W:?}"' EXIT
java -cp "$(cat "$D/cp.txt")" "$D/Repro.java" $PORT 2000 "$W" > "$D/srv-$TAG.log" 2>&1 & SP=$!
for i in $(seq 1 60); do grep -q STARTED "$D/srv-$TAG.log" && break; sleep 0.5; done
( smax=0; cmax=0; while :; do s=$(ss -Htan state time-wait "( sport = :$PORT )" | wc -l); c=$(ss -Htan state time-wait "( dport = :$PORT )" | wc -l)
  (( s > smax )) && smax=$s; (( c > cmax )) && cmax=$c; echo "$smax $cmax" > "$W/tw"; sleep 2; done ) & MP=$!
env CLOSE_LAST="$CL" "$@" k6 run --quiet --no-color "$D/gap.js" > "$D/$TAG.log" 2>&1 || echo "k6 exit $?" >> "$D/$TAG.log"
sleep 3; read -r SMAX CMAX < "$W/tw"
printf '%s\tCLOSE_LAST=%s %s\t%s\tgap.js=%s\ttw_server_max=%s\ttw_client_max=%s\n' "$TAG" "$CL" "$*" "$(k6 version | head -1 | cut -d' ' -f2)" "$(sha256sum "$D/gap.js" | cut -c1-12)" "$SMAX" "$CMAX" >> "$D/conditions.tsv"
grep -E "eof_|first_|last_|early_|iterations|dropped" "$D/$TAG.log" || true; tail -1 "$D/conditions.tsv"
