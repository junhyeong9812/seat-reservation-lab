#!/usr/bin/env bash
# ADR-016 측정 캠페인: paid 2구성 × 3 · race 2구성 × 3 · cost 3구성 × 3 = 21회차(측정 명세 §1-5) + 무효 재측정. ADR-015 campaign.sh의 얇은 변형 —
#   같은 동결(--sha git archive · 해시) · 같은 이어서 실행 대조(--id) · 같은 재측정 상한(자리마다 환경 2번 · 하네스 2번 따로) · 연속 무효 5회 정지 ·
#   루프 안 실행기 `< /dev/null` · 끝 집계 전 계획 대 실행 대조(exit 6). 바뀐 것: 계획 = plan16.py --order · paid · cost 자리 = run16.sh ·
#   race 자리 = ADR-015 run.sh --results-root <이 캠페인 결과 뿌리>(plan15 race 값 그대로 — 회차 폴더 race/<모드>/rep<k>) · plan16 FIXED가 False면 실제 실행 거부 ·
#   끝 집계 = summarize16.py <캠페인 폴더>. ADR-015 조기 확인(race current 경합 증거)은 current 자리가 없어 없다.
#
#   scripts/campaign16.sh --sha <SHA> --images <images.json> --start-conditions <json> [--id <id>] [--k6-bin k6] [--probe-k6-bin <경로>]
#   scripts/campaign16.sh --sha <SHA> [--start-conditions <json>] --dry-run
#   scripts/campaign16.sh --sha <SHA> --simulate --results-root <폴더> [--sim-plan <파일>] [--worktree] [--id <id>]
#   scripts/campaign16.sh --check <캠페인 결과 폴더>
# 시작 조건(--start-conditions JSON): ⓪ 하네스 동결 · 이미지 고정 ① .164 스모크(paid · race · cost 각 1회 축소판) smoke.done ② 캠페인 스모크 campaign_smoke.done
#   ③ dry-run(plan16.py --dry-run exit 0) ④ k6 PC 점검(pc_check.done + pccheck14.sh) ⑤ 측정 전 고정(plan16 FIXED = True) ⑥ 측정 SHA 전체 테스트 green tests_green.done · sha
# exit: 1 시작 조건 · 2 인자 · 동결 · 3 연속 무효 5회 · 5 해시 · 6 계획 대 실행 · 7 끝 집계 · 8 요약기 예외(사용자 보고)
# sim-plan 줄: "<회차>:<셀>:<구성 — race는 ADR-015 모드 이름>:<시도>=<status>"
set -uo pipefail
if [[ "${1:-}" == --check ]]; then CHECK_ONLY="${2:?--check <결과 폴더>}"; fi
if [[ -z "${CHECK_ONLY:-}" && "${CAMPAIGN16_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"
  sha_arg=HEAD prev="" wt=0 testmode=0
  for a in "$@"; do [[ "$prev" == --sha ]] && sha_arg="$a"; [[ "$a" == --worktree ]] && wt=1; [[ "$a" == --dry-run || "$a" == --simulate ]] && testmode=1; prev="$a"; done
  (( wt && ! testmode )) && { echo "--worktree는 --dry-run · --simulate 전용" >&2; exit 2; }
  if (( ! testmode )) && ! python3 -I "$src/plan16.py" --fixed > /dev/null; then   # 동결 전에도 막는다(동결본 안에서 한 번 더 — 아래)
    echo "plan16.py FIXED = False — 측정 전 고정 커밋 전이라 실제 캠페인을 거부한다(--dry-run · --simulate만)" >&2; exit 2
  fi
  sha=$(git -C "$repo" rev-parse --short=8 "${sha_arg}^{commit}" 2>/dev/null) || { echo "SHA 해석 실패: $sha_arg" >&2; exit 2; }
  mapfile -t hp < <(bash "$src/hhash16.sh" --paths)
  EX=(':(exclude)k6/ADR-016/results' ':(exclude)k6/ADR-015/results')
  dirty=""
  git -C "$repo" diff --quiet "$sha" -- "${hp[@]}" "${EX[@]}" 2>/dev/null || dirty="작업트리 변경"
  [[ -z "$(git -C "$repo" ls-files --others --exclude-standard -- "${hp[@]}" "${EX[@]}")" ]] || dirty="${dirty:+$dirty · }미추적 파일"
  frozen="$(mktemp -d)"
  if (( wt )); then
    tar -C "$repo" --exclude=k6/ADR-016/results --exclude=k6/ADR-015/results --exclude=__pycache__ -cf - "${hp[@]}" | tar -x -C "$frozen" \
      || { echo "작업트리 사본 실패" >&2; rm -rf "${frozen:?}"; exit 2; }
    dirty="worktree(시험 전용)${dirty:+ · $dirty}"
  else
    git -C "$repo" archive "$sha" -- "${hp[@]}" "${EX[@]}" | tar -x -C "$frozen" \
      && [[ -f "$frozen/k6/ADR-016/scripts/campaign16.sh" ]] || { echo "git archive $sha 동결 실패(그 SHA에 하네스가 없다?)" >&2; rm -rf "${frozen:?}"; exit 2; }
  fi
  hh=$(bash "$frozen/k6/ADR-016/scripts/hhash16.sh" "$frozen") || { echo "동결본 해시 실패" >&2; rm -rf "${frozen:?}"; exit 2; }
  hh15=$(bash "$frozen/k6/ADR-015/scripts/hhash.sh" "$frozen") || { echo "동결본 ADR-015 해시 실패" >&2; rm -rf "${frozen:?}"; exit 2; }
  export CAMPAIGN16_FROZEN=1 C16_REPO="$repo" C16_SHA="$sha" C16_FROZEN="$frozen" C16_HHASH="$hh" C16_HHASH15="$hh15" C16_DIRTY="$dirty" C16_WT="$wt"
  exec bash "$frozen/k6/ADR-016/scripts/campaign16.sh" "$@"
fi
SRC="$(cd "$(dirname "$0")" && pwd)"
cdir() { if [[ "$1" == race ]]; then [[ "$2" == cond ]] && echo conditional || echo conditional-extend; else echo "$2"; fi; }   # race 회차 폴더 = ADR-015 모드 이름
st_of() { cat "$ROOT/$2/$(cdir "$2" "$3")/rep$1/status" 2>/dev/null; }   # 인자: rep cell config
missing_of() { local seq k c g; while read -r seq k c g; do [[ -n "$seq" ]] || continue; [[ -n "$(st_of "$k" "$c" "$g")" ]] || echo "#$seq rep$k $c $g"; done; }
if [[ -n "${CHECK_ONLY:-}" ]]; then
  ROOT="$(cd "$CHECK_ONLY" && pwd)" || exit 2
  [[ -s "$ROOT/order.txt" ]] || { echo "order.txt 없음 — $ROOT" >&2; exit 2; }
  want="$(python3 -I "$SRC/plan16.py" --order)" || exit 2
  [[ "$(cat "$ROOT/order.txt")" == "$want" ]] || { echo "order.txt가 plan16.py --order와 다르다" >&2; exit 2; }
  missing=$(missing_of < "$ROOT/order.txt")
  if [[ -n "$missing" ]]; then echo "계획 대 실행: 실행되지 않은 자리 $(wc -l <<< "$missing")개 — $(tr '\n' ' ' <<< "$missing")" >&2; exit 6; fi
  echo "계획 대 실행: $(grep -c . "$ROOT/order.txt")자리 모두 status 있음"; exit 0
fi
trap '[[ "$C16_FROZEN" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "${C16_FROZEN:?}"' EXIT
REPO="$C16_REPO"; ADR16="$REPO/k6/ADR-016"
SHA="" IMAGES="" START="" CID="" K6_BIN=k6 PROBE_K6_BIN="" DRY=0 SIMU=0 RESULTS_ROOT="" SIM_PLAN_F=""
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;; --images) IMAGES="$2"; shift 2 ;; --start-conditions) START="$2"; shift 2 ;;
    --id) CID="$2"; shift 2 ;; --k6-bin) K6_BIN="$2"; shift 2 ;; --probe-k6-bin) PROBE_K6_BIN="$2"; shift 2 ;; --results-root) RESULTS_ROOT="$2"; shift 2 ;;
    --sim-plan) SIM_PLAN_F="$2"; shift 2 ;; --dry-run) DRY=1; shift ;; --simulate) SIMU=1; shift ;; --worktree) shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
SHA=$(git -C "$REPO" rev-parse --short=8 "${SHA:-HEAD}^{commit}") || exit 2
[[ "$SHA" == "$C16_SHA" ]] || { echo "동결 SHA $C16_SHA ≠ --sha $SHA" >&2; exit 2; }
FIXED_RC=0; python3 -I "$SRC/plan16.py" --fixed > /dev/null || FIXED_RC=1
if (( ! DRY && ! SIMU && FIXED_RC )); then echo "plan16.py FIXED = False — 측정 전 고정 커밋 전이라 실제 캠페인을 거부한다(--dry-run · --simulate만)" >&2; exit 2; fi
ID_GIVEN=0; [[ -n "$CID" ]] && ID_GIVEN=1
if (( ! DRY && ! SIMU )) && [[ -z "$CID" ]]; then CID="$(date +%Y%m%d)-adr016-$SHA"; fi
CID="${CID:-sim-$SHA}"
ROOT="${RESULTS_ROOT:-$ADR16/results}/$CID"
# 재개는 --id로만(리뷰 L2-7 · 명세 금지영역 '캠페인 재개 시 --id 없이 실행'): 자동 생성한 ID의 결과 폴더가 이미 있으면 이어 쓰지 않는다
if (( ! DRY && ! ID_GIVEN )) && [[ -e "$ROOT" ]]; then echo "자동 ID $CID의 결과 폴더가 이미 있다($ROOT) — 재개는 --id $CID로" >&2; exit 2; fi
ORDER="$(python3 -I "$SRC/plan16.py" --order)" || { echo "계획(plan16.py --order) 실패" >&2; exit 2; }
DRYJ="$(python3 -I "$SRC/plan16.py" --dry-run --json)"; DRY_RC=$?
jflag() { [[ -s "$START" ]] && [[ "$(jq -r "$1 // false" "$START" 2>/dev/null)" == true ]]; }
cond_lines() {
  local ok=1
  if [[ -z "$C16_DIRTY" ]]; then echo "⓪ 하네스 = $SHA(무변경 · 미추적 없음) · 동결본 해시 ${C16_HHASH:0:12} → 통과"; else echo "⓪ 하네스 = $SHA 아님($C16_DIRTY) → 막음"; ok=0; fi
  if [[ -s "$IMAGES" ]] && jq -e --arg s "$SHA" '.app.sha == $s and ([.app.id, (.aux[])] | all(test("^sha256:")))' "$IMAGES" > /dev/null 2>&1; then echo "⓪ 이미지 고정 → 통과"
  else echo "⓪ 이미지 고정 — --images 없음 · SHA 다름 → 막음"; ok=0; fi
  jflag .smoke.done && echo "① .164 스모크 → 통과" || { echo "① .164 스모크 — smoke.done 아님 → 막음"; ok=0; }
  jflag .campaign_smoke.done && echo "② 캠페인 스모크 → 통과" || { echo "② 캠페인 스모크 — campaign_smoke.done 아님 → 막음"; ok=0; }
  if (( DRY_RC == 0 )); then echo "③ dry-run $(jq -r .n <<< "$DRYJ")회차 · 예상 $(jq -r .est_hours <<< "$DRYJ")시간 → 통과"; else echo "③ dry-run 문제 $(jq -c .problems <<< "$DRYJ" 2>/dev/null) → 막음"; ok=0; fi
  local pcj; pcj="$(bash "$C16_FROZEN/k6/ADR-014/scripts/pccheck14.sh" 2>/dev/null)"; local pcrc=$?
  if ! jflag .pc_check.done; then echo "④ k6 PC 점검 — pc_check.done 아님 → 막음"; ok=0
  elif (( pcrc == 0 )) && [[ "$(jq -r .ok <<< "$pcj" 2>/dev/null)" == true ]]; then echo "④ k6 PC 점검 → 통과"
  else echo "④ k6 PC 점검 — $(jq -r '.why | join(" · ")' <<< "$pcj" 2>/dev/null || echo "pccheck14 출력 못 읽음") → 막음"; ok=0; fi
  (( FIXED_RC == 0 )) && echo "⑤ 측정 전 고정(plan16 FIXED) → 통과" || { echo "⑤ 측정 전 고정 — plan16 FIXED = False(자리표시 값) → 막음"; ok=0; }
  if jflag .tests_green.done && [[ "$(jq -r '.tests_green.sha // ""' "$START" | cut -c1-8)" == "$SHA" ]]; then echo "⑥ 전체 테스트 green @ $SHA → 통과"
  else echo "⑥ 전체 테스트 green — tests_green.done · sha = $SHA 아님 → 막음"; ok=0; fi
  return $(( ! ok ))
}
if (( DRY )); then
  echo "# ADR-016 캠페인 계획 — id $CID · 하네스 $SHA · 결과 $ROOT"
  python3 -I "$SRC/plan16.py" --dry-run
  echo "# 순서"; echo "$ORDER"
  echo "# 시작 조건"; cond_lines; echo "시작 조건 판정: $( (( $? == 0 )) && echo 시작 가능 || echo 시작하지 않음)"
  exit 0
fi
COND_TMP="$(mktemp)"
if (( ! SIMU )); then
  cond_lines | tee /dev/stderr > "$COND_TMP"; (( ${PIPESTATUS[0]} == 0 )) || { echo "시작 조건 미충족 — 시작하지 않는다" >&2; rm -f "$COND_TMP"; exit 1; }
elif (( ! C16_WT )) && [[ -n "$C16_DIRTY" ]]; then echo "simulate: 하네스 dirty($C16_DIRTY) — --worktree로" >&2; rm -f "$COND_TMP"; exit 2; fi
mkdir -p "$ROOT"
if [[ -s "$ROOT/order.txt" ]]; then
  [[ "$(cat "$ROOT/order.txt")" == "$ORDER" ]] || { echo "이어서: order.txt가 다시 계산한 순서와 다르다" >&2; exit 2; }
  [[ "$(jq -r .sha "$ROOT/plan.json")" == "$SHA" ]] || { echo "이어서: 계획 SHA가 다르다" >&2; exit 2; }
  [[ "$(jq -r .harness_hash "$ROOT/plan.json")" == "$C16_HHASH" ]] || { echo "이어서: 동결본 해시가 계획과 다르다" >&2; exit 2; }
  (( SIMU )) || cmp -s "$IMAGES" "$ROOT/images.json" || { echo "이어서: images.json이 계획과 다르다" >&2; exit 2; }
else
  echo "$ORDER" > "$ROOT/order.txt"
  (( SIMU )) || { cp "$IMAGES" "$ROOT/images.json"; cp "$START" "$ROOT/start-conditions.json"; }
  jq -n --arg sha "$SHA" --arg k6 "$K6_BIN" --arg at "$(date -Is)" --arg hh "$C16_HHASH" --arg hh15 "$C16_HHASH15" --argjson dry "$DRYJ" --argjson sim "$SIMU" \
    '{sha: $sha, k6_bin: $k6, created: $at, reps: $dry.n, harness_hash: $hh, harness_hash15: $hh15, fixed: $dry.fixed, est_hours: $dry.est_hours, simulate: ($sim == 1)}' > "$ROOT/plan.json"
fi
[[ -s "$COND_TMP" ]] && cp "$COND_TMP" "$ROOT/start-conditions.log"; rm -f "$COND_TMP"
LOG="$ROOT/CAMPAIGN.log"
RUN_MODE=(); (( SIMU )) && RUN_MODE=(--simulate) && (( C16_WT )) && RUN_MODE+=(--worktree)
PROBE_ARG=(); [[ -n "$PROBE_K6_BIN" ]] && PROBE_ARG=(--probe-k6-bin "$PROBE_K6_BIN")
# 환경 = env-stall만 · pc-link-down · pc-mem-low가 든 무효(ADR-015 campaign.sh kind_of와 같은 규칙) · status 없는 시도는 하네스
kind_of() { case "$1" in invalid:env-stall|invalid:pc-link-down|invalid:pc-link-down,*|invalid:*,pc-link-down|invalid:*,pc-link-down,*|invalid:pc-mem-low|invalid:pc-mem-low,*|invalid:*,pc-mem-low|invalid:*,pc-mem-low,*) echo env ;; invalid:*) echo harness ;; "") echo harness ;; *) echo valid ;; esac; }
attempts_of() { local d; d="$ROOT/$2/$(cdir "$2" "$3")/rep$1"; local n; for n in $(ls -d "$d".attempt* 2>/dev/null | sed 's/.*\.attempt//' | sort -n); do cat "$d.attempt$n/status" 2>/dev/null || echo; done; [[ -d "$d" ]] && { cat "$d/status" 2>/dev/null || echo; }; }
run_one() {   # 인자: rep cell config attempt
  echo "$(date -Is) RUN rep$1 $2 $3 attempt$4" >> "$LOG"
  if [[ "$2" == race ]]; then   # ADR-015 run.sh 그대로(동결본 안의 사본 · 해시 = 동결 ADR-015 해시)
    CAMPAIGN15=1 CAMPAIGN15_WORKTREE="$C16_WT" HARNESS_HASH15="$C16_HHASH15" REPO_ROOT="$REPO" ADR15_DIR="$REPO/k6/ADR-015" SIM_PLAN="$SIM_PLAN_F" \
      bash "$SRC/../../ADR-015/scripts/run.sh" --sha "$SHA" --images "${IMAGES:-/dev/null}" --mode "$(cdir race "$3")" --cell race --rep "$1" --id "$CID" --attempt "$4" \
      --k6-bin "$K6_BIN" --results-root "$(dirname "$ROOT")" "${RUN_MODE[@]}" "${PROBE_ARG[@]}" < /dev/null >> "$ROOT/runner.log" 2>&1
  else
    CAMPAIGN16=1 CAMPAIGN16_WORKTREE="$C16_WT" HARNESS_HASH16="$C16_HHASH" REPO_ROOT="$REPO" ADR16_DIR="$ADR16" SIM_PLAN="$SIM_PLAN_F" \
      bash "$SRC/run16.sh" --sha "$SHA" --images "${IMAGES:-/dev/null}" --cell "$2" --config "$3" --rep "$1" --id "$CID" --attempt "$4" \
      --k6-bin "$K6_BIN" --results-root "$(dirname "$ROOT")" "${RUN_MODE[@]}" "${PROBE_ARG[@]}" < /dev/null >> "$ROOT/runner.log" 2>&1
  fi
  echo "$(date -Is) END rep$1 $2 $3 attempt$4 $(st_of "$1" "$2" "$3" || true)" >> "$LOG"
  # 요약기 예외(리뷰 L1-1): 재측정 대상 하네스 무효로 세지 않는다 — 실패 자료가 요약기 결함에 가려졌을 수 있다 → 멈추고 사용자 보고(회차 폴더 rep-summary.json error · traceback)
  if [[ "$(st_of "$1" "$2" "$3")" == *summarize-failed* ]]; then
    echo "멈춤: rep$1 $2 $3 요약기 예외(summarize-failed) — 사용자 보고 대상(재측정하지 않는다)" | tee -a "$LOG" >&2; exit 8
  fi
}
streak=0
while read -r seq k c g; do
  if [[ -z "$(st_of "$k" "$c" "$g")" ]]; then
    att=$(( $(attempts_of "$k" "$c" "$g" | wc -l) + 1 ))
    run_one "$k" "$c" "$g" "$att"
    if [[ "$(kind_of "$(st_of "$k" "$c" "$g")")" != valid ]]; then streak=$((streak + 1)); else streak=0; fi
    (( streak >= 5 )) && { echo "멈춤: 무효 연속 5회 — 환경 확인" | tee -a "$LOG" >&2; exit 3; }
  fi
done <<< "$ORDER"
for round in 1 2 3 4; do   # 재측정 — invalid만 · 자리마다 환경 2번 · 하네스 2번(따로) · 원래 순서대로
  ran=0
  while read -r seq k c g; do
    st=$(st_of "$k" "$c" "$g"); kd=$(kind_of "$st")
    [[ "$kd" == valid ]] && continue
    hist=$(attempts_of "$k" "$c" "$g"); n=$(wc -l <<< "$hist")
    used=$(head -n $(( n - 1 )) <<< "$hist" | while IFS= read -r s; do kind_of "$s"; done | grep -cx "$kd"); (( n > 1 )) || used=0
    (( used < 2 )) || continue
    run_one "$k" "$c" "$g" $(( n + 1 )); ran=1
  done <<< "$ORDER"
  (( ran )) || break
done
missing=$(missing_of <<< "$ORDER")
if [[ -n "$missing" ]]; then echo "멈춤: 실행되지 않은 자리 $(wc -l <<< "$missing")개 — $(tr '\n' ' ' <<< "$missing")" | tee -a "$LOG" >&2; exit 6; fi
bad=$(for mf in "$ROOT"/*/*/rep*/meta.json; do [[ -f "$mf" ]] || continue
        want="$C16_HHASH"; [[ "$mf" == "$ROOT"/race/* ]] && want="$C16_HHASH15"
        [[ "$(jq -r '.harness_hash // ""' "$mf")" == "$want" ]] || echo "$mf"; done)
if [[ -n "$bad" ]]; then echo "멈춤: 동결본과 다른 하네스 해시의 회차 — $bad" | tee -a "$LOG" >&2; exit 5; fi
if (( SIMU )); then echo "simulate: $(grep -c . <<< "$ORDER")자리 모두 status · 해시 일치 — 끝 집계 생략($ROOT)" | tee -a "$LOG"; exit 0; fi
python3 -I "$SRC/summarize16.py" "$ROOT" >> "$LOG" 2>&1 || { echo "끝 집계 실패 — $LOG" >&2; exit 7; }
jq -r '.decision | "결론: \(.status) · 고른 후보 \(.pick // "없음")"' "$ROOT/result.json" | tee -a "$LOG"
echo "끝 — $ROOT/RESULT.md"
