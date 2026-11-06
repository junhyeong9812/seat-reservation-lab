-- ADR-015 §설계-4 R2 확인 — 하나라도 어기면 배포하지 않는다: R0 = 0행 · v_counter_mismatch = 0 · v_reserved_without_confirmed = 0 · v_confirmed_on_unreserved_seat = 0.
-- 판정기 문장(LoadtestDataService.consistency — LoadtestDataService.kt:140-210)을 DB에서 직접 옮겼다. 이름은 판정기 출력 이름 그대로(DB 뷰가 아니다).
-- 다른 점 1개: v_counter_mismatch의 '마감 회차 제외'(starts_at <= now)는 판정기가 앱 Clock을 쓰고 여기서는 DB now()를 쓴다(시계 차만큼 경계 회차가 갈릴 수 있다 — ADR-015 [구현 검증] #12).
-- v_counter_mismatch는 카운터 방식(counter 계열 — 판정기 COUNTER_FAMILY)일 때만 뜻이 있다. 다른 매수 방식이면 cnt를 유지하지 않으므로 그 칸은 보지 않는다(README).
-- 출력 한 줄(JSON) + ok = false면 psql이 오류로 끝난다(exit 3 — ON_ERROR_STOP).
--   psql -X -v ON_ERROR_STOP=1 -f ops/confirm-unique-v12/r2-verify.sql
\set ON_ERROR_STOP on
WITH actual AS (
    SELECT schedule_id, user_id, sum(n) AS total FROM (
        SELECT schedule_id, user_id, count(*) AS n FROM seat_hold GROUP BY 1, 2
        UNION ALL
        SELECT schedule_id, user_id, count(*) FROM reservation WHERE status = 'CONFIRMED' GROUP BY 1, 2
    ) u GROUP BY 1, 2
),
pairs AS (
    SELECT schedule_id, coalesce(q.cnt, 0) AS cnt, coalesce(a.total, 0) AS total
    FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id)
),
closed_s AS (SELECT id FROM product_schedule WHERE starts_at <= now()),
r AS (
    SELECT
      (SELECT count(*) FROM (SELECT seat_id FROM reservation WHERE status = 'CONFIRMED' GROUP BY seat_id HAVING count(*) > 1) d) AS r0_rows,
      (SELECT count(*) FROM pairs WHERE cnt <> total AND schedule_id NOT IN (SELECT id FROM closed_s))                     AS v_counter_mismatch,
      (SELECT count(*) FROM product_seat s WHERE s.status = 'RESERVED'
         AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.seat_id = s.id AND r.status = 'CONFIRMED'))                    AS v_reserved_without_confirmed,
      (SELECT count(*) FROM reservation r JOIN product_seat s ON s.id = r.seat_id
         WHERE r.status = 'CONFIRMED' AND s.status <> 'RESERVED')                                                          AS v_confirmed_on_unreserved_seat
)
SELECT json_build_object('r0_rows', r0_rows, 'v_counter_mismatch', v_counter_mismatch, 'v_reserved_without_confirmed', v_reserved_without_confirmed,
                         'v_confirmed_on_unreserved_seat', v_confirmed_on_unreserved_seat, 'checked_at', now()) AS r2,
       (r0_rows = 0 AND v_counter_mismatch = 0 AND v_reserved_without_confirmed = 0 AND v_confirmed_on_unreserved_seat = 0) AS r2_ok
FROM r \gset
\echo :r2
\if :r2_ok
\echo 'R2 ok — 배포 가능'
\else
\echo 'R2 FAIL — 배포하지 않는다(0이 아닌 칸을 본다)'
DO $$ BEGIN RAISE EXCEPTION 'R2 확인 실패'; END $$;
\endif
