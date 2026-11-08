#!/usr/bin/env python3
"""ADR-016 모형 계산(설계-8.1 A · 8.2 · 9.6 · 측정 명세 §1-2) — 서버 없음 · 표준 라이브러리만 · 시드 고정.
세션 결과는 outcome16.decide(단일 정의 — M16-1)로 정한다. 모형은 후보 · 값 고르기 · 민감도에만 쓰고 판정은 부하 관측으로 한다(설계-9.6).

    model16.py --table                         설계-8.3 표 재현(δ 0 · CAP 10분) — 허용 오차 대조
    model16.py --sweep [--delta F] [--err S]   정책 × 분포 × CAP 전체 → JSON
    model16.py --choose [--delta F] [--err S] [--margin-slack S]   고르는 규칙(명세 §0 · §1-2)이 고른 θ · W · M → JSON
δ 파일(F) = 줄마다 δ̂ 초(스모크의 확정 응답 시간 — 상한). 주지 않으면 δ = 0으로 계산하고 출력에 "delta": "zero"를 적는다(판정 입력에는 쓰지 않는다 — 결측 대체 금지)."""
import argparse
import json
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import outcome16 as O  # noqa: E402

TTL = 300.0
BATCH = 10.0
T_RANGE = (30.0, 240.0)
T_SENS = ((30.0, 120.0), (60.0, 270.0))
MEDIANS = (30.0, 45.0, 60.0)
SIGMAS = (0.7, 0.94, 1.2)
CAPS = (600.0, 1800.0)
BASE = {"t": T_RANGE, "m": 45.0, "s": 0.94, "cap": 600.0}
TARGET_WRONGED = 0.001   # Q14 억울 ≤ 0.1%
TARGET_LOSS = 0.05       # Q14 결제 전 거부 + 결제 실패 ≤ 5%
N_DEFAULT = 300_000
THETA_STEP = 1.0         # ①+ⓑ θ 격자(초)


def sampler(t_range, m, s, seed):
    r = random.Random(seed)
    mu = math.log(m)
    def draw():
        return r.uniform(*t_range), r.lognormvariate(mu, s)
    return draw, r


def simulate(policy, dist, n=N_DEFAULT, seed=1, deltas=None, err=0.0):
    """policy: dict(mode, b, c, theta, window, margin) · dist: dict(t, m, s, cap) → 비율 · 홀드 수명."""
    draw, r = sampler(dist["t"], dist["m"], dist["s"], seed)
    counts = dict.fromkeys(O.CATEGORIES, 0)
    lives = []
    for _ in range(n):
        think, pay = draw()
        delta = r.choice(deltas) if deltas else 0.0
        out = O.decide(mode=policy["mode"], b=policy["b"], c=policy["c"], theta=policy["theta"], window=policy["window"],
                       margin=policy["margin"], cap=dist["cap"], ttl=TTL, think=think, pay=pay, delta=delta, err=err)
        counts[out] += 1
        lives.append(hold_life(policy, think, pay, delta, dist["cap"], out))
    lives.sort()
    q = lambda p: lives[min(len(lives) - 1, int(p * len(lives)))]
    return {"n": n, **{k: v / n for k, v in counts.items()}, "life_p50": q(0.5), "life_p99": q(0.99), "life_max": lives[-1]}


def hold_life(policy, think, pay, delta, cap, out):
    """홀드 수명(선점 → 확정 또는 만료) — 좌석 점유 비용의 대리값. 결제 전 거부 · 결제 실패는 만료까지 산다(확정을 보내지 않음)."""
    e = TTL
    if policy["mode"] == O.MODE_EXT and out != O.PRE_REJECT:
        rem = TTL - think
        if rem < policy["window"]:
            e = think + policy["window"]
    if out == O.SUCCESS:
        return think + pay + delta
    return e


def pol(mode, b, c, theta=0.0, window=0.0, margin=0.0):
    return {"mode": mode, "b": b, "c": c, "theta": theta, "window": window, "margin": margin}


def choose(deltas=None, err=0.0, margin_slack=0.0, n=N_DEFAULT, seed=1):
    """고르는 규칙(명세 §0 Q/A · Q/A 3):
    M = δ̂ + err의 p99.9를 덮는 초 올림(δ̂가 없으면 결측 — None) · ④ W = CAP + M + 여유(배치 + margin_slack) · ④ θ = M ·
    ①+ⓑ θ = '결제 전 거부 + 결제 실패 ≤ 5%' 안에서 억울 최소(기준 분포 · ⓒ 끔 · 격자 THETA_STEP) — θ ≥ M(앱 기동 검사 M ≤ θ)."""
    if deltas:
        xs = sorted(d + err for d in deltas)
        m_val = float(math.ceil(xs[min(len(xs) - 1, int(0.999 * len(xs)))]))
    else:
        m_val = None
    out = {"M": m_val, "delta": "measured" if deltas else "missing", "err": err}
    cap = BASE["cap"]
    out["W"] = None if m_val is None else cap + m_val + BATCH + margin_slack
    out["theta_ext"] = m_val
    best = None
    th = m_val or 0.0
    while th < TTL:
        r = simulate(pol(O.MODE_COND, True, False, theta=th), BASE, n=n, seed=seed, deltas=deltas, err=err)
        loss = r[O.PRE_REJECT] + r[O.PAY_FAIL]
        if loss <= TARGET_LOSS and (best is None or r[O.WRONGED] < best[1][O.WRONGED]):
            best = (th, r)
        if loss > TARGET_LOSS:
            break  # 거부는 θ에 단조 증가 — 넘으면 끝
        th += THETA_STEP
    out["theta_cond"] = None if best is None else best[0]
    out["theta_cond_rates"] = None if best is None else best[1]
    return out


def table(n=N_DEFAULT, seed=1):
    """설계-8.3 표 재현(δ 0 · CAP 10분 · T U[30, 240] · m 45)."""
    rows = {}
    for s in SIGMAS:
        d = {**BASE, "s": s}
        rows[f"s{s}"] = {
            "cond": simulate(pol(O.MODE_COND, False, False), d, n, seed)[O.WRONGED],
            "cond_b120": [simulate(pol(O.MODE_COND, True, False, theta=120.0), d, n, seed)[k] for k in (O.WRONGED, O.PRE_REJECT)],
            "ext": {w: simulate(pol(O.MODE_EXT, True, False, window=w * 60.0), d, n, seed)[O.WRONGED] for w in (2, 3, 5, 10)},
            "cond_c10": [simulate(pol(O.MODE_COND, False, True, margin=10.0), d, n, seed)[k] for k in (O.WRONGED, O.PAY_FAIL)],
        }
    return rows


# 설계-8.3 표(시드 1 · 2 · 표본 30만~40만 — 메인 python 계산 2026-11-07)
DESIGN_83 = {
    "s0.7": {"cond": 0.0684, "cond_b120": [0.0179, 0.285], "ext": {2: 0.0414, 3: 0.0187, 5: 0.0033, 10: 0.0}, "cond_c10": [0.0, 0.0866]},
    "s0.94": {"cond": 0.1147, "cond_b120": [0.0464, 0.285], "ext": {2: 0.0880, 3: 0.0568, 5: 0.0191, 10: 0.0}, "cond_c10": [0.0, 0.1362]},
    "s1.2": {"cond": 0.1518, "cond_b120": [0.0736, 0.285], "ext": {2: 0.1271, 3: 0.0934, 5: 0.0410, 10: 0.0}, "cond_c10": [0.0, 0.1849]},
}


def table_check(rows, n=N_DEFAULT):
    """표본 오차 4σ(√(p(1−p)/n) · 두 계산 모두 표본이라 √2) 안이면 일치."""
    bad = []
    def cmp(name, got, want):
        tol = 4 * math.sqrt(2 * max(want * (1 - want), 1 / n) / n) + 1e-4
        if abs(got - want) > tol:
            bad.append(f"{name}: 모형 {got:.4f} · 설계 {want:.4f} · 허용 {tol:.4f}")
    for k, want in DESIGN_83.items():
        got = rows[k]
        cmp(f"{k} ①", got["cond"], want["cond"])
        for i, nm in enumerate(("억울", "거부")):
            cmp(f"{k} ①+ⓑ θ2분 {nm}", got["cond_b120"][i], want["cond_b120"][i])
        for w, v in want["ext"].items():
            cmp(f"{k} ④ W{w}분", got["ext"][w], v)
        for i, nm in enumerate(("억울", "실패")):
            cmp(f"{k} ①+ⓒ M10초 {nm}", got["cond_c10"][i], want["cond_c10"][i])
    return bad


def sweep(deltas=None, err=0.0, picked=None, n=N_DEFAULT, seed=1):
    picked = picked or choose(deltas, err, n=n, seed=seed)
    th_c = picked["theta_cond"] or 0.0
    m = picked["M"] or 0.0
    w = picked["W"] or (BASE["cap"] + BATCH)
    pols = {
        "cond": pol(O.MODE_COND, False, False, theta=th_c),
        "cond+c": pol(O.MODE_COND, False, True, theta=th_c, margin=m),
        "cond+b": pol(O.MODE_COND, True, False, theta=th_c),
        "cond+b+c": pol(O.MODE_COND, True, True, theta=th_c, margin=m),
        "ext": pol(O.MODE_EXT, True, False, theta=m, window=w, margin=m),
        "ext+c": pol(O.MODE_EXT, True, True, theta=m, window=w, margin=m),
    }
    dists = {"base": BASE}
    for s in SIGMAS:
        for mm in MEDIANS:
            for cap in CAPS:
                dists[f"m{mm:g}-s{s}-cap{cap / 60:g}"] = {"t": T_RANGE, "m": mm, "s": s, "cap": cap}
    for t in T_SENS:
        dists[f"t{t[0]:g}-{t[1]:g}"] = {**BASE, "t": t}
    return {"picked": picked, "results": {pn: {dn: simulate(p, d, n, seed, deltas, err) for dn, d in dists.items()} for pn, p in pols.items()}}


def read_deltas(path):
    if not path:
        return None
    xs = [float(x) for x in Path(path).read_text().split() if x.strip()]
    if not xs:
        raise SystemExit(f"δ 파일이 비었다: {path}")
    return xs


def main():
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--table", action="store_true")
    g.add_argument("--sweep", action="store_true")
    g.add_argument("--choose", action="store_true")
    ap.add_argument("--delta")
    ap.add_argument("--err", type=float, default=0.0)
    ap.add_argument("--margin-slack", type=float, default=0.0)
    ap.add_argument("-n", type=int, default=N_DEFAULT)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    deltas = read_deltas(a.delta)
    if a.table:
        rows = table(a.n, a.seed)
        bad = table_check(rows, a.n)
        print(json.dumps({"rows": rows, "mismatch": bad}, ensure_ascii=False, indent=1))
        sys.exit(1 if bad else 0)
    if a.choose:
        print(json.dumps(choose(deltas, a.err, a.margin_slack, a.n, a.seed), ensure_ascii=False, indent=1))
        return
    print(json.dumps(sweep(deltas, a.err, n=a.n, seed=a.seed), ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
