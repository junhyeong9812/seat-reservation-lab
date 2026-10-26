#!/usr/bin/env bash
# ADR-009 측정 실행기 (ADR-008 실행기 복사 + 조건 축 --cond · 정리 축 --cleanup · 셀 S3e(효과 — 어긋남 주입)·S3c(마감 임박) · 카운터 어긋남 수집·판정).
#   명세: docs/plans/2026-10-12/adr-009-counter-drift/requirement-spec.md §5·§10·§11 · 설계 design-v2.md §9·§10.
#   --cond control|control-noclose|A|A+C|A+F|B|B+C|default — 앱에 넣는 키(compose.yml 머리말):
#     ADR-009 결정(명세 §12)으로 앱 기본값이 A·C·정리 ① 켬이 됐다 — default 밖의 조건은 A·C·정리 세 키를 늘 명시한다(켬이든 끔이든 — 전환 전과 같은 실효 값).
#     ADR-012 기본값 교체(명세 §10 K9 — 업무 앱 기본값 A · 증분 trigger 1초 · 안전망 · split · C 끔): default 밖의 조건은 증분·안전망·탐지 모양도 늘 명시한다 —
#       A+I·A+X 밖 = incremental.method none · enabled false · safety-net false · detect-shape one-shot(교체 전과 같은 실효 값 — inc_env) · A+X 업무 앱 = 안전망 false · one-shot.
#       Flyway 위치는 전부터 늘 명시한다(FLYWAY_LOCATIONS). --cond default는 기본값이 옛 C가 아닌 SHA(lib.sh app_default_is_old_c)면 계획 단계에서 거부한다 — C 셀은 --cond A+C --cleanup schedule.
#       control         후보 모두 끔(세 키 끔 명시) · 판매 마감 켬(ADR-008 대조군과 같은 실효 설정)
#       control-noclose control + sales-close.enabled=false(마감 판정 비용 분리 — 명세 §10 D2)
#       A               counter-drift.expiry-repair=true
#       A+C             A + reconcile.enabled=true · interval 60s(명세 §11 R3 — 앱 안 대조, 앱 풀 공유)
#       A+F             업무 앱 A + external-reconciler=true · 별도 컨테이너 reconciler(compose.reconciler.yml — 프로필 loadtest,reconciler · 포트 8102 · 대조 60s)
#       B               limit-strategy=counter-trigger + spring.flyway.locations에 classpath:db/migration-trigger(V8)
#       B+C             B + reconcile(60s) — A 없이(명세 §11: 트리거가 마감 회차를 건너뛴다)
#       default         ADR-009 기본값(task 07a): 조건 키를 하나도 넣지 않는다 — 앱 기본값 A 켬 · C 켬(60s) · 정리 ① schedule · 마감 켬 · counter-upsert-precheck.
#                       교체 전 SHA에서만(ADR-012 명세 §10 K9 — 이어서 실행·옛 결과 집계의 default = C 해석은 그대로)
#                       --cleanup은 받지 않는다(정리 방식도 앱 기본값 — 기대값·plan.json의 cleanup은 schedule). 키가 없다는 증거 = limit-key.json의 drift 개수 0
#     매수 방식 키는 B·B+C에서만 넣는다. 그 밖은 ADR-008처럼 넣지 않고 기본값(counter-upsert-precheck)을 대조한다 — limit-key.json의 기대 개수가 조건마다 다르다(B = 1, 나머지 0).
#   --cleanup none|schedule|zero — counter-drift.cleanup.mode(마감 임박 셀 S3c — 명세 §10 D6). 기본 none(default 조건 밖에서는 none도 키로 넣는다)
#   셀(ADR-008 셀 + 둘):
#       S3e-a20  효과 셀 = S3-a20 + 어긋남 주입(k6 시작 + 회차 계획 INJECT_ROUNDS의 초마다 lib.sh inject_drift — injected.json) · 끝 쌍 상태(end-pairs.json) · effect.json(요약기 --effect)
#       S3c-a20  마감 임박 셀 = S3-a20 흐름 + k6 바로 전에 회차 starts_at = k6 시작 + 600초(close.json) · 마감 5초 전 표본(pre-close.json) · 끝 정리 상태(close-end.json)
#   회차 합격(rep_status — 정확히 ok만 정상): ADR-008 사유(조건별 기대값으로) + ADR-009 사유(README 표).
# 그 앞: ADR-008 측정 실행기 (ADR-007 실행기 복사 — 매수 방식을 주입하지 않고 앱 기본값으로 띄운다 · 기본값 출처 증거 limit-key.json · 회차 합격 판정 summarize.py --accept-check).
# 그 앞: ADR-007 측정 실행기 (ADR-006 실행기 복사 + 매수 방식 counter-upsert-precheck + DB 설정 축 --pg-preset + 대기 이벤트 표본 timeline-waits.jsonl + 고정 도착률 셀 S4c<rate>). k6 PC에서 실행. 측정 대상 = 로컬 커밋 SHA 하나 — 서버의 앱과 k6 시나리오 모두 그 커밋에서 꺼낸다.
# 인덱스는 V2 고정(ADR-002 기준선), 배경 0. 좌석 전략은 pessimistic-nowait(ADR-003 결정 3b) 고정이 기본 — 적용 값은 회차마다 대조한다. 앱은 1대(ADR-009에는 --apps 없음).
#
#   scripts/run.sh --sha <SHA> --cond <조건> [--cleanup none] [--pg-preset default] [--levels "4"] [--reps 3] [--cells "S2"] [--id <matrix-id>] [--only-rep k]
#     [--app-mem 1g] [--app-gc auto|g1|zgc]   # ADR-010 앱 JVM 축: 컨테이너 메모리 한도 · 수집기(auto = JVM 기본 선택, g1 = -XX:+UseG1GC, zgc = -XX:+UseZGC -XX:+ZGenerational) — 기본은 이전과 같은 1g·auto
#     ADR-011 손잡이(README 'ADR-011' — 지정하지 않으면 이전과 같은 렌더·계획·실행):
#     [--k6-bin k6]           주 k6·예열 k6 바이너리(plan.json condition.k6_bin·k6_version — 이어서 실행 때 대조 · meta k6_version = 그 바이너리의 version)
#     [--gctrace]             주 k6를 GODEBUG=gctrace=1로 — stderr의 gc 줄마다 PC epoch(소수 초)를 앞에 붙여 k6-gctrace.log.gz(나머지 stderr는 이전처럼 k6-stdout.log)
#     [--probes] [--probe-k6-bin <경로>]   저율 측정기 둘(scenarios/probe-ping.js — 50/s GET /actuator): PC 측정기(별도 k6 프로세스 → 8101) ·
#                             서버 측정기(compose.probe.yml probe 컨테이너 → http://app:8101). 주 k6 창에 맞춰 띄우고 내린다(probe-status.json · probe-check.json · 실패 = probe-missing)
#                             이미지 손잡이 PROBE_IMAGE(환경변수, 기본 grafana/k6:2.2.0 — plan.json condition.probe_image)
#     [--app-pretouch]        앱 JVM에 -XX:+AlwaysPreTouch(compose APP_JVM_OPTS3) — 기동 때 잡힌 힙만 건드린다(초기 힙 기본 = 한도의 1/64 → 4g면 64MB, 실측)
#     [--app-xms-full]        앱 JVM에 -XX:InitialRAMPercentage=75(compose APP_JVM_OPTS4) — 초기 힙 = 최대 힙. pretouch와 함께 줘야 힙 전체를 미리 건드린다
#     [--s4c-steps 12] [--seed-schedules 200]   S4c 계획 구간 수(30초 구간) · S4c 시드 회차 수(회차당 1만 석) — E3 장시간 = 60 · 800
#     ADR-012 R8 손잡이(지정하지 않으면 이전과 같은 렌더·계획·실행):
#     [--reconcile-shape one-shot|split|group]   --cond A+F에서만 — F 컨테이너 SPRING_APPLICATION_JSON에 counter-drift.reconcile.detect-shape
#                             (compose.reconciler.yml RECONCILE_DETECT_SHAPE) · plan.json condition.reconcile_shape · meta reconcile_shape ·
#                             reconciler-config.json의 detectShape가 이 값(지정 안 하면 ONE_SHOT)이 아니면 reconciler-config-mismatch
#                             (ADR-012 2단계: --cond A+I면 업무 앱 키 counter-drift.reconcile.detect-shape(compose.yml APP_DETECT_SHAPE) · A+X면 외부 앱 몫)
#     ADR-012 2단계 손잡이(§4.2.2·4.2.5·4.2.7·4.2.9 ③④ — 지정하지 않으면 이전과 같은 렌더·계획·실행. 새 조건 A+I·A+X에서만 받는다):
#       --cond A+I            A + 앱 안 증분(incremental.enabled=true) + 안전망(safety-net.enabled=true) · C 끔 — M2
#       --cond A+X            A + external-reconciler=true + 표시만(incremental.method · enabled=false) — 증분·안전망은 외부 앱(.158 reconciler-app) — M3.
#                             외부 앱(.158 · compose 프로젝트 seatlab-reconciler · 포트 8103 — lib.sh external_app_*): 배포(SHA — PC에서 이미지 빌드 → docker save | ssh docker load ·
#                             compose 파일만 복사, §4.2.8 ①②) → 회차마다 업무 앱 기동 뒤 compose up(빌드·pull 없음) · 8103 health ·
#                             configprops(external-config.json) · ⑥ 표본(timeline-ext-server.jsonl docker stats · timeline-ext-hikari.jsonl · clock-offset-ext.json) · 회차 끝 로그·내리기.
#                             시작 전에 lib.sh external_app_ready(EXT_DB_PASSWORD — k6 PC 셸 환경 · .158 도달 · 포트)를 통과해야 한다(아니면 exit 2)
#     [--incremental mem|trg|stmt]   방식(A+I·A+X 필수 · A+X는 trg|stmt) — 앱 키 method = memory|trigger|stmt · Flyway 위치(§4.2.2 조합표):
#                             mem = + migration-incremental(V10) · trg = + migration-updated-at-trigger(V9) + migration-incremental · stmt = + migration-updated-at(V9) + migration-incremental
#     [--incremental-interval 1s]    증분 틱 주기(정수 초 — 기본 1s, 계획에 늘 남는다) · source = inc-<app|ext>-<mem|trg|stmt>-<주기>, 안전망 = safety-<app|ext>(§4.2.6)
#     [--inject-app on|expiry] 효과 셀(S3e*)만 — 앱 경로 주입(loadtest.inject-app.enabled) + track-inject(첫 후보 시각) + S3e 주입의 counter_app_inject path='db' 행(lib.sh inject_drift)
#                             expiry = 위와 같고 앱 경로를 만료 경로(AE)만(loadtest.inject-app.paths=expiry — 명세 task 08b · 결과 리뷰 RR-5: on은 선점 경로 AH·AL이 max-pairs를 먼저 쓴다)
#     새 조건의 회차 판정: incremental-config-mismatch(summarize.py --inc-config-check — A+X는 두 앱 configprops) · schema-mismatch(db-schema.json — 열·인덱스·트리거·V10 테이블) ·
#       reconcile-missing(첫 증분 틱 · k6 구간의 증분 틱·안전망 실행 결손) · reconcile-errors(증분·안전망 지표 errors 증가)
#   --pg-preset default|nockpt|buf512|tmpfs — ADR-007 DB 설정 축(그대로). tmpfs = compose.tmpfs.yml(데이터 디렉터리 tmpfs · DB 메모리 8g)
#     [--pg-tmpfs-size <GiB>]  ADR-011 E3(대조 켜고 끄기): tmpfs 크기 = <GiB>g · DB 메모리 = <GiB>+2g(지정 안 하면 0 = 6g·8g 그대로 — tmpfs preset만)
#   셀 S4c4200 = S4 시나리오 고정 도착률 4,200/s(램프 60초 + 30초 × 12구간) — 판정은 정상 종료만(한계 규칙 없음)
#   스모크 전용: --worktree — 앱은 <SHA> 그대로, 하네스(k6/ADR-009)는 작업트리에서(커밋 전 실증). meta·plan에 harness=worktree:<해시>로 남는다. 캠페인에서는 거부.
#   주입·마감 손잡이(환경변수 — plan.json knobs에 남고 이어서 실행 때 대조한다, H-15): INJECT_ROUNDS="<초>:<D>,<L1>,<L2>,<H> …"(조건별 기본 — 아래 inject_rounds_default) ·
#     INJECT_HELD_MIN_S(250) · INJECT_EXP_MARGIN_S(15) · CLOSE_LEAD_S(1). 캠페인은 기본값만(campaign.sh가 넘기지 않는다)
#   scripts/run.sh --sha <SHA> --id <matrix-id> --resume [--only-rep k]   # 이어서 (계획은 기존 plan.json)
#   --only-rep k: 계획의 회차 중 k번째만 잰다 — 캠페인이 회차 단위로 전 조건을 한 바퀴씩 돌릴 때(조건 간 드리프트 분산)
#
# 결과: results/<matrix-id>/L<level>/<cell>/rep<k>/ 에 회차별 원시 결과 전부.
# 계획(plan.json)을 먼저 남기고, 성공·실패 모든 회차를 MATRIX.log 한 줄 + 회차 폴더 status/meta로 기록한다 (무음 스킵 금지).
set -uo pipefail

# 스크립트 동결: bash는 실행 중에도 파일을 이어 읽는다 — 장시간 매트릭스 도중 작업트리를 고치면 실행이 깨진다(실측).
# 시작하자마자 scripts/ 를 임시 위치로 복사해 그 사본으로 다시 실행한다.
if [[ "${RUN_FROZEN:-0}" != 1 ]]; then
  src="$(cd "$(dirname "$0")" && pwd)"
  frozen="$(mktemp -d)"
  cp -r "$src" "$frozen/scripts"
  # 캠페인(이미 동결된 사본)에서 불렸으면 원래 위치를 이어받는다 — 결과가 임시 폴더로 가지 않게
  export RUN_FROZEN=1 FROZEN_DIR="$frozen" ADR_DIR="${ADR_DIR:-$(cd "$src/.." && pwd)}"
  export REPO_ROOT="${REPO_ROOT:-$(cd "$ADR_DIR/../.." && pwd)}"
  exec bash "$frozen/scripts/run.sh" "$@"
fi
source "$(dirname "$0")/lib.sh"
set +e   # 한 회차 실패가 매트릭스 전체를 멈추지 않게 — 실패는 status로 기록한다

SHA="" LEVELS="4" REPS=3 CELLS="S2" MATRIX_ID="" RESUME=0 ONLY_REP=""
STRATEGY=pessimistic-nowait POOL=10 APPS=1 DELAY_MS=0   # ADR-003 조건 축 (+ ADR-004 임계 구역 지연) — ADR-009는 좌석 3b·앱 1대 고정
COND="" CLEANUP=none    # ADR-009 조건 축(머리말)
PG_PRESET=default       # ADR-007 조건 축: DB 설정(preset_env)
APP_MEM=1g APP_GC=auto  # ADR-010 조건 축: 앱 메모리 한도 · 수집기(머리말)
# ADR-011 손잡이(머리말): 주 k6 바이너리 · Go GC 기록 · 측정기 · AlwaysPreTouch · S4c 구간 수 · S4c 시드 회차 수
K6_BIN=k6 GCTRACE=0 PROBES=0 APP_PRETOUCH=0 APP_XMS_FULL=0 S4C_STEPS=12 SEED_SCHEDULES=200
PG_TMPFS_GB=0   # ADR-011 E3: tmpfs 크기(GiB) — 0 = preset 값(6g · DB 8g)
RECONCILE_SHAPE=""   # ADR-012 R8: F의 탐지 모양(머리말) — 빈 값 = 지정 안 함(키 없음 · 앱 기본 one-shot)
INC_METHOD="" INC_INTERVAL="" INJECT_APP=0 INJECT_APP_PATHS=""   # INJECT_APP_PATHS: --inject-app expiry → expiry(빈 값 = 앱 기본 두 경로 · 키 없음) · ADR-012 2단계(머리말) — 빈 값·0 = 지정 안 함(A+I·A+X 밖의 조건은 렌더·계획 그대로)
INJECT_APP_EVERY=50   # ADR-012 §4.2.7 앱 경로 주입 쌍 선택 (scheduleId × 31 + userId) mod every == 0 — 앱 기본값(키를 넣지 않는다 · inc-config-check가 configprops와 대조)
# 측정기 k6 기본 = 안정 경로의 v2.2.0(공식 tar.gz sha256 b5a8003c… 확인 뒤 푼 바이너리 — 바이너리 sha256 9fced9ff…, ADR-011 log). 주 k6와 다른 바이너리
PROBE_K6_BIN="$HOME/.local/opt/k6-v2.2.0/k6"
WORKTREE=0   # 스모크 전용(머리말)
COND_CLI=""             # 명령줄로 준 조건 — 이어서 실행 시 계획과 다르면 거부
while (( $# )); do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --levels) LEVELS="$2"; shift 2 ;;
    --reps) REPS="$2"; shift 2 ;;
    --cells) CELLS="$2"; shift 2 ;;
    --id) MATRIX_ID="$2"; shift 2 ;;
    --resume) RESUME=1; shift ;;
    --strategy) STRATEGY="$2"; COND_CLI+=" strategy"; shift 2 ;;
    --pool) POOL="$2"; COND_CLI+=" pool"; shift 2 ;;
    --delay-ms) DELAY_MS="$2"; COND_CLI+=" delay"; shift 2 ;;
    --cond) COND="$2"; COND_CLI+=" cond"; shift 2 ;;
    --cleanup) CLEANUP="$2"; COND_CLI+=" cleanup"; shift 2 ;;
    --apps|--limit-strategy|--inject-limit-strategy) echo "$1: ADR-009에는 없다 — 앱 1대 고정, 매수 방식은 --cond B·B+C만 주입(머리말)" >&2; exit 2 ;;
    --pg-preset) PG_PRESET="$2"; COND_CLI+=" pg"; shift 2 ;;
    --app-mem) APP_MEM="$2"; COND_CLI+=" app_mem"; shift 2 ;;
    --app-gc) APP_GC="$2"; COND_CLI+=" app_gc"; shift 2 ;;
    --k6-bin) K6_BIN="$2"; COND_CLI+=" k6_bin"; shift 2 ;;
    --gctrace) GCTRACE=1; COND_CLI+=" gctrace"; shift ;;
    --probes) PROBES=1; COND_CLI+=" probes"; shift ;;
    --probe-k6-bin) PROBE_K6_BIN="$2"; COND_CLI+=" probe_k6_bin"; shift 2 ;;
    --app-pretouch) APP_PRETOUCH=1; COND_CLI+=" app_pretouch"; shift ;;
    --app-xms-full) APP_XMS_FULL=1; COND_CLI+=" app_xms_full"; shift ;;
    --s4c-steps) S4C_STEPS="$2"; COND_CLI+=" s4c_steps"; shift 2 ;;
    --seed-schedules) SEED_SCHEDULES="$2"; COND_CLI+=" seed_schedules"; shift 2 ;;
    --pg-tmpfs-size) PG_TMPFS_GB="$2"; COND_CLI+=" pg_tmpfs_gb"; shift 2 ;;
    --reconcile-shape) [[ -n "${2:-}" ]] || { echo "--reconcile-shape one-shot|split|group(빈 값 불가)" >&2; exit 2; }; RECONCILE_SHAPE="$2"; COND_CLI+=" reconcile_shape"; shift 2 ;;
    --incremental) [[ -n "${2:-}" ]] || { echo "--incremental mem|trg|stmt(빈 값 불가)" >&2; exit 2; }; INC_METHOD="$2"; COND_CLI+=" incremental"; shift 2 ;;
    --incremental-interval) [[ -n "${2:-}" ]] || { echo "--incremental-interval <정수>s(빈 값 불가)" >&2; exit 2; }; INC_INTERVAL="$2"; COND_CLI+=" incremental_interval"; shift 2 ;;
    --inject-app) [[ "${2:-}" =~ ^(on|expiry)$ ]] || { echo "--inject-app on|expiry" >&2; exit 2; }; INJECT_APP=1; [[ "$2" == expiry ]] && INJECT_APP_PATHS=expiry; COND_CLI+=" inject_app"; shift 2 ;;
    --worktree) WORKTREE=1; shift ;;
    --only-rep) ONLY_REP="$2"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SHA" ]] || { echo "--sha required" >&2; exit 2; }
STRATEGIES="none jvm-lock jvm-lock-in-tx conditional-update pessimistic pessimistic-nowait optimistic unique advisory redis-nx redis-lock"
[[ " $STRATEGIES " == *" $STRATEGY "* ]] || { echo "--strategy one of: $STRATEGIES" >&2; exit 2; }
[[ "$DELAY_MS" =~ ^[0-9]+$ ]] || { echo "--delay-ms <정수 ms>" >&2; exit 2; }
# ADR-009 조건(머리말). 이어서 실행(--resume)이면 아래에서 계획 값으로 다시 정한다 — 여기서는 명령줄 값만 검사
CONDS="control control-noclose A A+C A+F B B+C default A+I A+X"   # A+I·A+X = ADR-012 2단계(머리말)
(( RESUME )) && [[ -z "$COND" ]] && COND=control   # 이어서 실행은 계획에서 복원(아래)
[[ " $CONDS " == *" $COND "* ]] || { echo "--cond one of: $CONDS" >&2; exit 2; }
[[ "$CLEANUP" =~ ^(none|schedule|zero)$ ]] || { echo "--cleanup none|schedule|zero" >&2; exit 2; }
[[ -z "$RECONCILE_SHAPE" || "$RECONCILE_SHAPE" =~ ^(one-shot|split|group)$ ]] || { echo "--reconcile-shape one-shot|split|group" >&2; exit 2; }
[[ -z "$INC_METHOD" || "$INC_METHOD" =~ ^(mem|trg|stmt)$ ]] || { echo "--incremental mem|trg|stmt" >&2; exit 2; }
[[ -z "$INC_INTERVAL" || "$INC_INTERVAL" =~ ^[1-9][0-9]*s$ ]] || { echo "--incremental-interval <정수>s(예: 1s · 5s)" >&2; exit 2; }
[[ "$APP_MEM" =~ ^[1-9][0-9]*[mg]$ ]] || { echo "--app-mem <정수>m|g (예: 1g · 1792m)" >&2; exit 2; }
[[ "$APP_GC" =~ ^(auto|g1|zgc)$ ]] || { echo "--app-gc auto|g1|zgc" >&2; exit 2; }
# ADR-011: S4c 구간 수 상한 = 계산 — 첫 홀드(k6 첫 요청)가 만료되기 전(S4c 앱 설정 HOLD_TTL 60m = 3,600초 — config_env s4)에 회차 끝 판정(consistency)까지 끝나야 한다
#   (만료 배치가 측정·판정에 끼지 않게). k6 첫 요청부터: 램프 60 + 구간 × 30 + 측정기 내림 여유 S4C_PROBE_STOP_S(재점검: 실패 경로 최대 = PC 신호 뒤 30 + 서버 수거 단계 시한 합 lib.sh probe_server_collect 90+30+120+60+90 = 420)
#   + IO_SETTLE_S(11) + consistency 시한 CONSISTENCY_TIMEOUT(600 — run_rep) ≤ 3,600 → 구간 ≤ ⌊(3,600 − 60 − 90 − 11 − 600) ÷ 30⌋ = 94.
#   (S3 셀의 TTL+grace 대기는 S4c에 없다. 장시간 실측 스모크로 여유를 확인 — 메인)
#   시드 회차 수 상한 1,000 = LoadtestDataService.reset(명세 §3 — 회차 ≤ 1,000). 좌석 수 ≥ 누적 요청은 셀을 펼친 뒤(아래) 확인한다
S4C_HOLD_TTL_S=3600 S4C_RAMP_S=60 S4C_PROBE_STOP_S=420 S4C_CONSISTENCY_S=600
S4C_STEPS_MAX=$(( (S4C_HOLD_TTL_S - S4C_RAMP_S - S4C_PROBE_STOP_S - ${IO_SETTLE_S:-11} - S4C_CONSISTENCY_S) / 30 ))
[[ "$S4C_STEPS" =~ ^[1-9][0-9]*$ ]] && (( S4C_STEPS <= S4C_STEPS_MAX )) || { echo "--s4c-steps 1..$S4C_STEPS_MAX(30초 구간 수 — HOLD_TTL 3,600초 − 램프 − 측정기 내림 − IO 대기 − consistency 시한)" >&2; exit 2; }
[[ "$SEED_SCHEDULES" =~ ^[1-9][0-9]*$ ]] && (( SEED_SCHEDULES <= 1000 )) || { echo "--seed-schedules 1..1000(회차당 1만 석)" >&2; exit 2; }
PROBE_IMAGE_ENV="${PROBE_IMAGE:-}" PROBE_IMAGE="${PROBE_IMAGE:-grafana/k6:2.2.0}" PROBE_RATE=50
[[ "$PROBE_IMAGE" =~ ^[A-Za-z0-9._/:@-]+$ ]] || { echo "PROBE_IMAGE 형식(이미지 이름[:태그][@digest])" >&2; exit 2; }
# S4c 좌석(시드 회차 × 1만 석)이 누적 요청(좌석 = 반복 번호 — 램프 (50 + 목표) ÷ 2 × 60초 + 목표 × 구간 × 30초)보다 적으면 없는 좌석(404)이 성공률을 깎는다 — 시작 전에 거부.
#   기본(12구간 · 200회차): 4,200/s 163.95만 ≤ 200만 · E3(60구간 · 800회차): 769.5만 ≤ 800만. 이어서 실행은 계획을 만들 때 이미 확인했다
if (( ! RESUME )); then
  for c in $CELLS; do
    [[ "$c" =~ ^S4c([0-9]+)$ ]] || continue
    need=$(( (50 + BASH_REMATCH[1]) * 30 + BASH_REMATCH[1] * S4C_STEPS * 30 ))
    (( need <= SEED_SCHEDULES * 10000 )) || { echo "$c: 누적 요청 $need > 시드 좌석 $(( SEED_SCHEDULES * 10000 )) — --seed-schedules를 늘린다" >&2; exit 2; }
  done
fi
# ADR-011: 실행할 k6 바이너리의 version(plan.json condition·meta k6_version). 못 읽으면 실행하지 않는다 — 이어서 실행이면 계획의 version과도 같아야 한다(아래)
# + 바이너리 sha256(PATH에서 찾은 실제 파일 — plan.json condition.k6_sha256·probe_k6_sha256, 이어서 실행·회차마다 대조: k6-binary-changed)
bin_sha256() { local f; f="$(command -v "$1" 2>/dev/null)" && [[ -f "$f" ]] && sha256sum "$f" | cut -d' ' -f1; }
k6_versions() {
  K6_VERSION="$("$K6_BIN" version 2>/dev/null | head -1)"
  [[ "$K6_VERSION" == "k6 v"* ]] || { echo "--k6-bin $K6_BIN: version을 읽지 못함" >&2; return 1; }
  K6_SHA256="$(bin_sha256 "$K6_BIN")"; [[ "$K6_SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "--k6-bin $K6_BIN: sha256을 못 셈" >&2; return 1; }
  PROBE_K6_VERSION="" PROBE_K6_SHA256=""
  if (( PROBES )); then
    PROBE_K6_VERSION="$("$PROBE_K6_BIN" version 2>/dev/null | head -1)"
    [[ "$PROBE_K6_VERSION" == "k6 v"* ]] || { echo "--probe-k6-bin $PROBE_K6_BIN: version을 읽지 못함" >&2; return 1; }
    PROBE_K6_SHA256="$(bin_sha256 "$PROBE_K6_BIN")"; [[ "$PROBE_K6_SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "--probe-k6-bin $PROBE_K6_BIN: sha256을 못 셈" >&2; return 1; }
  fi
}
[[ "$COND" == default && " $COND_CLI " == *" cleanup "* ]] && { echo "--cond default는 --cleanup을 받지 않는다(정리 방식도 앱 기본값 schedule — 키를 넣지 않는다)" >&2; exit 2; }
# 시드 회차 starts_at 2026-12-31 19:00+09(LoadtestDataService) 시한폭탄(명세 §0 · 설계 §5a): 그 1시간 전부터는 회차를 시작하지 않는다(모든 선점이 SCHEDULE_CLOSED가 된다)
if (( $(date -u +%s) >= $(date -u -d '2026-12-31T09:00:00Z' +%s) )); then echo "seed-time-bomb: 시드 회차 starts_at(2026-12-31T10:00Z) 1시간 전 — 하네스 시드 시각부터 바꿔야 한다" >&2; exit 2; fi
# ADR-007 DB 설정 축(명세 §9.2): 세 조건은 서로 독립 — 기본(PostgreSQL 16 기본값) · 체크포인트 없음(간격 1h·WAL 상한 16GB — 회차당 WAL 약 0.75GB라
# 시간·WAL 기준 체크포인트가 측정 중에 돌지 않는다는 가정, 명세 §8 가정 1 — 회차마다 pg_stat_bgwriter 차분·DB 로그로 확인) · 공유 버퍼 512MB.
# 값은 compose db.command의 환경변수로 넘기고(이름 = compose.yml), 대조(rep_status pg-preset-mismatch-*)는 SHOW 형태(current_setting) 그대로 한다.
preset_env() {
  case "$1" in
    default) echo "PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB" ;;
    nockpt)  echo "PG_CHECKPOINT_TIMEOUT=1h PG_MAX_WAL_SIZE=16GB PG_SHARED_BUFFERS=128MB" ;;
    buf512)  echo "PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=512MB" ;;
    # ADR-007 후속(디스크 원인 C5 — 2026-10-10 명세 §2): PG 설정은 default 그대로 + tmpfs 크기·DB 메모리 한도(compose.tmpfs.yml이 읽는다 — 대조 루프는 PG_* 세 키만 본다)
    tmpfs)   if (( PG_TMPFS_GB > 0 )); then echo "PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=${PG_TMPFS_GB}g DB_MEM=$(( PG_TMPFS_GB + 2 ))g"
             else echo "PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=6g DB_MEM=8g"; fi ;;
    *) return 1 ;;
  esac
}
# ADR-011 E3: tmpfs 크기는 tmpfs preset에서만 · 상한 20(서버 31GiB − 앱·측정기 여유). preset_env가 산술로 쓰기 전에 검사한다
[[ "$PG_TMPFS_GB" =~ ^(0|[1-9][0-9]?)$ ]] && (( PG_TMPFS_GB <= 20 )) || { echo "--pg-tmpfs-size 0..20(GiB — 0 = preset 값 6g)" >&2; exit 2; }
(( PG_TMPFS_GB == 0 )) || [[ "$PG_PRESET" == tmpfs ]] || { echo "--pg-tmpfs-size는 --pg-preset tmpfs에서만" >&2; exit 2; }
preset_env "$PG_PRESET" > /dev/null || { echo "--pg-preset one of: default nockpt buf512 tmpfs" >&2; exit 2; }
if (( WORKTREE )); then
  [[ "${CAMPAIGN_FROZEN:-0}" != 1 ]] || { echo "--worktree는 스모크 전용 — 캠페인에서는 커밋된 하네스만" >&2; exit 2; }
  (( ! RESUME )) || { echo "--worktree와 --resume은 함께 쓸 수 없다(작업트리 하네스는 이어서 잴 근거가 없다)" >&2; exit 2; }
fi
# ADR-009 조건 → 기대 매수 방식·앱 키(cond_env)·F 오버레이. 이어서 실행이면 계획 값으로 다시 부른다(아래)
set_cond() {
  case "$COND" in B|B+C) LIMIT_STRATEGY=counter-trigger ;; *) LIMIT_STRATEGY=counter-upsert-precheck ;; esac
  APPLIED_LIMIT="$LIMIT_STRATEGY"
  case "$COND" in B|B+C) LIMIT_KEYS_EXPECTED=1 ;; *) LIMIT_KEYS_EXPECTED=0 ;; esac   # 앱 컨테이너 환경의 매수 방식 키 등장 수(limit-key.json env) — B·B+C만 1
  HAS_C=0 HAS_F=0 HAS_A=0 IS_DEFAULT=0
  case "$COND" in A|A+C|A+F|default) HAS_A=1 ;; esac
  case "$COND" in A+C|B+C|default) HAS_C=1 ;; esac
  # default: 키 없이 앱 기본값(A·C 켬 · 정리 ① schedule) — 기대값(cfg_want·정리 오류 판정·plan.json)이 실효 값 schedule을 보도록
  [[ "$COND" == default ]] && { IS_DEFAULT=1; CLEANUP=schedule; }
  [[ "$COND" == A+F ]] && HAS_F=1
  RECONCILE_INTERVAL=60s   # 명세 §11 R3 — 비용·효과 셀 모두 60초
  # ADR-012 2단계(머리말): I = 앱 안 증분 + 안전망(M2) · X = 외부 앱 증분 + 안전망(M3 — 업무 앱은 A·정리 + 표시만). 둘 다 A 켬 · C 끔
  HAS_I=0 HAS_X=0 HAS_INC=0 INC_SOURCE="" SAFETY_SOURCE=""
  case "$COND" in A+I) HAS_A=1 HAS_I=1 ;; A+X) HAS_A=1 HAS_X=1 ;; esac
  (( HAS_I || HAS_X )) && HAS_INC=1
  if (( HAS_INC )); then
    [[ -n "$INC_INTERVAL" ]] || INC_INTERVAL=1s   # 기본 1초(§4.2.2) — 계획에 늘 값을 남긴다
    INC_SOURCE="inc-$( ((HAS_X)) && echo ext || echo app)-$INC_METHOD-$INC_INTERVAL"   # §4.2.6 — 주기 꼬리표 = 정수 초 Ns
    SAFETY_SOURCE="safety-$( ((HAS_X)) && echo ext || echo app)"
  fi
}
# ADR-012 2단계 손잡이 검사(이어서 실행은 계획 복원 뒤 다시 부른다): 새 조건에서만 받고, 방식은 새 조건에 필수 · 외부 앱은 ⓑⓒ만(§4.2.8) · 앱 경로 주입은 효과 셀만
inc_args_check() {
  if (( HAS_INC )); then
    [[ -n "$INC_METHOD" ]] || { echo "--cond $COND는 --incremental mem|trg|stmt가 필요하다" >&2; return 1; }
    (( HAS_X )) && [[ "$INC_METHOD" == mem ]] && { echo "--cond A+X(외부 앱)는 --incremental trg|stmt만(메모리 집합은 다른 프로세스가 볼 수 없다 — §4.2.8)" >&2; return 1; }
  else
    [[ -z "$INC_METHOD$INC_INTERVAL" ]] && (( ! INJECT_APP )) || { echo "--incremental·--incremental-interval·--inject-app은 --cond A+I·A+X에서만 — cond=$COND" >&2; return 1; }
  fi
  if (( INJECT_APP )); then
    local c; for c in $1; do [[ "$c" == S3e* ]] || { echo "--inject-app on은 효과 셀(S3e*)에서만 — 셀 $c" >&2; return 1; }; done
  fi
  return 0
}
set_cond
(( RESUME )) || inc_args_check "$CELLS" || exit 2
# ADR-012 R8: 탐지 모양은 F 컨테이너 손잡이 — A+F 밖에서 받으면 조용히 버리지 않고 거부(이어서 실행은 계획 복원 뒤 아래에서 다시 본다)
#   ADR-012 2단계: A+I(업무 앱의 증분·안전망 탐지)·A+X(외부 앱)에도 받는다(§4.2.9 ③)
(( RESUME )) || [[ -z "$RECONCILE_SHAPE" || "$COND" == A+F || "$COND" == A+I || "$COND" == A+X ]] || { echo "--reconcile-shape는 --cond A+F·A+I·A+X에서만(대조 탐지 모양) — cond=$COND" >&2; exit 2; }
# ADR-012 M3: 외부 앱(.158) 오케스트레이션(lib.sh external_app_*)이 없거나 준비 점검(external_app_ready)이 실패하면 배포·결과 폴더 전에 거부한다(조용히 업무 앱만 띄워 '대조 없음'을 재지 않게)
if (( HAS_X )) && ! external_app_supported; then echo "--cond A+X: 외부 앱 오케스트레이션이 없다(lib.sh external_app_*) — 거부" >&2; exit 2; fi
if (( HAS_X && ! RESUME )) && ! external_app_ready; then echo "--cond A+X: 외부 앱 준비 점검 실패(lib.sh external_app_ready) — 거부" >&2; exit 2; fi
SHA="$(git -C "$REPO_ROOT" rev-parse --short "$SHA")" || exit 2
# ADR-012 명세 §10 K9: 업무 앱 기본값이 옛 C(lib.sh app_default_is_old_c)가 아닌 SHA(교체 뒤 등)면 키 없는 앱은 C가 아니다 — default를 조용히 다른 조건으로 재지 않게
#   계획 단계에서 거부(이어서 실행은 계획 SHA 그대로라 검사하지 않는다)
if [[ "$COND" == default ]] && (( ! RESUME )); then
  app_default_is_old_c "$SHA"; oldc=$?
  case "$oldc" in
    0) ;;
    1) echo "--cond default: $SHA의 업무 앱 기본값이 옛 default(C 켬 · 증분 끔)가 아니다(ADR-012 명세 §10 — 교체 뒤는 증분 + 안전망 · C 끔) — C 셀은 --cond A+C --cleanup schedule" >&2; exit 2 ;;
    *) echo "--cond default: $SHA의 application.yml로 기본값이 옛 C인지 판정하지 못했다(app_default_is_old_c — 파일·키 없음·파싱 실패·조건부 문서) — 거부" >&2; exit 2 ;;
  esac
fi
# 실행하는 하네스 = 기록되는 SHA: 작업트리의 k6/ADR-009(results 제외)가 SHA와 다르면 거부한다.
# 캠페인에서 불렸으면 캠페인이 시작 때 이미 확인했다 — 이틀 도는 동안 작업트리를 고쳐도 남은 조건이 멈추지 않게 건너뛴다.
# --worktree(스모크)는 이 검사 대신 하네스 트리 해시를 기록한다.
HARNESS="sha:$SHA" DEPLOY_ID="$SHA"
if (( WORKTREE )); then
  HARNESS="worktree:$(worktree_harness_hash)"; DEPLOY_ID="$SHA-wt-${HARNESS#worktree:}"
elif [[ "${CAMPAIGN_FROZEN:-0}" != 1 ]] && { ! git -C "$REPO_ROOT" diff --quiet "$SHA" -- k6/ADR-009 ':(exclude)k6/ADR-009/results' \
   || [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- k6/ADR-009 ':(exclude)k6/ADR-009/results')" ]]; }; then
  echo "k6/ADR-009 differs from $SHA — commit first so the harness that runs is the one recorded (스모크는 --worktree)" >&2; exit 2
fi
MATRIX_ID="${MATRIX_ID:-$(date +%Y%m%d-%H%M%S)-$SHA}"
OUT_ROOT="$ADR_DIR/results/$MATRIX_ID"
MATRIX_LOG="$OUT_ROOT/MATRIX.log"
if (( RESUME )); then
  # 이어서 실행: 계획·셀·회차는 기존 plan.json 그대로. 측정 대상(앱·시나리오·compose)이 계획의 SHA와 같을 때만 허용한다
  [[ -f "$OUT_ROOT/plan.json" ]] || { echo "resume: $OUT_ROOT/plan.json 없음" >&2; exit 2; }
  PLAN_SHA="$(jq -r .sha "$OUT_ROOT/plan.json")"
  if ! git -C "$REPO_ROOT" diff --quiet "$PLAN_SHA" "$SHA" -- src build.gradle.kts Dockerfile .dockerignore settings.gradle.kts gradle gradlew main-app reconcile-core reconciler-app k6/ADR-009/scenarios k6/ADR-009/compose.yml k6/ADR-009/compose.reconciler.yml k6/ADR-009/compose.tmpfs.yml k6/ADR-009/compose.probe.yml; then
    echo "resume: 측정 대상이 계획($PLAN_SHA)과 다르다 — 이어서 잴 수 없음" >&2; exit 2
  fi
  LEVELS="$(jq -r '.levels|join(" ")' "$OUT_ROOT/plan.json")"; REPS="$(jq -r .reps "$OUT_ROOT/plan.json")"
  # 조건도 계획에서 복원한다 — 명령줄로 다른 값을 주면 거부(다른 조건이 한 결과로 섞이지 않게)
  P_STRATEGY="$(jq -r .condition.strategy "$OUT_ROOT/plan.json")"; P_POOL="$(jq -r .condition.pool "$OUT_ROOT/plan.json")"; P_APPS="$(jq -r .condition.apps "$OUT_ROOT/plan.json")"
  P_DELAY="$(jq -r '.condition.delay_ms // 0' "$OUT_ROOT/plan.json")"; P_LIMIT="$(jq -r .condition.limit_strategy "$OUT_ROOT/plan.json")"
  P_PG="$(jq -r '.condition.pg_preset // "default"' "$OUT_ROOT/plan.json")"
  P_COND="$(jq -r .condition.cond "$OUT_ROOT/plan.json")"; P_CLEANUP="$(jq -r '.condition.cleanup // "none"' "$OUT_ROOT/plan.json")"
  # ADR-010 축이 없는 옛 계획 = 그때의 실효 값(1g·auto)
  P_APP_MEM="$(jq -r '.condition.app_mem // "1g"' "$OUT_ROOT/plan.json")"; P_APP_GC="$(jq -r '.condition.app_gc // "auto"' "$OUT_ROOT/plan.json")"
  # ADR-011 손잡이가 없는 옛 계획 = 그때의 실효 값(k6 · 끔 · 12구간 · 200회차)
  P_K6_BIN="$(jq -r '.condition.k6_bin // "k6"' "$OUT_ROOT/plan.json")"; P_GCTRACE="$(jq -r 'if .condition.gctrace then 1 else 0 end' "$OUT_ROOT/plan.json")"
  P_PROBES="$(jq -r 'if .condition.probes then 1 else 0 end' "$OUT_ROOT/plan.json")"; P_PROBE_K6_BIN="$(jq -r --arg d "$PROBE_K6_BIN" '.condition.probe_k6_bin // $d' "$OUT_ROOT/plan.json")"
  P_PROBE_IMAGE="$(jq -r --arg d "$PROBE_IMAGE" '.condition.probe_image // $d' "$OUT_ROOT/plan.json")"
  P_APP_PRETOUCH="$(jq -r 'if .condition.app_pretouch then 1 else 0 end' "$OUT_ROOT/plan.json")"
  P_APP_XMS_FULL="$(jq -r 'if .condition.app_xms_full then 1 else 0 end' "$OUT_ROOT/plan.json")"
  P_S4C_STEPS="$(jq -r '.condition.s4c_steps // 12' "$OUT_ROOT/plan.json")"; P_SEED_SCHEDULES="$(jq -r '.condition.seed_schedules // 200' "$OUT_ROOT/plan.json")"
  P_PG_TMPFS_GB="$(jq -r '.condition.pg_tmpfs_gb // 0' "$OUT_ROOT/plan.json")"
  # ADR-012 R8 손잡이가 없는 옛 계획 = 지정 안 함(빈 값)
  P_RECONCILE_SHAPE="$(jq -r '.condition.reconcile_shape // ""' "$OUT_ROOT/plan.json")"
  # ADR-012 2단계 손잡이가 없는 옛 계획 = 지정 안 함
  P_INC_METHOD="$(jq -r '.condition.incremental.method // ""' "$OUT_ROOT/plan.json")"; P_INC_INTERVAL="$(jq -r '.condition.incremental.interval // ""' "$OUT_ROOT/plan.json")"
  P_INJECT_APP="$(jq -r 'if .condition.incremental.inject_app then 1 else 0 end' "$OUT_ROOT/plan.json")"; P_INJECT_APP_PATHS="$(jq -r '.condition.incremental.inject_app_paths // ""' "$OUT_ROOT/plan.json")"
  for k in $COND_CLI; do
    case "$k" in
      strategy) [[ "$STRATEGY" == "$P_STRATEGY" ]] || { echo "resume: --strategy $STRATEGY ≠ 계획 $P_STRATEGY" >&2; exit 2; } ;;
      pool)     [[ "$POOL" == "$P_POOL" ]] || { echo "resume: --pool $POOL ≠ 계획 $P_POOL" >&2; exit 2; } ;;
      delay)    [[ "$DELAY_MS" == "$P_DELAY" ]] || { echo "resume: --delay-ms $DELAY_MS ≠ 계획 $P_DELAY" >&2; exit 2; } ;;
      pg)       [[ "$PG_PRESET" == "$P_PG" ]] || { echo "resume: --pg-preset $PG_PRESET ≠ 계획 $P_PG" >&2; exit 2; } ;;
      cond)     [[ "$COND" == "$P_COND" ]] || { echo "resume: --cond $COND ≠ 계획 $P_COND" >&2; exit 2; } ;;
      cleanup)  [[ "$CLEANUP" == "$P_CLEANUP" ]] || { echo "resume: --cleanup $CLEANUP ≠ 계획 $P_CLEANUP" >&2; exit 2; } ;;
      app_mem)  [[ "$APP_MEM" == "$P_APP_MEM" ]] || { echo "resume: --app-mem $APP_MEM ≠ 계획 $P_APP_MEM" >&2; exit 2; } ;;
      app_gc)   [[ "$APP_GC" == "$P_APP_GC" ]] || { echo "resume: --app-gc $APP_GC ≠ 계획 $P_APP_GC" >&2; exit 2; } ;;
      k6_bin)   [[ "$K6_BIN" == "$P_K6_BIN" ]] || { echo "resume: --k6-bin $K6_BIN ≠ 계획 $P_K6_BIN" >&2; exit 2; } ;;
      gctrace)  [[ "$GCTRACE" == "$P_GCTRACE" ]] || { echo "resume: --gctrace ≠ 계획(gctrace=$P_GCTRACE)" >&2; exit 2; } ;;
      probes)   [[ "$PROBES" == "$P_PROBES" ]] || { echo "resume: --probes ≠ 계획(probes=$P_PROBES)" >&2; exit 2; } ;;
      probe_k6_bin) [[ "$PROBE_K6_BIN" == "$P_PROBE_K6_BIN" ]] || { echo "resume: --probe-k6-bin $PROBE_K6_BIN ≠ 계획 $P_PROBE_K6_BIN" >&2; exit 2; } ;;
      app_pretouch) [[ "$APP_PRETOUCH" == "$P_APP_PRETOUCH" ]] || { echo "resume: --app-pretouch ≠ 계획(app_pretouch=$P_APP_PRETOUCH)" >&2; exit 2; } ;;
      app_xms_full) [[ "$APP_XMS_FULL" == "$P_APP_XMS_FULL" ]] || { echo "resume: --app-xms-full ≠ 계획(app_xms_full=$P_APP_XMS_FULL)" >&2; exit 2; } ;;
      s4c_steps) [[ "$S4C_STEPS" == "$P_S4C_STEPS" ]] || { echo "resume: --s4c-steps $S4C_STEPS ≠ 계획 $P_S4C_STEPS" >&2; exit 2; } ;;
      seed_schedules) [[ "$SEED_SCHEDULES" == "$P_SEED_SCHEDULES" ]] || { echo "resume: --seed-schedules $SEED_SCHEDULES ≠ 계획 $P_SEED_SCHEDULES" >&2; exit 2; } ;;
      pg_tmpfs_gb) [[ "$PG_TMPFS_GB" == "$P_PG_TMPFS_GB" ]] || { echo "resume: --pg-tmpfs-size $PG_TMPFS_GB ≠ 계획 $P_PG_TMPFS_GB" >&2; exit 2; } ;;
      reconcile_shape) [[ "$RECONCILE_SHAPE" == "$P_RECONCILE_SHAPE" ]] || { echo "resume: --reconcile-shape $RECONCILE_SHAPE ≠ 계획 ${P_RECONCILE_SHAPE:-(지정 안 함)}" >&2; exit 2; } ;;
      incremental) [[ "$INC_METHOD" == "$P_INC_METHOD" ]] || { echo "resume: --incremental $INC_METHOD ≠ 계획 ${P_INC_METHOD:-(지정 안 함)}" >&2; exit 2; } ;;
      incremental_interval) [[ "$INC_INTERVAL" == "$P_INC_INTERVAL" ]] || { echo "resume: --incremental-interval $INC_INTERVAL ≠ 계획 ${P_INC_INTERVAL:-(지정 안 함)}" >&2; exit 2; } ;;
      inject_app) [[ "$INJECT_APP" == "$P_INJECT_APP" && "$INJECT_APP_PATHS" == "$P_INJECT_APP_PATHS" ]] || { echo "resume: --inject-app ≠ 계획(inject_app=$P_INJECT_APP paths=${P_INJECT_APP_PATHS:-기본})" >&2; exit 2; } ;;
    esac
  done
  # 측정기 이미지는 환경변수 손잡이 — 명시했는데 계획과 다르면 거부(지정하지 않으면 위 기본값이 들어 있으므로 계획에 값이 있을 때만 대조)
  if [[ -n "${PROBE_IMAGE_ENV:-}" && "$PROBE_IMAGE_ENV" != "$P_PROBE_IMAGE" ]]; then echo "resume: PROBE_IMAGE=$PROBE_IMAGE_ENV ≠ 계획 $P_PROBE_IMAGE" >&2; exit 2; fi
  STRATEGY="$P_STRATEGY" POOL="$P_POOL" APPS="$P_APPS" DELAY_MS="$P_DELAY" PG_PRESET="$P_PG" COND="$P_COND" CLEANUP="$P_CLEANUP" APP_MEM="$P_APP_MEM" APP_GC="$P_APP_GC"
  K6_BIN="$P_K6_BIN" GCTRACE="$P_GCTRACE" PROBES="$P_PROBES" PROBE_K6_BIN="$P_PROBE_K6_BIN" PROBE_IMAGE="$P_PROBE_IMAGE" APP_PRETOUCH="$P_APP_PRETOUCH" APP_XMS_FULL="$P_APP_XMS_FULL"
  S4C_STEPS="$P_S4C_STEPS" SEED_SCHEDULES="$P_SEED_SCHEDULES" PG_TMPFS_GB="$P_PG_TMPFS_GB" RECONCILE_SHAPE="$P_RECONCILE_SHAPE"
  INC_METHOD="$P_INC_METHOD" INC_INTERVAL="$P_INC_INTERVAL" INJECT_APP="$P_INJECT_APP" INJECT_APP_PATHS="$P_INJECT_APP_PATHS"
  k6_versions || exit 2
  for k in k6_version probe_k6_version k6_sha256 probe_k6_sha256; do
    pv="$(jq -r --arg k "$k" '.condition[$k] // empty' "$OUT_ROOT/plan.json")"
    case "$k" in k6_version) cv="$K6_VERSION" ;; probe_k6_version) cv="$PROBE_K6_VERSION" ;; k6_sha256) cv="$K6_SHA256" ;; *) cv="$PROBE_K6_SHA256" ;; esac
    if [[ -n "$pv" && "$pv" != "$cv" ]]; then echo "resume: $k '$cv' ≠ 계획 '$pv'" >&2; exit 2; fi
  done
  # H-15: 주입·마감 손잡이는 계획 값으로 — 환경변수로 다른 값을 주면 거부
  for k in INJECT_ROUNDS INJECT_HELD_MIN_S INJECT_EXP_MARGIN_S CLOSE_LEAD_S PRE_K6_HOLD_S PRE_K6_TARGET_S PRE_K6_TOL_S PRE_K6_LEAD_S; do
    pv="$(jq -r --arg k "$k" '.knobs[$k] // empty' "$OUT_ROOT/plan.json")"
    [[ -n "$pv" ]] || { echo "resume: plan.json knobs.$k 없음" >&2; exit 2; }
    if [[ -n "${!k:-}" && "${!k}" != "$pv" ]]; then echo "resume: $k=${!k} ≠ 계획 $pv" >&2; exit 2; fi
    printf -v "$k" '%s' "$pv"
  done
  set_cond
  [[ "$LIMIT_STRATEGY" == "$(jq -r .condition.limit_strategy "$OUT_ROOT/plan.json")" ]] || { echo "resume: 계획의 limit_strategy가 조건 $COND의 기대값과 다르다" >&2; exit 2; }
  [[ -z "$RECONCILE_SHAPE" || "$COND" == A+F || "$COND" == A+I || "$COND" == A+X ]] || { echo "resume: 계획의 reconcile_shape $RECONCILE_SHAPE는 A+F·A+I·A+X에서만 — cond=$COND" >&2; exit 2; }
  RESUME_CELLS="$(jq -r '.cells|join(" ")' "$OUT_ROOT/plan.json")"
  inc_args_check "$RESUME_CELLS" || { echo "resume: 계획의 ADR-012 손잡이가 조건 $COND와 맞지 않는다" >&2; exit 2; }
  if (( HAS_X )) && ! external_app_supported; then echo "resume: --cond A+X — 외부 앱 오케스트레이션이 없다(lib.sh external_app_*)" >&2; exit 2; fi
  if (( HAS_X )) && ! external_app_ready; then echo "resume: --cond A+X — 외부 앱 준비 점검 실패(lib.sh external_app_ready)" >&2; exit 2; fi
  echo "$(date -Is) RESUME sha=$SHA plan_sha=$PLAN_SHA (앱·시나리오·compose 동일 확인)" >> "$MATRIX_LOG"
else
  # 기존 결과를 덮어쓰지 않는다 — 근거 보존
  [[ -e "$OUT_ROOT" ]] && { echo "results/$MATRIX_ID already exists — refuse to overwrite" >&2; exit 2; }
  k6_versions || exit 2   # 결과 폴더를 만들기 전에(빈 폴더가 남지 않게)
  mkdir -p "$OUT_ROOT"
fi
SUMMARIZE="$(dirname "$0")/summarize.py"   # 동결 사본

# k6 시나리오도 측정 SHA에서 꺼낸다 — 작업트리가 바뀌어도 기록된 SHA로 재현된다
# 유니크 전략만 V4(좌석당 홀드 유니크)를 붙인다 — 앱 기동 검사가 전략↔인덱스 짝을 강제하고, 회차 판정이 실제 인덱스를 다시 대조한다
FLYWAY_LOCATIONS=$([[ "$STRATEGY" == unique ]] && echo "classpath:db/migration,classpath:db/migration-unique" || echo "classpath:db/migration")
# ADR-009 B(counter-trigger)는 V8 트리거(db/migration-trigger)를 더한다 — 앱 기동 검사가 트리거 ⇔ counter-trigger를 강제하고, 회차 판정이 pg_trigger를 다시 대조한다(db-triggers.json)
[[ "$LIMIT_STRATEGY" == counter-trigger ]] && FLYWAY_LOCATIONS="$FLYWAY_LOCATIONS,classpath:db/migration-trigger"
# ADR-012 2단계(§4.2.2 Flyway 위치 조합표 — 기본 위치 뒤에 더한다. M3 업무 앱도 M2와 같은 위치 · 외부 앱은 Flyway 없음): V9(열·인덱스 — trg는 트리거 위치) + V10(안전망·기록 테이블)
if (( HAS_INC )); then
  case "$INC_METHOD" in
    trg)  FLYWAY_LOCATIONS="$FLYWAY_LOCATIONS,classpath:db/migration-updated-at-trigger" ;;
    stmt) FLYWAY_LOCATIONS="$FLYWAY_LOCATIONS,classpath:db/migration-updated-at" ;;
  esac
  FLYWAY_LOCATIONS="$FLYWAY_LOCATIONS,classpath:db/migration-incremental"
fi
EXPECTED_INDEXES="idx_reservation_schedule_user,idx_reservation_seat_id,idx_seat_hold_expires_at,idx_seat_hold_seat_id,idx_seat_hold_user_id"
[[ "$STRATEGY" == unique ]] && EXPECTED_INDEXES="idx_reservation_schedule_user,idx_reservation_seat_id,idx_seat_hold_expires_at,idx_seat_hold_seat_id,idx_seat_hold_user_id,uq_seat_hold_seat_id"
# ADR-009 F: 별도 대조 컨테이너 오버레이 — compose_up이 이 변수를 쓰고, compose_down은 늘 이 오버레이까지 지정한다(lib.sh)
export COMPOSE_EXTRA=""
(( HAS_F )) && export COMPOSE_EXTRA="-f k6/ADR-009/compose.reconciler.yml"
# 효과 셀 주입 계획(머리말 · lib.sh inject_drift): 시각(k6 시작 기준 초)과 종류별 수. 캠페인은 기본값만(campaign.sh가 환경변수를 넘기지 않는다)
# H-1: 회차(라운드)별 종류 수. control은 첫 주입의 D·L1이 만료를 멈춰(정지 경로) 둘째 물결 선점이 생기지 않는다 — 둘째 주입의 '만료될' 후보(D·L1)가 0이라
#   계획에서 뺀다(L2·H만). 나머지 조건은 만료가 이어지므로 두 라운드 모두 D10·L1 5·L2 5·H10
inject_rounds_default() { [[ "$COND" == control ]] && echo "270:10,5,5,10 600:0,0,5,10" || echo "270:10,5,5,10 600:10,5,5,10"; }
INJECT_ROUNDS="${INJECT_ROUNDS:-$(inject_rounds_default)}" INJECT_HELD_MIN_S="${INJECT_HELD_MIN_S:-250}" INJECT_EXP_MARGIN_S="${INJECT_EXP_MARGIN_S:-15}" CLOSE_LEAD_S="${CLOSE_LEAD_S:-1}"
[[ "$INJECT_ROUNDS" =~ ^[0-9]+:[0-9]+,[0-9]+,[0-9]+,[0-9]+(\ [0-9]+:[0-9]+,[0-9]+,[0-9]+,[0-9]+)*$ && "$INJECT_HELD_MIN_S$INJECT_EXP_MARGIN_S$CLOSE_LEAD_S" =~ ^[0-9]+$ ]] \
  || { echo "INJECT_ROUNDS(<초>:<D>,<L1>,<L2>,<H> …)·INJECT_HELD_MIN_S·INJECT_EXP_MARGIN_S·CLOSE_LEAD_S 형식" >&2; exit 2; }
export INJECT_HELD_MIN_S INJECT_EXP_MARGIN_S
# ADR-012 --inject-app on(§4.2.7): S3e 주입이 같은 트랜잭션에서 counter_app_inject path='db' 행을 남기고, 앱 경로 주입 쌍((s × 31 + u) mod every = 0)·이미 기록된 주입 쌍은 고르지 않는다(lib.sh inject_drift)
INJECT_TRACK=$INJECT_APP
# 명세 §13 k6 전 대기(교란 수정 — README '시작 상태'): 시드 끝 → 대기점 PRE_K6_HOLD_S(70) — 대조 조건 첫 바퀴 대기의 이론 최대
#   (주기 60초 − 예열 뒤 시드까지 최소 약 20초 + 바퀴 시간 + 폴링 3초 — 실측 30~43초, 리뷰 H1·R5로 주기 + 바퀴 + 폴링 ≥ 70초를 덮게) ·
#   시드 끝 → **첫 요청** 목표 PRE_K6_TARGET_S(87 = 70 + 준비 단계 약 16~17초 실측) · 허용 PRE_K6_TOL_S(5) ·
#   PRE_K6_LEAD_S(1.2 — 맞춤 sleep 뒤 첫 요청까지: 시계 차 측정 약 0.5초 + S3c 준비 + k6 기동 약 0.3초 실측). 넷 다 plan.json knobs
PRE_K6_HOLD_S="${PRE_K6_HOLD_S:-70}" PRE_K6_TARGET_S="${PRE_K6_TARGET_S:-87}" PRE_K6_TOL_S="${PRE_K6_TOL_S:-5}" PRE_K6_LEAD_S="${PRE_K6_LEAD_S:-1.2}"
[[ "$PRE_K6_HOLD_S$PRE_K6_TARGET_S$PRE_K6_TOL_S" =~ ^[0-9]+$ && "$PRE_K6_LEAD_S" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "PRE_K6_HOLD_S·PRE_K6_TARGET_S·PRE_K6_TOL_S(정수 초)·PRE_K6_LEAD_S(초) 형식" >&2; exit 2; }
# 조건 키(compose.yml 머리말) — 모든 변수를 늘 넘긴다(빈 값 = 키 없음 · compose 경고 없음).
# default 밖의 조건은 A·C·정리를 늘 명시한다(ADR-009 기본값 전환 뒤에도 전환 전과 같은 실효 값). default는 셋 다 비운다(키 없음 = 앱 기본값)
cond_env() {
  local a c ci cl
  if (( IS_DEFAULT )); then a="" c="" ci="" cl=""
  else
    a=$( ((HAS_A)) && echo true || echo false) c=$( ((HAS_C)) && echo true || echo false) ci=$( ((HAS_C)) && echo "$RECONCILE_INTERVAL") cl="$CLEANUP"
  fi
  echo "LIMIT_TRIGGER=$([[ "$LIMIT_STRATEGY" == counter-trigger ]] && echo 1) SALES_CLOSE_OFF=$([[ "$COND" == control-noclose ]] && echo 1)" \
       "DRIFT_A=$a DRIFT_EXTERNAL=$( ((HAS_F || HAS_X)) && echo 1) DRIFT_C_ENABLED=$c DRIFT_C=$ci" \
       "DRIFT_CLEANUP=$cl RECONCILE_INTERVAL=$RECONCILE_INTERVAL$( ((HAS_F)) && echo "${RECONCILE_SHAPE:+ RECONCILE_DETECT_SHAPE=$RECONCILE_SHAPE}")$(inc_env)"
}
# ADR-012 업무 앱 키(compose.yml 머리말).
#   A+I: 방식 · 틱 켬 · 주기 · 안전망 켬 · 탐지 모양(--reconcile-shape, 없으면 one-shot) · A+X: 방식 · 틱 끔(표시만) · 안전망 끔 · one-shot.
#   효과 셀 --inject-app on: track-inject(두 앱 — §4.2.9 ④) + 앱 경로 주입.
#   그 밖의 조건(default 제외 — 명세 §10 K9): method none · 틱 끔 · 안전망 끔 · one-shot을 늘 넣는다 — 업무 앱 기본값 교체(증분 켬) 뒤에도 교체 전과 같은 실효 값.
#   (교체 전에는 이 키들이 A+I·A+X에서만 붙어 이전 조건의 렌더가 그대로였다 — 교체 뒤 렌더 변화는 ADR-009 선례 46f5b04d와 같이 받아들인다.) default = 키 없음
INC_BLANK_ENV="INC_METHOD= INC_ENABLED= INC_INTERVAL= INC_TRACK= SAFETY_ENABLED= APP_DETECT_SHAPE= INJECT_APP= INJECT_APP_PATHS="
inc_method_key() { case "$1" in mem) echo memory ;; trg) echo trigger ;; *) echo "$1" ;; esac; }
inc_env() {
  (( IS_DEFAULT )) && return 0
  if (( ! HAS_INC )); then printf ' INC_METHOD=none INC_ENABLED=false SAFETY_ENABLED=false APP_DETECT_SHAPE=one-shot'; return 0; fi
  if (( HAS_I )); then
    printf ' INC_METHOD=%s INC_ENABLED=true INC_INTERVAL=%s SAFETY_ENABLED=true APP_DETECT_SHAPE=%s' "$(inc_method_key "$INC_METHOD")" "$INC_INTERVAL" "${RECONCILE_SHAPE:-one-shot}"
  else
    printf ' INC_METHOD=%s INC_ENABLED=false SAFETY_ENABLED=false APP_DETECT_SHAPE=one-shot' "$(inc_method_key "$INC_METHOD")"
  fi
  (( INJECT_APP )) && printf ' INC_TRACK=1 INJECT_APP=1'
  [[ -n "$INJECT_APP_PATHS" ]] && printf ' INJECT_APP_PATHS=%s' "$INJECT_APP_PATHS"
  return 0
}
# ADR-012 M3 외부 앱 compose 변수(reconciler-app/compose.yml 머리말 — A+X만): 방식(업무 앱과 같은 값) · 주기 · 탐지 모양 · track-inject(효과 셀) ·
#   JVM 앞머리 = 업무 앱 APP_JVM_OPTS·2·3·4와 같은 값(F가 업무 앱 JAVA_TOOL_OPTIONS를 물려받는 것과 같은 꼴 — §4.2.8 'JVM 옵션은 F와 같게')
ext_env() {
  printf 'INC_METHOD=%s INC_INTERVAL=%s DETECT_SHAPE=%s INC_TRACK=%s' "$(inc_method_key "$INC_METHOD")" "$INC_INTERVAL" "$RECONCILE_SHAPE" "$( ((INJECT_APP)) && echo 1)"
  printf ' RECONCILER_JVM_OPTS=%s RECONCILER_JVM_OPTS2=%s RECONCILER_JVM_OPTS3=%s RECONCILER_JVM_OPTS4=%s' "$(case "$APP_GC" in g1) echo "-XX:+UseG1GC" ;; zgc) echo "-XX:+UseZGC" ;; esac)" \
    "$([[ "$APP_GC" == zgc ]] && echo "-XX:+ZGenerational")" "$( ((APP_PRETOUCH)) && echo "-XX:+AlwaysPreTouch")" "$( ((APP_XMS_FULL)) && echo "-XX:InitialRAMPercentage=75")"
}
# ADR-007 후속 tmpfs preset: DB 볼륨을 tmpfs로(오버레이). compose_down은 오버레이 없이도 같은 볼륨(pgdata)을 지운다(down -v)
[[ "$PG_PRESET" == tmpfs ]] && export COMPOSE_EXTRA="${COMPOSE_EXTRA:-} -f k6/ADR-009/compose.tmpfs.yml"

K6_TREE="$(mktemp -d)"
# 정리는 이 실행이 만든 임시 경로만 — 동결 사본은 mktemp 경로일 때만 지운다 (작업트리 삭제 방지)
cleanup() {
  # 이 실행의 최상위 셸에서만 지운다 — 서브셸이 EXIT 트랩을 물려받아 실행 중에 동결 사본을 지우는 일이 없게(ADR-007 스모크: 회차 도중 동결 사본이 사라져
  # gapcheck.py를 못 찾았다 — 원인 미확정, 방어). 누가 언제 지웠는지 남긴다
  [[ "$BASHPID" == "$$" ]] || return 0
  echo "[$(date '+%F %T')] cleanup pid=$BASHPID frozen=${FROZEN_DIR:-} k6_tree=$K6_TREE" >&2
  rm -rf "$K6_TREE"
  # ADR-011(리뷰 L2): 도중에 끝나면 PC 측정기가 남지 않게 — 그 pid가 아직 측정기 k6(이름 = --probe-k6-bin basename)일 때만 SIGINT
  if [[ -n "${PROBE_PC_PID:-}" ]] && kill -0 "$PROBE_PC_PID" 2>/dev/null \
     && [[ "$(ps -o comm= -p "$PROBE_PC_PID" 2>/dev/null)" == "$(basename "$PROBE_K6_BIN" | cut -c1-15)" ]]; then
    echo "[$(date '+%F %T')] cleanup: probe pc pid=$PROBE_PC_PID SIGINT" >&2; kill -INT "$PROBE_PC_PID" 2>/dev/null
    # 재점검: 끝날 때까지 최대 10초 기다리고, 남으면 SIGKILL
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$PROBE_PC_PID" 2>/dev/null || break; sleep 1; done
    kill -0 "$PROBE_PC_PID" 2>/dev/null && kill -KILL "$PROBE_PC_PID" 2>/dev/null
  fi
  # 재점검: 서버 측정기 컨테이너가 남았으면 지운다(다음 회차 compose_down도 probe 부재를 확인하지만, 도중 종료 뒤 곧바로 지우게)
  if (( ${PROBES:-0} )); then remote_t 60 "docker rm -f seatlab-probe-1" > /dev/null 2>&1 || true; fi
  # ADR-012 M3: 도중에 끝나도 외부 앱(.158 seatlab-reconciler)이 남지 않게 — 이 프로젝트만 내린다(lib.sh external_app_down)
  if (( ${HAS_X:-0} )) && [[ -n "${SHA:-}" ]]; then external_app_down "$SHA" > /dev/null 2>&1 || echo "[$(date '+%F %T')] cleanup: external_app_down 실패 — .158 $EXT_PROJECT 확인 필요" >&2; fi
  [[ -n "${FROZEN_DIR:-}" && "$FROZEN_DIR" == "${TMPDIR:-/tmp}"/tmp.* && -d "$FROZEN_DIR/scripts" ]] && rm -rf "$FROZEN_DIR"
}
trap cleanup EXIT
if (( WORKTREE )); then   # 스모크: 작업트리 시나리오의 사본(실행 중 수정에 흔들리지 않게)
  mkdir -p "$K6_TREE/k6/ADR-009" && cp -r "$REPO_ROOT/k6/ADR-009/scenarios" "$K6_TREE/k6/ADR-009/" \
    || { echo "cannot copy worktree scenarios" >&2; exit 2; }
else
  git -C "$REPO_ROOT" archive "$SHA" k6/ADR-009/scenarios | tar -x -C "$K6_TREE" \
    || { echo "cannot extract scenarios from $SHA" >&2; exit 2; }
fi
SCENARIOS="$K6_TREE/k6/ADR-009/scenarios"

# ---- 셀 정의 ----------------------------------------------------------------------------------------
# 앱 설정 그룹. S4는 측정 시간(약 7분)보다 긴 TTL로 만료 배치를 배제한다 — 배치가 처리량 측정에 끼어들지 않게.
config_of() { case "$1" in S3A|S3B) echo scaled ;; S4|S4c*|S6*) echo s4 ;; *) echo base ;; esac; }   # S6·S4c(고정 도착률)도 측정 중 만료 배치를 배제(TTL 60m)
config_env() {   # + ADR-003 조건(전략·풀)은 모든 그룹에 공통으로 붙는다
  # ADR-007: DB 설정 축(preset_env)도 모든 그룹에 붙는다 — compose db.command가 읽는다(meta.json app_env에 그대로 남는다)
  # ADR-008: SEAT_HOLD_LIMIT_STRATEGY를 넘기지 않는다 — compose가 읽지도 않지만 meta app_env에 거짓 값을 남기지 않게
  # ADR-009: 조건 키(cond_env)도 모든 그룹에 붙는다 — meta.json app_env에 그대로 남는다(값에 비밀 없음)
  # ADR-010: 앱 JVM 축(APP_MEM = compose mem_limit · APP_JVM_OPTS = JAVA_TOOL_OPTIONS 앞에 붙음 — auto면 넘기지 않는다)
  local cond="HOLD_STRATEGY=$STRATEGY FLYWAY_LOCATIONS=$FLYWAY_LOCATIONS POOL_SIZE=$POOL CRITICAL_SECTION_DELAY=${DELAY_MS}ms $(preset_env "$PG_PRESET") $(cond_env) APP_MEM=$APP_MEM APP_JVM_OPTS=$(case "$APP_GC" in g1) echo "-XX:+UseG1GC" ;; zgc) echo "-XX:+UseZGC" ;; esac) APP_JVM_OPTS2=$([[ "$APP_GC" == zgc ]] && echo "-XX:+ZGenerational") APP_JVM_OPTS3=$( ((APP_PRETOUCH)) && echo "-XX:+AlwaysPreTouch") APP_JVM_OPTS4=$( ((APP_XMS_FULL)) && echo "-XX:InitialRAMPercentage=75")"   # 리뷰: auto도 빈 값으로 명시(원격 환경에 남은 값이 섞이지 않게) · ADR-011 APP_JVM_OPTS3(--app-pretouch)도 같다
  case "$1" in
    base)   echo "HOLD_TTL=5m EXPIRY_INTERVAL=10s $cond" ;;
    scaled) echo "HOLD_TTL=30s EXPIRY_INTERVAL=1s $cond" ;;
    s4)     echo "HOLD_TTL=60m EXPIRY_INTERVAL=10s $cond" ;;
  esac
}
ttl_s_of()   { case "$(config_of "$1")" in scaled) echo 30 ;; s4) echo 3600 ;; *) echo 300 ;; esac; }
grace_of()   { case "$(config_of "$1")" in scaled) echo 2 ;; *) echo 20 ;; esac; }   # 배치 주기 × 2
seed_of()    { case "$1" in S4) echo "200 10000 0" ;; S4c*) echo "$SEED_SCHEDULES 10000 0" ;; S6*) echo "20 10000 0" ;; *) echo "1 10000 0" ;; esac; }   # S6: 핫 1~100,000 + 이웃 100,001~ (7.5만)   # 배경 0. S4는 200만 석 — 16단계 누적 요청(약 197만)이 시드를 넘으면 없는 좌석(404)이 성공률을 깎는다
script_of() {
  case "$1" in
    S1) echo s1-same-seat.js ;; S2) echo s2-same-user.js ;; S4|S4c*) echo s4-throughput.js ;; S7) echo s7-winner-rollback.js ;;
    S3|S3A|S3B|S3e|S3c) echo s3-full-flow.js ;;   # ADR-009 S3e(효과)·S3c(마감 임박) = S3 흐름
    S6*) echo s6-contention.js ;;
  esac
}
# 셀 이름 → 시나리오 종류: S3-a20 → S3, S7-m1 → S7(경쟁자 수 변형 — ADR-005 사용자 재합의 2026-10-06)
cell_base() { local b="${1%-a*}"; echo "${b%-m*}"; }
# S3 변형 × 이탈률은 셀 이름에 붙인다: S3-a0 / S3-a20 / S3-a50
expand_cells() {
  if [[ -n "${RESUME_CELLS:-}" ]]; then printf '%s\n' $RESUME_CELLS; return; fi
  for c in $CELLS; do
    case "$c" in S3|S3A|S3B) for a in 0 20 50; do echo "$c-a$a"; done ;; S6) for k in 1 10 100; do echo "S6-k$k"; done ;; *) echo "$c" ;; esac
  done
}
k6_env_of() {  # 시나리오 파라미터는 기본값에 기대지 않고 전부 명시한다 (meta.json에 그대로 남는다)
  local cell="$1" base="$(cell_base "$1")" abandon=0
  [[ "$cell" == *-a* ]] && abandon="${cell##*-a}"
  local ab; ab=$(awk "BEGIN{print $abandon/100}")
  case "$base" in
    S1)  echo "VUS=1000 SEAT_ID=1" ;;
    S2)  echo "USERS=100 PER_USER=10" ;;
    S3|S3e|S3c)  echo "RATE=300 TOTAL=200000 SEATS=10000 RETRIES=3 THINK_MIN_S=30 THINK_MAX_S=240 TAIL_S=360 ABANDON=$ab" ;;   # S3c의 CLOSE_AT_MS·CLOCK_OFFSET_MS는 run_rep이 k6 직전에 더한다
    S3A) echo "RATE=300 TOTAL=20000 SEATS=10000 RETRIES=3 THINK_MIN_S=3 THINK_MAX_S=24 TAIL_S=36 ABANDON=$ab" ;;
    S3B) echo "RATE=3000 TOTAL=200000 SEATS=10000 RETRIES=3 THINK_MIN_S=3 THINK_MAX_S=24 TAIL_S=36 ABANDON=$ab" ;;
    S4)  echo "START_RATE=50 FACTOR=1.5 STEPS=16 STEP_S=30" ;;   # ADR-002 13단계(6,487)에서 한계 미관측 → 16단계(약 2.2만/s)
    # ADR-007 고정 도착률(명세 §9.2 — 지속성·누적 vs 시각): 같은 시나리오를 FACTOR=1로 — 12구간 × 30초 = 6분, 구간 = stage 태그(요약기 30초 구간표).
    # 리뷰 A13: 앞에 램프 60초(계단 시작 도착률 50/s → 목표, 선형 — stage 태그 'ramp', 구간표·판정·벽 분석 밖)를 둔다. 0초부터 목표를 걸면 기동 과도에 VU가 10,000까지 늘어
    # 6분 내내 request timeout·dial i/o timeout이 남았다(스모크 smoke-b). 좌석 = 반복 번호라 누적 요청 = 램프 (50 + 4,200) ÷ 2 × 60 = 12.75만 + 4,200 × 360 = 151.2만
    # = 163.95만 < 시드 200만 석(seed_of). 에러율 중단(abortOnFail)은 끈다 — 무너진 뒤의 회복·지속을 끝까지 본다
    # ADR-011: 구간 수 = --s4c-steps(기본 12 — 이전과 같은 문자열). 시드 회차 수 --seed-schedules(seed_of — 기본 200)
    S4c*) echo "START_RATE=${base#S4c} FACTOR=1 STEPS=$S4C_STEPS STEP_S=30 STOP_ON_ERROR=0 RAMP_S=60 RAMP_FROM=50" ;;
    S6-k*) echo "K=${base#S6-k} M=20 START_RATE=200 FACTOR=2 STEPS=5 STEP_S=30 NEIGHBOR_RATE=500" ;;   # 핫 200→3,200/s + 이웃 500/s
    S7)  local m=20 late=0 c="$cell"; [[ "$c" == *-late ]] && { late=1; c="${c%-late}"; }   # ADR-007 S7-m1-late = S7-m1 + U를 R 응답 뒤에(시나리오 LATE)
         [[ "$c" == *-m* ]] && m="${c##*-m}"
         echo "SEATS=100 M=$m LEAD_MS=1 GAP_MS=50 START_DELAY_MS=3000$( ((late)) && echo ' LATE=1')" ;;   # ADR-005 이긴 쪽 롤백 — 설계 근거 README. S7-m1 = 일반 1명(빈 좌석 비율을 직접)
  esac
}

# ---- 수집 --------------------------------------------------------------------------------------------
snapshot() {  # 인자: 출력폴더 라벨
  local dir="$1" label="$2"
  remote "date -Is; uptime; nproc; free -m; docker stats --no-stream --format '{{json .}}'" > "$dir/server-$label.txt" 2>&1
  { date -Is; uptime; nproc; free -m; } > "$dir/client-$label.txt" 2>&1
}
app_config() {  # 적용된 설정 (비밀번호 등 제외) + JVM이 본 CPU 수 + 풀 크기 + 컨테이너 제한
  local dir="$1" props cpu pool mem containers
  # ADR-012 2단계: 새 조건은 seat.hold 아래 다른 묶음(앱 경로 주입 seat.hold.loadtest.inject-app 등 — 따로 바인딩될 수 있다)도 남긴다. 이전 조건은 같은 정규식 그대로
  local pre='^(seat\.hold|spring\.datasource\.hikari|spring\.flyway|server)$'; (( HAS_INC )) && pre='^(seat\.hold(\..+)?|spring\.datasource\.hikari|spring\.flyway|server)$'
  props=$(actuator configprops | jq -c --arg pre "$pre" '[.contexts[].beans[]
      | select(.prefix | test($pre))
      | {prefix, properties: (.properties | with_entries(select(.key | test("password|secret"; "i") | not)))}]') || return 1
  cpu=$(actuator metrics/system.cpu.count | jq -c '[.measurements[].value]') || return 1
  pool=$(actuator metrics/hikaricp.connections.max | jq -c '[.measurements[].value]') || return 1
  mem=$(actuator metrics/jvm.memory.max | jq -c '[.measurements[].value]') || return 1
  local names="seatlab-app-1 seatlab-db-1 seatlab-redis-1"; (( HAS_F )) && names+=" seatlab-reconciler-1"
  containers=$(remote "docker inspect $names --format '{{json .Name}} {{json .HostConfig}}'" | jq -Rsc 'split("\n") | map(select(length>0) | split(" ") | {name: (.[0]|fromjson), cpu: (.[1:]|join(" ")|fromjson|.NanoCpus), mem: (.[1:]|join(" ")|fromjson|.Memory)})') || return 1
  local idx; idx=$(db_indexes) || return 1   # DB에 실제로 있는 인덱스 — 인덱스 조건 적용의 근거
  jq -n --argjson p "$props" --argjson c "$cpu" --argjson h "$pool" --argjson m "$mem" --argjson k "$containers" --argjson i "$idx" \
    '{configprops:$p, "system.cpu.count":$c, "hikaricp.connections.max":$h, "jvm.memory.max":$m, containers:$k, db_indexes:$i}' > "$dir/app-config.json"
  # ADR-009 F: 대조 컨테이너의 적용 설정(seat.hold — counterDrift.reconcile 포함. 만료 스케줄러 끔은 configprops에 없고 기동 검사가 강제한다)도 따로 — rep_status reconciler-config-mismatch
  if (( HAS_F )); then
    app_actuator reconciler configprops | jq -c '{configprops: [.contexts[].beans[] | select(.prefix | test("^(seat\\.hold|spring\\.datasource\\.hikari|server)$"))
        | {prefix, properties: (.properties | with_entries(select(.key | test("password|secret"; "i") | not)))}]}' > "$dir/reconciler-config.json" || return 1
  fi
  # ADR-012 M3: 외부 앱(8103)의 적용 설정 → external-config.json(같은 모양 {configprops:[…]}) — incremental-config-mismatch가 두 앱을 함께 본다(lib.sh external_app_configprops)
  if (( HAS_X )); then external_app_configprops > "$dir/external-config.json" || return 1; fi
}
limit_keys() {  # 인자: 회차폴더 → limit-key.json = 컨테이너마다 lib.sh limit_key_check 한 줄의 배열(앱 + F면 reconciler — 첫 원소가 앱)
  local dir="$1" arr="[]" c line names="seatlab-app-1"
  (( HAS_F )) && names+=" seatlab-reconciler-1"
  for c in $names; do
    line=$(limit_key_check "$c") || return 1
    arr=$(jq -c --argjson x "$line" '. + [$x]' <<< "$arr") || return 1
  done
  echo "$arr" > "$dir/limit-key.json"
}
# 표본 서버 사본(lib.sh db_sampler) — 회차마다 하나, 배포 폴더 아래 .samples/(REMOTE_BASE 밖은 쓰지 않는다)
sampler_copy_of() {  # 인자: level cell rep — 시도마다 다른 이름(시각 + 난수, 리뷰 R2): 재측정·이어서 실행이 옛 사본에 덧붙이지 않게
  echo "$REMOTE_BASE/$DEPLOY_ID/.samples/$(echo "$MATRIX_ID" | tr '/' '_')-L$1-$2-rep$3-$(date +%Y%m%d%H%M%S)-$RANDOM.jsonl"
}
# 회차 끝: 서버 사본을 받아 timeline-locks/waits를 다시 만든다(흘려받은 줄보다 많을 때만 — 흘려받기 공백 복구). 결과는 sampler-recovery.txt에 한 줄,
# 서버 사본은 지운다(서버 디스크 — 회차당 약 1MB). 사본을 못 받으면 흘려받은 파일 그대로(공백은 rep_status waits-gap이 드러낸다)
recover_samples() {  # 인자: 회차폴더
  local dir="$1" tmp="$1/.sampler-remote.jsonl" nl nw rl rw
  if remote "cat '$SAMPLER_COPY'" > "$tmp" 2>/dev/null && [[ -s "$tmp" ]]; then
    nl=$(wc -l < "$dir/timeline-locks.jsonl" 2>/dev/null || echo 0); nw=$(wc -l < "$dir/timeline-waits.jsonl" 2>/dev/null || echo 0)
    rl=$(grep -c '"lock_waiting"' "$tmp"); rw=$(grep -c '"waits"' "$tmp")
    if (( rl > nl || rw > nw )); then
      grep '"lock_waiting"' "$tmp" > "$dir/timeline-locks.jsonl"; grep '"waits"' "$tmp" > "$dir/timeline-waits.jsonl"
      echo "$(date -Is) recovered-from-server locks $nl→$rl waits $nw→$rw" >> "$dir/sampler-recovery.txt"
    else
      echo "$(date -Is) stream-complete locks $nl (server $rl) waits $nw (server $rw)" >> "$dir/sampler-recovery.txt"
    fi
  else
    echo "$(date -Is) server-copy-unavailable" >> "$dir/sampler-recovery.txt"
  fi
  # 서버 사본 지우기(서버 디스크) — 받지 못했어도 남은 것이 있으면 지운다. 지운 뒤에도 남아 있거나 확인이 실패하면 errors.log 한 줄 +
  # sampler-recovery.txt에 sampler-copy-not-removed(rep_status 사유 — 리뷰 R2: 지우기 실패가 무음으로 서버에 쌓이지 않게)
  if ! remote "rm -f '$SAMPLER_COPY' && test ! -e '$SAMPLER_COPY'" > /dev/null 2>&1; then
    echo "$(date -Is) sampler copy not removed: $SAMPLER_COPY" >> "$dir/errors.log"
    echo "$(date -Is) sampler-copy-not-removed $SAMPLER_COPY" >> "$dir/sampler-recovery.txt"
  fi
  rm -f "$tmp"
}

start_pollers() {  # 인자: 출력폴더 셀 → 백그라운드 PID들을 출력
  # 수집기의 stdout은 /dev/null — $(...)가 백그라운드 프로세스의 파이프를 기다리며 멈추지 않게
  local dir="$1" cell="$2" K6_COMM; K6_COMM="$(basename "$K6_BIN" | cut -c1-15)"
  ( while :; do
      remote "echo \"{\\\"t\\\":\\\"\$(date -Is)\\\",\\\"load\\\":\\\"\$(cut -d' ' -f1-3 /proc/loadavg)\\\"}\"; docker stats --no-stream --format '{{json .}}'" \
        >> "$dir/timeline-server.jsonl" 2>/dev/null
      sleep 10
    done ) > /dev/null 2>&1 & echo $!
  ( while :; do
      # ADR-011: 주 k6 바이너리 이름(--k6-bin — 기본 k6, comm은 15자) · 측정기(--probes)의 PC k6 프로세스는 뺀다(probe-pc.pid — 같은 이름일 수 있다)
      if [[ -s "$dir/probe-pc.pid" ]]; then p=$(pgrep -x "$K6_COMM" | grep -vxF -f "$dir/probe-pc.pid" | head -1); else p=$(pgrep -x "$K6_COMM" | head -1); fi
      [[ -n "$p" ]] && echo "{\"t\":\"$(date -Is)\",\"k6_pcpu\":$(ps -o pcpu= -p "$p" | tr -d ' '),\"k6_rss_kb\":$(ps -o rss= -p "$p" | tr -d ' '),\"load\":\"$(cut -d' ' -f1-3 /proc/loadavg)\"}" >> "$dir/timeline-client.jsonl"
      sleep 5
    done ) > /dev/null 2>&1 & echo $!
  # 락 대기(ADR-003 ④) + 대기 이벤트(ADR-007): 서버 쪽 루프가 0.5초마다 두 줄 — SSH 하나·psql 호출 하나(lib.sh db_sampler).
  # 줄의 키로 나눠 쓴다(락 = lock_waiting, 대기 이벤트 = waits). 줄마다 바로 파일에 쓴다 — 아래 '표본이 나온 뒤 부하' 대기가 파일 크기를 본다.
  # 받는 쪽은 bash `read` 루프다: bash는 파이프 입력을 한 바이트씩 읽어 줄이 오는 즉시 쓰고, 프로세스가 꺼질 때 버퍼에 남는 줄이 없다.
  # (ADR-007 리뷰 A11: 이전의 awk는 이 PC에서 mawk 1.3.4라 파이프 **입력**을 큰 덩어리로 블록 버퍼링했다 — fflush는 출력만 비운다. 줄이 덩어리째 늦게 오고
  #  회차 끝에 루프를 끄면 마지막 덩어리가 사라졌다 = 스모크의 '흘려받기가 k6 +240~261초에 멈춤'·'S2 0줄'. 0.1초마다 600바이트 줄 재현: mawk 2초 뒤 0줄 · 이 루프 20줄+.
  #  스모크 errors.log에 'db_sampler ended'는 0건 — SSH가 끊긴 것이 아니었다.)
  # SSH가 끝나면 1초 뒤 다시 연다 — 위 원인과 무관한 무해한 보조 장치로 남긴다. 끝날 때마다 errors.log에 한 줄(무음 공백 금지 — 공백이 30초를 넘으면 rep_status waits-gap).
  # 서버 사본 복구(recover_samples)도 보조 장치로 유지한다. 정리는 아래 stop_pollers가 루프와 그 자식(ssh·read 루프)을 함께 끈다
  ( while :; do
      db_sampler "$SAMPLER_COPY" 2>/dev/null < /dev/null \
        | while IFS= read -r line; do
            case "$line" in
              *'"lock_waiting"'*) printf '%s\n' "$line" >> "$dir/timeline-locks.jsonl" ;;
              *'"waits"'*) printf '%s\n' "$line" >> "$dir/timeline-waits.jsonl" ;;
            esac
          done
      echo "$(date -Is) db_sampler ended rc=${PIPESTATUS[0]}/${PIPESTATUS[1]} — reopen in 1s" >> "$dir/errors.log"
      sleep 1
    done ) > /dev/null 2>&1 & echo $!
  # ADR-007 후속 호스트 디스크 표본(lib.sh host_sampler — 서버 1초 루프, 받는 쪽은 위와 같은 bash read 루프: mawk 버퍼링 재발 금지·리뷰 A11).
  # 서버 사본(HOST_COPY)·iostat 사본(HOST_IOSTAT)은 회차 끝 host_recover가 받는다. SSH가 끝나면 1초 뒤 다시 연다(errors.log 한 줄 — 무음 공백 금지)
  ( while :; do
      host_sampler "$HOST_COPY" "$HOST_IOSTAT" 2>/dev/null \
        | while IFS= read -r line; do printf '%s\n' "$line" >> "$dir/timeline-host.jsonl"; done
      echo "$(date -Is) host_sampler ended rc=${PIPESTATUS[0]}/${PIPESTATUS[1]} — reopen in 1s" >> "$dir/errors.log"
      sleep 1
    done ) > /dev/null 2>&1 & echo $!
  # 커넥션 풀 대기: 앱의 Hikari pending/active, 1초마다(앱 2대면 nginx가 번갈아 보낸다 — 어느 앱인지는 기록하지 않음, 편향)
  ( while :; do
      pend=$(curl -fsS --max-time 2 "$BASE_URL/actuator/metrics/hikaricp.connections.pending" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null)
      act=$(curl -fsS --max-time 2 "$BASE_URL/actuator/metrics/hikaricp.connections.active" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null)
      [[ -n "$pend" && -n "$act" ]] && echo "{\"t\":\"$(date -Is)\",\"pending\":$pend,\"active\":$act}" >> "$dir/timeline-hikari.jsonl"
      sleep 1
    done ) > /dev/null 2>&1 & echo $!
  # ADR-012 ⑥(M3 — §4.2.9): 외부 앱 컨테이너 docker stats(10초 — 그 컨테이너만, lib.sh external_stats) · 외부 앱 Hikari pending/active(1초 — 8103 actuator)
  if (( HAS_X )); then
    ( while :; do external_stats >> "$dir/timeline-ext-server.jsonl" 2>/dev/null; sleep 10; done ) > /dev/null 2>&1 & echo $!
    ( while :; do
        pend=$(curl -fsS --max-time 2 "$EXTERNAL_URL/actuator/metrics/hikaricp.connections.pending" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null)
        act=$(curl -fsS --max-time 2 "$EXTERNAL_URL/actuator/metrics/hikaricp.connections.active" 2>/dev/null | jq -c '.measurements[0].value' 2>/dev/null)
        [[ -n "$pend" && -n "$act" ]] && echo "{\"t\":\"$(date -Is)\",\"pending\":$pend,\"active\":$act}" >> "$dir/timeline-ext-hikari.jsonl"
        sleep 1
      done ) > /dev/null 2>&1 & echo $!
  fi
  case "$cell" in
    S3*)   # 좌석 상태 곡선 — DB 직접 가벼운 집계(전체 판정은 회차 끝 1회). poll_ms = ssh 포함 왕복 (간섭 판단용)
      ( while :; do
          t0=$(date +%s%N); j=$(db_counts true); t1=$(date +%s%N)
          [[ -n "$j" ]] && echo "$j" | jq -c --arg t "$(date -Is)" --argjson ms $(( (t1 - t0) / 1000000 )) '. + {t: $t, poll_ms: $ms}' >> "$dir/timeline-db.jsonl"
          sleep 5
        done ) > /dev/null 2>&1 & echo $! ;;
    S4|S4c*|S6*)    # 누적 홀드 수만 — DB 직접 (전체 판정은 무거워 부하에 간섭한다)
      ( while :; do
          t0=$(date +%s%N); j=$(db_counts false); t1=$(date +%s%N)
          [[ -n "$j" ]] && echo "$j" | jq -c --arg t "$(date -Is)" --argjson ms $(( (t1 - t0) / 1000000 )) '. + {t: $t, poll_ms: $ms}' >> "$dir/timeline-db.jsonl"
          sleep 10
        done ) > /dev/null 2>&1 & echo $! ;;
  esac
}

# 수집기 정리: 루프(서브셸)를 먼저 끄고 그 자식(진행 중인 ssh·awk·curl 등)도 끈다 — 루프만 끄면 표본 루프의 ssh·awk가 고아로 남는다(ADR-007 재시작 루프)
stop_pollers() {
  local p kids
  for p in "$@"; do
    kids=$(pgrep -P "$p" 2>/dev/null)
    kill "$p" 2>/dev/null
    [[ -n "$kids" ]] && kill $kids 2>/dev/null
  done
}

# ---- 회차 실행 ---------------------------------------------------------------------------------------
UNHEALTHY_STREAK=0

run_rep() {  # 인자: level cell rep
  local level="$1" cell="$2" rep="$3" base="$(cell_base "$2")"
  local dir="$OUT_ROOT/L$level/$cell/rep$rep"
  mkdir -p "$dir"
  local started; started=$(date -Is)
  [[ -n "$LIMIT_CONFIG" ]] && echo "$LIMIT_CONFIG" > "$dir/limit-config.json"   # ADR-008 설정 파일 정적 검사(회차 전 한 번 — 위) 사본
  local cfg_env; cfg_env="$(config_env "$(config_of "$base")") APP_CPUS=$(app_cpus "$level") DB_CPUS=$level"
  log "L$level $cell rep$rep cond=$COND cleanup=$CLEANUP → $dir"

  # 회차마다 새 컨테이너 — JVM·커넥션 풀·DB 캐시 상태를 회차 사이에 넘기지 않는다(V7 기록 테이블도 새 볼륨이라 이 회차의 행뿐이다)
  # ADR-012 M3: 외부 앱(.158)을 먼저 내린다 — 옛 외부 앱이 새 DB에 붙어 대조하지 않게(lib.sh external_app_down — 이 프로젝트만)
  if (( HAS_X )) && ! external_app_down "$SHA" >> "$dir/external-compose.log" 2>&1; then
    UNHEALTHY_STREAK=$((UNHEALTHY_STREAK + 1))
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "external-down-failed" -1 ""; return 1
  fi
  if ! compose_down "$DEPLOY_ID" >> "$dir/compose.log" 2>&1; then
    UNHEALTHY_STREAK=$((UNHEALTHY_STREAK + 1))
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "compose-down-failed" -1 ""; return 1
  fi
  # ADR-012: compose.yml의 새 변수(INC_* 등 — compose.yml 머리말)를 빈 값으로 앞에 둔다(env는 뒤 값이 이긴다) — 이전 조건도 'variable is not set' 경고·원격 환경 값 섞임이 없다.
  #   렌더는 같다(빈 값 = 키 없음). meta.json app_env(config_env)에는 넣지 않는다(이전 조건의 기록 그대로)
  if ! compose_up "$DEPLOY_ID" $INC_BLANK_ENV $cfg_env >> "$dir/compose.log" 2>&1; then
    UNHEALTHY_STREAK=$((UNHEALTHY_STREAK + 1))
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "compose-up-failed" -1 ""; return 1
  fi
  if ! wait_health 180 || ! reset_with_retry "$dir" 1 10000 > /dev/null; then
    UNHEALTHY_STREAK=$((UNHEALTHY_STREAK + 1))
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "unhealthy" -1 ""; return 1
  fi
  # ADR-009 F: 대조 컨테이너도 떠야 한다(기동 검사 실패 — 만료 스케줄러·대조 키 — 면 여기서 끊긴다)
  if (( HAS_F )) && ! BASE_URL="$RECONCILER_URL" wait_health 180; then
    UNHEALTHY_STREAK=$((UNHEALTHY_STREAK + 1))
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "reconciler-unhealthy" -1 ""; return 1
  fi
  # ADR-012 M3: 업무 앱이 스키마(Flyway — V9·V10)를 만든 뒤 외부 앱을 띄운다(외부 앱은 같은 IncrementalSchemaCheck로 확인만) — 기동 검사 실패·health 시한이면 external-unhealthy
  if (( HAS_X )) && ! external_app_up "$SHA" "$dir" $(ext_env); then
    UNHEALTHY_STREAK=$((UNHEALTHY_STREAK + 1))
    external_app_logs "$dir"   # 기동 실패 원인(기동 검사 메시지)을 남긴다
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "external-unhealthy" -1 ""; return 1
  fi
  UNHEALTHY_STREAK=0
  "$K6_BIN" run --quiet "$SCENARIOS/warmup.js" > "$dir/warmup.log" 2>&1   # ADR-011 --k6-bin(기본 k6)
  local seed; seed=$(seed_of "$base")
  if ! reset_with_retry "$dir" $seed > "$dir/seed.json"; then
    finish_rep "$dir" "$level" "$cell" "$rep" "$started" "|" "seed-failed" -1 ""; return 1
  fi
  local seed_done=$SECONDS seed_t; seed_t=$(date +%s.%N)
  db_state > "$dir/db-state-seed.json" 2>>"$dir/errors.log" || echo "db_state seed failed" >> "$dir/errors.log"   # 리뷰 R2 — 시드 끝 DB 상태
  # ADR-009 C·F: 대조가 실제로 도는지 — 시드 뒤 첫 바퀴 기록이 생길 때까지 기다린다(최대 주기 + 60초). 못 보면 reconcile-first.json runs = 0 → rep_status reconcile-missing
  if (( HAS_C || HAS_F || HAS_INC )); then
    # ADR-012 2단계: 새 조건은 첫 증분 틱(source = INC_SOURCE — 주기 1·5초라 대기점 안에 온다)을 같은 방식으로 기다린다
    # 기다리는 상한 = 대기점(PRE_K6_HOLD_S) — 그 안에 첫 바퀴가 없으면 목표 시각에 k6를 시작할 수 없다 → 아래 pre-k6-overrun(하네스 실패)로 멈춘다(리뷰 H1:
    # reconcile-missing(결과 사유)로 남기지 않는다 — 첫 바퀴가 늦은 것은 이 하네스의 시작 맞춤 실패다)
    wait_reconcile_run "$(if (( HAS_INC )); then echo "$INC_SOURCE"; elif (( HAS_F )); then echo reconcile-proc; else echo reconcile-app; fi)" "$PRE_K6_HOLD_S" > "$dir/reconcile-first.json" 2>>"$dir/errors.log" \
      || echo "wait_reconcile_run failed" >> "$dir/errors.log"
  fi
  # 명세 §13(교란 수정): 모든 조건이 k6 전에 같은 시간을 거친다 — 시드 끝(DB 준비 = /internal/reset 반환, ANALYZE 포함) 뒤 PRE_K6_HOLD_S까지 기다린다
  # (대조 조건은 첫 바퀴를 본 뒤 남은 만큼, 대조 없는 조건은 그 길이 전부). 옛 하네스는 대조 조건만 약 30~43초 더 기다려 k6 시작이 DB autovacuum과 다르게 겹쳤다
  local now_t hold_left; now_t=$(date +%s.%N)
  local hold_reached; hold_reached=$(awk -v a="$now_t" -v b="$seed_t" 'BEGIN { printf "%.3f", a - b }')
  hold_left=$(awk -v h="$PRE_K6_HOLD_S" -v r="$hold_reached" 'BEGIN { d = h - r; printf "%.3f", (d > 0 ? d : 0) }')
  # 리뷰 H1·R5: 대기점을 넘었거나(첫 바퀴가 늦음) 대조 조건인데 첫 바퀴를 못 봤으면 k6 전에 멈춘다 — 하네스 실패(pre-k6-overrun, 재측정 대상)
  if awk -v r="$hold_reached" -v h="$PRE_K6_HOLD_S" 'BEGIN { exit !(r > h + 1) }' \
     || { (( HAS_C || HAS_F || HAS_INC )) && ! jq -e '.runs > 0' "$dir/reconcile-first.json" > /dev/null 2>&1; }; then
    abort_pre_k6 "$dir" "$level" "$cell" "$rep" "$started" "hold-reached=${hold_reached}s"; return 1
  fi
  sleep "$hold_left"

  snapshot "$dir" before
  app_config "$dir" 2>>"$dir/errors.log" || echo "app_config failed" >> "$dir/errors.log"
  # 매수 방식 키 흔적(개수만 — 환경 전체는 남기지 않는다). ADR-009: 기대 개수는 조건마다(B·B+C = 1) — rep_status
  limit_keys "$dir" 2>>"$dir/errors.log" || echo "limit_keys failed" >> "$dir/errors.log"
  db_triggers > "$dir/db-triggers.json" 2>>"$dir/errors.log" || echo "db_triggers failed" >> "$dir/errors.log"
  # ADR-012 2단계: 스키마(V9 열·인덱스 · 트리거 user_hold_quota_touch · V10 테이블)가 방식과 맞나 — rep_status schema-mismatch
  if (( HAS_INC )); then db_schema > "$dir/db-schema.json" 2>>"$dir/errors.log" || echo "db_schema failed" >> "$dir/errors.log"; fi
  pg_settings > "$dir/pg-settings.json" 2>>"$dir/errors.log" || echo "pg_settings failed" >> "$dir/errors.log"
  SAMPLER_COPY="$(sampler_copy_of "$level" "$cell" "$rep")"
  HOST_COPY="${SAMPLER_COPY%.jsonl}-host.jsonl" HOST_IOSTAT="${SAMPLER_COPY%.jsonl}-iostat.txt"
  if host_devices docker > "$dir/host-devices.json" 2>>"$dir/errors.log" && jq -e '.sampled | length > 0' "$dir/host-devices.json" > /dev/null 2>&1; then
    HOST_DEVS="$(jq -r '.sampled | join(" ")' "$dir/host-devices.json")"
  else
    echo "$(date -Is) host_devices failed — sampler uses default devices: $HOST_DEVS" >> "$dir/errors.log"
  fi
  local pids; pids=$(start_pollers "$dir" "$cell")
  local w waited=0; for w in $(seq 1 30); do [[ -s "$dir/timeline-locks.jsonl" ]] && { waited=1; break; }; sleep 0.5; done
  (( waited )) || echo "$(date -Is) samples-before-load timeout: timeline-locks.jsonl 15s 동안 비어 있음 — 표본 없이 부하 시작" >> "$dir/errors.log"
  waited=0; for w in $(seq 1 20); do [[ -s "$dir/timeline-host.jsonl" ]] && { waited=1; break; }; sleep 0.5; done
  (( waited )) || echo "$(date -Is) host-samples-before-load timeout: timeline-host.jsonl 10s 동안 비어 있음" >> "$dir/errors.log"
  # (리뷰 R6) 옛 'IO_SETTLE_S만큼 시드 뒤 기다리기'는 명세 §13 대기점(70초 ≥ IO_SETTLE_S 11초)이 늘 덮어 할 일이 없다 — 지웠다. k6 뒤의 IO_SETTLE_S 대기는 그대로
  local wait_io
  io_snapshot > "$dir/io-before.json" 2>>"$dir/errors.log" || echo "io_snapshot before failed" >> "$dir/errors.log"
  local deadlocks0; deadlocks0=$(db_scalar "SELECT deadlocks FROM pg_stat_database WHERE datname='seat'" 2>/dev/null)
  app_counters "$dir/before-k6.json" 2>>"$dir/errors.log"
  drift_snaps "$dir" before
  # 명세 §13: 마지막 맞춤 — 시드 끝 → k6 시작이 PRE_K6_TARGET_S가 되게 남은 만큼 더 기다린다(준비 단계 길이 차를 지운다). 넘었으면 기다리지 않는다(rep_status pre-k6-mismatch가 판정)
  db_state > "$dir/db-state-k6.json" 2>>"$dir/errors.log" || echo "db_state k6 failed" >> "$dir/errors.log"   # 리뷰 R2 — k6 직전 DB 상태(맞춤 sleep 앞)
  # ADR-012 R5(하네스 ⑧): updated_at 방식(V9)만 — 쿼터 테이블 갱신·HOT 갱신 누적 · updated_at 인덱스 크기를 k6 직전·회차 끝에(quota-hot.json). HOT 비율은 io-before/after에도 있다
  local qh0=null; [[ "$INC_METHOD" == trg || "$INC_METHOD" == stmt ]] && { qh0=$(quota_hot 2>>"$dir/errors.log") && jq -e . <<< "$qh0" > /dev/null 2>&1 || qh0=null; }
  local now2; now2=$(date +%s.%N)
  # 리뷰 H1: 준비 단계까지 마친 시각이 이미 목표 + 허용을 넘었으면(첫 요청이 허용 밖이 된다) k6 전에 멈춘다
  if awk -v n="$now2" -v b="$seed_t" -v t="$PRE_K6_TARGET_S" -v l="$PRE_K6_TOL_S" -v lead="$PRE_K6_LEAD_S" 'BEGIN { exit !((n - b) + lead > t + l) }'; then
    stop_pollers $pids; remote "pkill -f 'FROM pg_locks WHERE NOT granted' || true" > /dev/null 2>&1
    recover_samples "$dir"; host_sampler_kill "$HOST_COPY"; host_recover "$dir" "$HOST_COPY" "$HOST_IOSTAT"   # 서버 사본 지우기(서버 디스크)
    abort_pre_k6 "$dir" "$level" "$cell" "$rep" "$started" "setup-done=$(awk -v n="$now2" -v b="$seed_t" 'BEGIN { printf "%.3f", n - b }')s"; return 1
  fi
  local topup; topup=$(awk -v t="$PRE_K6_TARGET_S" -v n="$now2" -v b="$seed_t" -v lead="$PRE_K6_LEAD_S" 'BEGIN { d = t - lead - (n - b); printf "%.3f", (d > 0 ? d : 0) }')
  # ADR-011 측정기(--probes): 맞춤 sleep 앞에 띄운다 — PC 측정기는 배경 프로세스, 서버 측정기는 배경 ssh(기동 왕복이 시드 끝 → 첫 요청 맞춤을 밀지 않게).
  # 서버 측정기 첫 요청은 주 k6 시작과 몇 초 어긋날 수 있다 — 분석 창은 램프(60초) 뒤라 덮인다(판정 = probe-check.json)
  (( PROBES )) && probes_start "$dir" "$cell"
  sleep "$topup"

  local k6env; k6env=$(k6_env_of "$cell")
  local clk_before clk_after; clk_before=$(clock_offset 2>>"$dir/errors.log")
  local clk_ext_before="" clk_ext_after=""; (( HAS_X )) && clk_ext_before=$(SERVER="$EXT_SERVER" clock_offset 2>>"$dir/errors.log")   # ADR-012 ⑥: .158 시계 − PC 시계
  local dash=()
  [[ "$base" != S1 && "$base" != S2 && "$base" != S7 ]] && dash=(K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_PORT=-1 "K6_WEB_DASHBOARD_EXPORT=$dir/k6-dashboard.html")
  # ADR-009 마감 임박 셀(S3c — 설계 §9): k6 바로 전에 회차 starts_at = 지금 + 600초(+ k6 시작까지 예상 지연 CLOSE_LEAD_S, 기본 1초)를 DB에 넣고 close.json에 남긴다.
  # reset 인자로 넣지 않는 이유 = 시드~k6 준비 시간(회차마다 다름)이 오차가 된다. k6에는 서버 시계 epoch ms(CLOSE_AT_MS)와 시계 차(ms)를 넘긴다(시나리오 머리말)
  local bg_jobs=()
  if [[ "$base" == S3c ]]; then
    local cj; cj=$(close_set_starts "$(( 600 + CLOSE_LEAD_S ))" 2>>"$dir/errors.log")
    if jq -e '.updated == 1 and (.starts_at_epoch | type == "number")' <<< "$cj" > /dev/null 2>&1; then
      echo "$cj" > "$dir/close.json"
      local off_ms; off_ms=$(jq -r '(.offset_s // 0) * 1000 | floor' <<< "${clk_before:-null}" 2>/dev/null); [[ "$off_ms" =~ ^-?[0-9]+$ ]] || off_ms=0
      k6env+=" CLOSE_AT_MS=$(jq -r '.starts_at_epoch * 1000 | floor' <<< "$cj") CLOCK_OFFSET_MS=$off_ms"
      pre_close_job "$dir" "$(jq -r .starts_at_epoch <<< "$cj")" "$off_ms" & bg_jobs+=($!)
    else
      echo "$(date -Is) close_set_starts failed: ${cj:-빈 출력}" >> "$dir/errors.log"
    fi
  fi
  local k6_started; k6_started=$(date -Is)
  local k6_t0; k6_t0=$(date +%s.%N)
  # 명세 §13: 시드 끝(DB 준비) → k6 시작 실제 시간 — 모든 회차 meta.pre_k6 · rep_status pre-k6-mismatch
  jq -n --argjson seed "$seed_t" --argjson k6 "$k6_t0" --argjson hold "$PRE_K6_HOLD_S" --argjson target "$PRE_K6_TARGET_S" --argjson tol "$PRE_K6_TOL_S" \
        --argjson reached "$hold_reached" --argjson left "$hold_left" --argjson topup "$topup" \
        --argjson rw "$(jq -c '.waited_s // null' "$dir/reconcile-first.json" 2>/dev/null || echo null)" \
    '{mode: "fixed-after-seed", seed_done_epoch: $seed, k6_start_epoch: $k6, seed_to_k6_s: (($k6 - $seed) * 1000 | round / 1000),
      hold_s: $hold, target_s: $target, tol_s: $tol, reconcile_wait_s: $rw, hold_reached_s: $reached, hold_slept_s: $left, topup_s: $topup}' > "$dir/pre-k6.json"
  # ADR-009 효과 셀(S3e — 설계 §9 주입기): k6 시작 기준 INJECT_ROUNDS의 초마다 주입(배경 작업 — injected.jsonl 한 줄씩). 끝나면 injected.json으로 묶는다
  [[ "$base" == S3e ]] && { injector_job "$dir" "$k6_t0" & bg_jobs+=($!); }
  local k6_exit
  if (( GCTRACE )); then
    # ADR-011 --gctrace: Go GC 기록(GODEBUG=gctrace=1 — 런타임이 GC 한 바퀴마다 stderr에 'gc N @…' 한 줄). stdout은 이전처럼 k6-stdout.log, stderr는 줄 나누기(gctrace_split)로 —
    # gc 줄은 받은 순간의 PC epoch를 앞에 붙여 k6-gctrace.log, 나머지 stderr 줄은 k6-stdout.log에 덧붙인다(둘 다 O_APPEND). 종료 코드 = 파이프의 k6 쪽(PIPESTATUS[0])
    : > "$dir/k6-stdout.log"
    env $k6env "${dash[@]}" BASE_URL="$BASE_URL" OUT_DIR="$dir" GODEBUG=gctrace=1 \
      "$K6_BIN" run --quiet --out "csv=$dir/k6-requests.csv.gz" "$SCENARIOS/$(script_of "$base")" 2>&1 >> "$dir/k6-stdout.log" \
      | gctrace_split "$dir/k6-stdout.log" "$dir/k6-gctrace.log"
    local pst=("${PIPESTATUS[@]}"); k6_exit=${pst[0]}
    # 리뷰 L1: 줄 나누기(파이썬) 종료 코드와 주 k6 끝 시각을 남긴다 — rep_status gctrace-missing(split ≠ 0 · 마지막 gc 줄이 주 k6 끝 − 30초보다 이름)
    echo "k6=${pst[0]} split=${pst[1]} k6_end_epoch=$(date +%s.%N)" > "$dir/gctrace-status.txt"
  else
    env $k6env "${dash[@]}" BASE_URL="$BASE_URL" OUT_DIR="$dir" \
      "$K6_BIN" run --quiet --out "csv=$dir/k6-requests.csv.gz" "$SCENARIOS/$(script_of "$base")" > "$dir/k6-stdout.log" 2>&1
    k6_exit=$?
  fi
  local k6_t1; k6_t1=$(date +%s.%N)
  local k6_ended; k6_ended=$(date -Is)
  local k6_done=$SECONDS
  jq -n --arg s "$k6_started" --arg e "$k6_ended" --argjson t0 "$k6_t0" '{k6_started:$s, k6_ended:$e, k6_t0_epoch:$t0}' > "$dir/k6-window.json"
  (( PROBES )) && probes_stop "$dir" "$k6_t0" "$k6_t1"   # ADR-011: 주 k6가 끝나자마자 측정기를 내린다(probe-status.json)
  clk_after=$(clock_offset 2>>"$dir/errors.log")
  jq -n --argjson b "${clk_before:-null}" --argjson a "${clk_after:-null}" '{before:$b, after:$a}' > "$dir/clock-offset.json" \
    || echo '{"before":null,"after":null}' > "$dir/clock-offset.json"
  # ADR-012 ⑥(M3): .158 시계 차(PC 기준 — .158 ↔ .164 = 이 값 − clock-offset.json, 외부 앱 기록 시각(counter_drift_log.at · 틱 started_at)을 맞출 때)
  if (( HAS_X )); then
    clk_ext_after=$(SERVER="$EXT_SERVER" clock_offset 2>>"$dir/errors.log")
    jq -n --argjson b "${clk_ext_before:-null}" --argjson a "${clk_ext_after:-null}" --arg s "$EXT_SERVER" '{server:$s, before:$b, after:$a}' > "$dir/clock-offset-ext.json" \
      || echo '{"before":null,"after":null}' > "$dir/clock-offset-ext.json"
  fi
  after_k6 "$dir" "$deadlocks0" 2>>"$dir/errors.log" || echo "after_k6 failed" >> "$dir/errors.log"
  drift_snaps "$dir" after-k6
  wait_io=$(( ${IO_SETTLE_S:-11} - (SECONDS - k6_done) )); (( wait_io > 0 )) && sleep "$wait_io"
  io_snapshot > "$dir/io-after.json" 2>>"$dir/errors.log" || echo "io_snapshot after failed" >> "$dir/errors.log"
  pg_storage > "$dir/pg-storage.json" 2>>"$dir/errors.log" || echo "pg_storage failed" >> "$dir/errors.log"
  [[ "$base" == S2 || "$base" == S7 ]] && { end_state "$dir" "$base" "$k6env" 2>>"$dir/errors.log" || echo "end_state failed" >> "$dir/errors.log"; }

  local grace; grace=$(grace_of "$base")
  [[ "$base" == S3* ]] && sleep $(( $(ttl_s_of "$base") + grace + 2 ))
  # 주입·마감 표본 배경 작업은 k6보다 먼저 끝난다(마지막 주입 +600초 · 마감 표본 +595초 < k6 약 1,000초) — 남았으면 기다린다(무음 유실 금지)
  local j; for j in "${bg_jobs[@]}"; do wait "$j" 2>/dev/null; done
  CONSISTENCY_TIMEOUT=$S4C_CONSISTENCY_S consistency "$grace" > "$dir/consistency.json" 2>>"$dir/errors.log"   # 600초 — S4c 구간 상한 계산(머리 S4C_STEPS_MAX)에 든다
  # ADR-011 E3(리뷰 rv34): 끝 정합성 쿼리 뒤 저장 공간·메모리 최고치를 한 번 더(기록만 — 임시 파일 여유 확인. 넘치면 db-log-fatal·consistency 실패로 드러난다)
  pg_storage > "$dir/pg-storage-end.json" 2>>"$dir/errors.log" || echo "pg_storage end failed" >> "$dir/errors.log"
  # ADR-009 회차 끝 수집: 앱(·F) 지표 · V7 기록 테이블 · 효과 셀 끝 쌍 상태 · 마감 임박 셀 정리 상태
  drift_snaps "$dir" end
  drift_db_dump $( ((HAS_INC)) && echo v10) > "$dir/drift-db.json" 2>>"$dir/errors.log" || echo "drift_db_dump failed" >> "$dir/errors.log"   # ADR-012: V10 기록 테이블도(새 조건)
  if [[ "$INC_METHOD" == trg || "$INC_METHOD" == stmt ]]; then
    local qh1; qh1=$(quota_hot 2>>"$dir/errors.log") && jq -e . <<< "$qh1" > /dev/null 2>&1 || qh1=null
    jq -n --argjson a "$qh0" --argjson b "$qh1" '{k6: $a, end: $b}' > "$dir/quota-hot.json"
  fi
  # 리뷰 H2·H5·R2: pre-k6.json에 첫 요청 시각·대조 바퀴 위상·대기 중 DB 상태를 더한다(판정 기준 = 시드 끝 → 첫 요청)
  python3 "$SUMMARIZE" --pre-k6-finish "$dir" 2>>"$dir/errors.log" || echo "pre-k6 finish failed" >> "$dir/errors.log"
  if [[ "$base" == S3e ]]; then
    injected_finish "$dir"
    # ADR-012 --inject-app on: 앱 경로 주입 쌍(drift-db.json app_inject — path hold·expiry)도 끝 상태에 넣는다(주입 밖 끝 어긋남·오탐 판정에서 주입 쌍으로)
    local appinj=""; (( INJECT_APP )) && appinj=$(jq -r '[.app_inject[]? | select(.path != "db") | "(\(.schedule_id),\(.user_id))"] | unique | join(",")' "$dir/drift-db.json" 2>/dev/null)
    local injp; injp=$(jq -r '[.rounds[].pairs[] | "(\(.schedule_id),\(.user_id))"] | join(",")' "$dir/injected.json" 2>/dev/null)
    end_pairs "$injp${injp:+${appinj:+,}}$appinj" > "$dir/end-pairs.json" 2>>"$dir/errors.log" \
      || echo "end_pairs failed" >> "$dir/errors.log"
  fi
  [[ "$base" == S3c ]] && { close_end > "$dir/close-end.json" 2>>"$dir/errors.log" || echo "close_end failed" >> "$dir/errors.log"; }
  stop_pollers $pids
  remote "pkill -f 'FROM pg_locks WHERE NOT granted' || true" > /dev/null 2>&1
  recover_samples "$dir"
  host_sampler_kill "$HOST_COPY"
  host_recover "$dir" "$HOST_COPY" "$HOST_IOSTAT"
  snapshot "$dir" after

  # H-12: 파이프 실패(ssh·docker logs)와 빈 로그를 드러낸다 — logs-status.txt 한 줄씩, rep_status container-log-missing
  local c lf
  for c in app db $( ((HAS_F)) && echo reconciler); do
    lf="$dir/$c.log.gz"
    remote "docker logs --since '$started' seatlab-$c-1 2>&1" | gzip > "$lf"
    echo "$c pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]} bytes=$(zcat "$lf" 2>/dev/null | wc -c)" >> "$dir/logs-status.txt"
  done
  # 명세 §13: JVM GC 로그(compose JAVA_TOOL_OPTIONS -Xlog:async -Xlog:gc*,safepoint:file=/dev/shm/gc.log — 컨테이너가 회차마다 새로 뜨므로 이 회차 전체) → gc.log.gz · F면 reconciler-gc.log.gz.
  # 수집기는 고르지 않는다(JVM 기본 선택 그대로 — 기록만: gc.json collector). 상태는 gc-status.txt(rep_status gc-log-missing)
  for c in app $( ((HAS_F)) && echo reconciler); do
    lf="$dir/$([[ $c == app ]] && echo gc || echo reconciler-gc).log.gz"
    remote "docker exec seatlab-$c-1 cat /dev/shm/gc.log" | gzip > "$lf"
    echo "$c pipe=${PIPESTATUS[0]}/${PIPESTATUS[1]} bytes=$(zcat "$lf" 2>/dev/null | wc -c)" >> "$dir/gc-status.txt"
  done
  python3 "$SUMMARIZE" --gc "$dir" > "$dir/gc.json" 2>>"$dir/errors.log" || echo "gc summary failed" >> "$dir/errors.log"
  # ADR-012 M3: 외부 앱 로그·GC 로그(external-status.txt — rep_status external-log-missing) 뒤 내리기(회차 끝 — 다음 회차 앞에서도 다시 내린다).
  #   내리기 결과는 external-down.txt(rc) — 0이 아니면 rep_status external-down-failed(하네스 실패 — campaign이 기동 실패와 같이 센다 · rv46)
  if (( HAS_X )); then
    external_app_logs "$dir"
    external_app_down "$SHA" >> "$dir/external-compose.log" 2>&1; local ext_down_rc=$?
    echo "rep-end rc=$ext_down_rc" > "$dir/external-down.txt"
    (( ext_down_rc == 0 )) || echo "$(date -Is) external_app_down failed at rep end (rc=$ext_down_rc)" >> "$dir/errors.log"
  fi
  # ADR-010(리뷰): 앱 컨테이너에 실제로 걸린 메모리 한도(bytes)와 JAVA_TOOL_OPTIONS 한 변수만 기록 — 환경 전체는 남기지 않는다. rep_status app-jvm-mismatch
  local amem ajto
  amem="$(remote "docker inspect --format '{{.HostConfig.Memory}}' seatlab-app-1" 2>/dev/null | tr -d '[:space:]')"
  ajto="$(remote "docker exec seatlab-app-1 sh -c 'printf %s \"\$JAVA_TOOL_OPTIONS\"'" 2>/dev/null)"
  jq -n --arg mem "$amem" --arg jto "$ajto" --arg want_mem "$APP_MEM" --arg want_gc "$APP_GC" \
    '{mem_bytes:($mem|tonumber? // null), java_tool_options:$jto, want_mem:$want_mem, want_gc:$want_gc}' > "$dir/app-jvm.json"
  gzip -f "$dir/k6-stdout.log"
  (( GCTRACE )) && gzip -f "$dir/k6-gctrace.log"
  # ADR-009 효과 지표(effect.json — 요약기 --effect, 주입 셀만). rep_status 전에 만든다(합격 판정이 읽는다)
  # ADR-012 새 조건: 증분·안전망 source와 앱 경로 주입 여부를 함께 넘긴다(허용 출처 · R4·R6 — summarize.py effect)
  [[ "$base" == S3e ]] && { python3 "$SUMMARIZE" --effect "$dir" "$COND" $( ((HAS_INC)) && echo "$INC_SOURCE $SAFETY_SOURCE $INJECT_APP") > /dev/null 2>>"$dir/errors.log" || echo "effect failed" >> "$dir/errors.log"; }

  finish_rep "$dir" "$level" "$cell" "$rep" "$started" "$k6_started|$k6_ended" "$(rep_status "$dir" "$base" "$k6_exit" "$k6_started" "$k6_ended")" "$k6_exit" "$k6env"
}

# 리뷰 H1·R5: k6 전 맞춤 실패 — k6를 시작하지 않고 회차를 pre-k6-overrun(하네스 실패, 재측정 대상)으로 끝낸다. 정리(컨테이너 내리기)는 다음 회차·끝의 compose_down
abort_pre_k6() {  # 인자: 회차폴더 level cell rep started 설명
  echo "$(date -Is) pre-k6-overrun: $6 (대기점 ${PRE_K6_HOLD_S}s · 목표 ${PRE_K6_TARGET_S}±${PRE_K6_TOL_S}s) — k6 전에 멈춤" >> "$1/errors.log"
  remote "docker logs --since '$5' seatlab-app-1 2>&1" | gzip > "$1/app.log.gz"
  finish_rep "$1" "$2" "$3" "$4" "$5" "|" "pre-k6-overrun" -1 ""
}

# ---- ADR-011 측정기·Go GC 기록 ----------------------------------------------------------------------------
# --gctrace: 주 k6 stderr 줄 나누기. 줄을 받은 순간(time.time — PC 시계)을 gc 줄 앞에 붙인다. 파이프를 블록 버퍼로 기다리지 않는다(BufferedReader는 pipe에서 온 만큼만 읽는다 —
# mawk 입력 버퍼링 사고(리뷰 A11) 재발 금지). 인자: 나머지 stderr를 덧붙일 파일 · gc 줄 파일
gctrace_split() {
  python3 -I -c '
import re, sys, time
out = open(sys.argv[1], "ab", buffering=0)
gc = open(sys.argv[2], "wb")
pat = re.compile(rb"^gc [0-9]+ @")
n = 0
for line in sys.stdin.buffer:
    t = time.time()
    if pat.match(line):
        gc.write(b"%.6f " % t + line)
        n += 1
        if n % 20 == 0:
            gc.flush()
    else:
        out.write(line)
gc.close()
out.close()
' "$1" "$2"
}
# 측정기 길이(초): 주 k6 창(S4c = 램프 60 + 구간 × 30)보다 PROBE_TAIL_S(180 — k6 기동·종료 + 내리기 여유) 길게 — 주 k6가 끝나면 신호로 멈춘다(probes_stop).
# 다른 셀은 1시간(측정기는 S4c용 — 다른 셀에서도 돌지만 판정 창은 같다)
probe_duration_of() { case "$(cell_base "$1")" in S4c*) echo $(( 60 + S4C_STEPS * 30 + ${PROBE_TAIL_S:-180} )) ;; *) echo 3600 ;; esac; }
probes_start() {  # 인자: 회차폴더 셀 — PROBE_PC_PID·PROBE_SRV_JOB·PROBE_PC_T0·PROBE_DUR를 정한다
  local dir="$1"
  PROBE_DUR=$(probe_duration_of "$2")
  # 서버 측정기: 배경 ssh(lib.sh probe_server_up) — 시작·끝 시각과 종료 코드를 .probe-server-up에(probes_stop이 probe-status.json으로 묶는다)
  ( t=$(date +%s.%N); probe_server_up "$DEPLOY_ID" "$PROBE_DUR" >> "$dir/probe-server.log" 2>&1; rc=$?; echo "$t $(date +%s.%N) $rc" > "$dir/.probe-server-up" ) > /dev/null 2>&1 &
  PROBE_SRV_JOB=$!
  # PC 측정기: 주 k6와 다른 프로세스(--probe-k6-bin) — 요청 시각 ms(K6_CSV_TIME_FORMAT=unix_milli)
  PROBE_PC_T0=$(date +%s.%N)
  env BASE_URL="$BASE_URL" PROBE_RATE="$PROBE_RATE" PROBE_DURATION_S="$PROBE_DUR" K6_CSV_TIME_FORMAT=unix_milli \
    "$PROBE_K6_BIN" run --quiet --out "csv=$dir/k6-probe-pc.csv.gz" "$SCENARIOS/probe-ping.js" > "$dir/probe-pc.log" 2>&1 < /dev/null &
  PROBE_PC_PID=$!
  echo "$PROBE_PC_PID" > "$dir/probe-pc.pid"   # 수집기(timeline-client)가 주 k6를 고를 때 뺀다
}
probes_stop() {  # 인자: 회차폴더 주k6시작epoch 주k6끝epoch → probe-status.json(판정은 rep_status — tail.py --probe-check)
  local dir="$1" i killed=false pc_rc pc_sig pc_end srv up0="" up1="" uprc=""
  # 기동 ssh(보통 수 초 — 주 k6보다 먼저 끝나 있다). ssh 자체에 시한 PROBE_UP_TIMEOUT_S(120 — lib.sh probe_server_up, 넘으면 rc 124)가 있고,
  # 여기서도 시작부터 그 시한 + 10초가 지나도 남아 있으면 배경 작업과 자식(ssh)을 끈다(up_rc = killed → probe-missing). 그 뒤 두 측정기 정리는 아래 그대로
  local up_deadline; up_deadline=$(awk -v t="$PROBE_PC_T0" -v m="${PROBE_UP_TIMEOUT_S:-120}" 'BEGIN { printf "%d", t + m + 10 }')
  while kill -0 "$PROBE_SRV_JOB" 2>/dev/null && (( $(date +%s) < up_deadline )); do sleep 1; done
  if kill -0 "$PROBE_SRV_JOB" 2>/dev/null; then
    pkill -P "$PROBE_SRV_JOB" 2>/dev/null; kill "$PROBE_SRV_JOB" 2>/dev/null
    echo "$(date -Is) probe server up: 시한 초과 — 배경 기동을 끔" >> "$dir/errors.log"
    [[ -s "$dir/.probe-server-up" ]] || echo "$PROBE_PC_T0 $(date +%s.%N) killed" > "$dir/.probe-server-up"
  fi
  wait "$PROBE_SRV_JOB" 2>/dev/null
  pc_sig=$(date +%s.%N)
  kill -INT "$PROBE_PC_PID" 2>/dev/null   # k6 정상 종료(CSV를 닫는다 — 종료 코드 105)
  for i in $(seq 1 60); do kill -0 "$PROBE_PC_PID" 2>/dev/null || break; sleep 0.5; done
  if kill -0 "$PROBE_PC_PID" 2>/dev/null; then kill -KILL "$PROBE_PC_PID" 2>/dev/null; killed=true; fi
  wait "$PROBE_PC_PID" 2>/dev/null; pc_rc=$?
  pc_end=$(date +%s.%N)
  srv=$(probe_server_collect "$DEPLOY_ID" "$dir" 2>>"$dir/errors.log") && jq -e . <<< "$srv" > /dev/null 2>&1 || srv=null
  [[ -s "$dir/.probe-server-up" ]] && read -r up0 up1 uprc < "$dir/.probe-server-up"
  rm -f "$dir/.probe-server-up"
  local f; for f in probe-pc.log probe-server.log; do [[ -f "$dir/$f" ]] && gzip -f "$dir/$f"; done
  jq -n --argjson rate "$PROBE_RATE" --argjson dur "$PROBE_DUR" --argjson k0 "$2" --argjson k1 "$3" \
        --arg pbin "$PROBE_K6_BIN" --arg pver "$PROBE_K6_VERSION" --argjson ppid "$PROBE_PC_PID" --argjson pt0 "$PROBE_PC_T0" --argjson psig "$pc_sig" \
        --argjson pend "$pc_end" --argjson prc "$pc_rc" --argjson killed "$killed" \
        --arg image "$PROBE_IMAGE" --arg up0 "$up0" --arg up1 "$up1" --arg uprc "$uprc" --argjson srv "$srv" \
    '{rate: $rate, duration_s: $dur, main: {k6_t0_epoch: $k0, k6_end_epoch: $k1},
      pc: {bin: $pbin, version: $pver, pid: $ppid, start_epoch: $pt0, stop_signal_epoch: $psig, end_epoch: $pend, exit_code: $prc, killed: $killed},
      server: ({image: $image, up_start_epoch: ($up0 | tonumber? // null), up_end_epoch: ($up1 | tonumber? // null), up_rc: ($uprc | tonumber? // null)}
               + (if $srv == null then {collect: null} else $srv end))}' > "$dir/probe-status.json"
}

# ---- ADR-009 수집·배경 작업 ------------------------------------------------------------------------------
# 앱(·F) 지표 스냅숏 → drift-<라벨>.json = {"app":{…}, "reconciler":{…}|없음}. 조회 실패는 그 키가 null(rep_status drift-snap-failed)
drift_snaps() {  # 인자: 회차폴더 라벨(before|after-k6|end)
  local dir="$1" label="$2" a r="null"
  a=$(drift_snap app 2>>"$dir/errors.log") || a=null
  jq -e . <<< "$a" > /dev/null 2>&1 || a=null
  if (( HAS_F )); then r=$(drift_snap reconciler 2>>"$dir/errors.log") || r=null; jq -e . <<< "$r" > /dev/null 2>&1 || r=null; fi
  jq -n --argjson a "$a" --argjson r "$r" --argjson f "$HAS_F" '{app:$a} + (if $f == 1 then {reconciler:$r} else {} end)' > "$dir/drift-$label.json"
  # ADR-012 M3: 외부 앱 지표(8103 — 증분·안전망 errors·borrows · Hikari)는 따로 drift-ext-<라벨>.json({external:{…}|null} — 이전 파일 모양은 그대로)
  if (( HAS_X )); then
    local e; e=$(python3 -I "$LIB_DIR/actsnap.py" "$EXTERNAL_URL" 2>>"$dir/errors.log") || e=null
    jq -e . <<< "$e" > /dev/null 2>&1 || e=null
    jq -n --argjson e "$e" '{external:$e}' > "$dir/drift-ext-$label.json"
  fi
}
# 효과 셀 주입기(배경): k6 시작(PC epoch) + 라운드 초마다 inject_drift 한 번(라운드별 종류 수 — H-1) — 결과 한 줄씩 injected.jsonl(실패도 한 줄 — 무음 금지)
injector_job() {  # 인자: 회차폴더 k6시작epoch
  local dir="$1" t0="$2" r at plan now excl="" out rc
  for r in $INJECT_ROUNDS; do
    at="${r%%:*}"; IFS=, read -r -a plan <<< "${r#*:}"
    now=$(date +%s.%N); sleep "$(awk -v a="$at" -v t="$t0" -v n="$now" 'BEGIN { d = t + a - n; print (d > 0 ? d : 0) }')"
    out=$(inject_drift "${plan[0]}" "${plan[1]}" "${plan[2]}" "${plan[3]}" "$excl" 2>&1); rc=$?
    if (( rc == 0 )) && jq -e '.pairs | type == "array"' <<< "$out" > /dev/null 2>&1; then
      jq -c --argjson s "$at" --argjson d "${plan[0]}" --argjson l1 "${plan[1]}" --argjson l2 "${plan[2]}" --argjson h "${plan[3]}" \
        '{at_s: $s, ok: true, planned: {D: $d, L1: $l1, L2: $l2, H: $h}} + .' <<< "$out" >> "$dir/injected.jsonl"
      excl+=$(jq -r '[.pairs[] | "(\(.schedule_id),\(.user_id))"] | join(",")' <<< "$out")","
      excl="${excl#,}"
    else
      jq -nc --argjson s "$at" --arg e "$(head -c 2000 <<< "$out")" --argjson rc "$rc" '{at_s: $s, ok: false, rc: $rc, error: $e}' >> "$dir/injected.jsonl"
    fi
  done
}
# injected.jsonl → injected.json {"plan":{rounds:[{at_s,D,L1,L2,H}], held_min_s, exp_margin_s}, "rounds":[…]} — 라운드별 계획보다 적게 골랐으면 rep_status inject-short
injected_finish() {
  local dir="$1"
  jq -s --arg r "$INJECT_ROUNDS" --argjson hm "$INJECT_HELD_MIN_S" --argjson em "$INJECT_EXP_MARGIN_S" \
    '{plan: {rounds: ($r | split(" ") | map(split(":") as [$a, $k] | ($k | split(",") | map(tonumber)) as $n | {at_s: ($a | tonumber), D: $n[0], L1: $n[1], L2: $n[2], H: $n[3]})),
             held_min_s: $hm, exp_margin_s: $em}, rounds: .}' \
    "$dir/injected.jsonl" > "$dir/injected.json" 2>>"$dir/errors.log" \
    || jq -n '{plan: null, rounds: []}' > "$dir/injected.json"
}
# 마감 5초 전 표본(배경 — S3c): 서버 시계 starts_at − 5초를 PC 시계로 옮겨(− 시계 차) 기다렸다가 pre_close_sample 한 번 → pre-close.json
pre_close_job() {  # 인자: 회차폴더 starts_at(서버 epoch 초) 시계차(ms)
  local dir="$1" sa="$2" off_ms="$3" now out
  now=$(date +%s.%N); sleep "$(awk -v s="$sa" -v o="$off_ms" -v n="$now" 'BEGIN { d = s - o / 1000 - 5 - n; print (d > 0 ? d : 0) }')"
  out=$(pre_close_sample 2>>"$dir/errors.log") && jq -e . <<< "$out" > /dev/null 2>&1 && echo "$out" > "$dir/pre-close.json" \
    || echo "$(date -Is) pre_close_sample failed" >> "$dir/errors.log"
}

# 부하 직후 누적 지표(ADR-003 ④⑤⑦) — 0.5~1초 표본이 놓치는 짧은 버스트를 누적값으로 본다. 앱 2대면 앱마다(lb 안에서 직접).
#   acquire: Hikari 커넥션 획득 대기 timer(COUNT·TOTAL_TIME 누적 / MAX는 최근 약 2분 창) · timeouts: 획득 타임아웃 수
#   hold_requests: 그 앱이 처리한 선점 요청 수(앱 2대에서 두 앱 모두 받았는지) · deadlocks_delta: 부하 중 DB 데드락 수
# 앱별 누적 카운터 한 벌을 파일로 — 부하 직전·직후에 한 번씩. 과부하 직후 앱이 적체를 푸는 중이면 응답이 늦어 실패할 수 있어 조회마다 재시도한다.
actuator_retry() {  # 인자: 앱 경로 — 성공한 JSON 또는 빈 출력
  local i out
  for i in 1 2 3 4 5 6; do
    out=$(app_actuator "$1" "$2" 2>/dev/null) && [[ -n "$out" ]] && { echo "$out"; return 0; }
    sleep 10
  done
  return 1
}
# ADR-005 앱 지표: 매수 제어 구간 타이머(prepare 트랜잭션 밖 쿼터 행 준비 · acquire · check · span = acquire 시작 ~ check 끝 — COUNT·TOTAL_TIME 누적 초, MAX 최근 창)와 SERIALIZABLE 카운터.
# Micrometer는 처음 기록될 때 지표를 만든다 — 지표 목록에 이름이 없으면 '아직 0'(카운터 0 · 타이머 0건). 목록 조회 자체가 실패하면 null(미측정).
limit_metrics() {  # 인자: 앱 → {"prepare"|"acquire"|"check"|"span":{COUNT,TOTAL_TIME,MAX},"serialization_failure":n,"retry":n} 또는 null
  local a="$1" names out='{}' m key v
  names=$(actuator_retry "$a" metrics | jq -c '.names') || { echo null; return; }
  [[ -n "$names" && "$names" != null ]] || { echo null; return; }
  for m in prepare acquire check span; do
    if jq -e --arg n "seat.hold.limit.$m" 'index($n) != null' <<< "$names" > /dev/null; then
      v=$(actuator_retry "$a" "metrics/seat.hold.limit.$m" | jq -c '[.measurements[] | {(.statistic): .value}] | add') || v=null
    else
      v='{"COUNT":0,"TOTAL_TIME":0,"MAX":0}'
    fi
    out=$(jq -c --arg k "$m" --argjson v "${v:-null}" '. + {($k): $v}' <<< "$out")
  done
  for key in serialization_failure retry; do
    if jq -e --arg n "seat.hold.limit.$key" 'index($n) != null' <<< "$names" > /dev/null; then
      v=$(actuator_retry "$a" "metrics/seat.hold.limit.$key" | jq -c '[.measurements[] | select(.statistic=="COUNT") | .value][0]') || v=null
    else
      v=0
    fi
    out=$(jq -c --arg k "$key" --argjson v "${v:-null}" '. + {($k): $v}' <<< "$out")
  done
  echo "$out"
}

# 선점 요청 수(요청당 커넥션 빌림의 분모 — 명세 §9.4 ③). 인자: 앱 → 숫자 또는 null.
#   0은 '아직 기록 없음'이 지표 목록에서 확인될 때만: http.server.requests가 목록에 없거나, 있는데 uri 태그 값에 선점 URI가 없을 때
#   (그 URI로 요청이 한 번도 없으면 태그 조회가 404). 그 밖의 실패(목록·태그 조회 실패, 값 아님)는 null — 0으로 채우면 직전 값이 0이 되어
#   차분이 누적값이 되고 빌림이 조용히 낮게 나온다(ADR-006 리뷰 지적). 조회마다 actuator_retry(과부하 직후 늦은 응답).
HOLD_URI='/api/schedules/{scheduleId}/seats/{seatId}/hold'
# ADR-008(리뷰 루프 2 D1): 확정 요청도 커넥션을 빌린다(S3) — 요청당 빌림의 분모에 넣으려고 같은 방식으로 센다. 시나리오가 보내는 앱 요청은 선점·확정 둘뿐이다
# (scenarios/lib/common.js http.post 2곳 — /internal·/actuator는 run.sh가 부하 밖에서 부르고, 그중 DB를 쓰는 /internal은 k6 직전·직후 스냅숏 사이에 없다)
CONFIRM_URI='/api/holds/{holdId}/confirm'
uri_requests() {  # 인자: 앱 URI템플릿 → 그 URI의 http.server.requests COUNT · 0(기록 없음 확인) · null(조회 실패) — 정의는 위 머리말(hold_requests)
  local a="$1" uri="$2" names base v
  names=$(actuator_retry "$a" metrics | jq -c '.names') || { echo null; return; }
  jq -e 'type == "array"' <<< "$names" > /dev/null 2>&1 || { echo null; return; }
  jq -e 'index("http.server.requests") != null' <<< "$names" > /dev/null || { echo 0; return; }
  base=$(actuator_retry "$a" metrics/http.server.requests) || { echo null; return; }
  jq -e '.availableTags | type == "array"' <<< "$base" > /dev/null 2>&1 || { echo null; return; }
  jq -e --arg u "$uri" '[.availableTags[] | select(.tag == "uri") | .values[]] | index($u) != null' <<< "$base" > /dev/null || { echo 0; return; }
  v=$(actuator_retry "$a" "metrics/http.server.requests?tag=uri:$(sed 's/{/%7B/g; s/}/%7D/g' <<< "$uri")" \
        | jq -c '[.measurements[] | select(.statistic=="COUNT") | .value][0]') || { echo null; return; }
  jq -e 'type == "number"' <<< "$v" > /dev/null 2>&1 && echo "$v" || echo null
}
hold_requests() { uri_requests "$1" "$HOLD_URI"; }
counter_or_zero() {  # 인자: 앱 지표이름 → COUNT · 0(목록에 이름 없음 = 아직 기록 없음) · null(조회 실패)
  local names v
  names=$(actuator_retry "$1" metrics | jq -c '.names') || { echo null; return; }
  jq -e 'type == "array"' <<< "$names" > /dev/null 2>&1 || { echo null; return; }
  jq -e --arg n "$2" 'index($n) != null' <<< "$names" > /dev/null || { echo 0; return; }
  v=$(actuator_retry "$1" "metrics/$2" | jq -c '[.measurements[] | select(.statistic=="COUNT") | .value][0]') || { echo null; return; }
  jq -e 'type == "number"' <<< "$v" > /dev/null 2>&1 && echo "$v" || echo null
}
confirm_requests() { uri_requests "$1" "$CONFIRM_URI"; }

app_counters() {  # 인자: 출력 파일
  local out="$1" arr="[]" a apps="app"
  for a in $apps; do
    local st lst pool acq tmo req lim props
    props=$(actuator_retry "$a" configprops)
    st=$(jq -c '[.contexts[].beans[] | select(.prefix=="seat.hold") | .properties.strategy][0]' <<< "$props")
    lst=$(jq -c '[.contexts[].beans[] | select(.prefix=="seat.hold") | .properties.limitStrategy][0]' <<< "$props")
    lim=$(limit_metrics "$a")
    pool=$(actuator_retry "$a" metrics/hikaricp.connections.max | jq -c '.measurements[0].value')
    # H-11: 대조·정리 빌림을 획득(acquire) 앞뒤로 두 번 읽어 같을 때만 쓴다(그 사이 바퀴가 빌리면 차감이 어긋난다) — 3번까지, 끝내 다르면 null(borrow-missing)
    local rb cb rb2 cb2 try ib="" sb="" ib2="" sb2=""
    for try in 1 2 3; do
      rb=$(counter_or_zero "$a" seat.hold.counter.reconcile.borrows); cb=$(counter_or_zero "$a" seat.hold.counter.cleanup.borrows)
      # ADR-012 M2(A+I): 증분 틱·안전망도 업무 앱 풀에서 빌린다 — 같은 앞뒤 두 번 읽기(§4.2.6 하네스 ⑤). M3 외부 앱의 빌림은 업무 앱 풀 밖이라 읽지 않는다
      if (( HAS_I )); then ib=$(counter_or_zero "$a" seat.hold.counter.reconcile.incremental.borrows); sb=$(counter_or_zero "$a" seat.hold.counter.safety.borrows); fi
      acq=$(actuator_retry "$a" metrics/hikaricp.connections.acquire | jq -c '[.measurements[] | {(.statistic): .value}] | add')
      rb2=$(counter_or_zero "$a" seat.hold.counter.reconcile.borrows); cb2=$(counter_or_zero "$a" seat.hold.counter.cleanup.borrows)
      if (( HAS_I )); then ib2=$(counter_or_zero "$a" seat.hold.counter.reconcile.incremental.borrows); sb2=$(counter_or_zero "$a" seat.hold.counter.safety.borrows); fi
      # ADR-012 스모크(10-20): 증분 틱(1초)·안전망은 두 읽기 사이에 늘 빌린다 — 같기를 요구하면 다시 읽기가 준비 시간을 넘긴다(pre-k6-overrun).
      # 같기는 대조(C)·정리만 요구하고, 증분·안전망은 앞 값을 쓰고 차이(두 읽기 사이 빌림)를 *_jitter로 남긴다(30분 수백만 획득 대비 몇 건)
      [[ "$rb" == "$rb2" && "$cb" == "$cb2" ]] && break
      echo "$(date -Is) app_counters: 빌림 지표가 획득 조회 사이에 바뀜(reconcile $rb→$rb2 cleanup $cb→$cb2${ib:+ incremental $ib→$ib2 safety $sb→$sb2}) — 다시 읽음 $try" >> "${out%/*}/errors.log"
    done
    [[ "$rb" == "$rb2" ]] || rb=null
    [[ "$cb" == "$cb2" ]] || cb=null
    local ij="" sj=""
    if (( HAS_I )); then
      [[ "$ib" =~ ^[0-9.eE+-]+$ && "$ib2" =~ ^[0-9.eE+-]+$ ]] && ij=$(awk -v a="$ib" -v b="$ib2" 'BEGIN{print b-a}') || ib=null
      [[ "$sb" =~ ^[0-9.eE+-]+$ && "$sb2" =~ ^[0-9.eE+-]+$ ]] && sj=$(awk -v a="$sb" -v b="$sb2" 'BEGIN{print b-a}') || sb=null
    fi
    tmo=$(actuator_retry "$a" metrics/hikaricp.connections.timeout | jq -c '.measurements[0].value')
    req=$(hold_requests "$a")   # 숫자 · 0(기록 없음 확인) · null(조회 실패) — 위 함수
    local creq; creq=$(confirm_requests "$a")   # ADR-008 D1 — 같은 규칙
    # ADR-009 빌림 식(설계 §4d): 대조(C)·정리가 앱 풀에서 빌린 수 — 요청당 빌림에서 뺀다. 이름이 목록에 없으면 0(아직 기록 없음), 조회 실패는 null
    arr=$(jq -c --arg a "$a" --argjson st "${st:-null}" --argjson ls "${lst:-null}" --argjson p "${pool:-null}" --argjson q "${acq:-null}" --argjson t "${tmo:-null}" --argjson r "${req:-null}" --argjson l "${lim:-null}" --argjson cr "${creq:-null}" \
          --argjson rb "${rb:-null}" --argjson cb "${cb:-null}" \
          '. + [{app:$a, strategy:$st, limit_strategy:$ls, pool:$p, acquire:$q, timeouts:$t, hold_requests:$r, confirm_requests:$cr, reconcile_borrows:$rb, cleanup_borrows:$cb, limit:$l}]' <<< "$arr")
    # ADR-012 M2: 증분·안전망 빌림 키는 A+I에서만 더한다(이전 조건의 파일 모양 그대로 — summarize.borrow_per_request가 키가 있을 때만 뺀다)
    (( HAS_I )) && arr=$(jq -c --argjson ib "${ib:-null}" --argjson sb "${sb:-null}" --argjson ij "${ij:-null}" --argjson sj "${sj:-null}" '.[-1] += {incremental_borrows:$ib, safety_borrows:$sb, incremental_borrows_jitter:$ij, safety_borrows_jitter:$sj}' <<< "$arr")
  done
  echo "$arr" > "$out"
}

after_k6() {  # 인자: 폴더 부하 전 deadlocks — 부하 중 증가분(직후 − 직전)을 남긴다. MAX는 누적이 아니라 최근 약 2분 창 그대로
  local dir="$1" d0="$2" arr
  app_counters "$dir/after-k6.raw.json"
  arr=$(jq -c --slurpfile b "$dir/before-k6.json" '[.[] as $x | ($b[0] // [] | map(select(.app == $x.app))[0]) as $y |
          $x + {acquire: (if $x.acquire and $y.acquire then {COUNT: ($x.acquire.COUNT - $y.acquire.COUNT), TOTAL_TIME: ($x.acquire.TOTAL_TIME - $y.acquire.TOTAL_TIME), MAX: $x.acquire.MAX} else null end),
                timeouts: (if $x.timeouts != null and $y.timeouts != null then $x.timeouts - $y.timeouts else null end),
                limit: (if $x.limit and $y.limit
                           and ([("prepare","acquire","check","span")] | all(. as $m | $x.limit[$m] and $y.limit[$m]))
                           and $x.limit.serialization_failure != null and $y.limit.serialization_failure != null
                           and $x.limit.retry != null and $y.limit.retry != null then
                          (reduce ("prepare","acquire","check","span") as $m ({};
                              . + {($m): {COUNT: ($x.limit[$m].COUNT - $y.limit[$m].COUNT), TOTAL_TIME: ($x.limit[$m].TOTAL_TIME - $y.limit[$m].TOTAL_TIME), MAX: $x.limit[$m].MAX}})
                           + {serialization_failure: ($x.limit.serialization_failure - $y.limit.serialization_failure),
                              retry: ($x.limit.retry - $y.limit.retry)}) else null end),
                hold_requests: (if $y and $x.hold_requests != null and $y.hold_requests != null then $x.hold_requests - $y.hold_requests else null end),
                confirm_requests: (if $y and $x.confirm_requests != null and $y.confirm_requests != null then $x.confirm_requests - $y.confirm_requests else null end),
                reconcile_borrows: (if $y and $x.reconcile_borrows != null and $y.reconcile_borrows != null then $x.reconcile_borrows - $y.reconcile_borrows else null end),
                cleanup_borrows: (if $y and $x.cleanup_borrows != null and $y.cleanup_borrows != null then $x.cleanup_borrows - $y.cleanup_borrows else null end)}
             # ADR-012 M2(A+I만 키가 있다): 증분·안전망 빌림 증가분 — 같은 규칙(어느 쪽이 null이면 null)
             + (reduce ("incremental_borrows", "safety_borrows") as $k ({}; if ($x | has($k)) then . + {($k): (if $y and $x[$k] != null and $y[$k] != null then $x[$k] - $y[$k] else null end)} else . end))]' "$dir/after-k6.raw.json") || arr="[]"
  local d1 redis
  d1=$(db_scalar "SELECT deadlocks FROM pg_stat_database WHERE datname='seat'")
  redis=$(remote "docker exec seatlab-redis-1 sh -c 'redis-cli info memory | grep ^used_memory: | cut -d: -f2; redis-cli dbsize'" | tr -d '\r' | tr '\n' ' ')
  jq -n --argjson apps "$arr" --arg d0 "$d0" --arg d1 "$d1" --arg redis "$redis" \
    '{apps:$apps, deadlocks_delta:(if ($d0|test("^[0-9]+$")) and ($d1|test("^[0-9]+$")) then ($d1|tonumber)-($d0|tonumber) else null end),
      redis:{used_memory:($redis|split(" ")[0]|tonumber? // null), keys:($redis|split(" ")[1]|tonumber? // null)}}' > "$dir/after-k6.json"
}

# ADR-005 끝 상태(DB 직접 — 앱 판정기와 별도로 사용자·좌석 단위가 필요하다). 인자: 폴더 셀 k6env
#   S2: 측정 회차(1)의 사용자별 홀드 수 + 확정 예약 수 → {"per_user": {"<user>": n}}
#   S7: + 대상 좌석(1..SEATS)별 상태·홀드 보유자 → {"per_seat": {"<seat>": {"status":…, "holders":[…]}}}
end_state() {
  local dir="$1" base="$2" k6env="$3" seats pu ps
  pu=$(db_scalar "SELECT coalesce(json_object_agg(user_id, n), '{}') FROM (SELECT user_id, sum(n) AS n FROM (SELECT user_id, count(*) AS n FROM seat_hold WHERE schedule_id = 1 GROUP BY 1 UNION ALL SELECT user_id, count(*) FROM reservation WHERE schedule_id = 1 AND status = 'CONFIRMED' GROUP BY 1) u GROUP BY 1) t") || return 1
  ps='null'
  if [[ "$base" == S7 ]]; then
    seats=$(sed -n 's/.*SEATS=\([0-9]*\).*/\1/p' <<< "$k6env")
    [[ -n "$seats" ]] || return 1
    ps=$(db_scalar "SELECT coalesce(json_object_agg(s.id, json_build_object('status', s.status, 'holders', coalesce((SELECT json_agg(h.user_id ORDER BY h.id) FROM seat_hold h WHERE h.seat_id = s.id), '[]'))), '{}') FROM product_seat s WHERE s.schedule_id = 1 AND s.id <= $seats") || return 1
  fi
  jq -n --argjson pu "$pu" --argjson ps "$ps" '{per_user: $pu} + (if $ps == null then {} else {per_seat: $ps} end)' > "$dir/end-state.json"
}

app_cpus() {  # 앱 1대 = 단계 CPU 전부, 앱 2대 = 절반씩(L4 → 2 + 2)
  if (( APPS == 2 )); then echo $(( $1 / 2 )); else echo "$1"; fi
}

# 회차 산출물 검증 — 사유를 누적한다 (하나라도 있으면 실패)
# ADR-007: 복합 조건은 전부 if로 묶는다 — `A || B && C`는 `(A || B) && C`로 묶여 ADR-006에서 S3 회차에 늘 사유가 붙었다(fbaaf815).
# 한 줄 `조건 || reasons+=(…)`는 '조건이 거짓이면 사유' 하나뿐인 경우에만 남겼다. 판정식마다 양방향 오프라인 확인 기록은 README '재측정 판정식'.
rep_status() {  # 인자: 폴더 base k6_exit k6_started k6_ended
  local dir="$1" base="$2" k6_exit="$3" k6_started="$4" k6_ended="$5" reasons=()
  if (( k6_exit != 0 )); then
    # S4(계단)의 99 = 한계 도달로 스스로 멈춤(정상). 단 성공한 단계가 하나도 없으면 측정 실패다.
    # 고정 도착률(S4c*)은 에러율 중단을 껐으므로(k6_env_of STOP_ON_ERROR=0) 0이 아닌 종료는 전부 실패다
    if [[ "$base" == S4 && "$k6_exit" -eq 99 ]]; then
      if ! python3 "$SUMMARIZE" --s4-check "$dir/k6-summary.json"; then reasons+=("s4-no-successful-stage"); fi
    else
      reasons+=("k6-exit-$k6_exit")
    fi
  fi
  # 고정 도착률: 정상 종료 = 계획 구간(12 × 30초) 전부에 요청이 있고 k6가 계획 시간을 다 돌았다. 목표 미달은 결과이지 실패가 아니다(요약기 구간표)
  if [[ "$base" == S4c* ]]; then
    if ! python3 "$SUMMARIZE" --fixed-check "$dir/k6-summary.json"; then reasons+=("fixed-incomplete"); fi
  fi
  local f
  # 10-21 무음 실패 감사: k6-window.json(k6 구간 — gc.json k6 정지·효과 지표가 읽는다)도 필수 산출물
  for f in k6-summary.json consistency.json app-config.json seed.json after-k6.json io-before.json io-after.json pg-settings.json k6-window.json; do
    if ! { [[ -s "$dir/$f" ]] && jq empty "$dir/$f" 2>/dev/null; }; then reasons+=("invalid-$f"); fi
  done
  if [[ "$base" == S2 || "$base" == S7 ]]; then
    if ! { [[ -s "$dir/end-state.json" ]] && jq -e '.per_user | type == "object"' "$dir/end-state.json" > /dev/null 2>&1; }; then reasons+=("invalid-end-state.json"); fi
    if [[ "$base" == S7 ]] && ! jq -e '.per_seat | length > 0' "$dir/end-state.json" > /dev/null 2>&1; then reasons+=("invalid-end-state.json-seats"); fi
  fi
  # ADR-006 I/O 스냅숏: 시간 열(fsync 평균 등)은 타이밍 설정이 켜져 있어야 의미가 있다(compose db.command) — 꺼져 있으면 0이 '빠름'으로 읽힌다
  local io
  for io in io-before.json io-after.json; do
    jq -e '.pg.wal.wal_sync != null and (.disk | length) > 0' "$dir/$io" > /dev/null 2>&1 || reasons+=("invalid-$io-content")
    jq -e '.pg.settings.track_wal_io_timing == "on" and .pg.settings.track_io_timing == "on"' "$dir/$io" > /dev/null 2>&1 || reasons+=("io-timing-off-$io")
  done
  # ADR-007 DB 설정 축: 실제 값(SHOW — pg-settings.json)이 preset과 같은가. 키마다 사유 하나(어느 설정이 어긋났는지 status에서 보이게)
  if jq empty "$dir/pg-settings.json" 2>/dev/null && [[ -s "$dir/pg-settings.json" ]]; then
    local kv key want got
    for kv in $(preset_env "$PG_PRESET"); do
      case "${kv%%=*}" in
        PG_CHECKPOINT_TIMEOUT) key=checkpoint_timeout ;; PG_MAX_WAL_SIZE) key=max_wal_size ;; PG_SHARED_BUFFERS) key=shared_buffers ;; *) continue ;;
      esac
      want="${kv#*=}"; got="$(jq -r --arg k "$key" '.[$k] // empty' "$dir/pg-settings.json")"
      if [[ "$got" != "$want" ]]; then reasons+=("pg-preset-mismatch-$key"); fi
    done
    if [[ "$(jq -r '.log_checkpoints // empty' "$dir/pg-settings.json")" != on ]]; then reasons+=("log-checkpoints-off"); fi
  fi
  # 수집기는 오류를 버리고 돈다 — 파일이 비면 조용히 실패한 것이므로 회차 실패로 드러낸다(락 표본 SQL 오류가 빈 파일만 남긴 스모크 실측)
  for f in server-before.txt server-after.txt client-before.txt client-after.txt timeline-server.jsonl timeline-locks.jsonl timeline-waits.jsonl timeline-hikari.jsonl; do
    [[ -s "$dir/$f" ]] || reasons+=("missing-$f")
  done
  for f in k6-requests.csv.gz app.log.gz db.log.gz k6-stdout.log.gz; do   # 압축 파일은 무결성까지
    if ! { [[ -s "$dir/$f" ]] && gzip -t "$dir/$f" 2>/dev/null; }; then reasons+=("corrupt-or-missing-$f"); fi
  done
  # 조건이 실제로 적용됐는지 — 풀 크기와 DB의 실제 인덱스 목록을 계획과 대조한다
  if jq empty "$dir/app-config.json" 2>/dev/null; then
    [[ "$(jq -r '."hikaricp.connections.max"[0] // empty | floor' "$dir/app-config.json")" == "$POOL" ]] || reasons+=("pool-mismatch")
    [[ "$(jq -r '[.db_indexes[].name] | sort | join(",")' "$dir/app-config.json")" == "$EXPECTED_INDEXES" ]] || reasons+=("index-mismatch")
    # 적용된 경합 제어 전략 — 설정 바인딩 결과(enum 이름)를 계획과 대조
    [[ "$(jq -r '.configprops[] | select(.prefix=="seat.hold") | .properties.strategy' "$dir/app-config.json")" == "$(echo "$STRATEGY" | tr 'a-z-' 'A-Z_')" ]] || reasons+=("strategy-mismatch")
    # 임계 구역 지연(ADR-004) — Duration 바인딩 결과(ISO-8601, 예: PT0.02S / PT0S)와 대조
    [[ "$(jq -r '.configprops[] | select(.prefix=="seat.hold") | .properties.criticalSectionDelay' "$dir/app-config.json")" == "$(python3 -c "print('PT%gS' % ($DELAY_MS/1000))")" ]] || reasons+=("delay-mismatch")
    # ADR-005 매수 제어 방식 — 계획 값(LIMIT_STRATEGY)과 바인딩 결과(enum 이름) 대조. 주입 값(APPLIED_LIMIT)이 아니라 계획과 비교해야 주입 실수가 잡힌다
    [[ "$(jq -r '.configprops[] | select(.prefix=="seat.hold") | .properties.limitStrategy' "$dir/app-config.json")" == "$(echo "$LIMIT_STRATEGY" | tr 'a-z-' 'A-Z_')" ]] || reasons+=("limit-strategy-mismatch")
  fi
  # ADR-008 기본값 출처 → ADR-009 조건 인지(명세 §5 · 사용자 지시 '매수 방식 키는 B·B+C에만'): limit-key.json = [앱, (F면) reconciler] — lib.sh limit_key_check.
  #   앱: 환경의 매수 방식 키 등장 수 = LIMIT_KEYS_EXPECTED(B·B+C = 1 — SPRING_APPLICATION_JSON의 "limit-strategy" 한 번, 그 밖 0) · 명령줄 0.
  #   reconciler: 0(compose.reconciler.yml이 JSON을 다시 쓴다). 다르면 limit-key-present(기대보다 많음) · limit-key-missing(B인데 없음 — 주입 실패),
  #   파일이 없거나 컨테이너 수가 다르면 limit-key-check-failed
  if jq -e --argjson n "$((1 + HAS_F))" 'type == "array" and length == $n and all(.[]; (.env | type) == "number" and (.cmd | type) == "number")' "$dir/limit-key.json" > /dev/null 2>&1; then
    if jq -e --argjson x "$LIMIT_KEYS_EXPECTED" '.[0].env > $x or .[0].cmd > 0 or any(.[1:][]; .env + .cmd > 0)' "$dir/limit-key.json" > /dev/null; then reasons+=("limit-key-present"); fi
    if jq -e --argjson x "$LIMIT_KEYS_EXPECTED" '.[0].env < $x' "$dir/limit-key.json" > /dev/null; then reasons+=("limit-key-missing"); fi
  else
    reasons+=("limit-key-check-failed")
  fi
  # ADR-009 기본값 조건(default): 앱 컨테이너 환경·명령줄에 후보·정리·마감 키(counter-drift · sales-close — relaxed 이름 포함)가 하나도 없어야 한다 — 있으면 drift-key-present,
  #   개수 필드가 없으면 limit-key-check-failed(같은 증거 파일). 다른 조건은 개수만 남긴다(전환 전 조건은 세 키를 명시하므로 0이 아니다)
  if (( IS_DEFAULT )); then
    if ! jq -e '(.[0].drift | type) == "number" and (.[0].drift_cmd | type) == "number"' "$dir/limit-key.json" > /dev/null 2>&1; then reasons+=("limit-key-check-failed")
    elif jq -e '.[0].drift + .[0].drift_cmd > 0' "$dir/limit-key.json" > /dev/null; then reasons+=("drift-key-present"); fi
  fi
  # ADR-008 설정 파일 정적 검사(리뷰 루프 1 C4): limit-set-by-profile · limit-default-not-precheck · spring-config-override, 확인 실패면 limit-config-check-failed
  if jq -e '.reasons | type == "array"' "$dir/limit-config.json" > /dev/null 2>&1; then
    local lcr; for lcr in $(jq -r '.reasons[]' "$dir/limit-config.json"); do reasons+=("$lcr"); done
  else
    reasons+=("limit-config-check-failed")
  fi
  # 런타임 쪽(리뷰 루프 1 C8): 앱 컨테이너 환경에 SPRING_CONFIG_*가 있으면 외부 설정 위치 — 정적 검사와 같은 사유(한 번만)
  if jq -e 'type == "array" and any(.[]; (.spring_config // 0) > 0)' "$dir/limit-key.json" > /dev/null 2>&1 && [[ " ${reasons[*]} " != *" spring-config-override "* ]]; then
    reasons+=("spring-config-override")
  fi
  # ADR-008 회차 합격(명세 §5 — summarize.py --accept-check: 매수 초과 · v_counter_mismatch · 억울한 좌석(S7-m1) · 명세 순서 위반(S7-m1-late) · 빌림 1.0(모든 셀 — 리뷰 루프 1 C1)).
  # 사유는 한 줄에 하나(exit 1). 낸 사유는 종료 코드와 무관하게 모두 붙인다(리뷰 루프 1 C6 — 판정 실패가 이미 찾은 사유를 지우지 않게).
  # exit 0인데 사유가 있거나, exit 1인데 사유가 없거나, 그 밖의 종료(파이썬 자체 실패 등)면 accept-check-failed — 조용히 ok가 되지 않게
  local acc acc_rc r
  # ADR-009: 조건·정리 방식을 함께 넘긴다(조건별 기대값 — 효과 셀·마감 임박 셀·빌림 식). 판정 목록은 summarize.py accept_check 머리말
  acc=$(python3 "$SUMMARIZE" --accept-check "$dir" "$(basename "$(dirname "$dir")")" "$COND" "$CLEANUP" 2>>"$dir/errors.log"); acc_rc=$?
  for r in $acc; do reasons+=("$r"); done
  if { [[ "$acc_rc" == 0 && -n "$acc" ]] || [[ "$acc_rc" == 1 && -z "$acc" ]] || [[ "$acc_rc" != 0 && "$acc_rc" != 1 ]]; } \
     && [[ " ${reasons[*]} " != *" accept-check-failed "* ]]; then
    reasons+=("accept-check-failed")
  fi
  # 앱마다 계획한 전략·풀로 떠서 부하 중 선점 요청을 실제로 받았는가(예열 제외 증가분) — 앱 2대에서 한 대만 살아도 nginx가 다른 쪽으로 몰아
  # '1대 측정'이 되는 것을 잡는다. 지표 조회 자체가 실패한 것(null)은 불일치와 따로 표시한다
  if jq empty "$dir/after-k6.json" 2>/dev/null; then
    [[ "$(jq '[.apps[] | select(.strategy == null or .limit_strategy == null or .acquire == null or .timeouts == null or .limit == null)] | length' "$dir/after-k6.json")" == 0 ]] \
      || reasons+=("after-k6-fetch-failed")
    local want; want="$(echo "$STRATEGY" | tr 'a-z-' 'A-Z_')"
    local want_l; want_l="$(echo "$LIMIT_STRATEGY" | tr 'a-z-' 'A-Z_')"
    [[ "$(jq --arg s "$want" --arg l "$want_l" --argjson p "$POOL" '[.apps[] | select(.strategy==$s and .limit_strategy==$l and (.pool|floor)==$p and (.hold_requests // 0) > 0)] | length' "$dir/after-k6.json")" == "$APPS" ]] \
      || reasons+=("apps-mismatch")
    # ADR-006 요청당 커넥션 빌림(명세 §9.4 ③): 분모(선점 요청 수)가 직전·직후 모두 실제로 읽혔나 — null(조회 실패)이면 빌림을 낼 수 없다
    local hf
    for hf in before-k6.json after-k6.raw.json; do
      jq -e --argjson n "$APPS" 'length == $n and all(.[]; .hold_requests | type == "number")' "$dir/$hf" > /dev/null 2>&1 || reasons+=("hold-requests-missing-$hf")
    done
    # 빌림 = Σ획득 증가분 ÷ Σ선점 증가분이 0.9 ~ 4 밖이면 지표가 어긋난 것(분모가 누적값·다른 URI 등). ADR-005 실측: 1방식 1.00~1.02,
    # 다중 커넥션 방식(counter·quota-*) 2.1~3.0, serializable-retry 최대 2.72 — 상한 4는 그 위 여유. 계산 불가(획득 null·선점 0)는 위 사유들이 드러낸다
    # S3는 확정 요청도 커넥션을 빌리는데 분모는 선점 요청만이라 '선점당 빌림'으로 읽을 수 없다 — 범위 검사는 S2·S4·S4c·S7 계열에만(ADR-006 재점검 N1)
    # (`A || B && C`는 `(A || B) && C`라 S3에서 늘 사유가 붙었다 — if로 묶는다, fbaaf815)
    # ADR-009: 획득에서 대조·정리 빌림을 뺀 값으로(설계 §4d)
    if [[ "$base" != S3* ]] && jq -e '[.apps[] | .acquire.COUNT - (.reconcile_borrows // 0) - (.cleanup_borrows // 0) - (.incremental_borrows // 0) - (.safety_borrows // 0)] as $a | [.apps[].hold_requests] as $h
           | if ($a | all(type == "number")) and ($h | all(type == "number")) and ($h | add) > 0
             then (($a | add) / ($h | add)) as $r | ($r < 0.9 or $r > 4) else false end' "$dir/after-k6.json" > /dev/null 2>&1; then
      reasons+=("invalid-borrow-ratio")
    fi
  fi
  # 측정 중 k6 PC~서버 경로 단절(ADR-002 A2): 서버 지표 수집 공백이 30초를 넘으면 그 회차는 재측정 대상
  if ! python3 "$(dirname "$0")/gapcheck.py" "$dir/timeline-server.jsonl" "$k6_started" "$k6_ended" 30 2>>"$dir/errors.log"; then reasons+=("path-gap"); fi
  # ADR-007 시계 차(리뷰 A3 — lib.sh clock_offset, k6 앞·뒤): 대기 표본·DB 로그(서버 시계)를 PC 시계로 옮기는 값. 둘 중 하나라도 없으면 clock-offset-missing,
  # |차| > 0.5초면 clock-offset-large(벽 ±3초 창에 비해 크다 — 시계 동기화부터 본다). 평균을 아래 표본 판정에 쓴다(없으면 0)
  local clk_off=0
  if jq -e '(.before.offset_s | type == "number") and (.after.offset_s | type == "number")' "$dir/clock-offset.json" > /dev/null 2>&1; then
    clk_off=$(jq -r '(.before.offset_s + .after.offset_s) / 2' "$dir/clock-offset.json")
    if jq -e '[.before.offset_s, .after.offset_s] | any(. > 0.5 or . < -0.5)' "$dir/clock-offset.json" > /dev/null 2>&1; then reasons+=("clock-offset-large"); fi
  else
    reasons+=("clock-offset-missing")
  fi
  # ADR-007 대기 이벤트 표본: k6 구간 안에서 표본 사이가 30초를 넘으면 벽 시각의 대기 분포를 낼 수 없다(같은 판정기 — '{"t"'로 시작하는 줄의 t, 서버 시각 − 시계 차) → waits-gap.
  # k6 구간 안 표본이 하나도 없으면 waits-none-in-k6(리뷰 A10 — S2·S7은 k6가 몇 초라 30초 공백 판정으로는 못 잡는다; gapcheck exit 3).
  # 파일이 비어 있으면 위 missing-timeline-waits.jsonl이 이미 드러낸다
  if [[ -s "$dir/timeline-waits.jsonl" ]]; then
    python3 "$(dirname "$0")/gapcheck.py" "$dir/timeline-waits.jsonl" "$k6_started" "$k6_ended" 30 "$clk_off" 1 2>>"$dir/errors.log"
    case $? in
      0) ;;
      1) reasons+=("waits-gap") ;;
      3) reasons+=("waits-none-in-k6") ;;
      *) reasons+=("waits-check-failed") ;;
    esac
  fi
  # 서버 표본 사본을 지우지 못했으면(recover_samples — 리뷰 R2) 사유
  if grep -qs 'sampler-copy-not-removed' "$dir/sampler-recovery.txt"; then reasons+=("sampler-copy-not-removed"); fi
  # ADR-007 S7-m1-late(명세 §9.4 ⑥): 기회(R 201 뒤에 보낸 U)가 0이면 그 회차로는 명세 순서를 판정할 수 없다 — 실패
  if [[ "$(basename "$(dirname "$dir")")" == *-late ]] && ! python3 "$SUMMARIZE" --s7late-check "$dir" 2>>"$dir/errors.log"; then reasons+=("s7late-no-opportunity"); fi
  # ADR-007 리뷰 A5: S2·S7 hold_code 행(요청 1건당 1행)이 선점 요청 수와 같고 깨진 행이 없나 — 빠지면 가짜 거절·명세 순서 위반이 조용히 줄어든다
  if [[ "$base" == S2 || "$base" == S7 ]]; then
    if ! python3 "$SUMMARIZE" --codes-check "$dir" 2>>"$dir/errors.log"; then reasons+=("hold-codes-mismatch"); fi
  fi
  # ADR-007 벽 판정(wall.py --rep-check — 코드 0 처리(리뷰 A12)가 들어간 같은 벽 정의):
  #   벽이 있는데 벽 ±3초 대기 표본이 6개 미만(0.5초 표본이라 기대 약 12) → waits-thin-at-wall(리뷰 A4 — 벽 시각 대기 분포를 믿을 수 없다)
  #   nockpt 조건에서 k6 구간과 겹친 체크포인트(shutdown 제외)가 있으면 checkpoint-during-k6(리뷰 A14 — 명세 §8 가정 1이 그 회차에서 깨졌다), DB 로그를 못 읽었으면 checkpoint-check-failed
  if [[ "$base" == S4 || "$base" == S4c* ]]; then
    local wj
    if wj=$(python3 "$(dirname "$0")/wall.py" --rep-check "$dir" "$k6_started" "$k6_ended" 2>>"$dir/errors.log") && jq -e . <<< "$wj" > /dev/null 2>&1; then
      echo "$wj" > "$dir/wall-check.json"
      # 기준 초 = 벽, 고정 셀 '과도 미종료'면 처음 나쁜 초(wall.py thin_check_s — 리뷰 A4 잔여)
      if jq -e '.thin_check_samples != null and .thin_check_samples < 6' <<< "$wj" > /dev/null; then reasons+=("waits-thin-at-wall"); fi
      if [[ "$PG_PRESET" == nockpt ]]; then
        if jq -e '.checkpoints_during_k6 == null' <<< "$wj" > /dev/null; then
          reasons+=("checkpoint-check-failed")
        elif jq -e '.checkpoints_during_k6 | length > 0' <<< "$wj" > /dev/null; then
          reasons+=("checkpoint-during-k6")
        fi
      fi
    else
      reasons+=("wall-check-failed")
    fi
  fi
  if [[ "$base" == S3* || "$base" == S4 || "$base" == S4c* || "$base" == S6* ]]; then
    [[ -s "$dir/timeline-db.jsonl" ]] || reasons+=("missing-timeline-db.jsonl")
    [[ -s "$dir/k6-dashboard.html" ]] || reasons+=("missing-k6-dashboard.html")
  fi
  # ADR-007 후속(디스크 원인 — 2026-10-10 명세 §5 · 리뷰 rv8 A1·A4): 표본 장치(host-devices.json — 디스크 모델이 EXPECTED_DISK_MODEL이어야 한다).
  # 파일이 없거나 깨지면 invalid-host-devices.json, 모델·장치가 다르면 host-device-unexpected
  local hdisk=""
  if jq -e '(.disk | type == "string") and (.sampled | type == "array")' "$dir/host-devices.json" > /dev/null 2>&1; then
    hdisk="$(jq -r .disk "$dir/host-devices.json")"
    if ! jq -e --arg m "$EXPECTED_DISK_MODEL" '.model == $m and (.disk as $d | .sampled | any(. == $d))' "$dir/host-devices.json" > /dev/null 2>&1; then reasons+=("host-device-unexpected"); fi
  else
    reasons+=("invalid-host-devices.json")
  fi
  # 호스트 표본(서버 시계 − 시계 차): 그 디스크 누적값이 든 표본이 k6 구간 초의 90% 미만이거나 최대 공백 > 2.5초면 host-sampler-thin(파일 없음·빈 파일 포함 — wdrop.py --coverage)
  if ! python3 "$(dirname "$0")/wdrop.py" --coverage "$dir/timeline-host.jsonl" "$k6_started" "$k6_ended" "$clk_off" 0.9 "${hdisk:-nvme1n1}" 2>>"$dir/errors.log"; then reasons+=("host-sampler-thin"); fi
  # iostat 교차 확인 사본이 없거나 그 디스크 행이 없으면 host-iostat-missing
  if ! grep -qs "^${hdisk:-nvme1n1} " "$dir/iostat-host.txt"; then reasons+=("host-iostat-missing"); fi
  # 호스트 표본 서버 사본을 지우지 못했으면(lib.sh host_recover) 사유
  if grep -qs 'host-copy-not-removed' "$dir/host-recovery.txt"; then reasons+=("host-copy-not-removed"); fi
  # tmpfs preset: 데이터 디렉터리가 실제로 tmpfs인가(컨테이너 안 stat -f — pg-storage.json). 파일이 없거나 깨져도 확인 불가 = 미적용
  if [[ "$PG_PRESET" == tmpfs ]]; then
    if ! jq -e '.fstype == "tmpfs"' "$dir/pg-storage.json" > /dev/null 2>&1; then reasons+=("tmpfs-not-applied"); fi
    # 회차 끝 tmpfs 남은 공간이 TMPFS_MIN_AVAIL(기본 512MiB) 미만이면 — 크기를 넘기 직전이었다(명세 §8 가정 2, 리뷰 rv8 A3)
    if jq -e '.fstype == "tmpfs"' "$dir/pg-storage.json" > /dev/null 2>&1 \
       && ! jq -e --argjson min "${TMPFS_MIN_AVAIL:-536870912}" '(.df_bytes.avail | type == "number") and .df_bytes.avail >= $min' "$dir/pg-storage.json" > /dev/null 2>&1; then
      reasons+=("tmpfs-low-space")
    fi
    # ADR-011 E3(리뷰 rv34): 적용된 크기가 요청(preset_env PG_TMPFS_SIZE·DB_MEM — GiB 정수)과 바이트까지 같은가(tmpfs size·memory.max는 정확히 맞는다 — 6g 회차 실측)
    local want_t want_m; want_t=$(preset_env "$PG_PRESET" | tr ' ' '\n' | sed -n 's/^PG_TMPFS_SIZE=\([0-9]*\)g$/\1/p'); want_m=$(preset_env "$PG_PRESET" | tr ' ' '\n' | sed -n 's/^DB_MEM=\([0-9]*\)g$/\1/p')
    if ! jq -e --argjson t "$(( want_t * 1073741824 ))" --arg m "$(( want_m * 1073741824 ))" '.df_bytes.size == $t and .cgroup.max == $m' "$dir/pg-storage.json" > /dev/null 2>&1; then
      reasons+=("tmpfs-size-mismatch")
    fi
  else
    # 반대 방향(리뷰 rv8 A2): 디스크 preset인데 데이터 디렉터리가 tmpfs면(이전 tmpfs 볼륨을 이어 씀 등) disk-preset-on-tmpfs, 저장 위치를 못 읽었으면 storage-check-failed
    if ! jq -e '.fstype | type == "string" and length > 0' "$dir/pg-storage.json" > /dev/null 2>&1; then
      reasons+=("storage-check-failed")
    elif jq -e '.fstype == "tmpfs"' "$dir/pg-storage.json" > /dev/null 2>&1; then
      reasons+=("disk-preset-on-tmpfs")
    fi
  fi
  # DB 메모리 초과 종료(모든 preset — 리뷰 rv8 A3): memory.events oom_kill > 0 또는 OOMKilled
  if jq -e '.oom_killed == true or ((.cgroup.oom_kill // 0) > 0)' "$dir/pg-storage.json" > /dev/null 2>&1; then reasons+=("db-oom-kill"); fi
  # DB 로그의 치명 신호(모든 preset): PANIC · 백엔드가 신호로 죽음 · 공간 없음
  if zgrep -qsE 'PANIC|terminated by signal|No space left on device' "$dir/db.log.gz" 2>/dev/null; then reasons+=("db-log-fatal"); fi
  # ---- ADR-009 조건 적용·카운터 어긋남 수집(명세 §5·§10·§11 · 설계 §9) ----
  # 적용 설정(configprops seat.hold): 조건이 켠 키만 켜졌나 — counterDrift.{expiryRepair, externalReconciler, reconcile.enabled, reconcile.interval, cleanup.mode} · salesClose.enabled
  #   + ADR-012 명세 §10 K9: reconcile.{incremental.method, incremental.enabled, safetyNet.enabled, detectShape}(업무 앱 — inc_env와 같은 기대: A+I = 방식·켬·켬·모양 ·
  #   A+X = 방식·끔·끔·ONE_SHOT · 그 밖(default 포함 — 교체 전 SHA 앱 기본값) = NONE·끔·끔·ONE_SHOT)
  if jq empty "$dir/app-config.json" 2>/dev/null; then
    local cfg_want cfg_got
    cfg_want="$(jq -nc --argjson a "$HAS_A" --argjson f "$(( HAS_F || HAS_X ))" --argjson c "$HAS_C" --arg cl "$CLEANUP" --arg noc "$([[ "$COND" == control-noclose ]] && echo 1)" \
      --arg im "$( ((HAS_INC)) && inc_method_key "$INC_METHOD" | tr 'a-z' 'A-Z' || echo NONE)" --argjson ion "$HAS_I" \
      --arg ds "$( ((HAS_I)) && tr 'a-z-' 'A-Z_' <<< "${RECONCILE_SHAPE:-one-shot}" || echo ONE_SHOT)" \
      '{expiryRepair: ($a == 1), externalReconciler: ($f == 1), reconcile: ($c == 1), interval: (if $c == 1 then "PT1M" else null end), cleanup: ($cl | ascii_upcase), salesClose: ($noc != "1"),
        incMethod: $im, incEnabled: ($ion == 1), safetyNet: ($ion == 1), detectShape: $ds}')"
    cfg_got="$(jq -c '[.configprops[] | select(.prefix=="seat.hold") | .properties][0] as $p
      | {expiryRepair: $p.counterDrift.expiryRepair, externalReconciler: $p.counterDrift.externalReconciler, reconcile: $p.counterDrift.reconcile.enabled,
         interval: (if $p.counterDrift.reconcile.enabled then $p.counterDrift.reconcile.interval else null end), cleanup: $p.counterDrift.cleanup.mode, salesClose: $p.salesClose.enabled,
         incMethod: $p.counterDrift.reconcile.incremental.method, incEnabled: $p.counterDrift.reconcile.incremental.enabled,
         safetyNet: $p.counterDrift.reconcile.safetyNet.enabled, detectShape: $p.counterDrift.reconcile.detectShape}' "$dir/app-config.json" 2>/dev/null)"
    [[ -n "$cfg_got" && "$cfg_got" == "$cfg_want" ]] || { reasons+=("cond-config-mismatch"); echo "$(date -Is) cond-config want=$cfg_want got=${cfg_got:-없음}" >> "$dir/errors.log"; }
  fi
  # F: 대조 컨테이너 설정(대조 켬 · 주기 60s · A 끔 · 정리 끔 — ADR-009 기본값 전환 뒤에는 application-reconciler.yml이 A·정리를 끈다) — 못 읽었으면 같은 사유
  #   ADR-012 명세 §10 K8·K9: 증분·안전망 끔(프로필 yml) · 방식 NONE(compose.reconciler.yml이 명시)
  # ADR-012 R8: 탐지 모양 = --reconcile-shape(지정 안 하면 앱 기본 ONE_SHOT — configprops는 enum 이름) — 조용히 one-shot으로 돌지 않게
  local want_shape; want_shape="$(tr 'a-z-' 'A-Z_' <<< "${RECONCILE_SHAPE:-one-shot}")"
  if (( HAS_F )) && ! jq -e --arg ds "$want_shape" '[.configprops[] | select(.prefix=="seat.hold") | .properties][0] | .counterDrift.reconcile.enabled == true and .counterDrift.reconcile.interval == "PT1M" and .counterDrift.expiryRepair == false and .counterDrift.cleanup.mode == "NONE" and .counterDrift.reconcile.detectShape == $ds and .counterDrift.reconcile.incremental.method == "NONE" and .counterDrift.reconcile.incremental.enabled == false and .counterDrift.reconcile.safetyNet.enabled == false' "$dir/reconciler-config.json" > /dev/null 2>&1; then
    reasons+=("reconciler-config-mismatch")
  fi
  # 트리거(V8) ⇔ B: pg_trigger 두 이름(seat_hold_quota·reservation_quota)이 B·B+C면 둘 다, 그 밖은 없어야 한다
  if jq -e '.triggers | type == "array"' "$dir/db-triggers.json" > /dev/null 2>&1; then
    local ntrg; ntrg=$(jq '.triggers | length' "$dir/db-triggers.json")
    [[ "$ntrg" == "$([[ "$LIMIT_STRATEGY" == counter-trigger ]] && echo 2 || echo 0)" ]] || reasons+=("trigger-mismatch")
  else
    reasons+=("trigger-check-failed")
  fi
  # 지표 스냅숏(drift-before·after-k6·end — 앱, F면 reconciler도)과 V7 기록 테이블 덤프
  for f in drift-before.json drift-after-k6.json drift-end.json; do
    jq -e --argjson f "$HAS_F" '(.app | type == "object" and (.error | not)) and ($f == 0 or (.reconciler | type == "object" and (.error | not)))' "$dir/$f" > /dev/null 2>&1 || reasons+=("drift-snap-failed")
  done
  jq -e '(.drift_log | type == "array") and (.reconcile_runs | type == "array") and (.cleanup_runs | type == "array")' "$dir/drift-db.json" > /dev/null 2>&1 || reasons+=("drift-db-failed")
  # H-4·N-5: counter_drift_log를 페이지로 다 모았는데도 총수(먼저 셈)보다 적으면 수집 실패 — 효과·오탐 판정을 믿을 수 없다(재측정 대상, 결과 사유 아님)
  if jq -e '(.drift_log_total // 0) > (.drift_log | length)' "$dir/drift-db.json" > /dev/null 2>&1; then reasons+=("drift-db-incomplete"); fi
  # C·F가 실제로 돌았나(reconcile-missing): 시드 뒤 첫 바퀴를 봤고(reconcile-first.json), 긴 셀(S2 밖 — k6가 60초보다 길다)은 k6 구간에도 그 출처의 바퀴가 있어야 한다.
  # 바퀴 기록의 errors·skipped_chunks > 0이면 reconcile-errors(대조 실패를 숨기지 않는다 — 결과 사유)
  if (( HAS_C || HAS_F )); then
    local rsrc; rsrc=$( ((HAS_F)) && echo reconcile-proc || echo reconcile-app)
    jq -e '.runs > 0' "$dir/reconcile-first.json" > /dev/null 2>&1 || reasons+=("reconcile-missing")
    # H-13: k6 구간 안 바퀴 수 ≥ 기대(구간 ÷ 주기 − 1, 최소 1 — summarize.py runs_in_window)
    if [[ "$base" != S2 ]] && ! python3 "$SUMMARIZE" --runs-in-window "$dir" "$rsrc" "$k6_started" "$k6_ended" "${RECONCILE_INTERVAL%s}" 2>>"$dir/errors.log"; then reasons+=("reconcile-missing"); fi
    # H-13: 앱(C)·F 지표 reconcile.errors 증가(k6 직전 → 회차 끝) > 0도 reconcile-errors(바퀴 기록이 실패해 DB에 없는 오류까지)
    if jq -e --slurpfile b "$dir/drift-before.json" --arg k "$( ((HAS_F)) && echo reconciler || echo app)" \
         '((.[$k].reconcile.errors // 0) - ($b[0][$k].reconcile.errors // 0)) > 0' "$dir/drift-end.json" > /dev/null 2>&1; then reasons+=("reconcile-errors"); fi
    if jq -e '[.reconcile_runs[] | (.errors + .skipped_chunks)] | add // 0 | . > 0' "$dir/drift-db.json" > /dev/null 2>&1; then reasons+=("reconcile-errors"); fi
  fi
  # ---- ADR-012 2단계(새 조건 A+I·A+X만 — §4.2.9 ③④) ----
  if (( HAS_INC )); then
    # 적용 설정: 방식·틱·주기·안전망·탐지 모양·track-inject·앱 경로 주입(A+X는 업무 앱 + 외부 앱 configprops — external-config.json). 판정 실패도 같은 사유
    local icr; icr=$(python3 "$SUMMARIZE" --inc-config-check "$dir" "$COND" "$INC_METHOD" "$INC_INTERVAL" "${RECONCILE_SHAPE:-one-shot}" "$INJECT_APP" "$INJECT_APP_EVERY" "${INJECT_APP_PATHS:-default}" 2>>"$dir/errors.log") \
      || reasons+=("incremental-config-mismatch")
    # 스키마: V9 열·인덱스(trg·stmt) · 트리거 user_hold_quota_touch(trg만 — 다른 방식이면 없어야) · V10 세 테이블(늘) — db-schema.json(lib.sh db_schema)
    if jq -e '(.updated_at_column | type == "boolean") and (.tables | type == "array")' "$dir/db-schema.json" > /dev/null 2>&1; then
      jq -e --argjson v9 "$([[ "$INC_METHOD" == mem ]] && echo false || echo true)" --argjson tg "$([[ "$INC_METHOD" == trg ]] && echo true || echo false)" \
        '.updated_at_column == $v9 and .updated_at_index == $v9 and .touch_trigger == $tg and ((.tables | sort) == ["counter_app_inject", "counter_inc_gap", "counter_safety_run"])' \
        "$dir/db-schema.json" > /dev/null || reasons+=("schema-mismatch")
    else
      reasons+=("schema-mismatch")
    fi
    # R5(하네스 ⑧ — 10-21 무음 실패 감사): V9 방식(trg·stmt)은 quota-hot.json의 k6 직전·회차 끝 두 측정(갱신·HOT 누적 · updated_at 인덱스 크기)이 모두 있어야 한다 —
    #   quota_hot 조회가 실패하면 그 칸이 null로 조용히 남았다 → quota-hot-missing(수집 실패 — campaign ACCEPT_RE 밖이라 다시 잰다)
    if [[ "$INC_METHOD" == trg || "$INC_METHOD" == stmt ]] \
       && ! jq -e 'all(.k6, .end; type == "object" and (.n_tup_upd | type == "number") and (.n_tup_hot_upd | type == "number") and (.idx_bytes | type == "number"))' "$dir/quota-hot.json" > /dev/null 2>&1; then
      reasons+=("quota-hot-missing")
    fi
    # 대조가 실제로 돌았나: 시드 뒤 첫 증분 틱(reconcile-first.json) · k6 구간의 증분 틱(주기)·안전망 실행(1분) 결손(긴 셀만 — S2 밖) · V10 기록 덤프
    jq -e '.runs > 0' "$dir/reconcile-first.json" > /dev/null 2>&1 || reasons+=("reconcile-missing")
    if [[ "$base" != S2 ]]; then
      python3 "$SUMMARIZE" --runs-in-window "$dir" "$INC_SOURCE" "$k6_started" "$k6_ended" "${INC_INTERVAL%s}" 2>>"$dir/errors.log" || reasons+=("reconcile-missing")
      python3 "$SUMMARIZE" --runs-in-window "$dir" "$SAFETY_SOURCE" "$k6_started" "$k6_ended" 60 2>>"$dir/errors.log" || reasons+=("reconcile-missing")
    fi
    jq -e '(.safety_runs | type == "array") and (.inc_gaps | type == "array") and (.app_inject | type == "array")' "$dir/drift-db.json" > /dev/null 2>&1 || reasons+=("drift-db-failed")
    # R7 기록 짝(summarize 머리말 'R7 기록 짝'): k6 구간 안전망 counter_reconcile_run마다 같은 run_id의 counter_safety_run + 필수 열 — 없으면 R7 판정 불가(결과 사유)
    python3 "$SUMMARIZE" --safety-pair-check "$dir" "$SAFETY_SOURCE" "$k6_started" "$k6_ended" 2>>"$dir/errors.log" || reasons+=("safety-record-missing")
    if jq -e '[.reconcile_runs[] | (.errors + .skipped_chunks)] | add // 0 | . > 0' "$dir/drift-db.json" > /dev/null 2>&1; then reasons+=("reconcile-errors"); fi
    # 증분·안전망 지표 errors 증가(k6 직전 → 회차 끝 — actsnap incremental·safety, 업무 앱 — A+X 외부 앱은 아래 drift-ext-*) > 0이면 reconcile-errors(바퀴 기록 errors는 위 공통 검사)
    if (( HAS_I )) && jq -e --slurpfile b "$dir/drift-before.json" \
         '(((.app.incremental.errors // 0) - ($b[0].app.incremental.errors // 0)) + ((.app.safety.errors // 0) - ($b[0].app.safety.errors // 0))) > 0' "$dir/drift-end.json" > /dev/null 2>&1; then
      reasons+=("reconcile-errors")
    fi
    # ADR-012 M3: 외부 앱 지표 errors 증가(drift-ext-before → end) > 0이면 reconcile-errors · 지표를 못 읽었으면 drift-snap-failed
    if (( HAS_X )); then
      for f in drift-ext-before.json drift-ext-end.json; do jq -e '.external | type == "object" and (.error | not)' "$dir/$f" > /dev/null 2>&1 || reasons+=("drift-snap-failed"); done
      if jq -e --slurpfile b "$dir/drift-ext-before.json" \
           '(((.external.incremental.errors // 0) - ($b[0].external.incremental.errors // 0)) + ((.external.safety.errors // 0) - ($b[0].external.safety.errors // 0))) > 0' "$dir/drift-ext-end.json" > /dev/null 2>&1; then
        reasons+=("reconcile-errors")
      fi
      # 회차 끝 내리기 실패(external-down.txt — 파일이 없거나 rc ≠ 0) → external-down-failed
      grep -qxs 'rep-end rc=0' "$dir/external-down.txt" || reasons+=("external-down-failed")
      # ⑥ 표본: 외부 앱 docker stats(k6 구간 공백 ≤ 30초 — 업무 서버 path-gap과 같은 판정기) · Hikari · .158 시계 차(앞·뒤) — 하나라도 없으면 external-sample-missing(하네스 실패)
      #   docker stats 표본의 t는 .158 시계 — clock-offset-ext.json(.158 − PC, 앞·뒤 평균)을 빼서 PC 시계의 k6 구간과 견준다(rv46 · 없으면 0 — 그때는 아래 시계 차 검사가 사유를 단다).
      #   stats 행이 없는 시각은 lib.sh external_stats가 '{"t"' 줄을 내지 않으므로 공백으로 잡힌다
      local ext_off=0
      if jq -e '(.before.offset_s | type == "number") and (.after.offset_s | type == "number")' "$dir/clock-offset-ext.json" > /dev/null 2>&1; then
        ext_off=$(jq -r '(.before.offset_s + .after.offset_s) / 2' "$dir/clock-offset-ext.json")
      fi
      python3 "$(dirname "$0")/gapcheck.py" "$dir/timeline-ext-server.jsonl" "$k6_started" "$k6_ended" 30 "$ext_off" 1 2>>"$dir/errors.log" || reasons+=("external-sample-missing")
      grep -qs "\"Name\":\"$EXT_CONTAINER\"" "$dir/timeline-ext-server.jsonl" || reasons+=("external-sample-missing")
      [[ -s "$dir/timeline-ext-hikari.jsonl" ]] || reasons+=("external-sample-missing")
      jq -e '(.before.offset_s | type == "number") and (.after.offset_s | type == "number")' "$dir/clock-offset-ext.json" > /dev/null 2>&1 || reasons+=("external-sample-missing")
      # 외부 앱 로그·GC 로그(파이프 0/0 · 0바이트 아님 · 두 줄)
      if ! [[ -s "$dir/external-status.txt" ]] || grep -qvE '^(log|gc) pipe=0/0 bytes=[1-9][0-9]*$' "$dir/external-status.txt" || [[ "$(wc -l < "$dir/external-status.txt")" != 2 ]]; then
        reasons+=("external-log-missing")
      fi
    fi
    # 효과 셀 --inject-app on: S3e 주입마다 같은 트랜잭션의 counter_app_inject path='db' 행 수(tracked) = 기록한 주입 수
    if [[ "$base" == S3e ]] && (( INJECT_APP )) && jq -e '.rounds | type == "array" and length > 0' "$dir/injected.json" > /dev/null 2>&1; then
      jq -e 'all(.rounds[]; .tracked == (.counts.D + .counts.L1 + .counts.L2 + .counts.H))' "$dir/injected.json" > /dev/null 2>&1 || reasons+=("inject-mismatch")
    fi
  fi
  # 정리(S3c)도 실패를 숨기지 않는다: 앱 지표 cleanup.errors 증가(회차 전체 — before → end) > 0이면 cleanup-errors
  if [[ "$CLEANUP" != none ]] && jq -e '(.app.cleanup.errors // 0) > 0' "$dir/drift-end.json" > /dev/null 2>&1; then reasons+=("cleanup-errors"); fi
  # 기록 자체 실패(무음 금지 — 설계 §1a): drift.record_failed > 0
  if jq -e '[(.app.drift_record_failed // 0), (.reconciler.drift_record_failed // 0)] | add > 0' "$dir/drift-end.json" > /dev/null 2>&1; then reasons+=("drift-record-failed"); fi
  # 효과 셀: 주입 결과(injected.json — 계획 수보다 적으면 inject-short, 주입 SQL 실패면 inject-failed)와 효과 지표(effect.json)가 있어야 한다
  if [[ "$base" == S3e ]]; then
    # H-1: 라운드마다 그 라운드 계획(plan.rounds — 조건별)과 대조. H-7: 종류별 영향 행 수(applied) = 기록한 수(counts)
    if ! jq -e '(.rounds | type == "array" and length > 0) and (.plan.rounds | type == "array") and (.rounds | length) == (.plan.rounds | length) and all(.rounds[]; .ok == true)' "$dir/injected.json" > /dev/null 2>&1; then
      reasons+=("inject-failed")
    else
      jq -e '[.plan.rounds, .rounds] | transpose | all(.[]; .[0].at_s == .[1].at_s and .[0].D == .[1].counts.D and .[0].L1 == .[1].counts.L1 and .[0].L2 == .[1].counts.L2 and .[0].H == .[1].counts.H)' "$dir/injected.json" > /dev/null 2>&1 \
        || reasons+=("inject-short")
      jq -e 'all(.rounds[]; .applied.D == .counts.D and .applied.L == (.counts.L1 + .counts.L2) and .applied.H == .counts.H)' "$dir/injected.json" > /dev/null 2>&1 \
        || reasons+=("inject-mismatch")
    fi
    jq -e '.kinds | type == "object"' "$dir/effect.json" > /dev/null 2>&1 || reasons+=("effect-check-failed")
    jq -e '(.injected | type == "array") and (.mismatch_total | type == "number")' "$dir/end-pairs.json" > /dev/null 2>&1 || reasons+=("effect-check-failed")
  fi
  # 마감 임박 셀: starts_at을 실제로 넣었나(close.json) — 판정(close-leak 등)은 accept-check
  if [[ "$base" == S3c ]] && ! jq -e '.updated == 1' "$dir/close.json" > /dev/null 2>&1; then reasons+=("close-not-applied"); fi
  [[ "$HAS_F" == 1 ]] && { [[ -s "$dir/reconciler.log.gz" ]] && gzip -t "$dir/reconciler.log.gz" 2>/dev/null || reasons+=("corrupt-or-missing-reconciler.log.gz"); }
  # H-12: 컨테이너 로그 — 파이프가 실패했거나(0/0 아님) 내용이 0바이트면 container-log-missing
  if ! [[ -s "$dir/logs-status.txt" ]] || grep -qvE '^[a-z]+ pipe=0/0 bytes=[1-9][0-9]*$' "$dir/logs-status.txt" \
     || [[ "$(wc -l < "$dir/logs-status.txt")" != "$((2 + HAS_F))" ]]; then
    reasons+=("container-log-missing")
  fi
  # 명세 §13: 시드 끝 → k6 시작이 목표 ± 허용(PRE_K6_TARGET_S ± PRE_K6_TOL_S) 밖이면 pre-k6-mismatch — 조건마다 k6 시작 상태가 달라지는 교란을 막는다(파일 없음도 같은 사유)
  # 리뷰 H5: 기준은 k6 **첫 요청** 시각(seed_to_first_request_s — pre-k6 finish). 없으면 같은 사유
  if ! jq -e '(.seed_to_first_request_s | type == "number") and ((.seed_to_first_request_s - .target_s) | fabs) <= .tol_s' "$dir/pre-k6.json" > /dev/null 2>&1; then reasons+=("pre-k6-mismatch"); fi
  # 명세 §13: GC 로그(앱, F면 reconciler) — 파이프 실패·0바이트·수집기 줄 없음이면 gc-log-missing
  if ! [[ -s "$dir/gc-status.txt" ]] || grep -qvE '^[a-z]+ pipe=0/0 bytes=[1-9][0-9]*$' "$dir/gc-status.txt" \
     || [[ "$(wc -l < "$dir/gc-status.txt")" != "$((1 + HAS_F))" ]] \
     || ! jq -e --argjson f "$HAS_F" '(.app.collector | type == "string") and ($f == 0 or (.reconciler.collector | type == "string"))' "$dir/gc.json" > /dev/null 2>&1; then
    reasons+=("gc-log-missing")
  fi
  # 명세 §13 · ADR-010 리뷰: 로그가 있어도 k6 구간 GC 정지가 0이면(파싱 실패·잘림) 'Full GC 0'으로 보이지 않게 같은 사유
  #   고정 도착률 셀(S4c — k6 약 7분, 정지 수천 회)만 — S2처럼 k6 구간이 몇 초인 셀은 실제로 GC가 0일 수 있다(재점검)
  if [[ "$base" == S4c* ]] && ! jq -e '(.app.k6.stops // 0) > 0' "$dir/gc.json" > /dev/null 2>&1; then reasons+=("gc-log-missing"); fi
  # ADR-010 리뷰: 실제 메모리 한도(bytes)와 JAVA_TOOL_OPTIONS 앞머리가 계획(--app-mem·--app-gc)과 같아야 한다 — 다르면 app-jvm-mismatch(하네스 실패, 다시 잼)
  local want_b; case "$APP_MEM" in *g) want_b=$(( ${APP_MEM%g} * 1073741824 )) ;; *m) want_b=$(( ${APP_MEM%m} * 1048576 )) ;; esac
  local want_jto; case "$APP_GC" in g1) want_jto="-XX:+UseG1GC " ;; zgc) want_jto="-XX:+UseZGC -XX:+ZGenerational " ;; *) want_jto="" ;; esac
  # ADR-011 --app-pretouch: compose 순서 APP_JVM_OPTS · APP_JVM_OPTS2 · APP_JVM_OPTS3 → 수집기 옵션 뒤, MaxRAMPercentage 앞
  (( APP_PRETOUCH )) && want_jto+="-XX:+AlwaysPreTouch "
  (( APP_XMS_FULL )) && want_jto+="-XX:InitialRAMPercentage=75 "
  want_jto+="-XX:MaxRAMPercentage="
  if ! jq -e --argjson b "$want_b" --arg p "$want_jto" '.mem_bytes == $b and (.java_tool_options | startswith($p))' "$dir/app-jvm.json" > /dev/null 2>&1; then reasons+=("app-jvm-mismatch"); fi
  # ADR-010: --app-gc g1이면 앱 수집기가 실제로 G1이어야 한다(옵션이 안 먹으면 조건이 섞인다). auto는 기록만(gc.json collector)
  if [[ "$APP_GC" == g1 ]] && ! jq -e '.app.collector == "G1"' "$dir/gc.json" > /dev/null 2>&1; then reasons+=("gc-collector-mismatch"); fi
  # zgc: 수집기 = Z이고 세대형(로그에 Minor Collection — 세대형만 있다)이어야 한다
  if [[ "$APP_GC" == zgc ]] && ! jq -e '(.app.collector // "" | startswith("The Z Garbage Collector")) and (.app.zgc_generational == true)' "$dir/gc.json" > /dev/null 2>&1; then reasons+=("gc-collector-mismatch"); fi
  # ADR-011 --gctrace: gc 줄 기록(k6-gctrace.log.gz)이 없거나 깨졌거나 gc 줄이 0이면 gctrace-missing(하네스 실패 — 다시 잼). k6 종료 코드 판정은 위 그대로
  if (( GCTRACE )); then
    # 리뷰 L1: 줄 나누기 종료 코드 0 · 마지막 gc 줄 시각 ≥ 주 k6 끝 − 30초(도중에 끊긴 기록을 '있음'으로 보지 않게 — 실측 GC 간격 약 1.2초)
    local gsplit gend glast
    gsplit="$(sed -n 's/.* split=\([0-9]*\).*/\1/p' "$dir/gctrace-status.txt" 2>/dev/null)"; gend="$(sed -n 's/.*k6_end_epoch=\([0-9.]*\).*/\1/p' "$dir/gctrace-status.txt" 2>/dev/null)"
    glast="$(zcat "$dir/k6-gctrace.log.gz" 2>/dev/null | grep -E '^[0-9]+\.[0-9]+ gc [0-9]+ @' | tail -1 | cut -d' ' -f1)"
    if ! { [[ -s "$dir/k6-gctrace.log.gz" ]] && gzip -t "$dir/k6-gctrace.log.gz" 2>/dev/null && [[ -n "$glast" && "$gsplit" == 0 && -n "$gend" ]] \
           && awk -v l="$glast" -v e="$gend" 'BEGIN { exit !(l >= e - 30) }'; }; then reasons+=("gctrace-missing"); fi
  fi
  # ADR-011(리뷰 M3): 주 k6·측정기 바이너리가 계획(plan.json sha256)과 그대로인가 — 다르면 k6-binary-changed(하네스 실패 — 다시 잼)
  if [[ "$(bin_sha256 "$K6_BIN")" != "$K6_SHA256" ]] || { (( PROBES )) && [[ "$(bin_sha256 "$PROBE_K6_BIN")" != "$PROBE_K6_SHA256" ]]; }; then reasons+=("k6-binary-changed"); fi
  # ADR-011 --probes: 두 측정기가 주 k6 창(램프 끝 ~ k6 끝)을 덮었나 — 종료 코드(0·105) · CSV 무결성 · 덮은 범위·건수·공백(tail.py --probe-check → probe-check.json).
  #   실패는 probe-missing(결과 사유가 아니라 하네스 실패 — 다시 잰다). 판정을 못 하면(파이썬 실패) 같은 사유
  if (( PROBES )); then
    if ! python3 -I "$(dirname "$0")/tail.py" --probe-check "$dir" > /dev/null 2>>"$dir/errors.log"; then reasons+=("probe-missing"); fi
  fi
  # 같은 사유가 여러 경로로 붙었으면 한 번만(순서 유지)
  local uniq=() x; for x in "${reasons[@]}"; do [[ " ${uniq[*]} " == *" $x "* ]] || uniq+=("$x"); done; reasons=("${uniq[@]}")
  if (( ${#reasons[@]} )); then (IFS=,; echo "${reasons[*]}"); else echo ok; fi
}

reset_with_retry() {  # 인자: 회차폴더 schedules seats — 과부하 직후 적체가 풀릴 때까지 재시도
  local dir="$1"; shift
  local i
  for i in 1 2 3 4 5 6; do
    if reset_db "$@"; then echo "$(date -Is) reset ok attempt=$i" >> "$dir/recovery.log"; return 0; fi
    echo "$(date -Is) reset failed attempt=$i" >> "$dir/recovery.log"
    sleep 10
  done
  return 1
}

finish_rep() {  # 인자: 폴더 level cell rep started "k6_started|k6_ended" status k6_exit k6env — 성공·실패 모두 여기서 기록한다
  local dir="$1" level="$2" cell="$3" rep="$4" started="$5" k6_window="$6" status="$7" k6_exit="$8" k6env="$9" base="$(cell_base "$3")"
  # ADR-012 2단계(10-21): pre-k6.json이 1초 증분 셀에서 193KB — --argjson(인자 하나 최대 128KB, MAX_ARG_STRLEN)을 넘어 jq가 실행되지 못했고 meta.json이 빈 채
  #   status만 ok로 남았다(무음 — 9회차). 회차 파일 내용은 인자로 넘기지 않는다: --slurpfile($x[0]). 없거나 깨진 파일 = null(이전과 같은 값 — json_file_or_null)
  local tmps=() f_pg f_clk f_pre f_gc
  f_pg=$(json_file_or_null "$dir/pg-settings.json") f_clk=$(json_file_or_null "$dir/clock-offset.json") \
    f_pre=$(json_file_or_null "$dir/pre-k6.json") f_gc=$(json_file_or_null "$dir/gc.json")
  [[ "$f_pg" == "$dir/"* ]] || tmps+=("$f_pg"); [[ "$f_clk" == "$dir/"* ]] || tmps+=("$f_clk")
  [[ "$f_pre" == "$dir/"* ]] || tmps+=("$f_pre"); [[ "$f_gc" == "$dir/"* ]] || tmps+=("$f_gc")
  # 쓰기는 임시 파일 → 검증 → 이름 바꾸기. 실패하면 status = meta-write-failed(하네스 실패 — campaign ACCEPT_RE 밖이라 다시 잰다) + errors.log 한 줄.
  # status는 meta 뒤에 쓴다 — 그 사이에 끊기면 status가 없어 --resume이 끝나지 못한 회차로 다시 잰다(meta 없는 '끝난 회차'가 생기지 않게)
  if ! jq -n --arg sha "$SHA" --arg level "$level" --arg cell "$cell" --arg rep "$rep" \
        --arg started "$started" --arg ended "$(date -Is)" \
        --arg k6_started "${k6_window%%|*}" --arg k6_ended "${k6_window##*|}" --arg k6env "$k6env" \
        --arg appenv "$(config_env "$(config_of "$base")") APP_CPUS=$(app_cpus "$level") DB_CPUS=$level" \
        --arg ttl "$(ttl_s_of "$base")" --arg strategy "$STRATEGY" --arg pool "$POOL" --arg apps "$APPS" --arg delay "$DELAY_MS" \
        --arg limit "$LIMIT_STRATEGY" --arg applied "$APPLIED_LIMIT" --arg harness "$HARNESS" \
        --arg applied_seat "$(jq -r '.configprops[]? | select(.prefix=="seat.hold") | .properties.strategy' "$dir/app-config.json" 2>/dev/null)" \
        --arg applied_lim "$(jq -r '.configprops[]? | select(.prefix=="seat.hold") | .properties.limitStrategy' "$dir/app-config.json" 2>/dev/null)" \
        --arg status "$status" --argjson k6_exit "$k6_exit" --arg k6 "$("$K6_BIN" version | head -1)" --arg k6bin "$K6_BIN" \
        --argjson adr011 "$(jq -nc --argjson g "$GCTRACE" --argjson p "$PROBES" --argjson t "$APP_PRETOUCH" --argjson x "$APP_XMS_FULL" --argjson st "$S4C_STEPS" --argjson ss "$SEED_SCHEDULES" --argjson tg "$PG_TMPFS_GB" \
                             '{gctrace: ($g == 1), probes: ($p == 1), app_pretouch: ($t == 1), app_xms_full: ($x == 1), s4c_steps: $st, seed_schedules: $ss, pg_tmpfs_gb: $tg}')" \
        --arg cond "$COND" --arg cleanup "$CLEANUP" --arg rint "$RECONCILE_INTERVAL" --arg rshape "$RECONCILE_SHAPE" \
        --argjson inc "$(inc_json meta)" \
        --arg pg_preset "$PG_PRESET" --arg pg_env "$(preset_env "$PG_PRESET")" \
        --slurpfile pg_applied "$f_pg" --slurpfile clock "$f_clk" --slurpfile prek6 "$f_pre" --slurpfile gcs "$f_gc" \
        '{sha:$sha, level:($level|tonumber), cell:$cell, rep:($rep|tonumber), started:$started, ended:$ended,
          k6_started:$k6_started, k6_ended:$k6_ended, ttl_s:($ttl|tonumber),
          strategy:$strategy, pool:($pool|tonumber), apps:($apps|tonumber), delay_ms:($delay|tonumber), bg:0,
          limit_strategy:$limit, limit_source:(if $cond == "B" or $cond == "B+C" then "injected" else "default" end),
          injected_limit_strategy:(if $cond == "B" or $cond == "B+C" then $limit else null end), harness:$harness,
          cond:$cond, cleanup:$cleanup, reconcile_interval:$rint,
          applied:{strategy:$applied_seat, limit_strategy:$applied_lim},
          k6_env:$k6env, app_env:$appenv, k6_exit:$k6_exit, status:$status, k6_version:$k6, k6_bin:$k6bin, adr011:$adr011,
          io_settle_s:(($ENV.IO_SETTLE_S // "11")|tonumber),
          pg_preset:$pg_preset, pg_env:$pg_env, pg_applied:$pg_applied[0], clock_offset:$clock[0], pre_k6:$prek6[0],
          gc:(try ($gcs[0] | if . == null then null else
                {app: (.app | if . then {collector, stops, stop_ms_max, nested, log_bytes, k6} else null end),
                 reconciler: (.reconciler | if . then {collector, stops, stop_ms_max, nested, log_bytes, k6} else null end)} end) catch null)}
          + (if $rshape != "" then {reconcile_shape:$rshape} else {} end)
          + (if $inc != null then {incremental:$inc} else {} end)' > "$dir/meta.json.tmp" \
     || ! jq -e 'type == "object" and .sha != null' "$dir/meta.json.tmp" > /dev/null 2>&1 \
     || ! mv "$dir/meta.json.tmp" "$dir/meta.json"; then
    echo "$(date -Is) meta-write-failed — meta.json을 쓰지 못함(회차 상태 $status → meta-write-failed)" >> "$dir/errors.log"
    rm -f "$dir/meta.json.tmp"; status="meta-write-failed"
  fi
  (( ${#tmps[@]} )) && rm -f "${tmps[@]}"
  echo "$status" > "$dir/status"
  echo "$(date -Is) L$level $cell rep$rep $status" >> "$MATRIX_LOG"
}
# 파일이 올바른 JSON이면 그 경로, 아니면 null 한 줄 임시 파일 경로(호출자가 지운다) — jq --slurpfile로 넘길 것(인자 크기 한도 없음)
json_file_or_null() {  # 인자: 파일
  if [[ -s "$1" ]] && jq empty "$1" > /dev/null 2>&1; then echo "$1"; return; fi
  local t; t=$(mktemp) && echo null > "$t" && echo "$t"
}

# ADR-012 2단계 기록(plan.json condition.incremental · meta.json incremental) — 새 조건만(이전 조건은 null → 키 없음 · 계획·meta 그대로)
inc_json() {  # 인자: plan|meta
  (( HAS_INC )) || { echo null; return; }
  if [[ "$1" == plan ]]; then
    jq -nc --arg m "$INC_METHOD" --arg i "$INC_INTERVAL" --argjson j "$INJECT_APP" --arg p "$INJECT_APP_PATHS" '{method:$m, interval:$i, inject_app:($j == 1)} + (if $p != "" then {inject_app_paths:$p} else {} end)'
  else
    jq -nc --arg m "$INC_METHOD" --arg i "$INC_INTERVAL" --argjson j "$INJECT_APP" --arg s "$INC_SOURCE" --arg ss "$SAFETY_SOURCE" --arg x "$( ((HAS_X)) && echo ext || echo app)" \
      --argjson every "$INJECT_APP_EVERY" --arg es "${EXT_SERVER:-}" --arg ep "${EXT_PROJECT:-}" --arg eu "${EXTERNAL_URL:-}" --arg ed "${EXT_DB_URL:-}" \
      --arg p "$INJECT_APP_PATHS" '{method:$m, interval:$i, inject_app:($j == 1), source:$s, safety_source:$ss, where:$x} + (if $j == 1 then {inject_app_every:$every} else {} end) + (if $p != "" then {inject_app_paths:$p} else {} end)
       + (if $x == "ext" then {external:{server:$es, project:$ep, url:$eu, db_url:$ed}} else {} end)'
  fi
}

# ---- 매트릭스 ----------------------------------------------------------------------------------------
# 계획을 먼저 남긴다 — 요약은 이 계획 기준으로 미측정·누락을 표시한다 (이어서 실행이면 기존 계획 유지)
if (( ! RESUME )); then   # 10-21 감사: 계획은 임시 파일 → 검증 → 이름 바꾸기. 못 쓰면 회차를 돌리지 않는다(exit 2 — 캠페인도 멈춘다)
jq -n --arg sha "$SHA" --arg levels "$LEVELS" --argjson reps "$REPS" --arg cells "$(expand_cells | tr '\n' ' ')" \
  --arg strategy "$STRATEGY" --argjson pool "$POOL" --argjson apps "$APPS" --argjson delay "$DELAY_MS" --arg limit "$LIMIT_STRATEGY" --arg harness "$HARNESS" \
  --arg pg "$PG_PRESET" --arg app_mem "$APP_MEM" --arg app_gc "$APP_GC" --arg cond "$COND" --arg cleanup "$CLEANUP" --arg rint "$RECONCILE_INTERVAL" --arg inj_rounds "$INJECT_ROUNDS" --arg hm "$INJECT_HELD_MIN_S" --arg em "$INJECT_EXP_MARGIN_S" --arg cl "$CLOSE_LEAD_S" \
  --arg ph "$PRE_K6_HOLD_S" --arg pt "$PRE_K6_TARGET_S" --arg pl "$PRE_K6_TOL_S" --arg pd "$PRE_K6_LEAD_S" \
  --arg k6bin "$K6_BIN" --arg k6ver "$K6_VERSION" --argjson gctrace "$GCTRACE" --argjson probes "$PROBES" --arg pbin "$PROBE_K6_BIN" --arg pver "$PROBE_K6_VERSION" \
  --arg k6sha "$K6_SHA256" --arg psha "$PROBE_K6_SHA256" \
  --arg pimage "$PROBE_IMAGE" --argjson prate "$PROBE_RATE" --argjson pretouch "$APP_PRETOUCH" --argjson xmsfull "$APP_XMS_FULL" --argjson s4c_steps "$S4C_STEPS" --argjson seed_sched "$SEED_SCHEDULES" --argjson pg_tmpfs_gb "$PG_TMPFS_GB" --arg rshape "$RECONCILE_SHAPE" \
  --argjson inc "$(inc_json plan)" \
  '{sha:$sha, harness:$harness, levels:($levels|split(" ")|map(select(.!="")|tonumber)), reps:$reps,
    cells:($cells|split(" ")|map(select(.!=""))),
    condition:({cond:$cond, cleanup:$cleanup, reconcile_interval:$rint, limit_strategy:$limit,
               limit_source:(if $cond == "B" or $cond == "B+C" then "injected" else "default" end), pg_preset:$pg, strategy:$strategy, pool:$pool, apps:$apps, delay_ms:$delay, app_mem:$app_mem, app_gc:$app_gc,
               k6_bin:$k6bin, k6_version:$k6ver, k6_sha256:$k6sha, probe_k6_sha256:(if $probes == 1 then $psha else null end), gctrace:($gctrace == 1), probes:($probes == 1), probe_k6_bin:$pbin,
               probe_k6_version:(if $probes == 1 then $pver else null end), probe_image:$pimage, probe_rate:$prate,
               app_pretouch:($pretouch == 1), app_xms_full:($xmsfull == 1), s4c_steps:$s4c_steps, seed_schedules:$seed_sched, pg_tmpfs_gb:$pg_tmpfs_gb}
               + (if $rshape != "" then {reconcile_shape:$rshape} else {} end)
               + (if $inc != null then {incremental:$inc} else {} end)),
    knobs:{INJECT_ROUNDS:$inj_rounds, INJECT_HELD_MIN_S:$hm, INJECT_EXP_MARGIN_S:$em, CLOSE_LEAD_S:$cl, PRE_K6_HOLD_S:$ph, PRE_K6_TARGET_S:$pt, PRE_K6_TOL_S:$pl, PRE_K6_LEAD_S:$pd},
    start_state:{mode:"fixed-after-seed", hold_s:($ph|tonumber), target_s:($pt|tonumber), tol_s:($pl|tonumber), lead_s:($pd|tonumber),
                 judged_on:"시드 끝 → k6 첫 요청(리뷰 H5 — S4c setup_data.startedAt, 그 밖 CSV 1초 분해능)",
                 overrun:"대기점·맞춤에서 목표 + 허용을 넘으면 k6 전에 멈춘다(pre-k6-overrun — 하네스 실패, 재측정 대상)",
                 limit:"시드 끝 → k6와 첫 대조 바퀴 → k6를 함께 맞출 수는 없다 — 시드 끝 기준으로 맞추고 바퀴 위상은 기록만(pre-k6.json reconcile_phase)",
                 analyze:"시드 직후 ANALYZE는 원래 있다(/internal/reset → LoadtestDataService.analyze — product·product_schedule·product_seat·seat_hold·reservation, 모든 조건 공통) — 더하지 않음",
                 vacuum:"시드 직후 VACUUM은 넣지 않음 — 옛 캠페인과 같은 시작 상태(autovacuum 포함)를 두고 시점만 맞춘다",
                 why:"교란 원인은 시작 상태가 아니라 대조 조건만 k6 전에 약 30~43초 더 기다린 시점 차(명세 §13) — 모든 조건을 같은 시점(시드 끝 + target_s)에 시작한다",
                 jvm:"JAVA_TOOL_OPTIONS -Xlog:async -Xlog:gc*,safepoint:file=/dev/shm/gc.log:…:filecount=0 — 수집기는 JVM 기본 선택 그대로(gc.json collector에 기록)"},
    created:(now|todate)}' > "$OUT_ROOT/plan.json.tmp" && jq -e '.condition | type == "object"' "$OUT_ROOT/plan.json.tmp" > /dev/null 2>&1 && mv "$OUT_ROOT/plan.json.tmp" "$OUT_ROOT/plan.json" \
  || { rm -f "$OUT_ROOT/plan.json.tmp"; echo "$(date -Is) ABORT plan-write-failed" >> "$MATRIX_LOG"; log "plan.json write failed — abort"; exit 2; }
fi

# ADR-008 설정 파일 정적 검사(리뷰 루프 1 C4 — lib.sh limit_config_check): 측정 대상 앱 SHA의 프로필 설정이 매수 방식을 덮지 않는가 ·
# application.yml이 counter-upsert-precheck인가 · 띄울 compose에 SPRING_CONFIG_*가 없는가. 회차 전에 한 번 — 결과는 회차마다 limit-config.json으로 남고
# rep_status가 사유로 낸다(확인 자체가 실패하면 limit-config-check-failed). compose는 실제로 띄울 사본: 작업트리 스모크면 작업트리, 아니면 SHA에서 꺼낸 것
LC_TREE="$(mktemp -d)"
lc_files=()
for f in k6/ADR-009/compose.yml $(sed -n 's/-f \([^ ]*\)/\1/gp' <<< "${COMPOSE_EXTRA:-}"); do
  mkdir -p "$LC_TREE/$(dirname "$f")"
  if (( WORKTREE )); then cp "$REPO_ROOT/$f" "$LC_TREE/$f" 2>/dev/null; else git -C "$REPO_ROOT" show "$SHA:$f" > "$LC_TREE/$f" 2>/dev/null; fi
  lc_files+=("$LC_TREE/$f")
done
LIMIT_CONFIG="$(limit_config_check "$SHA" "${lc_files[@]}" 2>/dev/null)" || LIMIT_CONFIG=""
rm -rf "$LC_TREE"
if [[ -n "$LIMIT_CONFIG" ]]; then echo "$LIMIT_CONFIG" > "$OUT_ROOT/limit-config.json"; fi
echo "$(date -Is) limit-config $(if [[ -n "$LIMIT_CONFIG" ]]; then jq -c .reasons <<< "$LIMIT_CONFIG"; else echo check-failed; fi)" >> "$MATRIX_LOG"
# 캠페인(리뷰 루프 2 D4): 정적 검사가 실패했거나 사유가 하나라도 있으면 배포 전에 멈춘다(exit 6) — 회차마다 같은 사유로 실패할 것이 정해져 있다.
# campaign.sh는 run.sh가 0이 아니면 캠페인 전체를 멈춘다(재측정하지 않는다). 작업트리 스모크(--worktree — 음성 대조 fb181a30)와 캠페인 밖 단독 실행은
# 그대로 회차를 돌려 회차 status에 사유를 남긴다(음성 대조는 그 사유를 보려고 돈다)
if [[ "${CAMPAIGN_FROZEN:-0}" == 1 ]] && { [[ -z "$LIMIT_CONFIG" ]] || jq -e '.reasons | length > 0' <<< "$LIMIT_CONFIG" > /dev/null; }; then
  echo "$(date -Is) ABORT limit-config $(if [[ -n "$LIMIT_CONFIG" ]]; then jq -c .reasons <<< "$LIMIT_CONFIG"; else echo check-failed; fi) — 캠페인에서는 배포 전에 멈춘다" >> "$MATRIX_LOG"
  log "limit-config check failed or has reasons — abort before deploy (campaign)"
  exit 6
fi

if (( WORKTREE )); then
  deploy_worktree "$SHA" "$DEPLOY_ID" || { echo "$(date -Is) ABORT deploy-failed" >> "$MATRIX_LOG"; log "deploy failed"; exit 3; }
else
  deploy_sha "$SHA" || { echo "$(date -Is) ABORT deploy-failed" >> "$MATRIX_LOG"; log "deploy failed"; exit 3; }
fi
# ADR-012 M3: 외부 앱은 늘 커밋 그대로(--worktree여도 앱은 SHA) — .158 labs/seat-reservation-lab-reconciler/<SHA>
if (( HAS_X )); then
  external_app_deploy "$SHA" || { echo "$(date -Is) ABORT external-deploy-failed" >> "$MATRIX_LOG"; log "external deploy failed"; exit 3; }
fi
log "matrix $MATRIX_ID: cond=$COND cleanup=$CLEANUP levels=[$LEVELS] reps=$REPS cells=[$CELLS] pg_preset=$PG_PRESET"
for level in $LEVELS; do
  for cell in $(expand_cells); do
    for rep in $(seq 1 "$REPS"); do
      [[ -n "$ONLY_REP" && "$rep" != "$ONLY_REP" ]] && continue
      rdir="$OUT_ROOT/L$level/$cell/rep$rep"
      if (( RESUME )); then
        [[ -f "$rdir/status" ]] && continue                     # 끝난 회차(성공·실패 모두 기록됨)는 건너뛴다
        if [[ -d "$rdir" ]]; then                                  # 끝나지 못한 회차는 근거로 옆에 보존하고 다시 잰다
          mv "$rdir" "$rdir.incomplete-$(date +%Y%m%d%H%M%S)"
          echo "$(date -Is) L$level $cell rep$rep incomplete-dir-preserved" >> "$MATRIX_LOG"
        fi
      fi
      run_rep "$level" "$cell" "$rep"
      FIRST_REP_DONE="${FIRST_REP_DONE:-}"
      # 첫 회차부터 못 뜨면 빌드·배포 문제 — 즉시 중단. 캠페인에서는 매 호출이 회차 하나라 '첫 회차'가 매번이다 —
      # 일시 실패 한 번에 캠페인 전체가 멈추지 않게 캠페인이 연속 실패를 따로 센다(campaign.sh)
      if [[ -z "$FIRST_REP_DONE" && "${CAMPAIGN_FROZEN:-0}" != 1 ]] && (( UNHEALTHY_STREAK > 0 )); then
        echo "$(date -Is) ABORT first-rep-unhealthy" >> "$MATRIX_LOG"; log "first rep unhealthy — abort"
        { compose_down "$DEPLOY_ID" || echo "$(date -Is) compose-down-failed-at-abort" >> "$MATRIX_LOG"; }; exit 4
      fi
      FIRST_REP_DONE=1
      if (( UNHEALTHY_STREAK >= 3 )); then
        echo "$(date -Is) ABORT unhealthy-3-in-a-row" >> "$MATRIX_LOG"; log "3 consecutive unhealthy — abort"
        { compose_down "$DEPLOY_ID" || echo "$(date -Is) compose-down-failed-at-abort" >> "$MATRIX_LOG"; }; exit 4
      fi
    done
  done
done
compose_down "$DEPLOY_ID" || { echo "$(date -Is) compose-down-failed-at-end" >> "$MATRIX_LOG"; log "compose down failed at end"; }
log "matrix done: $OUT_ROOT"
