# log — ADR-017 1단계 구현(결제 의도 · 거부 기록 · 보상 · 흉내 PG)

> 메인 단일 writer · 발생 시점 append.

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 11-13 | 명세 초안(사용자 '응 구현 명세 써줘') — 범위 main-app + mock-pg + 테스트 · task 01~08 · stakes 높음 · 기본값 제안 · 설계 선검증은 설계 리뷰에서 완료(ADR-017 설계-10) — 새 호환 불변식 N17-10~12의 기존 강제 위치: 모드 분기 `ConfirmReservationService.kt:55-59` · payment-start 등록 조건 `PaymentStartController.kt:27,55-58` | 사용자 합의 대기 |
| 11-13 | 사용자 합의: 명세 그대로 · 기본값 제안대로 · auto → set-state SPEC=1 · MODE=auto | task 01 착수 |

## 리뷰 ledger

- (아직 없음)

## 생략한 검증

- (없음)

## 완료 요약

- (미완)
