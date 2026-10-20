-- ADR-012 ⓒ(updated_at 앱 문장) 전용 — spring.flyway.locations에 classpath:db/migration-updated-at을 더할 때만 적용된다(V4·V8과 같은 선택 위치).
-- 트리거 위치(db/migration-updated-at-trigger)의 V9에서 트리거·함수만 뺀 것이다 — 열·인덱스 DDL 문자열은 두 파일이 같다(§4.2.2).
-- 두 V9 위치를 함께 더하면 Flyway가 '같은 버전 V9가 2개'로 기동을 멈춘다(조용히 섞이지 않는다).
-- now()는 STABLE이라 기존 행은 다시 쓰지 않고(빠른 기본값) 마이그레이션 시각을 갖는다. INSERT 경로는 이 기본값으로 덮인다.
ALTER TABLE user_hold_quota ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX idx_user_hold_quota_updated_at ON user_hold_quota (updated_at);
