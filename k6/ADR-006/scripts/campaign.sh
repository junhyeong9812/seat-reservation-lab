#!/usr/bin/env bash
# ADR-006 캠페인(ADR-005 campaign.sh 복사): 조건(매수 제어 방식 × 셀 묶음)마다 run.sh를 부른다. 조건마다 결과 폴더가 따로 생긴다.
# 좌석 전략은 pessimistic-nowait(3b) 고정·풀 10·앱 1대·인덱스 V2·배경 0 — 달라지는 변수는 매수 방식뿐(ADR-006 명세 §2·§9.2).
#
#   scripts/campaign.sh --sha <SHA> [--id <campaign-id>]
#   scripts/campaign.sh --sha <SHA> --id <id> --dry-run     # 조건·순서 계획(conditions.txt·order.txt)만 쓰고 끝 — 측정 없음
#
# 순서는 회차 우선(rep-major): 1회차를 전 조건 한 바퀴 → 2회차 한 바퀴 → … — 조건 사이의 시간대 차이(ADR-002 §7.4 드리프트)와
# 측정 중 경로 단절이 한 조건에 몰리지 않고 조건마다 고르게 퍼지게.
# ADR-006 순서 섞기(명세 §9.3 '고정 시드로 섞기'를 회차 번호 기반 결정적 균형 순환으로 구현 — 근거 README '개선 2'):
#   ADR-005는 회차마다 조건 순서가 같아 '캠페인 안 시각'과 '조건'이 겹쳤다(ADR-005 §7.3 ③ 시간 갈림).
#   회차 k = 묶음(s24·s7m1·s3a20 — 그 회차에 도는 것만) 순서를 (k−1)만큼 회전 + 각 묶음 안 방식(none·advisory-try·counter-upsert) 순서를 (k−1)만큼 회전.
#   → 같은 묶음 안에서 각 방식이 회차마다 다른 자리(1·2·3번째)를 돌아가며 맡는다(해시 정렬은 s24에서 none이 5회 중 한 번도 첫째가 아니었다).
#   난수·해시 없이 회차 번호만으로 정해져 언제 다시 계산해도 같다(--resume도 같은 순서).
#   실제 순서는 order.txt(회차 · 회차 안 순번 · 캠페인 전체 순번 · 조건)와 CAMPAIGN.log(ORDER 행)에 남긴다. 계산 결과는 회차별 행 수·조건 집합·순번을
#   검증하고(어긋나면 exit 2), 이어서 실행할 때 다시 계산한 순서가 기존 order.txt와 다르면(조건 목록·회차 수·순서 규칙 변경) 멈춘다.
#   끝의 재측정은 order.txt 밖이다(CAMPAIGN.log의 re-measure 행 — compare.py가 실제 호출 순번으로 다시 센다).
# 끝나면 정상이 아닌 회차를 다시 잰다(최대 2바퀴) —
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
SHA="" CAMPAIGN="" REPS=5 S3_REPS=3 SUITE=main DRY_RUN=0   # ADR-006 명세 §9.2: S2·S4·S7-m1 5회, S3-a20 3회
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --id) CAMPAIGN="$2"; shift 2 ;;
    --reps) REPS="$2"; shift 2 ;;   # 스모크용 — 본측정은 5
    # S3 조건은 N회차까지만(ADR-006 명세 §9.2 — 3회). 계획(plan.json)은 --reps 그대로라 빠진 회차는 요약에 '미측정'으로 드러난다
    --s3-reps) S3_REPS="$2"; shift 2 ;;
    --suite) SUITE="$2"; shift 2 ;;   # ADR-006은 main만
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
# 실행하는 하네스 = 기록되는 SHA
[[ "$SUITE" == main ]] || { echo "--suite main only (ADR-006)" >&2; exit 2; }
# dry-run은 아무것도 재지 않으므로(계획 파일만) 작업트리 하네스 검사를 건너뛴다
if (( ! DRY_RUN )) && { ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-006 ':(exclude)k6/ADR-006/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-006 ':(exclude)k6/ADR-006/results')" ]]; }; then
  echo "k6/ADR-006 differs from $SHA — commit first" >&2; exit 2
fi
CAMPAIGN="${CAMPAIGN:-$(date +%Y%m%d)-adr006-$SHA}"
ROOT="$ADR_DIR/results/$CAMPAIGN"
mkdir -p "$ROOT"
LOG="$ROOT/CAMPAIGN.log"

# 조건 목록(ADR-006 명세 §9.2 매트릭스) — 이름  run.sh 인자. 매수 방식 3개 × {S2·S4 L2·L4, S7-m1 L4, S3 이탈 20% L4}
# (S3는 이탈 20%만 · S7은 M=1만 — 셀 이름을 그대로 넘긴다: run.sh expand_cells는 S3만 0/20/50으로 펼치고 S3-a20·S7-m1은 그대로 둔다)
LIMITS="none advisory-try counter-upsert"   # 순서 순환의 방식 목록 순서이기도 하다(머리말)
BUNDLES="s24 s7m1 s3a20"                    # 묶음 목록 순서 = 순서 순환의 묶음 순서
SEAT="--strategy pessimistic-nowait --pool 10 --apps 1"
CONDITIONS=()
for l in $LIMITS; do CONDITIONS+=("$l-s24 --limit-strategy $l $SEAT --levels '2 4' --cells 'S2 S4'"); done
for l in $LIMITS; do CONDITIONS+=("$l-s7m1 --limit-strategy $l $SEAT --levels '4' --cells 'S7-m1'"); done
for l in $LIMITS; do CONDITIONS+=("$l-s3a20 --limit-strategy $l $SEAT --levels '4' --cells 'S3-a20'"); done
is_s3() { [[ "$1" == *-s3a20 ]]; }
# 조건 목록이 바뀐 채로 이어서 실행하면 결과 폴더 하나에 다른 계획이 섞인다 — 기존 목록과 다르면 멈춘다
if [[ -f "$ROOT/conditions.txt" ]] && ! diff -q <(printf '%s\n' "${CONDITIONS[@]}") "$ROOT/conditions.txt" > /dev/null; then
  echo "conditions.txt가 기존 캠페인과 다르다 — 같은 --id로 다른 조건을 이어 잴 수 없음" >&2; exit 2
fi
printf '%s\n' "${CONDITIONS[@]}" > "$ROOT/conditions.txt"

# 순서 섞기(머리말 — 균형 순환): 회차 k에 실제로 도는 조건(S3는 S3_REPS 회차까지)
rep_names() {  # 인자: 회차 → 그 회차에 도는 조건 이름 한 줄씩(conditions.txt 순서) — 검증의 기준
  local rep="$1" line n
  for line in "${CONDITIONS[@]}"; do
    n="${line%% *}"
    if [[ -n "$S3_REPS" ]] && is_s3 "$n" && (( rep > S3_REPS )); then continue; fi
    echo "$n"
  done
}
ORDER_BODY="$(mktemp)" ORDER_NEW="$(mktemp)"
order_fail() { echo "order.txt 계산 실패: $1 — 캠페인을 시작하지 않는다" >&2; rm -f "$ORDER_BODY" "$ORDER_NEW"; exit 2; }
# 계산: 회차 k → 묶음 순서 (k−1) 회전, 각 묶음 안 방식 순서 (k−1) 회전. 출력 = '회차 회차안순번 전체순번 조건'
python3 -c 'import sys
reps, s3_reps, methods, bundles, s3b = int(sys.argv[1]), sys.argv[2], sys.argv[3].split(), sys.argv[4].split(), sys.argv[5]
rot = lambda xs, k: xs[k % len(xs):] + xs[:k % len(xs)]
seq = 0
for k in range(1, reps + 1):
    bs = [b for b in bundles if not (b == s3b and s3_reps and k > int(s3_reps))]
    pos = 0
    for b in rot(bs, k - 1):
        for m in rot(methods, k - 1):
            pos += 1; seq += 1
            print(k, pos, seq, f"{m}-{b}")' "$REPS" "$S3_REPS" "$LIMITS" "$BUNDLES" s3a20 > "$ORDER_BODY" \
  || order_fail "python3 종료 코드 $?"
# 검증(계산과 독립 — bash·awk): 회차마다 조건 집합이 rep_names와 같고(S3_REPS 이하 회차 9행 · 그 밖 6행 — 기본 39행), 회차 안 순번 1..n, 전체 순번 1..N
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
  echo "# ADR-006 조건 순서 — 균형 순환: 회차 k = 묶음($BUNDLES) 순서 (k−1) 회전 · 묶음 안 방식($LIMITS) 순서 (k−1) 회전 (campaign.sh 머리말 · README 개선 2)"
  echo "# 회차 회차안순번 전체순번 조건"
  cat "$ORDER_BODY"
} > "$ORDER_NEW"
rm -f "$ORDER_BODY"
if [[ -f "$ROOT/order.txt" ]] && ! diff -q "$ORDER_NEW" "$ROOT/order.txt" > /dev/null; then
  echo "order.txt가 다시 계산한 순서와 다르다(조건 목록·회차 수·순서 규칙 변경) — 이어서 실행 거부" >&2; rm -f "$ORDER_NEW"; exit 2
fi
mv "$ORDER_NEW" "$ROOT/order.txt"
if (( DRY_RUN )); then
  echo "$(date -Is) DRY-RUN sha=$SHA reps=$REPS s3_reps=$S3_REPS order=rotation conditions=${#CONDITIONS[@]} — conditions.txt·order.txt만 썼다" >> "$LOG"
  for rep in $(seq 1 "$REPS"); do echo "$(date -Is) ORDER rep$rep: $(awk -v r="$rep" '$1 == r {printf "%s ", $4}' "$ROOT/order.txt")" >> "$LOG"; done
  cat "$ROOT/order.txt"; exit 0
fi

INFRA_STREAK=0
run_condition_rep() {  # 인자: 조건 한 줄, 회차. run.sh가 0이 아니면(인자·SHA·배포) 캠페인을 멈춘다 — 조용히 넘어가지 않게
  local line="$1" rep="$2" name="${1%% *}" args="${1#* }" resume="" rc
  [[ -f "$ROOT/$name/plan.json" ]] && resume="--resume"
  local mark; mark="$(mktemp)"   # 이 호출이 쓴 status만 세려고(재측정 전의 옛 실패를 연속 실패로 세지 않게)
  echo "$(date -Is) $name rep$rep start $resume" >> "$LOG"
  local reps="$REPS"; [[ -n "$S3_REPS" ]] && is_s3 "$name" && reps="$S3_REPS"   # 계획(plan.json) 자체를 실제 회차로 — 4·5회차가 미측정으로 남지 않게
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

echo "$(date -Is) CAMPAIGN start sha=$SHA suite=$SUITE reps=$REPS s3_reps=$S3_REPS order=rotation conditions=${#CONDITIONS[@]}" >> "$LOG"
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
# (ADR-005의 끝 단계 DB 벤치 limit-bench는 ADR-006 매트릭스에 없다 — 하네스에서 뺐다)
left=$(non_ok_reps)
if [[ -n "$left" ]]; then
  echo "$(date -Is) CAMPAIGN done — 비정상 회차 $(echo "$left" | wc -l)개 남음:" >> "$LOG"
  for st in $left; do echo "  $st: $(cat "$st")" >> "$LOG"; done
  exit 1
fi
echo "$(date -Is) CAMPAIGN done — 비정상 회차 0" >> "$LOG"
