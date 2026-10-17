-- (1) 쿼터 행 주도 청크: 회차 :s의 쿼터 행을 PK keyset으로 :k개(user_id > :after), 쌍마다 실제 값을 인덱스 탐색으로 센다(범위 스캔 없음)
WITH q AS (
  SELECT user_id, cnt FROM user_hold_quota
  WHERE schedule_id = :s AND user_id > :after ORDER BY user_id LIMIT :k),
x AS (
  SELECT q.user_id, q.cnt,
         (SELECT count(*) FROM seat_hold h WHERE h.user_id = q.user_id AND h.schedule_id = :s)
       + (SELECT count(*) FROM reservation r WHERE r.schedule_id = :s AND r.user_id = q.user_id AND r.status = 'CONFIRMED') AS actual
  FROM q)
SELECT (SELECT max(user_id) FROM q) AS last_user, (SELECT count(*) FROM q) AS scanned, d.user_id, d.cnt, d.actual
FROM (SELECT 1) one LEFT JOIN (SELECT * FROM x WHERE cnt <> actual) d ON true;
