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
| 10-19 12:00 | R8 설계 선검증 rv39 회수(Opus 높음 1·중간 5·낮음 4 · codex 높음 1·중간 3·낮음 1) → 전부 채택: ADR-012 §4.1 다시 씀(ⓑⓒ `user_id = ANY(U)` · 탐지 전용 RR 템플릿 · 경계·실패 규칙 · chunk_ms 정의 · 하네스 거부·기대 설정 · 스모크 EXPLAIN) · §5 R8 기각 조건·판정(주 지표 ① chunk_ms_total) · §7-9 · 명세 §2·§5 | 설계 문서 리뷰 = 선검증 + 듀얼 1패스 — post-fix 재점검 1회 |
| 10-19 11:47 | R8 설계 재점검(codex) 신규 2(중간 fetch size · 낮음 명세 문구) → 반영 · 설계 문서 리뷰 종료(선검증 + 재점검) · blind 테스트 워커(Opus) 진행 중 | 구현 워커는 blind 테스트 회수 뒤 |
| 10-19 11:51 | 04b 스모크 통과(633c8b7d · 새 구조 이미지 빌드·기동 · 11:38~11:50 · 상태 ok · 정합성 위반 10항목 0 · 대조 바퀴 9(reconcile-app) · 오류 0 · 설정 검사 사유 없음 · 앱 ERROR 로그 0 · 정리 뒤 seatlab 컨테이너·볼륨 0) | **task 04b 완료**(테스트 508 · 산출물 동일 · 하네스 계획 동일 · 리뷰 종료 · 스모크) |
| 10-19 12:20 | blind 테스트 워커(Opus) 회수 e9c22570(DetectShapeEquivalenceTest.kt 1파일 — 4클래스(one-shot·split·group·기본) × 7: 바인딩 · 동등성(쌍·복구) · 바퀴 카운터(chunks 6·borrows 19 등) · 어긋남 0 k 배수 · 한 스냅샷(seat_hold 잠금 사이 커밋 — 갈라진 스냅샷이면 오탐) + 문장 모양 · 복구 RC(FOR UPDATE 사이 커밋 → 오탐 판정) · 실패(pg_cancel_backend → 청크 통째 버림)) · 지금은 컴파일 실패(손잡이 없음 — 예상) · 워커가 임시 stub으로 one-shot 7/7 green·split/group 손잡이 무시 구현을 3개가 가름 확인 · 못 고정: 읽기 전용 여부·toIntExact 넘침 → 구현 워커(Opus) 위임(코드 main-app + 하네스 run.sh·campaign adr012r8·tail.py ①② 시간 열) | 구현 대기 |
| 10-19 13:40 | 04c 구현 워커(Opus) 회수: 9984c08f(손잡이·enum) · 0e7f59cf(split·group — 탐지 전용 RR 읽기 전용 템플릿 · ⓐⓑⓒ · ANY · 흘려 읽기 fetchSize k · toIntExact · 청크 통째 버림) · 28533a59(run.sh --reconcile-shape A+F만 · RECONCILE_DETECT_SHAPE · plan/meta · 이어서 대조 · 설정 대조 detectShape) · 890ea938(campaign adr012r8 — f 줄 = adr012s1 f 바이트 같음) · 1ebdc0a4(tail.py ①② 갈래 시간 8열 · 탐지 모양 열) · 워커 검증: 전체 536(508+28)/0/0/0 · blind 28 첫 실행부터 green(단언·typealias 무변경) + 재실행 2회 · 기존 9세트 dry-run diff 0 · 미지정 compose 렌더·plan·meta 같음 · soakrc·adr012s1 TAIL.md 바이트 같음 · 메인 확인: diff 읽음(ONE_SHOT 경로 무변경 · 분기 1곳) · 로컬 XML은 마지막 blind 재실행만 남아 있어 전체 테스트 다시 돌림 · 워커 미완: tail에 F 메모리 열 없음 | 코드 듀얼 리뷰 rv40(Opus ∥ codex) + 전체 테스트 진행 |
| 10-19 14:20 | 메인 전체 테스트(1ebdc0a4 위 — run.sh만 다른 7bf6dee6과 Gradle 입력 같음): suites 101 · 536/0/0/0 · rv40 재점검(codex) 신규 없음 → 코드 리뷰 종료 · .164 꺼져 있음(ping 응답 없음) — 사용자에게 켜 달라고 요청 | .164 스모크(split·group + EXPLAIN) 대기 |
| 10-19 14:40 | 사용자 "ADR 문서에 실측 기록 다 안 해놨어?" → 메인 확인: §5 요약 한 단락뿐 · 머리말 '§8 이후에 쓴다' 미이행 → ADR-012 §8 1단계 측정 결과 작성(8.1 꼬리 · 8.2 바퀴·CPU·갈래별 시간 · 8.3 판정 — 값은 TAIL.md 1·3~5절 · summary.json hold_p99 · drift-db.json 끝에서 두 번째 바퀴에서 옮김) · 새로 적은 것: f 측정기 튐 PC 38·서버 36(c 3·2) · 회차 전체 선점 p99 f가 c보다 낮음 · §4.1 근거 숫자를 끝에서 두 번째 바퀴로 고침(이전 '마지막 바퀴 12.6·5.9초'는 k6 끝 무렵 바퀴) · 머리말 진행 상태 | 결과 문서 듀얼 1패스는 task 09에서 R8·2단계와 함께 |
| 10-19 14:55 | 사용자 "ADR-001부터 실험 결과 표를 전부 ADR에 우선 기록 · 실측은 저녁" → docs 작업(L0) · 워커 5개(Opus) 병렬: ①000·001·002 ②003·004 ③005·006 ④007·008 ⑤009·010·011 — 기준 = 결과 파일(COMPARISON·SUMMARY·TAIL·원시 json) · 대화 기록(1663be45 jsonl)은 빠진 표 찾기용(숫자는 결과 파일로 재확인) · 번호 이동 8차 → 캠페인 id·내용으로 대응 · 측정 전 고정 문구 무변경 · 워커는 커밋 안 함(메인이 ADR별 커밋) | 워커 진행 |
| 10-19 15:30 | 사용자 "각 실제 지표를 기반으로 다 기록하고 이 기록에 따른 의사결정을 어떻게 했는지 전부 ADR에" → 브리핑에 의사결정 기록 추가 · 워커 5개 회수 · ADR별 커밋 5e35d1af(000)·dba37eab(001)·89479e91(002)·b5238df7(003)·8f818e5c(004)·66da4fef(005)·2e0d5943(006)·커밋(007·008)·c37d0090(009)·950e904e(010)·388d51f9(011) + a06ed458(012 §9) · 결과 파일과 다른 숫자 0 · 정정 2(ADR-003 §8 a50 최저 = jvm-lock 8,038 — 메인 재확인 · ADR-009 §7.2 단위 표기) · 보강 각주(ADR-004 §5.1 · ADR-007 §7.1 이상치 2건) · '결과 파일 미확인' 표시: ADR-002 보정 2,646/s(원시 계산 2,586) · ADR-006 2건 · ADR-009 2건 · ADR-011 2건(soak 6회 합) · 측정 전 고정 절 문제(고치지 않음): ADR-001 §1 결정 4 철회 미반영 · ADR-002 61/49시간·§6 순서·상태 줄 · ADR-003 §2.11 EX 300(S4·S6은 3600) · 388/389 반올림 · ADR-011 §5 번호 · 작업 log 시각이 대화 기록과 몇 시간 어긋난 행 다수(ADR-001·002·003·006·007·008·009·010·011) | 사용자 결정 대기(고정 절 각주·log 시각) |
| 10-19 15:50 | 사용자 답: 고정 절 문제 → "코드 리뷰를 별도 에이전트에게 받고 그에 따른 의사결정이니 어떤 리뷰를 어떻게 받았고 그에 따라 어떻게 판단했다로 적는 게 맞다" · log 시각 → 'log는 두고 주 1행' → ADR-001~011 의사결정 기록 절 끝에 '검토 기록 — 2026-10-19 사후 대조 검토'(무엇을 받았나 · 공통 판단 원칙 · 발견별 근거·판단·반영 표 — 46행) · 작업 log 11개 끝에 시각 주 1행 | 커밋 |
| 10-20 00:39 | 사용자 ".164 켰어 진행하자" · .164(부팅 1분 · seatlab 컨테이너·볼륨 0) · PC 점검: 다른 컨테이너 ccle-postgres-1 하나(healthy 4일) · load 3.6/24 · R8 스모크 split 시작(run.sh A+F --reconcile-shape split 12구간 · 27ea021a · id smoke-r8-split) · 부하 4.5분 뒤 EXPLAIN(ANALYZE, BUFFERS) 세 모양 + reconciler 세션(backend_xid) 확인 예약 | 대기 |
| 10-20 00:45 | 사용자 "다음 순서도 문제없으면 쭉 진행하자" → 스모크 통과 시 adr012r8 캠페인 → 집계 → R8 판정·ADR 기록 → task 05 설계로 이어서 진행(문제·판정 갈림·재합의 사항이 생기면 멈추고 보고) | — |
| 10-20 00:47 | EXPLAIN(부하 4.5분 · 회차 1 쿼터 1만 행 · 사용자 1,000명 청크): one-shot 2.57ms·버퍼 4,753 · split ⓐ0.20+ⓑ1.00+ⓒ0.005ms(버퍼 약 4,534) · group ⓐ0.20+ⓑ1.22ms(약 4,531) — 읽는 대상·버퍼 같음(설계대로) · 홀드는 셋 다 idx_seat_hold_user_id · 청크 모두 ≪ 200ms · **새 한계: S4c는 확정 예약이 없어 reservation이 비어 있다 — R8은 홀드 쪽 비용만 비교** · reconciler 세션 표본 idle·xid 없음(탐지 중 표본 아님 — 약한 근거) | — |
| 10-20 00:51 | split 스모크 통과(rc 0 · 상태 ok · reconciler-config detectShape SPLIT · plan reconcile_shape split · 바퀴 9(reconcile-proc) · 오류·건너뛴 청크 0 · 정합성 위반 0 · 앱 ERROR 0 · 끝에서 두 번째 바퀴 쌍 146만·① 2,268ms·청크 최대 30ms·② 1,719ms) → group 스모크 시작 | 대기 |
| 10-20 01:02 | group 스모크 통과(rc 0 · ok · detectShape GROUP · 바퀴 9 · 오류·건너뛴 청크 0 · 정합성 위반 0 · 앱·reconciler ERROR 0 · 끝에서 두 번째 바퀴 쌍 147만·① 2,704ms·청크 최대 4ms·② 1,644ms) · .164 seatlab 컨테이너 0 · PC 점검(ccle-postgres-1 healthy · load 4.4/24) · ADR-012 §7-10 한계(예약 없음 — 홀드 쪽만 비교 · EXPLAIN 값) | adr012r8 캠페인 시작 |
| 10-20 01:02 | task 04c 캠페인 시작: `campaign.sh --set adr012r8 --sha 8c7207b2 --k6-bin ~/.local/opt/k6-v2.2.0/k6`(setsid 분리 · results/adr012r8-launch.log) → results/20261020-adr012r8-8c7207b2 · 9회 · 계획 326.7분(1단계 실측 회당 약 40.5분 → 끝 약 07:10) | 측정 중 |
| 10-20 01:20 | task 05 설계 초안 워커(Opus) 회수 b521b7ea(ADR-012 §4.2 · 11 하위 절) · 사용자 결정 3: 새 테이블 counter_safety_run·counter_app_inject = **선택 위치 V10으로 만든다**(명세 'V9 = 열만' 범위 확장 — 재합의) · M3 안전망 = **외부 앱** · 외부 앱 한도 = **F와 같게 CPU 1·1g** · 설계가 찾은 고정 문구 문제 4(§2.1 30초 재읽기 · §2.1 복구 미룸 · 명세 §5 고르는 규칙 낡음 · 명세 §0 S3e 안전망 주기) — §4.2에 판단 기록 | 설계 선검증(듀얼) |
| 10-20 07:01 | R8 캠페인 끝(01:02~07:01 · 9/9 exit 0 · 비정상 회차 0 · 회당 약 40분) | 집계(summarize 3 병렬 → tail.py --set adr012r8) |
| 10-20 08:30 | §4.2 설계 선검증 rv41 회수(Opus 높음 0·중간 5·낮음 7 · codex 높음 4·중간 5·낮음 1) → 사용자 결정 2: 불변식 1 = **조건부 보장 + 안전망**(60초·재시작·상한 밖은 안전망 + 기록) · 2' = **7셀(M0 추가)** → 명세 §0·§2·§5 재합의 행 · 수정 워커(Opus) 위임(§4.2 기계 수정 + §3-1·§2.3·§4 표 재합의 줄 + §4.2.12 설계 선검증 기록) | 진행 |
| 10-20 09:00 | rv41 수정 워커(Opus) 회수 b509c52b(지적 22건 반영 · 기각 0 · §2.3·§3-1·§4 표·R4 재합의 줄 · §4.2.12 검토 기록 22행 · §7-11·12) · 범위를 넘은 2건 사용자 확인: `counter_inc_gap`(V10 테이블 3개) **'넣는다'** · R7 반증 기준 변경 **'합의'** → 명세 §0·§2 · ADR §9 결정 행 2 | 설계 재점검(codex) |
| 10-20 09:40 | 설계 재점검(codex) 신규 중간 2(보장 밖 분류 기준 시각 · 자기 INSERT 시간) → 메인 직접 반영(tx_started_at · 자기 INSERT 제외 + 지표) · §4.2.12 표 r-a·r-b · 설계 문서 리뷰 종료(선검증 + 듀얼 + 재점검) | task 05 설계 확정 — R8 결과로 탐지 모양만 남음 |
| 10-20 08:50 | R8 집계 끝(summarize 3 · tail rc 0 · 누락·의심 없음) — 결과 커밋 ecd717ed(185M) · ADR §8.4 7cc96a8e · 후반 ① 중앙 f 11,509 · split 7,235.5(−37% 비겹침) · group 9,282(−19%) · 바퀴 중 DB CPU f 258.1 · split 258.3(겹침) · group 264.8(비겹침 높음) · p99 최대 겹침 · ② f 8,457 · split 8,007.5(겹침) · group 7,736(f와 비겹침) → 유효 조건 해석 갈림 → 사용자 **'split 성립'** → §8.4 판정 · §9 행 · §4.2.4 탐지 모양 = split | task 06 구현 준비 |
| 10-20 09:00 | task 06 착수(사용자 '쭉 진행' 10-20 00:45) — 분할 06a core 추출 → 06b V9·V10·기동 검사 → 06c 증분 ⓐⓑⓒ·창 → 06d 안전망 → 06e 주입·후보 시각 → 06f reconciler-app → 06g 하네스 · 06a 워커(Opus — refactoring.md 순서 · 536 baseline · configprops JSON·jar·dry-run diff) ∥ blind 테스트 워커(Opus — §4.2.10 계약만 · 저장소 밖 scratchpad/blind06에 작성 · 구현이 들어갈 때 메인이 넣음) | 진행 |
| 10-20 09:40 | blind 테스트 워커(Opus) 회수 — scratchpad/blind06 21파일·89 테스트(타입 검사 통과 · 미실행 — 구현 없음) · 이음새 가정 S1~S8 · 계약 모호점 18 → 메인이 ADR §4.2.13 계약 명확화로 고정(1초 경계 · ⓐ+counter-trigger 불가 · gap source · 열 분리 · id_at 표본 없음 등) | 06a 대기 · 이후 06b~ 구현 워커에 §4.2.13 + 이음새 전달 |
| 10-20 10:35 | task 06a 워커(Opus) 회수: 1e8b484a(sqlStateOf → core public) · d0a8b372(ScheduleClosure) · b841c8b1(ReconcileProperties·DetectShape — @Bean 바인딩 · R8 blind 테스트 별칭 1줄) · 7b6d66e5(CounterReconciler·PairRepair git mv · skipReason public · core 빌드 plugin.spring·BOM platform) · 워커 검증: 커밋마다 536/0/0/0 · configprops seat.hold 바이트 같음 3경우 · dry-run 10세트 같음 · jar 차이 = §4.2.1 이름 · classpath.idx 순서만 · ChunkDetector·PairRepairRunner 분리는 미착수 → **메인 판단: 06c·06d에서 호출처와 함께** · 메인: 로컬 XML이 3건뿐(워커 임시 테스트 흔적) → 전체 다시 실행 중 · 06a 듀얼 리뷰 rv42(Opus ∥ codex) | 리뷰 대기 |
| 10-20 11:10 | 메인 전체 테스트(7b6d66e5 --rerun) 결과 확인 · rv42 리뷰 종료(Opus 낮음 3 기록 · codex 신규 없음) → **task 06a 완료** | 06b 착수 |
| 10-20 11:15 | 06b~06e 한 워커(Opus)로 묶음 — blind 테스트 공용 도우미가 모든 이음새를 참조해 06b만으로는 컴파일 불가 · 슬라이스마다 커밋·536 유지 → 이음새가 모두 생기면 blind 89 투입(이음새 이름·계약 상수 줄만 고칠 수 있음 — 그 밖은 멈추고 보고) ∥ 06g 하네스 워커(Opus — k6/ADR-009만 · gradle 안 돌림 · 세트 adr012s2·adr012s2x·adr012eff · 집계 R4~R7·effect.json) | 진행 |
| 10-20 13:00 | 06b~06e 워커(Opus) 회수: 97af1361(06b 손잡이·V9 2곳·V10 3테이블·기동 검사) · b0c670c4(06c ChunkDetector·QuotaSelector·PairRepairRunner·IncrementalReconciler·창·메모리 집합·ⓒ 문장·스케줄러) · 0b83c1e7(06d 안전망) · b668176d(06e 주입·후보 시각·숨은 세션 SQL) · 50faccc5(blind 투입 — 원본과 바이트 같음) · 조각마다 536/0/0 · blind 112 중 107 통과·5 실패(재실행 2회 같음) → 메인 확인: 5개 모두 테스트 전제 오류(틱 뒤 기록 조회가 빌림 1 더함 · 1인 한도 2인데 시드 cnt 3·5 · 같은 행 2·3번째 대기자는 pg_blocking_pids에 앞 대기자) → d79dc3d7 전제만 수정(단언 무변경) · 워커 판단 6건 → ADR §4.2.13 #19~24 · configprops 기존 18키 무변경·새 23키는 incremental·safetyNet·injectApp 아래만 · 전체 테스트 재실행 + 코드 듀얼 리뷰 rv43(Opus ∥ codex) | 진행 |
| 10-20 11:35 | 메인 전체 테스트(d79dc3d7 --rerun): suites 143 · **648/0/0/0** · rv43 회수(codex 높음 1·중간 2 · Opus 중간 3·낮음 6 — 겹침 정리 8건) → 수정 워커(Opus) | 진행 |
| 10-20 11:35 | 기록 주: 이 log의 '10-20 11:15'·'10-20 13:00' 행 시각은 추정으로 적었다(실제 각각 약 10:45·11:20 — 셸 date 기준). 이후 행은 date로 확인해 적는다 | — |
| 10-20 11:49 | 06g 하네스 워커(Opus) 회수 7fcfaaff(run.sh A+I·A+X · --incremental·--incremental-interval·--inject-app · Flyway 조합 · 회차 사유 5종 · lib.sh path='db' 주입 기록·V10 덤프·db_schema·quota_hot · external_app_* stub · campaign adr012s2 24회 14.5h·adr012s2x 21회 12.7h(--m2-cell 필수)·adr012eff 30회 11.5h · summarize effect R4·R6·보장 밖·R7·R2 · tail 7절 · actsnap) · 워커 검증: 기존 10세트 dry-run 같음 · run.sh 48조건 plan·compose config 같음 · TAIL 3캠페인 같음 · postgres:16 임시 컨테이너로 SQL 확인 · 메인 판단 2: ① 효과 세트를 M2(adr012eff 6셀)·M3(adr012effx 4셀)로 나눔(M2 효과를 .158 전에 측정) ② 앱 주입기는 시드 초기화 때 주입 상태·counter_app_inject를 비우고 예열 사용자 범위(900000~)를 주입하지 않음 → 앱·하네스 후속 수정 · 06g 듀얼 리뷰 rv44(Opus ∥ codex) | 진행 |
| 10-20 11:59 | rv44 회수(Opus 높음 1·중간 1·낮음 4 · codex 높음 2·중간 1) → 메인 판단: 증분·안전망 셀 = split(§8.4) · noc·M0 = 기본(현재 방식) · 효과 세트 M2/M3 분리 → 하네스 수정 워커(Opus — k6만) ∥ 앱 수정 워커(rv43 진행 중 — 이어서 RG-1 주입기) | 진행 |
| 10-20 12:11 | 하네스 수정 워커(Opus) 회수 d4db5504(효과 세트 M2 adr012eff 18회 · M3 adr012effx 12회 · 증분·안전망 셀 split · 효과 tmpfs 12) · e7e73325(R4 적체 기준 = 주입 전 마지막 안전망 실행 · R7 기록 짝 safety-record-missing · retry_overflow · R6 판정 불가 · 예상 담당 열) · 워커 검증: 기존 10세트 dry-run 20/20 바이트 같음 · 새 세트 계획표 · 거부 경로 · 합성 점검 수정 전·후 다름 · 워커가 찾은 R7 ① 허용식 불일치(ADR rv43 줄 미반영) → 메인 직접 e72be453(탐지 몫 max(예산+청크, 열린 회차+예산/2+3×청크) · db_ms_open 필수) | 앱 수정 워커 대기 |
| 10-20 12:18 | rv43 수정 워커(Opus) 회수 522aade4·f12ff82c·b6d1c3f4·3095d072·be674516·c58511d4·48318a1e·379b6824(지적 1~3·5~8) · RF-4 멈춤(테스트 7클래스가 '증분만' 조합으로 기동) · 전체 648 중 1 실패 = RecentWindowTest 14(예산 5ms가 강제 첫 블록에서 끝남 — 기준 커밋에서도 단독 실패, 앞선 전체 실행에선 통과 = 시간 민감) · 메인: ADR §4.2.6 kind 표에 retry_overflow · §4.2.13 #7 '더 넓게' 정정 · #21을 '다시 읽기에도 3회 규칙'으로 정정(§4.2.5와 맞춤) · 메인 판단: RF-4 = 7클래스에 '안전망 켜되 안 돌게' 속성만 + 기동 실패 검사 · 테스트 14 = 예산 전제만 수정 · RG-1 = 시드 초기화 때 주입 상태·counter_app_inject 비움 + 예열 사용자(≥900000) 제외 → 워커(Opus) | 진행 |
| 10-20 13:01 | 남은 3건 워커(Opus) 회수: 24e45d44(증분만 조합 기동 실패 · 7클래스 안전망 켬·1h 속성만 · 금지 조합 테스트 1) · b9eee5e1(테스트 14 — 예산만 올리면 전체 커서가 매 실행 한 바퀴 돌아 다시 읽기를 증명 못 함(실측) → 별도 클래스·예산 200ms·앞 회차 채움 300,000행·전제 단언 3 추가 · 기존 단언 문구 그대로 · 반대 확인 reread=1ms면 실패) · 921d9367(주입기 시드 초기화·예열 사용자 제외 + 테스트 2 · 반대 확인) · 전체 651/0/0 · 증분 패키지 2회 115/0 · 메인: 테스트 14 처리 수용 · ADR §4.2.2 rv43 검사·조합표 행 · §4.2.7 예열·시드 초기화 | 재점검(codex) → 06f |
| 10-20 13:47 | 재점검 rv45(codex — rv43·rv44 수정분 전체) **신규 없음** → 앱·하네스 리뷰 종료 · 06f 워커(Opus) 회수 c7596003(reconciler-app 본체 · 기동 검사 · 스케줄러 · configprops 비밀번호 가림 · blind 동등성 테스트) · b7779d96(Dockerfile · compose — 프로젝트 seatlab-reconciler · 8103 · cpus 1 · 1g · DB 값은 셸 env 필수) · 4cd1b7bf(lib.sh external_app_* 실구현 · run.sh A+X 순서·표본·사유 · campaign 연속 실패 집계) · 워커 검증: main-app 651/0 · reconciler-app 6/0 · 로컬 이미지 빌드 373MB · postgres:16 로컬 스모크(V10 없으면 실패 · health UP 22초 · inc-ext-trg-1s 61틱 · safety-ext · actuator 3개만) · 가짜 ssh로 하네스 함수 · 14세트 dry-run 57파일 같음 · 남은 것: db_sampler by_src에 외부 앱 'X' 분류 없음 · summarize 외부 앱 시계 차·self_record 연결 · .158 이미지 정리 · 06f 듀얼 리뷰 rv46 | 진행 |
| 10-20 13:51 | 06f 리뷰 rv46 회수(Opus 높음 0·중간 2·낮음 6 · codex 높음 1·중간 3·낮음 1) — 원격 명령 전수: prune·패턴 image rm·-p 없는 down·sudo 없음 · 메인 판단: 배포를 ADR §4.2.8대로(PC 빌드 + docker save·ssh load · compose 파일만 동기화 — 앞 브리핑의 'git archive 배포'는 메인 실수) · 비밀번호 잔여 위험(컨테이너 env) 기록 → 수정 워커(Opus) | 진행 |
| 10-20 14:13 | rv46 수정 워커(Opus) 회수 c82cf854 — 배포 ADR대로(PC 빌드 → save·load · compose만 · pull_policy never · --no-build) · 입력 검증 · env 허용 목록(RECONCILER_TAG 제외) · IFS= read -r · --since 제거 · stats 한 줄 검증 + .158 시계 차 · 회차 끝 down 실패 사유 · compose config 경고 · 검증: bash -n · 14세트 dry-run 같음 · 가짜 ssh 80/80 · 로컬 빌드 + save/load 왕복(.Id·지문 같음 · 이미지 Env에 PASSWORD 0) · 메인: 판단 2건(라벨+층 지문 · TAG 제외) 수용 · ADR §4.2.8 배포·남는 위험·정리 명령 | load-bearing 가정: .158 load 뒤 지문 같음 → task 07에서 실증 |
| 10-20 14:15 | rv46 재점검(codex) 중간 2 → 메인 직접 da1718dd · 06f 리뷰 종료 · **task 06 구현 끝**(06a~06g · 테스트 main-app 651 · reconciler-app 6 · 리뷰 rv42~rv46 + 재점검) · .164 꺼져 있음 → A+I 스모크·2단계 대기 · task 07(.158) 비밀번호 전달 방식 사용자 확인 대기 | — |
| 10-20 18:11 | 사용자 ".164 켰어 진행하자" · .164(부팅 1분 · seatlab 컨테이너·볼륨 0) · PC 점검(ccle-postgres-1 healthy · load 4.1/24) · 2단계 계획 dry-run 확인(8조건 · 24회 · 증분 셀 split) · A+I 스모크 3개 시작(mem·trg·stmt · 1s · split · 12구간 · f4bbebab · id smoke-ai-*) | 대기 |
| 10-20 18:21 | A+I 스모크 3개 **모두 pre-k6-overrun**(setup-done 91.9초 > 87±5): 원인 = run.sh app_counters가 부하 전 빌림 지표를 획득 조회 앞뒤로 두 번 읽어 같기를 요구 — 증분 틱(1초)·안전망이 그 사이 늘 빌려 다시 읽기 3회 → 준비 시간 초과 · 수정 a6939bcb(같기는 C·정리만 · 증분·안전망은 앞 값 + 차이 `*_jitter` 기록 · 다른 조건 무변경) · 실패 폴더는 smoke-ai-*-overrun · .164 남은 컨테이너 0 · 스모크 다시 | 대기 |
| 10-20 18:56 | A+I 스모크 3개 통과(a6939bcb · 상태 ok · rc 0): mem 증분 틱 557·trg 549·stmt 551 + safety-app 9 · 오류 0 · 정합성 위반 0 · 앱 ERROR 0 · gap = restart 1행(기동 — 설계대로) · 안전망 실행 DB 시간 약 2,000ms(예산 2s를 채움 — 전체 커서 청크 약 580 · 청크 최대 14~16ms ≤ 200ms) · lap 미완료(9회 안) · 시작 표본 짧음 표시 · 부하 전 증분 빌림 차이 1~6 | 2단계 캠페인 시작 |
| 10-20 18:56 | task 08 2단계 캠페인 시작: `campaign.sh --set adr012s2 --sha 58d24e3b --k6-bin ~/.local/opt/k6-v2.2.0/k6`(setsid 분리 · results/adr012s2-launch.log) → results/20261020-adr012s2-58d24e3b · 8조건 × 3 = 24회 · 계획 871.2분(실측 회당 약 40분 → 끝 약 10-21 11:10) | 측정 중 |
| 10-21 00:10 | .164 다른 컨테이너 확인(사용자 — 스터디 노트 서비스): llm-ollama(GPU RTX 2070 SUPER · CPU 한도 없음 · 메모리 4.9GB · 측정 시점 GPU 0%·모델 미적재) · llm-wrapper · ci-cd-agent — 건드리지 않음 · 하네스 docker stats가 이미 모든 컨테이너를 10초마다 표본(회차당 156) → 집계 때 llm-ollama CPU 튐과 느린 초 겹침을 회차별로 보고 '오염 의심' 표시(필요하면 재측정) | 측정 계속 |
| 10-21 11:21 | 2단계 캠페인 끝(10-20 18:56 ~ 10-21 11:20 · CAMPAIGN done — 비정상 회차 0 · 24/24 ok) | 집계(summarize 8 병렬 → tail.py --set adr012s2) |
| 10-21 11:34 | 사용자 "우선 스모크까지만 하자" → 효과 본측정(adr012eff) 시작 안 함 · 효과 스모크(smoke-eff-trg)만 · 사용자 지적 "M2가 먼지 () 안에 설명 다 넣어달라고 전에도 다 말하지 않았어? 읽기 힘들다" → ADR-012 약어 풀어 쓰기 워커(Opus — 용어표 + 절 첫 언급·표 행/열 이름 괄호) · 메모리 피드백 갱신(ADR-012 기호 · 워커 문서도 커밋 전 확인) | 진행 |
| 10-21 11:37 | 사용자 "스모크도 종료하고 164를 다시 켜면 스모크부터" → 효과 스모크 중단: run.sh에 TERM — 정리 함정은 돌았지만 하위 표본기·k6가 남음 → 메인이 PC 쪽 프로세스 종료(pkill이 자기 셸까지 맞춰 exit 144 — 결과 영향 없음) · .164는 하네스와 같은 compose down -v --remove-orphans(프로젝트 seatlab만) → 컨테이너·볼륨 0 · 폴더 smoke-eff-trg-stopped · 다음 .164 켜면: 효과 스모크 → adr012eff | .164 꺼도 됨 |
| 10-21 11:46 | ADR-012 약어 풀어 쓰기 워커(Opus) 회수 — §용어 61행 · 본문 괄호 236건(절 첫 언급 · 표 머리·첫 열) · 겹치는 기호 구분(split 조회 ⓐⓑⓒ · 리뷰 지적 번호 M1~) · 메인 확인: 원본이 새 글의 부분 수열(삽입만) · 용어표·본문 표본 확인 | 커밋 |
| 10-21 14:21 | 2단계 집계 끝(summarize 8 · tail rc 0) — **하네스 무음 실패 발견**: 1초 증분 셀 9회 meta.json 0바이트(pre-k6.json 193KB를 jq --argjson 인자로 → 인자 한도 128KB 초과 '인수 명단이 너무 김' · 상태는 먼저 ok로 써짐) → TAIL 7절(R2·R5·R7)의 1초 행 비어 있음 · HOT 비율 모든 셀 누락 · 측정 데이터(응답·CPU·DB·inc-config)는 유효 · 사용자 '측정 진행' 뒤 '하네스도 다 수정하자' → 메인 run.sh finish_rep 수정(slurpfile · 임시 파일 검증 · 실패 시 meta-write-failed) + 하네스 수정 워커(Opus — 인자 한도 전수 감사 · 무음 쓰기 실패 · 9회 meta 복원 스크립트 · HOT 비율 · 1초 셀 재집계) · 측정은 하네스 수정 뒤 효과 스모크부터 | 진행 |
| 10-21 15:34 | 하네스 수정 워커 커밋 5a31b58b(인자 한도·무음 실패 — 워커 보고 대기 · 1초 셀 재집계 진행 중) · 효과 스모크(5a31b58b · M2 앱 안 증분 · ⓑ DB 트리거 · 1초 · split · S3e + 앱 경로 주입) 대조는 정상: 주입 120쌍(앱 경로 AH 30·AL 30 · DB 직접 D 20·H 20·L1 10·L2 10) · 발견 110·복구 110 · 오류 0 · 정합성 위반 0 · effect.json 120쌍·unexpected 0 · meta.json 129KB 정상 · 증분 틱 897·안전망 15(k6 구간 결손 0) · 빌림 1.0001 · **상태 gc-collector-mismatch** — 원인: 하네스가 'Minor Collection' 줄로만 세대형 ZGC를 판정 · S3e 낮은 부하라 Minor 0·Major 170(세대형 맞음) → 03adbf02(Major Collection 줄도 인정 · 스모크·2단계 로그로 확인) | 효과 본측정 시작 |
| 10-21 16:50 | 하네스 수정 워커(Opus) 회수 5a31b58b — 인자 한도 감사(실패 원인 pre-k6 195KB · 잠재 결함 drift_db_dump 페이지 약 700행부터 무음 버림 → 파일로) · finish_rep 임시 파일 검증·meta-write-failed(재측정 대상 — ACCEPT_RE 밖) · 새 사유 invalid-k6-window.json·quota-hot-missing · meta_rebuild.py로 9회 복원(규칙 검증 15/15 · ended만 0~1초 근사) · 1초 셀 summarize·tail 재집계 · HOT 칸: S4c는 쿼터 UPDATE 0(INSERT만)이라 0÷0 → '갱신 0' 표기 · 메인: 결과 커밋 45539bc5 · ADR §8.5 2단계 결과·§9 행 · README 사유 표 3개 추가는 task 09 | 효과 측정 진행 중 |
| 10-21 22:38 | 앱 안 효과 캠페인 끝(adr012eff · 71585d53 · 10-21 15:34 ~ 22:35 · 18/18 ok · 비정상 회차 0) | 집계(summarize 6 병렬 → tail.py --set adr012eff) |
| 10-21 22:41 | 효과 집계 끝(summarize 6 · tail rc 0) · 결과 커밋 2d075467 · ADR §8.6 효과 결과·§8.7 판정: 성공 = ⓐ 메모리 1초 · ⓑ 트리거 1초 · ⓑ 트리거 5초 · 규칙 1 미결정 → 규칙 2 → **ⓑ 트리거 1초** · 2' 비교 셀 trg-1s | 남은 측정: .158 스모크 → 2'(adr012s2x --m2-cell trg-1s) → 외부 앱 효과(adr012effx) · 비밀번호 전달 방식 사용자 확인 대기 |
| 10-21 23:44 | 사용자 "다음 측정도 진행하자" · "164랑 똑같은 ssh 인증서로 되지 않아?" → 메인 설명(SSH 키는 같지만 외부 앱은 .164 PostgreSQL에 DB 계정으로 접속) · 확인: 측정 DB는 하네스 compose.yml이 회차마다 고정 측정값(POSTGRES_PASSWORD — 이미 커밋됨)으로 만들고 업무 앱 DB_PASSWORD와 같은 값(값 출력 없이 일치만 확인) → lib.sh `ext_db_password`: EXT_DB_PASSWORD가 없으면 측정 커밋 compose.yml 값(변수에만 · export 안 함) · external_app_ready rc 0(.158 compose 5.0.0 · 디스크 여유 · 배포 0 · 이미지 0) | .158 스모크 |
| 10-22 00:08 | task 07 .158 외부 앱 스모크 2개 통과(0f2e4f47 · M3 외부 앱 증분 · ⓑ 트리거·ⓒ 앱 문장 · 1초 · split · 23:45 ~ 00:08): 상태 ok · 두 앱 설정 대조 사유 0 · 외부 증분 틱 521·526 + safety-ext 9 · 오류 0 · 정합성 위반 0 · 외부 앱 ERROR 0 · 외부 컨테이너 한도 CPU 1·1g 적용 · .158 시계 차 −10~−24ms(RTT 1~4ms) · .158 표본 40행 · PC 빌드 → save·load 배포(.158 이미지 seatlab-reconciler:0f2e4f47 782MB · 폴더 1개) · 정리 뒤 .158·.164 컨테이너 0 | 2'·외부 효과 캠페인 연속 시작 |
| 10-22 00:08 | task 08 2' 캠페인 시작 → 이어서 외부 앱 효과: `campaign.sh --set adr012s2x --m2-cell trg-1s --ext-ready --sha ee649b8d` 뒤 `--set adr012effx --ext-ready`(setsid 한 줄 · results/adr012-ext-launch.log) · 2' 7조건 × 3 = 21회 계획 762.3분 · 외부 효과 12회 약 4.6시간 → 끝 약 10-22 17:00 | 측정 중 |
| 10-22 13:53 | **PC 디스크 가득 참(13:35 · 468G 중 여유 0)** — 결과 폴더 40G(원시 k6-requests.csv.gz 약 60G 누적 · git 제외) + 다른 프로젝트 도커 48G + 이전 세션 임시 8.4G · 2' noc rep3이 요청 CSV를 못 써 k6 오류 로그 2.6G 증가 → 사용자 '지금 지운다' → 이 작업 임시(이전 세션 임시 폴더 8.4G · /tmp/04b·t06a·rv42chk)·이 랩 비교 이미지(seatlab-04b-before/after · 옛 reconciler 태그 2) 삭제 · 해당 회차 k6 중지·오류 로그 1MB로 자름(회차 무효 → 하네스 재측정) · 여유 12G(남은 측정 약 2G) · 13:13까지 끝난 19회는 영향 없음 · 후속: 끝난 ADR 원시 CSV 보관 위치 결정(NEXT) | 측정 계속 |
| 10-22 20:45 | 2'(adr012s2x · ee649b8d · 00:08 ~ 15:57) 21회 ok — noc rep3은 디스크 가득 참으로 무효(k6-exit-143 등) → 하네스가 15:16 재측정 ok · c rep3 1회 borrow-missing → 재측정 ok · 외부 앱 효과(adr012effx · 15:57 ~ 20:44) 12회 ok · 두 캠페인 비정상 회차 0 · .164·.158 남은 컨테이너 0 | 집계(summarize 11 병렬 → tail effx·s2x) |
| 10-22 22:22 | 2'·외부 효과 집계 끝(summarize 11 · tail 2 rc 0) · 결과 커밋 55e178f5 · ADR §8.8: M3 4셀 성공 · 앱 JVM 분리 효과 작음 · 기본값 결론 유지(M2 ⓑ 1초) · 남은 '누락·의심' = 외부 앱 시계 차 미보정(−10~−24ms — 영향 없음) · 외부 앱 self_record 지표 없음(R7에 0) | **task 08 끝** → task 09 |
| 10-23 03:25 | task 09 착수(사용자 '12번 정리부터 마무리') · .9 서버도 안전망·ADR-013 배치 후보로 사용 가능(사용자) · 기본값 교체 영향 조사 워커(Opus) · 결과 문서 듀얼 1패스 시작 — base ecd717ed(§8.4 직전) · packet = ADR-012 diff + spec · 미러 = ADR-009·011·012·013 + ADR-012 결과 6폴더(csv·log·dashboard 제외) + summarize·run·lib · 보안 스캔: 비밀값 0 · 사설 IP(.158·.164)만 | 진행 |
| 10-23 03:50 | 결과 문서 듀얼 1패스 회수: codex 3건 · Opus 7건 → 종합(아래 ledger RR-1~7) · **R5 판정 충돌(H)** → 사용자 결정 '추가 측정 후 판정'(AskUserQuestion) — 명세 재합의 대상(측정 추가) · 기본값 교체 영향 조사 회수(Opus — 판단 지점 9개 · 기본값 교체는 R5 판정 뒤) · 판정 무관 지적 ADR 반영 | 진행 |
| 10-23 03:58 | task 08b 착수 — 사용자 '그대로 진행 · 164 켤게' · 하네스 f2ac01c4(세트 adr012r5 · --inject-app expiry — bash -n · py_compile · dry-run 15회 222분 · 조건 5줄 확인) · 스모크 S2 trg-1s + S3e ae(만료 경로만) 시작 — .164 기동 4분 · 컨테이너 3(사용자 study-note — 건드리지 않음) | 진행 |
| 10-23 04:37 | 스모크 끝: smoke-adr012r5-s2 ok(쿼터 UPDATE 100 · HOT 0 · hold p99 240.9ms — 1,000건 몰림) · smoke-adr012r5-ae ok(AE 56쌍 · 복구 inc 41 · safety 15 · 최대 0.77초 · R4·R6 위반 0 · unexpected 0 · inc-config paths [EXPIRY]) · **R5 지표 측정 전 고정**: 회차 k6-summary.json `hold_duration` p(99)(회차 전체 — 2단계 표와 같은 '회차 전체' 선점 p99) 3회 중앙값 · 판정 = i-trg-1s ≤ noc × 1.10(S3-a20·S2 각각) · HOT = quota-hot.json(k6 → end 차분) · 본측정 campaign adr012r5 시작(SHA cc906369 · .164) · 사용자 결정: 번호 이동 9차 = ADR-013(Kafka) → 020 · 014~020 → 013~019(측정과 병행 — docs·주석만, k6/ADR-009 손대지 않음) · 그 뒤 ADR-013(새 번호 — 운영 중 매수 방식 전환) 작업 착수 요청 | 진행 |
| 10-23 04:46 | 번호 이동 9차 회수(Opus 워커): 8c2df227(docs 27파일 · git mv 8) · 826c6874(코드 주석 3줄 · compileKotlin 통과) · 메인 확인: 파일 목록 013 전환~020 Kafka · ADR-020 머리말·상태 줄 · 9차 메모 · k6/·log.md 변경 0 · 메인 보정: ADR-020 선행·후행(순서 뒤집힘) · README ADR-020 행 '보류' | 9차 끝 |
| 10-23 08:24 | adr012r5 끝(08:21 · 15회 ok · 비정상 0) · 결과 커밋 473ae26c(비밀값 검사: 비-html 매칭 0 · html은 k6 대시보드 base64 오탐) · 집계(메인 python — k6-summary hold_duration p99 · quota-hot · effect.json): S3-a20 noc 1.340 / trg 1.314(−1.9%) · S2 noc 363.5 / trg 266.3(−26.8%) → **R5 성립** · AE 51·39·48쌍 최대 0.95초 · R4·R6·오탐·끝 불일치 0 · ADR §8.9·§8.7 표·§9 행 · RR-1·RR-5 해소 | **task 08b 끝** → task 09 기본값 교체 |
| 10-23 09:08 | task 09 기본값 교체: 사용자 결정 4건(yml만 · default 거부·A+C 명시 · 혼동 조합 둘 다 거부 · ADR-001~008 키 명시) → 명세 §10 계약 K1~K10 고정 · stakes 높음(기본값·스키마 기본 적용) → 구현 워커(Opus — diff) ∥ blind 테스트 워커(Opus — 명세 §10만, 구현 diff 미열람) · 설계 선검증 생략 사유: 새 동작 불변식 = 기동 거부 2건(K6·K7)뿐이고 기존 강제 위치 HoldStrategyStartupCheck.kt(:99 external+C 거부 · :158·163·166 증분·안전망 조합 거부)와 같은 꼴 — blind 테스트가 계약을 직접 잰다 | 진행 |
| 10-23 10:31 | 구현 회수(Opus): 39a11a8d(앱 기본값·기동 검사 K6·K7·reconciler yml) · 3acc4cc9(테스트 고정·기본값 테스트) · d40b2195(k6/ADR-009 — default 거부·A+C·키 명시·cond-config 확장) · 3251efe6(ADR-001~008 compose) · 워커 보고 gradle test 661/0 · 옛 SHA dry-run 15세트 바이트 동일 · blind 회수(Opus · worktree): dac158a4 → cherry-pick ea6cd0b9 · 교체 전 16 중 12 실패(기대대로) → 교체 뒤 16/16 통과(메인 실행 1m36s) · 디스크 99% → 사용자 승인 정리(멈춘 컨테이너 1.4G · 볼륨 10.0G · 이미지 34.5G · 옛 ADR-009 blind worktree — 커밋은 main-app/ 경로로 이미 반영 확인) → 55G 여유 · 전체 테스트 재실행(백그라운드) · 코드 듀얼 리뷰 루프 1회차 시작(base 9ab4f768 · 미러 6.0M — k6 결과 제외 · 측정 DB 더미 비밀번호 'seat' 미러에서 redact) | 진행 |
| 10-23 10:40 | 코드 리뷰 루프 1 회수: codex 1(M) · Opus 6(M 1 · L 5) → 종합 RC-1~7 전부 채택 · 수정 워커(Opus · worktree — 전체 테스트가 메인 작업트리에서 도는 중) | 진행 |
| 10-23 10:48 | 전체 테스트(HEAD ea6cd0b9 코드 · 메인 실행 `./gradlew test --continue` 17m38s rc=0): 677건 · 실패 0 · 오류 0 · 건너뜀 0(구현 661 + blind 16) | 리뷰 수정 대기 |
| 10-23 11:34 | 리뷰 수정 회수(Opus · worktree fix/adr012-rc): 5커밋 cherry-pick → b7bf323b(설정 검사를 Flyway 앞 — HoldStrategyMigrationGate · run()에서 재검사) · 4e44ca30(활성 트리거만) · 61b67ac8(판정 0/1/2 · 소문자) · 7e1fa0a6(StartupCheckOrderTest 4건 — ea6cd0b9 코드로 되돌리면 3건 실패 확인) · dacba19d(안내 보정) · 워커 전체 테스트 681/0(17m45s) · 판정 함수 원시값 9ab4f768→1 · ea6cd0b9→0 · 없는 SHA→2 · 93dd610a→1 · 빈 인자→2 · run.sh --cond default(ea6cd0b9) exit 2 · campaign jvm-baseline dry-run 옛 SHA default · 새 SHA A+C(RC-7 해소) · 코드는 4afad003과 동일(diff 0) · 리뷰 루프 2회차 시작(Opus ∥ codex — 누적 diff + 수정 diff 참고) | 진행 |
| 10-23 11:42 | 리뷰 루프 2 회수: codex 1(M) · Opus 3(M 1 · L 2) — 신규 채택 4(RC-8~11) · 수정-새결함 0(Opus: 루프 1 수정의 회귀·순환 의존 없음) · **정지 규칙 주의**: RC-8은 RC-1과 같은 불변식('거부된 기동은 스키마를 바꾸지 않는다')의 남은 경로 — 2회째 수정 · 이번에도 깨지면 설계를 되돌려 재슬라이스(core §3) · 수정 워커(Opus · worktree) | 진행 |
| 10-23 12:51 | 루프 2 수정 회수(Opus · worktree fix/adr012-rc2): cherry-pick 6커밋(… d4875ca8 — 코드는 b8d4dba9와 diff 0) · 워커 전체 테스트 685/0 · 새 테스트 3건 dacba19d 코드에서 실패 확인 · RC-9 원시값 9ab4f768·cc906369·93dd610a→0 · dacba19d→1 · 없는 SHA·728c236c→2 · 판단 지점 5(V4·V8 경로 — 같은 불변식의 남은 구멍) → **정지 규칙 적용**: 3번째 덧대기 대신 사용자 결정 '범위 밖으로 기록'(AskUserQuestion) → 거부 메시지 안내 + ADR §7-13 ⑦ · 전체 테스트 재실행 + 리뷰 루프 3(마지막) 시작 | 진행 |
| 10-23 13:00 | 리뷰 루프 3(마지막) 회수: Opus H·M 0 · L 3(계약 K1~K10 위반 없음 — 렌즈 8개 applicable 근거 file:line) · codex M 2 → 기각 2(RC-L3-1·2 — 옛 SHA 캠페인은 그 SHA 하네스로만 이어 돈다: run.sh 하네스=SHA 검사가 교체 전부터 있음 · 새 비교 항목은 옛 하네스에 없음) · L 3 채택(RC-12~14) → 주석·ADR §7-13 ⑧ 기록(동작 무변경) · **루프 3에 신규 채택 3(L — 문서·주석) → 종료 조건 '신규 채택 0' 미충족 · 최대 3루프 도달** → 사용자 보고 | 사용자 결정 대기 |
| 10-23 13:55 | 사용자 결정 '이대로 마감'(AskUserQuestion — 루프 3 신규 채택 L 3건은 주석·ADR 기록 · 동작 무변경) → **user override 기록**: 잔여 위험 = ADR §7-13 ⑦·⑧(비표준·측정 전용 경로) · 전체 테스트(HEAD 12e3b9fd 코드 · 18m29s rc=0): main-app 679 · 실패 0 · 오류 0(reconciler-app 6 — 변경 없음 up-to-date) · 안전선: 스모크(측정 스모크 2 · K1~K10 부팅 테스트) · diff self-review(리뷰 3루프) · 되돌리기 = 손잡이 K2·K3 · 공개 계약 영향 = 기본 스키마 V9·V10(ADR §7-13) · **검증 완료** → 사이클 마감 | 마감 진행 |
| 10-23 14:11 | 아카이브(Opus 워커 · study-note 브랜치 archive/2026-10-23 · push 전): issue/shell/argument-size-limit ⓐ 65a42fa9 · issue/cross-cutting/data/heuristic-matching-false-positive ⓒ b1e5e52f · 기존 카드 issue/cross-cutting/os/process-group-and-tree-termination 동일 — 무변경 · issue/kotlin/spring/validate-before-migrate ⓐ 513521e9 · issue/cross-cutting/database/hot-update-and-indexed-columns ⓐ 876a7828 · issue/cross-cutting/database/catalog-presence-vs-enabled ⓐ 0d514551 · 후보 7(명령행 같은 키 쉼표 합침) 제외 — log에 근거 없음(테스트 주석뿐) | 카드 4 + 방안 비교 1 |
| 10-23 14:11 | 아카이브 노출 스캔: 원 식별자(seat-reservation-lab·seatlab·labs-commerce·HoldStrategy*·IncrementalSchemaCheck·user_hold_quota·192.168.·/home/·pickjog·ADR-0 등) + 시크릿 + 경로·IP — 워커 커밋마다 0 · 메인 재스캔(main..archive 추가 행) 매칭 10건 = authoring-guide:28 관례 'Claude 초안' 표시(기존 카드 339곳 동일 — 오탐) · 실식별자 0 | push 확인 대기 |
| 10-23 15:31 | 사용자 확인(AskUserQuestion) → push origin feat/adr-012-incremental-reconcile · PR #11(main 대상 · 본문 --body-file · AI 표기 없음) · study-note archive/2026-10-23 → main ff · push fd0f9032..0d514551 | **ADR-012 작업 끝** · 다음: ADR-013(새 작업 폴더) |

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
- review packet(R8 설계 선검증 457ae7dd — ADR-012 §4.1·§5 R8·§7-9 · 명세 §2 R8): rv39 — 미러(ADR-012 · 명세 · drift 코드 · 마이그레이션 · run.sh · compose.reconciler). Opus ∥ codex.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| RD-1 | 1 | opus H1 + codex M(인덱스) | ADR §4.1 홀드 user_id 범위 (a,b]는 다른 회차·다른 사용자 홀드까지 읽음 — 인덱스 부재를 잼 | 채택 — `user_id = ANY(청크 사용자 k명)`로 읽는 대상을 one-shot과 같게 | fixed | (이 커밋) |
| RD-2 | 1 | codex H + opus M5 | split 결과 행 상한 없음 → F 1g 넘칠 수 있음 | 채택 — RD-1로 U 밖 사용자 없음 + 흘려 읽기(목록 안 쌓음) · 메모리 = k명 카운터 | fixed | (이 커밋) |
| RD-3 | 1 | opus M1 | 공유 tx 격리 수준 바꾸면 복구도 RR | 채택 — 탐지 전용 템플릿 · 복구 격리 그대로 테스트 | fixed | (이 커밋) |
| RD-4 | 1 | codex M + opus L3 | 세 조회 중간 실패 처리 없음 | 채택 — 청크 통째로 버림 · 지금 경로 | fixed | (이 커밋) |
| RD-5 | 1 | codex M + opus M3 | 바퀴 중 DB CPU로 ① 몫 못 가름 · 읽은 행 수 기록 곳 없음 | 채택 — 주 지표 ① chunk_ms_total · 보조 DB CPU · ② 시간 겹침을 유효 조건으로 · 읽은 행은 스모크 EXPLAIN | fixed | (이 커밋) |
| RD-6 | 1 | opus M2 | RR 트랜잭션 왕복 비용 | 채택 — chunk_ms 정의에 포함 명시 | fixed | (이 커밋) |
| RD-7 | 1 | opus M4 + codex L | 손잡이가 안 먹으면 조용히 one-shot | 채택 — 기대 설정 검사에 detectShape · F 아닌 조건 거부 · 지정할 때만 키 | fixed | (이 커밋) |
| RD-8 | 1 | opus L1·L2·L4 + codex L | 경계(빈 청크·커서·없는 사용자 0·expires_at)·int 넘침·문서 불일치 | 채택 — §4.1 경계 규칙 · toIntExact · 명세 ① 범위로 통일 | fixed | (이 커밋) |
| RD-9 | p | codex 재점검 | ADR §4.1 split fetch size 없음 → pgJDBC 전체 수신 | 채택 — fetch size = 청크 크기(트랜잭션 안 커서) | fixed | (이 커밋) |
| RD-10 | p | codex 재점검 L | 명세 §0 R8 행이 옛 설계(범위·core) | 채택 — 갱신 | fixed | (이 커밋) |
- review packet(04c 구현 1e7a5746..1ebdc0a4): rv40 — 미러(impl.diff · drift 코드 · SeatHoldProperties · 자원 · drift 테스트 · k6/ADR-009 scripts·compose). Opus ∥ codex.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| RC-1 | 1 | codex L | run.sh:119 `--reconcile-shape ""`가 기본으로 조용히 감 | 채택 — 빈 값 거부(exit 2 확인) | fixed | 7bf6dee6 |
| RC-2 | 1 | codex L + opus L5 | compose.reconciler.yml:24 미지정 시 compose 미설정 경고 · 바깥 셸 값이 샐 수 있음 | 기각 — 빈 값을 늘 넣으면 meta.json app_env가 바뀌어 '미지정 = 렌더 그대로'가 깨진다 · 새는 값은 reconciler-config-mismatch가 잡음 | rejected | |
| RC-3 | 1 | opus L1 | CounterReconciler.kt:503-506 읽기 전용을 고정하는 테스트 없음 | 기록 — ADR §4.1 해석 주의 · 스모크 때 pg_stat_activity로 확인 | open | |
| RC-4 | 1 | opus L2·L3 | fetch size 왕복 · JPA 트랜잭션 비용이 모양 효과를 가릴 수 있음 | 기록 — ADR §4.1 해석 주의(R8 기각 시 먼저 가름) | fixed | 7bf6dee6 |
| RC-5 | 1 | opus L4 | 바깥 트랜잭션 안 runOnce → RC 합류 | 기록 — 지금 호출처 스케줄러뿐 | fixed | 7bf6dee6 |
- review packet(§4.2 설계 a8626d42): rv41 — 미러(ADR-012 · 명세 · main-app/src/main · k6/ADR-009 lib·run·summarize·compose · reconcile-core·reconciler-app · Dockerfile). Opus ∥ codex. 발견별 판단 정본 = ADR-012 §4.2.12(수정 워커가 작성 · 메인 확인).
- review packet(06a a843f08c..7b6d66e5): rv42 — 미러(main-app/src/main · 빌드 파일 · reconcile-core · reconciler-app · Dockerfile · ADR-012 · run.sh · drift 테스트 · refactor.diff). Opus(워커 비교 자료 /tmp/t06a 직접 재대조 · BOOT-INF/lib 펼쳐 겹침 확인) ∥ codex.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| RE-1 | 1 | opus L | /tmp/t06a/dup — 워커 중복 점검이 META-INF를 뺐음 | 기록 — Opus가 lib 전체를 펼쳐 확인: 겹치는 클래스 0 · 겹치는 META-INF는 spring.factories·AutoConfiguration.imports·services 등 합쳐 읽는 종류뿐(순서 없는 리스너 호출 순서만 바뀔 수 있음) | closed | |
| RE-2 | 1 | opus L | dry-run 10세트 같음은 약한 증거(이번 diff가 k6를 안 건드림) | 기록 — 하네스 렌더 보존의 실제 근거는 configprops 비교(Opus가 평탄화 diff로 재확인 — 다른 것은 Testcontainers 포트뿐) | closed | |
| RE-3 | 1 | opus L | SeatReservationApplication.kt:21-22 /actuator/beans에 reconcileProperties 새 빈 | 기록 — §3-4 범위 밖(하네스는 metrics만) | closed | |
| RE-4 | 1 | codex | — | 신규 없음(미러에 git·산출물이 없어 수치 재검증은 Opus 몫) | closed | |
- review packet(06b~06e 0be75d84..d79dc3d7): rv43 — 미러(main-app/src · reconcile-core · ADR-012 · 명세 · impl.diff 5,821줄). Opus ∥ codex.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| RF-1 | 1 | codex H + opus L5 | IncrementalReconciler.kt:297 ⓑⓒ 재시도 상한 넘침 무기록 | 채택 — gap 행·지표 | fixed | 522aade4~379b6824 |
| RF-2 | 1 | opus M1 + codex M | IncrementalReconciler.kt:183-190 기록 INSERT 실패 시 gap 유실 | 채택 | fixed | 522aade4~379b6824 |
| RF-3 | 1 | opus M2 | SafetyNetReconciler.kt:457·484·572 갈래별 강제 첫 블록 대 허용식 | 채택 — 허용식 + 강제 블록 ≤ 3 × chunk_ms_max(R7 rv43 줄 · 테스트 12) | fixed | 522aade4~379b6824 |
| RF-4 | 1 | opus M3 | HoldStrategyStartupCheck.kt:158 증분만 켜고 안전망·C 끔 | 채택 — 기동 실패(테스트 사용 여부 먼저) | fixed | 24e45d44 |
| RF-5 | 1 | codex M + opus L2 | IncrementalSchemaCheck.kt:39-41 트리거 스키마 무시 | 채택 | fixed | 522aade4~379b6824 |
| RF-6 | 1 | opus L1 | IncrementalReconciler.kt:365 접속 중 자기 역할 세션을 숨은 세션으로 | 채택 | fixed | 522aade4~379b6824 |
| RF-7 | 1 | opus L3 | SafetyNetReconciler.kt:586-588 전체 커서·다시 읽기 3회 규칙·기록 | 채택 | fixed | 522aade4~379b6824 |
| RF-8 | 1 | opus L4·L6 | 주석 2곳 | 채택 | fixed | 522aade4~379b6824 |
- review packet(06g 7fcfaaff): rv44 — 미러(k6/ADR-009 scripts·compose · ADR-012 · 마이그레이션 · reconcile-core). Opus(앱 이름 전수 대조 · 기존 10세트 dry-run 직접 재확인) ∥ codex.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| RG-1 | 1 | opus H | run.sh:661·670 · warmup.js:5 · AppPathInjector.kt:35,51-60 예열이 주입 상한을 먼저 씀 → 효과 30회 모두 effect-unexpected | 채택 — 앱: 시드 초기화 때 주입 상태 비움 + 예열 사용자(≥900000) 제외 (메인 판단과 같음) | fixed | 921d9367 |
| RG-2 | 1 | codex H | campaign.sh:211 증분 셀에 --reconcile-shape split 없음(§8.4) | 채택 — 증분·안전망·효과·2' 비교 셀 split · noc·M0는 기본(현재 방식) | fixed | d4db5504·e7e73325 |
| RG-3 | 1 | codex H | summarize.py:1246 R4 적체에 주입 뒤 실행이 섞임 | 채택 — 주입 tx 시작 이전 마지막 안전망 실행 기준 | fixed | d4db5504·e7e73325 |
| RG-4 | 1 | codex M | run.sh:1416 · summarize.py:1365 안전망 상세 행 결손 → R7 runs 0 ok | 채택 — run_id 짝 · 결손 사유 · R7 판정 불가 | fixed | d4db5504·e7e73325 |
| RG-5 | 1 | opus M | summarize.py:1223-1240 retry_overflow kind 빠짐 | 채택 | fixed | d4db5504·e7e73325 |
| RG-6 | 1 | opus L | summarize.py:1276,1298 path='db' 없을 때 R6 False | 채택 — None | fixed | d4db5504·e7e73325 |
| RG-7 | 1 | opus L | campaign.sh:204 효과 세트 tmpfs 12 없음 | 채택 | fixed | d4db5504·e7e73325 |
| RG-8 | 1 | opus L | summarize.py:1995 self_record 하한 | 채택 — 표에 '하한' 표시 | fixed | d4db5504·e7e73325 |
| RG-9 | 1 | opus L | inc_effect 전체 커서만 잡는 D·L1·H에 120초 기준 | 채택 — 쌍별 '예상 탐지 갈래' 열(R4 규칙 무변경) · 스모크에서 바퀴 시간 확인 | fixed | d4db5504·e7e73325 |
- review packet(06f 63576c13..3ae06c2a): rv46 — 미러(reconciler-app · reconcile-core · k6/ADR-009/scripts · ADR-012 · 명세 · Dockerfile · settings · ext.diff 932줄). Opus(원격 명령 전수 목록 · 비밀번호 흐름) ∥ codex.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| RX-1 | 1 | opus M1 | lib.sh:496-511,521 배포가 ADR §4.2.8(PC 빌드 + save·load)과 다름 — .158에서 빌드(기반 이미지 pull·빌드 캐시) | 채택 — ADR대로 되돌림(원인: 메인 브리핑이 'git archive로 배포'라 적음 — 브리핑 실수) | fixed | c82cf854 |
| RX-2 | 1 | codex H + opus L4 | lib.sh:468-469,521 EXT_DB_USERNAME·SERVER_HOST 따옴표 주입 | 채택 — 형식 검증 | fixed | c82cf854 |
| RX-3 | 1 | opus M2 | lib.sh:566 docker logs --since PC 시각 | 채택 — --since 제거 | fixed | c82cf854 |
| RX-4 | 1 | codex M | run.sh:1485 .158 표본 공백 검사에 시계 차 미전달 | 채택 | fixed | c82cf854 |
| RX-5 | 1 | codex M | lib.sh:560·run.sh:1486 docker stats 실패를 시각 행이 가림 | 채택 | fixed | c82cf854 |
| RX-6 | 1 | codex M | run.sh:890 회차 끝 외부 앱 down 실패가 사유에 없음 | 채택 — external-down-failed | fixed | c82cf854 |
| RX-7 | 1 | opus L3 | lib.sh:518 env 키 화이트리스트 없음 | 채택 | fixed | c82cf854 |
| RX-8 | 1 | opus L5 | read -r에 IFS= 없음 | 채택 | fixed | c82cf854 |
| RX-9 | 1 | opus L8 + codex L | 비밀번호가 컨테이너 env·docker inspect·config.v2.json(root 전용)에 남음 · compose config 평문 | 잔여 위험 기록 — env 방식(ADR §4.2.8 ③)의 불가피한 경로 · 하네스는 Env를 읽는 inspect·원격 compose config를 쓰지 않음 · 사용자에게 알림 | fixed | c82cf854 |
| RX-10 | 1 | opus L6·L7 | 포트 검사 127.0.0.1만 · .158 이미지 정리 경로 없음 | 기록 — 포트는 실패가 드러남 · 정리 명령(프로젝트 태그 한정) 문서화 | fixed | c82cf854 |
| RX-11 | p | codex 재점검 | lib.sh:530 라벨만 같은 다른 이미지를 다시 짓지 않음 · lib.sh:540 층만 같고 설정이 다른 이미지 | 채택 — PC에서 늘 다시 지음(캐시) · 지문에 `{{json .Config}}` 포함 | fixed | da1718dd |
| RR-1 | 1 | opus F1(H) + codex F2(M) | ADR-012:810·839·847-848·890 R5 '판정 불가'를 선언한 자료로 '성립' | 채택 — §8.5-6 정정 · ⓑ·M3 셀 R5 보류 · 사용자 결정 추가 측정(§8.9) | fixed(§8.9 — R5 성립) | 473ae26c |
| RR-2 | 1 | opus F2(M) | 2' noc rep3 재측정이 캠페인 끝에 돌아 noc 범위 상한을 만듦 | 채택 — §8.8-1 민감도 기록(M2 R3 판정 캠페인은 2단계) | fixed | a76eb798 |
| RR-3 | 1 | opus F3(M) | §8.8-3 '범위가 겹친다'는 원자료와 반대 · 규칙 1 미대입 · §8.7 단정 | 채택 — 비겹침으로 정정 · 규칙 1 대입 · 조건부 문구 | fixed | a76eb798 |
| RR-4 | 1 | opus F4(M) | M3 안전망 바퀴 완료 1 대 3 · 적체 460만 대 200만 누락 | 채택 — §8.8-3 하위 항목 | fixed | a76eb798 |
| RR-5 | 1 | opus F5(M) | AE(만료 경로 앱 주입) 0쌍 — 상한 60을 AH·AL이 소진 · R6 범위 과장 | 채택 — R6 선점 경로 한정 · §8.9에서 AE 확인 | fixed(§8.9 — 트리거 1초 AE 138쌍 위반 0) | 473ae26c |
| RR-6 | 1 | opus F6(L) + codex F3(L) | 주입 120쌍 · 2,160쌍 오기 | 채택 | fixed | a76eb798 |
| RR-7 | 1 | codex F1(H) + opus F7(L) | R8 유효 조건 사후 재해석 | 채택(문구) — '측정 전 문구로는 보류 · split은 측정 뒤 사용자 결정' 명시 · 결정은 유지(사용자 결정 10-20) | fixed | a76eb798 |
| RC-1 | 1 | opus F1(M) | HoldStrategyStartupCheck.kt:36-42(ApplicationRunner — Flyway 뒤) · :98-101 · IncrementalSchemaCheck.kt:56-60 | 채택 — 설정만으로 판정되는 거부를 Flyway 앞으로 · '거부 → 안내 키 → 같은 DB 재기동' 테스트 | fixed | b7bf323b · 7e1fa0a6 |
| RC-2 | 1 | codex F1(M) | IncrementalSchemaCheck.kt:39 트리거 존재만 셈(tgenabled 미확인) — 기본 경로가 됨 | 채택 — 활성 트리거만 인정 + 테스트 | fixed | 4e44ca30 · 7e1fa0a6 |
| RC-3 | 1 | opus F2(L) | HoldStrategyStartupCheck.kt:131-133 · CLEANUP_HINT — 비카운터에 틀린 안내 | 채택 | fixed | b7bf323b |
| RC-4 | 1 | opus F3(L) | HoldStrategyStartupCheck.kt:188-191 K6 뒤 도달 불가 갈래 · 문구 | 채택 | fixed | b7bf323b · dacba19d |
| RC-5 | 1 | opus F4(L) | k6/ADR-009/scripts/lib.sh:417-423 판정 불가를 '교체 전'으로 접음 · 대소문자 | 채택 — 경로 없음 1 · 그 밖 오류 2 · 소문자 비교 | fixed | 61b67ac8 |
| RC-6 | 1 | opus F5(L) | application-reconciler.yml:14-21 F 키 없이 → 업무 앱 기본 위치로 V9·V10 적용 | 채택 — yml 주석 + ADR §7 운영 위험 | fixed | b7bf323b |
| RC-7 | 1 | opus F6(L) | K9 하네스 자동 확인 없음 | 채택 — 두 SHA dry-run·판정 함수 원시값을 log에 기록 | fixed | log 이 행 |
| RC-8 | 2 | opus R2-1(M) | HoldStrategyStartupCheck.kt:35-40 게이트가 checkConfig만 · IncrementalSchemaCheck.kt:73-78·88-93 방식↔V9 위치·max_prepared_transactions는 마이그레이션 뒤 | 채택 — 위치↔방식 대응·V10 필요·max_prepared_transactions를 마이그레이션 앞에서 · 남은 트리거 거부 메시지에 K3 안내 · 회귀 테스트(위치 뺀 K2) | fixed | d5739fcc→cherry · d4875ca8 |
| RC-9 | 2 | codex F-1(M) | k6/ADR-009/scripts/lib.sh:418 · run.sh:253 · campaign.sh:143 default 허용을 incremental.enabled 하나로 판정 | 채택 — 'C 켬 그리고 증분 꺼짐'일 때만 default(옛 C) 허용 | fixed | 7abcb848→cherry |
| RC-10 | 2 | opus R2-2(L) | Adr012DefaultsContractTest.kt:220-223 K1 safety-app source 미측정 | 채택 | fixed | db9138ca→cherry |
| RC-11 | 2 | opus R2-3(L) | cfgkeys.py:340-350 on-profile 문서 무시 | 채택 — on-profile 문서가 있으면 판정 불가(2) | fixed | 1a6d467a→cherry |
| RC-L3-1 | 3 | codex(M) | campaign.sh:154 · run.sh:269 옛 SHA 캠페인 현재 하네스 resume 불가 | 기각 — 하네스=SHA 규칙(run.sh SHA 일치 검사 · 교체 전부터)으로 옛 캠페인은 옛 하네스로 이어 돈다 · 회귀 아님 | — | — |
| RC-L3-2 | 3 | codex(M) | run.sh:1439 옛 앱 configprops를 새 필드로 판정 | 기각 — 같은 이유(옛 SHA = 옛 하네스 · 새 비교 항목 없음) · --worktree 스모크만 해당(구현자 보고 판단 7) | — | — |
| RC-12 | 3 | opus L-1 | IncrementalSchemaCheck.kt:62-73 afterLocations가 적용 여부 구분 안 함(비표준 경로) | 채택 — 주석 정정 + ADR §7-13 ⑧ 기록(동작 무변경 · 마이그레이션 뒤 검사가 거부) | fixed | ef4799cc · 12e3b9fd |
Je3b9fd |
| RC-14 | 3 | opus L-3 | HoldStrategyStartupCheck.kt:30 불변식 주석이 범위보다 넓음(V5·V7) | 채택 — 주석을 실제 범위로 | fixed | ef4799cc |

## 완료 요약 (2026-10-23 — 초안 Opus 워커 · 스니펫은 git 출력 그대로)

**무엇이 됐나**
- 측정 7캠페인 99회 전부 ok(비정상 0): adr012s1 9 · adr012r8 9 · adr012s2 24 · adr012eff 18 · adr012s2x 21(noc rep3·c rep3 재측정) · adr012effx 12 · adr012r5 15.
- 판정: R1(C 바퀴 비용 출처) 유지 — 비용은 DB 쪽 · R8 split 채택(측정 뒤 사용자 결정 · 재해석 공개) · M2(앱 안 증분) 성공 = ⓐ(메모리 집합) 1초 · ⓑ(DB 트리거 updated_at) 1초 → 규칙 2로 **ⓑ 1초** · M3(외부 앱 .158) 4셀은 R5만 보류 — 선택 배포 · ADR-020 기준선.
- 기본값 교체(명세 §10 K1~K10): application.yml만 — A 켬 · 앱 안 증분 ⓑ 1초 + 안전망 · split · C 끔 · Flyway V9 트리거·V10 기본. 구현 39a11a8d · 3acc4cc9 · d40b2195 · 3251efe6 · blind ea6cd0b9 · 리뷰 수정 b7bf323b … ef4799cc.
- 번호 이동 9차: Kafka·복제본 → ADR-020(보류) · 014~020 → 013~019(8c2df227 · 826c6874 · 5215851b).

**핵심 diff**

① `main-app/src/main/resources/application.yml` 9ab4f768 → HEAD
```diff
+  flyway:
+    locations: classpath:db/migration,classpath:db/migration-updated-at-trigger,classpath:db/migration-incremental
-        enabled: true
+        enabled: false
+        detect-shape: split
+        incremental:
+          method: trigger
+          enabled: true
+          interval: 1s
+        safety-net:
+          enabled: true
```

② `reconcile-core/.../IncrementalSchemaCheck.kt` HEAD — 활성 트리거만 인정
```kotlin
127:        val triggerActive = states.any { it == "O" || it == "A" }
128-        val updatedAtMethod = method == IncrementalMethod.TRIGGER || method == IncrementalMethod.STMT
129-        if (updatedAtMethod) {
130-            check(column && index) {
```

③ `main-app/.../HoldStrategyStartupCheck.kt` HEAD — 거부될 기동은 마이그레이션 앞에서
```kotlin
44:    fun holdStrategyFlywayMigrationStrategy(properties: SeatHoldProperties, environment: Environment) = FlywayMigrationStrategy { flyway ->
45-        HoldStrategyStartupCheck.checkConfig(properties, environment)
46-        val config = flyway.configuration
47-        val target = config.target
58:        flyway.migrate()
```

**검증**: 측정(위 7캠페인 — 핵심 수치 ADR §8.1~8.9) · 전체 테스트 main-app 679 · 실패 0(reconciler-app 6 변경 없음 — 워커 실행 685/0과 같음) · blind 16(교체 전 12 실패 → 16/16) · 결과 문서 듀얼 RR-1~7(R5 판정 충돌 → adr012r5 추가 측정으로 해소) · 코드 루프 3회 RC-1~14 채택 · RC-L3-1·2 기각 · 루프 3 신규 L 3 → **user override**(잔여 위험 ADR §7-13 ⑦·⑧).
**배운 것**: ① 같은 불변식을 두 번 고쳐도 구멍이 남으면 범위를 좁혀 기록한다(정지 규칙 — RC-1 → RC-8 → V4·V8 범위 밖) ② 하네스 무음 실패(빈 meta.json — jq 인수 한도)는 회차 상태를 'ok'로 둔 채 숨는다 → 쓰기 뒤 검증 + meta-write-failed 사유.
**남은 빚**: 없음(DEBT=0). push·PR은 사용자 확인 대기. 측정 안 한 셀(§8.9) · M3 안전망 느린 원인 · 원시 CSV 보관은 NEXT N0a.
