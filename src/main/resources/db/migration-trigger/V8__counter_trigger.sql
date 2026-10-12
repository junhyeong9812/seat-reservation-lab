-- ADR-009 후보 B 전용 — spring.flyway.locations에 classpath:db/migration-trigger를 더할 때만 적용된다(V4 migration-unique와 같은 방식). 매수 방식 counter-trigger와 짝(기동 검사).
-- 카운터 정의(V6·판정기와 같다): cnt = 그 (회차, 사용자)의 seat_hold 행 수 + reservation CONFIRMED 수.
-- 트리거가 홀드·예약 변경에 따른 cnt의 쓰기 주체다(앱 선점은 판정 — 잠금·상한 확인 — 만 하고 cnt를 바꾸지 않는다. 예외: 어긋남 복구(대조 C·F)는 cnt를 실제 값으로 덮어쓴다).
-- 엄격: 줄일 행이 없으면 예외(SQLSTATE SR001), 음수는 V5 CHECK(23514)가 막는다 — 조용히 0으로 자르지 않는다(무음 금지 — 명세 §10 D3).
-- v2(codex B1): 카운터 기여가 바뀌지 않는 UPDATE는 아무것도 쓰지 않고 바로 반환한다 — 기여 = (CONFIRMED 여부, 회차, 사용자).
--   v1은 CONFIRMED → CONFIRMED 갱신에 −1·+1을 썼다: 주입으로 cnt가 0이면 −1이 CHECK에 걸려 순증감 0 갱신이 실패했다.

CREATE FUNCTION quota_add(p_schedule BIGINT, p_user BIGINT, p_delta INT) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF p_delta > 0 THEN
        INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (p_schedule, p_user, p_delta)
        ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = user_hold_quota.cnt + EXCLUDED.cnt;
    ELSE
        UPDATE user_hold_quota SET cnt = cnt + p_delta WHERE schedule_id = p_schedule AND user_id = p_user;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = 'SR001',
                MESSAGE = format('counter row missing: schedule %s user %s', p_schedule, p_user);
        END IF;
    END IF;
END $$;

CREATE FUNCTION trg_seat_hold_quota() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM quota_add(NEW.schedule_id, NEW.user_id, 1);
    ELSIF TG_OP = 'DELETE' THEN
        PERFORM quota_add(OLD.schedule_id, OLD.user_id, -1);
    ELSE  -- UPDATE OF schedule_id, user_id: 홀드는 행 자체가 기여 1 — 키가 같으면 기여 불변
        IF (OLD.schedule_id, OLD.user_id) IS NOT DISTINCT FROM (NEW.schedule_id, NEW.user_id) THEN
            RETURN NULL;
        END IF;
        PERFORM quota_add(NEW.schedule_id, NEW.user_id, 1);   -- 늘리는 쪽 먼저(같은 트랜잭션 — 순서는 결과에 무관, 행 없음 오류만 −1 쪽)
        PERFORM quota_add(OLD.schedule_id, OLD.user_id, -1);
    END IF;
    RETURN NULL;
END $$;

CREATE FUNCTION trg_reservation_quota() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    old_c BOOLEAN := TG_OP IN ('UPDATE', 'DELETE') AND OLD.status = 'CONFIRMED';
    new_c BOOLEAN := TG_OP IN ('INSERT', 'UPDATE') AND NEW.status = 'CONFIRMED';
BEGIN
    IF TG_OP = 'UPDATE' AND old_c = new_c
       AND (NOT old_c OR (OLD.schedule_id, OLD.user_id) IS NOT DISTINCT FROM (NEW.schedule_id, NEW.user_id)) THEN
        RETURN NULL;   -- 기여 불변: 둘 다 CONFIRMED 아님(회차·사용자가 바뀌어도 기여 0) · 둘 다 CONFIRMED이고 키가 같음
    END IF;
    IF new_c THEN PERFORM quota_add(NEW.schedule_id, NEW.user_id, 1); END IF;
    IF old_c THEN PERFORM quota_add(OLD.schedule_id, OLD.user_id, -1); END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER seat_hold_quota AFTER INSERT OR DELETE OR UPDATE OF schedule_id, user_id ON seat_hold
    FOR EACH ROW EXECUTE FUNCTION trg_seat_hold_quota();
CREATE TRIGGER reservation_quota AFTER INSERT OR DELETE OR UPDATE OF status, schedule_id, user_id ON reservation
    FOR EACH ROW EXECUTE FUNCTION trg_reservation_quota();
