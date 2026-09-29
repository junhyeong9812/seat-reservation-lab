-- ADR-002 DB 기준선 보정: 선점·확정 경로의 전체 스캔을 없애는 일반 인덱스.
-- 모두 유니크가 아니다 — 조회 속도만 바꾸고 중복(같은 좌석 홀드 2개 등)은 막지 않는다.
-- 중복을 막는 유니크 인덱스·제약은 락 비교(ADR-003)의 해결책 후보로 남긴다.
-- 인덱스 없는 조건은 spring.flyway.target=1 로 이 파일을 적용하지 않고 만든다.

CREATE INDEX idx_seat_hold_seat_id        ON seat_hold (seat_id);                -- 좌석의 홀드 목록 로드, FK
CREATE INDEX idx_seat_hold_schedule_user  ON seat_hold (schedule_id, user_id);   -- 1인 2매 확인 (홀드 수)
CREATE INDEX idx_seat_hold_expires_at     ON seat_hold (expires_at);             -- 만료 배치 조회
CREATE INDEX idx_reservation_schedule_user ON reservation (schedule_id, user_id); -- 1인 2매 확인 (확정 예약 수)
CREATE INDEX idx_reservation_seat_id      ON reservation (seat_id);              -- FK
