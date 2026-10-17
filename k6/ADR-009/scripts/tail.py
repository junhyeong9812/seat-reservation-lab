#!/usr/bin/env python3
"""ADR-011 꼬리 지연 판정 지표(ADR-011 §4 가설 T1~T5) — 회차별·조건별 값만 낸다(성립·기각 판정은 메인 몫).

    scripts/tail.py --set k6ver|pretouch|soak|soakrc results/<campaign-id>   → <campaign-id>/TAIL.md · tail.json
                                                                       (먼저 조건마다 summarize.py — 안정성 4항목·메모리는 summary.json을 쓴다)
    scripts/tail.py --rep <회차폴더>                                    → 회차 하나의 지표 JSON(표준 출력 — 스모크용, summary.json 없이 되는 것만)
    scripts/tail.py --probe-check <회차폴더>                            → probe-check.json · exit 0 = 두 측정기 정상(run.sh rep_status probe-missing)

정의(출처 — 바꾸면 ADR-011 §4와 함께)
  - 시각: 주 k6 CSV 행 시각 = PC 시계 정수 초. 측정기 CSV = ms(K6_CSV_TIME_FORMAT=unix_milli) — PC 측정기는 PC 시계, 서버 측정기는 서버 시계 → PC 시계 = 서버 − 시계 차
    (clock-offset.json k6 앞·뒤 평균 — summarize.clock_off와 같다). Go GC(k6-gctrace.log) = 줄을 받은 순간의 PC epoch(run.sh gctrace_split). 겹침은 ±1초(아래 '겹침').
  - 램프 끝 t0 = k6-summary setup_data.startedAt ÷ 1000 + ramp_s. 정상 구간 초 = 주 k6 행 시각 s ≥ t0(정수 초 — ADR-010 log '느린 초 대조'와 같다) ~ 마지막 행 초.
  - 느린 초(ADR-010 log '느린 초 대조'): 정상 구간 초 중 hold 요청(코드 0·빈 코드 제외)의 최대 응답(http_req_duration) > 50ms인 초.
  - 26초 성분(T1): x_s = log(1 + 그 초의 '10ms 넘는 요청 수'(코드 0 제외)) — 정상 구간 모든 초(행 없는 초 = 0), 평균을 뺀 주기도 |Σ x_s e^(−2πi s/P)|² ÷ N,
    P = 24~31초(0.05초 간격). 통계량 = 그 대역 최대. 귀무 = 스펙트럼을 보존하는 대리 계열 PERMS(1000)개(고정 시드) — 기본 ar: x에 AR(4)(Yule-Walker)를 맞춘
    이론 스펙트럼을 갖는 복소 정규 푸리에 계수(진폭·위상 모두 무작위) → 역변환. p = (대리 계열 대역 최대 ≥ 관측 수 + 1) ÷ (PERMS + 1).
    status: ok · no-component(10ms 넘는 요청 0건 — '성분 없음') · short(초 < 대역 2주기 — '자료 부족'). 다른 귀무(비교용, TAIL_NULL): smooth(주기도 이동 중앙값 ÷ ln 2) ·
    phase(순수 위상 무작위 — 진폭을 그대로 두므로 주기도가 관측과 같아 이 통계량에 힘이 없다) · perm(무작위 재배열 — 옛 정의, 상관 있는 잡음에 거짓 양성).
    검정 확인(2026-10-16, 기존 회차 — Serial Full GC 초 ±1초를 중앙값으로 가림): v2.2.0 20회 거짓 양성 0/20 · v2.3.0 24회 검출 23/24 ·
    AR(1) 합성 N=360 φ=0.5 4.8%·φ=0.7 4.5%(400개씩). 가리지 않으면 v2.2.0 4/20 — Serial Full GC(약 47초 간격)의 배음이 대역에 든다.
    ADR-011 실험은 ZGC(Full GC 없음)라 가리지 않는다 — Full GC가 있는 수집기에 쓰면 그 초를 먼저 가려야 한다.
  - 지연 분해: 느린 초마다 그 초 최대 응답 요청 하나의 waiting(서버 응답 대기) 대 클라이언트 구간 = blocked + sending + receiving.
    서버형 느린 초 = waiting > 클라이언트 구간(waiting 지배). 중앙값은 느린 초들의 값.
    주기 없는 서버형(T4) = 서버형 중 26초 성분 봉우리(검정의 최대 주기 P·위상: 봉우리 = 정상 구간 첫 초 + 위상 + kP)의 ±1.5초(TAIL_PERIOD_TOL_S)에 들지 않는 초.
    성분이 유의하지 않으면(p > 0.05) 전부 비주기.
  - 겹침(T2·T3 공통): 사건 = PC 시계 구간 [시작, 끝]. 느린 초 s(= [s, s+1))의 ±1초 창 [s−1, s+2)와 사건 구간이 겹치면 겹침.
    우연 비율 = 정상 구간 초 중 그런 초의 비율 · 우연 기대 = 느린 초 수 × 우연 비율 · 배수 = 겹침 ÷ 기대 · 최대 배수 = 1 ÷ 우연 비율(느린 초가 전부 겹쳐도
    넘을 수 없는 값). 최대 배수 < 2(가설의 기준 배수)면 undecidable — 그 회차로는 판정 불가('판정 불가', 조건 묶음 합에서 뺀다).
  - 측정기 튐(T2·T4): 평소 p99 = 정상 구간(t0 ~ 주 k6 끝) 측정기 응답(코드 200)의 p99. 튐 = 응답 > max(평소 p99 × 3, 절대 하한 20ms(TAIL_SPIKE_FLOOR_MS)) 또는 코드 ≠ 200.
    사건 구간 = [응답 끝 − 응답 시간, 응답 끝](행 시각 = 응답 끝).
  - Go GC(T3): gctrace 'a+b+c ms clock'(a·c = 정지 두 번, b = 동시 표시). wall = a+b+c · STW = a+c. 사건 = STW ≥ 5ms(TAIL_GC_STW_MS) 또는 wall ≥ 100ms(TAIL_GC_WALL_MS)인 바퀴.
    줄 시각 = GC가 끝난 시각 → 구간 [끝 − wall, 끝]. 기본값 근거: 스모크 smoke-adr011-probes(v2.3.0, 4,200/s 430초 GC 353바퀴 — 약 1.2초마다)에서 모든 바퀴를 세면
    우연 비율 1.00(최대 배수 1 — 판정 불가). STW 분포 p50 0.48 · p90 5.35 · p99 33.5ms, wall p50 43 · p90 253ms → 이 기준의 사건 51개 · 우연 비율 0.31 · 최대 배수 3.2
    (기준은 우연 비율·최대 배수로 정했다 — 겹침 수는 보지 않음). 모든 바퀴 기준 겹침은 overlap_all로 함께 남긴다.
  - 장시간(T5): 1분 p99 = 정상 구간을 t0의 올림부터 60초씩 나눈 분마다 hold 응답(코드 0 제외) p99(정렬 값[int(n × 0.99)] — summarize와 같다).
    첫 5분 = 앞 5개 분 p99의 중앙값, 마지막 5분 = 끝 5개 완전한 분의 중앙값, 증가 % = 마지막 ÷ 첫 − 1(분이 10개 미만이면 없음). 단조 경향 = 분 번호와 분 p99의 순위 상관(스피어만).
    안정성 4항목(ADR-010 §9) = compare.jvm_rep 값 그대로: ① 정상 구간 Full GC 0·앱 스레드 멈춤 0 ② GC 정지 최대 < 10ms ③ 에러율 0·코드 0 비율 0·성공 RPS ÷ 4,200 > 0.995
    ④ 앱 메모리 램프 뒤 첫 1분 → 마지막 1분 < 10%. 앱 메모리 최대 ÷ 한도 = docker stats 최대 MiB ÷ (app-jvm.json mem_bytes ÷ 1 MiB).
  - 조건 묶음 값 = ok 회차만(겹침·기대는 합한 값으로 배수 — 회차별 값은 회차 표). 손으로 채우지 않는다 — 없으면 '누락'.
측정기 정상(--probe-check — 두 측정기 모두): 기동·종료 코드(PC 0·105 · 강제 종료 아님 / 서버 기동 0 · 종료 0·105 · 수거 0) · CSV gzip 무결 ·
  창(램프 끝 t0 ~ 주 k6 끝) 안 응답만으로 — 공백 ≤ 10초(창 시작 → 첫 응답 · 응답 사이 · 마지막 응답 → 창 끝 모두) · 건수 ≥ 0.9 × 도착률 × 창 길이 · 코드 200 아닌 비율 ≤ 1%.
"""
import gzip
import json
import math
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SLOW_MS = 50
N10_MS = 10
BAND = (24.0, 31.0, 0.05)
PERMS = 1000
SEED = 20261016
SPIKE_X = 3
PROBE_MIN_SHARE, PROBE_MAX_GAP_S, PROBE_BAD_MAX = 0.9, 10, 0.01
# 손잡이(환경변수 — 쓴 값은 회차 결과 knobs에 남는다). 기본값 근거는 머리말 '기본값'
_env = lambda k, d: type(d)(__import__("os").environ.get(k, d))
NULL = _env("TAIL_NULL", "ar")                  # 26초 성분 귀무: ar(기본) | smooth | phase | perm — 근거 머리말
AR_ORDER = _env("TAIL_AR_ORDER", 4)             # ar 귀무의 차수
SMOOTH_HZ = _env("TAIL_SMOOTH_HZ", 0.03)        # smooth 귀무의 이동 중앙값 폭(± 주파수 Hz)
P_SIG = 0.05                                     # 26초 성분 유의(T1) — 주기 초 분리(T4)도 이 기준
PERIOD_TOL_S = _env("TAIL_PERIOD_TOL_S", 1.5)   # 주기 초 = 성분 봉우리 ± 이 초
SPIKE_FLOOR_MS = _env("TAIL_SPIKE_FLOOR_MS", 20.0)   # 측정기 튐 절대 하한(T2·T4)
GC_STW_MS = _env("TAIL_GC_STW_MS", 5.0)          # Go GC 사건(T3): STW(a+c) ≥ 이 값 …
GC_WALL_MS = _env("TAIL_GC_WALL_MS", 100.0)      # … 또는 wall(a+b+c) ≥ 이 값
LIFT_DECIDE = 2.0                                # T2·T3 배수 기준 — 최대 배수가 이보다 작으면 undecidable


def load(p):
    try:
        return json.loads(Path(p).read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def p99(xs):
    xs = sorted(xs)
    return xs[int(len(xs) * 0.99)] if xs else None


def clock_offset(d):
    c = load(d / "clock-offset.json") or {}
    xs = [x["offset_s"] for x in (c.get("before"), c.get("after")) if isinstance(x, dict) and num(x.get("offset_s"))]
    return sum(xs) / len(xs) if xs else None


def ramp_end(d):
    ks = load(d / "k6-summary.json") or {}
    st = (ks.get("setup_data") or {}).get("startedAt")
    return st / 1000 + (ks.get("ramp_s") or 0) if num(st) else None


# ---- 원시 읽기 ----------------------------------------------------------------------------------------
def main_csv(d, t0):
    """주 k6 CSV(hold) → 초별 {n, n10, mx, comp(최대 응답 요청의 waiting·blocked·sending·receiving)} · 분별 응답 목록(코드 0 제외, s ≥ t0).
    한 요청의 행은 http_req_duration 다음에 blocked·connecting·tls·sending·waiting·receiving이 붙어 온다(k6 CSV — 요청 한 묶음)."""
    from array import array
    per, mins, last = {}, {}, None
    s_min = math.ceil(t0)
    comp_idx = {"http_req_blocked": 0, "http_req_sending": 1, "http_req_waiting": 2, "http_req_receiving": 3}
    with gzip.open(d / "k6-requests.csv.gz", "rt", newline="") as f:
        head = f.readline().rstrip("\n").split(",")
        i_ts, i_val, i_name, i_st = (head.index(k) for k in ("timestamp", "metric_value", "name", "status"))
        cur = None   # [초, 응답, b, snd, w, rcv]
        def close(c):
            s, dur = c[0], c[1]
            e = per.get(s)
            if e is None:
                e = per[s] = {"n": 0, "n10": 0, "mx": -1.0, "comp": None}
            e["n"] += 1
            if dur > N10_MS:
                e["n10"] += 1
            if dur > e["mx"]:
                e["mx"] = dur
                e["comp"] = (c[4], c[2], c[3], c[5])   # waiting, blocked, sending, receiving
            mins.setdefault((s - s_min) // 60, array("f")).append(dur)
        for line in f:
            if not line.startswith("http_req_"):
                continue
            c = line.split(",", i_st + 1)
            m = c[0]
            if m == "http_req_duration":
                if cur is not None:
                    close(cur)
                cur = None
                if c[i_name] != "hold" or c[i_st] in ("0", ""):
                    continue
                s = int(c[i_ts])
                last = s if last is None or s > last else last
                if s < t0:
                    continue
                cur = [s, float(c[i_val]), 0.0, 0.0, 0.0, 0.0]
            elif cur is not None and m in comp_idx:
                cur[2 + comp_idx[m]] = float(c[i_val])
        if cur is not None:
            close(cur)
    return per, mins, last


def probe_csv(path, shift=0.0):
    """측정기 CSV(ping) → [(PC 시각 초(float), 응답 ms, 코드)] 시각 순. 시각 ms − shift(초)."""
    out = []
    with gzip.open(path, "rt", newline="") as f:
        head = f.readline().rstrip("\n").split(",")
        i_ts, i_val, i_name, i_st = (head.index(k) for k in ("timestamp", "metric_value", "name", "status"))
        for line in f:
            if not line.startswith("http_req_duration,"):
                continue
            c = line.split(",", i_st + 1)
            if c[i_name] != "ping":
                continue
            out.append((int(c[i_ts]) / 1000 - shift, float(c[i_val]), c[i_st]))
    out.sort()
    return out


def gctrace(path):
    """k6-gctrace.log.gz → [(끝 PC epoch, wall ms = a+b+c, STW ms = a+c)] — 'gc 12 @3.4s 1%: a+b+c ms clock, …'(a·c = 정지 두 번, b = 동시 표시).
    줄 시각 = 줄을 받은 순간 = GC가 끝난 시각(Go 런타임은 한 바퀴가 끝날 때 줄을 쓴다) → 시작 = 끝 − wall ÷ 1000."""
    out = []
    with gzip.open(path, "rt") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 4 or parts[1] != "gc":
                continue
            try:
                t = float(parts[0])
            except ValueError:
                continue
            wall = stw = None
            for i, p in enumerate(parts):
                if p == "ms" and i + 1 < len(parts) and parts[i + 1].startswith("clock"):
                    try:
                        a = [float(x) for x in parts[i - 1].split("+")]
                        wall, stw = sum(a), a[0] + a[-1]
                    except ValueError:
                        pass
                    break
            out.append((t, wall, stw))
    return out


# ---- 지표 ------------------------------------------------------------------------------------------------
def covered_secs(intervals):
    """사건 구간 [a, b](PC 초) → ±1초로 맞는 정수 초 집합: 초 s(= [s, s+1))의 ±1초 창 [s−1, s+2)와 겹치는 s = (a − 2, b + 1]의 정수."""
    out = set()
    for a, b in intervals:
        out.update(range(math.floor(a - 2) + 1, math.floor(b + 1) + 1))
    return out


def overlap(slow, steady, intervals):
    """느린 초 중 ±1초 안에 사건(구간)이 있는 수 · 우연 비율(정상 구간 초 중 그런 초의 비율) · 우연 기대(비율 × 느린 초 수) · 배수(겹침 ÷ 기대) ·
    최대 배수(1 ÷ 우연 비율 — 느린 초가 전부 겹쳐도 넘을 수 없는 값). 최대 배수 < LIFT_DECIDE(2 — 가설 T2·T3 기준 배수)면 undecidable(그 회차로는 판정 불가)."""
    cov = covered_secs(intervals)
    hits = sum(1 for s in slow if s in cov)
    share = (sum(1 for s in steady if s in cov) / len(steady)) if steady else None
    exp = share * len(slow) if share is not None else None
    max_lift = (1 / share) if share else None
    return {"hits": hits, "n_slow": len(slow), "events": len(intervals), "chance_share": share, "expected": exp, "lift": (hits / exp) if exp else None,
            # 사건(튐·GC)이 0건이면 겹칠 대상이 없다 — 판정 불가가 아니라 '겹침 없음'(배수 0 — 반증 관측 없음, ADR-011 §4 측정 전 정정)
            "max_lift": max_lift, "no_events": share == 0,
            "undecidable": share is not None and share > 0 and max_lift < LIFT_DECIDE}


def _series(per, s_min, s_max):
    return [math.log1p(per[s]["n10"]) if s in per else 0.0 for s in range(s_min, s_max + 1)]


def period_test(per, s_min, s_max, null=None, x=None):
    """26초 성분 검정(머리말). 귀무(null): smooth(기본) = 매끈한 스펙트럼 대리 계열 · phase = 순수 위상 무작위(진폭 보존 — 비교용) · perm = 무작위 재배열(옛 정의).
    status: ok · no-component(10ms 넘는 요청 0건 — 성분 없음) · short(초가 대역 2주기보다 짧음 — 자료 부족)."""
    import numpy as np
    null = null or NULL
    x = np.asarray(x if x is not None else _series(per, s_min, s_max), float)
    n = len(x)
    base = {"n_secs": n, "null": null, "band_s": [BAND[0], BAND[1]], "surrogates": PERMS}
    if n < 2 * BAND[1]:
        return {**base, "status": "short", "p": None, "best_period_s": None}
    if not x.any():
        return {**base, "status": "no-component", "p": None, "best_period_s": None}
    x = x - x.mean()
    Ps = np.arange(BAND[0], BAND[1] + 1e-9, BAND[2])
    t = np.arange(n)
    E = np.exp(-2j * np.pi * np.outer(t, 1 / Ps))   # N × K
    pw = lambda X: np.abs(X @ E) ** 2 / n
    C = x @ E
    obs = np.abs(C) ** 2 / n
    i = int(obs.argmax())
    P = float(Ps[i])
    phase = float((-np.angle(C[i]) * P / (2 * np.pi)) % P)   # 성분 봉우리 = s_min + phase + kP (초)
    rng = np.random.default_rng(SEED)
    F = np.fft.rfft(x)
    amp2 = np.abs(F) ** 2
    if null == "smooth":
        # 주기도(진폭²)를 ±W칸(주파수 ±SMOOTH_HZ) 이동 중앙값으로 매끈하게 — 중앙값은 좁은 봉우리를 지운다. 지수 분포의 중앙값 = 평균 × ln 2라 ÷ ln 2로 평균에 맞춘다.
        # 대리 계열 = 그 평균 스펙트럼의 복소 정규(진폭·위상 모두 무작위) → 역변환. 진폭을 그대로 두는 순수 위상 무작위는 주기도가 관측과 같아 이 통계량에 힘이 없다
        W = max(5, int(round(SMOOTH_HZ * n)))
        S = np.array([np.median(amp2[max(1, k - W):k + W + 1]) for k in range(len(amp2))]) / math.log(2)
        S[0] = 0.0
    elif null == "ar":
        # 모수 귀무: AR(AR_ORDER) Yule-Walker 적합의 이론 스펙트럼 σ² ÷ |1 − Σ φ_j e^(−2πifj)|² — E|F_k|² ≈ N × S(f_k)
        r = np.array([x[:n - j] @ x[j:] / n for j in range(AR_ORDER + 1)])
        Rm = np.array([[r[abs(i - j)] for j in range(AR_ORDER)] for i in range(AR_ORDER)])
        phi = np.linalg.solve(Rm, r[1:])
        sig2 = r[0] - phi @ r[1:]
        f = np.arange(len(F)) / n
        A = 1 - np.exp(-2j * np.pi * np.outer(f, np.arange(1, AR_ORDER + 1))) @ phi
        S = n * sig2 / np.abs(A) ** 2
        S[0] = 0.0
    null_max = np.empty(PERMS)
    for k in range(0, PERMS, 100):
        m = min(100, PERMS - k)
        if null == "perm":
            X = np.array([rng.permutation(x) for _ in range(m)])
        elif null == "phase":
            ph = rng.uniform(0, 2 * np.pi, (m, len(F))); ph[:, 0] = 0
            if n % 2 == 0:
                ph[:, -1] = 0
            X = np.fft.irfft(np.abs(F) * np.exp(1j * ph), n=n)
        else:
            G = (rng.standard_normal((m, len(F))) + 1j * rng.standard_normal((m, len(F)))) * np.sqrt(S / 2)
            G[:, 0] = 0
            if n % 2 == 0:
                G[:, -1] = rng.standard_normal(m) * np.sqrt(S[-1])
            X = np.fft.irfft(G, n=n)
        X = X - X.mean(axis=1, keepdims=True)
        null_max[k:k + m] = pw(X).max(axis=1)
    p = float((np.sum(null_max >= obs[i]) + 1) / (PERMS + 1))
    return {**base, "status": "ok", "best_period_s": round(P, 2), "phase_s": round(phase, 2), "power": float(obs[i]),
            "null_median": float(np.median(null_max)), "p": p}


def periodic_secs(secs, s_min, period):
    """26초 성분에 맞는 초(봉우리 s_min + phase + kP의 ±PERIOD_TOL_S) — 성분이 유의(p ≤ P_SIG)할 때만, 아니면 빈 집합."""
    if not period or period.get("status") != "ok" or not num(period.get("p")) or period["p"] > P_SIG:
        return set()
    P, ph = period["best_period_s"], period["phase_s"]
    out = set()
    for s in secs:
        r = (s - s_min - ph) % P
        if min(r, P - r) <= PERIOD_TOL_S:
            out.add(s)
    return out


def probe_spikes(rows, t0, t1):
    """측정기 튐: 응답 > max(평소 p99 × SPIKE_X, SPIKE_FLOOR_MS) 또는 코드 ≠ 200. 사건 구간 = [응답 끝 − 응답 시간, 응답 끝](행 시각 = 끝)."""
    win = [r for r in rows if t0 <= r[0] <= t1]
    base = p99([r[1] for r in win if r[2] == "200"])
    if base is None:
        return None
    thr = max(SPIKE_X * base, SPIKE_FLOOR_MS)
    sp = [r for r in win if r[2] != "200" or r[1] > thr]
    return {"n": len(win), "p99_ms": base, "max_ms": max(r[1] for r in win), "spikes": len(sp), "threshold_ms": thr,
            "floor_ms": SPIKE_FLOOR_MS, "intervals": [(r[0] - r[1] / 1000, r[0]) for r in sp]}


def minute_trend(mins):
    full = sorted(m for m, v in mins.items() if len(v) > 0)
    if not full:
        return None
    # 마지막 분은 끝이 덜 찰 수 있다 — 앞 분 건수의 절반 미만이면 뺀다(얇은 분)
    med_n = statistics.median(len(mins[m]) for m in full)
    full = [m for m in full if len(mins[m]) >= med_n / 2]
    p = {m: p99(mins[m].tolist()) for m in full}
    out = {"minutes": len(full), "p99_by_minute": [round(p[m], 2) for m in full]}
    if len(full) >= 10:
        f5, l5 = statistics.median(p[m] for m in full[:5]), statistics.median(p[m] for m in full[-5:])
        out.update(first5_p99=f5, last5_p99=l5, growth_pct=(l5 / f5 - 1) * 100 if f5 else None)
    if len(full) >= 3:
        ys = [p[m] for m in full]
        ry = [sorted(ys).index(y) for y in ys]
        n = len(ys)
        rx = list(range(n))
        mx, my = statistics.mean(rx), statistics.mean(ry)
        cov = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
        sx = math.sqrt(sum((a - mx) ** 2 for a in rx)); sy = math.sqrt(sum((b - my) ** 2 for b in ry))
        out["spearman"] = cov / (sx * sy) if sx and sy else None
    return out


def rep_tail(d):
    """회차 하나의 ADR-011 지표(원시 파일만 — summary.json 불필요)."""
    d = Path(d)
    out = {"dir": str(d), "status": (d / "status").read_text().strip() if (d / "status").exists() else None, "notes": [],
           "knobs": {"null": NULL, "surrogates": PERMS, "spike_x": SPIKE_X, "spike_floor_ms": SPIKE_FLOOR_MS, "gc_stw_ms": GC_STW_MS, "gc_wall_ms": GC_WALL_MS,
                     "lift_decide": LIFT_DECIDE, "period_tol_s": PERIOD_TOL_S}}
    t0 = ramp_end(d)
    if t0 is None or not (d / "k6-requests.csv.gz").exists():
        out["notes"].append("k6-summary startedAt 또는 k6-requests.csv.gz 없음")
        return out
    per, mins, last = main_csv(d, t0)
    s_min = math.ceil(t0)
    steady = list(range(s_min, (last or s_min) + 1))
    slow = sorted(s for s in steady if s in per and per[s]["mx"] > SLOW_MS)
    k6_start = int(load(d / "k6-summary.json")["setup_data"]["startedAt"] / 1000)
    out.update(t0=t0, steady_secs=len(steady), slow_secs=len(slow), slow_rel_s=[s - k6_start for s in slow],
               slow_max_ms=[round(per[s]["mx"], 1) for s in slow])
    out["period"] = period_test(per, s_min, last or s_min)
    # 지연 분해 + T4 '주기 없는 서버형'(26초 성분에 맞는 초를 뺀 서버형 — 성분이 유의하지 않으면 전부 비주기)
    server = [s for s in slow if per[s]["comp"][0] > sum(per[s]["comp"][1:])]
    comps = [per[s]["comp"] for s in slow]
    client = [b + sn + r for (_, b, sn, r) in comps]
    wait = [w for (w, _, _, _) in comps]
    psec = periodic_secs(slow, s_min, out["period"])
    out["decomp"] = {"server_type": len(server), "client_type": len(slow) - len(server),
                     "waiting_med_ms": statistics.median(wait) if wait else None, "client_med_ms": statistics.median(client) if client else None,
                     "periodic_slow": len(psec), "periodic_server_slow": sum(1 for s in server if s in psec),
                     "aperiodic_server_slow": sum(1 for s in server if s not in psec)}
    # 측정기
    t1 = ((load(d / "probe-status.json") or {}).get("main") or {}).get("k6_end_epoch") or (last + 1 if last else None)
    off = clock_offset(d)
    out["probes"] = {}
    for name, f, shift in (("pc", "k6-probe-pc.csv.gz", 0.0), ("server", "k6-probe-server.csv.gz", off)):
        if not (d / f).exists():
            continue
        if shift is None:
            out["notes"].append(f"{name}: clock-offset 없음 — 서버 측정기 시각을 옮길 수 없다")
            continue
        try:
            sp = probe_spikes(probe_csv(d / f, shift), t0, t1)
        except (OSError, EOFError, ValueError) as e:
            out["notes"].append(f"{name}: {type(e).__name__}")
            continue
        if sp is None:
            out["probes"][name] = None
            continue
        ov = overlap(slow, steady, sp.pop("intervals"))
        out["probes"][name] = {**sp, "overlap": ov}
    # Go GC: 사건 = STW ≥ GC_STW_MS 또는 wall ≥ GC_WALL_MS인 바퀴, 구간 [끝 − wall, 끝]
    if (d / "k6-gctrace.log.gz").exists():
        allg = [x for x in gctrace(d / "k6-gctrace.log.gz") if t0 - 2 <= x[0] <= (t1 or x[0]) + 2]
        ev = [(t - (w or 0) / 1000, t) for t, w, s in allg if (num(s) and s >= GC_STW_MS) or (num(w) and w >= GC_WALL_MS)]
        ov = overlap(slow, steady, ev)
        stw = [s for _, _, s in allg if num(s)]
        wl = [w for _, w, _ in allg if num(w)]
        out["gogc"] = {"n": len(allg), "events": len(ev), "interval_med_s": statistics.median([b[0] - a[0] for a, b in zip(allg, allg[1:])]) if len(allg) > 1 else None,
                       "stw_max_ms": max(stw) if stw else None, "wall_med_ms": statistics.median(wl) if wl else None, "overlap": ov,
                       "overlap_all": overlap(slow, steady, [(t - (w or 0) / 1000, t) for t, w, _ in allg])}
    out["minute"] = minute_trend(mins)
    return out


# ---- 측정기 정상(run.sh rep_status probe-missing) ------------------------------------------------------
def probe_check(d):
    d = Path(d)
    st = load(d / "probe-status.json")
    res = {"ok": False, "reasons": [], "pc": None, "server": None}
    if not st:
        res["reasons"].append("probe-status.json 없음")
    t0, off = ramp_end(d), clock_offset(d)
    t1 = ((st or {}).get("main") or {}).get("k6_end_epoch")
    rate = (st or {}).get("rate") or 50
    if t0 is None or not num(t1):
        res["reasons"].append("창(램프 끝·주 k6 끝)을 모름")
    pc, sv = (st or {}).get("pc") or {}, (st or {}).get("server") or {}
    if pc.get("exit_code") not in (0, 105) or pc.get("killed"):
        res["reasons"].append(f"pc 종료 코드 {pc.get('exit_code')} · 강제 종료 {pc.get('killed')}")
    if sv.get("up_rc") != 0 or sv.get("exit_code") not in (0, 105) or sv.get("cp_rc") != 0 or sv.get("stop_rc") != 0 or sv.get("rm_rc") != 0:
        # 재점검(codex): 멈춤(stop)·지우기(rm) 실패도 — 컨테이너가 남아 다음 회차에 섞일 수 있다
        res["reasons"].append(f"server 기동 {sv.get('up_rc')}(124 = 기동 시한 초과) · 멈춤 {sv.get('stop_rc')} · 종료 코드 {sv.get('exit_code')} · 수거 {sv.get('cp_rc')} · 지우기 {sv.get('rm_rc')}")
    for name, f, shift in (("pc", "k6-probe-pc.csv.gz", 0.0), ("server", "k6-probe-server.csv.gz", off)):
        if t0 is None or not num(t1):
            break
        if shift is None:
            res["reasons"].append(f"{name}: clock-offset 없음")
            continue
        try:
            rows = probe_csv(d / f, shift)
        except (OSError, EOFError, ValueError, StopIteration) as e:
            res["reasons"].append(f"{name}: CSV 읽기 실패 {type(e).__name__}")
            continue
        win = [r for r in rows if t0 <= r[0] <= t1]
        ts = [r[0] for r in win]
        # 리뷰(codex 높음): 창 안 공백 + 양쪽 경계 공백(창 시작 → 첫 응답 · 마지막 응답 → 창 끝)을 같은 기준(PROBE_MAX_GAP_S)으로
        inner = max((b - a for a, b in zip(ts, ts[1:])), default=None)
        head = (ts[0] - t0) if ts else None
        tail_gap = (t1 - ts[-1]) if ts else None
        gap = max((g for g in (inner, head, tail_gap) if g is not None), default=None)
        bad = sum(1 for r in win if r[2] != "200")
        info = {"rows": len(rows), "in_window": len(win), "window": [t0, t1], "first_in_window": ts[0] if ts else None, "last_in_window": ts[-1] if ts else None,
                "expected": rate * (t1 - t0), "head_gap_s": head, "tail_gap_s": tail_gap, "max_inner_gap_s": inner, "max_gap_s": gap, "non200": bad}
        res[name] = info
        why = []
        if not ts:
            why.append("창 안 응답 0")
        if len(win) < PROBE_MIN_SHARE * info["expected"]:
            why.append(f"건수 {len(win)} < {PROBE_MIN_SHARE} × {info['expected']:.0f}")
        if gap is None or gap > PROBE_MAX_GAP_S:
            why.append(f"공백 {gap}초 > {PROBE_MAX_GAP_S}(앞 {head} · 안 {inner} · 뒤 {tail_gap})")
        if win and bad / len(win) > PROBE_BAD_MAX:
            why.append(f"200 아닌 응답 {bad}/{len(win)}")
        res["reasons"] += [f"{name}: {w}" for w in why]
    res["ok"] = not res["reasons"]
    (d / "probe-check.json").write_text(json.dumps(res, ensure_ascii=False, indent=1))
    return 0 if res["ok"] else 1


# ---- 캠페인 표 -------------------------------------------------------------------------------------------
def fx(x, d=1):
    if x is None:
        return "누락"
    if isinstance(x, bool):
        return "○" if x else "✕"
    if num(x):
        return f"{x:,.{d}f}" if not float(x).is_integer() or d == 0 else f"{x:,.0f}"
    return str(x)


def table(out, head, rows):
    out.append("| " + " | ".join(head) + " |")
    out.append("|" + "---|" * len(head))
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    out.append("")


def campaign(set_name, root):
    root = Path(root)
    sys.path.insert(0, str(HERE))
    import compare as C   # noqa: E402 — 안정성 4항목·앱 메모리(jvm_rep) 재사용
    conds = [l.split()[0] for l in (root / "conditions.txt").read_text().splitlines() if l.strip()]
    expect = {"soak": 2, "soakrc": 1}.get(set_name, 3)
    camp = C.Camp(root, expect_reps=expect)
    js = {"campaign": root.name, "set": set_name, "conds": {}}
    out = [f"# ADR-011 꼬리 지연 지표 — `{root.name}` (--set {set_name})\n",
           "> `k6/ADR-009/scripts/tail.py`가 회차 원시 파일(주 k6·측정기 CSV · gctrace · clock-offset)과 summary.json(안정성·메모리)에서 산출. 정의는 스크립트 머리말. "
           "값만 낸다 — 가설 T1~T5의 성립·기각 판정은 담지 않는다. 조건 묶음 = ok 회차만.\n"]
    per_rep_rows, cond_rows, stab_rows = [], [], []
    for c in conds:
        folder_reps = sorted(p for p in (root / c / "L4" / "S4c4200").glob("rep*") if p.is_dir() and "." not in p.name)
        summ_reps = {r.get("rep"): r for r in ((camp.summ.get(c) or {}).get("L4/S4c4200") or [])}
        if not summ_reps:
            camp.notes.append(f"{c}: summary.json 없음 — 안정성·메모리 칸은 누락(summarize.py 먼저)")
        reps = []
        for rd in folder_reps:
            t = rep_tail(rd)
            r = summ_reps.get(rd.name)
            if r is not None:
                jv = C.jvm_rep(camp, c, r)
                lim = jv["mem_bytes"] / 1048576 if num(jv.get("mem_bytes")) else None
                t["stability"] = {
                    "s1_full_stall_0": (jv["full_steady"] == 0 and jv["stalls_steady"] == 0) if num(jv["full_steady"]) and num(jv["stalls_steady"]) else None,
                    "s2_stop_lt_10ms": (jv["stop_max_steady"] < 10) if num(jv["stop_max_steady"]) else None,
                    "s3_err_code0_rps": (jv["err"] == 0 and jv["status0_share"] == 0 and jv["rps_ratio"] > 0.995)
                                        if all(num(jv[k]) for k in ("err", "status0_share", "rps_ratio")) else None,
                    "s4_mem_growth_lt_10": (jv["app_mem_growth_pct"] < 10) if num(jv["app_mem_growth_pct"]) else None,
                    "mem_growth_pct": jv["app_mem_growth_pct"], "stop_max_steady": jv["stop_max_steady"],
                    "app_mem_max_mib": jv["app_mem_max"], "mem_limit_mib": lim,
                    "mem_max_share": (jv["app_mem_max"] / lim) if num(jv["app_mem_max"]) and lim else None,
                    "sec_p99_max": jv["sec_p99_max"], "slow_secs_50ms_summarize": jv["slow_secs"]}
            reps.append(t)
        js["conds"][c] = reps
        ok = [t for t in reps if t["status"] == "ok"]
        if len(ok) != expect:
            camp.notes.append(f"{c}: ok 회차 {len(ok)}/{expect} — {[(Path(t['dir']).name, t['status']) for t in reps]}")
        for t in reps:
            g = lambda *k: _get(t, *k)
            per_rep_rows.append([c, Path(t["dir"]).name, t["status"] or "누락", fx(g("slow_secs"), 0), _ptxt(g("period")), fx(g("period", "best_period_s"), 2),
                                 fx(g("decomp", "server_type"), 0), fx(g("decomp", "aperiodic_server_slow"), 0), fx(g("decomp", "periodic_slow"), 0), fx(g("decomp", "client_type"), 0), fx(g("decomp", "waiting_med_ms")), fx(g("decomp", "client_med_ms")),
                                 _ov(g("probes", "pc")), fx(g("probes", "pc", "spikes"), 0), _ov(g("probes", "server")), fx(g("probes", "server", "spikes"), 0),
                                 _ovg(g("gogc", "overlap")), f'{fx(g("gogc", "events"), 0)}/{fx(g("gogc", "n"), 0)}', fx(g("gogc", "stw_max_ms"), 2)])
            if t.get("stability") or t.get("minute"):
                s = t.get("stability") or {}
                m = t.get("minute") or {}
                stab_rows.append([c, Path(t["dir"]).name, t["status"] or "누락", fx(s.get("s1_full_stall_0")), fx(s.get("s2_stop_lt_10ms")), fx(s.get("s3_err_code0_rps")),
                                  fx(s.get("s4_mem_growth_lt_10")), fx(s.get("mem_growth_pct")), fx(s.get("app_mem_max_mib"), 0), fx(s.get("mem_limit_mib"), 0),
                                  fx((s.get("mem_max_share") or 0) * 100 if num(s.get("mem_max_share")) else None), fx(m.get("minutes"), 0), fx(m.get("first5_p99"), 2),
                                  fx(m.get("last5_p99"), 2), fx(m.get("growth_pct")), fx(m.get("spearman"), 2), fx(s.get("sec_p99_max")), fx(s.get("slow_secs_50ms_summarize"), 0)])
        # 조건 묶음(ok 회차): 느린 초·p·서버형 범위 + 겹침 합
        rng = lambda k: _rng([_get(t, *k) for t in ok])
        pool = lambda *k: _pool([_get(t, *k) for t in ok])
        nocomp = sum(1 for t in ok if _get(t, "period", "status") == "no-component")
        cond_rows.append([c, len(ok), rng(("slow_secs",)), rng(("period", "p")) + (f" · 성분 없음 {nocomp}" if nocomp else ""),
                          sum(1 for t in ok if num(_get(t, "period", "p")) and _get(t, "period", "p") <= P_SIG),
                          rng(("decomp", "server_type")), rng(("decomp", "aperiodic_server_slow")), pool("probes", "pc", "overlap"), rng(("probes", "pc", "spikes")),
                          pool("probes", "server", "overlap"), rng(("probes", "server", "spikes")), pool("gogc", "overlap")])
    out.append("## 1. 조건별(ok 회차) — 값 = 중앙값 [최소–최대] · 겹침 = 합한 겹침/느린 초(우연 기대 · 배수)\n")
    table(out, ["조건", "n(ok)", "느린 초", "26초 성분 p(24~31초)", "p ≤ 0.05 회차 수", "서버형 느린 초", "주기 없는 서버형(T4)", "PC 측정기 겹침", "PC 측정기 튐 수", "서버 측정기 겹침",
                "서버 측정기 튐 수", "Go GC 겹침"], cond_rows)
    out.append("## 2. 회차별\n")
    table(out, ["조건", "회차", "상태", "느린 초", "26초 p", "최대 성분 주기 s", "서버형", "주기 없는 서버형", "주기 초(±1.5)", "클라이언트형", "waiting 중앙 ms", "클라이언트 구간 중앙 ms",
                "PC 측정기 겹침(기대·배수·최대 배수)", "PC 튐", "서버 측정기 겹침(기대·배수·최대 배수)", "서버 튐", "Go GC 겹침(기대·배수·최대 배수)", "Go GC 사건/전체", "Go GC STW 최대 ms"], per_rep_rows)
    out.append("## 3. 안정성(ADR-010 §9 4항목) · 앱 메모리 · 1분 p99 추세\n")
    table(out, ["조건", "회차", "상태", "① Full GC·앱 멈춤 0", "② 정지 최대 < 10ms", "③ 에러·코드 0·RPS", "④ 메모리 추세 < 10%", "메모리 추세 %", "앱 메모리 최대 MiB",
                "한도 MiB", "최대 ÷ 한도 %", "분 수", "첫 5분 p99 ms", "마지막 5분 p99 ms", "증가 %", "순위 상관", "1초 p99 최대 ms", "> 50ms 초(summarize)"], stab_rows)
    out.append("## 누락·의심\n")
    out += [f"- {n}" for n in camp.notes] or ["- 자동 점검에서 걸린 항목 없음"]
    js["notes"] = camp.notes
    (root / "TAIL.md").write_text("\n".join(out) + "\n")
    (root / "tail.json").write_text(json.dumps(js, ensure_ascii=False, indent=1, default=str))
    print(root / "TAIL.md")
    return 0


def _get(x, *k):
    for kk in k:
        x = x.get(kk) if isinstance(x, dict) else None
    return x


def _rng(vals):
    v = [x for x in vals if num(x)]
    if not v:
        return "누락"
    d = 3 if all(0 <= x <= 1 for x in v) and not all(float(x).is_integer() for x in v) else 0
    s = fx(statistics.median(v), d) if len(v) == 1 else f"{fx(statistics.median(v), d)} [{fx(min(v), d)}–{fx(max(v), d)}]"
    return s + (f" · 누락 {len(vals) - len(v)}" if len(v) < len(vals) else "")


def _pool(ovs):
    """조건 묶음 겹침 — 판정 가능한 회차(undecidable 아님)만 합한다. 판정 불가 회차 수는 따로 적는다."""
    o = [x for x in ovs if isinstance(x, dict) and num(x.get("expected"))]
    if not o:
        return "누락"
    und = [x for x in o if x.get("undecidable")]
    o = [x for x in o if not x.get("undecidable")]
    tail = (f" · 판정 불가 {len(und)}회" if und else "") + (f" · 누락 {len(ovs) - len(o) - len(und)}" if len(o) + len(und) < len(ovs) else "")
    if not o:
        return "판정 불가(전 회차 최대 배수 < %g)" % LIFT_DECIDE + tail
    h, n, e = sum(x["hits"] for x in o), sum(x["n_slow"] for x in o), sum(x["expected"] for x in o)
    return f"{h}/{n}(기대 {e:.1f} · {('×%.2f' % (h / e)) if e else '—'})" + tail


def _ov(p):
    return _ovg((p or {}).get("overlap")) if p else "누락"


def _ovg(o):
    if not isinstance(o, dict):
        return "누락"
    mx = ("최대 ×%.2f" % o["max_lift"]) if num(o.get("max_lift")) else "최대 —"
    s = f"{o['hits']}/{o['n_slow']}(기대 {fx(o['expected'])} · {('×%.2f' % o['lift']) if num(o.get('lift')) else '—'} · {mx})"
    return ("판정 불가 " + s) if o.get("undecidable") else s


def _ptxt(p):
    """26초 성분 p — '성분 없음'(10ms 넘는 요청 0건)과 '자료 부족'(초가 대역 2주기보다 짧음)·'누락'(계산 안 됨)을 가른다."""
    if not isinstance(p, dict):
        return "누락"
    st = p.get("status")
    if st == "no-component":
        return "성분 없음"
    if st == "short":
        return "자료 부족"
    return fx(p.get("p"), 3)


def main(argv):
    if len(argv) == 2 and argv[0] == "--probe-check":
        return probe_check(argv[1])
    if len(argv) == 2 and argv[0] == "--rep":
        print(json.dumps(rep_tail(argv[1]), ensure_ascii=False, indent=1, default=str))
        return 0
    if len(argv) == 3 and argv[0] == "--set" and argv[1] in ("k6ver", "pretouch", "soak", "soakrc"):
        return campaign(argv[1], argv[2])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
