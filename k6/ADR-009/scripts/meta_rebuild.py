#!/usr/bin/env python3
"""회차 meta.json 복원(10-21 — ADR-012 2단계 캠페인 20261020-adr012s2-58d24e3b의 1초 증분 셀 9회차).

원인: run.sh finish_rep가 pre-k6.json(1초 셀 약 193KB)을 jq --argjson 인자 하나로 넘겼다 — 인자 하나 최대 128KB(MAX_ARG_STRLEN)를 넘어
jq가 실행되지 못했고(`인수 명단이 너무 김`) meta.json은 빈 파일, status는 ok로 남았다. 하네스는 고쳤다(finish_rep --slurpfile · meta-write-failed).
이 스크립트는 회차 폴더의 다른 산출물에서 finish_rep와 **같은 모양**(키 순서 포함)의 meta.json을 다시 만든다.

  python3 meta_rebuild.py --check <회차폴더>...   # meta.json이 있는 회차: 다시 만든 값과 실제 값을 비교(복원 규칙 검증 — ended·reconstructed 제외)
  python3 meta_rebuild.py --write <회차폴더>...   # meta.json이 비었거나 깨진 회차만 다시 쓴다(올바른 meta.json은 건드리지 않는다)
  python3 meta_rebuild.py --rewrite <회차폴더>... # 이 스크립트가 쓴 meta.json(reconstructed 있음)만 다시 쓴다 — 빈 파일 mtime이 없어지므로 ended는 MATRIX.log

출처(회차 폴더 = <캠페인>/<조건>/L<level>/<셀>/rep<n>):
  sha·harness·조건 값·k6_bin·k6_version·adr011·reconcile_shape·incremental(method·interval·inject_app) = 조건 폴더 plan.json
  started = 회차 limit-config.json mtime(초 버림 — run_rep가 started=$(date -Is) 바로 다음 줄에 쓴다. 이 캠페인 meta 있는 15회차 15/15 일치: --check)
            · 없으면 캠페인 <조건>.runner.log 'L<level> <셀> rep<n> cond=… → <회차폴더>' 줄(같은 15회차 14/15 일치 · 1회 +1초)
  ended   = 빈 meta.json mtime(초 버림 — 실패한 jq의 '> meta.json' 리다이렉트 시각 = ended=$(date -Is)와 나머지 인자 펼침 바로 뒤) · 없으면 MATRIX.log
            'L<level> <셀> rep<n> <status>' 줄 시각. 둘 다 근사(원래 값보다 0~1초 늦을 수 있다) → reconstructed.approx
  k6_started·k6_ended = k6-window.json · app_env = compose.log 마지막 'compose up' 줄(앞의 INC_BLANK_ENV 빈 값 묶음 · 뒤 '(attempt n)' 제외 = run.sh cfg_env)
  ttl_s = app_env HOLD_TTL · k6_env = run.sh k6_env_of(S4c — plan s4c_steps) · applied = app-config.json · status = status · k6_exit = status ok인 S4c 회차면 0(rep_status: S4c는 0 밖 종료가 늘 사유)
  pg_applied = pg-settings.json · clock_offset = clock-offset.json · pre_k6 = pre-k6.json · gc = gc.json(finish_rep와 같은 골라내기)
  io_settle_s·pg_env = 같은 캠페인의 다른 회차 meta.json 값(한 캠페인 프로세스 — 환경 IO_SETTLE_S 상속 · pg_env = preset_env(pg_preset) 고정 문자열)이 하나로 같을 때만
  incremental.source·safety_source·where = run.sh 규칙(inc-<app|ext>-<방식>-<주기> · safety-<app|ext>) — errors.log runs-in-window 줄로 교차 확인
복원하지 못한 값은 null로 두고 reconstructed.null_fields에 적는다.
"""
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

INC_BLANK_ENV = "INC_METHOD= INC_ENABLED= INC_INTERVAL= INC_TRACK= SAFETY_ENABLED= APP_DETECT_SHAPE= INJECT_APP="   # run.sh INC_BLANK_ENV
REASON = "jq ARG_MAX — 10-21"


def load(p):
    try:
        t = Path(p).read_text()
        return json.loads(t) if t.strip() else None
    except (OSError, ValueError):
        return None


def first_json(p):
    """finish_rep json_file_or_null + --slurpfile [0]: 비었거나 깨진 파일 = None."""
    try:
        t = Path(p).read_text()
    except OSError:
        return None
    if not t.strip():
        return None
    try:
        return json.JSONDecoder().raw_decode(t.lstrip())[0]
    except ValueError:
        return None


def gc_pick(g):
    """finish_rep gc 골라내기 — {app, reconciler}: 각 {collector, stops, stop_ms_max, nested, log_bytes, k6}(없는 키 null) · 값이 거짓이면 null."""
    if g is None:
        return None
    if not isinstance(g, dict):
        return None
    pick = lambda v: {k: v.get(k) for k in ("collector", "stops", "stop_ms_max", "nested", "log_bytes", "k6")} if v else None
    try:
        return {"app": pick(g.get("app")), "reconciler": pick(g.get("reconciler"))}
    except AttributeError:
        return None


def jq_r_lines(vals):
    """jq -r 출력을 $(…)로 받은 문자열 — 줄마다 값(null은 'null'), 끝 줄바꿈 없음."""
    return "\n".join("null" if v is None else (v if isinstance(v, str) else json.dumps(v)) for v in vals)


def siblings_value(campaign, key, where=lambda m: True):
    vals = set()
    for mp in campaign.glob("*/L*/*/rep*/meta.json"):
        m = load(mp)
        if isinstance(m, dict) and where(m) and "reconstructed" not in m:
            vals.add(json.dumps(m.get(key), sort_keys=True))
    return json.loads(vals.pop()) if len(vals) == 1 else None


def rebuild(rep):
    rep = Path(rep).resolve()
    cell_dir, level_dir, cond_dir = rep.parent, rep.parent.parent, rep.parent.parent.parent
    campaign = cond_dir.parent
    level, cell, n = int(level_dir.name[1:]), cell_dir.name, int(rep.name[3:])
    used, nulls, approx, notes = [], [], {}, []
    plan = load(cond_dir / "plan.json")
    if not isinstance(plan, dict):
        raise SystemExit(f"{rep}: plan.json 없음 — 복원 불가")
    used.append(f"{cond_dir.name}/plan.json")
    c = plan.get("condition") or {}
    # 시각
    started = ended = None
    rl = campaign / f"{cond_dir.name}.runner.log"
    pat = re.compile(rf"^\[(\d{{4}}-\d\d-\d\d \d\d:\d\d:\d\d)\] L{level} {re.escape(cell)} rep{n} cond=\S+ cleanup=\S+ → (.+)$")
    ml = cond_dir / "MATRIX.log"
    status = (rep / "status").read_text().strip() if (rep / "status").exists() else None
    if status is not None:
        used.append("status")
    tz = None
    if ml.exists():
        for line in ml.read_text().splitlines():
            mm = re.match(rf"^(\S+) L{level} {re.escape(cell)} rep{n} (\S+)$", line)
            if mm and mm.group(2) == status:
                ended = mm.group(1)
        if ended:
            tz = datetime.fromisoformat(ended).tzinfo
            used.append(f"{cond_dir.name}/MATRIX.log")
            mp = rep / "meta.json"
            if mp.exists() and mp.stat().st_size == 0:
                ended = datetime.fromtimestamp(int(mp.stat().st_mtime), tz).isoformat()
                used.append("meta.json(빈 파일 mtime)")
                approx["ended"] = "빈 meta.json mtime(초 버림) — 원래 값(jq 인자 펼침 전 date)보다 0~1초 늦을 수 있다"
            else:
                approx["ended"] = "MATRIX.log 기록 시각 — 원래 값보다 0~1초 늦을 수 있다"
    lc = rep / "limit-config.json"
    if lc.exists() and tz is not None:
        started = datetime.fromtimestamp(int(lc.stat().st_mtime), tz).isoformat()
        used.append("limit-config.json(mtime)")
    elif rl.exists() and tz is not None:
        for line in rl.read_text().splitlines():
            mm = pat.match(line)
            if mm and Path(mm.group(2).strip()).resolve() == rep:
                started = datetime.strptime(mm.group(1), "%Y-%m-%d %H:%M:%S").replace(tzinfo=tz).isoformat()
        if started:
            used.append(rl.name)
    win = load(rep / "k6-window.json") or {}
    if win:
        used.append("k6-window.json")
    # app_env = compose.log 마지막 compose up 줄
    app_env = None
    if (rep / "compose.log").exists():
        ups = [m.group(1) for m in (re.match(r"^\[[^\]]+\] compose up \(\S+\) (.*) \(attempt \d+\)$", l) for l in (rep / "compose.log").read_text().splitlines()) if m]
        if ups:
            app_env = ups[-1][len(INC_BLANK_ENV) + 1:] if ups[-1].startswith(INC_BLANK_ENV + " ") else ups[-1]
            used.append("compose.log")
    ttl = None
    if app_env:
        mt = re.search(r"(?:^| )HOLD_TTL=(\d+)(s|m|h)(?: |$)", app_env)
        ttl = int(mt.group(1)) * {"s": 1, "m": 60, "h": 3600}[mt.group(2)] if mt else None
    # applied
    ac = load(rep / "app-config.json")
    if isinstance(ac, dict):
        used.append("app-config.json")
        sh = [b for b in (ac.get("configprops") or []) if isinstance(b, dict) and b.get("prefix") == "seat.hold"]
        applied = {"strategy": jq_r_lines([(b.get("properties") or {}).get("strategy") for b in sh]),
                   "limit_strategy": jq_r_lines([(b.get("properties") or {}).get("limitStrategy") for b in sh])}
    else:
        applied = {"strategy": None, "limit_strategy": None}
        nulls += ["applied.strategy", "applied.limit_strategy"]
    k6_env = (f"START_RATE={cell[3:]} FACTOR=1 STEPS={c.get('s4c_steps')} STEP_S=30 STOP_ON_ERROR=0 RAMP_S=60 RAMP_FROM=50"
              if cell.startswith("S4c") and c.get("s4c_steps") is not None else None)
    k6_exit = 0 if (status == "ok" and cell.startswith("S4c")) else None
    cond = c.get("cond")
    files = {}
    for k, f in (("pg_applied", "pg-settings.json"), ("clock_offset", "clock-offset.json"), ("pre_k6", "pre-k6.json"), ("gc", "gc.json")):
        files[k] = first_json(rep / f)
        if files[k] is not None:
            used.append(f)
    io_settle = siblings_value(campaign, "io_settle_s")
    pg_env = siblings_value(campaign, "pg_env", lambda m: m.get("pg_preset") == c.get("pg_preset"))
    if pg_env is not None and app_env is not None and pg_env not in app_env:
        notes.append("pg_env(다른 회차 값)가 app_env에 없다 — null")
        pg_env = None
    if io_settle is not None or pg_env is not None:
        used.append("같은 캠페인 다른 회차 meta.json(io_settle_s · pg_env)")
    meta = {
        "sha": plan.get("sha"), "level": level, "cell": cell, "rep": n, "started": started, "ended": ended,
        "k6_started": win.get("k6_started"), "k6_ended": win.get("k6_ended"), "ttl_s": ttl,
        "strategy": c.get("strategy"), "pool": c.get("pool"), "apps": c.get("apps"), "delay_ms": c.get("delay_ms"), "bg": 0,
        "limit_strategy": c.get("limit_strategy"), "limit_source": "injected" if cond in ("B", "B+C") else "default",
        "injected_limit_strategy": c.get("limit_strategy") if cond in ("B", "B+C") else None, "harness": plan.get("harness"),
        "cond": cond, "cleanup": c.get("cleanup"), "reconcile_interval": c.get("reconcile_interval"),
        "applied": applied,
        "k6_env": k6_env, "app_env": app_env, "k6_exit": k6_exit, "status": status, "k6_version": c.get("k6_version"), "k6_bin": c.get("k6_bin"),
        "adr011": {"gctrace": c.get("gctrace"), "probes": c.get("probes"), "app_pretouch": c.get("app_pretouch"), "app_xms_full": c.get("app_xms_full"),
                   "s4c_steps": c.get("s4c_steps"), "seed_schedules": c.get("seed_schedules"), "pg_tmpfs_gb": c.get("pg_tmpfs_gb")},
        "io_settle_s": io_settle,
        "pg_preset": c.get("pg_preset"), "pg_env": pg_env, "pg_applied": files["pg_applied"], "clock_offset": files["clock_offset"], "pre_k6": files["pre_k6"],
        "gc": gc_pick(files["gc"]),
    }
    if c.get("reconcile_shape"):
        meta["reconcile_shape"] = c["reconcile_shape"]
    inc = c.get("incremental")
    if inc:
        if cond != "A+I":
            raise SystemExit(f"{rep}: cond {cond} — 외부 앱(A+X) 기록(external)은 복원하지 않는다")
        src = f"inc-app-{inc.get('method')}-{inc.get('interval')}"
        el = (rep / "errors.log").read_text() if (rep / "errors.log").exists() else ""
        if f"runs-in-window: {src} " not in el:
            notes.append(f"errors.log에 runs-in-window {src} 줄 없음 — source 교차 확인 실패")
        else:
            used.append("errors.log")
        meta["incremental"] = {"method": inc.get("method"), "interval": inc.get("interval"), "inject_app": inc.get("inject_app"),
                               "source": src, "safety_source": "safety-app", "where": "app"}
        if inc.get("inject_app"):
            ic = load(rep / "inc-config.json") or {}
            every = (ic.get("want") or {}).get("every")
            meta["incremental"]["inject_app_every"] = int(every) if every is not None else None
            used.append("inc-config.json")
    for k, v in meta.items():
        if v is None and k not in ("injected_limit_strategy",):
            nulls.append(k)
    for k, v in (meta.get("adr011") or {}).items():
        if v is None:
            nulls.append(f"adr011.{k}")
    return meta, {"at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"), "from": sorted(set(used), key=used.index), "reason": REASON,
                  "null_fields": sorted(set(nulls)), "approx": approx, **({"notes": notes} if notes else {})}


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ("--check", "--write", "--rewrite"):
        print(__doc__, file=sys.stderr)
        return 2
    mode, bad = sys.argv[1], 0
    for r in sys.argv[2:]:
        rep = Path(r)
        cur = load(rep / "meta.json")
        meta, rec = rebuild(rep)
        if mode == "--check":
            if not isinstance(cur, dict):
                print(f"SKIP {rep} (meta.json 없음·빈 파일)")
                continue
            diff = [k for k in sorted(set(cur) | set(meta)) if k not in ("ended", "reconstructed") and cur.get(k) != meta.get(k)]
            ed = cur.get("ended"), meta.get("ended")
            dt = (datetime.fromisoformat(ed[1]) - datetime.fromisoformat(ed[0])).total_seconds() if all(ed) else None
            print(f"{'OK  ' if not diff else 'DIFF'} {rep} ended 차 {dt}s" + (f" 다른 키 {diff}" if diff else ""))
            bad += bool(diff)
        else:
            if isinstance(cur, dict) and not (mode == "--rewrite" and "reconstructed" in cur):
                print(f"SKIP {rep} (올바른 meta.json — 건드리지 않음)")
                continue
            meta["reconstructed"] = rec
            tmp = rep / "meta.json.tmp"
            tmp.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
            json.loads(tmp.read_text())
            tmp.replace(rep / "meta.json")
            print(f"WROTE {rep} null={rec['null_fields']} approx={list(rec['approx'])}" + (f" notes={rec['notes']}" if rec.get("notes") else ""))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
