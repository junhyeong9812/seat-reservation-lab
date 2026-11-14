#!/usr/bin/env python3
"""ADR-017 요약기 자기 시험(서버 없음 — 계약 INTERFACE.md): §4 shape 표 · §5 결정표 전 갈래 · §7 회차 무효 사유 · §8 G1 항목 실패 · G2 문턱 · G3 much_higher ·
캠페인 판정 · 순서 분기 · 시도 고르기. 가짜 회차 폴더를 임시 폴더에 만들어 summarize17 · outcome17을 그대로 돌린다.

    selftest17.py [--campaign]   → 실패 0이면 exit 0(--campaign = 캠페인 모의까지 · 없으면 회차 단위만)
"""
import copy
import csv
import gzip
import io
import json
import shutil
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import outcome17 as O  # noqa: E402
import plan17  # noqa: E402
import summarize17 as SM  # noqa: E402

FAILS = []
NPASS = [0]


def check(name, cond, detail=""):
    if cond:
        NPASS[0] += 1
    else:
        FAILS.append(f"{name}: {detail}")


OVER = {"n": 20, "rate": 2, "tMin": 2, "tMax": 5, "payMedian": 2, "payMax": 5, "lMin": 0.2, "lMax": 3, "killAt": [15, 30]}
T0_PC = 1_794_000_000.0   # 가짜 k6 시작(PC 초) · 시계 차 0
T0 = T0_PC * 1000
MODE_STATE = {"R": "CAPTURED", "D": "AUTHORIZED", "S": "CAPTURED"}


def iso(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat().replace("+00:00", "Z")


def nest(kv):
    out = {}
    for dotted, v in kv.items():
        cur = out
        parts = dotted.split(".")
        for p in parts[:-1]:
            cur = cur.setdefault(p, {})
        cur[parts[-1]] = v
    return out


def app_config(cand):
    props = [{"prefix": pre, "properties": nest(kv)} for pre, kv in plan17.expected_config(cand).items()]
    return {"configprops": props, "hikaricp.connections.max": [plan17.POOL], "app_nofile": {"soft": 65536, "hard": 65536}}


def resp(st, code, ts, **kw):
    o = {"st": st, "code": code, "ec": 0, "ts": ts, "tr": ts + 10}
    o.update(kw)
    return o


class World:
    """가짜 회차 하나 — 세션 행 · DB 덤프 · 원장 · 그 밖 파일을 일관되게 만든다(기대 갈래 = INTERFACE §5 '기대 갈래')."""

    def __init__(self, cand, cell, rep, lat=None, cpu=10.0, g2_extra_s=0.0):
        self.cand, self.cell, self.rep = cand, cell, rep
        self.plan = plan17.render(cand, cell, rep, OVER)
        self.plan["over"] = OVER
        self.plan["smoke"] = True
        self.lat = lat or {"confirm": 20.0, "pay_start": 15.0}
        self.cpu = cpu
        self.rows, self.intents, self.res, self.rej, self.tasks, self.ledger = [], [], [], [], [], []
        self.rid = 0
        self.tid = 0
        self.g2_extra = g2_extra_s * 1000
        self.build()

    def task(self, uid, typ, src, created, done, result, attempts=1, status="DONE"):
        self.tid += 1
        self.tasks.append({"id": self.tid, "uid": uid, "type": typ, "source": src, "status": status, "attempts": attempts, "next_attempt_ms": created,
                           "lease_until_ms": None, "last_result": result, "created_ms": created, "done_ms": done if status == "DONE" else None})

    def reservation(self, uid, seat, origin):
        self.rid += 1
        self.res.append({"id": self.rid, "schedule_id": 1, "seat_id": seat, "user_id": 0, "payment_uid": uid, "status": "CONFIRMED", "intent_id": uid,
                         "origin": origin, "created_ms": T0})
        return self.rid

    def led(self, uid, state, cap=None, auth=None, closed=None, refunds=0):
        self.ledger.append({"uid": uid, "userId": 1, "amount": 10000, "deadline": iso(T0 + 600000), "state": state, "registeredAt": iso(T0),
                            "authorizedAt": iso(auth) if auth else None, "capturedAt": iso(cap) if cap else None, "closedAt": iso(closed) if closed else None,
                            "refunds": refunds, "tombstone": False})

    def build(self):
        p, cyc = self.plan, self.plan["kind_cycle"]
        spare = p["seed"]["spare"]
        attempts = 2 if self.cell == "pg" else 1
        for sid in range(p["n_total"]):
            kind = cyc[sid % len(cyc)]
            a = plan17.session_attrs(self.rep, sid, p["params"])
            uid = f"u-{sid:04d}"
            t = T0 + sid * 500
            row = {"k": "s", "v": 1, "sid": sid, "kind": kind, "rep": self.rep, "cand": self.cand, "cell": self.cell, "user": plan17.USER_BASE + sid,
                   "seat": spare + sid + 1, **a, "uid": uid, "at": None, "h": resp(201, "OK", t, hid=sid + 1, exp=t + 300000, ok=True),
                   "p": resp(200, "OK", t + 3000, hid=sid + 1, exp=t + 614000, pb=t + 613000, pws=609, ext=True, uid=uid, ok=True),
                   "g": None, "c1": None, "c2": None, "end": None}
            self.intents.append({"uid": uid, "hold_id": sid + 1, "user_id": row["user"], "schedule_id": 1, "seat_id": row["seat"], "section": "A",
                                 "pay_by_ms": t + 613000, "state": "OPEN", "result_code": None, "reservation_id": None, "closed_reason": None, "created_ms": t + 3000})
            it = self.intents[-1]
            if kind == "abandon":
                row["end"] = "abandon"
                it.update(state="CLOSING", closed_reason="SWEEP", result_code="HOLD_NOT_FOUND")
                self.task(uid, "CLOSE", "SWEEP", t + 620000, t + 621000 + self.g2_extra, "CLOSED", attempts)
                self.led(uid, "CLOSED", closed=t + 621000)
                self.rows.append(row)
                continue
            gt = t + 6000
            row["g"] = {"op": plan17.PG_OP[self.cand], "st": 200, "code": "OK", "state": MODE_STATE[self.cand], "ec": 0, "ts": gt, "tr": gt + 5, "ok": True}
            if kind == "noconf":
                row["end"] = "noconf"
                it.update(state="CLOSING", closed_reason="SWEEP", result_code="HOLD_EXPIRED")
                self.task(uid, "CLOSE", "SWEEP", t + 620000, t + 621000 + self.g2_extra, "VOIDED" if self.cand == "D" else "REFUNDED", attempts)
                if self.cand == "D":
                    self.led(uid, "VOIDED", auth=gt, closed=t + 621000)
                else:
                    self.led(uid, "REFUNDED", cap=gt, closed=t + 621000, refunds=1)
                self.rows.append(row)
                continue
            row["end"] = "done"
            if kind == "late":
                row["at"] = t + 614000 + 1000
                c1 = resp(409, "HOLD_EXPIRED", row["at"], rid=None, seat=None, rs=None, cp=None)
                self.rej.append({"id": len(self.rej) + 1, "uid": uid, "code": "HOLD_EXPIRED", "http_status": 409, "rejected_ms": row["at"]})
                if self.cand == "S":
                    rid = self.reservation(uid, len(self.rej), "SUBSTITUTE")
                    it.update(state="SUBSTITUTED", result_code="HOLD_EXPIRED", reservation_id=rid)
                    c1["cp"] = {"type": "SUBSTITUTE", "status": "DONE", "taskId": None, "rid": rid, "seat": len(self.rej)}
                    self.led(uid, "CAPTURED", cap=gt)
                else:
                    it.update(state="REJECTED", result_code="HOLD_EXPIRED")
                    self.task(uid, "CLOSE", "REJECTION", row["at"] + 5, row["at"] + 2000 + self.g2_extra, "VOIDED" if self.cand == "D" else "REFUNDED", attempts)
                    c1["cp"] = {"type": "CLOSE", "status": "PENDING", "taskId": self.tid, "rid": None, "seat": None}
                    if self.cand == "D":
                        self.led(uid, "VOIDED", auth=gt, closed=row["at"] + 2000)
                    else:
                        self.led(uid, "REFUNDED", cap=gt, closed=row["at"] + 2000, refunds=1)
                row["c1"] = c1
            else:
                row["at"] = gt + 500
                rid = self.reservation(uid, row["seat"], "HOLD")
                it.update(state="CONFIRMED", reservation_id=rid)
                row["c1"] = resp(200, "OK", row["at"], rid=rid, seat=row["seat"], rs="CONFIRMED", cp=None)
                if self.cand == "D":
                    row["c1"]["cp"] = {"type": "CAPTURE", "status": "PENDING", "taskId": None, "rid": None, "seat": None}
                    self.task(uid, "CAPTURE", "CONFIRM", row["at"], row["at"] + 1000, "CAPTURED", attempts)
                    self.led(uid, "CAPTURED", auth=gt, cap=row["at"] + 1000)
                else:
                    self.led(uid, "CAPTURED", cap=gt)
                if kind == "retry":
                    row["c2"] = copy.deepcopy(row["c1"])
                    row["c2"]["ts"] += 1000
                    row["c2"]["tr"] += 1000
            self.rows.append(row)

    def write(self, d):
        d = Path(d)
        d.mkdir(parents=True, exist_ok=True)
        p = self.plan
        js = lambda name, obj: (d / name).write_text(json.dumps(obj))  # noqa: E731
        js("plan.json", p)
        (d / "harness-reasons.txt").write_text("")
        buf = io.StringIO()
        for r in self.rows:
            buf.write(json.dumps({"level": "info", "msg": json.dumps(r), "source": "console", "time": "x"}) + "\n")
        with gzip.open(d / "sessions17.log.gz", "wt") as f:
            f.write(buf.getvalue())
        js("k6-summary.json", {"adr017": {"rep": str(self.rep), "cand": self.cand, "cell": self.cell, "pg_op": plan17.PG_OP[self.cand], "rate": p["rate"],
                                          "d_arr_s": p["d_arr_s"], "plan_n": p["n_total"], "cycle": p["kind_cycle"], "params": p["params"],
                                          "spare": p["seed"]["spare"], "pg_url": "http://x:8104"}})
        end_pc = T0_PC + 700
        js("k6-window.json", {"k6_t0_epoch": T0_PC, "k6_end_epoch": end_pc, "exit": 0, "clock_offset_ms_given": 0})
        js("clock-offset.json", {"before": {"offset_s": 0.0, "rtt_s": 0.001, "at": T0_PC}, "after": {"offset_s": 0.0, "rtt_s": 0.001, "at": end_pc}})
        js("inject.json", {"cell": self.cell, "events": [{"at": "reset", "t_ms": T0 - 60000, "rc": 0, "http": 204, "request": None, "response": None},
                                                         {"at": "faults", "t_ms": T0 - 59000, "rc": 0, "http": 200, "request": p["pg"]["faults"],
                                                          "response": copy.deepcopy(p["pg"]["faults"])}]})
        if self.cell == "kill":
            js("kill.json", {"planned_at_s": p["kill"]["at_s"], "k6_t0_ms": T0, "events": [
                {"n": 1, "planned_ms": T0 + 15000, "kill_ms": T0 + 15000, "kill_rc": 0, "start_ms": T0 + 15100, "start_rc": 0, "ready_ms": T0 + 30000,
                 "ready_ok": True, "started_at": "2026-11-14T00:00:01Z"},
                {"n": 2, "planned_ms": T0 + 30000, "kill_ms": T0 + 31000, "kill_rc": 0, "start_ms": T0 + 31100, "start_rc": 0, "ready_ms": T0 + 46000,
                 "ready_ok": True, "started_at": "2026-11-14T00:00:02Z"}]})
        js("drain.json", {"start_ms": T0 + 700000, "end_ms": T0 + 712000, "reason": "empty", "cap_s": 600, "poll_s": 5,
                          "polls": [{"t_ms": T0 + 700000, "pending": 0, "open": 0, "open_unheld": 0}, {"t_ms": T0 + 707000, "pending": 0, "open": 0, "open_unheld": 0}]})
        ops = {"register": len(self.intents)}
        if self.cell == "pg":   # 처리 30건씩 — 주입 결정열(시드 고정)에 FAIL · UNKNOWN이 들어 있다(test_inject가 확인)
            ops.update(close=30, capture=30)
        js("pg-ledger.json", {"payments": sorted(self.ledger, key=lambda x: x["uid"]), "ops": ops})
        js("db-dump.json", {"t_ms": T0 + 712000, "payment_intent": self.intents, "reservation": self.res, "confirm_rejection": self.rej,
                            "compensation_task": self.tasks})
        js("consistency.json", {**{k: 0 for k in SM.V_KEYS}, "pay_compensation_mode": p["mode"], **{k: 0 for k in SM.PAY_ZERO}, "pay_intents": len(self.intents)})
        done = {}
        for t in self.tasks:
            if t["status"] == "DONE":
                k = f"{t['type']}:{t['last_result']}"
                done[k] = done.get(k, 0) + 1
        retry = {"CLOSE": sum(t["attempts"] - 1 for t in self.tasks if t["type"] == "CLOSE")}
        rejected = {"HOLD_EXPIRED": len(self.rej)}
        zero = {"t": "x", "base": "x", "process_start_s": 1000.0, "compensation": {"done": {}, "retry": {"CLOSE": 0}, "dead": {}, "unresolved": 0,
                "oldest_pending_seconds": 0, "record_failed": 0, "sweep_failed": 0},
                "confirm": {"legacy": 0, "uid_conflict": 0, "rejected": {}, "failed": {}, "pay_start": {}, "extend": {}}, "pg_register": {"ok": 0}}
        end = copy.deepcopy(zero)
        end["compensation"]["done"], end["compensation"]["retry"], end["confirm"]["rejected"] = done, retry, rejected
        after = copy.deepcopy(end)
        if self.cell == "kill":
            end["process_start_s"] = 1060.0   # 재기동 — 시작 시각이 바뀐다
        js("act17-before.json", zero)
        js("act17-after-k6.json", after)
        js("act17-end.json", end)
        js("app-config.json", app_config(self.cand))
        js("seed-check.json", {"schedules": 1, "seats": p["seed"]["seats"], "available": p["seed"]["seats"], "holds": 0, "reservations": 0,
                               "quota_rows": 0, "flyway": ["1", "15"]})
        js("meta.json", {"cand": self.cand, "cell": self.cell})
        if self.cell == "base":
            lines = []
            for i in range(0, 720, 10):
                lines.append(json.dumps({"t": datetime.fromtimestamp(T0_PC + i, timezone.utc).isoformat(), "load": "0 0 0"}))
                lines.append(json.dumps({"Name": "seatlab-db-1", "CPUPerc": f"{self.cpu}%", "MemUsage": "100MiB / 1GiB"}))
                lines.append(json.dumps({"Name": "seatlab-app-1", "CPUPerc": "5%", "MemUsage": "300MiB / 2GiB"}))
            (d / "timeline-server.jsonl").write_text("\n".join(lines) + "\n")
            head = ["metric_name", "timestamp", "metric_value", "check", "error", "error_code", "expected_response", "group", "method", "name", "proto",
                    "scenario", "service", "status", "subproto", "tls_version", "url", "extra_tags", "metadata"]
            with gzip.open(d / "k6-requests.csv.gz", "wt", newline="") as f:
                w = csv.writer(f)
                w.writerow(head)
                for name, v in (("confirm", self.lat["confirm"]), ("pay_start", self.lat["pay_start"])):
                    for _ in range(10):
                        w.writerow(["http_req_duration", "0", str(v), "", "", "", "true", "", "POST", name, "", "main", "", "200", "", "", "", "", ""])
        (d / "status").write_text("ok\n")
        return d


def summ(d):
    s, st = SM.rep_summary(d)
    return s, st


def reasons_of(st):
    return set() if st == "ok" else set(st.split(":", 1)[1].split(","))


# ---- 1. shape 표(§4) ------------------------------------------------------------------------------------------------------------
def test_shapes():
    def e(**kw):
        base = {"uid": "u", "intent_state": "OPEN", "res": [], "pg_state": None, "refunds": 0, "captured": False, "authorized": False}
        base.update(kw)
        return base
    C = lambda o: {"id": 1, "status": "CONFIRMED", "origin": o}  # noqa: E731
    cases = [
        (e(res=[C("HOLD"), C("SUBSTITUTE")], pg_state="CAPTURED"), "double_reservation"),
        (e(pg_state="REFUNDED", refunds=2), "double_refund"),
        (e(pg_state="REFUNDED", refunds=1, res=[C("HOLD")]), "refunded_with_seat"),
        (e(pg_state="VOIDED", res=[C("HOLD")]), "voided_with_seat"),
        (e(pg_state="CAPTURED", res=[C("SUBSTITUTE")]), "substituted"),
        (e(pg_state="REFUNDED", res=[C("SUBSTITUTE")]), "refunded_with_seat"),
        (e(pg_state="CLOSED", res=[C("SUBSTITUTE")]), "substituted_unpaid"),
        (e(pg_state="CAPTURED", res=[C("HOLD")]), "seat_paid"),
        (e(pg_state="AUTHORIZED", res=[C("HOLD")]), "seat_moneyless"),
        (e(pg_state=None, res=[C("HOLD")]), "seat_moneyless"),
        (e(pg_state="REFUNDED", refunds=1), "refunded"),
        (e(pg_state="VOIDED"), "voided"),
        (e(pg_state="CLOSED"), "closed_unpaid"),
        (e(pg_state="CAPTURED"), "orphan"),
        (e(pg_state="AUTHORIZED"), "auth_open"),
        (e(pg_state="EXPIRED"), "auth_open"),
        (e(pg_state="REGISTERED"), "open_unpaid"),
        (e(pg_state=None), "no_ledger"),
        (e(intent_state=None, pg_state="CLOSED", captured=True), "no_intent"),
        (e(res=[{"id": 1, "status": "CANCELLED", "origin": "HOLD"}], pg_state="CAPTURED"), "orphan"),
        (e(pg_state="CLOSED", captured=True), "orphan"),
        (e(pg_state="CLOSED", authorized=True), "auth_open"),
        (e(pg_state="WEIRD"), "no_intent"),
        (e(intent_state=None, pg_state="CLOSED", authorized=True), "no_intent"),
        (e(intent_state=None, pg_state="REGISTERED"), "open_unpaid"),
    ]
    for i, (end, want) in enumerate(cases):
        got = O.shape(end)
        check(f"shape#{i}", got == want, f"{got} ≠ {want}")
    led = {"payments": [{"uid": "a", "state": "CAPTURED", "capturedAt": "2026-11-14T01:53:31.492485043Z", "refunds": 0}]}
    db = {"payment_intent": [], "reservation": [], "confirm_rejection": [], "compensation_task": []}
    check("end_states/no_intent-orphan", O.end_states(db, led)["a"]["shape"] == "orphan")
    check("iso_ms nanos", abs(O.iso_ms("2026-11-14T01:53:31.492485043Z") - 1794621211492.485) < 0.01, str(O.iso_ms("2026-11-14T01:53:31.492485043Z")))


# ---- 2. 결정표(§5) -----------------------------------------------------------------------------------------------------------------
def test_inject():
    """L1-4: Java Random · String.hashCode 재현 — 실제 mock-pg 컨테이너에 faults(fail 0.3 · unknown 0.2)를 걸고 30회 호출한 결정열(2026-11-14 로컬 대조)을 고정값으로."""
    check("java_hash", (SM.java_hash("close"), SM.java_hash("capture"), SM.java_hash("")) == (94756344, 552585030, 0))
    live = {(12345, "close"): "PFPUPFFPPUUPFFPPUUPPPPUUPPPFPP", (12345, "capture"): "FPPPUPFUFUFUPUUFPPPPPUPPUPUFPF",
            (1218959823, "close"): "PFUPFFPFUPPPPPPFPPPPFPUFFUPFFP", (1218959823, "capture"): "PPUUFPPUPPFFUFFUUPFFFPFPFFPPUU",
            (-7, "close"): "FPFPPFFFFPFPUUPPPPPPFUUPPPPFUP", (-7, "capture"): "PPFPPPFPFFPFFUPUPFUUPFPUPFUPPF"}
    for (seed, op), want in live.items():
        rnd = SM.JavaRandom(SM.s64(seed * 31 + SM.java_hash(op)))
        got = "".join("F" if x < 0.3 else ("U" if x < 0.5 else "P") for x in (rnd.next_double() for _ in range(30)))
        check(f"java_random/{seed}/{op}", got == want, got)
        cfg = {"seed": seed, "rules": {op: {"delayMs": 0, "failRate": 0.3, "unknownRate": 0.2, "unknownDelayMs": 400}}}
        n = sum(c != "F" for c in want)
        d = SM.inject_decisions(cfg, {op: n})[op]
        upto = want[:want.rstrip("F").__len__()]   # 마지막 처리 뒤 FAIL은 걷지 않는다(하한)
        check(f"inject_decisions/{seed}/{op}", d == {"processed": n, "fail": upto.count("F"), "unknown": upto.count("U"), "reached": True}, str(d))
    check("inject_decisions/no-rate", SM.inject_decisions({"seed": 1, "rules": {"close": {"failRate": 0, "unknownRate": 0}}}, {"close": 9}) == {})


def test_classify():
    w = World("R", "base", 1)
    plan = w.plan
    by_kind = {}
    for r in w.rows:
        by_kind.setdefault(r["kind"], r)
    ends_for = lambda cand_world: O.end_states({"payment_intent": cand_world.intents, "reservation": cand_world.res,  # noqa: E731
                                                "confirm_rejection": cand_world.rej, "compensation_task": cand_world.tasks}, {"payments": cand_world.ledger})
    for cand in ("R", "D", "S"):
        cw = World(cand, "base", 1)
        ends = ends_for(cw)
        want = {"ok": "success", "retry": "success", "abandon": "not_paid",
                "late": {"R": "wronged_refunded", "D": "wronged_voided", "S": "wronged_substituted"}[cand],
                "noconf": {"R": "wronged_refunded", "D": "wronged_voided", "S": "wronged_refunded"}[cand]}
        for r in cw.rows:
            got = O.classify(r, ends.get(r["uid"]), cand, [], cw.plan)
            check(f"classify/{cand}/{r['kind']}", got == want[r["kind"]], f"sid {r['sid']}: {got}")
    ends = ends_for(w)
    ok = copy.deepcopy(by_kind["ok"])
    E = lambda r: ends.get(r["uid"])  # noqa: E731

    def case(name, row, want, end="same", cand="R", kills=()):
        got = O.classify(row, E(row) if end == "same" else end, cand, list(kills), plan)
        check(f"classify/{name}", got == want, f"{got} ≠ {want}")
    # A 행 정합
    r = copy.deepcopy(ok); r["kind"] = "late"; case("A-kind", r, "invalid:row-bad")
    r = copy.deepcopy(ok); r["cand"] = "D"; case("A-cand", r, "invalid:row-bad")
    r = copy.deepcopy(ok); del r["L"]; case("A-field", r, "invalid:row-bad")
    r = copy.deepcopy(ok); r["c2"] = copy.deepcopy(r["c1"]); case("A-c2-on-ok", r, "invalid:row-bad")
    r = copy.deepcopy(by_kind["retry"]); r["c2"] = None; case("A-retry-no-c2", r, "invalid:row-bad")
    r = copy.deepcopy(ok); r["end"] = "weird"; case("A-end", r, "invalid:row-bad")
    # B 앱 죽음
    r = copy.deepcopy(ok); r["c1"] = resp(0, "NOCODE", r["c1"]["ts"]); win = [(r["c1"]["ts"] - 100, r["c1"]["ts"] + 5)]
    case("B-confirm0-in-kill", r, "app_dead", kills=win)
    case("B-confirm0-no-kill", r, "invalid:confirm-code0")
    r2 = copy.deepcopy(r); r2["c1"]["st"] = 502; case("B-confirm502-in-kill", r2, "app_dead", kills=win)
    hb = copy.deepcopy(ok); hb.update(uid=None, p=None, g=None, c1=None, at=None, end="hold"); hb["h"] = resp(0, "NOCODE", hb["h"]["ts"], ok=False)
    case("B-hold0-kill", hb, "app_dead", end=None, kills=[(hb["h"]["ts"], hb["h"]["tr"])])
    # C hold
    case("C-hold0", hb, "invalid:hold-code0", end=None)
    h2 = copy.deepcopy(hb); h2["h"]["ec"] = 1050; case("C-hold-timeout", h2, "mode_fail:hold-timeout", end=None)
    h3 = copy.deepcopy(hb); h3["h"]["st"] = 503; case("C-hold5xx", h3, "mode_fail:hold-5xx", end=None)
    h4 = copy.deepcopy(hb); h4["h"].update(st=409, code="SEAT_NOT_AVAILABLE"); case("C-hold409", h4, "invalid:hold-409-SEAT_NOT_AVAILABLE", end=None)
    # C pay_start
    ps = copy.deepcopy(ok); ps.update(uid=None, g=None, c1=None, at=None, end="pay_start")
    for (st, code, ec), want in (((409, "HOLD_EXPIRED", 0), "pre_reject"), ((404, "HOLD_NOT_FOUND", 0), "pre_reject"), ((503, "NOCODE", 0), "mode_fail:pay-start-503"),
                                 ((200, "OK", 0), "mode_fail:pay-start-body"), ((0, "NOCODE", 1050), "mode_fail:pay-start-timeout"),
                                 ((0, "NOCODE", 0), "invalid:pay-start-code0"), ((403, "HOLD_NOT_OWNED", 0), "mode_fail:pay-start-403-HOLD_NOT_OWNED")):
        x = copy.deepcopy(ps); x["p"].update(st=st, code=code, ec=ec, ok=False); case(f"C-ps-{st}-{code}", x, want, end=None)
    x = copy.deepcopy(ps); x["p"].update(st=409, code="HOLD_EXPIRED", ok=False)
    case("C-ps-reject-with-seat", x, "integrity:pre-reject-seat_paid", end=E(ok))
    # C abandon
    ab = by_kind["abandon"]
    case("C-abandon", ab, "not_paid")
    case("C-abandon-open", ab, "integrity:abandon-open_unpaid", end={**E(ab), "pg_state": "REGISTERED", "shape": "open_unpaid"})
    # C pg
    pg = copy.deepcopy(by_kind["noconf"]); pg["kind"] = "noconf"; pg["end"] = "pg"; pg["g"].update(st=409, code="DEADLINE_PASSED", state="REGISTERED", ok=False)
    case("C-pg-deadline", pg, "pre_reject", end={"shape": "closed_unpaid", "res": []})
    case("C-pg-deadline-bad", pg, "integrity:pg-deadline-refunded", end={"shape": "refunded", "res": []})
    pg2 = copy.deepcopy(pg); pg2["g"].update(st=0, code="NOCODE"); case("C-pg-0", pg2, "invalid:pg-0-NOCODE", end=None)
    # C noconf
    nc = by_kind["noconf"]
    for sh, want in (("refunded", "wronged_refunded"), ("voided", "wronged_voided"), ("orphan", "wronged_open"), ("auth_open", "wronged_open"),
                     ("seat_paid", "integrity:noconf-seat_paid"), ("substituted", "integrity:noconf-substituted")):
        case(f"C-noconf-{sh}", nc, want, end={"shape": sh, "res": []})
    # D done
    rt = copy.deepcopy(by_kind["retry"]); rt["c2"]["rid"] = 999; case("D-retry-mismatch", rt, "integrity:retry-replay-mismatch")
    # L1-1 retry: 미확정 응답은 재생 대조에서 뺀다
    base_rt = by_kind["retry"]
    x = copy.deepcopy(base_rt); x["c1"] = resp(0, "NOCODE", x["c1"]["ts"]); w1 = [(x["c1"]["ts"] - 10, x["c1"]["tr"] + 10)]
    case("L1-1-c1-0-kill", x, "app_dead", kills=w1)
    case("L1-1-c1-0", x, "invalid:confirm-code0")
    x2 = copy.deepcopy(base_rt); x2["c1"] = resp(503, "NOCODE", x2["c1"]["ts"]); case("L1-1-c1-503", x2, "mode_fail:confirm-5xx")
    x3 = copy.deepcopy(base_rt); x3["c1"] = dict(resp(0, "NOCODE", x3["c1"]["ts"]), ec=1050); case("L1-1-c1-timeout", x3, "mode_fail:confirm-timeout")
    x4 = copy.deepcopy(base_rt); x4["c2"] = resp(0, "NOCODE", x4["c2"]["ts"]); case("L1-1-c2-0-kill", x4, "app_dead", kills=[(x4["c2"]["ts"], x4["c2"]["tr"])])
    case("L1-1-c2-0", x4, "invalid:confirm-code0")
    x5 = copy.deepcopy(base_rt); x5["c2"] = resp(502, "NOCODE", x5["c2"]["ts"]); case("L1-1-c2-502", x5, "mode_fail:confirm-5xx")
    x6 = copy.deepcopy(base_rt); x6["c1"] = resp(0, "NOCODE", x6["c1"]["ts"]); x6["c2"] = resp(0, "NOCODE", x6["c2"]["ts"])
    case("L1-1-both-0-c1kill", x6, "app_dead", kills=[(x6["c1"]["ts"], x6["c1"]["tr"])])
    x = copy.deepcopy(ok); x["c1"]["rid"] = 999; case("D-200-rid", x, "integrity:200-mismatch")
    case("D-200-moneyless-R", ok, "integrity:200-mismatch", end={**E(ok), "shape": "seat_moneyless"})
    case("D-200-moneyless-D", ok, "success", end={**E(ok), "shape": "seat_moneyless"}, cand="D")
    case("D-200-refunded", ok, "integrity:200-refunded", end={**E(ok), "shape": "refunded"})
    lt = by_kind["late"]
    case("D-rej-refunded-D", lt, "integrity:reject-refunded", cand="D")
    case("D-rej-voided-R", lt, "integrity:reject-voided", end={**E(lt), "shape": "voided"})
    case("D-rej-orphan", lt, "wronged_open", end={**E(lt), "shape": "orphan"})
    case("D-rej-seat", lt, "integrity:reject-seat_paid", end={**E(ok), "shape": "seat_paid"})
    case("D-rej-sub-noresp", lt, "integrity:reject-substituted", end={**E(lt), "shape": "substituted"}, cand="S")
    for (st, code, ec), want in (((0, "NOCODE", 1050), "mode_fail:confirm-timeout"), ((0, "NOCODE", 0), "invalid:confirm-code0"),
                                 ((500, "NOCODE", 0), "mode_fail:confirm-5xx"), ((403, "HOLD_NOT_OWNED", 0), "mode_fail:confirm-403-HOLD_NOT_OWNED")):
        x = copy.deepcopy(ok); x["c1"].update(st=st, code=code, ec=ec); case(f"D-{st}-{code}-{ec}", x, want)
    check("kill_windows", O.kill_windows({"events": [{"kill_ms": 1000, "start_ms": 2000, "ready_ms": None}, {"kill_ms": 10, "ready_ms": 50}]})
          == [(0, 2000 + 120000 + 1000), (-990, 1050)], str(O.kill_windows({"events": [{"kill_ms": 1000, "start_ms": 2000, "ready_ms": None}]})))
    check("kill_windows none", O.kill_windows(None) == [])


# ---- 3. 회차 판정(§7 · §8) --------------------------------------------------------------------------------------------------------
def test_runs(tmp):
    for cand in ("R", "D", "S"):
        for cell in ("base", "pg", "kill"):
            d = World(cand, cell, 1).write(tmp / f"ok-{cand}-{cell}")
            s, st = summ(d)
            check(f"run-ok/{cand}/{cell}", st == "ok", st)
            check(f"run-g1/{cand}/{cell}", s and s["g1"]["ok"], json.dumps(s["g1"] if s else None)[:300])
    base = World("R", "pg", 2)

    def mutated(name, fn, want, cell=None, cand="R", g1_fail=None):
        w = World(cand, cell or "pg", 2) if (cell or cand != "R") else copy.deepcopy(base)
        d = w.write(tmp / f"m-{name}")
        fn(d, w)
        s, st = summ(d)
        got = reasons_of(st)
        if want is not None:
            check(f"invalid/{name}", want in got, st)
        if g1_fail is not None:
            check(f"g1/{name}", s["g1"]["ok"] is False and (s["g1"]["items"].get(g1_fail) not in (0, None) or g1_fail == "-"), json.dumps(s["g1"]["items"])[:400])
        return s, st

    def rm(*names):
        return lambda d, w: [Path(d, n).unlink() for n in names]

    def edit(name, f):
        def go(d, w):
            p = Path(d, name)
            obj = json.loads(p.read_text())
            ret = f(obj)
            obj = ret if isinstance(ret, dict) else obj
            p.write_text(json.dumps(obj))
        return go

    def rows_edit(f):
        def go(d, w):
            rows = [json.loads(json.loads(l)["msg"]) for l in gzip.open(Path(d, "sessions17.log.gz"), "rt") if l.strip()]
            rows = f(rows) or rows
            with gzip.open(Path(d, "sessions17.log.gz"), "wt") as fo:
                for r in rows:
                    fo.write((json.dumps({"level": "info", "msg": json.dumps(r)}) if isinstance(r, dict) else r) + "\n")
        return go
    s, st = summ(tmp / "m-none") if False else (None, None)
    mutated("sessions-missing", rm("sessions17.log.gz"), "sessions-missing")
    mutated("sessions-broken", rows_edit(lambda rs: ["{not json", *rs]), "sessions-broken")
    mutated("count", rows_edit(lambda rs: rs[1:]), "sessions-count-mismatch")
    mutated("dup", rows_edit(lambda rs: rs + [rs[0]]), "session-dup")
    mutated("attrs", rows_edit(lambda rs: [dict(r, T=r["T"] + 0.01) if r["sid"] == 3 else r for r in rs]), "session-attrs-mismatch")
    mutated("seat", rows_edit(lambda rs: [dict(r, seat=1) if r["sid"] == 3 else r for r in rs]), "session-attrs-mismatch")
    mutated("session-invalid", rows_edit(lambda rs: [dict(r, h=dict(r["h"], st=0, ok=False), p=None, g=None, c1=None, c2=None, uid=None, at=None, end="hold")
                                                     if r["sid"] == 0 else r for r in rs]), "session-invalid")
    for f, why in (("pg-ledger.json", "ledger-missing"), ("db-dump.json", "db-dump-missing"), ("consistency.json", "consistency-missing"),
                   ("act17-end.json", "act-missing"), ("app-config.json", "app-config-missing"), ("seed-check.json", "seed-check-missing"),
                   ("inject.json", "inject-missing"), ("drain.json", "drain-missing")):
        mutated(f"miss-{f}", rm(f), why)
    mutated("act-error", edit("act17-before.json", lambda o: {"error": "metrics-list-failed"}), "act-missing")
    mutated("kill-missing", rm("kill.json"), "kill-missing", cell="kill")
    mutated("kill-once", edit("kill.json", lambda o: dict(o, events=o["events"][:1])), "kill-not-observed", cell="kill")
    mutated("kill-same-start", edit("kill.json", lambda o: dict(o, events=[dict(e, started_at="same") for e in o["events"]])), "kill-not-observed", cell="kill")
    mutated("kill-rc", edit("kill.json", lambda o: dict(o, events=[dict(o["events"][0], kill_rc=1), o["events"][1]])), "kill-not-observed", cell="kill")
    mutated("config", edit("app-config.json", lambda o: o["configprops"][2]["properties"].update(mode="SUBSTITUTE")), "compensation-mode-mismatch")
    mutated("config-worker", edit("app-config.json", lambda o: o["configprops"][2]["properties"]["worker"].update(batch=7)), "config-mismatch")
    mutated("cons-mode", edit("consistency.json", lambda o: o.update(pay_compensation_mode="none")), "consistency-mode-mismatch")
    mutated("seed", edit("seed-check.json", lambda o: o.update(holds=3)), "seed-mismatch")
    mutated("seed-v15", edit("seed-check.json", lambda o: o.update(flyway=["1", "13"])), "seed-mismatch")
    mutated("pg-not-reset", edit("pg-ledger.json", lambda o: o["payments"].append(dict(o["payments"][0], uid="warm-1"))), "pg-not-reset")
    mutated("reset-during", edit("inject.json", lambda o: o["events"].append({"at": "reset", "t_ms": T0 + 5000, "rc": 0, "http": 204})), "pg-reset-during-run")
    mutated("inject-mismatch", edit("inject.json", lambda o: o["events"][1]["response"]["rules"]["close"].update(failRate=0.5)), "inject-mismatch")
    mutated("inject-noreset", edit("inject.json", lambda o: o.update(events=o["events"][1:])), "inject-mismatch")
    mutated("inject-leak", edit("inject.json", lambda o: o["events"][1].update(response={"seed": o["events"][1]["response"]["seed"],
                                                                                        "rules": {"close": plan17.PG_RULE}})), "inject-leak", cell="base")

    def lead_proceed(cfg, op):
        r = cfg["rules"][op]
        rnd = SM.JavaRandom(SM.s64(cfg["seed"] * 31 + SM.java_hash(op)))
        k = 0
        while rnd.next_double() >= r["failRate"] + r["unknownRate"]:
            k += 1
        return k

    def no_inject(d, w):   # 원장 처리 수 = 결정열 앞쪽 PROCEED 수 — 주입 결정이 하나도 닿지 않는다(재시도 attempts는 그대로 2 — 원인 무관 재시도로는 통과하지 않는다)
        cfg = w.plan["pg"]["faults"]
        edit("pg-ledger.json", lambda o: o["ops"].update({op: lead_proceed(cfg, op) for op in cfg["rules"]}))(d, w)
    mutated("inject-not-observed", no_inject, "inject-not-observed")
    mutated("inject-undecidable", edit("inject.json", lambda o: [r.update(failRate=1.0, unknownRate=0.0) for r in o["events"][1]["response"]["rules"].values()]),
            "inject-undecidable")
    mutated("drain-cap", edit("drain.json", lambda o: o.update(reason="cap")), "drain-timeout")
    mutated("no-late-rej", rows_edit(lambda rs: [dict(r, c1=dict(r["c1"], st=200, code="OK")) if r["kind"] == "late" else r for r in rs]), "no-late-rejection")
    mutated("no-sweep", edit("db-dump.json", lambda o: [t.update(source="REJECTION") for t in o["compensation_task"] if t["uid"] in ("u-0008",)]), "no-sweep-close")
    mutated("metric", edit("act17-end.json", lambda o: o["compensation"].update(done={})), "app-metric-mismatch")
    mutated("metric-rej", edit("act17-end.json", lambda o: o["confirm"].update(rejected={})), "app-metric-mismatch")
    mutated("k6-exit", edit("k6-window.json", lambda o: o.update(exit=99)), "k6-exit-99")
    mutated("k6-plan", edit("k6-summary.json", lambda o: o["adr017"].update(plan_n=999)), "k6-plan-mismatch")
    mutated("clock", edit("clock-offset.json", lambda o: o.update(before=None)), "clock-offset-bad")
    mutated("latency", rm("k6-requests.csv.gz"), "latency-missing", cell="base")
    mutated("dbcpu", rm("timeline-server.jsonl"), "db-cpu-missing", cell="base")
    mutated("harness", lambda d, w: Path(d, "harness-reasons.txt").write_text("compose-up-failed\n"), "compose-up-failed")
    # G1 항목 실패(회차는 유효 · g1 ok 거짓)
    mutated("g1-dead", edit("db-dump.json", lambda o: o["compensation_task"][0].update(status="DEAD")), None, g1_fail="dead_tasks")
    mutated("g1-orphan", lambda d, w: (edit("pg-ledger.json", lambda o: [p.update(state="CAPTURED", closedAt=None, refunds=0) for p in o["payments"] if p["uid"] == "u-0001"])(d, w)),
            None, g1_fail="orphan")
    mutated("g1-double-refund", edit("pg-ledger.json", lambda o: [p.update(refunds=2) for p in o["payments"] if p["uid"] == "u-0001"]), None, g1_fail="double_refund")
    mutated("g1-cons", edit("consistency.json", lambda o: o.update(pay_capture_and_close=1)), None, g1_fail="consistency_pay")
    mutated("g1-v", edit("consistency.json", lambda o: o.update(v_duplicate_hold_seats=2)), None, g1_fail="consistency_v_sum")
    mutated("g1-open", edit("db-dump.json", lambda o: o["payment_intent"][0].update(state="OPEN")), None, g1_fail="open_after_drain")
    mutated("g1-integrity", rows_edit(lambda rs: [dict(r, c1=dict(r["c1"], rid=999)) if r["sid"] == 0 else r for r in rs]), None, g1_fail="integrity")
    mutated("g1-record", rows_edit(lambda rs: [dict(r, uid="ghost") if r["sid"] == 0 else r for r in rs]), None, g1_fail="-")
    mutated("g1-moneyless-R", edit("pg-ledger.json", lambda o: [p.update(state="AUTHORIZED") for p in o["payments"] if p["uid"] == "u-0000"]), None,
            g1_fail="seat_moneyless")
    s, st = summ(World("D", "base", 1).write(tmp / "d-moneyless-ok"))
    check("D-moneyless-not-g1", st == "ok" and s["g1"]["items"]["seat_moneyless"] == 0)
    # M17-3: 계획 = 분류(종류별)
    s, st = summ(tmp / "ok-S-base")
    check("M17-3", sum(sum(v.values()) for v in s["sessions"]["by_kind"].values()) == s["sessions"]["n"] == World("S", "base", 1).plan["n_total"]
          and all(sum(s["sessions"]["by_kind"][k].values()) == n for k, n in s["sessions"]["planned"].items() if n), json.dumps(s["sessions"])[:300])
    check("cost/S-ratio", s["cost"]["substitute_ratio"] == 1.0, str(s["cost"]["substitute_ratio"]))
    check("G2 R REJECTION n", summ(tmp / "ok-R-base")[0]["g2"]["by_source"]["REJECTION"]["n"] == 4)
    # ---- 리뷰 루프 1 수정 확인 ----
    # L1-2 v_* 필수 키
    mutated("L1-2-v-missing", edit("consistency.json", lambda o: o.pop("v_counter_mismatch")), "consistency-missing")
    mutated("L1-2-v-nonnum", edit("consistency.json", lambda o: o.update(v_stale_held_seats="0")), "consistency-missing")
    mutated("L1-2-pay-missing", edit("consistency.json", lambda o: o.pop("pay_dead_tasks")), "consistency-missing")
    # L1-3 G2 시각 결측
    mutated("L1-3-done-null", edit("db-dump.json", lambda o: [t.update(done_ms=None) for t in o["compensation_task"] if t["type"] == "CLOSE"][:1]), "g2-time-missing")
    mutated("L1-3-rej-missing", edit("db-dump.json", lambda o: o.update(confirm_rejection=o["confirm_rejection"][1:])), "g2-time-missing")
    s0, _ = summ(tmp / "ok-R-pg")
    n_close = sum(1 for t in World("R", "pg", 1).tasks if t["type"] == "CLOSE")
    check("L1-3-n=targets", s0["g2"]["n"] == n_close, f"{s0['g2']['n']} ≠ {n_close}")
    # L1-4 주입 결정 수 · pg 셀 기록
    check("L1-4-inject-key", s0["inject"] and all(v["reached"] for v in s0["inject"].values()) and sum(v["fail"] + v["unknown"] for v in s0["inject"].values()) > 0,
          json.dumps(s0.get("inject")))
    # L1-5 kill 시각
    mutated("L1-5-late", edit("kill.json", lambda o: o["events"][1].update(kill_ms=o["events"][1]["planned_ms"] + 9000)), "kill-not-observed", cell="kill")
    mutated("L1-5-outside", edit("k6-window.json", lambda o: o.update(k6_end_epoch=T0_PC + 20)), "kill-not-observed", cell="kill")
    mutated("L1-5-planned", edit("kill.json", lambda o: o["events"][0].update(planned_ms=T0 + 60000, kill_ms=T0 + 60000)), "kill-not-observed", cell="kill")
    mutated("L1-5-no-tol", edit("plan.json", lambda o: o["values"].pop("kill_tol_ms")), "kill-not-observed", cell="kill")
    # L1-6 act 필수 지표 · 재기동
    mutated("L1-6-null", edit("act17-end.json", lambda o: o["confirm"].update(legacy=None)), "act-missing")
    mutated("L1-6-dictnull", edit("act17-before.json", lambda o: o["compensation"].update(dead=None)), "act-missing")
    mutated("L1-6-failed", edit("act17-after-k6.json", lambda o: o.update(failed_names=["seat.confirm.extend"])), "act-missing")
    mutated("L1-6-ps-null", edit("act17-before.json", lambda o: o.update(process_start_s=None)), "act-missing")
    mutated("L1-6-restart-base", edit("act17-end.json", lambda o: o.update(process_start_s=5.0)), "app-restarted", cell="base")
    mutated("L1-6-norestart-kill", edit("act17-end.json", lambda o: o.update(process_start_s=1000.0)), "kill-not-observed", cell="kill")
    _, st = summ(tmp / "ok-R-kill")
    check("L1-6-kill-no-app-restarted", "app-restarted" not in reasons_of(st), st)

    # L1-7 kill 중 payment-start 응답 유실 uid
    def ps_lost(created):
        def go(d, w):
            r0 = next(r for r in w.rows if r["sid"] == 0)
            def fix_rows(rs):
                out = []
                for r in rs:
                    if r["sid"] == 0:
                        r = dict(r, uid=None, g=None, c1=None, c2=None, at=None, end="pay_start", p=dict(r["p"], st=0, code="NOCODE", ok=False, ts=T0 + 15500, tr=T0 + 15600, uid=None, hid=None, exp=None, pb=None, pws=None, ext=None))
                    out.append(r)
                return out
            rows_edit(fix_rows)(d, w)
            edit("db-dump.json", lambda o: [i.update(created_ms=created) for i in o["payment_intent"] if i["uid"] == r0["uid"]])(d, w)
        return go
    s7, st7 = mutated("L1-7-excused", ps_lost(T0 + 15500), None, cell="kill")
    check("L1-7-excused", "pg-not-reset" not in reasons_of(st7) and s7["kill"]["left_intents"] == 1 and s7["sessions"]["by_kind"]["ok"].get("app_dead") == 1, st7)
    mutated("L1-7-outside", ps_lost(T0 + 200000), "pg-not-reset", cell="kill")
    # L1-8 비용 보고
    sd, _ = summ(tmp / "ok-D-base")
    check("L1-8-void-time", sd["cost"]["void_time_s"]["n"] == 4 + 1, json.dumps(sd["cost"]["void_time_s"]))
    check("L1-8-app-dead-shapes", s7["cost"]["app_dead_shapes"] == {"none": 1}, json.dumps(s7["cost"]["app_dead_shapes"]))


# ---- 4. 캠페인(§8 · §9.2) ---------------------------------------------------------------------------------------------------------
def make_campaign(root, tweak=None):
    for cand in plan17.CAND_ORDER:
        for cell in plan17.CELLS:
            for rep in range(1, plan17.REPS + 1):
                kw = {}
                if tweak:
                    kw = tweak(cand, cell, rep) or {}
                if kw.get("skip"):
                    continue
                w = World(cand, cell, rep, lat=kw.get("lat"), cpu=kw.get("cpu", 10.0 + rep), g2_extra_s=kw.get("g2_extra_s", 0.0))
                d = w.write(root / cand / cell / f"rep{rep}")
                if kw.get("mutate"):
                    kw["mutate"](d)
                s, st = SM.rep_summary(d)
                if kw.get("summary_mut"):
                    kw["summary_mut"](s)
                (d / "summary17.json").write_text(json.dumps(s))
                (d / "status").write_text(st + "\n")
    return root


def test_campaign(tmp):
    def run(name, tweak, want_choice, extra=None):
        root = make_campaign(tmp / f"c-{name}", tweak)
        out = SM.campaign(root)
        check(f"campaign/{name}", out["choice"] == want_choice, f"choice {out['choice']} · pass {out['pass']}")
        if extra:
            extra(out)
        return out
    run("all-pass", None, "D", lambda o: check("campaign/runs", all(v["used"] == k.split("/")[-1] for k, v in o["runs"].items())))

    def g1_fail_D(c, cell, rep):   # G1 항목(v_* 위반)만 실패 — 회차는 유효
        if c == "D" and cell == "pg" and rep == 2:
            return {"mutate": lambda d: Path(d, "consistency.json").write_text(json.dumps(dict(json.loads(Path(d, "consistency.json").read_text()), v_duplicate_hold_seats=1)))}
    run("D-g1-fail", g1_fail_D, "R", lambda o: check("campaign/g1-fail-run", o["g1"]["D"]["fail_runs"] == ["D/pg/rep2"], str(o["g1"]["D"])))
    run("D-g2-base-over-X", lambda c, cell, rep: {"g2_extra_s": 31.0} if (c, cell) == ("D", "base") else None, "R",
        lambda o: check("campaign/g2", o["g2"]["D"]["base"]["ok"] is False and o["g2"]["D"]["pg"]["ok"], str(o["g2"]["D"])))
    run("D-g2-pg-under-Y", lambda c, cell, rep: {"g2_extra_s": 100.0} if (c, cell) == ("D", "pg") else None, "D")
    run("D-g3-much-higher", lambda c, cell, rep: {"lat": {"confirm": 40.0 + rep, "pay_start": 15.0}} if (c, cell) == ("D", "base") else None, "R",
        lambda o: check("campaign/g3", o["g3"]["D"]["confirm_p99_ms"]["much_higher"] and not o["g3"]["D"]["ok"], json.dumps(o["g3"]["D"])))
    run("D-g3-overlap", lambda c, cell, rep: {"lat": {"confirm": 21.5 if rep == 1 else 40.0, "pay_start": 15.0}} if (c, cell) == ("D", "base")
        else ({"lat": {"confirm": 20.0 + rep, "pay_start": 15.0}} if (c, cell) == ("R", "base") else None), "D")
    run("D-g3-ratio-small", lambda c, cell, rep: {"cpu": 13.05 + 0.05 * rep} if (c, cell) == ("D", "base") else None, "D",
        lambda o: check("campaign/g3-ratio", not o["g3"]["D"]["db_cpu_mean"]["much_higher"], json.dumps(o["g3"]["D"]["db_cpu_mean"])))
    run("D-g3-cpu-high", lambda c, cell, rep: {"cpu": 30.0 + rep} if (c, cell) == ("D", "base") else None, "R")
    run("D-insufficient", lambda c, cell, rep: {"skip": True} if (c, cell, rep) == ("D", "kill", 3) else None, None,
        lambda o: check("campaign/insufficient", o["pass"]["D"] == "insufficient", str(o["pass"])))
    run("R-insufficient", lambda c, cell, rep: {"skip": True} if (c, cell, rep) == ("R", "base", 1) else None, None,
        lambda o: check("campaign/R-insufficient", all(v == "insufficient" for v in o["pass"].values()), str(o["pass"])))
    run("D-fail-R-insufficient", lambda c, cell, rep: ({"g2_extra_s": 200.0} if (c, cell) == ("D", "kill") else ({"skip": True} if (c, cell, rep) == ("R", "pg", 1) else None)),
        None, lambda o: check("campaign/R-insuff", o["pass"]["D"] == "insufficient" and o["pass"]["R"] == "insufficient", str(o["pass"])))

    def values_differ(c, cell, rep):
        if (c, cell, rep) == ("S", "pg", 3):
            return {"mutate": lambda d: Path(d, "plan.json").write_text(json.dumps(dict(json.loads(Path(d, "plan.json").read_text()),
                     values=dict(json.loads(Path(d, "plan.json").read_text())["values"], g2_y_s=999.0))))}
    run("values-differ", values_differ, None, lambda o: check("campaign/values", all(v == "insufficient" for v in o["pass"].values()), str(o["pass"])))
    run("D-and-R-fail", lambda c, cell, rep: {"g2_extra_s": 200.0} if c in ("D", "R") and cell == "kill" else None, "S")
    run("none", lambda c, cell, rep: {"g2_extra_s": 200.0} if cell == "kill" else None, None)
    # L1-9 G2 표본 0 = 판정 불가(실패가 아니다) · 판정된 실패가 우선
    nos = lambda c0, cell0: (lambda c, cell, rep: {"summary_mut": lambda sm: sm["g2"].update(p99_s=None, n=0)} if (c, cell, rep) == (c0, cell0, 1) else None)  # noqa: E731
    run("L1-9-D-nosample", nos("D", "pg"), None, lambda o: check("L1-9-insufficient", o["pass"]["D"] == "insufficient" and o["g2"]["D"]["pg"]["no_sample"], str(o["pass"])))
    run("L1-9-S-nosample", nos("S", "base"), "D", lambda o: check("L1-9-S", o["pass"]["S"] == "insufficient", str(o["pass"])))

    def fail_and_nosample(c, cell, rep):
        if (c, cell) == ("D", "kill"):
            return {"g2_extra_s": 200.0}
        if (c, cell, rep) == ("D", "pg", 1):
            return {"summary_mut": lambda sm: sm["g2"].update(p99_s=None, n=0)}
    run("L1-9-fail-wins", fail_and_nosample, "R", lambda o: check("L1-9-false", o["pass"]["D"] is False, str(o["pass"])))
    # 시도 고르기: rep1 무효 · attempt1 유효 → attempt1을 쓴다 · status ok인 가장 늦은 시도
    root = make_campaign(tmp / "c-attempts")
    d = root / "D" / "base" / "rep1"
    shutil.copytree(d, root / "D" / "base" / "rep1.attempt1")
    (d / "status").write_text("invalid:drain-timeout\n")
    out = SM.campaign(root)
    check("campaign/attempt-pick", out["runs"]["D/base/rep1"]["used"] == "rep1.attempt1" and out["runs"]["D/base/rep1"]["attempts"] == 2, str(out["runs"]["D/base/rep1"]))
    rc = __import__("subprocess").run([sys.executable, "-I", str(HERE / "summarize17.py"), "--campaign", str(root)], capture_output=True, text=True)
    check("campaign/cli", rc.returncode == 0 and rc.stdout.strip().splitlines()[-1] == "choice:D" and (root / "campaign17.json").exists(), rc.stdout + rc.stderr)
    rc = __import__("subprocess").run([sys.executable, "-I", str(HERE / "summarize17.py"), "--rep", str(root / "R" / "pg" / "rep1")], capture_output=True, text=True)
    check("rep/cli", rc.returncode == 0 and rc.stdout.strip().splitlines()[-1] == "ok", rc.stdout + rc.stderr)
    rc = __import__("subprocess").run([sys.executable, "-I", str(HERE / "outcome17.py"), str(root / "S" / "kill" / "rep1")], capture_output=True, text=True)
    o = json.loads(rc.stdout) if rc.returncode == 0 else {}
    check("outcome/cli", rc.returncode == 0 and set(o) == {"sessions", "counts", "uids"} and len(o["sessions"]) == OVER["n"], rc.stdout[:200] + rc.stderr)
    rc = __import__("subprocess").run([sys.executable, "-I", str(HERE / "outcome17.py"), str(tmp)], capture_output=True, text=True)
    check("outcome/cli-missing", rc.returncode == 2 and json.loads(rc.stdout).get("error") == "plan-missing", rc.stdout)
    rc = __import__("subprocess").run([sys.executable, "-I", str(HERE / "summarize17.py"), "--rep", str(tmp)], capture_output=True, text=True)
    check("rep/cli-noplan", rc.returncode == 2, str(rc.returncode))


def main(argv):
    tmp = Path(tempfile.mkdtemp(prefix="selftest17-"))
    try:
        test_shapes()
        test_inject()
        test_classify()
        test_runs(tmp)
        if "--campaign" in argv:
            test_campaign(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    for f in FAILS:
        print("FAIL", f)
    print(f"selftest17: 통과 {NPASS[0]} · 실패 {len(FAILS)}")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
