-- ADR-003 유니크 전략 전용 — spring.flyway.locations에 classpath:db/migration-unique를 더할 때만 적용된다.
-- 좌석당 살아 있는 홀드 1개를 DB가 강제한다(홀드는 확정·만료 시 행이 지워지므로 seat_id 유니크로 충분).
CREATE UNIQUE INDEX uq_seat_hold_seat_id ON seat_hold (seat_id);
