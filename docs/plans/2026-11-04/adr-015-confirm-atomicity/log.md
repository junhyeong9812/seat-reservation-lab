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

## 리뷰 ledger (中↑ — 듀얼 리뷰가 돈 작업 필수)

- review packet(설계 선검증 · 루프 1): base e862322d^ · `$OUT` = scratchpad/ds15res.H8ul(정리됨 — codex 결과 사본 `k6/ADR-014/results/_evidence-20261104/reviews/ds15-design/` · Opus 결과는 워커 회수 본문 — ADR-014 log 11-04 16:40 행 요약) · mirror scratchpad/ds15mir.Op8V(삭제됨)
- review packet(수정분 재점검 · 루프 1 post-fix): base 61226b03 · 결과 사본 같은 폴더 `codex-postfix.md`

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

- 주: Opus 설계 선검증 원문은 파일로 남지 않았다(워커 회수 본문) — 위 id 대응은 ADR-014 log 11-04 16:40 행의 요약에서 옮긴 것.

## 생략한 검증

- (없음 — DEBT=0)

## 완료 요약 (마감 시)

- (미작성)
