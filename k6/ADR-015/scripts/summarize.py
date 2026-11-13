#!/usr/bin/env python3
"""ADR-015 요약기 — 회차 관문(G-H 하네스 · G-E 환경 → status) · 모드 실패(G-F — 유효 회차의 실패 자료) · 지표 · 캠페인 판정(성공 → 고르는 규칙 1~4단 → 교체 관문 G1~G4 → 결론) · RESULT.md.
기준: 측정 전 고정 규칙 초안(15-09 — 메인이 ADR-015에 반영). 계획 값은 plan15.py(단일 출처). 읽기 · 검증 · 범위 · 정지 분류 도구는 ADR-014 summarize.py를 그대로 쓴다
(Inputs · clock · server_rows · db_cpu · stall_chunks · classify_chunk · rng · lower · much_higher · eliminate — 그 파일은 고치지 않는다).

    summarize.py --rep <회차폴더>               → rep-summary.json · 표준 출력 마지막 줄 = status(ok | invalid:<사유,…>)
    summarize.py <캠페인 결과 폴더>              → result.json · RESULT.md
    summarize.py --early-check <캠페인 결과 폴더> → exit 0 증거 ≥ 1 · 1 = ok인데 회차 1 race current 경합 증거 0(캠페인 멈춤 exit 4) · 2 = 무효·없음(보류 — 재측정 경로)
    summarize.py --v12 <v12 결과 폴더>           → v12-summary.json(#5 판정 — 트랜잭션 안 판 유지 | CONCURRENTLY 후속 | 판정 불가)

판정 입력이 없거나 깨지면 0이 아니라 None이고 그 입력의 사유로 회차가 무효다(ADR-014 '판정 입력 전수 점검'과 같은 규칙). 캠페인에서 None = 판정 불가."""
import csv
import gzip
import re
from datetime import datetime
import importlib.util
import json
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import plan15  # noqa: E402

_spec = importlib.util.spec_from_file_location("summarize14", HERE.parent.parent / "ADR-014" / "scripts" / "summarize.py")
S14 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(S14)
load, num, ts, p99, jsonl, Inputs, clock = S14.load, S14.num, S14.ts, S14.p99, S14.jsonl, S14.Inputs, S14.clock
rng, lower, much_higher, eliminate = S14.rng, S14.lower, S14.much_higher, S14.eliminate
S14.INPUT_REASON.update({"act15_before": "app-metric-missing", "act15_end": "app-metric-missing", "act15_wend": "app-metric-missing",
                         "table_before": "table-stats-missing", "table_end": "table-stats-missing"})

# 끝 판정기 정합 열(초안 측정-5 · §설계-8.2 '이중 결과 수 = 0' + ADR-014 측정-7 정합 목록) — 모든 모드 기대 0. 하나라도 없거나 숫자가 아니면 end-check-failed
CONS_ZERO = ("v_duplicate_confirmed_seats", "v_reserved_without_confirmed", "v_confirmed_on_unreserved_seat", "v_hold_on_unheld_seat",
             "active_on_reserved_seats", "double_sold_seats", "v_counter_mismatch", "v_over_limit_users", "multi_active_seats",
             "v_duplicate_hold_seats", "v_held_without_hold", "expired_hold_rows", "v_stale_held_seats")
CONS_INFO = ("held_no_active", "confirmed", "reserved", "hold_rows")
TIMEOUT_CODE = "1050"   # k6 error_code — 요청 시간 초과(30초 — 5xx와 따로 센다 · 그 밖의 코드 0은 하네스 EOF 류)
# 확정 계약 = (HTTP 상태, 본문 code) 허용 쌍(리뷰 M15-21 — ADR-015 §설계-3 공통 에러 계약 · N4 · ApiExceptionHandler.kt:46-55). k6는 상태와 무관하게 본문 code를 읽는다
#   (lib15 confirmCodeOf — 200 = OK · 본문 없음 = NOCODE). 상태만 맞고 code가 다르거나(예: 404 NOCODE · 409 HOLD_NOT_FOUND) code만 맞는 쌍은 계약 밖(G-F noncontract)
CONTRACT_PAIRS = {("200", "OK"), ("404", "HOLD_NOT_FOUND"), ("403", "HOLD_NOT_OWNED"), ("409", "HOLD_EXPIRED"), ("409", "SEAT_NOT_HELD")}
# 새 모드 ① · ③ · ④ 양성 대조(리뷰 M15-26 — 서버 논리 증거 · 루프 1 M15-3b의 '계획 오프셋 win 묶음 404'를 바꿈): 단독 확정이
#   404 HOLD_NOT_FOUND ∧ 응답 끝 te + 시계 오차 − 지연 + 여유 < e ∧ te − ts ≥ 지연. ①④는 :now(AtomicConfirmProcess.kt:41) 바로 뒤에 지연을 자고(:69) C1을 쓴다 →
#   :now ≤ te(참값) − 지연 < e → C1의 조건(user · expires_at > :now)은 참인데 0행 = 그 홀드 행이 지연 중에 사라졌다(진단 :94 '없음' 404 · HOLD_EXPIRED 아님).
#   지우는 쪽은 배치뿐이다(단독 — 같은 홀드의 다른 확정 없음 · 배치는 앱 Clock으로 expires_at ≤ 배치 now인 행만 지운다 — §설계-3 '시각'). ③은 O1(:147 — 지연 앞)에서도
#   404가 나므로 te − ts ≥ 지연(지연을 잤다)을 함께 요구한다(잔여 한계: O1 404가 서버 앞단 대기 ≥ 지연 뒤에 나면 구별 못 한다 — 초안 측정-14)
NOT_FOUND = ("404", "HOLD_NOT_FOUND")
# FM1 양성 대조를 G1에 쓰는 모드(리뷰 M15-6): A′ 배치는 경합 창 안에서도 커밋해 좌석을 푼다 → 재선점 201이 그 확정 [송신, 응답 끝] 안에 들어올 수 있다
#   (fix1 로컬 배치 10초 2분: ① 7건). current(JPA 배치가 겹친 확정 때문에 틱 롤백으로 지는 쪽이 잦다 — 같은 조건 FM1 겹침 0 · 틱 실패 6/14)와
#   ②(잠금 — 재선점 NOWAIT 409)는 구조상 드물거나 0 → G1에 쓰지 않고 측정-14 한계로 적는다
FM1_PC_MODES = ("conditional", "optimistic", "conditional-extend")
# ②(pessimistic) 양성 대조(리뷰 M15-26): ②는 P1 · P2 잠금을 쥔 채 지연을 자고 이긴다 — 진 쪽 응답이 없다. 진 쪽 = A′ 배치 B3가 그 홀드 행 잠금을 기다림 →
#   B3 대기 표본(scripts/b3wait-sampler.sh — race 셀 20ms)에서 'B3가 Lock 대기 ∧ 막은 세션 = idle in transaction · 마지막 문장 ② P2' 표본 ≥ 1(연속 표본은 한 번).
#   pgss B3 max_exec_time(루프 1 근사 — 실행 시간에는 삭제 작업 · CPU · I/O도 든다)은 판정에서 빼고 설명 열(pess_b3)로만
PESS_B3_WAIT_FRAC = 0.25   # 설명 열 기준(루프 1 D-14 — 판정에 쓰지 않는다)
# 측정-7 'A도 같은 문턱'(리뷰 M15-17 — ADR-014 측정-7 :765 · :766 · 측정-8 :800 ⓐ와 같은 꼴): 새 모드 X의 G-F 항목 중 current도 같은 식으로 재는(기대 0) 항목은
#   current가 같은 (항목, 셀)의 유효 회차에서 어겼거나 그 (항목, 셀)을 세울 수 없으면(current 유효 < 3) X의 실패가 아니라 판정 불가다(하네스 · 환경 · 조건 원인을 가를 수 없다).
#   대상 = 선점 · 재선점 5xx · 시간 초과(1050 — 요청 이름별) · 방식 정지 · 잘못된 거부(M15-19). 대상 밖 = 정합 · API 이중 결과 · 40P01(current의 위반은 A 결함 — G2(a)) ·
#   확정 5xx(current 기대 > 0) · current가 내지 않는 갈래(payment-start · 연장 · unexpected · 되돌림 · 계약 밖 코드 — current는 그 경로가 없거나 기대가 다르다)
A_RULE_EXACT = ("5xx:hold", "5xx:rehold", "method_stall", "early_reject")
A_RULE_PREFIX = ("timeout:",)
A_DEFECT = ("integrity", "api_double", "deadlock")
RACE, COST = "race", "cost"
# 3단 · G3 비용 지표 = cost 셀 셋만(리뷰 M15-33 — 사용자 결정 2026-11-05): race 셀은 ④ 재선점 구조(연장 때문에 409를 거듭 받는다) · ② 잠금 보유 지연이 섞여
#   모드가 아니라 부하 구성을 잰다 → race 확정 p99 · race DB CPU는 설명 열(DESC_METRICS)로만. 결과 문서용으로 모드별 재선점 요청 수(req.rehold)를 함께 낸다
G3_METRICS = [(COST, "p99.confirm_200"), (COST, "p99.hold_201"), (COST, "db_cpu")]
DESC_METRICS = [(RACE, "p99.confirm_200"), (RACE, "db_cpu"), (RACE, "req.rehold"), (COST, "req.rehold")]
# current 경합 증거 · G2(b)(c)의 원인 귀속(리뷰 M15-29): 회차 app.log(run.sh가 docker logs로 거둔 app.log.gz)에서 확정 5xx · 배치 틱 실패의 예외 종류를 센다.
#   0행 DELETE 갈래 = Hibernate 고아 DELETE가 0행 → StaleObjectStateException → ObjectOptimisticLockingFailureException(15-02 실측 — ADR-015 :124 · log 11-04 23:47).
#   .164 시험(int164 current race · 하네스 8388e198) 실물 형식: 요청 = '[dispatcherServlet] … Request processing failed: <예외>' + ReservationController.confirm 프레임 ·
#   틱 = 'TaskUtils$LoggingErrorHandler : Unexpected error occurred in scheduled task' + 다음 줄 <예외> + ExpireHoldsService.expire 프레임.
#   귀속 = 맨 위 예외 ∈ ZERO_ROW_EXC ∧ StaleObjectStateException이 SeatHold 엔티티에서 ∧ AbstractDeleteCoordinator(DELETE 행 수 검사) 프레임
ZERO_ROW_EXC = ("org.springframework.orm.ObjectOptimisticLockingFailureException", "org.hibernate.StaleObjectStateException")
LOG_HEAD = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:?\d\d))\s+(ERROR|WARN|INFO|DEBUG|TRACE)\s+\d+\s+---\s+"
                      r"(?:\[[^\]]*\]\s+)?\[[^\]]*\]\s+(\S+)\s+:\s?(.*)$")
EXC_NAME = re.compile(r"([a-z][\w$]*(?:\.[\w$]+)+(?:Exception|Error))\b")
APPLOG_SLACK_S = 2.0   # k6 첫 요청(t0) 앞 이만큼부터 센다(서버 시각 — 예열 · 시드 단계의 사건을 넣지 않는다)
# #15 배치 문장 묶음(리뷰 M15-9 — pg_stat_statements 정규화 문장의 앞머리 · 공백 하나로 접은 뒤 비교). 실측 문장(fix1 로컬 스모크 — PG 16 · Hibernate 6.6)에서 옮겼다.
#   shared = 확정 · 선점도 같은 문장을 낸다(문장 글자로 배치 몫을 가를 수 없다) → 틱당 값에서 빼고 따로 적는다.
BATCH_STMTS = {
    "a_prime": [("B1", "SELECT DISTINCT schedule_id, user_id FROM seat_hold WHERE expires_at <=", False),
                ("B2", "SELECT q.schedule_id, q.user_id FROM user_hold_quota q JOIN unnest(", False),
                ("B2b", "SELECT id FROM seat_hold WHERE schedule_id = $1 AND user_id = $2 AND expires_at <=", False),
                ("B3", "DELETE FROM seat_hold h USING unnest(", False),
                ("B3b", "DELETE FROM seat_hold WHERE id = ANY(", False),
                ("B4", "UPDATE user_hold_quota SET cnt = cnt - $1 WHERE schedule_id = $2 AND user_id = $3 AND cnt >= $4", False),
                ("B5", "UPDATE product_seat st SET status = $3 WHERE st.id = ANY(", False),
                ("B5d", "SELECT id, status FROM product_seat WHERE id = ANY(", False)],
    "jpa": [("J1-finder", "select distinct ps1_0.id,ps1_0.row_no,ps1_0.schedule_id,ps1_0.seat_no,ps1_0.section,ps1_0.status from product_seat ps1_0 left join seat_hold h1_0", False),
            ("J4-decrement", "UPDATE user_hold_quota SET cnt = cnt - $1 WHERE schedule_id = $2 AND user_id = $3 AND cnt >= $4", False),
            ("J2-holds-load", "select h1_0.seat_id,h1_0.id,h1_0.expires_at,h1_0.held_at,h1_0.schedule_id,h1_0.user_id from seat_hold h1_0 where h1_0.seat_id=$1", True),
            ("J3-seat-update", "update product_seat set row_no=$1,schedule_id=$2,seat_no=$3,section=$4,status=$5 where id=$6", True),
            ("J5-hold-delete", "delete from seat_hold where id=$1", True)],
}
# 2단 · G2(b)(c) = 원인 귀속 값(리뷰 M15-29): current = app.log 0행 DELETE 갈래로 귀속된 확정 5xx · 틱 실패 · 새 모드 = 전체(보수적 — 귀속 안 된 5xx도 그 모드 몫이고 G-F 그대로)
STAGE2_METRICS = [(RACE, "g2.confirm_5xx"), (RACE, "g2.tick_failed")]


def parse_tags(s):
    return dict(kv.split("=", 1) for kv in s.split("&") if "=" in kv) if s else {}


def read_csv(path, w0, w1):
    """k6-requests.csv.gz 한 번 읽기. 창 = PC [w0, w1)(행 시각 = 완료 정수 초 — ADR-014 read_csv와 같은 창 규칙).
    lat(이름:상태별 창 안 지연) · per_sec(창 안 초별 응답 수) · err(이름별 5xx · 시간 초과 — 실행 전체) · code0_other(시간 초과가 아닌 코드 0 — 실행 전체) ·
    dropped(시나리오별 — 실행 전체) · conf · rehold_ok · pay · ext_then_lost · hold_fail · crn."""
    out = {"lat": {}, "per_sec": {}, "err": {}, "err5": {}, "timeout": {}, "st_all": {}, "code0_other": {}, "dropped": {}, "conf": [], "rehold_ok": [], "pay": {},
           "pay_fl": {}, "ext_then_lost": 0, "hold_fail": {}, "hold_fail_other": {}, "crn": {}, "req": {}, "sess": {}, "conf_skip": {}, "pay_body_bad": {}}
    inc = lambda d, k: d.__setitem__(k, d.get(k, 0) + 1)   # noqa: E731
    with gzip.open(path, "rt", newline="") as f:
        rd = csv.reader(f)
        head = next(rd)
        ix = {k: head.index(k) for k in ("metric_name", "timestamp", "metric_value", "name", "scenario", "status", "extra_tags")}
        i_ec = head.index("error_code") if "error_code" in head else None
        for r in rd:
            if not r:
                continue
            m = r[ix["metric_name"]]
            t = int(r[ix["timestamp"]])
            inwin = w0 <= t < w1 or int(w0) == t
            sc = r[ix["scenario"]]
            if m == "http_req_duration":
                name, st = r[ix["name"]], r[ix["status"]]
                inc(out["req"], name)
                inc(out["st_all"], f"{name}:{st}")
                if st == "0":
                    ec = r[i_ec] if i_ec is not None else ""
                    if ec == TIMEOUT_CODE:
                        inc(out["err"], name)
                        inc(out["timeout"], name)            # 시간 초과는 5xx와 따로(리뷰 M15-3a — 경합 증거 · G2(b)에 섞지 않는다)
                    else:
                        inc(out["code0_other"], f"{name}:{ec or '?'}")
                elif st.isdigit() and int(st) >= 500:
                    inc(out["err"], name)
                    inc(out["err5"], name)
                if inwin:
                    out["per_sec"][t] = out["per_sec"].get(t, 0) + 1
                    out["lat"].setdefault(f"{name}:{st}", []).append(float(r[ix["metric_value"]]))
            elif m == "dropped_iterations":
                out["dropped"][sc] = out["dropped"].get(sc, 0) + int(float(r[ix["metric_value"]]))
            elif m in ("crn_n", "crn_h1", "crn_h2"):
                c = out["crn"].setdefault(sc, {"n": 0, "h1": 0, "h2": 0})
                c[m[4:]] += int(float(r[ix["metric_value"]]))
            elif m in ("sess", "conf_skip"):
                tg = parse_tags(r[ix["extra_tags"]])
                fl = tg.get("fl", "?")
                out[m][fl] = out[m].get(fl, 0) + (int(tg["n"]) if str(tg.get("n", "")).isdigit() else 10 ** 9)   # n 없음 = 맞출 수 없음(대조가 어긋나게)
            elif m == "pay_body_bad":
                inc(out["pay_body_bad"], parse_tags(r[ix["extra_tags"]]).get("fl", "?"))
            elif m in ("conf", "rehold_ok", "pay", "ext_then_lost", "hold_fail"):
                tg = parse_tags(r[ix["extra_tags"]])
                tg["_in"] = inwin
                if m == "conf":
                    out["conf"].append(tg)
                elif m == "rehold_ok":
                    out["rehold_ok"].append(tg)
                elif m == "ext_then_lost":
                    out["ext_then_lost"] += 1
                elif m == "pay":
                    k = f"{tg.get('st')}:{tg.get('code')}"
                    inc(out["pay"], k)
                    inc(out["pay_fl"], f"{tg.get('fl', '?')}|{k}")
                else:
                    # hold_fail(리뷰 M15-12): 하네스 무효(G-H)는 4xx · 본문 없음만 — 자기 좌석 첫 선점이 거절됐다(대역 · 시드 결함).
                    #   5xx · 시간 초과는 err의 hold 몫으로 모드 실패(G-F)에 가고, 여기서는 따로 적기만 한다(하네스 무효로 숨기지 않는다)
                    st = tg.get("st") or ""
                    k = f"{st}:{tg.get('code')}"
                    inc(out["hold_fail"] if st == "body" or (st.isdigit() and 400 <= int(st) < 500) else out["hold_fail_other"], k)
    return out


def _f(x):
    """태그 문자열 → float(숫자가 아니면 None)."""
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def batch_statements(rows, path, ticks):
    """#15(리뷰 M15-9): pgss 행(정규화 문장 · calls · total_ms · max_ms)에서 배치 문장 묶음을 앞머리로 골라 묶음별 합 · 틱당 값.
    path = a_prime(새 모드 A′) | jpa(current). shared 묶음(확정 · 선점과 같은 문장)은 틱당 값에서 빼고 따로 적는다. 상위 N행에 기대지 않는다(모든 행을 본다)."""
    classes = {}
    norm = lambda q: " ".join(str(q or "").split())   # noqa: E731
    for r in rows:
        q = norm(r.get("q"))
        for name, prefix, shared in BATCH_STMTS[path]:
            if q.startswith(prefix):
                c = classes.setdefault(name, {"calls": 0, "total_ms": 0.0, "max_ms": 0.0, "shared": shared})
                c["calls"] += int(r.get("calls") or 0)
                c["total_ms"] += float(r.get("total_ms") or 0)
                c["max_ms"] = max(c["max_ms"], float(r.get("max_ms") or 0))
                break
    own = [c for c in classes.values() if not c["shared"]]
    # not_seen = 이 회차에 한 번도 안 나온 배치 문장 묶음(B2b · B3b · B5d는 MISSING 쌍 · 되돌림 실패 갈래라 0이 보통 — 결측이 아니다)
    out = {"path": path, "classes": classes, "ticks": ticks, "not_seen": [n for n, _, sh in BATCH_STMTS[path] if not sh and n not in classes]}
    if num(ticks) and ticks > 0 and own:
        out["per_tick"] = {"calls": sum(c["calls"] for c in own) / ticks, "ms": sum(c["total_ms"] for c in own) / ticks}
    else:
        out["per_tick"] = None
    return out


def b3wait_summary(rows, period_ms):
    """B3 대기 표본(b3wait-sampler.sh 줄 — t · b3_lock · b3_on_p2) → 에피소드 수(b3_on_p2 ≥ 1인 연속 표본 = 한 번 · 표본 사이가 주기 × 3을 넘으면 끊는다) ·
    표본 수 · 최대 공백(초) · B3 Lock 대기 표본 수(막은 쪽 무관 — 설명). 시각 · 값을 못 읽는 줄은 bad로 센다(에피소드에 넣지 않는다)."""
    pts, bad = [], 0
    for r in rows:
        try:
            pts.append((ts(r["t"]), int(r["b3_lock"]), int(r["b3_on_p2"])))
        except (KeyError, ValueError, TypeError):
            bad += 1
    pts.sort()
    ep, prev_t, prev_on = 0, None, False
    for t, _, on in pts:
        cont = prev_t is not None and t - prev_t <= 3 * period_ms / 1000
        if on >= 1 and not (prev_on and cont):
            ep += 1
        prev_t, prev_on = t, on >= 1
    gaps = [b[0] - a[0] for a, b in zip(pts, pts[1:])]
    return {"episodes": ep, "samples": len(pts), "bad": bad, "on_p2_samples": sum(1 for p in pts if p[2] >= 1),
            "b3_lock_samples": sum(1 for p in pts if p[1] >= 1), "gap_max_s": max(gaps) if gaps else None, "period_ms": period_ms}


def _log_t(s):
    """로그 머리 시각(ISO · Z 또는 ±hh:mm) → epoch 초(못 읽으면 None)."""
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def applog_attrib(path, t_from):
    """app.log(.gz) → 확정 5xx · 틱 실패의 예외 종류별 수와 0행 DELETE 귀속 수(리뷰 M15-29). 사건 = 머리 줄(LOG_HEAD) + 다음 머리 줄 앞까지의 줄(스택).
    t_from(서버 epoch 초) 앞 사건은 세지 않는다. 파일 없음 · 못 읽음 · 머리 줄 0 · 시각을 못 읽는 ERROR 머리 = None(판정 불가 — 0으로 채우지 않는다).
    kind: confirm(ReservationController.confirm 프레임) · other_request(그 밖 요청 5xx) · tick(ExpireHoldsService.expire) · other_task(그 밖 예약 작업)."""
    path = Path(path)
    if not path.exists():
        return None
    out = {k: {"zero_row": 0, "by_exc": {}} for k in ("confirm", "other_request", "tick", "other_task")}
    heads, bad = 0, 0
    try:
        with gzip.open(path, "rt", errors="replace") if path.suffix == ".gz" else open(path, errors="replace") as f:
            lines = f.read().splitlines()
    except (OSError, EOFError):
        return None
    ev = []

    def flush(e):
        nonlocal bad
        if not e or e[1] != "ERROR":
            return
        t, _, logger, msg, body = e
        if logger.endswith("[dispatcherServlet]") and "Request processing failed" in msg:
            m = EXC_NAME.search(msg.split("Request processing failed", 1)[1])
            text = msg + "\n" + "\n".join(body)
            kind = "confirm" if "ReservationController.confirm(" in text else "other_request"
        elif "LoggingErrorHandler" in logger and "scheduled task" in msg:
            first = next((x for x in body if x.strip()), "")
            m = EXC_NAME.match(first.strip())
            text = "\n".join(body)
            kind = "tick" if "ExpireHoldsService.expire(" in text else "other_task"
        else:
            return
        if t is None:
            bad += 1
            return
        if t < t_from:
            return
        exc = m.group(1) if m else "?"
        o = out[kind]
        o["by_exc"][exc] = o["by_exc"].get(exc, 0) + 1
        if exc in ZERO_ROW_EXC and "StaleObjectStateException" in text and "domain.SeatHold#" in text and "AbstractDeleteCoordinator" in text:
            o["zero_row"] += 1
    cur = None
    for ln in lines:
        h = LOG_HEAD.match(ln)
        if h:
            heads += 1
            flush(cur)
            cur = (_log_t(h.group(1)), h.group(2), h.group(3), h.group(4), [])
        elif cur is not None:
            cur[4].append(ln)
    flush(cur)
    if heads == 0 or bad:
        return None
    for o in out.values():
        o["total"] = sum(o["by_exc"].values())
    out["heads"] = heads
    return out


def _d(a, b, *path):
    """스냅숏 차(b − a) — 어느 쪽이든 경로 값이 숫자가 아니면 None(0으로 채우지 않는다)."""
    def g(x):
        for p in path:
            x = x.get(p) if isinstance(x, dict) else None
        return x
    va, vb = g(a), g(b)
    return vb - va if num(va) and num(vb) else None


def rep_summary(d):
    d = Path(d)
    inp = Inputs(d)
    reasons, flags = set(), []
    plan = inp.json("plan", "plan.json", need=("mode", "cell"), nums=("rep", "ttl_s", "window.from_s", "window.len_s", "delay_ms", "d_arr_s"))
    if plan is not None and (plan["mode"] not in plan15.MODES or plan["cell"] not in plan15.GROUPS):
        plan = inp.miss("plan", f"plan.json 모드·셀이 계획에 없음({plan['mode']!r} · {plan['cell']!r})")
    if plan is None:
        return _write(d, {"status": "invalid:plan-missing", "reasons": ["plan-missing"]})
    mode, cell = plan["mode"], plan["cell"]
    new = mode in plan15.NEW_MODES
    if (d / "harness-reasons.txt").exists():
        reasons.update(x.strip() for x in (d / "harness-reasons.txt").read_text().splitlines() if x.strip())
    else:
        inp.miss("harness_reasons", "harness-reasons.txt 없음")
    ks = inp.json("k6_summary", "k6-summary.json", nums=("setup_data.startedAt",))
    clk = clock(inp)
    if clk and (clk["rtt_half_max"] > S14.CLOCK_RTT_HALF_MAX_S or clk["drift"] > S14.CLOCK_DRIFT_MAX_S):
        reasons.add("clock-offset-bad")
    off = clk["off"] if clk else None
    t0 = ks["setup_data"]["startedAt"] / 1000 if ks else None
    w0 = t0 + plan["window"]["from_s"] if t0 is not None else None
    w1 = w0 + plan["window"]["len_s"] if w0 is not None else None
    M = {"mode": mode, "cell": cell, "rep": plan["rep"], "t0_pc": t0, "window_pc": [w0, w1], "off_s": off}
    F = {}   # 모드 실패(G-F) — 유효 회차의 실패 자료
    pk = inp.json("pre_k6", "pre-k6.json", nums=("seed_done_epoch",))
    if pk and t0 is not None:
        M["seed_to_first_request_s"] = t0 - pk["seed_done_epoch"]
        if abs(M["seed_to_first_request_s"] - S14.PRE_K6_TARGET_S) > S14.PRE_K6_TOL_S:
            reasons.add("pre-k6-mismatch")
    # 설정(모드 · 지연 · ④ 키 · Flyway 위치) · 시드 · 스키마
    if (d / "app-config.json").exists():
        rc, cc = plan15.config_check(mode, cell, d / "app-config.json", plan.get("knobs") or {})
        reasons.update(cc["reasons"])
        M["config_detail"] = cc["detail"]
    else:
        inp.miss("app_config", "app-config.json 없음")
    wu = inp.json("warmup", "warmup-status.json", nums=("rc",))
    if wu is not None and wu["rc"] != 0:
        reasons.add("warmup-failed")
    sc = inp.json("seed_check", "seed-check.json", nums=("schedules", "want_schedules", "seats", "want_seats", "available", "holds", "reservations",
                                                          "quota_rows", "v12_valid", "v13_column", "v14_triggers"))
    if sc:
        if not (sc["schedules"] == sc["want_schedules"] and sc["seats"] == sc["want_seats"] == sc["available"]
                and sc["holds"] == 0 and sc["reservations"] == 0 and sc["quota_rows"] == 0):
            reasons.add("seed-mismatch")
        want_v14 = mode == "optimistic"
        if sc["v12_valid"] != 1 or sc["v13_column"] != 1 or (sc["v14_triggers"] > 0) != want_v14:
            reasons.add("schema-mismatch")
        M["flyway"] = sc.get("flyway")
    ai = (d / "app-instances.txt").read_text().split() if (d / "app-instances.txt").exists() else None
    if ai is None or len(ai) != 1:
        reasons.add("app-instances-not-one")   # 모드 혼합 금지(§3.5)
    pl = inp.json("pc_link", "pc-link.json", need=("readable",), nums=("down",))
    if pl is not None:
        if pl["readable"] is not True:
            inp.miss("pc_link", "pc-link.json readable 아님")
        elif pl["down"] > 0:
            reasons.add("pc-link-down")
    # k6 PC MemAvailable 하한(리뷰 M15-25 — ADR-014 summarize.py:818-847 · 명세 R5 ② 그대로 이식): 판정 창 안 5초 표본(timeline-client.jsonl mem_avail_kb — lib14 start_pollers14가
    #   남긴다) 최소 < plan14.PC_MEM_RUN_MIN_KB(4GiB) → pc-mem-low(환경 — campaign.sh kind_of가 환경 상한으로 센다) · 파일 없음 · 깨진 줄 · 값 결측 · 공백 > 30초 → pc-mem-missing
    if w0 is not None:
        cl = jsonl(d / "timeline-client.jsonl")
        if cl is None:
            inp.miss("client", "timeline-client.jsonl 없음")
        elif cl.broken:
            inp.miss("client", f"timeline-client.jsonl 깨진 줄 {cl.broken}")
        else:
            mrows, mbad = [], 0
            for r in cl:
                try:
                    t = ts(r["t"])
                except (KeyError, ValueError, TypeError):
                    mbad += 1
                    continue
                if w0 <= t < w1:
                    if num(r.get("mem_avail_kb")):
                        mrows.append((t, r["mem_avail_kb"]))
                    else:
                        mbad += 1
            tt = [w0] + [t for t, _ in mrows] + [w1]
            gap = max(b - a for a, b in zip(tt, tt[1:]))
            if mbad or not mrows or gap > S14.plan14.PC_MEM_GAP_MAX_S:
                inp.miss("client", f"MemAvailable 표본 — 못 읽는 줄 {mbad} · 창 안 {len(mrows)}개 · 최대 공백 {gap:.0f}초(> {S14.plan14.PC_MEM_GAP_MAX_S:.0f}초면 결측)")
            else:
                mn = min(m for _, m in mrows)
                M["pc_mem"] = {"min_kb": mn, "n": len(mrows), "gap_max_s": gap, "floor_kb": S14.plan14.PC_MEM_RUN_MIN_KB}
                if mn < S14.plan14.PC_MEM_RUN_MIN_KB:
                    reasons.add("pc-mem-low")
    pc = inp.json("probe_check", "probe-check.json", need=("ok",))
    if pc is not None and pc["ok"] is not True:
        reasons.add("probe-missing")
    kw = inp.json("k6_window", "k6-window.json", nums=("exit",))
    if kw is not None and kw["exit"] != 0:
        reasons.add(f"k6-exit-{kw['exit']:g}")
    # 시계 차 오차 상한(ms — 리뷰 M15-26): k6 ts · te 태그 = PC 시각 + 준 시계 차(k6-window.json clock_offset_ms_given — run.sh가 k6 직전 표본으로 준 값).
    #   참 시계 차는 회차 앞 · 뒤 표본(clock-offset.json — ADR-009 clock_offset: 왕복 최소 표본 · 오차 ≤ 왕복/2) 사이에 있다고 본다 →
    #   오차 ≤ max(|준 값 − 앞|, |준 값 − 뒤|) + 왕복/2 최대 + 1ms(정수 ms 반올림 · Date.now 해상도). 못 세우면 None(서버 논리 증거 · 잘못된 거부 = None — 0으로 채우지 않는다)
    err_ms = None
    if clk and kw is not None and num(kw.get("clock_offset_ms_given")):
        g = kw["clock_offset_ms_given"]
        err_ms = max(abs(g - clk["before"]["offset_s"] * 1000), abs(g - clk["after"]["offset_s"] * 1000)) + clk["rtt_half_max"] * 1000 + 1
    M["clock_err_ms"] = err_ms
    # k6 CSV
    csvd = None
    if (d / "k6-requests.csv.gz").exists() and w0 is not None:
        try:
            csvd = read_csv(d / "k6-requests.csv.gz", w0, w1)
        except (OSError, EOFError, ValueError, StopIteration, IndexError, csv.Error) as e:
            inp.miss("csv", f"CSV 읽기 실패 {type(e).__name__}")
    else:
        inp.miss("csv", "k6-requests.csv.gz 없음 또는 창 모름")
    # 서버 표본(DB CPU · path-gap)
    srows, tlines = S14.server_rows(d / "timeline-server.jsonl", off) if off is not None else (None, None)
    if srows is None:
        inp.miss("server", "timeline-server.jsonl 없음")
    elif srows.broken:
        inp.miss("server", f"timeline-server.jsonl 깨진 줄 {srows.broken}")
    elif w0 is not None:
        dc = S14.db_cpu(srows, w0, w1)
        M["db_cpu"] = dc["mean"]
        M["db_cpu_detail"] = dc
        if dc["missing"]:
            reasons.add("db-cpu-missing")
        g, _ = S14.gaps_ok([t for t in tlines if w0 <= t < w1], w0, w1, S14.PATH_GAP_MAX_S)
        if g > S14.PATH_GAP_MAX_S:
            reasons.add("path-gap")
    cons = inp.json("consistency", "consistency.json", nums=CONS_ZERO)
    tb = inp.json("table_before", "table-before.json", nums=("deadlocks",))
    te = inp.json("table_end", "table-end.json", nums=("deadlocks",))
    a15 = {k: inp.json(f"act15_{k}", f"act15-{f}.json", nums=("unique_violation", "unexpected", "tick.ok", "tick.failed", "extension_lost", "server_5xx"))
           for k, f in (("before", "before"), ("wend", "window-end"), ("end", "end"))}
    for k, a in a15.items():
        if a and a.get("failed"):
            a15[k] = inp.miss(f"act15_{k}", f"지표 조회 실패 {a['failed']}")
    # GC · 정지 덩어리(ADR-014 env-stall 규칙 그대로 — 측정기 · GC · 대기 · Hikari)
    gc = None
    if t0 is not None and off is not None and (d / "gc.log.gz").exists():
        gc = S14.S9.gc_summary(d / "gc.log.gz", (w0 + off, w1 + off), w0 + off)
    if not gc or not gc.get("collector"):
        inp.miss("gc", "GC 로그 없음·빈 로그")
    ls = (d / "logs-status.txt").read_text().splitlines() if (d / "logs-status.txt").exists() else None
    if ls is None or any(("pipe=0/0" not in x) or x.rstrip().endswith("bytes=0") for x in ls if x.strip()) or len([x for x in ls if x.strip()]) < 2:
        inp.miss("logs", "logs-status.txt 파이프 실패·빈 로그")
    hik = jsonl(d / "timeline-hikari.jsonl")
    for h in hik or []:
        h["_t"] = h["t_ms"] / 1000 if num(h.get("t_ms")) else ts(h["t"])
    waits = jsonl(d / "timeline-waits.jsonl")
    if waits is None:
        inp.miss("waits", "timeline-waits.jsonl 없음")
    # B3 대기 표본(리뷰 M15-26 — ② 양성 대조 입력 · race 셀 다섯 모드 같은 주기로 켠다). ②에서만 판정 입력이다(없음 · 깨짐 → b3wait-missing — G-H) · 다른 모드는 설명
    if plan.get("b3wait_ms"):
        bw = jsonl(d / "timeline-b3wait.jsonl")
        if bw is None or bw.broken or not bw:
            if mode == "pessimistic":
                inp.miss("b3wait", f"timeline-b3wait.jsonl {'없음' if bw is None else f'깨진 줄 {bw.broken} · 표본 {len(bw)}'}")
        else:
            M["b3wait"] = b3wait_summary(bw, plan["b3wait_ms"])
            if mode == "pessimistic" and M["b3wait"]["bad"]:
                inp.miss("b3wait", f"timeline-b3wait.jsonl 못 읽는 줄 {M['b3wait']['bad']}")
    probe_srv = None
    if (d / "k6-probe-server.csv.gz").exists() and off is not None:
        try:
            probe_srv = S14._probe_csv(d / "k6-probe-server.csv.gz", off)
        except (OSError, EOFError, ValueError, StopIteration):
            probe_srv = None
    cfg = load(d / "app-config.json") or {}
    hmax = (cfg.get("hikaricp.connections.max") or [None])[0]

    # ---- 지표 · 관문 ----
    if csvd is not None:
        lat = csvd["lat"]
        M["p99"] = {k: p99(lat.get(n) or []) for k, n in (("confirm_200", "confirm:200"), ("hold_201", "hold:201"), ("rehold_409", "rehold:409"),
                                                          ("pay_start_200", "pay_start:200"))}
        M["dropped"] = csvd["dropped"]
        if sum(csvd["dropped"].values()) > 0:
            reasons.add("dropped-iterations")                       # 세션 번호가 main · rehold 사이에서 어긋난다(G-H)
        M["code0_other"] = csvd["code0_other"]
        if csvd["code0_other"]:
            reasons.add("k6-code0")                                 # 하네스 EOF 류(keep-alive · 연결 — ADR-014 06c) — 방식 실패로 세지 않고 재측정(G-H)
        M["hold_fail"] = csvd["hold_fail"]
        M["hold_fail_other"] = csvd["hold_fail_other"]              # 첫 선점 5xx · 시간 초과(G-F 쪽 — err의 hold 몫과 같은 건)
        if csvd["hold_fail"]:
            reasons.add("hold-fail")                                # 자기 좌석 첫 선점 4xx — 좌석 대역 · 시드 결함(G-H · 리뷰 M15-12)
        M["err"] = csvd["err"]
        M["err5"] = csvd["err5"]
        M["timeout"] = csvd["timeout"]
        M["req"] = csvd["req"]
        # 확정 응답 분포 M1(창 안 — 완료 초 기준: ADR-014 창 규칙) · 오프셋 묶음별. ④ 경계 흐름(fl=x)은 'x:' 머리로 가른다
        key = lambda c: f"{'x:' if c.get('fl') == 'x' else ''}{c.get('cb')}|{'dup' if c.get('dup') in ('1', '2') else 'single'}|{c.get('st')}:{c.get('code')}"   # noqa: E731
        # FM1 겹침(리뷰 M15-6 · M15-18): 경합 창 후보 = 계획 win 묶음 ∧ 단독(dup=0) ∧ (④는 경계 흐름 fl=x — 기준 흐름은 연장이 창을 닫는다). post · 짝 · ④ 기준 흐름은 설명 열(fm1_overlap_all)
        fm1_fl = "x" if mode == "conditional-extend" else "b"
        dist, dist_all, seats_ok, spans, spans_all = {}, {}, set(), {}, {}
        for c in csvd["conf"]:
            if c.get("code") == "OK":
                seats_ok.add(c.get("seat"))
            k = key(c)
            dist_all[k] = dist_all.get(k, 0) + 1
            if num(_f(c.get("ts"))) and num(_f(c.get("te"))):
                sp = (_f(c["ts"]), _f(c["te"]))
                spans_all.setdefault(c.get("seat"), []).append(sp)
                if c.get("cb") == "win" and c.get("dup") == "0" and (c.get("fl") or "b") == fm1_fl:
                    spans.setdefault(c.get("seat"), []).append(sp)
            if c["_in"]:
                dist[k] = dist.get(k, 0) + 1
        M["confirm_dist"] = dict(sorted(dist.items()))
        M["confirm_ok_window"] = sum(v for k, v in dist.items() if k.endswith("|200:OK"))
        M["confirm_tps"] = M["confirm_ok_window"] / plan["window"]["len_s"]
        # 계약 밖 확정 코드는 회차 전체에서 센다(리뷰 M15-10 — 실패는 넓게: 측정-0 판정 창 · 분포 M1만 창 안)
        M["confirm_noncontract"] = {k: v for k, v in sorted(dist_all.items()) if tuple(k.split("|")[2].split(":", 1)) not in CONTRACT_PAIRS}   # (상태, code) 쌍 — 리뷰 M15-21
        M["confirm_5xx"] = csvd["err5"].get("confirm", 0)           # M2 = 상태 ≥ 500만(시간 초과는 confirm_timeout — 리뷰 M15-3a)
        M["confirm_timeout"] = csvd["timeout"].get("confirm", 0)
        M["api_double"] = len(seats_ok & {r.get("seat") for r in csvd["rehold_ok"]})   # 같은 좌석 확정 200 + 재선점 201(기대 0 — 모든 모드)
        M["rehold_ok"] = len(csvd["rehold_ok"])
        M["rehold_ok_dense"] = sum(1 for r in csvd["rehold_ok"] if r.get("ph") == "d")
        # FM1 겹침(리뷰 M15-6): 재선점 201(응답 끝 서버 시각)이 같은 좌석 확정의 [송신, 응답 끝] 안 — '배치가 푼 좌석을 남이 잡은 뒤 그 확정이 쓴다' 순서가 부하에서 생겼다
        ov = lambda sp: sum(1 for r in csvd["rehold_ok"] if num(_f(r.get("t"))) and any(a <= _f(r["t"]) <= b for a, b in sp.get(r.get("seat"), [])))   # noqa: E731
        M["fm1_overlap"] = ov(spans)
        M["fm1_overlap_all"] = ov(spans_all)   # 설명(post · 짝 · ④ 기준 흐름 포함 — 루프 1 정의)
        # 잘못된 거부(리뷰 M15-19 — 모든 모드 · 모든 셀): 단독 확정의 응답 끝이 시계 오차를 보태도 기준 만료 e 앞(te + 오차 < e)이면 확정의 모든 문장이 e 전에 끝났다 —
        #   배치는 expires_at ≤ 배치 now(≥ e)인 행만 지우고 좌석은 HELD라 재선점도 못 잡는다 → 200 말고는 잘못된 거부(시간 초과 · 5xx 포함). 오차를 못 세우면 None(G-F에 넣지 않고 판정 입력 결측)
        #   기준 = 그 확정 앞에 서버가 준 가장 늦은 expiresAt(리뷰 M15-32 — k6 conf{el}: ④는 payment-start 200의 연장 만료 e′ · 기준 흐름도 e가 아니라 e′ ·
        #   다른 모드는 선점 201의 e와 같다). ④ 확정에 el이 없으면 기준을 모른다 → 판정 입력 결측(csv — 하네스)
        el_of = lambda c: _f(c.get("el")) if c.get("el") not in (None, "") else (None if mode == "conditional-extend" else _f(c.get("e")))   # noqa: E731
        if mode == "conditional-extend" and any(c.get("dup") == "0" and el_of(c) is None for c in csvd["conf"]):
            inp.miss("csv", "④ 확정 conf{el}(서버가 준 가장 늦은 만료) 없음")
        if err_ms is None:
            M["early_reject"] = None
        else:
            er, n_early = {}, 0
            for c in csvd["conf"]:
                te_, el_ = _f(c.get("te")), el_of(c)
                if c.get("dup") == "0" and num(te_) and num(el_) and te_ + err_ms < el_:
                    n_early += 1
                    if c.get("st") != "200":
                        er[f"{c.get('st')}:{c.get('code')}"] = er.get(f"{c.get('st')}:{c.get('code')}", 0) + 1
            M["early_reject"] = er
            M["early_n"] = n_early
        # 계획 확정 수 대 실제(리뷰 M15-23 — 흐름별): sess n 합 = conf 수 + conf_skip n 합. 어긋나면 반복이 확정 전에 끊겼다(gracefulStop · 스크립트 예외) → 하네스(G-H)
        nconf = {}
        for c in csvd["conf"]:
            nconf[c.get("fl") or "b"] = nconf.get(c.get("fl") or "b", 0) + 1
        M["confirm_count"] = {"planned": csvd["sess"], "sent": nconf, "skipped": csvd["conf_skip"]}
        if any(csvd["sess"].get(f, 0) != nconf.get(f, 0) + csvd["conf_skip"].get(f, 0) for f in set(csvd["sess"]) | set(nconf) | set(csvd["conf_skip"])):
            reasons.add("confirm-count-mismatch")
        M["pay"] = csvd["pay"]
        M["pay_fl"] = csvd["pay_fl"]
        M["ext_then_lost"] = csvd["ext_then_lost"]
        M["crn"] = csvd["crn"]
        secs, chunks = S14.stall_chunks(csvd["per_sec"], w0, w1)
        env_s, cls = 0, []
        for a, b in chunks:
            if b - a >= S14.STALL_MIN_S:
                k, why = S14.classify_chunk(a, b, off, probe_srv, (gc or {}).get("long_stops", []), waits, hik, hmax)
                cls.append({"from_rel": a - w0, "len": b - a, "class": k, **why})
                env_s += (b - a) if k == "env" else 0
        M["stall"] = {"secs": len(secs), "env_secs": env_s, "chunks": cls}
        if env_s > 0:
            reasons.add("env-stall")
        if any(c["class"] == "method" for c in cls):
            F["method_stall"] = sum(c["len"] for c in cls if c["class"] == "method")
    a0, a1, aw = a15["before"], a15["end"], a15["wend"]
    if a0 and a1:
        D = lambda *p: _d(a0, a1, *p)   # noqa: E731 — 회차 전체(창 밖 포함) 증분: 실패는 넓게 센다
        lat_w = ((aw or {}).get("lateness") or {}).get("db") if isinstance((aw or {}).get("lateness"), dict) else None
        M["app"] = {"unique_violation": D("unique_violation"), "unexpected": D("unexpected"), "tick_ok": D("tick", "ok"), "tick_failed": D("tick", "failed"),
                    "converted_db": D("converted_after_expiry", "db"), "converted_app": D("converted_after_expiry", "app"),
                    # lateness(리뷰 M15-15): Micrometer Timer MAX는 시간 창 감쇠 최대다 — 회차 끝(수렴 52초 뒤) 값은 이미 줄었을 수 있어 판정 창 끝 스냅숏 값을 쓴다
                    #   ('감쇠 최대 — 창 끝 직전 약 2분'). 건수 · 합은 회차 전체 증분 → 평균
                    "lateness_db_wend_max_s": lat_w.get("MAX") if isinstance(lat_w, dict) and num(lat_w.get("MAX")) else None,
                    "lateness_db_count": D("lateness", "db", "COUNT"), "lateness_db_total_s": D("lateness", "db", "TOTAL_TIME"),
                    "extend": {r: D("extend", r) for r in ("extended", "already", "not_needed", "too_late")},
                    "extension_lost": D("extension_lost"), "deleted_sweep": {e: D("deleted_sweep", e) for e in ("true", "false")},
                    "deleted_other": {p: D("deleted_other", p) for p in ("reclaim", "batch")},
                    "missing_pairs_sweep": D("missing_pairs_sweep"), "revert_sweep": {r: D("revert_sweep", r) for r in ("reverted", "not_held", "kept_active")},
                    "missing_mismatch": D("missing_mismatch"), "server_5xx": D("server_5xx")}
        lc, lt = M["app"]["lateness_db_count"], M["app"]["lateness_db_total_s"]
        M["app"]["lateness_db_mean_s"] = lt / lc if num(lc) and num(lt) and lc > 0 else None
        if aw:
            M["app_window"] = {"tick_failed": _d(a0, aw, "tick", "failed"), "unique_violation": _d(a0, aw, "unique_violation")}
        M["tick_failed"] = M["app"]["tick_failed"]
    if tb and te:
        M["deadlocks"] = te["deadlocks"] - tb["deadlocks"]
        # 행 연산 증분(DB 쪽 — 모드 무관 같은 정의): JPA 배치(current)는 seat.hold.expiry.deleted를 내지 않으므로 '지운 홀드 수'는 여기서 견준다(초안 측정-12 태그 차이 메모)
        M["db_tup"] = {f"{t}.{k}": _d(tb, te, "tables", t, k) for t, k in (("seat_hold", "n_tup_del"), ("product_seat", "n_tup_upd"), ("reservation", "n_tup_ins"))}
    if cons:
        M["cons"] = {k: cons[k] for k in CONS_ZERO + tuple(x for x in CONS_INFO if x in cons)}
    # pg_stat_statements(#15 — 켠 경우만 · 리뷰 M15-9): 배치 문장 묶음별 호출 · 시간(k6 직전 0으로 맞춘 뒤 회차 끝까지) ÷ 틱 수(같은 구간 — act15 before → end)
    pg = load(d / "pgss-end.json") if plan.get("pgss") else None
    app = M.get("app") or {}
    if plan.get("pgss"):
        if not (isinstance(pg, dict) and isinstance(pg.get("rows"), list)):
            inp.miss("pgss", "pgss-end.json 없음·깨짐(pgss 켬)")
        else:
            ticks = (app.get("tick_ok") or 0) + (app.get("tick_failed") or 0) if num(app.get("tick_ok")) and num(app.get("tick_failed")) else None
            M["batch_stmt"] = batch_statements(pg["rows"], "a_prime" if new else "jpa", ticks)
    # ---- 모드 실패(G-F) — 회차는 유효. 정합은 모든 모드(current의 위반은 'A 결함' — G2(a) 입력) ----
    if cons:
        bad = {k: cons[k] for k in CONS_ZERO if cons[k] != 0}
        if bad:
            F["integrity"] = bad
    if csvd is not None and M.get("api_double"):
        F["api_double"] = M["api_double"]
    if M.get("deadlocks"):
        F["deadlock"] = M["deadlocks"]
    if csvd is not None and csvd["timeout"]:
        F["timeout"] = csvd["timeout"]          # 시간 초과(1050) — 모든 모드 기록(새 모드는 실패 · current는 경합 증거가 아니라 실패 · 환경 신호로 따로 — 리뷰 M15-3a)
    if csvd is not None and M.get("early_reject"):
        F["early_reject"] = M["early_reject"]   # 잘못된 거부(리뷰 M15-19 — 모든 모드 · 새 모드는 'A도 같은 문턱' 대상)
    if new:
        for k in ("unique_violation", "unexpected", "extension_lost"):
            if app.get(k):
                F[k] = app[k]
        rv = app.get("revert_sweep") or {}
        if rv.get("not_held") or rv.get("kept_active"):
            F["revert_invariant"] = {k: rv[k] for k in ("not_held", "kept_active")}
        if csvd is not None:
            if sum(csvd["err5"].values()):
                F["5xx"] = csvd["err5"]
            if M.get("confirm_noncontract"):
                F["noncontract"] = M["confirm_noncontract"]
            if csvd["ext_then_lost"]:
                F["ext_then_lost"] = csvd["ext_then_lost"]
            if csvd["pay_body_bad"]:
                # ④ payment-start 200 본문 expiresAt 못 읽음(리뷰 M15-23): §3.4 '200 {holdId, expiresAt}' 계약 위반 — 본문은 앱이 만든다 → 모드 실패(G-F).
                #   k6 파서(lib14 isoMs) 결함이면 모든 ④ 회차에 나므로 시작 조건 ① 스모크(④ race)에서 먼저 드러난다
                F["pay_body_bad"] = csvd["pay_body_bad"]
    elif csvd is not None:
        other = {k: v for k, v in csvd["err5"].items() if k != "confirm"}
        if other:
            F["5xx_non_confirm"] = other   # current도 확정 밖 5xx는 기대하지 않는다(선점 · 재선점 경로 무변경)
    # ④ payment-start(리뷰 M15-4): 흐름 구성상 ④ 세션은 모두 e − 10초에 payment-start를 보내고(남은 10초 > payment-min-remaining 5초 · < 창 2분)
    #   모두 200 extended여야 한다 — 기대값 extended = 200 수 · already = not_needed = too_late = 0. 보낸 수 < main 선점 201 수 = 하네스(G-H — 반복이 끊겼다) ·
    #   비200 · 서버 갈래 불일치 = 모드 실패(G-F). 다른 모드에서 payment-start가 나가면 하네스 결함(G-H)
    if csvd is not None:
        sent = sum(csvd["pay"].values())
        ok200 = sum(v for k, v in csvd["pay"].items() if k.startswith("200:"))
        planned = csvd["st_all"].get("hold:201", 0)
        M["pay_sent"], M["pay_200"], M["pay_planned"] = sent, ok200, planned
        if mode == "conditional-extend":
            if sent < planned or sent == 0:
                reasons.add("pay-shortfall")
            non200 = {k: v for k, v in csvd["pay"].items() if not k.startswith("200:")}
            if non200:
                F["pay_non200"] = non200
            ext = app.get("extend") or {}
            want = {"extended": ok200, "already": 0, "not_needed": 0, "too_late": 0}
            if a0 and a1 and any(ext.get(k) != v for k, v in want.items()):
                F["extend_mismatch"] = {"got": ext, "want": want}
        elif sent:
            reasons.add("pay-unexpected")
    # 경합 증거(양성 대조 — 리뷰 M15-3a · M15-26): 회차 전체 · 단독 확정만(짝 진 쪽은 N2의 정상 결과라 배치 경합과 무관)
    #   current = app.log에서 0행 DELETE 갈래(StaleObjectState → ObjectOptimisticLockingFailure)로 귀속된 확정 5xx(FM1) + 배치 틱 실패(FM3) — 리뷰 M15-29
    #     (루프 2까지의 '계획 win 단독 5xx + 틱 실패 지표'는 원인을 가리지 않아 다른 예외로도 성립했다 → 설명 열 evidence_desc로)
    #   ① · ③ = 기준 흐름(fl=b) · ④ = 경계 흐름(fl=x — e′ 기준) 단독 확정 중 404 HOLD_NOT_FOUND ∧ te + 오차 − 지연 + 여유 < e ∧ te − ts ≥ 지연(서버 :now < e — NOT_FOUND 머리말)
    #   ② = B3 대기 표본에서 'B3가 ② P2 뒤 지연 중인 확정을 기다림' 에피소드 수(b3wait-sampler.sh) — 표본 없음 = None(판정 불가)
    #   설명 열(판정 아님): 계획 오프셋 win 묶음 404(루프 1 근사) · 송신 기준(ts + 오차 < e − 여유 — 메인 초안 식) · pgss B3 max(②)
    def singles(fl):
        return [c for c in (csvd or {}).get("conf", []) if c.get("dup") == "0" and (c.get("fl") or "b") == fl]
    parts, delay, margin = {}, plan["delay_ms"], plan.get("evidence_margin_ms", plan15.EVIDENCE_MARGIN_MS)
    # app.log 예외 종류(리뷰 M15-29 — 모든 모드 설명 · current는 판정 입력): k6 첫 요청(t0 — 서버 시각) 앞 APPLOG_SLACK_S부터
    al = applog_attrib(d / "app.log.gz", t0 + off - APPLOG_SLACK_S) if t0 is not None and off is not None else None
    M["applog"] = al
    k5 = csvd["err5"].get("confirm", 0) if csvd is not None else None
    tfm = app.get("tick_failed")
    if mode == "current":
        # current = app.log에서 0행 DELETE 갈래(StaleObjectState → ObjectOptimisticLockingFailure — 15-02 실측)로 귀속된 확정 5xx + 배치 틱 실패만.
        #   귀속 안 된 확정 5xx(k6 5xx − 귀속) · 틱 실패(지표 − 귀속)는 evidence_desc.unattributed로 따로 센다(설명 — 원인 후보는 by_exc). 로그를 못 읽으면 None(판정 불가)
        a5 = al["confirm"]["zero_row"] if al else None
        at = al["tick"]["zero_row"] if al else None
        parts = {"confirm_5xx_zero_row": a5, "tick_failed_zero_row": at}
        M["g2"] = {"confirm_5xx": a5, "tick_failed": at}
        M["evidence_desc"] = {
            "win_single_5xx_k6": sum(1 for c in singles("b") if c.get("cb") == "win" and (c.get("st") or "").isdigit() and int(c["st"]) >= 500) if csvd is not None else None,
            "confirm_5xx_k6": k5, "tick_failed_metric": tfm,
            "unattributed": {"confirm_5xx": k5 - a5 if num(k5) and num(a5) else None, "tick_failed": tfm - at if num(tfm) and num(at) else None},
            "log_confirm_by_exc": al["confirm"]["by_exc"] if al else None, "log_tick_by_exc": al["tick"]["by_exc"] if al else None}
    elif mode in ("conditional", "optimistic", "conditional-extend"):
        fl = "x" if mode == "conditional-extend" else "b"
        nf = [c for c in singles(fl) if (c.get("st"), c.get("code")) == NOT_FOUND] if csvd is not None else []
        tv = lambda c, k: _f(c.get(k))   # noqa: E731
        ok = lambda c: all(num(tv(c, k)) for k in ("ts", "te", "e"))   # noqa: E731
        srv = (sum(1 for c in nf if ok(c) and tv(c, "te") - tv(c, "ts") >= delay and tv(c, "te") + err_ms - delay + margin < tv(c, "e"))
               if csvd is not None and err_ms is not None and delay > 0 else None)
        parts = {f"server_not_found_{fl}": srv}
        M["evidence_desc"] = {"planned_win_404": sum(1 for c in nf if c.get("cb") == "win"), "not_found_single": len(nf),
                              "send_based": sum(1 for c in nf if ok(c) and tv(c, "ts") + err_ms < tv(c, "e") - margin) if err_ms is not None else None}
    elif mode == "pessimistic":
        b3 = ((M.get("batch_stmt") or {}).get("classes") or {}).get("B3") or {}
        M["pess_b3"] = {"max_ms": b3.get("max_ms"), "calls": b3.get("calls"), "total_ms": b3.get("total_ms"), "desc_threshold_ms": delay * PESS_B3_WAIT_FRAC}
        parts = {"b3_wait_on_p2": (M.get("b3wait") or {}).get("episodes")}
    if mode != "current":
        # 새 모드 G2(b)(c) 값 = 전체(보수적 — 그 모드 몫 · 귀속 안 된 5xx도 G-F 그대로)
        M["g2"] = {"confirm_5xx": M.get("confirm_5xx") if csvd is not None else None, "tick_failed": tfm}
    M["evidence_parts"] = parts
    M["fm2_evidence"] = app.get("unique_violation") if mode == "current" else None
    M["contention_evidence"] = sum(parts.values()) if parts and all(num(x) for x in parts.values()) else None
    reasons.update(inp.reasons())
    st = "ok" if not reasons else "invalid:" + ",".join(sorted(reasons))
    return _write(d, {"status": st, "reasons": sorted(reasons), "missing": inp.missing, "flags": flags, "metrics": M, "fail": F})


def _write(d, s):
    try:
        (Path(d) / "rep-summary.json").write_text(json.dumps(s, ensure_ascii=False, indent=1, default=str))
    except OSError:
        pass
    return s


# ---- 캠페인 ----------------------------------------------------------------------------------------------------------------------------
def _get(m, dotted):
    for p in dotted.split("."):
        m = m.get(p) if isinstance(m, dict) else None
    return m if num(m) else None


def fail_items(f):
    """G-F 자료(fail dict) → 항목 열쇠 목록(리뷰 M15-17 'A도 같은 문턱'의 (항목, 셀) 단위). 5xx · 시간 초과는 요청 이름별로 가른다(current의 5xx_non_confirm도 같은 열쇠)."""
    out = []
    for k, v in (f or {}).items():
        if k in ("5xx", "5xx_non_confirm", "timeout") and isinstance(v, dict):
            out += [f"{'timeout' if k == 'timeout' else '5xx'}:{n}" for n in v]
        else:
            out.append(k)
    return out


def a_rule(item):
    return item in A_RULE_EXACT or item.startswith(A_RULE_PREFIX)


def collect(root):
    reps = {}
    for st in Path(root).glob("*/*/rep[0-9]/status"):
        d = st.parent
        if st.read_text().strip() != "ok":
            continue
        s = load(d / "rep-summary.json") or rep_summary(d)
        if s.get("status") != "ok":
            continue
        reps.setdefault((s["metrics"]["cell"], s["metrics"]["mode"]), []).append(s)
    return reps


def campaign(root, write=True):
    reps = collect(root)
    vals = lambda c, m, key: [_get(s["metrics"], key) for s in reps.get((c, m), [])]   # noqa: E731
    R = lambda c, m, key: rng(vals(c, m, key))   # noqa: E731
    nvalid = {(c, m): len(reps.get((c, m), [])) for c in plan15.GROUPS for m in plan15.MODES}
    valid3 = {k: v >= plan15.REPS for k, v in nvalid.items()}
    fails = {m: [f"{c}/rep{s['metrics']['rep']}: {sorted(s['fail'])}" for c in plan15.GROUPS for s in reps.get((c, m), []) if s.get("fail")] for m in plan15.MODES}
    # A 결함(G2(a) — 측정-6 G-F 'current = A 결함': 판정기 · API 수준 이중 결과 · 40P01 — 리뷰 M15-13)
    cur_defect = any(k in s["fail"] for c in plan15.GROUPS for s in reps.get((c, "current"), []) for k in ("integrity", "api_double", "deadlock"))
    res = {"valid3": {f"{c}/{m}": v for (c, m), v in valid3.items()}, "nvalid": {f"{c}/{m}": v for (c, m), v in nvalid.items()}, "fails": fails,
           "current_integrity_defect": cur_defect, "candidates": {}, "undetermined": []}
    ev = {m: vals(RACE, m, "contention_evidence") for m in plan15.MODES}
    pc_ok = lambda m: len(ev[m]) >= plan15.REPS and all(x is not None and x >= 1 for x in ev[m])   # noqa: E731
    # G4 캠페인 유효 — current 두 셀 유효 3회 · current race 회차마다 경합 증거 ≥ 1(app.log 0행 DELETE 귀속 확정 5xx + 틱 실패 — 리뷰 M15-29 · 시간 초과는 따로 기록)
    g4_parts = {"valid3": all(valid3[(c, "current")] for c in plan15.GROUPS), "positive_control": pc_ok("current")}
    g4 = all(g4_parts.values())
    res["G4"] = {"ok": g4, "parts": g4_parts, "evidence": {m: ev[m] for m in plan15.MODES},
                 "current_timeout": [s["fail"].get("timeout") for s in reps.get((RACE, "current"), []) if s["fail"].get("timeout")]}
    # FM1 겹침(리뷰 M15-6 — 설명 · 관문 아님): 모드별 race 회차의 '재선점 201이 같은 좌석 확정의 [송신, 응답 끝] 안' 건수. current(JPA 배치가 틱 롤백으로 지는 쪽이
    #   잦다)와 ②(잠금 — 재선점 NOWAIT 409)는 구조상 드물거나 0이다(초안 측정-14) — M12(API 수준 이중 결과)는 G-F 문턱으로만 쓰고 'FM1-Y를 부하가 쟀다'의 근거로 쓰지 않는다
    res["fm1_overlap"] = {m: vals(RACE, m, "fm1_overlap") for m in plan15.MODES}
    # 'A도 같은 문턱'(리뷰 M15-17 — 측정-7): current의 (항목, 셀) — current 유효 회차의 G-F 항목 합집합
    cur_items = {c: {i for s in reps.get((c, "current"), []) for i in fail_items(s.get("fail"))} for c in plan15.GROUPS}
    hard, soft = {}, {}
    for m in plan15.NEW_MODES:
        hard[m], soft[m] = [], []
        for c in plan15.GROUPS:
            for s in reps.get((c, m), []):
                for i in fail_items(s.get("fail")):
                    tag = f"{c}/rep{s['metrics']['rep']}: {i}"
                    if i in A_DEFECT or not a_rule(i):
                        hard[m].append(tag)
                    elif not valid3[(c, "current")]:
                        soft[m].append(f"{tag} — current {c} 유효 {nvalid[(c, 'current')]}/{plan15.REPS}라 'A도 같은 문턱'을 세울 수 없음")
                    elif i in cur_items[c]:
                        soft[m].append(f"{tag} — current도 같은 (항목, 셀)에서 어김(하네스 · 환경 · 조건 원인)")
                    elif i.startswith("timeout:") and any(x.startswith("timeout:") for x in cur_items[c]):
                        # 시간 초과는 요청 이름과 무관하게 묶는다(Opus 루프 3 OQ2): 환경 원인 시간 초과는 그때 떠 있던 요청에 아무렇게나 걸린다 —
                        #   current에 그 셀 시간 초과가 하나라도 있으면 X의 어느 요청 이름 시간 초과든(④ pay_start 포함) X의 실패로 가르지 못한다
                        soft[m].append(f"{tag} — current도 같은 셀에서 시간 초과({sorted(x for x in cur_items[c] if x.startswith('timeout:'))} — 요청 이름 무관 묶음)")
                    else:
                        hard[m].append(tag)
    res["a_rule"] = {m: {"fail": hard[m], "undetermined": soft[m]} for m in plan15.NEW_MODES}
    # G1 — 새 모드 상태: fail(G-F 1건이라도 — 'A도 같은 문턱' 대상 항목은 current가 같은 (항목, 셀)을 어기지 않았을 때만) |
    #   undetermined('A도 같은 문턱' · 유효 반복 부족(재측정 한도 뒤) · 양성 대조 None/0 · 2 · 3단 지표 범위 없음 — 리뷰 M15-1 · M15-14 · M15-17) | success
    status, why = {}, {}
    for m in plan15.NEW_MODES:
        if hard[m]:
            status[m], why[m] = "fail", "G-F " + " · ".join(hard[m][:5])
        elif soft[m]:
            status[m], why[m] = "undetermined", "A도 같은 문턱 " + " · ".join(soft[m][:5])
        elif not all(valid3[(c, m)] for c in plan15.GROUPS):
            status[m], why[m] = "undetermined", "유효 반복 부족 " + ", ".join(f"{c} {nvalid[(c, m)]}/{plan15.REPS}" for c in plan15.GROUPS if not valid3[(c, m)])
        elif not pc_ok(m):
            status[m], why[m] = "undetermined", f"양성 대조 성립 안 함(race 경합 표지 {ev[m]})"
        elif m in FM1_PC_MODES and not (len(res["fm1_overlap"][m]) >= plan15.REPS and all(num(x) for x in res["fm1_overlap"][m]) and sum(res["fm1_overlap"][m]) >= 1):
            status[m], why[m] = "undetermined", f"FM1 양성 대조 없음(race FM1 겹침 {res['fm1_overlap'][m]} — M12 = 0의 뜻이 서지 않는다)"
        elif any(R(c, m, k) is None for c, k in STAGE2_METRICS + G3_METRICS):
            status[m], why[m] = "undetermined", "2 · 3단 지표 범위 없음 " + ", ".join(f"{c}/{k}" for c, k in STAGE2_METRICS + G3_METRICS if R(c, m, k) is None)
        else:
            status[m], why[m] = "success", ""
        res["candidates"][m] = {"G1": status[m] == "success", "status": status[m], "why": why[m],
                                "G1_detail": {"valid": {c: nvalid[(c, m)] for c in plan15.GROUPS}, "fails": fails[m], "evidence": ev[m]}}
    cands = [m for m in plan15.NEW_MODES if status[m] == "success"]

    def beats2(y, x):
        better = any(lower(R(c, y, k), R(c, x, k)) for c, k in STAGE2_METRICS)
        worse = any(lower(R(c, x, k), R(c, y, k)) for c, k in STAGE2_METRICS)
        return better and not worse

    def beats3(y, x):
        return any(much_higher(vals(c, x, k), vals(c, y, k)) for c, k in G3_METRICS) and not any(much_higher(vals(c, y, k), vals(c, x, k)) for c, k in G3_METRICS)
    stage_of, pool = {}, list(cands)
    for stage, fn in (("2", beats2), ("3", beats3)):
        keep, out = eliminate(pool, fn)
        for x in out:
            stage_of[x] = stage
        pool = keep
    for x in sorted(pool, key=plan15.SIMPLICITY.index):
        stage_of.setdefault(x, "4")
    rank = sorted(cands, key=lambda x: ({"4": 0, "3": 1, "2": 2}[stage_of[x]], plan15.SIMPLICITY.index(x)))
    res["rank"], res["eliminated_at"] = rank, stage_of

    def gates(m):
        """G2 · G3 — ok | False(확정적으로 못 넘음) | None(판정 불가). G3 hit가 있으면 판정 불가 지표가 함께 있어도 '걸림'(ADR-014 :801 끝)."""
        # 원인 귀속 값(리뷰 M15-29): current = app.log 0행 DELETE 갈래로 귀속된 수 · X = 전체 — 귀속 안 된 current 5xx · 틱 실패로 X가 '이기지' 않는다
        b5, bc = R(RACE, m, "g2.confirm_5xx"), R(RACE, "current", "g2.confirm_5xx")
        tf, tc = R(RACE, m, "g2.tick_failed"), R(RACE, "current", "g2.tick_failed")
        a, b, c = cur_defect and not hard[m], lower(b5, bc), lower(tf, tc)
        g2 = True if (a or b or c) else (None if None in (b5, bc, tf, tc) else False)
        hit = [f"{cc}/{k}" for cc, k in G3_METRICS if much_higher(vals(cc, m, k), vals(cc, "current", k))]
        und3 = [f"{cc}/{k}" for cc, k in G3_METRICS if R(cc, m, k) is None or R(cc, "current", k) is None]
        g3 = False if hit else (None if und3 else True)
        return {"G2": {"a_integrity": a, "b_5xx": b, "c_tick": c, "ok": g2}, "G3": {"ok": g3, "hit": hit, "undetermined": und3}}
    for m in plan15.NEW_MODES:
        if status[m] != "fail":
            res["candidates"][m].update(gates(m))
    passes = lambda m: res["candidates"][m]["G2"]["ok"] is True and res["candidates"][m]["G3"]["ok"] is True   # noqa: E731
    maybe = lambda m: res["candidates"][m]["G2"]["ok"] is not False and res["candidates"][m]["G3"]["ok"] is not False   # noqa: E731 — 넘었을 수 있다
    new_default, blocked = None, None
    for m in rank:   # 순위 차례 — 처음 모두 통과한 후보. 그 앞에 관문 판정 불가 후보가 있으면 멈춘다(리뷰 M15-2 · ADR-014 :801 ⓒ · :802 ⓓ)
        if passes(m):
            new_default = m
            break
        if maybe(m):
            blocked = m
            break
    undet_block = [m for m in plan15.NEW_MODES if status[m] == "undetermined" and maybe(m)]   # 판정 불가 후보가 이겼을 수 있다(관문에서 확정적으로 걸리지 않았다)
    res["undetermined"] = [f"{m}: {why[m]}" for m in plan15.NEW_MODES if status[m] == "undetermined"]
    provisional = f" · 잠정: {new_default or '교체 후보 없음'} · 순위 {rank}"
    if not g4:
        verdict = f"판정 불가 → 사용자 보고(G4 — {', '.join(k for k, v in g4_parts.items() if not v)})"
    elif blocked:
        verdict = f"판정 불가 → 사용자 보고(앞 순위 {blocked}의 관문 판정 불가 — G2 {res['candidates'][blocked]['G2']['ok']} · G3 {res['candidates'][blocked]['G3']['ok']}){provisional}"
    elif undet_block:
        verdict = f"판정 불가 → 사용자 보고(후보 판정 불가 — {' · '.join(f'{m}: {why[m]}' for m in undet_block)}){provisional}"
    elif new_default:
        verdict = f"교체 — 새 기본값 {new_default}"
    else:
        # current 유지 사유는 사실대로(리뷰 M15-34): 판정 불가 후보는 여기 오기 전에 모두 관문에서 확정적으로 걸렸다(아니면 위 undet_block)
        def caught(m):
            g = res["candidates"][m]
            return " · ".join(x for x in ("G2 False" if g["G2"]["ok"] is False else "", f"G3 hit {g['G3']['hit']}" if g["G3"]["ok"] is False else "") if x)
        und_caught = [f"{m}({caught(m)})" for m in plan15.NEW_MODES if status[m] == "undetermined"]
        tail = f" · 판정 불가였으나 관문에서 확정적으로 걸림: {' · '.join(und_caught)}" if und_caught else ""
        if not cands:
            fl = [m for m in plan15.NEW_MODES if status[m] == "fail"]
            verdict = f"current 유지 ①(G1 성공 후보 없음 — 실패: {', '.join(fl) or '없음'}{tail})"
        else:
            verdict = f"current 유지 ②(성공 후보가 모두 관문에서 확정적으로 걸림 — {' · '.join(f'{m}({caught(m)})' for m in rank)}{tail})"
    if verdict.startswith("교체") and any(rank.index(x) < rank.index(new_default) for x in rank):
        verdict += " · 주의: 앞 순위 후보가 관문에서 걸렸다(결과 문서에 그 관문 기록)"
    # #5 V12 단계(측정-10)를 캠페인 완료 관문에 넣는다(리뷰 M15-30 · M15-31): campaign.sh가 남긴 v12-stage.json(skipped · rc) + v12-summary 판정.
    #   단계 누락(--skip-v12 · 폴더 · 단계 기록 없음) · 실패(rc ≠ 0) · 필수 값 결측(v12_summary 판정 불가)이면 결론 옆에 'V12 판정 불가'를 적고,
    #   교체 결론은 그대로 두되 '운영 적용 전 V12 판정 필요'를 붙인다(교체 커밋 · 운영 적용 앞에 #5를 다시 잰다)
    vroot = Path(root) / "v12"
    stage = load(Path(root) / "v12-stage.json")
    res["v12"] = v12_summary(vroot, write=False) if vroot.is_dir() else None
    if not isinstance(stage, dict):
        v12why = "V12 단계 기록(v12-stage.json) 없음"
    elif stage.get("skipped") is True:
        v12why = "--skip-v12(단계 건너뜀)"
    elif stage.get("rc") != 0:
        v12why = f"V12 단계 실패(rc={stage.get('rc')!r})"
    elif res["v12"] is None:
        v12why = "v12 결과 폴더 없음"
    elif str(res["v12"].get("verdict", "")).startswith("판정 불가"):
        v12why = res["v12"]["verdict"]
    else:
        v12why = None
    res["v12_gate"] = {"determined": v12why is None, "why": v12why, "stage": stage, "verdict": (res["v12"] or {}).get("verdict")}
    if v12why is None:
        verdict += f" · #5 V12: {res['v12']['verdict']}"
    else:
        verdict += f" · V12 판정 불가({v12why})"
        if verdict.startswith("교체"):
            verdict += " — 운영 적용 전 V12 판정 필요"
    res["decision"] = {"verdict": verdict, "new_default": new_default if verdict.startswith("교체") else None, "provisional": new_default,
                       "v12_determined": v12why is None, "v12_required_before_apply": verdict.startswith("교체") and v12why is not None}
    res["ranges"] = {f"{c}/{m}/{k}": R(c, m, k) for m in plan15.MODES for c, k in STAGE2_METRICS + G3_METRICS}
    # 설명 열(판정 아님 — 리뷰 M15-33): race 확정 p99 · race DB CPU · 모드별 재선점 요청 수(req.rehold)
    res["desc_ranges"] = {f"{c}/{m}/{k}": R(c, m, k) for m in plan15.MODES for c, k in DESC_METRICS}
    if write:
        (Path(root) / "result.json").write_text(json.dumps(res, ensure_ascii=False, indent=1, default=str))
        (Path(root) / "RESULT.md").write_text(render_md(res))
    return res


def render_md(res):
    L = ["# ADR-015 확정 원자성 측정 결과(요약기 — 측정 전 고정 규칙 초안 측정-8)", "", f"**결론**: {res['decision']['verdict']}", "",
         "| 모드 | 상태 | 사유 | G2 이긴다 | G3 비용 | 탈락 단 |", "|---|---|---|---|---|---|"]
    for m in plan15.NEW_MODES:
        c = res["candidates"].get(m, {})
        L.append(f"| {m} | {c.get('status')} | {c.get('why') or '-'} | {c.get('G2', {}).get('ok', '-')} | {c.get('G3', {}).get('ok', '-')} | {res['eliminated_at'].get(m, '-')} |")
    L += ["", f"G4 캠페인 유효: {res['G4']}", "", f"#5 V12 관문: {res.get('v12_gate')}", "", "## 범위(유효 3회 [최소, 최대] — 2단 · 3단 · G2 · G3 판정)", ""]
    L += [f"- {k}: {v}" for k, v in res["ranges"].items()]
    L += ["", "## 설명 열(판정 아님 — race 확정 p99 · race DB CPU · 재선점 요청 수 req.rehold)", ""] + [f"- {k}: {v}" for k, v in (res.get("desc_ranges") or {}).items()]
    L += ["", "## 모드 실패(G-F)", ""] + [f"- {m}: {v}" for m, v in res["fails"].items() if v]
    if res.get("undetermined"):
        L += ["", "## 판정 불가 후보", ""] + [f"- {x}" for x in res["undetermined"]]
    if res.get("v12"):
        L += ["", "## #5 V12 생성 시간", "", f"{res['v12'].get('verdict')}"]
    return "\n".join(L) + "\n"


def early_check(root):
    """회차 1 race current 조기 확인(리뷰 M15-11): 0 = ok · 경합 증거 ≥ 1 / 1 = ok인데 증거 0(부하가 경합을 만들지 못했다 — 캠페인 exit 4) /
    2 = 무효 · 없음 · 증거 None(판정 보류 — 재측정 경로로 넘긴다 · 멈추지 않는다)."""
    for st in Path(root).glob("race/current/rep1/status"):
        s = load(st.parent / "rep-summary.json") or {}
        ev = (s.get("metrics") or {}).get("contention_evidence")
        status = st.read_text().strip()
        print(f"early-check: race/current/rep1 경합 증거 {ev!r} · status {status}")
        if status != "ok" or not num(ev):
            return 2
        return 0 if ev >= 1 else 1
    print("early-check: race/current/rep1 없음")
    return 2


# ---- #5 V12 생성 시간 ------------------------------------------------------------------------------------------------------------------
def v12_summary(root, write=True):
    """#5 판정(리뷰 M15-7 · M15-8): 계획(v12-plan.json — 크기 목록 · 반복)의 모든 (크기, 반복) 자리가 있어야 하고 자리마다
    시드 종료 코드 0 · 시드 실측 행 수(예약 = N · 좌석 = N + 1,000) · 값 · indisvalid가 맞아야 한다. 하나라도 빠지면 판정 불가. 판정은 계획한 가장 큰 N."""
    root = Path(root)
    vp = load(root / "v12-plan.json")
    out = {"by_rows": {}, "problems": []}
    if not (isinstance(vp, dict) and isinstance(vp.get("rows"), list) and vp["rows"] and all(isinstance(x, int) for x in vp["rows"]) and num(vp.get("reps"))):
        out["verdict"] = "판정 불가(#5 — v12-plan.json 없음·깨짐: 계획한 크기 · 반복을 모른다)"
        if write:
            (root / "v12-summary.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
        return out
    limit = vp.get("block_max_ms", plan15.V12_BLOCK_MAX_MS)
    out["block_max_ms"], out["planned"] = limit, {"rows": vp["rows"], "reps": int(vp["reps"])}
    for n in sorted(vp["rows"]):
        blk, tx_b, conc_b, conc_p, bad = [], [], [], [], []
        for k in range(1, int(vp["reps"]) + 1):
            r = load(root / f"v12-{n}-rep{k}.json")
            why = []
            if not isinstance(r, dict):
                why.append("파일 없음·깨짐")
            else:
                if r.get("error"):
                    why.append(f"error {r['error']}")
                if r.get("rows") != n or r.get("rep") != k:
                    why.append(f"rows/rep 표지 {r.get('rows')}/{r.get('rep')}")
                if r.get("seed_rc") != 0:
                    why.append(f"시드 rc {r.get('seed_rc')!r}")
                sd = r.get("seed") if isinstance(r.get("seed"), dict) else {}
                if sd.get("rows") != n or sd.get("seats") != n + 1000:
                    why.append(f"시드 행 수 예약 {sd.get('rows')!r}(기대 {n}) · 좌석 {sd.get('seats')!r}(기대 {n + 1000})")
                tx, cc = r.get("tx") or {}, r.get("concurrently") or {}
                if not (num(tx.get("build_ms")) and num(tx.get("probe_blocked_ms")) and isinstance(tx.get("probe_started_inside"), bool)) or tx.get("error"):
                    why.append("트랜잭션 안 값 없음(build_ms · probe_blocked_ms · probe_started_inside)")
                # CONCURRENTLY 판도 필수(리뷰 M15-31): 생성 시간 · 탐침 결과(대기 · 구간 안에서 떴나) — 빠지면 '두 방식 비교'가 불완전하다 → 판정 불가
                if not (num(cc.get("build_ms")) and num(cc.get("probe_ms")) and isinstance(cc.get("probe_started_inside"), bool)) or cc.get("error"):
                    why.append("CONCURRENTLY 값 없음(build_ms · probe_ms · probe_started_inside)")
                if not (tx.get("valid") is True and cc.get("valid") is True):
                    why.append(f"indisvalid tx {tx.get('valid')!r} · conc {cc.get('valid')!r}")
            if why:
                bad.append(f"rep{k}: " + " · ".join(why))
                blk.append(None)
                continue
            # 쓰기 차단 길이 = 트랜잭션 안 생성 시간(SHARE 락을 문장 끝 · 커밋까지 쥔다 — 그 순간 도착한 쓰기가 기다리는 최대) · 탐침이 구간 안에서 떴으면 탐침 대기와 큰 쪽
            #   (로컬 시험 15-09: 30만 행 생성 190ms < 탐침 지연 300ms라 탐침이 구간 밖에서 떴다 — 탐침만으로는 차단을 못 잰다)
            blk.append(max(tx["build_ms"], tx["probe_blocked_ms"] if tx.get("probe_started_inside") else 0))
            tx_b.append(tx["build_ms"])
            conc_b.append(cc.get("build_ms"))
            conc_p.append(cc.get("probe_ms"))
        out["by_rows"][n] = {"reps_ok": sum(1 for x in blk if x is not None), "tx_build_ms": tx_b, "tx_probe_blocked_ms": blk, "conc_build_ms": conc_b,
                             "conc_probe_ms": conc_p, "problems": bad, "block_max": max(blk) if blk and all(num(x) for x in blk) else None}
        out["problems"] += [f"{n:,}행 {x}" for x in bad]
    big = max(vp["rows"])
    b = out["by_rows"][big]
    if out["problems"]:
        out["verdict"] = f"판정 불가(#5 — 계획 자리 빠짐·결함 {len(out['problems'])}건: {out['problems'][:3]})"
    elif b["block_max"] <= limit:
        out["verdict"] = f"트랜잭션 안 판 유지 — {big:,}행 쓰기 차단 최대 {b['block_max']:.0f}ms ≤ {limit}ms"
    else:
        out["verdict"] = f"CONCURRENTLY 판 후속 — {big:,}행 쓰기 차단 최대 {b['block_max']:.0f}ms > {limit}ms"
    if write:
        (root / "v12-summary.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    return out


def main(argv):
    if len(argv) == 2 and argv[0] == "--rep":
        print(rep_summary(argv[1])["status"])
        return 0
    if len(argv) == 2 and argv[0] == "--early-check":
        return early_check(argv[1])
    if len(argv) == 2 and argv[0] == "--v12":
        print(json.dumps(v12_summary(argv[1]), ensure_ascii=False))
        return 0
    if len(argv) == 1 and not argv[0].startswith("-"):
        r = campaign(argv[0])
        print(r["decision"]["verdict"])
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
