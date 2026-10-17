-- (2) 행 없음 쌍: 홀드(id 범위)·확정 예약(id 범위) 중 쿼터 행이 없는 (회차, 사용자). PK 범위 + 쿼터 PK 탐색
SELECT 'hold' AS src, h.schedule_id, h.user_id, count(*) AS n
FROM seat_hold h
WHERE h.id >= :a AND h.id < :b
  AND NOT EXISTS (SELECT 1 FROM user_hold_quota q WHERE q.schedule_id = h.schedule_id AND q.user_id = h.user_id)
GROUP BY 1, 2, 3;
