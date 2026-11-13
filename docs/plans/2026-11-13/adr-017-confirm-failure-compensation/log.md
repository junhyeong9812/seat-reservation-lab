# log — ADR-017 결제 성공 후 확정 실패 보상

> 작업 폴더의 두 번째 파일(명세서와 한 쌍). 메인이 단일 writer · 발생 시점에 append.

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 11-13 | 사전: ADR-016 PR #15 squash 병합 f8f52cc33(사용자 지시 '합치고') · main fast-forward · 브랜치 `feat/adr-017-confirm-failure-compensation`(로컬 · base f8f52cc33) | ADR-016 병합 기록은 이 브랜치 docs 커밋에서 NEXT · ADR-016 log에 반영 |
| 11-13 | 착수(사용자 '다음 ADR-017 시작하자'): 사실 확인 — ADR-017 문서는 가설 초안(38줄) · ADR-016 설계-4가 넘긴 것(거부 기록 표 `confirm_rejection` · 같은 paymentUid 재시도 멱등 · 판정기 '결제 uid 있는데 확정 없음' · 보상 방식 즉시 환불 · 대체 좌석 · ⓓ 승인→확정→매입) · 코드: 확정은 `paymentUid`를 받기만 함(reservation.payment_uid NOT NULL · 유니크 없음) · 결제 자체는 02 payment-consistency-lab 범위 | 인터뷰 |
| 11-13 | 인터뷰 1차 12문항(I1~I12) 사용자 답 — ADR-017 문서 §인터뷰에 기록 · 설계용 코드 사실 조사는 explorer 위임(Sonnet · 읽기 전용) | 설계 초안 |
| 11-13 | explorer 회수(Sonnet · 154,761 토큰 · 230초 · 7항목) — 핵심 2개 교차 확인(ConfirmOutcomeMetrics.kt:29-39 응답 기준 지표 · AtomicConfirmProcess.kt:67-100 C1/C2 거부 · 트랜잭션 전체 롤백) ✔ | 채택 |
| 11-13 | 설계 초안 작성(사용자 '설계 초안 써줘'): 설계-1~9 · 불변식 N17-1~9 · 열린 질문 Q1~Q9 | 사용자 검토 → 열린 질문 답 → 설계 선검증 + 듀얼 리뷰 |
| 11-13 | 사용자 답: Q1~Q9 모두 권장안 · '리뷰 돌려줘' → 설계 리뷰(설계 문서형 — 설계 선검증 N17-1~9 + 듀얼 1패스 · 상한 中 변형) 착수 | — |
| 11-13 | 리뷰 packet: base f8f52cc33 + 작업트리 · OUT = scratchpad/rv17d-lPKY · 미러 = scratchpad/mir17d-4SiC(k6/*/results · ledger · NEXT 제외 · 9.1M) · spec = ADR-017 §인터뷰 + §설계-9(사용자 결정) · 보안 스캔: 키 패턴 0 · password/token = 이미 push된 tracked 설정(값 미출력) · 사설 IP = 기존 하네스 문서 → 오탐 · codex(medium) ∥ Opus 워커 동시 시작 | 회수 대기 |
| 11-13 | 회수: codex(EXIT 0) C1~C9 + OQ 2 · 불변식 반례 N17-2·3·5·9 / Opus 워커(139,033 토큰 · 331초) F1~F13 + OQ 2 · 반례 N17-3·5·8 → 종합 RD17-1~13 + OQ 2 전부 채택 · 뿌리 = uid 직렬화 지점 없음 | 감사 |
| 11-13 | codex 종합 감사: 5건(RD17-9 N17-1 범위 · RD17-10 미결제 종결 뒤 늦은 결제 · RD17-4 잠금 순서 교착 · RD17-7 none 탐지 유지 · OQ1 재생 계약) → 5건 전부 채택 | 수정 |
| 11-13 | 사용자 결정(처방이 계약 변경이라 질문): I13 payment-start 필수화(서버 uid 발급 · PG 등록) · I14 보상 셀 거부 = 결제 후 확정 지연 주입 | ADR 설계-3~8 다시 씀 · 설계-10 · 인터뷰 I13 · I14 · Q1·3·4·8·9 주석 |
| 11-13 | post-fix 타깃 재점검(codex medium · 수정 diff 4e58c5de1..작업트리 + spec2) 시작 · 미러 갱신 | 회수 대기 |
| 11-13 | post-fix 재점검 회수(codex EXIT 0): 새 결함 3 — P1 미등록 uid CLOSE 결과 미정(높음) · P2 S 잠금 순서가 A′(쿼터 → 홀드 → 좌석 — SweepConditionalBatch.kt:43,58,87 메인 확인)와 반대 → 교착(높음) · P3 안전망 종결 intent 재생 코드 없음(중간) → 3건 채택 · 고침(中 변형 — 재점검 반복 없음 · 남은 위험은 구현 단계 동시성 테스트로 [구현 검증]) | 설계 리뷰 마감 · 미러 삭제 |

## 리뷰 ledger

### 설계 리뷰 (2026-11-13 · 설계 문서형 — 설계 선검증 + 듀얼 1패스 · 상한 中 변형)

- review packet: base f8f52cc33 + 작업트리 · OUT = scratchpad/rv17d-lPKY · 미러 = scratchpad/mir17d-4SiC

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| RD17-1 | 1 | codex C1 · opus F1 · F2 덧붙임 | ADR §4.2 · AtomicConfirmProcess.kt:73-80,214-237 | 채택 | fixed | 1 |
| RD17-2 | 1 | codex C2 · opus F2 | ADR §4.4 · PaymentStartService.kt:43-56 · SeatHoldController.kt:30-35 | 채택 | fixed | 1 |
| RD17-3 | 1 | codex C4 · opus F3 · OQ2 | ADR §3 · §8 · PayBy.kt:16-28 | 채택(사용자 I13 · I14) | fixed | 1 |
| RD17-4 | 1 | codex C3 · opus F12 · 감사 | ADR §5 · UserLimitStrategies.kt:279-331 · SweepConditionalBatch.kt:55-85 · SalesCloseGate.kt:197-211 | 채택 | fixed | 1 |
| RD17-5 | 1 | codex C5 · opus F13 | ADR §4.3 | 채택 | fixed | 1 |
| RD17-6 | 1 | codex C6 · opus F5 | V1__init.sql:24-41 | 채택 | fixed | 1 |
| RD17-7 | 1 | codex C7 · opus F7 · 감사 | ADR §9 Q1 · application.yml | 채택 | fixed | 1 |
| RD17-8 | 1 | codex C8 · opus F10 | ADR §8 | 채택 | fixed | 1 |
| RD17-9 | 1 | codex C9 · opus F8 · 감사 | V1__init.sql:33-40 · s3-full-flow.js:61 · LoadtestEndpointsTest.kt:171,200 · Adr015ApiContractTests.kt:197 | 채택 | fixed | 1 |
| RD17-10 | 1 | opus F4 · codex OQ2 · 감사 | ReservationController.kt:21-28 | 채택 | fixed | 1 |
| RD17-11 | 1 | opus F6 | ADR §3 · §4.3 · Q5 | 채택 | fixed | 1 |
| RD17-12 | 1 | opus F9 | LoadtestDataService.kt:46 · paid-flow.js:112 | 채택 | fixed | 1 |
| RD17-13 | 1 | opus F11 | ADR §3 | 채택 | fixed | 1 |
| RD17-OQ1 | 1 | codex OQ1 · 감사 | ApiExceptionHandler.kt:26-37 | 채택(계약 명시) | fixed | 1 |
| RD17-OQ2 | 1 | opus OQ1 | I11 | 채택(명시) | fixed | 1 |
| RD17-P1 | post-fix | codex | ADR §3 · §4.2 · §4.5 | 채택 | fixed | post-fix |
| RD17-P2 | post-fix | codex | ADR §5 · SweepConditionalBatch.kt:43,58,87 | 채택 | fixed(교착 0은 [구현 검증]) | post-fix |
| RD17-P3 | post-fix | codex | ADR §4.3 ⓒ · §4.5 | 채택 | fixed | post-fix |

## 생략한 검증

- (없음)

## 완료 요약

- (미완)
