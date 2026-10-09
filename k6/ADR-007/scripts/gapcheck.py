#!/usr/bin/env python3
"""측정 중 k6 PC~서버 경로 단절 판정(ADR-002 A2 인계) — 서버 지표 수집(SSH, 10초 간격) 시각 사이의 공백이 임계를 넘으면 exit 1.

    scripts/gapcheck.py <timeline.jsonl> <k6_started ISO> <k6_ended ISO> <임계 초> [<시계 차 초> [<최소 표본 수>]]

k6 실행 구간의 양 끝도 공백 계산에 넣는다 — 구간 안 표본이 하나도 없으면 구간 전체가 공백이다.
공백은 경로 단절(와이파이·NAT)과 서버 쪽 정체를 구분하지 못한다 — 어느 쪽이든 그 회차의 측정을 믿을 수 없으므로 재측정 대상으로 표시한다.
ADR-007(리뷰 A3·A10): 대기 표본(timeline-waits.jsonl)처럼 시각이 서버 시계인 파일은 <시계 차 초>(서버 − PC, run.sh clock-offset.json)를 빼서 PC 시계의 k6 구간과 비교한다(기본 0).
  <최소 표본 수>를 주면 k6 구간 안 표본이 그보다 적을 때 exit 3 — 짧은 셀(S2·S7은 k6가 몇 초)은 구간이 임계(30초)보다 짧아 공백 판정으로는 표본 0을 못 잡는다.
"""
import json
import sys
from datetime import datetime


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def main(path, started, ended, threshold, offset=0.0, min_samples=0):
    t0, t1 = ts(started), ts(ended)
    stamps = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line.startswith('{"t"'):
                t = ts(json.loads(line)["t"]) - offset
                if t0 <= t <= t1:
                    stamps.append(t)
    if len(stamps) < min_samples:
        print(f"samples: k6 구간 안 표본 {len(stamps)}개 < {min_samples}", file=sys.stderr)
        return 3
    edges = [t0] + sorted(stamps) + [t1]
    gap = max(b - a for a, b in zip(edges, edges[1:]))
    if gap > threshold:
        print(f"path-gap: 서버 지표 수집 공백 {gap:.0f}s > {threshold}s", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if not 5 <= len(sys.argv) <= 7:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]),
                  float(sys.argv[5]) if len(sys.argv) > 5 else 0.0, int(sys.argv[6]) if len(sys.argv) > 6 else 0))
