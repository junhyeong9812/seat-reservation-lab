-- ADR-009 후보 B 전용 — spring.flyway.locations에 classpath:db/migration-trigger를 더할 때만 적용된다(V4 migration-unique와 같은 방식). 매수 방식 counter-trigger와 짝(기동 검사).
-- 카운터 정의(V6·판정기와 같다): cnt = 그 (회차, 사용자)의 seat_hold 행 수 + reservation CONFIRMED 수.
-- 트리거가 홀드·예약 변경에 따른 cnt의 쓰기 주체다(앱 선점은 판정 — 잠금·상한 확인 — 만 하고 cnt를 바꾸지 않는다. 예외: 어긋남 복구(대조 C·F)는 cnt를 실제 값으로 덮어쓴다).
-- 엄격: 늘리거나 줄일 행이 없으면 예외(SQLSTATE SR001), 음수는 V5 CHECK(23514)가 막는다 — 조용히 0으로 자르지 않는다(무음 금지 — 명세 §10 D3).
-- v2(codex B1): 카운터 기여가 바뀌지 않는 UPDATE는 아무것도 쓰지 않고 바로 반환한다 — 기여 = (CONFIRMED 여부, 회차, 사용자).
--   v1은 CONFIRMED → CONFIRMED 갱신에 −1·+1을 썼다: 주입으로 cnt가 0이면 −1이 CHECK에 걸려 순증감 0 갱신이 실패했다.

-- 마감 판정(코드 루프 1 L1-2 · L1-19, 사용자 결정): 마감 회차(starts_at <= 기준 시각)의 기여 변화는 카운터에 반영하지 않는다
-- (명세 §10: 마감 회차는 카운터를 쓰지 않는다 — 판정기·대조도 뺀다). 판정은 기여 쪽별이다: quota_add가 쪽마다 자기 회차를 본다
-- (UPDATE로 회차·사용자·CONFIRMED 여부가 바뀌면 이전 쪽 −1·새 쪽 +1을 각각 판정 — 열린 쪽에만 엄격한 증감).
-- 기준 시각 출처(코드 루프 2 L2-4): 세션 설정 seatlab.counter_now가 있으면 그 값, 없으면 DB now()(트랜잭션 시작 시각).
--   앱의 counter-trigger 경로(선점·확정·만료)는 트랜잭션 시작 때 set_config('seatlab.counter_now', <앱 Clock>, true)(= SET LOCAL)를 넣는다 —
--   판매 마감 판정(SalesCloseGate, 앱 Clock)과 같은 출처가 되어 마감 경계에서 앱과 트리거의 판정이 갈리지 않는다.
--   앱을 거치지 않는 직접 DB 쓰기(운영 수작업·주입)는 설정이 없어 now()로 판정한다 — 앱 서버와 DB 서버의 시계 차만큼 경계가 어긋날 수 있다
--   (그 회차는 마감 직후 판정기·대조에서 빠지므로 남는 어긋남은 판정 대상이 아니다).
CREATE FUNCTION counter_now() RETURNS timestamptz
LANGUAGE sql STABLE AS $$
    SELECT coalesce(nullif(current_setting('seatlab.counter_now', true), '')::timestamptz, now())
$$;

CREATE FUNCTION counter_schedule_closed(p_schedule BIGINT) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM product_schedule WHERE id = p_schedule AND starts_at <= counter_now())
$$;

-- 엄격(L1-1, 사용자 결정): 증가·감소 모두 쿼터 행이 있어야 한다 — 없으면 SR001(행을 새로 만들지 않는다). 선점 경로는 앱(counter-trigger의
-- lockQuotaRow)이 행을 먼저 보장하므로 영향이 없고, 확정·직접 변경은 어긋남(행 없음)이 있을 때만 실패한다. 음수는 V5 CHECK(23514).
CREATE FUNCTION quota_add(p_schedule BIGINT, p_user BIGINT, p_delta INT) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF counter_schedule_closed(p_schedule) THEN
        RETURN;
    END IF;
    UPDATE user_hold_quota SET cnt = cnt + p_delta WHERE schedule_id = p_schedule AND user_id = p_user;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = 'SR001',
            MESSAGE = format('counter row missing: schedule %s user %s', p_schedule, p_user);
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
        PERFORM quota_add(NEW.schedule_id, NEW.user_id, 1);   -- 늘리는 쪽 먼저(같은 트랜잭션 — 순서는 결과에 무관). 쪽마다 마감 판정
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
