#!/usr/bin/env python3
"""ADR-006 조건별 결과 요약(ADR-005 요약기 복사 + S4 한계 규칙 수정 · 요청당 커넥션 빌림 · 서버 I/O 차분) — 원시 결과(results/<matrix-id>/)에서 표를 산출한다.

    scripts/summarize.py results/<matrix-id>          표 산출 → SUMMARY.md · summary.json · curves/
    scripts/summarize.py --s4-check <k6-summary.json>  S4 회차에 성공한 단계가 하나라도 있으면 exit 0

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

ADR-006 추가 지표
  요청당 커넥션 빌림 = Hikari 획득(hikaricp.connections.acquire) COUNT 증가분 ÷ 선점 요청(http.server.requests hold URI) COUNT 증가분 (k6 직전·직후 — run.sh after_k6).
  서버 I/O = io-before.json · io-after.json(lib.sh io_snapshot — k6 직전·직후, 각각 통계 반영 대기 IO_SETTLE_S 뒤) 차분. io_delta 참고.
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
    return {"elapsed_s": elapsed_ms / 1000, "settings": pa.get("settings"), "devices": a.get("devices"),
            "reset_changed": pa["wal"].get("stats_reset") != pb["wal"].get("stats_reset"),
            "wal": wal, "io": io, "bgwriter": bg, "disk": disk, "disk_main": main}


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
            "disk_util_pct": dm.get("util_pct"), "disk_write_mb": dm.get("write_mb")}


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
    # ADR-006 요청당 커넥션 빌림(명세 §9.4 ③) = Hikari 획득 COUNT 증가분 ÷ 선점 요청 증가분 — 앱 하나라도 조회 실패면 미측정.
    # hold_requests는 run.sh hold_requests가 낸다: 지표 목록에서 '기록 없음'이 확인될 때만 0, 그 밖의 조회 실패는 null → 증가분 null → 여기서 None
    # (ADR-006 초판은 실패를 0으로 채워 직전 값이 0이 되면 분모가 누적값이 되어 빌림이 조용히 낮게 나왔다). 범위 검사는 run.sh rep_status(invalid-borrow-ratio)
    holds = [a.get("hold_requests") for a in apps]
    rec["borrow_per_hold"] = count / sum(holds) if complete and holds and all(h is not None for h in holds) and sum(holds) else None
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
    lines = [f"# ADR-006 결과 요약 — `{root.name}` · 매수 방식 {cond.get('limit_strategy')} · 좌석 전략 {cond.get('strategy')} · 풀 {cond.get('pool')} · 앱 {cond.get('apps')}대"
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
