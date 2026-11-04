#!/usr/bin/env bash
# ADR-015 측정 캠페인: 모드 5 × 셀 2(race · cost) × 3회 = 30회(회차마다 run.sh 한 번) + #5 V12 단계(v12-build.sh — 루프 뒤 한 번). ADR-014 campaign.sh의 얇은 변형 —
#   같은 동결(--sha git archive · 해시) · 같은 이어서 실행 대조 · 같은 재측정 상한(자리마다 환경 2번 · 하네스 2번 따로) · 루프 안 run.sh `< /dev/null`(ADR-013 §6.1) ·
#   끝 집계 전 계획 대 실행 대조(exit 6). 바뀐 것: 계획 = plan15.py --order · 시작 조건 ⓪~⑦(초안 측정-9) · 조기 멈춤 = 회차 1 race current 경합 증거 0(exit 4) ·
#   공통 난수 대조 없음(세션 속성은 씨앗에서 결정 · 같은 세션 번호를 main · rehold가 공유 — dropped-iterations가 무효로 막는다).
#
#   scripts/campaign.sh --sha <SHA> --images <images.json> --start-conditions <json> [--knobs <json>] [--id <id>] [--k6-bin k6] [--probe-k6-bin <경로>] [--skip-v12]
#   scripts/campaign.sh --sha <SHA> [--knobs <json>] [--start-conditions <json>] --dry-run
#   scripts/campaign.sh --sha <SHA> --simulate --results-root <폴더> [--sim-plan <파일>] [--worktree]
#   scripts/campaign.sh --check <캠페인 결과 폴더>
# 시작 조건(--start-conditions JSON — 각 log 1행): ⓪ 하네스 동결 · 이미지 고정 ① .164 스모크(다섯 모드 × race 1회 + current · conditional × cost 1회) smoke.done
#   ② 캠페인 스모크(두 자리 이상) campaign_smoke.done ③ dry-run(plan15.py --dry-run exit 0) ④ k6 PC 점검(pc_check.done + pccheck14.sh)
#   ⑤ [구현 검증] 측정 몫(#12 앱−DB 시계 차 · #15 pgss 결정 · 흐름별 VU 최대) impl_verify.done ⑥ knobs(plan.json에 고정) ⑦ 측정 SHA에서 전체 테스트 green tests_green.done · sha
set -uo pipefail
if [[ "${1:-}" == --check ]]; then CHECK_ONLY="${2:?--check <결과 폴더>}"; fi
if [[ -z "${CHECK_ONLY:-}" && "${CAMPAIGN15_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"
  sha_arg=HEAD prev="" wt=0 testmode=0
  for a in "$@"; do [[ "$prev" == --sha ]] && sha_arg="$a"; [[ "$a" == --worktree ]] && wt=1; [[ "$a" == --dry-run || "$a" == --simulate ]] && testmode=1; prev="$a"; done
  (( wt && ! testmode )) && { echo "--worktree는 --dry-run · --simulate 전용" >&2; exit 2; }
  sha=$(git -C "$repo" rev-parse --short=8 "${sha_arg}^{commit}" 2>/dev/null) || { echo "SHA 해석 실패: $sha_arg" >&2; exit 2; }
  mapfile -t hp < <(bash "$src/hhash.sh" --paths)
  dirty=""
  git -C "$repo" diff --quiet "$sha" -- "${hp[@]}" ':(exclude)k6/ADR-015/results' 2>/dev/null || dirty="작업트리 변경"
  [[ -z "$(git -C "$repo" ls-files --others --exclude-standard -- "${hp[@]}" ':(exclude)k6/ADR-015/results')" ]] || dirty="${dirty:+$dirty · }미추적 파일"
  frozen="$(mktemp -d)"
  if (( wt )); then
    tar -C "$repo" --exclude=k6/ADR-015/results --exclude=__pycache__ -cf - "${hp[@]}" | tar -x -C "$frozen" || { echo "작업트리 사본 실패" >&2; rm -rf "${frozen:?}"; exit 2; }
    dirty="worktree(시험 전용)${dirty:+ · $dirty}"
  else
    git -C "$repo" archive "$sha" -- "${hp[@]}" ':(exclude)k6/ADR-015/results' | tar -x -C "$frozen" \
      && [[ -f "$frozen/k6/ADR-015/scripts/campaign.sh" ]] || { echo "git archive $sha 동결 실패(그 SHA에 하네스가 없다?)" >&2; rm -rf "${frozen:?}"; exit 2; }
  fi
  hh=$(bash "$frozen/k6/ADR-015/scripts/hhash.sh" "$frozen") || { echo "동결본 해시 실패" >&2; rm -rf "${frozen:?}"; exit 2; }
  export CAMPAIGN15_FROZEN=1 C15_REPO="$repo" C15_SHA="$sha" C15_FROZEN="$frozen" C15_HHASH="$hh" C15_DIRTY="$dirty" C15_WT="$wt"
  exec bash "$frozen/k6/ADR-015/scripts/campaign.sh" "$@"
fi
SRC="$(cd "$(dirname "$0")" && pwd)"
st_of() { cat "$ROOT/$2/$3/rep$1/status" 2>/dev/null; }   # 인자: rep cell mode
missing_of() { local seq k c m; while read -r seq k c m; do [[ -n "$seq" ]] || continue; [[ -n "$(st_of "$k" "$c" "$m")" ]] || echo "#$seq rep$k $c $m"; done; }
if [[ -n "${CHECK_ONLY:-}" ]]; then
  ROOT="$(cd "$CHECK_ONLY" && pwd)" || exit 2
  [[ -s "$ROOT/order.txt" ]] || { echo "order.txt 없음 — $ROOT" >&2; exit 2; }
  knobs_arg=(); [[ -s "$ROOT/knobs.json" ]] && knobs_arg=(--knobs "$ROOT/knobs.json")
  want="$(python3 -I "$SRC/plan15.py" --order "${knobs_arg[@]}")" || exit 2
  [[ "$(cat "$ROOT/order.txt")" == "$want" ]] || { echo "order.txt가 plan15.py --order와 다르다" >&2; exit 2; }
  missing=$(missing_of < "$ROOT/order.txt")
  if [[ -n "$missing" ]]; then echo "계획 대 실행: 실행되지 않은 자리 $(wc -l <<< "$missing")개 — $(tr '\n' ' ' <<< "$missing")" >&2; exit 6; fi
  echo "계획 대 실행: $(grep -c . "$ROOT/order.txt")자리 모두 status 있음"; exit 0
fi
trap '[[ "$C15_FROZEN" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "${C15_FROZEN:?}"' EXIT
REPO="$C15_REPO"; ADR15="$REPO/k6/ADR-015"
SHA="" IMAGES="" START="" KNOBS="" CID="" K6_BIN=k6 PROBE_K6_BIN="" DRY=0 SIMU=0 RESULTS_ROOT="" SIM_PLAN_F="" SKIP_V12=0
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;; --images) IMAGES="$2"; shift 2 ;; --start-conditions) START="$2"; shift 2 ;; --knobs) KNOBS="$2"; shift 2 ;;
    --id) CID="$2"; shift 2 ;; --k6-bin) K6_BIN="$2"; shift 2 ;; --probe-k6-bin) PROBE_K6_BIN="$2"; shift 2 ;; --results-root) RESULTS_ROOT="$2"; shift 2 ;;
    --sim-plan) SIM_PLAN_F="$2"; shift 2 ;; --dry-run) DRY=1; shift ;; --simulate) SIMU=1; shift ;; --worktree) shift ;; --skip-v12) SKIP_V12=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
SHA=$(git -C "$REPO" rev-parse --short=8 "${SHA:-HEAD}^{commit}") || exit 2
[[ "$SHA" == "$C15_SHA" ]] || { echo "동결 SHA $C15_SHA ≠ --sha $SHA" >&2; exit 2; }
CID="${CID:-$(date +%Y%m%d)-adr015-$SHA}"
ROOT="${RESULTS_ROOT:-$ADR15/results}/$CID"
KNOBS_ARG=(); [[ -n "$KNOBS" ]] && KNOBS_ARG=(--knobs "$KNOBS")
ORDER="$(python3 -I "$SRC/plan15.py" --order "${KNOBS_ARG[@]}")" || { echo "계획(plan15.py --order) 실패" >&2; exit 2; }
DRYJ="$(python3 -I "$SRC/plan15.py" --dry-run --json "${KNOBS_ARG[@]}")"; DRY_RC=$?
jflag() { [[ -s "$START" ]] && [[ "$(jq -r "$1 // false" "$START" 2>/dev/null)" == true ]]; }
cond_lines() {
  local ok=1
  if [[ -z "$C15_DIRTY" ]]; then echo "⓪ 하네스 = $SHA(무변경 · 미추적 없음) · 동결본 해시 ${C15_HHASH:0:12} → 통과"; else echo "⓪ 하네스 = $SHA 아님($C15_DIRTY) → 막음"; ok=0; fi
  if [[ -s "$IMAGES" ]] && jq -e --arg s "$SHA" '.app.sha == $s and ([.app.id, (.aux[])] | all(test("^sha256:")))' "$IMAGES" > /dev/null 2>&1; then echo "⓪ 이미지 고정 → 통과"
  else echo "⓪ 이미지 고정 — --images 없음 · SHA 다름 → 막음"; ok=0; fi
  jflag .smoke.done && echo "① .164 스모크 → 통과" || { echo "① .164 스모크 — smoke.done 아님 → 막음"; ok=0; }
  jflag .campaign_smoke.done && echo "② 캠페인 스모크 → 통과" || { echo "② 캠페인 스모크 — campaign_smoke.done 아님 → 막음"; ok=0; }
  if (( DRY_RC == 0 )); then echo "③ dry-run $(jq -r .n <<< "$DRYJ")자리 · 예상 $(jq -r .est_hours <<< "$DRYJ")시간 → 통과"; else echo "③ dry-run 문제 $(jq -c .problems <<< "$DRYJ" 2>/dev/null) → 막음"; ok=0; fi
  local pcj; pcj="$(bash "$C15_FROZEN/k6/ADR-014/scripts/pccheck14.sh" 2>/dev/null)"; local pcrc=$?
  if ! jflag .pc_check.done; then echo "④ k6 PC 점검 — pc_check.done 아님 → 막음"; ok=0
  elif (( pcrc == 0 )) && [[ "$(jq -r .ok <<< "$pcj" 2>/dev/null)" == true ]]; then echo "④ k6 PC 점검 → 통과"
  else echo "④ k6 PC 점검 — $(jq -r '.why | join(" · ")' <<< "$pcj" 2>/dev/null || echo "pccheck14 출력 못 읽음") → 막음"; ok=0; fi
  jflag .impl_verify.done && echo "⑤ [구현 검증] 측정 몫(#12 · #15 · VU 최대) → 통과" || { echo "⑤ [구현 검증] — impl_verify.done 아님 → 막음"; ok=0; }
  echo "⑥ knobs $( [[ -n "$KNOBS" ]] && jq -c . "$KNOBS" 2>/dev/null || echo '{} — 기본값') → 기록"
  if jflag .tests_green.done && [[ "$(jq -r '.tests_green.sha // ""' "$START" | cut -c1-8)" == "$SHA" ]]; then echo "⑦ 전체 테스트 green @ $SHA → 통과"
  else echo "⑦ 전체 테스트 green — tests_green.done · sha = $SHA 아님 → 막음"; ok=0; fi
  return $(( ! ok ))
}
if (( DRY )); then
  echo "# ADR-015 캠페인 계획 — id $CID · 하네스 $SHA · 결과 $ROOT"
  python3 -I "$SRC/plan15.py" --dry-run "${KNOBS_ARG[@]}"
  echo "# 순서"; echo "$ORDER" | head -10; echo "…"
  echo "# 시작 조건"; cond_lines; echo "시작 조건 판정: $( (( $? == 0 )) && echo 시작 가능 || echo 시작하지 않음)"
  exit 0
fi
COND_TMP="$(mktemp)"
if (( ! SIMU )); then
  cond_lines | tee /dev/stderr > "$COND_TMP"; (( ${PIPESTATUS[0]} == 0 )) || { echo "시작 조건 미충족 — 시작하지 않는다" >&2; rm -f "$COND_TMP"; exit 1; }
elif (( ! C15_WT )) && [[ -n "$C15_DIRTY" ]]; then echo "simulate: 하네스 dirty($C15_DIRTY) — --worktree로" >&2; rm -f "$COND_TMP"; exit 2; fi
mkdir -p "$ROOT"
if [[ -s "$ROOT/order.txt" ]]; then
  [[ "$(cat "$ROOT/order.txt")" == "$ORDER" ]] || { echo "이어서: order.txt가 다시 계산한 순서와 다르다" >&2; exit 2; }
  [[ "$(jq -r .sha "$ROOT/plan.json")" == "$SHA" ]] || { echo "이어서: 계획 SHA가 다르다" >&2; exit 2; }
  [[ "$(jq -r .harness_hash "$ROOT/plan.json")" == "$C15_HHASH" ]] || { echo "이어서: 동결본 해시가 계획과 다르다" >&2; exit 2; }
  [[ "$(jq -c '.knobs' "$ROOT/plan.json")" == "$( [[ -n "$KNOBS" ]] && jq -c . "$KNOBS" || echo '{}')" ]] || { echo "이어서: knobs가 계획과 다르다" >&2; exit 2; }
  (( SIMU )) || cmp -s "$IMAGES" "$ROOT/images.json" || { echo "이어서: images.json이 계획과 다르다" >&2; exit 2; }
else
  echo "$ORDER" > "$ROOT/order.txt"
  [[ -n "$KNOBS" ]] && jq . "$KNOBS" > "$ROOT/knobs.json"
  (( SIMU )) || { cp "$IMAGES" "$ROOT/images.json"; cp "$START" "$ROOT/start-conditions.json"; }
  jq -n --arg sha "$SHA" --arg k6 "$K6_BIN" --arg at "$(date -Is)" --arg hh "$C15_HHASH" --argjson knobs "$( [[ -n "$KNOBS" ]] && jq -c . "$KNOBS" || echo '{}')" \
        --argjson dry "$DRYJ" --argjson sim "$SIMU" \
    '{sha: $sha, k6_bin: $k6, created: $at, reps: $dry.n, harness_hash: $hh, knobs: $knobs, est_hours: $dry.est_hours, simulate: ($sim == 1)}' > "$ROOT/plan.json"
fi
[[ -s "$COND_TMP" ]] && cp "$COND_TMP" "$ROOT/start-conditions.log"; rm -f "$COND_TMP"
LOG="$ROOT/CAMPAIGN.log"
RUN_KNOBS=(); [[ -s "$ROOT/knobs.json" ]] && RUN_KNOBS=(--knobs "$ROOT/knobs.json")
RUN_MODE=(); (( SIMU )) && RUN_MODE=(--simulate) && (( C15_WT )) && RUN_MODE+=(--worktree)
PROBE_ARG=(); [[ -n "$PROBE_K6_BIN" ]] && PROBE_ARG=(--probe-k6-bin "$PROBE_K6_BIN")
# 환경 = env-stall만 · pc-link-down · pc-mem-low가 든 무효(ADR-014 campaign.sh kind_of와 같은 규칙) · status 없는 시도는 하네스
kind_of() { case "$1" in invalid:env-stall|invalid:pc-link-down|invalid:pc-link-down,*|invalid:*,pc-link-down|invalid:*,pc-link-down,*|invalid:pc-mem-low|invalid:pc-mem-low,*|invalid:*,pc-mem-low|invalid:*,pc-mem-low,*) echo env ;; invalid:*) echo harness ;; "") echo harness ;; *) echo valid ;; esac; }
attempts_of() { local d="$ROOT/$2/$3/rep$1" n; for n in $(ls -d "$d".attempt* 2>/dev/null | sed 's/.*\.attempt//' | sort -n); do cat "$d.attempt$n/status" 2>/dev/null || echo; done; [[ -d "$d" ]] && { cat "$d/status" 2>/dev/null || echo; }; }
run_one() {   # 인자: rep cell mode attempt
  echo "$(date -Is) RUN rep$1 $2 $3 attempt$4" >> "$LOG"
  CAMPAIGN15=1 CAMPAIGN15_WORKTREE="$C15_WT" HARNESS_HASH15="$C15_HHASH" REPO_ROOT="$REPO" ADR15_DIR="$ADR15" SIM_PLAN="$SIM_PLAN_F" \
    bash "$SRC/run.sh" --sha "$SHA" --images "${IMAGES:-/dev/null}" --mode "$3" --cell "$2" --rep "$1" --id "$CID" --attempt "$4" \
    --k6-bin "$K6_BIN" --results-root "$(dirname "$ROOT")" "${RUN_KNOBS[@]}" "${RUN_MODE[@]}" "${PROBE_ARG[@]}" < /dev/null >> "$ROOT/runner.log" 2>&1
  echo "$(date -Is) END rep$1 $2 $3 attempt$4 $(st_of "$1" "$2" "$3" || true)" >> "$LOG"
}
streak=0 early_done=0
while read -r seq k c m; do
  if [[ -z "$(st_of "$k" "$c" "$m")" ]]; then
    att=$(( $(attempts_of "$k" "$c" "$m" | wc -l) + 1 ))
    run_one "$k" "$c" "$m" "$att"
    if [[ "$(kind_of "$(st_of "$k" "$c" "$m")")" != valid ]]; then streak=$((streak + 1)); else streak=0; fi
    (( streak >= 5 )) && { echo "멈춤: 무효 연속 5회 — 환경 확인" | tee -a "$LOG" >&2; exit 3; }
  fi
  if (( k == 1 && seq == 5 && ! early_done && ( ! SIMU || ${CAMPAIGN15_SIM_EARLY:-0} ) )); then   # 회차 1의 race 묶음 끝 — current 양성 대조(경합 증거 ≥ 1) 조기 확인
    early_done=1
    # 리뷰 M15-11: exit 4는 'ok인데 증거 0'일 때만(summarize --early-check rc 1). 무효 · 없음(rc 2)은 멈추지 않고 재측정 경로로 넘긴다(끝 G4가 판정한다)
    python3 -I "$SRC/summarize.py" --early-check "$ROOT" >> "$LOG" 2>&1; erc=$?
    case "$erc" in
      0) ;;
      1) echo "멈춤: 회차 1 race current 경합 증거 0 — 부하가 경합을 만들지 못했다(사용자 보고)" | tee -a "$LOG" >&2; exit 4 ;;
      *) echo "$(date -Is) 조기 확인 보류(rc=$erc — race/current/rep1 무효 · 없음 · 증거 없음): 재측정 경로로 넘긴다(끝 G4가 판정)" >> "$LOG" ;;
    esac
  fi
done <<< "$ORDER"
for round in 1 2 3 4; do   # 재측정 — invalid만 · 자리마다 환경 2번 · 하네스 2번(따로) · 원래 순서대로
  ran=0
  while read -r seq k c m; do
    st=$(st_of "$k" "$c" "$m"); kd=$(kind_of "$st")
    [[ "$kd" == valid ]] && continue
    hist=$(attempts_of "$k" "$c" "$m"); n=$(wc -l <<< "$hist")
    used=$(head -n $(( n - 1 )) <<< "$hist" | while IFS= read -r s; do kind_of "$s"; done | grep -cx "$kd"); (( n > 1 )) || used=0
    (( used < 2 )) || continue
    run_one "$k" "$c" "$m" $(( n + 1 )); ran=1
  done <<< "$ORDER"
  (( ran )) || break
done
missing=$(missing_of <<< "$ORDER")
if [[ -n "$missing" ]]; then echo "멈춤: 실행되지 않은 자리 $(wc -l <<< "$missing")개 — $(tr '\n' ' ' <<< "$missing")" | tee -a "$LOG" >&2; exit 6; fi
bad=$(for mf in "$ROOT"/*/*/rep*/meta.json; do [[ -f "$mf" ]] || continue; [[ "$(jq -r '.harness_hash // ""' "$mf")" == "$C15_HHASH" ]] || echo "$mf"; done)
if [[ -n "$bad" ]]; then echo "멈춤: 동결본과 다른 하네스 해시의 회차 — $bad" | tee -a "$LOG" >&2; exit 5; fi
if (( SIMU )); then echo "simulate: $(grep -c . <<< "$ORDER")자리 모두 status · 해시 일치 — #5 · 끝 집계 생략($ROOT)" | tee -a "$LOG"; exit 0; fi
if (( ! SKIP_V12 )); then   # #5 V12 생성 시간(초안 측정-2) — 부하 회차 뒤 한 번(회차 사이에 섞지 않는다)
  echo "$(date -Is) V12 단계 시작" >> "$LOG"
  REPO_ROOT="$REPO" bash "$SRC/v12-build.sh" --sha "$SHA" --images "$IMAGES" --out "$ROOT/v12" "${RUN_KNOBS[@]}" < /dev/null >> "$ROOT/runner.log" 2>&1 || echo "$(date -Is) V12 단계 실패(rc=$?) — 판정 불가로 남는다" >> "$LOG"
fi
python3 -I "$SRC/summarize.py" "$ROOT" >> "$LOG" 2>&1 || { echo "끝 집계 실패 — $LOG" >&2; exit 7; }
jq -r '.decision | "결론: \(.verdict) · 새 기본값 \(.new_default // "없음")"' "$ROOT/result.json" | tee -a "$LOG"
echo "끝 — $ROOT/RESULT.md"
