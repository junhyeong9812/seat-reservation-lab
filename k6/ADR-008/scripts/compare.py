#!/usr/bin/env python3
"""ADR-008 확정 측정 비교(명세 §5) — 기본값 기동(ADR-008 캠페인) 대 ADR-007 precheck 본측정(방식 명시 counter-upsert-precheck, 명세 §3 기준소스).
ADR-007 compare.py(방식 × 설정 117회 교차표)는 쓰지 않는다 — 여기는 셀 5개 × (ADR-008 · ADR-007 precheck-default) 두 열뿐이다.

    scripts/compare.py results/<campaign-id> [--baseline <ADR-007 캠페인 폴더>]   → <campaign-id>/COMPARISON.md · comparison.json

먼저 조건마다 `summarize.py results/<campaign-id>/<조건>`을 돌려 summary.json이 있어야 한다(기준 쪽은 ADR-007 본측정에 이미 있다).
조건 짝: default-<셀> ↔ 기준 counter-upsert-precheck-default-<셀> (셀 = s2 · s3a20 · s7m1 · s7m1late · s4).
회차 포함: status가 정확히 ok인 회차만 — S4의 s4-no-successful-stage도 빼고 '문제'로 센다(리뷰 루프 1 C2. ADR-007 compare.py는 그 S4를 결과로 넣었다).

판정(명세 §5) — 하나라도 어긋나면 '문제'로 세고 exit 1:
  합격 항목(ADR-008 회차마다 rep_status도 같은 기준으로 본다 — summarize.py --accept-check): 매수 초과 0(판정기 + S2 응답·끝 상태 사용자 수) · v_counter_mismatch 0 ·
    억울한 좌석 0(S7-m1) · 명세 순서 위반 0(S7-m1-late, 기회 > 0) · 요청당 빌림(분모 = 선점 + 확정 — 리뷰 루프 2 D1) 1.0 = [0.98, 1.02](모든 셀 — 리뷰 루프 1 C1,
    근거는 summarize.py BORROW_ONE 주석) · 매수 초과·카운터 불일치 항목이 빠진 회차가 있으면 그것도 문제(D3) ·
    적용 매수 방식 = COUNTER_UPSERT_PRECHECK(meta applied) · limit_source = default.
  계획 회차 수: 짧은 셀 3회 · S4 1회가 모두 ok.
  기준(ADR-007) 쪽(리뷰 루프 1 C3): 기준 폴더가 없으면 exit 2. 셀마다 기준 summary.json이 없거나, 기준 회차가 0이거나, 기준 ok 회차가 계획(3회 — S4는 1회 이상)보다 적으면 '문제'.
같은 크기(명세 §5 '같은 자릿수' — 다르면 원인 기록, **판정이 아니라 기록 대상**(exit에 영향 없음), 리뷰 루프 1 C9): 지표마다 ADR-008 회차 중앙값 ÷ ADR-007 중앙값 비율을 내고
  비율이 0.5~2배 밖이면 '크기 다름'으로 표시한다(표의 '비율 0.5~2배' 열이 이 규칙). 자릿수(⌊log10⌋) 일치도 참고 열로 함께 낸다.
표의 모든 수치는 summary.json에서 계산한다(손 계산 금지).
"""
import json
import math
import statistics
import sys
from pathlib import Path

DEFAULT_BASELINE = Path(__file__).resolve().parents[2] / "ADR-007" / "results" / "20261009-adr007-ea8b85cc"
CELLS = (("s2", "S2", 3), ("s3a20", "S3-a20", 3), ("s7m1", "S7-m1", 3), ("s7m1late", "S7-m1-late", 3), ("s4", "S4", 1))
# 자릿수 비교 지표(명세 §5: S3-a20 WAL, S2 p99 — 그 밖은 참고)
MAGNITUDE = {
    "S2": [("hold_p99", "선점 p99 ms")],
    "S3-a20": [("io_wal_mb", "WAL MB"), ("io_wal_bytes_per_hold", "WAL B/선점"), ("hold_p99", "선점 p99 ms")],
    "S7-m1": [("hold_p99", "선점 p99 ms")],
    "S7-m1-late": [("hold_p99", "선점 p99 ms")],
    "S4": [("limit_strict", "엄격 한계 RPS"), ("saturation", "포화점 RPS"), ("io_wal_bytes_per_hold", "WAL B/선점")],
}
EXPECTED_LIMIT = "COUNTER_UPSERT_PRECHECK"


def load(p):
    try:
        return json.loads(Path(p).read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def used(cell, reps):
    return [r for r in reps if r.get("status") == "ok"]   # 정확히 ok만(머리말 — 리뷰 루프 1 C2)


def val(r, key):
    if key == "limit_strict":
        return (r.get("limits") or {}).get("strict")
    if key == "saturation":
        return (r.get("limits") or {}).get("saturation")
    return r.get(key)


def med(xs):
    xs = [x for x in xs if isinstance(x, (int, float))]
    return statistics.median(xs) if xs else None


def digits(x):
    return None if x is None or x <= 0 else math.floor(math.log10(x))


def fmt(x, d=3):
    return "-" if x is None else (f"{x:,.{d}f}" if isinstance(x, float) else f"{x:,}")


def reps_of(root, cond, cell):
    s = load(root / cond / "summary.json")
    return None if s is None else s.get(f"L4/{cell}", [])


def accept_row(cell, reps):
    """합격 항목 회차값(최대·범위) — 기준 쪽에도 같은 식으로(같은 정의로 비교)."""
    cons = [r.get("consistency") or {} for r in reps]
    s7 = [r.get("s7") or {} for r in reps]
    row = {
        "over_limit_max": max((c.get("v_over_limit_users") for c in cons if c.get("v_over_limit_users") is not None), default=None),
        "over_limit_missing": sum(1 for c in cons if c.get("v_over_limit_users") is None),   # 리뷰 루프 2 D3 — 빠진 회차를 조용히 건너뛰지 않게
        "counter_mismatch_max": max((c.get("v_counter_mismatch") for c in cons if c.get("v_counter_mismatch") is not None), default=None),
        "counter_mismatch_missing": sum(1 for c in cons if c.get("v_counter_mismatch") is None),
        # 요청당 빌림(리뷰 루프 2 D1 — summarize.py borrow_per_request). 그 필드가 없는 옛 요약(ADR-007 기준 쪽)은 선점당 값으로 보여 준다(기준 열은 표시만, 판정 아님)
        "borrow": [r.get("borrow_per_request", r.get("borrow_per_hold")) for r in reps],
        "borrow_kind": sorted({"request" if "borrow_per_request" in r else "hold" for r in reps}),
        "applied_limit": sorted({((r.get("meta") or {}).get("applied") or {}).get("limit_strategy") for r in reps} - {None}),
        "limit_source": sorted({(r.get("meta") or {}).get("limit_source") for r in reps} - {None}),
    }
    if cell == "S2":   # 리뷰 루프 1 C7 — 응답·끝 상태 기준 매수 초과 사용자(summarize.py s2_users)
        s2 = [r.get("s2") or {} for r in reps]
        row["over_users_resp"] = [x.get("over_users_resp") for x in s2]
        row["over_users_db"] = [x.get("over_users_db") for x in s2]
    if cell == "S7-m1":
        row["wronged_seats"] = [x.get("wronged_seats") for x in s7]
    if cell == "S7-m1-late":
        row["order_violations"] = [x.get("u_hle_after_r_201") for x in s7]
        row["opportunities"] = [x.get("u_after_r_201") for x in s7]
    return row


def problems_of(cell, plan_reps, all_reps, reps, row):
    out = []
    if len(all_reps) != plan_reps or len(reps) != plan_reps:
        out.append(f"회차 {len(reps)}/{plan_reps} ok(전체 {len(all_reps)}: {[r.get('status') for r in all_reps]})")
    if row["over_limit_max"] != 0 or row["over_limit_missing"]:
        out.append(f"매수 초과 {row['over_limit_max']} (항목 없음 {row['over_limit_missing']}회)")
    if cell == "S2" and any(x != 0 for x in row["over_users_resp"] + row["over_users_db"]):
        out.append(f"S2 매수 초과 사용자 응답 {row['over_users_resp']} · 끝 상태 {row['over_users_db']}")
    if row["counter_mismatch_max"] != 0 or row["counter_mismatch_missing"]:
        out.append(f"카운터 불일치 {row['counter_mismatch_max']} (항목 없음 {row['counter_mismatch_missing']}회)")
    if cell == "S7-m1" and any(w != 0 for w in row["wronged_seats"]):
        out.append(f"억울한 좌석 {row['wronged_seats']}")
    if cell == "S7-m1-late":
        if any(v != 0 for v in row["order_violations"]):
            out.append(f"명세 순서 위반 {row['order_violations']}")
        if any(not o for o in row["opportunities"]):
            out.append(f"기회 0 회차 {row['opportunities']}")
    if row["borrow_kind"] != ["request"]:   # ADR-008 요약기는 늘 요청당 빌림을 낸다 — 없으면 옛 요약기로 요약한 것
        out.append(f"요청당 빌림 없음({row['borrow_kind']} — ADR-008 summarize.py로 다시 요약)")
    if any(b is None or not (0.98 <= b <= 1.02) for b in row["borrow"]):   # 모든 셀(리뷰 루프 1 C1) · 요청당(D1)
        out.append(f"빌림 {row['borrow']}")
    if row["applied_limit"] != [EXPECTED_LIMIT]:
        out.append(f"적용 매수 방식 {row['applied_limit']}")
    if row["limit_source"] != ["default"]:
        out.append(f"limit_source {row['limit_source']}")
    return out


def main(argv):
    if not argv or len(argv) not in (1, 3) or (len(argv) == 3 and argv[1] != "--baseline"):
        print(__doc__, file=sys.stderr)
        return 2
    root = Path(argv[0])
    base = Path(argv[2]) if len(argv) == 3 else DEFAULT_BASELINE
    if not root.is_dir() or not base.is_dir():   # 리뷰 루프 1 C3 — 경로가 없으면 비교 자체를 하지 않는다
        print(f"경로 없음: {root if not root.is_dir() else base}", file=sys.stderr)
        return 2
    out = [f"# ADR-008 확정 측정 비교 — `{root.name}` (기본값 기동) vs ADR-007 `{base.name}` precheck-default(방식 명시)\n",
           "> `k6/ADR-008/scripts/compare.py`가 두 캠페인의 조건별 `summary.json`에서 산출. 값 = 중앙값 [회차별]. 판정 기준은 머리말(명세 §5).\n"]
    js = {"campaign": root.name, "baseline": str(base), "cells": {}}
    problems, magnitude_diff = [], []
    out.append("| 셀 | n(ok/계획) | 매수 초과(최대) | 카운터 불일치(최대) | 억울한 좌석 | 순서 위반 / 기회 | 요청당 빌림 | 적용 매수 방식 | 기준 n · 매수 초과 · 불일치 · 빌림(옛 요약은 선점당) | 문제 |")
    out.append("|---|---|---|---|---|---|---|---|---|---|")
    for tag, cell, plan_reps in CELLS:
        all_reps = reps_of(root, f"default-{tag}", cell)
        if all_reps is None:
            problems.append(f"{cell}: ADR-008 summary.json 없음(default-{tag} — summarize.py 먼저)")
            out.append(f"| {cell} | 미측정 | | | | | | | | summary 없음 |")
            continue
        reps = used(cell, all_reps)
        b_all = reps_of(base, f"counter-upsert-precheck-default-{tag}", cell)
        if b_all is None:
            problems.append(f"{cell}: 기준 summary.json 없음({base.name}/counter-upsert-precheck-default-{tag})")
            b_all = []
        b_reps = used(cell, b_all)
        if not b_all:
            problems.append(f"{cell}: 기준 회차 0")
        elif len(b_reps) < (1 if cell == "S4" else 3):
            problems.append(f"{cell}: 기준 ok 회차 {len(b_reps)} < 계획 {1 if cell == 'S4' else 3}")
        row, brow = accept_row(cell, reps), accept_row(cell, b_reps)
        pr = problems_of(cell, plan_reps, all_reps, reps, row)
        problems += [f"{cell}: {p}" for p in pr]
        wr = row.get("wronged_seats", "-")
        ov = f"{row.get('order_violations', '-')} / {row.get('opportunities', '-')}" if cell == "S7-m1-late" else "-"
        out.append(f"| {cell} | {len(reps)}/{plan_reps} | {row['over_limit_max']} | {row['counter_mismatch_max']} | {wr} | {ov} | "
                   f"{', '.join(fmt(b, 4) for b in row['borrow'])} | {', '.join(row['applied_limit'])} | "
                   f"{len(b_reps)} · {brow['over_limit_max']} · {brow['counter_mismatch_max']} · {', '.join(fmt(b, 4) for b in brow['borrow'])} | "
                   f"{'; '.join(pr) or '없음'} |")
        mags = []
        for key, label in MAGNITUDE.get(cell, []):
            a, b = med([val(r, key) for r in reps]), med([val(r, key) for r in b_reps])
            same = digits(a) is not None and digits(a) == digits(b)
            ratio = (a / b) if a is not None and b else None
            within = ratio is not None and 0.5 <= ratio <= 2
            mags.append({"metric": key, "label": label, "adr008": a, "adr007": b, "adr008_reps": [val(r, key) for r in reps],
                         "ratio": ratio, "within_half_double": within, "same_digits": same})
            if not within:
                magnitude_diff.append(f"{cell} {label}: ADR-008 {fmt(a)} vs ADR-007 {fmt(b)} (비율 {fmt(ratio, 2)} — 0.5~2배 밖)")
        js["cells"][cell] = {"accept": row, "baseline_accept": brow, "problems": pr, "magnitude": mags,
                             "statuses": [r.get("status") for r in all_reps]}
    out.append("\n## 같은 크기(명세 §5 '같은 자릿수' — 규칙: 중앙값 비율 0.5~2배, 판정 아님·원인 기록 대상. 자릿수 일치는 참고)\n")
    out.append("| 셀 | 지표 | ADR-008 중앙값 [회차] | ADR-007 precheck 중앙값 | 비율 | 비율 0.5~2배 | 같은 자릿수(참고) |")
    out.append("|---|---|---|---|---|---|---|")
    for cell, c in js["cells"].items():
        for m in c["magnitude"]:
            out.append(f"| {cell} | {m['label']} | {fmt(m['adr008'])} [{', '.join(fmt(x) for x in m['adr008_reps'])}] | {fmt(m['adr007'])} | "
                       f"{fmt(m['ratio'], 2)} | {'예' if m['within_half_double'] else '**아니오**'} | {'예' if m['same_digits'] else '아니오'} |")
    out.append(f"\n## 문제 {len(problems)}건\n")
    out += [f"- {p}" for p in problems] or ["- 없음"]
    out.append(f"\n## 크기 다름(비율 0.5~2배 밖) {len(magnitude_diff)}건(문제 아님 — 원인 기록 대상)\n")
    out += [f"- {p}" for p in magnitude_diff] or ["- 없음"]
    js.update(problems=problems, magnitude_diff=magnitude_diff)
    (root / "COMPARISON.md").write_text("\n".join(out) + "\n")
    (root / "comparison.json").write_text(json.dumps(js, ensure_ascii=False, indent=1, default=str))
    print(root / "COMPARISON.md")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
