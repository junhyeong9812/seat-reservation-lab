#!/usr/bin/env bash
# ADR-018 측정 캠페인: 본 셀 방식 4(B0 · B1 · split · one-shot) × 겹침 3(low · mid · high) × 3회 = 36회차 + 경계 셀 · 동시 경합 셀 split · one-shot × 3회씩 12회차(합 48 —
#   측정 명세 Q9 · Q10 · Q17) + 무효 재측정. ADR-017 campaign17.sh의 변형 — 같은 동결(--sha git archive · 해시) · 같은 이어서 실행 대조(--id만으로 재개) · 같은 재측정 상한
#   (자리마다 환경 2번 · 하네스 2번 따로) · 연속 무효 5회 정지 · 루프 안 실행기 `< /dev/null` · 끝 집계 전 계획 대 실행 대조(exit 6). 바뀐 것: 계획 = plan18.py --order
#   (열 = 순번 회차 셀 방식) · 자리 = run18.sh(회차마다 끝에 compose down -v · 끝에 run18.sh --down으로 한 번 더) · 끝 집계 = summarize18.py --campaign(방식별 근거 표 ·
#   정합 판정 — 기본값 결정은 사람이 한다) · 환경 판별에 k6-conn-error(명세 Q11 — 연결 오류 회차는 재측정)를 더한다.
#   (campaign16의 `tee /dev/stderr`는 stderr가 파일이면 O_TRUNC로 그 파일을 비웠다 — `>&2`로 바꿈).
#
#   scripts/campaign18.sh --sha <SHA> --images <images.json> --start-conditions <json> [--id <id>] [--k6-bin k6]
#   scripts/campaign18.sh --sha <SHA> [--start-conditions <json>] --dry-run
#   scripts/campaign18.sh --sha <SHA> --simulate --results-root <폴더> [--sim-plan <파일>] [--worktree] [--id <id>]
#   scripts/campaign18.sh --check <캠페인 결과 폴더>
# 시작 조건(--start-conditions JSON): ⓪ 하네스 동결 · 이미지 고정(앱 · mock-pg) ① .164 스모크 smoke.done ② 캠페인 스모크 campaign_smoke.done ③ dry-run(plan18 exit 0)
#   ④ k6 PC 점검(pc_check.done + pccheck14.sh) ⑤ 측정 전 고정(plan18 FIXED = True) ⑥ 측정 SHA 전체 테스트 green tests_green.done · sha
# exit: 1 시작 조건 · 2 인자 · 동결 · 3 연속 무효 5회 · 4 재측정을 다 쓴 뒤에도 무효인 자리가 남음(끝 집계는 한다 · 리뷰 H1-4) · 5 해시 · 6 계획 대 실행 ·
#   7 끝 집계 · 8 요약기 예외(사용자 보고). --check: 0 모든 자리 status ok · 4 무효 자리 있음 · 6 status 없는 자리 있음
# sim-plan 줄: "<회차>:<후보>:<셀>:<시도>=<status>"
set -uo pipefail
if [[ "${1:-}" == --check ]]; then CHECK_ONLY="${2:?--check <결과 폴더>}"; fi
if [[ -z "${CHECK_ONLY:-}" && "${CAMPAIGN18_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"
  sha_arg=HEAD prev="" wt=0 testmode=0
  for a in "$@"; do [[ "$prev" == --sha ]] && sha_arg="$a"; [[ "$a" == --worktree ]] && wt=1; [[ "$a" == --dry-run || "$a" == --simulate ]] && testmode=1; prev="$a"; done
  (( wt && ! testmode )) && { echo "--worktree는 --dry-run · --simulate 전용" >&2; exit 2; }
  if (( ! testmode )) && ! python3 -I "$src/plan18.py" --fixed > /dev/null; then
    echo "plan18.py FIXED = False — 측정 전 고정 커밋 전이라 실제 캠페인을 거부한다(--dry-run · --simulate만)" >&2; exit 2
  fi
  sha=$(git -C "$repo" rev-parse --short=8 "${sha_arg}^{commit}" 2>/dev/null) || { echo "SHA 해석 실패: $sha_arg" >&2; exit 2; }
  mapfile -t hp < <(bash "$src/hhash18.sh" --paths)
  EX=(':(exclude)k6/ADR-018/results' ':(exclude)k6/ADR-016/results' ':(exclude)k6/ADR-015/results')
  dirty=""
  git -C "$repo" diff --quiet "$sha" -- "${hp[@]}" "${EX[@]}" 2>/dev/null || dirty="작업트리 변경"
  [[ -z "$(git -C "$repo" ls-files --others --exclude-standard -- "${hp[@]}" "${EX[@]}")" ]] || dirty="${dirty:+$dirty · }미추적 파일"
  frozen="$(mktemp -d)"
  if (( wt )); then
    tar -C "$repo" --exclude=k6/ADR-018/results --exclude=k6/ADR-016/results --exclude=k6/ADR-015/results --exclude=__pycache__ -cf - "${hp[@]}" | tar -x -C "$frozen" \
      || { echo "작업트리 사본 실패" >&2; rm -rf "${frozen:?}"; exit 2; }
    dirty="worktree(시험 전용)${dirty:+ · $dirty}"
  else
    git -C "$repo" archive "$sha" -- "${hp[@]}" "${EX[@]}" | tar -x -C "$frozen" \
      && [[ -f "$frozen/k6/ADR-018/scripts/campaign18.sh" ]] || { echo "git archive $sha 동결 실패(그 SHA에 하네스가 없다?)" >&2; rm -rf "${frozen:?}"; exit 2; }
  fi
  hh=$(bash "$frozen/k6/ADR-018/scripts/hhash18.sh" "$frozen") || { echo "동결본 해시 실패" >&2; rm -rf "${frozen:?}"; exit 2; }
  export CAMPAIGN18_FROZEN=1 C18_REPO="$repo" C18_SHA="$sha" C18_FROZEN="$frozen" C18_HHASH="$hh" C18_DIRTY="$dirty" C18_WT="$wt"
  exec bash "$frozen/k6/ADR-018/scripts/campaign18.sh" "$@"
fi
SRC="$(cd "$(dirname "$0")" && pwd)"
st_of() { cat "$ROOT/$2/$3/rep$1/status" 2>/dev/null; }   # 인자: rep method cell
missing_of() { local seq k c g; while read -r seq k c g; do [[ -n "$seq" ]] || continue; [[ -n "$(st_of "$k" "$g" "$c")" ]] || echo "#$seq rep$k $g $c"; done; }
invalid_of() { local seq k c g st; while read -r seq k c g; do [[ -n "$seq" ]] || continue; st="$(st_of "$k" "$g" "$c")"; [[ -z "$st" || "$st" == ok ]] || echo "#$seq rep$k $g $c $st"; done; }
if [[ -n "${CHECK_ONLY:-}" ]]; then
  ROOT="$(cd "$CHECK_ONLY" && pwd)" || exit 2
  [[ -s "$ROOT/order.txt" ]] || { echo "order.txt 없음 — $ROOT" >&2; exit 2; }
  want="$(python3 -I "$SRC/plan18.py" --order)" || exit 2
  [[ "$(cat "$ROOT/order.txt")" == "$want" ]] || { echo "order.txt가 plan18.py --order와 다르다" >&2; exit 2; }
  missing=$(missing_of < "$ROOT/order.txt")
  if [[ -n "$missing" ]]; then echo "계획 대 실행: 실행되지 않은 자리 $(wc -l <<< "$missing")개 — $(tr '\n' ' ' <<< "$missing")" >&2; exit 6; fi
  bad=$(invalid_of < "$ROOT/order.txt")
  if [[ -n "$bad" ]]; then echo "유효성: 무효로 남은 자리 $(wc -l <<< "$bad")개 — $(tr '\n' ' ' <<< "$bad")" >&2; exit 4; fi
  echo "계획 대 실행: $(grep -c . "$ROOT/order.txt")자리 모두 status ok"; exit 0
fi
trap '[[ "$C18_FROZEN" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "${C18_FROZEN:?}"' EXIT
REPO="$C18_REPO"; ADR18="$REPO/k6/ADR-018"
SHA="" IMAGES="" START="" CID="" K6_BIN=k6 DRY=0 SIMU=0 RESULTS_ROOT="" SIM_PLAN_F=""
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;; --images) IMAGES="$2"; shift 2 ;; --start-conditions) START="$2"; shift 2 ;;
    --id) CID="$2"; shift 2 ;; --k6-bin) K6_BIN="$2"; shift 2 ;; --results-root) RESULTS_ROOT="$2"; shift 2 ;;
    --sim-plan) SIM_PLAN_F="$2"; shift 2 ;; --dry-run) DRY=1; shift ;; --simulate) SIMU=1; shift ;; --worktree) shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
SHA=$(git -C "$REPO" rev-parse --short=8 "${SHA:-HEAD}^{commit}") || exit 2
[[ "$SHA" == "$C18_SHA" ]] || { echo "동결 SHA $C18_SHA ≠ --sha $SHA" >&2; exit 2; }
FIXED_RC=0; python3 -I "$SRC/plan18.py" --fixed > /dev/null || FIXED_RC=1
if (( ! DRY && ! SIMU && FIXED_RC )); then echo "plan18.py FIXED = False — 측정 전 고정 커밋 전이라 실제 캠페인을 거부한다(--dry-run · --simulate만)" >&2; exit 2; fi
ID_GIVEN=0; [[ -n "$CID" ]] && ID_GIVEN=1
if (( ! DRY && ! SIMU )) && [[ -z "$CID" ]]; then CID="$(date +%Y%m%d)-adr018-$SHA"; fi
CID="${CID:-sim-$SHA}"
ROOT="${RESULTS_ROOT:-$ADR18/results}/$CID"
# 재개는 --id로만(명세 금지영역 '캠페인 재개 시 --id 없이 실행'): 자동 생성한 ID의 결과 폴더가 이미 있으면 이어 쓰지 않는다
if (( ! DRY && ! ID_GIVEN )) && [[ -e "$ROOT" ]]; then echo "자동 ID $CID의 결과 폴더가 이미 있다($ROOT) — 재개는 --id $CID로" >&2; exit 2; fi
ORDER="$(python3 -I "$SRC/plan18.py" --order)" || { echo "계획(plan18.py --order) 실패" >&2; exit 2; }
DRYJ="$(python3 -I "$SRC/plan18.py" --dry-run --json)"; DRY_RC=$?
jflag() { [[ -s "$START" ]] && [[ "$(jq -r "$1 // false" "$START" 2>/dev/null)" == true ]]; }
cond_lines() {
  local ok=1
  if [[ -z "$C18_DIRTY" ]]; then echo "⓪ 하네스 = $SHA(무변경 · 미추적 없음) · 동결본 해시 ${C18_HHASH:0:12} → 통과"; else echo "⓪ 하네스 = $SHA 아님($C18_DIRTY) → 막음"; ok=0; fi
  if [[ -s "$IMAGES" ]] && jq -e --arg s "$SHA" '.app.sha == $s and ([.app.id, .mockpg.id, (.aux[])] | all(test("^sha256:")))' "$IMAGES" > /dev/null 2>&1; then echo "⓪ 이미지 고정(앱 · mock-pg) → 통과"
  else echo "⓪ 이미지 고정 — --images 없음 · SHA 다름 · mockpg 없음 → 막음"; ok=0; fi
  jflag .smoke.done && echo "① .164 스모크 → 통과" || { echo "① .164 스모크 — smoke.done 아님 → 막음"; ok=0; }
  jflag .campaign_smoke.done && echo "② 캠페인 스모크 → 통과" || { echo "② 캠페인 스모크 — campaign_smoke.done 아님 → 막음"; ok=0; }
  # ③ dry-run 문제 0 + 최악 시간(배수 상한을 매 회차 다 씀) ≤ 예산(사용자 결정 2026-11-14 — 12시간 = 최악 기준)
  if (( DRY_RC == 0 )) && jq -e '.est_max_hours <= .budget_hours' <<< "$DRYJ" > /dev/null 2>&1; then
    echo "③ dry-run $(jq -r .n <<< "$DRYJ")회차 · 예상 $(jq -r .est_hours <<< "$DRYJ")시간 · 최악 $(jq -r .est_max_hours <<< "$DRYJ")시간 ≤ 예산 $(jq -r .budget_hours <<< "$DRYJ")시간 → 통과"
  else echo "③ dry-run 문제 $(jq -c .problems <<< "$DRYJ" 2>/dev/null) · 최악 $(jq -r .est_max_hours <<< "$DRYJ" 2>/dev/null)시간 → 막음"; ok=0; fi
  local pcj; pcj="$(bash "$C18_FROZEN/k6/ADR-014/scripts/pccheck14.sh" 2>/dev/null)"; local pcrc=$?
  if ! jflag .pc_check.done; then echo "④ k6 PC 점검 — pc_check.done 아님 → 막음"; ok=0
  elif (( pcrc == 0 )) && [[ "$(jq -r .ok <<< "$pcj" 2>/dev/null)" == true ]]; then echo "④ k6 PC 점검 → 통과"
  else echo "④ k6 PC 점검 — $(jq -r '.why | join(" · ")' <<< "$pcj" 2>/dev/null || echo "pccheck14 출력 못 읽음") → 막음"; ok=0; fi
  (( FIXED_RC == 0 )) && echo "⑤ 측정 전 고정(plan18 FIXED) → 통과" || { echo "⑤ 측정 전 고정 — plan18 FIXED = False(자리표시 값) → 막음"; ok=0; }
  if jflag .tests_green.done && [[ "$(jq -r '.tests_green.sha // ""' "$START" | cut -c1-8)" == "$SHA" ]]; then echo "⑥ 전체 테스트 green @ $SHA → 통과"
  else echo "⑥ 전체 테스트 green — tests_green.done · sha = $SHA 아님 → 막음"; ok=0; fi
  return $(( ! ok ))
}
if (( DRY )); then
  echo "# ADR-018 캠페인 계획 — id $CID · 하네스 $SHA · 결과 $ROOT"
  python3 -I "$SRC/plan18.py" --dry-run
  echo "# 순서(순번 회차 셀 방식)"; echo "$ORDER"
  echo "# 시작 조건"; cond_lines; echo "시작 조건 판정: $( (( $? == 0 )) && echo 시작 가능 || echo 시작하지 않음)"
  exit 0
fi
COND_TMP="$(mktemp)"
if (( ! SIMU )); then
  cond_lines > "$COND_TMP"; crc=$?; cat "$COND_TMP" >&2   # stderr로 바로(`tee /dev/stderr` 금지 — stderr가 파일이면 O_TRUNC)
  (( crc == 0 )) || { echo "시작 조건 미충족 — 시작하지 않는다" >&2; rm -f "$COND_TMP"; exit 1; }
elif (( ! C18_WT )) && [[ -n "$C18_DIRTY" ]]; then echo "simulate: 하네스 dirty($C18_DIRTY) — --worktree로" >&2; rm -f "$COND_TMP"; exit 2; fi
mkdir -p "$ROOT"
if [[ -s "$ROOT/order.txt" ]]; then
  [[ "$(cat "$ROOT/order.txt")" == "$ORDER" ]] || { echo "이어서: order.txt가 다시 계산한 순서와 다르다" >&2; exit 2; }
  [[ "$(jq -r .sha "$ROOT/plan.json")" == "$SHA" ]] || { echo "이어서: 계획 SHA가 다르다" >&2; exit 2; }
  [[ "$(jq -r .harness_hash "$ROOT/plan.json")" == "$C18_HHASH" ]] || { echo "이어서: 동결본 해시가 계획과 다르다" >&2; exit 2; }
  (( SIMU )) || cmp -s "$IMAGES" "$ROOT/images.json" || { echo "이어서: images.json이 계획과 다르다" >&2; exit 2; }
else
  echo "$ORDER" > "$ROOT/order.txt"
  (( SIMU )) || { cp "$IMAGES" "$ROOT/images.json"; cp "$START" "$ROOT/start-conditions.json"; }
  jq -n --arg sha "$SHA" --arg k6 "$K6_BIN" --arg at "$(date -Is)" --arg hh "$C18_HHASH" --argjson dry "$DRYJ" --argjson sim "$SIMU" \
    '{sha: $sha, k6_bin: $k6, created: $at, reps: $dry.n, harness_hash: $hh, fixed: $dry.fixed, est_hours: $dry.est_hours, est_max_hours: $dry.est_max_hours,
      simulate: ($sim == 1)}' > "$ROOT/plan.json"
fi
[[ -s "$COND_TMP" ]] && cp "$COND_TMP" "$ROOT/start-conditions.log"; rm -f "$COND_TMP"
LOG="$ROOT/CAMPAIGN.log"
RUN_MODE=(); (( SIMU )) && RUN_MODE=(--simulate) && (( C18_WT )) && RUN_MODE+=(--worktree)
# 환경 판별(campaign17 kind_of와 같은 우선순위 — 쉼표 사유 목록을 낱낱이 본다): pc-link-down · pc-mem-low · k6-conn-error가 하나라도 있으면 env(다른 사유와 섞여도) ·
#   사유가 전부 env-send-lag면 env · 그 밖 무효와 status 없는 시도는 harness · ok는 valid. summarize18.env_kind와 같은 규칙
kind_of() {
  case "$1" in "") echo harness; return ;; invalid:*) ;; *) echo valid; return ;; esac
  local r any=0 sole=1 rs; IFS=, read -ra rs <<< "${1#invalid:}"
  for r in "${rs[@]}"; do case "$r" in pc-link-down|pc-mem-low|k6-conn-error) any=1 ;; env-send-lag) ;; *) sole=0 ;; esac; done
  if (( any || sole )); then echo env; else echo harness; fi
}
attempts_of() { local d="$ROOT/$2/$3/rep$1" n; for n in $(ls -d "$d".attempt* 2>/dev/null | sed 's/.*\.attempt//' | sort -n); do cat "$d.attempt$n/status" 2>/dev/null || echo; done; [[ -d "$d" ]] && { cat "$d/status" 2>/dev/null || echo; }; }
run_one() {   # 인자: rep method cell attempt
  echo "$(date -Is) RUN rep$1 $2 $3 attempt$4" >> "$LOG"
  CAMPAIGN18=1 CAMPAIGN18_WORKTREE="$C18_WT" HARNESS_HASH18="$C18_HHASH" REPO_ROOT="$REPO" ADR18_DIR="$ADR18" SIM_PLAN="$SIM_PLAN_F" \
    bash "$SRC/run18.sh" --sha "$SHA" --images "${IMAGES:-/dev/null}" --method "$2" --cell "$3" --rep "$1" --id "$CID" --attempt "$4" \
    --k6-bin "$K6_BIN" --results-root "$(dirname "$ROOT")" "${RUN_MODE[@]}" < /dev/null >> "$ROOT/runner.log" 2>&1
  echo "$(date -Is) END rep$1 $2 $3 attempt$4 $(st_of "$1" "$2" "$3" || true)" >> "$LOG"
  if [[ "$(st_of "$1" "$2" "$3")" == *summarize-failed* ]]; then   # 요약기 예외는 재측정하지 않고 멈춘다(실패 자료가 요약기 결함에 가려졌을 수 있다)
    echo "멈춤: rep$1 $2 $3 요약기 예외(summarize-failed) — 사용자 보고 대상(재측정하지 않는다)" | tee -a "$LOG" >&2; exit 8
  fi
}
final_down() { (( SIMU )) && return 0; REPO_ROOT="$REPO" bash "$SRC/run18.sh" --down --sha "$SHA" < /dev/null >> "$ROOT/runner.log" 2>&1 || echo "$(date -Is) 끝 내리기 실패 — 서버 확인" | tee -a "$LOG" >&2; }
streak=0
while read -r seq k c g; do   # order.txt 열 = 순번 회차 셀 방식
  if [[ -z "$(st_of "$k" "$g" "$c")" ]]; then
    att=$(( $(attempts_of "$k" "$g" "$c" | wc -l) + 1 ))
    run_one "$k" "$g" "$c" "$att"
    if [[ "$(kind_of "$(st_of "$k" "$g" "$c")")" != valid ]]; then streak=$((streak + 1)); else streak=0; fi
    (( streak >= 5 )) && { echo "멈춤: 무효 연속 5회 — 환경 확인" | tee -a "$LOG" >&2; final_down; exit 3; }
  fi
done <<< "$ORDER"
for round in 1 2 3 4; do   # 재측정 — invalid만 · 자리마다 환경 2번 · 하네스 2번(따로) · 원래 순서대로
  ran=0
  while read -r seq k c g; do
    st=$(st_of "$k" "$g" "$c"); kd=$(kind_of "$st")
    [[ "$kd" == valid ]] && continue
    hist=$(attempts_of "$k" "$g" "$c"); n=$(wc -l <<< "$hist")
    used=$(head -n $(( n - 1 )) <<< "$hist" | while IFS= read -r s; do kind_of "$s"; done | grep -cx "$kd"); (( n > 1 )) || used=0
    (( used < 2 )) || continue
    run_one "$k" "$g" "$c" $(( n + 1 )); ran=1
  done <<< "$ORDER"
  (( ran )) || break
done
final_down
missing=$(missing_of <<< "$ORDER")
if [[ -n "$missing" ]]; then echo "멈춤: 실행되지 않은 자리 $(wc -l <<< "$missing")개 — $(tr '\n' ' ' <<< "$missing")" | tee -a "$LOG" >&2; exit 6; fi
bad=$(for mf in "$ROOT"/*/*/rep*/meta.json; do [[ -f "$mf" ]] || continue; [[ "$(jq -r '.harness_hash // ""' "$mf")" == "$C18_HHASH" ]] || echo "$mf"; done)
if [[ -n "$bad" ]]; then echo "멈춤: 동결본과 다른 하네스 해시의 회차 — $bad" | tee -a "$LOG" >&2; exit 5; fi
bad=$(invalid_of <<< "$ORDER")   # 재측정 상한을 다 쓴 뒤에도 무효인 자리(리뷰 H1-4 — 끝 집계는 하되 exit 4로 알린다)
[[ -n "$bad" ]] && echo "무효로 남은 자리 $(wc -l <<< "$bad")개(재측정 상한 소진) — $(tr '\n' ' ' <<< "$bad")" | tee -a "$LOG" >&2
if (( SIMU )); then echo "simulate: $(grep -c . <<< "$ORDER")자리 모두 status · 해시 일치 — 끝 집계 생략($ROOT)" | tee -a "$LOG"; [[ -n "$bad" ]] && exit 4; exit 0; fi
python3 -I "$SRC/summarize18.py" --campaign "$ROOT" >> "$LOG" 2>&1 || { echo "끝 집계 실패 — $LOG" >&2; exit 7; }
jq -r '"결론: 표 완결 \(.complete) · 방식별 통과(정합 · p99 상한) \(.pass | tojson) — 기본값 결정은 사람이 한다"' "$ROOT/campaign18.json" | tee -a "$LOG"
echo "끝 — $ROOT/campaign18.json"
[[ -n "$bad" ]] && exit 4
exit 0
