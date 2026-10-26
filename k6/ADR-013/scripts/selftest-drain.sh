#!/usr/bin/env bash
# 비움 확인 흉내 테스트(서버 없음 · CR13-16) — lib13.sh의 app_inflight · lb_started_after · drain_verdict를 가짜 curl · remote · docker로 돌린다.
#   확인: 조회가 실패하면(HTTP 실패 · 404 · 파싱 실패 · 빈 값 · 기록 파일 없음) 어느 경로에서도 0이 아니라 '모름'(빈 출력 + 종료 1)이고,
#   drain_verdict는 모름을 성공으로 보지 않는다(started_after_unknown · inflight_unknown).
#
#   selftest-drain.sh [lib13.sh 경로]      exit 0 = 모두 통과(경로를 주면 그 판으로 — 수정 전 판의 실패 확인용)
set -u
LIB13="${1:-$(cd "$(dirname "$0")" && pwd)/lib13.sh}"
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
: > "$STUB/lib.sh"   # ADR-009 lib.sh 대신(이 테스트가 쓰는 함수는 lib13.sh 안에 있다)
export LIB9_DIR="$STUB" SERVER_HOST=127.0.0.1
# shellcheck source=lib13.sh
source "$LIB13"
set +e
FAILS=0
check() { if [[ "$2" == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1 — 기대 [$3] · 실제 [$2]"; FAILS=$((FAILS + 1)); fi; }

# ---- 가짜 curl: FAKE 모드에 따라 actuator 응답을 흉내 낸다. -f가 있으면 HTTP ≥ 400에서 출력 없이 22(curl과 같다) ------------------------
FAKE=""
curl() {
  local f=0 url="" a
  for a in "$@"; do case "$a" in -f|-fsS|-fs|-sf) f=1 ;; http*) url="$a" ;; esac; done
  local code=200 body=""
  local tags_post='{"name":"http.server.requests.active","measurements":[{"statistic":"ACTIVE_TASKS","value":0},{"statistic":"DURATION","value":0}],"availableTags":[{"tag":"method","values":["GET","POST"]},{"tag":"uri","values":["UNKNOWN"]}]}'
  case "$url" in
    */actuator/metrics) body='{"names":["http.server.requests","http.server.requests.active","jvm.memory.used"]}'; [[ "$FAKE" == base_fail ]] && code=500 ;;
    *http.server.requests.active\?tag=method:POST)
      case "$FAKE" in
        ok_zero) body='{"measurements":[{"statistic":"ACTIVE_TASKS","value":0}]}' ;;
        ok_three) body='{"measurements":[{"statistic":"ACTIVE_TASKS","value":3},{"statistic":"DURATION","value":1.2}]}' ;;
        tag_404) code=404; body='{"timestamp":"x","status":404,"error":"Not Found"}' ;;
        tag_garbage) body='<html>bad gateway</html>' ;;
        tag_empty) body='{"measurements":[]}' ;;
        *) code=404 ;;
      esac ;;
    *http.server.requests.active\?tag=uri:*)   # 수정 전 판의 URI별 조회 — 진행 중 3건이 있어도 uri 태그는 UNKNOWN이라 404(태그 값 없음)
      code=404; body='{"timestamp":"x","status":404,"error":"Not Found"}' ;;
    *http.server.requests.active)
      case "$FAKE" in
        base_fail) code=500 ;;
        no_tags) body='{"name":"http.server.requests.active","measurements":[{"statistic":"ACTIVE_TASKS","value":0}]}' ;;
        no_post) body='{"name":"http.server.requests.active","measurements":[{"statistic":"ACTIVE_TASKS","value":1}],"availableTags":[{"tag":"method","values":["GET"]}]}' ;;
        *) body="$tags_post" ;;
      esac ;;
    *) code=404 ;;
  esac
  if (( code >= 400 )); then (( f )) && return 22; fi
  printf '%s' "$body"
}
inflight() { FAKE="$1"; local out rc; out=$(app_inflight "http://fake:8111"); rc=$?; echo "${out}|rc=$rc"; }

echo "# app_inflight — 알 때만 숫자"
check "진행 중 0(POST 타이머 있음 · 값 0) → 0" "$(inflight ok_zero)" "0|rc=0"
check "진행 중 3 → 3(uri 태그가 UNKNOWN이어도 놓치지 않는다)" "$(inflight ok_three)" "3|rc=0"
check "POST를 한 번도 시작하지 않은 앱 → 알고 있는 0" "$(inflight no_post)" "0|rc=0"
echo "# app_inflight — 조회 실패는 0이 아니라 모름"
check "지표 조회 HTTP 500 → 모름" "$(inflight base_fail)" "|rc=1"
check "method:POST 조회 404 → 모름" "$(inflight tag_404)" "|rc=1"
check "method:POST 응답이 JSON 아님 → 모름" "$(inflight tag_garbage)" "|rc=1"
check "method:POST 응답 ACTIVE_TASKS 없음(빈 값) → 모름" "$(inflight tag_empty)" "|rc=1"
check "availableTags 없음 → 모름" "$(inflight no_tags)" "|rc=1"

# ---- lb_started_after: 가짜 remote(로컬 bash) + 가짜 docker exec(로컬 sh) — 기록 파일을 바꿔 가며 -------------------------------------------
remote() { bash -c "$1"; }
docker() { [[ "$1" == exec ]] || return 99; shift; while [[ "$1" == -* ]]; do shift; done; shift; "$@"; }
export -f docker
LOGF="$STUB/cut.log"
started() { local out rc; out=$(LB_ACCESS_LOG="$LOGF" lb_started_after "$@"); rc=$?; echo "${out}|rc=$rc"; }
LB_ACCESS_LOG="$LOGF"
printf '%s\n' "100.5 0.2 10.0.0.9:8101 201" "101.0 0.1 10.0.0.9:8101 201" "99.0 0.5 10.0.0.8:8101 201" > "$LOGF"
echo "# lb_started_after"
check "효력 뒤 그 IP로 시작한 요청 2건 → 2" "$(started 10.0.0.9 100.0 0)" "2|rc=0"
check "그 IP 요청 없음 → 0(파일을 읽었을 때만)" "$(started 10.0.0.7 100.0 0)" "0|rc=0"
rm -f "$LOGF"
check "기록 파일 없음 → 모름(tail 실패에 awk가 0을 찍지 않는다)" "$(started 10.0.0.9 100.0 0)" "|rc=1"
printf 'x\n' > "$LOGF"
check "기록이 효력 시각의 바이트보다 짧다(잘림·교체) → 모름" "$(started 10.0.0.9 100.0 500)" "|rc=1"
remote() { return 255; }
check "원격 실패(ssh 255) → 모름" "$(started 10.0.0.9 100.0 0)" "|rc=1"

# ---- drain_verdict(do_drain의 유일한 성공 기준) --------------------------------------------------------------------------------------------
echo "# drain_verdict"
if [[ "$(type -t drain_verdict)" != function ]]; then
  check "drain_verdict 함수 있음(do_drain 판정 단일 출처)" "없음" "있음"
else
  check "시작 0 · 진행 중 0 → ok" "$(drain_verdict 0 0)" "ok"
  check "시작 0 · 진행 중 0.0 → ok" "$(drain_verdict 0 0.0)" "ok"
  check "시작 모름 → started_after_unknown" "$(drain_verdict '' 0)" "started_after_unknown"
  check "시작 0 · 진행 중 모름 → inflight_unknown(성공 아님)" "$(drain_verdict 0 '')" "inflight_unknown"
  check "시작 0 · 진행 중 1 → incomplete" "$(drain_verdict 0 1)" "incomplete"
  check "시작 2 · 진행 중 모름 → incomplete(시작한 요청이 먼저)" "$(drain_verdict 2 '')" "incomplete"
fi
# ---- 호출처: 진행 중·시작 요청을 do_drain 밖에서 따로 재는 곳이 없다(run.sh · lib13.sh grep) -------------------------------------------------
RUN="$(dirname "$LIB13")/run.sh"
if [[ -f "$RUN" ]]; then
  n=$(grep -c 'drain_verdict' "$RUN"); check "run.sh do_drain이 drain_verdict로 판정" "$(( n > 0 ))" "1"
  n=$(grep -cE 'inf" =~|"\$n" == 0' "$RUN"); check "run.sh에 따로 쓴 진행 중·시작 비교식 없음" "$n" "0"
fi
echo; echo "$FAILS 실패"
exit $(( FAILS > 0 ))
