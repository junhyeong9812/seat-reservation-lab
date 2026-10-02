#!/usr/bin/env python3
"""ADR-002 환경 동일성 확인 — 인덱스 없음·풀 10의 S4를 다시 잰 값(c00)이 ADR-001 범위에 드는지 판정한다.

    scripts/envcheck.py <c00 결과 폴더> <ADR-001 summary.json>   → exit 0 = 범위 안(ADR-001 재사용 가능), 1 = 벗어남

판정 지표: 단계(L2·L4)마다 S4 엄격 한계와 포화점.
통과 조건: 계획한 회차가 모두 정상(ok)이고, 각 회차의 값이 모두 ADR-001 5회의 [최소, 최대] 안에 있어야 한다
(중앙값 비교는 범위 밖 회차를 숨길 수 있어 쓰지 않는다).
결과는 <c00>/envcheck.json 에 남긴다.
"""
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import summarize  # noqa: E402  (같은 요약 규칙으로 산출 — 판정 기준을 두 곳에 두지 않는다)


def limits(recs):
    return {k: [r["limits"][k] for r in recs if r.get("status") == "ok" and "limits" in r] for k in ("strict", "saturation")}


def main(c00_dir, adr001_summary):
    c00 = Path(c00_dir)
    plan = json.loads((c00 / "plan.json").read_text())
    base = json.loads(Path(adr001_summary).read_text())
    verdict = {"passed": True, "levels": {}}
    for level in plan["levels"]:
        reps = [summarize.rep_record(d, c00, level, "S4") for d in sorted((c00 / f"L{level}" / "S4").glob("rep[0-9]*"))]
        ok_reps = [r for r in reps if r.get("status") == "ok"]
        lv = {"planned_reps": plan["reps"], "ok_reps": len(ok_reps),
              "not_ok": [f'{r["rep"]}:{r.get("status")}' for r in reps if r.get("status") != "ok"]}
        complete = len(ok_reps) == plan["reps"]
        lv["complete"] = complete
        verdict["passed"] &= complete
        now = limits(ok_reps)
        ref = limits(base.get(f"L{level}/S4", []))
        for k in ("strict", "saturation"):
            if not now[k] or not ref[k]:
                lv[k] = {"passed": False, "reason": "값 없음", "now": now[k], "ref": ref[k]}
            else:
                lo, hi = min(ref[k]), max(ref[k])
                ok = all(lo <= x <= hi for x in now[k])
                lv[k] = {"passed": ok, "now": now[k], "now_median": statistics.median(now[k]), "ref_min": lo, "ref_max": hi}
            verdict["passed"] &= lv[k]["passed"]
        verdict["levels"][f"L{level}"] = lv
    (c00 / "envcheck.json").write_text(json.dumps(verdict, ensure_ascii=False, indent=1))
    print(json.dumps(verdict, ensure_ascii=False))
    return 0 if verdict["passed"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
