-- ADR-015 §3.3 ③(낙관락 — 좌석 버전 비교) 전용 — spring.flyway.locations에 classpath:db/migration-confirm-version을 더할 때만 적용된다(V4 · V8 · V11과 같은 선택 위치).
-- 지금 product_seat.version(V3)은 ADR-003 낙관락 선점만 올린다 — 기본 조합의 선점 · 배치 · 확정은 올리지 않아 확정만 버전을 비교하면 ABA(HELD → AVAILABLE → HELD)를 못 본다.
-- 그래서 상태가 실제로 바뀔 때마다 DB가 버전을 올린다(선점 · 배치 · 확정 코드 — JPA · JDBC 모두 — 를 고치지 않고 덮는다 · §3.3 (나)). 같은 값 UPDATE는 올리지 않는다.
-- 짝(기동 검사 — HoldStrategyStartupCheck): seat.confirm.mode=optimistic이면 이 위치가 있어야 하고, 이 위치가 있으면 낙관락 선점(seat.hold.strategy=optimistic)은 쓰지 않는다
-- (그 전략은 버전을 먼저 읽고 flush 뒤 bumpVersion(expected)을 한다 — 트리거가 먼저 올리면 늘 0행).
-- 되돌리기: 모드만 current로 — 위치는 목록에서 빼지 않는다(적용된 V14가 위치에 없으면 Flyway 검증이 기동을 멈춘다). 트리거가 남아도 기본 조합에 무해하다(버전을 읽는 곳이 없다).
-- 기본 위치 V12 · V13보다 높은 번호: 나중에 이 위치를 더해도 '적용된 버전보다 낮은 미적용'이 되지 않는다(§설계-4 번호).
CREATE FUNCTION trg_product_seat_status_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.version := OLD.version + 1;
    RETURN NEW;
END $$;

CREATE TRIGGER product_seat_status_version BEFORE UPDATE OF status ON product_seat
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION trg_product_seat_status_version();
