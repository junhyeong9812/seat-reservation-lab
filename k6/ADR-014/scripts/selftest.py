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
 ⑳ 판정 불가: P7 재선점 0 · 'A도 같은 문턱'(P12) · 환경 상한 소진 → 결론 '판정 불가' · 유효 회차의 값 None → 비교에서 판정 불가(0으로 보지 않음)
 ㉑ 코드 리뷰 루프 2: 정합 문턱 열 결측·숫자 아님(CR14-8) · 시계 차 rtt_s(CR14-9) · 첫 선점 409 → probe-incomplete(CR14-10) · PR-3 실행 = 송신 수(CR14-11) ·
    h4-sampler-missing(CR14-12) · 창 끝 스냅숏 · P12 창 안 증분(CR14-13) · warmup-failed · 예열 상태 분포(CR14-14) · P5 판정 불가 · P6·P9 반증 · 정합 위반 · G2(c) ·
    상태 문턱 6·7·8행 'A도 같은 문턱' · --early-check(CR14-15) · G3 판정 불가 결론(CR14-18) · 재선점 기회 넓은·좁힌 값(CR14-19) · RESERVED ↔ 확정 짝 위반(CR14-20)"""
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
    before = {"offset_s": OFF, "rtt_s": rtt}
    if kw.get("rtt_missing"):
        del before["rtt_s"]
    wj(d / "clock-offset.json", {"before": before, "after": {"offset_s": OFF + 0.001, "rtt_s": 0.002}})
    if not kw.get("no_warmup"):
        wj(d / "warmup-status.json", {"rc": kw.get("warmup_rc", 0), "by_status": {"warm-hold:201": 2000}, "n": 2000})
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
                rejected = kw.get("setup_reject") == (pr, i)   # 첫 선점이 5xx가 아닌 이유(409)로 실패 → 판정 요청이 나가지 않는다(CR14-10)
                for sp in setups:
                    if kw.get("setup_row_drop") == (pr, i, sp):   # 첫 선점 기록 행 자체가 빠짐(CR14-22)
                        continue
                    rows.append(prow(sp, "409" if rejected else "201", "SEAT_NOT_AVAILABLE" if rejected else "OK", st0 * 1000, "", ee=int(e)))
                if rejected:
                    continue
                skew = kw.get("skew", {}).get((pr, str(float(dd))), 0)
                if kw.get("skip_judged") == (pr, i):
                    continue
                odd = kw.get("judged_odd", {}).get((pr, i))   # 판정 요청 응답을 예상 밖 값으로(CR14-23) — {단계: 상태}
                if pr in ("pr1", "pr2"):
                    plan_ms = e + dd * 1000
                    sent = plan_ms - OFF * 1000 + (skew if i % 2 == 0 else 0)
                    if odd:
                        rows.append(prow("h1", odd["h1"], "BAD_REQUEST", sent, int(plan_ms)))
                    elif (method == "A" or kw.get("probe409")) and dd in (1, 3):
                        rows.append(prow("h1", "409", "SEAT_NOT_AVAILABLE" if pr == "pr1" else "HOLD_LIMIT_EXCEEDED", sent, int(plan_ms), rej=kw.get("probe_rej", "rule")))
                        probe409 += 1
                    else:
                        rows.append(prow("h1", "201", "OK", sent, int(plan_ms)))
                else:
                    tv, tc = e + 300, e + dd * 1000
                    sv, sc_ = ("409" if dd < 0 else "201"), ("200" if dd < 0 else "409")
                    rows.append(prow("v", (odd or {}).get("v", sv), "SEAT_NOT_AVAILABLE" if dd < 0 else "OK", tv - OFF * 1000, int(tv), rej="rule" if dd < 0 else ""))
                    probe409 += dd < 0 and (odd or {}).get("v", sv) == "409"
                    rows.append(prow("c", (odd or {}).get("c", sc_), "OK" if dd < 0 else "HOLD_EXPIRED", tc - OFF * 1000, int(tc)))
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
            "expired_hold_rows": 5 if method == "B" else 0, "v_held_without_hold": kw.get("held_without_hold", 0), "confirmed": 25 + 87,
            "v_reserved_without_confirmed": kw.get("reserved_without_confirmed", 0), "v_confirmed_on_unreserved_seat": 0}
    if kw.get("cons_drop"):
        del cons[kw["cons_drop"]]
    cons.update(kw.get("cons_set", {}))
    wj(d / "consistency.json", cons)
    runs = [] if kw.get("no_reconcile") else [{"source": "inc-app-trg-1s", "started_at": iso(T0 + OFF + 1)}, {"source": "safety-app", "started_at": iso(T0 + OFF + 30)}]
    wj(d / "drift-db.json", {"drift_log_total": 0, "drift_log": [], "reconcile_runs": runs})
    for f in ("before", "after-k6", "end"):
        wj(d / f"drift-{f}.json", {"tick": {"ok": 1, "failed": 0}, "drift_record_failed": 0, "cleanup": {"errors": 0}})
    app_rule = rej_rule + kw.get("app_rule_extra", 0)
    tf_in, tf_out = kw.get("tick_failed_win", 0), kw.get("tick_failed_outside", 0)   # 틱 실패 — 판정 창 안 · 창 끝 뒤(CR14-13)
    for f, rr, tf in (("before", 0, 0), ("window-end", app_rule, tf_in), ("after-k6", app_rule, tf_in + tf_out), ("end", app_rule, tf_in + tf_out)):
        if kw.get("no_act") and f == "after-k6" or kw.get("no_wend") and f == "window-end":
            continue
        snap_t = {"t": iso(w1 + kw.get("wend_lag", 3.0)), "t_end": iso(w1 + kw.get("wend_lag", 3.0) + 0.5)} if f == "window-end" else {}
        wj(d / f"act14-{f}.json", {**snap_t, "rejected": {"rule": rr, "nowait": 0}, "tick": {"ok": 10, "failed": tf}, "reclaim": {"COUNT": 0, "TOTAL_TIME": 0, "MAX": 0},
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
    if plan["kind"] == "main" and not kw.get("no_h4"):
        (d / "timeline-h4.jsonl").write_text("\n".join(json.dumps({"t": iso(T0 + OFF + i / 2), "h4_active": 0, "h4_lock": 0}) for i in range(10)) + "\n")
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
    rep_round2(tmp)
    campaign_rules(tmp)
    campaign_round2(tmp)
    early_check_rules(tmp)


# ---- 코드 리뷰 루프 2(CR14-8 ~ CR14-20) — 회차 판정의 새 분기 ---------------------------------------------------------------------
def rep_round2(tmp):
    base = rep(make_rep(tmp / "r2-ok"))
    check("㉑ 정상 회차에 새 분기 사유·이름표 없음(h4-sampler-missing · warmup-failed · 창 끝 스냅숏)",
          base["status"] == "ok" and not any("h4-sampler-missing" in f for f in base["flags"]) and 0 <= base["metrics"]["wend_lag_s"] <= S.WEND_LAG_MAX_S,
          base["status"] + json.dumps(base["flags"], ensure_ascii=False))
    # CR14-8 — 정합 문턱 열: 없음 · None · 숫자 아님 → end-check-failed(0으로 채우지 않는다)
    for name, kw in (("열 없음", {"cons_drop": "double_sold_seats"}), ("None", {"cons_set": {"multi_active_seats": None}}),
                     ("문자열", {"cons_set": {"v_over_limit_users": "0"}}), ("bool", {"cons_set": {"v_counter_mismatch": False}}),
                     ("새 열 없음(CR14-20)", {"cons_drop": "v_confirmed_on_unreserved_seat"}),
                     ("끝 active_on_reserved 없음(CR14-21)", {"cons_drop": "active_on_reserved_seats"})):
        s = rep(make_rep(tmp / f"r2-cons-{len(name)}-{list(kw)[0]}", **kw))
        check(f"CR14-8 정합 문턱 열 {name} → end-check-failed · 정합 자료 없음", "end-check-failed" in s["reasons"] and "integrity" not in s["violations"],
              s["status"] + json.dumps(s["missing_inputs"], ensure_ascii=False))
    # CR14-9 — 시계 차 rtt_s
    s = rep(make_rep(tmp / "r2-rtt-none", rtt_missing=True))
    check("CR14-9 앞 시계 차 표본 rtt_s 없음 → clock-offset-bad", "clock-offset-bad" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "r2-rtt-neg", rtt=-0.001))
    check("CR14-9 rtt_s 음수 → clock-offset-bad", "clock-offset-bad" in s["reasons"], s["status"])
    # CR14-10 — 첫 선점 409 → 판정 요청 안 나감 → probe-incomplete · 원인 이름표는 남긴다
    s = rep(make_rep(tmp / "r2-setup", setup_reject=("pr1", 3)))
    pm = s["metrics"]["probe"]
    check("CR14-10 탐침 첫 선점 409(5xx 아님) → probe-incomplete · setup_rejected · probe-anomaly 이름표",
          "probe-incomplete" in s["reasons"] and pm["setup_rejected"] and any("첫 선점 409" in x for x in pm["judged_missing"])
          and any("probe-anomaly" in f for f in s["flags"]), s["status"] + json.dumps(pm["judged_missing"], ensure_ascii=False))
    # CR14-11 — PR-3 실행 = 송신된 판정 요청 수
    pr3 = base["metrics"]["probe"]["pr3"]
    check("CR14-11 정상 회차 PR-3 송신 = 계획", pr3["sent"] == pr3["planned"] == 175 and pr3["executed"] == 175, json.dumps(pr3, ensure_ascii=False))
    s = rep(make_rep(tmp / "r2-pr3", skip_judged=("pr3", 5)))
    check("CR14-11 PR-3 판정 요청 하나 안 나감 → 송신 < 계획 · probe-incomplete", s["metrics"]["probe"]["pr3"]["sent"] == 174 and "probe-incomplete" in s["reasons"], s["status"])
    # CR14-12 — 주 셀 H4 표본 파일 없음 → 이름표만
    s = rep(make_rep(tmp / "r2-h4", no_h4=True))
    check("CR14-12 주 셀 timeline-h4.jsonl 없음 → h4-sampler-missing 이름표(유효)", s["status"] == "ok" and any("h4-sampler-missing" in f for f in s["flags"]), s["status"])
    # CR14-13 — 판정 창 끝 스냅숏 · P12 창 안 증분
    s = rep(make_rep(tmp / "r2-wend-none", no_wend=True))
    check("CR14-13 창 끝 스냅숏 없음 → app-metric-missing · P12 틱 실패 판정 불가", "app-metric-missing" in s["reasons"] and "tick_failed" in s["violations"]["p12_unknown"], s["status"])
    s = rep(make_rep(tmp / "r2-wend-early", wend_lag=-1.0))
    check("CR14-13 창 끝 스냅숏이 창 끝보다 이르다 → app-metric-missing", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "r2-wend-late", wend_lag=S.WEND_LAG_MAX_S + 5))
    check("CR14-13 창 끝 스냅숏이 창 끝 + 10초보다 늦다 → app-metric-missing", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "r2-tick-out", tick_failed_outside=2))
    check("CR14-13 창 밖(창 끝 뒤) 틱 실패 → P12 아님 · 기록만",
          s["status"] == "ok" and "tick_failed" not in s["violations"]["p12"] and s["metrics"]["app"]["tick_failed_outside"] == 2 and s["metrics"]["app"]["tick_failed_k6"] == 2,
          json.dumps(s["violations"]["p12"]) + json.dumps(s["metrics"]["app"]))
    s = rep(make_rep(tmp / "r2-tick-in", tick_failed_win=1))
    check("CR14-13 창 안 틱 실패 → P12 tick_failed", s["status"] == "ok" and s["violations"]["p12"].get("tick_failed") == 1, json.dumps(s["violations"]["p12"]))
    # CR14-14 — 예열
    s = rep(make_rep(tmp / "r2-warm-rc", warmup_rc=1))
    check("CR14-14 예열 k6 rc ≠ 0 → warmup-failed", "warmup-failed" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "r2-warm-none", no_warmup=True))
    check("CR14-14 예열 기록 없음 → warmup-failed(무음 통과 아님)", "warmup-failed" in s["reasons"], s["status"])
    wcsv = tmp / "warm.csv.gz"
    with gzip.open(wcsv, "wt") as f:
        f.write(HEAD + "\n" + "\n".join([http(T0, "warm-hold", "201", "hold")] * 3 + [http(T0, "warm-hold", "409", "hold"), cnt("iterations", T0, "hold", "")]) + "\n")
    w = S.warmup_status(wcsv, 0)
    check("CR14-14 예열 상태 분포(이름:상태별 수)", w == {"rc": 0, "by_status": {"warm-hold:201": 3, "warm-hold:409": 1}, "n": 4}, json.dumps(w))
    check("CR14-14 예열 CSV 없음 → 분포 None · rc는 그대로", S.warmup_status(tmp / "nope.csv.gz", 2) == {"rc": 2, "by_status": None, "n": None})
    # CR14-19 — P7 재선점 기회 넓은 값 · 좁힌 값(재선점 = 앞 만료 + 3초 < 배치 주기 10초)
    fl = base["metrics"]["flow"]
    check("CR14-19 재선점 기회 넓은 값 · 좁힌 값 둘 다", fl["rehold_other"] == 25 and fl["rehold_other_narrow"] == 25, json.dumps(fl, ensure_ascii=False)[:200])
    # CR14-20 — 좌석 RESERVED ↔ 확정 짝 위반 → 정합 위반
    s = rep(make_rep(tmp / "r2-resv", reserved_without_confirmed=1))
    check("CR14-20 v_reserved_without_confirmed > 0 → 정합 위반(유효 회차의 방식 실패 자료)",
          s["status"] == "ok" and s["violations"]["integrity"].get("reserved_without_confirmed") == 1, json.dumps(s["violations"].get("integrity")))
    # CR14-22 — 첫 선점 기록 행 자체가 없음 → 판정 요청이 있어도 정상 탐침으로 판정하지 않는다 → probe-incomplete
    for pr, sp in (("pr1", "h0"), ("pr2", "h0a"), ("pr2", "h0b"), ("pr3", "u0")):
        s = rep(make_rep(tmp / f"r3-nosetup-{pr}-{sp}", setup_row_drop=(pr, 4, sp)))
        pm = s["metrics"]["probe"]
        check(f"CR14-22 {pr} 첫 선점 {sp} 기록 없음 → probe-incomplete · judged_missing",
              "probe-incomplete" in s["reasons"] and any(f"{pr}#4:첫 선점 {sp} 기록 없음" in x for x in pm["judged_missing"]),
              s["status"] + json.dumps(pm["judged_missing"], ensure_ascii=False))
    # CR14-23 — 판정 요청 응답이 예상 밖(400·404) → 판정 불가 응답 → 분모에서 빠지고 probe-incomplete
    for name, odd in (("PR-1 h1 400", {("pr1", 2): {"h1": "400"}}), ("PR-2 h1 404", {("pr2", 3): {"h1": "404"}}),
                      ("PR-3 v 400", {("pr3", 6): {"v": "400"}}), ("PR-3 c 404", {("pr3", 7): {"c": "404"}})):
        s = rep(make_rep(tmp / f"r3-odd-{name.replace(' ', '-')}", judged_odd=odd))
        pm = s["metrics"]["probe"]
        check(f"CR14-23 {name} → 판정 불가 응답 1 · probe-incomplete · probe-anomaly 이름표 · M3 분모에서 빠짐",
              "probe-incomplete" in s["reasons"] and len(pm["unjudgeable"]) == 1 and any("probe-anomaly" in f for f in s["flags"])
              and (pm["m3"]["den"] == base["metrics"]["probe"]["m3"]["den"] - (1 if "h1" in name else 0)),
              s["status"] + json.dumps(pm["unjudgeable"], ensure_ascii=False) + f" den {pm['m3']['den']}")
    # CR14-21 — 끝 판정기 active_on_reserved_seats > 0 → 정합 위반(모든 방식 기대 0)
    for m in "ABCD":
        s = rep(make_rep(tmp / f"r3-aor-{m}", method=m, cons_set={"active_on_reserved_seats": 2}))
        check(f"CR14-21 {m} 끝 active_on_reserved_seats > 0 → 정합 위반 active_on_reserved_end",
              s["status"] == "ok" and s["violations"]["integrity"].get("active_on_reserved_end") == 2, s["status"] + json.dumps(s["violations"].get("integrity")))
    check("CR14-21 정상 회차 → active_on_reserved_end 위반 없음", "active_on_reserved_end" not in base["violations"]["integrity"], json.dumps(base["violations"]["integrity"]))


# ---- 캠페인 판정(가짜 회차 요약) ----------------------------------------------------------------------------------------------------
def fake(m, c, r, **o):
    t95 = o.get("t95", {}).get(m, 100.0) + r * 0.5
    cost = o.get("cost", {}).get(m, 10.0) + r * 0.1
    s = {"status": "ok", "reasons": [], "metrics": {"rep": r, "method": m, "cell": c,
         "state": {"T95": t95, "W": (50.0 if m == "A" else 0.0) if o.get("w") is None else o["w"], "L_A": 5.0, "c_pct": 1.0, "expired_end_window": 10},
         "probe": {"m3": {"n409": o.get("m3", {}).get(m, 10 if m == "A" else 0), "batch": o.get("m3b", {}).get(m, 0), "den": 100,
                          "unfair_d13": o.get("a_unfair", 5) if m == "A" else 0, "by_delta": {}},
                   "pr3": {"sent": o.get("pr3_sent", {}).get((m, c, r), 100), "executed": 100, "planned": 100}},
         "p99": {"cost_hold": cost, "hold_201": cost, "confirm_200": cost, "seatmap_200": cost}, "db_cpu": {"mean": o.get("cpu", {}).get(m, 100.0) + r},
         "flow": {"rehold_other": o.get("rehold", 5), "rehold_other_narrow": 2, "abandoned": 10, "revisit": {"n201": 1}},
         "app": {"kept_active": o.get("kept", {}).get(m, 3)}},
         "violations": {"integrity": dict(o.get("integ", {}).get((m, c), {})), "state": [{"row": x, "why": "selftest"} for x in o.get("state", {}).get((m, c), [])],
                        "p12": dict(o.get("p12", {}).get((m, c), {})), "p12_unknown": [], "p4_judge409": 0,
                        "p9_early_not200": o.get("p9", {}).get((m, c), 0), "p6": {"violated": m in o.get("p6v", "")} if m == "D" else None}}
    if o.get("none_t95") == (m, c, r):
        s["metrics"]["state"]["T95"] = None
    if (m, c, r) in o.get("none_cost", ()):
        s["metrics"]["p99"]["cost_hold"] = None   # 유효 회차의 비용 지표 None → G3 판정 불가(CR14-18)
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


def campaign_round2(tmp):
    """측정-7 성공 · 측정-8 관문의 새 분기(코드 리뷰 CR14-15 · CR14-11 · CR14-18). 기준 = A의 T95가 길어 G2(a)가 서는 캠페인."""
    r = verdict((write_campaign(tmp / "k1", t95={"A": 140}, kept={"C": 0}) or tmp / "k1"))
    check("CR14-15 P5 되돌림 기회 0(3회 합) → C 판정 불가(성공 아님) · B·D 그대로", r["success"]["C"]["verdict"] == "판정 불가"
          and any("P5: 되돌림 기회 0" in u for u in r["success"]["C"]["undecidable"]) and r["success"]["D"]["verdict"] == "성공", json.dumps(r["success"]["C"], ensure_ascii=False))
    r = verdict((write_campaign(tmp / "k2", t95={"A": 140}, p6v="D") or tmp / "k2"))
    check("CR14-15 P6 반증 → D 실패 · 결론은 다음 순위(B)", r["success"]["D"]["verdict"] == "실패" and any("P6 반증" in f for f in r["success"]["D"]["fails"])
          and r["decision"]["new_default"] == "lazy", r["decision"]["verdict"])
    r = verdict((write_campaign(tmp / "k3", t95={"A": 140}, p9={("B", "a20-t30"): 1}) or tmp / "k3"))
    check("CR14-15 P9 반증(만료 전 확정 거절) → B 실패", r["success"]["B"]["verdict"] == "실패" and any("P9 반증" in f for f in r["success"]["B"]["fails"]),
          json.dumps(r["success"]["B"], ensure_ascii=False))
    r = verdict((write_campaign(tmp / "k4", t95={"A": 140}, integ={("C", "a50-t30"): {"double_sold": 1}}) or tmp / "k4"))
    check("CR14-15 정합 위반 1건 → C 실패", r["success"]["C"]["verdict"] == "실패" and any("정합 위반" in f for f in r["success"]["C"]["fails"]),
          json.dumps(r["success"]["C"], ensure_ascii=False))
    # G2(c): T95 같음(a 아님) · A 탐침 부당 실패 0(P3 무효 → b 아님) · A에만 정합 위반 → c로 이김
    r = verdict((write_campaign(tmp / "k5", a_unfair=0, integ={("A", "a20-t300"): {"double_sold": 1}}) or tmp / "k5"))
    g = r["gates"]["D"]["G2"]
    check("CR14-15 G2(c) A에만 정합 위반 → (a)·(b) 없이 G2 통과 · 기본값 교체 D",
          g["c"] and not g["a"] and not g["b"] and r["decision"]["new_default"] == "derived" and r["A"]["integrity"], json.dumps(r["gates"]["D"], ensure_ascii=False))
    # 상태 문턱 6·7·8행 — A도 같은 셀에서 어기면 판정 불가(방식 실패 아님) · 5행(D)은 A와 견주지 않는다
    c = "a20-t30"
    r = verdict((write_campaign(tmp / "k6", t95={"A": 140}, state={("A", c): [6, 7, 8], ("B", c): [7], ("C", c): [8], ("D", c): [6]}) or tmp / "k6"))
    ok = all(r["success"][m]["verdict"] == "판정 불가" and r["success"][m]["a_rule_only"] and not r["success"][m]["fails"]
             and any(f"state:{row} {c}: A도 같은 셀" in u for u in r["success"][m]["undecidable"]) for m, row in (("B", 7), ("C", 8), ("D", 6)))
    check("CR14-15 상태 문턱 6·7·8행 'A도 같은 문턱' → B·C·D 판정 불가 · 결론 '판정 불가 → 사용자 보고'",
          ok and r["decision"]["verdict"].startswith("판정 불가"), json.dumps(r["success"], ensure_ascii=False)[:400])
    r = verdict((write_campaign(tmp / "k7", t95={"A": 140}, state={("A", c): [6], ("B", c): [6], ("C", c): [6]}) or tmp / "k7"))
    check("CR14-15 6행은 기대 0인 방식(A·D)만 문턱 — B·C의 6행은 문턱 아님(성공)", r["success"]["B"]["verdict"] == "성공" and r["success"]["C"]["verdict"] == "성공",
          json.dumps(r["success"], ensure_ascii=False)[:300])
    r = verdict((write_campaign(tmp / "k8", t95={"A": 140}, state={("A", c): [5], ("D", c): [5]}) or tmp / "k8"))
    check("CR14-15 D 5행은 A와 견주지 않는다 → A가 같은 셀에서 어겨도 D 실패", r["success"]["D"]["verdict"] == "실패"
          and any("A와 견주지 않는 칸" in f for f in r["success"]["D"]["fails"]), json.dumps(r["success"]["D"], ensure_ascii=False))
    # CR14-11 — PR-3 실행(송신된 판정 요청) < 계획인 유효 회차 → P7·P9 판정 불가(메모가 아니다)
    r = verdict((write_campaign(tmp / "k9", t95={"A": 140}, pr3_sent={("B", "a20-t30", 2): 99}) or tmp / "k9"))
    check("CR14-11 PR-3 실행 < 계획 → B 판정 불가(P7·P9) · 다른 후보 그대로", r["success"]["B"]["verdict"] == "판정 불가"
          and any("PR-3 실행" in u for u in r["success"]["B"]["undecidable"]) and r["success"]["D"]["verdict"] == "성공", json.dumps(r["success"]["B"], ensure_ascii=False))
    check("CR14-19 캠페인 메모에 P7 재선점 기회 넓은 값 · 좁힌 값", any("좁힌 값" in n and "넓은 값(판정) 60" in n for n in r["success"]["D"]["notes"]),
          json.dumps(r["success"]["D"]["notes"], ensure_ascii=False))
    # CR14-18 — G3 판정 불가
    r = verdict((write_campaign(tmp / "k10", t95={"A": 140}, none_cost={(m, "S4c4200", 1) for m in "BCD"}) or tmp / "k10"))
    v = r["decision"]["verdict"]
    check("CR14-18 G2를 넘은 후보가 모두 G3 판정 불가 → '판정 불가 → 사용자 보고'(A 유지 ③ 아님)",
          v.startswith("판정 불가") and "ⓒ" in v and not v.startswith("A 유지") and r["decision"]["new_default"] is None
          and all(r["gates"][m]["G3"]["undecidable"] and not r["gates"][m]["G3"]["hits"] for m in "BCD"), v)
    r = verdict((write_campaign(tmp / "k11", t95={"A": 140}, none_cost={("D", "S4c4200", 1)}) or tmp / "k11"))
    v = r["decision"]["verdict"]
    check("CR14-18 1순위(D) G3 판정 불가 · 2순위(B) 관문 통과 → '판정 불가 → 사용자 보고'(잠정 B)",
          r["ranked"][0] == "D" and v.startswith("판정 불가") and "잠정: B" in v and "G3 판정 불가 D" in v and r["decision"]["new_default"] is None, v)
    r = verdict((write_campaign(tmp / "k12", t95={"A": 140}, none_cost={("C", "S4c4200", 1)}) or tmp / "k12"))
    check("CR14-18 뒤 순위(C)만 G3 판정 불가 → 1순위(D)가 그대로 교체", r["decision"]["new_default"] == "derived", r["decision"]["verdict"])


def early_check_rules(tmp):
    """--early-check(측정-9 '캠페인 멈춤'): 회차 1 TTL 30초 두 묶음 A의 W = 0 또는 PR-1 δ ∈ {1, 3} 부당 실패 0 → exit 1."""
    def run_e(name, w=50.0, n409=3, status="ok"):
        root = tmp / f"early-{name}"
        for c in S.TTL_GROUPS["t30"]:
            d = root / c / "A" / "rep1"
            d.mkdir(parents=True, exist_ok=True)
            (d / "status").write_text(status + "\n")
            wj(d / "rep-summary.json", {"metrics": {"state": {"W": w}, "probe": {"m3": {"by_delta": {"pr1:1.0": {"n409": n409}, "pr1:20.0": {"n409": 0}}}}}})
        return S.early_check(root)
    check("CR14-15 --early-check 정상(W > 0 · 부당 실패 > 0) → 0", run_e("ok") == 0)
    check("CR14-15 --early-check A W = 0 → 1(캠페인 멈춤)", run_e("w0", w=0.0) == 1)
    check("CR14-15 --early-check A PR-1 δ1·δ3 부당 실패 0 → 1", run_e("u0", n409=0) == 1)
    check("CR14-15 --early-check 회차가 유효 아님 → 판정하지 않는다(0)", run_e("inv", w=0.0, status="invalid:gc-log-missing") == 0)


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
