-- ADR-014 좌석 맵(GET /api/schedules/{id}/seats) 전용 — spring.flyway.locations에 classpath:db/migration-seat-map을 더할 때만 적용된다(V4·V8·V9·V10과 같은 선택 위치).
-- 기본 위치 목록(application.yml)에는 넣지 않는다(선검증 DS14-OQ1 — 기본 sweep 배포에 스키마 변경을 붙이지 않는다). 측정 때 모든 방식 셀에 같게 더하고,
-- 기본 위치 편입은 기본값(expiry-mode)을 교체할 때 함께 한다. 이 인덱스가 없어도 좌석 맵은 같은 답을 내고 좌석 표를 훑을 뿐이다.
-- 트랜잭션 안 CREATE INDEX는 SHARE 락으로 끝날 때까지 product_seat 쓰기를 막는다 — 시드 크기 사본에서 시간을 먼저 잰다(§설계-7 · [구현 검증] #5).
CREATE INDEX idx_product_seat_schedule_id ON product_seat (schedule_id);
