# log — ADR-007 후속: 디스크 원인(C5) 확인

## 타임라인

| 시각 | 사건 | 결과 |
|------|------|------|
| 10-10 12:40 | 사용자 결정: ADR-007 전환 판정 보류 — "디스크 원인 먼저 확인하고 결정하자". 인터뷰: 방법 4종 전부(재측정+iostat · fio · tmpfs · 휴식 시간) · 최소 셀(디스크 12 + tmpfs 12) · ADR-007에 이어 기록 | 명세 작성 |
| 10-10 12:55 | 명세 합의 "합의, auto로 진행" → set-state spec-approved · mode auto | SPEC=1 MODE=auto |
| 10-10 12:57 | 가정 1 조기 실증(메인, 읽기 전용 SSH): 커널 6.8.0-136, `/proc/diskstats` nvme1n1·dm-0(= ubuntu-lv) 20열(flush 수·시간 포함) sudo 없이 읽힘 · `/proc/pressure/io` 읽힘 | **가정 1 성립** |
| 10-10 13:00 | task 01·02 위임(Opus·high 워커, 백그라운드): 호스트 샘플러(diskstats·meminfo·PSI 1초 + iostat 교차) · tmpfs preset · 쓰기 반감 탐지 스크립트 · fio 스크립트 · 캠페인 배선 + 스모크 3종 | 회수 대기 |
| 10-10 13:20 | ADR-007 §7.1(본측정 결과·가설 판정)·§7.2(후속 계획)·§8(보류)·§2 C5·상태 행 작성(메인). 교차 확인 중 정정: H6 뒤 절(남는 틈은 드물다) **반증** — S7-m1 precheck 틈 포함 100/100(매 회차). 판정기 위반은 none-s2(대조군)뿐 확인 | 문서 리뷰는 §7.2 뒤 한꺼번에 |
| 10-10 13:40 | 사용자 지적: ADR-006에 ADR-005 counter → counter-upsert 코드 변화 스니펫 없음 → ADR-006 §2.1(counter의 prepare+UPDATE vs counter-upsert 한 문장, 비교표: 빌림 3/2회 → 1회 등)·ADR-007 §3.1(precheck acquire·assertSeatAvailable, 상황별 표) 추가 — 스니펫은 UserLimitStrategies.kt·HoldSeatService.kt 실파일에서 복사, hasPrepare 기본 false 확인 | 문서만 |
| 10-10 13:55 | 사용자 지적: 스니펫이 override 정의뿐이고 실제 실행 흐름(hold 내부)이 없음 → ADR-006 §2.1을 (1) 실행 흐름(Controller → HoldSeatService.hold → 3b PessimisticNoWaitHoldStrategy tx.execute → HoldSeatProcess.hold, 끼어드는 자리 ⓐ prepare·ⓑ around·ⓒ acquire·ⓓ check + 인터페이스 기본값 + 자리별 표) / (2) 방식별 코드로 재구성. ADR-007 §3.1은 ⓒ만 바뀐다고 연결. 실파일 확인: DbLockHoldStrategies.kt·HoldSeatProcess.kt·UserLimitStrategies.kt 인터페이스·ActiveUserLimit | 문서만 |
| 10-10 14:15 | 사용자 요청: ADR-005도 같은 형식(실행 흐름 안에서 방식별 구간) → §2.1.1 실행 흐름(ActiveUserLimit·Controller·Service·3b·Process·인터페이스 기본값·HoldLimitPolicy) 추가, §2.2~2.8 스니펫을 방식별 ⓐ~ⓓ 흐름 + 실코드로 교체. 기존 오류 정정: L3 스니펫이 around에서 ensureQuotaRow를 부르는 것으로 적혀 있었음 → 실제는 prepare. ADR-006·007에 내가 적은 3b 락 SQL 'FOR UPDATE NOWAIT' → 실측 'for no key update nowait'(ADR-003 §288·317)로 정정(ADR-006 8행 기존 표기 포함) | 문서만 |
| 10-10 14:30 | 사용자 요청: ADR-005에 쿼터 테이블 설명 추가 → §2.1.2(V5 DDL 실파일, 뷰가 아닌 이유, PK = 락 단위, 방식별 사용 표, 비정규화 cnt 유지·대가). 질문 'advisory + 쿼터 테이블 감사' 답변(측정 안 한 조합 — 판정기 v_over_limit_users가 이미 COUNT로 감사함을 LoadtestDataService.kt에서 확인) | 문서만 |
| 10-10 14:55 | 사용자 요청: 트랜잭션·사용자 락 설명을 ADR-005에 정리 + 별도 동작 방식 문서 → ADR-005 §2.1.3(앱 tx = DB tx·같은 커넥션 조건·MANDATORY·약속형 락·줄이는 작업·xact 자동 해제·세션 락·장기 tx 대비책) + 새 문서 docs/how-it-works/hold-request-flow.md(흐름도·전략 패턴·방식별 표·트랜잭션·락 범위표·쿼터 테이블·xact·확정/만료·코드 위치). 확인한 실파일: DbLockHoldStrategies.kt:131(JpaTransactionManager 커넥션 노출 주석)·ConfirmReservationService·ExpireHoldsService·ProductSeat·ProductSeatRepository·UserLimitStrategyType(12종)·application.yml(타임아웃 설정 없음). 인용 정정: S2 none 수치를 ADR-006 §5.3 ① 값(L4 17 [11–24])으로 | 문서만 |
| 10-10 20:55 | task 01·02 회수(Opus·high 워커, 기준 697ae198): 커밋 4c0a35b8(하네스)·236ae1cc(wdrop 부하 구간 한정). 오프라인 dry-run 117 HEAD와 바이트 동일 · disk-cause 24 · disk-rest 5단계 · rep_status 92/92 · wdrop 옛 캠페인 81/81 06a 재현. 스모크 3종 ok: (a) 디스크 counter-upsert S4 — 호스트 표본 100%, docker 반감 k6 +364s 9.26GB, 같은 때 nvme1n1 w_await 0.29→1.21ms·flush 평균 0.44→1.58ms·wMB/s 27.7→12.4(**가정 1·판정 (a) 신호**), 벽 361s · (b) tmpfs — 데이터 tmpfs 확인, db 549MB·WAL 1.06GB·메모리 최고 1.87/8GB(**가정 2 성립**), 반감 없음, 벽 409s(13단계), 엄격 한계 6,435(디스크 4,300) · (c) fio 2G ok, fio-tmp 비움, 이미지 seatlab-fio:alpine3.20(14.5MB) 서버에 남김. 메인 교차 확인: smoke-dc-a·b status ok, wdrop.py 표 재현. 워커 범위 밖 선택 4건(host-copy-not-removed 사유·pg-storage.json·호스트 샘플러 stdin 전송·fio 구성/휴식 프로토콜 재개 불가)은 리뷰 대상에 포함 | task 03 리뷰 |
| 10-10 21:00 | task 03 듀얼 1패스 시작 — base 697ae198, OUT=scratchpad/rv8(packet/·results/·mirror/). 1차 미러 생성이 tracked 결과 폴더까지 복사해 **노트북 디스크 가득 참** → 미러 즉시 삭제(내 임시물), results 제외해 재생성(2.7MB). 디스크 여유 3.4GB. 보안 스캔: compose의 실험용 DB 비밀번호 'seat'·사설 IP 192.168.55.164(이전 리뷰 rv7과 같은 판정 — 실험 고정값·내부망, 외부 비밀 아님) 외 0. Opus 워커 ∥ codex(medium, read-only, cwd=미러) 병렬 | 회수 대기 |
| 10-10 21:15 | 리뷰 회수: Opus F1~F7 + Q1~Q3, codex F1~F7(rc=0). 메인 종합 A1~A12 전부 채택·기각 0(합침: opus F4+codex F3, opus F6+codex F5, opus Q1+codex F4, opus Q2+codex F2, opus Q3+codex F6). A1은 smoke-dc-a 실데이터로 재현(off=0.002 → 93.3%). codex 종합 감사 백그라운드 시작(rv8/results/audit.md). 수정은 같은 하네스 워커에 이어 위임(A1~A12) | 수정·감사 대기 |
| 10-10 21:08 | codex 종합 감사 회수(rc=0): 누락 0, 수정 방향 조정 4건 — A6(배포 선행만으로 '바로 다음' 아님 · DB를 fio 중 띄우지 않음 · 실제 틈 기록), A9(시작이 아니라 **끝**이 마감 전 · 넘은 회차는 crossed-fstrim으로 제외), A10(정리는 이번 시도 사본·fio-tmp만, 증거 회수 실패 시 폴더 보존), A12(본 캠페인 eval 경로 유지 — disk-rest 호출만). 전부 채택해 워커에 추가 전달 | 수정 대기 |
| 10-10 22:54 | 수정 워커 회수(Opus): e754dab4(A1~A12 + 감사 4건) · 049a49c0(fio 중단 신호 즉시 처리) · 오프라인: main dry-run 697ae198과 바이트 동일·117호출, disk-cause 24호출 278분, disk-rest 5단계 72분, rep_status 117/117, wdrop 구 캠페인 81/81 동일, 마감 가드 rc=5 · 라이브: fio 2G ok(data 2,147,483,648B), TERM 6초 처리, tmpfs S4 ok(드롭 없음·벽 413초), default S4 ok(드롭 +373초 9.25GB·w_await 0.28→1.34ms·flush avg 0.44→1.85ms·벽 309초) · 이벤트 원시각은 워커 보고 | 회수 |
| 10-10 22:56 | 메인 교차 확인: campaign.sh:75 fio-size 정규식·:107-125 guard_start/mark_crossed·:321 okre=ok(비main) 실코드 확인 · rv8 미러 삭제 · rv9 post-fix 재점검(codex, 수정자 아님) 시작 | 진행 |
| 10-10 23:00 | rv9 post-fix 재점검(codex gpt-6-sol, read-only 미러, 수정자 아님): A1·A2·A5·A6·A7·A10·A11·A12 fixed · A3·A4·A8·A9 partial · 새 결함 3(N1~N3) | 종합 → N1~N3 채택, A3·A8 잔여는 변경 불요(ledger 사유) |
| 10-10 23:08 | b943a3f0 커밋(wdrop.py·run.sh 주석) · 확인: 공백 시험 3종 · smoke-dc-rv8-a 값 워커 보고와 동일 · 구 캠페인 wdrop 117/117 HEAD판과 동일 · status 우선 표시 · dry-run 휴식 72분·24회 278분 · rep_status 시험 재실행 중(노트북 단일 코어 ~40분, 결과는 다음 행) | 리뷰 ledger 종료 |
| 10-10 23:12 | **재측정 시작**: `systemd-run --user --unit seatlab-adr007-dc` → campaign.sh --sha b943a3f0 --set disk-rest(--id 20261010-rest-b943a3f0) 다음 --set disk-cause(--id 20261010-dc-b943a3f0) · 재시작 정책 없음(disk-rest 중단 시 새 id 필요) · 직전 디스크 부하 = 스모크 default S4 22:54 끝(fio-1 전 약 18분 쉼) · 예상 끝 휴식 00:25 → 24회 약 05:05 | 마감 10-12 10:21 전 |
| 10-10 23:20 | 휴식 fio-1 ok: data 16,106,127,360B(=기대) · wal 1.47GB · data 36MB/s(rate=35m) p99 17µs · wal 3MB/s p99 45µs · 호스트 반감 없음 | 관찰 |
| 10-10 23:27 | 휴식 fio-2 ok(간격 23.0초): data 36MB/s p99 20µs · 반감 없음(연속 약 35GB) · 30분 휴식 시작 | 관찰 — 판정은 05에서 |
| 10-10 23:45 | rep_status 시험 b943a3f0: pass=117 fail=0 · 원본 변경 0건 | 안전선 ② 완료 |
| 10-11 00:05 | 휴식 fio-3 ok(fio-2 끝 → fio-3 = 1824.1초): data 36MB/s p99 15µs · 반감 없음 | 관찰 |
| 10-11 00:16 | 휴식 S4(counter-upsert·default, fio-3 끝 → k6 시작 72.7초) ok: docker 반감 +371초 9.19GB · 호스트 반감 +379초 · wMB/s 28.0→13.1 · w_await 0.25→1.13ms · flush avg 0.41→1.64ms · 벽 373초 · REST done fail=0 · disk-cause 24회 시작(00:16:32) | 관찰 — 반감 시점이 디스크 이력(직전 35GB/18분 쉼)과 무관해 보임, 판정은 05 |
| 10-11 04:15 | disk-cause 24회 끝: 24/24 ok · CAMPAIGN done 비정상 0 · dc-exit=0 · 마감·fstrim 표시 없음 | 측정 완료 |
| 10-11 04:3x | 분석(summarize·wall·wdrop·compare): docker 반감 디스크 16/16(S4 +353~367초 8.5~8.9GB · S4c4200 +154~156초 8.9~9.5GB) · tmpfs 0/12 · 반감 때 nvme1n1 w_await 0.24~0.30→0.76~2.16ms · flush avg 0.41~0.49→1.15~2.22ms · 호스트 기준 반감은 k6 시작 뒤 nvme1n1 누적 3.3~3.7GB · tmpfs S4 엄격 한계 none 6,401 / counter-upsert 6,445 / precheck 6,444(디스크 4,298 / 2,866 / 4,171) · S4c4200 tmpfs 6/6 4,200/s 유지(최악 p99 4~6ms) vs 디스크 6/6 붕괴(2,833~2,899/s) · tmpfs 대기 이벤트에 WALWrite·WALSync 없음 | C5 (a) 성립 · (b) 불성립(fio 3회 반감 없음) · (c) 성립 · (d) 불성립 → 명세 정의상 '확인' 아님, '반증'도 아님 |
| 10-11 10:0x | 정정(문서 워커 지적, 메인 확인): tmpfs 회차 'docker 반감 0/12'는 **구조상 당연** — tmpfs 쓰기는 블록 장치를 안 거쳐 DB 컨테이너 BlockIO가 회차 내내 거의 0. (c)의 근거는 처리량·벽(tmpfs S4 엄격 한계 6,401~6,445 · S4c4200 6/6 유지)과 대기 이벤트(WALWrite·WALSync 없음)다 | 사용자 보고 정정 |
| 10-11 10:4x | 후속 작업 `2026-10-11/adr-007-fsync-fio`의 PG 같은 fsync 패턴 fio에서 (b) 성립(+179초 5.2GB · flush/s 약 1/3 · flush 평균 약 3배) → C5 확인 · 사용자 결정: counter-upsert-precheck 전환 | 판정·결정은 후속 작업 log가 정본 |
| 10-11 11:0x | 정정(문서 워커): docker 반감 '디스크 16/16'은 스모크 포함 수 — dc 캠페인 디스크 회차는 12/12 · 옛 fio도 flush 기준으론 반감 있었음(후속 log 11:0x 행) | 정정 |

## 리뷰 ledger (中↑)

- 루프 1(rv8, base 697ae198, 中 듀얼 1패스): A1~A12 — 아래 표. 종합표 원본: scratchpad rv8/results/synthesis.md

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop |
|---|---|---|---|---|---|---|
| A1 | 1 | opus | wdrop.py:84 · host-sampler.sh:54-57 커버리지 거짓 thin | 채택 | fixed | e754dab4 · b943a3f0(공백 한도 2.5초 — post-fix N1) |
| A2 | 1 | opus | run.sh:698 · lib.sh:76 디스크 회차가 tmpfs 볼륨 | 채택 | fixed | e754dab4 |
| A3 | 1 | opus | lib.sh:276-296 tmpfs ENOSPC·OOM·PANIC 미판정 | 채택 | fixed | e754dab4 (재점검 '재시작 횟수 미확인' — compose에 restart 정책 없음: 컨테이너 죽으면 회차 실패, PG 내부 재기동은 'terminated by signal'로 잡음 → 변경 불요) |
| A4 | 1 | opus+codex | lib.sh:234·260 · host-sampler.sh:18 · wdrop.py:84 장치 고정·대상 카운터·iostat 누락 | 채택 | fixed | e754dab4 · b943a3f0(분석 장치 = 회차 host-devices.json — post-fix N2) |
| A5 | 1 | opus | wdrop.py:134 ms 2^32 랩 | 채택 | fixed | e754dab4 |
| A6 | 1 | opus+codex | campaign.sh:103·108 · fio-disk.sh:138 휴식 시험 틈 | 채택 | fixed | e754dab4 |
| A7 | 1 | opus | fio-disk.sh:168 harness=HEAD | 채택 | fixed | e754dab4 |
| A8 | 1 | opus+codex | fio-disk.sh:51·59·71 exitall·바이트 검증 | 채택 | fixed | e754dab4 (재점검 'wal·총 바이트 미검증' — wal은 time_based라 기대 크기 없음, 기록만 → 변경 불요) |
| A9 | 1 | opus+codex | campaign.sh:236 fstrim 마감 | 채택 | fixed | e754dab4 · b943a3f0(wdrop가 status 파일 정본 — post-fix N3) |
| A10 | 1 | opus+codex | fio-disk.sh:94 중단 정리·SIGHUP | 채택 | fixed | e754dab4 · 049a49c0 |
| A11 | 1 | codex | campaign.sh:232 s4-no-successful-stage 면제 | 채택 | fixed | e754dab4 |
| A12 | 1 | codex | campaign.sh:103·108 eval 인자 검증 | 채택 | fixed | e754dab4 |
| N1 | post-fix | codex | wdrop.py:90 표본 1개 빠짐 + 지터 2.001초 공백 → 거짓 thin | 채택 | fixed | b943a3f0 MAX_GAP_S 2.5 — 1개 빠짐+1ms/300ms 통과, 2개 빠짐 thin 확인 |
| N2 | post-fix | codex | wdrop.py:288 분석 --dev 기본 nvme1n1 고정 — 장치 번호 바뀌면 다른 디스크 분석 | 채택 | fixed | b943a3f0 회차 host-devices.json disk 사용 |
| N3 | post-fix | codex | campaign.sh:124 crossed-fstrim이 status만 — wdrop는 meta.json ok 표시 | 채택 | fixed | b943a3f0 status 파일 우선 — 사본에 crossed 넣어 표시 확인 |

## 생략한 검증

- (없음)

## 완료 요약
