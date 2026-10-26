#!/usr/bin/env python3
"""ADR-009 앱 지표 스냅숏 — 카운터 어긋남·만료 틱·대조·정리·마감 판정 지표를 actuator에서 읽어 한 줄 JSON으로 낸다(run.sh drift_snap — k6 직전·직후·회차 끝).

    scripts/actsnap.py <base_url>      예: http://192.168.55.164:8101 (업무 앱) · :8102 (F 대조 프로세스)

값 규칙(ADR-008 uri_requests와 같다): 지표 목록에 이름이 없으면 0(아직 기록 없음 — Micrometer는 처음 기록될 때 만든다),
목록에는 있는데 태그 값이 없으면 0, 조회 자체가 실패하면 null(미측정 — 0으로 채우지 않는다: 차분이 조용히 틀어지지 않게).
지표 정의는 앱 코드: ExpireHoldsService.TICK_METRIC · CounterDriftRecorder(DRIFT_METRIC·RECORD_FAILED_METRIC) · CounterReconciler(BORROWS·EXCLUDED·ERRORS·SKIPPED_CHUNKS·
chunk·orphan 타이머) · QuotaCleanup(BORROWS·DELETED·ERRORS) · SalesCloseGate(seat.hold.sales-close.check 타이머).
주의: reconcile.excluded{unit=pairs}는 바퀴마다 마감 회차의 쿼터 행 수(skipped_pairs)를 더하고, 복구 트랜잭션의 마감 제외(skipped reason=closed)도 1씩 더한다 —
'마감 제외 쌍 수'가 아니라 누적 계수다(마감 제외는 DB counter_drift_log reason=closed·counter_reconcile_run.skipped_closed로 센다 — 요약기 effect).
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BASE = sys.argv[1].rstrip("/") if len(sys.argv) == 2 else sys.exit("usage: actsnap.py <base_url>")


def get(path):
    """JSON · 404(이름·태그 없음) · None(실패 — 6번 재시도: 과부하 직후 늦은 응답)."""
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


def stats(name, tag=None):
    """{statistic: value} · 0 표시({} 대신 None 아님 — 아래 pick이 0으로 읽는다) · None(실패)."""
    if name not in NAMES:
        return {}
    base = get(f"metrics/{name}")
    if not isinstance(base, dict):
        return None
    if tag:
        k, v = tag.split(":", 1)
        vals = [x for t in base.get("availableTags") or [] if t.get("tag") == k for x in t.get("values") or []]
        if v not in vals:
            return {}
        base = get(f"metrics/{name}?tag={urllib.parse.quote(tag, safe=':')}")
        if not isinstance(base, dict):
            return None
    return {m["statistic"]: m["value"] for m in base.get("measurements") or []}


def pick(name, tag=None, stat="COUNT"):
    s = stats(name, tag)
    return None if s is None else s.get(stat, 0)


def timer(name, tag=None):
    s = stats(name, tag)
    if s is None:
        return None
    return {"COUNT": s.get("COUNT", 0), "TOTAL_TIME": s.get("TOTAL_TIME", 0), "MAX": s.get("MAX", 0)}


R = "seat.hold.counter.reconcile"
out = {
    "t": datetime.now(timezone.utc).isoformat(),
    "base": BASE,
    "tick": {"ok": pick("seat.hold.expiry.tick", "outcome:ok"), "failed": pick("seat.hold.expiry.tick", "outcome:failed")},
    "drift": {a: pick("seat.hold.counter.drift", f"action:{a}") for a in ("repaired", "false-positive", "skipped")},
    "drift_record_failed": pick("seat.hold.counter.drift.record_failed"),
    "reconcile": {
        "borrows": pick(f"{R}.borrows"), "errors": pick(f"{R}.errors"), "skipped_chunks": pick(f"{R}.skipped_chunks"),
        "excluded_schedules": pick(f"{R}.excluded", "unit:schedules"), "excluded_pairs_counter": pick(f"{R}.excluded", "unit:pairs"),
        "chunk": timer(f"{R}.chunk"), "orphan": timer(f"{R}.orphan"),
    },
    "cleanup": {"borrows": pick("seat.hold.counter.cleanup.borrows"), "deleted": pick("seat.hold.counter.cleanup.deleted"),
                "errors": pick("seat.hold.counter.cleanup.errors")},
    "sales_close_check": timer("seat.hold.sales-close.check"),
    "hikari_acquire": timer("hikaricp.connections.acquire"),
    "process_cpu_usage": pick("process.cpu.usage", stat="VALUE"),
    "process_cpu_time_s": pick("process.cpu.time", stat="VALUE") if "process.cpu.time" in NAMES else None,
}
# ADR-012 2단계(§4.2.6 지표 — 손잡이가 켜질 때만 등록된다): 증분·안전망 이름이 목록에 하나라도 있을 때만 키를 더한다(이전 조건의 스냅숏 모양 그대로).
#   errors = 태그(stage) 합 · self_record = 안전망 자기 행 INSERT 타이머(MAX는 최근 약 2분 창 — R7 허용식의 '자기 기록 INSERT(지표 최대값)')
I, SN = f"{R}.incremental", "seat.hold.counter.safety"
if any(n.startswith(I + ".") for n in NAMES):
    out["incremental"] = {"borrows": pick(f"{I}.borrows"), "errors": pick(f"{I}.errors"), "candidates": pick(f"{I}.candidates"),
                          "window_clamped": pick(f"{I}.window_clamped"), "hidden_sessions": pick(f"{I}.hidden_sessions"),
                          "memory_overflow": pick(f"{I}.memory.overflow"), "memory_requeued": pick(f"{I}.memory.requeued"),
                          "memory_size": pick(f"{I}.memory.size", stat="VALUE"), "tick": timer(f"{I}.tick")}
if any(n.startswith(SN + ".") for n in NAMES):
    out["safety"] = {"borrows": pick(f"{SN}.borrows"), "errors": pick(f"{SN}.errors"), "expedited": pick(f"{SN}.expedited"),
                     "lap_overdue": pick(f"{SN}.lap_overdue"), "carry_dropped": pick(f"{SN}.carry_dropped"),
                     "recent_skipped_ids": pick(f"{SN}.recent.skipped_ids"), "lag_ids": pick(f"{SN}.recent.lag_ids", stat="VALUE"),
                     "run": timer(f"{SN}.run"), "self_record": timer(f"{SN}.self_record")}
print(json.dumps(out, ensure_ascii=False))
