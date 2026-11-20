#!/usr/bin/env python3
"""ADR-018 계획의 단일 출처(측정 명세 docs/plans/2026-11-20/adr-018-measurement · 하네스 설계 harness-design.md) —
본 셀 = 방식 4(B0 · B1 · split · one-shot) × 겹침 3(low · mid · high) × 반복 3 = 36회차 · 경계 셀(edge) = split · one-shot × 반복 3 = 6회차 ·
동시 경합 셀(burst) = split · one-shot × 반복 3 = 6회차 · 합 48회차(재합의 2026-11-20 — 하네스 설계 §8).
회전 순서 · 세션 시드 · 세션 종류 교차 배정 · 회차 렌더(앱 설정 · k6 환경 · 좌석 · 시드) · 기대 설정(configprops 대조) · dry-run.
run18.sh · campaign18.sh · outcome18.py · summarize18.py · selftest18.py가 이 파일을 읽는다(계약 = k6/ADR-018/INTERFACE.md).

    plan18.py --order                                                             → 48행 "순번 회차 셀 방식"(campaign18.sh order.txt)
    plan18.py --render --method <B0|B1|split|one-shot> --cell <low|mid|high|edge|burst> --rep k [--over '<json>']  → 회차 계획 JSON(plan.json)
    plan18.py --dry-run [--json]                                                  → 회차 시간 · VU · 좌석 · 총 시간 · 줄이는 안 — 문제 있으면 exit 1
    plan18.py --config-check <method> <cell> <app-config.json>                    → {"reasons":[…],"detail":[…]} · exit 0 일치 · 1 불일치 · 2 판정 불가
    plan18.py --fixed                                                             → exit 0 = 측정 전 고정 값(FIXED = True) · 1 = 초안 값(실제 실행 거부)
    plan18.py --session <method> <cell> <rep> <sid>                               → 그 세션의 종류 · 시드 속성 · 좌석 JSON(flow18.js와 같은 값 — 대조용)

**FIXED = True — 측정 전 고정 값(2026-11-20 · .164 스모크 smoke18-164a 뒤 · 사용자 승인).** 세션 비율 · 겹침 단계(H) · 경계 셀 앱 눈금 · p99 허용 상한 ·
배수 상한 · 첫 5초 지연 표본 제외를 이 파일 한 곳에서 고정했다(이 뒤 바꾸면 캠페인 무효 — M18-7).
본 셀 앱 눈금(TTL · W · θ · M · 배치 · 워커 · 안전망)은 앱 기본값 그대로다(ADR-017과 같다)."""
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent / "ADR-016" / "scripts"))
sys.path.insert(0, str(HERE.parent.parent / "ADR-015" / "scripts"))
import plan15  # noqa: E402
from plan16 import fnv1a, iso_dur, mulberry32  # noqa: E402  — k6 lib14.js와 같은 비트의 난수


def dur(s):
    """초 → Spring Duration 문자열. 정수 초는 '10s', 소수는 '200ms'(Spring의 단순 표기는 정수만 받는다 — 경계 셀 배치 0.2초)."""
    return f"{s:g}s" if float(s).is_integer() else f"{round(s * 1000)}ms"

FIXED = True    # 측정 전 고정(2026-11-20 · smoke18-164a 뒤 · 사용자 승인) — False면 run18.sh · campaign18.sh는 --dry-run · --simulate · --smoke만 허용

# ---- 방식 · 셀 · 반복 ----------------------------------------------------------------------------------------------------------
METHODS = ["B0", "B1", "split", "one-shot"]
GROUP_METHODS = ("split", "one-shot")          # 묶음 API(seat.hold-group.enabled=true · mode)
OVERLAPS = ["low", "mid", "high"]              # 본 셀
EDGE = "edge"                                  # 경계 셀(남은 위험 1 · M18-5)
BURST = "burst"                                # 동시 경합 셀(재합의 2026-11-20 — 하네스 설계 §8 · 명세 Q17)
CELLS = OVERLAPS + [EDGE, BURST]               # 회차 안 묶음 순서(low → mid → high → edge → burst)
EDGE_METHODS = ["split", "one-shot"]
BURST_METHODS = ["split", "one-shot"]
REPS = 3
MODE_KEY = {"split": "split", "one-shot": "one-shot"}               # seat.hold-group.mode 값(compose 환경)
MODE_ENUM = {"split": "SPLIT", "one-shot": "ONE_SHOT"}              # configprops 표기
METRIC_MODE = {"split": "split", "one-shot": "one_shot"}            # seat.group.hold.rejected{mode} 태그 값(지표 계약 §1.2)


def methods_of(cell):
    return EDGE_METHODS if cell == EDGE else (BURST_METHODS if cell == BURST else METHODS)


# ---- 앱 눈금(본 셀 = application.yml 기본값 · 경계 셀 = 짧게 — 기존 설정 키만 · 기동 검사 M ≤ θ · θ < TTL · θ < W) ------------------------
APP = {
    "main": {"ttl_s": 300.0, "batch_s": 10.0, "W_s": 611.0, "theta_s": 1.0, "M_s": 1.0},
    # 고정 — TTL 8초 · 배치 0.2초(아래 줄) · W 6초 · θ 0초 · M 0초 — θ = 0이어야 E 직전의 결제 시작이 '남은 시간 ≤ θ' 409로 다 막히지 않고 연장 문장까지 간다(메인 보정 2026-11-20)
    #   배치 0.2초(재합의 2026-11-20 — 명세 Q18 · 기존 키 seat.hold.expiry-interval · 기동 검사에 하한 없음)
    "edge": {"ttl_s": 8.0, "batch_s": 0.2, "W_s": 6.0, "theta_s": 0.0, "M_s": 0.0},
}
CONFIRM_MODE = "conditional-extend"
COMPENSATION = "refund"
COMP = {"worker": {"interval_s": 1.0, "batch": 50, "lease_s": 30.0, "max_attempts": 10, "backoff_initial_s": 1.0, "backoff_max_s": 60.0},
        "sweep": {"interval_s": 5.0, "batch": 200}}
PG_CONNECT_TIMEOUT_S, PG_READ_TIMEOUT_S, AMOUNT = 1.0, 3.0, 10_000
PG_BASE_URL_APP = "http://mock-pg:8104"
MOCKPG_AUTH_TTL = "30m"
MAX_PER_USER = 2                               # seat.hold.max-per-user(설정 무변경 — 묶음 크기 2)
POOL = plan15.POOL

# ---- 측정 전 고정 값(2026-11-20 · smoke18-164a 뒤 · 사용자 승인) ---------------------------------------------------------------------------------------------
N_SESSIONS = 1100             # 회차 세션 수(ADR-016 · 017과 같은 고정 도착률 — 명세 Q6 · 본 셀 · 동시 경합 셀)
EDGE_N_SESSIONS = 1000        # 경계 셀 세션 수(재합의 2026-11-20 — 명세 Q18)
BURST_SIZE = (2, 4)           # 고정 · 동시 경합 묶음 크기 ~ 정수 U[2, 4](묶음마다 시드)
BURST_BLOCK = 4               # 묶음 하나의 좌석 칸(s, s+1, s+2 + 빈칸 1 — 묶음끼리 좌석이 겹치지 않는다)
SEATS_PER_ROW = 50            # LoadtestDataService.SEATS_PER_ROW — 좌석 id = (줄 − 1) × 50 + 번호. 좌석 쌍 · 묶음 칸은 한 줄 안에서만(리뷰 H1-6)
BLOCKS_PER_ROW = SEATS_PER_ROW // BURST_BLOCK   # 12칸(48석) · 줄 끝 2석은 비운다
HOLD_SAMPLE_S = {"main": 2.0, "edge": 1.0}      # 부하 중 홀드 표본 주기(리뷰 H1-1 — 묶음별 홀드 수 · 만료 갈림 · 남은 재고 · 가벼운 SQL 한 문장)
HOLD_SAMPLE_GAP_FACTOR = {"main": 3.0, "edge": 5.0}   # k6 창 안 성공 표본 사이 최대 공백(창 양끝 포함) ≤ 이 배수 × 표본 주기 — 넘으면 hold-sample-missing(리뷰 H2-1 ·
#   경계 셀 5 = 5초 — 스모크 실측 최대 1.4초 대비 여유 · 메인 결정 2026-11-20)
HOLD_SAMPLE_FAIL_MAX = 0.10   # 표본 실패(err 줄 · 깨진 줄) 비율 상한 — 넘으면 hold-sample-missing(리뷰 H2-1)
APP_LOG_MATCH_TOL_MS = 200    # 경계 셀 5xx 응답 ↔ 앱 ERROR 로그 연결 허용 오차(응답 창 [ts, tr] 양쪽 · 서버 시계 — 리뷰 H2-3)
BURST_LEAD_S = 0.2            # 고정 · 묶음 송신 = 스크립트 시작 + (묶음 마지막 세션 번호 ÷ 도착률 + 이 값)초 — 마지막 세션의 반복이 시작될 여유
BURST_SPREAD_MAX_MS = 5.0     # 고정 · 묶음 안 선점 송신 시각 폭 문턱(ms)
BURST_SPREAD_BAD_FRAC = 0.10  # 고정 · 폭이 문턱을 넘는 묶음 비율이 이보다 크면 무효(burst-spread — 동시성이 성립하지 않음)
RATE = 20                     # 세션/초 → 도착 구간 55초
KINDS_MAIN = ("ok", "abandon", "release")
KINDS_EDGE = ("edge_confirm", "edge_ps")
KINDS = KINDS_MAIN + KINDS_EDGE
KIND_WEIGHTS = {"main": {"ok": 70, "abandon": 15, "release": 15}, "edge": {"edge_confirm": 1, "edge_ps": 1}}   # 고정 · 확정 70 · 이탈 15 · 해제 15(%)
T_RANGE = (30.0, 240.0)       # 생각 시간 T(선점 → 결제 시작) ~ U — ADR-017 ok 세션 분포 그대로
PAY_MEDIAN_S, PAY_SIGMA, PAY_MAX_S = 45.0, 0.94, 300.0   # 결제 시간 P(결제 시작 응답 → PG pay) ~ 로그정규 · 상한(ADR-017 그대로)
CONF_GAP_RANGE = (0.0, 2.0)   # PG 응답 → 확정 ~ U초(ADR-017 그대로)
REL_RANGE = (2.0, 5.0)        # 고정 · release 세션: 선점 응답 → 해제 ~ U초
H_FACTOR = {"low": 3.0, "mid": 0.5, "high": 0.2}   # 고정(high 0.1 → 0.2 — .164 스모크에서 0.1은 재고가 3.5초 만에 소진) · 인기 구역 크기 H = ⌈세션 수 × 배수⌉ = 시작 위치 수(seat_of — 줄 매핑: i = ⌊U × H⌋ · s = ⌊i ÷ 49⌋ × 50 + i mod 49 + 1 · 좌석 (s, s+1) 같은 줄)
EDGE_H_CELL = "low"           # 경계 셀의 겹침(설계 §4 — 교착은 겹침이 아니라 만료 배치와의 경쟁)
SEAT_PAD = 50                 # 좌석 = 줄 수 × 50 + 여유(줄 수 = ⌈H ÷ 49⌉ · burst ⌈묶음 수 ÷ 12⌉ — seed_of · 여유는 아무도 노리지 않는 좌석 · 정합 검사 대상에는 포함)
EDGE_EPS_S = 0.5              # 고정 · 경계 송신 = E + X · X ~ U[−0.5, +0.5]초
EDGE_PS_CENTER_S = 0.0        # 고정 · edge_ps 결제 시작 = 선점 만료 E + 0 ± 0.5초(EDGE_EPS_S) — 경계 셀 θ = 0이라 E 앞이면 연장 문장 · 뒤면 만료 거부 · 그 사이 만료 배치와 경합
P99_LIMIT_MS = {"hold": 40.0, "pay_start": 40.0, "confirm": 50.0}   # 고정(.164 smoke18-164a — 첫 5초 차가운 표본을 뺀 관측 최대 p99 선점 24.4 · 결제 시작 25.0 · 확정 31.5ms × 약 1.5)
WARMUP_EXCLUDE_S = 5.0        # 고정 — k6 시작부터 이 시간 안에 보낸 요청은 단계별 p50 · p99 판정 표본에서 뺀다(회차 첫 PG 등록 · JIT 차가운 지연 130~180ms · 뺀 수는 warmup_excluded로 보고)
DRAIN_CAP_S = {"main": 240.0, "edge": 120.0}   # 고정 · 본 · 동시 경합 셀 240초(메인 판단 2026-11-20 — 48회차 최악 ≤ 12시간 · 실측 배수 = summary18 drain.len_s)   # 배수 창 상한(닿으면 덤프 · 판정 — 남은 일은 G1)
DRAIN_EST_S = {"main": 30.0, "edge": 20.0}     # dry-run 손셈용 예상 배수 시간
DRAIN_POLL_S = 5.0
SEND_LAG_MAX_MS = {"main": 2000, "edge": 250}  # 고정 · 예정 송신(at) ↔ 실제 송신(ts) 허용 지연 — 넘는 요청이 있으면 env-send-lag
PC_MEM_RUN_MIN_KB = 4 * 1024 * 1024            # ADR-014 plan14 복사(ADR-017과 같다)
PC_MEM_GAP_MAX_S = 30.0

# ---- 하네스 상수(고정) ------------------------------------------------------------------------------------------------------
USER_BASE = 1_000_000         # 세션 사용자 = USER_BASE + sid
SCHEDULE = 1
GRACE_MARGIN_S = 30
OTHER_S = 160                 # 회차 고정 몫(배포 · 기동 · 예열 · 시드 · PG reset · 시작 대기 — ADR-017 OTHER_S 그대로)
PRE_VUS_HEADROOM, PRE_VUS_PAD, MAX_VUS_FACTOR = 1.15, 20, 1.5
BUDGET_HOURS = 12.0           # 명세 Q10 — 최악(배수 상한을 매 회차 다 씀) 기준
SESSION_SEED = "adr018"
PG_SEED = "adr018-pg"
CONSISTENCY_GRACE_S = {"main": 20, "edge": 3}   # /internal/consistency?graceSeconds — 배치 주기 × 2(main 10초 · edge 1초) 이상


def scale(cell):
    return "edge" if cell == EDGE else "main"


def app_of(cell):
    return APP[scale(cell)]


# ---- 세션 종류 · 속성 ------------------------------------------------------------------------------------------------------------
def kind_cycle(cell, weights=None):
    """교차 배정 한 바퀴(plan17 kind_cycle과 같은 매끈한 가중 순환) — 비율을 최대공약수로 줄인 칸 수를 고르게 흩는다."""
    w = weights or KIND_WEIGHTS[scale(cell)]
    order = [k for k in KINDS if k in w]
    g = 0
    for v in w.values():
        g = math.gcd(g, int(v))
    slots = {k: int(w[k]) // g for k in order if int(w.get(k, 0)) > 0}
    total = sum(slots.values())
    cur = dict.fromkeys(slots, 0)
    out = []
    for _ in range(total):
        for k in slots:
            cur[k] += slots[k]
        best = max(slots, key=lambda k: (cur[k], -order.index(k)))
        cur[best] -= total
        out.append(best)
    return out


def planned(n_total, cyc):
    out = dict.fromkeys(sorted(set(cyc)), 0)
    for sid in range(n_total):
        out[cyc[sid % len(cyc)]] += 1
    return out


def params(cell, over=None):
    p = {"tMin": T_RANGE[0], "tMax": T_RANGE[1], "payMedian": PAY_MEDIAN_S, "paySigma": PAY_SIGMA, "payMax": PAY_MAX_S,
         "gMin": CONF_GAP_RANGE[0], "gMax": CONF_GAP_RANGE[1], "dMin": REL_RANGE[0], "dMax": REL_RANGE[1],
         "eps": EDGE_EPS_S, "psCenter": EDGE_PS_CENTER_S}
    p.update({k: v for k, v in (over or {}).items() if k in p})
    return p


def session_attrs(rep, sid, p):
    """(회차, 세션 번호) 시드 → T · P · G · D · U · X. lib18.js sessionAttrs18과 같은 식 · 같은 순서(종류 · 방식 · 셀과 무관하게 다 뽑는다 —
    같은 회차의 방식들이 같은 값을 받는다 · 셀은 H만 다르다).
    T ~ U[tMin, tMax] · P = min(exp(ln m + σ z), payMax) · z = √(−2 ln(1 − u1)) cos(2π u2) · G ~ U[gMin, gMax] · D ~ U[dMin, dMax] · U ~ U[0, 1) · X ~ U[−eps, +eps]."""
    r = mulberry32(fnv1a(f"{SESSION_SEED}|{rep}|{sid}"))
    t = p["tMin"] + r() * (p["tMax"] - p["tMin"])
    u1, u2 = r(), r()
    z = math.sqrt(-2.0 * math.log(1.0 - u1)) * math.cos(2.0 * math.pi * u2)
    pay = min(math.exp(math.log(p["payMedian"]) + p["paySigma"] * z), p["payMax"])
    g = p["gMin"] + r() * (p["gMax"] - p["gMin"])
    d = p["dMin"] + r() * (p["dMax"] - p["dMin"])
    u = r()
    x = -p["eps"] + r() * (2 * p["eps"])
    return {"T": t, "P": pay, "G": g, "D": d, "U": u, "X": x}


_BURSTS = {}


def bursts(rep, n_total):
    """동시 경합 묶음 [(첫 세션 번호, 크기)…] — 크기 = BURST_SIZE 범위의 정수(씨앗 'adr018-burst|회차|묶음 번호' · lib18.js bursts18과 같은 식). 마지막 묶음은 세션 수에서 자른다."""
    key = (rep, n_total)
    if key not in _BURSTS:
        out, first, k = [], 0, 0
        lo, hi = BURST_SIZE
        while first < n_total:
            r = mulberry32(fnv1a(f"adr018-burst|{rep}|{k}"))
            size = min(lo + int(math.floor(r() * (hi - lo + 1))), n_total - first)
            out.append((first, size))
            first += size
            k += 1
        _BURSTS[key] = out
    return _BURSTS[key]


def burst_of(rep, n_total, sid):
    """세션 → (묶음 번호, 첫 세션, 마지막 세션)."""
    for k, (first, size) in enumerate(bursts(rep, n_total)):
        if first <= sid < first + size:
            return k, first, first + size - 1
    raise ValueError(sid)


def h_of(cell, n_total, rep=1):
    """인기 구역 크기 H = 시작 위치 수(한 줄 안의 쌍 시작 위치 — 줄마다 49개) — 경계 셀은 low의 H · 동시 경합 셀은 묶음 수(칸 수)."""
    if cell == BURST:
        return len(bursts(rep, n_total))
    c = EDGE_H_CELL if cell == EDGE else cell
    return max(1, math.ceil(n_total * H_FACTOR[c]))


def seats_for(cell, rep, n_total, sid, u, h):
    """세션 좌석 쌍. 본 · 경계 셀 = seat_of(U, H). 동시 경합 셀 = 묶음 k의 칸 b = 1 + k × BURST_BLOCK에서 (b + o, b + o + 1) · o = 0(U < 0.5) | 1 —
    한 묶음의 세션은 같은 쌍 또는 한 석 어긋난 쌍을 노리고(어느 둘이든 b + 1을 함께 노린다) 묶음끼리는 겹치지 않는다."""
    if cell == BURST:
        k, _, _ = burst_of(rep, n_total, sid)
        b = (k // BLOCKS_PER_ROW) * SEATS_PER_ROW + (k % BLOCKS_PER_ROW) * BURST_BLOCK + 1 + (0 if u < 0.5 else 1)
        return [b, b + 1]
    return seat_of(u, h)


def seat_of(u, h):
    """시작 위치 번호 i = ⌊U × H⌋ ∈ [0, H) → 줄 r = i ÷ 49 · 칸 c = i mod 49 · s = r × 50 + c + 1 · 세션 좌석 = (s, s + 1) — 두 석이 늘 같은 줄
    (회차 1 · 좌석 id = 줄 순 — LoadtestDataService.reset 한 줄 50석)."""
    i = int(math.floor(u * h))
    s = (i // (SEATS_PER_ROW - 1)) * SEATS_PER_ROW + i % (SEATS_PER_ROW - 1) + 1
    return [s, s + 1]


def same_row(seats):
    return isinstance(seats, list) and len(seats) == 2 and all(isinstance(x, int) and x >= 1 for x in seats) \
        and (seats[0] - 1) // SEATS_PER_ROW == (seats[1] - 1) // SEATS_PER_ROW


def seed_of(cell, n_total, rep=1):
    h = h_of(cell, n_total, rep)
    per = BLOCKS_PER_ROW if cell == BURST else SEATS_PER_ROW - 1
    rows = math.ceil(h / per)
    # 재고 소진 판정(holds-sample free_pairs): 노리는 쌍 시작 좌석의 최댓값과 종류(row = 줄 안 모든 시작 · burst = 칸의 첫째 · 둘째 자리만)
    if cell == BURST:
        last = (h - 1) // BLOCKS_PER_ROW * SEATS_PER_ROW + (h - 1) % BLOCKS_PER_ROW * BURST_BLOCK + 2
    else:
        last = seat_of((h - 1 + 0.5) / h, h)[0]
    return {"schedules": 1, "seats": rows * SEATS_PER_ROW + SEAT_PAD, "H": h, "rows": rows, "pad": SEAT_PAD, "section": "A",
            "pair_max_start": last, "pair_kind": "burst" if cell == BURST else "row"}


def session_life(kind, a, p, app):
    """세션 수명(초 · 첫 요청 뒤 · 응답 시간 0 근사) — VU · grace 계산용."""
    if kind == "ok":
        return a["T"] + a["P"] + a["G"]
    if kind == "release":
        return a["D"]
    if kind == "abandon":
        return 0.0
    if kind == "edge_confirm":
        return app["ttl_s"] + a["X"]
    if kind == "edge_ps":
        return app["ttl_s"] + p["psCenter"] + a["X"] + a["G"]
    raise ValueError(kind)


def schedule(cell, rep, n_total, rate, p, cyc):
    app = app_of(cell)
    ev, lives = [], []
    for sid in range(n_total):
        a = session_attrs(rep, sid, p)
        life = max(0.0, session_life(cyc[sid % len(cyc)], a, p, app))
        lives.append(life)
        t0 = (burst_of(rep, n_total, sid)[2] / rate + BURST_LEAD_S) if cell == BURST else sid / rate
        ev.append((t0, 1))
        ev.append((t0 + life + 0.05, -1))   # 0.05초 = 요청 몇 개의 응답 시간(동시 수 하한이 0이 되지 않게)
    cur = peak = 0
    for _, d in sorted(ev, key=lambda e: (e[0], e[1])):
        cur += d
        peak = max(peak, cur)
    end = max(t for t, d in ev if d == -1)
    return peak, end, lives


def grace_bound(cell, p):
    """gracefulStop 상한(세션 수명의 분포 상한 + 여유) — 본 셀 = T 상한 + P 상한 + G 상한 · 경계 셀 = TTL + |중심| + eps + G 상한."""
    if cell == EDGE:
        return app_of(cell)["ttl_s"] + abs(p["psCenter"]) + p["eps"] + p["gMax"]
    return p["tMax"] + p["payMax"] + p["gMax"] + (BURST_LEAD_S + BURST_SIZE[1] / RATE if cell == BURST else 0)


def values():
    return {"fixed": FIXED, "n_sessions": N_SESSIONS, "rate": RATE, "kind_weights": KIND_WEIGHTS, "t_range": list(T_RANGE),
            "pay_median_s": PAY_MEDIAN_S, "pay_sigma": PAY_SIGMA, "pay_max_s": PAY_MAX_S, "conf_gap_range": list(CONF_GAP_RANGE),
            "rel_range": list(REL_RANGE), "h_factor": H_FACTOR, "edge_h_cell": EDGE_H_CELL, "seat_pad": SEAT_PAD, "edge_eps_s": EDGE_EPS_S,
            "edge_ps_center_s": EDGE_PS_CENTER_S, "p99_limit_ms": P99_LIMIT_MS, "drain_cap_s": DRAIN_CAP_S, "send_lag_max_ms": SEND_LAG_MAX_MS,
            "pc_mem_run_min_kb": PC_MEM_RUN_MIN_KB, "pc_mem_gap_max_s": PC_MEM_GAP_MAX_S, "app": APP, "compensation": COMP,
            "compensation_mode": COMPENSATION, "amount": AMOUNT, "max_per_user": MAX_PER_USER, "consistency_grace_s": CONSISTENCY_GRACE_S,
            "pg_read_timeout_s": PG_READ_TIMEOUT_S, "mockpg_auth_ttl": MOCKPG_AUTH_TTL,
            "edge_n_sessions": EDGE_N_SESSIONS, "burst_size": list(BURST_SIZE), "burst_block": BURST_BLOCK, "burst_lead_s": BURST_LEAD_S,
            "burst_spread_max_ms": BURST_SPREAD_MAX_MS, "burst_spread_bad_frac": BURST_SPREAD_BAD_FRAC, "seats_per_row": SEATS_PER_ROW,
            "hold_sample_s": HOLD_SAMPLE_S, "hold_sample_gap_factor": HOLD_SAMPLE_GAP_FACTOR, "hold_sample_fail_max": HOLD_SAMPLE_FAIL_MAX,
            "app_log_match_tol_ms": APP_LOG_MATCH_TOL_MS, "warmup_exclude_s": WARMUP_EXCLUDE_S}


_RENDER_CACHE = {}
# --render --over(스모크 · 로컬 축소 회차 전용 — run18.sh가 --smoke일 때만 넘긴다 · plan.json에 "over"로 남는다)
OVER_KEYS = {"n", "rate", "drainCap", "tMin", "tMax", "payMedian", "paySigma", "payMax", "gMin", "gMax", "dMin", "dMax", "eps", "psCenter"}


def render(method, cell, rep, over=None):
    key = (method, cell, rep, json.dumps(over or {}, sort_keys=True))
    if key not in _RENDER_CACHE:
        _RENDER_CACHE[key] = json.dumps(_render(method, cell, rep, over or {}))
    return json.loads(_RENDER_CACHE[key])


def _render(method, cell, rep, over):
    if cell not in CELLS or method not in methods_of(cell) or rep not in range(1, REPS + 1):
        raise SystemExit(f"방식 {method} · 셀 {cell} · 회차 {rep}")
    n_total = int(over.get("n", EDGE_N_SESSIONS if cell == EDGE else N_SESSIONS))
    rate = float(over.get("rate", RATE))
    p = params(cell, over)
    cyc = kind_cycle(cell)
    app = app_of(cell)
    sc = scale(cell)
    d_arr = n_total / rate
    peak, end, lives = schedule(cell, rep, n_total, rate, p, cyc)
    grace = math.ceil(max(grace_bound(cell, p), max(lives)) + GRACE_MARGIN_S)
    pre = math.ceil(peak * PRE_VUS_HEADROOM) + PRE_VUS_PAD
    mx = math.ceil(pre * MAX_VUS_FACTOR)
    seed = seed_of(cell, n_total, rep)
    k6_est = max(end, d_arr) + 5
    drain_cap = float(over.get("drainCap", DRAIN_CAP_S[sc]))
    group = method in GROUP_METHODS
    app_env = {
        "CONFIRM_MODE": CONFIRM_MODE, "CONFIRM_DELAY": "0ms", "PAYMENT_WINDOW": dur(app["W_s"]), "PAYMENT_MIN_REMAINING": dur(app["theta_s"]),
        "PAY_BY_MARGIN": dur(app["M_s"]), "HOLD_TTL": dur(app["ttl_s"]), "EXPIRY_INTERVAL": dur(app["batch_s"]), "COMPENSATION_MODE": COMPENSATION,
        "PG_BASE_URL": PG_BASE_URL_APP, "MOCKPG_AUTH_TTL": MOCKPG_AUTH_TTL, "FLYWAY_LOCATIONS": ",".join(plan15.FLYWAY), "POOL_SIZE": str(POOL),
        "TOMCAT_MAX_CONNECTIONS": str(plan15.TOMCAT_MAX_CONNECTIONS), "APP_NOFILE": str(plan15.APP_NOFILE), "PG_PRELOAD": "pg_stat_statements",
        "HOLD_GROUP_ENABLED": "true" if group else "false", "HOLD_GROUP_MODE": MODE_KEY.get(method, "split"),
    }
    k6env = {
        "REP": str(rep), "METHOD": method, "CELL": cell, "RATE": f"{rate:g}", "D_ARR_S": f"{d_arr:g}", "N_TOTAL": str(n_total), "GRACE_S": str(grace),
        "KIND_CYCLE": ",".join(cyc), "T_MIN_S": f"{p['tMin']:g}", "T_MAX_S": f"{p['tMax']:g}", "PAY_MEDIAN_S": f"{p['payMedian']:g}",
        "PAY_SIGMA": f"{p['paySigma']:g}", "PAY_MAX_S": f"{p['payMax']:g}", "GAP_MIN_S": f"{p['gMin']:g}", "GAP_MAX_S": f"{p['gMax']:g}",
        "REL_MIN_S": f"{p['dMin']:g}", "REL_MAX_S": f"{p['dMax']:g}", "EDGE_EPS_S": f"{p['eps']:g}", "EDGE_PS_CENTER_S": f"{p['psCenter']:g}",
        "BURST": "1" if cell == BURST else "0", "BURST_MIN": str(BURST_SIZE[0]), "BURST_MAX": str(BURST_SIZE[1]), "BURST_BLOCK": str(BURST_BLOCK),
        "BURST_LEAD_S": f"{BURST_LEAD_S:g}",
        "H": str(seed["H"]), "SCHEDULE": str(SCHEDULE), "USER_BASE": str(USER_BASE), "PRE_VUS": str(pre), "MAX_VUS": str(mx),
    }
    return {
        "adr": "018", "method": method, "cell": cell, "overlap": EDGE_H_CELL if cell == EDGE else (None if cell == BURST else cell), "scale": sc, "group": group,
        "metric_mode": METRIC_MODE.get(method), "rep": rep, "fixed": FIXED, "values": values(),
        "n_total": n_total, "rate": rate, "d_arr_s": d_arr, "kind_cycle": cyc, "planned": planned(n_total, cyc), "params": p,
        "seed": seed, "app": app, "app_env": app_env, "grace_s": grace,
        "life": {"mean_s": round(sum(lives) / len(lives), 1), "max_s": round(max(lives), 1)}, "peak_concurrency": peak,
        "k6": {"script": "k6/ADR-018/scenarios/flow18.js", "duration_s": d_arr + grace, "est_s": round(k6_est), "pre_vus": pre, "max_vus": mx,
               "lead_s": plan15.k6_lead_s(pre), "env": k6env},
        "pg": {"base_url_app": PG_BASE_URL_APP, "faults": {"seed": fnv1a(f"{PG_SEED}|{rep}"), "rules": {}}, "op": "pay", "auth_ttl": MOCKPG_AUTH_TTL},
        "order": {"seq": next((q for q, rr, c, m in order() if (rr, c, m) == (rep, cell, method)), None), "rep_cells": cells_of_rep(rep)},
        "hold_sample_s": HOLD_SAMPLE_S[sc], "hold_sample_gap_factor": HOLD_SAMPLE_GAP_FACTOR[sc],
        "send_lag_max_ms": SEND_LAG_MAX_MS[sc], "p99_limit_ms": P99_LIMIT_MS, "consistency_grace_s": CONSISTENCY_GRACE_S[sc],
        "drain": {"cap_s": drain_cap, "poll_s": DRAIN_POLL_S, "est_s": DRAIN_EST_S[sc]},
        "est_s": round(k6_est + DRAIN_EST_S[sc] + OTHER_S), "est_max_s": round(k6_est + drain_cap + OTHER_S),
    }


def cells_of_rep(r):
    """회차 r의 셀 묶음 순서 — 겹침 단계(low · mid · high)를 회차마다 한 칸씩 회전(리뷰 H1-5 · 시간 효과와 섞이지 않게) · 그 뒤 edge · burst."""
    k = (r - 1) % len(OVERLAPS)
    return OVERLAPS[k:] + OVERLAPS[:k] + [EDGE, BURST]


def order():
    """회차 우선(rep 1 → 3) · 셀 묶음 순서 cells_of_rep(r) · 묶음 g · 회차 r의 방식 = methods_of(cell)을 (r + g) mod 개수 칸 회전 —
    한 셀 안에서 방식들이 회차마다 다른 자리를 차지한다(plan17 order()와 같은 뜻 — 시간 효과와 섞이지 않게)."""
    rows, seq = [], 0
    for r in range(1, REPS + 1):
        for cell in cells_of_rep(r):
            g = CELLS.index(cell)   # 방식 회전은 셀 고유 번호로(셀 자리가 회차마다 돌아도 방식 자리는 회차마다 다르게)
            ms = methods_of(cell)
            rot = (r + g) % len(ms)
            for m in ms[rot:] + ms[:rot]:
                seq += 1
                rows.append((seq, r, cell, m))
    return rows


def expected_config(method, cell):
    app = app_of(cell)
    w, s = COMP["worker"], COMP["sweep"]
    group = method in GROUP_METHODS
    return {
        "seat.confirm": {"mode": plan15.MODE_ENUM[CONFIRM_MODE], "loadtest.delay": "PT0S", "paymentWindow": iso_dur(app["W_s"]),
                         "paymentMinRemaining": iso_dur(app["theta_s"]), "payByMargin": iso_dur(app["M_s"])},
        "seat.hold": {"expiryMode": "SWEEP", "ttl": iso_dur(app["ttl_s"]), "expiryInterval": iso_dur(app["batch_s"]), "strategy": "PESSIMISTIC_NOWAIT",
                      "limitStrategy": "COUNTER_UPSERT_PRECHECK", "maxPerUser": MAX_PER_USER},
        "seat.hold-group": {"enabled": group, "mode": MODE_ENUM.get(method, "SPLIT")},
        "seat.compensation": {"mode": COMPENSATION.upper(), "schedulerEnabled": True,
                              "worker.interval": iso_dur(w["interval_s"]), "worker.batch": w["batch"], "worker.lease": iso_dur(w["lease_s"]),
                              "worker.maxAttempts": w["max_attempts"], "worker.backoffInitial": iso_dur(w["backoff_initial_s"]),
                              "worker.backoffMax": iso_dur(w["backoff_max_s"]), "sweep.interval": iso_dur(s["interval_s"]), "sweep.batch": s["batch"]},
        "seat.payment": {"pg.baseUrl": PG_BASE_URL_APP, "pg.connectTimeout": iso_dur(PG_CONNECT_TIMEOUT_S), "pg.readTimeout": iso_dur(PG_READ_TIMEOUT_S),
                         "amount": AMOUNT},
        "spring.flyway": {"locations": plan15.FLYWAY},
        "server": {"tomcat.maxConnections": plan15.TOMCAT_MAX_CONNECTIONS},
    }


def config_check(method, cell, cfg_path):
    """app-config.json(configprops — seat.hold-group 접두어 포함) ↔ 기대 설정. 사유: hold-group-mismatch(seat.hold-group.*) · cond-config-mismatch(그 밖) ·
    cond-config-missing(파일 없음 · 깨짐 · 방식 · 셀 이름 오류)."""
    if cell not in CELLS or method not in methods_of(cell):
        return 2, {"reasons": ["cond-config-missing"], "detail": [f"방식 {method} · 셀 {cell}"]}
    try:
        cfg = json.loads(Path(cfg_path).read_text())
    except (OSError, ValueError):
        cfg = None
    if not isinstance(cfg, dict) or not isinstance(cfg.get("configprops"), list):
        return 2, {"reasons": ["cond-config-missing"], "detail": ["app-config.json 없음·깨짐"]}
    props = cfg["configprops"]
    reasons, detail = set(), []
    for prefix, kv in expected_config(method, cell).items():
        for key, want in kv.items():
            got = plan15._get(props, prefix, key)
            if prefix == "spring.flyway" and isinstance(got, list):
                ok = list(got) == list(want)
            elif isinstance(want, bool) or isinstance(got, bool):
                ok = got is want
            else:
                ok = plan15._norm(got) == plan15._norm(want)
            if not ok:
                reasons.add("hold-group-mismatch" if prefix == "seat.hold-group" else "cond-config-mismatch")
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


def startup_problems(app):
    """앱 기동 검사(HoldStrategyStartupCheck — conditional-extend): M ≤ θ · θ < TTL · θ < W · W > 0 · 모두 ≥ 0."""
    out = []
    if not app["M_s"] <= app["theta_s"]:
        out.append("M > θ")
    if not app["theta_s"] < app["ttl_s"]:
        out.append("θ ≥ TTL")
    if not (app["W_s"] > 0 and app["theta_s"] < app["W_s"]):
        out.append("θ ≥ W 또는 W ≤ 0")
    if min(app.values()) < 0:
        out.append("음수 눈금")
    return out


def _total(over=None):
    """48회차 합(초). 줄이는 안의 drainCap은 본 셀에만(경계 셀 상한은 그대로)."""
    rows = order()
    def ov(cell):
        return {k: v for k, v in (over or {}).items() if not (cell == EDGE and k == "drainCap")} or None
    est = sum(render(m, cell, r, ov(cell))["est_s"] for _, r, cell, m in rows)
    est_max = sum(render(m, cell, r, ov(cell))["est_max_s"] for _, r, cell, m in rows)
    return est, est_max


REDUCTIONS = [
    ("본 · 동시 경합 셀 배수 상한 240→150초", {"drainCap": 150.0}),
    ("생각 시간 상한 T 240→120초", {"tMax": 120.0}),
    ("결제 시간 상한 P 300→150초", {"payMax": 150.0}),
    ("세션 1,100→800(도착 40초)", {"n": 800}),
]


def dry_run():
    rows = order()
    problems, per = [], {}
    for name, app in APP.items():
        for x in startup_problems(app):
            problems.append(f"앱 눈금({name}): {x} — 기동 검사가 거부한다")
    main, edge = APP["main"], APP["edge"]
    if not T_RANGE[1] < main["ttl_s"] - main["theta_s"]:
        problems.append(f"T 상한 {T_RANGE[1]:g} ≥ TTL − θ — 본 셀 결제 시작이 거부된다")
    if not PAY_MAX_S + CONF_GAP_RANGE[1] < main["W_s"] - main["M_s"] - 5:
        problems.append("P 상한 + 확정 간격 ≥ W − M − 5초 — 정상 확정이 PG 마감 · 연장 만료에 걸린다")
    if not REL_RANGE[1] < main["ttl_s"]:
        problems.append("해제 지연 상한 ≥ TTL — 해제 전에 만료된다")
    if not EDGE_EPS_S < edge["ttl_s"] - edge["M_s"]:
        problems.append("경계 X 폭 ≥ TTL − M — 경계 확정 세션의 결제가 PG 마감을 넘는다")
    if len(rows) != len(METHODS) * len(OVERLAPS) * REPS + (len(EDGE_METHODS) + len(BURST_METHODS)) * REPS:
        problems.append(f"순서 행 수 {len(rows)} ≠ 48")
    for _, r, cell, m in rows:
        p = render(m, cell, r)
        tag = f"{m}/{cell}/rep{r}"
        if sum(p["planned"].values()) != p["n_total"]:
            problems.append(f"{tag}: 종류 배정 합 ≠ 세션 수")
        if p["grace_s"] < p["life"]["max_s"]:
            problems.append(f"{tag}: gracefulStop < 세션 수명 최대")
        if p["seed"]["pair_max_start"] + 1 > p["seed"]["seats"]:
            problems.append(f"{tag}: 노리는 쌍의 둘째 좌석 {p['seed']['pair_max_start'] + 1} > 좌석 {p['seed']['seats']}")
        if r == 1:
            per[f"{m}/{cell}"] = {"est_min": round(p["est_s"] / 60, 1), "est_max_min": round(p["est_max_s"] / 60, 1), "d_arr_s": p["d_arr_s"],
                                  "k6_est_s": p["k6"]["est_s"], "grace_s": p["grace_s"], "drain_est_s": p["drain"]["est_s"], "drain_cap_s": p["drain"]["cap_s"],
                                  "n_total": p["n_total"], "planned": p["planned"], "pre_vus": p["k6"]["pre_vus"], "max_vus": p["k6"]["max_vus"],
                                  "peak": p["peak_concurrency"], "seed": p["seed"], "life": p["life"], "app": p["app"]}
    est, est_max = _total()
    if est_max / 3600 > BUDGET_HOURS:
        problems.append(f"최악 {est_max / 3600:.2f}시간 > 예산 {BUDGET_HOURS:g}시간(예상 {est / 3600:.2f}시간)")
    red = []
    for name, ov in REDUCTIONS:
        e, em = _total(ov)
        red.append({"name": name, "over": ov, "est_hours": round(e / 3600, 2), "est_max_hours": round(em / 3600, 2)})
    return {"n": len(rows), "est_hours": round(est / 3600, 2), "est_max_hours": round(est_max / 3600, 2), "budget_hours": BUDGET_HOURS,
            "fixed": FIXED, "per_cell": per, "reductions": red, "problems": problems}


def main(argv):
    a = argv[1:]
    if a[:1] == ["--order"]:
        print("\n".join(f"{s} {r} {c} {m}" for s, r, c, m in order()))
        return 0
    if a[:1] == ["--fixed"]:
        print("FIXED" if FIXED else "NOT-FIXED")
        return 0 if FIXED else 1
    if a[:1] == ["--render"]:
        kv = dict(zip(a[1::2], a[2::2]))
        if not {"--method", "--cell", "--rep"} <= set(kv):
            print(__doc__, file=sys.stderr)
            return 2
        over = json.loads(kv["--over"]) if "--over" in kv else None
        if over is not None and (not isinstance(over, dict) or set(over) - OVER_KEYS):
            print(f"--over 키는 {sorted(OVER_KEYS)}만", file=sys.stderr)
            return 2
        out = render(kv["--method"], kv["--cell"], int(kv["--rep"]), over)
        if over:
            out["over"] = over
        print(json.dumps(out, ensure_ascii=False))
        return 0
    if a[:1] == ["--session"] and len(a) == 5:
        method, cell, rep, sid = a[1], a[2], int(a[3]), int(a[4])
        p = render(method, cell, rep)
        at = session_attrs(rep, sid, p["params"])
        cyc = p["kind_cycle"]
        print(json.dumps({"method": method, "cell": cell, "rep": rep, "sid": sid, "kind": cyc[sid % len(cyc)], **at,
                          "seats": seats_for(cell, rep, p["n_total"], sid, at["U"], p["seed"]["H"]), "user": USER_BASE + sid}))
        return 0
    if a[:1] == ["--dry-run"]:
        d = dry_run()
        if "--json" in a:
            print(json.dumps(d, ensure_ascii=False))
        else:
            print(f"회차 {d['n']} · 예상 {d['est_hours']}시간(배수 예상치) · 최악 {d['est_max_hours']}시간(배수 상한 본 {DRAIN_CAP_S['main']:g} · 경계 {DRAIN_CAP_S['edge']:g}초)"
                  f" · 예산 {d['budget_hours']:g}시간 · FIXED={d['fixed']}(+ 재측정 · 스모크 별도)")
            for k, v in d["per_cell"].items():
                print(f"  {k}: 회차 약 {v['est_min']}분(최악 {v['est_max_min']}분) · 도착 {v['d_arr_s']:g}초 · k6 약 {v['k6_est_s']}초(grace {v['grace_s']}) · "
                      f"배수 예상 {v['drain_est_s']:g}초(상한 {v['drain_cap_s']:g}) · 세션 {v['n_total']} {v['planned']} · 동시 최대 {v['peak']} · "
                      f"VU 미리 {v['pre_vus']} 최대 {v['max_vus']} · 좌석 {v['seed']['seats']}(H {v['seed']['H']}) · 수명 평균 {v['life']['mean_s']}초 최대 {v['life']['max_s']}초")
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
