#!/usr/bin/env python3
"""ADR-013 요약기 단위 확인(서버 없이) — 가짜 회차 폴더를 만들어 summarize.py --rep · 캠페인 판정 · --select를 돌리고 기대값과 대조한다.

    selftest.py [--keep <폴더>]      exit 0 = 모든 확인 통과

확인하는 것: ① 정상 회차 → ok · 칸별 짝 수 ② 짝 제외(§5.8-2 — 단독(옛) 칸 탐침이 실패 응답이면 같은 (d, 종류) 전환 칸 탐침도 빠진다)
③ 칸별 T ≤ O(§5.8-3 — 한 칸 T > O면 P9 (자) 반증) ④ N10 결정성 위반 분류(하네스 = 실패 응답 있음 · 정합 = 그 밖) ⑤ 최소 수 미달 원인(절차 지연 = fail · 실행기 = invalid)
⑥ 고르는 규칙의 결정성(후보 순서를 바꿔도 같은 결과)과 순환 처리(순환 밖에서 빠진 후보는 되살리지 않는다) ⑦ ① 중단 구간 탐침 = '중단' · J = 단독(새) + lb 편입 뒤 탐침
⑧ N = 실제 구간 단독(새)(칸 무관) ⑨ (자) 3회 합 짝 0 칸 = 판정 불가 ⑩ 예상 밖 판정 응답 = probe-anomaly · P9 (바) ⑪ GC 로그 없음·빈 로그 = gc-log-missing
⑫ H4 수집 오류·지표 없음 = app-collect-missing ⑬ (마) 응답 섞인 탐침은 두 수에서 뺀다 ⑭ P7 이어 읽기 끝 = page_full 지표 + 행 수 ⑮ 끝 검사 앱 ≠ 계획 = final-app-mismatch
(코드 리뷰 루프 1 CR13-1~14 · 참고 — ⑦의 J · ①의 J 수는 CR13-1 정정 뒤 값)."""
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
             old_fail=None, anomaly=None, l_hold2_fail=None, gc_bad=None, collect_errors=None, no_actuator=None, page_size=100000,
             inc_pairs=None, page_full=None, final_app=None):
    """verdicts[(cell, d, kind)] = 'right'|'wrong' · old_fail = {(d, kind)}: 단독(옛) 칸 탐침을 실패 응답(코드 0)으로.
    anomaly = {(cell, d, kind): (code, body)}: 판정 요청을 201·409지만 맞음·틀림 모양이 아닌 응답으로 · l_hold2_fail = {(cell, d)}: L 선점 2 = 코드 0 · 선점 3 = 201(CR13-11)
    gc_bad = {생애: 'missing'|'empty'} · collect_errors = {생애: [..]} · no_actuator = {생애} · inc_pairs = 첫 증분 틱부터 pairs_scanned 목록 ·
    page_full = 새 앱 생애 page_full COUNT · final_app = final-app.json의 끝 검사 앱(계획과 다르게)."""
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
        det = {"ip": f"10.0.0.{3 + cnt[e['slot']]}"} if e["op"] == "lb-add" else ({"errors": (collect_errors or {}).get(life, [])} if e["op"] == "collect" else None)
        rows.append({"kind": "plan", "step": e["step"], "op": e["op"], "slot": e.get("slot"), "life": life, "role": roles.get(e.get("slot")) if e.get("slot") else "",
                     "start_pc": T0 + e["t0"], "end_pc": T0 + e["t1"] + 0.01, "result": "ok", "detail": det})
        if life:
            lives[life] = roles[e["slot"]]
    end = plan["end_state"]
    rows.append({"kind": "end-state", "alive": end["alive"], "lb": end["lb"]})
    for sl in end["alive"]:   # 회차 끝 살아 있는 앱 수집(run.sh evx final-collect)
        life = f"{sl}{cnt[sl]}"
        rows.append({"kind": "final-collect", "op": sl, "t_pc": T0 + k6_s + plan["timeline"]["converge_s"] + 5,
                     "detail": {"life": life, "errors": (collect_errors or {}).get(life, [])}})
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
    i = 0
    while t < k6_s:
        pairs = inc_pairs[i] if inc_pairs and i < len(inc_pairs) else (200 if i == 0 else 50)
        runs.append({"source": "inc-app-trg-1s", "started_at": iso(t), "finished_at": iso(t + 0.05), "pairs_scanned": pairs})
        i += 1
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
        an = (anomaly or {}).get((cell, p["d"], p["kind"]))
        if p["kind"] == "H":
            c2 = 0 if fail_dec else (201 if v == "right" else 409)
            reqs.append({"name": "hold2", "sent_pc": T0 + dec - 0.02, "done_pc": T0 + dec, "code": c2, "body_code": "OK" if c2 == 201 else ("HOLD_LIMIT_EXCEEDED" if c2 == 409 else None)})
        else:
            h2f = l_hold2_fail and (cell, p["d"]) in l_hold2_fail
            reqs.append({"name": "hold2", "sent_pc": T0 + dec - 0.05, "done_pc": T0 + dec - 0.03, "code": 0 if h2f else 201, "body_code": None if h2f else "OK"})
            c3 = 0 if fail_dec else (201 if h2f else (409 if v == "right" else 201))
            reqs.append({"name": "hold3", "sent_pc": T0 + dec - 0.02, "done_pc": T0 + dec, "code": c3, "body_code": "HOLD_LIMIT_EXCEEDED" if c3 == 409 else ("OK" if c3 == 201 else None)})
        if an:
            reqs[-1].update(code=an[0], body_code=an[1])
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
    (d / "final-app.json").write_text(json.dumps({"final_app": final_app or end["final_new"], "planned": end["final_new"],
                                                  "end_check_start_pc": T0 + k6_s + plan["timeline"]["converge_s"]}))
    for life, role in list(lives.items()) + [("A1", "old")]:
        cp = [{"prefix": "seat.hold", "properties": {"counterDrift": {"reconcile": {"incremental": {"pageSize": page_size}}}}}]
        (d / f"app-{life}-config.json").write_text(json.dumps({"configprops": cp}))
        names = ["seat.hold.counter.drift.repair"] + (["seat.hold.counter.reconcile.incremental.page_full"] if role == "new" else [])
        metrics = {"seat.hold.counter.reconcile.incremental.page_full": {"measurements": [{"statistic": "COUNT", "value": page_full or 0}]}} if role == "new" else {}
        if life not in (no_actuator or ()):
            (d / f"app-{life}-actuator.json").write_text(json.dumps({"names": names, "configprops": cp, "metrics": metrics}))
        with gzip.open(d / f"app-{life}.log.gz", "wt") as f:
            f.write("2026-10-24 INFO started\n")
        bad = (gc_bad or {}).get(life)
        if bad != "missing":
            with gzip.open(d / f"app-{life}-gc.log.gz", "wt") as f:
                f.write("" if bad == "empty" else "[2026-10-24T00:00:00.000+0000][info][gc] Using The Z Garbage Collector\n")
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
    check("① 짝 수 O = T = 6 · J = 12(전환 6 + 단독(새) 6 — CR13-1) · N = 4 · Q = 4", pt.get("counts") == {"O": 6, "Q": 4, "T": 6, "N": 4, "N_H": 2, "N_L": 2, "J": 12}, str(pt.get("counts")))
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
    out, st = make_rep(root / "c7", 1, "S4c", 1, startup=70.0)   # 전환 칸 첫 탐침 T1 + 60초(§5.8-1 정정) — 중단이 그보다 길어야 중단 탐침이 생긴다
    pr = (out.get("probe") or {}).get("probes") or []
    outage = [x for x in pr if x["status"] == "outage"]
    check("⑦ ① 전환 칸 중 중단 구간 탐침 = 중단", len(outage) >= 1 and all(x["cell"] == "trans" for x in outage), str([(x["id"], x["status"]) for x in pr if x["cell"] == "trans"]))
    cnts = (out.get("probe_tables") or {}).get("counts", {})
    after = [x for x in pr if x["cell"] == "trans" and x["status"] == "decision" and x["interval"] == "new" and x["d"] in (5, 30, 75)]
    check("⑦ ① J = 단독(새) 칸 6 + 전환 칸 중 lb 편입 뒤 판정 탐침(§5.4 '① 6 이상' — CR13-1) · 회차 ok",
          st == "ok" and len(after) >= 1 and cnts.get("J") == 6 + len(after), f"{st} {cnts} lb 편입 뒤 {[x['id'] for x in after]}")
    n_exp = [x for x in pr if x["status"] == "decision" and x["interval"] == "new" and x["d"] in (30, 75)]
    check("⑧ N = 실제 구간 단독(새) 판정 탐침(칸 무관 — CR13-9) · 전환 칸 lb 편입 뒤 d ≥ 30 탐침이 든다",
          cnts.get("N") == len(n_exp) and any(x["cell"] == "trans" for x in n_exp), f"{cnts} 기대 {[(x['id'], x['cell']) for x in n_exp]}")
    check("⑦ ① 중단 구간이 정지 초로 보인다(D1 > 0)", ((out.get("k6") or {}).get("D1") or 0) > 0, str((out.get("k6") or {}).get("D1")))
    # ⑨ (자) 6칸 고정 — 3회 모두 단독(옛) 75H 실패 응답 → 75H 짝 0 → 판정 불가(CR13-2)
    camp2 = root / "camp2"
    for r in (1, 2, 3):
        make_rep(camp2, 3, "S4c", r, old_fail={(75, "H")})
        make_rep(camp2, 3, "S3e", r)
    res2 = summarize.campaign(camp2)
    c2 = res2["cells"]["S4c-p3"]
    check("⑨ (자) 3회 합 짝 0인 칸(75H) = 판정 불가 → P9 항목 · 성공 아님",
          isinstance(c2["P9"], list) and any("판정 불가" in x and "(자)" in x and "75H" in x for x in c2["P9"]) and not res2["success"][3]["success"]
          and c2["T_vs_O"].get("75H") == [0, 0, 0], f"{c2['P9']} {c2.get('T_vs_O')}")
    # ⑩ 예상 밖 판정 응답(201·409인데 모양이 아님) → 회차 flags · P9 (바)(CR13-3)
    out, st = make_rep(root / "c10", 3, "S4c", 1, anomaly={("trans", 30, "H"): (409, "SEAT_NOT_AVAILABLE")})
    check("⑩ 예상 밖 판정 응답 → probe-anomaly 기록(회차 유효)", st == "ok" and "probe-anomaly" in (out.get("flags") or []) and len(out.get("probe_anomaly") or []) == 1,
          f"{st} {out.get('flags')} {out.get('probe_anomaly')}")
    camp3 = root / "camp3"
    for r in (1, 2, 3):
        make_rep(camp3, 3, "S4c", r, anomaly={("trans", 30, "H"): (409, "SEAT_NOT_AVAILABLE")} if r == 2 else None)
        make_rep(camp3, 3, "S3e", r)
    c3 = summarize.campaign(camp3)["cells"]["S4c-p3"]
    check("⑩ 예상 밖 판정 응답 → 캠페인 P9 (바) 항목(정합 분류)", isinstance(c3["P9"], list) and any("(바)" in x and "예상 밖" in x for x in c3["P9"]), str(c3["P9"]))
    # ⑪ GC 로그 결측·빈 로그 → gc-log-missing(CR13-4)
    out, st = make_rep(root / "c11a", 3, "S4c", 1, gc_bad={"B1": "missing"})
    check("⑪ 새 앱 GC 로그 없음 → invalid:gc-log-missing:B1", st.startswith("invalid:") and "gc-log-missing:B1" in st, st)
    out, st = make_rep(root / "c11b", 3, "S4c", 1, gc_bad={"A1": "empty"})
    check("⑪ 옛 앱 GC 로그 빈 파일 → invalid:gc-log-missing:A1", st.startswith("invalid:") and "gc-log-missing:A1" in st, st)
    # ⑫ H4 필수 산출물(CR13-5)
    out, st = make_rep(root / "c12a", 3, "S4c", 1, collect_errors={"A1": ["app-log"]})
    check("⑫ 수집 사건 detail 오류 → invalid:app-collect-missing:A1", st.startswith("invalid:") and "app-collect-missing:A1" in st, st)
    out, st = make_rep(root / "c12b", 3, "S4c", 1, no_actuator={"B1"})
    check("⑫ 새 앱 지표·configprops 파일 없음 → invalid:app-collect-missing:B1(actuator)", st.startswith("invalid:") and "app-collect-missing:B1:actuator" in st, st)
    # ⑬ (마) 응답 섞인 탐침은 두 수에서 빠지고 따로(CR13-11)
    out, st = make_rep(root / "c13", 3, "S4c", 1, l_hold2_fail={("trans", 30)})
    pt = out.get("probe_tables") or {}
    check("⑬ L 선점 2 코드 0 · 선점 3 201 탐침 → (마) 결과 수에서 빠짐 · unclean 기록", pt.get("L_over_results") == pt.get("snap_over_limit") == 0
          and [u["id"] for u in pt.get("unclean_over") or []] == [x["id"] for x in out["probe"]["probes"] if x["cell"] == "trans" and x["d"] == 30 and x["type"] == "L"],
          f"{pt.get('L_over_results')} {pt.get('snap_over_limit')} {pt.get('unclean_over')}")
    # ⑭ P7 이어 읽기 끝 = page_full 지표 + 행 수(CR13-참고) — 되돌림 재시도로 pairs_scanned ≥ page-size인 안 찬 틱
    out, st = make_rep(root / "c14", 3, "S4c", 1, page_size=100, inc_pairs=[100, 120, 50], page_full=0)
    check("⑭ page_full 0이면 첫 틱 pairs_scanned ≥ page-size여도 이어 읽기 = 1틱", (out.get("P7") or {}).get("carry_ticks") == 1, str(out.get("P7")))
    out, st = make_rep(root / "c14b", 3, "S4c", 1, page_size=100, inc_pairs=[100, 100, 50], page_full=2)
    check("⑭ page_full 2 · 두 틱 찬 페이지 → 이어 읽기 = 3틱", (out.get("P7") or {}).get("carry_ticks") == 3, str(out.get("P7")))
    # ⑮ 끝 검사 앱 ≠ 계획(CR13-참고)
    out, st = make_rep(root / "c15", 2, "S4c", 1, final_app="B")
    check("⑮ 끝 검사 앱 ≠ 계획 → fail:final-app-mismatch", st.startswith("fail:") and "final-app-mismatch" in st, st)
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
