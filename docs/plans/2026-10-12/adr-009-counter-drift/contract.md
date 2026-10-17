# ADR-009 계약 표면 (blind 테스트 워커용 — 이름·외부 동작만, 구현 방법은 담지 않음)

> 출처: 설계 v2(메인이 이름만 발췌). 테스트는 이 이름과 명세 §2·§5·§10·§11의 외부 동작으로만 쓴다. 이름이 구현에서 달라지면 구현 쪽이 이 계약에 맞춘다(구현 워커에 같은 계약을 준다).

## 설정 키 (`seat.hold.*`)
- `seat.hold.sales-close.enabled` (boolean, 기본 true) — 판매 마감 판정 켬/끔
- `seat.hold.counter-drift.expiry-repair` (boolean, 기본 false) — 후보 A
- `seat.hold.counter-drift.reconcile.enabled` (boolean, 기본 false) — 후보 C(앱 안 대조)
- `seat.hold.counter-drift.reconcile.interval` (Duration, 기본 60s)
- `seat.hold.counter-drift.reconcile.lock-timeout` (Duration, 기본 1s)
- `seat.hold.counter-drift.cleanup.mode` (`none` | `schedule` | `zero`, 기본 none) — 정리 ①(schedule)·②(zero)
- `seat.hold.expiry-scheduler-enabled` (boolean, 기본 true) — F 프로필에서 false
- 매수 방식 새 값: `seat.hold.limit-strategy: counter-trigger` (후보 B, 열거형 `UserLimitStrategyType.COUNTER_TRIGGER`)
- 프로필 `reconciler` — 후보 F(별도 프로세스, 같은 jar). 이 프로필은 대조만 하고 만료 스케줄러를 끈다.
- B 트리거 마이그레이션 위치: `classpath:db/migration-trigger`(선택 위치 — V4 `db/migration-unique`와 같은 방식, `spring.flyway.locations`에 추가할 때만 생김)

## 에러 코드
- `ErrorCode.SCHEDULE_CLOSED` → HTTP 409. 판정 순서: 마감 → 좌석 → 매수.

## 테이블 (V7 `db/migration`)
- `counter_drift_log`: 열 `id`, `at`(timestamptz), `source`(`expiry-repair` | `reconcile-app` | `reconcile-proc` 등), `schedule_id`, `user_id`, `kind`(`missing` | `below` | `above`), `cnt_before`(NULL 가능), `actual`, `action`(`repaired` | `false-positive` | `skipped`), `reason`(NULL 가능 — skipped의 SQLSTATE)
- `counter_reconcile_run`: 대조 한 바퀴 기록(시작·끝·청크 수·검사 쌍 수·발견·복구·오탐·skipped_pairs·skipped_schedules 등)
- `counter_cleanup_run`: `id`, `at`, `mode`, `schedule_id`(NULL 가능), `holds_seen`, `rows_deleted`, `waited_ms`, `stmt_ms`

## 지표 (Micrometer)
- `seat.hold.counter.drift` 태그 `source`·`kind`·`action`
- `seat.hold.expiry.tick` 태그 `outcome`(`ok` | `failed`)
- `seat.hold.counter.reconcile.borrows`, `seat.hold.counter.cleanup.borrows` — 커넥션 빌림마다 정확히 +1

## 판정기 응답 (`LoadtestDataService` consistency)
- counter 계열이면 `v_counter_mismatch`는 **열린 회차(starts_at > 앱 Clock now)만** 센다
- 새 필드 `counter_excluded_schedules`, `counter_excluded_pairs`

## 테스트 이음새
- `fun interface CounterRepairProbe { fun afterDetect(scheduleId: Long, userId: Long, observedCnt: Int?, observedActual: Int) }` — 대조가 어긋남을 찾은 뒤, 복구 트랜잭션 시작 전에 부른다. 빈이 없으면 아무것도 안 함. 테스트에서 빈으로 등록해 그 사이에 쓰기를 끼워 넣는다.

## 진입점(테스트에서 직접 부를 수 있게)
- 대조 한 바퀴: `CounterReconciler.runOnce(source: String)` (반환: 바퀴 요약)
- 정리 한 번: `QuotaCleanup.runOnce()` (반환: 정리 요약)
- 만료: 기존 `ExpireHoldsUseCase.expire()`
