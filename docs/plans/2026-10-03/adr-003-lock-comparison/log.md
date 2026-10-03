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

## 리뷰 ledger (中↑)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
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

