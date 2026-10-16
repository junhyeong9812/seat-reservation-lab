#!/usr/bin/env python3
"""ADR-011 꼬리 지연 판정 지표(ADR-011 §4 가설 T1~T5) — 회차별·조건별 값만 낸다(성립·기각 판정은 메인 몫).

    scripts/tail.py --set k6ver|pretouch|soak results/<campaign-id>   → <campaign-id>/TAIL.md · tail.json
                                                                       (먼저 조건마다 summarize.py — 안정성 4항목·메모리는 summary.json을 쓴다)
    scripts/tail.py --rep <회차폴더>                                    → 회차 하나의 지표 JSON(표준 출력 — 스모크용, summary.json 없이 되는 것만)
    scripts/tail.py --probe-check <회차폴더>                            → probe-check.json · exit 0 = 두 측정기 정상(run.sh rep_status probe-missing)

정의(출처 — 바꾸면 ADR-011 §4와 함께)
  - 시각: 주 k6 CSV 행 시각 = PC 시계 정수 초. 측정기 CSV = ms(K6_CSV_TIME_FORMAT=unix_milli) — PC 측정기는 PC 시계, 서버 측정기는 서버 시계 → PC 시계 = 서버 − 시계 차
    (clock-offset.json k6 앞·뒤 평균 — summarize.clock_off와 같다). Go GC(k6-gctrace.log) = 줄을 받은 순간의 PC epoch(run.sh gctrace_split). 겹침은 모두 정수 초 ±1초.
  - 램프 끝 t0 = k6-summary setup_data.startedAt ÷ 1000 + ramp_s. 정상 구간 초 = 주 k6 행 시각 s ≥ t0(정수 초 — ADR-010 log '느린 초 대조'와 같다) ~ 마지막 행 초.
  - 느린 초(ADR-010 log '느린 초 대조'): 정상 구간 초 중 hold 요청(코드 0·빈 코드 제외)의 최대 응답(http_req_duration) > 50ms인 초.
  - 26초 성분(T1): x_s = log(1 + 그 초의 '10ms 넘는 요청 수'(코드 0 제외)) — 정상 구간 모든 초(행 없는 초 = 0), 평균을 뺀 주기도 |Σ x_s e^(−2πi s/P)|² ÷ N,
    P = 24~31초(0.05초 간격). 통계량 = 그 대역 최대. 귀무 = x를 무작위 재배열(PERMS회, 고정 시드)한 계열의 같은 대역 최대. p = (귀무 ≥ 관측 수 + 1) ÷ (PERMS + 1).
  - 지연 분해: 느린 초마다 그 초 최대 응답 요청 하나의 waiting(서버 응답 대기) 대 클라이언트 구간 = blocked + sending + receiving.
    서버형 느린 초 = waiting > 클라이언트 구간(waiting 지배). 중앙값은 느린 초들의 값.
  - 측정기 튐(T2·T4): 측정기 평소 p99 = 정상 구간(t0 ~ 주 k6 끝) 측정기 응답(코드 200)의 p99. 튐 = 응답 > 평소 p99 × 3 또는 코드 ≠ 200. 튐 초 = PC 시계 정수 초.
    겹침 = 주 k6 느린 초 중 ±1초 안에 튐 초가 있는 수. 우연 기대 = 느린 초 수 × (정상 구간 초 중 ±1초 안에 튐 초가 있는 초의 비율). 배수 = 겹침 ÷ 우연 기대.
  - Go GC(T3): gctrace 줄의 초. 겹침·우연 기대는 위와 같은 방법(±1초). STW = 'a+b+c ms clock'의 a·c(정지 두 번) 중 큰 값.
  - 장시간(T5): 1분 p99 = 정상 구간을 t0의 올림부터 60초씩 나눈 분마다 hold 응답(코드 0 제외) p99(정렬 값[int(n × 0.99)] — summarize와 같다).
    첫 5분 = 앞 5개 분 p99의 중앙값, 마지막 5분 = 끝 5개 완전한 분의 중앙값, 증가 % = 마지막 ÷ 첫 − 1(분이 10개 미만이면 없음). 단조 경향 = 분 번호와 분 p99의 순위 상관(스피어만).
    안정성 4항목(ADR-010 §9) = compare.jvm_rep 값 그대로: ① 정상 구간 Full GC 0·앱 스레드 멈춤 0 ② GC 정지 최대 < 10ms ③ 에러율 0·코드 0 비율 0·성공 RPS ÷ 4,200 > 0.995
    ④ 앱 메모리 램프 뒤 첫 1분 → 마지막 1분 < 10%. 앱 메모리 최대 ÷ 한도 = docker stats 최대 MiB ÷ (app-jvm.json mem_bytes ÷ 1 MiB).
  - 조건 묶음 값 = ok 회차만(겹침·기대는 합한 값으로 배수 — 회차별 값은 회차 표). 손으로 채우지 않는다 — 없으면 '누락'.
측정기 정상(--probe-check — 두 측정기 모두): 기동·종료 코드(PC 0·105 · 강제 종료 아님 / 서버 기동 0 · 종료 0·105 · 수거 0) · CSV gzip 무결 ·
  덮은 범위(첫 응답 ≤ t0 + 1 · 마지막 응답 ≥ 주 k6 끝 − 2) · 건수 ≥ 0.9 × 도착률 × 창 길이 · 창 안 공백 ≤ 10초 · 코드 200 아닌 비율 ≤ 1%.
"""
import bisect
import gzip
import json
import math
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SLOW_MS = 50
N10_MS = 10
BAND = (24.0, 31.0, 0.05)
PERMS = 1000
SEED = 20261016
SPIKE_X = 3
PROBE_MIN_SHARE, PROBE_MAX_GAP_S, PROBE_BAD_MAX = 0.9, 10, 0.01


def load(p):
    try:
        return json.loads(Path(p).read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def p99(xs):
    xs = sorted(xs)
    return xs[int(len(xs) * 0.99)] if xs else None


def clock_offset(d):
    c = load(d / "clock-offset.json") or {}
    xs = [x["offset_s"] for x in (c.get("before"), c.get("after")) if isinstance(x, dict) and num(x.get("offset_s"))]
    return sum(xs) / len(xs) if xs else None


def ramp_end(d):
    ks = load(d / "k6-summary.json") or {}
    st = (ks.get("setup_data") or {}).get("startedAt")
    return st / 1000 + (ks.get("ramp_s") or 0) if num(st) else None


# ---- 원시 읽기 ----------------------------------------------------------------------------------------
def main_csv(d, t0):
    """주 k6 CSV(hold) → 초별 {n, n10, mx, comp(최대 응답 요청의 waiting·blocked·sending·receiving)} · 분별 응답 목록(코드 0 제외, s ≥ t0).
    한 요청의 행은 http_req_duration 다음에 blocked·connecting·tls·sending·waiting·receiving이 붙어 온다(k6 CSV — 요청 한 묶음)."""
    from array import array
    per, mins, last = {}, {}, None
    s_min = math.ceil(t0)
    comp_idx = {"http_req_blocked": 0, "http_req_sending": 1, "http_req_waiting": 2, "http_req_receiving": 3}
    with gzip.open(d / "k6-requests.csv.gz", "rt", newline="") as f:
        head = f.readline().rstrip("\n").split(",")
        i_ts, i_val, i_name, i_st = (head.index(k) for k in ("timestamp", "metric_value", "name", "status"))
        cur = None   # [초, 응답, b, snd, w, rcv]
        def close(c):
            s, dur = c[0], c[1]
            e = per.get(s)
            if e is None:
                e = per[s] = {"n": 0, "n10": 0, "mx": -1.0, "comp": None}
            e["n"] += 1
            if dur > N10_MS:
                e["n10"] += 1
            if dur > e["mx"]:
                e["mx"] = dur
                e["comp"] = (c[4], c[2], c[3], c[5])   # waiting, blocked, sending, receiving
            mins.setdefault((s - s_min) // 60, array("f")).append(dur)
        for line in f:
            if not line.startswith("http_req_"):
                continue
            c = line.split(",", i_st + 1)
            m = c[0]
            if m == "http_req_duration":
                if cur is not None:
                    close(cur)
                cur = None
                if c[i_name] != "hold" or c[i_st] in ("0", ""):
                    continue
                s = int(c[i_ts])
                last = s if last is None or s > last else last
                if s < t0:
                    continue
                cur = [s, float(c[i_val]), 0.0, 0.0, 0.0, 0.0]
            elif cur is not None and m in comp_idx:
                cur[2 + comp_idx[m]] = float(c[i_val])
        if cur is not None:
            close(cur)
    return per, mins, last


def probe_csv(path, shift=0.0):
    """측정기 CSV(ping) → [(PC 시각 초(float), 응답 ms, 코드)] 시각 순. 시각 ms − shift(초)."""
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
            out.append((int(c[i_ts]) / 1000 - shift, float(c[i_val]), c[i_st]))
    out.sort()
    return out


def gctrace(path):
    """k6-gctrace.log.gz → [(PC epoch, STW 최대 ms)]."""
    out = []
    with gzip.open(path, "rt") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 4 or parts[1] != "gc":
                continue
            try:
                t = float(parts[0])
            except ValueError:
                continue
            stw = None
            for i, p in enumerate(parts):   # 'gc 12 @3.4s 1%: 0.01+1.2+0.02 ms clock, …'
                if p == "ms" and i + 1 < len(parts) and parts[i + 1].startswith("clock"):
                    try:
                        a = [float(x) for x in parts[i - 1].split("+")]
                        stw = max(a[0], a[-1])
                    except ValueError:
                        pass
                    break
            out.append((t, stw))
    return out


# ---- 지표 ------------------------------------------------------------------------------------------------
def overlap(slow, steady, ev_secs):
    """느린 초 중 ±1초 안에 사건 초가 있는 수 · 우연 기대(정상 구간 초 중 그런 초의 비율 × 느린 초 수) · 배수."""
    ev = set(ev_secs)
    near = lambda s: (s - 1 in ev) or (s in ev) or (s + 1 in ev)
    hits = sum(1 for s in slow if near(s))
    share = (sum(1 for s in steady if near(s)) / len(steady)) if steady else None
    exp = share * len(slow) if share is not None else None
    return {"hits": hits, "n_slow": len(slow), "chance_share": share, "expected": exp, "lift": (hits / exp) if exp else None}


def period_test(per, s_min, s_max):
    import numpy as np
    secs = np.arange(s_min, s_max + 1)
    x = np.array([math.log1p(per[s]["n10"]) if s in per else 0.0 for s in secs.tolist()])
    if len(x) < 2 * BAND[1] or not x.any():
        return {"n_secs": len(x), "p": None, "best_period_s": None, "power": None}
    Ps = np.arange(BAND[0], BAND[1] + 1e-9, BAND[2])
    t = np.arange(len(x))
    E = np.exp(-2j * np.pi * np.outer(t, 1 / Ps))   # N × K
    pw = lambda X: np.abs((X - X.mean(axis=-1, keepdims=True)) @ E) ** 2 / len(x)
    obs = pw(x[None, :])[0]
    i = int(obs.argmax())
    rng = np.random.default_rng(SEED)
    null = np.empty(PERMS)
    for k in range(0, PERMS, 100):
        X = np.array([rng.permutation(x) for _ in range(min(100, PERMS - k))])
        null[k:k + len(X)] = pw(X).max(axis=1)
    return {"n_secs": len(x), "best_period_s": round(float(Ps[i]), 2), "power": float(obs[i]),
            "null_median": float(np.median(null)), "p": float((np.sum(null >= obs[i]) + 1) / (PERMS + 1)), "perms": PERMS, "band_s": [BAND[0], BAND[1]]}


def probe_spikes(rows, t0, t1):
    win = [r for r in rows if t0 <= r[0] <= t1]
    base = p99([r[1] for r in win if r[2] == "200"])
    if base is None:
        return None
    sp = [r for r in win if r[2] != "200" or r[1] > SPIKE_X * base]
    return {"n": len(win), "p99_ms": base, "max_ms": max(r[1] for r in win), "spikes": len(sp),
            "spike_secs": sorted({int(math.floor(r[0])) for r in sp}), "threshold_ms": SPIKE_X * base}


def minute_trend(mins):
    full = sorted(m for m, v in mins.items() if len(v) > 0)
    if not full:
        return None
    # 마지막 분은 끝이 덜 찰 수 있다 — 앞 분 건수의 절반 미만이면 뺀다(얇은 분)
    med_n = statistics.median(len(mins[m]) for m in full)
    full = [m for m in full if len(mins[m]) >= med_n / 2]
    p = {m: p99(mins[m].tolist()) for m in full}
    out = {"minutes": len(full), "p99_by_minute": [round(p[m], 2) for m in full]}
    if len(full) >= 10:
        f5, l5 = statistics.median(p[m] for m in full[:5]), statistics.median(p[m] for m in full[-5:])
        out.update(first5_p99=f5, last5_p99=l5, growth_pct=(l5 / f5 - 1) * 100 if f5 else None)
    if len(full) >= 3:
        ys = [p[m] for m in full]
        ry = [sorted(ys).index(y) for y in ys]
        n = len(ys)
        rx = list(range(n))
        mx, my = statistics.mean(rx), statistics.mean(ry)
        cov = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
        sx = math.sqrt(sum((a - mx) ** 2 for a in rx)); sy = math.sqrt(sum((b - my) ** 2 for b in ry))
        out["spearman"] = cov / (sx * sy) if sx and sy else None
    return out


def rep_tail(d):
    """회차 하나의 ADR-011 지표(원시 파일만 — summary.json 불필요)."""
    d = Path(d)
    out = {"dir": str(d), "status": (d / "status").read_text().strip() if (d / "status").exists() else None, "notes": []}
    t0 = ramp_end(d)
    if t0 is None or not (d / "k6-requests.csv.gz").exists():
        out["notes"].append("k6-summary startedAt 또는 k6-requests.csv.gz 없음")
        return out
    per, mins, last = main_csv(d, t0)
    s_min = math.ceil(t0)
    steady = list(range(s_min, (last or s_min) + 1))
    slow = sorted(s for s in steady if s in per and per[s]["mx"] > SLOW_MS)
    k6_start = int(load(d / "k6-summary.json")["setup_data"]["startedAt"] / 1000)
    out.update(t0=t0, steady_secs=len(steady), slow_secs=len(slow), slow_rel_s=[s - k6_start for s in slow],
               slow_max_ms=[round(per[s]["mx"], 1) for s in slow])
    # 지연 분해
    comps = [per[s]["comp"] for s in slow]
    client = [b + sn + r for (_, b, sn, r) in comps]
    wait = [w for (w, _, _, _) in comps]
    out["decomp"] = {"server_type": sum(1 for w, c in zip(wait, client) if w > c), "client_type": sum(1 for w, c in zip(wait, client) if w <= c),
                     "waiting_med_ms": statistics.median(wait) if wait else None, "client_med_ms": statistics.median(client) if client else None}
    out["period"] = period_test(per, s_min, last or s_min)
    # 측정기
    t1 = ((load(d / "probe-status.json") or {}).get("main") or {}).get("k6_end_epoch") or (last + 1 if last else None)
    off = clock_offset(d)
    out["probes"] = {}
    for name, f, shift in (("pc", "k6-probe-pc.csv.gz", 0.0), ("server", "k6-probe-server.csv.gz", off)):
        if not (d / f).exists():
            continue
        if shift is None:
            out["notes"].append(f"{name}: clock-offset 없음 — 서버 측정기 시각을 옮길 수 없다")
            continue
        try:
            sp = probe_spikes(probe_csv(d / f, shift), t0, t1)
        except (OSError, EOFError, ValueError) as e:
            out["notes"].append(f"{name}: {type(e).__name__}")
            continue
        if sp is None:
            out["probes"][name] = None
            continue
        ov = overlap(slow, steady, sp.pop("spike_secs"))
        out["probes"][name] = {**sp, "overlap": ov}
    # Go GC
    if (d / "k6-gctrace.log.gz").exists():
        g = [x for x in gctrace(d / "k6-gctrace.log.gz") if t0 - 1 <= x[0] <= (t1 or x[0])]
        ov = overlap(slow, steady, [int(math.floor(t)) for t, _ in g])
        stw = [s for _, s in g if num(s)]
        out["gogc"] = {"n": len(g), "interval_med_s": statistics.median([b[0] - a[0] for a, b in zip(g, g[1:])]) if len(g) > 1 else None,
                       "stw_max_ms": max(stw) if stw else None, "overlap": ov}
    out["minute"] = minute_trend(mins)
    return out


# ---- 측정기 정상(run.sh rep_status probe-missing) ------------------------------------------------------
def probe_check(d):
    d = Path(d)
    st = load(d / "probe-status.json")
    res = {"ok": False, "reasons": [], "pc": None, "server": None}
    if not st:
        res["reasons"].append("probe-status.json 없음")
    t0, off = ramp_end(d), clock_offset(d)
    t1 = ((st or {}).get("main") or {}).get("k6_end_epoch")
    rate = (st or {}).get("rate") or 50
    if t0 is None or not num(t1):
        res["reasons"].append("창(램프 끝·주 k6 끝)을 모름")
    pc, sv = (st or {}).get("pc") or {}, (st or {}).get("server") or {}
    if pc.get("exit_code") not in (0, 105) or pc.get("killed"):
        res["reasons"].append(f"pc 종료 코드 {pc.get('exit_code')} · 강제 종료 {pc.get('killed')}")
    if sv.get("up_rc") != 0 or sv.get("exit_code") not in (0, 105) or sv.get("cp_rc") != 0:
        res["reasons"].append(f"server 기동 {sv.get('up_rc')} · 종료 코드 {sv.get('exit_code')} · 수거 {sv.get('cp_rc')}")
    for name, f, shift in (("pc", "k6-probe-pc.csv.gz", 0.0), ("server", "k6-probe-server.csv.gz", off)):
        if t0 is None or not num(t1):
            break
        if shift is None:
            res["reasons"].append(f"{name}: clock-offset 없음")
            continue
        try:
            rows = probe_csv(d / f, shift)
        except (OSError, EOFError, ValueError, StopIteration) as e:
            res["reasons"].append(f"{name}: CSV 읽기 실패 {type(e).__name__}")
            continue
        win = [r for r in rows if t0 <= r[0] <= t1]
        ts = [r[0] for r in win]
        gap = max((b - a for a, b in zip(ts, ts[1:])), default=None)
        bad = sum(1 for r in win if r[2] != "200")
        info = {"rows": len(rows), "in_window": len(win), "first": rows[0][0] if rows else None, "last": rows[-1][0] if rows else None,
                "expected": rate * (t1 - t0), "max_gap_s": gap, "non200": bad}
        res[name] = info
        why = []
        if not rows or rows[0][0] > t0 + 1:
            why.append("첫 응답이 램프 끝 + 1초보다 늦다")
        if not rows or rows[-1][0] < t1 - 2:
            why.append("마지막 응답이 주 k6 끝 − 2초보다 이르다")
        if len(win) < PROBE_MIN_SHARE * info["expected"]:
            why.append(f"건수 {len(win)} < {PROBE_MIN_SHARE} × {info['expected']:.0f}")
        if gap is None or gap > PROBE_MAX_GAP_S:
            why.append(f"공백 {gap}초 > {PROBE_MAX_GAP_S}")
        if win and bad / len(win) > PROBE_BAD_MAX:
            why.append(f"200 아닌 응답 {bad}/{len(win)}")
        res["reasons"] += [f"{name}: {w}" for w in why]
    res["ok"] = not res["reasons"]
    (d / "probe-check.json").write_text(json.dumps(res, ensure_ascii=False, indent=1))
    return 0 if res["ok"] else 1


# ---- 캠페인 표 -------------------------------------------------------------------------------------------
def fx(x, d=1):
    if x is None:
        return "누락"
    if isinstance(x, bool):
        return "○" if x else "✕"
    if num(x):
        return f"{x:,.{d}f}" if not float(x).is_integer() or d == 0 else f"{x:,.0f}"
    return str(x)


def table(out, head, rows):
    out.append("| " + " | ".join(head) + " |")
    out.append("|" + "---|" * len(head))
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    out.append("")


def campaign(set_name, root):
    root = Path(root)
    sys.path.insert(0, str(HERE))
    import compare as C   # noqa: E402 — 안정성 4항목·앱 메모리(jvm_rep) 재사용
    conds = [l.split()[0] for l in (root / "conditions.txt").read_text().splitlines() if l.strip()]
    expect = 2 if set_name == "soak" else 3
    camp = C.Camp(root, expect_reps=expect)
    js = {"campaign": root.name, "set": set_name, "conds": {}}
    out = [f"# ADR-011 꼬리 지연 지표 — `{root.name}` (--set {set_name})\n",
           "> `k6/ADR-009/scripts/tail.py`가 회차 원시 파일(주 k6·측정기 CSV · gctrace · clock-offset)과 summary.json(안정성·메모리)에서 산출. 정의는 스크립트 머리말. "
           "값만 낸다 — 가설 T1~T5의 성립·기각 판정은 담지 않는다. 조건 묶음 = ok 회차만.\n"]
    per_rep_rows, cond_rows, stab_rows = [], [], []
    for c in conds:
        folder_reps = sorted(p for p in (root / c / "L4" / "S4c4200").glob("rep*") if p.is_dir() and "." not in p.name)
        summ_reps = {r.get("rep"): r for r in ((camp.summ.get(c) or {}).get("L4/S4c4200") or [])}
        if not summ_reps:
            camp.notes.append(f"{c}: summary.json 없음 — 안정성·메모리 칸은 누락(summarize.py 먼저)")
        reps = []
        for rd in folder_reps:
            t = rep_tail(rd)
            r = summ_reps.get(rd.name)
            if r is not None:
                jv = C.jvm_rep(camp, c, r)
                lim = jv["mem_bytes"] / 1048576 if num(jv.get("mem_bytes")) else None
                t["stability"] = {
                    "s1_full_stall_0": (jv["full_steady"] == 0 and jv["stalls_steady"] == 0) if num(jv["full_steady"]) and num(jv["stalls_steady"]) else None,
                    "s2_stop_lt_10ms": (jv["stop_max_steady"] < 10) if num(jv["stop_max_steady"]) else None,
                    "s3_err_code0_rps": (jv["err"] == 0 and jv["status0_share"] == 0 and jv["rps_ratio"] > 0.995)
                                        if all(num(jv[k]) for k in ("err", "status0_share", "rps_ratio")) else None,
                    "s4_mem_growth_lt_10": (jv["app_mem_growth_pct"] < 10) if num(jv["app_mem_growth_pct"]) else None,
                    "mem_growth_pct": jv["app_mem_growth_pct"], "stop_max_steady": jv["stop_max_steady"],
                    "app_mem_max_mib": jv["app_mem_max"], "mem_limit_mib": lim,
                    "mem_max_share": (jv["app_mem_max"] / lim) if num(jv["app_mem_max"]) and lim else None,
                    "sec_p99_max": jv["sec_p99_max"], "slow_secs_50ms_summarize": jv["slow_secs"]}
            reps.append(t)
        js["conds"][c] = reps
        ok = [t for t in reps if t["status"] == "ok"]
        if len(ok) != expect:
            camp.notes.append(f"{c}: ok 회차 {len(ok)}/{expect} — {[(Path(t['dir']).name, t['status']) for t in reps]}")
        for t in reps:
            g = lambda *k: _get(t, *k)
            per_rep_rows.append([c, Path(t["dir"]).name, t["status"] or "누락", fx(g("slow_secs"), 0), fx(g("period", "p"), 3), fx(g("period", "best_period_s"), 2),
                                 fx(g("decomp", "server_type"), 0), fx(g("decomp", "client_type"), 0), fx(g("decomp", "waiting_med_ms")), fx(g("decomp", "client_med_ms")),
                                 _ov(g("probes", "pc")), fx(g("probes", "pc", "spikes"), 0), _ov(g("probes", "server")), fx(g("probes", "server", "spikes"), 0),
                                 _ovg(g("gogc", "overlap")), fx(g("gogc", "n"), 0), fx(g("gogc", "stw_max_ms"), 2)])
            if t.get("stability") or t.get("minute"):
                s = t.get("stability") or {}
                m = t.get("minute") or {}
                stab_rows.append([c, Path(t["dir"]).name, t["status"] or "누락", fx(s.get("s1_full_stall_0")), fx(s.get("s2_stop_lt_10ms")), fx(s.get("s3_err_code0_rps")),
                                  fx(s.get("s4_mem_growth_lt_10")), fx(s.get("mem_growth_pct")), fx(s.get("app_mem_max_mib"), 0), fx(s.get("mem_limit_mib"), 0),
                                  fx((s.get("mem_max_share") or 0) * 100 if num(s.get("mem_max_share")) else None), fx(m.get("minutes"), 0), fx(m.get("first5_p99"), 2),
                                  fx(m.get("last5_p99"), 2), fx(m.get("growth_pct")), fx(m.get("spearman"), 2), fx(s.get("sec_p99_max")), fx(s.get("slow_secs_50ms_summarize"), 0)])
        # 조건 묶음(ok 회차): 느린 초·p·서버형 범위 + 겹침 합
        rng = lambda k: _rng([_get(t, *k) for t in ok])
        pool = lambda *k: _pool([_get(t, *k) for t in ok])
        cond_rows.append([c, len(ok), rng(("slow_secs",)), rng(("period", "p")), sum(1 for t in ok if num(_get(t, "period", "p")) and _get(t, "period", "p") <= 0.05),
                          rng(("decomp", "server_type")), pool("probes", "pc", "overlap"), rng(("probes", "pc", "spikes")),
                          pool("probes", "server", "overlap"), rng(("probes", "server", "spikes")), pool("gogc", "overlap")])
    out.append("## 1. 조건별(ok 회차) — 값 = 중앙값 [최소–최대] · 겹침 = 합한 겹침/느린 초(우연 기대 · 배수)\n")
    table(out, ["조건", "n(ok)", "느린 초", "26초 성분 p(24~31초)", "p ≤ 0.05 회차 수", "서버형 느린 초", "PC 측정기 겹침", "PC 측정기 튐 수", "서버 측정기 겹침",
                "서버 측정기 튐 수", "Go GC 겹침"], cond_rows)
    out.append("## 2. 회차별\n")
    table(out, ["조건", "회차", "상태", "느린 초", "26초 p", "최대 성분 주기 s", "서버형", "클라이언트형", "waiting 중앙 ms", "클라이언트 구간 중앙 ms",
                "PC 측정기 겹침(기대·배수)", "PC 튐", "서버 측정기 겹침(기대·배수)", "서버 튐", "Go GC 겹침(기대·배수)", "Go GC 수", "Go GC STW 최대 ms"], per_rep_rows)
    out.append("## 3. 안정성(ADR-010 §9 4항목) · 앱 메모리 · 1분 p99 추세\n")
    table(out, ["조건", "회차", "상태", "① Full GC·앱 멈춤 0", "② 정지 최대 < 10ms", "③ 에러·코드 0·RPS", "④ 메모리 추세 < 10%", "메모리 추세 %", "앱 메모리 최대 MiB",
                "한도 MiB", "최대 ÷ 한도 %", "분 수", "첫 5분 p99 ms", "마지막 5분 p99 ms", "증가 %", "순위 상관", "1초 p99 최대 ms", "> 50ms 초(summarize)"], stab_rows)
    out.append("## 누락·의심\n")
    out += [f"- {n}" for n in camp.notes] or ["- 자동 점검에서 걸린 항목 없음"]
    js["notes"] = camp.notes
    (root / "TAIL.md").write_text("\n".join(out) + "\n")
    (root / "tail.json").write_text(json.dumps(js, ensure_ascii=False, indent=1, default=str))
    print(root / "TAIL.md")
    return 0


def _get(x, *k):
    for kk in k:
        x = x.get(kk) if isinstance(x, dict) else None
    return x


def _rng(vals):
    v = [x for x in vals if num(x)]
    if not v:
        return "누락"
    d = 3 if all(0 <= x <= 1 for x in v) and not all(float(x).is_integer() for x in v) else 0
    s = fx(statistics.median(v), d) if len(v) == 1 else f"{fx(statistics.median(v), d)} [{fx(min(v), d)}–{fx(max(v), d)}]"
    return s + (f" · 누락 {len(vals) - len(v)}" if len(v) < len(vals) else "")


def _pool(ovs):
    o = [x for x in ovs if isinstance(x, dict) and num(x.get("expected"))]
    if not o:
        return "누락"
    h, n, e = sum(x["hits"] for x in o), sum(x["n_slow"] for x in o), sum(x["expected"] for x in o)
    return f"{h}/{n}(기대 {e:.1f} · {('×%.2f' % (h / e)) if e else '—'})" + (f" · 누락 {len(ovs) - len(o)}" if len(o) < len(ovs) else "")


def _ov(p):
    return _ovg((p or {}).get("overlap")) if p else "누락"


def _ovg(o):
    if not isinstance(o, dict):
        return "누락"
    return f"{o['hits']}/{o['n_slow']}(기대 {fx(o['expected'])} · {('×%.2f' % o['lift']) if num(o.get('lift')) else '—'})"


def main(argv):
    if len(argv) == 2 and argv[0] == "--probe-check":
        return probe_check(argv[1])
    if len(argv) == 2 and argv[0] == "--rep":
        print(json.dumps(rep_tail(argv[1]), ensure_ascii=False, indent=1, default=str))
        return 0
    if len(argv) == 3 and argv[0] == "--set" and argv[1] in ("k6ver", "pretouch", "soak"):
        return campaign(argv[1], argv[2])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
