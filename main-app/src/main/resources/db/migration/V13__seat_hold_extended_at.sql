-- ADR-015 §3.4 ④(결제 중 홀드 연장) 표지 열 — 기본 위치(추가형 · 모든 구성). 첫 연장 시각(NULL = 연장 안 함) — 한 홀드는 많아야 한 번 연장된다.
-- 기존 행은 NULL(연장 안 한 홀드 — 뜻이 맞다). 기본값 없음 · NULL 허용이라 PostgreSQL은 표를 다시 쓰지 않는다(카탈로그만 · 짧은 ACCESS EXCLUSIVE).
-- 엔티티 SeatHold에는 매핑하지 않는다(ddl-auto: validate는 매핑한 열만 본다 · JPA 선점 INSERT는 이 열을 쓰지 않아 NULL로 들어간다).
-- 새 확정 모드(①~④)는 이 열이 있어야 기동한다(A′ 배치 B3 RETURNING — §설계-10 T2).
ALTER TABLE seat_hold ADD COLUMN extended_at TIMESTAMPTZ NULL;
