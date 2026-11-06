#!/usr/bin/env bash
# ADR-015 하네스 파일 해시 — campaign.sh · run.sh 공통 단일 출처(ADR-014 hhash.sh와 같은 규칙 — 캠페인 동결본과 회차가 쓴 하네스를 대조한다).
#   hhash.sh --paths        → 하네스 경로(한 줄에 하나 — git diff · git archive · 해시가 같은 목록을 쓴다). ADR-014 · ADR-009 하네스는 source · import로 쓰므로 함께 묶는다
#   hhash.sh <트리 뿌리>     → sha256(64자) 한 줄 — 경로 아래 파일(*/results · __pycache__ 제외)을 상대 경로 순서로 sha256sum한 목록의 sha256. 경로가 없으면 exit 1
set -uo pipefail
HPATHS15=(k6/ADR-015 k6/ADR-014/scripts k6/ADR-014/scenarios k6/ADR-009/scripts k6/ADR-009/scenarios k6/ADR-009/compose.yml k6/ADR-009/compose.tmpfs.yml k6/ADR-009/compose.probe.yml)
if [[ "${1:-}" == --paths ]]; then printf '%s\n' "${HPATHS15[@]}"; exit 0; fi
[[ -n "${1:-}" ]] || { echo "인자: <트리 뿌리> | --paths" >&2; exit 2; }
cd "$1" || exit 1
for p in "${HPATHS15[@]}"; do [[ -e "$p" ]] || { echo "hhash: $1에 $p 없음" >&2; exit 1; }; done
out=$(find "${HPATHS15[@]}" \( -path k6/ADR-015/results -o -name __pycache__ \) -prune -o -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum) || exit 1
out="${out%% *}"
[[ "$out" =~ ^[0-9a-f]{64}$ ]] || exit 1
echo "$out"
