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

## 리뷰 ledger

- (아직 없음)

## 생략한 검증

- (없음)

## 완료 요약

- (미완)
