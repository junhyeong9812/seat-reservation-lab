#!/usr/bin/env bash
# ADR-018 하네스 파일 해시 — run18.sh · campaign18.sh 공통(ADR-017 hhash17.sh와 같은 규칙). ADR-017 · 016 · 015 · 014 · 009 하네스는 source · import로 쓰므로 함께 묶는다
#   (plan18.py → plan16 · plan15 / outcome18.py → outcome17 / lib18.sh → lib17 → lib15 → lib14 → lib.sh / lib18.js → lib17.js → lib15.js → lib14.js · common.js /
#    compose: ADR-009 · 015 · 017 오버레이 위에 compose.pay18.yml).
#   hhash18.sh --paths   → 하네스 경로(한 줄에 하나 — git diff · git archive · 해시가 같은 목록을 쓴다)
#   hhash18.sh <트리 뿌리> → sha256(64자) 한 줄 — 경로 아래 파일(*/results · __pycache__ 제외)을 상대 경로 순서로 sha256sum한 목록의 sha256
set -uo pipefail
HPATHS18=(k6/ADR-018 k6/ADR-017/scripts k6/ADR-017/scenarios k6/ADR-017/compose.pay17.yml k6/ADR-016/scripts k6/ADR-016/scenarios
          k6/ADR-015/scripts k6/ADR-015/scenarios k6/ADR-015/compose.confirm.yml k6/ADR-014/scripts k6/ADR-014/scenarios
          k6/ADR-009/scripts k6/ADR-009/scenarios k6/ADR-009/compose.yml k6/ADR-009/compose.tmpfs.yml k6/ADR-009/compose.probe.yml)
if [[ "${1:-}" == --paths ]]; then printf '%s\n' "${HPATHS18[@]}"; exit 0; fi
[[ -n "${1:-}" ]] || { echo "인자: <트리 뿌리> | --paths" >&2; exit 2; }
cd "$1" || exit 1
for p in "${HPATHS18[@]}"; do [[ -e "$p" ]] || { echo "hhash18: $1에 $p 없음" >&2; exit 1; }; done
out=$(find "${HPATHS18[@]}" \( -path k6/ADR-018/results -o -name __pycache__ \) -prune -o -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum) || exit 1
out="${out%% *}"
[[ "$out" =~ ^[0-9a-f]{64}$ ]] || exit 1
echo "$out"
