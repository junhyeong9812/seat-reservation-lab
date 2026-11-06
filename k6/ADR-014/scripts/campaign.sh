#!/usr/bin/env bash
# ADR-014 측정 캠페인(측정-2 · 측정-9): 4방식 × (주 셀 4 + 비용 셀 1) × 3회 = 60회. 회차마다 run.sh 한 번.
#   순서 = 회차 우선(회차 1의 20자리 → 2 → 3) · 회차 안 셀 묶음 a20-t30 → a50-t30 → a20-t300 → a50-t300 → S4c4200 · 묶음 g · 회차 r의 방식 = ABCD를 (r + g) mod 4칸 회전
#     — 계획의 단일 출처 scripts/plan14.py --order. order.txt에 남고 이어서 실행 때 다시 계산해 다르면 멈춘다.
#
#   scripts/campaign.sh --sha <SHA> --images <images.json> --start-conditions <json> [--knobs <json>] [--id <id>] [--k6-bin k6] [--probe-k6-bin <경로>]
#   scripts/campaign.sh --sha <SHA> [--knobs <json>] [--start-conditions <json>] --dry-run      # 60자리 계획 · 예상 시간 · 탐침 일정 점검 · 시작 조건 줄(서버 접속 없음)
#   scripts/campaign.sh --sha <SHA> --simulate --results-root <폴더> [--sim-plan <파일>] [--worktree]  # 서버 없이 루프 전체(run.sh --simulate) — 계획 대 실행 대조 · 재측정 상한 시험
#   scripts/campaign.sh --check <캠페인 결과 폴더>                                                  # 계획 대 실행 대조만(미실행 자리가 있으면 exit 6)
#   --worktree는 --dry-run · --simulate 전용(커밋 전 시험 — 본측정은 늘 --sha 동결본)
#
# 시작 조건(측정-9 '캠페인 시작 조건' ⓪~⑦ — 측정 전 1번 · --start-conditions JSON · 각 log 1행):
#   ⓪ 하네스 동결 — 작업트리의 하네스 경로(scripts/hhash.sh --paths)가 --sha와 같고 미추적 파일이 없어야 시작 · 시작하면 그 SHA를 git archive로 동결해 회차·끝 집계가 동결본을 쓴다
#      (해시를 plan.json에 · 회차마다 run.sh가 자기 원본 해시를 meta.json에 · 끝에 대조) · 이미지 ID 고정(--images — run.sh --prepare-image)
#   ① .164 스모크(네 방식 × a50-t30 + A·D × 비용 셀 — env-stall만으로 무효인 회차는 막지 않는다)  — JSON smoke.done = true
#   ② 캠페인 스모크(두 자리 이상 — 루프 stdin 교훈 · ADR-013 §6.1)                              — JSON campaign_smoke.done = true
#   ③ dry-run(60자리 · 탐침 마지막 요청 ≤ t0 + D_arr − 5초)                                     — plan14.py --dry-run 직접 계산(exit 0이어야 통과)
#   ④ k6 PC 점검(재시작 루프 없음 — docker ps 기록 · R6 2026-11-03: 다른 컨테이너·load는 막지 않고 기록만 — MemAvailable·판정 불가만 막음)                         — JSON pc_check.done = true
#      + PC 다른 부하 없음(명세 R5 ② — 측정 전 재해석 2026-10-30): scripts/pccheck14.sh 자동(seatlab 밖 실행 중 컨테이너 0 · 1분 load ≤ 4 · MemAvailable ≥ 16GiB —
#        문턱은 plan14 PC_*) · 시작 · 이어서 실행 때마다 · 어기거나 못 읽으면 시작하지 않는다(다른 부하를 멈추는 것은 사용자 몫)
#   ⑤ [구현 검증] #15~#25 결과 · 표본 비용(Δc · R₀ · 적용 결과) · 환경 정지 빈도 · 흐름별 VU 최대   — JSON impl_verify.done = true(+ 값은 기록용)
#   ⑥ 시작 전 1번만 바꾸는 값(D_arr′ · 지도 속도 · maxVUs · 표본 방식) = --knobs(plan.json에 고정 — 이어서 실행 때 다르면 멈춘다)
#   ⑦ 동시성 테스트 green(§설계-9 N2 · §설계-10 #12) 측정 SHA에서                                 — JSON concurrency_green.done = true · sha = --sha
# 재측정(측정-9 · 리뷰 MR-10): invalid:*만 — 같은 (방식, 셀, 회차) 자리에서 환경(env-stall만 · pc-link-down 포함 — kind_of) 재측정 최대 2번 · 그 밖 하네스 사유 재측정 최대 2번을 **따로** 센다
#   (자리의 시도 이력 rep<k>.attempt<n> · rep<k>의 status로 다시 계산 — 이어서 실행해도 같다) · 캠페인 끝에 원래 순서대로(--attempt n → 결과 표 'rerun').
#   ok(유효 — 방식 실패 포함)는 다시 재지 않는다. 상한을 다 쓴 자리: 마지막 시도가 환경(kind_of env)이면 '판정 불가 → 사용자 보고'(요약기 · 측정-8 ⓑ) · 하네스면 그 (방식, 셀) 유효 < 3.
# 멈춤(측정-9 '캠페인 멈춤' — 자동 재실행 없음): 하네스 실패(무효) 연속 5회 → exit 3 · 회차 1의 TTL 30초 두 묶음(8자리)이 끝났을 때 A의 W = 0이거나
#   A의 PR-1 δ ∈ {1, 3} 부당 실패 0(G4·P3 양성 대조가 처음부터 무너짐 — summarize.py --early-check) → exit 4.
# 공통 난수: 본 바퀴 끝과 재측정 바퀴마다 summarize.py --crn-check(같은 (회차, 셀)에서 다수와 다른 방식 → status에 crn-mismatch → 재측정 대상).
# 끝: 계획 대 실행 대조(모든 자리에 status — 없으면 exit 6 · 무음 금지) → 회차 하네스 해시 = 동결본(exit 5) → summarize.py <결과 폴더>(RESULT.md · result.json).
# 루프 안 run.sh는 늘 `< /dev/null`로 부른다 — ADR-013 첫 캠페인에서 run.sh(ssh·docker)가 회차 루프의 here-string을 읽어 1회만 돌았다(§6.1 · 측정-9 ②).
set -uo pipefail
if [[ "${1:-}" == --check ]]; then CHECK_ONLY="${2:?--check <결과 폴더>}"; fi
if [[ -z "${CHECK_ONLY:-}" && "${CAMPAIGN14_FROZEN:-0}" != 1 ]]; then   # 동결(CR13-21과 같은 규칙): --sha를 git archive로 풀고 그 사본의 campaign.sh로 다시 시작한다
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"
  sha_arg=HEAD prev="" wt=0 testmode=0
  for a in "$@"; do [[ "$prev" == --sha ]] && sha_arg="$a"; [[ "$a" == --worktree ]] && wt=1; [[ "$a" == --dry-run || "$a" == --simulate ]] && testmode=1; prev="$a"; done
  (( wt && ! testmode )) && { echo "--worktree는 --dry-run · --simulate 전용" >&2; exit 2; }
  sha=$(git -C "$repo" rev-parse --short=8 "${sha_arg}^{commit}" 2>/dev/null) || { echo "SHA 해석 실패: $sha_arg" >&2; exit 2; }
  mapfile -t hp < <(bash "$src/hhash.sh" --paths)
  (( ${#hp[@]} > 0 )) || { echo "하네스 경로 목록 실패" >&2; exit 2; }
  dirty=""
  git -C "$repo" diff --quiet "$sha" -- "${hp[@]}" ':(exclude)k6/ADR-014/results' 2>/dev/null || dirty="작업트리 변경"
  [[ -z "$(git -C "$repo" ls-files --others --exclude-standard -- "${hp[@]}" ':(exclude)k6/ADR-014/results')" ]] || dirty="${dirty:+$dirty · }미추적 파일"
  frozen="$(mktemp -d)"
  if (( wt )); then
    tar -C "$repo" --exclude=k6/ADR-014/results --exclude=__pycache__ -cf - "${hp[@]}" | tar -x -C "$frozen" || { echo "작업트리 사본 실패" >&2; rm -rf "$frozen"; exit 2; }
    dirty="worktree(시험 전용)${dirty:+ · $dirty}"
  else
    git -C "$repo" archive "$sha" -- "${hp[@]}" ':(exclude)k6/ADR-014/results' | tar -x -C "$frozen" \
      && [[ -f "$frozen/k6/ADR-014/scripts/campaign.sh" && -f "$frozen/k6/ADR-014/scripts/hhash.sh" ]] || { echo "git archive $sha 동결 실패(그 SHA에 하네스가 없다?)" >&2; rm -rf "$frozen"; exit 2; }
  fi
  hh=$(bash "$frozen/k6/ADR-014/scripts/hhash.sh" "$frozen") || { echo "동결본 해시 실패" >&2; rm -rf "$frozen"; exit 2; }
  export CAMPAIGN14_FROZEN=1 C14_REPO="$repo" C14_SHA="$sha" C14_FROZEN="$frozen" C14_HHASH="$hh" C14_DIRTY="$dirty" C14_WT="$wt"
  exec bash "$frozen/k6/ADR-014/scripts/campaign.sh" "$@"
fi
SRC="$(cd "$(dirname "$0")" && pwd)"

# ---- 계획 대 실행 대조(끝 집계 전 · --check) — 단일 함수 ---------------------------------------------------------------------------------
st_of() { cat "$ROOT/$2/$3/rep$1/status" 2>/dev/null; }   # 인자: rep cell method
missing_of() {   # 표준 입력 = order 행 → 미실행(status 없음) 자리를 한 줄씩
  local seq k c m
  while read -r seq k c m; do [[ -n "$seq" ]] || continue; [[ -n "$(st_of "$k" "$c" "$m")" ]] || echo "#$seq rep$k $c $m"; done
}
if [[ -n "${CHECK_ONLY:-}" ]]; then
  ROOT="$(cd "$CHECK_ONLY" && pwd)" || exit 2
  [[ -s "$ROOT/order.txt" ]] || { echo "order.txt 없음 — $ROOT" >&2; exit 2; }
  knobs_arg=(); [[ -s "$ROOT/knobs.json" ]] && knobs_arg=(--knobs "$ROOT/knobs.json")
  want="$(python3 -I "$SRC/plan14.py" --order "${knobs_arg[@]}")" || exit 2
  [[ "$(cat "$ROOT/order.txt")" == "$want" ]] || { echo "order.txt가 plan14.py --order와 다르다 — 멈춘다" >&2; exit 2; }
  missing=$(missing_of < "$ROOT/order.txt")
  if [[ -n "$missing" ]]; then echo "계획 대 실행: 실행되지 않은 자리 $(wc -l <<< "$missing")개 — $(tr '\n' ' ' <<< "$missing")" >&2; exit 6; fi
  echo "계획 대 실행: $(grep -c . "$ROOT/order.txt")자리 모두 status 있음"; exit 0
fi
trap '[[ "$C14_FROZEN" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "$C14_FROZEN"' EXIT
REPO="$C14_REPO"; ADR14="$REPO/k6/ADR-014"
SHA="" IMAGES="" START="" KNOBS="" CID="" K6_BIN=k6 PROBE_K6_BIN="" DRY=0 SIMU=0 RESULTS_ROOT="" SIM_PLAN_F=""
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --images) IMAGES="$2"; shift 2 ;;
    --start-conditions) START="$2"; shift 2 ;;
    --knobs) KNOBS="$2"; shift 2 ;;
    --id) CID="$2"; shift 2 ;;
    --k6-bin) K6_BIN="$2"; shift 2 ;;
    --probe-k6-bin) PROBE_K6_BIN="$2"; shift 2 ;;
    --results-root) RESULTS_ROOT="$2"; shift 2 ;;
    --sim-plan) SIM_PLAN_F="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    --simulate) SIMU=1; shift ;;
    --worktree) shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
SHA=$(git -C "$REPO" rev-parse --short=8 "${SHA:-HEAD}^{commit}") || exit 2
[[ "$SHA" == "$C14_SHA" ]] || { echo "동결 SHA $C14_SHA ≠ --sha $SHA" >&2; exit 2; }
CID="${CID:-$(date +%Y%m%d)-adr014-$SHA}"
ROOT="${RESULTS_ROOT:-$ADR14/results}/$CID"
KNOBS_ARG=(); [[ -n "$KNOBS" ]] && KNOBS_ARG=(--knobs "$KNOBS")
ORDER="$(python3 -I "$SRC/plan14.py" --order "${KNOBS_ARG[@]}")" || { echo "계획(plan14.py --order) 실패" >&2; exit 2; }
DRYJ="$(python3 -I "$SRC/plan14.py" --dry-run --json "${KNOBS_ARG[@]}")"; DRY_RC=$?
jflag() { [[ -s "$START" ]] && [[ "$(jq -r "$1 // false" "$START" 2>/dev/null)" == true ]]; }
cond_lines() {   # 시작 조건 줄(측정-9) — 판정 결과는 마지막 열
  local ok=1
  if [[ -z "$C14_DIRTY" ]]; then echo "⓪ 하네스 = $SHA(하네스 경로 무변경 · 미추적 없음) · 동결본 해시 ${C14_HHASH:0:12} → 통과"
  else echo "⓪ 하네스 = $SHA 아님($C14_DIRTY) → 막음"; ok=0; fi
  if [[ -s "$IMAGES" ]] && jq -e --arg s "$SHA" '.app.sha == $s and ([.app.id, (.aux[])] | all(test("^sha256:")))' "$IMAGES" > /dev/null 2>&1; then
    echo "⓪ 이미지 고정 — $(jq -r '.app.tag + " " + .app.id[7:19]' "$IMAGES") → 통과"
  else echo "⓪ 이미지 고정 — --images 없음 · SHA 다름 · 형식 아님 → 막음"; ok=0; fi
  jflag .smoke.done && echo "① .164 스모크(네 방식 × a50-t30 + A·D × 비용 셀) → 통과" || { echo "① .164 스모크 — start-conditions smoke.done 아님 → 막음"; ok=0; }
  jflag .campaign_smoke.done && echo "② 캠페인 스모크(두 자리 이상 · 루프 stdin) → 통과" || { echo "② 캠페인 스모크 — campaign_smoke.done 아님 → 막음"; ok=0; }
  if (( DRY_RC == 0 )); then echo "③ dry-run $(jq -r .n <<< "$DRYJ")자리 · 예상 $(jq -r .est_hours <<< "$DRYJ")시간 · 탐침 마지막 요청 < D_arr − 5초 → 통과"
  else echo "③ dry-run 문제 $(jq -c .problems <<< "$DRYJ" 2>/dev/null) → 막음"; ok=0; fi
  # ④ k6 PC 점검: 사람 기록(pc_check.done — 재시작 루프 프로세스 없음) + PC 다른 부하 없음 자동 점검(명세 R5 ② — pccheck14.sh: seatlab 밖 컨테이너 0 · load · MemAvailable)
  local pcj; pcj="$(bash "$SRC/pccheck14.sh" 2>/dev/null)"; local pcrc=$?
  if ! jflag .pc_check.done; then echo "④ k6 PC 점검 — pc_check.done 아님 → 막음"; ok=0
  elif (( pcrc == 0 )) && [[ "$(jq -r .ok <<< "$pcj" 2>/dev/null)" == true ]]; then
    echo "④ k6 PC 점검(docker ps 기록) · MemAvailable 문턱 $(jq -c '{busy, load1, mem_avail_gib: ((.mem_avail_kb / 1048576 * 10 | floor) / 10), containers_other, limits}' <<< "$pcj") → 통과"
  else echo "④ k6 PC 점검 — PC 다른 부하(명세 R5 ②): $(jq -r '.why | join(" · ")' <<< "$pcj" 2>/dev/null || echo "pccheck14 출력 못 읽음") → 막음"; ok=0; fi
  jflag .impl_verify.done && echo "⑤ [구현 검증] #15~#25 · 표본 비용 · 환경 정지 빈도 · VU 최대 → 통과" || { echo "⑤ [구현 검증] — impl_verify.done 아님 → 막음"; ok=0; }
  echo "⑥ 시작 전 고정값(knobs) $( [[ -n "$KNOBS" ]] && jq -c . "$KNOBS" 2>/dev/null || echo '{} — 기본값') → 기록"
  if jflag .concurrency_green.done && [[ "$(jq -r '.concurrency_green.sha // ""' "$START" | cut -c1-8)" == "$SHA" ]]; then echo "⑦ 동시성 테스트 green(N2 · #12) @ $SHA → 통과"
  else echo "⑦ 동시성 테스트 green — concurrency_green.done · sha = $SHA 아님 → 막음"; ok=0; fi
  return $(( ! ok ))
}
if (( DRY )); then
  echo "# ADR-014 캠페인 계획 — id $CID · 하네스 $SHA · $(jq -r .n <<< "$DRYJ")자리(주 16셀 + 비용 4셀 × 3회) · 결과 $ROOT"
  python3 -I "$SRC/plan14.py" --dry-run "${KNOBS_ARG[@]}"
  echo "# 시작 조건(측정-9)"
  cond_lines; echo "시작 조건 판정: $( (( $? == 0 )) && echo 시작 가능 || echo 시작하지 않음)"
  exit 0
fi
if (( ! SIMU )); then
  cond_lines | tee /dev/stderr > "/tmp/.c14-cond.$$"; (( ${PIPESTATUS[0]} == 0 )) || { echo "시작 조건 미충족 — 시작하지 않는다(측정-9)" >&2; rm -f "/tmp/.c14-cond.$$"; exit 1; }
elif (( ! C14_WT )) && [[ -n "$C14_DIRTY" ]]; then echo "simulate: 하네스 dirty($C14_DIRTY) — --worktree로" >&2; exit 2; fi
mkdir -p "$ROOT"
if [[ -s "$ROOT/order.txt" ]]; then
  [[ "$(cat "$ROOT/order.txt")" == "$ORDER" ]] || { echo "이어서: order.txt가 다시 계산한 순서와 다르다 — 멈춘다" >&2; exit 2; }
  [[ "$(jq -r .sha "$ROOT/plan.json")" == "$SHA" ]] || { echo "이어서: 계획 SHA가 다르다" >&2; exit 2; }
  [[ "$(jq -r .harness_hash "$ROOT/plan.json")" == "$C14_HHASH" ]] || { echo "이어서: 동결본 해시가 계획과 다르다" >&2; exit 2; }
  [[ "$(jq -c '.knobs' "$ROOT/plan.json")" == "$( [[ -n "$KNOBS" ]] && jq -c . "$KNOBS" || echo '{}')" ]] || { echo "이어서: knobs가 계획과 다르다(시작 뒤 고정 — 측정-9 ⑥)" >&2; exit 2; }
  (( SIMU )) || cmp -s "$IMAGES" "$ROOT/images.json" || { echo "이어서: images.json이 계획과 다르다(캠페인 안 재빌드 금지)" >&2; exit 2; }
else
  echo "$ORDER" > "$ROOT/order.txt"
  if [[ -n "$KNOBS" ]]; then jq . "$KNOBS" > "$ROOT/knobs.json"; fi
  (( SIMU )) || { cp "$IMAGES" "$ROOT/images.json"; cp "$START" "$ROOT/start-conditions.json"; }
  jq -n --arg sha "$SHA" --arg k6 "$K6_BIN" --arg at "$(date -Is)" --arg hh "$C14_HHASH" --argjson knobs "$( [[ -n "$KNOBS" ]] && jq -c . "$KNOBS" || echo '{}')" \
        --argjson dry "$DRYJ" --argjson sim "$SIMU" \
    '{sha: $sha, k6_bin: $k6, created: $at, reps: 60, harness_hash: $hh, knobs: $knobs, est_hours: $dry.est_hours, simulate: ($sim == 1)}' > "$ROOT/plan.json"
fi
[[ -f "/tmp/.c14-cond.$$" ]] && { cp "/tmp/.c14-cond.$$" "$ROOT/start-conditions.log"; rm -f "/tmp/.c14-cond.$$"; }
LOG="$ROOT/CAMPAIGN.log"
RUN_KNOBS=(); [[ -s "$ROOT/knobs.json" ]] && RUN_KNOBS=(--knobs "$ROOT/knobs.json")
RUN_MODE=(); (( SIMU )) && RUN_MODE=(--simulate) && (( C14_WT )) && RUN_MODE+=(--worktree)
PROBE_ARG=(); [[ -n "$PROBE_K6_BIN" ]] && PROBE_ARG=(--probe-k6-bin "$PROBE_K6_BIN")
# 환경 = env-stall만 · 또는 pc-link-down(사용자 결정 ② 2026-10-29) · pc-mem-low(명세 R5 ② 2026-10-30)가 든 무효(그 회차의 다른 사유는 끊김·메모리 부족의 결과) —
#   summarize.py _kind와 같은 규칙. status 없는 시도(도중 끊김)는 하네스로 센다
kind_of() { case "$1" in invalid:env-stall|invalid:pc-link-down|invalid:pc-link-down,*|invalid:*,pc-link-down|invalid:*,pc-link-down,*|invalid:pc-mem-low|invalid:pc-mem-low,*|invalid:*,pc-mem-low|invalid:*,pc-mem-low,*) echo env ;; invalid:*) echo harness ;; "") echo harness ;; *) echo valid ;; esac; }
attempts_of() {   # 인자: rep cell method → 시도 status를 차례대로(앞 시도 rep<k>.attempt<n> · 마지막 rep<k>) 한 줄씩(없으면 빈 줄)
  local d="$ROOT/$2/$3/rep$1" n
  for n in $(ls -d "$d".attempt* 2>/dev/null | sed 's/.*\.attempt//' | sort -n); do cat "$d.attempt$n/status" 2>/dev/null || echo; done
  [[ -d "$d" ]] && { cat "$d/status" 2>/dev/null || echo; }
}
run_one() {   # 인자: rep cell method attempt
  echo "$(date -Is) RUN rep$1 $2 $3 attempt$4" >> "$LOG"
  CAMPAIGN14=1 CAMPAIGN14_WORKTREE="$C14_WT" HARNESS_HASH14="$C14_HHASH" REPO_ROOT="$REPO" ADR14_DIR="$ADR14" SIM_PLAN="$SIM_PLAN_F" \
    bash "$SRC/run.sh" --sha "$SHA" --images "${IMAGES:-/dev/null}" --method "$3" --cell "$2" --rep "$1" --id "$CID" --attempt "$4" \
    --k6-bin "$K6_BIN" --results-root "$(dirname "$ROOT")" "${RUN_KNOBS[@]}" "${RUN_MODE[@]}" "${PROBE_ARG[@]}" < /dev/null >> "$ROOT/runner.log" 2>&1   # stdin 차단(머리말)
  echo "$(date -Is) END rep$1 $2 $3 attempt$4 $(st_of "$1" "$2" "$3" || true)" >> "$LOG"
}
streak=0 done_n=0 early_done=0
while read -r seq k c m; do
  done_n=$((done_n + 1))
  if [[ -z "$(st_of "$k" "$c" "$m")" ]]; then
    att=$(( $(attempts_of "$k" "$c" "$m" | wc -l) + 1 ))
    run_one "$k" "$c" "$m" "$att"
    st=$(st_of "$k" "$c" "$m")
    if [[ "$(kind_of "$st")" != valid ]]; then streak=$((streak + 1)); else streak=0; fi
    (( streak >= 5 )) && { echo "멈춤: 하네스 실패(무효) 연속 5회 — 환경 확인(측정-9 '캠페인 멈춤')" | tee -a "$LOG" >&2; exit 3; }
  fi
  if (( k == 1 && seq == 8 && ! early_done && ! SIMU )); then   # 회차 1의 TTL 30초 두 묶음 끝 — G4·P3 양성 대조 조기 확인
    early_done=1
    if ! python3 -I "$SRC/summarize.py" --early-check "$ROOT" >> "$LOG" 2>&1; then
      echo "멈춤: 회차 1 TTL 30초 묶음에서 A의 W = 0 또는 A의 PR-1 δ ∈ {1, 3} 부당 실패 0 — 양성 대조가 처음부터 무너졌다(측정-9 · 사용자 보고)" | tee -a "$LOG" >&2; exit 4
    fi
  fi
done <<< "$ORDER"
# 공통 난수 대조(측정-3.5 · 측정-9 crn-mismatch): 같은 (회차, 셀)의 방식 사이 세션 속성 해시가 다수와 다른 회차의 status에 crn-mismatch를 더한다 → 아래 재측정 대상
crn_check() { python3 -I "$SRC/summarize.py" --crn-check "$ROOT" >> "$LOG" 2>&1 || echo "$(date -Is) crn-check 실패" >> "$LOG"; }
crn_check
# 재측정 — invalid만 · 자리마다 환경 2번 · 하네스 2번(따로) · 원래 순서대로 · 한 바퀴에 자리마다 한 번
for round in 1 2 3 4; do
  ran=0
  while read -r seq k c m; do
    st=$(st_of "$k" "$c" "$m"); kd=$(kind_of "$st")
    [[ "$kd" == valid ]] && continue
    hist=$(attempts_of "$k" "$c" "$m"); n=$(wc -l <<< "$hist")
    used=$(head -n $(( n - 1 )) <<< "$hist" | while IFS= read -r s; do kind_of "$s"; done | grep -cx "$kd")
    (( n > 1 )) || used=0
    (( used < 2 )) || continue
    run_one "$k" "$c" "$m" $(( n + 1 )); ran=1
  done <<< "$ORDER"
  (( ran )) || break
  crn_check
done
missing=$(missing_of <<< "$ORDER")
if [[ -n "$missing" ]]; then echo "멈춤: 계획한 자리 중 실행되지 않은 회차 $(wc -l <<< "$missing")개 — $(tr '\n' ' ' <<< "$missing")" | tee -a "$LOG" >&2; exit 6; fi
bad=$(for mf in "$ROOT"/*/*/rep*/meta.json; do [[ -f "$mf" ]] || continue; [[ "$(jq -r '.harness_hash // ""' "$mf")" == "$C14_HHASH" ]] || echo "$mf"; done)
if [[ -n "$bad" ]]; then echo "멈춤: 동결본과 다른 하네스 해시의 회차 — $bad" | tee -a "$LOG" >&2; exit 5; fi
if (( SIMU )); then echo "simulate: 60자리 모두 status · 해시 일치 — 끝 집계 생략($ROOT)" | tee -a "$LOG"; exit 0; fi
python3 -I "$SRC/summarize.py" "$ROOT" >> "$LOG" 2>&1 || { echo "끝 집계 실패 — $LOG" >&2; exit 7; }
jq -r '.decision | "결론: \(.verdict) · 새 기본값 \(.new_default // "없음")"' "$ROOT/result.json" | tee -a "$LOG"
echo "끝 — $ROOT/RESULT.md"
