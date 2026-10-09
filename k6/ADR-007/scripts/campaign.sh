#!/usr/bin/env bash
# ADR-007 캠페인(ADR-006 campaign.sh 복사): 조건(매수 방식 × DB 설정 × 셀)마다 run.sh를 부른다. 조건마다 결과 폴더가 따로 생긴다.
# 좌석 전략은 pessimistic-nowait(3b) 고정·풀 10·앱 1대·인덱스 V2·배경 0·L4만 — 달라지는 변수는 매수 방식·DB 설정·부하(ADR-007 명세 §2·§9.2).
#
#   scripts/campaign.sh --sha <SHA> [--id <campaign-id>]
#   scripts/campaign.sh --sha <SHA> --id <id> --dry-run     # 조건·순서 계획(conditions.txt·order.txt)만 쓰고 끝 — 측정 없음(기본 117행)
#
# 매트릭스(명세 §9.2) — 조건 이름 = <방식>-<설정>-<셀>(셀은 소문자·'-' 없앰: s4 · s4c4200 · s4c2000 · s2 · s7m1 · s3a20):
#   진단     방식 {none, counter-upsert, counter-upsert-precheck} × 설정 {default, nockpt, buf512} × 셀 {S4, S4c4200, S4c2000} × 3회 = 81
#   변형 확인 같은 3방식 × default × {S2, S7-m1, S7-m1-late, S3-a20} × 3회 = 36 (S7-m1-late — 명세 재합의 2026-10-09: U를 R 응답 뒤에 보내 '팔린 좌석에 U' 기회를 만든다)
#   → 39조건 × 3회 = 117회 호출(run.sh 한 번 = 조건 하나의 회차 하나).
# 순서는 회차 우선(rep-major) + 회차마다 균형 순환(ADR-006 방식 — 근거 ADR-006 README '개선 2'):
#   묶음 = (설정, 셀) 13개. 회차 k = 묶음 순서를 (k−1) × ⌈묶음 수 ÷ 회차 수⌉만큼 회전 + 각 묶음 안 방식 순서를 (k−1)만큼 회전.
#   ADR-006은 묶음이 3개라 (k−1)칸 회전으로 충분했지만 13개를 1칸씩 돌리면 3회 동안 묶음 자리가 거의 그대로다(캠페인 안 시각 = 묶음).
#   ⌈13 ÷ 3⌉ = 5칸씩 돌리면 각 묶음이 회차마다 캠페인의 앞·가운데·뒤 1/3을 한 번씩 맡는다. 묶음 목록은 설정을 섞어 둔다(BUNDLES) —
#   한 1/3 안에 세 설정이 고루 들어가게. 방식은 3개 × 3회라 묶음 안 각 자리(1·2·3번째)를 세 방식이 한 번씩 맡는다(라틴 방진).
#   난수·해시 없이 회차 번호만으로 정해져 언제 다시 계산해도 같다(--resume도 같은 순서).
#   실제 순서는 order.txt(회차 · 회차 안 순번 · 캠페인 전체 순번 · 조건)와 CAMPAIGN.log(ORDER 행)에 남긴다. 계산 결과는 회차별 행 수·조건 집합·순번을
#   검증하고(어긋나면 exit 2), 이어서 실행할 때 다시 계산한 순서가 기존 order.txt와 다르면(조건 목록·회차 수·순서 규칙 변경) 멈춘다.
#   끝의 재측정은 order.txt 밖이다(CAMPAIGN.log의 re-measure 행 — compare.py가 실제 호출 순번으로 다시 센다).
# 끝나면 정상이 아닌 회차를 다시 잰다(최대 2바퀴) —
# 단, '한계 < 첫 단계'(s4-no-successful-stage만 — 계단 S4)는 실패가 아니라 결과라 다시 재지 않는다. 그래도 남는 비정상 회차는 목록으로 남기고 exit 1.
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
SHA="" CAMPAIGN="" REPS=3 SUITE=main DRY_RUN=0   # ADR-007 명세 §9.2: 전 조건 3회
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --id) CAMPAIGN="$2"; shift 2 ;;
    --reps) REPS="$2"; shift 2 ;;   # 스모크용 — 본측정은 3
    --suite) SUITE="$2"; shift 2 ;;   # ADR-007은 main만
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
# 실행하는 하네스 = 기록되는 SHA
[[ "$SUITE" == main ]] || { echo "--suite main only (ADR-007)" >&2; exit 2; }
# dry-run은 아무것도 재지 않으므로(계획 파일만) 작업트리 하네스 검사를 건너뛴다
if (( ! DRY_RUN )) && { ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-007 ':(exclude)k6/ADR-007/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-007 ':(exclude)k6/ADR-007/results')" ]]; }; then
  echo "k6/ADR-007 differs from $SHA — commit first" >&2; exit 2
fi
CAMPAIGN="${CAMPAIGN:-$(date +%Y%m%d)-adr007-$SHA}"
ROOT="$ADR_DIR/results/$CAMPAIGN"
mkdir -p "$ROOT"
LOG="$ROOT/CAMPAIGN.log"

# 조건 목록(ADR-007 명세 §9.2 매트릭스, 머리말) — 이름  run.sh 인자. 묶음 = <설정>-<셀 이름표>
LIMITS="none counter-upsert counter-upsert-precheck"   # 순서 순환의 방식 목록 순서이기도 하다(머리말)
# 묶음 목록 순서 = 순서 순환의 묶음 순서 — 설정을 섞어 둔다(머리말: 회차마다 ⌈13 ÷ 3⌉ = 5칸씩 돌 때 한 1/3 안에 세 설정이 고루 들어가게)
BUNDLES="default-s4 nockpt-s4 buf512-s4 default-s4c4200 nockpt-s4c4200 buf512-s4c4200 default-s4c2000 nockpt-s4c2000 buf512-s4c2000 default-s2 default-s7m1 default-s7m1late default-s3a20"
cell_of() {  # 셀 이름표 → run.sh 셀 이름
  case "$1" in s4) echo S4 ;; s4c4200) echo S4c4200 ;; s4c2000) echo S4c2000 ;; s2) echo S2 ;; s7m1) echo S7-m1 ;; s7m1late) echo S7-m1-late ;; s3a20) echo S3-a20 ;; *) return 1 ;; esac
}
SEAT="--strategy pessimistic-nowait --pool 10 --apps 1 --levels '4'"
CONDITIONS=()
for b in $BUNDLES; do
  preset="${b%%-*}" tag="${b#*-}"
  cell="$(cell_of "$tag")" || { echo "묶음 $b: 셀 이름표 $tag 모름" >&2; exit 2; }
  for l in $LIMITS; do CONDITIONS+=("$l-$b --limit-strategy $l --pg-preset $preset $SEAT --cells '$cell'"); done
done
# 조건 목록이 바뀐 채로 이어서 실행하면 결과 폴더 하나에 다른 계획이 섞인다 — 기존 목록과 다르면 멈춘다
if [[ -f "$ROOT/conditions.txt" ]] && ! diff -q <(printf '%s\n' "${CONDITIONS[@]}") "$ROOT/conditions.txt" > /dev/null; then
  echo "conditions.txt가 기존 캠페인과 다르다 — 같은 --id로 다른 조건을 이어 잴 수 없음" >&2; exit 2
fi
printf '%s\n' "${CONDITIONS[@]}" > "$ROOT/conditions.txt"

# 순서 섞기(머리말 — 균형 순환): 회차마다 전 조건이 돈다(ADR-007은 S3도 3회 — 회차별로 빠지는 조건이 없다)
rep_names() {  # 인자: 회차 → 그 회차에 도는 조건 이름 한 줄씩(conditions.txt 순서) — 검증의 기준
  local line
  for line in "${CONDITIONS[@]}"; do echo "${line%% *}"; done
}
ORDER_BODY="$(mktemp)" ORDER_NEW="$(mktemp)"
order_fail() { echo "order.txt 계산 실패: $1 — 캠페인을 시작하지 않는다" >&2; rm -f "$ORDER_BODY" "$ORDER_NEW"; exit 2; }
# 계산: 회차 k → 묶음 순서 (k−1)×⌈묶음 수÷회차 수⌉ 회전, 각 묶음 안 방식 순서 (k−1) 회전. 출력 = '회차 회차안순번 전체순번 조건'
python3 -c 'import sys
reps, methods, bundles = int(sys.argv[1]), sys.argv[2].split(), sys.argv[3].split()
rot = lambda xs, k: xs[k % len(xs):] + xs[:k % len(xs)]
step = -(-len(bundles) // reps)
seq = 0
for k in range(1, reps + 1):
    pos = 0
    for b in rot(bundles, (k - 1) * step):
        for m in rot(methods, k - 1):
            pos += 1; seq += 1
            print(k, pos, seq, f"{m}-{b}")' "$REPS" "$LIMITS" "$BUNDLES" > "$ORDER_BODY" \
  || order_fail "python3 종료 코드 $?"
# 검증(계산과 독립 — bash·awk): 회차마다 조건 집합이 rep_names와 같고(39행), 회차 안 순번 1..n, 전체 순번 1..N(기본 117행)
total=0
for rep in $(seq 1 "$REPS"); do
  want="$(rep_names "$rep" | sort)"; n_want=$(rep_names "$rep" | wc -l)
  got="$(awk -v r="$rep" '$1 == r {print $4}' "$ORDER_BODY" | sort)"
  [[ -n "$want" && "$got" == "$want" ]] || order_fail "회차 $rep 조건이 계획과 다르다(기대 ${n_want}행, 실제 $(awk -v r="$rep" '$1 == r' "$ORDER_BODY" | wc -l)행)"
  [[ "$(awk -v r="$rep" '$1 == r {print $2}' "$ORDER_BODY")" == "$(seq 1 "$n_want")" ]] || order_fail "회차 $rep 회차 안 순번이 1..$n_want 이 아니다"
  total=$((total + n_want))
done
[[ "$(wc -l < "$ORDER_BODY")" -eq "$total" && "$(awk '{print $3}' "$ORDER_BODY")" == "$(seq 1 "$total")" ]] \
  || order_fail "전체 행 수·순번이 기대($total행, 1..$total)와 다르다(실제 $(wc -l < "$ORDER_BODY")행)"
{
  echo "# ADR-007 조건 순서 — 균형 순환: 회차 k = 묶음($BUNDLES) 순서 (k−1)×⌈묶음 수÷회차 수⌉ 회전 · 묶음 안 방식($LIMITS) 순서 (k−1) 회전 (campaign.sh 머리말)"
  echo "# 회차 회차안순번 전체순번 조건"
  cat "$ORDER_BODY"
} > "$ORDER_NEW"
rm -f "$ORDER_BODY"
if [[ -f "$ROOT/order.txt" ]] && ! diff -q "$ORDER_NEW" "$ROOT/order.txt" > /dev/null; then
  echo "order.txt가 다시 계산한 순서와 다르다(조건 목록·회차 수·순서 규칙 변경) — 이어서 실행 거부" >&2; rm -f "$ORDER_NEW"; exit 2
fi
mv "$ORDER_NEW" "$ROOT/order.txt"
if (( DRY_RUN )); then
  echo "$(date -Is) DRY-RUN sha=$SHA reps=$REPS order=rotation conditions=${#CONDITIONS[@]} planned_calls=$total — conditions.txt·order.txt만 썼다" >> "$LOG"
  for rep in $(seq 1 "$REPS"); do echo "$(date -Is) ORDER rep$rep: $(awk -v r="$rep" '$1 == r {printf "%s ", $4}' "$ROOT/order.txt")" >> "$LOG"; done
  cat "$ROOT/order.txt"
  echo "계획: 조건 ${#CONDITIONS[@]}개 × ${REPS}회 = ${total}회 호출" >&2
  exit 0
fi

INFRA_STREAK=0
run_condition_rep() {  # 인자: 조건 한 줄, 회차. run.sh가 0이 아니면(인자·SHA·배포) 캠페인을 멈춘다 — 조용히 넘어가지 않게
  local line="$1" rep="$2" name="${1%% *}" args="${1#* }" resume="" rc
  [[ -f "$ROOT/$name/plan.json" ]] && resume="--resume"
  local mark; mark="$(mktemp)"   # 이 호출이 쓴 status만 세려고(재측정 전의 옛 실패를 연속 실패로 세지 않게)
  echo "$(date -Is) $name rep$rep start $resume" >> "$LOG"
  eval bash "$DIR/run.sh" --sha "$SHA" --id "$CAMPAIGN/$name" --reps "$REPS" --only-rep "$rep" $args $resume >> "$ROOT/$name.runner.log" 2>&1
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

echo "$(date -Is) CAMPAIGN start sha=$SHA suite=$SUITE reps=$REPS order=rotation conditions=${#CONDITIONS[@]} planned_calls=$total" >> "$LOG"
for rep in $(seq 1 "$REPS"); do
  echo "$(date -Is) ORDER rep$rep: $(awk -v r="$rep" '$1 == r {printf "%s ", $4}' "$ROOT/order.txt")" >> "$LOG"
  for name in $(awk -v r="$rep" '$1 == r {print $4}' "$ROOT/order.txt"); do   # order.txt가 정본 — 위에서 다시 계산해 같음을 확인했다
    run_condition_rep "$(grep -m1 "^$name " "$ROOT/conditions.txt")" "$rep"
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
left=$(non_ok_reps)
if [[ -n "$left" ]]; then
  echo "$(date -Is) CAMPAIGN done — 비정상 회차 $(echo "$left" | wc -l)개 남음:" >> "$LOG"
  for st in $left; do echo "  $st: $(cat "$st")" >> "$LOG"; done
  exit 1
fi
echo "$(date -Is) CAMPAIGN done — 비정상 회차 0" >> "$LOG"
