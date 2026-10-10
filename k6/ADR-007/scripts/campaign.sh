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
#   묶음 = (설정, 셀 묶음) 12개(S7-m1·S7-m1-late는 한 묶음). 회차 k = 묶음 순서를 (k−1) × ⌈묶음 수 ÷ 회차 수⌉만큼 회전 + 각 묶음 안 방식 순서를 (k−1)만큼 회전.
#   ADR-006은 묶음이 3개라 (k−1)칸 회전으로 충분했지만 12개를 1칸씩 돌리면 3회 동안 묶음 자리가 거의 그대로다(캠페인 안 시각 = 묶음).
#   ⌈12 ÷ 3⌉ = 4칸씩 돌리면 각 묶음이 회차마다 캠페인의 앞·가운데·뒤 1/3을 한 번씩 맡는다. 묶음 목록은 설정을 섞어 둔다(BUNDLES) —
#   한 1/3 안에 세 설정이 고루 들어가게. 방식은 3개 × 3회라 묶음 안 각 자리(1·2·3번째)를 세 방식이 한 번씩 맡는다(라틴 방진).
#   난수·해시 없이 회차 번호만으로 정해져 언제 다시 계산해도 같다(--resume도 같은 순서).
#   실제 순서는 order.txt(회차 · 회차 안 순번 · 캠페인 전체 순번 · 조건)와 CAMPAIGN.log(ORDER 행)에 남긴다. 계산 결과는 회차별 행 수·조건 집합·순번을
#   검증하고(어긋나면 exit 2), 이어서 실행할 때 다시 계산한 순서가 기존 order.txt와 다르면(조건 목록·회차 수·순서 규칙 변경) 멈춘다.
#   끝의 재측정은 order.txt 밖이다(CAMPAIGN.log의 re-measure 행 — compare.py가 실제 호출 순번으로 다시 센다).
# 끝나면 정상이 아닌 회차를 다시 잰다(최대 2바퀴) —
# 단, '한계 < 첫 단계'(s4-no-successful-stage만 — 계단 S4)는 실패가 아니라 결과라 다시 재지 않는다. 그래도 남는 비정상 회차는 목록으로 남기고 exit 1.
# 중단되면 같은 --id로 다시 부르면 된다: 각 조건은 run.sh --resume으로 이어서 재고, 끝난 회차(status 있음)는 건너뛴다.
#
# ADR-007 후속(디스크 원인 C5 — docs/plans/2026-10-10/adr-007-disk-cause 명세 §9 task 04) — --set으로 고른다(기본 main = 위 117회, 바이트 그대로):
#   --set disk-cause  재측정 24회: 방식 {none, counter-upsert, counter-upsert-precheck} × 셀 {S4, S4c4200} × 설정 {default(디스크), tmpfs} × 2회(--reps 기본 2).
#                     조건 이름 = <방식>-<default|tmpfs>-<s4|s4c4200>. 순서 = 회차 우선 · 회차 k는 셀 순서·방식 순서·설정 순서를 각각 (k−1)만큼 회전 —
#                     회차 안에서 디스크·tmpfs가 한 번씩 번갈아 온다(1회차 D T D T …, 2회차 T D T D …). 재측정·이어서 실행·검증은 main과 같다.
#   --set disk-rest   휴식 시험(이어서 실행 없음 — 시간 간격이 측정값이라 중간부터 다시 잴 수 없다: results/<id>/rest/가 이미 있으면 거부):
#                     fio-1(--fio-size, 기본 15G) → 바로 fio-2 → --rest-s(기본 1800초) 휴식 → fio-3 → 바로(휴식 없이) 디스크 S4 1회(counter-upsert·default).
#                     결과 = results/<id>/rest/fio-N/(fio-disk.sh) · results/<id>/rest/counter-upsert-default-s4-afterfill/(run.sh). 계획 = steps.txt. --dry-run은 단계만 쓴다.
#                     fio-1 앞에 배포·이미지 빌드(compose build — DB는 띄우지 않는다: fio 단독 조건을 오염시키지 않게)를 미리 해 둔다. 그래도 S4는 compose up·health·예열·시드 뒤에
#                     k6를 시작하므로 '바로'가 아니다 — 단계 사이 실제 간격(앞 단계 부하 끝 → 이 단계 부하 시작, fio = fio 컨테이너 실행, S4 = k6 시작)을 rest/gaps.txt와
#                     CAMPAIGN.log(REST gap 행)에 남긴다(리뷰 rv8 A6). fio 단계는 --pre-s 2 --tail-s 5(간격을 줄이려고 — 로그 회수·시험 파일 지우기는 다음 단계 앞에 한다).
#   마감(리뷰 rv8 A9 — 명세 §2: 정기 fstrim 전에 **끝낸다**): main이 아닌 세트는 회차·단계마다 예상 끝(지금 + 계획 시간 + 여유 300초)이 --deadline(기본 2026-10-12T10:21:00+09:00)을
#     넘으면 시작하지 않고 'ABORT deadline' 한 줄 + exit 5. 끝난 뒤 지금 시각이 마감을 넘었거나 서버 fstrim.service 실행 시각(systemctl show — sudo 불필요)이
#     캠페인 시작 때와 달라졌으면 그 회차·단계의 status에 crossed-fstrim을 붙이고 exit 5(비교에서 빼게). 시작 때 전체 계획이 마감 안에 드는지 추정해 넘으면 경고.
#     마감 뒤 관찰은 --allow-after-fstrim을 줄 때만(그때는 거부·표시 대신 로그 한 줄).
#   재측정(main이 아닌 세트): status가 ok가 아닌 회차는 전부 다시 잰다(s4-no-successful-stage도 — 리뷰 rv8 A11).
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
SHA="" CAMPAIGN="" REPS="" SUITE=main DRY_RUN=0   # ADR-007 명세 §9.2: 전 조건 3회(main) · 후속 disk-cause 2회
SET=main FIO_SIZE=15G REST_S=1800   # ADR-007 후속(머리말)
DEADLINE="2026-10-12T10:21:00+09:00" ALLOW_AFTER=0   # ADR-007 후속 마감(머리말 — 리뷰 rv8 A9)
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --id) CAMPAIGN="$2"; shift 2 ;;
    --reps) REPS="$2"; shift 2 ;;   # 스모크용 — 본측정은 3
    --suite) SUITE="$2"; shift 2 ;;   # ADR-007은 main만
    --dry-run) DRY_RUN=1; shift ;;
    --set) SET="$2"; shift 2 ;;
    --fio-size) FIO_SIZE="$2"; shift 2 ;;
    --rest-s) REST_S="$2"; shift 2 ;;
    --deadline) DEADLINE="$2"; shift 2 ;;
    --allow-after-fstrim) ALLOW_AFTER=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
# 실행하는 하네스 = 기록되는 SHA
[[ "$SUITE" == main ]] || { echo "--suite main only (ADR-007)" >&2; exit 2; }
case "$SET" in main) REPS="${REPS:-3}" ;; disk-cause) REPS="${REPS:-2}" ;; disk-rest) REPS=1 ;; *) echo "--set main|disk-cause|disk-rest" >&2; exit 2 ;; esac
[[ "$REST_S" =~ ^[0-9]+$ ]] || { echo "--rest-s <초>" >&2; exit 2; }
[[ "$FIO_SIZE" =~ ^[0-9]+[KMG]$ ]] || { echo "--fio-size <정수><K|M|G>" >&2; exit 2; }   # 리뷰 rv8 A12 — fio-disk.sh로 넘기기 전에
DEADLINE_S="$(date -d "$DEADLINE" +%s 2>/dev/null)" || { echo "--deadline <ISO 시각>" >&2; exit 2; }
# dry-run은 아무것도 재지 않으므로(계획 파일만) 작업트리 하네스 검사를 건너뛴다
if (( ! DRY_RUN )) && { ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-007 ':(exclude)k6/ADR-007/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-007 ':(exclude)k6/ADR-007/results')" ]]; }; then
  echo "k6/ADR-007 differs from $SHA — commit first" >&2; exit 2
fi
if [[ "$SET" == main ]]; then CAMPAIGN="${CAMPAIGN:-$(date +%Y%m%d)-adr007-$SHA}"; else CAMPAIGN="${CAMPAIGN:-$(date +%Y%m%d)-adr007-$SET-$SHA}"; fi
ROOT="$ADR_DIR/results/$CAMPAIGN"
mkdir -p "$ROOT"
LOG="$ROOT/CAMPAIGN.log"

# 조건 목록(ADR-007 명세 §9.2 매트릭스, 머리말) — 이름  run.sh 인자. 묶음 = <설정>-<셀 이름표>
LIMITS="none counter-upsert counter-upsert-precheck"   # 순서 순환의 방식 목록 순서이기도 하다(머리말)
# 묶음 목록 순서 = 순서 순환의 묶음 순서 — 설정을 섞어 둔다(머리말: 회차마다 ⌈12 ÷ 3⌉ = 4칸씩 돌 때 한 1/3 안에 세 설정이 고루 들어가게)
# 묶음 하나에 셀 여럿 = '+'(S7-m1과 S7-m1-late — 둘 다 짧은 셀). 13묶음이면 어떤 회전 폭으로도 묶음마다 앞·가운데·뒤 1/3을 한 번씩 맡지 못한다(12묶음 × 4칸은 맡는다)
BUNDLES="default-s4 nockpt-s4 buf512-s4 default-s4c4200 nockpt-s4c4200 buf512-s4c4200 default-s4c2000 nockpt-s4c2000 buf512-s4c2000 default-s2 default-s7m1+s7m1late default-s3a20"
cell_of() {  # 셀 이름표 → run.sh 셀 이름
  case "$1" in s4) echo S4 ;; s4c4200) echo S4c4200 ;; s4c2000) echo S4c2000 ;; s2) echo S2 ;; s7m1) echo S7-m1 ;; s7m1late) echo S7-m1-late ;; s3a20) echo S3-a20 ;; *) return 1 ;; esac
}
SEAT="--strategy pessimistic-nowait --pool 10 --apps 1 --levels '4'"
# ---- ADR-007 후속 마감(머리말 — 리뷰 rv8 A9). main 세트는 쓰지 않는다 ----------------------------------------------------------------
MARGIN_S=300
FSTRIM0=""
fstrim_mark() {   # 서버 fstrim.service 마지막 실행 시각(읽기 전용 — sudo 불필요). 실패면 빈 값
  ( source "$DIR/lib.sh" > /dev/null 2>&1; set +e; remote "systemctl show fstrim.service -p ExecMainStartTimestamp --value" 2>/dev/null ) | tr -d '\r'
}
fio_planned_s() {  # fio 한 번 계획 시간: data job 크기 ÷ 35 MiB/s + 120초(이미지·표본·꼬리·정리)
  python3 -c 'import sys; s=sys.argv[1]; n,u=int(s[:-1]),s[-1]; print(int(n*1024**"KMG".index(u)*1024/(35*2**20))+120)' "$FIO_SIZE"
}
rep_planned_s() { case "$1" in *-s4|*-s4-*) echo 720 ;; *) echo 660 ;; esac; }   # 회차 한 번(배포 끝난 뒤): S4 약 10.3분 · S4c 약 9.5분 → 반올림
guard_start() {  # 인자: 이름 계획초 — 예상 끝이 마감을 넘으면 시작하지 않는다(exit 5)
  (( ALLOW_AFTER )) && return 0
  local now; now=$(date +%s)
  if (( now + $2 + MARGIN_S > DEADLINE_S )); then
    echo "$(date -Is) ABORT deadline: $1 예상 끝 $(date -d "@$((now + $2 + MARGIN_S))" -Is)(계획 $2초 + 여유 ${MARGIN_S}초) > 마감 $DEADLINE — 시작하지 않음" >> "$LOG"
    echo "deadline: $1 시작 거부" >&2; exit 5
  fi
  if [[ -n "$FSTRIM0" ]]; then
    local f; f="$(fstrim_mark)"
    if [[ -n "$f" && "$f" != "$FSTRIM0" ]]; then echo "$(date -Is) ABORT fstrim-ran: $f (캠페인 시작 때 $FSTRIM0) — $1 시작하지 않음" >> "$LOG"; exit 5; fi
  fi
}
mark_crossed() {  # 인자: 이름 status파일 — 끝난 뒤 마감을 넘었거나 fstrim이 돌았으면 crossed-fstrim을 붙이고 exit 5
  local now f st crossed=""; now=$(date +%s); f="$(fstrim_mark)"
  (( now > DEADLINE_S )) && crossed="끝 $(date -Is) > 마감 $DEADLINE"
  [[ -n "$FSTRIM0" && -n "$f" && "$f" != "$FSTRIM0" ]] && crossed="${crossed:+$crossed · }fstrim 실행 $f"
  [[ -n "$crossed" ]] || return 0
  if (( ALLOW_AFTER )); then echo "$(date -Is) POST-CUTOFF $1 ($crossed) — --allow-after-fstrim이라 표시하지 않음" >> "$LOG"; return 0; fi
  if [[ -f "$2" ]]; then st="$(cat "$2")"; [[ "$st" == ok ]] && st=crossed-fstrim || st="$st,crossed-fstrim"; echo "$st" > "$2"; fi
  echo "$(date -Is) ABORT crossed-fstrim: $1 ($crossed) — status에 crossed-fstrim" >> "$LOG"; exit 5
}
dc_planned_s() { local n t=120; for n in $(awk '!/^#/ {print $4}' "$ROOT/order.txt"); do t=$(( t + $(rep_planned_s "$n") )); done; echo "$t"; }   # 120 = 배포
plan_check() {  # 인자: 계획 총초 — 전체가 마감 안에 드는지 추정(넘으면 경고만 — 회차마다 guard_start가 막는다)
  local now; now=$(date +%s)
  local msg="계획 추정 $(( $1 / 60 ))분 → 예상 끝 $(date -d "@$((now + $1))" -Is) · 마감 $DEADLINE"
  if (( now + $1 > DEADLINE_S )); then echo "$(date -Is) WARN deadline: $msg — 마감 전에 다 끝나지 않는다" >> "$LOG"; echo "WARN: $msg — 마감 초과" >&2
  else echo "$(date -Is) PLAN $msg" >> "$LOG"; echo "$msg" >&2; fi
}
# ---- ADR-007 후속 휴식 시험(--set disk-rest, 머리말) — 조건 목록·순서 순환을 쓰지 않는 별도 경로 -----------------------------------
if [[ "$SET" == disk-rest ]]; then
  REST_ROOT="$ROOT/rest"
  REP_NAME=counter-upsert-default-s4-afterfill
  REP_ARGS=(--limit-strategy counter-upsert --pg-preset default --strategy pessimistic-nowait --pool 10 --apps 1 --levels 4 --cells S4)
  FIO_ARGS=(--sha "$SHA" --size "$FIO_SIZE" --pre-s 2 --tail-s 5)
  STEPS=("fio-1 fio ${FIO_ARGS[*]}" "fio-2 fio ${FIO_ARGS[*]}" "rest-${REST_S}s rest $REST_S" "fio-3 fio ${FIO_ARGS[*]}" "$REP_NAME rep ${REP_ARGS[*]}")
  FIO_S=$(fio_planned_s); REST_PLAN=$(( 180 + FIO_S * 3 + REST_S + $(rep_planned_s "$REP_NAME") ))   # 180 = 배포·이미지 빌드
  if (( DRY_RUN )); then
    printf '%s\n' "${STEPS[@]}" > "$ROOT/steps.txt"
    echo "$(date -Is) DRY-RUN set=disk-rest sha=$SHA steps=${#STEPS[@]} — steps.txt만 썼다" >> "$LOG"
    cat "$ROOT/steps.txt"
    echo "계획(disk-rest): 단계 ${#STEPS[@]}개 — fio 3회(각 $FIO_SIZE) · 휴식 ${REST_S}초 · S4 1회(fio-3 뒤 — 배포·빌드는 미리, compose up·시드 간격은 gaps.txt)" >&2
    plan_check "$REST_PLAN"
    exit 0
  fi
  [[ -e "$REST_ROOT" ]] && { echo "$REST_ROOT 이미 있음 — 휴식 시험은 이어서 실행하지 않는다(새 --id로)" >&2; exit 2; }
  mkdir -p "$REST_ROOT"; printf '%s\n' "${STEPS[@]}" > "$ROOT/steps.txt"
  FSTRIM0="$(fstrim_mark)"
  echo "$(date -Is) REST start sha=$SHA steps=${#STEPS[@]} fstrim_last=${FSTRIM0:-unknown}" >> "$LOG"
  plan_check "$REST_PLAN"
  guard_start "rest-protocol(전체)" "$REST_PLAN"
  # 배포 + 앱 이미지 빌드만 미리(DB는 띄우지 않는다 — 머리말)
  if ( source "$DIR/lib.sh"; set +e; deploy_sha "$SHA" && remote "cd $REMOTE_BASE/$SHA && docker compose -p seatlab -f $COMPOSE_FILE build" ) >> "$REST_ROOT/prestage.log" 2>&1; then
    echo "$(date -Is) REST prestage ok (deploy + compose build)" >> "$LOG"
  else
    echo "$(date -Is) ABORT REST prestage failed" >> "$LOG"; exit 3
  fi
  iso_s() { python3 -c 'import sys, datetime; print(datetime.datetime.fromisoformat(sys.argv[1]).timestamp())' "$1" 2>/dev/null; }
  gap() {  # 인자: 앞 이름 앞 끝(epoch) 이 이름 이 시작(epoch) 설명
    local g; g=$(python3 -c 'import sys; print(f"{float(sys.argv[2]) - float(sys.argv[1]):.1f}")' "$2" "$4")
    echo "$1 → $3 = ${g}s ($5)" >> "$REST_ROOT/gaps.txt"; echo "$(date -Is) REST gap $1 → $3 = ${g}s ($5)" >> "$LOG"
  }
  rest_fail=0 prev="" prev_end=""
  for step in "${STEPS[@]}"; do
    read -r sname skind _ <<< "$step"
    case "$skind" in
      fio)  guard_start "$sname" "$FIO_S"
            echo "$(date -Is) REST $sname start" >> "$LOG"
            bash "$DIR/fio-disk.sh" --id "$CAMPAIGN/rest" --name "$sname" "${FIO_ARGS[@]}" >> "$REST_ROOT/$sname.runner.log" 2>&1; rc=$?
            echo "$(date -Is) REST $sname exit=$rc status=$(cat "$REST_ROOT/$sname/status" 2>/dev/null || echo none)" >> "$LOG"
            fs=$(iso_s "$(jq -r '.fio_started // empty' "$REST_ROOT/$sname/meta.json" 2>/dev/null)"); fe=$(iso_s "$(jq -r '.fio_ended // empty' "$REST_ROOT/$sname/meta.json" 2>/dev/null)")
            [[ -n "$prev" && -n "$prev_end" && -n "$fs" ]] && gap "$prev" "$prev_end" "$sname" "$fs" "앞 부하 끝 → fio 컨테이너 실행 — 꼬리·정리·이미지 확인·표본 시작·기준선 포함"
            mark_crossed "$sname" "$REST_ROOT/$sname/status"
            # fio 하나라도 실패하면 뒤 단계의 '바로 다음'·'휴식 뒤' 의미가 깨진다 — 멈춘다
            (( rc == 0 )) || { echo "$(date -Is) ABORT REST $sname" >> "$LOG"; exit 1; }
            prev="$sname" prev_end="$fe" ;;
      rest) guard_start "$sname" "$REST_S"
            echo "$(date -Is) REST $sname start" >> "$LOG"
            sleep "$REST_S"; echo "$(date -Is) REST $sname done" >> "$LOG" ;;   # 간격은 다음 fio의 gap 행이 앞 fio 끝부터 잰다
      rep)  guard_start "$sname" "$(rep_planned_s "$sname")"
            echo "$(date -Is) REST $sname start" >> "$LOG"
            bash "$DIR/run.sh" --sha "$SHA" --id "$CAMPAIGN/rest/$sname" --reps 1 --only-rep 1 "${REP_ARGS[@]}" >> "$REST_ROOT/$sname.runner.log" 2>&1; rc=$?
            stf="$(ls "$REST_ROOT/$sname"/L*/*/rep1/status 2>/dev/null | head -1)"; st="$(cat "$stf" 2>/dev/null || echo none)"
            echo "$(date -Is) REST $sname exit=$rc status=$st" >> "$LOG"
            m="$(dirname "${stf:-/nonexistent/x}")/meta.json"
            rs=$(iso_s "$(jq -r '.started // empty' "$m" 2>/dev/null)"); ks=$(iso_s "$(jq -r '.k6_started // empty' "$m" 2>/dev/null)")
            [[ -n "$prev_end" && -n "$rs" ]] && gap "$prev" "$prev_end" "$sname(회차 시작)" "$rs" "fio 끝 → run.sh 회차 시작 — fio 꼬리·정리·run.sh 기동"
            [[ -n "$prev_end" && -n "$ks" ]] && gap "$prev" "$prev_end" "$sname(k6 시작)" "$ks" "fio 끝 → k6 시작 — 위 + compose down/up·health·예열·시드(200만 석)·표본 대기"
            mark_crossed "$sname" "$stf"
            [[ "$rc" == 0 && "$st" == ok ]] || rest_fail=1 ;;
    esac
  done
  echo "$(date -Is) REST done fail=$rest_fail" >> "$LOG"
  exit "$rest_fail"
fi
CONDITIONS=()
if [[ "$SET" == disk-cause ]]; then
  # 재측정 24회(머리말) — 셀 · 방식 · 설정(default = 디스크, tmpfs) 순서가 조건 목록 순서이자 순서 회전의 기준
  DC_TAGS="s4 s4c4200" DC_PRESETS="default tmpfs"
  for tag in $DC_TAGS; do
    cell="$(cell_of "$tag")"
    for l in $LIMITS; do for preset in $DC_PRESETS; do CONDITIONS+=("$l-$preset-$tag --limit-strategy $l --pg-preset $preset $SEAT --cells '$cell'"); done; done
  done
else
for b in $BUNDLES; do
  preset="${b%%-*}"
  IFS='+' read -ra tags <<< "${b#*-}"
  for tag in "${tags[@]}"; do
    cell="$(cell_of "$tag")" || { echo "묶음 $b: 셀 이름표 $tag 모름" >&2; exit 2; }
    for l in $LIMITS; do CONDITIONS+=("$l-$preset-$tag --limit-strategy $l --pg-preset $preset $SEAT --cells '$cell'"); done
  done
done
fi
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
# (disk-cause: 셀·방식·설정 순서를 각각 (k−1) 회전 — 머리말)
if [[ "$SET" == disk-cause ]]; then
python3 -c 'import sys
reps, methods, tags, presets = int(sys.argv[1]), sys.argv[2].split(), sys.argv[3].split(), sys.argv[4].split()
rot = lambda xs, k: xs[k % len(xs):] + xs[:k % len(xs)]
seq = 0
for k in range(1, reps + 1):
    pos = 0
    for t in rot(tags, k - 1):
        for m in rot(methods, k - 1):
            for p in rot(presets, k - 1):
                pos += 1; seq += 1
                print(k, pos, seq, f"{m}-{p}-{t}")' "$REPS" "$LIMITS" "$DC_TAGS" "$DC_PRESETS" > "$ORDER_BODY" \
  || order_fail "python3 종료 코드 $?"
else
python3 -c 'import sys
reps, methods, bundles = int(sys.argv[1]), sys.argv[2].split(), sys.argv[3].split()
rot = lambda xs, k: xs[k % len(xs):] + xs[:k % len(xs)]
step = -(-len(bundles) // reps)
seq = 0
for k in range(1, reps + 1):
    pos = 0
    for b in rot(bundles, (k - 1) * step):
        preset, tags = b.split("-", 1)
        for t in tags.split("+"):
            for m in rot(methods, k - 1):
                pos += 1; seq += 1
                print(k, pos, seq, f"{m}-{preset}-{t}")' "$REPS" "$LIMITS" "$BUNDLES" > "$ORDER_BODY" \
  || order_fail "python3 종료 코드 $?"
fi
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
  if [[ "$SET" == disk-cause ]]; then
    echo "# ADR-007 후속 disk-cause 조건 순서 — 회차 k = 셀($DC_TAGS)·방식($LIMITS)·설정($DC_PRESETS) 순서를 각각 (k−1) 회전 — 디스크·tmpfs 번갈아 (campaign.sh 머리말)"
  else
  echo "# ADR-007 조건 순서 — 균형 순환: 회차 k = 묶음($BUNDLES) 순서 (k−1)×⌈묶음 수÷회차 수⌉ 회전 · 묶음 안 방식($LIMITS) 순서 (k−1) 회전 (campaign.sh 머리말)"
  fi
  echo "# 회차 회차안순번 전체순번 조건"
  cat "$ORDER_BODY"
} > "$ORDER_NEW"
rm -f "$ORDER_BODY"
if [[ -f "$ROOT/order.txt" ]] && ! diff -q "$ORDER_NEW" "$ROOT/order.txt" > /dev/null; then
  echo "order.txt가 다시 계산한 순서와 다르다(조건 목록·회차 수·순서 규칙 변경) — 이어서 실행 거부" >&2; rm -f "$ORDER_NEW"; exit 2
fi
mv "$ORDER_NEW" "$ROOT/order.txt"
if (( DRY_RUN )); then
  echo "$(date -Is) DRY-RUN $([[ "$SET" != main ]] && echo "set=$SET ")sha=$SHA reps=$REPS order=rotation conditions=${#CONDITIONS[@]} planned_calls=$total — conditions.txt·order.txt만 썼다" >> "$LOG"
  for rep in $(seq 1 "$REPS"); do echo "$(date -Is) ORDER rep$rep: $(awk -v r="$rep" '$1 == r {printf "%s ", $4}' "$ROOT/order.txt")" >> "$LOG"; done
  cat "$ROOT/order.txt"
  echo "계획: 조건 ${#CONDITIONS[@]}개 × ${REPS}회 = ${total}회 호출" >&2
  [[ "$SET" != main ]] && plan_check "$(dc_planned_s)"
  exit 0
fi

if [[ "$SET" != main ]]; then FSTRIM0="$(fstrim_mark)"; (( DRY_RUN )) || echo "$(date -Is) fstrim_last=${FSTRIM0:-unknown}" >> "$LOG"; fi
INFRA_STREAK=0
run_condition_rep() {  # 인자: 조건 한 줄, 회차. run.sh가 0이 아니면(인자·SHA·배포) 캠페인을 멈춘다 — 조용히 넘어가지 않게
  local line="$1" rep="$2" name="${1%% *}" args="${1#* }" resume="" rc
  [[ "$SET" != main ]] && guard_start "$name rep$rep" "$(rep_planned_s "$name")"   # ADR-007 후속 마감(머리말)
  [[ -f "$ROOT/$name/plan.json" ]] && resume="--resume"
  local mark; mark="$(mktemp)"   # 이 호출이 쓴 status만 세려고(재측정 전의 옛 실패를 연속 실패로 세지 않게)
  echo "$(date -Is) $name rep$rep start $resume" >> "$LOG"
  eval bash "$DIR/run.sh" --sha "$SHA" --id "$CAMPAIGN/$name" --reps "$REPS" --only-rep "$rep" $args $resume >> "$ROOT/$name.runner.log" 2>&1
  rc=$?
  echo "$(date -Is) $name rep$rep exit=$rc" >> "$LOG"
  if (( rc != 0 )); then echo "$(date -Is) ABORT $name rep$rep exit=$rc" >> "$LOG"; exit "$rc"; fi
  [[ "$SET" != main ]] && mark_crossed "$name rep$rep" "$(ls "$ROOT/$name"/L*/*/rep"$rep"/status 2>/dev/null | head -1)"
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
  # ADR-007 후속 세트는 ok만 정상(리뷰 rv8 A11 — s4-no-successful-stage도 다시 잰다)
  local okre='ok|s4-no-successful-stage'; [[ "$SET" != main ]] && okre='ok'
  grep -LxsE "$okre" "$ROOT"/*/L*/*/rep*/status 2>/dev/null | grep -vE '\.(path-gap|retry|incomplete)-' || true
}

echo "$(date -Is) CAMPAIGN start $([[ "$SET" != main ]] && echo "set=$SET ")sha=$SHA suite=$SUITE reps=$REPS order=rotation conditions=${#CONDITIONS[@]} planned_calls=$total" >> "$LOG"
[[ "$SET" != main ]] && plan_check "$(dc_planned_s)"
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
