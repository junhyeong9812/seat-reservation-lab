#!/usr/bin/env python3
"""ADR-018 앱 지표 스냅숏(계약 INTERFACE.md §6.4) — actuator에서 ADR-018 이름을 읽어 한 줄 JSON(run18.sh — k6 직전 · k6 직후 · 배수 뒤 덤프 뒤).
조회 · 값 규칙은 ADR-014 actsnap14.py 그대로(그 파일의 stats · count를 쓴다 — 이름이 목록에 없으면 0 · 조회 실패는 null · 0으로 채우지 않는다).
지표 이름(main-app 상수 · 지표 계약 docs/plans/2026-11-20/adr-018-measurement/test-contract.md):
  seat.group.request{op,outcome} · seat.group.hold.rejected{mode,cause} · seat.db.deadlock{path} · seat.hold.rejected{cause}(1석 — ADR-014) ·
  seat.hold.expiry.tick{outcome} · seat.compensation.done{type,result} · .retry{type} · .dead{type} · .unresolved · .record_failed · .sweep_failed · process.start.time.

    scripts/actsnap18.py <base_url>
"""
import importlib.util
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

if len(sys.argv) != 2:
    sys.exit("usage: actsnap18.py <base_url>")
_p = Path(__file__).resolve().parent.parent.parent / "ADR-014" / "scripts" / "actsnap14.py"
_spec = importlib.util.spec_from_file_location("actsnap14", _p)
_m = importlib.util.module_from_spec(_spec)
_saved = sys.stdout
try:
    sys.stdout = open("/dev/null", "w")   # actsnap14는 적재 끝에 자기 JSON을 찍는다 — 버린다(actsnap17.py와 같은 처리)
    _spec.loader.exec_module(_m)
except SystemExit as e:
    sys.stdout = _saved
    print(json.dumps({"t": datetime.now(timezone.utc).isoformat(), "error": "metrics-list-failed"}))
    sys.exit(e.code or 1)
finally:
    if sys.stdout is not _saved:
        sys.stdout.close()
        sys.stdout = _saved
get, stats, count, NAMES, FAILED = _m.get, _m.stats, _m.count, _m.NAMES, _m.FAILED
FAILED.clear()
T0 = datetime.now(timezone.utc).isoformat()


def tag_values(name, key):
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


def by_tags(name, keys):
    """태그 조합별 COUNT — keys 하나면 {값: n}, 둘이면 {"a:b": n}(0인 조합은 뺀다). 조회 실패면 None(actsnap17 by_tags와 같다)."""
    vals = [tag_values(name, k) for k in keys]
    if any(v is None for v in vals):
        return None
    out = {}
    if len(keys) == 1:
        for v in vals[0]:
            out[v] = count(name, (keys[0], v))
    else:
        for a in vals[0]:
            for b in vals[1]:
                n = count(name, (keys[0], a), (keys[1], b))
                if n:
                    out[f"{a}:{b}"] = n
                elif n is None:
                    out[f"{a}:{b}"] = None
    return None if any(v is None for v in out.values()) else out


def gauge(name):
    s = stats(name)
    return None if s is None else s.get("VALUE", 0)


C = "seat.compensation"
out = {
    "t": T0,
    "base": _m.BASE,
    "process_start_s": gauge("process.start.time"),
    "group_request": by_tags("seat.group.request", ("op", "outcome")),
    "group_rejected": by_tags("seat.group.hold.rejected", ("mode", "cause")),
    "hold_rejected": by_tags("seat.hold.rejected", ("cause",)),
    "deadlock": by_tags("seat.db.deadlock", ("path",)),
    "expiry_tick": by_tags("seat.hold.expiry.tick", ("outcome",)),
    "compensation": {
        "done": by_tags(f"{C}.done", ("type", "result")), "retry": by_tags(f"{C}.retry", ("type",)), "dead": by_tags(f"{C}.dead", ("type",)),
        "unresolved": count(f"{C}.unresolved"), "record_failed": count(f"{C}.record_failed"), "sweep_failed": count(f"{C}.sweep_failed"),
    },
}
if out["process_start_s"] == 0:   # 이름이 없으면(지표 미등록) 0이 아니라 null — 재기동 탐지에 0을 쓰지 않는다
    out["process_start_s"] = None
out["t_end"] = datetime.now(timezone.utc).isoformat()
if FAILED:
    out["failed_names"] = sorted(set(FAILED))
print(json.dumps(out, ensure_ascii=False))
