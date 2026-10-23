#!/usr/bin/env python3
"""ADR-013 판정 자료 · 회차 판정(§5.3) · 캠페인 판정(§5.2 P1~P12 · §5.4 성공 · §5.5 고르는 규칙 · §5.8).

    summarize.py --rep <회차폴더>          → rep-summary.json · 마지막 줄 = status(ok · invalid:<사유,…> · fail:<사유,…>) — run.sh가 status로 쓴다
    summarize.py <캠페인폴더>              → result.json · RESULT.md(회차 표 · P1~P12 · 성공 · 고르는 규칙 · TAIL 표)
    summarize.py --select <json>           → 고르는 규칙만(§5.5 — 후보·지표 범위 JSON을 받아 단계별 결과) · selftest가 쓴다

정의(ADR-013 원문 — 바꾸면 ADR과 함께. **§5.8이 §5.4·§5.3·§4.7.1의 해당 정의를 대신한다**):
  시각: '회차 기준 초' rel = PC epoch − T0(t0.json — 주 k6를 띄운 PC 시각). 서버 시각 → PC = 서버 − OFF(clock-offset.json 앞·뒤 평균 — 없으면 앞).
  구간(§4.7.1 S5-11): plan13.intervals(실제 사건) — old [0, T1 첫 사건) · trans [T1 첫 사건, 마지막 전환 사건 끝) · new [그 뒤, k6 끝). ①의 trans = 중단(outage).
  정지 초(S4c · DS-6): 1초 격자(k6 CSV 행 시각 = 완료 · wall.py:11)마다 hold 201 = 0인 초. 보낸 시각 기준(완료 − 지연 · 코드 0 제외)도 함께.
  서버형 느린 초(ADR-012 tail.py 정의): 램프 뒤 초 중 hold(코드 0 제외) 최대 응답 > 50ms이고 그 요청의 waiting > blocked + sending + receiving인 초.
  D1 = S4c 정지 초 수 [90, k6 끝) · D2 = S3e 가용성 탐침 실패 수(코드 0 · 5xx · 502 · 요청 시한 · 버린 반복 — §5.1) · D3 = S4c [T1 첫 사건, k6 끝) 서버형 느린 초.
  한도 탐침(§4.7.1 · §5.8): 판정 탐침 = 주입·판정이 한 구간 · 경계 아님(owner_changed 없음 — DS-10) · 판정 요청 응답이 201·409 · 중단 아님.
    맞음/틀림 — H: 선점 2 201 = 맞음 · 409 HOLD_LIMIT_EXCEEDED = 틀림(부당 거절). L: 선점 2 201 · 선점 3 409 HOLD_LIMIT_EXCEEDED = 맞음 · 선점 3 201 = 틀림(한도 초과).
      그 밖(응답은 모두 201·409인데 모양이 다름) = 예상 밖 판정 응답 — 판정에서 빼고 flags probe-anomaly · 캠페인 P9 (바)(정합 분류 — §5.3 N10 기준과 같다 · CR13-3).
    짝 제외(§5.8-2): (회차, d, 종류)마다 단독(옛) 칸 탐침과 전환 칸 탐침 중 하나가 빠지면 다른 쪽도 뺀다(d ∈ {5, 30, 75}).
    전환 손해(§5.8-3 — §5.4 'min(T) > max(O)'를 대신한다): 부하·절차마다 3회 합으로 (d, 종류) 6칸(먼저 고정)마다 T 틀림 ≤ O 틀림 — 한 칸이라도 T > O면 반증 ·
      3회 합 짝 0인 칸 = 판정 불가(성공 아님 — CR13-2). ①은 해당 없음.
    J1·J2(§5.8-3 · §5.8-3a 정정 — §5.5 'Pᵣ = 전환 + 단독(새)'를 대신한다): 회차마다 Σ(T 틀림 − O 틀림) ÷ 짝 맞춘 T 수.
      모집단 = 전환 칸(T)만 — (d, 종류)마다 실제 구간이 전환인 전환 칸 판정 탐침과 같은 (d, 종류) 단독(옛) 칸 판정 탐침의 짝(§5.8-2 짝 제외).
      단독(새) 칸·①의 lb 편입 뒤 탐침은 J에 들지 않는다(CR13-19 — CR13-1을 되돌림). ①은 전환 구간에 섞임이 없어 J = 0(정의 — ①의 손해는 D1·D2).
    최소 판정 탐침 수(§5.4 표 + §5.8-4 — 회차마다 · 짝 제외 뒤): O ≥ 3 · Q(단독(옛) d ∈ {1, 5}) ≥ 2 · T ≥ 3(②~⑤) ·
      N(실제 구간 단독(새) · 칸 무관 · d ∈ {30, 75} — CR13-9) H ≥ 1 · L ≥ 1 · 합 ≥ 2 · J ≥ 3(②~⑤ — J 수 = T 짝 수 · ①은 J = 0 정의라 묻지 않는다).
    실행기 완료(CR13-15): done 행 없음 · done 계획·끝낸 수 ≠ 계획 · 탐침 기록 수 ≠ 계획 = invalid:probe-incomplete(최소 수를 채워도 — 남은 탐침이 숨는다).
    미달 원인(§5.8-5): 하네스(실행기 끝 행 없음 · 탐침 기록 < 계획 · 주입·스냅숏 SQL 실패 · 실행기 예외) = invalid:probe-short · 그 밖 = fail:probe-short-by-procedure.
    (마) 두 수(스냅숏 > 2 · L 선점 3 = 201)는 응답이 모두 201·409인 탐침만 — 코드 0·5xx 섞인 탐침은 unclean_over로 따로(CR13-11).
  N10 결정성: 판정 요청 직후 스냅숏 실제(홀드 + 확정) = 그 탐침의 201 누계 — 다르면 probe-nondeterministic, 기계적 분류(§5.3 S5-10):
    하네스 = ⓐ 그 탐침 요청 중 201·409가 아닌 응답 ≥ 1 또는 ⓑ 다시 센 201 수 = 스냅숏 실제이고 홀드 행이 모두 201 요청 창 안 · 정합 = 그 밖(P9 (바)).
  촘촘한 락 표본 결측(§5.3): ADR 창 [T1 첫 사건 − 10, 마지막 lb 넣기 끝 + 30](④⑤ + [적용판 시작 − 10, 끝 + 30])에서 0.2초 표본 기대 수 대비 결측 > 10% = invalid.
  가용성 탐침 부족(§5.3): 전환 창(위 창과 같다) **밖**에서 (보낸 요청 + 버린 반복) < 계획(2/s × 길이)의 95% = invalid. 창 안의 부족·버린 반복은 D2 실패다.
  단독(옛) 기준 창 [60, 90)(§5.3 S5-7): S4c 정지 초 > 0 · 두 부하 5xx·502 > 0 = invalid(환경). S3e는 정지 초를 쓰지 않는다(좌석 소진 — DS-6).
  H4 앱별 수집(CR13-5): UP한 생애마다 수집 사건(오류 0) · 지표·configprops · 앱 로그 — 결측 = invalid:app-collect-missing:<생애>:<항목> ·
    GC 요약 없음·빈 로그·창 없음 = invalid:gc-log-missing:<생애>(CR13-4).
  전환 실패 회차(CR13-18): 절차 사유가 하네스 사유보다 앞선다 — **전환 실패 사건 시작 시각 뒤에 생기는 산출물**의 결측(P7 자료 · N3 그림자 지표 · H4 · GC ·
    옛 앱 로그(수집이 실패 뒤) · 끝 검사 앱·끝 검사·DB 덤프 · m 해석)은 하네스 사유로 묻지 않고 harness_deferred에 기록만 한다(그 결측은 실패의 결과일 수 있다).
    전환 실패 앞의 하네스 사유(시드 · 기준 창 · 시계 차 · k6·탐침 기록 등)는 그대로 invalid다(회차 자체를 믿을 수 없다).
  끝 검사 앱 ≠ 계획 final_new = fail:final-app-mismatch(CR13-참고).
수치는 모두 원시 파일에서 계산한다(손 계산 금지). 없는 파일은 사유로 드러낸다(무음 금지)."""
import gzip
from array import array
import json
import math
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import plan13  # noqa: E402

def _gc9():
    """ADR-009 요약기의 gc_summary(수정 없이 가져다 쓴다 — 이름이 같은 이 파일과 섞이지 않게 경로로 읽는다)."""
    import importlib.util
    import os
    for base in (HERE.parents[1] / "ADR-009" / "scripts", Path(os.environ.get("LIB9_DIR") or "/nonexistent")):
        f = base / "summarize.py"
        if f.exists():
            spec = importlib.util.spec_from_file_location("summarize9", f)
            m = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(m)
            return m.gc_summary
    return None


gc_summary9 = _gc9()

SLOW_MS = 50
D_SET = (5, 30, 75)
KINDS = ("H", "L")
MIN = {"O": 3, "Q": 2, "T": 3, "N": 2, "N_H": 1, "N_L": 1, "J": 3}
N6B_S = 3.0
MLOCK_STEP_S, MLOCK_MISS = 0.2, 0.10
AVAIL_MIN_SHARE = 0.95
BUSINESS = re.compile(r"^seatlab-[AB]-[0-9a-f]+-(old|new|shadow)$")
# run.sh가 회차 끝(k6·수렴 뒤) 단계에서 남기는 하네스 사유 — 전환 실패 회차에서는 기록만(CR13-18)
END_HREASONS = {"end-check-failed", "end-check-app-failed", "drift-db-failed", "end-pairs-failed", "injected-missing"}


def load(p):
    try:
        return json.loads(Path(p).read_text())
    except (OSError, json.JSONDecodeError):
        return None


def jsonl(p):
    out = []
    try:
        for line in Path(p).read_text().splitlines():
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    except OSError:
        return None
    return out


def num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def iso(s):
    from datetime import datetime
    if s is None:
        return None
    if num(s):
        return float(s)
    return datetime.fromisoformat(str(s).replace("Z", "+00:00")).timestamp()


_PG_TS = re.compile(r"(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2}(?:\.\d+)?)([+-]\d{2})(?::?(\d{2}))?(?::?(\d{2}))?")


def pg_ts(v):
    """PostgreSQL timestamptz 글(attmissingval '{"2026-10-24 00:00:00.123+09:30"}' 등) → epoch 초 · 못 읽으면 None(CR13-28 — 분 단위 오프셋 · 소수 초 없음)."""
    from datetime import datetime, timedelta, timezone
    m = _PG_TS.search(str(v))
    if not m:
        return None
    day, tm, oh, om, osec = m.groups()
    sign = -1 if oh.startswith("-") else 1
    off = timedelta(hours=int(oh[1:]), minutes=int(om or 0), seconds=int(osec or 0)) * sign
    frac = tm.split(".")[1] if "." in tm else ""
    base = datetime.fromisoformat(f"{day}T{tm.split('.')[0]}").replace(tzinfo=timezone(off))
    return base.timestamp() + (float("0." + frac) if frac else 0.0)


def p99(xs):
    xs = sorted(xs)
    return xs[int(len(xs) * 0.99)] if xs else None


# ---- 시각 ------------------------------------------------------------------------------------------------------------------
class Clock:
    def __init__(self, d):
        t = load(d / "t0.json") or {}
        self.T0 = t.get("t0_pc")
        c = load(d / "clock-offset.json") or {}
        xs = [x["offset_s"] for x in (c.get("before"), c.get("after")) if isinstance(x, dict) and num(x.get("offset_s"))]
        self.off = sum(xs) / len(xs) if xs else (t.get("offset_s") or 0.0)
        self.off_known = bool(xs)

    def pc(self, t_pc):
        return None if t_pc is None or self.T0 is None else t_pc - self.T0

    def srv(self, t_srv):
        return None if t_srv is None or self.T0 is None else t_srv - self.off - self.T0


# ---- 사건 ------------------------------------------------------------------------------------------------------------------
def load_events(d, clk):
    rows = jsonl(d / "events.jsonl") or []
    ev = []
    for e in rows:
        if e.get("kind", "plan") != "plan":
            continue
        e = dict(e)
        e["t0"], e["t1"] = clk.pc(e.get("start_pc")), clk.pc(e.get("end_pc"))
        ev.append(e)
    samplers = [r for r in rows if r.get("kind") == "sampler"]
    return ev, samplers, rows


def lives_of(ev):
    """생애별 {life: {slot, role, sha, start, up, lb_add, lb_remove, stop_start, stop_end, collect}} — 회차 기준 초."""
    out = {}
    for e in ev:
        life = e.get("life")
        if not life:
            continue
        L = out.setdefault(life, {"slot": e.get("slot"), "role": e.get("role"), "sha": e.get("sha")})
        if e["result"] != "ok":
            continue
        k = {"start": "start", "wait-health": "up", "lb-add": "lb_add", "lb-remove": "lb_remove", "collect": "collect"}.get(e["op"])
        if k:
            L[k] = e["t1"] if k in ("up", "lb_add", "lb_remove") else e["t0"]
            if k == "lb_add":
                L["ip"] = (e.get("detail") or {}).get("ip")
            if k == "lb_remove":
                L["remove_eff"] = (e.get("detail") or {}).get("effective")
        if e["op"] == "stop":
            L["stop_start"], L["stop_end"] = e["t0"], e["t1"]
    return out


# ---- 주 k6 CSV ---------------------------------------------------------------------------------------------------------------
def main_csv(path, T0):
    """초(정수 PC 초)별: n(hold 응답) · c201 · c201_send(보낸 초 기준) · e5xx · e502 · c0 · mx · comp(waiting, blocked, sending, receiving) · durs(코드 0 제외)."""
    per = {}
    if not path.exists():
        return None
    with gzip.open(path, "rt", newline="") as f:
        head = f.readline().rstrip("\n").split(",")
        i_ts, i_val, i_name, i_st = (head.index(k) for k in ("timestamp", "metric_value", "name", "status"))
        comp_idx = {"http_req_blocked": 1, "http_req_sending": 2, "http_req_waiting": 0, "http_req_receiving": 3}
        cur = None

        def close(c):
            s, dur, st, name, comp = c
            e = per.setdefault(s, {"n": 0, "c201": 0, "c201_send": 0, "e5xx": 0, "e502": 0, "c0": 0, "mx": -1.0, "comp": None, "durs": array("f"), "all": 0})
            e["all"] += 1
            if st.startswith("5"):
                e["e5xx"] += 1
                if st == "502":
                    e["e502"] += 1
            if st in ("0", ""):
                e["c0"] += 1
            if name != "hold":
                return
            e["n"] += 1
            if st == "201":
                e["c201"] += 1
                ss = int(math.floor(s + 0.5 - dur / 1000.0))   # 보낸 초 ≈ 완료 − 지연(행 시각이 정수 초라 ±1초 격자)
                per.setdefault(ss, {"n": 0, "c201": 0, "c201_send": 0, "e5xx": 0, "e502": 0, "c0": 0, "mx": -1.0, "comp": None, "durs": array("f"), "all": 0})["c201_send"] += 1
            if st not in ("0", ""):
                e["durs"].append(dur)
                if dur > e["mx"]:
                    e["mx"], e["comp"] = dur, comp
        for line in f:
            if not line.startswith("http_req_"):
                continue
            c = line.split(",", i_st + 1)
            m = c[0]
            if m == "http_req_duration":
                if cur is not None:
                    close(cur)
                cur = [int(c[i_ts]), float(c[i_val]), c[i_st], c[i_name], [0.0, 0.0, 0.0, 0.0]]
            elif cur is not None and m in comp_idx:
                cur[4][comp_idx[m]] = float(c[i_val])
        if cur is not None:
            close(cur)
    return per


def k6_metrics(per, T0, iv, load, k6_end_rel, t1_first, plan):
    L = plan["load_conf"]
    ramp = L.get("ramp_s") or 0
    rate = L.get("plan_rate")
    secs = sorted(per)
    lo = int(math.ceil(T0 + ramp)) if load == "S4c" else int(math.ceil(T0))
    # 마지막 완전한 초 끝 — k6 프로세스 끝이 아니라 계획 도착 끝(timeline.k6_s)에서 자른다: k6는 도착이 끝난 뒤에도 마무리(진행 중 요청 대기·요약 쓰기)로
    #   약 12초 더 살아 있고(.164 스모크 2026-10-24 — 1,200초 뒤 요청 0), 그 초들이 '201 0인 초'로 D1·정지 초에 들었다(모든 회차 +11초 — 무중단 이름표 불가)
    hi = int(math.floor(T0 + min(k6_end_rel, plan["timeline"]["k6_s"])))
    grid = list(range(lo, hi))

    def rel(s):
        return s + 0.5 - T0
    out = {"grid": [lo - T0, hi - T0], "by_interval": {}}
    stall = [s for s in grid if per.get(s, {}).get("c201", 0) == 0]
    stall_send = [s for s in grid if per.get(s, {}).get("c201_send", 0) == 0]
    half = [s for s in grid if rate and per.get(s, {}).get("c201", 0) < 0.5 * rate]
    server = [s for s in grid if per.get(s) and per[s]["mx"] > SLOW_MS and per[s]["comp"] and per[s]["comp"][0] > sum(per[s]["comp"][1:])]

    def longest(ss):
        best = cur = 0
        prev = None
        for s in ss:
            cur = cur + 1 if prev is not None and s == prev + 1 else 1
            best = max(best, cur)
            prev = s
        return best
    for name in ("old", "trans", "new"):
        a, b = iv[name] if iv else (0, 0)
        g = [s for s in grid if a <= rel(s) < b]
        allg = [s for s in secs if a <= rel(s) < b]
        durs = [x for s in allg for x in per[s]["durs"]]
        sp = [p99(per[s]["durs"]) for s in g if per.get(s) and per[s]["durs"] and (not rate or per[s]["c201"] >= 0.5 * rate)]
        out["by_interval"][name] = {
            "secs": len(g), "stall": sum(1 for s in stall if a <= rel(s) < b), "stall_longest": longest([s for s in stall if a <= rel(s) < b]),
            "stall_send": sum(1 for s in stall_send if a <= rel(s) < b), "stall_send_longest": longest([s for s in stall_send if a <= rel(s) < b]),
            "half_rate": sum(1 for s in half if a <= rel(s) < b), "server_slow": sum(1 for s in server if a <= rel(s) < b),
            "e5xx": sum(per[s]["e5xx"] for s in allg), "e502": sum(per[s]["e502"] for s in allg), "code0": sum(per[s]["c0"] for s in allg),
            "p99_ms": p99(durs), "sec_p99_max_ms": max([x for x in sp if x is not None], default=None)}
    bw = plan["fixed"]["baseline_window"]
    bg = [s for s in secs if bw[0] <= rel(s) < bw[1]]
    out["baseline"] = {"window": bw, "stall": sum(1 for s in stall if bw[0] <= rel(s) < bw[1]) if load == "S4c" else None,
                       "e5xx": sum(per[s]["e5xx"] for s in bg), "e502": sum(per[s]["e502"] for s in bg)}
    d1f = plan["fixed"]["d1_from"]
    out["D1"] = sum(1 for s in stall if rel(s) >= d1f) if load == "S4c" else None
    out["D1_send"] = sum(1 for s in stall_send if rel(s) >= d1f) if load == "S4c" else None
    out["D3"] = sum(1 for s in server if t1_first is not None and rel(s) >= t1_first) if load == "S4c" else None
    p12 = plan["fixed"]["p12_window"]
    p12g = [s for s in grid if p12[0] <= rel(s) < p12[1]]
    out["P12"] = {"server_slow": sum(1 for s in server if p12[0] <= rel(s) < p12[1]), "observed": sum(1 for s in p12g if per.get(s, {}).get("n", 0) > 0)} if load == "S4c" else None
    out["stall_all"] = len(stall)
    out["stall_rel"] = [round(rel(s), 1) for s in stall][:400]
    out["stall_longest_all"] = longest(stall)
    return out


# ---- 가용성 탐침 -------------------------------------------------------------------------------------------------------------
def avail_rows(path):
    rows, dropped = [], []
    if not path.exists():
        return None, None
    with gzip.open(path, "rt", newline="") as f:
        head = f.readline().rstrip("\n").split(",")
        i_ts, i_val, i_tags = head.index("timestamp"), head.index("metric_value"), head.index("extra_tags")
        for line in f:
            if line.startswith("avail_req,"):
                c = line.rstrip("\n").split(",")
                if len(c) <= i_tags:
                    continue
                tags = dict(kv.split("=", 1) for kv in c[i_tags].split("&") if "=" in kv)
                if "t0" in tags:
                    rows.append({"sent_ms": float(tags["t0"]), "done_ms": float(c[i_ts]), "code": tags.get("code"), "dur": float(tags.get("dur") or 0)})
            elif line.startswith("dropped_iterations,"):
                c = line.split(",")
                dropped.append((float(c[i_ts]), float(c[i_val])))
    return rows, dropped


def avail_metrics(rows, dropped, T0, iv, windows, rate):
    if rows is None:
        return None
    fail = lambda r: r["code"] in ("0", "") or (r["code"] or "").startswith("5")
    out = {"by_interval": {}, "n": len(rows), "dropped": sum(v for _, v in dropped)}
    srel = lambda r: r["sent_ms"] / 1000 - T0
    for name in ("old", "trans", "new"):
        a, b = iv[name] if iv else (0, 0)
        rr = [r for r in rows if a <= srel(r) < b]
        dd = sum(v for t, v in dropped if a <= t / 1000 - T0 < b)
        out["by_interval"][name] = {"sent": len(rr), "ok": sum(1 for r in rr if r["code"] in ("201", "409")),
                                    "fail": sum(1 for r in rr if fail(r)), "e502": sum(1 for r in rr if r["code"] == "502"),
                                    "code0": sum(1 for r in rr if r["code"] in ("0", "")), "dropped": dd,
                                    "exhausted": sum(1 for r in rr if r["code"] == "seat-exhausted")}
    rows_s = sorted(rows, key=lambda r: r["sent_ms"])
    best = cur = 0.0
    start = None
    for r in rows_s:
        if fail(r):
            start = r["sent_ms"] if start is None else start
            cur = (r["sent_ms"] - start) / 1000 + 1 / rate
            best = max(best, cur)
        else:
            start = None
    out["longest_fail_send_s"] = round(best, 3)
    rows_c = sorted(rows, key=lambda r: r["done_ms"])
    best = 0.0
    start = None
    for r in rows_c:
        if fail(r):
            start = r["done_ms"] if start is None else start
            best = max(best, (r["done_ms"] - start) / 1000 + 1 / rate)
        else:
            start = None
    out["longest_fail_done_s"] = round(best, 3)
    out["fail"] = sum(1 for r in rows if fail(r))
    out["D2"] = out["fail"] + out["dropped"]
    # 전환 창 밖 부족(§5.3)
    k6a, k6b = 0.0, iv["new"][1] if iv else 0.0
    def in_win(t):
        return any(a <= t < b for a, b in windows)
    outside_len = sum(1 for k in range(int(k6a * 10), int(k6b * 10)) if not in_win(k / 10)) / 10
    sent_out = sum(1 for r in rows if not in_win(srel(r)) and k6a <= srel(r) < k6b) + sum(v for t, v in dropped if not in_win(t / 1000 - T0) and k6a <= t / 1000 - T0 < k6b)
    out["outside"] = {"len_s": outside_len, "planned": outside_len * rate, "sent_plus_dropped": sent_out,
                      "short": sent_out < AVAIL_MIN_SHARE * outside_len * rate}
    return out


# ---- 촘촘한 락 표본 ------------------------------------------------------------------------------------------------------------
def adr_windows(ev, lives, plan):
    """§4.5-2 창(회차 기준 초) — [T1 첫 사건 − 10, 마지막 lb 넣기 끝 + 30] · ④⑤ + [적용판 시작 − 10, 끝 + 30]."""
    fx = plan["fixed"]
    w = []
    t1 = [e["t0"] for e in ev if e["step"] == "T1"]
    adds = [e["t1"] for e in ev if e["op"] == "lb-add" and e["step"] != "setup" and e["result"] == "ok"]
    if t1:
        w.append((min(t1) - fx["mlock_before_s"], (max(adds) if adds else max(e["t1"] for e in ev if e["step"] == "T1")) + fx["mlock_after_s"]))
    pre = [e for e in ev if e["op"] == "pre-apply"]
    if pre:
        w.append((pre[0]["t0"] - fx["mlock_before_s"], pre[0]["t1"] + fx["mlock_after_s"]))
    return w


def mlock_metrics(d, clk, ev, lives, windows, pre_apply):
    rows = jsonl(d / "timeline-migration-locks.jsonl")
    if rows is None:
        return {"present": False, "missing_share": 1.0 if windows else 0.0}
    ts = sorted(clk.srv(r.get("t")) for r in rows if num(r.get("t")))
    cov = []
    for a, b in windows:
        exp = max(1, int((b - a) / MLOCK_STEP_S))
        got = sum(1 for t in ts if a <= t < b)
        cov.append({"window": [round(a, 1), round(b, 1)], "expected": exp, "got": got, "missing_share": max(0.0, 1 - got / exp)})
    miss = max((c["missing_share"] for c in cov), default=0.0)
    # 막은 쪽 분류: 적용판(seatlab-online-v9) · 마이그레이션(lb 편입 전 새·그림자 앱 세션 — Flyway는 앱 커넥션 이름) · 그 밖
    mig = {}
    for life, L in lives.items():
        if L.get("role") in ("new", "shadow") and L.get("start") is not None:
            mig[life] = (L["start"], L.get("lb_add", L.get("up", 1e18)))
    waits_by_v9, waits_by_mig, max_wait = {}, {}, 0.0
    lock_wait_max = 0
    for r in rows:
        t = clk.srv(r.get("t"))
        if t is None:
            continue
        lock_wait_max = max(lock_wait_max, r.get("lock_wait") or 0)
        bl = {b["pid"]: b.get("app") or "" for b in (r.get("blockers") or [])}
        for w in r.get("waiters") or []:
            if not BUSINESS.match(w.get("app") or ""):
                continue
            max_wait = max(max_wait, float(w.get("wait_s") or 0))
            apps = [bl.get(p, "") for p in (w.get("blk") or [])]
            key = (w.get("pid"), w.get("qs"))
            if "seatlab-online-v9" in apps:
                waits_by_v9[key] = max(waits_by_v9.get(key, 0.0), float(w.get("wait_s") or 0))
            for a in apps:
                m = re.match(r"^seatlab-([AB])-[0-9a-f]+-(new|shadow)$", a)
                if m:
                    for life, (s0, s1) in mig.items():
                        if life.startswith(m.group(1)) and s0 <= t <= s1:
                            waits_by_mig.setdefault(life, []).append(t)
    cic = {}
    for r in rows:
        for c in r.get("cic") or []:
            ph = c.get("phase")
            t = clk.srv(r.get("t"))
            cic.setdefault(ph, [t, t])
            cic[ph][1] = t
    step3 = ((pre_apply or {}).get("steps") or {}).get("3") or {}
    s3 = (clk.pc(step3.get("start_pc")), clk.pc(step3.get("end_pc"))) if step3.get("start_pc") else None
    in_step3 = 0
    if s3:
        for r in rows:
            t = clk.srv(r.get("t"))
            if t is None or not (s3[0] <= t <= s3[1]):
                continue
            bl = {b["pid"]: b.get("app") or "" for b in (r.get("blockers") or [])}
            if any(BUSINESS.match(w.get("app") or "") and "seatlab-online-v9" in [bl.get(p, "") for p in (w.get("blk") or [])] for w in r.get("waiters") or []):
                in_step3 += 1
    n6b_max = max(waits_by_v9.values(), default=0.0)
    return {"present": True, "samples": len(rows), "coverage": cov, "missing_share": miss, "lock_wait_max": lock_wait_max,
            "business_wait_max_s": round(max_wait, 3),
            "N6b": {"max_s": round(n6b_max, 3), "sum_s": round(sum(waits_by_v9.values()), 3), "sessions": len(waits_by_v9), "exceeded": n6b_max > N6B_S},
            "blocked_by_migration": {k: len(v) for k, v in waits_by_mig.items()},
            "step3_blocked_samples": in_step3,
            "cic_phases": {k: {"from": round(v[0], 2), "to": round(v[1], 2), "s": round(v[1] - v[0], 2)} for k, v in cic.items() if v[0] is not None}}


# ---- DB 기록 -------------------------------------------------------------------------------------------------------------------
def db_records(d, clk):
    db = load(d / "drift-db.json") or {}
    runs = [dict(r, s=clk.srv(iso(r["started_at"])), f=clk.srv(iso(r["finished_at"]))) for r in db.get("reconcile_runs") or []]
    gaps = [dict(g, at_r=clk.srv(iso(g.get("at"))), to_r=clk.srv(iso(g.get("to_at"))), to_srv=iso(g.get("to_at"))) for g in db.get("inc_gaps") or []]
    log = db.get("drift_log") or []
    repaired = {}
    for r in log:
        if r.get("action") != "repaired":
            continue
        repaired.setdefault((r["schedule_id"], r["user_id"]), []).append((iso(r["at"]), r.get("source")))
    for v in repaired.values():
        v.sort()
    return db, runs, gaps, log, repaired


def first_repair(repaired, s, u, after_srv):
    for at, src in repaired.get((s, u), []):
        if after_srv is None or at >= after_srv - 0.001:
            return at, src
    return None, None


# ---- 한도 탐침 ------------------------------------------------------------------------------------------------------------------
def probe_metrics(d, clk, plan, iv, instants, repaired, end_start_srv, outage_name):
    raw = jsonl(d / "probe.jsonl")
    if raw is None:
        return {"present": False}
    recs = {r["id"]: r for r in raw if r.get("kind") == "probe"}
    done = next((r for r in raw if r.get("kind") == "done"), None)
    planned = len(plan["probes"])
    out = {"present": True, "planned": planned, "records": len(recs), "done": done is not None, "probes": [], "nondet": [], "anomaly": []}
    harness_err = done is None or len(recs) < planned
    off = clk.off
    for p in plan["probes"]:
        r = recs.get(p["id"])
        x = {"id": p["id"], "cell": p["cell"], "type": p["kind"], "d": p["d"], "s": p["schedule_id"], "u": p["user_id"]}
        if r is None:
            x.update(status="missing")
            out["probes"].append(x)
            continue
        reqs = {q["name"]: q for q in r.get("requests") or []}
        errs = r.get("errors") or []
        if any(e.startswith(("inject", "snap", "driver")) for e in errs):
            harness_err = True
        inj = r.get("inject") or {}
        x["inject_srv"] = inj.get("at_db")
        x["inject_rel"] = clk.srv(inj.get("at_db"))
        dec = [reqs.get("hold2")] + ([reqs.get("hold3")] if p["kind"] == "L" else [])
        dec = [q for q in dec if q]
        x["decide_rel"] = clk.pc(max((q["done_pc"] for q in dec), default=None)) if dec else None
        codes = [q["code"] for q in r.get("requests") or []]
        h1 = reqs.get("hold1")
        x["codes"] = codes
        x["clean"] = bool(codes) and all(c in (201, 409) for c in codes)   # (마) 두 수의 모집단(CR13-11)
        if h1 is None or h1["code"] != 201 or x["inject_rel"] is None:
            t = clk.pc(h1["sent_pc"]) if h1 else None
            x["status"] = "outage" if outage_name and plan13.interval_of(t, iv) == "trans" else ("failed-response" if h1 and h1["code"] != 201 else "harness-error")
            out["probes"].append(x)
            continue
        if len(dec) < (2 if p["kind"] == "L" else 1) or any(q["code"] not in (201, 409) for q in dec):
            x["status"] = "outage" if outage_name and plan13.interval_of(x["inject_rel"], iv) == "trans" else "failed-response"
        name, bnd, crossed = plan13.classify_probe(x["inject_rel"], x["decide_rel"], iv, instants)
        x["interval_inject"] = plan13.interval_of(x["inject_rel"], iv)
        x["interval"] = name
        x["boundary"] = bnd
        x["crossed"] = crossed
        # 맞음·틀림
        verdict = None
        if p["kind"] == "H" and reqs.get("hold2"):
            c, bc = reqs["hold2"]["code"], reqs["hold2"].get("body_code")
            verdict = "right" if c == 201 else ("wrong" if c == 409 and bc == "HOLD_LIMIT_EXCEEDED" else "anomaly")
        elif p["kind"] == "L" and reqs.get("hold2") and reqs.get("hold3"):
            c2, c3, b3 = reqs["hold2"]["code"], reqs["hold3"]["code"], reqs["hold3"].get("body_code")
            if c2 != 201:
                verdict = "anomaly"
            else:
                verdict = "right" if (c3 == 409 and b3 == "HOLD_LIMIT_EXCEEDED") else ("wrong" if c3 == 201 else "anomaly")
        x["verdict"] = verdict
        if verdict == "anomaly" and "status" not in x:
            out["anomaly"].append(p["id"])
        if "status" not in x:
            if outage_name and name == "trans":
                x["status"] = "outage"
            elif bnd:
                x["status"] = "boundary"
            elif verdict in ("right", "wrong"):
                x["status"] = "decision"
            else:
                x["status"] = "anomaly"
        # N10 결정성 — 스냅숏마다(판정 요청 직후)
        cum = 0
        nd = []
        for q in r.get("requests") or []:
            if q["code"] == 201:
                cum += 1
            sn = next((s for s in r.get("snaps") or [] if s.get("after") == q["name"]), None)
            if sn is None:
                continue
            act = (sn.get("holds") or 0) + (sn.get("confirmed") or 0)
            x.setdefault("snaps", []).append({"after": q["name"], "cnt": sn.get("cnt"), "actual": act, "expect": cum})
            if sn.get("holds") is not None and act != cum:
                wins = [(w["sent_pc"] + off - 0.25, w["done_pc"] + off + 0.25) for w in r["requests"] if w["code"] == 201]
                rows_in = all(any(a <= h["held_at_db"] <= b for a, b in wins) for h in sn.get("hold_rows") or [])
                recount = sum(1 for w in r["requests"][: r["requests"].index(q) + 1] if w["code"] == 201)
                harness = any(w["code"] not in (201, 409) for w in r["requests"]) or (recount == act and rows_in)
                nd.append({"after": q["name"], "actual": act, "expect": cum, "class": "harness" if harness else "integrity"})
        if nd:
            out["nondet"].append({"id": p["id"], "items": nd})
        # 탐침 쌍 한도 초과(스냅숏 실제 > 2 — §4.7.1 S5-1 ⓐ)
        last = (x.get("snaps") or [{}])[-1]
        x["snap_over_limit"] = (last.get("actual") or 0) > 2
        fa, fs = first_repair(repaired, p["schedule_id"], p["user_id"], x["inject_srv"])
        x["first_repair_s"] = round(fa - x["inject_srv"], 3) if fa else None
        x["first_repair_src"] = fs
        x["repaired_by_end"] = fa is not None and (end_start_srv is None or fa <= end_start_srv)
        out["probes"].append(x)
    out["harness_error"] = harness_err
    # 실행기 완료(CR13-15): 끝 행 · 계획 = 끝낸 수 = 기록 수 — 최소 수를 채웠어도 아니면 무효(빠진 탐침이 판정에서 조용히 사라지지 않게)
    inc = []
    if done is None:
        inc.append("done 행 없음")
    else:
        if done.get("planned") != planned:
            inc.append(f"done.planned {done.get('planned')} ≠ 계획 {planned}")
        if done.get("finished") != planned:
            inc.append(f"done.finished {done.get('finished')} ≠ 계획 {planned}")
    if len(recs) != planned:
        inc.append(f"탐침 기록 {len(recs)} ≠ 계획 {planned}")
    out["incomplete"] = inc
    return out


def probe_tables(pm, proc):
    """회차 하나: O · Q · T · N · J 수(짝 제외 뒤) · 칸별 틀림 · J 값 · 최소 수 미달.
    J 모집단(§5.8-3a 정정 — 측정 전 · CR13-19): 전환 칸(T)만 — (d, 종류)마다 실제 구간이 전환인 전환 칸 판정 탐침(T)과 같은 (d, 종류) 단독(옛) 칸 판정 탐침(O)의 짝.
      J = Σ(T 틀림 − O 틀림) ÷ 짝 수(§5.8-3 문자 그대로). 단독(새) 칸은 들지 않는다. ①은 전환 구간에 섞임이 없어 J = 0(정의 · J 수 = None · 최소 수 묻지 않음).
    N(P9 (가) · 최소 수 — CR13-9) = 실제 구간 단독(새)의 판정 탐침(칸 무관 · 경계 아님) · d ∈ {30, 75}.
    (마) 두 수(스냅숏 > 2 · L 선점 3 = 201 — CR13-11) = 요청 응답이 모두 201·409인 탐침만. 코드 0·5xx가 섞인 탐침은 unclean으로 따로 남긴다."""
    P = {x["id"]: x for x in pm.get("probes") or []}
    by = {}
    for x in P.values():
        by[(x["cell"], x["d"], x["type"])] = x
    dec = lambda x, iv: x is not None and x.get("status") == "decision" and x.get("interval") in iv
    wrong = lambda x: int(x["verdict"] == "wrong")
    cells = {}
    paired_T = o_n = j_n = jsum = 0
    for d in D_SET:
        for k in KINDS:
            o = by.get(("old", d, k))
            t = by.get(("trans", d, k))
            o_ok = dec(o, ("old",))
            o_n += o_ok
            t_ok = dec(t, ("trans",)) if proc != 1 else False
            c = {"O_ok": o_ok, "T_ok": t_ok}
            if proc != 1 and o_ok and t_ok:
                c["pair_T"] = True
                c["O_wrong"] = wrong(o)
                c["T_wrong"] = wrong(t)
                paired_T += 1
                j_n += 1
                jsum += c["T_wrong"] - c["O_wrong"]
            cells[f"{d}{k}"] = c
    q = [x for x in P.values() if x["cell"] == "old" and x["d"] in (1, 5) and dec(x, ("old",))]
    n = [x for x in P.values() if x["d"] in (30, 75) and dec(x, ("new",))]
    nH = sum(1 for x in n if x["type"] == "H")
    nL = sum(1 for x in n if x["type"] == "L")
    counts = {"O": paired_T if proc != 1 else o_n, "Q": len(q), "T": paired_T if proc != 1 else None, "N": len(n), "N_H": nH, "N_L": nL,
              "J": j_n if proc != 1 else None}
    short = []
    for k in ("O", "Q", "T", "N", "N_H", "N_L", "J"):
        if k in ("T", "J") and proc == 1:
            continue
        if (counts[k] or 0) < MIN[k]:
            short.append(f"{k}={counts[k]}<{MIN[k]}")
    l_over = lambda x: x["type"] == "L" and len(x.get("codes") or []) >= 3 and x["codes"][2] == 201
    clean = [x for x in P.values() if x.get("clean")]
    unclean = [x for x in P.values() if x.get("codes") and not x.get("clean") and (x.get("snap_over_limit") or l_over(x))]
    return {"cells": cells, "counts": counts, "short": short,
            "Q_wrong": sum(1 for x in q if x["verdict"] == "wrong"), "Q_n": len(q),
            "N_wrong": sum(1 for x in n if x["verdict"] == "wrong"),
            "J": 0.0 if proc == 1 else (round(jsum / j_n, 4) if j_n else None),
            "boundary": [x["id"] for x in P.values() if x.get("status") == "boundary"],
            "L_over_results": sum(1 for x in clean if l_over(x)),
            "snap_over_limit": sum(1 for x in clean if x.get("snap_over_limit")),
            "unclean_over": [{"id": x["id"], "codes": x["codes"], "snap_over_limit": bool(x.get("snap_over_limit"))} for x in unclean],
            "unclean_snap_over": sum(1 for x in unclean if x.get("snap_over_limit"))}


# ---- 회차 판정 -----------------------------------------------------------------------------------------------------------------
def rep_summary(d):
    d = Path(d)
    plan = load(d / "plan.json")
    if plan is None:
        return {"status": "invalid:plan-missing"}, "invalid:plan-missing"
    proc, load_ = plan["proc"], plan["load"]
    clk = Clock(d)
    harness, procedure, flags, notes = [], [], [], []
    for line in (d / "harness-reasons.txt").read_text().splitlines() if (d / "harness-reasons.txt").exists() else []:
        if line.strip():
            harness.append(line.strip())
    if clk.T0 is None:
        harness.append("t0-missing")
        return {"status": "invalid:t0-missing"}, "invalid:t0-missing"
    if not clk.off_known:
        harness.append("clock-offset-missing")
    ev, samplers, rows = load_events(d, clk)
    win = load(d / "k6-window.json") or {}
    k6_end_rel = clk.pc(win.get("end_pc")) or plan["timeline"]["k6_s"]
    pv = plan13.intervals([dict(e, t0=e["t0"], t1=e["t1"]) for e in ev if e["step"] != "setup" and e["t0"] is not None], k6_end_rel)
    lives = lives_of(ev)
    t1_first = pv["trans"][0] if pv else None

    def collect_at(life):
        """그 생애 산출물(지표·로그·GC)을 받은 시각 — 내림 직전 수집 사건 시작 또는 회차 끝 final-collect(없으면 None = 받지 못함)."""
        c = (lives.get(life) or {}).get("collect")
        if c is None:
            fc = next((r for r in rows if r.get("kind") == "final-collect" and (r.get("detail") or {}).get("life") == life), None)
            c = clk.pc(fc["t_pc"]) if fc and num(fc.get("t_pc")) else None
        return c
    # 전환 실패(H3 · §5.3)
    tf = load(d / "transition-failed.json")
    ec = load(d / "events-check.json")
    # 전환 실패 시각(CR13-18) = 첫 실패 사건(result ≠ ok)의 시작 — 그 뒤에 생기는 산출물 결측은 hpost로(아래 끝에서 나눈다). 실패 사건 행이 없으면 모름(None → 모든 hpost를 뒤로 본다)
    fail_ev = sorted((e for e in ev if e.get("result") not in (None, "ok") and e["t0"] is not None), key=lambda e: e["t0"])
    tf_rel = fail_ev[0]["t0"] if (tf and fail_ev) else None
    post = []   # (사유, 그 산출물이 생기는 회차 기준 초 | None = 회차 끝·모름)

    def hpost(reason, t):
        post.append((reason, t))
    if tf:
        (procedure if tf.get("cause") != "harness" else harness).append(f"transition-failed:{tf.get('reason')}")
    elif ec is None:
        harness.append("events-check-missing")
    elif not ec.get("ok"):
        harness.append("transition-failed:events-check")   # 오케스트레이터 몫(사건 누락 · 순서 뒤바뀜 · 끝 상태 다름 — 실패 사건 행 없이)
    # DB 기록
    db, runs, gaps, dlog, repaired = db_records(d, clk)
    if not db:
        hpost("drift-db-missing", None)   # 회차 끝 덤프
    end = load(d / "end-check.json")
    fa = load(d / "final-app.json") or {}
    # 끝 검사 앱(H6)이 계획(plan end_state.final_new)과 다르다(CR13-참고): run.sh가 계획 앱이 health를 못 내면 살아 있는 다른 앱으로 끝 검사를 돌린다 —
    #   계획한 마지막 새 앱이 회차 끝에 살아 있지 않은 것은 절차 이행 실패(P10)라 절차 사유로 드러낸다(원인을 가르지 못한 실패는 절차 — §5.3). 파일 없음 = 하네스.
    if not fa:
        hpost("final-app-missing", None)   # 회차 끝
    elif fa.get("final_app") != plan["end_state"]["final_new"]:
        procedure.append("final-app-mismatch")
    end_start_srv = (fa.get("end_check_start_pc") or 0) + clk.off if fa.get("end_check_start_pc") else None
    # 파생 소유 변경 순간: 새 앱 첫 증분 틱 · 옛 앱 마지막 C 바퀴 끝(옛 앱 내림 전)
    derived = []
    old = next((L for k, L in lives.items() if L.get("role") == "old"), {})
    for life, L in lives.items():
        if L.get("role") == "new" and L.get("start") is not None:
            fi = sorted((r for r in runs if str(r["source"]).startswith("inc-") and r["s"] is not None and r["s"] >= L["start"] - 1), key=lambda r: r["s"])
            if fi:
                derived.append((fi[0]["s"], f"first-inc-tick:{life}"))
                L["first_inc"] = fi[0]
    c_runs = sorted((r for r in runs if r["source"] == "reconcile-app" and r["f"] is not None and (old.get("stop_start") is None or r["f"] <= old["stop_start"])), key=lambda r: r["f"])
    if c_runs:
        derived.append((c_runs[-1]["f"], "last-c-round-end"))
    instants = plan13.owner_instants([e for e in ev if e["step"] != "setup"], derived)
    out = {"proc": proc, "load": load_, "rep_dir": str(d), "T0": clk.T0, "offset_s": clk.off, "intervals": pv, "lives": lives,
           "outage": [round(x, 3) for x in pv["trans"]] if (pv and proc == 1) else None}
    # k6 주 부하
    per = main_csv(d / "k6-requests.csv.gz", clk.T0)
    if per is None:
        harness.append("k6-csv-missing")
        km = None
    else:
        km = k6_metrics(per, clk.T0, pv, load_, k6_end_rel, t1_first, plan) if pv else None
        out["k6"] = km
        if km:
            bl = km["baseline"]
            if (bl["stall"] or 0) > 0 or bl["e5xx"] > 0 or bl["e502"] > 0:
                harness.append("baseline-unstable")   # §5.3 S5-7 — 단독(옛) 기준 창 [60, 90)
    # 창(§4.5-2)
    windows = adr_windows([e for e in ev if e["step"] != "setup"], lives, plan)
    out["windows"] = windows
    # 가용성 탐침
    rows_a, dropped = avail_rows(d / "k6-avail.csv.gz")
    if rows_a is None:
        harness.append("avail-csv-missing")
    else:
        am = avail_metrics(rows_a, dropped, clk.T0, pv, windows, plan["avail"]["rate"])
        out["avail"] = am
        if am and am["outside"]["short"]:
            harness.append("avail-short-outside-window")
    # 락 표본
    pre = load(d / "pre-apply.json")
    ml = mlock_metrics(d, clk, ev, lives, windows, pre)
    out["mlock"] = ml
    if windows and ml["missing_share"] > MLOCK_MISS:
        harness.append("mlock-samples-missing")
    if ml.get("N6b", {}).get("exceeded"):
        flags.append("pre-apply-blocking-exceeded")
    # 적용판(④⑤)
    if proc in (4, 5):
        out["pre_apply"] = {"result": (pre or {}).get("result"), "method": ((pre or {}).get("record") or {}).get("method"),
                            "cli_class": (((pre or {}).get("record") or {}).get("cli") or {}).get("class"),
                            "steps": {k: {kk: v.get(kk) for kk in ("tries", "lock_timeouts", "wall_s", "drops", "skipped")} for k, v in ((pre or {}).get("steps") or {}).items()},
                            "m": (pre or {}).get("m")}
        # pre-apply-late(§4.2) = 적용판이 T1 계획 시각까지 끝나지 않아 T1을 미룬 초(적용판 끝 − T1 계획 시각). T1 첫 사건의 late_s는
        #   오케스트레이터 지연(약 0.5초)이 섞여 적용판이 일찍 끝나도 문턱을 넘었다(.164 스모크 2026-10-24 — 적용판 끝 100초 · late_s 0.505)
        pa_ev = [e for e in ev if e["op"] == "pre-apply" and e["t1"] is not None]
        t1_at = next((s_["at_s"] for s_ in plan["steps"] if s_["name"] == "T1"), None)
        late = max(0.0, pa_ev[0]["t1"] - t1_at) if pa_ev and t1_at is not None else 0.0
        out["pre_apply"]["late_s"] = late
        if late > 0:
            flags.append("pre-apply-late")
        if pre and pre.get("record") and pre["record"].get("cli") and pre["record"]["cli"].get("class") not in ("ok", None):
            notes.append(f"기록 ① 분류 {pre['record']['cli']['class']}")
    # 탐침
    pm = probe_metrics(d, clk, plan, pv, instants, repaired, end_start_srv, proc == 1)
    out["probe"] = pm
    if pm.get("present") and pm.get("incomplete"):
        harness.append("probe-incomplete")   # CR13-15 — 실행기 끝 행·계획 대 끝낸 수·기록 수(최소 수 충족과 무관)
    if pm.get("present"):
        pt = probe_tables(pm, proc)
        out["probe_tables"] = pt
        nd_h = [n for n in pm["nondet"] if any(i["class"] == "harness" for i in n["items"])]
        nd_i = [n for n in pm["nondet"] if all(i["class"] == "integrity" for i in n["items"])]
        if nd_h:
            harness.append("probe-nondeterministic")   # 하네스 분류 — 그 부하 15회 전체(캠페인이 멈추고 보고 — §5.3 · §5.7-5)
        out["probe_nondet_integrity"] = len(nd_i)
        # 예상 밖 판정 응답(CR13-3): 응답이 모두 201·409인데 맞음·틀림 모양이 아니다(H 선점 2 = 409 다른 코드 · L 선점 2 ≠ 201 · L 선점 3 = 409 다른 코드).
        #   §5.3 N10 기계적 분류와 같은 기준 — 하네스 몫은 '201·409가 아닌 응답이 있을 때'뿐이고(서버 커밋 여부를 모름) 그 밖은 서버가 확정해 답한 것이라
        #   앱 판정 결함 = 정합 분류다. 회차는 유효로 두고 캠페인 P9 (바) 반증 항목으로 센다(판정에서 빠져 숨지 않게 — 무음 금지).
        out["probe_anomaly"] = list(pm.get("anomaly") or [])
        if out["probe_anomaly"]:
            flags.append("probe-anomaly")
        if pm["harness_error"]:
            harness.append("probe-error")   # 루프 3 — 주입·스냅숏·실행기 오류가 한 건이라도 있으면 최소 수와 관계없이 하네스 무효(그 탐침이 집계에서 조용히 빠지지 않게)
        if pt["short"]:
            if pm["harness_error"]:
                harness.append("probe-short")
            else:
                procedure.append("probe-short-by-procedure")   # §5.8-5 — 절차 사건 지연으로 경계로 빠짐 = 그 절차의 실패(P9 반증)
    else:
        harness.append("probe-missing")
    # 소유 넘김(N4 · P5 · P9 (아))
    S = None
    if c_runs:
        S = c_runs[-1]["s"]
    newL = next((L for k, L in sorted(lives.items()) if L.get("role") == "new"), None)
    m_val = None
    if end and end.get("m"):
        m_srv = pg_ts(end["m"])
        if m_srv is None:
            hpost("m-parse-failed", None)   # CR13-28 — m이 있는데 못 읽으면 L*를 restart 하한만으로 줄여 보지 않는다(끝 검사 산출물)
        else:
            m_val = clk.srv(m_srv)
    restart = None
    if newL and newL.get("start") is not None:
        rr = sorted((g for g in gaps if g.get("kind") == "restart" and g["at_r"] is not None and g["at_r"] >= newL["start"] - 1), key=lambda g: g["at_r"])
        restart = rr[0] if rr else None
    Lstar = None
    if restart and restart.get("to_r") is not None:
        Lstar = max(restart["to_r"], m_val) if m_val is not None else restart["to_r"]
    own = {"S": S, "L_star": Lstar, "m": m_val, "restart_to": restart.get("to_r") if restart else None,
           "uncovered_s": max(0.0, Lstar - S) if (S is not None and Lstar is not None) else None}
    if own["uncovered_s"] and per is not None:
        a, b = S, Lstar
        own["writes_in_gap"] = sum(e["c201"] for s, e in per.items() if a <= s + 0.5 - clk.T0 < b)
    if own["uncovered_s"]:
        flags.append("owner-uncovered-s")
        gap_probes = [x for x in pm.get("probes") or [] if x.get("inject_rel") is not None and S <= x["inject_rel"] < Lstar]
        own["gap_probes"] = len(gap_probes)
        own["gap_probes_unrepaired"] = sum(1 for x in gap_probes if not x.get("repaired_by_end"))
    out["owner"] = own
    # §4.11 ② page_full(찬 페이지 수 — 판정 자료) — 새 앱 생애마다 마지막 수집 지표(COUNT). P7 이어 읽기 끝 판정이 쓴다(CR13-참고)
    page_full = {}
    for life, L in lives.items():
        if L.get("role") != "new":
            continue
        a = load(d / f"app-{life}-actuator.json") or {}
        for n, m in (a.get("metrics") or {}).items():
            if n.endswith(".page_full") and m:
                page_full[life] = next((x.get("value") for x in m.get("measurements") or [] if x.get("statistic") == "COUNT"), 0) or 0
                if page_full[life] > 0:
                    flags.append("page_full")
    # P7(첫 틱 — §4.11) — 판정 자료 결측은 하네스 사유
    p7 = {"applicable": newL is not None}
    if page_full:
        p7["page_full"] = page_full
    if newL:
        fi = newL.get("first_inc")
        new_life = next(k for k, L in lives.items() if L is newL)
        cfg = load(d / f"app-{new_life}-config.json") or {}
        hold = next((b.get("properties") or {} for b in cfg.get("configprops") or [] if b.get("prefix") == "seat.hold"), {})
        ps = (((hold.get("counterDrift") or {}).get("reconcile") or {}).get("incremental") or {}).get("pageSize") or 100000
        if fi is None or restart is None:
            hpost("p7-data-missing", newL.get("up"))   # 새 앱 UP 뒤에 생기는 자료(UP 못 했으면 None)
            p7["missing"] = [x for x, v in (("first-inc", fi), ("restart", restart)) if v is None]
        else:
            ticks = sorted((r for r in runs if r["source"] == fi["source"] and r["s"] is not None and r["s"] >= fi["s"]), key=lambda r: r["s"])
            # 이어 읽기 끝(CR13-참고): 찬 페이지 = 행 수(pairs_scanned — 읽은 행 + 되돌림 재시도 쌍) ≥ page-size **이고** 그 생애 page_full 지표 몫이 남았을 때만.
            #   pairs_scanned만 보면 되돌림 재시도 쌍이 더해진 안 찬 틱도 찬 것으로 이어 붙는다. 지표가 없으면 행 수만(출처 기록).
            pf_left = page_full.get(new_life)
            p7["carry_end_source"] = "page_full+rows" if pf_left is not None else "rows"
            carry = []
            for r in ticks:
                carry.append(r)
                full = (r.get("pairs_scanned") or 0) >= ps and (pf_left is None or pf_left > 0)
                if not full:
                    break
                if pf_left is not None:
                    pf_left -= 1
            W = carry[-1]["s"] - restart["to_r"] if restart.get("to_r") is not None else None
            tot = sum(r.get("pairs_scanned") or 0 for r in carry)
            p7.update(page_size=ps, carry_ticks=len(carry), first_pairs=fi.get("pairs_scanned"), carry_pairs=tot, W_s=W,
                      first_tick_s=(fi["f"] - fi["s"]) if fi.get("f") is not None else None, m_missing="m 없음" in str(restart.get("detail") or ""),
                      restart_detail=restart.get("detail"))
            refute = []
            if load_ == "S4c" and W is not None and tot > 1.5 * 4200 * max(W, 0):
                refute.append("후보 합계 > 1.5 × 4,200 × W")
            if p7["m_missing"]:
                refute.append("restart detail 'm 없음'")
            if len(carry) > 60:
                refute.append("carry_ticks > 60")
            p7["refuted"] = refute
    out["P7"] = p7
    clamped = sum(1 for g in gaps if g.get("kind") == "window_clamped")
    if clamped:
        flags.append("window_clamped")
    # N2 · N3(②⑤)
    n23 = []
    if proc in (2, 5):
        t2 = [e["t0"] for e in ev if e["step"] == "T2"]
        t2f = min(t2) if t2 else None
        if t2f is not None:
            bad = [r for r in runs if (str(r["source"]).startswith("inc-") or str(r["source"]).startswith("safety-")) and r["s"] is not None and r["s"] < t2f]
            if bad:
                n23.append(f"N2: T2 전 증분·안전망 행 {len(bad)}")
        if old.get("stop_end") is not None:
            bad = [r for r in runs if r["source"] == "reconcile-app" and r["s"] is not None and r["s"] > old["stop_end"]]
            if bad:
                n23.append(f"N2: 옛 앱 내림 뒤 reconcile-app 행 {len(bad)}")
        for life, L in lives.items():
            if L.get("role") == "shadow":
                a = load(d / f"app-{life}-actuator.json") or {}
                names = a.get("names") or []
                bad = [n for n in names if n.startswith("seat.hold.counter.reconcile.incremental") or n.startswith("seat.hold.counter.safety")]
                if a.get("names") is None:
                    hpost(f"shadow-metrics-missing:{life}", collect_at(life))
                elif bad:
                    n23.append(f"N3: 그림자 앱 {life} 지표 {bad[:3]}")
    out["N2N3"] = n23
    # H4 앱별 수집(CR13-5): UP한 생애마다 수집(내림 직전 collect 사건 또는 회차 끝 final-collect) · 수집 오류 0 · 지표·configprops · 앱 로그 · GC 로그.
    #   결측 = 하네스 사유(P10 GC · P11 · P7 · N3 판정 자료). 전환 실패 회차(transition-failed)는 뒤 사건이 그 결과라 묻지 않는다 — 절차 실패를 하네스 무효로 덮지 않게.
    collected = {}
    for e in ev:
        if e["op"] == "collect" and e["result"] == "ok" and e.get("life"):
            collected[e["life"]] = (e.get("detail") or {}).get("errors")
    for r in rows:
        if r.get("kind") == "final-collect" and (r.get("detail") or {}).get("life"):
            collected[r["detail"]["life"]] = r["detail"].get("errors")
    errs_of = lambda v: [x for x in (v.split() if isinstance(v, str) else (v or [])) if x]
    h4 = {}
    for life, L in lives.items():
        if L.get("up") is None:
            continue
        miss = []
        if life not in collected:
            miss.append("collect")
        else:
            miss += [f"collect-{x}" for x in errs_of(collected[life])]
            a = load(d / f"app-{life}-actuator.json")
            if not a or a.get("names") is None or a.get("configprops") is None:
                miss.append("actuator")
            try:
                if not gzip.open(d / f"app-{life}.log.gz", "rt", errors="replace").read(1):
                    miss.append("app-log")
            except OSError:
                miss.append("app-log")
        h4[life] = sorted(set(miss))
        if miss:
            hpost(f"app-collect-missing:{life}:{'+'.join(h4[life])}", collect_at(life))
    out["H4"] = h4
    # GC(P10 — 앱마다 창 [max(UP + 60, k6 시작 + 60), 내림 직전 수집]) — 요약 없음·빈 로그·창 없음 = gc-log-missing(CR13-4 · 무음 통과 금지)
    gcs = {}
    for life, L in lives.items():
        if L.get("up") is None:
            continue
        gz = d / f"app-{life}-gc.log.gz"
        w0 = max(L["up"] + 60, 60.0)
        col = collect_at(life)
        if gc_summary9 is None or not gz.exists() or col is None or col <= w0:
            gcs[life] = None if (gc_summary9 is None or not gz.exists()) else {"window": None}
            hpost(f"gc-log-missing:{life}", col)
            continue
        g = gc_summary9(gz, window=(w0 + clk.T0 + clk.off, col + clk.T0 + clk.off), steady_from=w0 + clk.T0 + clk.off)
        if not g or not g.get("lines"):
            gcs[life] = {"window": [round(w0, 1), round(col, 1)], "lines": 0}
            hpost(f"gc-log-missing:{life}", col)
            continue
        k = g.get("k6") or {}
        gcs[life] = {"window": [round(w0, 1), round(col, 1)], "full": k.get("full"), "stalls": k.get("stalls"), "stop_ms_max": k.get("stop_ms_max"),
                     "collector": (g or {}).get("collector"),
                     "violation": bool(k) and ((k.get("full") or 0) > 0 or (k.get("stalls") or 0) > 0 or (k.get("stop_ms_max") or 0) >= 10)}
    out["gc"] = gcs
    # 옛 앱 로그(P11) — 결측은 하네스 사유
    p11 = {}
    for life, L in lives.items():
        if L.get("role") != "old":
            continue
        gz = d / f"app-{life}.log.gz"
        try:
            txt = gzip.open(gz, "rt", errors="replace").read()
        except OSError:
            txt = ""
        if not txt:
            hpost(f"old-app-log-missing:{life}", collect_at(life))
            continue
        p11[life] = {"cached_plan": txt.count("cached plan must not change result type"),
                     "error_lines": sum(1 for line in txt.splitlines() if " ERROR " in line)}
        if p11[life]["cached_plan"] or p11[life]["error_lines"]:
            flags.append("old-app-errors")
    out["P11"] = p11
    # 끝 검사(P9 (다)(마)(사))
    p9 = {}
    if end is None:
        hpost("end-check-missing", None)   # 회차 끝
    else:
        p9["mismatch_all"] = end.get("mismatch_all")
        p9["over_limit_main"] = end.get("over_limit_main")
        p9["over_limit_probe"] = end.get("over_limit_probe")
        sch = []
        if (end.get("updated_at_null") or 0) > 0:
            sch.append("updated_at NULL")
        if not end.get("trigger") or end["trigger"].get("enabled") != "O":
            sch.append("트리거 없음·비활성")
        if not end.get("index") or not (end["index"].get("valid") and end["index"].get("ready")):
            sch.append("인덱스 유효 아님")
        ref = load(d / "ref-history-all.json") or []
        refck = {r["version"]: r["checksum"] for r in ref if r.get("version") in ("9", "10")}
        got = {r["version"]: r["checksum"] for r in end.get("flyway") or [] if r.get("version") in ("9", "10") and r.get("success")}
        for v in ("9", "10"):
            if v not in got or refck.get(v) is None or got[v] != refck[v]:
                sch.append(f"V{v} 이력 {got.get(v)} ≠ ref {refck.get(v)}")
        p9["schema"] = sch
        p9["m_rows_post_v9"] = end.get("m_rows_post_v9")
        out["flyway"] = [{k: r.get(k) for k in ("version", "execution_time", "installed_rank", "success")} for r in end.get("flyway") or [] if r.get("version") in ("9", "10")]
    cons = load(d / "consistency.json")
    if cons is None:
        crc = (d / "consistency.rc").read_text().strip() if (d / "consistency.rc").exists() else ""
        if crc == "28":
            flags.append("end-check-timeout")   # 끝 검사 시한 초과(curl 28)만 → 그 회차 P9 판정 불가(§5.2 P9 판정 불가 칸)
        elif not crc:
            harness.append("end-check-app-failed")   # 파일도 원인도 없다 — 측정 프로세스 실패로 본다(루프 3)
        # 그 밖의 rc는 run.sh가 hreason end-check-app-failed를 남겼다(하네스 → 재측정 · 전환 실패 회차면 END_HREASONS 규칙으로 기록만)
    else:
        p9["v_counter_mismatch"] = cons.get("v_counter_mismatch")
    # 주입(S3e — P9 (나) · J3 · (차))
    inj_pairs = set()
    if load_ == "S3e":
        inj = load(d / "injected.json") or {}
        ep = load(d / "end-pairs.json") or {}
        if not inj:
            hpost("injected-missing", None)   # 회차 끝에 모은다
        rounds = [r for r in inj.get("rounds") or [] if r.get("ok")]
        if len(rounds) < len((inj.get("plan") or {}).get("rounds") or []):
            okat = {r.get("at_s") for r in rounds}
            miss_at = [r.get("at_s") for r in (inj.get("plan") or {}).get("rounds") or [] if r.get("at_s") not in okat]
            hpost("inject-round-failed", min((x for x in miss_at if num(x)), default=None))   # 빠진 첫 차례 시각
        j3, j3_missing = [], 0
        for r in rounds:
            for p in r.get("pairs") or []:
                key = (p["schedule_id"], p["user_id"])
                inj_pairs.add(key)
                if r["at_s"] in (450, 630) and p["kind"] in ("H", "L1", "L2"):
                    a, _ = first_repair(repaired, key[0], key[1], r.get("at_epoch"))
                    if a is None:
                        j3_missing += 1
                    else:
                        j3.append(a - r["at_epoch"])
        p9["inject_end_bad"] = sum(1 for x in ep.get("injected") or [] if (x.get("cnt") or 0) != (x.get("actual") or 0))
        p9["J3"] = round(max(j3), 3) if j3 else None
        p9["J3_missing"] = j3_missing
        out["t2_mismatch"] = (load(d / "t2-mismatch.json") or {}).get("mismatch")
    # 오탐 복구(주입·탐침 밖 쌍의 repaired — ADR-009 효과 정의) · false-positive 기록 수(출처별)
    probe_pairs = {(x["s"], x["u"]) for x in pm.get("probes") or []}
    outside = [k for k in repaired if k not in inj_pairs and k not in probe_pairs]
    p9["repairs_outside"] = len(outside)
    fp = {}
    for r in dlog:
        if r.get("action") == "false-positive":
            fp[r.get("source")] = fp.get(r.get("source"), 0) + 1
    out["false_positive_by_source"] = fp
    out["P9_rep"] = p9
    # 대조 비용(기록): 출처별 바퀴 수·중앙
    rs = {}
    for r in runs:
        if r["s"] is None or r["f"] is None:
            continue
        rs.setdefault(r["source"], []).append(r["f"] - r["s"])
    out["reconcile"] = {k: {"n": len(v), "p50_s": sorted(v)[len(v) // 2], "max_s": max(v)} for k, v in rs.items()}
    # 하네스 산출물 결측 나누기(CR13-18): 전환 실패가 있으면 실패 시각 뒤(또는 시각 모름)의 산출물 결측은 기록만 — 앞의 것은 하네스 사유
    deferred = []
    for reason, t in post:
        if tf and (tf_rel is None or t is None or t >= tf_rel):
            deferred.append(reason)
        else:
            harness.append(reason)
    run_hr = [h for h in harness if h in END_HREASONS]   # run.sh 회차 끝 단계 실패(harness-reasons.txt) — 같은 규칙
    if tf:
        deferred += run_hr
        harness = [h for h in harness if h not in END_HREASONS]
    out["harness_deferred"] = sorted(set(deferred))
    out["transition_failed_at"] = tf_rel
    if deferred:
        notes.append(f"전환 실패({tf.get('reason')}) 뒤 산출물 결측 — 하네스로 묻지 않음: {sorted(set(deferred))}")
    # 상태 — 하네스(전환 실패 앞) > 절차 > ok
    harness = sorted(set(harness))
    procedure = sorted(set(procedure))
    status = ("invalid:" + ",".join(harness)) if harness else (("fail:" + ",".join(procedure)) if procedure else "ok")
    out.update(harness_reasons=harness, procedure_reasons=procedure, flags=sorted(set(flags)), notes=notes, status=status)
    (d / "rep-summary.json").write_text(json.dumps(out, ensure_ascii=False, indent=1, default=str))
    return out, status


# ---- 고르는 규칙(§5.5) -------------------------------------------------------------------------------------------------------------
def _lower(a, b):
    """a 범위가 b보다 비겹침으로 낮다(a 최대 < b 최소). 값 없으면 관계 없음."""
    if not a or not b:
        return False
    return max(a) < min(b)


def eliminate(cands, metrics, vals):
    """단 하나: Y → X 화살표 = Y가 어떤 지표에서 X보다 낮고 어느 지표에서도 높지 않다. 들어오는 화살표 없는 후보가 남는다.
    아무도 안 남으면 순환에 든 후보만(§5.5 S5-12). → (남은 후보, 화살표 목록)."""
    edges = []
    for y in cands:
        for x in cands:
            if x == y:
                continue
            lo = any(_lower(vals[y].get(m), vals[x].get(m)) for m in metrics)
            hi = any(_lower(vals[x].get(m), vals[y].get(m)) for m in metrics)
            if lo and not hi:
                edges.append((y, x))
    keep = [x for x in cands if not any(t == x for _, t in edges)]
    if keep:
        return keep, edges, False
    # 순환에 든 후보: 자기에게 돌아오는 경로가 있는 노드
    adj = {c: [t for s, t in edges if s == c] for c in cands}

    def reach(a, b):
        seen, st = set(), [a]
        while st:
            n = st.pop()
            for m in adj[n]:
                if m == b:
                    return True
                if m not in seen:
                    seen.add(m)
                    st.append(m)
        return False
    return [c for c in cands if reach(c, c)], edges, True


def select(cands, vals, kfix):
    """cands: 성공한 절차 목록 · vals[절차] = {J1: [..], J2, J3, D1, D2, D3} · kfix[절차] = (K, 되돌리기 추가 중단, 설정 벌 수)."""
    trace = []
    if not cands:
        return None, [{"stage": "0", "result": "후보 없음 — 규칙 4(고르지 않음)"}]
    cur = sorted(cands)
    if len(cur) == 1:
        return cur[0], [{"stage": "0", "result": f"후보 1개 — {cur[0]}"}]
    for stage, ms in (("1 정합", ("J1", "J2", "J3")), ("2 멈춤", ("D1", "D2")), ("2 꼬리", ("D3",))):
        keep, edges, cyc = eliminate(cur, ms, vals)
        trace.append({"stage": stage, "in": cur, "edges": edges, "cycle": cyc, "out": keep})
        cur = keep
        if len(cur) == 1:
            return cur[0], trace
    best = sorted(cur, key=lambda p: (kfix[p][0], kfix[p][1], kfix[p][2], p))
    trace.append({"stage": "3 단계 수", "in": cur, "order": [(p, kfix[p]) for p in best], "out": [best[0]]})
    return best[0], trace


# ---- 캠페인 ---------------------------------------------------------------------------------------------------------------------
def campaign(root):
    root = Path(root)
    reps = {}
    for f in sorted(root.glob("*/p*/rep*/status")):
        if not re.fullmatch(r"rep\d+", f.parent.name):
            continue   # 앞 시도(rep<k>.attempt<n>)는 남기기만 한다
        s = load(f.parent / "rep-summary.json") or {"load": f.parent.parent.parent.name, "proc": int(f.parent.parent.name[1:])}   # k6 전에 멈춘 회차(abort)
        st = f.read_text().strip()
        meta = load(f.parent / "meta.json") or {}
        s["status_file"] = st
        s["rerun"] = bool(meta.get("rerun"))
        reps.setdefault((s.get("load"), s.get("proc")), {})[int(f.parent.name[3:])] = s
    res = {"cells": {}, "campaign_valid": {}, "success": {}, "selection": None}
    valid = lambda s: s["status_file"] == "ok" or s["status_file"].startswith("fail:")
    # P8(부하마다 — 캠페인 유효)
    for load_ in ("S4c", "S3e"):
        q_n = q_w = 0
        for proc in range(1, 6):
            for s in (reps.get((load_, proc)) or {}).values():
                if valid(s) and s.get("probe_tables"):
                    q_n += s["probe_tables"]["Q_n"]
                    q_w += s["probe_tables"]["Q_wrong"]
        p8 = "판정 불가" if q_n < 30 else ("성립" if q_w / q_n >= 0.5 else "반증")
        res["campaign_valid"][load_] = {"P8": p8, "Q_n": q_n, "Q_wrong": q_w, "Q_ratio": round(q_w / q_n, 4) if q_n else None}
    p7_bad = [(k, r) for k, v in reps.items() for r, s in v.items() if valid(s) and (s.get("P7") or {}).get("refuted")]
    res["campaign_valid"]["P7"] = "반증" if p7_bad else "성립"
    res["campaign_valid"]["P7_refuted"] = [f"{k[0]} p{k[1]} rep{r}" for k, r in p7_bad]
    vals = {}
    for proc in range(1, 6):
        ok_all, why = True, []
        v = {}
        for load_ in ("S4c", "S3e"):
            rr = reps.get((load_, proc)) or {}
            vr = {r: s for r, s in rr.items() if valid(s)}
            cell = {"reps": {r: rr[r]["status_file"] + (" (rerun)" if rr[r]["rerun"] else "") for r in sorted(rr)}, "valid": len(vr)}
            if len(vr) < 3:
                ok_all = False
                why.append(f"{load_}: 유효 회차 {len(vr)} < 3(P10 판정 불가)")
            if any(s["status_file"] != "ok" for s in vr.values()):
                ok_all = False
                why.append(f"{load_}: 절차 원인 실패 회차 — P10 반증")
            # P9 항목
            p9 = []
            for r, s in sorted(vr.items()):
                x = s.get("P9_rep") or {}
                pt = s.get("probe_tables") or {}
                if pt.get("N_wrong"):
                    p9.append(f"(가) rep{r} 단독(새) d≥30 틀림 {pt['N_wrong']}")
                if x.get("inject_end_bad"):
                    p9.append(f"(나) rep{r} 주입 쌍 끝 불일치 {x['inject_end_bad']}")
                if x.get("mismatch_all"):
                    p9.append(f"(다) rep{r} 끝 불일치 {x['mismatch_all']}")
                if x.get("v_counter_mismatch"):   # CR13-23 — 앱 끝 검사(/internal/consistency)의 카운터 불일치도 (다) 반증 자료(끝 SQL과 따로 — 둘 중 하나라도)
                    p9.append(f"(다) rep{r} 앱 끝 검사 v_counter_mismatch {x['v_counter_mismatch']}")
                if x.get("repairs_outside"):
                    p9.append(f"(라) rep{r} 주입·탐침 밖 복구 {x['repairs_outside']}")
                # (마) ⓐ 스냅숏 > 2 수 = 결과(L 선점 3 = 201) 수 — 둘 다 응답이 모두 201·409인 탐침만(CR13-11) ·
                #     ⓒ S4c 끝 검사 탐침 회차 한도 초과 쌍(DB 전체) = 그 결과 수 + 응답이 섞인 탐침 중 스냅숏 > 2(unclean — 따로 기록한 몫)
                if x.get("over_limit_main") or (pt and pt.get("snap_over_limit") != pt.get("L_over_results")) or \
                   (load_ == "S4c" and pt and x.get("over_limit_probe") is not None
                    and x["over_limit_probe"] != (pt.get("L_over_results") or 0) + (pt.get("unclean_snap_over") or 0)):
                    p9.append(f"(마) rep{r} 한도 초과 대조 — 주 부하 {x.get('over_limit_main')} · 스냅숏 {pt.get('snap_over_limit')} · 결과 {pt.get('L_over_results')} · "
                              f"끝 탐침 {x.get('over_limit_probe')} · 응답 섞인 탐침 스냅숏 > 2 {pt.get('unclean_snap_over')}")
                if s.get("probe_nondet_integrity"):
                    p9.append(f"(바) rep{r} 결정성 위반(정합 분류) {s['probe_nondet_integrity']}")
                if s.get("probe_anomaly"):
                    p9.append(f"(바) rep{r} 예상 밖 판정 응답(201·409 — 정합 분류 · CR13-3) {s['probe_anomaly']}")
                if x.get("schema"):
                    p9.append(f"(사) rep{r} {x['schema']}")
                if (s.get("owner") or {}).get("gap_probes_unrepaired"):
                    p9.append(f"(아) rep{r} 소유 공백 탐침 미복구 {s['owner']['gap_probes_unrepaired']}")
                if x.get("J3_missing"):
                    p9.append(f"(차) rep{r} J3 대상 첫 복구 없음 {x['J3_missing']}")
                if "end-check-timeout" in (s.get("flags") or []):
                    p9.append(f"판정 불가: rep{r} 끝 검사 시한")
            # (자) 칸별 T ≤ O(§5.8-3) — 6칸(d ∈ {5, 30, 75} × H·L)을 먼저 고정한다. 3회 합 짝 수 0인 칸 = 판정 불가(성공 아님 — §5.4 · CR13-2)
            if proc != 1:
                tot = {f"{dd}{kk}": [0, 0, 0] for dd in D_SET for kk in KINDS}
                for s in vr.values():
                    for c, cc in ((s.get("probe_tables") or {}).get("cells") or {}).items():
                        if cc.get("pair_T"):
                            t = tot.setdefault(c, [0, 0, 0])
                            t[0] += cc["T_wrong"]
                            t[1] += cc["O_wrong"]
                            t[2] += 1
                cell["T_vs_O"] = tot
                bad = [c for c, (tw, ow, n) in tot.items() if tw > ow]
                if bad:
                    p9.append(f"(자) 칸 {bad} T 틀림 > O 틀림")
                zero = [c for c, (tw, ow, n) in tot.items() if n == 0]
                if zero:
                    p9.append(f"판정 불가: (자) 칸 {zero} 3회 합 짝 0")
            cell["P9"] = p9 or "성립"
            if p9:
                ok_all = False
                why += [f"{load_} P9 {x}" for x in p9]
            # P10 GC · N2/N3
            for r, s in vr.items():
                for life, g in (s.get("gc") or {}).items():
                    if g and g.get("violation"):
                        ok_all = False
                        why.append(f"{load_} rep{r} P10 GC {life}")
                if s.get("N2N3"):
                    ok_all = False
                    why.append(f"{load_} rep{r} {s['N2N3']}")
            # 지표 값(회차별)
            def col(f):
                return [f(s) for _, s in sorted(vr.items()) if f(s) is not None]
            if load_ == "S4c":
                v["J1"] = col(lambda s: (s.get("probe_tables") or {}).get("J"))
                v["D1"] = col(lambda s: (s.get("k6") or {}).get("D1"))
                v["D3"] = col(lambda s: (s.get("k6") or {}).get("D3"))
                if len(v["D1"]) < 3:
                    ok_all = False
                    why.append("D1이 유효 3회 모두 계산되지 않음")
            else:
                v["J2"] = col(lambda s: (s.get("probe_tables") or {}).get("J"))
                v["J3"] = col(lambda s: (s.get("P9_rep") or {}).get("J3"))
                v["D2"] = col(lambda s: (s.get("avail") or {}).get("D2"))
                if len(v["D2"]) < 3:
                    ok_all = False
                    why.append("D2가 유효 3회 모두 계산되지 않음")
            cell["explain"] = explain_cell(load_, proc, vr)
            res["cells"][f"{load_}-p{proc}"] = cell
        for lv in ("S4c", "S3e"):
            if res["campaign_valid"][lv]["P8"] != "성립":
                ok_all = False
                why.append(f"P8 {lv} {res['campaign_valid'][lv]['P8']}(캠페인 유효 조건)")
        if res["campaign_valid"]["P7"] != "성립":
            ok_all = False
            why.append("P7 반증(캠페인 유효 조건 — task 05로)")
        res["success"][proc] = {"success": ok_all, "why": why, "values": v}
        vals[proc] = v
    cands = [p for p in range(1, 6) if res["success"][p]["success"]]
    kfix = {p: plan13.K_FIXED[p] for p in range(1, 6)}
    pick, trace = select(cands, vals, kfix)
    res["selection"] = {"candidates": cands, "picked": pick, "trace": trace,
                        "rule4": pick is None}
    (root / "result.json").write_text(json.dumps(res, ensure_ascii=False, indent=1, default=str))
    (root / "RESULT.md").write_text(result_md(res, reps))
    return res


def explain_cell(load_, proc, vr):
    """설명 가설 P1~P6 · P11 · P12 판정 자료(회차별 값 — 성공·고르기에 쓰지 않는다)."""
    out = {}
    for r, s in sorted(vr.items()):
        k = s.get("k6") or {}
        bi = k.get("by_interval") or {}
        ml = s.get("mlock") or {}
        e = {"stall_trans_longest": (bi.get("trans") or {}).get("stall_longest"), "stall_outside_trans": sum((bi.get(n) or {}).get("stall") or 0 for n in ("old", "new")),
             "avail_longest_fail_s": (s.get("avail") or {}).get("longest_fail_send_s"), "flyway": s.get("flyway"),
             "blocked_by_migration": ml.get("blocked_by_migration"), "N6b": ml.get("N6b"), "step3_blocked": ml.get("step3_blocked_samples"),
             "owner_uncovered_s": (s.get("owner") or {}).get("uncovered_s"), "P11": s.get("P11"), "P12": k.get("P12"),
             "outage": s.get("outage"), "pre_apply": s.get("pre_apply"), "reconcile": s.get("reconcile"), "fp": s.get("false_positive_by_source")}
        out[f"rep{r}"] = e
    return out


def rng(xs, d=3):
    xs = [x for x in xs if x is not None]
    if not xs:
        return "—"
    f = (lambda x: f"{x:.{d}f}") if any(isinstance(x, float) and not float(x).is_integer() for x in xs) else (lambda x: f"{x:g}")
    return f"[{f(min(xs))}–{f(max(xs))}]"


def result_md(res, reps):
    L = ["# ADR-013 결과(요약기 산출 — 손으로 고치지 않는다)", ""]
    L.append("## 1. 회차 상태")
    L.append("| 부하 | 절차 | rep1 | rep2 | rep3 | 유효 |")
    L.append("|---|---|---|---|---|---|")
    for key, c in res["cells"].items():
        ld, p = key.split("-p")
        L.append(f"| {ld} | {p} | " + " | ".join(c["reps"].get(r, "미측정") for r in (1, 2, 3)) + f" | {c['valid']} |")
    L += ["", "## 2. 캠페인 유효 조건(P7 · P8)", ""]
    for k, v in res["campaign_valid"].items():
        L.append(f"- {k}: {v}")
    L += ["", "## 3. 절차별 성공(§5.4)", "", "| 절차 | 성공 | J1 | J2 | J3 | D1 | D2 | D3 | K | 실패 항목 |", "|---|---|---|---|---|---|---|---|---|---|"]
    for p in range(1, 6):
        s = res["success"][p]
        v = s["values"]
        L.append(f"| {p} | {'성공' if s['success'] else '아님'} | {rng(v.get('J1', []))} | {rng(v.get('J2', []))} | {rng(v.get('J3', []))} | {rng(v.get('D1', []))} | "
                 f"{rng(v.get('D2', []))} | {rng(v.get('D3', []))} | {plan13.K_FIXED[p][0]} | {'; '.join(s['why'][:6]) or '—'} |")
    L += ["", "## 4. 전환 손해 칸별(§5.8-3 — 3회 합 T 틀림 / O 틀림 / 짝 수)", ""]
    for key, c in res["cells"].items():
        if "T_vs_O" in c:
            L.append(f"- {key}: " + " · ".join(f"{k} {t}/{o}/{n}" for k, (t, o, n) in sorted(c["T_vs_O"].items())))
    L += ["", "## 5. 고르는 규칙(§5.5)", ""]
    sel = res["selection"]
    L.append(f"- 후보: {sel['candidates']} → **{'고르지 않음(규칙 4)' if sel['picked'] is None else '절차 ' + str(sel['picked'])}**")
    for t in sel["trace"]:
        L.append(f"  - {t}")
    L += ["", "## 6. TAIL(S4c 구간별 — 회차별)", "", "| 절차 | 회차 | 정지 초(old/trans/new) | 가장 긴 정지(trans) | 서버형 느린 초(old/trans/new) | 1초 p99 최대 ms(trans) | 5xx·502(trans) | P12 서버형 |",
          "|---|---|---|---|---|---|---|---|"]
    for p in range(1, 6):
        for r, s in sorted((reps.get(("S4c", p)) or {}).items()):
            bi = (s.get("k6") or {}).get("by_interval") or {}
            g = lambda n, k: (bi.get(n) or {}).get(k)
            L.append(f"| {p} | {r} | {g('old', 'stall')}/{g('trans', 'stall')}/{g('new', 'stall')} | {g('trans', 'stall_longest')} | "
                     f"{g('old', 'server_slow')}/{g('trans', 'server_slow')}/{g('new', 'server_slow')} | {g('trans', 'sec_p99_max_ms')} | "
                     f"{g('trans', 'e5xx')}·{g('trans', 'e502')} | {((s.get('k6') or {}).get('P12') or {}).get('server_slow')} |")
    return "\n".join(L) + "\n"


def main(argv):
    if len(argv) == 3 and argv[1] == "--rep":
        out, st = rep_summary(argv[2])
        print(st)
        return 0
    if len(argv) == 4 and argv[1] == "--calibration":   # §4.1 DS-12 — 두 시간 비(SQL 시드 DB ÷ 옛 앱 S4c 30분 DB) · 허용 [0.8, 1.25]
        a, b = load(argv[2]), load(argv[3])
        r1 = a["c_round_s"] / b["c_round_s"] if a and b and b.get("c_round_s") else None
        r2 = a["create_index_s"] / b["create_index_s"] if a and b and b.get("create_index_s") else None
        ok = all(x is not None and 0.8 <= x <= 1.25 for x in (r1, r2))
        print(json.dumps({"calibration": {"c_round_ratio": r1, "create_index_ratio": r2, "in_range": ok,
                                          "seed": (a or {}).get("size"), "load": (b or {}).get("size")}}, ensure_ascii=False))
        return 0 if ok else 1
    if len(argv) == 3 and argv[1] == "--select":
        d = json.loads(Path(argv[2]).read_text())
        vals = {int(k): v for k, v in d["vals"].items()}
        kfix = {int(k): tuple(v) for k, v in d.get("kfix", {}).items()} or {p: plan13.K_FIXED[p] for p in vals}
        pick, trace = select([int(c) for c in d["cands"]], vals, kfix)
        print(json.dumps({"picked": pick, "trace": trace}, ensure_ascii=False))
        return 0
    if len(argv) == 2 and Path(argv[1]).is_dir():
        r = campaign(argv[1])
        print(json.dumps(r["selection"], ensure_ascii=False))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
