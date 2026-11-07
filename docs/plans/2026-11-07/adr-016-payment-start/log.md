# log — ADR-016 2단계: ① payment-start · 결제 마감 시각 · 확정 거부 지표

> 명세: `requirement-spec.md` · 설계: `docs/adr/ADR-016-paid-seat-loss.md` 설계-9 · 1단계 log: `../adr-016-paid-seat-loss/log.md`

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 11-07 | 사용자 "응 구현 명세로 진행해줘" → 실코드 읽기(PaymentStart* · SeatConfirmProperties · HoldStrategyStartupCheck:205-300 · SeatHold/Reservation 컨트롤러 · ApiExceptionHandler · AtomicConfirmProcess:1-120 · Adr015RaceScenarios:215-245 · 옛 키 참조 전수 grep) | 설계-9.3 `M < θ`는 과강 — `M ≤ θ`로 충분(200 ⇒ 남은 > θ) · 기본 0 · 0 기동 불가 발견 |
| 11-07 | 인터뷰 4문: 범위 = 앱 코드 먼저 · θ=0 · M=0 · `M ≤ θ` · stakes 높음(루프 + blind) · auto | 명세 작성 · 합의 대기 |
| 11-07 | 사용자 "합의 · 진행하자" → set-state SPEC=1 · MODE=auto | task 01 착수 · blind 테스트 워커(task 05) 병렬 |

## 리뷰 ledger

| ID | 출처 | 위치 | 처리 | 상태 |
|---|---|---|---|---|

## 생략한 검증

- (없음)

## 완료 요약

- (미완)
