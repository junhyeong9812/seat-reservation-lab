#!/usr/bin/env python3
"""S4 회차의 '멈춤' 구간을 찾는다 — 부하가 걸려 있는데 응답 완료가 STALL_S초 이상 하나도 없는 구간.

    scripts/stalls.py results/<campaign-id>     → <campaign-id>/stalls.json (+ 표 출력)

k6-requests.csv.gz의 http_req_duration 행(타임스탬프 = 완료 시각, 초 단위)으로 초별 완료 수를 세고,
첫 완료~마지막 완료 사이에서 완료 0인 연속 구간을 기록한다. 각 구간에
- offset_s: k6 시작(meta.k6_started) 기준 시작 시각
- phase: `tail`(k6 종료 TAIL_S초 이내 — 단계 중단 후 남은 요청이 끝나는 구간) / `mid`(부하 한가운데)
- 같은 시각 ±6초의 서버 CPU 최대(timeline-server.jsonl)와 표본 수 — 표본 0이면 서버 지표 수집도 끊긴 것
을 붙인다. CPU가 낮은 멈춤은 '서버가 일하지 않는데 완료가 없음'이고, 원인(경로 단절·서버 IO 정체 등)은 이것만으로 가를 수 없다.
중단된 회차(`rep*.incomplete-*`)는 따로 표시하고 집계에 넣지 않는다.
"""
import gzip
import json
import sys
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime
from pathlib import Path

STALL_S = 5
TAIL_S = 45


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def server_cpu(rep):
    rows, now = [], None
    path = rep / "timeline-server.jsonl"
    if not path.exists():
        return rows
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        if "t" in r:
            now = ts(r["t"])
        elif r.get("Name") in ("seatlab-app-1", "seatlab-db-1") and now is not None:
            rows.append((now, r["Name"][8:10], float(r["CPUPerc"].rstrip("%") or 0)))
    return rows


def one(rep):
    rep = Path(rep)
    per_sec, bad_lines = {}, 0
    try:
        with gzip.open(rep / "k6-requests.csv.gz", "rt") as f:
            f.readline()
            for line in f:
                if line.startswith("http_req_duration,"):
                    try:
                        t = int(line.split(",", 2)[1])
                    except (ValueError, IndexError):
                        bad_lines += 1  # 손상된 행 하나 — 그 행만 건너뛰고 센다
                        continue
                    per_sec[t] = per_sec.get(t, 0) + 1
    except EOFError:
        return str(rep), {"truncated": True, "stalls": []}
    meta = json.loads((rep / "meta.json").read_text())
    if not per_sec or not meta.get("k6_started") or not meta.get("k6_ended"):
        return str(rep), {"bad_lines": bad_lines, "stalls": []}
    k0, k1 = ts(meta["k6_started"]), ts(meta["k6_ended"])
    t0, t1 = min(per_sec), max(per_sec)
    cpu = server_cpu(rep)
    stalls, t = [], t0
    while t <= t1:
        if per_sec.get(t, 0) == 0:
            s = t
            while t <= t1 and per_sec.get(t, 0) == 0:
                t += 1
            if t - s >= STALL_S:
                near = [(n, c) for (ct, n, c) in cpu if s - 6 <= ct <= t + 6]
                stalls.append({"start": datetime.fromtimestamp(s).isoformat(), "offset_s": round(s - k0), "seconds": t - s,
                               "phase": "tail" if s >= k1 - TAIL_S else "mid",
                               "app_cpu_max_near": max((c for n, c in near if n == "ap"), default=None),
                               "db_cpu_max_near": max((c for n, c in near if n == "db"), default=None),
                               "cpu_samples_near": len(near) // 2})
        t += 1
    return str(rep), {"bad_lines": bad_lines, "stalls": stalls}


def main(root):
    root = Path(root)
    conds = [d for d in root.iterdir() if (d / "plan.json").exists()]   # 조건 폴더 = plan.json 있는 곳(이름 규칙 무관)
    # 정규 회차만(rep숫자) + 중단 회차(.incomplete) — 재측정으로 옆에 보존한 옛 회차(.retry-·.path-gap-)는 집계에 넣지 않는다
    reps = sorted(p for d in conds for p in d.glob("L*/S4/rep*") if (p / "k6-requests.csv.gz").exists()
                  and (p.name[3:].isdigit() or ".incomplete" in p.name))
    out, incomplete = {}, []
    with ProcessPoolExecutor(max_workers=8) as ex:
        for rep, res in ex.map(one, reps):
            key = str(Path(rep).relative_to(root))
            if ".incomplete" in key or res.get("truncated"):
                incomplete.append(key)
                continue
            out[key] = res
    (root / "stalls.json").write_text(json.dumps({"reps": out, "incomplete_or_truncated": incomplete}, indent=1, sort_keys=True))
    mid = {k: [s for s in v["stalls"] if s["phase"] == "mid"] for k, v in out.items()}
    tail = {k: [s for s in v["stalls"] if s["phase"] == "tail"] for k, v in out.items()}
    print(f"S4 회차 {len(out)}개(중단·잘린 회차 {len(incomplete)}개 별도) — 부하 중(mid) 멈춤 있는 회차 {sum(1 for v in mid.values() if v)}개, "
          f"종료 직전(tail) 멈춤만 있는 회차 {sum(1 for k in out if tail[k] and not mid[k])}개")
    for k in incomplete:
        print(f"{k}  (중단·잘린 회차 — 집계 제외)")
    for k, v in sorted(out.items()):
        if v["bad_lines"]:
            print(f"{k}  손상 행 {v['bad_lines']}개 건너뜀")
        for s in v["stalls"]:
            print(f"{k}  {s['phase']:4}  {s['start']}  +{s['offset_s']}s  {s['seconds']}s  "
                  f"app≤{s['app_cpu_max_near']}  db≤{s['db_cpu_max_near']}  표본 {s['cpu_samples_near']}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
