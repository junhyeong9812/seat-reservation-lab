#!/usr/bin/env python3
"""ADR-014 요약기·판정 규칙 단위 확인(서버 없이) — 가짜 원자료로 회차 폴더를 만들어 summarize.py --rep을 돌리고, 가짜 회차 요약으로 캠페인 판정
(성공 · 고르는 규칙 · 관문 · 결론)을 돌려 기대값과 대조한다.

    selftest.py [--keep <폴더>] [--campaign]      exit 0 = 모든 확인 통과
      --campaign: campaign.sh --simulate(서버 없음 — run.sh --simulate)로 60자리 루프 · 계획 대 실행 대조 · 재측정 상한(환경·하네스 따로) · stdin 차단도 확인한다

확인하는 것(측정-9 회차 사유 · 측정-7 성공 · 측정-8 고르는 규칙):
 ① 정상 주 셀 회차 → ok · W > 0(A) · T95 · 탐침 M3 ② 상태 표본 파일 없음 → state-sampler-gap(무음 0 아님) · 표본 4초 공백 → state-sampler-gap · 앞끝 공백(MR-4)
 ③ DB 컨테이너 docker stats 행 빠짐 → db-cpu-missing ④ 판정 창 안 dropped_iterations → dropped-iterations ⑤ 탐침 송신 오차 > 허용이 한 칸 10% 초과 → probe-timing-bad
 ⑥ 판정 요청이 안 나간 탐침 → probe-incomplete ⑦ 409 ≥ 1인데 원인 표지 대조 불일치 → app-metric-missing · 409 = 0이면 검사 건너뜀(MR-17) · 앱 지표 파일 없음 → app-metric-missing
 ⑧ env-stall: 정지 8초 + 서버 측정기 무응답 + GC 없음 + DB 쪽 원인 없음 → invalid:env-stall · 업무 세션 Lock 대기가 있으면 방식 정지(유효 · P12 자료) · 측정기가 응답했으면 방식 정지
 ⑨ 시계 차 왕복/2 > 20ms → clock-offset-bad ⑩ 테이블 통계 없음 → table-stats-missing ⑪ 설정 불일치 → expiry-mode-mismatch · ttl-mismatch · cond-config-mismatch
 ⑫ V11 인덱스 없음 → seat-map-index-missing ⑬ GC 로그 없음 → gc-log-missing ⑭ 증분·안전망 기록 0 → reconcile-missing ⑮ 비용 셀 → ok(상태 표본·탐침 사유 없음)
 ⑯ 409 원인: B·D의 nowait = 판정 409 + 분류 불가 · C의 nowait = 배치 잠금 409 ⑰ 상태 기대값 문턱(D 5행 · A·D 6행) ⑱ crn-mismatch(같은 회차 방식 사이 다수와 다름)
 ⑲ 캠페인: 모두 성공 → 2단 T95로 빠짐 · 3단 ≫(비겹침 + 10%) · 4단 D > B > C · 순환 처리 · G2(b)는 P3 유효일 때만 · G3 걸리면 다음 순위
 ⑳ 판정 불가: P7 재선점 0 · 'A도 같은 문턱'(P12) · 환경 상한 소진 → 결론 '판정 불가' · 유효 회차의 값 None → 비교에서 판정 불가(0으로 보지 않음)
 ㉑ 코드 리뷰 루프 2: 정합 문턱 열 결측·숫자 아님(CR14-8) · 시계 차 rtt_s(CR14-9) · 첫 선점 409 → probe-incomplete(CR14-10) · PR-3 실행 = 송신 수(CR14-11) ·
    h4-sampler-missing(CR14-12) · 창 끝 스냅숏 · P12 창 안 증분(CR14-13) · warmup-failed · 예열 상태 분포(CR14-14) · P5 판정 불가 · P6·P9 반증 · 정합 위반 · G2(c) ·
    상태 문턱 6·7·8행 'A도 같은 문턱' · --early-check(CR14-15) · G3 판정 불가 결론(CR14-18) · 재선점 기회 넓은·좁힌 값(CR14-19) · RESERVED ↔ 확정 짝 위반(CR14-20)
 ㉒ 코드 리뷰 루프 3: 끝 active_on_reserved_seats 정합 위반(CR14-21) · 첫 선점 기록 없음 → probe-incomplete(CR14-22) · 판정 요청 예상 밖 응답 → 판정 불가 응답(CR14-23) ·
    3단 비용 범위 None → 3단 판정 불가 · 결론 ⓓ(CR14-24)
 ㉓ 판정 입력 전수 점검(생성형): REP_INPUTS 표의 판정 입력을 하나씩 지우거나 숫자 아닌 값으로 바꾼 회차 → 모두 기대 사유로 무효 · P12 입력 → p12_unknown ·
    jsonl 깨진 줄 · CSV 기록 깨짐 · env-stall 입력 깨짐(→ 방식 정지) · 캠페인 요약 판정 열 결측 → 유효로 세지 않음 · 비용·2단 지표 None → 결론 판정 불가
 ㉔ 코드 리뷰 루프 3 후속: GC 로그 시각 못 읽는 정지·stall 줄 → gc-log-missing(CR14-25) · crn 다수결은 유효 회차끼리만 · 유효 1개면 대조 안 함(CR14-27) ·
    서버 시각 줄 가운데 찢김 → db-cpu-missing · 찢긴 마지막 줄 하나는 괜찮다(CR14-28) · A의 같은 (항목, 셀) 판정 불가 → X도 판정 불가(CR14-30)
 ㉕ 정식 스모크 뒤 사용자 결정(2026-10-29): ① 원인 표지 대조 허용(0 ≤ 앱 − k6 ≤ 응답 없는 선점 수) · ② PC 링크 끊김 → pc-link-down(환경 — kind_of = _kind) ·
    못 읽음·없음·구간 부족 → pc-link-missing · pclink14.sh 갈래(가짜 journalctl) · 캠페인 루프 환경 상한에 pc-link-down
 ㉖ 확인 스모크 뒤 명세 R5(2026-10-30): ① compose 로컬 렌더(max-connections 20000 · nofile 65536 · 네 방식 × 5셀 · 변수 없음) · 적용값 대조(maxConnections ·
    app_nofile — 다르거나 없으면 cond-config-mismatch · 확인 스모크 실제 8192) · PRE_VUS 처방(×1.5) · k6 lead 식(실측 11회) · run.sh 반영 ·
    ② pccheck14.sh 갈래(가짜 docker · /proc) · campaign.sh 시작 조건 ④ · 회차 중 MemAvailable < 4GiB → pc-mem-low(환경) · 결측 → pc-mem-missing · client_line14"""
import gzip
import json
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import plan14  # noqa: E402
import summarize as S  # noqa: E402

T0, OFF = 1_800_000_000.0, 0.4
FAILS = []
# 명세 §10 R5 ①(2026-10-30 확인 스모크): 측정 compose만 Tomcat max-connections 20,000 · 앱 컨테이너 nofile = 20,000 연결 + 여유(오버레이 ulimits)
R5_MAX_CONN, R5_NOFILE = 20000, 65536
REPO = HERE.parent.parent.parent
CONFIRM14 = REPO / "k6/ADR-014/results/confirm14-20261030"
HEAD = "metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,scenario,service,status,subproto,tls_version,url,extra_tags,metadata"


def check(name, cond, detail=""):
    print(("ok   " if cond else "FAIL ") + name + (f" — {detail}" if not cond and detail else ""))
    if not cond:
        FAILS.append(name)


def iso(t, tz="+00:00"):
    return datetime.fromtimestamp(t, timezone.utc).isoformat().replace("+00:00", tz)


def wj(p, x):
    Path(p).write_text(json.dumps(x))


def http(t, name, st, scen, v=5.0, tags=""):
    return f"http_req_duration,{int(t)},{v},,,,true,,POST,{name},HTTP/1.1,{scen},,{st},,,u,{tags},"


def cnt(m, t, scen, tags, v=1):
    return f"{m},{int(t)},{v},,,,,,,,,{scen},,,,,,{tags},"


def make_rep(root, method="A", cell="a20-t30", rep=1, **kw):
    """완전한 가짜 회차 폴더(주 셀 · 비용 셀). kw로 원자료를 하나씩 망가뜨린다."""
    plan = plan14.render(method, cell, rep)
    d = Path(root) / cell / method / f"rep{rep}"
    d.mkdir(parents=True, exist_ok=True)
    wj(d / "plan.json", plan)
    wj(d / "meta.json", {"rerun": False, "attempt": 1})
    (d / "harness-reasons.txt").write_text("")   # run.sh가 회차 시작에 빈 파일로 만든다
    wj(d / "k6-summary.json", {"setup_data": {"startedAt": T0 * 1000}})
    rtt = kw.get("rtt", 0.002)
    before = {"offset_s": OFF, "rtt_s": rtt}
    if kw.get("rtt_missing"):
        del before["rtt_s"]
    wj(d / "clock-offset.json", {"before": before, "after": {"offset_s": OFF + 0.001, "rtt_s": 0.002}})
    if not kw.get("no_warmup"):
        wj(d / "warmup-status.json", {"rc": kw.get("warmup_rc", 0), "by_status": {"warm-hold:201": 2000}, "n": 2000})
    wj(d / "pre-k6.json", {"seed_done_epoch": T0 - 87.0})
    wj(d / "k6-window.json", {"exit": 0, "k6_started": iso(T0), "k6_ended": iso(T0 + 300)})
    hold = {"expiryMode": kw.get("cfg_mode", plan14.MODE_ENUM[method]), "ttl": kw.get("cfg_ttl", f"PT{plan['ttl_s']}S"), "expiryInterval": "PT10S",
            "strategy": "PESSIMISTIC_NOWAIT", "limitStrategy": "COUNTER_UPSERT_PRECHECK", "maxPerUser": 2, "criticalSectionDelay": "PT0S",
            "counterDrift": {"expiryRepair": True, "reconcile": {"enabled": False, "detectShape": "SPLIT", "incremental": {"method": "TRIGGER", "enabled": True, "interval": "PT1S"},
                                                               "safetyNet": {"enabled": True}}, "cleanup": {"mode": "SCHEDULE"}}}
    tomcat = {"maxConnections": kw.get("cfg_maxconn", R5_MAX_CONN), "acceptCount": 100, "threads": {"max": 200, "minSpare": 10}}
    cfg = {"configprops": [{"prefix": "seat.hold", "properties": hold},
                           {"prefix": "spring.flyway", "properties": {"locations": kw.get("cfg_flyway", plan14.FLYWAY)}},
                           {"prefix": "server", "properties": {"tomcat": tomcat}}],
           "hikaricp.connections.max": [10], "app_nofile": {"soft": kw.get("cfg_nofile", R5_NOFILE), "hard": kw.get("cfg_nofile", R5_NOFILE)}}
    if kw.get("cfg_no_nofile"):
        del cfg["app_nofile"]
    wj(d / "app-config.json", cfg)
    wj(d / "limit-key.json", {"env": 0, "cmd": 0, "drift": 0, "drift_cmd": 0, "present": False})
    ss, sn = plan["seed"]["schedules"], plan["seed"]["seats"]
    wj(d / "seed-check.json", {"schedules": ss, "seats": ss * sn, "available": ss * sn, "holds": 0, "reservations": 0, "quota_rows": 0,
                               "seat_map_index": kw.get("index", 1), "flyway_v11": 1, "want_schedules": ss, "want_seats": ss * sn})
    (d / "logs-status.txt").write_text("app pipe=0/0 bytes=100\ndb pipe=0/0 bytes=100\n")
    wj(d / "probe-check.json", {"ok": True})
    wj(d / "pc-check.json", {"ok": True, "why": [], "containers_other": [], "load1": 0.5, "mem_avail_kb": 30 * 1024 * 1024})   # run.sh pccheck14(이름표만)
    win = plan["window"]
    if not kw.get("no_pc_link"):   # PC 링크 끊김 수집(pclink14.sh — 사용자 결정 ②): 회차 시작 ~ 끝
        nd = kw.get("pc_link_down", 0)
        wj(d / "pc-link.json", {"readable": kw.get("pc_link_readable", True), "iface": "eno1", "since_epoch": int(T0 - 300) + kw.get("pc_link_since_shift", 0),
                                "until_epoch": int(T0 + win["from_s"] + win["len_s"] + 400), "down": nd,
                                "lines": [f"2026-10-29T00:00:0{i}+09:00 pc kernel: r8169 eno1: Link is Down" for i in range(nd)], "why": ""})
    w0, w1 = T0 + win["from_s"], T0 + win["from_s"] + win["len_s"]
    if not kw.get("no_gc"):
        g = [f"[{iso(T0 - 100, '+0000')}][0.009s][info][gc     ] Using The Z Garbage Collector"]
        for t in range(int(w0), int(w1), 30):
            g.append(f'[{datetime.fromtimestamp(t + OFF, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000")}+0000][1s][info][safepoint   ] Safepoint "ZMarkStartYoungAndOld", '
                     "Time since last: 1 ns, Reaching safepoint: 1 ns, Cleanup: 1 ns, At safepoint: 1000 ns, Leaving safepoint: 1 ns, Total: 90000 ns")
        with gzip.open(d / "gc.log.gz", "wt") as f:
            f.write("\n".join(g) + "\n")
    stall = kw.get("stall")   # (시작 상대초, 길이)
    rows = []
    n409 = 0
    for s in range(int(w0) - 2, int(w1) + 12):
        rel = s - T0
        if stall and stall[0] <= rel < stall[0] + stall[1]:
            continue
        if plan["kind"] == "main":
            for st, n in (("201", 2), ("409", 3)):
                for _ in range(n):
                    rows.append(http(s, "hold", st, "blind"))
            n409 += 3 if w0 - 2 <= s else 0
            rows.append(http(s, "confirm", "200", "blind"))
            rows.append(http(s, "seatmap", "200", "map"))
        else:
            for _ in range(5):
                rows.append(http(s, "hold", "201", "s4", tags="stage=0" if rel >= 60 else "stage=ramp"))
    for i in range(kw.get("hold_code0", 0)):   # 응답 없는 선점(k6 코드 0 — 서버는 409로 셌을 수 있다 · 원인 표지 대조 허용 — 사용자 결정 ①)
        rows.append(http(T0 + 50 + i, "hold", "0", "blind"))
    alt = kw.get("crn_alt")   # True = 999(다른 해시) · 정수 = 그 값(CR14-27 — 무효 회차끼리 같은 다른 해시)
    for sc in ("blind", "map"):
        rows += [cnt("crn_n", T0, sc, "", 100), cnt("crn_h1", T0, sc, "", 12345 if not alt else 999 if alt is True else alt), cnt("crn_h2", T0, sc, "", 777)]
    if kw.get("dropped"):
        rows.append(cnt("dropped_iterations", T0 + 30, "blind", ""))
    hold409 = sum(1 for r in rows if r.startswith("http_req_duration") and ",hold," in r and ",409," in r)
    probe409 = 0
    if plan["kind"] == "main":
        pp = plan["probes"]
        ttl = plan["ttl_s"]
        for pr in ("pr1", "pr2", "pr3"):
            per = pp["period_ms"][pr] / 1000
            ds = plan14.DELTAS_C if pr == "pr3" else plan14.DELTAS
            for i in range(pp["count"][pr]):
                dd = ds[i % len(ds)]
                st0 = T0 + pp["start_s"] + i * per
                e = (st0 + OFF + ttl) * 1000
                base = f"pr={pr}&pid={i}&d={dd if pr == 'pr3' else int(dd)}"

                def prow(step, st, code, sent, plan_ms, rej="", ee=""):
                    return cnt("probe", sent / 1000, pr, f"{base}&step={step}&st={st}&code={code}&rej={rej}&sent={int(sent)}&plan={plan_ms}&e={ee}&rt=3")
                setups = ["h0"] if pr == "pr1" else ["h0a", "h0b"] if pr == "pr2" else ["u0"]
                rejected = kw.get("setup_reject") == (pr, i)   # 첫 선점이 5xx가 아닌 이유(409)로 실패 → 판정 요청이 나가지 않는다(CR14-10)
                for sp in setups:
                    if kw.get("setup_row_drop") == (pr, i, sp):   # 첫 선점 기록 행 자체가 빠짐(CR14-22)
                        continue
                    rows.append(prow(sp, "409" if rejected else "201", "SEAT_NOT_AVAILABLE" if rejected else "OK", st0 * 1000, "", ee=int(e)))
                if rejected:
                    continue
                skew = kw.get("skew", {}).get((pr, str(float(dd))), 0)
                if kw.get("probe_d_override", (None, None))[:2] == (pr, i):   # 계획에 없는 δ로 기록(판정 입력 전수 점검)
                    rows[-1] = rows[-1].replace(f"&d={dd if pr == 'pr3' else int(dd)}&", f"&d={kw['probe_d_override'][2]}&")
                if kw.get("skip_judged") == (pr, i):
                    continue
                odd = kw.get("judged_odd", {}).get((pr, i))   # 판정 요청 응답을 예상 밖 값으로(CR14-23) — {단계: 상태}
                if pr in ("pr1", "pr2"):
                    plan_ms = e + dd * 1000
                    sent = plan_ms - OFF * 1000 + (skew if i % 2 == 0 else 0)
                    if odd:
                        rows.append(prow("h1", odd["h1"], "BAD_REQUEST", sent, int(plan_ms)))
                    elif (method == "A" or kw.get("probe409")) and dd in (1, 3):
                        rows.append(prow("h1", "409", "SEAT_NOT_AVAILABLE" if pr == "pr1" else "HOLD_LIMIT_EXCEEDED", sent, int(plan_ms), rej=kw.get("probe_rej", "rule")))
                        probe409 += 1
                    else:
                        rows.append(prow("h1", "201", "OK", sent, int(plan_ms)))
                else:
                    tv, tc = e + 300, e + dd * 1000
                    sv, sc_ = ("409" if dd < 0 else "201"), ("200" if dd < 0 else "409")
                    rows.append(prow("v", (odd or {}).get("v", sv), "SEAT_NOT_AVAILABLE" if dd < 0 else "OK", tv - OFF * 1000, int(tv), rej="rule" if dd < 0 else ""))
                    probe409 += dd < 0 and (odd or {}).get("v", sv) == "409"
                    rows.append(prow("c", (odd or {}).get("c", sc_), "OK" if dd < 0 else "HOLD_EXPIRED", tc - OFF * 1000, int(tc)))
        # 주 흐름 홀드·확정 기록(재구성 — 좌석 1..50 · 이탈 25 · 재선점)
        for k in range(50):
            ex = T0 + OFF + 5 + k + ttl
            exs = "garbage" if kw.get("hold_exp_bad") == k else iso(ex).replace('+00:00', 'Z')
            rows.append(cnt("hold_ok", T0 + 5 + k, "blind", f"seat={k + 1}&exp={exs}&u={1000000 + k}&kind=first&fate={'abandon' if k % 2 else 'confirm'}"))
            if k % 2:
                ex2 = ex + 3 + ttl
                rows.append(cnt("hold_ok", ex + 3 - OFF, "blind", f"seat={k + 1}&exp={iso(ex2).replace('+00:00', 'Z')}&u={2000000 + k}&kind=first&fate=confirm"))
            else:
                rt = "" if kw.get("confirm_rt_drop") == k else "&rt=5"
                rows.append(cnt("confirm_rec", T0 + 10 + k, "blind", f"seat={k + 1}&exp={iso(ex).replace('+00:00', 'Z')}&st=200&code=OK&sent={int((T0 + 10 + k) * 1000)}{rt}"))
    rej_rule = hold409 + probe409
    if kw.get("rej_rows", True):
        rows.append(cnt("rej", T0 + 100, "blind", "cause=rule", rej_rule))
    with gzip.open(d / "k6-requests.csv.gz", "wt") as f:
        f.write(HEAD + "\n" + "\n".join(rows) + "\n")
    # docker stats(10초) — DB · 앱
    srv = []
    for t in range(int(w0) - 20, int(w1) + 30, 10):
        srv.append(json.dumps({"t": iso(t + OFF), "load": "1 1 1"}))
        if not (kw.get("db_rows_gap") and w0 + 50 <= t < w0 + 100):
            srv.append(json.dumps({"Name": "seatlab-db-1", "CPUPerc": "120.5%", "MemUsage": "1GiB / 14GiB"}))
        srv.append(json.dumps({"Name": "seatlab-app-1", "CPUPerc": "200%", "MemUsage": f"{900 + (t - w0) * kw.get('mem_slope', 0):.1f}MiB / 2GiB"}))
    (d / "timeline-server.jsonl").write_text("\n".join(srv) + "\n")
    if not kw.get("no_client"):   # k6 PC 5초 표본(lib14 client_line14) — MemAvailable(명세 R5 ②)
        (d / "timeline-client.jsonl").write_text(client_rows(w0 - 10, w1 + 10, dip=kw.get("client_dip"), gap=kw.get("client_gap"), drop_mem=kw.get("client_drop_mem")))
    cons = {"expiry_mode": plan["mode"], "v_counter_mismatch": 0, "v_over_limit_users": 0, "v_duplicate_confirmed_seats": 0, "double_sold_seats": 0,
            "multi_active_seats": 0, "held_no_active": kw.get("held_no_active_end", 0), "active_not_held": 0, "active_on_reserved_seats": 0,
            "expired_hold_rows": 5 if method == "B" else 0, "v_held_without_hold": kw.get("held_without_hold", 0), "confirmed": 25 + 87,
            "v_reserved_without_confirmed": kw.get("reserved_without_confirmed", 0), "v_confirmed_on_unreserved_seat": 0}
    if kw.get("cons_drop"):
        del cons[kw["cons_drop"]]
    cons.update(kw.get("cons_set", {}))
    wj(d / "consistency.json", cons)
    runs = [] if kw.get("no_reconcile") else [{"source": "inc-app-trg-1s", "started_at": iso(T0 + OFF + 1)}, {"source": "safety-app", "started_at": iso(T0 + OFF + 30)}]
    wj(d / "drift-db.json", {"drift_log_total": 0, "drift_log": [], "reconcile_runs": runs})
    for f in ("before", "after-k6", "end"):
        wj(d / f"drift-{f}.json", {"tick": {"ok": 1, "failed": 0}, "drift_record_failed": 0, "cleanup": {"errors": 0}})
    app_rule = rej_rule + kw.get("app_rule_extra", 0)
    tf_in, tf_out = kw.get("tick_failed_win", 0), kw.get("tick_failed_outside", 0)   # 틱 실패 — 판정 창 안 · 창 끝 뒤(CR14-13)
    for f, rr, tf in (("before", 0, 0), ("window-end", app_rule, tf_in), ("after-k6", app_rule, tf_in + tf_out), ("end", app_rule, tf_in + tf_out)):
        if kw.get("no_act") and f == "after-k6" or kw.get("no_wend") and f == "window-end":
            continue
        snap_t = {"t": iso(w1 + kw.get("wend_lag", 3.0)), "t_end": iso(w1 + kw.get("wend_lag", 3.0) + 0.5)} if f == "window-end" else {}
        wj(d / f"act14-{f}.json", {**snap_t, "rejected": {"rule": rr, "nowait": 0}, "tick": {"ok": 10, "failed": tf}, "reclaim": {"COUNT": 0, "TOTAL_TIME": 0, "MAX": 0},
                                   "deleted": {"reclaim": 0, "batch": 0}, "missing_pairs": {"reclaim": 0, "batch": 0}, "skip_locked": {"c2": 0, "h4b": 0},
                                   "revert": {"reclaim.reverted": 0, "reclaim.kept_active": 0, "batch.reverted": 0, "batch.kept_active": 3 if f != "before" else 0},
                                   "chunk": {"ok": 0, "failed": 0}, "missing_mismatch": 0})
    hk = []
    for t in range(int(w0) - 5, int(w1) + 5):
        hk.append(json.dumps({"t": iso(t), "t_ms": t * 1000, "pending": 0, "active": 3}))
    (d / "timeline-hikari.jsonl").write_text("\n".join(hk) + "\n")
    wt = []
    for i in range(int((w1 - w0 + 20) * 2)):
        t = w0 - 5 + i * 0.5
        bs = {"app|CPU:-": 2}
        if kw.get("lock_in_stall") and stall and stall[0] <= t - T0 < stall[0] + stall[1]:
            bs["app|Lock:tuple"] = 3
        wt.append(json.dumps({"t": iso(t + OFF), "waits": {}, "by_src": bs}))
    (d / "timeline-waits.jsonl").write_text("\n".join(wt) + "\n")
    pr = []
    for i in range(int((w1 - w0 + 40) * 10)):
        t = w0 - 10 + i * 0.1
        if stall and not kw.get("probe_alive") and stall[0] <= t - T0 < stall[0] + stall[1]:
            continue
        pr.append(f"http_req_duration,{int((t + OFF) * 1000)},1.0,,,,true,,GET,ping,HTTP/1.1,ping,,200,,,u,,")
    with gzip.open(d / "k6-probe-server.csv.gz", "wt") as f:
        f.write(HEAD + "\n" + "\n".join(pr) + "\n")
    if not kw.get("no_table"):
        for f, upd in (("before", 100), ("after", 100 + 112), ("end", 212)):
            if plan["kind"] == "cost" and f == "end":
                continue
            wj(d / f"table-{f}.json", {"tables": {"product_seat": {"n_tup_upd": upd}}, "deadlocks": 0, "confirmed": 112})
    if plan["kind"] == "main" and not kw.get("no_state"):
        st = []
        for i in range(-3, int(win["len_s"]) + 30):
            if kw.get("state_gap") and kw["state_gap"][0] <= i < kw["state_gap"][1]:
                continue
            if kw.get("state_late_start") and i < kw["state_late_start"]:
                continue
            t = T0 + OFF + i
            res = min(10000, 300 + i * 100)
            hna = 2 if method == "A" and i > 30 else (kw.get("hna_samples", 0) if i == 50 else 0)
            st.append(json.dumps({"t": iso(t), "db_now": iso(t), "reserved": res, "free_by_rule": 10000 - res, "held_no_active": hna, "active_not_held": 0,
                                  "multi_active": 0, "active_on_reserved": 0, "expired_rows": 4, "sample_ms": 12.5}))
        (d / "timeline-seatstate.jsonl").write_text("\n".join(st) + "\n")
    if plan["kind"] == "main" and not kw.get("no_h4"):
        (d / "timeline-h4.jsonl").write_text("\n".join(json.dumps({"t": iso(T0 + OFF + i / 2), "h4_active": 0, "h4_lock": 0}) for i in range(10)) + "\n")
    return d


def rep(d):
    return S.rep_summary(d)


def main():
    keep = None
    if "--keep" in sys.argv:
        keep = Path(sys.argv[sys.argv.index("--keep") + 1])
    tmp = Path(keep or tempfile.mkdtemp(prefix="adr014-selftest-"))
    if keep and tmp.exists():
        shutil.rmtree(tmp)
    tmp.mkdir(parents=True, exist_ok=True)
    try:
        run(tmp)
        if "--campaign" in sys.argv:
            campaign_sim(tmp)
    finally:
        if not keep:
            shutil.rmtree(tmp, ignore_errors=True)
    print(f"\n{'모두 통과' if not FAILS else f'실패 {len(FAILS)}: {FAILS}'}")
    return 1 if FAILS else 0


def run(tmp):
    # ① 정상
    s = rep(make_rep(tmp / "ok"))
    check("① 정상 주 셀 → ok", s["status"] == "ok", s["status"] + " " + json.dumps(s["missing_inputs"], ensure_ascii=False))
    st = s["metrics"].get("state") or {}
    check("① A W > 0 · T95 유한", (st.get("W") or 0) > 0 and st.get("T95") not in (None, S.INF), str(st.get("W")) + " " + str(st.get("T95")))
    m3 = s["metrics"]["probe"]["m3"]
    check("① A 탐침 δ1·δ3 부당 실패 > 0 · M3 비율", m3["unfair_d13"] > 0 and 0 < m3["rate"] < 1, json.dumps(m3)[:200])
    check("① 탐침 계획 대 실행 일치", not s["metrics"]["probe"]["incomplete"], str(s["metrics"]["probe"]["incomplete"]))
    check("① W 계단 함수 = Σ 2 × 1초(31~240초 표본 210개) ≈ 420", abs(st["W"] - 2 * (240 - 31)) < 3, str(st["W"]))
    # ②
    s = rep(make_rep(tmp / "nostate", no_state=True))
    check("② 상태 표본 없음 → state-sampler-gap(무음 0 아님)", "state-sampler-gap" in s["reasons"] and (s["metrics"].get("state") is None), s["status"])
    s = rep(make_rep(tmp / "gap", state_gap=(100, 104)))
    check("② 상태 표본 4초 공백 → state-sampler-gap", "state-sampler-gap" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "head", state_late_start=4))
    check("② 앞끝 공백(t0 → 첫 표본 4초 — MR-4) → state-sampler-gap", "state-sampler-gap" in s["reasons"], s["status"])
    # ③
    s = rep(make_rep(tmp / "dbcpu", db_rows_gap=True))
    check("③ DB docker stats 행 빠짐 → db-cpu-missing", "db-cpu-missing" in s["reasons"], s["status"])
    # ④
    s = rep(make_rep(tmp / "drop", dropped=True))
    check("④ 창 안 dropped_iterations → dropped-iterations", "dropped-iterations" in s["reasons"], s["status"])
    # ⑤ 한 칸(pr1 δ5)에서 짝수 순번만 200ms 늦게 → 절반 빠짐 > 10%
    s = rep(make_rep(tmp / "timing", skew={("pr1", "5.0"): 200}))
    check("⑤ 송신 오차 초과 > 10% → probe-timing-bad · probe-timing-dropped 기록", "probe-timing-bad" in s["reasons"] and any("probe-timing-dropped" in f for f in s["flags"]), s["status"])
    s = rep(make_rep(tmp / "timing-ok", skew={("pr1", "5.0"): 60}))
    check("⑤ 송신 오차 60ms(PR-1 허용 100ms 안) → 사유 아님", "probe-timing-bad" not in s["reasons"], s["status"])
    # ⑥
    s = rep(make_rep(tmp / "incomp", skip_judged=("pr2", 7)))
    check("⑥ 판정 요청 안 나간 탐침 → probe-incomplete", "probe-incomplete" in s["reasons"], s["status"])
    # ⑦
    s = rep(make_rep(tmp / "appm", app_rule_extra=5))
    check("⑦ 원인 표지 k6 합 ≠ 앱 지표 증분 → app-metric-missing", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "appm2", rej_rows=False))
    check("⑦ 409 ≥ 1인데 헤더 0 → app-metric-missing", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "appm3", no_act=True))
    check("⑦ 앱 지표 스냅숏 없음 → app-metric-missing(0으로 채우지 않음)", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "cost-ok", cell="S4c4200", method="D", rej_rows=False))
    check("⑦ 409 = 0 회차는 원인 표지 검사를 건너뛴다(MR-17)", "app-metric-missing" not in s["reasons"], s["status"])
    # ⑧ env-stall
    s = rep(make_rep(tmp / "env", stall=(100, 8)))
    check("⑧ 정지 8초 + 측정기 무응답 + GC 없음 + DB 원인 없음 → invalid:env-stall(환경 상한)", s["status"] == "invalid:env-stall" and s["env_stall_only"], s["status"] + json.dumps(s["metrics"].get("stall"), ensure_ascii=False)[:300])
    s = rep(make_rep(tmp / "envlock", stall=(100, 8), lock_in_stall=True))
    check("⑧ 업무 세션 Lock 대기 있음 → 방식 정지(유효 · P12 정지 초)", s["status"] == "ok" and (s["violations"]["p12"].get("method_stall_secs") or 0) >= 8, s["status"] + json.dumps(s["violations"]["p12"]))
    s = rep(make_rep(tmp / "envalive", stall=(100, 8), probe_alive=True))
    check("⑧ 서버 측정기가 응답했다 → 방식 정지", s["status"] == "ok" and (s["violations"]["p12"].get("method_stall_secs") or 0) >= 8, s["status"])
    s = rep(make_rep(tmp / "short", stall=(100, 3)))
    check("⑧ 3초 정지(5초 미만) → 방식 정지 초(환경 후보 아님)", s["status"] == "ok" and s["violations"]["p12"].get("method_stall_secs") == 3, s["status"])
    # ⑨~⑭
    check("⑨ 왕복/2 > 20ms → clock-offset-bad", "clock-offset-bad" in rep(make_rep(tmp / "clk", rtt=0.05))["reasons"])
    check("⑩ 테이블 통계 없음 → table-stats-missing", "table-stats-missing" in rep(make_rep(tmp / "tbl", no_table=True))["reasons"])
    r = rep(make_rep(tmp / "cfg1", method="B", cfg_mode="SWEEP"))["reasons"]
    check("⑪ expiryMode ≠ 셀 → expiry-mode-mismatch", "expiry-mode-mismatch" in r, str(r))
    check("⑪ TTL ≠ 셀 → ttl-mismatch", "ttl-mismatch" in rep(make_rep(tmp / "cfg2", cfg_ttl="PT5M"))["reasons"])
    check("⑪ Flyway에 V11 위치 없음 → cond-config-mismatch", "cond-config-mismatch" in rep(make_rep(tmp / "cfg3", cfg_flyway=plan14.FLYWAY[:3]))["reasons"])
    check("⑫ V11 인덱스 없음 → seat-map-index-missing", "seat-map-index-missing" in rep(make_rep(tmp / "idx", index=0))["reasons"])
    check("⑬ GC 로그 없음 → gc-log-missing", "gc-log-missing" in rep(make_rep(tmp / "gc", no_gc=True))["reasons"])
    check("⑭ 증분·안전망 기록 0 → reconcile-missing", "reconcile-missing" in rep(make_rep(tmp / "rec", no_reconcile=True))["reasons"])
    # ⑮ 비용 셀
    s = rep(make_rep(tmp / "cost", cell="S4c4200", method="A", rej_rows=False))
    check("⑮ 비용 셀 → ok(상태 표본·탐침 사유 없음) · 메모리 증가 계산", s["status"] == "ok" and s["metrics"].get("mem_growth") is not None, s["status"] + json.dumps(s["missing_inputs"], ensure_ascii=False))
    s = rep(make_rep(tmp / "costmem", cell="S4c4200", method="A", rej_rows=False, mem_slope=1.0))
    check("⑮ 비용 셀 앱 메모리 첫 1분 → 마지막 1분 ≥ 10% → P12 ④ 자료", s["violations"]["p12"].get("mem_growth_ge_10pct") == 1, json.dumps(s["violations"]["p12"]))
    # ⑯ 409 원인 분류
    check("⑯ B의 탐침 nowait = 판정 409 + 분류 불가", S.classify_409("B", "nowait") == ("rule", True) and S.classify_409("D", "") == ("rule", True))
    check("⑯ C의 nowait = 배치 잠금 409", S.classify_409("C", "nowait") == ("batch", False))
    s = rep(make_rep(tmp / "bnow", method="B", probe_rej="nowait", probe409=True))
    check("⑯ B 회차 탐침 nowait 409 → 판정 409(P4 반증 자료) + reject-cause-unknown 이름표 · 배치 잠금 0",
          s["violations"].get("p4_judge409", 0) > 0 and s["metrics"]["probe"]["m3"]["batch"] == 0 and any("reject-cause-unknown" in f for f in s["flags"]),
          json.dumps(s["metrics"]["probe"]["m3"])[:200])
    s = rep(make_rep(tmp / "cnow", method="C", probe_rej="nowait", probe409=True))
    check("⑯ C 회차 탐침 nowait 409 → 배치 잠금 409(M3ᵦ) · 판정 409 0(P4 문턱 밖)",
          s["metrics"]["probe"]["m3"]["batch"] > 0 and s["violations"].get("p4_judge409") == 0, json.dumps(s["metrics"]["probe"]["m3"])[:200])
    s = rep(make_rep(tmp / "bok", method="B"))
    check("⑯ B 정상 회차 → 판정 409 0", s["status"] == "ok" and s["violations"].get("p4_judge409") == 0, s["status"])
    # ⑰ 상태 기대값 문턱
    s = rep(make_rep(tmp / "d5", method="D", hna_samples=1))
    check("⑰ D 판정 창 표본 held_no_active > 0 → 5행 위반", any(v["row"] == 5 for v in s["violations"]["state"]), json.dumps(s["violations"]["state"]))
    s = rep(make_rep(tmp / "a6", method="A", held_without_hold=2))
    check("⑰ A v_held_without_hold > 0 → 6행 위반", any(v["row"] == 6 for v in s["violations"]["state"]))
    s = rep(make_rep(tmp / "b6", method="B", held_without_hold=2))
    check("⑰ B v_held_without_hold > 0 → 문턱 아님(기대 '> 0 가능')", not any(v["row"] == 6 for v in s["violations"]["state"]))
    # ⑱ crn
    root = tmp / "crn"
    for m in "ABCD":
        make_rep(root, method=m, crn_alt=(m == "C"))
        rep(root / "a20-t30" / m / "rep1")
        (root / "a20-t30" / m / "rep1" / "status").write_text("ok\n")
    S.crn_check(root)
    stc = (root / "a20-t30" / "C" / "rep1" / "status").read_text().strip()
    sta = (root / "a20-t30" / "A" / "rep1" / "status").read_text().strip()
    check("⑱ 다수와 다른 방식 → crn-mismatch · 나머지 그대로", stc == "invalid:crn-mismatch" and sta == "ok", stc + " / " + sta)
    root = tmp / "crn-none"
    for m in "ABCD":
        make_rep(root, method=m)
        rep(root / "a20-t30" / m / "rep1")
        (root / "a20-t30" / m / "rep1" / "status").write_text("ok\n")
    f = root / "a20-t30" / "B" / "rep1" / "rep-summary.json"
    x = json.loads(f.read_text()); x["metrics"]["crn"] = {}; f.write_text(json.dumps(x))
    S.crn_check(root)
    stb = (root / "a20-t30" / "B" / "rep1" / "status").read_text().strip()
    sta = (root / "a20-t30" / "A" / "rep1" / "status").read_text().strip()
    check("전수 점검 유효 회차 요약에 crn 합 없음 → crn-mismatch(대조에서 빠져 무음 통과하지 않는다) · 나머지 그대로", stb == "invalid:crn-mismatch" and sta == "ok", stb + " / " + sta)
    rep_round2(tmp)
    campaign_rules(tmp)
    campaign_round2(tmp)
    campaign_round3(tmp)
    early_check_rules(tmp)
    generative(tmp)
    generative_campaign(tmp)
    round3_followup(tmp)
    rej_tolerance(tmp)
    pc_link_rules(tmp)
    r5_conn_rules(tmp)
    r5_prevus_rules()
    r5_lead_rules()
    r5_pc_rules(tmp)


GIB_KB = 1024 * 1024


def fake_pc(tmp, name, containers=(), load1=0.5, mem_gib=30.0, docker_rc=0):
    """가짜 PC(docker · /proc/loadavg · /proc/meminfo) → env. containers = [(이름, compose 프로젝트)] — 실행 중인 것만."""
    d = tmp / f"pc-{name}"
    (d / "bin").mkdir(parents=True, exist_ok=True)
    lines = "\n".join(f"{n}\t{p}" for n, p in containers)
    (d / "bin" / "docker").write_text(f"#!/bin/sh\n[ \"$1\" = ps ] || exit 9\n[ {docker_rc} = 0 ] || {{ echo 'Cannot connect to the Docker daemon' >&2; exit {docker_rc}; }}\n"
                                      f"printf '%s' '{lines}'\n[ -n '{lines}' ] && echo\nexit 0\n")
    (d / "bin" / "docker").chmod(0o755)
    (d / "loadavg").write_text(f"{load1} 1.00 1.00 2/900 12345\n")
    (d / "meminfo").write_text(f"MemTotal:       38682556 kB\nMemFree:         1000000 kB\nMemAvailable:   {int(mem_gib * GIB_KB)} kB\n")
    return {**os.environ, "PATH": f"{d / 'bin'}:{os.environ['PATH']}", "PC_LOADAVG_FILE": str(d / "loadavg"), "PC_MEMINFO_FILE": str(d / "meminfo")}


def client_rows(t0, t1, mem_gib=20.0, dip=None, gap=None, drop_mem=None, every=5):
    """timeline-client.jsonl(lib14 start_pollers14 — 5초) 가짜 줄. dip = (상대초 a, b, GiB) · gap = (a, b) 표본 없음 · drop_mem = 그 상대초 줄에서 mem_avail_kb를 뺀다."""
    out = []
    for t in range(int(t0), int(t1), every):
        rel = t - T0
        if gap and gap[0] <= rel < gap[1]:
            continue
        m = dip[2] if dip and dip[0] <= rel < dip[1] else mem_gib
        row = {"t": iso(t), "k6_pcpu": 500.0, "k6_rss_kb": 4000000, "load": "8.0 7.0 6.0", "mem_avail_kb": int(m * GIB_KB)}
        if drop_mem is not None and drop_mem <= rel < drop_mem + every:
            del row["mem_avail_kb"]
        out.append(json.dumps(row))
    return "\n".join(out) + "\n"


def r5_pc_rules(tmp):
    pc = HERE / "pccheck14.sh"
    # ① 시작 조건 PC 점검(pccheck14.sh — 문턱: seatlab 밖 컨테이너 0 · 1분 load ≤ 4.0 · MemAvailable ≥ 16GiB · 판정 불가는 막음)
    cases = [("clean", {}, True), ("seatlab-only", {"containers": [("seatlab-app-1", "seatlab")]}, True),
             ("other-ctr", {"containers": [("ccle-postgres-1", "ccle")]}, False), ("plain-ctr", {"containers": [("check-c2-tbuild", "")]}, False),
             ("load", {"load1": 4.5}, False), ("load-edge", {"load1": 4.0}, True), ("mem", {"mem_gib": 12.0}, False), ("docker-down", {"docker_rc": 1}, False)]
    bad = []
    for name, kw, want in cases:
        env = fake_pc(tmp, name, **kw)
        r = subprocess.run(["bash", str(pc)], capture_output=True, text=True, env=env)
        try:
            j = json.loads(r.stdout)
        except ValueError:
            j = None
        if not (j and j.get("ok") is want and (r.returncode == 0) == want and (isinstance(j.get("mem_avail_kb"), int) or not want)):
            bad.append(f"{name}: rc={r.returncode} {r.stdout[:200]} {r.stderr[:120]}")
    check("R5 ② pccheck14: 깨끗 · seatlab만 → 통과 · 다른 컨테이너(프로젝트 없음 포함) · load > 4 · MemAvailable < 16GiB · docker 못 읽음 → 막음", not bad, "; ".join(bad))
    env = fake_pc(tmp, "nomem")
    Path(env["PC_MEMINFO_FILE"]).write_text("MemTotal: 1 kB\n")
    r = subprocess.run(["bash", str(pc)], capture_output=True, text=True, env=env)
    check("R5 ② pccheck14: MemAvailable 못 읽음 → 막음(판정 불가 = 시작하지 않음)", r.returncode != 0 and '"ok": false' in r.stdout.replace('"ok":false', '"ok": false'), r.stdout[:200])
    # ② campaign.sh 시작 조건 ④에 PC 점검이 들어간다(dry-run 줄 — 서버 접속 없음)
    sc = tmp / "start.json"
    sc.write_text(json.dumps({"smoke": {"done": True}, "campaign_smoke": {"done": True}, "pc_check": {"done": True}, "impl_verify": {"done": True},
                              "concurrency_green": {"done": True, "sha": "x"}}))
    outs = {}
    for name, kw in (("clean", {}), ("busy", {"containers": [("ccle-postgres-1", "ccle")], "load1": 20.0})):
        env = fake_pc(tmp, f"camp-{name}", **kw)
        p = subprocess.run(["bash", str(HERE / "campaign.sh"), "--sha", "HEAD", "--dry-run", "--worktree", "--start-conditions", str(sc)], cwd=REPO,
                           capture_output=True, text=True, env=env, timeout=300)
        outs[name] = next((l for l in p.stdout.splitlines() if l.startswith("④")), "")
    check("R5 ② campaign.sh 시작 조건 ④: PC 깨끗 → 통과 · 다른 컨테이너 · load 20 → 막음(PC 다른 부하)",
          outs["clean"].endswith("통과") and "PC" in outs["clean"] and outs["busy"].endswith("막음") and "ccle-postgres-1" in outs["busy"], str(outs))
    # ③ 회차 중 MemAvailable(timeline-client.jsonl mem_avail_kb — 판정 창): 최소 < 4GiB → pc-mem-low(환경) · 표본 결측 → pc-mem-missing(하네스)
    s = rep(make_rep(tmp / "pcm-ok"))
    check("R5 ③ 회차 중 MemAvailable 20GiB → ok · 지표에 최소값", s["status"] == "ok" and s["metrics"].get("pc_mem", {}).get("min_kb") == 20 * GIB_KB, s["status"] + str(s["metrics"].get("pc_mem")))
    s = rep(make_rep(tmp / "pcm-low", client_dip=(100, 120, 1.98)))
    check("R5 ③ 판정 창 안 MemAvailable 1.98GiB(확인 스모크 최저) → invalid:pc-mem-low · 분류 환경", s["status"] == "invalid:pc-mem-low" and S._kind(s["status"]) == "env", s["status"])
    s = rep(make_rep(tmp / "pcm-low-drop", client_dip=(100, 120, 2.0), dropped=True))
    check("R5 ③ pc-mem-low + dropped-iterations → 그래도 환경(메모리 부족의 결과 — pc-link-down과 같은 규칙)", "pc-mem-low" in s["reasons"] and S._kind(s["status"]) == "env", s["status"])
    s = rep(make_rep(tmp / "pcm-edge", client_dip=(100, 120, 4.0)))
    check("R5 ③ MemAvailable 정확히 4GiB → 무효 아님(문턱 미만만)", s["status"] == "ok", s["status"])
    s = rep(make_rep(tmp / "pcm-outside", client_dip=(-60, -10, 1.0)))
    check("R5 ③ 판정 창 밖(t0 앞) MemAvailable 부족 → 무효 아님(창 안만 본다)", s["status"] == "ok", s["status"])
    s = rep(make_rep(tmp / "pcm-none", no_client=True))
    check("R5 ③ timeline-client.jsonl 없음 → pc-mem-missing(하네스 — 무효 · 부족 없음으로 넘기지 않는다)", s["status"] == "invalid:pc-mem-missing" and S._kind(s["status"]) == "harness", s["status"])
    s = rep(make_rep(tmp / "pcm-gap", client_gap=(100, 160)))
    check("R5 ③ 표본 공백 60초 > 30초 → pc-mem-missing", "pc-mem-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "pcm-col", client_drop_mem=100))
    check("R5 ③ 창 안 한 줄에 mem_avail_kb 없음 → pc-mem-missing", "pc-mem-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "pcm-cost", cell="S4c4200", method="D", rej_rows=False, client_dip=(100, 110, 1.0)))
    check("R5 ③ 비용 셀도 pc-mem-low", s["status"] == "invalid:pc-mem-low", s["status"])
    # ④ campaign.sh kind_of = summarize._kind(pc-mem-low 포함)
    sts = ["invalid:pc-mem-low", "invalid:dropped-iterations,pc-mem-low", "invalid:pc-mem-low,probe-incomplete", "invalid:pc-mem-missing",
           "invalid:env-stall,pc-mem-low", "invalid:pc-link-down,pc-mem-low", "ok"]
    fn = next(l for l in (HERE / "campaign.sh").read_text().splitlines() if l.startswith("kind_of()"))
    got = subprocess.run(["bash", "-c", fn + "\nfor s in \"$@\"; do kind_of \"$s\"; done", "x", *sts], capture_output=True, text=True).stdout.split()
    want = ["env", "env", "env", "harness", "env", "env", "valid"]
    check("R5 ③ campaign.sh kind_of = summarize._kind = 기대(pc-mem-low는 다른 사유와 함께여도 환경)", got == want and [S._kind(x) for x in sts] == want,
          f"{list(zip(sts, got, [S._kind(x) for x in sts]))}")
    # ⑤ 회차 기록 줄(lib14.sh client_line14): k6 pcpu · rss · load · MemAvailable — ps가 비어도(프로세스 끝) JSON이 깨지지 않는다
    env = fake_pc(tmp, "line", load1=2.5, mem_gib=21.0)
    lines = {}
    for name, pid in (("self", str(os.getpid())), ("gone", "999999999")):
        r = subprocess.run(["bash", "-c", f"LIB9_DIR={REPO}/k6/ADR-009/scripts; source {HERE}/lib14.sh; client_line14 {pid}"], capture_output=True, text=True, env=env)
        try:
            lines[name] = json.loads(r.stdout)
        except ValueError:
            lines[name] = r.stdout + r.stderr[-200:]
    a, b = lines["self"], lines["gone"]
    check("R5 ⑤ client_line14: mem_avail_kb · load(가짜 /proc) · pcpu · rss 숫자 · 없는 pid는 null(깨진 JSON 아님)",
          isinstance(a, dict) and a.get("mem_avail_kb") == 21 * GIB_KB and a.get("load", "").startswith("2.5") and isinstance(a.get("k6_rss_kb"), int)
          and isinstance(b, dict) and b.get("k6_pcpu") is None and b.get("mem_avail_kb") == 21 * GIB_KB, str(lines))


# k6 실행 → 첫 요청(setup_data.startedAt) 실측(k6/ADR-014/results — pre-k6.json k6_launch_epoch · k6-summary.json): (미리 만든 VU 합, 초, 출처)
K6_LEAD_SEEN = [(500, 0.266, "smoke14-20261029 S4c4200 A"), (500, 0.276, "smoke14-20261029 S4c4200 D"), (2500, 0.891, "smoke14-20261028 a50-t30 A"),
                (2500, 1.137, "smoke14-20261029 A attempt1"), (2500, 0.930, "smoke14net-20261028 B"), (4700, 1.509, "smoke14-20261029 A"),
                (4700, 1.420, "smoke14-20261029 C"), (4700, 1.712, "smoke14-20261029 D"), (4700, 3.115, "confirm14 A(PC load 14~36)"),
                (4700, 2.661, "confirm14 B(PC load)"), (13300, 6.610, "confirm14 a50-t300 D(PC load)")]


def r5_lead_rules():
    # 시작 대기(시드 끝 → 첫 요청 87 ± 5초 — 판정 기준 그대로): k6 실행 → 첫 요청 lead를 미리 만든 VU 수로 잡아야 VU를 많이 미리 만드는 칸에서도 87초에 맞는다
    leads = {}
    for c in plan14.GROUPS:
        k6 = plan14.render("A", c, 1)["k6"]
        leads[c] = (k6.get("pre_vus_total"), k6.get("lead_s"))
    check("R5 lead: 모든 셀 계획에 미리 만든 VU 합 · lead_s(초) · TTL 5분 칸 lead > TTL 30초 칸 > 비용 셀",
          all(isinstance(v, int) and isinstance(l, float) for v, l in leads.values()) and leads["a50-t300"][1] > leads["a50-t30"][1] > leads["S4c4200"][1], str(leads))
    if not all(isinstance(l, float) for _, l in leads.values()):
        return
    check("R5 lead: 미리 만든 VU 합 = 계획 env 합(맹목 + 지도 + 탐침 × 3) — a50-t300 15,300 · a50-t30 4,700 · 비용 500(s4-throughput 기본값)",
          (leads["a50-t300"][0], leads["a50-t30"][0], leads["S4c4200"][0]) == (15300, 4700, 500), str(leads))
    bad = [f"{src} V={v} 실측 {s} 계획 {plan14.k6_lead_s(v):.2f}" for v, s, src in K6_LEAD_SEEN if abs(plan14.k6_lead_s(v) - s) > 2.5]
    check("R5 lead: 식이 실측 11회(PC 한가함 · 부하 둘 다 — 13,300 VU 6.61초 포함)와 2.5초 안(시드 → 첫 요청이 87 ± 5초 안에 남는 여유)", not bad, "; ".join(bad))
    old = [f"{src} {87 - 1.2 + s:.1f}" for v, s, src in K6_LEAD_SEEN if abs(s - 1.2) > 5]
    check("R5 lead: (대조) 옛 고정 lead 1.2초 기준이면 11회 중 13,300 VU만 87 ± 5 밖(92.4초 — 그 회차 실측 시드 → 첫 요청 93.2초 pre-k6-mismatch)", old == ["confirm14 a50-t300 D(PC load) 92.4"], str(old))
    p = subprocess.run(["bash", str(HERE / "run.sh"), "--dry-run", "--sha", "HEAD", "--method", "D", "--cell", "a50-t300", "--rep", "1", "--id", "x"],
                       capture_output=True, text=True, cwd=REPO)
    check("R5 lead: run.sh가 계획의 lead_s로 시작 대기를 맞춘다(dry-run 시각 줄)", f"k6 lead {leads['a50-t300'][1]}초" in p.stdout, p.stdout[-400:] + p.stderr[-200:])


def r5_prevus_rules():
    # 측정-9 시작 조건 ⑤ 처방(재해석 2026-10-29): 미리 만든 VU · maxVUs = max(14,000, ⌈VU 최대 × 1.5⌉) — 확인 스모크 a50-t300 D 흐름별 VU 최대(맹목 9,254 · 지도 583)
    vu = {"blind": 9254, "map": 583}
    need = {f: -(-v * 3 // 2) for f, v in vu.items()}   # ⌈v × 1.5⌉ = 13,881 · 875
    bad = []
    for c in ("a20-t300", "a50-t300"):
        e = plan14.render("A", c, 1)["k6"]["env"]
        for f, key in (("blind", "BLIND"), ("map", "MAP")):
            pre, mx = int(e[f"PRE_VUS_{key}"]), int(e[f"MAX_VUS_{key}"])
            if pre < need[f] or mx != max(14000, need[f]) or pre > mx:
                bad.append(f"{c} {f} pre={pre} max={mx} need={need[f]}")
    check("R5 PRE_VUS: TTL 5분 칸 미리 만든 VU ≥ ⌈VU 최대 × 1.5⌉(맹목 13,881 · 지도 875) · maxVUs = max(14,000, ⌈×1.5⌉) ≥ 미리 만든 VU", not bad, "; ".join(bad))
    e = plan14.render("A", "a50-t30", 1)["k6"]["env"]
    check("R5 PRE_VUS: TTL 30초 칸은 그대로(4,000 · 400)", (e["PRE_VUS_BLIND"], e["PRE_VUS_MAP"]) == ("4000", "400"), str(e["PRE_VUS_BLIND"]))


# ---- 명세 §10 R5(2026-10-30 확인 스모크) — 측정 조건 ① 연결 상한 ---------------------------------------------------------------------
def compose_app(app_env):
    """측정 compose(ADR-009 compose.yml + tmpfs + compose.expiry.yml — lib14.sh compose14와 같은 -f 묶음)를 로컬에서 렌더해 app 서비스를 낸다(서버 접속 없음)."""
    env = {k: v for k, v in os.environ.items() if not k.startswith(("SPRING", "APP_", "TOMCAT"))}
    env.update(app_env)
    p = subprocess.run(["docker", "compose", "-p", "seatlab", "-f", "k6/ADR-009/compose.yml", "-f", "k6/ADR-009/compose.tmpfs.yml", "-f", "k6/ADR-014/compose.expiry.yml",
                        "config", "--format", "json"], cwd=REPO, capture_output=True, text=True, env=env)
    if p.returncode != 0:
        return None, p.stderr[-300:]
    return json.loads(p.stdout)["services"]["app"], ""


def r5_conn_rules(tmp):
    # ① 렌더: 네 방식 × 모든 셀(주 · 비용)에서 앱 JSON server.tomcat.max-connections = 20,000 · ulimits nofile · 다른 Tomcat 키 없음
    bad = []
    for m in plan14.ABCD:
        for c in plan14.GROUPS:
            app, err = compose_app(plan14.render(m, c, 1)["app_env"])
            if app is None:
                bad.append(f"{m} {c} 렌더 실패 {err}")
                continue
            sj = json.loads(app["environment"]["SPRING_APPLICATION_JSON"])
            tc = (sj.get("server") or {}).get("tomcat") or {}
            nf = (app.get("ulimits") or {}).get("nofile") or {}
            if tc != {"max-connections": R5_MAX_CONN} or nf != {"soft": R5_NOFILE, "hard": R5_NOFILE}:
                bad.append(f"{m} {c} tomcat={tc} nofile={nf}")
    check("R5 ① compose 렌더: 네 방식 × 5셀 모두 max-connections 20000(다른 Tomcat 키 없음) · nofile 65536", not bad, "; ".join(bad[:3]))
    app, err = compose_app({})
    sj = json.loads(app["environment"]["SPRING_APPLICATION_JSON"]) if app else {}
    check("R5 ① compose 렌더(변수 없음 — 서버 측정기 기동 경로): 기본값도 20000 · 65536", app is not None and sj.get("server", {}).get("tomcat") == {"max-connections": R5_MAX_CONN}
          and app.get("ulimits", {}).get("nofile") == {"soft": R5_NOFILE, "hard": R5_NOFILE}, err or str(sj.get("server")))
    # ② 적용값 대조(configprops · 앱 컨테이너 nofile) — 다르면 cond-config-mismatch(하네스 무효)
    s = rep(make_rep(tmp / "r5-ok"))
    check("R5 ② 적용값 20000 · nofile 65536 → ok", s["status"] == "ok", s["status"])
    s = rep(make_rep(tmp / "r5-8192", cfg_maxconn=8192))
    check("R5 ② maxConnections 8192(앱 기본값 — 오버레이 미적용) → cond-config-mismatch", "cond-config-mismatch" in s["reasons"] and s["status"] != "ok", s["status"])
    s = rep(make_rep(tmp / "r5-nofile", cfg_nofile=8192))
    check("R5 ② 앱 nofile 8192 < 필요 → cond-config-mismatch", "cond-config-mismatch" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "r5-nofile-none", cfg_no_nofile=True))
    check("R5 ② app_nofile 수집 없음 → cond-config-mismatch(무음 통과 아님)", "cond-config-mismatch" in s["reasons"], s["status"])
    d = make_rep(tmp / "r5-noserver")
    x = json.loads((d / "app-config.json").read_text())
    x["configprops"] = [b for b in x["configprops"] if b["prefix"] != "server"]
    (d / "app-config.json").write_text(json.dumps(x))
    s = rep(d)
    check("R5 ② configprops server 묶음 없음 → cond-config-mismatch", "cond-config-mismatch" in s["reasons"], s["status"])
    real = CONFIRM14 / "a50-t300/D/rep1/app-config.json"
    if real.exists():
        res = plan14.config_check("D", "a50-t300", json.loads(real.read_text()))
        check("R5 ② 확인 스모크 실제 app-config.json(maxConnections 8192)을 대조 → cond-config-mismatch · maxConnections 상세",
              res is not None and "cond-config-mismatch" in res[0] and any("maxConnections" in x for x in res[1]), str(res))


# ---- 코드 리뷰 루프 2(CR14-8 ~ CR14-20) — 회차 판정의 새 분기 ---------------------------------------------------------------------
def rep_round2(tmp):
    base = rep(make_rep(tmp / "r2-ok"))
    check("㉑ 정상 회차에 새 분기 사유·이름표 없음(h4-sampler-missing · warmup-failed · 창 끝 스냅숏)",
          base["status"] == "ok" and not any("h4-sampler-missing" in f for f in base["flags"]) and 0 <= base["metrics"]["wend_lag_s"] <= S.WEND_LAG_MAX_S,
          base["status"] + json.dumps(base["flags"], ensure_ascii=False))
    # CR14-8 — 정합 문턱 열: 없음 · None · 숫자 아님 → end-check-failed(0으로 채우지 않는다)
    for name, kw in (("열 없음", {"cons_drop": "double_sold_seats"}), ("None", {"cons_set": {"multi_active_seats": None}}),
                     ("문자열", {"cons_set": {"v_over_limit_users": "0"}}), ("bool", {"cons_set": {"v_counter_mismatch": False}}),
                     ("새 열 없음(CR14-20)", {"cons_drop": "v_confirmed_on_unreserved_seat"}),
                     ("끝 active_on_reserved 없음(CR14-21)", {"cons_drop": "active_on_reserved_seats"})):
        s = rep(make_rep(tmp / f"r2-cons-{len(name)}-{list(kw)[0]}", **kw))
        check(f"CR14-8 정합 문턱 열 {name} → end-check-failed · 정합 자료 없음", "end-check-failed" in s["reasons"] and "integrity" not in s["violations"],
              s["status"] + json.dumps(s["missing_inputs"], ensure_ascii=False))
    # CR14-9 — 시계 차 rtt_s
    s = rep(make_rep(tmp / "r2-rtt-none", rtt_missing=True))
    check("CR14-9 앞 시계 차 표본 rtt_s 없음 → clock-offset-bad", "clock-offset-bad" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "r2-rtt-neg", rtt=-0.001))
    check("CR14-9 rtt_s 음수 → clock-offset-bad", "clock-offset-bad" in s["reasons"], s["status"])
    # CR14-10 — 첫 선점 409 → 판정 요청 안 나감 → probe-incomplete · 원인 이름표는 남긴다
    s = rep(make_rep(tmp / "r2-setup", setup_reject=("pr1", 3)))
    pm = s["metrics"]["probe"]
    check("CR14-10 탐침 첫 선점 409(5xx 아님) → probe-incomplete · setup_rejected · probe-anomaly 이름표",
          "probe-incomplete" in s["reasons"] and pm["setup_rejected"] and any("첫 선점 409" in x for x in pm["judged_missing"])
          and any("probe-anomaly" in f for f in s["flags"]), s["status"] + json.dumps(pm["judged_missing"], ensure_ascii=False))
    # CR14-11 — PR-3 실행 = 송신된 판정 요청 수
    pr3 = base["metrics"]["probe"]["pr3"]
    check("CR14-11 정상 회차 PR-3 송신 = 계획", pr3["sent"] == pr3["planned"] == 175 and pr3["executed"] == 175, json.dumps(pr3, ensure_ascii=False))
    s = rep(make_rep(tmp / "r2-pr3", skip_judged=("pr3", 5)))
    check("CR14-11 PR-3 판정 요청 하나 안 나감 → 송신 < 계획 · probe-incomplete", s["metrics"]["probe"]["pr3"]["sent"] == 174 and "probe-incomplete" in s["reasons"], s["status"])
    # CR14-12 — 주 셀 H4 표본 파일 없음 → 이름표만
    s = rep(make_rep(tmp / "r2-h4", no_h4=True))
    check("CR14-12 주 셀 timeline-h4.jsonl 없음 → h4-sampler-missing 이름표(유효)", s["status"] == "ok" and any("h4-sampler-missing" in f for f in s["flags"]), s["status"])
    # CR14-13 — 판정 창 끝 스냅숏 · P12 창 안 증분
    s = rep(make_rep(tmp / "r2-wend-none", no_wend=True))
    check("CR14-13 창 끝 스냅숏 없음 → app-metric-missing · P12 틱 실패 판정 불가", "app-metric-missing" in s["reasons"] and "tick_failed" in s["violations"]["p12_unknown"], s["status"])
    s = rep(make_rep(tmp / "r2-wend-early", wend_lag=-1.0))
    check("CR14-13 창 끝 스냅숏이 창 끝보다 이르다 → app-metric-missing", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "r2-wend-late", wend_lag=S.WEND_LAG_MAX_S + 5))
    check("CR14-13 창 끝 스냅숏이 창 끝 + 10초보다 늦다 → app-metric-missing", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "r2-tick-out", tick_failed_outside=2))
    check("CR14-13 창 밖(창 끝 뒤) 틱 실패 → P12 아님 · 기록만",
          s["status"] == "ok" and "tick_failed" not in s["violations"]["p12"] and s["metrics"]["app"]["tick_failed_outside"] == 2 and s["metrics"]["app"]["tick_failed_k6"] == 2,
          json.dumps(s["violations"]["p12"]) + json.dumps(s["metrics"]["app"]))
    s = rep(make_rep(tmp / "r2-tick-in", tick_failed_win=1))
    check("CR14-13 창 안 틱 실패 → P12 tick_failed", s["status"] == "ok" and s["violations"]["p12"].get("tick_failed") == 1, json.dumps(s["violations"]["p12"]))
    # CR14-14 — 예열
    s = rep(make_rep(tmp / "r2-warm-rc", warmup_rc=1))
    check("CR14-14 예열 k6 rc ≠ 0 → warmup-failed", "warmup-failed" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "r2-warm-none", no_warmup=True))
    check("CR14-14 예열 기록 없음 → warmup-failed(무음 통과 아님)", "warmup-failed" in s["reasons"], s["status"])
    wcsv = tmp / "warm.csv.gz"
    with gzip.open(wcsv, "wt") as f:
        f.write(HEAD + "\n" + "\n".join([http(T0, "warm-hold", "201", "hold")] * 3 + [http(T0, "warm-hold", "409", "hold"), cnt("iterations", T0, "hold", "")]) + "\n")
    w = S.warmup_status(wcsv, 0)
    check("CR14-14 예열 상태 분포(이름:상태별 수)", w == {"rc": 0, "by_status": {"warm-hold:201": 3, "warm-hold:409": 1}, "n": 4}, json.dumps(w))
    check("CR14-14 예열 CSV 없음 → 분포 None · rc는 그대로", S.warmup_status(tmp / "nope.csv.gz", 2) == {"rc": 2, "by_status": None, "n": None})
    # CR14-19 — P7 재선점 기회 넓은 값 · 좁힌 값(재선점 = 앞 만료 + 3초 < 배치 주기 10초)
    fl = base["metrics"]["flow"]
    check("CR14-19 재선점 기회 넓은 값 · 좁힌 값 둘 다", fl["rehold_other"] == 25 and fl["rehold_other_narrow"] == 25, json.dumps(fl, ensure_ascii=False)[:200])
    # CR14-20 — 좌석 RESERVED ↔ 확정 짝 위반 → 정합 위반
    s = rep(make_rep(tmp / "r2-resv", reserved_without_confirmed=1))
    check("CR14-20 v_reserved_without_confirmed > 0 → 정합 위반(유효 회차의 방식 실패 자료)",
          s["status"] == "ok" and s["violations"]["integrity"].get("reserved_without_confirmed") == 1, json.dumps(s["violations"].get("integrity")))
    # CR14-22 — 첫 선점 기록 행 자체가 없음 → 판정 요청이 있어도 정상 탐침으로 판정하지 않는다 → probe-incomplete
    for pr, sp in (("pr1", "h0"), ("pr2", "h0a"), ("pr2", "h0b"), ("pr3", "u0")):
        s = rep(make_rep(tmp / f"r3-nosetup-{pr}-{sp}", setup_row_drop=(pr, 4, sp)))
        pm = s["metrics"]["probe"]
        check(f"CR14-22 {pr} 첫 선점 {sp} 기록 없음 → probe-incomplete · judged_missing",
              "probe-incomplete" in s["reasons"] and any(f"{pr}#4:첫 선점 {sp} 기록 없음" in x for x in pm["judged_missing"]),
              s["status"] + json.dumps(pm["judged_missing"], ensure_ascii=False))
    # CR14-23 — 판정 요청 응답이 예상 밖(400·404) → 판정 불가 응답 → 분모에서 빠지고 probe-incomplete
    for name, odd in (("PR-1 h1 400", {("pr1", 2): {"h1": "400"}}), ("PR-2 h1 404", {("pr2", 3): {"h1": "404"}}),
                      ("PR-3 v 400", {("pr3", 6): {"v": "400"}}), ("PR-3 c 400", {("pr3", 7): {"c": "400"}})):
        s = rep(make_rep(tmp / f"r3-odd-{name.replace(' ', '-')}", judged_odd=odd))
        pm = s["metrics"]["probe"]
        check(f"CR14-23 {name} → 판정 불가 응답 1 · probe-incomplete · probe-anomaly 이름표 · M3 분모에서 빠짐",
              "probe-incomplete" in s["reasons"] and len(pm["unjudgeable"]) == 1 and any("probe-anomaly" in f for f in s["flags"])
              and (pm["m3"]["den"] == base["metrics"]["probe"]["m3"]["den"] - (1 if "h1" in name else 0)),
              s["status"] + json.dumps(pm["unjudgeable"], ensure_ascii=False) + f" den {pm['m3']['den']}")
    # 측정 전 재해석(2026-10-28 · 스모크) — 확정 c 404(HOLD_NOT_FOUND)는 판정 가능: δc > 0(i=7 → +1.0) = 거부 · δc < 0(i=4 → −1.0) = 실패(early_not200)
    bp = base["metrics"]["probe"]["pr3"]
    s = rep(make_rep(tmp / "r4-c404-late", judged_odd={("pr3", 7): {"c": "404"}}))
    pm = s["metrics"]["probe"]
    check("확정 c 404 · δc > 0 → 판정 불가 아님 · 만료 뒤 거부로 셈(late_n 같음 · late_200 같음)",
          "probe-incomplete" not in s["reasons"] and not pm["unjudgeable"] and pm["pr3"]["late_n"] == bp["late_n"] and pm["pr3"]["late_200"] == bp["late_200"],
          s["status"] + json.dumps(pm["pr3"], ensure_ascii=False))
    s = rep(make_rep(tmp / "r4-c404-early", judged_odd={("pr3", 4): {"c": "404"}}))
    pm = s["metrics"]["probe"]
    check("확정 c 404 · δc < 0 → 만료 전 홀드 소실 = 실패(early_not200 + 1)",
          "probe-incomplete" not in s["reasons"] and not pm["unjudgeable"] and pm["pr3"]["early_not200"] == bp["early_not200"] + 1,
          s["status"] + json.dumps(pm["pr3"], ensure_ascii=False))
    # CR14-21 — 끝 판정기 active_on_reserved_seats > 0 → 정합 위반(모든 방식 기대 0)
    for m in "ABCD":
        s = rep(make_rep(tmp / f"r3-aor-{m}", method=m, cons_set={"active_on_reserved_seats": 2}))
        check(f"CR14-21 {m} 끝 active_on_reserved_seats > 0 → 정합 위반 active_on_reserved_end",
              s["status"] == "ok" and s["violations"]["integrity"].get("active_on_reserved_end") == 2, s["status"] + json.dumps(s["violations"].get("integrity")))
    check("CR14-21 정상 회차 → active_on_reserved_end 위반 없음", "active_on_reserved_end" not in base["violations"]["integrity"], json.dumps(base["violations"]["integrity"]))


# ---- 캠페인 판정(가짜 회차 요약) ----------------------------------------------------------------------------------------------------
def fake(m, c, r, **o):
    t95 = o.get("t95", {}).get(m, 100.0) + r * 0.5
    cost = o.get("cost", {}).get(m, 10.0) + r * 0.1
    s = {"status": "ok", "reasons": [], "metrics": {"rep": r, "method": m, "cell": c,
         "state": {"T95": t95, "W": (50.0 if m == "A" else 0.0) if o.get("w") is None else o["w"], "L_A": 5.0, "c_pct": 1.0, "expired_end_window": 10},
         "probe": {"m3": {"n409": o.get("m3", {}).get(m, 10 if m == "A" else 0), "batch": o.get("m3b", {}).get(m, 0), "den": 100,
                          "unfair_d13": o.get("a_unfair", 5) if m == "A" else 0, "by_delta": {}},
                   "pr3": {"sent": o.get("pr3_sent", {}).get((m, c, r), 100), "executed": 100, "planned": 100}},
         "p99": {"cost_hold": cost, "hold_201": cost, "confirm_200": cost, "seatmap_200": cost}, "db_cpu": {"mean": o.get("cpu", {}).get(m, 100.0) + r},
         "flow": {"rehold_other": o.get("rehold", 5), "rehold_other_narrow": 2, "abandoned": 10, "revisit": {"n201": 1}},
         "app": {"kept_active": o.get("kept", {}).get(m, 3)}},
         "violations": {"integrity": dict(o.get("integ", {}).get((m, c), {})), "state": [{"row": x, "why": "selftest"} for x in o.get("state", {}).get((m, c), [])],
                        "p12": dict(o.get("p12", {}).get((m, c), {})), "p12_unknown": list(o.get("p12_unknown", {}).get((m, c), [])), "p12_checked": sorted(set(S.P12_FIXED) - set(o.get("p12_unchecked", {}).get((m, c), ()))), "p4_judge409": 0,
                        "p9_early_not200": o.get("p9", {}).get((m, c), 0), "p6": {"violated": m in o.get("p6v", "")} if m == "D" else None}}
    if o.get("none_t95") == (m, c, r):
        s["metrics"]["state"]["T95"] = None
    if (m, c, r) in o.get("none_seatmap", ()):
        s["metrics"]["p99"]["seatmap_200"] = None   # 3단에만 쓰는 지표(a50-t300 지도 p99 — G3 밖) None → 3단 판정 불가(CR14-24)
    if (m, c, r) in o.get("none_cost", ()):
        s["metrics"]["p99"]["cost_hold"] = None   # 유효 회차의 비용 지표 None → G3 판정 불가(CR14-18)
    return s


def write_campaign(root, **o):
    for c in plan14.GROUPS:
        for m in plan14.ABCD:
            for r in range(1, 4):
                d = Path(root) / c / m / f"rep{r}"
                d.mkdir(parents=True, exist_ok=True)
                st = o.get("status", {}).get((r, c, m), "ok")
                (d / "status").write_text(st + "\n")
                wj(d / "rep-summary.json", fake(m, c, r, **o))


def verdict(root):
    S.campaign(root)
    return json.loads((Path(root) / "result.json").read_text())


def campaign_rules(tmp):
    # ⑲ 2단: B의 T95가 C·D보다 비겹침으로 짧다 → C·D 빠짐 → B
    r = verdict((write_campaign(tmp / "c1", t95={"A": 140, "B": 80, "C": 100, "D": 100}) or tmp / "c1"))
    check("⑲ 2단 T95로 C·D가 빠지고 B가 1순위", r["ranked"][0] == "B" and r["stages"][1]["out"] == ["C", "D"], json.dumps(r["stages"], ensure_ascii=False))
    check("⑲ G2(a) T95 A보다 짧다 → 기본값 교체 B", r["decision"]["new_default"] == "lazy", r["decision"]["verdict"])
    # 3단: D의 비용이 B·C보다 ≫ → D 빠짐 · B·C 동률 → 4단 B
    r = verdict((write_campaign(tmp / "c2", t95={"A": 140}, cost={"A": 10, "B": 10, "C": 10, "D": 20}) or tmp / "c2"))
    check("⑲ 3단 ≫(비겹침 + 10%)로 D 빠짐 · 4단 B > C", r["stages"][2]["out"] == ["D"] and r["ranked"][0] == "B", json.dumps(r["stages"], ensure_ascii=False))
    r = verdict((write_campaign(tmp / "c2b", t95={"A": 140}, cost={"A": 10, "B": 10, "C": 10, "D": 10.5}) or tmp / "c2b"))
    check("⑲ 3단 +5%(비겹침이어도 10% 미만)는 빠지지 않는다 → 4단 D", r["ranked"][0] == "D", json.dumps(r["stages"], ensure_ascii=False))
    # G3: 1순위 D가 A보다 ≫ → G3 걸림 → 2순위 B
    r = verdict((write_campaign(tmp / "c3", t95={"A": 140}, cost={"A": 10, "B": 10, "C": 10, "D": 10}, cpu={"D": 200}) or tmp / "c3"))
    check("⑲ 1순위 D가 G3(DB CPU ≫ A)에 걸리면 다음 순위가 관문을 넘는다", r["gates"]["D"]["G3"]["hits"] and r["decision"]["new_default"] == "lazy",
          json.dumps({k: v["G3"] for k, v in r["gates"].items()}, ensure_ascii=False))
    # G2(b): T95 차이 없음 + 탐침만 → P3 유효면 이김
    r = verdict((write_campaign(tmp / "c4") or tmp / "c4"))
    check("⑲ T95 같음 · P3 유효 → G2(b)로 이김(측정-8 '주의')", r["gates"][r["ranked"][0]]["G2"]["b"] and r["decision"]["new_default"], json.dumps(r["gates"], ensure_ascii=False)[:300])
    # 순환
    beats = {("X", "Y"), ("Y", "Z"), ("Z", "X")}
    keep, gone = S.eliminate(["X", "Y", "Z"], lambda a, b: (a, b) in beats)
    check("⑲ 순환 → 순환에 든 후보만 남긴다", sorted(keep) == ["X", "Y", "Z"] and not gone)
    beats2 = {("X", "Y"), ("Y", "X"), ("X", "W"), ("Y", "W")}
    keep, gone = S.eliminate(["X", "Y", "W"], lambda a, b: (a, b) in beats2)
    check("⑲ 순환 밖에서 빠진 후보는 되살리지 않는다", sorted(keep) == ["X", "Y"] and gone == ["W"], f"{keep} {gone}")
    # ⑳ 판정 불가
    r = verdict((write_campaign(tmp / "c5", t95={"A": 140}, rehold=0) or tmp / "c5"))
    check("⑳ P7 재선점 0(3회 합) → 후보 판정 불가 · 결론 A 유지 ①(성공 후보 없음)",
          all(r["success"][m]["verdict"] == "판정 불가" for m in "BCD") and "A 유지 ①" in r["decision"]["verdict"], r["decision"]["verdict"])
    p12 = {(m, "a20-t30"): {"5xx_or_0:hold": 3} for m in "ABCD"}
    r = verdict((write_campaign(tmp / "c6", t95={"A": 140}, p12=p12) or tmp / "c6"))
    check("⑳ A도 같은 (항목, 셀)을 어김 → 판정 불가(방식 실패 아님) · 결론 '판정 불가 → 사용자 보고'",
          all(r["success"][m]["verdict"] == "판정 불가" for m in "BCD") and r["decision"]["verdict"].startswith("판정 불가"), r["decision"]["verdict"])
    p12b = {("D", "a20-t30"): {"5xx_or_0:hold": 3}}
    r = verdict((write_campaign(tmp / "c7", t95={"A": 140}, p12=p12b) or tmp / "c7"))
    check("⑳ A는 어기지 않은 항목을 D가 어김 → D 실패", r["success"]["D"]["verdict"] == "실패" and r["success"]["B"]["verdict"] == "성공", json.dumps(r["success"]["D"], ensure_ascii=False))
    st = {(2, "a50-t300", "C"): "invalid:env-stall"}
    r = verdict((write_campaign(tmp / "c8", t95={"A": 140}, status=st) or tmp / "c8"))
    check("⑳ 환경 상한 소진 자리(C) → 관문을 넘은 후보(D)가 있어도 결론 '판정 불가 → 사용자 보고'(잠정 결과 함께)",
          r["env_exhausted"] == ["rep2 a50-t300 C"] and r["decision"]["verdict"].startswith("판정 불가") and "잠정: D" in r["decision"]["verdict"]
          and r["decision"]["new_default"] is None, r["decision"]["verdict"])
    check("⑳ 환경 상한 소진 방식(C)은 성공 아님(유효 < 3 — 판정 불가)", r["success"]["C"]["verdict"] == "판정 불가", r["success"]["C"]["verdict"])
    r = verdict((write_campaign(tmp / "c9", t95={"A": 140, "B": 80, "C": 100, "D": 100}, none_t95=("B", "a20-t30", 2)) or tmp / "c9"))
    check("⑳ 유효 회차의 T95 None → 그 비교는 판정 불가(0으로 보지 않음) · range None", r["ranges"]["T95"]["B:a20-t30"] is None, str(r["ranges"]["T95"]["B:a20-t30"]))
    r = verdict((write_campaign(tmp / "c10", t95={"A": 140}, w=0.0) or tmp / "c10"))
    check("⑳ A의 TTL 30초 W = 0 → G4 실패 → A 유지 ④(판정 불가)", not r["A"]["G4"] and "④" in r["decision"]["verdict"], r["decision"]["verdict"])


def campaign_round2(tmp):
    """측정-7 성공 · 측정-8 관문의 새 분기(코드 리뷰 CR14-15 · CR14-11 · CR14-18). 기준 = A의 T95가 길어 G2(a)가 서는 캠페인."""
    r = verdict((write_campaign(tmp / "k1", t95={"A": 140}, kept={"C": 0}) or tmp / "k1"))
    check("CR14-15 P5 되돌림 기회 0(3회 합) → C 판정 불가(성공 아님) · B·D 그대로", r["success"]["C"]["verdict"] == "판정 불가"
          and any("P5: 되돌림 기회 0" in u for u in r["success"]["C"]["undecidable"]) and r["success"]["D"]["verdict"] == "성공", json.dumps(r["success"]["C"], ensure_ascii=False))
    r = verdict((write_campaign(tmp / "k2", t95={"A": 140}, p6v="D") or tmp / "k2"))
    check("CR14-15 P6 반증 → D 실패 · 결론은 다음 순위(B)", r["success"]["D"]["verdict"] == "실패" and any("P6 반증" in f for f in r["success"]["D"]["fails"])
          and r["decision"]["new_default"] == "lazy", r["decision"]["verdict"])
    r = verdict((write_campaign(tmp / "k3", t95={"A": 140}, p9={("B", "a20-t30"): 1}) or tmp / "k3"))
    check("CR14-15 P9 반증(만료 전 확정 거절) → B 실패", r["success"]["B"]["verdict"] == "실패" and any("P9 반증" in f for f in r["success"]["B"]["fails"]),
          json.dumps(r["success"]["B"], ensure_ascii=False))
    r = verdict((write_campaign(tmp / "k4", t95={"A": 140}, integ={("C", "a50-t30"): {"double_sold": 1}}) or tmp / "k4"))
    check("CR14-15 정합 위반 1건 → C 실패", r["success"]["C"]["verdict"] == "실패" and any("정합 위반" in f for f in r["success"]["C"]["fails"]),
          json.dumps(r["success"]["C"], ensure_ascii=False))
    # G2(c): T95 같음(a 아님) · A 탐침 부당 실패 0(P3 무효 → b 아님) · A에만 정합 위반 → c로 이김
    r = verdict((write_campaign(tmp / "k5", a_unfair=0, integ={("A", "a20-t300"): {"double_sold": 1}}) or tmp / "k5"))
    g = r["gates"]["D"]["G2"]
    check("CR14-15 G2(c) A에만 정합 위반 → (a)·(b) 없이 G2 통과 · 기본값 교체 D",
          g["c"] and not g["a"] and not g["b"] and r["decision"]["new_default"] == "derived" and r["A"]["integrity"], json.dumps(r["gates"]["D"], ensure_ascii=False))
    # 상태 문턱 6·7·8행 — A도 같은 셀에서 어기면 판정 불가(방식 실패 아님) · 5행(D)은 A와 견주지 않는다
    c = "a20-t30"
    r = verdict((write_campaign(tmp / "k6", t95={"A": 140}, state={("A", c): [6, 7, 8], ("B", c): [7], ("C", c): [8], ("D", c): [6]}) or tmp / "k6"))
    ok = all(r["success"][m]["verdict"] == "판정 불가" and r["success"][m]["a_rule_only"] and not r["success"][m]["fails"]
             and any(f"state:{row} {c}: A도 같은 셀" in u for u in r["success"][m]["undecidable"]) for m, row in (("B", 7), ("C", 8), ("D", 6)))
    check("CR14-15 상태 문턱 6·7·8행 'A도 같은 문턱' → B·C·D 판정 불가 · 결론 '판정 불가 → 사용자 보고'",
          ok and r["decision"]["verdict"].startswith("판정 불가"), json.dumps(r["success"], ensure_ascii=False)[:400])
    r = verdict((write_campaign(tmp / "k7", t95={"A": 140}, state={("A", c): [6], ("B", c): [6], ("C", c): [6]}) or tmp / "k7"))
    check("CR14-15 6행은 기대 0인 방식(A·D)만 문턱 — B·C의 6행은 문턱 아님(성공)", r["success"]["B"]["verdict"] == "성공" and r["success"]["C"]["verdict"] == "성공",
          json.dumps(r["success"], ensure_ascii=False)[:300])
    r = verdict((write_campaign(tmp / "k8", t95={"A": 140}, state={("A", c): [5], ("D", c): [5]}) or tmp / "k8"))
    check("CR14-15 D 5행은 A와 견주지 않는다 → A가 같은 셀에서 어겨도 D 실패", r["success"]["D"]["verdict"] == "실패"
          and any("A와 견주지 않는 칸" in f for f in r["success"]["D"]["fails"]), json.dumps(r["success"]["D"], ensure_ascii=False))
    # CR14-11 — PR-3 실행(송신된 판정 요청) < 계획인 유효 회차 → P7·P9 판정 불가(메모가 아니다)
    r = verdict((write_campaign(tmp / "k9", t95={"A": 140}, pr3_sent={("B", "a20-t30", 2): 99}) or tmp / "k9"))
    check("CR14-11 PR-3 실행 < 계획 → B 판정 불가(P7·P9) · 다른 후보 그대로", r["success"]["B"]["verdict"] == "판정 불가"
          and any("PR-3 실행" in u for u in r["success"]["B"]["undecidable"]) and r["success"]["D"]["verdict"] == "성공", json.dumps(r["success"]["B"], ensure_ascii=False))
    check("CR14-19 캠페인 메모에 P7 재선점 기회 넓은 값 · 좁힌 값", any("좁힌 값" in n and "넓은 값(판정) 60" in n for n in r["success"]["D"]["notes"]),
          json.dumps(r["success"]["D"]["notes"], ensure_ascii=False))
    # CR14-18 — G3 판정 불가
    r = verdict((write_campaign(tmp / "k10", t95={"A": 140}, none_cost={(m, "S4c4200", 1) for m in "BCD"}) or tmp / "k10"))
    v = r["decision"]["verdict"]
    check("CR14-18 G2를 넘은 후보가 모두 G3 판정 불가 → '판정 불가 → 사용자 보고'(A 유지 ③ 아님)",
          v.startswith("판정 불가") and "ⓒ" in v and not v.startswith("A 유지") and r["decision"]["new_default"] is None
          and all(r["gates"][m]["G3"]["undecidable"] and not r["gates"][m]["G3"]["hits"] for m in "BCD"), v)
    r = verdict((write_campaign(tmp / "k11", t95={"A": 140}, none_cost={("D", "S4c4200", 1)}) or tmp / "k11"))
    v = r["decision"]["verdict"]
    check("CR14-18 1순위(D) G3 판정 불가 · 2순위(B) 관문 통과 → '판정 불가 → 사용자 보고'(잠정 B)",
          r["ranked"][0] == "D" and v.startswith("판정 불가") and "잠정: B" in v and "G3 판정 불가 D" in v and r["decision"]["new_default"] is None, v)
    # C를 2단에서 빼 둔다(T95가 B·D보다 비겹침으로 김) — C가 3단에 남으면 그 None은 3단 판정 불가(CR14-24 ⓓ)로 순위 자체가 정해지지 않는다
    r = verdict((write_campaign(tmp / "k12", t95={"A": 140, "C": 120}, none_cost={("C", "S4c4200", 1)}) or tmp / "k12"))
    check("CR14-18 뒤 순위(C — 2단에서 빠짐)만 G3 판정 불가 → 1순위(D)가 그대로 교체", r["decision"]["new_default"] == "derived"
          and r["gates"]["C"]["G3"]["undecidable"] and not r["rank_undecidable"], r["decision"]["verdict"])
    r = verdict((write_campaign(tmp / "k13", t95={"A": 140}, none_cost={("C", "S4c4200", 1)}) or tmp / "k13"))
    check("CR14-24 뒤 순위(C)의 G3 지표 None이 3단에도 섞이면 순위가 정해지지 않는다 → '판정 불가 → 사용자 보고'(ⓓ · 잠정 D)",
          r["decision"]["verdict"].startswith("판정 불가") and "ⓓ" in r["decision"]["verdict"] and "잠정: D" in r["decision"]["verdict"], r["decision"]["verdict"])


def campaign_round3(tmp):
    """코드 리뷰 루프 3(CR14-24) — 3단 비용 지표 범위 None → 3단 판정 불가 · 결론 ⓓ."""
    cj = S.COST_JUDGE_CELL
    r = verdict((write_campaign(tmp / "q1", t95={"A": 140}, none_seatmap={("B", cj, 2)}) or tmp / "q1"))
    st3 = r["stages"][2]
    v = r["decision"]["verdict"]
    check("CR14-24 3단 후보 B의 지도 p99 범위 None → 3단 판정 불가 · 관문 통과 D가 있어도 '판정 불가 → 사용자 보고'(ⓓ · 잠정 D)",
          st3["undecidable"] == ["a50t300_seatmap_p99 B"] and v.startswith("판정 불가") and "ⓓ" in v and "잠정: D" in v and r["decision"]["new_default"] is None,
          json.dumps(st3, ensure_ascii=False) + " " + v)
    r = verdict((write_campaign(tmp / "q2", t95={"A": 140, "B": 80, "C": 100, "D": 100}, none_seatmap={("C", cj, 2)}) or tmp / "q2"))
    check("CR14-24 3단에 후보가 하나만 남으면(2단에서 C·D 빠짐) None이 있어도 3단 판정 불가 아님 → 교체 B",
          not r["stages"][2]["undecidable"] and r["decision"]["new_default"] == "lazy", json.dumps(r["stages"], ensure_ascii=False) + r["decision"]["verdict"])
    r = verdict((write_campaign(tmp / "q3", t95={"A": 140}, cpu={"B": 200, "C": 200}, none_seatmap={("B", cj, 2)}) or tmp / "q3"))
    check("CR14-24 3단 판정 불가여도 다른 후보(B·C)가 모두 G3에 걸리면 순위와 무관 → 교체 D",
          r["stages"][2]["undecidable"] and r["decision"]["new_default"] == "derived" and all(r["gates"][m]["G3"]["hits"] for m in "BC"),
          r["decision"]["verdict"])


# ---- 판정 입력 전수 점검 — 생성형 결측 주입(k6/ADR-014/README.md '판정 입력 출처 표'를 돌며) ------------------------------------------------
# (파일, 점 경로 | None = 파일 삭제, 기대 사유) — 값을 지우는 것(del)과 숫자 아닌 값으로 바꾸는 것(bad) 둘 다 돌린다. 기대 사유가 status에 있어야 한다(무효 — 무음 0 금지)
REP_INPUTS = [
    ("plan.json", "method", "plan-missing"), ("plan.json", "kind", "plan-missing"), ("plan.json", "mode", ("plan-missing", "expiry-mode-mismatch")), ("plan.json", "rep", "plan-missing"),
    ("plan.json", "ttl_s", "plan-missing"), ("plan.json", "window.from_s", "plan-missing"), ("plan.json", "window.len_s", "plan-missing"),
    ("meta.json", None, "meta-missing"), ("harness-reasons.txt", None, "harness-reasons-missing"),
    ("k6-summary.json", "setup_data.startedAt", "k6-summary-missing"), ("k6-window.json", "exit", "k6-window-missing"),
    ("clock-offset.json", "before.offset_s", "clock-offset-bad"), ("clock-offset.json", "after.rtt_s", "clock-offset-bad"),
    ("pre-k6.json", "seed_done_epoch", "pre-k6-mismatch"), ("app-config.json", "configprops", "cond-config-missing"),
    ("app-config.json", "app_nofile.soft", "cond-config-mismatch"), ("app-config.json", ("configprops", 2, "properties", "tomcat", "maxConnections"), "cond-config-mismatch"),
    ("warmup-status.json", "rc", "warmup-failed"),
    *[("limit-key.json", k, "limit-key-missing") for k in ("env", "cmd", "drift", "drift_cmd")], ("limit-key.json", "present", "limit-key-present"),
    *[("seed-check.json", k, "seed-mismatch") for k in ("schedules", "want_schedules", "seats", "want_seats", "available", "holds", "reservations", "quota_rows")],
    ("seed-check.json", "seat_map_index", "seat-map-index-missing"), ("seed-check.json", "flyway_v11", "seat-map-index-missing"),
    ("probe-check.json", "ok", "probe-missing"), ("logs-status.txt", None, "container-log-missing"), ("gc.log.gz", None, "gc-log-missing"),
    ("k6-requests.csv.gz", None, "k6-csv-missing"), ("timeline-server.jsonl", None, "db-cpu-missing"), ("timeline-seatstate.jsonl", None, "state-sampler-gap"),
    ("timeline-waits.jsonl", None, "waits-gap"), ("timeline-client.jsonl", None, "pc-mem-missing"),
    ("pc-link.json", None, "pc-link-missing"), *[("pc-link.json", k, "pc-link-missing") for k in ("readable", "down", "since_epoch", "until_epoch")],
    *[("consistency.json", k, "end-check-failed") for k in S.CONS_COLS], ("consistency.json", "expiry_mode", "expiry-mode-mismatch"),
    ("drift-db.json", "drift_log_total", "drift-db-failed"),
    *[(f"drift-{f}.json", k, "drift-snap-failed") for f in ("before", "after-k6", "end") for k in ("tick", "drift_record_failed", "cleanup.errors")],
    *[(f"act14-{f}.json", k, "app-metric-missing") for f in ("before", "window-end", "after-k6", "end") for k in ("rejected.rule", "tick.ok")],
    ("act14-window-end.json", "t", "app-metric-missing"),
    *[(f"table-{f}.json", "tables.product_seat.n_tup_upd", "table-stats-missing") for f in ("before", "after", "end")],
]
# P12 · P5 입력 — 회차는 유효로 두되 그 항목이 '판정 불가'(p12_unknown · None)여야 한다
P12_INPUTS = [("act14-window-end.json", "tick.failed", "tick_failed"), ("act14-window-end.json", "missing_mismatch", "missing_mismatch"),
              ("act14-before.json", "tick.failed", "tick_failed"), ("table-after.json", "deadlocks", "deadlock")]


def _mutate(d, fname, path, how):
    f = d / fname
    if path is None:
        f.unlink()
        return
    x = json.loads(f.read_text())
    cur, parts = x, (list(path) if isinstance(path, tuple) else path.split("."))   # 튜플 = 점이 든 키(revert의 'reclaim.kept_active')
    for p_ in parts[:-1]:
        cur = cur[p_]
    if how == "del":
        del cur[parts[-1]]
    else:
        cur[parts[-1]] = "x"
    f.write_text(json.dumps(x))


def generative(tmp):
    base = make_rep(tmp / "gen-base")
    s0 = rep(base)
    check("생성형: 바탕 회차 ok", s0["status"] == "ok", s0["status"])
    n_bad = 0
    for fname, path, want in REP_INPUTS:
        for how in (("del",) if path is None else ("del", "bad")):
            d = tmp / "gen" / f"{fname}-{path}-{how}".replace("/", "_")
            shutil.copytree(base, d)
            _mutate(d, fname, path, how)
            s = rep(d)
            ok = any(w in s["reasons"] for w in ((want,) if isinstance(want, str) else want)) and s["status"] != "ok"
            n_bad += not ok
            if not ok:
                check(f"생성형: {fname} {path or '파일'} {how} → {want}", False, s["status"])
    check(f"생성형: 판정 입력 {sum(1 if p is None else 2 for _, p, _ in REP_INPUTS)}건 결측·숫자 아님 주입 → 모두 무효(기대 사유)", n_bad == 0, f"어긋남 {n_bad}")
    for fname, path, key in P12_INPUTS:
        d = tmp / "gen" / f"p12-{fname}-{path}"
        shutil.copytree(base, d)
        _mutate(d, fname, path, "del")
        s = rep(d)
        check(f"생성형: {fname} {path} 없음 → P12 {key} 판정 불가(p12_unknown — 0으로 보지 않음)", key in s["violations"]["p12_unknown"], json.dumps(s["violations"]))
    for f in ("act14-before.json", "act14-end.json"):
        d = tmp / "gen" / f"p5-{f}"
        shutil.copytree(base, d)
        _mutate(d, f, ("revert", "reclaim.kept_active"), "del")
        s = rep(d)
        check(f"생성형: {f} revert.reclaim.kept_active 없음 → P5 되돌림 기회 None(`or 0` 아님)", s["metrics"]["app"]["kept_active"] is None, json.dumps(s["metrics"]["app"]))
    # jsonl 가운데 깨진 줄 → 무효(버리고 넘어가지 않는다) · 찢긴 마지막 줄 하나는 괜찮다
    for fname, want in (("timeline-seatstate.jsonl", "state-sampler-gap"), ("timeline-waits.jsonl", "waits-gap")):
        d = tmp / "gen" / f"torn-{fname}"
        shutil.copytree(base, d)
        ls = (d / fname).read_text().splitlines()
        (d / fname).write_text("\n".join(ls[:40] + ['{"t": "broken'] + ls[40:]) + "\n")
        check(f"생성형: {fname} 가운데 깨진 줄 → {want}", want in rep(d)["reasons"])
        d2 = tmp / "gen" / f"tail-{fname}"
        shutil.copytree(base, d2)
        (d2 / fname).write_text("\n".join(ls + ['{"t": "torn']) + "\n")
        check(f"생성형: {fname} 찢긴 마지막 줄 하나 → 무효 아님", rep(d2)["status"] == "ok", rep(d2)["status"])
    d = tmp / "gen" / "state-col"
    shutil.copytree(base, d)
    ls = (d / "timeline-seatstate.jsonl").read_text().splitlines()
    x = json.loads(ls[60]); del x["multi_active"]; ls[60] = json.dumps(x)
    (d / "timeline-seatstate.jsonl").write_text("\n".join(ls) + "\n")
    check("생성형: 상태 표본 한 줄의 multi_active 없음 → state-sampler-gap(그 초의 위반을 버리지 않는다)", "state-sampler-gap" in rep(d)["reasons"])
    d = tmp / "gen" / "srv-cpu"
    shutil.copytree(base, d)
    ls = (d / "timeline-server.jsonl").read_text().splitlines()
    i = next(i for i, l in enumerate(ls) if "seatlab-db-1" in l)
    ls[i] = json.dumps({"Name": "seatlab-db-1", "CPUPerc": "--", "MemUsage": "1GiB / 14GiB"})
    (d / "timeline-server.jsonl").write_text("\n".join(ls) + "\n")
    check("생성형: docker stats DB 줄 CPU 못 읽음 → db-cpu-missing(버리고 평균 내지 않는다)", "db-cpu-missing" in rep(d)["reasons"])
    # drift_log 행의 시각·action을 못 읽음 → drift-db-failed(무음 제외 금지)
    for name, row in (("at", {"at": "??", "action": "repaired"}), ("action", {"at": iso(T0 + 50), "action": "weird"})):
        d = tmp / "gen" / f"dlog-{name}"
        shutil.copytree(base, d)
        x = json.loads((d / "drift-db.json").read_text())
        x["drift_log"], x["drift_log_total"] = [row], 1
        (d / "drift-db.json").write_text(json.dumps(x))
        check(f"생성형: drift_log 행 {name} 못 읽음 → drift-db-failed", "drift-db-failed" in rep(d)["reasons"])
    # CSV 기록 행 깨짐 — 탐침 · 주 흐름
    for name, kw, want in (("탐침 δ 계획 밖", {"probe_d_override": ("pr1", 2, "9")}, "probe-incomplete"),
                           ("hold_ok exp 깨짐", {"hold_exp_bad": 3}, "k6-csv-missing"),
                           ("confirm_rec rt 없음", {"confirm_rt_drop": 4}, "k6-csv-missing")):
        s = rep(make_rep(tmp / "gen" / f"csv-{name.replace(' ', '-')}", **kw))
        check(f"생성형: CSV {name} → {want}", want in s["reasons"], s["status"])
    # Hikari 깨짐 → 정지 덩어리는 env로 가르지 않는다(방식)
    d = make_rep(tmp / "gen" / "hik-broken", stall=(100, 8))
    ls = (d / "timeline-hikari.jsonl").read_text().splitlines()
    (d / "timeline-hikari.jsonl").write_text("\n".join(ls[:5] + ["{broken"] + ls[5:]) + "\n")
    s = rep(d)
    check("생성형: Hikari 깨진 줄 → env-stall이 아니라 방식 정지(유효 · P12)", s["status"] == "ok" and (s["violations"]["p12"].get("method_stall_secs") or 0) >= 8, s["status"])
    # 대기 표본 by_src 없음 → env로 가르지 않는다
    d = make_rep(tmp / "gen" / "waits-bysrc", stall=(100, 8))
    ls = [json.loads(l) for l in (d / "timeline-waits.jsonl").read_text().splitlines()]
    for w in ls:
        if S.ts(w["t"]) - OFF - T0 >= 100 and S.ts(w["t"]) - OFF - T0 < 108:
            w.pop("by_src")
    (d / "timeline-waits.jsonl").write_text("\n".join(json.dumps(w) for w in ls) + "\n")
    s = rep(d)
    check("생성형: 정지 구간 대기 표본 by_src 없음 → env-stall 아님(방식 정지)", "env-stall" not in s["reasons"] and (s["violations"]["p12"].get("method_stall_secs") or 0) >= 8, s["status"])


def generative_campaign(tmp):
    """캠페인 판정 입력: 유효 회차 요약의 판정 열 하나를 빼면 그 회차는 유효로 세지 않는다 · 지표 None은 판정 불가로 올라간다."""
    paths = ["metrics.rep", "violations.integrity", "violations.state", "violations.p12", "violations.p12_unknown", "violations.p12_checked",
             "metrics.state.T95", "metrics.probe.m3.den", "metrics.probe.m3.batch", "metrics.probe.pr3", "metrics.flow",
             "violations.p4_judge409", "violations.p9_early_not200"]
    for i, path in enumerate(paths):
        root = tmp / f"gc-{i}"
        write_campaign(root, t95={"A": 140})
        f = root / "a20-t30" / "B" / "rep2" / "rep-summary.json"
        x = json.loads(f.read_text())
        cur, parts = x, path.split(".")
        for p_ in parts[:-1]:
            cur = cur[p_]
        del cur[parts[-1]]
        f.write_text(json.dumps(x))
        r = verdict(root)
        check(f"생성형 캠페인: B 회차 요약 {path} 없음 → 유효로 세지 않음 · B 판정 불가",
              r["success"]["B"]["verdict"] == "판정 불가" and r["summary_broken"] and r["valid_n"]["B:a20-t30"] == 2, json.dumps(r["success"]["B"], ensure_ascii=False)[:300])
    root = tmp / "gc-p6"
    write_campaign(root, t95={"A": 140})
    f = root / "a20-t30" / "D" / "rep1" / "rep-summary.json"
    x = json.loads(f.read_text()); del x["violations"]["p6"]; f.write_text(json.dumps(x))
    check("생성형 캠페인: D 회차 요약 p6 열 없음 → D 판정 불가", verdict(root)["success"]["D"]["verdict"] == "판정 불가")
    for k in S.P12_FIXED:
        c = S.COST_CELL if k == "mem_growth_ge_10pct" else "a20-t30"
        r = verdict((write_campaign(tmp / f"gc-p12-{k}", t95={"A": 140}, p12_unchecked={("B", c): {k}}) or tmp / f"gc-p12-{k}"))
        check(f"생성형 캠페인: B {c} P12 {k} 계산 기록 없음 → B 판정 불가(키 없음 = 0 아님)",
              r["success"]["B"]["verdict"] == "판정 불가" and any(f"p12:{k}" in u for u in r["success"]["B"]["undecidable"]), json.dumps(r["success"]["B"], ensure_ascii=False)[:300])
    # 비용 지표(3단 · G3) 각각 None → 결론 '판정 불가'(3단 ⓓ 또는 G3 ⓒ) — 0 · '≫ 아님'으로 넘어가지 않는다
    for k, f in (("cost_hold_p99", ("p99", "cost_hold", S.COST_CELL)), ("cost_db_cpu", ("db_cpu", "mean", S.COST_CELL)),
                 ("a50t300_hold201_p99", ("p99", "hold_201", S.COST_JUDGE_CELL)), ("a50t300_confirm_p99", ("p99", "confirm_200", S.COST_JUDGE_CELL)),
                 ("a50t300_seatmap_p99", ("p99", "seatmap_200", S.COST_JUDGE_CELL)), ("a50t300_db_cpu", ("db_cpu", "mean", S.COST_JUDGE_CELL))):
        root = tmp / f"gc-cost-{k}"
        write_campaign(root, t95={"A": 140})
        fp = root / f[2] / "B" / "rep3" / "rep-summary.json"
        x = json.loads(fp.read_text()); x["metrics"][f[0]][f[1]] = None; fp.write_text(json.dumps(x))
        r = verdict(root)
        check(f"생성형 캠페인: B 비용 지표 {k} None → 결론 판정 불가(ⓒ·ⓓ)", r["decision"]["verdict"].startswith("판정 불가") and r["decision"]["new_default"] is None,
              r["decision"]["verdict"][:200])
    # 2단 지표 None — 같은 꼴
    root = tmp / "gc-m3b"
    write_campaign(root, t95={"A": 140})
    for c in S.TTL_GROUPS["t30"]:
        fp = root / c / "B" / "rep1" / "rep-summary.json"
        x = json.loads(fp.read_text()); x["metrics"]["probe"]["m3"]["den"] = 0; fp.write_text(json.dumps(x))
    r = verdict(root)
    check("생성형 캠페인: B M3ᵦ 분모 0(범위 None) → 2단 판정 불가 · 결론 판정 불가(ⓓ)",
          r["stages"][1]["undecidable"] and r["decision"]["verdict"].startswith("판정 불가"), json.dumps(r["stages"][1], ensure_ascii=False) + r["decision"]["verdict"][:200])
    # 표본 비용 분리 여부 모름(c_pct None) — a50-t300 DB CPU가 결과를 가르면 판정 불가 · 가르지 않으면 영향 없음
    root = tmp / "gc-sep"
    write_campaign(root, t95={"A": 140}, cpu={"D": 200})
    fp = root / S.COST_JUDGE_CELL / "C" / "rep1" / "rep-summary.json"
    x = json.loads(fp.read_text()); x["metrics"]["state"]["c_pct"] = None; fp.write_text(json.dumps(x))
    r = verdict(root)
    check("생성형 캠페인: 표본 비용 분리 판정 불가 · D의 a50-t300 DB CPU ≫ A → G3 걸림이 아니라 판정 불가",
          r["sample_cost"]["undecidable"] and "a50t300_db_cpu" not in r["gates"]["D"]["G3"]["hits"]
          and any("분리 판정 불가" in u for u in r["gates"]["D"]["G3"]["undecidable"]), json.dumps(r["gates"]["D"]["G3"], ensure_ascii=False))
    # P7 재선점 기회 값 없음 · P3 탐침 값 없음
    root = tmp / "gc-p7"
    write_campaign(root, t95={"A": 140})
    fp = root / "a50-t30" / "C" / "rep2" / "rep-summary.json"
    x = json.loads(fp.read_text()); x["metrics"]["flow"]["rehold_other"] = None; fp.write_text(json.dumps(x))
    r = verdict(root)
    check("생성형 캠페인: C 재선점 기회 값 None → P7 판정 불가(0으로 더하지 않음)", r["success"]["C"]["verdict"] == "판정 불가"
          and any("P7: 재선점 기회" in u for u in r["success"]["C"]["undecidable"]), json.dumps(r["success"]["C"], ensure_ascii=False)[:300])
    root = tmp / "gc-p3"
    write_campaign(root, t95={"A": 100})   # T95 같음 → G2는 (b)뿐
    fp = root / "a20-t30" / "A" / "rep2" / "rep-summary.json"
    x = json.loads(fp.read_text()); x["metrics"]["probe"]["m3"]["unfair_d13"] = None; fp.write_text(json.dumps(x))
    r = verdict(root)
    check("생성형 캠페인: A 부당 실패 값 None(유효 회차 요약 깨짐) → 유효로 세지 않음 → G4 실패(A 유지 ④ — 판정 불가)",
          not r["A"]["G4"] and "④" in r["decision"]["verdict"], r["decision"]["verdict"])


def rej_tolerance(tmp):
    # 원인 표지 대조 허용(측정 전 재해석 2026-10-29 · 스모크 — 사용자 결정 ①): 0 ≤ 앱 − k6 ≤ 그 회차 응답 없는 선점(k6 코드 0) 수면 일치
    s = rep(make_rep(tmp / "rejtol-ok", hold_code0=2, app_rule_extra=2))
    check("결정 ① 앱 − k6 = 2 ≤ 코드 0 선점 2 → 일치(app-metric-missing 아님 · P12는 그대로 5xx_or_0)", s["status"] == "ok"
          and s["violations"]["p12"].get("5xx_or_0:hold") == 2, s["status"] + " " + str(s["flags"]))
    s = rep(make_rep(tmp / "rejtol-over", hold_code0=1, app_rule_extra=2))
    check("결정 ① 앱 − k6 = 2 > 코드 0 선점 1 → app-metric-missing", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "rejtol-neg", hold_code0=3, app_rule_extra=-1))
    check("결정 ① k6가 더 많다(앱 − k6 = −1) → app-metric-missing(코드 0이 있어도)", "app-metric-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "rejtol-none", app_rule_extra=1))
    check("결정 ① 코드 0 선점 0인데 앱 − k6 = 1 → app-metric-missing(전과 같다)", "app-metric-missing" in s["reasons"], s["status"])


def pc_link_rules(tmp):
    # PC 링크 끊김(측정 전 재해석 2026-10-29 · 스모크 — 사용자 결정 ②): 회차 시작 ~ 끝 커널 로그 'eno1: Link is Down' ≥ 1 → pc-link-down(환경) · 못 읽음 → 결측(무효)
    s = rep(make_rep(tmp / "pcl-down", pc_link_down=2))
    check("결정 ② Link is Down 2줄 → invalid:pc-link-down · 분류 환경", s["status"] == "invalid:pc-link-down" and s["env"] and S._kind(s["status"]) == "env", s["status"])
    s = rep(make_rep(tmp / "pcl-down-drop", pc_link_down=1, dropped=True))
    check("결정 ② pc-link-down + 다른 하네스 사유(dropped-iterations) → 그래도 환경(끊김의 결과)", "pc-link-down" in s["reasons"] and "dropped-iterations" in s["reasons"]
          and S._kind(s["status"]) == "env", s["status"])
    s = rep(make_rep(tmp / "pcl-unread", pc_link_readable=False))
    check("결정 ② 커널 로그 못 읽음(readable false) → pc-link-missing(하네스 — 무효 · 끊김 없음으로 넘기지 않는다)", s["status"] == "invalid:pc-link-missing"
          and S._kind(s["status"]) == "harness", s["status"])
    s = rep(make_rep(tmp / "pcl-none", no_pc_link=True))
    check("결정 ② pc-link.json 없음 → pc-link-missing", s["status"] == "invalid:pc-link-missing", s["status"])
    s = rep(make_rep(tmp / "pcl-late", pc_link_since_shift=400))
    check("결정 ② 수집 구간이 판정 창 앞을 덮지 않음 → pc-link-missing", "pc-link-missing" in s["reasons"], s["status"])
    s = rep(make_rep(tmp / "pcl-cost", cell="S4c4200", method="D", rej_rows=False, pc_link_down=1))
    check("결정 ② 비용 셀도 pc-link-down", s["status"] == "invalid:pc-link-down", s["status"])
    s = rep(make_rep(tmp / "pcl-envstall-mixed", stall=(100, 8), index=0))
    check("env-stall + 하네스 사유(pc-link-down 없음) → 하네스(원문 그대로)", S._kind(s["status"]) == "harness" and "env-stall" in s["reasons"], s["status"])
    # campaign.sh kind_of = summarize._kind(같은 규칙 — 대조)
    sts = ["ok", "", "invalid:env-stall", "invalid:pc-link-down", "invalid:dropped-iterations,pc-link-down", "invalid:pc-link-down,probe-incomplete",
           "invalid:dropped-iterations,pc-link-down,probe-incomplete", "invalid:env-stall,pc-link-down", "invalid:env-stall,gc-log-missing", "invalid:pc-link-missing",
           "invalid:gc-log-missing"]
    fn = next(l for l in (HERE / "campaign.sh").read_text().splitlines() if l.startswith("kind_of()"))
    got = subprocess.run(["bash", "-c", fn + "\nfor s in \"$@\"; do kind_of \"$s\"; done", "x", *sts], capture_output=True, text=True).stdout.split()
    want = [S._kind(x) for x in sts]
    check("결정 ② campaign.sh kind_of = summarize._kind(11가지 status)", got == want, f"{list(zip(sts, got, want))}")
    # pclink14.sh — 가짜 journalctl(PATH 앞)로 갈래 확인
    fb = tmp / "fakebin"
    fb.mkdir(exist_ok=True)
    (fb / "journalctl").write_text("""#!/bin/sh
# 가짜 journalctl — FAKE_J: down · quiet · hint · rc · nokernel / '-b'가 든 호출 = 이번 부팅 커널 줄 확인
case " $* " in *" -b "*) boot=1 ;; *) boot=0 ;; esac
case "$FAKE_J" in
  rc) echo "Failed to open journal" >&2; exit 1 ;;
  hint) echo "Hint: You are currently not seeing messages from other users and the system." >&2; echo "-- No entries --"; exit 0 ;;
  nokernel) echo "-- No entries --"; exit 0 ;;
esac
if [ "$boot" = 1 ]; then echo "2026-10-29T15:00:00+09:00 pc kernel: x"; exit 0; fi
if [ "$FAKE_J" = down ]; then
  echo "2026-10-29T15:00:00+09:00 pc kernel: r8169 0000:03:00.0 eno1: Link is Down"
  echo "2026-10-29T15:00:03+09:00 pc kernel: r8169 0000:03:00.0 eno1: Link is Up - 1Gbps/Full"
  echo "2026-10-29T15:00:05+09:00 pc kernel: r8169 0000:03:00.0 eno1: Link is Down"
else echo "-- No entries --"; fi
""")
    (fb / "journalctl").chmod(0o755)

    def pcl(mode):
        env = {**os.environ, "PATH": f"{fb}:{os.environ['PATH']}", "FAKE_J": mode}
        r = subprocess.run(["bash", str(HERE / "pclink14.sh"), "100", "200"], capture_output=True, text=True, env=env)
        try:
            return json.loads(r.stdout)
        except ValueError:
            return {"rc": r.returncode, "err": r.stderr}
    a, b, c, e, f = pcl("down"), pcl("quiet"), pcl("hint"), pcl("rc"), pcl("nokernel")
    check("pclink14: Link is Down 2줄(Up 줄 제외) → down 2 · readable", a.get("readable") is True and a.get("down") == 2, str(a))
    check("pclink14: 줄 없음 → down 0 · readable", b.get("readable") is True and b.get("down") == 0, str(b))
    check("pclink14: 권한 경고 · rc ≠ 0 · 이번 부팅 커널 줄 0 → readable false(못 읽음)",
          c.get("readable") is False and e.get("readable") is False and f.get("readable") is False, f"{c} {e} {f}")


def round3_followup(tmp):
    """㉔ 코드 리뷰 루프 3 후속 — 지적(CR14-25 · 27 · 28 · 30)마다 한 블록."""
    base = make_rep(tmp / "f-base")
    check("CR14-25·28 바탕 회차 ok · 시각 못 읽는 GC 줄 0", (s := rep(base))["status"] == "ok" and s["metrics"].get("gc_untimed_lines") == 0, s["status"])
    # CR14-25 — GC 정지·stall 줄의 시각 머리를 못 읽음 → gc-log-missing(ADR-009 gc_summary는 창 밖으로 센다)
    for name, mk in (("정지 줄 시각 머리 없음", lambda ls: ls[:3] + [ls[3].split("]", 1)[1]] + ls[4:]),
                     ("정지 줄 시각 깨짐", lambda ls: ls[:3] + ["[garbage]" + ls[3].split("]", 1)[1]] + ls[4:]),
                     ("stall 줄 시각 머리 없음", lambda ls: ls + ["[1s][info][gc,phases] Allocation Stall (http-nio-8080-exec-1) 250.000ms"])):
        d = tmp / "f" / f"gc-{name.replace(' ', '-')}"
        shutil.copytree(base, d)
        with gzip.open(d / "gc.log.gz", "rt") as f:
            ls = f.read().splitlines()
        with gzip.open(d / "gc.log.gz", "wt") as f:
            f.write("\n".join(mk(ls)) + "\n")
        s = rep(d)
        check(f"CR14-25 GC {name} → gc-log-missing(창 밖으로 무음 처리하지 않는다)", "gc-log-missing" in s["reasons"] and s["metrics"].get("gc_untimed_lines") == 1,
              s["status"] + f" untimed {s['metrics'].get('gc_untimed_lines')}")
    # CR14-28 — 서버 시각 줄 찢김
    srv = (base / "timeline-server.jsonl").read_text().splitlines()
    i = next(i for i, l in enumerate(srv) if '"load"' in l and i > 10)
    for name, ls, want_ok in (("가운데 서버 시각 줄 찢김", srv[:i] + [srv[i][:15]] + srv[i + 1:], False),
                              ("찢긴 마지막 줄 하나", srv + ['{"t": "2027'], True)):
        d = tmp / "f" / f"srv-{name.replace(' ', '-')}"
        shutil.copytree(base, d)
        (d / "timeline-server.jsonl").write_text("\n".join(ls) + "\n")
        s = rep(d)
        if want_ok:
            check(f"CR14-28 timeline-server.jsonl {name} → 무효 아님", s["status"] == "ok", s["status"])
        else:
            check(f"CR14-28 timeline-server.jsonl {name} → db-cpu-missing(버리고 앞 시각에 붙이지 않는다)",
                  "db-cpu-missing" in s["reasons"] and "깨진 줄(마지막 줄 말고 — CR14-28) 1" in s["missing_inputs"].get("server", ""), s["status"] + json.dumps(s["missing_inputs"], ensure_ascii=False))
    # CR14-27 — crn 다수결은 유효 회차끼리만
    def crn_case(name, alt, status):
        root = tmp / f"crn27-{name}"
        for m in "ABCD":
            make_rep(root, method=m, crn_alt=alt.get(m, False))
            rep(root / "a20-t30" / m / "rep1")
            (root / "a20-t30" / m / "rep1" / "status").write_text(status.get(m, "ok") + "\n")
        S.crn_check(root)
        st = {m: (root / "a20-t30" / m / "rep1" / "status").read_text().strip() for m in "ABCD"}
        return st, json.loads((root / "crn-check.json").read_text())["groups"].get("rep1:a20-t30")
    inv = "invalid:gc-log-missing"
    st, g = crn_case("two-ok", {"C": 999, "D": 999}, {"C": inv, "D": inv})
    check("CR14-27 유효 A·B 같음 · 무효 C·D(다른 해시끼리 같음) → A·B 그대로 ok · 무효 회차 status 그대로(다수결에 들지 않는다)",
          st == {"A": "ok", "B": "ok", "C": inv, "D": inv} and g["excluded_invalid"] == ["C", "D"] and g["compared"], json.dumps([st, g], ensure_ascii=False))
    st, g = crn_case("two-ok-diff", {"B": 555, "C": 999, "D": 555}, {"C": inv, "D": inv})
    check("CR14-27 유효 A·B가 서로 다름(무효 D가 B와 같아도 다수가 되지 않는다) → 과반 없음 → A·B 모두 crn-mismatch",
          st["A"] == "invalid:crn-mismatch" and st["B"] == "invalid:crn-mismatch" and st["D"] == inv, json.dumps([st, g], ensure_ascii=False))
    st, g = crn_case("one-ok", {"B": 555, "C": 999, "D": 777}, {"B": inv, "C": inv, "D": "invalid:env-stall"})
    check("CR14-27 유효 회차 하나(A)뿐 → 대조할 짝 없음 → 그대로 ok(compared false)", st["A"] == "ok" and g and not g["compared"] and g["mismatch"] == [],
          json.dumps([st, g], ensure_ascii=False))
    # CR14-30 — A의 같은 (항목, 셀) 판정 불가 → X도 판정 불가(실패로 세지 않는다)
    c = "a20-t30"
    r = verdict((write_campaign(tmp / "f30", t95={"A": 140}, p12={("B", c): {"5xx_or_0:hold": 3}}, p12_unknown={("A", c): ["5xx_or_0:hold"]}) or tmp / "f30"))
    b = r["success"]["B"]
    check("CR14-30 B가 어긴 P12 항목을 A가 같은 셀에서 판정 불가 → B 판정 불가(실패 아님) · ⓐ 갈래 · 결론 판정 불가",
          b["verdict"] == "판정 불가" and not b["fails"] and b["a_rule_only"] and any("CR14-30" in u for u in b["undecidable"])
          and r["decision"]["verdict"].startswith("판정 불가") and r["decision"]["new_default"] is None, json.dumps(b, ensure_ascii=False) + r["decision"]["verdict"])
    r = verdict((write_campaign(tmp / "f30b", t95={"A": 140}, p12={("B", c): {"5xx_or_0:hold": 3}}, p12_unknown={("A", "a50-t30"): ["5xx_or_0:hold"]}) or tmp / "f30b"))
    check("CR14-30 A의 판정 불가가 다른 셀이면 그 셀의 B 위반은 그대로 실패", r["success"]["B"]["verdict"] == "실패", json.dumps(r["success"]["B"], ensure_ascii=False))


def early_check_rules(tmp):
    """--early-check(측정-9 '캠페인 멈춤'): 회차 1 TTL 30초 두 묶음 A의 W = 0 또는 PR-1 δ ∈ {1, 3} 부당 실패 0 → exit 1."""
    def run_e(name, w=50.0, n409=3, status="ok"):
        root = tmp / f"early-{name}"
        for c in S.TTL_GROUPS["t30"]:
            d = root / c / "A" / "rep1"
            d.mkdir(parents=True, exist_ok=True)
            (d / "status").write_text(status + "\n")
            wj(d / "rep-summary.json", {"metrics": {"state": {"W": w}, "probe": {"m3": {"by_delta": {"pr1:1.0": {"n409": n409}, "pr1:20.0": {"n409": 0}}}}}})
        return S.early_check(root)
    check("CR14-15 --early-check 정상(W > 0 · 부당 실패 > 0) → 0", run_e("ok") == 0)
    check("CR14-15 --early-check A W = 0 → 1(캠페인 멈춤)", run_e("w0", w=0.0) == 1)
    check("CR14-15 --early-check A PR-1 δ1·δ3 부당 실패 0 → 1", run_e("u0", n409=0) == 1)
    check("CR14-15 --early-check 회차가 유효 아님 → 판정하지 않는다(0)", run_e("inv", w=0.0, status="invalid:gc-log-missing") == 0)
    check("전수 점검 --early-check 유효인데 W 없음 → 1(통과로 넘기지 않는다)", run_e("wnone", w=None) == 1)


# ---- campaign.sh 루프(서버 없음) ------------------------------------------------------------------------------------------------
def campaign_sim(tmp):
    repo = HERE.parent.parent.parent
    cs = HERE / "campaign.sh"
    out = tmp / "sim"

    def run_c(cid, plan_lines=None):
        sp = tmp / f"{cid}.plan"
        sp.write_text("\n".join(plan_lines or []) + "\n")
        p = subprocess.run(["bash", str(cs), "--sha", "HEAD", "--simulate", "--worktree", "--results-root", str(out), "--id", cid, "--sim-plan", str(sp)],
                           cwd=repo, capture_output=True, text=True, timeout=900)
        return p.returncode, p.stdout + p.stderr
    rc, log = run_c("all-ok")
    n = len(list((out / "all-ok").glob("*/*/rep*/status")))
    check("캠페인 루프: 60자리 모두 실행 · exit 0(stdin 차단 — run.sh가 표준 입력을 읽어도 루프가 끝나지 않는다)", rc == 0 and n == 60, f"rc={rc} n={n} {log[-400:]}")
    rc, log = run_c("skip", ["1:a50-t30:A:1=skip", "1:a50-t30:A:2=skip", "1:a50-t30:A:3=skip", "1:a50-t30:A:4=skip", "1:a50-t30:A:5=skip"])
    check("캠페인 루프: status를 끝내 못 쓴 자리 → 계획 대 실행 대조 exit 6", rc == 6 and "실행되지 않은 회차" in log, f"rc={rc} {log[-300:]}")
    lines = [f"2:a20-t30:B:{a}=invalid:env-stall" for a in (1, 2, 3)] + ["3:a20-t30:C:1=invalid:gc-log-missing", "3:a20-t30:C:2=invalid:env-stall",
                                                                         "3:a20-t30:C:3=invalid:gc-log-missing", "3:a20-t30:C:4=invalid:env-stall",
                                                                         "3:a20-t30:C:5=invalid:gc-log-missing"]
    rc, log = run_c("rerun", lines)
    b = sorted(p.name for p in (out / "rerun" / "a20-t30" / "B").glob("rep2*"))
    c = sorted(p.name for p in (out / "rerun" / "a20-t30" / "C").glob("rep3*"))
    check("재측정: env-stall만 → 환경 2번 뒤 멈춤(시도 3)", b == ["rep2", "rep2.attempt1", "rep2.attempt2"], str(b))
    check("재측정: 하네스 2번 + 환경 2번을 따로 센다(시도 5)", c == ["rep3", "rep3.attempt1", "rep3.attempt2", "rep3.attempt3", "rep3.attempt4"], str(c))
    lines = [f"2:a50-t30:D:{a}=invalid:pc-link-down,probe-incomplete" for a in (1, 2, 3)] + ["3:a50-t30:B:1=invalid:gc-log-missing", "3:a50-t30:B:2=invalid:pc-link-down",
                                                                                         "3:a50-t30:B:3=invalid:gc-log-missing", "3:a50-t30:B:4=invalid:env-stall",
                                                                                         "3:a50-t30:B:5=invalid:gc-log-missing"]
    rc, log = run_c("rerun-pcl", lines)
    dd = sorted(p.name for p in (out / "rerun-pcl" / "a50-t30" / "D").glob("rep2*"))
    bb = sorted(p.name for p in (out / "rerun-pcl" / "a50-t30" / "B").glob("rep3*"))
    check("재측정: pc-link-down(+ 다른 사유) → 환경 상한 2번 뒤 멈춤(시도 3 — 하네스 몫을 쓰지 않는다)", dd == ["rep2", "rep2.attempt1", "rep2.attempt2"], str(dd))
    check("재측정: pc-link-down · env-stall이 같은 환경 몫(2번) · 하네스 2번 따로(시도 5)", bb == ["rep3", "rep3.attempt1", "rep3.attempt2", "rep3.attempt3", "rep3.attempt4"], str(bb))
    rc2 = subprocess.run(["bash", str(cs), "--check", str(out / "all-ok")], capture_output=True, text=True).returncode
    shutil.rmtree(out / "all-ok" / "a20-t300" / "D" / "rep3")
    p = subprocess.run(["bash", str(cs), "--check", str(out / "all-ok")], capture_output=True, text=True)
    check("--check: 모두 있으면 0 · 한 자리 지우면 exit 6", rc2 == 0 and p.returncode == 6, f"{rc2} {p.returncode} {p.stderr[-200:]}")


if __name__ == "__main__":
    sys.exit(main())
