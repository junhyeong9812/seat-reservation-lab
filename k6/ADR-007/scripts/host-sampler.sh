#!/usr/bin/env bash
# ADR-007 후속(디스크 원인 C5 — docs/plans/2026-10-10/adr-007-disk-cause 명세 §2·§3·§9 task 01): 호스트 디스크 표본. **서버에서** 돈다 —
# k6 PC가 `ssh <서버> "bash -s -- <사본> <iostat 사본> <장치>…" < host-sampler.sh`로 보낸다(lib.sh host_sampler). 서버에 설치·배포할 것이 없다.
#
#   1초마다(초 경계에 맞춰) 한 줄 JSON — 원시 누적값만(비율은 Python이 차분으로 낸다: wdrop.py):
#     {"t": 서버 epoch 초(소수 6자리),
#      "disk": {"<장치>": /proc/diskstats 17열(커널 문서 iostats.rst 순서 — reads·read_merges·read_sectors·read_ms·writes·write_merges·write_sectors·write_ms·
#                        in_flight·io_ms·weighted_ms·discards·discard_merges·discard_sectors·discard_ms·flushes·flush_ms. 커널 5.5 미만이라 열이 없으면 null)},
#      "mem": {"Dirty_kB", "Writeback_kB"} (/proc/meminfo), "psi_io": {"some"|"full": {avg10, avg60, avg300, total(μs 누적)}} (/proc/pressure/io)}
#   읽기만 한다 — /proc 세 파일을 bash 내장 read로(프로세스를 띄우지 않는다). sudo 불필요(서버 6.8 커널 확인 2026-10-10).
#   교차 확인용 `iostat -x -t -y 1 <장치>…`를 함께 띄워 <iostat 사본>에 덧붙인다(stdbuf -oL — 끊길 때 버퍼에 남는 줄이 없게).
#
# 끝: 표준 출력(SSH → k6 PC) 쓰기가 실패하거나(받는 쪽이 꺼짐 — EPIPE) 부모(sshd 세션)가 사라지면 끝낸다(lib.sh db_sampler와 같은 규칙 — ADR-007 리뷰 R1).
#     끝날 때 iostat도 끈다(trap). 줄은 표준 출력보다 <사본>에 먼저 쓴다 — 흘려받기가 끊겨도 회차 끝에 사본으로 복구한다(lib.sh host_recover).
set -u
export LC_ALL=C   # EPOCHREALTIME의 소수점이 로캘을 따른다
copy="$1" io="$2"; shift 2
(( $# )) || set -- nvme1n1 dm-0
devs=" $* "
mkdir -p "$(dirname "$copy")" "$(dirname "$io")" || exit 1
parent=$PPID
ipid=""
if command -v iostat > /dev/null 2>&1; then
  echo "# host-sampler pid=$$ start=$EPOCHREALTIME devs=$*" >> "$io"
  stdbuf -oL iostat -x -t -y 1 "$@" >> "$io" 2>&1 < /dev/null &
  ipid=$!
else
  echo "# host-sampler pid=$$ iostat-not-found" >> "$io"
fi
stop_iostat() { [[ -n "$ipid" ]] && kill "$ipid" 2> /dev/null; ipid=""; }
trap 'stop_iostat; exit 0' TERM HUP INT PIPE
trap stop_iostat EXIT
while kill -0 "$parent" 2> /dev/null; do
  now=$EPOCHREALTIME
  d=""
  while read -r _maj _min name f1 f2 f3 f4 f5 f6 f7 f8 f9 f10 f11 f12 f13 f14 f15 f16 f17 _rest; do
    [[ "$devs" == *" $name "* ]] || continue
    d+="${d:+,}\"$name\":{\"reads\":$f1,\"read_merges\":$f2,\"read_sectors\":$f3,\"read_ms\":$f4,\"writes\":$f5,\"write_merges\":$f6,\"write_sectors\":$f7,\"write_ms\":$f8,\"in_flight\":$f9,\"io_ms\":$f10,\"weighted_ms\":$f11,\"discards\":${f12:-null},\"discard_merges\":${f13:-null},\"discard_sectors\":${f14:-null},\"discard_ms\":${f15:-null},\"flushes\":${f16:-null},\"flush_ms\":${f17:-null}}"
  done < /proc/diskstats
  dirty="" wb=""
  while read -r k v _u; do
    case "$k" in Dirty:) dirty=$v ;; Writeback:) wb=$v ;; esac
  done < /proc/meminfo
  psi=""
  if [[ -r /proc/pressure/io ]]; then
    while read -r kind a10 a60 a300 tot; do
      psi+="${psi:+,}\"$kind\":{\"avg10\":${a10#avg10=},\"avg60\":${a60#avg60=},\"avg300\":${a300#avg300=},\"total\":${tot#total=}}"
    done < /proc/pressure/io
  fi
  line="{\"t\":$now,\"disk\":{$d},\"mem\":{\"Dirty_kB\":${dirty:-null},\"Writeback_kB\":${wb:-null}},\"psi_io\":{$psi}}"
  printf '%s\n' "$line" >> "$copy"
  printf '%s\n' "$line" || break
  # 다음 초 경계까지 잔다(표본 시각이 초마다 고르게 — 읽기에 걸린 시간을 빼고)
  frac=${EPOCHREALTIME#*.}; us=$(( 1000000 - 10#$frac ))
  (( us < 1000 )) && us=$(( us + 1000000 ))
  printf -v s '%d.%06d' $(( us / 1000000 )) $(( us % 1000000 ))
  sleep "$s"
done
