# 디스크 쓰기 지연은 어떻게 재나 — ADR-007 디스크 원인(C5) 실험 동작 방식

> 이 문서는 **측정 하네스가 어떻게 동작하는가**만 적는다. 왜 이렇게 골랐는지와 판정은 ADR-007 §7·§8에 있다.
> - 명세: `docs/plans/2026-10-10/adr-007-disk-cause/requirement-spec.md`(본 실험) · `docs/plans/2026-10-11/adr-007-fsync-fio/requirement-spec.md`(fsync 패턴 추가 시험)
> - 타임라인·결과: `docs/plans/2026-10-10/adr-007-disk-cause/log.md` · `docs/plans/2026-10-11/adr-007-fsync-fio/log.md`
> - 판정과 결정: ADR-007 §7.2(C5 확인)·§8(counter-upsert-precheck 전환)
>
> 기준 코드: 브랜치 `feat/adr-007-throughput-wall`. §2~§8.4는 커밋 `b943a3f0`, pgwal 패턴(§5.6)·새 분석 신호(§8.5)는 커밋 `dfad9a68`이다. 코드 인용은 `git show <커밋>:<경로>` 실파일 발췌이며 줄 번호는 블록에 적은 커밋 기준이다. 줄인 곳은 `…`로 표시했다.
> - dfad9a68에서 `fio-disk.sh`·`wdrop.py`·`campaign.sh`가 바뀌었지만 append 패턴·기존 신호의 동작은 그대로다(줄 번호만 밀렸다). 단 `campaign.sh`의 `gap()`은 disk-rest 안에서 위로 옮겨 fsync-fio와 함께 쓰게 바뀌었다(출력 파일·태그를 `GAPS_FILE`·`GAP_TAG`로 받는다 — §7.2 인용은 b943a3f0판). `host-sampler.sh`·`lib.sh`·`run.sh`·`compose.tmpfs.yml`은 그대로다.
>
> 예시 숫자는 세 결과 폴더에서 가져왔다: `k6/ADR-007/results/20261010-rest-b943a3f0/`(휴식 시험) · `k6/ADR-007/results/20261010-dc-b943a3f0/`(재측정 24회) · `k6/ADR-007/results/20261011-fsync-dfad9a68/`(fsync 패턴 fio).

## 1. 무엇을 알고 싶은가 — 네 가지 방법

ADR-007 본측정에서 DB 회차마다 같은 일이 있었다. DB 컨테이너의 누적 블록 쓰기가 약 9GB에 닿으면 쓰기 속도가 절반으로 떨어졌다(약 38 → 16MB/s). 같은 순간 `LWLock:WALWrite` 대기가 뛰고 처리량이 무너졌다. 매수 방식·PG 설정과 무관했다.

원인 후보 **C5** = 저장장치(또는 호스트 쓰기 경로)다. 서버 디스크는 이렇다(명세 §0 서버 사전 확인).

- `nvme1n1` Samsung SSD 980 500GB — DRAM 캐시가 없는 NVMe SSD
- 그 위에 LVM `ubuntu-vg/ubuntu-lv` → `/`(ext4, `rw,relatime`, 온라인 discard 없음)
- Docker 볼륨(PG 데이터)도 이 `/` 아래에 있다
- `fstrim.timer` 주 1회 — 다음 실행 2026-10-12 10:21 KST

C5를 네 방향에서 잰다. 명세 §5의 판정 기준 (a)~(d)가 각각 하나씩 붙어 있다.

| 방법 | 무엇을 하나 | 이렇게 나오면 | 기준 |
|------|------------|--------------|------|
| ① 재측정 + 호스트 디스크 표본 | DB 회차를 다시 돌리며 서버 `/proc/diskstats`를 1초마다 읽는다 | 쓰기 반감 시각에 `nvme1n1`의 w_await 또는 flush 시간이 함께 뛴다 → 디스크가 그 순간 느려졌다 | (a) |
| ② fio 단독 재현 | DB 없이 fio로 비슷한 쓰기를 15GB 건다 | 비슷한 누적량에서 꺾인다 → PG 없이도 저장 경로(디스크·파일시스템·블록 계층)가 꺾인다 | (b) |
| ③ DB를 tmpfs에 | PG 데이터 디렉터리를 메모리에 두고 같은 회차를 돈다 | 반감·붕괴가 사라진다 → 디스크를 빼면 문제가 없어진다 | (c) |
| ④ 휴식 시간 시험 | fio 연속 2회 → 30분 휴식 → fio → 바로 DB 회차 | 휴식 길이가 꺾이는 지점을 옮긴다 → 저장 경로에 쉬면 풀리는 상태가 있다(SSD 캐시·정리 작업은 그 해석 후보 — 장치 안은 미측정) | (d, 선택 신호) |

- **확인** = (a)~(d) 모두 성립(단 (d)는 없어도 a~c로 판정).
- **반증** = tmpfs에서도 같은 지점에서 무너진다 → 원인을 앱·PG 쪽에서 다시 찾는다.

결과는 두 단계로 나왔다.

1. 첫 판정(log.md 10-11 04:3x 행): (a) 성립 · (b) 불성립 · (c) 성립 · (d) 불성립 → '확인'도 '반증'도 아니었다.
   - (b)를 놓친 것은 **총 쓰기 바이트 규칙** 하나 때문이다. fio에서는 data job의 버퍼 쓰기가 총량을 받쳐, flush가 반으로 줄어도 총량은 0.6배 아래로 안 떨어진다(§8.5). 옛 fio도 flush 기준으로는 3/3 꺾였다(§7.2).
   - 옛 fio의 패턴 차이(파일 끝 덧붙이기 · flush 약 900 vs 약 2,000회/s · 총 약 65 vs 약 29MB/s — §10.1)는 **탐지가 아니라 비교 가능성**의 문제다. 기준선이 DB 회차와 달라 "같은 누적량에서 꺾이나"를 비교할 수 없었다.
2. 추가 시험(2026-10-11): PG WAL처럼 미리 채운 파일을 덮어쓰는 `--pattern pgwal`(§5.6)로 기준선을 DB 회차에 맞춰 2회 돌리고, flush 수에도 같은 반감 규칙을 썼다(§8.5). 두 회 모두 +179초에서 flush가 꺾였다 → **(b) 성립 — 서명 일치**(flush/s 약 1/3 · flush 평균 약 3배). 누적량은 명세 창(3~5GB) 경계 밖 5.2GB(채우기 포함 6.3GB)로, DB 회차 3.26~3.74GB와 다르다 → **C5 확인**(ADR-007 §7.2).
   - 확인된 범위는 "이 서버 저장 경로(`nvme1n1` · ext4 · LVM 블록 층)"다. fio와 DB가 같은 층을 지나고, tmpfs는 그 층 전체를 빼면서 DB 메모리 한도도 2g → 8g로 바꿨다. "SSD 하나" 또는 "DRAM 없는 SSD 동작"은 해석이다.

## 2. 한눈에 보기

```
 k6 PC (노트북)                                 서버 jun@192.168.55.164  (~/labs/seat-reservation-lab)
 ┌─────────────────────────┐                   ┌──────────────────────────────────────────────────────┐
 │ campaign.sh             │                   │                                                      │
 │  ├─ run.sh ── k6 ───────┼── HTTP :8101 ────▶│  seatlab-app-1 ──▶ seatlab-db-1 (PG16)               │
 │  │   ├ docker stats 10s ◀┼── SSH ───────────┤│                     └ 볼륨 seatlab_pgdata            │
 │  │   ├ db_sampler 0.5s  ◀┼── SSH ───────────┤│                       (default: 디스크 / tmpfs: 메모리)│
 │  │   └ host_sampler 1s  ◀┼── SSH(stdin으로   ││                                                      │
 │  │                       │    스크립트 전송) ├┼▶ host-sampler.sh ── /proc/diskstats·meminfo·pressure │
 │  └─ fio-disk.sh ─────────┼── SSH ───────────▶│                    └ iostat -x 1 (교차 확인)          │
 │                          │                   │  seatlab-fio-<name> (alpine + fio 3.36)              │
 │ 분석: wdrop.py           │                   │    └ 바인드 ~/labs/seat-reservation-lab/fio-tmp/<실행>│
 │  (결과 폴더 읽기만)      │                   │                                                      │
 └─────────────────────────┘                   │  장치 사슬: ext4 / ← dm-0(ubuntu-lv)                  │
                                                │                   ← nvme1n1p3(파티션)                │
                                                │                   ← nvme1n1(Samsung 980, 표본 기준)  │
                                                └──────────────────────────────────────────────────────┘
```

- 노트북이 모든 것을 지휘한다. 서버에는 측정용으로 설치한 것이 없다. 호스트 표본 스크립트도 매번 SSH 표준 입력으로 보낸다.
- 서버의 다른 NVMe `nvme0n1`(Windows 디스크)은 읽지도 않는다(명세 §4).
- fio와 DB는 동시에 돌지 않는다. fio는 "DB 없는 단독" 조건이다.

## 3. 세 층의 측정

쓰기 하나가 디스크에 닿기까지 세 층을 지난다. 층마다 재는 도구가 다르다.

```
 PG 백엔드/fio  ─ write()·fdatasync() ─▶  커널(페이지 캐시·ext4·jbd2 저널)  ─▶  블록 장치 dm-0 → nvme1n1p3 → nvme1n1  ─▶ SSD 컨트롤러·NAND
 └ 층 3: DB 쪽 (docker stats·대기 이벤트)  └ (직접 재지 않음 — Dirty·Writeback만)    └ 층 2: /proc/diskstats        └ (볼 수 없음)
 └ 층 1: fio 자체 시간 (clat·sync lat)
```

### 3.1 먼저 — fsync와 NVMe flush

- `write()`는 보통 **페이지 캐시**(메모리)에 쓰고 바로 돌아온다. 디스크에는 나중에 커널이 내려 쓴다(writeback).
- `fsync()`·`fdatasync()`는 "이 파일 내용이 전원이 꺼져도 남게 하라"는 요청이다. 커널은 다음을 한다(일반적인 ext4 동작 — 이 실험이 직접 확인한 것은 아니다).
  1. 그 파일의 더티 페이지를 장치로 쓴다.
  2. 메타데이터가 바뀌었으면(예: 파일 크기가 커졌다) ext4 저널(jbd2) 커밋을 쓴다. `fdatasync`는 크기처럼 데이터를 읽는 데 필요한 메타데이터만 챙긴다.
  3. 장치에 **flush** 명령을 보낸다. SSD 안의 휘발성 쓰기 캐시에 있는 것을 NAND에 내려 쓰게 하는 명령이다. NVMe에서는 Flush 명령이다.
- 그래서 **fdatasync 1회 ≈ 장치 flush 1회**다. 실측도 그렇다: 휴식 fio-1에서 wal job의 쓰기 횟수(fio IOPS 408.2/s, `fio.json`)와 호스트 `nvme1n1` flush/s(408.1/s, `timeline-host.jsonl`을 wdrop.py 집계 함수로 fio 구간에서 계산)가 같다.
- PG는 커밋 때 WAL을 `fdatasync`한다. 반감 전 DB 회차는 `nvme1n1` flush가 약 1,750~2,055회/s였다(`20261010-dc-b943a3f0/WDROP.md` flush_per_s 전 값). 단 이것은 **장치 flush 수이지 WAL 동기화 수가 아니다.**
  - 그룹 커밋으로 WAL 동기화 1회가 커밋 여러 건을 덮는다: 커밋/WAL fsync 1.93~2.92(`20261010-dc-b943a3f0/COMPARISON.md` '커밋/WAL fsync', 디스크 회차).
  - 장치 flush에는 WAL 말고도 데이터 파일 fsync(체크포인터)·ext4 저널 커밋·서버의 다른 쓰기가 섞인다.
- flush는 파티션·LVM 층에서는 0으로 보인다. 휴식 fio-1 첫 표본에서 `nvme1n1` flushes = 183,292,809, `nvme1n1p3`·`dm-0` flushes = 0이다(`rest/fio-1/timeline-host.jsonl` 1행). flush는 디스크 장치에서 센다.

### 3.2 층 1 — fio 자신의 시간

fio는 매 I/O의 시간을 직접 잰다. 이 실험에서 보는 값은 셋이다.

| 값 | 뜻 | 어디 |
|----|----|------|
| `clat` | `write()` 호출 하나가 끝나기까지(완료 지연). 버퍼 쓰기면 메모리 복사라 µs 단위 | `fio.json` `jobs[].write.clat_ns` · 초당 로그 `<job>_clat.N.log` |
| `lat` | 제출 지연(slat) + clat | `write.lat_ns` · `<job>_lat.N.log` |
| `sync lat` | `fdatasync()`/`fsync()` 호출 하나의 시간 — **디스크 동기화 비용** | `jobs[].sync.lat_ns` (초당 로그 없음) |

- 휴식 fio-1 wal job(`rest/fio-1/fio.json`): clat 평균 6.6µs인데 sync lat 평균은 **2.44ms**(p50 1.09ms · p90 4.11ms · p99 5.73ms, N = 179,166)다. 쓰기는 메모리에서 끝나고 시간은 거의 다 fdatasync에서 쓴다.
- 같은 실행의 data job: clat p99 17µs, `fsync`(4096쓰기 = 32MiB마다) 1회 평균 105ms(N = 479).
- 초당 로그는 `log_avg_msec=1000`이라 1초 평균 한 줄이다. 첫 열 = 경과 ms, 둘째 열 = 값. fio 관례상 bw는 KiB/s, lat은 ns다(fio 문서 기준 — 하네스 코드는 단위를 다루지 않는다). 예: `data_bw.2.log` 첫 줄 `1000, 35848, 1, 0, 0` = 35,848KiB/s ≈ 35MiB/s(설정 `rate=35m`).

### 3.3 층 2 — 커널 `/proc/diskstats`

장치 지표의 정답이다(명세 §3). 커널이 장치마다 누적 카운터를 내놓는다. sudo 없이 읽힌다(log.md 10-10 12:57 행: 커널 6.8.0-136, 20열 확인).

host-sampler.sh는 장치 이름 뒤 17열을 커널 문서(iostats.rst) 순서대로 이름 붙여 **원시 누적값 그대로** 기록한다. 이 실험이 쓰는 열:

| 열 | 뜻 |
|----|----|
| `writes` | 완료된 쓰기 요청 수 |
| `write_sectors` | 쓴 섹터 수(512바이트 단위) |
| `write_ms` | 쓰기 요청들이 걸린 시간의 **합**(ms). 요청마다 들어온 때부터 끝날 때까지 — 대기열에서 기다린 시간 포함 |
| `io_ms` | 장치가 바빴던 시간(ms) — util% 계산용 |
| `weighted_ms` | 진행 중 요청 수 × 시간의 합 — 평균 대기열 길이(aqu-sz) 계산용 |
| `flushes` · `flush_ms` | 완료된 flush 요청 수와 그 시간의 합(커널 5.5+ 열 — 없으면 null) |

비율은 서버에서 계산하지 않는다. 분석기(wdrop.py)가 이웃 두 표본의 차분으로 낸다.

```python
# k6/ADR-007/scripts/wdrop.py:180-181 (agg — 창 안 차분의 합으로)
"n": len(rows), "wMBps": div(ws * 512 / 1e6 if ws is not None else None, dt), "w_per_s": div(w, dt), "w_await_ms": div(wm, w),
"flush_per_s": div(fl, dt), "flush_avg_ms": div(fm, fl), "aqu_sz": div(wt / 1000 if wt is not None else None, dt),
```

| 지표 | 식 |
|------|----|
| wMB/s | Δwrite_sectors × 512 ÷ Δt ÷ 10^6 |
| w_await (ms) | Δwrite_ms ÷ Δwrites |
| flush/s | Δflushes ÷ Δt |
| flush 평균 (ms) | Δflush_ms ÷ Δflushes |
| aqu-sz | Δweighted_ms ÷ Δt ÷ 1000 |
| util% | Δio_ms ÷ Δt ÷ 10 |

창(예: 반감 전 30초) 값은 **초 평균의 평균이 아니라** 창 안 차분을 다 더한 뒤 나눈다. 쓰기가 적은 초가 평균을 흔들지 않게 하려는 것이다.

**시간 열은 32비트에서 돈다.** 커널의 `*_ms` 카운터는 2^32ms(약 49.7일)마다 0으로 돌아간다. 차분이 음수면 2^32를 더한다. 다른 누적값이 줄면 그 간격의 그 값은 버린다.

```python
# k6/ADR-007/scripts/wdrop.py:134-144
MS_FIELDS = {"read_ms", "write_ms", "io_ms", "weighted_ms", "discard_ms", "flush_ms"}


def delta(a, b, wraps32):
    """누적값 차분 — 시간 열은 32비트로 돈다(음수면 + 2^32), 그 밖의 값이 줄면 None"""
    if not isinstance(a, (int, float)) or not isinstance(b, (int, float)):
        return None
    d = b - a
    if d < 0:
        return d + 2**32 if wraps32 else None
    return d
```

**교차 확인 — `iostat -x`.** 같은 장치를 `iostat -x -t -y 1`로도 1초마다 기록한다(`iostat-host.txt`). 열에 `w/s · wkB/s · w_await · f/s · f_await · aqu-sz · %util`이 있다. 이 사본은 사람이 눈으로 맞춰 보는 용도다. 하네스는 그 디스크 행이 있는지만 확인한다(`host-iostat-missing`, §9). 숫자를 자동으로 대조하는 코드는 없다.

### 3.4 층 3 — DB 쪽

| 무엇 | 어떻게 | 주기 | 파일 |
|------|--------|------|------|
| DB 컨테이너 블록 쓰기 | `docker stats --no-stream`의 `BlockIO` 둘째 값(누적, 10진 단위) | 10초 | `timeline-server.jsonl` |
| PG 대기 이벤트 | `pg_stat_activity` 활성 세션의 `wait_event_type:wait_event`별 개수 + 백그라운드 프로세스(checkpointer·walwriter…) 상태 | 0.5초 | `timeline-waits.jsonl` (lib.sh `db_sampler`) |
| PG WAL 누적 통계 | `pg_stat_wal`(wal_write·wal_sync·…_time) | k6 직전·직후 | `io-before.json`·`io-after.json` (lib.sh `io_snapshot`) |

**쓰기 반감**의 원래 정의는 docker stats BlockIO에서 나왔다(§8). 대기 이벤트로는 반감 때 `LWLock:WALWrite`가 뛰는지, tmpfs에서 `WALWrite`·`WALSync`가 사라지는지를 본다(log.md 10-11 04:3x 행: tmpfs 대기 이벤트에 WALWrite·WALSync 없음).

## 4. 호스트 표본 — `host-sampler.sh`

서버에서 도는 bash 스크립트 하나다. 노트북이 SSH 표준 입력으로 보낸다.

```bash
# k6/ADR-007/scripts/lib.sh:263-265
host_sampler() {
  remote "bash -s -- '$1' '$2' $HOST_DEVS" < "$LIB_DIR/host-sampler.sh"
}
```

### 4.1 무엇을 어떻게 읽나

1초마다(초 경계에 맞춰) 세 파일을 bash 내장 `read`로 읽고 한 줄 JSON을 낸다. 외부 프로세스를 띄우지 않으니 측정에 주는 부하가 작다.

```bash
# k6/ADR-007/scripts/host-sampler.sh:33-58
while kill -0 "$parent" 2> /dev/null; do
  now=$EPOCHREALTIME
  d=""
  while read -r _maj _min name f1 f2 f3 f4 f5 f6 f7 f8 f9 f10 f11 f12 f13 f14 f15 f16 f17 _rest; do
    [[ "$devs" == *" $name "* ]] || continue
    d+="${d:+,}\"$name\":{\"reads\":$f1, … ,\"writes\":$f5,\"write_merges\":$f6,\"write_sectors\":$f7,\"write_ms\":$f8, … ,\"flushes\":${f16:-null},\"flush_ms\":${f17:-null}}"
  done < /proc/diskstats
  dirty="" wb=""
  while read -r k v _u; do
    case "$k" in Dirty:) dirty=$v ;; Writeback:) wb=$v ;; esac
  done < /proc/meminfo
  …
  line="{\"t\":$now,\"disk\":{$d},\"mem\":{\"Dirty_kB\":${dirty:-null},\"Writeback_kB\":${wb:-null}},\"psi_io\":{$psi}}"
  printf '%s\n' "$line" >> "$copy"
  printf '%s\n' "$line" || break
  # 다음 초 경계까지 잔다(표본 시각이 초마다 고르게 — 읽기에 걸린 시간을 빼고)
  frac=${EPOCHREALTIME#*.}; us=$(( 1000000 - 10#$frac ))
  (( us < 1000 )) && us=$(( us + 1000000 ))
  printf -v s '%d.%06d' $(( us / 1000000 )) $(( us % 1000000 ))
  sleep "$s"
done
```

- `/proc/diskstats` — 표본 장치들의 17열 원시 누적값.
- `/proc/meminfo` — `Dirty`(아직 디스크로 안 내려간 페이지)·`Writeback`(지금 내려가는 중).
- `/proc/pressure/io` — PSI. I/O를 기다리느라 멈춘 시간의 누적(μs).
- 시각 `t`는 **서버 시계** epoch 초(소수 6자리)다(§10.3).

결과 한 줄의 실제 모양(`rest/fio-1/timeline-host.jsonl` 1행, `nvme1n1`만 남기고 줄임):

```json
{"t":1791641553.765121,"disk":{"nvme1n1":{"reads":700316, … ,"writes":452381138,"write_merges":16190133,"write_sectors":7104266714,"write_ms":310021173,"in_flight":0,"io_ms":138379149,"weighted_ms":420506645, … ,"flushes":183292809,"flush_ms":110288046}, …},"mem":{"Dirty_kB":1296,"Writeback_kB":0},"psi_io":{"some":{"avg10":0.35,"avg60":0.09,"avg300":0.05,"total":14567405472},"full":{…}}}
```

### 4.2 끊김에 대비한 두 벌 기록

- 줄을 **서버 사본에 먼저** 쓰고(`>> "$copy"`) 그다음 표준 출력(SSH → 노트북)으로 보낸다.
- 표준 출력 쓰기가 실패하거나(노트북 쪽이 닫힘) 부모 sshd가 사라지면 루프가 끝난다. 끝날 때 trap이 iostat도 끈다.
- 노트북 쪽은 `while IFS= read -r line` bash 루프로 받는다. 예전 mawk 수신기가 파이프 입력을 덩어리로 버퍼링해 줄이 늦거나 사라진 적이 있어서다(ADR-007 리뷰 A11).

```bash
# k6/ADR-007/scripts/run.sh:298-305
  # ADR-007 후속 호스트 디스크 표본(lib.sh host_sampler — 서버 1초 루프, 받는 쪽은 위와 같은 bash read 루프: mawk 버퍼링 재발 금지·리뷰 A11).
  # 서버 사본(HOST_COPY)·iostat 사본(HOST_IOSTAT)은 회차 끝 host_recover가 받는다. SSH가 끝나면 1초 뒤 다시 연다(errors.log 한 줄 — 무음 공백 금지)
  ( while :; do
      host_sampler "$HOST_COPY" "$HOST_IOSTAT" 2>/dev/null \
        | while IFS= read -r line; do printf '%s\n' "$line" >> "$dir/timeline-host.jsonl"; done
      echo "$(date -Is) host_sampler ended rc=${PIPESTATUS[0]}/${PIPESTATUS[1]} — reopen in 1s" >> "$dir/errors.log"
      sleep 1
    done ) > /dev/null 2>&1 & echo $!
```

- 회차 끝에 `host_recover`(lib.sh:274-298)가 서버 사본을 받는다. 서버 사본 줄이 흘려받은 줄보다 많으면 `timeline-host.jsonl`을 사본으로 다시 만든다. 그리고 서버 사본을 지운다.
- 실제 예: `rest/fio-1/host-recovery.txt` = `recovered-from-server host 451→453` · `iostat-copied 4513 lines`. 끝의 두 줄은 스트림이 닫히기 전에 못 받았던 것을 사본에서 되살렸다.

### 4.3 회차 안에서 언제 켜고 끄나 (run.sh `run_rep`)

1. `host_devices docker`로 표본 장치를 정한다(§4.4) → `host-devices.json`.
2. `start_pollers`가 docker stats·db_sampler·host_sampler 루프를 함께 띄운다.
3. 락 표본이 나오고(최대 15초), 호스트 표본이 나올 때까지(최대 10초) 기다린다. 시간을 넘기면 errors.log에 한 줄만 남기고 진행한다.
4. `clock_offset`(시계 차) → `k6_started` → **k6** → `k6_ended` → `clock_offset`.
5. `pg_storage`(§6) → 정합성 판정 → `stop_pollers` → `host_sampler_kill` → `host_recover`.

```bash
# k6/ADR-007/scripts/run.sh:378-392 (줄임)
  HOST_COPY="${SAMPLER_COPY%.jsonl}-host.jsonl" HOST_IOSTAT="${SAMPLER_COPY%.jsonl}-iostat.txt"   # ADR-007 후속 호스트 표본 서버 사본(같은 시도 이름)
  # 표본 장치 = DB 볼륨(Docker 데이터 루트)의 장치 사슬(리뷰 rv8 A4 — lib.sh host_devices). 못 정하면 기본값(HOST_DEVS)으로 돌고 rep_status가 드러낸다
  if host_devices docker > "$dir/host-devices.json" 2>>"$dir/errors.log" && jq -e '.sampled | length > 0' "$dir/host-devices.json" > /dev/null 2>&1; then
    HOST_DEVS="$(jq -r '.sampled | join(" ")' "$dir/host-devices.json")"
  …
  local pids; pids=$(start_pollers "$dir" "$cell")
  …
  waited=0; for w in $(seq 1 20); do [[ -s "$dir/timeline-host.jsonl" ]] && { waited=1; break; }; sleep 0.5; done
  (( waited )) || echo "$(date -Is) host-samples-before-load timeout: timeline-host.jsonl 10s 동안 비어 있음" >> "$dir/errors.log"
```

표본은 k6 구간보다 넓게 돈다(앞의 기다림·뒤의 판정 시간). 그래서 반감 직전·직후 창(±30초)이 회차 경계에 걸려도 표본이 있다.

### 4.4 장치 이름은 어떻게 정하나

장치 번호(`nvme1n1`·`dm-0`)는 재부팅 때 바뀔 수 있다. 그래서 회차·fio마다 **실제 대상 경로에서 거꾸로 찾아간다**.

```bash
# k6/ADR-007/scripts/lib.sh:249-262
host_devices() {
  timeout 30 ssh -o BatchMode=yes -o ConnectTimeout=5 "$SERVER" bash -s -- "$1" <<'REMOTE'
set -uo pipefail
if [ "$1" = docker ]; then p=$(docker info -f '{{.DockerRootDir}}'); else p="$HOME/$1"; fi
src=$(findmnt -n -o SOURCE --target "$p") || exit 1
chain=$(lsblk -s -no KNAME,TYPE "$src") || exit 1
disk=$(printf '%s\n' "$chain" | awk '$2 == "disk" {print $1; exit}')
[ -n "$disk" ] || exit 1
model=$(sed 's/[[:space:]]*$//' "/sys/block/$disk/device/model" 2>/dev/null)
…
printf '{"target":"%s","fs_source":"%s","chain":[%s],"sampled":[%s],"disk":"%s","model":"%s"}\n' "$p" "$src" "$cj" "$sj" "$disk" "$model"
REMOTE
}
```

1. 대상 경로: DB 회차는 Docker 데이터 루트(`/var/lib/docker` — 볼륨이 그 아래), fio는 `~/labs/seat-reservation-lab/fio-tmp`.
2. `findmnt --target` → 그 경로가 올라간 파일시스템의 원본 장치(`/dev/mapper/ubuntu--vg-ubuntu--lv`).
3. `lsblk -s`(역방향 트리) → 그 아래 장치 사슬. TYPE이 `disk`인 첫 장치가 분석 기준 디스크다.
4. 모델은 그 디스크 하나만 `/sys/block/<disk>/device/model`에서 읽는다. 기대값 `EXPECTED_DISK_MODEL`(기본 `Samsung SSD 980 500GB`, lib.sh:244)과 다르면 회차 사유 `host-device-unexpected`.

실제 결과(`rest/fio-1/host-devices.json` — DB 회차의 `host-devices.json`도 target만 `/var/lib/docker`로 다르고 같다):

```json
{"target":"/home/jun/labs/seat-reservation-lab/fio-tmp","fs_source":"/dev/mapper/ubuntu--vg-ubuntu--lv","chain":[{"name":"dm-0","type":"lvm"},{"name":"nvme1n1","type":"disk"},{"name":"nvme1n1p3","type":"part"}],"sampled":["dm-0","nvme1n1","nvme1n1p3"],"disk":"nvme1n1","model":"Samsung SSD 980 500GB"}
```

`chain` 배열 순서는 `lsblk -s -no` 출력 순서 그대로다(dm-0, nvme1n1, nvme1n1p3). 실제 계층은 dm-0 → nvme1n1p3 → nvme1n1이다. 코드는 순서를 쓰지 않고 TYPE으로 디스크를 고르므로 결과에는 영향이 없다.

## 5. fio 단독 재현 — `fio-disk.sh`

### 5.1 왜 fio인가

명세에는 이유가 따로 적혀 있지 않다. 코드에서 fio가 해 주는 일은 이렇다.

- 쓰기 크기·속도 제한·매 쓰기 fdatasync·N쓰기마다 fsync를 옵션 하나로 정한다.
- I/O마다 시간을 재고 분위수(p50·p99…)와 sync 시간을 `fio.json`에 낸다.
- 1초 평균 로그(`write_bw_log`·`write_lat_log`)를 낸다.

같은 것을 직접 짜면 측정 코드 자체를 또 검증해야 한다. fio는 서버에 없고 sudo도 없어서(명세 §0) 일회성 컨테이너로 돌린다. 이미지는 서버에서 빌드한다(`FROM alpine:3.20` + `apk add fio`, fio-disk.sh:139). 실제 버전은 `fio-3.36`이다(`rest/fio-1/meta.json`).

### 5.2 job 파일 — 두 job이 동시에

PostgreSQL 쓰기를 둘로 나눠 흉내 낸다: WAL(작게, 매번 동기화)과 데이터 파일(크게, 버퍼 쓰기, 가끔 동기화).

```bash
# k6/ADR-007/scripts/fio-disk.sh:59-89
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
```

| 옵션 | 뜻 | 왜 |
|------|----|----|
| `directory=/work` | 시험 파일 위치 | 컨테이너의 `/work` = 서버 `fio-tmp/<실행 폴더>` 바인드 마운트 |
| `ioengine=psync` | `pwrite()` 동기 호출 | PG 백엔드처럼 한 번에 하나씩 쓴다 |
| `fallocate=none` | 공간을 미리 잡지 않는다 | 쓰는 만큼 파일이 자란다 — **§10.1의 문제가 여기서 나온다**(pgwal은 미리 채운 파일이라 이 옵션이 있어도 wal.dat는 안 자란다 — §5.6) |
| `exitall` | 한 job이 끝나면 전부 끝낸다 | wal은 시간 기반이라 스스로 안 끝난다. data가 끝나면 함께 끝난다(리뷰 rv8 A8 — 전에는 wal이 먼저 끝나 data가 잘릴 수 있었다) |
| `log_avg_msec=1000` · `per_job_logs=1` | 1초 평균 로그, job별 파일 | 호스트 1초 표본과 같은 해상도 |
| wal `rw=write` `bs=8k` | 8KiB 순차 쓰기 | PG WAL 페이지 크기 |
| wal `fdatasync=1` | **매 쓰기마다** fdatasync | 커밋마다 WAL 동기화 흉내 |
| wal `time_based` `runtime=86400` `size=$SIZE` | 24시간 상한, 파일은 size 안에서 되감는다 | 실제 길이는 data job이 정한다(exitall) |
| wal 속도 | 기본 무제한(`--wal-rate 0`) | 디스크가 받아 주는 만큼 |
| data `bs=8k` `rate=$DATA_RATE` | 8KiB 버퍼 쓰기, 기본 35MiB/s | 반감 전 DB 쓰기 약 38MB/s 흉내(fio-disk.sh:9 — docker 척도, 호스트 척도는 23~28MB/s · §8.2) |
| data `fsync=$FSYNC_EVERY` | 4096쓰기(32MiB)마다 fsync | 체크포인트·관계 파일 동기화 흉내 |
| data `end_fsync=1` | 끝에 한 번 fsync | 마지막 더티 페이지까지 디스크로 |
| data `size=$SIZE` | 기본 15G | 반감 지점 약 9GB를 넉넉히 넘게 |

휴식 시험의 실제 job 파일(`rest/fio-1/job.fio`)은 위에 `bs=8k`·`size=15G`·`rate=35m`·`fsync=4096`이 들어간 그대로다. wal 속도 행은 빈 줄이다.

### 5.3 실행 흐름

```
fio-disk.sh --id <id> --name fio-N
 1. 이미지 확인/빌드 · fio 버전 · 서버 실행 폴더 생성 + job.fio 업로드
 2. host_devices fio-tmp → host-devices.json → 호스트 표본 루프 시작 → 첫 표본 대기 → --pre-s초 기준선 → clock_offset
 3. docker run --rm seatlab-fio-<name> … fio --output-format=json --output=/work/fio.json /work/job.fio   (배경 실행 + wait)
 4. --tail-s초 꼬리(쓰기 뒤 더티 페이지 flush 관찰) → clock_offset → teardown(표본 중지·로그 회수·시험 파일 삭제)
 5. 판정: 바이트 · 로그 파일 · 장치 · 표본 커버리지 · iostat → status · meta.json
```

fio 컨테이너는 서버 사용자 uid·gid로 돈다(`--user $(id -u):$(id -g)`). 시험 파일을 sudo 없이 지우기 위해서다.

```bash
# k6/ADR-007/scripts/fio-disk.sh:169-178
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
```

### 5.4 바이트 검증

wal은 시간 기반이라 기대 크기가 없다. 그래서 data job만 검사한다. data가 계획의 99% 미만이면 `fio-data-short`다.

```python
# k6/ADR-007/scripts/fio-disk.sh:184-191 (heredoc 안 Python)
import json, sys
d = json.load(open(sys.argv[1])); n, u = int(sys.argv[2][:-1]), sys.argv[2][-1].upper()
want = n * 1024 ** "KMG".index(u) * 1024
b = {j["jobname"]: j["write"]["io_bytes"] for j in d["jobs"]}
print(json.dumps({"wal": b.get("wal"), "data": b.get("data"), "total": sum(v for v in b.values() if v), "data_expected": want,
                  "data_ok": bool(b.get("data") and b["data"] >= 0.99 * want)}))
```

실제(`rest/fio-1/meta.json` `fio_bytes`): data 16,106,127,360B = 기대 16,106,127,360B(15GiB) · wal 1,467,727,872B · 합계 17,573,855,232B · `data_ok: true`.

### 5.5 중단 정리와 `fio-tmp` 경계

- 시험 파일은 서버 `~/labs/seat-reservation-lab/fio-tmp/<id>-<name>-<시각>/` 아래에만 만든다(명세 §2).
- 지우기 전에 `realpath`로 실제 경로를 확인한다. `fio-tmp` 그 자체가 아니고 그 **아래**일 때만 지운다. 심볼릭 링크로 밖을 가리키면 거부한다.

```bash
# k6/ADR-007/scripts/fio-disk.sh:99-103
# 서버 실행 폴더 지우기 — 실제 경로가 fio-tmp 아래(그 자체가 아닌 하위)일 때만. 인자: 'files'(시험 파일 둘만) | 'all'(실행 폴더 통째로)
remove_run() {
  remote "base=\$(realpath -e \"\$HOME/$FIO_BASE\") && [ \"\$base\" = \"\$HOME/$FIO_BASE\" ] && p=\$(realpath -e \"\$HOME/$RUN_DIR\") && case \"\$p\" in \"\$base\"/?*) ;; *) echo \"refuse: \$p\" >&2; exit 1 ;; esac && \
    if [ '$1' = all ]; then rm -rf -- \"\$p\" && test ! -e \"\$p\"; else rm -f -- \"\$p/wal.dat\" \"\$p/data.dat\" && test ! -e \"\$p/wal.dat\" && test ! -e \"\$p/data.dat\"; fi"
}
```

정리(`teardown`, fio-disk.sh:108-127)는 정상 끝과 중단(INT·TERM·HUP)이 같이 쓰고, 한 번만 돈다.

1. 노트북 쪽 표본 루프를 끈다.
2. 이 실행의 컨테이너(`seatlab-fio-<name>`)를 지운다.
3. 이 실행의 서버 표본 사본을 받고 지운다(`host_recover`).
4. 서버의 `fio.json`·`*.log`를 tar로 받아 온다.
5. 시험 파일 둘(`wal.dat`·`data.dat`)은 **항상** 지운다(서버 디스크를 채우지 않게).
6. 로그 회수가 됐을 때만 실행 폴더를 통째로 지운다. 실패하면 근거로 남기고 errors.log에 경로를 적는다.

중단이면 status = `interrupted[,사유…]`, exit 130이다(fio-disk.sh:128-134). 배경 실행 + `wait`로 바꾼 이유는 위 코드 주석대로다. bash는 앞쪽 자식이 끝날 때까지 trap을 미루기 때문이다.

### 5.6 PG WAL 흉내 — `--pattern pgwal` (dfad9a68)

§10.1에서 보듯 옛 wal job은 PG WAL과 다르게 썼다. PG는 0으로 미리 채운 16MB 세그먼트를 **덮어쓴다**. 옛 job은 파일 끝에 덧붙였다. 그래서 같은 매 쓰기 fdatasync라도 동기화 특성이 달랐다. `--pattern pgwal`은 이것을 맞춘다. 옵션 없이 부르면(`--pattern append`) job 파일·meta가 b943a3f0과 바이트 동일하다(명세 §2).

**job 파일 — 두 job 모두 시간으로 끝난다**

```bash
# k6/ADR-007/scripts/fio-disk.sh:124-166 (dfad9a68)
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
```

| append와 다른 점 | 값(기본) | 뜻 |
|----------------|---------|----|
| `[global] time_based` `runtime` | `--runtime 300` | 두 job 모두 300초에 끝난다. `exitall`이 필요 없다 |
| wal `overwrite=1` `size=$WAL_PREFILL` | `--wal-prefill 1G` | 이미 있는 wal.dat 1GiB **안**을 덮어쓴다. 끝에 닿으면 처음부터 다시 쓴다 |
| wal `rate` | `--wal-rate 16m` | 16MiB/s ÷ 8KiB ≈ 초당 2,000회 fdatasync — DB 회차 반감 전 flush 1,750~2,055회/s에 맞춤 |
| data `rate` | `--data-rate 12m` | wal 16 + data 12 ≈ 28MB/s — DB 회차 반감 전 쓰기 23~28MB/s |
| `[prefill]` | 1MiB 순차 · `zero_buffers` · 끝에 fsync | 측정 전에 wal.dat를 0으로 채운다(PG 세그먼트 흉내) |

- 이 패턴에서만 기본값이 바뀐다: `--wal-rate 16m` · `--data-rate 12m`(명시하면 그 값, fio-disk.sh:69-71).
- 인자 검사: `--runtime` 10~3600초, `--wal-rate` 0 거부(바이트 기준이 속도 × 시간이라), `--size ≥ data 속도 × 시간`(data.dat가 되감기지 않게), `--wal-prefill ≥ --wal-bs`(:72-85).
- 실제 job 파일(`20261011-fsync-dfad9a68/fsync/fio-sync-1/job.fio`·`prefill.fio`)은 위에 `runtime=300`·`rate=16m`·`size=1G`·`rate=12m`·`size=15G`가 들어간 그대로다.

**흐름 — 채우기는 측정 구간 밖**

```
DB 확인(떠 있으면 거부) → 이미지·실행 폴더(job.fio + prefill.fio) → 장치 사슬·호스트 표본 시작
 → 채우기 fio(prefill.json · prefill_started/ended) → 채운 바이트·wal.dat 크기 확인(모자라면 측정 건너뜀)
 → --pre-s 기준선 → clock_offset → 측정 fio(fio.json · fio_started/ended) → 꼬리 → 정리(지우기 직전 wal.dat 크기 한 번 더)
```

실제 시각(`fio-sync-1/meta.json`): 채우기 10:14:23.30 ~ 10:14:25.07(약 1.8초), 측정 10:14:31.59 ~ 10:19:32.99.

**단독 조건 — DB가 떠 있으면 시작하지 않는다**

```bash
# k6/ADR-007/scripts/fio-disk.sh:179-185 (dfad9a68)
# pgwal 단독 조건(리뷰 rv10 B1): seatlab DB 컨테이너가 떠 있으면(또는 확인 실패) 시작하지 않는다
if [[ "$PATTERN" == pgwal ]]; then
  dbup=$(remote "docker ps -q --filter label=com.docker.compose.project=seatlab --filter label=com.docker.compose.service=db" 2>>"$OUT/errors.log"); dbrc=$?
  if (( dbrc != 0 )) || [[ -n "$dbup" ]]; then
    echo "$(date -Is) db-running check: rc=$dbrc containers=[$dbup] — fio not started" >> "$OUT/errors.log"; echo db-running > "$OUT/status"; log "seatlab db running (or check failed) — refuse"; exit 3
  fi
fi
```

확인 자체가 실패해도(ssh 오류) 거부한다. campaign.sh도 단계마다 같은 확인(`db_idle`)을 한다.

**채우기 확인 — 모자라거나 크기를 못 읽으면 측정을 건너뛴다**

```bash
# k6/ADR-007/scripts/fio-disk.sh:272-286 (dfad9a68)
  if (( PREFILL_EXIT != 0 )); then
    reasons+=("fio-prefill-exit-$PREFILL_EXIT"); PREFILL_FAILED=1
  else
    # 채운 바이트(prefill.json — 서버에서 바로 읽는다)와 채운 직후 wal.dat 크기(B5). 모자라면 측정을 건너뛴다(B2)
    pf=$(remote "cat \"\$HOME/$RUN_DIR/prefill.json\"" 2>>"$OUT/errors.log" | python3 -c 'import json,sys; print(sum(j["write"]["io_bytes"] for j in json.load(sys.stdin)["jobs"]))' 2>>"$OUT/errors.log")
    WAL_SZ_PREFILL=$(remote "stat -c %s \"\$HOME/$RUN_DIR/wal.dat\"" 2>>"$OUT/errors.log"); [[ "$WAL_SZ_PREFILL" =~ ^[0-9]+$ ]] || WAL_SZ_PREFILL=null
    if [[ "$WAL_SZ_PREFILL" == null ]]; then   # 크기를 못 읽으면 덮어쓰기 조건을 확인할 수 없다 — 측정하지 않는다(재점검 rv11)
      echo "$(date -Is) wal.dat size unreadable after prefill — measured fio skipped" >> "$OUT/errors.log"
      reasons+=("fio-wal-file-unverified"); PREFILL_FAILED=1
    fi
    if ! [[ "$pf" =~ ^[0-9]+$ ]] || (( pf < $(to_bytes "$WAL_PREFILL") )); then
      echo "$(date -Is) prefill bytes ${pf:-unreadable} < $(to_bytes "$WAL_PREFILL") — measured fio skipped" >> "$OUT/errors.log"
      reasons+=("fio-prefill-short"); PREFILL_FAILED=1
    fi
  fi
```

**덮어쓰기였나 — wal.dat 크기를 두 번 잰다**

wal.dat는 덮어쓰기라 채운 크기 그대로여야 한다. 채운 직후(위)와 정리 직전(teardown, :210-214)에 `stat -c %s`로 재고, 둘 중 하나라도 `--wal-prefill`과 다르면 `fio-wal-file-grew`다.

```bash
# k6/ADR-007/scripts/fio-disk.sh:354-358 (dfad9a68)
# B5: wal.dat 크기 = 채운 크기(채운 직후·정리 직전 — 측정을 건너뛰었으면 채운 직후만)
if [[ "$PATTERN" == pgwal && "$WAL_SZ_PREFILL" != null ]]; then
  want_wal=$(to_bytes "$WAL_PREFILL")
  if [[ "$WAL_SZ_PREFILL" != "$want_wal" ]] || { (( ! PREFILL_FAILED )) && [[ "$WAL_SZ_END" != "$want_wal" ]]; }; then reasons+=("fio-wal-file-grew"); fi
fi
```

실제(`fio-sync-1/meta.json` `wal_file_bytes`): `after_prefill` 1,073,741,824 · `before_remove` 1,073,741,824 — 1GiB 그대로였다.

**바이트 검증 — 시간 끝이라 기준 = 속도 × 시간 × 0.95, wal은 관찰값**

```python
# k6/ADR-007/scripts/fio-disk.sh:331-341 (dfad9a68, heredoc 안 Python)
we, de = B(sys.argv[3]) * rt * 0.95, B(sys.argv[4]) * rt * 0.95
…
print(json.dumps({"wal": b.get("wal"), "data": b.get("data"), "total": sum(v for v in b.values() if v),
                  "wal_expected_min": int(we), "data_expected_min": int(de),
                  "wal_ok": bool(b.get("wal") and b["wal"] >= we), "data_ok": bool(b.get("data") and b["data"] >= de),
                  "wal_ratio": round(b["wal"] / (we / 0.95), 4) if b.get("wal") else None,
                  "wal_wrapped": bool(b.get("wal") and b["wal"] > B(sys.argv[6])),
                  "prefill": p, "prefill_expected": B(sys.argv[6]), "prefill_ok": bool(p is not None and p >= B(sys.argv[6]))}))
```

```bash
# k6/ADR-007/scripts/fio-disk.sh:346-348 (dfad9a68)
  # wal 부족은 관찰값(B7 — 사유 없음, fio_bytes.wal_ok·wal_ratio)
  jq -e '.data_ok' <<< "$FIO_BYTES" > /dev/null || reasons+=("fio-data-short")
  jq -e '.prefill_ok' <<< "$FIO_BYTES" > /dev/null || reasons+=("fio-prefill-short")
```

- data가 모자라면 `fio-data-short`(실패). wal이 모자란 것은 **실패가 아니다**. 디스크가 느려지면 매 쓰기 fdatasync인 wal이 속도 제한 아래로 떨어지는 것 자체가 측정값이기 때문이다(명세 §2 재합의). `wal_ok`·`wal_ratio`만 남긴다.
- 실제(`fio-sync-1/meta.json` `fio_bytes`): wal 3,464,085,504B(`wal_ok: false` · `wal_ratio` 0.6883 · `wal_wrapped: true`) · data 3,774,873,600B(기준 3,586,129,920 이상 — `data_ok: true`) · prefill 1,073,741,824B(`prefill_ok: true`). status는 `ok`.

**캠페인 — `campaign.sh --set fsync-fio`**

```bash
# k6/ADR-007/scripts/campaign.sh:146-153 (dfad9a68, 줄임)
if [[ "$SET" == fsync-fio ]]; then
  FS_ROOT="$ROOT/fsync"
  FSYNC_ARGS=(--sha "$SHA" --pattern pgwal --runtime "$FIO_RUNTIME")
  STEPS=("fio-sync-1 fio ${FSYNC_ARGS[*]}" "fio-sync-2 fio ${FSYNC_ARGS[*]}")
  FS_STEP_S=$(( FIO_RUNTIME + 180 ))   # 측정 + 채우기(1G)·이미지 확인·표본·기준선 5초·꼬리 20초·정리
  # 인자 조합 검증(리뷰 rv10 B6): fio-disk.sh가 같은 인자로 job 파일을 만들 수 있는지(예: --fio-runtime 3600이면 data 12 MiB/s × 3600 > --size 15G → 거부)
  argerr=$(bash "$DIR/fio-disk.sh" --id "$CAMPAIGN/fsync" --name fio-sync-1 "${FSYNC_ARGS[@]}" --dry-run 2>&1 > /dev/null) \
    || { echo "fsync-fio: fio-disk.sh가 인자를 거부했다 — $argerr" >&2; echo "$(date -Is) ABORT FSYNC argcheck: $argerr" >> "$LOG"; exit 2; }
```

- fio-sync-1 → fio-sync-2를 **휴식 없이** 이어 돈다. 앞 측정 끝 → 다음 측정 시작 간격을 `fsync/gaps.txt`·CAMPAIGN.log에 남긴다(disk-rest와 같은 `gap` 함수 — dfad9a68에서 두 세트가 공유하게 위로 옮겼다).
- 마감·fstrim 가드(§7.3)는 그대로 쓴다. 이어서 실행이 없다(`fsync/`가 있으면 거부).
- 단계마다 `db_idle`로 DB가 떠 있지 않은지 확인한다. 떠 있으면 exit 6.
- 실제(`20261011-fsync-dfad9a68/CAMPAIGN.log`): 10:14:16 시작, 계획 추정 16분, fio-sync-1 → fio-sync-2 간격 40.5초, 10:25:40 끝.

## 6. tmpfs 조건 — `compose.tmpfs.yml`

PG 데이터 디렉터리(WAL 포함)를 메모리 파일시스템에 둔다. 디스크를 경로에서 빼는 것이다.

```yaml
# k6/ADR-007/compose.tmpfs.yml:5-14
services:
  db:
    mem_limit: ${DB_MEM:-8g}

volumes:
  pgdata:
    driver_opts:
      type: tmpfs
      device: tmpfs
      o: "size=${PG_TMPFS_SIZE:-6g}"
```

```bash
# k6/ADR-007/scripts/run.sh:75 (preset_env) · :141
    tmpfs)   echo "PG_CHECKPOINT_TIMEOUT=5min PG_MAX_WAL_SIZE=1GB PG_SHARED_BUFFERS=128MB PG_TMPFS_SIZE=6g DB_MEM=8g" ;;
[[ "$PG_PRESET" == tmpfs ]] && export COMPOSE_EXTRA="${COMPOSE_EXTRA:-} -f k6/ADR-007/compose.tmpfs.yml"
```

- 바뀌는 것은 둘뿐이다: 볼륨 종류(tmpfs 6g)와 DB 메모리 한도(2g → 8g). PG 설정 세 값은 default와 같다.
- 메모리 한도를 올리는 이유: tmpfs 페이지가 컨테이너 메모리(cgroup shmem)로 잡히기 때문이다. 이 차이는 **교란 변수**로 결과에 명시한다(명세 §2).
- 볼륨은 회차마다 `compose down -v`로 지운다. 내린 뒤 `seatlab_pgdata` 볼륨과 db 컨테이너가 정말 없는지 확인한다. 남으면 그 회차는 `compose-down-failed`로 끊는다(lib.sh:72-86). tmpfs 회차 다음 디스크 회차가 tmpfs 볼륨을 이어 쓰지 않게 하려는 것이다(리뷰 rv8 A2).

### 6.1 적용 확인 — `pg-storage.json`

모든 회차의 k6 뒤에 DB 컨테이너 안에서 저장 위치와 크기를 읽는다.

```bash
# k6/ADR-007/scripts/lib.sh:309-314 (pg_storage 안, 서버에서 실행)
dd=$(q "SHOW data_directory")
fs=$(docker exec "$c" stat -f -c %T "$dd" 2>/dev/null | tr -d '[:space:]')
fm=$(docker exec "$c" findmnt -n -o SOURCE,FSTYPE,SIZE,USED,OPTIONS --target "$dd" 2>/dev/null | head -1 | tr -s ' ' | tr -d '"')
df=$(docker exec "$c" df -B1 --output=size,used,avail "$dd" 2>/dev/null | tail -1 | tr -s ' ' | sed 's/^ //')
db=$(q "SELECT pg_database_size('seat')")
wal=$(q "SELECT coalesce(sum(size), 0) FROM pg_ls_waldir()")
```

실제 두 회차(`20261010-dc-b943a3f0/…/L4/S4/rep1/pg-storage.json`):

| 항목 | none-tmpfs-s4 rep1 | none-default-s4 rep1 |
|------|-------------------|---------------------|
| `fstype` (`stat -f`) | `tmpfs` | `ext2/ext3` |
| `findmnt` | `tmpfs tmpfs 6G 1.2G rw,relatime,size=6291456k,inode64` | `/dev/mapper/ubuntu--vg-ubuntu--lv[/var/lib/docker/volumes/seatlab_pgdata/_data] ext4 97.9G 43.2G …` |
| `df_bytes.avail` | 5,116,669,952 | 53,265,739,776 |
| `db_bytes` / `wal_bytes` | 381,066,263 / 905,969,664 | 324,541,463 / 889,192,448 |
| `cgroup.max` / `shmem` | 8,589,934,592 / 1,473,536,000 | 2,147,483,648 / 147,570,688 |

- `stat -f -c %T`는 ext4를 `ext2/ext3`로 보고한다(같은 매직 넘버). 코드는 `"tmpfs"`인가만 보므로 판정에는 문제가 없다. 정확한 종류는 `findmnt` 열에 있다.
- tmpfs 회차의 `shmem` 약 1.47GB가 tmpfs에 들어간 DB+WAL이다. 6GB 안에 넉넉히 들어갔다(명세 §8 가정 2).

판정 사유는 §9 표에 있다(`tmpfs-not-applied`·`tmpfs-low-space`·`disk-preset-on-tmpfs`·`storage-check-failed`·`db-oom-kill`).

## 7. 휴식 시험 — `campaign.sh --set disk-rest`

SSD는 쉬는 동안 내부 정리(가비지 컬렉션·SLC 캐시 비우기 등)를 한다고 알려져 있다. 휴식 길이가 꺾이는 지점을 옮기는지 본다(기준 (d)).

### 7.1 단계

```bash
# k6/ADR-007/scripts/campaign.sh:136-141
  REST_ROOT="$ROOT/rest"
  REP_NAME=counter-upsert-default-s4-afterfill
  REP_ARGS=(--limit-strategy counter-upsert --pg-preset default --strategy pessimistic-nowait --pool 10 --apps 1 --levels 4 --cells S4)
  FIO_ARGS=(--sha "$SHA" --size "$FIO_SIZE" --pre-s 2 --tail-s 5)
  STEPS=("fio-1 fio ${FIO_ARGS[*]}" "fio-2 fio ${FIO_ARGS[*]}" "rest-${REST_S}s rest $REST_S" "fio-3 fio ${FIO_ARGS[*]}" "$REP_NAME rep ${REP_ARGS[*]}")
  FIO_S=$(fio_planned_s); REST_PLAN=$(( 180 + FIO_S * 3 + REST_S + $(rep_planned_s "$REP_NAME") ))   # 180 = 배포·이미지 빌드
```

1. **미리 준비**: 배포 + 앱 이미지 빌드(`compose build`). DB는 띄우지 않는다. fio 단독 조건을 더럽히지 않게 하려는 것이다(campaign.sh:157).
2. fio-1(15G) → **바로** fio-2(15G) → **1800초 휴식** → fio-3(15G) → **바로** 디스크 S4 1회(counter-upsert·default).
3. fio 단계는 `--pre-s 2 --tail-s 5`로 줄인다. 단계 사이 간격을 짧게 하려는 것이다.
4. fio가 하나라도 실패하면 멈춘다. 뒤 단계의 '바로 다음'·'휴식 뒤' 의미가 깨지기 때문이다.
5. 이어서 실행이 없다. 시간 간격 자체가 측정값이라 중간부터 다시 잴 수 없다. `results/<id>/rest/`가 이미 있으면 거부한다.

### 7.2 간격을 기록한다

'바로'는 0초가 아니다. 앞 단계의 꼬리·정리, 다음 단계의 표본 시작·기준선이 끼어든다. S4는 compose up·health·예열·시드(200만 석)까지 지난 뒤에야 k6를 시작한다. 그래서 실제 간격(앞 부하 끝 → 이 부하 시작)을 `rest/gaps.txt`와 CAMPAIGN.log에 남긴다(리뷰 rv8 A6).

```bash
# k6/ADR-007/scripts/campaign.sh:163-166
  gap() {  # 인자: 앞 이름 앞 끝(epoch) 이 이름 이 시작(epoch) 설명
    local g; g=$(python3 -c 'import sys; print(f"{float(sys.argv[2]) - float(sys.argv[1]):.1f}")' "$2" "$4")
    echo "$1 → $3 = ${g}s ($5)" >> "$REST_ROOT/gaps.txt"; echo "$(date -Is) REST gap $1 → $3 = ${g}s ($5)" >> "$LOG"
  }
```

실제 `20261010-rest-b943a3f0/rest/gaps.txt`:

| 구간 | 간격 |
|------|------|
| fio-1 → fio-2 | 23.0초 |
| fio-2 → fio-3 | 1,824.1초 |
| fio-3 → S4 회차 시작 | 14.7초 |
| fio-3 → S4 k6 시작 | 72.7초 |

결과(log.md 10-10 23:20 ~ 10-11 00:16 행): fio 3회 모두 **총 쓰기** 호스트 반감 없음(fio-1·2 연속 약 35GB 포함). 휴식 뒤 S4는 docker 반감 +371초 9.19GB로, 다른 디스크 회차와 같은 자리였다.

나중에 추가한 flush 반감(§8.5)으로 같은 폴더를 다시 보면 3회 모두 꺾였다(`20261010-rest-b943a3f0/WDROP.md`·`wdrop.json` — dfad9a68 `wdrop.py`로 만든 산출물):

| 실행 | flush 반감 초 | 그때 장치 누적 GB | flush/s 직전 4칸 중앙값 → 반감 칸 (10초 칸) |
|------|-------------|----------------|-------------------|
| fio-1 | 119 | 7.81 | 912 → 297.4 |
| fio-2 (fio-1 뒤 23초) | 60 | 3.79 | 915.1 → 463.1 |
| fio-3 (30분 휴식 뒤) | 119 | 7.81 | 918.1 → 301.7 |
| 휴식 뒤 S4(회차) | 369 | 3.45 | 1,719.3 → 1,010.2 |

첫 실행과 30분 휴식 뒤가 같은 자리다. 23초 뒤의 fio-2만 일찍 꺾였다.

### 7.3 마감·fstrim 가드

정기 fstrim(2026-10-12 10:21 KST)은 SSD 상태를 바꾼다. fstrim 전후가 섞이면 비교가 안 된다. 그래서 main이 아닌 세트(disk-cause·disk-rest)에 두 겹의 가드가 있다.

```bash
# k6/ADR-007/scripts/campaign.sh:106-126 (줄임)
guard_start() {  # 인자: 이름 계획초 — 예상 끝이 마감을 넘으면 시작하지 않는다(exit 5)
  (( ALLOW_AFTER )) && return 0
  local now; now=$(date +%s)
  if (( now + $2 + MARGIN_S > DEADLINE_S )); then
    echo "$(date -Is) ABORT deadline: $1 예상 끝 … > 마감 $DEADLINE — 시작하지 않음" >> "$LOG"
    echo "deadline: $1 시작 거부" >&2; exit 5
  fi
  if [[ -n "$FSTRIM0" ]]; then
    local f; f="$(fstrim_mark)"
    if [[ -n "$f" && "$f" != "$FSTRIM0" ]]; then echo "$(date -Is) ABORT fstrim-ran: $f (캠페인 시작 때 $FSTRIM0) — $1 시작하지 않음" >> "$LOG"; exit 5; fi
  fi
}
mark_crossed() {  # 인자: 이름 status파일 — 끝난 뒤 마감을 넘었거나 fstrim이 돌았으면 crossed-fstrim을 붙이고 exit 5
  local now f st crossed=""; now=$(date +%s); f="$(fstrim_mark)"
  (( now > DEADLINE_S )) && crossed="끝 $(date -Is) > 마감 $DEADLINE"
  [[ -n "$FSTRIM0" && -n "$f" && "$f" != "$FSTRIM0" ]] && crossed="${crossed:+$crossed · }fstrim 실행 $f"
  …
  if [[ -f "$2" ]]; then st="$(cat "$2")"; [[ "$st" == ok ]] && st=crossed-fstrim || st="$st,crossed-fstrim"; echo "$st" > "$2"; fi
  echo "$(date -Is) ABORT crossed-fstrim: $1 ($crossed) — status에 crossed-fstrim" >> "$LOG"; exit 5
}
```

- **시작 전**(`guard_start`): 지금 + 계획 시간 + 여유 300초가 마감(기본 `2026-10-12T10:21:00+09:00`)을 넘으면 시작하지 않는다. 서버 `fstrim.service`의 마지막 실행 시각(`systemctl show … ExecMainStartTimestamp` — sudo 불필요)이 캠페인 시작 때와 달라졌어도 멈춘다.
- **끝난 뒤**(`mark_crossed`): 마감을 넘었거나 fstrim이 돌았으면 그 회차·단계의 status에 `crossed-fstrim`을 붙이고 멈춘다. 분석(wdrop.py)은 status 파일을 정본으로 읽으므로 그 표시가 보인다(§8.4).
- 계획 시간: fio 1회 = data 크기 ÷ 35MiB/s + 120초, S4 회차 720초, S4c 회차 660초(campaign.sh:102-105).
- 실제: 휴식 시험 시작 때 `fstrim_last=Mon 2026-10-05 00:04:44 UTC`, 계획 추정 72분(`20261010-rest-b943a3f0/CAMPAIGN.log` 1~2행). disk-cause 24회는 10-11 04:15에 마감·fstrim 표시 없이 끝났다(log.md).
- `--allow-after-fstrim`을 주면 거부·표시 대신 로그 한 줄만 남긴다(fstrim 뒤 관찰용).

## 8. 분석 — `wdrop.py`

결과 폴더를 **읽기만** 한다. 캠페인 루트·조건 폴더·회차 폴더·fio 폴더 어디를 줘도 된다.

### 8.1 쓰기 반감의 정의

task 06a 분석 정의를 그대로 옮겼다(명세 §3).

```python
# k6/ADR-007/scripts/wdrop.py:121-130
def find_drop(points, min_t):
    """points = [(t, 누적 바이트)] — 06a 규칙. 반환 dict 또는 None"""
    iv = [(t1, t2, (v2 - v1) / (t2 - t1) / 1e6, v1) for (t1, v1), (t2, v2) in zip(points, points[1:]) if t2 > t1]
    for i in range(4, len(iv)):
        if iv[i][0] < min_t:
            continue
        prev = statistics.median(x[2] for x in iv[i - 4:i])
        if i + 1 < len(iv) and iv[i][2] < 0.6 * prev and iv[i + 1][2] < 0.6 * prev:
            return {"t": iv[i][0], "bytes": iv[i][3], "prev_mbps": prev, "drop_mbps": iv[i][2], "next_mbps": iv[i + 1][2]}
    return None
```

- 간격 = 연속 두 표본 사이. 속도 = Δ누적 쓰기 ÷ Δt(MB/s, 10^6).
- 반감 = 다음을 모두 만족하는 **첫** 간격 i.
  - i ≥ 4(직전 4간격이 있다)이고 시작 시각 ≥ k6 + 30초(예열 구간 제외)
  - 속도(i) < 0.6 × 직전 4간격 속도의 **중앙값**
  - 다음 간격 속도(i+1)도 < 0.6 × 그 중앙값(한 번 튄 것은 반감이 아니다). 다음 간격이 없으면 반감 아님.
- 같은 함수를 두 데이터에 쓴다.
  - **docker 반감**: docker stats BlockIO 누적(10초 표본). DB 회차만.
  - **호스트 반감**: 호스트 장치 누적 쓰기를 k6(또는 fio) 시작 기준 10초 칸으로 묶어서(`host_cum_points`). 부하 구간 끝 이후 칸은 뺀다. 끝난 뒤 쓰기가 0으로 떨어지는 것을 반감으로 잡지 않게 하려는 것이다(fio 스모크 실측, wdrop.py:246-249).

### 8.2 ±30초 창

반감 시각(t1) 기준 `[−30, 0)`초와 `[0, +30)`초의 호스트 지표를 비교한다(`--win`, 기본 30). 이웃 표본 간격의 가운데 시각으로 창에 넣는다.

```python
# k6/ADR-007/scripts/wdrop.py:188-189 · :242-244
def window(rows, lo, hi):
    return agg([r for r in rows if lo <= r[0] - r[1] / 2 < hi])
…
        if drop:
            res["host_pre"] = {k: rnd(v) for k, v in (window(rel, drop["t"] - win, drop["t"]) or {}).items()}
            res["host_post"] = {k: rnd(v) for k, v in (window(rel, drop["t"], drop["t"] + win) or {}).items()}
```

실제 한 행(`20261010-dc-b943a3f0/WDROP.md`, counter-upsert-default-s4 rep1):

| 항목 | 값 |
|------|----|
| docker 반감 | k6 +355초 · 8.93GB · 54.2 → 19.2·17.7MB/s |
| 호스트 반감 | +359초 · k6 시작 뒤 `nvme1n1` 누적 3.47GB |
| wMB/s 전→후 | 28.0 → 11.0 |
| w_await 전→후 | 0.24 → 1.96ms |
| flush/s 전→후 | 2,055 → 483 |
| flush 평균 전→후 | 0.41 → 1.90ms |
| aqu-sz 전→후 | 1.86 → 2.95 |

docker GB(8.93)와 호스트 GB(3.47)는 기준점이 다르다. docker 값은 **컨테이너 시작부터** 누적이라 시드(200만 석 INSERT)가 들어 있고, 호스트 값은 **k6 시작부터** 누적이며 서버 전체 I/O다.

**속도 차이는 설명되지 않았다.** 반감 직전 docker BlockIO 쓰기 속도는 47.7~56.5MB/s(`WDROP.md` 'MB/s 직전', 디스크 12회)인데 같은 때 호스트 `nvme1n1`은 23.2~28.0MB/s다. 시드 포함은 누적량 차이를 설명할 뿐 속도 비(약 2배)는 설명하지 못한다. cgroup 블록 통계가 여러 층(dm·파티션)을 겹쳐 세는 것일 수 있으나 확인하지 않았다(미검증). fio-disk.sh 머리말(:9)의 "반감 전 DB 쓰기 약 38MB/s"도 docker 척도다 — 호스트 척도로는 23~28MB/s다(문서 메모 — 코드는 바꾸지 않는다).

### 8.3 커버리지 검사 (`--coverage`)

run.sh·fio-disk.sh가 회차 판정 때 부른다. 표본이 빠진 회차를 `host-sampler-thin`으로 거른다.

```python
# k6/ADR-007/scripts/wdrop.py:79-98
MAX_GAP_S = 2.5


def coverage(path, started, ended, off, min_ratio, dev=None):
    t0, t1 = ts(started), ts(ended)
    total = t1 - t0
    if total < 1:
        print(f"host coverage: k6 구간 {total:.1f}s < 1s — 판정 안 함", file=sys.stderr)
        return 0
    stamps = sorted(r["t"] - off for r in host_samples(path)
                    if (dev in (r.get("disk") or {}) if dev else bool(r.get("disk"))))
    inw = [t for t in stamps if t0 <= t <= t1]
    ratio = len(inw) / total
    edges = [t0] + inw + [t1]
    gap = max(b - a for a, b in zip(edges, edges[1:]))
    if ratio < min_ratio or gap > MAX_GAP_S:
        …
        return 1
    return 0
```

- 두 조건 중 하나라도 걸리면 thin이다.
  - **비율**: 구간 안 표본 수 ÷ 구간 초 < 0.9
  - **최대 공백**: 구간 양 끝을 포함한 이웃 표본 사이가 2.5초 초과
- 그 디스크 값이 든 표본만 센다(dev 인자).
- 2.5초인 이유: 표본 1개 빠짐(≈2초) + 시각 흔들림은 통과, 2개 연속 빠짐(≈3초)은 thin(리뷰 post-fix N1).
- 이전 방식은 "표본이 든 정수 초 칸 비율"이었다. 표본이 서버 초 경계 직후 약 1ms에 찍혀서, 시계 차가 조금만 양수여도 두 표본이 한 칸에 몰려 거짓 thin이 났다(wdrop.py:24, 리뷰 rv8 A1).

### 8.4 분석 장치와 status

```python
# k6/ADR-007/scripts/wdrop.py:215-222
    if dev is None:   # 회차가 실제로 표본한 디스크(host-devices.json) — 장치 번호가 바뀌어도 그 디스크를 본다. 없으면 기본값
        try:
            dev = json.loads((rep / "host-devices.json").read_text())["disk"]
        except (OSError, ValueError, KeyError):
            dev = DEVS_DEFAULT
    # status 파일이 정본(마감을 넘은 회차·단계는 campaign.sh가 status에만 crossed-fstrim을 붙인다)
    st_file = rep / "status"
    status = st_file.read_text().strip() if st_file.is_file() else meta.get("status")
```

- 분석 장치 = 회차의 `host-devices.json` `disk`(없으면 `nvme1n1`). `--dev`로 덮어쓸 수 있다(리뷰 post-fix N2).
- status = `status` 파일이 먼저다. `meta.json`의 status는 회차가 끝날 때 값이라 나중에 붙은 `crossed-fstrim`이 없다(리뷰 post-fix N3).

### 8.5 새 신호 — flush 반감·지속 상승·기준선 (dfad9a68)

총 쓰기 반감(§8.1)만으로는 fio의 꺾임을 놓쳤다. fio에는 data job의 버퍼 쓰기가 있어서, 디스크가 느려져 wal의 fdatasync 수가 먼저 줄어도 **총 MB/s는 0.6배 아래로 안 떨어진다**. 실제로 fio-sync-1은 flush 반감 전후 wMB/s 28.6 → 19.3(약 0.67배)이라 총 쓰기 규칙에 걸리지 않았다(`20261011-fsync-dfad9a68/wdrop.json` `host_flush_drop_pre`·`_post`). 그래서 wdrop.py에 신호를 더했다. 기존 필드는 그대로이고 새 필드로만 더한다.

| 필드 | 무엇 | 대상 |
|------|------|------|
| `host_flush_drop` | 같은 반감 규칙(0.6 × 직전 4칸 중앙값 · 다음 칸도 · 10초 칸 · 부하 구간 안)을 **flush 누적 수**에 | 회차·fio |
| `host_rise` | flush 평균·w_await의 **지속 상승** 시작 | 회차·fio |
| `host_baseline30` | 측정 첫 30초 지표 | fio |
| `host_flush_drop_pre`·`_post` | flush 반감 전후 ±30초 창(측정 구간 안으로 자름) | fio |
| `prefill_to_start_gb`·`gb_since_prefill` | 채우기 시작부터의 누적 GB | fio pgwal |
| `host_bins10` · `wal_trend` | 10초 칸 표 · `wal_bw.1.log` 10초 평균의 기준·최소 | fio |

**flush 반감** — 기존 `host_cum_points`에 누적할 열을 고를 수 있게 하고(`key`·`scale`), flush 수에 `find_drop`을 그대로 쓴다.

```python
# k6/ADR-007/scripts/wdrop.py:337-343 (dfad9a68)
        # flush 반감(리뷰 rv10 B8 — 머리말): flush 누적 수 10초 칸에 같은 규칙. find_drop의 속도 단위는 10^6/s라 flush/s로 되돌린다
        fpts = host_cum_points([dict(r, t=r["t"] - off - k0) for r in samples], dev, 0.0, key="flushes", scale=1)
        fd = find_drop([p for p in fpts if p[0] <= dur], 30)
        gb_at = dict(hpts)
        res["host_flush_drop"] = None if not fd else {
            "s": round(fd["t"]), "gb_since_start": rnd(gb_at[fd["t"]] / 1e9) if fd["t"] in gb_at else None,
            "prev_flush_per_s": rnd(fd["prev_mbps"] * 1e6, 1), "drop_flush_per_s": rnd(fd["drop_mbps"] * 1e6, 1), "next_flush_per_s": rnd(fd["next_mbps"] * 1e6, 1)}
```

`find_drop`은 속도를 10^6으로 나눠 MB/s로 낸다. 그래서 flush에서는 `× 1e6`으로 flush/s로 되돌린다. 그때의 GB는 같은 10초 경계의 장치 누적 쓰기다.

**지속 상승** — flush 지연은 오르는데 flush/s·총 MB/s는 버티는 경우(반감 규칙이 놓친다)를 잡는다.

```python
# k6/ADR-007/scripts/wdrop.py:248-260 (dfad9a68)
def rise_onset(bins, key, factor=2.0):
    vals = [(s, (a or {}).get(key)) for s, a in bins]
    base_v = [v for _, v in vals[:3] if v is not None]
    if len(base_v) < 3:
        return None
    base = statistics.median(base_v)
    out = {"baseline": rnd(base, 3), "factor": factor, "s": None}
    for i in range(3, len(vals) - 1):
        (s, v), (_, v2) = vals[i], vals[i + 1]
        if v is not None and v2 is not None and v > factor * base and v2 > factor * base:
            out.update(s=s, value=rnd(v, 3), next=rnd(v2, 3))
            break
    return out
```

- 칸 = 시작 기준 10초 칸(`bins10` — 부하 구간 안에 온전히 든 칸만), 칸 값 = 그 칸의 `agg`(§3.3 식).
- 기준 = **처음 3칸**의 중앙값. 시작 = 4번째 칸부터 보아, 그 칸과 **다음 칸 모두** 기준의 2배를 넘는 첫 칸.
- `flush_avg_ms`와 `w_await_ms` 둘을 따로 본다. 시작 칸의 누적 GB는 그 칸 시작 이전 마지막 1초 경계 값이다.

**fio 창 자르기(리뷰 B4)** — fio에서는 ±30초 창을 측정 구간 `[0, dur]` 안으로 자른다. 앞의 채우기·기준선과 뒤의 꼬리가 섞이지 않게 하려는 것이다. 회차(kind=rep)는 그대로다.

```python
# k6/ADR-007/scripts/wdrop.py:330-331 · :360 (dfad9a68)
        # fio는 창을 측정 구간 안으로 자른다(리뷰 rv10 B4 — 채우기·꼬리가 섞이지 않게). 회차는 그대로
        clip = (lambda lo, hi: (max(lo, 0.0), min(hi, dur))) if kind == "fio" else (lambda lo, hi: (lo, hi))
…
            res["host_baseline30"] = {k: rnd(v) for k, v in (window(rel, 0.0, min(30.0, dur)) or {}).items()}
```

`host_baseline30`은 측정 첫 30초다. "DB 회차 반감 전과 같은 기준에서 출발했나"를 본다. 실제 fio-sync-1: flush 2,020.12회/s · flush 평균 0.40ms · w_await 0.33ms.

**채우기부터의 누적(`prefill_to_start_gb`)** — 꺾인 지점을 "측정 시작부터"와 "채우기 시작부터(채운 1GiB 포함)" 두 가지로 낸다.

```python
# k6/ADR-007/scripts/wdrop.py:231-239 (dfad9a68)
def dev_bytes_before(samples, dev, t):
    """서버 시각 t 이전(같거나) 마지막 표본의 장치 쓰기 누적(바이트) — 채우기 시작처럼 짧은 구간의 앞을 놓치지 않게. 없으면 None"""
    prev = None
    for r in samples:
        if r["t"] > t:
            break
        if (r.get("disk") or {}).get(dev):
            prev = r["disk"][dev]["write_sectors"] * 512
    return prev
```

```python
# k6/ADR-007/scripts/wdrop.py:362-368 (dfad9a68, 줄임)
            if meta.get("prefill_started"):
                b_pf, b_fs = dev_bytes_before(samples, dev, ts(meta["prefill_started"]) + off), dev_bytes_at(samples, dev, k0 + off)   # 채우기 앞 표본 — 채우기(약 2초)가 다음 표본 전에 끝나도 빠지지 않게
                extra = (b_fs - b_pf) if b_pf is not None and b_fs is not None else None
                res["prefill_to_start_gb"] = rnd(extra / 1e9) if extra is not None else None
                for key in ("host_drop", "host_flush_drop"):
                    if res.get(key) and res[key].get("gb_since_start") is not None and extra is not None:
                        res[key]["gb_since_prefill"] = rnd(res[key]["gb_since_start"] + extra / 1e9)
…
```

- 채우기는 약 2초라 1초 표본 하나 사이에 끝날 수 있다. 그래서 채우기 시작 **이전** 마지막 표본(`dev_bytes_before`)과 측정 시작 **이후** 첫 표본(`dev_bytes_at`)의 차를 쓴다. 채우기 뒤 표본을 시작점으로 잡으면 채운 양이 빠진다(dfad9a68 수정 — 커밋 메시지 "채우기 기준 누적 GB는 채우기 앞 표본에서").
- 시각 변환: meta의 시각은 PC 시계이므로 `+ off`(서버 − PC)로 서버 시각에 맞춘다.
- 실제: `prefill_to_start_gb` 1.08(두 회 모두) — 채운 1GiB(1.07GB) + 기준선 동안의 쓰기.

**결과(`20261011-fsync-dfad9a68/WDROP.md`·`wdrop.json`)**

| | fio-sync-1 | fio-sync-2 |
|--|--|--|
| 총 쓰기 반감(`host_drop`) | 없음 | 없음 |
| flush 반감 | +179초 · 5.21GB(채우기 포함 6.29) · 2,047.9 → 730.6회/s | +179초 · 5.23GB(6.31) · 2,048.1 → 887.5회/s |
| 지속 상승 flush 평균(기준 → 시작 칸 값) | +180초 · 0.401 → 1.227ms | +180초 · 0.405 → 1.185ms |
| 지속 상승 w_await | +180초 · 0.329 → 2.409ms | +180초 · 0.335 → 2.009ms |
| ±30초 flush/s · flush 평균 · w_await | 1,975 → 711 · 0.42 → 1.24 · 0.35 → 4.74ms | 2,048 → 683 · 0.40 → 1.30 · 0.33 → 3.70ms |

두 회가 같은 초·같은 누적량에서 꺾였다. fio-sync-2는 fio-sync-1이 끝나고 40.5초 뒤 시작했는데 기준선(첫 30초 flush 2,023회/s · 0.40ms)이 정상이었다.

**`wal_trend`와 fio lat 로그의 한계** — wdrop.py 머리말(:30-32)이 적었듯, fio의 lat·clat 로그는 쓰기 호출 시간뿐이고 **fdatasync 시간이 없다**. 그래서 sync 지연의 초별 추세는 낼 수 없다(sync는 fio.json 전체 값만). 대신 `wal_bw.1.log`의 10초 평균을 본다. 매 쓰기 fdatasync라 sync가 느려지면 wal 속도가 제한(16MiB/s) 아래로 떨어진다. 실제: fio-sync-1 기준 16,385KiB/s → 최소 2,723KiB/s(+220초, 0.166배).

## 9. 회차가 유효한가 — 사유 표

회차(run.sh `rep_status`)와 fio 실행(fio-disk.sh 판정)은 사유를 쉼표로 이어 `status` 파일에 쓴다. 없으면 `ok`다. 아래는 이번 실험에서 더한 사유만이다(b943a3f0 run.sh:703-737 · fio-disk.sh:177·193-207 · campaign.sh:124, pgwal 사유는 dfad9a68 fio-disk.sh:179-185·272-286·344-358).

| 사유 | 어디 | 언제 붙나 |
|------|------|----------|
| `host-sampler-thin` | 회차·fio | §8.3 커버리지 실패(파일 없음·빈 파일 포함) |
| `host-iostat-missing` | 회차·fio | `iostat-host.txt`에 그 디스크 행이 없다 |
| `host-copy-not-removed` | 회차·fio | 서버 표본 사본을 지우지 못했다(`host-recovery.txt`) |
| `host-device-unexpected` | 회차·fio | 디스크 모델이 `EXPECTED_DISK_MODEL`이 아니거나 disk가 sampled에 없다 |
| `invalid-host-devices.json` | 회차·fio | `host-devices.json`이 없거나 깨졌다 |
| `tmpfs-not-applied` | 회차(tmpfs) | `pg-storage.json`의 fstype이 `tmpfs`가 아니다(파일 없음·깨짐 포함) |
| `tmpfs-low-space` | 회차(tmpfs) | 회차 끝 tmpfs 남은 공간 < `TMPFS_MIN_AVAIL`(기본 512MiB) |
| `disk-preset-on-tmpfs` | 회차(디스크) | 디스크 preset인데 데이터 디렉터리가 tmpfs다 |
| `storage-check-failed` | 회차(디스크) | 저장 위치(fstype)를 읽지 못했다 |
| `db-oom-kill` | 회차(전부) | cgroup `memory.events` oom_kill > 0 또는 `OOMKilled` |
| `db-log-fatal` | 회차(전부) | DB 로그에 `PANIC`·`terminated by signal`·`No space left on device` |
| `compose-down-failed` | 회차(전부) | 내린 뒤에도 pgdata 볼륨·db 컨테이너가 남았다 |
| `fio-exit-<n>` | fio | fio 종료 코드 ≠ 0 |
| `invalid-fio.json` | fio | `fio.json`을 읽을 수 없다 |
| `fio-data-short` | fio | data job 바이트 < 계획 × 0.99 |
| `missing-fio-logs-<job>` | fio | `<job>_bw.*.log`나 `<job>_clat.*.log`가 없다 |
| `fio-logs-not-copied` | fio | 서버에서 로그를 받지 못했다(실행 폴더는 근거로 남긴다) |
| `fio-files-not-removed` | fio | 시험 파일·실행 폴더를 지우지 못했다 |
| `interrupted` | fio | 신호로 중단 |
| `image-build-failed` · `run-dir-failed` | fio | 이미지 빌드·서버 실행 폴더 만들기 실패(바로 끝) |
| `db-running` | fio(pgwal) | seatlab DB 컨테이너가 떠 있거나 확인 실패 — 시작하지 않음 |
| `fio-prefill-exit-<n>` | fio(pgwal) | 채우기 fio 종료 코드 ≠ 0 — 측정 건너뜀 |
| `fio-prefill-short` | fio(pgwal) | 채운 바이트 < `--wal-prefill` — 측정 건너뜀(측정 뒤 `prefill_ok` 거짓이어도) |
| `fio-wal-file-unverified` | fio(pgwal) | 채운 직후 wal.dat 크기를 못 읽음 — 측정 건너뜀 |
| `fio-wal-file-grew` | fio(pgwal) | wal.dat 크기가 채운 직후·정리 직전 중 하나라도 `--wal-prefill`과 다름(덮어쓰기가 아니었다) |
| `crossed-fstrim` | campaign(main 외) | 끝난 뒤 마감을 넘었거나 fstrim이 돌았다(§7.3) |

- 시계 차 사유(`clock-offset-missing`·`clock-offset-large`)는 ADR-007 본측정에서 이미 있었다. 호스트 표본 판정도 그 시계 차를 쓴다.
- main이 아닌 세트는 `ok`만 정상이다. 계단 S4의 '한계 < 첫 단계'(`s4-no-successful-stage`)도 다시 잰다(campaign.sh:321, 리뷰 rv8 A11).
- pgwal의 data 부족은 `fio-data-short`(기준 = 속도 × 시간 × 0.95)다. **wal 부족에는 사유가 없다** — 관찰값(`fio_bytes.wal_ok`·`wal_ratio`, §5.6).
- 머리말과 코드가 다른 곳(dfad9a68에서도 그대로):
  - fio-disk.sh 머리말(:23-24)은 `missing-fio-logs`라고 적었지만, 코드(b943a3f0 :195 · dfad9a68 :352)가 붙이는 실제 값은 `missing-fio-logs-wal`·`missing-fio-logs-data`다.
  - fio-disk.sh 머리말(:34, dfad9a68)은 pgwal 바이트 검증 사유로 `fio-wal-short / fio-data-short`를 적었다. 코드는 `fio-wal-short`를 붙이지 않는다(:346-348 — 같은 머리말 :37도 "wal job 부족은 … 사유를 붙이지 않는다"고 적었다).

## 10. 배운 것과 한계

### 10.1 패턴은 PG의 동기화 특성과 맞아야 하고, 꺾임은 flush로 봐야 한다

(b)는 처음에 불성립이었다가 추가 시험에서 성립했다. **놓친 것은 총 바이트 규칙(②) 하나다** — 옛 fio도 flush 기준으로는 3/3 꺾였다. 패턴 차이(①)는 탐지가 아니라 **비교 가능성**의 문제였다.

**① 옛 fio wal job은 PG와 기준선이 달랐다.** 매 쓰기 fdatasync이긴 했지만 초당 flush 수와 총 쓰기량이 DB 회차와 달랐다.

| | 옛 append (휴식 fio-1) | pgwal (fio-sync-1) | PG DB 회차 |
|--|--|--|--|
| wal 파일 | `fallocate=none` — 끝에 덧붙이며 자란다 | 미리 0으로 채운 1GiB 안을 덮어쓴다 | 미리 0으로 채운 16MB 세그먼트를 덮어쓴다 |
| 측정 첫 30초 flush/s · flush 평균 | 866.6 · 0.44ms | 2,020.1 · 0.40ms | 반감 전 1,749~2,055 · 0.41~0.49ms |
| 그때 총 쓰기 | 65.5MB/s(반감 전 30초) | 29.0MB/s | 반감 전 23.2~28.0MB/s |
| fio.json wal sync 평균(실행 전체) | 2.44ms(p50 1.09 · p90 4.11) | 0.659ms(p50 0.42 · p99 2.11) | — |
| flush 반감 | +119초 · 장치 누적 7.81GB | +179초 · 5.21GB(채우기 포함 6.29) | 호스트 반감 3.26~3.74GB |

출처: append = `20261010-rest-b943a3f0/rest/fio-1/fio.json` + dfad9a68 wdrop.py 재계산(`host_baseline30`·`host_flush_drop_pre`) · pgwal = `20261011-fsync-dfad9a68/wdrop.json`·`fsync/fio-sync-1/fio.json` · DB = `20261010-dc-b943a3f0/WDROP.md`.

- pgwal은 기준선이 DB 회차와 같은 자리(초당 약 2,000회, 0.4ms)에서 출발했다. 옛 append는 초당 약 870회였고 총 쓰기가 두 배 넘었다. 이 상태로는 "같은 누적량에서 꺾이나"를 비교할 수 없다.
- 옛 append의 sync 평균 2.44ms는 **실행 전체** 평균이다. 꺾이기 전 flush 평균은 0.42~0.44ms였다. 그러니 "fsync 1번이 원래 느린 패턴"이라기보다 **초당 횟수·총량이 다르고, 꺾인 뒤 구간이 평균을 끌어올린 것**이다. (이 문서 첫 판과 후속 명세는 2.44ms를 '1회가 느린 패턴'의 근거로 읽었다 — 정정한다.)
- 파일이 자라면 fdatasync가 ext4 저널 커밋까지 기다린다는 해석(§3.1)은 남아 있지만, 저널 쓰기를 따로 잰 것은 아니다.
- 숫자 하나: fio-1의 호스트 flush는 fio 구간 전체 평균 **408.1회/s**(wal IOPS 408.2와 같다, 초별 중앙값 232/s)다. 처음 적었던 389회/s(앞뒤 대기가 섞인 창)는 후속 명세·log에서 408로 정정됐다(`docs/plans/2026-10-11/adr-007-fsync-fio/log.md` 10-11 10:0x 행).

**② 총 쓰기 바이트로는 꺾임이 안 보였다.** fio에는 data job의 버퍼 쓰기가 있다. 디스크가 느려지면 매 쓰기 fdatasync인 wal이 먼저 줄지만, data가 총량을 받쳐 총 MB/s는 0.6배 아래로 안 떨어진다(append 65.5 → 45.0 · pgwal 28.6 → 19.3MB/s, 약 0.67~0.69배). 같은 규칙을 flush 수에 쓰면(§8.5) 둘 다 꺾임이 보인다.

**교훈**

- 디스크 단독 재현은 PG의 **동기화 특성**(덮어쓰기 · 초당 fdatasync 수 · 총 쓰기량)을 맞춰야 DB 회차와 **비교**가 된다. "매 쓰기 fdatasync"만 맞추는 것으로는 부족했다.
- 다만 맞춰도 시간과 누적량은 겹친다(속도 고정 — +179초와 5.2GB를 가를 수 없다). 옛 append는 flush 약 900회/s로도 +119초 · 7.81GB에서 꺾였다.
- 동기화가 병목인 부하에서는 **flush 수·flush 평균**이 1차 신호다. 총 바이트는 버퍼 쓰기가 섞이면 꺾임을 가린다.
- 남은 차이: 꺾이는 누적량이 fio pgwal 5.2GB, DB 회차 3.26~3.74GB로 다르다. 원인은 확인하지 않았다(체크포인트 데이터 쓰기·세그먼트 생성 등 쓰기 구성 차이로 추정 — ADR-007 §7.2 한계).

### 10.2 w_await는 장치 속도가 아니다

- `write_ms`는 요청마다 **대기열에서 기다린 시간까지** 더한 합이다. w_await = 그 합 ÷ 요청 수, 즉 요청당 평균 체류 시간이다.
- 요청이 몰리면 장치가 그대로여도 w_await가 커진다. 휴식 fio-1 구간(이 문서에서 wdrop.py 집계 함수로 계산)은 w_await 7.73ms인데 aqu-sz가 15.3, util 95.6%였다. data job의 버퍼 쓰기가 writeback으로 한꺼번에 내려가며 대기열이 길어진 것이다. DB 회차 반감 전(w_await 0.24ms, aqu-sz 1.86)과 바로 비교할 수 없다.
- 합의 평균이라 분포(꼬리)는 모른다. 꼬리를 보려면 fio의 분위수(층 1)를 봐야 한다.
- 그래서 판정 (a)는 w_await와 **flush 평균**을 함께 본다. flush는 대기열이 덜 끼는 단일 명령이라 장치 쪽 지연에 더 가깝다. 그래도 flush_ms도 같은 방식의 합이다.

### 10.3 시계 차

- 호스트 표본·대기 표본의 시각은 **서버 시계**, k6 구간·fio 시작/끝은 **노트북 시계**다.
- `clock_offset`(lib.sh:151-176)이 SSH 연결 하나를 열어 둔 채 5번 묻고, 왕복이 가장 짧은 표본으로 offset = 서버 시각 − (보낸 시각 + 받은 시각) ÷ 2를 낸다. 오차 ≤ 그 왕복 ÷ 2다.
- k6(또는 fio) 직전·직후 두 번 재고, 분석은 그 **평균**을 서버 시각에서 뺀다(wdrop.py `clock_off`).
- 실제: 휴식 fio-1 = +0.0116 / +0.0113초(왕복 2.4 / 2.9ms), none-default-s4 rep1 = −0.0435 / −0.0516초(`clock-offset.json`). |차| > 0.5초면 회차 사유 `clock-offset-large`다.
- 이 정도 차는 1초 표본·10초 docker 표본에 비해 작다. 그래도 커버리지 검사(§8.3)는 시계 차 보정 전에는 거짓 thin을 냈다.

### 10.4 그 밖의 한계

- **tmpfs 회차의 docker 반감 '없음'은 정의상 당연하다.** tmpfs 쓰기는 블록 장치를 지나지 않아 docker BlockIO에 잡히지 않는다. none-tmpfs-s4 rep1의 db BlockIO는 회차 내내 `147kB / 16.4kB`였다(`timeline-server.jsonl`). 디스크 회차는 `2.11GB → 10.9GB`로 늘었다. 그래서 (c)의 근거는 반감 표가 아니라 처리량·벽 결과와 대기 이벤트다: S4c4200 tmpfs 6/6이 4,200/s 유지(디스크 6/6 붕괴), 그리고 S4 벽의 **서명 변화**. tmpfs S4에도 벽은 있다 — 13단계(+393~406초)로 밀렸고(디스크 벽은 대부분 11~12단계, 이상치 precheck rep1은 8단계), 벽 ±3초 대기 상위가 `CPU:-`·`Client:ClientRead`이며 WAL 대기가 없다(`20261010-dc-b943a3f0/WALL.md`). S4에서 벽이 '사라졌다'고 쓰지 않는다.
- 호스트 지표는 **서버 전체** I/O다. 같은 디스크를 쓰는 다른 프로세스의 쓰기도 섞인다. 측정 중 다른 컨테이너는 0개였다(명세 §0).
- SSD 안쪽(컨트롤러·SLC 캐시·NAND)은 볼 수 없다. sudo가 없어 `smartctl`·`nvme-cli`도 못 쓴다(명세 §0). 커널이 보는 요청 단위 시간까지가 한계다.
- 10초 docker 표본이라 반감 시각의 분해능은 10초다. 호스트 반감도 10초 칸으로 묶는다.
- iostat 사본은 존재만 자동 확인한다. 값 대조는 사람이 한다.
- fio-disk.sh 머리말(:12 — dfad9a68에서도 그대로)은 초당 로그 이름을 `<job>_bw.1.log`처럼 적었다. 실제로는 job 순번이 붙어 data는 `data_bw.2.log`다(`rest/fio-1/`·`fsync/fio-sync-1/`). 검사 코드는 `${j}_bw.*.log` 글롭이라 영향이 없다.
- **꺾이는 지점은 디스크 이력에 거의 매이지 않는다.** 30분 휴식 뒤 fio-3과 첫 fio-1이 같은 자리(+119초 · 7.81GB)에서, fio-3(18.06GB, `rest/fio-3/meta.json`)이 끝나고 72.7초 뒤(`rest/gaps.txt`) 시작한 S4가 다른 디스크 회차와 같은 자리에서 꺾였다. pgwal은 40.5초 쉰 뒤 정상 기준선에서 다시 같은 자리(+179초)에서 꺾였다. 예외는 23초 뒤의 옛 fio-2(+60초 · 3.79GB)뿐이다. 아주 짧은 쉼의 효과는 패턴에 따라 다를 수 있다.
- "DRAM 없는 SSD의 캐시·매핑 동작 때문"은 해석이다. 장치 안을 잰 것이 아니다.

## 11. 코드 위치

| 무엇 | 파일 |
|------|------|
| 호스트 표본(서버 쪽 1초 루프) | `k6/ADR-007/scripts/host-sampler.sh` |
| 표본 시작·장치 사슬·사본 복구·저장 위치 | `k6/ADR-007/scripts/lib.sh` — `host_sampler`·`host_devices`·`host_sampler_kill`·`host_recover`·`pg_storage`·`clock_offset`·`compose_down` (b943a3f0 :72-86, :151-176, :238-326) |
| 회차 안 배선·회차 사유 | `k6/ADR-007/scripts/run.sh` — `preset_env`(:69-78) · `start_pollers` 호스트 루프(:298-305) · `run_rep`(:378-434) · `rep_status` 디스크 사유(:703-737) (b943a3f0) |
| fio 단독 재현(append·pgwal) | `k6/ADR-007/scripts/fio-disk.sh` — pgwal job(dfad9a68 :124-166) · DB 확인(:179-185) · 채우기(:262-287) · 바이트·크기 판정(:325-358) |
| tmpfs 오버레이 | `k6/ADR-007/compose.tmpfs.yml` |
| 세트·마감·fstrim 가드 | `k6/ADR-007/scripts/campaign.sh` — 마감(b943a3f0 :96-133) · disk-rest(b943a3f0 :134-199) · disk-cause(b943a3f0 :200-261) · fsync-fio(dfad9a68 :145-191) |
| 반감 탐지·호스트 지표·커버리지·새 신호 | `k6/ADR-007/scripts/wdrop.py` — `find_drop`·`coverage`·`agg` · flush 반감·지속 상승·기준선·채우기 누적(dfad9a68 :226-286, :330-372) |
| 예시 결과 — 휴식 시험 | `k6/ADR-007/results/20261010-rest-b943a3f0/` (`rest/fio-1/`·`rest/gaps.txt`·`CAMPAIGN.log`) |
| 예시 결과 — 재측정 24회 | `k6/ADR-007/results/20261010-dc-b943a3f0/` (`WDROP.md`·`WALL.md`·`COMPARISON.md`·회차별 `pg-storage.json`·`summary.json`) |
| 예시 결과 — fsync 패턴 fio | `k6/ADR-007/results/20261011-fsync-dfad9a68/` (`WDROP.md`·`wdrop.json`·`fsync/fio-sync-N/`·`fsync/gaps.txt`) |
| 판정·결정 | `docs/adr/ADR-007-counter-upsert-throughput-wall.md` §7.2·§8 |
