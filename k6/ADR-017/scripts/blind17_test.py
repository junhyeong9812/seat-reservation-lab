#!/usr/bin/env python3
"""ADR-017 하네스 blind 테스트(구현 미열람 계약 — task 05).

근거 = 계약 `k6/ADR-017/INTERFACE.md`(§3~§9) · 측정 명세 `docs/plans/2026-11-14/adr-017-measurement/requirement-spec.md`
(M17-1~6 · §5) · 설계 ADR-017 설계-2 · 6 · 8 · 9. 구현 파일(outcome17 · summarize17 · plan17 등)은 읽지 않고 계약에서
사례를 만들었다. 각 사례의 docstring/메시지에 근거 절(§)을 적는다.

    python3 -I k6/ADR-017/scripts/blind17_test.py [-v]

- outcome17은 모듈로 import해 함수(§9.1)를 부르고, CLI(§9.1)도 부른다.
- summarize17은 CLI만 계약에 있으므로(§9.2) 임시 디렉터리에 가짜 회차 · 캠페인 폴더를 만들어 부른다.
- 가짜 회차 폴더는 계약에 형식이 없는 파일(app-config · seed-check · meta · timeline · k6 CSV 일부)을 정확히 맞출 수 없다.
  그래서 회차 무효 사례는 **차분**으로 단언한다 — 결함 변형에는 그 사유가 있고, 같은 바탕(결함 없는 변형)에는 그 사유가 없다.
  status 전체가 `ok`인지는 단언하지 않는다(계약 밖 파일 형식 때문에 다른 사유가 섞일 수 있다).
- import 실패 · 스크립트 없음은 skip이 아니라 오류(ERROR)로 센다.
"""
import copy
import datetime as _dt
import gzip
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))  # python3 -I는 스크립트 폴더를 sys.path에 넣지 않는다

OUTCOME_PY = HERE / "outcome17.py"
SUMMARIZE_PY = HERE / "summarize17.py"


def _try_import(name):
    try:
        return __import__(name), None
    except Exception as e:  # noqa: BLE001 — import 실패 원인을 그대로 보고한다
        return None, f"{type(e).__name__}: {e}"


O, O_ERR = _try_import("outcome17")
P17, _ = _try_import("plan17")  # session_attrs(rep, sid, params)만 쓴다(§2) — 없으면 고정값


def _require(mod, err, name):
    if mod is None:
        raise ImportError(f"{name}.py import 실패 — 이 클래스의 사례는 돌지 않았다(skip 아님 · 오류로 센다): {err}")


def _require_script(path):
    if not path.exists():
        raise AssertionError(f"{path.name} 없음 — 이 클래스의 사례는 돌지 않았다(skip 아님)")


# =====================================================================================
# 공통 고정값 · 빌더
# =====================================================================================

T0 = 1_794_614_400_000.0  # 2026-11-14T00:00:00Z (서버 ms)
CYCLE = ["ok", "late", "noconf", "abandon", "retry"]
CAND_MODE = {"R": "refund", "D": "auth-capture", "S": "substitute"}
PG_OP = {"R": "pay", "S": "pay", "D": "authorize"}
SPARE = 5
USER_BASE = 1000000  # §2 k6.env.USER_BASE = "1000000"
PARAMS = {"tMin": 30, "tMax": 240, "payMedian": 20, "paySigma": 0.5, "payMax": 120,
          "gMin": 1, "gMax": 5, "rMin": 1, "rMax": 3, "lMin": 0.2, "lMax": 10}
FAULTS_BASE = {"seed": 1, "rules": {}}
FAULTS_PG = {"seed": 1, "rules": {
    "capture": {"delayMs": 200, "failRate": 0.1, "unknownRate": 0.05, "unknownDelayMs": 0},
    "close": {"delayMs": 200, "failRate": 0.1, "unknownRate": 0.05, "unknownDelayMs": 0}}}
REJ_PAIRS = [(404, "HOLD_NOT_FOUND"), (409, "HOLD_EXPIRED"), (409, "SEAT_NOT_HELD")]
G1_ZERO_SHAPES = ["orphan", "double_reservation", "double_refund", "refunded_with_seat", "voided_with_seat",
                  "auth_open", "open_unpaid", "substituted_unpaid", "no_ledger", "no_intent"]  # §8 G1-1
PAY_ZERO = ["pay_multi_reservation_intents", "pay_rejection_state_mismatch", "pay_reserved_with_close",
            "pay_capture_and_close", "pay_rejected_without_task", "pay_dead_tasks", "pay_open_unheld_intents"]  # §6.3


def uid_of(sid):
    return f"5e000000-0000-4000-8000-{sid:012d}"


def iso(ms):
    d = _dt.datetime.fromtimestamp(ms / 1000.0, tz=_dt.timezone.utc)
    return d.strftime("%Y-%m-%dT%H:%M:%S.") + f"{d.microsecond // 1000:03d}Z"


def plan_obj(cand="R", cell="base", rep=1, fixed=False, cycle=None, n_total=None):
    cycle = list(cycle or CYCLE)
    n = n_total if n_total is not None else len(cycle)
    planned = {}
    for sid in range(n):
        k = cycle[sid % len(cycle)]
        planned[k] = planned.get(k, 0) + 1
    kill = None
    if cell == "kill":
        kill = {"at_s": [120, 360], "signal": "KILL", "restart": "immediate", "container": "seatlab-app-1"}
    return {"cand": cand, "mode": CAND_MODE[cand], "cell": cell, "rep": rep, "fixed": fixed,
            "n_total": n, "planned": planned, "kind_cycle": cycle, "params": dict(PARAMS),
            "seed": {"schedules": 1, "seats": SPARE + n, "spare": SPARE, "section": "A"},
            "pg": {"faults": copy.deepcopy(FAULTS_PG if cell == "pg" else FAULTS_BASE), "op": PG_OP[cand]},
            "kill": kill, "drain": {"cap_s": 600, "poll_s": 2, "est_s": 30},
            "values": {"g2_x_s": 30, "g2_y_s": 120, "kind_weights": {k: 1 for k in cycle}, "pg_rule": "fixed"},
            "rate": 1, "d_arr_s": 10,
            "app_env": {}, "k6": {"env": {"USER_BASE": str(USER_BASE)}}}


def attrs(rep, sid):
    """§2 · §3.1 T · P · G · R · L = plan17.session_attrs(rep, sid, params). 반환 형식은 계약에 없어
    dict(T..L 키) 또는 길이 5 순서열(T, P, G, R, L)을 받는다. 그 밖이면 고정값(바탕 ok 사례가 실패해 드러난다)."""
    if P17 is not None and hasattr(P17, "session_attrs"):
        try:
            a = P17.session_attrs(rep, sid, dict(PARAMS))
            if isinstance(a, dict) and all(k in a for k in "TPGRL"):
                return {k: float(a[k]) for k in "TPGRL"}
            if isinstance(a, (list, tuple)) and len(a) == 5:
                return dict(zip("TPGRL", map(float, a)))
        except Exception:  # noqa: BLE001
            pass
    return {"T": 60.0, "P": 20.0, "G": 2.0, "R": 1.0, "L": 3.0}


def _nest(flat, prefix):
    out = {}
    for k, v in flat.items():
        if k.startswith(prefix + "."):
            k = k[len(prefix) + 1:]
        cur = out
        parts = k.split(".")
        for q in parts[:-1]:
            cur = cur.setdefault(q, {})
        cur[parts[-1]] = v
    return out


def app_config(cand):
    """§6.10 시험용 바탕 = expected_config(cand)의 점 경로를 중첩으로 편 configprops + hikari 10 + nofile 65536."""
    exp = None
    if P17 is not None and hasattr(P17, "expected_config"):
        for arg in (cand, CAND_MODE[cand]):
            try:
                exp = P17.expected_config(arg)
                break
            except Exception:  # noqa: BLE001
                continue
    props = []
    if isinstance(exp, dict):
        for pfx, flat in exp.items():
            props.append({"prefix": pfx, "properties": _nest(flat, pfx) if isinstance(flat, dict) else flat})
    return {"configprops": props, "hikaricp.connections.max": [10], "app_nofile": {"soft": 65536, "hard": 65536},
            "system.cpu.count": [8], "containers": [], "db_indexes": []}


def H(**kw):
    d = {"st": 201, "code": "OK", "ec": 0, "ts": T0, "tr": T0 + 5, "hid": 7, "exp": T0 + 300000, "ok": True}
    d.update(kw)
    return d


def Pp(uid, **kw):
    d = {"st": 200, "code": "OK", "ec": 0, "ts": T0 + 10, "tr": T0 + 15, "hid": 7, "exp": T0 + 611000,
         "pb": T0 + 600000, "pws": 600, "ext": True, "uid": uid, "ok": True}
    d.update(kw)
    return d


def Gg(op="pay", **kw):
    d = {"op": op, "st": 200, "code": "OK", "state": "CAPTURED" if op == "pay" else "AUTHORIZED", "ec": 0,
         "ts": T0 + 20, "tr": T0 + 25, "ok": True}
    d.update(kw)
    return d


def C(st=200, code=None, rid=None, seat=None, rs=None, cp=None, ec=0, ts=T0 + 30, tr=None):
    if code is None:
        code = "OK" if 200 <= st < 300 else "NOCODE"
    return {"st": st, "code": code, "ec": ec, "ts": ts, "tr": ts + 5 if tr is None else tr,
            "rid": rid, "seat": seat, "rs": rs, "cp": cp}


def C200(rid, seat=None):
    return C(200, rid=rid, seat=seat, rs="CONFIRMED")


def row(kind, end="done", sid=None, cand="R", cell="base", rep=1, uid="__auto__", h=None, p=None, g=None,
        c1=None, c2=None):
    """§3.1 · §3.2 정합 세션 행. end에 따라 앞 필드를 채운다."""
    if sid is None:
        sid = CYCLE.index(kind)
    if uid == "__auto__":
        uid = None if end in ("hold", "pay_start") else uid_of(sid)
    r = {"k": "s", "v": 1, "sid": sid, "kind": kind, "rep": rep, "cand": cand, "cell": cell,
         "user": USER_BASE + sid, "seat": SPARE + sid + 1, **attrs(rep, sid),
         "uid": uid, "at": None, "h": None, "p": None, "g": None, "c1": None, "c2": None, "end": end}
    r["h"] = h if h is not None else H()
    if end == "hold":
        return r
    r["p"] = p if p is not None else Pp(uid)
    if end in ("pay_start", "abandon"):
        return r
    r["g"] = g if g is not None else Gg(PG_OP[cand])
    if end in ("pg", "noconf"):
        return r
    r["c1"] = c1 if c1 is not None else C200(100 + sid)
    if kind == "retry":
        r["c2"] = c2 if c2 is not None else copy.deepcopy(r["c1"])
    r["at"] = T0 + 28
    return r


SHAPE_PG = {"double_reservation": "CAPTURED", "double_refund": "REFUNDED", "refunded_with_seat": "REFUNDED",
            "voided_with_seat": "VOIDED", "substituted": "CAPTURED", "substituted_unpaid": "CLOSED",
            "seat_paid": "CAPTURED", "seat_moneyless": "AUTHORIZED", "refunded": "REFUNDED", "voided": "VOIDED",
            "closed_unpaid": "CLOSED", "orphan": "CAPTURED", "auth_open": "AUTHORIZED", "open_unpaid": "REGISTERED",
            "no_ledger": None, "no_intent": "CAPTURED"}


def E(shape, uid="U", res=None, pg_state="__auto__", refunds=None, captured=None, authorized=None,
      intent_state="REJECTED", close=None, capture=None, rejection=None):
    """§4 End 객체(classify 단위 시험용 — shape를 직접 준다)."""
    if pg_state == "__auto__":
        pg_state = SHAPE_PG.get(shape)
    if res is None:
        res = []
        if shape in ("seat_paid", "seat_moneyless", "refunded_with_seat", "voided_with_seat"):
            res = [{"id": 100, "status": "CONFIRMED", "origin": "HOLD"}]
        elif shape in ("substituted", "substituted_unpaid"):
            res = [{"id": 900, "status": "CONFIRMED", "origin": "SUBSTITUTE"}]
        elif shape == "double_reservation":
            res = [{"id": 100, "status": "CONFIRMED", "origin": "HOLD"}, {"id": 101, "status": "CONFIRMED", "origin": "HOLD"}]
    if refunds is None:
        refunds = 2 if shape == "double_refund" else (1 if pg_state == "REFUNDED" else 0)
    if captured is None:
        captured = pg_state in ("CAPTURED", "REFUNDED")
    if authorized is None:
        authorized = pg_state in ("AUTHORIZED", "VOIDED", "EXPIRED")
    return {"uid": uid, "intent_state": None if shape == "no_intent" else intent_state, "closed_reason": None,
            "result_code": None, "res": res, "pg_state": pg_state, "refunds": refunds, "captured": captured,
            "authorized": authorized, "tombstone": False, "close": close, "capture": capture,
            "rejection": rejection, "shape": shape}


DEFECT_PREFIXES = ("invalid:", "mode_fail:", "integrity:")


# =====================================================================================
# A. outcome17 상수 · iso_ms · kill_windows (§9.1 · §0 · §6.7)
# =====================================================================================

class TestOutcomeConstants(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome17")

    def test_categories(self):
        """§9.1 CATEGORIES"""
        self.assertEqual(tuple(O.CATEGORIES), ("success", "not_paid", "pre_reject", "wronged_refunded", "wronged_voided",
                                               "wronged_substituted", "wronged_open", "app_dead"))

    def test_pairs_and_consts(self):
        """§9.1 CONFIRM_REJECT · PAY_START_REJECT · K6_TIMEOUT_ERROR · KILL_TOL_MS"""
        self.assertEqual(set(O.CONFIRM_REJECT), {(404, "HOLD_NOT_FOUND"), (409, "HOLD_EXPIRED"), (409, "SEAT_NOT_HELD")})
        self.assertEqual(set(O.PAY_START_REJECT), {(409, "HOLD_EXPIRED"), (404, "HOLD_NOT_FOUND")})
        self.assertEqual(O.K6_TIMEOUT_ERROR, 1050)
        self.assertEqual(O.KILL_TOL_MS, 1000)

    def test_shapes_cover_table(self):
        """§9.1 SHAPES = §4 표의 shape 이름 전부"""
        want = {"double_reservation", "double_refund", "refunded_with_seat", "voided_with_seat", "substituted",
                "substituted_unpaid", "seat_paid", "seat_moneyless", "refunded", "voided", "closed_unpaid", "orphan",
                "auth_open", "open_unpaid", "no_ledger", "no_intent"}
        self.assertEqual(set(O.SHAPES), want)


class TestIsoMs(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome17")

    def test_z_no_fraction(self):
        """§0 PG 원장 ISO-8601 Z → epoch ms"""
        self.assertAlmostEqual(O.iso_ms("2026-11-14T00:00:00Z"), T0, places=3)

    def test_fraction_9_digits(self):
        """§0 소수 최대 9자리"""
        self.assertAlmostEqual(O.iso_ms("2026-11-14T00:00:01.123456789Z"), T0 + 1123.456789, delta=1e-3)

    def test_fraction_3_digits(self):
        self.assertAlmostEqual(O.iso_ms("2026-11-14T00:00:01.5Z"), T0 + 1500, places=3)

    def test_none(self):
        """§9.1 시그니처 str | None → None"""
        self.assertIsNone(O.iso_ms(None))


class TestKillWindows(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome17")

    def test_none(self):
        """§9.1 None → []"""
        self.assertEqual(list(O.kill_windows(None)), [])

    def test_ready_and_fallback(self):
        """§6.7 창 = [kill_ms − 1000, (ready_ms 또는 start_ms + 120000) + 1000]"""
        k = {"planned_at_s": [120, 360], "k6_t0_ms": T0, "events": [
            {"n": 1, "planned_ms": T0 + 120000, "kill_ms": T0 + 120010, "kill_rc": 0, "start_ms": T0 + 121000,
             "start_rc": 0, "ready_ms": T0 + 140000, "ready_ok": True, "started_at": "a"},
            {"n": 2, "planned_ms": T0 + 360000, "kill_ms": T0 + 360010, "kill_rc": 0, "start_ms": T0 + 361000,
             "start_rc": 0, "ready_ms": None, "ready_ok": False, "started_at": "b"}]}
        w = [tuple(x) for x in O.kill_windows(k)]
        self.assertEqual(len(w), 2)
        self.assertAlmostEqual(w[0][0], T0 + 119010)
        self.assertAlmostEqual(w[0][1], T0 + 141000)
        self.assertAlmostEqual(w[1][0], T0 + 359010)
        self.assertAlmostEqual(w[1][1], T0 + 361000 + 120000 + 1000)


# =====================================================================================
# B. shape — §4 표(위에서 처음 맞는 줄)
# =====================================================================================

class TestShape(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome17")

    def s(self, res=(), pg_state=None, refunds=0, captured=False, authorized=False, intent=True):
        e = E("seat_paid", res=list(res), pg_state=pg_state, refunds=refunds, captured=captured, authorized=authorized,
              intent_state="CONFIRMED" if intent else None)
        e.pop("shape", None)
        return O.shape(e)

    HOLD = {"id": 1, "status": "CONFIRMED", "origin": "HOLD"}
    HOLD2 = {"id": 2, "status": "CONFIRMED", "origin": "HOLD"}
    SUB = {"id": 3, "status": "CONFIRMED", "origin": "SUBSTITUTE"}
    CANC = {"id": 4, "status": "CANCELLED", "origin": "HOLD"}

    def test_1_double_reservation_first(self):
        """§4 #1 conf ≥ 2 — refunds ≥ 2 · REFUNDED보다 먼저"""
        self.assertEqual(self.s([self.HOLD, self.HOLD2], "REFUNDED", refunds=2, captured=True), "double_reservation")
        self.assertEqual(self.s([self.HOLD, self.SUB], "CAPTURED", captured=True), "double_reservation")

    def test_2_double_refund(self):
        """§4 #2 refunds ≥ 2 — #3보다 먼저"""
        self.assertEqual(self.s([self.HOLD], "REFUNDED", refunds=2, captured=True), "double_refund")
        self.assertEqual(self.s([], "REFUNDED", refunds=3, captured=True), "double_refund")

    def test_3_refunded_with_seat(self):
        self.assertEqual(self.s([self.HOLD], "REFUNDED", refunds=1, captured=True), "refunded_with_seat")
        self.assertEqual(self.s([self.SUB], "REFUNDED", refunds=1, captured=True), "refunded_with_seat")

    def test_4_voided_with_seat(self):
        self.assertEqual(self.s([self.HOLD], "VOIDED", authorized=True), "voided_with_seat")

    def test_5_substituted(self):
        """§4 #5 conf_sub = 1 · CAPTURED만 정상"""
        self.assertEqual(self.s([self.SUB], "CAPTURED", captured=True), "substituted")
        for st in ("CLOSED", "REGISTERED", "AUTHORIZED", "EXPIRED"):
            self.assertEqual(self.s([self.SUB], st), "substituted_unpaid", st)
        self.assertEqual(self.s([self.SUB], None), "substituted_unpaid", "원장 없음")

    def test_6_seat_paid(self):
        self.assertEqual(self.s([self.HOLD], "CAPTURED", captured=True), "seat_paid")

    def test_7_seat_moneyless(self):
        """§4 #7 그 밖 pg_state · 원장 없음 — #14(no_ledger)보다 먼저"""
        for st in ("AUTHORIZED", "EXPIRED", "CLOSED", "REGISTERED", None):
            self.assertEqual(self.s([self.HOLD], st), "seat_moneyless", st)

    def test_non_confirmed_res_ignored(self):
        """§4 conf = status CONFIRMED 수만"""
        self.assertEqual(self.s([self.CANC], "REFUNDED", refunds=1, captured=True), "refunded")
        self.assertEqual(self.s([self.CANC, self.HOLD], "CAPTURED", captured=True), "seat_paid")

    def test_8_to_13(self):
        self.assertEqual(self.s([], "REFUNDED", refunds=1, captured=True), "refunded")
        self.assertEqual(self.s([], "VOIDED", authorized=True), "voided")
        self.assertEqual(self.s([], "CLOSED"), "closed_unpaid")
        self.assertEqual(self.s([], "CAPTURED", captured=True), "orphan")
        self.assertEqual(self.s([], "AUTHORIZED", authorized=True), "auth_open")
        self.assertEqual(self.s([], "EXPIRED", authorized=True), "auth_open")
        self.assertEqual(self.s([], "REGISTERED"), "open_unpaid")

    def test_14_15(self):
        self.assertEqual(self.s([], None, intent=True), "no_ledger")
        self.assertEqual(self.s([], "CAPTURED", captured=True, intent=False), "orphan",
                         "§4 #11이 #15보다 먼저 — 원장 CAPTURED · intent 없음은 orphan")
        self.assertEqual(self.s([], "REGISTERED", intent=False), "open_unpaid", "§4 #13이 #15보다 먼저")
        self.assertEqual(self.s([], "CLOSED", authorized=True, intent=False), "no_intent",
                         "§4 #15 intent 없는 CLOSED + 결제 흔적")
        self.assertEqual(self.s([], "CLOSED", captured=True, intent=False), "no_intent")
        self.assertEqual(self.s([], "WEIRD", intent=True), "no_intent", "§4 #15 알 수 없는 pg_state")
        self.assertEqual(self.s([], "REGISTERED", intent=False), "open_unpaid", "§4 원장에만 있는 uid는 8~13줄")

    def test_11_12_closed_with_trace(self):
        """§4 #11 · #12 CLOSED + 결제 흔적 + intent 있음 → orphan · auth_open"""
        self.assertEqual(self.s([], "CLOSED", captured=True, intent=True), "orphan")
        self.assertEqual(self.s([], "CLOSED", captured=True, authorized=True, intent=True), "orphan",
                         "captured 참이면 #11(authorized 무관)")
        self.assertEqual(self.s([], "CLOSED", authorized=True, intent=True), "auth_open")


# =====================================================================================
# C. end_states — §4 (DB + 원장 → uid별 End)
# =====================================================================================

def ledger_entry(uid, state, captured=False, authorized=False, refunds=0, t=T0, user=None):
    return {"uid": uid, "userId": user, "amount": 10000, "deadline": iso(t + 600000), "state": state,
            "registeredAt": iso(t + 10), "authorizedAt": iso(t + 20) if authorized else None,
            "capturedAt": iso(t + 20) if captured else None,
            "closedAt": iso(t + 40000) if state in ("REFUNDED", "VOIDED", "CLOSED") else None,
            "refunds": refunds, "tombstone": False}


def intent_row(uid, sid, state, rid=None, closed_reason=None, result_code=None):
    return {"uid": uid, "hold_id": 7 + sid, "user_id": USER_BASE + sid, "schedule_id": 1, "seat_id": SPARE + sid + 1,
            "section": "A", "pay_by_ms": T0 + 600000, "state": state, "result_code": result_code,
            "reservation_id": rid, "closed_reason": closed_reason, "created_ms": T0 + 12}


def res_row(rid, uid, sid, status="CONFIRMED", origin="HOLD", seat=None, intent_id="__uid__"):
    return {"id": rid, "schedule_id": 1, "seat_id": seat if seat is not None else SPARE + sid + 1,
            "user_id": USER_BASE + sid, "payment_uid": uid, "status": status,
            "intent_id": uid if intent_id == "__uid__" else intent_id, "origin": origin, "created_ms": T0 + 30}


def task_row(tid, uid, typ, source, status, result, created, done, attempts=1):
    return {"id": tid, "uid": uid, "type": typ, "source": source, "status": status, "attempts": attempts,
            "next_attempt_ms": created, "lease_until_ms": None, "last_result": result, "created_ms": created,
            "done_ms": done}


def rej_row(rid, uid, code, http, ms):
    return {"id": rid, "uid": uid, "code": code, "http_status": http, "rejected_ms": ms}


class TestEndStates(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome17")

    def test_union_and_fields(self):
        """§4 DB intent 또는 원장에 나오는 모든 uid · res는 intent_id = uid 기준 · task는 type별"""
        a, b, c = uid_of(0), uid_of(1), uid_of(9)
        db = {"t_ms": T0 + 900000,
              "payment_intent": [intent_row(a, 0, "CONFIRMED", rid=100), intent_row(b, 1, "REJECTED")],
              "reservation": [res_row(100, a, 0),
                              res_row(200, b, 1, intent_id=None)],  # 옛 경로 — intent_id null이면 b의 res 아님
              "confirm_rejection": [rej_row(1, b, "HOLD_EXPIRED", 409, T0 + 700000)],
              "compensation_task": [task_row(1, b, "CLOSE", "REJECTION", "DONE", "REFUNDED", T0 + 700000, T0 + 712000, 2)]}
        led = {"payments": [ledger_entry(a, "CAPTURED", captured=True),
                            ledger_entry(b, "REFUNDED", captured=True, refunds=1),
                            ledger_entry(c, "CAPTURED", captured=True)],
               "ops": {"register": 3, "pay": 3, "authorize": 0, "capture": 0, "close": 1}}
        es = O.end_states(db, led)
        self.assertEqual(set(es), {a, b, c})
        self.assertEqual(es[a]["shape"], "seat_paid")
        self.assertEqual([x["id"] for x in es[a]["res"]], [100])
        self.assertEqual(es[b]["res"], [], "§4 res = reservation.intent_id = uid인 행만")
        self.assertEqual(es[b]["shape"], "refunded")
        self.assertEqual(es[b]["refunds"], 1)
        self.assertTrue(es[b]["captured"])
        self.assertIsNotNone(es[b]["close"])
        self.assertEqual(es[b]["close"]["source"], "REJECTION")
        self.assertEqual(es[b]["close"]["attempts"], 2)
        self.assertIsNone(es[b]["capture"])
        self.assertEqual(es[b]["rejection"]["code"], "HOLD_EXPIRED")
        self.assertEqual(es[b]["rejection"]["http_status"], 409)
        self.assertEqual(es[c]["shape"], "orphan")
        self.assertIsNone(es[c]["intent_state"])

    def test_no_ledger(self):
        u = uid_of(3)
        db = {"t_ms": T0, "payment_intent": [intent_row(u, 3, "OPEN")], "reservation": [],
              "confirm_rejection": [], "compensation_task": []}
        es = O.end_states(db, {"payments": [], "ops": {}})
        self.assertEqual(es[u]["shape"], "no_ledger")
        self.assertIsNone(es[u]["pg_state"])

    def test_authorized_flag(self):
        u = uid_of(0)
        db = {"t_ms": T0, "payment_intent": [intent_row(u, 0, "REJECTED")], "reservation": [],
              "confirm_rejection": [], "compensation_task": []}
        es = O.end_states(db, {"payments": [ledger_entry(u, "VOIDED", authorized=True)], "ops": {}})
        self.assertTrue(es[u]["authorized"])
        self.assertFalse(es[u]["captured"])
        self.assertEqual(es[u]["shape"], "voided")


# =====================================================================================
# D. read_sessions — §3
# =====================================================================================

def k6line(obj, level="info"):
    return json.dumps({"level": level, "msg": json.dumps(obj) if not isinstance(obj, str) else obj,
                       "source": "console", "time": "2026-11-14T00:00:00Z"})


def write_gz(path, lines, trailing_newline=True):
    text = "\n".join(lines) + ("\n" if trailing_newline and lines else "")
    with gzip.open(path, "wt", encoding="utf-8") as f:
        f.write(text)


class TestReadSessions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome17")

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="blind17-rs-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_missing(self):
        """§9.1 파일 없음 = None"""
        self.assertIsNone(O.read_sessions(self.tmp / "sessions17.log.gz"))

    def test_clean(self):
        p = self.tmp / "sessions17.log.gz"
        write_gz(p, [k6line(row(k)) for k in CYCLE])
        rows, broken = O.read_sessions(p)
        self.assertEqual((len(rows), broken), (5, 0))
        self.assertEqual(sorted(r["sid"] for r in rows), [0, 1, 2, 3, 4])

    def test_broken_kinds(self):
        """§3 msg 비JSON · k ≠ s · level error = 깨진 줄"""
        p = self.tmp / "sessions17.log.gz"
        write_gz(p, [k6line(row("ok")), k6line("not json {"), k6line({"k": "x", "v": 1}),
                     json.dumps({"level": "error", "msg": "TypeError: boom", "source": "console", "time": "t"}),
                     k6line(row("late"))])
        rows, broken = O.read_sessions(p)
        self.assertEqual(len(rows), 2)
        self.assertEqual(broken, 3)

    def test_torn_last_line_not_counted(self):
        """§3 파일 맨 끝 찢긴 한 줄은 세지 않는다"""
        p = self.tmp / "sessions17.log.gz"
        full = k6line(row("late"))
        write_gz(p, [k6line(row("ok")), full[: len(full) // 2]], trailing_newline=False)
        rows, broken = O.read_sessions(p)
        self.assertEqual((len(rows), broken), (1, 0))

    def test_torn_middle_line_counted(self):
        p = self.tmp / "sessions17.log.gz"
        full = k6line(row("late"))
        write_gz(p, [k6line(row("ok")), full[: len(full) // 2], k6line(row("noconf", end="noconf"))])
        rows, broken = O.read_sessions(p)
        self.assertEqual((len(rows), broken), (2, 1))


# =====================================================================================
# E. classify — §5 결정표
# =====================================================================================

class TestClassify(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome17")

    def c(self, r, end, cand=None, kills=(), plan=None):
        cand = cand or r["cand"]
        plan = plan or plan_obj(cand=cand, cell=r["cell"], rep=r["rep"])
        return O.classify(r, end, cand, list(kills), plan)

    # ---- A. 행 정합 ----
    def test_A_row_bad(self):
        """§5 A · §3.2 — 어기면 invalid:row-bad"""
        cases = {}
        r = row("ok", end="hold", h=H(st=409, code="X", ok=False)); r["p"] = Pp(None, ok=False, st=500)
        cases["hold에 p 있음"] = r
        r = row("ok"); r["c1"] = None
        cases["done인데 c1 없음"] = r
        r = row("retry"); r["c2"] = None
        cases["retry done인데 c2 없음"] = r
        r = row("ok"); r["c2"] = C200(100)
        cases["retry 아닌데 c2 있음"] = r
        r = row("abandon", end="abandon"); r["g"] = Gg()
        cases["abandon에 g 있음"] = r
        r = row("noconf", end="noconf"); r["c1"] = C200(102)
        cases["noconf에 c1 있음"] = r
        r = row("ok", end="noconf")
        cases["end noconf인데 kind ok"] = r
        r = row("noconf", end="abandon")
        cases["end abandon인데 kind noconf"] = r
        r = row("abandon", end="done")
        cases["end done인데 kind abandon"] = r
        r = row("ok", end="pay_start", p=Pp(None, st=409, code="HOLD_EXPIRED", ok=False)); r["uid"] = uid_of(0)
        cases["pay_start인데 uid 있음"] = r
        r = row("ok"); r["at"] = None
        cases["done인데 at 없음"] = r
        r = row("ok"); r["kind"] = "late"
        cases["kind ≠ kind_cycle[sid mod len]"] = r
        r = row("ok"); r["rep"] = 2
        cases["rep ≠ plan"] = r
        r = row("ok"); r["cand"] = "D"
        cases["cand ≠ plan"] = r
        r = row("ok"); r["cell"] = "pg"
        cases["cell ≠ plan"] = r
        for name, r in cases.items():
            with self.subTest(name):
                end = E("seat_paid", res=[{"id": 100, "status": "CONFIRMED", "origin": "HOLD"}])
                self.assertEqual(O.classify(r, end, "R", [], plan_obj("R", "base", 1)), "invalid:row-bad", name)

    def test_A_kind_cycle_mod(self):
        """§3.1 kind = kind_cycle[sid mod len] — sid 5는 ok"""
        r = row("ok", sid=5, c1=C200(105))
        end = E("seat_paid", res=[{"id": 105, "status": "CONFIRMED", "origin": "HOLD"}])
        self.assertEqual(self.c(r, end, plan=plan_obj(n_total=10)), "success")

    # ---- B. 앱 죽음 ----
    def test_B_confirm_in_kill_window(self):
        """§5 B 마지막 응답(c1) st 0 · ≥ 500 + kill 창 겹침 → app_dead"""
        kills = [(T0 + 20, T0 + 40)]
        for st, ec in ((0, 0), (0, 1050), (500, 0), (503, 0)):
            r = row("ok", cell="kill", c1=C(st, ec=ec))
            self.assertEqual(self.c(r, E("orphan"), kills=kills), "app_dead", f"c1 {st}/{ec}")

    def test_B_hold_and_pay_start(self):
        kills = [(T0 - 100, T0 + 100)]
        r = row("ok", end="hold", cell="kill", h=H(st=0, ec=0, ok=False))
        self.assertEqual(self.c(r, None, kills=kills), "app_dead", "hold st 0 in window")
        r = row("ok", end="pay_start", cell="kill", p=Pp(None, st=502, code="NOCODE", ok=False))
        self.assertEqual(self.c(r, None, kills=kills), "app_dead", "pay_start 502 in window")

    def test_B_boundaries(self):
        """§5 B 겹침 = ts ≤ hi 이고 tr ≥ lo(닫힌 구간)"""
        ts, tr = T0 + 30, T0 + 35
        for name, kills, want in (("ts == hi", [(T0, ts)], "app_dead"),
                                  ("tr == lo", [(tr, T0 + 99)], "app_dead"),
                                  ("ts > hi", [(T0, ts - 0.5)], "mode_fail:confirm-5xx"),
                                  ("tr < lo", [(tr + 0.5, T0 + 99)], "mode_fail:confirm-5xx")):
            with self.subTest(name):
                r = row("ok", cell="kill", c1=C(503, ts=ts, tr=tr))
                self.assertEqual(self.c(r, E("orphan"), kills=kills), want, name)

    def test_B_requires_failure_status(self):
        """§5 B 응답이 4xx(거부)면 창 안이어도 app_dead 아님 → D표"""
        r = row("late", cell="kill", c1=C(409, "HOLD_EXPIRED"))
        self.assertEqual(self.c(r, E("refunded"), kills=[(T0, T0 + 99)]), "wronged_refunded")

    def test_B_no_kills(self):
        r = row("ok", c1=C(503))
        self.assertEqual(self.c(r, E("orphan")), "mode_fail:confirm-5xx")

    def test_B_pg_response_excluded(self):
        """§5 B PG 응답 g는 앱 호출이 아니라 제외 — end pg는 app_dead 아님 → C pg 갈래"""
        r = row("ok", end="pg", cell="kill", g=Gg(st=0, code="NOCODE", state=None, ok=False))
        self.assertEqual(self.c(r, E("open_unpaid"), kills=[(T0, T0 + 99)]), "invalid:pg-0-NOCODE")

    def test_B_retry_last_is_c2(self):
        """§5 B done이면 c2 또는 c1 — retry는 c2가 마지막"""
        kills = [(T0 + 50, T0 + 60)]
        r = row("retry", cell="kill", c1=C200(104, seat=10), c2=C(0, ts=T0 + 52, tr=T0 + 55))
        self.assertEqual(self.c(r, E("seat_paid", res=[{"id": 104, "status": "CONFIRMED", "origin": "HOLD"}]),
                                kills=kills), "app_dead")
        # c1이 창 안 503이어도 c2(마지막)가 창 밖 200이면 app_dead 아님 → D 재생 대조 → 불일치
        r = row("retry", cell="kill", c1=C(503, ts=T0 + 52, tr=T0 + 55), c2=C(200, rid=104, rs="CONFIRMED", ts=T0 + 70))
        self.assertEqual(self.c(r, E("seat_paid", res=[{"id": 104, "status": "CONFIRMED", "origin": "HOLD"}]),
                                kills=kills), "integrity:retry-replay-mismatch")

    def test_B_not_applied_abandon_noconf(self):
        """§5 B end가 abandon · noconf · pg면 마지막 응답 객체가 없다 → B 미적용 · C로"""
        kills = [(T0 - 10 ** 9, T0 + 10 ** 9)]
        r = row("abandon", end="abandon", cell="kill")
        r["p"]["st"] = 200
        self.assertEqual(self.c(r, E("closed_unpaid"), kills=kills), "not_paid")
        r = row("noconf", end="noconf", cell="kill")
        self.assertEqual(self.c(r, E("refunded"), kills=kills), "wronged_refunded")
        r = row("ok", end="pg", cell="kill", g=Gg(st=503, code="NOCODE", state=None, ok=False))
        self.assertEqual(self.c(r, E("open_unpaid"), kills=kills), "invalid:pg-503-NOCODE")

    def test_C_end_none_strings(self):
        """§5 C end가 None이면 <shape> 자리에 none"""
        self.assertEqual(self.c(row("abandon", end="abandon"), None), "integrity:abandon-none")
        self.assertEqual(self.c(row("noconf", end="noconf"), None), "integrity:noconf-none")
        self.assertEqual(self.c(row("ok", end="pg", g=Gg(st=409, code="DEADLINE_PASSED", state=None, ok=False)), None),
                         "integrity:pg-deadline-none")
        self.assertEqual(self.c(row("ok", c1=C200(100)), None), "integrity:200-none")
        self.assertEqual(self.c(row("late", c1=C(409, "HOLD_EXPIRED")), None), "integrity:reject-none")

    # ---- C. hold ----
    def test_C_hold(self):
        """§5 C hold 4줄"""
        for h, want in ((H(st=0, ec=1050, code="NOCODE", ok=False), "mode_fail:hold-timeout"),
                        (H(st=0, ec=0, code="NOCODE", ok=False), "invalid:hold-code0"),
                        (H(st=0, ec=1211, code="NOCODE", ok=False), "invalid:hold-code0"),
                        (H(st=500, code="NOCODE", ok=False), "mode_fail:hold-5xx"),
                        (H(st=503, code="X", ok=False), "mode_fail:hold-5xx"),
                        (H(st=409, code="SEAT_ALREADY_HELD", ok=False), "invalid:hold-409-SEAT_ALREADY_HELD")):
            with self.subTest(want):
                self.assertEqual(self.c(row("ok", end="hold", h=h), None), want)

    # ---- C. pay_start ----
    def test_C_pay_start_pre_reject(self):
        """§5 C pay_start 거부 쌍 + shape ∈ {None, closed_unpaid, open_unpaid} → pre_reject"""
        for st, code in ((409, "HOLD_EXPIRED"), (404, "HOLD_NOT_FOUND")):
            for end in (None, E("closed_unpaid"), E("open_unpaid")):
                with self.subTest((st, code, end and end["shape"])):
                    r = row("ok", end="pay_start", p=Pp(None, st=st, code=code, ok=False))
                    self.assertEqual(self.c(r, end), "pre_reject")

    def test_C_pay_start_integrity(self):
        r = row("ok", end="pay_start", p=Pp(None, st=409, code="HOLD_EXPIRED", ok=False))
        self.assertEqual(self.c(r, E("refunded")), "integrity:pre-reject-refunded")
        self.assertEqual(self.c(r, E("seat_paid")), "integrity:pre-reject-seat_paid")

    def test_C_pay_start_other(self):
        for p, want in ((Pp(None, st=503, code="PG_UNAVAILABLE", ok=False), "mode_fail:pay-start-503"),
                        (Pp(None, st=200, code="OK", pws="x", ok=False), "mode_fail:pay-start-body"),
                        (Pp(None, st=0, ec=1050, code="NOCODE", ok=False), "mode_fail:pay-start-timeout"),
                        (Pp(None, st=0, ec=0, code="NOCODE", ok=False), "invalid:pay-start-code0"),
                        (Pp(None, st=409, code="SEAT_NOT_HELD", ok=False), "mode_fail:pay-start-409-SEAT_NOT_HELD"),
                        (Pp(None, st=500, code="NOCODE", ok=False), "mode_fail:pay-start-500-NOCODE"),
                        (Pp(None, st=403, code="HOLD_NOT_OWNED", ok=False), "mode_fail:pay-start-403-HOLD_NOT_OWNED")):
            with self.subTest(want):
                self.assertEqual(self.c(row("ok", end="pay_start", p=p), None), want)

    # ---- C. abandon ----
    def test_C_abandon(self):
        r = row("abandon", end="abandon")
        self.assertEqual(self.c(r, E("closed_unpaid")), "not_paid")
        self.assertEqual(self.c(r, E("open_unpaid")), "integrity:abandon-open_unpaid")
        self.assertEqual(self.c(r, E("orphan")), "integrity:abandon-orphan")

    # ---- C. pg ----
    def test_C_pg(self):
        dl = Gg(st=409, code="DEADLINE_PASSED", state=None, ok=False)
        r = row("ok", end="pg", g=dl)
        self.assertEqual(self.c(r, E("closed_unpaid")), "pre_reject")
        self.assertEqual(self.c(r, E("open_unpaid")), "pre_reject")
        self.assertEqual(self.c(r, E("orphan")), "integrity:pg-deadline-orphan")
        self.assertEqual(self.c(r, E("refunded")), "integrity:pg-deadline-refunded")
        for g, want in ((Gg(st=0, code="NOCODE", state=None, ok=False), "invalid:pg-0-NOCODE"),
                        (Gg(st=503, code="NOCODE", state=None, ok=False), "invalid:pg-503-NOCODE"),
                        (Gg(st=409, code="ALREADY_CLOSED", state=None, ok=False), "invalid:pg-409-ALREADY_CLOSED"),
                        (Gg(st=200, code="OK", state="REGISTERED", ok=False), "invalid:pg-200-OK")):
            with self.subTest(want):
                self.assertEqual(self.c(row("ok", end="pg", g=g), E("open_unpaid")), want)

    # ---- C. noconf ----
    def test_C_noconf(self):
        r = row("noconf", end="noconf")
        for shape, want in (("refunded", "wronged_refunded"), ("voided", "wronged_voided"),
                            ("orphan", "wronged_open"), ("auth_open", "wronged_open"),
                            ("seat_paid", "integrity:noconf-seat_paid"),
                            ("seat_moneyless", "integrity:noconf-seat_moneyless"),
                            ("substituted", "integrity:noconf-substituted"),
                            ("closed_unpaid", "integrity:noconf-closed_unpaid")):
            with self.subTest(shape):
                self.assertEqual(self.c(r, E(shape)), want)

    # ---- D. done ----
    def test_D_1_success(self):
        r = row("ok", c1=C200(100))
        self.assertEqual(self.c(r, E("seat_paid")), "success")

    def test_D_2_moneyless_D(self):
        r = row("ok", cand="D", c1=C200(100))
        self.assertEqual(self.c(r, E("seat_moneyless")), "success")

    def test_D_3_mismatch(self):
        """§5 D #3 rid 다름 · R · S의 moneyless"""
        self.assertEqual(self.c(row("ok", c1=C200(999)), E("seat_paid")), "integrity:200-mismatch")
        self.assertEqual(self.c(row("ok", c1=C200(100)), E("seat_moneyless")), "integrity:200-mismatch")
        self.assertEqual(self.c(row("ok", cand="S", c1=C200(100)), E("seat_moneyless")), "integrity:200-mismatch")
        self.assertEqual(self.c(row("ok", cand="D", c1=C200(999)), E("seat_moneyless")), "integrity:200-mismatch")
        end = E("seat_paid", res=[{"id": 100, "status": "CANCELLED", "origin": "HOLD"},
                                  {"id": 101, "status": "CONFIRMED", "origin": "HOLD"}])
        self.assertEqual(self.c(row("ok", c1=C200(100)), end), "integrity:200-mismatch", "rid = CONFIRMED 예약 id여야")

    def test_D_4_200_other(self):
        for shape in ("refunded", "orphan", "closed_unpaid", "substituted", "voided"):
            with self.subTest(shape):
                self.assertEqual(self.c(row("ok", c1=C200(100)), E(shape)), f"integrity:200-{shape}")

    def test_D_5_wronged_refunded(self):
        for cand in ("R", "S"):
            for st, code in REJ_PAIRS:
                with self.subTest((cand, st, code)):
                    r = row("late", cand=cand, c1=C(st, code))
                    self.assertEqual(self.c(r, E("refunded")), "wronged_refunded")

    def test_D_6_wronged_voided(self):
        for st, code in REJ_PAIRS:
            r = row("late", cand="D", c1=C(st, code))
            self.assertEqual(self.c(r, E("voided")), "wronged_voided")

    def test_D_7_wronged_substituted(self):
        cp = {"type": "SUBSTITUTE", "status": "DONE", "taskId": None, "rid": 900, "seat": 1}
        r = row("late", cand="S", c1=C(409, "HOLD_EXPIRED", cp=cp))
        self.assertEqual(self.c(r, E("substituted")), "wronged_substituted")

    def test_D_9_reject_integrity(self):
        """§5 D #9 — 5~7의 cand · 응답 조건 불일치 포함"""
        cp_ok = {"type": "SUBSTITUTE", "status": "DONE", "taskId": None, "rid": 900, "seat": 1}
        cases = (("R의 voided", "R", None, "voided", "integrity:reject-voided"),
                 ("D의 refunded", "D", None, "refunded", "integrity:reject-refunded"),
                 ("S의 voided", "S", None, "voided", "integrity:reject-voided"),
                 ("R의 substituted", "R", cp_ok, "substituted", "integrity:reject-substituted"),
                 ("S · cp 없음", "S", None, "substituted", "integrity:reject-substituted"),
                 ("S · cp type CLOSE", "S", dict(cp_ok, type="CLOSE"), "substituted", "integrity:reject-substituted"),
                 ("S · cp.rid 다름", "S", dict(cp_ok, rid=901), "substituted", "integrity:reject-substituted"),
                 ("seat_paid", "R", None, "seat_paid", "integrity:reject-seat_paid"),
                 ("closed_unpaid", "R", None, "closed_unpaid", "integrity:reject-closed_unpaid"),
                 ("seat_moneyless D", "D", None, "seat_moneyless", "integrity:reject-seat_moneyless"))
        for name, cand, cp, shape, want in cases:
            with self.subTest(name):
                r = row("late", cand=cand, c1=C(409, "HOLD_EXPIRED", cp=cp))
                self.assertEqual(self.c(r, E(shape)), want)

    def test_D_8_wronged_open(self):
        for cand in ("R", "D", "S"):
            for shape in ("orphan", "auth_open"):
                with self.subTest((cand, shape)):
                    r = row("late", cand=cand, c1=C(404, "HOLD_NOT_FOUND"))
                    self.assertEqual(self.c(r, E(shape)), "wronged_open")

    def test_D_10_to_13(self):
        for c1, want in ((C(0, ec=1050), "mode_fail:confirm-timeout"),
                         (C(0, ec=0), "invalid:confirm-code0"),
                         (C(0, ec=1211), "invalid:confirm-code0"),
                         (C(500), "mode_fail:confirm-5xx"),
                         (C(503, "X"), "mode_fail:confirm-5xx"),
                         (C(409, "UID_CONFLICT"), "mode_fail:confirm-409-UID_CONFLICT"),
                         (C(403, "HOLD_NOT_OWNED"), "mode_fail:confirm-403-HOLD_NOT_OWNED"),
                         (C(404, "HOLD_EXPIRED"), "mode_fail:confirm-404-HOLD_EXPIRED")):
            with self.subTest(want):
                self.assertEqual(self.c(row("ok", c1=c1), E("orphan")), want)

    def test_D_retry(self):
        """§5 D retry는 c2를 먼저 대조(st · code · rid)"""
        end = E("seat_paid", res=[{"id": 104, "status": "CONFIRMED", "origin": "HOLD"}])
        r = row("retry", c1=C200(104), c2=C200(104))
        self.assertEqual(self.c(r, end), "success")
        r = row("retry", c1=C200(104), c2=C200(105))
        self.assertEqual(self.c(r, end), "integrity:retry-replay-mismatch")
        r = row("retry", c1=C200(104), c2=C(409, "HOLD_EXPIRED"))
        self.assertEqual(self.c(r, end), "integrity:retry-replay-mismatch")
        r = row("retry", c1=C(409, "HOLD_EXPIRED"), c2=C(409, "SEAT_NOT_HELD"))
        self.assertEqual(self.c(r, E("refunded")), "integrity:retry-replay-mismatch")
        r = row("retry", c1=C(409, "HOLD_EXPIRED"), c2=C(409, "HOLD_EXPIRED"))
        self.assertEqual(self.c(r, E("refunded")), "wronged_refunded")

    def test_expected_base_branches(self):
        """§5 기대 갈래(base · 앱 죽음 없음)"""
        cp = {"type": "SUBSTITUTE", "status": "DONE", "taskId": None, "rid": 900, "seat": 1}
        exp = [("R", "late", "refunded", None, "wronged_refunded"),
               ("D", "late", "voided", None, "wronged_voided"),
               ("S", "late", "substituted", cp, "wronged_substituted"),
               ("S", "late", "refunded", None, "wronged_refunded")]
        for cand, kind, shape, cpx, want in exp:
            r = row(kind, cand=cand, c1=C(409, "HOLD_EXPIRED", cp=cpx))
            self.assertEqual(self.c(r, E(shape)), want, (cand, kind, shape))
        for cand, shape, want in (("R", "refunded", "wronged_refunded"), ("S", "refunded", "wronged_refunded"),
                                  ("D", "voided", "wronged_voided")):
            self.assertEqual(self.c(row("noconf", end="noconf", cand=cand), E(shape)), want)

    def test_result_shape(self):
        """§5 반환값 = 범주 또는 결함 문자열"""
        r = self.c(row("ok", c1=C(418, "TEAPOT")), E("orphan"))
        self.assertTrue(r in O.CATEGORIES or r.startswith(DEFECT_PREFIXES), r)


# =====================================================================================
# F. 가짜 회차 폴더(§1) — summarize17 --rep · outcome17 CLI
# =====================================================================================

class Rep:
    """회차 폴더 내용을 dict로 들고 있다가 write()로 쓴다. 변형은 쓰기 전에 필드를 고친다."""

    def __init__(self, cand="R", cell="base", rep=1):
        self.cand, self.cell, self.rep = cand, cell, rep
        self.plan = plan_obj(cand, cell, rep)
        self.rows = []
        self.extra_lines = []  # 세션 로그에 덧붙일 원시 줄
        self.torn_tail = None
        self.intents, self.res, self.rejs, self.tasks, self.ledger = [], [], [], [], []
        self.consistency = {f"v_{k}": 0 for k in ("double_booking", "hold_and_reserved", "orphan_hold")}
        self.consistency.update({k: 0 for k in PAY_ZERO})
        self.harness = []
        self.k6_exit = 0
        self.drain_reason = "empty"
        self.inject_events = None
        self.kill = None
        self.process_start = (1000.0, 1000.0)
        self.act_end_overrides = {}
        self.act_end_rejected = None  # None = 거부 행에서 파생
        self.skip_files = set()
        self.corrupt_files = set()
        self.ops = None
        self.clock = {"before": {"offset_s": 0.0, "rtt_s": 0.002, "at": iso(T0 - 60000)},
                      "after": {"offset_s": 0.0, "rtt_s": 0.002, "at": iso(T0 + 800000)}}
        self.appcfg = app_config(cand)
        self.seed = None  # write()에서 plan으로 채움
        self.db_cpu = "12.3%"
        self._build()

    # --- 바탕(결함 없는 회차) ---
    def _build(self):
        cand, op = self.cand, PG_OP[self.cand]
        tr_rej = T0 + 700000.0      # 지연 확정 거부 시각
        t_sweep = T0 + 640000.0     # 안전망 탐지 시각
        dur = 12500.0               # 보상 완료 시간(모든 표본 같음 — p99 = 12.5 s)
        self.dur = dur
        tid = [0]

        def task(uid, typ, source, result, created, attempts=1, status="DONE", done="__auto__"):
            tid[0] += 1
            d = created + dur if done == "__auto__" else done
            self.tasks.append(task_row(tid[0], uid, typ, source, status, result, created,
                                       d if status == "DONE" else None, attempts))

        for sid, kind in enumerate(CYCLE):
            u = uid_of(sid)
            if kind in ("ok", "retry"):
                rid = 100 + sid
                c = C200(rid, seat=SPARE + sid + 1)
                self.rows.append(row(kind, sid=sid, cand=cand, cell=self.cell, rep=self.rep, c1=c,
                                     c2=copy.deepcopy(c) if kind == "retry" else None))
                self.intents.append(intent_row(u, sid, "CONFIRMED", rid=rid))
                self.res.append(res_row(rid, u, sid))
                if cand == "D":
                    self.ledger.append(ledger_entry(u, "CAPTURED", captured=True, authorized=True))
                    task(u, "CAPTURE", "CONFIRM", "CAPTURED", T0 + 40)
                else:
                    self.ledger.append(ledger_entry(u, "CAPTURED", captured=True))
            elif kind == "late":
                if cand == "S":
                    cp = {"type": "SUBSTITUTE", "status": "DONE", "taskId": None, "rid": 300, "seat": 1}
                    c1 = C(409, "HOLD_EXPIRED", cp=cp, ts=tr_rej - 5, tr=tr_rej + 5)
                else:
                    c1 = C(409, "HOLD_EXPIRED", ts=tr_rej - 5, tr=tr_rej + 5)
                self.rows.append(row(kind, sid=sid, cand=cand, cell=self.cell, rep=self.rep, c1=c1))
                self.rejs.append(rej_row(1, u, "HOLD_EXPIRED", 409, tr_rej))
                if cand == "S":
                    self.intents.append(intent_row(u, sid, "SUBSTITUTED", rid=300, result_code="HOLD_EXPIRED"))
                    self.res.append(res_row(300, u, sid, origin="SUBSTITUTE", seat=1))
                    self.ledger.append(ledger_entry(u, "CAPTURED", captured=True))
                elif cand == "D":
                    self.intents.append(intent_row(u, sid, "REJECTED", result_code="HOLD_EXPIRED"))
                    self.ledger.append(ledger_entry(u, "VOIDED", authorized=True))
                    task(u, "CLOSE", "REJECTION", "VOIDED", tr_rej)
                else:
                    self.intents.append(intent_row(u, sid, "REJECTED", result_code="HOLD_EXPIRED"))
                    self.ledger.append(ledger_entry(u, "REFUNDED", captured=True, refunds=1))
                    task(u, "CLOSE", "REJECTION", "REFUNDED", tr_rej)
            elif kind == "noconf":
                self.rows.append(row(kind, end="noconf", sid=sid, cand=cand, cell=self.cell, rep=self.rep))
                self.intents.append(intent_row(u, sid, "CLOSING", closed_reason="SWEEP", result_code="HOLD_EXPIRED"))
                if cand == "D":
                    self.ledger.append(ledger_entry(u, "VOIDED", authorized=True))
                    task(u, "CLOSE", "SWEEP", "VOIDED", t_sweep)
                else:
                    self.ledger.append(ledger_entry(u, "REFUNDED", captured=True, refunds=1))
                    task(u, "CLOSE", "SWEEP", "REFUNDED", t_sweep)
            elif kind == "abandon":
                self.rows.append(row(kind, end="abandon", sid=sid, cand=cand, cell=self.cell, rep=self.rep))
                self.intents.append(intent_row(u, sid, "CLOSING", closed_reason="SWEEP", result_code="HOLD_EXPIRED"))
                self.ledger.append(ledger_entry(u, "CLOSED"))
                task(u, "CLOSE", "SWEEP", "CLOSED", t_sweep)
        if self.cell == "pg":
            self.tasks[-1]["attempts"] = 2  # 주입 흔적(§7 inject-not-observed 반대)
        if self.cell == "kill":
            self.kill = {"planned_at_s": [120, 360], "k6_t0_ms": T0 - 1000, "events": [
                {"n": 1, "planned_ms": T0 + 119000, "kill_ms": T0 + 119010, "kill_rc": 0, "start_ms": T0 + 119500,
                 "start_rc": 0, "ready_ms": T0 + 140000, "ready_ok": True, "started_at": "2026-11-14T00:01:59.5Z"},
                {"n": 2, "planned_ms": T0 + 359000, "kill_ms": T0 + 359010, "kill_rc": 0, "start_ms": T0 + 359500,
                 "start_rc": 0, "ready_ms": T0 + 380000, "ready_ok": True, "started_at": "2026-11-14T00:05:59.5Z"}]}
            self.process_start = (1000.0, 1360.0)
        faults = self.plan["pg"]["faults"]
        self.inject_events = [
            {"at": "reset", "t_ms": T0 - 5000, "rc": 0, "http": 204, "request": None, "response": None},
            {"at": "faults", "t_ms": T0 - 4000, "rc": 0, "http": 200, "request": copy.deepcopy(faults),
             "response": copy.deepcopy(faults)}]

    # --- 파생 값 ---
    def done_metric(self):
        out = {}
        for t in self.tasks:
            if t["status"] == "DONE":
                k = f"{t['type']}:{t['last_result']}"
                out[k] = out.get(k, 0) + 1
        return out

    def act(self, point):
        if point == "before":
            done, rej, retry = {}, {}, {}
        else:
            done = self.done_metric()
            rej = {}
            for r in self.rejs:
                rej[r["code"]] = rej.get(r["code"], 0) + 1
            retry = {}
            for t in self.tasks:
                if t["attempts"] > 1:
                    retry[t["type"]] = retry.get(t["type"], 0) + t["attempts"] - 1
        a = {"t": iso(T0), "t_end": iso(T0 + 1), "base": "http://app:8080",
             "process_start_s": self.process_start[0] if point == "before" else self.process_start[1],
             "compensation": {"done": done, "retry": retry, "dead": {}, "unresolved": 0,
                              "oldest_pending_seconds": 0, "record_failed": 0, "sweep_failed": 0},
             "confirm": {"legacy": 0, "uid_conflict": 0, "rejected": rej, "failed": {},
                         "pay_start": {"ok": len(self.rows)}, "extend": {}},
             "pg_register": {"ok": len(self.rows), "rejected": 0, "unavailable": 0}}
        if point == "end":
            for k, v in self.act_end_overrides.items():
                a["compensation"][k] = v
            if self.act_end_rejected is not None:
                a["confirm"]["rejected"] = self.act_end_rejected
        return a

    # --- 쓰기 ---
    def write(self, d):
        d = Path(d)
        d.mkdir(parents=True, exist_ok=True)
        pl = self.plan
        led = {"payments": sorted(self.ledger, key=lambda x: x["uid"]),
               "ops": self.ops or {"register": len(self.ledger), "pay": len(self.ledger), "authorize": 0,
                                   "capture": 0, "close": len(self.tasks)}}
        db = {"t_ms": T0 + 800000, "payment_intent": sorted(self.intents, key=lambda x: x["uid"]),
              "reservation": sorted(self.res, key=lambda x: x["id"]), "confirm_rejection": self.rejs,
              "compensation_task": self.tasks}
        cons = dict(self.consistency, pay_intents=len(self.intents),
                    pay_open_intents=sum(1 for i in self.intents if i["state"] == "OPEN"), pay_pending_tasks=0,
                    pay_moneyless_reservations=0, pay_substitute_reservations=sum(1 for r in self.res if r["origin"] == "SUBSTITUTE"),
                    pay_compensation_mode=self.consistency.get("pay_compensation_mode", CAND_MODE[self.cand]))
        k6sum = {"metrics": {}, "adr017": {"rep": pl["rep"], "cand": pl["cand"], "cell": pl["cell"], "pg_op": pl["pg"]["op"],
                                          "rate": pl["rate"], "d_arr_s": pl["d_arr_s"],
                                          "plan_n": pl["n_total"], "cycle": pl["kind_cycle"], "params": pl["params"],
                                          "spare": pl["seed"]["spare"], "pg_url": "http://mock-pg:8104"}}
        drain = {"start_ms": T0 + 700000 + 20000, "end_ms": T0 + 760000, "reason": self.drain_reason,
                 "cap_s": 600, "poll_s": 2, "polls": [{"t_ms": T0 + 750000, "pending": 0, "open": 0, "open_unheld": 0},
                                                      {"t_ms": T0 + 757000, "pending": 0, "open": 0, "open_unheld": 0}]}
        if self.seed is None:
            n = pl["seed"]["seats"]
            self.seed = {"schedules": 1, "seats": n, "available": n, "holds": 0, "reservations": 0, "quota_rows": 0,
                         "flyway": [str(v) for v in range(1, 16)]}
        files = {
            "plan.json": pl,
            "meta.json": {"cand": self.cand, "cell": self.cell, "pg_faults_seed": 1, "kill_planned_at_s": [120, 360] if self.cell == "kill" else None,
                          "mockpg_image_id": "sha256:0", "harness_hash": "0" * 64},
            "pg-ledger.json": led, "db-dump.json": db, "consistency.json": cons,
            "act17-before.json": self.act("before"), "act17-after-k6.json": self.act("after-k6"), "act17-end.json": self.act("end"),
            "drain.json": drain, "inject.json": {"cell": self.cell, "events": self.inject_events},
            "k6-summary.json": k6sum,
            "k6-window.json": {"k6_t0_epoch": (T0 - 1000) / 1000.0, "k6_end_epoch": (T0 + 700000) / 1000.0,
                               "exit": self.k6_exit, "clock_offset_ms_given": 0},
            "clock-offset.json": self.clock,
            "app-config.json": self.appcfg,
            "seed-check.json": self.seed,
        }
        if self.kill is not None:
            files["kill.json"] = self.kill
        for name, obj in files.items():
            if name in self.skip_files:
                continue
            p = d / name
            if name in self.corrupt_files:
                p.write_text('{"broken": ', encoding="utf-8")
            else:
                p.write_text(json.dumps(obj), encoding="utf-8")
        (d / "status").write_text("ok\n", encoding="utf-8")
        if "harness-reasons.txt" not in self.skip_files:
            (d / "harness-reasons.txt").write_text("".join(x + "\n" for x in self.harness), encoding="utf-8")
        if "sessions17.log.gz" not in self.skip_files:
            lines = [k6line(r) for r in self.rows] + list(self.extra_lines)
            if self.torn_tail is not None:
                lines.append(self.torn_tail)
            write_gz(d / "sessions17.log.gz", lines, trailing_newline=self.torn_tail is None)
        if self.cell == "base":
            hdr = ("metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,"
                   "scenario,service,status,subproto,tls_version,url,extra_tags")
            csv = [hdr]
            ts = int(T0 / 1000)
            for i in range(20):
                for nm in ("confirm", "pay_start"):
                    csv.append(f"http_req_duration,{ts + i},{10 + i},,,,true,,POST,{nm},HTTP/1.1,s,,200,,,http://app/{nm},")
            if "k6-requests.csv.gz" not in self.skip_files:
                with gzip.open(d / "k6-requests.csv.gz", "wt", encoding="utf-8") as f:
                    f.write("\n".join(csv) + "\n")
            if "timeline-server.jsonl" not in self.skip_files:
                tl = []
                for t_ms in range(int(T0 - 20000), int(T0 + 720000), 10000):  # §6.11 10초마다 · 창 양끝 넘어서
                    tl.append(json.dumps({"t": iso(t_ms), "load": "0.50 0.40 0.30"}))
                    tl.append(json.dumps({"Name": "seatlab-db-1", "CPUPerc": self.db_cpu, "MemUsage": "100MiB / 1GiB"}))
                    tl.append(json.dumps({"Name": "seatlab-app-1", "CPUPerc": "50.0%", "MemUsage": "500MiB / 2GiB"}))
                (d / "timeline-server.jsonl").write_text("\n".join(tl) + "\n", encoding="utf-8")
        return d


def run_cli(script, *args):
    _require_script(script)
    return subprocess.run([sys.executable, "-I", str(script), *map(str, args)], capture_output=True, text=True, timeout=120)


def last_line(s):
    lines = [x for x in s.strip().splitlines() if x.strip()]
    return lines[-1].strip() if lines else ""


class RepCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="blind17-rep-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def summarize(self, rep, name="rep1"):
        d = rep.write(self.tmp / name)
        pr = run_cli(SUMMARIZE_PY, "--rep", d)
        self.assertEqual(pr.returncode, 0, f"summarize17 --rep exit {pr.returncode} · stderr={pr.stderr[-800:]}")
        sp = d / "summary17.json"
        self.assertTrue(sp.exists(), "§9.2 --rep는 summary17.json을 쓴다")
        s = json.loads(sp.read_text(encoding="utf-8"))
        self.assertEqual(last_line(pr.stdout), s["status"], "§9.2 stdout 마지막 줄 = status")
        self.assertEqual(s["status"] == "ok", s["reasons"] == [], "§7 status ok ⇔ 사유 없음")
        if s["status"] != "ok":
            self.assertEqual(s["status"], "invalid:" + ",".join(s["reasons"]), "§7 status = invalid: + reasons(같은 순서)")
        return s

    def assertOnly(self, s, expected, why=""):
        """바탕 ok → 결함 하나 → 정확히 그 사유만(§7). 요약기 사유는 사전순(§7)이므로 목록 그대로 비교."""
        self.assertEqual(s["reasons"], sorted(expected), f"{why} → 사유 {sorted(expected)} 기대 · 실제 {s['reasons']}")

    def assertIncludes(self, s, expected, why=""):
        """연쇄 사유가 문서로 정해지지 않는 결함 — 필수 사유가 들어 있고 무효인지만."""
        for r in expected:
            self.assertIn(r, s["reasons"], f"{why} → 사유 {r} 기대 · 실제 {s['reasons']}")
        self.assertNotEqual(s["status"], "ok")

    def assertOk(self, s, why=""):
        self.assertEqual(s["status"], "ok", f"{why} 바탕(결함 없음) → ok 기대 · 실제 {s['reasons']}")


class TestRepBaseline(RepCase):
    def test_baseline_ok_each_cand_cell(self):
        """§6.10 · §6.11 바탕 값 그대로 만든 회차 9가지(후보 × 셀) → status ok · G1 통과 · §5 기대 갈래 · M17-6 수"""
        want_late = {"R": "wronged_refunded", "D": "wronged_voided", "S": "wronged_substituted"}
        want_noconf = {"R": "wronged_refunded", "D": "wronged_voided", "S": "wronged_refunded"}
        for cand in ("R", "D", "S"):
            for cell in ("base", "pg", "kill"):
                with self.subTest((cand, cell)):
                    s = self.summarize(Rep(cand, cell), name=f"{cand}-{cell}")
                    self.assertOk(s, f"{cand}/{cell}")
                    bk = s["sessions"]["by_kind"]
                    self.assertEqual(bk["ok"], {"success": 1})
                    self.assertEqual(bk["retry"], {"success": 1})
                    self.assertEqual(bk["abandon"], {"not_paid": 1})
                    self.assertEqual(bk["late"], {want_late[cand]: 1})
                    self.assertEqual(bk["noconf"], {want_noconf[cand]: 1})
                    self.assertEqual(s["sessions"]["n"], 5)
                    self.assertEqual(s["m17_6"], {"late_rejections": 1, "noconf_sweep_closes": 1})
                    self.assertTrue(s["g1"]["ok"], f"G1 바탕 통과 기대 · items={s['g1']['items']}")
                    for k in ("record_pay_ok_not_in_ledger", "record_pay_ok_no_intent", "record_ledger_paid_without_k6_pay"):
                        self.assertEqual(s["g1"]["items"].get(k), 0, f"§9.2 record-level {k}")
                    self.assertEqual((s["cand"], s["cell"], s["rep"]), (cand, cell, 1))
                    if cell == "base":
                        self.assertIsNotNone(s["g3"]["db_cpu_mean"])
                        self.assertIsNotNone(s["g3"]["confirm_p99_ms"])
                        self.assertIsNotNone(s["g3"]["pay_start_p99_ms"])

    def test_g3_null_outside_base(self):
        """§9.2 g3는 base 셀만 값 · 그 밖 null"""
        s = self.summarize(Rep("R", "pg"))
        self.assertEqual(s["g3"], {"db_cpu_mean": None, "confirm_p99_ms": None, "pay_start_p99_ms": None})

    def test_kill_block(self):
        """§9.2 kill: {events, restarted} | null"""
        s = self.summarize(Rep("R", "kill"), "k")
        self.assertEqual(s["kill"]["events"], 2)
        self.assertTrue(s["kill"]["restarted"])
        s = self.summarize(Rep("R", "base"), "b")
        self.assertIsNone(s["kill"])

    def test_meta_not_read(self):
        """§6.9 summarize17은 meta.json을 읽지 않는다 — 없어도 ok"""
        rep = Rep()
        rep.skip_files.add("meta.json")
        self.assertOk(self.summarize(rep), "meta.json 없음")


class TestRepInvalidReasons(RepCase):
    """§7 회차 무효 사유 — 바탕 ok → 결함 하나 → 그 사유만"""

    def test_plan_missing_exit2(self):
        """§7 plan-missing → 요약기 exit 2"""
        for mode in ("missing", "corrupt"):
            rep = Rep()
            (rep.skip_files if mode == "missing" else rep.corrupt_files).add("plan.json")
            d = rep.write(self.tmp / mode)
            self.assertEqual(run_cli(SUMMARIZE_PY, "--rep", d).returncode, 2, mode)

    def test_required_files_exact(self):
        """§1 필수(✔) 없음 · 깨짐 → 그 사유만"""
        mapping = {"consistency.json": ["consistency-missing"],
                   "inject.json": ["inject-missing"], "drain.json": ["drain-missing"],
                   "app-config.json": ["app-config-missing"], "seed-check.json": ["seed-check-missing"]}
        for fname, reasons in mapping.items():
            for mode in ("missing", "corrupt"):
                with self.subTest((fname, mode)):
                    rep = Rep("R", "pg")
                    (rep.skip_files if mode == "missing" else rep.corrupt_files).add(fname)
                    self.assertOnly(self.summarize(rep, name=f"{fname}-{mode}"), reasons, f"{fname} {mode}")

    def test_dump_missing_marks_sessions_invalid(self):
        """§7 dump(원장 · DB) 하나라도 없으면 모든 세션 invalid:dump-missing → session-invalid도"""
        for fname, reason in (("pg-ledger.json", "ledger-missing"), ("db-dump.json", "db-dump-missing")):
            for mode in ("missing", "corrupt"):
                with self.subTest((fname, mode)):
                    rep = Rep("R", "pg")
                    (rep.skip_files if mode == "missing" else rep.corrupt_files).add(fname)
                    s = self.summarize(rep, name=f"{fname}-{mode}")
                    self.assertIncludes(s, [reason, "session-invalid"], f"{fname} {mode}")
                    self.assertEqual(s["sessions"]["invalid_detail"], {"invalid:dump-missing": 5})
                    self.assertFalse(s["g1"]["ok"], "§9.2 g1.ok는 dump가 있어야")

    def test_act_missing(self):
        for fname in ("act17-before.json", "act17-after-k6.json", "act17-end.json"):
            for mode in ("missing", "corrupt", "error-key"):
                with self.subTest((fname, mode)):
                    rep = Rep("R", "pg")
                    if mode == "missing":
                        rep.skip_files.add(fname)
                    elif mode == "corrupt":
                        rep.corrupt_files.add(fname)
                    else:
                        orig = rep.write

                        def write(d, orig=orig, fname=fname):
                            out = orig(d)
                            (out / fname).write_text(json.dumps({"error": "actuator down"}), encoding="utf-8")
                            return out
                        rep.write = write
                    self.assertIncludes(self.summarize(rep, name=f"{fname}-{mode}"), ["act-missing"], f"{fname} {mode}")

    def test_harness_reasons_missing(self):
        rep = Rep()
        rep.skip_files.add("harness-reasons.txt")
        self.assertOnly(self.summarize(rep), ["harness-reasons-missing"])

    def test_k6_window_missing(self):
        """§6.11 k6-window.json 없음 = k6-exit-unknown(clock_offset_ms_given도 숫자가 아니게 됨 → clock-offset-bad)"""
        rep = Rep("R", "pg")
        rep.skip_files.add("k6-window.json")
        self.assertIncludes(self.summarize(rep), ["k6-exit-unknown"])

    def test_clock_offset_bad(self):
        """§6.11 before가 객체가 아니거나 clock_offset_ms_given이 숫자가 아님"""
        rep = Rep("R", "pg")
        rep.clock["before"] = None
        self.assertOnly(self.summarize(rep, "before-null"), ["clock-offset-bad"])
        rep = Rep("R", "pg")
        rep.skip_files.add("clock-offset.json")
        self.assertIncludes(self.summarize(rep, "missing"), ["clock-offset-bad"])

    def test_seed_mismatch(self):
        for k, v in (("holds", 1), ("available", 9), ("schedules", 2), ("quota_rows", 1), ("reservations", 1),
                     ("flyway", ["1", "14"])):
            with self.subTest(k):
                rep = Rep("R", "pg")
                n = rep.plan["seed"]["seats"]
                rep.seed = {"schedules": 1, "seats": n, "available": n, "holds": 0, "reservations": 0, "quota_rows": 0,
                            "flyway": [str(x) for x in range(1, 16)]}
                rep.seed[k] = v
                self.assertOnly(self.summarize(rep, k), ["seed-mismatch"], k)

    def test_compensation_mode_in_config(self):
        """§6.10 config-check — 다른 후보의 configprops → compensation-mode-mismatch(config-mismatch 동반 여부는 문서 밖)"""
        rep = Rep("D", "pg")
        rep.appcfg = app_config("R")
        self.assertIncludes(self.summarize(rep), ["compensation-mode-mismatch"])

    def test_config_hikari_and_nofile(self):
        """§6.10 hikaricp.connections.max[0] == 10 · nofile ≥ 21000"""
        for name, f in (("hikari", lambda c: c.update({"hikaricp.connections.max": [20]})),
                        ("nofile", lambda c: c.update({"app_nofile": {"soft": 1024, "hard": 1024}}))):
            with self.subTest(name):
                rep = Rep("R", "pg")
                f(rep.appcfg)
                self.assertOnly(self.summarize(rep, name), ["config-mismatch"], name)

    def test_kill_json_missing_kill_cell(self):
        rep = Rep("R", "kill")
        rep.skip_files.add("kill.json")
        self.assertIncludes(self.summarize(rep), ["kill-missing"], "kill 셀 kill.json 없음")

    def test_sessions_missing(self):
        rep = Rep("R", "pg")
        rep.skip_files.add("sessions17.log.gz")
        self.assertIncludes(self.summarize(rep), ["sessions-missing"])

    def test_sessions_broken(self):
        """§3 깨진 줄 > 0 → sessions-broken만 · 끝 찢긴 줄은 세지 않음(ok)"""
        rep = Rep()
        rep.rows = rep.rows[1:]
        rep.extra_lines += [k6line("not-json"), k6line(rep_row0 := row("ok", sid=0, c1=C200(100, seat=SPARE + 1)))]
        del rep_row0
        self.assertOnly(self.summarize(rep, "broken"), ["sessions-broken"], "중간 비JSON 줄")
        rep = Rep()
        rep.extra_lines.append(json.dumps({"level": "error", "msg": "ReferenceError: x", "source": "console", "time": "t"}))
        self.assertOnly(self.summarize(rep, "err"), ["sessions-broken"], "k6 level:error 줄")
        rep = Rep()
        rep.extra_lines.append(k6line({"k": "t", "v": 1}))
        self.assertOnly(self.summarize(rep, "kx"), ["sessions-broken"], "k ≠ s")
        rep = Rep()
        full = k6line(row("ok", sid=5))
        rep.torn_tail = full[: len(full) // 3]
        self.assertOk(self.summarize(rep, "torn"), "끝 찢긴 줄")

    def test_count_mismatch_total(self):
        """M17-3 계획 세션 수 ≠ 분류 세션 수"""
        rep = Rep()
        rep.rows = [r for r in rep.rows if r["sid"] != 3]
        rep.intents = [i for i in rep.intents if i["uid"] != uid_of(3)]
        rep.ledger = [x for x in rep.ledger if x["uid"] != uid_of(3)]
        rep.tasks = [t for t in rep.tasks if t["uid"] != uid_of(3)]
        self.assertOnly(self.summarize(rep), ["sessions-count-mismatch"], "행 4 · n_total 5")

    def test_count_mismatch_by_kind(self):
        """M17-3 종류별 — 총수는 같고 종류만 다름(sid 4 대신 sid 5 = ok)"""
        rep = Rep()
        rep.rows = [r for r in rep.rows if r["sid"] != 4]
        rep.rows.append(row("ok", sid=5, c1=C200(105, seat=SPARE + 6)))
        for coll in (rep.intents, rep.ledger):
            for x in coll:
                if x["uid"] == uid_of(4):
                    x["uid"] = uid_of(5)
        for x in rep.intents:
            if x["uid"] == uid_of(5):
                x.update(reservation_id=105, user_id=USER_BASE + 5, seat_id=SPARE + 6)
        for x in rep.res:
            if x["payment_uid"] == uid_of(4):
                x.update(id=105, payment_uid=uid_of(5), intent_id=uid_of(5), user_id=USER_BASE + 5, seat_id=SPARE + 6)
        self.assertOnly(self.summarize(rep), ["sessions-count-mismatch"], "ok 2 · retry 0 (총 5)")

    def test_session_dup(self):
        """같은 sid 두 줄 — 행 수 6 ≠ 5라 count-mismatch도(§7 행 수 조건)"""
        rep = Rep()
        rep.rows.append(copy.deepcopy(rep.rows[0]))
        self.assertOnly(self.summarize(rep), ["session-dup", "sessions-count-mismatch"], "sid 0 두 줄")

    def test_session_attrs_mismatch(self):
        for name, f in (("T", lambda r: r.update(T=r["T"] + 1e-3)),
                        ("L", lambda r: r.update(L=r["L"] - 1e-3)),
                        ("user", lambda r: r.update(user=r["user"] + 1)),
                        ("seat", lambda r: r.update(seat=r["seat"] + 1))):
            with self.subTest(name):
                rep = Rep("R", "pg")
                f(rep.rows[0])
                self.assertOnly(self.summarize(rep, name), ["session-attrs-mismatch"], name)

    def test_session_attrs_within_tolerance(self):
        """§3.1 |차| ≤ 1e-6은 같음"""
        rep = Rep("R", "pg")
        rep.rows[0]["T"] += 5e-7
        self.assertOk(self.summarize(rep))

    def test_session_invalid(self):
        """§7 session-invalid · invalid_detail"""
        rep = Rep()
        rep.rows[0] = row("ok", end="hold", sid=0, h=H(st=409, code="SEAT_ALREADY_HELD", ok=False))
        rep.intents = [i for i in rep.intents if i["uid"] != uid_of(0)]
        rep.res = [x for x in rep.res if x["payment_uid"] != uid_of(0)]
        rep.ledger = [x for x in rep.ledger if x["uid"] != uid_of(0)]
        s = self.summarize(rep)
        self.assertOnly(s, ["session-invalid"], "선점 409")
        self.assertEqual(s["sessions"]["invalid_detail"], {"invalid:hold-409-SEAT_ALREADY_HELD": 1})

    def test_row_bad_session_invalid(self):
        """§5 A · §3.2 retry done인데 c2 없음 → invalid:row-bad → session-invalid"""
        rep = Rep()
        rep.rows[4]["c2"] = None
        s = self.summarize(rep)
        self.assertOnly(s, ["session-invalid"])
        self.assertEqual(s["sessions"]["invalid_detail"], {"invalid:row-bad": 1})

    def test_consistency_mode_mismatch(self):
        rep = Rep("D")
        rep.consistency["pay_compensation_mode"] = "refund"
        self.assertOnly(self.summarize(rep), ["consistency-mode-mismatch"], "D인데 refund")

    def test_pg_not_reset(self):
        """§7 · M17-4 세션 uid가 아닌 uid(원장 · DB)"""
        rep = Rep()
        rep.ledger.append(ledger_entry("pay-warmup-1", "REGISTERED"))
        self.assertOnly(self.summarize(rep, "led"), ["pg-not-reset"], "원장 잔재 uid")
        rep = Rep()
        rep.intents.append(intent_row("pay-warmup-2", 9, "OPEN"))
        self.assertOnly(self.summarize(rep, "db"), ["pg-not-reset"], "DB 잔재 uid")

    def test_pg_reset_during_run(self):
        rep = Rep()
        rep.inject_events.append({"at": "reset", "t_ms": T0 + 300000, "rc": 0, "http": 204, "request": None, "response": None})
        rep.inject_events.append({"at": "faults", "t_ms": T0 + 300100, "rc": 0, "http": 200,
                                  "request": FAULTS_BASE, "response": FAULTS_BASE})
        self.assertOnly(self.summarize(rep), ["pg-reset-during-run"], "k6 시작 뒤 reset")

    def test_inject_mismatch(self):
        rep = Rep("R", "pg")
        resp = copy.deepcopy(FAULTS_PG)
        resp["rules"]["close"]["failRate"] = 0.2
        rep.inject_events[-1]["response"] = resp
        self.assertOnly(self.summarize(rep), ["inject-mismatch"], "적용 설정 ≠ plan pg.faults")

    def test_inject_leak(self):
        """§6.6 base · kill도 rules {} — 규칙이 남으면 inject-leak. 적용 설정이 plan(rules {})과 달라 inject-mismatch도."""
        for cell in ("base", "kill"):
            with self.subTest(cell):
                rep = Rep("R", cell)
                rep.inject_events[-1]["response"] = copy.deepcopy(FAULTS_PG)
                self.assertOnly(self.summarize(rep, cell), ["inject-leak", "inject-mismatch"], f"{cell} 규칙 남음")

    def test_inject_not_observed(self):
        """§7 pg 셀 · Σ(attempts − 1) = 0 이고 retry 합 = 0 → 무효 (둘 중 하나라도 > 0이면 아님)"""
        rep = Rep("R", "pg")
        for t in rep.tasks:
            t["attempts"] = 1
        self.assertOnly(self.summarize(rep, "none"), ["inject-not-observed"], "주입 흔적 0")
        rep = Rep("R", "pg")
        for t in rep.tasks:
            t["attempts"] = 1
        rep.act_end_overrides["retry"] = {"CLOSE": 1}
        self.assertOk(self.summarize(rep, "retry-only"), "지표 retry 1")
        rep = Rep("R", "pg")
        rep.act_end_overrides["retry"] = {}
        self.assertOk(self.summarize(rep, "attempts-only"), "attempts 2")
        rep = Rep("R", "base")
        for t in rep.tasks:
            t["attempts"] = 1
        self.assertOk(self.summarize(rep, "base"), "base 셀은 대상 아님")

    def test_kill_not_observed(self):
        """§6.7 유효 조건 각각"""
        def ev(rep):
            return rep.kill["events"]
        variants = {
            "events 1개": lambda r: ev(r).pop(),
            "kill_rc ≠ 0": lambda r: ev(r)[0].update(kill_rc=1),
            "start_rc ≠ 0": lambda r: ev(r)[1].update(start_rc=125),
            "ready_ok 거짓": lambda r: ev(r)[1].update(ready_ok=False, ready_ms=None),
            "started_at 같음": lambda r: ev(r)[1].update(started_at=ev(r)[0]["started_at"]),
            "events 0개": lambda r: ev(r).clear(),
        }
        for name, f in variants.items():
            with self.subTest(name):
                rep = Rep("R", "kill")
                f(rep)
                self.assertOnly(self.summarize(rep, name.replace(" ", "_")), ["kill-not-observed"], name)

    def test_drain_timeout(self):
        rep = Rep()
        rep.drain_reason = "cap"
        self.assertOnly(self.summarize(rep), ["drain-timeout"], "drain reason cap")

    def test_harness_reasons_order(self):
        """§7 status = invalid: + [하네스 사유(줄 순서 · 중복 하나로)] + [요약기 사유(사전순 · 하네스와 같은 것 빼고)]"""
        rep = Rep()
        rep.harness = ["warmup-failed", "dump-race"]
        s = self.summarize(rep, "h")
        self.assertEqual(s["reasons"], ["warmup-failed", "dump-race"])
        self.assertEqual(s["status"], "invalid:warmup-failed,dump-race")
        rep = Rep()
        rep.harness = ["zz-harness", "zz-harness"]
        rep.drain_reason = "cap"
        rep.consistency["pay_compensation_mode"] = "substitute"
        s = self.summarize(rep, "mix")
        self.assertEqual(s["reasons"], ["zz-harness", "consistency-mode-mismatch", "drain-timeout"])
        rep = Rep()
        rep.harness = ["drain-timeout"]
        rep.drain_reason = "cap"
        s = self.summarize(rep, "dedup")
        self.assertEqual(s["reasons"], ["drain-timeout"])

    def test_no_late_rejection(self):
        """M17-6 · §7 late 세션 c1 거부 = 0 → 무효"""
        rep = Rep()
        late = rep.rows[1]
        late["c1"] = C200(201, seat=SPARE + 2)
        rep.rejs.clear()
        rep.tasks = [t for t in rep.tasks if t["uid"] != uid_of(1)]
        rep.ledger[1] = ledger_entry(uid_of(1), "CAPTURED", captured=True)
        rep.intents[1] = intent_row(uid_of(1), 1, "CONFIRMED", rid=201)
        rep.res.append(res_row(201, uid_of(1), 1))
        s = self.summarize(rep)
        self.assertOnly(s, ["no-late-rejection"], "late 전부 성공")
        self.assertEqual(s["m17_6"]["late_rejections"], 0)
        self.assertEqual(s["sessions"]["by_kind"]["late"], {"success": 1})

    def test_no_late_rejection_counts_non_409_reject(self):
        """M17-6 late c1이 거부 쌍이면(404 HOLD_NOT_FOUND) 셈 — ok"""
        rep = Rep()
        rep.rows[1]["c1"] = C(404, "HOLD_NOT_FOUND", ts=T0 + 699995, tr=T0 + 700005)
        rep.rejs[0].update(code="HOLD_NOT_FOUND", http_status=404)
        s = self.summarize(rep)
        self.assertOk(s, "404 HOLD_NOT_FOUND도 거부 쌍")
        self.assertEqual(s["m17_6"]["late_rejections"], 1)

    def test_no_sweep_close(self):
        """M17-6 · §7 noconf uid의 CLOSE source SWEEP = 0 → 무효"""
        rep = Rep()
        for t in rep.tasks:
            if t["uid"] == uid_of(2):
                t["source"] = "CONFIRM"
        s = self.summarize(rep, "src")
        self.assertOnly(s, ["no-sweep-close"], "noconf 작업 출처 CONFIRM")
        self.assertEqual(s["m17_6"]["noconf_sweep_closes"], 0)
        rep = Rep()
        rep.tasks = [t for t in rep.tasks if t["uid"] != uid_of(2)]
        rep.ledger[2] = ledger_entry(uid_of(2), "CAPTURED", captured=True)
        s = self.summarize(rep, "none")
        self.assertOnly(s, ["no-sweep-close"], "noconf 작업 없음(abandon SWEEP만)")
        self.assertEqual(s["m17_6"]["noconf_sweep_closes"], 0)

    def test_app_metric_mismatch(self):
        """§7 kill 셀 밖 · done 합 ≠ DB DONE · rejected 합 < 거부 행"""
        rep = Rep("R", "base")
        rep.act_end_overrides["done"] = {"CLOSE:REFUNDED": 1}
        self.assertOnly(self.summarize(rep, "done"), ["app-metric-mismatch"], "done 1 · DB 3")
        rep = Rep("R", "base")
        rep.rejs.append(rej_row(2, uid_of(1), "HOLD_EXPIRED", 409, T0 + 700100))
        rep.act_end_rejected = {"HOLD_EXPIRED": 1}
        self.assertOnly(self.summarize(rep, "rej"), ["app-metric-mismatch"], "rejected 1 < 거부 행 2")
        rep = Rep("R", "base")
        rep.act_end_rejected = {"HOLD_EXPIRED": 3}
        self.assertOk(self.summarize(rep, "rej-more"), "rejected 합 > 거부 행은 허용(<만 무효)")
        rep = Rep("R", "kill")
        rep.act_end_overrides["done"] = {"CLOSE:REFUNDED": 1}
        self.assertOk(self.summarize(rep, "kill"), "kill 셀은 대조 안 함")

    def _k6sum(self, rep_obj, **upd):
        rep = rep_obj
        orig = rep.write

        def write(d):
            out = orig(d)
            ks = json.loads((out / "k6-summary.json").read_text(encoding="utf-8"))
            ks["adr017"].update(upd)
            (out / "k6-summary.json").write_text(json.dumps(ks), encoding="utf-8")
            return out
        rep.write = write
        return rep

    def test_k6_exit_and_plan_mismatch(self):
        rep = Rep()
        rep.k6_exit = 99
        self.assertOnly(self.summarize(rep, "exit"), ["k6-exit-99"], "k6 exit 99")
        for k, v in (("rep", 3), ("cand", "D"), ("cell", "pg"), ("plan_n", 6), ("cycle", ["ok"]), ("spare", 9)):
            with self.subTest(k):
                rep = self._k6sum(Rep(), **{k: v})
                self.assertOnly(self.summarize(rep, f"kpm-{k}"), ["k6-plan-mismatch"], f"adr017.{k}")

    def test_k6_rep_string_compare(self):
        """§6.8 str(rep) == str(plan.rep) — 문자열 "1"도 같음"""
        rep = self._k6sum(Rep(), rep="1")
        self.assertOk(self.summarize(rep))

    def test_k6_unchecked_keys(self):
        """§6.8 pg_op · rate · d_arr_s · params · pg_url은 기록만"""
        rep = self._k6sum(Rep(), pg_op="authorize", rate=99, d_arr_s=1, params={}, pg_url="x")
        self.assertOk(self.summarize(rep))

    def test_k6_summary_missing(self):
        rep = Rep()
        rep.skip_files.add("k6-summary.json")
        self.assertOnly(self.summarize(rep), ["k6-plan-mismatch"], "§6.8 파일 없음도 같다")

    def test_latency_missing_base_only(self):
        """§7 base 셀만 — CSV에 confirm · pay_start 200 표본 0"""
        rep = Rep("R", "base")
        rep.skip_files.add("k6-requests.csv.gz")
        self.assertOnly(self.summarize(rep, "b"), ["latency-missing"], "base CSV 없음")
        rep = Rep("R", "pg")
        self.assertOk(self.summarize(rep, "p"), "pg 셀은 G3 아님(CSV 없음)")

    def test_db_cpu_missing_base_only(self):
        rep = Rep("R", "base")
        rep.skip_files.add("timeline-server.jsonl")
        self.assertOnly(self.summarize(rep, "b"), ["db-cpu-missing"], "base timeline 없음")
        rep = Rep("R", "base")
        rep.db_cpu = "n/a"
        self.assertOnly(self.summarize(rep, "bad"), ["db-cpu-missing"], "CPUPerc 못 읽음")


class TestRepG1(RepCase):
    """§8 G1 — 기대 0 항목 각각 1건 위반(설계-6 · 설계-2)"""

    def g1(self, rep, name="r"):
        s = self.summarize(rep, name)
        return s, s["g1"]

    def test_orphan(self):
        """고아 결제 — late 거부인데 CLOSE 없음 · CAPTURED → orphan + wronged_open"""
        rep = Rep()
        rep.tasks = [t for t in rep.tasks if t["uid"] != uid_of(1)]
        rep.ledger[1] = ledger_entry(uid_of(1), "CAPTURED", captured=True)
        s, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["orphan"], 1)
        self.assertEqual(g["items"]["wronged_open"], 1)
        self.assertEqual(s["uids"].get("orphan"), 1)

    def test_double_reservation(self):
        """intent당 예약 2"""
        rep = Rep()
        rep.res.append(res_row(150, uid_of(0), 0, seat=2))
        s, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["double_reservation"], 1)

    def test_double_refund(self):
        rep = Rep()
        rep.ledger[1]["refunds"] = 2
        _, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["double_refund"], 1)

    def test_refunded_with_seat(self):
        """환불됐는데 좌석"""
        rep = Rep()
        rep.ledger[0] = ledger_entry(uid_of(0), "REFUNDED", captured=True, refunds=1)
        _, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["refunded_with_seat"], 1)

    def test_voided_with_seat(self):
        """승인 취소됐는데 좌석(D)"""
        rep = Rep("D")
        rep.ledger[0] = ledger_entry(uid_of(0), "VOIDED", authorized=True)
        _, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["voided_with_seat"], 1)

    def test_auth_open(self):
        """D 승인이 묶인 채 남음 · EXPIRED도"""
        for st in ("AUTHORIZED", "EXPIRED"):
            with self.subTest(st):
                rep = Rep("D")
                rep.tasks = [t for t in rep.tasks if t["uid"] != uid_of(2)]
                rep.ledger[2] = ledger_entry(uid_of(2), st, authorized=True)
                _, g = self.g1(rep, st)
                self.assertFalse(g["ok"])
                self.assertEqual(g["items"]["auth_open"], 1)
                self.assertEqual(g["items"]["wronged_open"], 1)

    def test_open_unpaid(self):
        rep = Rep()
        rep.tasks = [t for t in rep.tasks if t["uid"] != uid_of(3)]
        rep.ledger[3] = ledger_entry(uid_of(3), "REGISTERED")
        _, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["open_unpaid"], 1)

    def test_substituted_unpaid(self):
        rep = Rep("S")
        rep.ledger[1] = ledger_entry(uid_of(1), "CLOSED")
        _, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["substituted_unpaid"], 1)

    def test_no_ledger(self):
        rep = Rep()
        rep.ledger = [x for x in rep.ledger if x["uid"] != uid_of(3)]
        _, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["no_ledger"], 1)

    def test_no_intent(self):
        """원장 항목은 있는데 intent 없음(세션 uid라 pg-not-reset 아님).
        §4 #8~13이 먼저라 #15에 닿는 원장은 CLOSED이면서 captured · authorized 중 하나가 참인 것뿐이다."""
        rep = Rep()
        rep.intents = [i for i in rep.intents if i["uid"] != uid_of(3)]
        rep.ledger[3] = ledger_entry(uid_of(3), "CLOSED", authorized=True)
        _, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["no_intent"], 1)

    def test_seat_moneyless_R_fails_G1(self):
        """§8 R · S의 seat_moneyless도 기대 0"""
        for cand in ("R", "S"):
            with self.subTest(cand):
                rep = Rep(cand)
                rep.ledger[0] = ledger_entry(uid_of(0), "REGISTERED")
                _, g = self.g1(rep, cand)
                self.assertFalse(g["ok"])
                self.assertEqual(g["items"].get("seat_moneyless"), 1, "§9.2 g1.items.seat_moneyless(R · S만 셈)")

    def test_seat_moneyless_D_is_cost(self):
        """§8 D의 seat_moneyless는 G-비용 — G1 통과 · cost.moneyless 1"""
        rep = Rep("D")
        rep.ledger[0] = ledger_entry(uid_of(0), "AUTHORIZED", authorized=True)
        for t in rep.tasks:
            if t["uid"] == uid_of(0):
                t["last_result"] = "FAILED_EXPIRED"
        s, g = self.g1(rep)
        self.assertTrue(g["ok"], f"D moneyless는 G1 대상 아님 · items={g['items']}")
        self.assertEqual(s["cost"]["moneyless"], 1)
        self.assertEqual(g["items"].get("seat_moneyless"), 0, "§9.2 D는 0")
        self.assertEqual(s["sessions"]["by_kind"]["ok"], {"success": 1})

    def test_dead_task(self):
        rep = Rep()
        for t in rep.tasks:
            if t["uid"] == uid_of(1):
                t.update(status="DEAD", done_ms=None, attempts=8, last_result="FAILED_503")
        rep.ledger[1] = ledger_entry(uid_of(1), "CAPTURED", captured=True)
        _, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["dead_tasks"], 1)

    def test_open_after_drain(self):
        """배수 뒤 OPEN 만료 intent — 덤프로 다시 센다"""
        rep = Rep()
        rep.intents[3]["state"] = "OPEN"
        rep.intents[3]["closed_reason"] = None
        _, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["open_after_drain"], 1)

    def test_consistency_pay_each(self):
        for k in PAY_ZERO:
            with self.subTest(k):
                rep = Rep()
                rep.consistency[k] = 1
                _, g = self.g1(rep, k)
                self.assertFalse(g["ok"], k)
                self.assertEqual(g["items"]["consistency_pay"].get(k), 1)

    def test_consistency_v_sum(self):
        rep = Rep()
        rep.consistency["v_double_booking"] = 2
        rep.consistency["v_orphan_hold"] = 1
        _, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["consistency_v_sum"], 3)

    def test_report_only_consistency_not_g1(self):
        """§6.3 보고 항목(pay_open_intents 등)은 G1 아님 — 바탕 pay_substitute_reservations 1(S)인데 통과"""
        s, g = self.g1(Rep("S"))
        self.assertTrue(g["ok"])

    def test_integrity_counts(self):
        """M17-1 응답과 끝 상태 어긋남 = G1 실패 — ok 200인데 원장 REFUNDED · 예약 없음"""
        rep = Rep()
        rep.res = [x for x in rep.res if x["payment_uid"] != uid_of(0)]
        rep.intents[0]["state"] = "REJECTED"
        rep.ledger[0] = ledger_entry(uid_of(0), "REFUNDED", captured=True, refunds=1)
        s, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["integrity"], 1)
        self.assertEqual(s["sessions"]["integrity"].get("integrity:200-refunded"), 1)

    def test_mode_fail_counts(self):
        rep = Rep()
        rep.rows[0]["c1"] = C(503)
        s, g = self.g1(rep)
        self.assertFalse(g["ok"])
        self.assertEqual(g["items"]["mode_fail"], 1)
        self.assertEqual(s["sessions"]["mode_fail"].get("mode_fail:confirm-5xx"), 1)

    def test_app_dead_not_invalid_but_uid_checked(self):
        """§5 B app_dead는 무효가 아니고 uid는 G1 대상"""
        rep = Rep("R", "kill")
        ev = rep.kill["events"][0]
        rep.rows[0]["c1"] = C(0, ts=ev["kill_ms"] + 10, tr=ev["kill_ms"] + 20)
        s, g = self.g1(rep, "dead-ok")
        self.assertEqual(s["sessions"]["by_kind"]["ok"], {"app_dead": 1})
        self.assertNotIn("session-invalid", s["reasons"])
        self.assertTrue(g["ok"], "uid 끝 상태(seat_paid)는 정상")
        self.assertEqual(s["cost"]["app_dead"], 1)
        rep = Rep("R", "kill")
        ev = rep.kill["events"][0]
        rep.rows[1]["c1"] = C(0, ts=ev["kill_ms"] + 10, tr=ev["kill_ms"] + 20)
        rep.tasks = [t for t in rep.tasks if t["uid"] != uid_of(1)]
        rep.ledger[1] = ledger_entry(uid_of(1), "CAPTURED", captured=True)
        s, g = self.g1(rep, "dead-orphan")
        self.assertEqual(s["sessions"]["by_kind"]["late"], {"app_dead": 1})
        self.assertFalse(g["ok"], "app_dead 세션 uid의 orphan은 G1 실패")
        self.assertEqual(g["items"]["orphan"], 1)


class TestRepG2(RepCase):
    """§8 G2 표본 · 시간 · p99 (회차)"""

    def test_samples_and_p99(self):
        s = self.summarize(Rep("R"))
        g2 = s["g2"]
        self.assertEqual(g2["n"], 3)
        self.assertAlmostEqual(g2["p99_s"], 12.5, places=6)
        self.assertEqual(g2["by_source"]["REJECTION"]["n"], 1)
        self.assertEqual(g2["by_source"]["SWEEP"]["n"], 2)
        self.assertEqual(g2["substitute_n"], 0)

    def test_rejection_start_is_rejected_ms(self):
        """§8 REJECTION 시작 = confirm_rejection.rejected_ms(작업 created_ms 아님)"""
        rep = Rep("R")
        for t in rep.tasks:
            if t["source"] == "REJECTION":
                t["created_ms"] += 5000  # created는 늦지만 시작은 rejected_ms
        s = self.summarize(rep)
        self.assertAlmostEqual(s["g2"]["by_source"]["REJECTION"]["p99_s"], 12.5, places=6)

    def test_capture_confirm_excluded(self):
        """§8 표본 = CLOSE · DONE · source ∈ {REJECTION, SWEEP} — D의 CAPTURE(CONFIRM) 제외"""
        s = self.summarize(Rep("D"))
        self.assertEqual(s["g2"]["n"], 3)

    def test_not_done_excluded(self):
        rep = Rep("R")
        rep.tasks.append(task_row(99, uid_of(2), "CLOSE", "SWEEP", "PENDING", None, T0 + 600000, None))
        s = self.summarize(rep)
        self.assertEqual(s["g2"]["n"], 3)

    def test_substitute_separate(self):
        """§8 S 대체 성공은 표본에 넣지 않고 따로"""
        s = self.summarize(Rep("S"))
        self.assertEqual(s["g2"]["n"], 2)
        self.assertEqual(s["g2"]["substitute_n"], 1)

    def test_uniform_30(self):
        """p99 = X 정확히(표본 전부 30.0초 — p99 계산법과 무관)"""
        rep = Rep("R")
        for t in rep.tasks:
            start = rep.rejs[0]["rejected_ms"] if t["source"] == "REJECTION" else t["created_ms"]
            t["done_ms"] = start + 30000.0
        s = self.summarize(rep)
        self.assertAlmostEqual(s["g2"]["p99_s"], 30.0, places=6)

    def test_percentile_rule(self):
        """§8 백분위 = 정렬 xs[min(n − 1, ⌊n × q⌋)] — 표본 10 · 20 · 30초 → p50 20 · p90 30 · p99 30 · max 30"""
        rep = Rep("R")
        durs = {uid_of(1): 10000.0, uid_of(2): 30000.0, uid_of(3): 20000.0}
        for t in rep.tasks:
            start = rep.rejs[0]["rejected_ms"] if t["source"] == "REJECTION" else t["created_ms"]
            t["done_ms"] = start + durs[t["uid"]]
        g2 = self.summarize(rep)["g2"]
        self.assertAlmostEqual(g2["p50_s"], 20.0, places=6)
        self.assertAlmostEqual(g2["p90_s"], 30.0, places=6)
        self.assertAlmostEqual(g2["p99_s"], 30.0, places=6)
        self.assertAlmostEqual(g2["max_s"], 30.0, places=6)
        self.assertAlmostEqual(g2["by_source"]["SWEEP"]["p99_s"], 30.0, places=6)

    def test_percentile_two_samples(self):
        """n = 2: p50 = xs[1](큰 값 — 보간 없음)"""
        rep = Rep("S")
        durs = {uid_of(2): 4000.0, uid_of(3): 8000.0}
        for t in rep.tasks:
            t["done_ms"] = t["created_ms"] + durs[t["uid"]]
        g2 = self.summarize(rep)["g2"]
        self.assertAlmostEqual(g2["p50_s"], 8.0, places=6)

    def test_no_sample(self):
        """§8 표본 0 → g2.n 0"""
        rep = Rep("S")
        rep.tasks = []
        s = self.summarize(rep)
        self.assertEqual(s["g2"]["n"], 0)
        self.assertIsNone(s["g2"]["p99_s"])


class TestOutcomeCli(RepCase):
    def test_cli_output(self):
        """§9.1 CLI JSON 한 줄 · sid 순 · counts · uids(덤프의 모든 uid)"""
        d = Rep("R").write(self.tmp / "o")
        pr = run_cli(OUTCOME_PY, d)
        self.assertEqual(pr.returncode, 0, pr.stderr[-800:])
        out = json.loads(last_line(pr.stdout))
        self.assertEqual([x["sid"] for x in out["sessions"]], [0, 1, 2, 3, 4])
        self.assertEqual(out["sessions"][1]["cat"], "wronged_refunded")
        self.assertEqual(out["sessions"][1]["shape"], "refunded")
        self.assertEqual(out["counts"]["late"], {"wronged_refunded": 1})
        self.assertEqual(out["uids"], {"seat_paid": 2, "refunded": 2, "closed_unpaid": 1})

    def test_cli_uids_include_non_session(self):
        rep = Rep("R")
        rep.ledger.append(ledger_entry("stray-uid", "CAPTURED", captured=True))
        d = rep.write(self.tmp / "o2")
        out = json.loads(last_line(run_cli(OUTCOME_PY, d).stdout))
        self.assertEqual(out["uids"].get("orphan"), 1)

    def test_cli_missing_input(self):
        """§9.1 오류 = §7 사유 이름 · 검사 순서 plan → sessions → db-dump → ledger → kill(kill 셀) · 첫째 하나만"""
        cases = ((("R", "base"), {"pg-ledger.json"}, "ledger-missing"),
                 (("R", "base"), {"db-dump.json", "pg-ledger.json"}, "db-dump-missing"),
                 (("R", "base"), {"sessions17.log.gz", "db-dump.json"}, "sessions-missing"),
                 (("R", "base"), {"plan.json", "sessions17.log.gz"}, "plan-missing"),
                 (("R", "kill"), {"kill.json"}, "kill-missing"),
                 (("R", "kill"), {"kill.json", "pg-ledger.json"}, "ledger-missing"))
        for i, ((cand, cell), skip, want) in enumerate(cases):
            with self.subTest((cell, sorted(skip))):
                rep = Rep(cand, cell)
                rep.skip_files |= skip
                d = rep.write(self.tmp / f"o3-{i}")
                pr = run_cli(OUTCOME_PY, d)
                self.assertEqual(pr.returncode, 2)
                self.assertEqual(json.loads(last_line(pr.stdout)), {"error": want})


# =====================================================================================
# G. 캠페인 판정 — summarize17 --campaign (§8 · §9.2)
# =====================================================================================

CELLS = ("base", "pg", "kill")


def mk_summary(cand, cell, rep, status="ok", g1_ok=True, p99=10.0, n=5, g3=None):
    g3v = {"db_cpu_mean": None, "confirm_p99_ms": None, "pay_start_p99_ms": None}
    if cell == "base":
        g3v = dict(zip(("db_cpu_mean", "confirm_p99_ms", "pay_start_p99_ms"), g3 or (100.0, 50.0, 40.0)))
    reasons = [] if status == "ok" else status[len("invalid:"):].split(",")
    items = {k: 0 for k in G1_ZERO_SHAPES}
    items.update(dead_tasks=0, open_after_drain=0, consistency_pay={k: 0 for k in PAY_ZERO}, consistency_v_sum=0,
                 integrity=0, wronged_open=0, mode_fail=0)
    if not g1_ok:
        items["orphan"] = 1
    return {"cand": cand, "cell": cell, "rep": rep, "status": status, "reasons": reasons,
            "sessions": {"n": 5, "planned": {k: 1 for k in CYCLE}, "by_kind": {}, "invalid_detail": {},
                         "integrity": {}, "mode_fail": {}},
            "uids": {}, "g1": {"ok": g1_ok, "items": items},
            "g2": {"n": n, "p50_s": p99, "p90_s": p99, "p99_s": p99 if n else None, "max_s": p99 if n else None,
                   "by_source": {"REJECTION": {"n": n, "p99_s": p99 if n else None}, "SWEEP": {"n": 0, "p99_s": None}},
                   "substitute_n": 0},
            "g3": g3v, "m17_6": {"late_rejections": 1, "noconf_sweep_closes": 1},
            "cost": {"moneyless": 0, "substitute_ratio": None, "money_lost_s": {"n": 0, "p50": None, "p99": None, "max": None},
                     "pg_deadline_409": 0, "tasks_by_source": {}, "attempts": {}, "uid_conflict": 0, "legacy": 0, "app_dead": 0},
            "drain": {"reason": "empty", "len_s": 10}, "kill": {"events": 2, "restarted": True} if cell == "kill" else None}


class CampaignCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="blind17-camp-"))
        self.over = {}       # (cand, cell, rep) → mk_summary kwargs
        self.attempts = {}   # (cand, cell, rep) → [kwargs …] 앞 시도(오래된 것부터 attempt1, attempt2 …)
        self.fixed = True
        self.values = {}     # (cand, cell, rep) → plan values 덮어쓰기

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write_rep(self, d, cand, cell, rep, kw):
        d.mkdir(parents=True, exist_ok=True)
        s = mk_summary(cand, cell, rep, **kw)
        (d / "summary17.json").write_text(json.dumps(s), encoding="utf-8")
        (d / "status").write_text(s["status"] + "\n", encoding="utf-8")
        pl = plan_obj(cand, cell, rep, fixed=self.fixed)
        pl["values"].update(self.values.get("*", {}))
        pl["values"].update(self.values.get((cand, cell, rep), {}))
        (d / "plan.json").write_text(json.dumps(pl), encoding="utf-8")

    def run_campaign(self):
        root = self.tmp / "c-test"
        for cand in ("R", "D", "S"):
            for cell in CELLS:
                for rep in (1, 2, 3):
                    key = (cand, cell, rep)
                    for i, kw in enumerate(self.attempts.get(key, []), start=1):
                        self._write_rep(root / cand / cell / f"rep{rep}.attempt{i}", cand, cell, rep, kw)
                    if self.over.get(key) == "absent":
                        continue
                    self._write_rep(root / cand / cell / f"rep{rep}", cand, cell, rep, self.over.get(key, {}))
        pr = run_cli(SUMMARIZE_PY, "--campaign", root)
        self.assertEqual(pr.returncode, 0, pr.stderr[-800:])
        cj = json.loads((root / "campaign17.json").read_text(encoding="utf-8"))
        ll = last_line(pr.stdout)
        self.assertEqual(ll, f"choice:{cj['choice'] or 'none'}", "§9.2 stdout 마지막 줄 = choice")
        return cj

    def set_cand(self, cand, cells=CELLS, reps=(1, 2, 3), **kw):
        for cell in cells:
            for rep in reps:
                self.over[(cand, cell, rep)] = dict(self.over.get((cand, cell, rep), {}), **kw)


class TestCampaignOrder(CampaignCase):
    """§8 순서 D > R > S"""

    def test_all_pass_D(self):
        cj = self.run_campaign()
        self.assertEqual(cj["pass"], {"R": True, "D": True, "S": True})
        self.assertEqual(cj["choice"], "D")
        self.assertEqual(list(cj["order"]), ["D", "R", "S"])
        self.assertTrue(cj["plan_fixed"])

    def test_D_g1_fail_R(self):
        self.over[("D", "pg", 2)] = {"g1_ok": False}
        cj = self.run_campaign()
        self.assertFalse(cj["g1"]["D"]["ok"])
        self.assertTrue(any("D/pg/rep2" in str(x) for x in cj["g1"]["D"]["fail_runs"]), cj["g1"]["D"])
        self.assertIs(cj["pass"]["D"], False)
        self.assertEqual(cj["choice"], "R")

    def test_D_R_fail_S(self):
        self.over[("D", "base", 1)] = {"g1_ok": False}
        self.over[("R", "kill", 3)] = {"g1_ok": False}
        cj = self.run_campaign()
        self.assertEqual(cj["choice"], "S")

    def test_all_fail_none(self):
        for cand in ("R", "D", "S"):
            self.over[(cand, "base", 1)] = {"g1_ok": False}
        cj = self.run_campaign()
        self.assertIsNone(cj["choice"])
        self.assertEqual(cj["pass"], {"R": False, "D": False, "S": False})

    def test_plan_fixed_false(self):
        self.fixed = False
        self.assertFalse(self.run_campaign()["plan_fixed"])


class TestCampaignG2(CampaignCase):
    """§8 G2 base p99 ≤ X(30) · pg · kill p99 ≤ Y(120) — 경계 포함(≤)"""

    def test_base_boundary(self):
        for p, ok in ((30.0, True), (29.999, True), (30.001, False)):
            with self.subTest(p):
                self.over = {("D", "base", 2): {"p99": p}}
                cj = self.run_campaign()
                self.assertEqual(cj["g2"]["D"]["base"]["ok"], ok, f"p99 {p}")
                self.assertEqual(cj["g2"]["D"]["base"]["limit_s"], 30)
                self.assertEqual(cj["choice"], "D" if ok else "R")
                shutil.rmtree(self.tmp / "c-test", ignore_errors=True)

    def test_pg_kill_boundary(self):
        for cell in ("pg", "kill"):
            for p, ok in ((120.0, True), (119.999, True), (120.001, False)):
                with self.subTest((cell, p)):
                    self.over = {("D", cell, 3): {"p99": p}}
                    cj = self.run_campaign()
                    self.assertEqual(cj["g2"]["D"][cell]["ok"], ok)
                    self.assertEqual(cj["g2"]["D"][cell]["limit_s"], 120)
                    self.assertEqual(cj["pass"]["D"], ok)
                    shutil.rmtree(self.tmp / "c-test", ignore_errors=True)

    def test_threshold_from_plan_values(self):
        """§8 X · Y 출처 = 사용 회차 plan.json values(요약기 상수 아님) — X 60이면 p99 45 통과"""
        self.values["*"] = {"g2_x_s": 60, "g2_y_s": 200}
        self.over[("D", "base", 1)] = {"p99": 45.0}
        self.over[("D", "pg", 1)] = {"p99": 150.0}
        cj = self.run_campaign()
        self.assertEqual(cj["g2"]["D"]["base"]["limit_s"], 60)
        self.assertEqual(cj["g2"]["D"]["pg"]["limit_s"], 200)
        self.assertTrue(cj["g2"]["D"]["base"]["ok"])
        self.assertEqual(cj["choice"], "D")

    def test_threshold_differs_between_runs(self):
        """§8 사용 회차 사이 X 값이 다르면(M17-2 위반) 모든 후보 insufficient · choice null"""
        self.values[("S", "pg", 2)] = {"g2_x_s": 31}
        cj = self.run_campaign()
        self.assertEqual(cj["pass"], {"R": "insufficient", "D": "insufficient", "S": "insufficient"})
        self.assertIsNone(cj["choice"])

    def test_threshold_in_unused_attempt_ignored(self):
        """무효 앞 시도의 plan 값은 사용 회차가 아니라 무관"""
        self.attempts[("S", "pg", 2)] = [{"status": "invalid:drain-timeout"}]
        # attempt1 plan의 X만 다르게 — values 키는 최신 폴더에도 적용되므로 _write_rep 경로로 따로 쓴다
        cj_root = self.tmp / "c-test" / "S" / "pg" / "rep2.attempt1"
        cj = self.run_campaign()
        pl = json.loads((cj_root / "plan.json").read_text(encoding="utf-8"))
        pl["values"]["g2_x_s"] = 99
        (cj_root / "plan.json").write_text(json.dumps(pl), encoding="utf-8")
        pr = run_cli(SUMMARIZE_PY, "--campaign", self.tmp / "c-test")
        self.assertEqual(last_line(pr.stdout), "choice:D", cj)

    def test_base_uses_x_not_y(self):
        """base 셀 p99 60초 — Y 안이어도 X 밖이면 실패"""
        self.over[("D", "base", 1)] = {"p99": 60.0}
        cj = self.run_campaign()
        self.assertFalse(cj["g2"]["D"]["base"]["ok"])

    def test_no_sample_fails(self):
        """§8 표본 0 → 판정 불가(g2-no-sample) = 실패로 센다"""
        self.over[("D", "kill", 1)] = {"n": 0}
        cj = self.run_campaign()
        self.assertFalse(cj["g2"]["D"]["kill"]["ok"])
        self.assertIs(cj["pass"]["D"], False)
        self.assertEqual(cj["choice"], "R")


class TestCampaignG3(CampaignCase):
    """§8 G3 much_higher = 범위 비겹침 ∧ 후보가 위 ∧ 중앙값 비 > 1.10 · R은 늘 통과"""

    def g3(self, cand, vals, metric=0):
        for rep, v in zip((1, 2, 3), vals):
            g = [100.0, 50.0, 40.0]
            g[metric] = v
            self.over[(cand, "base", rep)] = {"g3": tuple(g)}

    def test_ratio_exactly_110_not_much_higher(self):
        self.g3("R", (100.0, 100.0, 100.0))
        self.g3("D", (110.0, 110.0, 110.0))
        cj = self.run_campaign()
        self.assertTrue(cj["g3"]["D"]["ok"])
        self.assertEqual(cj["choice"], "D")

    def test_ratio_above_110_much_higher(self):
        self.g3("R", (100.0, 100.0, 100.0))
        self.g3("D", (111.0, 111.0, 111.0))
        cj = self.run_campaign()
        self.assertFalse(cj["g3"]["D"]["ok"])
        self.assertEqual(cj["choice"], "R")

    def test_each_metric(self):
        for m in (0, 1, 2):
            with self.subTest(m):
                self.over = {}
                self.g3("R", (100.0, 100.0, 100.0), metric=m)
                self.g3("D", (150.0, 160.0, 170.0), metric=m)
                cj = self.run_campaign()
                self.assertFalse(cj["g3"]["D"]["ok"], f"지표 {m} much_higher")
                shutil.rmtree(self.tmp / "c-test", ignore_errors=True)

    def test_overlap_not_much_higher(self):
        """범위 겹침 — 중앙값 비 2여도 아님"""
        self.g3("R", (90.0, 100.0, 110.0))
        self.g3("D", (105.0, 200.0, 210.0))
        cj = self.run_campaign()
        self.assertTrue(cj["g3"]["D"]["ok"])

    def test_touching_ranges_overlap(self):
        """범위 끝이 같음(R 최대 = D 최소) — 겹침으로 본다"""
        self.g3("R", (90.0, 100.0, 120.0))
        self.g3("D", (120.0, 200.0, 210.0))
        cj = self.run_campaign()
        self.assertTrue(cj["g3"]["D"]["ok"])

    def test_below_not_much_higher(self):
        self.g3("R", (100.0, 100.0, 100.0))
        self.g3("D", (10.0, 10.0, 10.0))
        self.assertTrue(self.run_campaign()["g3"]["D"]["ok"])

    def test_non_numeric_metric_fails(self):
        """§8 지표 값이 셋 다 숫자가 아니면 그 후보 G3 ok = false"""
        for rep_ in (1, 2, 3):
            self.over[("D", "base", rep_)] = {"g3": (100.0, None, 40.0)}
        cj = self.run_campaign()
        self.assertFalse(cj["g3"]["D"]["ok"])
        self.assertEqual(cj["choice"], "R")

    def test_R_always_passes(self):
        self.g3("R", (1000.0, 1000.0, 1000.0))
        self.assertTrue(self.run_campaign()["g3"]["R"]["ok"])

    def test_median_ratio_value(self):
        self.g3("R", (90.0, 100.0, 105.0))
        self.g3("D", (120.0, 130.0, 500.0))
        cj = self.run_campaign()
        m = cj["g3"]["D"]
        ent = [v for k, v in m.items() if k != "ok" and isinstance(v, dict) and v.get("median") == 130.0]
        self.assertTrue(ent, f"중앙값 130 지표 항목 기대 · 실제 {m}")
        self.assertEqual(list(ent[0]["range"]), [120.0, 500.0])
        self.assertAlmostEqual(ent[0]["ratio_vs_R"], 1.3, places=9)
        self.assertTrue(ent[0]["much_higher"])
        self.assertFalse(m["ok"])


class TestCampaignValidity(CampaignCase):
    """M17-3 · §9.2 유효 시도 · insufficient"""

    def test_insufficient_invalid_without_retry(self):
        """§8 D insufficient · R 통과 → choice null(아래로 내려가지 않음)"""
        self.over[("D", "base", 3)] = {"status": "invalid:drain-timeout"}
        cj = self.run_campaign()
        self.assertEqual(cj["pass"]["D"], "insufficient")
        self.assertIs(cj["pass"]["R"], True)
        self.assertIsNone(cj["choice"])

    def test_R_insufficient_all_insufficient(self):
        """§8 R이 insufficient면 G3 기준이 없어 모든 후보 insufficient"""
        self.over[("R", "pg", 1)] = "absent"
        cj = self.run_campaign()
        self.assertEqual(cj["pass"], {"R": "insufficient", "D": "insufficient", "S": "insufficient"})
        self.assertIsNone(cj["choice"])

    def test_D_fail_R_insufficient_null(self):
        """D 실패 → R insufficient(→ 전부 insufficient) → null"""
        self.over[("D", "base", 1)] = {"g1_ok": False}
        self.over[("R", "kill", 2)] = {"status": "invalid:kill-not-observed"}
        self.assertIsNone(self.run_campaign()["choice"])

    def test_S_insufficient_after_R_pass(self):
        """D 실패 · R 통과 → R(S insufficient는 R 뒤라 무관)"""
        self.over[("D", "base", 1)] = {"g1_ok": False}
        self.over[("S", "base", 1)] = "absent"
        cj = self.run_campaign()
        self.assertEqual(cj["choice"], "R")

    def test_insufficient_missing_rep(self):
        self.over[("S", "kill", 2)] = "absent"
        cj = self.run_campaign()
        self.assertEqual(cj["pass"]["S"], "insufficient")
        self.assertEqual(cj["choice"], "D")

    def test_invalid_rep_excluded_from_judgement(self):
        """무효 회차(G1 실패 · p99 큼)는 판정에 들어가지 않는다 — 앞 시도가 무효 · 최신이 유효"""
        self.attempts[("D", "pg", 1)] = [{"status": "invalid:inject-not-observed", "g1_ok": False, "p99": 999.0}]
        cj = self.run_campaign()
        self.assertTrue(cj["g1"]["D"]["ok"])
        self.assertTrue(cj["g2"]["D"]["pg"]["ok"])
        self.assertEqual(cj["choice"], "D")
        run = cj["runs"]["D/pg/rep1"]
        self.assertEqual(run["used"], "rep1")
        self.assertEqual(run["attempts"], 2)

    def test_latest_ok_attempt_used(self):
        """§9.2 status ok인 가장 늦은 시도 — 최신(rep1)이 무효면 attempt1(유효)"""
        self.attempts[("D", "base", 1)] = [{"p99": 5.0}]
        self.over[("D", "base", 1)] = {"status": "invalid:sessions-broken", "g1_ok": False}
        cj = self.run_campaign()
        self.assertEqual(cj["runs"]["D/base/rep1"]["used"], "rep1.attempt1")
        self.assertNotEqual(cj["pass"]["D"], "insufficient")
        self.assertEqual(cj["choice"], "D")

    def test_latest_of_two_ok(self):
        """두 시도 모두 ok면 가장 늦은 것(rep1) — 앞 시도의 p99 실패는 무시"""
        self.attempts[("D", "base", 2)] = [{"p99": 99.0}]
        cj = self.run_campaign()
        self.assertEqual(cj["runs"]["D/base/rep2"]["used"], "rep2")
        self.assertTrue(cj["g2"]["D"]["base"]["ok"])

    def test_attempt_order_numeric(self):
        """attempt1 < attempt2 < rep(최신) — 최신 무효 · attempt2 유효(G1 실패) · attempt1 유효(통과) → attempt2"""
        self.attempts[("D", "kill", 1)] = [{}, {"g1_ok": False}]
        self.over[("D", "kill", 1)] = {"status": "invalid:kill-not-observed"}
        cj = self.run_campaign()
        self.assertEqual(cj["runs"]["D/kill/rep1"]["used"], "rep1.attempt2")
        self.assertFalse(cj["g1"]["D"]["ok"])
        self.assertEqual(cj["choice"], "R")


if __name__ == "__main__":
    unittest.main(verbosity=2)
