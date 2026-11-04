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
# 확정 계약 코드(N4 — 200 · 404 HOLD_NOT_FOUND · 403 HOLD_NOT_OWNED · 409 HOLD_EXPIRED · 409 SEAT_NOT_HELD). k6 분류(ADR-009 errorCodeOf)는 409만 본문 code를 읽고
#   404 · 403은 'HTTP404' · 'HTTP403'으로 낸다(fix1 로컬 스모크에서 드러남 — 이름만 두면 정상 404가 계약 밖으로 세어져 새 모드가 늘 G-F에 걸린다)
CONTRACT = {"OK", "HTTP404", "HTTP403", "HOLD_NOT_FOUND", "HOLD_NOT_OWNED", "HOLD_EXPIRED", "SEAT_NOT_HELD"}
# 진 쪽 계약 코드(리뷰 M15-3b — 새 모드 ① · ③ · ④의 경합 표지): 배치가 경합 창 안에서 홀드를 먼저 지웠다 → C1 · O2 0행 → 진단 '없음' 404.
#   HOLD_EXPIRED는 넣지 않는다 — win 단독의 409 HOLD_EXPIRED는 송신이 e 직전이라 서버 :now가 이미 e 뒤인 늦은 도착이다(배치 무관 — fix1 로컬 배치 10초:
#   current 5 · ① 6건이 배치 없이도 났다). SEAT_NOT_HELD는 ① C2 · ③ O3에서 홀드를 먼저 쥔 뒤라 배치가 좌석을 되돌릴 수 없다(설계상 경합 표지가 아니다)
LOSER = {"HTTP404", "HOLD_NOT_FOUND"}
# FM1 양성 대조를 G1에 쓰는 모드(리뷰 M15-6): A′ 배치는 경합 창 안에서도 커밋해 좌석을 푼다 → 재선점 201이 그 확정 [송신, 응답 끝] 안에 들어올 수 있다
#   (fix1 로컬 배치 10초 2분: ① 7건). current(JPA 배치가 겹친 확정 때문에 틱 롤백으로 지는 쪽이 잦다 — 같은 조건 FM1 겹침 0 · 틱 실패 6/14)와
#   ②(잠금 — 재선점 NOWAIT 409)는 구조상 드물거나 0 → G1에 쓰지 않고 측정-14 한계로 적는다
FM1_PC_MODES = ("conditional", "optimistic", "conditional-extend")
# ②(pessimistic) 경합 표지(리뷰 M15-3b): ②는 P1 · P2 잠금을 쥔 채 지연을 자고 이긴다 — 진 쪽 응답이 없다. 진 쪽은 A′ 배치 B3(조건부 DELETE)가 그 홀드 행
#   잠금을 기다린 것이다 → pg_stat_statements B3 max_exec_time ≥ 지연 × 이 비율(fix1 로컬 스모크: ② B3 max 143ms 대 ① 0.4ms · 지연 200ms). pgss 꺼짐 = 판정 불가
PESS_B3_WAIT_FRAC = 0.25
RACE, COST = "race", "cost"
G3_METRICS = [(COST, "p99.confirm_200"), (COST, "p99.hold_201"), (COST, "db_cpu"), (RACE, "p99.confirm_200"), (RACE, "db_cpu")]
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
STAGE2_METRICS = [(RACE, "confirm_5xx"), (RACE, "tick_failed")]


def parse_tags(s):
    return dict(kv.split("=", 1) for kv in s.split("&") if "=" in kv) if s else {}


def read_csv(path, w0, w1):
    """k6-requests.csv.gz 한 번 읽기. 창 = PC [w0, w1)(행 시각 = 완료 정수 초 — ADR-014 read_csv와 같은 창 규칙).
    lat(이름:상태별 창 안 지연) · per_sec(창 안 초별 응답 수) · err(이름별 5xx · 시간 초과 — 실행 전체) · code0_other(시간 초과가 아닌 코드 0 — 실행 전체) ·
    dropped(시나리오별 — 실행 전체) · conf · rehold_ok · pay · ext_then_lost · hold_fail · crn."""
    out = {"lat": {}, "per_sec": {}, "err": {}, "err5": {}, "timeout": {}, "st_all": {}, "code0_other": {}, "dropped": {}, "conf": [], "rehold_ok": [], "pay": {},
           "pay_fl": {}, "ext_then_lost": 0, "hold_fail": {}, "hold_fail_other": {}, "crn": {}, "req": {}}
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
    pc = inp.json("probe_check", "probe-check.json", need=("ok",))
    if pc is not None and pc["ok"] is not True:
        reasons.add("probe-missing")
    kw = inp.json("k6_window", "k6-window.json", nums=("exit",))
    if kw is not None and kw["exit"] != 0:
        reasons.add(f"k6-exit-{kw['exit']:g}")
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
        key = lambda c: f"{'x:' if c.get('fl') == 'x' else ''}{c.get('cb')}|{'dup' if c.get('dup') in ('1', '2') else 'single'}|{c.get('code')}"   # noqa: E731
        dist, dist_all, seats_ok, spans = {}, {}, set(), {}
        for c in csvd["conf"]:
            if c.get("code") == "OK":
                seats_ok.add(c.get("seat"))
            k = key(c)
            dist_all[k] = dist_all.get(k, 0) + 1
            if num(_f(c.get("ts"))) and num(_f(c.get("te"))):
                spans.setdefault(c.get("seat"), []).append((_f(c["ts"]), _f(c["te"])))
            if c["_in"]:
                dist[k] = dist.get(k, 0) + 1
        M["confirm_dist"] = dict(sorted(dist.items()))
        M["confirm_ok_window"] = sum(v for k, v in dist.items() if k.endswith("|OK"))
        M["confirm_tps"] = M["confirm_ok_window"] / plan["window"]["len_s"]
        # 계약 밖 확정 코드는 회차 전체에서 센다(리뷰 M15-10 — 실패는 넓게: 측정-0 판정 창 · 분포 M1만 창 안)
        M["confirm_noncontract"] = {k: v for k, v in sorted(dist_all.items()) if k.split("|")[2] not in CONTRACT}
        M["confirm_5xx"] = csvd["err5"].get("confirm", 0)           # M2 = 상태 ≥ 500만(시간 초과는 confirm_timeout — 리뷰 M15-3a)
        M["confirm_timeout"] = csvd["timeout"].get("confirm", 0)
        M["api_double"] = len(seats_ok & {r.get("seat") for r in csvd["rehold_ok"]})   # 같은 좌석 확정 200 + 재선점 201(기대 0 — 모든 모드)
        M["rehold_ok"] = len(csvd["rehold_ok"])
        M["rehold_ok_dense"] = sum(1 for r in csvd["rehold_ok"] if r.get("ph") == "d")
        # FM1 겹침(리뷰 M15-6): 재선점 201(응답 끝 서버 시각)이 같은 좌석 확정의 [송신, 응답 끝] 안 — '배치가 푼 좌석을 남이 잡은 뒤 그 확정이 쓴다' 순서가 부하에서 생겼다
        M["fm1_overlap"] = sum(1 for r in csvd["rehold_ok"] if num(_f(r.get("t"))) and any(a <= _f(r["t"]) <= b for a, b in spans.get(r.get("seat"), [])))
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
    elif csvd is not None:
        other = {k: v for k, v in csvd["err5"].items() if k != "confirm"}
        if other:
            F["5xx_non_confirm"] = other   # current도 확정 밖 5xx는 기대하지 않는다(선점 · 재선점 경로 무변경)
    # ④ payment-start(리뷰 M15-4): 흐름 구성상 ④ 세션은 모두 e − 10초에 payment-start를 보내고(남은 10초 > extend-min-remaining 5초 · < 창 2분)
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
    # 경합 증거(양성 대조 — 리뷰 M15-3a · M15-3b): 회차 전체 · win 묶음 · 단독 확정만(짝 진 쪽은 N2의 정상 결과라 배치 경합과 무관)
    #   current = win 단독 확정 5xx(상태 ≥ 500 — FM1 0행 예외 갈래 · 시간 초과 제외) + 틱 실패(FM3 틱 롤백)
    #   ① · ③ = 기준 흐름(fl=b) win 단독 확정의 진 쪽 계약 코드(LOSER — 배치가 경합 창 안에서 홀드를 먼저 지웠다)
    #   ④ = ④ 경계 흐름(fl=x — e′ 기준) win 단독 확정의 진 쪽 계약 코드(기준 흐름은 연장이 창을 닫는다)
    #   ② = A′ 배치 B3가 ②의 홀드 행 잠금을 기다렸다(pgss B3 max ≥ 지연 × PESS_B3_WAIT_FRAC) — 1 | 0 · pgss 꺼짐 · 행 없음 = None(판정 불가)
    def win_single(fl):
        return [c for c in (csvd or {}).get("conf", []) if c.get("cb") == "win" and c.get("dup") == "0" and (c.get("fl") or "b") == fl]
    parts = {}
    if mode == "current":
        parts = {"win_single_5xx": sum(1 for c in win_single("b") if (c.get("st") or "").isdigit() and int(c["st"]) >= 500) if csvd is not None else None,
                 "tick_failed": app.get("tick_failed")}
    elif mode in ("conditional", "optimistic", "conditional-extend"):
        fl = "x" if mode == "conditional-extend" else "b"
        parts = {f"win_single_loser_{fl}": sum(1 for c in win_single(fl) if c.get("code") in LOSER) if csvd is not None else None}
    elif mode == "pessimistic":
        b3 = ((M.get("batch_stmt") or {}).get("classes") or {}).get("B3") or {}
        thr = plan["delay_ms"] * PESS_B3_WAIT_FRAC
        parts = {"b3_wait": (1 if b3["max_ms"] >= thr else 0) if num(b3.get("max_ms")) and plan["delay_ms"] > 0 else None}
        M["pess_b3"] = {"max_ms": b3.get("max_ms"), "calls": b3.get("calls"), "total_ms": b3.get("total_ms"), "threshold_ms": thr}
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
    # G4 캠페인 유효 — current 두 셀 유효 3회 · current race 회차마다 경합 증거 ≥ 1(win 단독 5xx + 틱 실패 — 리뷰 M15-3a · 시간 초과는 따로 기록)
    g4_parts = {"valid3": all(valid3[(c, "current")] for c in plan15.GROUPS), "positive_control": pc_ok("current")}
    g4 = all(g4_parts.values())
    res["G4"] = {"ok": g4, "parts": g4_parts, "evidence": {m: ev[m] for m in plan15.MODES},
                 "current_timeout": [s["fail"].get("timeout") for s in reps.get((RACE, "current"), []) if s["fail"].get("timeout")]}
    # FM1 겹침(리뷰 M15-6 — 설명 · 관문 아님): 모드별 race 회차의 '재선점 201이 같은 좌석 확정의 [송신, 응답 끝] 안' 건수. current(JPA 배치가 틱 롤백으로 지는 쪽이
    #   잦다)와 ②(잠금 — 재선점 NOWAIT 409)는 구조상 드물거나 0이다(초안 측정-14) — M12(API 수준 이중 결과)는 G-F 문턱으로만 쓰고 'FM1-Y를 부하가 쟀다'의 근거로 쓰지 않는다
    res["fm1_overlap"] = {m: vals(RACE, m, "fm1_overlap") for m in plan15.MODES}
    # G1 — 새 모드 상태: fail(G-F 1건이라도) | undetermined(유효 반복 부족(재측정 한도 뒤) · 양성 대조 None/0 · 2 · 3단 지표 범위 없음 — 리뷰 M15-1 · M15-14) | success
    status, why = {}, {}
    for m in plan15.NEW_MODES:
        if fails[m]:
            status[m], why[m] = "fail", "G-F"
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
        b5, bc = R(RACE, m, "confirm_5xx"), R(RACE, "current", "confirm_5xx")
        tf, tc = R(RACE, m, "tick_failed"), R(RACE, "current", "tick_failed")
        a, b, c = cur_defect and not fails[m], lower(b5, bc), lower(tf, tc)
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
    elif not cands:
        verdict = "current 유지 ①(성공한 후보 없음)"
    elif all(res["candidates"][m]["G2"]["ok"] is False for m in rank):
        verdict = "current 유지 ②(G2 — 이긴 후보 없음)"
    else:
        verdict = "current 유지 ③(G2를 넘은 후보가 모두 G3에서 걸림)"
    if verdict.startswith("교체") and any(rank.index(x) < rank.index(new_default) for x in rank):
        verdict += " · 주의: 앞 순위 후보가 관문에서 걸렸다(결과 문서에 그 관문 기록)"
    res["decision"] = {"verdict": verdict, "new_default": new_default if verdict.startswith("교체") else None, "provisional": new_default}
    res["v12"] = v12_summary(Path(root) / "v12", write=False) if (Path(root) / "v12").is_dir() else None
    res["ranges"] = {f"{c}/{m}/{k}": R(c, m, k) for m in plan15.MODES for c, k in STAGE2_METRICS + G3_METRICS}
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
    L += ["", f"G4 캠페인 유효: {res['G4']}", "", "## 범위(유효 3회 [최소, 최대])", ""]
    L += [f"- {k}: {v}" for k, v in res["ranges"].items()]
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
                if not (num(tx.get("build_ms")) and num(tx.get("probe_blocked_ms"))):
                    why.append("트랜잭션 안 값 없음")
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
