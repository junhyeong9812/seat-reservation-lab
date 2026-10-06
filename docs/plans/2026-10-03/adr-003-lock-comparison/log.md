# log — ADR-003 같은 좌석 경합: 락 방식 비교

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-02 19:45 | 인터뷰 1·2차(ADR-002 세션 중 — `docs/plans/2026-09-29/adr-002-db-baseline/log.md` 19:45) | 명세 §0 |
| 10-03 ~00:43 | 브랜치 `feat/adr-003-lock-comparison`(main 48b4136) · 선점 경로 코드 확인(HoldSeatService·ProductSeat·HoldLimitPolicy·ProductSeatRepository·ConfirmReservationService) → 인터뷰 3차(synchronized 2변형·Redis 관문·하네스 보강 4종·와이파이 유지+단절 판정) | 명세 작성 — 전략 11종, 약 70h(S4 계단 확장 반영, 인터뷰 때 63h) |
| 10-03 ~00:47 | 명세 합의 "합의 — 이대로 진행" · 모드 auto → set-state SPEC=1·MODE=auto | task 01부터 |
| 10-03 ~00:52 | task 02 구현: Redisson 4.8.0 + data-redis, V3 version 열(엔티티 미매핑 — @Version은 전 전략에 걸려 기준선 변경), V4 유니크는 별도 location(db/migration-unique), HoldSeatProcess(MANDATORY)+전략 11개, 기동 검사(유니크 인덱스↔전략, Redis ping), loadtest reset이 Redis 전략에서 flushDb | 컴파일 OK |
| 10-03 ~00:55 | **가정 1 스모크(기존 테스트) 14/38 실패** — NPE: `HoldSeatProcess.hold`의 기본 인자 `productSeatRepository::findByIdAndScheduleId`가 호출 쪽(@Transactional CGLIB 프록시)에서 계산돼 프록시의 null 필드를 참조 | 기본 인자를 null로, 본문에서 선택. **CS 이슈 후보**(Kotlin 기본 인자 × Spring 프록시) |
| 10-03 ~00:56 | 수정 후 기존 테스트 38/38 green — **가정 1 실증**(기본 none = 기준선 동작) | 전략 테스트 작성 |
| 10-03 ~00:59 | 전략 테스트 작성(task 02·03): HoldStrategyTest 18(계약 동일 ×8 · 경합 차단 6전략 50동시 정확히 1 · 대조군 2 응답=DB · NOWAIT 즉시 409 vs 비관락 대기) · UniqueHoldStrategyTest 3(V4 location + 기동 검사 불일치 거부) · RedisHoldStrategyTest 4(NX·분산락 50동시, 키 TTL, DB 패배 시 키 해제) | 전체 63/63 green |
| 10-03 01:01 | 커밋 95d0bd8(전략 11종+테스트) | — |
| 10-03 01:17 | task 04 하네스: k6/ADR-003(전략·풀·앱 대수 축, Redis 상시, 2앱 오버레이+nginx, strategy-mismatch·path-gap 판정, 락 대기 0.5s·Hikari 1s, S1 t0 공정성, S4 16단계, 배포 재시도, 회차 우선 캠페인+path-gap 재측정). **S4 16단계 누적 요청 약 197만 > 시드 100만 석 → 없는 좌석 404가 성공률을 깎을 것을 발견** → S4 시드 200만 석 | gapcheck 자체 검증 3경우(정상 0·공백 1·표본 없음 1) · 커밋 38af24f |
| 10-03 01:18 | 스모크 4종 시작(systemd 유닛 seatlab-adr003-smoke): A redis-lock S1 L4 · B jvm-lock 앱 2대 S1 L4 · C unique S1 L4 · D none S4 L4(가정 2) | 대기 중 task 01: ADR-003 문서 가설 H1~H8·선택지·기준·계획·§6.1 |
| 10-03 01:20 | 로그 시각 정정: 01:18 이전 행 일부를 추정 시각(실제보다 늦게)으로 적었음 — main 병합 00:41·커밋 01:01/01:17 기준으로 `~` 표기 보정 | 이후 행은 `date` 확인 후 기록 |
| 10-03 01:26 | 스모크 1차 결과: A redis-lock S1 ok(201=1) 그러나 **timeline-locks 빈 파일**(SQL 별칭 t 충돌 → 매번 오류, 2>/dev/null로 무음) · B 2앱 **compose 무효**(extends에 file 없음) → up 실패를 넘겨 **A의 컨테이너를 잼**(invalid-app-config로 우연히 걸림), down도 매 회차 실패해 볼륨 미삭제 → C 결과 무효 · D는 작업트리≠SHA로 거부(설계대로) | 수정 커밋 → 스모크 2차(seatlab-adr003-smoke2). **CS 이슈 후보**: 기동 실패를 넘긴 측정이 이전 실행 대상을 잼 · 오류를 버리는 수집기의 무음 실패 |
| 10-03 01:38 | 스모크 2차(ee2843f): A redis-lock S1 201=1 ok · B jvm-lock **앱 2대 201=2**(앱마다 1, 판정기 중복 1 — H3 첫 신호) ok · C unique 201=1·인덱스 6 ok · D none S4 L4 ok **엄격 한계 4,300**(6,487 단계 처리 2,701/s·p99 4.1s·dropped 131만) — ADR-002 c01 L4는 5회 모두 ≥6,294. 공정성: S1 요청 시작 65ms 안 48개 ms, 같은 ms 최대 39 → 순위는 동순위로 거칠다 | **기준선 변화 의심** — 시기 드리프트 vs 이번 변경(락 표본 docker exec 2Hz·Hikari 폴링·Redis·시드 200만)을 가르려 ADR-002 하네스(557dff4) c01 L4 S4 1회 재측정(calib) |
| 10-03 01:37 | 보정 1차 거부: ADR-002 run.sh가 557dff4 ≠ 작업트리(이후 분석 스크립트 추가) → 0aeebc6(앱·시나리오·실행기 557dff4와 diff 0 확인)로 재실행 | — |
| 10-03 01:46 | **보정 결과: ADR-002 하네스·앱 그대로 c01 L4 S4 = 엄격 한계 4,300**(6,487 단계 처리 2,646/s·p99 5.9s) — ADR-003 변경과 무관. 6,487 단계 서버 CPU 앱 150%·DB 118%(9/30은 372%·258%) → 서버 아님, 요청 경로 쪽 | 결과는 ADR-002 결과 트리를 건드리지 않게 k6/ADR-003/results/calib-adr002-harness-c01-L4S4로 이동 |
| 10-03 01:48 | **경로 변경 발견**: 서버 경로 = 유선 eno1(192.168.55.114, 1Gbps, 같은 서브넷·NAT 없음) — 10-01 12:01 재부팅 때 링크 업, 기본 경로 metric 100(와이파이 600). ADR-002 c00~c03은 와이파이, **c04~c09(10-01 12:07~)는 유선** → ADR-002 D2(풀 20·40 < 풀 10)·D7(배경 100만)이 경로와 교락. 노트북 유휴 부하 4~5(WebKitWebProcess 113% 등, 9/30은 2) | 사용자 결정 필요: ADR-003 측정 경로·원인 조사·ADR-002 정정 |
| 10-03 01:55 | 경로 조사: ① 404 요청 계단 — 앱이 오류 처리만으로 CPU 400% 포화(경로 측정 실패) ② seatlab-pathcap nginx(204, 8101) 3천·5천·7천·1만/s — **누락 0, p99 0.6ms**(nginx CPU ≤11%) → 유선 경로·k6 PC는 병목 아님 ③ 보정 회차 초당 곡선: 6,487 단계 진입 순간 처리 2.5천/s·지연 1~5s(9/30은 6.2천/s·최대 200ms) ④ 서버 커널 'SYN flooding on 8101' 경고는 9/30·10/3 모두 있음(출력 제한으로 회차 시각 일치 불명) | 원인 미확정. 검사 nginx·설정 폴더 삭제(seatlab 컨테이너 0) |
| 10-03 08:51 | 사용자: "진행하자 와이파이랑 유선은 우선 보류" → 현재 유선으로 진행, 원인 조사 NEXT로. 명세 §0 기록 | ADR-002 정정 섹션 작성 → 리뷰 → 본측정 |
| 10-03 08:52 | ADR-002 정정 섹션 추가(경로 변경 10-01 12:01 — c04~c09 유선, D2 '풀 20·40 < 풀 10' 철회, D7 L4 저하 폭 판정 보류, D1·D3·D4·D5·D6 유지) · 측정 전 리뷰(中 듀얼 1패스) 시작 — packet: base 48b4136 / OUT /tmp/tmp.zAAXrm5wZl / mirror /tmp/tmp.BMoIX6FUar (smoke2 산출 일부 포함). 보안 스캔: `token` 변수명 2건 오탐. codex 사용 한도(10-04 20:53) → Opus ∥ Sonnet | 리뷰 대기 |
| 10-03 09:06 | 리뷰 회수: Sonnet 9 + Opus 7 + OQ 3 → 종합 M1~M9 채택(전략 11종 정확성은 양쪽 모두 결함 없음). 양성 대조 추가 중 **1b가 50명×5라운드에서 중복 0** — 로컬 DB 커밋이 빨라 '락 해제~커밋' 틈이 거의 없음 → 1b는 일관성만 단언, H2는 본측정이 판정 | 수정 커밋 f9eea28 — 테스트 66/66 green · 스모크 3차(pessimistic S1 · 2앱 jvm S1 · redis-nx S4) 시작 |
| 10-03 09:19 | 스모크 3차(f9eea28): pessimistic S1 201=1·**락 대기 최대 8**(풀 10)·획득 대기 max 265ms · 2앱 jvm 201=2·두 앱 모두 요청 처리(1,308/1,661) · redis-nx S4 ok, 키 64만에 Redis 100MB(197만이면 ~310MB < 1g), 한계 4,300(유선 상한과 같음) | post-fix 재점검(Opus): R1·R2·R4~R9 해소, R3 부분 + 신규 N1~N6 |
| 10-03 09:23 | N1~N6 수정(1b 테스트 사용자 대역·누적 지표 직전/직후 차분·조회 재시도·실패=미측정·보존 회차 분리·연속 실패는 새 status만) → 테스트 66/66, 커밋 96e2e6c | 재점검 2회차는 하지 않음(中 1회 규칙) — 스모크 4차로 확인 |
| 10-03 09:25 | 스모크 4차(96e2e6c): pessimistic S1 증가분 요청 1,000·획득 대기 평균 52ms · 2앱 jvm 앱별 502/498 · 둘 다 ok | — |
| 10-03 09:26 | **본측정 시작**: campaign.sh --sha 96e2e6c --id 20261003-adr003-96e2e6c — 유닛 seatlab-adr003(ManagedOOMPreference=avoid, Restart=on-failure 180s·6h 5회), 조건 36개 × 5회 회차 우선 | 약 70h 추정 |
| 10-03 10:08 | 사용자 요청: 선택지 아래 1a부터 전 방식의 DB 쿼리·앱 코드 스니펫·락 방식·워크플로우상 락 구간을 소제목으로 → ADR-003 §2.1~2.13 작성(코드는 실파일 그대로, SQL은 코드·S5 기준 — Hibernate 생성 SQL 형태는 측정 중 k6 PC에서 테스트를 돌리면 측정이 오염되므로 **캠페인 후 SQL 로그로 대조**(확인 전 표시)). 표의 redis-nx `PX` → `EX` 정정 | 이월: 캠페인 후 SQL 로그 대조 |
| 10-03 10:28 | 사용자 질문: 줄무늬 65,536이 여러 공연·호텔에 적은가 → 좌석 수 상한이 아니라 락 수 — 거짓 경합 확률은 동시 처리 수/65,536(워커 200이면 ~0.3%), 측정(S4 연속 좌석)에서는 사실상 0. ADR §2.3에 설명·대안 추가 + §2.4.1 1a vs 1b 비교(코드 순서·단계표·시간축·틈의 길이·고치는 법) | — |
| 10-03 10:35 | 사용자 정리: JVM 락 = DB 조회 앞 JVM 대기열(앞 처리가 끝날 때까지 앱 실행 지연) → ADR §2.3 'JVM 락의 본질' 추가 — 줄무늬/키별 락/메모리 관문 비교표와 비교하지 않은 이유. **발견: ReentrantLock() 기본 비공정 → 1a는 FIFO 아님, H7(대기형은 먼저 온 요청이 이긴다)이 1a에 성립 안 할 수 있음** | 키별 락·메모리 관문 실험은 하지 않음(사용자 고민 끝 기록으로) |
| 10-04 12:04 | 1회차 16.0h 분해: S3 11.0h(69%)·S1S4 3.8h·풀20 1.0h·2앱 0.2h. S3 1회차 분석(11전략×3이탈): 확정·409 재시도·포기·에러·지연·커넥션 대기 모두 전략 간 같음(L4 S3 부하가 처리 능력보다 한참 아래), 차이는 none(2/1/1)·1b(0/1/2)의 **일시 중복 홀드** — 끝 상태 판정기(v_duplicate_hold_seats)는 0으로 놓침(한 쪽 확정 후 다른 홀드는 판정 전 만료) | 사용자 선택 A: S3 3회 → campaign.sh --s3-reps 커밋 b574543(측정 대상 diff 0), 조건 경계 전환 감시 유닛 seatlab-adr003-switch |
| 10-04 12:14 | 캠페인 전환 완료: 조건 경계(optimistic-p10-s3 rep2 exit) → seatlab-adr003 정지 → seatlab-adr003b(sha b574543, --s3-reps 3) 같은 id 재개 | 끊긴 회차 없음 |
| 10-04 18:21 | 사용자: 지연 변형은 새 ADR로(A) → 작업 트리 feat/adr-003-s6(/home/jun/project/lab/backend-labs-commerce/seat-lab-s6 — 본 캠페인 자동 재개의 하네스 SHA 검사를 깨지 않게)에서: ADR-004~010 → 005~011(축약 표기 2곳 수동 정정), 새 ADR-004(임계 구역 길이·락 보유 시간) cad082e · S6 구현(지연 설정·s6-contention.js·--delay-ms·--suite s6·S6 요약·지연 테스트) 147051f — 컴파일·k6 inspect만(테스트·스모크는 캠페인 종료 후: k6 PC·서버 공유) | 본 캠페인 종료 후 병합 → 테스트 → 스모크 → S6 캠페인 |
| 10-05 19:32 | **본측정 캠페인 종료** — 434/434(S3 4·5회차는 계획대로 생략), 비정상 회차 0, 재측정 0, 경로 내내 유선 | — |
| 10-05 21:36 | feat/adr-003-s6 병합(853ef7a) → 테스트 68/68 green(지연 테스트 2 포함) → S6 스모크(pessimistic·지연 20ms·K 1/10/100·L4) 시작 | — |
| 10-05 21:49 | S6 스모크(pessimistic·지연 20ms·K 1/10/100) 3셀 ok — **이웃 스트림 번짐 확인**: K=1 이웃 p99 2.5s→6.5s, 이웃 처리 464→322/s(목표 500), 커넥션 획득 대기 평균 190ms·최대 841ms, 중복 0 | S6 하네스 동작 확인 |
| 10-05 21:52 | Hibernate 실제 SQL 대조(측정 없는 틈에): 로그 레벨 환경변수(LOGGING_LEVEL_ORG_HIBERNATE_SQL)는 Spring이 로거 이름을 소문자로 바꿔 org.hibernate.SQL에 안 먹음 → spring.jpa.show-sql로. 결과: 비관락 `for no key update`(nowait 포함) 확인 · 1인 2매 홀드 수는 `left join` · **flush에 `update seat_hold set seat_id=?`가 하나 더**(단방향 @OneToMany @JoinColumn — 선점 1회 쓰기 3문장) | ADR-003 §2 SQL을 실측으로 교체, '확인 전' 0. CS 이슈 후보: 단방향 OneToMany의 추가 FK UPDATE · 환경변수 로그 레벨 대소문자 |
| 10-05 21:53 | **S6 캠페인 시작**: campaign.sh --sha 853ef7a --suite s6 --reps 3 --id 20261005-adr003-s6-853ef7a — 유닛 seatlab-adr003-s6(Restart=on-failure), 조건 22 × K 3 × 3회 = 198회 | 약 12~13h, 10-06 오전 종료 예상 |
| 10-06 09:42 | **S6 캠페인 종료**(CAMPAIGN.log 기준) — 198/198, 비정상 0, 유선 | 요약·교차표·errsplit·stalls 산출(compare.py에 S6 단계표 추가 — 미커밋) |
| 10-06 10:20 | ADR-003 §7(결과)·§8(결정 제안 — 3b nowait 기본, 2 차선, 사용자 확인 대기)·§9·§10, ADR-004 §5~§8 작성. 기록 전 전수 확인: 대조군 뺀 577회 판정기 위반 0 · S6 일시 중복 9방식 전부 0 · 대조군 양성(경합 셀). **발견: 지연 20ms는 첫 단계부터 포화** — 이웃 500/s×20ms = 풀 10, Hikari 대기 최대 189~190, k6 dropped 7.8만~13.3만(VU 상한) → 방식 차이는 K=1 − K=100(바닥)으로만 판정. 이전에 구두로 제안한 '2 conditional 기본'을 ADR-004 결과(묶음 C — 진 쪽도 지연을 씀)로 **3b로 바꿔 제안** | 다음: 中 듀얼 1패스 리뷰(문서) |
| 10-06 10:2x | 中 듀얼 1패스(결과 문서) 시작(시각은 10:20 기록 직후 — 분 단위 미기록) — packet: git diff HEAD(ADR-003·004·compare.py) + spec, 미러 = tracked + 두 캠페인 요약 파일(원시 csv·gz 제외), `$OUT=scratchpad/rv.8l3L`. 보안 스캔: compose 테스트 DB 비밀번호 `seat`·`${DB_PASSWORD}`·UUID 토큰 변수 — 공개 repo 기존 내용, 오탐 판정 | codex(medium) ∥ Opus 워커 |
| 10-06 10:41 | 회수(10:20~10:41 사이, 개별 시각 미기록 — 10:41 일괄 기록): codex 6건 · Opus 12건 + OQ 4. 메인 재현: 577→523(미측정 66회 포함 오류) · 7,289는 좌석 수/16,421은 초과 홀드 수(S6 TTL 60분 — 만료 없음) · S6 에러 합 3,616 = 2×(10,000−8,192) 33회 · 첫 승자 순위 ≥10 27%·≥20 15% · L2 S4 에러 11단계 시작 = redis-lock p10·p20, pessimistic p20, advisory p20 · 0-1220 69/140회 최대 255 — 전부 확인 | 중복 병합 후 채택 14 · 기각 0 (D1~D14) |
| 10-06 10:41 | 수정(같은 구간): compare.py(S6 열 '핫 201/s'·중복 좌석/초과 홀드/일시 3단위·Hikari 대기·dropped·n=사용/전체·빈 셀 미측정 행, S4 json acquire, 판정기 전수 절 신설) → errsplit 뒤 두 캠페인 COMPARISON 재생성(응답 분류 절 포함). ADR-003 §7.2·§7.3①②③④·§7.5·§8·§9·§10, ADR-004 §5.1~§5.5·§6·§7 정정 | 다음: post-fix 타깃 재점검(codex) |
| 10-06 10:45 | post-fix 재점검(codex, 미러 rv.8l3L/mirror2 — 수정 전·후 diff + 재생성 결과): D1~D9·D11~D14 해소, D10 미해소(redis-nx '세 조건 모두' 과장), 신규 N1(에러 상한 아래가 redis-nx뿐 — 틀림, nowait·jvm·redis-lock도 아래)·N2(획득 대기 다른 방식 범위에 jvm·redis-lock K=1·10 29~45 누락) → 수정. 신규 결함 2건은 수정 경로에서 나온 것(D3 표현) — 中 규정상 재점검 반복 없음, 메인이 데이터로 재확인(에러 합 방식별 최소~최대) | 리뷰 종료 |
| 10-06 11:32 | 사용자 피드백 반영: 용어 정의(ADR-004 §5.0 — 핫·무경합 요청·스트림·번짐·무경합 처리·20ms 지연·최저점·교락, '스레드가 아님') · '이웃' → '무경합 요청'(문서·compare.py 표 머리, 코드·k6 태그 neighbor는 유지) · '바닥/K=100 기준선' → '최저점' · ADR-003 §2.13에 단계 ⓪~⑨.4 정의(⑨를 9.1 1b 해제/9.2 COMMIT/9.3 커넥션 반납/9.4 트랜잭션 밖 해제로) · 3b가 L2 p50에서 3a보다 느린 이유(추정) §8. **사용자 결정**: ADR-003 기본 = 3b nowait(대기로 다른 좌석을 놓치는 클라이언트가 없어야 한다 — 즉시 실패형 우선, Redis는 운영·부분 실패·키 TTL 때문에 아직 안 씀), ADR-004 = 느린 작업은 판정 뒤로 → ADR 상태 Accepted, **repo README 맨 위에 결정 기록**(사용자 지정 — 상위 README의 'repo README는 원본 그대로' 규칙의 예외) | 다음: 커밋·push 확인 |

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| D1 | 1 | codex·opus | ADR-003 §7.3① 편향·§9 ADR-006·판정기 행 | 채택 — S6 판정기 '일시 중복 놓침'은 단위 혼합(좌석 vs 초과 홀드). S3만 근거로 남김 | fixed | 1 |
| D2 | 1 | codex·opus | ADR-003 §7.3① 전수 확인 | 채택 — 577→523, d20 K=100 대조군 위반 누락 | fixed | 1 |
| D3 | 1 | opus | ADR-004 §5.1·§5.2·H2, ADR-003 §7.5 | 채택 — S6 에러 수가 VU−max-connections 상한(3,616)에 걸림 | fixed(편향 명시·비교 근거 제외) | 1 |
| D4 | 1 | codex·opus | ADR-003 §7.3④·§8 표 | 채택 — 획득 대기 평균 분모 편향·nowait를 'DB 앞 거름'으로 오분류 | fixed | 1 |
| D5 | 1 | opus | ADR-003 §8 | 채택 — '3a 다음으로 빠름'(L2 p50 한정)·'지연 0에서 2=3b'(S6 한정) | fixed | 1 |
| D6 | 1 | opus | ADR-003 §8 대가 | 채택 — NOWAIT가 만료 배치·확정의 행 락에도 즉시 409 | fixed(대가 ③ + ADR-006 인계) | 1 |
| D7 | 1 | opus | ADR-003 §7.3④·§7.5 | 채택 — 풀 20 S4 한계 '같다' 오류·'redis-lock만' 범위 | fixed | 1 |
| D8 | 1 | codex·opus | ADR-004 §5.1·H4 | 채택 — K=10도 예외, H4 '방향 반대'→'효과 없음', 기제 K 의존 | fixed | 1 |
| D9 | 1 | opus | ADR-004 H3 | 채택 — 대기형 묶음 정의 충돌(1b)·'M배' 용량상 불가 | fixed | 1 |
| D10 | 1 | opus | ADR-003 H7·② | 채택 — 공정성 수치 오류, redis-nx 늦은 순위 반복 | fixed(post-fix에서 redis-nx 과장 재수정) | 1·post-fix |
| D11 | 1 | opus | 명세 '표는 스크립트 산출' | 채택 — COMPARISON이 errsplit보다 먼저 생성·전수 집계 수작업 | fixed(재생성·판정기 전수 절) | 1 |
| D12 | 1 | opus | compare.py S6 절 | 채택 — acqm4 json 누락·n 형식·빈 셀 무음 누락 | fixed | 1 |
| D13 | 1 | codex | compare.py·ADR S6 '핫 성공/s' | 채택 — 201 수라 중복 포함 | fixed(열 이름 '핫 201/s') | 1 |
| D14 | 1 | codex | ADR-004 §5.1·§5.5 | 채택 — 측정 순서 고정 편향·K=100은 무경합 대조군 아님 | fixed(K=100 기준선(분산 경합)·편향·후속) | 1 |
| N1 | post-fix | codex | ADR-004 §5.1·§5.2·H2, ADR-003 §7.5 | 채택 — 상한 아래 방식이 redis-nx뿐이라는 단정 오류 | fixed | post-fix |
| N2 | post-fix | codex | ADR-004 §5.5 | 채택 — 획득 대기 범위에 jvm·redis-lock K=1·10 누락 | fixed | post-fix |
| OQ | 1 | opus | — | OQ1 Tomcat max-connections 8,192(Spring Boot 기본·재정의 없음 — 문서 '추정') · OQ2 원시 CSV 근거는 이전 산출 · OQ3 SHA 전환 diff 0은 log 10-04 12:04 · OQ4 redis-lock d0 K10·100 이웃 p99 → §7.3③ 반영 | — | — |
| R1 | 1 | sonnet | errsplit.py·stalls.py·compare.py `c*` 글롭 — ADR-003 조건 이름 대부분 누락 | 채택 | fixed | 1 |
| R2 | 1 | opus·sonnet | 앱 2대: app2 생존·전략·풀·요청 처리 미검증(nginx 한쪽 몰림 시 1대 측정이 ok) | 채택 | fixed | 1 |
| R3 | 1 | opus·sonnet | S1 버스트를 순간 표본이 놓치고 0으로 요약 · Hikari 표본 0 쪽 편향 | 채택 | fixed(표본 대기·누적 지표·미측정 표기·편향 기록) | 1 |
| R4 | 1 | opus·sonnet | 비정상 회차 재측정 경로가 path-gap뿐 · 일시 기동 실패 1회에 캠페인 중단 · SSH 재시도 없음 | 채택 | fixed | 1 |
| R5 | 1 | opus·sonnet | redis-nx 해제 실패가 원 예외를 덮음 · NOWAIT catch가 데드락까지 409 | 채택 | fixed | 1 |
| R6 | 1 | opus·sonnet | 낙관락 왕복 2회 추가·짧은 대기인데 '대기 아니오' 분류 | 채택 | fixed(ADR 편향·분류) | 1 |
| R7 | 1 | opus·sonnet | 테스트 양성 대조 없음·Redis/unique 응답-DB 대조·404 계약 누락 | 채택 | fixed(낙관락 결정적 인터리빙 테스트는 범위 밖 — 50동시+리뷰 추론) | 1 |
| R8 | 1 | opus·sonnet | redis-nx S4 키 ~197만 vs Redis 512m | 채택 | fixed(1g + 회차별 메모리·키 기록, 스모크 확인 예정) | 1 |
| R9 | 1 | opus·sonnet | path-gap이 과부하와 단절을 못 가름 · 시계 차이 | 채택 | fixed(ADR 한계 기록) | 1 |

## 생략한 검증

- (없음)

## 완료 요약

