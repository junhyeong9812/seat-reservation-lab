#!/usr/bin/env python3
"""요청별 원시 기록(k6-requests.csv.gz)을 응답 코드로 나눈다 — 명세의 "타임아웃 빈도" 정의(코드 0 vs 5xx).

    scripts/errsplit.py results/<campaign-id>     → <campaign-id>/errsplit.json · requests-sha256.txt

회차마다 http_req_duration 행을 (요청 이름, S4 단계, 응답 코드 분류)로 센다.
분류: 2xx · 409 · 5xx · 기타 · 응답 코드 0은 k6 error_code로 나눈다 —
  0-1050 요청 타임아웃(30s) · 0-1211 연결 수립 실패(dial i/o timeout) · 0-1220 연결 끊김(connection reset by peer) ·
  0-1000 그 밖(EOF 등) · 0-<코드> 나머지.
  (지연으로 타임아웃을 가르면 안 된다 — k6는 타임아웃 난 요청의 http_req_duration을 0 근처로 기록한다. 실측 2026-10-02)
요청별 CSV는 커밋하지 않으므로(명세 §9.2) 파일별 sha256도 함께 남긴다 — 커밋된 요약이 어느 원본에서 나왔는지 대조용.
"""
import csv
import gzip
import hashlib
import json
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

def classify(status, error_code):
    if status == "0" or status == "":
        return f"0-{error_code or 'none'}"
    if status == "409":
        return "409"
    if status.startswith("2"):
        return "2xx"
    if status.startswith("5"):
        return "5xx"
    return "other"


def one(path):
    path = Path(path)
    sha = hashlib.sha256()
    counts = {}
    with open(path, "rb") as raw:
        for chunk in iter(lambda: raw.read(1 << 20), b""):
            sha.update(chunk)
    try:
        with gzip.open(path, "rt", newline="") as f:
            header = next(csv.reader([f.readline()]))
            i_name, i_status, i_tags, i_val = header.index("name"), header.index("status"), header.index("extra_tags"), header.index("error_code")
            for line in f:
                if not line.startswith("http_req_duration,"):
                    continue
                row = next(csv.reader([line]))
                if len(row) <= max(i_name, i_status, i_tags, i_val):
                    continue  # 잘린 마지막 행
                tags = dict(kv.split("=", 1) for kv in row[i_tags].split("&") if "=" in kv)
                key = f"{row[i_name]}|{tags.get('stage', '-')}|{classify(row[i_status], row[i_val])}"
                counts[key] = counts.get(key, 0) + 1
    except EOFError:
        counts["_truncated"] = 1  # 기록 도중 끊긴 파일(중단된 회차) — 앞부분까지만 셌다. 조용히 넘기지 않고 표시
    return str(path), sha.hexdigest(), counts


def main(root):
    root = Path(root)
    files = sorted(p for p in root.glob("c*/L*/*/rep*/k6-requests.csv.gz") if ".incomplete" not in str(p))
    files += sorted(root.glob("c*/L*/*/rep*.incomplete*/k6-requests.csv.gz"))
    out, shas = {}, []
    with ProcessPoolExecutor(max_workers=8) as ex:
        for path, digest, counts in ex.map(one, files):
            rel = str(Path(path).relative_to(root))
            out[str(Path(rel).parent)] = counts
            shas.append(f"{digest}  {rel}")
            print(f"done {rel}", flush=True)
    (root / "errsplit.json").write_text(json.dumps(out, indent=1, sort_keys=True))
    (root / "requests-sha256.txt").write_text("\n".join(shas) + "\n")
    print(f"wrote {root / 'errsplit.json'} ({len(out)} reps), {root / 'requests-sha256.txt'}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
