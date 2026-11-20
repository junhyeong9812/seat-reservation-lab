#!/usr/bin/env python3
"""ADR-018 세션 결과 — 단일 정의(측정 명세 M18-1 · M18-3 · 계약 k6/ADR-018/INTERFACE.md §3 · §4 · §5 · §9.1). summarize18 · selftest18이 이 파일을 따른다.

- read_sessions(path)                   = 세션 로그(§3) → (행 목록, 깨진 줄 수) · 파일 없음 = None(outcome17.read_sessions 그대로)
- end_states(db_dump, ledger, amount)   = 배수 뒤 DB 덤프(§6.2) + PG 원장(§6.1) → uid마다 끝 상태(§4 — shape 포함)
- shape(end, amount)                    = §4 표(위에서부터 처음 맞는 줄)
- classify(row, ends, plan)             = §5 결정표 → 범주 하나 · 'invalid:…' · 'fail:…' · 'unexpected:…' · 'integrity:…'
- metric_outcome(o)                     = 응답 객체 → seat.group.request{outcome} 값(§6.4 지표 대조)

    outcome18.py <회차 폴더>   → 표준 출력 JSON 한 줄 {"sessions": [...], "counts": {...}, "uids": {...}} · exit 0 · 입력 없음 = exit 2 {"error": "<사유>"}
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent / "ADR-017" / "scripts"))
from outcome17 import iso_ms, read_sessions  # noqa: E402,F401  — 세션 로그 읽기 · ISO 시각(ADR-017과 같은 규칙)

CATEGORIES = ("success", "released", "abandoned", "lost", "partial_kept", "partial_released", "ps_rejected", "pg_rejected",
              "confirm_rejected", "confirm_mixed", "edge_5xx")
SHAPES = ("double_reservation", "double_refund", "refunded_with_seat", "partial_reservation", "amount_mismatch", "seat_paid", "seat_moneyless",
          "refunded", "closed_unpaid", "orphan", "open_unpaid", "no_ledger", "no_intent")
G1_SHAPES = ("double_reservation", "double_refund", "refunded_with_seat", "partial_reservation", "amount_mismatch", "seat_moneyless", "orphan",
             "open_unpaid", "no_ledger", "no_intent")
CONFIRM_REJECT = frozenset({(404, "HOLD_NOT_FOUND"), (409, "HOLD_EXPIRED"), (409, "SEAT_NOT_HELD")})
PS_REJECT = frozenset({(409, "HOLD_EXPIRED"), (404, "HOLD_NOT_FOUND")})
HOLD_LOST = (409, "SEAT_NOT_AVAILABLE")
HOLD_LIMIT = (409, "HOLD_LIMIT_EXCEEDED")
PG_DEADLINE = (409, "DEADLINE_PASSED")
KINDS = ("ok", "abandon", "release", "edge_confirm", "edge_ps")
ENDS = ("hold", "partial", "abandon", "release", "pay_start", "pg", "done")
ROW_FIELDS = ("k", "v", "sid", "kind", "rep", "method", "cell", "user", "seats", "T", "P", "G", "D", "U", "X",
              "hold", "rel", "ps", "pay", "cf", "got", "end")
GROUP_METHODS = ("split", "one-shot")
# 응답 op → seat.group.request{op}(지표 계약 §1.1). 1석 선점 · 결제 시작 · 확정(hold · pay_start · confirm)과 PG(pg_pay)는 이 지표에 없다
METRIC_OP = {"hold_group": "hold", "release_group": "release_group", "release": "release_hold", "pay_start_group": "pay_start", "confirm_group": "confirm"}
APP_OPS = ("hold", "hold_group", "release", "release_group", "pay_start", "pay_start_group", "confirm", "confirm_group")


def num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def sessions_file(d):
    d = Path(d)
    for n in ("sessions18.log.gz", "sessions18.log"):
        if (d / n).exists():
            return d / n
    return d / "sessions18.log.gz"


def requests(row):
    """세션의 응답 객체 전부(hold → rel → ps → pay → cf 순 — 같은 목록 안은 보낸 순서)."""
    out = []
    for k in ("hold", "rel", "ps", "pay", "cf"):
        v = row.get(k)
        if isinstance(v, list):
            out.extend(o for o in v if isinstance(o, dict))
    return out


def metric_outcome(o):
    """응답 → 지표 outcome 값: 2xx = ok · 본문 code(ErrorCode 이름) · 그 밖 status_<코드>(지표 계약 §1.1). 상태 0은 None(서버가 셌는지 모름)."""
    st = o.get("st")
    if not isinstance(st, int) or st == 0:
        return None
    if 200 <= st < 300:
        return "ok"
    code = o.get("code")
    return code if isinstance(code, str) and code not in ("OK", "NOCODE", "") else f"status_{st}"


# ---- uid 끝 상태(§4) ---------------------------------------------------------------------------------------------------------
def _task(t):
    return {"id": t.get("id"), "source": t.get("source"), "status": t.get("status"), "attempts": t.get("attempts"),
            "last_result": t.get("last_result"), "created_ms": t.get("created_ms"), "done_ms": t.get("done_ms")}


def end_states(db_dump, ledger, amount):
    """DB intent 또는 PG 원장에 나오는 모든 uid → End(§4)."""
    intents = {i["uid"]: i for i in db_dump.get("payment_intent") or []}
    led = {p["uid"]: p for p in (ledger.get("payments") or [])}
    res, tasks, rej = {}, {}, {}
    for r in db_dump.get("reservation") or []:
        if r.get("intent_id") is not None:
            res.setdefault(r["intent_id"], []).append({"id": r.get("id"), "seat_id": r.get("seat_id"), "status": r.get("status"), "origin": r.get("origin")})
    for t in db_dump.get("compensation_task") or []:
        tasks.setdefault(t["uid"], {})[t.get("type")] = _task(t)
    for c in db_dump.get("confirm_rejection") or []:
        rej[c["uid"]] = {"code": c.get("code"), "http_status": c.get("http_status"), "rejected_ms": c.get("rejected_ms")}
    out = {}
    for uid in sorted(set(intents) | set(led)):
        i, p = intents.get(uid), led.get(uid)
        e = {
            "uid": uid, "intent_state": i.get("state") if i else None, "user_id": i.get("user_id") if i else (p.get("userId") if p else None),
            "group_id": i.get("group_id") if i else None, "hold_id": i.get("hold_id") if i else None,
            "seat_count": int(i.get("seat_count") or 1) if i else None, "res": res.get(uid, []),
            "pg_state": p.get("state") if p else None, "refunds": int(p.get("refunds") or 0) if p else 0,
            "captured": bool(p and p.get("capturedAt")), "amount": p.get("amount") if p else None, "tombstone": bool(p and p.get("tombstone")),
            "close": tasks.get(uid, {}).get("CLOSE"), "capture": tasks.get(uid, {}).get("CAPTURE"), "rejection": rej.get(uid),
        }
        e["shape"] = shape(e, amount)
        out[uid] = e
    return out


def shape(end, amount):
    """§4 표 — 위에서부터 처음 맞는 줄. conf = CONFIRMED 예약 수 · sc = intent seat_count(intent 없으면 1)."""
    conf = sum(1 for r in end.get("res") or [] if r.get("status") == "CONFIRMED")
    sc = end.get("seat_count") or 1
    st = end.get("pg_state")
    has_intent = end.get("intent_state") is not None
    has_ledger = st is not None
    if conf > sc:
        return "double_reservation"
    if (end.get("refunds") or 0) >= 2:
        return "double_refund"
    if st == "REFUNDED" and conf:
        return "refunded_with_seat"
    if 0 < conf < sc:
        return "partial_reservation"
    if has_intent and has_ledger and not end.get("tombstone") and num(amount) and end.get("amount") != sc * amount:
        return "amount_mismatch"
    if conf == sc:
        return "seat_paid" if st == "CAPTURED" else "seat_moneyless"
    if st == "REFUNDED":
        return "refunded"
    if st == "CLOSED" and not end.get("captured"):
        return "closed_unpaid"
    if st == "CAPTURED" or (st == "CLOSED" and end.get("captured") and has_intent):
        return "orphan"
    if st == "REGISTERED":
        return "open_unpaid"
    if has_intent and not has_ledger:
        return "no_ledger"
    return "no_intent"   # 그 밖 전부 — intent 없는 CLOSED + 결제 흔적 · refund 모드에서 생기지 않는 AUTHORIZED · VOIDED · EXPIRED · 알 수 없는 값


# ---- 행 정합(§3.2) ---------------------------------------------------------------------------------------------------------
def _ok(o):
    return isinstance(o, dict) and o.get("ok") is True


def _ops(lst, op):
    return all(isinstance(o, dict) and o.get("op") == op for o in lst)


def _row_ok(row, plan):
    if not isinstance(row, dict) or any(f not in row for f in ROW_FIELDS):
        return False
    cyc = plan.get("kind_cycle") or []
    sid = row.get("sid")
    if not isinstance(sid, int) or not cyc or row.get("kind") != cyc[sid % len(cyc)]:
        return False
    if row.get("rep") != plan.get("rep") or row.get("method") != plan.get("method") or row.get("cell") != plan.get("cell"):
        return False
    h, rl, ps, pay, cf = row["hold"], row["rel"], row["ps"], row["pay"], row["cf"]
    if not all(isinstance(x, list) for x in (h, rl, ps, pay, cf)):
        return False
    group = row["method"] in GROUP_METHODS
    kind, got, end = row["kind"], row["got"], row["end"]
    n_ids = 1 if group else 2
    if not h or not _ops(h, "hold_group" if group else "hold") or len(h) > n_ids:
        return False
    if not (num(h[0].get("at")) and h[0]["at"] == h[0]["at"] and abs(h[0]["at"]) != float("inf")):
        return False   # 첫 선점의 예정 송신 at은 모든 셀에서 유한해야 한다(하네스 기록 결손 = 행 오류 — 리뷰 H3-R5)
    if got == "none":
        return end == "hold" and not _ok(h[-1]) and all(_ok(x) for x in h[:-1]) and len(h) == 1 and not (rl or ps or pay or cf)
    if got == "first":
        if group or end != "partial" or len(h) != 2 or not _ok(h[0]) or _ok(h[1]) or ps or pay or cf:
            return False
        if row["method"] == "B0":
            return not rl
        return len(rl) == 1 and _ops(rl, "release") and rl[0].get("id") == h[0].get("hid")
    if got != "both" or len(h) != n_ids or not all(_ok(x) for x in h):
        return False
    if end == "abandon":
        return kind == "abandon" and not (rl or ps or pay or cf)
    if end == "release":
        return kind == "release" and len(rl) == n_ids and _ops(rl, "release_group" if group else "release") and not (ps or pay or cf)
    if kind not in ("ok", "edge_confirm", "edge_ps") or rl:
        return False
    want_ps = n_ids if kind == "ok" else 1
    ps_op = "pay_start_group" if group else "pay_start"
    if not ps or not _ops(ps, ps_op) or len(ps) > want_ps:
        return False
    if end == "pay_start":
        return not _ok(ps[-1]) and all(_ok(x) for x in ps[:-1]) and not (pay or cf)
    if len(ps) != want_ps or not all(_ok(x) for x in ps) or not pay or not _ops(pay, "pg_pay") or len(pay) > len(ps):
        return False
    if end == "pg":
        return not _ok(pay[-1]) and all(_ok(x) for x in pay[:-1]) and not cf
    if end == "done":
        return len(pay) == len(ps) and all(_ok(x) for x in pay) and len(cf) == len(ps) and _ops(cf, "confirm_group" if group else "confirm")
    return False


# ---- 결정표(§5) ------------------------------------------------------------------------------------------------------------
def _pair(o):
    return (o.get("st"), o.get("code"))


def _unexpected(stage, o):
    return f"unexpected:{stage}-{o.get('st')}-{o.get('code')}"


def _hold_fail(o):
    """선점 실패 응답 하나의 갈래(§5 C)."""
    if _pair(o) == HOLD_LOST:
        return None   # 정상 경합 패배
    if _pair(o) == HOLD_LIMIT:
        return "integrity:hold-limit"   # 사용자마다 2석 이하 · 세션마다 다른 사용자 — 한도 초과는 카운터 어긋남
    if o.get("st") == 201:
        return "integrity:hold-body"   # 앱 응답 계약 위반(좌석 열 ≠ 요청 · holdId 결측 · 1석 seatId 다름 등) — 하네스가 아니라 앱의 결함(리뷰 H2-5)
    return _unexpected(o.get("op"), o)


def _confirmed_ids(end):
    return sorted((r.get("id"), r.get("seat_id")) for r in (end or {}).get("res") or [] if r.get("status") == "CONFIRMED")


def classify(row, ends, plan):
    """§5 결정표 — 위에서부터 처음 맞는 줄. ends = uid → End(§4)."""
    if not _row_ok(row, plan):
        return "invalid:row-bad"
    pre = row.get("pre")
    if isinstance(pre, dict):   # 동시 경합 셀 연결 미리 맺기(GET /actuator/health — 리뷰 H2-7): 상태 0 = 연결 오류(환경) · 200이 아님 = 하네스 무효
        if pre.get("st") == 0:
            return "invalid:conn-preconnect"
        if pre.get("st") != 200:
            return "invalid:preconnect"
    reqs = requests(row)
    for o in reqs:   # B. k6 연결 오류(상태 0 — dial · reset · timeout)
        if o.get("st") == 0:
            return f"invalid:conn-{o.get('op')}"
    for o in reqs:   # C. 앱 5xx(PG 응답은 E의 pg 줄)
        if o.get("op") in APP_OPS and isinstance(o.get("st"), int) and o["st"] >= 500:
            return "edge_5xx" if row.get("cell") == "edge" else f"fail:5xx-{o.get('op')}"
    got, end, kind = row["got"], row["end"], row["kind"]
    h = row["hold"]
    if got == "none":   # D. 선점
        return _hold_fail(h[-1]) or "lost"
    if got == "first":
        bad = _hold_fail(h[1])
        if bad:
            return bad
        if row["method"] == "B0":
            return "partial_kept"
        r = row["rel"][0]
        return "partial_released" if _ok(r) else _unexpected("release", r)
    if end == "abandon":   # E. 2석을 다 잡은 세션
        return "abandoned"
    if end == "release":
        bad = next((r for r in row["rel"] if not _ok(r)), None)
        return _unexpected(bad.get("op"), bad) if bad else "released"
    ps_uids = [p.get("uid") for p in row["ps"] if _ok(p)]
    if end == "pay_start":
        x = row["ps"][-1]
        if _pair(x) in PS_REJECT:
            for u in ps_uids:   # B의 둘째 결제 시작 거부 — 앞서 발급된 uid는 안전망이 닫아야 한다(결제 없음)
                s = (ends.get(u) or {}).get("shape", "none")
                if s != "closed_unpaid":
                    return f"integrity:ps-left-{s}"
            return "ps_rejected"
        if x.get("st") == 200:
            return "integrity:ps-body"   # 결제 시작 200 본문 계약 위반 — 앱 결함(리뷰 H2-5)
        return _unexpected(x.get("op"), x)
    if end == "pg":
        x = row["pay"][-1]
        if _pair(x) == PG_DEADLINE:
            for u in ps_uids:
                s = (ends.get(u) or {}).get("shape", "none")
                if s not in ("closed_unpaid", "refunded"):   # 앞 uid가 결제됐으면 안전망 환불(refunded) · 아니면 결제 없이 닫힘
                    return f"integrity:pg-{s}"
            return "pg_rejected"
        return f"invalid:pg-{x.get('st')}-{x.get('code')}"
    good = rej = 0
    for c in row["cf"]:   # done — 확정 응답마다
        e = ends.get(c.get("uid"))
        s = (e or {}).get("shape", "none")
        if _ok(c):
            if s not in ("seat_paid",):
                return f"integrity:200-{s}"
            if sorted(zip(c.get("rids") or [], c.get("rseats") or [])) != _confirmed_ids(e):
                return "integrity:200-mismatch"
            good += 1
        elif _pair(c) in CONFIRM_REJECT:
            if s != "refunded":
                return f"integrity:reject-{s}"
            rej += 1
        elif c.get("st") == 200:
            return "integrity:confirm-body"
        else:
            return _unexpected(c.get("op"), c)
    if good and rej:
        return "confirm_mixed"
    return "success" if good else "confirm_rejected"


def analyze(d):
    d = Path(d)
    try:
        plan = json.loads((d / "plan.json").read_text())
    except (OSError, ValueError):
        return None, "plan"
    rd = read_sessions(sessions_file(d))
    if rd is None:
        return None, "sessions"
    try:
        db = json.loads((d / "db-dump.json").read_text())
    except (OSError, ValueError):
        return None, "db-dump"
    try:
        led = json.loads((d / "pg-ledger.json").read_text())
    except (OSError, ValueError):
        return None, "ledger"
    ends = end_states(db, led, (plan.get("values") or {}).get("amount"))
    rows, _ = rd
    out = {"sessions": [], "counts": {}, "uids": {}}
    for r in sorted(rows, key=lambda x: x.get("sid") if isinstance(x.get("sid"), int) else -1):
        c = classify(r, ends, plan)
        out["sessions"].append({"sid": r.get("sid"), "kind": r.get("kind"), "got": r.get("got"), "cat": c})
        out["counts"].setdefault(str(r.get("kind")), {})
        out["counts"][str(r.get("kind"))][c] = out["counts"][str(r.get("kind"))].get(c, 0) + 1
    for e in ends.values():
        out["uids"][e["shape"]] = out["uids"].get(e["shape"], 0) + 1
    return out, None


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    out, err = analyze(argv[1])
    if err:
        print(json.dumps({"error": f"{err}-missing"}))
        return 2
    print(json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
