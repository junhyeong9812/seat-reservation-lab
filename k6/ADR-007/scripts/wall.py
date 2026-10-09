#!/usr/bin/env python3
"""벽 분석(ADR-007 명세 §9.3 — ADR-006 s4_wall.py 일반화): S4 계단·S4c 고정 도착률 회차마다 지연이 처음 무너진 초와 그 시각의 정황.

    scripts/wall.py <결과 폴더> [--json <out.json>]     → 표 출력(markdown) · --json이면 회차별 기록도

<결과 폴더> 아래의 `*/L*/S4*/rep<k>`·`L*/S4*/rep<k>`(캠페인 폴더·조건 폴더 어느 쪽이든)를 찾는다. 보존된 옛 회차(`rep<k>.retry-*` 등)는 뺀다.
포함 회차 = status `ok`(S4는 단독 `s4-no-successful-stage`도 — compare.py 포함 규칙과 같다).

정의 (초 = k6 시작(meta.k6_started) 기준, k6-requests.csv.gz http_req_duration 행의 타임스탬프 = 완료 시각(초 단위)으로 묶는다)
  무너진 초(벽) = 초별 p99 > 200ms가 처음 나온 초. 기준 200ms는 엄격 한계 기준 500ms보다 낮게 잡아 무너지기 시작한 초를 찾는다(ADR-006 §5.3 ②).
    - 계단(S4): ADR-006 s4_wall.py와 같다 — 11단계(목표 4,325건/s) 시작 = 325~344초 중 초당 완료가 3,000건 이상이 된 첫 초,
      벽 = 그 초부터 370초 전(12단계 앞 10초까지 — ADR-006 S4-WALL.md와 같은 창) 안에서 찾는다. 11단계 시작을 못 찾으면 '-'.
    - 고정(S4c*): k6 시작 0초부터 계획 시간(구간 수 × 구간 초, k6-summary.json) 전까지 전체에서 찾는다.
    - 무너지지 않으면 벽과 그에 딸린 열은 전부 '-'.
  누적 선점 = 벽 초 이전(벽 초 제외)에 완료된 201 응답 수(같은 CSV — 요청이 끝난 시각 기준).
  DB 기동 후 경과 = 벽 시각 − DB 로그(db.log.gz)의 'database system is ready to accept connections' 중 k6 시작 전 마지막 줄(초기화용 임시 서버 다음의 실제 서버).
  체크포인트 = DB 로그 log_checkpoints의 'checkpoint starting: <종류>' ~ 'checkpoint complete' 쌍(shutdown 제외). 벽 시각이 그 사이면 진행 중(종류·k6 기준 시작~완료 초).
    k6 구간(k6_started~k6_ended)과 겹친 체크포인트 목록도 함께 남긴다(체크포인트 없음 조건에서 측정 중 체크포인트가 없었는지 — 명세 §8 가정 1).
  대기 이벤트 = timeline-waits.jsonl(lib.sh db_sampler — 0.5초 표본) 중 벽 ±3초 표본의 대기 이벤트별 평균 활성 세션 수 상위 5개(+ 표본 수).
    같은 창의 체크포인터 대기 이벤트(bg.checkpointer)도 남긴다.
"""
import collections
import csv
import gzip
import json
import re
import sys
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

WALL_P99_MS = 200
STEP_START = (325, 345, 3000)   # 11단계 시작 탐색 창 [325, 345) · 초당 완료 기준(10단계 목표 2,883건/s 위)
STEP_END = 370                  # 계단 벽 탐색 끝(제외) — ADR-006 s4_wall.py와 같다
WAIT_WINDOW_S = 3
PG_TS = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?) UTC ")


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def pg_ts(line):
    m = PG_TS.match(line)
    return datetime.fromisoformat(m.group(1)).replace(tzinfo=timezone.utc).timestamp() if m else None


def db_log(rep_dir, k0):
    """DB 로그 → (k6 시작 전 마지막 ready 시각, [체크포인트 {kind, start, end}])."""
    path = rep_dir / "db.log.gz"
    ready, ckpts, cur = None, [], None
    if not path.exists():
        return None, None
    with gzip.open(path, "rt", errors="replace") as f:
        for line in f:
            t = pg_ts(line)
            if t is None:
                continue
            if "database system is ready to accept connections" in line and t <= k0:
                ready = t
            m = re.search(r"checkpoint starting: (.*)$", line)
            if m:
                cur = {"kind": m.group(1).strip(), "start": t, "end": None}
                ckpts.append(cur)
            elif "checkpoint complete" in line and cur is not None and cur["end"] is None:
                cur["end"] = t
    return ready, [c for c in ckpts if "shutdown" not in c["kind"]]


def waits_near(rep_dir, t_wall):
    """벽 ±WAIT_WINDOW_S초 표본의 대기 이벤트 평균 세션 수 상위 5 · 표본 수 · 체크포인터 대기 이벤트 분포."""
    path = rep_dir / "timeline-waits.jsonl"
    if not path.exists():
        return None, 0, None
    tot, ck, n = collections.Counter(), collections.Counter(), 0
    for line in path.read_text().splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "waits" not in row or abs(ts(row["t"]) - t_wall) > WAIT_WINDOW_S:
            continue
        n += 1
        tot.update(row["waits"] or {})
        if (row.get("bg") or {}).get("checkpointer"):
            ck[row["bg"]["checkpointer"]] += 1
    if not n:
        return None, 0, None
    return [(k, v / n) for k, v in tot.most_common(5)], n, dict(ck)


def one(rep_dir):
    rep_dir = Path(rep_dir)
    meta = json.loads((rep_dir / "meta.json").read_text())
    cell = rep_dir.parts[-2]
    fixed = cell.startswith("S4c")
    k0 = ts(meta["k6_started"])
    k1 = ts(meta["k6_ended"]) if meta.get("k6_ended") else None
    summ = json.loads((rep_dir / "k6-summary.json").read_text()) if (rep_dir / "k6-summary.json").exists() else {}
    planned_s = len(summ.get("stage_rates") or []) * summ.get("step_seconds", 30)
    lat = collections.defaultdict(list)
    ok_per_s = collections.Counter()
    with gzip.open(rep_dir / "k6-requests.csv.gz", "rt") as f:
        r = csv.reader(f)
        header = next(r)
        i_status = header.index("status")
        for row in r:
            if row[0] == "http_req_duration":
                t = int(int(row[1]) - k0)
                lat[t].append(float(row[2]))
                if row[i_status] == "201":
                    ok_per_s[t] += 1
    p99 = lambda t: sorted(lat[t])[int(len(lat[t]) * 0.99)] if lat[t] else None
    if fixed:
        start, lo, hi = 0, 0, planned_s
    else:
        start = next((t for t in range(*STEP_START[:2]) if len(lat[t]) >= STEP_START[2]), None)
        lo, hi = start, STEP_END
    wall = next((t for t in range(lo, hi) if lat[t] and p99(t) > WALL_P99_MS), None) if start is not None else None
    ready, ckpts = db_log(rep_dir, k0)
    rel = lambda x: None if x is None else round(x - k0, 1)
    in_k6 = [c for c in (ckpts or []) if (k1 is None or c["start"] <= k1) and (c["end"] is None or c["end"] >= k0)]
    rec = {"cond": rep_dir.parts[-4] if len(rep_dir.parts) >= 4 else "", "level": rep_dir.parts[-3], "cell": cell, "rep": rep_dir.name,
           "status": (rep_dir / "status").read_text().strip(), "fixed": fixed, "step11_start_s": None if fixed else start, "wall_s": wall,
           "wall_minus_start_s": None if fixed or wall is None or start is None else wall - start,
           "db_ready_to_k6_s": None if ready is None else round(k0 - ready, 1),
           "checkpoints_during_k6": None if ckpts is None else [{"kind": c["kind"], "start_s": rel(c["start"]), "end_s": rel(c["end"])} for c in in_k6],
           "wall_p99_ms": None, "cum_201_before_wall": None, "db_uptime_at_wall_s": None, "checkpoint_at_wall": None,
           "waits_top": None, "waits_samples": 0, "checkpointer_at_wall": None}
    if wall is not None:
        t_wall = k0 + wall
        rec["wall_p99_ms"] = p99(wall)
        rec["cum_201_before_wall"] = sum(v for t, v in ok_per_s.items() if t < wall)
        rec["db_uptime_at_wall_s"] = None if ready is None else round(t_wall - ready, 1)
        if ckpts is not None:
            cur = next((c for c in ckpts if c["start"] <= t_wall and (c["end"] is None or t_wall < c["end"])), None)
            rec["checkpoint_at_wall"] = {"kind": cur["kind"], "start_s": rel(cur["start"]), "end_s": rel(cur["end"])} if cur else False
        rec["waits_top"], rec["waits_samples"], rec["checkpointer_at_wall"] = waits_near(rep_dir, t_wall)
    return rec


def reps_of(root):
    root = Path(root)
    found = set(root.glob("*/L*/S4*/rep[0-9]*")) | set(root.glob("L*/S4*/rep[0-9]*"))
    out = []
    for p in sorted(found):
        if not p.is_dir() or "." in p.name or not (p / "status").exists():
            continue
        st = (p / "status").read_text().strip()
        if st == "ok" or (p.parts[-2] == "S4" and st == "s4-no-successful-stage"):
            out.append(p)
    return out


def fmt_ck(c):
    if c is None:
        return "-"
    if c is False:
        return "아니오"
    return f'예({c["kind"]} +{c["start_s"]:.0f}~{"+%.0f" % c["end_s"] if c["end_s"] is not None else "끝 기록 없음"})'


def main(argv):
    if not argv or argv[0].startswith("-"):
        print(__doc__, file=sys.stderr)
        return 2
    root, out_json = argv[0], None
    if len(argv) == 3 and argv[1] == "--json":
        out_json = argv[2]
    elif len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    reps = reps_of(root)
    with ProcessPoolExecutor(8) as ex:
        recs = list(ex.map(one, reps))
    d = lambda v: "-" if v is None else (f"{v:,.0f}" if isinstance(v, (int, float)) else str(v))
    print("| 조건 | 단계 | 셀 | 회차 | 11단계 시작 초 | 무너진 초(k6 기준) | 차이 s | 그 초 p99 ms | 누적 선점(201) | DB 기동 후 경과 s | 체크포인트 진행 중 | "
          "대기 이벤트 상위 5(벽 ±3초 평균 세션 · 표본) | 체크포인터(벽 ±3초) | k6 구간 체크포인트(종류 +시작~완료) |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in recs:
        if r["wall_s"] is None:
            wt, ck, cp = "-", "-", "-"
        else:
            wt = ("; ".join(f"{k} {v:.2f}" for k, v in r["waits_top"]) + f" ({r['waits_samples']})") if r["waits_top"] else "표본 없음"
            ck = fmt_ck(r["checkpoint_at_wall"])
            cp = ", ".join(f"{k}×{v}" for k, v in (r["checkpointer_at_wall"] or {}).items()) or "-"
        during = "-" if r["checkpoints_during_k6"] is None else (
            ", ".join(f'{c["kind"]} +{c["start_s"]:.0f}~{"+%.0f" % c["end_s"] if c["end_s"] is not None else "끝 기록 없음"}' for c in r["checkpoints_during_k6"]) or "없음")
        print(f'| {r["cond"]} | {r["level"]} | {r["cell"]} | {r["rep"]} | {d(r["step11_start_s"])} | {d(r["wall_s"])} | {d(r["wall_minus_start_s"])} | '
              f'{d(r["wall_p99_ms"])} | {d(r["cum_201_before_wall"])} | {d(r["db_uptime_at_wall_s"]) if r["wall_s"] is not None else "-"} | {ck} | {wt} | {cp} | {during} |')
    if out_json:
        Path(out_json).write_text(json.dumps(recs, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
