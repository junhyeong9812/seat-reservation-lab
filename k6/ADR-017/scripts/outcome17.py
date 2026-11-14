#!/usr/bin/env python3
"""ADR-017 세션 결과 — 단일 정의(측정 명세 M17-1 · 계약 k6/ADR-017/INTERFACE.md §3 · §4 · §5 · §9.1). summarize17 · selftest17이 이 파일을 따른다.

- read_sessions(path)          = 세션 로그(§3) → (행 목록, 깨진 줄 수) · 파일 없음 = None
- end_states(db_dump, ledger)  = 배수 뒤 DB 덤프(§6.2) + PG 원장(§6.1) → uid마다 끝 상태(§4 — shape 포함)
- shape(end)                   = §4 표(위에서부터 처음 맞는 줄)
- kill_windows(kill)           = kill.json(§6.7) → kill 창 [(lo_ms, hi_ms)]
- classify(row, end, cand, kills, plan) = §5 결정표 → 범주 하나 · 'invalid:…' · 'mode_fail:…' · 'integrity:…'

    outcome17.py <회차 폴더>   → 표준 출력 JSON 한 줄 {"sessions": [...], "counts": {...}, "uids": {...}} · exit 0 · 입력 없음 = exit 2 {"error": "<파일>-missing"}
"""
import gzip
import json
import sys
from datetime import datetime
from pathlib import Path

CATEGORIES = ("success", "not_paid", "pre_reject", "wronged_refunded", "wronged_voided", "wronged_substituted", "wronged_open", "app_dead")
SHAPES = ("double_reservation", "double_refund", "refunded_with_seat", "voided_with_seat", "substituted", "substituted_unpaid", "seat_paid",
          "seat_moneyless", "refunded", "voided", "closed_unpaid", "orphan", "auth_open", "open_unpaid", "no_ledger", "no_intent")
CONFIRM_REJECT = frozenset({(404, "HOLD_NOT_FOUND"), (409, "HOLD_EXPIRED"), (409, "SEAT_NOT_HELD")})
PAY_START_REJECT = frozenset({(409, "HOLD_EXPIRED"), (404, "HOLD_NOT_FOUND")})
K6_TIMEOUT_ERROR = 1050
KILL_TOL_MS = 1000
KILL_READY_FALLBACK_MS = 120000   # ready_ms가 없으면 창 끝 = start_ms + 120초(§6.7)
KINDS = ("ok", "late", "noconf", "abandon", "retry")
ROW_FIELDS = ("k", "v", "sid", "kind", "rep", "cand", "cell", "user", "seat", "T", "P", "G", "R", "L", "uid", "at", "h", "p", "g", "c1", "c2", "end")


def iso_ms(s):
    """PG 원장 ISO-8601(`Z` · 소수 최대 9자리) → epoch ms · None/빈 값 → None."""
    if not s:
        return None
    t = str(s).replace("Z", "+00:00")
    if "." in t:   # 소수 6자리 넘으면 자른다(파이썬 버전 무관)
        head, rest = t.split(".", 1)
        i = 0
        while i < len(rest) and rest[i].isdigit():
            i += 1
        t = f"{head}.{rest[:i][:6].ljust(6, '0')}{rest[i:]}"
    return datetime.fromisoformat(t).timestamp() * 1000


def read_sessions(path):
    """§3: 줄마다 k6 JSON 로그 · msg = 세션 행. 깨진 줄(파싱 실패 · k ≠ s · level error) 수를 센다 — 맨 끝 찢긴 한 줄(파싱 실패)은 세지 않는다."""
    p = Path(path)
    if not p.exists():
        return None
    opener = gzip.open if p.suffix == ".gz" else open
    with opener(p, "rt", errors="replace") as f:
        lines = [x.strip() for x in f if x.strip()]
    rows, broken = [], 0
    for i, line in enumerate(lines):
        try:
            outer = json.loads(line)
            if isinstance(outer, dict) and outer.get("level") == "error":
                broken += 1
                continue
            m = json.loads(outer["msg"])
        except (ValueError, KeyError, TypeError):
            broken += i != len(lines) - 1
            continue
        if isinstance(m, dict) and m.get("k") == "s":
            rows.append(m)
        else:
            broken += 1
    return rows, broken


def _task(t):
    return {"id": t.get("id"), "source": t.get("source"), "status": t.get("status"), "attempts": t.get("attempts"),
            "last_result": t.get("last_result"), "created_ms": t.get("created_ms"), "done_ms": t.get("done_ms")}


def end_states(db_dump, ledger):
    """DB intent 또는 PG 원장에 나오는 모든 uid → End(§4)."""
    intents = {i["uid"]: i for i in db_dump.get("payment_intent") or []}
    led = {p["uid"]: p for p in (ledger.get("payments") or [])}
    res, tasks, rej = {}, {}, {}
    for r in db_dump.get("reservation") or []:
        if r.get("intent_id") is not None:
            res.setdefault(r["intent_id"], []).append({"id": r.get("id"), "status": r.get("status"), "origin": r.get("origin")})
    for t in db_dump.get("compensation_task") or []:
        tasks.setdefault(t["uid"], {})[t.get("type")] = _task(t)
    for c in db_dump.get("confirm_rejection") or []:
        rej[c["uid"]] = {"code": c.get("code"), "http_status": c.get("http_status"), "rejected_ms": c.get("rejected_ms")}
    out = {}
    for uid in sorted(set(intents) | set(led)):
        i, p = intents.get(uid), led.get(uid)
        e = {
            "uid": uid, "intent_state": i.get("state") if i else None, "closed_reason": i.get("closed_reason") if i else None,
            "result_code": i.get("result_code") if i else None, "res": res.get(uid, []),
            "pg_state": p.get("state") if p else None, "refunds": int(p.get("refunds") or 0) if p else 0,
            "captured": bool(p and p.get("capturedAt")), "authorized": bool(p and p.get("authorizedAt")), "tombstone": bool(p and p.get("tombstone")),
            "close": tasks.get(uid, {}).get("CLOSE"), "capture": tasks.get(uid, {}).get("CAPTURE"), "rejection": rej.get(uid),
        }
        e["shape"] = shape(e)
        out[uid] = e
    return out


def shape(end):
    """§4 표 — 위에서부터 처음 맞는 줄."""
    conf = [r for r in end.get("res") or [] if r.get("status") == "CONFIRMED"]
    ch = sum(1 for r in conf if r.get("origin") == "HOLD")
    cs = sum(1 for r in conf if r.get("origin") == "SUBSTITUTE")
    st = end.get("pg_state")
    has_intent = end.get("intent_state") is not None
    has_ledger = st is not None
    if len(conf) >= 2:
        return "double_reservation"
    if (end.get("refunds") or 0) >= 2:
        return "double_refund"
    if st == "REFUNDED" and conf:
        return "refunded_with_seat"
    if st == "VOIDED" and conf:
        return "voided_with_seat"
    if cs == 1:
        return "substituted" if st == "CAPTURED" else "substituted_unpaid"
    if ch == 1:
        return "seat_paid" if st == "CAPTURED" else "seat_moneyless"
    if st == "REFUNDED":
        return "refunded"
    if st == "VOIDED":
        return "voided"
    if st == "CLOSED" and not end.get("captured") and not end.get("authorized"):
        return "closed_unpaid"
    if st == "CAPTURED" or (st == "CLOSED" and end.get("captured") and has_intent):   # intent 있는 CLOSED + 결제 흔적 = 환불 없이 닫힘(흉내 PG에서는 생기지 않음 — 생기면 고아)
        return "orphan"
    if st in ("AUTHORIZED", "EXPIRED") or (st == "CLOSED" and end.get("authorized") and has_intent):
        return "auth_open"
    if st == "REGISTERED":
        return "open_unpaid"
    if has_intent and not has_ledger:
        return "no_ledger"
    return "no_intent"   # 그 밖 전부(§4 줄 15) — intent 없는 CLOSED + 결제 흔적 · 알 수 없는 pg_state


def kill_windows(kill):
    """§6.7 — [kill_ms − 1초, (ready_ms 또는 start_ms + 120초) + 1초]."""
    if not isinstance(kill, dict):
        return []
    out = []
    for ev in kill.get("events") or []:
        k = ev.get("kill_ms")
        if not isinstance(k, (int, float)):
            continue
        hi = ev.get("ready_ms")
        if not isinstance(hi, (int, float)):
            s = ev.get("start_ms")
            hi = (s if isinstance(s, (int, float)) else k) + KILL_READY_FALLBACK_MS
        out.append((k - KILL_TOL_MS, hi + KILL_TOL_MS))
    return out


def _ok(o):
    return isinstance(o, dict) and o.get("ok") is True


def _row_ok(row, plan):
    """§3.2 + 종류 규칙 + 회차 식별(§5 A)."""
    if not isinstance(row, dict) or any(f not in row for f in ROW_FIELDS):
        return False
    cyc = plan.get("kind_cycle") or []
    sid = row.get("sid")
    if not isinstance(sid, int) or not cyc or row.get("kind") != cyc[sid % len(cyc)]:
        return False
    if row.get("rep") != plan.get("rep") or row.get("cand") != plan.get("cand") or row.get("cell") != plan.get("cell"):
        return False
    e, kind = row.get("end"), row.get("kind")
    h, p, g, c1, c2, uid = row["h"], row["p"], row["g"], row["c1"], row["c2"], row["uid"]
    if e == "hold":
        return isinstance(h, dict) and not _ok(h) and p is None and g is None and c1 is None and c2 is None and uid is None
    if e == "pay_start":
        return _ok(h) and isinstance(p, dict) and not _ok(p) and g is None and c1 is None and c2 is None and uid is None
    if e == "abandon":
        return kind == "abandon" and _ok(p) and uid is not None and g is None and c1 is None and c2 is None
    if e == "pg":
        return _ok(p) and isinstance(g, dict) and not _ok(g) and uid is not None and c1 is None and c2 is None
    if e == "noconf":
        return kind == "noconf" and _ok(g) and c1 is None and c2 is None
    if e == "done":
        if kind not in ("ok", "late", "retry") or not _ok(g) or not isinstance(c1, dict) or row.get("at") is None:
            return False
        return isinstance(c2, dict) if kind == "retry" else c2 is None
    return False


def _sc(o):
    return o.get("st"), o.get("code")


def _confirmed_id(end, origin):
    for r in (end or {}).get("res") or []:
        if r.get("status") == "CONFIRMED" and r.get("origin") == origin:
            return r.get("id")
    return None


def classify(row, end, cand, kills, plan):
    """§5 결정표."""
    if not _row_ok(row, plan):
        return "invalid:row-bad"
    e = row["end"]
    sh = end["shape"] if end else "none"
    # B. 앱 죽음 — end 단계의 앱 응답이 상태 0 · 5xx이고 [ts, tr]이 kill 창과 겹침
    last = {"hold": row["h"], "pay_start": row["p"], "done": row["c2"] if row["c2"] is not None else row["c1"]}.get(e)
    if isinstance(last, dict):
        st = last.get("st")
        if isinstance(st, int) and (st == 0 or st >= 500):
            ts, tr = last.get("ts"), last.get("tr")
            if isinstance(ts, (int, float)) and isinstance(tr, (int, float)) and any(ts <= hi and tr >= lo for lo, hi in kills or []):
                return "app_dead"
    if e == "hold":
        h = row["h"]
        st, code, ec = h.get("st"), h.get("code"), h.get("ec")
        if st == 0:
            return "mode_fail:hold-timeout" if ec == K6_TIMEOUT_ERROR else "invalid:hold-code0"
        if isinstance(st, int) and st >= 500:
            return "mode_fail:hold-5xx"
        return f"invalid:hold-{st}-{code}"
    if e == "pay_start":
        p = row["p"]
        st, code, ec = p.get("st"), p.get("code"), p.get("ec")
        if (st, code) in PAY_START_REJECT:
            return "pre_reject" if sh in ("none", "closed_unpaid", "open_unpaid") else f"integrity:pre-reject-{sh}"
        if st == 503:
            return "mode_fail:pay-start-503"
        if st == 200:
            return "mode_fail:pay-start-body"
        if st == 0:
            return "mode_fail:pay-start-timeout" if ec == K6_TIMEOUT_ERROR else "invalid:pay-start-code0"
        return f"mode_fail:pay-start-{st}-{code}"
    if e == "abandon":
        return "not_paid" if sh == "closed_unpaid" else f"integrity:abandon-{sh}"
    if e == "pg":
        g = row["g"]
        if g.get("st") == 409 and g.get("code") == "DEADLINE_PASSED":
            return "pre_reject" if sh in ("closed_unpaid", "open_unpaid") else f"integrity:pg-deadline-{sh}"
        return f"invalid:pg-{g.get('st')}-{g.get('code')}"
    if e == "noconf":
        m = {"refunded": "wronged_refunded", "voided": "wronged_voided", "orphan": "wronged_open", "auth_open": "wronged_open"}
        return m.get(sh, f"integrity:noconf-{sh}")
    # D. done
    c = row["c1"]
    if row["kind"] == "retry":
        c2 = row["c2"]
        if (c2.get("st"), c2.get("code"), c2.get("rid")) != (c.get("st"), c.get("code"), c.get("rid")):
            return "integrity:retry-replay-mismatch"
    st, code, ec = c.get("st"), c.get("code"), c.get("ec")
    if st == 200:
        rid = _confirmed_id(end, "HOLD")
        if sh == "seat_paid" and rid is not None and c.get("rid") == rid:
            return "success"
        if sh == "seat_moneyless" and cand == "D" and rid is not None and c.get("rid") == rid:
            return "success"
        if sh in ("seat_paid", "seat_moneyless"):
            return "integrity:200-mismatch"
        return f"integrity:200-{sh}"
    if (st, code) in CONFIRM_REJECT:
        if sh == "refunded" and cand in ("R", "S"):
            return "wronged_refunded"
        if sh == "voided" and cand == "D":
            return "wronged_voided"
        cp = c.get("cp") if isinstance(c.get("cp"), dict) else {}
        if sh == "substituted" and cand == "S" and cp.get("type") == "SUBSTITUTE" and cp.get("rid") is not None \
                and cp.get("rid") == _confirmed_id(end, "SUBSTITUTE"):
            return "wronged_substituted"
        if sh in ("orphan", "auth_open"):
            return "wronged_open"
        return f"integrity:reject-{sh}"
    if st == 0:
        return "mode_fail:confirm-timeout" if ec == K6_TIMEOUT_ERROR else "invalid:confirm-code0"
    if isinstance(st, int) and st >= 500:
        return "mode_fail:confirm-5xx"
    return f"mode_fail:confirm-{st}-{code}"


def _load(path):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return None


def sessions_file(d):
    d = Path(d)
    for n in ("sessions17.log.gz", "sessions17.log"):
        if (d / n).exists():
            return d / n
    return d / "sessions17.log.gz"


def analyze(d):
    """회차 폴더 → (결과 dict, 오류 파일 이름 | None). summarize17이 같은 함수를 쓴다."""
    d = Path(d)
    plan = _load(d / "plan.json")
    if not isinstance(plan, dict):
        return None, "plan"
    rd = read_sessions(sessions_file(d))
    if rd is None:
        return None, "sessions"
    db, led = _load(d / "db-dump.json"), _load(d / "pg-ledger.json")
    if not isinstance(db, dict):
        return None, "db-dump"
    if not isinstance(led, dict):
        return None, "ledger"
    kill = None
    if plan.get("cell") == "kill":
        kill = _load(d / "kill.json")
        if not isinstance(kill, dict):
            return None, "kill"
    ends = end_states(db, led)
    kills = kill_windows(kill)
    rows, _ = rd
    sess, counts = [], {}
    for r in sorted(rows, key=lambda x: x.get("sid") if isinstance(x.get("sid"), int) else -1):
        uid = r.get("uid") if isinstance(r, dict) else None
        end = ends.get(uid) if uid else None
        cat = classify(r, end, plan.get("cand"), kills, plan)
        kind = r.get("kind") if isinstance(r, dict) else None
        sess.append({"sid": r.get("sid"), "kind": kind, "uid": uid, "cat": cat, "shape": end["shape"] if end else None})
        counts.setdefault(str(kind), {})
        counts[str(kind)][cat] = counts[str(kind)].get(cat, 0) + 1
    uids = {}
    for e in ends.values():
        uids[e["shape"]] = uids.get(e["shape"], 0) + 1
    return {"sessions": sess, "counts": counts, "uids": uids, "_ends": ends, "_rows": rows, "_plan": plan, "_kills": kills}, None


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    out, err = analyze(argv[1])
    if err:
        print(json.dumps({"error": f"{err}-missing"}))
        return 2
    print(json.dumps({k: v for k, v in out.items() if not k.startswith("_")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
