#!/usr/bin/env python3
"""ADR-005 캠페인의 S4 결과를 ADR-006 한계 규칙(summarize.py 머리말 'S4 한계 규칙')으로 다시 계산한다 — 원본은 읽기만 한다.

    scripts/recompute_s4.py <ADR-005 캠페인 폴더> <출력 폴더>     → <출력>/S4-RECOMPUTED.md · s4-recomputed.json

회차 포함 규칙 = compare.py S4와 같다: status `ok` + `s4-no-successful-stage`가 든 회차(그 밖 비정상은 '제외'로 표시).
회차마다 원시 k6-summary.json·meta.json에서 단계 표(summarize.stage_table)를 다시 만들고
  - 새 엄격 한계(strict)·멈춘 단계와 사유 · 포화점(최대 성공 RPS)·포화 단계(첫 목표 미달)
  - 옛 엄격 한계 = ADR-005 규칙(strict_legacy)을 다시 계산한 값, 그리고 ADR-005 summary.json에 기록된 값
를 나란히 적는다. 둘(옛 규칙 재계산 vs 기록값)이 다르면 재계산 경로가 원본과 어긋난 것 — 표에 '기록과 불일치'로 드러내고 exit 1.
계단 상한(≥) 표시는 compare.py censored와 같은 뜻: k6 정상 종료(exit 0)·partial 없음·새 규칙에서 멈춘 단계 없음.
"""
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from summarize import load, s4_limits, stage_table  # noqa: E402

RESULT_FAIL = "s4-no-successful-stage"


def fmt(v):
    return "-" if v is None else f"{v:,.0f}"


def stop_txt(st):
    return "-(끝까지 통과)" if not st else f'{st["stage"]}({st["target_rps"]:,}/s):{"+".join(st["reasons"])}'


def main(src, out):
    src, out = Path(src), Path(out)
    out.mkdir(parents=True, exist_ok=True)
    rows, js, problems = [], {}, []
    conds = sorted(d for d in src.iterdir() if (d / "plan.json").exists() and "S4" in json.loads((d / "plan.json").read_text()).get("cells", []))
    for cond in conds:
        plan = json.loads((cond / "plan.json").read_text())
        recorded = load(cond / "summary.json") or {}
        for lv in plan["levels"]:
            key = f"L{lv}/S4"
            rec_by_rep = {r["rep"]: r for r in recorded.get(key, [])}
            for k in range(1, plan["reps"] + 1):
                rd = cond / f"L{lv}" / "S4" / f"rep{k}"
                status = (rd / "status").read_text().strip() if (rd / "status").exists() else "미측정"
                meta = load(rd / "meta.json") or {}
                summ = load(rd / "k6-summary.json")
                row = {"cond": cond.name, "limit_strategy": plan["condition"].get("limit_strategy"), "level": f"L{lv}", "rep": f"rep{k}",
                       "status": status, "k6_started": meta.get("k6_started")}
                used = status == "ok" or RESULT_FAIL in status
                if not used or not summ:
                    row["excluded"] = True
                    rows.append(row)
                    continue
                stages = stage_table(summ, aborted=meta.get("k6_exit") == 99)
                lim = s4_limits(stages)
                old_rec = ((rec_by_rep.get(f"rep{k}") or {}).get("limits") or {}).get("strict")
                censored = meta.get("k6_exit") == 0 and not any(s["partial"] for s in stages) and lim["strict_stop"] is None
                row.update(new_strict=lim["strict"], strict_stop=lim["strict_stop"], old_strict=lim["strict_legacy"], old_recorded=old_rec,
                           new_loose=lim["loose"], old_loose=lim["loose_legacy"], saturation=lim["saturation"],
                           saturation_stage=lim["saturation_stage"], censored=censored, changed=lim["strict"] != lim["strict_legacy"])
                # ADR-005 요약기는 ok 회차만 기록한다 — 기록값이 있는 회차만 대조
                if old_rec is not None and abs(old_rec - lim["strict_legacy"]) > 1e-9:
                    row["recorded_mismatch"] = True
                    problems.append(f"{cond.name}/{key}/rep{k}: 옛 규칙 재계산 {lim['strict_legacy']} ≠ 기록 {old_rec}")
                rows.append(row)
    js["rows"] = rows

    lines = [f"# ADR-005 S4 재계산 — 새 한계 규칙(ADR-006) vs 옛 규칙(ADR-005) · 원본 `{src.name}`", "",
             "> `k6/ADR-006/scripts/recompute_s4.py`가 ADR-005 원시 결과(k6-summary.json·meta.json·status)에서 산출 — 원본은 읽기만 했다. "
             "새 규칙 = 기준 위반 **또는 목표 미달(실제 도착 < 0.9 × 목표)** 이 처음 나온 단계에서 멈추고 그 직전 단계의 성공 RPS(`summarize.py` 머리말). "
             "옛 규칙 = 목표 미달을 보지 않음(ADR-005). 포화점 = 완전한 단계 중 최대 성공 RPS · 포화 단계 = 첫 목표 미달 단계. "
             "≥ = 계단 끝까지 통과(계단 상한 — 진짜 한계 미관측). 값 단위 건/s.", ""]

    # 조건 × 단계 요약
    lines += ["## 조건 × 단계 요약 (포함 회차 중앙값 [최소–최대])", "",
              "| 조건 | 단계 | n | 새 엄격 한계 | 옛 엄격 한계(재계산) | 새 완화 한계 | 옛 완화 한계 | 포화점 | 새≠옛 회차 수 | 제외 회차 |",
              "|---|---|---|---|---|---|---|---|---|---|"]

    def sp(vals):
        v = [x for x in vals if x is not None]
        if not v:
            return "미측정"
        return f"{statistics.median(v):,.0f}" + ("" if len(v) == 1 else f" [{min(v):,.0f}–{max(v):,.0f}]")
    groups = {}
    for r in rows:
        groups.setdefault((r["cond"], r["level"]), []).append(r)
    for (cond, lv), rs in groups.items():
        used = [r for r in rs if not r.get("excluded")]
        excl = [f'{r["rep"]}:{r["status"]}' for r in rs if r.get("excluded")]
        lines.append(f"| {cond} | {lv} | {len(used)}/{len(rs)} | {sp([r['new_strict'] for r in used])} | {sp([r['old_strict'] for r in used])} | "
                     f"{sp([r['new_loose'] for r in used])} | {sp([r['old_loose'] for r in used])} | {sp([r['saturation'] for r in used])} | "
                     f"{sum(1 for r in used if r['changed'])} | {', '.join(excl) or '-'} |")

    lines += ["", "## 회차별 — 새 vs 옛 엄격 한계 · 멈춘 단계(단계 번호(목표/s):사유) · 포화 단계", "",
              "| 조건 | 단계 | 회차 | k6 시작 | status | 새 엄격 | 멈춘 단계(새 규칙) | 옛 엄격(재계산) | 옛 엄격(ADR-005 기록) | 포화점 | 포화 단계(목표/s→성공) | 달라짐 |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        if r.get("excluded"):
            lines.append(f"| {r['cond']} | {r['level']} | {r['rep']} | {r.get('k6_started') or '-'} | {r['status']} | 제외 | | | | | | |")
            continue
        ss = r["saturation_stage"]
        sat = "-(목표 미달 없음)" if not ss else f'{ss["stage"]}({ss["target_rps"]:,}/s→{ss["ok_rps"]:,.0f})'
        mark = "≥" if r["censored"] else ""
        lines.append(f"| {r['cond']} | {r['level']} | {r['rep']} | {r['k6_started']} | {r['status']} | {mark}{fmt(r['new_strict'])} | {stop_txt(r['strict_stop'])} | "
                     f"{fmt(r['old_strict'])} | {fmt(r['old_recorded'])}{' **기록과 불일치**' if r.get('recorded_mismatch') else ''} | {fmt(r['saturation'])} | {sat} | "
                     f"{'**예**' if r['changed'] else '-'} |")
    changed = [r for r in rows if r.get("changed")]
    lines += ["", f"## 새 규칙에서 값이 바뀐 회차 — {len(changed)}회", ""]
    for r in changed:
        lines.append(f"- {r['cond']} {r['level']} {r['rep']}: 옛 {fmt(r['old_strict'])} → 새 {fmt(r['new_strict'])} "
                     f"(멈춘 단계 {stop_txt(r['strict_stop'])} · 포화점 {fmt(r['saturation'])})")
    if problems:
        lines += ["", "## 재계산 경로 문제", ""] + [f"- {p}" for p in problems]
    (out / "S4-RECOMPUTED.md").write_text("\n".join(lines) + "\n")
    (out / "s4-recomputed.json").write_text(json.dumps(js, ensure_ascii=False, indent=1))
    print(out / "S4-RECOMPUTED.md")
    if problems:
        print("문제:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
