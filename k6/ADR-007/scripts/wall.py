#!/usr/bin/env python3
"""벽 분석(ADR-007 명세 §9.3 — ADR-006 s4_wall.py 일반화): S4 계단·S4c 고정 도착률 회차마다 지연이 처음 무너진 초와 그 시각의 정황.

    scripts/wall.py <결과 폴더> [--json <out.json>]     → 표 출력(markdown) · --json이면 회차별 기록도
    scripts/wall.py --rep-check <회차 폴더> <k6_started ISO> <k6_ended ISO>
                                                      → 회차 하나의 기록을 JSON 한 줄로(run.sh rep_status가 회차 판정에 쓴다 — meta.json·status가 아직 없을 때)

<결과 폴더> 아래의 `*/L*/S4*/rep<k>`·`L*/S4*/rep<k>`(캠페인 폴더·조건 폴더 어느 쪽이든)를 찾는다. 보존된 옛 회차(`rep<k>.retry-*` 등)는 뺀다.
포함 회차 = status `ok`(S4는 단독 `s4-no-successful-stage`도 — compare.py 포함 규칙과 같다).

정의 (초 = k6 시작(meta.k6_started) 기준, k6-requests.csv.gz http_req_duration 행의 타임스탬프 = 완료 시각(초 단위)으로 묶는다)
  나쁜 초 = 그 초의 p99 > 200ms 또는 응답 코드 0(요청 타임아웃·연결 실패·연결 끊김) 완료가 그 초 완료의 1% 이상.
    p99는 코드 0 행을 뺀 지연으로 낸다(리뷰 A12 — k6는 코드 0 요청의 지연을 0에 가깝게 기록한다: 스모크 smoke-b 코드 0 20,222건 중 18,538건 < 5ms.
    섞으면 초별 p99가 낮게 나오고 시작 초(완료 − 지연)도 틀린다). 기준 200ms는 엄격 한계 기준 500ms보다 낮게 잡아 무너지기 시작한 초를 찾는다(ADR-006 §5.3 ②).
  계단(S4): 벽(wall_s) = 첫 단계 뒤(시나리오 시작 + 30초 — k6-summary.json setup_data.startedAt, 없으면 k6 시작 + 30초)부터 마지막 완료까지 **전체**에서 처음 나쁜 초
    (리뷰 A1 — ADR-006 s4_wall.py는 11단계 [시작, 370초) 창만 봐서 벽이 밀리거나·일찍 무너지거나·없으면 모두 '-'였다). wall_stage = 그 초에 끝난 요청의 stage 태그 최빈값.
    ADR-006 비교용으로 step11_start_s(325~344초 중 초당 완료 ≥ 3,000인 첫 초)·wall_minus_start_s(= 벽 − 그 초)도 그대로 낸다.
  고정(S4c*): 램프(stage 태그 'ramp' — 리뷰 A13, 시나리오 RAMP_S)의 요청은 전부 뺀다. 구간 = 램프 끝(startedAt + ramp_s)부터 계획 시간(구간 수 × 구간 초).
    wall_s = 그 구간에서 처음 나쁜 초(완료 초 — 명세 정의 그대로). 기동 과도에 대비해(램프가 있으면 보통 곧바로 끝난다) 과도 뒤 벽도 낸다 —
    settle_s = 처음으로 SETTLE_RUN초 연속 '시작 초 기준 나쁘지 않은 초'가 시작된 초, wall_after_settle_s = 그 뒤 처음 나쁜 시작 초.
    시작 초 = 완료 초 − 지연(코드 0 행은 지연이 틀려 뺀다). 시작 초 기준 나쁜 초 = 그 초에 시작한 요청의 p99 > 200ms 또는 그 초(완료 초)의 코드 0 비율 ≥ 1%
    (코드 0은 실패가 드러난 시각에 센다). 완료 초로 묶으면 과도 중에 보낸 요청이 30초 타임아웃으로 끝나는 초가 '과도 뒤 벽'으로 잡힌다.
  결과(wall_outcome): `wall`(벽 있음 — wall_stage) · `무너지지 않음`(창 안에 완료가 있었는데 나쁜 초가 없다) · `과도 미종료`(고정: settle_s 없음 — 6분 동안
    5초 연속 정상이 한 번도 없었다 = 처음부터 무너진 상태, 리뷰 A2) · `표본 없음`(창 안에 완료가 하나도 없다). 표의 '-'는 해당 열이 정의되지 않는다는 뜻이다.
  정황(누적·체크포인트·대기 이벤트)은 계단 = wall_s, 고정 = wall_after_settle_s 기준.
  누적 선점 = 벽 초 이전(벽 초 제외)에 완료된 201 응답 수(같은 CSV — 요청이 끝난 시각 기준, 고정 셀은 램프의 201도 센다 — DB에 쌓인 홀드 행 수).
  시계 차(리뷰 A3): 대기 표본(clock_timestamp())·DB 로그는 서버 시계, 벽·k6 구간은 k6 PC 시계다. run.sh가 k6 바로 앞·뒤에 잰 서버 − PC 차
    (clock-offset.json · meta.json clock_offset — lib.sh clock_offset)의 평균을 서버 시각에서 빼서 PC 시계로 옮긴다(없으면 0 — clock_offset_s = null로 드러난다).
  DB 기동 후 경과 = 벽 시각 − DB 로그(db.log.gz)의 'database system is ready to accept connections' 중 k6 시작 전 마지막 줄(초기화용 임시 서버 다음의 실제 서버).
  체크포인트 = DB 로그 log_checkpoints의 'checkpoint starting: <종류>' ~ 'checkpoint complete' 쌍(shutdown 제외). 벽 시각이 그 사이면 진행 중(종류·k6 기준 시작~완료 초).
    k6 구간(k6_started~k6_ended)과 겹친 체크포인트 목록도 함께 남긴다(체크포인트 없음 조건에서 측정 중 체크포인트가 없었는지 — 명세 §8 가정 1, rep_status checkpoint-during-k6).
  대기 이벤트 = timeline-waits.jsonl(lib.sh db_sampler — 0.5초 표본) 중 벽 ±3초 표본의 대기 이벤트별 평균 활성 세션 수 상위 5개(+ 표본 수 —
    기대 약 12개, 6개 미만이면 rep_status waits-thin-at-wall). 같은 창의 체크포인터 대기 이벤트(bg.checkpointer)도 남긴다.
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
ZERO_SHARE = 0.01               # 코드 0 완료 비율이 이 이상이면 나쁜 초(리뷰 A12)
STEP_START = (325, 345, 3000)   # 11단계 시작 탐색 창 [325, 345) · 초당 완료 기준(10단계 목표 2,883건/s 위) — ADR-006 비교용
WAIT_WINDOW_S = 3
WAITS_MIN = 6                   # 벽 ±3초 표본 하한(0.5초 표본이라 기대 약 12) — rep_status waits-thin-at-wall
SETTLE_RUN = 5                  # 고정 셀 기동 과도 끝 = 나쁘지 않은 시작 초가 이만큼 연속인 첫 초
CLOCK_MAX_S = 0.5               # |시계 차|가 이보다 크면 rep_status clock-offset-large
PG_TS = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?) UTC ")


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def pg_ts(line):
    m = PG_TS.match(line)
    return datetime.fromisoformat(m.group(1)).replace(tzinfo=timezone.utc).timestamp() if m else None


def load(path):
    try:
        return json.loads(Path(path).read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def clock_offset(rep_dir, meta):
    """서버 − PC 시계 차(초) — meta.json clock_offset(run.sh finish_rep) 또는 회차 폴더 clock-offset.json의 앞·뒤 측정 평균. 측정이 없으면 None."""
    c = (meta or {}).get("clock_offset") or load(rep_dir / "clock-offset.json") or {}
    vals = [x["offset_s"] for x in (c.get("before"), c.get("after")) if isinstance(x, dict) and isinstance(x.get("offset_s"), (int, float))]
    return (sum(vals) / len(vals) if vals else None), vals


def db_log(rep_dir, k0, off):
    """DB 로그 → (k6 시작 전 마지막 ready 시각, [체크포인트 {kind, start, end}]) — 시각은 PC 시계(서버 시각 − off)."""
    path = rep_dir / "db.log.gz"
    ready, ckpts, cur = None, [], None
    if not path.exists():
        return None, None
    with gzip.open(path, "rt", errors="replace") as f:
        for line in f:
            t = pg_ts(line)
            if t is None:
                continue
            t -= off
            if "database system is ready to accept connections" in line and t <= k0:
                ready = t
            m = re.search(r"checkpoint starting: (.*)$", line)
            if m:
                cur = {"kind": m.group(1).strip(), "start": t, "end": None}
                ckpts.append(cur)
            elif "checkpoint complete" in line and cur is not None and cur["end"] is None:
                cur["end"] = t
    return ready, [c for c in ckpts if "shutdown" not in c["kind"]]


def waits_near(rep_dir, t_wall, off):
    """벽 ±WAIT_WINDOW_S초 표본의 대기 이벤트 평균 세션 수 상위 5 · 표본 수 · 체크포인터 대기 이벤트 분포(표본 시각 = 서버 시각 − off)."""
    path = rep_dir / "timeline-waits.jsonl"
    if not path.exists():
        return None, 0, None
    tot, ck, n = collections.Counter(), collections.Counter(), 0
    for line in path.read_text().splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "waits" not in row or abs(ts(row["t"]) - off - t_wall) > WAIT_WINDOW_S:
            continue
        n += 1
        tot.update(row["waits"] or {})
        if (row.get("bg") or {}).get("checkpointer"):
            ck[row["bg"]["checkpointer"]] += 1
    if not n:
        return None, 0, None
    return [(k, v / n) for k, v in tot.most_common(5)], n, dict(ck)


def p99(xs):
    return sorted(xs)[int(len(xs) * 0.99)] if xs else None


def one(rep_dir, meta=None, status=None):
    rep_dir = Path(rep_dir)
    meta = meta or json.loads((rep_dir / "meta.json").read_text())
    if status is None:
        status = (rep_dir / "status").read_text().strip() if (rep_dir / "status").exists() else "pending"
    cell = rep_dir.parts[-2]
    fixed = cell.startswith("S4c")
    k0 = ts(meta["k6_started"])
    k1 = ts(meta["k6_ended"]) if meta.get("k6_ended") else None
    off, off_vals = clock_offset(rep_dir, meta)
    o = off or 0.0
    summ = load(rep_dir / "k6-summary.json") or {}
    step_s = summ.get("step_seconds", 30)
    planned_s = len(summ.get("stage_rates") or []) * step_s
    ramp_s = summ.get("ramp_s") or 0
    started_ms = (summ.get("setup_data") or {}).get("startedAt")
    scen0 = started_ms / 1000 - k0 if started_ms else 0.0   # 시나리오 시작(setup 끝) − k6 시작, 초
    # 완료 초별: 코드 0이 아닌 지연 · 전체 완료 수 · 코드 0 완료 수 · stage 태그 / 시작 초별(고정 셀): 코드 0이 아닌 지연 · stage 태그 / 201 완료 수(누적 선점)
    lat, n_all, n0, tag_done = collections.defaultdict(list), collections.Counter(), collections.Counter(), collections.defaultdict(collections.Counter)
    lat_start, tag_start = collections.defaultdict(list), collections.defaultdict(collections.Counter)
    ok_per_s = collections.Counter()
    with gzip.open(rep_dir / "k6-requests.csv.gz", "rt", newline="") as f:
        header = next(csv.reader([f.readline()]))
        i_ts, i_val, i_status, i_tags = header.index("timestamp"), header.index("metric_value"), header.index("status"), header.index("extra_tags")
        for line in f:
            if not line.startswith("http_req_duration,"):
                continue
            row = next(csv.reader([line]))
            if len(row) <= i_tags:
                continue   # 잘린 마지막 행
            stage = next((kv[6:] for kv in row[i_tags].split("&") if kv.startswith("stage=")), "-")
            done = int(row[i_ts])
            t = int(done - k0)
            if row[i_status] == "201":
                ok_per_s[t] += 1
            if fixed and stage == "ramp":
                continue   # 램프 — 구간·과도·벽 밖(누적 선점에만 든다)
            d = float(row[i_val])
            n_all[t] += 1
            tag_done[t][stage] += 1
            if row[i_status] in ("0", ""):
                n0[t] += 1
                continue
            lat[t].append(d)
            if fixed:
                s = int(done - d / 1000 - k0)
                lat_start[s].append(d)
                tag_start[s][stage] += 1
    zero_bad = lambda t: n_all[t] and n0[t] >= ZERO_SHARE * n_all[t]
    bad = lambda t: bool(n_all[t]) and ((lat[t] and p99(lat[t]) > WALL_P99_MS) or zero_bad(t))
    step11 = None if fixed else next((t for t in range(*STEP_START[:2]) if n_all[t] >= STEP_START[2]), None)
    last = max(n_all, default=-1)
    if fixed:
        lo = int(scen0 + ramp_s)
        hi = lo + planned_s
    else:
        lo = int(scen0) + step_s   # 첫 단계 뒤부터(첫 단계 = 기동 직후 50/s — 초당 표본이 적고 JIT 등으로 흔들린다)
        hi = last + 1
    wall = next((t for t in range(lo, hi) if bad(t)), None)
    any_done = any(n_all[t] for t in range(lo, hi))
    if fixed:
        bad_s = lambda t: bool((lat_start[t] and p99(lat_start[t]) > WALL_P99_MS) or zero_bad(t))
        settle = next((t for t in range(lo, hi - SETTLE_RUN) if all(lat_start[u] and not bad_s(u) for u in range(t, t + SETTLE_RUN))), None)
        w = next((t for t in range(settle, hi) if bad_s(t)), None) if settle is not None else None
        outcome = "표본 없음" if not any_done else ("과도 미종료" if settle is None else ("wall" if w is not None else "무너지지 않음"))
        tags = tag_start
    else:
        settle, w = None, wall
        outcome = "표본 없음" if not any_done else ("wall" if wall is not None else "무너지지 않음")
        tags = tag_done
    ready, ckpts = db_log(rep_dir, k0, o)
    rel = lambda x: None if x is None else round(x - k0, 1)
    in_k6 = [c for c in (ckpts or []) if (k1 is None or c["start"] <= k1) and (c["end"] is None or c["end"] >= k0)]
    rec = {"cond": rep_dir.parts[-4] if len(rep_dir.parts) >= 4 else "", "level": rep_dir.parts[-3], "cell": cell, "rep": rep_dir.name,
           "status": status, "fixed": fixed, "window_s": [lo, hi], "ramp_s": ramp_s,
           "step11_start_s": step11, "wall_s": wall, "wall_minus_start_s": None if fixed or wall is None or step11 is None else wall - step11,
           "settle_s": settle, "wall_after_settle_s": w if fixed else None, "wall_outcome": outcome, "wall_stage": None,
           "clock_offset_s": None if off is None else round(off, 4), "clock_offsets_s": off_vals,
           "db_ready_to_k6_s": None if ready is None else round(k0 - ready, 1),
           "checkpoints_during_k6": None if ckpts is None else [{"kind": c["kind"], "start_s": rel(c["start"]), "end_s": rel(c["end"])} for c in in_k6],
           "wall_p99_ms": None, "wall_zero_share": None, "cum_201_before_wall": None, "db_uptime_at_wall_s": None, "checkpoint_at_wall": None,
           "waits_top": None, "waits_samples": 0, "checkpointer_at_wall": None}
    if w is not None:
        t_wall = k0 + w
        st = tags[w].most_common(1)
        rec["wall_stage"] = st[0][0] if st else None
        rec["wall_p99_ms"] = p99(lat_start[w]) if fixed else p99(lat[w])
        rec["wall_zero_share"] = round(n0[w] / n_all[w], 4) if n_all[w] else None
        rec["cum_201_before_wall"] = sum(v for t, v in ok_per_s.items() if t < w)
        rec["db_uptime_at_wall_s"] = None if ready is None else round(t_wall - ready, 1)
        if ckpts is not None:
            cur = next((c for c in ckpts if c["start"] <= t_wall and (c["end"] is None or t_wall < c["end"])), None)
            rec["checkpoint_at_wall"] = {"kind": cur["kind"], "start_s": rel(cur["start"]), "end_s": rel(cur["end"])} if cur else False
        rec["waits_top"], rec["waits_samples"], rec["checkpointer_at_wall"] = waits_near(rep_dir, t_wall, o)
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


def outcome_txt(r):
    """벽 결과 한 칸: 벽 초(단계) 또는 무너지지 않음·과도 미종료·표본 없음 — '-'로 뭉개지 않는다(리뷰 A1·A2)."""
    w = r["wall_after_settle_s"] if r["fixed"] else r["wall_s"]
    if r["wall_outcome"] == "wall":
        return f'{w:,.0f}초({"구간" if r["fixed"] else "단계"} {r["wall_stage"]})'
    return r["wall_outcome"]


def main(argv):
    if len(argv) == 4 and argv[0] == "--rep-check":
        rep = Path(argv[1])
        rec = one(rep, meta={"k6_started": argv[2], "k6_ended": argv[3]})
        print(json.dumps({k: rec[k] for k in ("wall_outcome", "wall_s", "wall_after_settle_s", "wall_stage", "settle_s", "waits_samples",
                                              "checkpoints_during_k6", "clock_offset_s", "clock_offsets_s")}, ensure_ascii=False))
        return 0
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
    print("| 조건 | 단계 | 셀 | 회차 | 벽 결과(계단 = 첫 단계 뒤 전체 · 고정 = 과도 뒤, 시작 초) | 11단계 시작 초 | 무너진 초(완료 초 원 정의) | 차이 s(벽 − 11단계 시작) | 과도 끝 초(고정) | "
          "그 초 p99 ms(코드 0 제외) | 그 초 코드 0 비율 | 누적 선점(201) | DB 기동 후 경과 s | 체크포인트 진행 중 | "
          "대기 이벤트 상위 5(벽 ±3초 평균 세션 · 표본) | 체크포인터(벽 ±3초) | k6 구간 체크포인트(종류 +시작~완료) | 시계 차 s(서버 − PC) |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in recs:
        w = r["wall_after_settle_s"] if r["fixed"] else r["wall_s"]
        if w is None:
            wt, ck, cp = "-", "-", "-"
        else:
            wt = ("; ".join(f"{k} {v:.2f}" for k, v in r["waits_top"]) + f" ({r['waits_samples']})") if r["waits_top"] else "표본 없음"
            ck = fmt_ck(r["checkpoint_at_wall"])
            cp = ", ".join(f"{k}×{v}" for k, v in (r["checkpointer_at_wall"] or {}).items()) or "-"
        during = "-" if r["checkpoints_during_k6"] is None else (
            ", ".join(f'{c["kind"]} +{c["start_s"]:.0f}~{"+%.0f" % c["end_s"] if c["end_s"] is not None else "끝 기록 없음"}' for c in r["checkpoints_during_k6"]) or "없음")
        settle = ("과도 미종료" if r["settle_s"] is None else d(r["settle_s"])) if r["fixed"] else "-"
        zs = "-" if r["wall_zero_share"] is None else f'{r["wall_zero_share"]:.1%}'
        clk = "미측정" if r["clock_offset_s"] is None else f'{r["clock_offset_s"]:+.3f}'
        print(f'| {r["cond"]} | {r["level"]} | {r["cell"]} | {r["rep"]} | {outcome_txt(r)} | {d(r["step11_start_s"])} | {d(r["wall_s"])} | {d(r["wall_minus_start_s"])} | '
              f'{settle} | {d(r["wall_p99_ms"])} | {zs} | {d(r["cum_201_before_wall"])} | {d(r["db_uptime_at_wall_s"]) if w is not None else "-"} | {ck} | {wt} | {cp} | {during} | {clk} |')
    if out_json:
        Path(out_json).write_text(json.dumps(recs, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
