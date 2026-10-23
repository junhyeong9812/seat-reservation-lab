-- ADR-013 §4.4 단계 2(뒤) — 트리거. V9 트리거판(:17-18)과 같은 문장. 함수와 한 트랜잭션(apply.sh — SET LOCAL lock_timeout = '2s').
CREATE TRIGGER user_hold_quota_touch BEFORE UPDATE ON user_hold_quota
    FOR EACH ROW EXECUTE FUNCTION trg_quota_touch();
