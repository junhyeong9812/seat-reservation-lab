#!/usr/bin/env python3
"""ADR-013 요약기 단위 확인(서버 없이) — 가짜 회차 폴더를 만들어 summarize.py --rep · 캠페인 판정 · --select를 돌리고 기대값과 대조한다.

    selftest.py [--keep <폴더>]      exit 0 = 모든 확인 통과

확인하는 것: ① 정상 회차 → ok · 칸별 짝 수 ② 짝 제외(§5.8-2 — 단독(옛) 칸 탐침이 실패 응답이면 같은 (d, 종류) 전환 칸 탐침도 빠진다)
③ 칸별 T ≤ O(§5.8-3 — 한 칸 T > O면 P9 (자) 반증) ④ N10 결정성 위반 분류(하네스 = 실패 응답 있음 · 정합 = 그 밖) ⑤ 최소 수 미달 원인(절차 지연 = fail · 실행기 = invalid)
⑥ 고르는 규칙의 결정성(후보 순서를 바꿔도 같은 결과)과 순환 처리(순환 밖에서 빠진 후보는 되살리지 않는다) ⑦ ① 중단 구간 탐침 = '중단' · J는 lb 편입 뒤 탐침."""
import gzip
import json
import random
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import plan13  # noqa: E402
import summarize  # noqa: E402

T0, OFF = 1_800_000_000.0, 0.4
FAILS = []


def check(name, cond, detail=""):
    print(("ok   " if cond else "FAIL ") + name + (f" — {detail}" if detail and not cond else ""))
    if not cond:
        FAILS.append(name)


def write_csv(path, rows):
    head = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"
    with gzip.open(path, "wt") as f:
        f.write(head + "\n")
        for r in rows:
            f.write(r + "\n")


def k6_rows(k6_s, stall_from=None, stall_to=None):
    out = []
    for s in range(int(T0), int(T0 + k6_s)):
        rel = s - T0
        if stall_from is not None and stall_from <= rel < stall_to:
            continue
        for _ in range(3):
            out.append(f"http_req_duration,{s},5.0,,,,true,,POST,hold,HTTP/1.1,s4,,201,,,u,,")
            out.append(f"http_req_waiting,{s},4.0,,,,true,,POST,hold,HTTP/1.1,s4,,201,,,u,,")
    return out


def avail_rows(k6_s, fail_from=None, fail_to=None):
    out = []
    t = T0
    while t < T0 + k6_s:
        code = "502" if fail_from is not None and fail_from <= t - T0 < fail_to else "201"
        out.append(f"avail_req,{int((t + 0.01) * 1000)},1,,,,,,,,,avail,,,,,,t0={int(t * 1000)}&code={code}&dur=3,")
        t += 0.5
    return out


def make_rep(root, proc, load, rep, verdicts=None, startup=20.0, outage_fail_codes=False, nondet=None, extra_boundary=False, driver_done=True,
             old_fail=None):
    """verdicts[(cell, d, kind)] = 'right'|'wrong' · old_fail = {(d, kind)}: 단독(옛) 칸 탐침을 실패 응답(코드 0)으로."""
    d = root / load / f"p{proc}" / f"rep{rep}"
    d.mkdir(parents=True)
    plan = plan13.render(proc, load)
    (d / "plan.json").write_text(json.dumps(plan))
    (d / "t0.json").write_text(json.dumps({"t0_pc": T0, "offset_s": OFF}))
    (d / "clock-offset.json").write_text(json.dumps({"before": {"offset_s": OFF}, "after": {"offset_s": OFF}}))
    k6_s = plan["timeline"]["k6_s"]
    (d / "k6-window.json").write_text(json.dumps({"end_pc": T0 + k6_s}))
    ev, derived = plan13.planned_events_timed(proc, load, startup)
    rows = []
    lives, cnt, roles = {}, {"A": 0, "B": 0}, {"A": None, "B": None}
    # 셋업(옛 앱 A1)
    for i, op in enumerate(("start", "wait-health", "config-check", "lb-add")):
        rows.append({"kind": "plan", "step": "setup", "op": op, "slot": "A", "life": "A1", "role": "old", "start_pc": T0 - 200 + i, "end_pc": T0 - 199 + i,
                     "result": "ok", "detail": {"ip": "10.0.0.2"} if op == "lb-add" else None})
    cnt["A"], roles["A"] = 1, "old"
    for e in ev:
        if e["op"] == "start":
            cnt[e["slot"]] += 1
            roles[e["slot"]] = next(a["role"] for s in plan["steps"] if s["name"] == e["step"] for a in s["actions"] if a["op"] == "start" and a["slot"] == e["slot"])
        life = f"{e['slot']}{cnt[e['slot']]}" if e.get("slot") else None
        rows.append({"kind": "plan", "step": e["step"], "op": e["op"], "slot": e.get("slot"), "life": life, "role": roles.get(e.get("slot")) if e.get("slot") else "",
                     "start_pc": T0 + e["t0"], "end_pc": T0 + e["t1"] + 0.01, "result": "ok",
                     "detail": {"ip": f"10.0.0.{3 + cnt[e['slot']]}"} if e["op"] == "lb-add" else None})
        if life:
            lives[life] = roles[e["slot"]]
    end = plan["end_state"]
    rows.append({"kind": "end-state", "alive": end["alive"], "lb": end["lb"]})
    (d / "events.jsonl").write_text("\n".join(json.dumps(r) for r in rows) + "\n")
    plan13.events_check(d)
    iv = plan13.intervals([dict(e, t0=e["t0"], t1=e["t1"] + 0.01) for e in ev], k6_s)
    outage = (iv["trans"][0], iv["trans"][1]) if proc == 1 else (None, None)
    write_csv(d / "k6-requests.csv.gz", k6_rows(k6_s, *outage))
    write_csv(d / "k6-avail.csv.gz", avail_rows(k6_s, *outage))
    # 촘촘한 락 표본: 0.2초마다 [첫 창 시작, 끝 창 끝]
    ws = summarize.adr_windows([dict(e, result="ok") | {"t1": e["t1"] + 0.01} for e in ev], {}, plan)
    lo, hi = min(a for a, _ in ws), max(b for _, b in ws)
    ml = []
    t = lo
    while t < hi:
        ml.append(json.dumps({"t": T0 + OFF + t, "lock_wait": 0, "waiters": [], "blockers": [], "quota_locks": [], "cic": []}))
        t += 0.2
    (d / "timeline-migration-locks.jsonl").write_text("\n".join(ml) + "\n")
    # DB 기록: 옛 C(60초) — 옛 앱 내림 전 · 새 앱 증분 틱(1초) — 새 앱 UP 뒤 · restart 행
    old_stop = next(e["t0"] for e in ev if e["op"] == "stop" and e["slot"] == "A")
    new_life_up = None
    cntA = {"A": 1, "B": 0}
    for e in ev:
        if e["op"] == "start":
            cntA[e["slot"]] += 1
        if e["op"] == "wait-health":
            role = next(a["role"] for s in plan["steps"] if s["name"] == e["step"] for a in s["actions"] if a["op"] == "start" and a["slot"] == e["slot"])
            if role == "new" and new_life_up is None:
                new_life_up = e["t1"]
    iso = lambda rel: __import__("datetime").datetime.fromtimestamp(T0 + OFF + rel, __import__("datetime").timezone.utc).isoformat()
    runs = []
    t = 5.0
    while t + 10 < old_stop:
        runs.append({"source": "reconcile-app", "started_at": iso(t), "finished_at": iso(t + 10), "pairs_scanned": 1000})
        t += 60
    t = new_life_up + 1
    first = True
    while t < k6_s:
        runs.append({"source": "inc-app-trg-1s", "started_at": iso(t), "finished_at": iso(t + 0.05), "pairs_scanned": 200 if first else 50})
        first = False
        t += 1
    gaps = [{"kind": "restart", "at": iso(new_life_up), "to_at": iso(new_life_up - 60), "detail": "m=..."}]
    log = []
    # 탐침
    sched = plan["probes"]
    verdicts = verdicts or {}
    plines = []
    for p in sched:
        inj = p["at_s"] + 0.4
        dec = inj + p["d"] + 0.3
        cell = p["cell"]
        reqs = [{"name": "hold1", "sent_pc": T0 + p["at_s"], "done_pc": T0 + p["at_s"] + 0.01, "code": 201, "body_code": "OK"}]
        v = verdicts.get((cell, p["d"], p["kind"]), "right")
        in_outage = proc == 1 and outage[0] is not None and outage[0] <= p["at_s"] < outage[1]
        if in_outage:
            reqs = [{"name": "hold1", "sent_pc": T0 + p["at_s"], "done_pc": T0 + p["at_s"] + 0.01, "code": 502, "body_code": None}]
            rec = {"kind": "probe", "id": p["id"], "cell": cell, "type": p["kind"], "d": p["d"], "requests": reqs, "inject": None, "snaps": [], "errors": ["hold1 502 None"]}
            plines.append(json.dumps(rec))
            continue
        fail_dec = old_fail and cell == "old" and (p["d"], p["kind"]) in old_fail
        if p["kind"] == "H":
            c2 = 0 if fail_dec else (201 if v == "right" else 409)
            reqs.append({"name": "hold2", "sent_pc": T0 + dec - 0.02, "done_pc": T0 + dec, "code": c2, "body_code": "OK" if c2 == 201 else ("HOLD_LIMIT_EXCEEDED" if c2 == 409 else None)})
        else:
            reqs.append({"name": "hold2", "sent_pc": T0 + dec - 0.05, "done_pc": T0 + dec - 0.03, "code": 201, "body_code": "OK"})
            c3 = 0 if fail_dec else (409 if v == "right" else 201)
            reqs.append({"name": "hold3", "sent_pc": T0 + dec - 0.02, "done_pc": T0 + dec, "code": c3, "body_code": "HOLD_LIMIT_EXCEEDED" if c3 == 409 else ("OK" if c3 == 201 else None)})
        snaps = []
        cum = 0
        for q in reqs:
            if q["code"] == 201:
                cum += 1
            if q["name"] in ("hold2", "hold3"):
                act = cum
                if nondet and nondet.get("id") == p["id"] and q["name"] == nondet.get("after"):
                    act = cum + nondet.get("delta", 1)
                snaps.append({"after": q["name"], "t_db": T0 + OFF + dec, "cnt": 1, "holds": act, "confirmed": 0,
                              "hold_rows": [{"id": 1, "held_at_db": T0 + OFF + p["at_s"] + 0.005}]})
        rec = {"kind": "probe", "id": p["id"], "cell": cell, "type": p["kind"], "d": p["d"], "requests": reqs,
               "inject": {"at_db": T0 + OFF + inj, "rc": 0}, "snaps": snaps, "errors": []}
        plines.append(json.dumps(rec))
        log.append({"action": "repaired", "schedule_id": p["schedule_id"], "user_id": p["user_id"], "at": iso(inj + 2), "source": "inc-app-trg-1s"})
    if driver_done:
        plines.append(json.dumps({"kind": "done", "planned": len(sched), "finished": len(sched)}))
    (d / "probe.jsonl").write_text("\n".join(plines) + "\n")
    (d / "drift-db.json").write_text(json.dumps({"reconcile_runs": runs, "inc_gaps": gaps, "drift_log": log}))
    ref = [{"version": "9", "checksum": 11, "success": True}, {"version": "10", "checksum": 22, "success": True}]
    (d / "ref-history-all.json").write_text(json.dumps(ref))
    (d / "end-check.json").write_text(json.dumps({"mismatch_all": 0, "over_limit_main": 0, "over_limit_probe": sum(1 for (c, dd, k), v in verdicts.items() if k == "L" and v == "wrong") if load == "S4c" else 0,
                                                  "updated_at_null": 0, "m": '{"2026-10-24 00:00:00+00"}', "trigger": {"enabled": "O"}, "index": {"valid": True, "ready": True},
                                                  "flyway": [dict(r, success=True) for r in ref]}))
    (d / "consistency.json").write_text(json.dumps({"v_counter_mismatch": 0}))
    (d / "final-app.json").write_text(json.dumps({"final_app": end["final_new"], "end_check_start_pc": T0 + k6_s + plan["timeline"]["converge_s"]}))
    for life, role in list(lives.items()) + [("A1", "old")]:
        (d / f"app-{life}-config.json").write_text(json.dumps({"configprops": [{"prefix": "seat.hold", "properties": {"counterDrift": {"reconcile": {"incremental": {"pageSize": 100000}}}}}]}))
        if role == "shadow":
            (d / f"app-{life}-actuator.json").write_text(json.dumps({"names": ["seat.hold.counter.drift.repair"]}))
    with gzip.open(d / "app-A1.log.gz", "wt") as f:
        f.write("2026-10-24 INFO started\n")
    if load == "S3e":
        (d / "injected.json").write_text(json.dumps({"plan": {"rounds": [{"at_s": 270}, {"at_s": 450}, {"at_s": 630}]}, "rounds": [
            {"at_s": a, "ok": True, "at_epoch": T0 + OFF + a, "pairs": [{"kind": "H", "schedule_id": 1, "user_id": 300000 + a}]} for a in (270, 450, 630)]}))
        (d / "end-pairs.json").write_text(json.dumps({"injected": [{"cnt": 1, "actual": 1}] * 3}))
        dl = json.loads((d / "drift-db.json").read_text())
        for a in (270, 450, 630):
            dl["drift_log"].append({"action": "repaired", "schedule_id": 1, "user_id": 300000 + a, "at": iso(a + 3), "source": "inc-app-trg-1s"})
        (d / "drift-db.json").write_text(json.dumps(dl))
    (d / "harness-reasons.txt").write_text("")
    out, st = summarize.rep_summary(d)
    (d / "status").write_text(st)
    (d / "meta.json").write_text(json.dumps({"rerun": False}))
    return out, st


def main():
    keep = sys.argv[sys.argv.index("--keep") + 1] if "--keep" in sys.argv else None
    root = Path(keep) if keep else Path(tempfile.mkdtemp())
    if keep and root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True, exist_ok=True)
    # ① 정상 회차
    out, st = make_rep(root / "c1", 3, "S4c", 1)
    check("① 정상 회차 → ok", st == "ok", f"{st} {out.get('harness_reasons')} {out.get('procedure_reasons')}")
    pt = out.get("probe_tables") or {}
    check("① 짝 수 O = T = 6 · J = 6 · N = 4 · Q = 4", pt.get("counts") == {"O": 6, "Q": 4, "T": 6, "N": 4, "N_H": 2, "N_L": 2, "J": 6}, str(pt.get("counts")))
    check("① D1 = 0(정지 초 없음)", (out.get("k6") or {}).get("D1") == 0, str((out.get("k6") or {}).get("D1")))
    # ② 짝 제외
    out, st = make_rep(root / "c2", 3, "S4c", 1, old_fail={(30, "H")})
    c = (out.get("probe_tables") or {}).get("cells", {})
    check("② 짝 제외: 단독(옛) 30H 실패 응답 → 30H 짝 없음 · T 수 5", not c.get("30H", {}).get("pair_T") and out["probe_tables"]["counts"]["T"] == 5, str(c.get("30H")))
    # ③ 칸별 T ≤ O — 캠페인(절차 3 · S4c 3회): rep1에서 전환 칸 75L만 틀림 → 한 칸 T(1) > O(0) → (자) 반증
    camp = root / "camp"
    for r in (1, 2, 3):
        make_rep(camp, 3, "S4c", r, verdicts={("trans", 75, "L"): "wrong"} if r == 1 else
                 {("old", 5, "H"): "wrong", ("trans", 5, "H"): "wrong"})
        make_rep(camp, 3, "S3e", r)
    res = summarize.campaign(camp)
    cell = res["cells"]["S4c-p3"]
    check("③ 칸별 T ≤ O: 75L에서 T 1 > O 0 → P9 (자) 반증", isinstance(cell["P9"], list) and any("(자)" in x and "75L" in x for x in cell["P9"]), str(cell["P9"]))
    check("③ 같은 칸 T = O(5H 2/2)는 반증 아님", cell["T_vs_O"].get("5H", [0, 0])[0] == cell["T_vs_O"].get("5H", [0, 0])[1] == 2, str(cell["T_vs_O"].get("5H")))
    # ④ N10 결정성 분류
    out, st = make_rep(root / "c4a", 2, "S3e", 1, nondet={"id": 2, "after": "hold2", "delta": 1})
    check("④ 결정성 위반(실패 응답 없음 · 탐침 밖 홀드) → 정합 분류 · 회차 ok(P9 (바) 자료)", st == "ok" and out.get("probe_nondet_integrity") == 1, f"{st} {out.get('probe', {}).get('nondet')}")
    out, st = make_rep(root / "c4b", 2, "S3e", 1, nondet={"id": 2, "after": "hold2", "delta": 1}, old_fail={(30, "H")})
    nd = (out.get("probe") or {}).get("nondet") or []
    check("④ 결정성 위반 + 그 탐침에 201·409 밖 응답 → 하네스 분류 → invalid:probe-nondeterministic",
          "probe-nondeterministic" in st and nd and nd[0]["items"][0]["class"] == "harness", f"{st} {nd}")
    # ⑤ 최소 수 미달 원인
    allfail = {(d, k) for d in (5, 30, 75) for k in "HL"} - {(5, "H"), (5, "L")}
    out, st = make_rep(root / "c5a", 4, "S4c", 1, old_fail=allfail)
    check("⑤ 최소 수 미달(실행기 정상) → fail:probe-short-by-procedure", st.startswith("fail:") and "probe-short-by-procedure" in st, st)
    out, st = make_rep(root / "c5b", 4, "S4c", 1, old_fail=allfail, driver_done=False)
    check("⑤ 최소 수 미달 + 실행기 끝 행 없음 → invalid:probe-short", st.startswith("invalid:") and "probe-short" in st, st)
    # ⑦ ① 중단 탐침
    out, st = make_rep(root / "c7", 1, "S4c", 1, startup=45.0)
    pr = (out.get("probe") or {}).get("probes") or []
    outage = [x for x in pr if x["status"] == "outage"]
    check("⑦ ① 전환 칸 중 중단 구간 탐침 = 중단", len(outage) >= 1 and all(x["cell"] == "trans" for x in outage), str([(x["id"], x["status"]) for x in pr if x["cell"] == "trans"]))
    cells = (out.get("probe_tables") or {}).get("cells", {})
    check("⑦ ① J 짝 = 6(전환 칸 lb 편입 뒤 + 나머지는 단독(새) 칸) · 회차 ok", (out.get("probe_tables") or {}).get("counts", {}).get("J") == 6 and st == "ok"
          and any(c.get("X_cell") == "trans" for c in cells.values()) and any(c.get("X_cell") == "new" for c in cells.values()), f"{st} {(out.get('probe_tables') or {}).get('counts')}")
    check("⑦ ① 중단 구간이 정지 초로 보인다(D1 > 0)", ((out.get("k6") or {}).get("D1") or 0) > 0, str((out.get("k6") or {}).get("D1")))
    # ⑥ 고르는 규칙 — 결정성 · 순환
    vals = {1: {"J1": [5, 5], "J2": [5, 5], "J3": [5, 5], "D1": [0], "D2": [0], "D3": [0]},
            2: {"J1": [0, 0], "J2": [0, 1], "J3": [1, 1], "D1": [1, 2], "D2": [0], "D3": [0]},
            3: {"J1": [1, 1], "J2": [0, 0], "J3": [0, 1], "D1": [1, 2], "D2": [0], "D3": [0]},
            4: {"J1": [0, 1], "J2": [1, 1], "J3": [0, 0], "D1": [1, 2], "D2": [0], "D3": [0]}}
    kfix = {p: plan13.K_FIXED[p] for p in range(1, 6)}
    picks = set()
    for _ in range(6):
        c = [1, 2, 3, 4]
        random.shuffle(c)
        pick, trace = summarize.select(c, vals, kfix)
        picks.add(pick)
    check("⑥ 결정성(후보 순서 무관)", len(picks) == 1, str(picks))
    pick, trace = summarize.select([1, 2, 3, 4], vals, kfix)
    t1 = trace[0]
    check("⑥ 순환(2→3→4→2) — 순환 밖 1은 빠지고 2·3·4가 남는다", t1["cycle"] and sorted(t1["out"]) == [2, 3, 4], str(t1))
    check("⑥ 남은 2·3·4 동률(D1 겹침) → 단계 수 K 최소 = ③", pick == 3, str(pick))
    vals2 = {2: {"J1": [0, 0.1], "J2": [0], "J3": [3, 4], "D1": [0], "D2": [0], "D3": [0]}, 5: {"J1": [0.2, 0.3], "J2": [0], "J3": [3, 5], "D1": [0], "D2": [0], "D3": [0]}}
    pick, trace = summarize.select([5, 2], vals2, kfix)
    check("⑥ 정합 단에서 범위 비겹침으로 낮은 쪽(②)을 고른다", pick == 2 and trace[0]["out"] == [2], str(trace))
    print(f"\n{len(FAILS)} 실패 — 가짜 회차 폴더: {root}")
    if not keep:
        shutil.rmtree(root, ignore_errors=True)
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
