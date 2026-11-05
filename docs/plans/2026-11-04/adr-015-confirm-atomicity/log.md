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
| 11-05 05:30 | 루프 2 착수: packet 재생성(base e5556bcc · 58파일 +4049/−117 · loop-fix-diff = b07f1c1f 대비 · 미러 log.md 0) · 보안 스캔 키류 0 · 수정분의 `password` 류 1건 = 하네스 로컬 테스트 값(공개 repo) · codex ∥ Opus 병렬 | `$OUT` = scratchpad/rv15b.reEL · mirror = scratchpad/rv15bmir.4e0r · 루프 1 미러 삭제 |
| 11-05 05:55 | 루프 2 회수: codex(EXIT 0 · F1 V13 열 관문 미검사 · F2 측정 없음) · Opus(F1 정리 ① 켠 교착 짝 미실행 · F2 ADR-013 §4.4 표 미반영 · F3 bootTestRun test 프로필 + OQ 4) → 메인 종합(신규 채택 3 · 범위 밖 1 · OQ 5) → codex 감사(EXIT 0 · OQ15-9 '받아들인 한계' 종결 불가 — history-diff 실패 뒤 남은 행의 확인 · 재개 절차 없음 → R15-15 채택) · 사본 `…/reviews/rv15-impl-loop2/` | **신규 채택 4 → 종료 조건 미충족** · 수정 워커(R15-11 · 12 · 15) · 문서(R15-13 · OQ15-5) 메인 |
| 11-05 06:10 | 사용자 질문 'ADR문서를 왜 바꾸지?' → 설명(ADR-015 = 이번 작업의 열린 정본 · ADR-013 = PR #12로 닫힌 결정 기록 — 3af36761에서 §4.4에 단락을 끼운 것은 잘못된 판단) → 사용자 "응 ADR-013은 되돌리고 그렇게 진행해줘" | 1e8e2d33: ADR-013을 3af36761^로 복원(e5556bcc 대비 diff 0) · 같은 내용을 ADR-015 §설계-11 글머리로 · 수정 워커에 README 첫 기동 단계 포함 지시 |
| 11-05 06:25 | 루프 2 수정 회수(기준 648a20a0 · 원시각 05:28:31~06:22:07): R15-11 관문 V13 열 검사(+ 관문 끈 red 확인) · R15-12 정리 ① 켠 교착 짝 22칸 green — **40P01 0**(고리 없음) · R15-15 README + 06 복구 틀 · test-local EXIT 0(ok 50) · 전체 EXIT 0 · 메인 교차 확인 XML **1108/0/0/20** · blind · V1~V14 · k6 results · ADR-013 diff 0 | 코드 커밋 6f5dfaf4 · 새 파일 06-history-diff-recover.sql(브리핑 밖 — test-local이 실행하려고) · ADR §설계-11에 R15-11 · 12 · 15 반영 · 다음: 루프 3 |
| 11-05 06:55 | 루프 3(packet base e5556bcc · 61파일 +4579/−117 · loop-fix-diff = 648a20a0 대비 · `$OUT` scratchpad/rv15c.MaHq · mirror rv15cmir.Tslo): codex(EXIT 0 · N1~N11 위반 없음 · F1 V12 운영 크기 미측정 · F2 측정 없음 — 둘 다 범위 밖) · Opus(H · M 0 · F1 L A′ · payment-start 지표 커밋 전 증가 + OQ 6) → 종합(신규 채택 1 = R15-16) → codex 감사 '이상 없음' | **루프 3에 신규 채택 1 → 종료 조건 미충족 · 최대 3루프 → `review unresolved`** — 사용자 결정 필요(R15-16 처리 · OQ15-12 마감 회차 연장 정책 · OQ15-13 재합의 여부) |
| 11-05 07:00 | `review unresolved` 보고 → 사용자 답(AskUserQuestion): R15-16 = '지금 고치고 재확인'(루프 상한 초과 예외 — **user override**: 고친 hunk만 codex 타깃 재점검 1회) · OQ15-12 마감 회차 연장 = '받아들이고 ADR에 기록' · OQ15-13 = '구체화로 처리'(재합의 아님) | 명세 §11 · ADR §설계-11 글머리 추가 · R15-16 수정 워커 착수 · OQ15-10 · 14 → NEXT · OQ15-15 → 15-09 측정 기록 |
| 11-05 07:30 | R15-16 수정 회수(원시각 06:35:42~07:23:34 · 새 테스트 2칸 수정 전 red → 수정 후 green · 관련 179/0 · 전체 EXIT 0) · 메인 교차 확인 XML **1110/0/0/20** · 코드 커밋 8bd5f387 → codex post-fix 타깃 재점검(fix.diff + 인접 호출부 + 새 테스트만 · EXIT 0): PF15-1 1건 → 기각(도달 경로 없음 — HoldExpiryScheduler.kt:15-17이 유일 호출자 · 기존 패턴) → **신규 채택 0** | **15-08 리뷰 종료** — 루프 3 + user override(R15-16 상한 초과 수정 · 타깃 재점검 1회). 남은 것: 범위 밖 R15-4/14/17/18(15-09 · 15-10) · NEXT 이월 OQ15-4/8/10/14 · 워커 관찰(A′ missing 지표 · C 배치 C6 revert가 트랜잭션 안 — C·D 경로는 이번 범위 밖 → NEXT) · 다음: 15-09 측정 설계 |
| 11-05 07:40 | 워커 15-09(Opus — k6/ADR-015 confirm-contention 하네스(ADR-014 하네스 재사용 · keep-alive 수정 포함 · 로컬 단위 점검만) + 측정 전 고정 규칙 초안(scratchpad/adr015-measure-draft.md — ADR 반영은 메인 리뷰 뒤) · .164 접속 금지) 착수 | 백그라운드 · 초안은 설계 문서 → 듀얼 1패스 + 재점검 뒤 ADR 커밋 → 사용자 확인 → 15-10 |
| 11-05 08:20 | 15-09 회수(기준 b104fc4a · 원시각 07:27:18~08:07:24): k6/ADR-015 하네스 13파일(미추적 · ADR-014 동결 사본 source · import 재사용 · keep-alive `closeBeforeLongThink`) · 초안 scratchpad/adr015-measure-draft.md(측정-0~15 · 10셀 × 3회 · 약 8시간 · G-H/G-E/G-F · G1~G4 · 메인/사용자 결정 D-1~D-11) · 로컬 점검 전부 EXIT 0(selftest 62 · k6 inspect 5 · compose config 3 · dry-run · 로컬 스모크 7회 — current 배치 1초에서 경합 창 확정 500 4건 / conditional 5xx 0 · 404 4 · converted_after_expiry{db} 29 · 판정기 0) · 스모크로 하네스 결함 2건 자체 수정 | 혼합 산출물(설계 문서 + 실행 하네스) → 실행물 규칙: 듀얼 리뷰 루프(≤3) 착수 · packet base b104fc4a · `$OUT` scratchpad/rv15m.4wHJ · mirror rv15mmir.JzMr · 보안 스캔 키류 0 · 내부 IP 0 |
| 11-05 08:45 | 측정 설계 리뷰 루프 1 회수: codex(EXIT 0 · F1~F7 — H 4) · Opus(F1~F12 — H 2) → 종합(16건 채택) → codex 감사(EXIT 0 · 2건 정정: M15-16 기각 — prepare_db 호출부가 `prepare_db \|\| …`로 실패 처리(diff :2030-2037 · :2057-2058) · M15-3 병합 오류 → M15-3a(current 양성 대조 = 무관 5xx · 1050) / M15-3b(새 모드 양성 대조 = 성공 수)로 분리) · 사본 `…/reviews/rv15-measure-loop1/` | 채택 16(M15-1~15 · 3a/3b) · 기각 1 · 수정 워커 착수 · D-1~D-11 사용자 질문 병행 |
| 11-05 08:50 | 사용자 답(AskUserQuestion — 측정 전 고정): D-10 = '500 · 롤백 개선 + 비용'(계약 코드로 진다 · 틱 롤백 없음 = 이김 · G3 비용 유지) · 권장값 고정(D-1~D-9 · D-11 — 지연 200ms · cost 300/s · (0.1, 0.5) · ④ 2m/5s · race 600s/cost 300s · ①>②>③>④ · pgss 켬) · 규모 약 8시간 그대로 · cost 셀 ④ payment-start 포함 비교 | 수정 워커에 전달(SendMessage) |
| 11-05 09:00 | 측정 설계 수정 회수(원시각 08:18:58~08:51:50): M15-1~15 · 3a/3b fixed + 목록 밖 결함 1(404 · 403이 HTTP404/HTTP403으로 분류돼 계약 밖으로 셈 — summarize.py:40) · selftest 109 EXIT 0(메인 재실행 EXIT 0) · 로컬 스모크 8회(배치 2초 · 10초) — 다섯 모드 판정기 0 · 40P01 0 · 경합 증거 current 4 · 6 / ① 3 · 6 / ② 1 · 1 / ③ 7 / ④ 6 · ④ payment-start 600/600 extended · FM1 겹침 ① 7 · current 0~1 · ② 0 · 총 시간 약 5.9시간(gracefulStop · 시간 계산 정정) | 하네스 커밋 8388e198 · 메인 결정(기술 — 권장 채택): D-12 ④ 경계 흐름 race 0.5 · cost 0 · D-13 재선점 조밀 20ms · D-14 ② B3 대기 문턱 delay × 0.25 · D-15 새 모드 경합 표지 0 · FM1 겹침 0 = 판정 불가(보수적 — 실패로 보지 않음) · 다음: 측정 설계 리뷰 루프 2 |
| 11-05 09:15 | 사용자 "164켰고 바로 진행하면 되" — .164 확인(uptime 14분 · 사용자 컨테이너 llm-ollama · llm-wrapper · ci-cd-agent 가동 — 무접촉 · 메모리 가용 29G · 디스크 40G) · codex 측정 리뷰 루프 2(EXIT 0 · F1~F6 — H 2는 새 모드 양성 대조의 근사성 · 루프 1 M15-3b와 같은 자리) | 명세 §5 '측정 전 ADR 고정' 유지 → 본 캠페인은 루프 2 수정 · ADR 커밋 뒤 · 그동안 .164 통합 시험 1회(current race · 하네스 8388e198 · 결과 scratchpad/int164 — 판정 · 시작 조건에 쓰지 않음) |
| 11-05 09:35 | 측정 설계 리뷰 루프 2 회수: codex(F1~F6 — H 2: 새 모드 양성 대조 근사) · Opus(F1 H 'A도 같은 문턱' 누락 · F2~F4 M) → 종합(채택 12 · M15-26 기각 = 받아들인 한계) → codex 감사: **M15-26 기각 부적절**(계획 오프셋 기준 404 · B3 전체 실행 시간 max는 충돌 증명 불가 · 같은 시드 parity는 그 모드 회차의 충돌을 증명 못 함) → **메인 재판정: M15-26 채택(재설계)** — ①③④ = 실측 송신 시각(시계 차 보정) < e − 여유 ∧ 본문 code HOLD_NOT_FOUND(:now < e인데 C1/O2 0행 = 배치가 지움 — 서버 논리로 증명) · ② = 샘플러의 배치 DELETE seat_hold Lock 대기 관측 · 관측 불가면 판정 불가 | 신규 채택 13(M15-17~28 · 26) · 루프 2 수정 워커 · 사본 `…/reviews/rv15-measure-loop2/` · 루프 1 M15-3b의 근사 표지를 서버 논리 증거로 바꾸는 재설계(정지 규칙 — 같은 설계 polish가 아니라 증거 원천 교체) |
| 11-05 09:20 | .164 통합 시험 회수(하네스 8388e198 · current race rep1 · 09:02:41~09:16:53 · prepare EXIT 0 · run EXIT 0 · 판정 · 시작 조건 미사용): status ok · reasons 0 · k6 rc 0 · 판정기 13열 전부 0 · 40P01 0 · 확정 5xx 28(win 단독 22 · win 이중 4 · post 1 — 우연한 가드 갈래) · 틱 실패 4/71 · unique_violation 723(FM2 짝) · FM1 겹침 10 · 확정 p99 205.8ms · DB CPU 19.8% · 시계 차 0.067s · flyway 1~13(기본 + updated-at + incremental) · 원격 seatlab 컨테이너 3개는 다음 회차 down -v 대상(사용자 컨테이너 무접촉) | **실경로(배포 · 기동 · 수집 · 요약) 동작 확인** · current 양성 대조(서버 쪽 5xx + 틱 실패)가 .164에서도 섬 · `post\|single\|HTTP404` 49 = M15-21(본문 code) 대상 |
| 11-05 09:45 | 측정 설계 루프 2 수정 회수(원시각 09:21:06~09:34:57): M15-17~28 fixed · selftest 143 EXIT 0(메인 재실행 EXIT 0) · 로컬 스모크 5회 — 새 증거 ① 8 · ② 3 에피소드 · ③ 5 · ④ 2 · current 5 · 잘못된 거부 0 · 계약 밖 쌍 0 · V12 모양 음성 8갈래 0 | 하네스 커밋 5c1b96e5 · 메인 결정: ①③④ 증거식 = 응답 끝 기준(`te + ε − delay + 20ms < e ∧ te − ts ≥ delay` — 송신 기준은 :now 앞 대기 상한이 없어 증명 불가 · AtomicConfirmProcess.kt:41 · :69) 채택 · B3 표본기 20ms = race 셀 다섯 모드 공통(②만 켜면 표본 부하가 ② 비용에만 섞임) 채택 · cost 재선점 0은 사용자에 보고됨 · 미검증: B3 표본기 원격 경로(.164 스모크에서 확인) · 다음: 루프 3 |
| 11-05 09:55 | 측정 설계 리뷰 루프 3(packet base b104fc4a · 14파일 +2833 · `$OUT` scratchpad/rv15o.Rm1P · mirror rv15omir.fsha): codex(F1 H current 양성 대조 원인 미구분 · F2 · F3 M V12 결측 무음) · Opus(F1 M ④ 기준 흐름 잘못된 거부 기준이 원래 e · F2 M race G3에 ④ 재선점 구조 차이 · F3 L 'current 유지' 사유 갈래 · F4 L 인용 줄 ExpireHoldsService.kt:39 → :47 · OQ 7 — ①④ · ② 증거 논리는 코드로 성립 확인) → 신규 채택 7 → **`review unresolved`(루프 3 상한)** · .164 통합 시험 2(하네스 5c1b96e5 · pessimistic race · 09:38:51~09:52:55 · ok): B3 표본기 원격 경로 동작 — 에피소드 26(추정 ≈20) · 5xx 0 · 틱 실패 0 · 판정기 0 · 40P01 0 · unique_violation 0 · revert not_held/kept_active 0 | 사용자 답(AskUserQuestion): '고치고 재확인 뒤 측정'(**user override** — 상한 초과 수정 + codex 타깃 재점검 1회) · race 셀 비용 지표는 G3 판정에서 제외(설명 열 · 비용은 cost 셀만) |
| 11-05 10:30 | 측정 설계 루프 3 수정 회수(M15-29~35 + OQ2 · selftest 167 EXIT 0 · 메인 재실행 EXIT 0 · .164 원자료 재요약: current 귀속 증거 32 = 0행 DELETE 확정 5xx 28 + 틱 실패 4 · 귀속 안 됨 0 · pessimistic ok · B3 26) → 하네스 커밋 86ffbcd1 → codex 타깃 재점검(EXIT 0): PF-m1 1건 → 기각(V12는 모드 독립) → 신규 채택 0 | **측정 설계 리뷰 종료**(3루프 + user override) · 초안을 ADR-015 '## 측정 전 고정 규칙'으로 옮겨 커밋 = **측정 전 고정** · 다음: 시작 조건(.164 스모크 7회 · 캠페인 스모크 · dry-run · PC 점검 · 전체 테스트 green 확인) |
| 11-05 10:40 | 15-10 착수 — 시작 조건 ① .164 스모크 7회(하네스 86ffbcd1 · id smoke15-20261105 · 다섯 모드 race + current · conditional cost · 러너 scratchpad/smoke15.sh · 판정 미사용) 시작 · ⑦ 근거: main-app · reconciler · 빌드 파일 diff 8bd5f387..774cb308 = 0 → 1110/0/0/20 green(11-05 07:23:34 전체 실행)이 측정 SHA의 앱 코드와 같다(k6 PC = 이 PC라 측정 중 gradle 재실행은 부하 생성기를 흔들어 하지 않음) | 다음: 스모크 판정 → ② 캠페인 스모크(두 자리) → ③~⑥ → 캠페인 |
| 11-05 16:20 | 시작 조건 ① 스모크 7회 회수(14:45~16:15 · 하네스 86ffbcd1): ok 5 · **invalid:dropped-iterations 2** — ④ race(rehold 13 · 15:52:20~24) · current cost(rehold 21 @ k6 +107초 · 8 @ +179초) · 누락은 rehold 시나리오만 · 경합 증거 current 29 · ① 19 · ② 14 에피소드 · ③ 16 · ④ 11(early-check rc 0) | 원인: k6 constant-arrival-rate는 빈 VU가 없으면 반복을 버리고 VU를 새로 만든다 — rehold 미리 만든 VU 여유 약 5%(rate × 45초 대 실수명 43초 · ④ race 평균 약 980 대 1000 · rehold 반복 최대 152.6초) · cost rehold 최소 20(반복 약 1ms라 k6 순간 정지에 몰림) → 하네스 수정 29e09aef(plan15 VU = 1.3 × 도착률 × 수명 × ρ · 최소 도착률 × 1초 · selftest 161 EXIT 0 — 기준 160 + 1) · 측정 규칙 · 판정식 무변경 · 새 SHA라 이미지 재준비 + 실패 2셀 재실행(id smoke15b-20261105) · 듀얼 1패스 리뷰 병행 |
| 11-05 16:46 | 재실행 회수(29e09aef · smoke15b-20261105): ④ race ok(누락 0 · 증거 8 · vus 최대 2745) · current cost ok(누락 0 · vus 9116) · 리뷰 VU-1 결함 없음 · VU-2 기각(결정적 시드 최대 동시 세션 근거) · 시작 조건 JSON `k6/ADR-015/results/start-conditions-{csmoke-,}29e09aef.json`(⑤ 시계 차 PC−서버 −0.041~0.055초 · #12 앱−DB 괄호 중앙 4~15ms · #15 pgss 켬 · VU 최대) · ⑦ 8bd5f387..29e09aef 앱 · 빌드 diff 0 · ④ pccheck14 ok(load 2.6 · alert-system 11개 기록만) · dry-run 30자리 5.92시간 | ② 캠페인 스모크 campaign15smoke-20261105 시작(16:46:05 · --skip-v12 · 러너 scratchpad/csmoke15.sh — 세 번째 자리 진입 확인 뒤 멈춤) |
| 11-05 17:15 | ② 캠페인 스모크 회수(campaign15smoke-20261105): rep1 race conditional ok(16:46:05–17:00:07) · race pessimistic ok(17:00:07–17:14:11) · 세 번째 자리 race optimistic 진입(17:14:11) 확인 뒤 중지(EXIT 143 · 남은 k6 · campaign 프로세스 0 — 원격 seatlab은 다음 회차 down -v) · start-conditions-29e09aef.json campaign_smoke.done = true · pccheck14 재확인 ok(load 5.61) | **본 캠페인 시작** 17:14:53 — id 20261105-adr015-29e09aef · 하네스 29e09aef(동결본 해시 a50d3ca5d837) · 이미지 images-29e09aef.json · 30자리 + V12 단계 · 예상 5.92시간 + V12 · setsid nohup(세션과 분리) · 시작 조건 ⓪~⑦ 전부 통과 |
| 11-05 18:44 | 사용자 ".164를 써야 해 잠시 중지" → 캠페인 중지: 끝난 자리 6(rep1 race 다섯 모드 ok · early-check current 경합 증거 25 · rep1 cost pessimistic ok) · 7번째 rep1 cost optimistic(18:36:12 시작) 진행 중 중단 — campaign 세션 그룹 TERM → KILL · PC 쪽 k6 · run.sh 0 · .164 `docker compose -p seatlab down -v`(seatlab 컨테이너 4 · 볼륨 · 네트워크 제거 · 사용자 컨테이너 llm-ollama · llm-wrapper · ci-cd-agent 무접촉) | 보류 — 재개 = 같은 명령(--sha 29e09aef --images images-29e09aef.json --start-conditions start-conditions-29e09aef.json) · order.txt 대조 뒤 끝난 6자리 건너뜀 · 중단된 자리는 status 없는 시도 1(하네스 몫 1회 소모)로 세고 attempt2부터 · 재개 전 pccheck14 · .164 상태 재확인 |

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
| R15-11 | 2 | codex F1 | HoldStrategyStartupCheck.kt:58-65 · :143-150 — V13 열 존재를 migrate 뒤에만 확인(거부 기동이 V14 트리거를 만들 수 있음) | 채택 → 관문에서 열 확인 | fixed | 2(6f5dfaf4) |
| R15-12 | 2 | Opus F1 | Adr015 레이스 컨텍스트 전부 PIN_CLEANUP_OFF · 교착 칸 = 확정 ↔ A′ 하나 — spec ⑤ '교착 × current · ①~④' · ADR §설계-7 #9 | 채택 → 정리 ① 켠 컨텍스트 교착 짝 칸(fix verification test) | fixed | 2(6f5dfaf4) |
| R15-13 | 2 | Opus F2 | docs/adr/ADR-013-counter-config-cutover.md:107-108 — §4.4 표에 앱 첫 기동 out-of-order 1회 없음 | 채택(문서 — 메인) → 3af36761에서 ADR-013에 넣었다가 **사용자 결정(11-05)으로 되돌림** → ADR-015 §설계-11 + ops/online-v9/README.md | fixed | 2(1e8e2d33) |
| R15-14 | 2 | codex F2 | k6/ADR-015 · 결과 미측정 | 범위 밖(R15-4와 같음) | — | |
| R15-15 | 2 | 감사(OQ15-9 재검토) | ops/online-v9/apply.sh:271 · test-local.sh:255 · ADR-015:522 — history-diff 실패 뒤 남은 이력 행의 확인 · 재개 절차 없음 | 채택 → runbook 절차 | fixed | 2(6f5dfaf4) |
| OQ15-5 | 2 | Opus F3 | test/resources/config/application.properties — bootTestRun에도 test 프로필 | 받아들인 한계(로컬만) → ADR T1 아래 기록 | fixed | 2(3af36761) |
| OQ15-6 | 2 | Opus OQ1 | 수정한 기존 테스트 목록 | 해소(log 11-05 01:50 행) | — | |
| OQ15-7 | 2 | Opus OQ2 | 모드 혼합 금지 절차의 자리 | 15-09 측정 절차에 | — | |
| OQ15-8 | 2 | Opus OQ3 | ADR-013 하네스 V12 SHA 거부 → 재실행 불가 | NEXT(슬롯별 target) | user-deferred 후보 | |
| R15-16 | 3 | Opus F1 | SweepConditionalBatch.kt:85-95 · :113-123 · PaymentStartService.kt:49 — A′ deleted/revert/extension_lost · extend{extended} 커밋 전 증가 | 채택(L) | fixed | 3+(8bd5f387 · user override) |
| R15-17 | 3 | codex F1 | V12:4-6 · ops/confirm-unique-v12/README.md:18 — 운영 크기 V12 생성 시간 미측정 | 범위 밖([구현 검증] #5 — 15-09 · 15-10) | — | |
| R15-18 | 3 | codex F2 | k6/ADR-015 · 결과 미측정 | 범위 밖(R15-4와 같음) | — | |
| OQ15-10 | 3 | Opus OQ1 | HoldStrategyStartupCheck.kt:210 · 생성자 — confirm 기본값 | open question | user-deferred(NEXT) | |
| OQ15-12 | 3 | Opus OQ3 | PaymentStartService E1 — 마감 회차 홀드도 연장(ADR §3.4에 없음) | open question(사용자 정책) | fixed | 3(사용자 결정 · ADR 기록) |
| OQ15-13 | 3 | Opus OQ4 | 명세 승인 뒤 설계 변경(T1 · T2 · §설계-11) 재합의 대상 여부 | open question(사용자 보고) | fixed | 3(사용자 결정 · 명세 §11) |
| OQ15-14 | 3 | Opus OQ5 | reaches() 특수 target(current/next) 메시지 | open question | user-deferred(NEXT) | |
| OQ15-15 | 3 | Opus OQ6 | A′ B2b 쌍마다 SELECT | 15-09 측정 기록 항목 | — | |
| PF15-1 | post-fix | codex(타깃 재점검) | ExpireHoldsService.kt:46 — tx.execute가 바깥 트랜잭션에 합류하면 A′ 지표 · tick{ok}가 커밋 전 | 기각 — 도달 경로 없음: 유일한 호출자 HoldExpiryScheduler.kt:15-17(@Scheduled · 트랜잭션 없음) · 같은 자리의 tick{ok} · counters.afterCommit은 ADR-009부터 있던 패턴(이번 diff가 도입한 것 아님) | — | |

측정 설계 리뷰(15-09 · base b104fc4a · 초안 scratchpad/adr015-measure-draft.md + k6/ADR-015):

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| M15-1 | 1 | codex F1 | 초안 :149 · :176 · summarize.py(판정) — 유효 3회 부족 = 후보 실패로 오결론 | 채택 → 판정 불가 | fixed | 1(8388e198) |
| M15-2 | 1 | codex F2 · Opus F4 | 초안 :175 · summarize.py:431-446 — 앞 순위 G3 판정 불가인데 뒤 후보 교체 | 채택 | fixed | 1(8388e198) |
| M15-3a | 1 | codex F3 · 감사 | 초안 :172 · summarize(diff :1483-1490) — current 양성 대조가 무관 5xx · 1050으로 성립 | 채택 → win 묶음 · FM 결과에 묶음 · 1050 분리 | fixed | 1(8388e198) |
| M15-3b | 1 | Opus F3 · 감사 | 초안 :20 · :149 · summarize.py:331-335 — 새 모드 양성 대조 = 성공 수(실충돌 아님) | 채택 → 모드별 충돌 표지 | fixed | 1(8388e198) |
| M15-4 | 1 | codex F4 · Opus F7 | confirm-contention.js:85-91 · summarize.py:264 — ④ payment-start 200 · 연장 하한 없음 · 비200 무음 | 채택 | fixed | 1(8388e198) |
| M15-5 | 1 | Opus F1 | 초안 :149 · confirm-contention.js:85-91 · AtomicConfirmProcess.kt:73-74 — ④가 연장으로 경계를 구조적으로 못 엶 | 채택 → ④ 전용 경계 흐름 | fixed | 1(8388e198) |
| M15-6 | 1 | Opus F2 | plan15.py:31 · :40 · confirm-contention.js:119-120 — 재선점이 경합 창 뒤라 FM1 불가 | 채택 → 재선점 시각 · FM1 양성 대조 또는 한계 명시 | fixed | 1(8388e198) |
| M15-7 | 1 | codex F5 | v12-build.sh 시드 · summarize — 시드 실패 · 행 수 미검증 | 채택 | fixed | 1(8388e198) |
| M15-8 | 1 | codex F6 | summarize(V12) — 계획 크기 · 반복 완전성 미대조 | 채택 | fixed | 1(8388e198) |
| M15-9 | 1 | codex F7 | 초안 :213-215 · actsnap/pgss — #15 틱당 문장 수 · 시간 미계산 | 채택 | fixed | 1(8388e198) |
| M15-10 | 1 | Opus F5 | summarize.py:252-260 — 계약 밖 코드를 창 안만 | 채택 | fixed | 1(8388e198) |
| M15-11 | 1 | Opus F6 | campaign.sh:137-140 · summarize.py:471-480 — 조기 확인 invalid → exit 4 영구 정지 | 채택 | fixed | 1(8388e198) |
| M15-12 | 1 | Opus F8 | summarize.py:115-119 · :242-244 — 선점 5xx · 1050이 hold-fail(G-H)로 숨음 | 채택 | fixed | 1(8388e198) |
| M15-13 | 1 | Opus F9 | summarize.py:378 — G2(a)에 40P01 누락 | 채택 | fixed | 1(8388e198) |
| M15-14 | 1 | Opus F10 | summarize.py:405 · :447 — undet 미사용 | 채택 | fixed | 1(8388e198) |
| M15-15 | 1 | Opus F11 | summarize.py:284 · run.sh:262 — lateness 감쇠 최대 | 채택(L) | fixed | 1(8388e198) |
| M15-16 | 1 | Opus F12 | v12-build.sh:73 — docker stop rc | 기각(감사 — 호출부 `prepare_db \|\| …`) | — | |
| M15-17 | 2 | Opus F1 | 초안 :149-162 · :197 · summarize.py:407-426 · :526-527 — ADR-014 :765/:800 'A도 같은 문턱' 누락 | 채택(H) | fixed | 2(5c1b96e5) |
| M15-18 | 2 | Opus F2 · codex F1 일부 | summarize.py:331-332 · :346 — FM1 겹침이 post 묶음까지 | 채택 | fixed | 2(5c1b96e5) |
| M15-19 | 2 | Opus F3 | summarize.py:71 · 초안 :179 · :190 — pre 묶음 잘못된 거부 관문 없음 | 채택 | fixed | 2(5c1b96e5) |
| M15-20 | 2 | Opus F4 | confirm-contention.js:130-146 · plan15.py:39 — cost ④ 재선점 요청 구조적 차이 | 채택 → cost 셀 재선점 끔 | fixed | 2(5c1b96e5) |
| M15-21 | 2 | codex F3 · Opus OQ3 | diff :1715 · :2015 — 404 · 403 본문 code 미확인 | 채택 | fixed | 2(5c1b96e5) |
| M15-22 | 2 | codex F4 | diff :613 — V12 모양 검사 약함 | 채택 | fixed | 2(5c1b96e5) |
| M15-23 | 2 | codex F5 | diff :153 · :2112 — ④ expiresAt 파싱 실패 무음 · 확정 수 미대조 | 채택 | fixed | 2(5c1b96e5) |
| M15-24 | 2 | codex F6 | diff :858 — Hikari 풀 크기 미대조 | 채택 | fixed | 2(5c1b96e5) |
| M15-25 | 2 | codex OQ2 | 초안 :262 — PC 메모리 하한 관문 없음 | 채택 | fixed | 2(5c1b96e5) |
| M15-26 | 2 | codex F1 · F2 · 감사 | diff :2124 · :2138 · 초안 :168 — 새 모드 양성 대조가 충돌 증명 못 함 | 기각 → **감사로 채택(재설계)** | fixed | 2(5c1b96e5) |
| M15-27 | 2 | codex OQ1 | 초안 :288 — D-12~15 표기 | 채택(문서) | fixed | 2(5c1b96e5) |
| M15-28 | 2 | Opus OQ2 · OQ4 | 초안 :197 · summarize.py:586-599 — 닿지 않는 갈래 · 근거 | 채택(문서) | fixed | 2(5c1b96e5) |
| M15-29 | 3 | codex F1 | 초안 :24 · :214 · summarize(diff :2477-2496 · :2557-2560) · ExpireHoldsService.kt:45-61 — current 양성 대조(틱 실패 · win 5xx)가 원인 미구분 | 채택(H) → 앱 로그 예외 종류(0행 DELETE — StaleObjectState/ObjectOptimisticLockingFailure)로 원인 귀속 · G2도 원인별 | fixed | 3+(86ffbcd1 · user override) |
| M15-30 | 3 | codex F2 | diff :611-616 · :2666-2668 · 초안 :239-244 — V12 단계 실패/누락이 최종 결론에 반영 안 됨 | 채택 → 캠페인 완료 관문 · 결론 판정 불가 | fixed | 3+(86ffbcd1 · user override) |
| M15-31 | 3 | codex F3 | diff :2737-2762 — V12 CONCURRENTLY 시간값 · 탐침 결과 검증 없음 | 채택 | fixed | 3+(86ffbcd1 · user override) |
| M15-32 | 3 | Opus F1 | confirm-contention.js:103 · :131 · :140 · summarize.py:447 — ④ 기준 흐름 잘못된 거부 기준이 연장 전 e | 채택 → 그 확정 앞 서버가 준 가장 늦은 expiresAt | fixed | 3+(86ffbcd1 · user override) |
| M15-33 | 3 | Opus F2 | 초안 :202 · :213 · plan15.py:41 · summarize.py:63 — race G3에 ④ 재선점 구조 차이 | 채택 → 사용자 결정: race 비용 지표 G3 제외(설명 열) | fixed | 3+(86ffbcd1 · user override) |
| M15-34 | 3 | Opus F3 | 초안 :220 · summarize.py:753-758 — 'current 유지' 사유 갈래 부정확 | 채택(L) | fixed | 3+(86ffbcd1 · user override) |
| M15-35 | 3 | Opus F4 | 초안 :115 — 인용 ExpireHoldsService.kt:39 실재 아님(:47 · SweepConditionalBatch.kt:38 · :61 · :72) | 채택(L) | fixed | 3+(86ffbcd1 · user override) |
| PF-m1 | post-fix | codex(타깃 재점검) | summarize.py:881 · campaign.sh:176 — V12 판정 불가여도 결론 '교체' 유지 | 기각 — V12는 기본 위치라 current 포함 모든 모드에 같이 들어간다(모드 선택과 독립 · V12__…sql · application.yml) · V12 결과는 '트랜잭션 안 판 유지 / CONCURRENTLY 판 후속'이라는 배포 방식 질문 · 결론에 '운영 적용 전 V12 판정 필요' 강제로 충분 | — | |
| VU-1 | 하네스 수정 1패스(29e09aef) | Opus | plan15.py:158-160 — 수명 모델 · 1.3 여유 · 소비처 | 결함 없음(수명 main 31 · rehold 43 · ④ +110 실흐름과 일치 · 소비처 run.sh만 · 미리 만든 VU +3%) | — | |
| VU-2 | 하네스 수정 1패스(29e09aef) | codex | plan15.py:161 — 1.3배는 무누락 보장 아님(최악 20/s × 152.5초 = 3,050) | 기각 — 세션 속성은 (회차, 세션 번호) 결정적 · 계획 회차 rep1~3 실제 속성 최대 동시 세션(수명 상한 기준): ④ race rehold 1038 · 1039 · 1014 < 1274 · main 1765 < 2236 · race rehold 470 < 559(옛 1000은 rep1 1038에 못 미쳐 스모크 누락과 일치) · 남는 위험 = k6 정지 · 지연 꼬리(cost rehold 최소 = 도착률 × 1초) · 근거 `…/reviews/rv15-vu-fix/vupeak.mjs` | — | |

- 주(설계 선검증 표): Opus 설계 선검증 원문은 파일로 남지 않았다(워커 회수 본문) — 위 id 대응은 ADR-014 log 11-04 16:40 행의 요약에서 옮긴 것.

## 생략한 검증

- (없음 — DEBT=0)

## 완료 요약 (마감 시)

- (미작성)
