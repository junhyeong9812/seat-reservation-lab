#!/usr/bin/env bash
# ADR-013 측정 캠페인(§2 · §4.6 H8 · §5.3 재측정 · §5.8-5): 5절차 × {S4c, S3e} × 3회 = 30회. 회차마다 run.sh 한 번.
#   순서 = 회차 우선(회차 1의 10회 → 회차 2 → 회차 3) · 부하 순서 S4c → S3e(같은 부하 안에서만 비교) · 절차 순서는 회차 k마다 (k − 1)칸 회전
#     (회차 1: ①②③④⑤ · 2: ②③④⑤① · 3: ③④⑤①② — H8 'ABCD 회전'). 순서는 order.txt에 남고 이어서 실행 때 다시 계산해 다르면 멈춘다.
#
#   scripts/campaign.sh --sha <하네스 SHA> --images <images.json> --start-conditions <json> [--sha-new <SHA>] [--id <id>] [--k6-bin k6] [--pg-tmpfs-size 12]
#   scripts/campaign.sh --sha <SHA> [--images <json>] [--start-conditions <json>] --dry-run     # 계획 30행 · 시작 조건 줄 · 탐침 일정 dry-run · 예상 시간(측정 없음)
#
# 시작 조건(§5.3 '캠페인 시작 조건' — 측정 전 1회 · log 1행씩 · --start-conditions JSON):
#   ① 보정 두 시간 비 calibration.c_round_ratio · calibration.create_index_ratio가 둘 다 [0.8, 1.25] — 밖이면 **시작하지 않는다**(재시드 1회 → 그래도 밖이면 task 05 — §4.1 · S5-6)
#   ② 옛·새 이미지 ID 고정(--images — run.sh --prepare-images · DS-11)
#   ③ 부하 중 V9 스모크 두 경로(§4.5 DS-13) — ds13_smoke.done = true(오류가 나와도 막지 않는다 — P11 판정 자료)
#   ④ §4.9 [구현 검증] 결과 — impl_verify.done = true
#   ⑤ 탐침 일정 dry-run 경계 탐침 0(§4.9-20 · §5.8-1 · §5.8-0 '캠페인 전에 .164 스모크로 잰 사건 길이로 다시 dry-run') — 시작 조건 JSON
#      smoke_dirs = [스모크 회차 폴더 …](저장소 뿌리 기준 상대 경로 또는 절대 경로) 필수 · plan13.py가 그 events.jsonl에서 사건 종류 9종마다
#      성공 사건 dur_s 최대를 **직접 계산**해(기동 시작 · 설정 확인 · lb 넣기·빼기 포함 — CR13-20) --probe-dry-run을 돈다 ·
#      폴더 없음 · 한 종류라도 실측 없음 · 경계 탐침 > 0이면 시작하지 않는다(손값·0 길이 경로 없음 — CR13-8 · CR13-20)
#   ⑥ 적용판 단계 3 statement_timeout = max(⌈같은 스모크 pre-apply.json 단계 3 CONCURRENTLY 성공 시도 최대 × 3⌉, 600)초(§4.4 DS-8 · CR13-17) —
#      실측이 없으면(④⑤ 스모크 없음) 시작하지 않는다 · 정한 값은 plan.json에 남기고 모든 회차에 --stmt-timeout으로 넘긴다
#   ⓪ 하네스 = --sha(CR13-21): 작업트리의 하네스 경로(scripts/hhash.sh --paths)가 그 SHA와 같고 미추적 파일이 없어야 시작한다 ·
#      시작하면 그 SHA를 git archive로 동결해 회차·끝 집계가 모두 동결본을 쓴다(캠페인 중 작업트리를 고쳐도 측정이 바뀌지 않는다) ·
#      동결본 해시(hhash.sh)를 plan.json에 남기고 회차마다 run.sh가 자기 원본 해시를 대조해 meta.json에 남긴다 · 끝에 모든 회차 meta 해시를 대조한다
# 재측정(§5.3 · §5.8-5): status invalid:*(하네스)만 — 같은 (부하, 절차, 회차) 자리 최대 2번 · 캠페인 끝에 원래 순서대로(--attempt n → 결과 표 'rerun').
#   fail:*(절차 원인)·ok는 다시 재지 않는다(다시 재서 ok가 나오면 실패가 가려진다).
#   멈추고 보고(자동 재실행 없음 — 사용자 확인): invalid의 사유에 probe-nondeterministic(하네스 분류 — 그 부하 15회 전체 재실행 · §5.3 S5-10 · §5.7-5) ·
#   캠페인 끝 요약에서 P8 반증·판정 불가(§5.8-5) · P7 반증(task 05로 — §5.3). 하네스 결함을 고치면 30회 전체를 다시 돈다(§5.8-5 — 새 --id · 앞 결과는 superseded로 남김).
set -uo pipefail
if [[ "${CAMPAIGN13_FROZEN:-0}" != 1 ]]; then   # 동결(CR13-21): --sha를 git archive로 풀고 그 사본의 campaign.sh로 다시 시작한다
  src="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$src/../../.." && pwd)"
  sha_arg=HEAD prev=""; for a in "$@"; do [[ "$prev" == --sha ]] && sha_arg="$a"; prev="$a"; done
  sha=$(git -C "$repo" rev-parse --short=8 "${sha_arg}^{commit}" 2>/dev/null) || { echo "SHA 해석 실패: $sha_arg" >&2; exit 2; }
  mapfile -t hp < <(bash "$src/hhash.sh" --paths)
  (( ${#hp[@]} > 0 )) || { echo "하네스 경로 목록 실패" >&2; exit 2; }
  dirty=""
  git -C "$repo" diff --quiet "$sha" -- "${hp[@]}" ':(exclude)k6/ADR-013/results' || dirty="작업트리 변경"
  [[ -z "$(git -C "$repo" ls-files --others --exclude-standard -- "${hp[@]}" ':(exclude)k6/ADR-013/results')" ]] || dirty="${dirty:+$dirty · }미추적 파일"
  frozen="$(mktemp -d)"
  git -C "$repo" archive "$sha" -- "${hp[@]}" ':(exclude)k6/ADR-013/results' | tar -x -C "$frozen" \
    && [[ -f "$frozen/k6/ADR-013/scripts/campaign.sh" && -f "$frozen/k6/ADR-013/scripts/hhash.sh" ]] || { echo "git archive $sha 동결 실패(그 SHA에 하네스·hhash.sh가 없다?)" >&2; rm -rf "$frozen"; exit 2; }
  hh=$(bash "$frozen/k6/ADR-013/scripts/hhash.sh" "$frozen") || { echo "동결본 해시 실패" >&2; rm -rf "$frozen"; exit 2; }
  export CAMPAIGN13_FROZEN=1 C13_REPO="$repo" C13_SHA="$sha" C13_FROZEN="$frozen" C13_HHASH="$hh" C13_DIRTY="$dirty"
  exec bash "$frozen/k6/ADR-013/scripts/campaign.sh" "$@"
fi
trap '[[ "$C13_FROZEN" == "${TMPDIR:-/tmp}"/tmp.* ]] && rm -rf "$C13_FROZEN"' EXIT
SRC="$C13_FROZEN/k6/ADR-013/scripts"; REPO="$C13_REPO"; ADR13="$REPO/k6/ADR-013"   # 실행 = 동결본 · 결과 = 저장소의 results
SHA="" SHA_NEW="" IMAGES="" START="" CID="" K6_BIN=k6 PG_TMPFS=12 DRY=0
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --sha-new) SHA_NEW="$2"; shift 2 ;;
    --images) IMAGES="$2"; shift 2 ;;
    --start-conditions) START="$2"; shift 2 ;;
    --id) CID="$2"; shift 2 ;;
    --k6-bin) K6_BIN="$2"; shift 2 ;;
    --pg-tmpfs-size) PG_TMPFS="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
SHA=$(git -C "$REPO" rev-parse --short=8 "${SHA:-HEAD}^{commit}") || exit 2
[[ "$SHA" == "$C13_SHA" ]] || { echo "동결 SHA $C13_SHA ≠ --sha $SHA" >&2; exit 2; }
CID="${CID:-$(date +%Y%m%d)-adr013-$SHA}"
ROOT="$ADR13/results/$CID"
P=(1 2 3 4 5)
order() {   # → "seq rep load proc" 30행
  local seq=0 k ld i
  for k in 1 2 3; do
    for ld in S4c S3e; do
      for i in 0 1 2 3 4; do seq=$((seq + 1)); echo "$seq $k $ld ${P[$(( (i + k - 1) % 5 ))]}"; done
    done
  done
}
ORDER="$(order)"
# 시작 조건 ⑤·⑥ 입력(CR13-20 · CR13-17): 스모크 회차 폴더(저장소 뿌리 기준 상대 · 절대) → 실측 사건 길이 · statement_timeout
SMOKE_DIRS=()
if [[ -s "$START" ]]; then
  while IFS= read -r p; do [[ -n "$p" ]] && SMOKE_DIRS+=("$( [[ "$p" == /* ]] && echo "$p" || echo "$REPO/$p")"); done < <(jq -r '.smoke_dirs[]? // empty' "$START" 2>/dev/null)
fi
SMOKE_ARGS=(); for p in "${SMOKE_DIRS[@]}"; do SMOKE_ARGS+=(--smoke "$p"); done
STMT_JSON='{}'; STMT_S=""
if (( ${#SMOKE_DIRS[@]} )); then
  STMT_JSON=$(python3 -I "$SRC/plan13.py" --stmt-timeout "${SMOKE_DIRS[@]}" 2>/dev/null) || true
  STMT_S=$(jq -r '.stmt_timeout_s // empty' <<< "$STMT_JSON" 2>/dev/null)
fi
DRY_JSON='{}'
(( ${#SMOKE_DIRS[@]} )) && DRY_JSON=$(python3 -I "$SRC/plan13.py" --probe-dry-run "${SMOKE_ARGS[@]}" --json 2>/dev/null)
cond_lines() {   # 시작 조건 줄(§5.3) — 판정 결과는 마지막 열
  local ok=1 c r1 r2
  if [[ -z "$C13_DIRTY" ]]; then echo "⓪ 하네스 = $SHA(작업트리 하네스 경로 무변경 · 미추적 없음) · 동결본 해시 ${C13_HHASH:0:12} → 통과"
  else echo "⓪ 하네스 = $SHA 아님($C13_DIRTY — $(bash "$SRC/hhash.sh" --paths | xargs)) → 막음"; ok=0; fi
  if [[ -s "$START" ]]; then
    r1=$(jq -r '.calibration.c_round_ratio // empty' "$START"); r2=$(jq -r '.calibration.create_index_ratio // empty' "$START")
    if [[ -n "$r1" && -n "$r2" ]] && awk -v a="$r1" -v b="$r2" 'BEGIN { exit !(a >= 0.8 && a <= 1.25 && b >= 0.8 && b <= 1.25) }'; then c="통과"; else c="막음"; ok=0; fi
    echo "① 보정 비 C 한 바퀴 ${r1:-없음} · CREATE INDEX ${r2:-없음} — 허용 [0.8, 1.25] → $c"
    [[ "$(jq -r '.ds13_smoke.done // false' "$START")" == true ]] && c="통과" || { c="막음"; ok=0; }
    echo "③ 부하 중 V9 스모크(DS-13 두 경로) → $c"
    [[ "$(jq -r '.impl_verify.done // false' "$START")" == true ]] && c="통과" || { c="막음"; ok=0; }
    echo "④ §4.9 [구현 검증] 결과 → $c"
  else
    echo "① 보정 비 — --start-conditions 없음 → 막음"; echo "③ 부하 중 V9 스모크 — 없음 → 막음"; echo "④ [구현 검증] — 없음 → 막음"; ok=0
  fi
  if [[ -s "$IMAGES" ]] && jq -e '[.old.id, .new.id] | all(test("^sha256:"))' "$IMAGES" > /dev/null 2>&1; then
    echo "② 이미지 ID 고정 — 옛 $(jq -r '.old.tag + " " + (.old.id[7:19])' "$IMAGES") · 새 $(jq -r '.new.tag + " " + (.new.id[7:19])' "$IMAGES") → 통과"
  else echo "② 이미지 ID 고정 — --images 없음·형식 아님 → 막음"; ok=0; fi
  local b durs
  if (( ! ${#SMOKE_DIRS[@]} )); then
    echo "⑤ 탐침 일정 dry-run — 시작 조건 smoke_dirs(스모크 회차 폴더) 없음 → 막음(실측 사건 길이 없이 시작하지 않는다)"; ok=0
  else
    b=$(jq -r '.boundary_total // empty' <<< "$DRY_JSON" 2>/dev/null); durs=$(jq -c '.durations // {}' <<< "$DRY_JSON" 2>/dev/null)
    if [[ "$b" == 0 ]]; then echo "⑤ 탐침 일정 dry-run(스모크 ${#SMOKE_DIRS[@]}회차 op별 최대 $durs) 경계 탐침 0 → 통과"
    else echo "⑤ 탐침 일정 dry-run(스모크 ${#SMOKE_DIRS[@]}회차) 경계 탐침 ${b:-판정 실패} · $(jq -c '.problems // empty' <<< "$DRY_JSON" 2>/dev/null) → 막음"; ok=0; fi
  fi
  if [[ "$STMT_S" =~ ^[0-9]+$ ]]; then echo "⑥ 적용판 단계 3 statement_timeout ${STMT_S}s(CONCURRENTLY 실측 최대 $(jq -r .concurrently_max_s <<< "$STMT_JSON")초 × 3 · 최소 600) → 통과"
  else echo "⑥ 적용판 단계 3 statement_timeout — 스모크 CONCURRENTLY 실측 없음 $(jq -c '.problems // empty' <<< "$STMT_JSON" 2>/dev/null) → 막음"; ok=0; fi
  return $(( ! ok ))
}
if (( DRY )); then
  echo "# ADR-013 캠페인 계획 — id $CID · 하네스 $SHA · 30회(5절차 × S4c·S3e × 3)"
  echo "순번 회차 부하 절차 예상분"
  while read -r seq k ld p; do echo "$seq $k $ld $p $([[ $ld == S4c ]] && echo 45 || echo 27)"; done <<< "$ORDER"
  echo "예상 합계 약 $(( (15 * 45 + 15 * 27) / 60 ))시간(S4c 45분 · S3e 27분 — 셋업·끝 검사 포함 추정 [구현 검증])"
  echo "# 시작 조건"
  cond_lines; echo "시작 조건 판정: $( (( $? == 0 )) && echo 시작 가능 || echo 시작하지 않음)"
  exit 0
fi
cond_lines | tee /dev/stderr > /tmp/.c13-cond.$$ || { echo "시작 조건 미충족 — 시작하지 않는다(§5.3)" >&2; exit 1; }
mkdir -p "$ROOT"
if [[ -s "$ROOT/order.txt" ]]; then
  [[ "$(cat "$ROOT/order.txt")" == "$ORDER" ]] || { echo "이어서: order.txt가 다시 계산한 순서와 다르다 — 멈춘다" >&2; exit 2; }
  [[ "$(jq -r .sha "$ROOT/plan.json")" == "$SHA" ]] || { echo "이어서: 계획 SHA가 다르다" >&2; exit 2; }
  [[ "$(jq -r .harness_hash "$ROOT/plan.json")" == "$C13_HHASH" ]] || { echo "이어서: 동결본 해시가 계획과 다르다(CR13-21)" >&2; exit 2; }
  [[ "$(jq -r .stmt_timeout_s "$ROOT/plan.json")" == "$STMT_S" ]] || { echo "이어서: statement_timeout이 계획과 다르다(CR13-17)" >&2; exit 2; }
  cmp -s "$IMAGES" "$ROOT/images.json" || { echo "이어서: images.json이 계획과 다르다(캠페인 안 재빌드 금지 — DS-11)" >&2; exit 2; }
else
  echo "$ORDER" > "$ROOT/order.txt"; cp "$IMAGES" "$ROOT/images.json"; cp "$START" "$ROOT/start-conditions.json"
  jq -n --arg sha "$SHA" --arg new "${SHA_NEW:-$SHA}" --arg k6 "$K6_BIN" --argjson tmpfs "$PG_TMPFS" --arg at "$(date -Is)" --arg hh "$C13_HHASH" \
        --argjson stmt "$STMT_S" --argjson stmtj "$STMT_JSON" --argjson dry "$DRY_JSON" --args \
    '{sha: $sha, sha_new: $new, k6_bin: $k6, pg_tmpfs_gb: $tmpfs, created: $at, reps: 30, harness_hash: $hh, stmt_timeout_s: $stmt,
      stmt_timeout_from: $stmtj, smoke_dirs: $ARGS.positional, smoke_durations: $dry.durations}' "${SMOKE_DIRS[@]}" > "$ROOT/plan.json"
fi
cp /tmp/.c13-cond.$$ "$ROOT/start-conditions.log"; rm -f /tmp/.c13-cond.$$
LOG="$ROOT/CAMPAIGN.log"
st_of() { cat "$ROOT/$1/p$2/rep$3/status" 2>/dev/null; }
run_one() {   # 인자: rep load proc attempt
  echo "$(date -Is) RUN rep$1 $2 p$3 attempt$4" >> "$LOG"
  CAMPAIGN13=1 HARNESS_HASH13="$C13_HHASH" REPO_ROOT="$REPO" ADR13_DIR="$ADR13" bash "$SRC/run.sh" --sha "$SHA" --sha-new "${SHA_NEW:-$SHA}" \
    --images "$ROOT/images.json" --proc "$3" --load "$2" --rep "$1" --id "$CID" --attempt "$4" --k6-bin "$K6_BIN" --pg-tmpfs-size "$PG_TMPFS" \
    --stmt-timeout "${STMT_S}s" >> "$ROOT/runner.log" 2>&1
  local st; st=$(st_of "$2" "$3" "$1")
  echo "$(date -Is) END rep$1 $2 p$3 attempt$4 ${st:-상태없음}" >> "$LOG"
  if [[ "$st" == *probe-nondeterministic* ]]; then
    echo "멈춤: $2 p$3 rep$1 probe-nondeterministic(하네스 분류) — 그 부하 15회 전체 재실행은 사용자 확인 뒤(§5.3 · §5.7-5)" | tee -a "$LOG" >&2; exit 3
  fi
}
streak=0
while read -r seq k ld p; do
  st=$(st_of "$ld" "$p" "$k")
  [[ -n "$st" ]] && continue   # 끝난 자리(이어서 실행)
  run_one "$k" "$ld" "$p" 1
  st=$(st_of "$ld" "$p" "$k")
  if [[ "$st" == invalid:* ]]; then streak=$((streak + 1)); else streak=0; fi
  (( streak >= 5 )) && { echo "멈춤: 하네스 실패 연속 5회 — 환경 확인" | tee -a "$LOG" >&2; exit 3; }
done <<< "$ORDER"
# 재측정 — invalid만 · 자리마다 최대 2번 · 원래 순서대로
for att in 2 3; do
  while read -r seq k ld p; do
    st=$(st_of "$ld" "$p" "$k")
    [[ "$st" == invalid:* ]] || continue
    [[ -e "$ROOT/$ld/p$p/rep$k.attempt$((att - 1))" ]] && continue
    run_one "$k" "$ld" "$p" "$att"
  done <<< "$ORDER"
done
# 회차가 쓴 하네스 = 동결본(CR13-21): 모든 회차(앞 시도 포함) meta.json harness_hash 대조 — 다르면 집계하지 않고 멈춘다
bad=$(for m in "$ROOT"/*/p*/rep*/meta.json; do [[ -f "$m" ]] || continue; [[ "$(jq -r '.harness_hash // ""' "$m")" == "$C13_HHASH" ]] || echo "$m"; done)
if [[ -n "$bad" ]]; then echo "멈춤: 동결본과 다른 하네스 해시의 회차 — $bad" | tee -a "$LOG" >&2; exit 5; fi
python3 -I "$SRC/summarize.py" "$ROOT" >> "$LOG" 2>&1
jq -r '.campaign_valid | to_entries[] | "\(.key): \(.value)"' "$ROOT/result.json" | tee -a "$LOG"
if jq -e '.campaign_valid.S4c.P8 != "성립" or .campaign_valid.S3e.P8 != "성립" or .campaign_valid.P7 != "성립"' "$ROOT/result.json" > /dev/null; then
  echo "멈춤: 캠페인 유효 가설 P7·P8 중 성립하지 않는 것이 있다 — 자동 재실행하지 않는다(사용자 보고 — §5.8-5)" | tee -a "$LOG" >&2; exit 4
fi
echo "끝 — $ROOT/RESULT.md"
