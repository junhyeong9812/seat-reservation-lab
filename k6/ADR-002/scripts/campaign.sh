#!/usr/bin/env bash
# ADR-002 캠페인: 조건(인덱스·풀·배경)마다 run.sh를 순서대로 부른다. 조건마다 결과 폴더가 따로 생긴다.
#
#   scripts/campaign.sh --sha <SHA> [--id <campaign-id>]
#
# 중단되면 같은 --id로 다시 부르면 된다: 끝난 조건은 건너뛰고, 시작한 조건은 run.sh --resume으로 이어서 잰다.
set -uo pipefail
# 스크립트 동결 — 약 이틀 도는 동안 작업트리를 고쳐도 실행 중인 캠페인이 깨지지 않게(ADR-001 실측).
# run.sh·s5-bench.sh도 이 동결 사본에서 불린다. 결과 위치(ADR_DIR)·repo는 원래 경로를 넘긴다.
if [[ "${CAMPAIGN_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"
  frozen="$(mktemp -d)"
  cp -r "$src" "$frozen/scripts"
  export CAMPAIGN_FROZEN=1 ADR_DIR="$(cd "$src/.." && pwd)"
  export REPO_ROOT="$(cd "$ADR_DIR/../.." && pwd)"
  exec bash "$frozen/scripts/campaign.sh" "$@"
fi
DIR="$(cd "$(dirname "$0")" && pwd)"   # 동결 사본의 scripts/
SHA="" CAMPAIGN=""
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --id) CAMPAIGN="$2"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
# 실행하는 하네스 = 기록되는 SHA (S5도 이 확인 아래에서 돈다)
if ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-002 ':(exclude)k6/ADR-002/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-002 ':(exclude)k6/ADR-002/results')" ]]; then
  echo "k6/ADR-002 differs from $SHA — commit first" >&2; exit 2
fi
CAMPAIGN="${CAMPAIGN:-$(date +%Y%m%d)-adr002-$SHA}"
ROOT="$ADR_DIR/results/$CAMPAIGN"
mkdir -p "$ROOT"
LOG="$ROOT/CAMPAIGN.log"

# 조건 목록 — 순서: 환경 동일성 확인 → S5(DB 쿼리) → 핵심(인덱스·풀 10) → 규모 → 풀 20·40 → 인덱스 없음 풀 20·40
CONDITIONS=(
  "c00-envcheck-noidx-p10    --index off --pool 10 --bg 0       --cells S4             --reps 2"
  "c01-idx-p10               --index on  --pool 10 --bg 0       --cells 'S1 S2 S4 S3'  --reps 5"
  "c02-noidx-p10-bg100k      --index off --pool 10 --bg 100000  --cells 'S1 S4'        --reps 5"
  "c03-idx-p10-bg100k        --index on  --pool 10 --bg 100000  --cells 'S1 S4'        --reps 5"
  "c04-noidx-p10-bg1m        --index off --pool 10 --bg 1000000 --cells 'S1 S4'        --reps 5"
  "c05-idx-p10-bg1m          --index on  --pool 10 --bg 1000000 --cells 'S1 S4'        --reps 5"
  "c06-idx-p20               --index on  --pool 20 --bg 0       --cells 'S1 S2 S4 S3'  --reps 5"
  "c07-idx-p40               --index on  --pool 40 --bg 0       --cells 'S1 S2 S4 S3'  --reps 5"
  "c08-noidx-p20             --index off --pool 20 --bg 0       --cells 'S1 S4'        --reps 5"
  "c09-noidx-p40             --index off --pool 40 --bg 0       --cells 'S1 S4'        --reps 5"
)
printf '%s\n' "${CONDITIONS[@]}" > "$ROOT/conditions.txt"

echo "$(date -Is) CAMPAIGN start sha=$SHA" >> "$LOG"
if [[ ! -f "$ROOT/s5/DONE" ]]; then
  echo "$(date -Is) s5 start" >> "$LOG"
  bash "$DIR/s5-bench.sh" --sha "$SHA" --out "$ROOT/s5" >> "$ROOT/s5.runner.log" 2>&1
  echo "$(date -Is) s5 exit=$?" >> "$LOG"
fi
for line in "${CONDITIONS[@]}"; do
  name="${line%% *}"; args="${line#* }"
  if [[ -f "$ROOT/$name/DONE" ]]; then continue; fi
  resume=""; [[ -f "$ROOT/$name/plan.json" ]] && resume="--resume"
  echo "$(date -Is) $name start $resume" >> "$LOG"
  eval bash "$DIR/run.sh" --sha "$SHA" --id "$CAMPAIGN/$name" --levels "'2 4'" $args $resume >> "$ROOT/$name.runner.log" 2>&1
  rc=$?
  echo "$(date -Is) $name exit=$rc" >> "$LOG"
  (( rc == 0 )) && touch "$ROOT/$name/DONE"
done
echo "$(date -Is) CAMPAIGN done" >> "$LOG"
