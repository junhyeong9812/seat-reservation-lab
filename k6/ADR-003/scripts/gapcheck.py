#!/usr/bin/env python3
"""측정 중 k6 PC~서버 경로 단절 판정(ADR-002 A2 인계) — 서버 지표 수집(SSH, 10초 간격) 시각 사이의 공백이 임계를 넘으면 exit 1.

    scripts/gapcheck.py <timeline-server.jsonl> <k6_started ISO> <k6_ended ISO> <임계 초>

k6 실행 구간의 양 끝도 공백 계산에 넣는다 — 구간 안 표본이 하나도 없으면 구간 전체가 공백이다.
공백은 경로 단절(와이파이·NAT)과 서버 쪽 정체를 구분하지 못한다 — 어느 쪽이든 그 회차의 측정을 믿을 수 없으므로 재측정 대상으로 표시한다.
"""
import json
import sys
from datetime import datetime


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def main(path, started, ended, threshold):
    t0, t1 = ts(started), ts(ended)
    stamps = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line.startswith('{"t"'):
                t = ts(json.loads(line)["t"])
                if t0 <= t <= t1:
                    stamps.append(t)
    edges = [t0] + sorted(stamps) + [t1]
    gap = max(b - a for a, b in zip(edges, edges[1:]))
    if gap > threshold:
        print(f"path-gap: 서버 지표 수집 공백 {gap:.0f}s > {threshold}s", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 5:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])))
