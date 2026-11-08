#!/usr/bin/env python3
"""ADR-016 하네스 구현자 확인(서버 없음 — 측정 명세 §1-3 selftest16 항목): 계획 · 설정 대조(M ≤ θ < min(TTL, W)) · 결정표 전 갈래 · predict = classify(흉내 응답) ·
내림 경계 · 순수 판정 함수 · 회차 무효 사유(합성 회차 — 흉내 서버로 만든 세션 행) · 캠페인 판정 분기 · k6 난수 = plan16 난수(node가 있으면).
    python3 -I k6/ADR-016/scripts/selftest16.py [--campaign]   # --campaign = campaign16.sh --simulate 루프(21자리 · 재측정 상한 · --check · FIXED 거부)
exit 0 = 모두 통과 · 1 = 실패."""
import gzip
import json
import math
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
sys.path.insert(0, str(HERE))
import outcome16 as O  # noqa: E402
import plan16  # noqa: E402
import summarize16 as S  # noqa: E402

FAILS = []


def check(name, cond, detail=""):
    print(("ok   " if cond else "FAIL ") + name + (f" — {detail}" if detail and not cond else ""))
    if not cond:
        FAILS.append(name)


# ==== 계획 ====
rows = plan16.order()
check("order 21회차(paid 2 · race 2 · cost 3 × 3)", len(rows) == 21)
grp = {}
for _, r, c, g in rows:
    grp.setdefault((r, c), []).append(g)
check("회차 × 셀마다 구성 한 번씩", all(sorted(v) == sorted(plan16.CONFIGS[c]) for (r, c), v in grp.items()))
check("셀 순서 paid → race → cost(회차마다)", all([c for _, rr, c, _ in rows if rr == r] == ["paid"] * 2 + ["race"] * 2 + ["cost"] * 3 for r in (1, 2, 3)))
r15 = [m for _, r, c, m in plan16.plan15.order() if c == "race" and m in ("conditional", "conditional-extend")]
check("race 순서 = plan15 race 회전에서 ① · ④만", [plan16.RACE_MODE[g] for _, _, c, g in rows if c == "race"] == r15)
check("cost 회전 — 구성마다 서로 다른 자리 3", all(len({grp[(r, 'cost')].index(g) for r in (1, 2, 3)}) == 3 for g in plan16.CONFIGS["cost"]))
_fx = plan16.FIXED
for v in (False, True):   # 두 상태 모두(리뷰 L1-2 — 고정 커밋으로 FIXED = True가 돼도 selftest가 깨지지 않는다)
    plan16.FIXED = v
    check(f"FIXED = {v} → --fixed exit {0 if v else 1} · render fixed 표지", plan16.main(["x", "--fixed"]) == (0 if v else 1)
          and plan16.render("paid", "ext", 1)["fixed"] is v)
plan16.FIXED = _fx
check("plan16 FIXED 값 = 파일의 표지(True | False)", isinstance(plan16.FIXED, bool))
for c in ("paid", "cost"):
    for g in plan16.CONFIGS[c]:
        p = plan16.render(c, g, 1)
        check(f"render {c}/{g} — 기동 검사 통과(M ≤ θ < min(TTL, W))", p["startup"]["ok"], str(p["startup"]))
        check(f"render {c}/{g} — 흐름 배정 합 = 세션 수 · 좌석 충분", sum(p["planned"].values()) == p["n_total"] and p["seed"]["schedules"] * 50000 >= p["n_total"])
        check(f"render {c}/{g} — gracefulStop ≥ 수명 최대 · 수렴 ≥ max(TTL, W) + 배치", p["grace_s"] >= p["life"]["max_s"]
              and p["converge_s"] >= max(plan16.TTL_S, plan16.W_S if g == "ext" else 0) + plan16.BATCH_S)
        check(f"render {c}/{g} — 미리 만든 VU ≥ 결정적 일정 최대 동시 세션", p["k6"]["pre_vus"]["main"] >= p["peak_concurrency"]["main"])
        check(f"render {c}/{g} — TTL 5분 · 배치 10초 · M 키", p["app_env"]["HOLD_TTL"] == "300s" and p["app_env"]["EXPIRY_INTERVAL"] == "10s"
              and p["app_env"]["PAY_BY_MARGIN"] == f"{plan16.M_S:g}s")
pc = plan16.render("paid", "cond", 1)
NB = plan16.N_PER_FLOW // plan16.FLOW_WEIGHT * plan16.BOUNDARY_WEIGHT
check("paid ① 흐름당 N · 경계 nd · bcd 같은 크기 · 교차 배정(첫 바퀴 = n nc b bc 반복 + nd + bcd — L2-10)",
      all(pc["planned"][f] == plan16.N_PER_FLOW for f in ("n", "nc", "b", "bc")) and pc["planned"]["nd"] == pc["planned"]["bcd"] == NB
      and pc["flow_cycle"][:5] == ["n", "nc", "b", "bc", "n"] and pc["flow_cycle"][-2:] == ["nd", "bcd"] and pc["flows"]["bcd"] == {"b": 1, "c": 1, "bd": 1},
      json.dumps(pc["planned"]))
check("L2-10 후보 → 경계 흐름(cond+c nd · cond+b+c bcd · ext+c ed)", plan16.BOUNDARY_FLOW == {"cond+c": "nd", "cond+b+c": "bcd", "ext+c": "ed"})
check("L2-9 탐침 = ① check 회차만(paid cond · cost cond · cost cond+b)", all((plan16.render(c, g, 1)["probe16"]["holds"] > 0) == (g != "ext")
                                                                   for c in ("paid", "cost") for g in plan16.CONFIGS[c]))
pe = plan16.render("paid", "ext", 1)
check("paid ④ 흐름 e · ec = 흐름당 세션 · 경계 ed = 흐름당 ÷ 4 · W = CAP + M + 배치 + 여유(관계식 — 값이 바뀌어도 성립 · 리뷰 L2-8)",
      pe["planned"] == {"e": plan16.N_PER_FLOW, "ec": plan16.N_PER_FLOW, "ed": plan16.N_PER_FLOW // plan16.FLOW_WEIGHT * plan16.BOUNDARY_WEIGHT}
      and pe["knobs16"]["window"] == plan16.CAP_S + plan16.M_S + plan16.BATCH_S + plan16.W_MARGIN_S and pe["knobs16"]["theta"] == plan16.M_S)
pk = plan16.render("cost", "ext", 1)
check("cost — ρ 0 · 창 = 정상 상태(시작 > 0) · 도착률 = COST_RATE", pk["rho"] == 0 and pk["window"]["kind"] == "steady" and pk["window"]["from_s"] > 0
      and pk["rate"] == plan16.COST_RATE)
DRY = plan16.dry_run()
check("dry-run 문제 없음(자리표시 값)", not DRY["problems"], str(DRY["problems"]))
# 기동 검사 위반을 dry-run이 잡는다(값을 잠시 바꿔 본다)
saved = (plan16.THETA_COND_S, plan16.M_S, plan16.THETA_EXT_S, plan16.W_S)
for th, m, the, w, name in ((300.0, 1.0, 1.0, 611.0, "① θ = TTL"), (69.0, 70.0, 1.0, 611.0, "① M > θ"), (69.0, 1.0, 0.5, 611.0, "④ M > θ"),
                            (69.0, 1.0, 200.0, 150.0, "④ θ ≥ W")):
    plan16.THETA_COND_S, plan16.M_S, plan16.THETA_EXT_S, plan16.W_S = th, m, the, w
    st = [plan16.startup_check(g)["ok"] for g in ("cond", "ext")]
    check(f"기동 검사 위반 검출 — {name}", not all(st), str(st))
plan16.THETA_COND_S, plan16.M_S, plan16.THETA_EXT_S, plan16.W_S = saved
check("iso_dur(Java Duration 표기)", [plan16.iso_dur(x) for x in (0, 69, 611, 300, 0.2, 3600)] == ["PT0S", "PT1M9S", "PT10M11S", "PT5M", "PT0.2S", "PT1H"])

# ---- 설정 대조 ----
tmp = Path(tempfile.mkdtemp())


def fake_cfg(config, theta=None, margin=None, window=None, ttl="PT5M", mode=None):
    k = plan16.knobs_of(config)
    ext = plan16.MODE_OF[config] == O.MODE_EXT
    return {"configprops": [
        {"prefix": "seat.confirm", "properties": {"mode": mode or plan16.plan15.MODE_ENUM[plan16.MODE_OF[config]], "loadtest": {"delay": "PT0S"},
                                                  "paymentWindow": window or (plan16.iso_dur(k["window"]) if ext else "PT0S"),
                                                  "paymentMinRemaining": theta or plan16.iso_dur(k["theta"]), "payByMargin": margin or plan16.iso_dur(k["margin"])}},
        {"prefix": "seat.hold", "properties": {"expiryMode": "SWEEP", "ttl": ttl, "expiryInterval": "PT10S", "strategy": "PESSIMISTIC_NOWAIT",
                                               "limitStrategy": "COUNTER_UPSERT_PRECHECK"}},
        {"prefix": "spring.flyway", "properties": {"locations": plan16.plan15.FLYWAY}},
        {"prefix": "server", "properties": {"tomcat": {"maxConnections": 20000}}}],
        "app_nofile": {"soft": 65536, "hard": 65536}, "hikaricp.connections.max": [10]}


f = tmp / "cfg.json"
for cfg, kw, want, name in (("cond", {}, 0, "① 일치"), ("ext", {}, 0, "④ 일치"), ("cond", {"theta": "PT0S"}, 1, "θ 다름(paymentMinRemaining)"),
                            ("ext", {"margin": "PT0S"}, 1, "M 다름(payByMargin)"), ("ext", {"window": "PT2M"}, 1, "W 다름(paymentWindow)"),
                            ("cond", {"ttl": "PT30S"}, 1, "TTL 다름"), ("cond", {"mode": "CONDITIONAL_EXTEND"}, 1, "모드 다름")):
    f.write_text(json.dumps(fake_cfg(cfg, **kw)))
    rc, out = plan16.config_check("paid", cfg, f)
    check(f"config-check {name} → rc {want}", rc == want and (want == 0 or out["reasons"]), json.dumps(out, ensure_ascii=False))
rc, out = plan16.config_check("paid", "cond", tmp / "없음.json")
check("config-check 파일 없음 → 판정 불가(2)", rc == 2)

# ---- compose 오버레이 = ADR-015 앱 JSON + pay-by-margin 한 쌍 ----
j15 = re.search(r"SPRING_APPLICATION_JSON: >-\n(.*?)\n(?=\S|\Z)", (REPO / "k6/ADR-015/compose.confirm.yml").read_text(), re.S).group(1)
j16 = re.search(r"SPRING_APPLICATION_JSON: >-\n(.*?)(?:\n\S|\Z)", (REPO / "k6/ADR-016/compose.paid16.yml").read_text(), re.S).group(1)
norm = lambda s: re.sub(r"\s+", "", s)   # noqa: E731
check("compose.paid16 = ADR-015 앱 JSON + \"pay-by-margin\" 한 키", norm(j16).replace('"pay-by-margin":"${PAY_BY_MARGIN:-0s}",', "") == norm(j15).split("db:")[0].strip(),
      norm(j16)[:200])

# ---- k6 난수 = plan16 난수(node — lib14.js fnv1a · mulberry32 · lib16.js sessionAttrs16 소스를 그대로 떼어 돌린다) ----


def js_fn(path, name):
    s = path.read_text()
    i = s.index(f"export function {name}(")
    j = s.index("{", i)
    depth = 0
    for k in range(j, len(s)):
        depth += {"{": 1, "}": -1}.get(s[k], 0)
        if depth == 0:
            return s[i + len("export "):k + 1]
    raise ValueError(name)


node = shutil.which("node")
if node:
    src = "\n".join([js_fn(REPO / "k6/ADR-014/scenarios/lib14.js", "fnv1a"), js_fn(REPO / "k6/ADR-014/scenarios/lib14.js", "mulberry32"),
                     js_fn(REPO / "k6/ADR-016/scenarios/lib16.js", "sessionAttrs16"),
                     "const P = {tMin: 30, tMax: 240, dMedian: 45, dSigma: 0.94, rho: 0.5, gridN: 13};",
                     "const out = []; for (const [r, s] of [[1, 0], [1, 1], [2, 77], [3, 4674], [1, 99999]]) out.push(sessionAttrs16(r, s, P));",
                     "console.log(JSON.stringify(out));"])
    p = subprocess.run([node, "-e", src], capture_output=True, text=True)
    try:
        js = json.loads(p.stdout)
        py = [plan16.session_attrs(r, s, 0.5) for r, s in [[1, 0], [1, 1], [2, 77], [3, 4674], [1, 99999]]]
        same = all(abs(a["T"] - b["T"]) < 1e-9 and abs(a["D"] - b["D"]) < 1e-9 * max(1, b["D"]) and a["rehold"] == b["rehold"] and a["gx"] == b["gx"] for a, b in zip(js, py))
        check("k6 sessionAttrs16 = plan16.session_attrs(5세션 — T · D · ρ · 격자)", same, f"{js[:2]} {py[:2]}")
    except ValueError:
        check("k6 sessionAttrs16 node 실행", False, p.stderr[-300:])
else:
    print("skip k6 난수 대조(node 없음)")

# ==== 결정표(outcome16.classify — 명세 §1-3 전 갈래) ====
H = {"st": 201, "ok_body": True}
cases = [
    ({"hold": {"st": 409, "ok_body": True}}, "invalid:hold-fail"), ({"hold": {"st": 201, "ok_body": False}}, "invalid:hold-fail"),
    ({"hold": H, "pay": {"st": 409, "code": "HOLD_EXPIRED"}}, O.PRE_REJECT), ({"hold": H, "pay": {"st": 404, "code": "HOLD_NOT_FOUND"}}, O.PRE_REJECT),
    ({"hold": H, "pay": {"st": 409, "code": "HOLD_EXPIRED"}, "reserved": True}, "integrity:pre-reject-with-reservation"),
    ({"hold": H, "pay": {"st": 403, "code": "HOLD_NOT_OWNED"}}, "mode_fail:pay-noncontract-403-HOLD_NOT_OWNED"),
    ({"hold": H, "pay": {"st": 0, "err": 1050}}, "mode_fail:pay-timeout"), ({"hold": H, "pay": {"st": 0, "err": 1000}}, "invalid:pay-code0"),
    ({"hold": H, "pay": {"st": 503}}, "mode_fail:pay-5xx"), ({"hold": H, "pay": {"st": 200, "ok_body": False}}, "mode_fail:pay-body-bad"),
    ({"hold": H, "pay_fail": "cap"}, O.PAY_FAIL), ({"hold": H, "pay_fail": "deadline"}, O.PAY_FAIL),
    ({"hold": H, "pay_fail": "cap", "reserved": True}, "integrity:pay-fail-with-reservation"),
    ({"hold": H, "confirm": {"st": 200}, "reserved": True}, O.SUCCESS), ({"hold": H, "confirm": {"st": 200}}, "integrity:200-without-reservation"),
    ({"hold": H, "confirm": {"st": 409, "code": "HOLD_EXPIRED"}}, O.WRONGED), ({"hold": H, "confirm": {"st": 404, "code": "HOLD_NOT_FOUND"}}, O.WRONGED),
    ({"hold": H, "confirm": {"st": 409, "code": "SEAT_NOT_HELD"}}, O.WRONGED),
    ({"hold": H, "confirm": {"st": 404, "code": "HOLD_NOT_FOUND"}, "reserved": True}, "integrity:reject-with-reservation"),
    ({"hold": H, "confirm": {"st": 403, "code": "HOLD_NOT_OWNED"}}, "mode_fail:confirm-noncontract-403-HOLD_NOT_OWNED"),
    ({"hold": H, "confirm": {"st": 0, "err": 1050}}, "mode_fail:confirm-timeout"), ({"hold": H, "confirm": {"st": 0, "err": 1000}}, "invalid:confirm-code0"),
    ({"hold": H, "confirm": {"st": 500}}, "mode_fail:confirm-5xx"), ({"hold": H}, "invalid:confirm-missing"),
    ({"hold": H, "pay": {"st": 200, "ok_body": True}, "confirm": {"st": 200}, "reserved": True}, O.SUCCESS),
    ({"hold": H, "pay": {"st": 409, "code": "HOLD_EXPIRED"}, "confirm": {"st": 200}}, "invalid:confirm-after-pre-reject"),
    ({"hold": H, "pay_fail": "deadline", "confirm": {"st": 200}}, "invalid:confirm-after-pay-fail"),
]
check(f"결정표 {len(cases)}갈래", all(O.classify(r) == w for r, w in cases), str([(w, O.classify(r)) for r, w in cases if O.classify(r) != w]))

# ==== 내림 경계 · 마감(DV16-R1) ====
check("payWithinSeconds = ⌊payBy − 응답⌋ · 음수 = 0", O.pay_within_seconds(10.0, 5.001) == 4 and O.pay_within_seconds(10.0, 6.0) == 4 and O.pay_within_seconds(5.0, 6.0) == 0)
check("마감 = min(수신 + pws, payBy − err) — 전송 지연 > 버린 소수면 payBy 쪽", abs(O.deadline(6.2, O.pay_within_seconds(10.0, 5.3), 10.0, 0.02) - 9.98) < 1e-12
      and abs(O.deadline(5.31, 4, 10.0, 0.02) - 9.31) < 1e-12)

# ==== predict = classify(흉내 응답) = decide(정확한 시각) ====


def simulate_session(mode, b, c, theta, window, margin, cap, T, D, delta, net):
    """흉내 서버 + 하네스(paid-flow.js와 같은 순서) → (classify 입력, predict 입력). 시각 = 서버 시계 초 · 선점 서버 시각 0."""
    e0, h_recv = plan16.TTL_S, net
    pb, e = e0 - margin, e0
    src = (h_recv, O.pay_within_seconds(pb, 0.0), pb)
    rec = {"hold": H, "pay": None, "pay_fail": None, "confirm": None, "reserved": False}
    think_end = h_recv + T
    tp, ps_send, ps_recv, e_after = think_end, None, None, None
    if b or mode == O.MODE_EXT:
        ps_send, srv = think_end, think_end + net
        ps_recv = srv + net
        br = O._pay_start_branch(mode, e0 - srv, theta, window)
        if br == "reject":
            rec["pay"] = {"st": 409, "code": "HOLD_EXPIRED"}
            return rec, dict(e0=e0, hold_recv=h_recv, think_end=think_end, ps_send=ps_send, ps_recv=ps_recv, pay=D)
        if br == "extend":
            e = srv + window
        e_after = e
        rec["pay"] = {"st": 200, "ok_body": True}
        src = (ps_recv, O.pay_within_seconds(e - margin, srv), e - margin)
        tp = ps_recv
    pi = dict(e0=e0, hold_recv=h_recv, think_end=think_end, ps_send=ps_send, ps_recv=ps_recv, e_after=e_after, pay=D, pws=src[1], pay_by=src[2], src_recv=src[0])
    if D > cap:
        rec["pay_fail"] = "cap"
        return rec, pi
    if c and tp + D > O.deadline(src[0], src[1], src[2], 0.0):
        rec["pay_fail"] = "deadline"
        return rec, pi
    c_send = tp + D
    now = c_send + delta
    ok = e > now
    rec["confirm"] = {"st": 200} if ok else {"st": 409, "code": "HOLD_EXPIRED"}
    rec["reserved"] = ok
    pi.update(c_send=c_send, c_recv=c_send + 2 * delta)
    return rec, pi


rnd = random.Random(7)
bad, nband, ntot = [], 0, 0
for mode, b, c, theta, window in ((O.MODE_COND, False, False, 69.0, 0.0), (O.MODE_COND, False, True, 69.0, 0.0), (O.MODE_COND, True, False, 69.0, 0.0),
                                  (O.MODE_COND, True, True, 69.0, 0.0), (O.MODE_EXT, True, False, 1.0, 611.0), (O.MODE_EXT, True, True, 1.0, 611.0),
                                  (O.MODE_EXT, True, False, 1.0, 120.0)):
    for _ in range(3000):
        T, D = rnd.uniform(30, 240), rnd.lognormvariate(math.log(45), 0.94)
        delta, net = rnd.uniform(0.001, 0.05), rnd.uniform(0.0005, 0.01)
        rec, pi = simulate_session(mode, b, c, theta, window, 1.0, 600.0, T, D, delta, net)
        obs = O.classify(rec)
        pred, band = O.predict(mode=mode, b=b, c=c, theta=theta, window=window, margin=1.0, cap=600.0, err=0.0, **pi)
        ntot += 1
        nband += band
        if pred != obs and not band:
            bad.append((mode, b, c, round(T, 2), round(D, 2), pred, obs))
check(f"predict = classify(흉내 응답) — {ntot}세션 · 띠 밖 불일치 0(띠 안 {nband})", not bad, str(bad[:5]))
# decide(정확한 시각 · 지연 0) = classify(흉내 · 지연 0)
bad = []
for mode, b, c, theta, window in ((O.MODE_COND, False, False, 69.0, 0.0), (O.MODE_COND, True, True, 69.0, 0.0), (O.MODE_EXT, True, True, 1.0, 611.0), (O.MODE_EXT, True, False, 1.0, 200.0)):
    for _ in range(3000):
        T, D, delta = rnd.uniform(30, 240), rnd.lognormvariate(math.log(45), 0.94), rnd.uniform(0, 0.05)
        rec, _ = simulate_session(mode, b, c, theta, window, 1.0, 600.0, T, D, delta, 0.0)
        dec = O.decide(mode=mode, b=b, c=c, theta=theta, window=window, margin=1.0, cap=600.0, ttl=plan16.TTL_S, think=T, pay=D, delta=delta)
        if dec != O.classify(rec):
            bad.append((mode, b, c, T, D, dec, O.classify(rec)))
check("decide(정확한 시각) = classify(흉내 응답 · 지연 0) — 12,000세션", not bad, str(bad[:3]))

# ==== 순수 판정 함수(인터페이스 C절) ====
check("cp_upper — 0건 1 − 0.05^(1/n) · n ≤ 0 None · k = n → 1 · k=1,n=3300 ≈ 4.74/n",
      abs(S.cp_upper(0, 3300) - (1 - 0.05 ** (1 / 3300))) < 1e-12 and S.cp_upper(0, 0) is None and S.cp_upper(5, 5) == 1.0 and abs(S.cp_upper(1, 3300) - 4.7439 / 3300) < 2e-6)
check("g_target — 0/3300 · 손실 5% 통과 · 1/3300 탈락(상한 0.144%) · 0/2000 탈락(상한 0.15%) · 손실 5.1% 탈락 · n 0 False",
      S.g_target(0, 3300, 100, 65) and not S.g_target(1, 3300, 0, 0) and not S.g_target(0, 2000, 0, 0) and not S.g_target(0, 3300, 100, 69) and not S.g_target(0, 0, 0, 0))
ok, lst = S.g_model([("success", False, "success"), ("wronged", True, "success")])
check("g_model — 띠 안 불일치 허용", ok and any(x["kind"] == "허용" for x in lst))
check("g_model — 띠 밖 불일치 1건 → False", not S.g_model([("success", False, "wronged")])[0])
ok, lst = S.g_model([("success", False, "mode_fail:confirm-5xx")])
check("g_model — 4갈래 밖 관측만 → None(판정 가능 행 0) · '판정 밖' 사유(L4-4)", ok is None and lst[0]["kind"] == "판정 밖")
ok, lst = S.g_model([("success", False, "success"), ("success", False, "integrity:200-without-reservation")])
check("g_model — 4갈래 밖 관측은 결과를 False로 만들지 않는다(G-정합이 다룬다 · L4-4)", ok is True and any(x["kind"] == "판정 밖" for x in lst))
check("g_model — 행 없음 → None(검출력 없음 · 결함 아님 — L3-4)", S.g_model([])[0] is None)
check("n16_5 — k6 ≤ 서버 ≤ k6 + status0", S.n16_5({"rejected:HOLD_EXPIRED": 3}, 1, {"rejected:HOLD_EXPIRED": 4})[0]
      and not S.n16_5({"rejected:HOLD_EXPIRED": 3}, 1, {"rejected:HOLD_EXPIRED": 5})[0] and not S.n16_5({"rejected:HOLD_EXPIRED": 3}, 0, {"rejected:HOLD_EXPIRED": 2})[0])
check("n16_5 — 한쪽에만 있는 키 · 서버 None", not S.n16_5({}, 0, {"failed:500": 1})[0] and not S.n16_5({"failed:500": 1}, 0, {})[0]
      and not S.n16_5({}, 0, None)[0] and S.n16_5({}, 0, {})[0])
check("boundary_ok — 마감 전 송신 억울 1 → False · 마감 전 0건 → False · 정상 → True",
      not S.boundary_ok([(True, O.WRONGED), (True, O.SUCCESS)])[0] and not S.boundary_ok([(False, O.PAY_FAIL)])[0]
      and S.boundary_ok([(True, O.SUCCESS), (False, O.PAY_FAIL), (False, O.WRONGED)])[0])
check("flow_count_ok — 중첩 dict 합 · 어긋남", S.flow_count_ok({"n": 3}, {"n": {"success": 2, "invalid:hold-fail": 1}})[0]
      and not S.flow_count_ok({"n": 3}, {"n": {"success": 2}})[0] and not S.flow_count_ok({"n": 3}, {"n": {"success": 3}, "x": {"success": 1}})[0])
check("early_reject — 남은 하한 > θ인데 409 HOLD_EXPIRED만", S.early_reject([(300, 100, 100.5, 0.02, 409, "HOLD_EXPIRED"), (300, 230, 231.5, 0.02, 409, "HOLD_EXPIRED"),
                                                                 (300, 100, 100.5, 0.02, "200", "OK"), (300, 100, 100.5, 0.02, 404, "HOLD_NOT_FOUND")], 69.0) == 1)
T_, F_, N_ = True, False, None
base = {n: {"g_consistency": T_, "g_model": T_, "g_target": F_, "cost_report": []} for n in S.ORDER + ["cond"]}
for n in S.ORDER:
    if n.endswith("+c"):
        base[n]["boundary_ok"] = T_
dc = lambda upd: S.decide_campaign({k: {**v, **upd.get(k, {})} for k, v in base.items()})   # noqa: E731
check("decide — 통과 없음 → none", dc({})["status"] == "none")
check("decide — ④만 통과 → pick ext", dc({"ext": {"g_target": T_}})["pick"] == "ext" and dc({"ext": {"g_target": T_}})["status"] == "pick")
check("decide — ①+ⓒ · ④ 통과 → 순서상 cond+c", dc({"ext": {"g_target": T_}, "cond+c": {"g_target": T_}})["pick"] == "cond+c")
check("decide — ①+ⓒ 경계 실패 → 다음 후보", dc({"cond+c": {"g_target": T_, "boundary_ok": F_}, "ext": {"g_target": T_}})["pick"] == "ext")
r_ = dc({"cond+c": {"g_target": T_}, "cond": {"g_model": F_}, "ext": {"g_target": T_}})
check("decide — ①+ⓒ 짝 ① g_model False → 판정 불가(탈락 아님) · 원인 조사 · 뒤 ext 통과 = undetermined(계약 C 정정 · L2-1)",
      r_["state"]["cond+c"] == "undetermined" and r_["pick"] == "ext" and r_["status"] == "undetermined" and any(x.startswith("원인 조사:cond+c") for x in r_["report"]), str(r_))
r_ = dc({"ext": {"g_target": T_, "g_model": F_}})
check("decide — 자기 g_model False → 판정 불가 · 원인 조사:ext · pick 없음", r_["state"]["ext"] == "undetermined" and r_["pick"] is None
      and r_["status"] == "undetermined" and any(x.startswith("원인 조사:ext") for x in r_["report"]), str(r_))
r_ = dc({"ext": {"g_target": F_, "g_model": F_}})
check("decide — g_model False면 다른 관문이 False여도 판정 불가(원인 조사 · L3-4)", r_["state"]["ext"] == "undetermined" and any(x.startswith("원인 조사:ext") for x in r_["report"]))
r_ = dc({"cond+c": {"g_target": F_}, "cond": {"g_model": F_}, "ext": {"g_target": T_}})
check("decide — ⓒ 짝 g_model False · 자기 목표 False여도 판정 불가 → 뒤 ext pick은 undetermined(L3-4)", r_["state"]["cond+c"] == "undetermined"
      and r_["pick"] == "ext" and r_["status"] == "undetermined")
r_ = dc({"cond+b": {"g_target": N_}, "ext": {"g_target": T_}})
check("decide — 앞 후보 판정 불가 · 뒤 통과 → undetermined(pick 기록)", r_["status"] == "undetermined" and r_["pick"] == "ext")
check("decide — 판정 불가만 → undetermined · pick 없음", dc({"ext": {"g_target": N_}})["status"] == "undetermined" and dc({"ext": {"g_target": N_}})["pick"] is None)
r_ = dc({"ext": {"g_target": T_, "cost_report": ["cost/db_cpu"]}})
check("decide — G-비용 보고 지표는 report에", any("cost/db_cpu" in x for x in r_["report"]))
check("decide — ⓒ 후보 boundary_ok None → 판정 불가", dc({"cond+c": {"g_target": T_, "boundary_ok": N_}, "ext": {"g_target": T_}})["status"] == "undetermined")

# ==== 합성 회차(흉내 서버로 만든 세션 행 + 환경 입력 — 결측이 없으면 ok) ====
HEAD = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"
csvrow = lambda m, t, v, name="", sc="main", st="", ec="", extra="": f"{m},{t},{v},,,{ec},,,POST,{name},HTTP/1.1,{sc},,{st},,,,{extra},"   # noqa: E731
T0 = 1_800_000_000.0   # PC 초(시계 차 0)
# pgss16_writes 덤프 모양(run16 — 쓰기 동사 문장 전문 · calls · rows): main-app 코드의 쓰기 문장(① 기준 — 허용 목록 안)
PG_BASE = [{"q": "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES ($1, $2, $3, $4, $5) RETURNING id", "calls": 4675, "rows": 4675},
           {"q": "DELETE FROM seat_hold WHERE id = $1 AND user_id = $2 AND expires_at > $3", "calls": 3900, "rows": 3500},
           {"q": "UPDATE product_seat SET status = 'RESERVED' WHERE id = $1 AND status = 'HELD'", "calls": 3500, "rows": 3500},
           {"q": "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES ($1, $2, $3, $4, $5, $6)", "calls": 3500, "rows": 3500},
           {"q": "INSERT INTO user_hold_quota (schedule_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING", "calls": 4675, "rows": 4675},
           {"q": "DELETE FROM seat_hold h USING unnest($1::bigint[], $2::bigint[]) AS p(sc, us) WHERE h.schedule_id = p.sc", "calls": 30, "rows": 400}]


def gen_sessions(plan, delta_ms=5.0, net_ms=1.0, ek=20.0):
    """paid-flow.js와 같은 행(k='s' · 'r') — 흉내 서버(정확한 판정 · 배치 10초) · 시각 ms(서버 = PC)."""
    k, mode = plan["knobs16"], plan["mode"]
    cyc, flows, grid = plan["flow_cycle"], plan["flows"], plan16.BOUNDARY_GRID_MS
    out, rh, confirmed, pay_n, conf_rej = [], [], [], {}, {}
    for sid in range(plan["n_total"]):
        fl = cyc[sid % len(cyc)]
        f = flows[fl]
        a = plan16.session_attrs(plan["rep"], sid, plan["rho"])
        arr = (T0 + sid / plan["rate"]) * 1000
        h_srv = arr + net_ms
        e0 = h_srv + plan16.TTL_S * 1000
        pb = e0 - k["margin"] * 1000
        hid, user = sid + 1, 1_000_000 + sid
        row = {"k": "s", "fl": fl, "sid": sid, "T": a["T"], "D": a["D"], "gx": grid[a["gx"]] if f["bd"] else None, "ek": ek, "uid": f"pay-{user}-{hid}",
               "dl": None, "pf": None, "th": None, "tp": None, "p": None, "c": None,
               "h": {"st": 201, "code": "NOCODE", "ec": 0, "ts": arr, "tr": arr + 2 * net_ms, "hid": hid, "exp": e0, "pb": pb,
                     "pws": math.floor((pb - h_srv) / 1000), "ext": None, "ok": True}}
        if a["rehold"]:
            rh.append({"k": "r", "sid": sid, "n": 3, "got": True, "t": e0 + 2000, "t1": e0 + plan16.REHOLD_FROM_MS + 5})
        row["th"] = row["h"]["tr"] + a["T"] * 1000
        src, tp, e = (row["h"]["tr"], row["h"]["pws"], pb), row["th"], e0
        if f["b"] or mode == O.MODE_EXT:
            ts_, srv = row["th"], row["th"] + net_ms
            rem = (e0 - srv) / 1000
            if rem <= 0 or rem <= k["theta"]:
                row["p"] = {"st": 409, "code": "HOLD_EXPIRED", "ec": 0, "ts": ts_, "tr": srv + net_ms}
                pay_n["409"] = pay_n.get("409", 0) + 1
                out.append(row)
                continue
            ext = mode == O.MODE_EXT and rem < k["window"]
            if ext:
                e = srv + k["window"] * 1000
            pb2 = e - k["margin"] * 1000
            row["p"] = {"st": 200, "code": "OK", "ec": 0, "ts": ts_, "tr": srv + net_ms, "hid": hid, "exp": e, "pb": pb2,
                        "pws": math.floor((pb2 - srv) / 1000), "ext": ext, "ok": True}
            pay_n["200"] = pay_n.get("200", 0) + 1
            pay_n["ext" if ext else "nn"] = pay_n.get("ext" if ext else "nn", 0) + 1
            src, tp = (row["p"]["tr"], row["p"]["pws"], pb2), row["p"]["tr"]
        row["tp"] = tp
        if f["bd"]:
            row["dl"] = min(src[0] + src[1] * 1000, src[2] - ek)
            if row["gx"] < 0:
                row["pf"] = "deadline"
                out.append(row)
                continue
            send = row["dl"] - row["gx"]
        else:
            if a["D"] > plan["cap_s"]:
                row["pf"] = "cap"
                out.append(row)
                continue
            if f["c"]:
                row["dl"] = min(src[0] + src[1] * 1000, src[2] - ek)
                if tp + a["D"] * 1000 > row["dl"]:
                    row["pf"] = "deadline"
                    out.append(row)
                    continue
            send = tp + a["D"] * 1000
        now = send + delta_ms
        if e > now:
            row["c"] = {"st": 200, "code": "OK", "ec": 0, "ts": send, "tr": send + 2 * delta_ms}
            confirmed.append(row["uid"])
        else:
            code = ("409", "HOLD_EXPIRED") if now < e + 10_000 else ("404", "HOLD_NOT_FOUND")
            row["c"] = {"st": int(code[0]), "code": code[1], "ec": 0, "ts": send, "tr": send + 2 * delta_ms}
            conf_rej[code[1]] = conf_rej.get(code[1], 0) + 1
        out.append(row)
    return out, rh, confirmed, pay_n, conf_rej


def synth(cell, config, mutate=None, post=None):
    plan = plan16.render(cell, config, 1)
    d = Path(tempfile.mkdtemp()) / cell / config / "rep1"
    d.mkdir(parents=True)
    (d / "plan.json").write_text(json.dumps(plan))
    (d / "harness-reasons.txt").write_text("")
    (d / "k6-summary.json").write_text(json.dumps({"setup_data": {"startedAt": T0 * 1000}}))
    (d / "pre-k6.json").write_text(json.dumps({"seed_done_epoch": T0 - 87}))
    (d / "clock-offset.json").write_text(json.dumps({"before": {"offset_s": 0.0, "rtt_s": 0.002}, "after": {"offset_s": 0.001, "rtt_s": 0.002}}))
    k6_end = T0 + plan["d_arr_s"] + 600 if cell == "paid" else T0 + plan["d_arr_s"] + 400
    (d / "k6-window.json").write_text(json.dumps({"exit": 0, "clock_offset_ms_given": 0, "clock_err_ms_given": 21, "k6_end_epoch": k6_end}))
    (d / "app-config.json").write_text(json.dumps(fake_cfg(config)))
    (d / "warmup-status.json").write_text(json.dumps({"rc": 0}))
    sch = plan["seed"]["schedules"] * 50000
    (d / "seed-check.json").write_text(json.dumps({"schedules": plan["seed"]["schedules"], "want_schedules": plan["seed"]["schedules"], "seats": sch, "want_seats": sch,
                                                   "available": sch, "holds": 0, "reservations": 0, "quota_rows": 0, "v12_valid": 1, "v13_column": 1, "v14_triggers": 0}))
    (d / "app-instances.txt").write_text("seatlab-app-1\n")
    (d / "pc-link.json").write_text(json.dumps({"readable": True, "down": 0}))
    (d / "probe-check.json").write_text(json.dumps({"ok": True}))
    (d / "logs-status.txt").write_text("app pipe=0/0 bytes=10\ndb pipe=0/0 bytes=10\n")
    (d / "timeline-waits.jsonl").write_text(json.dumps({"t": "2027-01-15T08:00:00+00:00", "waits": {}}) + "\n")
    w0, w1 = T0 + plan["window"]["from_s"], T0 + plan["window"]["from_s"] + plan["window"]["len_s"]
    wend = k6_end if cell == "paid" else w1   # 환경 점검 창 끝(리뷰 L1-5 — paid = k6 끝)
    iso = lambda t: __import__("datetime").datetime.fromtimestamp(t, __import__("datetime").timezone.utc).isoformat()   # noqa: E731
    (d / "timeline-client.jsonl").write_text("".join(json.dumps({"t": iso(t), "mem_avail_kb": 20_000_000}) + "\n" for t in range(int(w0) - 5, int(wend) + 6, 5)))
    srv = ""
    for t in range(int(w0) - 10, int(wend) + 11, 5):
        srv += json.dumps({"t": iso(t), "load": "1 1 1"}) + "\n" + json.dumps({"Name": "seatlab-db-1", "CPUPerc": "12.0%", "MemUsage": "1GiB / 14GiB"}) + "\n"
        srv += json.dumps({"Name": "seatlab-app-1", "CPUPerc": "30.0%", "MemUsage": "900MiB / 2GiB"}) + "\n"
    (d / "timeline-server.jsonl").write_text(srv)
    (d / "timeline-tw.jsonl").write_text("".join(json.dumps({"t": iso(t), "t_ms": t * 1000, "tw_all": 50, "tw_server": 40}) + "\n" for t in range(int(T0), int(wend) + 2, 2)))
    with gzip.open(d / "gc.log.gz", "wt") as g:
        g.write("[2027-01-15T08:00:00.000+0000][info][gc,init] Using The Z Garbage Collector\n")
    (d / "consistency.json").write_text(json.dumps({c: 0 for c in S.S15.CONS_ZERO}))
    tabs = lambda upd: {"deadlocks": 0, "tables": {"seat_hold": {"n_tup_upd": upd, "n_tup_del": 0}, "reservation": {"n_tup_ins": 0}}}   # noqa: E731
    (d / "table-before.json").write_text(json.dumps(tabs(0)))
    (d / "pgss-end.json").write_text(json.dumps({"rows": []}))
    (d / "pgss16-end.json").write_text(json.dumps({"t": "2027-01-15T08:00:00+00:00", "rows": PG_BASE}))
    if plan["probe16"]["holds"]:
        P_ = plan["probe16"]["holds"]
        tbl = {"deadlocks": 0, "tables": {t: {"n_tup_ins": P_, "n_tup_upd": P_ if t == "product_seat" else 0, "n_tup_del": 0} for t in S.PROBE_TABLES}}
        pgs = {"t": "x", "rows": [{"q": PG_BASE[0]["q"], "calls": P_, "rows": P_}]}
        base_ = {"deadlocks": 0, "tables": {t: {"n_tup_ins": 0, "n_tup_upd": 0, "n_tup_del": 0} for t in S.PROBE_TABLES}}
        (d / "probe16.json").write_text(json.dumps({"wait_s": 12, "holds": [f"{i}:{i}" for i in range(1, P_ + 1)], "pay_codes": ["200"] * P_, "table_base": base_, "table_before": tbl,
                                                    "table_after": tbl, "pgss_before": pgs, "pgss_after": pgs}))
    lines = [HEAD] + [csvrow("http_req_duration", t, 5.0, "hold", st="201") for t in range(int(w0), int(w1) + 1)]
    act0 = {"pay_start": dict.fromkeys(("ok", "insufficient", "expired", "not_found", "not_owned"), 0),
            "extend": dict.fromkeys(("extended", "already", "not_needed", "too_late"), 0), "rejected": {}, "failed": {}}
    act1 = json.loads(json.dumps(act0))
    sess = None
    if cell == "paid":
        sess, rh, confirmed, pay_n, conf_rej = gen_sessions(plan)
        if mutate:
            mutate(locals())
        with gzip.open(d / "sessions16.log.gz", "wt") as g:
            for r in sess + rh:
                g.write(json.dumps({"level": "info", "msg": json.dumps(r), "time": "x"}) + "\n")
        (d / "reservations.json").write_text(json.dumps({"rows": [{"u": u, "s": "CONFIRMED", "seat": 1} for u in confirmed]}))
        if plan["mode"] == O.MODE_COND:
            act1["pay_start"]["ok"], act1["pay_start"]["insufficient"] = pay_n.get("200", 0), pay_n.get("409", 0)
        else:
            act1["extend"]["extended"], act1["extend"]["not_needed"], act1["extend"]["too_late"] = pay_n.get("ext", 0), pay_n.get("nn", 0), pay_n.get("409", 0)
        act1["rejected"] = dict(conf_rej)
    else:
        outs = {}
        for sid in range(plan["n_total"]):
            a = plan16.session_attrs(1, sid, 0.0)
            o = "cap" if a["D"] > 600 else "conf:200:OK"
            outs[o] = outs.get(o, 0) + 1
        fl = plan["flow_cycle"][0]
        n_res = [None]
        if mutate:
            mutate(locals())
        lines += [csvrow("s16", int(T0), n, extra=f"fl={fl}&out={o}") for o, n in outs.items()]
        if plan["mode"] == O.MODE_EXT:
            act1["extend"]["not_needed"] = plan["n_total"]   # 흉내: 모두 200(④ — 연장 여부는 대조 밖)
        elif any(v["b"] for v in plan["flows"].values()):
            act1["pay_start"]["ok"] = plan["n_total"]
        (d / "hold-rows-wend.json").write_text(json.dumps({"holds": 20000, "held_seats": 20000}))
        n200 = n_res[0] if n_res[0] is not None else sum(n for o, n in outs.items() if o.startswith("conf:200"))
        (d / "reservations.json").write_text(json.dumps({"rows": [{"u": f"u{i}", "s": "CONFIRMED"} for i in range(n200)]}))
    (d / "table-end.json").write_text(json.dumps(tabs(0 if plan["mode"] == O.MODE_COND else 500)))
    (d / "act16-before.json").write_text(json.dumps(act0))
    (d / "act16-end.json").write_text(json.dumps(act1))
    with gzip.open(d / "k6-requests.csv.gz", "wt") as g:
        g.write("\n".join(lines) + "\n")
    if post:
        post(d)
    return d, S.rep_summary(d)


d, s = synth("paid", "cond")
m = s["metrics"]
check("합성 paid ① 회차 → ok · 실패 자료 없음", s["status"] == "ok" and not s["fail"], f"{s['status']} {s.get('missing')} {s['fail']}")
check("합성 paid ① — 흐름 5 · 계획 = 분류 · G-모형 흐름 4 모두 True", set(m["flows"]) == {"n", "nc", "b", "bc", "nd", "bcd"}
      and all(v["ok"] for v in m["g_model"].values()) and len(m["g_model"]) == 4, json.dumps(m["g_model"], ensure_ascii=False)[:400])
check("합성 paid ① — ⓒ 경계 흐름 nd · bcd 각각 통과(마감 전 송신 > 0 · 억울 0)", set(m["boundary"]) == {"nd", "bcd"}
      and all(v["ok"] and v["sent_before"] > 0 for v in m["boundary"].values()), str({f: {k: v for k, v in b.items() if k != "rows"} for f, b in m["boundary"].items()}))
check("합성 paid ① — N16-1 격리 탐침 증분 0(통과)", m["n16_1_probe"]["writes"] == [] and not m["n16_1_probe"]["bad"] and "n16_1_probe" not in s["fail"], str(m.get("n16_1_probe")))
check("합성 paid ① — N16-1 증거 0 · N16-5 · 서버 교차 통과", m["n16_1"] == {"seat_hold_n_tup_upd": 0, "update_seat_hold_calls": 0, "pay_start_5xx": 0} and m["n16_5"]["ok"] and m["pay_start_cross"]["ok"],
      json.dumps([m.get("n16_1"), m.get("n16_5"), m.get("pay_start_cross")], ensure_ascii=False)[:300])
check("합성 paid ① — δ̂ 분포 · 재선점 · 점유 시간 값", m["delta"]["n"] > 0 and abs(m["delta"]["p50"] - 0.01) < 1e-9 and m["rehold"]["never_ratio"] == 0 and m["occupancy"]["mean_s"] > 0)
fl_n = m["flows"]["n"]["classified"]
check("합성 paid ① — ① 흐름 억울 비율 ≈ 모형(10% 안팎)", 0.05 < fl_n.get("wronged", 0) / 1100 < 0.2, str(fl_n))
d, s = synth("paid", "ext")
check("합성 paid ④ 회차 → ok · G-모형 · 경계 통과", s["status"] == "ok" and not s["fail"] and all(v["ok"] for v in s["metrics"]["g_model"].values())
      and s["metrics"]["boundary"]["ed"]["ok"] and "n16_1_probe" not in s["metrics"], f"{s['status']} {s.get('missing')} {s['fail']} {s['metrics'].get('g_model')}")
check("합성 paid ④ — 억울 0(W = CAP + M + 배치)", sum(v["classified"].get("wronged", 0) for v in s["metrics"]["flows"].values()) == 0)
d, s = synth("cost", "cond+b")
check("합성 cost ①+ⓑ 회차 → ok(집계 계수 · 흐름 수 대조 · TIME_WAIT · 홀드 행)", s["status"] == "ok" and s["metrics"].get("tw_server_max") == 40
      and s["metrics"].get("hold_rows_wend") == 20000, f"{s['status']} {s.get('missing')}")


# ---- 회차 무효 사유(그린 위장 점검: 입력 결측은 ok가 아니다) ----
def drop_session(L):
    L["sess"].pop(10)


def tamper_td(L):
    L["sess"][5]["T"] += 0.5


def tamper_dl(L):
    r = next(x for x in L["sess"] if x["dl"] is not None)
    r["dl"] += 50


def lose_reservation(L):
    L["confirmed"].pop(0)


def hold_fail(L):
    L["sess"][3]["h"]["st"] = 409
    L["sess"][3]["h"]["ok"] = False


def code0(L):
    r = next(x for x in L["sess"] if x["c"])
    r["c"].update(st=0, ec=1000)


def timeout(L):
    r = next(x for x in L["sess"] if x["c"] and x["c"]["st"] == 200)
    r["c"].update(st=0, ec=1050, code="NOCODE")
    L["confirmed"].remove(r["uid"])


def wrong_reject(L):   # 서버가 남은 시간 > θ인데 결제 전 거부(잘못된 거부) — k6 · 서버 지표는 맞춰 둔다
    r = next(x for x in L["sess"] if x["fl"] == "b" and x["p"] and x["p"]["st"] == 200 and x["c"] and x["c"]["st"] == 200)
    r["p"] = {"st": 409, "code": "HOLD_EXPIRED", "ec": 0, "ts": r["p"]["ts"], "tr": r["p"]["tr"]}
    r["c"] = None
    L["confirmed"].remove(r["uid"])
    L["pay_n"]["200"] -= 1
    L["pay_n"]["409"] = L["pay_n"].get("409", 0) + 1


for fn, want, name in ((drop_session, "flow-count-mismatch", "세션 행 하나 빠짐(조용한 누락) → flow-count-mismatch"),
                       (tamper_td, "session-td-mismatch", "기록 T ≠ 시드 재계산 → session-td-mismatch"),
                       (tamper_dl, "deadline-mismatch", "기록 마감 ≠ outcome16.deadline 식 → deadline-mismatch"),
                       (hold_fail, "hold-fail", "선점 비201 → hold-fail(무효)"), (code0, "k6-code0", "확정 status 0(1050 아님) → k6-code0(무효)")):
    _, s = synth("paid", "cond", fn)
    check(name, want in s["reasons"], s["status"])
_, s = synth("paid", "cond", lose_reservation)
check("확정 200인데 예약 없음 → G-정합 실패(integrity · 회차는 유효)", s["status"] == "ok" and "integrity:200-without-reservation" in (s["fail"].get("integrity") or {}), str(s["fail"]))
_, s = synth("paid", "cond", timeout)
check("확정 시간 초과(1050) → 방식 실패(mode_fail · 회차는 유효)", s["status"] == "ok" and "mode_fail:confirm-timeout" in (s["fail"].get("mode_fail") or {}), f"{s['status']} {s['fail']}")
_, s = synth("paid", "cond", wrong_reject)
# outcome16.predict는 결제 성공 예상 · 확정 송신 없음이면 'paid'(4갈래 밖)를 돌려 관측과 늘 어긋난다 → 잘못된 결제 전 거부는 early_reject(G-정합)와 G-모형 둘 다 잡는다
check("남은 시간 > θ인데 409 → early_reject 1(G-정합 실패 자료)", s["fail"].get("early_reject") == 1, f"{s['fail']} {s['metrics']['g_model']['b']}")
check("남은 시간 > θ인데 409 → G-모형 띠 밖 불일치(그린 위장 아님)", s["metrics"]["g_model"]["b"]["ok"] is False, str(s["metrics"]["g_model"]["b"]))
for fname, want in (("sessions16.log.gz", "sessions-missing"), ("reservations.json", "reservation-dump-missing"), ("clock-offset.json", "clock-offset-bad"),
                    ("act16-end.json", "app-metric-missing"), ("pgss16-end.json", "pgss-missing"), ("table-end.json", "table-stats-missing"),
                    ("k6-requests.csv.gz", "k6-csv-missing"), ("consistency.json", "end-check-failed")):
    d, _ = synth("paid", "cond")
    (d / fname).unlink()
    s = S.rep_summary(d)
    check(f"입력 결측 {fname} → {want}", want in s["reasons"], s["status"])
d, _ = synth("paid", "cond")
(d / "clock-offset.json").unlink()
s = S.rep_summary(d)
check("시계 오차 결측 → clock-err-missing · G-모형 None(0으로 채우지 않음)", "clock-err-missing" in s["reasons"] and all(v["ok"] is None for v in s["metrics"]["g_model"].values()), s["status"])
d, _ = synth("cost", "cond")
(d / "timeline-tw.jsonl").unlink()
check("cost TIME_WAIT 표본 없음 → tw-missing", "tw-missing" in S.rep_summary(d)["reasons"])
d, _ = synth("cost", "cond")
(d / "hold-rows-wend.json").unlink()
check("cost 홀드 행 없음 → hold-rows-missing", "hold-rows-missing" in S.rep_summary(d)["reasons"])
d = Path(tempfile.mkdtemp())
(d / "plan.json").write_text(json.dumps(plan16.plan15.render("conditional", "race", 1)))
check("ADR-015 계획 폴더 → plan-missing(셀 혼동 금지)", S.rep_summary(d)["status"] == "invalid:plan-missing")


# ---- 리뷰 루프 1 회귀 사례(L1-1 · 5 · 6 · 7 · 8 · 9 · 10 · 14 · 15) ----
def first(L, pred):
    return next(x for x in L["sess"] if pred(x))


def pay5xx_c(L):   # ⓒ 흐름 payment-start 5xx(서버는 ok를 세지 않는다)
    r = first(L, lambda x: x["fl"] == "bc" and x["p"] and x["p"]["st"] == 200 and x["c"])
    r["p"] = {"st": 503, "code": "NOCODE", "ec": 0, "ts": r["p"]["ts"], "tr": r["p"]["tr"]}
    r.update(c=None, dl=None, pf=None, tp=None)
    if r["uid"] in L["confirmed"]:
        L["confirmed"].remove(r["uid"])
    else:
        code = next(k for k in L["conf_rej"])
        L["conf_rej"][code] -= 1
    L["pay_n"]["200"] -= 1


def paybody_c(L):   # ⓒ 흐름 payment-start 200 본문 결함(서버는 ok를 센다)
    r = first(L, lambda x: x["fl"] == "bc" and x["p"] and x["p"]["st"] == 200 and x["c"] and x["c"]["st"] == 200)
    r["p"].update(ok=False, pb=None, pws=None)
    r.update(c=None, dl=None, pf=None)
    L["confirmed"].remove(r["uid"])


_, s = synth("paid", "cond", pay5xx_c)
gb = s["metrics"].get("g_model", {}).get("bc", {})
check("L1-1 · L4-4 ⓒ 흐름 payment-start 5xx → 요약기가 죽지 않음 · mode_fail:pay-5xx(G-F) · G-모형 bc는 '판정 밖' 표기(False 아님)", s["status"] == "ok"
      and "mode_fail:pay-5xx" in (s["fail"].get("mode_fail") or {}) and gb.get("ok") is True and any(x.get("kind") == "판정 밖" for x in gb.get("bad", [])),
      f"{s['status']} {s['fail']} {gb}")
_, s = synth("paid", "cond", paybody_c)
check("L1-1 ⓒ 흐름 payment-start 200 본문 결함 → mode_fail:pay-body-bad(회차 유효)", s["status"] == "ok" and "mode_fail:pay-body-bad" in (s["fail"].get("mode_fail") or {}),
      f"{s['status']} {s['fail']}")
d, _ = synth("paid", "cond")
rows_ = [json.loads(json.loads(x)["msg"]) for x in gzip.open(d / "sessions16.log.gz", "rt")]
del rows_[0]["D"]   # 요약기 안쪽 예외를 일부러 낸다
with gzip.open(d / "sessions16.log.gz", "wt") as g:
    g.write("".join(json.dumps({"msg": json.dumps(r)}) + "\n" for r in rows_))
import io, contextlib
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    rc_ = S.main(["x", "--rep", str(d)])
sm_ = json.loads((d / "rep-summary.json").read_text())
check("L1-1 요약기 예외 → status invalid:summarize-failed · 추적 · 사용자 보고 표지(죽지 않음)", rc_ == 0 and buf.getvalue().strip().endswith("invalid:summarize-failed")
      and sm_.get("report_to_user") and "KeyError" in sm_.get("error", ""), buf.getvalue()[-200:])


def flow_tamper(L):
    L["sess"][7]["fl"] = "nd" if L["sess"][7]["fl"] != "nd" else "n"


def pf_deadline_coff(L):   # ⓒ 끔 흐름(n)에 pf = deadline
    r = first(L, lambda x: x["fl"] == "n" and x["c"] and x["c"]["st"] == 200)
    r.update(pf="deadline", c=None)
    L["confirmed"].remove(r["uid"])


def pf_cap_small(L):   # D ≤ CAP인데 pf = cap
    r = first(L, lambda x: x["fl"] == "b" and x["c"] and x["c"]["st"] == 200 and x["D"] <= 600)
    r.update(pf="cap", c=None)
    L["confirmed"].remove(r["uid"])


def no_send(L):   # 결제 성공(pf 없음)인데 확정을 보내지 않음 — 억울 0이 '확정 안 보냄'으로 생기는 그린 위장
    r = first(L, lambda x: x["fl"] == "n" and x["c"] and x["c"]["st"] != 200)
    code = r["c"]["code"]
    r["c"] = None
    L["conf_rej"][code] -= 1


def rh_drop(L):
    L["rh"].pop(0)


def rh_dup(L):
    L["rh"].append(dict(L["rh"][0]))


def rh_extra(L):
    tgt = {x["sid"] for x in L["rh"]}
    L["rh"].append({"k": "r", "sid": next(i for i in range(10 ** 6) if i not in tgt), "n": 1, "got": True, "t": 0})


def seat_not_held(L):
    r = first(L, lambda x: x["c"] and x["c"]["st"] == 409 and x["c"]["code"] == "HOLD_EXPIRED")
    r["c"]["code"] = "SEAT_NOT_HELD"
    L["conf_rej"]["HOLD_EXPIRED"] -= 1
    L["conf_rej"]["SEAT_NOT_HELD"] = L["conf_rej"].get("SEAT_NOT_HELD", 0) + 1


def orphan(L):
    L["confirmed"].append("pay-9999999-1")


def pay_cross(L):   # 서버 pay_start ok 지표와 k6 200 수가 어긋남
    L["pay_n"]["200"] += 3


def boundary_bad(L):   # 경계 흐름: 마감 전에 보냈는데 거부(억울)
    r = first(L, lambda x: x["fl"] == "nd" and x["c"] and x["c"]["st"] == 200)
    r["c"].update(st=409, code="HOLD_EXPIRED")
    L["confirmed"].remove(r["uid"])
    L["conf_rej"]["HOLD_EXPIRED"] = L["conf_rej"].get("HOLD_EXPIRED", 0) + 1


for fn, want, name in ((flow_tamper, "flow-assign-mismatch", "L1-7 흐름 배정 ≠ 바퀴[sid mod 길이] → flow-assign-mismatch"),
                       (pf_deadline_coff, "session-payfail-mismatch", "L1-15 ⓒ 끔 흐름에 pf=deadline → session-payfail-mismatch"),
                       (pf_cap_small, "session-payfail-mismatch", "L1-15 D ≤ CAP인데 pf=cap → session-payfail-mismatch"),
                       (no_send, "confirm-send-mismatch", "L1-15 결제 성공인데 확정 안 보냄 → confirm-send-mismatch"),
                       (rh_drop, "rehold-count-mismatch", "L1-8 재선점 행 누락 → rehold-count-mismatch"),
                       (rh_dup, "rehold-count-mismatch", "L1-8 재선점 행 중복 → rehold-count-mismatch"),
                       (rh_extra, "rehold-count-mismatch", "L1-8 대상 밖 재선점 행 → rehold-count-mismatch")):
    _, s = synth("paid", "cond", fn)
    check(name, want in s["reasons"], s["status"])
for fn, key, name in ((seat_not_held, "seat_not_held", "L1-14 409 SEAT_NOT_HELD → G-정합 실패 자료"), (orphan, "reservation_orphan", "L1-14 세션 밖 CONFIRMED 예약 → reservation_orphan"),
                      (pay_cross, "pay_start_mismatch", "L1-14 pay_start 지표 ≠ k6 → pay_start_mismatch")):
    _, s = synth("paid", "cond", fn)
    check(name, s["status"] == "ok" and key in s["fail"], f"{s['status']} {s['fail']}")
_, s = synth("paid", "cond", boundary_bad)
check("L1-14 경계 흐름 마감 전 송신 억울 → nd ok False(bcd는 그대로)", s["metrics"]["boundary"]["nd"]["ok"] is False and s["metrics"]["boundary"]["nd"]["wronged_before"] >= 1
      and s["metrics"]["boundary"]["bcd"]["ok"])
PG_UPD = {"t": "x", "rows": PG_BASE + [{"q": "UPDATE seat_hold SET expires_at = $1 WHERE id = $2", "calls": 2, "rows": 2}]}
_, s = synth("paid", "cond", post=lambda d: (d / "pgss16-end.json").write_text(json.dumps(PG_UPD)))
check("L1-9 · L1-14 ① pgss UPDATE seat_hold > 0 → n16_1 실패 + 허용 밖 쓰기(write_unknown)", "n16_1" in s["fail"] and "write_unknown" in s["fail"], str(s["fail"]))
PG_UNK = {"t": "x", "rows": PG_BASE + [{"q": "INSERT INTO counter_cleanup_run (at, mode, schedule_id, holds_seen, rows_deleted) VALUES ($1, $2, $3, $4, $5)", "calls": 1, "rows": 1},
                                      {"q": "WITH x AS (UPDATE product_seat SET status = $1 WHERE id = $2 RETURNING id) SELECT * FROM x", "calls": 1, "rows": 1},
                                      {"q": "WITH c AS (SELECT id FROM seat_hold WHERE expires_at <= $1) SELECT count(*) FROM c", "calls": 5, "rows": 5}]}
_, s = synth("paid", "ext", post=lambda d: (d / "pgss16-end.json").write_text(json.dumps(PG_UNK)))
wu = s["fail"].get("write_unknown") or []
check("L1-9 허용 밖 쓰기(INSERT counter_cleanup_run) 1건만 · WITH 안 허용 쓰기 · SELECT FOR UPDATE는 쓰기 아님", len(wu) == 1 and "counter_cleanup_run" in wu[0], str(wu))
_, s = synth("paid", "ext", post=lambda d: (d / "pgss16-end.json").write_text(json.dumps({"t": "x", "rows": PG_BASE + [{"q": "UPDATE seat_hold SET expires_at = $1, extended_at = $2 WHERE id = $3 AND expires_at > $4", "calls": 4, "rows": 4}]})))
check("L1-9 ④ UPDATE seat_hold(E1 연장)는 허용", "write_unknown" not in s["fail"], str(s["fail"]))


def tw_err(d):
    with open(d / "timeline-tw.jsonl", "a") as f:
        f.write(json.dumps({"t": "2027-01-15T08:00:00+00:00", "t_ms": 1, "err": "ss rc 1/0"}) + "\n")


_, s = synth("cost", "cond", post=tw_err)
check("L1-6 cost TIME_WAIT ss 실패 표본 → tw-missing(0으로 세지 않음)", "tw-missing" in s["reasons"], s["status"])
# paid는 TIME_WAIT를 판정에 쓰지 않는다(cost 지표 — 메인 결정 2026-11-08): 결측이면 값 None · 회차는 무효가 아니다 · 0으로 세지도 않는다
_, s = synth("paid", "cond", post=tw_err)
check("L1-6 paid TIME_WAIT ss 실패 표본 → 무효 아님 · 값 None(0 아님)", "tw-missing" not in s["reasons"] and s["metrics"].get("tw_server_max") is None, f"{s['status']} {s['metrics'].get('tw_server_max')}")


def client_short(d):   # PC 메모리 표본이 도착 창 끝에서 멈춤(k6 끝까지 덮지 못함)
    w1 = T0 + plan16.render("paid", "cond", 1)["d_arr_s"]
    rows = [json.loads(x) for x in (d / "timeline-client.jsonl").read_text().splitlines()]
    (d / "timeline-client.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows if S.ts(r["t"]) <= w1 + 5))


d, s = synth("paid", "cond")
pp = plan16.render("paid", "cond", 1)
check("L1-5 paid 환경 점검 창 = [t0, min(t0 + d_arr + grace, k6 끝)]", s["metrics"]["env_window_pc"] == [T0, T0 + pp["d_arr_s"] + 600], str(s["metrics"]["env_window_pc"]))
_, s = synth("paid", "cond", post=client_short)
check("L1-5 PC 메모리 표본이 도착 창까지만 → pc-mem-missing(창을 k6 끝까지 넓혔다)", "pc-mem-missing" in s["reasons"], s["status"])


def cost_bad(L):
    L["outs"]["conf:403:HOLD_NOT_OWNED"] = 2
    L["outs"]["conf:200:OK"] -= 2
    L["outs"]["pay:200:body"] = 1
    L["outs"]["conf:200:OK"] -= 1


_, s = synth("cost", "cond+b", cost_bad)
mf = s["fail"].get("mode_fail") or {}
check("L1-10 cost out 계수 → 결정표 갈래(확정 403 계약 밖 · payment-start 200 본문 결함 → mode_fail)",
      "mode_fail:confirm-noncontract-403-HOLD_NOT_OWNED" in mf and "mode_fail:pay-body-bad" in mf and "integrity" not in s["fail"], str(s["fail"]))
_, s = synth("cost", "cond", lambda L: L["n_res"].__setitem__(0, 5))
check("L1-10 cost 확정 200 합 ≠ CONFIRMED 행 수 → integrity", "integrity" in s["fail"], str(s["fail"]))
pk_ = plan16.render("cost", "ext", 1)["window"]["hold_ratio"]
check("L1-16 cost 창 — 홀드 수명 기준 정상 상태 비율(④ 창 시작 < 100% · 99% 도달 시각)", 0 < pk_["from"] < 1 and pk_["from_for_99"] > 0, str(pk_))
check("L1-13 run16 — 시계 차 파싱 실패를 0으로 채우지 않는다(OFF_MS=0 없음 · abort clock-offset-bad)",
      "|| OFF_MS=0" not in (HERE / "run16.sh").read_text() and "|| abort16 clock-offset-bad" in (HERE / "run16.sh").read_text())
import model16
sw = model16.sweep(picked={"M": None, "W": None, "theta_cond": 69.0}, n=10)
check("L1-12 model16 sweep — M · W 결측이면 계산하지 않음(delta missing · 0 채움 없음)", sw["results"] is None and sw["delta"] == "missing" and "M" in sw["missing"])
check("L1-12 model_cap30 — δ̂ 또는 err 없으면 None", S.model_cap30([], 0.003) is None and S.model_cap30([0.01], None) is None)


# ---- 리뷰 루프 2 사례(L2-2 · 4 · 6) ----
def add_http(rows_):
    def post(d):
        with gzip.open(d / "k6-requests.csv.gz", "rt") as g:
            body = g.read()
        with gzip.open(d / "k6-requests.csv.gz", "wt") as g:
            g.write(body + "\n".join(csvrow("http_req_duration", int(T0) + 5, 5.0, n, sc=sc, st=st, ec=ec) for n, sc, st, ec in rows_) + "\n")
    return post


_, s = synth("cost", "cond", post=add_http([("hold", "main", "503", "")]))
check("L2-2 cost 선점 5xx → hold-fail(무효 · paid와 같은 규칙)", "hold-fail" in s["reasons"], s["status"])
_, s = synth("cost", "cond", post=add_http([("hold", "main", "0", "1050")]))
check("L2-2 cost 선점 시간 초과 → hold-fail", "hold-fail" in s["reasons"], s["status"])
_, s = synth("cost", "cond", lambda L: L["outs"].__setitem__("hold:409:SEAT_NOT_AVAILABLE", 0) or None)
check("L2-2 cost 선점 out 계수(hold:*) → hold-fail", "hold-fail" in s["reasons"], s["status"])
for cell, cfg in (("paid", "cond"), ("cost", "cond")):
    _, s = synth(cell, cfg, post=add_http([("rehold", "rehold", "502", ""), ("rehold", "rehold", "0", "1050")]))
    check(f"L2-2 {cell} 재선점 5xx · 시간 초과 → 회차 유효 · fail에 없음 · undetermined.rehold_undetermined", s["status"] == "ok" and "5xx" not in s["fail"]
          and "timeout" not in s["fail"] and s["undetermined"].get("rehold_undetermined") == {"5xx:rehold": 1, "timeout:rehold": 1}, f"{s['status']} {s['fail']} {s.get('undetermined')}")
_bh = plan16.BUDGET_HOURS
plan16.BUDGET_HOURS = 1.0
check("L2-4 dry-run 예상 시간 > 예산 → 문제(rc ≠ 0)", any("예산" in x for x in plan16.dry_run()["problems"]) and plan16.main(["x", "--dry-run"]) == 1)
plan16.BUDGET_HOURS = _bh
check("L2-4 dry-run 예산 안(지금 값) → 문제 없음", DRY["est_hours"] <= plan16.BUDGET_HOURS)
check("L2-6 paid-flow.js OUT_DIR 필수(E('OUT_DIR'))", "E('OUT_DIR')" in (REPO / "k6/ADR-016/scenarios/paid-flow.js").read_text())

# ==== 캠페인 판정(합성 rep-summary) ====


def fake_campaign(weak_grid=False, mode_fail_cond=False, bad_bcd=False, paid_ok=True, wr_ext=0, n_reps=3, fail_cost=False, race_ev=2, race_fail=None, drop_cost_metric=None, drop_paid_flow_metric=False):
    root = Path(tempfile.mkdtemp())
    for cell in plan16.CELLS:
        for g in plan16.CONFIGS[cell]:
            for r in range(1, n_reps + 1):
                dd = root / cell / (plan16.RACE_MODE[g] if cell == "race" else g) / f"rep{r}"
                dd.mkdir(parents=True)
                (dd / "status").write_text("ok\n")
                if cell == "race":
                    rf = race_fail if (g == "ext" and race_fail) else {}
                    (dd / "rep-summary.json").write_text(json.dumps({"status": "ok", "metrics": {"mode": plan16.RACE_MODE[g], "rep": r,
                                                                                               "contention_evidence": race_ev if g == "ext" else 3}, "fail": rf}))
                    continue
                M_ = {"cell": cell, "config": g, "rep": r, "p99": {"confirm_200": 10 + (5 if g == "ext" else 0) + r, "hold_201": 5 + r, "pay_start_200": 4},
                      "db_cpu": 10 + (5 if g == "ext" else 0) + r * 0.1, "hold_rows_wend": 20000 + r, "tw_server_max": 100 + r, "clock_err_ms": 3}
                if cell == "paid":
                    fls = plan16.FLOWS[("paid", g)]
                    M_["flows"] = {}
                    for fl, (b, c, bd) in fls.items():
                        if bd:
                            continue
                        cl = {"success": 1100} if g == "ext" else {"success": 900, "wronged": 120, "pay_fail": 80}
                        if g == "ext" and fl == "e" and wr_ext:
                            cl = {"success": 1100 - wr_ext, "wronged": wr_ext}
                        M_["flows"][fl] = {"planned": 1100, "classified": cl}
                    M_["g_model"] = {fl: {"ok": paid_ok} for fl in M_["flows"]}
                    M_["boundary"] = {fl: {"rows": [[True, "wronged" if (bad_bcd and fl == "bcd") else "success"], [False, "pay_fail"]],
                                           "grid": {"0": {"planned": 10, "sent_before": 3 if (weak_grid and fl == "nd") else 10, "wronged_before": 0},
                                                    "-200": {"planned": 10, "sent_before": 0, "wronged_before": 0}}}
                                      for fl, (b, c, bd) in fls.items() if bd}
                    M_["delta"] = {"values": [0.004, 0.006]}
                    M_["rehold_by_flow"] = {fl: {"never_ratio": (0.9 if g == "ext" else 0.0) + r * 0.001} for fl in M_["flows"]}
                    M_["occupancy_by_flow"] = {fl: {"mean_s": 300 + r if g == "ext" else 150 + r} for fl in M_["flows"]}
                    if drop_paid_flow_metric and g == "ext":
                        M_["rehold_by_flow"]["e"]["never_ratio"] = None
                if drop_cost_metric and cell == "cost" and g == "ext" and r == 1:
                    M_.pop(drop_cost_metric)
                fail = {"n16_5": ["x"]} if fail_cost and cell == "cost" and g == "ext" else {}
                if mode_fail_cond and cell == "paid" and g == "cond":
                    fail = {"mode_fail": {"mode_fail:confirm-5xx": 1}}
                (dd / "rep-summary.json").write_text(json.dumps({"status": "ok", "metrics": M_, "fail": fail}))
    return root


r = S.campaign(fake_campaign(), write=False)
check("캠페인 — ④ 두 흐름 억울 0 · ① 후보 목표 미달 → 순서상 ext+c · G-비용 보고(db_cpu · 재선점)", r["decision"]["pick"] == "ext+c" and r["decision"]["status"] == "pick"
      and "cost/db_cpu" in r["candidates"]["ext+c"]["cost_report"] and "paid/rehold.never_ratio[e:n]" in r["candidates"]["ext"]["cost_report"], json.dumps(r["decision"], ensure_ascii=False))
check("캠페인 — ① 후보는 목표 미달(억울 120/1100)", r["candidates"]["cond+c"]["g_target"] is False and r["candidates"]["cond+b"]["g_target"] is False)
r = S.campaign(fake_campaign(wr_ext=1), write=False)
check("캠페인 — ④ 억울 3/3300(상한 > 0.1%) → ④ 탈락", r["candidates"]["ext"]["g_target"] is False, str(r["candidates"]["ext"]))
r = S.campaign(fake_campaign(n_reps=2), write=False)
check("캠페인 — 유효 2회 → 판정 불가", r["decision"]["status"] == "undetermined" and r["decision"]["pick"] is None)
r = S.campaign(fake_campaign(paid_ok=False), write=False)
check("캠페인 — G-모형 False → 통과 없음 · 판정 불가 · 원인 조사(L2-1)", r["decision"]["pick"] is None and r["decision"]["status"] == "undetermined"
      and any(x.startswith("원인 조사:") for x in r["decision"]["report"]))
check("RESULT.md — 판정 불가 → 사용자 보고 문구", "판정 불가 → 사용자 보고" in S.render_md(r))
r = S.campaign(fake_campaign(n_reps=3), write=False)
r["decision"] = S.decide_campaign({n: {"g_consistency": True, "g_model": True, "g_target": False, "cost_report": [], "boundary_ok": True} for n in S.ORDER + ["cond"]})
check("RESULT.md — none(통과 후보 없음)도 판정 불가 → 사용자 보고", r["decision"]["status"] == "none" and "통과 후보 없음" in S.render_md(r))
r = S.campaign(fake_campaign(fail_cost=True), write=False)
check("캠페인 — cost ④ 정합 실패(N16-5) → ④ 탈락", r["candidates"]["ext"]["g_consistency"] is False)
md = S.render_md(S.campaign(fake_campaign(), write=False))
check("RESULT.md — CAP 30분 모형 · ⓒ 조건 병기", "cap30" in md and "결제 쪽이 마감" in md)

# ---- 리뷰 루프 1 캠페인 사례(L1-3 · 4 · 11 · 12) ----
r = S.campaign(fake_campaign(race_ev=0), write=False)
check("L1-4 race ④ 회차 양성 대조 0 → ④ 후보 g_consistency None(판정 불가)", r["candidates"]["ext"]["g_consistency"] is None and r["candidates"]["cond"]["g_consistency"] is True)
r = S.campaign(fake_campaign(race_fail={"timeout": {"hold": 1}}), write=False)
check("L1-4 race 'A도 같은 문턱' 대상(시간 초과) → None(False 아님)", r["candidates"]["ext"]["g_consistency"] is None)
r = S.campaign(fake_campaign(race_fail={"integrity": {"v_duplicate_confirmed_seats": 1}}), write=False)
check("L1-4 race 정합 위반 → False", r["candidates"]["ext"]["g_consistency"] is False)
r = S.campaign(fake_campaign(drop_cost_metric="db_cpu"), write=False)
check("L1-3 cost 지표 결측 → '판정 불가:cost/db_cpu' · 그 후보 판정 불가(조용히 빠지지 않음)", "판정 불가:cost/db_cpu" in r["candidates"]["ext+c"]["cost_report"]
      and r["decision"]["state"]["ext+c"] == "undetermined", json.dumps(r["decision"], ensure_ascii=False)[:300])
check("L1-3 cost payment-start p99는 기준(① cost)이 없어 비교에서 뺀다", not any("pay_start" in x for x in r["candidates"]["ext"]["cost_report"]))
r = S.campaign(fake_campaign(drop_paid_flow_metric=True), write=False)
check("L1-8 · L1-11 paid 재선점 비율 결측 → '판정 불가:paid/rehold.never_ratio[e:n]'", "판정 불가:paid/rehold.never_ratio[e:n]" in r["candidates"]["ext"]["cost_report"],
      str(r["candidates"]["ext"]["cost_report"]))
check("L1-3 decide — cost_report '판정 불가:'가 있으면 통과 후보도 판정 불가", dc({"ext": {"g_target": T_, "cost_report": ["판정 불가:cost/db_cpu"]}})["status"] == "undetermined")
fc = fake_campaign()
for f_ in fc.glob("paid/*/rep*/rep-summary.json"):
    j = json.loads(f_.read_text())
    j["metrics"]["delta"] = {"values": []}
    f_.write_text(json.dumps(j))
r = S.campaign(fc, write=False)
md = S.render_md(r)
check("L1-12 δ̂ 결측 캠페인 → 모형 계산 안 함 · RESULT.md 'δ̂ 결측 — 계산 안 함' · N16-1 세 겹 · 짝 메모", r["model"] is None and "δ̂ 결측 — 계산 안 함" in md
      and "readOnly" in md and "paid e 대 ① n" in md)



# ---- 리뷰 루프 2 사례(L2-9 격리 탐침 · L2-10 bcd) ----
def probe_mut(fn):
    def post(d):
        j = json.loads((d / "probe16.json").read_text())
        fn(j)
        (d / "probe16.json").write_text(json.dumps(j))
    return post


_, s = synth("paid", "cond", post=probe_mut(lambda j: j["table_after"]["tables"]["seat_hold"].__setitem__("n_tup_upd", 3)))
check("L2-9 탐침 seat_hold n_tup_upd 증분 > 0 → n16_1_probe 실패 자료", "n16_1_probe" in s["fail"] and any("seat_hold.n_tup_upd +3" in x for x in s["fail"]["n16_1_probe"]), str(s["fail"]))
_, s = synth("cost", "cond+b", post=probe_mut(lambda j: j["pgss_after"]["rows"].append({"q": "UPDATE seat_hold SET expires_at = $1 WHERE id = $2", "calls": 2, "rows": 2})))
check("L2-9 탐침 쓰기 문장 rows 증분 > 0(cost ①+ⓑ) → n16_1_probe", "n16_1_probe" in s["fail"], str(s["fail"]))
_, s = synth("cost", "cond", post=probe_mut(lambda j: j["pgss_after"]["rows"].append({"q": "DELETE FROM seat_hold h USING unnest($1::bigint[]) AS p(x) WHERE true", "calls": 3, "rows": 0})))
check("L2-9 탐침 0행 쓰기 문장 호출(배치 틱)은 증분 아님", "n16_1_probe" not in s["fail"], str(s["fail"]))
_, s = synth("paid", "cond", post=lambda d: (d / "probe16.json").unlink())
check("L2-9 probe16.json 없음 → probe16-missing(무효)", "probe16-missing" in s["reasons"], s["status"])
_, s = synth("paid", "cond", post=probe_mut(lambda j: j["pay_codes"].__setitem__(0, "409")))
check("L2-9 탐침 payment-start 200 아님 → probe16-bad(탐침 성립 안 함)", "probe16-bad" in s["reasons"], s["status"])
r = S.campaign(fake_campaign(bad_bcd=True), write=False)
check("L2-10 bcd 경계 실패 → cond+b+c boundary False · cond+c(nd)는 True", r["candidates"]["cond+b+c"]["boundary_ok"] is False and r["candidates"]["cond+c"]["boundary_ok"] is True
      and r["candidates"]["ext+c"]["boundary_ok"] is True)

fc = fake_campaign()
for f_ in fc.glob("paid/ext/rep2/rep-summary.json"):
    j = json.loads(f_.read_text())
    j["undetermined"] = {"rehold_undetermined": {"5xx:rehold": 1}}
    f_.write_text(json.dumps(j))
r = S.campaign(fc, write=False)
check("L2-2 paid 재선점 5xx 회차 → 그 구성 g_consistency None(False 아님)", r["candidates"]["ext"]["g_consistency"] is None and r["candidates"]["cond"]["g_consistency"] is True)


# ---- 리뷰 루프 3 사례(L3-1 · 2 · 3 · 5 · 6) ----
_, s = synth("paid", "cond", post=probe_mut(lambda j: j.__setitem__("table_before", j["table_base"])))
check("L3-3 탐침 양성 대조 불성립(기준 → 전 seat_hold 삽입 ≠ P — 통계 반영 미완) → probe16-bad", "probe16-bad" in s["reasons"], s["status"])
check("L3-3 탐침 대기 ≥ 12초(PGSTAT_IDLE_INTERVAL 10초 + 여유) · 기준 스냅숏", "w=12" in (HERE / "run16.sh").read_text() and "table_base: $t0" in (HERE / "run16.sh").read_text())
kwp = dict(mode=O.MODE_COND, b=True, c=True, theta=69.0, window=0.0, margin=1.0, cap=600.0, e0=300.0, hold_recv=0.0, think_end=230.997,
           ps_send=230.997, ps_recv=231.001, pay=10.0, err=0.002)
try:
    pr_ = O.predict(**kwp)
    check("L3-2 predict — ⓒ · θ 띠 안 · 마감 입력 없음 → 예외 없이 (PAID, 띠 안)", pr_ == (O.PAID, True), str(pr_))
except Exception as e:   # noqa: BLE001
    check("L3-2 predict — ⓒ · θ 띠 안 · 마감 입력 없음 → 예외 없이 (PAID, 띠 안)", False, repr(e))


def bc_theta_reject(L):   # bc 세션: 서버가 θ 문턱 언저리에서 결제 전 거부 — 하네스 중간값으로는 통과
    r = first(L, lambda x: x["fl"] == "bc" and x["p"] and x["p"]["st"] == 200 and x["c"])
    e0 = r["h"]["exp"]
    r["p"] = {"st": 409, "code": "HOLD_EXPIRED", "ec": 0, "ts": e0 - 69_000 - 3, "tr": e0 - 69_000 + 1}
    if r["uid"] in L["confirmed"]:
        L["confirmed"].remove(r["uid"])
    else:
        L["conf_rej"][r["c"]["code"]] -= 1
    r.update(c=None, dl=None, pf=None, tp=None)
    L["pay_n"]["200"] -= 1
    L["pay_n"]["409"] = L["pay_n"].get("409", 0) + 1


_, s = synth("paid", "cond", bc_theta_reject)
check("L3-2 bc · θ 띠 안 · 서버 거부 → summarize 예외 없음 · 회차 ok · G-모형 bc 허용(띠 안)", s["status"] == "ok" and s["metrics"]["g_model"]["bc"]["ok"] is True
      and s["metrics"]["g_model"]["bc"]["allowed"] >= 1, f"{s['status']} {s['metrics'].get('g_model', {}).get('bc')}")
base_ext = dict(mode=O.MODE_EXT, b=True, c=False, theta=1.0, window=611.0, margin=1.0, cap=600.0, e0=300.0, hold_recv=0.0, think_end=100.0,
                ps_send=100.0, ps_recv=100.1, pay=590.0, c_send=690.0, c_recv=690.2, err=0.003)
check("L3-1 predict ④ — 응답 만료(e_after)를 예측에 쓰지 않는다(엉뚱한 e_after에도 같은 예측)",
      O.predict(**base_ext, e_after=None) == O.predict(**base_ext, e_after=650.0) == O.predict(**base_ext, e_after=99999.0))
check("L3-1 predict ④ — 확정 띠 = 정책 만료 구간 [ps_send + W, ps_recv + W] ± err와 확정 구간이 겹치면 띠 안",
      O.predict(**{**base_ext, "c_send": 711.0, "c_recv": 711.05})[1] is True and O.predict(**base_ext)[1] is False)


def ext_value_bad(L):   # ④ 연장 응답 만료가 정책식 구간 밖(+5초)
    r = first(L, lambda x: x["p"] and x["p"]["st"] == 200 and x["p"].get("ext"))
    r["p"]["exp"] += 5000


_, s = synth("paid", "ext", ext_value_bad)
check("L3-1 ④ 연장 만료 값이 정책식 구간 밖 → extend_value_mismatch(G-정합 실패 자료 · 예측과 따로)", s["fail"].get("extend_value_mismatch") == 1, str(s["fail"]))


def ext_value_bad_bd(L):   # ④ 경계 흐름(ed)의 연장 응답 만료가 정책식 구간 밖(+5초) — 재점검 L4-1: 경계 분기 앞에서 검사해야 잡힌다
    r = first(L, lambda x: x.get("fl") == "ed" and x["p"] and x["p"]["st"] == 200 and x["p"].get("ext"))
    r["p"]["exp"] += 5000


_, s = synth("paid", "ext", ext_value_bad_bd)
check("L4-1 ④ 경계 흐름 ed의 연장 만료 값이 구간 밖 → extend_value_mismatch(경계 흐름도 검사)", s["fail"].get("extend_value_mismatch") == 1, str(s["fail"]))


def pay_hid_bad(L):   # payment-start 200이 다른 홀드 ID를 돌려줌(k6 ok 판정도 false)
    r = first(L, lambda x: x["fl"] == "b" and x["p"] and x["p"]["st"] == 200 and x["c"] and x["c"]["st"] == 200)
    r["p"].update(hid=r["p"]["hid"] + 1, ok=False)
    r.update(c=None, dl=None, pf=None)
    L["confirmed"].remove(r["uid"])


_, s = synth("paid", "cond", pay_hid_bad)
check("L3-5 payment-start 200 holdId 불일치 → mode_fail:pay-body-bad", "mode_fail:pay-body-bad" in (s["fail"].get("mode_fail") or {}), str(s["fail"]))
check("L3-5 paid-flow.js — payment-start 본문 ok에 holdId 일치(row.p.hid === hb.hid)", "row.p.hid === hb.hid" in (REPO / "k6/ADR-016/scenarios/paid-flow.js").read_text())
ch = model16.choose(deltas=None, err=0.003, n=500)
ch2 = model16.choose(deltas=[0.01, 0.02], err=None, n=500)
check("L3-6 model16.choose — δ̂ 또는 err 결측 → M · W · θ_cond · 비율 모두 None(δ 0 채움 없음)",
      all(x[k] is None for x in (ch, ch2) for k in ("M", "theta_cond", "theta_cond_rates")) and ch["W"] is None, f"{ch} {ch2}")
pc_ = subprocess.run([sys.executable, "-I", str(HERE / "model16.py"), "--choose", "-n", "100"], capture_output=True, text=True)
check("L3-6 model16 --choose — --delta · --err 없이 거부(rc ≠ 0)", pc_.returncode != 0 and "--delta" in pc_.stderr, pc_.stderr[-200:])


# ---- 리뷰 루프 4 사례(L4-2 · 3 · 4) ----
def late_wake_near(L):   # 경계 흐름 nd: 마감 직전 격자 x = 0 · 50 · 100ms가 모두 늦게 깨어 안 보냄(lw) — 먼 격자만 성공
    for r in L["sess"]:
        if r["fl"] == "nd" and r["gx"] in (0, 50, 100) and r["c"] is not None:
            if r["uid"] in L["confirmed"]:
                L["confirmed"].remove(r["uid"])
            else:
                L["conf_rej"][r["c"]["code"]] -= 1
            r.update(c=None, pf="deadline", lw=True)


d, s = synth("paid", "cond", late_wake_near)
bn = s["metrics"]["boundary"]["nd"]
check("L4-2 x=0 · 50 · 100 전부 늦게 깨어 안 보냄 + 먼 격자 성공 → 회차 유효 · boundary_ok True지만 grid_ok None(검출력 부족)", s["status"] == "ok" and bn["ok"] is True
      and bn["grid_ok"] is None and {"0", "50", "100"} <= set(bn["grid_weak"]) and bn["grid"]["0"]["late_wake"] > 0, f"{s['status']} {bn.get('grid_ok')} {bn.get('grid_weak')}")
_, s = synth("paid", "cond")
check("L4-2 마감 직전 격자 표본 충분 · 거부 0 → grid_ok True(nd · bcd)", all(s["metrics"]["boundary"][f]["grid_ok"] is True for f in ("nd", "bcd")),
      str({f: s["metrics"]["boundary"][f]["grid_weak"] for f in ("nd", "bcd")}))
check("L4-2 boundary_grid_ok — 마감 전 송신 억울 → False · 계획 대비 송신 부족 → None · 충분 → True",
      S.boundary_grid_ok({"0": {"planned": 10, "sent_before": 10, "wronged_before": 1}})[0] is False
      and S.boundary_grid_ok({"0": {"planned": 10, "sent_before": 7, "wronged_before": 0}})[0] is None
      and S.boundary_grid_ok({"0": {"planned": 10, "sent_before": 8, "wronged_before": 0}, "-200": {"planned": 9, "sent_before": 0, "wronged_before": 0}})[0] is True)


def lw_coff(L):   # lw 표지인데 확정을 보냈다(모순) → 결제 실패 표지 대조 실패
    r = first(L, lambda x: x["fl"] == "nd" and x["c"] is not None)
    r["lw"] = True
    r["pf"] = "deadline"


_, s = synth("paid", "cond", lw_coff)
check("L4-2 lw인데 확정을 보냄 → session-payfail-mismatch", "session-payfail-mismatch" in s["reasons"], s["status"])
r = S.campaign(fake_campaign(weak_grid=True), write=False)
check("L4-2 캠페인 — nd 격자 검출력 부족 → cond+c boundary None(판정 불가) · ed는 True", r["candidates"]["cond+c"]["boundary_ok"] is None
      and r["candidates"]["ext+c"]["boundary_ok"] is True)


def rh_late(L):
    for x in L["rh"][: max(2, len(L["rh"]) // 50)]:
        x["t1"] += 5000


def rh_no_t1(L):
    L["rh"][0].pop("t1")


_, s = synth("paid", "cond", rh_late)
check("L4-3 재선점 첫 시도 지연 > 허용인 행 > 1% → rehold-schedule-late", "rehold-schedule-late" in s["reasons"] and s["metrics"]["rehold_schedule"]["late"] >= 2, s["status"])
_, s = synth("paid", "cond", lambda L: L["rh"][0].__setitem__("t1", L["rh"][0]["t1"] + 5000))
check("L4-3 지연 행 1건(≤ 1%) → 무효 아님 · 계수 기록", "rehold-schedule-late" not in s["reasons"] and s["metrics"]["rehold_schedule"]["late"] == 1, s["status"])
_, s = synth("paid", "cond", rh_no_t1)
check("L4-3 t1 없음 → rehold-schedule-missing(0으로 채우지 않음)", "rehold-schedule-missing" in s["reasons"], s["status"])
check("L4-3 paid-flow.js 재선점 행에 t1", "got, t, t1 }" in (REPO / "k6/ADR-016/scenarios/paid-flow.js").read_text())
r = S.campaign(fake_campaign(mode_fail_cond=True), write=False)
check("L4-4 캠페인 — paid ① 회차 mode_fail → ① 후보 g_consistency False(탈락) · ④+ⓒ 통과 → status pick(판정 불가 아님)",
      all(r["candidates"][n]["g_consistency"] is False for n in ("cond+c", "cond+b+c", "cond+b")) and r["decision"]["pick"] == "ext+c"
      and r["decision"]["status"] == "pick" and not any(x.startswith("원인 조사") for x in r["decision"]["report"]), json.dumps(r["decision"], ensure_ascii=False)[:300])

# ==== 캠페인 루프(선택) ====
if "--campaign" in sys.argv:
    sim = Path(tempfile.mkdtemp())
    sp = sim / "plan.txt"
    sp.write_text("1:paid:cond:1=invalid:env-stall\n2:cost:ext:1=invalid:k6-code0\n2:cost:ext:2=invalid:k6-code0\n2:cost:ext:3=invalid:k6-code0\n"
                  "1:race:conditional-extend:1=invalid:k6-code0\n")
    p = subprocess.run(["bash", str(HERE / "campaign16.sh"), "--simulate", "--worktree", "--results-root", str(sim), "--id", "t", "--sim-plan", str(sp)],
                       stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=REPO)
    check("campaign16 --simulate exit 0", p.returncode == 0, (p.stdout + p.stderr)[-800:])
    check("env-stall 자리 재측정 → ok", (sim / "t/paid/cond/rep1/status").read_text().strip() == "ok")
    check("하네스 무효 재측정 상한 2번(시도 3)", len(list((sim / "t/cost/ext").glob("rep2.attempt*"))) == 2
          and (sim / "t/cost/ext/rep2/status").read_text().strip() == "invalid:k6-code0")
    check("race 자리 = ADR-015 run.sh(race/<모드>/rep · 재측정 1번)", (sim / "t/race/conditional-extend/rep1/status").read_text().strip() == "ok"
          and (sim / "t/race/conditional-extend/rep1.attempt1").is_dir() and (sim / "t/race/conditional/rep3/status").exists())
    check("21자리 모두 status", len(list(sim.glob("t/*/*/rep[0-9]/status"))) == 21)
    mh = json.loads((sim / "t/race/conditional/rep1/meta.json").read_text())["harness_hash"]
    check("race meta 해시 = 동결 ADR-015 해시 · paid meta = 동결 ADR-016 해시", mh == json.loads((sim / "t/plan.json").read_text())["harness_hash15"]
          and json.loads((sim / "t/paid/ext/rep1/meta.json").read_text())["harness_hash"] == json.loads((sim / "t/plan.json").read_text())["harness_hash"])
    p = subprocess.run(["bash", str(HERE / "campaign16.sh"), "--check", str(sim / "t")], capture_output=True, text=True)
    check("campaign16 --check exit 0", p.returncode == 0, p.stderr)
    # 이어서(--id 재개): 같은 id로 다시 돌리면 새 시도가 없다
    p = subprocess.run(["bash", str(HERE / "campaign16.sh"), "--simulate", "--worktree", "--results-root", str(sim), "--id", "t", "--sim-plan", str(sp)],
                       stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=REPO)
    check("--id 재개 → exit 0 · 추가 시도 없음", p.returncode == 0 and len(list((sim / "t/cost/ext").glob("rep2.attempt*"))) == 2, p.stderr[-300:])
    (sim / "t/order.txt").write_text("1 1 paid cond\n")
    p = subprocess.run(["bash", str(HERE / "campaign16.sh"), "--simulate", "--worktree", "--results-root", str(sim), "--id", "t"],
                       stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=REPO)
    check("이어서: order.txt 다름 → 거부(exit 2)", p.returncode == 2)
    shutil.rmtree(sim, ignore_errors=True)
    sim = Path(tempfile.mkdtemp())
    (sim / "plan.txt").write_text("".join(f"{r}:{c}:{plan16.RACE_MODE[g] if c == 'race' else g}:1=invalid:k6-code0\n" for _, r, c, g in plan16.order()[:5]))
    p = subprocess.run(["bash", str(HERE / "campaign16.sh"), "--simulate", "--worktree", "--results-root", str(sim), "--id", "t", "--sim-plan", str(sim / "plan.txt")],
                       stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=REPO)
    check("연속 무효 5회 → 멈춤(exit 3)", p.returncode == 3, f"rc={p.returncode}")
    shutil.rmtree(sim, ignore_errors=True)
    # FIXED 두 상태(리뷰 L1-2 — 고정 커밋 뒤에도 깨지지 않게): FIXED를 바꿔 끼운 하네스 사본(git 밖 — SHA 해석에서 멈춰 원격 · git 동작이 없다)으로
    #   False = FIXED 문구로 거부 · True = FIXED 관문을 지나 다음 관문(SHA 해석)에서 멈춤
    hp = subprocess.run(["bash", str(HERE / "hhash16.sh"), "--paths"], capture_output=True, text=True).stdout.split()
    for fixed in (False, True):
        tree = Path(tempfile.mkdtemp())
        subprocess.run(f"tar -C {REPO} --exclude=k6/ADR-016/results --exclude=k6/ADR-015/results --exclude=__pycache__ -cf - {' '.join(hp)} | tar -x -C {tree}",
                       shell=True, check=True)
        pf_ = tree / "k6/ADR-016/scripts/plan16.py"
        pf_.write_text(re.sub(r"^FIXED = (True|False)$", f"FIXED = {fixed}", pf_.read_text(), count=1, flags=re.M))
        sc = tree / "k6/ADR-016/scripts"
        pc_ = subprocess.run(["bash", str(sc / "campaign16.sh"), "--sha", "HEAD"], stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=tree)
        pr_ = subprocess.run(["bash", str(sc / "run16.sh"), "--cell", "paid", "--config", "cond", "--rep", "1", "--id", "x", "--images", "/dev/null"],
                             stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=tree)
        if fixed:
            check("FIXED True 사본 — campaign16 · run16이 FIXED 관문을 지난다(다음 관문 SHA 해석에서 멈춤)", "FIXED" not in pc_.stderr and "FIXED" not in pr_.stderr
                  and "SHA 해석 실패" in pc_.stderr + pr_.stderr, (pc_.stderr + pr_.stderr)[-300:])
        else:
            check("FIXED False 사본 — campaign16 · run16 실제 실행 거부(exit 2 · FIXED)", pc_.returncode == 2 and "FIXED" in pc_.stderr and pr_.returncode == 2
                  and "FIXED" in pr_.stderr, (pc_.stderr + pr_.stderr)[-300:])
        shutil.rmtree(tree, ignore_errors=True)
    # 재개는 --id로만(리뷰 L2-7): --id 없이 두 번 → 둘째는 자동 ID 폴더가 있어 거부
    sim = Path(tempfile.mkdtemp())
    p1 = subprocess.run(["bash", str(HERE / "campaign16.sh"), "--simulate", "--worktree", "--results-root", str(sim)], stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=REPO)
    p2 = subprocess.run(["bash", str(HERE / "campaign16.sh"), "--simulate", "--worktree", "--results-root", str(sim)], stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=REPO)
    check("L2-7 --id 없이 자동 ID 폴더가 이미 있으면 거부(exit 2 · --id 요구)", p1.returncode == 0 and p2.returncode == 2 and "--id" in p2.stderr, f"{p1.returncode} {p2.returncode} {p2.stderr[-200:]}")
    shutil.rmtree(sim, ignore_errors=True)
    # 요약기 예외 → 멈춤 · 사용자 보고(exit 8 — 재측정하지 않는다 · 리뷰 L1-1)
    sim = Path(tempfile.mkdtemp())
    (sim / "plan.txt").write_text("1:paid:ext:1=invalid:summarize-failed\n")
    p = subprocess.run(["bash", str(HERE / "campaign16.sh"), "--simulate", "--worktree", "--results-root", str(sim), "--id", "t", "--sim-plan", str(sim / "plan.txt")],
                       stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=REPO)
    check("summarize-failed → 멈춤 exit 8 · 재측정 없음", p.returncode == 8 and not list((sim / "t/paid/ext").glob("rep1.attempt*")), f"rc={p.returncode}")
    shutil.rmtree(sim, ignore_errors=True)
    p = subprocess.run(["bash", str(HERE / "run16.sh"), "--cell", "cost", "--config", "ext", "--rep", "2", "--id", "x", "--dry-run"],
                       stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=REPO)
    check("run16 --dry-run exit 0(기동 검사 · 시각 · VU 출력)", p.returncode == 0 and "기동 검사" in p.stdout and "VU" in p.stdout, p.stderr[-300:])

shutil.rmtree(tmp, ignore_errors=True)
print(f"\n{'통과' if not FAILS else '실패 ' + str(len(FAILS)) + '건: ' + ', '.join(FAILS)}")
sys.exit(1 if FAILS else 0)
