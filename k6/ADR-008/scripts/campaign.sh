#!/usr/bin/env bash
# ADR-008 확정 측정 캠페인(명세 §5 — 기본값 기동으로 기능 셀 재측정): 조건(셀)마다 run.sh를 부른다. 조건마다 결과 폴더가 따로 생긴다.
# ADR-007 campaign.sh(117회 매트릭스·후속 세트)에서 회차 우선 실행·이어서 실행·순서 기록·재측정 골격만 가져왔다.
# 좌석 전략 pessimistic-nowait(3b)·풀 10·앱 1대·인덱스 V2·배경 0·L4·DB 설정 default — **매수 방식은 주입하지 않는다**(run.sh 머리말: 앱 기본값으로 뜬다).
#
#   scripts/campaign.sh --sha <SHA> [--id <campaign-id>]
#   scripts/campaign.sh --sha <SHA> --id <id> --dry-run     # 조건·순서 계획(conditions.txt·order.txt)만 쓰고 끝 — 측정 없음(13행)
#
# 계획(명세 §5 확정 측정) — 조건 이름 = default-<셀 이름표>:
#   default-s2 · default-s3a20 · default-s7m1 · default-s7m1late 각 3회 + default-s4 1회 = 13회 호출(run.sh 한 번 = 조건 하나의 회차 하나).
# 순서 = 회차 우선: 회차 k는 짧은 셀 4개(SHORT 순서)를 (k−1)칸 회전해 돌고, S4(1회)는 1회차 끝에 한 번. 난수 없이 회차 번호만으로 정해져 다시 계산해도 같다.
#   실제 순서는 order.txt(회차 · 회차 안 순번 · 전체 순번 · 조건)와 CAMPAIGN.log(ORDER 행). 이어서 실행할 때 다시 계산한 순서가 order.txt와 다르면 멈춘다.
# 중단되면 같은 --id로 다시 부르면 된다: 각 조건은 run.sh --resume으로 이어서 재고, 끝난 회차(status 있음)는 건너뛴다.
# 끝나면 정상이 아닌 회차를 다시 잰다(최대 2바퀴) — **단 회차 합격 사유(run.sh rep_status: over-limit · counter-mismatch* · s7-wronged-seats ·
#   s7late-order-violation · borrow-not-one · limit-strategy-mismatch · limit-key-present)가 있는 회차는 다시 재지 않는다**: 그것은 측정 결과(기본값이 기준을 못 지켰다)이지
#   하네스 실패가 아니다 — 다시 재서 ok가 나오면 실패가 가려진다. 그대로 남기고 exit 1. 'S4 한계 < 첫 단계'(s4-no-successful-stage 단독)도 결과다(ADR-007과 같다).
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
SHA="" CAMPAIGN="" DRY_RUN=0
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --id) CAMPAIGN="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
# 실행하는 하네스 = 기록되는 SHA (dry-run은 아무것도 재지 않으므로 건너뛴다)
if (( ! DRY_RUN )) && { ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-008 ':(exclude)k6/ADR-008/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-008 ':(exclude)k6/ADR-008/results')" ]]; }; then
  echo "k6/ADR-008 differs from $SHA — commit first" >&2; exit 2
fi
CAMPAIGN="${CAMPAIGN:-$(date +%Y%m%d)-adr008-$SHA}"
ROOT="$ADR_DIR/results/$CAMPAIGN"
mkdir -p "$ROOT"
LOG="$ROOT/CAMPAIGN.log"

# 조건 목록(머리말) — 이름 · 회차 수 · run.sh 인자
SEAT="--strategy pessimistic-nowait --pool 10 --apps 1 --levels '4' --pg-preset default"
SHORT="default-s2 default-s3a20 default-s7m1 default-s7m1late"   # 회전 순서
REPS_SHORT=3
CONDITIONS=(
  "default-s2 $SEAT --cells 'S2'"
  "default-s3a20 $SEAT --cells 'S3-a20'"
  "default-s7m1 $SEAT --cells 'S7-m1'"
  "default-s7m1late $SEAT --cells 'S7-m1-late'"
  "default-s4 $SEAT --cells 'S4'"
)
reps_of() { [[ "$1" == default-s4 ]] && echo 1 || echo "$REPS_SHORT"; }
COND_NEW="$(mktemp)"
printf '%s\n' "${CONDITIONS[@]}" > "$COND_NEW"
if [[ -f "$ROOT/conditions.txt" ]] && ! diff -q "$COND_NEW" "$ROOT/conditions.txt" > /dev/null; then
  echo "conditions.txt가 이번 조건 목록과 다르다 — 이어서 실행 거부" >&2; rm -f "$COND_NEW"; exit 2
fi
mv "$COND_NEW" "$ROOT/conditions.txt"

# 순서(머리말): 회차 k = SHORT를 (k−1)칸 회전, 1회차 끝에 default-s4
ORDER_NEW="$(mktemp)"
{
  echo "# ADR-008 조건 순서 — 회차 우선 · 회차 k = 짧은 셀($SHORT) (k−1)칸 회전 · S4는 1회차 끝 (campaign.sh 머리말)"
  echo "# 회차 회차안순번 전체순번 조건"
  read -r -a short <<< "$SHORT"
  n=${#short[@]} seq_all=0
  for rep in $(seq 1 "$REPS_SHORT"); do
    pos=0
    for i in $(seq 0 $((n - 1))); do
      pos=$((pos + 1)); seq_all=$((seq_all + 1))
      echo "$rep $pos $seq_all ${short[$(( (i + rep - 1) % n ))]}"
    done
    if (( rep == 1 )); then pos=$((pos + 1)); seq_all=$((seq_all + 1)); echo "$rep $pos $seq_all default-s4"; fi
  done
} > "$ORDER_NEW"
# 계획 검증: 13행 · 짧은 셀 각 3회(회차 1·2·3에 한 번씩) · S4 1회 — 어긋나면 exit 2
total=$(grep -vc '^#' "$ORDER_NEW")
expect=$(( $(wc -w <<< "$SHORT") * REPS_SHORT + 1 ))
bad=""
(( total == expect )) || bad+=" 행수 $total≠$expect"
for c in $SHORT; do
  [[ "$(awk -v c="$c" '$4 == c {print $1}' "$ORDER_NEW" | sort -n | tr '\n' ' ')" == "$(seq 1 "$REPS_SHORT" | tr '\n' ' ')" ]] || bad+=" $c 회차 어긋남"
done
[[ "$(awk '$4 == "default-s4"' "$ORDER_NEW" | wc -l)" == 1 ]] || bad+=" default-s4 ≠ 1회"
[[ "$(grep -v '^#' "$ORDER_NEW" | awk '{print $3}' | tr '\n' ' ')" == "$(seq 1 "$total" | tr '\n' ' ')" ]] || bad+=" 전체 순번 어긋남"
[[ -z "$bad" ]] || { echo "순서 계획 검증 실패:$bad" >&2; rm -f "$ORDER_NEW"; exit 2; }
if [[ -f "$ROOT/order.txt" ]] && ! diff -q "$ORDER_NEW" "$ROOT/order.txt" > /dev/null; then
  echo "order.txt가 다시 계산한 순서와 다르다 — 이어서 실행 거부" >&2; rm -f "$ORDER_NEW"; exit 2
fi
mv "$ORDER_NEW" "$ROOT/order.txt"
if (( DRY_RUN )); then
  echo "$(date -Is) DRY-RUN sha=$SHA conditions=${#CONDITIONS[@]} planned_calls=$total — conditions.txt·order.txt만 썼다" >> "$LOG"
  for rep in $(seq 1 "$REPS_SHORT"); do echo "$(date -Is) ORDER rep$rep: $(awk -v r="$rep" '$1 == r {printf "%s ", $4}' "$ROOT/order.txt")" >> "$LOG"; done
  cat "$ROOT/order.txt"
  echo "계획: 조건 ${#CONDITIONS[@]}개 · ${total}회 호출(짧은 셀 4개 × ${REPS_SHORT}회 + S4 1회)" >&2
  exit 0
fi

INFRA_STREAK=0
run_condition_rep() {  # 인자: 조건 한 줄, 회차. run.sh가 0이 아니면(인자·SHA·배포) 캠페인을 멈춘다 — 조용히 넘어가지 않게
  local line="$1" rep="$2" name="${1%% *}" args="${1#* }" resume="" rc
  [[ -f "$ROOT/$name/plan.json" ]] && resume="--resume"
  local mark; mark="$(mktemp)"   # 이 호출이 쓴 status만 세려고
  echo "$(date -Is) $name rep$rep start $resume" >> "$LOG"
  eval bash "$DIR/run.sh" --sha "$SHA" --id "$CAMPAIGN/$name" --reps "$(reps_of "$name")" --only-rep "$rep" $args $resume >> "$ROOT/$name.runner.log" 2>&1
  rc=$?
  echo "$(date -Is) $name rep$rep exit=$rc" >> "$LOG"
  if (( rc != 0 )); then echo "$(date -Is) ABORT $name rep$rep exit=$rc" >> "$LOG"; exit "$rc"; fi
  # 기동 계열 실패(앱이 안 뜸)가 호출 5번 연속이면 빌드·서버 문제 — 멈춘다
  local fresh; fresh=$(find "$ROOT/$name" -path "*/rep$rep/status" -newer "$mark" 2>/dev/null); rm -f "$mark"
  if [[ -n "$fresh" ]] && grep -qsE '^(unhealthy|compose-up-failed|seed-failed)$' $fresh; then
    INFRA_STREAK=$((INFRA_STREAK + 1))
    (( INFRA_STREAK >= 5 )) && { echo "$(date -Is) ABORT infra-failure-5-in-a-row" >> "$LOG"; exit 4; }
  else
    INFRA_STREAK=0
  fi
}

# 회차 합격 사유(머리말) — 이 사유가 하나라도 있는 회차는 다시 재지 않는다
ACCEPT_RE='(^|,)(over-limit|counter-mismatch|counter-mismatch-missing|s7-wronged-seats|s7late-order-violation|borrow-not-one|limit-strategy-mismatch|limit-key-present)(,|$)'
all_status() { ls "$ROOT"/*/L*/*/rep*/status 2>/dev/null | grep -vE '\.(path-gap|retry|incomplete)-' || true; }
non_ok_reps() {  # 정상 아닌 회차의 status 경로(보존된 옛 회차 제외) — 'S4 한계 < 첫 단계' 단독은 결과
  local st; for st in $(all_status); do grep -qxsE 'ok|s4-no-successful-stage' "$st" || echo "$st"; done
}
retryable() { local st; for st in $(non_ok_reps); do grep -qsE "$ACCEPT_RE" "$st" || echo "$st"; done; }

echo "$(date -Is) CAMPAIGN start sha=$SHA conditions=${#CONDITIONS[@]} planned_calls=$total" >> "$LOG"
for rep in $(seq 1 "$REPS_SHORT"); do
  echo "$(date -Is) ORDER rep$rep: $(awk -v r="$rep" '$1 == r {printf "%s ", $4}' "$ROOT/order.txt")" >> "$LOG"
  for name in $(awk -v r="$rep" '$1 == r {print $4}' "$ROOT/order.txt"); do   # order.txt가 정본
    run_condition_rep "$(grep -m1 "^$name " "$ROOT/conditions.txt")" "$rep"
  done
done

# 비정상 회차 재측정(합격 사유 회차 제외 — 머리말): 그 회차 폴더를 옆으로 보존하고(.path-gap-<시각> 또는 .retry-<시각>) 같은 회차를 다시 잰다 — 최대 2바퀴
for round in 1 2; do
  bad=$(retryable)
  [[ -z "$bad" ]] && break
  echo "$(date -Is) re-measure round $round: $(echo "$bad" | wc -l) reps" >> "$LOG"
  for st in $bad; do
    rdir="$(dirname "$st")"; tag=retry; grep -q 'path-gap' "$st" && tag=path-gap
    echo "$(date -Is) re-measure $rdir ($(cat "$st"))" >> "$LOG"
    mv "$rdir" "$rdir.$tag-$(date +%Y%m%d%H%M%S)"
    name="$(echo "$rdir" | sed "s#^$ROOT/##; s#/.*##")"; rep="${rdir##*rep}"
    run_condition_rep "$(grep -m1 "^$name " "$ROOT/conditions.txt")" "$rep"
  done
done
left=$(non_ok_reps)
if [[ -n "$left" ]]; then
  echo "$(date -Is) CAMPAIGN done — 비정상 회차 $(echo "$left" | wc -l)개 남음(합격 사유 회차는 재측정하지 않음):" >> "$LOG"
  for st in $left; do echo "  $st: $(cat "$st")" >> "$LOG"; done
  exit 1
fi
echo "$(date -Is) CAMPAIGN done — 비정상 회차 0" >> "$LOG"
