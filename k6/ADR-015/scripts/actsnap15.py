#!/usr/bin/env python3
"""ADR-015 앱 지표 스냅숏 — 확정 원자성의 측정 표면을 actuator에서 읽어 한 줄 JSON으로 낸다(run.sh — k6 직전 · 판정 창 끝 · 직후 · 회차 끝. 차분은 요약기).
조회 · 값 규칙은 ADR-014 actsnap14.py와 같다(그 파일의 get · stats를 그대로 쓴다 — 이름이 목록에 없으면 0 · 조회 실패는 null · 0으로 채우지 않는다).
ADR-014 스냅숏(actsnap14)도 같은 시점에 함께 찍는다(run.sh) — 거기 없는 ADR-015 이름만 여기서 읽는다. 지표 이름의 출처(main-app 코드 상수):
  seat.confirm.unique_violation                       ConfirmReservationService.UNIQUE_VIOLATION_METRIC(태그 없음 — 설계-10 T4)
  seat.confirm.unexpected                             AtomicConfirmProcess.UNEXPECTED_METRIC(진단 '설계상 생기지 않는다' 갈래 · PaymentStartService도 같은 이름)
  seat.confirm.converted_after_expiry{clock}          ConfirmReservationService.CONVERTED_AFTER_EXPIRY_METRIC(db | app — 커밋 뒤)
  seat.confirm.converted_after_expiry.lateness{clock} 같은 곳 …_LATENESS_METRIC(Timer — 건수 · 합 · 최대)
  seat.confirm.extend{result}                         PaymentStartService.EXTEND_METRIC(extended · already · not_needed · too_late)
  seat.confirm.extension_lost                         SweepConditionalBatch.EXTENSION_LOST_METRIC(A′에서 구조적 0)
  seat.hold.expiry.deleted{path=sweep, extended}      SweepConditionalBatch(A′만 — JPA 배치(current)는 이 지표를 내지 않는다: 태그 키 차이 메모 · 초안 측정-5)
  seat.hold.expiry.missing_pairs{path=sweep}          A′ B2b — MISSING 쌍마다 1(= B2b SELECT 문장 수 · #15)
  seat.hold.expiry.revert{path=sweep, result}         A′ B5(reverted · not_held · kept_active)
  seat.hold.expiry.missing_mismatch · tick{outcome}   LazyExpiryBatch · ExpireHoldsService(A′ B3b 불일치 · 틱 ok | failed — current FM3 틱 롤백도 failed)
  http.server.requests{outcome=SERVER_ERROR}          Spring 기본(서버 쪽 5xx — k6 5xx와 교차 확인)

    scripts/actsnap15.py <base_url>
"""
import importlib.util
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

if len(sys.argv) != 2:
    sys.exit("usage: actsnap15.py <base_url>")
# actsnap14는 모듈 적재 때 지표 목록을 읽는다(BASE = argv[1]) — 같은 인자로 적재해 get · stats · NAMES를 그대로 쓴다
_p = Path(__file__).resolve().parent.parent.parent / "ADR-014" / "scripts" / "actsnap14.py"
_spec = importlib.util.spec_from_file_location("actsnap14", _p)
_m = importlib.util.module_from_spec(_spec)
_saved = sys.stdout
try:
    sys.stdout = open("/dev/null", "w")   # actsnap14는 끝에 자기 JSON을 찍는다 — 여기서는 버린다(이 스냅숏은 run.sh가 따로 찍는다)
    _spec.loader.exec_module(_m)
except SystemExit as e:   # 지표 목록 실패(actsnap14가 exit 1)
    sys.stdout = _saved
    print(json.dumps({"t": datetime.now(timezone.utc).isoformat(), "error": "metrics-list-failed"}))
    sys.exit(e.code or 1)
finally:
    if sys.stdout is not _saved:
        sys.stdout.close()
        sys.stdout = _saved
count, timer, NAMES, FAILED = _m.count, _m.timer, _m.NAMES, _m.FAILED
FAILED.clear()

C, X = "seat.confirm", "seat.hold.expiry"
out = {
    "t": datetime.now(timezone.utc).isoformat(),
    "base": _m.BASE,
    "unique_violation": count(f"{C}.unique_violation"),
    "unexpected": count(f"{C}.unexpected"),
    "converted_after_expiry": {k: count(f"{C}.converted_after_expiry", ("clock", k)) for k in ("db", "app")},
    "lateness": {k: timer(f"{C}.converted_after_expiry.lateness", ("clock", k)) for k in ("db", "app")},
    "extend": {r: count(f"{C}.extend", ("result", r)) for r in ("extended", "already", "not_needed", "too_late")},
    "extension_lost": count(f"{C}.extension_lost"),
    "deleted_sweep": {e: count(f"{X}.deleted", ("path", "sweep"), ("extended", e)) for e in ("true", "false")},
    "deleted_other": {p: count(f"{X}.deleted", ("path", p)) for p in ("reclaim", "batch")},
    "missing_pairs_sweep": count(f"{X}.missing_pairs", ("path", "sweep")),
    "revert_sweep": {r: count(f"{X}.revert", ("path", "sweep"), ("result", r)) for r in ("reverted", "not_held", "kept_active")},
    "missing_mismatch": count(f"{X}.missing_mismatch"),
    "tick": {o: count(f"{X}.tick", ("outcome", o)) for o in ("ok", "failed")},
    "server_5xx": count("http.server.requests", ("outcome", "SERVER_ERROR")),
    "names_present": sorted(n for n in NAMES if n.startswith((C, X))),
}
out["t_end"] = datetime.now(timezone.utc).isoformat()
if FAILED:
    out["failed"] = sorted(set(FAILED))
print(json.dumps(out, ensure_ascii=False))
