#!/usr/bin/env python3
"""S4 11단계(목표 4,325건/s) 안에서 지연이 처음 무너지는 초를 찾는다 — ADR-006 §5.3 ② 근거 2.

    scripts/s4_wall.py results/<campaign-id>     → 표 출력(조건 · 단계 · 회차 · 11단계 시작 초 · 무너진 초 · 차이)

회차마다 k6-requests.csv.gz의 http_req_duration 행(타임스탬프 = 완료 시각, 초 단위)을 meta.k6_started 기준 초로 묶는다.
11단계 시작 = 325~345초 중 초당 완료가 3,000건 이상이 된 첫 초(10단계 목표 2,883건/s 위). 무너진 초 = 그 뒤 초별 p99가 200ms를 처음 넘는 초.
"""
import collections
import csv
import gzip
import json
import sys
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime
from pathlib import Path


def one(rep_dir):
    rep_dir = Path(rep_dir)
    k0 = datetime.fromisoformat(json.loads((rep_dir / "meta.json").read_text())["k6_started"]).timestamp()
    lat = collections.defaultdict(list)
    with gzip.open(rep_dir / "k6-requests.csv.gz", "rt") as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            if row[0] == "http_req_duration":
                t = int(int(row[1]) - k0)
                if 300 <= t < 370:
                    lat[t].append(float(row[2]))
    start = next((t for t in range(325, 345) if len(lat[t]) >= 3000), None)
    p99 = lambda t: sorted(lat[t])[int(len(lat[t]) * 0.99)] if lat[t] else None
    wall = next((t for t in range(start, 370) if lat[t] and p99(t) > 200), None) if start is not None else None
    lv, rep = rep_dir.parts[-3], rep_dir.name
    return rep_dir.parts[-4], lv, rep, start, wall, (wall - start if start is not None and wall is not None else None)


def main(root):
    reps = sorted(p for p in Path(root).glob("*/L*/S4/rep[0-9]*") if p.is_dir() and "." not in p.name
                  and (p / "status").exists() and (p / "status").read_text().strip() in ("ok", "s4-no-successful-stage"))
    print("| 조건 | 단계 | 회차 | 11단계 시작 초 | 무너진 초 | 차이 s |\n|---|---|---|---|---|---|")
    with ProcessPoolExecutor(8) as ex:
        for row in ex.map(one, reps):
            print("| " + " | ".join("-" if v is None else str(v) for v in row) + " |")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
