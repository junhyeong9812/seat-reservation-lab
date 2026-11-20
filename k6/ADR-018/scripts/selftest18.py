#!/usr/bin/env python3
"""ADR-018 하네스 자기 시험(서버 없음 — 계약 INTERFACE.md): §4 shape 표 · §5 결정표 전 갈래 · §7 회차 무효 사유 전부 · §8 G1 항목 실패 · 비교 지표 ·
캠페인 표 · 방식별 판정 · 시도 고르기 · (--shell) campaign18.sh --simulate 재측정 상한 · run18.sh --dry-run.
가짜 회차 폴더를 임시 폴더에 만들어 summarize18 · outcome18을 그대로 돌린다.

    selftest18.py [--no-shell]   → 실패 0이면 exit 0(갈래 수 · 통과 수를 마지막 줄에)
"""
import copy
import csv
import gzip
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import outcome18 as O  # noqa: E402
import plan18  # noqa: E402
import summarize18 as SM  # noqa: E402

FAILS = []
NPASS = [0]


def check(name, cond, detail=""):
    if cond:
        NPASS[0] += 1
    else:
        FAILS.append(f"{name}: {detail}")


OVER = {"n": 60, "rate": 20, "tMin": 2, "tMax": 5, "payMedian": 2, "payMax": 5, "dMin": 1, "dMax": 2}
T0_PC = 1_794_000_000.0   # 가짜 k6 시작(PC 초) · 시계 차 0
T0 = T0_PC * 1000
AMOUNT = plan18.AMOUNT
LAT_MS = 4.0
SESSION_START_MS = 6000    # 모의 세계의 첫 세션은 k6 시작 6초 뒤(첫 5초 차가운 표본 제외 갈래를 따로 시험한다)               # CSV 지연(모든 2xx — 상한 아래)


def iso(sec):
    return datetime.fromtimestamp(sec, timezone.utc).isoformat()


def iso_ms(ms):
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


def app_config(method, cell):
    props = [{"prefix": pre, "properties": nest(kv)} for pre, kv in plan18.expected_config(method, cell).items()]
    return {"configprops": props, "hikaricp.connections.max": [plan18.POOL], "app_nofile": {"soft": 65536, "hard": 65536}}


def cons_zero():
    c = {k: 0 for k in SM.V_KEYS + SM.PAY_ZERO_END + SM.GROUP_ZERO}
    c.update(pay_compensation_mode="refund", pay_intents=0, max_per_user=2)
    return c


class World:
    """가짜 회차 하나 — 좌석 점유를 차례로 흉내 내 세션 행 · DB 덤프 · 원장 · 지표 · CSV를 일관되게 만든다(기대 갈래 = INTERFACE §5 '기대 갈래')."""

    def __init__(self, method, cell, rep=1):
        self.method, self.cell, self.rep = method, cell, rep
        self.plan = plan18.render(method, cell, rep, OVER)
        self.plan["over"] = OVER
        self.plan["smoke"] = True
        self.group = method in plan18.GROUP_METHODS
        self.rows, self.intents, self.res, self.rej, self.tasks, self.ledger, self.groups = [], [], [], [], [], [], []
        self.csv = []
        self.req = {}       # 지표 seat.group.request{op:outcome} 증분(세션 응답을 만들 때 따로 센다)
        self.hrj, self.grj = {}, {}
        self.dead = {}
        self.ids = {"hold": 0, "group": 0, "res": 0, "task": 0}
        self.owner, self.until = {}, {}
        self.build()

    # ---- 응답 · 기록 ----
    def resp(self, op, st, code, ts, **kw):
        o = {"op": op, "st": st, "code": code, "ec": 0, "ts": ts, "tr": ts + 5}
        o.update(kw)
        if 200 <= st < 300:
            self.csv.append((op, LAT_MS, st, int((ts + 5) // 1000)))   # CSV timestamp = 끝 시각(PC 초 · 시계 차 0)
        mo = {"hold_group": "hold", "release_group": "release_group", "release": "release_hold", "pay_start_group": "pay_start",
              "confirm_group": "confirm"}.get(op)
        if mo:
            out = "ok" if 200 <= st < 300 else (code if code not in ("OK", "NOCODE") else f"status_{st}")
            self.req[f"{mo}:{out}"] = self.req.get(f"{mo}:{out}", 0) + 1
        return o

    def task(self, uid, src, created, result="REFUNDED", status="DONE"):
        self.ids["task"] += 1
        self.tasks.append({"id": self.ids["task"], "uid": uid, "type": "CLOSE", "source": src, "status": status, "attempts": 1, "next_attempt_ms": created,
                           "lease_until_ms": None, "last_result": result, "created_ms": created, "done_ms": created + 500 if status == "DONE" else None})

    def intent(self, uid, user, hold_id, seat, sc, gid, t):
        self.intents.append({"uid": uid, "hold_id": hold_id, "user_id": user, "schedule_id": 1, "seat_id": seat, "section": "A", "pay_by_ms": t + 600000,
                             "state": "OPEN", "result_code": None, "reservation_id": None, "closed_reason": None, "created_ms": t, "group_id": gid, "seat_count": sc})
        self.ledger.append({"uid": uid, "userId": user, "amount": sc * AMOUNT, "deadline": iso_ms(t + 600000), "state": "REGISTERED", "registeredAt": iso_ms(t),
                            "authorizedAt": None, "capturedAt": None, "closedAt": None, "refunds": 0, "tombstone": False})
        return self.intents[-1], self.ledger[-1]

    def free(self, seats):
        return all(s not in self.owner for s in seats)

    def take(self, seats, sid, until):
        """좌석 점유 — until = 그 세션 번호에 이르면 풀린다(이탈 · 첫 석만 · 결제 뒤 거부를 만료로 흉내 · None = 확정 — 풀리지 않는다)."""
        for s in seats:
            self.owner[s] = sid
            self.until[s] = until

    def expire(self, sid):
        for s in [x for x, u in self.until.items() if u is not None and u <= sid]:
            self.owner.pop(s, None)
            self.until.pop(s, None)

    # ---- 세션 ----
    def build(self):
        p = self.plan
        H, cyc, app = p["seed"]["H"], p["kind_cycle"], p["app"]
        ttl = app["ttl_s"] * 1000
        for sid in range(p["n_total"]):
            kind = cyc[sid % len(cyc)]
            a = plan18.session_attrs(self.rep, sid, p["params"])
            seats = plan18.seats_for(self.cell, self.rep, p["n_total"], sid, a["U"], H)
            user = plan18.USER_BASE + sid
            t = T0 + SESSION_START_MS + sid * 50   # 첫 세션 = k6 시작 + 6초(첫 5초 지연 표본 제외 규칙 밖 — 고정 값 2026-11-20)
            hat = {"at": t}   # 첫 선점 예정 송신(본 · 경계 셀 = 스크립트 시작 + sid ÷ 도착률 — 리뷰 H3-5 · R5)
            if self.cell == "burst":   # 묶음 예정 송신 + 구성원마다 0~2ms 어긋남(실측 폭 흉내)
                bk, _, last = plan18.burst_of(self.rep, p["n_total"], sid)
                at_ms = T0 + SESSION_START_MS + (last / p["rate"] + plan18.BURST_LEAD_S) * 1000
                t = at_ms + sid % 3
                hat = {"at": at_ms}
            row = {"k": "s", "v": 1, "sid": sid, "kind": kind, "rep": self.rep, "method": self.method, "cell": self.cell, "user": user, "seats": seats,
                   **a, "hold": [], "rel": [], "ps": [], "pay": [], "cf": [], "got": None, "end": None}
            if self.cell == "burst":
                row["burst"] = plan18.burst_of(self.rep, p["n_total"], sid)[0]
                row["pre"] = {"st": 200, "ts": t - 150, "tr": t - 148, "cn": 0.4}
            self.rows.append(row)
            self.expire(sid)
            if self.group:
                if self.free(seats):
                    self.ids["group"] += 1
                    gid = self.ids["group"]
                    hids = [self.ids["hold"] + 1, self.ids["hold"] + 2]
                    self.ids["hold"] += 2
                    h = self.resp("hold_group", 201, "OK", t, seats=seats, rj=None, gid=gid, hids=hids, exp=t + 5 + ttl, ok=True, cn=0, bl=0, **hat)
                    self.groups.append({"id": gid, "schedule_id": 1, "user_id": user, "seat_count": 2, "created_ms": t})
                    self.take(seats, sid, sid + 4)
                    row["hold"].append(h)
                    ids, seats_of, hold_ids = [gid], [seats], [hids[0]]
                else:
                    cause = "nowait" if self.method == "split" else "short"
                    key = f"{plan18.METRIC_MODE[self.method]}:{cause}"
                    self.grj[key] = self.grj.get(key, 0) + 1
                    row["hold"].append(self.resp("hold_group", 409, "SEAT_NOT_AVAILABLE", t, seats=seats, rj=f"group-{cause}", gid=None, hids=[], exp=None, ok=False, cn=0, bl=0, **hat))
                    row["got"], row["end"] = "none", "hold"
                    continue
            else:
                hs = []
                for i, s in enumerate(seats):
                    ts = t + i * 10
                    if s in self.owner:
                        cause = "nowait" if sid % 2 else "rule"
                        self.hrj[cause] = self.hrj.get(cause, 0) + 1
                        row["hold"].append(self.resp("hold", 409, "SEAT_NOT_AVAILABLE", ts, seat=s, rj=cause, hid=None, exp=None, ok=False, **(hat if i == 0 else {})))
                        break
                    self.ids["hold"] += 1
                    h = self.resp("hold", 201, "OK", ts, seat=s, rj=None, hid=self.ids["hold"], exp=ts + 5 + ttl, ok=True, **(hat if i == 0 else {}))
                    self.take([s], sid, sid + 4)
                    row["hold"].append(h)
                    hs.append(h)
                if not hs:
                    row["got"], row["end"] = "none", "hold"
                    continue
                if len(hs) == 1:
                    row["got"], row["end"] = "first", "partial"
                    if self.method == "B1":
                        row["rel"].append(self.resp("release", 204, "OK", hs[0]["tr"] + 30, id=hs[0]["hid"], ok=True))
                        self.owner.pop(seats[0], None)
                        self.until.pop(seats[0], None)
                    continue
                ids, seats_of, hold_ids = [hs[0]["hid"], hs[1]["hid"]], [[seats[0]], [seats[1]]], [hs[0]["hid"], hs[1]["hid"]]
            row["got"] = "both"
            last_tr = row["hold"][-1]["tr"]
            if kind == "abandon":
                row["end"] = "abandon"
                continue
            if kind == "release":
                for i, x in enumerate(ids):
                    row["rel"].append(self.resp("release_group" if self.group else "release", 204, "OK", last_tr + a["D"] * 1000 + i * 10, id=x, ok=True))
                if self.cell != "burst":   # 동시 경합 묶음 안에서는 해제(D초 뒤)가 같은 순간의 다른 선점보다 늦다 — 풀지 않는다
                    for s in seats:
                        self.owner.pop(s, None)
                        self.until.pop(s, None)
                row["end"] = "release"
                continue
            psop = "pay_start_group" if self.group else "pay_start"
            ps_at = row["hold"][0]["exp"] + (p["params"]["psCenter"] + a["X"]) * 1000 if kind == "edge_ps" else None
            if kind == "edge_ps" and ps_at >= row["hold"][0]["exp"]:   # E 뒤 결제 시작 → 409(가짜 세계의 기대 · E 앞은 θ = 0이라 연장 뒤 확정)
                at = ps_at
                row["ps"].append(self.resp(psop, 409, "HOLD_EXPIRED", at + 2, id=ids[0], at=at, exp=None, pb=None, pws=None, ext=None, uid=None, ok=False))
                row["end"] = "pay_start"
                for s in seats:
                    self.owner.pop(s, None)
                continue
            # ok · edge_confirm — 결제 시작(id마다)
            pst = ps_at + 2 if ps_at is not None else last_tr + (a["T"] * 1000 if kind == "ok" else 1)
            uids = []
            for i, x in enumerate(ids):
                uid = f"u-{sid:04d}-{i}"
                exp = row["hold"][0]["exp"] if kind == "edge_confirm" else pst + app["W_s"] * 1000
                row["ps"].append(self.resp(psop, 200, "OK", pst + i * 10, id=x, at=ps_at, exp=exp, pb=exp - 1000, pws=600, ext=kind != "edge_confirm", uid=uid,
                                           ok=True))
                self.intent(uid, row["user"], hold_ids[i], seats_of[i][0], len(seats_of[i]), ids[0] if self.group else None, pst)
                uids.append(uid)
            pt = row["ps"][-1]["tr"] + (a["P"] * 1000 if kind == "ok" else 1)
            for i, uid in enumerate(uids):
                row["pay"].append(self.resp("pg_pay", 200, "OK", pt + i * 10, uid=uid, state="CAPTURED", ok=True))
                led = next(x for x in self.ledger if x["uid"] == uid)
                led.update(state="CAPTURED", capturedAt=iso_ms(pt + i * 10))
            at = row["ps"][0]["exp"] + a["X"] * 1000 if kind == "edge_confirm" else row["pay"][-1]["tr"] + a["G"] * 1000
            cfop = "confirm_group" if self.group else "confirm"
            for i, uid in enumerate(uids):
                ts = at + 3 + i * 10
                it = next(x for x in self.intents if x["uid"] == uid)
                led = next(x for x in self.ledger if x["uid"] == uid)
                if kind == "edge_confirm" and a["X"] >= 0:   # 만료 뒤 확정 → 거부 · 환불(refund)
                    row["cf"].append(self.resp(cfop, 409, "HOLD_EXPIRED", ts, id=ids[i], uid=uid, at=at if i == 0 else None, rids=[], rseats=[],
                                               cp={"type": "CLOSE", "status": "PENDING"}, ok=False))
                    self.rej.append({"id": len(self.rej) + 1, "uid": uid, "code": "HOLD_EXPIRED", "http_status": 409, "rejected_ms": ts})
                    it.update(state="REJECTED", result_code="HOLD_EXPIRED")
                    self.task(uid, "REJECTION", ts)
                    led.update(state="REFUNDED", closedAt=iso_ms(ts + 500), refunds=1)
                    continue
                rids = []
                for s in seats_of[i]:
                    self.ids["res"] += 1
                    rids.append(self.ids["res"])
                    self.res.append({"id": self.ids["res"], "schedule_id": 1, "seat_id": s, "user_id": row["user"], "payment_uid": uid, "status": "CONFIRMED",
                                     "intent_id": uid, "origin": "HOLD", "created_ms": ts})
                it.update(state="CONFIRMED", reservation_id=rids[0])
                self.take(seats_of[i], sid, None)
                row["cf"].append(self.resp(cfop, 200, "OK", ts, id=ids[i], uid=uid, at=at if i == 0 else None, rids=rids, rseats=list(seats_of[i]), cp=None, ok=True))
            row["end"] = "done"

    # ---- 파일 ----
    def act(self, end):
        base = {"t": iso(T0_PC), "t_end": iso(T0_PC), "base": "http://x", "process_start_s": 1.7e9,
                "group_request": {}, "group_rejected": {}, "hold_rejected": {"rule": 7}, "deadlock": {}, "expiry_tick": {"ok": 3},
                "compensation": {"done": {}, "retry": {}, "dead": {}, "unresolved": 0, "record_failed": 0, "sweep_failed": 0}}
        if not end:
            return base
        b = copy.deepcopy(base)
        for k, v in self.req.items():
            b["group_request"][k] = b["group_request"].get(k, 0) + v
        for k, v in self.grj.items():
            b["group_rejected"][k] = v
        for k, v in self.hrj.items():
            b["hold_rejected"][k] = b["hold_rejected"].get(k, 0) + v
        for k, v in self.dead.items():
            b["deadlock"][k] = v
        b["expiry_tick"]["ok"] = 50
        b["compensation"]["done"] = {"CLOSE:REFUNDED": len(self.tasks)} if self.tasks else {}
        return b

    def write(self, d):
        d = Path(d)
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)
        p = self.plan
        (d / "plan.json").write_text(json.dumps(p))
        (d / "harness-reasons.txt").write_text("")
        with gzip.open(d / "sessions18.log.gz", "wt") as f:
            for r in self.rows:
                f.write(json.dumps({"level": "info", "msg": json.dumps(r), "source": "console"}) + "\n")
        (d / "k6-summary.json").write_text(json.dumps({"metrics": {}, "adr018": {"rep": str(self.rep), "method": self.method, "cell": self.cell, "rate": p["rate"],
                                                                                 "d_arr_s": p["d_arr_s"], "plan_n": p["n_total"], "cycle": p["kind_cycle"],
                                                                                 "params": p["params"], "h": p["seed"]["H"], "pg_url": "http://x:8104"}}))
        (d / "k6-window.json").write_text(json.dumps({"k6_t0_epoch": T0_PC, "k6_end_epoch": T0_PC + 200, "exit": 0, "clock_offset_ms_given": 0}))
        (d / "clock-offset.json").write_text(json.dumps({"before": {"offset_s": 0.0, "rtt_s": 0.001, "at": iso(T0_PC)}, "after": {"offset_s": 0.0, "rtt_s": 0.001}}))
        (d / "pc-link.json").write_text(json.dumps({"readable": True, "iface": "eth0", "since_epoch": T0_PC - 100, "until_epoch": T0_PC + 300, "down": 0, "lines": [], "why": None}))
        with open(d / "timeline-client.jsonl", "w") as f:
            for i in range(0, 41):
                f.write(json.dumps({"t": iso(T0_PC + i * 5), "k6_pcpu": 10, "k6_rss_kb": 100000, "load": "1 1 1", "mem_avail_kb": 8 * 1024 * 1024}) + "\n")
        (d / "inject.json").write_text(json.dumps({"cell": self.cell, "events": [
            {"at": "reset", "t_ms": T0 - 20000, "rc": 0, "http": 204, "request": None, "response": None},
            {"at": "faults", "t_ms": T0 - 19000, "rc": 0, "http": 200, "request": p["pg"]["faults"], "response": copy.deepcopy(p["pg"]["faults"])}]}))
        (d / "drain.json").write_text(json.dumps({"start_ms": T0 + 200000, "end_ms": T0 + 215000, "reason": "empty", "cap_s": p["drain"]["cap_s"], "poll_s": 5,
                                                  "polls": [{"t_ms": T0 + 200000, "pending": 0, "open": 0, "open_unheld": 0, "holds": 0},
                                                            {"t_ms": T0 + 207000, "pending": 0, "open": 0, "open_unheld": 0, "holds": 0}],
                                                  "dump": {"attempts": 1, "max": 1, "stable": True}}))
        ops = {"register": len(self.ledger), "pay": sum(1 for r in self.rows for x in r["pay"] if x["ok"]), "close": len(self.tasks)}
        (d / "pg-ledger.json").write_text(json.dumps({"payments": sorted(self.ledger, key=lambda x: x["uid"]), "ops": {k: v for k, v in ops.items() if v}}))
        (d / "db-dump.json").write_text(json.dumps({"t_ms": T0 + 216000, "payment_intent": sorted(self.intents, key=lambda x: x["uid"]), "reservation": self.res,
                                                    "confirm_rejection": self.rej, "compensation_task": self.tasks, "hold_group": self.groups, "seat_hold": []}))
        (d / "consistency.json").write_text(json.dumps(cons_zero()))
        (d / "consistency-k6.json").write_text(json.dumps(cons_zero()))
        (d / "act18-before.json").write_text(json.dumps(self.act(False)))
        (d / "act18-after-k6.json").write_text(json.dumps(self.act(True)))
        (d / "act18-end.json").write_text(json.dumps(self.act(True)))
        (d / "app-config.json").write_text(json.dumps(app_config(self.method, self.cell)))
        with gzip.open(d / "app.log.gz", "wt") as f:   # 앱 로그(요청 예외 없음 — §6.13)
            f.write(f"{iso_ms(T0 - 60000)}  INFO 1 --- [seat-reservation-lab] [           main] c.j.l.s.SeatReservationApplicationKt     : Starting SeatReservationApplicationKt v0.0.1\n")
        per = p["hold_sample_s"] * 1000
        with open(d / "holds-sample.jsonl", "w") as f:   # 부하 중 홀드 표본(§6.12): k6 창 전체(200초)에 주기마다 · 부분 선점 0 · 넷째 표본에서 잡을 쌍 0
            for i in range(int(200000 // per)):
                f.write(json.dumps({"t_ms": T0 + 500 + i * per, "holds": 12, "group_holds": 12 if self.group else 0, "groups_live": 6 if self.group else 0,
                                    "partial": 0, "split": 0, "available": 60 - 5 * i, "free_pairs": max(0, 3 - i), "q_ms": 1.5, "pc_t0_ms": T0 + i * per, "pc_t1_ms": T0 + i * per + 20}) + "\n")
        (d / "seed-check.json").write_text(json.dumps({"schedules": 1, "seats": p["seed"]["seats"], "available": p["seed"]["seats"], "holds": 0, "reservations": 0,
                                                       "quota_rows": 0, "flyway": [str(i) for i in range(1, 17)]}))
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(["metric_name", "timestamp", "metric_value", "check", "error", "error_code", "expected_response", "group", "method", "name", "proto",
                    "scenario", "service", "status", "subproto", "tls_version", "url", "extra_tags", "metadata"])
        for op, ms, st, tsec in self.csv:
            w.writerow(["http_req_duration", str(tsec), f"{ms}", "", "", "", "true", "", "POST", op, "HTTP/1.1", "main", "", str(st), "", "", "", "", ""])
            w.writerow(["http_reqs", str(tsec), "1", "", "", "", "true", "", "POST", op, "HTTP/1.1", "main", "", str(st), "", "", "", "", ""])
        with gzip.open(d / "k6-requests.csv.gz", "wt", newline="") as f:
            f.write(buf.getvalue())
        (d / "meta.json").write_text(json.dumps({"sha": "deadbeef", "harness_hash": "h" * 64, "method": self.method, "cell": self.cell, "rep": self.rep}))
        return d


# ---- 파일 편집 도우미 ------------------------------------------------------------------------------------------------------------
def summ(d):
    s, st = SM.rep_summary(d)
    return s, st


def reasons_of(st):
    return st.split(":", 1)[1].split(",") if st and st.startswith("invalid:") else []


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


def read_rows(d):
    return [json.loads(json.loads(x)["msg"]) for x in gzip.open(Path(d, "sessions18.log.gz"), "rt") if x.strip()]


def write_rows(d, rows):
    with gzip.open(Path(d, "sessions18.log.gz"), "wt") as fo:
        for r in rows:
            fo.write((json.dumps({"level": "info", "msg": json.dumps(r)}) if isinstance(r, dict) else r) + "\n")


def rows_edit_path(f):
    return lambda d: rows_edit(f)(d, None)


def rows_edit(f):
    def go(d, w):
        rows = read_rows(d)
        rows = f(rows) or rows
        write_rows(d, rows)
    return go


def first(rows, pred):
    return next(r for r in rows if pred(r))


# ---- §4 shape 표 ----------------------------------------------------------------------------------------------------------------
def test_shapes():
    A = AMOUNT

    def E(**kw):
        e = {"intent_state": "CONFIRMED", "seat_count": 2, "res": [], "pg_state": "CAPTURED", "refunds": 0, "captured": True, "amount": 2 * A, "tombstone": False}
        e.update(kw)
        return e
    R = lambda n: [{"id": i, "seat_id": i, "status": "CONFIRMED"} for i in range(n)]  # noqa: E731
    cases = [
        ("double", E(res=R(3)), "double_reservation"),
        ("double-1seat", E(seat_count=1, amount=A, res=R(2)), "double_reservation"),
        ("double-refund", E(refunds=2, pg_state="REFUNDED"), "double_refund"),
        ("refunded-seat", E(res=R(2), pg_state="REFUNDED", refunds=1), "refunded_with_seat"),
        ("partial", E(res=R(1)), "partial_reservation"),
        ("amount", E(res=R(2), amount=A), "amount_mismatch"),
        ("paid", E(res=R(2)), "seat_paid"),
        ("paid-1seat", E(seat_count=1, amount=A, res=R(1)), "seat_paid"),
        ("moneyless", E(res=R(2), pg_state="REGISTERED", captured=False), "seat_moneyless"),
        ("refunded", E(pg_state="REFUNDED", refunds=1), "refunded"),
        ("closed", E(pg_state="CLOSED", captured=False), "closed_unpaid"),
        ("orphan", E(), "orphan"),
        ("orphan-closed", E(pg_state="CLOSED"), "orphan"),
        ("open", E(pg_state="REGISTERED", captured=False), "open_unpaid"),
        ("no-ledger", E(pg_state=None, captured=False, amount=None), "no_ledger"),
        ("no-intent", E(intent_state=None, seat_count=None, pg_state="VOIDED", captured=False), "no_intent"),
        ("no-intent-closed-paid", E(intent_state=None, seat_count=None, pg_state="CLOSED"), "no_intent"),
        ("tombstone-amount", E(res=R(2), amount=None, tombstone=True), "seat_paid"),
    ]
    for name, e, want in cases:
        got = O.shape(e, A)
        check(f"shape/{name}", got == want, f"{got} ≠ {want}")
    check("shape/all-named", set(w for _, _, w in cases) == set(O.SHAPES), set(O.SHAPES) - set(w for _, _, w in cases))


# ---- §5 결정표 ------------------------------------------------------------------------------------------------------------------
def test_classify():
    wg = World("split", "low", 1)
    wb0 = World("B0", "high", 1)
    wb1 = World("B1", "high", 1)
    we = World("one-shot", "edge", 1)
    for w in (wg, wb0, wb1, we):
        led = {"payments": w.ledger}
        db = {"payment_intent": w.intents, "reservation": w.res, "confirm_rejection": w.rej, "compensation_task": w.tasks}
        w.ends = O.end_states(db, led, AMOUNT)

    def case(name, w, row, want, ends=None):
        got = O.classify(row, w.ends if ends is None else ends, w.plan)
        check(f"classify/{name}", got == want, f"{got} ≠ {want}")

    def pick(w, pred):
        return copy.deepcopy(first(w.rows, pred))
    # 기대 갈래(가짜 세계 전부)
    exp_cat = {}
    for w in (wg, wb0, wb1, we):
        for r in w.rows:
            c = O.classify(r, w.ends, w.plan)
            exp_cat[(w.method, c)] = exp_cat.get((w.method, c), 0) + 1
            if r["got"] == "none":
                ok = c == "lost"
            elif r["got"] == "first":
                ok = c == ("partial_kept" if w.method == "B0" else "partial_released")
            elif r["kind"] == "abandon":
                ok = c == "abandoned"
            elif r["kind"] == "release":
                ok = c == "released"
            elif r["kind"] == "edge_ps":
                ok = c == ("ps_rejected" if r["X"] >= 0 else "success")
            elif r["kind"] == "edge_confirm":
                ok = c == ("confirm_rejected" if r["X"] >= 0 else "success")
            else:
                ok = c == "success"
            check(f"classify/world/{w.method}/{w.cell}/{r['sid']}", ok, f"{r['kind']} {r['got']} → {c}")
    for m, c in (("split", "success"), ("split", "lost"), ("split", "released"), ("split", "abandoned"), ("B0", "partial_kept"), ("B1", "partial_released"),
                 ("B0", "success"), ("one-shot", "ps_rejected"), ("one-shot", "confirm_rejected")):
        check(f"classify/world-has/{m}/{c}", exp_cat.get((m, c), 0) > 0, "가짜 세계에 그 갈래가 없다(시험 자료 보강 필요)")
    ok = pick(wg, lambda r: r["kind"] == "ok" and r["got"] == "both")
    case("A-row-kind", wg, dict(ok, kind="release"), "invalid:row-bad")
    case("A-row-method", wg, dict(ok, method="one-shot"), "invalid:row-bad")
    case("A-row-field", wg, {k: v for k, v in ok.items() if k != "cf"}, "invalid:row-bad")
    case("A-row-end", wg, dict(ok, end="pg"), "invalid:row-bad")
    case("A-row-got", wg, dict(ok, got="first"), "invalid:row-bad")
    case("A-row-cf-count", wg, dict(ok, cf=ok["cf"] * 2), "invalid:row-bad")
    x = copy.deepcopy(ok)
    x["pay"][0]["st"] = 0
    x["pay"][0]["ok"] = False
    x["cf"], x["end"] = [], "pg"
    case("B-conn-pay", wg, x, "invalid:conn-pg_pay")
    x = copy.deepcopy(ok)
    x["cf"][0].update(st=0, ok=False, code="NOCODE", rids=[], rseats=[])
    case("B-conn-confirm", wg, x, "invalid:conn-confirm_group")
    x = copy.deepcopy(ok)
    x["cf"][0].update(st=500, ok=False, code="NOCODE", rids=[], rseats=[])
    case("C-5xx-main", wg, x, "fail:5xx-confirm_group")
    ec = pick(we, lambda r: r["kind"] == "edge_confirm" and r["end"] == "done")
    ec["cf"][0].update(st=500, ok=False, code="NOCODE", rids=[], rseats=[])
    case("C-5xx-edge", we, ec, "edge_5xx")
    lost = pick(wg, lambda r: r["got"] == "none")
    case("D-lost", wg, lost, "lost")
    case("D-limit", wg, dict(lost, hold=[dict(lost["hold"][0], code="HOLD_LIMIT_EXCEEDED")]), "integrity:hold-limit")
    case("D-hold-400", wg, dict(lost, hold=[dict(lost["hold"][0], st=400, code="INVALID_SEAT_SELECTION")]), "unexpected:hold_group-400-INVALID_SEAT_SELECTION")
    case("D-hold-201-body", wg, dict(lost, hold=[dict(lost["hold"][0], st=201, code="OK")]), "integrity:hold-body")   # 앱 계약 위반 = G1(H2-5)
    case("D-hold-201-partial", wg, dict(lost, hold=[dict(lost["hold"][0], st=201, code="OK", gid=5, hids=[1])]), "integrity:hold-body")
    case("D-hold1-201-body", wb0, dict(pick(wb0, lambda r: r["got"] == "none"), hold=[dict(pick(wb0, lambda r: r["got"] == "none")["hold"][0], st=201, code="OK")]),
         "integrity:hold-body")
    burst_row = pick(World("split", "burst", 1), lambda r: r["got"] == "none")
    wbu = World("split", "burst", 1)
    wbu.ends = {}
    case("H2-7-preconnect-0", wbu, dict(burst_row, pre={"st": 0, "ts": 1, "tr": 2, "cn": 0}), "invalid:conn-preconnect")
    case("H2-7-preconnect-503", wbu, dict(burst_row, pre={"st": 503, "ts": 1, "tr": 2, "cn": 0}), "invalid:preconnect")
    pk = pick(wb0, lambda r: r["got"] == "first")
    case("D-partial-kept", wb0, pk, "partial_kept")
    case("D-partial-limit", wb0, dict(pk, hold=[pk["hold"][0], dict(pk["hold"][1], code="HOLD_LIMIT_EXCEEDED")]), "integrity:hold-limit")
    pr = pick(wb1, lambda r: r["got"] == "first")
    case("D-partial-released", wb1, pr, "partial_released")
    case("D-partial-release-404", wb1, dict(pr, rel=[dict(pr["rel"][0], st=404, code="HOLD_NOT_FOUND", ok=False)]), "unexpected:release-404-HOLD_NOT_FOUND")
    case("D-B0-with-release", wb0, dict(pk, rel=[{"op": "release", "id": pk["hold"][0]["hid"], "st": 204, "ok": True}]), "invalid:row-bad")
    ab = pick(wg, lambda r: r["kind"] == "abandon" and r["got"] == "both")
    case("E-abandon", wg, ab, "abandoned")
    rl = pick(wg, lambda r: r["kind"] == "release" and r["got"] == "both")
    case("E-release", wg, rl, "released")
    case("E-release-409", wg, dict(rl, rel=[dict(rl["rel"][0], st=409, code="PAYMENT_STARTED", ok=False)]), "unexpected:release_group-409-PAYMENT_STARTED")
    # 결제 시작 거부(묶음 1번 · B의 둘째)
    x = copy.deepcopy(ok)
    x.update(ps=[dict(x["ps"][0], st=409, code="HOLD_EXPIRED", ok=False, uid=None)], pay=[], cf=[], end="pay_start")
    case("E-ps-reject", wg, x, "ps_rejected")
    x2 = copy.deepcopy(x)
    x2["ps"][0].update(st=503, code="PG_UNAVAILABLE")
    case("E-ps-503", wg, x2, "fail:5xx-pay_start_group")
    x2["ps"][0].update(st=403, code="HOLD_NOT_OWNED")
    case("E-ps-403", wg, x2, "unexpected:pay_start_group-403-HOLD_NOT_OWNED")
    x2["ps"][0].update(st=200, code="OK")
    case("E-ps-body", wg, x2, "integrity:ps-body")
    bok = pick(wb0, lambda r: r["kind"] == "ok" and r["got"] == "both")
    xb = copy.deepcopy(bok)
    xb.update(ps=[xb["ps"][0], dict(xb["ps"][1], st=404, code="HOLD_NOT_FOUND", ok=False, uid=None)], pay=[], cf=[], end="pay_start")
    ends = copy.deepcopy(wb0.ends)
    u0 = xb["ps"][0]["uid"]
    ends[u0] = dict(ends[u0], shape="closed_unpaid")
    case("E-ps-second-reject", wb0, xb, "ps_rejected", ends=ends)
    ends[u0] = dict(ends[u0], shape="open_unpaid")
    case("E-ps-second-left-open", wb0, xb, "integrity:ps-left-open_unpaid", ends=ends)
    # PG
    x = copy.deepcopy(ok)
    x.update(pay=[dict(x["pay"][0], st=409, code="DEADLINE_PASSED", ok=False, state="REGISTERED")], cf=[], end="pg")
    ends = copy.deepcopy(wg.ends)
    ends[x["ps"][0]["uid"]] = dict(ends[x["ps"][0]["uid"]], shape="closed_unpaid")
    case("E-pg-deadline", wg, x, "pg_rejected", ends=ends)
    ends[x["ps"][0]["uid"]] = dict(ends[x["ps"][0]["uid"]], shape="orphan")
    case("E-pg-deadline-orphan", wg, x, "integrity:pg-orphan", ends=ends)
    x["pay"][0].update(st=500, code="NOCODE")
    case("E-pg-500", wg, x, "invalid:pg-500-NOCODE")
    # 확정
    case("F-success", wg, ok, "success")
    ends = copy.deepcopy(wg.ends)
    uid = ok["cf"][0]["uid"]
    ends[uid] = dict(ends[uid], shape="orphan")
    case("F-200-orphan", wg, ok, "integrity:200-orphan", ends=ends)
    x = copy.deepcopy(ok)
    x["cf"][0]["rids"] = [999, 998]
    case("F-200-mismatch", wg, x, "integrity:200-mismatch")
    x = copy.deepcopy(ok)
    x["cf"][0]["rseats"] = list(reversed(x["cf"][0]["rseats"]))
    case("F-200-seat-mismatch", wg, x, "integrity:200-mismatch")
    x = copy.deepcopy(ok)
    x["cf"][0].update(ok=False)
    case("F-confirm-body", wg, x, "integrity:confirm-body")
    rj = pick(we, lambda r: r["kind"] == "edge_confirm" and r["end"] == "done" and r["X"] >= 0)
    case("F-reject-refunded", we, rj, "confirm_rejected")
    ends = copy.deepcopy(we.ends)
    ends[rj["cf"][0]["uid"]] = dict(ends[rj["cf"][0]["uid"]], shape="orphan")
    case("F-reject-orphan", we, rj, "integrity:reject-orphan", ends=ends)
    x = copy.deepcopy(rj)
    x["cf"][0].update(st=409, code="PAYMENT_STARTED")
    case("F-confirm-409-other", we, x, "unexpected:confirm_group-409-PAYMENT_STARTED")
    # B 혼합(첫 확정 200 · 둘째 거부 → 환불)
    xb = copy.deepcopy(bok)
    u1 = xb["cf"][1]["uid"]
    xb["cf"][1].update(st=409, code="HOLD_EXPIRED", ok=False, rids=[], rseats=[])
    ends = copy.deepcopy(wb0.ends)
    ends[u1] = dict(ends[u1], shape="refunded")
    case("F-mixed", wb0, xb, "confirm_mixed", ends=ends)
    case("F-mixed-not-refunded", wb0, xb, "integrity:reject-seat_paid")
    # metric_outcome
    check("metric_outcome/ok", O.metric_outcome({"st": 204, "code": "OK"}) == "ok")
    check("metric_outcome/code", O.metric_outcome({"st": 409, "code": "SEAT_NOT_AVAILABLE"}) == "SEAT_NOT_AVAILABLE")
    check("metric_outcome/status", O.metric_outcome({"st": 500, "code": "NOCODE"}) == "status_500")
    check("metric_outcome/group-body-400", O.metric_outcome({"st": 400, "code": "INVALID_SEAT_SELECTION"}) == "INVALID_SEAT_SELECTION")
    check("metric_outcome/conn", O.metric_outcome({"st": 0, "code": "NOCODE"}) is None)


# ---- 회차 판정(§7 · §8) --------------------------------------------------------------------------------------------------------
def test_runs(tmp):
    for cell in plan18.CELLS:
        for method in plan18.methods_of(cell):
            w = World(method, cell, 1)
            d = w.write(tmp / f"ok-{method}-{cell}")
            s, st = summ(d)
            check(f"run-ok/{method}/{cell}", st == "ok", st)
            check(f"run-g1/{method}/{cell}", s and s["g1"]["ok"], json.dumps(s["g1"] if s else None)[:400])
            got = {"both": 0, "first": 0, "none": 0}
            for r in w.rows:
                got[r["got"]] += 1
            check(f"run-hold/{method}/{cell}", s and all(s["hold"][k] == v for k, v in got.items()) and s["hold"]["n"] == len(w.rows), json.dumps(s["hold"]))
            check(f"run-first/{method}/{cell}", (s["hold"]["first_only"] is not None) == (method in ("B0", "B1")), method)
            check(f"run-metric/{method}/{cell}", s["metric"] and s["metric"]["k6_group_request"] == w.req, json.dumps(s["metric"])[:300])
            check(f"run-latency/{method}/{cell}", s["latency"]["hold"]["p99_ms"] == LAT_MS, json.dumps(s["latency"]))
            if cell == "edge":
                check(f"run-edge/{method}", s["edge"] and s["edge"]["in_window"] > 0 and s["edge"]["http_5xx_link"] == {"40p01": 0, "other": 0, "unlinked": 0, "csv_only": 0}
                      and all(x["sent"] > 0 for x in s["edge"]["exposure"].values()), json.dumps(s["edge"])[:300])
    # 첫 5초 지연 표본 제외(고정 값 2026-11-20): k6 시작 4초에 끝난 느린 선점 2건 → p99 판정 표본에서 빠지고 warmup_excluded로 보고
    def warm_slow(d, w):
        _csv_add(d, ["http_req_duration", str(int(T0_PC + 4)), "900.0", "", "", "", "true", "", "POST", "hold_group", "HTTP/1.1", "main", "", "201", "", "", "", "", ""])
    def late_slow(d, w):
        _csv_add(d, ["http_req_duration", str(int(T0_PC + 30)), "900.0", "", "", "", "true", "", "POST", "hold_group", "HTTP/1.1", "main", "", "201", "", "", "", "", ""])
    wv = World("split", "low", 2)
    dw = wv.write(tmp / "warm-early")
    warm_slow(dw, wv)
    lat_early = SM.latency_of(SM.k6_csv(dw / "k6-requests.csv.gz")["ms"]["hold_group"], T0_PC, plan18.WARMUP_EXCLUDE_S)
    check("warmup/excluded", lat_early["warmup_excluded"] == 1 and lat_early["p99_ms"] == LAT_MS, lat_early)
    dl_ = wv.write(tmp / "warm-late")
    late_slow(dl_, wv)
    lat_late = SM.latency_of(SM.k6_csv(dl_ / "k6-requests.csv.gz")["ms"]["hold_group"], T0_PC, plan18.WARMUP_EXCLUDE_S)
    check("warmup/not-excluded-after-5s", lat_late["warmup_excluded"] == 0 and lat_late["p99_ms"] == 900.0, lat_late)
    s_ok, _ = summ(tmp / "ok-split-low")
    check("warmup/reported", all(s_ok["latency"][st]["warmup_excluded"] == 0 for st in ("hold", "pay_start", "confirm")), json.dumps(s_ok["latency"]))
    check("warmup/unknown-start", SM.latency_of([(900.0, T0_PC + 1)], None, 5.0)["warmup_excluded"] == 0)
    # B0 · B1 비교 지표가 실제로 갈린다(가짜 세계 high — B0은 첫 석을 쥐고 B1은 놓는다)
    b0, _ = summ(tmp / "ok-B0-high")
    b1, _ = summ(tmp / "ok-B1-high")
    check("compare/B0-blocks", b0["hold"]["first_only"]["n"] > 0 and b0["hold"]["first_only"]["held_s"]["p50"] > 5, json.dumps(b0["hold"]["first_only"]))
    check("compare/B1-short", b1["hold"]["first_only"]["n"] > 0 and b1["hold"]["first_only"]["held_s"]["max"] < 1, json.dumps(b1["hold"]["first_only"]))
    rows_b0 = read_rows(tmp / "ok-split-burst")   # 경합 쌍 수를 정의(§8.2)대로 전수 비교로 다시 센다(묶음 세계 — 선점 창이 겹친다)
    sbu, _ = summ(tmp / "ok-split-burst")
    pairs_bf = [0, 0]
    for i, x in enumerate(rows_b0):
        for y in rows_b0[i + 1:]:
            wx, wy = (x["hold"][0]["ts"], x["hold"][-1]["tr"]), (y["hold"][0]["ts"], y["hold"][-1]["tr"])
            if abs(x["seats"][0] - y["seats"][0]) <= 1 and wx[0] <= wy[1] and wy[0] <= wx[1]:
                pairs_bf[0] += 1
                pairs_bf[1] += x["got"] != "both" and y["got"] != "both"
    check("compare/pairs", [sbu["hold"]["both_fail_pairs"]["pairs"], sbu["hold"]["both_fail_pairs"]["both_fail"]] == pairs_bf and pairs_bf[0] > 0,
          (sbu["hold"]["both_fail_pairs"], pairs_bf))
    check("compare/pairs-B0-none", b0["hold"]["both_fail_pairs"]["pairs"] == 0)   # 50ms 간격 세계 — 선점 창이 안 겹친다
    check("compare/two-seat-ms", b0["hold"]["two_seat_ms"]["n"] == b0["hold"]["both"] and b0["hold"]["two_seat_ms"]["max"] == 15, json.dumps(b0["hold"]["two_seat_ms"]))
    sp = b0["hold_samples"]
    check("samples/stock-out", sp and sp["live_n"] == 100 and sp["partial_max"] == 0 and sp["stock_out_s"] == 6.5 and sp["max_gap_s"] <= 2.0
          and sp["fail_ratio"] == 0, json.dumps(sp))

    def mutated(name, fn, want, method="split", cell="low", g1_fail=None, ok_status=False):
        w = World(method, cell, 2)
        d = w.write(tmp / f"m-{name}")
        fn(d, w)
        s, st = summ(d)
        got = reasons_of(st)
        if want is not None:
            check(f"invalid/{name}", want in got, st)
        if ok_status:
            check(f"status-ok/{name}", st == "ok", st)
        if g1_fail is not None:
            check(f"g1/{name}", s["g1"]["ok"] is False and s["g1"]["items"].get(g1_fail) not in (0, None), json.dumps(s["g1"]["items"])[:500])
        return s, st

    mutated("harness-reasons-missing", rm("harness-reasons.txt"), "harness-reasons-missing")
    _, st = mutated("harness-first", lambda d, w: Path(d, "harness-reasons.txt").write_text("deploy-failed\n"), "deploy-failed")
    check("status-order/harness-first", st.startswith("invalid:deploy-failed"), st)
    _, st = mutated("order", lambda d, w: (Path(d, "harness-reasons.txt").write_text("z-x\n"), rm("seed-check.json", "drain.json")(d, w)), "z-x")
    check("status-order/sorted", st == "invalid:z-x,drain-missing,seed-check-missing", st)
    mutated("sessions-missing", rm("sessions18.log.gz"), "sessions-missing")
    mutated("sessions-broken", rows_edit(lambda rs: ["{not json", *rs]), "sessions-broken")
    _, st = mutated("sessions-torn-tail", rows_edit(lambda rs: [*rs, '{"level":"info","msg":"{\\"k\\":'] ), None)
    check("torn-tail/not-broken", "sessions-broken" not in reasons_of(st), st)
    mutated("sessions-k6-error", rows_edit(lambda rs: [*rs[:3], '{"level":"error","msg":"TypeError"}', *rs[3:]]), "sessions-broken")
    mutated("count", rows_edit(lambda rs: rs[1:]), "sessions-count-mismatch")
    mutated("dup", rows_edit(lambda rs: rs + [rs[0]]), "session-dup")
    for k in ("T", "P", "G", "D", "U", "X"):
        mutated(f"attrs-{k}", rows_edit(lambda rs, k=k: [dict(r, **{k: r[k] + 0.001}) if r["sid"] == 3 else r for r in rs]), "session-attrs-mismatch")
    mutated("attrs-seats", rows_edit(lambda rs: [dict(r, seats=[r["seats"][0] + 1, r["seats"][1] + 1]) if r["sid"] == 3 else r for r in rs]), "session-attrs-mismatch")
    mutated("attrs-user", rows_edit(lambda rs: [dict(r, user=r["user"] + 1) if r["sid"] == 3 else r for r in rs]), "session-attrs-mismatch")
    mutated("session-invalid", rows_edit(lambda rs: [dict(r, end="pg") if r["sid"] == 0 else r for r in rs]), "session-invalid")

    def conn(rs):
        r = first(rs, lambda x: x["got"] == "none")
        r["hold"][0].update(st=0, code="NOCODE", ok=False)
    s, st = mutated("conn", rows_edit(conn), "k6-conn-error")
    check("conn/not-session-invalid", "session-invalid" not in reasons_of(st), st)
    check("conn/env", SM.env_kind(st) == "env", st)
    for f, why in (("pg-ledger.json", "ledger-missing"), ("db-dump.json", "db-dump-missing"), ("consistency.json", "consistency-missing"),
                   ("consistency-k6.json", "consistency-missing"), ("act18-end.json", "act-missing"), ("act18-before.json", "act-missing"),
                   ("act18-after-k6.json", "act-missing"), ("app-config.json", "app-config-missing"), ("seed-check.json", "seed-check-missing"),
                   ("inject.json", "inject-missing"), ("drain.json", "drain-missing"), ("k6-window.json", "k6-exit-unknown"),
                   ("k6-requests.csv.gz", "k6-csv-missing"), ("pc-link.json", "pc-link-missing"), ("timeline-client.jsonl", "pc-mem-missing"),
                   ("k6-summary.json", "k6-plan-mismatch"), ("clock-offset.json", "clock-offset-bad")):
        mutated(f"miss-{f}", rm(f), why)
    mutated("dump-hold-group-missing", edit("db-dump.json", lambda o: o.pop("hold_group")), "db-dump-missing")
    mutated("cons-key", edit("consistency-k6.json", lambda o: o.pop("group_partial_holds")), "consistency-missing")
    mutated("cons-str", edit("consistency.json", lambda o: o.update(v_counter_mismatch="0")), "consistency-missing")
    mutated("act-failed-names", edit("act18-end.json", lambda o: o.update(failed_names=["seat.group.request"])), "act-missing")
    mutated("act-null", edit("act18-end.json", lambda o: o.update(deadlock=None)), "act-missing")
    mutated("act-error", edit("act18-before.json", lambda o: {"error": "metrics-list-failed"}), "act-missing")
    mutated("config-ttl", edit("app-config.json", lambda o: next(b for b in o["configprops"] if b["prefix"] == "seat.hold")["properties"].update(ttl="PT8S")),
            "config-mismatch")
    mutated("config-max-per-user", edit("app-config.json", lambda o: next(b for b in o["configprops"] if b["prefix"] == "seat.hold")["properties"].update(maxPerUser=4)),
            "config-mismatch")
    mutated("hold-group-enabled", edit("app-config.json", lambda o: next(b for b in o["configprops"] if b["prefix"] == "seat.hold-group")["properties"].update(enabled=False)),
            "hold-group-mismatch")
    mutated("hold-group-mode", edit("app-config.json", lambda o: next(b for b in o["configprops"] if b["prefix"] == "seat.hold-group")["properties"].update(mode="ONE_SHOT")),
            "hold-group-mismatch")
    mutated("hold-group-B-enabled", edit("app-config.json", lambda o: next(b for b in o["configprops"] if b["prefix"] == "seat.hold-group")["properties"].update(enabled=True)),
            "hold-group-mismatch", method="B0")
    mutated("config-edge-W", edit("app-config.json", lambda o: next(b for b in o["configprops"] if b["prefix"] == "seat.confirm")["properties"].update(paymentWindow="PT10M11S")),
            "config-mismatch", cell="edge")
    mutated("config-pool", edit("app-config.json", lambda o: o.update({"hikaricp.connections.max": [20]})), "config-mismatch")
    mutated("config-broken", lambda d, w: Path(d, "app-config.json").write_text("{"), "app-config-missing")
    mutated("cons-mode", edit("consistency.json", lambda o: o.update(pay_compensation_mode="none")), "consistency-mode-mismatch")
    mutated("seed", edit("seed-check.json", lambda o: o.update(holds=3)), "seed-mismatch")
    mutated("seed-v16", edit("seed-check.json", lambda o: o.update(flyway=[str(i) for i in range(1, 16)])), "seed-mismatch")
    mutated("seed-seats", edit("seed-check.json", lambda o: o.update(seats=o["seats"] + 1, available=o["seats"] + 1)), "seed-mismatch")
    mutated("k6-exit", edit("k6-window.json", lambda o: o.update(exit=99)), "k6-exit-99")
    mutated("clock", edit("k6-window.json", lambda o: o.update(clock_offset_ms_given=None)), "clock-offset-bad")
    mutated("clock-before", edit("clock-offset.json", lambda o: o.update(before=None)), "clock-offset-bad")
    for k, v in (("plan_n", 999), ("cycle", ["ok"]), ("h", 1), ("method", "B0"), ("cell", "mid"), ("rep", "3")):
        mutated(f"k6-plan-{k}", edit("k6-summary.json", lambda o, k=k, v=v: o["adr018"].update({k: v})), "k6-plan-mismatch")
    mutated("inject-noreset", edit("inject.json", lambda o: o.update(events=o["events"][1:])), "inject-mismatch")
    mutated("inject-seed", edit("inject.json", lambda o: o["events"][1]["response"].update(seed=1)), "inject-mismatch")
    mutated("inject-rc", edit("inject.json", lambda o: o["events"][1].update(rc=7)), "inject-mismatch")
    mutated("inject-leak", edit("inject.json", lambda o: o["events"][1]["response"].update(rules={"close": {"failRate": 0.1}})), "inject-leak")
    mutated("reset-during", edit("inject.json", lambda o: o["events"].append({"at": "reset", "t_ms": T0 + 5000, "rc": 0, "http": 204})), "pg-reset-during-run")
    _, st = mutated("cap-ok", edit("drain.json", lambda o: o.update(reason="cap")), None, ok_status=True)
    mutated("cap-pollfail", edit("drain.json", lambda o: o.update(reason="cap", polls=[{"t_ms": T0, "err": "x"}])), "drain-poll-failed")
    mutated("drain-reason", edit("drain.json", lambda o: o.update(reason="??")), "drain-missing")
    mutated("restart", edit("act18-end.json", lambda o: o.update(process_start_s=1.8e9)), "app-restarted")
    mutated("stray-intent", edit("db-dump.json", lambda o: o["payment_intent"].append(dict(o["payment_intent"][0], uid="warm-1", user_id=7))), "pg-not-reset")
    mutated("stray-ledger", edit("pg-ledger.json", lambda o: o["payments"].append(dict(o["payments"][0], uid="warm-2"))), "pg-not-reset")
    mutated("stray-group", edit("db-dump.json", lambda o: o["hold_group"].append({"id": 999, "schedule_id": 1, "user_id": 5, "seat_count": 2})), "pg-not-reset")
    mutated("stray-hold", edit("db-dump.json", lambda o: o["seat_hold"].append({"id": 999, "seat_id": 1, "user_id": 5, "group_id": None})), "pg-not-reset")
    mutated("stray-res", edit("db-dump.json", lambda o: o["reservation"].append({"id": 999, "seat_id": 1, "user_id": 5, "status": "CONFIRMED"})), "pg-not-reset")
    mutated("hdr-group", edit("act18-end.json", lambda o: o.update(group_rejected={})), "reject-header-mismatch")
    mutated("hdr-group-mode", edit("act18-end.json", lambda o: o.update(group_rejected={k.replace("split", "one_shot"): v for k, v in o["group_rejected"].items()})),
            "reject-header-mismatch")
    mutated("hdr-group-1seat-up", edit("act18-end.json", lambda o: o["hold_rejected"].update(nowait=1)), "reject-header-mismatch")   # 묶음 409가 1석 지표를 올리면
    mutated("hdr-missing", rows_edit(lambda rs: [dict(r, hold=[dict(r["hold"][0], rj=None)]) if r["got"] == "none" else r for r in rs]), "reject-header-mismatch")
    mutated("hdr-B", edit("act18-end.json", lambda o: o["hold_rejected"].update(rule=o["hold_rejected"]["rule"] + 1)), "reject-header-mismatch", method="B1", cell="high")
    mutated("hdr-B-missing", rows_edit(lambda rs: [dict(r, hold=[*r["hold"][:-1], dict(r["hold"][-1], rj=None)]) if r["got"] != "both" else r for r in rs]),
            "reject-header-mismatch", method="B0", cell="high")
    mutated("req-count", edit("act18-end.json", lambda o: o["group_request"].update({"confirm:ok": o["group_request"]["confirm:ok"] + 1})), "group-request-mismatch")
    mutated("req-outcome", edit("act18-end.json", lambda o: o["group_request"].update({"hold:status_409": o["group_request"].pop("hold:SEAT_NOT_AVAILABLE")})),
            "group-request-mismatch")
    mutated("req-B-release", edit("act18-end.json", lambda o: o["group_request"].clear()), "group-request-mismatch", method="B1", cell="high")
    # 경계 셀 5xx(리뷰 H2-3): 앱 로그의 요청 예외 ERROR 블록과 응답마다 연결 — 40P01만 측정값 · 그 밖 · 연결 불가 = G1 실패
    def edge5(dl, log):
        def go(d, w):
            rows = read_rows(d)
            r = first(rows, lambda x: x["end"] == "done" and x["cf"][0]["ok"])
            r["cf"][0].update(st=500, code="NOCODE", ok=False, rids=[], rseats=[])
            t500 = r["cf"][0]["ts"] + 3
            write_rows(d, rows)
            edit("act18-end.json", lambda o: (o["group_request"].update({"confirm:ok": o["group_request"]["confirm:ok"] - 1,
                                                                        "confirm:status_500": 1}), o["deadlock"].update(dl)))(d, w)
            p = Path(d, "k6-requests.csv.gz")   # CSV: 그 200 한 줄을 뺀다
            lines = gzip.open(p, "rt").read().splitlines()
            i = next(i for i, x in enumerate(lines) if x.startswith("http_req_duration") and ",confirm_group," in x)
            del lines[i]
            # 실제 k6 CSV처럼 그 500 응답의 줄을 남긴다(끝 시각 초 단위 — 행 있는 5xx와 짝지어져 '행 없음'으로 세지 않아야 한다)
            lines.append(f"http_req_duration,{int((t500 + 2) / 1000)},12.0,,,,false,,POST,confirm_group,HTTP/1.1,main,,500,,,,,")
            with gzip.open(p, "wt") as f:
                f.write("\n".join(lines) + "\n")
            if log is not None:
                cause = ("org.postgresql.util.PSQLException: ERROR: deadlock detected\n  Detail: Process 1 waits for ShareLock" if log == "deadlock"
                         else "java.lang.IllegalStateException: boom")
                with gzip.open(Path(d, "app.log.gz"), "at") as f:
                    f.write(f"{iso_ms(t500)} ERROR 1 --- [seat-reservation-lab] [nio-8101-exec-7] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : "
                            f"Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed: x] with root cause\n\n"
                            f"{cause}\n\tat org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2733)\n"
                            f"{iso_ms(t500 + 1)}  WARN 1 --- [seat-reservation-lab] [nio-8101-exec-7] x : after\n")
        return go
    s, st = mutated("edge-5xx-40p01", edge5({"group_confirm": 1}, "deadlock"), None, method="split", cell="edge", ok_status=True)
    check("edge-5xx/40p01-linked", s["g1"]["ok"] and s["edge"]["http_5xx_link"] == {"40p01": 1, "other": 0, "unlinked": 0, "csv_only": 0}
          and s["edge"]["deadlock_total"] == 1 and s["edge"]["http_5xx_total"] == 1, json.dumps(s["edge"])[:300])
    s, st = mutated("edge-5xx-other", edge5({"group_confirm": 2}, "other"), None, method="split", cell="edge", g1_fail="edge_5xx_non40p01")
    check("edge-5xx/not-invalid", st == "ok", st)
    mutated("edge-5xx-unlinked", edge5({"group_confirm": 1}, None), None, method="one-shot", cell="edge", g1_fail="edge_5xx_unlinked")
    s, _ = mutated("edge-5xx-no-log-file", lambda d, w: (edge5({"group_confirm": 1}, None)(d, w), Path(d, "app.log.gz").unlink()), "app-log-missing", cell="edge")
    check("H3-3/no-log-not-unlinked", s["g1"]["items"]["edge_5xx_unlinked"] == 0, json.dumps(s["edge"]["http_5xx_link"]))   # 로그가 없으면 무효이지 연결 불가가 아니다
    def log_text(txt):
        def go(d, w):
            with gzip.open(Path(d, "app.log.gz"), "wt") as f:
                f.write(txt)
        return go
    mutated("H3-3-empty-log", log_text(""), "app-log-missing", cell="edge")
    mutated("H3-3-no-start-header", log_text(f"{iso_ms(T0)}  INFO 1 --- [x] [main] y : hello\n"), "app-log-missing", cell="edge")
    _, st = mutated("H3-3-main-cell-log-not-needed", log_text(""), None, cell="low", ok_status=True)   # 요약기 무효는 경계 셀만(본 셀은 run18 하네스 사유)
    mutated("H3-3-broken-gzip", lambda d, w: Path(d, "app.log.gz").write_bytes(b"not gzip"), "app-log-missing", cell="edge")
    s, st = mutated("edge-not-sent", rows_edit(lambda rs: [dict(r, ps=[dict(p_, ts=p_["ts"] + 5000, at=None) for p_ in r["ps"]],
                                                              cf=[dict(c, ts=c["ts"] + 5000, at=None) for c in r["cf"]]) for r in rs]),
                    "edge-not-sent", method="split", cell="edge")
    mutated("edge-not-sent-ps-only", rows_edit(lambda rs: [dict(r, ps=[dict(r["ps"][0], ts=r["hold"][0]["exp"] - 1000, tr=r["hold"][0]["exp"] - 995, at=None)])
                                                           if r["kind"] == "edge_ps" and r["ps"] else r for r in rs]), "edge-not-sent", cell="edge")
    # 진입 · straddle은 측정값(유효성 아님): 창 안 송신은 있되 진입 0이어도 ok
    _, st = mutated("edge-entered-zero-ok", rows_edit(lambda rs: [dict(r, cf=[dict(r["cf"][0], ts=r["ps"][0]["exp"] + 300, tr=r["ps"][0]["exp"] + 305, at=None)])
                                                                  if r["kind"] == "edge_confirm" and r["cf"] else r for r in rs]), None, cell="edge", ok_status=True)
    s, _ = summ(tmp / "ok-split-edge")
    check("edge/exposure", all(k in s["edge"]["exposure"]["edge_ps"] for k in ("in_window", "entered", "straddle", "contains", "sent")), json.dumps(s["edge"]["exposure"]))
    s, st = mutated("main-5xx", edge5({"group_confirm": 1}, "deadlock"), None, method="split", cell="low", g1_fail="main_5xx")
    check("main-5xx/edge-items-zero", s["g1"]["items"]["edge_5xx_unlinked"] == 0 and s["g1"]["items"]["edge_5xx_non40p01"] == 0, json.dumps(s["g1"]["items"])[:200])
    mutated("dropped", lambda d, w: _csv_add(d, ["dropped_iterations", "1", "2", "", "", "", "", "", "", "", "", "main", "", "", "", "", "", "", ""]), "dropped-iterations")
    mutated("csv-short-row", lambda d, w: _csv_add(d, ["http_req_duration", "1"]), "k6-csv-missing")
    mutated("csv-nan", lambda d, w: _csv_add(d, ["http_req_duration", "1", "x", "", "", "", "", "", "", "hold_group", "", "main", "", "201", "", "", "", "", ""]),
            "k6-csv-missing")
    mutated("latency-count", lambda d, w: _csv_add(d, ["http_req_duration", "1", "3", "", "", "", "", "", "", "hold_group", "", "main", "", "201", "", "", "", "", ""]),
            "latency-missing")
    mutated("pc-link-down", edit("pc-link.json", lambda o: o.update(down=2)), "pc-link-down")
    mutated("pc-link-unreadable", edit("pc-link.json", lambda o: o.update(readable=False)), "pc-link-missing")

    def mem(f):
        def go(d, w):
            p = Path(d, "timeline-client.jsonl")
            ls = [json.loads(x) for x in p.read_text().splitlines()]
            p.write_text("\n".join(json.dumps(x) for x in f(ls)) + "\n")
        return go
    mutated("pc-mem-low", mem(lambda ls: [dict(x, mem_avail_kb=1000) if i == 5 else x for i, x in enumerate(ls)]), "pc-mem-low")
    mutated("pc-mem-gap", mem(lambda ls: ls[:3] + ls[20:]), "pc-mem-missing")
    mutated("pc-mem-null", mem(lambda ls: [dict(x, mem_avail_kb=None) if i == 5 else x for i, x in enumerate(ls)]), "pc-mem-missing")
    mutated("send-lag", rows_edit(lambda rs: [dict(r, cf=[dict(r["cf"][0], ts=r["cf"][0]["at"] + 2500)] + r["cf"][1:]) if r["end"] == "done" and r["sid"] == first(
        rs, lambda x: x["end"] == "done")["sid"] else r for r in rs]), "env-send-lag")
    mutated("send-lag-edge", rows_edit(lambda rs: [dict(r, ps=[dict(r["ps"][0], ts=r["ps"][0]["at"] + 300)]) if r["kind"] == "edge_ps" and r["sid"] == 1 else r
                                       for r in rs]), "env-send-lag", cell="edge")
    _, st = mutated("send-lag-env", rows_edit(lambda rs: [dict(r, cf=[dict(r["cf"][0], ts=r["cf"][0]["at"] + 2500)] + r["cf"][1:]) if r["end"] == "done" else r
                                                          for r in rs]), "env-send-lag")
    check("send-lag/env-kind", SM.env_kind(st) == "env", st)
    # ---- G1(정합 — 회차 ok이되 판정 실패) ----
    def kill_res(d, w):   # 확정 200인데 DB에 예약이 없음 → uid orphan · 세션 integrity
        edit("db-dump.json", lambda o: o.update(reservation=[x for x in o["reservation"] if x["intent_id"] != w.res[0]["intent_id"]]))(d, w)
    s, st = mutated("g1-orphan", kill_res, None, g1_fail="orphan", ok_status=True)
    check("g1-orphan/integrity", s["g1"]["items"]["integrity"] > 0, json.dumps(s["sessions"]["integrity"]))
    mutated("g1-partial-res", edit("db-dump.json", lambda o: o.update(reservation=o["reservation"][1:])), None, g1_fail="partial_reservation")
    mutated("g1-double-sold", edit("db-dump.json", lambda o: o["reservation"].append(dict(o["reservation"][0], id=9999, intent_id=None))), None,
            g1_fail="double_sold_seats")
    mutated("g1-amount", edit("pg-ledger.json", lambda o: o["payments"][0].update(amount=AMOUNT)), None, g1_fail="amount_mismatch")
    mutated("g1-amount-B", edit("pg-ledger.json", lambda o: o["payments"][0].update(amount=2 * AMOUNT)), None, method="B0", g1_fail="amount_mismatch")
    mutated("g1-partial-holds", edit("consistency-k6.json", lambda o: o.update(group_partial_holds=1)), None, g1_fail="consistency_k6")
    mutated("g1-expiry-split", edit("consistency.json", lambda o: o.update(group_expiry_split=1)), None, g1_fail="consistency_end")
    mutated("g1-partial-reservations", edit("consistency.json", lambda o: o.update(group_partial_reservations=1)), None, g1_fail="consistency_end")
    mutated("g1-v-counter", edit("consistency.json", lambda o: o.update(v_counter_mismatch=2)), None, g1_fail="consistency_v_sum")
    mutated("g1-v-k6", edit("consistency-k6.json", lambda o: o.update(v_duplicate_hold_seats=1)), None, g1_fail="consistency_k6_v_sum")
    mutated("g1-dead", edit("db-dump.json", lambda o: o["compensation_task"].append({"id": 99, "uid": "u-x", "type": "CLOSE", "source": "SWEEP", "status": "DEAD"})),
            None, cell="edge", g1_fail="dead_tasks")
    mutated("g1-unfinished", edit("db-dump.json", lambda o: o["compensation_task"][0].update(status="PENDING", done_ms=None)), None, cell="edge",
            g1_fail="unfinished_tasks")
    mutated("g1-open", edit("db-dump.json", lambda o: o["payment_intent"][0].update(state="OPEN")), None, g1_fail="open_after_drain")
    mutated("g1-unexpected", rows_edit(lambda rs: [dict(r, rel=[dict(r["rel"][0], st=409, code="PAYMENT_STARTED", ok=False)]) if r["kind"] == "release" and r["got"] == "both"
                                                   else r for r in rs]), None, g1_fail="unexpected")
    mutated("g1-group-missing", edit("db-dump.json", lambda o: o.update(hold_group=o["hold_group"][1:])), None, g1_fail="record_group_missing")
    mutated("g1-group-extra", edit("db-dump.json", lambda o: o["hold_group"].append(dict(o["hold_group"][0], id=777))), None, g1_fail="record_group_extra")
    mutated("g1-paid-no-k6", edit("pg-ledger.json", lambda o: o["payments"].append({"uid": "u-9999-0", "userId": plan18.USER_BASE, "amount": 2 * AMOUNT,
                                                                                    "state": "CAPTURED", "capturedAt": iso_ms(T0), "refunds": 0, "tombstone": False})),
            None, g1_fail="record_ledger_paid_without_k6_pay")
    mutated("g1-pay-not-in-ledger", edit("pg-ledger.json", lambda o: o["payments"][0].update(capturedAt=None, state="REGISTERED")), None,
            g1_fail="record_pay_ok_not_in_ledger")
    mutated("g1-pay-no-intent", edit("db-dump.json", lambda o: o.update(payment_intent=o["payment_intent"][1:])), None, g1_fail="record_pay_ok_no_intent")
    # ---- 동시 경합 셀(burst) ----
    s, st = mutated("burst-ok", lambda d, w: None, None, cell="burst", ok_status=True)
    b = s["burst"]
    check("burst/stats", b and b["bursts"] == len(plan18.bursts(2, OVER["n"])) and b["multi_success"] == 0 and not b["bad"]
          and b["spread_ms"]["max"] <= 2 and b["causes"] == {"nowait": s["hold"]["none"]}, json.dumps(b))
    s1, _ = mutated("burst-ok-oneshot", lambda d, w: None, None, method="one-shot", cell="burst", ok_status=True)
    check("burst/short", s1["burst"]["causes"] == {"short": s1["hold"]["none"]}, json.dumps(s1["burst"]))
    def spread(n_ms, every):
        def go(rs):
            for r in rs:
                if r["burst"] % every == 0 and r["sid"] == plan18.burst_of(2, OVER["n"], r["sid"])[2]:
                    r["hold"][0]["ts"] += n_ms
                    r["hold"][0]["tr"] += n_ms
        return rows_edit(go)
    mutated("burst-spread", spread(10, 3), "burst-spread", cell="burst")
    _, st = mutated("burst-spread-few", spread(10, 1000), None, cell="burst", ok_status=True)
    s, _ = mutated("burst-member-missing", rows_edit(lambda rs: rs[:-1]), "sessions-count-mismatch", cell="burst")
    check("burst/member-missing-over", s["burst"]["over"] >= 1, json.dumps(s["burst"]))
    mutated("burst-attrs", rows_edit(lambda rs: [dict(r, burst=r["burst"] + 1) if r["sid"] == 2 else r for r in rs]), "session-attrs-mismatch", cell="burst")
    mutated("burst-seats", rows_edit(lambda rs: [dict(r, seats=[r["seats"][0] + 4, r["seats"][1] + 4]) if r["sid"] == 2 else r for r in rs]),
            "session-attrs-mismatch", cell="burst")
    mutated("burst-lag", rows_edit(lambda rs: [dict(r, hold=[dict(r["hold"][0], ts=r["hold"][0]["at"] + 2500)]) if r["sid"] == 0 else r for r in rs]),
            "env-send-lag", cell="burst")
    mutated("burst-deadlock", edit("act18-end.json", lambda o: o["deadlock"].update(group_hold=1)), None, cell="burst", g1_fail="deadlock")
    mutated("main-deadlock", edit("act18-end.json", lambda o: o["deadlock"].update(expiry_tick=1)), None, cell="mid", g1_fail="deadlock")
    s, st = mutated("edge-deadlock-not-g1", edit("act18-end.json", lambda o: o["deadlock"].update(expiry_tick=2)), None, cell="edge", ok_status=True)
    check("edge-deadlock/g1-ok", s["g1"]["ok"] and s["edge"]["deadlock_total"] == 2, json.dumps(s["edge"])[:200])
    # ---- 리뷰 1회차 보정 갈래 ----
    def samples(f):
        def go(d, w):
            p_ = Path(d, "holds-sample.jsonl")
            ls = [json.loads(x) for x in p_.read_text().splitlines()]
            out = f(ls)
            p_.write_text("".join(x if isinstance(x, str) else json.dumps(x) + "\n" for x in out))
        return go
    mutated("H1-1-partial", samples(lambda ls: [dict(x, partial=1) if i == 2 else x for i, x in enumerate(ls)]), None, g1_fail="sample_partial")
    mutated("H1-1-split", samples(lambda ls: [dict(x, split=2) if i == 4 else x for i, x in enumerate(ls)]), None, cell="burst", g1_fail="sample_split")
    mutated("H1-1-missing", rm("holds-sample.jsonl"), "hold-sample-missing")
    mutated("H1-1-no-live", samples(lambda ls: [dict(x, holds=0) for x in ls]), "hold-sample-missing")
    mutated("H1-1-all-broken", samples(lambda ls: ["{x\n" for _ in ls]), "hold-sample-missing")
    s, _ = mutated("stock-never-out", samples(lambda ls: [dict(x, free_pairs=4) for x in ls]), None, ok_status=True)
    check("stock/never-null", s["hold_samples"]["stock_out_s"] is None and s["hold_samples"]["last_free_pairs"] == 4, json.dumps(s["hold_samples"]))
    mutated("H1-1-no-free-pairs", samples(lambda ls: [{k: v for k, v in x.items() if k != "free_pairs"} for x in ls]), "hold-sample-missing")
    _, st = mutated("H1-1-some-err", samples(lambda ls: ls + [{"pc_t0_ms": 1, "err": "timeout"}]), None, ok_status=True)

    # ---- 리뷰 3회차 ----
    def csv5(name, t_off_s, log):   # 행이 남지 않은 앱 5xx(CSV에만) · (선택) 그 시각의 요청 예외 로그 블록
        def go(d, w):
            _csv_add(d, ["http_req_duration", str(int(T0_PC + t_off_s)), "12.0", "", "", "", "false", "", "POST", name, "HTTP/1.1", "main", "", "500", "", "", "", "", ""])
            if log is not None:
                cause = "org.postgresql.util.PSQLException: ERROR: deadlock detected" if log == "deadlock" else "java.lang.IllegalStateException: boom"
                with gzip.open(Path(d, "app.log.gz"), "at") as f:
                    f.write(f"{iso_ms(T0 + t_off_s * 1000 - 5)} ERROR 1 --- [x] [nio-8101-exec-1] o.a.c.c.C.[.[.[/].[dispatcherServlet] : "
                            f"Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [x] with root cause\n\n{cause}\n")
        return go
    mutated("H3-2-main-csv-only-500", csv5("hold_group", 3, None), None, cell="low", g1_fail="csv_5xx_without_row")
    s, st = mutated("H3-2-edge-csv-only-40p01", csv5("confirm_group", 4, "deadlock"), None, cell="edge", ok_status=True)
    check("H3-2/edge-linked", s["g1"]["ok"] and s["edge"]["http_5xx_link"] == {"40p01": 1, "other": 0, "unlinked": 0, "csv_only": 1}
          and s["edge"]["http_5xx_total"] == 1 and s["edge"]["http_5xx"] == {"confirm_group": 1}, json.dumps(s["edge"])[:300])   # CSV 전용 5xx는 한 번만(H3-R2)
    s, _ = mutated("H3-R2-row-and-csv", lambda d, w: (edge5({"group_confirm": 2}, "deadlock")(d, w), csv5("confirm_group", 40, "deadlock")(d, w)), None, cell="edge",
                   ok_status=True)
    check("H3-R2/row-plus-csv", s["edge"]["http_5xx_total"] == 2 and s["edge"]["http_5xx"] == {"confirm_group": 2}
          and s["edge"]["http_5xx_link"] == {"40p01": 2, "other": 0, "unlinked": 0, "csv_only": 1}, json.dumps(s["edge"])[:300])
    # H3-R4: 묶음 선점 5xx가 있으면 DB에만 있는 묶음은 확인 불가(None) — g1 실패가 아니라 unchecked
    def hold5xx(d, w):
        rows = read_rows(d)
        r = first(rows, lambda x: x["got"] == "none")
        r["hold"][0].update(st=500, code="NOCODE", rj=None)
        t5 = r["hold"][0]["ts"] + 3
        write_rows(d, rows)
        edit("act18-end.json", lambda o: (o["group_request"].update({"hold:SEAT_NOT_AVAILABLE": o["group_request"]["hold:SEAT_NOT_AVAILABLE"] - 1, "hold:status_500": 1}),
                                          o["group_rejected"].update({k: v - 1 for k, v in o["group_rejected"].items()}), o["deadlock"].update(group_hold=1)))(d, w)
        with gzip.open(Path(d, "app.log.gz"), "at") as f:
            f.write(f"{iso_ms(t5)} ERROR 1 --- [x] [nio-8101-exec-1] o.a.c.c.C.[.[.[/].[dispatcherServlet] : Servlet.service() for servlet [dispatcherServlet] threw\n\n"
                    f"org.postgresql.util.PSQLException: ERROR: deadlock detected\n")
    s, st = mutated("H3-R4-group-extra-unchecked", hold5xx, None, cell="edge", ok_status=True)
    check("H3-R4/unchecked-not-fail", s["g1"]["ok"] and s["g1"]["items"]["record_group_extra"] is None and "record_group_extra" in s["g1"]["unchecked"],
          json.dumps({"g1": s["g1"]["ok"], "unchecked": s["g1"]["unchecked"]}))
    # H3-R5: 첫 선점 at 결측 = 행 오류(세션 무효)
    mutated("H3-R5-at-missing", rows_edit(lambda rs: [dict(r, hold=[{k: v for k, v in r["hold"][0].items() if k != "at"}] + r["hold"][1:]) if r["sid"] == 3 else r for r in rs]),
            "session-invalid")
    mutated("H3-R5-at-missing-edge", rows_edit(lambda rs: [dict(r, hold=[dict(r["hold"][0], at=None)]) if r["sid"] == 3 else r for r in rs]), "session-invalid", cell="edge")
    mutated("H3-2-edge-csv-only-unlinked", csv5("confirm_group", 4, None), None, cell="edge", g1_fail="edge_5xx_unlinked")

    def long_short(d, w):   # 긴 응답 [A, A+1000] · 짧은 응답 [A+400, A+450] · 블록 A+440(40P01) · A+990(그 밖) — 끝 순이면 짧은 쪽이 440을 가져간다(H3-4)
        rows = read_rows(d)
        ok_rows = [r for r in rows if r["end"] == "done" and r["cf"][0]["ok"]][:2]
        A = ok_rows[0]["cf"][0]["ts"]
        ok_rows[0]["cf"][0].update(st=500, code="NOCODE", ok=False, rids=[], rseats=[], ts=A, tr=A + 1000, at=None)
        ok_rows[1]["cf"][0].update(st=500, code="NOCODE", ok=False, rids=[], rseats=[], ts=A + 400, tr=A + 450, at=None)
        write_rows(d, rows)
        edit("act18-end.json", lambda o: o["group_request"].update({"confirm:ok": o["group_request"]["confirm:ok"] - 2, "confirm:status_500": 2}))(d, w)
        p_ = Path(d, "k6-requests.csv.gz")
        lines = gzip.open(p_, "rt").read().splitlines()
        for _ in range(2):
            i = next(i for i, x in enumerate(lines) if x.startswith("http_req_duration") and ",confirm_group," in x)
            del lines[i]
        with gzip.open(p_, "wt") as f:
            f.write("\n".join(lines) + "\n")
        with gzip.open(Path(d, "app.log.gz"), "at") as f:
            for t, cause in ((A + 440, "org.postgresql.util.PSQLException: ERROR: deadlock detected"), (A + 990, "java.lang.IllegalStateException: boom")):
                f.write(f"{iso_ms(t)} ERROR 1 --- [x] [nio-8101-exec-1] o.a.c.c.C.[.[.[/].[dispatcherServlet] : Servlet.service() for servlet [dispatcherServlet] threw\n\n{cause}\n")
    s, _ = mutated("H3-4-long-short", long_short, None, cell="edge", g1_fail="edge_5xx_non40p01")
    check("H3-4/tr-order", s["edge"]["http_5xx_link"] == {"40p01": 1, "other": 1, "unlinked": 0, "csv_only": 0}, json.dumps(s["edge"]["http_5xx_link"]))
    mutated("H3-5-rate", edit("k6-summary.json", lambda o: o["adr018"].update(rate=10)), "k6-plan-mismatch")
    mutated("H3-5-darr", edit("k6-summary.json", lambda o: o["adr018"].update(d_arr_s=o["adr018"]["d_arr_s"] + 1)), "k6-plan-mismatch")
    mutated("H3-5-hold-lag", rows_edit(lambda rs: [dict(r, hold=[dict(r["hold"][0], at=r["hold"][0]["ts"] - 2500)] + r["hold"][1:]) if r["sid"] == 0 else r for r in rs]),
            "env-send-lag", method="B1", cell="mid")
    def cause_tag(tag):
        def go(d, w):
            rows = read_rows(d)
            n = 0
            for r in rows:
                if r["got"] == "none":
                    r["hold"][0]["rj"] = f"group-{tag}"
                    n += 1
            write_rows(d, rows)
            edit("act18-end.json", lambda o: o.update(group_rejected={f"split:{tag}": n}))(d, w)
        return go
    s, st = mutated("H3-6-split-short", cause_tag("short"), None, method="split", cell="low", g1_fail="group_cause_bad")
    check("H3-6/not-metric-mismatch", "reject-header-mismatch" not in reasons_of(st), st)
    mutated("H3-6-unknown", cause_tag("weird"), None, method="split", cell="low", g1_fail="group_cause_bad")
    _, st = mutated("H3-6-split-rule-ok", cause_tag("rule"), None, method="split", cell="low", ok_status=True)

    # 홀드 표본 공백 · 실패 비율(리뷰 H2-1)
    mutated("H2-1-gap", samples(lambda ls: ls[:10] + ls[25:]), "hold-sample-missing")
    mutated("H2-1-late-start", samples(lambda ls: ls[5:]), "hold-sample-missing")
    mutated("H2-1-fail-ratio", samples(lambda ls: ls + [{"pc_t0_ms": 1, "err": "timeout"}] * 12), "hold-sample-missing")
    _, st = mutated("H2-1-fail-few", samples(lambda ls: ls + [{"pc_t0_ms": 1, "err": "timeout"}] * 9), None, ok_status=True)
    s, _ = mutated("H2-7-preconnect-run", rows_edit(lambda rs: [dict(r, pre=dict(r["pre"], st=0)) if r["sid"] == 1 else r for r in rs]), "k6-conn-error", cell="burst")
    check("H2-7/env", SM.env_kind(s["status"]) == "env", s["status"])
    mutated("H2-7-preconnect-run-404", rows_edit(lambda rs: [dict(r, pre=dict(r["pre"], st=404)) if r["sid"] == 1 else r for r in rs]), "session-invalid", cell="burst")
    def conn(frac_every):
        def go(rs):
            for r in rs:
                if r["burst"] % frac_every == 0 and r["sid"] == plan18.burst_of(2, OVER["n"], r["sid"])[1]:
                    r["hold"][0]["cn"] = 0.8
        return rows_edit(go)
    s, _ = mutated("H1-8-new-conn", conn(3), "burst-spread", cell="burst")
    check("H1-8/conn-counted", s["burst"]["conn_new_bursts"] >= 3 and s["burst"]["spread_ms"]["max"] <= 2, json.dumps(s["burst"])[:300])
    _, st = mutated("H1-8-new-conn-few", conn(1000), None, cell="burst", ok_status=True)
    s, _ = mutated("H1-8-cn-missing", rows_edit(lambda rs: [dict(r, hold=[{k: v for k, v in r["hold"][0].items() if k != "cn"}]) for r in rs]), "burst-spread", cell="burst")

    def multi(rs):   # 같은 묶음에서 진 이탈 세션 하나를 '2석 성공'으로 바꾼다 → 묶음 안 성공 2
        bmap = {}
        for r in rs:
            bmap.setdefault(r["burst"], []).append(r)
        for k, mem in bmap.items():
            lose = [r for r in mem if r["got"] == "none" and r["kind"] == "abandon"]
            if lose and any(r["got"] == "both" for r in mem):
                r = lose[0]
                r["hold"] = [dict(r["hold"][0], st=201, code="OK", ok=True, gid=999, hids=[9998, 9999], exp=r["hold"][0]["ts"] + 300000, rj=None)]
                r["got"], r["end"] = "both", "abandon"
                return
        raise AssertionError("시험 자료: 이탈로 진 묶음 구성원이 없다")
    s, _ = mutated("H1-10-multi", rows_edit(multi), None, cell="burst", g1_fail="burst_multi_success")
    mutated("g1-limit", rows_edit(lambda rs: [dict(r, hold=[dict(r["hold"][0], code="HOLD_LIMIT_EXCEEDED", rj="group-rule")]) if r["got"] == "none" and r["sid"] == first(
        rs, lambda x: x["got"] == "none")["sid"] else r for r in rs]), None, g1_fail="integrity")


def _csv_add(d, row):
    p = Path(d, "k6-requests.csv.gz")
    txt = gzip.open(p, "rt").read()
    buf = io.StringIO()
    csv.writer(buf).writerow(row)
    with gzip.open(p, "wt") as f:
        f.write(txt + buf.getvalue())


# ---- 비교 지표 단위 ---------------------------------------------------------------------------------------------------------------
def test_metrics():
    lo, hi = SM.wilson(50, 100)
    check("wilson/50-100", abs(lo - 0.4038) < 1e-3 and abs(hi - 0.5962) < 1e-3, (lo, hi))
    check("wilson/0", SM.wilson(0, 0) is None)
    lo, hi = SM.wilson(0, 10)
    check("wilson/k0", lo == 0.0 and 0.27 < hi < 0.28, (lo, hi))
    H = lambda sid, s, ts, tr, got: {"sid": sid, "seats": [s, s + 1], "got": got, "hold": [{"op": "hold_group", "seats": [s, s + 1], "ts": ts, "tr": tr}]}  # noqa: E731
    rows = [H(0, 10, 0, 10, "none"), H(1, 11, 5, 15, "none"), H(2, 12, 100, 110, "both"), H(3, 10, 8, 20, "both"), H(4, 30, 0, 10, "none")]
    c = SM.contention(rows)
    # 쌍: (0,1) 겹침 · 둘 다 실패 / (0,3) 같은 s · 겹침 · 3 성공 / (1,3) |11−10| = 1 · 겹침 / (1,2) 시간 안 겹침 / 4 홀로
    check("contention/pairs", c["pairs"] == 3 and c["both_fail"] == 1, c)
    rb = [{"sid": 0, "got": "first", "seats": [5, 6], "hold": [{"op": "hold", "seat": 5, "ts": 0, "tr": 10, "exp": 300010, "ok": True},
                                                                {"op": "hold", "seat": 6, "ts": 11, "tr": 20, "ok": False}], "rel": []},
          {"sid": 1, "got": "none", "seats": [4, 5], "hold": [{"op": "hold", "seat": 4, "ts": 1000, "tr": 1010, "ok": False}]},
          {"sid": 2, "got": "first", "seats": [5, 6], "hold": [{"op": "hold", "seat": 5, "ts": 2000, "tr": 2010, "ok": False}]},
          {"sid": 3, "got": "none", "seats": [5, 6], "hold": [{"op": "hold", "seat": 5, "ts": 400000, "tr": 400010, "ok": False}]}]
    f = SM.first_only(rb, "B0")
    check("first-only/B0", f["n"] == 2 and f["blocked_sessions"] == 1 and abs(f["held_s"]["max"] - 300.0) < 1e-9, f)
    rb[0]["rel"] = [{"op": "release", "tr": 50, "ok": True}]
    f = SM.first_only(rb, "B1")
    check("first-only/B1", f["held_s"]["max"] == 0.04 and f["blocked_sessions"] == 0, f)
    for st, want in (("ok", "valid"), ("", "harness"), ("invalid:pc-link-down,seed-mismatch", "env"), ("invalid:k6-conn-error,group-request-mismatch", "env"),
                     ("invalid:env-send-lag", "env"), ("invalid:env-send-lag,seed-mismatch", "harness"), ("invalid:session-invalid", "harness")):
        check(f"env_kind/{st}", SM.env_kind(st) == want, SM.env_kind(st))


# ---- plan18 ------------------------------------------------------------------------------------------------------------------
def test_plan():
    rows = plan18.order()
    check("plan/order-48", len(rows) == 48, len(rows))
    check("plan/order-unique", len({(r, c, m) for _, r, c, m in rows}) == 48)
    check("plan/burst-methods", sorted({m for _, _, c, m in rows if c == "burst"}) == ["one-shot", "split"])
    for cell in plan18.OVERLAPS:
        for r in range(1, 4):
            ms = [m for _, rr, c, m in rows if rr == r and c == cell]
            check(f"plan/order-{cell}-{r}", sorted(ms) == sorted(plan18.METHODS), ms)
        firsts = [next(m for _, rr, c, m in rows if rr == r and c == cell) for r in range(1, 4)]
        check(f"plan/rotate-{cell}", len(set(firsts)) == 3, firsts)
    check("plan/edge-methods", sorted({m for _, _, c, m in rows if c == "edge"}) == ["one-shot", "split"])
    p = plan18.render("one-shot", "edge", 2)
    check("plan/edge-app", p["app_env"]["HOLD_TTL"] == "8s" and p["app_env"]["HOLD_GROUP_MODE"] == "one-shot" and p["app_env"]["HOLD_GROUP_ENABLED"] == "true", p["app_env"])
    check("plan/edge-startup", plan18.startup_problems(p["app"]) == [], plan18.startup_problems(p["app"]))
    check("plan/edge-theta-M-0", p["app"]["theta_s"] == 0 and p["app"]["M_s"] == 0 and p["app_env"]["PAYMENT_MIN_REMAINING"] == "0s"
          and p["app_env"]["PAY_BY_MARGIN"] == "0s" and p["params"]["psCenter"] == 0 and p["params"]["eps"] == 0.5, p["app_env"])
    ec = plan18.expected_config("split", "edge")["seat.confirm"]
    check("plan/edge-config-PT0S", ec["paymentMinRemaining"] == "PT0S" and ec["payByMargin"] == "PT0S", ec)
    check("plan/main-theta-1", plan18.render("split", "low", 1)["app_env"]["PAYMENT_MIN_REMAINING"] == "1s")
    check("plan/startup-bad", plan18.startup_problems({"ttl_s": 1, "batch_s": 1, "W_s": 1, "theta_s": 1, "M_s": 2}) != [])
    b = plan18.render("B0", "low", 1)
    check("plan/B-disabled", b["app_env"]["HOLD_GROUP_ENABLED"] == "false" and b["group"] is False)
    check("plan/H", (plan18.render("split", "low", 1)["seed"]["H"], plan18.render("split", "mid", 1)["seed"]["H"],
                     plan18.render("split", "high", 1)["seed"]["H"], p["seed"]["H"]) == (3300, 550, 220, 3000))   # high 0.2(고정 2026-11-20)
    check("plan/fixed-values", plan18.FIXED is True and plan18.P99_LIMIT_MS == {"hold": 40.0, "pay_start": 40.0, "confirm": 50.0}
          and plan18.WARMUP_EXCLUDE_S == 5.0 and plan18.H_FACTOR == {"low": 3.0, "mid": 0.5, "high": 0.2}
          and plan18.KIND_WEIGHTS["main"] == {"ok": 70, "abandon": 15, "release": 15} and plan18.DRAIN_CAP_S == {"main": 240.0, "edge": 120.0})
    check("plan/edge-1000-200ms", p["n_total"] == 1000 and p["app_env"]["EXPIRY_INTERVAL"] == "200ms" and p["app"]["batch_s"] == 0.2
          and plan18.expected_config("split", "edge")["seat.hold"]["expiryInterval"] == "PT0.2S", (p["n_total"], p["app_env"]["EXPIRY_INTERVAL"]))
    bl = plan18.bursts(2, 1100)
    check("plan/bursts-cover", sum(n for _, n in bl) == 1100 and all(2 <= n <= 4 for _, n in bl[:-1]) and [f for f, _ in bl] == sorted({f for f, _ in bl}))
    check("plan/bursts-sizes", {n for _, n in bl} >= {2, 3, 4}, {n for _, n in bl})
    pb = plan18.render("one-shot", "burst", 2)
    check("plan/burst-render", pb["k6"]["env"]["BURST"] == "1" and pb["seed"]["H"] == len(bl) and pb["seed"]["seats"] == -(-len(bl) // plan18.BLOCKS_PER_ROW) * 50 + plan18.SEAT_PAD and pb["group"] and pb["scale"] == "main",
          pb["k6"]["env"])
    blocks = {}
    for sid in range(1100):
        a = plan18.session_attrs(2, sid, pb["params"])
        k = plan18.burst_of(2, 1100, sid)[0]
        blocks.setdefault(k, set()).update(plan18.seats_for("burst", 2, 1100, sid, a["U"], pb["seed"]["H"]))
    ks = sorted(blocks)
    check("plan/burst-disjoint", all(not (blocks[a_] & blocks[b_]) for a_, b_ in zip(ks, ks[1:])) and all(len(v) <= 3 for v in blocks.values()))
    check("plan/main-render-burst-0", plan18.render("split", "low", 1)["k6"]["env"]["BURST"] == "0")
    check("plan/dur-ms", plan18.dur(0.2) == "200ms" and plan18.dur(10.0) == "10s" and plan18.dur(611.0) == "611s")
    a1 = plan18.session_attrs(1, 7, plan18.render("B0", "low", 1)["params"])
    a2 = plan18.session_attrs(1, 7, plan18.render("split", "high", 1)["params"])
    check("plan/attrs-same-across-cells", a1 == a2)
    sh = plan18.render("split", "high", 1)["seed"]
    check("plan/seat-range", all(1 <= plan18.seat_of(u / 1000, sh["H"])[0] and plan18.seat_of(u / 1000, sh["H"])[1] <= sh["seats"] - plan18.SEAT_PAD
                                 for u in range(1000)) and sh["rows"] == 5, sh)
    r = subprocess.run([sys.executable, "-I", str(HERE / "plan18.py"), "--fixed"], capture_output=True, text=True)
    check("plan/fixed-flag", r.returncode == (0 if plan18.FIXED else 1), r.returncode)
    check("plan/rep-cells-rotate", [plan18.cells_of_rep(r_)[0] for r_ in (1, 2, 3)] == ["low", "mid", "high"]
          and all(plan18.cells_of_rep(r_)[3:] == ["edge", "burst"] for r_ in (1, 2, 3)))
    pr = plan18.render("B1", "mid", 2)
    check("plan/order-in-plan", pr["order"]["rep_cells"] == plan18.cells_of_rep(2)
          and (pr["order"]["seq"], 2, "mid", "B1") in plan18.order(), pr["order"])
    check("plan/same-row", all(plan18.same_row(plan18.seat_of(u / 5000, 3300)) for u in range(5000))
          and all(plan18.same_row(plan18.seats_for("burst", 1, 1100, sid, 0.7, 367)) for sid in range(1100))
          and not plan18.same_row([50, 51]) and plan18.same_row([49, 50]))
    d = plan18.dry_run()
    # 48회차 최악 시간이 예산을 넘는 것은 [초안] 값의 결과다(메인 결정 — 줄이는 안 보고). 그 밖의 문제는 0이어야 한다
    check("plan/dry-run-problems", [x for x in d["problems"] if not x.startswith("최악")] == [], d["problems"])
    check("plan/dry-run-48", d["n"] == 48, d["n"])
    check("plan/dry-run-budget", d["est_max_hours"] <= plan18.BUDGET_HOURS and d["problems"] == [], (d["est_max_hours"], d["problems"]))
    check("plan/drain-cap-240", plan18.DRAIN_CAP_S == {"main": 240.0, "edge": 120.0} and plan18.render("split", "burst", 1)["drain"]["cap_s"] == 240.0)
    sd = plan18.render("B0", "high", 1)["seed"]
    check("plan/pair-range", sd["pair_max_start"] == 224 and sd["pair_kind"] == "row" and plan18.render("split", "burst", 1)["seed"]["pair_kind"] == "burst")
    check("plan/config-B-split-off", plan18.expected_config("B1", "low")["seat.hold-group"] == {"enabled": False, "mode": "SPLIT"})
    rc, _ = plan18.config_check("B0", "edge", "/nonexistent")
    check("plan/config-B-edge", rc == 2)


# ---- 캠페인 ---------------------------------------------------------------------------------------------------------------------
def make_campaign(root, tweak=None):
    root = Path(root)
    if root.exists():
        shutil.rmtree(root)
    for _, rep, cell, method in plan18.order():   # rep마다 그 rep 시드로 만든 세계(리뷰 H2-4 — 실제 rep_summary status로 시험)
        w = World(method, cell, rep)
        d = root / method / cell / f"rep{rep}"
        w.write(d)
        edit("plan.json", lambda o: (o.update(fixed=True), o.pop("smoke", None), o.pop("over", None)))(d, None)   # 캠페인은 고정 값 회차만(리뷰 H1-9)
        s, st = SM.rep_summary(d)
        Path(d, "summary18.json").write_text(json.dumps(s))
        Path(d, "status").write_text(st + "\n")
        if tweak:
            tweak(d, method, cell, rep)
    return root


def test_campaign(tmp):
    def force_ok(d, m, c, r):   # 강제 쓰기 없음(리뷰 H2-4) — 실제 rep_summary가 쓴 status 그대로
        pass
    root = make_campaign(tmp / "camp-ok")
    bad = [str(x.parent.relative_to(root)) for x in root.glob("*/*/rep*/status") if x.read_text().strip() != "ok"]
    check("campaign/real-status-ok", bad == [], bad)
    out = SM.campaign(root)
    check("campaign/complete", out["complete"] is True and out["runs_consistent"] is True, json.dumps({k: out[k] for k in ("complete", "runs_consistent")}))
    check("campaign/pass-all", out["pass"] == {m: True for m in plan18.METHODS}, out["pass"])
    check("campaign/table", all(out["table"][m][ov]["sessions"] == 3 * OVER["n"] for m in plan18.METHODS for ov in plan18.OVERLAPS))
    check("campaign/wilson", all(out["table"][m][ov]["wilson95"] is not None for m in plan18.METHODS for ov in plan18.OVERLAPS))
    check("campaign/first-only", out["table"]["B0"]["high"]["first_only"]["n"] > 0 and out["table"]["split"]["high"]["first_only"] is None)
    check("campaign/burst", all(out["burst"][m]["reps_used"] == 3 and out["burst"][m]["bursts"] > 0 and out["burst"][m]["rate"] is not None
                                for m in plan18.BURST_METHODS) and out["burst"]["split"]["causes"].get("nowait", 0) > 0, json.dumps(out["burst"])[:300])
    check("campaign/edge", all(out["edge"][m]["reps_used"] == 3 and out["edge"][m]["in_window"] > 0 and "deadlock_total" in out["edge"][m] and "http_5xx_total" in out["edge"][m] for m in plan18.EDGE_METHODS), json.dumps(out["edge"])[:300])
    pc = out["paired"]["mid"]["B0-B1"]
    check("campaign/paired", set(pc["diffs"]) == {"1", "2", "3"} and pc["mean"] is not None and "split-one-shot" in out["paired"]["burst"]
          and out["table"]["B0"]["mid"]["rate_by_rep"]["2"] - out["table"]["B1"]["mid"]["rate_by_rep"]["2"] == pc["diffs"]["2"], json.dumps(pc))
    check("campaign/unfixed-none", out["unfixed_runs"] == [] and out["g1_in_invalid"] == [])
    check("campaign/burst-latency", all(out["burst"][m]["latency"]["hold"]["ok"] for m in plan18.BURST_METHODS), json.dumps(out["burst"]["split"]["latency"]))
    # H2-2: 동시 경합 셀 p99 초과 → 그 방식 실패 · 경계 셀 p99 초과는 판정에 넣지 않는다
    def slow_cell(cell_):
        def go(d, m, c, r):
            if (m, c, r) == ("one-shot", cell_, 2):
                edit("summary18.json", lambda o: o["latency"]["hold"].update(p99_ms=999.0))(d, None)
        return go
    out2 = SM.campaign(make_campaign(tmp / "camp-burst-slow", slow_cell("burst")))
    check("campaign/burst-p99-over", out2["pass"]["one-shot"] is False and out2["pass"]["split"] is True, out2["pass"])
    out2 = SM.campaign(make_campaign(tmp / "camp-edge-slow", slow_cell("edge")))
    check("campaign/edge-p99-ignored", out2["pass"]["one-shot"] is True and out2["edge"]["one-shot"]["latency"]["hold"]["p99_ms"][1] == 999.0, out2["pass"])
    # H2-6: 무효 시도에서 본 정합 위반 → 그 방식 통과 불가 · 결측만으로 g1 거짓인 무효 시도는 세지 않는다
    root6 = make_campaign(tmp / "camp-g1inv")
    base6 = root6 / "B1" / "high"
    shutil.copytree(base6 / "rep2", base6 / "rep2.attempt1")
    edit("summary18.json", lambda o: (o.update(status="invalid:pc-link-down"), o["g1"].update(ok=False), o["g1"]["items"].update(orphan=1)))(base6 / "rep2.attempt1", None)
    Path(base6 / "rep2.attempt1" / "status").write_text("invalid:pc-link-down\n")
    base6b = root6 / "B0" / "low"
    shutil.copytree(base6b / "rep1", base6b / "rep1.attempt1")
    edit("summary18.json", lambda o: (o.update(status="invalid:db-dump-missing"), o["g1"].update(ok=False),
                                      o["g1"]["items"].update(dead_tasks=None, open_after_drain=None)))(base6b / "rep1.attempt1", None)
    Path(base6b / "rep1.attempt1" / "status").write_text("invalid:db-dump-missing\n")
    # H3-1: 결측 산출물 · 연결 오류 · 덤프 불안정 시도는 g1_in_invalid로 세지 않는다(실제 폴더에서 rep_summary를 다시 돌린다)
    def rerun(p_, fn):
        fn(p_)
        s_, st_ = SM.rep_summary(p_)
        Path(p_, "summary18.json").write_text(json.dumps(s_))
        Path(p_, "status").write_text(st_ + "\n")
        return s_
    for cell_, method_, rep_, fn in (("low", "split", 1, lambda p_: Path(p_, "pg-ledger.json").unlink()),
                                     ("mid", "one-shot", 2, lambda p_: Path(p_, "db-dump.json").unlink()),
                                     ("high", "B0", 3, rows_edit_path(lambda rs: [dict(r, hold=[dict(r["hold"][0], st=0, code="NOCODE", ok=False)], rel=[], ps=[], pay=[], cf=[],
                                                                                         got="none", end="hold") if r["sid"] == 5 else r for r in rs]))):
        b_ = root6 / method_ / cell_
        shutil.copytree(b_ / f"rep{rep_}", b_ / f"rep{rep_}.attempt1")
        s_ = rerun(b_ / f"rep{rep_}.attempt1", fn)
        check(f"H3-1/{cell_}-invalid", s_["status"] != "ok" and not SM.g1_violation(s_["g1"]["items"]), (s_["status"], {k: v for k, v in s_["g1"]["items"].items() if v}))
    b_ = root6 / "split" / "burst"
    shutil.copytree(b_ / "rep1", b_ / "rep1.attempt1")
    edit("summary18.json", lambda o: (o.update(status="invalid:dump-race", reasons=["dump-race"]), o["g1"].update(ok=False), o["g1"]["items"].update(orphan=2)))(b_ / "rep1.attempt1", None)
    Path(b_ / "rep1.attempt1" / "status").write_text("invalid:dump-race\n")
    b_ = root6 / "one-shot" / "burst"   # 덤프 불안정 + 응답에서 본 부분 선점(앱 계약 위반) → 덤프와 무관하므로 g1_in_invalid(H3-R1)
    shutil.copytree(b_ / "rep2", b_ / "rep2.attempt1")
    edit("summary18.json", lambda o: (o.update(status="invalid:dump-unstable", reasons=["dump-unstable"]), o["g1"].update(ok=False),
                                      o["g1"]["items"].update(integrity=1, integrity_response=1, orphan=3)))(b_ / "rep2.attempt1", None)
    Path(b_ / "rep2.attempt1" / "status").write_text("invalid:dump-unstable\n")
    out6 = SM.campaign(root6)
    check("H3-R1/dump-unstable-response-violation", "one-shot/burst/rep2:rep2.attempt1" in out6["g1_in_invalid"] and out6["pass"]["one-shot"] is False,
          (out6["g1_in_invalid"], out6["pass"]))
    check("H3-1/dump-race-unchecked", out6["g1_unchecked_dump"] == ["split/burst/rep1:rep1.attempt1"] and out6["pass"]["split"] is True, (out6["g1_unchecked_dump"], out6["pass"]))
    check("campaign/g1-in-invalid", out6["g1_in_invalid"] == ["B1/high/rep2:rep2.attempt1", "one-shot/burst/rep2:rep2.attempt1"] and out6["pass"]["B1"] is False and out6["pass"]["B0"] is True,
          (out6["g1_in_invalid"], out6["pass"]))
    def smoke_one(d, m, c, r):
        force_ok(d, m, c, r)
        if (m, c, r) == ("split", "low", 2):
            edit("plan.json", lambda o: o.update(smoke=True))(d, None)
    out = SM.campaign(make_campaign(tmp / "camp-smoke", smoke_one))
    check("campaign/smoke-insufficient", out["complete"] is False and all(v == "insufficient" for v in out["pass"].values()) and out["unfixed_runs"] == ["split/low/rep2"],
          (out["pass"], out["unfixed_runs"]))
    def unfixed_g1(d, m, c, r):   # 고정 아님 + 정합 실패 1회차 → 판정된 실패도 '판정 불가'
        force_ok(d, m, c, r)
        edit("plan.json", lambda o: o.update(fixed=False))(d, None)
        if (m, c, r) == ("B0", "mid", 2):
            edit("summary18.json", lambda o: o["g1"].update(ok=False))(d, None)
    out = SM.campaign(make_campaign(tmp / "camp-unfixed", unfixed_g1))
    check("campaign/unfixed-insufficient", all(v == "insufficient" for v in out["pass"].values()) and len(out["unfixed_runs"]) == 48, out["pass"])
    # 정합 실패 1회차 → 그 방식 False(판정된 실패가 우선)
    def g1fail(d, m, c, r):
        force_ok(d, m, c, r)
        if (m, c, r) == ("B0", "mid", 2):
            s = json.loads(Path(d, "summary18.json").read_text())
            s["g1"]["ok"] = False
            Path(d, "summary18.json").write_text(json.dumps(s))
    out = SM.campaign(make_campaign(tmp / "camp-g1", g1fail))
    check("campaign/g1-fail", out["pass"]["B0"] is False and out["g1"]["B0/mid"]["fail_runs"] == ["B0/mid/rep2"] and out["pass"]["B1"] is True, out["pass"])
    def edge_fail(d, m, c, r):
        force_ok(d, m, c, r)
        if (m, c, r) == ("one-shot", "edge", 3):
            s = json.loads(Path(d, "summary18.json").read_text())
            s["g1"]["ok"] = False
            Path(d, "summary18.json").write_text(json.dumps(s))
    out = SM.campaign(make_campaign(tmp / "camp-edge", edge_fail))
    check("campaign/edge-g1-fail", out["pass"]["one-shot"] is False and out["pass"]["split"] is True, out["pass"])
    # p99 상한 초과 → 그 방식 False
    def slow(d, m, c, r):
        force_ok(d, m, c, r)
        if (m, c, r) == ("split", "high", 1):
            s = json.loads(Path(d, "summary18.json").read_text())
            s["latency"]["hold"]["p99_ms"] = 999.0
            Path(d, "summary18.json").write_text(json.dumps(s))
    out = SM.campaign(make_campaign(tmp / "camp-slow", slow))
    check("campaign/p99-over", out["pass"]["split"] is False and out["table"]["split"]["high"]["latency"]["hold"]["over"] is True, out["pass"])
    # 무효 회차 → 그 자리 사용 없음 → 그 방식 insufficient · 표 미완결
    def invalid(d, m, c, r):
        force_ok(d, m, c, r)
        if (m, c, r) == ("B1", "low", 3):
            Path(d, "status").write_text("invalid:pc-link-down\n")
    out = SM.campaign(make_campaign(tmp / "camp-inv", invalid))
    check("campaign/invalid-insufficient", out["pass"]["B1"] == "insufficient" and out["complete"] is False and out["pass"]["B0"] is True, out["pass"])
    check("campaign/runs-status", out["runs"]["B1/low/rep3"]["used"] is None and out["runs"]["B1/low/rep3"]["status"].startswith("invalid"), out["runs"]["B1/low/rep3"])
    # 재측정 시도 고르기 — 가장 늦은 ok 시도
    root = make_campaign(tmp / "camp-att", invalid)
    base = root / "B1" / "low"
    shutil.copytree(base / "rep3", base / "rep3.attempt1")
    shutil.rmtree(base / "rep3")
    shutil.copytree(root / "B1" / "low" / "rep2", base / "rep3")
    s = json.loads((base / "rep3" / "summary18.json").read_text())
    s["rep"] = 3
    (base / "rep3" / "summary18.json").write_text(json.dumps(s))
    out = SM.campaign(root)
    check("campaign/attempt-pick", out["runs"]["B1/low/rep3"]["used"] == "rep3" and out["runs"]["B1/low/rep3"]["attempts"] == 2 and out["pass"]["B1"] is True,
          out["runs"]["B1/low/rep3"])
    # 회차 사이 값 · SHA 불일치 → runs_consistent false · 실패 없는 방식은 insufficient
    def sha_diff(d, m, c, r):
        force_ok(d, m, c, r)
        if (m, c, r) == ("split", "low", 1):
            edit("meta.json", lambda o: o.update(sha="other"))(d, None)
    out = SM.campaign(make_campaign(tmp / "camp-sha", sha_diff))
    check("campaign/inconsistent", out["runs_consistent"] is False and all(v == "insufficient" for v in out["pass"].values()), out["pass"])
    # p99 상한 없음(값 결측) → insufficient
    def nolimit(d, m, c, r):
        force_ok(d, m, c, r)
        edit("plan.json", lambda o: o["values"].update(p99_limit_ms={"hold": None, "pay_start": 18.88, "confirm": 10.97}))(d, None)
    out = SM.campaign(make_campaign(tmp / "camp-nolimit", nolimit))
    check("campaign/no-limit", all(v == "insufficient" for v in out["pass"].values()), out["pass"])
    # CLI
    r = subprocess.run([sys.executable, "-I", str(HERE / "summarize18.py"), "--campaign", str(tmp / "camp-ok")], capture_output=True, text=True)
    check("campaign/cli", r.returncode == 0 and r.stdout.strip().splitlines()[-1].startswith("pass:") and (tmp / "camp-ok" / "campaign18.json").exists(), r.stdout + r.stderr)
    r = subprocess.run([sys.executable, "-I", str(HERE / "summarize18.py"), "--rep", str(tmp / "nope")], capture_output=True, text=True)
    check("rep/cli-noplan", r.returncode == 2, r.returncode)
    d = tmp / "ok-split-low"
    r = subprocess.run([sys.executable, "-I", str(HERE / "summarize18.py"), "--rep", str(d)], capture_output=True, text=True)
    check("rep/cli-ok", r.returncode == 0 and r.stdout.strip().splitlines()[-1] == "ok" and (d / "summary18.json").exists(), r.stdout + r.stderr)
    r = subprocess.run([sys.executable, "-I", str(HERE / "outcome18.py"), str(d)], capture_output=True, text=True)
    check("outcome/cli", r.returncode == 0 and len(json.loads(r.stdout)["sessions"]) == OVER["n"], r.stderr)
    r = subprocess.run([sys.executable, "-I", str(HERE / "outcome18.py"), str(tmp / "nope")], capture_output=True, text=True)
    check("outcome/cli-missing", r.returncode == 2 and json.loads(r.stdout)["error"] == "plan-missing", r.stdout)


# ---- 셸(campaign18.sh --simulate · run18.sh --dry-run) ---------------------------------------------------------------------------
def test_shell(tmp):
    env = dict(os.environ)
    r = subprocess.run(["bash", str(HERE / "run18.sh"), "--dry-run", "--worktree", "--smoke", "--method", "one-shot", "--cell", "edge", "--rep", "1",
                        "--id", "smoke18-x"], capture_output=True, text=True, env=env)
    check("shell/run-dry", r.returncode == 0 and "compose.pay18.yml" in r.stdout and "HOLD_GROUP_MODE=one-shot" in r.stdout and "HOLD_TTL=8s" in r.stdout,
          (r.stdout + r.stderr)[-600:])
    r = subprocess.run(["bash", str(HERE / "run18.sh"), "--dry-run", "--method", "B0", "--cell", "edge", "--rep", "1", "--id", "x"], capture_output=True, text=True)
    check("shell/run-edge-B", r.returncode == 2, r.returncode)
    r = subprocess.run(["bash", str(HERE / "run18.sh"), "--dry-run", "--method", "B1", "--cell", "burst", "--rep", "1", "--id", "x"], capture_output=True, text=True)
    check("shell/run-burst-B", r.returncode == 2, r.returncode)
    r = subprocess.run(["bash", str(HERE / "run18.sh"), "--dry-run", "--worktree", "--smoke", "--method", "split", "--cell", "burst", "--rep", "2", "--id", "smoke18-x"],
                       capture_output=True, text=True)
    check("shell/run-dry-burst", r.returncode == 0 and "BURST=1" in r.stdout and "HOLD_GROUP_MODE=split" in r.stdout, (r.stdout + r.stderr)[-400:])
    r = subprocess.run(["bash", str(HERE / "run18.sh"), "--sha", "HEAD", "--method", "split", "--cell", "low", "--rep", "1", "--id", "x", "--images", "/dev/null"],
                       capture_output=True, text=True)
    if plan18.FIXED:   # 고정 뒤에는 FIXED 관문을 지나 다음 관문(하네스 · 이미지)에서 멈춘다
        check("shell/run-fixed-gate", "FIXED = False" not in r.stderr, r.stderr[-300:])
    else:
        check("shell/run-not-fixed", r.returncode == 2 and "FIXED = False" in r.stderr, r.stderr[-300:])
    sim = tmp / "sim-plan.txt"
    # rep1 low B1 첫 시도 무효(하네스) → 재측정 2번까지 · split edge rep2 환경 무효 2번 → 3번째 ok
    sim.write_text("1:B1:low:1=invalid:seed-mismatch\n1:B1:low:2=invalid:seed-mismatch\n1:B1:low:3=invalid:seed-mismatch\n"
                   "2:split:edge:1=invalid:k6-conn-error\n2:split:edge:2=invalid:pc-link-down\n")
    res = tmp / "simroot"
    r = subprocess.run(["bash", str(HERE / "campaign18.sh"), "--sha", "HEAD", "--simulate", "--worktree", "--results-root", str(res), "--sim-plan", str(sim),
                        "--id", "sim18"], capture_output=True, text=True, timeout=1200)
    root = res / "sim18"
    check("shell/campaign-sim-invalid-left", r.returncode == 4 and "무효로 남은 자리 1개" in r.stderr, (r.stdout + r.stderr)[-800:])   # B1/low/rep1(하네스 상한 소진)
    st = lambda p: (root / p / "status").read_text().strip() if (root / p / "status").exists() else None  # noqa: E731
    check("shell/sim-harness-cap", st("B1/low/rep1") == "invalid:seed-mismatch" and (root / "B1/low/rep1.attempt2").is_dir()
          and not (root / "B1/low/rep1.attempt3").exists(), sorted(x.name for x in (root / "B1/low").iterdir()) if (root / "B1/low").exists() else None)
    check("shell/sim-env-retry", st("split/edge/rep2") == "ok" and (root / "split/edge/rep2.attempt2").is_dir(), st("split/edge/rep2"))
    order = (root / "order.txt").read_text() if (root / "order.txt").exists() else ""
    check("shell/sim-order", order.strip() == subprocess.run([sys.executable, "-I", str(HERE / "plan18.py"), "--order"], capture_output=True, text=True).stdout.strip())
    r = subprocess.run(["bash", str(HERE / "campaign18.sh"), "--check", str(root)], capture_output=True, text=True)
    check("shell/check-invalid", r.returncode == 4 and "B1" in r.stderr, r.stderr)
    shutil.copytree(root / "B0" / "low" / "rep1", root / "B1" / "low" / "rep1.ok")
    shutil.move(str(root / "B1" / "low" / "rep1"), str(root / "B1" / "low" / "rep1.attempt3"))
    shutil.move(str(root / "B1" / "low" / "rep1.ok"), str(root / "B1" / "low" / "rep1"))
    r = subprocess.run(["bash", str(HERE / "campaign18.sh"), "--check", str(root)], capture_output=True, text=True)
    check("shell/check", r.returncode == 0, r.stderr)
    shutil.rmtree(root / "B0" / "low" / "rep1")
    r = subprocess.run(["bash", str(HERE / "campaign18.sh"), "--check", str(root)], capture_output=True, text=True)
    check("shell/check-missing", r.returncode == 6, r.returncode)
    r = subprocess.run(["bash", str(HERE / "campaign18.sh"), "--sha", "HEAD", "--images", "/dev/null", "--start-conditions", "/dev/null"], capture_output=True, text=True)
    if plan18.FIXED:   # 고정 뒤: 시작 조건(/dev/null)이 없어 시작하지 않는다. 하네스가 커밋 전(작업트리 ≠ HEAD)이면 동결본(git archive HEAD)의 FIXED 관문이 먼저 막는다 — 둘 다 거부
        dirty = subprocess.run(["git", "-C", str(HERE.parent.parent.parent), "diff", "--quiet", "HEAD", "--", "k6/ADR-018"]).returncode != 0
        check("shell/campaign-fixed-gate", r.returncode != 0 and ("FIXED = False" in r.stderr) == dirty, (dirty, r.returncode, r.stderr[-300:]))
    else:
        check("shell/campaign-not-fixed", r.returncode == 2 and "FIXED = False" in r.stderr, r.stderr[-300:])


def main(argv):
    shell = "--no-shell" not in argv
    tmp = Path(tempfile.mkdtemp(prefix="selftest18-"))
    try:
        test_plan()
        test_shapes()
        test_classify()
        test_metrics()
        test_runs(tmp)
        test_campaign(tmp)
        if shell:
            test_shell(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    for f in FAILS:
        print("FAIL", f)
    total = NPASS[0] + len(FAILS)
    print(f"selftest18: 갈래 {total} · 통과 {NPASS[0]} · 실패 {len(FAILS)}{'' if shell else ' (셸 갈래 제외)'}")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
