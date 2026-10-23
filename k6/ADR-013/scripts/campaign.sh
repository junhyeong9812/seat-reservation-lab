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
#      smoke_durations.{startup_s, collect_s, stop_s, drain_s}(스모크 events.jsonl 실측 — 기동 → UP · 수집 · 내림 · 비움의 최대 초) 필수 ·
#      그 값으로 plan13.py --probe-dry-run을 돌려 0이 아니거나 값이 없으면 시작하지 않는다(사건 길이 0 dry-run은 판정 자료가 아니다 — CR13-8)
# 재측정(§5.3 · §5.8-5): status invalid:*(하네스)만 — 같은 (부하, 절차, 회차) 자리 최대 2번 · 캠페인 끝에 원래 순서대로(--attempt n → 결과 표 'rerun').
#   fail:*(절차 원인)·ok는 다시 재지 않는다(다시 재서 ok가 나오면 실패가 가려진다).
#   멈추고 보고(자동 재실행 없음 — 사용자 확인): invalid의 사유에 probe-nondeterministic(하네스 분류 — 그 부하 15회 전체 재실행 · §5.3 S5-10 · §5.7-5) ·
#   캠페인 끝 요약에서 P8 반증·판정 불가(§5.8-5) · P7 반증(task 05로 — §5.3). 하네스 결함을 고치면 30회 전체를 다시 돈다(§5.8-5 — 새 --id · 앞 결과는 superseded로 남김).
set -uo pipefail
if [[ "${CAMPAIGN13_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"; frozen="$(mktemp -d)"; cp -r "$src" "$frozen/scripts"
  export CAMPAIGN13_FROZEN=1 C13_SRC="$src"; exec bash "$frozen/scripts/campaign.sh" "$@"
fi
SRC="$C13_SRC"; ADR13="$(cd "$SRC/.." && pwd)"; REPO="$(cd "$ADR13/../.." && pwd)"
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
cond_lines() {   # 시작 조건 줄(§5.3) — 판정 결과는 마지막 열
  local ok=1 c r1 r2
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
  local b dur=""
  [[ -s "$START" ]] && jq -e '.smoke_durations as $s | [$s.startup_s, $s.collect_s, $s.stop_s, $s.drain_s] | all(type == "number" and . >= 0)' "$START" > /dev/null 2>&1 \
    && dur=$(jq -r '.smoke_durations | "--startup-s \(.startup_s) --collect-s \(.collect_s) --stop-s \(.stop_s) --drain-s \(.drain_s)"' "$START")
  if [[ -z "$dur" ]]; then
    echo "⑤ 탐침 일정 dry-run — 시작 조건 smoke_durations(startup_s·collect_s·stop_s·drain_s 스모크 실측) 없음·형식 아님 → 막음"; ok=0
  else
    # shellcheck disable=SC2086
    b=$(python3 -I "$SRC/plan13.py" --probe-dry-run $dur --json | jq -r .boundary_total)
    [[ "$b" == 0 ]] && echo "⑤ 탐침 일정 dry-run($dur) 경계 탐침 $b → 통과" || { echo "⑤ 탐침 일정 dry-run($dur) 경계 탐침 ${b:-판정 실패} → 막음"; ok=0; }
  fi
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
  cmp -s "$IMAGES" "$ROOT/images.json" || { echo "이어서: images.json이 계획과 다르다(캠페인 안 재빌드 금지 — DS-11)" >&2; exit 2; }
else
  echo "$ORDER" > "$ROOT/order.txt"; cp "$IMAGES" "$ROOT/images.json"; cp "$START" "$ROOT/start-conditions.json"
  jq -n --arg sha "$SHA" --arg new "${SHA_NEW:-$SHA}" --arg k6 "$K6_BIN" --argjson tmpfs "$PG_TMPFS" --arg at "$(date -Is)" \
    '{sha: $sha, sha_new: $new, k6_bin: $k6, pg_tmpfs_gb: $tmpfs, created: $at, reps: 30}' > "$ROOT/plan.json"
fi
cp /tmp/.c13-cond.$$ "$ROOT/start-conditions.log"; rm -f /tmp/.c13-cond.$$
LOG="$ROOT/CAMPAIGN.log"
st_of() { cat "$ROOT/$1/p$2/rep$3/status" 2>/dev/null; }
run_one() {   # 인자: rep load proc attempt
  echo "$(date -Is) RUN rep$1 $2 p$3 attempt$4" >> "$LOG"
  CAMPAIGN13=1 bash "$SRC/run.sh" --sha "$SHA" --sha-new "${SHA_NEW:-$SHA}" --images "$ROOT/images.json" --proc "$3" --load "$2" --rep "$1" --id "$CID" \
    --attempt "$4" --k6-bin "$K6_BIN" --pg-tmpfs-size "$PG_TMPFS" >> "$ROOT/runner.log" 2>&1
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
python3 -I "$SRC/summarize.py" "$ROOT" >> "$LOG" 2>&1
jq -r '.campaign_valid | to_entries[] | "\(.key): \(.value)"' "$ROOT/result.json" | tee -a "$LOG"
if jq -e '.campaign_valid.S4c.P8 != "성립" or .campaign_valid.S3e.P8 != "성립" or .campaign_valid.P7 != "성립"' "$ROOT/result.json" > /dev/null; then
  echo "멈춤: 캠페인 유효 가설 P7·P8 중 성립하지 않는 것이 있다 — 자동 재실행하지 않는다(사용자 보고 — §5.8-5)" | tee -a "$LOG" >&2; exit 4
fi
echo "끝 — $ROOT/RESULT.md"
