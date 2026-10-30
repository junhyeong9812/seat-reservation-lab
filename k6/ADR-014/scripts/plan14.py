#!/usr/bin/env python3
"""ADR-014 계획의 단일 출처 — 셀(주 16 + 비용 4) · 회차 순서(회차 우선 · 묶음 순서 · 방식 회전) · 공통 난수 씨앗 · 셀 렌더(앱 설정 · k6 환경 ·
탐침 일정 · 사용자·좌석 대역 · 판정 창) · 기대 설정(configprops 대조) · dry-run 점검. run.sh·campaign.sh·summarize.py·selftest.py가 모두 이 파일을 읽는다
(값을 두 곳에 두지 않는다 — ADR-013 plan13.py와 같은 구조).
기준: docs/adr/ADR-014-hold-expiry.md '측정 전 고정 규칙'(측정-1 조건 · 측정-2 셀·D_arr·순서 · 측정-3 부하 모델 · 측정-4 고정값 · 측정-9 시작 조건 ③⑥).

    plan14.py --order [--knobs <json>]                         → 60행 "순번 회차 셀 방식"(campaign.sh가 order.txt로 남기고 이어서 실행 때 다시 계산해 대조)
    plan14.py --render <A|B|C|D> <셀> <회차> [--knobs <json>]   → 회차 계획 JSON(표준 출력 — run.sh가 plan.json으로 남긴다)
    plan14.py --dry-run [--knobs <json>] [--json]               → 60자리 계획 · 예상 시간 · 탐침 일정 점검(마지막 탐침 요청 ≤ t0 + D_arr − 5초 · 좌석 대역)
                                                                  · 문제 있으면 exit 1(시작 조건 ③)
    plan14.py --config-check <A|B|C|D> <셀> <app-config.json>   → 기대 설정 대조 사유 JSON 한 줄 {"reasons":[…],"detail":[…]} · exit 0 일치 · 1 불일치 · 2 판정 불가
    plan14.py --darr-extend '<{"셀": T95_추정초, …}>'            → 시작 조건 ⑥(리뷰 MR-13)의 D_arr′ = ⌈T95 ÷ 0.6 ÷ 60⌉ × 60(줄이지 않는다) — knobs JSON 조각을 낸다

knobs(시작 조건 ⑤⑥이 캠페인 전에 한 번만 정하는 값 — campaign.sh plan.json에 고정 · 회차 plan.json에도 남는다):
    {"d_arr": {"a50-t300": 2700, …}, "map_rate": 10, "max_vus": {"blind": 14000, "map": 14000, "probe": 14000}, "state_expired_every_s": 1}
    d_arr는 늘리기만(측정-2 'D_arr를 줄이지 않는다') · map_rate는 줄이기만(측정-9 ⑥ 'PC 수신 > 50MB/s면 줄임') · max_vus는 14,000 이상(측정-4 MR-11) ·
    state_expired_every_s ∈ {1, 10}(측정-5 머리 '표본 방식 변경' — expired_rows 하위 질의를 10초 간격 별도 표본으로)."""
import json
import math
import sys
from pathlib import Path

# ---- 고정값(측정-1 · 측정-2 · 측정-3 · 측정-4 — 측정 뒤 바꾸지 않는다) -------------------------------------------------------------
METHODS = {"A": "sweep", "B": "lazy", "C": "lazy-sweep", "D": "derived"}       # 측정-1 방식 축(Q9)
MODE_ENUM = {"A": "SWEEP", "B": "LAZY", "C": "LAZY_SWEEP", "D": "DERIVED"}     # configprops 표기(ExpiryMode enum)
MAIN = {   # 측정-2 주 셀(D_arr = 리뷰 MR-13 반영 값)
    "a20-t300": {"abandon": 0.2, "ttl_s": 300, "d_arr_s": 1200},
    "a50-t300": {"abandon": 0.5, "ttl_s": 300, "d_arr_s": 2220},
    "a20-t30": {"abandon": 0.2, "ttl_s": 30, "d_arr_s": 240},
    "a50-t30": {"abandon": 0.5, "ttl_s": 30, "d_arr_s": 420},   # 시작 조건 ⑥ 적용(정식 스모크 2026-10-29 — T95 A 216.20초 = 360초의 60.1% → --darr-extend 420)
}                                                                  # 나머지 셋은 a50-t30 라운드 길이 외삽 T95_추정이 60% 안 — 그대로(측정-2 ⑥ 적용 기록)
COST = {"S4c4200": {"ttl_s": 3600, "rate": 4200, "steps": 12, "step_s": 30, "ramp_s": 60, "ramp_from": 50, "seed_schedules": 200}}   # 측정-2 비용 셀(S4c 그대로)
GROUPS = ["a20-t30", "a50-t30", "a20-t300", "a50-t300", "S4c4200"]   # 측정-2 '회차 안 셀 묶음 순서'
REPS = 3
ABCD = "ABCD"
EXPIRY_INTERVAL = "10s"                                                # 측정-1 모든 셀 고정(application.yml:36)
FLYWAY = ["classpath:db/migration", "classpath:db/migration-updated-at-trigger", "classpath:db/migration-incremental",
          "classpath:db/migration-seat-map"]                            # 측정-1: 업무 앱 기본 위치(application.yml:16) + V11 위치(모든 셀 같게)
POOL = 10
TOMCAT_MAX_CONNECTIONS = 20000                                         # 명세 §10 R5 ①(2026-10-30 확인 스모크): 측정 compose에서만 · 네 방식 · 모든 셀 같게(앱 기본 8,192 —
                                                                       # 동시 VU 최대 9,254가 VU마다 keep-alive 연결을 쥐어 넘었다 · compose.expiry.yml 머리말)
APP_NOFILE = 65536                                                     # 앱 컨테이너 nofile(soft = hard — 오버레이 ulimits): 연결 20,000 + DB 풀 · Redis · jar · 로그 여유
NOFILE_MARGIN = 1000                                                   # 대조 하한 = 연결 상한 + 이 여유(APP_NOFILE이 이보다 작으면 모듈 적재에서 멈춘다)
assert APP_NOFILE >= TOMCAT_MAX_CONNECTIONS + NOFILE_MARGIN
SEATS = 10000
BLIND_RATE, MAP_RATE, RETRIES = 300, 10, 3                              # 측정-3.1 · 측정-4
HOT_RATIO, HOT_SHARE = 0.2, 0.7
RHO, REVISIT_MAX_S = 0.2, 20                                           # 측정-3.1 재방문(MQ5)
THINK = {300: (30, 240), 30: (3, 24)}                                  # 측정-3.1 생각 시간
GRACE_STOP = {300: 330, 30: 10}                                        # 측정-3.1 gracefulStop
PROBE_START = {300: 60, 30: 10}                                        # 측정-3.2 시작 칸 [t0 + 60 | 10, t0 + D_arr − TTL − 25)
PROBE_END_MARGIN = 25
PROBE_RATE = {300: {"pr1": 0.25, "pr2": 0.25, "pr3": 0.2}, 30: {"pr1": 1.0, "pr2": 1.0, "pr3": 1.0}}
DELTAS = [1, 3, 5, 8, 12, 20]                                          # PR-1·PR-2 δ(초 — 차례대로 돌림)
DELTAS_C = [-1.0, -0.3, 0.3, 1.0]                                      # PR-3 δc(초)
V_AFTER_S = 0.3                                                        # PR-3 경쟁자 v = e + 0.3초
TOL_MS = {"pr1": 100, "pr2": 100, "pr3": 50}                           # 측정-3.2 칸별 송신 오차 허용(리뷰 MR-2)
DROP_MAX = 0.10                                                        # 칸별 뺀 비율 > 10%면 probe-timing-bad
LAST_REQ_MARGIN_S = 5                                                  # 마지막 탐침 요청 ≤ t0 + D_arr − 5초(시작 조건 ③)
USERS = {"blind": 1_000_000, "map": 3_000_000, "pr1": 5_000_000, "pr2": 6_000_000, "pr3u": 7_000_000, "pr3v": 8_000_000}   # 측정-3.1 '흐름별 번호 대역'(task 04)
PROBE_SCHEDULE = 2                                                     # 탐침 회차(측정-0)
PROBE_SEAT0 = {"pr1": 10001, "pr2": 11001, "pr3": 13001}               # 회차 2 좌석 = 10,001~20,000(LoadtestDataService.reset 좌석 id 규칙)
PROBE_SEATS_PER = {"pr1": 1, "pr2": 3, "pr3": 1}
PRE_VUS_T300 = {"blind": 12000, "map": 1000, "probe": 100}            # TTL 5분 칸(2026-10-29 사용자 결정): 만석 뒤 확정 세션이 생각 시간(30~240초) 동안 VU를 쥐어 동시 반복이
                                                                       # 수천~1만에 이를 수 있다(추정) — maxVUs 14,000 안에서 미리 올리고 확인 스모크 a50-t300 1회로 실측(VU 최대 · dropped)
MAX_VUS_MIN = 14000                                                    # 측정-4 MR-11(S3 선례)
PRE_VUS = {"blind": 4000, "map": 400, "probe": 100}                    # 미리 만든 VU(TTL 30초 칸) — 모자라면 k6가 실행 중에 VU를 늦게 만들어 그 사이 반복을 버린다(dropped_iterations).
                                                                       # 스모크 a50-t30(10-29 · 1Gbps) blind 동시 반복 약 2,244 > 2,000 → t0 + 17~20초 blind 7건 버림(maxVUs 14,000은 안 닿음) · ×1.5 넘게 올림
STATE_EVERY = (1, 10)
CONVERGE_EXTRA_S = 22                                                  # 수렴 대기 = TTL + 20 + 2(ADR-009 run.sh:861)
OTHER_S = 181                                                          # 측정-2 회차 시간 추정의 '그 밖'(기동~수집 — 추정)
COST_EST_S = 13 * 60                                                   # 측정-2 비용 셀 회차 추정
T_X = {"T90": 9000, "T95": 9500, "T99": 9900, "T100": 10000}            # 측정-0 T_x(1만 석 기준)


def load_knobs(path):
    if not path:
        return {}
    k = json.loads(Path(path).read_text()) if Path(path).exists() else json.loads(path)
    bad = []
    for c, v in (k.get("d_arr") or {}).items():
        if c not in MAIN or not isinstance(v, int) or v < MAIN[c]["d_arr_s"] or v % 60:
            bad.append(f"d_arr.{c}={v}(주 셀 · 정수 분 · 기본값 이상만 — 측정-2 MR-13)")
    mr = k.get("map_rate")
    if mr is not None and (not isinstance(mr, (int, float)) or not 0 < mr <= MAP_RATE):
        bad.append(f"map_rate={mr}(0 < · ≤ {MAP_RATE} — 측정-9 ⑥ 줄이기만)")
    for f, v in (k.get("max_vus") or {}).items():
        if f not in ("blind", "map", "probe") or not isinstance(v, int) or v < MAX_VUS_MIN:
            bad.append(f"max_vus.{f}={v}(≥ {MAX_VUS_MIN})")
    se = k.get("state_expired_every_s", 1)
    if se not in STATE_EVERY:
        bad.append(f"state_expired_every_s={se}(1 | 10)")
    extra = set(k) - {"d_arr", "map_rate", "max_vus", "state_expired_every_s", "note"}
    if extra:
        bad.append(f"모르는 knobs 키 {sorted(extra)}")
    if bad:
        raise SystemExit("knobs 형식: " + " · ".join(bad))
    return k


def order(knobs=None):
    """측정-2 순서: 회차 우선 · 묶음 순서 GROUPS · 묶음 g(0부터) · 회차 r(1부터)의 방식 = ABCD를 (r + g) mod 4칸 회전."""
    rows, seq = [], 0
    for r in range(1, REPS + 1):
        for g, cell in enumerate(GROUPS):
            rot = (r + g) % 4
            for m in ABCD[rot:] + ABCD[:rot]:
                seq += 1
                rows.append((seq, r, cell, m))
    return rows


def probe_plan(ttl, d_arr):
    """측정-3.2 탐침 일정 — 탐침 종류마다 시작 칸 [start, end)에서 고정 간격 1/rate초로 시작(k6 constant-arrival-rate: startTime = start · duration = end − start ·
    시작 시각 = start + i/rate < end). δ(·δc)는 순번 i로 차례대로 돌린다. 계획 수 = ⌈(end − start) × rate⌉."""
    start, end = PROBE_START[ttl], d_arr - ttl - PROBE_END_MARGIN
    out = {"start_s": start, "end_s": end, "len_s": end - start, "rate": {}, "period_ms": {}, "count": {}, "per_cell": {}, "last_request_s": {},
           "deltas": DELTAS, "deltas_c": DELTAS_C, "v_after_s": V_AFTER_S, "tol_ms": TOL_MS, "drop_max": DROP_MAX,
           "schedule_id": PROBE_SCHEDULE, "seat0": PROBE_SEAT0, "seats_per": PROBE_SEATS_PER}
    for pr, rate in PROBE_RATE[ttl].items():
        period = 1.0 / rate
        n = math.ceil(round((end - start) * rate, 9))
        ds = DELTAS_C if pr == "pr3" else DELTAS
        cells = {}
        last = 0.0
        for i in range(n):
            d = ds[i % len(ds)]
            cells[str(d)] = cells.get(str(d), 0) + 1
            st = start + i * period
            e = st + ttl                                           # 서버 만료 ≈ 첫 선점 + TTL(응답 지연은 0으로 본다 — 여유는 LAST_REQ_MARGIN_S)
            last = max(last, e + (max(V_AFTER_S, d) if pr == "pr3" else d))
        out["rate"][pr] = rate
        out["period_ms"][pr] = int(round(period * 1000))
        # k6 duration = (n − 1) 주기 + 반 주기 — 마지막 시작 뒤 경계에 걸린 반복이 하나 더 시작되지 않게(로컬 흉내 서버 시험: duration이 주기의 정수배면 경계 반복이
        # 들쭉날쭉 하나 더 시작됐다 — 8초 · 1/s에 8~9개). 시작 시각은 start + i × 주기 그대로
        out.setdefault("dur_ms", {})[pr] = int(round((n - 0.5) * period * 1000))
        out["count"][pr] = n
        out["per_cell"][pr] = cells
        out["last_request_s"][pr] = round(last, 3)
    return out


def flyway_str():
    return ",".join(FLYWAY)


def conn_env():
    """연결 상한 · nofile(명세 R5 ① — 모든 셀 · 네 방식 같게) → compose.expiry.yml 변수."""
    return {"TOMCAT_MAX_CONNECTIONS": str(TOMCAT_MAX_CONNECTIONS), "APP_NOFILE": str(APP_NOFILE)}


def render(method, cell, rep, knobs=None):
    knobs = knobs or {}
    if method not in METHODS or rep not in range(1, REPS + 1):
        raise SystemExit(f"방식 {method} · 회차 {rep}")
    mode = METHODS[method]
    common = {"method": method, "mode": mode, "mode_enum": MODE_ENUM[method], "cell": cell, "rep": rep, "crn_seed": rep,
              "expiry_interval": EXPIRY_INTERVAL, "flyway_locations": FLYWAY, "pool": POOL, "knobs": knobs}
    if cell in COST:
        c = COST[cell]
        k6_s = c["ramp_s"] + c["steps"] * c["step_s"]
        return {**common, "kind": "cost", "ttl_s": c["ttl_s"], "converge_s": 0,
                "seed": {"schedules": c["seed_schedules"], "seats": SEATS},
                "app_env": {"EXPIRY_MODE": mode, "HOLD_TTL": "60m", "EXPIRY_INTERVAL": EXPIRY_INTERVAL, "FLYWAY_LOCATIONS": flyway_str(), "POOL_SIZE": str(POOL),
                            **conn_env()},
                "k6": {"script": "k6/ADR-009/scenarios/s4-throughput.js", "duration_s": k6_s,
                       "env": {"START_RATE": str(c["rate"]), "FACTOR": "1", "STEPS": str(c["steps"]), "STEP_S": str(c["step_s"]),
                               "STOP_ON_ERROR": "0", "RAMP_S": str(c["ramp_s"]), "RAMP_FROM": str(c["ramp_from"])}},
                # 비용 셀 판정 창 = 정상 구간(램프 60초 뒤 — ADR-007 고정 셀 정의) · 상태 표본기 없음(리뷰 MR-1)
                "window": {"kind": "steady", "from_s": c["ramp_s"], "len_s": c["steps"] * c["step_s"]},
                "samplers": {"state": False, "h4": False}, "probes": None,
                "est_s": COST_EST_S}
    if cell not in MAIN:
        raise SystemExit(f"셀 {cell}")
    c = MAIN[cell]
    ttl, ab = c["ttl_s"], c["abandon"]
    d_arr = (knobs.get("d_arr") or {}).get(cell, c["d_arr_s"])
    map_rate = knobs.get("map_rate", MAP_RATE)
    mv = {f: (knobs.get("max_vus") or {}).get(f, MAX_VUS_MIN) for f in ("blind", "map", "probe")}
    tmin, tmax = THINK[ttl]
    pp = probe_plan(ttl, d_arr)
    pv = PRE_VUS_T300 if ttl >= 300 else PRE_VUS
    env = {"REP": str(rep), "D_ARR_S": str(d_arr), "TTL_S": str(ttl), "ABANDON": str(ab), "THINK_MIN_S": str(tmin), "THINK_MAX_S": str(tmax),
           "GRACE_STOP_S": str(GRACE_STOP[ttl]), "BLIND_RATE": str(BLIND_RATE), "MAP_RATE": str(map_rate), "RETRIES": str(RETRIES),
           "SEATS": str(SEATS), "HOT_RATIO": str(HOT_RATIO), "HOT_SHARE": str(HOT_SHARE), "RHO": str(RHO), "REVISIT_MAX_S": str(REVISIT_MAX_S),
           "MAX_VUS_BLIND": str(mv["blind"]), "MAX_VUS_MAP": str(mv["map"]), "MAX_VUS_PROBE": str(mv["probe"]),
           "PRE_VUS_BLIND": str(pv["blind"]), "PRE_VUS_MAP": str(pv["map"]), "PRE_VUS_PROBE": str(pv["probe"]),
           "USER_BLIND": str(USERS["blind"]), "USER_MAP": str(USERS["map"]),
           "PROBES": "1", "PR_START_S": str(pp["start_s"]), "PR_SCHEDULE": str(PROBE_SCHEDULE),
           "PR1_PERIOD_MS": str(pp["period_ms"]["pr1"]), "PR2_PERIOD_MS": str(pp["period_ms"]["pr2"]), "PR3_PERIOD_MS": str(pp["period_ms"]["pr3"]),
           "PR1_DUR_MS": str(pp["dur_ms"]["pr1"]), "PR2_DUR_MS": str(pp["dur_ms"]["pr2"]), "PR3_DUR_MS": str(pp["dur_ms"]["pr3"]),
           "PR1_SEAT0": str(PROBE_SEAT0["pr1"]), "PR2_SEAT0": str(PROBE_SEAT0["pr2"]), "PR3_SEAT0": str(PROBE_SEAT0["pr3"]),
           "PR1_USER0": str(USERS["pr1"]), "PR2_USER0": str(USERS["pr2"]), "PR3U_USER0": str(USERS["pr3u"]), "PR3V_USER0": str(USERS["pr3v"]),
           "V_AFTER_MS": str(int(V_AFTER_S * 1000)), "PR_DELTAS": ",".join(str(x) for x in DELTAS), "PR_DELTAS_C": ",".join(str(x) for x in DELTAS_C)}
    return {**common, "kind": "main", "ttl_s": ttl, "abandon": ab, "d_arr_s": d_arr, "think_s": [tmin, tmax], "grace_stop_s": GRACE_STOP[ttl],
            "converge_s": ttl + CONVERGE_EXTRA_S, "rates": {"blind": BLIND_RATE, "map": map_rate}, "retries": RETRIES, "rho": RHO,
            "revisit_max_s": REVISIT_MAX_S, "max_vus": mv, "seats": SEATS, "hot": [HOT_RATIO, HOT_SHARE], "users": USERS,
            "seed": {"schedules": 2, "seats": SEATS},
            "app_env": {"EXPIRY_MODE": mode, "HOLD_TTL": f"{ttl}s" if ttl < 60 else f"{ttl // 60}m", "EXPIRY_INTERVAL": EXPIRY_INTERVAL,
                        "FLYWAY_LOCATIONS": flyway_str(), "POOL_SIZE": str(POOL), **conn_env()},
            "k6": {"script": "k6/ADR-014/scenarios/s14-flow.js", "duration_s": d_arr + max(GRACE_STOP[ttl], ttl + 60), "env": env},
            "window": {"kind": "arrival", "from_s": 0, "len_s": d_arr},
            "samplers": {"state": True, "h4": True, "state_expired_every_s": knobs.get("state_expired_every_s", 1)},
            "probes": pp, "t_x": T_X,
            "est_s": d_arr + 30 + (ttl + CONVERGE_EXTRA_S) + OTHER_S}


# ---- 기대 설정(측정-1 · 측정-9 cond-config-mismatch · expiry-mode-mismatch · ttl-mismatch) -----------------------------------------------
def _norm(v):
    return str(v).replace("-", "_").upper() if v is not None else None


def _dur_s(v):
    """Spring Duration 표기(PT5M · PT10S · PT1H · 30s · 1000ms) → 초 · 모르면 None."""
    if v is None:
        return None
    s = str(v).strip().upper()
    try:
        if s.startswith("PT"):
            tot, num = 0.0, ""
            for ch in s[2:]:
                if ch.isdigit() or ch == ".":
                    num += ch
                    continue
                tot += float(num) * {"H": 3600, "M": 60, "S": 1}[ch]
                num = ""
            return tot
        for suf, mul in (("MS", 0.001), ("S", 1), ("M", 60), ("H", 3600)):
            if s.endswith(suf):
                return float(s[: -len(suf)]) * mul
        return float(s)
    except (KeyError, ValueError):
        return None


def config_check(method, cell, app_config):
    """app_config = run.sh app-config.json {configprops:[{prefix, properties}], "hikaricp.connections.max": [n]}.
    반환 (reasons, detail) — reasons ⊆ {expiry-mode-mismatch, ttl-mismatch, cond-config-mismatch} · None = 판정 불가(seat.hold 묶음 없음)."""
    props = app_config.get("configprops") or []
    hold = next((b.get("properties") or {} for b in props if b.get("prefix") == "seat.hold"), None)
    fw = next((b.get("properties") or {} for b in props if b.get("prefix") == "spring.flyway"), None)
    if hold is None:
        return None
    plan = render(method, cell, 1)
    reasons, detail = set(), []

    def bad(reason, what):
        reasons.add(reason)
        detail.append(f"{reason}: {what}")
    if _norm(hold.get("expiryMode")) != MODE_ENUM[method]:
        bad("expiry-mode-mismatch", f"expiryMode {hold.get('expiryMode')!r} ≠ {MODE_ENUM[method]}")
    if _dur_s(hold.get("ttl")) != float(plan["ttl_s"]):
        bad("ttl-mismatch", f"ttl {hold.get('ttl')!r} ≠ {plan['ttl_s']}s")
    if _dur_s(hold.get("expiryInterval")) != 10.0:
        bad("ttl-mismatch", f"expiryInterval {hold.get('expiryInterval')!r} ≠ 10s")
    cd = hold.get("counterDrift") or {}
    rc = cd.get("reconcile") or {}
    inc = rc.get("incremental") or {}
    sn = rc.get("safetyNet") or {}
    cl = cd.get("cleanup") or {}
    want = [("strategy", _norm(hold.get("strategy")), "PESSIMISTIC_NOWAIT"),
            ("limitStrategy", _norm(hold.get("limitStrategy")), "COUNTER_UPSERT_PRECHECK"),
            ("maxPerUser", hold.get("maxPerUser"), 2),
            ("criticalSectionDelay", _dur_s(hold.get("criticalSectionDelay")), 0.0),
            ("counterDrift.expiryRepair", cd.get("expiryRepair"), True),
            ("reconcile.enabled(C)", rc.get("enabled"), False),
            ("reconcile.detectShape", _norm(rc.get("detectShape")), "SPLIT"),
            ("incremental.method", _norm(inc.get("method")), "TRIGGER"),
            ("incremental.enabled", inc.get("enabled"), True),
            ("incremental.interval", _dur_s(inc.get("interval")), 1.0),
            ("safetyNet.enabled", sn.get("enabled"), True),
            ("cleanup.mode", _norm(cl.get("mode")), "SCHEDULE")]
    for name, got, exp in want:
        if got != exp:
            bad("cond-config-mismatch", f"{name} {got!r} ≠ {exp!r}")
    inj = ((hold.get("loadtest") or {}).get("injectApp") or {}).get("enabled")
    if inj not in (None, False):
        bad("cond-config-mismatch", f"loadtest.injectApp.enabled {inj!r} ≠ false(측정-1 주입 없음)")
    locs = (fw or {}).get("locations")
    if locs is None or [str(x) for x in locs] != FLYWAY:
        bad("cond-config-mismatch", f"flyway.locations {locs!r} ≠ {FLYWAY}")
    pool = app_config.get("hikaricp.connections.max")
    if not (isinstance(pool, list) and pool and pool[0] == POOL):
        bad("cond-config-mismatch", f"hikaricp.connections.max {pool!r} ≠ [{POOL}]")
    # 명세 R5 ①: Tomcat 연결 상한(configprops server 묶음) · 앱 컨테이너 nofile(/proc/1/limits — app_config14 app_nofile). 없음·숫자 아님도 불일치(무음 통과 금지)
    srv = next((b.get("properties") or {} for b in props if b.get("prefix") == "server"), None)
    mc = ((srv or {}).get("tomcat") or {}).get("maxConnections")
    if mc != TOMCAT_MAX_CONNECTIONS or isinstance(mc, bool):
        bad("cond-config-mismatch", f"server.tomcat.maxConnections {mc!r} ≠ {TOMCAT_MAX_CONNECTIONS}(명세 R5 ①)")
    nf = app_config.get("app_nofile") or {}
    need = TOMCAT_MAX_CONNECTIONS + NOFILE_MARGIN
    if not all(isinstance(nf.get(k), int) and not isinstance(nf.get(k), bool) and nf[k] >= need for k in ("soft", "hard")):
        bad("cond-config-mismatch", f"앱 nofile {nf!r} — soft·hard ≥ {need}(연결 상한 + {NOFILE_MARGIN}) 아님(명세 R5 ①)")
    return sorted(reasons), detail


# ---- dry-run(시작 조건 ③) ----------------------------------------------------------------------------------------------------
def dry_run(knobs):
    rows = order(knobs)
    problems, cells = [], {}
    for cell in GROUPS:
        p = render("A", cell, 1, knobs)
        info = {"kind": p["kind"], "est_min": round(p["est_s"] / 60, 1)}
        if p["kind"] == "main":
            pp = p["probes"]
            info.update(d_arr_s=p["d_arr_s"], ttl_s=p["ttl_s"], probe_window=[pp["start_s"], pp["end_s"]], probe_count=pp["count"],
                        per_cell=pp["per_cell"], last_request_s=pp["last_request_s"], limit_s=p["d_arr_s"] - LAST_REQ_MARGIN_S)
            for pr, last in pp["last_request_s"].items():
                if not last < p["d_arr_s"] - LAST_REQ_MARGIN_S:
                    problems.append(f"{cell} {pr} 마지막 탐침 요청 {last}s ≥ D_arr − 5 = {p['d_arr_s'] - LAST_REQ_MARGIN_S}s")
            for pr, n in pp["count"].items():
                hi = PROBE_SEAT0[pr] + n * PROBE_SEATS_PER[pr] - 1
                nxt = {"pr1": PROBE_SEAT0["pr2"], "pr2": PROBE_SEAT0["pr3"], "pr3": 20001}[pr]
                if hi >= nxt:
                    problems.append(f"{cell} {pr} 좌석 대역 {PROBE_SEAT0[pr]}~{hi}가 다음 대역 {nxt}와 겹친다")
                if min(pp["per_cell"][pr].values()) < 26:
                    problems.append(f"{cell} {pr} 칸당 계획 수 {pp['per_cell'][pr]} < 26(측정-3.2 '10%를 빼도 26개 이상')")
            info["probe_seats_max"] = sum(pp["count"][k] * PROBE_SEATS_PER[k] for k in pp["count"])
            blind_users = p["rates"]["blind"] * p["d_arr_s"]
            if USERS["blind"] + blind_users >= USERS["map"]:
                problems.append(f"{cell} 맹목 사용자 대역 {USERS['blind']}+{blind_users}가 지도 대역과 겹친다")
        cells[cell] = info
    total = sum(render(m, c, r, knobs)["est_s"] for _, r, c, m in rows)
    return {"rows": [{"seq": s, "rep": r, "cell": c, "method": m, "mode": METHODS[m], "crn_seed": r,
                      "est_min": round(render(m, c, r, knobs)["est_s"] / 60, 1)} for s, r, c, m in rows],
            "n": len(rows), "est_hours": round(total / 3600, 1), "cells": cells, "problems": problems, "knobs": knobs}


def darr_extend(t95):
    """측정-2 MR-13: D_arr′ = ⌈T95_추정 ÷ 0.6 ÷ 60⌉ × 60 — D_arr′ < D_arr면 그대로. 입력 = {셀: T95 추정초(네 방식 최대)}."""
    out = {}
    for cell, t in t95.items():
        base = MAIN[cell]["d_arr_s"]
        nd = math.ceil(t / 0.6 / 60) * 60
        out[cell] = {"t95_est_s": t, "ratio": round(t / base, 3), "d_arr_s": base, "d_arr_new_s": max(base, nd), "extend": nd > base}
    return out


def main(argv):
    knobs_path = None
    if "--knobs" in argv:
        i = argv.index("--knobs")
        knobs_path = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    knobs = load_knobs(knobs_path)
    if argv[:1] == ["--order"]:
        for s, r, c, m in order(knobs):
            print(s, r, c, m)
        return 0
    if argv[:1] == ["--render"] and len(argv) == 4:
        print(json.dumps(render(argv[1], argv[2], int(argv[3]), knobs), ensure_ascii=False))
        return 0
    if argv[:1] == ["--dry-run"]:
        d = dry_run(knobs)
        if "--json" in argv:
            print(json.dumps(d, ensure_ascii=False))
        else:
            print(f"# ADR-014 계획 — {d['n']}자리(주 16셀 + 비용 4셀 × {REPS}회) · 예상 약 {d['est_hours']}시간(추정 — 측정-2) · knobs {json.dumps(knobs, ensure_ascii=False)}")
            print("순번 회차 셀 방식 손잡이 씨앗 예상분")
            for x in d["rows"]:
                print(x["seq"], x["rep"], x["cell"], x["method"], x["mode"], x["crn_seed"], x["est_min"])
            print("# 셀별 탐침 일정(시작 조건 ③ — 마지막 탐침 요청 < t0 + D_arr − 5초)")
            for c, i in d["cells"].items():
                print(c, json.dumps(i, ensure_ascii=False))
            print("# 점검: " + ("문제 없음" if not d["problems"] else " | ".join(d["problems"])))
        return 1 if d["problems"] else 0
    if argv[:1] == ["--config-check"] and len(argv) == 4:
        try:
            cfg = json.loads(Path(argv[3]).read_text())
        except (OSError, ValueError) as e:
            print(json.dumps({"reasons": [], "detail": [f"읽기 실패 {type(e).__name__}"]}, ensure_ascii=False))
            return 2
        res = config_check(argv[1], argv[2], cfg)
        if res is None:
            print(json.dumps({"reasons": [], "detail": ["seat.hold 묶음 없음"]}, ensure_ascii=False))
            return 2
        print(json.dumps({"reasons": res[0], "detail": res[1]}, ensure_ascii=False))
        return 1 if res[0] else 0
    if argv[:1] == ["--darr-extend"] and len(argv) == 2:
        print(json.dumps(darr_extend(json.loads(argv[1])), ensure_ascii=False))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
