-- ADR-015 §설계-4 최후 방어선 — 기본 위치(classpath:db/migration · 모든 구성에 적용 — §인터뷰 · Q7).
-- 같은 좌석의 CONFIRMED는 1건(CANCELED 이력은 막지 않는다 — 부분 인덱스). FM2(같은 좌석 CONFIRMED 2건)만 막는다 — FM1 · FM3은 확정 원자성(§설계-3)이 맡는다.
-- 기존 중복이 있으면 이 문장이 23505로 실패하고 Flyway가 기동을 멈춘다(조용히 건너뛰지 않는다 — IF NOT EXISTS 쓰지 않음). 배포 전 R0 사전 검사(§설계-4)를 먼저 돌린다.
-- 트랜잭션 안 판: reservation에 SHARE 락을 끝까지 쥔다(그동안 확정 INSERT가 멈춘다). CONCURRENTLY 판은 운영 크기 사본 측정([구현 검증] #5) 뒤 결정한다.
-- 앱의 A 확정은 이 인덱스 위반(23505 · 이름 일치)을 409 SEAT_NOT_HELD + 지표 seat.confirm.unique_violation으로 바꾼다. 기준선 재현(ADR-001·003 조건)은 spring.flyway.target = V12 앞 마지막 버전(기본 위치만이면 7 — 11은 선택 위치에만 있어 failOnMissingTarget으로 기동 실패 · ADR-015 T12).
CREATE UNIQUE INDEX uq_reservation_confirmed_seat ON reservation (seat_id) WHERE status = 'CONFIRMED';
