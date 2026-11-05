#!/usr/bin/env python3
"""ADR-015 하네스 단위 확인(서버 없음) — 계획(plan15) · 설정 대조 · compose db.command 대조 · CSV 읽기 · 회차 관문(결측 → 무효) · 캠페인 판정 분기 · #5 판정.
    python3 k6/ADR-015/scripts/selftest.py [--campaign]   # --campaign = campaign.sh --simulate 루프(30자리 · 재측정 상한 · --check)까지
exit 0 = 모두 통과 · 1 = 실패(실패 항목을 표준 오류에)."""
import gzip
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
sys.path.insert(0, str(HERE))
import plan15  # noqa: E402
import summarize as S  # noqa: E402

FAILS = []


def check(name, cond, detail=""):
    print(("ok   " if cond else "FAIL ") + name + (f" — {detail}" if detail and not cond else ""))
    if not cond:
        FAILS.append(name)


# ---- 계획 ----
rows = plan15.order()
check("order 30자리", len(rows) == 30)
groups = {}
for _, r, c, m in rows:
    groups.setdefault((r, c), []).append(m)
check("묶음마다 다섯 모드 한 번씩", all(sorted(v) == sorted(plan15.MODES) for v in groups.values()))
pos = {m: set() for m in plan15.MODES}
for v in groups.values():
    for i, m in enumerate(v):
        pos[m].add(i)
check("회전 — 모드마다 서로 다른 자리 ≥ 4", all(len(p) >= 4 for p in pos.values()), str(pos))
for m in plan15.MODES:
    for c in plan15.GROUPS:
        p = plan15.render(m, c, 1)
        check(f"render {m}/{c} — V14는 optimistic만", (plan15.FLYWAY_V14 in p["flyway_locations"]) == (m == "optimistic"))
        check(f"render {m}/{c} — ④ 키는 conditional-extend만", (p["app_env"]["PAYMENT_WINDOW"] != "0s") == (m == "conditional-extend")
              and p["k6"]["env"]["PAY_START"] == ("1" if m == "conditional-extend" else "0"))
        check(f"render {m}/{c} — 좌석 충분", p["seed"]["schedules"] * plan15.SEATS_PER_SCHEDULE >= p["seed"]["sessions_max"])
check("race 풀 점유 추정 < 풀 50%", plan15.render("current", "race", 1)["pool_busy_est"] < plan15.POOL * 0.5)
px, pc = plan15.render("conditional-extend", "race", 1), plan15.render("conditional-extend", "cost", 1)
check("M15-5 ④ race = 경계 흐름 0.5 · gracefulStop 160초 · cost = 0 · 50초",
      px["k6"]["env"]["EXT_BOUNDARY_F"] == "0.5" and px["grace_s"] == 160 and pc["k6"]["env"]["EXT_BOUNDARY_F"] == "0.0" and pc["grace_s"] == 50, f"{px['grace_s']} {pc['grace_s']}")
check("M15-5 다른 모드 race는 경계 흐름 없음", plan15.render("conditional", "race", 1)["k6"]["env"]["EXT_BOUNDARY_F"] == "0.0")
pr = plan15.render("current", "race", 1)
check("M15-6 race 재선점 조밀 구간이 경합 창 [0, delay]을 덮는다", pr["rehold_dense_ms"][0] <= 0 and pr["rehold_dense_ms"][1] > 0 and pr["rehold_dense_ms"][2] >= pr["delay_ms"], str(pr["rehold_dense_ms"]))
check("M15-6 cost는 조밀 구간 끔(step 0)", plan15.render("current", "cost", 1)["rehold_dense_ms"][1] == 0)
check("dry-run 문제 없음", not plan15.dry_run({})["problems"])
check("M15-3b knobs pgss 0 → dry-run 문제(② 경합 표지 판정 불가)", any("pgss=0" in x for x in plan15.dry_run({"pgss": 0})["problems"]))
try:
    plan15.load_knobs('{"cost_rate": 250}')
    check("knobs 범위 밖 거부", False)
except SystemExit:
    check("knobs 범위 밖 거부", True)
check("knobs cost_rate 100 반영", plan15.render("current", "cost", 1, plan15.load_knobs('{"cost_rate": 100}'))["rate"] == 100)

# ---- 설정 대조 ----
tmp = Path(tempfile.mkdtemp())


def fake_cfg(mode, delay="PT0.2S", nofile=65536, locs=None, pool=(10,)):
    ext = mode == "conditional-extend"
    return {"configprops": [
        {"prefix": "seat.confirm", "properties": {"mode": plan15.MODE_ENUM[mode], "loadtest": {"delay": delay}, "paymentWindow": "PT2M" if ext else "PT0S",
                                                  "extendMinRemaining": "PT5S" if ext else "PT0S"}},
        {"prefix": "seat.hold", "properties": {"expiryMode": "SWEEP", "ttl": "PT30S", "expiryInterval": "PT10S", "strategy": "PESSIMISTIC_NOWAIT",
                                               "limitStrategy": "COUNTER_UPSERT_PRECHECK"}},
        {"prefix": "spring.flyway", "properties": {"locations": locs or plan15.flyway(mode)}},
        {"prefix": "server", "properties": {"tomcat": {"maxConnections": 20000}}}],
        "app_nofile": {"soft": nofile, "hard": nofile}, **({"hikaricp.connections.max": list(pool)} if pool is not None else {})}


f = tmp / "cfg.json"
f.write_text(json.dumps(fake_cfg("conditional-extend")))
rc, out = plan15.config_check("conditional-extend", "race", f)
check("설정 대조 일치 → 0", rc == 0, json.dumps(out, ensure_ascii=False))
f.write_text(json.dumps(fake_cfg("conditional")))
rc, out = plan15.config_check("pessimistic", "race", f)
check("모드 다름 → confirm-mode-mismatch", rc == 1 and "confirm-mode-mismatch" in out["reasons"], json.dumps(out, ensure_ascii=False))
f.write_text(json.dumps(fake_cfg("current", delay="PT0S")))
rc, out = plan15.config_check("current", "race", f)
check("지연 다름(race 200ms 기대) → cond-config-mismatch", rc == 1 and "cond-config-mismatch" in out["reasons"])
f.write_text(json.dumps(fake_cfg("optimistic", locs=plan15.FLYWAY)))
rc, out = plan15.config_check("optimistic", "cost", f)
check("optimistic인데 V14 위치 없음 → 불일치", rc == 1)
for pool, want in (((10,), 0), ((20,), 1), (None, 1), (("10",), 1)):
    f.write_text(json.dumps(fake_cfg("current", pool=pool)))
    rc, out = plan15.config_check("current", "race", f)
    check(f"M15-24 Hikari 풀 실측 {pool} → rc {want}", rc == want and (want == 0 or any("hikaricp" in x for x in out["detail"])), json.dumps(out, ensure_ascii=False))
pr_, pc_ = plan15.render("current", "race", 1), plan15.render("current", "cost", 1)
check("M15-20 재선점 비율 race 0.5 · cost 0(루프 2 — cost만 끔) · cost rehold VU 최소 20",
      pr_["k6"]["env"]["RHO"] == "0.5" and pc_["k6"]["env"]["RHO"] == "0.0" and pc_["k6"]["pre_vus"]["rehold"] == 20, f"{pr_['k6']['env']['RHO']} {pc_['k6']['env']['RHO']} {pc_['k6']['pre_vus']}")
check("M15-26 B3 대기 표본 race 20ms(다섯 모드 같게) · cost 끔", all(plan15.render(m, "race", 1)["b3wait_ms"] == 20 and plan15.render(m, "cost", 1)["b3wait_ms"] == 0 for m in plan15.MODES))

# ---- compose db.command = ADR-009 + shared_preload_libraries ----
c9 = (REPO / "k6/ADR-009/compose.yml").read_text()
c15 = (REPO / "k6/ADR-015/compose.confirm.yml").read_text()
cmd = lambda s: re.search(r'command:\s*(\[.*?\])', s, re.S).group(1)   # noqa: E731
base = [x for x in re.findall(r'"([^"]*)"', cmd(c9))]
mine = [x for x in re.findall(r'"([^"]*)"', cmd(c15))]
check("compose db.command = ADR-009 목록 + preload 한 쌍", mine[:len(base)] == base and mine[len(base):] == ["-c", "shared_preload_libraries=${PG_PRELOAD:-}"], f"{mine}")

# ---- CSV 읽기 ----
HEAD = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"


def row(m, t, v, name="", sc="main", st="", ec="", extra=""):
    return f"{m},{t},{v},,,{ec},,,POST,{name},HTTP/1.1,{sc},,{st},,,u,{extra},"


lines = [HEAD,
         row("http_req_duration", 100, 12.0, "hold", st="201"), row("http_req_duration", 101, 210.0, "confirm", st="200"),
         row("http_req_duration", 101, 5.0, "confirm", st="500"), row("http_req_duration", 102, 30000.0, "confirm", st="0", ec="1050"),
         row("http_req_duration", 102, 1.0, "rehold", sc="rehold", st="0", ec="1000"),
         row("conf", 101, 1, extra="cb=win&dup=0&st=200&code=OK&seat=7"), row("conf", 101, 1, extra="cb=post&dup=0&st=500&code=HTTP500&seat=8"),
         row("rehold_ok", 103, 1, sc="rehold", extra="seat=7&k=3"), row("dropped_iterations", 105, 1, sc="rehold"),
         row("http_req_duration", 900, 9.0, "hold", st="201"),
         row("hold_fail", 101, 1, extra="st=409&code=SEAT_NOT_AVAILABLE"), row("hold_fail", 101, 1, extra="st=500&code=HTTP500"),
         row("hold_fail", 101, 1, extra="st=0&code=HTTP0"), row("hold_fail", 101, 1, extra="st=body&code=NOBODY")]
cp = tmp / "k6.csv.gz"
with gzip.open(cp, "wt") as g:
    g.write("\n".join(lines) + "\n")
cd = S.read_csv(cp, 100.0, 200.0)
check("CSV — 5xx · 시간 초과는 err(방식)", cd["err"].get("confirm") == 2, str(cd["err"]))
check("M15-3a CSV — 5xx(err5)와 시간 초과(timeout)를 따로 센다", cd["err5"].get("confirm") == 1 and cd["timeout"].get("confirm") == 1, f"{cd['err5']} {cd['timeout']}")
check("M15-12 CSV — hold_fail(G-H)은 4xx · 본문 없음만 · 5xx · 코드 0은 hold_fail_other", cd["hold_fail"] == {"409:SEAT_NOT_AVAILABLE": 1, "body:NOBODY": 1}
      and cd["hold_fail_other"] == {"500:HTTP500": 1, "0:HTTP0": 1}, f"{cd['hold_fail']} {cd['hold_fail_other']}")
check("CSV — 시간 초과 아닌 코드 0은 code0_other(하네스)", cd["code0_other"] == {"rehold:1000": 1}, str(cd["code0_other"]))
check("CSV — 창 밖 행은 지연에서 빠짐", len(cd["lat"].get("hold:201", [])) == 1)
check("CSV — dropped", cd["dropped"] == {"rehold": 1})
check("CSV — API 수준 이중 결과 재료(좌석 7 확정 OK + 재선점 201)", {c["seat"] for c in cd["conf"] if c.get("code") == "OK"} & {r["seat"] for r in cd["rehold_ok"]} == {"7"})

# ---- 회차 관문: 입력이 없으면 ok가 아니다 ----
rd = tmp / "race" / "conditional" / "rep1"
rd.mkdir(parents=True)
(rd / "plan.json").write_text(json.dumps(plan15.render("conditional", "race", 1)))
(rd / "harness-reasons.txt").write_text("")
s = S.rep_summary(rd)
check("입력 결측 회차 → invalid(0으로 채우지 않음)", s["status"].startswith("invalid:") and "k6-csv-missing" in s["status"], s["status"])

# ---- 회차 지표(합성 회차 폴더 — 결측이 많아 status는 invalid지만 지표 · 실패 계산은 그대로 돈다) ----


def synth_rep(mode, conf_rows=(), rehold_rows=(), pay_rows=(), extra_http=(), act=None, act_w=None, pgss=None, cell="race", clock=True, sess=None, skip=(), body_bad=(),
              b3w=None, client=None):
    """conf_rows = (t, cb, dup, st, code, seat, fl, ts, te[, e]) · rehold_rows = (seat, t) · pay_rows = (st, code, fl) · act = (before, end) 덮어쓰기 dict ·
    clock = 시계 차 파일(오차 = max(|0 − 0|, |0 − 1|) + 2/2 + 1 = 3ms) · sess = [(fl, n)](None = conf 줄에서 맞춰 만든다) · skip = [(fl, n)] · body_bad = [fl] ·
    b3w = B3 대기 표본 줄 목록(None = 파일 없음) · client = timeline-client.jsonl 줄 목록(None = 파일 없음)."""
    d = Path(tempfile.mkdtemp()) / cell / mode / "rep1"
    d.mkdir(parents=True)
    pl = plan15.render(mode, cell, 1)
    pl["pgss"] = 0 if pgss is None else 1
    (d / "plan.json").write_text(json.dumps(pl))
    (d / "harness-reasons.txt").write_text("")
    (d / "k6-summary.json").write_text(json.dumps({"setup_data": {"startedAt": 100000}}))
    ln = [HEAD]
    auto = {}
    for cr in conf_rows:
        t, cb, dup, st, code, seat, fl, ts_, te_ = cr[:9]
        e_ = cr[9] if len(cr) > 9 else 10 ** 9
        ln.append(row("http_req_duration", t, 5.0, "confirm", st=st, ec="1050" if st == "0" else ""))
        ln.append(row("conf", t, 1, extra=f"cb={cb}&dup={dup}&st={st}&code={code}&seat={seat}&fl={fl}&ts={ts_}&te={te_}&e={e_}"))
        auto[fl] = auto.get(fl, 0) + 1
    for fl, n in (sess if sess is not None else list(auto.items())):
        ln.append(row("sess", 100, 1, extra=f"fl={fl}&n={n}"))
    for fl, n in skip:
        ln.append(row("conf_skip", 100, 1, extra=f"fl={fl}&n={n}"))
    for fl in body_bad:
        ln.append(row("pay_body_bad", 100, 1, extra=f"fl={fl}"))
    for seat, t in rehold_rows:
        ln.append(row("http_req_duration", 105, 3.0, "rehold", sc="rehold", st="201"))
        ln.append(row("rehold_ok", 105, 1, sc="rehold", extra=f"seat={seat}&k=3&ph=d&t={t}"))
    for st, code, fl in pay_rows:
        ln.append(row("http_req_duration", 102, 3.0, "pay_start", st=st))
        ln.append(row("pay", 102, 1, extra=f"st={st}&code={code}&fl={fl}"))
    for name, st in extra_http:
        ln.append(row("http_req_duration", 101, 3.0, name, st=st))
    with gzip.open(d / "k6-requests.csv.gz", "wt") as g:
        g.write("\n".join(ln) + "\n")
    base = {"unique_violation": 0, "unexpected": 0, "tick": {"ok": 0, "failed": 0}, "extension_lost": 0, "server_5xx": 0,
            "converted_after_expiry": {"db": 0, "app": 0}, "lateness": {"db": {"COUNT": 0, "TOTAL_TIME": 0, "MAX": 0}},
            "extend": {"extended": 0, "already": 0, "not_needed": 0, "too_late": 0}, "revert_sweep": {"reverted": 0, "not_held": 0, "kept_active": 0}}
    b0, b1 = (act or ({}, {}))
    (d / "act15-before.json").write_text(json.dumps({**base, **b0}))
    (d / "act15-end.json").write_text(json.dumps({**base, **b1}))
    (d / "act15-window-end.json").write_text(json.dumps({**base, **(act_w or b1)}))
    if pgss is not None:
        (d / "pgss-end.json").write_text(json.dumps({"rows": pgss}))
    if clock:
        (d / "clock-offset.json").write_text(json.dumps({"before": {"offset_s": 0.0, "rtt_s": 0.002}, "after": {"offset_s": 0.001, "rtt_s": 0.002}}))
        (d / "k6-window.json").write_text(json.dumps({"exit": 0, "clock_offset_ms_given": 0}))
    if b3w is not None:
        (d / "timeline-b3wait.jsonl").write_text("".join(json.dumps(x) + "\n" for x in b3w))
    if client is not None:
        (d / "timeline-client.jsonl").write_text("".join(json.dumps(x) + "\n" for x in client))
    return S.rep_summary(d)


# M15-3a current: win 단독 5xx만 증거(짝 · post 5xx · 시간 초과는 아님) + 틱 실패
rs = synth_rep("current", conf_rows=[(101, "win", "0", "500", "HTTP500", 1, "b", 1, 2), (101, "win", "1", "500", "HTTP500", 2, "b", 1, 2),
                                     (101, "post", "0", "500", "HTTP500", 3, "b", 1, 2), (101, "win", "0", "0", "HTTP0", 4, "b", 1, 2)],
               act=({"tick": {"ok": 0, "failed": 0}}, {"tick": {"ok": 5, "failed": 2}}))
m_ = rs["metrics"]
check("M15-3a current 경합 증거 = win 단독 5xx 1 + 틱 실패 2 = 3(짝 · post 5xx · 시간 초과 제외)", m_["contention_evidence"] == 3 and m_["evidence_parts"] == {"win_single_5xx": 1, "tick_failed": 2},
      json.dumps(m_.get("evidence_parts")))
check("M15-3a current M2 = 상태 ≥ 500만 3 · 시간 초과 1은 따로 · G-F timeout 기록", m_["confirm_5xx"] == 3 and m_["confirm_timeout"] == 1 and rs["fail"].get("timeout") == {"confirm": 1},
      f"{m_['confirm_5xx']} {m_['confirm_timeout']} {rs['fail']}")
rs = synth_rep("current", conf_rows=[(101, "win", "0", "0", "HTTP0", 4, "b", 1, 2)], act=({}, {}))
check("M15-3a current 시간 초과만 → 경합 증거 0(무관 신호로 성립하지 않음)", rs["metrics"]["contention_evidence"] == 0, json.dumps(rs["metrics"].get("evidence_parts")))
# M15-26 새 모드 ① · ③ 양성 대조 = 서버 논리 증거(404 HOLD_NOT_FOUND ∧ te + 오차(3) − 지연(200) + 여유(20) < e ∧ te − ts ≥ 지연) — 단독 · 기준 흐름만
NF = ("404", "HOLD_NOT_FOUND")
cr = [(101, "win", "0", *NF, 1, "b", 1000, 1210, 1500),     # 증거: 1210 + 3 − 200 + 20 = 1033 < 1500 · 지연을 잤다
      (101, "win", "0", *NF, 2, "b", 1290, 1490, 1500),     # e 직전 송신: 1490 + 3 − 200 + 20 = 1313 < 1500 → 증거(:now ≤ te − 지연 = 1290 < e)
      (101, "post", "0", *NF, 3, "b", 1490, 1700, 1500),    # 늦은 도착(:now가 e 뒤일 수 있다): 1523 ≥ 1500 → 아님(계획 묶음 무관)
      (101, "win", "0", *NF, 4, "b", 1000, 1005, 1500),     # ③ O1 경로처럼 지연을 안 잠(te − ts 5 < 200) → 아님
      (101, "win", "2", *NF, 5, "b", 1000, 1210, 1500),     # 짝 진 쪽(N2 정상) → 아님
      (101, "win", "0", "409", "HOLD_EXPIRED", 6, "b", 1000, 1210, 1500),   # 409 → 아님
      (101, "win", "0", "200", "OK", 7, "b", 1000, 1210, 1500)]
rs = synth_rep("conditional", conf_rows=cr, act=({}, {"converted_after_expiry": {"db": 9, "app": 9}}))
check("M15-26 ① 서버 논리 증거 = 2(지연 잔 단독 404 HOLD_NOT_FOUND · te + 오차 − 지연 + 여유 < e) — 늦은 도착 · 지연 안 잠 · 짝 · 409 · 200 제외",
      rs["metrics"]["contention_evidence"] == 2 and rs["metrics"]["clock_err_ms"] == 3, json.dumps({"p": rs["metrics"].get("evidence_parts"), "err": rs["metrics"].get("clock_err_ms")}))
check("M15-26 ① 계획 win 404(루프 1 근사)는 설명 열로만(3건 — 지연 안 잔 404도 세던 값 · 판정 값 2와 다르다)", rs["metrics"]["evidence_desc"]["planned_win_404"] == 3, json.dumps(rs["metrics"].get("evidence_desc")))
check("M15-21 ① 404 HOLD_NOT_FOUND · 409 HOLD_EXPIRED는 계약 쌍 → noncontract 없음", "noncontract" not in rs["fail"], json.dumps(rs["fail"]))
rs = synth_rep("conditional", conf_rows=[cr[0]], clock=False)
check("M15-26 시계 차 파일 없음 → 증거 None(0으로 채우지 않음) · clock-offset-bad", rs["metrics"]["contention_evidence"] is None and "clock-offset-bad" in rs["reasons"], json.dumps(rs["reasons"]))
rs = synth_rep("optimistic", conf_rows=cr[:4])
check("M15-26 ③도 같은 식(지연 안 잔 O1 404는 아님) → 2", rs["metrics"]["contention_evidence"] == 2, json.dumps(rs["metrics"].get("evidence_parts")))
rs = synth_rep("conditional", conf_rows=[(101, "win", "0", "404", "NOCODE", 1, "b", 1000, 1210, 1500), (101, "win", "0", "409", "HOLD_NOT_FOUND", 2, "b", 1000, 1210, 1500),
                                         (101, "win", "0", "403", "HOLD_NOT_OWNED", 3, "b", 1000, 1210, 1500)])
check("M15-21 (상태, code) 쌍: 404 NOCODE · 409 HOLD_NOT_FOUND = 계약 밖 · 403 HOLD_NOT_OWNED = 계약 안 · 증거 0", rs["fail"].get("noncontract") == {"win|single|404:NOCODE": 1, "win|single|409:HOLD_NOT_FOUND": 1}
      and rs["metrics"]["contention_evidence"] == 0, json.dumps({"f": rs["fail"], "p": rs["metrics"].get("evidence_parts")}))
xr = [(101, "win", "0", *NF, 1, "b", 1000, 1210, 1500), (101, "win", "0", *NF, 2, "x", 1000, 1210, 1500), (101, "post", "0", *NF, 3, "x", 1000, 1210, 1500)]
rs = synth_rep("conditional-extend", conf_rows=xr, pay_rows=[("200", "OK", "b"), ("200", "OK", "x"), ("200", "OK", "x")],
               extra_http=[("hold", "201")] * 3, act=({}, {"extend": {"extended": 3, "already": 0, "not_needed": 0, "too_late": 0}}))
check("M15-26 ④ 증거 = 경계 흐름(fl=x)만 · 계획 묶음 무관(post도 서버 식이 서면) → 2 · 기준 흐름 1 제외", rs["metrics"]["contention_evidence"] == 2, json.dumps(rs["metrics"].get("evidence_parts")))
check("M15-4 ④ payment-start 3/3 200 · extended 3 → G-F · pay 사유 없음", not any(k in rs["fail"] for k in ("pay_non200", "extend_mismatch")) and "pay-shortfall" not in rs["reasons"],
      json.dumps({"fail": rs["fail"], "reasons": rs["reasons"]}))
check("M15-23 ④ 흐름별 계획 확정 수 = 실제 → confirm-count-mismatch 없음", "confirm-count-mismatch" not in rs["reasons"], json.dumps(rs["metrics"].get("confirm_count")))
rs = synth_rep("conditional-extend", conf_rows=xr, pay_rows=[("200", "OK", "b"), ("409", "HOLD_EXPIRED", "x")], extra_http=[("hold", "201")] * 3,
               act=({}, {"extend": {"extended": 1, "already": 0, "not_needed": 0, "too_late": 1}}))
check("M15-4 ④ payment-start 보낸 2 < 선점 201 3 → pay-shortfall(G-H)", "pay-shortfall" in rs["reasons"], json.dumps(rs["reasons"]))
check("M15-4 ④ 비200 → G-F pay_non200 · too_late 1 → extend_mismatch", rs["fail"].get("pay_non200") == {"409:HOLD_EXPIRED": 1} and "extend_mismatch" in rs["fail"], json.dumps(rs["fail"]))
rs = synth_rep("conditional-extend", conf_rows=xr, pay_rows=[("200", "OK", "b")] * 3, extra_http=[("hold", "201")] * 3,
               act=({}, {"extend": {"extended": 2, "already": 0, "not_needed": 1, "too_late": 0}}))
check("M15-4 ④ not_needed 1(흐름 구성상 0) → extend_mismatch", "extend_mismatch" in rs["fail"], json.dumps(rs["fail"]))
# M15-23 ④ 200 본문 결함 · 흐름별 확정 수
rs = synth_rep("conditional-extend", conf_rows=xr, pay_rows=[("200", "OK", "b"), ("200", "OK", "x"), ("200", "OK", "x"), ("200", "OK", "x")], extra_http=[("hold", "201")] * 4,
               sess=[("b", 1), ("x", 1), ("x", 1), ("x", 2)], skip=[("x", 2)], body_bad=["x"], act=({}, {"extend": {"extended": 4, "already": 0, "not_needed": 0, "too_late": 0}}))
check("M15-23 ④ 200 본문 expiresAt 못 읽음 → G-F pay_body_bad · 건너뛴 확정(conf_skip n 2)으로 흐름 x 계획 4 = 보냄 2 + 건너뜀 2", rs["fail"].get("pay_body_bad") == {"x": 1}
      and "confirm-count-mismatch" not in rs["reasons"], json.dumps({"f": rs["fail"], "c": rs["metrics"].get("confirm_count")}))
rs = synth_rep("conditional", conf_rows=cr[:2], sess=[("b", 1), ("b", 1), ("b", 2)])
check("M15-23 계획 확정 4 > 실제 2(반복이 확정 전에 끊김) → confirm-count-mismatch(G-H)", "confirm-count-mismatch" in rs["reasons"], json.dumps(rs["metrics"].get("confirm_count")))
rs = synth_rep("conditional", conf_rows=xr[:1], pay_rows=[("200", "OK", "b")], act=({}, {}))
check("M15-4 ④ 밖 모드에서 payment-start → pay-unexpected(G-H)", "pay-unexpected" in rs["reasons"])
# M15-26 ② = B3 대기 표본(B3가 ② P2 뒤 지연 중 확정을 기다림) — pgss B3 max는 설명 열
b3 = lambda mx: [{"q": "DELETE FROM seat_hold h USING unnest($1::bigint[], $2::bigint[]) AS p(schedule_id, user_id) WHERE h.expires_at <= $3", "calls": 20, "total_ms": 300.0, "max_ms": mx}]   # noqa: E731
bw = lambda on, lock=None: [{"t": f"2026-11-05T00:00:00.{i * 20:03d}+00:00", "b3_lock": (lock or on)[i], "b3_on_p2": on[i]} for i in range(len(on))]   # noqa: E731
rs = synth_rep("pessimistic", act=({}, {"tick": {"ok": 20, "failed": 0}}), pgss=b3(0.4), b3w=bw([0, 1, 1, 0, 1, 0]))
check("M15-26 ② 증거 = B3 대기 에피소드 2(연속 표본은 한 번) — pgss B3 max 0.4ms(설명)와 무관", rs["metrics"]["contention_evidence"] == 2 and rs["metrics"]["b3wait"]["on_p2_samples"] == 3,
      json.dumps({"p": rs["metrics"].get("evidence_parts"), "b": rs["metrics"].get("b3wait")}))
rs = synth_rep("pessimistic", act=({}, {"tick": {"ok": 20, "failed": 0}}), pgss=b3(143.0), b3w=bw([0, 0, 0], lock=[1, 1, 0]))
check("M15-26 ② pgss B3 max 143ms · B3 Lock 대기(막은 쪽이 ② P2 아님)만 → 증거 0(근사 표지는 판정에서 뺌)", rs["metrics"]["contention_evidence"] == 0 and rs["metrics"]["pess_b3"]["max_ms"] == 143.0,
      json.dumps(rs["metrics"].get("evidence_parts")))
rs = synth_rep("pessimistic", act=({}, {"tick": {"ok": 20, "failed": 0}}), pgss=b3(143.0))
check("M15-26 ② B3 대기 표본 없음 → 증거 None · b3wait-missing(G-H)", rs["metrics"]["contention_evidence"] is None and "b3wait-missing" in rs["reasons"], json.dumps(rs["reasons"]))
rs = synth_rep("conditional", conf_rows=[cr[0]])
check("M15-26 ② 밖 모드는 B3 대기 표본 없어도 무효가 아니다(설명)", "b3wait-missing" not in rs["reasons"], json.dumps(rs["reasons"]))
# M15-10 계약 밖 코드는 회차 전체
rs = synth_rep("conditional", conf_rows=[(950, "post", "0", "400", "NOCODE", 1, "b", 1, 2), (101, "pre", "0", "200", "OK", 2, "b", 1, 2)])
check("M15-10 창 밖 계약 밖 코드(400)도 G-F noncontract · 분포 M1은 창 안만", rs["fail"].get("noncontract") == {"post|single|400:NOCODE": 1}
      and "post|single|400:NOCODE" not in rs["metrics"]["confirm_dist"], json.dumps({"f": rs["fail"], "d": rs["metrics"]["confirm_dist"]}))
# M15-6 · M15-18 FM1 겹침 = 계획 win ∧ 단독 ∧ (④ fl=x) 확정만 · post · 짝은 설명 열
rs = synth_rep("conditional", conf_rows=[(101, "win", "0", *NF, 7, "b", 1000, 1300), (101, "win", "0", *NF, 8, "b", 1000, 1300),
                                         (101, "post", "0", *NF, 9, "b", 1000, 1300), (101, "win", "1", *NF, 10, "b", 1000, 1300)],
               rehold_rows=[(7, 1200), (8, 1400), (9, 1200), (10, 1200)])
check("M15-18 FM1 겹침 = win 단독만 1(1400 밖 · post · 짝 제외) · 설명 열 fm1_overlap_all 3", rs["metrics"]["fm1_overlap"] == 1 and rs["metrics"]["fm1_overlap_all"] == 3,
      f"{rs['metrics'].get('fm1_overlap')} {rs['metrics'].get('fm1_overlap_all')}")
rs = synth_rep("conditional-extend", conf_rows=[(101, "win", "0", *NF, 7, "b", 1000, 1300), (101, "win", "0", *NF, 8, "x", 1000, 1300)], rehold_rows=[(7, 1200), (8, 1200)],
               pay_rows=[("200", "OK", "b"), ("200", "OK", "x")], extra_http=[("hold", "201")] * 2, act=({}, {"extend": {"extended": 2, "already": 0, "not_needed": 0, "too_late": 0}}))
check("M15-18 ④ FM1 겹침 = 경계 흐름(fl=x)만 1", rs["metrics"]["fm1_overlap"] == 1, str(rs["metrics"].get("fm1_overlap")))
# M15-19 잘못된 거부 = 단독 · te + 오차 < e인데 200 아님(모든 모드)
er = [(101, "pre", "0", *NF, 1, "b", 1000, 1210, 1500), (101, "pre", "0", "500", "NOCODE", 2, "b", 1000, 1210, 1500), (101, "pre", "1", *NF, 3, "b", 1000, 1210, 1500),
      (101, "pre", "0", "200", "OK", 4, "b", 1000, 1210, 1500), (101, "win", "0", *NF, 5, "b", 1300, 1498, 1500)]
for m in ("current", "conditional"):
    rs = synth_rep(m, conf_rows=er)
    check(f"M15-19 {m} 잘못된 거부 = 단독 te + 오차 < e인 404 · 500(짝 · 200 · te + 오차 ≥ e 제외) → G-F early_reject",
          rs["fail"].get("early_reject") == {"404:HOLD_NOT_FOUND": 1, "500:NOCODE": 1} and rs["metrics"]["early_n"] == 3, json.dumps({"f": rs["fail"].get("early_reject"), "n": rs["metrics"].get("early_n")}))
rs = synth_rep("conditional", conf_rows=er[:1], clock=False)
check("M15-19 시계 차 못 세움 → 잘못된 거부 None(G-F에 넣지 않음 — 회차는 clock-offset-bad)", rs["metrics"]["early_reject"] is None and "early_reject" not in rs["fail"])
# M15-25 PC 메모리 하한(ADR-014 이식)
cl = lambda kb, n=122, step=5: [{"t": f"1970-01-01T00:{(100 + i * step) // 60:02d}:{(100 + i * step) % 60:02d}+00:00", "mem_avail_kb": kb} for i in range(n)]   # noqa: E731
rs = synth_rep("conditional", conf_rows=[cr[0]], client=cl(8 * 1024 * 1024))
check("M15-25 PC MemAvailable 8GiB ≥ 4GiB · 표본 창을 덮음 → 사유 없음", not ({"pc-mem-low", "pc-mem-missing"} & set(rs["reasons"])) and rs["metrics"]["pc_mem"]["min_kb"] == 8 * 1024 * 1024, json.dumps(rs["reasons"]))
low = cl(8 * 1024 * 1024)
low[50]["mem_avail_kb"] = 3 * 1024 * 1024
rs = synth_rep("conditional", conf_rows=[cr[0]], client=low)
check("M15-25 PC MemAvailable 최소 3GiB < 4GiB → pc-mem-low(환경)", "pc-mem-low" in rs["reasons"], json.dumps(rs["reasons"]))
rs = synth_rep("conditional", conf_rows=[cr[0]])
check("M15-25 timeline-client.jsonl 없음 → pc-mem-missing", "pc-mem-missing" in rs["reasons"], json.dumps(rs["reasons"]))
rs = synth_rep("conditional", conf_rows=[cr[0]], client=cl(8 * 1024 * 1024, n=10, step=60))
check("M15-25 표본 공백 > 30초 → pc-mem-missing", "pc-mem-missing" in rs["reasons"], json.dumps(rs["reasons"]))
# M15-15 lateness = 창 끝 스냅숏 MAX
rs = synth_rep("conditional", act=({}, {"lateness": {"db": {"COUNT": 4, "TOTAL_TIME": 0.4, "MAX": 0.0}}}), act_w={"lateness": {"db": {"COUNT": 4, "TOTAL_TIME": 0.4, "MAX": 0.19}}})
check("M15-15 lateness 최대 = 창 끝 스냅숏(감쇠 전) 0.19 · 평균 0.1", rs["metrics"]["app"]["lateness_db_wend_max_s"] == 0.19 and abs(rs["metrics"]["app"]["lateness_db_mean_s"] - 0.1) < 1e-9,
      json.dumps(rs["metrics"]["app"]))
# M15-9 #15 배치 문장 묶음 · 틱당
bs = S.batch_statements([{"q": "SELECT DISTINCT schedule_id, user_id FROM seat_hold WHERE expires_at <= $1 ORDER BY schedule_id, user_id", "calls": 10, "total_ms": 2.0, "max_ms": 0.3},
                         {"q": "DELETE   FROM seat_hold h USING unnest($1::bigint[], $2::bigint[]) AS p", "calls": 10, "total_ms": 8.0, "max_ms": 1.0},
                         {"q": "UPDATE user_hold_quota SET cnt = cnt - $1 WHERE schedule_id = $2 AND user_id = $3 AND cnt >= $4", "calls": 30, "total_ms": 10.0, "max_ms": 1.0},
                         {"q": "SELECT status FROM product_seat WHERE id = $1 AND schedule_id = $2", "calls": 999, "total_ms": 99.0, "max_ms": 1.0}] + [{"q": f"x{i}", "calls": 1, "total_ms": 1000.0 - i, "max_ms": 1} for i in range(40)],
                        "a_prime", 10)
check("M15-9 A′ 배치 문장 묶음(상위 N행 무관 · 공백 정규화) 틱당 문장 5 · 2ms", bs["per_tick"] == {"calls": 5.0, "ms": 2.0} and set(bs["classes"]) == {"B1", "B3", "B4"}, json.dumps(bs))
bj = S.batch_statements([{"q": "select distinct ps1_0.id,ps1_0.row_no,ps1_0.schedule_id,ps1_0.seat_no,ps1_0.section,ps1_0.status from product_seat ps1_0 left join seat_hold h1_0 on ps1_0.id=h1_0.seat_id", "calls": 10, "total_ms": 5.0, "max_ms": 1},
                         {"q": "delete from seat_hold where id=$1", "calls": 500, "total_ms": 50.0, "max_ms": 1}], "jpa", 10)
check("M15-9 JPA — 확정과 같은 문장(shared)은 틱당 값에서 뺀다", bj["per_tick"] == {"calls": 1.0, "ms": 0.5} and bj["classes"]["J5-hold-delete"]["shared"] is True, json.dumps(bj))

# ---- 캠페인 판정 분기 ----


def fake_campaign(spec):
    """spec[(cell, mode)] = 회차 지표 dict(세 회차 같은 값 + 회차별 작은 흔들림) · fail dict · nreps(유효 회차 수 — 기본 3) · none(값을 None으로 둘 지표 이름 — rep2만)."""
    root = Path(tempfile.mkdtemp())
    for (c, m), v in spec.items():
        for r in range(1, v.get("nreps", 3) + 1):
            d = root / c / m / f"rep{r}"
            d.mkdir(parents=True)
            met = {"mode": m, "cell": c, "rep": r, "p99": {"confirm_200": v.get("p99c", 10) + r * 0.1, "hold_201": v.get("p99h", 5) + r * 0.1},
                   "db_cpu": v.get("cpu", 100) + r, "confirm_5xx": v.get("x5", 0) + (r if v.get("x5") else 0), "tick_failed": v.get("tf", 0),
                   "contention_evidence": v.get("ev", 5), "fm1_overlap": v.get("fm1", 1)}
            if v.get("none") and r == 2:
                *head, last = v["none"].split(".")
                tgt = met
                for h in head:
                    tgt = tgt[h]
                tgt[last] = None
            (d / "rep-summary.json").write_text(json.dumps({"status": "ok", "metrics": met, "fail": v.get("fail", {}) if r == 1 else {}}))
            (d / "status").write_text("ok\n")
    return root


def base_spec():
    sp = {}
    for c in plan15.GROUPS:
        sp[(c, "current")] = {"x5": 20 if c == "race" else 0, "tf": 3 if c == "race" else 0, "ev": 25}
        for m in plan15.NEW_MODES:
            sp[(c, m)] = {"ev": 4}
    return sp


r = S.campaign(fake_campaign(base_spec()), write=False)
check("모두 이김 · 비용 같음 → 단순성 1위 conditional 교체", r["decision"]["new_default"] == "conditional", r["decision"]["verdict"])
sp = base_spec()
sp[("cost", "conditional")]["cpu"] = 300     # ① 비용 ≫ → 3단에서 빠지고 G3에도 걸림
r = S.campaign(fake_campaign(sp), write=False)
check("① 비용 ≫ → 다음 단순 후보 pessimistic", r["decision"]["new_default"] == "pessimistic", r["decision"]["verdict"])
sp = base_spec()
for m in plan15.NEW_MODES:
    sp[("cost", m)]["cpu"] = 300
r = S.campaign(fake_campaign(sp), write=False)
check("모든 후보 G3 걸림 → current 유지 ③", r["decision"]["new_default"] is None and "③" in r["decision"]["verdict"], r["decision"]["verdict"])
sp = base_spec()
sp[("race", "current")]["x5"] = 0
sp[("race", "current")]["tf"] = 0
sp[("race", "current")]["ev"] = 0
r = S.campaign(fake_campaign(sp), write=False)
check("current 경합 증거 0 → 판정 불가(G4)", r["decision"]["new_default"] is None and "G4" in r["decision"]["verdict"], r["decision"]["verdict"])
sp = base_spec()
sp[("race", "current")]["x5"] = 0
sp[("race", "current")]["tf"] = 0
sp[("race", "current")]["ev"] = 2   # 경합은 있었지만(유니크 위반 등) 5xx · 틱 실패 차이 없음
r = S.campaign(fake_campaign(sp), write=False)
check("이긴 후보 없음 → current 유지 ②", r["decision"]["new_default"] is None and "②" in r["decision"]["verdict"], r["decision"]["verdict"])
sp = base_spec()
sp[("race", "conditional")]["fail"] = {"5xx": {"confirm": 1}}
r = S.campaign(fake_campaign(sp), write=False)
check("① 실패(5xx) → 후보 아님 → pessimistic", r["decision"]["new_default"] == "pessimistic" and not r["candidates"]["conditional"]["G1"], r["decision"]["verdict"])
sp = base_spec()
for m in plan15.NEW_MODES:
    sp[("race", m)]["ev"] = 0
r = S.campaign(fake_campaign(sp), write=False)
check("M15-3b 새 모드 경합 표지 0(경합이 그 모드에서 안 생김) → 후보 실패가 아니라 판정 불가", r["decision"]["new_default"] is None and r["decision"]["verdict"].startswith("판정 불가")
      and all(r["candidates"][m]["status"] == "undetermined" for m in plan15.NEW_MODES), r["decision"]["verdict"])
sp = base_spec()
sp[("race", "conditional")]["fm1"] = 0
r = S.campaign(fake_campaign(sp), write=False)
check("M15-6 ① race FM1 겹침 합 0 → ① 판정 불가(M12 = 0의 뜻이 서지 않음) → 결론 판정 불가", r["candidates"]["conditional"]["status"] == "undetermined"
      and r["decision"]["verdict"].startswith("판정 불가"), r["decision"]["verdict"])
sp = base_spec()
sp[("race", "pessimistic")]["fm1"] = 0
sp[("cost", "conditional")]["cpu"] = 300
r = S.campaign(fake_campaign(sp), write=False)
check("M15-6 ② FM1 겹침 0은 G1에 쓰지 않는다(구조상 0 — 한계) → ① 비용 ≫ · ② 교체", r["candidates"]["pessimistic"]["status"] == "success" and r["decision"]["new_default"] == "pessimistic", r["decision"]["verdict"])
# M15-1 유효 반복 부족(재측정 한도 뒤) = 판정 불가(후보 실패와 구별)
sp = base_spec()
sp[("race", "conditional")]["nreps"] = 2
r = S.campaign(fake_campaign(sp), write=False)
check("M15-1 ① race 유효 2회 → 판정 불가(교체 pessimistic · current 유지가 아님)", r["decision"]["new_default"] is None and r["decision"]["verdict"].startswith("판정 불가")
      and r["candidates"]["conditional"]["status"] == "undetermined" and "유효 반복 부족" in r["candidates"]["conditional"]["why"], r["decision"]["verdict"])
sp = base_spec()
for m in plan15.NEW_MODES:
    sp[("race", m)]["nreps"] = 2
r = S.campaign(fake_campaign(sp), write=False)
check("M15-1 후보 모두 유효 부족 · current만 유효 → 'current 유지 ①'이 아니라 판정 불가", "①" not in r["decision"]["verdict"] and r["decision"]["verdict"].startswith("판정 불가"), r["decision"]["verdict"])
sp = base_spec()
sp[("race", "conditional")]["nreps"] = 2
sp[("race", "conditional")]["fail"] = {"5xx": {"confirm": 1}}
r = S.campaign(fake_campaign(sp), write=False)
check("M15-1 유효 부족이어도 G-F 1건이면 실패(판정 불가 아님) → pessimistic 교체", r["candidates"]["conditional"]["status"] == "fail" and r["decision"]["new_default"] == "pessimistic", r["decision"]["verdict"])
# M15-2 + M15-14 앞 순위 후보 판정 불가 → 뒤 후보 교체 중단
sp = base_spec()
sp[("cost", "conditional")]["none"] = "p99.confirm_200"
r = S.campaign(fake_campaign(sp), write=False)
check("M15-14 ① 3단 지표 None → ① 판정 불가 · M15-2 뒤 후보(pessimistic) 교체 중단 → 판정 불가", r["decision"]["new_default"] is None and r["decision"]["verdict"].startswith("판정 불가")
      and r["decision"]["provisional"] == "pessimistic" and r["candidates"]["conditional"]["status"] == "undetermined", r["decision"]["verdict"])
sp = base_spec()
sp[("cost", "conditional")]["none"] = "p99.confirm_200"
sp[("race", "conditional")]["cpu"] = 300   # ①은 G3에서 확정적으로 걸린다(race DB CPU ≫ current) → 판정 불가여도 막지 않는다(ADR-014 :802)
r = S.campaign(fake_campaign(sp), write=False)
check("M15-2 판정 불가 후보가 G3에서 확정적으로 걸리면 막지 않는다 → pessimistic 교체", r["decision"]["new_default"] == "pessimistic", r["decision"]["verdict"])
sp = base_spec()
sp[("cost", "current")]["none"] = "db_cpu"
r = S.campaign(fake_campaign(sp), write=False)
check("M15-2 current 비용 범위 없음 → 1위 G3 판정 불가 → 'current 유지 ③'이 아니라 판정 불가(앞 순위)", r["decision"]["new_default"] is None and "앞 순위" in r["decision"]["verdict"], r["decision"]["verdict"])
# M15-13 G2(a) A 결함에 40P01 포함
sp = base_spec()
sp[("race", "current")].update({"x5": 0, "tf": 0, "ev": 2, "fail": {"deadlock": 1}})
r = S.campaign(fake_campaign(sp), write=False)
check("M15-13 current 40P01 = A 결함 → G2(a) → 교체 conditional", r["current_integrity_defect"] and r["decision"]["new_default"] == "conditional", r["decision"]["verdict"])

# M15-17 'A도 같은 문턱'(측정-7 — ADR-014 :765 · :766 · :800 ⓐ): 대상 항목(선점 · 재선점 5xx · 시간 초과 · 방식 정지 · 잘못된 거부)은 current도 같은 (항목, 셀)에서 어기면
#   또는 current 그 셀 유효 < 3이면 판정 불가 · 대상 밖(정합 · API 이중 · 40P01 · 확정 5xx · payment-start 등)은 그대로 실패
def a_case(xfail, cfail=None, ccell="race", cnreps=3, xcell="race", xm="conditional"):
    sp = base_spec()
    sp[(xcell, xm)]["fail"] = xfail
    if cfail is not None:
        sp[(ccell, "current")]["fail"] = cfail
    sp[(ccell, "current")]["nreps"] = cnreps
    return S.campaign(fake_campaign(sp), write=False)


for name, kw, want in (
        ("선점 5xx — current도 같은 셀 확정 밖 5xx(선점) → 판정 불가", dict(xfail={"5xx": {"hold": 2}}, cfail={"5xx_non_confirm": {"hold": 1}}), "undetermined"),
        ("시간 초과(확정) — current도 같은 셀 → 판정 불가", dict(xfail={"timeout": {"confirm": 1}}, cfail={"timeout": {"confirm": 3}}), "undetermined"),
        ("방식 정지 — current도 같은 셀 → 판정 불가", dict(xfail={"method_stall": 6}, cfail={"method_stall": 5}), "undetermined"),
        ("잘못된 거부(cost) — current도 cost → 판정 불가", dict(xfail={"early_reject": {"404:HOLD_NOT_FOUND": 1}}, cfail={"early_reject": {"500:NOCODE": 1}}, ccell="cost", xcell="cost"), "undetermined"),
        ("선점 5xx — current는 안 어김 → 실패", dict(xfail={"5xx": {"hold": 2}}), "fail"),
        ("방식 정지 race — current는 cost에서만 어김(다른 셀) → 실패", dict(xfail={"method_stall": 6}, cfail={"method_stall": 5}, ccell="cost"), "fail"),
        ("선점 5xx — current race 유효 2/3(세울 수 없음) → 판정 불가", dict(xfail={"5xx": {"hold": 2}}, cnreps=2), "undetermined"),
        ("확정 5xx — current 기대 > 0(대상 밖) → 실패", dict(xfail={"5xx": {"confirm": 1}}, cfail={"5xx_non_confirm": {"hold": 1}}), "fail"),
        ("정합 — current도 정합 위반(A 결함 · 대상 밖) → 실패", dict(xfail={"integrity": {"double_sold_seats": 1}}, cfail={"integrity": {"double_sold_seats": 1}}), "fail"),
        ("④ payment-start 5xx — current는 그 경로가 없음(대상 밖) → 실패", dict(xfail={"5xx": {"pay_start": 1}}, xm="conditional-extend"), "fail"),
        ("선점 5xx + 확정 5xx — 하나라도 대상 밖 실패면 실패", dict(xfail={"5xx": {"hold": 1, "confirm": 1}}, cfail={"5xx_non_confirm": {"hold": 1}}), "fail")):
    r = a_case(**kw)
    xm = kw.get("xm", "conditional")
    check(f"M15-17 {name}", r["candidates"][xm]["status"] == want, f"{r['candidates'][xm]['status']} · {r['candidates'][xm]['why']} · {r['decision']['verdict']}")
r = a_case(xfail={"5xx": {"hold": 2}}, cfail={"5xx_non_confirm": {"hold": 1}})
check("M15-17 'A도 같은 문턱' 판정 불가 후보(①)가 관문에서 확정적으로 걸리지 않았다 → 결론 판정 불가(교체 ② 아님 — ADR-014 :800 ⓐ)",
      r["decision"]["new_default"] is None and r["decision"]["verdict"].startswith("판정 불가") and "A도 같은 문턱" in r["decision"]["verdict"], r["decision"]["verdict"])
sp = base_spec()
for m in plan15.NEW_MODES:
    sp[("race", m)]["fail"] = {"timeout": {"confirm": 1}}
sp[("race", "current")]["fail"] = {"timeout": {"confirm": 2}}
r = S.campaign(fake_campaign(sp), write=False)
check("M15-17 환경 원인 시간 초과가 다섯 모드 모두 → 'current 유지 ①'(오결론)이 아니라 판정 불가", r["decision"]["verdict"].startswith("판정 불가") and "①" not in r["decision"]["verdict"]
      and all(r["candidates"][m]["status"] == "undetermined" for m in plan15.NEW_MODES), r["decision"]["verdict"])

# ---- #5 판정 ----
vr = Path(tempfile.mkdtemp())
check("M15-8 #5 v12-plan.json 없음 → 판정 불가", S.v12_summary(vr, write=False)["verdict"].startswith("판정 불가"))
(vr / "v12-plan.json").write_text(json.dumps({"rows": [1000000, 5000000], "reps": 3, "block_max_ms": 2000}))
vrec = lambda n, k, blk, **kw: {"rows": n, "rep": k, "seed_rc": 0, "seed": {"rows": n, "seats": n + 1000},   # noqa: E731
                                "tx": {"build_ms": blk, "probe_blocked_ms": blk - 300 + k, "probe_started_inside": True, "valid": True},
                                "concurrently": {"build_ms": blk * 2, "probe_ms": 3, "valid": True}, **kw}
for k in range(1, 4):
    (vr / f"v12-1000000-rep{k}.json").write_text(json.dumps(vrec(1000000, k, 400)))
check("M15-8 #5 계획한 500만 자리가 모두 빠짐 → 100만으로 판정하지 않고 판정 불가", S.v12_summary(vr, write=False)["verdict"].startswith("판정 불가"), S.v12_summary(vr, write=False)["verdict"])
for k in range(1, 4):
    (vr / f"v12-5000000-rep{k}.json").write_text(json.dumps(vrec(5000000, k, 1800)))
check("#5 차단 ≤ 2초 → 트랜잭션 안 판 유지", S.v12_summary(vr, write=False)["verdict"].startswith("트랜잭션 안"), S.v12_summary(vr, write=False)["verdict"])
(vr / "v12-5000000-rep1.json").write_text(json.dumps(vrec(5000000, 1, 1800, seed_rc=3)))
check("M15-7 #5 시드 rc ≠ 0 → 판정 불가", S.v12_summary(vr, write=False)["verdict"].startswith("판정 불가"))
(vr / "v12-5000000-rep1.json").write_text(json.dumps(vrec(5000000, 1, 1800, seed={"rows": 120000, "seats": 5001000})))
check("M15-7 #5 시드 실측 예약 행 수 ≠ N → 판정 불가", S.v12_summary(vr, write=False)["verdict"].startswith("판정 불가"))
(vr / "v12-5000000-rep1.json").write_text(json.dumps(vrec(5000000, 1, 1800)))
(vr / "v12-5000000-rep3.json").write_text(json.dumps(vrec(5000000, 3, 2500, tx={"build_ms": 2500, "probe_blocked_ms": 100, "probe_started_inside": False, "valid": True},
                                                       concurrently={"build_ms": 8000, "probe_ms": 3, "valid": True})))
check("#5 생성 2.5초(탐침은 구간 밖) → CONCURRENTLY 후속", S.v12_summary(vr, write=False)["verdict"].startswith("CONCURRENTLY"))
(vr / "v12-5000000-rep2.json").write_text(json.dumps({"rows": 5000000, "rep": 2, "error": "prepare-failed"}))
check("#5 값 빠짐 → 판정 불가", S.v12_summary(vr, write=False)["verdict"].startswith("판정 불가"))

# ---- 캠페인 루프(선택) ----
if "--campaign" in sys.argv:
    sim = Path(tempfile.mkdtemp())
    sp = sim / "plan.txt"
    sp.write_text("1:race:current:1=invalid:env-stall\n2:cost:optimistic:1=invalid:k6-code0\n2:cost:optimistic:2=invalid:k6-code0\n2:cost:optimistic:3=invalid:k6-code0\n")
    p = subprocess.run(["bash", str(HERE / "campaign.sh"), "--simulate", "--worktree", "--results-root", str(sim), "--id", "t", "--sim-plan", str(sp)],
                       stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=REPO)
    check("campaign --simulate exit 0", p.returncode == 0, p.stderr[-500:])
    check("env-stall 자리 재측정 → ok", (sim / "t/race/current/rep1/status").read_text().strip() == "ok")
    check("하네스 무효 재측정 상한 2번(시도 3)", len(list((sim / "t/cost/optimistic").glob("rep2.attempt*"))) == 2)
    p = subprocess.run(["bash", str(HERE / "campaign.sh"), "--check", str(sim / "t")], capture_output=True, text=True)
    check("campaign --check exit 0", p.returncode == 0, p.stderr)
    shutil.rmtree(sim, ignore_errors=True)
    # M15-11 조기 확인: 회차 1 race current가 무효면 멈추지 않고 재측정(exit 0) · ok인데 증거 0이면 exit 4
    import os
    for plan_txt, want, name in (("1:race:current:1=invalid:k6-code0\n", 0, "M15-11 조기 확인 대상 무효 → exit 4 아님 · 재측정으로 ok"),
                                 ("1:race:current:1=ok;ev=0\n", 4, "M15-11 조기 확인 ok인데 증거 0 → exit 4"),
                                 ("1:race:current:1=ok;ev=3\n", 0, "M15-11 조기 확인 ok · 증거 3 → 계속")):
        sim = Path(tempfile.mkdtemp())
        (sim / "plan.txt").write_text(plan_txt)
        p = subprocess.run(["bash", str(HERE / "campaign.sh"), "--simulate", "--worktree", "--results-root", str(sim), "--id", "t", "--sim-plan", str(sim / "plan.txt")],
                           stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=REPO, env={**os.environ, "CAMPAIGN15_SIM_EARLY": "1"})
        check(name, p.returncode == want, f"rc={p.returncode} {p.stderr[-300:]}")
        shutil.rmtree(sim, ignore_errors=True)

# M15-11 요약기 조기 확인 반환값(0 증거 ≥ 1 · 1 ok인데 0 · 2 무효 — 보류)
for st_txt, evv, want in (("invalid:k6-code0", 5, 2), ("ok", 0, 1), ("ok", 4, 0), ("ok", None, 2)):
    er = Path(tempfile.mkdtemp())
    (er / "race/current/rep1").mkdir(parents=True)
    (er / "race/current/rep1/status").write_text(st_txt + "\n")
    (er / "race/current/rep1/rep-summary.json").write_text(json.dumps({"metrics": {"contention_evidence": evv}}))
    check(f"M15-11 early_check status {st_txt} · 증거 {evv} → {want}", S.early_check(er) == want)
    shutil.rmtree(er, ignore_errors=True)

shutil.rmtree(tmp, ignore_errors=True)
print(f"\n{'통과' if not FAILS else '실패 ' + str(len(FAILS)) + '건: ' + ', '.join(FAILS)}")
sys.exit(1 if FAILS else 0)
