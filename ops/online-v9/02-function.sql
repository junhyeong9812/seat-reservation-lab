-- ADR-013 §4.4 단계 2(앞) — 함수. V9 트리거판(:9-15)과 같은 문장(동치 확인이 pg_get_functiondef로 다시 본다 — 단계 4).
CREATE FUNCTION trg_quota_touch() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $$;
