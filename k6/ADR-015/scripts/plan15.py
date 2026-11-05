#!/usr/bin/env python3
"""ADR-015 계획의 단일 출처 — 모드 5 × 셀 2(race · cost) × 회차 3 = 30자리 · 회차 순서(회차 우선 · 셀 묶음 순서 · 모드 회전) · 공통 난수 씨앗 · 셀 렌더(앱 설정 ·
k6 환경 · 시드 · 판정 창) · 기대 설정(configprops 대조) · dry-run(자리 · 시간 추정 · 좌석 · 풀 점유 점검). run.sh · campaign.sh · summarize.py · selftest.py가 이 파일을 읽는다
(값을 두 곳에 두지 않는다 — ADR-014 plan14.py와 같은 구조). 기준: 측정 전 고정 규칙 초안(15-09 · scratchpad adr015-measure-draft.md → 메인이 ADR-015에 반영).
값 옆 '메인 결정 D-n'은 초안이 후보 2개 + 권장으로 둔 값이다(권장값을 기본으로 넣었다 — 메인이 바꾸면 여기 한 곳만 고친다).

    plan15.py --order [--knobs <json>]                      → 30행 "순번 회차 셀 모드"(campaign.sh가 order.txt로 남기고 이어서 실행 때 다시 계산해 대조)
    plan15.py --render <모드> <셀> <회차> [--knobs <json>]  → 회차 계획 JSON(표준 출력 — run.sh가 plan.json으로)
    plan15.py --dry-run [--knobs <json>] [--json]            → 30자리 · 예상 시간 · 셀 점검(좌석 · 풀 점유 · 판정 창) — 문제 있으면 exit 1
    plan15.py --config-check <모드> <셀> <app-config.json>   → 기대 설정 대조 {"reasons":[…],"detail":[…]} · exit 0 일치 · 1 불일치 · 2 판정 불가
    plan15.py --v12 [--knobs <json>]                         → #5(V12 생성 시간) 단계 계획 JSON(v12-build.sh가 읽는다)

knobs(캠페인 전에 한 번만 — plan.json에 고정): {"pgss": 0|1, "cost_rate": 100|300, "v12_rows": [1000000, 5000000], "note": "…"}
    pgss = pg_stat_statements(#15 — D-4) · cost_rate = 비용 셀 도착률(D-2) · v12_rows = #5 운영 크기 후보(D-5)."""
import json
import math
import sys
from pathlib import Path

# ---- 고정값(측정 뒤 바꾸지 않는다 — 근거는 초안 측정-4 표) ----------------------------------------------------------------------------
MODES = ["current", "conditional", "pessimistic", "optimistic", "conditional-extend"]   # §설계-5 손잡이 값(SeatConfirmProperties.ConfirmMode)
MODE_ENUM = {m: m.upper().replace("-", "_") for m in MODES}                               # configprops 표기
NEW_MODES = MODES[1:]
SIMPLICITY = ["conditional", "pessimistic", "optimistic", "conditional-extend"]         # 4단 단순성(초안 측정-8 — ① > ② > ③ > ④)
TTL_S = 30                                                                                # ADR-014 t30 셀 선례(plan14 MAIN a20-t30)
EXPIRY_INTERVAL_S = 10                                                                    # application.yml expiry-interval 기본값 · ADR-014 측정-1 모든 셀 고정
POOL = 10                                                                                 # ADR-014 plan14 POOL
REPS = 3                                                                                  # ADR-010~014 선례(범위 비겹침의 최소 단위)
CELLS = {
    # race(경합 셀): 확정 지연 주입으로 '확인 뒤 · 첫 쓰기 앞' 창을 열어 만료 경계 경합을 만든다 — 판정 지표(5xx · 틱 실패 · 이중 결과)의 셀
    "race": {"rate": 20, "d_arr_s": 600, "delay_ms": 200},
    # cost(비용 셀): 지연 없음 — 모드별 확정 · 선점 · 배치 비용(p99 · DB CPU)의 셀. 도착률 = 메인 결정 D-2(권장 300 = ADR-014 맹목 300세션/초 선례)
    "cost": {"rate": 300, "d_arr_s": 300, "delay_ms": 0},
}
GROUPS = ["race", "cost"]                                                                 # 회차 안 셀 묶음 순서
COST_RATES = (100, 300)                                                                   # D-2 후보
C_MARGIN_MS = 300                                                                         # 확정 송신 오프셋 c ~ U[−(delay + 300ms), +300ms] (서버 만료 시각 e 기준)
F_DUP = 0.1                                                                               # 같은 홀드 이중 확정 비율(D-3 권장)
# 재선점 VU를 붙이는 세션 비율(D-3 사용자 결정 0.5) — 리뷰 M15-20(루프 2): **cost 셀만 0**. cost 셀은 경합 판정 몫이 없고(race가 맡는다 — 조밀 구간도 끈다)
#   ④만 연장 때문에 재선점이 13초 동안 409를 거듭 받아(다른 모드는 배치가 풀어 준 좌석에서 201로 이탈) 재선점 요청 수가 구조적으로 많다 → G3 비용이 모드가 아니라 부하 구성을 잰다
RHO = {"race": 0.5, "cost": 0.0}
REHOLD_FROM_MS, REHOLD_STEP_MS = 500, 1000                                                # 재선점 성긴 구간: e + 0.5초부터 1초 간격
REHOLD_UNTIL_S = EXPIRY_INTERVAL_S + 3                                                    # … e + 13초까지(배치 한 주기 + 여유 — 풀린 좌석을 놓치지 않는다)
# 재선점 조밀 구간(리뷰 M15-6): 경합 창 = 만료 e 직후 ~ 확정 쓰기(≤ e + delay — win 묶음 c ∈ [−delay, 0)) 안에 재선점이 들어가야 FM1 순서
#   '배치가 풀어 준 좌석을 재선점 201 → 그 세션 확정 쓰기'가 부하에서 생길 수 있다. e − 20ms부터 20ms 간격으로 e + delay + 100ms까지(확정 응답 끝 여유).
#   지연 0(cost)이면 창이 없다 — 조밀 구간을 끈다(재선점 요청이 비용 지표를 흐리지 않게).
REHOLD_DENSE_FROM_MS, REHOLD_DENSE_STEP_MS, REHOLD_DENSE_TAIL_MS = -20, 20, 100
PAYMENT_WINDOW, EXTEND_MIN_REMAINING = "2m", "5s"                                         # ④ — 설계-10 T6 테스트 값(D-6 권장)
PAYMENT_WINDOW_S = 120
PAY_START_BEFORE_E_S = 10                                                                 # ④ payment-start = e − 10초(> extend-min-remaining 5초 · < window)
# ④ 경계 흐름(리뷰 M15-4 · M15-5): race 셀 ④ 세션의 이 비율은 payment-start가 준 e′ 기준 e′ + c에 확정한다 — 연장 뒤에도 만료 경계 경합 창이 ④에서 열린다
#   (나머지는 e + c에 확정 — 연장이 홀드를 지키는가 · ext_then_lost). cost 셀은 0(세션이 약 2분 길어져 VU가 약 2만 늘어난다 — 비용 셀 VU 상한).
EXT_BOUNDARY_F = {"race": 0.5, "cost": 0.0}
SEATS_PER_SCHEDULE = 50000
SEAT_MARGIN = 1.05
GRACE_S = TTL_S + REHOLD_UNTIL_S + 7                                                      # gracefulStop = TTL + 재선점 끝 + 여유 = 50초(창 끝에 시작한 세션이 끝날 시간)
GRACE_EXT_B_S = GRACE_S + PAYMENT_WINDOW_S - PAY_START_BEFORE_E_S                         # ④ 경계 흐름이 있는 셀 = 50 + 110 = 160초(e′ ≈ e + 110초)
CONVERGE_EXTRA_S = 22                                                                     # 수렴 대기 = TTL + 22초(ADR-014 plan14 CONVERGE_EXTRA_S)
OTHER_S = 200                                                                             # 회차 시간 = k6 + 200초(ADR-014 t30 셀 실측 — 초안 측정-2 · 수렴 52초 포함 · 다음 회차까지 간격 포함)
TOMCAT_MAX_CONNECTIONS, APP_NOFILE = 20000, 65536                                         # ADR-014 측정-1 R5 ① 그대로
FLYWAY = ["classpath:db/migration", "classpath:db/migration-updated-at-trigger", "classpath:db/migration-incremental"]   # 업무 앱 기본 위치(application.yml)
FLYWAY_V14 = "classpath:db/migration-confirm-version"                                     # ③만(V14 트리거 — 규칙 6)
USERS = {"main": 1_000_000, "rehold": 4_000_000}                                          # 흐름별 사용자 대역(세션 번호 + 대역)
PRE_VUS_PER_RATE = {"main": 40, "rehold": 45}                                             # 미리 만든 VU = 도착률 × 생각 시간(약 TTL) 보다 넉넉히 — main 세션 ≈ TTL + 1초 · rehold ≈ TTL + 13초 × ρ
MAX_VUS_FACTOR = 1.5
K6_LEAD_BASE_S, K6_LEAD_PER_VU_MS = 0.15, 0.37                                            # ADR-014 plan14 k6_lead_s 식 그대로
PGSS_DEFAULT = 1                                                                          # D-4 — 기본 켬(초안 권장 · 다섯 모드 모두). fix1: ② 경합 표지(A′ B3 대기)와 #15 틱당 문장 수가 pgss를 읽는다
V12_ROWS_DEFAULT = [1_000_000, 5_000_000]                                                 # D-5 후보(ADR-002 규모 축 100만 · S5 상단 500만)
V12_BLOCK_MAX_MS = 2000                                                                   # #5 판정: 쓰기 차단 ≤ 2초면 트랜잭션 안 판 유지(ADR-013 lock_timeout 2초 선례 — D-5)
V12_REPS = 3
# 새 모드 양성 대조(리뷰 M15-26 — 서버 논리 증거): ①③④ 진 쪽 404가 '배치가 지연 중에 홀드를 지웠다'로 읽히려면 서버 :now < e여야 한다.
#   ①④는 :now 바로 뒤에 지연을 잔다(AtomicConfirmProcess.kt:41 · :69) → :now ≤ 응답 끝 − 지연. 응답 끝(k6 서버 시각 태그 te)을 시계 차 오차로 보정해
#   te + 오차 − 지연 + 여유 < e 이면 :now < e. 여유 20ms = ADR-014 측정-4 시계 오차 허용(:672 — 초안 측정-4 오프셋 행과 같은 값) · Thread.sleep ms 해상도 · 컨테이너 시계 흔들림 몫
EVIDENCE_MARGIN_MS = 20
# ② 양성 대조(리뷰 M15-26): A′ 배치 B3가 ② 확정(P2 뒤 지연 중 — 홀드 · 좌석 잠금 보유)을 기다리는 순간을 pg_stat_activity 표본으로 잡는다(scripts/b3wait-sampler.sh).
#   B3 대기 길이 w = 틱이 만난 순간 ②의 남은 지연(길이 편향 잔여 — 평균 약 delay/3) → 주기 p ms 표본이 한 대기를 잡을 확률 = E[min(1, w ÷ p)] — p = 20ms면 약 0.90 ·
#   ADR-009 대기 표본 500ms면 약 0.13(초안 측정-4 fix2 행 — .164 race 회차당 잡힌 에피소드 기대 약 20 대 약 2.9).
#   race 셀 다섯 모드 모두 같은 주기로 켠다(표본 질의 부하가 ②의 race 비용 지표에만 섞이지 않게) · cost 셀은 끈다(지연 0 — 잠금을 쥔 채 자지 않는다)
B3WAIT_PERIOD_MS = {"race": 20, "cost": 0}


def k6_lead_s(pre_vus_total):
    return round(K6_LEAD_BASE_S + pre_vus_total * K6_LEAD_PER_VU_MS / 1000, 2)


def load_knobs(path):
    if not path:
        return {}
    k = json.loads(Path(path).read_text()) if Path(path).exists() else json.loads(path)
    bad = []
    if k.get("pgss", PGSS_DEFAULT) not in (0, 1):
        bad.append(f"pgss={k.get('pgss')}(0 | 1)")
    if k.get("cost_rate", CELLS["cost"]["rate"]) not in COST_RATES:
        bad.append(f"cost_rate={k.get('cost_rate')}({COST_RATES})")
    rows = k.get("v12_rows", V12_ROWS_DEFAULT)
    if not (isinstance(rows, list) and rows and all(isinstance(x, int) and 1000 <= x <= 5_000_000 for x in rows)):
        bad.append(f"v12_rows={rows}(1,000 ~ 5,000,000 정수 목록)")
    extra = set(k) - {"pgss", "cost_rate", "v12_rows", "note"}
    if extra:
        bad.append(f"모르는 knobs 키 {sorted(extra)}")
    if bad:
        raise SystemExit("knobs 형식: " + " · ".join(bad))
    return k


def cell_of(cell, knobs):
    if cell not in CELLS:
        raise SystemExit(f"셀 {cell}")
    c = dict(CELLS[cell])
    if cell == "cost":
        c["rate"] = knobs.get("cost_rate", c["rate"])
    return c


def order(knobs=None):
    """회차 우선 · 묶음 순서 GROUPS · 묶음 g(0부터) · 회차 r(1부터)의 모드 = MODES를 (r + g) mod 5칸 회전(ADR-014 '균형 회전'과 같은 뜻 — 6묶음에서 모드마다 각 자리 1~2번)."""
    rows, seq = [], 0
    for r in range(1, REPS + 1):
        for g, cell in enumerate(GROUPS):
            rot = (r + g) % len(MODES)
            for m in MODES[rot:] + MODES[:rot]:
                seq += 1
                rows.append((seq, r, cell, m))
    return rows


def grace_of(mode, cell):
    return GRACE_EXT_B_S if mode == "conditional-extend" and EXT_BOUNDARY_F[cell] > 0 else GRACE_S


def rehold_dense(cell, delay_ms):
    # (from, step, until) ms — step 0 = 조밀 구간 없음(지연 0 셀)
    return (REHOLD_DENSE_FROM_MS, REHOLD_DENSE_STEP_MS, delay_ms + REHOLD_DENSE_TAIL_MS) if delay_ms > 0 else (0, 0, 0)


def seed_of(c, grace=GRACE_S):
    sessions = math.ceil(c["rate"] * (c["d_arr_s"] + grace) * SEAT_MARGIN)
    return {"schedules": max(1, math.ceil(sessions / SEATS_PER_SCHEDULE)), "seats": SEATS_PER_SCHEDULE, "sessions_max": sessions}


def flyway(mode):
    return FLYWAY + ([FLYWAY_V14] if mode == "optimistic" else [])


def render(mode, cell, rep, knobs=None):
    knobs = knobs or {}
    if mode not in MODES or rep not in range(1, REPS + 1):
        raise SystemExit(f"모드 {mode} · 회차 {rep}")
    c = cell_of(cell, knobs)
    ext = mode == "conditional-extend"
    xb = EXT_BOUNDARY_F[cell] if ext else 0.0
    grace = grace_of(mode, cell)
    seed = seed_of(c, grace)
    dense = rehold_dense(cell, c["delay_ms"])
    # 미리 만든 VU: ④ 경계 흐름 세션은 약 (창 − 리드)초 더 산다(main · rehold 둘 다)
    longer = xb * (PAYMENT_WINDOW_S - PAY_START_BEFORE_E_S)
    rho = RHO[cell]
    pre = {f: math.ceil(c["rate"] * (v + longer) * (rho if f == "rehold" else 1)) for f, v in PRE_VUS_PER_RATE.items()}
    pre = {f: max(v, 20) for f, v in pre.items()}
    mx = {f: math.ceil(v * MAX_VUS_FACTOR) for f, v in pre.items()}
    d_arr = c["d_arr_s"]
    app_env = {
        "CONFIRM_MODE": mode, "CONFIRM_DELAY": f"{c['delay_ms']}ms",
        "PAYMENT_WINDOW": PAYMENT_WINDOW if ext else "0s", "EXTEND_MIN_REMAINING": EXTEND_MIN_REMAINING if ext else "0s",
        "HOLD_TTL": f"{TTL_S}s", "EXPIRY_INTERVAL": f"{EXPIRY_INTERVAL_S}s", "FLYWAY_LOCATIONS": ",".join(flyway(mode)), "POOL_SIZE": str(POOL),
        "TOMCAT_MAX_CONNECTIONS": str(TOMCAT_MAX_CONNECTIONS), "APP_NOFILE": str(APP_NOFILE),
        "PG_PRELOAD": "pg_stat_statements" if knobs.get("pgss", PGSS_DEFAULT) else "",
    }
    k6env = {
        "REP": str(rep), "RATE": str(c["rate"]), "D_ARR_S": str(d_arr), "GRACE_S": str(grace), "TTL_S": str(TTL_S),
        "DELAY_MS": str(c["delay_ms"]), "C_MARGIN_MS": str(C_MARGIN_MS), "F_DUP": str(F_DUP), "RHO": str(rho),
        "REHOLD_FROM_MS": str(REHOLD_FROM_MS), "REHOLD_STEP_MS": str(REHOLD_STEP_MS), "REHOLD_UNTIL_S": str(REHOLD_UNTIL_S),
        "REHOLD_DENSE_FROM_MS": str(dense[0]), "REHOLD_DENSE_STEP_MS": str(dense[1]), "REHOLD_DENSE_UNTIL_MS": str(dense[2]),
        "PAY_START": "1" if ext else "0", "PAY_START_BEFORE_E_S": str(PAY_START_BEFORE_E_S), "PAY_WINDOW_S": str(PAYMENT_WINDOW_S),
        "EXT_BOUNDARY_F": str(xb),
        "SEATS_PER_SCHEDULE": str(SEATS_PER_SCHEDULE), "SCHEDULES": str(seed["schedules"]),
        "USER_MAIN": str(USERS["main"]), "USER_REHOLD": str(USERS["rehold"]),
        "PRE_VUS_MAIN": str(pre["main"]), "MAX_VUS_MAIN": str(mx["main"]), "PRE_VUS_REHOLD": str(pre["rehold"]), "MAX_VUS_REHOLD": str(mx["rehold"]),
    }
    pre_total = pre["main"] + pre["rehold"]
    return {
        "mode": mode, "mode_enum": MODE_ENUM[mode], "cell": cell, "rep": rep, "crn_seed": rep, "kind": "main", "knobs": knobs,
        "ttl_s": TTL_S, "expiry_interval_s": EXPIRY_INTERVAL_S, "delay_ms": c["delay_ms"], "rate": c["rate"], "d_arr_s": d_arr,
        "flyway_locations": flyway(mode), "pool": POOL, "payment_window": PAYMENT_WINDOW if ext else None,
        "extend_min_remaining": EXTEND_MIN_REMAINING if ext else None, "pgss": int(bool(knobs.get("pgss", PGSS_DEFAULT))),
        "seed": seed, "app_env": app_env, "grace_s": grace, "ext_boundary_f": xb, "rehold_dense_ms": list(dense), "rho": rho,
        "b3wait_ms": B3WAIT_PERIOD_MS[cell] if c["delay_ms"] > 0 else 0, "evidence_margin_ms": EVIDENCE_MARGIN_MS,
        "k6": {"script": "k6/ADR-015/scenarios/confirm-contention.js", "duration_s": d_arr + grace, "pre_vus": pre, "max_vus": mx,
               "pre_vus_total": pre_total, "lead_s": k6_lead_s(pre_total), "env": k6env},
        # 판정 창 = [t0, t0 + D_arr)(ADR-014 측정-0 정의 그대로) — 요청 시각이 창 안인 응답만 센다. 창 끝 뒤 gracefulStop 동안 끝난 세션은 창 밖(설명 지표에만)
        "window": {"kind": "arrival", "from_s": 0, "len_s": d_arr},
        "converge_s": TTL_S + CONVERGE_EXTRA_S,
        "samplers": {"state": False, "h4": False},
        "est_s": d_arr + grace + OTHER_S,
        # 풀 점유 추정(지연이 트랜잭션 안에서 잔다 — 연결을 쥔다): 확정 도착률 × (1 + 이중 비율) × 지연
        "pool_busy_est": round(c["rate"] * (1 + F_DUP) * c["delay_ms"] / 1000, 2),
    }


def expected_config(mode, cell, knobs=None):
    p = render(mode, cell, 1, knobs)
    return {
        "seat.confirm": {"mode": MODE_ENUM[mode], "loadtest.delay": f"PT{p['delay_ms'] / 1000:g}S" if p["delay_ms"] else "PT0S",
                         "paymentWindow": "PT2M" if mode == "conditional-extend" else "PT0S",
                         "extendMinRemaining": "PT5S" if mode == "conditional-extend" else "PT0S"},
        "seat.hold": {"expiryMode": "SWEEP", "ttl": f"PT{TTL_S}S", "expiryInterval": f"PT{EXPIRY_INTERVAL_S}S", "strategy": "PESSIMISTIC_NOWAIT",
                      "limitStrategy": "COUNTER_UPSERT_PRECHECK"},
        "spring.flyway": {"locations": flyway(mode)},
        "server": {"tomcat.maxConnections": TOMCAT_MAX_CONNECTIONS},
    }


def _get(props, prefix, dotted):
    for b in props:
        if b.get("prefix") == prefix:
            cur = b.get("properties")
            for part in dotted.split("."):
                cur = cur.get(part) if isinstance(cur, dict) else None
            return cur
    return None


def _norm(v):
    """configprops 값 정규화 — enum은 대문자 · Duration은 ISO('PT0.2S') · 목록은 목록."""
    if isinstance(v, str):
        return v.upper().replace("-", "_") if not v.startswith(("PT", "classpath")) else v
    return v


def config_check(mode, cell, cfg_path, knobs=None):
    cfg = json.loads(Path(cfg_path).read_text()) if Path(cfg_path).exists() else None
    if not isinstance(cfg, dict) or not isinstance(cfg.get("configprops"), list):
        return 2, {"reasons": ["cond-config-missing"], "detail": ["app-config.json 없음·깨짐"]}
    props = cfg["configprops"]
    reasons, detail = set(), []
    for prefix, kv in expected_config(mode, cell, knobs).items():
        for k, want in kv.items():
            got = _get(props, prefix, k)
            if prefix == "spring.flyway" and isinstance(got, list):
                ok = list(got) == list(want)
            else:
                ok = _norm(got) == _norm(want) or (isinstance(want, int) and got == want)
            if not ok:
                reasons.add("confirm-mode-mismatch" if (prefix, k) == ("seat.confirm", "mode") else "cond-config-mismatch")
                detail.append(f"{prefix}.{k}: 기대 {want!r} · 실제 {got!r}")
    # 리뷰 M15-24: Hikari 풀 크기(실측 hikaricp.connections.max)를 계획값과 대조(ADR-014 plan14.py:310 선례) — 없음 · 숫자 아님도 불일치(무음 통과 금지)
    pool = cfg.get("hikaricp.connections.max")
    if not (isinstance(pool, list) and pool and isinstance(pool[0], (int, float)) and not isinstance(pool[0], bool) and pool[0] == POOL):
        reasons.add("cond-config-mismatch")
        detail.append(f"hikaricp.connections.max {pool!r} ≠ [{POOL}]")
    nf = cfg.get("app_nofile")
    if not (isinstance(nf, dict) and all(isinstance(nf.get(x), int) and nf[x] >= TOMCAT_MAX_CONNECTIONS + 1000 for x in ("soft", "hard"))):
        reasons.add("cond-config-mismatch")
        detail.append(f"app_nofile {nf!r} < {TOMCAT_MAX_CONNECTIONS + 1000}")
    return (1 if reasons else 0), {"reasons": sorted(reasons), "detail": detail}


def dry_run(knobs):
    rows = order(knobs)
    problems, cells = [], {}
    for cell in GROUPS:
        for mode in MODES:
            p = render(mode, cell, 1, knobs)
            cells[f"{cell}/{mode}"] = p
            if p["seed"]["sessions_max"] > p["seed"]["schedules"] * SEATS_PER_SCHEDULE:
                problems.append(f"{cell}/{mode}: 좌석 부족")
            if p["pool_busy_est"] > POOL * 0.5:
                problems.append(f"{cell}/{mode}: 지연 풀 점유 추정 {p['pool_busy_est']} > 풀 {POOL}의 50%")
            if p["delay_ms"] + C_MARGIN_MS > TTL_S * 1000:
                problems.append(f"{cell}: 오프셋 창이 TTL보다 길다")
            fr, st, un = p["rehold_dense_ms"]
            if p["delay_ms"] > 0 and not (st > 0 and fr <= 0 and un >= p["delay_ms"]):
                problems.append(f"{cell}/{mode}: 재선점 조밀 구간이 경합 창 [0, {p['delay_ms']}ms]을 덮지 않는다")
            if cell == "race" and not p["b3wait_ms"] > 0:
                problems.append(f"race/{mode}: B3 대기 표본 꺼짐(② 양성 대조를 낼 수 없다 — 리뷰 M15-26)")
            if mode == "conditional-extend" and cell == "race" and not p["ext_boundary_f"] > 0:
                problems.append("race/conditional-extend: ④ 경계 흐름 없음(④ race 양성 대조가 구조적으로 0)")
    if not cells["race/pessimistic"]["pgss"]:
        problems.append("pgss=0 — ② 경합 표지(A′ B3가 ②의 홀드 잠금을 기다림 · pg_stat_statements)를 낼 수 없어 ②는 늘 판정 불가 → 결론이 판정 불가로 간다(D-4)")
    est = sum(cells[f"{c}/{m}"]["est_s"] for _, _, c, m in rows)
    return {"n": len(rows), "est_hours": round(est / 3600, 2), "per_cell_min": {k: round(v["est_s"] / 60, 1) for k, v in cells.items() if k.endswith("/current")},
            "ext_race_min": round(cells["race/conditional-extend"]["est_s"] / 60, 1),
            "vus": {k: v["k6"]["max_vus"] for k, v in cells.items() if k.endswith(("/current", "/conditional-extend"))},
            "pool_busy_est": {k.split("/")[0]: v["pool_busy_est"] for k, v in cells.items() if k.endswith("/current")},
            "seed": {k.split("/")[0]: v["seed"] for k, v in cells.items() if k.endswith("/current")}, "problems": problems}


def v12_plan(knobs):
    return {"rows": knobs.get("v12_rows", V12_ROWS_DEFAULT), "reps": V12_REPS, "block_max_ms": V12_BLOCK_MAX_MS,
            "seats_per_schedule": 100000, "probe_after_ms": 50, "index": "uq_reservation_confirmed_seat",
            "statement": "CREATE UNIQUE INDEX uq_reservation_confirmed_seat ON reservation (seat_id) WHERE status = 'CONFIRMED'"}


def main(argv):
    args = argv[1:]
    knobs = {}
    if "--knobs" in args:
        i = args.index("--knobs")
        knobs = load_knobs(args[i + 1])
        del args[i:i + 2]
    if args[:1] == ["--order"]:
        print("\n".join(f"{s} {r} {c} {m}" for s, r, c, m in order(knobs)))
        return 0
    if args[:1] == ["--render"] and len(args) == 4:
        print(json.dumps(render(args[1], args[2], int(args[3]), knobs), ensure_ascii=False))
        return 0
    if args[:1] == ["--dry-run"]:
        d = dry_run(knobs)
        if "--json" in args:
            print(json.dumps(d, ensure_ascii=False))
        else:
            print(f"자리 {d['n']} · 예상 {d['est_hours']}시간(+ #5 V12 단계 · 재측정 · 스모크)")
            for k, v in d["per_cell_min"].items():
                print(f"  {k.split('/')[0]}: 회차 약 {v}분 · 시드 {d['seed'][k.split('/')[0]]} · 풀 점유 추정 {d['pool_busy_est'][k.split('/')[0]]}")
            print(f"  race/④: 회차 약 {d['ext_race_min']}분(경계 흐름 — gracefulStop {GRACE_EXT_B_S}초) · 최대 VU {d['vus']}")
            for p in d["problems"]:
                print(f"  문제: {p}")
        return 1 if d["problems"] else 0
    if args[:1] == ["--config-check"] and len(args) == 4:
        rc, out = config_check(args[1], args[2], args[3], knobs)
        print(json.dumps(out, ensure_ascii=False))
        return rc
    if args[:1] == ["--v12"]:
        print(json.dumps(v12_plan(knobs), ensure_ascii=False))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
