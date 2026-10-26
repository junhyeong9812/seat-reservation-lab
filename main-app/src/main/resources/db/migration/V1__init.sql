-- ADR-000 기준선: 서로게이트 PK와 FK만 둔다.
-- 중복을 막는 유니크 제약·부분 인덱스·CHECK는 의도적으로 없다 (이후 ADR의 해결책 후보).

CREATE TABLE product (
    id    BIGSERIAL    PRIMARY KEY,
    name  VARCHAR(200) NOT NULL
);

CREATE TABLE product_schedule (
    id          BIGSERIAL   PRIMARY KEY,
    product_id  BIGINT      NOT NULL REFERENCES product (id),
    starts_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE product_seat (
    id           BIGSERIAL   PRIMARY KEY,
    schedule_id  BIGINT      NOT NULL REFERENCES product_schedule (id),
    section      VARCHAR(20) NOT NULL,
    row_no       INT         NOT NULL,
    seat_no      INT         NOT NULL,
    status       VARCHAR(20) NOT NULL
);

CREATE TABLE seat_hold (
    id           BIGSERIAL   PRIMARY KEY,
    seat_id      BIGINT      NOT NULL REFERENCES product_seat (id),
    schedule_id  BIGINT      NOT NULL REFERENCES product_schedule (id),
    user_id      BIGINT      NOT NULL,
    held_at      TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE reservation (
    id           BIGSERIAL   PRIMARY KEY,
    schedule_id  BIGINT      NOT NULL REFERENCES product_schedule (id),
    seat_id      BIGINT      NOT NULL REFERENCES product_seat (id),
    user_id      BIGINT      NOT NULL,
    payment_uid  VARCHAR(64) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);
