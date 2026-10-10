#!/usr/bin/env python3
"""ADR-007 후속(디스크 원인 C5 — docs/plans/2026-10-10/adr-007-disk-cause 명세 §3·§5·§9 task 01): 쓰기 반감 탐지 + 호스트 디스크 지표.

    scripts/wdrop.py <캠페인 루트|조건 폴더|회차 폴더|fio 폴더> [--json <파일>] [--series-dir <폴더>] [--dev nvme1n1] [--win 30]
    scripts/wdrop.py --coverage <timeline-host.jsonl> <k6_started ISO> <k6_ended ISO> <시계 차 초> [<최소 비율 0.9>]   # run.sh rep_status host-sampler-thin

쓰기 반감(task 06a 분석 정의를 그대로 옮겼다 — 근거 scratchpad an7/worker/extra.py·wdrop.py):
  - 표본 = timeline-server.jsonl(10초 루프의 docker stats)에서 DB 컨테이너(이름에 'db') BlockIO 쓰기 누적(바이트 — docker의 10진 단위 kB·MB·GB) · 시각 = 바로 앞 시각 행.
  - 간격 i = 연속 두 표본 [t1, t2) · 속도 = Δ쓰기 ÷ Δt (MB/s, 10^6).
  - 반감 = i ≥ 4이고 t1 ≥ k6 +30초인 첫 간격 중 속도(i) < 0.6 × 직전 4간격 속도의 중앙값 **이고** 속도(i+1)도 < 0.6 × 그 중앙값(다음 간격이 없으면 반감 아님).
  - 산출: 반감 k6 초(t1) · GB-at-drop = t1 시점 누적 쓰기(10^9) · 직전 중앙값 MB/s → 반감 간격·다음 간격 MB/s.
  - 시각: 06a는 서버 시각 − k6 시작(PC 시계)을 그대로 썼다. 여기서는 시계 차(meta.json clock_offset 평균 — 서버 − PC)를 빼서 PC 시계 k6 초로 낸다
    (차이 ≤ 0.5초 — 반감 간격·GB는 같고 k6 초만 반올림 1초 차이가 날 수 있다. 오프라인 확인: 20261009-adr007-ea8b85cc 81회).
호스트 지표(timeline-host.jsonl이 있을 때 — 1초 표본, 서버 시계, 원시 누적값 → 이웃 표본 차분):
  - 장치(--dev, 기본 nvme1n1)마다 wMB/s(Δwrite_sectors × 512 ÷ Δt ÷ 10^6) · w/s · w_await(Δwrite_ms ÷ Δwrites, ms) · flush/s · flush 평균(Δflush_ms ÷ Δflushes, ms) ·
    aqu-sz(Δweighted_ms ÷ Δt ÷ 1000) · util%(Δio_ms ÷ Δt ÷ 10) · Dirty·Writeback(MB, 표본 평균) · PSI io some·full(Δtotal μs ÷ Δt ÷ 10^4 = %).
  - 창: 반감 시각(t1, 서버 시계) 기준 [−win, 0) vs [0, +win) — 창 안 차분의 합으로(초 평균의 평균이 아니라) 비율을 낸다. w_await·flush 평균은 그 합의 비.
  - 호스트 반감(교차 확인): 같은 규칙을 호스트 장치 쓰기에 — 10초 칸(k6 시작 기준)으로 묶어 같은 0.6 × 직전 4칸 중앙값·다음 칸 조건. GB = 그 칸 시작까지 장치 누적 쓰기(k6 시작 기준 — 서버 전체 I/O).
  - fio 폴더(meta.json kind=fio): docker stats가 없으므로 호스트 반감만(fio 시작 기준 10초 칸) + 칸별 누적 GB.
--series-dir: 회차마다 초 단위 호스트 지표 CSV(<조건>__<회차>.csv — k6 초 · 위 지표).
--coverage: k6 구간 [시작, 끝)의 1초 칸(정수 초 — 구간 안에 온전히 든 칸) 중 표본(서버 시각 − 시계 차)이 하나 이상인 칸의 비율 < 최소 비율이면 exit 1
  (파일 없음·빈 파일 = 0%). 구간이 1초 미만이면 판정하지 않는다(exit 0). 비율은 stderr 한 줄.
결과 폴더에는 쓰지 않는다(읽기만).
"""
import argparse
import json
import math
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

DEVS_DEFAULT = "nvme1n1"
REP_RE = re.compile(r"^rep\d+$")


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def sz(v):
    m = re.match(r"([\d.]+)\s*([kKMGT]?i?B)", v.strip())
    if not m:
        return None
    f = {"B": 1, "kB": 1e3, "KB": 1e3, "KiB": 1024, "MB": 1e6, "MiB": 2**20, "GB": 1e9, "GiB": 2**30, "TB": 1e12}.get(m.group(2))
    return float(m.group(1)) * f if f else None


def clock_off(meta):
    c = meta.get("clock_offset") or {}
    vals = [x["offset_s"] for x in (c.get("before"), c.get("after")) if isinstance(x, dict) and isinstance(x.get("offset_s"), (int, float))]
    return sum(vals) / len(vals) if vals else 0.0


def host_samples(path):
    out = []
    try:
        with open(path) as f:
            for line in f:
                if not line.startswith('{"t"'):
                    continue
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(r.get("t"), (int, float)):
                    out.append(r)
    except OSError:
        pass
    out.sort(key=lambda r: r["t"])
    return out


# ---- --coverage ------------------------------------------------------------------------------------------
def coverage(path, started, ended, off, min_ratio):
    t0, t1 = ts(started), ts(ended)
    lo, hi = math.ceil(t0), math.floor(t1)
    total = hi - lo
    if total < 1:
        print(f"host coverage: k6 구간 {t1 - t0:.1f}s < 1s — 판정 안 함", file=sys.stderr)
        return 0
    secs = {math.floor(r["t"] - off) for r in host_samples(path)}
    got = sum(1 for s in range(lo, hi) if s in secs)
    ratio = got / total
    if ratio < min_ratio:
        print(f"host-sampler-thin: k6 구간 1초 칸 {got}/{total} = {ratio:.1%} < {min_ratio:.0%}", file=sys.stderr)
        return 1
    return 0


# ---- docker stats 반감(06a 정의) -----------------------------------------------------------------------
def bio_series(rep):
    bio, cur = [], None
    p = rep / "timeline-server.jsonl"
    if not p.exists():
        return bio
    for line in p.read_text().splitlines():
        try:
            r = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "load" in r:
            cur = ts(r["t"])
        elif "Name" in r and "db" in r["Name"] and cur is not None and "BlockIO" in r:
            w = sz(r["BlockIO"].split("/")[1])
            if w is not None:
                bio.append((cur, w))
    return bio


def find_drop(points, min_t):
    """points = [(t, 누적 바이트)] — 06a 규칙. 반환 dict 또는 None"""
    iv = [(t1, t2, (v2 - v1) / (t2 - t1) / 1e6, v1) for (t1, v1), (t2, v2) in zip(points, points[1:]) if t2 > t1]
    for i in range(4, len(iv)):
        if iv[i][0] < min_t:
            continue
        prev = statistics.median(x[2] for x in iv[i - 4:i])
        if i + 1 < len(iv) and iv[i][2] < 0.6 * prev and iv[i + 1][2] < 0.6 * prev:
            return {"t": iv[i][0], "bytes": iv[i][3], "prev_mbps": prev, "drop_mbps": iv[i][2], "next_mbps": iv[i + 1][2]}
    return None


# ---- 호스트 지표 -----------------------------------------------------------------------------------------
def per_second(samples, dev):
    """이웃 표본 차분 → [(t_end, dt, {Δ값})]"""
    rows = []
    for a, b in zip(samples, samples[1:]):
        da, db = (a.get("disk") or {}).get(dev), (b.get("disk") or {}).get(dev)
        dt = b["t"] - a["t"]
        if not da or not db or dt <= 0:
            continue
        d = {k: (db[k] - da[k]) if isinstance(db.get(k), (int, float)) and isinstance(da.get(k), (int, float)) else None
             for k in ("writes", "write_sectors", "write_ms", "flushes", "flush_ms", "weighted_ms", "io_ms")}
        for kind in ("some", "full"):
            pa, pb = ((a.get("psi_io") or {}).get(kind) or {}).get("total"), ((b.get("psi_io") or {}).get(kind) or {}).get("total")
            d["psi_" + kind] = (pb - pa) if isinstance(pa, (int, float)) and isinstance(pb, (int, float)) else None
        m = b.get("mem") or {}
        d["dirty_kB"], d["wb_kB"] = m.get("Dirty_kB"), m.get("Writeback_kB")
        rows.append((b["t"], dt, d))
    return rows


def ssum(rows, k):
    vals = [r[2][k] for r in rows if r[2].get(k) is not None]
    return sum(vals) if vals else None


def agg(rows):
    if not rows:
        return None
    dt = sum(r[1] for r in rows)
    w, ws, wm, fl, fm, wt, io = (ssum(rows, k) for k in ("writes", "write_sectors", "write_ms", "flushes", "flush_ms", "weighted_ms", "io_ms"))
    ps, pf = ssum(rows, "psi_some"), ssum(rows, "psi_full")
    dirty = [r[2]["dirty_kB"] for r in rows if r[2].get("dirty_kB") is not None]
    wb = [r[2]["wb_kB"] for r in rows if r[2].get("wb_kB") is not None]
    div = lambda a, b: (a / b) if a is not None and b else None
    return {
        "n": len(rows), "wMBps": div(ws * 512 / 1e6 if ws is not None else None, dt), "w_per_s": div(w, dt), "w_await_ms": div(wm, w),
        "flush_per_s": div(fl, dt), "flush_avg_ms": div(fm, fl), "aqu_sz": div(wt / 1000 if wt is not None else None, dt),
        "util_pct": div(io / 10 if io is not None else None, dt), "dirty_MB": statistics.mean(dirty) / 1024 if dirty else None,
        "writeback_MB": statistics.mean(wb) / 1024 if wb else None,
        "psi_some_pct": div(ps / 1e4 if ps is not None else None, dt), "psi_full_pct": div(pf / 1e4 if pf is not None else None, dt),
    }


def window(rows, lo, hi):
    return agg([r for r in rows if lo <= r[0] - r[1] / 2 < hi])


def host_cum_points(samples, dev, t0, bin_s=10):
    """장치 쓰기 누적(바이트, t0 기준 0)을 t0 + k·bin_s 칸 경계마다 — 칸 경계에 가장 가까운 앞 표본"""
    pts = [(r["t"], r["disk"][dev]["write_sectors"] * 512) for r in samples if (r.get("disk") or {}).get(dev)]
    if len(pts) < 2:
        return []
    base = next((v for t, v in pts if t >= t0), pts[0][1])
    out, j = [], 0
    k = math.ceil((pts[0][0] - t0) / bin_s)
    while t0 + k * bin_s <= pts[-1][0]:
        edge = t0 + k * bin_s
        while j + 1 < len(pts) and pts[j + 1][0] <= edge:
            j += 1
        out.append((pts[j][0], pts[j][1] - base))
        k += 1
    return out


def rnd(x, n=2):
    return None if x is None else round(x, n)


def analyze(rep, dev, win, series_dir):
    meta = json.loads((rep / "meta.json").read_text())
    kind = meta.get("kind", "rep")
    off = clock_off(meta)
    k0 = ts(meta.get("k6_started") or meta.get("fio_started"))
    name = rep.parent.parent.parent.name + "__" + rep.name if kind == "rep" else rep.name
    res = {"name": name, "kind": kind, "status": meta.get("status"), "pg_preset": meta.get("pg_preset"), "clock_offset_s": rnd(off, 4)}
    # docker stats 반감 — 시각은 서버 시계 → PC 시계(− 시계 차)
    drop = None
    if kind == "rep":
        bio = [(t - off, w) for t, w in bio_series(rep)]
        drop = find_drop([(t - k0, w) for t, w in bio], 30)
        res["docker_drop"] = None if not drop else {"k6_s": round(drop["t"]), "gb_at_drop": rnd(drop["bytes"] / 1e9), "prev_mbps": rnd(drop["prev_mbps"], 1),
                                                      "drop_mbps": rnd(drop["drop_mbps"], 1), "next_mbps": rnd(drop["next_mbps"], 1)}
    samples = host_samples(rep / "timeline-host.jsonl")
    res["host_samples"] = len(samples)
    if samples:
        rows = per_second(samples, dev)
        # 호스트 시각(서버) → k6 초: t − 시계 차 − k0
        rel = [(t - off - k0, dt, d) for t, dt, d in rows]
        res["host_dev"] = dev
        if drop:
            res["host_pre"] = {k: rnd(v) for k, v in (window(rel, drop["t"] - win, drop["t"]) or {}).items()}
            res["host_post"] = {k: rnd(v) for k, v in (window(rel, drop["t"], drop["t"] + win) or {}).items()}
        hpts = host_cum_points([dict(r, t=r["t"] - off - k0) for r in samples], dev, 0.0)
        hd = find_drop(hpts, 30)
        res["host_drop"] = None if not hd else {"s": round(hd["t"]), "gb_since_start": rnd(hd["bytes"] / 1e9), "prev_mbps": rnd(hd["prev_mbps"], 1),
                                                  "drop_mbps": rnd(hd["drop_mbps"], 1), "next_mbps": rnd(hd["next_mbps"], 1)}
        if hd:
            res["host_drop_pre"] = {k: rnd(v) for k, v in (window(rel, hd["t"] - win, hd["t"]) or {}).items()}
            res["host_drop_post"] = {k: rnd(v) for k, v in (window(rel, hd["t"], hd["t"] + win) or {}).items()}
        if kind == "fio":
            res["host_cum_gb_by_10s"] = [[round(t), rnd(v / 1e9)] for t, v in hpts]
        if series_dir:
            series_dir.mkdir(parents=True, exist_ok=True)
            cols = ("wMBps", "w_per_s", "w_await_ms", "flush_per_s", "flush_avg_ms", "aqu_sz", "util_pct", "dirty_MB", "writeback_MB", "psi_some_pct", "psi_full_pct")
            with open(series_dir / f"{name}.csv", "w") as f:
                f.write("s," + ",".join(cols) + "\n")
                for t, dt, d in rel:
                    a = agg([(t, dt, d)])
                    f.write(f"{t:.1f}," + ",".join("" if a[c] is None else f"{a[c]:.3f}" for c in cols) + "\n")
    return res


def find_reps(root):
    if (root / "meta.json").exists():
        return [root]
    out = []
    for p in sorted(root.rglob("meta.json")):
        d = p.parent
        if REP_RE.match(d.name) and (d / "timeline-server.jsonl").exists():
            out.append(d)
        elif d.name.startswith("fio-") and (d / "timeline-host.jsonl").exists():
            out.append(d)
    return out


def fmt(x):
    return "-" if x is None else (f"{x:.0f}" if abs(x) >= 100 else f"{x:.2f}" if abs(x) < 10 else f"{x:.1f}")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--coverage":
        a = sys.argv[2:]
        if len(a) not in (4, 5):
            print(__doc__, file=sys.stderr)
            return 2
        return coverage(a[0], a[1], a[2], float(a[3]), float(a[4]) if len(a) > 4 else 0.9)
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--json")
    ap.add_argument("--series-dir")
    ap.add_argument("--dev", default=DEVS_DEFAULT)
    ap.add_argument("--win", type=float, default=30)
    args = ap.parse_args()
    root = Path(args.root)
    reps = find_reps(root)
    if not reps:
        print(f"회차 없음: {root}", file=sys.stderr)
        return 1
    res = [analyze(r, args.dev, args.win, Path(args.series_dir) if args.series_dir else None) for r in reps]
    keys = ("wMBps", "w_await_ms", "flush_per_s", "flush_avg_ms", "aqu_sz", "util_pct", "dirty_MB", "writeback_MB", "psi_full_pct")
    print(f"# 쓰기 반감 — `{root.name}` (docker stats 06a 정의 · 호스트 {args.dev} 창 ±{args.win:g}초)\n")
    print("| 회차 | status | 반감 k6 초 | GB-at-drop | MB/s 직전 → 반감·다음 | 호스트 표본 | 호스트 반감 초 (GB) | " + " | ".join(f"{k} 전→후" for k in keys) + " |")
    print("|" + "---|" * (7 + len(keys)))
    for r in res:
        d = r.get("docker_drop")
        hd = r.get("host_drop")
        pre, post = r.get("host_pre") or r.get("host_drop_pre") or {}, r.get("host_post") or r.get("host_drop_post") or {}
        cells = [r["name"], str(r.get("status")), "-" if not d else str(d["k6_s"]), "-" if not d else fmt(d["gb_at_drop"]),
                 "-" if not d else f"{fmt(d['prev_mbps'])} → {fmt(d['drop_mbps'])}·{fmt(d['next_mbps'])}", str(r.get("host_samples", 0)),
                 "-" if not hd else f"{hd['s']} ({fmt(hd['gb_since_start'])})"]
        cells += [f"{fmt(pre.get(k))} → {fmt(post.get(k))}" if (pre or post) else "-" for k in keys]
        print("| " + " | ".join(cells) + " |")
    print("\n창 기준: docker 반감이 있으면 그 시각, 없고 호스트 반감만 있으면(fio 등) 호스트 반감 시각. '반감 없음' = '-'.")
    if args.json:
        Path(args.json).write_text(json.dumps(res, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
