-- ADR-013 §4.4 운영 중 적용판 단계 1 — 열. V9(db/migration-updated-at-trigger/V9__quota_updated_at_trigger.sql:4)와 같은 문장.
-- apply.sh가 BEGIN · SET LOCAL lock_timeout = '2s'(시한이 세션에 남지 않게 — DS-8) 안에서 이 문장만 돌리고 COMMIT한다. 빠른 기본값 — 표를 다시 쓰지 않는다.
ALTER TABLE user_hold_quota ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
