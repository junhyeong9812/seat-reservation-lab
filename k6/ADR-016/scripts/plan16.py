#!/usr/bin/env python3
"""ADR-016 계획의 단일 출처(측정 명세 §1-3 · 4 · 5 · 설계-8 · 9) — 셀 3(paid · race · cost) · 구성 · 반복 3 · 회차 순서 · 실제 눈금 시간(grace · 수렴 · 미리 만든 VU) ·
셀 렌더(앱 설정 · k6 환경 · 시드 · 판정 창) · 기대 설정(configprops 대조) · dry-run. run16.sh · campaign16.sh · summarize16.py · selftest16.py가 이 파일을 읽는다.

    plan16.py --order                                                → 21행 "순번 회차 셀 구성"(campaign16.sh order.txt)
    plan16.py --render --cell <paid|race|cost> --config <cond|cond+b|ext> --rep k   → 회차 계획 JSON
    plan16.py --dry-run [--json]                                     → 회차 시간 · VU · 좌석 수 · 렌더마다 M ≤ θ < min(TTL, W) 점검 — 문제 있으면 exit 1
    plan16.py --config-check <cell> <config> <app-config.json>       → {"reasons":[…],"detail":[…]} · exit 0 일치 · 1 불일치 · 2 판정 불가
    plan16.py --fixed                                                → exit 0 = 측정 전 고정 커밋의 값(FIXED = True) · 1 = 자리표시 값(실제 실행 거부)

**FIXED = True — 측정 전 고정 값(2026-11-09 사용자 확인).** θ · W · M · 도착률은 .164 스모크 smoke16-20261109(δ̂ p99.9 8ms · err ≤ 34.8ms → M 1초 ·
model16 --choose → θ_cond 69초 · cost 300/s는 PC 메모리로 불가 → 100/s)로 정했다(명세 §1-4 · M16-2). FIXED가 False면 run16.sh · campaign16.sh가 실제 실행을 거부한다(--dry-run · --simulate만)."""
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent.parent / "ADR-015" / "scripts"))
import model16  # noqa: E402
import outcome16 as O  # noqa: E402
import plan15  # noqa: E402

FIXED = True   # 측정 전 고정(task 07 · 사용자 확인 2026-11-09) — .164 스모크 smoke16-20261109로 값 확정

# ---- 앱 기본 눈금(명세 §1-3 '실제 눈금' — TTL 30초 상수 재사용 금지) ------------------------------------------------------------------
TTL_S = model16.TTL                    # application.yml seat.hold.ttl 5m
BATCH_S = model16.BATCH                # expiry-interval 10s
POOL = plan15.POOL
REPS = 3
T_RANGE = model16.BASE["t"]            # 생각 시간 T ~ U[30, 240]초(설계-8.2 · Q15)
D_MEDIAN, D_SIGMA = model16.BASE["m"], model16.BASE["s"]   # 결제 시간 D ~ 로그정규(m 45초 · σ 0.94 — 기준 분포)
CAP_S = model16.BASE["cap"]            # 결제 상한 CAP 10분(D > CAP = 결제 실패) — 30분은 모형 민감도(명세 §0)

# ---- 측정 전 고정 값(자리표시 — 출처 주석 · task 07에서 확정) -----------------------------------------------------------------------
M_S = 1.0             # M(pay-by-margin) — 규칙: 스모크 δ̂ + err_ms의 p99.9를 덮는 초 올림(model16.choose). 자리표시 1초 = δ̂ 미측정(choose가 None) · 정수 초 최소
THETA_COND_S = 69.0   # ①+ⓑ θ — 규칙: '결제 전 거부 + 결제 실패 ≤ 5%' 안에서 억울 최소(model16.choose theta_cond · δ 0 · 기준 분포 계산값 자리)
THETA_EXT_S = M_S     # ④ θ = M(명세 §0)
W_MARGIN_S = 0.0      # ④ W 여유의 측정 전 고정 몫(W = CAP + M + 배치 + 이 값 — DV-1)
W_S = CAP_S + M_S + BATCH_S + W_MARGIN_S   # = 611초
PAID_RATE = 20        # paid 셀 도착률(명세 §8 가정 1 '20/s')
COST_RATE = 100       # cost 셀 도착률 — 가정 1 PC 실측으로 300 또는 100(명세 Q/A 2). 자리표시 = 100
COST_RATES = (100, 300)
N_PER_FLOW = 1100     # paid 흐름당 회차 세션(DV-4 · 3회 합 3,300 — 0건이면 상한 ≈ 0.091%)
FLOW_WEIGHT, BOUNDARY_WEIGHT = 4, 1   # 교차 배정 한 바퀴 = 흐름마다 4세션 + 경계 1세션 → 경계 흐름 = 1,100 × 1/4 = 275세션(명세 '수백' 자리)
# ⓒ 경계 흐름 격자 x(ms): 확정 송신 = 하네스 마감 − x. x < 0 = 마감 뒤 — 보내지 않고 결제 실패(대조 계수)로 센다(명세 §1-3 DV-3)
BOUNDARY_GRID_MS = (3000, 2000, 1500, 1000, 700, 500, 300, 200, 100, 50, 0, -200, -1000)
RHO = {"paid": 0.5, "cost": 0.0}
BOUNDARY_MIN_FRAC = 0.8     # ⓒ 경계 판정 검출력: 격자 x > 0 각 점의(x = 0은 마감 시각 정각 송신이라 깨어나는 흔들림으로 늘 절반가량 늦다 — 스모크 S3 · 기록만) '마감 전 실제 송신' 수 ≥ 그 점 계획 수 × 이 값이어야 판정(아니면 None — 리뷰 L4-2 · 자리표시)
REHOLD_LAG_TOL_MS = 2000    # 재선점 첫 시도 지연 허용(실제 (t1 − e) − 계획 첫 오프셋) — 넘는 행이 재선점 대상의 1% 초과면 무효(리뷰 L4-3 · 자리표시)
REHOLD_LAG_MAX_FRAC = 0.01          # 재선점 비율(명세 Q/A 2 · cost = 0 — ADR-015 M15-20)
REHOLD_FROM_MS, REHOLD_STEP_MS = -1000, 1000   # 재선점 시도: 원래 만료 e − 1초부터 1초 간격(e 둘레 — 자리표시)
REHOLD_UNTIL_S = BATCH_S + 3                  # … e + 13초까지(배치 한 주기 + 여유 — ADR-015 plan15와 같은 꼴)
CLOCK_ERR_MARGIN_MS = 20   # k6의 ⓒ 마감 계산 err = k6 직전 시계 차 표본 왕복/2 + 이 값(ADR-015 EVIDENCE_MARGIN_MS 20ms 선례 · 자리표시)
COST_WINDOW_LEN_S = 300    # cost 판정 창 길이(ADR-015 cost d_arr 300초 선례 · 자리표시)
COST_HOLD_STEADY = 0.99   # cost 창 시작의 동시 홀드 수 ≥ 정상 상태의 99%(리뷰 L1-16)
COST_STEADY_TAIL = 0.01    # cost 창 시작 = 남은 수명 꼬리(∫_t^∞ S) ≤ 평균 수명의 1%가 되는 첫 t(정상 상태 — 명세 '앞 구간 버림')
GRACE_MARGIN_S = 30        # gracefulStop = 세션 수명 최대(T 최대 + max(CAP, 경계 대기)) + 여유
CONVERGE_MARGIN_S = 22     # 수렴 = max(TTL, W) + 배치 + 여유(ADR-014 CONVERGE_EXTRA_S 22초 선례)
BUDGET_HOURS = 10.0       # 캠페인 시간 예산(명세 §0 '하룻밤 약 10시간' — dry-run이 넘으면 문제로 낸다)
OTHER_S = 150              # 회차 시간 = k6 + 수렴 + 이 값(배포 · 기동 · 예열 · 시드 · 시작 대기 87초 — ADR-015 OTHER_S 200 − 수렴 52)
PRE_VUS_HEADROOM, PRE_VUS_PAD, MAX_VUS_FACTOR = 1.15, 20, 1.5   # 미리 만든 VU = 결정적 일정의 최대 동시 세션 × 1.15 + 20
SEATS_PER_SCHEDULE, SEAT_MARGIN = plan15.SEATS_PER_SCHEDULE, plan15.SEAT_MARGIN
USERS = {"main": 1_000_000, "rehold": 4_000_000}
# N16-1 (b) 허용 쓰기 문장(리뷰 L1-9 — 자리표시 · FIXED 대상): pg_stat_statements의 쓰기 문장(INSERT · UPDATE · DELETE · WITH … 쓰기)을 (동사, 표) 쌍으로 접어
#   이 목록 밖이면 G-정합 실패 자료. 지금 값 = main-app 코드의 쓰기 문장(선점 · 확정 C1/C2 · A′ 배치 · 쿼터)을 grep으로 모은 (동사, 표) — 측정 전 고정 커밋에서
#   .164 스모크 pgss 실측 문장으로 바꿔 확정한다. ④만 UPDATE seat_hold(E1 연장)를 허용한다(① = 0 — N16-1 (a)도 따로 본다).
ALLOWED_WRITES = {
    "conditional": [("insert", "seat_hold"), ("delete", "seat_hold"), ("update", "product_seat"), ("insert", "reservation"),
                    ("insert", "user_hold_quota"), ("update", "user_hold_quota"), ("delete", "user_hold_quota")],
}
# 배경 작업(카운터 대조 · 안전 점검 스케줄러 — ADR-012 · 013) 기록 표: 좌석 도메인 밖 · 부하와 무관하게 주기로 쓴다(스모크 smoke16-20261109 pgss 실측)
BACKGROUND_WRITES = [("insert", "counter_reconcile_run"), ("insert", "counter_safety_run")]
for _m in ALLOWED_WRITES:
    ALLOWED_WRITES[_m] += BACKGROUND_WRITES
# 선점 때 JPA가 내는 외래키 채우기(ProductSeat.holds — 단방향 @OneToMany @JoinColumn: INSERT 뒤 UPDATE seat_id) — 문장 단위로만 허용(스모크 S1 · 호출 수 = 선점 수).
#   ① 회차의 'UPDATE seat_hold 0'(N16-1 (a))은 이 문장을 뺀 나머지로 본다 · ④의 연장 UPDATE(SET expires_at …)는 아래 (동사, 표)로 허용
SEAT_HOLD_FK_FILL = "update seat_hold set seat_id=$1 where id=$2"
ALLOWED_WRITES["conditional-extend"] = ALLOWED_WRITES["conditional"] + [("update", "seat_hold")]

# ---- 셀 · 구성 · 흐름 ------------------------------------------------------------------------------------------------------------
CELLS = ["paid", "race", "cost"]       # 회차 안 묶음 순서(명세 '셀 순서' — paid → race → cost)
CONFIGS = {"paid": ["cond", "ext"], "race": ["cond", "ext"], "cost": ["cond", "cond+b", "ext"]}
MODE_OF = {"cond": O.MODE_COND, "cond+b": O.MODE_COND, "ext": O.MODE_EXT}
# 흐름: b = payment-start를 부르나(④는 늘) · c = ⓒ 켬 · bd = ⓒ 경계 흐름(c 켬 · 결제 시간 대신 마감 − x 격자)
#   ① 회차 경계 흐름은 ⓑ 없음(마감을 준 응답 = 선점 201) — ①+ⓒ · ①+ⓑ+ⓒ 후보가 같은 경계 결과를 쓴다(M 덮음은 응답 출처와 무관 — 둘 다 payBy = 만료 − M)
FLOWS = {
    ("paid", "cond"): {"n": (0, 0, 0), "nc": (0, 1, 0), "b": (1, 0, 0), "bc": (1, 1, 0), "nd": (0, 1, 1), "bcd": (1, 1, 1)},
    ("paid", "ext"): {"e": (1, 0, 0), "ec": (1, 1, 0), "ed": (1, 1, 1)},
    ("cost", "cond"): {"n": (0, 0, 0)},
    ("cost", "cond+b"): {"b": (1, 0, 0)},
    ("cost", "ext"): {"e": (1, 0, 0)},
}
# 후보(summarize16 ORDER) ↔ paid 흐름 · cost 구성
CANDIDATE_FLOW = {"cond": ("cond", "n"), "cond+c": ("cond", "nc"), "cond+b": ("cond", "b"), "cond+b+c": ("cond", "bc"), "ext": ("ext", "e"), "ext+c": ("ext", "ec")}
# 후보 → ⓒ 경계 흐름(리뷰 L2-10): ①+ⓒ = nd(마감 출처 = 선점 201) · ①+ⓑ+ⓒ = bcd(마감 출처 = payment-start 200) · ④+ⓒ = ed
BOUNDARY_FLOW = {"cond+c": "nd", "cond+b+c": "bcd", "ext+c": "ed"}
# N16-1 격리 탐침(리뷰 L2-9 — 경로별 증거): ① check 회차마다 예열 뒤 · 본 시드 전에 홀드 P개 → 스냅숏 → payment-start만 P회 → 스냅숏. 쓰기 증분 0이어야 통과(자리표시 P)
PROBE_HOLDS = 50
PROBE_S = 60   # 탐침 시간(초기화 + 홀드 P · payment-start P · 스냅숏 3개 · 통계 반영 대기 12초 × 3 — 리뷰 L3-3) — 회차 시간 추정에 더한다
PROBE_USER = 8_000_000
CANDIDATE_COST = {"cond": "cond", "cond+c": "cond", "cond+b": "cond+b", "cond+b+c": "cond+b", "ext": "ext", "ext+c": "ext"}
RACE_MODE = {"cond": "conditional", "ext": "conditional-extend"}


# ---- 결정적 난수(k6 lib14.js fnv1a · mulberry32와 같은 비트 — lib16.js sessionAttrs16과 같은 순서) ----------------------------------
M32 = 0xFFFFFFFF


def fnv1a(s, basis=0x811C9DC5):
    h = basis & M32
    for ch in s:
        h ^= ord(ch)
        h = (h * 0x01000193) & M32
    return h


def mulberry32(seed):
    a = seed & M32

    def nxt():
        nonlocal a
        a = (a + 0x6D2B79F5) & M32
        t = a
        t = ((t ^ (t >> 15)) * (t | 1)) & M32
        t = (t ^ ((t + (((t ^ (t >> 7)) * (t | 61)) & M32)) & M32)) & M32
        return ((t ^ (t >> 14)) & M32) / 4294967296
    return nxt


def session_attrs(rep, sid, rho, grid_n=len(BOUNDARY_GRID_MS)):
    """(회차, 세션 번호) 시드 → T(초) · D(초 — 로그정규 Box-Muller) · 재선점 여부 · 경계 격자 번호. lib16.js sessionAttrs16과 같은 식 · 같은 순서."""
    r = mulberry32(fnv1a(f"adr016|{rep}|{sid}"))
    t = T_RANGE[0] + r() * (T_RANGE[1] - T_RANGE[0])
    u1, u2 = r(), r()
    z = math.sqrt(-2.0 * math.log(1.0 - u1)) * math.cos(2.0 * math.pi * u2)
    d = math.exp(math.log(D_MEDIAN) + D_SIGMA * z)
    rehold = r() < rho
    gx = math.floor(r() * grid_n)
    return {"T": t, "D": d, "rehold": rehold, "gx": gx}


def iso_dur(s):
    """초 → Java Duration.toString() 꼴(configprops 표기 — PT1M9S · PT10M11S · PT0S · PT0.2S)."""
    if s == 0:
        return "PT0S"
    h, rem = divmod(float(s), 3600)
    m, sec = divmod(rem, 60)
    out = "PT" + (f"{int(h)}H" if h else "") + (f"{int(m)}M" if m else "")
    if sec:
        out += (f"{int(sec)}S" if float(sec).is_integer() else f"{sec:g}S")
    return out


def dur(s):
    return f"{s:g}s"


def values():
    return {"fixed": FIXED, "M_s": M_S, "theta_cond_s": THETA_COND_S, "theta_ext_s": THETA_EXT_S, "W_s": W_S, "W_margin_s": W_MARGIN_S,
            "paid_rate": PAID_RATE, "cost_rate": COST_RATE, "n_per_flow": N_PER_FLOW, "boundary_grid_ms": list(BOUNDARY_GRID_MS), "rho": RHO,
            "cap_s": CAP_S, "ttl_s": TTL_S, "batch_s": BATCH_S, "t_range": list(T_RANGE), "d_median": D_MEDIAN, "d_sigma": D_SIGMA,
            "clock_err_margin_ms": CLOCK_ERR_MARGIN_MS, "cost_window_len_s": COST_WINDOW_LEN_S, "probe_holds": PROBE_HOLDS}


def knobs_of(config):
    """서버 손잡이(구성 → θ · W · M). ① 구성(cond · cond+b)은 같은 서버 — θ = ①+ⓑ θ(ⓑ 없는 흐름은 θ를 쓰지 않음 · 명세 §0)."""
    if MODE_OF[config] == O.MODE_EXT:
        return {"theta": THETA_EXT_S, "window": W_S, "margin": M_S}
    return {"theta": THETA_COND_S, "window": 0.0, "margin": M_S}


def startup_check(config):
    """앱 기동 검사와 같은 규칙(HoldStrategyStartupCheck — 명세 §1-1 · §1-2): M ≤ θ < min(TTL, W)(① = TTL만 · ④ = θ < W · θ < TTL) · θ · M ≥ 0."""
    k = knobs_of(config)
    lim = TTL_S if MODE_OF[config] == O.MODE_COND else min(TTL_S, k["window"])
    why = []
    if k["theta"] < 0 or k["margin"] < 0:
        why.append("θ 또는 M 음수")
    if not k["margin"] <= k["theta"]:
        why.append(f"M {k['margin']:g} > θ {k['theta']:g}")
    if not k["theta"] < lim:
        why.append(f"θ {k['theta']:g} ≥ min(TTL, W) {lim:g}")
    return {"ok": not why, "why": why, "M": k["margin"], "theta": k["theta"], "limit": lim}


def flow_cycle(cell, config):
    """교차 배정 한 바퀴(세션 번호 mod 바퀴 길이 — 흐름 무게가 같으면 '세션 번호 mod 흐름 수'): 일반 흐름을 FLOW_WEIGHT번 돌고 경계 흐름 BOUNDARY_WEIGHT번."""
    fl = FLOWS[(cell, config)]
    main = [f for f, v in fl.items() if not v[2]]
    bd = [f for f, v in fl.items() if v[2]]
    if cell == "cost":
        return main
    return main * FLOW_WEIGHT + bd * BOUNDARY_WEIGHT


def planned(cell, config, n_total):
    cyc = flow_cycle(cell, config)
    out = dict.fromkeys(FLOWS[(cell, config)], 0)
    for sid in range(n_total):
        out[cyc[sid % len(cyc)]] += 1
    return out


def session_life(cell, config, flow, a):
    """하네스 세션 수명(초 · 선점 응답 뒤) — 미리 만든 VU · grace 계산용(δ = 0 · 응답 시간 0으로 본 상한 근사). 결제 전 거부 · 결제 실패는 T에서 끝난다(잠들지 않음)."""
    b, c, bd = FLOWS[(cell, config)][flow]
    mode = MODE_OF[config]
    k = knobs_of(config)
    t = a["T"]
    if bd:
        x = BOUNDARY_GRID_MS[a["gx"]] / 1000
        e, src = TTL_S, 0.0
        if b or mode == O.MODE_EXT:
            rem = TTL_S - t
            if rem <= 0 or rem <= k["theta"]:
                return t
            if mode == O.MODE_EXT and rem < k["window"]:
                e = t + k["window"]
            src = t
        dl = O.deadline(src, O.pay_within_seconds(e - k["margin"], src), e - k["margin"], 0.0)
        return t if x < 0 else max(t, dl - x)
    out = O.decide(mode=mode, b=bool(b), c=bool(c), theta=k["theta"], window=k["window"], margin=k["margin"], cap=CAP_S, ttl=TTL_S,
                   think=t, pay=a["D"], delta=0.0)
    return t + a["D"] if out in (O.SUCCESS, O.WRONGED) else t


def hold_life(cell, config, flow, a):
    """홀드 수명(초 · 선점 → 행이 사라질 때 — 리뷰 L1-16): 결제 성공 = T + D(확정이 지운다) · 그 밖(억울 · 결제 전 거부 · 결제 실패) = 만료 + 배치 한 주기 —
    ④ 연장된 홀드는 T + W까지 산다(세션 수명과 다르다 · 재선점 0인 cost 기준)."""
    b, c, _bd = FLOWS[(cell, config)][flow]
    mode, k, t = MODE_OF[config], knobs_of(config), a["T"]
    out = O.decide(mode=mode, b=bool(b), c=bool(c), theta=k["theta"], window=k["window"], margin=k["margin"], cap=CAP_S, ttl=TTL_S, think=t, pay=a["D"], delta=0.0)
    if out == O.SUCCESS:
        return t + a["D"]
    e = TTL_S
    if out != O.PRE_REJECT and mode == O.MODE_EXT and TTL_S - t < k["window"]:
        e = t + k["window"]
    return e + BATCH_S


def hold_ratio(lives, t):
    """도착 시작 t초 뒤 살아 있는 홀드 수 ÷ 정상 상태 홀드 수(도착률 × 평균 홀드 수명) = E[min(L, t)] ÷ E[L]."""
    return sum(min(x, t) for x in lives) / sum(lives)


def schedule(cell, config, rep, rate, n_total):
    """결정적 일정(도착 = 세션 번호 ÷ 도착률) → 흐름별 최대 동시 세션 · 마지막 끝 시각 · 수명 목록."""
    cyc = flow_cycle(cell, config)
    rho = RHO[cell]
    ev = {"main": [], "rehold": []}
    lives = []
    for sid in range(n_total):
        a = session_attrs(rep, sid, rho)
        t0 = sid / rate
        life = session_life(cell, config, cyc[sid % len(cyc)], a)
        lives.append(life)
        ev["main"].append((t0, t0 + life))
        ev["rehold"].append((t0, t0 + (TTL_S + REHOLD_UNTIL_S if a["rehold"] else 0.0)))
    peak, end = {}, 0.0
    for k, xs in ev.items():
        pts = sorted([(s, 1) for s, _ in xs] + [(e, -1) for _, e in xs], key=lambda p: (p[0], p[1]))
        cur = mx = 0
        for _, dlt in pts:
            cur += dlt
            mx = max(mx, cur)
        peak[k] = mx
        end = max(end, max(e for _, e in xs))
    return peak, end, lives


def steady_from(lives):
    """cost 판정 창 시작(정상 상태): 남은 수명 꼬리 E[(L − t)+] ≤ COST_STEADY_TAIL × E[L]인 첫 t(10초 올림)."""
    mean = sum(lives) / len(lives)
    t = 0
    while sum(max(0.0, x - t) for x in lives) / len(lives) > COST_STEADY_TAIL * mean:
        t += 10
    return t


def seed_of(sessions):
    n = math.ceil(sessions * SEAT_MARGIN)
    return {"schedules": max(1, math.ceil(n / SEATS_PER_SCHEDULE)), "seats": SEATS_PER_SCHEDULE, "sessions_max": n}


_RENDER_CACHE = {}


def render(cell, config, rep, cost_rate=None):
    """회차 계획(같은 인자 = 같은 값 — 결정적 일정 계산이 무거워 프로세스 안에서 기억한다 · 돌려줄 때 사본).
    cost_rate = .164 스모크 전용 cost 도착률(COST_RATES 안 · run16.sh --smoke만 넘긴다 — 가정 1 '300/s 우선' 실측) · None = COST_RATE."""
    if cost_rate is not None and cost_rate not in COST_RATES:
        raise SystemExit(f"cost 도착률 {cost_rate} ∉ {COST_RATES}")
    rate = COST_RATE if cost_rate is None else cost_rate
    key = (cell, config, rep, FIXED, M_S, THETA_COND_S, THETA_EXT_S, W_S, rate)
    if key not in _RENDER_CACHE:
        _RENDER_CACHE[key] = json.dumps(_render(cell, config, rep, rate))
    return json.loads(_RENDER_CACHE[key])


def _render(cell, config, rep, cost_rate=COST_RATE):
    if cell not in CELLS or config not in CONFIGS.get(cell, []) or rep not in range(1, REPS + 1):
        raise SystemExit(f"셀 {cell} · 구성 {config} · 회차 {rep}")
    if cell == "race":   # ADR-015 race 셀 그대로(plan15 값 · TTL 30초 — 명세 §1-3) — ① · ④만
        p = plan15.render(RACE_MODE[config], "race", rep)
        return {**p, "adr": "015", "cell16": "race", "config": config, "fixed": FIXED}
    mode = MODE_OF[config]
    k = knobs_of(config)
    ext = mode == O.MODE_EXT
    rate = PAID_RATE if cell == "paid" else cost_rate
    flows = FLOWS[(cell, config)]
    cyc = flow_cycle(cell, config)
    if cell == "paid":
        n_total = N_PER_FLOW // FLOW_WEIGHT * len(cyc)
        d_arr = n_total / rate
        window = {"kind": "arrival", "from_s": 0, "len_s": d_arr}
    else:
        attrs = [session_attrs(rep, sid, RHO[cell]) for sid in range(20000)]
        probe = [session_life(cell, config, cyc[0], a) for a in attrs]
        hl = [hold_life(cell, config, cyc[0], a) for a in attrs]
        # 창 시작 = 세션 수명(VU) 기준 정상 상태와 홀드 수명 기준 99% 도달(리뷰 L1-16 · 메인 결정 2026-11-08) 중 늦은 쪽
        from_s = max(steady_from(probe), next(t for t in range(0, 4000, 10) if hold_ratio(hl, t) >= COST_HOLD_STEADY))
        d_arr = from_s + COST_WINDOW_LEN_S
        n_total = round(d_arr * rate)
        # 홀드 수명 기준 정상 상태 도달 비율(리뷰 L1-16 — 창 시작 · 끝에서 동시 홀드 수가 정상 상태의 몇 %인가 · 창을 늘릴지는 측정 전 고정 때 정한다)
        window = {"kind": "steady", "from_s": from_s, "len_s": COST_WINDOW_LEN_S,
                  "hold_ratio": {"from": round(hold_ratio(hl, from_s), 4), "end": round(hold_ratio(hl, from_s + COST_WINDOW_LEN_S), 4),
                                 "mean_hold_s": round(sum(hl) / len(hl), 1), "max_hold_s": round(max(hl), 1),
                                 "from_for_99": next(t for t in range(0, 4000, 10) if hold_ratio(hl, t) >= 0.99)}}
    peak, end, lives = schedule(cell, config, rep, rate, n_total)
    bd_wait = max((W_S if ext else TTL_S) - M_S, CAP_S)
    grace = math.ceil(max(T_RANGE[1] + max(CAP_S, bd_wait), max(lives)) + GRACE_MARGIN_S)
    pre = {f: math.ceil(v * PRE_VUS_HEADROOM) + PRE_VUS_PAD for f, v in peak.items()}
    mx = {f: math.ceil(v * MAX_VUS_FACTOR) for f, v in pre.items()}
    seed = seed_of(n_total)
    converge = max(TTL_S, W_S if ext else 0.0) + BATCH_S + CONVERGE_MARGIN_S
    app_env = {
        "CONFIRM_MODE": mode, "CONFIRM_DELAY": "0ms", "PAYMENT_WINDOW": dur(k["window"]) if ext else "0s",
        "PAYMENT_MIN_REMAINING": dur(k["theta"]), "PAY_BY_MARGIN": dur(k["margin"]),
        "HOLD_TTL": dur(TTL_S), "EXPIRY_INTERVAL": dur(BATCH_S), "FLYWAY_LOCATIONS": ",".join(plan15.FLYWAY), "POOL_SIZE": str(POOL),
        "TOMCAT_MAX_CONNECTIONS": str(plan15.TOMCAT_MAX_CONNECTIONS), "APP_NOFILE": str(plan15.APP_NOFILE), "PG_PRELOAD": "pg_stat_statements",
    }
    k6env = {
        "REP": str(rep), "CELL": cell, "MODE": mode, "RATE": str(rate), "D_ARR_S": f"{d_arr:g}", "GRACE_S": str(grace), "TTL_S": f"{TTL_S:g}",
        "T_MIN_S": f"{T_RANGE[0]:g}", "T_MAX_S": f"{T_RANGE[1]:g}", "D_MEDIAN_S": f"{D_MEDIAN:g}", "D_SIGMA": f"{D_SIGMA:g}", "CAP_S": f"{CAP_S:g}",
        "RHO": f"{RHO[cell]:g}", "FLOWS": json.dumps({f: {"b": v[0], "c": v[1], "bd": v[2]} for f, v in flows.items()}, separators=(",", ":")),
        "FLOW_CYCLE": ",".join(cyc), "GRID_MS": ",".join(str(x) for x in BOUNDARY_GRID_MS),
        "REHOLD_FROM_MS": str(REHOLD_FROM_MS), "REHOLD_STEP_MS": str(REHOLD_STEP_MS), "REHOLD_UNTIL_S": f"{REHOLD_UNTIL_S:g}",
        "CLOCK_ERR_MARGIN_MS": str(CLOCK_ERR_MARGIN_MS), "SEATS_PER_SCHEDULE": str(SEATS_PER_SCHEDULE), "SCHEDULES": str(seed["schedules"]),
        "USER_MAIN": str(USERS["main"]), "USER_REHOLD": str(USERS["rehold"]),
        "PRE_VUS_MAIN": str(pre["main"]), "MAX_VUS_MAIN": str(mx["main"]), "PRE_VUS_REHOLD": str(pre["rehold"]), "MAX_VUS_REHOLD": str(mx["rehold"]),
    }
    pre_total = pre["main"] + pre["rehold"]
    k6_est = max(end, d_arr) + 5
    return {
        "adr": "016", "cell": cell, "config": config, "mode": mode, "mode_enum": plan15.MODE_ENUM[mode], "rep": rep, "fixed": FIXED, "values": values(),
        "knobs16": k, "startup": startup_check(config), "ttl_s": TTL_S, "expiry_interval_s": BATCH_S, "cap_s": CAP_S, "rate": rate, "d_arr_s": d_arr,
        "n_total": n_total, "planned": planned(cell, config, n_total), "flows": {f: {"b": v[0], "c": v[1], "bd": v[2]} for f, v in flows.items()},
        "flow_cycle": cyc, "flyway_locations": plan15.FLYWAY, "pool": POOL, "pgss": 1, "seed": seed, "app_env": app_env, "grace_s": grace, "rho": RHO[cell],
        "life": {"mean_s": round(sum(lives) / len(lives), 1), "max_s": round(max(lives), 1)}, "peak_concurrency": peak,
        "k6": {"script": "k6/ADR-016/scenarios/paid-flow.js", "duration_s": d_arr + grace, "est_s": round(k6_est), "pre_vus": pre, "max_vus": mx,
               "pre_vus_total": pre_total, "lead_s": plan15.k6_lead_s(pre_total), "env": k6env},
        "window": window, "converge_s": converge, "est_s": round(k6_est + converge + OTHER_S + (PROBE_S if mode == O.MODE_COND else 0)),
        "probe16": {"holds": PROBE_HOLDS if mode == O.MODE_COND else 0, "user_base": PROBE_USER, "schedule": 1},
    }


def order():
    """회차 우선 · 묶음 순서 CELLS(paid → race → cost) · 묶음 g · 회차 r의 구성 = CONFIGS를 (r + g) mod n칸 회전(ADR-015 order()와 같은 뜻).
    race는 plan15.order()의 race 회전에서 ① · ④만 그 순서대로 뽑는다(명세 §1-3 '두 모드만 뽑고')."""
    rows, seq = [], 0
    inv = {v: k for k, v in RACE_MODE.items()}
    for r in range(1, REPS + 1):
        for g, cell in enumerate(CELLS):
            if cell == "race":
                cfgs = [inv[m] for _, rr, c, m in plan15.order() if rr == r and c == "race" and m in inv]
            else:
                cs = CONFIGS[cell]
                rot = (r + g) % len(cs)
                cfgs = cs[rot:] + cs[:rot]
            for cfg in cfgs:
                seq += 1
                rows.append((seq, r, cell, cfg))
    return rows


def expected_config(cell, config):
    if cell == "race":
        return plan15.expected_config(RACE_MODE[config], "race")
    mode = MODE_OF[config]
    k = knobs_of(config)
    return {
        "seat.confirm": {"mode": plan15.MODE_ENUM[mode], "loadtest.delay": "PT0S", "paymentWindow": iso_dur(k["window"]) if mode == O.MODE_EXT else "PT0S",
                         "paymentMinRemaining": iso_dur(k["theta"]), "payByMargin": iso_dur(k["margin"])},
        "seat.hold": {"expiryMode": "SWEEP", "ttl": iso_dur(TTL_S), "expiryInterval": iso_dur(BATCH_S), "strategy": "PESSIMISTIC_NOWAIT",
                      "limitStrategy": "COUNTER_UPSERT_PRECHECK"},
        "spring.flyway": {"locations": plan15.FLYWAY},
        "server": {"tomcat.maxConnections": plan15.TOMCAT_MAX_CONNECTIONS},
    }


def config_check(cell, config, cfg_path):
    """plan15.config_check와 같은 방식 — paymentMinRemaining(θ) · payByMargin(M) · paymentWindow(W) · ttl · 풀 · nofile 대조."""
    if cell == "race":
        return plan15.config_check(RACE_MODE[config], "race", cfg_path)
    cfg = json.loads(Path(cfg_path).read_text()) if Path(cfg_path).exists() else None
    if not isinstance(cfg, dict) or not isinstance(cfg.get("configprops"), list):
        return 2, {"reasons": ["cond-config-missing"], "detail": ["app-config.json 없음·깨짐"]}
    props = cfg["configprops"]
    reasons, detail = set(), []
    for prefix, kv in expected_config(cell, config).items():
        for key, want in kv.items():
            got = plan15._get(props, prefix, key)
            ok = list(got) == list(want) if prefix == "spring.flyway" and isinstance(got, list) else (
                plan15._norm(got) == plan15._norm(want) or (isinstance(want, int) and got == want))
            if not ok:
                reasons.add("confirm-mode-mismatch" if (prefix, key) == ("seat.confirm", "mode") else "cond-config-mismatch")
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


def dry_run():
    rows = order()
    problems, cells = [], {}
    for cell in CELLS:
        for cfg in CONFIGS[cell]:
            for rep in range(1, REPS + 1):
                p = render(cell, cfg, rep)
                cells[f"{cell}/{cfg}/rep{rep}"] = p
                if cell == "race":
                    continue
                if not p["startup"]["ok"]:
                    problems.append(f"{cell}/{cfg}/rep{rep}: 기동 검사 M ≤ θ < min(TTL, W) 위반 — {' · '.join(p['startup']['why'])}")
                if p["seed"]["sessions_max"] > p["seed"]["schedules"] * SEATS_PER_SCHEDULE:
                    problems.append(f"{cell}/{cfg}/rep{rep}: 좌석 부족")
                if sum(p["planned"].values()) != p["n_total"]:
                    problems.append(f"{cell}/{cfg}/rep{rep}: 흐름 배정 합 ≠ 세션 수")
                if cell == "paid" and any(v != N_PER_FLOW for f, v in p["planned"].items() if not p["flows"][f]["bd"]):
                    problems.append(f"paid/{cfg}/rep{rep}: 흐름당 세션 ≠ {N_PER_FLOW}")
                if p["grace_s"] < p["life"]["max_s"]:
                    problems.append(f"{cell}/{cfg}/rep{rep}: gracefulStop < 세션 수명 최대")
                if cell == "cost" and p["window"]["from_s"] + p["window"]["len_s"] > p["d_arr_s"]:
                    problems.append(f"cost/{cfg}/rep{rep}: 판정 창이 도착 구간 밖")
    if COST_RATE not in COST_RATES:
        problems.append(f"cost 도착률 {COST_RATE} ∉ {COST_RATES}")
    est = sum(cells[f"{c}/{g}/rep{r}"]["est_s"] for _, r, c, g in rows)
    if est / 3600 > BUDGET_HOURS:   # 명세 §1-5 하룻밤 예산(리뷰 L2-4) — 넘으면 고정 전에 줄이는 안을 보고
        problems.append(f"예상 {est / 3600:.2f}시간 > 예산 {BUDGET_HOURS}시간")
    per = {}
    for _, r, c, g in rows:
        if r == 1:
            p = cells[f"{c}/{g}/rep1"]
            per[f"{c}/{g}"] = {"est_min": round(p["est_s"] / 60, 1), "d_arr_s": p["d_arr_s"], "grace_s": p["grace_s"], "converge_s": p["converge_s"],
                               "k6_est_s": p["k6"].get("est_s", p["k6"]["duration_s"]), "n_total": p.get("n_total", p["seed"]["sessions_max"]),
                               "pre_vus": p["k6"]["pre_vus"], "max_vus": p["k6"]["max_vus"], "seed": p["seed"],
                               "startup": p.get("startup"), "window": p["window"], "life": p.get("life")}
    return {"n": len(rows), "est_hours": round(est / 3600, 2), "fixed": FIXED, "per_cell": per, "problems": problems}


def main(argv):
    a = argv[1:]
    if a[:1] == ["--order"]:
        print("\n".join(f"{s} {r} {c} {g}" for s, r, c, g in order()))
        return 0
    if a[:1] == ["--fixed"]:
        print("FIXED" if FIXED else "NOT-FIXED")
        return 0 if FIXED else 1
    if a[:1] == ["--render"]:
        kv = dict(zip(a[1::2], a[2::2]))
        if not {"--cell", "--config", "--rep"} <= set(kv):
            print(__doc__, file=sys.stderr)
            return 2
        cr = int(kv["--cost-rate"]) if "--cost-rate" in kv else None
        print(json.dumps(render(kv["--cell"], kv["--config"], int(kv["--rep"]), cr), ensure_ascii=False))
        return 0
    if a[:1] == ["--dry-run"]:
        d = dry_run()
        if "--json" in a:
            print(json.dumps(d, ensure_ascii=False))
        else:
            print(f"회차 {d['n']} · 예상 {d['est_hours']}시간(+ 재측정 · 스모크) · FIXED={d['fixed']}")
            for k, v in d["per_cell"].items():
                st = v["startup"]
                print(f"  {k}: 회차 약 {v['est_min']}분 · 도착 {v['d_arr_s']:g}초 · k6 약 {v['k6_est_s']}초 · grace {v['grace_s']}초 · 수렴 {v['converge_s']:g}초 · "
                      f"세션 {v['n_total']} · VU 미리 {v['pre_vus']} 최대 {v['max_vus']} · 시드 {v['seed']['schedules']}×{v['seed']['seats']}"
                      + (f" · 기동 검사 M {st['M']:g} ≤ θ {st['theta']:g} < {st['limit']:g} {'통과' if st['ok'] else '위반'}" if st else "")
                      + (f" · 창 {v['window']['kind']} {v['window']['from_s']:g}+{v['window']['len_s']:g}초 · 홀드 정상 상태 도달 창 시작 "
                         f"{v['window']['hold_ratio']['from']:.1%} 끝 {v['window']['hold_ratio']['end']:.1%}(99% = {v['window']['hold_ratio']['from_for_99']}초 · "
                         f"홀드 수명 평균 {v['window']['hold_ratio']['mean_hold_s']}초)" if k.startswith("cost") else "")
                      + (f" · 수명 평균 {v['life']['mean_s']}초 최대 {v['life']['max_s']}초" if v.get("life") else ""))
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
