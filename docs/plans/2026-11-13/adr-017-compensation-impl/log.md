# log — ADR-017 1단계 구현(결제 의도 · 거부 기록 · 보상 · 흉내 PG)

> 메인 단일 writer · 발생 시점 append.

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 11-13 | 명세 초안(사용자 '응 구현 명세 써줘') — 범위 main-app + mock-pg + 테스트 · task 01~08 · stakes 높음 · 기본값 제안 · 설계 선검증은 설계 리뷰에서 완료(ADR-017 설계-10) — 새 호환 불변식 N17-10~12의 기존 강제 위치: 모드 분기 `ConfirmReservationService.kt:55-59` · payment-start 등록 조건 `PaymentStartController.kt:27,55-58` | 사용자 합의 대기 |
| 11-13 | 사용자 합의: 명세 그대로 · 기본값 제안대로 · auto → set-state SPEC=1 · MODE=auto | task 01 착수 |
| 11-13 | blind 테스트 워커(Opus · worktree · 01·02 계약) 시작 ∥ 메인 task 01 구현: V15 · PaymentIntents · payment-start intent 생성(④ 재생 · E1 NOT EXISTS · ① 재판정 뒤 기존 intent) · 응답 paymentUid · 리셋 2곳(IntegrationTest · LoadtestDataService) · 기동 검사 V15(계획 · DB) | 영향 테스트 |
| 11-13 | 영향 테스트 1차: 259 · 실패 14 → 코드로 고칠 것(④ 재호출 지표 · ① 재판정) 고침 · 남은 것은 계약 변화 5건 → **명세 §4에 따라 멈추고 보고** → 사용자 승인: ① 응답 키 +paymentUid(Adr016Support.kt:66) ② Flyway 이력 +V15(FirstWindowPageTest:222 · Adr014ImplStartupTest:180) ③ N16-1 → intent 행만 씀(Adr016ConditionalPaymentStartTest) ④ ④ not_needed 뒤 연장 없음(Adr015ApiContractTests:107) ⑤ FM5 백엔드 찾기(Adr015Fm5LockWaitTest:63) | 고침 · 재실행 |
| 11-13 | blind 워커 회수(Opus · 167,251 토큰 · 773초): adr017/ 6파일(Support · PaymentStartIntent · ConditionalPaymentStart · ConfirmIntent · ConfirmConcurrency · CurrentMode) · 가정 9개(none 모드 compensation 필드 생략 허용 · payment_uid도 저장 · code = ErrorCode 이름 · 래치 자리 · GET 본문 state 등) · worktree HEAD가 f8f52cc33(명세는 git show로 읽음 — 영향 없음) → 레포로 복사 | task 01 클래스 재실행 |

| 11-13 | task 01 · 02 마무리: blind 정정 1(extended = '연장돼 있음' — ADR-016 N16-8 · already = true) · N16-1 대체 단언 정정 · 인덱스를 명세대로 (status, next_attempt_at) → adr017 33 테스트(동시성 7 포함) 통과 · 커밋 ca6f0e23b | task 03 |
| 11-13 | 부록 A(이름 · 키 · 연결 계약 · mock-pg 포트 8104 — reconciler-app이 8103) 커밋 b261fc668 · blind 워커(03~05) worktree 생성 실패 — **디스크 100%**(worktree 1개 6.6G — 추적 중인 k6 결과) → 끝난 워커 worktree 제거 · 사용자 승인으로 옛 worktree 2개 제거(여유 23G) · 이후 blind 워커는 같은 레포 · 새 파일만 · gradle 금지 | — |
| 11-13 | 구현: mock-pg 모듈(원장 · 주입 · API) · PgClient · PG 등록 · 워커 · 안전망 · 스케줄러 · 기동 검사 · DEAD retry API · D · S(SubstituteAllocator — 쿼터 옮김/upsert · A′ 순서) · 판정기 pay_* · runbook · ADR §구현 · 부록 B 커밋 3486460b1 · blind 워커(06 · 07) 시작 | — |
| 11-13 | blind 워커(03~05) 회수(Opus · 185,085 토큰 · 646초): mock-pg 계약 25(실행 24) · 앱 ↔ PG 21 · 가정 목록(시각 출처 · GET 본문 등) · 지적: mock-pg application.yml이 main-app 테스트 클래스패스와 겹칠 위험 → yml 삭제 · 포트는 Dockerfile 인자 / mock-pg GET 본문이 부록 A({payment, code})와 달랐음 → 고침(PgClient 함께) → mock-pg 24 · 앱 ↔ PG 21 통과(환불 수는 실제 PG 원장 단언 — 위장 아님) | D · S 워커 대기 |
| 11-13 | blind 워커(06 · 07) 회수(Opus · 192,967 토큰 · 668초): D 5 · S 6 · S 동시성 12판 · 기동 3 · 모호 4(409 길 원래 좌석 재선택 — 해 없음 · 매수 기동 검사 중복 — 결과 같음 · yml 가림 — 이미 삭제 · GET 형식) → 실행 15 · 실패 3: ① D authTtl 10분 < W 611초 — 결제창 승인이 연장보다 먼저 만료(기본값 결함 — 메인) → 30분(T + W ≤ 851초 위) ② 조회가 SUBSTITUTE 정보를 안 줌 → 재생과 같은 compensationView로 | 고침 |
| 11-13 | ADR-017 + mock-pg 93 테스트 통과(실패 0) → 코드 커밋 e6946a799 · 전체 테스트 시작 | 회수 대기 |
| 11-13 | 전체 테스트 17:42~18:52 — 70분 제한 시간 초과(124) · main-app:test에서 OutOfMemoryError 다수 → Hikari 연결 시간 초과 연쇄 · 실패 32(전부 OOM 뒤 — 단언 실패 아님). 원인: 테스트 JVM 기본 힙 512m + ADR-017의 새 컨텍스트 조합(캐시 ≤ 32) + 같은 JVM 흉내 PG → 계획 밖 파일(main-app/build.gradle.kts)이라 멈추고 보고 → 사용자 승인: `maxHeapSize = "2g"`(테스트 한정) | 전체 재실행 |
| 11-13 | 전체 재실행 21:25~21:46(21분 · OOM 없음): main-app 1235 · 실패 5 · 건너뜀 24 / mock-pg 24 · reconciler-app 6 통과. 실패 5 = 전부 V15 추가형의 기존 단언(Flyway 이력 3 · 인덱스 목록 1 · V6 예약 행 열 1) → 멈추고 보고 → 사용자 승인(권장안: 이력 +15 · reservation_intent_uk 추가 · V6은 V5 시점 열만 비교) → 5 클래스 재실행 통과 → 커밋 e2876b5ce | 스모크 · 리뷰 |
| 11-13 | 사용자 '스모크랑 리뷰 진행해줘' → 리뷰 루프 1 packet: BASE 7dc19d30e(명세 커밋) · HEAD e2876b5ce + 작업트리 · OUT=scratchpad/review1(packet · mirror · results) · 제외: 모든 log.md · measurement-log · NEXT · k6/**/results(무관한 기존 측정 산출물 — 미러 9.9M) · untracked 0 · 보안 스캔 매칭 = 오탐(코드 변수 · 테스트 컨테이너 계정)만 · 내부 IP 0 → Opus 워커 ∥ codex(medium) 병렬 시작 | 스모크 병행 |
| 11-13 | 스모크(scratchpad/smoke/compose.yml — 앱 · DB · mock-pg 이미지 빌드 · refund · TTL 40s): 1차는 기본 ④ W 611초 연장으로 만료가 안 와 지연 거부 불가 → payment-window 20s로 재기동 · 2차: 성공 경로 200 · intent CONFIRMED · 예약 intent_id 채움 · PG CAPTURED / 지연 거부: pay(CAPTURED) → 41초 대기 → 확정 404 HOLD_NOT_FOUND + compensation CLOSE(안전망이 먼저 닫음 — closed_reason SWEEP · 재생 코드) · 재요청 같은 본문 재생 · 워커 CLOSE DONE(attempts 1 · REFUNDED) · /ledger refunds 1 · ops close 1 · 남의 uid 조회 404 | 통과(확정 거부 기록 출처 REJECTION 경로는 안전망이 먼저 닫아 스모크에서 못 봄 — 테스트가 덮음) |
| 11-13 | 리뷰 루프 1 회수: Opus(204,081 토큰 · 499초) F1~F10 + OQ 7 · codex F1~F2 + OQ 2 → 메인 종합(아래 ledger) → 채택 10 수정 + 회귀 테스트 5(Adr017BeforeV15Test — 수정 전 재현 500 확인 · Adr017ReviewFixTest 4) → ADR-017 + mock-pg 98 통과 | codex 종합 감사 |
| 11-13 | codex 종합 감사(medium): 판정 오류 후보 3(R12 · R3 · OQ4) · 미해소 2(R7 · R11) → R7 · R11 수정(회수 DEAD · capture 종결 = 409 EXPIRED · CLOSED만) + 테스트 2 · R12 근거 정정 후 기각 유지 · R3 미해결 명시 → ADR-017 + mock-pg 100 통과 | 리뷰 루프 2 |
| 11-13 | 리뷰 루프 2 packet: BASE 7dc19d30e · 작업트리(루프 1 수정 미커밋) + untracked 2(Adr017BeforeV15Test · Adr017ReviewFixTest) · OUT=scratchpad/review2 · packet-loop-fix.md(참고) · 보안 스캔 매칭 1 = 오탐(ADR-007 'disk-measurement') · 내부 IP 0 → Opus ∥ codex 시작 | 회수 대기 |
| 11-13 | 리뷰 루프 2 회수: Opus(213,976 토큰 · 515초) medium 4 · low 5 · OQ 7 / codex medium 1 · low 1 · OQ 2 → 신규 채택 10(R15~R24 — 문서 4 포함) · R15는 R11 재수정(같은 곳 2회 — 명세 '등 409' 원문으로 되돌림, 사용자 보고) · 테스트 갱신(REGISTERED → FAILED_REGISTERED) → ADR-015 · 016 · 017 · loadtest · mock-pg 351 통과 | 리뷰 루프 3 |
| 11-13 | 리뷰 루프 3 packet(OUT=scratchpad/review3 · 스캔 오탐 1 동일) → Opus ∥ codex 시작 ∥ 스모크 3차(수정 코드 이미지 재빌드 · 스모크 DB 볼륨 새로 — V15 변경): 성공 200 / 만료 0.3초 뒤 확정 → 409 HOLD_EXPIRED + CLOSE PENDING · intent REJECTED · confirm_rejection 1 · 작업 source REJECTION → DONE REFUNDED(attempts 1) · /ledger refunds 1 · 판정기 v_* · pay_* 위반 0 · pay_compensation_mode refund | 통과 |
| 11-13 | 리뷰 루프 3 회수: Opus(234,365 토큰 · 541초) medium 1(= R3) · low 4 · Q 4 / codex high 1(→ low 정정) · medium 1(기각) · OQ 1 → 신규 채택 4(R27 · R28 코드 · R29 · R31 문서) + R30 마감 때 → ADR-017 + mock-pg 100 통과 · **루프 상한 3 도달 + 신규 채택 있음 + R3 사용자 결정 대기 → review unresolved** | 사용자 보고 · 전체 테스트 |
| 11-13 | 전체 테스트(루프 3 수정 뒤) 22:52~23:13: main-app 1242 · 실패 0 · 건너뜀 24 / mock-pg 24 · reconciler-app 6 | 통과 |
| 11-13 | 사용자 결정(AskUserQuestion): R3 = '409로 막기(권장)' · 리뷰 마감 = '수정분 재점검 1회(권장)' → R3 구현(내 홀드 · intent 있음 · 다른 uid → 409 SEAT_NOT_HELD · uid_conflict 지표) · blind 테스트 N17-10 1개를 새 계약으로 | 전체 테스트 · 재점검 |
| 11-13 | codex 수정분 재점검(OUT=scratchpad/recheck · fix-diff 13KB · 스캔 0): high 2 → R34 채택 수정 · R35 low 정정(문서) | — |
| 11-14 | 전체 테스트(R3 뒤): main-app 실패 5 — 기존 ADR-015 · 016 테스트가 payment-start 뒤 `pay-<user>` uid로 확정(새 계약에서 409) → **명세 §4에 따라 멈추고 보고** → 사용자 승인 '인자만 바꾸고 테스트 돌린 뒤 커밋': 5개 확정 호출 인자만 발급 paymentUid로(단언 무변경 — Adr015ApiContractTests · Adr015RaceScenarios FM5 · Adr015Fm5LockWaitTest · Adr016ConditionalPaymentStartTest N16-2·4) · ADR §구현 옛 하네스 주의 | 전체 재실행 |
| 11-14 | 전체 재실행 1차는 새 테스트 컴파일 오류(dataSource가 상위 클래스 필드와 겹침 — 5초 실패 · 결과 파일은 옛 것)를 고침 → 2차 00:30~00:52: main-app 1243 · 실패 0 · 건너뜀 24 / mock-pg 24 · reconciler-app 6 → 코드 커밋 6c0d91d79(docs 제외) | 스모크 4차 |
| 11-14 | 스모크 4차(커밋 이미지 · 볼륨 새로): 다른 uid 확정 409 SEAT_NOT_HELD → 발급 uid 확정 200 / 만료 0.3초 뒤 확정 409 HOLD_EXPIRED + CLOSE(REJECTION) → DONE REFUNDED · refunds 1 · 판정기 v_* · pay_* 위반 0 → 스모크 스택 내림(down -v — 이 작업이 만든 adr017smoke만) | 리뷰 마감 |
| 11-14 | 리뷰 마감: 루프 3 + 사용자 선택 '수정분 재점검 1회' 완료 — 채택 finding 전부 fixed(R3 사용자 결정 반영) · 재점검 신규 2(R34 수정 · R35 문서) · open 0 | docs 커밋 · 마감 |
| 11-14 | 사이클 마감(사용자 '1단계 마감부터 진행해줘'): ADR 상태 줄 · §구현 커밋 SHA · NEXT N00c · 측정로그 1행 · 완료 요약(Opus 워커 97,563 토큰 · 94초 — 스니펫 실파일 복사 · 메인이 PaymentConfirmService:45-56 대조) · 이슈 아카이브 보류(study-note는 이 프로젝트 금지 영역 — issue-archive 보류 경로 · CS 이슈 후보: 아웃박스 리스 fencing · FK 묵시 FOR KEY SHARE · SKIP LOCKED 후보 기아) | docs 커밋 |

## 리뷰 ledger

| id | loop | source | 근거 | disposition | status | fixed_in |
|----|------|--------|------|-------------|--------|----------|
| R1 | 1 | opus F1 · codex F1 | PaymentConfirmService.kt:44 · CompensationSweep.kt:33 · PaymentController.kt:29 — V15 앞 스키마(current + target 7, 기동 허용)에서 확정 · 조회 500 · 안전망 ERROR | 채택(재현 테스트 500 확인) | fixed — PaymentIntents.available(to_regclass 1회) · 없으면 옛 경로 · 안전망 0 | 1 |
| R2 | 1 | opus F2 | CompensationSweep.kt:31-56 — none이 같은 가장 오래된 batch건을 매 주기 FOR UPDATE · 201번째부터 탐지 안 됨 · 로그 없음 | 채택 | fixed — none = 잠금 없는 count 1문장 · 카운터 += n · WARN 로그(카운터 의미 KDoc 명시) | 1 |
| R3 | 1 | opus F3 | PaymentConfirmService.kt:44 — intent 있는 홀드를 다른 uid로 확정 → 옛 경로 → 안전망이 intent CLOSE → 좌석 + 환불 | open question → 사용자 결정(N17-10 테스트 'payment-start 뒤 자기 uid = 옛 경로 · intent OPEN'과 충돌) | fixed — 사용자 결정 2026-11-13 '409로 막기': 내 홀드에 intent가 있으면 다른 uid 확정 409 SEAT_NOT_HELD · 기록 없음 · blind 테스트 1개 계약 갱신 | 재점검 |
| R4 | 1 | opus F4 | PaymentConfirmService.kt:57-63 · SubstituteAllocator.kt:38-44 — S의 DB 오류 500 무기록 · 마감 판정 catch가 DB 오류를 schedule-closed로 | 채택 | fixed — DB 오류는 ERROR 로그 + CLOSE로 · 마감 catch는 SeatReservationException만 | 1 |
| R5 | 1 | opus F5 | SubstituteAllocator.kt:39 — sales-close 끔이면 S가 마감 판정 없이 대체 | 채택 | fixed — substitute ⇒ sales-close.enabled 기동 검사 | 1 |
| R6 | 1 | opus F6 | CompensationWorker.kt:73-102 — 배치 처리 시간 상한 > 리스 → 다른 워커와 겹쳐 PG 재호출 | 채택 | fixed — PG 시간 상한(2 × (connect + read))이 리스 안에 안 남으면 부르지 않고 놓아줌 · 기동 검사 lease > 상한 | 1 |
| R7 | 1 | opus F7 · 감사 | CompensationWorker.kt:36-38 — 리스 회수가 attempts를 안 올려 반복 죽음이 DEAD 안 됨 · (감사) +1만 해도 DEAD 판정은 retry()에만 있어 미해소 | 채택 | fixed — 회수 = attempts +1 · UNKNOWN · 상한이면 회수 자리에서 DEAD(지표 · ERROR) | 1 |
| R8 | 1 | opus F8 | CompensationAdminController.kt:16-19 · ops/compensation-dead/README.md:42 — runbook이 노출 범위를 틀리게 적음 | 채택(문서) | fixed — runbook 문구 | 1 |
| R9 | 1 | opus F9 | PaymentStartService.kt:77-80 — readOnly 해제 뒤 낡은 KDoc | 채택 | fixed | 1 |
| R10 | 1 | opus F10 | PaymentConfirmService.kt:48-71 — 닫힌 intent의 거부 재생이 재잠금 · S 트랜잭션을 또 돎(G3 비용 부풀림) | 채택 | fixed — Replayed로 갈라 곧바로 응답 | 1 |
| R11 | 1 | codex F2 · 감사(opus OQ4 재검토) | CompensationWorker.kt:105 — CAPTURE 모든 4xx를 종결-실패로 · (감사) 409 REGISTERED(미승인 — 아직 결제 가능)도 종결로 묶음 | 채택 | fixed — 409 EXPIRED · CLOSED만 DONE FAILED(ADR-017:109) · 그 밖 재시도 → 상한 DEAD | 1 |
| R12 | 1 | codex OQ · 감사 | SubstituteAllocator.kt:48-64 — 다른 빈 좌석이 없어도 원래 좌석을 되돌려 다시 고름 · (감사) 부록 B '빈 좌석 없으면 CLOSE'와 충돌? | 기각 유지 — 근거 정정: ADR-017:213 설계-5 S 순서 4가 '원래 홀드를 지우고 원래 좌석을 되돌린 뒤' 5에서 같은 section 빈 좌석을 고른다 → 되돌린 원래 좌석도 빈 좌석(설계가 정한 순서) · 사용자 보고 | — | — |
| R13 | 1 | opus OQ1 · OQ4 · OQ5 · OQ2 | 닫힌 intent payment-start 재호출 · D 미승인 확정 · 모드 변경 잔여 작업 · 옛 경로 추가 SELECT | 범위 밖(설계상 none = 보상 없음 · Q5 돈 없는 좌석 집계 · 측정 2단계) — 사용자 보고 | — | — |
| R3′ | 1 | 감사 | R3 — 결정 대기 중에도 N17-5(좌석 + 환불 공존) 위험이 남음을 미해결로 명시하라 | 채택(표기) | fixed — R3 사용자 결정 반영 | 재점검 |
| R14 | 1 | opus OQ7 · codex OQ | 기존 테스트 단언 변경 | 기각 — 사용자 승인 2회(log 11-13 행: 계약 변화 5건 · V15 이력 5건) | — | — |
| R15 | 2 | opus F1 | CompensationWorker.kt:125-128 — 루프 1 감사 반영(409 EXPIRED · CLOSED만 종결)이 명세 부록 B '승인 만료(EXPIRED) **등 409** → DONE FAILED_'와 어긋남 · REGISTERED가 DEAD로 G1 'DEAD 0'을 깸 | 채택 — 명세 원문 우선(판정 기준) · **같은 곳 2회 수정(R11) → 사용자 보고** | fixed — 409 전부 종결-실패 · 409 밖 4xx만 재시도 | 2 |
| R16 | 2 | opus F2 | = R3(같은 홀드 · 다른 uid) + none에서 OPEN intent 누적 오탐 | R3에 병합 | fixed — R3 | 재점검 |
| R17 | 2 | opus F3 | CompensationSweep.kt:49-62 — none 탐지 비용 선형 증가 · 5초마다 같은 WARN · 카운터 재집계 | 부분 채택 | fixed — WARN은 수가 바뀔 때만 · 카운터 의미 · 비용 증가는 ADR §구현에 명시(Q8 정리 없음의 결과) | 2 |
| R18 | 2 | opus F4 | V15:8-9 — schedule_id · seat_id FK가 intent INSERT에 회차 · 좌석 FOR KEY SHARE(N16-1 잠금 0 깨짐 · NOWAIT 원인 표지 변화) | 채택(V15는 미병합 — V1~V14 아님) | fixed — 두 FK 제거 · 주석 | 2 |
| R19 | 2 | opus F5 | PaymentStartService 지표가 PG 등록 실패(503) 전에 커밋 뒤 셈 | 채택(문서) | fixed — ADR §구현에 대조 규칙(pg.register{result}로 뺀다) | 2 |
| R20 | 2 | opus F6 | ops runbook에 설계-4.4 '수동 종결 기록' 경로 없음 | 채택 | fixed — DEAD 행만 DONE · MANUAL_<사유> SQL | 2 |
| R21 | 2 | opus F7 | A2 창에서 안전망 코드 재생(첫 거부 코드와 다를 수 있음) | 채택(문서) | fixed — ADR §구현 명시(이중 결과 0) | 2 |
| R22 | 2 | opus F8 | 옛 경로 추가 SELECT · 커넥션 2회 | 채택(문서) | fixed — ADR §구현 | 2 |
| R23 | 2 | opus F9 · codex F2 | CompensationScheduling — 음수 interval · backoff-max 0 통과 | 채택 | fixed — interval > 0 · backoff-max > 0 | 2 |
| R24 | 2 | codex F1 · (opus 루프 1 OQ3) | LoadtestDataService.kt:238 — pay_rejected_without_task가 none에서 구조적 위반(명세 §1-9 '모드 ≠ none') | 채택(루프 1 '범위 밖' 판정 정정) | fixed — none이면 항목 뺌 · pay_compensation_mode 추가 | 2 |
| R25 | 2 | codex OQ · opus OQ6 · OQ7 | 원래 좌석 재선택(=R12) · retry API 노출 · 스케줄러 시작 시각 앱 Clock | 기각 — R12 근거 동일 · 노출은 runbook 명시(R8) · 운영 Clock = 시스템 시계(테스트는 스케줄러 끔) | — | — |
| R26 | 3 | opus F1 | = R3(세 번째 — 같은 홀드 · 다른 uid → 좌석 + 환불, 판정기 무음) | R3에 병합 | fixed — R3 | 재점검 |
| R27 | 3 | opus F2 | CompensationSweep — 후보마다 FOR UPDATE 대기(확정 중 intent가 주기 전체를 멈춤) · 한 건 예외가 나머지 건너뜀 | 채택 | fixed — tryLock(SKIP LOCKED) · 건별 try/catch + sweep_failed 지표 · ERROR | 3 |
| R28 | 3 | opus F3 | PgRegistration — 이미 REFUNDED · VOIDED uid가 멱등 200으로 오면 payment-start 200(닫힌 uid) | 채택 | fixed — 닫힌 상태면 409 HOLD_EXPIRED(재현 테스트 없음 — 홀드가 살아 있는 거부 뒤 환불 완료 모양을 만들기 어려워 생략 · 코드 1분기) | 3 |
| R29 | 3 | opus F4 | mock-pg /admin/reset이 주입 설정도 비움 | 채택(문서) — 코드 유지(기존 테스트 BeforeEach가 이 동작에 기댐) | fixed — ADR §구현에 2단계 하네스 주의 | 3 |
| R30 | 3 | opus F5 · (루프 1 OQ6) | ADR §구현 커밋 SHA · 상태 줄 | 채택 | 마감 docs 커밋에서 기입 | — |
| R31 | 3 | codex F1(high) | PaymentStartService.kt:51 — ④ 동시 첫 호출에서 미커밋 intent를 못 본 E1이 연장 | 부분 채택 — 심각도 low로 정정: 어긋남은 pay_by < 만료(안전 방향)뿐 · RD17-2 위험 방향(마감 > 만료) 불가(intent pay_by는 먼저 쓴 쪽 값 · 연장은 만료를 늘리기만) | fixed — ADR §구현에 경합 결과 명시(코드 무변경) | 3 |
| R32 | 3 | codex F2 | LoadtestDataService.kt:234 — 판정기 DB now() vs 안전망 앱 Clock | 기각 — 판정기 활성 정의는 DB now()가 기존 단일 규칙(ADR-014 §8.3 · expiryStates와 같다) · 랩은 앱 · DB 같은 호스트 시계 | — | — |
| R33 | 3 | opus Q2 · Q3 · Q4 · codex OQ | ① 재호출 θ 재판정 · D REGISTERED 종결(= R15 해석) · N17-7 배치 한도 · 원래 좌석 재선택(= R12) | 사용자 보고(Q4는 ADR §구현에 명시) | — | — |
| R34 | 재점검 | codex | CompensationSweep.kt:37 — 잠기거나 계속 실패하는 맨 앞 후보가 매 주기 LIMIT 자리를 차지(뒤 후보 기아) | 채택 | fixed — uid 순 순회 위치 · 모자라면 처음부터 채움 + 테스트(Adr017ReviewFixSweepTest — batch 1 · 앞 후보 잠금) | 재점검 |
| R35 | 재점검 | codex(high) | PaymentConfirmService.kt:46 — R3 검사(findByHold)와 옛 확정이 원자적이지 않음(같은 홀드 payment-start 커밋과 다른 uid 확정 동시) | 부분 채택 — 심각도 low: 클라이언트가 자기 요청끼리 경합할 때만 · 발급 uid 결제는 payment-start 응답 뒤라 정상 흐름에 없음 | fixed — ADR §구현에 남는 틈 명시(코드 무변경) | 재점검 |


## 생략한 검증

- (없음)

## 완료 요약

- **무엇**: payment-start가 결제 uid를 발급하고(I13) 서버가 `payment_intent`(V15) 행으로 쥔다 — 확정 · 거부 기록 · 안전망 · 대체가 모두 이 행을 `FOR UPDATE`로 먼저 잡아 결과 하나(멱등 재생). 거부는 `confirm_rejection` + CLOSE 작업, 보상은 아웃박스 + 워커(리스 · fencing · DEAD) · 안전망이 흉내 PG(`mock-pg` 8104)를 부른다. R · D · S는 `seat.compensation.mode`(기본 none — PG 호출 0 · N17-12). 판정기 `pay_*` · DEAD runbook. 65파일 +5674/−55.
- **검증**: 전체 main-app 1243/0/24 · mock-pg 24 · reconciler-app 6 · 로컬 compose 스모크 4회(마지막 = 커밋 이미지: 다른 uid 409 → 발급 uid 200 · 만료 직후 확정 409 + CLOSE → REFUNDED · refunds 1 · 판정기 위반 0) · 듀얼 리뷰 3루프 + 수정분 재점검(R1~R35 · open 0) · blind 테스트 워커 3.
- **사용자 결정 · 승인**: R3(내 홀드에 intent가 있으면 다른 uid 확정 409) · 리뷰 마감 = 수정분 재점검 1회 · 명세 §4 멈춤 3회(계약 변화 5 · V15 이력 단언 5 · 확정 인자 5) · 테스트 힙 2g.
- **커밋**: ca6f0e23b → e6946a799 → e2876b5ce → 6c0d91d79(코드) · b261fc668 · 3486460b1(명세 부록) · 이 마감 docs 커밋.

### 핵심 diff (실파일 — 7dc19d30e → 6c0d91d79)

1. 확정 진입을 facade로 — `main-app/src/main/kotlin/com/jun/labs/seatreservation/api/ReservationController.kt`(before 27~34 → after 32~41)

```kotlin
// before
        val result = confirmReservationUseCase.confirm(
            ConfirmReservationCommand(holdId = holdId, userId = userId, paymentUid = request.paymentUid),
        )
        return ConfirmReservationResponse(
            reservationId = result.reservationId,
            seatId = result.seatId,
            status = result.status,
        )
// after
        val response = paymentConfirm.confirm(
            ConfirmReservationCommand(holdId = holdId, userId = userId, paymentUid = request.paymentUid),
        )
        val result = response.result
        return ConfirmReservationResponse(
            reservationId = result.reservationId,
            seatId = result.seatId,
            status = result.status,
            compensation = response.compensation,
        )
```

2. payment-start — 재생 먼저 · E1 연장은 intent가 없을 때만 · 첫 200에서 intent — `main-app/src/main/kotlin/com/jun/labs/seatreservation/service/impl/PaymentStartService.kt`(before 57~59 → after 47 · 59 · 66~68 · 114~117)

```kotlin
// before
        if (extendedExpiresAt != null) {
            countAfterCommit(RESULT_EXTENDED)
            return PaymentStartResult(holdId, extendedExpiresAt, extended = true)
// after
        replay(holdId, userId, now)?.let { return it }
               AND NOT EXISTS (SELECT 1 FROM payment_intent i WHERE i.hold_id = seat_hold.id)
        if (extendedExpiresAt != null) {
            countAfterCommit(RESULT_EXTENDED)
            return open(holdId, userId, extendedExpiresAt, extended = true, now)
    private fun open(holdId: Long, userId: Long, expiresAt: Instant, extended: Boolean, now: Instant): PaymentStartResult {
        val intent = intents.openForHold(holdId, userId, expiresAt.minus(confirmProperties.payByMargin), now)
            ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        return PaymentStartResult(holdId, expiresAt, extended, paymentUid = intent.uid, payBy = intent.payBy)
```

3. 확정 분기 — 옛 경로 · 다른 uid 409(R3) — `main-app/src/main/kotlin/com/jun/labs/seatreservation/service/impl/payment/PaymentConfirmService.kt:45-56`(새 파일)

```kotlin
    fun confirm(command: ConfirmReservationCommand): ConfirmResponse {
        if (intents.find(command.paymentUid) == null) {
            // 남의 홀드면 옛 경로가 지금처럼 403으로 거부한다(진단 그대로) — 내 홀드에 intent가 있을 때만 막는다
            if (intents.findByHold(command.holdId)?.userId == command.userId) {
                meters.counter(UID_CONFLICT_METRIC).increment()
                throw SeatReservationException(ErrorCode.SEAT_NOT_HELD)
            }
            meters.counter(LEGACY_METRIC).increment()
            return ConfirmResponse(confirmUseCase.confirm(command), compensation = null)
        }
        val rejected = try {
            return tx.execute { confirmIntent(command) }!!
```

4. 워커 응답 분류 — `main-app/src/main/kotlin/com/jun/labs/seatreservation/service/impl/payment/CompensationWorker.kt:125-131`(새 파일)

```kotlin
        is Call.Rejected -> when (type) {
            CompensationTasks.Type.CAPTURE ->
                // 409 = 처리된 거부(승인 만료 EXPIRED · 취소 CLOSED · 미승인 REGISTERED 등) — 종결-실패(명세 부록 B '등 409' · 돈 없는 좌석)
                if (call.status == 409) Outcome.Done("FAILED_${call.code ?: call.status}", moneyless = true)
                else Outcome.Retry("REJECTED_${call.code ?: call.status}") // 409 밖 4xx(미등록 404 등 원장 불일치) — 재시도 · 상한에서 DEAD로 드러낸다
            CompensationTasks.Type.CLOSE -> Outcome.Retry("REJECTED_${call.code ?: call.status}")
        }
```

5. V15 — intent당 예약 ≤ 1(N17-1) — `main-app/src/main/resources/db/migration/V15__payment_intent_compensation.sql:57-60`(새 파일)

```sql
ALTER TABLE reservation ADD COLUMN intent_id VARCHAR(64) NULL REFERENCES payment_intent (uid);
ALTER TABLE reservation ADD CONSTRAINT reservation_intent_uk UNIQUE (intent_id);
-- 대체 좌석 표시(설계-5 S). 상수 기본값 추가는 PostgreSQL 11+에서 표를 다시 쓰지 않는다(카탈로그만)
ALTER TABLE reservation ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'HOLD';
```

- **바뀐 외부 계약 · 남은 틈**: ADR-017 §구현(바뀐 계약 · 구현에서 정한 세부)이 정본 — paymentUid 필수 · N16-1 대체 · intent 뒤 연장 없음 · Flyway 15 · 다른 uid 409 / R35 경합 · R31 pay_by 이른 쪽 · A2 재생 코드 · 옛 경로 조회 1문장 · none 비용 증가 · N17-7 배치 한도.
- **2단계로**: `k6/ADR-017/` 하네스 · 측정 전 고정(주입 비율 · 셀 · G1~G3 · 워커 · 안전망 손잡이) · .164 배포 스모크 · 캠페인(≤ 12시간) · ADR §실험 결과 · §결정.
