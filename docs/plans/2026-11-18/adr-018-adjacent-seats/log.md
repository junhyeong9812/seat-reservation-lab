# 작업 기록 — ADR-018 연석 부분 선점 (1단계: 재현 · 설계 · 구현)

> 명세: `requirement-spec.md` · 브랜치 `feat/adr-018-adjacent-seats`(기준 721983c29) · stakes 높음 · MODE auto

## 타임라인 (시각 | 사건 | 결과)

| 시각 | 사건 | 결과 |
|------|------|------|
| 2026-11-18 | 인터뷰(AskUserQuestion 2회 + 확인 1회) · 명세 작성 | 합의 대기 |
| 2026-11-18 | 사용자 "응 합의, auto로 진행하고 payment도 PR 올려줘 …" → set-state spec-approved · mode auto · Q/A 9 확정(묶음은 refund · none만) | SPEC=1 · MODE=auto |
| 2026-11-18 | 독자용 문서 낱말 규칙(korean-wording · wording-table) 확인 — 이 작업의 ADR-018 본문 · README · PR 본문에 적용(명세 · log는 대상 아님) | 기록 |
| 2026-11-18 18:2x | task 01 재현 스모크(B0 · PC · 임시 postgres:16 127.0.0.1:25432 · bootJar(721983c29 트리) · loadtest 프로필 · mode none · 기본 pessimistic-nowait · counter-upsert-precheck · TTL 5분 · 시드 1회차 2,000석 · 스크립트 scratchpad/adr018/b0_smoke.py) — ① 경합 없음 1인 2석 20회: 20/20 둘 다 201 ② 두 사용자 같은 2석 반대 순서 · 동시 시작 200회: **200/200 두 사용자 모두 1석만**(부분 선점 400명 · 201 400 · 409 SEAT_NOT_AVAILABLE 400 · 지연 0~20ms를 둘째 요청 앞에 넣어도 200/200 같음) ③ 둘째 사용자 시작을 0~50ms 늦춤 100회: 한 사람이 2석 74 · 둘 다 1석 26(부분 선점 52명) · DB: HELD 600 = seat_hold 600 · quota cnt=1 452명(= 400 + 52) · cnt=2 74명 · held_at→expires_at 전부 5분 · /internal/consistency 위반 0(부분 선점은 불변식상 '정상'으로 보임) · `DELETE /api/holds/1` → 404(해제 경로 없음) | 가정 1 실증(부분 선점 실재) · TTL 경과 확인 대기 |
| 2026-11-18 18:3x | task 01 TTL 확인: 선점 뒤 ~150초 seat_hold 600 · HELD 600 · 만료(09:31:14) 뒤 ~350초 seat_hold 0 · HELD 0 · cnt>0 0 · consistency available 2000 · 정리(JVM 0 · 컨테이너 0) | task 01 끝 — 부분 선점이 TTL까지 묶임 실증 |
| 2026-11-18 18:4x | task 02 설계: explorer(Sonnet · 171,864 토큰 · 136초)로 '홀드 1 = 좌석 1 = intent 1 = 예약 1' 전제 위치 조사 · 핵심 사실: PG 등록 금액 = 상수 `seat.payment.amount`(PaymentProperties.kt:11) · CLOSE는 금액 인자 없이 등록 금액 전체 환불(mock-pg Ledger) → 묶음 = N × 단가 등록 · CLOSE 1건 · ADR-018 설계-1~8 작성(V16 hold_group · seat_hold.group_id · payment_intent group_id/seat_count · reservation (intent, seat) 유니크 · 다석 API split/one-shot(SKIP LOCKED) · 해제 API(FOR UPDATE ↔ 결제 의도 INSERT FOR KEY SHARE) · 묶음 결제 시작/확정 · enabled 기본 false + 기동 검사) · 낱말 검사 0 | 설계 선검증(codex high) 실행 중 |
| 2026-11-18 19:0x | 설계 선검증 회수(codex high · 미러 scratchpad/adr018/dv-mirror · 출력 dv-out/codex-design.md · 보안 스캔 0) — finding 7(high 3 · medium 4) → ledger DV-1~7 전부 채택 · 설계 반영은 재개 때 · 사용자 지시 "노티·페이먼츠 ADR-000 마무리 · 도메인 설계 확인 뒤 ADR-018" + 순서 답 '원래 순서 ledger 먼저' → **ADR-018 일시 중지**(재개 지점: DV-1~7을 ADR-018 설계 절에 반영 → task 03 blind · 04 구현) | 중지 |
| 2026-11-19 16:0x | **재개**(notification ADR-000 PR #1 병합 뒤 · 사용자 "진행하자") · 낱말 메모(NOTE) 확인 — 다른 세션 낱말 변경은 이미 커밋됨(ac83e8a88 · 31cbfd5e5 · ed3946a96) · DV-1~7 코드 재확인(PaymentStartService.kt:45-112 · SweepConditionalBatch.kt:35-75 · LazyExpiryBatch.kt:236 lockQuotaPairs · LoadtestDataService.kt:44-55 · PaymentConfirmService.kt:153 · PgRegistration.kt:24-26 · PgClient.Ok(state만) · mock-pg Ledger.kt:38 · UserLimitStrategies.kt:318-330) → 지적 그대로 확인 · ADR-018 설계 반영(설계-1 reset · 설계-2 +N 0행 재확인 · 불일치 탐지 등록 · 설계-3 잠금 순서 카운터 → 홀드 · 설계-4 재호출 먼저 · 연장 조건 `expires_at < now + W` · 0행 진단 · 갈라짐 거부 · 금액 인자 · 등록 금액 확인 · 설계-5 재생 N행) · 낱말: 본문 남은 후보 0 · 머리글 옛 낱말 8곳은 다른 ADR과 같게 치환 | DV 반영 |
| 2026-11-19 16:2x | 메인: support/IntegrationTest.resetData에 hold_group 삭제(표 있을 때만) · test-contract.md(설정 키 enabled 기본 false · mode 기본 split · HTTP 모양 · 새 오류 코드 3 · DB · consistency 4필드 · PG 금액 · 파일 경계 adr018/ ↔ adr018impl/) → task 03 blind 워커(Opus · worktree · 구현 미열람) ∥ task 04 구현 워커(Opus · 원 저장소) 착수 | 착수 |
| 2026-11-19 16:5x | task 03 회수(blind 워커 · Opus · worktree HEAD 721983c29 — 시험만이라 영향 없음): adr018/ 5파일 · 메서드 37(실행 49 — G1~G3 12개 × split · one-shot) · compileTestKotlin 통과 · 구현 없이 14건 실패(예상 원인뿐) · 해석 11(payBy는 같은 시각 1석과 같음 · G3 교착 판정 = 5xx 0 + 409 SEAT_NOT_AVAILABLE만 + 응답 < 1초(deadlock_timeout) · DV-3 래치는 `insert into user_hold_quota` SQL 모양에 기댐 · 해제 뒤 hold_group 행 남음 · 확정 성공 본문에 compensation 키 허용 · intent 없는 묶음 확정은 시험 밖 등) · 원 저장소 복사는 구현 워커 회수 뒤(구현 워커의 전체 실행에 섞이지 않게) | 회수 |
| 2026-11-19 17:4x | task 04 회수(구현 워커 · Opus · 346,976 토큰 · 52분): adr018impl 39/39 · 전체 cleanTest test: main-app 1291 중 실패 7 · mock-pg 24/0 · reconciler 6/0 · 실패 7 = 스키마 모습 단언(Flyway 적용 버전 목록 5 · IndexMigrationTest 인덱스 집합 1 · Adr017PaymentStartIntentTest:101 reservation.intent_id 단독 유니크 1) — G6 '기존 단언 무변경'과 설계-1(V16 · 예약 유니크 (intent, seat))이 충돌 → 워커가 멈추고 보고 · 정한 것: HOLD_IN_GROUP 검사는 enabled일 때만 · 묶음 결제 시작 · 확정은 id 순 FOR UPDATE 하위 질의로 잠금(해제와 교착 방지 — 설계 보강) · 55P03 SQLSTATE 판정 · openForHold FOR KEY SHARE(설계-3 ↔ 설계-8 '1석 쿼리 그대로' 긴장) · 묶음 경로 측정 지표 없음(2단계) → 메인: blind adr018/ 원 저장소로 복사(IntegrationTest 동일 확인) · blind 실행 · 사용자에게 G6 재합의 질문 | 재합의 대기 |
| 2026-11-19 18:0x | blind 49건 첫 실행(구현 붙임): 실패 3 — ConsistencyTest 500(loadtest 프로필의 appPathInjector ↔ adr015ExpiryHook 빈 중복 · 시험 구성) · G5 404/403 준비에서 HOLD_LIMIT_EXCEEDED(시험 준비) · 없는 묶음 확정 404 기대 ↔ 구현 409 SEAT_NOT_HELD(설계 미정) · 사용자 재합의(AskUserQuestion): '7개 모두 고침' — G6을 '동작 단언 그대로 · 스키마 모습 단언은 V16에 맞춤'으로 좁힘 · N17-1 → 예약 수 ≤ seat_count · 메인 결정: 없는 묶음 · 모르는 uid = 409 SEAT_NOT_HELD · 기록 없음(ADR-017 Q3와 같음) · ADR-018 설계-1 · 5 · 8 갱신(N17-1 재정의 · SEAT_NOT_HELD · id 순 FOR UPDATE 하위 질의 · openForHold FOR KEY SHARE) · test-contract §2 → blind 워커 재개(시험 3 고침) ∥ 구현 워커 재개(단언 7 + 전체 실행) | 수정 중 |
| 2026-11-19 19:3x | 리뷰 루프 1 회수: codex high(F3 · open 1) ∥ Opus 워커(221,876 토큰 · F2 · open 8) → 메인 종합: 채택 5(R1-1~5) · open question 정리(R1-OQ) · 수정은 구현 워커 재개(시험은 별도 복사본에서 — 메인 전체 실행과 충돌 방지) | 수정 중 |
| 2026-11-19 19:4x | codex 종합 감사(medium): R1-1~3 · 5 동의 · OQ 교착은 확인 과제 동의 · R1-4는 계약 변경으로 표시 + 설계-7 '언제나 열린다' 충돌 → ADR 설계-7 문장 수정 · 기본 조합에서 enabled=false 1석 해제 204는 기존 시험이 그대로 봄 | 반영 |

## 리뷰 ledger

| id | 루프 | 출처 | 위치 | 판정 | 상태 |
|----|------|------|------|------|------|
| DV-1 | 선검증 | codex | 설계-4 · PaymentStartService.kt:47 · 100 | 채택 — 묶음 결제 시작 재호출: 결제 의도를 먼저 조회해 재생 · 첫 호출 0행은 1석처럼 원인 진단 | fixed(설계 반영) |
| DV-2 | 선검증 | codex | 설계-4 · PaymentStartService.kt:53 · 58 | 채택 — 연장 조건에 `expires_at < now + W` 누락(TTL > W 설정에서 만료를 앞당김) · 1석과 같은 조건 · 연장 불필요 판정 | fixed(설계 반영) |
| DV-3 | 선검증 | codex | 설계-2 · UserLimitStrategies.kt:320 | 채택 — `+N` 0행이면 좌석을 새 문장으로 다시 읽어 좌석 불가를 먼저(precheck와 같게) | fixed(설계 반영) |
| DV-4 | 선검증 | codex | 설계-3 · SweepConditionalBatch.kt:43 · 60 | 채택 — 해제(홀드→쿼터)와 sweep(쿼터→홀드) 잠금 순서 반대 → 교착 · 해제도 쿼터 먼저 | fixed(설계 반영) |
| DV-5 | 선검증 | codex | 설계-1 · LoadtestDataService.kt:49-52 | 채택 — reset TRUNCATE 목록에 hold_group(FK) 없으면 V16 뒤 reset 실패(G6) | fixed(설계 반영) |
| DV-6 | 선검증 | codex | 설계-5 · PaymentConfirmService.kt:153 | 채택 — 묶음 확정 재생은 intent_id로 N행 정렬 조회 + seat_count 대조 | fixed(설계 반영) |
| DV-7 | 선검증 | codex | 설계-4 · PgRegistration.kt:24 · mock-pg Ledger.kt:38 · 81 | 채택 — 묶음 등록 금액 N × amount를 명시 전달(기존 등록기 재사용 시 1석 금액) · 재등록 금액 확인 | fixed(설계 반영) |

| R1-1 | 1 | codex | HoldGroupPaymentStartService.kt:62-79 · 95-101 | 채택 — 재호출 · 0행 진단 경로가 남은 홀드 수 = seat_count를 대조하지 않음(부분 묶음이면 N석 결제 의도 · PG N석 금액) — 설계-4 부분 결과 거부 | fixed(루프 1) |
| R1-2 | 1 | codex | Adr018ConfigVariantTests.kt:137-175 | 채택 — 정합성 검증기 새 4필드가 0만 단언(항상 0 반환이어도 통과) → impl 쪽 fix verification 시험으로 위반 상태별 1 확인(blind 파일은 그대로) | fixed(루프 1) |
| R1-3 | 1 | codex | LoadtestDataService.kt:239 · 255 · 267-278 | 채택(low) — V16에서 옛 pay_multi_reservation_intents 집계를 돌리고 버림 | fixed(루프 1) |
| R1-4 | 1 | opus | HoldReleaseController.kt:16-22 · HoldReleaseService.kt:41 · 71 · 76 · V8__counter_trigger.sql:46 · 73 · HoldStrategyStartupCheck.kt:316-324 | 채택(계약 변경 — ADR 설계-3 · 설계-7 · test-contract §1: 기본 조합 밖 해제 2개 404) — 1석 해제가 기본 조합이 아닌 구성(counter-trigger · 카운터 없는 매수 방식 · redis · lazy 만료)에서도 열림 → 트리거 −1 + 앱 −1 이중 감소(G5 · G2) 또는 500 | fixed(루프 1) |
| R1-5 | 1 | opus | requirement-spec.md:39 G6 | 채택(문서) — 2026-11-19 합의(스키마 모습 단언 7곳 조정)가 명세 G6에 없음 | fixed(루프 1) |
| R1-OQ | 1 | opus · codex | — | open question: ① sweep(unnest 순) ∥ 묶음 확정 · 연장(id 순) 만료 경계 교착 가능성(재현 근거 없음) ② V16 뒤 앞 버전 바이너리 되돌림 경로 ③ 묶음 경로 지표 없음 ④ 묶음 확정에 측정 지연 주입 · converted_after_expiry 없음 ⑤ blind G3 1초 문턱 ⑥ PG 금액 불일치 503이 1석에도(설정 변경 시) ⑦ 결제 조회에 groupId · seat_count 없음 ⑧ source "hold-release"가 DriftSource 상수 목록에 없음 → ③④⑦은 2단계 측정 명세 입력(NEXT) · 나머지 기록 | open question |
| R2-1 | 2 | opus | HoldGroupPaymentStartService.kt:42 · SweepConditionalBatch.kt:58-65 | 채택 — 결제 시작 잠금 하위 질의에 만료 조건이 없어 만료된 묶음 행까지 id 순으로 잠금 → 배치(물리 순 잠금)와 40P01 → 500 · 배치 틱 롤백. 하위 질의에 expires_at > now | fixed(루프 2) |
| R2-2 | 2 | opus | HoldGroupConcurrencyTest.kt:30-105 · 명세 G5 | 채택(시험) — 해제 ∥ 확정(옛 경로) · 결제 시작 ∥ 배치 동시성 시험 없음 | fixed(루프 2) |
| R2-3 | 2 | codex · opus | adr018/Adr018HoldGroupScenarios.kt:215 | 채택(시험) — 벽시계 1초 단언은 GC · CI 부하로 거짓 실패(교착은 5xx 조건이 이미 잡음) → 5초로(메인이 직접 — fix verification, blind 계약 소급 오염 아님) | fixed(루프 2) |
| R2-4 | 2 | codex | adr018impl/HoldGroupConcurrencyTest.kt:49 | 채택(시험) — winners > 0 단언이 one-shot(SKIP LOCKED 둘 다 409 허용 — 설계-2)에서 거짓 실패 가능 → split 한정 | fixed(루프 2) |
| R2-5 | 2 | opus | HoldReleaseService.kt:54-56 · HoldReleaseUnsupportedTest.kt:234 | 채택 — releaseGroup의 미지원 검사는 도달 불가(enabled=true → conditional-extend → 기본 조합 강제) · 시험의 묶음 404는 컨트롤러 미등록 때문(항상 통과) → 검사 삭제 · 시험 줄 이름을 '꺼짐 404'로 | fixed(루프 2) |
| R2-6 | 2 | opus(open question 1) | HoldReleaseService.kt:40-42 · ExpireHoldsService.kt:48 | 채택 — confirm.mode=current에서는 JPA 배치 · 확정(좌석 → 홀드 순)이라 해제의 잠금 순서 논거(SweepConditionalBatch) 불성립 · 결제 중 해제 거부도 불성립 → 지원 조건에 confirm.mode ≠ current(계약 변경 — ADR 설계-3 · test-contract) | fixed(루프 2) |
| R2-7 | 2 | opus(open question 6) | expiry/Adr014ImplStartupTest.kt:179 | 채택 — 주석 '최대 버전 15' 낡음 | fixed(루프 2) |
| R2-OQ | 2 | opus | — | ② N17-1 DB 방어선 약화(부분 유니크 대안) — 설계-1 합의 · 2단계 전 재검토 후보(NEXT) ③ 1석 경로 새 실패 갈래(금액 불일치 503 · FOR KEY SHARE WAL) — 2단계 베이스라인 비교 때 기록 ④ 묶음 경로 지표 · 측정 이음새 없음 — 2단계 명세 ⑤ 묶음 끄기 전 남은 묶음 홀드 0 확인 절차 → ADR 설계-8에 한 줄(메인) | open question |
| R3-1 | 3 | opus | HoldReleaseService.kt:72-74 · 101-102 · PairRepair.kt:88-93 · SweepConditionalBatch.kt:45-52 · 58-65 | 채택 — 쿼터 행 없음(MISSING) 갈래에서 해제가 홀드 → 카운터 순서(sweep B2b는 카운터 → 홀드) → 만료 홀드 해제 ∥ 배치 40P01(해제 500 또는 틱 롤백, 자가 회복) | fixed(루프 3 — R3-2는 문서 · 주석, 남은 위험 기록) |
| R3-2 | 3 | opus | HoldGroupConfirmService.kt:73-81 · HoldGroupPaymentStartService.kt:18-20 · 44 · SweepConditionalBatch.kt:58-65 · ExpireHoldsService.kt:47 | 채택 — 확정 · 결제 시작의 now와 배치 틱의 now가 달라 만료 경계(now_c < E ≤ ts_s)에서 같은 묶음 행을 id 순 vs 물리 순으로 잠금 → 40P01(확정 500 → 안전망 환불, 자가 회복) · 주석 · 설계-5 문장이 실제보다 강함 | fixed(루프 3 — R3-2는 문서 · 주석, 남은 위험 기록) |
| R3-OQ | 3 | opus | — | ① 해제가 confirm ②pessimistic · ③optimistic에서도 열림 — 설계-3의 current 제외 이유(결제 시작 없음)가 ②③에도 해당 · 나머지 3조건은 ADR-016 기동 검사와 중복 ② 묶음 확정 거부 지표 없음 ③ 묶음 선점 409가 1석 거절 지표와 섞임 ④ 결제 조회의 묶음 표현 ⑤ 1석 금액 불일치 503 ⑥ 시험 스레드 풀 shutdown 없음(HoldGroupConcurrencyTest.kt:127) | open question |
| PF-1 | post-fix | codex | HoldGroupConfirmService.kt:31 · ADR 설계-5 · HoldGroupStartupCheck.kt:51 · CompensationSweep.kt:71 | 채택(문서 · 주석) — 남은 위험 서술이 compensation none을 빠뜨림 | fixed(post-fix) |
| PF-2 | post-fix | codex | adr018impl/HoldGroupConcurrencyTest.kt:169 | 채택 — 해제 ∥ 배치(MISSING) 시험이 잠금 단계 경합을 강제하지 않음 → 탭 필요, 이번 범위 밖 | user-deferred 후보(보고) |

## 생략한 검증

- (없음)
| 2026-11-19 18:5x | task 04 수정 회수(구현 워커 · Opus 384,492 토큰): 기존 시험 7개 스키마 모습 단언 V16 반영(IndexMigrationTest · Adr017PaymentStartIntentTest:101 · Flyway 목록 5곳) · HoldGroupConfirmService 확정 DELETE를 설계-5 꼴로 · 전체 실행(scratchpad 복사본 — 원 저장소 첫 실행은 다른 Gradle의 cleanTest와 충돌해 무효) main-app 1340/0(건너뜀 24) · mock-pg 24/0 · reconciler 6/0 · 앱 스모크 미수행 | 메인 단독 재실행 |
| 2026-11-19 19:0x | 듀얼 리뷰 루프 1 착수: BASE=ed3946a96(+미커밋) · OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/seat-rv1 · 미러 $OUT/mirror(k6 · .claude · ledger 제외, 369파일) · packet 보안 스캔 0건 · 미러 스캔: 실키 패턴 0 · 나머지(${DB_PASSWORD} 자리표시자 · 이미 추적되는 문서의 내부 IP)는 원격에 있는 기존 내용 · codex high ∥ Opus 워커 · 메인 전체 시험 동시 실행 중 | 리뷰 중 |
| 2026-11-19 20:0x | 메인 단독 전체 cleanTest test(원 저장소 · 리뷰 1 수정 전 코드 · 33분 17초): main-app 1340/0(건너뜀 24) · mock-pg 24/0 · reconciler 6/0 — 수정 뒤 재실행 예정 | green(수정 전) |
| 2026-11-19 20:3x | 리뷰 1 수정 회수(구현 워커 · 누적 417,302 토큰): R1-1 holdsOrReject에 남은 홀드 수 ≠ seat_count → split 409(재호출은 intent.seat_count) · 시험 3(첫 호출 · 진단 갈래 · 재호출 — PG 등록 수 /ledger) · R1-2 HoldGroupConsistencyJudgeTest 5(필드별 위반 → 그 필드만 1) · R1-3 V16이면 새 정의 집계 1회 · R1-4 HoldReleaseService.supported(sweep · pessimistic-nowait · counter-upsert-precheck) 아니면 404 · HoldReleaseUnsupportedTest(counter-trigger 해제 404 · 홀드 · 카운터 · 좌석 무변화) · 복사본 전체 main-app 1349/0(건너뜀 24) · mock-pg 24/0 · reconciler 6/0 | 메인 확인 → 스모크 |
| 2026-11-19 20:4x | 로컬 스모크(bootJar · 임시 postgres:16 127.0.0.1:25435 · mock-pg 18090 · 앱 18080 loadtest 프로필 · hold-group.enabled=true · refund): ① 묶음 2석(seatIds 역순 → holds seatId 오름차순) → 결제 시작 extended · 재호출 같은 uid · payBy → PG 등록 20000(2 × 10000) → pay → 확정 예약 2(같은 intent) · 재확정 같은 결과 · 카운터 2 ② 늦은 확정: 홀드 만료(DB 직접) → sweep 4초 → 확정 404 + compensation CLOSE → PG REFUNDED 20000 · 환불 1회 · 작업 DONE · 예약 0 · 카운터 0 ③ 1석 API로 묶음 홀드 해제 409 HOLD_IN_GROUP · 남의 묶음 403 · 묶음 해제 204 → 카운터 2→0 · 좌석 AVAILABLE · 1석 해제 204 → 카운터 0 · 결제 시작 뒤 묶음 해제 409 PAYMENT_STARTED · 1석 결제 시작 on 묶음 홀드 409 HOLD_IN_GROUP ④ /internal/consistency 위반 필드 모두 0(새 4필드 포함) · 앱 ERROR 0 · 정리(JVM 0 · 컨테이너 0) | 스모크 통과 |
| 2026-11-19 20:5x | application.yml 주석 '1석 해제 늘 열림' → R1-4 계약으로(메인 수정) · 수정 뒤 원 저장소 전체 시험 착수 · 듀얼 리뷰 루프 2 착수: OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/seat-rv2 · 미러 같은 방식 · _packet/loop1-fix-diff.md(로컬 경로 a/ · b/로 가림) · packet 스캔 0 · codex high ∥ 새 Opus 워커 | 리뷰 중 |
| 2026-11-19 21:1x | 리뷰 루프 2 회수: codex high(구현 위반 0 · 시험 F2) ∥ Opus 워커(307,765 토큰 · F4 low · open 6) → 채택 7(R2-1~7) · 기각 0 · 수정 전 원 저장소 전체 실행은 루프 2 수정 때문에 중단(그 실행에서 Adr012DefaultsContractTest 15건 IllegalStateException(기동 실패) — 클래스 단독 재실행 15/15 통과 → 환경 요인 추정, 최종 전체 실행에서 재확인) | 수정 중 |
| 2026-11-19 21:2x | 메인: R2-3 adr018/Adr018HoldGroupScenarios.kt:215 문턱 1초 → 5초(주석에 근거) · ADR 설계-3 지원 조합에 confirm.mode ≠ current와 이유 · 설계-8 묶음 끄기 전 남은 묶음 홀드 0 확인(R2-OQ⑤) · test-contract §1 · 구현 워커 재개(R2-1 · 2 · 4 · 5 · 6 · 7) | 수정 중 |
| 2026-11-19 21:5x | 리뷰 2 수정 회수(구현 워커 · 누적 ~430k 토큰): R2-1 잠금 하위 질의 expires_at > now · R2-2 결제 시작 ∥ 만료 배치 · 1석 해제 ∥ 1석 확정(옛 경로 — conditional-extend에도 있음, PaymentConfirmService.confirm) × 25 × split/one-shot · R2-4 expectWinner(split만) · R2-5 releaseGroup 검사 삭제 + 주석 · 시험 줄 삭제 · R2-6 supported에 confirm.mode ≠ CURRENT · HoldReleaseCurrentModeTest · R2-7 주석 · 복사본 전체 main-app 1354/0(건너뜀 24 · adr018impl 53 · adr018 49) · mock-pg 24/0 · reconciler 6/0 · 경계 사고: 구현 워커 grep 출력에 adr018/Adr018Support.kt:136-137(도우미 함수 정의 2줄 — 해제 경로)이 노출 — blind 시험은 이미 작성 완료 · 구현 변경 근거로 쓰지 않음 보고 → 영향 없음으로 판정, 기록만 | 메인 전체 실행 · 루프 3 |
| 2026-11-19 22:2x | 리뷰 루프 3 회수: codex high finding 0 · 렌즈 9 verified ∥ Opus 워커(287,632 토큰) low 2 · open 6 → 메인 실코드 확인: R3-1 HoldReleaseService.kt:72-74 · 101-102(쿼터 행 없으면 홀드 FOR UPDATE 뒤 repair INSERT) vs SweepConditionalBatch.kt:45-52(B2b repair → B3 DELETE) 순서 반대 확인 · R3-2 두 경로의 now가 다른 시각 → 경계 창에서 같은 행 집합 · 채택 2 → 루프 상한(3) 도달 · 종료 조건 불충족(신규 채택 2) → review unresolved, 사용자 결정 요청 | unresolved |
| 2026-11-19 22:3x | 사용자 결정 '작게 고치고 재점검 1회': R3-1 코드(해제 MISSING 갈래를 배치 B2b 순서로) · R3-2 코드 무변경 — 주석 · ADR 설계-5 남은 위험(만료 경계 40P01, 2단계 빈도 측정) · OQ① 해제 지원 = confirm.mode.paymentStartEnabled(conditional · conditional-extend — 처음엔 conditional-extend만으로 지시했다가 SeatConfirmProperties.kt:55 확인 뒤 정정) · OQ⑥ 시험 풀 shutdown → 구현 워커 재개 · 메인: ADR 설계-3 · 5 · test-contract §1 | 수정 중 |
| 2026-11-19 22:5x | 메인 원 저장소 전체(루프 2 수정 코드 — 컴파일은 루프 3 수정 전 · 32분): main-app 1354/0(건너뜀 24) · mock-pg 24/0 · reconciler 6/0 · 앞선 Adr012DefaultsContractTest 기동 실패 재발 없음(그때 실행 중단 · 동시 Gradle과 겹친 환경 요인으로 판정) | green(루프 2) |
| 2026-11-19 23:0x | 리뷰 3 수정 회수(구현 워커 · 누적 ~455k 토큰): R3-1 쿼터 잠금이 비면 홀드 FOR UPDATE 전에 repairMissing(1석 = 그 id · 묶음 = group id 순) · 잠근 집합 ≠ 복구 id면 404 롤백 · delete()의 복구 호출 제거 · 시험(쿼터 행 삭제 → 만료 1석 해제 ∥ 배치 × 25 × 2방식) 첫 실행에서 배치가 LazyExpiryBatch.MissingMismatch로 틱 롤백(해제가 먼저 지운 MISSING 쌍 — 기존 B3b 규칙, 확정과 겹칠 때와 같음) → 단언을 '배치 성공 또는 MissingMismatch(다음 틱 재실행) · 40P01 등은 실패'로 넓힘 → 메인 판정: 기존 설계 규칙이므로 수용 · ADR 설계-3에 한 문장 · R3-2 주석 2곳 · OQ① supported = mode.paymentStartEnabled + HoldReleasePessimisticModeTest · OQ⑥ @AfterEach shutdownNow · 복사본 전체 main-app 1357/0(건너뜀 24 · adr018impl 56 · adr018 49) · mock-pg 24/0 · reconciler 6/0 | post-fix 재점검 |
| 2026-11-19 23:1x | post-fix 재점검(codex high · OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/seat-pf): 항목 1~4 근거 확인 · 신규 2 → PF-1(주석 · ADR의 '안전망이 환불' 단정 — compensation none에서는 탐지만) 메인이 주석 1줄 · ADR 1문장 수정(코드 동작 무변경 — 실행 중인 최종 전체 시험은 컴파일 뒤라 주석 차이만) · PF-2(MISSING 해제 ∥ 배치 시험이 시작 장벽뿐이라 잠금 단계 경합을 강제하지 않음 — 회귀를 통계적으로만 잡음) → 시험용 동기화 지점(탭) 추가가 필요해 이번 범위 밖, 남은 위험으로 기록 · 사용자 보고 | 마감 판정 |
| 2026-11-19 23:5x | 최종 원 저장소 전체 cleanTest test(최종 코드 · 31분 15초): main-app 1357/0(건너뜀 24 · adr018impl 56 · adr018 49) · mock-pg 24/0 · reconciler 6/0 · Adr012 재발 없음 · ADR 낱말 치환 4(어긋남 → 불일치 · 진 쪽 → 실패한 요청 · 안전망 → 폴백 스캔) · NEXT N00d · 측정 로그 1행 · 리뷰 종료: 루프 3 상한 뒤 사용자 결정으로 작게 고침 + post-fix 1회(PF-1 fixed · PF-2 남은 위험) | 마감 |
| 2026-11-20 00:0x | 마감 보고 뒤 사용자 질문으로 확인: 스모크가 루프 1 수정 코드였고 최종 코드(b663e7de5)로는 안 돌렸음(보고 누락) → 최종 jar 재스모크: ① 정상 2석 · PG 20000 · 재호출 같은 uid · 확정 2 · 재확정 같음 · 카운터 2 ② 만료된 묶음 결제 시작 409 HOLD_EXPIRED(R2-1 경로) · 늦은 확정 404 + CLOSE → REFUNDED 20000 · 1회 · 카운터 0 ③ HOLD_IN_GROUP 409 · 남의 것 403 · 묶음 해제 204 · 카운터 0 · 좌석 AVAILABLE ④ 쿼터 행 삭제 뒤 1석 해제 204 · 카운터 행 복구 0 · 좌석 AVAILABLE · 홀드 0(R3-1 경로) ⑤ PAYMENT_STARTED 409 · 1석 결제 시작 HOLD_IN_GROUP 409 ⑥ 정합성 위반 0 · ERROR 0 · 정리 | 스모크 통과(최종) |

## 완료 요약 (2026-11-19)

- 1단계 범위: 연속 좌석 묶음 홀드 — V16 스키마 · 묶음 선점(split · one-shot) · 해제 API 2개 · 묶음 결제 시작 · 묶음 확정 · 정합성 4필드 · 기동 검사 · 기본 끔(`seat.hold-group.enabled=false`, `mode=split`).
- 검증: 최종 전체 main-app 1357/0 · mock-pg 24/0 · reconciler 6/0(blind 49 · 구현 56) · 로컬 스모크(정상 2석 · PG 20000 · 재호출 · 늦은 확정 환불 1회 · 해제 · 409 코드 · 정합성 0) · 설계 선검증 DV-1~7 · 듀얼 리뷰 3루프(R1 5 · R2 7 · R3 2) + 종합 감사 1 + post-fix 1(PF-1 fixed · PF-2 남은 위험).
- 남은 위험: 만료 경계 묶음 확정 · 결제 시작 ∥ 배치 40P01(ADR 설계-5 — 롤백 회복) · PF-2(MISSING 해제 ∥ 배치 시험은 잠금 단계 경합 비강제) · N17-1 DB 방어선 약화(설계-1 합의).
- 경계 사고 1: 구현 워커 grep 출력에 blind 도우미 2줄 노출(영향 없음 판정).
- CS 이슈 아카이브: study-note 쓰기 금지(사용자 규칙) → 보류(후보: '만료 시각 경계에서 앱 시계 now가 경로마다 달라 id 순 잠금 규약이 깨지는 교착').

핵심 diff (실파일 복사):

```sql
-- V16__hold_group.sql (새 파일)
CREATE TABLE hold_group (
    id          BIGSERIAL   PRIMARY KEY,
    schedule_id BIGINT      NOT NULL REFERENCES product_schedule (id),
    user_id     BIGINT      NOT NULL,
    seat_count  INT         NOT NULL CHECK (seat_count >= 1),
    created_at  TIMESTAMPTZ NOT NULL
);
ALTER TABLE seat_hold ADD COLUMN group_id BIGINT NULL REFERENCES hold_group (id);
ALTER TABLE payment_intent ADD COLUMN seat_count INT    NOT NULL DEFAULT 1;
ALTER TABLE reservation DROP CONSTRAINT reservation_intent_uk;
ALTER TABLE reservation ADD CONSTRAINT reservation_intent_seat_uk UNIQUE (intent_id, seat_id);
```

```kotlin
// PaymentIntents.kt — 1석 결제 의도 INSERT (before: 잠금 없음 → after)
               FOR KEY SHARE OF h
// HoldReleaseService.kt:42
    private val supported = confirmProperties.mode.paymentStartEnabled
```
