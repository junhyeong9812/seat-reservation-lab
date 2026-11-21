-- ADR-018 설계-1 묶음 홀드(다석 선점) — 기본 위치 · 추가형(1석 홀드는 group_id NULL — 1석 경로의 쿼리 · 측정 계약 무변경).
-- 묶음의 홀드 행은 늘 같은 expires_at을 가진다(선점 때 한 시각 · 결제 시작 때 함께 연장) — 만료 배치는 묶음을 통째로 지운다.
-- hold_group 행은 홀드가 사라져도 남긴다(결제 의도 · 예약이 그 id를 가리킨다).
CREATE TABLE hold_group (
    id          BIGSERIAL   PRIMARY KEY,
    schedule_id BIGINT      NOT NULL REFERENCES product_schedule (id),
    user_id     BIGINT      NOT NULL,
    seat_count  INT         NOT NULL CHECK (seat_count >= 1),
    created_at  TIMESTAMPTZ NOT NULL
);
ALTER TABLE seat_hold ADD COLUMN group_id BIGINT NULL REFERENCES hold_group (id);
CREATE INDEX idx_seat_hold_group ON seat_hold (group_id) WHERE group_id IS NOT NULL;

-- 묶음 결제 의도 = 묶음당 1행(seat_count = N · hold_id · seat_id · section은 id가 가장 작은 홀드의 값). 기존 행은 1석(seat_count 1)
ALTER TABLE payment_intent ADD COLUMN group_id   BIGINT NULL REFERENCES hold_group (id);
ALTER TABLE payment_intent ADD COLUMN seat_count INT    NOT NULL DEFAULT 1;
ALTER TABLE payment_intent ADD CONSTRAINT payment_intent_group_uk UNIQUE (group_id);

-- 결제 의도당 예약 1행 → (결제 의도, 좌석)당 1행. 1석 결제 의도는 좌석이 하나라 뜻이 같다(결제 의도당 예약 수 ≤ seat_count는 정합성 검증기가 센다).
ALTER TABLE reservation DROP CONSTRAINT reservation_intent_uk;
ALTER TABLE reservation ADD CONSTRAINT reservation_intent_seat_uk UNIQUE (intent_id, seat_id);
