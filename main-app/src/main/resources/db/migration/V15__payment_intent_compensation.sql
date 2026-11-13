-- ADR-017 설계-4.1 — 결제 의도 · 거부 기록 · 보상 아웃박스(기본 위치 · 추가형 · 모든 구성).
-- payment_intent = uid 소유 행이자 직렬화 지점(RD17-1): 확정 · 거부 기록 · 안전망 · 대체 배정이 모두 이 행을 FOR UPDATE로 먼저 잡고 state 하나로 결과를 고른다.
-- uid는 payment-start가 발급한다(I13). hold_id는 같은 홀드의 재호출이 같은 uid를 받게 하는 열이다 — seat_hold 행은 확정 · 만료로 지워지므로 FK를 두지 않는다.
-- schedule_id · seat_id도 홀드에서 옮겨 적은 스냅숏이라 FK를 두지 않는다 — FK면 intent INSERT가 회차 · 좌석 행에 FOR KEY SHARE를 걸어
-- payment-start(N16-1 — 잠금 문장 0)가 핫 회차 행 공유 잠금 · 좌석 FOR UPDATE NOWAIT 경합을 새로 만든다.
CREATE TABLE payment_intent (
    uid            VARCHAR(64)  PRIMARY KEY,
    hold_id        BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    schedule_id    BIGINT       NOT NULL,
    seat_id        BIGINT       NOT NULL,
    section        VARCHAR(20)  NOT NULL,
    pay_by         TIMESTAMPTZ  NOT NULL,
    state          VARCHAR(16)  NOT NULL,
    result_code    VARCHAR(32)  NULL,
    reservation_id BIGINT       NULL,
    closed_reason  VARCHAR(16)  NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT payment_intent_hold_uk UNIQUE (hold_id),
    CONSTRAINT payment_intent_state_ck CHECK (state IN ('OPEN', 'CONFIRMED', 'REJECTED', 'SUBSTITUTED', 'CLOSING'))
);
-- 안전망(설계-4.5)이 OPEN만 훑는다 — 닫힌 intent는 쌓이기만 하므로(Q8) 부분 인덱스로 OPEN 행만 둔다
CREATE INDEX idx_payment_intent_open ON payment_intent (created_at) WHERE state = 'OPEN';

-- 거부의 증거(설계-4.1) — 홀드 · 좌석 정보는 intent에 있다. uid당 1행(첫 거부만 — 뒤 요청은 재생)
CREATE TABLE confirm_rejection (
    id          BIGSERIAL    PRIMARY KEY,
    uid         VARCHAR(64)  NOT NULL UNIQUE REFERENCES payment_intent (uid),
    code        VARCHAR(32)  NOT NULL,
    http_status INT          NOT NULL,
    rejected_at TIMESTAMPTZ  NOT NULL
);

-- 보상 아웃박스(설계-4.4) — (uid, type)당 1행 · INSERT는 ON CONFLICT DO NOTHING
CREATE TABLE compensation_task (
    id              BIGSERIAL    PRIMARY KEY,
    uid             VARCHAR(64)  NOT NULL REFERENCES payment_intent (uid),
    type            VARCHAR(16)  NOT NULL,
    source          VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    lease_until     TIMESTAMPTZ  NULL,
    last_result     VARCHAR(64)  NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    done_at         TIMESTAMPTZ  NULL,
    CONSTRAINT compensation_task_uid_type_uk UNIQUE (uid, type),
    CONSTRAINT compensation_task_type_ck CHECK (type IN ('CLOSE', 'CAPTURE')),
    CONSTRAINT compensation_task_source_ck CHECK (source IN ('REJECTION', 'SWEEP', 'CONFIRM')),
    CONSTRAINT compensation_task_status_ck CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'DEAD'))
);
-- 워커가 집는 순서(status = PENDING · next_attempt_at) · 리스 만료 IN_PROGRESS 회수 · 정체 지표가 같은 인덱스를 쓴다
CREATE INDEX idx_compensation_task_due ON compensation_task (status, next_attempt_at);

-- intent 경로의 예약만 값을 가진다(기존 행 · 옛 경로 = NULL — RD17-9: payment_uid 유니크를 두지 않아 기존 데이터 · 옛 하네스와 충돌 없음).
-- NULL 허용 · 기본값 없음 → 표를 다시 쓰지 않는다. UNIQUE = N17-1(intent당 예약 ≤ 1)
ALTER TABLE reservation ADD COLUMN intent_id VARCHAR(64) NULL REFERENCES payment_intent (uid);
ALTER TABLE reservation ADD CONSTRAINT reservation_intent_uk UNIQUE (intent_id);
-- 대체 좌석 표시(설계-5 S). 상수 기본값 추가는 PostgreSQL 11+에서 표를 다시 쓰지 않는다(카탈로그만)
ALTER TABLE reservation ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'HOLD';
