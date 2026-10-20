-- ADR-012 ⓑ(updated_at DB 트리거) 전용 — spring.flyway.locations에 classpath:db/migration-updated-at-trigger를 더할 때만 적용된다(V4·V8과 같은 선택 위치).
-- 열·인덱스 DDL 문자열은 문장 위치(db/migration-updated-at)의 V9와 같다(§4.2.2). 두 V9 위치를 함께 더하면 Flyway가 기동을 멈춘다.
-- now()는 STABLE이라 기존 행은 다시 쓰지 않고(빠른 기본값) 마이그레이션 시각을 갖는다. INSERT 경로는 이 기본값으로 덮인다.
ALTER TABLE user_hold_quota ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX idx_user_hold_quota_updated_at ON user_hold_quota (updated_at);

-- 모든 UPDATE(앱 문장 · V8 quota_add · 직접 SQL · ON CONFLICT DO UPDATE)가 표시를 남긴다 — 조건(WHEN)을 두지 않는다.
-- 값 = now() = 그 쓰기 트랜잭션의 시작 시각(증분 창 규칙의 근거 — §4.2.3). 행 삭제는 표시가 없다(안전망 몫).
CREATE FUNCTION trg_quota_touch() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $$;

CREATE TRIGGER user_hold_quota_touch BEFORE UPDATE ON user_hold_quota
    FOR EACH ROW EXECUTE FUNCTION trg_quota_touch();
