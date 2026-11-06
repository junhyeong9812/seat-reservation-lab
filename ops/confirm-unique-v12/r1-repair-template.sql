-- ADR-015 §설계-4 R1 복구 틀 — R0이 0행이 아닐 때, **사람이 고른 뒤**(어느 예약을 남길지 = 사업 결정 · 취소한 쪽은 ADR-016 보상 대상) 한 좌석씩 돌린다.
-- 자리표시자(psql 변수): keep = 남길 예약 id · drop = 취소할 예약 id 목록(배열 리터럴). commit을 주지 않으면 끝에서 ROLLBACK한다(기본 = 미리보기).
--   psql -X -v ON_ERROR_STOP=1 -v keep=101 -v drop='{102}' -f ops/confirm-unique-v12/r1-repair-template.sql             # 미리보기(ROLLBACK)
--   psql -X -v ON_ERROR_STOP=1 -v keep=101 -v drop='{102}' -v commit=1 -f ops/confirm-unique-v12/r1-repair-template.sql  # 적용(COMMIT)
-- 한 트랜잭션 · 쿼터 행을 먼저 잠근다(ADR-014 §2.4 규칙 ①과 같은 뜻). 차감(cnt − n)이 아니라 **쌍마다 V6 정의로 다시 센다**(어긋난 cnt를 옮기지 않는다 — ADR 정정).
-- 좌석은 RESERVED 그대로(확정 1건이 남는다) · 취소한 예약은 행으로 남긴다(지우지 않는다 — 결제 uid 추적).
-- 어느 확인이든 어기면 RAISE로 끝난다(ON_ERROR_STOP → psql exit 3 · 연결이 끊기며 트랜잭션은 ROLLBACK — 아무것도 바뀌지 않는다).
\set ON_ERROR_STOP on
\if :{?keep}
\else
DO $$ BEGIN RAISE EXCEPTION 'R1: -v keep=<남길 예약 id> 가 필요하다'; END $$;
\endif
\if :{?drop}
\else
DO $$ BEGIN RAISE EXCEPTION 'R1: -v drop=''{<취소할 예약 id>,…}'' 가 필요하다'; END $$;
\endif

BEGIN;

-- ① 쌍 잠금(정렬 · 기다림) — 취소할 예약의 (회차, 사용자) 쌍
SELECT q.schedule_id, q.user_id FROM user_hold_quota q
 WHERE (q.schedule_id, q.user_id) IN (SELECT schedule_id, user_id FROM reservation WHERE id = ANY(:'drop'::bigint[]))
 ORDER BY q.schedule_id, q.user_id FOR UPDATE OF q;

-- ⓟ 고른 값 확인(틀 밖 오입력 방지 — ADR 본문에 없는 앞 확인): keep은 CONFIRMED · drop은 비지 않고 중복 없음 · keep 불포함 ·
--    drop 전부 존재 · CONFIRMED · keep과 같은 좌석. 예약 행을 FOR UPDATE로 잡아 ②까지 바뀌지 않게 한다.
SELECT id, seat_id, status FROM reservation WHERE id = :'keep'::bigint OR id = ANY(:'drop'::bigint[]) ORDER BY id FOR UPDATE;
SELECT
  (SELECT count(*) FROM reservation WHERE id = :'keep'::bigint AND status = 'CONFIRMED') = 1
  AND cardinality(:'drop'::bigint[]) >= 1
  AND cardinality(:'drop'::bigint[]) = (SELECT count(DISTINCT x) FROM unnest(:'drop'::bigint[]) x)
  AND NOT (:'keep'::bigint = ANY(:'drop'::bigint[]))
  AND (SELECT count(*) FROM reservation r
        WHERE r.id = ANY(:'drop'::bigint[]) AND r.status = 'CONFIRMED'
          AND r.seat_id = (SELECT seat_id FROM reservation WHERE id = :'keep'::bigint)) = cardinality(:'drop'::bigint[]) AS pick_ok \gset
\if :pick_ok
\else
DO $$ BEGIN RAISE EXCEPTION 'R1: 고른 값이 틀렸다(keep CONFIRMED · drop 전부 CONFIRMED · 같은 좌석 · 중복 없음 · keep 불포함)'; END $$;
\endif

-- ② 취소 — 영향 행 수 = cardinality(:drop)이어야 한다(아니면 ROLLBACK — 그 사이 누가 바꿨다)
WITH c AS (UPDATE reservation SET status = 'CANCELED' WHERE id = ANY(:'drop'::bigint[]) AND status = 'CONFIRMED' RETURNING id)
SELECT count(*) AS canceled, count(*) = cardinality(:'drop'::bigint[]) AS cancel_ok FROM c \gset
\if :cancel_ok
\else
DO $$ BEGIN RAISE EXCEPTION 'R1: 취소 행 수가 drop 수와 다르다 — 그 사이 누가 바꿨다'; END $$;
\endif

-- ③ 다시 세기(V6__backfill_user_hold_quota.sql:2-3 · :15-24와 같은 정의: cnt = 그 쌍의 seat_hold 행 전체 + reservation CONFIRMED) — ②를 본 같은 트랜잭션의 새 문장
--   증분 방식이 stmt(V9 문장판 — updated_at 열은 있고 트리거 user_hold_quota_touch는 없음)면 updated_at = now()를 더한다(trigger면 V9 트리거가 쓴다 · 열이 없으면 쓰지 않는다)
SELECT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'user_hold_quota'::regclass AND attname = 'updated_at' AND NOT attisdropped)
   AND NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'user_hold_quota_touch' AND tgrelid = 'user_hold_quota'::regclass AND NOT tgisinternal) AS stmt_mode \gset
\if :stmt_mode
UPDATE user_hold_quota q
   SET cnt = (SELECT count(*) FROM seat_hold h WHERE h.schedule_id = q.schedule_id AND h.user_id = q.user_id)
           + (SELECT count(*) FROM reservation r WHERE r.schedule_id = q.schedule_id AND r.user_id = q.user_id AND r.status = 'CONFIRMED'),
       updated_at = now()
 WHERE (q.schedule_id, q.user_id) IN (SELECT schedule_id, user_id FROM reservation WHERE id = ANY(:'drop'::bigint[]))
RETURNING q.schedule_id, q.user_id, q.cnt;
\else
UPDATE user_hold_quota q
   SET cnt = (SELECT count(*) FROM seat_hold h WHERE h.schedule_id = q.schedule_id AND h.user_id = q.user_id)
           + (SELECT count(*) FROM reservation r WHERE r.schedule_id = q.schedule_id AND r.user_id = q.user_id AND r.status = 'CONFIRMED')
 WHERE (q.schedule_id, q.user_id) IN (SELECT schedule_id, user_id FROM reservation WHERE id = ANY(:'drop'::bigint[]))
RETURNING q.schedule_id, q.user_id, q.cnt;
\endif
--   쿼터 행이 없는 쌍(정리 ①이 지운 마감 회차 등)은 ③이 건드리지 않는다 — 카운터를 쓰지 않는 쌍이다(행을 새로 만들지 않는다)

-- ④ 그 좌석의 CONFIRMED = 1(keep)인지 같은 트랜잭션에서 확인
SELECT (SELECT array_agg(id ORDER BY id) FROM reservation WHERE status = 'CONFIRMED'
         AND seat_id = (SELECT seat_id FROM reservation WHERE id = :'keep'::bigint)) = ARRAY[:'keep'::bigint] AS seat_ok \gset
\if :seat_ok
\else
DO $$ BEGIN RAISE EXCEPTION 'R1: 복구 뒤 그 좌석의 CONFIRMED가 keep 1건이 아니다'; END $$;
\endif

\if :{?commit}
COMMIT;
\echo 'R1 COMMIT — 취소' :canceled '건 · 이어서 R0 · R2'
\else
ROLLBACK;
\echo 'R1 미리보기 — ROLLBACK(바뀐 것 없음) · 적용하려면 -v commit=1'
\endif
