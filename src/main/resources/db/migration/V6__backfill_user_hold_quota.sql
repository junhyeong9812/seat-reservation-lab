-- ADR-008 매수 제어 기본값(counter-upsert-precheck) 전환에 따른 카운터 백필.
-- 카운터 정의(판정기 LoadtestDataService v_counter_mismatch와 같다): user_hold_quota.cnt = 그 (회차, 사용자)의 seat_hold 행 전체 수(만료 전·후 구분 없음 —
-- 만료 배치가 실제로 지운 홀드만큼 내리므로) + reservation CONFIRMED 수. 회차는 seat_hold·reservation 자신의 schedule_id.
-- 쓰는 테이블은 user_hold_quota 하나뿐이다(홀드·예약은 바꾸지 않는다). 상한(max-per-user)을 넘는 실제 값도 그대로 둔다 — 다음 요청부터 upsert의 cnt + 1 <= 최대가 막는다.
-- 멱등: 다시 실행해도 같은 결과(두 번째 실행은 아무 행도 쓰지 않는다). 빈 DB에서는 아무 행도 만들지 않는다.
-- 전환 전제(cutover): 마이그레이션 전에 카운터를 유지하지 않는 이전 버전(none 등)의 쓰기 주체를 전부 멈춘다 — 아래 LOCK은 실행 중의 쓰기만 막고,
--   커밋 뒤 이전 버전이 쓴 홀드·예약은 카운터에 반영되지 않는다(V6는 한 번만 돈다). 적용 직후 v_counter_mismatch = 0을 확인한다.
-- 범위 가정: (회차, 사용자)당 홀드 + 확정 예약 수 ≤ int 범위(sum()::int — 넘으면 조용히 자르지 않고 오류로 실패한다).

-- 실행 중 쓰기 차단: 세 테이블을 함께 잠가 ①·②가 같은 사실을 보게 한다(진행 중인 쓰기는 끝날 때까지 기다리고, 새 쓰기는 커밋까지 막는다).
-- Flyway는 이 파일을 트랜잭션 하나로 실행한다(LOCK TABLE은 트랜잭션 안에서만 된다).
LOCK TABLE seat_hold, reservation, user_hold_quota IN SHARE ROW EXCLUSIVE MODE;

-- ① 실제 값이 1 이상인 쌍: 행이 없으면 만들고, 있으면 실제 값으로 덮어쓴다(같으면 쓰지 않는다).
INSERT INTO user_hold_quota (schedule_id, user_id, cnt)
SELECT schedule_id, user_id, sum(n)::int
FROM (
    SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY schedule_id, user_id
    UNION ALL
    SELECT schedule_id, user_id, count(*) AS n FROM reservation WHERE status = 'CONFIRMED' GROUP BY schedule_id, user_id
) actual
GROUP BY schedule_id, user_id
ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = EXCLUDED.cnt
WHERE user_hold_quota.cnt <> EXCLUDED.cnt;

-- ② 실제 값이 0인데 cnt가 0이 아닌 기존 행: 0으로 둔다(행은 지우지 않는다 — 쿼터 행 락 방식·counter의 prepare가 만든 행을 그대로 둔다).
UPDATE user_hold_quota q
SET cnt = 0
WHERE q.cnt <> 0
  AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.schedule_id = q.schedule_id AND h.user_id = q.user_id)
  AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.schedule_id = q.schedule_id AND r.user_id = q.user_id AND r.status = 'CONFIRMED');
