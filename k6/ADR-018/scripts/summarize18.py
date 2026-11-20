#!/usr/bin/env python3
"""ADR-018 요약기 — 회차 무효 판정 · 회차 요약 · 캠페인 표(계약 k6/ADR-018/INTERFACE.md §7 · §8 · §9.2). 세션 분류 = outcome18(결정표 하나).

    summarize18.py --rep <회차 폴더>        → <폴더>/summary18.json · 표준 출력 마지막 줄 = ok | invalid:<사유,…> · exit 0(판정함) · 2(plan 없음 · 판정 불가)
    summarize18.py --campaign <캠페인 폴더> → <폴더>/campaign18.json · 표준 출력 마지막 줄 = pass:<방식별 JSON> · exit 0

기본값 결정(묶음 API를 켤지 · split과 one-shot 중 무엇)은 사람이 한다 — 요약기는 정합 판정과 근거 표만 낸다(하네스 설계 §6).
백분위(p50 · p99)는 ADR-014 · 017과 같은 규칙 — 정렬 뒤 xs[min(n − 1, ⌊n × q⌋)](보간 없음)."""
import csv
import gzip
import json
import math
import re
from datetime import datetime
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent.parent / "ADR-017" / "scripts"))
import outcome18 as O  # noqa: E402
import plan18  # noqa: E402
from summarize17 import PAY_ZERO, S14, V_KEYS, load, num, pct  # noqa: E402  — ADR-017 요약기 조각(값 규칙 같음)

ATTR_TOL = 1e-6
ATTR_KEYS = ("T", "P", "G", "D", "U", "X")
GROUP_ZERO = ("group_partial_holds", "group_expiry_split", "group_partial_reservations")   # 지표 계약 · ADR-018 설계-7(기대 0)
PAY_ZERO_END = tuple(PAY_ZERO)   # pay_multi_reservation_intents(V16 정의) 포함 7개 — 배수 뒤
CONS_ZERO_K6 = ("pay_multi_reservation_intents",) + GROUP_ZERO   # k6 직후 스냅숏에서도 0이어야 하는 것(홀드가 아직 남아 있는 시점의 부분 선점)
ACT_SCALARS = (("process_start_s",), ("compensation", "unresolved"), ("compensation", "record_failed"), ("compensation", "sweep_failed"))
ACT_DICTS = (("group_request",), ("group_rejected",), ("hold_rejected",), ("deadlock",), ("expiry_tick",),
             ("compensation", "done"), ("compensation", "retry"), ("compensation", "dead"))
ENV_ANY = frozenset({"pc-link-down", "pc-mem-low", "k6-conn-error"})   # 섞여 있어도 환경(명세 Q11 — 연결 오류 회차는 재측정)
ENV_SOLE = frozenset({"env-send-lag"})                                  # 이것만 있을 때 환경
STAGES = {"hold": ("hold", "hold_group"), "pay_start": ("pay_start", "pay_start_group"), "confirm": ("confirm", "confirm_group")}
CSV_NAMES = ("hold", "hold_group", "release", "release_group", "pay_start", "pay_start_group", "pg_pay", "confirm", "confirm_group")
DEADLOCK_PATH = {"hold_group": "group_hold", "pay_start_group": "group_pay_start", "confirm_group": "group_confirm", "release_group": "release",
                 "release": "release"}
Z95 = 1.959963984540054


DUMP_UNSTABLE = frozenset({"dump-race", "dump-unstable", "ledger-recheck-failed"})
# 덤프와 무관하게 관측한 G1 항목(응답 · CSV · 홀드 표본 · k6 직후 정합 스냅숏 · 지표 — 리뷰 H3-R1)
DUMP_INDEPENDENT = frozenset({"integrity_response", "unexpected", "main_5xx", "csv_5xx_without_row", "sample_partial", "sample_split", "group_cause_bad",
                              "burst_multi_success", "edge_5xx_non40p01", "edge_5xx_unlinked", "deadlock", "consistency_k6", "consistency_k6_v_sum"})
RESPONSE_INTEGRITY = frozenset({"integrity:hold-body", "integrity:ps-body", "integrity:confirm-body", "integrity:hold-limit"})
UNCHECKABLE = frozenset({"record_group_extra", "record_pay_ok_not_in_ledger", "record_ledger_paid_without_k6_pay"})   # 확인 불가(None)일 수 있는 항목


def g1_violation(items):
    """G1 항목 중 실제로 0이 아닌 숫자(위반 관측)가 있는가 — 결측(None)만으로 거짓인 경우(덤프 없음 등)는 위반으로 치지 않는다(H2-6)."""
    for v in items.values():
        if isinstance(v, dict):
            if any(num(x) and x != 0 for x in v.values()):
                return True
        elif num(v) and v != 0:
            return True
    return False


def wilson(k, n, z=Z95):
    """Wilson 95% 구간 [lo, hi] · n = 0이면 None."""
    if not n:
        return None
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return [max(0.0, c - h), min(1.0, c + h)]


def dist(xs):
    return {"n": len(xs), "p50": pct(xs, 0.5), "p99": pct(xs, 0.99), "max": max(xs) if xs else None}


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


def dict_delta(before, end, path):
    """사전 지표의 키별 증분(end − before) · 0인 키는 뺀다. 결측이면 None."""
    a, b = _get(before, path), _get(end, path)
    if not isinstance(a, dict) or not isinstance(b, dict):
        return None
    out = {}
    for k in set(a) | set(b):
        v = (b.get(k) or 0) - (a.get(k) or 0)
        if v:
            out[k] = v
    return out


def k6_csv(path):
    """k6 CSV → {"ms": {name: [2xx 지연 ms]}, "dropped": 합} · 파일 없음 · 깨짐 = None(무음으로 건너뛰지 않는다 — ADR-017 k6_csv와 같은 규칙)."""
    p = Path(path)
    if not p.exists():
        return None
    vals = {n: [] for n in CSV_NAMES}
    x5 = []   # 앱 요청 5xx(행이 남지 않은 반복 포함 — 리뷰 H3-2): (name, 끝 시각 초(PC · CSV timestamp는 초 단위), 지연 ms, status)
    dropped = 0
    try:
        with gzip.open(p, "rt", newline="") as f:
            rd = csv.reader(f)
            head = next(rd)
            im, iv, iname, ist = head.index("metric_name"), head.index("metric_value"), head.index("name"), head.index("status")
            its = head.index("timestamp")
            for r in rd:
                if len(r) != len(head):
                    return None
                if r[im] == "http_req_duration" and r[ist][:1] == "2" and r[iname] in vals:
                    vals[r[iname]].append((float(r[iv]), float(r[its])))   # (지연 ms, 끝 시각 PC 초 — CSV timestamp는 초 단위)
                elif r[im] == "http_req_duration" and r[ist][:1] == "5" and r[iname] in O.APP_OPS:
                    x5.append((r[iname], float(r[its]), float(r[iv]), int(r[ist])))
                elif r[im] == "dropped_iterations":
                    dropped += int(float(r[iv]))
    except (OSError, ValueError, StopIteration, EOFError, csv.Error):
        return None
    return {"ms": vals, "dropped": dropped, "x5": x5}


def latency_of(ms_ts, k6t0_s, warm_s):
    """단계 지연 표본(ms, 끝 시각 초) → p50 · p99 · 뺀 수. 송신 시각(끝 시각 − 지연)이 k6 시작 + warm_s 안이면 판정 표본에서 뺀다(첫 5초 차가운 지연 —
    CSV 시각이 초 단위라 경계에서 1초 안팎 어긋날 수 있다). k6 시작을 모르면 빼지 않는다."""
    keep, excl = [], 0
    for ms, t in ms_ts:
        if num(k6t0_s) and num(warm_s) and t * 1000 - ms < (k6t0_s + warm_s) * 1000:
            excl += 1
        else:
            keep.append(ms)
    return {"n": len(keep), "p50_ms": pct(keep, 0.5), "p99_ms": pct(keep, 0.99), "warmup_excluded": excl}


def env_kind(status):
    """회차 status → env | harness | valid(campaign18 kind_of와 같은 규칙): ENV_ANY가 하나라도 있으면 env · 사유가 전부 ENV_SOLE이면 env · 그 밖 무효 · status 없음은 harness."""
    if status == "ok":
        return "valid"
    if not status or not status.startswith("invalid:"):
        return "harness"
    rs = set(status.split(":", 1)[1].split(","))
    if rs & ENV_ANY:
        return "env"
    return "env" if rs and rs <= ENV_SOLE else "harness"


def _hold_window(row):
    h = [o for o in row.get("hold") or [] if isinstance(o, dict)]
    if not h or not num(h[0].get("ts")) or not num(h[-1].get("tr")):
        return None
    return h[0]["ts"], h[-1]["tr"]


def _seats_of_req(o):
    if o.get("op") == "hold":
        return [o.get("seat")]
    return list(o.get("seats") or [])


def contention(rows):
    """§8.2 경합 쌍: 좌석 쌍이 겹치고(|s_i − s_j| ≤ 1) 선점 창 [첫 선점 ts, 마지막 선점 tr]이 겹치는 세션 쌍. 둘 다 2석을 못 잡은 쌍 = both_fail."""
    by_s = {}
    for r in rows:
        seats = r.get("seats")
        w = _hold_window(r)
        if isinstance(seats, list) and seats and isinstance(seats[0], int) and w:
            by_s.setdefault(seats[0], []).append((r.get("sid"), w, r.get("got") == "both"))
    pairs = both_fail = 0
    for s, lst in by_s.items():
        for d in (0, 1):
            other = by_s.get(s + d, [])
            for i, (sa, wa, ga) in enumerate(lst):
                for sb, wb, gb in (other[i + 1:] if d == 0 else other):
                    if wa[0] <= wb[1] and wb[0] <= wa[1]:
                        pairs += 1
                        both_fail += (not ga) and (not gb)
    return {"pairs": pairs, "both_fail": both_fail, "ratio": both_fail / pairs if pairs else None}


def first_only(rows, method):
    """§8.2 B0 · B1 '첫 석만 잡음': 묶인 시간(B0 = 선점 응답 expiresAt − 응답 수신 · B1 = 해제 응답 수신 − 선점 응답 수신) · 그 좌석에서 막힌 다른 세션 수
    (그 창 안에 그 좌석을 포함한 선점 요청을 보내 실패한 다른 세션 — 세션당 한 번)."""
    reqs = {}
    for r in rows:
        for o in r.get("hold") or []:
            if isinstance(o, dict) and num(o.get("ts")):
                for x in _seats_of_req(o):
                    reqs.setdefault(x, []).append((o["ts"], o.get("ok") is True, r.get("sid")))
    held, blocked, n = [], 0, 0
    for r in rows:
        if r.get("got") != "first":
            continue
        h0 = r["hold"][0]
        if method == "B1":
            rl = (r.get("rel") or [None])[0]
            hi = rl.get("tr") if isinstance(rl, dict) else None
        else:
            hi = h0.get("exp")
        lo = h0.get("tr")
        n += 1
        if not (num(lo) and num(hi)):
            continue
        held.append((hi - lo) / 1000)
        who = {sid for ts, ok, sid in reqs.get(h0.get("seat"), []) if lo <= ts <= hi and not ok and sid != r.get("sid")}
        blocked += len(who)
    return {"n": n, "held_s": dist(held), "blocked_sessions": blocked}


_LOG_HEAD = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?)(Z|[+-]\d\d:?\d\d)\s+(ERROR|WARN|INFO|DEBUG|TRACE)\s")


APP_LOG_START = "Starting SeatReservationApplication"   # Spring Boot 기동 머리 줄(run18 logs18이 회차 시작부터 docker logs를 모으므로 늘 있어야 한다)


def app_errors(path):
    """앱 로그(app.log.gz — docker logs) → 처리되지 않은 요청 예외 ERROR 블록 [{t_ms, deadlock}] · 파일 없음 = None.
    블록 = 시각 머리 줄 + 다음 머리 줄 전까지의 줄(스택 · root cause). 요청 예외 = 머리 줄에 'Servlet.service() for servlet'(Tomcat StandardWrapperValve).
    deadlock = 블록에 'deadlock detected'(PostgreSQL 40P01 문구) 또는 '40P01'. 시각 = 머리 줄 ISO(앱 컨테이너 시계 — 서버 시계와 같은 호스트)."""
    p = Path(path)
    if not p.exists():
        return None
    try:
        with gzip.open(p, "rt", errors="replace") as f:
            lines = f.read().splitlines()
    except (OSError, EOFError):
        return None
    if not any(APP_LOG_START in x for x in lines):
        return None   # 0바이트 · 기동 머리 줄 없음 = 로그가 회차 전체를 담지 않았다(리뷰 H3-3 — app-log-missing)
    out, cur = [], None
    for line in lines:
        m = _LOG_HEAD.match(line)
        if m:
            if cur:
                out.append(cur)
            cur = None
            if m.group(3) == "ERROR" and "Servlet.service() for servlet" in line:
                t = datetime.fromisoformat(m.group(1) + ("+00:00" if m.group(2) == "Z" else m.group(2))).timestamp() * 1000
                cur = {"t_ms": t, "text": [line]}
        elif cur is not None:
            cur["text"].append(line)
    if cur:
        out.append(cur)
    return [{"t_ms": c["t_ms"], "deadlock": any("deadlock detected" in x or "40P01" in x for x in c["text"])} for c in sorted(out, key=lambda c: c["t_ms"])]


def hold_samples(path, k6t0_ms, k6end_ms=None):
    """holds-sample.jsonl(§6.12) → 표본 요약 · 파일 없음 = None. live = 홀드가 남은 표본.
    stock_out_s = 노리는 범위 안에서 더 잡을 수 있는 쌍(free_pairs — 같은 줄 이웃 두 석 모두 AVAILABLE)이 0인 첫 표본 시각 − k6 시작(초 · 메인 판단 2026-11-20).
    끝까지 0이 아니면 null이고 last_free_pairs(마지막 표본 값)를 함께 낸다."""
    p = Path(path)
    if not p.exists():
        return None
    ok, err = [], 0
    for line in p.read_text(errors="replace").splitlines():
        try:
            o = json.loads(line)
        except ValueError:
            err += 1
            continue
        if isinstance(o, dict) and all(num(o.get(k)) for k in ("t_ms", "holds", "partial", "split", "available", "free_pairs", "q_ms")):
            ok.append(o)
        else:
            err += 1
    live = [o for o in ok if o["holds"] > 0]
    ok = sorted(ok, key=lambda x: x["t_ms"])
    # k6 창 안 성공 표본 사이 최대 공백(창 양끝 포함 · 초 — 리뷰 H2-1)
    gap = None
    if num(k6t0_ms) and num(k6end_ms):
        tt = [k6t0_ms] + [o["t_ms"] for o in ok if k6t0_ms <= o["t_ms"] <= k6end_ms] + [k6end_ms]
        gap = max(b - a for a, b in zip(tt, tt[1:])) / 1000
    out0 = next((o["t_ms"] for o in ok if o["free_pairs"] == 0), None)
    total = len(ok) + err
    return {"ok_n": len(ok), "err_n": err, "fail_ratio": err / total if total else None, "max_gap_s": gap, "live_n": len(live), "partial_max": max((o["partial"] for o in ok), default=None),
            "split_max": max((o["split"] for o in ok), default=None), "holds_max": max((o["holds"] for o in ok), default=None),
            "group_holds_max": max((o.get("group_holds") or 0 for o in ok), default=None), "q_ms": dist([o["q_ms"] for o in ok]),
            "first_t_ms": ok[0]["t_ms"] if ok else None, "last_t_ms": ok[-1]["t_ms"] if ok else None,
            "stock_out_s": (out0 - k6t0_ms) / 1000 if num(out0) and num(k6t0_ms) else None,
            "last_free_pairs": ok[-1]["free_pairs"] if ok else None, "free_pairs_min": min((o["free_pairs"] for o in ok), default=None),
            "available_min": min((o["available"] for o in ok), default=None),
            "partial_at": [o["t_ms"] for o in ok if o["partial"] > 0][:20]}


def burst_stats(rows, plan, k6_group_rj):
    """동시 경합 셀(§8.2): 묶음별 2석 성공 수 · 모두 실패한 묶음 비율 · 원인 표지 분포 · 묶음 안 선점 송신 폭(max ts − min ts · ms).
    폭을 잴 수 없는 묶음(구성원 행 결측 · ts 결측)과 문턱을 넘는 묶음을 'over'로 센다. bad = over 비율 > 문턱 비율(값은 plan values)."""
    vals = plan.get("values") or {}
    lim, frac = vals.get("burst_spread_max_ms"), vals.get("burst_spread_bad_frac")
    n_total = plan.get("n_total") or 0
    want = plan18.bursts(plan.get("rep"), n_total)
    by_k = {}
    for r in rows:
        if isinstance(r.get("burst"), int):
            by_k.setdefault(r["burst"], []).append(r)
    hist, spreads, over, all_fail, multi, connected = {}, [], 0, 0, 0, 0
    cns = [r["hold"][0].get("cn") for r in rows if r.get("hold") and isinstance(r["hold"][0], dict) and num(r["hold"][0].get("cn"))]
    pre_cn = [r["pre"].get("cn") for r in rows if isinstance(r.get("pre"), dict) and num(r["pre"].get("cn"))]
    for k, (first, size) in enumerate(want):
        mem = by_k.get(k, [])
        ts = [r["hold"][0].get("ts") for r in mem if r.get("hold") and isinstance(r["hold"][0], dict)]
        cn = [r["hold"][0].get("cn") for r in mem if r.get("hold") and isinstance(r["hold"][0], dict)]
        conn_bad = any(not num(x) or x > 0 for x in cn)   # 선점이 새 연결을 맺었다(미리 맺은 연결을 못 씀 — 리뷰 H1-8)
        connected += conn_bad
        succ = sum(1 for r in mem if r.get("got") == "both")
        hist[str(succ)] = hist.get(str(succ), 0) + 1
        all_fail += succ == 0
        multi += succ > 1
        if len(mem) != size or len(ts) != size or not all(num(x) for x in ts):
            over += 1
            continue
        sp = max(ts) - min(ts)
        spreads.append(sp)
        over += (not num(lim)) or sp > lim or conn_bad
    nb = len(want)
    bad = (not num(frac)) or (nb > 0 and over / nb > frac)
    causes = {k.split(":", 1)[1] if ":" in k else k: v for k, v in (k6_group_rj or {}).items()}
    return {"bursts": nb, "success_hist": hist, "all_fail": all_fail, "all_fail_ratio": all_fail / nb if nb else None, "multi_success": multi,
            "causes": causes, "spread_ms": {"n": len(spreads), "p50": pct(spreads, 0.5), "p99": pct(spreads, 0.99), "max": max(spreads) if spreads else None},
            "over": over, "over_ratio": over / nb if nb else None, "conn_new_bursts": connected,
            "hold_connecting_ms": dist(cns), "preconnect_connecting_ms": dist(pre_cn), "limit_ms": lim, "bad_frac": frac, "bad": bad}


def rep_summary(d):
    """회차 폴더 → (summary dict, status 문자열). plan 없음 = (None, None)."""
    d = Path(d)
    plan = load(d / "plan.json")
    if not isinstance(plan, dict):
        return None, None
    method, cell, rep = plan.get("method"), plan.get("cell"), plan.get("rep")
    edge = cell == plan18.EDGE
    vals = plan.get("values") or {}
    reasons, harness = set(), []
    hr = d / "harness-reasons.txt"
    if hr.exists():
        for x in hr.read_text().splitlines():
            if x.strip() and x.strip() not in harness:
                harness.append(x.strip())
    else:
        reasons.add("harness-reasons-missing")

    # ---- 세션(계획 세션 수 = 분류 세션 수 · 시드 대조) ----
    rd = O.read_sessions(O.sessions_file(d))
    rows = []
    if rd is None:
        reasons.add("sessions-missing")
    else:
        rows, broken = rd
        if broken:
            reasons.add("sessions-broken")
    rows = [r for r in rows if isinstance(r, dict)]
    sids = [r.get("sid") for r in rows]
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
    H = (plan.get("seed") or {}).get("H")
    user_base = int(((plan.get("k6") or {}).get("env") or {}).get("USER_BASE", plan18.USER_BASE))
    cyc = plan.get("kind_cycle") or []
    for r in rows:
        sid = r.get("sid")
        if not isinstance(sid, int) or sid < 0 or not num(H):
            reasons.add("session-attrs-mismatch")
            continue
        a = plan18.session_attrs(rep, sid, params)
        n_plan = plan.get("n_total") or 0
        if any(not num(r.get(k)) or abs(r[k] - a[k]) > ATTR_TOL for k in ATTR_KEYS) \
                or (sid >= n_plan if cell == plan18.BURST else False) \
                or r.get("seats") != plan18.seats_for(cell, rep, n_plan, sid, a["U"], H) or not plan18.same_row(r.get("seats")) \
                or r.get("user") != user_base + sid or not cyc or r.get("kind") != cyc[sid % len(cyc)] \
                or (cell == plan18.BURST and r.get("burst") != plan18.burst_of(rep, n_plan, sid)[0]):
            reasons.add("session-attrs-mismatch")

    # ---- 입력 파일 ----
    led, db = load(d / "pg-ledger.json"), load(d / "db-dump.json")
    cons, cons_k6 = load(d / "consistency.json"), load(d / "consistency-k6.json")
    acts = {p: load(d / f"act18-{p}.json") for p in ("before", "after-k6", "end")}
    if not isinstance(led, dict) or not isinstance(led.get("payments"), list):
        reasons.add("ledger-missing")
        led = None
    if not isinstance(db, dict) or not all(isinstance(db.get(k), list) for k in ("payment_intent", "reservation", "confirm_rejection", "compensation_task",
                                                                                     "hold_group", "seat_hold")):
        reasons.add("db-dump-missing")
        db = None
    for c in (cons, cons_k6):
        if not isinstance(c, dict) or any(not num(c.get(k)) for k in V_KEYS + PAY_ZERO_END + GROUP_ZERO):
            reasons.add("consistency-missing")
    cons = cons if isinstance(cons, dict) else None
    cons_k6 = cons_k6 if isinstance(cons_k6, dict) else None
    if not all(act_ok(a) for a in acts.values()):
        reasons.add("act-missing")
    ps0, ps1 = _get(acts["before"], ("process_start_s",)), _get(acts["end"], ("process_start_s",))
    if num(ps0) and num(ps1) and ps0 != ps1:
        reasons.add("app-restarted")
    seed = load(d / "seed-check.json")
    if not isinstance(seed, dict):
        reasons.add("seed-check-missing")
    else:
        fly = [str(x) for x in seed.get("flyway") or []]
        if not (seed.get("schedules") == 1 and seed.get("seats") == plan["seed"]["seats"] and seed.get("available") == plan["seed"]["seats"]
                and seed.get("holds") == 0 and seed.get("reservations") == 0 and seed.get("quota_rows") == 0 and "15" in fly and "16" in fly):
            reasons.add("seed-mismatch")
    if not (d / "app-config.json").exists():
        reasons.add("app-config-missing")
    else:
        _, cc = plan18.config_check(method, cell, d / "app-config.json")
        for x in cc["reasons"]:
            reasons.add({"cond-config-mismatch": "config-mismatch", "cond-config-missing": "app-config-missing"}.get(x, x))
    if cons is not None and cons.get("pay_compensation_mode") != vals.get("compensation_mode"):
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
    hd = ks.get("adr018") if isinstance(ks, dict) else None
    if not isinstance(hd, dict) or str(hd.get("rep")) != str(rep) or hd.get("method") != method or hd.get("cell") != cell \
            or hd.get("plan_n") != plan.get("n_total") or hd.get("cycle") != cyc or hd.get("h") != H \
            or not num(hd.get("rate")) or hd.get("rate") != plan.get("rate") or not num(hd.get("d_arr_s")) or abs(hd["d_arr_s"] - (plan.get("d_arr_s") or 0)) > 1e-9:
        # 도착률 · 도착 구간도 계획과 대조한다(리뷰 H3-5 — k6가 다른 부하로 돌았으면 무효)
        reasons.add("k6-plan-mismatch")

    # ---- PG 리셋 · 배수 ----
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
        resp = fl[-1].get("response") if fl else None
        if last_reset < 0 or not fl or fl[-1].get("rc") != 0 or not isinstance(resp, dict) or resp.get("seed") != (pf or {}).get("seed"):
            reasons.add("inject-mismatch")
        elif resp.get("rules"):
            reasons.add("inject-leak")   # PG 주입 없음(명세 Q15) — 규칙이 남아 있으면 무효
    drain = load(d / "drain.json")
    drain_out = None
    if not isinstance(drain, dict) or drain.get("reason") not in ("empty", "cap"):
        reasons.add("drain-missing")
    else:
        polls = drain.get("polls") if isinstance(drain.get("polls"), list) else []
        if drain["reason"] == "cap" and not any(isinstance(x, dict) and "err" not in x and num(x.get("pending")) for x in polls):
            reasons.add("drain-poll-failed")
        drain_out = {"reason": drain["reason"], "len_s": (drain["end_ms"] - drain["start_ms"]) / 1000 if num(drain.get("end_ms")) and num(drain.get("start_ms")) else None}

    # ---- 분류 · uid 끝 상태 ----
    amount = vals.get("amount")
    ends = O.end_states(db, led, amount) if db is not None and led is not None else {}
    cats, by_kind, inv, integ, fail, unexp = [], {}, {}, {}, {}, {}
    for r in rows:
        c = O.classify(r, ends, plan) if (db is not None and led is not None) else "invalid:dump-missing"
        cats.append((r, c))
        k = str(r.get("kind"))
        by_kind.setdefault(k, {})
        by_kind[k][c] = by_kind[k].get(c, 0) + 1
        for pre, bucket in (("invalid:", inv), ("integrity:", integ), ("fail:", fail), ("unexpected:", unexp)):
            if c.startswith(pre):
                bucket[c] = bucket.get(c, 0) + 1
    if any(c.startswith("invalid:conn-") for c in inv):
        reasons.add("k6-conn-error")
    if any(not c.startswith("invalid:conn-") for c in inv):
        reasons.add("session-invalid")
    uids = {}
    for e in ends.values():
        uids[e["shape"]] = uids.get(e["shape"], 0) + 1

    # ---- 회차 격리(pg-not-reset) · record-level ----
    users = {r.get("user") for r in rows}
    sess_uids = {p.get("uid") for r in rows for p in r.get("ps") or [] if isinstance(p, dict) and p.get("uid")}
    stray = []
    if db is not None:
        stray += [f"intent:{i.get('uid')}" for i in db["payment_intent"] if i.get("user_id") not in users]
        stray += [f"group:{g.get('id')}" for g in db["hold_group"] if g.get("user_id") not in users]
        stray += [f"reservation:{x.get('id')}" for x in db["reservation"] if x.get("user_id") not in users]
        stray += [f"hold:{x.get('id')}" for x in db["seat_hold"] if x.get("user_id") not in users]
    if led is not None and db is not None:
        db_uids = {i.get("uid") for i in db["payment_intent"]}
        stray += [f"ledger:{p.get('uid')}" for p in led["payments"] if p.get("uid") not in db_uids and p.get("uid") not in sess_uids]
    if stray:
        reasons.add("pg-not-reset")
    pay_ok = [p for r in rows for p in r.get("pay") or [] if isinstance(p, dict) and p.get("ok") is True]
    pay_ok_uids = {p.get("uid") for p in pay_ok}
    # record-level 대조는 양쪽 산출물이 온전할 때만 수로 낸다(리뷰 H3-1 — 결측 산출물을 정합 위반으로 세지 않게): DB 덤프 · 원장이 없으면 None,
    #   세션 행이 결손됐거나(수 불일치 · 깨진 줄 · 중복) 연결 오류 응답이 있으면 'k6가 본 것'이 불완전하므로 k6 ↔ 원장 두 방향 대조는 None(확인 불가)
    dumps_ok = db is not None and led is not None
    rows_whole = not (reasons & {"sessions-missing", "sessions-broken", "sessions-count-mismatch", "session-dup"}) \
        and not any(isinstance(o, dict) and o.get("st") == 0 for r in rows for o in O.requests(r))
    rec_not_ledger = sum(1 for p in pay_ok if not (ends.get(p.get("uid")) or {}).get("captured")) if dumps_ok and rows_whole else None
    rec_no_intent = sum(1 for p in pay_ok if (ends.get(p.get("uid")) or {}).get("intent_state") is None) if dumps_ok else None
    rec_paid_no_k6 = sum(1 for u, e in ends.items() if e["captured"] and u not in pay_ok_uids) if dumps_ok and rows_whole else None
    k6_gids = {o.get("gid") for r in rows for o in r.get("hold") or [] if isinstance(o, dict) and o.get("op") == "hold_group" and o.get("ok") is True}
    unsettled_group = any(isinstance(o, dict) and o.get("op") == "hold_group" and (o.get("st") == 0 or (num(o.get("st")) and o["st"] >= 500))
                          for r in rows for o in r.get("hold") or [])
    db_gids = {g.get("id") for g in db["hold_group"]} if db is not None else set()
    rec_group_missing = len(k6_gids - db_gids) if db is not None else None
    # 묶음 선점 5xx · 연결 오류 · 행 결손이면 DB에만 있는 묶음이 결함인지 알 수 없다 — None(확인 불가 · 리뷰 H3-R4 — 0으로 두지 않는다)
    rec_group_extra = len(db_gids - k6_gids) if db is not None and rows_whole and not unsettled_group else None
    seat_conf = {}
    for x in (db["reservation"] if db is not None else []):
        if x.get("status") == "CONFIRMED":
            seat_conf[x.get("seat_id")] = seat_conf.get(x.get("seat_id"), 0) + 1
    double_sold = sum(1 for v in seat_conf.values() if v > 1) if db is not None else None
    record = {"k6_pay_ok": len(pay_ok), "ledger_paid": sum(1 for e in ends.values() if e["captured"]), "db_intents": len(db["payment_intent"]) if db else None,
              "pay_ok_not_in_ledger": rec_not_ledger, "pay_ok_no_intent": rec_no_intent, "ledger_paid_without_k6_pay": rec_paid_no_k6,
              "k6_groups": len(k6_gids), "db_groups": len(db_gids) if db is not None else None, "group_missing": rec_group_missing,
              "group_extra": rec_group_extra, "group_extra_unchecked": unsettled_group, "stray": stray[:20], "stray_n": len(stray)}
    tasks = db["compensation_task"] if db else []

    # ---- 지표 대조(§7 — 설계 §5): 원인 표지 헤더 합 ↔ seat.hold.rejected · seat.group.hold.rejected · 묶음 API 요청 수 ↔ seat.group.request ----
    reqs = [o for r in rows for o in O.requests(r)]
    k6_hold_rj, k6_group_rj, k6_req = {}, {}, {}
    for o in reqs:
        if o.get("op") == "hold" and o.get("st") == 409:
            k = o.get("rj") if o.get("rj") in ("nowait", "rule") else f"bad:{o.get('rj')}"
            k6_hold_rj[k] = k6_hold_rj.get(k, 0) + 1
        if o.get("op") == "hold_group" and o.get("st") == 409:
            rj = o.get("rj")
            k = f"{plan.get('metric_mode')}:{rj[len('group-'):]}" if isinstance(rj, str) and rj.startswith("group-") else f"bad:{rj}"
            k6_group_rj[k] = k6_group_rj.get(k, 0) + 1
        mo = O.METRIC_OP.get(o.get("op"))
        out_ = O.metric_outcome(o)
        if mo and out_:
            k6_req[f"{mo}:{out_}"] = k6_req.get(f"{mo}:{out_}", 0) + 1
    # 묶음 409 원인 표지의 계약(리뷰 H3-6 — 지표 대조와 따로): 허용값 nowait · short · rule 중 방식별 가능 원인(split = nowait · rule ·
    #   one_shot = short · rule)만 · 그 밖 · 헤더 없음 · 앱 지표의 계약 밖 키는 앱 계약 위반 = G1
    allowed = {"split": {"nowait", "rule"}, "one_shot": {"short", "rule"}}.get(plan.get("metric_mode"), set())
    cause_bad = sum(1 for o in reqs if o.get("op") == "hold_group" and o.get("st") == 409
                    and not (isinstance(o.get("rj"), str) and o["rj"].startswith("group-") and o["rj"][len("group-"):] in allowed))
    metric = None
    if all(act_ok(a) for a in (acts["before"], acts["end"])):
        dh = dict_delta(acts["before"], acts["end"], ("hold_rejected",))
        dg = dict_delta(acts["before"], acts["end"], ("group_rejected",))
        dr = dict_delta(acts["before"], acts["end"], ("group_request",))
        dd = dict_delta(acts["before"], acts["end"], ("deadlock",))
        metric = {"k6_hold_reject": k6_hold_rj, "app_hold_reject": dh, "k6_group_reject": k6_group_rj, "app_group_reject": dg,
                  "k6_group_request": k6_req, "app_group_request": dr, "deadlock": dd,
                  "expiry_tick": dict_delta(acts["before"], acts["end"], ("expiry_tick",))}
        cause_bad += sum(v for k, v in (dg or {}).items() if k.split(":", 1)[0] != plan.get("metric_mode") or k.split(":", 1)[-1] not in allowed)
        if dh != k6_hold_rj or dg != k6_group_rj:
            reasons.add("reject-header-mismatch")
        if dr != k6_req:
            reasons.add("group-request-mismatch")

    # ---- 5xx(본 셀 = G1 · 경계 셀 = 교착으로 설명돼야 함 — 설계 §4) ----
    x5 = {}
    for o in reqs:
        if o.get("op") in O.APP_OPS and num(o.get("st")) and o["st"] >= 500:
            x5[o["op"]] = x5.get(o["op"], 0) + 1
    # 행이 남지 않은 앱 5xx(반복 중단 — 리뷰 H3-2): CSV의 앱 요청 5xx 가운데 세션 행의 5xx와 짝지어지지 않는 것(이름마다 끝 시각 ±1초 + 허용 오차 · 이른 것부터)
    csvd = k6_csv(d / "k6-requests.csv.gz")
    off_ms = off if num(off) else 0
    csv_only = []
    if csvd is not None:
        left = sorted(csvd["x5"], key=lambda x: x[1])
        rows5 = sorted((o for o in reqs if o.get("op") in O.APP_OPS and num(o.get("st")) and o["st"] >= 500 and num(o.get("tr"))), key=lambda o: o["tr"])
        for o in rows5:
            for k_, (nm, t_s, dur, st_) in enumerate(left):
                if nm == o["op"] and abs(t_s * 1000 + off_ms - o["tr"]) <= 1000 + (vals.get("app_log_match_tol_ms") or 0):
                    del left[k_]
                    break
        csv_only = [{"op": nm, "st": st_, "ts": t_s * 1000 + off_ms - dur - 1000, "tr": t_s * 1000 + off_ms + 1000, "csv_only": True} for nm, t_s, dur, st_ in left]
    edge_out = None
    if edge:
        dl = (metric or {}).get("deadlock")
        # 5xx 원인 = 앱 로그에서 응답마다 직접 연결(리뷰 H2-3 — 집계로 추론하지 않는다): 처리되지 않은 요청 예외의 ERROR 로그(Tomcat
        #   'Servlet.service() … threw exception')를 응답 창 [ts − tol, tr + tol]에서 찾아 짝짓고(이른 것부터 · 한 로그는 한 응답에만),
        #   그 로그 블록에 'deadlock detected'(PostgreSQL 40P01 문구) 또는 '40P01'이 있으면 40P01로 본다. 짝이 없으면 '연결 불가'.
        tol = vals.get("app_log_match_tol_ms") or 0
        errs = app_errors(d / "app.log.gz")
        if errs is None:
            reasons.add("app-log-missing")   # 경계 셀: 앱 로그 없음 · 비었음 · 깨짐(기동 머리 줄 없음) — 5xx 원인을 셀 수 없다(리뷰 H3-3)
        # 응답 끝(tr) 순 · 창 안 가장 이른 미사용 블록(리뷰 H3-4 — 긴 응답이 짧은 응답의 블록을 먼저 가져가지 않게) · 행 없는 CSV 5xx도 같은 규칙(창 = CSV 끝 시각 ±1초)
        five = sorted([o for o in reqs if o.get("op") in O.APP_OPS and num(o.get("st")) and o["st"] >= 500] + csv_only, key=lambda o: o.get("tr") or 0)
        used_i, link = set(), {"40p01": 0, "other": 0, "unlinked": 0, "csv_only": len(csv_only)}
        for o in five:
            pick = None
            for k_, e in enumerate(errs or []):
                if k_ not in used_i and num(o.get("ts")) and num(o.get("tr")) and o["ts"] - tol <= e["t_ms"] <= o["tr"] + tol:
                    pick = k_
                    break
            if pick is None:
                link["unlinked"] += errs is not None   # 로그가 정상인데 창 안에 블록이 없을 때만 연결 불가(로그 자체가 없으면 app-log-missing 무효 — H3-3)
            else:
                used_i.add(pick)
                link["40p01" if errs[pick]["deadlock"] else "other"] += 1
        eps_ms = (params.get("eps") or 0) * 1000
        batch_ms = (plan.get("app") or {}).get("batch_s", 0) * 1000
        # 노출량(리뷰 H2-3 — 유효성 조건이 아니다): 흐름마다 예약 창 송신 수(in_window · |ts − 중심| ≤ eps) · 경합 창 진입(ts ∈ (E − 배치, E]) ·
        #   straddle(ts < E ≤ tr) · 요청 구간이 E를 품음(ts ≤ E ≤ tr). 남는 무효 조건은 흐름마다 창 안에 보낸 요청 0(송신 실패)뿐
        expo = {k: {"in_window": 0, "entered": 0, "straddle": 0, "contains": 0, "sent": 0} for k in ("edge_confirm", "edge_ps")}
        ent = {"edge_confirm": {}, "edge_ps": {}}
        for r in rows:
            if r.get("kind") == "edge_confirm" and r.get("cf") and r.get("ps"):
                E, o = r["ps"][0].get("exp"), r["cf"][0]
            elif r.get("kind") == "edge_ps" and r.get("ps") and r.get("hold"):
                E, o = r["hold"][0].get("exp"), r["ps"][0]
            else:
                continue
            if not (num(E) and num(o.get("ts")) and num(o.get("tr"))):
                continue
            x = expo[r["kind"]]
            centre = E + ((params.get("psCenter") or 0) * 1000 if r["kind"] == "edge_ps" else 0)
            x["sent"] += 1
            x["in_window"] += abs(o["ts"] - centre) <= eps_ms + 1
            x["entered"] += E - batch_ms < o["ts"] <= E
            x["straddle"] += o["ts"] < E <= o["tr"]
            x["contains"] += o["ts"] <= E <= o["tr"]
            key = f"{o.get('st')}:{o.get('code')}"
            ent[r["kind"]][key] = ent[r["kind"]].get(key, 0) + 1
        in_win = sum(x["in_window"] for x in expo.values())
        straddle = sum(x["straddle"] for x in expo.values())
        if rd is not None and any(x["in_window"] == 0 for x in expo.values()):
            reasons.add("edge-not-sent")   # 흐름마다 경계 창 안에 보낸 요청이 1건 이상이어야 한다(송신 실패 — 가정 2의 하네스 쪽 조건)
        refunds_done = sum(1 for t in tasks if t.get("type") == "CLOSE" and t.get("status") == "DONE" and t.get("last_result") == "REFUNDED")
        dl_total = sum((dl or {}).values()) if isinstance(dl, dict) else None
        x5e = dict(x5)   # 경계 셀 5xx = 세션 행 5xx + 행 없는 CSV 5xx(한 번씩 — 리뷰 H3-R2)
        for o in csv_only:
            x5e[o["op"]] = x5e.get(o["op"], 0) + 1
        edge_out = {"http_5xx": x5e, "http_5xx_total": sum(x5e.values()), "http_5xx_link": link, "app_log_errors": None if errs is None else len(errs),
                    "deadlock": dl, "deadlock_total": dl_total, "exposure": expo, "in_window": in_win, "straddle": straddle, "responses": ent,
                    "refunds_done": refunds_done, "close_by_source": {s: sum(1 for t in tasks if t.get("type") == "CLOSE" and t.get("source") == s)
                                                                      for s in ("REJECTION", "SWEEP", "CONFIRM")}}

    # ---- 환경: PC 링크 · PC 메모리 · CSV(빠진 반복 · 지연) · 예정 송신 지연 ----
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
    latency = {s: {"n": 0, "p50_ms": None, "p99_ms": None, "warmup_excluded": None} for s in STAGES}
    if csvd is None:
        reasons.add("k6-csv-missing")
    else:
        if csvd["dropped"] > 0:
            reasons.add("dropped-iterations")
        want = {n: 0 for n in CSV_NAMES}
        for o in reqs:
            if o.get("op") in want and num(o.get("st")) and 200 <= o["st"] < 300:
                want[o["op"]] += 1
        if any(len(csvd["ms"][n]) != want[n] for n in CSV_NAMES):
            reasons.add("latency-missing")   # CSV 2xx 수 ≠ 세션 로그 2xx 수(요청 종류마다)
        k6t0_s = kw.get("k6_t0_epoch") if isinstance(kw, dict) else None
        for s, names in STAGES.items():   # 첫 5초 안에 보낸 요청은 판정 표본에서 뺀다(고정 값 2026-11-20 · 뺀 수 = warmup_excluded)
            latency[s] = latency_of([x for n in names for x in csvd["ms"][n]], k6t0_s, vals.get("warmup_exclude_s"))
    lag_max = plan.get("send_lag_max_ms")
    lags = []
    for r in rows:
        for o in (r.get("hold") or []) + (r.get("ps") or []) + (r.get("cf") or []):
            if isinstance(o, dict) and num(o.get("at")) and num(o.get("ts")):
                lags.append(o["ts"] - o["at"])
    send_lag = {"n": len(lags), "p50": pct(lags, 0.5), "p99": pct(lags, 0.99), "max": max(lags) if lags else None,
                "over": sum(1 for x in lags if x > lag_max) if num(lag_max) else None}
    if not num(lag_max) or send_lag["over"]:
        reasons.add("env-send-lag")

    # ---- 부하 중 홀드 표본(리뷰 H1-1 — holds-sample.jsonl · 홀드가 살아 있는 시점의 부분 선점 · 만료 갈림 · 재고 소진 시각) ----
    samp = hold_samples(d / "holds-sample.jsonl", k6t0_ms, k6end_ms)
    gap_lim = (plan.get("hold_sample_gap_factor") or 0) * (plan.get("hold_sample_s") or 0)   # 계수 = plan18 HOLD_SAMPLE_GAP_FACTOR[scale] · 값 출처는 plan 한 곳
    if samp is None or samp["ok_n"] == 0 or samp["live_n"] == 0 or not num(samp["max_gap_s"]) or not gap_lim or samp["max_gap_s"] > gap_lim \
            or not num(vals.get("hold_sample_fail_max")) or samp["fail_ratio"] is None or samp["fail_ratio"] > vals["hold_sample_fail_max"]:
        reasons.add("hold-sample-missing")   # 표본 없음 · 홀드 산 표본 없음 · 창 안 공백 > 3 × 주기 · 실패 비율 > 10% — 부분 선점을 셀 수 없다(리뷰 H2-1)
    if samp is not None:
        samp["gap_limit_s"] = gap_lim

    # ---- G1 정합(M18-1 — 회차마다) ----
    items = {s: uids.get(s, 0) for s in O.G1_SHAPES}
    items["dead_tasks"] = sum(1 for t in tasks if t.get("status") == "DEAD") if db else None
    items["open_after_drain"] = sum(1 for i in db["payment_intent"] if i.get("state") == "OPEN") if db else None
    items["unfinished_tasks"] = sum(1 for t in tasks if t.get("status") in ("PENDING", "IN_PROGRESS")) if db else None
    items["double_sold_seats"] = double_sold
    items["consistency_end"] = {k: cons.get(k) for k in PAY_ZERO_END + GROUP_ZERO} if cons else None
    items["consistency_k6"] = {k: cons_k6.get(k) for k in CONS_ZERO_K6} if cons_k6 else None
    items["consistency_v_sum"] = sum(v for k, v in cons.items() if k.startswith("v_") and num(v)) if cons else None
    items["consistency_k6_v_sum"] = sum(v for k, v in cons_k6.items() if k.startswith("v_") and num(v)) if cons_k6 else None
    items["integrity"] = sum(integ.values())
    # 응답만으로 본 앱 계약 위반(덤프와 무관 — 리뷰 H3-R1): 본문 계약 위반 · 한도 초과 응답
    items["integrity_response"] = sum(n for c, n in integ.items() if c in RESPONSE_INTEGRITY)
    items["unexpected"] = sum(unexp.values())
    items["main_5xx"] = 0 if edge else sum(x5.values())
    # 경계 셀 5xx(리뷰 H2-3): 앱 로그로 40P01과 연결된 5xx만 측정값이다 — 40P01이 아닌 5xx · 연결할 수 없는 5xx는 G1 실패
    items["edge_5xx_non40p01"] = edge_out["http_5xx_link"]["other"] if edge_out else 0
    items["edge_5xx_unlinked"] = edge_out["http_5xx_link"]["unlinked"] if edge_out else 0
    # 본 · 동시 경합 셀: 행이 남지 않은 앱 5xx(CSV에만 있음)도 5xx다(리뷰 H3-2 — 경계 셀은 위 로그 연결로 센다)
    items["group_cause_bad"] = cause_bad if plan.get("group") else 0
    items["csv_5xx_without_row"] = (len(csv_only) if csvd is not None else None) if not edge else 0
    # 본 · 동시 경합 셀의 교착은 0이어야 한다(설계-2 · 3 '교착이 생기지 않는다' · 하네스 설계 §8) — 경계 셀은 측정값(§8.2 edge)
    items["sample_partial"] = samp["partial_max"] if samp else None   # 부하 중 표본의 부분 선점 묶음 수 최대(기대 0)
    items["sample_split"] = samp["split_max"] if samp else None       # 부하 중 표본의 만료 갈림 묶음 수 최대(기대 0)
    items["deadlock"] = 0 if edge else (sum(metric["deadlock"].values()) if metric and isinstance(metric.get("deadlock"), dict) else None)
    items["record_pay_ok_not_in_ledger"] = rec_not_ledger
    items["record_pay_ok_no_intent"] = rec_no_intent
    items["record_ledger_paid_without_k6_pay"] = rec_paid_no_k6
    items["record_group_missing"] = rec_group_missing
    items["record_group_extra"] = rec_group_extra

    def zero(v):
        if isinstance(v, dict):
            return all(num(x) and x == 0 for x in v.values())
        return num(v) and v == 0
    # 확인 불가(None)로 둔 record 항목은 실패로 세지 않고 따로 적는다(리뷰 H3-R4 — 결측과 실제 0을 구분). 덤프 · 원장 자체가 없으면 g1은 거짓이다
    g1_unchecked_items = sorted(k for k in UNCHECKABLE if items.get(k) is None)
    g1_ok = db is not None and led is not None and all(zero(v) for k, v in items.items() if not (k in UNCHECKABLE and v is None))

    # ---- 비교 지표(M18-2) ----
    got = {"both": 0, "first": 0, "none": 0}
    for r in rows:
        if r.get("got") in got:
            got[r["got"]] += 1
    n_rows = len(rows)
    cat_n = {}
    for _, c in cats:
        cat_n[c] = cat_n.get(c, 0) + 1
    hold = {"n": n_rows, **got, "rate": got["both"] / n_rows if n_rows else None, "wilson": wilson(got["both"], n_rows),
            "both_fail_pairs": contention(rows), "two_seat_ms": dist([r["hold"][-1]["tr"] - r["hold"][0]["ts"] for r in rows if r.get("got") == "both"
                                                                      and num(r["hold"][0].get("ts")) and num(r["hold"][-1].get("tr"))]), "first_only": first_only(rows, method) if method in ("B0", "B1") else None}
    outcomes = {c: cat_n.get(c, 0) for c in O.CATEGORIES}
    burst_out = burst_stats(rows, plan, k6_group_rj) if cell == plan18.BURST else None
    if burst_out is not None and burst_out["bad"]:
        reasons.add("burst-spread")   # 묶음 안 송신 폭이 문턱을 넘는 묶음이 많다 — 동시성이 성립하지 않음(하네스 설계 §8)
    if burst_out is not None:   # 묶음 안 2석 성공이 둘 이상 = 같은 b + 1 좌석을 둘이 잡음 — 정합 실패(리뷰 H1-10)
        items["burst_multi_success"] = burst_out["multi_success"]
        g1_ok = g1_ok and burst_out["multi_success"] == 0
    outcomes["refunds_done"] = sum(1 for t in tasks if t.get("type") == "CLOSE" and t.get("status") == "DONE" and t.get("last_result") == "REFUNDED")

    out_reasons = harness + sorted(r for r in reasons if r not in harness)
    status = "ok" if not out_reasons else "invalid:" + ",".join(out_reasons)
    summ = {"method": method, "cell": cell, "rep": rep, "status": status, "reasons": out_reasons,
            "sessions": {"n": n_rows, "planned": planned, "by_kind": by_kind, "invalid_detail": inv, "integrity": integ, "fail": fail, "unexpected": unexp},
            "uids": uids, "g1": {"ok": g1_ok, "items": items, "unchecked": g1_unchecked_items}, "hold": hold, "latency": latency, "outcomes": outcomes, "edge": edge_out,
            "burst": burst_out, "hold_samples": samp, "order": plan.get("order"), "metric": metric, "record": record, "drain": drain_out, "send_lag_ms": send_lag, "pc_mem": pc_mem}
    return summ, status


# ---- 캠페인 ------------------------------------------------------------------------------------------------------------------------
def attempts_of(cdir, method, cell, rep):
    base = Path(cdir) / method / cell
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
    runs, used, fixed, picks = {}, {}, None, {}
    g1_in_invalid, g1_unchecked = [], []   # 사용하지 않은 시도(무효 · 앞 시도)에서 실제 정합 위반이 보인 것(리뷰 H2-6 — 메인 정책 '정합성 먼저')
    for _, rep, cell, method in plan18.order():
        tries = attempts_of(cdir, method, cell, rep)
        key = f"{method}/{cell}/rep{rep}"
        pick0 = next((p for p in tries if status_of(p) == "ok"), None)
        for p in tries:
            if p == pick0:
                continue
            s0 = load(p / "summary18.json")
            if not isinstance(s0, dict) and (p / "plan.json").exists():
                s0, _ = rep_summary(p)
            if isinstance(s0, dict) and (s0.get("g1") or {}).get("ok") is False and g1_violation(s0["g1"].get("items") or {}):
                its = s0["g1"].get("items") or {}
                if set(s0.get("reasons") or []) & DUMP_UNSTABLE:
                    # 덤프가 안정 스냅숏이 아닌 시도: 덤프에 기대는 항목(끝 상태 · record · 배수 뒤 정합)만 확인 불가로 돌리고,
                    #   응답 · CSV · 홀드 표본 · 지표에서 따로 본 위반은 그대로 g1_in_invalid에 남긴다(리뷰 H3-R1)
                    if g1_violation({k: v for k, v in its.items() if k in DUMP_INDEPENDENT}):
                        g1_in_invalid.append(f"{key}:{p.name}")
                    else:
                        g1_unchecked.append(f"{key}:{p.name}")
                else:
                    g1_in_invalid.append(f"{key}:{p.name}")
        pick = next((p for p in tries if status_of(p) == "ok"), None)
        runs[key] = {"status": status_of(pick) if pick else (status_of(tries[0]) if tries else ""), "attempts": len(tries), "used": pick.name if pick else None,
                     "drain": None}
        if pick:
            s = load(pick / "summary18.json")
            if not isinstance(s, dict):
                s, _ = rep_summary(pick)
            if isinstance(s, dict) and s.get("status") == "ok":
                used[key] = s
                runs[key]["drain"] = s.get("drain")   # 실측 배수(reason · len_s) — 상한 240초 [초안] 확인용
                picks[key] = pick
                pl = load(pick / "plan.json")
                if isinstance(pl, dict) and fixed is None:
                    fixed = pl.get("fixed")
            else:
                runs[key]["used"] = None
    # 회차 사이 대조(M18-7): 사용 회차 전부의 plan.json values 전체 · meta.json 측정 SHA · 하네스 해시가 같아야 한다
    sigs = []
    for k, pick in picks.items():
        pl, mt = load(pick / "plan.json"), load(pick / "meta.json")
        sigs.append(json.dumps([(pl or {}).get("values"), (mt or {}).get("sha"), (mt or {}).get("harness_hash")], sort_keys=True)
                    if isinstance(pl, dict) and isinstance(pl.get("values"), dict) and isinstance(mt, dict) and mt.get("sha") and mt.get("harness_hash") else None)
    consistent = bool(sigs) and None not in sigs and len(set(sigs)) == 1
    # 측정 전 고정 값으로만(리뷰 H1-9 — M18-7): plan.fixed가 참이 아니거나 스모크(smoke) · 축소(over) 회차가 하나라도 섞이면 판정하지 않는다
    unfixed = []
    for k, pick in picks.items():
        pl = load(pick / "plan.json")
        if not isinstance(pl, dict) or pl.get("fixed") is not True or pl.get("smoke") or pl.get("over"):
            unfixed.append(k)
    limits = json.loads(sigs[0])[0].get("p99_limit_ms") if consistent else None
    full = len(used) == len(runs)
    g1 = {}
    for method in plan18.METHODS:
        for cell in plan18.CELLS:
            if method not in plan18.methods_of(cell):
                continue
            mine = {k: v for k, v in used.items() if k.startswith(f"{method}/{cell}/")}
            fails = sorted(k for k, v in mine.items() if not v["g1"]["ok"])
            g1[f"{method}/{cell}"] = {"ok": not fails and len(mine) == plan18.REPS, "fail_runs": fails, "reps_used": len(mine)}
    table = {}
    for method in plan18.METHODS:
        table[method] = {}
        for ov in plan18.OVERLAPS:
            ss = [used[f"{method}/{ov}/rep{r}"] for r in range(1, plan18.REPS + 1) if f"{method}/{ov}/rep{r}" in used]
            n = sum(s["hold"]["n"] for s in ss)
            k = sum(s["hold"]["both"] for s in ss)
            pairs = sum(s["hold"]["both_fail_pairs"]["pairs"] for s in ss)
            bf = sum(s["hold"]["both_fail_pairs"]["both_fail"] for s in ss)
            fo = [s["hold"]["first_only"] for s in ss if s["hold"].get("first_only")]
            lat = {}
            for st in STAGES:
                p99s = [s["latency"][st]["p99_ms"] for s in ss]
                lim = (limits or {}).get(st)
                lat[st] = {"p50_ms": [s["latency"][st]["p50_ms"] for s in ss], "p99_ms": p99s,
                           "warmup_excluded": [s["latency"][st].get("warmup_excluded") for s in ss],
                           "median_p99_ms": statistics.median([x for x in p99s if num(x)]) if any(num(x) for x in p99s) else None,
                           "limit_ms": lim, "over": num(lim) and any(num(x) and x > lim for x in p99s),
                           "ok": num(lim) and len(p99s) == plan18.REPS and all(num(x) and x <= lim for x in p99s)}
            table[method][ov] = {
                "reps_used": len(ss), "sessions": n, "both": k, "rate": k / n if n else None, "wilson95": wilson(k, n),
                "rate_per_rep": [s["hold"]["rate"] for s in ss], "both_fail_pairs": {"pairs": pairs, "both_fail": bf, "ratio": bf / pairs if pairs else None},
                "first_only": {"n": sum(f["n"] for f in fo), "held_p99_s": [f["held_s"]["p99"] for f in fo], "held_p50_s": [f["held_s"]["p50"] for f in fo],
                               "blocked_sessions": sum(f["blocked_sessions"] for f in fo)} if method in ("B0", "B1") else None,
                "latency": lat, "success": sum(s["outcomes"]["success"] for s in ss), "confirm_rejected": sum(s["outcomes"]["confirm_rejected"] for s in ss),
                "confirm_mixed": sum(s["outcomes"]["confirm_mixed"] for s in ss), "refunds_done": sum(s["outcomes"]["refunds_done"] for s in ss)}
    # 짝 비교(리뷰 H1-11): 같은 rep 번호끼리 2석 성공률 차(A − B). 같은 rep의 두 회차는 같은 세션 시드를 쓰므로 짝을 이룬다.
    #   세션은 서로 독립이 아니다(같은 좌석을 다투므로) — 합친 Wilson 구간은 세션 독립 가정이라 폭이 낙관적이다. 판단은 짝 차의 부호 · 크기를 함께 본다(INTERFACE §8.3)
    def by_rep(method, cell):
        return {r: used[f"{method}/{cell}/rep{r}"]["hold"]["rate"] for r in range(1, plan18.REPS + 1) if f"{method}/{cell}/rep{r}" in used}
    paired = {}
    for cell in plan18.OVERLAPS + [plan18.BURST]:
        ms = plan18.methods_of(cell)
        paired[cell] = {}
        for i, a in enumerate(ms):
            for b in ms[i + 1:]:
                ra, rb = by_rep(a, cell), by_rep(b, cell)
                diffs = {str(r): ra[r] - rb[r] for r in ra if r in rb and num(ra[r]) and num(rb[r])}
                xs = list(diffs.values())
                paired[cell][f"{a}-{b}"] = {"diffs": diffs, "mean": sum(xs) / len(xs) if xs else None, "min": min(xs) if xs else None,
                                            "max": max(xs) if xs else None, "same_sign": bool(xs) and (all(x > 0 for x in xs) or all(x < 0 for x in xs))}
    for method in plan18.METHODS:
        for ov in plan18.OVERLAPS:
            table[method][ov]["rate_by_rep"] = {str(r): v for r, v in by_rep(method, ov).items()}
    edge = {}
    for method in plan18.EDGE_METHODS:
        ss = [used[f"{method}/edge/rep{r}"] for r in range(1, plan18.REPS + 1) if f"{method}/edge/rep{r}" in used]
        agg = {"reps_used": len(ss), "deadlock": {}, "http_5xx": {}, "http_5xx_link": {}, "deadlock_total": 0, "http_5xx_total": 0,
               "refunds_done": 0, "in_window": 0, "straddle": 0, "exposure": {},
               "latency": {st: {"p99_ms": [x["latency"][st]["p99_ms"] for x in ss], "p50_ms": [x["latency"][st]["p50_ms"] for x in ss]} for st in STAGES},
               "g1_ok": g1[f"{method}/edge"]["ok"], "close_by_source": {}}
        for s in ss:
            e = s.get("edge") or {}
            for key in ("deadlock", "http_5xx", "http_5xx_link", "close_by_source"):
                for kk, v in (e.get(key) or {}).items():
                    agg[key][kk] = agg[key].get(kk, 0) + v
            for key in ("refunds_done", "in_window", "straddle", "deadlock_total", "http_5xx_total"):
                agg[key] += e.get(key) or 0
            for flow, x in (e.get("exposure") or {}).items():
                for kk, v in x.items():
                    agg["exposure"].setdefault(flow, {})
                    agg["exposure"][flow][kk] = agg["exposure"][flow].get(kk, 0) + v
        edge[method] = agg
    burst = {}
    for method in plan18.BURST_METHODS:
        ss = [used[f"{method}/burst/rep{r}"] for r in range(1, plan18.REPS + 1) if f"{method}/burst/rep{r}" in used]
        agg = {"reps_used": len(ss), "bursts": 0, "all_fail": 0, "multi_success": 0, "success_hist": {}, "causes": {}, "spread_p99_ms": [],
               "over": 0, "both": 0, "sessions": 0, "http_5xx": 0, "deadlock": 0, "g1_ok": g1[f"{method}/burst"]["ok"]}
        for s in ss:
            b = s.get("burst") or {}
            for key in ("bursts", "all_fail", "multi_success", "over"):
                agg[key] += b.get(key) or 0
            for key in ("success_hist", "causes"):
                for kk, v in (b.get(key) or {}).items():
                    agg[key][kk] = agg[key].get(kk, 0) + v
            agg["spread_p99_ms"].append((b.get("spread_ms") or {}).get("p99"))
            agg["both"] += s["hold"]["both"]
            agg["sessions"] += s["hold"]["n"]
            agg["http_5xx"] += s["g1"]["items"].get("main_5xx") or 0
            agg["deadlock"] += s["g1"]["items"].get("deadlock") or 0
        lim = limits or {}
        agg["latency"] = {}
        for st in STAGES:   # p99 상한 판정에 동시 경합 셀도 넣는다(리뷰 H2-2 — 경계 셀은 TTL · W를 바꾼 눈금이라 보고만)
            p99s = [x["latency"][st]["p99_ms"] for x in ss]
            agg["latency"][st] = {"p99_ms": p99s, "warmup_excluded": [x["latency"][st].get("warmup_excluded") for x in ss], "limit_ms": lim.get(st), "over": num(lim.get(st)) and any(num(x) and x > lim[st] for x in p99s),
                                  "ok": num(lim.get(st)) and len(p99s) == plan18.REPS and all(num(x) and x <= lim[st] for x in p99s)}
        agg["all_fail_ratio"] = agg["all_fail"] / agg["bursts"] if agg["bursts"] else None
        agg["rate"] = agg["both"] / agg["sessions"] if agg["sessions"] else None
        agg["wilson95"] = wilson(agg["both"], agg["sessions"])
        burst[method] = agg
    pas = {}
    for method in plan18.METHODS:
        cells = [c for c in plan18.CELLS if method in plan18.methods_of(c)]
        g1_fail = any(g1[f"{method}/{c}"]["fail_runs"] for c in cells)
        lat_cells = [table[method][ov]["latency"] for ov in plan18.OVERLAPS] + ([burst[method]["latency"]] if method in burst else [])
        over = any(lc[st]["over"] for lc in lat_cells for st in STAGES)
        complete = all(g1[f"{method}/{c}"]["reps_used"] == plan18.REPS for c in cells) and consistent and not unfixed
        lim_ok = all(lc[st]["ok"] for lc in lat_cells for st in STAGES)   # 상한 없음 · 표본 없음 · 회차 부족 = 판정 불가
        g1_inv = any(x.startswith(f"{method}/") for x in g1_in_invalid)   # 무효 시도에서 본 정합 위반도 그 방식을 통과시키지 않는다(H2-6)
        if g1_fail or over or g1_inv:
            pas[method] = False   # 판정된 실패가 우선(정합 먼저 · p99 상한)
        elif not complete or not lim_ok:
            pas[method] = "insufficient"
        else:
            pas[method] = True
    if unfixed:   # 고정 값이 아닌 회차가 섞이면 판정된 실패도 '판정 불가'로 둔다(그 회차 값으로 고른 결론이 아니다)
        pas = {m: "insufficient" for m in pas}
    return {"plan_fixed": fixed, "runs_consistent": consistent, "complete": full and consistent and not unfixed, "unfixed_runs": sorted(unfixed), "g1_in_invalid": sorted(g1_in_invalid), "g1_unchecked_dump": sorted(g1_unchecked),
            "paired": paired, "runs": runs, "g1": g1, "table": table, "edge": edge, "burst": burst,
            "pass": pas, "note": "기본값 결정(묶음 API 기본 켬 · split · one-shot 선택)은 사람이 한다 — 이 표는 근거다(하네스 설계 §6)"}


def main(argv):
    a = argv[1:]
    if len(a) == 2 and a[0] == "--rep":
        s, st = rep_summary(a[1])
        if s is None:
            print("plan.json 없음 · 깨짐 — 판정 불가", file=sys.stderr)
            return 2
        Path(a[1], "summary18.json").write_text(json.dumps(s, ensure_ascii=False, indent=1))
        print(st)
        return 0
    if len(a) == 2 and a[0] == "--campaign":
        out = campaign(a[1])
        Path(a[1], "campaign18.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
        print(f"complete:{str(out['complete']).lower()}")
        print("pass:" + json.dumps(out["pass"], ensure_ascii=False, separators=(",", ":")))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
