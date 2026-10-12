-- ADR-009 설계 초안 SQL v2 (psql 변수). v1(reconcile-v1.sql)의 '(회차, user_id 범위) 범위 스캔' 청크는 회차 첫·끝 청크가
-- seat_hold user_id 인덱스를 회차 밖까지 훑어 S4c 축척에서 한 바퀴 155초·청크 최대 1.0초였다(실험 E1 — e1-pass-out.txt). v2는 두 갈래로 나눴다.

-- [대상 회차] 열린 회차만(명세 §10: 마감 회차는 카운터를 쓰지 않는다). :now = 앱 Clock
--   SELECT id FROM product_schedule WHERE starts_at > :now ORDER BY id;
--   제외 기록(바퀴마다): SELECT count(*) FROM product_schedule WHERE starts_at <= :now  ·  쌍 수 = 그 회차들의 쿼터 행 수(PK 앞부분 index-only count)

-- [C·F ① 쿼터 행 주도 청크] 회차 :s의 쿼터 행을 PK keyset으로 :k개(user_id > :after) — 쌍마다 실제 값을 인덱스 탐색으로 센다(범위 스캔 없음).
-- 한 문장 = 한 스냅숏 = 커넥션 빌림 1. 어긋남이 0이어도 1행(last_user·scanned)을 돌려준다. scanned < :k면 그 회차 끝.
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

-- [C·F ② 행 없음 쌍] 쿼터 행이 없는데 홀드·확정 예약이 있는 (회차, 사용자) — 홀드·예약 PK(id) 범위 청크 + 쿼터 PK 탐색. 열린 회차만(:open = 배열).
SELECT 'hold' AS src, h.schedule_id, h.user_id, count(*) AS n
FROM seat_hold h
WHERE h.id >= :a AND h.id < :b AND h.schedule_id = ANY(:open)
  AND NOT EXISTS (SELECT 1 FROM user_hold_quota q WHERE q.schedule_id = h.schedule_id AND q.user_id = h.user_id)
GROUP BY 1, 2, 3;
-- 예약도 같은 꼴(reservation id 범위, status = 'CONFIRMED').

-- [쌍 복구] 짧은 트랜잭션 하나(C·F는 쌍마다 + SET LOCAL lock_timeout, A는 만료 트랜잭션 안 — 시간 제한 없음). 잠금 뒤 새 문장에서 센다(READ COMMITTED).
-- 0) (C·F만) SET LOCAL lock_timeout = :lock_timeout
-- 1) 행이 없을 때만: INSERT … (cnt 0) ON CONFLICT DO NOTHING
-- 2) SELECT cnt … FOR UPDATE
-- 2') (테스트 이음새 — CounterRepairProbe.afterDetectBeforeLock: 탐지 뒤·잠금 전. 운영에는 빈 구현)  ※ 실제 호출 위치는 1) 앞
-- 3) 실제 값 = 한 문장: (SELECT count(*) FROM seat_hold WHERE … [AND id <> ALL(:expiring)]) + (SELECT count(*) FROM reservation WHERE … AND status = 'CONFIRMED')
-- 4) 다르면 UPDATE … SET cnt = 실제(repaired), 같으면 false-positive — 둘 다 counter_drift_log 1행(같은 트랜잭션)
-- 실패(55P03·40P01 등): 롤백 → 새 트랜잭션에서 counter_drift_log(action = 'skipped', reason = SQLSTATE) — 실험 O1. 다음 바퀴가 다시 탐지한다.

-- [A 사전 검사 감소] UPDATE user_hold_quota SET cnt = cnt - :n WHERE schedule_id = :s AND user_id = :u AND cnt >= :n;   -- 0행인 쌍만 복구로

-- [정리 ① 회차 단위 — 명세 §10] 회차 :s가 마감됐고(starts_at <= :now) 쿼터 행이 남아 있을 때, 한 트랜잭션:
--   SELECT starts_at FROM product_schedule WHERE id = :s FOR UPDATE;          -- 선점의 마감 판정(FOR SHARE)과 직렬화 — 진행 중 선점이 끝날 때까지 기다린다
--   SELECT EXISTS (SELECT 1 FROM seat_hold WHERE schedule_id = :s);           -- 잠금 뒤 새 문장(그 사이 커밋된 홀드가 보인다)
--   (없으면) DELETE FROM user_hold_quota WHERE schedule_id = :s;              -- 확정 쌍까지 전부(판정기·대조가 마감 회차를 빼므로 어긋남이 아니다)
--   기록: counter_cleanup_run(schedule_id, mode 'schedule', holds_seen, rows_deleted, waited_ms)
-- 선점 쪽(마감 판정): SELECT starts_at FROM product_schedule WHERE id = :s FOR SHARE;   -- 트랜잭션 안(SERIALIZABLE은 SET TRANSACTION 다음)

-- [정리 ② cnt = 0] 청크마다 한 문장(열린 회차·마감 회차 모두). EvalPlanQual 재확인으로 진행 중 upsert와 안전(실험 E2·E2b·E3).
-- DELETE FROM user_hold_quota q WHERE q.schedule_id = :s AND q.user_id > :after AND q.user_id <= :last AND q.cnt = 0
--   AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.schedule_id = q.schedule_id AND h.user_id = q.user_id)
--   AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.schedule_id = q.schedule_id AND r.user_id = q.user_id AND r.status = 'CONFIRMED');

-- [판정기 v_counter_mismatch v2 — LoadtestDataService.consistency, 명세 §10 C1] :now = 앱 Clock
-- WITH open_s AS (SELECT id FROM product_schedule WHERE starts_at > :now), actual AS (…지금과 같다…)
-- SELECT count(*) FROM user_hold_quota q FULL JOIN actual a USING (schedule_id, user_id)
-- WHERE schedule_id IN (SELECT id FROM open_s) AND coalesce(q.cnt, 0) <> coalesce(a.total, 0);
-- + counter_excluded_schedules = count(product_schedule WHERE starts_at <= :now)
-- + counter_excluded_pairs = 마감 회차의 (쿼터 행 ∪ 실제 값 쌍) 수
