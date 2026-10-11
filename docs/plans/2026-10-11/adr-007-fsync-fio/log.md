# log — ADR-007 fsync 패턴 fio 추가 시험

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-11 04:4x~09:3x(답 수신 시각 미기록) | 선행 작업(2026-10-10/adr-007-disk-cause) C5 판정 (a)·(c) 성립 (b)·(d) 불성립 보고 → 사용자 "지금 추가 시험" · 패턴 = WAL 흉내 + 매번 fsync · 패턴당 8GB 2회 · 결정은 시험 뒤 | 인터뷰 |
| 10-11 09:3x | 메인 실측 정정: 이전 fio wal도 매 쓰기 fdatasync였음 — 덧붙이기(파일 자람)라 sync 평균 2.44ms·flush 389회/s vs DB 0.41ms·약 2,000회/s → 핵심 변경 = 미리 채운 파일 덮어쓰기 | 사용자에게 정정 보고 |
| 10-11 09:43 | 명세 합의 "합의, auto로 진행" → SPEC=1 · MODE=auto | 게이트 통과 |
| 10-11 09:44 | 하네스 워커(Opus, 재개) task 01·02 위임 · 같은 시각 문서 워커(Opus) — docs/how-it-works/disk-measurement.md(사용자 요청 "디스크 측정 방식 설계·코드 설명 문서") | 위임 |
| 10-11 09:49 | 워커 회수: b28d3cc4(fio-disk.sh --pattern pgwal · campaign --set fsync-fio) · 오프라인: append job 3조합 HEAD 동일 · main/disk-cause/disk-rest dry-run 동일 · 인자 검증 rc=2 · 스모크 60초 ok: 호스트 flush 2,054/s · flush avg 0.407ms · w_await 0.33ms · 29.4MB/s · wal sync mean 0.45ms p99 0.63ms · 채우기 1.9초 분리 · fio-tmp 비움 | 가정 1 성립(메인 fio.json 교차 확인: wal 16.8MB/s sync mean 0.450ms) |
| 10-11 09:5x | 리뷰 rv10 시작: codex ∥ Opus 워커(미러 b28d3cc4, packet = spec·diff·스모크) | 진행 |
| 10-11 10:0x | 문서 워커 회수: docs/how-it-works/disk-measurement.md 764줄(미커밋) · 메인 대조: find_drop 인용 = b943a3f0 wdrop.py:121-130 일치 · 워커 지적 반영: fio-1 flush 389/s → **408/s**(fio 구간 — 389는 앞뒤 대기 섞인 창, 명세 정정) | 정정 |
| 10-11 10:1x | rv10 종합: codex 4 + opus 6(F1~F6) → B1~B9 전부 채택 · 기각 0 · B7은 명세 §2 충돌 → 사용자 재합의 "관찰값으로 기록"(명세 §2·§5 갱신) | 감사·수정 위임 |
| 10-11 10:2x | rv10 감사(codex, 1회 미러 권한 실패 → 재실행 rc=0): 누락 0 · 방향 보강 3 — B5 크기 전후 2회 + wal_wrapped · B8 flush avg·w_await 지속 상승 탐지(기준 = 첫 3칸 중앙값, 연속 2칸 > 2배) · B9 판정 규칙(느린 상태로 시작한 회차 = 판정 불가, 부정 판정은 PG 같은 기준선 필요 — 분석에서 적용) | 워커에 전달 |
| 10-11 10:09 | 수정 워커 회수: edf77c4e(B1~B9 + 감사 보강) · 9ac16eb3(wal_wrapped false→null 버그) · 오프라인: append job 4조합·pgwal job 동일 · 캠페인 4세트 dry-run 동일 · rc=2 사례 전부 · wdrop 기존 150항목 새 필드 외 동일 · 합성 검사 flush 반감·지속 상승 탐지 · 스모크 2회 ok(첫 30초 flush 1,980~2,004/s · 평균 0.40~0.41ms · w_await 0.32ms · 반감·상승 없음 · wal.dat 크기 전후 1GiB) · 워커 덧붙임: 옛 덧붙이기 fio-1/2/3에 flush 반감 있음(119·60·119초, 912→297~463/s) | 회수 — B2 건너뛰기·B1 거부 경로는 라이브 미확인(코드 읽기) |
| 10-11 10:1x | rv11 post-fix 재점검(codex, 수정자 아님) 시작 | 진행 |
| 10-11 10:2x | rv11 재점검(codex): B1·B2·B3·B4·B6·B7 fixed · B5·B8·B9 partial + 새 결함 3(채운 직후 stat 실패 → 크기 검사 건너뜀 · 채우기 기준 GB가 채우기 뒤 첫 표본에서 시작 · 지속 상승이 기본 표에 없음) → 메인 수정 dfad9a68(측정 건너뜀+fio-wal-file-unverified · dev_bytes_before · 표 열) · 확인: wdrop JSON dc 24·rest 4·smoke 3 9ac16eb3판과 동일 · pgwal dry-run · fsync-fio dry-run 16분 | ledger 종료 |
| 10-11 10:14 | **본시험 시작**: systemd-run --user --unit seatlab-adr007-fs → campaign.sh --sha dfad9a68 --set fsync-fio --id 20261011-fsync-dfad9a68 | 예상 16분 |
| 10-11 10:25 | 본시험 끝: fio-sync-1·2 ok · 간격 40.5초 · wal 되감기 true · wal.dat 1GiB 전후 동일 · wal_ratio 0.688/0.691(관찰값) · 기준선 첫 30초 flush 2,020/2,023회/s · 평균 0.40ms · w_await 0.33ms(PG 같음) · **flush 반감 둘 다 +179초**(fio 시작부터 5.21/5.23GB, 채우기 포함 6.29/6.31GB) · flush/s 1,975~2,048 → 683~711 · flush avg 0.40~0.42 → 1.24~1.30ms · w_await 0.33~0.35 → 3.70~4.74ms · 지속 상승 시작 +180초(둘 다) · 총량 반감은 없음(data가 받침) · wal 10초 최소 16.4MB/s의 0.17~0.18배 · fio.json wal sync mean 0.66ms p50 0.42 p99 2.11 | **(b) 성립** — DB 회차와 같은 서명(flush/s 약 1/3, flush 평균 약 3배)이 fio 단독에서 재현. 위치는 5.2GB(DB 회차 호스트 기준 3.3~3.7GB)로 약간 뒤 · 2회차도 기준선 정상 → 40초 쉼에 회복, 같은 지점에서 다시 꺾임 |
| 10-11 10:4x | C5 최종: (a)·(b)·(c) 성립 · (d) 불성립(선택) → 명세 기준 **확인** · 사용자 질문 "precheck방식이 머야?" → 설명(코드·상황표·ADR-007 H5·H6 실측, 남는 틈 포함) · **사용자 결정: advisory-try → counter-upsert-precheck 전환** · 문서 범위 = ADR-007 §7.2·§8 + disk-measurement.md 마무리 + NEXT·측정로그·이슈 아카이브 | 결정 |
| 10-11 11:0x | **정정(문서 워커 발견, 메인 확인)**: 옛 덧붙이기 fio-1의 sync 평균 2.44ms는 반감 뒤 구간이 지배한 전체 평균 — 반감 전 10초 칸은 flush 약 910~922회/s · flush 평균 0.421~0.427ms · 호스트 쓰기 약 67MB/s(fio-1 host_bins10, dfad9a68 wdrop.py). 그리고 +110~120초(약 7.8GB)에서 flush 0.42→1.9~2.2ms로 꺾였다(총량 67→44MB/s = 0.65배라 총량 규칙 0.6에 안 걸림). 즉 '덧붙이기라 sync 1번이 느렸다'는 메인 해석은 틀렸고, 옛 fio도 서명을 재현했지만 총량 규칙이 놓쳤다. 차이는 flush 빈도(약 910 vs 2,000회/s)와 총 쓰기 속도(약 2배) | 명세 §0 근거 문단 오해석 — 판정 (b) 결론은 불변(오히려 강화) · 사용자 정정 보고 |
| 10-11 11:1x | 문서 워커 회수: ADR-007 259→377줄 · disk-measurement.md 1,077줄 · 메인 확인: 옛 fio-1 10초 칸 반감 전 flush 0.421~0.427ms(위 정정 행) · 문서 듀얼 리뷰 rv12(codex ∥ Opus, 미러 = HEAD + 문서 3 + 결과 증거) | 리뷰 |
| 10-11 11:3x | rv12 종합: codex 5 + Opus H3·M8·L8 → D1~D13 전부 채택(기각 0) — 주요: C5 범위를 '저장 경로'로(D1) · (b) 누적량 창 밖 명시(D2) · tmpfs S4 벽은 13단계로 이동·성격 변화(D3) · none≈advisory-try는 추론·포화점 none 6,403 < precheck 8,336(D4) · ADR-006 §6 전환 규칙 문자 그대로는 미충족 → 사용자 결정(D5) · fio-3→S4 간격 오기(D6) · 이상치 precheck rep1 256초 벽(D11) · 증거 보강: rest 폴더 WDROP.md·wdrop.json 생성(dfad9a68 wdrop.py) | 문서 워커에 수정 위임 |
| 10-11 11:5x | 문서 워커 D1~D13 반영(ADR-007 405줄 · disk-measurement 1,084줄) · rv12 post-fix 재점검(codex): D2·D4~D10·D12 fixed · D1·D3·D11·D13 partial + 새 모순 3(precheck rep1 8단계 벽 누락 · '모든 방식' 귀속 · 방법 표 'SSD 내부 상태') → 메인 직접 수정(12회 중 11회 명시·저장 경로 표현) · 결과 증거: dc errsplit.json·requests-sha256.txt 생성 | 문서 리뷰 종료 |
| 10-11 17:5x | 아카이브 범위 사용자 확인(8건: ADR-006 이월 3 + ADR-007 측정 타당성 3 + 셸·도구 2) · 원 식별자(노출 스캔 입력): seat-reservation-lab · seatlab · HoldSeatService · HoldSeatProcess · HoldLimitPolicy · UserLimitStrategies · CounterUpsertUserLimit · CounterUpsertPrecheckUserLimit · LoadtestDataService · user_hold_quota · product_seat · wdrop.py · fio-disk.sh · campaign.sh · run.sh · s4_wall.py · 192.168.55.164 · jun · labs · nvme1n1 · dm-0 · Samsung SSD 980 · 방식명(counter-upsert·precheck·advisory-try) · 셀명(S4c4200 등) · ADR 번호 · 캠페인 id · 리뷰 id · 커밋 SHA · 작성자명 | 아카이브 워커(Opus) |
| 10-11 18:05 | 아카이브: worktree study-note-wt-archive-1011 · 브랜치 archive/2026-10-11(기반 archive/2026-10-09 1f04dca0 — 미push 배치에 이어서) · 77463530 shell/exit-status-semantics ⓒ · 9efcec83 testing/performance-measurement-validity ⓒ(2·6번) · ca821bc2 database/rolled-back-write-cost ⓐ · 3b4f6080 infra/sustained-sync-write-degradation ⓐ · fea6ff92 reliability/edge-detection-on-raw-signals ⓒ · cb390232 concurrency/cancellation-reachability ⓒ · 8f504714 data/absent-vs-empty ⓒ · 노출 스캔 7커밋 추가 행 0건(메인 재확인 0건) · 출처 없는 수치 제거(trap '약 45초' 등) | 아카이브 완료 — push(1009+1011 묶음)는 사용자 확인 대기, main은 1커밋 앞서 있어 ff 불가 가능 |

## 리뷰 ledger

- review packet: rv10 base b943a3f0 → b28d3cc4 · scratchpad/rv10/mirror(_packet: spec·diff·smoke)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| B1 | 1 | codex P1 | campaign.sh:165-171 | 채택 — fio 전 seatlab DB 컨테이너 없음 확인 | fixed | edf77c4e |
| B2 | 1 | codex P2 + opus F2 | fio-disk.sh:255 | 채택 — 채우기 실패·부족이면 본 측정 건너뜀 | fixed | edf77c4e |
| B3 | 1 | codex P2 | fio-disk.sh:71·295 | 채택 — 0m 등 0 값 속도 거부 | fixed | edf77c4e |
| B4 | 1 | codex P2 | wdrop.py:253 | 채택 — fio 회차 전후 창을 fio 구간으로 자름 | fixed | edf77c4e |
| B5 | 1 | opus F1 | fio-disk.sh(remove_run 전) | 채택 — wal.dat 크기 meta 기록(= prefill 기대) | fixed | edf77c4e·9ac16eb3 |
| B6 | 1 | opus F3 | campaign.sh:79 · fio-disk.sh:79 | 채택 — runtime 상한 불일치 dry-run에서 검출 | fixed | edf77c4e |
| B7 | 1 | opus F4 | fio-disk.sh:308 · campaign.sh:176 | 채택(명세 재합의 "관찰값으로 기록") — pgwal wal 부족 = 관찰값 | fixed | edf77c4e |
| B8 | 1 | opus F5 | wdrop.py:249 | 채택 — 호스트 flush/s에도 반감 규칙 + 결과 표 | fixed | edf77c4e |
| B9 | 1 | opus F6 | 분석 | 채택 — 2회차 첫 30초 vs 1회차 기준값, 누적 GB를 prefill_started 기준도 보고 | fixed | edf77c4e |

## 생략한 검증

- 없음
