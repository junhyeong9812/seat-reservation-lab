#!/usr/bin/env python3
"""ADR-003 조건 교차표(ADR-002 compare.py 복사 — 조건 표기만 전략·풀·앱 대수) — 캠페인 결과(results/<campaign-id>/)의 조건별 summary.json·S5 원시 결과에서 비교표를 산출한다.

    scripts/compare.py results/<campaign-id>     → <campaign-id>/COMPARISON.md · comparison.json

먼저 조건마다 `summarize.py results/<campaign-id>/<조건>`을 돌려 summary.json이 있어야 한다(conditions.txt와 대조 — 빠진 조건은 '미측정' 행).
표의 모든 수치는 원시 파일에서 계산한다 (손 계산 금지). 값 = 중앙값 [최소–최대], 회차별 값은 괄호 없이 나열.

회차 포함 규칙 (n = 계산에 쓴 회차/전체 회차):
- S1·S2·S3: status `ok`만.
- S4: `ok` + status에 `s4-no-successful-stage`가 있는 회차(첫 단계부터 한계를 넘은 것 = 실측 결과 '한계 < 첫 단계').
  그 밖의 비정상(`invalid-*`만 — 연결 불가 등 하네스·경로 실패)은 제외하고 비정상 열에 사유를 적는다.
- S4 한계가 계단 끝까지 기준을 지켰으면(모든 단계 통과) 진짜 한계가 아니라 계단 상한이다 → 값 앞에 `≥`.
- CPU = k6 실행 구간 **전체**(과부하 단계·중단 후 꼬리 포함)의 docker stats 최대값. 구간 안 표본 사이 공백이 30초를 넘으면
  '수집 공백'으로 표시한다(서버 지표 수집 = k6 PC에서 SSH — 공백은 경로 단절의 신호).
"""
import gzip
import json
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

LEVELS = ("L2", "L4")
S5_SIZES = (0, 10000, 100000, 1000000, 5000000)
S5_QUERIES = {"q1": "Q1 1인 2매 — 홀드 수", "q2": "Q2 1인 2매 — 확정 수", "q3": "Q3 좌석의 홀드 목록", "q4": "Q4 만료 배치 조회"}
RESULT_FAIL = "s4-no-successful-stage"
GAP_S = 30
STRICT = (500, 0.01)
ERR_CLASSES = ("2xx", "409", "5xx", "0-1050", "0-1211", "0-1220", "0-1000")


def spread(values, digits=0):
    v = [x for x in values if x is not None]
    if not v:
        return "미측정"
    fmt = (lambda x: f"{x:,.{digits}f}")
    return f"{fmt(statistics.median(v))} [{fmt(min(v))}–{fmt(max(v))}]"


def reps_list(values, digits=0):
    return ", ".join("-" if x is None else f"{x:,.{digits}f}" for x in values)


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def cpu_max(rep_dir, meta):
    """k6 실행 구간의 앱·DB CPU% 최대값 + 수집 공백(초). timeline-server.jsonl: 시각 행 뒤에 컨테이너 행들이 온다."""
    path = rep_dir / "timeline-server.jsonl"
    if not path.exists() or not meta.get("k6_started") or not meta.get("k6_ended"):
        return None, None, None
    t0, t1 = ts(meta["k6_started"]), ts(meta["k6_ended"])
    now, stamps = None, []
    app, db = [], []
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        if "t" in r:
            now = ts(r["t"])
            if t0 <= now <= t1:
                stamps.append(now)
            continue
        if now is None or not (t0 <= now <= t1):
            continue
        val = float(r.get("CPUPerc", "0").rstrip("%") or 0)
        if r.get("Name") == "seatlab-app-1":
            app.append(val)
        elif r.get("Name") == "seatlab-db-1":
            db.append(val)
    edges = [t0] + stamps + [t1]
    gap = max(b - a for a, b in zip(edges, edges[1:]))
    return (max(app) if app else None), (max(db) if db else None), gap


def censored(rec):
    """모든 계획 단계가 끝까지 돌았고 전부 엄격 기준을 지켰나 — 그렇다면 한계는 계단 상한(진짜 한계 미관측).
    '끝까지 돌았다' = k6 정상 종료(exit 0 — 단계 에러율 중단은 99). 요청 0인 단계는 stage_table이 건너뛰므로 단계 수로는 판정하지 않는다."""
    stages = rec.get("stages") or []
    if not stages or rec.get("meta", {}).get("k6_exit") != 0 or any(s["partial"] for s in stages):
        return False
    return all(s["p99_ms"] is not None and s["p99_ms"] < STRICT[0] and s["error_rate"] < STRICT[1] for s in stages)


def spread_censored(values, cens):
    """중앙값 [최소–최대] — 그 값을 정하는 회차가 계단 상한이면 앞에 ≥ (짝수 개면 가운데 두 회차 중 하나라도)."""
    if not values:
        return "미측정"
    order = sorted(range(len(values)), key=lambda i: values[i])
    n = len(order)
    mid = [order[n // 2]] if n % 2 else [order[n // 2 - 1], order[n // 2]]
    mark = lambda idx: "≥" if any(cens[i] for i in idx) else ""
    lo, hi = order[0], order[-1]
    return (f"{mark(mid)}{statistics.median(values):,.0f} [{mark([lo])}{values[lo]:,.0f}–{mark([hi])}{values[hi]:,.0f}]")


def used_reps(cell, reps):
    if cell == "S4":
        return [r for r in reps if r["status"] == "ok" or RESULT_FAIL in r["status"]]
    return [r for r in reps if r["status"] == "ok"]


def load_conditions(root):
    conds, missing = {}, []
    listed = [line.split()[0] for line in (root / "conditions.txt").read_text().splitlines() if line.strip()] \
        if (root / "conditions.txt").exists() else []
    for name in listed or sorted(p.parent.name for p in root.glob("*/summary.json")):
        f = root / name / "summary.json"
        if not f.exists():
            missing.append(name)
            continue
        plan = json.loads((f.parent / "plan.json").read_text())
        conds[name] = {"plan": plan, "data": json.loads(f.read_text())}
    return conds, missing


def cond_label(name, plan):
    c = plan["condition"]
    return f"{name} (전략 {c['strategy']}·풀 {c['pool']}·앱 {c['apps']}대)"


def main(root):
    root = Path(root)
    conds, missing = load_conditions(root)
    if not conds:
        print(f"{root}: 조건별 summary.json 없음 — summarize.py를 먼저 돌린다", file=sys.stderr)
        return 1
    problems = []
    out, js = [], {"conditions": {}, "s5": {}}
    out.append(f"# ADR-003 조건 교차표 — `{root.name}`\n")
    out.append("> `scripts/compare.py`가 조건별 `summary.json`(summarize.py 산출)·S5 원시 결과에서 산출. "
               "값 = 중앙값 [최소–최대]. n = 계산에 쓴 회차/전체 회차(포함 규칙은 스크립트 머리말).\n")
    for name in missing:
        out.append(f"- **미측정**: `{name}` — conditions.txt에 있으나 summary.json 없음")
        problems.append(f"missing condition {name}")

    def section(title, cell_suffix, cols, row_fn):
        out.append(f"\n## {title}\n")
        out.append("| 조건 | 단계 | n | " + " | ".join(cols) + " |")
        out.append("|---|---|---|" + "---|" * len(cols))
        for name, c in conds.items():
            for lv in LEVELS:
                key = f"{lv}/{cell_suffix}"
                if key not in c["data"]:
                    continue
                reps = c["data"][key]
                used = used_reps(cell_suffix, reps)
                vals = row_fn(name, lv, reps, used)
                js["conditions"].setdefault(name, {})[key] = vals["json"]
                out.append(f"| {cond_label(name, c['plan'])} | {lv} | {len(used)}/{len(reps)} | " + " | ".join(vals["cells"]) + " |")

    def s1(name, lv, reps, used):
        v201 = [r["hold_201"] for r in used]
        p99 = [r["hold_p99"] for r in used]
        p50 = [r["hold_p50"] for r in used]
        rank = [r.get("first_winner_rank") for r in used]
        lockm = [r.get("lock_waiting_max") for r in used]
        acqm = [r.get("acquire_mean_ms") for r in used]
        acqx = [r.get("acquire_max_ms") for r in used]
        return {"cells": [spread(v201), reps_list(v201), spread(p50), spread(p99), spread(rank), reps_list(rank), spread(lockm), spread(acqm, 1), spread(acqx)],
                "json": {"hold_201": v201, "hold_p50": p50, "hold_p99": p99, "first_winner_rank": rank, "lock_waiting_max": lockm,
                         "acquire_mean_ms": acqm, "acquire_max_ms": acqx}}
    section("S1 같은 좌석 1,000명 — 201 수(정합이면 1) · 지연 · 공정성(첫 승자 도착 순위, 1,000 중) · 락·커넥션 대기", "S1",
            ["201", "회차별 201", "p50 ms", "p99 ms", "첫 승자 순위", "회차별 순위", "DB 락 대기 최대", "커넥션 획득 대기 평균 ms", "획득 대기 최대 ms"], s1)

    def s2(name, lv, reps, used):
        v201 = [r["hold_201"] for r in used]
        over = [r["consistency"].get("v_over_limit_users") for r in used]
        p99 = [r["hold_p99"] for r in used]
        return {"cells": [spread(v201), spread(over), spread(p99)], "json": {"hold_201": v201, "over_limit_users": over, "hold_p99": p99}}
    section("S2 같은 사용자 동시 요청 — 201(상한 200)·매수 초과 사용자", "S2", ["201", "매수 초과 사용자", "p99 ms"], s2)

    def s4(name, lv, reps, used):
        acqm4 = [r.get("acquire_mean_ms") for r in used]
        strict = [r["limits"]["strict"] for r in used]
        sat = [r["limits"]["saturation"] for r in used]
        cens = [censored(r) for r in used]
        cpus = [cpu_max(root / name / lv / "S4" / r["rep"], r.get("meta", {})) for r in used]
        app = [a for a, _, _ in cpus]
        db = [d for _, d, _ in cpus]
        gaps = [f"{r['rep']}:{g:.0f}s" for r, (_, _, g) in zip(used, cpus) if g is not None and g > GAP_S]
        gaps += [f"{r['rep']}:지표 없음" for r, (_, _, g) in zip(used, cpus) if g is None]  # 수집 안 됨 ≠ 공백 없음
        excluded = [f"{r['rep']}:{r['status']}(제외)" for r in reps if r not in used]
        result_fail = [f"{r['rep']}:{r['status']}(결과 포함)" for r in used if r["status"] != "ok"]
        lim = spread_censored(strict, cens)
        per_rep = ", ".join(("≥" if c else "") + f"{v:,.0f}" for v, c in zip(strict, cens))
        return {"cells": [lim, per_rep, spread(sat), spread(app), spread(db), spread(acqm4, 2), ", ".join(gaps) or "-", ", ".join(excluded + result_fail) or "-"],
                "json": {"strict": strict, "censored": cens, "saturation": sat, "app_cpu_max": app, "db_cpu_max": db, "acquire_mean_ms": acqm4,
                         "collection_gaps": gaps, "excluded": excluded, "result_fail": result_fail}}
    section("S4 처리량 한계 — 엄격(p99<500ms·에러<1%) 직전 단계 성공 RPS(≥ = 계단 끝까지 통과 — 계단 상한) · 포화점 · 부하 구간 전체 CPU% 최대",
            "S4", ["엄격 한계", "회차별 엄격 한계", "포화점", "앱 CPU% 최대(구간 전체)", "DB CPU% 최대(구간 전체)", "커넥션 획득 대기 평균 ms", f"서버 지표 수집 공백 >{GAP_S}s", "비정상 회차"], s4)

    for cell in ("S3-a0", "S3-a20", "S3-a50"):
        def s3(name, lv, reps, used):
            ce = [r["confirm_error_rate"] for r in used]
            he = [r["hold_error_rate"] for r in used]
            conf = [r["consistency"].get("confirmed") for r in used]
            gap = [r["ownership_gap"] for r in used]
            return {"cells": [spread(ce, 2), reps_list(ce, 2), spread(he, 2), spread(conf), spread(gap)],
                    "json": {"confirm_error_rate": ce, "hold_error_rate": he, "confirmed": conf, "ownership_gap": gap}}
        section(f"S3 원본 {cell} — 확정 에러율·유령 확정(확정200 − CONFIRMED)", cell,
                ["확정 에러율", "회차별 확정 에러율", "선점 에러율", "CONFIRMED", "확정200 − CONFIRMED"], s3)

    # ---- S6 경합 강도 스윕(ADR-003·004): 조건(전략×지연) × K × 단계 — 회차 중앙값 ----------------------
    s6 = [(n, c) for n, c in conds.items() if any(k.split("/")[1].startswith("S6") for k in c["data"])]
    if s6:
        out.append("\n## S6 경합 강도 스윕 — 전략 × 임계 구역 지연 × 핫 좌석 K × 핫 도착률 단계 (회차 중앙값)\n")
        out.append("> 핫 201/s = 핫 스트림 201 응답 수 ÷ 단계 시간 — 중복 승리도 센다(정합 방식에서만 '좌석이 넘어간 속도'). 무경합 = 경합 없는 다른 좌석 요청 500건/s(코드·k6 태그 이름은 neighbor). "
                   "무경합 처리/s·p99·에러율이 핫 경합의 '번짐'. 중복 = 판정기 중복 좌석 수(v_duplicate_hold_seats) / 판정기 초과 홀드 수(v_excess_hold_rows) / "
                   "요청 기록 일시 중복 홀드 수(duplicate — 초과 홀드와 같은 단위). 커넥션 획득 평균은 획득 1회당(분모가 방식마다 다르다). "
                   "Hikari 대기 최대·dropped(k6가 시작 못 한 반복)·락 대기는 회차 전체.\n")
        out.append("| 조건 | K | n | 단계 목표/s | 핫 201/s | 핫 p99 ms | 핫 에러율 | 무경합 처리/s | 무경합 p99 ms | 무경합 에러율 | 중복 좌석·초과 홀드·일시 | 커넥션 획득 평균 ms | Hikari 대기 최대 | dropped | 락 대기 최대 |")
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for name, c in s6:
            for key, reps in c["data"].items():
                lv, cell = key.split("/")
                if not cell.startswith("S6"):
                    continue
                used = [r for r in reps if r["status"] == "ok"]
                if not used:  # 무음 누락 금지 — 쓸 회차가 없으면 미측정 행
                    out.append(f"| {name} | {cell[4:]} | 0/{len(reps)} | 미측정 | | | | | | | | | | | |")
                    js.setdefault("s6", {})[f"{name}/{key}"] = {"stages": [], "n": 0, "n_total": len(reps)}
                    continue
                nst = max((len(r.get("s6_stages") or []) for r in used), default=0)
                rows6 = []
                for i in range(nst):
                    st = [r["s6_stages"][i] for r in used if len(r.get("s6_stages") or []) > i]
                    q = lambda k, d=0: spread([x[k] for x in st], d)
                    first = i == 0
                    out.append(f"| {name if first else ''} | {cell[4:] if first else ''} | {f'{len(used)}/{len(reps)}' if first else ''} | {st[0]['target']:,} | {q('hot_ok', 1)} | {q('hot_p99')} | "
                               f"{q('hot_err', 3)} | {q('nb_ok', 1)} | {q('nb_p99')} | {q('nb_err', 3)} | "
                               + (f"{spread([r['consistency'].get('v_duplicate_hold_seats') for r in used])}·{spread([r['consistency'].get('v_excess_hold_rows') for r in used])}·"
                                  f"{spread([r.get('duplicate') for r in used])} | {spread([r.get('acquire_mean_ms') for r in used], 1)} | "
                                  f"{spread([r.get('hikari_pending_max') for r in used])} | {spread([r.get('dropped') for r in used])} | {spread([r.get('lock_waiting_max') for r in used])} |"
                                  if first else "| | | | |"))
                    rows6.append({k: [x[k] for x in st] for k in st[0]})
                js.setdefault("s6", {})[f"{name}/{key}"] = {"stages": rows6, "n": len(used), "n_total": len(reps),
                    "dup_seats": [r["consistency"].get("v_duplicate_hold_seats") for r in used],
                    "dup_excess_rows": [r["consistency"].get("v_excess_hold_rows") for r in used], "dup_transient": [r.get("duplicate") for r in used],
                    "hikari_pending_max": [r.get("hikari_pending_max") for r in used], "dropped": [r.get("dropped") for r in used],
                    "acquire_mean_ms": [r.get("acquire_mean_ms") for r in used], "lock_waiting_max": [r.get("lock_waiting_max") for r in used]}

    # ---- 판정기 전수: 셀마다 위반(v_* 합 > 0) 회차 수 — 정합성 '전 회차' 주장의 산출 근거 ----------------
    out.append("\n## 판정기 전수 — 셀별 위반 회차(v_* 합 > 0) / 정상 회차\n")
    out.append("| 조건 | 셀 | 위반 회차 | 정상 회차 | v_* 합 최대 |")
    out.append("|---|---|---|---|---|")
    for name, c in conds.items():
        for key, reps in c["data"].items():
            ok = [r for r in reps if r["status"] == "ok"]
            sums = [sum(v for k, v in (r.get("consistency") or {}).items() if k.startswith("v_") and isinstance(v, (int, float))) for r in ok]
            out.append(f"| {name} | {key} | {sum(1 for x in sums if x)} | {len(ok)} | {max(sums, default=0):,} |")
            js.setdefault("oracle_sweep", {})[f"{name}/{key}"] = {"violating": sum(1 for x in sums if x), "ok": len(ok), "max": max(sums, default=0)}

    # ---- 응답 코드 분류 (errsplit.py 산출 — 있으면) ----------------------------------------------------
    es = root / "errsplit.json"
    if es.exists():
        split = json.loads(es.read_text())
        legend = ("> 0-1050 요청 타임아웃(30s) · 0-1211 연결 수립 실패(dial i/o timeout) · 0-1220 연결 끊김(reset by peer) · "
                  "0-1000 그 밖(EOF 등). 그 밖의 코드는 '기타'. 괄호 = 그 요청 전체 대비 비율. m = 합산한 회차/포함 규칙상 회차.\n")

        def row(tot):
            n = sum(tot.values())
            other = sum(v for k, v in tot.items() if k not in ERR_CLASSES)
            cells = [f"{tot.get(k, 0):,} ({tot.get(k, 0) / n:.1%})" if n else "0" for k in ERR_CLASSES]
            return f"{n:,} | " + " | ".join(cells) + f" | {other:,}"

        out.append("\n## 응답 코드 분류 — S1·S2·S3 (`scripts/errsplit.py` 산출)\n")
        out.append(legend)
        out.append("| 조건 | 단계 | 셀 | 요청 | m | 전체 | " + " | ".join(ERR_CLASSES) + " | 기타 |")
        out.append("|---|---|---|---|---|---|" + "---|" * (len(ERR_CLASSES) + 1))
        stage_rows, out_missing = [], []
        for name, c in conds.items():
            for key, reps in c["data"].items():
                lv, cell = key.split("/")
                used = [r["rep"] for r in used_reps(cell, reps)]
                have = [rep for rep in used if f"{name}/{lv}/{cell}/{rep}" in split
                        and "_truncated" not in split[f"{name}/{lv}/{cell}/{rep}"]]
                for rep in sorted(set(used) - set(have)):
                    problems.append(f"errsplit 누락·잘림 {name}/{lv}/{cell}/{rep}")
                    out_missing.append(f"{name}/{lv}/{cell}/{rep}")
                for req in ("hold", "confirm"):
                    tot, per_stage = {}, {}
                    for rep in have:
                        for k, v in split[f"{name}/{lv}/{cell}/{rep}"].items():
                            if not k.startswith(req + "|"):
                                continue
                            _, stage, cls = k.split("|")
                            tot[cls] = tot.get(cls, 0) + v
                            per_stage.setdefault(stage, {})[cls] = per_stage.setdefault(stage, {}).get(cls, 0) + v
                    if not tot:
                        continue
                    js.setdefault("errsplit", {})[f"{name}/{key}/{req}"] = {"m": f"{len(have)}/{len(used)}", "total": tot, "per_stage": per_stage}
                    if cell == "S4":
                        for stage in sorted(per_stage, key=lambda x: int(x) if x.isdigit() else -1):
                            stage_rows.append(f"| {name} | {lv} | {stage} | {len(have)}/{len(used)} | {row(per_stage[stage])} |")
                    else:
                        out.append(f"| {name} | {lv} | {cell} | {req} | {len(have)}/{len(used)} | {row(tot)} |")
        out.append("\n## 응답 코드 분류 — S4 단계별 (선점, 포함 규칙상 회차 합계)\n")
        out.append(legend)
        out.append("| 조건 | 단계 | S4 단계 | m | 전체 | " + " | ".join(ERR_CLASSES) + " | 기타 |")
        out.append("|---|---|---|---|---|" + "---|" * (len(ERR_CLASSES) + 1))
        out.extend(stage_rows)
        if out_missing:
            out.append(f"\n- **표에서 빠진 회차(원자료 없음·잘림)**: {', '.join(out_missing)}")
        trunc = [k for k, v in split.items() if "_truncated" in v]
        if trunc:
            out.append(f"\n- 잘린 원시 파일(중단된 회차 — 표에 쓰지 않음): {', '.join(trunc)}")

    # ---- S5 ------------------------------------------------------------------------------------
    s5 = root / "s5"
    if s5.exists():
        def pg(path, pattern):
            for cand in (path, path.with_suffix(path.suffix + ".gz")):
                if cand.exists():
                    with (gzip.open(cand, "rt", errors="replace") if cand.suffix == ".gz" else open(cand, errors="replace")) as f:
                        for line in f:
                            m = re.search(pattern, line)
                            if m:
                                return float(m.group(1))
            return None

        def scans(path):
            if not path.exists():
                return "미측정"
            return " · ".join(sorted(set(m.strip() for m in re.findall(r"((?:Parallel )?(?:Seq|Index Only|Index|Bitmap Heap) Scan(?: using \w+)? on \w+)", path.read_text()))))

        out.append("\n## S5 쿼리 비용 — 1연결 평균 지연 ms (pgbench, DB 컨테이너 안)\n")
        out.append("| 단계 | 쿼리 | 인덱스 | " + " | ".join(f"{n:,}" for n in S5_SIZES) + " |")
        out.append("|---|---|---|" + "---|" * len(S5_SIZES))
        for lv in LEVELS:
            for q, qname in S5_QUERIES.items():
                for idx in ("off", "on"):
                    vals = [pg(s5 / f"{lv}-idx{idx}" / f"N{n}" / f"{q}-c1.txt", r"latency average = ([0-9.]+)") for n in S5_SIZES]
                    js["s5"].setdefault(lv, {}).setdefault(q, {})[idx] = {"c1_avg_ms": dict(zip(map(str, S5_SIZES), vals))}
                    out.append(f"| {lv} | {qname} | {idx} | " + " | ".join("미측정" if v is None else f"{v:.3f}" for v in vals) + " |")
        out.append("\n## S5 쿼리 처리량 — 10연결 tps (상대 비교 전용: pgbench 클라이언트가 DB CPU 한도를 함께 쓴다)\n")
        out.append("| 단계 | 쿼리 | 인덱스 | " + " | ".join(f"{n:,}" for n in S5_SIZES) + " |")
        out.append("|---|---|---|" + "---|" * len(S5_SIZES))
        for lv in LEVELS:
            for q, qname in S5_QUERIES.items():
                for idx in ("off", "on"):
                    vals = [pg(s5 / f"{lv}-idx{idx}" / f"N{n}" / f"{q}-c10.txt", r"tps = ([0-9.]+)") for n in S5_SIZES]
                    js["s5"][lv][q][idx]["c10_tps"] = dict(zip(map(str, S5_SIZES), vals))
                    out.append(f"| {lv} | {qname} | {idx} | " + " | ".join("미측정" if v is None else f"{v:,.0f}" for v in vals) + " |")
        out.append("\n## S5 실행 계획 — 배경 100만, L2\n")
        out.append("| 쿼리 | 인덱스 없음 | 인덱스 있음 |\n|---|---|---|")
        for q, qname in S5_QUERIES.items():
            off = scans(s5 / "L2-idxoff" / "N1000000" / f"explain-{q}.txt")
            on = scans(s5 / "L2-idxon" / "N1000000" / f"explain-{q}.txt")
            js["s5"].setdefault("explain_L2_N1000000", {})[q] = {"off": off, "on": on}
            out.append(f"| {qname} | {off} | {on} |")

    (root / "COMPARISON.md").write_text("\n".join(out) + "\n")
    (root / "comparison.json").write_text(json.dumps(js, ensure_ascii=False, indent=1))
    print(f"wrote {root / 'COMPARISON.md'}")
    if problems:
        print("문제 " + str(len(problems)) + "건:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
