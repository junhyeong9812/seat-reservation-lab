-- ADR-014 §설계-7 방식 전환 정리(선검증 DS14-2) — 모든 앱을 내린 정지 상태에서 T1·T2를 한 트랜잭션으로 돌린다. 방향(A ↔ B·C·D · B·C ↔ D · B ↔ C)마다 같다.
-- 이 폴더는 Flyway 위치가 아니다(클래스패스 밖 — 앱이 실행할 수 없다). 테스트(Adr014CutoverContractTest — Adr014Support.kt)가 이 파일의 정지 상태 검사 · 잠금(CR14-29) · 두 문장을 읽어 돌린다.
-- psql 변수 quota_touch(필수 — 주지 않으면 T1의 변수 자리가 그대로 남아 문법 오류로 멈춘다): 증분 대조 방식(seat.hold.counter-drift.reconcile.incremental.method)이
--   stmt면 ', updated_at = now()'(앱 감소 문장과 같은 뜻 — ExpiryCounters GUARD_STMT_SQL · §설계-2.4 DS14-6) · none · trigger면 ''(trigger는 V9 트리거가 쓴다). 사용 순서는 README.md.
-- 시각은 DB now() 하나(정지 상태라 잠금 경합이 없다). 음수 감소는 V5 CHECK(cnt >= 0)가 문장을 실패시킨다 — 조용히 0으로 자르지 않는다.
BEGIN;

-- 정지 상태 검사(코드 리뷰 CR14-16 — 늘 켬 · 끄는 변수 없음): 이 DB에 다른 클라이언트 세션(자기 세션 · 백그라운드 프로세스 제외)이 하나라도 있으면
--   아무것도 바꾸지 않고 멈춘다(SQLSTATE 55006 object_in_use · 트랜잭션 롤백). 업무 앱 · 대조 전용 프로세스(F)가 붙어 있으면 그 선점·배치·대조가 T1·T2와 섞인다(README 1번).
--   코드 리뷰 CR14-29: 권한 없는 롤은 남의 세션 행의 backend_type 등이 NULL이다(PG16 문서 §28.2.2 '다른 세션 행은 많은 열이 null' — datname은 보인다).
--   그래서 ① 실행 롤이 superuser 또는 pg_read_all_stats 권한(privileges of — pg_has_role USAGE)이 없으면 먼저 멈추고(42501 insufficient_privilege)
--   ② backend_type이 NULL인 행도 '있음'으로 센다(무음 통과 금지 — ①을 지나면 NULL은 나오지 않는다).
--   테스트는 이 블록을 따로 읽는다(Adr014Support.kt — 테스트 DB에는 풀 세션이 있다).
DO $quiesce$
DECLARE
  n int;
  who text;
BEGIN
  IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AND NOT pg_has_role(current_user, 'pg_read_all_stats', 'USAGE') THEN
    RAISE EXCEPTION 'ADR-014 방식 전환 정리: 실행 롤 %는 superuser도 pg_read_all_stats 권한도 없다 — 남의 세션이 보이지 않아 정지 상태를 확인할 수 없다(PG16 §28.2.2). superuser 또는 pg_read_all_stats를 받은 롤로 돌린다', current_user
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  SELECT count(*), string_agg(format('%s(pid %s · %s)', coalesce(nullif(application_name, ''), '?'), pid, coalesce(client_addr::text, 'local')), ', ')
    INTO n, who
    FROM pg_stat_activity
   WHERE datname = current_database() AND pid <> pg_backend_pid() AND (backend_type = 'client backend' OR backend_type IS NULL);
  IF n > 0 THEN
    RAISE EXCEPTION 'ADR-014 방식 전환 정리: 이 DB에 다른 세션 %개가 붙어 있다 — 업무 앱 · 대조 전용 프로세스를 모두 내린 뒤 다시 돌린다: %', n, who
      USING ERRCODE = 'object_in_use';
  END IF;
END
$quiesce$;

-- 코드 리뷰 CR14-29: 검사 직후 T1·T2가 쓰고 읽는 테이블(seat_hold · user_hold_quota · product_seat · T1이 읽는 product_schedule)을 COMMIT까지 쥔다.
--   product_schedule도 함께 쥐는 이유(재점검 CR14-31): 빼면 검사 뒤 붙은 세션이 product_schedule을 먼저 잠그고 seat_hold를 기다릴 때
--   T1의 product_schedule 읽기와 서로 기다리는 교착이 된다 — 정지 상태라 읽기 차단의 비용은 없다.
--   검사 뒤 새로 붙은 세션은 이 테이블을 읽지도 쓰지도 못하고 기다린다(잠금은 트랜잭션 끝에 풀린다). 다른 세션이 이미 그 테이블 잠금을 쥐고 있으면
--   기다리지 않고 멈춘다(NOWAIT — SQLSTATE 55P03 lock_not_available · 트랜잭션 롤백).
LOCK TABLE seat_hold, user_hold_quota, product_seat, product_schedule IN ACCESS EXCLUSIVE MODE NOWAIT;

-- T1 남은 만료 홀드 삭제 + 쌍별 감소(지운 행 수만큼 — 규칙 ②). 열린 회차만 감소(마감 회차는 카운터를 쓰지 않는다 — 정리 ①과 같은 뜻)
WITH d AS (DELETE FROM seat_hold WHERE expires_at <= now() RETURNING schedule_id, user_id),
     n AS (SELECT schedule_id, user_id, count(*) AS n FROM d GROUP BY schedule_id, user_id)
UPDATE user_hold_quota q SET cnt = q.cnt - n.n :quota_touch
  FROM n JOIN product_schedule s ON s.id = n.schedule_id AND s.starts_at > now()
 WHERE q.schedule_id = n.schedule_id AND q.user_id = n.user_id;

-- T2 좌석 기준 재계산 — RESERVED가 아닌 좌석을 '활성 홀드 있음 → HELD / 없음 → AVAILABLE'로
UPDATE product_seat st
   SET status = CASE WHEN EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now())
                     THEN 'HELD' ELSE 'AVAILABLE' END
 WHERE st.status <> 'RESERVED';

COMMIT;
