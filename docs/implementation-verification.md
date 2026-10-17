# 구현 검증 대장 ([구현 검증] 이연 항목)

> 설계 문서에서 "구현해 봐야 판정할 수 있다"고 미룬 항목을 한곳에 모은다. 각 항목은 해소되면 결과와 근거(커밋·결과 폴더)를 적고 상태를 바꾼다. 무음 이연을 막기 위한 대장이다.

| id | 출처 | 항목 | 해소 시점 | 상태 |
|----|------|------|----------|------|
| IV-009-1 | ADR-009 설계 v2 §3b (`docs/plans/2026-10-12/adr-009-counter-drift/`) | 실제 Hibernate에서 orphanRemoval DELETE의 행 수 확인(StaleState)이 확정↔만료 경합의 정합(확정 예약 + 좌석 AVAILABLE 방지)을 지키는가 | ADR-016(확정 원자성) | 열림 |
| IV-009-2 | 〃 §5 | 판매 마감 판정의 회차 행 `FOR SHARE`가 S4c4200(고정 도착률 4,200건/s)·S3-a20(실제 흐름, 이탈 20%)에서 주는 처리량·p99 비용(회차 하나에 몰리는 구조 — 마이크로벤치 0~40%로 열어 둠) | ADR-009 측정(마감 끔 대조 세트) | 열림 |
| IV-009-3 | 〃 §4 | 부하 중 대조(C·F) 한 바퀴 시간과 청크 최대(부하 없는 상태 약 15초·33ms) | ADR-009 측정 | 열림 |
| IV-009-4 | 〃 §6 | 정리 ①의 회차 `EXISTS seat_hold` 확인 시간과 회차 행 배타 잠금 보유 시간 | ADR-009 측정(마감 임박 셀 S3c-a20) | 열림 |
| IV-009-5 | 〃 §1a | A 실패 시 skipped를 독립 트랜잭션으로 기록하는 앱 경로 | ADR-009 구현·테스트 | **해소** — `ExpiryRepairImplTest`(틱 롤백 → 독립 트랜잭션 skipped reason P0001 → 다음 틱 복구), 커밋 b3157ab5·63ebe0f4 |
| IV-009-6 | 〃 §4 | 대조·정리 빌림 지표(`reconcile.borrows`·`cleanup.borrows`)와 Hikari acquire 차분의 정확 일치 | ADR-009 구현·테스트 | **해소** — `ReconcilerImplTest`·`QuotaCleanupImplTest`(`hikaricp.connections.acquire` 증가분 = 빌림 지표 증가분), 커밋 63ebe0f4 |
| IV-009-7 | 〃 §4 | C 대조 세션을 DB 쪽(대기 이벤트 표본 등)에서 구분하는 방법(application_name 등) | ADR-009 하네스 | 열림 |
