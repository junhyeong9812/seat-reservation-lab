#!/usr/bin/env bash
# ADR-007 후속(디스크 원인 C5 — docs/plans/2026-10-10/adr-007-disk-cause 명세 §2·§5·§9 task 01): fio 단독 재현. k6 PC에서 실행 — 서버에서 fio 일회성 컨테이너를 돌린다.
#
#   scripts/fio-disk.sh --id <결과 id> --name <fio-N> [--sha <하네스 SHA>] [--size 15G] [--data-rate 35m] [--fsync-every 4096] [--wal-bs 8k] [--wal-rate 0] [--pre-s 5] [--tail-s 20] [--dry-run]
#
# 부하(PostgreSQL 흉내 — 두 job 동시, 같은 그룹):
#   wal  = 8 KiB 순차 쓰기 + 매 쓰기 fdatasync(WAL 흉내 — psync). 속도 제한 없음(--wal-rate로 줄 수 있다). time_based(상한 24시간 — 파일은 --size 안에서 되감는다)라
#          스스로 끝나지 않는다 — **data job이 끝나면 함께 끝난다**(exitall, 리뷰 rv8 A8: 전에는 두 job 모두 size=$SIZE라 wal이 먼저 끝나면 data가 잘릴 수 있었다).
#   data = 버퍼 쓰기(순차 8 KiB — 관계 파일 확장 흉내) --data-rate(기본 35m = 35 MiB/s, ADR-007 반감 전 DB 쓰기 약 38MB/s) · --fsync-every 쓰기마다 fsync(기본 4096 = 32 MiB).
#   --size = data job 크기(기본 15G — ADR-007 반감 지점 약 9GB를 넉넉히 넘게, 1024 단위). 총 쓰기 ≈ size + wal job이 그동안 쓴 양. fallocate=none(파일이 쓰는 만큼 자란다).
#   검증: fio.json의 job별 쓴 바이트 — data < size × 0.99면 fio-data-short. job별·합계 바이트는 meta.json fio_bytes.
#   초당 로그: write_bw_log·write_lat_log(log_avg_msec=1000 — job마다 <job>_bw.1.log·<job>_lat.1.log·<job>_clat.1.log·<job>_slat.1.log) + --output-format=json(fio.json).
# 컨테이너: 이미지 seatlab-fio:alpine3.20(alpine 3.20 + apk add fio — 서버에서 빌드, 있으면 재사용, 라벨 com.docker.compose.project=seatlab) ·
#   이름 seatlab-fio-<name> · 라벨 com.docker.compose.project=seatlab · --user 서버 사용자(시험 파일을 sudo 없이 지울 수 있게) ·
#   바인드 마운트는 ~/labs/seat-reservation-lab/fio-tmp/<실행 폴더> 하나뿐(명세 §2 — 시험 파일은 fio-tmp 아래에만).
# fio 동안 호스트 디스크 표본(lib.sh host_sampler — timeline-host.jsonl·iostat-host.txt)을 함께 돈다(fio 앞 --pre-s초 + fio 뒤 --tail-s초 — 쓰기 뒤 flush를 본다).
#   표본 장치 = fio-tmp의 장치 사슬(lib.sh host_devices → host-devices.json — 리뷰 rv8 A4). 디스크 모델이 EXPECTED_DISK_MODEL이 아니면 host-device-unexpected.
# 시각: meta.json fio_started·fio_ended = fio 컨테이너 실행 직전·직후(PC 시계, 마이크로초) · script_started·script_ended = 이 스크립트 처음·끝 — 휴식 시험의 단계 간 간격(campaign.sh)이 쓴다.
# 중단(INT·TERM·HUP — 리뷰 rv8 A10): 이 실행의 컨테이너(seatlab-fio-<name>)·서버 표본 사본(이 실행의 경로만)·로그 회수를 한 번만 정리하고, 시험 파일 둘(wal.dat·data.dat)은 지운다.
#   로그 회수가 실패하면 실행 폴더는 지우지 않는다(근거 보존 — errors.log에 경로).
# 끝: 로그를 받아 results/<id>/<name>/ 에 두고, 서버의 실행 폴더(시험 파일 wal.dat·data.dat + 로그)를 지운다 — 지우기 전에 실제 경로(realpath)가
#   ~/labs/seat-reservation-lab/fio-tmp/ 아래인지 확인한다(그 밖은 지우지 않는다 — 명세 §4). 시험 파일은 로그 회수가 실패해도 지운다(서버 디스크).
# status(results/<id>/<name>/status): ok 또는 사유(쉼표) — fio-exit-<n> · invalid-fio.json · missing-fio-logs · host-sampler-thin(fio 구간 1초 칸 < 90%) ·
#   host-copy-not-removed · fio-files-not-removed · fio-logs-not-copied · fio-data-short · host-device-unexpected · invalid-host-devices.json · host-iostat-missing.
#   meta.json(kind=fio)에 인자·이미지·fio 버전·시각(PC)·시계 차·하네스(--sha가 있으면 sha:<SHA> — 캠페인이 넘긴다, 리뷰 rv8 A7 · 없으면 head:<HEAD>[-dirty]).
#
# --pattern pgwal(ADR-007 fsync 추가 시험 — docs/plans/2026-10-11/adr-007-fsync-fio 명세 §1. 기본 --pattern append = 위 동작 그대로, job 파일·meta 바이트 동일):
#   [--wal-prefill 1G] [--runtime 300] — 그리고 이 패턴에서만 기본값이 바뀐다: --wal-rate 16m · --data-rate 12m(명시하면 그 값)
#   채우기: 측정 전 별도 fio 실행(prefill.fio — wal.dat를 --wal-prefill 크기만큼 1 MiB 순차 쓰기·0으로·끝에 fsync — PG가 미리 0으로 채운 WAL 세그먼트 흉내) →
#     prefill.json · meta.json prefill_started·prefill_ended(측정 구간과 분리). 그 뒤 --pre-s초 기준선을 두고 측정 시작.
#   측정(job.fio — 두 job 모두 time_based runtime=--runtime초에 끝):
#     wal  = wal.dat **안을 덮어쓰기**(overwrite=1 · size=--wal-prefill — 끝에 닿으면 처음부터) 8 KiB 순차 + 매 쓰기 fdatasync · rate --wal-rate(16 MiB/s ≈ 2,000회/s)
#     data = append와 같은 버퍼 순차 8 KiB · rate --data-rate · fsync --fsync-every 쓰기마다 · size=--size(상한 — data.dat는 쓰는 만큼 자란다; --data-rate × --runtime 이상이어야 한다)
#   바이트 검증(시간 끝): job마다 쓴 바이트 < rate × runtime × 0.95면 fio-wal-short / fio-data-short. meta.json fio_bytes에 job별 기대값·통과 여부.
#   상태 사유 추가: fio-prefill-exit-<n> · fio-prefill-short(채운 바이트 < --wal-prefill) — 이 둘이면 측정 fio를 건너뛰고 바로 정리(리뷰 rv10 B2) ·
#     db-running(seatlab DB 컨테이너가 떠 있으면 시작하지 않음 — 단독 조건, B1) · fio-wal-file-grew(wal.dat 크기가 채운 직후·정리 직전 둘 중 하나라도 --wal-prefill과 다름, B5).
#   wal job 부족은 실패가 아니라 관찰값(명세 §2 사용자 재합의 — B7): fio_bytes.wal_ok·wal_ratio(쓴 바이트 ÷ rate × runtime)만 남기고 사유를 붙이지 않는다.
#   meta 추가: wal_file_bytes {after_prefill, before_remove} · data_file_bytes · wal_wrapped(wal 쓴 바이트 > 채운 크기 — 300초면 예상, 60초 스모크면 false).
set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
set +e

SCRIPT_STARTED=$(date +%Y-%m-%dT%H:%M:%S.%6N%:z)
ID="" NAME="" SIZE=15G DATA_RATE=35m FSYNC_EVERY=4096 WAL_BS=8k WAL_RATE=0 TAIL_S=20 PRE_S=5 DRY=0 HSHA=""
PATTERN=append WAL_PREFILL="" RUNTIME="" DATA_RATE_SET=0 WAL_RATE_SET=0   # --pattern pgwal(머리말)
while (( $# )); do
  case "$1" in
    --id) ID="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --size) SIZE="$2"; shift 2 ;;
    --data-rate) DATA_RATE="$2"; DATA_RATE_SET=1; shift 2 ;;
    --fsync-every) FSYNC_EVERY="$2"; shift 2 ;;
    --wal-bs) WAL_BS="$2"; shift 2 ;;
    --wal-rate) WAL_RATE="$2"; WAL_RATE_SET=1; shift 2 ;;
    --pattern) PATTERN="$2"; shift 2 ;;
    --wal-prefill) WAL_PREFILL="$2"; shift 2 ;;
    --runtime) RUNTIME="$2"; shift 2 ;;
    --tail-s) TAIL_S="$2"; shift 2 ;;
    --pre-s) PRE_S="$2"; shift 2 ;;
    --sha) HSHA="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$ID" && -n "$NAME" ]] || { echo "--id and --name required" >&2; exit 2; }
[[ "$NAME" =~ ^fio-[A-Za-z0-9._-]+$ ]] || { echo "--name must look like fio-<label>" >&2; exit 2; }
case "$PATTERN" in
  append) [[ -z "$WAL_PREFILL" && -z "$RUNTIME" ]] || { echo "--wal-prefill/--runtime are for --pattern pgwal only" >&2; exit 2; } ;;
  pgwal)  WAL_PREFILL="${WAL_PREFILL:-1G}" RUNTIME="${RUNTIME:-300}"
          (( WAL_RATE_SET )) || WAL_RATE=16m
          (( DATA_RATE_SET )) || DATA_RATE=12m
          [[ "$WAL_PREFILL" =~ ^[0-9]+[KMGkmg]$ && "$RUNTIME" =~ ^[0-9]+$ ]] && (( RUNTIME >= 10 && RUNTIME <= 3600 )) \
            || { echo "bad --wal-prefill (<n><K|M|G>) / --runtime (10..3600 s)" >&2; exit 2; }
          [[ "$WAL_RATE" != 0 ]] || { echo "--pattern pgwal needs --wal-rate > 0 (byte check is rate × runtime)" >&2; exit 2; } ;;
  *) echo "--pattern append|pgwal" >&2; exit 2 ;;
esac
# 속도 0(0k·0m·00m 등)은 거부한다(리뷰 rv10 B3) — 단 --wal-rate의 글자 그대로 0은 append의 '속도 제한 없음'(지금과 같다)
[[ "$SIZE" =~ ^[0-9]+[KMGkmg]$ && "$WAL_BS" =~ ^[0-9]+[kKmM]$ && "$DATA_RATE" =~ ^[1-9][0-9]*[kKmM]$ && "$WAL_RATE" =~ ^([1-9][0-9]*[kKmM]|0)$ ]] || { echo "bad size/bs/rate" >&2; exit 2; }
[[ "$FSYNC_EVERY" =~ ^[0-9]+$ && "$TAIL_S" =~ ^[0-9]+$ && "$PRE_S" =~ ^[0-9]+$ ]] || { echo "bad --fsync-every/--tail-s/--pre-s" >&2; exit 2; }
# 1024 단위 크기(K/M/G) → 바이트 (pgwal 검증용)
to_bytes() { python3 -c 'import sys; s=sys.argv[1]; print(int(s[:-1]) * 1024 ** ("KMG".index(s[-1].upper()) + 1))' "$1"; }
if [[ "$PATTERN" == pgwal ]]; then
  (( $(to_bytes "$SIZE") >= $(to_bytes "$DATA_RATE") * RUNTIME )) || { echo "--size must be >= --data-rate × --runtime for pgwal (data.dat must not wrap)" >&2; exit 2; }
  (( $(to_bytes "$WAL_PREFILL") >= $(to_bytes "$WAL_BS") )) || { echo "--wal-prefill must be >= --wal-bs" >&2; exit 2; }
fi
if [[ -n "$HSHA" ]]; then HARNESS="sha:$(git -C "$REPO_ROOT" rev-parse --short "$HSHA")" || exit 2
else HARNESS="head:$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null)$(git -C "$REPO_ROOT" diff --quiet HEAD -- k6/ADR-007/scripts 2>/dev/null || echo '-dirty')"; fi
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
time_based
runtime=86400
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
if [[ "$PATTERN" == pgwal ]]; then
JOB="[global]
directory=/work
ioengine=psync
fallocate=none
time_based
runtime=$RUNTIME
log_avg_msec=1000
per_job_logs=1

[wal]
rw=write
bs=$WAL_BS
fdatasync=1
overwrite=1
rate=$WAL_RATE
size=$WAL_PREFILL
filename=wal.dat
write_bw_log=wal
write_lat_log=wal

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
PREFILL_JOB="[prefill]
directory=/work
ioengine=psync
fallocate=none
rw=write
bs=1m
zero_buffers
size=$WAL_PREFILL
end_fsync=1
filename=wal.dat
"
fi
if (( DRY )); then
  echo "fio $NAME → $OUT (server ~/$RUN_DIR, image $IMAGE, container $CNAME)"; printf '%s\n' "$JOB"
  [[ "$PATTERN" == pgwal ]] && { echo "# prefill.fio (측정 전 별도 실행)"; printf '%s\n' "$PREFILL_JOB"; }
  exit 0
fi
[[ -e "$OUT" ]] && { echo "$OUT already exists — refuse to overwrite" >&2; exit 2; }
mkdir -p "$OUT"
log "fio $NAME size=$SIZE data_rate=$DATA_RATE fsync_every=$FSYNC_EVERY wal_bs=$WAL_BS wal_rate=$WAL_RATE → $OUT"
printf '%s\n' "$JOB" > "$OUT/job.fio"
[[ "$PATTERN" == pgwal ]] && printf '%s\n' "$PREFILL_JOB" > "$OUT/prefill.fio"
: > "$OUT/errors.log"
# pgwal 단독 조건(리뷰 rv10 B1): seatlab DB 컨테이너가 떠 있으면(또는 확인 실패) 시작하지 않는다
if [[ "$PATTERN" == pgwal ]]; then
  dbup=$(remote "docker ps -q --filter label=com.docker.compose.project=seatlab --filter label=com.docker.compose.service=db" 2>>"$OUT/errors.log"); dbrc=$?
  if (( dbrc != 0 )) || [[ -n "$dbup" ]]; then
    echo "$(date -Is) db-running check: rc=$dbrc containers=[$dbup] — fio not started" >> "$OUT/errors.log"; echo db-running > "$OUT/status"; log "seatlab db running (or check failed) — refuse"; exit 3
  fi
fi

# 서버 실행 폴더 지우기 — 실제 경로가 fio-tmp 아래(그 자체가 아닌 하위)일 때만. 인자: 'files'(시험 파일 둘만) | 'all'(실행 폴더 통째로)
remove_run() {
  remote "base=\$(realpath -e \"\$HOME/$FIO_BASE\") && [ \"\$base\" = \"\$HOME/$FIO_BASE\" ] && p=\$(realpath -e \"\$HOME/$RUN_DIR\") && case \"\$p\" in \"\$base\"/?*) ;; *) echo \"refuse: \$p\" >&2; exit 1 ;; esac && \
    if [ '$1' = all ]; then rm -rf -- \"\$p\" && test ! -e \"\$p\"; else rm -f -- \"\$p/wal.dat\" \"\$p/data.dat\" && test ! -e \"\$p/wal.dat\" && test ! -e \"\$p/data.dat\"; fi"
}
PIDS="" HOST_COPY="" HOST_IOSTAT="" RUN_MADE=0 TORN=0 copied=0 FIO_PID="" PREFILL_FAILED=0 WAL_SZ_PREFILL=null WAL_SZ_END=null DATA_SZ_END=null
reasons=()
# 정리(정상 끝·중단 공용, 한 번만 — 리뷰 rv8 A10): ① 로컬 표본 루프 ② 이 실행의 컨테이너 ③ 이 실행의 서버 표본 사본(받고 지움 — host_recover)
#   ④ 로그 회수 ⑤ 시험 파일 둘 지움 ⑥ 회수가 됐을 때만 실행 폴더 지움(아니면 근거로 남기고 errors.log에 경로)
teardown() {
  (( TORN )) && return 0; TORN=1
  local p kids
  for p in $PIDS; do kids=$(pgrep -P "$p" 2>/dev/null); kill "$p" 2>/dev/null; [[ -n "$kids" ]] && kill $kids 2>/dev/null; done; PIDS=""
  remote "docker rm -f $CNAME" > /dev/null 2>&1   # --rm이라 정상 끝이면 이미 없다
  [[ -n "$FIO_PID" ]] && kill "$FIO_PID" 2>/dev/null   # 컨테이너를 지우면 ssh도 끝나지만 남지 않게
  if [[ -n "$HOST_COPY" ]]; then host_sampler_kill "$HOST_COPY"; host_recover "$OUT" "$HOST_COPY" "$HOST_IOSTAT"; fi
  (( RUN_MADE )) || return 0
  if remote "cd \"\$HOME/$RUN_DIR\" && tar -cf - \$(ls fio.json prefill.json *.log 2>/dev/null)" 2>>"$OUT/errors.log" | tar -x -C "$OUT" 2>>"$OUT/errors.log" \
     && { [[ -s "$OUT/fio.json" ]] || { (( PREFILL_FAILED )) && [[ -s "$OUT/prefill.json" ]]; }; }; then
    copied=1
  else
    reasons+=("fio-logs-not-copied")
  fi
  # pgwal: 지우기 직전 파일 크기(리뷰 rv10 B5 — wal.dat는 덮어쓰기라 채운 크기 그대로여야 한다)
  if [[ "$PATTERN" == pgwal ]]; then
    read -r WAL_SZ_END DATA_SZ_END < <(remote "cd \"\$HOME/$RUN_DIR\" && echo \$(stat -c %s wal.dat 2>/dev/null || echo null) \$(stat -c %s data.dat 2>/dev/null || echo null)" 2>>"$OUT/errors.log")
    [[ "$WAL_SZ_END" =~ ^[0-9]+$ ]] || WAL_SZ_END=null; [[ "$DATA_SZ_END" =~ ^[0-9]+$ ]] || DATA_SZ_END=null
  fi
  remove_run files >> "$OUT/errors.log" 2>&1 || reasons+=("fio-files-not-removed")
  if (( copied )); then
    remove_run all >> "$OUT/errors.log" 2>&1 || reasons+=("fio-files-not-removed")
  else
    echo "$(date -Is) logs not copied — run dir kept as evidence (test files removed): ~/$RUN_DIR" >> "$OUT/errors.log"
  fi
}
on_signal() {
  log "interrupted — teardown (container, this run's sampler copies, logs, test files)"
  trap '' INT TERM HUP
  teardown
  echo "interrupted${reasons[*]:+,$(IFS=,; echo "${reasons[*]}")}" > "$OUT/status"; exit 130
}
trap on_signal INT TERM HUP

# 1. 이미지(서버에서 빌드 — alpine + apk add fio, 네트워크 필요) · 실행 폴더(서버 사용자 소유 — 컨테이너가 같은 사용자로 쓴다)
if ! remote "docker image inspect $IMAGE > /dev/null 2>&1" ; then
  log "build $IMAGE on server"
  printf 'FROM alpine:3.20\nRUN apk add --no-cache fio\nLABEL com.docker.compose.project=seatlab\n' \
    | remote "docker build -t $IMAGE -" >> "$OUT/build.log" 2>&1 || { echo "image-build-failed" > "$OUT/status"; log "image build failed"; exit 3; }
fi
FIO_VER=$(remote "docker run --rm --label com.docker.compose.project=seatlab $IMAGE fio --version" 2>>"$OUT/errors.log" | tr -d '\r')
IMAGE_ID=$(remote "docker image inspect -f '{{.Id}}' $IMAGE" 2>>"$OUT/errors.log")
printf '%s\n' "$JOB" | remote "mkdir -p \"\$HOME/$RUN_DIR\" && cat > \"\$HOME/$RUN_DIR/job.fio\"" || { echo "run-dir-failed" > "$OUT/status"; exit 3; }
if [[ "$PATTERN" == pgwal ]]; then
  printf '%s\n' "$PREFILL_JOB" | remote "cat > \"\$HOME/$RUN_DIR/prefill.fio\"" || { echo "run-dir-failed" > "$OUT/status"; exit 3; }
fi
RUN_MADE=1

# 2. 표본 장치(fio-tmp의 장치 사슬) · 호스트 표본(받는 쪽 bash read 루프 — run.sh와 같다)
if host_devices "$FIO_BASE" > "$OUT/host-devices.json" 2>>"$OUT/errors.log" && jq -e '.sampled | length > 0' "$OUT/host-devices.json" > /dev/null 2>&1; then
  HOST_DEVS="$(jq -r '.sampled | join(" ")' "$OUT/host-devices.json")"
else
  echo "$(date -Is) host_devices failed — sampler uses default devices: $HOST_DEVS" >> "$OUT/errors.log"
fi
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
# pgwal: 채우기(측정 구간 밖 — 시각을 따로 남긴다). 그 뒤 기준선
PREFILL_STARTED="" PREFILL_ENDED="" PREFILL_EXIT=null
if [[ "$PATTERN" == pgwal ]]; then
  PREFILL_STARTED=$(date +%Y-%m-%dT%H:%M:%S.%6N%:z)
  remote "docker run --rm --name $CNAME --label com.docker.compose.project=seatlab --user \$(id -u):\$(id -g) -v \"\$HOME/$RUN_DIR\":/work -w /work $IMAGE \
    fio --output-format=json --output=/work/prefill.json /work/prefill.fio" > "$OUT/prefill-stdout.log" 2>&1 &
  FIO_PID=$!
  wait "$FIO_PID"
  PREFILL_EXIT=$?
  PREFILL_ENDED=$(date +%Y-%m-%dT%H:%M:%S.%6N%:z)
  if (( PREFILL_EXIT != 0 )); then
    reasons+=("fio-prefill-exit-$PREFILL_EXIT"); PREFILL_FAILED=1
  else
    # 채운 바이트(prefill.json — 서버에서 바로 읽는다)와 채운 직후 wal.dat 크기(B5). 모자라면 측정을 건너뛴다(B2)
    pf=$(remote "cat \"\$HOME/$RUN_DIR/prefill.json\"" 2>>"$OUT/errors.log" | python3 -c 'import json,sys; print(sum(j["write"]["io_bytes"] for j in json.load(sys.stdin)["jobs"]))' 2>>"$OUT/errors.log")
    WAL_SZ_PREFILL=$(remote "stat -c %s \"\$HOME/$RUN_DIR/wal.dat\"" 2>>"$OUT/errors.log"); [[ "$WAL_SZ_PREFILL" =~ ^[0-9]+$ ]] || WAL_SZ_PREFILL=null
    if ! [[ "$pf" =~ ^[0-9]+$ ]] || (( pf < $(to_bytes "$WAL_PREFILL") )); then
      echo "$(date -Is) prefill bytes ${pf:-unreadable} < $(to_bytes "$WAL_PREFILL") — measured fio skipped" >> "$OUT/errors.log"
      reasons+=("fio-prefill-short"); PREFILL_FAILED=1
    fi
  fi
fi
FIO_STARTED="" FIO_ENDED="" FIO_EXIT=null
if (( ! PREFILL_FAILED )); then
sleep "$PRE_S" & wait $!   # fio 앞 기준선
CLK_BEFORE=$(clock_offset 2>>"$OUT/errors.log")

# 3. fio
FIO_STARTED=$(date +%Y-%m-%dT%H:%M:%S.%6N%:z)
# 배경 실행 + wait — bash는 앞쪽(foreground) 자식이 끝날 때까지 trap을 미룬다(중단 시험 실측: TERM 뒤 fio가 끝까지 돌았다). wait는 신호에 바로 깨어난다
remote "docker run --rm --name $CNAME --label com.docker.compose.project=seatlab --user \$(id -u):\$(id -g) -v \"\$HOME/$RUN_DIR\":/work -w /work $IMAGE \
  fio --output-format=json --output=/work/fio.json /work/job.fio" > "$OUT/fio-stdout.log" 2>&1 &
FIO_PID=$!
wait "$FIO_PID"
FIO_EXIT=$?
FIO_ENDED=$(date +%Y-%m-%dT%H:%M:%S.%6N%:z)
(( FIO_EXIT == 0 )) || reasons+=("fio-exit-$FIO_EXIT")
sleep "$TAIL_S" & wait $!   # 쓰기 뒤 꼬리(더티 페이지 flush) — 신호에 바로 깨게
fi
CLK_AFTER=$(clock_offset 2>>"$OUT/errors.log")
trap '' INT TERM HUP   # 정리 중에는 끊지 않는다(정리는 한 번만)
teardown

# 4. 판정
if [[ "$PATTERN" == append ]]; then
FIO_BYTES=$(python3 - "$OUT/fio.json" "$SIZE" <<'PY' 2>>"$OUT/errors.log"
import json, sys
d = json.load(open(sys.argv[1])); n, u = int(sys.argv[2][:-1]), sys.argv[2][-1].upper()
want = n * 1024 ** "KMG".index(u) * 1024
b = {j["jobname"]: j["write"]["io_bytes"] for j in d["jobs"]}
print(json.dumps({"wal": b.get("wal"), "data": b.get("data"), "total": sum(v for v in b.values() if v), "data_expected": want,
                  "data_ok": bool(b.get("data") and b["data"] >= 0.99 * want)}))
PY
)
if ! jq -e . <<< "$FIO_BYTES" > /dev/null 2>&1; then reasons+=("invalid-fio.json"); FIO_BYTES=null
elif ! jq -e '.data_ok' <<< "$FIO_BYTES" > /dev/null; then reasons+=("fio-data-short"); fi
elif (( PREFILL_FAILED )); then
FIO_BYTES=null   # 측정을 건너뜀 — 사유는 위(fio-prefill-*)
else
# pgwal(머리말): 시간 끝이라 기준 = rate × runtime × 0.95(job마다) · 채우기 = --wal-prefill 이상
FIO_BYTES=$(python3 - "$OUT/fio.json" "$OUT/prefill.json" "$WAL_RATE" "$DATA_RATE" "$RUNTIME" "$WAL_PREFILL" <<'PY' 2>>"$OUT/errors.log"
import json, sys
B = lambda s: int(s[:-1]) * 1024 ** ("KMG".index(s[-1].upper()) + 1)
d = json.load(open(sys.argv[1])); rt = int(sys.argv[5])
b = {j["jobname"]: j["write"]["io_bytes"] for j in d["jobs"]}
we, de = B(sys.argv[3]) * rt * 0.95, B(sys.argv[4]) * rt * 0.95
try:
    p = sum(j["write"]["io_bytes"] for j in json.load(open(sys.argv[2]))["jobs"])
except Exception:
    p = None
print(json.dumps({"wal": b.get("wal"), "data": b.get("data"), "total": sum(v for v in b.values() if v),
                  "wal_expected_min": int(we), "data_expected_min": int(de),
                  "wal_ok": bool(b.get("wal") and b["wal"] >= we), "data_ok": bool(b.get("data") and b["data"] >= de),
                  "wal_ratio": round(b["wal"] / (we / 0.95), 4) if b.get("wal") else None,
                  "wal_wrapped": bool(b.get("wal") and b["wal"] > B(sys.argv[6])),
                  "prefill": p, "prefill_expected": B(sys.argv[6]), "prefill_ok": bool(p is not None and p >= B(sys.argv[6]))}))
PY
)
if ! jq -e . <<< "$FIO_BYTES" > /dev/null 2>&1; then reasons+=("invalid-fio.json"); FIO_BYTES=null
else
  # wal 부족은 관찰값(B7 — 사유 없음, fio_bytes.wal_ok·wal_ratio)
  jq -e '.data_ok' <<< "$FIO_BYTES" > /dev/null || reasons+=("fio-data-short")
  jq -e '.prefill_ok' <<< "$FIO_BYTES" > /dev/null || reasons+=("fio-prefill-short")
fi
fi
if (( ! PREFILL_FAILED )); then
for j in wal data; do ls "$OUT/${j}_bw."*.log > /dev/null 2>&1 && ls "$OUT/${j}_clat."*.log > /dev/null 2>&1 || reasons+=("missing-fio-logs-$j"); done
fi
# B5: wal.dat 크기 = 채운 크기(채운 직후·정리 직전 — 측정을 건너뛰었으면 채운 직후만)
if [[ "$PATTERN" == pgwal && "$WAL_SZ_PREFILL" != null ]]; then
  want_wal=$(to_bytes "$WAL_PREFILL")
  if [[ "$WAL_SZ_PREFILL" != "$want_wal" ]] || { (( ! PREFILL_FAILED )) && [[ "$WAL_SZ_END" != "$want_wal" ]]; }; then reasons+=("fio-wal-file-grew"); fi
fi
jq -n --argjson b "${CLK_BEFORE:-null}" --argjson a "${CLK_AFTER:-null}" '{before:$b, after:$a}' > "$OUT/clock-offset.json" 2>/dev/null || echo '{"before":null,"after":null}' > "$OUT/clock-offset.json"
CLK_OFF=$(jq -r 'if (.before.offset_s|type)=="number" and (.after.offset_s|type)=="number" then (.before.offset_s + .after.offset_s)/2 else 0 end' "$OUT/clock-offset.json")
HDISK=""
if jq -e '(.disk | type == "string") and (.sampled | type == "array")' "$OUT/host-devices.json" > /dev/null 2>&1; then
  HDISK="$(jq -r .disk "$OUT/host-devices.json")"
  jq -e --arg m "$EXPECTED_DISK_MODEL" '.model == $m and (.disk as $d | .sampled | any(. == $d))' "$OUT/host-devices.json" > /dev/null 2>&1 || reasons+=("host-device-unexpected")
else
  reasons+=("invalid-host-devices.json")
fi
if (( ! PREFILL_FAILED )); then
python3 "$(dirname "$0")/wdrop.py" --coverage "$OUT/timeline-host.jsonl" "$FIO_STARTED" "$FIO_ENDED" "$CLK_OFF" 0.9 "${HDISK:-nvme1n1}" 2>>"$OUT/errors.log" || reasons+=("host-sampler-thin")
fi
grep -qs "^${HDISK:-nvme1n1} " "$OUT/iostat-host.txt" || reasons+=("host-iostat-missing")
grep -qs 'host-copy-not-removed' "$OUT/host-recovery.txt" && reasons+=("host-copy-not-removed")
STATUS=ok; (( ${#reasons[@]} )) && STATUS=$(IFS=,; echo "${reasons[*]}")
echo "$STATUS" > "$OUT/status"
jq -n --arg id "$ID" --arg name "$NAME" --arg size "$SIZE" --arg rate "$DATA_RATE" --arg fe "$FSYNC_EVERY" --arg wbs "$WAL_BS" --arg wr "$WAL_RATE" --arg tail "$TAIL_S" --arg pre "$PRE_S" \
  --arg image "$IMAGE" --arg image_id "$IMAGE_ID" --arg fio "$FIO_VER" --arg run_dir "~/$RUN_DIR" --arg container "$CNAME" \
  --arg fs "$FIO_STARTED" --arg fe2 "$FIO_ENDED" --arg ss "$SCRIPT_STARTED" --arg se "$(date +%Y-%m-%dT%H:%M:%S.%6N%:z)" \
  --argjson exit "$FIO_EXIT" --arg status "$STATUS" --arg host_devs "$HOST_DEVS" --arg harness "$HARNESS" --argjson bytes "$FIO_BYTES" \
  --argjson clock "$(jq -c . "$OUT/clock-offset.json")" \
  '{kind:"fio", id:$id, name:$name, size:$size, data_rate:$rate, fsync_every:($fe|tonumber), wal_bs:$wbs, wal_rate:$wr, tail_s:($tail|tonumber), pre_s:($pre|tonumber),
    image:$image, image_id:$image_id, fio_version:$fio, server_run_dir:$run_dir, container:$container, fio_started:$fs, fio_ended:$fe2,
    script_started:$ss, script_ended:$se, fio_exit:$exit, fio_bytes:$bytes, status:$status, host_devs:$host_devs, harness:$harness, clock_offset:$clock}' > "$OUT/meta.json"
# pgwal: 패턴·채우기 시각(측정 구간 밖)·시간을 덧붙인다 — append는 위 meta 그대로(바이트 동일)
if [[ "$PATTERN" == pgwal ]]; then
  jq --arg wp "$WAL_PREFILL" --arg rt "$RUNTIME" --arg ps "$PREFILL_STARTED" --arg pe "$PREFILL_ENDED" --argjson px "$PREFILL_EXIT" \
     --argjson wsp "$WAL_SZ_PREFILL" --argjson wse "$WAL_SZ_END" --argjson dse "$DATA_SZ_END" --argjson skipped "$( (( PREFILL_FAILED )) && echo true || echo false)" \
    '. + {pattern:"pgwal", wal_prefill:$wp, runtime_s:($rt|tonumber), prefill_started:$ps, prefill_ended:$pe, prefill_exit:$px,
          wal_file_bytes:{after_prefill:$wsp, before_remove:$wse}, data_file_bytes:$dse, wal_wrapped:(if (.fio_bytes | type) == "object" then .fio_bytes.wal_wrapped else null end), measured_skipped:$skipped}' "$OUT/meta.json" > "$OUT/meta.json.tmp" \
    && mv "$OUT/meta.json.tmp" "$OUT/meta.json"
fi
log "fio $NAME done: $STATUS"
[[ "$STATUS" == ok ]]
