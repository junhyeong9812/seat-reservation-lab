#!/usr/bin/env python3
"""ADR-014 요약기 — 회차 판정(측정-9 사유 → status) · 지표 M1~M14 · 가설 P1~P13 · 성공(측정-7) · 고르는 규칙 1~4단 · 교체 관문 G1~G4(측정-8) · RESULT.md.
기준: docs/adr/ADR-014-hold-expiry.md '측정 전 고정 규칙'. 계획 값은 plan14.py(단일 출처)에서 읽는다.

    summarize.py --rep <회차폴더>                → rep-summary.json · 표준 출력 마지막 줄 = status(ok | invalid:<사유,…>) — run.sh finish14가 status 파일로
    summarize.py <캠페인 결과 폴더>               → result.json · RESULT.md(방식별 성공 · 고르는 규칙 · 관문 · 결론 · 가설 · 지표 범위)
    summarize.py --crn-check <캠페인 결과 폴더>   → crn-check.json · 같은 (회차, 셀)의 방식 사이 세션 속성 해시가 다수와 다른 회차의 status에 crn-mismatch를 더한다(측정-3.5 · 측정-9)
    summarize.py --early-check <캠페인 결과 폴더> → exit 1 = 회차 1 TTL 30초 두 묶음에서 A의 W = 0 또는 A의 PR-1 δ ∈ {1, 3} 부당 실패 0(측정-9 '캠페인 멈춤')
    summarize.py --sample-cost <회차폴더> …      → 시작 조건 ⑤(측정-5 머리 · 리뷰 MR-1): 방식별 sample_ms p50·p99·최대 · c_X · Δc · B 기울기
    summarize.py --r0 <ADR-009 results 뿌리>     → R₀ = 과거 S3-a20 L4 tmpfs 회차 3회 묶음별 DB CPU 창 평균 (최대 − 최소) 중 최소

판정 입력은 한 곳(Inputs — 아래)에서 읽고 검증한다: 파일이 없거나 깨졌거나 필요한 열이 빠지면 그 값은 0이 아니라 None이고, 그 입력을 쓰는 사유(INPUT_REASON)로
회차가 무효가 된다(ADR-013 교훈 — 판정 입력이 무음으로 0이 되는 문제). 캠페인 집계에서 None을 만나면 그 항목은 '판정 불가'다(0으로 채우지 않는다).
시각: k6 CSV · 측정기 PC CSV · Hikari = PC 시계 · 상태 표본 · 대기 표본 · docker stats 줄 · GC 로그 · 서버 측정기 = 서버 시계. 서버 = PC + 시계 차(clock-offset.json 앞뒤 평균).
판정 창(측정-0) = [t0, t0 + D_arr) · t0 = k6-summary setup_data.startedAt(시나리오 시각 0 — 첫 요청). 비용 셀 = 정상 구간 [t0 + 램프 60, + 360)."""
import csv
import gzip
import importlib.util
import json
import math
import statistics
import sys
from array import array
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import plan14  # noqa: E402

LIB9 = HERE.parent.parent / "ADR-009" / "scripts"


def _load9(name):
    spec = importlib.util.spec_from_file_location(f"adr009_{name}", LIB9 / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


S9 = _load9("summarize")   # gc_summary(GC 로그 요약 — ADR-010 정의 그대로)

# ---- 고정값(측정-4 · 측정-9 — 측정 뒤 바꾸지 않는다) -------------------------------------------------------------------------------
STATE_GAP_MAX_S = 3.0          # 상태 표본 간격(양끝 포함) > 3초면 무효(리뷰 MR-4)
STATE_MIN_SHARE = 0.95         # 표본 수 < 창 초 × 0.95면 무효
DBCPU_EVERY_S, DBCPU_GAP_MAX_S = 15, 30.0   # M9 결측 규칙(리뷰 MR-3)
PATH_GAP_MAX_S = 30.0          # ADR-009 path-gap(gapcheck.py — 서버 시각 줄)
CLOCK_RTT_HALF_MAX_S, CLOCK_DRIFT_MAX_S = 0.020, 0.100   # clock-offset-bad
PRE_K6_TARGET_S, PRE_K6_TOL_S = 87.0, 5.0                 # pre-k6-mismatch(ADR-009 명세 §13)
STALL_MIN_S, PROBE_SILENT_S, GC_EXPLAINS_MS = 5, 5.0, 100.0   # env-stall(리뷰 MR-6)
WAITS_GAP_MAX_S = 1.0          # env-stall ① 대기 표본 공백 > 1초면 'DB 쪽 원인 없음'을 세우지 못함
GC_PAUSE_MAX_MS = 10.0         # P12 ②(ADR-010 §9)
MEM_GROWTH_MAX = 0.10          # P12 ④(비용 셀만)
TOL_RATIO = 1.10               # ≫ 둘째 조건(측정-8)
SIMPLICITY = ["D", "B", "C"]   # 4단(측정-8 · MQ7)
MAIN_CELLS = [c for c in plan14.GROUPS if c in plan14.MAIN]
COST_CELL = "S4c4200"
TTL_GROUPS = {"t30": ["a20-t30", "a50-t30"], "t300": ["a20-t300", "a50-t300"]}
COST_JUDGE_CELL = "a50-t300"   # 측정-8 3단 · G3(측정-4 '비용 판정 셀')
INF = float("inf")

# 입력 → 결측일 때의 회차 사유(측정-9). 한 곳에서만 정한다
INPUT_REASON = {
    "plan": "plan-missing", "meta": "meta-missing", "k6_summary": "k6-summary-missing", "csv": "k6-csv-missing", "clock": "clock-offset-bad",
    "seatstate": "state-sampler-gap", "server": "db-cpu-missing", "table_before": "table-stats-missing", "table_after": "table-stats-missing",
    "table_end": "table-stats-missing", "act_before": "app-metric-missing", "act_after": "app-metric-missing", "act_end": "app-metric-missing",
    "drift_before": "drift-snap-failed", "drift_after": "drift-snap-failed", "drift_end": "drift-snap-failed", "drift_db": "drift-db-failed",
    "consistency": "end-check-failed", "app_config": "cond-config-missing", "seed_check": "seed-mismatch", "gc": "gc-log-missing",
    "probe_check": "probe-missing", "waits": "waits-gap", "hikari": "hikari-missing", "limit_key": "limit-key-missing", "pre_k6": "pre-k6-mismatch",
    "logs": "container-log-missing", "probe_server": "probe-missing",
}
# 정합 문턱(측정-7 정합 위반 · 상태 기대값 문턱 · expect-mismatch · P6 아래 끝)에 쓰는 판정기 열 — 하나라도 없거나 숫자가 아니면 그 회차는 end-check-failed(0으로 채우지 않는다 — 코드 리뷰 CR14-8)
CONS_COLS = ("v_duplicate_confirmed_seats", "double_sold_seats", "multi_active_seats", "v_counter_mismatch", "v_over_limit_users",
             "held_no_active", "active_not_held", "expired_hold_rows", "v_held_without_hold", "confirmed")


def load(p):
    try:
        return json.loads(Path(p).read_text())
    except (OSError, ValueError):
        return None


def num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)


def ts(s):
    return datetime.fromisoformat(str(s).replace("Z", "+00:00")).timestamp()


def p99(xs):
    xs = sorted(xs)
    return xs[int(len(xs) * 0.99)] if xs else None


def jsonl(path):
    """jsonl 줄 → dict 목록 · 파일 없음 = None(빈 목록과 다르다)."""
    p = Path(path)
    if not p.exists():
        return None
    out = []
    for line in p.read_text(errors="replace").splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
    return out


# ---- 판정 입력(단일 검증 지점) -----------------------------------------------------------------------------------------------------
class Inputs:
    """회차 폴더의 판정 입력을 읽고 검증한다. get(name)은 값 또는 None — None이면 missing에 (이름, 사유)가 남는다(무음 0 금지)."""

    def __init__(self, d):
        self.d = Path(d)
        self.missing = {}
        self._cache = {}

    def miss(self, name, why):
        self.missing.setdefault(name, why)
        return None

    def json(self, name, fname, need=(), nums=()):
        """need = 있어야 하는 열(점 경로) · nums = 있어야 하고 숫자(유한 · bool 아님)여야 하는 열 — 하나라도 어기면 None(코드 리뷰 CR14-8)."""
        if name in self._cache:
            return self._cache[name]
        v = load(self.d / fname)
        if not isinstance(v, (dict, list)):
            v = self.miss(name, f"{fname} 없음·깨짐")
        else:
            for k in tuple(need) + tuple(nums):
                cur = v
                for part in k.split("."):
                    cur = cur.get(part) if isinstance(cur, dict) else None
                if cur is None:
                    v = self.miss(name, f"{fname} {k} 없음")
                    break
                if k in nums and not num(cur):
                    v = self.miss(name, f"{fname} {k} 숫자 아님({cur!r})")
                    break
        self._cache[name] = v
        return v

    def reasons(self):
        return sorted({INPUT_REASON.get(n, f"{n}-missing") for n in self.missing})


def clock(inp):
    c = inp.json("clock", "clock-offset.json")
    if not c:
        return None
    b, a = c.get("before"), c.get("after")
    if not (isinstance(b, dict) and isinstance(a, dict) and num(b.get("offset_s")) and num(a.get("offset_s"))):
        return inp.miss("clock", "앞 또는 뒤 시계 차 표본 없음")
    # 왕복(rtt_s)도 필수 숫자 — 없거나 음수면 왕복/2 문턱을 잴 수 없다(0으로 채우면 통과한다 — 코드 리뷰 CR14-9)
    if not all(num(x.get("rtt_s")) and x["rtt_s"] >= 0 for x in (b, a)):
        return inp.miss("clock", f"앞 또는 뒤 시계 차 표본의 rtt_s 없음·비정상(앞 {b.get('rtt_s')!r} · 뒤 {a.get('rtt_s')!r})")
    return {"off": (b["offset_s"] + a["offset_s"]) / 2, "rtt_half_max": max(b["rtt_s"], a["rtt_s"]) / 2,
            "drift": abs(a["offset_s"] - b["offset_s"]), "before": b, "after": a}


# ---- k6 CSV 한 번 읽기 ------------------------------------------------------------------------------------------------------------
def parse_tags(s):
    return dict(kv.split("=", 1) for kv in s.split("&") if "=" in kv) if s else {}


PROBE_SCEN = {"pr1", "pr2", "pr3"}


def read_csv(path, w0, w1, kind):
    """k6-requests.csv.gz 한 번 읽기. 창 = PC [w0, w1). 행 시각 = 정수 초(완료 시각 — ADR-009 CSV 그대로).
    반환 dict: lat(이름별 창 안 지연 — 탐침 제외) · per_sec(창 안 초별 응답 수 — 모든 흐름) · err(창 안 5xx·코드 0 — 이름별) · err_all(창 밖 포함) · hold409 · confirms ·
    dropped(시나리오별 창 안) · crn(흐름별 n·h1·h2) · rej(원인별 합) · hold_ok · confirm_rec · revisit_rec · probe · soldout · sec_hold(초별 선점 지연 — 1초 p99)."""
    out = {"lat": {}, "per_sec": {}, "err": {}, "err_all": {}, "hold409": 0, "hold409_by_scen": {}, "confirm_reqs": 0, "confirm_200": 0,
           "dropped": {}, "dropped_all": 0, "crn": {}, "rej": {}, "hold_ok": [], "confirm_rec": [], "revisit_rec": [], "probe": [], "soldout": 0,
           "sec_hold": {}, "hold_req_n": 0}
    with gzip.open(path, "rt", newline="") as f:
        head = next(csv.reader([f.readline()]))
        ix = {k: head.index(k) for k in ("metric_name", "timestamp", "metric_value", "name", "scenario", "status", "extra_tags")}
        i_m, i_t, i_v, i_n, i_sc, i_st, i_x = (ix[k] for k in ("metric_name", "timestamp", "metric_value", "name", "scenario", "status", "extra_tags"))
        for line in f:
            m = line[: line.find(",")]
            if m not in ("http_req_duration", "dropped_iterations", "crn_n", "crn_h1", "crn_h2", "rej", "hold_ok", "confirm_rec", "revisit_rec", "probe", "map_soldout"):
                continue
            r = next(csv.reader([line])) if '"' in line else line.rstrip("\n").split(",")
            t = int(r[i_t])
            inwin = w0 <= t < w1 or (int(w0) == t)   # 행 시각은 내림 초 — t0가 든 초도 창 안
            sc = r[i_sc]
            if m == "http_req_duration":
                name, st, v = r[i_n], r[i_st], float(r[i_v])
                tags = parse_tags(r[i_x])
                bad = st == "0" or (st.isdigit() and int(st) >= 500)
                if bad:
                    out["err_all"][name] = out["err_all"].get(name, 0) + 1
                if name == "hold":
                    out["hold_req_n"] += 1
                    if st == "409":
                        out["hold409"] += 1
                        out["hold409_by_scen"][sc] = out["hold409_by_scen"].get(sc, 0) + 1
                if name == "confirm":
                    out["confirm_reqs"] += 1
                    out["confirm_200"] += st == "200"
                if not inwin:
                    continue
                out["per_sec"][t] = out["per_sec"].get(t, 0) + 1
                if bad:
                    out["err"][name] = out["err"].get(name, 0) + 1
                if sc in PROBE_SCEN:
                    continue
                if kind == "cost" and tags.get("stage") == "ramp":
                    continue
                key = f"{name}:{st}"
                out["lat"].setdefault(key, array("d")).append(v)
                if name == "hold" and not bad:
                    out["sec_hold"].setdefault(t, array("d")).append(v)
            elif m == "dropped_iterations":
                out["dropped_all"] += 1
                if inwin:
                    out["dropped"][sc] = out["dropped"].get(sc, 0) + 1
            elif m in ("crn_n", "crn_h1", "crn_h2"):
                c = out["crn"].setdefault(sc, {"n": 0, "h1": 0, "h2": 0})
                c[m[4:]] += int(float(r[i_v]))
            elif m == "rej":
                cause = parse_tags(r[i_x]).get("cause", "?")
                out["rej"][cause] = out["rej"].get(cause, 0) + int(float(r[i_v]))
            elif m == "map_soldout":
                out["soldout"] += 1
            else:
                tags = parse_tags(r[i_x])
                tags["_scen"], tags["_t"] = sc, t
                out[{"hold_ok": "hold_ok", "confirm_rec": "confirm_rec", "revisit_rec": "revisit_rec", "probe": "probe"}[m]].append(tags)
    return out


# ---- 탐침(측정-3.2 · M3 · M5 ④ · M7) -----------------------------------------------------------------------------------------------
def classify_409(method, cause):
    """판정 409 대 배치 잠금 409(리뷰 MR-5): C·A의 nowait = 배치 잠금(좌석 행을 쓰는 배치가 쥔 잠금 — A 배치도 좌석 행을 UPDATE한다: 해석 메모) ·
    B·D의 nowait · 헤더 없음 · 모르는 값 = 판정 409로 센다 + 분류 불가."""
    if cause == "nowait" and method in ("A", "C"):
        return "batch", False
    if cause == "rule":
        return "rule", False
    return "rule", True


def dkey(x):
    """δ·δc 표기 맞춤 — k6(JS String(-1) = '-1')와 계획(Python str(-1.0) = '-1.0')을 같은 열쇠로."""
    return str(float(x))


def probe_metrics(method, plan, rows, off_s):
    pp = plan["probes"]
    planned_cell = {pr: {dkey(k): v for k, v in cells.items()} for pr, cells in pp["per_cell"].items()}
    tol, drop_max = pp["tol_ms"], pp["drop_max"]
    by = {}
    for r in rows:
        by.setdefault((r["pr"], int(r["pid"])), {})[r["step"]] = r
    res = {"started": {}, "planned": pp["count"], "per_cell": {}, "setup_errors": 0, "setup_rejected": [], "judged_missing": [],
           "timing_dropped": 0, "timing_bad_cells": [], "unknown_cause": [], "errors": 0, "schedule_closed": 0,
           "m3": {"n201": 0, "n409": 0, "rule": 0, "batch": 0, "by_code": {}, "by_delta": {}},
           "pr3": {"executed": 0, "planned": pp["count"]["pr3"], "viol_both": 0, "late_200": 0, "early_not200": 0, "late_codes": {}, "early_n": 0, "late_n": 0}}
    for (pr, pid), steps in by.items():
        res["started"][pr] = res["started"].get(pr, 0) + 1
        d = dkey(next(iter(steps.values()))["d"])
        cell = res["per_cell"].setdefault(f"{pr}:{d}", {"judged": 0, "dropped": 0, "planned": planned_cell[pr].get(d, 0)})
        setup = ("h0",) if pr == "pr1" else ("h0a", "h0b") if pr == "pr2" else ("u0",)
        bad_setup = None
        for s in setup:
            x = steps.get(s)
            if x is None:
                break
            if x.get("code") == "SCHEDULE_CLOSED":
                res["schedule_closed"] += 1
            if x["st"] != "201":
                bad_setup = x
                break
        if bad_setup is not None:
            if bad_setup["st"] == "0" or int(bad_setup["st"]) >= 500:
                res["setup_errors"] += 1      # 방식 — M11(측정-9 '탐침 응답 5xx·코드 0')
            else:
                # 5xx가 아닌 첫 선점 실패(409 등)도 판정 요청이 나가지 않은 탐침이다 → probe-incomplete 계산에 넣는다 · 원인 이름표는 그대로(코드 리뷰 CR14-10)
                res["setup_rejected"].append(f"{pr}#{pid}:{bad_setup['code']}")
                res["judged_missing"].append(f"{pr}#{pid}:첫 선점 {bad_setup['st']}")
            continue
        judged = ["h1"] if pr in ("pr1", "pr2") else ["v", "c"]
        if any(j not in steps for j in judged):
            res["judged_missing"].append(f"{pr}#{pid}")
            continue
        cell["judged"] += 1
        eps = [abs(int(steps[j]["sent"]) + off_s * 1000 - float(steps[j]["plan"])) for j in judged]
        if max(eps) > tol[pr]:
            cell["dropped"] += 1
            res["timing_dropped"] += 1
            continue
        if any(steps[j]["st"] == "0" or int(steps[j]["st"]) >= 500 for j in judged):
            res["errors"] += 1
            continue
        if pr in ("pr1", "pr2"):
            x = steps["h1"]
            dd = res["m3"]["by_delta"].setdefault(f"{pr}:{d}", {"n201": 0, "n409": 0, "rule": 0, "batch": 0})
            if x["st"] == "201":
                res["m3"]["n201"] += 1
                dd["n201"] += 1
            elif x["st"] == "409":
                kind, unknown = classify_409(method, x.get("rej", ""))
                res["m3"]["n409"] += 1
                dd["n409"] += 1
                res["m3"][kind] += 1
                dd[kind] += 1
                res["m3"]["by_code"][x["code"]] = res["m3"]["by_code"].get(x["code"], 0) + 1
                if unknown:
                    res["unknown_cause"].append(f"{pr}#{pid}:{x.get('rej') or '헤더 없음'}")
            else:
                res["setup_rejected"].append(f"{pr}#{pid}:h1:{x['code']}")
        else:
            res["pr3"]["executed"] += 1
            c, v = steps["c"], steps["v"]
            dc = float(d)
            if c["st"] == "200" and v["st"] == "201":
                res["pr3"]["viol_both"] += 1
            if dc > 0:
                res["pr3"]["late_n"] += 1
                res["pr3"]["late_200"] += c["st"] == "200"
                res["pr3"]["late_codes"][c["code"]] = res["pr3"]["late_codes"].get(c["code"], 0) + 1
            else:
                res["pr3"]["early_n"] += 1
                res["pr3"]["early_not200"] += c["st"] != "200"
    for k, cell in res["per_cell"].items():
        if cell["judged"] and cell["dropped"] / cell["judged"] > drop_max:
            res["timing_bad_cells"].append(f"{k} {cell['dropped']}/{cell['judged']}")
    inc = []
    for pr, n in pp["count"].items():
        if res["started"].get(pr, 0) != n:
            inc.append(f"{pr} 시작 {res['started'].get(pr, 0)} ≠ 계획 {n}")
    if res["judged_missing"]:
        inc.append(f"판정 요청 안 나감 {len(res['judged_missing'])}")
    for k, cell in res["per_cell"].items():
        if cell["judged"] - cell["dropped"] < cell["planned"] / 2:
            inc.append(f"{k} 판정 탐침 {cell['judged'] - cell['dropped']} < 계획 {cell['planned']}의 절반")
    res["incomplete"] = inc
    m = res["m3"]
    den = m["n201"] + m["n409"]
    m["rate"] = m["n409"] / den if den else None
    m["rate_rule"] = m["rule"] / den if den else None
    m["rate_batch"] = m["batch"] / den if den else None
    m["den"] = den
    m["unfair_d13"] = sum(v["n409"] for k, v in m["by_delta"].items() if k.split(":")[1] in ("1.0", "3.0"))
    return res


# ---- 상태 표본(M1 · M2 · M4 · M5 ② · M12) ---------------------------------------------------------------------------------------
def state_metrics(rows, w0s, w1s, ttl):
    """rows = timeline-seatstate.jsonl(서버 시각). 창 = 서버 [w0s, w1s)."""
    main = [r for r in rows if r.get("kind") != "expired" and "reserved" in r]
    exp_rows = [r for r in rows if "expired_rows" in r]
    for r in main + exp_rows:
        r["_t"] = ts(r["t"])
    win = sorted((r for r in main if w0s <= r["_t"] < w1s), key=lambda r: r["_t"])
    out = {"n": len(win), "window_s": w1s - w0s}
    edges = [w0s] + [r["_t"] for r in win] + [w1s]
    out["max_gap_s"] = max(b - a for a, b in zip(edges, edges[1:]))
    out["gap_bad"] = out["max_gap_s"] > STATE_GAP_MAX_S or len(win) < (w1s - w0s) * STATE_MIN_SHARE
    if not win:
        return out
    # M1 W — 계단 함수(앞끝은 첫 표본 값 · 뒤끝은 마지막 표본 값 — 리뷰 MR-4)
    W = win[0]["held_no_active"] * (win[0]["_t"] - w0s)
    for a, b in zip(win, win[1:]):
        W += a["held_no_active"] * (b["_t"] - a["_t"])
    W += win[-1]["held_no_active"] * (w1s - win[-1]["_t"])
    out["W"] = W
    for k, thr in plan14.T_X.items():
        hit = next((r["_t"] for r in win if r["reserved"] >= thr), None)
        out[k] = hit - w0s if hit is not None else INF
    out["T95_T90"] = out["T95"] - out["T90"] if math.isfinite(out["T95"]) and math.isfinite(out["T90"]) else None
    for col in ("active_not_held", "multi_active", "active_on_reserved", "held_no_active"):
        out[f"{col}_samples"] = sum(1 for r in win if r[col] > 0)
        out[f"{col}_max"] = max(r[col] for r in win)
    ew = sorted((r for r in exp_rows if r["_t"] < w1s), key=lambda r: r["_t"])
    out["expired_end_window"] = ew[-1]["expired_rows"] if ew else None
    ea = sorted(exp_rows, key=lambda r: r["_t"])
    out["expired_last"] = ea[-1]["expired_rows"] if ea else None
    out["expired_last_t_rel"] = ea[-1]["_t"] - w0s if ea else None
    sm = [r["sample_ms"] for r in win if num(r.get("sample_ms"))]
    out["sample_ms"] = {"n": len(sm), "p50": statistics.median(sm) if sm else None, "p99": p99(sm), "max": max(sm) if sm else None,
                        "sum": sum(sm)}
    out["c_pct"] = sum(sm) / ((w1s - w0s) * 1000) * 100 if sm else None   # 표본 CPU 몫(측정-5 머리 — 100 = 코어 1개)
    # B 기울기(측정-5 머리 — sample_ms 대 expired_rows)
    pts = [(r["expired_rows"], r["sample_ms"]) for r in win if num(r.get("sample_ms")) and num(r.get("expired_rows"))]
    if len(pts) >= 3:
        mx, my = statistics.mean(p[0] for p in pts), statistics.mean(p[1] for p in pts)
        sxx = sum((p[0] - mx) ** 2 for p in pts)
        out["slope_ms_per_row"] = sum((p[0] - mx) * (p[1] - my) for p in pts) / sxx if sxx else None
    return out


# ---- docker stats(M9 · P12 ④) -------------------------------------------------------------------------------------------------
def server_rows(path, off):
    """timeline-server.jsonl → [(PC 시각, 이름, CPU%, MemMiB)] — 컨테이너 줄의 시각 = 바로 앞 서버 시각 줄({"t"…}). 서버 시각 줄 목록도 함께."""
    rows, tlines, cur = [], [], None
    p = Path(path)
    if not p.exists():
        return None, None
    for line in p.read_text(errors="replace").splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            d = json.loads(line)
        except ValueError:
            continue
        if "t" in d and "load" in d:
            cur = ts(d["t"]) - off
            tlines.append(cur)
            continue
        if cur is None or "Name" not in d:
            continue
        try:
            cpu = float(str(d.get("CPUPerc", "")).rstrip("%"))
        except ValueError:
            continue
        mem = None
        mu = str(d.get("MemUsage", "")).split("/")[0].strip()
        for suf, mul in (("GiB", 1024), ("MiB", 1), ("KiB", 1 / 1024)):
            if mu.endswith(suf):
                try:
                    mem = float(mu[: -len(suf)]) * mul
                except ValueError:
                    pass
        rows.append((cur, d["Name"], cpu, mem))
    return rows, tlines


def gaps_ok(times, w0, w1, gap_max):
    edges = [w0] + sorted(times) + [w1]
    return max(b - a for a, b in zip(edges, edges[1:])), edges


def db_cpu(rows, w0, w1):
    db = [(t, c) for t, n, c, _ in rows if n == "seatlab-db-1" and w0 <= t < w1]
    need = math.ceil((w1 - w0) / DBCPU_EVERY_S)
    gap, _ = gaps_ok([t for t, _ in db], w0, w1, DBCPU_GAP_MAX_S)
    return {"n": len(db), "need": need, "max_gap_s": gap, "mean": statistics.mean(c for _, c in db) if db else None,
            "missing": len(db) < need or gap > DBCPU_GAP_MAX_S}


def mem_growth(rows, w0, w1):
    app = [(t, m) for t, n, _, m in rows if n == "seatlab-app-1" and m is not None and w0 <= t < w1]
    first = [m for t, m in app if t < w0 + 60]
    last = [m for t, m in app if t >= w1 - 60]
    if not first or not last:
        return None
    return statistics.mean(last) / statistics.mean(first) - 1


# ---- 정지 덩어리 · env-stall(측정-9 · 리뷰 MR-6) --------------------------------------------------------------------------------------
def stall_chunks(per_sec, w0, w1):
    secs = [s for s in range(math.ceil(w0), math.floor(w1)) if per_sec.get(s, 0) == 0]
    chunks, cur = [], None
    for s in secs:
        if cur and s == cur[1]:
            cur[1] = s + 1
        else:
            cur = [s, s + 1]
            chunks.append(cur)
    return secs, chunks


def classify_chunk(a, b, off, probe_srv, gc_long, waits, hikari, hikari_max):
    """정지 덩어리 [a, b)(PC 초) → ("env" | "method", 근거). env = 서버 측정기 5초 넘게 무응답 ∧ GC 정지 < 100ms ∧ DB 쪽 원인 없음(①②③ 모두)."""
    why = {}
    pt = sorted(t for t, _, _ in probe_srv) if probe_srv is not None else None
    if pt is None:
        why["probe"] = "서버 측정기 없음"
        return "method", why
    prev = [p for p in pt if p <= a][-1:] or [a - 3600]       # 덩어리 앞 마지막 응답(없으면 아주 먼 과거 — 공백이 크다)
    nxt = [p for p in pt if p >= b][:1] or [b + 3600]
    pts = prev + [p for p in pt if a < p < b] + nxt
    gap = max(y - x for x, y in zip(pts, pts[1:]))
    why["probe_gap_s"] = round(gap, 3)
    if gap <= PROBE_SILENT_S:
        return "method", why                                   # 앱은 살아 있었다(DB 경로 정지) — 방식
    gcm = max((g["ms"] for g in gc_long if a - 1 <= g["t"] - off <= b + 1), default=0.0)
    why["gc_ms"] = gcm
    if gcm >= GC_EXPLAINS_MS:
        return "method", why                                   # GC가 설명한다
    ws = sorted((ts(w["t"]) - off, w) for w in waits if a <= ts(w["t"]) - off < b) if waits is not None else None
    if not ws:
        why["db"] = "대기 표본 없음"
        return "method", why
    gap_w, _ = gaps_ok([t for t, _ in ws], a, b, WAITS_GAP_MAX_S)
    lock = sum(n for _, w in ws for k, n in (w.get("by_src") or {}).items() if k.startswith("app|Lock:"))
    why.update(waits_gap_s=gap_w, app_lock=lock)
    if gap_w > WAITS_GAP_MAX_S or lock > 0:
        return "method", why
    hk = sorted(hikari, key=lambda h: h["_t"]) if hikari else []
    before = [h for h in hk if h["_t"] < a][-1:]
    after = [h for h in hk if h["_t"] >= b][:1]
    inside = [h for h in hk if a <= h["_t"] < b]
    if not before or not after or not num(hikari_max):
        why["hikari"] = "앞·뒤 표본 또는 최대 없음"
        return "method", why
    sel = before + inside + after
    why.update(hikari_pending=max(h["pending"] for h in sel), hikari_active=max(h["active"] for h in sel))
    if why["hikari_pending"] > 0 or why["hikari_active"] >= hikari_max:
        return "method", why
    return "env", why


# ---- 주 흐름 재구성(M5 ③ · N_exp · 재선점 기회 · 재방문 설명) ------------------------------------------------------------------------
def flow_metrics(csvd, plan, off, w0s, w1s):
    ttl = plan["ttl_s"]
    holds = []
    for r in csvd["hold_ok"]:
        try:
            e = ts(r["exp"])
        except (KeyError, ValueError):
            continue
        holds.append({"seat": r["seat"], "exp": e, "held": e - ttl, "u": r.get("u"), "kind": r.get("kind"), "fate": r.get("fate")})
    conf200 = {}
    for c in csvd["confirm_rec"]:
        if c.get("st") == "200":
            done = (int(c["sent"]) + int(c.get("rt") or 0)) / 1000 + off
            conf200[(c["seat"], c["exp"])] = done
    per_seat = {}
    for h in holds:
        per_seat.setdefault(h["seat"], []).append(h)
    dup = after_conf = rehold_other = 0
    conf_done = {}
    for (seat, exp), done in conf200.items():
        conf_done.setdefault(seat, []).append(done)
    for seat, hs in per_seat.items():
        hs.sort(key=lambda h: h["held"])
        maxexp = None
        for i, h in enumerate(hs):
            if maxexp is not None and h["held"] < maxexp - 1e-6:
                dup += 1                                            # 활성 구간 겹침(summarize.py:383과 같은 가름)
            if i > 0:
                prev = hs[i - 1]
                if prev["u"] != h["u"] and h["held"] >= prev["exp"]:
                    rehold_other += 1                               # 남의 (만료) 홀드 뒤 재선점 — 기회 수(기록)
            maxexp = h["exp"] if maxexp is None else max(maxexp, h["exp"])
        for done in conf_done.get(seat, []):
            after_conf += sum(1 for h in hs if h["held"] > done + 0.1)   # 확정 200 뒤 그 좌석 201(M5 ③)
    exp_set = {(h["seat"], h["exp"]) for h in holds}
    conf_keys = set()
    for c in csvd["confirm_rec"]:
        if c.get("st") == "200":
            try:
                conf_keys.add((c["seat"], ts(c["exp"])))
            except (KeyError, ValueError):
                pass
    n_exp = sum(1 for (s, e) in exp_set if w0s <= e < w1s and (s, e) not in conf_keys)
    abandoned = sum(1 for h in holds if h["fate"] == "abandon")
    rv = {"n": 0, "n201": 0, "n409": 0, "legit": 0, "unfair": 0, "other": 0}
    for r in csvd["revisit_rec"]:
        rv["n"] += 1
        if r["st"] == "201":
            rv["n201"] += 1
        elif r["st"] == "409":
            rv["n409"] += 1
            try:
                e0, sent = ts(r["e"]), int(r["sent"]) / 1000 + off
            except (KeyError, ValueError):
                continue
            legit = any(h["u"] != r.get("u") and h["held"] <= sent and h["exp"] > e0 for h in per_seat.get(r["seat"], []))
            rv["legit" if legit else "unfair"] += 1
        else:
            rv["other"] += 1
    return {"holds": len(holds), "duplicate": dup, "after_confirm_201": after_conf, "rehold_other": rehold_other, "n_exp": n_exp,
            "abandoned": abandoned, "revisit": rv, "confirm_200_k6": csvd["confirm_200"]}


# ---- 방식별 상태 기대값(§설계-8.3 · 측정-7 문턱 · M12 expect-mismatch) -------------------------------------------------------------------
# 기대 0인 칸 = 성공 문턱(측정-7) · 0이 아닌 칸 = expect-mismatch 이름표(판정 자료 — 한 칸이 두 규칙에 함께 들지 않는다)
ZERO_ROWS = {   # 행 → 기대 0인 방식
    5: {"D"},            # 만료됐는데 HELD 저장(D — 옛 데이터 없음)
    6: {"A", "D"},       # HELD인데 홀드 행 없음(v_held_without_hold)
    7: {"A", "B", "C"},  # 활성 홀드가 있는데 HELD 아님(C는 P5 정합 위반으로만)
    8: {"A", "C", "D"},  # 수렴 뒤 남은 만료 홀드 행
}


def state_thresholds(method, cons, st):
    """기대 0 칸의 위반 목록 [(행, 근거)]. cons = consistency.json(CONS_COLS 검증을 통과한 것 — 열은 모두 숫자) · st = state_metrics(None이면 표본 열 없이 끝 값만)."""
    v = []
    if method in ZERO_ROWS[5]:
        if cons["held_no_active"] > 0 or (st and st.get("held_no_active_samples", 0) > 0):
            v.append((5, f"held_no_active 끝 {cons['held_no_active']} · 표본 {st and st.get('held_no_active_samples')}"))
    if method in ZERO_ROWS[6] and cons["v_held_without_hold"] > 0:
        v.append((6, f"v_held_without_hold {cons['v_held_without_hold']}"))
    if method in ZERO_ROWS[7] and method != "C":
        if cons["active_not_held"] > 0 or (st and st.get("active_not_held_samples", 0) > 0):
            v.append((7, f"active_not_held 끝 {cons['active_not_held']} · 표본 {st and st.get('active_not_held_samples')}"))
    if method in ZERO_ROWS[8] and cons["expired_hold_rows"] > 0:
        v.append((8, f"expired_hold_rows(수렴 뒤) {cons['expired_hold_rows']}"))
    return v


def expect_mismatch(method, cons, st, act_d):
    """기대값이 0이 아닌 칸(§설계-8.3) — 해석 메모: 수렴 뒤(TTL + 22초 — 마지막 홀드 만료 뒤 배치 2틱 이상) 값으로 본다."""
    out = []
    if method == "A" and cons["held_no_active"] > 0:
        out.append(f"5행 A: 수렴 뒤 held_no_active {cons['held_no_active']} > 0(기대 '마지막 틱 뒤 만료분만' = 수렴 뒤 0)")
    if method == "B":
        if not cons["expired_hold_rows"] > 0:
            out.append("8행 B: 수렴 뒤 남은 만료 홀드 0(기대 > 0)")
        if not cons["held_no_active"] > 0:
            out.append("5행 B: held_no_active 0(기대 '늘어난다')")
    if method == "C":
        sk = (act_d.get("skip_c2") or 0) + (act_d.get("skip_h4b") or 0)
        if cons["held_no_active"] > sk:
            out.append(f"5행 C: held_no_active {cons['held_no_active']} > 건너뜀 {sk}(기대 '배치 주기분 + 건너뜀분' — 수렴 뒤 건너뜀분만)")
        if cons["v_held_without_hold"] > (act_d.get("skip_h4b") or 0):
            out.append(f"6행 C: v_held_without_hold {cons['v_held_without_hold']} > H4b 건너뜀 {act_d.get('skip_h4b')}")
    return out


def _d(a, b, *path):
    """스냅숏 차분 b − a(경로) · 어느 쪽이든 None이면 None."""
    x, y = a, b
    for p in path:
        x = x.get(p) if isinstance(x, dict) else None
        y = y.get(p) if isinstance(y, dict) else None
    return y - x if num(x) and num(y) else None


# ---- 회차 판정(--rep) -------------------------------------------------------------------------------------------------------------
def rep_summary(d):
    d = Path(d)
    inp = Inputs(d)
    reasons, flags = set(), []
    plan = inp.json("plan", "plan.json", need=("method", "cell", "kind"))
    meta = inp.json("meta", "meta.json")
    if plan is None:
        return {"status": "invalid:plan-missing", "reasons": ["plan-missing"]}
    method, cell, kind = plan["method"], plan["cell"], plan["kind"]
    for line in (d / "harness-reasons.txt").read_text().splitlines() if (d / "harness-reasons.txt").exists() else []:
        if line.strip():
            reasons.add(line.strip())
    ks = inp.json("k6_summary", "k6-summary.json", need=("setup_data.startedAt",))
    clk = clock(inp)
    if clk and (clk["rtt_half_max"] > CLOCK_RTT_HALF_MAX_S or clk["drift"] > CLOCK_DRIFT_MAX_S):
        reasons.add("clock-offset-bad")
    off = clk["off"] if clk else None
    t0 = ks["setup_data"]["startedAt"] / 1000 if ks else None
    win = plan["window"]
    w0 = t0 + win["from_s"] if t0 is not None else None
    w1 = w0 + win["len_s"] if w0 is not None else None
    M = {"method": method, "cell": cell, "rep": plan["rep"], "kind": kind, "t0_pc": t0, "window_pc": [w0, w1], "off_s": off}
    V = {}   # 방식 위반(유효 회차의 실패 자료 — 측정-7)
    # 시작 대기(pre-k6-mismatch)
    pk = inp.json("pre_k6", "pre-k6.json", need=("seed_done_epoch",))
    if pk and t0 is not None:
        M["seed_to_first_request_s"] = t0 - pk["seed_done_epoch"]
        if abs(M["seed_to_first_request_s"] - PRE_K6_TARGET_S) > PRE_K6_TOL_S:
            reasons.add("pre-k6-mismatch")
    # 설정 · 시드 · 키
    cfg = inp.json("app_config", "app-config.json", need=("configprops",))
    if cfg is not None:
        cc = plan14.config_check(method, cell, cfg)
        if cc is None:
            reasons.add("cond-config-missing")
        else:
            reasons.update(cc[0])
            M["config_detail"] = cc[1]
    lk = inp.json("limit_key", "limit-key.json", need=("env",))
    if lk and (lk.get("present") or (lk.get("drift") or 0) > 0 or (lk.get("drift_cmd") or 0) > 0):
        reasons.add("limit-key-present")   # 카운터·매수 키를 넣지 않는다(측정-1 — 업무 앱 기본값)
    sc = inp.json("seed_check", "seed-check.json", need=("seats",))
    if sc:
        if not (sc["schedules"] == sc["want_schedules"] and sc["seats"] == sc["want_seats"] == sc["available"]
                and sc["holds"] == 0 and sc["reservations"] == 0 and sc["quota_rows"] == 0):
            reasons.add("seed-mismatch")
        if sc.get("seat_map_index") != 1 or sc.get("flyway_v11") != 1:
            reasons.add("seat-map-index-missing")
    # 로그 · GC
    ls = (d / "logs-status.txt").read_text().splitlines() if (d / "logs-status.txt").exists() else None
    if ls is None or any(("pipe=0/0" not in x) or x.rstrip().endswith("bytes=0") for x in ls if x.strip()) or len([x for x in ls if x.strip()]) < 2:
        inp.miss("logs", "logs-status.txt 파이프 실패·빈 로그")
    gc = None
    if t0 is not None and off is not None and (d / "gc.log.gz").exists():
        gc = S9.gc_summary(d / "gc.log.gz", (w0 + off, w1 + off), w0 + off)
    if not gc or not gc.get("collector"):
        inp.miss("gc", "GC 로그 없음·빈 로그·수집기 줄 없음")
        gc = None
    pc = inp.json("probe_check", "probe-check.json", need=("ok",))
    if pc is not None and not pc["ok"]:
        reasons.add("probe-missing")
    # k6 CSV
    csvd = None
    if (d / "k6-requests.csv.gz").exists() and w0 is not None:
        try:
            csvd = read_csv(d / "k6-requests.csv.gz", w0, w1, kind)
        except (OSError, EOFError, ValueError, StopIteration, IndexError) as e:
            inp.miss("csv", f"CSV 읽기 실패 {type(e).__name__}")
    else:
        inp.miss("csv", "k6-requests.csv.gz 없음 또는 창 모름")
    kw = load(d / "k6-window.json") or {}
    if kw.get("exit") not in (0, None):
        reasons.add(f"k6-exit-{kw.get('exit')}")
    # 서버 표본(M9 · path-gap · P12 ④)
    srows, tlines = server_rows(d / "timeline-server.jsonl", off or 0) if off is not None else (None, None)
    if srows is None:
        inp.miss("server", "timeline-server.jsonl 없음")
    elif w0 is not None:
        M["db_cpu"] = db_cpu(srows, w0, w1)
        if M["db_cpu"]["missing"]:
            reasons.add("db-cpu-missing")
        g, _ = gaps_ok([t for t in tlines if w0 <= t < w1], w0, w1, PATH_GAP_MAX_S)
        if g > PATH_GAP_MAX_S:
            reasons.add("path-gap")
        if kind == "cost":
            M["mem_growth"] = mem_growth(srows, w0, w1)
            if M["mem_growth"] is None:
                inp.miss("server", "앱 메모리 첫·마지막 1분 표본 없음")
    # 끝 판정기 · 기록
    cons = inp.json("consistency", "consistency.json", nums=CONS_COLS)   # 정합 문턱 열은 모두 있어야 하고 숫자여야 한다(코드 리뷰 CR14-8 — 무음 0 금지)
    ddb = inp.json("drift_db", "drift-db.json", need=("drift_log_total",))
    if ddb is not None and len(ddb.get("drift_log") or []) < ddb["drift_log_total"]:
        reasons.add("drift-db-incomplete")
    dsn = {k: inp.json(f"drift_{k}", f"drift-{f}.json", need=("tick",)) for k, f in (("before", "before"), ("after", "after-k6"), ("end", "end"))}
    if dsn["before"] and dsn["end"] and (_d(dsn["before"], dsn["end"], "drift_record_failed") or 0) > 0:
        reasons.add("drift-record-failed")
    if dsn["before"] and dsn["end"] and (_d(dsn["before"], dsn["end"], "cleanup", "errors") or 0) > 0:
        reasons.add("cleanup-errors")
    act = {k: inp.json(f"act_{k}", f"act14-{f}.json", need=("rejected.rule", "tick.ok")) for k, f in (("before", "before"), ("after", "after-k6"), ("end", "end"))}
    for k, a in act.items():
        if a and a.get("failed"):
            inp.miss(f"act_{k}", f"지표 조회 실패 {a['failed']}")
    hik = jsonl(d / "timeline-hikari.jsonl")
    for h in hik or []:
        h["_t"] = h["t_ms"] / 1000 if num(h.get("t_ms")) else ts(h["t"])
    waits = jsonl(d / "timeline-waits.jsonl")
    if waits is None:
        inp.miss("waits", "timeline-waits.jsonl 없음")
    elif w0 is not None and off is not None:
        g, _ = gaps_ok([ts(w["t"]) - off for w in waits if w0 <= ts(w["t"]) - off < w1], w0, w1, PATH_GAP_MAX_S)
        if g > PATH_GAP_MAX_S:
            reasons.add("waits-gap")
    hmax = (cfg or {}).get("hikaricp.connections.max", [None])[0] if cfg else None
    # 측정기(서버) — env-stall 판정 입력
    probe_srv = None
    if (d / "k6-probe-server.csv.gz").exists() and off is not None:
        try:
            probe_srv = _probe_csv(d / "k6-probe-server.csv.gz", off)
        except (OSError, EOFError, ValueError, StopIteration):
            probe_srv = None
    # ---- 지표 ----
    if csvd is not None:
        lat = csvd["lat"]
        M["p99"] = {k: p99(v) for k, v in (("hold_201", lat.get("hold:201")), ("hold_409", lat.get("hold:409")),
                                                ("confirm_200", lat.get("confirm:200")), ("seatmap_200", lat.get("seatmap:200"))) if v}
        if kind == "cost":
            M["p99"]["cost_hold"] = p99(lat.get("hold:201") or [])
        sec_p99 = [p99(v) for s, v in csvd["sec_hold"].items() if len(v) >= 10]
        M["p99_1s_max"] = max(sec_p99) if sec_p99 else None
        M["slow_secs"] = sum(1 for v in csvd["sec_hold"].values() if v and max(v) > 50)
        M["dropped"] = csvd["dropped"]
        M["dropped_all"] = csvd["dropped_all"]
        if kind == "main" and sum(csvd["dropped"].values()) > 0:
            reasons.add("dropped-iterations")
        elif kind == "cost" and sum(csvd["dropped"].values()) > 0:
            flags.append(f"cost-dropped-iterations {csvd['dropped']}(해석 메모 — 측정-9는 주 흐름만)")
        M["crn"] = csvd["crn"]
        M["rej_k6"] = csvd["rej"]
        M["hold409_k6"] = csvd["hold409"]
        M["m11"] = {"err_window": csvd["err"], "err_all": csvd["err_all"]}
        secs, chunks = stall_chunks(csvd["per_sec"], w0, w1)
        gc_long = gc.get("long_stops", []) if gc else []
        env_secs, method_secs, cls = 0, 0, []
        for a, b in chunks:
            if b - a >= STALL_MIN_S:
                k, why = classify_chunk(a, b, off, probe_srv, gc_long, waits, hik, hmax)
                cls.append({"from_rel": a - w0, "len": b - a, "class": k, **why})
                if k == "env":
                    env_secs += b - a
                    continue
            method_secs += b - a
        M["stall"] = {"secs": len(secs), "env_secs": env_secs, "method_secs": method_secs, "chunks": cls}
        if env_secs > 0:
            reasons.add("env-stall")
        # 원인 표지 대조(M3 ③ · app-metric-missing — 409가 1건 이상인 회차만 · 재점검 MR-17)
        if act["before"] and act["after"]:
            app_rule = _d(act["before"], act["after"], "rejected", "rule")
            app_now = _d(act["before"], act["after"], "rejected", "nowait")
            M["rej_app"] = {"rule": app_rule, "nowait": app_now}
            if csvd["hold409"] >= 1:
                k6_tot = (csvd["rej"].get("rule", 0) + csvd["rej"].get("nowait", 0))
                if k6_tot == 0 or app_rule != csvd["rej"].get("rule", 0) or app_now != csvd["rej"].get("nowait", 0):
                    reasons.add("app-metric-missing")
                    flags.append(f"원인 표지 대조 k6 {csvd['rej']} ≠ 앱 {M['rej_app']}")
            unk = csvd["rej"].get("missing", 0) + csvd["rej"].get("other", 0)
            if unk:
                flags.append(f"reject-cause-unknown(주 흐름·탐침 헤더 없음·모르는 값) {unk}")
    if gc:
        b = gc.get("k6") or {}
        M["gc"] = {"full": b.get("full"), "stalls": b.get("stalls"), "stop_ms_max": b.get("stop_ms_max")}
    if act["before"] and act["end"]:
        A0, A2 = act["before"], act["end"]
        M["app"] = {"reclaim": {k: _d(A0, A2, "reclaim", k) for k in ("COUNT", "TOTAL_TIME")}, "reclaim_max_s": (A2.get("reclaim") or {}).get("MAX"),
                    "deleted_reclaim": _d(A0, A2, "deleted", "reclaim"), "deleted_batch": _d(A0, A2, "deleted", "batch"),
                    "skip_c2": _d(A0, A2, "skip_locked", "c2"), "skip_h4b": _d(A0, A2, "skip_locked", "h4b"),
                    "kept_active": (_d(A0, A2, "revert", "batch.kept_active") or 0) + (_d(A0, A2, "revert", "reclaim.kept_active") or 0)
                    if _d(A0, A2, "revert", "batch.kept_active") is not None else None,
                    "reverted": _d(A0, A2, "revert", "batch.reverted"), "missing_pairs": _d(A0, A2, "missing_pairs", "batch"),
                    "chunk_failed": _d(A0, A2, "chunk", "failed"), "missing_mismatch": _d(A0, A2, "missing_mismatch"),
                    "tick_failed": _d(A0, A2, "tick", "failed"), "tick_ok": _d(A0, A2, "tick", "ok")}
        if act["after"]:
            M["app"]["tick_failed_k6"] = _d(A0, act["after"], "tick", "failed")
            M["app"]["missing_mismatch_k6"] = _d(A0, act["after"], "missing_mismatch")
    tb, ta, te = (inp.json(f"table_{k}", f"table-{k}.json", need=("tables.product_seat.n_tup_upd",)) for k in ("before", "after", "end"))
    if kind == "cost":
        inp.missing.pop("table_end", None)
    if tb and ta:
        M["deadlocks_k6"] = ta["deadlocks"] - tb["deadlocks"] if num(ta.get("deadlocks")) and num(tb.get("deadlocks")) else None
        M["upd_seat"] = {"d1": ta["tables"]["product_seat"]["n_tup_upd"] - tb["tables"]["product_seat"]["n_tup_upd"],
                         "d2": te["tables"]["product_seat"]["n_tup_upd"] - ta["tables"]["product_seat"]["n_tup_upd"] if te else None,
                         "confirmed_db": ta.get("confirmed")}
    dlog = _db_log_deadlocks(d, w0, w1, off) if w0 is not None and off is not None else None
    M["deadlocks_window"] = dlog
    if ddb is not None:
        seed_srv = (pk or {}).get("seed_done_epoch", 0) + (off or 0)
        logs = [x for x in ddb.get("drift_log") or [] if _safe_ts(x.get("at")) >= seed_srv]
        M["m6"] = {"repaired": sum(1 for x in logs if x.get("action") == "repaired"), "false_positive": sum(1 for x in logs if x.get("action") == "false-positive"),
                   "inc_runs": sum(1 for r in ddb.get("reconcile_runs") or [] if str(r.get("source", "")).startswith("inc-") and _safe_ts(r.get("started_at")) >= seed_srv),
                   "safety_runs": sum(1 for r in ddb.get("reconcile_runs") or [] if str(r.get("source", "")).startswith("safety") and _safe_ts(r.get("started_at")) >= seed_srv)}
        if M["m6"]["inc_runs"] == 0 or M["m6"]["safety_runs"] == 0:
            reasons.add("reconcile-missing")   # P8 생존 0 → 회차 무효(대조가 돌지 않았다)
    if cons:
        M["m6_end"] = {k: cons.get(k) for k in ("v_counter_mismatch", "v_over_limit_users", "v_duplicate_confirmed_seats", "double_sold_seats", "multi_active_seats",
                                                 "held_no_active", "active_not_held", "active_on_reserved_seats", "expired_hold_rows", "v_held_without_hold", "confirmed")}
        if cons.get("expiry_mode") != plan["mode"]:
            reasons.add("expiry-mode-mismatch")
    # 주 셀 전용
    st = None
    if kind == "main":
        rows = jsonl(d / "timeline-seatstate.jsonl")
        if rows is None or off is None or w0 is None:
            inp.miss("seatstate", "timeline-seatstate.jsonl 없음 또는 창 모름")
        else:
            st = state_metrics(rows, w0 + off, w1 + off, plan["ttl_s"])
            if st["gap_bad"]:
                reasons.add("state-sampler-gap")
            M["state"] = {k: v for k, v in st.items() if k != "gap_bad"}
        if csvd is not None:
            pm = probe_metrics(method, plan, csvd["probe"], off or 0)
            M["probe"] = pm
            if pm["timing_bad_cells"]:
                reasons.add("probe-timing-bad")
            if pm["incomplete"]:
                reasons.add("probe-incomplete")
            if pm["timing_dropped"]:
                flags.append(f"probe-timing-dropped {pm['timing_dropped']}")
            if pm["unknown_cause"]:
                flags.append(f"reject-cause-unknown(탐침) {len(pm['unknown_cause'])}: {pm['unknown_cause'][:10]}")
            if pm["setup_rejected"]:
                flags.append(f"probe-anomaly(탐침 첫 선점·판정 응답 예상 밖) {pm['setup_rejected'][:10]}")
            if pm["schedule_closed"]:
                reasons.add("schedule-closed-seen")
            if off is not None:
                M["flow"] = flow_metrics(csvd, plan, off, w0 + off, w1 + off)
                if st and st.get("W") is not None:
                    M["state"]["L_A"] = st["W"] / M["flow"]["n_exp"] if M["flow"]["n_exp"] else None
        h4 = jsonl(d / "timeline-h4.jsonl")
        if h4 is not None:
            M["h4"] = {"samples": len(h4), "seen": sum(1 for x in h4 if (x.get("h4_active") or 0) > 0), "lock": sum(1 for x in h4 if (x.get("h4_lock") or 0) > 0)}
            if method in ("B", "C", "D") and (M.get("app") or {}).get("reclaim", {}).get("COUNT") and not M["h4"]["seen"]:
                flags.append("h4-marker-unseen(회수는 있었는데 표지가 표본에 없다 — [구현 검증] #24 · 해석 메모)")
    # M14
    c1, c2 = load(d / "app-db-clock-before.json"), load(d / "app-db-clock-after.json")
    M["m14"] = {"app_minus_db_s": [((c["app_before"] + c["app_after"]) / 2 - c["db"]) for c in (c1, c2)
                                   if isinstance(c, dict) and all(num(c.get(k)) for k in ("app_before", "app_after", "db"))],
                "clock": {k: clk[k] for k in ("off", "rtt_half_max", "drift")} if clk else None}
    # ---- 방식 위반(유효 회차의 실패 자료) ----
    if cons:
        # 열은 CONS_COLS 검증을 통과했다(없거나 숫자가 아니면 cons = None → 회차 무효) — `or 0`으로 채우지 않는다(코드 리뷰 CR14-8)
        integ = {"dup_confirmed": cons["v_duplicate_confirmed_seats"], "double_sold": cons["double_sold_seats"],
                 "multi_active_end": cons["multi_active_seats"], "counter_mismatch": cons["v_counter_mismatch"],
                 "over_limit": cons["v_over_limit_users"]}
        if st:
            integ.update(multi_active_samples=st.get("multi_active_samples", 0), active_on_reserved_samples=st.get("active_on_reserved_samples", 0))
        if M.get("flow"):
            integ.update(running_duplicate=M["flow"]["duplicate"], confirm_then_201=M["flow"]["after_confirm_201"])
            # M5 ⑤ — 응답은 성공인데 행이 없음(k6 확정 200 — 모든 흐름 · DB CONFIRMED — 두 회차)
            integ["db_confirm_lt_k6"] = max(0, M["flow"]["confirm_200_k6"] - cons["confirmed"])
        if M.get("probe"):
            integ["pr3_both"] = M["probe"]["pr3"]["viol_both"]
            integ["late_confirm_200"] = M["probe"]["pr3"]["late_200"]
        if M.get("m6"):
            integ["repaired"] = M["m6"]["repaired"]
        if method == "C":
            integ["c_revert_violation"] = cons["active_not_held"] + (st.get("active_not_held_samples", 0) if st else 0)
        V["integrity"] = {k: v for k, v in integ.items() if v}
        V["state"] = [{"row": r, "why": w} for r, w in state_thresholds(method, cons, st)]
        em = expect_mismatch(method, cons, st, M.get("app") or {})
        if em:
            flags.append("expect-mismatch: " + " | ".join(em))
    p12 = {}
    if csvd is not None:
        for k, n in csvd["err"].items():
            p12[f"5xx_or_0:{k}"] = n
    p12["deadlock"] = M["deadlocks_window"] if M.get("deadlocks_window") is not None else M.get("deadlocks_k6")
    p12["tick_failed"] = (M.get("app") or {}).get("tick_failed_k6")
    p12["missing_mismatch"] = (M.get("app") or {}).get("missing_mismatch_k6")
    p12["method_stall_secs"] = (M.get("stall") or {}).get("method_secs")
    if M.get("probe"):
        p12["probe_errors"] = M["probe"]["errors"] + M["probe"]["setup_errors"]
    if gc:
        p12["gc_full_or_stall"] = (M["gc"]["full"] or 0) + (M["gc"]["stalls"] or 0)
        p12["gc_pause_ge_10ms"] = 1 if (M["gc"]["stop_ms_max"] or 0) >= GC_PAUSE_MAX_MS else 0
    if kind == "cost" and M.get("mem_growth") is not None:
        p12["mem_growth_ge_10pct"] = 1 if M["mem_growth"] >= MEM_GROWTH_MAX else 0
    V["p12"] = {k: v for k, v in p12.items() if v}
    V["p12_unknown"] = [k for k, v in p12.items() if v is None]
    if V["p12_unknown"]:
        flags.append(f"P12 입력 없음 {V['p12_unknown']}(→ 그 회차 P12 판정 불가)")
    if M.get("probe") and method in ("B", "C", "D"):
        V["p4_judge409"] = M["probe"]["m3"]["rule"]
        V["p9_early_not200"] = M["probe"]["pr3"]["early_not200"]
    if method == "D" and M.get("upd_seat") and kind == "main":
        u = M["upd_seat"]
        lo = cons.get("confirmed") if cons else None
        hi = csvd["confirm_reqs"] if csvd else None
        if lo is None or hi is None or u["d2"] is None:
            V["p6"] = None
        else:
            V["p6"] = {"d1": u["d1"], "lo": lo, "hi": hi, "d2": u["d2"], "violated": not (lo <= u["d1"] <= hi) or u["d2"] > 0}
    reasons.update(inp.reasons())
    reasons.discard("")
    if kind == "cost":
        for r in ("state-sampler-gap", "probe-incomplete", "probe-timing-bad", "dropped-iterations"):
            reasons.discard(r)
    status = "ok" if not reasons else "invalid:" + ",".join(sorted(reasons))
    out = {"status": status, "reasons": sorted(reasons), "env_stall_only": reasons == {"env-stall"}, "missing_inputs": inp.missing,
           "flags": flags, "metrics": M, "violations": V, "rerun": bool((meta or {}).get("rerun")), "attempt": (meta or {}).get("attempt")}
    (d / "rep-summary.json").write_text(json.dumps(_clean(out), ensure_ascii=False, indent=1, default=_jdefault))
    return out


def _clean(x):
    """JSON 쓰기용 — inf(T_x '창 밖' = 가장 나쁨)는 문자열 'inf'로(jq가 Infinity를 못 읽는다) · 읽을 때 _f가 되돌린다."""
    if isinstance(x, float) and math.isinf(x):
        return "inf" if x > 0 else "-inf"
    if isinstance(x, dict):
        return {k: _clean(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [_clean(v) for v in x]
    if isinstance(x, array):
        return list(x)
    return x


def _f(x):
    return INF if x == "inf" else -INF if x == "-inf" else x


def _jdefault(x):
    if isinstance(x, array):
        return list(x)
    if isinstance(x, float) and not math.isfinite(x):
        return None
    return str(x)


def _safe_ts(s):
    try:
        return ts(s)
    except (TypeError, ValueError):
        return -INF


def _probe_csv(path, off):
    """서버 측정기 CSV(ping · ms · 서버 시계) → [(PC 초, ms, 코드)] — ADR-009 tail.probe_csv와 같은 읽기."""
    out = []
    with gzip.open(path, "rt", newline="") as f:
        head = f.readline().rstrip("\n").split(",")
        i_ts, i_val, i_name, i_st = (head.index(k) for k in ("timestamp", "metric_value", "name", "status"))
        for line in f:
            if not line.startswith("http_req_duration,"):
                continue
            c = line.split(",", i_st + 1)
            if c[i_name] != "ping":
                continue
            out.append((int(c[i_ts]) / 1000 - off, float(c[i_val]), c[i_st]))
    out.sort()
    return out


def _db_log_deadlocks(d, w0, w1, off):
    """db.log.gz의 'deadlock detected' 줄 중 창 안(서버 시각 머리 '%m' — postgres:16 기본 log_line_prefix) · 시각을 못 읽는 줄이 있으면 None(→ k6 구간 증분을 쓴다)."""
    p = Path(d) / "db.log.gz"
    if not p.exists():
        return None
    n = 0
    try:
        with gzip.open(p, "rt", errors="replace") as f:
            for line in f:
                if "deadlock detected" not in line:
                    continue
                parts = line.split(" ", 3)   # '2026-10-26 07:00:00.123 UTC [57] ERROR:  deadlock detected'
                try:
                    tz = "+00:00" if parts[2] == "UTC" else (parts[2] + ":00" if len(parts[2]) == 3 and parts[2][0] in "+-" else None)
                    if tz is None:
                        return None
                    t = ts(f"{parts[0]}T{parts[1]}{tz}")
                except (IndexError, ValueError):
                    return None
                if w0 + off <= t < w1 + off:
                    n += 1
    except (OSError, EOFError):
        return None
    return n


# ---- crn 대조(측정-3.5 · MR-11) · 조기 확인 -------------------------------------------------------------------------------------------
def rep_dirs(root):
    root = Path(root)
    out = {}
    for c in plan14.GROUPS:
        for m in plan14.ABCD:
            for r in range(1, plan14.REPS + 1):
                d = root / c / m / f"rep{r}"
                out[(r, c, m)] = d
    return out


def crn_check(root):
    root = Path(root)
    res, changed = {}, []
    for r in range(1, plan14.REPS + 1):
        for c in MAIN_CELLS:
            hs = {}
            for m in plan14.ABCD:
                s = load(root / c / m / f"rep{r}" / "rep-summary.json")
                if s and s.get("metrics", {}).get("crn"):
                    hs[m] = json.dumps(s["metrics"]["crn"], sort_keys=True)
            if len(hs) < 2:
                continue
            vals = list(hs.values())
            top = max(set(vals), key=vals.count)
            bad = [m for m, h in hs.items() if h != top] if vals.count(top) > len(vals) / 2 else list(hs)
            res[f"rep{r}:{c}"] = {"methods": sorted(hs), "mismatch": bad}
            for m in bad:
                sp = root / c / m / f"rep{r}" / "status"
                st = sp.read_text().strip() if sp.exists() else ""
                if "crn-mismatch" not in st:
                    rs = set(st.split(":", 1)[1].split(",")) if st.startswith("invalid:") else set()
                    rs.add("crn-mismatch")
                    sp.write_text("invalid:" + ",".join(sorted(rs)) + "\n")
                    changed.append(f"rep{r} {c} {m}")
    (root / "crn-check.json").write_text(json.dumps({"groups": res, "changed": changed}, ensure_ascii=False, indent=1))
    print(json.dumps({"changed": changed}, ensure_ascii=False))
    return 0


def early_check(root):
    root = Path(root)
    ws, unfair, judged = [], 0, 0
    for c in TTL_GROUPS["t30"]:
        s = load(root / c / "A" / "rep1" / "rep-summary.json")
        st = (root / c / "A" / "rep1" / "status").read_text().strip() if (root / c / "A" / "rep1" / "status").exists() else ""
        if not s or st != "ok":
            print(f"early-check: {c} A rep1 유효 아님({st or '없음'}) — 판정하지 않는다(재측정 뒤 끝 집계 G4·P3가 본다)")
            continue
        judged += 1
        ws.append((s["metrics"].get("state") or {}).get("W"))
        unfair += sum(v["n409"] for k, v in ((s["metrics"].get("probe") or {}).get("m3", {}).get("by_delta") or {}).items()
                      if k.startswith("pr1:") and k.split(":")[1] in ("1.0", "3.0"))
    print(json.dumps({"A_W": ws, "A_pr1_d13_unfair": unfair, "judged_cells": judged}, ensure_ascii=False))
    if judged and (any(w is not None and w <= 0 for w in ws) or unfair == 0):
        return 1
    return 0


# ---- 캠페인 집계(측정-6 · 측정-7 · 측정-8) --------------------------------------------------------------------------------------------
def rng(vals):
    """유효 3회 값 → [최소, 최대] · 값이 3개 미만이거나 None이 섞이면 None(판정 불가)."""
    if len(vals) < plan14.REPS or any(v is None for v in vals):
        return None
    return [min(vals), max(vals)]


def lower(a, b):
    """a가 b보다 범위 비겹침으로 낮다(a 최대 < b 최소 — 같은 값이면 겹침)."""
    return a is not None and b is not None and a[1] < b[0]


def much_higher(xv, yv):
    """X ≫ Y(측정-0): X가 Y보다 범위 비겹침으로 높고 X 중앙값 > Y 중앙값 × 1.10."""
    rx, ry = rng(xv), rng(yv)
    if rx is None or ry is None:
        return False
    return lower(ry, rx) and statistics.median(xv) > statistics.median(yv) * TOL_RATIO


def eliminate(cands, beats):
    """beats(y, x) = y가 x를 빼는가. 빠지는 관계가 돌아 아무도 안 남으면 순환에 든 후보만 남긴다(ADR-013 §5.5 S5-12)."""
    out = {x for x in cands if any(beats(y, x) for y in cands if y != x)}
    keep = [x for x in cands if x not in out]
    if keep or not cands:
        return keep, sorted(out)
    # 모두 빠짐 — 순환에 든 후보(관계 그래프에서 자기에게 돌아오는 경로가 있는 후보)만 남긴다
    graph = {x: [y for y in cands if y != x and beats(x, y)] for x in cands}

    def reach(a, b, seen=()):
        return any(n == b or (n not in seen and reach(n, b, seen + (n,))) for n in graph[a])
    cyc = [x for x in cands if reach(x, x)]
    return cyc, sorted(set(cands) - set(cyc))


def campaign(root):
    root = Path(root)
    reps = {}
    slots = {}
    for (r, c, m), d in rep_dirs(root).items():
        st = (d / "status").read_text().strip() if (d / "status").exists() else None
        s = load(d / "rep-summary.json")
        hist = sorted(d.parent.glob(f"rep{r}.attempt*"))
        slots[(r, c, m)] = {"status": st, "attempts": len(hist) + (1 if d.exists() else 0),
                            "kinds": [(_kind((h / "status").read_text().strip() if (h / "status").exists() else "")) for h in hist] + [_kind(st or "")]}
        if st == "ok" and s:
            reps.setdefault((m, c), []).append(s)
    valid_n = {(m, c): len(reps.get((m, c), [])) for m in plan14.ABCD for c in plan14.GROUPS}
    env_exhausted = sorted(f"rep{r} {c} {m}" for (r, c, m), x in slots.items() if x["status"] and x["status"].startswith("invalid:") and _kind(x["status"]) == "env")
    harness_exhausted = sorted(f"rep{r} {c} {m}" for (r, c, m), x in slots.items() if x["status"] and x["status"].startswith("invalid:") and _kind(x["status"]) == "harness")

    def vals(m, c, f):
        return [f(s) for s in reps.get((m, c), [])]

    def mget(s, *path):
        x = s["metrics"]
        for p in path:
            x = x.get(p) if isinstance(x, dict) else None
        return x

    # 지표(방식, 셀) → 3회 값
    T95 = {(m, c): vals(m, c, lambda s: _f(mget(s, "state", "T95"))) for m in plan14.ABCD for c in MAIN_CELLS}

    def m3_group(m, g, key):
        out = []
        for r in range(1, plan14.REPS + 1):
            num_, den = 0, 0
            ok = True
            for c in TTL_GROUPS[g]:
                s = next((x for x in reps.get((m, c), []) if _rep_no(x) == r), None)
                if s is None or not mget(s, "probe", "m3"):
                    ok = False
                    break
                mm = mget(s, "probe", "m3")
                num_ += mm[key]
                den += mm["den"]
            if ok:
                out.append(num_ / den if den else None)
        return out
    M3 = {(m, g): m3_group(m, g, "n409") for m in plan14.ABCD for g in TTL_GROUPS}
    M3b = {(m, g): m3_group(m, g, "batch") for m in plan14.ABCD for g in TTL_GROUPS}
    COSTM = {
        "cost_hold_p99": lambda m: vals(m, COST_CELL, lambda s: mget(s, "p99", "cost_hold")),
        "cost_db_cpu": lambda m: vals(m, COST_CELL, lambda s: mget(s, "db_cpu", "mean")),
        "a50t300_hold201_p99": lambda m: vals(m, COST_JUDGE_CELL, lambda s: mget(s, "p99", "hold_201")),
        "a50t300_confirm_p99": lambda m: vals(m, COST_JUDGE_CELL, lambda s: mget(s, "p99", "confirm_200")),
        "a50t300_seatmap_p99": lambda m: vals(m, COST_JUDGE_CELL, lambda s: mget(s, "p99", "seatmap_200")),
        "a50t300_db_cpu": lambda m: vals(m, COST_JUDGE_CELL, lambda s: mget(s, "db_cpu", "mean")),
    }
    # 표본 비용 분리(측정-5 머리 · 리뷰 MR-1): Δc(a50-t300 실측) > A의 a50-t300 DB CPU 3회 범위면 분리
    cx = {m: [mget(s, "state", "c_pct") for s in reps.get((m, COST_JUDGE_CELL), [])] for m in plan14.ABCD}
    cxm = {m: statistics.mean(v) for m, v in cx.items() if v and all(num(x) for x in v)}
    a_cpu = rng(COSTM["a50t300_db_cpu"]("A"))
    delta_c = max(cxm.values()) - min(cxm.values()) if len(cxm) == 4 else None
    separate_cpu = delta_c is not None and a_cpu is not None and delta_c > a_cpu[1] - a_cpu[0]
    sample_cost = {"c_pct": cxm, "delta_c": delta_c, "A_a50t300_cpu_range": a_cpu, "separate_a50t300_db_cpu": separate_cpu,
                   "undecidable": delta_c is None}

    # ---- 방식별 성공(측정-7) ----
    def item_viol(m, c, key):
        """(항목, 셀)에서 유효 회차 하나라도 어겼나 · None = 판정 불가(입력 없음)."""
        xs = reps.get((m, c), [])
        if len(xs) < plan14.REPS:
            return None
        out = False
        for s in xs:
            v = s["violations"]
            if key.startswith("p12:"):
                k = key[4:]
                if k in (v.get("p12_unknown") or []):
                    return None
                out |= bool((v.get("p12") or {}).get(k))
            elif key.startswith("state:"):
                out |= any(x["row"] == int(key[6:]) for x in v.get("state") or [])
        return out
    p12_keys = sorted({k for xs in reps.values() for s in xs for k in list((s["violations"].get("p12") or {})) + list(s["violations"].get("p12_unknown") or [])})
    result = {}
    for m in ("B", "C", "D"):
        fails, undec, notes = [], [], []
        for c in plan14.GROUPS:
            if valid_n[(m, c)] < plan14.REPS:
                undec.append(f"{c} 유효 {valid_n[(m, c)]}/3")
        integ = [f"{c} rep{_rep_no(s)} {k}={v}" for c in plan14.GROUPS for s in reps.get((m, c), []) for k, v in (s["violations"].get("integrity") or {}).items()]
        if integ:
            fails.append("정합 위반: " + "; ".join(integ[:12]))
        # P4
        p4 = [f"{c} rep{_rep_no(s)} 판정409 {s['violations'].get('p4_judge409')}" for c in MAIN_CELLS for s in reps.get((m, c), []) if s["violations"].get("p4_judge409")]
        if p4:
            fails.append("P4 반증: " + "; ".join(p4))
        # P7 판정 불가 조건
        reh = sum((mget(s, "flow", "rehold_other") or 0) for c in MAIN_CELLS for s in reps.get((m, c), []))
        pr3_short = [f"{c} rep{_rep_no(s)}" for c in MAIN_CELLS for s in reps.get((m, c), []) if (mget(s, "probe", "pr3", "executed") or 0) < (mget(s, "probe", "pr3", "planned") or 0)]
        if reh == 0:
            undec.append("P7: 남의 만료 홀드 위 재선점 0(3회 합)")
        if pr3_short:
            notes.append(f"PR-3 실행 < 계획(송신 오차·오류로 빠짐 포함) {pr3_short[:6]}")
        # P9
        p9 = [f"{c} rep{_rep_no(s)} 만료 전 확정 거절 {s['violations'].get('p9_early_not200')}" for c in MAIN_CELLS for s in reps.get((m, c), []) if s["violations"].get("p9_early_not200")]
        if p9:
            fails.append("P9 반증: " + "; ".join(p9))
        # P5(C) · P6(D)
        if m == "C":
            opp = [mget(s, "app", "kept_active") for c in MAIN_CELLS for s in reps.get((m, c), [])]
            if any(o is None for o in opp):
                undec.append("P5: 되돌림 기회 지표 없음")
            elif sum(opp) == 0:
                undec.append("P5: 되돌림 기회 0(3회 합 — '막았다'가 아니라 '일이 없었다'일 수 있다)")
        if m == "D":
            p6 = [(c, _rep_no(s), s["violations"].get("p6")) for c in MAIN_CELLS for s in reps.get((m, c), [])]
            if any(x is None for _, _, x in p6):
                undec.append("P6: 테이블 통계 자료 없음")
            bad6 = [f"{c} rep{r} {x}" for c, r, x in p6 if x and x["violated"]]
            if bad6:
                fails.append("P6 반증: " + "; ".join(bad6))
        # P12 · 상태 기대값 문턱 — 'A도 같은 문턱'(측정-7)
        for key in [f"p12:{k}" for k in p12_keys] + [f"state:{r}" for r in (5, 6, 7, 8)]:
            if key.startswith("state:") and m not in ZERO_ROWS[int(key[6:])]:
                continue
            if key == "state:7" and m == "C":
                continue   # C의 7행은 P5 정합 위반으로만(재점검 MR-15)
            for c in plan14.GROUPS:
                if key == "p12:mem_growth_ge_10pct" and c != COST_CELL:
                    continue
                xv = item_viol(m, c, key)
                if xv is None:
                    if valid_n[(m, c)] == plan14.REPS:
                        undec.append(f"{key} {c}: 입력 없음")
                    continue
                if not xv:
                    continue
                a_rule = key.startswith("p12:") or (key.startswith("state:") and int(key[6:]) in (6, 7, 8))
                av = item_viol("A", c, key) if a_rule else False
                if a_rule and av:
                    undec.append(f"{key} {c}: A도 같은 셀에서 어김 → 하네스·조건 원인(판정 불가 · 사용자 보고)")
                else:
                    fails.append(f"{key} {c}" + ("" if a_rule else "(A와 견주지 않는 칸)"))
        verdict = "실패" if fails else "판정 불가" if undec else "성공"
        result[m] = {"verdict": verdict, "fails": fails, "undecidable": undec, "notes": notes,
                     "a_rule_only": verdict == "판정 불가" and all("A도 같은 셀" in u for u in undec)}
    # A: 기준(G4)
    a_valid = all(valid_n[("A", c)] == plan14.REPS for c in plan14.GROUPS)
    a_w = {c: vals("A", c, lambda s: mget(s, "state", "W")) for c in TTL_GROUPS["t30"]}
    p1_w = all(len(v) == plan14.REPS and all(num(x) and x > 0 for x in v) for v in a_w.values())
    a_integ = [f"{c} rep{_rep_no(s)} {k}={v}" for c in plan14.GROUPS for s in reps.get(("A", c), []) for k, v in (s["violations"].get("integrity") or {}).items()]
    g4 = a_valid and p1_w
    # P3 유효(TTL 묶음별): A의 PR-1·PR-2 δ ∈ {1, 3} 부당 실패가 3회 모두 0이 아니어야 한다
    p3_valid = {}
    for g, cells in TTL_GROUPS.items():
        per = []
        for r in range(1, plan14.REPS + 1):
            ss = [next((x for x in reps.get(("A", c), []) if _rep_no(x) == r), None) for c in cells]
            per.append(None if any(x is None for x in ss) else sum(mget(x, "probe", "m3", "unfair_d13") or 0 for x in ss))
        p3_valid[g] = None if any(x is None for x in per) else any(x > 0 for x in per)

    # ---- 고르는 규칙(측정-8) ----
    cands = [m for m in ("B", "C", "D") if result[m]["verdict"] == "성공"]
    stages, ranked = [], []

    def better2(y, x):   # 2단 — T95(4셀 · 낮을수록) · M3ᵦ(2묶음 · 낮을수록): y가 어디선가 낫고 어디서도 나쁘지 않으면 x를 뺀다
        mets = [(rng(T95[(y, c)]), rng(T95[(x, c)])) for c in MAIN_CELLS] + [(rng(M3b[(y, g)]), rng(M3b[(x, g)])) for g in TTL_GROUPS]
        return any(lower(a, b) for a, b in mets) and not any(lower(b, a) for a, b in mets)
    cost_keys = [k for k in COSTM if not (k == "a50t300_db_cpu" and separate_cpu)]

    def beats3(y, x):    # 3단 — X ≫ Y인 지표가 있고 Y ≫ X인 지표가 없으면 X가 빠진다
        return any(much_higher(COSTM[k](x), COSTM[k](y)) for k in cost_keys) and not any(much_higher(COSTM[k](y), COSTM[k](x)) for k in cost_keys)
    alive = list(cands)
    out_at = {}
    for name, rule in (("1 정합", None), ("2 판매 효과·사용자 실패", better2), ("3 비용", beats3)):
        if rule is None:
            stages.append({"stage": name, "alive": list(alive), "out": []})
            continue
        keep, gone = eliminate(alive, rule)
        for g in gone:
            out_at[g] = name
        stages.append({"stage": name, "alive": keep, "out": gone})
        alive = keep
    alive_sorted = sorted(alive, key=SIMPLICITY.index)
    stages.append({"stage": "4 단순성(D > B > C)", "alive": alive_sorted[:1], "out": alive_sorted[1:]})
    stage_rank = {"4": 0, "3 비용": 1, "2 판매 효과·사용자 실패": 2, "1 정합": 3}
    ranked = sorted(cands, key=lambda m: (stage_rank.get(out_at.get(m, "4"), 0), SIMPLICITY.index(m)))

    # ---- 교체 관문 G1~G4 ----
    def g2(m):
        t_better = any(lower(rng(T95[(m, c)]), rng(T95[("A", c)])) for c in MAIN_CELLS)
        t_worse = any(lower(rng(T95[("A", c)]), rng(T95[(m, c)])) for c in MAIN_CELLS)
        a = t_better and not t_worse
        b = any(p3_valid.get(g) and lower(rng(M3[(m, g)]), rng(M3[("A", g)])) for g in TTL_GROUPS)
        cc = bool(a_integ) and not any((s["violations"].get("integrity") or {}) for c in plan14.GROUPS for s in reps.get((m, c), []))
        return {"a": a, "b": b, "c": cc, "pass": a or b or cc}
    g3_keys = ["cost_hold_p99", "cost_db_cpu", "a50t300_hold201_p99", "a50t300_confirm_p99"] + ([] if separate_cpu else ["a50t300_db_cpu"])

    def g3(m):
        hit = [k for k in g3_keys if much_higher(COSTM[k](m), COSTM[k]("A"))]
        und = [k for k in g3_keys if rng(COSTM[k](m)) is None or rng(COSTM[k]("A")) is None]
        return {"hits": hit, "undecidable": und, "pass": not hit and not und}
    gates = {}
    winner = None
    for m in ranked:
        x = {"G1": result[m]["verdict"] == "성공", "G2": g2(m), "G3": g3(m), "G4": g4}
        x["pass"] = x["G1"] and x["G2"]["pass"] and x["G3"]["pass"] and x["G4"]
        gates[m] = x
        if x["pass"] and winner is None:
            winner = m
    blocked = env_exhausted or [m for m in result if result[m]["a_rule_only"]]
    if winner and blocked:
        # 해석 메모: 환경 상한 소진 자리(ⓑ)나 'A도 같은 문턱'으로만 판정 불가인 후보(ⓐ)가 있으면, 그 자리·후보가 순위를 바꿀 수 있으므로 관문을 넘은 후보가 있어도
        # 결론은 '판정 불가 → 사용자 보고'(잠정 결과를 함께 적는다 — 기본값은 사용자 결정 전까지 그대로)
        verdict = f"판정 불가 → 사용자 보고(측정-8 ⓐ·ⓑ — 잠정: {winner}({plan14.METHODS[winner]})가 관문 통과 · 막은 것 {blocked})"
        winner = None
    elif winner:
        verdict = f"기본값 교체 — expiry-mode = {plan14.METHODS[winner]}({winner})"
    elif blocked:
        verdict = "판정 불가 → 사용자 보고(측정-8 ⓐ·ⓑ — A 유지도 방식 실패도 아니다 · 기본값은 사용자 결정 전까지 그대로)"
    elif not g4:
        verdict = "A 유지 ④ — G4 실패(측정 전 규칙으로 고를 수 없다 · 판정 불가 · 사용자 보고)"
    elif not cands:
        verdict = "A 유지 ① — 성공한 후보가 없다"
    elif all(not gates[m]["G2"]["pass"] for m in ranked):
        verdict = "A 유지 ② — 성공한 후보가 모두 G2를 못 넘는다"
    else:
        verdict = "A 유지 ③ — G2를 넘은 후보가 모두 G3에서 걸린다"
    hyp = hypotheses(reps, T95, M3, COSTM, mget, p3_valid, a_w)
    res = {"slots": {f"rep{r}:{c}:{m}": x for (r, c, m), x in slots.items()}, "valid_n": {f"{m}:{c}": n for (m, c), n in valid_n.items()},
           "env_exhausted": env_exhausted, "harness_exhausted": harness_exhausted, "success": result, "A": {"valid": a_valid, "P1_W_t30": a_w, "P1_W_pos": p1_w,
           "integrity": a_integ, "G4": g4}, "P3_valid": p3_valid, "sample_cost": sample_cost, "stages": stages, "ranked": ranked, "gates": gates,
           "decision": {"verdict": verdict, "new_default": plan14.METHODS[winner] if winner else None,
                        "note": "G2(b)는 P3가 서면 사실상 늘 A가 진다 — 교체는 사실상 정합(G1)과 비용(G3)이 정한다(측정-8 '주의' · MQ3)"},
           "hypotheses": hyp,
           "ranges": {"T95": {f"{m}:{c}": rng(v) for (m, c), v in T95.items()}, "M3": {f"{m}:{g}": rng(v) for (m, g), v in M3.items()},
                      "M3b": {f"{m}:{g}": rng(v) for (m, g), v in M3b.items()},
                      "cost": {k: {m: rng(f(m)) for m in plan14.ABCD} for k, f in COSTM.items()},
                      "seatmap_ratio_vs_A": {m: (statistics.median(COSTM["a50t300_seatmap_p99"](m)) / statistics.median(COSTM["a50t300_seatmap_p99"]("A"))
                                                 if rng(COSTM["a50t300_seatmap_p99"](m)) and rng(COSTM["a50t300_seatmap_p99"]("A")) else None) for m in "BCD"}}}
    (root / "result.json").write_text(json.dumps(_clean(res), ensure_ascii=False, indent=1, default=_jdefault))
    (root / "RESULT.md").write_text(render_md(res))
    print(verdict)
    return 0


def _kind(st):
    if st == "invalid:env-stall":
        return "env"
    if not st or st.startswith("invalid:"):
        return "harness"
    return "valid"


def _rep_no(s):
    return int(s["metrics"]["rep"])


def hypotheses(reps, T95, M3, COSTM, mget, p3_valid, a_w):
    """P1~P13 판정 자료(측정-6) — 설명 가설은 기록만(성공·고르기에 쓰지 않는다). 값이 모자라면 '판정 불가'."""
    H = {}
    la = [mget(s, "state", "L_A") for c in MAIN_CELLS for s in reps.get(("A", c), [])]
    H["P1"] = {"L_A": la, "W_t30_A": a_w, "verdict": ("판정 불가" if any(x is None for x in la) or not la else
                                                       "반증" if any(x < 1 or x > 15 for x in la) else "성립")}
    p2 = {}
    for c in MAIN_CELLS:
        for m in "BCD":
            ra, rx = rng(T95[("A", c)]), rng(T95[(m, c)])
            p2[f"{m}:{c}"] = None if ra is None or rx is None else ("짧다(비겹침)" if lower(rx, ra) else "겹침·김")
    H["P2"] = p2
    H["P3"] = {"valid_by_group": p3_valid, "A_M3": {g: rng(M3[("A", g)]) for g in TTL_GROUPS}}
    H["P10"] = {k: {m: rng(COSTM[k](m)) for m in plan14.ABCD} for k in ("cost_hold_p99", "cost_db_cpu")}
    H["P11"] = {m: rng(COSTM["a50t300_seatmap_p99"](m)) for m in plan14.ABCD}
    p13 = []
    for c in MAIN_CELLS:
        for s in reps.get(("B", c), []):
            fl, stt = s["metrics"].get("flow") or {}, s["metrics"].get("state") or {}
            pred = (fl.get("abandoned") or 0) - ((fl.get("revisit") or {}).get("n201") or 0)
            got = stt.get("expired_end_window")
            p13.append({"cell": c, "pred": pred, "got": got, "within_10pct": (abs(got - pred) <= 0.1 * pred) if got is not None and pred else None})
    H["P13"] = p13
    return H


def render_md(res):
    L = ["# ADR-014 측정 결과(요약기 산출 — summarize.py)", "",
         f"결론: **{res['decision']['verdict']}**", "", f"- {res['decision']['note']}", ""]
    L.append("## 회차 유효 수(방식 × 셀 — 유효 3이어야 판정)")
    L.append("| 방식 | " + " | ".join(plan14.GROUPS) + " |")
    L.append("|---|" + "---|" * len(plan14.GROUPS))
    for m in plan14.ABCD:
        L.append(f"| {m} | " + " | ".join(str(res["valid_n"][f"{m}:{c}"]) for c in plan14.GROUPS) + " |")
    if res["env_exhausted"]:
        L.append(f"- 환경 상한 소진(판정 불가 → 사용자 보고): {res['env_exhausted']}")
    if res["harness_exhausted"]:
        L.append(f"- 하네스 상한 소진(유효 < 3): {res['harness_exhausted']}")
    L += ["", "## 방식별 성공(측정-7)"]
    for m, x in res["success"].items():
        L.append(f"- **{m}({plan14.METHODS[m]})**: {x['verdict']}" + (f" — 실패 {x['fails']}" if x["fails"] else "") +
                 (f" — 판정 불가 {x['undecidable']}" if x["undecidable"] else "") + (f" — 메모 {x['notes']}" if x["notes"] else ""))
    a = res["A"]
    L.append(f"- **A(sweep — 기준)**: 유효 {a['valid']} · P1 TTL 30초 W > 0 {a['P1_W_pos']} · G4 {a['G4']} · 정합 위반 {a['integrity'] or '없음'}(있으면 ADR-015 입력 — MQ9)")
    L += ["", "## 고르는 규칙(측정-8)"]
    for s in res["stages"]:
        L.append(f"- {s['stage']}: 남음 {s['alive']} · 빠짐 {s['out']}")
    L.append(f"- 순위: {res['ranked']}")
    sc = res["sample_cost"]
    L.append(f"- 표본 비용 기준(측정-5 머리): Δc {sc['delta_c']} · A a50-t300 DB CPU 범위 {sc['A_a50t300_cpu_range']} → a50-t300 DB CPU 분리 {sc['separate_a50t300_db_cpu']}")
    L += ["", "## 교체 관문(G1~G4)"]
    for m, g in res["gates"].items():
        L.append(f"- {m}: G1 {g['G1']} · G2 {g['G2']} · G3 {g['G3']} · G4 {g['G4']} → {'통과' if g['pass'] else '못 넘음'}")
    L += ["", "## 지표 범위(유효 3회 [최소–최대])", "| 지표 | A | B | C | D |", "|---|---|---|---|---|"]
    for c in MAIN_CELLS:
        L.append(f"| T95 {c} | " + " | ".join(str(res["ranges"]["T95"][f"{m}:{c}"]) for m in plan14.ABCD) + " |")
    for g in TTL_GROUPS:
        L.append(f"| M3 {g} | " + " | ".join(str(res["ranges"]["M3"][f"{m}:{g}"]) for m in plan14.ABCD) + " |")
        L.append(f"| M3ᵦ {g} | " + " | ".join(str(res["ranges"]["M3b"][f"{m}:{g}"]) for m in plan14.ABCD) + " |")
    for k, v in res["ranges"]["cost"].items():
        L.append(f"| {k} | " + " | ".join(str(v[m]) for m in plan14.ABCD) + " |")
    L.append(f"- 좌석 맵 p99 X ÷ A(MQ2 — 관문 밖 · 보고용): {res['ranges']['seatmap_ratio_vs_A']}")
    L += ["", "## 가설 자료(측정-6 — 설명 가설은 기록만)", "```", json.dumps(res["hypotheses"], ensure_ascii=False, default=_jdefault)[:6000], "```", ""]
    return "\n".join(L)


# ---- 시작 조건 ⑤ 입력 ------------------------------------------------------------------------------------------------------------
def sample_cost(dirs):
    per = {}
    for d in dirs:
        s = load(Path(d) / "rep-summary.json") or rep_summary(d)
        st = (s.get("metrics") or {}).get("state") or {}
        m = (s.get("metrics") or {}).get("method")
        per.setdefault(m, []).append({"dir": str(d), "sample_ms": st.get("sample_ms"), "c_pct": st.get("c_pct"), "slope_ms_per_row": st.get("slope_ms_per_row")})
    cs = {m: statistics.mean(x["c_pct"] for x in v if num(x["c_pct"])) for m, v in per.items() if any(num(x["c_pct"]) for x in v)}
    out = {"per_method": per, "c_pct": cs, "delta_c": (max(cs.values()) - min(cs.values())) if len(cs) >= 2 else None}
    print(json.dumps(out, ensure_ascii=False, default=_jdefault))
    return 0


def r0(root):
    groups = {}
    for m in Path(root).glob("*/*/L4/S3-a20/rep*/meta.json"):
        meta = load(m) or {}
        d = m.parent
        if meta.get("pg_preset") != "tmpfs" or (d / "status").read_text().strip() != "ok" if (d / "status").exists() else True:
            continue
        clk = (load(d / "clock-offset.json") or {})
        offs = [x["offset_s"] for x in (clk.get("before"), clk.get("after")) if isinstance(x, dict) and num(x.get("offset_s"))]
        if not offs or not meta.get("k6_started") or not meta.get("k6_ended"):
            continue
        rows, _ = server_rows(d / "timeline-server.jsonl", sum(offs) / len(offs))
        w0, w1 = ts(meta["k6_started"]), ts(meta["k6_ended"])
        c = db_cpu(rows or [], w0, w1)
        if c["mean"] is not None:
            groups.setdefault(str(d.parent.parent.parent), []).append(c["mean"])
    rr = {g: max(v) - min(v) for g, v in groups.items() if len(v) >= 3}
    out = {"groups": groups, "ranges": rr, "R0": min(rr.values()) if rr else None}
    print(json.dumps(out, ensure_ascii=False))
    return 0 if rr else 1


def main(argv):
    if len(argv) == 2 and argv[0] == "--rep":
        s = rep_summary(argv[1])
        print(s["status"])
        return 0
    if len(argv) == 2 and argv[0] == "--crn-check":
        return crn_check(argv[1])
    if len(argv) == 2 and argv[0] == "--early-check":
        return early_check(argv[1])
    if len(argv) >= 2 and argv[0] == "--sample-cost":
        return sample_cost(argv[1:])
    if len(argv) == 2 and argv[0] == "--r0":
        return r0(argv[1])
    if len(argv) == 1 and not argv[0].startswith("-"):
        return campaign(argv[0])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
