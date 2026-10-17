#!/usr/bin/env python3
"""ADR-009 확정 측정 비교(ADR §3 가설 H1~H8 · §5 판정 기준 · 명세 §5·§10·§11) — 캠페인 결과의 조건별 summary.json·effect.json·원시 파일에서 가설별 표를 산출한다.

    scripts/compare.py results/<campaign-id>     → <campaign-id>/COMPARISON.md · comparison.json
    scripts/compare.py --set cost-remeasure results/<campaign-id>   → 같은 파일(명세 §13 비용 셀 재측정 — 4조건 × 4셀 · 시작 상태·대조 위상·k6 구간 GC 표, 리뷰 R8)
    scripts/compare.py --set jvm-baseline results/<campaign-id>     → 같은 파일(ADR-010 앱 JVM 기준 — 1회 × 4조건 표: 수집기·힙·정상 구간 Full GC·GC 정지·지연·CPU·메모리)
    scripts/compare.py --set jvm-ladder results/<campaign-id>       → 같은 파일(ADR-010 2차 — G1·ZGC × 1g·2g·4g × 3회: 중앙값 [최소–최대] + 회차별 안정성 지표)

먼저 할 것(없으면 해당 칸은 '누락'으로 드러난다 — 채워 넣지 않는다):
  - 조건마다 `summarize.py results/<campaign-id>/<조건>` → summary.json(효과 셀 effect.json은 run.sh가 회차마다 이미 남긴다)
  - `wall.py results/<campaign-id> --json results/<campaign-id>/wall.json`(디스크 벽 시작) ·
    `wdrop.py results/<campaign-id> --json results/<campaign-id>/wdrop.json`(쓰기 반감)

규칙
  - 조건 = conditions.txt의 폴더 이름. 회차 포함 = status가 정확히 `ok`(ADR-008 compare.py와 같다). 보존된 옛 회차(rep<k>.retry-*)는 summarize.py가 읽지 않는다 —
    표 아래 '재측정' 줄에 따로 적는다.
  - 값 = 중앙값 [최소–최대](n = 그 값이 있는 회차 수). 값이 없는 회차가 있으면 `· 누락 k/n`을 붙인다. 셋 다 없으면 `누락`. 손 계산·추정으로 채우지 않는다.
  - 비율 = 조건 중앙값 ÷ 기준 중앙값 − 1(%) — H4: A+C·A+F ÷ A, H6: B ÷ control, H7: control ÷ control-noclose. 시각(벽·반감)은 차이(초, 조건 − 기준)로 낸다.
  - 각 표 아래 '반증 관측 사실' 한 줄 = 비교 값뿐이다. 가설의 성립·기각 판정은 쓰지 않는다(메인 세션 몫).
정의(출처)
  - S4c 성공 RPS = summarize fixed.ok_rps_total(201 ÷ 계획 시간, 램프 제외) · 선점 p50·p99 = k6 요약 hold_duration(램프·코드 0 포함 — 회차 전체) ·
    가장 나쁜 구간 p99(코드 0 제외) = fixed.worst_p99_nz_ms · 에러율 = hold_error rate.
  - 벽 시작 = wall.py 고정 셀 wall_after_settle_s(과도 뒤 처음 나쁜 시작 초, k6 시작 기준) — 결과가 wall이 아니면 그 결과 문자열(무너지지 않음 등)을 회차별로 적는다.
  - 쓰기 반감 = wdrop.py docker_drop.k6_s(DB 컨테이너 BlockIO 쓰기 속도 반감 시작, k6 초) · GB-at-drop. 반감 없음 = '없음'.
  - 선점당 WAL = io_wal_bytes_per_hold · MultiXact = io_mxid_consumed(새 MultiXact ID 수 — datminmxid 불변일 때만, 아니면 누락) · 선점당 MultiXact = 그 ÷ 선점 요청 수 ·
    마감 판정 = 앱 타이머 seat.hold.sales-close.check 평균 ms(k6 구간) · 출처별 대기 = summarize waits_by_src(F = application_name, C = 문장 표지 — 근사).
  - 대조 바퀴 = drift-db.json counter_reconcile_run 중 k6 구간(PC + 시계 차)과 겹친 바퀴(summarize reconcile_summary와 같은 창) — 바퀴 초 = finished − started.
  - CPU = timeline-server.jsonl(docker stats 약 10초 표본) k6 구간 평균·최대 %(100% = 1코어) — 컨테이너 seatlab-app-1 · seatlab-reconciler-1(F) · seatlab-db-1.
  - S3c 마감 뒤 선점·확정 · 마감 전 성공 선점 = summarize close_check와 같은 정의(k6 보낸 시각 t0 + 시계 차 vs starts_at ± (5ms + 시계 오차), 마감 전 창 330초).
"""
import json
import statistics
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import summarize as S  # noqa: E402 — 같은 폴더의 요약기(tag_rows·clock_off·ts·load·PRE_OK_WINDOW_S 재사용)

S4C_DISK = ("control", "control-noclose", "A", "B", "A+C", "A+F")
S4C_TMPFS = ("control", "A", "B", "A+C", "A+F")
COST = ("control", "A", "B", "A+C", "A+F")
EFFECT = ("control", "A", "A+C", "A+F", "B+C")
KINDS = S.INJECT_KINDS
SRC_OF = {"A+C": "reconcile-app", "B+C": "reconcile-app", "A+F": "reconcile-proc"}
WAIT_SRC = {"A+C": "C", "B+C": "C", "A+F": "F"}
CONTAINERS = {"app": "seatlab-app-1", "f": "seatlab-reconciler-1", "db": "seatlab-db-1"}
INTERVAL_S = 60


# ---- 값 모음·서식 ------------------------------------------------------------------------------------
def num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def agg(vals):
    pres = [v for v in vals if num(v)]
    return {"values": vals, "n": len(pres), "missing": len(vals) - len(pres),
            "median": statistics.median(pres) if pres else None, "min": min(pres) if pres else None, "max": max(pres) if pres else None}


def fx(x, d=None):
    if x is None:
        return "누락"
    if d is None:
        d = 0 if float(x).is_integer() or abs(x) >= 1000 else 2
    return f"{x:,.{d}f}"


def txt(a, d=None):
    if not a["values"]:
        return "누락(회차 없음)"
    if not a["n"]:
        return f"누락 {a['missing']}/{len(a['values'])}"
    s = fx(a["median"], d) if a["n"] == 1 else f"{fx(a['median'], d)} [{fx(a['min'], d)}–{fx(a['max'], d)}]"
    return s + (f" · 누락 {a['missing']}/{len(a['values'])}" if a["missing"] else "")


def pct(a, b):
    """중앙값 비율(%) = a ÷ b − 1."""
    ma, mb = a["median"], b["median"]
    return None if ma is None or not mb else (ma / mb - 1) * 100


def ptxt(p):
    return "누락" if p is None else f"{p:+.1f}%"


def dtxt(a, b, unit="s"):
    ma, mb = a["median"], b["median"]
    return "누락" if ma is None or mb is None else f"{ma - mb:+,.0f}{unit}"


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


# ---- 입력 ---------------------------------------------------------------------------------------------
class Camp:
    def __init__(self, root, expect_reps=3):
        self.root = root
        self.expect_reps = expect_reps
        self.conds = [l.split()[0] for l in (root / "conditions.txt").read_text().splitlines() if l.strip()]
        self.summ = {c: S.load(root / c / "summary.json") for c in self.conds}
        self.wall = {(w["cond"], w["rep"]): w for w in (S.load(root / "wall.json") or [])}
        self.wdrop = {w["name"]: w for w in (S.load(root / "wdrop.json") or [])}
        self.notes = []          # 누락·의심(자동 수집)
        self.statuses = {}

    def reps(self, folder, cell):
        s = self.summ.get(folder)
        if s is None:
            self.notes.append(f"{folder}: summary.json 없음(summarize.py 먼저)")
            return []
        all_reps = s.get(f"L4/{cell}") or []
        self.statuses[f"{folder}/{cell}"] = [r.get("status") for r in all_reps]
        bad = [f'{r.get("rep")}:{r.get("status")}' for r in all_reps if r.get("status") != "ok"]
        if bad or len(all_reps) != self.expect_reps:
            self.notes.append(f"{folder}/{cell}: ok 아닌 회차 {bad} · 전체 {len(all_reps)}회")
        for r in all_reps:
            r["_dir"] = self.root / folder / "L4" / cell / r["rep"]
            r["_folder"] = folder
        return [r for r in all_reps if r.get("status") == "ok"]


def get(r, *path):
    x = r
    for k in path:
        x = x.get(k) if isinstance(x, dict) else None
    return x


def src_sessions(r, src):
    """출처별 평균 세션 — 표본이 없으면(waits_by_src None) 누락, 표본은 있는데 그 출처 활성 세션이 없으면 0(summarize waits_by_src는 본 출처만 키로 남긴다)."""
    w = r.get("waits_by_src")
    if not isinstance(w, dict):
        return None
    return (w.get(src) or {}).get("sessions", 0.0)


def holds_of(r):
    h = r.get("hold_requests_per_app") or []
    return sum(h) if h and all(num(x) for x in h) else None


def wall_of(camp, r):
    w = camp.wall.get((r["_folder"], r["rep"]))
    if w is None:
        return None, "누락(wall.json)"
    if w.get("wall_outcome") == "wall":
        return w.get("wall_after_settle_s"), f'{w.get("wall_after_settle_s")}s'
    return None, w.get("wall_outcome") or "누락"


def wdrop_of(camp, r):
    w = camp.wdrop.get(f'{r["_folder"]}__{r["rep"]}')
    if w is None:
        return None, None, "누락(wdrop.json)"
    d = w.get("docker_drop")
    if d is None:
        return None, None, "없음"
    return d.get("k6_s"), d.get("gb_at_drop"), f'{d.get("k6_s")}s({d.get("gb_at_drop")}GB)'


def k6_window_server(r):
    m = r.get("meta") or {}
    if not m.get("k6_started") or not m.get("k6_ended"):
        return None
    off = r.get("clock_offset_s") or 0.0
    return ts(m["k6_started"]) + off, ts(m["k6_ended"]) + off


def reconcile_runs(r, src):
    """k6 구간과 겹친 그 출처 바퀴의 (시작, 끝) 목록 — 파일 없음 = None."""
    db = S.load(r["_dir"] / "drift-db.json")
    w = k6_window_server(r)
    if db is None or w is None:
        return None
    return [(ts(x["started_at"]), ts(x["finished_at"])) for x in db.get("reconcile_runs") or []
            if x.get("source") == src and ts(x["started_at"]) <= w[1] and ts(x["finished_at"]) >= w[0]]


def cpu(r):
    """k6 구간 docker stats CPU% {app,f,db: (평균, 최대, 표본 수)} — 파일 없음 = None."""
    p = r["_dir"] / "timeline-server.jsonl"
    m = r.get("meta") or {}
    if not p.exists() or not m.get("k6_started") or not m.get("k6_ended"):
        return None
    off = r.get("clock_offset_s") or 0.0
    t0, t1 = ts(m["k6_started"]), ts(m["k6_ended"])
    now, vals = None, {k: [] for k in CONTAINERS}
    for line in p.read_text().splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "t" in row:
            now = ts(row["t"]) - off
            continue
        if now is None or not (t0 <= now <= t1):
            continue
        for k, name in CONTAINERS.items():
            if row.get("Name") == name:
                try:
                    vals[k].append(float(str(row.get("CPUPerc", "")).rstrip("%")))
                except ValueError:
                    pass
    return {k: ((statistics.mean(v), max(v), len(v)) if v else None) for k, v in vals.items()}


# ---- 표 도우미 -----------------------------------------------------------------------------------------
def table(out, header, rows):
    out.append("| " + " | ".join(header) + " |")
    out.append("|" + "---|" * len(header))
    for row in rows:
        out.append("| " + " | ".join(str(c) for c in row) + " |")
    out.append("")


def fact(out, s):
    out.append(f"> **반증 관측 사실**: {s}")
    out.append("")


# ---- 비용: S4c --------------------------------------------------------------------------------------------
S4C_METRICS = [
    ("ok_rps", "성공 RPS", lambda r: get(r, "fixed", "ok_rps_total"), 0),
    ("arrive_ratio", "도착/목표", lambda r: get(r, "fixed", "achieved_ratio_total"), 3),
    ("p99", "선점 p99 ms(k6 전체)", lambda r: r.get("hold_p99"), 0),
    ("p50", "선점 p50 ms(k6 전체)", lambda r: r.get("hold_p50"), 0),
    ("worst_p99_nz", "가장 나쁜 구간 p99 ms(코드 0 제외)", lambda r: get(r, "fixed", "worst_p99_nz_ms"), 0),
    ("err", "에러율", lambda r: r.get("hold_error_rate"), 4),
    ("wal_per_hold", "선점당 WAL B", lambda r: r.get("io_wal_bytes_per_hold"), 0),
    ("mxid", "MultiXact ID", lambda r: r.get("io_mxid_consumed"), 0),
    ("mxid_per_hold", "선점당 MultiXact", lambda r: (r["io_mxid_consumed"] / holds_of(r)) if num(r.get("io_mxid_consumed")) and holds_of(r) else None, 3),
    ("close_ms", "마감 판정 평균 ms", lambda r: r.get("sales_close_check_mean_ms"), 4),
    ("ckpt", "체크포인트 수", lambda r: r.get("io_checkpoints"), 0),
]


def s4c_block(camp, out, js, preset, conds):
    rows, data = [], {}
    for c in conds:
        reps = camp.reps(f"{c}-s4c-{preset}", "S4c4200")
        d = {k: agg([fn(r) for r in reps]) for k, _, fn, _ in S4C_METRICS}
        walls = [wall_of(camp, r) for r in reps]
        drops = [wdrop_of(camp, r) for r in reps]
        d["wall"] = agg([w[0] for w in walls])
        d["wall_reps"] = [w[1] for w in walls]
        d["wdrop"] = agg([x[0] for x in drops])
        d["wdrop_gb"] = agg([x[1] for x in drops])
        d["wdrop_reps"] = [x[2] for x in drops]
        names = sorted({k for r in reps for k in (r.get("waits_by_src") or {})})
        d["waits_by_src"] = {k: agg([src_sessions(r, k) for r in reps]) for k in names}
        d["n"] = len(reps)
        if c == "control-noclose" and all(r.get("sales_close_check_n") == 0 for r in reps):
            camp.notes.append(f"{c}-s4c-{preset}: 마감 판정 0건(sales-close 끔 — 마감 판정 ms 누락은 기대된 빈 값)")
        for key, lbl in (("ok_rps", "성공 RPS"), ("p99", "선점 p99"), ("wall", "벽 시작"), ("wdrop", "쓰기 반감")):
            a = d[key]
            if a["n"] >= 2 and a["median"]:
                far = [(r["rep"], v) for r, v in zip(reps, a["values"]) if num(v) and abs(v / a["median"] - 1) > 0.25]
                if far:
                    camp.notes.append(f"{c}-s4c-{preset} {lbl}: 중앙값 {fx(a['median'])}에서 25% 넘게 벗어난 회차 " + ", ".join(f"{k}={fx(v)}" for k, v in far))
        data[c] = d
        rows.append([c, d["n"]] + [txt(d[k], dd) for k, _, _, dd in S4C_METRICS]
                    + [txt(d["wall"]) + " · " + ", ".join(d["wall_reps"]), ", ".join(d["wdrop_reps"]),
                       "; ".join(f"{k} {txt(v, 2)}" for k, v in d["waits_by_src"].items()) or "누락"])
    out.append(f"### S4c4200 {'디스크' if preset == 'disk' else 'tmpfs'} — 조건별(회차 중앙값 [최소–최대])\n")
    table(out, ["조건", "n"] + [lbl for _, lbl, _, _ in S4C_METRICS] + ["벽 시작 s(과도 뒤 · 회차별)", "쓰기 반감 k6 s(GB)", "출처별 대기(평균 세션)"], rows)
    js[f"s4c_{preset}"] = data
    return data


def ratio_rows(data, pairs):
    rows, res = [], {}
    for c, b in pairs:
        if c not in data or b not in data:
            continue
        x, y = data[c], data[b]
        r = {k: pct(x[k], y[k]) for k in ("ok_rps", "p99", "p50", "worst_p99_nz", "wal_per_hold", "mxid", "close_ms")}
        r.update(wall_diff=dtxt(x["wall"], y["wall"]), wdrop_diff=dtxt(x["wdrop"], y["wdrop"]))
        res[f"{c}/{b}"] = r
        rows.append([f"{c} ÷ {b}", ptxt(r["ok_rps"]), ptxt(r["p99"]), ptxt(r["p50"]), ptxt(r["worst_p99_nz"]), ptxt(r["wal_per_hold"]),
                     ptxt(r["mxid"]), ptxt(r["close_ms"]), r["wall_diff"], r["wdrop_diff"]])
    return rows, res


RATIO_HDR = ["비교(조건 ÷ 기준)", "성공 RPS", "p99", "p50", "가장 나쁜 구간 p99", "선점당 WAL", "MultiXact ID", "마감 판정 ms", "벽 시작 차(중앙값)", "쓰기 반감 차(중앙값)"]


def cmp_fact(data, c, b, extra=""):
    if c not in data or b not in data:
        return f"{c} vs {b}: 누락"
    x, y = data[c], data[b]
    return (f"{c} vs {b}: 성공 RPS {fx(x['ok_rps']['median'])} vs {fx(y['ok_rps']['median'])} ({ptxt(pct(x['ok_rps'], y['ok_rps']))}) · "
            f"p99 {fx(x['p99']['median'])} vs {fx(y['p99']['median'])} ms ({ptxt(pct(x['p99'], y['p99']))}) · "
            f"벽 시작 {txt(x['wall'])} vs {txt(y['wall'])} s · 반감 {txt(x['wdrop'])} vs {txt(y['wdrop'])} s{extra}")


# ---- 비용: S2 · S3-a20 -------------------------------------------------------------------------------------
def short_cost(camp, out, js, conds=COST):
    s2, s3 = {}, {}
    for c in conds:
        reps = camp.reps(f"{c}-s2", "S2")
        s2[c] = {"n": len(reps), "p99": agg([r.get("hold_p99") for r in reps]), "p50": agg([r.get("hold_p50") for r in reps]),
                 "borrow": agg([r.get("borrow_per_request") for r in reps]), "wal_per_hold": agg([r.get("io_wal_bytes_per_hold") for r in reps]),
                 "recon_runs": agg([sum(v.get("runs", 0) for v in (r.get("reconcile_k6") or {}).values()) if r.get("reconcile_k6") is not None else None for r in reps])}
        reps = camp.reps(f"{c}-s3a20", "S3-a20")
        s3[c] = {"n": len(reps), "wal_per_hold": agg([r.get("io_wal_bytes_per_hold") for r in reps]), "p99": agg([r.get("hold_p99") for r in reps]),
                 "p50": agg([r.get("hold_p50") for r in reps]), "borrow": agg([r.get("borrow_per_request") for r in reps]),
                 "recon_borrows_app": agg([r.get("reconcile_borrows_app") for r in reps]), "recon_borrows_f": agg([r.get("reconcile_borrows_f") for r in reps]),
                 "mxid": agg([r.get("io_mxid_consumed") for r in reps]), "close_ms": agg([r.get("sales_close_check_mean_ms") for r in reps])}
    out.append("### S2(사용자 100명 × 좌석 10개 동시) — 선점 p99\n")
    table(out, ["조건", "n", "선점 p99 ms", "선점 p50 ms", "요청당 빌림", "선점당 WAL B", "k6 구간 대조 바퀴 수", "p99 ÷ control", "p99 ÷ A"],
          [[c, d["n"], txt(d["p99"]), txt(d["p50"]), txt(d["borrow"], 4), txt(d["wal_per_hold"]), txt(d["recon_runs"]),
            ptxt(pct(d["p99"], s2["control"]["p99"])), ptxt(pct(d["p99"], s2["A"]["p99"]))] for c, d in s2.items()])
    fact(out, "S2 p99 중앙값 — " + " · ".join(f"{c} {fx(d['p99']['median'])} ms" for c, d in s2.items())
         + (f" · B vs control {ptxt(pct(s2['B']['p99'], s2['control']['p99']))}" if "B" in s2 else "") + f" · A+C vs A {ptxt(pct(s2['A+C']['p99'], s2['A']['p99']))} · A+F vs A {ptxt(pct(s2['A+F']['p99'], s2['A']['p99']))}"
         + " (ADR §6 ⑤: S2 k6 구간은 몇 초라 C·F 바퀴와 거의 겹치지 않는다 — 대조 바퀴 수 열)")
    out.append("### S3-a20(입장→선점→확정, 이탈 20%) — 선점당 WAL · p99 · 빌림\n")
    table(out, ["조건", "n", "선점당 WAL B", "선점 p99 ms", "선점 p50 ms", "요청당 빌림(대조·정리 뺌)", "앱 대조 빌림(Δ)", "F 빌림(Δ)", "MultiXact ID", "마감 판정 ms",
                "WAL ÷ control", "WAL ÷ A", "p99 ÷ control"],
          [[c, d["n"], txt(d["wal_per_hold"]), txt(d["p99"]), txt(d["p50"]), txt(d["borrow"], 4), txt(d["recon_borrows_app"]), txt(d["recon_borrows_f"]),
            txt(d["mxid"]), txt(d["close_ms"], 4), ptxt(pct(d["wal_per_hold"], s3["control"]["wal_per_hold"])), ptxt(pct(d["wal_per_hold"], s3["A"]["wal_per_hold"])),
            ptxt(pct(d["p99"], s3["control"]["p99"]))] for c, d in s3.items()])
    if "B" in s3:
      fact(out, f"B vs control 선점당 WAL {fx(s3['B']['wal_per_hold']['median'])} vs {fx(s3['control']['wal_per_hold']['median'])} B "
              f"({ptxt(pct(s3['B']['wal_per_hold'], s3['control']['wal_per_hold']))} — 설계 근사 +17%) · p99 {fx(s3['B']['p99']['median'])} vs {fx(s3['control']['p99']['median'])} ms "
              f"({ptxt(pct(s3['B']['p99'], s3['control']['p99']))}) · A+C vs A WAL {ptxt(pct(s3['A+C']['wal_per_hold'], s3['A']['wal_per_hold']))} · A+F vs A WAL {ptxt(pct(s3['A+F']['wal_per_hold'], s3['A']['wal_per_hold']))}")
    else:
      fact(out, f"A+C vs A 선점당 WAL {ptxt(pct(s3['A+C']['wal_per_hold'], s3['A']['wal_per_hold']))} · A+F vs A WAL {ptxt(pct(s3['A+F']['wal_per_hold'], s3['A']['wal_per_hold']))} · "
                f"A vs control WAL {ptxt(pct(s3['A']['wal_per_hold'], s3['control']['wal_per_hold']))} · p99 A+C vs A {ptxt(pct(s3['A+C']['p99'], s3['A']['p99']))}")
    js["s2"], js["s3a20"] = s2, s3


# ---- 효과: S3e ---------------------------------------------------------------------------------------------
def effect_block(camp, out, js):
    data = {}
    for c in EFFECT:
        reps = camp.reps(f"{c}-s3e", "S3e-a20")
        effs = []
        for r in reps:
            e = r.get("effect")
            if not isinstance(e, dict):
                camp.notes.append(f"{c}-s3e {r['rep']}: effect 없음")
            effs.append(e or {})
        d = {"n": len(reps), "kinds": {}}
        for k in KINDS:
            kk = [get(e, "kinds", k) or {} for e in effs]
            srcs = {}
            for i, e in enumerate(effs):
                for p in e.get("pairs") or []:
                    if p.get("kind") == k and p.get("repaired_by"):
                        srcs.setdefault(p["repaired_by"], [0] * len(effs))[i] += 1
            d["kinds"][k] = {"injected": agg([x.get("injected") for x in kk]), "repaired": agg([x.get("repaired") for x in kk]),
                             "end_matched": agg([x.get("end_matched") for x in kk]), "detected": agg([x.get("detected") for x in kk]),
                             "injected_sum": sum(x.get("injected") or 0 for x in kk), "repaired_sum": sum(x.get("repaired") or 0 for x in kk),
                             "end_matched_sum": sum(x.get("end_matched") or 0 for x in kk),
                             "repair_p50": agg([get(x, "repair_s", "p50") for x in kk]), "repair_max": agg([get(x, "repair_s", "max") for x in kk]),
                             "detect_p50": agg([get(x, "detect_s", "p50") for x in kk]), "detect_max": agg([get(x, "detect_s", "max") for x in kk]),
                             "sources": {s: agg(v) for s, v in sorted(srcs.items())}}
        for key in ("false_repairs", "false_positives", "skipped_failure", "skipped_closed", "tick_ok", "tick_failed", "stale_held_seats_end",
                    "noninjected_mismatch", "injected_mismatch_end", "mismatch_open_end", "end_missing"):
            d[key] = agg([e.get(key) for e in effs])
        d["skipped_by_reason"] = [e.get("skipped_failure_by_reason") for e in effs]
        d["tick_causes"] = [e.get("tick_failure_causes") for e in effs]
        d["tick_explained"] = [e.get("tick_explained") for e in effs]
        d["unexpected"] = [e.get("unexpected") for e in effs]
        d["recon_skipped_closed"] = agg([get(e, "reconcile", SRC_OF[c], "skipped_closed") for e in effs]) if c in SRC_OF else None
        for e, r in zip(effs, reps):   # 틱 실패 원인 수(app.log 전체) ≠ 틱 실패 지표(k6 직전 → 회차 끝)면 기록
            causes = e.get("tick_failure_causes")
            if isinstance(causes, dict) and num(e.get("tick_failed")) and sum(causes.values()) != e["tick_failed"]:
                camp.notes.append(f"{c}-s3e {r['rep']}: 틱 실패 지표 {e['tick_failed']} ≠ app.log 예외 블록 {sum(causes.values())} {causes} "
                                  "(원인 분류는 로그 전체 · 지표는 k6 직전→회차 끝 — 창 차이)")
        data[c] = d
    out.append("### S3e 주입·복구 — 종류별(값 = 회차 중앙값 [최소–최대] · 합계 = 3회 합)\n")
    out.append("> 종류: D 쿼터 행 삭제 · L1 cnt−1(만료 감소 음수 경로) · L2 cnt−1(확정 쌍 — 조용함) · H cnt+1(조용함). control은 둘째 라운드에서 D·L1을 뺀다(ADR §4).\n")
    rows = []
    for c, d in data.items():
        for k in KINDS:
            x = d["kinds"][k]
            rows.append([c, k, txt(x["injected"]), txt(x["repaired"]), txt(x["end_matched"]),
                         f'{x["repaired_sum"]}/{x["injected_sum"]} · 끝 일치 {x["end_matched_sum"]}', txt(x["detect_p50"], 1), txt(x["detect_max"], 1),
                         txt(x["repair_p50"], 1), txt(x["repair_max"], 1), "; ".join(f"{s} {txt(v)}" for s, v in x["sources"].items()) or "-"])
    table(out, ["조건", "종류", "주입", "복구", "끝 cnt=실제", "복구/주입 합계", "탐지 s p50", "탐지 s max", "복구 s p50", "복구 s max", "복구 출처(쌍 수)"], rows)
    lh = lambda c, k, f: data[c]["kinds"][k][f]["max"]
    fact(out, f"control D·L1 복구 {data['control']['kinds']['D']['repaired_sum'] + data['control']['kinds']['L1']['repaired_sum']}/"
              f"{data['control']['kinds']['D']['injected_sum'] + data['control']['kinds']['L1']['injected_sum']} · "
              f"A D·L1 복구 {data['A']['kinds']['D']['repaired_sum'] + data['A']['kinds']['L1']['repaired_sum']}/{data['A']['kinds']['D']['injected_sum'] + data['A']['kinds']['L1']['injected_sum']}"
              f"(끝 일치 {data['A']['kinds']['D']['end_matched_sum'] + data['A']['kinds']['L1']['end_matched_sum']}) · A L2·H 복구 "
              f"{data['A']['kinds']['L2']['repaired_sum'] + data['A']['kinds']['H']['repaired_sum']}/{data['A']['kinds']['L2']['injected_sum'] + data['A']['kinds']['H']['injected_sum']} · "
              + " · ".join(f"{c} 전 종류 복구 {sum(data[c]['kinds'][k]['repaired_sum'] for k in KINDS)}/{sum(data[c]['kinds'][k]['injected_sum'] for k in KINDS)}"
                           f"(끝 일치 {sum(data[c]['kinds'][k]['end_matched_sum'] for k in KINDS)}) · L2·H 탐지 s 최대 {fx(max(x for x in (lh(c, 'L2', 'detect_max'), lh(c, 'H', 'detect_max')) if x is not None), 1) if any(lh(c, k, 'detect_max') is not None for k in ('L2', 'H')) else '누락'} (2주기 = {2 * INTERVAL_S}s)"
                           for c in ("A+C", "A+F", "B+C")))
    out.append("### S3e 정지·오탐·건너뜀·끝 상태\n")
    rows = []
    for c, d in data.items():
        rows.append([c, d["n"], txt(d["tick_ok"]), txt(d["tick_failed"]), "; ".join(json.dumps(x, ensure_ascii=False) for x in d["tick_causes"]),
                     txt(d["stale_held_seats_end"]), txt(d["false_repairs"]), txt(d["false_positives"]),
                     txt(d["skipped_failure"]) + " · " + "; ".join(json.dumps(x, ensure_ascii=False) for x in d["skipped_by_reason"]),
                     txt(d["skipped_closed"]) + (f" · 바퀴 기록 {txt(d['recon_skipped_closed'])}" if d["recon_skipped_closed"] else ""),
                     txt(d["noninjected_mismatch"]), txt(d["injected_mismatch_end"]), txt(d["end_missing"]),
                     "; ".join(str(len(u)) if isinstance(u, list) else "누락" for u in d["unexpected"])])
    table(out, ["조건", "n", "틱 성공", "틱 실패", "틱 실패 원인(app.log, 회차별)", "끝 오래된 HELD", "오탐 복구", "false-positive 기록", "skipped 실패 · 이유별(회차별)",
                "skipped 마감 제외", "주입 밖 끝 어긋남", "주입 쌍 끝 어긋남", "끝 상태 빠진 주입 쌍", "기대와 다름 수(회차별)"], rows)
    fact(out, " · ".join(f"{c} 틱 실패 {txt(d['tick_failed'])} · 끝 오래된 HELD {txt(d['stale_held_seats_end'])} · 오탐 복구 {txt(d['false_repairs'])} · 주입 밖 끝 어긋남 {txt(d['noninjected_mismatch'])}"
                         for c, d in data.items())
         + f" · B+C 틱 실패 설명(일대일) {data['B+C']['tick_explained']}")
    js["s3e"] = data


# ---- 대조 비용(H4·H5) -----------------------------------------------------------------------------------------
def reconcile_block(camp, out, js, s4c, skip=()):
    plan = [("S4c 디스크", "s4c-disk", "S4c4200", ("A+C", "A+F")), ("S4c tmpfs", "s4c-tmpfs", "S4c4200", ("A+C", "A+F")),
            ("S3-a20", "s3a20", "S3-a20", ("A+C", "A+F")), ("S3e", "s3e", "S3e-a20", ("A+C", "A+F", "B+C")), ("S2", "s2", "S2", ("A+C", "A+F"))]
    data, rows, wrows, crows = {}, [], [], []
    plan = [x for x in plan if x[1] not in skip]   # cost-remeasure에는 효과 셀(s3e)이 없다
    for label, suf, cell, conds in plan:
        for c in conds:
            reps = camp.reps(f"{c}-{suf}", cell)
            src = SRC_OF[c]
            # reconcile_k6 = None(구간 없음) → 누락 · 그 출처 키 없음 = k6 구간에 겹친 바퀴 0(바퀴 수 0, 시간 열은 '바퀴 없음'으로 누락 표시)
            rk = [None if r.get("reconcile_k6") is None else (get(r, "reconcile_k6", src) or {"runs": 0}) for r in reps]
            for r, x in zip(reps, rk):
                if x == {"runs": 0}:
                    camp.notes.append(f"{c}-{suf} {r['rep']}: k6 구간과 겹친 {src} 바퀴 0 — 바퀴 시간·청크·빌림 열은 누락(바퀴 없음)")
            runs = [reconcile_runs(r, src) for r in reps]
            d = {"n": len(reps), "runs": agg([x.get("runs") if x else None for x in rk]),
                 "pass_p50": agg([get(x, "pass_s", "p50") for x in rk]), "pass_max": agg([get(x, "pass_s", "max") for x in rk]),
                 "runs_ge_interval": agg([None if x is None else sum(1 for a, b in x if b - a >= INTERVAL_S) for x in runs]),
                 "chunk_ms_max": agg([x.get("chunk_ms_max") if x else None for x in rk]), "borrows": agg([x.get("borrows") if x else None for x in rk]),
                 "errors": agg([x.get("errors") if x else None for x in rk]), "pairs_scanned_max": agg([x.get("pairs_scanned_max") if x else None for x in rk]),
                 "f_borrows_metric": agg([r.get("reconcile_borrows_f") for r in reps]) if c == "A+F" else None,
                 "app_borrows_metric": agg([r.get("reconcile_borrows_app") for r in reps]) if c != "A+F" else None}
            ws = WAIT_SRC[c]
            d["wait_sessions"] = agg([src_sessions(r, ws) for r in reps])
            d["wait_top"] = [get(r, "waits_by_src", ws, "top") for r in reps]
            d["wait_app_sessions"] = agg([src_sessions(r, "app") for r in reps])
            if label.startswith("S4c") or label == "S3-a20":
                cp = [cpu(r) for r in reps]
                for k in CONTAINERS:
                    d[f"cpu_{k}_mean"] = agg([(x[k] or (None,))[0] if x else None for x in cp])
                    d[f"cpu_{k}_max"] = agg([x[k][1] if x and x[k] else None for x in cp])
            data[f"{label}/{c}"] = d
            rows.append([label, c, d["n"], txt(d["runs"]), txt(d["pass_p50"], 2), txt(d["pass_max"], 2), txt(d["runs_ge_interval"]), txt(d["chunk_ms_max"]),
                         txt(d["borrows"]), txt(d["errors"]), txt(d["pairs_scanned_max"])])
            wrows.append([label, c, ws, txt(d["wait_sessions"], 3), txt(d["wait_app_sessions"], 3),
                          "; ".join(("누락" if r.get("waits_by_src") is None else "활성 없음(표본 있음)") if t is None else ", ".join(f"{k} {v:.3f}" for k, v in t)
                                    for r, t in zip(reps, d["wait_top"]))])
        if label.startswith("S4c") or label == "S3-a20":
            base_conds = ("control", "A") + conds
            for c in base_conds:
                key = f"{label}/{c}"
                if key not in data:
                    reps = camp.reps(f"{c}-{suf}", cell)
                    cp = [cpu(r) for r in reps]
                    data[key] = {"n": len(reps)}
                    for k in CONTAINERS:
                        data[key][f"cpu_{k}_mean"] = agg([(x[k] or (None,))[0] if x else None for x in cp])
                        data[key][f"cpu_{k}_max"] = agg([x[k][1] if x and x[k] else None for x in cp])
                d = data[key]
                crows.append([label, c, d["n"], txt(d["cpu_app_mean"], 0), txt(d["cpu_app_max"], 0), txt(d["cpu_f_mean"], 1), txt(d["cpu_f_max"], 1),
                              txt(d["cpu_db_mean"], 0), txt(d["cpu_db_max"], 0)])
    out.append("### 대조 바퀴(counter_reconcile_run — k6 구간과 겹친 바퀴)\n")
    table(out, ["셀", "조건", "n", "바퀴 수", "바퀴 s p50(회차 p50)", "바퀴 s max(회차 max)", f"바퀴 ≥ {INTERVAL_S}s 수", "청크 최대 ms", "빌림(바퀴 기록)", "오류", "훑은 쌍 최대"], rows)
    g = lambda k, f: data.get(k, {}).get(f) or agg([])
    fact(out, " · ".join(f"{lbl} {c}: 바퀴 s max {txt(g(f'{lbl}/{c}', 'pass_max'), 1)} · p50 {txt(g(f'{lbl}/{c}', 'pass_p50'), 1)} · ≥{INTERVAL_S}s 바퀴 {txt(g(f'{lbl}/{c}', 'runs_ge_interval'))}"
                         for lbl in ("S4c 디스크", "S4c tmpfs") for c in ("A+C", "A+F"))
         + f" (주기 {INTERVAL_S}s)")
    out.append("### DB 쪽 대기 이벤트 — 대조 출처(F = application_name 정확 · C = 문장 표지 근사, ADR §6 ④)\n")
    table(out, ["셀", "조건", "출처", "출처 평균 세션", "app 평균 세션", "출처 상위 3 대기 이벤트(회차별)"], wrows)
    fact(out, " · ".join(f"{lbl}: C {txt(g(f'{lbl}/A+C', 'wait_sessions'), 3)} vs F {txt(g(f'{lbl}/A+F', 'wait_sessions'), 3)} 세션"
                         for lbl in ("S4c 디스크", "S4c tmpfs", "S3-a20", "S3e")))
    out.append("### CPU(docker stats k6 구간 — 100% = 1코어, 앱·DB CPU 4개 · F CPU 1개)\n")
    table(out, ["셀", "조건", "n", "앱 평균 %", "앱 최대 %", "F 평균 %", "F 최대 %", "DB 평균 %", "DB 최대 %"], crows)
    fact(out, " · ".join(f"{lbl}: 앱 평균 A {txt(g(f'{lbl}/A', 'cpu_app_mean'), 0)} · A+C {txt(g(f'{lbl}/A+C', 'cpu_app_mean'), 0)} · A+F {txt(g(f'{lbl}/A+F', 'cpu_app_mean'), 0)} % · "
                         f"F 컨테이너 평균 {txt(g(f'{lbl}/A+F', 'cpu_f_mean'), 1)} %" for lbl in ("S4c 디스크", "S4c tmpfs", "S3-a20"))
         + " · 처리량·p99(H4 표): " + " · ".join(f"{p}: A+C {ptxt(pct(s4c[p]['A+C']['ok_rps'], s4c[p]['A']['ok_rps']))}/{ptxt(pct(s4c[p]['A+C']['p99'], s4c[p]['A']['p99']))} vs "
                                                f"A+F {ptxt(pct(s4c[p]['A+F']['ok_rps'], s4c[p]['A']['ok_rps']))}/{ptxt(pct(s4c[p]['A+F']['p99'], s4c[p]['A']['p99']))}(RPS/p99 ÷ A)"
                                                for p in ("disk", "tmpfs")))
    js["reconcile"] = data


# ---- 정리(H8): S3c ---------------------------------------------------------------------------------------------
def close_rep(r):
    rd = r["_dir"]
    cj, pre, ce, db = (S.load(rd / f) for f in ("close.json", "pre-close.json", "close-end.json", "drift-db.json"))
    off, err = S.clock_off(rd)
    res = {}
    if not isinstance(cj, dict) or not num(cj.get("starts_at_epoch")) or off is None:
        return {"error": "close.json·시계 차 없음"}
    sa, margin = cj["starts_at_epoch"], 0.005 + (err or 0)
    holds = S.tag_rows(rd / "k6-requests.csv.gz", "hold_code")
    confs = S.tag_rows(rd / "k6-requests.csv.gz", "confirm_code")
    after = lambda x: "t0" in x and int(x["t0"]) / 1000 + off > sa + margin
    if holds is not None:
        post = [x for x in holds if after(x)]
        res.update(post_holds=len(post), post_closed=sum(1 for x in post if x.get("code") == "SCHEDULE_CLOSED"),
                   post_ok=sum(1 for x in post if x.get("code") == "OK"),
                   post_other=sum(1 for x in post if x.get("code") not in ("OK", "SCHEDULE_CLOSED")),
                   pre_ok=sum(1 for x in holds if "t0" in x and x.get("code") == "OK" and sa - S.PRE_OK_WINDOW_S <= int(x["t0"]) / 1000 + off < sa - margin))
    if confs is not None:
        cp = [x for x in confs if after(x)]
        res.update(post_confirms=len(cp), post_confirm_ok=sum(1 for x in cp if x.get("code") == "OK"),
                   post_confirm_expired=sum(1 for x in cp if x.get("code") == "HOLD_EXPIRED"),
                   post_confirm_other=sum(1 for x in cp if x.get("code") not in ("OK", "HOLD_EXPIRED")))
    if isinstance(pre, dict):
        res.update(pre_mismatch=pre.get("mismatches"), pre_pairs=pre.get("pairs"),
                   pre_lead_s=(sa - pre["t_epoch"]) if num(pre.get("t_epoch")) else None)
    runs = (db or {}).get("cleanup_runs")
    if isinstance(runs, list):
        res.update(cleanup_runs=len(runs), rows_deleted=sum(x.get("rows_deleted") or 0 for x in runs),
                   holds_seen_max=max((x.get("holds_seen") or 0 for x in runs), default=None),
                   early_deletes=sum(1 for x in runs if (x.get("holds_seen") or 0) > 0 and (x.get("rows_deleted") or 0) > 0),
                   runs_holds_seen=sum(1 for x in runs if (x.get("holds_seen") or 0) > 0),
                   waited_ms_max=max((x.get("waited_ms") or 0 for x in runs), default=None), stmt_ms_max=max((x.get("stmt_ms") or 0 for x in runs), default=None))
    if isinstance(ce, dict):
        res.update({k: ce.get(k) for k in ("holds", "quota_rows", "closed_quota_rows", "closed_quota_rows_no_holds", "zero_rows_actual0")})
    res["confirmed_end"] = get(r, "consistency", "confirmed")
    res["tick_failed"] = r.get("tick_failed")
    return res


CLOSE_COLS = [("post_holds", "마감 뒤 선점"), ("post_closed", "그중 SCHEDULE_CLOSED"), ("post_ok", "그중 201"), ("post_other", "그중 그 밖 코드"),
              ("pre_ok", f"마감 전 {S.PRE_OK_WINDOW_S}s 성공 선점"), ("post_confirms", "마감 뒤 확정"), ("post_confirm_ok", "그중 OK"),
              ("post_confirm_expired", "그중 HOLD_EXPIRED"), ("post_confirm_other", "그중 그 밖"), ("pre_mismatch", "마감 전 표본 어긋남"), ("pre_pairs", "표본 쌍"),
              ("pre_lead_s", "표본 시각(마감 − s)"), ("cleanup_runs", "정리 기록 수"), ("rows_deleted", "지운 행 합"), ("holds_seen_max", "holds_seen 최대"),
              ("runs_holds_seen", "holds_seen>0 기록"), ("early_deletes", "holds_seen>0 & 지움"), ("waited_ms_max", "waited_ms 최대"), ("stmt_ms_max", "stmt_ms 최대"),
              ("holds", "끝 홀드"), ("quota_rows", "끝 쿼터 행"), ("closed_quota_rows_no_holds", "끝 마감 회차 쿼터 행(홀드 0)"), ("zero_rows_actual0", "끝 cnt0·실제0 행"),
              ("confirmed_end", "끝 CONFIRMED(판정기)"), ("tick_failed", "틱 실패")]


def close_block(camp, out, js):
    data, rows = {}, []
    for mode, folder in (("schedule(①)", "close-schedule-s3c"), ("zero(②)", "close-zero-s3c")):
        reps = camp.reps(folder, "S3c-a20")
        per = [close_rep(r) for r in reps]
        for r, p in zip(reps, per):
            if "error" in p:
                camp.notes.append(f"{folder} {r['rep']}: {p['error']}")
        d = {k: agg([p.get(k) for p in per]) for k, _ in CLOSE_COLS}
        d["n"], d["per_rep"] = len(reps), per
        data[mode] = d
        rows.append([mode, d["n"]] + [txt(d[k], 1 if k == "pre_lead_s" else None) for k, _ in CLOSE_COLS])
    out.append("### S3c 마감 임박(시드 회차 starts_at = k6 시작 + 약 600초) — 정리 방식별\n")
    out.append("> '확정 쌍 행 잔존'을 직접 세는 필드는 없다(close-end.json) — 끝 쿼터 행 수와 끝 CONFIRMED 수를 나란히 둔다(ADR §6 ⑩: 마감 뒤 판정기로는 반증 불가).\n")
    table(out, ["정리", "n"] + [lbl for _, lbl in CLOSE_COLS], rows)
    sc, zr = data["schedule(①)"], data["zero(②)"]
    fact(out, f"①: 마감 뒤 선점 {txt(sc['post_holds'])} 중 SCHEDULE_CLOSED {txt(sc['post_closed'])} · 마감 뒤 확정 OK {txt(sc['post_confirm_ok'])}/{txt(sc['post_confirms'])} · "
              f"holds_seen>0인 기록에서 지움 {txt(sc['early_deletes'])} · 지운 행 {txt(sc['rows_deleted'])} · 끝 홀드 {txt(sc['holds'])} · 끝 마감 회차 쿼터 행(홀드 0) {txt(sc['closed_quota_rows_no_holds'])} · "
              f"마감 전 표본 어긋남 {txt(sc['pre_mismatch'])} / ②: 마감 뒤 선점 {txt(zr['post_holds'])} 중 SCHEDULE_CLOSED {txt(zr['post_closed'])} · "
              f"마감 뒤 확정 OK {txt(zr['post_confirm_ok'])}/{txt(zr['post_confirms'])} · 끝 cnt0·실제0 행 {txt(zr['zero_rows_actual0'])} · 지운 행 {txt(zr['rows_deleted'])} · "
              f"끝 쿼터 행 {txt(zr['quota_rows'])} vs 끝 CONFIRMED {txt(zr['confirmed_end'])} · 마감 전 표본 어긋남 {txt(zr['pre_mismatch'])}")
    js["s3c"] = data


# ---- main ---------------------------------------------------------------------------------------------------
# ---- 명세 §13 cost-remeasure(리뷰 R8) -------------------------------------------------------------------------------
RM_CONDS = ("control", "A", "A+C", "A+F")
RM_CELLS = (("s4c-disk", "S4c4200"), ("s4c-tmpfs", "S4c4200"), ("s2", "S2"), ("s3a20", "S3-a20"))
S4C_RM_EXTRA = [("p99_steady", "선점 p99 ms 램프 밖(60~420초, 코드 0 제외)", lambda r: get(r, "fixed", "p99_steady_nz_ms"), 0),
                ("p99_ramp", "선점 p99 ms 램프(0~60초, 코드 0 제외)", lambda r: get(r, "fixed", "p99_ramp_nz_ms"), 0)]


def start_block(camp, out, js):
    """시작 상태(시드 끝 → 첫 요청 · DB 기동 → k6) 퍼짐 · 대조 바퀴 위상(k6 전 완료 수 · k6 구간 첫 바퀴 시작) · k6 구간 GC(정지 수·합·최대 — safepoint 기준) — 회차별 + 조건·셀별 중앙값."""
    rows, data = [], {}
    for folder_sfx, cell in RM_CELLS:
        for c in RM_CONDS:
            reps = camp.reps(f"{c}-{folder_sfx}", cell)
            d = {"seed_to_first": agg([get(r, "pre_k6", "seed_to_first_request_s") for r in reps]),
                 "db_start_to_k6": agg([get(r, "pre_k6", "db_start_to_k6_s") for r in reps]),
                 "before_k6": agg([get(r, "pre_k6", "reconcile_phase", "completed_before_k6") for r in reps]),
                 "first_in_k6": agg([get(r, "pre_k6", "reconcile_phase", "first_in_k6", "start_rel_s") for r in reps]),
                 "gc_stops": agg([get(r, "gc", "app", "k6", "stops") for r in reps]),
                 "gc_total": agg([get(r, "gc", "app", "k6", "stop_ms_total") for r in reps]),
                 "gc_max": agg([get(r, "gc", "app", "k6", "stop_ms_max") for r in reps]),
                 "gc_nested": agg([get(r, "gc", "app", "k6", "nested") for r in reps]),
                 "f_gc_max": agg([get(r, "gc", "reconciler", "k6", "stop_ms_max") for r in reps]),
                 "autovac_wait": agg([sum(t.get("autovacuum_count", 0) for t in (get(r, "pre_k6", "db_during_wait", "tables") or {}).values())
                                      if get(r, "pre_k6", "db_during_wait", "tables") is not None else None for r in reps]),
                 "n": len(reps)}
            data[f"{c}/{folder_sfx}"] = d
            rows.append([folder_sfx, c, d["n"], txt(d["seed_to_first"], 2), txt(d["db_start_to_k6"], 1), txt(d["autovac_wait"]), txt(d["before_k6"]),
                         txt(d["first_in_k6"], 1), txt(d["gc_stops"]), txt(d["gc_total"], 0), txt(d["gc_max"], 1), txt(d["gc_nested"]), txt(d["f_gc_max"], 1)])
    out.append("### 시작 상태 · 대조 바퀴 위상 · k6 구간 GC(조건·셀별 중앙값 [최소–최대])\n")
    table(out, ["셀", "조건", "n", "시드 끝 → 첫 요청 s", "DB 기동 → k6 s", "대기 중 autovacuum 수", "k6 전 완료 바퀴", "k6 구간 첫 바퀴 시작 s",
                "GC 정지 수(k6)", "GC 정지 합 ms", "GC 정지 최대 ms", "Young+Full 묶인 정지", "F GC 최대 ms"], rows)
    allv = [v for d in data.values() for v in d["seed_to_first"]["values"] if num(v)]
    if allv:
        fact(out, f"시드 끝 → 첫 요청 전체 퍼짐 {min(allv):.2f}~{max(allv):.2f}초(최대−최소 {max(allv) - min(allv):.2f}초) — 판정 허용 ±5초(pre-k6-mismatch). "
                  "시드 끝 → k6와 첫 대조 바퀴 → k6는 함께 맞출 수 없다(대조 바퀴 위상은 기록만 — README '시작 상태')")
    js["start"] = data


def remeasure_main(root, camp, out, js):
    out.append("## 1. 비용 재측정 — S4c4200 · S2 · S3-a20 (명세 §13 · H4 · H5)\n")
    saved = list(S4C_METRICS)
    S4C_METRICS.extend(S4C_RM_EXTRA)
    try:
        disk = s4c_block(camp, out, js, "disk", RM_CONDS)
        rr, rd = ratio_rows(disk, [("A+C", "A"), ("A+F", "A"), ("A", "control")])
        out.append("#### 디스크 — 기준 대비(중앙값 비율)\n")
        table(out, RATIO_HDR, rr)
        fact(out, "H4 " + cmp_fact(disk, "A+C", "A") + " / " + cmp_fact(disk, "A+F", "A"))
        tmp = s4c_block(camp, out, js, "tmpfs", RM_CONDS)
        rr2, rd2 = ratio_rows(tmp, [("A+C", "A"), ("A+F", "A"), ("A", "control")])
        out.append("#### tmpfs — 기준 대비(중앙값 비율)\n")
        table(out, RATIO_HDR, rr2)
        fact(out, "H4 " + cmp_fact(tmp, "A+C", "A") + " / " + cmp_fact(tmp, "A+F", "A"))
    finally:
        S4C_METRICS[:] = saved
    js["ratios"] = {"disk": rd, "tmpfs": rd2}
    short_cost(camp, out, js, RM_CONDS)
    out.append("## 2. 시작 상태 · 대조 위상 · GC (리뷰 R8)\n")
    start_block(camp, out, js)
    out.append("## 3. 대조 비용 — C vs F (H4 · H5)\n")
    reconcile_block(camp, out, js, {"disk": disk, "tmpfs": tmp}, skip=("s3e",))


JVM_CONDS = ("1g-auto", "1g-g1", "1792m-auto", "2g-auto")   # campaign.sh jvm-baseline — 폴더 <조건>-jvm
JVM2_CONDS = ("1g-g1", "2g-g1", "4g-g1", "1g-zgc", "2g-zgc", "4g-zgc")   # campaign.sh jvm-ladder(ADR-010 2차)


def app_mem(r):
    """k6 구간 docker stats 앱 메모리 사용(MiB) {mean, max, first, last} — first·last = 램프 뒤 첫 1분(k6 60~120초)·마지막 60초 평균(회차 안 추세, ADR-010 2차 안정성).
    파일 없음 = None. MemUsage '391.6MiB / 1GiB'의 앞쪽."""
    p = r["_dir"] / "timeline-server.jsonl"
    m = r.get("meta") or {}
    if not p.exists() or not m.get("k6_started") or not m.get("k6_ended"):
        return None
    off = r.get("clock_offset_s") or 0.0
    t0, t1 = ts(m["k6_started"]), ts(m["k6_ended"])
    # 리뷰: 램프 끝 = setup_data.startedAt + ramp_s(GC 정상 구간과 같은 기준 — PC 시계). 없으면 k6 시작 + 60
    ks = S.load(r["_dir"] / "k6-summary.json") or {}
    st = (ks.get("setup_data") or {}).get("startedAt")
    ramp_end = st / 1000 + (ks.get("ramp_s") or 0) if isinstance(st, (int, float)) else t0 + 60
    now, vals = None, []
    unit = {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024}
    for line in p.read_text().splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "t" in row:
            now = ts(row["t"]) - off
            continue
        if now is None or not (t0 <= now <= t1) or row.get("Name") != CONTAINERS["app"]:
            continue
        used = str(row.get("MemUsage", "")).split("/")[0].strip()
        for u, k in unit.items():
            if used.endswith(u):
                try:
                    vals.append((now, float(used[: -len(u)]) * k))
                except ValueError:
                    pass
                break
    if not vals:
        return None
    first = [v for t, v in vals if ramp_end <= t <= ramp_end + 60]   # 램프 뒤 첫 1분 — 램프 중 힙이 자라는 것은 추세가 아니다
    last = [v for t, v in vals if t >= t1 - 60]
    return {"mean": statistics.mean(v for _, v in vals), "max": max(v for _, v in vals),
            "first": statistics.mean(first) if first else None, "last": statistics.mean(last) if last else None}


def jvm_rep(camp, folder, r):
    """회차 하나의 ADR-010 지표 dict(ok 아닌 회차도 — 상태와 함께)."""
    if "_dir" not in r:
        r["_dir"] = camp.root / folder / "L4" / "S4c4200" / r["rep"]
    g = get(r, "gc", "app") or {}
    aj = S.load(r["_dir"] / "app-jvm.json")
    cp, mm = cpu(r), app_mem(r)
    rps = get(r, "fixed", "ok_rps_total")
    growth = (mm["last"] / mm["first"] - 1) * 100 if mm and num(mm.get("first")) and num(mm.get("last")) and mm["first"] else None
    return {"rep": r.get("rep"), "status": r.get("status"), "mem_bytes": (aj or {}).get("mem_bytes"), "collector": g.get("collector"),
            "zgc_generational": g.get("zgc_generational"), "heap_max_mb": g.get("heap_max_mb"), "heap_committed_max_mb": g.get("heap_committed_max_mb"),
            "full_steady": get(g, "k6_steady", "full"), "full_k6": get(g, "k6", "full"),
            "stalls_steady": get(g, "k6_steady", "stalls"), "stall_ms_max_steady": get(g, "k6_steady", "stall_ms_max"),
            "worker_stalls_steady": get(g, "k6_steady", "worker_stalls"), "gc_log_mb": (g["log_bytes"] / 1048576) if num(g.get("log_bytes")) else None,
            "stops_k6": get(g, "k6", "stops"), "stop_total_k6": get(g, "k6", "stop_ms_total"), "stop_max_k6": get(g, "k6", "stop_ms_max"),
            "stop_max_steady": get(g, "k6_steady", "stop_ms_max"),
            "ok_rps": rps, "rps_ratio": (rps / 4200) if num(rps) else None, "p99": r.get("hold_p99"), "p50": r.get("hold_p50"),
            "worst_p99_nz": get(r, "fixed", "worst_p99_nz_ms"), "p99_steady": get(r, "fixed", "p99_steady_nz_ms"), "p99_ramp": get(r, "fixed", "p99_ramp_nz_ms"),
            "err": r.get("hold_error_rate"), "sec_p99_max": get(r, "fixed", "sec_p99_max_ms"), "slow_secs": get(r, "fixed", "slow_secs_50ms"),
            "max_req": get(r, "fixed", "max_req_nz_ms"), "secs": get(r, "fixed", "secs_steady"), "secs_thin": get(r, "fixed", "secs_thin"),
            "secs_planned": get(r, "fixed", "secs_planned"), "status0_share": get(r, "fixed", "status0_share"),
            "app_cpu_mean": (cp or {}).get("app")[0] if (cp or {}).get("app") else None,
            "app_cpu_max": (cp or {}).get("app")[1] if (cp or {}).get("app") else None,
            "app_mem_mean": (mm or {}).get("mean"), "app_mem_max": (mm or {}).get("max"), "app_mem_growth_pct": growth}


JVM_COLS = [("status", "회차 상태", None), ("mem_bytes", "실제 메모리 한도 B", 0), ("collector", "수집기", None), ("heap_max_mb", "최대 힙 MB", 0),
            ("heap_committed_max_mb", "잡은 힙 최대 MB(로그 전체)", 0), ("full_steady", "Full GC(정상 구간)", 0), ("full_k6", "Full GC(k6 전체)", 0),
            ("stalls_steady", "앱 스레드 할당·재배치 멈춤(정상 구간)", 0), ("stall_ms_max_steady", "멈춤 최대 ms(정상 구간)", 1),
            ("worker_stalls_steady", "GC 작업자 재배치 멈춤(정상 구간 — 기록만)", 0),
            ("stops_k6", "GC 정지 수(k6)", 0), ("stop_total_k6", "GC 정지 합 ms", 0), ("stop_max_k6", "GC 정지 최대 ms(k6)", 1),
            ("stop_max_steady", "GC 정지 최대 ms(정상 구간)", 1), ("ok_rps", "성공 RPS", 0), ("p50", "선점 p50 ms", 2), ("p99", "선점 p99 ms", 2),
            ("p99_steady", "p99 램프 밖 ms", 2), ("worst_p99_nz", "가장 나쁜 30초 구간 p99 ms", 1), ("sec_p99_max", "1초 p99 최대 ms(램프 밖)", 1),
            ("slow_secs", "최대 응답 > 50ms인 초(램프 밖)", 0), ("max_req", "최대 응답 ms(램프 밖)", 0), ("secs", "집계 초(램프 밖)", 0), ("secs_planned", "계획 초", 0), ("secs_thin", "얇은 초", 0), ("status0_share", "코드 0 비율", 4),
            ("p99_ramp", "p99 램프 ms", 2), ("err", "에러율", 4), ("app_cpu_mean", "앱 CPU 평균 %", 0), ("app_cpu_max", "앱 CPU 최대 %", 0),
            ("app_mem_mean", "앱 메모리 평균 MiB", 0), ("app_mem_max", "앱 메모리 최대 MiB", 0), ("app_mem_growth_pct", "앱 메모리 램프 뒤 첫 1분→끝 1분 %", 1), ("gc_log_mb", "GC 로그 MB(/dev/shm — 앱 메모리에 포함)", 1)]


def jvm_main(root, camp, out, js, conds=JVM_CONDS, base_c="1g-auto"):
    """ADR-010 — 조건별 회차(1회 세트면 그 값, 다회 세트면 중앙값 [최소–최대]). 판정(J·K 가설)은 담지 않는다."""
    multi = camp.expect_reps > 1
    out.append(f"## 1. 앱 JVM 조건별 — S4c4200 tmpfs · 앱 기본값 조건(default) · {camp.expect_reps}회 (ADR-010)\n")
    rows, data, per_rep = [], {}, {}
    for c in conds:
        folder = f"{c}-jvm"
        camp.reps(folder, "S4c4200")   # ok 아닌 회차 메모(notes)·상태 기록
        allr = list((camp.summ.get(folder) or {}).get("L4/S4c4200") or [])
        reps = [jvm_rep(camp, folder, r) for r in allr]
        per_rep[c] = reps
        okr = [x for x in reps if x["status"] == "ok"]
        d = {"n": len(okr), "statuses": [x["status"] for x in reps]}
        for k, _, dd in JVM_COLS:
            if k in ("status", "collector"):
                d[k] = ", ".join(sorted({str(x[k]) for x in reps})) if reps else None
            else:
                d[k] = agg([x[k] for x in (okr if multi else (okr or reps))])   # 리뷰: 다회차는 ok 회차만 — 실패 회차 값은 회차별 표에만(1회 세트는 이전처럼 상태 칸과 함께)
        data[c] = d
        cell = lambda k, dd: d[k] if k in ("status", "collector") else (txt(d[k], dd) if multi else (fx(d[k]["values"][0], dd) if d[k]["values"] and num(d[k]["values"][0]) else "누락"))
        rows.append([c, d["n"]] + [cell(k, dd) or "누락" for k, _, dd in JVM_COLS])
    table(out, ["조건(메모리-수집기)", "n(ok)"] + [lbl for _, lbl, _ in JVM_COLS], rows)
    if multi:   # 회차별 안정성 원시 값(3회 모두 통과 판정은 메인 — 명세 §11)
        out.append("### 회차별 안정성 지표\n")
        rr = []
        for c in conds:
            for x in per_rep[c]:
                v = lambda k, dd=0: fx(x[k], dd) if num(x[k]) else ("누락" if x[k] is None else str(x[k]))
                rr.append([c, x["rep"], x["status"] or "누락", v("full_steady"), v("stalls_steady"), v("worker_stalls_steady"), v("stop_max_steady", 1), v("err", 4),
                           v("status0_share", 4), v("rps_ratio", 4), f"{v('secs')}/{v('secs_planned')}", v("app_mem_growth_pct", 1), v("gc_log_mb", 1),
                           v("p99", 2), v("sec_p99_max", 1), v("slow_secs"), v("app_cpu_mean")])
        table(out, ["조건", "회차", "상태", "Full GC(정상)", "앱 스레드 멈춤(정상)", "GC 작업자 멈춤(정상 — 기록만)", "GC 정지 최대 ms(정상)", "에러율", "코드 0 비율",
                    "성공 RPS ÷ 4,200", "집계 초/계획 초", "앱 메모리 램프 뒤 첫→끝 1분 %", "GC 로그 MB", "p99 ms", "1초 p99 최대 ms", "> 50ms 초", "앱 CPU 평균 %"], rr)
    base = data.get(base_c) or {}
    med = lambda d, k: (d.get(k) or {}).get("median") if isinstance(d.get(k), dict) else None
    v = lambda x, dd=0: fx(x, dd) if num(x) else ("누락" if x is None else str(x))
    rel = lambda a, b: (a / b - 1) * 100 if num(a) and num(b) and b else None
    facts = []
    for c in conds:
        if c == base_c:
            continue
        d = data.get(c) or {}
        facts.append(f"{c}: 수집기 {v(d.get('collector'))} · 정상 구간 Full GC {v(med(d, 'full_steady'))} · 멈춤 {v(med(d, 'stalls_steady'))} · "
                     f"정지 최대 {v(med(d, 'stop_max_steady'), 1)} ms · 1초 p99 최대 {v(med(d, 'sec_p99_max'), 1)} vs {v(med(base, 'sec_p99_max'), 1)} ms · "
                     f"p99 {ptxt(rel(med(d, 'p99'), med(base, 'p99')))} · 앱 CPU {ptxt(rel(med(d, 'app_cpu_mean'), med(base, 'app_cpu_mean')))}(÷ {base_c})")
    fact(out, " / ".join(facts) + (" — 값 = 회차 중앙값" if multi else " — 1회 측정(회차 사이 흔들림 미측정, ADR-010 §5 ①)"))
    js["jvm"] = data
    js["jvm_reps"] = per_rep


def main(argv):
    rm = jv = False
    if len(argv) == 3 and argv[0] == "--set" and argv[1] == "cost-remeasure":
        rm, argv = True, argv[2:]
    elif len(argv) == 3 and argv[0] == "--set" and argv[1] in ("jvm-baseline", "jvm-ladder"):
        jv, argv = argv[1], argv[2:]
    if len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    root = Path(argv[0])
    if not (root / "conditions.txt").exists():
        print(f"conditions.txt 없음: {root}", file=sys.stderr)
        return 2
    camp = Camp(root, expect_reps=1 if jv == "jvm-baseline" else 3)
    for f in (() if jv else ("wall.json", "wdrop.json")):   # jvm 표에는 벽·반감 칸이 없다
        if not (root / f).exists():
            camp.notes.append(f"{f} 없음 — 벽·반감 칸은 누락")
    out = [f"# ADR-009 확정 측정 비교 — `{root.name}`\n",
           "> `k6/ADR-009/scripts/compare.py`가 조건별 `summary.json`(summarize.py) · 회차 `effect.json`·원시 파일 · `wall.json`·`wdrop.json`에서 산출. "
           "값 = 중앙값 [최소–최대](n = 정확히 ok인 회차), 값이 빠진 회차는 `누락 k/n`. 정의는 스크립트 머리말. "
           "각 표 아래 '반증 관측 사실'은 비교 값뿐이며 가설 성립·기각 판정은 담지 않는다.\n"]
    js = {"campaign": root.name, "set": jv or ("cost-remeasure" if rm else "main")}
    if jv:
        out[0] = f"# ADR-010 앱 JVM 기준 비교 — `{root.name}`\n"
        if jv == "jvm-ladder":
            jvm_main(root, camp, out, js, JVM2_CONDS, "1g-g1")
        else:
            jvm_main(root, camp, out, js)
        return finish(root, camp, out, js)
    if rm:
        remeasure_main(root, camp, out, js)
        return finish(root, camp, out, js)

    out.append("## 1. 비용 — S4c4200 · S2 · S3-a20 (H4 · H6 · H7)\n")
    disk = s4c_block(camp, out, js, "disk", S4C_DISK)
    rr, rd = ratio_rows(disk, [("A+C", "A"), ("A+F", "A"), ("B", "control"), ("control", "control-noclose"), ("A", "control")])
    out.append("#### 디스크 — 기준 대비(중앙값 비율)\n")
    table(out, RATIO_HDR, rr)
    fact(out, "H4 " + cmp_fact(disk, "A+C", "A") + " / " + cmp_fact(disk, "A+F", "A"))
    fact(out, "H7 " + cmp_fact(disk, "control", "control-noclose",
                                f" · 마감 판정 평균 {txt(disk['control']['close_ms'], 4)} ms(noclose {txt(disk['control-noclose']['close_ms'], 4)}) · "
                                f"MultiXact ID {txt(disk['control']['mxid'])} vs {txt(disk['control-noclose']['mxid'])} · 선점당 WAL {txt(disk['control']['wal_per_hold'])} vs {txt(disk['control-noclose']['wal_per_hold'])} B"))
    fact(out, "H6 " + cmp_fact(disk, "B", "control", f" · 선점당 WAL {txt(disk['B']['wal_per_hold'])} vs {txt(disk['control']['wal_per_hold'])} B"))
    tmp = s4c_block(camp, out, js, "tmpfs", S4C_TMPFS)
    rr2, rd2 = ratio_rows(tmp, [("A+C", "A"), ("A+F", "A"), ("B", "control"), ("A", "control")])
    out.append("#### tmpfs — 기준 대비(중앙값 비율)\n")
    table(out, RATIO_HDR, rr2)
    fact(out, "H4 " + cmp_fact(tmp, "A+C", "A") + " / " + cmp_fact(tmp, "A+F", "A") + " / H6 " + cmp_fact(tmp, "B", "control"))
    js["ratios"] = {"disk": rd, "tmpfs": rd2}
    short_cost(camp, out, js)

    out.append("## 2. 효과 — S3e-a20 (H1 · H2 · H3)\n")
    effect_block(camp, out, js)

    out.append("## 3. 대조 비용 — C vs F (H4 · H5)\n")
    reconcile_block(camp, out, js, {"disk": disk, "tmpfs": tmp})

    out.append("## 4. 정리 — S3c-a20 (H8)\n")
    close_block(camp, out, js)
    return finish(root, camp, out, js)


def finish(root, camp, out, js):
    retries = sorted(str(p.relative_to(root)) for p in root.glob("*/L*/*/rep*.*") if p.is_dir())
    out.append("## 누락·의심·재측정\n")
    out += [f"- 재측정: `{p}`(보존된 옛 회차 — status `{(root / p / 'status').read_text().strip() if (root / p / 'status').exists() else '없음'}`) — 표에는 새 회차만 든다" for p in retries]
    out += [f"- {n}" for n in camp.notes] or ["- 자동 점검에서 걸린 항목 없음"]
    js.update(notes=camp.notes, retries=retries, statuses=camp.statuses)
    (root / "COMPARISON.md").write_text("\n".join(out) + "\n")
    (root / "comparison.json").write_text(json.dumps(js, ensure_ascii=False, indent=1, default=str))
    print(root / "COMPARISON.md")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
