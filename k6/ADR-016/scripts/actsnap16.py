#!/usr/bin/env python3
"""ADR-016 앱 지표 스냅숏(측정 명세 §1-3 G-정합 서버 교차 · N16-5) — actuator에서 ADR-016 이름을 읽어 한 줄 JSON(run16.sh — k6 직전 · 판정 창 끝 · 직후 · 회차 끝. 차분은 요약기).
actsnap15.py는 seat.confirm.extend만 읽고 아래 셋(pay_start · rejected · failed)을 모른다 → 이 파일을 새로 둔다. 조회 · 값 규칙은 ADR-014 actsnap14.py 그대로
(그 파일의 get · count를 쓴다 — 이름이 목록에 없으면 0 · 조회 실패는 null · 0으로 채우지 않는다). 지표 이름의 출처(main-app 코드 상수):
  seat.confirm.pay_start{result}   PaymentStartService.PAY_START_METRIC(ok · insufficient · expired · not_found · not_owned — ① 결제 시작 확인)
  seat.confirm.extend{result}      PaymentStartService.EXTEND_METRIC(extended · already · not_needed · too_late — ④)
  seat.confirm.rejected{code}      ConfirmOutcomeMetrics.REJECTED_METRIC(확정 도메인 거부 — 코드 값은 actuator availableTags에서 읽는다)
  seat.confirm.failed{status}      ConfirmOutcomeMetrics.FAILED_METRIC(그 밖 비2xx — 상태 값은 availableTags에서)

    scripts/actsnap16.py <base_url>
"""
import importlib.util
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

if len(sys.argv) != 2:
    sys.exit("usage: actsnap16.py <base_url>")
_p = Path(__file__).resolve().parent.parent.parent / "ADR-014" / "scripts" / "actsnap14.py"
_spec = importlib.util.spec_from_file_location("actsnap14", _p)
_m = importlib.util.module_from_spec(_spec)
_saved = sys.stdout
try:
    sys.stdout = open("/dev/null", "w")   # actsnap14는 적재 끝에 자기 JSON을 찍는다 — 버린다(actsnap15.py와 같은 처리)
    _spec.loader.exec_module(_m)
except SystemExit as e:
    sys.stdout = _saved
    print(json.dumps({"t": datetime.now(timezone.utc).isoformat(), "error": "metrics-list-failed"}))
    sys.exit(e.code or 1)
finally:
    if sys.stdout is not _saved:
        sys.stdout.close()
        sys.stdout = _saved
get, count, NAMES, FAILED = _m.get, _m.count, _m.NAMES, _m.FAILED
FAILED.clear()


def tag_values(name, key):
    """availableTags의 key 값 목록 · [](이름 없음) · None(조회 실패)."""
    if name not in NAMES:
        return []
    base = get(f"metrics/{name}")
    if base == 404:
        return []
    if not isinstance(base, dict):
        FAILED.append(name)
        return None
    for t in base.get("availableTags") or []:
        if t.get("tag") == key:
            return sorted(t.get("values") or [])
    return []


def by_tag(name, key):
    vals = tag_values(name, key)
    if vals is None:
        return None
    out = {v: count(name, (key, v)) for v in vals}
    return None if any(v is None for v in out.values()) else out


C = "seat.confirm"
out = {
    "t": datetime.now(timezone.utc).isoformat(),
    "base": _m.BASE,
    "pay_start": {r: count(f"{C}.pay_start", ("result", r)) for r in ("ok", "insufficient", "expired", "not_found", "not_owned")},
    "extend": {r: count(f"{C}.extend", ("result", r)) for r in ("extended", "already", "not_needed", "too_late")},
    "rejected": by_tag(f"{C}.rejected", "code"),
    "failed": by_tag(f"{C}.failed", "status"),
    "names_present": sorted(n for n in NAMES if n.startswith(C)),
}
out["t_end"] = datetime.now(timezone.utc).isoformat()
if FAILED:
    out["failed_names"] = sorted(set(FAILED))
print(json.dumps(out, ensure_ascii=False))
