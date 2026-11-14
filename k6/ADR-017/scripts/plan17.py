#!/usr/bin/env python3
"""ADR-017 계획의 단일 출처(측정 명세 docs/plans/2026-11-14/adr-017-measurement §1-1 · ADR-017 설계-8) — 후보 3(R · D · S) × 셀 3(base · pg · kill) × 반복 3 = 27회차 ·
회전 순서 · 세션 시드 · 세션 종류 교차 배정 · 회차 렌더(앱 설정 · k6 환경 · 시드 · PG 주입 · kill 일정 · 배수 창) · 기대 설정(configprops 대조) · dry-run.
run17.sh · campaign17.sh · outcome17.py · summarize17.py · selftest17.py가 이 파일을 읽는다(계약 = k6/ADR-017/INTERFACE.md).

    plan17.py --order                                                → 27행 "순번 회차 셀 후보"(campaign17.sh order.txt)
    plan17.py --render --cand <R|D|S> --cell <base|pg|kill> --rep k [--over '<json>']  → 회차 계획 JSON(plan.json · --over = 스모크 축소 — OVER_KEYS)
    plan17.py --dry-run [--json]                                     → 회차 시간 · VU · 좌석 · 총 시간 · 줄이는 안 — 문제 있으면 exit 1
    plan17.py --config-check <cand> <cell> <app-config.json>         → {"reasons":[…],"detail":[…]} · exit 0 일치 · 1 불일치 · 2 판정 불가
    plan17.py --fixed                                                → exit 0 = 측정 전 고정 값(FIXED = True) · 1 = 자리표시 값(실제 실행 거부)
    plan17.py --session <rep> <sid>                                  → 그 세션의 종류 · 시드 속성 JSON(paid-flow17.js와 같은 값 — 요약기 · selftest 대조용)

**FIXED = False — 자리표시 값.** 세션 비율 · 지연 분포 · 세션 수 · 도착률 · PG 주입 · kill 시각 · 배수 창 상한은 .164 스모크 뒤 측정 전 고정 커밋에서 정한다(명세 §1-2 · M17-2).
앱 눈금(TTL · W · θ · M · 배치 · 워커 · 안전망 손잡이)은 앱 기본값 그대로다(명세 §0 '실제 눈금')."""
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent / "ADR-016" / "scripts"))
sys.path.insert(0, str(HERE.parent.parent / "ADR-015" / "scripts"))
import plan15  # noqa: E402
from plan16 import dur, fnv1a, iso_dur, mulberry32  # noqa: E402  — k6 lib14.js와 같은 비트의 난수(plan16 그대로)

FIXED = False   # 측정 전 고정 커밋 전 — run17.sh · campaign17.sh는 --dry-run · --simulate · --smoke만 허용

# ---- 앱 눈금(application.yml · CompensationProperties · PaymentProperties 기본값 — 실제 눈금 · 바꾸지 않는다) -----------------------------------
TTL_S = 300.0                 # seat.hold.ttl 5m
BATCH_S = 10.0                # seat.hold.expiry-interval 10s(A′ 만료 배치)
W_S = 611.0                   # seat.confirm.payment-window(④ 연장 길이 — ADR-016 §결정)
THETA_S = 1.0                 # seat.confirm.payment-min-remaining
M_S = 1.0                     # seat.confirm.pay-by-margin(payBy = 연장 만료 − M)
CONFIRM_MODE = "conditional-extend"
COMP = {"worker": {"interval_s": 1.0, "batch": 50, "lease_s": 30.0, "max_attempts": 10, "backoff_initial_s": 1.0, "backoff_max_s": 60.0},
        "sweep": {"interval_s": 5.0, "batch": 200}}
PG_CONNECT_TIMEOUT_S, PG_READ_TIMEOUT_S, AMOUNT = 1.0, 3.0, 10_000
PG_BASE_URL_APP = "http://mock-pg:8104"   # 앱 컨테이너가 보는 주소(compose 망) — k6 PC는 run17.sh가 http://<서버>:8104를 PG_URL로 넘긴다
MOCKPG_AUTH_TTL = "30m"                   # mock-pg 승인 유효 시간(기본값과 같게 늘 명시 — T + W ≈ 851초보다 길어야 한다)
MOCKPG_AUTH_TTL_S = 1800.0
POOL = plan15.POOL

# ---- 후보 · 셀 · 반복 -------------------------------------------------------------------------------------------------------
CANDS = {"R": "refund", "D": "auth-capture", "S": "substitute"}
CAND_ORDER = ["R", "D", "S"]
CELLS = ["base", "pg", "kill"]      # 회차 안 묶음 순서(base → pg → kill)
REPS = 3
PG_OP = {"R": "pay", "D": "authorize", "S": "pay"}   # 클라이언트(k6)가 부르는 PG 결제 호출(설계-5)

# ---- 측정 전 고정 자리(자리표시 — 명세 §0 초안) ----------------------------------------------------------------------------------------
N_SESSIONS = 1100             # 회차 세션 수(명세 §0 '약 1,100')
RATE = 20                     # 일정 도착률(세션/초 — ADR-016 paid 셀 선례) → 도착 구간 55초
KINDS = ("ok", "late", "noconf", "abandon", "retry")
# 세션 종류 비율(%) — 정상 확정 · 지연 확정 · 확정 안 보냄 · 결제 안 하고 이탈 · 같은 uid 재시도(명세 §0 초안)
KIND_WEIGHTS = {"ok": 60, "late": 20, "noconf": 5, "abandon": 10, "retry": 5}
T_RANGE = (30.0, 240.0)       # 생각 시간 T(선점 → payment-start) ~ U — ADR-016 기준 분포
PAY_MEDIAN_S, PAY_SIGMA = 45.0, 0.94   # 결제 시간 P(payment-start 응답 → PG pay · authorize) ~ 로그정규(ADR-016 D 기준 분포)
PAY_MAX_S = 300.0             # P 상한(넘으면 이 값 — PG 마감 payBy = 연장 만료 − M ≈ W − M 안에 결제가 끝나게: 결제 거부를 만들지 않는다)
CONF_GAP_RANGE = (0.0, 2.0)   # PG 응답 → 확정(ok · retry 첫 확정) ~ U초
RETRY_GAP_RANGE = (0.0, 2.0)  # 첫 확정 응답 → 같은 uid 재확정(retry) ~ U초
LATE_RANGE = (0.2, 10.0)      # 지연 확정 송신 = payment-start 응답 expiresAt(연장 만료) + L ~ U초(I14 — 만료를 넘겨 거부를 만든다)
SEAT_FACTOR = 1.5             # 회차 좌석 = ⌈세션 × 1.5⌉(S 대체용 여유 — 명세 §0)
# PG 주입(pg 셀만 · 보상 호출만 — 사용자 결제 pay · authorize와 등록 register엔 없음). unknownDelayMs > 앱 PG 읽기 타임아웃(3초)이어야 '결과 모름'이 된다
PG_RULE = {"delayMs": 200, "failRate": 0.10, "unknownRate": 0.05, "unknownDelayMs": 5000}
PG_FAULT_OPS = ("capture", "close")   # mock-pg 주입 단위 = 변경 호출(register · pay · authorize · capture · close) — 조회 GET은 주입 지점이 없다(INTERFACE §8)
KILL_AT_S = (180.0, 760.0)    # kill 셀: k6 시작(t0) 뒤 앱 SIGKILL 시각(초) 2회 → 곧바로 재기동(명세 §0) — ① 결제 단계 한가운데 · ② 지연 확정 거부 · 안전망 종결이 몰리는 구간
DRAIN_CAP_S = 600.0           # 배수 창 상한(k6 끝 뒤) — 넘으면 회차 무효 drain-timeout
KILL_TOL_MS = 5000            # kill 시각 허용 오차(계획 ↔ 실제 kill · k6 시작 대조 — summarize17 kill-not-observed)
DRAIN_POLL_S = 5.0
DRAIN_EST_S = {"base": 30.0, "pg": 90.0, "kill": 60.0}   # dry-run 손셈용 예상 배수 시간(스모크 실측으로 고정)
G2_X_S, G2_Y_S = 30.0, 120.0  # G2 문턱 초안(Q6 — 스모크 실측 뒤 고정)

# ---- 하네스 상수(고정) ------------------------------------------------------------------------------------------------------
USER_BASE = 1_000_000         # 세션 사용자 = USER_BASE + sid
SCHEDULE = 1                  # 회차 하나(LoadtestDataService.reset — 좌석 section 은 모두 'A')
GRACE_MARGIN_S = 30
OTHER_S = 160                 # 회차 고정 몫(배포 · 기동 · 예열 · 시드 · PG reset · 시작 대기 87초 — ADR-016 OTHER_S 150 + PG 준비 10)
PRE_VUS_HEADROOM, PRE_VUS_PAD, MAX_VUS_FACTOR = 1.15, 20, 1.5
BUDGET_HOURS = 12.0           # 명세 I12
SESSION_SEED = "adr017"
PG_SEED = "adr017-pg"


# ---- 세션 종류 · 속성 ------------------------------------------------------------------------------------------------------------
def kind_cycle(weights=None):
    """교차 배정 한 바퀴(세션 번호 mod 바퀴 길이): 비율을 최대공약수로 줄인 칸 수를 매끈한 가중 순환(nginx smooth weighted round-robin)으로 펼친다 —
    종류가 바퀴 안에서 고르게 흩어져 시간 효과(도착 순서)와 섞이지 않는다. 같은 비율 = 같은 바퀴."""
    w = weights or KIND_WEIGHTS
    g = 0
    for v in w.values():
        g = math.gcd(g, int(v))
    slots = {k: int(w[k]) // g for k in KINDS if int(w.get(k, 0)) > 0}
    total = sum(slots.values())
    cur = dict.fromkeys(slots, 0)
    out = []
    for _ in range(total):
        for k in slots:
            cur[k] += slots[k]
        best = max(slots, key=lambda k: (cur[k], -KINDS.index(k)))
        cur[best] -= total
        out.append(best)
    return out


def planned(n_total, cyc=None):
    cyc = cyc or kind_cycle()
    out = dict.fromkeys(KINDS, 0)
    for sid in range(n_total):
        out[cyc[sid % len(cyc)]] += 1
    return out


def session_attrs(rep, sid, p=None):
    """(회차, 세션 번호) 시드 → T · P · G · R · L(초). lib17.js sessionAttrs17과 같은 식 · 같은 순서(종류와 무관하게 다 뽑는다 — 후보 · 셀이 같은 값을 받는다).
    T ~ U[tMin, tMax] · P = min(exp(ln m + σ z), payMax) · z = √(−2 ln(1 − u1)) cos(2π u2) · G ~ U[gMin, gMax] · R ~ U[rMin, rMax] · L ~ U[lMin, lMax]."""
    p = p or params()
    r = mulberry32(fnv1a(f"{SESSION_SEED}|{rep}|{sid}"))
    t = p["tMin"] + r() * (p["tMax"] - p["tMin"])
    u1, u2 = r(), r()
    z = math.sqrt(-2.0 * math.log(1.0 - u1)) * math.cos(2.0 * math.pi * u2)
    pay = min(math.exp(math.log(p["payMedian"]) + p["paySigma"] * z), p["payMax"])
    g = p["gMin"] + r() * (p["gMax"] - p["gMin"])
    rr = p["rMin"] + r() * (p["rMax"] - p["rMin"])
    lt = p["lMin"] + r() * (p["lMax"] - p["lMin"])
    return {"T": t, "P": pay, "G": g, "R": rr, "L": lt}


def params(over=None):
    p = {"tMin": T_RANGE[0], "tMax": T_RANGE[1], "payMedian": PAY_MEDIAN_S, "paySigma": PAY_SIGMA, "payMax": PAY_MAX_S,
         "gMin": CONF_GAP_RANGE[0], "gMax": CONF_GAP_RANGE[1], "rMin": RETRY_GAP_RANGE[0], "rMax": RETRY_GAP_RANGE[1],
         "lMin": LATE_RANGE[0], "lMax": LATE_RANGE[1]}
    p.update(over or {})
    return p


def session_life(kind, a):
    """세션 수명(초 · 선점 응답 뒤 · 응답 시간 0으로 본 근사) — VU · grace 계산용. late = T + W + L(연장 만료 = payment-start 시각 + W)."""
    if kind == "abandon":
        return a["T"]
    if kind == "noconf":
        return a["T"] + a["P"]
    if kind == "late":
        return a["T"] + W_S + a["L"]
    if kind == "retry":
        return a["T"] + a["P"] + a["G"] + a["R"]
    return a["T"] + a["P"] + a["G"]


def schedule(rep, n_total, rate, p=None, cyc=None):
    """결정적 일정(도착 = sid ÷ 도착률) → 최대 동시 세션 · 마지막 끝 시각 · 수명 목록."""
    cyc = cyc or kind_cycle()
    ev, lives = [], []
    for sid in range(n_total):
        a = session_attrs(rep, sid, p)
        life = session_life(cyc[sid % len(cyc)], a)
        lives.append(life)
        t0 = sid / rate
        ev.append((t0, 1))
        ev.append((t0 + life, -1))
    cur = peak = 0
    for _, d in sorted(ev, key=lambda e: (e[0], e[1])):
        cur += d
        peak = max(peak, cur)
    end = max((sid / rate) + lives[sid] for sid in range(n_total))
    return peak, end, lives


def seed_of(n_total):
    seats = math.ceil(n_total * SEAT_FACTOR)
    return {"schedules": 1, "seats": seats, "spare": seats - n_total, "sessions_max": n_total, "section": "A"}


def seat_of(sid, spare):
    """세션 좌석 = 여유 좌석 뒤(id spare + sid + 1). 대체 배정(SubstituteAllocator — 같은 회차 · section의 AVAILABLE 좌석을 id 순으로)이 아직 오지 않은 세션의 좌석이 아니라
    여유 좌석(id 1..spare)을 먼저 집게 한다."""
    return {"schedule": SCHEDULE, "seat": spare + sid + 1}


def pg_faults(cell, rep):
    """/admin/faults 본문(mock-pg Faults.Config) — 셀마다 늘 설정한다(base · kill = 규칙 없음 · 시드만). 시드 = (회차) — 같은 회차의 세 후보가 같은 열을 받는다."""
    rules = {op: dict(PG_RULE) for op in PG_FAULT_OPS} if cell == "pg" else {}
    return {"seed": fnv1a(f"{PG_SEED}|{rep}"), "rules": rules}


def values():
    return {"fixed": FIXED, "n_sessions": N_SESSIONS, "rate": RATE, "kind_weights": KIND_WEIGHTS, "t_range": list(T_RANGE),
            "pay_median_s": PAY_MEDIAN_S, "pay_sigma": PAY_SIGMA, "pay_max_s": PAY_MAX_S, "conf_gap_range": list(CONF_GAP_RANGE),
            "retry_gap_range": list(RETRY_GAP_RANGE), "late_range": list(LATE_RANGE), "seat_factor": SEAT_FACTOR, "pg_rule": PG_RULE,
            "pg_fault_ops": list(PG_FAULT_OPS), "kill_at_s": list(KILL_AT_S), "kill_tol_ms": KILL_TOL_MS, "drain_cap_s": DRAIN_CAP_S, "g2_x_s": G2_X_S, "g2_y_s": G2_Y_S,
            "ttl_s": TTL_S, "batch_s": BATCH_S, "W_s": W_S, "theta_s": THETA_S, "M_s": M_S, "compensation": COMP,
            "pg_read_timeout_s": PG_READ_TIMEOUT_S, "mockpg_auth_ttl": MOCKPG_AUTH_TTL}


_RENDER_CACHE = {}
# --render --over(스모크 · 로컬 축소 회차 전용 — run17.sh가 --smoke일 때만 넘긴다 · plan.json에 "over"로 남는다): 세션 수 · 도착률 · 세션 분포 · kill 시각 · 배수 상한
OVER_KEYS = {"n", "rate", "drainCap", "killAt", "tMin", "tMax", "payMedian", "paySigma", "payMax", "gMin", "gMax", "rMin", "rMax", "lMin", "lMax"}


def render(cand, cell, rep, over=None):
    """회차 계획(같은 인자 = 같은 값). over = dry-run '줄이는 안' 전용 덮어쓰기({n, rate, tMax …}) — 실행 렌더에는 쓰지 않는다."""
    key = (cand, cell, rep, json.dumps(over or {}, sort_keys=True))
    if key not in _RENDER_CACHE:
        _RENDER_CACHE[key] = json.dumps(_render(cand, cell, rep, over or {}))
    return json.loads(_RENDER_CACHE[key])


def _render(cand, cell, rep, over):
    if cand not in CANDS or cell not in CELLS or rep not in range(1, REPS + 1):
        raise SystemExit(f"후보 {cand} · 셀 {cell} · 회차 {rep}")
    n_total = int(over.get("n", N_SESSIONS))
    rate = float(over.get("rate", RATE))
    p = params({k: v for k, v in over.items() if k in params()})
    cyc = kind_cycle()
    d_arr = n_total / rate
    peak, end, lives = schedule(rep, n_total, rate, p, cyc)
    grace = math.ceil(max(p["tMax"] + W_S + p["lMax"], max(lives)) + GRACE_MARGIN_S)
    pre = math.ceil(peak * PRE_VUS_HEADROOM) + PRE_VUS_PAD
    mx = math.ceil(pre * MAX_VUS_FACTOR)
    seed = seed_of(n_total)
    k6_est = max(end, d_arr) + 5
    drain_est = DRAIN_EST_S[cell]
    drain_cap = float(over.get("drainCap", DRAIN_CAP_S))
    app_env = {
        "CONFIRM_MODE": CONFIRM_MODE, "CONFIRM_DELAY": "0ms", "PAYMENT_WINDOW": dur(W_S), "PAYMENT_MIN_REMAINING": dur(THETA_S), "PAY_BY_MARGIN": dur(M_S),
        "HOLD_TTL": dur(TTL_S), "EXPIRY_INTERVAL": dur(BATCH_S), "COMPENSATION_MODE": CANDS[cand], "PG_BASE_URL": PG_BASE_URL_APP,
        "MOCKPG_AUTH_TTL": MOCKPG_AUTH_TTL, "FLYWAY_LOCATIONS": ",".join(plan15.FLYWAY), "POOL_SIZE": str(POOL),
        "TOMCAT_MAX_CONNECTIONS": str(plan15.TOMCAT_MAX_CONNECTIONS), "APP_NOFILE": str(plan15.APP_NOFILE), "PG_PRELOAD": "pg_stat_statements",
    }
    k6env = {
        "REP": str(rep), "CAND": cand, "COMP_MODE": CANDS[cand], "CELL": cell, "PG_OP": PG_OP[cand], "RATE": f"{rate:g}", "D_ARR_S": f"{d_arr:g}",
        "N_TOTAL": str(n_total), "GRACE_S": str(grace), "KIND_CYCLE": ",".join(cyc),
        "T_MIN_S": f"{p['tMin']:g}", "T_MAX_S": f"{p['tMax']:g}", "PAY_MEDIAN_S": f"{p['payMedian']:g}", "PAY_SIGMA": f"{p['paySigma']:g}",
        "PAY_MAX_S": f"{p['payMax']:g}", "GAP_MIN_S": f"{p['gMin']:g}", "GAP_MAX_S": f"{p['gMax']:g}", "RETRY_MIN_S": f"{p['rMin']:g}",
        "RETRY_MAX_S": f"{p['rMax']:g}", "LATE_MIN_S": f"{p['lMin']:g}", "LATE_MAX_S": f"{p['lMax']:g}",
        "SCHEDULE": str(SCHEDULE), "SEAT_SPARE": str(seed["spare"]), "USER_BASE": str(USER_BASE),
        "PRE_VUS": str(pre), "MAX_VUS": str(mx),
    }
    kill = {"at_s": [float(x) for x in over.get("killAt", KILL_AT_S)], "signal": "KILL", "restart": "immediate", "container": "seatlab-app-1"} if cell == "kill" else None
    return {
        "adr": "017", "cand": cand, "mode": CANDS[cand], "cell": cell, "rep": rep, "fixed": FIXED, "values": values(),
        "n_total": n_total, "rate": rate, "d_arr_s": d_arr, "kind_cycle": cyc, "planned": planned(n_total, cyc), "params": p,
        "seed": seed, "app_env": app_env, "grace_s": grace,
        "life": {"mean_s": round(sum(lives) / len(lives), 1), "max_s": round(max(lives), 1)}, "peak_concurrency": peak,
        "k6": {"script": "k6/ADR-017/scenarios/paid-flow17.js", "duration_s": d_arr + grace, "est_s": round(k6_est), "pre_vus": pre, "max_vus": mx,
               "lead_s": plan15.k6_lead_s(pre), "env": k6env},
        "pg": {"base_url_app": PG_BASE_URL_APP, "faults": pg_faults(cell, rep), "op": PG_OP[cand], "auth_ttl": MOCKPG_AUTH_TTL},
        "kill": kill,
        "drain": {"cap_s": drain_cap, "poll_s": DRAIN_POLL_S, "est_s": drain_est},
        "est_s": round(k6_est + drain_est + OTHER_S), "est_max_s": round(k6_est + drain_cap + OTHER_S),
    }


def order():
    """회차 우선(rep 1 → 3) · 묶음 순서 CELLS(base → pg → kill) · 묶음 g · 회차 r의 후보 = CAND_ORDER를 (r + g) mod 3칸 회전 —
    한 셀 안에서 세 후보가 세 회차에 걸쳐 각 자리(첫째 · 둘째 · 셋째)를 한 번씩 차지한다(plan16 order()와 같은 뜻)."""
    rows, seq = [], 0
    for r in range(1, REPS + 1):
        for g, cell in enumerate(CELLS):
            rot = (r + g) % len(CAND_ORDER)
            for cand in CAND_ORDER[rot:] + CAND_ORDER[:rot]:
                seq += 1
                rows.append((seq, r, cell, cand))
    return rows


def expected_config(cand):
    w = COMP["worker"]
    s = COMP["sweep"]
    return {
        "seat.confirm": {"mode": plan15.MODE_ENUM[CONFIRM_MODE], "loadtest.delay": "PT0S", "paymentWindow": iso_dur(W_S),
                         "paymentMinRemaining": iso_dur(THETA_S), "payByMargin": iso_dur(M_S)},
        "seat.hold": {"expiryMode": "SWEEP", "ttl": iso_dur(TTL_S), "expiryInterval": iso_dur(BATCH_S), "strategy": "PESSIMISTIC_NOWAIT",
                      "limitStrategy": "COUNTER_UPSERT_PRECHECK"},
        "seat.compensation": {"mode": CANDS[cand].upper().replace("-", "_"), "schedulerEnabled": True,
                              "worker.interval": iso_dur(w["interval_s"]), "worker.batch": w["batch"], "worker.lease": iso_dur(w["lease_s"]),
                              "worker.maxAttempts": w["max_attempts"], "worker.backoffInitial": iso_dur(w["backoff_initial_s"]),
                              "worker.backoffMax": iso_dur(w["backoff_max_s"]), "sweep.interval": iso_dur(s["interval_s"]), "sweep.batch": s["batch"]},
        "seat.payment": {"pg.baseUrl": PG_BASE_URL_APP, "pg.connectTimeout": iso_dur(PG_CONNECT_TIMEOUT_S), "pg.readTimeout": iso_dur(PG_READ_TIMEOUT_S),
                         "amount": AMOUNT},
        "spring.flyway": {"locations": plan15.FLYWAY},
        "server": {"tomcat.maxConnections": plan15.TOMCAT_MAX_CONNECTIONS},
    }


def config_check(cand, cell, cfg_path):
    """app-config.json(configprops — seat.compensation · seat.payment 접두어 포함) ↔ 기대 설정. 사유: compensation-mode-mismatch(모드) · cond-config-mismatch(그 밖) ·
    cond-config-missing(파일 없음 · 깨짐). cell은 렌더 검증용(같은 앱 설정 — 셀은 앱 설정을 바꾸지 않는다)."""
    if cand not in CANDS or cell not in CELLS:
        return 2, {"reasons": ["cond-config-missing"], "detail": [f"후보 {cand} · 셀 {cell}"]}
    try:
        cfg = json.loads(Path(cfg_path).read_text())
    except (OSError, ValueError):
        cfg = None
    if not isinstance(cfg, dict) or not isinstance(cfg.get("configprops"), list):
        return 2, {"reasons": ["cond-config-missing"], "detail": ["app-config.json 없음·깨짐"]}
    props = cfg["configprops"]
    reasons, detail = set(), []
    for prefix, kv in expected_config(cand).items():
        for key, want in kv.items():
            got = plan15._get(props, prefix, key)
            if prefix == "spring.flyway" and isinstance(got, list):
                ok = list(got) == list(want)
            elif isinstance(want, bool) or isinstance(got, bool):
                ok = got is want
            else:
                ok = plan15._norm(got) == plan15._norm(want)
            if not ok:
                reasons.add("compensation-mode-mismatch" if (prefix, key) == ("seat.compensation", "mode") else "cond-config-mismatch")
                detail.append(f"{prefix}.{key}: 기대 {want!r} · 실제 {got!r}")
    pool = cfg.get("hikaricp.connections.max")
    if not (isinstance(pool, list) and pool and isinstance(pool[0], (int, float)) and not isinstance(pool[0], bool) and pool[0] == POOL):
        reasons.add("cond-config-mismatch")
        detail.append(f"hikaricp.connections.max {pool!r} ≠ [{POOL}]")
    nf = cfg.get("app_nofile")
    if not (isinstance(nf, dict) and all(isinstance(nf.get(x), int) and nf[x] >= plan15.TOMCAT_MAX_CONNECTIONS + 1000 for x in ("soft", "hard"))):
        reasons.add("cond-config-mismatch")
        detail.append(f"app_nofile {nf!r} < {plan15.TOMCAT_MAX_CONNECTIONS + 1000}")
    return (1 if reasons else 0), {"reasons": sorted(reasons), "detail": detail}


def _total(over=None):
    rows = order()
    est = sum(render(c, cell, r, over)["est_s"] for _, r, cell, c in rows)
    est_max = sum(render(c, cell, r, over)["est_max_s"] for _, r, cell, c in rows)
    return est, est_max


# dry-run '줄이는 안'(12시간을 넘을 때 측정 전 고정에서 고를 자리 — 명세 §0 · 가정 1): 세션 수 · 생각 시간 상한 · 배수 창 상한.
#   회차 길이의 대부분은 최장 세션(지연 확정 = T + W 611초 + L)이라 세션 수(도착 구간)를 줄여도 거의 줄지 않는다 — W는 앱 눈금이라 바꾸지 않는다
REDUCTIONS = [
    ("배수 상한 600→300초", {"drainCap": 300.0}),
    ("생각 시간 상한 T 240→120초", {"tMax": 120.0}),
    ("세션 1,100→800(도착 40초)", {"n": 800}),
    ("세션 1,100→800 + T 상한 120초", {"n": 800, "tMax": 120.0}),
]


def dry_run():
    rows = order()
    problems, per = [], {}
    for _, r, cell, cand in rows:
        p = render(cand, cell, r)
        tag = f"{cand}/{cell}/rep{r}"
        if sum(p["planned"].values()) != p["n_total"]:
            problems.append(f"{tag}: 종류 배정 합 ≠ 세션 수")
        if p["seed"]["spare"] < p["planned"]["late"] + p["planned"]["noconf"]:
            problems.append(f"{tag}: 여유 좌석 {p['seed']['spare']} < 지연 확정 + 확정 안 보냄 {p['planned']['late'] + p['planned']['noconf']}(S 대체 여유)")
        if p["grace_s"] < p["life"]["max_s"]:
            problems.append(f"{tag}: gracefulStop < 세션 수명 최대")
        if p["kill"] and not all(0 < x < p["k6"]["est_s"] for x in p["kill"]["at_s"]):
            problems.append(f"{tag}: kill 시각이 k6 구간 밖")
        if r == 1:
            per[f"{cand}/{cell}"] = {"est_min": round(p["est_s"] / 60, 1), "est_max_min": round(p["est_max_s"] / 60, 1), "d_arr_s": p["d_arr_s"],
                                     "k6_est_s": p["k6"]["est_s"], "grace_s": p["grace_s"], "drain_est_s": p["drain"]["est_s"], "n_total": p["n_total"],
                                     "planned": p["planned"], "pre_vus": p["k6"]["pre_vus"], "max_vus": p["k6"]["max_vus"],
                                     "peak": p["peak_concurrency"], "seed": p["seed"], "life": p["life"]}
    # 앱 눈금과 자리표시 값의 성립 조건(세션이 계획한 갈래로 가는가)
    if not T_RANGE[1] < TTL_S - THETA_S:
        problems.append(f"T 상한 {T_RANGE[1]:g} ≥ TTL − θ — payment-start가 결제 전 거부를 낸다")
    if not PAY_MAX_S + CONF_GAP_RANGE[1] < W_S - M_S - 5:
        problems.append(f"P 상한 + 확정 간격 ≥ W − M − 5초 — 정상 확정이 PG 마감 · 연장 만료에 걸린다")
    if not PG_RULE["unknownDelayMs"] > PG_READ_TIMEOUT_S * 1000:
        problems.append("unknownDelayMs ≤ 앱 PG 읽기 타임아웃 — 결과 모름이 생기지 않는다")
    if not MOCKPG_AUTH_TTL_S > T_RANGE[1] + W_S + LATE_RANGE[1] + DRAIN_CAP_S:
        problems.append("mock-pg 승인 유효 시간 < 세션 수명 + 배수 — D 승인이 측정 중 만료된다")
    est, est_max = _total()
    if est / 3600 > BUDGET_HOURS:
        problems.append(f"예상 {est / 3600:.2f}시간 > 예산 {BUDGET_HOURS:g}시간")
    red = []
    for name, ov in REDUCTIONS:
        e, em = _total(ov)
        red.append({"name": name, "over": ov, "est_hours": round(e / 3600, 2), "est_max_hours": round(em / 3600, 2)})
    return {"n": len(rows), "est_hours": round(est / 3600, 2), "est_max_hours": round(est_max / 3600, 2), "budget_hours": BUDGET_HOURS,
            "fixed": FIXED, "per_cell": per, "reductions": red, "problems": problems}


def main(argv):
    a = argv[1:]
    if a[:1] == ["--order"]:
        print("\n".join(f"{s} {r} {c} {k}" for s, r, c, k in order()))
        return 0
    if a[:1] == ["--fixed"]:
        print("FIXED" if FIXED else "NOT-FIXED")
        return 0 if FIXED else 1
    if a[:1] == ["--render"]:
        kv = dict(zip(a[1::2], a[2::2]))
        if not {"--cand", "--cell", "--rep"} <= set(kv):
            print(__doc__, file=sys.stderr)
            return 2
        over = json.loads(kv["--over"]) if "--over" in kv else None
        if over is not None and (not isinstance(over, dict) or set(over) - OVER_KEYS):
            print(f"--over 키는 {sorted(OVER_KEYS)}만", file=sys.stderr)
            return 2
        out = render(kv["--cand"], kv["--cell"], int(kv["--rep"]), over)
        if over:
            out["over"] = over
        print(json.dumps(out, ensure_ascii=False))
        return 0
    if a[:1] == ["--session"] and len(a) == 3:
        rep, sid = int(a[1]), int(a[2])
        cyc = kind_cycle()
        print(json.dumps({"rep": rep, "sid": sid, "kind": cyc[sid % len(cyc)], **session_attrs(rep, sid)}))
        return 0
    if a[:1] == ["--dry-run"]:
        d = dry_run()
        if "--json" in a:
            print(json.dumps(d, ensure_ascii=False))
        else:
            print(f"회차 {d['n']} · 예상 {d['est_hours']}시간(배수 예상치) · 최악 {d['est_max_hours']}시간(배수 상한 {DRAIN_CAP_S:g}초) · 예산 {d['budget_hours']:g}시간"
                  f" · FIXED={d['fixed']}(+ 재측정 · 스모크 별도)")
            for k, v in d["per_cell"].items():
                print(f"  {k}: 회차 약 {v['est_min']}분(최악 {v['est_max_min']}분) · 도착 {v['d_arr_s']:g}초 · k6 약 {v['k6_est_s']}초(grace {v['grace_s']}) · "
                      f"배수 예상 {v['drain_est_s']:g}초 · 세션 {v['n_total']} {v['planned']} · 동시 최대 {v['peak']} · VU 미리 {v['pre_vus']} 최대 {v['max_vus']} · "
                      f"좌석 {v['seed']['seats']}(여유 {v['seed']['spare']}) · 수명 평균 {v['life']['mean_s']}초 최대 {v['life']['max_s']}초")
            for x in d["reductions"]:
                print(f"  줄이는 안 — {x['name']}: 예상 {x['est_hours']}시간 · 최악 {x['est_max_hours']}시간")
            for p in d["problems"]:
                print(f"  문제: {p}")
        return 1 if d["problems"] else 0
    if a[:1] == ["--config-check"] and len(a) == 4:
        rc, out = config_check(a[1], a[2], a[3])
        print(json.dumps(out, ensure_ascii=False))
        return rc
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
