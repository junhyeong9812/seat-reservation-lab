#!/usr/bin/env python3
"""ADR-014 요약기·판정 규칙 단위 확인(서버 없이) — 가짜 원자료로 회차 폴더를 만들어 summarize.py --rep을 돌리고, 가짜 회차 요약으로 캠페인 판정
(성공 · 고르는 규칙 · 관문 · 결론)을 돌려 기대값과 대조한다.

    selftest.py [--keep <폴더>] [--campaign]      exit 0 = 모든 확인 통과
      --campaign: campaign.sh --simulate(서버 없음 — run.sh --simulate)로 60자리 루프 · 계획 대 실행 대조 · 재측정 상한(환경·하네스 따로) · stdin 차단도 확인한다

확인하는 것(측정-9 회차 사유 · 측정-7 성공 · 측정-8 고르는 규칙):
 ① 정상 주 셀 회차 → ok · W > 0(A) · T95 · 탐침 M3 ② 상태 표본 파일 없음 → state-sampler-gap(무음 0 아님) · 표본 4초 공백 → state-sampler-gap · 앞끝 공백(MR-4)
 ③ DB 컨테이너 docker stats 행 빠짐 → db-cpu-missing ④ 판정 창 안 dropped_iterations → dropped-iterations ⑤ 탐침 송신 오차 > 허용이 한 칸 10% 초과 → probe-timing-bad
 ⑥ 판정 요청이 안 나간 탐침 → probe-incomplete ⑦ 409 ≥ 1인데 원인 표지 대조 불일치 → app-metric-missing · 409 = 0이면 검사 건너뜀(MR-17) · 앱 지표 파일 없음 → app-metric-missing
 ⑧ env-stall: 정지 8초 + 서버 측정기 무응답 + GC 없음 + DB 쪽 원인 없음 → invalid:env-stall · 업무 세션 Lock 대기가 있으면 방식 정지(유효 · P12 자료) · 측정기가 응답했으면 방식 정지
 ⑨ 시계 차 왕복/2 > 20ms → clock-offset-bad ⑩ 테이블 통계 없음 → table-stats-missing ⑪ 설정 불일치 → expiry-mode-mismatch · ttl-mismatch · cond-config-mismatch
 ⑫ V11 인덱스 없음 → seat-map-index-missing ⑬ GC 로그 없음 → gc-log-missing ⑭ 증분·안전망 기록 0 → reconcile-missing ⑮ 비용 셀 → ok(상태 표본·탐침 사유 없음)
 ⑯ 409 원인: B·D의 nowait = 판정 409 + 분류 불가 · C의 nowait = 배치 잠금 409 ⑰ 상태 기대값 문턱(D 5행 · A·D 6행) ⑱ crn-mismatch(같은 회차 방식 사이 다수와 다름)
 ⑲ 캠페인: 모두 성공 → 2단 T95로 빠짐 · 3단 ≫(비겹침 + 10%) · 4단 D > B > C · 순환 처리 · G2(b)는 P3 유효일 때만 · G3 걸리면 다음 순위
 ⑳ 판정 불가: P7 재선점 0 · 'A도 같은 문턱'(P12) · 환경 상한 소진 → 결론 '판정 불가' · 유효 회차의 값 None → 비교에서 판정 불가(0으로 보지 않음)"""
import gzip
import json
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import plan14  # noqa: E402
import summarize as S  # noqa: E402

T0, OFF = 1_800_000_000.0, 0.4
FAILS = []
HEAD = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"


def check(name, cond, detail=""):
    print(("ok   " if cond else "FAIL ") + name + (f" — {detail}" if not cond and detail else ""))
    if not cond:
        FAILS.append(name)


def iso(t, tz="+00:00"):
    return datetime.fromtimestamp(t, timezone.utc).isoformat().replace("+00:00", tz)


def wj(p, x):
    Path(p).write_text(json.dumps(x))


def http(t, name, st, scen, v=5.0, tags=""):
    return f"http_req_duration,{int(t)},{v},,,,true,,POST,{name},HTTP/1.1,{scen},,{st},,,u,{tags},"


def cnt(m, t, scen, tags, v=1):
    return f"{m},{int(t)},{v},,,,,,,,,{scen},,,,,,{tags},"


def make_rep(root, method="A", cell="a20-t30", rep=1, **kw):
    """완전한 가짜 회차 폴더(주 셀 · 비용 셀). kw로 원자료를 하나씩 망가뜨린다."""
    plan = plan14.render(method, cell, rep)
    d = Path(root) / cell / method / f"rep{rep}"
    d.mkdir(parents=True, exist_ok=True)
    wj(d / "plan.json", plan)
    wj(d / "meta.json", {"rerun": False, "attempt": 1})
    wj(d / "k6-summary.json", {"setup_data": {"startedAt": T0 * 1000}})
    rtt = kw.get("rtt", 0.002)
    wj(d / "clock-offset.json", {"before": {"offset_s": OFF, "rtt_s": rtt}, "after": {"offset_s": OFF + 0.001, "rtt_s": 0.002}})
    wj(d / "pre-k6.json", {"seed_done_epoch": T0 - 87.0})
    wj(d / "k6-window.json", {"exit": 0, "k6_started": iso(T0), "k6_ended": iso(T0 + 300)})
    hold = {"expiryMode": kw.get("cfg_mode", plan14.MODE_ENUM[method]), "ttl": kw.get("cfg_ttl", f"PT{plan['ttl_s']}S"), "expiryInterval": "PT10S",
            "strategy": "PESSIMISTIC_NOWAIT", "limitStrategy": "COUNTER_UPSERT_PRECHECK", "maxPerUser": 2, "criticalSectionDelay": "PT0S",
            "counterDrift": {"expiryRepair": True, "reconcile": {"enabled": False, "detectShape": "SPLIT", "incremental": {"method": "TRIGGER", "enabled": True, "interval": "PT1S"},
                                                               "safetyNet": {"enabled": True}}, "cleanup": {"mode": "SCHEDULE"}}}
    wj(d / "app-config.json", {"configprops": [{"prefix": "seat.hold", "properties": hold},
                                               {"prefix": "spring.flyway", "properties": {"locations": kw.get("cfg_flyway", plan14.FLYWAY)}}],
                               "hikaricp.connections.max": [10]})
    wj(d / "limit-key.json", {"env": 0, "cmd": 0, "drift": 0, "drift_cmd": 0, "present": False})
    ss, sn = plan["seed"]["schedules"], plan["seed"]["seats"]
    wj(d / "seed-check.json", {"schedules": ss, "seats": ss * sn, "available": ss * sn, "holds": 0, "reservations": 0, "quota_rows": 0,
                               "seat_map_index": kw.get("index", 1), "flyway_v11": 1, "want_schedules": ss, "want_seats": ss * sn})
    (d / "logs-status.txt").write_text("app pipe=0/0 bytes=100\ndb pipe=0/0 bytes=100\n")
    wj(d / "probe-check.json", {"ok": True})
    win = plan["window"]
    w0, w1 = T0 + win["from_s"], T0 + win["from_s"] + win["len_s"]
    if not kw.get("no_gc"):
        g = [f"[{iso(T0 - 100, '+0000')}][0.009s][info][gc     ] Using The Z Garbage Collector"]
        for t in range(int(w0), int(w1), 30):
            g.append(f'[{datetime.fromtimestamp(t + OFF, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000")}+0000][1s][info][safepoint   ] Safepoint "ZMarkStartYoungAndOld", '
                     "Time since last: 1 ns, Reaching safepoint: 1 ns, Cleanup: 1 ns, At safepoint: 1000 ns, Leaving safepoint: 1 ns, Total: 90000 ns")
        with gzip.open(d / "gc.log.gz", "wt") as f:
            f.write("\n".join(g) + "\n")
    stall = kw.get("stall")   # (시작 상대초, 길이)
    rows = []
    n409 = 0
    for s in range(int(w0) - 2, int(w1) + 12):
        rel = s - T0
        if stall and stall[0] <= rel < stall[0] + stall[1]:
            continue
        if plan["kind"] == "main":
            for st, n in (("201", 2), ("409", 3)):
                for _ in range(n):
                    rows.append(http(s, "hold", st, "blind"))
            n409 += 3 if w0 - 2 <= s else 0
            rows.append(http(s, "confirm", "200", "blind"))
            rows.append(http(s, "seatmap", "200", "map"))
        else:
            for _ in range(5):
                rows.append(http(s, "hold", "201", "s4", tags="stage=0" if rel >= 60 else "stage=ramp"))
    for sc in ("blind", "map"):
        rows += [cnt("crn_n", T0, sc, "", 100), cnt("crn_h1", T0, sc, "", 12345 if not kw.get("crn_alt") else 999), cnt("crn_h2", T0, sc, "", 777)]
    if kw.get("dropped"):
        rows.append(cnt("dropped_iterations", T0 + 30, "blind", ""))
    hold409 = sum(1 for r in rows if r.startswith("http_req_duration") and ",hold," in r and ",409," in r)
    probe409 = 0
    if plan["kind"] == "main":
        pp = plan["probes"]
        ttl = plan["ttl_s"]
        for pr in ("pr1", "pr2", "pr3"):
            per = pp["period_ms"][pr] / 1000
            ds = plan14.DELTAS_C if pr == "pr3" else plan14.DELTAS
            for i in range(pp["count"][pr]):
                dd = ds[i % len(ds)]
                st0 = T0 + pp["start_s"] + i * per
                e = (st0 + OFF + ttl) * 1000
                base = f"pr={pr}&pid={i}&d={dd if pr == 'pr3' else int(dd)}"

                def prow(step, st, code, sent, plan_ms, rej="", ee=""):
                    return cnt("probe", sent / 1000, pr, f"{base}&step={step}&st={st}&code={code}&rej={rej}&sent={int(sent)}&plan={plan_ms}&e={ee}&rt=3")
                setups = ["h0"] if pr == "pr1" else ["h0a", "h0b"] if pr == "pr2" else ["u0"]
                for sp in setups:
                    rows.append(prow(sp, "201", "OK", st0 * 1000, "", ee=int(e)))
                skew = kw.get("skew", {}).get((pr, str(float(dd))), 0)
                if kw.get("skip_judged") == (pr, i):
                    continue
                if pr in ("pr1", "pr2"):
                    plan_ms = e + dd * 1000
                    sent = plan_ms - OFF * 1000 + (skew if i % 2 == 0 else 0)
                    if (method == "A" or kw.get("probe409")) and dd in (1, 3):
                        rows.append(prow("h1", "409", "SEAT_NOT_AVAILABLE" if pr == "pr1" else "HOLD_LIMIT_EXCEEDED", sent, int(plan_ms), rej=kw.get("probe_rej", "rule")))
                        probe409 += 1
                    else:
                        rows.append(prow("h1", "201", "OK", sent, int(plan_ms)))
                else:
                    tv, tc = e + 300, e + dd * 1000
                    rows.append(prow("v", "409" if dd < 0 else "201", "SEAT_NOT_AVAILABLE" if dd < 0 else "OK", tv - OFF * 1000, int(tv), rej="rule" if dd < 0 else ""))
                    probe409 += dd < 0
                    rows.append(prow("c", "200" if dd < 0 else "409", "OK" if dd < 0 else "HOLD_EXPIRED", tc - OFF * 1000, int(tc)))
        # 주 흐름 홀드·확정 기록(재구성 — 좌석 1..50 · 이탈 25 · 재선점)
        for k in range(50):
            ex = T0 + OFF + 5 + k + ttl
            rows.append(cnt("hold_ok", T0 + 5 + k, "blind", f"seat={k + 1}&exp={iso(ex).replace('+00:00', 'Z')}&u={1000000 + k}&kind=first&fate={'abandon' if k % 2 else 'confirm'}"))
            if k % 2:
                ex2 = ex + 3 + ttl
                rows.append(cnt("hold_ok", ex + 3 - OFF, "blind", f"seat={k + 1}&exp={iso(ex2).replace('+00:00', 'Z')}&u={2000000 + k}&kind=first&fate=confirm"))
            else:
                rows.append(cnt("confirm_rec", T0 + 10 + k, "blind", f"seat={k + 1}&exp={iso(ex).replace('+00:00', 'Z')}&st=200&code=OK&sent={int((T0 + 10 + k) * 1000)}&rt=5"))
    rej_rule = hold409 + probe409
    if kw.get("rej_rows", True):
        rows.append(cnt("rej", T0 + 100, "blind", "cause=rule", rej_rule))
    with gzip.open(d / "k6-requests.csv.gz", "wt") as f:
        f.write(HEAD + "\n" + "\n".join(rows) + "\n")
    # docker stats(10초) — DB · 앱
    srv = []
    for t in range(int(w0) - 20, int(w1) + 30, 10):
        srv.append(json.dumps({"t": iso(t + OFF), "load": "1 1 1"}))
        if not (kw.get("db_rows_gap") and w0 + 50 <= t < w0 + 100):
            srv.append(json.dumps({"Name": "seatlab-db-1", "CPUPerc": "120.5%", "MemUsage": "1GiB / 14GiB"}))
        srv.append(json.dumps({"Name": "seatlab-app-1", "CPUPerc": "200%", "MemUsage": f"{900 + (t - w0) * kw.get('mem_slope', 0):.1f}MiB / 2GiB"}))
    (d / "timeline-server.jsonl").write_text("\n".join(srv) + "\n")
    cons = {"expiry_mode": plan["mode"], "v_counter_mismatch": 0, "v_over_limit_users": 0, "v_duplicate_confirmed_seats": 0, "double_sold_seats": 0,
            "multi_active_seats": 0, "held_no_active": kw.get("held_no_active_end", 0), "active_not_held": 0, "active_on_reserved_seats": 0,
            "expired_hold_rows": 5 if method == "B" else 0, "v_held_without_hold": kw.get("held_without_hold", 0), "confirmed": 25 + 87}
    wj(d / "consistency.json", cons)
    runs = [] if kw.get("no_reconcile") else [{"source": "inc-app-trg-1s", "started_at": iso(T0 + OFF + 1)}, {"source": "safety-app", "started_at": iso(T0 + OFF + 30)}]
    wj(d / "drift-db.json", {"drift_log_total": 0, "drift_log": [], "reconcile_runs": runs})
    for f in ("before", "after-k6", "end"):
        wj(d / f"drift-{f}.json", {"tick": {"ok": 1, "failed": 0}, "drift_record_failed": 0, "cleanup": {"errors": 0}})
    app_rule = rej_rule + kw.get("app_rule_extra", 0)
    for f, rr in (("before", 0), ("after-k6", app_rule), ("end", app_rule)):
        if kw.get("no_act") and f == "after-k6":
            continue
        wj(d / f"act14-{f}.json", {"rejected": {"rule": rr, "nowait": 0}, "tick": {"ok": 10, "failed": 0}, "reclaim": {"COUNT": 0, "TOTAL_TIME": 0, "MAX": 0},
                                   "deleted": {"reclaim": 0, "batch": 0}, "missing_pairs": {"reclaim": 0, "batch": 0}, "skip_locked": {"c2": 0, "h4b": 0},
                                   "revert": {"reclaim.reverted": 0, "reclaim.kept_active": 0, "batch.reverted": 0, "batch.kept_active": 3 if f != "before" else 0},
                                   "chunk": {"ok": 0, "failed": 0}, "missing_mismatch": 0})
    hk = []
    for t in range(int(w0) - 5, int(w1) + 5):
        hk.append(json.dumps({"t": iso(t), "t_ms": t * 1000, "pending": 0, "active": 3}))
    (d / "timeline-hikari.jsonl").write_text("\n".join(hk) + "\n")
    wt = []
    for i in range(int((w1 - w0 + 20) * 2)):
        t = w0 - 5 + i * 0.5
        bs = {"app|CPU:-": 2}
        if kw.get("lock_in_stall") and stall and stall[0] <= t - T0 < stall[0] + stall[1]:
            bs["app|Lock:tuple"] = 3
        wt.append(json.dumps({"t": iso(t + OFF), "waits": {}, "by_src": bs}))
    (d / "timeline-waits.jsonl").write_text("\n".join(wt) + "\n")
    pr = []
    for i in range(int((w1 - w0 + 40) * 10)):
        t = w0 - 10 + i * 0.1
        if stall and not kw.get("probe_alive") and stall[0] <= t - T0 < stall[0] + stall[1]:
            continue
        pr.append(f"http_req_duration,{int((t + OFF) * 1000)},1.0,,,,true,,GET,ping,HTTP/1.1,ping,,200,,,u,,")
    with gzip.open(d / "k6-probe-server.csv.gz", "wt") as f:
        f.write(HEAD + "\n" + "\n".join(pr) + "\n")
    if not kw.get("no_table"):
        for f, upd in (("before", 100), ("after", 100 + 112), ("end", 212)):
            if plan["kind"] == "cost" and f == "end":
                continue
            wj(d / f"table-{f}.json", {"tables": {"product_seat": {"n_tup_upd": upd}}, "deadlocks": 0, "confirmed": 112})
    if plan["kind"] == "main" and not kw.get("no_state"):
        st = []
        for i in range(-3, int(win["len_s"]) + 30):
            if kw.get("state_gap") and kw["state_gap"][0] <= i < kw["state_gap"][1]:
                continue
            if kw.get("state_late_start") and i < kw["state_late_start"]:
                continue
            t = T0 + OFF + i
            res = min(10000, 300 + i * 100)
            hna = 2 if method == "A" and i > 30 else (kw.get("hna_samples", 0) if i == 50 else 0)
            st.append(json.dumps({"t": iso(t), "db_now": iso(t), "reserved": res, "free_by_rule": 10000 - res, "held_no_active": hna, "active_not_held": 0,
                                  "multi_active": 0, "active_on_reserved": 0, "expired_rows": 4, "sample_ms": 12.5}))
        (d / "timeline-seatstate.jsonl").write_text("\n".join(st) + "\n")
    return d


def rep(d):
    return S.rep_summary(d)


def main():
    keep = None
    if "--keep" in sys.argv:
        keep = Path(sys.argv[sys.argv.index("--keep") + 1])
    tmp = Path(keep or tempfile.mkdtemp(prefix="adr014-selftest-"))
    if keep and tmp.exists():
        shutil.rmtree(tmp)
    tmp.mkdir(parents=True, exist_ok=True)
    try:
        run(tmp)
        if "--campaign" in sys.argv:
            campaign_sim(tmp)
    finally:
        if not keep:
            shutil.rmtree(tmp, ignore_errors=True)
    print(f"\n{'모두 통과' if not FAILS else f'실패 {len(FAILS)}: {FAILS}'}")
    return 1 if FAILS else 0


def run(tmp):
    # ① 정상
    s = rep(make_rep(tmp / "ok"))
    check("① 정상 주 셀 → ok", s["status"] == "ok", s["status"] + " " + json.dumps(s["missing_inputs"], ensure_ascii=False))
    st = s["metrics"].get("state") or {}
    check("① A W > 0 · T95 유한", (st.get("W") or 0) > 0 and st.get("T95") not in (None, S.INF), str(st.get("W")) + " " + str(st.get("T95")))
    m3 = s["metrics"]["probe"]["m3"]
    check("① A 탐침 δ1·δ3 부당 실패 > 0 · M3 비율", m3["unfair_d13"] > 0 and 0 < m3["rate"] < 1, json.dumps(m3)[:200])
    check("① 탐침 계획 대 실행 일치", not s["metrics"]["probe"]["incomplete"], str(s["metrics"]["probe"]["incomplete"]))
    check("① W 계단 함수 = Σ 2 × 1초(31~240초 표본 210개) ≈ 420", abs(st["W"] - 2 * (240 - 31)) < 3, str(st["W"]))
    # ②
    s = rep(make_rep(tmp / "nostate", no_state=True))
    check("② 상태 표본 없음 → state-sampler-gap(무음 0 아님)", "state-sampler-gap" in s["reasons"] and (s["metrics"].get("state") is None), s["status"])
    s = rep(make_rep(tmp / "gap", state_gap=(100, 104)))
    check("② 상태 표본 4초 공백 → state-sampler-gap", "state-sampler-gap" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "head", state_late_start=4))
    check("② 앞끝 공백(t0 → 첫 표본 4초 — MR-4) → state-sampler-gap", "state-sampler-gap" in s["reasons"], s["status"])
    # ③
    s = rep(make_rep(tmp / "dbcpu", db_rows_gap=True))
    check("③ DB docker stats 행 빠짐 → db-cpu-missing", "db-cpu-missing" in s["reasons"], s["status"])
    # ④
    s = rep(make_rep(tmp / "drop", dropped=True))
    check("④ 창 안 dropped_iterations → dropped-iterations", "dropped-iterations" in s["reasons"], s["status"])
    # ⑤ 한 칸(pr1 δ5)에서 짝수 순번만 200ms 늦게 → 절반 빠짐 > 10%
    s = rep(make_rep(tmp / "timing", skew={("pr1", "5.0"): 200}))
    check("⑤ 송신 오차 초과 > 10% → probe-timing-bad · probe-timing-dropped 기록", "probe-timing-bad" in s["reasons"] and any("probe-timing-dropped" in f for f in s["flags"]), s["status"])
    s = rep(make_rep(tmp / "timing-ok", skew={("pr1", "5.0"): 60}))
    check("⑤ 송신 오차 60ms(PR-1 허용 100ms 안) → 사유 아님", "probe-timing-bad" not in s["reasons"], s["status"])
    # ⑥
    s = rep(make_rep(tmp / "incomp", skip_judged=("pr2", 7)))
    check("⑥ 판정 요청 안 나간 탐침 → probe-incomplete", "probe-incomplete" in s["reasons"], s["status"])
    # ⑦
    s = rep(make_rep(tmp / "appm", app_rule_extra=5))
    check("⑦ 원인 표지 k6 합 ≠ 앱 지표 증분 → app-metric-missing", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "appm2", rej_rows=False))
    check("⑦ 409 ≥ 1인데 헤더 0 → app-metric-missing", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "appm3", no_act=True))
    check("⑦ 앱 지표 스냅숏 없음 → app-metric-missing(0으로 채우지 않음)", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "cost-ok", cell="S4c4200", method="D", rej_rows=False))
    check("⑦ 409 = 0 회차는 원인 표지 검사를 건너뛴다(MR-17)", "app-metric-missing" not in s["reasons"], s["status"])
    # ⑧ env-stall
    s = rep(make_rep(tmp / "env", stall=(100, 8)))
    check("⑧ 정지 8초 + 측정기 무응답 + GC 없음 + DB 원인 없음 → invalid:env-stall(환경 상한)", s["status"] == "invalid:env-stall" and s["env_stall_only"], s["status"] + json.dumps(s["metrics"].get("stall"), ensure_ascii=False)[:300])
    s = rep(make_rep(tmp / "envlock", stall=(100, 8), lock_in_stall=True))
    check("⑧ 업무 세션 Lock 대기 있음 → 방식 정지(유효 · P12 정지 초)", s["status"] == "ok" and (s["violations"]["p12"].get("method_stall_secs") or 0) >= 8, s["status"] + json.dumps(s["violations"]["p12"]))
    s = rep(make_rep(tmp / "envalive", stall=(100, 8), probe_alive=True))
    check("⑧ 서버 측정기가 응답했다 → 방식 정지", s["status"] == "ok" and (s["violations"]["p12"].get("method_stall_secs") or 0) >= 8, s["status"])
    s = rep(make_rep(tmp / "short", stall=(100, 3)))
    check("⑧ 3초 정지(5초 미만) → 방식 정지 초(환경 후보 아님)", s["status"] == "ok" and s["violations"]["p12"].get("method_stall_secs") == 3, s["status"])
    # ⑨~⑭
    check("⑨ 왕복/2 > 20ms → clock-offset-bad", "clock-offset-bad" in rep(make_rep(tmp / "clk", rtt=0.05))["reasons"])
    check("⑩ 테이블 통계 없음 → table-stats-missing", "table-stats-missing" in rep(make_rep(tmp / "tbl", no_table=True))["reasons"])
    r = rep(make_rep(tmp / "cfg1", method="B", cfg_mode="SWEEP"))["reasons"]
    check("⑪ expiryMode ≠ 셀 → expiry-mode-mismatch", "expiry-mode-mismatch" in r, str(r))
    check("⑪ TTL ≠ 셀 → ttl-mismatch", "ttl-mismatch" in rep(make_rep(tmp / "cfg2", cfg_ttl="PT5M"))["reasons"])
    check("⑪ Flyway에 V11 위치 없음 → cond-config-mismatch", "cond-config-mismatch" in rep(make_rep(tmp / "cfg3", cfg_flyway=plan14.FLYWAY[:3]))["reasons"])
    check("⑫ V11 인덱스 없음 → seat-map-index-missing", "seat-map-index-missing" in rep(make_rep(tmp / "idx", index=0))["reasons"])
    check("⑬ GC 로그 없음 → gc-log-missing", "gc-log-missing" in rep(make_rep(tmp / "gc", no_gc=True))["reasons"])
    check("⑭ 증분·안전망 기록 0 → reconcile-missing", "reconcile-missing" in rep(make_rep(tmp / "rec", no_reconcile=True))["reasons"])
    # ⑮ 비용 셀
    s = rep(make_rep(tmp / "cost", cell="S4c4200", method="A", rej_rows=False))
    check("⑮ 비용 셀 → ok(상태 표본·탐침 사유 없음) · 메모리 증가 계산", s["status"] == "ok" and s["metrics"].get("mem_growth") is not None, s["status"] + json.dumps(s["missing_inputs"], ensure_ascii=False))
    s = rep(make_rep(tmp / "costmem", cell="S4c4200", method="A", rej_rows=False, mem_slope=1.0))
    check("⑮ 비용 셀 앱 메모리 첫 1분 → 마지막 1분 ≥ 10% → P12 ④ 자료", s["violations"]["p12"].get("mem_growth_ge_10pct") == 1, json.dumps(s["violations"]["p12"]))
    # ⑯ 409 원인 분류
    check("⑯ B의 탐침 nowait = 판정 409 + 분류 불가", S.classify_409("B", "nowait") == ("rule", True) and S.classify_409("D", "") == ("rule", True))
    check("⑯ C의 nowait = 배치 잠금 409", S.classify_409("C", "nowait") == ("batch", False))
    s = rep(make_rep(tmp / "bnow", method="B", probe_rej="nowait", probe409=True))
    check("⑯ B 회차 탐침 nowait 409 → 판정 409(P4 반증 자료) + reject-cause-unknown 이름표 · 배치 잠금 0",
          s["violations"].get("p4_judge409", 0) > 0 and s["metrics"]["probe"]["m3"]["batch"] == 0 and any("reject-cause-unknown" in f for f in s["flags"]),
          json.dumps(s["metrics"]["probe"]["m3"])[:200])
    s = rep(make_rep(tmp / "cnow", method="C", probe_rej="nowait", probe409=True))
    check("⑯ C 회차 탐침 nowait 409 → 배치 잠금 409(M3ᵦ) · 판정 409 0(P4 문턱 밖)",
          s["metrics"]["probe"]["m3"]["batch"] > 0 and s["violations"].get("p4_judge409") == 0, json.dumps(s["metrics"]["probe"]["m3"])[:200])
    s = rep(make_rep(tmp / "bok", method="B"))
    check("⑯ B 정상 회차 → 판정 409 0", s["status"] == "ok" and s["violations"].get("p4_judge409") == 0, s["status"])
    # ⑰ 상태 기대값 문턱
    s = rep(make_rep(tmp / "d5", method="D", hna_samples=1))
    check("⑰ D 판정 창 표본 held_no_active > 0 → 5행 위반", any(v["row"] == 5 for v in s["violations"]["state"]), json.dumps(s["violations"]["state"]))
    s = rep(make_rep(tmp / "a6", method="A", held_without_hold=2))
    check("⑰ A v_held_without_hold > 0 → 6행 위반", any(v["row"] == 6 for v in s["violations"]["state"]))
    s = rep(make_rep(tmp / "b6", method="B", held_without_hold=2))
    check("⑰ B v_held_without_hold > 0 → 문턱 아님(기대 '> 0 가능')", not any(v["row"] == 6 for v in s["violations"]["state"]))
    # ⑱ crn
    root = tmp / "crn"
    for m in "ABCD":
        make_rep(root, method=m, crn_alt=(m == "C"))
        rep(root / "a20-t30" / m / "rep1")
        (root / "a20-t30" / m / "rep1" / "status").write_text("ok\n")
    S.crn_check(root)
    stc = (root / "a20-t30" / "C" / "rep1" / "status").read_text().strip()
    sta = (root / "a20-t30" / "A" / "rep1" / "status").read_text().strip()
    check("⑱ 다수와 다른 방식 → crn-mismatch · 나머지 그대로", stc == "invalid:crn-mismatch" and sta == "ok", stc + " / " + sta)
    campaign_rules(tmp)


# ---- 캠페인 판정(가짜 회차 요약) ----------------------------------------------------------------------------------------------------
def fake(m, c, r, **o):
    t95 = o.get("t95", {}).get(m, 100.0) + r * 0.5
    cost = o.get("cost", {}).get(m, 10.0) + r * 0.1
    s = {"status": "ok", "reasons": [], "metrics": {"rep": r, "method": m, "cell": c,
         "state": {"T95": t95, "W": (50.0 if m == "A" else 0.0) if o.get("w") is None else o["w"], "L_A": 5.0, "c_pct": 1.0, "expired_end_window": 10},
         "probe": {"m3": {"n409": o.get("m3", {}).get(m, 10 if m == "A" else 0), "batch": o.get("m3b", {}).get(m, 0), "den": 100,
                          "unfair_d13": 5 if m == "A" else 0, "by_delta": {}}, "pr3": {"executed": 100, "planned": 100}},
         "p99": {"cost_hold": cost, "hold_201": cost, "confirm_200": cost, "seatmap_200": cost}, "db_cpu": {"mean": o.get("cpu", {}).get(m, 100.0) + r},
         "flow": {"rehold_other": o.get("rehold", 5), "abandoned": 10, "revisit": {"n201": 1}}, "app": {"kept_active": 3}},
         "violations": {"integrity": {}, "state": [], "p12": dict(o.get("p12", {}).get((m, c), {})), "p12_unknown": [], "p4_judge409": 0, "p9_early_not200": 0,
                        "p6": {"violated": False} if m == "D" else None}}
    if o.get("none_t95") == (m, c, r):
        s["metrics"]["state"]["T95"] = None
    return s


def write_campaign(root, **o):
    for c in plan14.GROUPS:
        for m in plan14.ABCD:
            for r in range(1, 4):
                d = Path(root) / c / m / f"rep{r}"
                d.mkdir(parents=True, exist_ok=True)
                st = o.get("status", {}).get((r, c, m), "ok")
                (d / "status").write_text(st + "\n")
                wj(d / "rep-summary.json", fake(m, c, r, **o))


def verdict(root):
    S.campaign(root)
    return json.loads((Path(root) / "result.json").read_text())


def campaign_rules(tmp):
    # ⑲ 2단: B의 T95가 C·D보다 비겹침으로 짧다 → C·D 빠짐 → B
    r = verdict((write_campaign(tmp / "c1", t95={"A": 140, "B": 80, "C": 100, "D": 100}) or tmp / "c1"))
    check("⑲ 2단 T95로 C·D가 빠지고 B가 1순위", r["ranked"][0] == "B" and r["stages"][1]["out"] == ["C", "D"], json.dumps(r["stages"], ensure_ascii=False))
    check("⑲ G2(a) T95 A보다 짧다 → 기본값 교체 B", r["decision"]["new_default"] == "lazy", r["decision"]["verdict"])
    # 3단: D의 비용이 B·C보다 ≫ → D 빠짐 · B·C 동률 → 4단 B
    r = verdict((write_campaign(tmp / "c2", t95={"A": 140}, cost={"A": 10, "B": 10, "C": 10, "D": 20}) or tmp / "c2"))
    check("⑲ 3단 ≫(비겹침 + 10%)로 D 빠짐 · 4단 B > C", r["stages"][2]["out"] == ["D"] and r["ranked"][0] == "B", json.dumps(r["stages"], ensure_ascii=False))
    r = verdict((write_campaign(tmp / "c2b", t95={"A": 140}, cost={"A": 10, "B": 10, "C": 10, "D": 10.5}) or tmp / "c2b"))
    check("⑲ 3단 +5%(비겹침이어도 10% 미만)는 빠지지 않는다 → 4단 D", r["ranked"][0] == "D", json.dumps(r["stages"], ensure_ascii=False))
    # G3: 1순위 D가 A보다 ≫ → G3 걸림 → 2순위 B
    r = verdict((write_campaign(tmp / "c3", t95={"A": 140}, cost={"A": 10, "B": 10, "C": 10, "D": 10}, cpu={"D": 200}) or tmp / "c3"))
    check("⑲ 1순위 D가 G3(DB CPU ≫ A)에 걸리면 다음 순위가 관문을 넘는다", r["gates"]["D"]["G3"]["hits"] and r["decision"]["new_default"] == "lazy",
          json.dumps({k: v["G3"] for k, v in r["gates"].items()}, ensure_ascii=False))
    # G2(b): T95 차이 없음 + 탐침만 → P3 유효면 이김
    r = verdict((write_campaign(tmp / "c4") or tmp / "c4"))
    check("⑲ T95 같음 · P3 유효 → G2(b)로 이김(측정-8 '주의')", r["gates"][r["ranked"][0]]["G2"]["b"] and r["decision"]["new_default"], json.dumps(r["gates"], ensure_ascii=False)[:300])
    # 순환
    beats = {("X", "Y"), ("Y", "Z"), ("Z", "X")}
    keep, gone = S.eliminate(["X", "Y", "Z"], lambda a, b: (a, b) in beats)
    check("⑲ 순환 → 순환에 든 후보만 남긴다", sorted(keep) == ["X", "Y", "Z"] and not gone)
    beats2 = {("X", "Y"), ("Y", "X"), ("X", "W"), ("Y", "W")}
    keep, gone = S.eliminate(["X", "Y", "W"], lambda a, b: (a, b) in beats2)
    check("⑲ 순환 밖에서 빠진 후보는 되살리지 않는다", sorted(keep) == ["X", "Y"] and gone == ["W"], f"{keep} {gone}")
    # ⑳ 판정 불가
    r = verdict((write_campaign(tmp / "c5", t95={"A": 140}, rehold=0) or tmp / "c5"))
    check("⑳ P7 재선점 0(3회 합) → 후보 판정 불가 · 결론 A 유지 ①(성공 후보 없음)",
          all(r["success"][m]["verdict"] == "판정 불가" for m in "BCD") and "A 유지 ①" in r["decision"]["verdict"], r["decision"]["verdict"])
    p12 = {(m, "a20-t30"): {"5xx_or_0:hold": 3} for m in "ABCD"}
    r = verdict((write_campaign(tmp / "c6", t95={"A": 140}, p12=p12) or tmp / "c6"))
    check("⑳ A도 같은 (항목, 셀)을 어김 → 판정 불가(방식 실패 아님) · 결론 '판정 불가 → 사용자 보고'",
          all(r["success"][m]["verdict"] == "판정 불가" for m in "BCD") and r["decision"]["verdict"].startswith("판정 불가"), r["decision"]["verdict"])
    p12b = {("D", "a20-t30"): {"5xx_or_0:hold": 3}}
    r = verdict((write_campaign(tmp / "c7", t95={"A": 140}, p12=p12b) or tmp / "c7"))
    check("⑳ A는 어기지 않은 항목을 D가 어김 → D 실패", r["success"]["D"]["verdict"] == "실패" and r["success"]["B"]["verdict"] == "성공", json.dumps(r["success"]["D"], ensure_ascii=False))
    st = {(2, "a50-t300", "C"): "invalid:env-stall"}
    r = verdict((write_campaign(tmp / "c8", t95={"A": 140}, status=st) or tmp / "c8"))
    check("⑳ 환경 상한 소진 자리(C) → 관문을 넘은 후보(D)가 있어도 결론 '판정 불가 → 사용자 보고'(잠정 결과 함께)",
          r["env_exhausted"] == ["rep2 a50-t300 C"] and r["decision"]["verdict"].startswith("판정 불가") and "잠정: D" in r["decision"]["verdict"]
          and r["decision"]["new_default"] is None, r["decision"]["verdict"])
    check("⑳ 환경 상한 소진 방식(C)은 성공 아님(유효 < 3 — 판정 불가)", r["success"]["C"]["verdict"] == "판정 불가", r["success"]["C"]["verdict"])
    r = verdict((write_campaign(tmp / "c9", t95={"A": 140, "B": 80, "C": 100, "D": 100}, none_t95=("B", "a20-t30", 2)) or tmp / "c9"))
    check("⑳ 유효 회차의 T95 None → 그 비교는 판정 불가(0으로 보지 않음) · range None", r["ranges"]["T95"]["B:a20-t30"] is None, str(r["ranges"]["T95"]["B:a20-t30"]))
    r = verdict((write_campaign(tmp / "c10", t95={"A": 140}, w=0.0) or tmp / "c10"))
    check("⑳ A의 TTL 30초 W = 0 → G4 실패 → A 유지 ④(판정 불가)", not r["A"]["G4"] and "④" in r["decision"]["verdict"], r["decision"]["verdict"])


# ---- campaign.sh 루프(서버 없음) ------------------------------------------------------------------------------------------------
def campaign_sim(tmp):
    repo = HERE.parent.parent.parent
    cs = HERE / "campaign.sh"
    out = tmp / "sim"

    def run_c(cid, plan_lines=None):
        sp = tmp / f"{cid}.plan"
        sp.write_text("\n".join(plan_lines or []) + "\n")
        p = subprocess.run(["bash", str(cs), "--sha", "HEAD", "--simulate", "--worktree", "--results-root", str(out), "--id", cid, "--sim-plan", str(sp)],
                           cwd=repo, capture_output=True, text=True, timeout=900)
        return p.returncode, p.stdout + p.stderr
    rc, log = run_c("all-ok")
    n = len(list((out / "all-ok").glob("*/*/rep*/status")))
    check("캠페인 루프: 60자리 모두 실행 · exit 0(stdin 차단 — run.sh가 표준 입력을 읽어도 루프가 끝나지 않는다)", rc == 0 and n == 60, f"rc={rc} n={n} {log[-400:]}")
    rc, log = run_c("skip", ["1:a50-t30:A:1=skip", "1:a50-t30:A:2=skip", "1:a50-t30:A:3=skip", "1:a50-t30:A:4=skip", "1:a50-t30:A:5=skip"])
    check("캠페인 루프: status를 끝내 못 쓴 자리 → 계획 대 실행 대조 exit 6", rc == 6 and "실행되지 않은 회차" in log, f"rc={rc} {log[-300:]}")
    lines = [f"2:a20-t30:B:{a}=invalid:env-stall" for a in (1, 2, 3)] + ["3:a20-t30:C:1=invalid:gc-log-missing", "3:a20-t30:C:2=invalid:env-stall",
                                                                         "3:a20-t30:C:3=invalid:gc-log-missing", "3:a20-t30:C:4=invalid:env-stall",
                                                                         "3:a20-t30:C:5=invalid:gc-log-missing"]
    rc, log = run_c("rerun", lines)
    b = sorted(p.name for p in (out / "rerun" / "a20-t30" / "B").glob("rep2*"))
    c = sorted(p.name for p in (out / "rerun" / "a20-t30" / "C").glob("rep3*"))
    check("재측정: env-stall만 → 환경 2번 뒤 멈춤(시도 3)", b == ["rep2", "rep2.attempt1", "rep2.attempt2"], str(b))
    check("재측정: 하네스 2번 + 환경 2번을 따로 센다(시도 5)", c == ["rep3", "rep3.attempt1", "rep3.attempt2", "rep3.attempt3", "rep3.attempt4"], str(c))
    rc2 = subprocess.run(["bash", str(cs), "--check", str(out / "all-ok")], capture_output=True, text=True).returncode
    shutil.rmtree(out / "all-ok" / "a20-t300" / "D" / "rep3")
    p = subprocess.run(["bash", str(cs), "--check", str(out / "all-ok")], capture_output=True, text=True)
    check("--check: 모두 있으면 0 · 한 자리 지우면 exit 6", rc2 == 0 and p.returncode == 6, f"{rc2} {p.returncode} {p.stderr[-200:]}")


if __name__ == "__main__":
    sys.exit(main())
