#!/usr/bin/env bash
# ADR-013 하네스 파일 해시 — campaign.sh · run.sh 공통 단일 출처(CR13-21 — 캠페인 동결본과 회차가 쓴 하네스를 대조한다).
#   hhash.sh --paths        → 하네스 경로(한 줄에 하나 — git diff · git archive · 해시가 같은 목록을 쓴다)
#   hhash.sh <트리 뿌리>     → sha256(64자) 한 줄 — 경로 아래 파일(k6/ADR-013/results · __pycache__ 제외)을 상대 경로 순서로 sha256sum한 목록의 sha256.
#                             경로가 하나라도 없거나 읽기 실패면 exit 1(빈 해시를 내지 않는다)
set -uo pipefail
HPATHS13=(k6/ADR-013 ops/online-v9 k6/ADR-009/scripts k6/ADR-009/scenarios k6/ADR-009/compose.yml k6/ADR-009/compose.tmpfs.yml)
if [[ "${1:-}" == --paths ]]; then printf '%s\n' "${HPATHS13[@]}"; exit 0; fi
[[ -n "${1:-}" ]] || { echo "인자: <트리 뿌리> | --paths" >&2; exit 2; }
cd "$1" || exit 1
for p in "${HPATHS13[@]}"; do [[ -e "$p" ]] || { echo "hhash: $1에 $p 없음" >&2; exit 1; }; done
out=$(find "${HPATHS13[@]}" \( -path k6/ADR-013/results -o -name __pycache__ \) -prune -o -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum) || exit 1
out="${out%% *}"
[[ "$out" =~ ^[0-9a-f]{64}$ ]] || exit 1
echo "$out"
