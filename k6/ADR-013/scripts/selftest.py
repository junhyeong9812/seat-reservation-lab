#!/usr/bin/env python3
"""ADR-013 요약기 단위 확인(서버 없이) — 가짜 회차 폴더를 만들어 summarize.py --rep · 캠페인 판정 · --select를 돌리고 기대값과 대조한다.

    selftest.py [--keep <폴더>]      exit 0 = 모든 확인 통과

확인하는 것: ① 정상 회차 → ok · 칸별 짝 수 ② 짝 제외(§5.8-2 — 단독(옛) 칸 탐침이 실패 응답이면 같은 (d, 종류) 전환 칸 탐침도 빠진다)
③ 칸별 T ≤ O(§5.8-3 — 한 칸 T > O면 P9 (자) 반증) ④ N10 결정성 위반 분류(하네스 = 실패 응답 있음 · 정합 = 그 밖) ⑤ 최소 수 미달 원인(절차 지연 = fail · 실행기 = invalid)
⑥ 고르는 규칙의 결정성(후보 순서를 바꿔도 같은 결과)과 순환 처리(순환 밖에서 빠진 후보는 되살리지 않는다) ⑦ ① 중단 구간 탐침 = '중단' · J = 단독(새) + lb 편입 뒤 탐침
⑧ N = 실제 구간 단독(새)(칸 무관) ⑨ (자) 3회 합 짝 0 칸 = 판정 불가 ⑩ 예상 밖 판정 응답 = probe-anomaly · P9 (바) ⑪ GC 로그 없음·빈 로그 = gc-log-missing
⑫ H4 수집 오류·지표 없음 = app-collect-missing ⑬ (마) 응답 섞인 탐침은 두 수에서 뺀다 ⑭ P7 이어 읽기 끝 = page_full 지표 + 행 수 ⑮ 끝 검사 앱 ≠ 계획 = final-app-mismatch
⑯ 실행기 끝 행 없음·기록 < 계획 = probe-incomplete(최소 수 충족이어도 — CR13-15) ⑰ 전환 실패(절차) 뒤 산출물 결측은 하네스로 묻지 않음 · 앞의 하네스 사유는 invalid(CR13-18)
⑱ J 모집단 = 전환 칸만 · ① J = 0(§5.8-3a — CR13-19) ⑲ 앱 끝 검사 v_counter_mismatch = P9 (다)(CR13-23) ⑳ m 시각 오프셋 해석(분 오프셋 · 못 읽음 = m-parse-failed — CR13-28)
㉑ 시작 조건 ⑤ 스모크 사건 길이 = events.jsonl op별 최대(모든 op — 기동 시작 길이도 dry-run에 든다 · CR13-20) ㉒ 단계 3 statement_timeout = max(⌈실측 × 3⌉, 600)(CR13-17)
㉓ 새 앱 incremental.pageSize = 100,000(CR13-27)
㉔ lb 아티팩트(§5.8-3b ⓐ — 창 안 코드 0은 k6·탐침 집계·D1·D2에서 빠지고 창 밖 코드 0·5xx는 남는다 · 효력 없음 = lb-artifact-window-missing) · 구성원 렌더 max_fails=0(ⓑ)
(코드 리뷰 루프 1 CR13-1~14 · 참고 · 루프 2 CR13-15~28 — ①·⑦의 J는 §5.8-3a 정정 뒤 값)."""
import gzip
import json
import math
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


def k6_rows(k6_s, stall_from=None, stall_to=None, override=None):
    """override = {회차 기준 정수 초: [(이름, 상태), ...]} — 그 초의 행을 이것으로 바꾼다(lb 아티팩트 경우)."""
    out = []
    for s in range(int(T0), int(T0 + k6_s)):
        rel = s - T0
        if override and rel in override:
            for name, st in override[rel]:
                out.append(f"http_req_duration,{s},5.0,,,,true,,POST,{name},HTTP/1.1,s4,,{st},,,u,,")
            continue
        if stall_from is not None and stall_from <= rel < stall_to:
            continue
        for _ in range(3):
            out.append(f"http_req_duration,{s},5.0,,,,true,,POST,hold,HTTP/1.1,s4,,201,,,u,,")
            out.append(f"http_req_waiting,{s},4.0,,,,true,,POST,hold,HTTP/1.1,s4,,201,,,u,,")
    return out


def avail_rows(k6_s, fail_from=None, fail_to=None, extra=None):
    """extra = [(받은 회차 기준 초, 코드), ...] — 더 넣는 탐침 행(lb 아티팩트 경우)."""
    out = [f"avail_req,{int((T0 + t) * 1000)},1,,,,,,,,,avail,,,,,,t0={int((T0 + t - 0.01) * 1000)}&code={c}&dur=10," for t, c in (extra or [])]
    t = T0
    while t < T0 + k6_s:
        code = "502" if fail_from is not None and fail_from <= t - T0 < fail_to else "201"
        out.append(f"avail_req,{int((t + 0.01) * 1000)},1,,,,,,,,,avail,,,,,,t0={int(t * 1000)}&code={code}&dur=3,")
        t += 0.5
    return out


def make_rep(root, proc, load, rep, verdicts=None, startup=20.0, outage_fail_codes=False, nondet=None, extra_boundary=False, driver_done=True,
             old_fail=None, anomaly=None, l_hold2_fail=None, gc_bad=None, collect_errors=None, no_actuator=None, page_size=100000,
             inc_pairs=None, page_full=None, final_app=None, lb_eff=True, lb_art=False):
    """verdicts[(cell, d, kind)] = 'right'|'wrong' · old_fail = {(d, kind)}: 단독(옛) 칸 탐침을 실패 응답(코드 0)으로.
    anomaly = {(cell, d, kind): (code, body)}: 판정 요청을 201·409지만 맞음·틀림 모양이 아닌 응답으로 · l_hold2_fail = {(cell, d)}: L 선점 2 = 코드 0 · 선점 3 = 201(CR13-11)
    gc_bad = {생애: 'missing'|'empty'} · collect_errors = {생애: [..]} · no_actuator = {생애} · inc_pairs = 첫 증분 틱부터 pairs_scanned 목록 ·
    page_full = 새 앱 생애 page_full COUNT · final_app = final-app.json의 끝 검사 앱(계획과 다르게) ·
    lb_eff = False: lb 사건 detail에 효력 시각 없음(§5.8-3b ⓐ 창 계산 불가) · lb_art = True: 첫 lb-remove 창 안 코드 0(k6 한 초 전부 · 탐침 2) ·
    창 밖 코드 0·5xx(k6 30초 앞 초 + 그다음 초는 코드 0만 · 탐침 코드 0 1 · 503 1)."""
    d = root / load / f"p{proc}" / f"rep{rep}"
    d.mkdir(parents=True)
    plan = plan13.render(proc, load)
    (d / "plan.json").write_text(json.dumps(plan))
    (d / "t0.json").write_text(json.dumps({"t0_pc": T0, "offset_s": OFF}))
    (d / "clock-offset.json").write_text(json.dumps({"before": {"offset_s": OFF}, "after": {"offset_s": OFF}}))
    k6_s = plan["timeline"]["k6_s"]
    (d / "k6-window.json").write_text(json.dumps({"end_pc": T0 + k6_s}))
    ev, derived = plan13.planned_events_timed(proc, load, {"wait-health": startup, "drain": float(plan13.DRAIN_S)} if startup else None)
    rows = []
    lives, cnt, roles = {}, {"A": 0, "B": 0}, {"A": None, "B": None}
    # 셋업(옛 앱 A1)
    for i, op in enumerate(("start", "wait-health", "config-check", "lb-add")):
        rows.append({"kind": "plan", "step": "setup", "op": op, "slot": "A", "life": "A1", "role": "old", "start_pc": T0 - 200 + i, "end_pc": T0 - 199 + i,
                     "dur_s": 1.0, "result": "ok", "detail": {"ip": "10.0.0.2"} if op == "lb-add" else None})
    cnt["A"], roles["A"] = 1, "old"
    for e in ev:
        if e["op"] == "start":
            cnt[e["slot"]] += 1
            roles[e["slot"]] = next(a["role"] for s in plan["steps"] if s["name"] == e["step"] for a in s["actions"] if a["op"] == "start" and a["slot"] == e["slot"])
        life = f"{e['slot']}{cnt[e['slot']]}" if e.get("slot") else None
        det = {"ip": f"10.0.0.{3 + cnt[e['slot']]}"} if e["op"] == "lb-add" else ({"errors": (collect_errors or {}).get(life, [])} if e["op"] == "collect" else None)
        if e["op"] in ("lb-add", "lb-remove"):   # lib13 lb_members 출력 모양(서버 시각) — 효력 = 사건 끝 바로 앞
            det = dict(det or {}, test_rc=0, reload_rc=0, reload_sent=T0 + OFF + (e["t0"] + e["t1"]) / 2, effective=(T0 + OFF + e["t1"]) if lb_eff else None)
        rows.append({"kind": "plan", "step": e["step"], "op": e["op"], "slot": e.get("slot"), "life": life, "role": roles.get(e.get("slot")) if e.get("slot") else "",
                     "start_pc": T0 + e["t0"], "end_pc": T0 + e["t1"] + 0.01, "dur_s": e["t1"] - e["t0"] + 0.01, "result": "ok", "detail": det})
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
    ov, aex = None, None
    if lb_art:
        rm = next(e for e in ev if e["op"] == "lb-remove")
        a, b = rm["t0"], rm["t1"] + 1.0   # 창 [사건 시작, 효력 + 1초]
        s_in = int(math.ceil(a)) if math.ceil(a) + 1 <= b else int(math.floor(a))
        s_out = int(math.floor(a)) - 30
        ov = {s_in: [("hold", "0")] * 3, s_out: [("hold", "201")] * 3 + [("hold", "0")] * 2 + [("hold", "503")], s_out + 1: [("hold", "0")] * 2}
        aex = [(a + 0.3, "0"), (a + 0.4, "0"), (s_out + 0.5, "0"), (s_out + 0.6, "503")]
    write_csv(d / "k6-requests.csv.gz", k6_rows(k6_s, *outage, override=ov))
    write_csv(d / "k6-avail.csv.gz", avail_rows(k6_s, *outage, extra=aex))
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
    check("① 짝 수 O = T = 6 · J = 6(전환 칸만 — §5.8-3a · CR13-19) · N = 4 · Q = 4", pt.get("counts") == {"O": 6, "Q": 4, "T": 6, "N": 4, "N_H": 2, "N_L": 2, "J": 6}, str(pt.get("counts")))
    check("① D1 = 0(정지 초 없음)", (out.get("k6") or {}).get("D1") == 0, str((out.get("k6") or {}).get("D1")))
    lba = out.get("lb_artifact") or {}
    n_lb = sum(1 for e in plan13.planned_events_timed(3, "S4c", {"wait-health": 20.0, "drain": float(plan13.DRAIN_S)})[0] if e["op"] in ("lb-add", "lb-remove"))
    check("① lb 아티팩트(§5.8-3b ⓐ): 사건마다 창 · 코드 0 없음 → 합계 0", lba.get("total") == 0 and len(lba.get("by_event") or []) == n_lb, str(lba))
    # ㉔ lb 아티팩트(§5.8-3b ⓐ) — 창 안 코드 0은 빠지고 창 밖 코드 0·5xx는 남는다 · 효력 없음 = 하네스 사유 · max_fails=0 렌더
    out, st = make_rep(root / "c24a", 3, "S4c", 1, lb_art=True)
    k, av, lba = out.get("k6") or {}, out.get("avail") or {}, out.get("lb_artifact") or {}
    bi = k.get("by_interval") or {}
    check("㉔ 회차 ok(창 밖 5xx는 기준 창 밖)", st == "ok", st)
    check("㉔ k6: 창 안 코드 0 3건 = lb 아티팩트 · 창 밖 코드 0 4건은 code0에 남음", (k.get("lb_artifact") or {}).get("n") == 3
          and sum((bi.get(n) or {}).get("code0") or 0 for n in ("old", "trans", "new")) == 4, f"{k.get('lb_artifact')} {[(n, (bi.get(n) or {}).get('code0')) for n in bi]}")
    check("㉔ k6: 창 밖 503 1건은 5xx에 남음", sum((bi.get(n) or {}).get("e5xx") or 0 for n in ("old", "trans", "new")) == 1, str([(n, (bi.get(n) or {}).get("e5xx")) for n in bi]))
    check("㉔ D1: 창 안 '코드 0뿐인 초' = 아티팩트 초로 빠짐 · 창 밖 '코드 0뿐인 초'는 남음 → D1 1 · D1_raw 2",
          k.get("D1") == 1 and k.get("D1_raw") == 2 and len((k.get("lb_artifact") or {}).get("stall_secs") or []) == 1, f"D1={k.get('D1')} raw={k.get('D1_raw')} {k.get('lb_artifact')}")
    check("㉔ 탐침: 창 안 코드 0 2건 = lb 아티팩트 · 창 밖 코드 0·503 → D2 2(D2_raw 4)",
          (av.get("lb_artifact") or {}).get("n") == 2 and av.get("D2") == 2 and av.get("D2_raw") == 4 and av.get("fail") == 2, f"{av.get('lb_artifact')} D2={av.get('D2')} raw={av.get('D2_raw')}")
    check("㉔ 회차 lb_artifact 합계 = k6 3 + 탐침 2 = 5 · 사건별 한 사건에 5",
          lba.get("total") == 5 and sorted((x.get("k6") or 0) + (x.get("avail") or 0) for x in lba.get("by_event") or [])[-1] == 5, str(lba))
    out, st = make_rep(root / "c24b", 3, "S4c", 1, lb_eff=False)
    check("㉔ lb 사건 효력 시각 없음 → invalid:lb-artifact-window-missing(무음으로 빼지 않음)", st.startswith("invalid:") and "lb-artifact-window-missing" in st, st)
    stub = root / "stub9"
    stub.mkdir()
    (stub / "lib.sh").write_text("")
    r = subprocess.run(["bash", "-c", 'source "$1"; lb_members_conf app app2; echo ---; lb_members_conf; echo ---; type lb_members', "_", str(HERE / "lib13.sh")],
                       capture_output=True, text=True, env={"LIB9_DIR": str(stub), "PATH": "/usr/bin:/bin"})
    parts = r.stdout.split("---\n")
    check("㉔ lb 구성원 렌더: 앱 server 줄마다 max_fails=0 · 자리표시 down 그대로(§5.8-3b ⓑ)",
          len(parts) == 3 and parts[0] == "server 127.0.0.1:9 down;\nserver app:8101 max_fails=0;\nserver app2:8101 max_fails=0;\n"
          and parts[1] == "server 127.0.0.1:9 down;\n", r.stdout[:300] + r.stderr[-300:])
    check("㉔ lb_members가 그 렌더를 쓴다(원격에 함수를 함께 보냄)", len(parts) == 3 and "declare -f lb_members_conf" in parts[2] and 'lb_members_conf "$@"' in parts[2], parts[-1][-400:])
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
    check("⑦ ① J = 0(전환 구간에 섞임 없음 — §5.8-3a 정의) · J 수 묻지 않음 · lb 편입 뒤 전환 칸 탐침이 있어도 J에 들지 않음 · 회차 ok",
          st == "ok" and len(after) >= 1 and cnts.get("J") is None and (out.get("probe_tables") or {}).get("J") == 0.0,
          f"{st} {cnts} J={(out.get('probe_tables') or {}).get('J')} lb 편입 뒤 {[x['id'] for x in after]}")
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
    # ⑯ 실행기 완료(CR13-15) — 최소 수를 채워도 끝 행 없음·기록 < 계획이면 무효
    out, st = make_rep(root / "c16a", 3, "S4c", 1, driver_done=False)
    check("⑯ 실행기 끝 행 없음(최소 수 충족) → invalid:probe-incomplete", st.startswith("invalid:") and "probe-incomplete" in st and not (out.get("probe_tables") or {}).get("short"),
          f"{st} short={(out.get('probe_tables') or {}).get('short')}")
    d = root / "c16b" / "S4c" / "p3" / "rep1"
    make_rep(root / "c16b", 3, "S4c", 1)
    lines = (d / "probe.jsonl").read_text().splitlines()
    drop = next(i for i, l in enumerate(lines) if json.loads(l).get("kind") == "probe" and json.loads(l).get("d") == 1)   # d = 1 탐침(최소 수에 들지 않는다) 하나 빠짐
    (d / "probe.jsonl").write_text("\n".join(l for i, l in enumerate(lines) if i != drop) + "\n")
    out, st = summarize.rep_summary(d)
    check("⑯ 탐침 기록 23 < 계획 24(끝 행은 있음) → invalid:probe-incomplete", st.startswith("invalid:") and "probe-incomplete" in st, f"{st} {(out.get('probe') or {}).get('incomplete')}")

    # ⑰ 전환 실패(절차) — 새 앱 health 시한 초과 · 그 뒤 사건 없음 · 증분 행·restart 없음(CR13-18 재현)
    def tf_health(base, gc_missing_old=False, no_clock=False):
        make_rep(base, 3, "S4c", 1)
        d = base / "S4c" / "p3" / "rep1"
        (d / "transition-failed.json").write_text(json.dumps({"cause": "procedure", "reason": "health-timeout", "op": "wait-health", "slot": "B", "detail": "시한 300초"}))
        (d / "events-check.json").unlink()
        rows, keep = [json.loads(l) for l in (d / "events.jsonl").read_text().splitlines()], []
        for e in rows:
            if e.get("kind") == "plan" and e.get("step") != "setup" and e.get("op") == "wait-health" and e.get("slot") == "B":
                keep.append(dict(e, result="fail", cause="procedure", detail={"reason": "health-timeout", "limit_s": 300}))
                break
            if e.get("kind") == "plan":
                keep.append(e)
        keep.append({"kind": "end-state", "alive": ["A", "B"], "lb": ["A"]})
        keep.append({"kind": "final-collect", "op": "A", "t_pc": T0 + 1400, "detail": {"life": "A1", "errors": []}})
        (d / "events.jsonl").write_text("\n".join(json.dumps(r) for r in keep) + "\n")
        db = json.loads((d / "drift-db.json").read_text())
        db["reconcile_runs"] = [r for r in db["reconcile_runs"] if not str(r["source"]).startswith("inc-")]
        db["inc_gaps"] = []
        (d / "drift-db.json").write_text(json.dumps(db))
        if gc_missing_old:
            (d / "app-A1-gc.log.gz").unlink()
        if no_clock:
            (d / "clock-offset.json").unlink()
        return summarize.rep_summary(d)
    # 리뷰 재현(그 뒤 사건은 그대로 두고 새 앱 wait-health만 실패 · 증분 행 없음) — 상태 문자열까지 같아야 한다
    make_rep(root / "c17r", 3, "S4c", 1)
    d = root / "c17r" / "S4c" / "p3" / "rep1"
    (d / "transition-failed.json").write_text(json.dumps({"cause": "procedure", "reason": "health-timeout", "op": "wait-health", "slot": "B"}))
    ev = [json.loads(l) for l in (d / "events.jsonl").read_text().splitlines()]
    for e in ev:
        if e.get("kind") == "plan" and e.get("op") == "wait-health" and e.get("role") == "new":
            e["result"] = "fail"
    (d / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev) + "\n")
    db = json.loads((d / "drift-db.json").read_text())
    db["reconcile_runs"] = [r for r in db["reconcile_runs"] if not str(r["source"]).startswith("inc-")]
    (d / "drift-db.json").write_text(json.dumps(db))
    out, st = summarize.rep_summary(d)
    check("⑰ 리뷰 재현 health-timeout + inc 행 없음 → fail:transition-failed:health-timeout", st == "fail:transition-failed:health-timeout", f"{st} {out.get('harness_reasons')}")
    # 실제 모양(실패 뒤 사건 없음 — 오케스트레이터가 멈춘다): 전환 칸·단독(새) 탐침 부족은 절차 몫(probe-short-by-procedure)이 함께 붙을 수 있다 — 하네스 사유는 없어야 한다
    out, st = tf_health(root / "c17a")
    check("⑰ 실패 뒤 사건 없음 + 증분 행 없음 → fail(transition-failed:health-timeout · 하네스 사유 없음 · p7-data-missing는 기록만)",
          st.startswith("fail:") and "transition-failed:health-timeout" in st and not out.get("harness_reasons") and "p7-data-missing" in (out.get("harness_deferred") or []),
          f"{st} deferred={out.get('harness_deferred')}")
    out, st = tf_health(root / "c17b", gc_missing_old=True)
    check("⑰ + 옛 앱 GC 로그 없음(실패 뒤 회차 끝 수집) → 여전히 fail(gc-log-missing:A1은 기록만)",
          st.startswith("fail:") and "transition-failed:health-timeout" in st and "gc-log-missing:A1" in (out.get("harness_deferred") or []), f"{st} deferred={out.get('harness_deferred')}")
    out, st = tf_health(root / "c17c", no_clock=True)
    check("⑰ + 시계 차 결측(전환 실패 앞의 하네스 사유) → invalid:clock-offset-missing 유지", st == "invalid:clock-offset-missing", f"{st} deferred={out.get('harness_deferred')}")
    out, st = make_rep(root / "c17d", 3, "S4c", 1, gc_bad={"A1": "missing"})
    check("⑰ 전환 실패 없음 + GC 로그 없음 → 그대로 invalid:gc-log-missing:A1", st == "invalid:gc-log-missing:A1", st)
    # ⑱ J 모집단 = 전환 칸만(§5.8-3a — CR13-19): 전환 칸 30H 틀림 · 단독(새) 칸 30H·30L 틀림 → J = 1/6(단독(새) 몫은 들지 않는다)
    out, st = make_rep(root / "c18", 3, "S4c", 1, verdicts={("trans", 30, "H"): "wrong", ("new", 30, "H"): "wrong", ("new", 30, "L"): "wrong"})
    pt = out.get("probe_tables") or {}
    check("⑱ J = Σ(T − O) ÷ 짝 수 = 1/6 · J 수 = T 짝 수 6(단독(새) 칸 틀림은 J 밖 — N 틀림으로만)", pt.get("J") == round(1 / 6, 4) and pt["counts"]["J"] == 6 and pt.get("N_wrong") == 2,
          f"J={pt.get('J')} counts={pt.get('counts')} N_wrong={pt.get('N_wrong')}")
    # ⑲ 앱 끝 검사 v_counter_mismatch(CR13-23) → 캠페인 P9 (다)
    camp4 = root / "camp4"
    for r in (1, 2, 3):
        make_rep(camp4, 3, "S4c", r)
        make_rep(camp4, 3, "S3e", r)
    d = camp4 / "S4c" / "p3" / "rep2"
    (d / "consistency.json").write_text(json.dumps({"v_counter_mismatch": 3}))
    summarize.rep_summary(d)
    c4 = summarize.campaign(camp4)["cells"]["S4c-p3"]
    check("⑲ 앱 끝 검사 v_counter_mismatch 3 → P9 (다) 항목", isinstance(c4["P9"], list) and any("(다)" in x and "v_counter_mismatch" in x for x in c4["P9"]), str(c4["P9"]))
    # ⑳ m 시각(CR13-28) — 분 오프셋 · 소수 초 · 못 읽음
    from datetime import datetime
    pg_ts = getattr(summarize, "pg_ts", None)
    check("⑳ m '+05:30' 오프셋 → 분까지 반영", pg_ts is not None and pg_ts('{"2026-10-24 05:30:00+05:30"}') == datetime.fromisoformat("2026-10-24T00:00:00+00:00").timestamp(),
          "pg_ts 없음" if pg_ts is None else str(pg_ts('{"2026-10-24 05:30:00+05:30"}')))
    check("⑳ m 소수 초 · '+00' → 그대로", pg_ts is not None and pg_ts('{"2026-10-24 00:00:01.25+00"}') == datetime.fromisoformat("2026-10-24T00:00:01.250+00:00").timestamp(),
          "pg_ts 없음" if pg_ts is None else str(pg_ts('{"2026-10-24 00:00:01.25+00"}')))
    d = root / "c20" / "S4c" / "p3" / "rep1"
    make_rep(root / "c20", 3, "S4c", 1)
    ec = json.loads((d / "end-check.json").read_text())
    (d / "end-check.json").write_text(json.dumps(dict(ec, m="{bogus}")))
    out, st = summarize.rep_summary(d)
    check("⑳ m이 있는데 못 읽음 → invalid:m-parse-failed(L*를 restart 하한만으로 줄이지 않는다)", st == "invalid:m-parse-failed", st)
    d = root / "c20b" / "S4c" / "p3" / "rep1"
    make_rep(root / "c20b", 3, "S4c", 1)
    ec = json.loads((d / "end-check.json").read_text())
    (d / "end-check.json").write_text(json.dumps(dict(ec, m='{"2026-10-24 05:30:00+05:30"}')))
    out, st = summarize.rep_summary(d)
    want = datetime.fromisoformat("2026-10-24T00:00:00+00:00").timestamp() - OFF - T0
    check("⑳ 회차 owner.m = 분 오프셋까지 읽은 시각", abs(((out.get("owner") or {}).get("m") or 0) - want) < 1e-6, f"{(out.get('owner') or {}).get('m')} ≠ {want}")
    # ㉑ 스모크 사건 길이(CR13-20) — 절차 ④ 스모크 한 회차에 9종이 다 있다 · 기동 시작(start) 길이도 dry-run을 움직인다
    sd = getattr(plan13, "smoke_durations", None)
    make_rep(root / "c21", 4, "S4c", 1)
    d = root / "c21" / "S4c" / "p4" / "rep1"
    if sd is None:
        check("㉑ plan13.smoke_durations 있음", False, "없음")
    else:
        durs, src, prob = sd([d])
        check("㉑ 스모크 events.jsonl → 9종 모두 실측 · wait-health 최대 = 20.01초", not prob and set(durs) == set(plan13.OPS) and abs(durs["wait-health"] - 20.01) < 1e-6, f"{durs} {prob}")
        durs, src, prob = sd([root / "없는 폴더"])
        check("㉑ 스모크 폴더 없음 → 문제(시작 조건 ⑤ 막음)", bool(prob), str(prob))
        sm = root / "c21s"
        sm.mkdir()
        (sm / "events.jsonl").write_text("\n".join(json.dumps({"kind": "plan", "op": op, "result": "ok", "dur_s": 70.0 if op == "start" else 0.0}) for op in plan13.OPS) + "\n")
        r = subprocess.run([sys.executable, "-I", str(HERE / "plan13.py"), "--probe-dry-run", "--smoke", str(sm), "--json"], capture_output=True, text=True)
        b = json.loads(r.stdout).get("boundary_total") if r.stdout.strip().startswith("{") else None
        check("㉑ 기동 시작(start) 70초만 길고 나머지 0 → dry-run 경계 > 0(start 길이가 판정에 든다) · exit 1", r.returncode == 1 and (b or 0) > 0, f"rc={r.returncode} b={b} {r.stderr[-200:]}")
    # ㉒ statement_timeout(CR13-17)
    stf = getattr(plan13, "stmt_timeout_from", None)
    if stf is None:
        check("㉒ plan13.stmt_timeout_from 있음", False, "없음")
    else:
        for name, atts, want in (("c22a", [{"result": "OK", "valid": "true", "build_s": 250.2}], 751), ("c22b", [{"result": "OK", "valid": "true", "build_s": 90}], 600),
                                 ("c22c", [{"result": "FAIL", "valid": "false", "build_s": 600}], None)):
            dd = root / name
            dd.mkdir()
            (dd / "pre-apply.json").write_text(json.dumps({"steps": {"3": {"attempts": atts}}}))
            sec, best, prob = stf([dd])
            check(f"㉒ 단계 3 실측 {atts[0]['build_s']}초({atts[0]['result']}) → statement_timeout {want}", sec == want and (bool(prob) == (want is None)), f"{sec} {best} {prob}")
    # ㉓ pageSize(CR13-27)
    base = {"counterDrift": {"expiryRepair": True, "cleanup": {"mode": "schedule"}, "reconcile": {"enabled": False, "detectShape": "split",
            "incremental": {"method": "trigger", "enabled": True, "interval": "1s", "pageSize": 100000}, "safetyNet": {"enabled": True}}}, "limitStrategy": "counter-upsert-precheck"}
    fw = {"prefix": "spring.flyway", "properties": {"locations": plan13.NEW_FLYWAY}}
    def cc(ps):
        h = json.loads(json.dumps(base))
        if ps is None:
            del h["counterDrift"]["reconcile"]["incremental"]["pageSize"]
        else:
            h["counterDrift"]["reconcile"]["incremental"]["pageSize"] = ps
        return plan13.config_check("new", [{"prefix": "seat.hold", "properties": h}, fw])
    check("㉓ 새 앱 pageSize 100000 → 일치", cc(100000) == [], str(cc(100000)))
    check("㉓ 새 앱 pageSize 5000(양수지만 기본값 아님) → 불일치", any("pageSize" in x for x in cc(5000)), str(cc(5000)))
    check("㉓ 새 앱 pageSize 없음 → 불일치", any("pageSize" in x for x in cc(None)), str(cc(None)))
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
