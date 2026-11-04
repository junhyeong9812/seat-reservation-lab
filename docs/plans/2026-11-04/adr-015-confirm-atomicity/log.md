# log — ADR-015 확정 원자성

> 명세 = `requirement-spec.md`(같은 폴더). 설계 정본 = `docs/adr/ADR-015-confirm-atomicity.md`. 메인 단일 writer.
> 설계 단계(L0 — 인터뷰 · 초안 15-01a · 선검증 · 개정 15-01b · 재점검)의 타임라인은 ADR-014 작업 폴더 log(`docs/plans/2026-10-26/adr-014-hold-expiry/log.md` 11-04 13:00~17:30 행)에 있다 — 여기에는 ledger만 옮긴다.

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 11-04 23:30 | 명세 작성(requirement-spec.md — 인터뷰 답 · 설계 §6 불변식 · task 15-02~10) → 사용자 합의 · Q15 '묶는다' · Q16 '200 · 표지 안 남김'(권장안) | SPEC=1 · MODE=auto 기록(set-state) |
| 11-04 23:35 | 브랜치 `feat/adr-015-confirm-atomicity` 생성 — base = `feat/adr-014-hold-expiry` e5556bcc(PR #13 미병합 · 원격 push 안 함) · 설계 선검증 ledger DS15-1~16 이관(아래) | 다음: 15-02 재현 스모크 #1 |
| 11-04 23:40 | 워커 15-02(Opus · general-purpose — 재현 스모크 #1 · main 무변경 · 테스트 소스만) 착수 | 백그라운드 |
| 11-04 23:47 | 15-02 회수(packet 원시각 23:42:18 착수 · 23:47:24~27 테스트 · 기준 7e84a4f6): `./gradlew :main-app:test --tests …Adr015ReproSmokeTest` EXIT=0 · 4/4 green(FM1-svc · FM1-http · FM3 · EPQ) · Hibernate 6.6.26 · 메인 교차 확인: PG 서버 로그 순서 `update product_seat` → `delete from seat_hold`(sysout 265-267행) · StaleObjectStateException 3회 · **가정 1 부분 성립**(결과 = 예외 → 롤백 → 500 · 이중 결과 0 · FM3 틱 롤백 — 맞음 / flush 순서 = 좌석 UPDATE → 홀드 DELETE — ADR 추정과 반대) · **가정 2 성립**(EPQ: 연장 커밋 뒤 조건부 DELETE 0행 · 대조 held_at만 바꾸면 1행) · 부수: `PESSIMISTIC_WRITE` = `FOR NO KEY UPDATE`(#4) · L-C 래치 = Hibernate StatementInspector(main 훅 불필요) | 범위 무변경(명세 §2 — 결과가 추정과 같으므로 §문제 유지) · ADR 잠금 순서 정정(JPA 경로 = 좌석 → 홀드 · ② 대조판 짝 = A′) · 테스트 커밋 c419972f |
| 11-04 23:55 | 미완료(15-02): FM3 'X 커밋 전 배치 flush' 변형(기다림 있는 순서 — 확정 flush~커밋 사이 래치 없음) · ROLLBACK 뒤 추가 select 출처 미확인 · FM2 · #13은 15-03 범위 | 15-03 blind 테스트로 넘김 |
| 11-04 23:51 | 워커 15-03(Opus — blind 계약 테스트 · 명세 + ADR만 · main 무변경) 착수 | 백그라운드 |
| 11-05 00:20 | 15-03 회수(packet 원시각 23:51:01 착수 · 00:08:10~00:13:40 전체 실행 · 기준 e2c92e19): 테스트 146(adr015 패키지 5파일 · 래치 = DataSource 탭 · 대조 확정/배치 JDBC · 'V12 없음' 판 = AFTER_MIGRATE 콜백 DROP) · EXIT=1 · 66 red · 메인 교차 확인: test-results XML 합계 146 / failures 66 / errors 0 · **그린 위장 점검 통과**: current 'V12 없음' + 대조 4칸(FM1 · FM2 · FM3 · FM5) green = 하네스가 이중 결과 · 잃음을 실제로 만든다 · 새 모드 칸 red(모드별 FM1 · FM2 · FM3 · N8 · 교착 · API · 기동) · 새 모드 클래스의 green 7칸 = 보존 칸(에러 순서 4 · 성공 · 확정 직후 선점 · API 없음) · current FM2(V12) red = V12 부재(15-04 뒤 green 예정) | 테스트 커밋 · 모호점 12곳 메인 결정 → ADR §설계-10(T1~T12) |
| 11-05 00:22 | 워커 15-04(Opus — V12 · V13 · V14 · seat.confirm 설정 · 기동 검사 · current 유니크 위반 매핑 · 기존 테스트 정정 · blind 테스트 무수정) 착수(packet 원시각 00:22:57) | 백그라운드 |
| 11-05 01:50 | 15-04 회수(기준 d39ac39e · 원시각 00:22:57~00:25:04 15-04 몫 4클래스 1차 green · 00:27:53~01:03:46 1차 전체 125 fail(정정 전) · 01:05:33~01:11:54 정정 재실행 · 01:12:19~01:50:16 최종 전체): `./gradlew test --continue` EXIT=1 · main-app 1076 / 99 fail / 20 skip · reconciler-app 6/6 · 메인 교차 확인: test-results XML 합계 1076/99/0/20 · **adr015 밖 실패 0** · 실패 99 = 새 모드 8클래스 + PaymentStart 8(15-05/06 몫 — 원인: 새 모드 확정 거부 500 48 · 래치 미도달 43 · payment-start 404 7 · 교착 대조판 1) · 15-04 몫(StartupContract 12 · UniqueIndex 3 · CurrentRace 10 · CurrentNoV12Race 14) 39 green · blind 테스트 diff 0(git diff 확인) | 정정한 기존 테스트 10곳: IndexMigrationTest:21-22(V12 기대값) · :25-49(BeforeV12 nested · target=7) · LoadtestEndpointsTest:107-114→:176-211(BeforeV12 클래스 · target=7) · V6FlywayUpgradeTest:82-84(V5 열만 비교 + extended_at NULL) · Adr009DefaultsTest:254-256 · Adr014ImplStartupTest:173-175(최대 13) · FirstWindowPageTest:36-42 · :221-222(계단 target 7/9 고정 · 버전 목록) · Adr013FirstWindowContractTest:74-76 · Adr013InvalidIndexContractTest:29-30 |
| 11-05 01:55 | T12 원인 = Flyway `failOnMissingTarget`(V11은 seat-map 선택 위치에만) → **메인 결정 (a)**: 기준선 재현 = V12 앞 마지막 버전(기본 위치 = 7) · `failOnMissingTarget=false` 기각(무음 통과) · ADR T12 행 · 명세 · V12/기동 검사 주석 정정 · 재실행(StartupContract 12 · IndexMigration 3) EXIT=0 | T1 구현 = 테스트 클래스패스 `config/application.properties`의 `spring.profiles.include=test`(시스템 속성 방식은 Adr012DefaultsContractTest:93 전제와 충돌해 기각) — **한계**: '키 + 프로필 없음 → 거부'를 Boot 기동으로 재는 테스트 없음 → 15-05에서 `checkConfig` + MockEnvironment 단위 테스트 추가 |
| 11-05 01:58 | 15-04 코드 커밋 9ee082f4(17파일 +383/−52 · docs 미포함) | 설계와 다른 곳: 새 모드 확정 ISE 거부(15-05에서 제거) · ExpireHoldsService A′ 분기 미착수(15-05) · 규칙 3에 delay<0 거부 추가 · V14 트리거 판정 = 이름 · 미완료: CONCURRENTLY 판 V12(#5 뒤) · 15-07 runbook |
| 11-05 02:00 | 워커 15-05(Opus — ①②③ 확정 · A′ 배치 · 지표 · ④ 확정 문장 = ① · T1 MockEnvironment 단위 테스트 · 기준 18d2c7a5 · blind 무수정) 착수 | 백그라운드 |
| 11-05 02:38 | 15-05 회수(기준 18d2c7a5 · 원시각 02:01:38~02:04:03 대상 클래스 · 02:04:30~02:37:38 전체): `./gradlew test --continue` EXIT=1 · main-app 1077 / 10 fail / 20 skip · reconciler-app 6/6 · 메인 교차 확인: XML 합계 1077/10/0/20 · 실패 = PaymentStartContract 8 + Extend(NoV12) FM5 2(L-E 래치 = payment-start E1 필요 — 15-06 몫) · 새 모드 6클래스 전부 green(교착 대조판 포함) · blind diff 0 | 코드 커밋 327ccdcb |
| 11-05 02:40 | 15-05 설계 편차 · 열린 점: ③ O3 0행 = 좌석 재조회 → 409 SEAT_NOT_HELD(O2가 이미 홀드를 지워 C1d 무의미) · ② P4 ≠ 1행 = ERROR + unexpected + ISE 롤백 · `seat.hold.expiry.deleted` 태그 키 집합이 A′ `{path,extended}` vs B·C·D `{path}`(Prometheus 붙이면 충돌 가능 — 15-08 리뷰 대상) · **`seat.confirm.loadtest.delay` 미배선 = 조용한 무동작**(15-09 측정 설계에서 결정 — 그 전 기동 거부 여부도 15-08에서 판단) · 확정 ① 잠금 순서 홀드 → 좌석 vs A′ 배치 순서 교착 여부 = 15-08 리뷰 렌즈 | 15-06 착수 |
| 11-05 02:40 | 워커 15-06(Opus — ④ payment-start · 기준 1d559529) 착수 | 백그라운드 |
| 11-05 03:15 | 15-06 회수(원시각 02:40:58~02:41:56 대상 3클래스 EXIT=0 · 02:42:05~03:14:43 전체 EXIT=0): 메인 교차 확인 XML 합계 main-app **1077 / 0 fail / 0 err / 20 skip** · reconciler-app 6/6 · 새 파일 2개만(PaymentStartService · PaymentStartController — 기존 파일 diff 0) · blind 무수정 | 코드 커밋 ee84cc5c · 편차: 진단 404/403/만료 409는 `seat.confirm.extend` 태그 없음(ADR 값 4개만 — 15-08에서 판단) · 등록 조건 = Binder로 enum 바인딩(ConditionalOnProperty 문자열 비교의 무음 누락 방지) · UseCase 인터페이스 없음 |
| 11-05 03:17 | 워커 15-07(Opus — apply.sh outOfOrder 1회 · test-local.sh ⓐ~ⓓ · 앱 쪽 계약 테스트 · ops/confirm-unique-v12 runbook R0/R1/R2 · 기준 ee84cc5c) 착수 | 백그라운드 |
| 11-05 04:08 | 15-07 회수(기준 ee84cc5c · 원시각 03:17:09 기준 test-local EXIT=1 FAIL 3 — 15-04의 V12 · V13 기본 위치가 기존 경우를 깸(ref 버전 목록 · seed_old target 없음) · 03:35:50~03:37:30 최종 test-local EXIT=0 ok 41 · 0 스킵 · jar 숨김 대체 경로 EXIT=3 SKIP 출력(무음 스킵 아님) · 03:35:29~04:07:13 전체 gradle EXIT=0): 메인 교차 확인 XML 합계 main-app **1079 / 0 / 0 / 20**(+2 Adr015OutOfOrderContractTest) · 컨테이너 seatlab* 잔여 0 · 비밀번호는 `$PW` 변수 + apply.sh 출력 args 가림(값 미출력 확인) | 코드 커밋 77e272d7 · [구현 검증] #6 실측 답 ADR 반영 · 편차: R1에 입력 확인 ⓟ · 좌석 확인 ④ · 미리보기(ROLLBACK) 기본 · R2 마감 기준 DB now()(판정기는 앱 Clock — #12) · `next.app_first_boot_out_of_order` 출력 추가 · cli 판 = 같은 Flyway 11.7.2 라이브러리 실행기(공식 CLI 아님 — 한계) · 기존 test-local.sh SQL 대체 경로 V6 LOCK TABLE 버그 수정(`psql -1`) · 미완료: CONCURRENTLY 재시도 실행(V12 CONCURRENTLY 판 없음 — #5) |
| 11-05 04:10 | 15-02~15-07 구현 완료 — 15-08 듀얼 리뷰 루프 1 착수(base e5556bcc = 브랜치 분기점) | — |
| 11-05 04:20 | 15-08 루프 1: review packet 생성(base e5556bcc · 39파일 +3511/−99 · untracked 0 · ledger/NEXT/measurement-log · k6 results · .claude 제외 · 미러 내 log.md 0 확인) · 보안 스캔: sk-/ghp_/AKIA/PRIVATE KEY 0 · `password=` 류 매칭 = 추적 파일의 로컬 일회용 컨테이너 테스트 값(compose.yml · BootSupport 등 — repo PUBLIC으로 이미 공개된 내용) · 내부 IP 매칭 = 기존 문서/스크립트(공개 repo) → 오탐/기공개로 판정 · 양쪽 같은 입력 · Opus 워커 ∥ codex(medium · read-only · cwd=미러) 병렬 착수 | 백그라운드 |
| 11-05 04:45 | 루프 1 회수: codex(EXIT 0 · F1~F4) · Opus(F1~F5 + OQ 5) → 메인 종합(채택 8 · 범위 밖 1 · OQ 4) → codex 종합 감사(EXIT 0 · 재검토 3: OQ-2 판정 성급 — V12 이력 있고 인덱스 없음 + V13 미적용이면 관문 통과 뒤 거부 = 스키마 변경 → R15-2로 확장 채택 · 같은 이름 비유니크 인덱스 → R15-10 채택 · 지연 최대값 수집 불가(ADR:142) → R15-9 채택) · 결과 사본 `k6/ADR-014/results/_evidence-20261104/reviews/rv15-impl-loop1/`(codex 원문 · 감사 · 종합표 · Opus 원문은 워커 회수 본문 — 아래 ledger) | 수정 워커 착수 |
| 11-05 05:20 | 루프 1 수정 회수(기준 b07f1c1f · 원시각 04:28~05:15:17): 새 테스트 adr015fix 6/0 · test-local EXIT 0(ok 45) · 전체 `./gradlew test --continue` EXIT 0 · 메인 교차 확인 XML **1085/0/0/20** · blind · V1~V14 · k6 results diff 0 · 컨테이너 잔여 0 | 코드 커밋 b9ee9c65(24파일) · R15-1~3 · 5~10 fixed · 계획 밖 파일: main-app/build.gradle.kts(postgresql implementation — R15-8) · ADR-013 하네스는 target 대신 V12 SHA 거부(대안 — 슬롯별 target NEXT) · ADR §설계-11 신설(구현 정정 반영) · 다음: 루프 2(누적 diff 재리뷰) |

## 리뷰 ledger (中↑ — 듀얼 리뷰가 돈 작업 필수)

- review packet(설계 선검증 · 루프 1): base e862322d^ · `$OUT` = scratchpad/ds15res.H8ul(정리됨 — codex 결과 사본 `k6/ADR-014/results/_evidence-20261104/reviews/ds15-design/` · Opus 결과는 워커 회수 본문 — ADR-014 log 11-04 16:40 행 요약) · mirror scratchpad/ds15mir.Op8V(삭제됨)
- review packet(수정분 재점검 · 루프 1 post-fix): base 61226b03 · 결과 사본 같은 폴더 `codex-postfix.md`
- **review packet(15-08 구현 리뷰 · 루프 1)**: base e5556bcc · `$OUT` = scratchpad/rv15.O1tc · mirror = scratchpad/rv15mir.iE2M · 생성 스크립트 scratchpad/mkpkt.sh

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| DS15-1 | 1 | codex H1 · Opus M2 | ADR-015 §3.4 연장식(초안) — 재호출이 다시 연장 | 채택 → 사용자 결정 '첫 연장만' · V13 표지 열 · E1 `extended_at IS NULL` | fixed | 1(61226b03) |
| DS15-2 | 1 | codex H2 | ExpireHoldsService.kt:38 · ProductSeat.kt:76 — JPA 배치가 낡은 후보로 연장된 홀드 삭제 | 채택 → A′ 배치 B3 조건부 DELETE(FM5 · N10) | fixed | 1 |
| DS15-3 | 1 | codex H3 · Opus L3 | §설계-5(초안) V12 부재 WARN | 채택 → 규칙 7 · N11 기동 거부 | fixed | 1 |
| DS15-4 | 1 | codex M | ConfirmReservationService.kt:49 IDENTITY INSERT — FM2 `current` 기대가 V12 유무로 갈림 | 채택 → FM2 칸 두 판 · `[구현 검증]` #13 | fixed | 1 |
| DS15-5 | 1 | Opus | FM3 · N5가 배치의 우연한 가드(0행 → 틱 롤백)에 기댐 | 채택 → 사용자 재합의 'A 배치 최소 수정 범위에' · §3.6 A′ | fixed | 1 |
| DS15-6 | 1 | Opus H1 | §설계-8.1(초안) — 대조군 없이는 그린 위장 | 채택 → 대조 확정 · 대조 배치 · '대조 0이면 테스트 실패' | fixed | 1 |
| DS15-7 | 1 | Opus M1 | FM3 래치 자리(초안) | 채택 → L-B(JPA — `afterExpiryDecrement` 조건) · L-B′ · L-B(대조) | fixed | 1 |
| DS15-8 | 1 | Opus M3 | 복구 R1 `cnt − n` 차감 | 채택 → V6 정의로 다시 세기 | fixed | 1 |
| DS15-9 | 1 | Opus M4 | V12 기본 위치 × 옛 방식 DB · ADR-013 runbook Flyway 순서 | 채택 → 사용자 결정 'outOfOrder 1회' · apply.sh · test-local.sh ⓐ~ⓓ | fixed | 1 |
| DS15-10 | 1 | Opus L1 | N6 유니크 위반 매핑 범위 | 채택 → A 확정만(`current` 포함) · B·C·D 500(받아들인 한계) | fixed | 1 |
| DS15-11 | 1 | Opus L2 | V13(당시 트리거) 기동 거부 시점이 migrate 뒤 | 채택 → 트리거 V14 이동 · 짝 검사 migrate 앞(규칙 6) | fixed | 1 |
| DS15-12 | 1 | Opus L4 | `CONCURRENTLY` 실패 뒤 재시도 | 채택 → `flyway repair` 절차 | fixed | 1 |
| DS15-13 | 1 | Opus OQ1 | 확정 만료 판정 시각 의미 | 사용자 결정 '요청 시작(:now) 유지 + converted_after_expiry 따로 셈' | fixed | 1 |
| DS15-14 | 1 | Opus(L 그 밖) | 동기화류(번호 · 줄 번호 · 용어) | 채택 → 15-01b 반영 | fixed | 1 |
| DS15-15 | 1(post-fix) | codex H | ADR-015:312 · :394 · :461 — 'V12 없음' 판을 `target=11`로 두면 V13 열 없음 → A′ B3 실패 | 채택 → 'V12 없음' 판 = 전 마이그레이션 뒤 인덱스만 DROP + 테스트 키 | fixed | 1(637a8bae) |
| DS15-16 | 1(post-fix) | codex M | ADR-015:393 · :455 — ③ 'V12 없음' 칸이 V14 기동 조건과 충돌 | 채택 → DS15-15와 같은 정정 | fixed | 1(637a8bae) |

구현 리뷰(15-08 · base e5556bcc):

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| R15-1 | 1 | codex F1 · Opus F1 | SeatConfirmProperties.kt:28 · HoldStrategyStartupCheck.kt:275-279 — loadtest.delay 무동작(무음) | 채택 → ADR §설계-8.2 자리에 주입 | fixed | 1(b9ee9c65) |
| R15-2 | 1 | codex F2 · 감사 1 | HoldStrategyStartupCheck.kt:59-70 · :130-149 — V12 INVALID/없음 검사가 migrate 뒤 → 거부된 기동이 V13을 적용할 수 있음 | 채택 → 관문(migrate 앞)에서 V12 이력 있음 + 인덱스 없음/INVALID 판정 | fixed | 1(b9ee9c65) |
| R15-3 | 1 | codex F3 · Opus F4 | AtomicConfirmProcess.kt:45 — converted_after_expiry{clock=db} 커밋 전 증가 | 채택 → afterCommit | fixed | 1(b9ee9c65) |
| R15-4 | 1 | codex F4 | k6/ADR-015 하네스 · 결과 미측정 | 범위 밖(15-09 · 15-10) | — | |
| R15-5 | 1 | Opus F2 | k6/ADR-001/compose.yml:44 · k6/ADR-002/compose.yml:44 · run.sh:82 — 기존 하네스가 V12 · V13 적용 → 기준선 가려짐 | 채택 → ADR-015 이전 하네스 target 명시 | fixed | 1(b9ee9c65) |
| R15-6 | 1 | Opus F3 | ops/online-v9/apply.sh:122 · :238-240 · :236 — outOfOrder가 9 이하 다른 미적용도 무실행 기록 | 채택 → 이력 차이 = {9} 확인 | fixed | 1(b9ee9c65) |
| R15-7 | 1 | Opus F5 | Adr015RaceScenarios.kt FM5 — B3 잠금 대기 뒤 EPQ 갈래 미검사 | 채택(fix verification test) | fixed | 1(b9ee9c65) |
| R15-8 | 1 | Opus OQ4 | ConfirmReservationService.kt:112- — message.contains 판정 | 채택(L) → constraint 이름 일치 | fixed | 1(b9ee9c65) |
| R15-9 | 1 | 감사 3 | AtomicConfirmProcess.kt:45 · :63-69 · ADR-015:142 — `전환 시각 − expires_at` 최대 수집 불가 | 채택 → 지연량 분포 기록 | fixed | 1(b9ee9c65) |
| R15-10 | 1 | 감사 2 | HoldStrategyStartupCheck.kt:132-149 · V12:6 — 이름 · indisvalid만 봄(같은 이름 비유니크 인덱스 통과) | 채택(L) → indisunique · 대상 표 · 부분 조건 확인 | fixed | 1(b9ee9c65) |
| OQ15-1 | 1 | Opus OQ1 | 여러 대 혼재 중 모드 혼합 | 받아들인 한계 → ADR에 '전 인스턴스 정지 후 전환' 명시(메인 문서) | fixed | 1(2330b3bd) |
| OQ15-2 | 1 | Opus OQ2 | checkConfirmSchema가 웹 서버 열린 뒤 | 받아들인 한계(기존 패턴) — 감사 1의 스키마 변경 갈래는 R15-2로 | — | |
| OQ15-3 | 1 | Opus OQ3 | 운영 test 프로필로 T1 우회 | 받아들인 한계(운영자 설정) | — | |
| OQ15-4 | 1 | Opus OQ5 | seat.hold.expiry.deleted 태그 키 집합 차이 | open question → NEXT | user-deferred 후보 | |
- 주: Opus 설계 선검증 원문은 파일로 남지 않았다(워커 회수 본문) — 위 id 대응은 ADR-014 log 11-04 16:40 행의 요약에서 옮긴 것.

## 생략한 검증

- (없음 — DEBT=0)

## 완료 요약 (마감 시)

- (미작성)
