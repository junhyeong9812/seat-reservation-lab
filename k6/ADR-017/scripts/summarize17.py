#!/usr/bin/env python3
"""ADR-017 요약기 — 회차 무효 판정 · 회차 요약 · 캠페인 판정(계약 k6/ADR-017/INTERFACE.md §7 · §8 · §9.2). 세션 분류 = outcome17(M17-1 — 결정표 하나).

    summarize17.py --rep <회차 폴더>        → <폴더>/summary17.json · 표준 출력 마지막 줄 = ok | invalid:<사유,…> · exit 0(판정함) · 2(plan 없음 · 판정 불가)
    summarize17.py --campaign <캠페인 폴더> → <폴더>/campaign17.json · 표준 출력 마지막 줄 = choice:<R|D|S|none> · exit 0

백분위(p50 · p90 · p99)는 ADR-014 summarize.p99와 같은 규칙 — 정렬 뒤 xs[⌊n × q⌋](n이 작으면 최댓값 쪽).
G1 항목에 record-level 대조(k6 결제 성공 ↔ PG 원장 ↔ DB intent — `record_*`)를 더한다(명세 §5 record-level)."""
import csv
import gzip
import importlib.util
import json
import math
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import outcome17 as O  # noqa: E402
import plan17  # noqa: E402

ATTR_TOL = 1e-6
RATIO_MUCH_HIGHER = 1.10
G1_SHAPES = ("orphan", "double_reservation", "double_refund", "refunded_with_seat", "voided_with_seat", "auth_open", "open_unpaid",
             "substituted_unpaid", "no_ledger", "no_intent")
PAY_ZERO = ("pay_multi_reservation_intents", "pay_rejection_state_mismatch", "pay_reserved_with_close", "pay_capture_and_close",
            "pay_rejected_without_task", "pay_dead_tasks", "pay_open_unheld_intents")
G3_METRICS = ("db_cpu_mean", "confirm_p99_ms", "pay_start_p99_ms")
# consistency 필수 v_* 키(LoadtestDataService ADR-001 항목 — INTERFACE §6.3): 없거나 숫자가 아니면 consistency-missing
V_KEYS = ("v_duplicate_hold_seats", "v_excess_hold_rows", "v_duplicate_confirmed_seats", "v_reserved_without_confirmed", "v_confirmed_on_unreserved_seat",
          "v_held_without_hold", "v_hold_on_unheld_seat", "v_stale_held_seats", "v_over_limit_users", "v_counter_mismatch")
# act17 필수 지표(INTERFACE §6.4): 값이 null이면 act-missing. 사전 값은 사전이고 그 안 값이 전부 숫자여야 한다
ACT_SCALARS = (("process_start_s",), ("compensation", "unresolved"), ("compensation", "oldest_pending_seconds"), ("compensation", "record_failed"),
               ("compensation", "sweep_failed"), ("confirm", "legacy"), ("confirm", "uid_conflict"))
ACT_DICTS = (("compensation", "done"), ("compensation", "retry"), ("compensation", "dead"), ("confirm", "rejected"), ("confirm", "failed"),
             ("confirm", "pay_start"), ("confirm", "extend"), ("pg_register",))
ENV_ANY = frozenset({"pc-link-down", "pc-mem-low"})       # 섞여 있어도 환경(campaign16 kind_of와 같은 우선순위)
ENV_SOLE = frozenset({"env-stall", "env-send-lag"})       # 이것만 있을 때 환경(campaign16의 env-stall 규칙)
INJECT_WALK_FACTOR, INJECT_WALK_PAD = 100, 10000   # 결정열 걷기 상한 = 처리 수 × 100 + 10,000(그 안에 처리 수에 못 닿으면 inject-undecidable)

ORDER = ["D", "R", "S"]
_S14 = None


def S14():
    """ADR-014 summarize(DB CPU — server_rows · db_cpu)를 그대로 쓴다(base 셀만 · 늦게 싣는다)."""
    global _S14
    if _S14 is None:
        p = HERE.parent.parent / "ADR-014" / "scripts" / "summarize.py"
        spec = importlib.util.spec_from_file_location("summarize14_for17", p)
        m = importlib.util.module_from_spec(spec)
        sys.path.insert(0, str(p.parent))
        spec.loader.exec_module(m)
        _S14 = m
    return _S14


def num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * q))] if xs else None


def load(path):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return None


def dist_s(ms_list):
    xs = [x / 1000 for x in ms_list]
    return {"n": len(xs), "p50": pct(xs, 0.5), "p99": pct(xs, 0.99), "max": max(xs) if xs else None}


def k6_csv(path):
    """k6 CSV → {"p99": {name: ms}, "n": {name: 200 표본 수}, "dropped": 합} (http_req_duration · status 200 · name ∈ confirm · pay_start). 파일 없음 · 깨짐 = None."""
    p = Path(path)
    if not p.exists():
        return None
    vals = {"confirm": [], "pay_start": []}
    dropped = 0
    try:
        with gzip.open(p, "rt", newline="") as f:
            rd = csv.reader(f)
            head = next(rd)
            im, iv, iname, ist = head.index("metric_name"), head.index("metric_value"), head.index("name"), head.index("status")
            for r in rd:
                if len(r) <= max(im, iv, iname, ist):
                    continue
                if r[im] == "http_req_duration" and r[ist] == "200" and r[iname] in vals:
                    vals[r[iname]].append(float(r[iv]))
                elif r[im] == "dropped_iterations":
                    dropped += int(float(r[iv]))
    except (OSError, ValueError, StopIteration, EOFError):
        return None
    return {"p99": {k: pct(v, 0.99) for k, v in vals.items()}, "n": {k: len(v) for k, v in vals.items()}, "dropped": dropped}


def env_kind(status):
    """회차 status → env | harness | valid(campaign17 kind_of와 같은 규칙 — campaign16 선례 우선순위): pc-link-down · pc-mem-low가 하나라도 있으면 env,
    사유가 전부 정지 계열(env-stall · env-send-lag)이면 env, 그 밖 무효 · status 없음은 harness."""
    if status == "ok":
        return "valid"
    if not status or not status.startswith("invalid:"):
        return "harness"
    rs = set(status.split(":", 1)[1].split(","))
    if rs & ENV_ANY:
        return "env"
    return "env" if rs and rs <= ENV_SOLE else "harness"


def latency_p99(path):
    """k6 CSV → {name: p99 ms}(http_req_duration · status 200 · name ∈ confirm · pay_start). 파일 없음 = None."""
    p = Path(path)
    if not p.exists():
        return None
    vals = {"confirm": [], "pay_start": []}
    try:
        with gzip.open(p, "rt", newline="") as f:
            rd = csv.reader(f)
            head = next(rd)
            im, iv, iname, ist = head.index("metric_name"), head.index("metric_value"), head.index("name"), head.index("status")
            for r in rd:
                if len(r) > max(im, iv, iname, ist) and r[im] == "http_req_duration" and r[ist] == "200" and r[iname] in vals:
                    vals[r[iname]].append(float(r[iv]))
    except (OSError, ValueError, StopIteration, EOFError):
        return None
    return {k: pct(v, 0.99) for k, v in vals.items()}


def faults_equal(a, b):
    if not isinstance(a, dict) or not isinstance(b, dict) or a.get("seed") != b.get("seed"):
        return False
    ra, rb = a.get("rules") or {}, b.get("rules") or {}
    if set(ra) != set(rb):
        return False
    for op in ra:
        for k in ("delayMs", "failRate", "unknownRate", "unknownDelayMs"):
            if not num(ra[op].get(k)) or not num(rb[op].get(k)) or abs(ra[op][k] - rb[op][k]) > 1e-12:
                return False
    return True


def _get(d, path):
    for k in path:
        d = d.get(k) if isinstance(d, dict) else None
    return d


def act_ok(a):
    """§6.4 필수 지표 — error 키 없음 · failed_names 없음 · 스칼라 숫자 · 사전 값 전부 숫자."""
    if not isinstance(a, dict) or "error" in a or a.get("failed_names"):
        return False
    if any(not num(_get(a, p)) for p in ACT_SCALARS):
        return False
    return all(isinstance(_get(a, p), dict) and all(num(v) for v in _get(a, p).values()) for p in ACT_DICTS)


# ---- mock-pg 주입 결정 재현(INTERFACE §7 inject-not-observed): Faults.decide = op마다 java.util.Random(seed × 31 + op.hashCode())의 nextDouble 열 ----
_M48 = (1 << 48) - 1


def java_hash(s):
    """java.lang.String.hashCode(32비트 부호 있음)."""
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h >= 1 << 31 else h


def s64(x):
    x &= (1 << 64) - 1
    return x - (1 << 64) if x >= 1 << 63 else x


class JavaRandom:
    """java.util.Random(48비트 LCG) — nextDouble만."""

    def __init__(self, seed):
        self.s = (seed ^ 0x5DEECE66D) & _M48

    def _next(self, bits):
        self.s = (self.s * 0x5DEECE66D + 0xB) & _M48
        return self.s >> (48 - bits)

    def next_double(self):
        return ((self._next(26) << 27) + self._next(27)) * (1.0 / (1 << 53))


def inject_decisions(cfg, ops):
    """주입 결정 수 하한 — op마다 결정열을 걸으며 처리(PROCEED · UNKNOWN — 원장 ops 1 증가)가 원장 ops 수에 닿을 때까지 만난 FAIL · UNKNOWN을 센다.
    규칙이 없거나 비율 0인 op는 난수를 쓰지 않는다(mock-pg와 같다). 반환 {op: {processed(걸어서 만난 처리 수 — reached면 원장 ops와 같다), fail, unknown, reached}}."""
    out = {}
    seed = int(cfg.get("seed") or 0)
    for op, r in (cfg.get("rules") or {}).items():
        fr, ur = float(r.get("failRate") or 0), float(r.get("unknownRate") or 0)
        if fr <= 0 and ur <= 0:
            continue
        n = int((ops or {}).get(op) or 0)
        rnd = JavaRandom(s64(seed * 31 + java_hash(op)))
        done = fail = unk = steps = 0
        limit = n * INJECT_WALK_FACTOR + INJECT_WALK_PAD
        while done < n and steps < limit:
            x = rnd.next_double()
            steps += 1
            if x < fr:
                fail += 1
            else:
                done += 1
                if x < fr + ur:
                    unk += 1
        out[op] = {"processed": done, "fail": fail, "unknown": unk, "reached": done == n}
    return out


def act_delta(before, end, path):
    def g(d):
        for k in path:
            d = d.get(k) if isinstance(d, dict) else None
        return d
    a, b = g(before), g(end)
    if isinstance(a, dict) or isinstance(b, dict):
        if not isinstance(a, dict) or not isinstance(b, dict) or any(v is None for v in list(a.values()) + list(b.values())):
            return None
        return sum(b.values()) - sum(a.values())
    return b - a if num(a) and num(b) else None


def rep_summary(d):
    """회차 폴더 → (summary dict, status 문자열). plan 없음 = (None, None)."""
    d = Path(d)
    plan = load(d / "plan.json")
    if not isinstance(plan, dict):
        return None, None
    cand, cell, rep = plan.get("cand"), plan.get("cell"), plan.get("rep")
    reasons, harness = set(), []
    hr = d / "harness-reasons.txt"
    if hr.exists():
        for x in hr.read_text().splitlines():
            if x.strip() and x.strip() not in harness:
                harness.append(x.strip())
    else:
        reasons.add("harness-reasons-missing")

    # ---- 세션 ----
    rd = O.read_sessions(O.sessions_file(d))
    rows = []
    if rd is None:
        reasons.add("sessions-missing")
    else:
        rows, broken = rd
        if broken:
            reasons.add("sessions-broken")
    sids = [r.get("sid") for r in rows if isinstance(r, dict)]
    if len(set(sids)) != len(sids):
        reasons.add("session-dup")
    planned = plan.get("planned") or {}
    by_kind_n = {}
    for r in rows:
        by_kind_n[r.get("kind")] = by_kind_n.get(r.get("kind"), 0) + 1
    if rd is not None and (len(rows) != plan.get("n_total") or any(by_kind_n.get(k, 0) != v for k, v in planned.items())
                           or any(k not in planned for k in by_kind_n)):
        reasons.add("sessions-count-mismatch")
    params = plan.get("params") or {}
    spare = (plan.get("seed") or {}).get("spare")
    user_base = int(((plan.get("k6") or {}).get("env") or {}).get("USER_BASE", plan17.USER_BASE))
    cyc = plan.get("kind_cycle") or []
    for r in rows:
        sid = r.get("sid")
        if not isinstance(sid, int) or sid < 0:
            reasons.add("session-attrs-mismatch")
            continue
        a = plan17.session_attrs(rep, sid, params)
        if any(not num(r.get(k)) or abs(r[k] - a[k]) > ATTR_TOL for k in ("T", "P", "G", "R", "L")) \
                or r.get("seat") != (spare or 0) + sid + 1 or r.get("user") != user_base + sid or not cyc or r.get("kind") != cyc[sid % len(cyc)]:
            reasons.add("session-attrs-mismatch")

    # ---- 입력 파일 ----
    led, db = load(d / "pg-ledger.json"), load(d / "db-dump.json")
    cons = load(d / "consistency.json")
    acts = {p: load(d / f"act17-{p}.json") for p in ("before", "after-k6", "end")}
    if not isinstance(led, dict) or not isinstance(led.get("payments"), list):
        reasons.add("ledger-missing")
        led = None
    if not isinstance(db, dict) or not all(isinstance(db.get(k), list) for k in ("payment_intent", "reservation", "confirm_rejection", "compensation_task")):
        reasons.add("db-dump-missing")
        db = None
    if not isinstance(cons, dict) or any(not num(cons.get(k)) for k in V_KEYS + PAY_ZERO):
        reasons.add("consistency-missing")
        cons = cons if isinstance(cons, dict) else None
    if not all(act_ok(a) for a in acts.values()):
        reasons.add("act-missing")
    ps0, ps1 = _get(acts["before"], ("process_start_s",)), _get(acts["end"], ("process_start_s",))
    restarted = num(ps0) and num(ps1) and ps0 != ps1
    if cell != "kill" and restarted:
        reasons.add("app-restarted")
    seed = load(d / "seed-check.json")
    if not isinstance(seed, dict):
        reasons.add("seed-check-missing")
    else:
        fly = [str(x) for x in seed.get("flyway") or []]
        if not (seed.get("schedules") == 1 and seed.get("seats") == plan["seed"]["seats"] and seed.get("available") == plan["seed"]["seats"]
                and seed.get("holds") == 0 and seed.get("reservations") == 0 and seed.get("quota_rows") == 0 and "15" in fly):
            reasons.add("seed-mismatch")
    if not (d / "app-config.json").exists():
        reasons.add("app-config-missing")
    else:
        rc, cc = plan17.config_check(cand, cell, d / "app-config.json")
        for x in cc["reasons"]:
            reasons.add({"cond-config-mismatch": "config-mismatch", "cond-config-missing": "app-config-missing"}.get(x, x))
    if cons is not None and cons.get("pay_compensation_mode") != plan.get("mode"):
        reasons.add("consistency-mode-mismatch")
    kw = load(d / "k6-window.json")
    off = kw.get("clock_offset_ms_given") if isinstance(kw, dict) else None
    clk = load(d / "clock-offset.json")
    if not num(off) or not isinstance(clk, dict) or not isinstance(clk.get("before"), dict):
        reasons.add("clock-offset-bad")
    if isinstance(kw, dict) and kw.get("exit") != 0:
        reasons.add(f"k6-exit-{kw.get('exit')}")
    elif not isinstance(kw, dict):
        reasons.add("k6-exit-unknown")
    k6t0_ms = kw["k6_t0_epoch"] * 1000 + off if isinstance(kw, dict) and num(kw.get("k6_t0_epoch")) and num(off) else None
    k6end_ms = kw["k6_end_epoch"] * 1000 + off if isinstance(kw, dict) and num(kw.get("k6_end_epoch")) and num(off) else None
    ks = load(d / "k6-summary.json")
    hd = ks.get("adr017") if isinstance(ks, dict) else None
    if not isinstance(hd, dict) or str(hd.get("rep")) != str(rep) or hd.get("cand") != cand or hd.get("cell") != cell \
            or hd.get("plan_n") != plan.get("n_total") or hd.get("cycle") != cyc or hd.get("spare") != spare:
        reasons.add("k6-plan-mismatch")

    # ---- 주입 · kill · 배수 ----
    inj = load(d / "inject.json")
    pf = (plan.get("pg") or {}).get("faults")
    if not isinstance(inj, dict) or not isinstance(inj.get("events"), list) or not inj["events"]:
        reasons.add("inject-missing")
    else:
        ev = inj["events"]
        if k6t0_ms is not None and any(e.get("at") == "reset" and num(e.get("t_ms")) and e["t_ms"] > k6t0_ms for e in ev):
            reasons.add("pg-reset-during-run")
        last_reset = max((i for i, e in enumerate(ev) if e.get("at") == "reset"), default=-1)
        fl = [e for e in ev[last_reset + 1:] if e.get("at") == "faults"]
        if last_reset < 0 or not fl or fl[-1].get("rc") != 0 or not faults_equal(fl[-1].get("response"), pf):
            reasons.add("inject-mismatch")
        if cell != "pg" and fl and isinstance(fl[-1].get("response"), dict) and (fl[-1]["response"].get("rules") or {}):
            reasons.add("inject-leak")
    applied = fl[-1]["response"] if isinstance(inj, dict) and isinstance(inj.get("events"), list) and inj["events"] and fl and isinstance(fl[-1].get("response"), dict) else pf
    kill = load(d / "kill.json") if cell == "kill" else None
    kill_out = None
    if cell == "kill":
        if not isinstance(kill, dict) or not isinstance(kill.get("events"), list):
            reasons.add("kill-missing")
            kill = None
        else:
            evs = kill["events"]
            st_at = [e.get("started_at") for e in evs]
            tol = (plan.get("values") or {}).get("kill_tol_ms")
            at = (plan.get("kill") or {}).get("at_s") or []
            t0k = kill.get("k6_t0_ms")
            timing = (num(tol) and num(t0k) and k6t0_ms is not None and k6end_ms is not None and abs(t0k - k6t0_ms) <= tol and len(at) == len(evs)
                      and all(num(e.get("kill_ms")) and num(e.get("planned_ms")) and abs(e["planned_ms"] - (t0k + a * 1000)) <= tol
                              and abs(e["kill_ms"] - e["planned_ms"]) <= tol and k6t0_ms <= e["kill_ms"] <= k6end_ms for e, a in zip(evs, at)))
            ok = (len(evs) == 2 and all(e.get("kill_rc") == 0 and e.get("start_rc") == 0 and e.get("ready_ok") is True for e in evs)
                  and len(set(st_at)) == 2 and all(st_at) and timing and restarted)
            if not ok:
                reasons.add("kill-not-observed")
            kill_out = {"events": len(evs), "restarted": ok}
    drain = load(d / "drain.json")
    drain_out = None
    if not isinstance(drain, dict) or drain.get("reason") not in ("empty", "cap"):
        reasons.add("drain-missing")
    else:
        polls = drain.get("polls") if isinstance(drain.get("polls"), list) else []
        if drain["reason"] == "cap" and not any(isinstance(x, dict) and "err" not in x and num(x.get("pending")) for x in polls):
            reasons.add("drain-poll-failed")   # 상한 도달 자체는 무효가 아니다(남은 일은 G1) — 조회가 한 번도 안 된 하네스 증거만 무효
        drain_out = {"reason": drain["reason"], "len_s": (drain["end_ms"] - drain["start_ms"]) / 1000 if num(drain.get("end_ms")) and num(drain.get("start_ms")) else None}

    # ---- 분류 · uid 끝 상태 ----
    ends = O.end_states(db, led) if db is not None and led is not None else {}
    kills = O.kill_windows(kill)
    cats, by_kind, inv, integ, mfail = [], {}, {}, {}, {}
    for r in rows:
        uid = r.get("uid")
        c = O.classify(r, ends.get(uid) if uid else None, cand, kills, plan) if (db is not None and led is not None) else "invalid:dump-missing"
        cats.append((r, c))
        k = str(r.get("kind"))
        by_kind.setdefault(k, {})
        by_kind[k][c] = by_kind[k].get(c, 0) + 1
        for pre, bucket in (("invalid:", inv), ("integrity:", integ), ("mode_fail:", mfail)):
            if c.startswith(pre):
                bucket[c] = bucket.get(c, 0) + 1
    if inv:
        reasons.add("session-invalid")
    uids = {}
    for e in ends.values():
        uids[e["shape"]] = uids.get(e["shape"], 0) + 1
    sess_uids = {r.get("uid") for r in rows if r.get("uid")} | {(r.get("p") or {}).get("uid") for r in rows if isinstance(r.get("p"), dict) and (r.get("p") or {}).get("uid")}
    # payment-start 미확정(503 · 응답 유실)인 세션 — intent는 커밋됐을 수 있다(PG 등록이 커밋 뒤) · (hold_id, user_id)로 uid를 이어 세션 uid로 본다(L2-1 · 셀 무관)
    linked, linked_kill = 0, 0
    if db is not None:
        by_hold = {(i.get("hold_id"), i.get("user_id")): i for i in db["payment_intent"]}
        for r, c in cats:
            if r.get("end") == "pay_start" and isinstance(r.get("p"), dict) and O._unsettled(r["p"]) and isinstance(r.get("h"), dict):
                i = by_hold.get((r["h"].get("hid"), r.get("user")))
                if i and i["uid"] not in sess_uids:
                    sess_uids.add(i["uid"])
                    linked += 1
                    linked_kill += c == "app_dead"
    left = [u for u in ends if u not in sess_uids]
    if left:
        reasons.add("pg-not-reset")
    if kill_out is not None:
        kill_out["left_intents"] = linked_kill
    tasks = db["compensation_task"] if db else []

    # ---- M17-6 · 주입 증거 · 지표 대조 ----
    late_rej = sum(1 for r in rows if r.get("kind") == "late" and isinstance(r.get("c1"), dict) and (r["c1"].get("st"), r["c1"].get("code")) in O.CONFIRM_REJECT)
    noconf_uids = {r.get("uid") for r in rows if r.get("kind") == "noconf" and r.get("uid")}
    sweep_close = sum(1 for t in tasks if t.get("uid") in noconf_uids and t.get("type") == "CLOSE" and t.get("source") == "SWEEP" and t.get("status") == "DONE")
    if rd is not None:
        if late_rej == 0:
            reasons.add("no-late-rejection")
        if db is not None and sweep_close == 0:
            reasons.add("no-sweep-close")
    inject = None
    if cell == "pg" and led is not None and isinstance(applied, dict):
        inject = inject_decisions(applied, led.get("ops"))
        if not all(v["reached"] for v in inject.values()):
            reasons.add("inject-undecidable")
        elif sum(v["fail"] + v["unknown"] for v in inject.values()) == 0:
            reasons.add("inject-not-observed")
    if cell != "kill" and db is not None and all(act_ok(a) for a in acts.values()):
        dd = act_delta(acts["before"], acts["end"], ("compensation", "done"))
        rj = act_delta(acts["before"], acts["end"], ("confirm", "rejected"))
        n_done = sum(1 for t in tasks if t.get("status") == "DONE")
        if dd is None or dd != n_done or rj is None or rj < len(db["confirm_rejection"]):
            reasons.add("app-metric-mismatch")

    # ---- record-level(k6 결제 성공 ↔ PG 원장 ↔ DB intent) ----
    pay_ok = [r for r in rows if isinstance(r.get("g"), dict) and r["g"].get("ok") is True]
    pay_ok_uids = {r.get("uid") for r in pay_ok}
    rec_not_ledger = sum(1 for r in pay_ok if not (ends.get(r.get("uid")) or {}).get("captured") and not (ends.get(r.get("uid")) or {}).get("authorized"))
    rec_no_intent = sum(1 for r in pay_ok if (ends.get(r.get("uid")) or {}).get("intent_state") is None)
    rec_ledger_paid_no_k6 = sum(1 for u, e in ends.items() if (e["captured"] or e["authorized"]) and u not in pay_ok_uids)
    record = {"k6_pay_ok": len(pay_ok), "ledger_paid": sum(1 for e in ends.values() if e["captured"] or e["authorized"]),
              "db_intents": len(db["payment_intent"]) if db else None, "pay_ok_not_in_ledger": rec_not_ledger,
              "pay_ok_no_intent": rec_no_intent, "ledger_paid_without_k6_pay": rec_ledger_paid_no_k6, "ps_linked_uids": linked}

    # ---- G1 ----
    items = {s: uids.get(s, 0) for s in G1_SHAPES}
    items["seat_moneyless"] = uids.get("seat_moneyless", 0) if cand in ("R", "S") else 0
    items["dead_tasks"] = sum(1 for t in tasks if t.get("status") == "DEAD") if db else None
    items["open_after_drain"] = sum(1 for i in db["payment_intent"] if i.get("state") == "OPEN") if db else None
    items["unfinished_tasks"] = sum(1 for t in tasks if t.get("status") in ("PENDING", "IN_PROGRESS")) if db else None   # 배수 상한에 닿아 남은 일(CLOSE · CAPTURE)
    items["consistency_pay"] = {k: cons.get(k) for k in PAY_ZERO} if cons else None
    items["consistency_v_sum"] = sum(v for k, v in cons.items() if k.startswith("v_") and num(v)) if cons else None
    items["integrity"] = sum(integ.values())
    items["wronged_open"] = sum(1 for _, c in cats if c == "wronged_open")
    items["mode_fail"] = sum(mfail.values())
    items["record_pay_ok_not_in_ledger"] = rec_not_ledger
    items["record_pay_ok_no_intent"] = rec_no_intent
    items["record_ledger_paid_without_k6_pay"] = rec_ledger_paid_no_k6

    def zero(v):
        if isinstance(v, dict):
            return all(num(x) and x == 0 for x in v.values())
        return num(v) and v == 0
    g1_ok = db is not None and led is not None and all(zero(v) for v in items.values())

    # ---- G2 ----
    rej_ms = {c["uid"]: c.get("rejected_ms") for c in db["confirm_rejection"]} if db else {}
    samp = {"REJECTION": [], "SWEEP": []}
    targets = [t for t in tasks if t.get("type") == "CLOSE" and t.get("status") == "DONE" and t.get("source") in samp]
    for t in targets:   # 시각 결측은 대체하지 않는다(g2-time-missing — 표본 수 = 대상 DONE CLOSE 수)
        start = rej_ms.get(t["uid"]) if t["source"] == "REJECTION" else t.get("created_ms")
        if not num(t.get("done_ms")) or not num(start):
            reasons.add("g2-time-missing")
            continue
        samp[t["source"]].append((t["done_ms"] - start) / 1000)
    allv = samp["REJECTION"] + samp["SWEEP"]
    g2 = {"n": len(allv), "p50_s": pct(allv, 0.5), "p90_s": pct(allv, 0.9), "p99_s": pct(allv, 0.99), "max_s": max(allv) if allv else None,
          "by_source": {k: {"n": len(v), "p99_s": pct(v, 0.99)} for k, v in samp.items()},
          "substitute_n": sum(1 for r in (db["reservation"] if db else []) if r.get("origin") == "SUBSTITUTE")}

    # ---- 환경(L2-3 · L2-4): PC 링크 · PC 메모리 · 빠진 반복 · 확정 송신 지연 ----
    vals = plan.get("values") or {}
    pl = load(d / "pc-link.json")
    if not isinstance(pl, dict) or pl.get("readable") is not True or not num(pl.get("down")):
        reasons.add("pc-link-missing")
    elif pl["down"] > 0:
        reasons.add("pc-link-down")
    pc_mem = None
    w0, w1 = (kw.get("k6_t0_epoch"), kw.get("k6_end_epoch")) if isinstance(kw, dict) else (None, None)
    cl_path = d / "timeline-client.jsonl"
    if not (num(w0) and num(w1)) or not cl_path.exists() or not num(vals.get("pc_mem_run_min_kb")) or not num(vals.get("pc_mem_gap_max_s")):
        reasons.add("pc-mem-missing")
    else:
        mrows, mbad = [], 0
        lines = [x for x in cl_path.read_text(errors="replace").splitlines() if x.strip()]
        for i, line in enumerate(lines):
            try:
                o = json.loads(line)
                t = S14().ts(o["t"])
            except (ValueError, KeyError, TypeError):
                mbad += i != len(lines) - 1
                continue
            if w0 <= t <= w1:
                if num(o.get("mem_avail_kb")):
                    mrows.append((t, o["mem_avail_kb"]))
                else:
                    mbad += 1
        tt = [w0] + sorted(t for t, _ in mrows) + [w1]
        gap = max(b - a for a, b in zip(tt, tt[1:]))
        if mbad or not mrows or gap > vals["pc_mem_gap_max_s"]:
            reasons.add("pc-mem-missing")
        else:
            pc_mem = {"min_kb": min(m for _, m in mrows), "n": len(mrows), "gap_max_s": gap}
            if pc_mem["min_kb"] < vals["pc_mem_run_min_kb"]:
                reasons.add("pc-mem-low")
    csvd = k6_csv(d / "k6-requests.csv.gz")
    if csvd is None:
        reasons.add("k6-csv-missing")
    elif csvd["dropped"] > 0:
        reasons.add("dropped-iterations")
    lags = [r["c1"]["ts"] - r["at"] for r in rows if r.get("end") == "done" and isinstance(r.get("c1"), dict) and num(r["c1"].get("ts")) and num(r.get("at"))]
    send_lag = {"n": len(lags), "p50": pct(lags, 0.5), "p99": pct(lags, 0.99), "max": max(lags) if lags else None,
                "over": None if not num(vals.get("send_lag_max_ms")) else sum(1 for x in lags if x > vals["send_lag_max_ms"])}
    if not num(vals.get("send_lag_max_ms")):
        reasons.add("env-send-lag")   # 허용치 없음 — 판정 불가를 통과로 두지 않는다
    elif send_lag["over"]:
        reasons.add("env-send-lag")

    # ---- G3(base 셀) ----
    g3 = {"db_cpu_mean": None, "confirm_p99_ms": None, "pay_start_p99_ms": None}
    if cell == "base":
        lat = csvd
        want = {"confirm": sum(1 for r in rows if isinstance(r.get("c1"), dict) and r["c1"].get("st") == 200),
                "pay_start": sum(1 for r in rows if isinstance(r.get("p"), dict) and r["p"].get("st") == 200)}
        if not lat or any(lat["p99"].get(k) is None or lat["n"].get(k) != want[k] for k in want):
            reasons.add("latency-missing")   # 표본 없음 · CSV 200 수 ≠ 세션 로그 성공 수(L2-5)
        else:
            g3["confirm_p99_ms"], g3["pay_start_p99_ms"] = lat["p99"]["confirm"], lat["p99"]["pay_start"]
        try:
            off_s = clk["before"]["offset_s"] if isinstance(clk, dict) and isinstance(clk.get("before"), dict) else None
            if num(off_s) and isinstance(kw, dict) and num(kw.get("k6_t0_epoch")) and num(kw.get("k6_end_epoch")):
                srows, _ = S14().server_rows(d / "timeline-server.jsonl", off_s)
                dc = S14().db_cpu(srows, kw["k6_t0_epoch"], kw["k6_end_epoch"]) if srows is not None and not srows.broken else None
                if dc and not dc["missing"]:
                    g3["db_cpu_mean"] = dc["mean"]
        except Exception:   # noqa: BLE001 — 입력 결측은 사유로(무음 아님)
            pass
        if g3["db_cpu_mean"] is None:
            reasons.add("db-cpu-missing")

    # ---- G-비용 ----
    cat_n = lambda name, kind=None: sum(1 for r, c in cats if c == name and (kind is None or r.get("kind") == kind))  # noqa: E731
    sub, late_ref = cat_n("wronged_substituted"), cat_n("wronged_refunded", "late")
    money = [iso2 - iso1 for iso1, iso2 in ((O.iso_ms(p.get("capturedAt")), O.iso_ms(p.get("closedAt"))) for p in (led["payments"] if led else [])
                                             if p.get("state") == "REFUNDED") if num(iso1) and num(iso2)]
    attempts = {}
    for t in tasks:
        attempts[str(t.get("attempts"))] = attempts.get(str(t.get("attempts")), 0) + 1
    kill_cell = cell == "kill"
    cost = {"moneyless": uids.get("seat_moneyless", 0), "substitute_ratio": (sub / (sub + late_ref)) if cand == "S" and (sub + late_ref) else None,
            "money_lost_s": dist_s(money), "pg_deadline_409": sum(1 for r in rows if isinstance(r.get("g"), dict) and r["g"].get("code") == "DEADLINE_PASSED"),
            "tasks_by_source": {s: sum(1 for t in tasks if t.get("source") == s) for s in ("REJECTION", "SWEEP", "CONFIRM")}, "attempts": attempts,
            "uid_conflict": None if kill_cell else act_delta(acts["before"], acts["end"], ("confirm", "uid_conflict")),
            "legacy": None if kill_cell else act_delta(acts["before"], acts["end"], ("confirm", "legacy")),
            "app_dead": cat_n("app_dead"),
            "void_time_s": dist_s([b - a for a, b in ((O.iso_ms(p.get("authorizedAt")), O.iso_ms(p.get("closedAt"))) for p in (led["payments"] if led else [])
                                                      if p.get("state") == "VOIDED") if num(a) and num(b)]),
            "app_dead_shapes": {}}
    for r, c in cats:
        if c == "app_dead":
            shp = (ends.get(r.get("uid")) or {}).get("shape", "none") if r.get("uid") else "none"
            cost["app_dead_shapes"][shp] = cost["app_dead_shapes"].get(shp, 0) + 1

    out_reasons = harness + sorted(r for r in reasons if r not in harness)
    status = "ok" if not out_reasons else "invalid:" + ",".join(out_reasons)
    summ = {"cand": cand, "cell": cell, "rep": rep, "status": status, "reasons": out_reasons,
            "sessions": {"n": len(rows), "planned": planned, "by_kind": by_kind, "invalid_detail": inv, "integrity": integ, "mode_fail": mfail},
            "uids": uids, "g1": {"ok": g1_ok, "items": items}, "g2": g2, "g3": g3,
            "m17_6": {"late_rejections": late_rej, "noconf_sweep_closes": sweep_close}, "cost": cost, "drain": drain_out, "kill": kill_out,
            "record": record, "inject": inject, "send_lag_ms": send_lag, "pc_mem": pc_mem}
    return summ, status


# ---- 캠페인 ------------------------------------------------------------------------------------------------------------------------
def attempts_of(cdir, cand, cell, rep):
    base = Path(cdir) / cand / cell
    out = []
    if (base / f"rep{rep}").is_dir():
        out.append(base / f"rep{rep}")
    tries = sorted((p for p in base.glob(f"rep{rep}.attempt*") if p.is_dir()), key=lambda p: int(p.name.split("attempt")[-1] or 0), reverse=True)
    return out + tries   # 가장 늦은 시도부터


def status_of(p):
    try:
        return (p / "status").read_text().strip()
    except OSError:
        return ""


def campaign(cdir):
    cdir = Path(cdir)
    runs, used, fixed = {}, {}, None
    for cand in plan17.CAND_ORDER:
        for cell in plan17.CELLS:
            for rep in range(1, plan17.REPS + 1):
                tries = attempts_of(cdir, cand, cell, rep)
                key = f"{cand}/{cell}/rep{rep}"
                pick = next((p for p in tries if status_of(p) == "ok"), None)
                runs[key] = {"status": status_of(pick) if pick else (status_of(tries[0]) if tries else ""), "attempts": len(tries),
                             "used": pick.name if pick else None}
                if pick:
                    s = load(pick / "summary17.json")
                    if not isinstance(s, dict):
                        s, _ = rep_summary(pick)
                    if isinstance(s, dict) and s.get("status") == "ok":
                        used[key] = s
                        pl = load(pick / "plan.json")
                        if isinstance(pl, dict) and fixed is None:
                            fixed = pl.get("fixed")
                    else:
                        runs[key]["used"] = None
    # 회차 사이 대조(L2-6 — M17-2): 사용 회차 전부의 plan.json values 전체 · meta.json 측정 SHA · 하네스 해시가 같아야 한다. 다르거나 없으면 모든 후보 판정 불가
    sigs = []
    for k in used:
        cand_, cell_, rep_ = k.split("/")
        pick = next((p for p in attempts_of(cdir, cand_, cell_, int(rep_[3:])) if p.name == runs[k]["used"]), None)
        pl, mt = (load(pick / "plan.json"), load(pick / "meta.json")) if pick else (None, None)
        sigs.append(json.dumps([(pl or {}).get("values"), (mt or {}).get("sha"), (mt or {}).get("harness_hash")], sort_keys=True)
                    if isinstance(pl, dict) and isinstance(pl.get("values"), dict) and isinstance(mt, dict) and mt.get("sha") and mt.get("harness_hash") else None)
    values_ok = bool(sigs) and None not in sigs and len(set(sigs)) == 1
    v0 = json.loads(sigs[0])[0] if values_ok else {}
    plan_vals = {"g2_x_s": v0.get("g2_x_s") if values_ok else None, "g2_y_s": v0.get("g2_y_s") if values_ok else None}
    g1, g2, g3, pas, cost = {}, {}, {}, {}, {}
    full = {c: all(f"{c}/{cell}/rep{r}" in used for cell in plan17.CELLS for r in range(1, plan17.REPS + 1)) for c in plan17.CAND_ORDER}
    for c in plan17.CAND_ORDER:
        mine = {k: v for k, v in used.items() if k.startswith(c + "/")}
        fails = sorted(k for k, v in mine.items() if not v["g1"]["ok"])
        g1[c] = {"ok": not fails and full[c], "fail_runs": fails}
        g2[c] = {}
        for cell in plan17.CELLS:
            ps = [used[f"{c}/{cell}/rep{r}"]["g2"]["p99_s"] for r in range(1, plan17.REPS + 1) if f"{c}/{cell}/rep{r}" in used]
            lim = plan_vals["g2_x_s"] if cell == "base" else plan_vals["g2_y_s"]
            g2[c][cell] = {"p99_s": ps, "limit_s": lim, "ok": num(lim) and len(ps) == plan17.REPS and all(num(p) and p <= lim for p in ps),
                           "no_sample": any(p is None for p in ps), "over": num(lim) and any(num(p) and p > lim for p in ps)}
        base = [used[f"{c}/base/rep{r}"]["g3"] for r in range(1, plan17.REPS + 1) if f"{c}/base/rep{r}" in used]
        g3[c] = {}
        for m in G3_METRICS:
            xs = [b.get(m) for b in base]
            ok = len(xs) == plan17.REPS and all(num(x) for x in xs)
            g3[c][m] = {"range": [min(xs), max(xs)] if ok else None, "median": statistics.median(xs) if ok else None, "ratio_vs_R": None, "much_higher": False}
        agg = {"moneyless": 0, "substituted": 0, "late_refunded": 0, "pg_deadline_409": 0, "app_dead": 0, "tasks_by_source": {}, "money_lost_p99_s": []}
        for v in mine.values():
            cc = v["cost"]
            agg["moneyless"] += cc["moneyless"]
            agg["pg_deadline_409"] += cc["pg_deadline_409"]
            agg["app_dead"] += cc["app_dead"]
            for s, n in cc["tasks_by_source"].items():
                agg["tasks_by_source"][s] = agg["tasks_by_source"].get(s, 0) + n
            agg["money_lost_p99_s"].append(cc["money_lost_s"]["p99"])
            agg["substituted"] += sum(n for k, kd in v["sessions"]["by_kind"].items() for cat, n in kd.items() if cat == "wronged_substituted")
            agg["late_refunded"] += v["sessions"]["by_kind"].get("late", {}).get("wronged_refunded", 0)
        agg["substitute_ratio"] = agg["substituted"] / (agg["substituted"] + agg["late_refunded"]) if c == "S" and (agg["substituted"] + agg["late_refunded"]) else None
        cost[c] = agg
    r = g3["R"]
    for c in plan17.CAND_ORDER:
        okc = all(g3[c][m]["range"] is not None for m in G3_METRICS) and all(r[m]["range"] is not None for m in G3_METRICS)
        for m in G3_METRICS:
            x, b = g3[c][m], r[m]
            if x["median"] is not None and b["median"]:
                x["ratio_vs_R"] = x["median"] / b["median"]
            if c != "R" and x["range"] and b["range"]:
                x["much_higher"] = x["range"][0] > b["range"][1] and x["ratio_vs_R"] is not None and x["ratio_vs_R"] > RATIO_MUCH_HIGHER
        g3[c]["ok"] = okc and not any(g3[c][m]["much_higher"] for m in G3_METRICS)
    for c in plan17.CAND_ORDER:
        if not full[c] or not full["R"] or not values_ok:
            pas[c] = "insufficient"
        elif not g1[c]["ok"] or any(g2[c][cell]["over"] for cell in plan17.CELLS) or not g3[c]["ok"]:
            pas[c] = False   # 판정된 실패가 우선
        elif any(g2[c][cell]["no_sample"] for cell in plan17.CELLS):
            pas[c] = "insufficient"   # G2 표본 0 = 판정 불가(L1-9)
        else:
            pas[c] = bool(all(g2[c][cell]["ok"] for cell in plan17.CELLS))
    if pas.get("R") == "insufficient":   # R이 판정 불가면 G3 기준이 없다 — 모든 후보 판정 불가(L2-7)
        pas = {c: "insufficient" for c in pas}
    choice = None   # 순서대로 — 통과면 고르고, 실패면 다음, 판정 불가(insufficient)면 멈춘다(아래 후보로 내려가지 않는다 — ADR-016 undetermined 선례)
    for c in ORDER:
        if pas[c] is True:
            choice = c
            break
        if pas[c] == "insufficient":
            break
    out = {"plan_fixed": fixed, "runs_consistent": values_ok, "runs": runs, "g1": g1, "g2": g2, "g3": g3, "pass": pas, "order": ORDER, "choice": choice, "cost": cost}
    return out


def main(argv):
    a = argv[1:]
    if len(a) == 2 and a[0] == "--rep":
        s, st = rep_summary(a[1])
        if s is None:
            print("plan.json 없음 · 깨짐 — 판정 불가", file=sys.stderr)
            return 2
        Path(a[1], "summary17.json").write_text(json.dumps(s, ensure_ascii=False, indent=1))
        print(st)
        return 0
    if len(a) == 2 and a[0] == "--campaign":
        out = campaign(a[1])
        Path(a[1], "campaign17.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
        print(json.dumps(out["pass"], ensure_ascii=False))
        print(f"choice:{out['choice'] or 'none'}")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
