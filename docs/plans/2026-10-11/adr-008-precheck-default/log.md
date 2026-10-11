# log — ADR-008 counter-upsert-precheck 기본 적용·검증

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-11 18:13 | 인터뷰(전환 범위·백필·테스트·순서·번호·확정 측정) → 명세 합의 "합의, auto로 진행" → SPEC=1 · MODE=auto · 기준 HEAD fb181a30 | 게이트 통과 |
| 10-11 18:1x | task 01 설계 선검증 워커(Opus) 위임 — V6 SQL·불변식 증명·기본값 위치·영향 전수(하네스·테스트·기동 검사)·ADR-008 하네스 계획 · task 03 blind 테스트 워커(Opus) 병렬 위임 — 명세만, 구현 diff 미열람 | 위임 |
| 10-11 18:18 | blind 테스트 워커 회수: 3파일 4클래스 15테스트(PrecheckDefaultTest — 기본값 3경로·동시 10요청 5라운드 / V6BackfillMigrationTest — 빈·혼합·덮어쓰기·실제 0 행·상한 초과·멱등 / DefaultLimitStartupCheckTest — 미지정+비3b 기동 실패·none 명시 기동·기본 기동) · HEAD fb181a30(구현 없음)에서 13 실패(전부 예상 원인: 기본값 NONE·V6 없음 — none에서 동시 10요청 ok=10으로 공허하지 않음 확인) · 2 통과 · 모호성 4(실제 0 행은 cnt 0/삭제 둘 다 허용 · 기동 메시지 결합 등) | 테스트 선작성 |
| 10-11 18:26 | 설계 워커 회수(scratchpad/adr008/design.md 279줄 · V6.sql): V6 = ① 실제 ≥1 쌍 upsert 덮어쓰기(같으면 안 씀) ② 실제 0인 기존 행 cnt=0(삭제 안 함 — ensureQuotaRow가 트랜잭션 밖에서 만든 행을 안에서 쓰는 방식 보호) · 임시 postgres:16에서 불일치 4→0, 재실행 xmin 불변 · LOCK TABLE 없음(Flyway 트랜잭션 락 기본 true, 11.7.2 jar 확인) · 영향: ADR-001·002·003 compose에 limit-strategy none 고정 필요(미고정 시 ADR-002 FLYWAY_TARGET=1은 쿼터 테이블 없어 500 · ADR-003 비3b 기동 실패) · 테스트 클래스별 none 고정 6(HoldStrategyTest·HoldSeatServiceTest·LoadtestEndpointsTest·UniqueHoldStrategyTest·RedisHoldStrategyTest·CriticalSectionDelayTest — 기반 클래스엔 안 둠: 기본값 테스트가 기반 컨텍스트 사용) · 주석 번호 2곳 · ADR-008 하네스 = ADR-007 복사 + compose에서 키 삭제 + docker inspect env 확인 + 옛 기본값 앱 대조(음성 대조) · 위험 R1(카운터 어긋남 → 만료 배치 CHECK 실패로 매 틱 전체 롤백) 등 | 설계 — codex 교차 검증 시작 |
| 10-11 18:4x | 설계 codex 교차 검증: V6 집계 = 판정기 정의 일치·멱등·빈 DB 무동작·blind 테스트와 충돌 없음 · 지적 5(P1~P5 채택) · sum()::int 범위 가정 명시 · 사용자 결정: R1 카운터 어긋남 복구는 ADR-009 | 구현 위임(설계 워커 재개 — 블라인드 테스트 워커와 분리) |
| 10-11 22:16 | 구현 워커 회수: 38df2e8e(기본값 2곳 · V6 LOCK TABLE + 전환 조건 주석 · 테스트 6클래스 none 고정 · 주석 번호 009·010 · decrementCounters 위험 주석 1줄) · ca840fb5(blind 테스트 3파일 — V6 재실행만 트랜잭션 래퍼: LOCK TABLE이 자동 커밋에서 실패) · 5ea0eccd(ADR-001~003 compose none 고정) · e152bcb8(k6/ADR-008 하네스 25파일 — 키 생략·limit-key.json 개수만·회차 합격 사유 9종·13회 캠페인·ADR-007 대비 비교) · 전체 테스트 190/0/0(blind 15 포함, 첫 실행 6 실패 = LOCK) · compose 렌더 전후 limit-strategy 동일(ADR-001~003은 미지정→none 명시, 실효 같음) · dry-run 13회 · rep_status 46/46 · 빌림 허용 [0.98, 1.02](S3 제외 — 워커 결정, 메인 채택: ADR-007 precheck 실측 1.000~1.0025) | P5는 문서(07)에서 |
| 10-11 22:2x | 리뷰 루프 1 시작(codex ∥ Opus, 미러 e152bcb8 · 패킷 = 앱 diff + ADR-008 대 ADR-007 diff) | 진행 |
| 10-11 22:4x | 리뷰 루프 1 회수: codex P1×2·P2×3 · Opus M1·L1~L4·info 3 (lens 3·4 무지적) → C1~C10 채택 · C11 편차 기록 · C12 증거 메인 대조 · info: V6 잠금 순서로 옛 쓰기 주체와 교착 가능(전환 조건 위반 시만 — ADR 기록) · 쿼터 테이블 없는 컨텍스트 500(ADR-009 기록) | 수정 위임(구현 워커) |
| 10-11 22:41 | 루프 1 수정 회수: 33559e0a(V6FlywayUpgradeTest — Flyway API로 V5 → 섞인 데이터 → 최신, V6 SUCCESS·불일치 4→0·홀드·예약 행 그대로 · HoldStrategyTest KDoc) · 52dbd003(하네스 C1~C4·C6~C9) · S3 빌림: ADR-007 precheck S3-a20 1.012901·1.012935·1.012943 → 같은 띠 [0.98, 1.02] · 전체 테스트 191/0/0 · rep_status 85/85 · limit_config 10/10(fb181a30 → limit-default-not-precheck) · limit_key 8/8 · compare 13/13 · dry-run 13 · 주의: 러너 도중 run.sh 주석만 sed 수정 | 루프 2 시작(codex ∥ Opus, 미러 52dbd003) |
| 10-11 23:1x | 루프 2 회수: codex·Opus 모두 C1~C10 fixed 확인(Opus는 52dbd003 전체 테스트 191/0 재실행·ADR-007 기준 실데이터 픽스처) · 새 지적 D1~D5 채택 · D6 기각 · 무회귀 | 수정 위임 → 루프 3(재점검) 예정 |
| 10-11 23:14 | 루프 2 수정 회수 84fab7c6(k6/ADR-008만): D1 요청당 빌림(서버 confirm_requests — S3 1.000116~1.000117, 다른 셀 불변, borrow_per_hold 유지) · D2 cfgkeys.py(PyYAML — import·location·profiles·group, HEAD 0건) · D3 over_limit_missing · D4 캠페인 모드 정적 검사 실패면 배포 전 exit 6·캠페인 중단 · D5 README · rep_status 94/94 · limit_config 15/15 · D4 10/10 · compare 17/17 · dry-run 13 · 앱·테스트 코드 무변경이라 gradle 미재실행(52dbd003 191/0) | 루프 3(codex 재점검) 시작 |
| 10-11 23:3x | 루프 3 재점검(codex): D1·D3·D4·D5 fixed · D2 partial → E1 채택(메인 수정 0e83867e) · E2 기각(보수적 방향) · 루프 상한 3 도달 — 남은 신규는 지금 파일에 없는 가상 설정 입력뿐(실제 HEAD 설정 0건) → **코드 리뷰 종료** | task 05 서버 스모크로 |
| 10-11 23:24 | 서버 스모크(구현 워커): 0e83867e 기본값 기동 S2·S7-m1 ok — configprops COUNTER_UPSERT_PRECHECK · limit-key 0 · limit-config [] · 매수 초과 0 · 불일치 0 · 억울한 좌석 0 · 빌림/요청 1.001·1.005 · 서버 flyway_schema_history V6 success(읽기 전용 조회) → **가정 2 성립** · 음성 대조 fb181a30 --worktree S2: limit-strategy-mismatch·limit-default-not-precheck·over-limit(v_over_limit_users 18)… 비ok, limit-key-present 없음 · 서버 정리 확인(seatlab 컨테이너·볼륨 0) | 스모크 통과 |
| 10-11 23:2x | **확정 측정 시작**: systemd-run --user --unit seatlab-adr008 → campaign.sh --sha 0e83867e --id 20261011-adr008-0e83867e(13회) | 예상 약 2.5시간 |
| 10-12 00:52 | 확정 측정 13회 끝(`20261011-adr008-0e83867e`) | 13/13 `ok` · CAMPAIGN "비정상 회차 0" |
| 10-12 00:5x | summarize ×5 · `compare.py` vs ADR-007 precheck-default | exit 0 · 문제 0건 · 크기 다름(0.5~2배 밖) 0건. 매수 초과 0 · 카운터 불일치 0 · 억울한 좌석 [0,0,0] · 순서 위반 [0,0,0]/100 · 요청당 빌림 1.000~1.0025 · 적용 방식 COUNTER_UPSERT_PRECHECK(limit_source=default) 전 셀 |
| 10-12 01:0x | S2 선점 p99 원인 확인 | 1.75배(중앙 434 vs 248ms; 회차 212·434·448 vs 기준 279·248·218). 1회차(첫 순서)만 기준과 같다. 측정 창 호스트 nvme1n1은 거의 쉼(flush 6~7회/s·0.3~0.4MB/s, flush 평균 0.59·0.91·0.63ms) → 디스크 원인 아님. 원인 미확인 — 짧은 버스트 셀의 꼬리 분산으로 기록(판정 항목 아님, 0.5~2배 안) |
| 10-12 01:0x | **task 06 판정: 명세 §5 확정 측정 합격** | 기능·정합 항목 전부 ADR-007 precheck와 같음 · S3-a20 WAL 1.00배 · S4 엄격 한계 4,297 RPS(1.03배) |
| 10-12 01:2x | task 07: WDROP(S4 호스트 반감 379초·3.76GB·flush 1,778→630 = 벽 12단계 같은 초) · ADR-008 문서 작성 · ADR-007 §8·NEXT N1 백필 표현 정정(seat_hold 행 전체) · 문서 듀얼 1패스 시작(packet·미러 scratchpad/rv14, 보안 스캔 0건, codex ∥ Opus) | 진행 |
| 10-12 01:4x | 문서 듀얼 1패스 회수: codex F1(R1 과소 카운터 경로) · Opus F1~F6(+참고 1) → 채택 전부(G1~G7) · 수정 → codex 타깃 재점검 "신규 finding 0" | **문서 리뷰 종료** |
| 10-12 01:5x | 측정로그 1행 · NEXT 갱신(N1 완료 → 완료 이력, ADR-009 최우선) · docs 커밋 · 측정 결과 커밋 83125e68 | 사이클 마감 ① |
| 10-12 02:0x | 사용자 결정: 아카이브 4카드(LOCK TABLE 자동 커밋 · 카운터 어긋남 세 갈래 · .properties 공백 구분자 · 기본값 증명 + 음성 대조) · push + main 대상 PR | 아카이브 위임(Opus) · push 진행. 노출 스캔 원 식별자: seat-reservation-lab · backend-labs-commerce · seatlab · user_hold_quota · seat_hold · product_seat · LoadtestDataService · UserLimitStrategies · decrementCounters · ExpireHoldsService · HoldStrategyStartupCheck · SeatHoldProperties · V6__backfill_user_hold_quota · cfgkeys.py · limit-key.json · limit-config.json · COUNTER_UPSERT_PRECHECK · counter-upsert-precheck · 192.168.55.164 · .164 · nvme1n1 · jun |
| 10-12 02:1x | 사용자 정정: "우선 의사결정으로 none이 아닌 advisory-try를 하려고 기준을 잡되 제대로 확인하기 전까지 연기해놓은 것" → ADR-008 §1·ADR-007 §8 문구 정정('반영된 적이 없다'(누락처럼 읽힘) → '확인 전까지 적용을 미뤄 둠'). 명세 §0의 같은 문구는 합의 원문이라 두고 이 행으로 정정 | 문서 정정, PR 전 반영 |
| 10-12 02:3x | push(feat/adr-007-throughput-wall, 사용자 승인) · 사용자 지적 "S2 이렇게만 적지 말고 () 안에 뜻을" → ADR-008 문서·PR 본문 약어 전부 뜻 병기(fc37c6fe) · PR #7 생성(main 대상, 사용자 승인 '이대로 생성') | https://github.com/junhyeong9812/seat-reservation-lab/pull/7 |
| 10-12 06:3x | 아카이브 워커(Opus) 회수 — 노출 스캔 추가 행 0건(오탐: 기존 행 token= 추상 코드·루프백 설명·저장소 자체 프로젝트 목록 경로 — 이번 추가 아님) · 메인 교차 확인(d49ab8ec diff 원 식별자 grep 0 · worktree clean) | 3카드 3커밋 |
| 10-12 06:3x | 아카이브: issue/cross-cutting/testing/verification-environment-parity ⓒ 357d2378 · issue/cross-cutting/data/denormalized-counter-drift ⓐ d49ab8ec · issue/cross-cutting/testing/green-masking ⓒ 7f7659a3 · .properties 공백 구분자 → 기존 카드 issue/cross-cutting/security/verify-what-you-use/ 동일 — 무변경 | archive/2026-10-11 = origin/main보다 10커밋 앞·0 뒤(ff 가능 — archive/2026-10-09는 이미 origin/main과 같음). push는 사용자 확인 |

## 리뷰 ledger

- review packet: 설계 선검증 rv13 — HEAD fb181a30 미러 + design.md·V6.sql·blind 테스트(scratchpad/rv13)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| E1 | 3 | codex | cfgkeys.py:44 | 채택 — .properties 공백 구분자(java.util.Properties) | fixed | 0e83867e(메인 수정 — 6가지 입력 확인·limitconfig 15/15) |
| E2 | 3 | codex | cfgkeys.py:48 | 기각 — 쓰지 않는 프로필 문서의 import도 세는 것은 안전 쪽(보수적 중단). 지금 파일엔 0건, 생기면 사람이 판단 | rejected | — |
| D1 | 2 | codex | summarize.py:581-588,659-666 | 채택 — 빌림을 선점당이 아니라 요청당(S3 확정 포함) | fixed | 84fab7c6 |
| D2 | 2 | codex | lib.sh:363-375 | 채택 — spring.config.import·location·profiles 설정도 정적 검사 | fixed | 84fab7c6·0e83867e(루프 3 partial → E1) |
| D3 | 2 | opus L1 | compare.py:76 | 채택 — v_over_limit_users 없는 회차 개수 표시·문제 | fixed | 84fab7c6 |
| D4 | 2 | opus L2 | run.sh:837 · campaign.sh:135 | 채택 — 캠페인에서 정적 검사 실패·사유면 배포 전 전체 중단 | fixed | 84fab7c6 |
| D5 | 2 | opus note | k6/ADR-008/README.md | 채택 — 음성 대조 사유 2개 명시 | fixed | 84fab7c6 |
| D6 | 2 | codex | lib.sh:341-344 | 기각 — SPRING_APPLICATION_JSON 유니코드 이스케이프 키는 위협 범위 밖(ADR-008 compose는 우리 것, 키 없음) | rejected | — |
| C1 | 1 | codex | summarize.py:643 · compare.py:110 | 채택 — S3도 빌림 검사(ADR-007 S3 실측으로 띠 결정) | fixed | 52dbd003 |
| C2 | 1 | codex | campaign.sh:130 · compare.py:44 | 채택 — S4 s4-no-successful-stage는 ADR-008에서 ok 아님 | fixed | 52dbd003 |
| C3 | 1 | codex + opus M1 | compare.py:138-156 | 채택 — 기준(ADR-007) 없음·부족이면 실패 | fixed | 52dbd003 |
| C4 | 1 | codex | lib.sh:333 · run.sh:635 | 채택 — 프로필·외부 설정이 limit-strategy를 덮으면 사유 | fixed | 52dbd003 |
| C5 | 1 | codex + opus(작은 틈) | V6BackfillMigrationTest.kt:21 | 채택 — Flyway target 5 → 데이터 → migrate 실제 업그레이드 테스트 | fixed | 33559e0a |
| C6 | 1 | opus L1 | summarize.py:597-653 · campaign.sh ACCEPT_RE | 채택 — 기준별 try·찾은 사유 보존·위반 있는 회차 자동 재측정 금지 | fixed | 52dbd003 |
| C7 | 1 | opus L2 | summarize.py:621-638 | 채택 — 입력 없으면 s2/s7-check-failed | fixed | 52dbd003 |
| C8 | 1 | opus L3 | lib.sh limit_key_check | 채택 — 모든 env·Cmd·Args 대소문자 무시 개수 | fixed | 52dbd003 |
| C9 | 1 | opus L4 | compare.py:62,152 | 채택 — 비율 0.5~2배 병기(게이트 아님) | fixed | 52dbd003 |
| C10 | 1 | opus info | HoldStrategyTest KDoc | 채택 — 낡은 '기본 none' 문구 | fixed | 33559e0a |
| C11 | 1 | opus info | UserLimitStrategies.kt:222 | 기록 — 명세 §4 '주석 번호 정정 외' 밖 주석 1줄(P6 이월 표시) — 유지, 편차로 기록 | no_change | — |
| C12 | 1 | opus lens5 | 명세 §5 하네스 무영향 증거 | 기록 — scratchpad/adr008/render-before·after.txt 메인 대조: ADR-001·002·003 미지정(옛 코드 기본 none) → none 명시, 005~007 동일, 주입 조합 동일, ADR-008 키 없음 | verified | — |
| P1 | 0(선검증) | codex | design.md:217,259 · ADR-007 run.sh:713 | 채택 — ADR-008 하네스에 wdrop.py 포함 | fixed | e152bcb8 |
| P2 | 0 | codex | V6.sql:9-25 | 채택 — V6 안 LOCK TABLE(SHARE ROW EXCLUSIVE) + 전환 조건(옛 쓰기 주체 정지·직후 불일치 0) | fixed | 38df2e8e·ca840fb5 |
| P3 | 0 | codex | run.sh:565-723 · summarize.py:937 | 채택 — 회차 합격 = 매수 초과 0·불일치 0·억울한 좌석 0·순서 위반 0·빌림 1.0 | fixed | e152bcb8 |
| P4 | 0 | codex | design.md:232 · compose.yml:62 | 채택 — 환경 전체 기록 금지(DB_PASSWORD), 키 유무만 | fixed | e152bcb8 |
| P5 | 0 | codex | LoadtestDataService.kt:163-179 | 채택 — 부하시험 정합 응답에 v_counter_mismatch 추가됨을 ADR에 기록 | fixed | ADR-008 §6② |
| P6 | 0 | 설계 R1 | ExpireHoldsService.kt:20-28 · UserLimitStrategies.kt:214-223 | 사용자 결정 — ADR-009로 이월(ADR-008엔 위험·이월 기록) | deferred | — |

- review packet(문서): rv14 — 미러(0e83867e tracked + ADR-008 문서·결과 요약 파일) · packet = ADR 두 문서 diff + spec · 보안 스캔 0건

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| G1 | doc | codex F1 + opus F1 | ADR-008 §6① · UserLimitStrategies.kt:214-223 · V5:6 | 채택 — R1을 행 없음(check)·음수(CHECK)·조용한 어긋남(과소 → 매수 초과 허용, 과대 → 조용한 거절) 세 갈래로 | fixed | doc |
| G2 | doc | opus F2 | ExpireHoldsService.kt:20-26 | 채택 — 실패는 한 틱이 아니라 수동 복구까지 매 틱(전역 만료 정지) | fixed | doc |
| G3 | doc | opus F3 | comparison.json S2 · ADR-007 order.txt | 채택 — 212 < 218(범위 밖 아래) · 순서 근거 제거(기준도 S4 뒤) · 호스트 수치 출처 명시 | fixed | doc |
| G4 | doc | opus F4 | WDROP.md:11 · ADR-007 §7.2 표 | 채택 — 창 정의 통일(flush/s 1,536→376) · ADR-007 범위 병기 · '같은 서명' → '같은 현상, 수치 근접' · docker 386초 구분 | fixed | doc |
| G5 | doc | opus F5 | V6FlywayUpgradeTest.kt:17 | 채택 — blind 15 vs 리뷰 C5 1 구분 | fixed | doc |
| G6 | doc | opus F6 | ADR-007 §8 | 채택 — '미적용' 문장에 결정 당시 표시 | fixed | doc |
| G7 | doc | opus 참고 | COMPARISON.md '같은 자릿수(참고)' | 채택 — 명세 '자릿수'를 비율 규칙으로 운영, 문자 그대로면 S7-m1만 다름 1줄 | fixed | doc |

## 생략한 검증

- 없음

## 완료 요약

- 결과: 매수 제어 기본값 none → counter-upsert-precheck(설정·코드) · V6 카운터 백필 · ADR-001~003 하네스·테스트 6클래스 none 고정 · k6/ADR-008 하네스 · 확정 측정 13/13 ok → ADR-008 결정됨(2026-10-12).
- 핵심 diff (fb181a30 → 0e83867e, 실파일):

```diff
-    /** 같은 사용자 동시 요청의 매수 제어(ADR-005). 기본 none = 조회 후 비교(동시 요청은 못 막음). */
-    val limitStrategy: UserLimitStrategyType = UserLimitStrategyType.NONE,
+    /** 같은 사용자 동시 요청의 매수 제어(ADR-005). 기본 = ADR-008 결정 counter-upsert-precheck(none = 조회 후 비교 — 동시 요청은 못 막음). */
+    val limitStrategy: UserLimitStrategyType = UserLimitStrategyType.COUNTER_UPSERT_PRECHECK,
-    # 같은 사용자 동시 요청의 매수 제어(ADR-005). 기본 none = 조회 후 비교
-    limit-strategy: none
+    # 같은 사용자 동시 요청의 매수 제어(ADR-005). 기본 = ADR-008 결정 counter-upsert-precheck(none = 조회 후 비교, ADR-000 기준선)
+    limit-strategy: counter-upsert-precheck
```

- V6: `src/main/resources/db/migration/V6__backfill_user_hold_quota.sql`(신규 31줄 — LOCK TABLE → 실제 ≥1 upsert 덮어쓰기 → 실제 0 행 cnt=0).
- 남은 것: push·PR(사용자 확인) · 이슈 아카이브 범위(사용자 확인) · R1·fail-fast·쿼터 행 정리 → ADR-009.
