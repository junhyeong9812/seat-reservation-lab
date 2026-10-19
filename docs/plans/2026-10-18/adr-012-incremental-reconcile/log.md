# log — ADR-012 카운터 증분 대조 (+ ADR-013 Kafka·복제본 분리)

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-18 00:50 | 사용자 "다음 ADR 명세 작성 시작하자" · 브랜치 feat/adr-012-incremental-reconcile(main 93dd610a 위) · 인터뷰 4회(16문항) — 답은 명세 §0 표 | — |
| 10-18 01:05 | 인터뷰 중 확인: S3e 주입은 DB 직접 수정(lib.sh:541-543 — D 행 삭제 · L1·L2 −1 · H +1) → 메모리 집합은 주입을 못 봄 · updated_at은 D를 못 봄 → 사용자 '증분 + 안전망 합산' 판정 · 마이그레이션 번호: migration V1·2·3·5·6·7 · migration-unique V4 · migration-trigger V8 → 새 V9 | — |
| 10-18 01:10 | 사용자 "외부 구조는 별도 폴더에 앱·도커를 따로 두고 158에 띄울 수 있게" → 외부 앱 = updated_at 방식(메모리 집합은 다른 프로세스가 볼 수 없음) · 서버 .158 · updated_at 갱신 주체 트리거·앱 문장 둘 다 | — |
| 10-18 01:20 | 사용자 "DB도 조회용과 커밋용으로 분리하고, 선점 좌석을 카프카로 쌓고 앱에서 비교해 대조하는 모델을 비교하는 거 아니야?" → 범위 재합의: 비교 = M0~M4 전부 · M4(Kafka + 읽기 복제본 + 외부 대조 앱 — .158 · CDC·아웃박스 둘 다)는 **ADR-013으로 나눔** · 번호 이동 2칸(012~018 → 014~020) · 사용자 요청: 선택지·고르지 않은 대안을 ADR에 기록 + README를 ADR-011까지 최신화 | 명세 초안 작성 — 합의 대기 |
| 10-18 01:45 | 사용자 "그대로 합의, 158 범위도 제안대로 진행하자" → set-state spec-approved · mode auto · 명세 §4 .158 범위 확정 | task 02 번호 이동 8차 시작 |
| 10-18 01:55 | task 02 번호 이동 8차 워커(Opus) 위임 · .158 읽기 전용 점검(가정 2 조기 확인): CPU 6 · 메모리 15GiB(가용 약 9) · 디스크 가용 150G · Docker 29.1.3 · Compose v5.0.0 · 가동 78일 · load 0.08 · 컨테이너 7개 가동(다른 서비스 — 이름만 확인, 건드리지 않음) · 쓰는 포트 22·53·6379·8090·13000·13001·15001·15100(8103 비어 있음) · `~/labs` 없음 · .164까지 RTT 0.30ms · .164:22 도달(15432는 .164 DB가 꺼져 있어 스모크 때 확인) | 외부 앱(ADR-012)에는 충분 · ADR-013 복제본+Kafka는 가용 메모리 약 9GiB에 다른 서비스와 같이 돌아 빠듯할 수 있음 — ADR-013 명세에서 다시 봄 |
| 10-18 02:20 | task 02 회수(Opus): 2996d57a(docs 24파일 — git mv 7 · 8차 메모 18파일 + ADR-011 새 메모) · 2ee4709a(코드 주석 3줄 · compileKotlin 통과) · 메인 확인: design-v2 113·133·449의 옛 'ADR-013'은 5차 때 밀린 채 남은 '확정 원자성' 참조 → 016으로(+2면 015 — 문맥·IV-009-1로 016 맞음) · 파일 7개 rename · 코드 주석 016·015·016 · task 01 README(1d9862b1) · task 03 ADR-012(§1~§6 측정 전)·ADR-013(배경·선택지)·README 2행 01d82fad · 측정 전 고정 문서 듀얼 리뷰 rv36(Opus ∥ codex) 시작 | 리뷰 대기 |
| 10-18 03:10 | rv36 회수: Opus 높음 6·중간 8·낮음 5 · codex 높음 7·중간 4(겹침 다수) → 사용자 결정 4: 안전망 10분 + 비용 측정 · 측정 전용 앱 경로 주입 추가 · 고르는 순서 탐지 범위 우선(트리거 > 메모리 > 앱 문장) · 외부 앱만 성공 시 A+C 유지 | ADR-012 §2~§7 다시 씀 |
| 10-18 03:25 | 사용자 "10분 안전망은 왜 필요한가" → 설명(증분이 못 보는 갈래 — DB 직접 수정·행 삭제·실제 값 쪽만·재시작) · 사용자 "매시간 2시간 범위만 처리하도록 별도 배치 조절 — 데이터가 계속 쌓이면 10분 안에 전체를 못 도니" → 메인 의견(창의 양은 도착률 × 2시간이라 상한 아님 · 오래된 쌍 영영 안 봄) → 사용자 선택 '예산 있는 이어서 훑기 + 최근 창 우선'(1분마다 DB 2초 · 앞 절반 최근 2시간 창(`seat_hold.id`·`reservation.id` 범위) · 나머지 전체 커서 · 전체 한 바퀴 시간 기록·1시간 초과 경고) · R7 추가 | 명세 §0·README 갱신 |
| 10-18 03:50 | rv36 post-fix 재점검(codex) 신규 4: 최근 창 처리량(1분 약 25만 행 대 실행당 약 1초) · id 할당 순서 ≠ 커밋 순서 · R7 예산에 복구·청크 상한 없음 · 효과 셀이 고부하 2분 약속을 반증 못 함 → 반영: 최근 창 자기 커서 + 적체 기록·경계 건너뜀 경고 · 직전 30초 id 범위 다시 읽기 · 예산 = 탐지+복구+기록 · 청크 ≤ 200ms 설계·500ms 반증 · R4 2분은 '적체 없을 때' · 고부하 적체는 기록 · 한계 §7-2 | 측정 전 고정 문서 리뷰 종료(설계 문서 — 듀얼 1패스 + 재점검 1회) |
| 10-18 04:05 | 사용자 ".164 말고 .9랑 .158 두 서버로 테스트" → .9 읽기 전용 점검(사용자 요청 — 이름·사양만): Ryzen 5 3550H 4코어 8스레드 · 메모리 15GiB(가용 약 12) · NVMe 512GB(여유 384G) · Docker 29.1.3 · 컨테이너 6개(다른 서비스) · `~/labs` 없음 · .158까지 RTT 0.26ms → 보고: 두 서버 모두 DB 14GB + 앱 2GB를 못 담고(전체 15GiB) · L4(앱 4 + DB 4 CPU)가 빠듯 · 다른 서비스와 상호 간섭 → 선택지(역할 분리 · .164 유지) 제시 → 사용자 '.164 측정 + .158 외부 앱'(명세 그대로) | 명세 변경 없음 · .9는 이 ADR에서 더 쓰지 않음 |
| 10-18 04:30 | 1단계 하네스 워커(Opus) 위임(세트 adr012s1 · 후반 창 · 정상 구간 CPU · 바퀴 지표) · 사용자 "싯 리저베이션 랩 안에 별도 앱 프로젝트를 코틀린으로 올리고 기존 앱은 main-app 폴더로 옮겨 1개 깃에 2개" → 메인 확인: 하네스 compose 8개 `context: ../..`·루트 Dockerfile · run.sh 경로 검사(`src build.gradle.kts Dockerfile`) → 선택지 A(main-app 이동)·B(루트 유지) 제시 → 사용자 **A** · 시점 = 1단계 측정 뒤 · 명세 task 04b 추가(동작 무변경 리팩토링 — refactoring.md 순서) | 명세 갱신 |
| 10-18 06:15 | 1단계 하네스 워커(Opus) 회수: 1c9887a0(campaign.sh·tail.py — 코드만) · 워커 확인: 기존 6폴더 TAIL.md diff 0 · 기존 세트 dry-run conditions·order diff 0 · soakrc c 회차 새 지표(C 바퀴 28 · 정상 구간 중앙 13.2초·최대 25.5초 · 바퀴 중 DB CPU 270% 대 밖 164%) · F는 합성 회차로만 확인 · 메인 확인: adr012s1 dry-run(9회 · 회전 noc c f / c f noc / f noc c · 326.7분) · f 줄 = c 줄에서 `--cond A+F --cleanup schedule`만 다름(run.sh:10·186 — F 별도 컨테이너 대조 60s) · README 표 초안 반영 · 해석 주의: F 컨테이너 한도 cpus 1·mem 1g(compose.reconciler.yml:29-30 — ADR-009와 같은 구성) · Hikari는 업무 앱 풀만 | 하네스 듀얼 리뷰 rv37(Opus ∥ codex) 시작 · soakrc TAIL 재생성 회귀 메인 재확인 중 |
| 10-18 07:40 | rv37 반영 워커(Opus) 회수 584dbf4c(tail.py 하나 — RH-1~5) · 6폴더 사본 전·후 TAIL.md cmp 동일(sha 표 워커 보고) · 합성 f·fnostats·cnoruns로 포화·결측 사유 확인 · 메인 확인: 렌더 표 칸·`cpu_limits` NanoCpus 경로(tail.py:384-399) · 워커가 보수적으로 고른 것: 정상 구간 공백 기준도 9초 · 결측 회차도 조건 묶음에 남김(R1 판정에서 메인이 뺌 — ADR-012 판정 유효 조건) | post-fix 재점검(codex) |
| 10-18 08:20 | post-fix 재점검(codex) 신규 2: RH-7 기각 · RH-8 메인 직접 수정·확인 | 하네스 리뷰 종료(듀얼 1루프 + 재점검 — 신규 실결함 1건 반영) · 다음: .164 스모크 |
| 10-18 08:35 | PC 점검: 다른 컨테이너 ccle-postgres-1 하나(healthy, 2일 가동 — 재시작 반복 없음) · load 7.1(24코어) · 상위 CPU는 데스크톱·IDE · F 조건 스모크 시작(run.sh `--cond A+F --cleanup schedule` 12구간 · --probes · tmpfs 12 · d828ba00 · id smoke-adr012s1-f) | 대기 |
| 10-18 13:23 | F 스모크 끝(13:10:44~13:22:29 · 상태 ok · rc 0): tail.py --rep — 기대 출처 reconcile-proc·F 컨테이너 · a12_missing·a12_warn 비어 있음 · F 한도 1코어(app-config.json NanoCpus에서 읽음) · F 바퀴 9(정상 구간 7 · 중앙 3.8초·최대 5.5초) · 바퀴 중 F CPU 평균 6.4%·최대 12.5%(포화 0) · 바퀴 중 DB CPU 215% 대 밖 148% · late_unavailable·steady_sparse는 스모크가 12구간(6분)이라 예상대로 | 하네스 검증 완료 → 1단계 캠페인 시작 |
| 10-18 13:24 | task 04 1단계 캠페인 시작: `campaign.sh --set adr012s1 --sha ad36e72e --k6-bin ~/.local/opt/k6-v2.2.0/k6`(setsid 분리 · 시작 로그 results/adr012s1-launch.log) → results/20261018-adr012s1-ad36e72e · 9회 · 예상 326.7분(끝 약 18:51) | 측정 중 |
| 10-18 19:02 | 세션 fork(새 세션 0961cd42 — 이전 1663be45의 대화 이어받음) · 게이트 상태가 새 세션 파일로 비어 있음 → 사용자 "그대로 auto로 다시 기록" → set-state spec-approved·mode auto(같은 명세) · 이전 세션 종료 알림 대기 멈춤 → 다시 걸음 · 캠페인은 setsid 분리라 영향 없음(8/9 ok · c rep3 18:49 시작) | 측정 계속 |
| 10-18 19:30 | 1단계 캠페인 끝(13:24~19:30 · CAMPAIGN done — 비정상 회차 0 · 9/9 exit 0) | summarize(조건 3) → tail.py --set adr012s1 집계 |
| 10-18 20:40 | 1단계 집계 끝(summarize 3 rc 0 · tail.py rc 0 · 누락·의심 없음 · 결측·주의 없음) — TAIL.md 4~6절: 바퀴 시간 정상 중앙 C 12.69 [12.66–13.28] 대 F 13.15 [12.92–13.20](겹침) · 후반 중앙 C 21.10 [19.87–21.48] 대 F 22.17 [21.78–22.60](F가 범위 비겹침으로 김) · 최대 C 27.99 [25.08–28.39] 대 F 29.24 [29.18–29.41](F 김) · 바퀴 중 F CPU 1.7%·최대 18.4%·포화 0(유효 조건 충족) · 바퀴 중/밖 DB CPU C 278/165 · F 258/149 · 앱 CPU 바퀴 중/밖 C 159/157 · pending>0 바퀴 중 C 0.017 · F 0.007 → **R1 유지(DB 쪽 비용)** · R3 양성 대조 유효: 후반 서버형 느린 초 noc 0 [0–0] 대 C 12 [11–12] · 새로 본 것: F의 후반 1초 p99 최대 207.7 [186.5–220.9]ms가 C 106.8 [75.7–117.3]보다 큼(범위 비겹침 — 서버형 느린 초는 13 대 12로 비슷) · 설정은 같음(reconciler-config: chunk 1000·orphan 10000·PT1M — C app-config와 같음) · 원인 미확인(후보: F는 앱 요청과 스레드를 나누지 않아 탐지 쿼리를 더 몰아서 냄 — 확인 안 함) · 결과 커밋(k6-requests.csv.gz 제외 — 기존 규칙, 185M) | ADR-012 1단계 결과 기록 → task 04b |
| 10-18 21:00 | 사용자 "DB는 단순 조회, 비교는 별도 배치 앱 — 한 방 쿼리가 지금 더 문제" → 메인 설명(비싼 쪽 = 쌍마다 인덱스 탐색 2번 · 비교 자체는 공짜 · 범위 훑기로 바꾸면 줄 수 있음 · 기울기는 그대로 · 시점 차이는 한 스냅샷으로) → 사용자 결정 3: ADR-012에 추가(R8) · 변형 둘 다(`split`·`group`) · 순서 04b 먼저 → 명세 §0·§1⑤·§2·§5·task 04c · ADR-012 §2.2 행·R8·R8 판정·1단계 결과 단락 | 명세 재합의 반영 · R8 문서는 04c 착수 때 테스트·하네스와 함께 리뷰 |
| 10-19 00:10 | 사용자 "4b 시작하자" → task 04b 워커(Opus) 위임 — refactoring.md 고정 순서(이동 전 테스트 508 baseline · jar 클래스·리소스 목록 baseline 선행) · 소단위 커밋(main-app 이동 → reconcile-core 추출 → reconciler-app 골격 → 하네스 경로 검사) · 메인 확인 사실: Dockerfile이 루트 `src`·`build.gradle.kts`를 복사 · 하네스 run.sh 8개 경로 검사(`-- src build.gradle.kts Dockerfile .dockerignore`) · lib.sh 2개(ADR-008·009) `ls-tree -- src/main/resources` — SHA마다 구조가 다를 수 있어 구조 인식으로 · .164 스모크는 메인 몫(.164 켜질 때) | 워커 진행 중 |
| 10-19 01:20 | 04b 워커(Opus) 회수: 1f434209(main-app 이동 — git mv · 루트 build 플러그인만 · Dockerfile `:main-app:bootJar` · jar 이름·kotlin moduleName 고정) · b82970dc(reconcile-core 골격 — 이동 0) · 90c9cd00(reconciler-app 골격) · 6c081c2d(run.sh 8 경로 목록 + main-app·reconcile-core·reconciler-app·settings · lib.sh 2 구조 인식) · 검증(워커): 이동 전 baseline 508/0/0/0 → 단계마다 508 · 테스트 이름 diff 0 · 이미지 jar 클래스 128 이름·sha256 diff 0 · 자원 12 diff 0 · lib 목록 reconcile-core jar 1개 추가만 · ADR-009 9세트 dry-run diff 0 · 옛 SHA archive 빌드 = baseline · 메인 확인: XML 97 suites 508/0/0/0 · Dockerfile · settings · **계획과 다름: drift 5클래스를 core로 못 옮김**(SeatHoldProperties · internal sqlStateOf · SalesCloseGate · JPA SeatHold · 역의존) — 워커가 멈추고 보고 | 사용자 결정 필요(core 추출 시점·방식) |
| 10-19 01:35 | 사용자 'core 추출은 05 설계 때 함께' → 명세 §0 행·task 04b·04c·05 · ADR-012 §2.2 행 · README 저장소 구조 절 · k6/ADR-008 README 경로 주 | 04b 듀얼 리뷰 + .164 스모크 |
| 10-19 09:18 | 04b 스모크(run.sh default · adde49d4 · id smoke-04b-default) **실패 — .164 도달 불가**: 01:40쯤 띄웠으나 러너 로그 첫 줄이 09:13:09(deploy) — 그 사이 지연 원인 미확인 · 09:13 이후 ssh 22 시간 초과 · 상태 pre-k6-overrun · compose down 3회 실패(확인 불가) · 09:18 ping 100% 손실 · codex 04b 리뷰: 낮음 1(plain jar 이름 main-app-…-plain.jar — 저장소 안 소비자 없음 확인) | .164 다시 켜지면: 남은 seatlab 컨테이너·볼륨 확인 후 스모크 재실행 |
| 10-19 09:40 | 04b 리뷰 rv38 회수(Opus ∥ codex — 신규 높음·중간 0 · 낮음 5) → e538236a(테스트 모듈 이름·plain jar 이름 고정 · 설정 검사에 reconcile-core 자원 · 이어서 검사에 gradle·gradlew — wrapper는 첫 커밋 뒤 안 바뀜) · 테스트 508/0/0/0 · 로컬 bootJar classes 140·lib 95 sha256이 이동 전 이미지 jar와 같음(+ reconcile-core jar) · 설정 검사: 옛 SHA 3파일·새 SHA 3파일 · 참조 없는 임시 커밋(core에 application-core.yml)에서 core 파일 잡음·사유 limit-set-by-profile · codex 재점검 신규 1(set -u 옛 bash 빈 배열 — 중간) → 633c8b7d `${core[@]+…}` · set -u 옛·새 SHA 확인 · 기록할 예외: jar 색인 파일 3개(MANIFEST 줄 순서 · classpath.idx · layers.idx)는 다름 — 실행 java -jar 영향 없음 | 리뷰 종료 · .164 스모크 대기(도달 불가) |
| 10-19 11:38 | 사용자 ".164 켰어 진행하자" · .164 확인(부팅 1분): 남은 seatlab 컨테이너 3개(Exited — app 143·db·redis)·볼륨 seatlab_pgdata → 우리 프로젝트 것만 docker rm·volume rm(남은 0) · 실패한 스모크 폴더는 smoke-04b-default-unreachable로 이름 바꿈 · 스모크 다시(633c8b7d · 11:38:15 deploy 바로 시작) | 대기 |
| 10-19 11:45 | 04c 설계 메모(ADR-012 §4.1 · §7-9): 1단계 바퀴 기록으로 갈래별 시간 확인(c rep1 마지막 바퀴 — ① 12.6초 · ② 5.9초) · 범위 = ① 쿼터 청크만(② 반조인은 쿼터 키 약 770만 쌍이 앱 메모리에 필요 — F 1g에 안 맞음) · 메인 확인: seat_hold 인덱스는 user_id 단일(V2:7) · reservation은 (schedule_id, user_id)(V2:11) → split·group 홀드 범위는 다른 회차 홀드까지 읽음 · 인덱스 추가는 안 함(스키마 변경) · 한 스냅샷 = 청크당 읽기 전용 REPEATABLE READ 1개(빌림 1) | 설계 선검증(듀얼) → blind 테스트 → 구현 |

## 리뷰 ledger

- review packet(ADR-012·013 측정 전 문서 01d82fad): rv36 — `scratchpad/rv36`(미러: docs/adr · README · 하네스 스크립트 · src/main · 명세 · ADR-009·011 결과 TAIL·COMPARISON — log·NEXT·measurement-log 제외). Opus ∥ codex.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| RA-1 | 1 | opus H1 + codex | ADR-012:45·75 ⓒ는 S3e 주입을 못 봄 · R6 ⓒ 없음 | 채택 — §2.3 방식별 표 | fixed | 3d312998 |
| RA-2 | 1 | opus H2 + codex H6 | R6 메모리 집합 반증 불가(주입이 전부 DB 직접) | 채택 — 사용자 결정: 측정 전용 앱 경로 주입 | fixed | 3d312998 |
| RA-3 | 1 | opus H3 + codex M3 | 고르는 규칙에 탐지 범위·운영 탐지 시간 없음 · R4에서 H3 '2주기' 빠짐 | 채택 — 사용자 결정: 안전망 재설계 · R4 탐지 시간 · 탐지 범위 우선 순서 | fixed | 3d312998 |
| RA-4 | 1 | opus H4 + codex H7 | 증분 창 동시성 불변식 없음(now() 시작 시각 · 시계 · 커밋 전 등록 · 상한) | 채택 — §3 동작 불변식 | fixed | 3d312998 |
| RA-5 | 1 | opus H5 | R3 양성 대조 없음 · noc 기준 미정 | 채택 — 판정 유효 조건 · 같은 캠페인 | fixed | 3d312998 |
| RA-6 | 1 | opus H6 + codex H2·H3·H4·M2 | 하네스 산출물 없음(후반 창·정상 구간 CPU·증분 바퀴·출처·summarize·빌림·.158) | 채택 — §4 '측정 전에 하네스에 필요한 것' ①~⑧ | fixed | 3d312998 |
| RA-7 | 1 | codex H1 + opus M6 | M3를 '통과한 것만' — 명세와 다름 | 채택 — 4셀 전부 + noc·비교 셀 | fixed | 3d312998 |
| RA-8 | 1 | codex H5 | R2·R6·안전망 비용이 성공 조건에 없음 | 채택 — 성공 = R2~R7 | fixed | 3d312998 |
| RA-9 | 1 | opus M1~M9·L1~L5 | 인용 수치 · 안전망 비용 · R1 원인 분리 · 규칙 정의 · 순서 근거 · HOT 비용 · updated_at 못 보는 경로 · R2 하한 · R3 창 · M3만 성공 · 문구 · M3 안전망 주체 | 채택(M3 안전망 주체는 설계 task 05에서 source 값과 함께 정함) | fixed | 3d312998 |
| RA-10 | 1 | codex M4 | 행 없음 갈래 후보 진입 불변식 | 채택 — §3-3 | fixed | 3d312998 |
| RA-11 | p | codex post-fix | 최근 창 처리량·커서 · id 커밋 순서 · R7 예산 범위 · 고부하 2분 | 채택 | fixed | (이 커밋) |
- review packet(1단계 하네스 1c9887a0 + README 2a6d1e53): rv37 — `scratchpad/rv37`(미러: k6/ADR-009 scripts·README·compose 2개 · ADR-011·012 · harness.diff · 표본 TAIL 2개). Opus ∥ codex.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| RH-1 | 1 | codex H1 + opus M3 | compose.reconciler.yml:29 F cpus 1 — F JVM 포화 시 R1이 '유지' 쪽으로 기움 · 표에 F CPU 평균만 | 채택 — 조건은 ADR-009 구성 유지(바꾸면 C 대 F 외 변수 추가) · F CPU 최대·한도 95% 이상 표본 비율 표기 + ADR-012 §5 R1 판정 유효 조건·§7 한계 | fixed | 584dbf4c |
| RH-2 | 1 | opus M1 + codex M2 + opus L5 | tail.py:262-267·410 바퀴를 시작 시각 하나로 창 소속 · 창 기준 t0 대 ceil(t0) | 채택 — 창 경계 s_min으로 통일 · 수·중앙·최대는 창 안에서 시작하고 끝난 바퀴 · 끝을 넘은 바퀴는 따로 셈 · 바퀴 중/밖 분류는 창과 겹치는 모든 바퀴 | fixed | 584dbf4c |
| RH-3 | 1 | opus M2 + codex M3 + opus L7 | tail.py:223·393·400-405 F 표본·출처 없음이 '해당 없음'과 구별 안 됨 · source None 행 | 채택 — 조건별 기대 출처(SRC_OF)·F 컨테이너 · 없으면 사유(notes·상태 칸) · None 출처 경고 | fixed | 584dbf4c |
| RH-4 | 1 | codex M4 + opus L6 | tail.py:181·351 후반 창 공백을 0으로 셈 | 채택 — 후반 창 관측 초·thin 초 수 표기 · 빈 초 > 9(1%)면 late_unavailable 사유 | fixed | 584dbf4c |
| RH-5 | 1 | opus L4 | tail.py:266 docker stats·Hikari 시각 거침 · in_n·out_n 숨음 | 채택 — 표에 n 표기(시각 정밀도는 한계로 기록) | fixed | 584dbf4c |
| RH-6 | 1 | opus L8 | 기존 세트 회귀 바이트 비교 안 함 | 메인 재확인 — soakrc 재생성 TAIL.md diff 0·tail.json 추가만(1c9887a0) · 수정 뒤 워커 6폴더 사본 전·후 cmp 동일(584dbf4c) | fixed | 584dbf4c |
| RH-7 | p | codex post-fix | tail.py:383 NanoCpus 0을 1코어로 처리 | 기각 — num(0)은 참이라 0 갈래('한도 없음')로 감(tail.py:94·387-389) · 기록 없이 RECONCILER_CPUS 바꾸는 경우는 run.sh app_config가 늘 기록하고 캠페인이 바꾸지 않음 | rejected | |
| RH-8 | p | codex post-fix | tail.py:580 알 수 없는 cond 값이면 결측 검사 건너뜀 | 채택 — A12_CONDS(run.sh CONDS) 밖이면 a12_missing · 합성 'A+F '로 사유 확인 · 기존 세트 TAIL.md는 a12 사유를 쓰지 않음(렌더 경로 adr012s1만) | fixed | e0d85bfa |
| RH-9 | 3 | codex 재점검(e0d85bfa) | — | 신규 없음 | closed | |
- review packet(04b 1f434209..6c081c2d): rv38 — 미러(빌드 파일·Dockerfile·.dockerignore·k6 scripts·OLD-*·refactor.diff — 이름만 바뀐 소스 제외). Opus(옛·새 worktree 실효 Gradle 설정·bootJar 비교) ∥ codex.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| RS-1 | 1 | codex L | main-app/build.gradle.kts:64 plain jar 이름 main-app-… | 채택 — base.archivesName | fixed | e538236a |
| RS-2 | 1 | opus L | main-app/build.gradle.kts:68-70 compileTestKotlin 모듈 이름 | 채택 | fixed | e538236a |
| RS-3 | 1 | opus L | jar 색인 3개 다름 — '이미지 jar 동일' 주장 정밀화 | 채택 — 기록(클래스·자원 동일, 색인 3개 다름) | fixed | (기록) |
| RS-4 | 1 | opus L | lib.sh:364-366·382-384 설정 검사가 main-app만 | 채택 — core 자원 포함 | fixed | e538236a |
| RS-5 | 1 | opus L | run.sh 이어서 검사 목록에 wrapper 없음(전부터) | 채택 | fixed | e538236a |
| RS-6 | p | codex 재점검 M | lib.sh:379·400 set -u 옛 bash 빈 배열 | 채택 | fixed | 633c8b7d |
