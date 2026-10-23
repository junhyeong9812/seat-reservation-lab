#!/usr/bin/env python3
"""ADR-013 한도 탐침 실행기(§4.7.1 · §5.8-1) — 회차 안에서 주 k6와 나란히 돈다(run.sh가 띄운다).

    limitprobe.py --plan <회차 plan.json> --t0 <PC epoch — 회차 기준 0초> --base-url <lb URL> --sql-cmd "<psql 명령>" --out <probe.jsonl>

k6가 아닌 이유: 탐침은 선점 사이에 SQL 주입(cnt ± 1)과 판정 직후 스냅숏(DB)이 필요하다 — k6에는 DB 접속이 없다. 가용성 탐침은 k6(scenarios/avail-probe.js)다.

탐침마다(plan.json probes — 계획 시각 at_s · 칸 · 종류 H|L · d · 쌍(회차, 사용자) · 좌석 3석) 스레드 하나:
  H: 선점 1 → SQL cnt + 1 → d초 → 선점 2(판정) → 스냅숏   맞음 = 선점 2 201 · 틀림 = 409 HOLD_LIMIT_EXCEEDED(부당 거절)
  L: 선점 1 → SQL cnt − 1 → d초 → 선점 2 → 스냅숏 → 선점 3(판정) → 스냅숏   맞음 = 선점 2 201 · 선점 3 409 HOLD_LIMIT_EXCEEDED · 틀림 = 선점 3 201(한도 초과)
  선점은 lb 경유(주 부하와 같은 경로 · 요청 시한 30초 — 주 부하 common.js와 같다) · 헤더 X-User-Id.
  주입 SQL은 RETURNING에서 DB clock_timestamp()(= inject_at — 락을 잡은 뒤 시각)를 받는다. 스냅숏은 한 문장으로 cnt · 홀드 수 · 확정 예약 수 · 홀드 행(id · held_at)을 읽는다(DS-5).
  SQL 세션 이름은 --sql-cmd가 정한다(run.sh: application_name seatlab-harness — H5).
기록(probe.jsonl — 한 줄 JSON, 사건마다 바로 덧붙인다 · 무음 유실 금지): kind = req(요청) · inject · snap · probe(탐침 끝 — 모든 원시값) · error · done(실행기 끝 — 계획 수 · 끝낸 수).
  시각: *_pc = PC epoch(요청 보낸·받은 순간) · *_db = DB 시계 epoch. 서버 시각 변환은 요약기(clock-offset.json).
  일정은 계획 시각 그대로다(실제 사건이 늦어도 옮기지 않는다 — §4.7.1). 늦게 시작한 탐침은 late_s로 남긴다."""
import argparse
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

LOCK = threading.Lock()


def emit(out, obj):
    with LOCK:
        out.write(json.dumps(obj, ensure_ascii=False) + "\n")
        out.flush()


def hold(base, schedule, seat, user, timeout):
    """→ {sent_pc, done_pc, code, body_code} — 코드 0 = 연결 실패·시한(서버 커밋 여부를 모른다)."""
    req = urllib.request.Request(f"{base}/api/schedules/{schedule}/seats/{seat}/hold", data=b"", method="POST",
                                 headers={"X-User-Id": str(user)})
    t0 = time.time()
    code, body = 0, None
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            code = r.status
            body = r.read(2000).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        code = e.code
        try:
            body = e.read(2000).decode("utf-8", "replace")
        except Exception:
            body = None
    except Exception as e:   # 시한 · 연결 실패 · 끊김
        body = f"{type(e).__name__}: {e}"[:200]
    t1 = time.time()
    bc = None
    if code == 201:
        bc = "OK"
    elif code == 409 and body:
        try:
            bc = json.loads(body).get("code")
        except (ValueError, AttributeError):
            bc = "409-NOCODE"
    return {"sent_pc": t0, "done_pc": t1, "code": code, "body_code": bc, **({"err": body} if code == 0 else {})}


def sql(cmd, text, timeout=60):
    """psql 한 번(표준 입력 SQL · -tAq) → (rc, 마지막 비지 않은 줄, stderr 앞부분)."""
    t0 = time.time()
    try:
        p = subprocess.run(cmd, shell=True, input=text, capture_output=True, text=True, timeout=timeout)
        lines = [x for x in p.stdout.splitlines() if x.strip()]
        return p.returncode, (lines[-1] if lines else ""), p.stderr[:500], t0, time.time()
    except subprocess.TimeoutExpired:
        return 124, "", "timeout", t0, time.time()


def inject_sql(s, u, delta):
    return (f"UPDATE user_hold_quota SET cnt = cnt + ({delta}) WHERE schedule_id = {s} AND user_id = {u} "
            f"RETURNING json_build_object('cnt', cnt, 'at_db', extract(epoch FROM clock_timestamp()), 'tx_db', extract(epoch FROM now()));\n")


def snap_sql(s, u):
    # DS-5: 한 문장 = 한 스냅숏. 실제 = 그 쌍의 홀드 수 + 확정 예약 수(끝 검사 v_counter_mismatch와 같은 정의)
    return ("SELECT json_build_object('t_db', extract(epoch FROM clock_timestamp()),"
            f" 'cnt', (SELECT cnt FROM user_hold_quota WHERE schedule_id = {s} AND user_id = {u}),"
            f" 'holds', (SELECT count(*) FROM seat_hold WHERE schedule_id = {s} AND user_id = {u}),"
            f" 'confirmed', (SELECT count(*) FROM reservation WHERE schedule_id = {s} AND user_id = {u} AND status = 'CONFIRMED'),"
            " 'hold_rows', (SELECT coalesce(json_agg(json_build_object('id', id, 'held_at_db', extract(epoch FROM held_at)) ORDER BY id), '[]')"
            f" FROM seat_hold WHERE schedule_id = {s} AND user_id = {u}));\n")


def run_probe(p, a, out, results):
    s, u, seats = p["schedule_id"], p["user_id"], p["seats"]
    rec = {"kind": "probe", "id": p["id"], "cell": p["cell"], "type": p["kind"], "d": p["d"], "at_s": p["at_s"],
           "schedule_id": s, "user_id": u, "requests": [], "inject": None, "snaps": [], "errors": []}
    try:
        wait = a.t0 + p["at_s"] - time.time()
        if wait > 0:
            time.sleep(wait)
        rec["late_s"] = round(max(0.0, time.time() - (a.t0 + p["at_s"])), 3)

        def req(i, name):
            r = hold(a.base_url, s, seats[i], u, a.timeout)
            r.update(name=name, seat=seats[i])
            rec["requests"].append(r)
            emit(out, {"kind": "req", "id": p["id"], **r})
            return r

        def snap(after):
            rc, line, err, t0, t1 = sql(a.sql_cmd, snap_sql(s, u))
            try:
                v = json.loads(line) if rc == 0 else None
            except ValueError:
                v = None
            if v is None:
                rec["errors"].append(f"snap-{after}: rc={rc} {err.strip()[:200]}")
            sn = {"after": after, "sent_pc": t0, "done_pc": t1, **(v or {})}
            rec["snaps"].append(sn)
            emit(out, {"kind": "snap", "id": p["id"], **sn})

        r1 = req(0, "hold1")
        if r1["code"] != 201:
            rec["errors"].append(f"hold1 {r1['code']} {r1.get('body_code')}")   # 준비 실패 — 판정 탐침이 아니다(요약기: 실패 응답)
            return
        rc, line, err, t0, t1 = sql(a.sql_cmd, inject_sql(s, u, +1 if p["kind"] == "H" else -1))
        try:
            iv = json.loads(line) if rc == 0 else None
        except ValueError:
            iv = None
        rec["inject"] = {"sent_pc": t0, "done_pc": t1, "rc": rc, **(iv or {}), **({"err": err.strip()[:300]} if iv is None else {})}
        emit(out, {"kind": "inject", "id": p["id"], **rec["inject"]})
        if iv is None:
            rec["errors"].append(f"inject rc={rc}")   # 하네스 실패(요약기 probe-short 원인 분류 — 하네스)
            return
        time.sleep(max(0.0, p["d"] - (time.time() - t1)))   # d = 주입이 끝난 뒤(락을 잡고 커밋한 뒤)부터
        if p["kind"] == "H":
            req(1, "hold2")
            snap("hold2")
        else:
            req(1, "hold2")
            snap("hold2")
            req(2, "hold3")
            snap("hold3")
    except Exception as e:   # 실행기 결함 — 숨기지 않는다
        rec["errors"].append(f"driver: {type(e).__name__}: {e}")
    finally:
        emit(out, rec)
        with LOCK:
            results.append(rec)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", required=True)
    ap.add_argument("--t0", type=float, required=True)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--sql-cmd", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--only", default="", help="쉼표로 고른 탐침 id만(스모크)")
    a = ap.parse_args()
    plan = json.load(open(a.plan))
    probes = plan["probes"]
    if a.only:
        keep = {int(x) for x in a.only.split(",")}
        probes = [p for p in probes if p["id"] in keep]
    results = []
    with open(a.out, "a") as out:
        emit(out, {"kind": "start", "planned": len(probes), "t0": a.t0, "pc": time.time()})
        th = [threading.Thread(target=run_probe, args=(p, a, out, results), daemon=True) for p in probes]
        for t in th:
            t.start()
        for t in th:
            t.join()
        emit(out, {"kind": "done", "planned": len(probes), "finished": len(results),
                   "with_errors": sum(1 for r in results if r["errors"]), "pc": time.time()})
    return 0 if len(results) == len(probes) else 1


if __name__ == "__main__":
    sys.exit(main())
