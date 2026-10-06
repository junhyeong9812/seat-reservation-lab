#!/usr/bin/env python3
"""ADR-005 조건별 결과 요약(ADR-003 요약기 복사 + S2 사용자별 판정·S7 이긴 쪽 롤백·매수 제어 타이머) — 원시 결과(results/<matrix-id>/)에서 표를 산출한다.

    scripts/summarize.py results/<matrix-id>          표 산출 → SUMMARY.md · summary.json · curves/
    scripts/summarize.py --s4-check <k6-summary.json>  S4 회차에 성공한 단계가 하나라도 있으면 exit 0

표의 모든 수치는 원시 파일에서 계산한다 (손 계산 금지). 회차 값은 중앙값 [최소–최대].
행은 plan.json(실행 전에 남긴 계획) 기준 — 실행되지 않은 셀·회차는 '미측정/누락'으로 드러난다.
"""
import csv
import gzip
import json
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
    """단계별 지표. 중단(abort)으로 끝난 마지막 단계는 30초를 다 채우지 못했으므로 partial로 표시하고 한계 산출에서 뺀다."""
    rows = []
    step = summ.get("step_seconds", 30)
    for i, target in enumerate(summ.get("stage_rates", [])):
        m = summ["metrics"]
        dur = m.get(f"hold_duration{{stage:{i}}}", {}).get("values", {})
        n = dur.get("count", 0)
        if not n:
            continue
        rows.append({
            "stage": i, "target_rps": target, "achieved_rps": n / step,
            "ok_rps": m.get(f"hold_201{{stage:{i}}}", {}).get("values", {}).get("count", 0) / step,
            "p99_ms": dur.get("p(99)"), "error_rate": m.get(f"hold_error{{stage:{i}}}", {}).get("values", {}).get("rate", 0),
            "partial": False,
        })
    if aborted and rows:
        rows[-1]["partial"] = True
    for r in rows:
        r["under_delivered"] = r["achieved_rps"] < 0.9 * r["target_rps"]   # k6가 목표 도착률을 못 채움
    return rows


def s4_limits(stages):
    """기준을 처음 위반한 단계의 직전 단계 성공 RPS = 그 기준의 한계. 포화점 = 완전한 단계 중 최대 성공 RPS."""
    complete = [s for s in stages if not s["partial"]]

    def limit(p99_ms, err):
        last = 0
        for s in complete:
            if s["p99_ms"] is None or s["p99_ms"] >= p99_ms or s["error_rate"] >= err:
                return last
            last = s["ok_rps"]
        return last
    return {"strict": limit(500, 0.01), "loose": limit(1000, 0.05),
            "saturation": max((s["ok_rps"] for s in complete), default=0),
            "under_delivered_stages": sum(1 for s in complete if s["under_delivered"])}


def s4_check(summary_path):
    """성공한 단계(에러율 < 50%이고 요청이 있었던 단계)가 하나라도 있으면 측정 성립."""
    summ = load(summary_path)
    if not summ:
        return 1
    return 0 if any(s["error_rate"] < 0.5 for s in stage_table(summ, aborted=False)) else 1


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
def hold_codes(csv_gz):
    """k6-requests.csv.gz의 hold_code 행(시나리오 lib — 요청 1건당 1행) → 태그 dict 목록. 행이 하나도 없으면 None(기록 없음 ≠ 0건)."""
    if not csv_gz.exists():
        return None
    rows = []
    with gzip.open(csv_gz, "rt", newline="") as f:
        header = next(csv.reader([f.readline()]))
        i_tags = header.index("extra_tags")
        for line in f:
            if not line.startswith("hold_code,"):
                continue
            row = next(csv.reader([line]))
            if len(row) <= i_tags:
                continue  # 잘린 마지막 행
            rows.append(dict(kv.split("=", 1) for kv in row[i_tags].split("&") if "=" in kv))
    return rows or None


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
                nsna = sum(1 for x in rs if x.get("code") == "SEAT_NOT_AVAILABLE")
                if nsna:
                    wronged_seats += 1
                    wronged_409 += nsna
    out["multi_201_seats"] = multi
    if seats is not None:
        out.update(empty_seats=empty, wronged_seats=wronged_seats, wronged_409=wronged_409)
        db_users = end_state.get("per_user") or {}
        out["u_over_limit_db"] = sum(1 for k, v in db_users.items() if 700000 < int(k) < 710000 and int(v) > limit)
    if lead:
        lead.sort()
        out["r_minus_u_ms"] = {"min": lead[0], "p50": lead[len(lead) // 2], "max": lead[-1], "neg": sum(1 for x in lead if x < 0)}
    return out


def timer(t):
    """Micrometer 타이머 차분(run.sh after_k6: COUNT·TOTAL_TIME 초 — 부하 중 증가분, MAX 초 — 최근 약 2분 창) → ms."""
    if not t:
        return None, None, None
    mean = t["TOTAL_TIME"] / t["COUNT"] * 1000 if t["COUNT"] else None
    return t["COUNT"], mean, t["MAX"] * 1000


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
    if base.startswith("S3"):
        rec["curve"] = s3_curve(rep_dir, meta, root, level, cell) or {}
    if base.startswith("S6") and summ:
        rec["s6_stages"] = s6_stage_table(summ)
        events = hold_events(rep_dir / "k6-requests.csv.gz", meta.get("ttl_s") or 3600) or []
        rec["duplicate"] = sum(1 for _, kind in events if kind == "duplicate")
    if base in ("S2", "S7"):
        limit = cons.get("max_per_user_limit", 2)
        try:
            codes = hold_codes(rep_dir / "k6-requests.csv.gz")
        except (EOFError, OSError, ValueError, StopIteration) as e:
            codes, rec["codes_error"] = None, f"{type(e).__name__}: {e}"
        end = load(rep_dir / "end-state.json")
        if codes is None:
            rec.setdefault("codes_error", "hold_code 행 없음")
        elif base == "S2":
            rec["s2"] = s2_users(codes, end, limit)
        else:
            rec["s7"] = s7_seats(codes, end, limit)
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
    rec["deadlocks"] = after.get("deadlocks_delta")
    # ADR-005 매수 제어 구간 타이머·SERIALIZABLE 카운터(앱 1대 — 앱 2대면 첫 앱만이 아니라 미측정으로 둔다)
    lim = [a.get("limit") for a in apps]
    if len(lim) == 1 and lim[0]:
        rec["limit_acquire_n"], rec["limit_acquire_mean_ms"], rec["limit_acquire_max_ms"] = timer(lim[0].get("acquire"))
        rec["limit_check_n"], rec["limit_check_mean_ms"], rec["limit_check_max_ms"] = timer(lim[0].get("check"))
        rec["serialization_failures"] = lim[0].get("serialization_failure")
        rec["limit_retries"] = lim[0].get("retry")
    rec["redis_used_mb"] = (after.get("redis") or {}).get("used_memory") and after["redis"]["used_memory"] / 1e6
    rec["redis_keys"] = (after.get("redis") or {}).get("keys")
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
    lines = [f"# ADR-005 결과 요약 — `{root.name}` · 매수 방식 {cond.get('limit_strategy')} · 좌석 전략 {cond.get('strategy')} · 풀 {cond.get('pool')} · 앱 {cond.get('apps')}대"
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
    lim_cols = ["매수 acquire 건수", "acquire 평균 ms", "acquire 최대 ms(최근 창)", "매수 check 건수", "check 평균 ms", "check 최대 ms(최근 창)", "40001 수", "재시도 수"]
    gd = lambda recs, key, d: spread([r.get(key) for r in recs], d)
    lim_vals = lambda ok: [g(ok, "limit_acquire_n"), gd(ok, "limit_acquire_mean_ms", 4), gd(ok, "limit_acquire_max_ms", 3),
                           g(ok, "limit_check_n"), gd(ok, "limit_check_mean_ms", 4), gd(ok, "limit_check_max_ms", 3),
                           g(ok, "serialization_failures"), g(ok, "limit_retries")]
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
    section("S4 처리량 한계 — 성공 RPS (기준을 처음 넘은 단계의 직전 단계)",
            ["단계", "셀", "n", "엄격 p99<500ms·에러<1%", "완화 p99<1s·에러<5%", "포화점", "목표 미달 단계 수", "k6 미시작(dropped)",
             "락 대기 최대(표본)", "풀 대기 최대(표본)", "커넥션 획득 대기 평균 ms", "풀 타임아웃", "데드락", "Redis MB·키", *lim_cols, "비정상 회차"],
            lambda x: x == "S4",
            lambda ok: [spread([r["limits"][k] for r in ok if "limits" in r]) for k in ("strict", "loose", "saturation", "under_delivered_stages")]
                       + [g(ok, "dropped"), g(ok, "lock_waiting_max"), g(ok, "hikari_pending_max"), g(ok, "acquire_mean_ms"),
                          g(ok, "pool_timeouts"), g(ok, "deadlocks"), f'{g(ok, "redis_used_mb")}·{g(ok, "redis_keys")}', *lim_vals(ok)])

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
    main(sys.argv[1])
