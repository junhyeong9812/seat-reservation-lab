#!/usr/bin/env python3
"""ADR-016 요약기(측정 명세 §1-3 '요약기' · 인터페이스 계약 C절) — 순수 판정 함수 · 회차 관문(--rep → status) · 캠페인 판정(G-정합 · G-모형 · G-목표 · G-비용 · decide_campaign) · RESULT.md.
세션 분류 · 예측 · 마감은 outcome16.py(단일 정의 — M16-1) · 계획 값은 plan16.py · 읽기 · 검증 도구는 ADR-015 · ADR-014 summarize.py를 import만 한다(고치지 않는다).

    summarize16.py --rep <회차폴더>     → rep-summary.json · 표준 출력 마지막 줄 = status(ok | invalid:<사유,…>)
    summarize16.py <캠페인 결과 폴더>    → result.json · RESULT.md

판정 입력이 없거나 깨지면 0이 아니라 None이고 그 입력의 사유로 회차가 무효다(δ̂ · err 결측을 0으로 채우지 않는다 — 명세 §1-2). 캠페인에서 None = 판정 불가."""
import csv
import gzip
import importlib.util
import json
import math
import re
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import outcome16 as O  # noqa: E402
import plan16  # noqa: E402

_spec = importlib.util.spec_from_file_location("summarize15", HERE.parent.parent / "ADR-015" / "scripts" / "summarize.py")
S15 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(S15)
S14 = S15.S14
num, load, jsonl, ts, p99 = S14.num, S14.load, S14.jsonl, S14.ts, S14.p99
much_higher, rng = S14.much_higher, S14.rng
S14.INPUT_REASON.update({"act16_before": "app-metric-missing", "act16_end": "app-metric-missing", "table_before": "table-stats-missing",
                         "table_end": "table-stats-missing", "sessions": "sessions-missing", "reservations": "reservation-dump-missing",
                         "tw": "tw-missing", "hold_rows": "hold-rows-missing", "pgss": "pgss-missing", "clock_err": "clock-err-missing",
                         "delta": "delta-missing", "probe16": "probe16-missing"})

TARGET_WRONGED, TARGET_LOSS, CONF = 0.001, 0.05, 0.95   # Q14 · 한쪽 95%(명세 G-목표)
ORDER = ["cond+c", "cond+b+c", "cond+b", "ext+c", "ext"]   # ①+ⓒ > ①+ⓑ+ⓒ > ①+ⓑ > ④+ⓒ > ④
COST_METRICS = ["p99.confirm_200", "p99.hold_201", "p99.pay_start_200", "db_cpu", "hold_rows_wend", "tw_server_max"]
PAID_COST_METRICS = ["rehold.never_ratio", "occupancy.mean_s"]   # 다른 사용자 비용(설계-9.6 — paid 셀 · 기준 = ① 흐름 n)
UPDATE_SEAT_HOLD = re.compile(r"^\s*update\s+seat_hold\b", re.I)
WRITE_STMT = re.compile(r"\b(insert\s+into|update|delete\s+from)\s+([a-z_][a-z0-9_]*)", re.I)
COST_NO_BASE = {"p99.pay_start_200": "cost ①(cond)은 payment-start를 부르지 않아 기준값이 없다 — 비교에서 뺀다"}
PAID_COST_PAIR = {"ext": ("e", "n"), "ext+c": ("ec", "nc")}   # 다른 사용자 비용 짝(리뷰 L1-11): ④ 흐름 대 같은 ⓒ 설정의 ① 흐름(ⓑ 없음 — 기준 ①)


PROBE_TABLES = ("seat_hold", "product_seat", "user_hold_quota", "reservation")


def probe16_check(inp, plan):
    """격리 탐침(probe16.json — run16 probe16): 홀드 P개 뒤 payment-start만 P회 흘린 구간의 증분. 반환 {writes: [증분 > 0 항목], bad: [탐침 성립 안 함], …} · 파일 없음 = None(결측 무효).
    판정 = pgss 쓰기 문장 rows(영향 행) 증분 0(1차 — 배치 틱의 0행 문장 호출은 세지 않는다 · 호출 수는 기록만) + 표 증분 n_tup_ins · upd · del 0(양성 대조가 성립할 때만 — 불성립 = bad)."""
    pr = inp.json("probe16", "probe16.json", need=("holds", "pay_codes", "table_base", "table_before", "table_after", "pgss_before", "pgss_after"))
    if pr is None:
        return None
    P = plan.get("probe16", {}).get("holds", 0)
    bad = []
    if len(pr["holds"]) != P:
        bad.append(f"홀드 {len(pr['holds'])}/{P}")
    if len(pr["pay_codes"]) != P or any(str(c) != "200" for c in pr["pay_codes"]):
        bad.append(f"payment-start 200이 아님 {sorted(set(map(str, pr['pay_codes'])))} × {len(pr['pay_codes'])}")
    writes = []
    # 양성 대조(리뷰 L3-3): 기준 → 전 스냅숏의 seat_hold 삽입 증분 = P여야 표 통계 반영이 끝났다 — 아니면 표 증분 0은 증거가 아니다(탐침 불성립)
    ctl = _d(pr["table_base"], pr["table_before"], "tables", "seat_hold", "n_tup_ins")
    if ctl != P:
        bad.append(f"양성 대조 seat_hold n_tup_ins 증분 {ctl} ≠ 홀드 {P}(통계 반영 미완)")
    for t in PROBE_TABLES:
        for k in ("n_tup_ins", "n_tup_upd", "n_tup_del"):
            dv = _d(pr["table_before"], pr["table_after"], "tables", t, k)
            if dv is None:
                bad.append(f"{t}.{k} 결측")
            elif dv:
                writes.append(f"{t}.{k} +{dv}")
    rb = {r.get("q"): r.get("rows", 0) for r in (pr["pgss_before"].get("rows") or [])}
    calls = {}
    for r in pr["pgss_after"].get("rows") or []:
        q = r.get("q")
        if not write_stmts([r]):
            continue
        dv = (r.get("rows") or 0) - rb.get(q, 0)
        if dv:
            writes.append(f"pgss rows +{dv}: {q[:80]}")
        calls[q[:80]] = r.get("calls", 0)
    return {"writes": writes, "bad": bad, "holds": len(pr["holds"]), "pay": len(pr["pay_codes"]), "write_calls_after": calls}


def write_stmts(rows):
    """pgss 행 → [(동사, 표, 호출 수, 문장 앞머리)] — 문장 머리가 쓰기(INSERT · UPDATE · DELETE)이거나 WITH … 안에 쓰기가 있는 것."""
    out = []
    for r in rows or []:
        q = (r.get("q") or "").strip()
        head = q.split(None, 1)[0].lower() if q else ""
        if head in ("insert", "update", "delete", "with"):
            for m in WRITE_STMT.finditer(q if head == "with" else q[:120]):
                out.append((m.group(1).split()[0].lower(), m.group(2).lower(), r.get("calls", 0), q[:80]))
                if head != "with":
                    break
    return out


# ==== 순수 판정 함수(인터페이스 C절) ===============================================================================================
def _binom_cdf(k, n, p):
    if p <= 0:
        return 1.0
    if p >= 1:
        return 1.0 if k >= n else 0.0
    lp, lq = math.log(p), math.log1p(-p)
    terms = [math.lgamma(n + 1) - math.lgamma(i + 1) - math.lgamma(n - i + 1) + i * lp + (n - i) * lq for i in range(k + 1)]
    m = max(terms)
    return min(1.0, math.exp(m) * sum(math.exp(t - m) for t in terms))


def cp_upper(k, n, conf=0.95):
    """Clopper-Pearson 한쪽 상한: P(X ≤ k | n, p) = 1 − conf인 p. k = 0이면 1 − (1 − conf)^(1/n) · k ≥ n이면 1 · n ≤ 0이면 None."""
    if n is None or n <= 0:
        return None
    if k < 0:
        return None
    if k >= n:
        return 1.0
    if k == 0:
        return 1 - (1 - conf) ** (1 / n)
    lo, hi = k / n, 1.0
    for _ in range(200):
        mid = (lo + hi) / 2
        if _binom_cdf(k, n, mid) > 1 - conf:
            lo = mid
        else:
            hi = mid
    return hi


def g_target(wronged, n, pre_reject, pay_fail):
    """G-목표: 억울 한쪽 95% 상한 ≤ 0.1% 그리고 (결제 전 거부 + 결제 실패) / n ≤ 5%(3회 합 점추정). n ≤ 0이면 False."""
    if n is None or n <= 0:
        return False
    up = cp_upper(wronged, n, CONF)
    return up is not None and up <= TARGET_WRONGED and (pre_reject + pay_fail) / n <= TARGET_LOSS


def g_model(rows):
    """G-모형 세션 대조: rows = [(predicted, in_band, observed)]. observed가 4갈래 밖 = 판정 밖(목록에 사유 · 결과 False) ·
    띠 밖 불일치 1건이면 False · 띠 안 불일치는 허용('허용'으로 표기). 행이 없으면 None(검출력 없음 — 결함 증거가 아니라 판정 불가 · 리뷰 L3-4)."""
    out, ok = [], True
    rows = list(rows)
    if not rows:
        return None, [{"kind": "행 없음"}]
    for i, (pred, band, obs) in enumerate(rows):
        if obs not in O.CATEGORIES:
            ok = False
            out.append({"i": i, "kind": "판정 밖", "predicted": pred, "observed": obs})
        elif pred != obs:
            if band:
                out.append({"i": i, "kind": "허용", "predicted": pred, "observed": obs})
            else:
                ok = False
                out.append({"i": i, "kind": "띠 밖", "predicted": pred, "observed": obs})
    return ok, out


def n16_5(k6_non2xx, k6_confirm_status0, server_delta):
    """N16-5: 키 'rejected:<code>' · 'failed:<status>'마다 k6 ≤ 서버 ≤ k6 + 확정 status 0(어느 쪽에만 있는 키는 없는 쪽 0) · 서버 값 None = 결측(False)."""
    if server_delta is None or k6_non2xx is None or k6_confirm_status0 is None:
        return False, ["결측(서버 지표 또는 k6 계수 없음)"]
    bad = []
    for key in sorted(set(k6_non2xx) | set(server_delta)):
        k, s = k6_non2xx.get(key, 0), server_delta.get(key, 0)
        if s is None or k is None:
            bad.append(f"{key}: 결측")
        elif not k <= s <= k + k6_confirm_status0:
            bad.append(f"{key}: k6 {k} · 서버 {s} · status0 {k6_confirm_status0}")
    return not bad, bad


def boundary_ok(rows):
    """ⓒ 경계 흐름: rows = [(sent_before_deadline, category)] — 마감 전 송신인데 wronged 1건이라도 → False · 마감 전 송신 0건 → False(검출력 없음)."""
    c = {"sent_before": 0, "wronged_before": 0, "not_before": 0, "categories": {}}
    for before, cat in rows:
        c["categories"][cat] = c["categories"].get(cat, 0) + 1
        if before:
            c["sent_before"] += 1
            c["wronged_before"] += cat == O.WRONGED
        else:
            c["not_before"] += 1
    return c["sent_before"] > 0 and c["wronged_before"] == 0, c


def flow_count_ok(planned, classified):
    """흐름별 계획 세션 수 = 분류된 세션 수(4갈래 + 결함 사유 모두). classified = {흐름: {분류: 수}}(정수도 받는다)."""
    bad = []
    for f in sorted(set(planned) | set(classified)):
        v = classified.get(f, 0)
        got = sum(v.values()) if isinstance(v, dict) else v
        if planned.get(f, 0) != got:
            bad.append(f"{f}: 계획 {planned.get(f, 0)} · 분류 {got}")
    return not bad, bad


def early_reject(rows, theta):
    """잘못된 결제 전 거부: rows = [(e0, ps_send, ps_recv, err, st, code)] · 서버 판정 시각 상한으로 본 남은 시간(e0 − ps_recv − err) > θ인데 409 HOLD_EXPIRED인 수."""
    n = 0
    for e0, _s, r, err, st, code in rows:
        try:
            st = int(st)
        except (TypeError, ValueError):
            continue
        if (st, code) == (409, "HOLD_EXPIRED") and e0 - r - err > theta:
            n += 1
    return n


def _gate_state(vals):
    vals = list(vals)
    if any(v is False for v in vals):
        return "fail"
    if any(v is None for v in vals):
        return "undetermined"
    return "pass"


def decide_campaign(cands):
    """순서 ORDER의 첫 통과 후보. 관문 = g_consistency · g_model · g_target(+ ⓒ 후보는 boundary_ok와 짝(이름에서 '+c'를 뗀 후보)의 g_model).
    False가 하나라도 = 탈락 · None이 있으면 판정 불가 · 앞 후보가 판정 불가인 채 뒤 후보가 통과하면 status = undetermined(앞 후보가 이길 수 있었다).
    g_model False(띠 밖 불일치 = 서버 또는 하네스 결함 의심)는 탈락이 아니라 판정 불가 + report '원인 조사:<후보>'(계약 C절 정정 · 리뷰 L2-1 — ⓒ 후보의 짝도 같다)."""
    report, state, undet_before = [], {}, False
    pick = None
    for name in ORDER:
        c = cands.get(name) or {}
        gm_ = lambda v: None if v is False else v   # noqa: E731 — G-모형 False = 결함 의심 → 판정 불가(묻지 않는다)
        g = [c.get("g_consistency"), gm_(c.get("g_model")), c.get("g_target")]
        if c.get("g_model") is False:
            report.append(f"원인 조사:{name} — G-모형 띠 밖 불일치(서버 또는 하네스 결함 의심)")
        if name.endswith("+c"):
            pair = cands.get(name[:-2]) or {}
            g += [c.get("boundary_ok"), gm_(pair.get("g_model"))]
            if pair.get("g_model") is False:
                report.append(f"원인 조사:{name} — 짝 {name[:-2]} G-모형 띠 밖 불일치")
        st = _gate_state(g)
        if c.get("g_model") is False or (name.endswith("+c") and (cands.get(name[:-2]) or {}).get("g_model") is False):
            st = "undetermined"   # 결함 의심은 다른 관문이 False여도 묻지 않는다(리뷰 L3-4 · OQ-3) — 원인 조사 뒤 다시 본다
        if st == "pass" and any(str(x).startswith("판정 불가:") for x in c.get("cost_report") or []):
            st = "undetermined"   # G-비용 지표 범위를 못 만들었다(리뷰 L1-3) — 비용 보고 여부를 모른 채 고르지 않는다
        state[name] = st
        report.append(f"{name}: {st} — 정합 {c.get('g_consistency')} · 모형 {c.get('g_model')} · 목표 {c.get('g_target')}"
                      + (f" · 경계 {c.get('boundary_ok')} · 짝 {name[:-2]} 모형 {(cands.get(name[:-2]) or {}).get('g_model')}" if name.endswith("+c") else ""))
        if st == "pass" and pick is None:
            pick = name
            break
        if st == "undetermined":
            undet_before = True
    if pick is not None:
        status = "undetermined" if undet_before else "pick"
        cr = (cands.get(pick) or {}).get("cost_report") or []
        if cr:
            report.append(f"G-비용 보고({pick}): ① 대비 ≫ 지표 {cr}")
        if undet_before:
            report.append(f"앞 후보 판정 불가 — {pick} 통과를 결론으로 쓰지 않는다")
    else:
        status = "undetermined" if any(v == "undetermined" for v in state.values()) else "none"
    return {"pick": pick, "status": status, "report": report, "state": state}


# ==== 회차 입력 ==================================================================================================================
def read_sessions(path):
    """sessions16.log.gz(k6 --log-format=json --console-output) → (세션 행 k='s', 재선점 행 k='r', 깨진 줄 수). 파일 없음 = None."""
    p = Path(path)
    if not p.exists():
        return None
    rows, rh, broken = [], [], 0
    with gzip.open(p, "rt", errors="replace") as f:
        lines = [x.strip() for x in f if x.strip()]
    for i, line in enumerate(lines):
        try:
            m = json.loads(json.loads(line)["msg"])
        except (ValueError, KeyError, TypeError):
            broken += i != len(lines) - 1   # 찢긴 마지막 줄 하나는 세지 않는다(그 세션은 흐름 수 대조가 잡는다)
            continue
        if isinstance(m, dict) and m.get("k") == "s":
            rows.append(m)
        elif isinstance(m, dict) and m.get("k") == "r":
            rh.append(m)
        else:
            broken += 1
    return rows, rh, broken


def read_s16(path):
    """cost 셀 집계 계수(CSV s16{fl, out} · r16{got}) → ({흐름: {out: 수}}, {got: 수})."""
    s16, r16 = {}, {}
    with gzip.open(path, "rt", newline="") as f:
        rd = csv.reader(f)
        head = next(rd)
        im, iv, ie = head.index("metric_name"), head.index("metric_value"), head.index("extra_tags")
        for r in rd:
            if not r or r[im] not in ("s16", "r16"):
                continue
            tg = S15.parse_tags(r[ie])
            v = int(float(r[iv]))
            if r[im] == "s16":
                d = s16.setdefault(tg.get("fl", "?"), {})
                d[tg.get("out", "?")] = d.get(tg.get("out", "?"), 0) + v
            else:
                r16[tg.get("got", "?")] = r16.get(tg.get("got", "?"), 0) + v
    return s16, r16


def _d(a, b, *path):
    return S15._d(a, b, *path)


def _q(xs, p):
    xs = sorted(x for x in xs if num(x))
    return xs[min(len(xs) - 1, int(p * len(xs)))] if xs else None


def env_common(d, inp, plan, reasons, M):
    """ADR-015 rep_summary의 환경 · 하네스 관문(같은 사유 이름) — 반환 (t0, w0, w1, off, err_ms, csvd)."""
    if (d / "harness-reasons.txt").exists():
        reasons.update(x.strip() for x in (d / "harness-reasons.txt").read_text().splitlines() if x.strip())
    else:
        inp.miss("harness_reasons", "harness-reasons.txt 없음")
    ks = inp.json("k6_summary", "k6-summary.json", nums=("setup_data.startedAt",))
    clk = S14.clock(inp)
    if clk and (clk["rtt_half_max"] > S14.CLOCK_RTT_HALF_MAX_S or clk["drift"] > S14.CLOCK_DRIFT_MAX_S):
        reasons.add("clock-offset-bad")
    off = clk["off"] if clk else None
    t0 = ks["setup_data"]["startedAt"] / 1000 if ks else None
    w0 = t0 + plan["window"]["from_s"] if t0 is not None else None
    w1 = w0 + plan["window"]["len_s"] if w0 is not None else None
    # 환경 점검 창(리뷰 L1-5): paid = [t0, t0 + d_arr + grace](k6 끝이 먼저면 k6 끝) — 판정 대상 세션이 k6 끝까지 살아 있다. cost = 판정 창.
    #   정지 덩어리(env-stall)만 도착 창 [w0, w1)로 센다(도착이 끝난 뒤 꼬리는 요청이 드물어 빈 초가 정상이다)
    kw0 = inp.json("k6_window", "k6-window.json", nums=("exit",))
    we1 = w1
    if plan["cell"] == "paid" and t0 is not None and num(plan.get("grace_s")):
        we1 = t0 + plan["d_arr_s"] + plan["grace_s"]
        if kw0 and num(kw0.get("k6_end_epoch")):
            we1 = min(we1, kw0["k6_end_epoch"])
    M.update({"t0_pc": t0, "window_pc": [w0, w1], "env_window_pc": [w0, we1], "off_s": off})
    pk = inp.json("pre_k6", "pre-k6.json", nums=("seed_done_epoch",))
    if pk and t0 is not None:
        M["seed_to_first_request_s"] = t0 - pk["seed_done_epoch"]
        if abs(M["seed_to_first_request_s"] - S14.PRE_K6_TARGET_S) > S14.PRE_K6_TOL_S:
            reasons.add("pre-k6-mismatch")
    if (d / "app-config.json").exists():
        _rc, cc = plan16.config_check(plan["cell"], plan["config"], d / "app-config.json")
        reasons.update(cc["reasons"])
        M["config_detail"] = cc["detail"]
    else:
        inp.miss("app_config", "app-config.json 없음")
    wu = inp.json("warmup", "warmup-status.json", nums=("rc",))
    if wu is not None and wu["rc"] != 0:
        reasons.add("warmup-failed")
    sc = inp.json("seed_check", "seed-check.json", nums=("schedules", "want_schedules", "seats", "want_seats", "available", "holds", "reservations",
                                                          "quota_rows", "v12_valid", "v13_column", "v14_triggers"))
    if sc:
        if not (sc["schedules"] == sc["want_schedules"] and sc["seats"] == sc["want_seats"] == sc["available"]
                and sc["holds"] == 0 and sc["reservations"] == 0 and sc["quota_rows"] == 0):
            reasons.add("seed-mismatch")
        if sc["v12_valid"] != 1 or sc["v13_column"] != 1 or sc["v14_triggers"] > 0:
            reasons.add("schema-mismatch")
    ai = (d / "app-instances.txt").read_text().split() if (d / "app-instances.txt").exists() else None
    if ai is None or len(ai) != 1:
        reasons.add("app-instances-not-one")
    pl = inp.json("pc_link", "pc-link.json", need=("readable",), nums=("down",))
    if pl is not None:
        if pl["readable"] is not True:
            inp.miss("pc_link", "pc-link.json readable 아님")
        elif pl["down"] > 0:
            reasons.add("pc-link-down")
    if w0 is not None:   # k6 PC MemAvailable 하한(ADR-015 rep_summary와 같은 규칙)
        cl = jsonl(d / "timeline-client.jsonl")
        if cl is None:
            inp.miss("client", "timeline-client.jsonl 없음")
        elif cl.broken:
            inp.miss("client", f"timeline-client.jsonl 깨진 줄 {cl.broken}")
        else:
            mrows, mbad = [], 0
            for r in cl:
                try:
                    t = ts(r["t"])
                except (KeyError, ValueError, TypeError):
                    mbad += 1
                    continue
                if w0 <= t < we1:
                    if num(r.get("mem_avail_kb")):
                        mrows.append((t, r["mem_avail_kb"]))
                    else:
                        mbad += 1
            tt = [w0] + [t for t, _ in mrows] + [we1]
            gap = max(b - a for a, b in zip(tt, tt[1:]))
            if mbad or not mrows or gap > S14.plan14.PC_MEM_GAP_MAX_S:
                inp.miss("client", f"MemAvailable 표본 — 못 읽는 줄 {mbad} · 창 안 {len(mrows)}개 · 최대 공백 {gap:.0f}초")
            else:
                mn = min(m for _, m in mrows)
                M["pc_mem"] = {"min_kb": mn, "n": len(mrows), "gap_max_s": gap}
                if mn < S14.plan14.PC_MEM_RUN_MIN_KB:
                    reasons.add("pc-mem-low")
    pc = inp.json("probe_check", "probe-check.json", need=("ok",))
    if pc is not None and pc["ok"] is not True:
        reasons.add("probe-missing")
    kw = inp.json("k6_window", "k6-window.json", nums=("exit",))
    if kw is not None and kw["exit"] != 0:
        reasons.add(f"k6-exit-{kw['exit']:g}")
    err_ms = None   # 시계 차 오차 상한(ms) — ADR-015 rep_summary와 같은 식
    if clk and kw is not None and num(kw.get("clock_offset_ms_given")):
        g = kw["clock_offset_ms_given"]
        err_ms = max(abs(g - clk["before"]["offset_s"] * 1000), abs(g - clk["after"]["offset_s"] * 1000)) + clk["rtt_half_max"] * 1000 + 1
    M["clock_err_ms"] = err_ms
    M["clock_err_ms_given"] = kw.get("clock_err_ms_given") if kw else None
    csvd = None
    if (d / "k6-requests.csv.gz").exists() and w0 is not None:
        try:
            csvd = S15.read_csv(d / "k6-requests.csv.gz", w0, we1)
        except (OSError, EOFError, ValueError, StopIteration, IndexError, csv.Error) as e:
            inp.miss("csv", f"CSV 읽기 실패 {type(e).__name__}")
    else:
        inp.miss("csv", "k6-requests.csv.gz 없음 또는 창 모름")
    srows, tlines = S14.server_rows(d / "timeline-server.jsonl", off) if off is not None else (None, None)
    if srows is None:
        inp.miss("server", "timeline-server.jsonl 없음")
    elif srows.broken:
        inp.miss("server", f"timeline-server.jsonl 깨진 줄 {srows.broken}")
    elif w0 is not None:
        dc = S14.db_cpu(srows, w0, we1)
        M["db_cpu"] = dc["mean"]
        if dc["missing"]:
            reasons.add("db-cpu-missing")
        g, _ = S14.gaps_ok([t for t in tlines if w0 <= t < we1], w0, we1, S14.PATH_GAP_MAX_S)
        if g > S14.PATH_GAP_MAX_S:
            reasons.add("path-gap")
    gc = None
    if t0 is not None and off is not None and (d / "gc.log.gz").exists():
        gc = S14.S9.gc_summary(d / "gc.log.gz", (w0 + off, we1 + off), w0 + off)
    if not gc or not gc.get("collector"):
        inp.miss("gc", "GC 로그 없음·빈 로그")
    ls = (d / "logs-status.txt").read_text().splitlines() if (d / "logs-status.txt").exists() else None
    if ls is None or any(("pipe=0/0" not in x) or x.rstrip().endswith("bytes=0") for x in ls if x.strip()) or len([x for x in ls if x.strip()]) < 2:
        inp.miss("logs", "logs-status.txt 파이프 실패·빈 로그")
    waits = jsonl(d / "timeline-waits.jsonl")
    if waits is None:
        inp.miss("waits", "timeline-waits.jsonl 없음")
    if csvd is not None:
        M["p99"] = {k: p99(csvd["lat"].get(n) or []) for k, n in (("confirm_200", "confirm:200"), ("hold_201", "hold:201"), ("pay_start_200", "pay_start:200"),
                                                                 ("rehold_409", "rehold:409"))}
        M["dropped"] = csvd["dropped"]
        if sum(csvd["dropped"].values()) > 0:
            reasons.add("dropped-iterations")
        M["code0_other"] = csvd["code0_other"]
        if csvd["code0_other"]:
            reasons.add("k6-code0")
        M["err5"], M["timeout"], M["req"] = csvd["err5"], csvd["timeout"], csvd["req"]
        hik = jsonl(d / "timeline-hikari.jsonl")
        for h in hik or []:
            h["_t"] = h["t_ms"] / 1000 if num(h.get("t_ms")) else ts(h["t"])
        probe_srv = None
        if (d / "k6-probe-server.csv.gz").exists() and off is not None:
            try:
                probe_srv = S14._probe_csv(d / "k6-probe-server.csv.gz", off)
            except (OSError, EOFError, ValueError, StopIteration):
                probe_srv = None
        hmax = ((load(d / "app-config.json") or {}).get("hikaricp.connections.max") or [None])[0]
        secs, chunks = S14.stall_chunks(csvd["per_sec"], w0, w1)   # 도착 창(위 주석)
        env_s, cls = 0, []
        for a, b in chunks:
            if b - a >= S14.STALL_MIN_S:
                k, why = S14.classify_chunk(a, b, off, probe_srv, (gc or {}).get("long_stops", []), waits, hik, hmax)
                cls.append({"from_rel": a - w0, "len": b - a, "class": k, **why})
                env_s += (b - a) if k == "env" else 0
        M["stall"] = {"secs": len(secs), "env_secs": env_s, "chunks": cls}
        if env_s > 0:
            reasons.add("env-stall")
        if any(c["class"] == "method" for c in cls):
            M["method_stall_s"] = sum(c["len"] for c in cls if c["class"] == "method")
    return t0, w0, w1, off, err_ms, csvd


def confirm_key(st, code):
    """확정 비2xx → N16-5 키(도메인 거부 403 · 404 · 409 + 본문 code = rejected · 그 밖 = failed). 200 · status 0 = None."""
    st = int(st)
    if st == 0 or 200 <= st < 300:
        return None
    if st in (403, 404, 409) and code not in (None, "", "NOCODE"):
        return f"rejected:{code}"
    return f"failed:{st}"


def server_n16_5(a0, a1):
    if not (a0 and a1) or not isinstance(a0.get("rejected"), dict) or not isinstance(a1.get("rejected"), dict) \
            or not isinstance(a0.get("failed"), dict) or not isinstance(a1.get("failed"), dict):
        return None
    out = {}
    for grp in ("rejected", "failed"):
        for k in set(a0[grp]) | set(a1[grp]):
            b, e = a0[grp].get(k, 0), a1[grp].get(k, 0)
            if not (num(b) and num(e)):
                return None
            if e - b:
                out[f"{grp}:{k}"] = e - b
    return out


def pay_start_cross(mode, k6c, s0, a0, a1):
    """서버 교차(명세 G-정합): ① pay_start{result} 모든 결과 · ④ extend{result}가 세는 결과만(DV16-R2). k6c = {'200','409','404','403'}: 수 · s0 = payment-start status 0."""
    if not (a0 and a1):
        return None, ["act16 없음"]
    bad = []
    rng_ok = lambda k, s: s is not None and k <= s <= k + s0   # noqa: E731
    if mode == O.MODE_COND:
        D = lambda r: _d(a0, a1, "pay_start", r)   # noqa: E731
        ins, exp = D("insufficient"), D("expired")
        pairs = {"ok": (k6c.get("200", 0), D("ok")), "expired+insufficient": (k6c.get("409", 0), ins + exp if num(ins) and num(exp) else None),
                 "not_found": (k6c.get("404", 0), D("not_found")), "not_owned": (k6c.get("403", 0), D("not_owned"))}
        for k, (kv, sv) in pairs.items():
            if not rng_ok(kv, sv):
                bad.append(f"pay_start {k}: k6 {kv} · 서버 {sv} · status0 {s0}")
    else:
        D = lambda r: _d(a0, a1, "extend", r)   # noqa: E731
        parts = [D(r) for r in ("extended", "already", "not_needed")]
        s200 = sum(parts) if all(num(x) for x in parts) else None
        if not rng_ok(k6c.get("200", 0), s200):
            bad.append(f"extend 200: k6 {k6c.get('200', 0)} · 서버 extended+already+not_needed {s200}")
        tl = D("too_late")
        if not (num(tl) and tl <= k6c.get("409", 0) + s0):
            bad.append(f"extend too_late {tl} > k6 409 {k6c.get('409', 0)} + status0 {s0}")
    return not bad, bad


# ==== 회차 요약 ==================================================================================================================
def rep_summary(d):
    d = Path(d)
    inp = S14.Inputs(d)
    reasons, M, F = set(), {}, {}
    plan = inp.json("plan", "plan.json", need=("cell", "config", "mode", "planned", "knobs16"),
                    nums=("rep", "d_arr_s", "window.from_s", "window.len_s", "n_total", "cap_s"))
    if plan is not None and (plan.get("adr") != "016" or plan["cell"] not in ("paid", "cost") or plan["config"] not in plan16.CONFIGS[plan["cell"]]):
        plan = inp.miss("plan", "plan.json이 ADR-016 paid · cost 계획이 아님")
    if plan is None:
        return _write(d, {"status": "invalid:plan-missing", "reasons": ["plan-missing"]})
    cell, config, mode = plan["cell"], plan["config"], plan["mode"]
    k = plan["knobs16"]
    M.update({"cell": cell, "config": config, "mode": mode, "rep": plan["rep"]})
    t0, w0, w1, off, err_ms, csvd = env_common(d, inp, plan, reasons, M)
    cons = inp.json("consistency", "consistency.json", nums=S15.CONS_ZERO)
    if cons:
        bad = {c: cons[c] for c in S15.CONS_ZERO if cons[c] != 0}
        if bad:
            F["cons"] = bad
    tb = inp.json("table_before", "table-before.json", nums=("deadlocks",))
    te = inp.json("table_end", "table-end.json", nums=("deadlocks",))
    a16 = {x: inp.json(f"act16_{x}", f"act16-{x}.json", need=("pay_start", "extend")) for x in ("before", "end")}
    for x, a in a16.items():
        if a and a.get("failed_names"):
            a16[x] = inp.miss(f"act16_{x}", f"지표 조회 실패 {a['failed_names']}")
    a0, a1 = a16["before"], a16["end"]
    srv5 = server_n16_5(a0, a1)
    if a0 and a1 and srv5 is None:
        inp.miss("act16_end", "act16 rejected · failed 결측")
    # N16-1 입력 = ADR-016 전용 pgss 쓰기 문장 덤프(run16 pgss16_writes — 표 필터 없음 · 문장 전문 · 리뷰 L2-3). ADR-015 pgss-end.json(표 필터 · 200자)은 설명용
    pg = load(d / "pgss16-end.json")
    if not (isinstance(pg, dict) and isinstance(pg.get("rows"), list)):
        pg = inp.miss("pgss", "pgss16-end.json 없음·깨짐")
    if tb and te:
        M["deadlocks"] = te["deadlocks"] - tb["deadlocks"]
        if M["deadlocks"]:
            F["deadlock"] = M["deadlocks"]
        M["db_tup"] = {f"{t}.{c}": _d(tb, te, "tables", t, c) for t, c in (("seat_hold", "n_tup_upd"), ("seat_hold", "n_tup_del"), ("reservation", "n_tup_ins"))}
    tw = jsonl(d / "timeline-tw.jsonl")   # PC TIME_WAIT(리뷰 L1-6): ss 실패 표본(err)이 하나라도 있으면 결측 — 0으로 세지 않는다
    tw_bad = tw is None or tw.broken or not tw or any("err" in r for r in tw) or not all(num(r.get("tw_server")) and num(r.get("tw_all")) for r in tw)
    if tw_bad and plan["cell"] == "cost":   # TIME_WAIT는 cost 지표(명세 §1-3) — paid 회차는 기록만(결측이면 값 없음 · 무효 아님 · 메인 결정 2026-11-08)
        inp.miss("tw", "timeline-tw.jsonl 없음·깨짐·ss 실패 표본")
    elif tw_bad:
        M["tw_server_max"] = M["tw_all_max"] = None
    else:
        M["tw_server_max"] = max(r["tw_server"] for r in tw)
        M["tw_all_max"] = max(r["tw_all"] for r in tw)
    k6c, s0p = {}, 0          # payment-start k6 계수(서버 교차)
    k6_non2xx, s0c = {}, 0    # 확정 비2xx(N16-5)
    if cell == "paid":
        paid_rep(d, inp, plan, k, err_ms, reasons, M, F, k6c, k6_non2xx)
        s0p, s0c = M.pop("_s0p", 0), M.pop("_s0c", 0)
    elif csvd is not None:
        cost_rep(d, inp, plan, reasons, M, F, k6c, k6_non2xx)
        s0p, s0c = M.pop("_s0p", 0), M.pop("_s0c", 0)
        hr = inp.json("hold_rows", "hold-rows-wend.json", nums=("holds",))
        if hr:
            M["hold_rows_wend"] = hr["holds"]
            M["occupancy"] = {"mean_s": hr["holds"] / plan["rate"]}   # Little: 동시 홀드 ÷ 도착률(cost는 세션 행이 없다)
    if "k6_non2xx_ready" in M:
        M.pop("k6_non2xx_ready")
        ok5, bad5 = n16_5(k6_non2xx, s0c, srv5)
        M["n16_5"] = {"ok": ok5, "detail": bad5, "k6": k6_non2xx, "server": srv5, "status0": s0c}
        if srv5 is not None and not ok5:
            F["n16_5"] = bad5
        if mode == O.MODE_EXT or any(v["b"] for v in plan["flows"].values()):
            okp, badp = pay_start_cross(mode, k6c, s0p, a0, a1)
            M["pay_start_cross"] = {"ok": okp, "detail": badp, "k6": k6c, "status0": s0p}
            if okp is False:
                F["pay_start_mismatch"] = badp
    # N16-1 부하 증거(리뷰 L1-9) — pgss로 '어느 문장이 payment-start 경로인가'는 가를 수 없다(같은 문장을 여러 경로가 낸다). 그래서 세 겹으로 본다:
    #   (a) ① 회차 seat_hold n_tup_upd 증분 0 + pgss UPDATE seat_hold 호출 0(① 코드에 seat_hold UPDATE가 없다 — 있으면 payment-start가 썼다는 신호)
    #   (b) 허용 목록(plan16.ALLOWED_WRITES — 자리표시 · 측정 전 고정) 밖 쓰기 문장 0(모르는 쓰기 = 새 쓰기 경로)
    #   (c) payment-start 5xx 0 — ① 확인은 @Transactional(readOnly = true)(PaymentStartService.check)라 쓰기를 시도하면 오류 → 5xx로 드러난다(아래 F["5xx"] · mode_fail)
    if pg:
        wr = write_stmts(pg["rows"])
        allowed = set(plan16.ALLOWED_WRITES.get(mode, []))
        unk = [w for w in wr if (w[0], w[1]) not in allowed and w[2]]
        M["writes"] = {"stmts": [list(w) for w in wr][:50], "unknown": [list(w) for w in unk]}
        if unk:
            F["write_unknown"] = [f"{w[0]} {w[1]} × {w[2]}: {w[3]}" for w in unk]
    if mode == O.MODE_COND:   # N16-1 격리 탐침(리뷰 L2-9 — 경로별 증거 · 주 판정): ① check만 흐른 구간의 쓰기 증분 0 · 위 세 겹은 보조
        pr16 = probe16_check(inp, plan)
        if pr16 is not None:
            M["n16_1_probe"] = pr16
            if pr16["bad"]:
                reasons.add("probe16-bad")
            elif pr16["writes"]:
                F["n16_1_probe"] = pr16["writes"]
    if mode == O.MODE_COND and tb and te and pg:
        upd = _d(tb, te, "tables", "seat_hold", "n_tup_upd")
        stm = sum(r.get("calls", 0) for r in pg["rows"] if UPDATE_SEAT_HOLD.match(r.get("q") or ""))
        M["n16_1"] = {"seat_hold_n_tup_upd": upd, "update_seat_hold_calls": stm, "pay_start_5xx": csvd["err5"].get("pay_start", 0) if csvd else None}
        if upd is None:
            inp.miss("table_end", "seat_hold n_tup_upd 없음")
        elif upd != 0 or stm != 0 or M["n16_1"]["pay_start_5xx"]:
            F["n16_1"] = M["n16_1"]
    U = {}   # 판정 불가 자료(실패 아님 — 리뷰 L2-2): 재선점 5xx · 시간 초과는 race 'A도 같은 문턱'처럼 원인(하네스 · 환경 · 서버)을 가를 대조가 없다 → 그 구성 판정 불가
    if csvd is not None:
        e5 = {k: v for k, v in csvd["err5"].items() if k != "rehold"}
        to = {k: v for k, v in csvd["timeout"].items() if k != "rehold"}
        if sum(e5.values()):
            F["5xx"] = e5
        if to:
            F["timeout"] = to
        ru = {f"{kind}:rehold": n for kind, src in (("5xx", csvd["err5"]), ("timeout", csvd["timeout"])) if (n := src.get("rehold", 0))}
        if ru:
            U["rehold_undetermined"] = ru
        if csvd["err5"].get("hold") or csvd["timeout"].get("hold"):
            reasons.add("hold-fail")   # 자기 좌석 첫 선점 5xx · 시간 초과 = paid와 같이 하네스 무효(L2-2 — cost도 같은 규칙)
    reasons.update(inp.reasons())
    st = "ok" if not reasons else "invalid:" + ",".join(sorted(reasons))
    return _write(d, {"status": st, "reasons": sorted(reasons), "missing": inp.missing, "metrics": M, "fail": F, "undetermined": U})


def _s(ms):
    return None if ms is None else ms / 1000


def paid_rep(d, inp, plan, k, err_ms, reasons, M, F, k6c, k6_non2xx):
    rd = read_sessions(d / "sessions16.log.gz")
    if rd is None:
        inp.miss("sessions", "sessions16.log.gz 없음")
        return
    rows, rh, broken = rd
    if broken:
        inp.miss("sessions", f"세션 행 깨진 줄 {broken}")
    rv = load(d / "reservations.json")
    if not (isinstance(rv, dict) and isinstance(rv.get("rows"), list)):
        inp.miss("reservations", "reservations.json 없음·깨짐")
        return
    if err_ms is None:
        inp.miss("clock_err", "시계 오차 상한(clock-offset · k6-window) 없음 — 0으로 채우지 않는다")
    confirmed = {r.get("u") for r in rv["rows"] if r.get("s") == "CONFIRMED"}
    flows, rep, cap = plan["flows"], plan["rep"], plan["cap_s"]
    seen, cats, by_sid = set(), {}, {}
    gm = {f: [] for f in flows}
    bd_rows, er_rows, deltas = {}, [], []
    occ = {}   # 흐름별 좌석 점유 시간(경계 흐름 제외 — 리뷰 L1-11)
    cyc = plan["flow_cycle"]
    td_bad = dl_bad = dup = delta_bad = fa_bad = pf_bad = send_bad = 0
    s_inv = {}
    for r in rows:
        sid, fl = r.get("sid"), r.get("fl")
        if sid in seen:
            dup += 1
            continue
        seen.add(sid)
        by_sid[sid] = r
        f = flows.get(fl)
        h, p, c = r.get("h") or {}, r.get("p"), r.get("c")
        rec = {"hold": {"st": h.get("st"), "ok_body": bool(h.get("ok"))},
               # 200 본문: k6 ok 표지 + 요청 홀드 ID 일치를 다시 본다(리뷰 L3-5)
               "pay": None if p is None else {"st": p.get("st"), "code": p.get("code"), "err": p.get("ec"),
                                              "ok_body": (p.get("st") != 200) or (bool(p.get("ok")) and p.get("hid") is not None and p.get("hid") == h.get("hid"))},
               "pay_fail": r.get("pf"), "confirm": None if c is None else {"st": c.get("st"), "code": c.get("code"), "err": c.get("ec")},
               "reserved": r.get("uid") in confirmed}
        cat = O.classify(rec)
        cats.setdefault(fl, {})
        cats[fl][cat] = cats[fl].get(cat, 0) + 1
        if cat.startswith("invalid:"):
            s_inv[cat] = s_inv.get(cat, 0) + 1
        if f is None or fl != cyc[sid % len(cyc)]:   # 흐름 배정 = 세션 번호 mod 바퀴 길이(리뷰 L1-7)
            fa_bad += 1
            continue
        # 기록된 T · D = 시드 재계산(하네스 결정성) · 기록된 마감 = 같은 식(outcome16.deadline)
        a = plan16.session_attrs(rep, sid, plan["rho"])
        if not (num(r.get("T")) and num(r.get("D")) and abs(r["T"] - a["T"]) <= 1e-6 * max(1, a["T"]) and abs(r["D"] - a["D"]) <= 1e-6 * max(1, a["D"])):
            td_bad += 1
        if f["bd"] and r.get("gx") != plan16.BOUNDARY_GRID_MS[a["gx"]]:
            td_bad += 1
        called = bool(f["b"]) or plan["mode"] == O.MODE_EXT
        src = p if called else h
        if r.get("dl") is not None:
            # outcome16.deadline과 같은 식(k6는 ms 단위 — pws × 1000)
            ok_src = src is not None and all(num(src.get(x)) for x in ("tr", "pws", "pb")) and num(r.get("ek"))
            want = O.deadline(src["tr"] / 1000, src["pws"], src["pb"] / 1000, r["ek"] / 1000) * 1000 if ok_src else None
            if want is None or abs(want - r["dl"]) > 1e-3:
                dl_bad += 1
        # 결제 실패 표지 재계산(리뷰 L1-15 — 기록 pf를 그대로 믿지 않는다): 결정 지점(선점 정상 ∧ payment-start를 안 부르거나 200 정상)에 간 세션만 pf를 가질 수 있다 ·
        #   경계 흐름 = 격자 x < 0이면 deadline · 그 밖 = D > CAP이면 cap, 아니고 ⓒ 켬 ∧ 결제 시작 + D > 기록 마감이면 deadline. 그리고 '확정 송신 ⇔ 결제 성공'(명세 §5 그린 위장 점검)
        reached = bool(h.get("ok")) and (not called or (p is not None and p.get("st") == 200 and bool(p.get("ok"))))
        tp = (p or {}).get("tr") if called else r.get("th")
        want_pf = None
        if reached:
            if f["bd"]:
                want_pf = "deadline" if num(r.get("gx")) and r["gx"] < 0 else None
            elif r["D"] > cap:
                want_pf = "cap"
            elif f["c"]:
                want_pf = "deadline" if not (num(tp) and num(r.get("dl"))) or tp + r["D"] * 1000 > r["dl"] else None
        if r.get("pf") != want_pf:
            pf_bad += 1
        if (c is not None) != (reached and want_pf is None):
            send_bad += 1
        if h.get("ok") and not f["bd"]:
            e_eff = p.get("exp") if (p and p.get("st") == 200 and num(p.get("exp"))) else h.get("exp")
            if c and c.get("st") == 200 and num(c.get("tr")):
                occ.setdefault(fl, []).append((c["tr"] - h["tr"]) / 1000)
            elif num(e_eff) and num(h.get("tr")):
                occ.setdefault(fl, []).append((e_eff - h["tr"]) / 1000)
        if p is not None and num(p.get("st")):
            st = int(p["st"])
            if st == 0:
                M["_s0p"] = M.get("_s0p", 0) + 1
            elif str(st) in ("200", "409", "404", "403"):
                k6c[str(st)] = k6c.get(str(st), 0) + 1
            if num(h.get("exp")) and num(p.get("ts")) and num(p.get("tr")) and err_ms is not None:
                er_rows.append((h["exp"] / 1000, p["ts"] / 1000, p["tr"] / 1000, err_ms / 1000, st, p.get("code")))
        if c is not None:
            key = confirm_key(c.get("st", 0), c.get("code"))
            if int(c.get("st", 0)) == 0:
                M["_s0c"] = M.get("_s0c", 0) + 1
            elif key:
                k6_non2xx[key] = k6_non2xx.get(key, 0) + 1
            if int(c.get("st", 0)) != 0:
                if num(c.get("ts")) and num(c.get("tr")):
                    deltas.append((c["tr"] - c["ts"]) / 1000)
                else:
                    delta_bad += 1
            if (c.get("st"), c.get("code")) == (409, "SEAT_NOT_HELD"):
                F["seat_not_held"] = F.get("seat_not_held", 0) + 1
        # payment-start 200 만료 값 검사(리뷰 L3-1 · 경계 흐름 포함 — 재점검 L4-1: 경계 분기 앞에서 모든 200 응답에): ① = 선점 만료 그대로 · ④ = 서버 :now ∈ [송신, 수신] ± err에서 남은 시간 < W면 [ps_send + W, ps_recv + W] ± err,
        #   ≥ W면 그대로(not_needed) · 문턱이 구간 안이면 둘 다 허용. 어긋나면 G-정합 실패 자료(extend_value_mismatch — 예측과 따로)
        if p is not None and p.get("st") == 200 and err_ms is not None and all(num(p.get(x)) for x in ("exp", "ts", "tr")) and num(h.get("exp")):
            Wm = k["window"] * 1000
            same = abs(p["exp"] - h["exp"]) <= 1
            ext_ok = p["ts"] + Wm - err_ms <= p["exp"] <= p["tr"] + Wm + err_ms
            if plan["mode"] == O.MODE_COND:
                good = same
            else:
                rem_lo, rem_hi = h["exp"] - p["tr"] - err_ms, h["exp"] - p["ts"] + err_ms
                good = same if rem_lo >= Wm else (ext_ok if rem_hi < Wm else (same or ext_ok))
            if not good:
                F["extend_value_mismatch"] = F.get("extend_value_mismatch", 0) + 1
        if f["bd"]:
            before = c is not None and num(c.get("ts")) and num(r.get("dl")) and c["ts"] <= r["dl"]
            bd_rows.setdefault(fl, []).append((bool(before), cat))
            continue
        if cat not in O.CATEGORIES:   # 결함 사유 세션은 예측하지 않고 g_model이 사유로 돌려주게(리뷰 L1-1 — 입력 결측으로 predict가 죽지 않게)
            gm[fl].append(("not-predicted", False, cat))
            continue
        if err_ms is None or not h.get("ok"):
            continue
        if called and not (p and num(p.get("ts")) and num(p.get("tr"))):
            continue
        e_s = err_ms / 1000
        kw = {}
        if f["c"] and src and all(num(src.get(x)) for x in ("tr", "pws", "pb")):
            # 하네스 마감 = min(수신 + pws, payBy − ek). 띠 계산의 err는 사후 오차 상한(e_s) — 마감 식이 하네스와 같도록 payBy를 ek − e_s만큼 옮겨 넣는다
            kw = {"pws": src["pws"], "src_recv": src["tr"] / 1000, "pay_by": (src["pb"] - r["ek"]) / 1000 + e_s}
        pred, band = O.predict(mode=plan["mode"], b=bool(f["b"]), c=bool(f["c"]), theta=k["theta"], window=k["window"], margin=k["margin"], cap=cap,
                               e0=h["exp"] / 1000, hold_recv=h["tr"] / 1000, think_end=_s(r.get("th")),
                               ps_send=_s(p.get("ts")) if p else None, ps_recv=_s(p.get("tr")) if p else None,
                               e_after=None, pay=r["D"],   # 응답 만료는 예측 입력이 아니다(리뷰 L3-1 — 아래 extend_value_mismatch가 따로 본다)
                               c_send=_s(c.get("ts")) if c else None, c_recv=_s(c.get("tr")) if c else None, err=e_s, **kw)
        # ⓑ · ④ 결제 시작 시각은 payment-start 수신(하네스 tp)이다 — predict도 ps_recv를 쓴다
        gm[fl].append((pred, band, cat))
    planned = plan["planned"]
    okc, badc = flow_count_ok(planned, cats)
    M["flows"] = {fl: {"planned": planned.get(fl, 0), "classified": cats.get(fl, {})} for fl in sorted(set(planned) | set(cats))}
    if not okc:
        reasons.add("flow-count-mismatch")
        M["flow_count_detail"] = badc
    for cat in s_inv:
        reasons.add("hold-fail" if cat == "invalid:hold-fail" else ("k6-code0" if cat.endswith("-code0") else "session-invalid"))
    M["session_invalid"] = s_inv
    if td_bad:
        reasons.add("session-td-mismatch")
    if dl_bad:
        reasons.add("deadline-mismatch")
    if dup:
        reasons.add("session-duplicate")
    if fa_bad:
        reasons.add("flow-assign-mismatch")
    if pf_bad:
        reasons.add("session-payfail-mismatch")
    if send_bad:
        reasons.add("confirm-send-mismatch")
    # 재선점 행(리뷰 L1-8): 시드상 ρ 대상 sid마다 결과 행이 정확히 하나 — 누락 · 중복 · 대상 밖 = rehold-count-mismatch
    want_rh = {sid for sid in range(plan["n_total"]) if plan16.session_attrs(rep, sid, plan["rho"])["rehold"]}
    got_rh = [x.get("sid") for x in rh]
    rh_bad = len(want_rh - set(got_rh)) + (len(got_rh) - len(set(got_rh))) + len(set(got_rh) - want_rh)
    if rh_bad:
        reasons.add("rehold-count-mismatch")
    if delta_bad or (not deltas and any(r.get("c") for r in rows)):
        inp.miss("delta", f"확정 송신 · 수신 시각 없음 {delta_bad}")
    M["harness_checks"] = {"td_mismatch": td_bad, "deadline_mismatch": dl_bad, "duplicate": dup, "flow_assign_mismatch": fa_bad,
                           "payfail_mismatch": pf_bad, "confirm_send_mismatch": send_bad, "rehold_count_mismatch": rh_bad}
    integ = {c: n for fl in cats.values() for c, n in fl.items() if c.startswith("integrity:")}
    mfail = {c: n for fl in cats.values() for c, n in fl.items() if c.startswith("mode_fail:")}
    if integ:
        F["integrity"] = integ
    if mfail:
        F["mode_fail"] = mfail
    uids = {r.get("uid") for r in rows}
    orphan = sorted(u for u in confirmed if u not in uids)
    if orphan:
        F["reservation_orphan"] = len(orphan)
    M["delta"] = {"n": len(deltas), "p50": _q(deltas, 0.5), "p99": _q(deltas, 0.99), "p999": _q(deltas, 0.999), "max": max(deltas) if deltas else None,
                  "values": sorted(deltas)}
    # G-모형(흐름별 — 경계 흐름 제외 · M16-3)
    M["g_model"] = {}
    for fl, gr in gm.items():
        if flows[fl]["bd"]:
            continue
        ok, lst = g_model(gr)
        M["g_model"][fl] = {"ok": ok if err_ms is not None else None, "n": len(gr), "allowed": sum(1 for x in lst if x.get("kind") == "허용"),
                            "bad": [x for x in lst if x.get("kind") != "허용"][:50]}
    M["boundary"] = {}   # 경계 흐름별(리뷰 L2-10 — ① nd · bcd · ④ ed)
    for fl_, rows_ in bd_rows.items():
        bok, bcnt = boundary_ok(rows_)
        M["boundary"][fl_] = {"ok": bok, **bcnt, "rows": rows_}
    er = early_reject(er_rows, k["theta"]) if err_ms is not None else None
    M["early_reject"] = er
    if er:
        F["early_reject"] = er
    # 다른 사용자 비용(설계-9.6 · 리뷰 L1-11): 흐름별(경계 흐름 제외) — 재선점 끝내 못 얻은 비율 · 좌석이 풀리기까지(201 수신 − 원래 만료 e) · 좌석 점유 시간
    def rh_stats(xs):
        rel = [(x["t"] - by_sid[x["sid"]]["h"]["exp"]) / 1000 for x in xs if x.get("got") and x.get("sid") in by_sid
               and num((by_sid[x["sid"]].get("h") or {}).get("exp")) and num(x.get("t"))]
        got = sum(1 for x in xs if x.get("got"))
        return {"n": len(xs), "got": got, "never_ratio": (1 - got / len(xs)) if xs else None, "release_p50_s": _q(rel, 0.5), "release_p99_s": _q(rel, 0.99)}
    rh_fl = {}
    for x in rh:
        if isinstance(x.get("sid"), int):
            fl_ = cyc[x["sid"] % len(cyc)]
            if not flows[fl_]["bd"]:
                rh_fl.setdefault(fl_, []).append(x)
    M["rehold"] = rh_stats([x for v in rh_fl.values() for x in v])
    M["rehold_by_flow"] = {fl_: rh_stats(v) for fl_, v in rh_fl.items()}
    allocc = [x for v in occ.values() for x in v]
    M["occupancy"] = {"mean_s": statistics.mean(allocc) if allocc else None, "p50_s": _q(allocc, 0.5), "p99_s": _q(allocc, 0.99)}
    M["occupancy_by_flow"] = {fl_: {"mean_s": statistics.mean(v), "p99_s": _q(v, 0.99)} for fl_, v in occ.items()}
    M["k6_non2xx_ready"] = True


def cost_rep(d, inp, plan, reasons, M, F, k6c, k6_non2xx):
    try:
        s16, r16 = read_s16(d / "k6-requests.csv.gz")
    except (OSError, EOFError, ValueError, StopIteration, IndexError, csv.Error) as e:
        inp.miss("csv", f"CSV s16 읽기 실패 {type(e).__name__}")
        return
    okc, badc = flow_count_ok(plan["planned"], s16)
    M["flows"] = {fl: {"planned": plan["planned"].get(fl, 0), "out": s16.get(fl, {})} for fl in sorted(set(plan["planned"]) | set(s16))}
    if not okc:
        reasons.add("flow-count-mismatch")
        M["flow_count_detail"] = badc
    s0p = s0c = 0
    for fl, outs in s16.items():
        b = (plan["flows"].get(fl) or {}).get("b") or plan["mode"] == O.MODE_EXT
        for out, n in outs.items():
            parts = out.split(":", 2)
            if parts[0] == "hold":
                reasons.add("hold-fail")   # 선점 비201 · 본문 결함 · 5xx · status 0 = paid와 같은 하네스 무효(리뷰 L2-2)
            elif parts[0] == "pay":
                if parts[1] == "0":
                    s0p += n
                elif parts[1] in ("409", "404", "403", "200"):
                    k6c[parts[1]] = k6c.get(parts[1], 0) + n
            elif parts[0] in ("cap", "dl", "conf") and b:
                k6c["200"] = k6c.get("200", 0) + n
            if parts[0] == "conf":
                if parts[1] == "0":
                    s0c += n
                else:
                    key = confirm_key(parts[1], parts[2] if len(parts) > 2 else None)
                    if key:
                        k6_non2xx[key] = k6_non2xx.get(key, 0) + n
    # 결정표와 같은 갈래(리뷰 L1-10): out 계수 → classify 입력(status 0은 CSV code0 · 시간 초과가 따로 잡는다) → 방식 실패 · 정합 실패 자료
    cats, n200 = {}, 0
    for fl, outs in s16.items():
        for out, n in outs.items():
            parts = out.split(":", 2)
            st = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else None
            code = parts[2] if len(parts) > 2 else None
            H = {"st": 201, "ok_body": True}
            if parts[0] == "pay" and st:
                rec = {"hold": H, "pay": {"st": st, "code": code, "ok_body": code != "body"}}
            elif parts[0] in ("cap", "dl"):
                rec = {"hold": H, "pay_fail": parts[0]}
            elif parts[0] == "conf" and st:
                rec = {"hold": H, "confirm": {"st": st, "code": code}, "reserved": st == 200}
                n200 += n if st == 200 else 0
            else:
                continue
            c_ = O.classify(rec)
            cats.setdefault(fl, {})
            cats[fl][c_] = cats[fl].get(c_, 0) + n
    M["categories"] = cats
    mfail = {c_: n for v in cats.values() for c_, n in v.items() if c_.startswith("mode_fail:")}
    if mfail:
        F["mode_fail"] = mfail
    rv = load(d / "reservations.json")   # record-level 대신 수 대조: 확정 200 합 = 끝 상태 CONFIRMED 행 수
    if not (isinstance(rv, dict) and isinstance(rv.get("rows"), list)):
        inp.miss("reservations", "reservations.json 없음·깨짐")
    else:
        nres = sum(1 for r in rv["rows"] if r.get("s") == "CONFIRMED")
        M["confirmed"] = {"k6_200": n200, "reservations": nres}
        if nres != n200:
            F["integrity"] = {"conf200-vs-reserved": f"k6 확정 200 {n200} · CONFIRMED {nres}"}
    M["_s0p"], M["_s0c"] = s0p, s0c
    M["rehold"] = r16
    M["k6_non2xx_ready"] = True


def _write(d, s):
    try:
        (Path(d) / "rep-summary.json").write_text(json.dumps(s, ensure_ascii=False, indent=1, default=str))
    except OSError:
        pass
    return s


# ==== 캠페인 ====================================================================================================================
def _get(m, dotted):
    for p in dotted.split("."):
        m = m.get(p) if isinstance(m, dict) else None
    return m if num(m) else None


def collect(root):
    reps = {}
    for st in Path(root).glob("*/*/rep[0-9]/status"):
        d = st.parent
        if st.read_text().strip() != "ok":
            continue
        cell = d.parent.parent.name
        if cell == "race":
            s = load(d / "rep-summary.json")
            if not s or s.get("status") != "ok":
                continue
            inv = {v: k for k, v in plan16.RACE_MODE.items()}
            reps.setdefault(("race", inv.get(d.parent.name, d.parent.name)), []).append(s)
            continue
        s = load(d / "rep-summary.json") or rep_summary(d)
        if s.get("status") != "ok":
            continue
        reps.setdefault((s["metrics"]["cell"], s["metrics"]["config"]), []).append(s)
    return reps


def _consistent(reps):
    """유효 3회 · 실패 자료(G-정합 · 방식 실패) 없음 → True · 있음 → False · 유효 < 3 → None."""
    if len(reps) < plan16.REPS:
        return None
    if any(s.get("fail") for s in reps):
        return False
    return None if any(s.get("undetermined") for s in reps) else True   # 재선점 5xx · 시간 초과(리뷰 L2-2) → 판정 불가


def _race_consistent(reps):
    """race(ADR-015 회차 요약 — 리뷰 L1-4 · 설계-8.4): 유효 < 3 → None · 회차마다 양성 대조(contention_evidence ≥ 1)가 아니면 None(경합을 못 만들었다 — 판정 불가) ·
    ADR-015 'A(current)도 같은 문턱' 대상 항목(선점 · 재선점 5xx · 시간 초과 · 방식 정지 · 잘못된 거부)은 current 자리가 없어 원인을 가를 수 없다 → None ·
    그 밖 실패 항목(정합 · API 이중 결과 · 확정 5xx · 계약 밖 …) → False."""
    if len(reps) < plan16.REPS:
        return None
    if not all(num(s["metrics"].get("contention_evidence")) and s["metrics"]["contention_evidence"] >= 1 for s in reps):
        return None
    items = [i for s in reps for i in S15.fail_items(s.get("fail"))]
    if any(not S15.a_rule(i) for i in items):
        return False
    return None if items else True


def model_cap30(deltas, err):
    """결정 문서 병기: CAP 30분 모형 결과(설계-9.6 · 명세 §1-3 순서 끝) — 측정 전 고정 값 · 관측 δ̂ · err. 둘 중 하나라도 없으면 계산하지 않는다(None — 0 채움 금지 · 리뷰 L1-12)."""
    if not deltas or err is None:
        return None
    k_c, k_e = plan16.knobs_of("cond"), plan16.knobs_of("ext")
    import model16
    pols = {"cond": model16.pol(O.MODE_COND, False, False, theta=k_c["theta"]),
            "cond+c": model16.pol(O.MODE_COND, False, True, theta=k_c["theta"], margin=k_c["margin"]),
            "cond+b": model16.pol(O.MODE_COND, True, False, theta=k_c["theta"]),
            "cond+b+c": model16.pol(O.MODE_COND, True, True, theta=k_c["theta"], margin=k_c["margin"]),
            "ext": model16.pol(O.MODE_EXT, True, False, theta=k_e["theta"], window=k_e["window"], margin=k_e["margin"]),
            "ext+c": model16.pol(O.MODE_EXT, True, True, theta=k_e["theta"], window=k_e["window"], margin=k_e["margin"])}
    out = {}
    for cap in (600.0, 1800.0):
        dist = {**model16.BASE, "cap": cap}
        out[f"cap{cap / 60:g}"] = {n: {kk: round(v, 5) for kk, v in model16.simulate(p, dist, n=100_000, seed=1, deltas=deltas, err=err).items()
                                       if kk in O.CATEGORIES + ("life_p99", "life_max")} for n, p in pols.items()}
    return out


def campaign(root, write=True):
    reps = collect(root)
    nvalid = {f"{c}/{g}": len(reps.get((c, g), [])) for c in plan16.CELLS for g in plan16.CONFIGS[c]}
    cands, detail = {}, {}
    deltas, errs = [], []
    for s in reps.get(("paid", "cond"), []) + reps.get(("paid", "ext"), []):
        deltas += (s["metrics"].get("delta") or {}).get("values") or []
        if num(s["metrics"].get("clock_err_ms")):
            errs.append(s["metrics"]["clock_err_ms"] / 1000)
    for name, (pcfg, fl) in plan16.CANDIDATE_FLOW.items():
        pr = reps.get(("paid", pcfg), [])
        cr = reps.get(("cost", plan16.CANDIDATE_COST[name]), [])
        rr = reps.get(("race", pcfg), [])
        gc_parts = [_consistent(pr), _consistent(cr), _race_consistent(rr)]
        g_cons = False if False in gc_parts else (None if None in gc_parts else True)
        if len(pr) < plan16.REPS:
            g_mod = g_tgt = bnd = None
            tot = None
        else:
            gms = [((s["metrics"].get("g_model") or {}).get(fl) or {}).get("ok") for s in pr]
            g_mod = False if False in gms else (None if None in gms else True)
            tot = dict.fromkeys(O.CATEGORIES, 0)
            tot["n"] = 0
            for s in pr:
                cl = ((s["metrics"].get("flows") or {}).get(fl) or {}).get("classified") or {}
                for c_, n_ in cl.items():
                    tot["n"] += n_
                    if c_ in O.CATEGORIES:
                        tot[c_] += n_
            g_tgt = g_target(tot[O.WRONGED], tot["n"], tot[O.PRE_REJECT], tot[O.PAY_FAIL])
            bfl = plan16.BOUNDARY_FLOW.get(name)
            brows = [tuple(x) for s in pr for x in ((s["metrics"].get("boundary") or {}).get(bfl) or {}).get("rows") or []] if bfl else []
            bnd = boundary_ok(brows)[0] if bfl else None
        # G-비용(리뷰 L1-3): ① 대비 ≫(ADR-014 much_higher). 범위를 못 만든 지표(유효 < 3 · 값 결측)는 '판정 불가:<지표>'로 남긴다(조용히 '보고 없음'이 되지 않게 —
        #   decide_campaign이 그 후보를 판정 불가로 다룬다). cost ① 기준값이 없는 지표(COST_NO_BASE)는 비교에서 빼고 RESULT.md에 적는다.
        cost_report = []
        def cmp(tag, xv, yv):
            if rng(xv) is None or rng(yv) is None:
                cost_report.append(f"판정 불가:{tag}")
            elif much_higher(xv, yv):
                cost_report.append(tag)
        base_c, x_c = reps.get(("cost", "cond"), []), cr
        if plan16.CANDIDATE_COST[name] != "cond":
            for mtr in COST_METRICS:
                if mtr not in COST_NO_BASE:
                    cmp(f"cost/{mtr}", [_get(s["metrics"], mtr) for s in x_c], [_get(s["metrics"], mtr) for s in base_c])
        if name in PAID_COST_PAIR:   # 다른 사용자 비용 — 같은 ⓒ 설정 흐름끼리(경계 흐름 제외)
            xf, yf = PAID_COST_PAIR[name]
            for mtr in PAID_COST_METRICS:
                cmp(f"paid/{mtr}[{xf}:{yf}]", [_get(s["metrics"], f"{mtr.split('.')[0]}_by_flow.{xf}.{mtr.split('.')[1]}") for s in pr],
                    [_get(s["metrics"], f"{mtr.split('.')[0]}_by_flow.{yf}.{mtr.split('.')[1]}") for s in reps.get(("paid", "cond"), [])])
        cands[name] = {"g_consistency": g_cons, "g_model": g_mod, "g_target": g_tgt, "cost_report": cost_report}
        if name.endswith("+c"):
            cands[name]["boundary_ok"] = bnd
        detail[name] = {"paid": pcfg, "flow": fl, "totals": tot, "wronged_upper": cp_upper(tot[O.WRONGED], tot["n"]) if tot else None,
                        "loss_rate": (tot[O.PRE_REJECT] + tot[O.PAY_FAIL]) / tot["n"] if tot and tot["n"] else None,
                        "consistency_parts": {"paid": gc_parts[0], "cost": gc_parts[1], "race": gc_parts[2]},
                        "fails": [f"{s['metrics'].get('cell', 'race')}/rep{s['metrics'].get('rep')}: {sorted(s.get('fail') or {})}" for s in pr + cr + rr if s.get("fail")]}
    dec = decide_campaign(cands)
    res = {"fixed": plan16.FIXED, "values": plan16.values(), "nvalid": nvalid, "candidates": cands, "detail": detail, "decision": dec,
           "model": model_cap30(deltas, max(errs) if errs else None),
           "condition": "ⓒ 후보의 억울 0은 결제 쪽이 마감(payBy)을 강제할 때만 성립한다(설계-9.3 · M16-3) — 서버 증거가 아니다"}
    if write:
        Path(root, "result.json").write_text(json.dumps(res, ensure_ascii=False, indent=1, default=str))
        Path(root, "RESULT.md").write_text(render_md(res))
    return res


def render_md(res):
    L = ["# ADR-016 캠페인 결과", "", f"- 측정 전 고정: {'예' if res['fixed'] else '아니오(자리표시 값 — 판정에 쓰지 않는다)'}",
         f"- 결론: **{res['decision']['status']}** · 고른 후보 {res['decision']['pick'] or '없음'}"
         + (" — 판정 불가 → 사용자 보고(통과 후보 없음)" if res['decision']['status'] == "none" else "")
         + (" — 판정 불가 → 사용자 보고" if res['decision']['status'] == "undetermined" else ""), f"- 조건: {res['condition']}", "",
         "| 후보 | 정합 | 모형 | 목표 | 경계 | 억울 상한 | 거부+실패 | G-비용 보고 |", "|---|---|---|---|---|---|---|---|"]
    for n in ORDER:
        c, dt = res["candidates"].get(n, {}), res["detail"].get(n, {})
        f = lambda x: "—" if x is None else (f"{x:.4%}" if isinstance(x, float) else str(x))   # noqa: E731
        L.append(f"| {n} | {c.get('g_consistency')} | {c.get('g_model')} | {c.get('g_target')} | {c.get('boundary_ok', '—')} | {f(dt.get('wronged_upper'))} | "
                 f"{f(dt.get('loss_rate'))} | {', '.join(c.get('cost_report') or []) or '—'} |")
    L += ["", "## 판정 기록", ""] + [f"- {x}" for x in res["decision"]["report"]]
    L += ["", "## G-비용 메모", "", *[f"- {k}: {v}" for k, v in COST_NO_BASE.items()],
          f"- 다른 사용자 비용 짝(경계 흐름 제외): {', '.join(f'{n} = paid {x} 대 ① {y}' for n, (x, y) in PAID_COST_PAIR.items())}",
          "- '판정 불가:<지표>' = 유효 3회 범위를 못 만든 지표 — 그 후보는 판정 불가로 다룬다",
          "- G-정합은 회차 단위다 — paid 회차의 실패 자료는 같은 회차의 모든 흐름 후보(예: cond+c · cond+b)를 함께 False로 만든다(같은 서버 · 의도 · OQ-2)",
          "", "## N16-1 근거(격리 탐침 + 세 겹 보조)", "", "- 주: 격리 탐침(probe16 — payment-start만 흘린 구간의 쓰기 증분 0 · 양성 대조 seat_hold 삽입 = P)", "- (a) ① 회차 seat_hold n_tup_upd 증분 0 · pgss UPDATE seat_hold 0", "- (b) 허용 목록(plan16.ALLOWED_WRITES) 밖 쓰기 문장 0",
          "- (c) payment-start 5xx 0 — ① 확인은 readOnly 트랜잭션이라 쓰기 시도는 오류(5xx)로 드러난다 · pgss만으로는 문장의 경로(payment-start)를 가를 수 없다"]
    L += ["", "## 모형(CAP 10 · 30분 — 관측 δ̂ · 설명용)", ""]
    if res["model"] is None:
        L += ["δ̂ 결측 — 계산 안 함(관측 δ̂ · 시계 오차가 없다 · 0으로 채우지 않는다)"]
    else:
        L += ["| 정책 | CAP | 억울 | 결제 전 거부 | 결제 실패 | 홀드 수명 p99 |", "|---|---|---|---|---|---|"]
    for cap, rows in (res["model"] or {}).items():
        for n, r in rows.items():
            L.append(f"| {n} | {cap} | {r[O.WRONGED]:.4%} | {r[O.PRE_REJECT]:.4%} | {r[O.PAY_FAIL]:.4%} | {r['life_p99']:.0f}초 |")
    L += ["", f"유효 회차: {res['nvalid']}", ""]
    return "\n".join(L)


def main(argv):
    a = argv[1:]
    if a[:1] == ["--rep"] and len(a) == 2:
        try:
            s = rep_summary(a[1])
        except Exception as e:   # noqa: BLE001 — 요약기 결함(리뷰 L1-1): G-F 자료를 조용히 하네스 무효로 바꾸지 않게 추적을 남기고 사용자 보고 표지(campaign16이 멈춘다)
            import traceback
            s = _write(a[1], {"status": "invalid:summarize-failed", "reasons": ["summarize-failed"], "report_to_user": True,
                              "error": f"{type(e).__name__}: {e}", "traceback": traceback.format_exc()})
        print(json.dumps({"reasons": s.get("reasons"), "missing": s.get("missing")}, ensure_ascii=False))
        print(s["status"])
        return 0
    if len(a) == 1 and not a[0].startswith("--"):
        r = campaign(a[0])
        print(f"결론: {r['decision']['status']} · {r['decision']['pick']}")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
