#!/usr/bin/env python3
"""ADR-001 결과 요약 — 원시 결과(results/<matrix-id>/L*/<cell>/rep*/)에서 표를 산출한다.

    scripts/summarize.py results/<matrix-id>

출력: <matrix-id>/SUMMARY.md, <matrix-id>/summary.json, S3 곡선 <matrix-id>/curves/<level>-<cell>.csv
표의 모든 수치는 이 스크립트가 원시 파일에서 계산한다 (손 계산 금지). 회차 5개는 중앙값 [최소–최대]로 보인다.
"""
import csv
import json
import statistics
import sys
from datetime import datetime
from pathlib import Path

TTL_S = {"S3": 300, "S3A": 30, "S3B": 30}


def load(path):
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def metric(summary, name, stat="count", default=0):
    m = summary.get("metrics", {}).get(name)
    if not m:
        return default
    return m.get("values", {}).get(stat, default)


def spread(values):
    vals = [v for v in values if v is not None]
    if not vals:
        return "미측정"
    med = statistics.median(vals)
    fmt = (lambda x: f"{x:,.0f}") if all(float(v).is_integer() for v in vals) else (lambda x: f"{x:,.2f}")
    if len(vals) == 1:
        return fmt(med)
    return f"{fmt(med)} [{fmt(min(vals))}–{fmt(max(vals))}]"


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def rep_record(rep_dir):
    meta = load(rep_dir / "meta.json") or {}
    summ = load(rep_dir / "k6-summary.json") or {}
    cons = load(rep_dir / "consistency.json") or {}
    status = (rep_dir / "status").read_text().strip() if (rep_dir / "status").exists() else "missing"
    rec = {
        "rep": rep_dir.name, "status": status, "meta": meta,
        "hold_201": metric(summ, "hold_201"), "hold_409": metric(summ, "hold_409"),
        "hold_other": metric(summ, "hold_other"), "hold_error_rate": metric(summ, "hold_error", "rate", None),
        "hold_p50": metric(summ, "hold_duration", "med", None), "hold_p99": metric(summ, "hold_duration", "p(99)", None),
        "hold_max": metric(summ, "hold_duration", "max", None),
        "confirm_200": metric(summ, "confirm_200"), "confirm_409": metric(summ, "confirm_409"),
        "confirm_other": metric(summ, "confirm_other"),
        "session_confirmed": metric(summ, "session_confirmed"), "session_abandoned": metric(summ, "session_abandoned"),
        "session_gave_up": metric(summ, "session_gave_up"), "dropped": metric(summ, "dropped_iterations"),
        "consistency": cons,
    }
    if "stage_rates" in summ:
        rec["stages"] = stage_table(summ)
    return rec


def stage_table(summ):
    rows = []
    step = summ.get("step_seconds", 30)
    for i, target in enumerate(summ["stage_rates"]):
        dur = summ["metrics"].get(f"hold_duration{{stage:{i}}}", {}).get("values", {})
        err = summ["metrics"].get(f"hold_error{{stage:{i}}}", {}).get("values", {})
        ok = summ["metrics"].get(f"hold_201{{stage:{i}}}", {}).get("values", {})
        n = dur.get("count", 0)
        if not n:
            continue
        rows.append({"stage": i, "target_rps": target, "achieved_rps": n / step, "ok_rps": ok.get("count", 0) / step,
                     "p99_ms": dur.get("p(99)"), "error_rate": err.get("rate", 0)})
    return rows


def s4_limits(stages):
    """세 기준의 한계 처리량: 기준을 넘기 직전 단계의 달성 RPS, 포화점 = 최대 성공 RPS."""
    def last_ok(p99_ms, err):
        good = [s for s in stages if s["p99_ms"] is not None and s["p99_ms"] < p99_ms and s["error_rate"] < err]
        return max((s["ok_rps"] for s in good), default=0)
    return {"strict": last_ok(500, 0.01), "loose": last_ok(1000, 0.05),
            "saturation": max((s["ok_rps"] for s in stages), default=0)}


def s3_curve(rep_dir, base, out_csv):
    """DB 상태 시계열을 TTL로 정규화한 곡선. 모양 비교용 지표: 좌석 50%·90%가 예약되는 시각 / TTL."""
    meta = load(rep_dir / "meta.json") or {}
    path = rep_dir / "timeline-db.jsonl"
    if not path.exists() or "started" not in meta:
        return None
    t0, ttl = ts(meta["started"]), TTL_S[base]
    rows = []
    for line in path.read_text().splitlines():
        try:
            d = json.loads(line)
        except json.JSONDecodeError:
            continue
        rows.append({"t_over_ttl": (ts(d["t"]) - t0) / ttl, "reserved": d["reserved"], "held": d["held"],
                     "available": d["available"], "hold_rows": d["hold_rows"]})
    if not rows:
        return None
    with out_csv.open("a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["rep", *rows[0].keys()])
        if f.tell() == 0:
            w.writeheader()
        for r in rows:
            w.writerow({"rep": rep_dir.name, **r})
    seats = rows[0]["reserved"] + rows[0]["held"] + rows[0]["available"]

    def first_at(frac):
        return next((r["t_over_ttl"] for r in rows if r["reserved"] >= frac * seats), None)
    return {"t50": first_at(0.5), "t90": first_at(0.9), "final_reserved": rows[-1]["reserved"]}


def main(root):
    root = Path(root)
    (root / "curves").mkdir(exist_ok=True)
    for old in (root / "curves").glob("*.csv"):
        old.unlink()
    cells = {}
    for rep_dir in sorted(root.glob("L*/*/rep*")):
        level, cell = rep_dir.parent.parent.name, rep_dir.parent.name
        rec = rep_record(rep_dir)
        base = cell.split("-a")[0]
        if base in TTL_S:
            rec["curve"] = s3_curve(rep_dir, base, root / "curves" / f"{level}-{cell}.csv")
        cells.setdefault((level, cell), []).append(rec)

    lines = [f"# ADR-001 결과 요약 — `{root.name}`", "",
             "> `scripts/summarize.py`가 원시 결과에서 산출. 값 = 중앙값 [최소–최대] (회차 수는 표의 n). 미실행·실패 회차는 '실패' 열에 표시.", ""]

    def section(title, header, key_filter, row_fn):
        rows = [(k, v) for k, v in sorted(cells.items()) if key_filter(k[1])]
        if not rows:
            return
        lines.extend([f"## {title}", "", "| " + " | ".join(header) + " |", "|" + "---|" * len(header)])
        for (level, cell), reps in rows:
            ok = [r for r in reps if r["status"] == "ok"]
            failed = [f'{r["rep"]}:{r["status"]}' for r in reps if r["status"] != "ok"]
            lines.append("| " + " | ".join([level, cell, str(len(ok)), *row_fn(ok), ", ".join(failed) or "-"]) + " |")
        lines.append("")

    v = lambda recs, key: spread([r["consistency"].get(key) for r in recs])
    section("S1 같은 좌석 1,000명 (Q1)",
            ["단계", "셀", "n", "201(성공)", "409", "에러율", "p50 ms", "p99 ms", "좌석당 홀드 초과 행", "실패"],
            lambda c: c == "S1",
            lambda ok: [spread([r["hold_201"] for r in ok]), spread([r["hold_409"] for r in ok]),
                        spread([r["hold_error_rate"] for r in ok]), spread([r["hold_p50"] for r in ok]),
                        spread([r["hold_p99"] for r in ok]), v(ok, "v_excess_hold_rows")])
    section("S2 같은 사용자 동시 요청 (1인 2매)",
            ["단계", "셀", "n", "201(성공)", "정합 상한", "매수 초과 사용자", "사용자당 최대", "p99 ms", "실패"],
            lambda c: c == "S2",
            lambda ok: [spread([r["hold_201"] for r in ok]), "200", v(ok, "v_over_limit_users"),
                        v(ok, "max_per_user"), spread([r["hold_p99"] for r in ok])])
    section("S3 선점→확정 전체 흐름 (Q3·Q7)",
            ["단계", "셀", "n", "확정 세션", "이탈", "포기", "RESERVED", "CONFIRMED", "중복 홀드 좌석", "중복 확정",
             "오래된 HELD", "매수 초과", "t50/TTL", "t90/TTL", "hold p99 ms", "실패"],
            lambda c: c.startswith("S3"),
            lambda ok: [spread([r["session_confirmed"] for r in ok]), spread([r["session_abandoned"] for r in ok]),
                        spread([r["session_gave_up"] for r in ok]), v(ok, "reserved"), v(ok, "confirmed"),
                        v(ok, "v_duplicate_hold_seats"), v(ok, "v_duplicate_confirmed_seats"),
                        v(ok, "v_stale_held_seats"), v(ok, "v_over_limit_users"),
                        spread([(r.get("curve") or {}).get("t50") for r in ok]),
                        spread([(r.get("curve") or {}).get("t90") for r in ok]),
                        spread([r["hold_p99"] for r in ok])])
    section("S4 처리량 한계 (성공 RPS)",
            ["단계", "셀", "n", "엄격(p99<500ms·에러<1%)", "완화(p99<1s·에러<5%)", "포화점", "누락 반복(dropped)", "실패"],
            lambda c: c == "S4",
            lambda ok: [spread([s4_limits(r.get("stages", []))[k] for r in ok]) for k in ("strict", "loose", "saturation")]
                       + [spread([r["dropped"] for r in ok])])

    (root / "SUMMARY.md").write_text("\n".join(lines))
    (root / "summary.json").write_text(json.dumps(
        {f"{k[0]}/{k[1]}": v for k, v in sorted(cells.items())}, ensure_ascii=False, indent=1, default=str))
    print(root / "SUMMARY.md")


if __name__ == "__main__":
    main(sys.argv[1])
