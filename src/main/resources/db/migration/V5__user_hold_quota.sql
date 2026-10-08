-- ADR-005 사용자 단위 매수 제어용 쿼터 행. 쿼터 행 락 방식(quota-lock·quota-nowait)은 이 행을 잠글 대상으로만 쓰고,
-- 카운터 방식(counter)만 cnt(홀드 + 확정 예약 수)를 유지한다. 다른 방식은 이 테이블을 읽지도 쓰지도 않는다.
CREATE TABLE user_hold_quota (
    schedule_id BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    cnt         INT    NOT NULL DEFAULT 0 CHECK (cnt >= 0),
    PRIMARY KEY (schedule_id, user_id)
);
