#!/usr/bin/env python3
"""ADR-008 조건별 결과 요약(ADR-007 요약기 복사 + 회차 합격 판정 --accept-check). 그 앞: ADR-007 조건별 결과 요약(ADR-006 요약기 복사 + 고정 도착률 셀 30초 구간표 · DB 설정 적용값 · 대기 이벤트 분포) — 원시 결과(results/<matrix-id>/)에서 표를 산출한다.

    scripts/summarize.py results/<matrix-id>             표 산출 → SUMMARY.md · summary.json · curves/
    scripts/summarize.py --s4-check <k6-summary.json>     S4 회차에 성공한 단계가 하나라도 있으면 exit 0
    scripts/summarize.py --fixed-check <k6-summary.json>  고정 도착률(S4c*) 회차가 정상 종료했으면 exit 0 (계획 구간 전부에 요청 · 램프 + 계획 시간을 다 돎)
    scripts/summarize.py --codes-check <회차 폴더>         S2·S7 회차의 hold_code 행 수 = 선점 요청 수이고 깨진 행이 없으면 exit 0 (리뷰 A5)
    scripts/summarize.py --s7late-check <회차 폴더>        S7-m1-late 회차에 기회(R 201 뒤에 보낸 U)가 1건 이상이면 exit 0
    scripts/summarize.py --accept-check <회차 폴더> <셀>   ADR-008 회차 합격(명세 §5) — 사유를 한 줄에 하나씩 stdout(판정 실패는 accept-check-failed), exit 0 합격 · 1 사유 있음

표의 모든 수치는 원시 파일에서 계산한다 (손 계산 금지). 회차 값은 중앙값 [최소–최대].
행은 plan.json(실행 전에 남긴 계획) 기준 — 실행되지 않은 셀·회차는 '미측정/누락'으로 드러난다.

S4 한계 규칙 (ADR-006 명세 §9.3 — ADR-005 §7.3 ③ 'SERIALIZABLE L4 엄격 한계 708'의 원인을 고친 것)
  단계 = k6 계단의 한 칸(30초). 계획 단계(stage_rates) 전부를 본다 — 요청 0인 단계도 행이 있다(ADR-005는 건너뛰었다).
  '완전한 단계' = k6가 30초를 다 채운 단계. 에러율 50% 초과로 k6가 멈추면(exit 99) 요청이 있었던 마지막 단계는 부분 단계(partial),
  그 뒤 단계는 미실행(not_run) — 둘 다 뺀다(partial은 ADR-005와 같다). 중단이 아닌데 요청 0인 단계 = 도착 0 = 목표 미달(사유 no-arrivals+under-delivered).
  단계별: 실제 도착 RPS = 그 단계 hold 요청 수 ÷ 30 · 성공 RPS = 201 수 ÷ 30 · p99 · 에러율.
  목표 미달(under-delivered) = 실제 도착 RPS < 0.9 × 목표 RPS — k6가 목표 도착률을 내지 못했다(VU가 응답을 기다리느라 모자람 = 포화).
  기준 B = 엄격(p99 < 500ms · 에러율 < 1%) · 완화(p99 < 1s · 에러율 < 5%).
  한계(B) = 완전한 단계를 계단 순서로 보며, 처음으로 아래 중 하나에 걸린 단계에서 멈추고 그 **직전 단계의 성공 RPS**(첫 단계에서 걸리면 0):
            ① p99 없음 ② p99 ≥ B의 p99 ③ 에러율 ≥ B의 에러율 ④ 목표 미달(새 규칙 — ADR-005는 ①~③만 봤다).
            멈춘 단계·사유를 함께 남긴다(strict_stop·loose_stop). 끝까지 하나도 안 걸리면 사유 없음 = 계단 상한(compare.py가 ≥ 표시).
            → 포화된 단계 뒤의 단계가 부하가 줄어 기준을 다시 만족해도 '통과'로 집지 않는다.
  포화점(saturation) = 완전한 단계 중 최대 성공 RPS(ADR-005와 같은 정의) · 포화 단계(saturation_stage) = 처음으로 목표 미달인 단계의
            {단계, 목표, 실제 도착, 성공 RPS}(없으면 null). 새 규칙에서 엄격 한계 ≤ 포화점은 항상 성립한다.
  strict_legacy·loose_legacy = ADR-005 규칙(①~③만) 값 — 두 규칙이 갈리는 회차를 드러내려고 함께 남긴다.

ADR-007 추가
  고정 도착률 셀(S4c<rate> — run.sh: S4 시나리오 FACTOR=1 · 램프 60초 뒤 12구간 × 30초 · 에러율 중단 없음): '한계'를 내지 않는다(목표가 하나뿐이라 계단 규칙이 성립하지 않는다).
            대신 30초 구간(= k6 stage 태그, 램프 'ramp' 제외)마다 실제 도착 RPS·도착/목표·dropped·성공 RPS·p99(코드 0 제외)·코드 0 비율·에러율 표 +
            전체 성공 RPS(201 ÷ 계획 시간) · 목표 미달 구간 수(fixed_csv · fixed_summary). status ok = 회차 유효(계획 구간을 다 돎)이지 목표 달성이 아니다.
  S4 계단의 단계 p99(k6 요약)도 코드 0 행의 0에 가까운 지연을 섞는다 — 한계 규칙은 에러율(< 1%·< 5%)로 그런 단계를 이미 멈추게 하므로 규칙은 그대로 두고,
            단계별 코드 0 수는 compare.py '응답 코드 분류 — S4 단계별'(errsplit.py)에서 본다.
  DB 설정 = meta.json pg_preset·pg_applied(run.sh가 회차마다 SHOW로 읽은 값) · 대기 이벤트 = timeline-waits.jsonl(lib.sh db_sampler)의 k6 구간 평균 활성 세션 수 상위 5개.
  벽 분석(처음 무너진 초·체크포인트·벽 근처 대기 이벤트)은 wall.py.

ADR-009 추가
  빌림(설계 §4d · 명세 §10 D8) = (Δ획득 − Δreconcile.borrows − Δcleanup.borrows) ÷ (Δ선점 + Δ확정) — 앱 풀을 같이 쓰는 대조(C)·정리의 빌림을 뺀다(F는 별도 풀).
            범위 [0.98, 1.02]. redis-nx '진 것' 경로(트랜잭션 밖 마감 판정 1문장)는 이 측정 범위 밖이다(좌석 3b 고정).
  MultiXact(L1-16·L2-11) = io-before/after의 pg_stat_slru(MultiXactOffset·MultiXactMember) 차분과 mxid_age(datminmxid) 차분(datminmxid가 같을 때만 = 새로 쓴 MultiXact ID 수).
  대기 출처(IV-009-7) = timeline-waits.jsonl by_src(lib.sh db_sampler — F = application_name, C = 문장 표지)의 k6 구간 평균 세션 수.
  제외 쌍 두 정의(L2-9): 판정기 counter_excluded_pairs = 마감 회차의 (쿼터 행 ∪ 실제 값 쌍) 수 · 대조 바퀴 skipped_pairs = 마감 회차의 쿼터 행 수 — 표에서 이름을 나눠 적는다.
  마감 제외(L3-4): 대조 복구 트랜잭션이 마감을 확인해 쓰지 않은 쌍(counter_drift_log reason=closed · counter_reconcile_run.skipped_closed)은 실패 skipped와 따로 센다.

ADR-006 추가 지표
  요청당 커넥션 빌림 = Hikari 획득(hikaricp.connections.acquire) COUNT 증가분 ÷ 선점 요청(http.server.requests hold URI) COUNT 증가분 (k6 직전·직후 — run.sh after_k6).
  서버 I/O = io-before.json · io-after.json(lib.sh io_snapshot — k6 직전·직후, 각각 통계 반영 대기 IO_SETTLE_S 뒤) 차분. io_delta 참고.
"""
import csv
import gzip
import json
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path


def load(path):
    try:
        return json.loads(Path(path).read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def metric(summary, name, stat="count", default=0):
    m = (summary or {}).get("metrics", {}).get(name)
    if not m:
        return default
    return m.get("values", {}).get(stat, default)


def env_value(meta, key, default=None):
    for pair in (meta.get("k6_env") or "").split():
        k, _, v = pair.partition("=")
        if k == key:
            return v
    return default


def spread(values, digits=None):
    vals = [v for v in values if v is not None]
    if not vals:
        return "미측정"
    med = statistics.median(vals)
    integral = all(float(v).is_integer() for v in vals)
    if digits is not None:   # 고정 자릿수(매수 제어 타이머처럼 1ms 미만 값 — 2자리면 0.00으로 뭉개진다)
        fmt = lambda x: f"{x:,.{digits}f}"
    else:
        fmt = (lambda x: f"{x:,.0f}") if integral else (lambda x: f"{x:,.2f}")
    return fmt(med) if len(vals) == 1 else f"{fmt(med)} [{fmt(min(vals))}–{fmt(max(vals))}]"


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


# ---- S4 --------------------------------------------------------------------------------------------
def stage_table(summ, aborted):
    """단계별 지표 — 계획 단계(stage_rates) 전부를 행으로 낸다(요청 0인 단계도 — 도착 0 = 목표 미달로 판정에 들어간다).
    중단(abort, k6 exit 99)이면: 요청이 있었던 마지막 단계는 30초를 다 채우지 못했으므로 partial, 그 뒤 단계는 k6가 시작하지 않았으므로
    not_run으로 표시하고 둘 다 한계 산출에서 뺀다. 중단이 아니면 요청 0인 단계도 완전한 단계다(30초 동안 도착이 하나도 없었다)."""
    rows = []
    step = summ.get("step_seconds", 30)
    for i, target in enumerate(summ.get("stage_rates", [])):
        m = summ["metrics"]
        dur = m.get(f"hold_duration{{stage:{i}}}", {}).get("values", {})
        n = dur.get("count", 0)
        rows.append({
            "stage": i, "target_rps": target, "requests": n, "achieved_rps": n / step,
            "ok_rps": m.get(f"hold_201{{stage:{i}}}", {}).get("values", {}).get("count", 0) / step,
            "p99_ms": dur.get("p(99)") if n else None,
            "error_rate": m.get(f"hold_error{{stage:{i}}}", {}).get("values", {}).get("rate", 0) if n else None,
            "partial": False, "not_run": False,
        })
    if aborted:
        last = max((r["stage"] for r in rows if r["requests"]), default=None)
        for r in rows:
            if last is None or r["stage"] > last:
                r["not_run"] = True
            elif r["stage"] == last:
                r["partial"] = True
    for r in rows:
        r["under_delivered"] = r["achieved_rps"] < 0.9 * r["target_rps"]   # k6가 목표 도착률을 못 채움(도착 0 포함)
    return rows


STRICT = (500, 0.01)
LOOSE = (1000, 0.05)


def s4_limits(stages):
    """S4 한계 — 규칙은 머리말 'S4 한계 규칙'. 반환: strict·loose(새 규칙) + *_stop(멈춘 단계·사유) + saturation·saturation_stage + *_legacy(ADR-005 규칙)."""
    complete = [s for s in stages if not s["partial"] and not s.get("not_run")]

    def violations(s, p99_ms, err, under):
        if not s.get("requests", 1):   # 도착 0(요청 0인 완전한 단계) = 목표 미달 — p99·에러율은 정의되지 않는다
            return ["no-arrivals", "under-delivered"]
        out = []
        if s["p99_ms"] is None:
            out.append("no-p99")
        elif s["p99_ms"] >= p99_ms:
            out.append("p99")
        if s["error_rate"] is not None and s["error_rate"] >= err:
            out.append("error")
        if under and s["under_delivered"]:
            out.append("under-delivered")
        return out

    def limit(p99_ms, err, under=True):
        last = 0
        for s in complete:
            if not under and not s.get("requests", 1):
                continue   # ADR-005 규칙(legacy)은 요청 0인 단계를 건너뛰었다 — 기록값 대조가 그대로 성립하게 같은 동작
            v = violations(s, p99_ms, err, under)
            if v:
                return last, {"stage": s["stage"], "target_rps": s["target_rps"], "reasons": v}
            last = s["ok_rps"]
        return last, None

    strict, strict_stop = limit(*STRICT)
    loose, loose_stop = limit(*LOOSE)
    sat = next((s for s in complete if s["under_delivered"]), None)
    return {"strict": strict, "strict_stop": strict_stop, "loose": loose, "loose_stop": loose_stop,
            "saturation": max((s["ok_rps"] for s in complete), default=0),
            "saturation_stage": sat and {k: sat[k] for k in ("stage", "target_rps", "achieved_rps", "ok_rps")},
            "strict_legacy": limit(*STRICT, under=False)[0], "loose_legacy": limit(*LOOSE, under=False)[0],
            "under_delivered_stages": sum(1 for s in complete if s["under_delivered"])}


def fixed_check(summary_path):
    """고정 도착률 회차의 정상 종료: 계획 구간(stage_rates) 전부에 요청이 있고 k6 실행 시간이 계획(램프 + 구간 수 × 구간 초) 이상.
    램프(stage 태그 'ramp' — 리뷰 A13)는 구간이 아니다: stage_table이 stage 0..n−1만 보므로 램프 요청은 '구간에 요청이 있다'에 들지 않는다."""
    summ = load(summary_path)
    if not summ or not summ.get("stage_rates"):
        return 1
    stages = stage_table(summ, aborted=False)
    planned_ms = (len(summ["stage_rates"]) * summ.get("step_seconds", 30) + (summ.get("ramp_s") or 0)) * 1000
    ran_ms = (summ.get("state") or {}).get("testRunDurationMs") or 0
    return 0 if all(s["requests"] for s in stages) and ran_ms >= planned_ms else 1


def fixed_csv(csv_gz, summ, stages):
    """고정 셀 구간별 원시 집계(k6-requests.csv.gz — 리뷰 A12·A6). 구간 = stage 태그(램프 'ramp'는 따로).
    - 코드 0(요청 타임아웃·연결 실패·끊김) 수·비율: k6는 코드 0 요청의 지연을 0에 가깝게 기록한다(스모크 smoke-b 20,222건 중 18,538건 < 5ms) —
      k6 요약의 구간 p99(stage_table p99_ms)는 이 값을 섞어 낮게 나온다. 그래서 p99_nz_ms = 코드 0을 뺀 p99를 따로 낸다.
    - dropped = k6 dropped_iterations 행(k6가 VU가 없어 시작하지 못한 반복) 중 램프 끝(setup_data.startedAt + ramp_s) 뒤 — 그 시각부터 구간 초로 나눠 구간별.
      행 타임스탬프가 초 단위라 경계 ±1초는 옆 구간(램프 끝이면 램프)에 들 수 있다. 램프 몫은 ramp_dropped.
    stages(stage_table 결과)에 키를 더하고 회차 합계 dict를 돌려준다. 원시 파일이 없거나 깨졌으면 None(구간 값은 미측정)."""
    if not csv_gz.exists():
        return None
    step = summ.get("step_seconds", 30)
    ramp_s = summ.get("ramp_s") or 0
    started = (summ.get("setup_data") or {}).get("startedAt")
    hold0 = started / 1000 + ramp_s if started else None
    n_iv = len(stages)
    lat = {i: [] for i in range(n_iv)}
    n0 = {i: 0 for i in range(n_iv)}
    nn = {i: 0 for i in range(n_iv)}
    drop = {i: 0 for i in range(n_iv)}
    ramp = {"requests": 0, "status0": 0, "dropped": 0}
    ramp_lat = []
    per_sec = {}   # ADR-010: 램프 밖 1초(행 타임스탬프 — 초 단위)별 지연(코드 0 제외) — 0.1초짜리 GC 멈춤은 30초 구간 p99에 묻힌다
    try:
        with gzip.open(csv_gz, "rt", newline="") as f:
            header = next(csv.reader([f.readline()]))
            i_ts, i_val, i_name, i_status, i_tags = (header.index(k) for k in ("timestamp", "metric_value", "name", "status", "extra_tags"))
            for line in f:
                if line.startswith("dropped_iterations,"):
                    t = int(line.split(",", 2)[1])
                    if hold0 is None or t < int(hold0):
                        ramp["dropped"] += 1
                    else:
                        drop[min(n_iv - 1, int((t - int(hold0)) // step))] += 1
                    continue
                if not line.startswith("http_req_duration,"):
                    continue
                row = next(csv.reader([line]))
                if len(row) <= i_tags or row[i_name] != "hold":
                    continue
                stage = next((kv[6:] for kv in row[i_tags].split("&") if kv.startswith("stage=")), None)
                zero = row[i_status] in ("0", "")
                if stage == "ramp":
                    ramp["requests"] += 1
                    ramp["status0"] += zero
                    if not zero:
                        ramp_lat.append(float(row[i_val]))
                    continue
                if stage is None or not stage.isdigit() or int(stage) >= n_iv:
                    continue
                i = int(stage)
                nn[i] += 1
                if zero:
                    n0[i] += 1
                else:
                    lat[i].append(float(row[i_val]))
                    per_sec.setdefault(int(row[i_ts]), []).append(float(row[i_val]))
    except (EOFError, OSError, ValueError, StopIteration):
        return None
    for s in stages:
        i = s["stage"]
        xs = sorted(lat[i])
        s.update(p99_nz_ms=xs[int(len(xs) * 0.99)] if xs else None, status0=n0[i], status0_share=n0[i] / nn[i] if nn[i] else None,
                 dropped=drop[i] if hold0 is not None else None, achieved_ratio=s["achieved_rps"] / s["target_rps"] if s["target_rps"] else None)
    tot = sum(nn.values())
    steady = sorted(x for i in lat for x in lat[i])
    ramp_lat.sort()
    p99 = lambda xs: xs[int(len(xs) * 0.99)] if xs else None
    # 리뷰 H2: 램프(0~60초)와 램프 밖(60~420초 — 계획 구간 전체) p99를 따로(코드 0 제외) — 램프 붕괴가 회차 전체 p99를 흔드는지 가른다
    ramp["p99_nz_ms"] = p99(ramp_lat)
    # ADR-010: 1초 p99의 최대 · 최대 응답 > 50ms인 초 수 · 최대 응답(램프 밖, 코드 0 제외)
    sec_p99 = [p99(sorted(v)) for v in per_sec.values() if v]
    return {"sec_p99_max_ms": max(sec_p99, default=None), "slow_secs_50ms": sum(1 for v in per_sec.values() if v and max(v) > 50),
            "max_req_nz_ms": max((max(v) for v in per_sec.values() if v), default=None), "secs_steady": len(per_sec),
            "p99_steady_nz_ms": p99(steady), "p99_ramp_nz_ms": p99(ramp_lat), "status0": sum(n0.values()), "status0_share": sum(n0.values()) / tot if tot else None,
            "dropped_hold": sum(drop.values()) if hold0 is not None else None, "ramp": ramp, "ramp_s": ramp_s}


def fixed_summary(stages, step_s, raw=None):
    """고정 도착률 회차 요약 — 계획 시간 전체 성공 RPS · 도착/목표 비율 · 목표 미달 구간 수 · 가장 나쁜 구간 p99(k6 요약 값과 코드 0 제외 값) ·
    구간 코드 0 합·비율 · 구간 dropped 합(fixed_csv). 램프는 들지 않는다."""
    total_s = len(stages) * step_s
    target = sum(s["target_rps"] for s in stages) * step_s
    raw = raw or {}
    return {"p99_steady_nz_ms": raw.get("p99_steady_nz_ms"), "p99_ramp_nz_ms": raw.get("p99_ramp_nz_ms"),
            "sec_p99_max_ms": raw.get("sec_p99_max_ms"), "slow_secs_50ms": raw.get("slow_secs_50ms"), "max_req_nz_ms": raw.get("max_req_nz_ms"),
            "secs_steady": raw.get("secs_steady"),
            "ok_rps_total": sum(s["ok_rps"] * step_s for s in stages) / total_s if total_s else None,
            "achieved_rps_total": sum(s["requests"] for s in stages) / total_s if total_s else None,
            "achieved_ratio_total": sum(s["requests"] for s in stages) / target if target else None,
            "under_delivered_intervals": sum(1 for s in stages if s["under_delivered"]),
            "first_under_delivered": next((s["stage"] for s in stages if s["under_delivered"]), None),
            "worst_p99_ms": max((s["p99_ms"] for s in stages if s["p99_ms"] is not None), default=None),
            "worst_p99_nz_ms": max((s["p99_nz_ms"] for s in stages if s.get("p99_nz_ms") is not None), default=None),
            "status0": raw.get("status0"), "status0_share": raw.get("status0_share"), "dropped_hold": raw.get("dropped_hold"),
            "ramp": raw.get("ramp"), "ramp_s": raw.get("ramp_s")}


def waits_top(path, window, n=5, off=0.0):
    """timeline-waits.jsonl(0.5초 표본)의 window 구간 평균 — 대기 이벤트별 활성 세션 수 평균(표본 수로 나눔) 상위 n개 + 표본 수.
    표본 시각은 서버 시계라 off(서버 − PC 시계 차, meta.json clock_offset — 리뷰 A3)를 빼서 PC 시계 window와 비교한다.
    표본 0개 → (None, 0) · 표본은 있는데 활성 세션이 하나도 없었다 → ([], 표본 수) — 표에서 둘을 다르게 적는다(리뷰 A10)."""
    if not path.exists():
        return None, 0
    tot, samples = {}, 0
    for line in path.read_text().splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "waits" not in row or (window and not (window[0] <= ts(row["t"]) - off <= window[1])):
            continue
        samples += 1
        for k, v in (row["waits"] or {}).items():
            tot[k] = tot.get(k, 0) + v
    if not samples:
        return None, 0
    return [(k, v / samples) for k, v in sorted(tot.items(), key=lambda kv: -kv[1])[:n]], samples


def waits_by_src(path, window, off=0.0):
    """ADR-009: timeline-waits.jsonl by_src("<출처>|<type>:<event>")의 window 평균 세션 수 → {출처: {합계, 상위 3}} (lib.sh db_sampler 머리말). 표본 없으면 None."""
    if not path.exists():
        return None
    tot, n = {}, 0
    for line in path.read_text().splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "by_src" not in row or (window and not (window[0] <= ts(row["t"]) - off <= window[1])):
            continue
        n += 1
        for k, v in (row["by_src"] or {}).items():
            tot[k] = tot.get(k, 0) + v
    if not n:
        return None
    out = {}
    for k, v in tot.items():
        src, ev = k.split("|", 1)
        d = out.setdefault(src, {"sessions": 0.0, "events": {}})
        d["sessions"] += v / n
        d["events"][ev] = v / n
    for d in out.values():
        d["top"] = sorted(d.pop("events").items(), key=lambda kv: -kv[1])[:3]
    return out


def s4_check(summary_path):
    """성공한 단계(에러율 < 50%이고 요청이 있었던 단계)가 하나라도 있으면 측정 성립."""
    summ = load(summary_path)
    if not summ:
        return 1
    return 0 if any(s["requests"] and s["error_rate"] < 0.5 for s in stage_table(summ, aborted=False)) else 1


# ---- S3 --------------------------------------------------------------------------------------------
def hold_events(csv_gz, ttl_s):
    """CSV의 hold_ok_seat(좌석·만료시각)에서 선점 성공을 좌석별로 모아 재선점/중복 선점을 가른다.
    서버 기준 선점 시각 = 만료 − TTL. 같은 좌석의 앞 홀드 만료 이후면 재선점, 이전이면 중복 선점."""
    per_seat = {}
    if not csv_gz.exists():
        return None
    with gzip.open(csv_gz, "rt", newline="") as f:
        header = next(csv.reader([f.readline()]))
        tag_col = header.index("extra_tags")
        for line in f:
            if not line.startswith("hold_ok_seat,"):
                continue
            tags = dict(kv.split("=", 1) for kv in next(csv.reader([line]))[tag_col].split("&") if "=" in kv)
            exp = ts(tags["exp"])
            per_seat.setdefault(tags["seat"], []).append((exp - ttl_s, exp))
    events = []
    for seat, holds in per_seat.items():
        holds.sort()
        for i, (held, exp) in enumerate(holds):
            if i == 0:
                kind = "first"
            else:
                kind = "rehold" if held >= holds[i - 1][1] else "duplicate"
            events.append((held, kind))
    events.sort()
    return events


def s3_curve(rep_dir, meta, root, level, cell):
    """시간축 = (시각 − k6 시작) / TTL. DB 상태 곡선(누적 확정 포함)과 재선점·중복 선점 누적 곡선을 남긴다."""
    ttl = meta.get("ttl_s")
    if not ttl or not meta.get("k6_started"):
        return None
    t0 = ts(meta["k6_started"])
    out = {}
    db_rows = []
    path = rep_dir / "timeline-db.jsonl"
    if path.exists():
        for line in path.read_text().splitlines():
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue
            t = (ts(d["t"]) - t0) / ttl
            if t < 0:
                continue
            db_rows.append({"rep": rep_dir.name, "t_over_ttl": round(t, 4), "reserved": d["reserved"],
                            "confirmed": d["confirmed"], "held": d["held"], "available": d["available"],
                            "hold_rows": d["hold_rows"], "poll_ms": d.get("poll_ms")})
    if db_rows:
        write_rows(root / "curves" / f"L{level}-{cell}-db.csv", db_rows)
        seats = db_rows[0]["reserved"] + db_rows[0]["held"] + db_rows[0]["available"]
        first_at = lambda frac: next((r["t_over_ttl"] for r in db_rows if r["confirmed"] >= frac * seats), None)
        out.update(t50=first_at(0.5), t90=first_at(0.9),
                   poll_ms_max=max((r["poll_ms"] or 0) for r in db_rows))
    try:
        events = hold_events(rep_dir / "k6-requests.csv.gz", ttl)
    except (EOFError, OSError, ValueError, KeyError, StopIteration) as e:
        # 손상된 원시 파일 하나가 전체 요약을 멈추지 않게 — 그 회차만 곡선 오류로 표시한다
        out["curve_error"] = f"{type(e).__name__}: {e}"
        events = None
    if events is not None:
        cum = {"rehold": 0, "duplicate": 0}
        ev_rows = []
        for held, kind in events:
            if kind in cum:
                cum[kind] += 1
                ev_rows.append({"rep": rep_dir.name, "t_over_ttl": round((held - t0) / ttl, 4),
                                "cum_rehold": cum["rehold"], "cum_duplicate": cum["duplicate"]})
        if ev_rows:
            write_rows(root / "curves" / f"L{level}-{cell}-rehold.csv", ev_rows)
        out.update(rehold=cum["rehold"], duplicate=cum["duplicate"])
    return out


def write_rows(path, rows):
    new = not path.exists()
    with path.open("a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), lineterminator="\n")
        if new:
            w.writeheader()
        w.writerows(rows)


# ---- ADR-005: 요청 기록(hold_code 행)·끝 상태 -------------------------------------------------------
def hold_codes(csv_gz, stats=None):
    """k6-requests.csv.gz의 hold_code 행(시나리오 lib — 요청 1건당 1행) → 태그 dict 목록. 행이 하나도 없으면 None(기록 없음 ≠ 0건).
    stats(dict)를 주면 {"lines": hold_code 행 수, "malformed": 잘렸거나 code 태그가 없는 행 수}를 채운다 — codes_check가 요청 수와 대조한다(리뷰 A5)."""
    if not csv_gz.exists():
        return None
    rows, lines, bad = [], 0, 0
    with gzip.open(csv_gz, "rt", newline="") as f:
        header = next(csv.reader([f.readline()]))
        i_tags = header.index("extra_tags")
        for line in f:
            if not line.startswith("hold_code,"):
                continue
            lines += 1
            row = next(csv.reader([line]))
            if len(row) <= i_tags:
                bad += 1
                continue  # 잘린 마지막 행
            tags = dict(kv.split("=", 1) for kv in row[i_tags].split("&") if "=" in kv)
            if "code" not in tags:
                bad += 1
                continue
            rows.append(tags)
    if stats is not None:
        stats.update(lines=lines, malformed=bad)
    return rows or None


def codes_check(rep_dir):
    """S2·S7 hold_code 행 수 = 측정 선점 요청 수(k6 요약 hold_duration count — S7 setup의 U 사전 선점은 hold()를 거치지 않아 둘 다에 없다)이고
    깨진 행이 없으면 exit 0(리뷰 A5 — 행이 빠지거나 깨지면 가짜 거절·명세 순서 위반 수가 조용히 줄어든다). 결과를 stderr에 한 줄."""
    rep_dir = Path(rep_dir)
    summ = load(rep_dir / "k6-summary.json")
    want = metric(summ, "hold_duration", "count", None) if summ else None
    stats = {}
    try:
        rows = hold_codes(rep_dir / "k6-requests.csv.gz", stats)
    except (EOFError, OSError, ValueError, StopIteration) as e:
        print(f"hold-codes: 읽기 실패 {type(e).__name__}: {e}", file=sys.stderr)
        return 1
    got = len(rows or [])
    print(f"hold-codes: 행 {stats.get('lines')} · 깨진 행 {stats.get('malformed')} · 쓸 수 있는 행 {got} · 요청 수(hold_duration) {want}", file=sys.stderr)
    return 0 if want is not None and got == want and not stats.get("malformed") else 1


LIMIT_CODES = ("SEAT_NOT_AVAILABLE", "HOLD_LIMIT_EXCEEDED")


def code_counts(rows):
    """409 응답의 에러 코드별 수 — SEAT_NOT_AVAILABLE · HOLD_LIMIT_EXCEEDED · 그 밖(409 다른 코드·본문 없음)."""
    out = {"SEAT_NOT_AVAILABLE": 0, "HOLD_LIMIT_EXCEEDED": 0, "409_other": 0}
    for r in rows:
        c = r.get("code")
        if c in LIMIT_CODES:
            out[c] += 1
        elif c not in ("OK",) and not (c or "").startswith("HTTP"):
            out["409_other"] += 1
    return out


def s2_users(rows, end_state, limit):
    """S2 사용자별 판정.
    - 매수 초과 사용자(응답) = 201 > limit 인 사용자 · (DB) = 끝 상태 매수 > limit (판정기 v_over_limit_users와 같은 정의, 측정 회차 사용자만)
    - 가짜 거절 사용자(DB) = 끝 상태 매수 < limit 인데 HOLD_LIMIT_EXCEEDED를 1번 이상 받은 사용자(명세 §9.3 ②) · (응답) = 201 < limit 인데 같은 코드를 받은 사용자"""
    per = {}
    for r in rows:
        u = r.get("user")
        d = per.setdefault(u, {"ok": 0, "hle": 0})
        if r.get("code") == "OK":
            d["ok"] += 1
        elif r.get("code") == "HOLD_LIMIT_EXCEEDED":
            d["hle"] += 1
    db = (end_state or {}).get("per_user")
    out = {"users": len(per), "over_users_resp": sum(1 for d in per.values() if d["ok"] > limit),
           "fake_reject_users_resp": sum(1 for d in per.values() if d["ok"] < limit and d["hle"]),
           "codes": code_counts(rows)}
    if db is not None:
        out["over_users_db"] = sum(1 for u in per if int(db.get(u, 0)) > limit)
        out["fake_reject_users_db"] = sum(1 for u, d in per.items() if int(db.get(u, 0)) < limit and d["hle"])
        # 응답 201 수와 끝 상태 매수가 다른 사용자 — 0이 아니면 응답과 DB 사실이 어긋난 것
        out["resp_db_mismatch_users"] = sum(1 for u, d in per.items() if int(db.get(u, 0)) != d["ok"])
    return out


def s7_seats(rows, end_state, limit):
    """S7 좌석별 판정(끝 상태 × 요청 기록).
    - 억울한 좌석 = 끝 상태 AVAILABLE(아무도 못 가짐)인데 일반 사용자(R)가 409 SEAT_NOT_AVAILABLE을 받은 좌석 · 억울한 409 = 그 좌석들의 그런 409 수
    - U 응답 코드 분포: HOLD_LIMIT_EXCEEDED = U가 매수 판정까지 갔다(좌석 락 먼저 잡았거나 counter처럼 좌석 전에 판정) · SEAT_NOT_AVAILABLE = 좌석 락에서 졌다 · OK = 매수 위반
    - 시차 실측: R 보낸 시각 − U 보낸 시각(ms, 같은 좌석) 분포"""
    seats = (end_state or {}).get("per_seat")
    by = {}
    for r in rows:
        if r.get("role") not in ("U", "R"):
            continue
        by.setdefault(r.get("seat"), []).append(r)
    out = {"seats_planned": len(seats) if seats is not None else None, "seats_requested": len(by),
           "u_codes": {}, "r_codes": code_counts([r for r in rows if r.get("role") == "R"]), "r_201": 0, "u_201": 0}
    lead = []
    wronged_seats = wronged_409 = empty = multi = 0
    wronged_by_code = {}
    for seat, reqs in by.items():
        u = [r for r in reqs if r["role"] == "U"]
        rs = [r for r in reqs if r["role"] == "R"]
        for x in u:
            out["u_codes"][x.get("code")] = out["u_codes"].get(x.get("code"), 0) + 1
        out["u_201"] += sum(1 for x in u if x.get("code") == "OK")
        n201 = sum(1 for x in rs if x.get("code") == "OK")
        out["r_201"] += n201
        multi += (n201 + sum(1 for x in u if x.get("code") == "OK")) > 1
        if u and "t0" in u[0]:
            lead += [int(x["t0"]) - int(u[0]["t0"]) for x in rs if "t0" in x]
        if seats is not None:
            st = (seats.get(seat) or {}).get("status")
            if st == "AVAILABLE":
                empty += 1
                # 명세 §9.3 ④: 빈 좌석에서 409를 받은 일반 사용자 — 코드 무관(SERIALIZABLE의 좌석 충돌 40001은 HOLD_LIMIT_EXCEEDED로 온다). 코드별 분해는 따로
                n409 = [x.get("code") for x in rs if x.get("code") not in ("OK",) and not str(x.get("code", "")).startswith("HTTP")]
                if n409:
                    wronged_seats += 1
                    wronged_409 += len(n409)
                    for c in n409:
                        wronged_by_code[c] = wronged_by_code.get(c, 0) + 1
    out["multi_201_seats"] = multi
    if seats is not None:
        out.update(empty_seats=empty, wronged_seats=wronged_seats, wronged_409=wronged_409, wronged_409_by_code=wronged_by_code)
        db_users = end_state.get("per_user") or {}
        out["u_over_limit_db"] = sum(1 for k, v in db_users.items() if 700000 < int(k) < 710000 and int(v) > limit)
    if lead:
        lead.sort()
        out["r_minus_u_ms"] = {"min": lead[0], "p50": lead[len(lead) // 2], "max": lead[-1], "neg": sum(1 for x in lead if x < 0)}
    return out


def s7_order(csv_gz):
    """ADR-007 변형 에러 순서(명세 §9.4 ⑥): S7 좌석별 U의 HOLD_LIMIT_EXCEEDED가 '팔린 좌석에 LIMIT'(명세 순서 위반)였나.
    분모(리뷰 A7): 시나리오는 U를 R보다 LEAD_MS(1ms) **먼저** 보내므로 U가 R의 201을 받은 뒤에 나가는 일(기회)은 드물다 — 위반 0이 '순서를 지켰다'인지
    '기회가 없었다'인지 가르려고 기회 수 u_after_r_201(+ 그 U의 코드 분포)과 시차 분포를 함께 낸다:
      u_minus_r_sent_ms = U 보낸 시각 − 같은 좌석 R 보낸 시각(ms) · u_minus_r201_recv_ms = U 보낸 시각 − R 201 수신 시각(pos = 양수 = 기회 수와 같은 기준).
    counter-upsert(매수 판정이 좌석보다 먼저 — U는 늘 HLE)가 양성 대조다: 기회가 있으면 그 기회는 전부 위반으로 잡혀야 한다.
    http_req_duration 행(hold — 태그 role·seat·t0 = 보낸 시각 ms, 값 = 지연 ms)과 hold_code 행(같은 태그 + code)을 (role, seat, t0)로 잇는다.
    - u_hle_after_r_201 = 같은 좌석 일반 사용자(R)의 201 응답을 k6가 **받은 시각**(t0 + 지연)이 U가 **보낸 시각**보다 앞인데 U가 HLE —
      좌석이 커밋된 뒤에 U가 읽었으므로 명세 순서라면 SEAT_NOT_AVAILABLE이어야 한다(확정 위반 — 하한: 받은 시각은 커밋보다 늦다)
    - u_hle_r_201 = 같은 좌석에서 R이 201을 받았는데 U가 HLE인 수 — 위 확정 위반 + 남는 틈(R이 커밋 전 잠근 좌석 · 시각 겹침)을 함께 센다"""
    if not csv_gz.exists():
        return None
    dur, codes = {}, []
    with gzip.open(csv_gz, "rt", newline="") as f:
        header = next(csv.reader([f.readline()]))
        i_val, i_tags, i_name = header.index("metric_value"), header.index("extra_tags"), header.index("name")
        for line in f:
            if not (line.startswith("http_req_duration,") or line.startswith("hold_code,")):
                continue
            row = next(csv.reader([line]))
            if len(row) <= i_tags:
                continue
            tags = dict(kv.split("=", 1) for kv in row[i_tags].split("&") if "=" in kv)
            if tags.get("role") not in ("U", "R") or "t0" not in tags:
                continue
            key = (tags["role"], tags.get("seat"), tags["t0"], tags.get("user"), tags.get("order"))
            if row[0] == "http_req_duration":
                if row[i_name] == "hold":
                    dur[key] = float(row[i_val])
            else:
                codes.append((key, tags.get("code")))
    by_seat = {}
    for key, code in codes:
        by_seat.setdefault(key[1], []).append((key, code))
    after = with_r = chance = 0
    chance_codes = {}
    sent_lag, recv_lag = [], []
    for seat, reqs in by_seat.items():
        r_done = [int(k[2]) + dur[k] for k, c in reqs if k[0] == "R" and c == "OK" and k in dur]
        r_sent = [int(k[2]) for k, c in reqs if k[0] == "R"]
        r_ok = any(kk[0] == "R" and cc == "OK" for kk, cc in reqs)
        for k, c in reqs:
            if k[0] != "U":
                continue
            u0 = int(k[2])
            sent_lag += [u0 - t for t in r_sent]
            recv_lag += [u0 - t for t in r_done]
            # 기회(분모 — 리뷰 A7) = U를 보낸 시각이 같은 좌석 R의 201 수신 뒤였던 U 요청: 명세 순서라면 SEAT_NOT_AVAILABLE이어야 하는 요청 전부.
            # S7-m1-late(태그 order=after_r — 같은 VU가 R 응답을 받은 뒤 보낸 U)는 시각 비교 없이 구성상 'R 응답 뒤'다: R이 201이면 기회
            # (ms 단위 t0 + 지연 추정은 1ms 안쪽에서 U 보낸 시각을 넘을 수 있어 구성상 순서를 시각으로 다시 판정하지 않는다)
            is_after = (k[4] == "after_r" and r_ok) or any(t < u0 for t in r_done)
            if is_after:
                chance += 1
                chance_codes[c] = chance_codes.get(c, 0) + 1
            if c == "HOLD_LIMIT_EXCEEDED":
                with_r += bool(r_done) or r_ok
                after += is_after

    def dist(xs):
        if not xs:
            return None
        xs = sorted(xs)
        return {"n": len(xs), "min": round(xs[0], 1), "p50": round(xs[len(xs) // 2], 1), "max": round(xs[-1], 1), "pos": sum(1 for x in xs if x > 0)}
    return {"u_hle_after_r_201": after, "u_hle_r_201": with_r,
            "u_after_r_201": chance, "u_after_r_201_codes": chance_codes,
            "u_minus_r_sent_ms": dist(sent_lag), "u_minus_r201_recv_ms": dist(recv_lag)}


def s7late_check(rep_dir):
    """S7-m1-late 회차에 기회(R 201 뒤에 보낸 U — s7_order u_after_r_201)가 1건 이상이면 exit 0 — 0이면 그 회차로는 명세 순서를 판정할 수 없다(rep_status s7late-no-opportunity)."""
    try:
        o = s7_order(Path(rep_dir) / "k6-requests.csv.gz")
    except (EOFError, OSError, ValueError, StopIteration) as e:
        print(f"s7late: 읽기 실패 {type(e).__name__}: {e}", file=sys.stderr)
        return 1
    print(f"s7late: 기회 {None if o is None else o['u_after_r_201']} · 위반 {None if o is None else o['u_hle_after_r_201']}", file=sys.stderr)
    return 0 if o and o["u_after_r_201"] > 0 else 1


def borrow_per_hold(apps):
    """요청당 커넥션 빌림 = Σ Hikari 획득 COUNT 증가분 ÷ Σ 선점 요청 증가분(after-k6.json apps — run.sh after_k6). 앱 하나라도 조회 실패(None)·선점 0이면 None(미측정).
    rep_record와 accept_check가 같은 정의를 쓴다(ADR-008 — ADR-007 rep_record 식을 함수로 뺐다, 값은 같다)."""
    acq = [a.get("acquire") for a in apps]
    holds = [a.get("hold_requests") for a in apps]
    if not apps or any(x is None for x in acq) or any(h is None for h in holds) or not sum(holds):
        return None
    return sum(x["COUNT"] for x in acq) / sum(holds)


def borrow_per_request(apps, summ):
    """ADR-008(리뷰 루프 2 D1) 요청당 커넥션 빌림 = Σ Hikari 획득 증가분 ÷ Σ(선점 + 확정 요청 증가분) — 회차 합격의 빌림 1.0은 이 값이다.
    시나리오가 보내는 앱 요청은 선점·확정 둘뿐이다(scenarios/lib/common.js). borrow_per_hold(선점만 분모)는 ADR-007 요약 호환으로 그대로 둔다 —
    S3는 확정도 빌려 선점당으로는 1.013(ADR-007 precheck)이지만 요청당으로는 1.000(남는 몇십 건 = 만료 배치 주기마다 1회).
    확정 수: after-k6.json apps의 confirm_requests(서버 http.server.requests — ADR-008 run.sh가 남긴다)가 기준. 키 자체가 없는 옛 회차(ADR-007 등)만
    k6 요약 confirm_duration count로 대신한다(source = "k6"). 키가 있는데 null(조회 실패)이면 None(미측정 — 대신 채우지 않는다).
    반환 (값 또는 None, source "server" | "k6" | None)."""
    acq = [a.get("acquire") for a in apps]
    holds = [a.get("hold_requests") for a in apps]
    if not apps or any(x is None for x in acq) or any(h is None for h in holds):
        return None, None
    if all("confirm_requests" in a for a in apps):
        conf = [a.get("confirm_requests") for a in apps]
        if any(c is None for c in conf):
            return None, "server"
        confirms, source = sum(conf), "server"
    else:
        confirms, source = metric(summ, "confirm_duration", "count", 0), "k6"
    total = sum(holds) + confirms
    # ADR-009(설계 §4d): 앱 풀을 같이 쓰는 대조(C)·정리의 빌림을 뺀다. 키가 없는 옛 회차는 0(그 기능이 없었다), 키가 있는데 null(조회 실패)이면 미측정
    drift = 0
    for a in apps:
        for k in ("reconcile_borrows", "cleanup_borrows"):
            if k in a:
                if a[k] is None:
                    return None, source
                drift += a[k]
    return ((sum(x["COUNT"] for x in acq) - drift) / total if total else None), source


# ADR-008 회차 합격(명세 §5 판정) — rep_status가 회차마다 부른다(사유가 하나라도 있으면 그 회차는 ok가 아니다).
# 빌림 허용 폭 [0.98, 1.02]: ADR-005 실측 1커넥션 방식 1.00~1.02(run.sh invalid-borrow-ratio 주석)를 '빌림 1.0'의 폭으로 쓴다. **모든 셀**(명세 §5 — 리뷰 루프 1 C1).
# 판정 값은 **요청당 빌림**(borrow_per_request — 분모 = 선점 + 확정, 리뷰 루프 2 D1). ADR-007 precheck-default 실측(20261009-adr007-ea8b85cc, 3회씩 — 확정 수는 k6 요약):
#   S3-a20 선점당 1.0129 → 요청당 1.0001 · S2 1.000 · S4 1.0001 · S7 1.0025(확정 없음 — 선점당과 같다). 기존 invalid-borrow-ratio(선점당, S3 제외)는 그대로 둔다.
BORROW_ONE = (0.98, 1.02)


def clock_off(rep_dir):
    """서버 − PC 시계 차(초 — clock-offset.json k6 앞·뒤 평균)와 그 오차 상한(가장 큰 왕복 ÷ 2). 없으면 (None, None)."""
    c = load(Path(rep_dir) / "clock-offset.json") or {}
    xs = [x for x in (c.get("before"), c.get("after")) if isinstance(x, dict) and isinstance(x.get("offset_s"), (int, float))]
    if not xs:
        return None, None
    return sum(x["offset_s"] for x in xs) / len(xs), max((x.get("rtt_s") or 0) for x in xs) / 2


def tag_rows(csv_gz, metric_name):
    """k6-requests.csv.gz의 <metric_name> 행(Counter — 요청 1건당 1행) → 태그 dict 목록(깨진 행은 뺀다). 파일 없음 = None."""
    if not csv_gz.exists():
        return None
    out = []
    with gzip.open(csv_gz, "rt", newline="") as f:
        header = next(csv.reader([f.readline()]))
        i_tags = header.index("extra_tags")
        for line in f:
            if not line.startswith(metric_name + ","):
                continue
            row = next(csv.reader([line]))
            if len(row) <= i_tags:
                continue
            out.append(dict(kv.split("=", 1) for kv in row[i_tags].split("&") if "=" in kv))
    return out


def reconcile_gaps(runs, source, w0, w1, interval_s, slack_s=10.0):
    """N-2: 대조 바퀴 결손 — 스케줄은 fixedDelay(바퀴가 끝난 뒤 주기)라 다음 바퀴 시작 ≈ 이번 시작 + 바퀴 실행 시간 + 주기.
    구간 [w0, w1](서버 epoch)에 걸친 바퀴 시작 간격이 (주기 + 앞 바퀴 실행 시간 + 여유)를 넘으면 결손. 끝단: 구간 끝까지 마지막 바퀴 이후 같은 한도,
    앞단: 구간 시작 전 바퀴가 없으면 첫 바퀴가 구간 시작 + 주기 + 여유 안에 와야 한다. 반환 (구간에 걸친 바퀴 수, 결손 목록)."""
    rs = sorted(((ts(r["started_at"]), ts(r["finished_at"])) for r in runs or [] if r.get("source") == source), key=lambda x: x[0])
    rs = [x for x in rs if x[0] <= w1]
    gaps = []
    before = [x for x in rs if x[0] <= w0]
    seq = ([before[-1]] if before else []) + [x for x in rs if x[0] > w0]
    if not seq:
        return 0, [{"from": w0, "to": w1, "limit": interval_s + slack_s, "edge": "none"}]
    if not before and seq[0][0] - w0 > interval_s + slack_s:
        gaps.append({"from": w0, "to": seq[0][0], "limit": interval_s + slack_s, "edge": "start"})
    for (s0, f0), (s1, _) in zip(seq, seq[1:]):
        lim = interval_s + (f0 - s0) + slack_s
        if s1 - s0 > lim:
            gaps.append({"from": s0, "to": s1, "limit": round(lim, 3)})
    s0, f0 = seq[-1]
    lim = interval_s + (max(f0, s0) - s0 if f0 <= w1 else w1 - s0) + slack_s
    if w1 - s0 > lim:
        gaps.append({"from": s0, "to": w1, "limit": round(lim, 3), "edge": "end"})
    n = sum(1 for s0, f0 in rs if s0 <= w1 and f0 >= w0)
    return n, gaps


def runs_in_window(rep_dir, source, k6_started, k6_ended, interval_s="60"):
    """그 출처(reconcile-app|reconcile-proc)의 대조 바퀴(counter_reconcile_run — 서버 시계)가 k6 구간(PC 시계 + 시계 차)에 결손 없이 이어졌으면 exit 0(N-2 — reconcile_gaps)."""
    db = load(Path(rep_dir) / "drift-db.json") or {}
    off, _ = clock_off(rep_dir)
    w0, w1 = ts(k6_started) + (off or 0), ts(k6_ended) + (off or 0)
    n, gaps = reconcile_gaps(db.get("reconcile_runs"), source, w0, w1, float(interval_s))
    print(f"runs-in-window: {source} 바퀴 {n} · 결손 {len(gaps)} {gaps[:3]}", file=sys.stderr)
    return 0 if n > 0 and not gaps else 1


# ---- ADR-009 효과 셀(S3e — 설계 §9 효과 지표 · 명세 §5 결정 기준 ①②) ----------------------------------------------------------
INJECT_KINDS = ("D", "L1", "L2", "H")
RECONCILE_SOURCE = {"A+C": "reconcile-app", "B+C": "reconcile-app", "A+F": "reconcile-proc", "default": "reconcile-app"}   # default = 앱 기본값 A + C(task 07a) — 기대는 A+C와 같다


def _stats(xs):
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    return {"n": len(xs), "p50": round(xs[len(xs) // 2], 3), "max": round(xs[-1], 3)}


def reconcile_summary(runs, w0=None, w1=None):
    """대조 바퀴(counter_reconcile_run) 출처별 요약 — 구간(서버 epoch)이 있으면 겹치는 바퀴만. 바퀴 시간 = finished − started(초)."""
    out = {}
    for r in runs or []:
        if w0 is not None and not (ts(r["started_at"]) <= w1 and ts(r["finished_at"]) >= w0):
            continue
        d = out.setdefault(r["source"], {"runs": 0, "pass_s": [], "chunk_ms_max": 0, "borrows": 0, "errors": 0, "skipped_chunks": 0, "found": 0, "repaired": 0,
                                         "false_positives": 0, "skipped": 0, "skipped_closed": 0, "pairs_scanned_max": 0, "skipped_schedules_max": 0,
                                         "skipped_pairs_quota_rows_max": 0, "chunks": 0, "orphan_chunks": 0, "chunk_ms_total": 0, "orphan_ms_total": 0})
        d["runs"] += 1
        d["pass_s"].append(ts(r["finished_at"]) - ts(r["started_at"]))
        d["chunk_ms_max"] = max(d["chunk_ms_max"], r["chunk_ms_max"])
        for k in ("borrows", "errors", "skipped_chunks", "found", "repaired", "false_positives", "skipped", "skipped_closed", "chunks", "orphan_chunks",
                  "chunk_ms_total", "orphan_ms_total"):
            d[k] += r.get(k) or 0
        d["pairs_scanned_max"] = max(d["pairs_scanned_max"], r.get("pairs_scanned") or 0)
        d["skipped_schedules_max"] = max(d["skipped_schedules_max"], r.get("skipped_schedules") or 0)
        d["skipped_pairs_quota_rows_max"] = max(d["skipped_pairs_quota_rows_max"], r.get("skipped_pairs") or 0)
    for d in out.values():
        d["pass_s"] = _stats(d["pass_s"])
    return out


def snap_delta(a, b, *path):
    """drift-*.json 두 스냅숏의 같은 경로 값 차분(a − b) — 하나라도 없으면 None."""
    def get(x):
        for k in path:
            x = x.get(k) if isinstance(x, dict) else None
        return x
    x, y = get(a), get(b)
    return x - y if isinstance(x, (int, float)) and isinstance(y, (int, float)) else None


GC_TIME = re.compile(r"^\[(\d{4}-\d{2}-\d{2}T[\d:.]+)([+-]\d{2})(\d{2})\]")
GC_END = re.compile(r"\bGC\((\d+)\) Pause (.+?) (\d+)M->(\d+)M\((\d+)M\) ([\d.]+)ms")
GC_SAFEPOINT = re.compile(r'Safepoint "([^"]+)".*At safepoint: (\d+) ns.*Total: (\d+) ns')


def gc_summary(gc_gz, window=None):
    """명세 §13 · 리뷰 R4: JVM GC 로그(-Xlog:gc*,safepoint — time 꾸밈) 요약. **정지(stop) = GC VM 작업의 safepoint 한 번**으로 센다:
    Serial은 Young이 승격 실패로 같은 safepoint 안에서 Full로 이어지면 GC id 두 개(Pause Young + Pause Full)가 한 정지다(실측 smoke3 control GC(110)+(111) =
    safepoint Total 102.5ms) — GC 끝 줄(Pause …)을 따로 세면 정지 수가 부풀고 최대가 작게 나온다. 그래서 safepoint 줄(VM 작업 이름 + Total)마다 그 사이 끝난
    GC id를 묶어 정지 하나로 셈 — gc_ids가 2개 이상이면 nested. GC 작업이 아닌 safepoint(Cleanup·ICBufferFull 등)는 safepoint_other로 따로.
    값: collector · stops(정지 수) · stop_ms_total·max(safepoint Total) · gc_ids(GC id 수 — 참고) · nested(GC id 여럿을 묶은 정지 수) · safepoint_other_max_ms ·
    k6 구간 같은 값(window = 서버 epoch — 앱 컨테이너 시계 = 서버 시계) · 50ms 넘는 정지 목록(시각 · 종류 · ms · GC id) · 로그 양(bytes·lines). 파일 없음·빈 파일 = None.
    ADR-010: full(Full GC가 든 정지 수 — 버킷마다) · k6_steady(k6 시작 60초 뒤 ~ 끝 — 램프 제외 정상 구간, 같은 값) · heap_max_mb(gc,init Heap Max Capacity) ·
    heap_committed_max_mb(GC 끝 줄 괄호 안 — GC 뒤 잡은 힙의 최대)."""
    if not gc_gz.exists():
        return None
    z = lambda: {"stops": 0, "stop_ms_total": 0.0, "stop_ms_max": 0.0, "nested": 0, "gc_ids": 0, "safepoint_other_max_ms": 0.0, "full": 0}
    out = {"collector": None, **z(), "lines": 0, "log_bytes": 0, "k6": z() if window else None, "k6_steady": z() if window else None,
           "long_stops": [], "unmatched_gc_ends": 0, "heap_max_mb": None, "heap_committed_max_mb": None}
    pending = []   # 직전 safepoint 뒤 끝난 GC(id, 종류)
    with gzip.open(gc_gz, "rt", errors="replace") as f:
        for line in f:
            out["lines"] += 1
            out["log_bytes"] += len(line.encode())
            m = GC_TIME.match(line)
            t = datetime.fromisoformat(f"{m.group(1)}{m.group(2)}:{m.group(3)}").timestamp() if m else None
            if out["collector"] is None and "Using " in line and "[gc" in line:
                out["collector"] = line.split("Using ", 1)[1].strip()
            if out["heap_max_mb"] is None and "Heap Max Capacity:" in line:
                hm = re.search(r"Heap Max Capacity: (\d+)([MG])", line)
                out["heap_max_mb"] = int(hm.group(1)) * (1024 if hm.group(2) == "G" else 1) if hm else None
            em = GC_END.search(line)
            if em:
                out["heap_committed_max_mb"] = max(out["heap_committed_max_mb"] or 0, int(em.group(5)))
                pending.append((int(em.group(1)), em.group(2)))
                out["gc_ids"] += 1
                continue
            sm = GC_SAFEPOINT.search(line)
            if not sm:
                continue
            ms = int(sm.group(3)) / 1e6
            in_k6 = window is not None and t is not None and window[0] <= t <= window[1]
            buckets = [out] + ([out["k6"]] if in_k6 else []) + ([out["k6_steady"]] if in_k6 and t >= window[0] + 60 else [])
            if pending:   # GC VM 작업 safepoint — 그 사이 끝난 GC id들이 한 정지
                for bk in buckets:
                    bk["stops"] += 1; bk["stop_ms_total"] += ms; bk["stop_ms_max"] = max(bk["stop_ms_max"], ms)
                    bk["nested"] += len(pending) > 1
                    bk["full"] += any(k.startswith("Full") for _, k in pending)
                    if bk is not out:
                        bk["gc_ids"] += len(pending)
                if ms > 50 and len(out["long_stops"]) < 200:
                    out["long_stops"].append({"t": t, "op": sm.group(1), "ms": round(ms, 3), "gc": [f"{i}:{k}" for i, k in pending]})
                pending = []
            else:
                for bk in buckets:
                    bk["safepoint_other_max_ms"] = max(bk["safepoint_other_max_ms"], ms)
    out["unmatched_gc_ends"] = len(pending)   # 파일 끝에 safepoint 줄 없이 남은 GC 끝(보통 0)
    return out if out["lines"] else None


def gc_report(rep_dir):
    """gc.json = {app: gc_summary(gc.log.gz), reconciler: …(F면)} — k6 구간은 k6-window.json + clock-offset.json(서버 시계로 옮김)."""
    rep_dir = Path(rep_dir)
    win = load(rep_dir / "k6-window.json") or {}
    off, _ = clock_off(rep_dir)
    w = (ts(win["k6_started"]) + (off or 0), ts(win["k6_ended"]) + (off or 0)) if win.get("k6_started") and win.get("k6_ended") else None
    out = {"app": gc_summary(rep_dir / "gc.log.gz", w)}
    if (rep_dir / "reconciler-gc.log.gz").exists():
        out["reconciler"] = gc_summary(rep_dir / "reconciler-gc.log.gz", w)
    print(json.dumps(out, ensure_ascii=False))
    return 0


def first_request(rep_dir):
    """k6 첫 요청 시각(PC epoch) — 리뷰 H5. S4c는 k6 요약 setup_data.startedAt(ms — 첫 반복 직전, 정확), 그 밖은 k6-requests.csv.gz의 http_req_duration
    행 중 가장 이른 (타임스탬프 − 지연)(타임스탬프가 초 단위라 ±1초). (epoch, 출처) · 없으면 (None, None)."""
    rep_dir = Path(rep_dir)
    st = ((load(rep_dir / "k6-summary.json") or {}).get("setup_data") or {}).get("startedAt")
    if isinstance(st, (int, float)):
        return st / 1000, "setup_data.startedAt"
    f = rep_dir / "k6-requests.csv.gz"
    if not f.exists():
        return None, None
    best = None
    try:
        with gzip.open(f, "rt", newline="") as fh:
            h = next(csv.reader([fh.readline()]))
            i_ts, i_v = h.index("timestamp"), h.index("metric_value")
            for line in fh:
                if not line.startswith("http_req_duration,"):
                    continue
                r = next(csv.reader([line]))
                x = int(r[i_ts]) - float(r[i_v]) / 1000
                best = x if best is None or x < best else best
    except (EOFError, OSError, ValueError, StopIteration):
        return None, None
    return (best, "csv(1s)") if best is not None else (None, None)


def reconcile_phase(rep_dir, seed_epoch, k6_t0, k6_end):
    """리뷰 H2·R1: 시드 끝 ~ k6 끝 사이 대조 바퀴(counter_reconcile_run — 서버 시계 → PC로 옮김)의 시작·끝(k6_t0 기준 초) · k6 전 완료 바퀴 수 · k6 구간 첫 바퀴.
    대조 없는 조건은 바퀴가 없다(빈 목록). 출처는 기록된 그대로(reconcile-app·reconcile-proc)."""
    db = load(Path(rep_dir) / "drift-db.json") or {}
    off, _ = clock_off(rep_dir)
    off = off or 0
    runs = []
    for r in db.get("reconcile_runs") or []:
        s0, f0 = ts(r["started_at"]) - off, ts(r["finished_at"]) - off
        if s0 >= seed_epoch - 1 and s0 <= k6_end:
            runs.append({"source": r["source"], "start_rel_s": round(s0 - k6_t0, 3), "end_rel_s": round(f0 - k6_t0, 3)})
    runs.sort(key=lambda x: x["start_rel_s"])
    first_in = next((x for x in runs if x["start_rel_s"] >= 0), None)
    return {"passes": runs, "completed_before_k6": sum(1 for x in runs if x["end_rel_s"] < 0), "first_in_k6": first_in}


def pre_k6_finish(rep_dir):
    """pre-k6.json(run.sh가 k6 시작 때 쓴 것)에 회차 끝 값을 더해 다시 쓴다 — 첫 요청 시각(H5 — 판정 기준) · 대조 바퀴 위상(H2) · DB 상태(R2: 시드 끝·k6 직전 스냅숏 차 ·
    DB 기동 → k6 시작). 판정은 rep_status(pre-k6-mismatch = |시드 끝 → 첫 요청 − target| > tol)."""
    rep_dir = Path(rep_dir)
    pk = load(rep_dir / "pre-k6.json")
    if not isinstance(pk, dict):
        return 1
    win = load(rep_dir / "k6-window.json") or {}
    fr, src = first_request(rep_dir)
    pk.update(first_request_epoch=fr, first_request_source=src,
              seed_to_first_request_s=round(fr - pk["seed_done_epoch"], 3) if fr else None,
              k6_startup_s=round(fr - pk["k6_start_epoch"], 3) if fr else None)
    k6_end = ts(win["k6_ended"]) if win.get("k6_ended") else pk["k6_start_epoch"] + 3600
    pk["reconcile_phase"] = reconcile_phase(rep_dir, pk["seed_done_epoch"], pk["k6_start_epoch"], k6_end)
    a, b = load(rep_dir / "db-state-seed.json"), load(rep_dir / "db-state-k6.json")
    off, _ = clock_off(rep_dir)
    if isinstance(a, dict) and isinstance(b, dict):
        ps = b.get("postmaster_start_epoch")
        pk["db_start_to_k6_s"] = round(pk["k6_start_epoch"] + (off or 0) - ps, 3) if isinstance(ps, (int, float)) else None
        pk["db_start_to_seed_s"] = round(pk["seed_done_epoch"] + (off or 0) - ps, 3) if isinstance(ps, (int, float)) else None
        d = {}
        for t, x in (b.get("tables") or {}).items():
            y = (a.get("tables") or {}).get(t) or {}
            d[t] = {k: (x.get(k) or 0) - (y.get(k) or 0) for k in ("autovacuum_count", "autoanalyze_count", "vacuum_count", "analyze_count")}
            d[t]["last_autovacuum"], d[t]["last_autoanalyze"] = x.get("last_autovacuum"), x.get("last_autoanalyze")
        ca, cb = a.get("checkpoints") or {}, b.get("checkpoints") or {}
        pk["db_during_wait"] = {"tables": d, "checkpoints": {k: (cb.get(k) or 0) - (ca.get(k) or 0) for k in ("timed", "req")},
                                "autovacuum_workers_at_k6": b.get("autovacuum_workers"), "same_postmaster": a.get("postmaster_start") == b.get("postmaster_start")}
    else:
        pk["db_start_to_k6_s"] = None
        pk["db_during_wait"] = None
    (rep_dir / "pre-k6.json").write_text(json.dumps(pk, ensure_ascii=False, indent=1))
    return 0


def tick_failure_causes(app_log_gz, with_events=False):
    """만료 틱 실패 원인(H-7) — app.log.gz의 스케줄 작업 예외 블록('Unexpected error occurred in scheduled task' 뒤 다음 로그 줄까지 — Caused by 포함)을 분류해 센다:
    SR001(B 트리거 엄격 — V8 RAISE 'counter row missing'. 로그에 SQLSTATE 자체는 찍히지 않는다) · 23514(CHECK cnt >= 0 — 'check constraint') ·
    missing-row(legacy 감소 '카운터 행 없음') · 40P01(deadlock) · 그 밖은 첫 예외 클래스. 파일 없음 = None."""
    if not app_log_gz.exists():
        return None
    out, block, events = {}, None, []
    stamp = re.compile(r"^\d{4}-\d{2}-\d{2}T")
    block_t = [None]

    def close(lines):
        t = "\n".join(lines)
        first = next((m.group(1) for m in (re.match(r"^\s*([\w.$]+(?:Exception|Error))\b", x) for x in lines) if m), "unknown")
        k = ("SR001" if ("SR001" in t or "counter row missing" in t) else "23514" if ("23514" in t or "check constraint" in t)
             else "missing-row" if "카운터 행 없음" in t else "40P01" if ("40P01" in t or "deadlock" in t.lower()) else first.rsplit(".", 1)[-1])
        out[k] = out.get(k, 0) + 1
        events.append({"t": block_t[0], "cause": k})

    with gzip.open(app_log_gz, "rt", errors="replace") as f:
        for line in f:
            if stamp.match(line):
                if block is not None:
                    close(block)
                block = [] if "Unexpected error occurred in scheduled task" in line else None
                if block is not None:
                    try:
                        block_t[0] = ts(line.split()[0])   # 로그 시각(앱 컨테이너 UTC — 서버 시계)
                    except ValueError:
                        block_t[0] = None
            elif block is not None:
                block.append(line)
    if block is not None:
        close(block)
    if with_events:
        return out, events
    return out


def effect(rep_dir, cond):
    """효과 지표(effect.json) — 입력: injected.json(주입) · drift-db.json(V7 기록) · end-pairs.json(끝 쌍 상태) · drift-before/end.json(틱 지표) · consistency.json · k6-window.json.
    쌍마다 탐지(주입 뒤 첫 기록 — 어느 action이든)·복구(첫 repaired) 시각과 걸린 초, 끝 상태(cnt = 실제?). 종류별 요약. 주입 밖 쌍의 repaired = 오탐 복구(결정 기준 ②).
    skipped는 실패(reason ≠ closed)와 마감 제외(reason = closed — L3-4)를 나눈다. 대조 바퀴 출처별(시간·청크 최대·빌림). 기대값 대조 → unexpected 목록(조건별 — 머리말 표)."""
    rep_dir = Path(rep_dir)
    inj, db, end = load(rep_dir / "injected.json") or {}, load(rep_dir / "drift-db.json") or {}, load(rep_dir / "end-pairs.json") or {}
    sb, se = load(rep_dir / "drift-before.json") or {}, load(rep_dir / "drift-end.json") or {}
    cons, win = load(rep_dir / "consistency.json") or {}, load(rep_dir / "k6-window.json") or {}
    off, _ = clock_off(rep_dir)
    k0 = ts(win["k6_started"]) + (off or 0) if win.get("k6_started") else None
    pairs = {}
    for rnd in inj.get("rounds") or []:
        for x in rnd.get("pairs") or []:
            pairs[(x["schedule_id"], x["user_id"])] = {"kind": x["kind"], "round_s": rnd.get("at_s"), "injected_at": rnd["at_epoch"]}
    logs = [r for r in db.get("drift_log") or [] if k0 is None or ts(r["at"]) >= k0 - 1]
    by_pair = {}
    for r in logs:
        by_pair.setdefault((r["schedule_id"], r["user_id"]), []).append(r)
    end_by = {(x["schedule_id"], x["user_id"]): x for x in end.get("injected") or []}
    per = []
    for key, v in sorted(pairs.items(), key=lambda kv: (kv[1]["injected_at"], kv[0])):
        rows = sorted((r for r in by_pair.get(key, []) if ts(r["at"]) >= v["injected_at"]), key=lambda r: (ts(r["at"]), r["id"]))
        rep = next((r for r in rows if r["action"] == "repaired"), None)
        e = end_by.get(key)
        per.append({"schedule_id": key[0], "user_id": key[1], "kind": v["kind"], "round_s": v["round_s"],
                    "detected_s": round(ts(rows[0]["at"]) - v["injected_at"], 3) if rows else None, "detected_by": rows[0]["source"] if rows else None,
                    "first_action": rows[0]["action"] if rows else None,
                    "repaired_s": round(ts(rep["at"]) - v["injected_at"], 3) if rep else None, "repaired_by": rep["source"] if rep else None,
                    "actions": [f'{r["source"]}:{r["action"]}{":" + r["reason"] if r.get("reason") else ""}' for r in rows],
                    "end_cnt": e.get("cnt") if e else None, "end_actual": e.get("actual") if e else None,
                    "end_matched": ((e.get("cnt") or 0) == e.get("actual")) if e else None})
    kinds = {}
    for k in INJECT_KINDS:
        xs = [x for x in per if x["kind"] == k]
        kinds[k] = {"injected": len(xs), "detected": sum(1 for x in xs if x["detected_s"] is not None), "repaired": sum(1 for x in xs if x["repaired_s"] is not None),
                    "end_matched": sum(1 for x in xs if x["end_matched"]), "repaired_by": sorted({x["repaired_by"] for x in xs if x["repaired_by"]}),
                    "detect_s": _stats([x["detected_s"] for x in xs]), "repair_s": _stats([x["repaired_s"] for x in xs])}
    # 오탐 복구 = 주입 밖 쌍의 repaired + (H-17) 주입 쌍인데 주입 **전**에 repaired(그때는 어긋남이 없었다)
    false_repairs = [r for r in logs if r["action"] == "repaired"
                     and ((r["schedule_id"], r["user_id"]) not in pairs or ts(r["at"]) < pairs[(r["schedule_id"], r["user_id"])]["injected_at"])]
    skipped = [r for r in logs if r["action"] == "skipped"]
    inj_mismatch_end = sum(1 for x in per if x["end_matched"] is False)
    end_missing = sum(1 for x in per if x["end_matched"] is None)   # H-6: 끝 상태가 없는 주입 쌍 — accept effect-check-failed
    causes, fail_events = tick_failure_causes(rep_dir / "app.log.gz", with_events=True) if (rep_dir / "app.log.gz").exists() else (None, None)
    mismatch_total = end.get("mismatch_total")
    tick_failed, tick_ok = snap_delta(se, sb, "app", "tick", "failed"), snap_delta(se, sb, "app", "tick", "ok")
    w1 = ts(win["k6_ended"]) + (off or 0) if win.get("k6_ended") else None
    out = {
        "cond": cond, "inject_plan": inj.get("plan"), "rounds": [{k: r.get(k) for k in ("at_s", "ok", "at", "counts", "error")} for r in inj.get("rounds") or []],
        "kinds": kinds, "pairs": per,
        "false_repairs": len(false_repairs), "false_repair_pairs": [[r["schedule_id"], r["user_id"], r["source"]] for r in false_repairs[:50]],
        "false_positives": sum(1 for r in logs if r["action"] == "false-positive"),
        "skipped_failure": sum(1 for r in skipped if r.get("reason") != "closed"),
        "skipped_failure_by_reason": {k: sum(1 for r in skipped if r.get("reason") == k) for k in sorted({r.get("reason") or "-" for r in skipped if r.get("reason") != "closed"})},
        "skipped_closed": sum(1 for r in skipped if r.get("reason") == "closed"),
        "tick_failed": tick_failed, "tick_ok": tick_ok, "stale_held_seats_end": cons.get("v_stale_held_seats"), "tick_failure_causes": causes,
        "end_missing": end_missing,
        "mismatch_open_end": mismatch_total, "injected_mismatch_end": inj_mismatch_end,
        "noninjected_mismatch": (mismatch_total - inj_mismatch_end) if isinstance(mismatch_total, int) else None,
        "reconcile": reconcile_summary(db.get("reconcile_runs"), k0, (w1 + 400) if w1 else None),
        "drift_log_rows_window": len(logs), "drift_log_truncated": (db.get("drift_log_total") or 0) > len(db.get("drift_log") or []),
    }
    # 조건별 기대(설계 v1 §9 effect-unexpected + 명세 §11 B 예외) — 어긋나면 이유를 문자열로
    un = []
    rep_n = lambda ks: sum(kinds[k]["repaired"] for k in ks)
    inj_n = lambda ks: sum(kinds[k]["injected"] for k in ks)
    matched = lambda ks: all(x["end_matched"] for x in per if x["kind"] in ks)
    stale = cons.get("v_stale_held_seats")
    src_ok = lambda allowed: all(x["repaired_by"] in (None, *allowed) for x in per)
    if not per:
        un.append("no-injected-pairs")
    elif cond == "control":
        if not (tick_failed or 0) > 0:
            un.append("control: 틱 실패 0 — 주입이 만료 정지 경로에 닿지 않았다")
        if any(x["detected_s"] is not None for x in per):
            un.append("control: 후보가 꺼져 있는데 주입 쌍 기록이 있다")
        # H-7: 정지 원인은 주입(행 없음 · CHECK)이어야 한다
        if causes is None or not causes or any(k not in ("missing-row", "23514") for k in causes):
            un.append(f"control: 틱 실패 원인 {causes} — 주입 원인(missing-row·23514)만 기대")
    elif cond in ("A", "A+C", "A+F", "B+C", "default"):
        recon = RECONCILE_SOURCE.get(cond)
        # H-3: 만료가 실제로 돌았고(틱 성공 > 0) 끝에 오래된 HELD 0
        if not (tick_ok or 0) > 0:
            un.append(f"{cond}: 틱 성공 0 — 만료가 돌지 않았다")
        if stale != 0:
            un.append(f"{cond}: 끝 오래된 HELD {stale}")
        if cond == "A":
            if rep_n(("D", "L1")) != inj_n(("D", "L1")):
                un.append(f"A: D·L1 복구 {rep_n(('D', 'L1'))}/{inj_n(('D', 'L1'))}")
            if not src_ok(("expiry-repair",)):
                un.append("A: expiry-repair 밖의 복구 출처")
            if rep_n(("L2", "H")) != 0:
                un.append(f"A: L2·H 복구 {rep_n(('L2', 'H'))} — A는 조용한 어긋남을 못 본다(다른 경로가 고쳤다)")
            if not matched(("D", "L1")):
                un.append("A: 끝에 D·L1 쌍이 cnt ≠ 실제")   # H-6
        else:
            if rep_n(INJECT_KINDS) != inj_n(INJECT_KINDS):
                un.append(f"{cond}: 복구 {rep_n(INJECT_KINDS)}/{inj_n(INJECT_KINDS)}")
            if not matched(INJECT_KINDS):
                un.append(f"{cond}: 끝에 주입 쌍 {inj_mismatch_end}개가 cnt ≠ 실제")   # H-6
            # H-3: 복구 출처 = A 경로(expiry-repair — B+C는 A 없음)와 그 조건의 대조뿐. L2·H는 대조만
            allowed = (recon,) if cond == "B+C" else ("expiry-repair", recon)
            if not src_ok(allowed):
                un.append(f"{cond}: 복구 출처가 {allowed} 밖")
            if any(x["kind"] in ("L2", "H") and x["repaired_by"] not in (None, recon) for x in per):
                un.append(f"{cond}: L2·H 복구 출처가 {recon}가 아니다")
        if cond != "B+C" and tick_failed != 0:
            un.append(f"{cond}: 틱 실패 {tick_failed}")
        if cond == "B+C" and (tick_failed or 0) > 0:
            # H-10·N-3: B 예외(명세 §11 · L2-5)는 일대일로만 — 실패 틱마다 로그 예외(시각)가 있고, 그 시각 ±1초 안에 주입 쌍의 expiry-trigger skipped
            # (reason SR001·23514 — ExpiryCounters.afterRollback이 롤백 직후 독립 트랜잭션으로 쓴다)가 그 쌍의 주입 → 복구 구간 안에 있어야 한다.
            # 로그를 못 읽었거나(파서 결과 없음) 회차 끝 스냅숏까지의 로그 예외 수 ≠ 틱 실패 수면 effect-check-failed, 설명 안 되는 틱이 하나라도 있으면 unexpected
            t_end = ts(se["app"]["t"]) if isinstance(se.get("app"), dict) and se["app"].get("t") else None
            evs = [e for e in fail_events or [] if e["t"] is not None and (k0 is None or e["t"] >= k0 - 1) and (t_end is None or e["t"] <= t_end)]
            trig = [r for r in logs if r["action"] == "skipped" and r["source"] == "expiry-trigger"]
            win = {(x["schedule_id"], x["user_id"]): (pairs[(x["schedule_id"], x["user_id"])]["injected_at"],
                                                       pairs[(x["schedule_id"], x["user_id"])]["injected_at"] + (x["repaired_s"] if x["repaired_s"] is not None else 1e9))
                   for x in per}
            ok_row = lambda r: (r.get("reason") in ("SR001", "23514") and (r["schedule_id"], r["user_id"]) in win
                                and win[(r["schedule_id"], r["user_id"])][0] <= ts(r["at"]) <= win[(r["schedule_id"], r["user_id"])][1] + 1)
            bad = [r for r in trig if not ok_row(r)]
            if bad:
                un.append(f"B+C: 주입 원인·구간 밖 틱 실패 기록 {len(bad)}")
            if not evs:
                out["tick_log_unreadable"] = True   # accept → effect-check-failed
            elif len(evs) != tick_failed:
                out["tick_log_unreadable"] = True
                out["tick_log_events"] = len(evs)
            else:
                unexplained = [e for e in evs if e["cause"] not in ("SR001", "23514")
                               or not any(ok_row(r) and abs(ts(r["at"]) - e["t"]) <= 1 for r in trig)]
                if unexplained:
                    un.append(f"B+C: 설명되지 않는 실패 틱 {len(unexplained)}/{len(evs)}")
                out["tick_explained"] = len(evs) - len(unexplained)
    else:
        un.append(f"효과 셀 대상 조건이 아니다: {cond}")
    if tick_failed is None or tick_ok is None:
        un.append("틱 지표 없음")
    out["unexpected"] = un
    (rep_dir / "effect.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    return out


# ---- ADR-009 마감 임박 셀(S3c — 설계 §9 사유) ----------------------------------------------------------------------------------
PRE_OK_WINDOW_S = 330


def close_check(rep_dir, cleanup, add):
    rep_dir = Path(rep_dir)
    cj, pre, ce, db = (load(rep_dir / f) for f in ("close.json", "pre-close.json", "close-end.json", "drift-db.json"))
    off, err = clock_off(rep_dir)
    if not isinstance(cj, dict) or not isinstance(cj.get("starts_at_epoch"), (int, float)) or off is None:
        add("close-check-failed")
        return
    sa, margin = cj["starts_at_epoch"], 0.005 + (err or 0)
    holds = tag_rows(rep_dir / "k6-requests.csv.gz", "hold_code")
    post = [r for r in holds or [] if "t0" in r and int(r["t0"]) / 1000 + off > sa + margin]
    # H-2: 마감 전 성공 표본 창 = starts_at − PRE_OK_WINDOW_S ~ starts_at(시나리오가 S3c의 성공 선점을 모두 남긴다 — 마지막 성공은 보통 둘째 물결 약 300~350초)
    pre_ok = [r for r in holds or [] if "t0" in r and r.get("code") == "OK" and sa - PRE_OK_WINDOW_S <= int(r["t0"]) / 1000 + off < sa - margin]
    if holds is None:
        add("close-check-failed")
    elif not post:
        add("close-no-hold-after")            # 판정 기회 없음(마감 뒤 보낸 선점 0)
    else:
        if any(r.get("code") == "OK" for r in post):
            add("close-leak")
        if any(r.get("code") not in ("OK", "SCHEDULE_CLOSED") for r in post):
            add("close-wrong-code")           # H-2: 마감 뒤 선점은 SCHEDULE_CLOSED여야 한다(마감 → 좌석 → 매수 순서 · 5xx·타임아웃 포함)
    if holds is not None and not pre_ok:
        add("close-no-hold-before")           # H-2: 기록 창(마감 전 CODE_WINDOW) 안에 성공 선점 표본이 없다 — 마감 전에 이미 막혔는지 판정할 수 없다
    confs = tag_rows(rep_dir / "k6-requests.csv.gz", "confirm_code")
    cpost = [r for r in confs or [] if "t0" in r and int(r["t0"]) / 1000 + off > sa + margin]
    if confs is None:
        add("close-check-failed")
    elif not any(r.get("code") == "OK" for r in cpost):
        add("close-no-confirm-after")         # H-5: 마감 뒤 유효 홀드의 확정 성공 0 — 명세 §2 '확정은 막지 않음'을 부하로 확인하지 못했다
    if confs is not None and any(r.get("code") not in ("OK", "HOLD_EXPIRED") for r in cpost):
        add("confirm-after-close-failed")
    # H-8: 표본 시각 허용 창 = starts_at − 5초 ± (2초 + 시계 오차) — 너무 이르면 마감 직전 정합의 증거가 아니다
    if not isinstance(pre, dict) or not isinstance(pre.get("mismatches"), int) or not isinstance(pre.get("t_epoch"), (int, float)) \
       or abs(pre["t_epoch"] - (sa - 5)) > 2 + (err or 0):
        add("close-check-failed")             # 표본이 없거나 허용 창 밖(마감 뒤 포함)에 찍혔다
    elif pre["mismatches"] > 0:
        add("pre-close-mismatch")
    runs = (db or {}).get("cleanup_runs")
    if not isinstance(runs, list):
        add("close-check-failed")
    elif any((r.get("holds_seen") or 0) > 0 and (r.get("rows_deleted") or 0) > 0 for r in runs):
        add("cleanup1-early")
    if not isinstance(ce, dict):
        add("close-check-failed")
    elif cleanup == "schedule":
        # H-9: 끝 홀드 0(만료가 끝났다) · 마감 회차의 쿼터 행 0 · 실제로 지운 기록(rows_deleted > 0) ≥ 1
        if (ce.get("closed_quota_rows_no_holds") or 0) > 0 or ce.get("holds") != 0 \
           or not any((r.get("rows_deleted") or 0) > 0 for r in runs or [] if r.get("mode") == "schedule"):
            add("cleanup1-incomplete")
    elif cleanup == "zero":
        if (ce.get("zero_rows_actual0") or 0) > 0 or not any((r.get("rows_deleted") or 0) > 0 for r in runs or [] if r.get("mode") == "zero"):
            add("cleanup2-left")
    # H-9: 마감 임박 셀에는 주입이 없다 — 틱 실패(k6 직전 → 회차 끝) > 0이면 정리·마감이 만료를 막은 것
    tf = snap_delta(load(rep_dir / "drift-end.json") or {}, load(rep_dir / "drift-before.json") or {}, "app", "tick", "failed")
    if tf is None:
        add("close-check-failed")
    elif tf > 0:
        add("close-tick-failed")


# ADR-008 회차 합격(명세 §5 판정) — rep_status가 회차마다 부른다(사유가 하나라도 있으면 그 회차는 ok가 아니다).
# 빌림 허용 폭 [0.98, 1.02]: ADR-005 실측 1커넥션 방식 1.00~1.02(run.sh invalid-borrow-ratio 주석)를 '빌림 1.0'의 폭으로 쓴다. **모든 셀**(명세 §5 — 리뷰 루프 1 C1).
# 판정 값은 **요청당 빌림**(borrow_per_request — 분모 = 선점 + 확정, 리뷰 루프 2 D1 · ADR-009: 분자에서 대조·정리 빌림을 뺀다).
BORROW_ONE = (0.98, 1.02)


def accept_check(rep_dir, cell, cond="control", cleanup="none"):
    """ADR-009 회차 합격(조건별 기대값). 사유(stdout 한 줄에 하나):
    ADR-008 그대로: over-limit · counter-mismatch · counter-mismatch-missing · accept-consistency-missing · s2-check-failed · borrow-not-one · borrow-missing · accept-check-failed
      (효과 셀 S3e의 counter-mismatch = **주입하지 않은** 열린 회차 쌍의 끝 어긋남 > 0 — 주입 쌍의 어긋남은 조건별 기대로 effect-unexpected가 본다.
       control·A의 남은 주입 어긋남·만료 정지는 결과이지 하네스 실패가 아니다)
    ADR-009: counter-judge-vacuous(판정기가 부하 대상 회차를 전부 뺐다 — 명세 §11 N3, 마감 임박 셀 제외) · counter-excluded-missing ·
      schedule-closed-seen(마감 없는 셀에서 SCHEDULE_CLOSED 응답 > 0 — 시드 시한폭탄) ·
      효과 셀: effect-unexpected · false-repair(주입 밖 쌍 repaired > 0) · effect-check-failed ·
      마감 임박 셀: close-leak · confirm-after-close-failed · cleanup1-early · cleanup1-incomplete · cleanup2-left · pre-close-mismatch ·
                    close-no-hold-after · close-no-confirm-after(판정 기회 없음) · close-check-failed.
    항목마다 따로 try — 한 항목의 예외가 다른 항목의 사유를 지우지 않는다. exit 0 = 합격, 1 = 사유 있음."""
    rep_dir = Path(rep_dir)
    base = cell.split("-a")[0].split("-m")[0]
    close_cell, effect_cell = base == "S3c", base == "S3e"
    reasons = []
    add = lambda r: r in reasons or reasons.append(r)
    state = {"limit": 2, "codes": None, "codes_read": False}

    def consistency():
        cons = load(rep_dir / "consistency.json")
        if not isinstance(cons, dict) or not isinstance(cons.get("v_over_limit_users"), (int, float)):
            add("accept-consistency-missing")
            return
        state["limit"] = cons.get("max_per_user_limit", 2)
        if cons["v_over_limit_users"] != 0:
            add("over-limit")
        cm = cons.get("v_counter_mismatch")
        if not isinstance(cm, (int, float)):
            add("counter-mismatch-missing")
        elif effect_cell:
            eff = load(rep_dir / "effect.json")
            nm = (eff or {}).get("noninjected_mismatch")
            if not isinstance(nm, int):
                add("effect-check-failed")
            elif nm != 0:
                add("counter-mismatch")
        elif cm != 0:
            add("counter-mismatch")
        if not close_cell:
            ex, seed = cons.get("counter_excluded_schedules"), (load(rep_dir / "seed.json") or {}).get("schedules")
            if not isinstance(ex, (int, float)):
                add("counter-excluded-missing")
            elif isinstance(seed, int) and seed > 0 and ex >= seed:
                add("counter-judge-vacuous")

    def codes():
        if not state["codes_read"]:
            state["codes"], state["codes_read"] = hold_codes(rep_dir / "k6-requests.csv.gz"), True
        return state["codes"]

    def s2():
        rows, end = codes(), load(rep_dir / "end-state.json")
        if rows is None or not isinstance(end, dict) or not isinstance(end.get("per_user"), dict):
            add("s2-check-failed")
            return
        r = s2_users(rows, end, state["limit"])
        if r.get("over_users_resp") or r.get("over_users_db"):
            add("over-limit")

    def schedule_closed():
        summ = load(rep_dir / "k6-summary.json")
        if summ is not None and metric(summ, "hold_closed") > 0:
            add("schedule-closed-seen")

    def effect_check():
        eff = load(rep_dir / "effect.json")
        if not isinstance(eff, dict) or not isinstance(eff.get("unexpected"), list):
            add("effect-check-failed")
            return
        if eff["unexpected"]:
            add("effect-unexpected")
        if eff.get("end_missing") != 0 or eff.get("tick_log_unreadable"):
            add("effect-check-failed")   # H-6: 주입 쌍의 끝 상태가 빠졌다 · N-3: B+C 틱 실패 로그를 못 읽었다(틱 수와 대조 불가)
        if eff.get("false_repairs"):
            add("false-repair")

    def close():
        close_check(rep_dir, cleanup, add)

    borrow = None

    def borrow_check():
        nonlocal borrow
        after = load(rep_dir / "after-k6.json") or {}
        borrow, _src = borrow_per_request(after.get("apps") or [], load(rep_dir / "k6-summary.json"))
        if borrow is None:
            add("borrow-missing")
        elif not (BORROW_ONE[0] <= borrow <= BORROW_ONE[1]):
            add("borrow-not-one")

    checks = [consistency]
    if base == "S2":
        checks.append(s2)
    if not close_cell:
        checks.append(schedule_closed)
    if effect_cell:
        checks.append(effect_check)
    if close_cell:
        checks.append(close)
    checks.append(borrow_check)
    for f in checks:
        try:
            f()
        except Exception as e:   # 한 항목의 실패는 그 항목만 — 사유로 드러내고 나머지 항목은 계속 본다
            print(f"accept: {f.__name__} 판정 실패 {type(e).__name__}: {e}", file=sys.stderr)
            add("accept-check-failed")
    print(f"accept: {cell} cond={cond} cleanup={cleanup} 사유 {reasons or '없음'} · 빌림 {borrow}", file=sys.stderr)
    for r in reasons:
        print(r)
    return 1 if reasons else 0


def timer(t):
    """Micrometer 타이머 차분(run.sh after_k6: COUNT·TOTAL_TIME 초 — 부하 중 증가분, MAX 초 — 최근 약 2분 창) → ms."""
    if not t:
        return None, None, None
    mean = t["TOTAL_TIME"] / t["COUNT"] * 1000 if t["COUNT"] else None
    return t["COUNT"], mean, t["MAX"] * 1000


# ---- ADR-006 서버 I/O 차분 --------------------------------------------------------------------------
WAL_KEYS = ("wal_records", "wal_fpi", "wal_bytes", "wal_buffers_full", "wal_write", "wal_sync", "wal_write_time", "wal_sync_time")
IO_KEYS = ("reads", "read_time", "writes", "write_time", "writebacks", "writeback_time", "extends", "extend_time", "fsyncs", "fsync_time")
DISK_KEYS = ("reads", "read_ms", "writes", "write_sectors", "write_ms", "io_ms", "weighted_ms", "flushes", "flush_ms")
SLRU_KEYS = ("blks_zeroed", "blks_hit", "blks_read", "blks_written", "blks_exists", "flushes", "truncates")
BG_KEYS = ("checkpoints_timed", "checkpoints_req", "checkpoint_write_time", "checkpoint_sync_time", "buffers_checkpoint", "buffers_backend", "buffers_backend_fsync")


def _d(a, b, k):
    x, y = (a or {}).get(k), (b or {}).get(k)
    return None if x is None or y is None else x - y


def _ratio(n, d):
    return n / d if n is not None and d else None


def io_delta(rep_dir, hold_requests):
    """io-after − io-before (lib.sh io_snapshot). 시간 단위는 PostgreSQL 통계 = ms, diskstats = ms.
    - wal: pg_stat_wal 차분 + wal_sync_avg_ms(= Δwal_sync_time ÷ Δwal_sync — WAL fsync 1회 평균) · wal_write_avg_ms ·
           wal_bytes_per_hold · commits_per_wal_sync(Δxact_commit ÷ Δwal_sync — 그룹 커밋 정도)
    - io: pg_stat_io 전 행 합(관계 파일 — PG16은 WAL을 여기 넣지 않는다) + fsync_avg_ms·write_avg_ms
    - bgwriter: 체크포인트 수(timed+req)·쓰기/동기화 ms
    - disk: 장치별 diskstats 차분 + write_wait_ms(Δwrite_ms ÷ Δwrites — 쓰기 1건 평균 대기, iostat w_await) · flush_avg_ms(Δflush_ms ÷ Δflushes) ·
            util_pct(Δio_ms ÷ 경과) · aqu(Δweighted_ms ÷ 경과) · write_mb. disk_main = 물리 장치(type disk) 중 첫 번째.
    stats_reset이 두 스냅숏 사이에 바뀌었으면(통계 초기화 — 새 컨테이너라 보통 같다) 차분을 믿을 수 없어 reset_changed=True."""
    b, a = load(rep_dir / "io-before.json"), load(rep_dir / "io-after.json")
    if not b or not a or not (a.get("pg") or {}).get("wal") or not (b.get("pg") or {}).get("wal"):
        return None
    pa, pb = a["pg"], b["pg"]
    elapsed_ms = (a["t0"] - b["t0"]) * 1000
    wal = {k: _d(pa["wal"], pb["wal"], k) for k in WAL_KEYS}
    commits = _d(pa.get("db"), pb.get("db"), "xact_commit")
    wal.update(wal_sync_avg_ms=_ratio(wal["wal_sync_time"], wal["wal_sync"]), wal_write_avg_ms=_ratio(wal["wal_write_time"], wal["wal_write"]),
               wal_bytes_per_hold=_ratio(wal["wal_bytes"], hold_requests), commits=commits,
               commits_per_wal_sync=_ratio(commits, wal["wal_sync"]))
    io = {k: 0 for k in IO_KEYS}
    for key, row in (pa.get("io") or {}).items():
        before = (pb.get("io") or {}).get(key) or {}
        for k in IO_KEYS:
            if row.get(k) is not None:
                io[k] += row[k] - (before.get(k) or 0)
    io.update(fsync_avg_ms=_ratio(io["fsync_time"], io["fsyncs"]), write_avg_ms=_ratio(io["write_time"], io["writes"]))
    bg = {k: _d(pa.get("bgwriter"), pb.get("bgwriter"), k) for k in BG_KEYS}
    bg["checkpoints"] = None if bg["checkpoints_timed"] is None or bg["checkpoints_req"] is None else bg["checkpoints_timed"] + bg["checkpoints_req"]
    disk = {}
    for dev, row in (a.get("disk") or {}).items():
        before = (b.get("disk") or {}).get(dev)
        if not before:
            continue
        d = {k: _d(row, before, k) for k in DISK_KEYS}
        d.update(write_wait_ms=_ratio(d["write_ms"], d["writes"]), flush_avg_ms=_ratio(d["flush_ms"], d["flushes"]),
                 util_pct=_ratio(d["io_ms"], elapsed_ms) and d["io_ms"] / elapsed_ms * 100, aqu=_ratio(d["weighted_ms"], elapsed_ms),
                 write_mb=d["write_sectors"] * 512 / 1e6 if d["write_sectors"] is not None else None)
        disk[dev] = d
    main = next((x["name"] for x in a.get("devices") or [] if x.get("type") == "disk" and x.get("name") in disk), None)
    # ADR-009(L1-16·L2-11): MultiXact SLRU 차분 · 새 MultiXact ID 수(datminmxid가 같을 때만) · 쿼터 테이블 누적 통계 차분
    slru = {}
    for name, row in (pa.get("slru") or {}).items():
        before = (pb.get("slru") or {}).get(name) or {}
        slru[name] = {k: _d(row, before, k) for k in SLRU_KEYS}
    ma, mb = pa.get("mxid") or {}, pb.get("mxid") or {}
    mxid = {"consumed": (ma["age"] - mb["age"]) if ma.get("age") is not None and mb.get("age") is not None and ma.get("datminmxid") == mb.get("datminmxid") else None,
            "datminmxid_changed": ma.get("datminmxid") != mb.get("datminmxid") if ma and mb else None}
    qa, qb = pa.get("quota_table") or {}, pb.get("quota_table") or {}
    quota = {k: _d(qa, qb, k) for k in ("n_tup_ins", "n_tup_upd", "n_tup_del", "n_tup_hot_upd", "autovacuum_count")}
    quota.update(n_dead_tup_end=qa.get("n_dead_tup"), bytes_end=qa.get("bytes"))
    return {"elapsed_s": elapsed_ms / 1000, "settings": pa.get("settings"), "devices": a.get("devices"),
            "reset_changed": pa["wal"].get("stats_reset") != pb["wal"].get("stats_reset"),
            "wal": wal, "io": io, "bgwriter": bg, "disk": disk, "disk_main": main, "slru": slru, "mxid": mxid, "quota_table": quota}


def io_flat(io):
    """표용 평탄 값 — io_delta 결과에서 몇 개만."""
    if not io:
        return {}
    dm = (io.get("disk") or {}).get(io.get("disk_main")) or {}
    w = io.get("wal") or {}
    return {"wal_sync": w.get("wal_sync"), "wal_sync_avg_ms": w.get("wal_sync_avg_ms"), "wal_mb": w.get("wal_bytes") and w["wal_bytes"] / 1e6,
            "wal_bytes_per_hold": w.get("wal_bytes_per_hold"), "commits_per_wal_sync": w.get("commits_per_wal_sync"),
            "checkpoints": (io.get("bgwriter") or {}).get("checkpoints"), "io_fsync_avg_ms": (io.get("io") or {}).get("fsync_avg_ms"),
            "disk_write_wait_ms": dm.get("write_wait_ms"), "disk_flush_avg_ms": dm.get("flush_avg_ms"),
            "disk_util_pct": dm.get("util_pct"), "disk_write_mb": dm.get("write_mb"),
            # ADR-009: WAL 레코드 수·FPI · MultiXact(새 ID 수 · Offset/Member SLRU 페이지 zeroed+written)
            "wal_records": w.get("wal_records"), "wal_fpi": w.get("wal_fpi"), "mxid_consumed": (io.get("mxid") or {}).get("consumed"),
            "mx_slru_pages": sum(((io.get("slru") or {}).get(n) or {}).get(k) or 0 for n in ("MultiXactOffset", "MultiXactMember") for k in ("blks_zeroed", "blks_written"))
                             if io.get("slru") else None}


# ---- ADR-003 추가 지표 -----------------------------------------------------------------------------
def s6_stage_table(summ):
    """S6 단계·스트림별 서브메트릭(시나리오가 stage·stream 태그로 남김) → 단계마다 핫·이웃 성공/s·409/s·에러율·p99."""
    step = summ.get("step_seconds", 30)
    m = summ.get("metrics", {})
    val = lambda name, stat, default=0: m.get(name, {}).get("values", {}).get(stat, default)
    rows = []
    for i, target in enumerate(summ.get("stage_rates", [])):
        t = lambda stream: f"{{stage:{i},stream:{stream}}}"
        rows.append({"stage": i, "target": target,
                     "hot_ok": val("hold_201" + t("hot"), "count") / step, "hot_409": val("hold_409" + t("hot"), "count") / step,
                     "hot_err": val("hold_error" + t("hot"), "rate", None), "hot_p99": val("hold_duration" + t("hot"), "p(99)", None),
                     "nb_ok": val("hold_201" + t("neighbor"), "count") / step, "nb_err": val("hold_error" + t("neighbor"), "rate", None),
                     "nb_p99": val("hold_duration" + t("neighbor"), "p(99)", None)})
    return rows


def s1_fairness(csv_gz):
    """S1 이긴 요청(201)의 도착 순위 — 요청을 보낸 시각 t0(ms, 시나리오 태그) 오름차순으로 몇 번째였나.
    반환: {"winner_ranks": [...], "n": 전체 요청 수}. 같은 ms는 동순위(최소 순위). t0 태그가 없으면 None."""
    if not csv_gz.exists():
        return None
    reqs = []
    with gzip.open(csv_gz, "rt", newline="") as f:
        header = next(csv.reader([f.readline()]))
        i_name, i_status, i_tags = header.index("name"), header.index("status"), header.index("extra_tags")
        for line in f:
            if not line.startswith("http_req_duration,"):
                continue
            row = next(csv.reader([line]))
            if row[i_name] != "hold":
                continue
            tags = dict(kv.split("=", 1) for kv in row[i_tags].split("&") if "=" in kv)
            if "t0" not in tags:
                return None
            reqs.append((int(tags["t0"]), row[i_status]))
    order = sorted(t for t, _ in reqs)
    rank = {}
    for i, t in enumerate(order, 1):
        rank.setdefault(t, i)
    return {"winner_ranks": sorted(rank[t] for t, st in reqs if st == "201"), "n": len(reqs)}


MIN_SAMPLES = 3


def timeline_max(path, key, window=None):
    """jsonl 타임라인의 [key] 최대값과 표본 수. window=(시작, 끝) epoch면 그 구간만."""
    vals = []
    if not path.exists():
        return None, 0
    for line in path.read_text().splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if key not in row or row[key] is None:
            continue
        if window and "t" in row and not (window[0] <= ts(row["t"]) <= window[1]):
            continue
        vals.append(row[key])
    return (max(vals) if vals else None), len(vals)


# ---- 회차 ------------------------------------------------------------------------------------------
def rep_record(rep_dir, root, level, cell):
    meta = load(rep_dir / "meta.json") or {}
    summ = load(rep_dir / "k6-summary.json") or {}
    cons = load(rep_dir / "consistency.json") or {}
    status = (rep_dir / "status").read_text().strip() if (rep_dir / "status").exists() else "missing-status"
    base = cell.split("-a")[0].split("-m")[0]   # S3-a20 → S3, S7-m1 → S7
    rec = {
        "rep": rep_dir.name, "status": status, "meta": meta, "consistency": cons,
        "hold_201": metric(summ, "hold_201"), "hold_409": metric(summ, "hold_409"), "hold_other": metric(summ, "hold_other"),
        "hold_error_rate": metric(summ, "hold_error", "rate", None), "confirm_error_rate": metric(summ, "confirm_error", "rate", None),
        "hold_p50": metric(summ, "hold_duration", "med", None), "hold_p99": metric(summ, "hold_duration", "p(99)", None),
        "confirm_200": metric(summ, "confirm_200"), "confirm_409": metric(summ, "confirm_409"),
        "session_confirmed": metric(summ, "session_confirmed"), "session_abandoned": metric(summ, "session_abandoned"),
        "session_gave_up": metric(summ, "session_gave_up"), "session_error": metric(summ, "session_error"),
        "iterations": metric(summ, "iterations"), "dropped": metric(summ, "dropped_iterations"),
    }
    # k6 성공 응답 수 대비 DB 소유 수 (명세 §2 판정 항목) — 0이 아니면 응답과 DB 사실이 어긋난 것
    bg = int(meta.get("bg") or 0)   # 배경 행(홀드 N·CONFIRMED N)은 측정과 무관 — 대조에서 뺀다
    if base in ("S1", "S2"):
        rec["ownership_gap"] = rec["hold_201"] - (cons["hold_rows"] - bg) if "hold_rows" in cons else None
    if base == "S7":   # 측정 요청 201 + setup의 U 사전 선점 201 = 홀드 행
        rec["setup_hold_201"] = metric(summ, "setup_hold_201")
        rec["ownership_gap"] = rec["hold_201"] + rec["setup_hold_201"] - (cons["hold_rows"] - bg) if "hold_rows" in cons else None
    elif base.startswith("S3"):
        rec["ownership_gap"] = rec["confirm_200"] - (cons["confirmed"] - bg) if "confirmed" in cons else None
    if base == "S2" and "max_per_user_limit" in cons:
        rec["s2_limit"] = int(env_value(meta, "USERS", 0)) * cons["max_per_user_limit"]
    if base == "S4" and summ:
        rec["stages"] = stage_table(summ, aborted=meta.get("k6_exit") == 99)
        rec["limits"] = s4_limits(rec["stages"])
    if base.startswith("S4c") and summ:   # 고정 도착률 — 구간표만(한계 규칙 없음, 머리말)
        rec["stages"] = stage_table(summ, aborted=False)
        raw = fixed_csv(rep_dir / "k6-requests.csv.gz", summ, rec["stages"])
        rec["fixed"] = fixed_summary(rec["stages"], summ.get("step_seconds", 30), raw)
        if raw is None:
            rec["codes_error"] = "고정 셀 원시 집계 실패(k6-requests.csv.gz 없음·깨짐) — 코드 0·dropped·p99(코드 0 제외) 미측정"
    if base.startswith("S3"):
        rec["curve"] = s3_curve(rep_dir, meta, root, level, cell) or {}
    if base.startswith("S6") and summ:
        rec["s6_stages"] = s6_stage_table(summ)
        events = hold_events(rep_dir / "k6-requests.csv.gz", meta.get("ttl_s") or 3600) or []
        rec["duplicate"] = sum(1 for _, kind in events if kind == "duplicate")
    if base in ("S2", "S7"):
        limit = cons.get("max_per_user_limit", 2)
        stats = {}
        try:
            codes = hold_codes(rep_dir / "k6-requests.csv.gz", stats)
        except (EOFError, OSError, ValueError, StopIteration) as e:
            codes, rec["codes_error"] = None, f"{type(e).__name__}: {e}"
        end = load(rep_dir / "end-state.json")
        # 리뷰 A5: 행 수 = 요청 수 대조(rep_status hold-codes-mismatch와 같은 기준 — 여기서는 표의 비정상 열에도 드러낸다)
        want = metric(summ, "hold_duration", "count", None)
        rec["hold_code_rows"], rec["hold_code_malformed"] = len(codes or []), stats.get("malformed")
        if codes is not None and (rec["hold_code_rows"] != want or stats.get("malformed")):
            rec["codes_error"] = f"hold_code 행 {rec['hold_code_rows']}(깨진 행 {stats.get('malformed')}) ≠ 요청 {want}"
        if codes is None:
            rec.setdefault("codes_error", "hold_code 행 없음")
        elif base == "S2":
            rec["s2"] = s2_users(codes, end, limit)
        else:
            rec["s7"] = s7_seats(codes, end, limit)
            rec["s7"].update(s7_order(rep_dir / "k6-requests.csv.gz") or {})
    if base == "S1":
        fair = s1_fairness(rep_dir / "k6-requests.csv.gz")
        if fair:
            rec["winner_ranks"] = fair["winner_ranks"]
            rec["first_winner_rank"] = fair["winner_ranks"][0] if fair["winner_ranks"] else None
    # 락 대기·커넥션 풀 대기 — k6 실행 구간의 최대값(표본 수 함께: 표본 0이면 '미측정'이지 0이 아니다)
    window = (ts(meta["k6_started"]), ts(meta["k6_ended"])) if meta.get("k6_started") and meta.get("k6_ended") else None
    for key in ("lock_waiting", "lock_wait_sessions", "advisory_held"):
        rec[f"{key}_max"], rec[f"{key}_samples"] = timeline_max(rep_dir / "timeline-locks.jsonl", key, window)
    rec["hikari_pending_max"], rec["hikari_samples"] = timeline_max(rep_dir / "timeline-hikari.jsonl", "pending", window)
    # ADR-007: DB 설정 적용값(SHOW)·k6 구간 대기 이벤트 분포
    rec["pg_preset"], rec["pg_applied"] = meta.get("pg_preset"), meta.get("pg_applied")
    # 서버 − PC 시계 차(리뷰 A3 — run.sh가 k6 앞·뒤에 잰 값의 평균, 없으면 0)
    clk = [x["offset_s"] for x in ((meta.get("clock_offset") or {}).get("before"), (meta.get("clock_offset") or {}).get("after"))
           if isinstance(x, dict) and isinstance(x.get("offset_s"), (int, float))]
    rec["clock_offset_s"] = sum(clk) / len(clk) if clk else None
    rec["waits_top"], rec["waits_samples"] = waits_top(rep_dir / "timeline-waits.jsonl", window, off=rec["clock_offset_s"] or 0.0)
    _all, _n = waits_top(rep_dir / "timeline-waits.jsonl", window, n=1000, off=rec["clock_offset_s"] or 0.0)
    rec["waits_top_all"] = dict(_all) if _all is not None else None
    # 순간 표본이 k6 구간 안에 MIN_SAMPLES개 미만이면 그 최대값은 '미측정' — 0으로 읽히지 않게(S1 버스트는 2초 안팎, 리뷰 지적)
    for key, n in (("lock_waiting_max", "lock_waiting_samples"), ("hikari_pending_max", "hikari_samples")):
        if rec[n] < MIN_SAMPLES:
            rec[key] = None
    # 부하 직후 누적 지표(run.sh after_k6) — 앱 2대면 앱별 값을 합산(타임아웃·요청)·최대(획득 대기)
    after = load(rep_dir / "after-k6.json") or {}
    apps = after.get("apps") or []
    # 앱 하나라도 조회에 실패했으면(None) 그 지표는 '미측정' — 0으로 채우지 않는다(재점검 지적)
    acq = [a.get("acquire") for a in apps]
    complete = bool(apps) and all(x is not None for x in acq)
    rec["acquire_max_ms"] = max(x["MAX"] * 1000 for x in acq) if complete else None   # Micrometer timer 단위 = 초
    count = sum(x["COUNT"] for x in acq) if complete else 0
    rec["acquire_mean_ms"] = sum(x["TOTAL_TIME"] for x in acq) / count * 1000 if complete and count else None
    rec["pool_timeouts"] = sum(a["timeouts"] for a in apps) if apps and all(a.get("timeouts") is not None for a in apps) else None
    rec["hold_requests_per_app"] = [a.get("hold_requests") for a in apps]
    # ADR-006 요청당 커넥션 빌림(명세 §9.4 ③) = Hikari 획득 COUNT 증가분 ÷ 선점 요청 증가분 — 앱 하나라도 조회 실패면 미측정.
    # hold_requests는 run.sh hold_requests가 낸다: 지표 목록에서 '기록 없음'이 확인될 때만 0, 그 밖의 조회 실패는 null → 증가분 null → 여기서 None
    # (ADR-006 초판은 실패를 0으로 채워 직전 값이 0이 되면 분모가 누적값이 되어 빌림이 조용히 낮게 나왔다). 범위 검사는 run.sh rep_status(invalid-borrow-ratio)
    holds = [a.get("hold_requests") for a in apps]
    rec["borrow_per_hold"] = borrow_per_hold(apps)
    rec["borrow_per_request"], rec["borrow_confirm_source"] = borrow_per_request(apps, summ)   # ADR-008 D1 — 합격 판정의 빌림(새 필드)
    rec["io"] = io_delta(rep_dir, sum(holds) if holds and all(h is not None for h in holds) else None)
    rec.update({f"io_{k}": v for k, v in io_flat(rec["io"]).items()})
    rec["deadlocks"] = after.get("deadlocks_delta")
    # ADR-005 매수 제어 구간 타이머·SERIALIZABLE 카운터(앱 1대 — 앱 2대면 첫 앱만이 아니라 미측정으로 둔다)
    lim = [a.get("limit") for a in apps]
    if len(lim) == 1 and lim[0]:
        for m in ("prepare", "acquire", "check", "span"):   # MAX는 최근 약 2분 창 — 짧은 셀은 직전 예열이 섞일 수 있어 비교에 쓰지 않는다
            rec[f"limit_{m}_n"], rec[f"limit_{m}_mean_ms"], rec[f"limit_{m}_max_ms"] = timer(lim[0].get(m))
        rec["serialization_failures"] = lim[0].get("serialization_failure")
        rec["limit_retries"] = lim[0].get("retry")
    rec["redis_used_mb"] = (after.get("redis") or {}).get("used_memory") and after["redis"]["used_memory"] / 1e6
    rec["redis_keys"] = (after.get("redis") or {}).get("keys")
    # ---- ADR-009: 조건 · 마감 판정 비용 · 대조(C·F) · 틱 · MultiXact · 출처별 대기 · 효과 · 마감 임박 셀 ----
    rec["cond"], rec["cleanup"] = meta.get("cond"), meta.get("cleanup")
    rec["pre_k6"] = load(rep_dir / "pre-k6.json")   # 명세 §13 시작 상태(시드 끝 → k6 시작)
    rec["gc"] = load(rep_dir / "gc.json")
    sb, sa, se = (load(rep_dir / f) or {} for f in ("drift-before.json", "drift-after-k6.json", "drift-end.json"))
    n_sc, t_sc = snap_delta(sa, sb, "app", "sales_close_check", "COUNT"), snap_delta(sa, sb, "app", "sales_close_check", "TOTAL_TIME")
    rec["sales_close_check_n"], rec["sales_close_check_mean_ms"] = n_sc, (t_sc / n_sc * 1000 if n_sc and t_sc is not None else None)
    rec["tick_failed"], rec["tick_ok"] = snap_delta(se, sb, "app", "tick", "failed"), snap_delta(se, sb, "app", "tick", "ok")
    rec["drift_repaired_metric"] = snap_delta(se, sb, "app", "drift", "repaired")
    rec["reconcile_borrows_app"] = snap_delta(sa, sb, "app", "reconcile", "borrows")
    rec["reconcile_borrows_f"] = snap_delta(sa, sb, "reconciler", "reconcile", "borrows")
    rec["cleanup_borrows_app"] = snap_delta(sa, sb, "app", "cleanup", "borrows")
    rec["f_cpu_usage_end"] = ((sa.get("reconciler") or {}).get("process_cpu_usage"))
    off = rec["clock_offset_s"] or 0.0
    db = load(rep_dir / "drift-db.json") or {}
    rec["reconcile_k6"] = reconcile_summary(db.get("reconcile_runs"), window[0] + off, window[1] + off) if window else None
    rec["reconcile_all"] = reconcile_summary(db.get("reconcile_runs"))
    rec["cleanup_runs"] = len(db.get("cleanup_runs") or []) if db else None
    rec["counter_excluded_schedules"], rec["judge_excluded_pairs_quota_or_actual"] = cons.get("counter_excluded_schedules"), cons.get("counter_excluded_pairs")
    rec["waits_by_src"] = waits_by_src(rep_dir / "timeline-waits.jsonl", window, off)
    rec["mx_waits"] = sum(v for k, v in (rec["waits_top_all"] or {}).items() if "MultiXact" in k) if (rec.get("waits_top_all") is not None) else None
    if base == "S3e":
        rec["effect"] = load(rep_dir / "effect.json")
    if base == "S3c":
        rec["close"] = {"close": load(rep_dir / "close.json"), "pre_close": load(rep_dir / "pre-close.json"), "end": load(rep_dir / "close-end.json"),
                        "cleanup_runs": db.get("cleanup_runs")}
    return rec


def main(root):
    root = Path(root)
    plan = load(root / "plan.json")
    if not plan:
        sys.exit(f"{root}/plan.json 없음 — 계획 없는 결과는 요약하지 않는다")
    (root / "curves").mkdir(exist_ok=True)
    for old in (root / "curves").glob("*.csv"):
        old.unlink()

    cells = {}
    for level in plan["levels"]:
        for cell in plan["cells"]:
            reps = []
            for k in range(1, plan["reps"] + 1):
                rep_dir = root / f"L{level}" / cell / f"rep{k}"
                if rep_dir.exists():
                    reps.append(rep_record(rep_dir, root, level, cell))
                else:
                    reps.append({"rep": f"rep{k}", "status": "미측정"})
            cells[(f"L{level}", cell)] = reps

    cond = plan.get("condition", {})
    lines = [f"# ADR-009 결과 요약 — `{root.name}` · 조건 {cond.get('cond')} · 정리 {cond.get('cleanup')} · 매수 방식 {cond.get('limit_strategy')} · DB 설정 {cond.get('pg_preset', 'default')} · 좌석 전략 {cond.get('strategy')} · 풀 {cond.get('pool')} · 앱 {cond.get('apps')}대"
             f" · 하네스 {plan.get('harness', '-')}", "",
             f"> `scripts/summarize.py`가 원시 결과에서 산출 · 측정 SHA `{plan['sha']}` · 계획 {len(plan['levels'])}단계 × {len(plan['cells'])}셀 × {plan['reps']}회.",
             "> 값 = 중앙값 [최소–최대], n = 정상 회차 수. 정상이 아닌 회차(실패·미측정)는 마지막 열에 모두 표시한다.", ""]

    def section(title, header, key_filter, row_fn):
        rows = [(k, v) for k, v in cells.items() if key_filter(k[1])]
        if not rows:
            return
        lines.extend([f"## {title}", "", "| " + " | ".join(header) + " |", "|" + "---|" * len(header)])
        for (level, cell), reps in rows:
            ok = [r for r in reps if r["status"] == "ok"]
            bad = [f'{r["rep"]}:{r["status"]}' for r in reps if r["status"] != "ok"]
            bad += [f'{r["rep"]}:curve-error' for r in ok if (r.get("curve") or {}).get("curve_error")]
            bad += [f'{r["rep"]}:codes-error({r["codes_error"]})' for r in ok if r.get("codes_error")]
            lines.append("| " + " | ".join([level, cell, str(len(ok)), *row_fn(ok), ", ".join(bad) or "-"]) + " |")
        lines.append("")

    v = lambda recs, key: spread([r["consistency"].get(key) for r in recs])
    g = lambda recs, key: spread([r.get(key) for r in recs])
    c = lambda recs, key: spread([(r.get("curve") or {}).get(key) for r in recs])
    section("S1 같은 좌석 1,000명 (Q1) — 정합이면 201 = 1",
            ["단계", "셀", "n", "201", "409", "에러율", "p50 ms", "p99 ms", "좌석당 초과 홀드 행", "201 − 홀드 행",
             "첫 승자 도착 순위(1,000 중)", "락 대기 최대(표본)", "풀 대기 최대(표본)", "커넥션 획득 대기 최대 ms", "획득 대기 평균 ms",
             "풀 타임아웃", "데드락", "앱별 선점 요청", "비정상 회차"],
            lambda x: x == "S1",
            lambda ok: [g(ok, "hold_201"), g(ok, "hold_409"), g(ok, "hold_error_rate"), g(ok, "hold_p50"),
                        g(ok, "hold_p99"), v(ok, "v_excess_hold_rows"), g(ok, "ownership_gap"),
                        g(ok, "first_winner_rank"), g(ok, "lock_waiting_max"), g(ok, "hikari_pending_max"),
                        g(ok, "acquire_max_ms"), g(ok, "acquire_mean_ms"), g(ok, "pool_timeouts"), g(ok, "deadlocks"),
                        "; ".join(str(r.get("hold_requests_per_app")) for r in ok) or "-"])
    s2 = lambda recs, key: spread([(r.get("s2") or {}).get(key) for r in recs])
    s2c = lambda recs, key: spread([((r.get("s2") or {}).get("codes") or {}).get(key) for r in recs])
    s7 = lambda recs, key: spread([(r.get("s7") or {}).get(key) for r in recs])
    lim_cols = ["prepare 평균 ms", "매수 acquire 건수", "acquire 평균 ms", "check 평균 ms", "span 평균 ms(acquire~check)", "span 최대 ms(최근 2분 창·예열 섞임 가능)", "40001 수", "재시도 수",
                # ADR-006: 요청당 커넥션 빌림 + 서버 I/O(k6 직전·직후 차분 — 머리말·io_delta)
                "요청당 커넥션 빌림", "WAL fsync 수", "WAL fsync 평균 ms", "커밋/WAL fsync", "WAL MB", "선점당 WAL B", "체크포인트 수",
                "디스크 쓰기 대기 평균 ms(물리)", "디스크 flush 평균 ms(물리)", "디스크 util %(물리)"]
    gd = lambda recs, key, d: spread([r.get(key) for r in recs], d)
    lim_vals = lambda ok: [gd(ok, "limit_prepare_mean_ms", 4), g(ok, "limit_acquire_n"), gd(ok, "limit_acquire_mean_ms", 4),
                           gd(ok, "limit_check_mean_ms", 4), gd(ok, "limit_span_mean_ms", 4), gd(ok, "limit_span_max_ms", 3),
                           g(ok, "serialization_failures"), g(ok, "limit_retries"),
                           gd(ok, "borrow_per_hold", 3), g(ok, "io_wal_sync"), gd(ok, "io_wal_sync_avg_ms", 3), gd(ok, "io_commits_per_wal_sync", 2),
                           gd(ok, "io_wal_mb", 1), gd(ok, "io_wal_bytes_per_hold", 0), g(ok, "io_checkpoints"),
                           gd(ok, "io_disk_write_wait_ms", 3), gd(ok, "io_disk_flush_avg_ms", 3), gd(ok, "io_disk_util_pct", 1)]
    section("S2 같은 사용자 동시 요청 (1인 2매) — 정합이면 매수 초과 사용자 0 · 가짜 거절은 즉시 실패형의 대가",
            ["단계", "셀", "n", "201 수", "상한(사용자×2)", "매수 초과 사용자(판정기)", "매수 초과 사용자(응답 201>2)", "가짜 거절 사용자(끝 상태<2 & HLE)",
             "가짜 거절 사용자(응답 201<2 & HLE)", "응답≠끝 상태 사용자", "사용자당 최대(판정기)",
             "409 SEAT_NOT_AVAILABLE 수", "409 HOLD_LIMIT_EXCEEDED 수", "409 기타 수", "에러율", "p50 ms", "p99 ms", "201 − 홀드 행", *lim_cols, "비정상 회차"],
            lambda x: x == "S2",
            lambda ok: [g(ok, "hold_201"), g(ok, "s2_limit"), v(ok, "v_over_limit_users"), s2(ok, "over_users_resp"),
                        s2(ok, "fake_reject_users_db"), s2(ok, "fake_reject_users_resp"), s2(ok, "resp_db_mismatch_users"), v(ok, "max_per_user"),
                        s2c(ok, "SEAT_NOT_AVAILABLE"), s2c(ok, "HOLD_LIMIT_EXCEEDED"), s2c(ok, "409_other"),
                        g(ok, "hold_error_rate"), g(ok, "hold_p50"), g(ok, "hold_p99"), g(ok, "ownership_gap"), *lim_vals(ok)])
    section("S7 이긴 쪽 롤백 — 좌석마다 2매 보유자 U 1명 + 일반 M명. 억울한 좌석 = 끝 상태 AVAILABLE인데 일반 사용자가 409 SEAT_NOT_AVAILABLE을 받은 좌석",
            ["단계", "셀", "n", "대상 좌석 수", "억울한 좌석 수", "억울한 409 수", "빈 좌석 수(끝 상태)", "일반 201 수", "201 2건+ 좌석 수",
             "U 201 수(매수 위반)", "U 끝 상태 매수>2", "U 코드 분포", "일반 409 SEAT_NOT_AVAILABLE 수", "일반 409 HOLD_LIMIT_EXCEEDED 수",
             "일반−U 보낸 시각 ms p50 [min–max]", "201 − 홀드 행", "p99 ms", *lim_cols, "비정상 회차"],
            lambda x: x == "S7" or x.startswith("S7-m"),
            lambda ok: [s7(ok, "seats_planned"), s7(ok, "wronged_seats"), s7(ok, "wronged_409"), s7(ok, "empty_seats"), s7(ok, "r_201"),
                        s7(ok, "multi_201_seats"), s7(ok, "u_201"), s7(ok, "u_over_limit_db"),
                        "; ".join(json.dumps((r.get("s7") or {}).get("u_codes"), ensure_ascii=False) for r in ok) or "-",
                        spread([((r.get("s7") or {}).get("r_codes") or {}).get("SEAT_NOT_AVAILABLE") for r in ok]),
                        spread([((r.get("s7") or {}).get("r_codes") or {}).get("HOLD_LIMIT_EXCEEDED") for r in ok]),
                        "; ".join("{p50} [{min}–{max}]".format(**(r.get("s7") or {}).get("r_minus_u_ms", {"p50": "-", "min": "-", "max": "-"})) for r in ok) or "-",
                        g(ok, "ownership_gap"), g(ok, "hold_p99"), *lim_vals(ok)])
    section("S3 선점→확정 전체 흐름 (Q3·Q7) — 정합이면 위반 열 전부 0",
            ["단계", "셀", "n", "입장", "k6 미시작(dropped)", "확정", "이탈", "포기", "에러 중단", "선점 에러율", "확정 에러율",
             "RESERVED", "CONFIRMED", "확정200 − CONFIRMED", "중복 홀드 좌석", "중복 확정", "오래된 HELD", "매수 초과",
             "카운터 불일치 사용자(counter만)", "재선점", "중복 선점", "t50/TTL", "t90/TTL", "선점 p99 ms", "판정 폴링 최대 ms", *lim_cols, "비정상 회차"],
            lambda x: x.startswith("S3"),
            lambda ok: [g(ok, "iterations"), g(ok, "dropped"), g(ok, "session_confirmed"), g(ok, "session_abandoned"),
                        g(ok, "session_gave_up"), g(ok, "session_error"), g(ok, "hold_error_rate"), g(ok, "confirm_error_rate"),
                        v(ok, "reserved"), v(ok, "confirmed"), g(ok, "ownership_gap"), v(ok, "v_duplicate_hold_seats"),
                        v(ok, "v_duplicate_confirmed_seats"), v(ok, "v_stale_held_seats"), v(ok, "v_over_limit_users"),
                        (v(ok, "v_counter_mismatch") if any("v_counter_mismatch" in r["consistency"] for r in ok) else "해당 없음"),
                        c(ok, "rehold"), c(ok, "duplicate"), c(ok, "t50"), c(ok, "t90"), g(ok, "hold_p99"), c(ok, "poll_ms_max"), *lim_vals(ok)])
    stop = lambda recs, key: "; ".join(
        (lambda st: "-" if not st else f'{st["stage"]}({st["target_rps"]:,}/s):{"+".join(st["reasons"])}')((r.get("limits") or {}).get(key)) for r in recs) or "-"
    sat_stage = lambda recs: "; ".join(
        (lambda st: "-" if not st else f'{st["stage"]}({st["target_rps"]:,}/s→성공 {st["ok_rps"]:,.0f})')((r.get("limits") or {}).get("saturation_stage")) for r in recs) or "-"
    # 부분 단계(중단된 마지막 단계)·미실행 단계(중단 뒤) — 회차별 '부분 p/미실행 a–b', 없으면 '-'
    def cut(recs):
        def one(r):
            st = r.get("stages") or []
            part = [s["stage"] for s in st if s["partial"]]
            nr = [s["stage"] for s in st if s.get("not_run")]
            txt = ([f"부분 {part[0]}"] if part else []) + ([f"미실행 {nr[0]}–{nr[-1]}" if len(nr) > 1 else f"미실행 {nr[0]}"] if nr else [])
            return " · ".join(txt) or "-"
        return "; ".join(one(r) for r in recs) or "-"
    section("S4 처리량 한계 — 성공 RPS (새 규칙: 기준 위반 또는 목표 미달이 처음 나온 단계의 직전 단계 — 머리말)",
            ["단계", "셀", "n", "엄격 p99<500ms·에러<1%", "엄격 멈춘 단계(회차별)", "완화 p99<1s·에러<5%", "엄격(ADR-005 규칙)", "포화점(최대 성공 RPS)",
             "포화 단계(첫 목표 미달, 회차별)", "목표 미달 단계 수", "부분·미실행 단계(회차별 — 한계 산출에서 뺌)", "k6 미시작(dropped)",
             "락 대기 최대(표본)", "풀 대기 최대(표본)", "커넥션 획득 대기 평균 ms", "풀 타임아웃", "데드락", "Redis MB·키", *lim_cols, "비정상 회차"],
            lambda x: x == "S4",
            lambda ok: [spread([r["limits"]["strict"] for r in ok if "limits" in r]), stop(ok, "strict_stop"),
                        spread([r["limits"]["loose"] for r in ok if "limits" in r]), spread([r["limits"]["strict_legacy"] for r in ok if "limits" in r]),
                        spread([r["limits"]["saturation"] for r in ok if "limits" in r]), sat_stage(ok),
                        spread([r["limits"]["under_delivered_stages"] for r in ok if "limits" in r]), cut(ok)]
                       + [g(ok, "dropped"), g(ok, "lock_waiting_max"), g(ok, "hikari_pending_max"), g(ok, "acquire_mean_ms"),
                          g(ok, "pool_timeouts"), g(ok, "deadlocks"), f'{g(ok, "redis_used_mb")}·{g(ok, "redis_keys")}', *lim_vals(ok)])

    # ---- ADR-007 고정 도착률(S4c*): 30초 구간표 — 회차 중앙값 [최소–최대] ----
    fixed = [(k, v) for k, v in cells.items() if k[1].startswith("S4c")]
    for (level, cell), reps in fixed:
        ok = [r for r in reps if r["status"] == "ok"]
        bad = ", ".join(f'{r["rep"]}:{r["status"]}' for r in reps if r["status"] != "ok") or "-"
        fx = lambda key: spread([(r.get("fixed") or {}).get(key) for r in ok])
        bad += "".join(f', {r["rep"]}:codes-error({r["codes_error"]})' for r in ok if r.get("codes_error"))
        rmp = lambda key: spread([((r.get("fixed") or {}).get("ramp") or {}).get(key) for r in ok])
        lines.extend([f"## {cell} 고정 도착률 — 30초 구간별 (한계 규칙 없음 · 목표 미달 = 실제 도착 < 0.9 × 목표 · 램프 제외)", "",
                      "> status `ok` = 회차가 유효하다(계획 구간을 다 돌았다)는 뜻이지 목표를 냈다는 뜻이 아니다 — 무너짐 자체가 측정값이다(리뷰 A6). 목표 달성은 도착/목표·dropped·코드 0 열로 본다.",
                      "> p99(코드 0 제외) = 코드 0(요청 타임아웃·연결 실패·끊김) 행을 뺀 p99 — k6 요약 p99는 코드 0의 0에 가까운 지연을 섞어 낮게 나온다(리뷰 A12, `fixed_csv`). "
                      "dropped = k6가 VU가 없어 시작하지 못한 반복(구간 = 램프 끝 기준 30초, ±1초).", "",
                      f"> {level} · n = {len(ok)} · 전체 성공 RPS {fx('ok_rps_total')} · 전체 도착 RPS {fx('achieved_rps_total')} · 도착/목표 {spread([(r.get('fixed') or {}).get('achieved_ratio_total') for r in ok], 3)}"
                      f" · 목표 미달 구간 수 {fx('under_delivered_intervals')} · 첫 목표 미달 구간 {fx('first_under_delivered')}"
                      f" · 가장 나쁜 구간 p99 ms(코드 0 제외) {fx('worst_p99_nz_ms')} · (k6 요약 p99 {fx('worst_p99_ms')}) · 구간 코드 0 {fx('status0')} ({spread([(r.get('fixed') or {}).get('status0_share') for r in ok], 4)})"
                      f" · 구간 dropped {fx('dropped_hold')} · k6 dropped 전체(램프 포함) {g(ok, 'dropped')}"
                      f" · 램프 {spread([(r.get('fixed') or {}).get('ramp_s') for r in ok])}초 요청 {rmp('requests')} · 코드 0 {rmp('status0')} · dropped {rmp('dropped')}"
                      f" · 풀 타임아웃 {g(ok, 'pool_timeouts')} · 커넥션 획득 대기 평균 ms {g(ok, 'acquire_mean_ms')} · 체크포인트 수 {g(ok, 'io_checkpoints')} · 비정상 회차 {bad}", "",
                      "| 구간 | 시작 초(램프 끝 기준) | 목표/s | 실제 도착/s | 도착/목표 | dropped | 성공/s | p99 ms(코드 0 제외) | 코드 0 비율 | 코드 0 수 | k6 요약 p99 ms | 에러율 | 목표 미달 회차 |",
                      "|---|---|---|---|---|---|---|---|---|---|---|---|---|"])
        nst = max((len(r.get("stages") or []) for r in ok), default=0)
        for i in range(nst):
            st = [r["stages"][i] for r in ok if len(r.get("stages") or []) > i]
            q = lambda key, d=None: spread([x.get(key) for x in st], d)
            step = 30
            lines.append(f"| {i} | {i * step} | {q('target_rps')} | {q('achieved_rps')} | {q('achieved_ratio', 3)} | {q('dropped')} | {q('ok_rps')} | {q('p99_nz_ms')} | "
                         f"{q('status0_share', 4)} | {q('status0')} | {q('p99_ms')} | {q('error_rate')} | {sum(1 for x in st if x['under_delivered'])}/{len(st)} |")
        lines.append("")

    # ---- ADR-007 DB 설정 적용값·대기 이벤트(k6 구간 평균 활성 세션 수 상위 5) — 회차별 ----
    lines.extend(["## DB 설정 적용값(SHOW)·대기 이벤트 — 회차별", "",
                  "> 대기 이벤트 = timeline-waits.jsonl(0.5초 표본, seat DB의 활성 세션 — 표본 세션 제외) k6 구간 평균 세션 수. `CPU:-` = 대기 없이 실행 중. 표본 = k6 구간 안 표본 수.", "",
                  "| 단계 | 셀 | 회차 | status | preset | checkpoint_timeout | max_wal_size | shared_buffers | 표본 | 대기 이벤트 상위 5(평균 세션) |", "|---|---|---|---|---|---|---|---|---|---|"])
    for (level, cell), reps in cells.items():
        for r in reps:
            if r["status"] == "미측정":
                continue
            pa = r.get("pg_applied") or {}
            # 표본 0개(표본이 k6 구간에 하나도 없음) ≠ 표본은 있는데 활성 세션이 없었음(리뷰 A10)
            wt = r.get("waits_top")
            top = "표본 없음" if wt is None else ("; ".join(f"{k} {v:.2f}" for k, v in wt) or "활성 세션 없음(표본 있음)")
            lines.append(f"| {level} | {cell} | {r['rep']} | {r['status']} | {r.get('pg_preset') or '-'} | {pa.get('checkpoint_timeout', '-')} | {pa.get('max_wal_size', '-')} | "
                         f"{pa.get('shared_buffers', '-')} | {r.get('waits_samples', 0)} | {top} |")
    lines.append("")

    # ---- ADR-009 회차별: 비용(빌림·마감 판정·WAL·MultiXact·대조) · 효과(S3e) · 마감 임박(S3c) ----
    f = lambda x, d=2: "-" if x is None else (f"{x:,.{d}f}" if isinstance(x, float) else f"{x:,}")
    lines.extend(["## ADR-009 회차별 비용 — 조건 적용·마감 판정·WAL·MultiXact·대조(C·F)", "",
                  "> 빌림 = (Δ획득 − Δ대조 빌림 − Δ정리 빌림) ÷ (Δ선점 + Δ확정)(설계 §4d). 마감 판정 = 앱 타이머 seat.hold.sales-close.check(k6 구간). "
                  "MultiXact ID = mxid_age 차분(datminmxid 불변일 때) · MX SLRU = MultiXactOffset/Member zeroed+written. 대조 = counter_reconcile_run(k6 구간과 겹친 바퀴 — 출처별). "
                  "출처별 대기 = by_src 평균 세션(F = application_name, C = 문장 표지 — lib.sh db_sampler).", "",
                  "| 셀 | 회차 | status | 조건 | 정리 | 요청당 빌림 | 선점 p99 ms | 마감 판정 n · 평균 ms | WAL MB | WAL 레코드 | FPI | MultiXact ID | MX SLRU 페이지 | MX 대기 세션 | 대조(출처: 바퀴 · 바퀴 s p50/max · 청크 최대 ms · 빌림 · 오류) | 앱 대조 빌림 | F 빌림 | 틱 실패 | 판정기 제외 회차 · 제외 쌍(쿼터∪실제) | 출처별 대기(평균 세션) |",
                  "|" + "---|" * 20])
    for (level, cell), reps in cells.items():
        for r in reps:
            if r["status"] == "미측정":
                continue
            rk = r.get("reconcile_k6") or {}
            rtxt = "; ".join(f'{src}: {d["runs"]} · {f((d["pass_s"] or {}).get("p50"))}/{f((d["pass_s"] or {}).get("max"))} · {d["chunk_ms_max"]} · {d["borrows"]} · {d["errors"]}' for src, d in rk.items()) or "-"
            ws = r.get("waits_by_src") or {}
            wtxt = "; ".join(f'{src} {d["sessions"]:.2f}' for src, d in sorted(ws.items())) or "-"
            lines.append(f"| {cell} | {r['rep']} | {r['status']} | {r.get('cond') or '-'} | {r.get('cleanup') or '-'} | {f(r.get('borrow_per_request'), 4)} | {f(r.get('hold_p99'))} | "
                         f"{f(r.get('sales_close_check_n'))} · {f(r.get('sales_close_check_mean_ms'), 4)} | {f(r.get('io_wal_mb'), 1)} | {f(r.get('io_wal_records'))} | {f(r.get('io_wal_fpi'))} | "
                         f"{f(r.get('io_mxid_consumed'))} | {f(r.get('io_mx_slru_pages'))} | {f(r.get('mx_waits'), 3)} | {rtxt} | {f(r.get('reconcile_borrows_app'))} | {f(r.get('reconcile_borrows_f'))} | "
                         f"{f(r.get('tick_failed'))} | {f(r.get('counter_excluded_schedules'))} · {f(r.get('judge_excluded_pairs_quota_or_actual'))} | {wtxt} |")
    lines.append("")
    eff = [(k, r) for k, reps in cells.items() for r in reps if r.get("effect")]
    if eff:
        lines.extend(["## ADR-009 효과 셀(S3e) — 주입 쌍 탐지·복구(명세 §5 결정 기준 ①②)", "",
                      "> 종류: D 쿼터 행 삭제 · L1 cnt−1(만료 음수 경로) · L2 cnt−1(확정 쌍, 조용함) · H cnt+1. 값 = 주입/탐지/복구/끝 일치 · 복구까지 초 p50/max. "
                      "skipped는 실패와 마감 제외(reason=closed)를 나눴다(L3-4). 오탐 복구 = 주입 밖 쌍 repaired(기대 0).", "",
                      "| 회차 | status | 조건 | D | L1 | L2 | H | 복구 출처 | 오탐 복구 | false-positive | skipped 실패 · 마감 제외 | 틱 실패 | 끝 오래된 HELD | 주입 밖 끝 어긋남 | 어긋남(기대와 다름) |",
                      "|" + "---|" * 15])
        for (level, cell), r in eff:
            e = r["effect"]
            kd = lambda k: (lambda x: f'{x["injected"]}/{x["detected"]}/{x["repaired"]}/{x["end_matched"]} · {f((x["repair_s"] or {}).get("p50"), 1)}/{f((x["repair_s"] or {}).get("max"), 1)}s')(e["kinds"][k])
            srcs = sorted({s for k in INJECT_KINDS for s in e["kinds"][k]["repaired_by"]})
            lines.append(f"| {cell} {r['rep']} | {r['status']} | {e.get('cond')} | {kd('D')} | {kd('L1')} | {kd('L2')} | {kd('H')} | {', '.join(srcs) or '-'} | {e.get('false_repairs')} | "
                         f"{e.get('false_positives')} | {e.get('skipped_failure')} · {e.get('skipped_closed')} | {f(e.get('tick_failed'))} | {f(e.get('stale_held_seats_end'))} | "
                         f"{f(e.get('noninjected_mismatch'))} | {'; '.join(e.get('unexpected') or []) or '-'} |")
        lines.append("")
    cls = [(k, r) for k, reps in cells.items() for r in reps if r.get("close")]
    if cls:
        lines.extend(["## ADR-009 마감 임박 셀(S3c) — 정리 2방식(설계 §6 비교 지표)", "",
                      "| 회차 | status | 정리 | starts_at − k6 시작 s | 마감 5초 전 어긋남/쌍 | 정리 기록 수 · 지운 행 · 대기 ms 최대 · 문장 ms 최대 | 끝 쿼터 행(마감 회차) · cnt0·실제0 행 | 쿼터 테이블 B · dead | Δ삭제 튜플 · autovacuum | WAL MB |",
                      "|" + "---|" * 10])
        for (level, cell), r in cls:
            c = r["close"]
            cj, pre, ce, runs = c.get("close") or {}, c.get("pre_close") or {}, c.get("end") or {}, c.get("cleanup_runs") or []
            k6s = ts(r["meta"]["k6_started"]) + (r.get("clock_offset_s") or 0) if r["meta"].get("k6_started") else None
            lead = cj["starts_at_epoch"] - k6s if cj.get("starts_at_epoch") and k6s else None
            q = (r.get("io") or {}).get("quota_table") or {}
            lines.append(f"| {cell} {r['rep']} | {r['status']} | {r.get('cleanup')} | {f(lead, 2)} | {pre.get('mismatches', '-')}/{pre.get('pairs', '-')} | "
                         f"{len(runs)} · {sum(x.get('rows_deleted') or 0 for x in runs)} · {max((x.get('waited_ms') or 0 for x in runs), default='-')} · {max((x.get('stmt_ms') or 0 for x in runs), default='-')} | "
                         f"{ce.get('closed_quota_rows', '-')} · {ce.get('zero_rows_actual0', '-')} | {ce.get('quota_bytes', '-')} · {(ce.get('quota_stats') or {}).get('n_dead_tup', '-')} | "
                         f"{f(q.get('n_tup_del'))} · {f(q.get('autovacuum_count'))} | {f(r.get('io_wal_mb'), 1)} |")
        lines.append("")

    # ---- S6 경합 강도 스윕(ADR-003·004): 단계별 핫·이웃 스트림 ----
    s6 = [(k, v) for k, v in cells.items() if k[1].startswith("S6")]
    if s6:
        lines.extend(["## S6 경합 강도 스윕 — 단계별 핫(같은 좌석 K개 × 경쟁자 M) · 이웃(경합 없는 좌석)", "",
                      "> 값 = 정상 회차의 중앙값. 성공·409는 초당, p99는 ms. 이웃 에러율이 핫 경합의 '번짐'이다.", "",
                      "| 단계 | 셀 | n | 핫 목표/s | 핫 성공/s | 핫 409/s | 핫 에러율 | 핫 p99 | 이웃 성공/s | 이웃 에러율 | 이웃 p99 | 중복 홀드(판정기) | 일시 중복(요청 기록) | 비정상 회차 |",
                      "|" + "---|" * 14])
        for (level, cell), reps in s6:
            ok = [r for r in reps if r["status"] == "ok"]
            bad = ", ".join(f'{r["rep"]}:{r["status"]}' for r in reps if r["status"] != "ok") or "-"
            nstage = max((len(r.get("s6_stages") or []) for r in ok), default=0)
            for i in range(nstage):
                st = [r["s6_stages"][i] for r in ok if len(r.get("s6_stages") or []) > i]
                q = lambda key: spread([x[key] for x in st])
                lines.append("| " + " | ".join([level, cell if i == 0 else "", str(len(ok)) if i == 0 else "",
                    q("target"), q("hot_ok"), q("hot_409"), q("hot_err"), q("hot_p99"), q("nb_ok"), q("nb_err"), q("nb_p99"),
                    v(ok, "v_duplicate_hold_seats") if i == 0 else "", g(ok, "duplicate") if i == 0 else "", bad if i == 0 else ""]) + " |")
        lines.append("")

    (root / "SUMMARY.md").write_text("\n".join(lines))
    (root / "summary.json").write_text(json.dumps({f"{k[0]}/{k[1]}": v for k, v in cells.items()},
                                                  ensure_ascii=False, indent=1, default=str))
    print(root / "SUMMARY.md")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--s4-check":
        sys.exit(s4_check(sys.argv[2]))
    if len(sys.argv) == 3 and sys.argv[1] == "--fixed-check":
        sys.exit(fixed_check(sys.argv[2]))
    if len(sys.argv) == 3 and sys.argv[1] == "--codes-check":
        sys.exit(codes_check(sys.argv[2]))
    if len(sys.argv) == 3 and sys.argv[1] == "--s7late-check":
        sys.exit(s7late_check(sys.argv[2]))
    if len(sys.argv) in (4, 6) and sys.argv[1] == "--accept-check":
        sys.exit(accept_check(*sys.argv[2:]))
    if len(sys.argv) == 4 and sys.argv[1] == "--effect":
        try:
            effect(sys.argv[2], sys.argv[3])
        except Exception as e:   # 효과 지표를 못 만들면 effect.json이 없다 → rep_status effect-check-failed
            sys.exit(f"effect: {type(e).__name__}: {e}")
        sys.exit(0)
    if len(sys.argv) == 3 and sys.argv[1] == "--pre-k6-finish":
        sys.exit(pre_k6_finish(sys.argv[2]))
    if len(sys.argv) == 3 and sys.argv[1] == "--gc":
        sys.exit(gc_report(sys.argv[2]))
    if len(sys.argv) in (6, 7) and sys.argv[1] == "--runs-in-window":
        sys.exit(runs_in_window(*sys.argv[2:]))
    main(sys.argv[1])
