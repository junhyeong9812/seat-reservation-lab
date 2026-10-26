#!/usr/bin/env python3
"""ADR-014 앱 지표 스냅숏 — 만료 방식의 측정 표면(측정-3.6 '새로(task 04 — 앱)' 행 · 측정-5 M3 · M4 · M10 · M11 · M13)을 actuator에서 읽어 한 줄 JSON으로 낸다
(run.sh — k6 직전 · 직후 · 회차 끝. 차분은 요약기). 카운터 어긋남·틱·증분·안전망 묶음은 ADR-009 actsnap.py가 그대로 맡는다(같은 시점에 둘 다 찍는다).

    scripts/actsnap14.py <base_url>

값 규칙(ADR-009 actsnap.py와 같다): 지표 목록에 이름이 없으면 0(아직 기록 없음 — Micrometer는 처음 기록될 때 만든다) · 목록에는 있는데 태그 값이 없으면 0 ·
조회 자체가 실패하면 null(미측정 — 0으로 채우지 않는다). 지표 이름의 출처(main-app 코드 상수):
  seat.hold.rejected{cause}               ApiExceptionHandler.REJECTED_METRIC(rule | nowait — 측정 프로필 선점 409 원인 · 리뷰 MR-5)
  seat.hold.reclaim(타이머)                LazyHoldProcess.RECLAIM_METRIC(H3~H5 회수 구간)
  seat.hold.expiry.deleted{path}          LazyHoldProcess.DELETED_METRIC(reclaim | batch — 지운 홀드 행 수)
  seat.hold.expiry.missing_pairs{path}    LazyHoldProcess.MISSING_METRIC(쿼터 행 없는 쌍 — MISSING 복구)
  seat.hold.expiry.skip_locked{stage}     LazyHoldProcess.SKIP_LOCKED_METRIC(c2 — C 배치 좌석 · h4b — C 회수의 다른 좌석)
  seat.hold.expiry.revert{path,result}    LazyHoldProcess.REVERT_METRIC(reverted · kept_active = 되돌림 기회 — P5의 판정 불가 조건)
  seat.hold.expiry.chunk{outcome}         LazyExpiryBatch.CHUNK_METRIC(C·D 청크 ok | failed)
  seat.hold.expiry.missing_mismatch       LazyExpiryBatch.MISSING_MISMATCH_METRIC(:missingIds 불일치 롤백 — M11)
  seat.hold.expiry.tick{outcome}          ExpireHoldsService.TICK_METRIC(ok | failed — 모든 방식)
  seat.hold.counter.cleanup.expired_holds_deleted · expired_holds_skipped   QuotaCleanup(B 정리 ① — 마감 회차만이라 이 측정에서는 0 기대)
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BASE = sys.argv[1].rstrip("/") if len(sys.argv) == 2 else sys.exit("usage: actsnap14.py <base_url>")


def get(path):
    for i in range(6):
        try:
            with urllib.request.urlopen(f"{BASE}/actuator/{path}", timeout=10) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return 404
        except (OSError, ValueError):
            pass
        time.sleep(min(10, 2 * (i + 1)))
    return None


listing = get("metrics")
if not isinstance(listing, dict) or not isinstance(listing.get("names"), list):
    print(json.dumps({"t": datetime.now(timezone.utc).isoformat(), "error": "metrics-list-failed"}))
    sys.exit(1)
NAMES = set(listing["names"])
FAILED = []


def stats(name, tags=()):
    """{statistic: value} · {}(이름·태그 값 없음 = 0) · None(조회 실패). tags = [("k", "v"), …] — 여러 태그를 함께 건다."""
    if name not in NAMES:
        return {}
    q = "&".join("tag=" + urllib.parse.quote(f"{k}:{v}", safe=":") for k, v in tags)
    base = get(f"metrics/{name}" + (f"?{q}" if q else ""))
    if base == 404:
        return {}
    if not isinstance(base, dict):
        FAILED.append(name)
        return None
    return {m["statistic"]: m["value"] for m in base.get("measurements") or []}


def count(name, *tags):
    s = stats(name, tags)
    return None if s is None else s.get("COUNT", 0)


def timer(name, *tags):
    s = stats(name, tags)
    return None if s is None else {"COUNT": s.get("COUNT", 0), "TOTAL_TIME": s.get("TOTAL_TIME", 0), "MAX": s.get("MAX", 0)}


X = "seat.hold.expiry"
out = {
    "t": datetime.now(timezone.utc).isoformat(),
    "base": BASE,
    "rejected": {c: count("seat.hold.rejected", ("cause", c)) for c in ("rule", "nowait")},
    "reclaim": timer("seat.hold.reclaim"),
    "deleted": {p: count(f"{X}.deleted", ("path", p)) for p in ("reclaim", "batch")},
    "missing_pairs": {p: count(f"{X}.missing_pairs", ("path", p)) for p in ("reclaim", "batch")},
    "skip_locked": {s: count(f"{X}.skip_locked", ("stage", s)) for s in ("c2", "h4b")},
    "revert": {f"{p}.{r}": count(f"{X}.revert", ("path", p), ("result", r)) for p in ("reclaim", "batch") for r in ("reverted", "kept_active")},
    "chunk": {o: count(f"{X}.chunk", ("outcome", o)) for o in ("ok", "failed")},
    "missing_mismatch": count(f"{X}.missing_mismatch"),
    "tick": {o: count(f"{X}.tick", ("outcome", o)) for o in ("ok", "failed")},
    "cleanup_expired": {"deleted": count("seat.hold.counter.cleanup.expired_holds_deleted"),
                        "skipped": count("seat.hold.counter.cleanup.expired_holds_skipped")},
    "names_present": sorted(n for n in NAMES if n.startswith(("seat.hold.rejected", "seat.hold.reclaim", X))),
}
if FAILED:
    out["failed"] = sorted(set(FAILED))
print(json.dumps(out, ensure_ascii=False))
