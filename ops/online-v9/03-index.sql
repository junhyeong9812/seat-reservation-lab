-- ADR-013 §4.4 단계 3 — 인덱스. V9(:5)의 CREATE INDEX에 CONCURRENTLY만 더한 것(트랜잭션 밖 — 자동 커밋).
-- apply.sh가 앞에 RESET lock_timeout · SET statement_timeout = '<상한>'을 둔다(DS-8 — CONCURRENTLY는 단계 사이 앞 트랜잭션을 락 대기로 기다린다).
CREATE INDEX CONCURRENTLY idx_user_hold_quota_updated_at ON user_hold_quota (updated_at);
