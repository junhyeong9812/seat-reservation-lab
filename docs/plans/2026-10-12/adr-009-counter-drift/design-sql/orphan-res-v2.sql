SELECT 'reservation' AS src, r.schedule_id, r.user_id, count(*) AS n
FROM reservation r
WHERE r.id >= :a AND r.id < :b AND r.status = 'CONFIRMED'
  AND NOT EXISTS (SELECT 1 FROM user_hold_quota q WHERE q.schedule_id = r.schedule_id AND q.user_id = r.user_id)
GROUP BY 1, 2, 3;
