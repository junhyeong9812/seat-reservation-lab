#!/usr/bin/env python3
"""ADR-011 꼬리 지연 판정 지표(ADR-011 §4 가설 T1~T5) — 회차별·조건별 값만 낸다(성립·기각 판정은 메인 몫).

    scripts/tail.py --set k6ver|pretouch|soak|soakrc|adr012s1|adr012r8|adr012s2|adr012s2x|adr012eff results/<campaign-id>   → <campaign-id>/TAIL.md · tail.json
                                                                       (adr012s1 = ADR-012 1단계 — 아래 'ADR-012' 지표 표 4·5를 더한다)
                                                                       (adr012r8 = ADR-012 R8 — 같은 표 + 대조 바퀴 표에 ①·② 갈래 시간 열 · 회차 표에 탐지 모양)
                                                                       (adr012s2·adr012s2x = ADR-012 2단계·2' — 표 4~6 + 7절 R2 증분 틱 · R7 안전망 · 보장 밖 기록 · R5 선점 p99·HOT·인덱스)
                                                                       (adr012eff = ADR-012 효과 — S3e 회차 effect.json만: 경로별 복구 · R4 · R6 · 보장 밖 · 오탐)
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
  - ADR-012 1단계(ADR-012 §4 창 정의 · 하네스 ①②⑦ — 회차 결과 late · cpu_steady · rounds, 표는 --set adr012s1에서만):
    정상 구간 = 정상 구간 첫 초 s_min(= t0의 올림)부터 1,800초 [s_min, s_min + 1800) · 후반 = [s_min + 900, s_min + 1800)(정상 구간이 1,800초 미만이면 late = null · late_unavailable 사유).
    ① 후반 느린 초 · 후반 서버형 느린 초(위 정의 그대로) · 후반 1초 p99 최대(초마다 hold 응답(코드 0 제외) 정렬 값[int(n × 0.99)], 계획 도착률 절반 미만 초 제외 — summarize와 같다).
       창 공백: 관측 초(응답 있는 초) · 빈 초(응답 0 — 창 초 수 − 관측 초) · 얇은 초(응답 < 계획 도착률 절반). 빈 + 얇은 > 9초(GAP_MAX_S)면 값은 남기고
       late_unavailable(후반) · steady_sparse(정상 구간 — 같은 기준) 사유를 적는다.
    ② 정상 구간 컨테이너 CPU% 평균·최대(docker stats — 서버 시각 − 시계 차가 [t0, 주 k6 끝] 안인 표본만 · 100% = 1코어) · 표본 수 · 표본 간격 최대(결측 판단).
       F 포화: F 컨테이너 표본 중 CPU% ≥ 한도 코어 × 100 × 0.95(SAT_FRAC)인 비율(sat_share). 한도 = 회차 app-config.json containers의 seatlab-reconciler-1 NanoCpus ÷ 10⁹
       (run.sh app_config가 docker inspect로 남긴다 — limit_source), 기록이 없으면 compose.reconciler.yml 기본 RECONCILER_CPUS = 1로 가정하고 limit_source에 그렇게 적는다.
    ⑦ 대조 바퀴(drift-db reconcile_runs — 출처별: reconcile-app = 앱 안 C · reconcile-proc = F): 바퀴 시간 = finished − started. 창(정상 구간·후반) 안 바퀴 = 시작과 끝이
       모두 창 안([시작, 끝] ⊂ [창 시작, 창 끝)) — 수·중앙값·최대. 창 경계에 걸친 바퀴(창과 겹치지만 안에 다 들지 않음)는 crossing으로 수만 따로.
       바퀴 중 대 밖: 정상 구간 표본(Hikari·docker stats) 중 그 출처의 어느 바퀴(경계에 걸친 것 포함)와 겹치면 바퀴 중 — Hikari(업무 앱 풀) pending > 0 표본 비율 ·
       컨테이너 CPU% 평균·최대(F는 포화 비율도) · 표본 수(in_n·out_n).
    R8(--set adr012r8 — ADR-012 §4.1·§5 'R8 판정' 주 지표): 같은 창 안 바퀴(시작·끝 모두 창 안 — 위 ⑦과 같은 바퀴)마다 ① 쿼터 청크 시간 chunk_ms_total ·
       ② 행 없음 갈래 시간 orphan_ms_total(drift-db reconcile_runs 열 — 앱이 잰 벽시계 ms) → 창별 중앙값·최대(값 없는 바퀴는 빼고 n에 안 셈). 회차 표 '탐지 모양' =
       회차 reconciler-config.json의 counterDrift.reconcile.detectShape(없으면 '누락' — run.sh reconciler-config-mismatch가 셀과 같은지 본다).
    결측 사유(a12_missing — 빈 칸으로 두지 않는다): 기대 출처 = 회차 meta.json cond → summarize.RECONCILE_SOURCE(A = 없음 · default = reconcile-app · A+F = reconcile-proc) ·
    기대 컨테이너 = 앱·DB(늘) + F(cond A+F). 기대 출처 바퀴 0(정상 구간 — 안·걸침 모두) · drift-db 없음 · 기대 컨테이너 표본 0 · 시계 차·docker stats 없음이면 사유 한 줄씩.
    a12_warn: 출처 없는 바퀴 행(표에 'None' 행으로 두지 않고 뺀다) · 기대하지 않은 출처·F 표본.
  - ADR-012 2단계(--set adr012s2·adr012s2x — ADR-012 §4·§5 R2·R5·R7 · 회차 결과 a12x, 판정은 메인 몫):
    기대 출처(새 조건 A+I·A+X) = meta.json incremental의 [증분 source, 안전망 source](summarize.expected_sources) — 둘 다 표 5·6에 행이 있고 정상 구간 바퀴 0이면 결측 사유.
    창(DB 시계) = 정상 구간 [s_min, s_min + 1800) + 시계 차. R2 = 증분 틱 시간(finished − started ms — 기록 앱 시계, A+X는 .158 시계 · 시계 차 미보정) 첫 5분 대 마지막 5분 중앙값
    (반증 = 2배 이상 그리고 +20ms 이상 — summarize.inc_r2). R7 = counter_safety_run 실행(db_started_at이 창 안) ①~⑤(summarize.safety_r7 — 허용식의 repair-budget·wall-cap·repair-cap은
    configprops, 없으면 §4.2.5 기본값 + 출처 표시 · 자기 기록 INSERT = actsnap safety.self_record MAX의 스냅숏 최대 — 최근 약 2분 창이라 하한). 보장 밖 = counter_inc_gap 종류별 행 수.
    R5 = 정상 구간(분 0~29) 선점 hold 응답(코드 0 제외) p99(분별 배열 — 단정밀도) · 같은 캠페인 noc(조건 이름 noc-…) 중앙값 대비 % · HOT 비율 = k6 구간 Δn_tup_hot_upd ÷ Δn_tup_upd
    (io-before/after quota_table) · updated_at 인덱스 크기 = quota-hot.json(k6 직전 · 회차 끝 — V9 방식만).
  - ADR-012 효과(--set adr012eff): 회차 effect.json(summarize.effect — run.sh가 회차 끝에 만든다)의 종류별 주입/복구/끝 일치 · R4 위반/판정 불가/보장 밖 · R6 위반/판정 수 · 오탐 복구 · unexpected.
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
def main_csv(d, t0, keep=None):
    """주 k6 CSV(hold) → 초별 {n, n10, mx, comp(최대 응답 요청의 waiting·blocked·sending·receiving)} · 분별 응답 목록(코드 0 제외, s ≥ t0).
    keep = (lo, hi)면 lo ≤ s < hi인 초의 응답 목록(배정밀도 — summarize 1초 p99와 같은 값)도 secv에 모은다(ADR-012 후반 창).
    한 요청의 행은 http_req_duration 다음에 blocked·connecting·tls·sending·waiting·receiving이 붙어 온다(k6 CSV — 요청 한 묶음)."""
    from array import array
    per, mins, last, secv = {}, {}, None, {}
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
            if keep is not None and keep[0] <= s < keep[1]:
                secv.setdefault(s, array("d")).append(dur)
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
    return per, mins, last, secv


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


# ---- ADR-012 1단계 지표(ADR-012 §4 '창 정의'·'측정 전에 하네스에 필요한 것' ①②⑦ · §5 R1 판정표) -----------------------
STEADY_S, LATE_FROM_S = 1800, 900   # 정상 구간 = 정상 구간 첫 초부터 1,800초 · 후반 = 그 900초 이상 1,800초 미만(ADR-012 §4 — 바꾸면 ADR과 함께)
GAP_MAX_S = 9                         # 창 안 빈 초 + 얇은 초가 이보다 많으면 그 창 값에 판정 불가 표시(값은 남긴다 — rv37)
SAT_FRAC = 0.95                       # F 포화 표본 = CPU% ≥ 한도 × 100 × 이 값(ADR-012 §5 R1 판정 유효 조건)
F_CPUS_DEFAULT = 1.0                  # compose.reconciler.yml cpus: ${RECONCILER_CPUS:-1} — 회차에 한도 기록이 없을 때의 가정
A12_CONDS = {"control", "control-noclose", "A", "A+C", "A+F", "B", "B+C", "default", "A+I", "A+X"}   # run.sh CONDS — 이 밖의 cond는 결측 사유(기대 출처를 정할 수 없다)
INC_CONDS = ("A+I", "A+X")   # ADR-012 2단계 새 조건 — 기대 출처 = meta.json incremental(summarize.expected_sources)


def iso_ts(x):
    from datetime import datetime
    return datetime.fromisoformat(str(x).replace("Z", "+00:00")).timestamp()


def sec_gaps(per, lo, hi, thin):
    """창 [lo, hi)의 초 공백: 관측 초(hold 응답(코드 0 제외) 있는 초) · 빈 초(창 초 수 − 관측 초) · 얇은 초(응답 < thin) · 빈 + 얇은 > GAP_MAX_S면 사유."""
    obs = [s for s in range(lo, hi) if s in per]
    th = sum(1 for s in obs if per[s]["n"] < thin)
    empty = (hi - lo) - len(obs)
    why = (f"빈 초 {empty} + 얇은 초 {th} = {empty + th} > {GAP_MAX_S}초 — 값은 남기되 판정 불가") if empty + th > GAP_MAX_S else None
    return {"secs_window": hi - lo, "secs": len(obs), "secs_empty": empty, "secs_thin": th, "thin_min_n": thin}, why


def late_window(per, secv, slow, server, s_min, steady_n, thin):
    """후반 창 [s_min + 900, s_min + 1800) — 느린 초 · 서버형 느린 초(decomp와 같은 정의) · 1초 p99 최대(summarize와 같은 계산: 초마다 hold 응답(코드 0 제외)
    정렬 값[int(n × 0.99)], 계획 도착률 절반 미만인 초(thin)는 뺌). 정상 구간이 1,800초 미만이면 None + 사유 · 빈 + 얇은 초 > GAP_MAX_S면 값 + 사유."""
    if steady_n < STEADY_S:
        return None, f"정상 구간 {steady_n}초 < {STEADY_S}초 — 후반 창 판정 불가"
    lo, hi = s_min + LATE_FROM_S, s_min + STEADY_S
    p = {s: p99(v.tolist()) for s, v in secv.items() if lo <= s < hi and len(v) >= thin}
    mx = max(p, key=p.get) if p else None
    gaps, why = sec_gaps(per, lo, hi, thin)
    return {"window_rel_s": [LATE_FROM_S, STEADY_S], **gaps,
            "slow_secs": sum(1 for s in slow if lo <= s < hi), "server_slow_secs": sum(1 for s in server if lo <= s < hi),
            "sec_p99_max_ms": p[mx] if mx is not None else None, "sec_p99_max_rel_s": (mx - s_min) if mx is not None else None}, (f"후반 {why}" if why else None)


def cpu_limits(d):
    """F 컨테이너 CPU 한도(코어) · 출처 — app-config.json containers(run.sh app_config: docker inspect HostConfig.NanoCpus) → 없으면 compose 기본 1(가정)."""
    for x in ((load(d / "app-config.json") or {}).get("containers") or []):
        if isinstance(x, dict) and str(x.get("name", "")).lstrip("/") == "seatlab-reconciler-1" and num(x.get("cpu")):
            if x["cpu"] > 0:
                return {"f": (x["cpu"] / 1e9, "app-config.json NanoCpus")}
            return {"f": (None, "app-config.json NanoCpus 0 — 한도 없음(포화 비율 없음)")}
    return {"f": (F_CPUS_DEFAULT, "가정: compose.reconciler.yml RECONCILER_CPUS 기본 1 — 회차 app-config.json에 F 한도 기록 없음")}


def cpu_stat(v, lim=None):
    """CPU% 표본 → 평균·최대·수 (+ 한도가 있으면 ≥ 한도 × 100 × SAT_FRAC 표본 수·비율)."""
    r = {"mean": statistics.mean(v) if v else None, "max": max(v) if v else None, "n": len(v)}
    if lim is not None:
        thr = lim[0] * 100 * SAT_FRAC if num(lim[0]) else None
        k = sum(1 for x in v if x >= thr) if thr is not None else None
        r.update(limit_cores=lim[0], limit_source=lim[1], sat_threshold_pct=thr, sat_n=k, sat_share=(k / len(v)) if v and k is not None else None)
    return r


def server_stats(d, off):
    """timeline-server.jsonl(서버에서 찍은 'date -Is' 줄 + docker stats 줄들) → [(PC 시각, {컨테이너 이름: CPU%})] — 시각 = 서버 시각 − 시계 차(compare.cpu와 같다)."""
    out, cur = [], None
    p = d / "timeline-server.jsonl"
    if not p.exists():
        return None
    for line in p.read_text().splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "t" in row:
            cur = (iso_ts(row["t"]) - off, {})
            out.append(cur)
            continue
        if cur is None or "Name" not in row:
            continue
        try:
            cur[1][row["Name"]] = float(str(row.get("CPUPerc", "")).rstrip("%"))
        except ValueError:
            pass
    return out


def steady_cpu(stats, t0, t1, containers, limits):
    """정상 구간 [t0, 주 k6 끝] 안 docker stats 표본만 — 컨테이너별 CPU% 평균·최대·표본 수(100% = 1코어) · 표본 간격 최대(창 시작 → 첫 표본 · 사이 · 마지막 → 창 끝 — 결측 판단용).
    F는 한도(limits — cpu_limits) 대비 포화 표본 비율도."""
    win = [x for x in stats if t0 <= x[0] <= t1]
    ts_ = [x[0] for x in win]
    res = {"window": [t0, t1], "samples": len(win), "head_gap_s": (ts_[0] - t0) if ts_ else None, "tail_gap_s": (t1 - ts_[-1]) if ts_ else None,
           "max_inner_gap_s": max((b - a for a, b in zip(ts_, ts_[1:])), default=None), "containers": {}}
    for k, name in containers.items():
        v = [x[1][name] for x in win if name in x[1]]
        if v or k != "f":   # F 컨테이너는 있을 때만(없는 조건에 '0 표본'을 남기지 않는다 — F가 기대인데 없으면 rep_tail이 a12_missing에 적는다)
            res["containers"][k] = {**cpu_stat(v, limits.get(k)), "missing": len(win) - len(v)}
    return res


def _dist(xs):
    return {"n": len(xs), "median_s": statistics.median(xs) if xs else None, "max_s": max(xs) if xs else None}


def _dist_ms(xs):
    v = [x for x in xs if num(x)]
    return {"n": len(v), "median_ms": statistics.median(v) if v else None, "max_ms": max(v) if v else None}


def recon_rounds(d, off, s_min, stats, containers, limits):
    """drift-db.json reconcile_runs → 출처(source — reconcile-app = 앱 안 C · reconcile-proc = F)별 바퀴 시간(finished − started, DB 시계 차이라 보정 불필요) · 경고 목록.
    시각 = DB 시각 − 시계 차 → PC 시계. 창 = 정상 구간 [s_min, s_min + 1800) · 후반 [s_min + 900, s_min + 1800). 창 안 바퀴 = 시작·끝 모두 창 안 · 경계에 걸친 바퀴 = crossing(수만).
    바퀴 중 대 바퀴 밖(정상 구간 안의 표본만, 바퀴 = 그 출처의 모든 바퀴 — 경계에 걸친 것 포함): Hikari pending > 0 표본 비율(timeline-hikari.jsonl — PC 'date -Is' 초 단위 →
    표본 = [T, T+1)이 바퀴 [시작, 끝]과 겹치면 바퀴 중) · docker stats 컨테이너별 CPU% 평균·최대(표본 시각이 바퀴 [시작, 끝] 안이면 바퀴 중 — F는 포화 비율도) · 표본 수.
    Hikari는 업무 앱 풀만 잰다(F 컨테이너 풀은 없음). 출처 없는 행·시각 못 읽은 행은 빼고 경고에 센다(빈 칸·'None' 행으로 두지 않는다)."""
    db = load(d / "drift-db.json")
    if db is None or not isinstance(db.get("reconcile_runs"), list):
        return None, []
    w0, w1, l0 = s_min, s_min + STEADY_S, s_min + LATE_FROM_S
    runs, nosrc, bad, ms = {}, 0, 0, {}
    for r in db["reconcile_runs"]:
        try:
            a, b = iso_ts(r["started_at"]) - off, iso_ts(r["finished_at"]) - off
        except (KeyError, TypeError, ValueError):
            bad += 1
            continue
        if not r.get("source"):
            nosrc += 1
            continue
        runs.setdefault(r["source"], []).append((a, b))
        ms.setdefault(r["source"], {})[(a, b)] = (r.get("chunk_ms_total"), r.get("orphan_ms_total"))   # R8 ①·② 갈래 시간(바퀴 기록 열)
    warns = ([f"drift-db reconcile_runs 출처(source) 없는 바퀴 {nosrc}행 — 뺐다"] if nosrc else []) + \
            ([f"drift-db reconcile_runs 시각(started_at·finished_at) 못 읽은 바퀴 {bad}행 — 뺐다"] if bad else [])
    hik = []
    hp = d / "timeline-hikari.jsonl"
    if hp.exists():
        for line in hp.read_text().splitlines():
            try:
                row = json.loads(line)
                t = iso_ts(row["t"])
            except (json.JSONDecodeError, KeyError, TypeError, ValueError):
                continue
            if w0 <= t < w1 and num(row.get("pending")):
                hik.append((t, row["pending"]))
    sw = [x for x in (stats or []) if w0 <= x[0] < w1]
    inside = lambda rr, lo, hi: [(a, b) for a, b in rr if lo <= a and b < hi]
    crossing = lambda rr, lo, hi: [(a, b) for a, b in rr if a < hi and b >= lo and not (lo <= a and b < hi)]
    out = {}
    for src, rr in sorted(runs.items()):
        st, lt = inside(rr, w0, w1), inside(rr, l0, w1)
        hin = [p for t, p in hik if any(t < b and t + 1 > a for a, b in rr)]
        hout = [p for t, p in hik if not any(t < b and t + 1 > a for a, b in rr)]
        cin = [x for x in sw if any(a <= x[0] <= b for a, b in rr)]
        cout = [x for x in sw if not any(a <= x[0] <= b for a, b in rr)]
        cpu = {}
        for k, name in containers.items():
            vi, vo = [x[1][name] for x in cin if name in x[1]], [x[1][name] for x in cout if name in x[1]]
            if vi or vo:
                lim = limits.get(k)
                ci, co = cpu_stat(vi, lim), cpu_stat(vo, lim)
                cpu[k] = {"in_mean": ci["mean"], "in_max": ci["max"], "in_n": ci["n"], "out_mean": co["mean"], "out_max": co["max"], "out_n": co["n"]}
                if lim is not None:
                    cpu[k].update(limit_cores=ci["limit_cores"], limit_source=ci["limit_source"], sat_threshold_pct=ci["sat_threshold_pct"],
                                  in_sat_n=ci["sat_n"], in_sat_share=ci["sat_share"], out_sat_n=co["sat_n"], out_sat_share=co["sat_share"])
        out[src] = {"total": len(rr), "steady": _dist([b - a for a, b in st]), "steady_crossing": len(crossing(rr, w0, w1)),
                    "late": _dist([b - a for a, b in lt]), "late_crossing": len(crossing(rr, l0, w1)),
                    "steady_dur_s": [round(b - a, 3) for a, b in st],
                    "hikari": {"in_n": len(hin), "in_pending_share": (sum(1 for p in hin if p > 0) / len(hin)) if hin else None,
                               "out_n": len(hout), "out_pending_share": (sum(1 for p in hout if p > 0) / len(hout)) if hout else None},
                    "stats_samples": {"in_n": len(cin), "out_n": len(cout)}, "cpu": cpu,
                    "branch_ms": {k: {"steady": _dist_ms([ms[src][x][i] for x in st]), "late": _dist_ms([ms[src][x][i] for x in lt])}
                                  for i, k in enumerate(("chunk", "orphan"))}}
    return out, warns


def rep_tail(d, a12x=False):
    """회차 하나의 ADR-011 지표(원시 파일만 — summary.json 불필요). a12x = ADR-012 2단계 지표(a12x — 새 세트·--rep에서만: 이전 세트의 tail.json 그대로)."""
    d = Path(d)
    out = {"dir": str(d), "status": (d / "status").read_text().strip() if (d / "status").exists() else None, "notes": [],
           "knobs": {"null": NULL, "surrogates": PERMS, "spike_x": SPIKE_X, "spike_floor_ms": SPIKE_FLOOR_MS, "gc_stw_ms": GC_STW_MS, "gc_wall_ms": GC_WALL_MS,
                     "lift_decide": LIFT_DECIDE, "period_tol_s": PERIOD_TOL_S}}
    t0 = ramp_end(d)
    if t0 is None or not (d / "k6-requests.csv.gz").exists():
        out["notes"].append("k6-summary startedAt 또는 k6-requests.csv.gz 없음")
        return out
    s_min = math.ceil(t0)
    per, mins, last, secv = main_csv(d, t0, keep=(s_min + LATE_FROM_S, s_min + STEADY_S))
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
    # ADR-012 1단계(위 'ADR-012' 함수들) — ① 후반 창 ② 정상 구간 CPU ⑦ 대조 바퀴
    rates = (load(d / "k6-summary.json") or {}).get("stage_rates") or []
    thin = (min(rates) / 2) if rates else 0
    out["late"], why = late_window(per, secv, slow, server, s_min, len(steady), thin)
    if why:
        out["late_unavailable"] = why
    out["steady_window"], why = sec_gaps(per, s_min, s_min + STEADY_S, thin)
    if why:
        out["steady_sparse"] = f"정상 구간 {why}"
    sys.path.insert(0, str(HERE))
    from compare import CONTAINERS, S   # noqa: E402 — app·f(seatlab-reconciler-1)·db 이름 한 곳 · summarize(조건 → 기대 대조 출처 RECONCILE_SOURCE)
    # 결측 사유(머리말 'a12_missing') — 기대 출처·컨테이너는 회차 meta.json cond에서
    miss, warn = [], []
    meta = load(d / "meta.json") or {}
    cond = meta.get("cond")
    exp_src, exp_f = S.RECONCILE_SOURCE.get(cond), cond == "A+F"
    exp_srcs = [exp_src] if exp_src else []
    out["a12_expect"] = {"cond": cond, "source": exp_src, "f_container": exp_f}
    if cond in INC_CONDS:   # ADR-012 새 조건: 기대 출처 2개(증분 · 안전망) — 표 행은 sources로
        exp_srcs = S.expected_sources(meta)
        exp_src = exp_srcs[0] if exp_srcs else None
        out["a12_expect"].update(source=exp_src, sources=exp_srcs)
        if not exp_srcs:
            miss.append("meta.json incremental 없음 — 기대 증분·안전망 출처를 정할 수 없다")
    if cond is None:
        miss.append("meta.json cond 없음 — 기대 대조 출처·F 컨테이너를 정할 수 없다")
    elif cond not in A12_CONDS:
        miss.append(f"meta.json cond {cond!r} 알 수 없음 — 기대 대조 출처·F 컨테이너를 정할 수 없다")
    limits = cpu_limits(d) if exp_f or cond not in A12_CONDS else {}
    if off is None:
        out["notes"].append("clock-offset 없음 — docker stats·대조 바퀴(서버 시계)를 PC 시계로 옮길 수 없다(cpu_steady·rounds 없음)")
        miss.append("clock-offset 없음 — CPU·대조 바퀴 없음")
        out["cpu_steady"] = out["rounds"] = None
    else:
        stats = server_stats(d, off)
        if stats is None:
            miss.append("timeline-server.jsonl 없음 — CPU 없음")
        elif not num(t1):
            miss.append("주 k6 끝 시각 없음 — 정상 구간 CPU 없음")
        out["cpu_steady"] = steady_cpu(stats, t0, t1, CONTAINERS, limits) if stats is not None and num(t1) else None
        out["rounds"], rw = recon_rounds(d, off, s_min, stats, CONTAINERS, limits)
        warn += rw
        if out["rounds"] is None:
            miss.append("drift-db.json reconcile_runs 없음 — 대조 바퀴 없음")
        else:
            for es in exp_srcs:
                r = out["rounds"].get(es) or {}
                n_in = (_get(r, "steady", "n") or 0) + (r.get("steady_crossing") or 0)
                if n_in == 0:
                    miss.append(f"기대 출처 {es} 바퀴 0(정상 구간 — 걸침 포함 · 전체 {r.get('total', 0)})")
            for src, r in out["rounds"].items():
                if src not in exp_srcs:
                    warn.append(f"기대하지 않은 출처 {src} 바퀴 {r['total']}(cond {cond})")
        cs = (out["cpu_steady"] or {}).get("containers") or {}
        if out["cpu_steady"] is not None:
            for k in ("app", "db") + (("f",) if exp_f else ()):
                if not _get(cs, k, "n"):
                    miss.append(f"{k} 컨테이너({CONTAINERS[k]}) docker stats 표본 0(정상 구간)")
            if not exp_f and _get(cs, "f", "n"):
                warn.append(f"F 컨테이너 표본 {cs['f']['n']}(cond {cond} — 기대 없음)")
    out["a12_missing"], out["a12_warn"] = miss, warn
    if a12x:
        out["a12x"] = a12x_rep(d, meta, cond, off, s_min, mins)
    return out


def a12x_rep(d, meta, cond, off, s_min, mins):
    """ADR-012 2단계 회차 지표(머리말 'ADR-012 2단계') — R2 · R7 · 보장 밖 · R5. 시계 차가 없으면 R2·R7은 None(사유 notes)."""
    import numpy as np
    sys.path.insert(0, str(HERE))
    import summarize as S2   # noqa: E402
    x = {"notes": []}
    # R5 선점 p99(정상 구간 분 0~29 — main_csv 분 번호 = (s − s_min) // 60)
    arrs = [np.frombuffer(mins[m], dtype=np.float32) for m in range(STEADY_S // 60) if m in mins and len(mins[m])]
    v = np.concatenate(arrs) if arrs else np.array([], dtype=np.float32)
    x["hold_p99_steady_ms"] = float(np.partition(v, int(len(v) * 0.99))[int(len(v) * 0.99)]) if len(v) else None
    x["hold_n_steady"] = int(len(v))
    qa, qb = (((load(d / f) or {}).get("pg") or {}).get("quota_table") or {} for f in ("io-after.json", "io-before.json"))
    du, dh = (qa.get(k) - qb.get(k) if num(qa.get(k)) and num(qb.get(k)) else None for k in ("n_tup_upd", "n_tup_hot_upd"))
    qh = load(d / "quota-hot.json") or {}
    x["r5"] = {"v9": (d / "quota-hot.json").exists(), "upd_k6": du, "hot_upd_k6": dh, "hot_ratio_k6": (dh / du) if num(du) and du and num(dh) else None,
               "idx_bytes_k6": _get(qh, "k6", "idx_bytes"), "idx_bytes_end": _get(qh, "end", "idx_bytes"), "table_bytes_end": _get(qh, "end", "table_bytes")}
    if cond not in INC_CONDS:
        return x
    if off is None:
        x["notes"].append("clock-offset 없음 — R2·R7 창을 DB 시계로 옮길 수 없다")
        return x
    db = load(d / "drift-db.json") or {}
    inc = meta.get("incremental") or {}
    w0, w1 = s_min + off, s_min + STEADY_S + off
    x["r2"] = S2.inc_r2(db.get("reconcile_runs"), inc.get("source"), w0, w1) if inc.get("source") else None
    if cond == "A+X":
        x["notes"].append("A+X 증분 틱 시각 = 외부 앱(.158) 시계 — 시계 차(⑥) 미보정(task 07)")
    cfg = d / ("external-config.json" if cond == "A+X" else "app-config.json")
    knobs, ksrc = S2.safety_knobs((load(cfg) or {}).get("configprops"))
    srec = S2.self_record_max_ms([load(d / f) or {} for f in ("drift-before.json", "drift-after-k6.json", "drift-end.json")]) if cond == "A+I" else None
    if srec is None:
        x["notes"].append("안전망 자기 기록 INSERT 지표 없음 — R7 허용식에 0" + ("(외부 앱 지표 — task 07)" if cond == "A+X" else ""))
    if not isinstance(db.get("safety_runs"), list):
        x["notes"].append("drift-db.json safety_runs 없음 — R7 없음")
    x["r7"] = S2.safety_r7(db.get("safety_runs") or [], knobs, srec, w0, w1) if isinstance(db.get("safety_runs"), list) else None
    x["knobs"], x["knob_source"] = knobs, ksrc
    x["gaps"] = S2.gap_counts(db.get("inc_gaps") or [], inc.get("source"))
    return x


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
    if set_name == "adr012eff":   # ADR-012 효과 — S3e 회차(effect.json)만, 꼬리 지표 없음
        return effect_campaign(root)
    import compare as C   # noqa: E402 — 안정성 4항목·앱 메모리(jvm_rep) 재사용
    conds = [l.split()[0] for l in (root / "conditions.txt").read_text().splitlines() if l.strip()]
    expect = {"soak": 2, "soakrc": 1, "adr012s1": 3, "adr012r8": 3, "adr012s2": 3, "adr012s2x": 3}.get(set_name, 3)
    a12x = set_name in A12X_SETS
    camp = C.Camp(root, expect_reps=expect)
    js = {"campaign": root.name, "set": set_name, "conds": {}}
    out = [f"# ADR-011 꼬리 지연 지표 — `{root.name}` (--set {set_name})\n",
           "> `k6/ADR-009/scripts/tail.py`가 회차 원시 파일(주 k6·측정기 CSV · gctrace · clock-offset)과 summary.json(안정성·메모리)에서 산출. 정의는 스크립트 머리말. "
           "값만 낸다 — 가설 T1~T5의 성립·기각 판정은 담지 않는다. 조건 묶음 = ok 회차만.\n"]
    per_rep_rows, cond_rows, stab_rows = [], [], []
    a12_cond, a12_rep, a12_rcond, a12_rrep = [], [], [], []   # ADR-012 1단계(adr012s1에서만 표로)
    for c in conds:
        folder_reps = sorted(p for p in (root / c / "L4" / "S4c4200").glob("rep*") if p.is_dir() and "." not in p.name)
        summ_reps = {r.get("rep"): r for r in ((camp.summ.get(c) or {}).get("L4/S4c4200") or [])}
        if not summ_reps:
            camp.notes.append(f"{c}: summary.json 없음 — 안정성·메모리 칸은 누락(summarize.py 먼저)")
        reps = []
        for rd in folder_reps:
            t = rep_tail(rd, a12x=a12x)
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
        if set_name in A12_SETS:
            _a12_rows(c, reps, ok, a12_cond, a12_rep, a12_rcond, a12_rrep, camp.notes, r8=set_name == "adr012r8")
    out.append("## 1. 조건별(ok 회차) — 값 = 중앙값 [최소–최대] · 겹침 = 합한 겹침/느린 초(우연 기대 · 배수)\n")
    table(out, ["조건", "n(ok)", "느린 초", "26초 성분 p(24~31초)", "p ≤ 0.05 회차 수", "서버형 느린 초", "주기 없는 서버형(T4)", "PC 측정기 겹침", "PC 측정기 튐 수", "서버 측정기 겹침",
                "서버 측정기 튐 수", "Go GC 겹침"], cond_rows)
    out.append("## 2. 회차별\n")
    table(out, ["조건", "회차", "상태", "느린 초", "26초 p", "최대 성분 주기 s", "서버형", "주기 없는 서버형", "주기 초(±1.5)", "클라이언트형", "waiting 중앙 ms", "클라이언트 구간 중앙 ms",
                "PC 측정기 겹침(기대·배수·최대 배수)", "PC 튐", "서버 측정기 겹침(기대·배수·최대 배수)", "서버 튐", "Go GC 겹침(기대·배수·최대 배수)", "Go GC 사건/전체", "Go GC STW 최대 ms"], per_rep_rows)
    out.append("## 3. 안정성(ADR-010 §9 4항목) · 앱 메모리 · 1분 p99 추세\n")
    table(out, ["조건", "회차", "상태", "① Full GC·앱 멈춤 0", "② 정지 최대 < 10ms", "③ 에러·코드 0·RPS", "④ 메모리 추세 < 10%", "메모리 추세 %", "앱 메모리 최대 MiB",
                "한도 MiB", "최대 ÷ 한도 %", "분 수", "첫 5분 p99 ms", "마지막 5분 p99 ms", "증가 %", "순위 상관", "1초 p99 최대 ms", "> 50ms 초(summarize)"], stab_rows)
    if set_name in A12_SETS:
        r8 = set_name == "adr012r8"
        lab = "ADR-012 R8(1단계 보강 — 탐지 모양)" if r8 else {"adr012s2": "ADR-012 2단계", "adr012s2x": "ADR-012 2'단계"}.get(set_name, "ADR-012 1단계")
        out.append(f"## 4. {lab} — 후반 꼬리 · 정상 구간 CPU(조건별 ok 회차 — 중앙값 [최소–최대])\n")
        out.append("> 후반 = 정상 구간 첫 초 s_min(t0의 올림) + 900초 이상 1,800초 미만 · 빈 초 = 응답 0인 초 · 얇은 초 = 계획 도착률 절반 미만(빈 + 얇은 > %d초면 회차 상태에 판정 불가 사유) · "
                   "CPU = docker stats 평균 %%(100%% = 1코어, 정상 구간 [t0, 주 k6 끝]) · F = seatlab-reconciler-1 · F 포화 = 표본 CPU %% ≥ 한도 × 100 × %g(한도 출처 = 회차 표 'F 한도 출처'). "
                   "정의는 스크립트 머리말 'ADR-012'.\n" % (GAP_MAX_S, SAT_FRAC))
        table(out, A12_HEAD + ["결측·주의(회차)"], a12_cond)
        out.append(f"## 5. {lab} — 대조 바퀴({'R8 주 지표 = 후반 ① 시간' if r8 else 'R1'} — 조건·출처별 ok 회차 — 중앙값 [최소–최대])\n")
        out.append("> 출처 reconcile-app = 앱 안 C · reconcile-proc = F. 바퀴 수(안) = 시작·끝 모두 창(정상 구간 [s_min, s_min + 1800) · 후반 [s_min + 900, s_min + 1800)) 안 — "
                   "중앙·최대도 이것만 · 걸침 = 창 경계에 걸친 바퀴 수 · 바퀴 시간 = finished − started(초). "
                   "바퀴 중/밖 = 정상 구간 표본 중 그 출처의 어느 바퀴(걸침 포함) [시작, 끝]과 겹친 것/아닌 것 — 표본 칸은 그 수(CPU = docker stats 표본) · Hikari는 업무 앱 풀. "
                   "기대 출처(회차 meta.json cond)는 바퀴가 없어도 행을 두고 상태 칸에 사유를 적는다."
                   + (" ① = chunk_ms_total(쿼터 청크) · ② = orphan_ms_total(행 없음 갈래) — 바퀴 기록 열(ms), 창 안 바퀴의 중앙·최대." if r8 else "") + "\n")
        rhead = A12_RHEAD + (A12_R8_HEAD if r8 else [])
        table(out, rhead, a12_rcond or [["(대조 바퀴 없음)"] + [""] * (len(rhead) - 1)])
        out.append(f"## 6. {lab} — 회차별\n")
        table(out, ["조건", "회차", "상태"] + A12_HEAD[2:] + ["F 한도 출처"] + (["탐지 모양(reconciler-config)"] if r8 else []), a12_rep)
        rh = ["조건", "회차", "상태", "출처"] + rhead[3:]
        table(out, rh, a12_rrep or [["(대조 바퀴 없음)"] + [""] * (len(rh) - 1)])
    if a12x:
        a12x_tables(out, js, lab, camp.notes)
    out.append("## 누락·의심\n")
    out += [f"- {n}" for n in camp.notes] or ["- 자동 점검에서 걸린 항목 없음"]
    js["notes"] = camp.notes
    (root / "TAIL.md").write_text("\n".join(out) + "\n")
    (root / "tail.json").write_text(json.dumps(js, ensure_ascii=False, indent=1, default=str))
    print(root / "TAIL.md")
    return 0


A12_HEAD = ["조건", "n(ok)", "후반 느린 초", "후반 서버형 느린 초", "후반 1초 p99 최대 ms", "후반 관측 초", "후반 빈 초", "후반 얇은 초", "앱 CPU %", "DB CPU %",
            "F CPU %", "F CPU 최대 %", "F ≥ 한도 95% 표본 비율", "F 한도 코어", "CPU 표본 수", "표본 간격 최대 s"]
A12_RHEAD = ["조건", "출처", "n(ok)", "바퀴 수(전체)", "바퀴 수(정상 — 안)", "걸침(정상)", "바퀴 시간 중앙 s(정상)", "바퀴 시간 최대 s(정상)", "바퀴 수(후반 — 안)", "걸침(후반)",
             "바퀴 시간 중앙 s(후반)", "바퀴 시간 최대 s(후반)", "pending > 0 비율 바퀴 중", "pending > 0 비율 바퀴 밖", "Hikari 표본 바퀴 중", "Hikari 표본 바퀴 밖",
             "CPU 표본 바퀴 중", "CPU 표본 바퀴 밖", "DB CPU % 바퀴 중", "DB CPU % 바퀴 밖", "앱 CPU % 바퀴 중", "앱 CPU % 바퀴 밖", "F CPU % 바퀴 중", "F CPU % 바퀴 밖",
             "F CPU 최대 % 바퀴 중", "F ≥ 한도 95% 비율 바퀴 중"]


# ---- ADR-012 2단계·2' 7절(a12x) · 효과 세트 -----------------------------------------------------------------
A12X_HEAD = ["조건", "n(ok)", "증분 틱 수(정상)", "틱 ms 중앙", "R2 첫 5분 ms", "R2 마지막 5분 ms", "R2 반증 회차", "후보 쌍 중앙(첫5 → 끝5)",
             "안전망 실행", "R7 ① 허용식 초과", "R7 ② 벽시계 > cap", "R7 ③ 복구 쌍 > cap", "R7 ④ 청크 > 500ms", "R7 ⑤ 추세 반증 회차", "실행 db_ms 중앙", "실행 db_ms 최대",
             "첫 5분 → 마지막 5분 db_ms", "청크 최대 ms", "표본 db_ms 중앙", "적체 id 최대", "바퀴 완료", "이월 최대 · 넘침", "경계 건너뜀 id",
             "보장 밖(kind 행)", "선점 p99 ms(정상)", "noc 대비 %", "HOT 비율(k6)", "updated_at 인덱스 MB(끝)"]


def _a12x_vals(t):
    x = t.get("a12x") or {}
    g = lambda *k: _get(x, *k)
    gp = "; ".join(f"{k} {v['rows']}" for k, v in sorted((x.get("gaps") or {}).items())) if isinstance(x.get("gaps"), dict) else None
    return {"ticks": g("r2", "ticks"), "tick_ms": g("r2", "tick_ms_median"), "f5": g("r2", "first5_ms"), "l5": g("r2", "last5_ms"), "r2v": g("r2", "violation"),
            "pf": g("r2", "pairs_first5"), "pl": g("r2", "pairs_last5"),
            "runs": g("r7", "runs"), "v1": g("r7", "v1_db_over_tol"), "v2": g("r7", "v2_wall_over_cap"), "v3": g("r7", "v3_pairs_over_cap"), "v4": g("r7", "v4_chunk_over_500"),
            "v5": g("r7", "v5_trend"), "db_med": g("r7", "db_ms_total", "median"), "db_max": g("r7", "db_ms_total", "max"), "f5db": g("r7", "first5_db_ms"), "l5db": g("r7", "last5_db_ms"),
            "chunk": g("r7", "chunk_ms_max", "max"), "smp": g("r7", "sample_db_ms", "median"), "lag": g("r7", "lag_ids", "max"), "laps": g("r7", "laps_completed"),
            "carry": g("r7", "carry_out_max"), "drop": g("r7", "carry_dropped"), "skip": g("r7", "recent_skip_ids"), "gaps": gp,
            "p99": x.get("hold_p99_steady_ms"), "hot": g("r5", "hot_ratio_k6"), "idx": (g("r5", "idx_bytes_end") / 1048576) if num(g("r5", "idx_bytes_end")) else None,
            "v9": g("r5", "v9")}


def a12x_tables(out, js, lab, notes):
    """7절 — 조건별(ok 회차 — 중앙값 [최소–최대] · 위반 수는 합 · 반증 회차 수) · 회차별. R5 noc 대비 = 조건 이름이 noc-로 시작하는 조건의 ok 회차 선점 p99 중앙값 기준."""
    out.append(f"## 7. {lab} — R2 증분 틱 · R7 안전망 · 보장 밖 기록 · R5 선점 비용(정상 구간 — 조건별 ok 회차)\n")
    out.append("> 정의는 스크립트 머리말 'ADR-012 2단계'. R7 ①~④ = 위반 실행 수(ok 회차 합) · ⑤·R2 = 반증 회차 수. 증분·안전망이 없는 조건(noc·c)은 R2·R7 칸 '—'. "
               "noc 대비 % = (조건 중앙값 ÷ noc 중앙값 − 1) × 100(§5 R5 허용선 +10%). 판정(성립·기각)은 담지 않는다.\n")
    noc = [c for c in js["conds"] if c.startswith("noc-")]
    noc_p99 = None
    if noc:
        v = [(t.get("a12x") or {}).get("hold_p99_steady_ms") for t in js["conds"][noc[0]] if t["status"] == "ok"]
        v = [x for x in v if num(x)]
        noc_p99 = statistics.median(v) if v else None
    else:
        notes.append("7절: noc 조건(noc-…) 없음 — R5 noc 대비 % 없음")
    cond_rows, rep_rows = [], []
    for c, reps in js["conds"].items():
        ok = [t for t in reps if t["status"] == "ok"]
        vals = [_a12x_vals(t) for t in ok]
        has_inc = any(_get(t, "a12_expect", "sources") for t in reps)
        col = lambda k, d=1: _rngd([v[k] for v in vals], d)
        sm = lambda k: (str(sum(v[k] for v in vals if num(v[k]))) + (f" · 누락 {sum(1 for v in vals if not num(v[k]))}" if any(not num(v[k]) for v in vals) else "")) if vals else "누락"
        cnt = lambda k: f"{sum(1 for v in vals if v[k] is True)}/{len(vals)}"
        p99m = statistics.median([v["p99"] for v in vals if num(v["p99"])]) if any(num(v["p99"]) for v in vals) else None
        inc_cells = [col("ticks", 0), col("tick_ms"), col("f5"), col("l5"), cnt("r2v"), f'{col("pf", 0)} → {col("pl", 0)}',
                     col("runs", 0), sm("v1"), sm("v2"), sm("v3"), sm("v4"), cnt("v5"), col("db_med"), col("db_max"), f'{col("f5db")} → {col("l5db")}',
                     col("chunk"), col("smp"), col("lag", 0), col("laps", 0), f'{col("carry", 0)} · {sm("drop")}', sm("skip"),
                     "; ".join(sorted({v["gaps"] for v in vals if v["gaps"]})) or ("0" if vals else "누락")]
        if not has_inc:
            inc_cells = ["—"] * len(inc_cells)
        v9 = any(v["v9"] for v in vals)   # updated_at 인덱스는 V9 방식(trg·stmt)만 — 그 밖은 '—'(누락 아님)
        cond_rows.append([c, len(ok)] + inc_cells + [col("p99", 2), fx((p99m / noc_p99 - 1) * 100, 1) if num(p99m) and noc_p99 else "누락",
                                                     col("hot", 3), col("idx", 1) if v9 else "—"])
        for t in reps:
            v = _a12x_vals(t)
            nt = (t.get("a12x") or {}).get("notes") or []
            ic = [fx(v["ticks"], 0), fx(v["tick_ms"]), fx(v["f5"]), fx(v["l5"]), fx(v["r2v"]), f'{fx(v["pf"], 0)} → {fx(v["pl"], 0)}', fx(v["runs"], 0),
                  fx(v["v1"], 0), fx(v["v2"], 0), fx(v["v3"], 0), fx(v["v4"], 0), fx(v["v5"]), fx(v["db_med"]), fx(v["db_max"]), f'{fx(v["f5db"])} → {fx(v["l5db"])}',
                  fx(v["chunk"]), fx(v["smp"]), fx(v["lag"], 0), fx(v["laps"], 0), f'{fx(v["carry"], 0)} · {fx(v["drop"], 0)}', fx(v["skip"], 0), v["gaps"] or "0"]
            if not _get(t, "a12_expect", "sources"):
                ic = ["—"] * len(ic)
            rep_rows.append([c, Path(t["dir"]).name, " · ".join([t["status"] or "누락"] + nt)] + ic
                            + [fx(v["p99"], 2), fx((v["p99"] / noc_p99 - 1) * 100, 1) if num(v["p99"]) and noc_p99 else "누락", fx(v["hot"], 3), fx(v["idx"], 1) if v["v9"] else "—"])
            notes += [f"{c} {Path(t['dir']).name} ADR-012 2단계: {n}" for n in nt]
    table(out, A12X_HEAD, cond_rows)
    table(out, ["조건", "회차", "상태"] + A12X_HEAD[2:], rep_rows)


EFF_KINDS = ("D", "L1", "L2", "H", "AH", "AL", "AE")


def effect_campaign(root):
    """--set adr012eff: 조건 폴더마다 L*/S3e*/rep* 회차의 effect.json(summarize.effect) → TAIL.md · tail.json. 값만 낸다(R4·R6 성립·기각은 메인 몫)."""
    conds = [l.split()[0] for l in (root / "conditions.txt").read_text().splitlines() if l.strip()]
    js, notes, rows, crow = {"campaign": root.name, "set": "adr012eff", "conds": {}}, [], [], []
    for c in conds:
        reps = sorted(p for p in (root / c).glob("L*/S3e*/rep*") if p.is_dir() and "." not in p.name)
        recs = []
        for rd in reps:
            e = load(rd / "effect.json")
            st = (rd / "status").read_text().strip() if (rd / "status").exists() else None
            rec = {"dir": str(rd), "status": st, "effect": e}
            recs.append(rec)
            if not isinstance(e, dict):
                notes.append(f"{c} {rd.name}: effect.json 없음")
                rows.append([c, rd.name, st or "누락"] + ["누락"] * 12)
                continue
            k = e.get("kinds") or {}
            kt = lambda x: (lambda d: f'{d["injected"]}/{d["repaired"]}/{d["end_matched"]}' if d else "—")(k.get(x))
            r4, r6 = e.get("r4") or {}, e.get("r6") or {}
            srcs = sorted({s for d in k.values() for s in d.get("repaired_by") or []})
            rows.append([c, rd.name, st or "누락", " · ".join(f"{x} {kt(x)}" for x in EFF_KINDS if k.get(x)), ", ".join(srcs) or "-",
                         f'{fx(r4.get("violations"), 0)}/{fx(r4.get("pairs"), 0)}', fx(r4.get("undecidable"), 0), fx(r4.get("out_of_guarantee"), 0),
                         "; ".join(f"{b} {n}" for b, n in (r4.get("by_basis") or {}).items()) or "-", fx(r4.get("lap_max_s")),
                         f'{fx(r6.get("violations"), 0)}/{fx(r6.get("judged"), 0)}', fx(r6.get("excluded_out_of_guarantee"), 0),
                         f'{fx(e.get("false_repairs"), 0)} · {fx(e.get("noninjected_mismatch"), 0)}', "; ".join(e.get("unexpected") or []) or "-",
                         "; ".join(f"{g} {v['rows']}" for g, v in sorted((e.get("gaps") or {}).items())) or "0"])
            if r4.get("clock_note"):
                notes.append(f"{c} {rd.name}: {r4['clock_note']}")
        js["conds"][c] = recs
        ok = [r for r in recs if r["status"] == "ok" and isinstance(r["effect"], dict)]
        if len(ok) != 3:
            notes.append(f"{c}: ok 회차 {len(ok)}/3 — {[(Path(r['dir']).name, r['status']) for r in recs]}")
        g = lambda r, *k: _get(r["effect"], *k)
        crow.append([c, len(ok), sum(g(r, "r4", "violations") or 0 for r in ok), sum(g(r, "r4", "undecidable") or 0 for r in ok),
                     sum(g(r, "r4", "out_of_guarantee") or 0 for r in ok), sum(g(r, "r6", "violations") or 0 for r in ok), sum(g(r, "r6", "judged") or 0 for r in ok),
                     sum(1 for r in ok if g(r, "injected_mismatch_end")), sum(g(r, "false_repairs") or 0 for r in ok), sum(1 for r in ok if g(r, "unexpected"))])
    out = [f"# ADR-012 효과 지표 — `{root.name}` (--set adr012eff)\n",
           "> `k6/ADR-009/scripts/tail.py`가 회차 effect.json(summarize.py effect — S3e DB 직접 주입 D·L1·L2·H + 앱 경로 주입 AH·AL·AE)에서 산출. 값만 낸다 — R4·R6 판정은 메인 몫. "
           "종류 칸 = 주입/복구/끝 일치. R4 = 위반/쌍(보장 밖 쌍 포함 — 기준이 다르다: 증분 '봄'·보장 안 = 주기 + 10초 · 그 밖 = 적체 없으면 120초 · 있으면 2 × 바퀴). "
           "R6 = 위반/판정(증분 '봄'·보장 안 쌍의 첫 후보 출처 ≠ 그 셀 증분 source). 정의는 summarize.inc_effect.\n",
           "## 1. 조건별(ok 회차 합)\n"]
    table(out, ["조건", "n(ok)", "R4 위반", "R4 판정 불가", "보장 밖 쌍", "R6 위반", "R6 판정 쌍", "끝 주입 쌍 불일치 회차", "오탐 복구", "unexpected 회차"], crow)
    out.append("## 2. 회차별\n")
    table(out, ["조건", "회차", "상태", "종류(주입/복구/끝 일치)", "복구 출처", "R4 위반/쌍", "R4 판정 불가", "보장 밖", "R4 기준별 쌍", "바퀴 최대 s", "R6 위반/판정",
                "R6 보장 밖 제외", "오탐 복구 · 주입 밖 끝 어긋남", "unexpected", "보장 밖 기록(kind 행)"], rows)
    out.append("## 누락·의심\n")
    out += [f"- {n}" for n in notes] or ["- 자동 점검에서 걸린 항목 없음"]
    js["notes"] = notes
    (root / "TAIL.md").write_text("\n".join(out) + "\n")
    (root / "tail.json").write_text(json.dumps(js, ensure_ascii=False, indent=1, default=str))
    print(root / "TAIL.md")
    return 0


def _gap(t):
    c = t.get("cpu_steady") or {}
    g = [x for x in (c.get("head_gap_s"), c.get("max_inner_gap_s"), c.get("tail_gap_s")) if num(x)]
    return max(g) if g else None


def _a12_vals(t):
    g = lambda *k: _get(t, *k)
    f = lambda k: g("cpu_steady", "containers", "f", k)
    return [g("late", "slow_secs"), g("late", "server_slow_secs"), g("late", "sec_p99_max_ms"), g("late", "secs"), g("late", "secs_empty"), g("late", "secs_thin"),
            g("cpu_steady", "containers", "app", "mean"), g("cpu_steady", "containers", "db", "mean"), f("mean"), f("max"), f("sat_share"), f("limit_cores"),
            g("cpu_steady", "samples"), _gap(t)]


A12_D = [0, 0, 1, 0, 0, 0, 1, 1, 1, 1, 3, 1, 0, 1]


def _a12_rvals(r):
    g = lambda *k: _get(r, *k)
    return [g("total"), g("steady", "n"), g("steady_crossing"), g("steady", "median_s"), g("steady", "max_s"), g("late", "n"), g("late_crossing"), g("late", "median_s"),
            g("late", "max_s"), g("hikari", "in_pending_share"), g("hikari", "out_pending_share"), g("hikari", "in_n"), g("hikari", "out_n"),
            g("stats_samples", "in_n"), g("stats_samples", "out_n"), g("cpu", "db", "in_mean"), g("cpu", "db", "out_mean"),
            g("cpu", "app", "in_mean"), g("cpu", "app", "out_mean"), g("cpu", "f", "in_mean"), g("cpu", "f", "out_mean"), g("cpu", "f", "in_max"), g("cpu", "f", "in_sat_share")]


A12_RD = [0, 0, 0, 2, 2, 0, 0, 2, 2, 3, 3, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 3]
A12_SETS = ("adr012s1", "adr012r8", "adr012s2", "adr012s2x")
A12X_SETS = ("adr012s2", "adr012s2x")   # ADR-012 2단계·2' — 7절(a12x)
# R8(--set adr012r8 — 머리말 'R8'): 대조 바퀴 표 끝에 ①·② 갈래 시간 열
A12_R8_HEAD = ["① 시간 중앙 ms(정상)", "① 시간 최대 ms(정상)", "① 시간 중앙 ms(후반)", "① 시간 최대 ms(후반)",
               "② 시간 중앙 ms(정상)", "② 시간 최대 ms(정상)", "② 시간 중앙 ms(후반)", "② 시간 최대 ms(후반)"]
A12_R8_RD = [1] * len(A12_R8_HEAD)


def _a12_r8vals(r):
    g = lambda *k: _get(r, *k)
    return [g("branch_ms", b, w, m) for b in ("chunk", "orphan") for w in ("steady", "late") for m in ("median_ms", "max_ms")]


def _detect_shape(t):
    p = (load(Path(t["dir"]) / "reconciler-config.json") or {}).get("configprops") or []
    v = [_get(x, "properties", "counterDrift", "reconcile", "detectShape") for x in p if isinstance(x, dict) and x.get("prefix") == "seat.hold"]
    return v[0] if v and v[0] else "누락"


def _a12_why(t):
    """회차 상태 칸에 붙일 사유 — 후반·정상 구간 공백(late_unavailable·steady_sparse) · 결측(a12_missing). 원시 읽기에서 멈춘 회차(a12_missing 없음)는 notes."""
    w = [t[k] for k in ("late_unavailable", "steady_sparse") if t.get(k)]
    w += t["a12_missing"] if "a12_missing" in t else [f"결측: {n}" for n in t.get("notes") or ["ADR-012 지표 없음"]]
    return w


A12_F = [i for i, h in enumerate(A12_HEAD[2:]) if h.startswith("F ")]          # F 열(값 목록 기준) — F를 기대하지 않는 회차는 '—'(누락 아님)
A12_RF = [i for i, h in enumerate(A12_RHEAD[3:]) if h.startswith("F ")]
NO_RUNS = {"total": 0, "steady": {"n": 0}, "steady_crossing": 0, "late": {"n": 0}, "late_crossing": 0}   # drift-db는 있는데 그 출처 바퀴가 없음 = 0(누락 아님)


def _rounds_of(t, src):
    r = t.get("rounds")
    return None if r is None else (r.get(src) or NO_RUNS)


def _a12_rows(c, reps, ok, cond_rows, rep_rows, rcond_rows, rrep_rows, notes, r8=False):
    """ADR-012 1단계 표 행(조건별·회차별) — 조건 묶음 = ok 회차만(위 표와 같은 규칙). 사유(_a12_why)는 회차 행 상태 칸 · 조건 행 '결측·주의' 칸 · 누락·의심 목록에 —
    빈 칸으로 두지 않는다. 기대 출처(a12_expect)는 바퀴가 0이어도 행을 만든다(바퀴 수 0 + 상태 칸 사유). F를 기대하지 않는 회차(cond ≠ A+F)의 F 열은 '—'."""
    exp_f = lambda t: _get(t, "a12_expect", "f_container") is not False
    dash = lambda cells, idx, on: [("—" if on and i in idx else x) for i, x in enumerate(cells)]
    vals = [_a12_vals(t) for t in ok]
    why = {Path(t["dir"]).name: _a12_why(t) for t in reps}
    warn = {Path(t["dir"]).name: [f"주의 {w}" for w in t.get("a12_warn") or []] for t in reps}
    no_f = bool(reps) and not any(exp_f(t) for t in reps)
    cond_rows.append([c, len(ok)] + dash([_rngd([v[i] for v in vals], A12_D[i]) for i in range(len(A12_D))], A12_F, no_f)
                     + ["; ".join(f"{k}: {' · '.join(why[k] + warn[k])}" for k in why if why[k] or warn[k]) or "없음"])
    for t in reps:
        name = Path(t["dir"]).name
        st = " · ".join([t["status"] or "누락"] + why[name])
        lim = _get(t, "cpu_steady", "containers", "f", "limit_source")
        rep_rows.append([c, name, st] + dash([fx(v, A12_D[i]) for i, v in enumerate(_a12_vals(t))], A12_F, not exp_f(t))
                        + [lim or ("—" if not exp_f(t) else "누락(F 표본 없음)")] + ([_detect_shape(t) if exp_f(t) else "—"] if r8 else []))
        notes += [f"{c} {name} ADR-012: {w}" for w in why[name]] + [f"{c} {name} ADR-012 주의: {w}" for w in t.get("a12_warn") or []]
    srcs = sorted({s for t in reps for s in ((t.get("rounds") or {}).keys())} | {_get(t, "a12_expect", "source") for t in reps if _get(t, "a12_expect", "source")}
                  | {x for t in reps for x in (_get(t, "a12_expect", "sources") or [])})   # ADR-012 새 조건: 증분·안전망 둘 다
    for src in srcs:
        rv = [_a12_rvals(_rounds_of(t, src)) for t in ok]
        rcond_rows.append([c, src, len(ok)] + dash([_rngd([v[i] for v in rv], A12_RD[i]) for i in range(len(A12_RD))], A12_RF, no_f)
                          + ([_rngd([_a12_r8vals(_rounds_of(t, src))[i] for t in ok], A12_R8_RD[i]) for i in range(len(A12_R8_RD))] if r8 else []))
        for t in reps:
            name = Path(t["dir"]).name
            rrep_rows.append([c, name, " · ".join([t["status"] or "누락"] + why[name]), src]
                             + dash([fx(v, A12_RD[i]) for i, v in enumerate(_a12_rvals(_rounds_of(t, src)))], A12_RF, not exp_f(t))
                             + ([fx(v, A12_R8_RD[i]) for i, v in enumerate(_a12_r8vals(_rounds_of(t, src)))] if r8 else []))


def _rngd(vals, d):
    """_rng와 같은 모양(중앙값 [최소–최대] · 누락 수)에 소수 자리 d를 고정한 것(ADR-012 표 — CPU·ms 값이 정수로 뭉개지지 않게)."""
    v = [x for x in vals if num(x)]
    if not v:
        return "누락"
    s = fx(statistics.median(v), d) if len(v) == 1 else f"{fx(statistics.median(v), d)} [{fx(min(v), d)}–{fx(max(v), d)}]"
    return s + (f" · 누락 {len(vals) - len(v)}" if len(v) < len(vals) else "")


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
        print(json.dumps(rep_tail(argv[1], a12x=True), ensure_ascii=False, indent=1, default=str))
        return 0
    if len(argv) == 3 and argv[0] == "--set" and argv[1] in ("k6ver", "pretouch", "soak", "soakrc", "adr012s1", "adr012r8", "adr012s2", "adr012s2x", "adr012eff"):
        return campaign(argv[1], argv[2])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
