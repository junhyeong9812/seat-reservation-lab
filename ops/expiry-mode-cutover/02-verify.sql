-- ADR-014 §설계-7 방식 전환 확인 — 01-cleanup.sql 뒤 · 앱을 올리기 전. 세 값이 모두 0이어야 올린다(하나라도 어기면 올리지 않는다).
-- 정의는 판정기(LoadtestDataService.consistency) 항목 그대로다 — 판정기 엔드포인트는 앱이 떠 있어야 해서 같은 문장을 DB에서 직접 돌린다.
--   v_held_without_hold   : HELD인데 홀드 행 없음(T2 뒤 HELD는 활성 홀드가 있는 좌석뿐)
--   v_duplicate_hold_seats: 좌석당 홀드 행 > 1(T1 뒤 남은 홀드는 모두 활성이고 좌석당 활성 ≤ 1 — N2)
--   v_counter_mismatch    : 열린 회차 쌍의 cnt ≠ 홀드 행 + CONFIRMED(마감 회차는 뺀다 — 판정기는 앱 Clock · 여기서는 앱이 내려가 있어 DB now())
-- 테스트(Adr014Support.kt cutoverVerify)가 이 파일을 읽어 돌린다.
WITH closed_s AS (SELECT id FROM product_schedule WHERE starts_at <= now()),
actual AS (
    SELECT schedule_id, user_id, sum(n) AS total FROM (
        SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
        UNION ALL
        SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2
    ) u GROUP BY 1, 2
),
pairs AS (
    SELECT schedule_id, coalesce(q.cnt, 0) AS cnt, coalesce(a.total, 0) AS total
    FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id)
)
SELECT
  (SELECT count(*) FROM product_seat s WHERE s.status = 'HELD'
     AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = s.id))                       AS v_held_without_hold,
  (SELECT count(*) FROM (SELECT seat_id FROM seat_hold GROUP BY seat_id HAVING count(*) > 1) x) AS v_duplicate_hold_seats,
  (SELECT count(*) FROM pairs WHERE cnt <> total AND schedule_id NOT IN (SELECT id FROM closed_s)) AS v_counter_mismatch;
