#!/usr/bin/env python3
"""ADR-013 앱별 수집(§4.6 H4 — 내리기 직전): 그 컨테이너의 actuator 지표 **전체**(이름 목록 + 이름마다 측정값·태그) · configprops(seat.hold · spring.flyway ·
hikari · server — 비밀 키 제외) · health를 한 JSON에. 지표는 컨테이너 생애마다 누적이 0부터라 생애별 최종값으로 둔다(합은 요약기).
옛 앱과 새 앱은 지표 이름이 다르다(증분·안전망 지표는 새 앱에만) — 이름 목록 그대로 남긴다(요약기가 SHA별 이름표를 쓴다).

    appcollect.py <앱 URL(http://host:port)> <out.json>     exit 0 = 이름 목록·configprops 둘 다 받음 · 1 = 하나라도 실패(받은 것은 남긴다)"""
import json
import re
import sys
import time
import urllib.parse
import urllib.request

PREFIX = re.compile(r"^(seat\.hold(\..+)?|spring\.datasource\.hikari|spring\.flyway|server)$")


def get(url, timeout=10):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.loads(r.read().decode())


def main(base, out):
    res = {"url": base, "t0": time.time(), "errors": []}
    try:
        cp = get(f"{base}/actuator/configprops")
        res["configprops"] = [{"prefix": b.get("prefix"), "properties": {k: v for k, v in (b.get("properties") or {}).items()
                                                                          if not re.search("password|secret", k, re.I)}}
                              for c in cp.get("contexts", {}).values() for b in (c.get("beans") or {}).values()
                              if PREFIX.match(b.get("prefix") or "")]
    except Exception as e:
        res["configprops"] = None
        res["errors"].append(f"configprops: {e}")
    try:
        names = get(f"{base}/actuator/metrics").get("names") or []
        res["names"] = names
        m = {}
        for n in names:
            try:
                d = get(f"{base}/actuator/metrics/{urllib.parse.quote(n)}", timeout=5)
                m[n] = {"measurements": d.get("measurements"), "availableTags": d.get("availableTags")}
            except Exception as e:
                m[n] = None
                res["errors"].append(f"metric {n}: {e}")
        res["metrics"] = m
    except Exception as e:
        res["names"] = None
        res["errors"].append(f"metrics: {e}")
    try:
        res["health"] = get(f"{base}/actuator/health", timeout=5)
    except Exception as e:
        res["health"] = None
        res["errors"].append(f"health: {e}")
    res["t1"] = time.time()
    with open(out, "w") as f:
        json.dump(res, f, ensure_ascii=False)
    return 0 if res.get("configprops") is not None and res.get("names") is not None else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
