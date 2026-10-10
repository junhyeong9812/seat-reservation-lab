#!/usr/bin/env bash
# ADR-007 후속(디스크 원인 C5 — docs/plans/2026-10-10/adr-007-disk-cause 명세 §2·§5·§9 task 01): fio 단독 재현. k6 PC에서 실행 — 서버에서 fio 일회성 컨테이너를 돌린다.
#
#   scripts/fio-disk.sh --id <결과 id> --name <fio-N> [--size 15G] [--data-rate 35m] [--fsync-every 4096] [--wal-bs 8k] [--wal-rate 0] [--tail-s 20] [--dry-run]
#
# 부하(PostgreSQL 흉내 — 두 job 동시, 같은 그룹):
#   wal  = 8 KiB 순차 쓰기 + 매 쓰기 fdatasync(WAL 흉내 — psync). 속도 제한 없음(--wal-rate로 줄 수 있다). data job이 끝나면 함께 끝난다(exitall).
#   data = 버퍼 쓰기(순차 8 KiB — 관계 파일 확장 흉내) --data-rate(기본 35m = 35 MiB/s, ADR-007 반감 전 DB 쓰기 약 38MB/s) · --fsync-every 쓰기마다 fsync(기본 4096 = 32 MiB).
#   --size = data job 크기(기본 15G — ADR-007 반감 지점 약 9GB를 넉넉히 넘게). 총 쓰기 ≈ size + wal job이 그동안 쓴 양. fallocate=none(미리 잡지 않는다 — 파일이 쓰는 만큼 자란다).
#   초당 로그: write_bw_log·write_lat_log(log_avg_msec=1000 — job마다 <job>_bw.1.log·<job>_lat.1.log·<job>_clat.1.log·<job>_slat.1.log) + --output-format=json(fio.json).
# 컨테이너: 이미지 seatlab-fio:alpine3.20(alpine 3.20 + apk add fio — 서버에서 빌드, 있으면 재사용, 라벨 com.docker.compose.project=seatlab) ·
#   이름 seatlab-fio-<name> · 라벨 com.docker.compose.project=seatlab · --user 서버 사용자(시험 파일을 sudo 없이 지울 수 있게) ·
#   바인드 마운트는 ~/labs/seat-reservation-lab/fio-tmp/<실행 폴더> 하나뿐(명세 §2 — 시험 파일은 fio-tmp 아래에만).
# fio 동안 호스트 디스크 표본(lib.sh host_sampler — timeline-host.jsonl·iostat-host.txt)을 함께 돈다(fio 앞 대기 + fio 뒤 --tail-s초 — 쓰기 뒤 flush를 본다).
# 끝: 로그를 받아 results/<id>/<name>/ 에 두고, 서버의 실행 폴더(시험 파일 wal.dat·data.dat + 로그)를 지운다 — 지우기 전에 실제 경로(realpath)가
#   ~/labs/seat-reservation-lab/fio-tmp/ 아래인지 확인한다(그 밖은 지우지 않는다 — 명세 §4). 시험 파일은 로그 회수가 실패해도 지운다(서버 디스크).
# status(results/<id>/<name>/status): ok 또는 사유(쉼표) — fio-exit-<n> · invalid-fio.json · missing-fio-logs · host-sampler-thin(fio 구간 1초 칸 < 90%) ·
#   host-copy-not-removed · fio-files-not-removed · fio-logs-not-copied. meta.json(kind=fio)에 인자·이미지·fio 버전·시각(PC)·시계 차.
set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
set +e

ID="" NAME="" SIZE=15G DATA_RATE=35m FSYNC_EVERY=4096 WAL_BS=8k WAL_RATE=0 TAIL_S=20 DRY=0
while (( $# )); do
  case "$1" in
    --id) ID="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --size) SIZE="$2"; shift 2 ;;
    --data-rate) DATA_RATE="$2"; shift 2 ;;
    --fsync-every) FSYNC_EVERY="$2"; shift 2 ;;
    --wal-bs) WAL_BS="$2"; shift 2 ;;
    --wal-rate) WAL_RATE="$2"; shift 2 ;;
    --tail-s) TAIL_S="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$ID" && -n "$NAME" ]] || { echo "--id and --name required" >&2; exit 2; }
[[ "$NAME" =~ ^fio-[A-Za-z0-9._-]+$ ]] || { echo "--name must look like fio-<label>" >&2; exit 2; }
[[ "$SIZE" =~ ^[0-9]+[KMGkmg]$ && "$WAL_BS" =~ ^[0-9]+[kKmM]$ && "$DATA_RATE" =~ ^[0-9]+[kKmM]$ && "$WAL_RATE" =~ ^([0-9]+[kKmM]|0)$ ]] || { echo "bad size/bs/rate" >&2; exit 2; }
[[ "$FSYNC_EVERY" =~ ^[0-9]+$ && "$TAIL_S" =~ ^[0-9]+$ ]] || { echo "bad --fsync-every/--tail-s" >&2; exit 2; }
OUT="$ADR_DIR/results/$ID/$NAME"
IMAGE=seatlab-fio:alpine3.20
FIO_BASE="$REMOTE_BASE/fio-tmp"                                  # 서버 홈 기준 — 시험 파일은 이 아래에만(명세 §2)
RUN_DIR="$FIO_BASE/$(echo "$ID" | tr '/' '_')-$NAME-$(date +%Y%m%d%H%M%S)"
CNAME="seatlab-fio-$NAME"
JOB="[global]
directory=/work
ioengine=psync
fallocate=none
exitall
log_avg_msec=1000
per_job_logs=1

[wal]
rw=write
bs=$WAL_BS
fdatasync=1
size=$SIZE
filename=wal.dat
write_bw_log=wal
write_lat_log=wal
$( [[ "$WAL_RATE" != 0 ]] && echo "rate=$WAL_RATE")

[data]
rw=write
bs=8k
rate=$DATA_RATE
fsync=$FSYNC_EVERY
end_fsync=1
size=$SIZE
filename=data.dat
write_bw_log=data
write_lat_log=data
"
if (( DRY )); then
  echo "fio $NAME → $OUT (server ~/$RUN_DIR, image $IMAGE, container $CNAME)"; printf '%s\n' "$JOB"; exit 0
fi
[[ -e "$OUT" ]] && { echo "$OUT already exists — refuse to overwrite" >&2; exit 2; }
mkdir -p "$OUT"
log "fio $NAME size=$SIZE data_rate=$DATA_RATE fsync_every=$FSYNC_EVERY wal_bs=$WAL_BS wal_rate=$WAL_RATE → $OUT"
printf '%s\n' "$JOB" > "$OUT/job.fio"
: > "$OUT/errors.log"

# 서버 실행 폴더 지우기 — 실제 경로가 fio-tmp 아래(그 자체가 아닌 하위)일 때만. 인자: 'files'(시험 파일 둘만) | 'all'(실행 폴더 통째로)
remove_run() {
  remote "base=\$(realpath -e \"\$HOME/$FIO_BASE\") && [ \"\$base\" = \"\$HOME/$FIO_BASE\" ] && p=\$(realpath -e \"\$HOME/$RUN_DIR\") && case \"\$p\" in \"\$base\"/?*) ;; *) echo \"refuse: \$p\" >&2; exit 1 ;; esac && \
    if [ '$1' = all ]; then rm -rf -- \"\$p\" && test ! -e \"\$p\"; else rm -f -- \"\$p/wal.dat\" \"\$p/data.dat\" && test ! -e \"\$p/wal.dat\" && test ! -e \"\$p/data.dat\"; fi"
}
PIDS=""
cleanup() {
  [[ -n "$PIDS" ]] && { for p in $PIDS; do kids=$(pgrep -P "$p" 2>/dev/null); kill "$p" 2>/dev/null; [[ -n "$kids" ]] && kill $kids 2>/dev/null; done; PIDS=""; }
}
on_signal() {
  log "interrupted — stopping container and removing test files"
  cleanup
  remote "docker rm -f $CNAME" > /dev/null 2>&1
  [[ -n "${HOST_COPY:-}" ]] && host_sampler_kill "$HOST_COPY"
  remove_run files >> "$OUT/errors.log" 2>&1 || echo "$(date -Is) fio test files not removed on interrupt: ~/$RUN_DIR" >> "$OUT/errors.log"
  echo interrupted > "$OUT/status"; exit 130
}
trap on_signal INT TERM

reasons=()
# 1. 이미지(서버에서 빌드 — alpine + apk add fio, 네트워크 필요) · 실행 폴더(서버 사용자 소유 — 컨테이너가 같은 사용자로 쓴다)
if ! remote "docker image inspect $IMAGE > /dev/null 2>&1" ; then
  log "build $IMAGE on server"
  printf 'FROM alpine:3.20\nRUN apk add --no-cache fio\nLABEL com.docker.compose.project=seatlab\n' \
    | remote "docker build -t $IMAGE -" >> "$OUT/build.log" 2>&1 || { echo "image-build-failed" > "$OUT/status"; log "image build failed"; exit 3; }
fi
FIO_VER=$(remote "docker run --rm --label com.docker.compose.project=seatlab $IMAGE fio --version" 2>>"$OUT/errors.log" | tr -d '\r')
IMAGE_ID=$(remote "docker image inspect -f '{{.Id}}' $IMAGE" 2>>"$OUT/errors.log")
printf '%s\n' "$JOB" | remote "mkdir -p \"\$HOME/$RUN_DIR\" && cat > \"\$HOME/$RUN_DIR/job.fio\"" || { echo "run-dir-failed" > "$OUT/status"; exit 3; }

# 2. 호스트 표본(받는 쪽 bash read 루프 — run.sh와 같다)
HOST_COPY="$REMOTE_BASE/.samples/$(echo "$ID" | tr '/' '_')-$NAME-$(date +%Y%m%d%H%M%S)-$RANDOM-host.jsonl"
HOST_IOSTAT="${HOST_COPY%.jsonl}-iostat.txt"
dir="$OUT"
( while :; do
    host_sampler "$HOST_COPY" "$HOST_IOSTAT" 2>/dev/null \
      | while IFS= read -r line; do printf '%s\n' "$line" >> "$dir/timeline-host.jsonl"; done
    echo "$(date -Is) host_sampler ended — reopen in 1s" >> "$dir/errors.log"
    sleep 1
  done ) > /dev/null 2>&1 &
PIDS="$!"
for w in $(seq 1 30); do [[ -s "$OUT/timeline-host.jsonl" ]] && break; sleep 0.5; done
[[ -s "$OUT/timeline-host.jsonl" ]] || echo "$(date -Is) host-samples-before-fio timeout" >> "$OUT/errors.log"
sleep 5   # fio 앞 기준선 몇 초
CLK_BEFORE=$(clock_offset 2>>"$OUT/errors.log")

# 3. fio
FIO_STARTED=$(date -Is)
remote "docker run --rm --name $CNAME --label com.docker.compose.project=seatlab --user \$(id -u):\$(id -g) -v \"\$HOME/$RUN_DIR\":/work -w /work $IMAGE \
  fio --output-format=json --output=/work/fio.json /work/job.fio" > "$OUT/fio-stdout.log" 2>&1
FIO_EXIT=$?
FIO_ENDED=$(date -Is)
(( FIO_EXIT == 0 )) || reasons+=("fio-exit-$FIO_EXIT")
sleep "$TAIL_S"   # 쓰기 뒤 꼬리(더티 페이지 flush)
CLK_AFTER=$(clock_offset 2>>"$OUT/errors.log")
cleanup
host_sampler_kill "$HOST_COPY"
host_recover "$OUT" "$HOST_COPY" "$HOST_IOSTAT"

# 4. 로그 회수 → 시험 파일 지우기 → (회수가 됐으면) 실행 폴더 지우기
copied=0
if remote "cd \"\$HOME/$RUN_DIR\" && tar -cf - fio.json \$(ls *.log 2>/dev/null)" 2>>"$OUT/errors.log" | tar -x -C "$OUT" 2>>"$OUT/errors.log" && [[ -s "$OUT/fio.json" ]]; then
  copied=1
else
  reasons+=("fio-logs-not-copied")
fi
remove_run files >> "$OUT/errors.log" 2>&1 || reasons+=("fio-files-not-removed")
if (( copied )); then
  remove_run all >> "$OUT/errors.log" 2>&1 || reasons+=("fio-files-not-removed")
else
  echo "$(date -Is) logs not copied — run dir kept for inspection (test files removed): ~/$RUN_DIR" >> "$OUT/errors.log"
fi
[[ -s "$OUT/fio.json" ]] && python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["jobs"]' "$OUT/fio.json" 2>/dev/null || reasons+=("invalid-fio.json")
for j in wal data; do ls "$OUT/${j}_bw."*.log > /dev/null 2>&1 && ls "$OUT/${j}_clat."*.log > /dev/null 2>&1 || reasons+=("missing-fio-logs-$j"); done
jq -n --argjson b "${CLK_BEFORE:-null}" --argjson a "${CLK_AFTER:-null}" '{before:$b, after:$a}' > "$OUT/clock-offset.json" 2>/dev/null || echo '{"before":null,"after":null}' > "$OUT/clock-offset.json"
CLK_OFF=$(jq -r 'if (.before.offset_s|type)=="number" and (.after.offset_s|type)=="number" then (.before.offset_s + .after.offset_s)/2 else 0 end' "$OUT/clock-offset.json")
python3 "$(dirname "$0")/wdrop.py" --coverage "$OUT/timeline-host.jsonl" "$FIO_STARTED" "$FIO_ENDED" "$CLK_OFF" 0.9 2>>"$OUT/errors.log" || reasons+=("host-sampler-thin")
grep -qs 'host-copy-not-removed' "$OUT/host-recovery.txt" && reasons+=("host-copy-not-removed")
STATUS=ok; (( ${#reasons[@]} )) && STATUS=$(IFS=,; echo "${reasons[*]}")
echo "$STATUS" > "$OUT/status"
jq -n --arg id "$ID" --arg name "$NAME" --arg size "$SIZE" --arg rate "$DATA_RATE" --arg fe "$FSYNC_EVERY" --arg wbs "$WAL_BS" --arg wr "$WAL_RATE" --arg tail "$TAIL_S" \
  --arg image "$IMAGE" --arg image_id "$IMAGE_ID" --arg fio "$FIO_VER" --arg run_dir "~/$RUN_DIR" --arg container "$CNAME" \
  --arg fs "$FIO_STARTED" --arg fe2 "$FIO_ENDED" --argjson exit "$FIO_EXIT" --arg status "$STATUS" --arg host_devs "$HOST_DEVS" \
  --arg harness "$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null)$(git -C "$REPO_ROOT" diff --quiet HEAD -- k6/ADR-007/scripts 2>/dev/null || echo '-dirty')" \
  --argjson clock "$(jq -c . "$OUT/clock-offset.json")" \
  '{kind:"fio", id:$id, name:$name, size:$size, data_rate:$rate, fsync_every:($fe|tonumber), wal_bs:$wbs, wal_rate:$wr, tail_s:($tail|tonumber),
    image:$image, image_id:$image_id, fio_version:$fio, server_run_dir:$run_dir, container:$container, fio_started:$fs, fio_ended:$fe2,
    fio_exit:$exit, status:$status, host_devs:$host_devs, harness:$harness, clock_offset:$clock}' > "$OUT/meta.json"
log "fio $NAME done: $STATUS"
[[ "$STATUS" == ok ]]
