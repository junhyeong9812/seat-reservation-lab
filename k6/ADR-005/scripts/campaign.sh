#!/usr/bin/env bash
# ADR-005 캠페인(ADR-003 campaign.sh 복사): 조건(매수 제어 방식 × 셀 묶음)마다 run.sh를 부른다. 조건마다 결과 폴더가 따로 생긴다.
# 좌석 전략은 pessimistic-nowait(3b) 고정·풀 10·앱 1대·인덱스 V2·배경 0 — 달라지는 변수는 매수 방식뿐(명세 §2·§9.2).
#
#   scripts/campaign.sh --sha <SHA> [--id <campaign-id>]
#
# 순서는 회차 우선(rep-major): 1회차를 전 조건 한 바퀴 → 2회차 한 바퀴 → … — 조건 사이의 시간대 차이(ADR-002 §7.4 드리프트)와
# 측정 중 경로 단절이 한 조건에 몰리지 않고 조건마다 고르게 퍼지게. 끝나면 정상이 아닌 회차를 다시 잰다(최대 2바퀴) —
# 단, '한계 < 첫 단계'(s4-no-successful-stage만)는 실패가 아니라 결과라 다시 재지 않는다. 그래도 남는 비정상 회차는 목록으로 남기고 exit 1.
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
SHA="" CAMPAIGN="" REPS=5 S3_REPS=3 SUITE=main   # ADR-005 명세 §9.2: S2·S4·S7 5회, S3 3회
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --id) CAMPAIGN="$2"; shift 2 ;;
    --reps) REPS="$2"; shift 2 ;;   # 스모크용 — 본측정은 5
    # S3 조건은 N회차까지만(ADR-005 명세 §9.2 — 3회). 계획(plan.json)은 --reps 그대로라 빠진 회차는 요약에 '미측정'으로 드러난다
    --s3-reps) S3_REPS="$2"; shift 2 ;;
    --suite) SUITE="$2"; shift 2 ;;   # ADR-005는 main만(ADR-003의 s6 묶음은 넣지 않았다)
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
# 실행하는 하네스 = 기록되는 SHA
[[ "$SUITE" == main ]] || { echo "--suite main only (ADR-005)" >&2; exit 2; }
if ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-005 ':(exclude)k6/ADR-005/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-005 ':(exclude)k6/ADR-005/results')" ]]; then
  echo "k6/ADR-005 differs from $SHA — commit first" >&2; exit 2
fi
CAMPAIGN="${CAMPAIGN:-$(date +%Y%m%d)-adr005-$SHA}"
ROOT="$ADR_DIR/results/$CAMPAIGN"
mkdir -p "$ROOT"
LOG="$ROOT/CAMPAIGN.log"

# 조건 목록(명세 §9.2 매트릭스) — 이름  run.sh 인자. 매수 방식 8개 × {S2·S4 L2·L4, S3 L4(이탈 0/20/50), S7·S7-m1 L4}
LIMITS="none advisory advisory-try advisory-try-early quota-lock quota-nowait quota-nowait-early counter serializable serializable-retry"   # -early: 사용자 재합의 2026-10-06(명세 순서 + 먼저 거절 변형 추가 측정)
SEAT="--strategy pessimistic-nowait --pool 10 --apps 1"
CONDITIONS=()
for l in $LIMITS; do CONDITIONS+=("$l-s24 --limit-strategy $l $SEAT --levels '2 4' --cells 'S2 S4'"); done
for l in $LIMITS; do CONDITIONS+=("$l-s3  --limit-strategy $l $SEAT --levels '4'   --cells 'S3'"); done
for l in $LIMITS; do CONDITIONS+=("$l-s7  --limit-strategy $l $SEAT --levels '4'   --cells 'S7 S7-m1'"); done
printf '%s\n' "${CONDITIONS[@]}" > "$ROOT/conditions.txt"

INFRA_STREAK=0
run_condition_rep() {  # 인자: 조건 한 줄, 회차. run.sh가 0이 아니면(인자·SHA·배포) 캠페인을 멈춘다 — 조용히 넘어가지 않게
  local line="$1" rep="$2" name="${1%% *}" args="${1#* }" resume="" rc
  [[ -f "$ROOT/$name/plan.json" ]] && resume="--resume"
  local mark; mark="$(mktemp)"   # 이 호출이 쓴 status만 세려고(재측정 전의 옛 실패를 연속 실패로 세지 않게)
  echo "$(date -Is) $name rep$rep start $resume" >> "$LOG"
  local reps="$REPS"; [[ -n "$S3_REPS" && "$name" == *-s3 ]] && reps="$S3_REPS"   # 계획(plan.json) 자체를 실제 회차로 — 4·5회차가 미측정으로 남지 않게
  eval bash "$DIR/run.sh" --sha "$SHA" --id "$CAMPAIGN/$name" --reps "$reps" --only-rep "$rep" $args $resume >> "$ROOT/$name.runner.log" 2>&1
  rc=$?
  echo "$(date -Is) $name rep$rep exit=$rc" >> "$LOG"
  if (( rc != 0 )); then echo "$(date -Is) ABORT $name rep$rep exit=$rc" >> "$LOG"; exit "$rc"; fi
  # 기동 계열 실패(앱이 안 뜸)가 호출 5번 연속이면 빌드·서버 문제 — 멈춘다. 한두 번은 끝의 재측정이 맡는다
  local fresh; fresh=$(find "$ROOT/$name" -path "*/rep$rep/status" -newer "$mark" 2>/dev/null); rm -f "$mark"
  if [[ -n "$fresh" ]] && grep -qsE '^(unhealthy|compose-up-failed|seed-failed)$' $fresh; then
    INFRA_STREAK=$((INFRA_STREAK + 1))
    (( INFRA_STREAK >= 5 )) && { echo "$(date -Is) ABORT infra-failure-5-in-a-row" >> "$LOG"; exit 4; }
  else
    INFRA_STREAK=0
  fi
}

non_ok_reps() {  # 정상 아닌 회차의 status 경로 — 보존된 옛 회차(.path-gap-·.retry-·.incomplete-)와 '결과로서의 실패'는 뺀다
  grep -LxsE 'ok|s4-no-successful-stage' "$ROOT"/*/L*/*/rep*/status 2>/dev/null | grep -vE '\.(path-gap|retry|incomplete)-' || true
}

echo "$(date -Is) CAMPAIGN start sha=$SHA suite=$SUITE reps=$REPS conditions=${#CONDITIONS[@]}" >> "$LOG"
for rep in $(seq 1 "$REPS"); do
  for line in "${CONDITIONS[@]}"; do
    if [[ -n "$S3_REPS" && "${line%% *}" == *-s3 ]] && (( rep > S3_REPS )); then continue; fi
    run_condition_rep "$line" "$rep"
  done
done

# 비정상 회차 재측정: 그 회차 폴더를 옆으로 보존하고(path-gap이면 .path-gap-<시각>, 그 밖은 .retry-<시각>) 같은 회차를 다시 잰다 — 최대 2바퀴
for round in 1 2; do
  bad=$(non_ok_reps)
  [[ -z "$bad" ]] && break
  echo "$(date -Is) re-measure round $round: $(echo "$bad" | wc -l) reps" >> "$LOG"
  for st in $bad; do
    rdir="$(dirname "$st")"; tag=retry; grep -q 'path-gap' "$st" && tag=path-gap
    echo "$(date -Is) re-measure $rdir ($(cat "$st"))" >> "$LOG"
    mv "$rdir" "$rdir.$tag-$(date +%Y%m%d%H%M%S)"
    name="$(echo "$rdir" | sed "s#^$ROOT/##; s#/.*##")"; rep="${rdir##*rep}"
    line="$(grep -m1 "^$name " "$ROOT/conditions.txt")"
    run_condition_rep "$line" "$rep"
  done
done
# 매수 확인 쿼리 DB 벤치(명세 §9.2) — 회차 측정이 끝난 뒤 한 번. 이미 끝났으면(재개) 건너뛴다. 실패는 캠페인 실패로
if [[ ! -f "$ROOT/limit-bench/DONE" ]]; then
  echo "$(date -Is) limit-bench start" >> "$LOG"
  bash "$DIR/limit-bench.sh" --sha "$SHA" --out "$ROOT/limit-bench" >> "$ROOT/limit-bench.runner.log" 2>&1 \
    || { echo "$(date -Is) ABORT limit-bench exit=$?" >> "$LOG"; exit 5; }
  echo "$(date -Is) limit-bench done" >> "$LOG"
fi
left=$(non_ok_reps)
if [[ -n "$left" ]]; then
  echo "$(date -Is) CAMPAIGN done — 비정상 회차 $(echo "$left" | wc -l)개 남음:" >> "$LOG"
  for st in $left; do echo "  $st: $(cat "$st")" >> "$LOG"; done
  exit 1
fi
echo "$(date -Is) CAMPAIGN done — 비정상 회차 0" >> "$LOG"
