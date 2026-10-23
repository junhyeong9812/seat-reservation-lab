#!/usr/bin/env python3
"""ADR-013 계획의 단일 출처 — 시각표(§4.2) · 절차별 단계(§4.2 표) · 계획 사건 목록(H3) · 탐침 일정(§5.8-1) · 구간 분류(§4.7.1 S5-11) ·
경계 탐침 판정(DS-10) · 기대 설정(H9). run.sh·campaign.sh·limitprobe.py·summarize.py가 모두 이 파일을 읽는다(값을 두 곳에 두지 않는다).

    plan13.py --render <절차 1..5> <S4c|S3e>             → 회차 계획 JSON(표준 출력): 시각표 · 단계(orchestrator가 그대로 실행) · 계획 사건 목록 · 끝 상태 · 탐침 일정
    plan13.py --probe-dry-run [--startup-s N] [--collect-s N] [--stop-s N] [--json]
                                                          → 다섯 절차 × 두 부하의 계획 칸에서 경계 탐침 수(§4.9-20 · §5.8-1 — 0이 아니면 exit 1)
                                                            민감도: 기동 → UP · 수집 · 내림에 걸리는 초(계획 판정은 모두 0 — 계획 시각 그대로)
    plan13.py --config-check <old|shadow|new> <configprops.json>   → 기대 설정 대조(H9 — cond-config-mismatch) · 사유 한 줄씩 · exit 0 일치 · 1 불일치 · 2 판정 불가
    plan13.py --events-check <회차폴더>                    → events-check.json(H3 계획 대 실제 — 빠진 · 계획에 없는 · 순서 · 끝 상태) · exit 0 일치 · 1 불일치

시각은 모두 '회차 기준 초' = 주 k6를 띄운 PC 시각(run.sh T0) 기준. 실제 사건 시각은 events.jsonl(PC epoch · 서버 epoch)."""
import json
import sys
from pathlib import Path

# ---- 고정값(ADR-013 §4.2 · §5.1 · §5.8 — 측정 뒤 바꾸지 않는다) ------------------------------------------------------------
LOADS = {
    # S4c: 램프 60 + 30초 × 38구간 = 1,200초 · T1 300 · T2 600 · 수렴 180초(§4.2 시각표)
    "S4c": {"k6_s": 1200, "T1": 300, "T2": 600, "converge_s": 180, "probe_schedule": 1000,
            "k6_env": "START_RATE=4200 FACTOR=1 STEPS=38 STEP_S=30 STOP_ON_ERROR=0 RAMP_S=60 RAMP_FROM=50",
            "script": "s4-throughput.js", "seed": {"schedules": 1000, "seats": 10000, "sql_from": 501, "sql_to": 999},
            "ttl": "60m", "expiry": "10s", "plan_rate": 4200, "ramp_s": 60, "ramp_from": 50},
    # S3e: 입장 667초(200,000 ÷ 300) + 꼬리 360초 ≈ 1,027초 · T1 360 · T2 540 · 수렴 = TTL 300 + 유예 20 + 2(지금 하네스 run.sh:861)
    "S3e": {"k6_s": 1027, "T1": 360, "T2": 540, "converge_s": 322, "probe_schedule": 2,
            "k6_env": "RATE=300 TOTAL=200000 SEATS=10000 RETRIES=3 THINK_MIN_S=30 THINK_MAX_S=240 TAIL_S=360 ABANDON=0.2",
            "script": "s3-full-flow.js", "seed": {"schedules": 2, "seats": 10000},
            "ttl": "5m", "expiry": "10s", "plan_rate": None, "ramp_s": 0, "ramp_from": None,
            "inject_rounds": "270:10,5,5,10 450:10,5,5,10 630:10,5,5,10"},   # §4.2 주입 차례(시각만 — 종류·수는 지금 기본)
}
PRE_APPLY_AT = 90            # ④⑤ 운영 중 적용판 시작(§4.2)
BASELINE_WINDOW = (60, 90)   # §5.3 단독(옛) 기준 창 — 모든 절차 공통(S5-7)
D1_FROM = 90                 # §5.5 D1 창 [90, k6 끝)
P12_WINDOW = (900, 1200)     # §5.2 P12 — S4c 단독(새) 마지막 300초
HEALTH_REP_START_S = 180     # 회차 시작 기동(지금 하네스 run.sh:708)
HEALTH_IN_REP_S = 300        # §5.1 health 시한(회차 안 모든 기동)
DRAIN_S, DRAIN_EXTRA_S, DRAIN_EXTRA_MAX = 5, 5, 2   # §4.2 lb 규칙 · H2 ⓒ(비움 5초 · 더 5초 최대 2번)
MLOCK_BEFORE_S, MLOCK_AFTER_S = 10, 30              # §4.5-2 촘촘한 표본 창 [T1 − 10, 마지막 lb 넣기 + 30] · ④⑤ [적용판 시작 − 10, 끝 + 30]
AVAIL = {"rate": 2, "timeout_s": 5, "vus": 20, "seat_offset": 5000}   # §5.1 가용성 탐침(초당 2 · 시한 5초 · VU 20/20 · 좌석 5,001~10,000)
# §5.8-1 탐침 일정: 칸 시작 + 30초부터 6초 간격 · d 내림차순 · H 먼저
PROBE_ORDER = [(75, "H"), (75, "L"), (30, "H"), (30, "L"), (5, "H"), (5, "L"), (1, "H"), (1, "L")]
PROBE_FIRST_S, PROBE_GAP_S = 30, 6
PROBE_DECIDE_MARGIN_S = 2    # dry-run: 판정 요청 2번(L)·주입 문장 왕복을 덮는 여유(보수적 — 늦게 끝나는 쪽으로 본다)
PROBE_USER_BASE, PROBE_SEAT_STRIDE = 90_000_000, 3   # 탐침마다 새 사용자 · 좌석 3석(L은 선점 3번)
AVAIL_USER_BASE = 80_000_000
SEATS_PER_SCHEDULE = 10_000
# §5.5 단계 수(고정값) — K · 되돌리기 추가 중단 · 설정 벌 수
K_FIXED = {1: (2, 1, 1), 2: (4, 0, 2), 3: (2, 0, 1), 4: (6, 0, 1), 5: (8, 0, 2)}
CELL_NAMES = ("old", "trans", "new")

# 사건 종류 — owner_changed(DS-10)를 만드는 것: lb 넣기·빼기 · 앱 기동(start)·UP(wait-health 끝) · 내림(stop). 파생(DB 기록): 새 앱 첫 증분 틱 · 옛 앱 마지막 C 바퀴 끝
OWNER_EVENTS = ("lb-add", "lb-remove", "start", "wait-health", "stop")


def _start(slot, role):
    """앱 하나를 올려 lb에 넣기까지(§4.2 lb 규칙 — 넣는 것은 health UP 뒤)."""
    return [{"op": "start", "slot": slot, "role": role}, {"op": "wait-health", "slot": slot},
            {"op": "config-check", "slot": slot}, {"op": "lb-add", "slot": slot}]


def _down(slot):
    """앱 하나를 빼고 비우고 수집하고 내리기(§4.2 lb 규칙 — 빼고 → 비움 → 수집 → 내림)."""
    return [{"op": "lb-remove", "slot": slot}, {"op": "drain", "slot": slot}, {"op": "collect", "slot": slot}, {"op": "stop", "slot": slot}]


def steps_of(proc, load):
    L = LOADS[load]
    t1, t2 = L["T1"], L["T2"]
    setup = {"name": "setup", "at_s": None, "actions": _start("A", "old")}
    if proc == 1:
        steps = [{"name": "T1", "at_s": t1, "actions": _down("A") + _start("B", "new")}]
        end = {"alive": ["B"], "lb": ["B"], "final_new": "B"}
    elif proc == 2:
        steps = [{"name": "T1", "at_s": t1, "actions": _start("B", "shadow")},
                 {"name": "T2", "at_s": t2, "actions": _down("A") + _start("A", "new") + _down("B")}]
        end = {"alive": ["A"], "lb": ["A"], "final_new": "A"}
    elif proc == 3:
        steps = [{"name": "T1", "at_s": t1, "actions": _start("B", "new")},
                 {"name": "T2", "at_s": t2, "actions": _down("A")}]
        end = {"alive": ["B"], "lb": ["B"], "final_new": "B"}
    elif proc == 4:
        steps = [{"name": "pre", "at_s": PRE_APPLY_AT, "actions": [{"op": "pre-apply"}]},
                 {"name": "T1", "at_s": t1, "actions": _start("B", "new")},
                 {"name": "T2", "at_s": t2, "actions": _down("A")}]
        end = {"alive": ["B"], "lb": ["B"], "final_new": "B"}
    elif proc == 5:
        steps = [{"name": "pre", "at_s": PRE_APPLY_AT, "actions": [{"op": "pre-apply"}]},
                 {"name": "T1", "at_s": t1, "actions": _start("B", "shadow")},
                 {"name": "T2", "at_s": t2, "actions": _down("A") + _start("A", "new") + _down("B")}]
        end = {"alive": ["A"], "lb": ["A"], "final_new": "A"}
    else:
        raise ValueError(f"절차 1..5 — {proc}")
    return setup, steps, end


def probe_schedule(load):
    """§5.8-1 — 칸마다 8탐침. 계획 시각 기준(실제 사건이 늦어도 옮기지 않는다)."""
    L = LOADS[load]
    out, idx = [], 0
    for cell, c0 in zip(CELL_NAMES, (0, L["T1"], L["T2"])):
        for k, (d, kind) in enumerate(PROBE_ORDER):
            seat0 = (L["probe_schedule"] - 1) * SEATS_PER_SCHEDULE + 1 + idx * PROBE_SEAT_STRIDE
            out.append({"id": idx, "cell": cell, "kind": kind, "d": d, "at_s": c0 + PROBE_FIRST_S + k * PROBE_GAP_S,
                        "schedule_id": L["probe_schedule"], "user_id": PROBE_USER_BASE + idx, "seats": [seat0, seat0 + 1, seat0 + 2]})
            idx += 1
    assert idx * PROBE_SEAT_STRIDE <= AVAIL["seat_offset"], "한도 탐침 좌석이 가용성 탐침 범위(5,001~)를 넘는다"
    return out


def render(proc, load):
    setup, steps, end = steps_of(proc, load)
    planned = [{"step": s["name"], "op": a["op"], "slot": a.get("slot"), **({"role": a["role"]} if "role" in a else {})}
               for s in [setup] + steps for a in s["actions"]]
    L = LOADS[load]
    return {"proc": proc, "load": load, "timeline": {k: L[k] for k in ("k6_s", "T1", "T2", "converge_s")},
            "pre_apply_at": PRE_APPLY_AT if proc in (4, 5) else None, "load_conf": L,
            "setup": setup, "steps": steps, "planned_events": planned, "end_state": end,
            "probes": probe_schedule(load), "avail": {**AVAIL, "schedule_id": L["probe_schedule"],
                                                      "seat_base": (L["probe_schedule"] - 1) * SEATS_PER_SCHEDULE + AVAIL["seat_offset"],
                                                      "user_base": AVAIL_USER_BASE},
            "fixed": {"health_rep_start_s": HEALTH_REP_START_S, "health_in_rep_s": HEALTH_IN_REP_S, "drain_s": DRAIN_S,
                      "drain_extra_s": DRAIN_EXTRA_S, "drain_extra_max": DRAIN_EXTRA_MAX, "mlock_before_s": MLOCK_BEFORE_S, "mlock_after_s": MLOCK_AFTER_S,
                      "baseline_window": BASELINE_WINDOW, "d1_from": D1_FROM, "p12_window": P12_WINDOW},
            "K": dict(zip(("K", "rollback_outage", "config_sets"), K_FIXED[proc]))}


# ---- 구간 분류(§4.7.1 S5-11 · 실제 사건 시각) — summarize.py와 dry-run이 같은 함수를 쓴다 ----------------------------------------
def intervals(events, k6_end_s):
    """events: [{step, op, slot, t0, t1}](회차 기준 초 · 결과 ok만 — 실패 사건도 시각은 쓴다). →
    {"old": [0, a), "trans": [a, b), "new": [b, k6_end)} · ①은 trans를 'outage'로 이름표(중단 = 옛 앱 lb 빼기 ~ 새 앱 lb 넣기 끝).
    a = T1 단계 첫 사건 시작 · b = 마지막 전환 단계(T2가 있으면 T2, 없으면 T1)의 마지막 사건 끝(①은 lb 넣기 끝 · ③④ 옛 앱 내림 끝 · ②⑤ 그림자 앱 내림 끝)."""
    t1 = [e for e in events if e["step"] == "T1"]
    if not t1:
        return None
    a = min(e["t0"] for e in t1)
    last_step = "T2" if any(e["step"] == "T2" for e in events) else "T1"
    b = max(e["t1"] for e in events if e["step"] == last_step)
    return {"old": [0.0, a], "trans": [a, b], "new": [b, float(k6_end_s)]}


def interval_of(t, iv):
    if iv is None or t is None:
        return None
    for name in ("old", "trans", "new"):
        lo, hi = iv[name]
        if lo <= t < hi:
            return name
    return "after" if t >= iv["new"][1] else None


def owner_instants(events, derived=()):
    """owner_changed를 만드는 순간들 — 사건 시작·끝(OWNER_EVENTS) + 파생(첫 증분 틱 · 마지막 C 바퀴 끝)."""
    out = []
    for e in events:
        if e["op"] in OWNER_EVENTS:
            out += [(e["t0"], f"{e['op']}:{e['slot']}"), (e["t1"], f"{e['op']}:{e['slot']}")]
    out += list(derived)
    return sorted(out)


def classify_probe(inject_s, decide_s, iv, instants):
    """→ (구간 | None, 경계 여부, 넘어간 사건 목록). 경계 = 주입·판정 구간이 다르거나 [주입, 판정 끝] 안에 소유 변경 순간이 있다(DS-10)."""
    a, b = interval_of(inject_s, iv), interval_of(decide_s, iv)
    crossed = [n for (t, n) in instants if inject_s <= t <= decide_s]
    return (a if a == b else None), (a != b or bool(crossed)), crossed


# ---- dry-run(§4.9-20) ---------------------------------------------------------------------------------------------------
def planned_events_timed(proc, load, startup_s=0.0, collect_s=0.0, stop_s=0.0):
    """계획 시각으로 사건 시각을 만든다 — 계획(인자 0)은 모든 사건이 순간(계획 시각표의 '같은 시각' 해석).
    민감도: startup_s = 기동 → UP · collect_s = 수집 · stop_s = 내림에 걸리는 초 — startup_s > 0이면 비움도 계획 비움 시간(DRAIN_S)을 쓴다."""
    setup, steps, _ = steps_of(proc, load)
    ev, derived = [], []
    for s in steps:
        t = float(s["at_s"])
        for a in s["actions"]:
            dur = 0.0
            if startup_s > 0 or collect_s > 0 or stop_s > 0:
                dur = {"wait-health": startup_s, "drain": float(DRAIN_S) if startup_s > 0 else 0.0, "collect": collect_s, "stop": stop_s}.get(a["op"], 0.0)
            if a["op"] == "pre-apply":
                dur = 0.0   # 적용판은 소유 변경이 아니다(OWNER_EVENTS 밖) — 길이는 dry-run 판정에 들지 않는다
            ev.append({"step": s["name"], "op": a["op"], "slot": a.get("slot"), "t0": t, "t1": t + dur})
            if a["op"] == "wait-health" and a.get("slot") and _role_at(steps, s, a) == "new":
                derived.append((t + dur, f"first-inc-tick:{a['slot']}"))   # 새 앱 첫 증분 틱 ≈ UP
            if a["op"] == "stop" and _role_before_stop(steps, s, a) == "old":
                derived.append((t, "last-c-round-end"))                      # 옛 앱 마지막 C 바퀴 끝 ≈ 내림
            t += dur
    return ev, derived


def _role_at(steps, step, action):
    role = None
    for s in steps:
        for a in s["actions"]:
            if a["op"] == "start" and a["slot"] == action["slot"]:
                role = a["role"]
            if a is action:
                return role
    return role


def _role_before_stop(steps, step, action):
    role = "old" if action["slot"] == "A" else None
    for s in steps:
        for a in s["actions"]:
            if a["op"] == "start" and a["slot"] == action["slot"]:
                role = a["role"]
            if a is action:
                return role
    return role


def probe_dry_run(startup_s=0.0, collect_s=0.0, stop_s=0.0):
    rows, total = [], 0
    for load in LOADS:
        k6_s = LOADS[load]["k6_s"]
        for proc in range(1, 6):
            ev, derived = planned_events_timed(proc, load, startup_s, collect_s, stop_s)
            iv = intervals(ev, k6_s)
            inst = owner_instants(ev, derived)
            cells = {c: {"probes": 0, "boundary": 0, "in": {}, "lost": []} for c in CELL_NAMES}
            worst = None
            for p in probe_schedule(load):
                inj = float(p["at_s"])
                dec = inj + p["d"] + PROBE_DECIDE_MARGIN_S
                name, bnd, crossed = classify_probe(inj, dec, iv, inst)
                c = cells[p["cell"]]
                c["probes"] += 1
                key = "boundary" if bnd else name
                c["in"][key] = c["in"].get(key, 0) + 1
                if bnd:
                    c["boundary"] += 1
                    c["lost"].append(f"d{p['d']}{p['kind']}")
                # 칸 끝까지 남는 여유(가장 늦게 끝나는 판정 → 그 칸이 속한 구간 끝)
                if name:
                    slack = iv[name][1] - dec
                    worst = slack if worst is None else min(worst, slack)
            b = sum(c["boundary"] for c in cells.values())
            total += b
            rows.append({"load": load, "proc": proc, "boundary": b, "min_slack_s": worst, "cells": cells,
                         "intervals": iv})
    return total, rows


# ---- 기대 설정(H9 · §4.3 표 · K1) -----------------------------------------------------------------------------------------
def _norm(v):
    return str(v).replace("-", "_").upper() if v is not None else None


def _dur_s(v):
    """Spring Duration 표기(PT1M · PT1S · 60s · 1000ms) → 초."""
    if v is None:
        return None
    s = str(v).strip()
    try:
        if s.upper().startswith("PT"):
            import re
            m = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", s.upper())
            if not m:
                return None
            h, mi, se = (float(x) if x else 0.0 for x in m.groups())
            return h * 3600 + mi * 60 + se
        for suf, mul in (("ms", 0.001), ("s", 1), ("m", 60), ("h", 3600)):
            if s.endswith(suf) and s[: -len(suf)].replace(".", "", 1).isdigit():
                return float(s[: -len(suf)]) * mul
        return float(s) / 1000 if s.isdigit() else None   # 단위 없는 숫자 = ms(Spring 기본)
    except ValueError:
        return None


NEW_FLYWAY = ["classpath:db/migration", "classpath:db/migration-updated-at-trigger", "classpath:db/migration-incremental"]


def config_check(role, props):
    """props = [{prefix, properties}](configprops 묶음). 사유 목록(빈 목록 = 일치) · None = 판정 불가(seat.hold 묶음 없음)."""
    hold = next((b.get("properties") or {} for b in props if b.get("prefix") == "seat.hold"), None)
    fw = next((b.get("properties") or {} for b in props if b.get("prefix") == "spring.flyway"), None)
    if hold is None:
        return None
    cd = hold.get("counterDrift") or {}
    rc = cd.get("reconcile") or {}
    inc = rc.get("incremental") or {}
    sn = rc.get("safetyNet") or {}
    cl = cd.get("cleanup") or {}
    r = []

    def want(name, got, exp):
        if got != exp:
            r.append(f"{role}: {name} {got!r} ≠ {exp!r}")
    want("counterDrift.expiryRepair", cd.get("expiryRepair"), True)
    want("cleanup.mode", _norm(cl.get("mode")), "SCHEDULE")
    want("limitStrategy", _norm(hold.get("limitStrategy")), "COUNTER_UPSERT_PRECHECK")
    if role == "old":
        want("reconcile.enabled(C)", rc.get("enabled"), True)
        if _dur_s(rc.get("interval")) != 60.0:
            r.append(f"old: reconcile.interval {rc.get('interval')!r} ≠ 60s")
        if "detectShape" in rc and _norm(rc.get("detectShape")) != "ONE_SHOT":
            r.append(f"old: detectShape {rc.get('detectShape')!r} ≠ one-shot")
        for k in ("incremental", "safetyNet"):   # 옛 앱(93dd610a)에는 없는 묶음 — 있으면 다른 이미지다
            if k in rc:
                r.append(f"old: reconcile.{k} 묶음이 있다(옛 이미지가 아니다)")
        locs = (fw or {}).get("locations")
        if locs is not None and [str(x) for x in locs] not in (["classpath:db/migration"], ["db/migration"]):
            r.append(f"old: flyway.locations {locs!r} ≠ [classpath:db/migration]")
    else:
        want("reconcile.enabled(C)", rc.get("enabled"), False)
        want("incremental.method", _norm(inc.get("method")), "TRIGGER")
        want("incremental.enabled", inc.get("enabled"), role == "new")
        want("safetyNet.enabled", sn.get("enabled"), role == "new")
        if role == "new":
            if _dur_s(inc.get("interval")) != 1.0:
                r.append(f"new: incremental.interval {inc.get('interval')!r} ≠ 1s")
            want("detectShape", _norm(rc.get("detectShape")), "SPLIT")
        locs = (fw or {}).get("locations")
        if locs is None or [str(x) for x in locs] != NEW_FLYWAY:
            r.append(f"{role}: flyway.locations {locs!r} ≠ {NEW_FLYWAY}(기본 위치 — K1)")
    if role == "new" and inc.get("pageSize") is not None and not (isinstance(inc.get("pageSize"), int) and inc["pageSize"] > 0):
        r.append(f"new: incremental.pageSize {inc.get('pageSize')!r} — 양수가 아니다")
    return r


# ---- 계획 대 실제(H3 · DS-12) ----------------------------------------------------------------------------------------------
def events_check(rep_dir):
    rep_dir = Path(rep_dir)
    plan = json.loads((rep_dir / "plan.json").read_text())
    rows = []
    bad_lines = 0
    p = rep_dir / "events.jsonl"
    if p.exists():
        for line in p.read_text().splitlines():
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                bad_lines += 1
    ev = [e for e in rows if e.get("kind", "plan") == "plan"]
    want = [(e["step"], e["op"], e.get("slot")) for e in plan["planned_events"]]
    got = [(e.get("step"), e.get("op"), e.get("slot")) for e in ev]
    ok_got = [(e.get("step"), e.get("op"), e.get("slot")) for e in ev if e.get("result") == "ok"]
    reasons = []
    if bad_lines:
        reasons.append(f"events.jsonl 깨진 줄 {bad_lines}")
    missing = [w for w in want if w not in ok_got]
    extra = [g for g in got if g not in want]
    # 순서: 성공 사건의 순서가 계획 순서의 부분열인가
    order_ok = True
    it = iter(want)
    for g in ok_got:
        for w in it:
            if w == g:
                break
        else:
            order_ok = False
            break
    if missing:
        reasons.append(f"빠진 사건 {len(missing)}: {missing[:4]}")
    if extra:
        reasons.append(f"계획에 없는 사건 {len(extra)}: {extra[:4]}")
    if not order_ok:
        reasons.append("순서 뒤바뀜")
    end = rows[-1] if rows and rows[-1].get("kind") == "end-state" else next((r for r in reversed(rows) if r.get("kind") == "end-state"), None)
    if end is None:
        reasons.append("끝 상태 행 없음")
    else:
        for k in ("alive", "lb"):
            if sorted(end.get(k) or []) != sorted(plan["end_state"][k]):
                reasons.append(f"끝 상태 {k} {end.get(k)} ≠ 계획 {plan['end_state'][k]}")
    out = {"ok": not reasons, "reasons": reasons, "planned": len(want), "got": len(got), "ok_events": len(ok_got),
           "missing": missing, "extra": extra, "order_ok": order_ok}
    (rep_dir / "events-check.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    return out


def main(argv):
    if len(argv) >= 4 and argv[1] == "--render":
        print(json.dumps(render(int(argv[2]), argv[3]), ensure_ascii=False))
        return 0
    if len(argv) >= 2 and argv[1] == "--probe-dry-run":
        opt = lambda k: float(argv[argv.index(k) + 1]) if k in argv else 0.0
        su, co, sp = opt("--startup-s"), opt("--collect-s"), opt("--stop-s")
        total, rows = probe_dry_run(su, co, sp)
        if "--json" in argv:
            print(json.dumps({"startup_s": su, "boundary_total": total, "rows": rows}, ensure_ascii=False))
        else:
            print(f"# 탐침 일정 dry-run(§4.9-20 · §5.8-1) — 기동 → UP {su:g}초 · 수집 {co:g}초 · 내림 {sp:g}초 · 판정 여유 {PROBE_DECIDE_MARGIN_S}초")
            print("부하 | 절차 | 경계 탐침 | 칸별(old/trans/new) 구간 배정 | 경계로 빠진 탐침 | 가장 짧은 칸 끝 여유(초)")
            for r in rows:
                cells = " · ".join(f"{c}:{dict(sorted(v['in'].items()))}" for c, v in r["cells"].items())
                lost = " ".join(f"{c}:{','.join(v['lost'])}" for c, v in r["cells"].items() if v["lost"]) or "-"
                print(f"{r['load']} | {r['proc']} | {r['boundary']} | {cells} | {lost} | {r['min_slack_s']:.0f}")
            print(f"경계 탐침 합계 = {total}")
        return 0 if total == 0 else 1
    if len(argv) == 4 and argv[1] == "--config-check":
        try:
            d = json.loads(Path(argv[3]).read_text())
        except (OSError, json.JSONDecodeError) as e:
            print(f"configprops 읽기 실패: {e}")
            return 2
        props = d.get("configprops") if isinstance(d, dict) else d
        r = config_check(argv[2], props or [])
        if r is None:
            print("configprops seat.hold 없음 — 판정 불가")
            return 2
        for x in r:
            print(x)
        return 0 if not r else 1
    if len(argv) == 3 and argv[1] == "--events-check":
        out = events_check(argv[2])
        for x in out["reasons"]:
            print(x)
        return 0 if out["ok"] else 1
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
