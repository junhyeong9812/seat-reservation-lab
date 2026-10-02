#!/usr/bin/env bash
# ADR-003 캠페인: 조건(전략·풀·앱 대수·셀·단계)마다 run.sh를 부른다. 조건마다 결과 폴더가 따로 생긴다.
#
#   scripts/campaign.sh --sha <SHA> [--id <campaign-id>]
#
# 순서는 회차 우선(rep-major): 1회차를 전 조건 한 바퀴 → 2회차 한 바퀴 → … — 조건 사이의 시간대 차이(ADR-002 §7.4 드리프트)와
# 측정 중 경로 단절이 한 조건에 몰리지 않고 조건마다 고르게 퍼지게. 끝나면 path-gap 회차만 다시 잰다(최대 2바퀴).
# 중단되면 같은 --id로 다시 부르면 된다: 각 조건은 run.sh --resume으로 이어서 재고, 끝난 회차(status 있음)는 건너뛴다.
set -uo pipefail
# 스크립트 동결 — 장시간 도는 동안 작업트리를 고쳐도 실행 중인 캠페인이 깨지지 않게(ADR-001 실측).
if [[ "${CAMPAIGN_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"
  frozen="$(mktemp -d)"
  cp -r "$src" "$frozen/scripts"
  export CAMPAIGN_FROZEN=1 ADR_DIR="$(cd "$src/.." && pwd)"
  export REPO_ROOT="$(cd "$ADR_DIR/../.." && pwd)"
  exec bash "$frozen/scripts/campaign.sh" "$@"
fi
DIR="$(cd "$(dirname "$0")" && pwd)"   # 동결 사본의 scripts/
SHA="" CAMPAIGN="" REPS=5
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --id) CAMPAIGN="$2"; shift 2 ;;
    --reps) REPS="$2"; shift 2 ;;   # 스모크용 — 본측정은 5
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
# 실행하는 하네스 = 기록되는 SHA
if ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-003 ':(exclude)k6/ADR-003/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-003 ':(exclude)k6/ADR-003/results')" ]]; then
  echo "k6/ADR-003 differs from $SHA — commit first" >&2; exit 2
fi
CAMPAIGN="${CAMPAIGN:-$(date +%Y%m%d)-adr003-$SHA}"
ROOT="$ADR_DIR/results/$CAMPAIGN"
mkdir -p "$ROOT"
LOG="$ROOT/CAMPAIGN.log"

# 조건 목록(명세 §9.2) — 이름  run.sh 인자
STRATEGIES="none jvm-lock jvm-lock-in-tx conditional-update pessimistic pessimistic-nowait optimistic unique advisory redis-nx redis-lock"
CONDITIONS=()
for s in $STRATEGIES; do CONDITIONS+=("$s-p10       --strategy $s --pool 10 --apps 1 --levels '2 4' --cells 'S1 S4'"); done
for s in $STRATEGIES; do CONDITIONS+=("$s-p10-s3    --strategy $s --pool 10 --apps 1 --levels '4'   --cells 'S3'"); done
for s in pessimistic advisory redis-lock; do CONDITIONS+=("$s-p20  --strategy $s --pool 20 --apps 1 --levels '2 4' --cells 'S1 S4'"); done
for s in $STRATEGIES; do CONDITIONS+=("$s-p10-2apps --strategy $s --pool 10 --apps 2 --levels '4'   --cells 'S1'"); done
printf '%s\n' "${CONDITIONS[@]}" > "$ROOT/conditions.txt"

run_condition_rep() {  # 인자: 조건 한 줄, 회차. run.sh가 0이 아니면(인자·SHA·배포·연속 unhealthy) 캠페인을 멈춘다 — 조용히 넘어가지 않게
  local line="$1" rep="$2" name="${1%% *}" args="${1#* }" resume="" rc
  [[ -f "$ROOT/$name/plan.json" ]] && resume="--resume"
  echo "$(date -Is) $name rep$rep start $resume" >> "$LOG"
  eval bash "$DIR/run.sh" --sha "$SHA" --id "$CAMPAIGN/$name" --reps "$REPS" --only-rep "$rep" $args $resume >> "$ROOT/$name.runner.log" 2>&1
  rc=$?
  echo "$(date -Is) $name rep$rep exit=$rc" >> "$LOG"
  if (( rc != 0 )); then echo "$(date -Is) ABORT $name rep$rep exit=$rc" >> "$LOG"; exit "$rc"; fi
}

echo "$(date -Is) CAMPAIGN start sha=$SHA reps=$REPS conditions=${#CONDITIONS[@]}" >> "$LOG"
for rep in $(seq 1 "$REPS"); do
  for line in "${CONDITIONS[@]}"; do run_condition_rep "$line" "$rep"; done
done

# path-gap 회차 재측정: 그 회차 폴더를 옆으로 보존하고(.path-gap-<시각>) 같은 회차를 다시 잰다 — 최대 2바퀴
for round in 1 2; do
  gaps=$(grep -l 'path-gap' "$ROOT"/*/L*/*/rep*/status 2>/dev/null | grep -v '\.path-gap-' || true)
  [[ -z "$gaps" ]] && break
  echo "$(date -Is) path-gap re-measure round $round: $(echo "$gaps" | wc -l) reps" >> "$LOG"
  for st in $gaps; do
    rdir="$(dirname "$st")"; mv "$rdir" "$rdir.path-gap-$(date +%Y%m%d%H%M%S)"
    name="$(echo "$rdir" | sed "s#^$ROOT/##; s#/.*##")"; rep="${rdir##*rep}"
    line="$(grep -m1 "^$name " "$ROOT/conditions.txt")"
    run_condition_rep "$line" "$rep"
  done
done
echo "$(date -Is) CAMPAIGN done" >> "$LOG"
