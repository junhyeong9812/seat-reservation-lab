#!/usr/bin/env bash
# ADR-009 측정 캠페인(명세 §5·§10·§11 · 설계 §10 매트릭스): 조건(이름)마다 run.sh를 부른다. 조건마다 결과 폴더가 따로 생긴다.
# ADR-008 campaign.sh 골격(회차 우선 실행·이어서 실행·순서 기록·재측정 규칙) — 조건 목록·순서·합격 사유만 ADR-009로 바꿨다.
# 좌석 전략 pessimistic-nowait(3b)·풀 10·앱 1대·인덱스 V2·배경 0·L4.
#
#   scripts/campaign.sh --sha <SHA> [--id <campaign-id>]
#   scripts/campaign.sh --sha <SHA> --id <id> --dry-run     # 조건·순서 계획(conditions.txt·order.txt) + 예상 시간만 — 측정 없음(84행)
#   scripts/campaign.sh --set cost-remeasure --sha <SHA> [--id <id>] [--dry-run]   # 명세 §13 비용 셀 재측정 48회(아래 'cost-remeasure')
#   scripts/campaign.sh --set jvm-baseline --sha <SHA> [--id <id>] [--dry-run]     # ADR-010 앱 JVM 기준 4회(아래 'jvm-baseline')
#   scripts/campaign.sh --set jvm-ladder --sha <SHA> [--id <id>] [--dry-run]       # ADR-010 2차 — G1·ZGC × 1g·2g·4g × 3회 = 18(아래 'jvm-ladder')
#   scripts/campaign.sh --set k6ver --sha <SHA> [--k6-v220 <경로>] [--k6-v230 k6] [--probe-k6-bin <경로>] [--id <id>] [--dry-run]   # ADR-011 E1(아래 'k6ver')
#   scripts/campaign.sh --set pretouch --sha <SHA> --k6-bin <경로> [--probe-k6-bin <경로>] [--id <id>] [--dry-run]                 # ADR-011 E2(아래 'pretouch')
#   scripts/campaign.sh --set soak --sha <SHA> --k6-bin <경로> [--probe-k6-bin <경로>] [--id <id>] [--dry-run]                     # ADR-011 E3(아래 'soak')
#   --set main(기본 — 아래 84회 계획, 명세 §13 전 본측정과 같은 conditions.txt·order.txt) | cost-remeasure | jvm-baseline | jvm-ladder | k6ver | pretouch | soak
#
# 계획(설계 §10, 명세 §11 측정 조건 갱신 — C·F = A+C·A+F, B+C는 효과 셀만) — 조건 이름 = <조건>-<셀 묶음>, 각 3회:
#   비용  {control, A, B, A+C, A+F} × {s4c-disk(S4c4200 디스크) · s4c-tmpfs(S4c4200 tmpfs) · s2(S2) · s3a20(S3-a20)}  = 60
#   마감 끔 대조  control-noclose × s4c-disk                                                                        =  3
#   효과  {control, A, A+C, A+F, B+C} × s3e(S3e-a20 = S3-a20 + 어긋남 주입)                                          = 15
#   마감 임박  control × s3c(S3c-a20) × 정리 {schedule, zero}                                                      =  6
#                                                                                                          합계 84
# 순서 = 회차 우선 + 균형 회전(같은 셀 묶음의 조건은 붙여서 — 조건 비교가 같은 시간대에 들게):
#   묶음 순서 BLOCKS를 회차 k마다 2(k−1)칸 회전(6묶음 × 3회차 — 각 묶음이 회차마다 다른 3분의 1 구간에 온다),
#   묶음 안 조건 순서를 회차 k마다 ⌈n/3⌉(k−1)칸 회전(같은 조건이 늘 묶음 첫머리에 오지 않게). 난수 없이 회차 번호만으로 정해진다.
#   실제 순서는 order.txt(회차 · 회차 안 순번 · 전체 순번 · 조건)와 CAMPAIGN.log(ORDER 행). 이어서 실행할 때 다시 계산한 순서가 order.txt와 다르면 멈춘다.
# 중단되면 같은 --id로 다시 부르면 된다(각 조건 run.sh --resume, 끝난 회차는 건너뛴다).
# 재측정(최대 2바퀴): 정확히 ok가 아닌 회차 — 단 **결과 사유**(ACCEPT_RE — 측정 결과이지 하네스 실패가 아니다)가 있거나 판정기 위반(매수 초과 · 카운터 불일치 — 효과 셀은
#   주입 밖 쌍 어긋남·오탐 복구)이 찍힌 회차는 다른 사유가 무엇이든 다시 재지 않는다(다시 재서 ok가 나오면 실패가 가려진다). 남으면 exit 1.
# cost-remeasure(명세 §13 — 교란 수정 뒤 비용 셀만): {control, A, A+C, A+F} × {s4c-disk · s4c-tmpfs · s2 · s3a20} × 3 = 48.
#   순서 = 회차 우선 + 균형 회전: 묶음 4개를 회차 k마다 (k−1)칸, 묶음 안 조건 4개를 회차 k마다 (k−1)칸 회전(4개 × 3회차 — 같은 묶음·조건이 회차마다 다른 자리).
#   판정은 이 캠페인 회차끼리만(옛 캠페인과 섞지 않는다 — 명세 §13). 기본 id = <날짜>-adr009-remeasure-<SHA>
# jvm-baseline(ADR-010 — 앱 JVM GC·메모리 기준): 앱 기본값 조건(default) × S4c4200 tmpfs × 앱 JVM {1g·auto, 1g·g1, 1792m·auto, 2g·auto} × 1회 = 4.
#   순서 고정(1회 — 회전 없음, ADR-010 §5 한계). 기본 id = <날짜>-adr010-jvm-<SHA>
# jvm-ladder(ADR-010 2차 — 명세 §11): default × S4c4200 tmpfs × {1g,2g,4g}-{g1,zgc} × 3회 = 18. 회차 우선 · 조건 6개를 회차마다 2(k−1)칸 회전. 기본 id = <날짜>-adr010-jvm2-<SHA>
# ADR-011(측정 환경 꼬리 지연 — ADR-011 §3): 모두 default × S4c4200 tmpfs × 세대형 ZGC · 회차 우선 · 조건 순서 고정(회전 없음 — 회차마다 A B → A B A B …, ABAB).
#   k6ver(E1): 2g × 주 k6 {v220, v230, v230gc} × 3 = 9 — 모두 --probes, Go GC 기록(--gctrace)은 v230gc만(스모크에서 기록 자체가 주 k6를 흔든 정황 — ADR-011 §3). 바이너리 = --k6-v220(기본 아래 K6_V220) · --k6-v230(기본 k6 = 시스템 v2.3.0) — 시작 전에 version 대조. 기본 id = <날짜>-adr011-k6ver-<SHA>
#   pretouch(E2): 4g × {none, xms(--app-xms-full — 초기 힙 = 최대), pt(--app-xms-full --app-pretouch — 힙 전체를 기동 때 미리 건드림)} × 3 = 9 — --probes. 주 k6 = --k6-bin(필수 — E1 결과로 정한다). 기본 id = <날짜>-adr011-pretouch-<SHA>
#   soak(E3): 2g × 2회 — --probes · --s4c-steps 60(30분) · --seed-schedules 800(800만 석). 주 k6 = --k6-bin(필수). 기본 id = <날짜>-adr011-soak-<SHA>
#   측정기 바이너리는 run.sh 기본(v2.2.0) — --probe-k6-bin을 주면 조건 인자에 붙는다. 비교 = scripts/tail.py --set <세트>
# 시한: 시드 회차 starts_at 2026-12-31 19:00+09 — run.sh가 그 1시간 전부터 회차를 거부한다(seed-time-bomb). 캠페인은 약 19.5시간.
set -uo pipefail
if [[ "${CAMPAIGN_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"
  frozen="$(mktemp -d)"
  cp -r "$src" "$frozen/scripts"
  export CAMPAIGN_FROZEN=1 ADR_DIR="$(cd "$src/.." && pwd)"
  export REPO_ROOT="$(cd "$ADR_DIR/../.." && pwd)"
  exec bash "$frozen/scripts/campaign.sh" "$@"
fi
DIR="$(cd "$(dirname "$0")" && pwd)"   # 동결 사본의 scripts/
SHA="" CAMPAIGN="" DRY_RUN=0 SET=main
# ADR-011 k6ver 기본: v2.2.0 = 안정 경로(공식 k6-v2.2.0-linux-amd64.tar.gz — checksums.txt sha256 b5a8003c… 확인 뒤 푼 바이너리) · v2.3.0 = 시스템 k6.
#   v2.2.0 바이너리 sha256(K6_V220_SHA256)을 시작 전에 대조한다 — 다른 빌드가 'v2.2.0'으로 섞이지 않게(run.sh는 plan.json k6_sha256으로 회차마다 대조)
K6_V220="$HOME/.local/opt/k6-v2.2.0/k6" K6_V230=k6
K6_V220_SHA256=9fced9ff140d73bf3d69b3cde2493adce3807b35ab210a5cb0771045d0ad5a29
K6_BIN_ARG="" PROBE_BIN_ARG="" CLI_K6=""
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --id) CAMPAIGN="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --set) SET="$2"; shift 2 ;;
    --k6-bin) K6_BIN_ARG="$2"; CLI_K6+=" k6-bin"; shift 2 ;;
    --k6-v220) K6_V220="$2"; CLI_K6+=" k6-v220"; shift 2 ;;
    --k6-v230) K6_V230="$2"; CLI_K6+=" k6-v230"; shift 2 ;;
    --probe-k6-bin) PROBE_BIN_ARG="$2"; CLI_K6+=" probe-k6-bin"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
[[ "$SET" =~ ^(main|cost-remeasure|jvm-baseline|jvm-ladder|k6ver|pretouch|soak)$ ]] || { echo "--set main|cost-remeasure|jvm-baseline|jvm-ladder|k6ver|pretouch|soak" >&2; exit 2; }
# ADR-011 바이너리 인자: 세트마다 받는 것만(다른 세트에 주면 조용히 버리지 않고 거부) · 경로는 eval되는 조건 줄에 들어가므로 안전한 글자만 · version을 시작 전에 확인
case "$SET" in
  k6ver) for k in $CLI_K6; do [[ "$k" == k6-bin ]] && { echo "--set k6ver는 --k6-bin 대신 --k6-v220·--k6-v230" >&2; exit 2; }; done ;;
  pretouch|soak) [[ -n "$K6_BIN_ARG" ]] || { echo "--set $SET는 --k6-bin <경로>가 필요하다(E1 결과로 정한 주 k6)" >&2; exit 2; }
                 for k in $CLI_K6; do [[ "$k" == k6-v2* ]] && { echo "--set $SET에는 --k6-v220·--k6-v230이 없다(--k6-bin)" >&2; exit 2; }; done ;;
  *) [[ -z "$CLI_K6" ]] || { echo "--k6-bin·--k6-v220·--k6-v230·--probe-k6-bin은 ADR-011 세트(k6ver·pretouch·soak)에서만" >&2; exit 2; } ;;
esac
for b in "$K6_BIN_ARG" "$K6_V220" "$K6_V230" "$PROBE_BIN_ARG"; do
  [[ -z "$b" || "$b" =~ ^[A-Za-z0-9._/+-]+$ ]] || { echo "바이너리 경로에 쓸 수 없는 글자: $b" >&2; exit 2; }
done
k6ver_of() { "$1" version 2>/dev/null | head -1; }
case "$SET" in
  k6ver)
    [[ "$(k6ver_of "$K6_V220")" == "k6 v2.2.0 "* ]] || { echo "--k6-v220 $K6_V220: version이 v2.2.0이 아니다($(k6ver_of "$K6_V220"))" >&2; exit 2; }
    [[ "$(k6ver_of "$K6_V230")" == "k6 v2.3.0 "* ]] || { echo "--k6-v230 $K6_V230: version이 v2.3.0이 아니다($(k6ver_of "$K6_V230"))" >&2; exit 2; }
    v220_sha="$(sha256sum "$(command -v "$K6_V220")" 2>/dev/null | cut -d' ' -f1)"
    [[ "$v220_sha" == "$K6_V220_SHA256" ]] || { echo "--k6-v220 $K6_V220: sha256 ${v220_sha:-없음} ≠ 공식 v2.2.0 $K6_V220_SHA256" >&2; exit 2; } ;;
  pretouch|soak) [[ "$(k6ver_of "$K6_BIN_ARG")" == "k6 v"* ]] || { echo "--k6-bin $K6_BIN_ARG: version을 읽지 못함" >&2; exit 2; } ;;
esac
if [[ -n "$PROBE_BIN_ARG" ]] && [[ "$(k6ver_of "$PROBE_BIN_ARG")" != "k6 v"* ]]; then echo "--probe-k6-bin $PROBE_BIN_ARG: version을 읽지 못함" >&2; exit 2; fi
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
if (( ! DRY_RUN )) && { ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-009 ':(exclude)k6/ADR-009/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-009 ':(exclude)k6/ADR-009/results')" ]]; }; then
  echo "k6/ADR-009 differs from $SHA — commit first" >&2; exit 2
fi
case "$SET" in k6ver|pretouch|soak) DEF_ID="$(date +%Y%m%d)-adr011-$SET-$SHA" ;; jvm-baseline) DEF_ID="$(date +%Y%m%d)-adr010-jvm-$SHA" ;; jvm-ladder) DEF_ID="$(date +%Y%m%d)-adr010-jvm2-$SHA" ;; cost-remeasure) DEF_ID="$(date +%Y%m%d)-adr009-remeasure-$SHA" ;; *) DEF_ID="$(date +%Y%m%d)-adr009-$SHA" ;; esac
CAMPAIGN="${CAMPAIGN:-$DEF_ID}"
ROOT="$ADR_DIR/results/$CAMPAIGN"
mkdir -p "$ROOT"
LOG="$ROOT/CAMPAIGN.log"

# 조건 목록(머리말) — 이름 · run.sh 인자. 셀 묶음별 조건 순서는 BLOCK_<묶음>
REPS=3
SEAT="--strategy pessimistic-nowait --pool 10 --levels '4'"
BLOCKS="s4c-disk s4c-tmpfs s2 s3a20 s3e s3c"
BLOCK_s4c_disk="control control-noclose A B A+C A+F"
BLOCK_s4c_tmpfs="control A B A+C A+F"
BLOCK_s2="control A B A+C A+F"
BLOCK_s3a20="control A B A+C A+F"
BLOCK_s3e="control A A+C A+F B+C"
BLOCK_s3c="schedule zero"
EXPECT_TOTAL=84 BLOCK_STEP=2 COND_STEP_DIV=3   # main: 묶음 2(k−1)칸 · 조건 ⌈n/3⌉(k−1)칸(머리말)
COND_ROT=1   # ADR-011 세트는 0(조건 순서 고정 — ABAB). 그 밖은 1(이전 회전 그대로)
if [[ "$SET" == cost-remeasure ]]; then
  BLOCKS="s4c-disk s4c-tmpfs s2 s3a20"
  BLOCK_s4c_disk="control A A+C A+F" BLOCK_s4c_tmpfs="control A A+C A+F" BLOCK_s2="control A A+C A+F" BLOCK_s3a20="control A A+C A+F"
  EXPECT_TOTAL=48 BLOCK_STEP=1 COND_STEP_DIV=4   # 묶음 (k−1)칸 · 조건 ⌈4/4⌉(k−1) = (k−1)칸
fi
if [[ "$SET" == jvm-baseline ]]; then
  REPS=1 BLOCKS="jvm" BLOCK_jvm="1g-auto 1g-g1 1792m-auto 2g-auto"
  EXPECT_TOTAL=4 BLOCK_STEP=0 COND_STEP_DIV=1   # 1회 — 회전 없음
fi
if [[ "$SET" == jvm-ladder ]]; then
  REPS=3 BLOCKS="jvm" BLOCK_jvm="1g-g1 2g-g1 4g-g1 1g-zgc 2g-zgc 4g-zgc"
  EXPECT_TOTAL=18 BLOCK_STEP=0 COND_STEP_DIV=3   # 조건 ⌈6/3⌉(k−1) = 2(k−1)칸
fi
# ADR-011(머리말): 묶음 하나 · 회전 없음(COND_ROT=0 — 회차마다 같은 순서라 회차 우선이면 A B A B …)
[[ "$SET" == k6ver ]] && { REPS=3 BLOCKS="k6ver" BLOCK_k6ver="v220 v230 v230gc" EXPECT_TOTAL=9 BLOCK_STEP=0 COND_STEP_DIV=1 COND_ROT=0; }
[[ "$SET" == pretouch ]] && { REPS=3 BLOCKS="pretouch" BLOCK_pretouch="none xms pt" EXPECT_TOTAL=9 BLOCK_STEP=0 COND_STEP_DIV=1 COND_ROT=0; }
[[ "$SET" == soak ]] && { REPS=2 BLOCKS="soak" BLOCK_soak="2g-zgc" EXPECT_TOTAL=2 BLOCK_STEP=0 COND_STEP_DIV=1 COND_ROT=0; }
ADR011_BASE="--cond default --cells 'S4c4200' --pg-preset tmpfs --app-gc zgc"
PROBE_TAIL="${PROBE_BIN_ARG:+ --probe-k6-bin $PROBE_BIN_ARG}"
args_of() {  # 인자: 묶음 조건 → run.sh 인자
  case "$1" in
    s4c-disk)  echo "--cond $2 --cells 'S4c4200' --pg-preset default" ;;
    s4c-tmpfs) echo "--cond $2 --cells 'S4c4200' --pg-preset tmpfs" ;;
    s2)        echo "--cond $2 --cells 'S2' --pg-preset default" ;;
    s3a20)     echo "--cond $2 --cells 'S3-a20' --pg-preset default" ;;
    s3e)       echo "--cond $2 --cells 'S3e-a20' --pg-preset default" ;;
    s3c)       echo "--cond control --cleanup $2 --cells 'S3c-a20' --pg-preset default" ;;
    jvm)       echo "--cond default --cells 'S4c4200' --pg-preset tmpfs --app-mem ${2%-*} --app-gc ${2##*-}" ;;   # 조건 = <메모리>-<수집기>
    # ADR-011(머리말) — 조건 줄에 바이너리 경로까지 든다(이어서 실행 때 conditions.txt 대조 · run.sh가 plan.json k6_bin·k6_version 대조)
    k6ver)     echo "$ADR011_BASE --app-mem 2g --k6-bin $([[ "$2" == v220 ]] && echo "$K6_V220" || echo "$K6_V230") --probes$([[ "$2" == v230gc ]] && echo " --gctrace")$PROBE_TAIL" ;;
    pretouch)  echo "$ADR011_BASE --app-mem 4g --k6-bin $K6_BIN_ARG --probes$(case "$2" in xms) echo " --app-xms-full" ;; pt) echo " --app-xms-full --app-pretouch" ;; esac)$PROBE_TAIL" ;;
    soak)      echo "$ADR011_BASE --app-mem 2g --k6-bin $K6_BIN_ARG --probes --s4c-steps 60 --seed-schedules 800$PROBE_TAIL" ;;
  esac
}
name_of() { case "$1" in s3c) echo "close-$2-s3c" ;; jvm) echo "$2-jvm" ;; *) echo "$2-$1" ;; esac; }
# 1회 예상 분(설계 §10 실측 출처: S2 1.3 · S3-a20 21.8 · S4c4200 9.6 — S3e·S3c는 S3-a20과 같은 길이) + C·F 첫 대조 바퀴 대기(최대 2분, 평균 1분으로 셈)
# 명세 §13 뒤 하네스는 모든 조건이 시드 끝 → 첫 요청 87초(PRE_K6_TARGET_S)를 거친다 — 옛 대기(대조 없음 약 17초 · 대조 약 45초)보다 1회당 약 1.2분·0.7분 길다.
#   main의 order.txt(예상분 열)는 바꾸지 않는다(명세 §13 전 계획과 바이트가 같아야 한다) — 보정 예상은 dry-run 끝 줄에 따로. cost-remeasure는 보정 값(모든 조건 + 1.2분)
minutes_of() {
  local m; case "$1" in s4c-*|jvm|k6ver|pretouch|soak) m=9.6 ;; s2) m=1.3 ;; *) m=21.8 ;; esac
  # ADR-011 soak: + 구간 48개 × 0.5분(60 − 12구간) + 시드 4배(200 → 800회차 — 시드 약 0.5분 × 3 실측 근사 1.5분)
  [[ "$1" == soak ]] && m=$(awk -v m="$m" 'BEGIN { print m + (60 - 12) * 0.5 + 1.5 }')
  if [[ "$SET" == cost-remeasure || "$SET" == jvm-baseline || "$SET" == jvm-ladder || "$SET" == k6ver || "$SET" == pretouch || "$SET" == soak ]]; then m=$(awk -v m="$m" 'BEGIN { print m + 1.2 }')
  else case "$2" in A+C|A+F|B+C) m=$(awk -v m="$m" 'BEGIN { print m + 1 }') ;; esac; fi
  echo "$m"
}
block_conds() { local v="BLOCK_${1//-/_}"; echo "${!v}"; }

COND_NEW="$(mktemp)"
for b in $BLOCKS; do for c in $(block_conds "$b"); do echo "$(name_of "$b" "$c") $SEAT $(args_of "$b" "$c")"; done; done > "$COND_NEW"
if [[ -f "$ROOT/conditions.txt" ]] && ! diff -q "$COND_NEW" "$ROOT/conditions.txt" > /dev/null; then
  echo "conditions.txt가 이번 조건 목록과 다르다 — 이어서 실행 거부" >&2; rm -f "$COND_NEW"; exit 2
fi
mv "$COND_NEW" "$ROOT/conditions.txt"

rotate() {  # 인자: 칸수 원소... → 왼쪽으로 칸수만큼 회전한 목록
  local k="$1"; shift; local a=("$@") n=$# i
  for (( i = 0; i < n; i++ )); do printf '%s ' "${a[$(( (i + k) % n ))]}"; done
}
ORDER_NEW="$(mktemp)"
{
  if [[ "$SET" == main ]]; then echo "# ADR-009 조건 순서 — 회차 우선 · 묶음($BLOCKS) 회차마다 2칸 회전 · 묶음 안 조건 ⌈n/3⌉칸 회전 (campaign.sh 머리말)"
  elif [[ "$SET" == cost-remeasure ]]; then echo "# ADR-009 cost-remeasure 조건 순서(명세 §13) — 회차 우선 · 묶음($BLOCKS) 회차마다 1칸 회전 · 묶음 안 조건 1칸 회전 (campaign.sh 머리말)"
  elif [[ "$SET" == jvm-baseline ]]; then echo "# ADR-010 jvm-baseline 조건 순서 — 1회 · 고정 순서(campaign.sh 머리말)"
  elif [[ "$SET" == jvm-ladder ]]; then echo "# ADR-010 jvm-ladder 조건 순서 — 회차 우선 · 조건 2칸 회전(campaign.sh 머리말)"
  else echo "# ADR-011 $SET 조건 순서 — 회차 우선 · 조건 순서 고정(ABAB — campaign.sh 머리말)"; fi
  echo "# 회차 회차안순번 전체순번 조건 예상분"
  seq_all=0
  for rep in $(seq 1 "$REPS"); do
    pos=0
    for b in $(rotate $(( BLOCK_STEP * (rep - 1) )) $BLOCKS); do
      read -r -a cs <<< "$(block_conds "$b")"
      for c in $(rotate $(( COND_ROT * ((${#cs[@]} + COND_STEP_DIV - 1) / COND_STEP_DIV) * (rep - 1) )) "${cs[@]}"); do
        pos=$((pos + 1)); seq_all=$((seq_all + 1))
        echo "$rep $pos $seq_all $(name_of "$b" "$c") $(minutes_of "$b" "$c")"
      done
    done
  done
} > "$ORDER_NEW"
# 계획 검증: 84행 · 조건마다 정확히 3회(회차 1·2·3에 한 번씩) · 전체 순번 연속 — 어긋나면 exit 2
total=$(grep -vc '^#' "$ORDER_NEW")
expect=$(( $(wc -l < "$ROOT/conditions.txt") * REPS ))
bad=""
(( total == expect && total == EXPECT_TOTAL )) || bad+=" 행수 $total≠$expect($EXPECT_TOTAL)"
while read -r name _; do
  [[ "$(awk -v c="$name" '$4 == c {print $1}' "$ORDER_NEW" | sort -n | tr '\n' ' ')" == "$(seq 1 "$REPS" | tr '\n' ' ')" ]] || bad+=" $name 회차 어긋남"
done < "$ROOT/conditions.txt"
[[ "$(grep -v '^#' "$ORDER_NEW" | awk '{print $3}' | tr '\n' ' ')" == "$(seq 1 "$total" | tr '\n' ' ')" ]] || bad+=" 전체 순번 어긋남"
[[ -z "$bad" ]] || { echo "순서 계획 검증 실패:$bad" >&2; rm -f "$ORDER_NEW"; exit 2; }
if [[ -f "$ROOT/order.txt" ]] && ! diff -q "$ORDER_NEW" "$ROOT/order.txt" > /dev/null; then
  echo "order.txt가 다시 계산한 순서와 다르다 — 이어서 실행 거부" >&2; rm -f "$ORDER_NEW"; exit 2
fi
mv "$ORDER_NEW" "$ROOT/order.txt"
EST_MIN=$(grep -v '^#' "$ROOT/order.txt" | awk '{ s += $5 } END { printf "%.1f", s }')
if (( DRY_RUN )); then
  echo "$(date -Is) DRY-RUN sha=$SHA conditions=$(wc -l < "$ROOT/conditions.txt") planned_calls=$total est_min=$EST_MIN — conditions.txt·order.txt만 썼다" >> "$LOG"
  for rep in $(seq 1 "$REPS"); do echo "$(date -Is) ORDER rep$rep: $(awk -v r="$rep" '$1 == r {printf "%s ", $4}' "$ROOT/order.txt")" >> "$LOG"; done
  cat "$ROOT/order.txt"
  echo "계획: 조건 $(wc -l < "$ROOT/conditions.txt")개 · ${total}회 호출 · 예상 ${EST_MIN}분($(awk -v m="$EST_MIN" 'BEGIN { printf "%.1f", m / 60 }')시간 — 재측정 제외)" >&2
  for g in $BLOCKS; do
    echo "  $g: $(grep -v '^#' "$ROOT/order.txt" | awk -v g="$g" '$4 ~ ("-" g "$") { n++; s += $5 } END { printf "%d회 %.1f분", n, s }')" >&2
  done
  # main: 명세 §13 시작 대기(모든 조건 87초)로 보정한 예상 — 대조 없는 조건 + 1.2분, 대조 조건 + 0.7분(order.txt는 그대로)
  [[ "$SET" == main ]] && echo "  보정 예상(명세 §13 시작 대기 반영): $(grep -v '^#' "$ROOT/order.txt" | awk '{ s += $5 + ($4 ~ /^(A\+C|A\+F|B\+C)-/ ? 0.7 : 1.2) } END { printf "%.1f분(%.1f시간)", s, s / 60 }')" >&2
  exit 0
fi

INFRA_STREAK=0
run_condition_rep() {  # 인자: 조건 한 줄, 회차. run.sh가 0이 아니면(인자·SHA·배포·정적 검사) 캠페인을 멈춘다 — 조용히 넘어가지 않게
  local line="$1" rep="$2" name="${1%% *}" args="${1#* }" resume="" rc
  [[ -f "$ROOT/$name/plan.json" ]] && resume="--resume"
  local mark; mark="$(mktemp)"
  echo "$(date -Is) $name rep$rep start $resume" >> "$LOG"
  if [[ -n "$resume" ]]; then
    # 이어서 실행: 조건은 계획에서 복원한다(run.sh) — 명령줄 조건 인자를 함께 넘겨 계획과 같은지 대조하게 한다
    eval bash "$DIR/run.sh" --sha "$SHA" --id "$CAMPAIGN/$name" --only-rep "$rep" $args $resume >> "$ROOT/$name.runner.log" 2>&1
  else
    eval bash "$DIR/run.sh" --sha "$SHA" --id "$CAMPAIGN/$name" --reps "$REPS" --only-rep "$rep" $args >> "$ROOT/$name.runner.log" 2>&1
  fi
  rc=$?
  echo "$(date -Is) $name rep$rep exit=$rc" >> "$LOG"
  if (( rc == 6 )); then echo "$(date -Is) ABORT limit-config $name rep$rep exit=6 — $(grep -h 'ABORT limit-config' "$ROOT/$name/MATRIX.log" 2>/dev/null | tail -1)" >> "$LOG"; exit 6; fi
  if (( rc != 0 )); then echo "$(date -Is) ABORT $name rep$rep exit=$rc" >> "$LOG"; exit "$rc"; fi
  local fresh; fresh=$(find "$ROOT/$name" -path "*/rep$rep/status" -newer "$mark" 2>/dev/null); rm -f "$mark"
  if [[ -n "$fresh" ]] && grep -qsE '^(unhealthy|reconciler-unhealthy|compose-up-failed|seed-failed)$' $fresh; then
    INFRA_STREAK=$((INFRA_STREAK + 1))
    (( INFRA_STREAK >= 5 )) && { echo "$(date -Is) ABORT infra-failure-5-in-a-row" >> "$LOG"; exit 4; }
  else
    INFRA_STREAK=0
  fi
}

# 결과 사유(머리말) — 이 사유가 하나라도 있는 회차는 다시 재지 않는다. ADR-008 결과 사유 + ADR-009(효과·마감 임박·조건 적용·시한폭탄·대조/정리 실패 ·
#   H-16 reconcile-missing(대조가 기대만큼 돌지 않은 것은 부하의 결과 — 다시 재서 가리지 않는다))
ACCEPT_RE='(^|,)(over-limit|counter-mismatch|counter-mismatch-missing|borrow-not-one|limit-strategy-mismatch|limit-key-present|limit-key-missing|limit-set-by-profile|limit-default-not-precheck|spring-config-override|spring-config-import|cond-config-mismatch|reconciler-config-mismatch|trigger-mismatch|effect-unexpected|false-repair|close-leak|confirm-after-close-failed|cleanup1-early|cleanup1-incomplete|cleanup2-left|pre-close-mismatch|schedule-closed-seen|counter-judge-vacuous|reconcile-errors|reconcile-missing|cleanup-errors|drift-record-failed|close-wrong-code|close-tick-failed|drift-key-present|gc-collector-mismatch)(,|$)'
all_status() { ls "$ROOT"/*/L*/*/rep*/status 2>/dev/null | grep -vE '\.(path-gap|retry|incomplete)-' || true; }
non_ok_reps() { local st; for st in $(all_status); do grep -qxs 'ok' "$st" || echo "$st"; done; }
consistency_violation() {  # 인자: status 경로 — 판정기 위반이 찍혔으면 0. 효과 셀(S3e)은 주입한 어긋남이 기대값이라 effect.json(주입 밖 어긋남·오탐 복구)으로 본다
  local d; d="$(dirname "$1")"
  if [[ "$d" == */S3e-*/* ]]; then
    jq -e '((.noninjected_mismatch // 0) > 0) or ((.false_repairs // 0) > 0)' "$d/effect.json" > /dev/null 2>&1 \
      || jq -e '(.v_over_limit_users // 0) > 0' "$d/consistency.json" > /dev/null 2>&1
  else
    jq -e '((.v_over_limit_users // 0) > 0) or ((.v_counter_mismatch // 0) > 0)' "$d/consistency.json" > /dev/null 2>&1
  fi
}
retryable() { local st; for st in $(non_ok_reps); do grep -qsE "$ACCEPT_RE" "$st" || consistency_violation "$st" || echo "$st"; done; }

echo "$(date -Is) CAMPAIGN start sha=$SHA conditions=$(wc -l < "$ROOT/conditions.txt") planned_calls=$total est_min=$EST_MIN" >> "$LOG"
for rep in $(seq 1 "$REPS"); do
  echo "$(date -Is) ORDER rep$rep: $(awk -v r="$rep" '$1 == r {printf "%s ", $4}' "$ROOT/order.txt")" >> "$LOG"
  for name in $(awk -v r="$rep" '$1 == r {print $4}' "$ROOT/order.txt"); do
    run_condition_rep "$(grep -m1 "^$name " "$ROOT/conditions.txt")" "$rep"
  done
done

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
  echo "$(date -Is) CAMPAIGN done — 비정상 회차 $(echo "$left" | wc -l)개 남음(결과 사유 회차는 재측정하지 않음):" >> "$LOG"
  for st in $left; do echo "  $st: $(cat "$st")" >> "$LOG"; done
  exit 1
fi
echo "$(date -Is) CAMPAIGN done — 비정상 회차 0" >> "$LOG"
