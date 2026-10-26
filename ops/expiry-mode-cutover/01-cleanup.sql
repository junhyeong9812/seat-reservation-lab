-- ADR-014 §설계-7 방식 전환 정리(선검증 DS14-2) — 모든 앱을 내린 정지 상태에서 T1·T2를 한 트랜잭션으로 돌린다. 방향(A ↔ B·C·D · B·C ↔ D · B ↔ C)마다 같다.
-- 이 폴더는 Flyway 위치가 아니다(클래스패스 밖 — 앱이 실행할 수 없다). 테스트(Adr014CutoverContractTest — Adr014Support.kt)가 이 파일의 두 문장을 읽어 돌린다.
-- psql 변수 quota_touch(필수 — 주지 않으면 T1의 변수 자리가 그대로 남아 문법 오류로 멈춘다): 증분 대조 방식(seat.hold.counter-drift.reconcile.incremental.method)이
--   stmt면 ', updated_at = now()'(앱 감소 문장과 같은 뜻 — ExpiryCounters GUARD_STMT_SQL · §설계-2.4 DS14-6) · none · trigger면 ''(trigger는 V9 트리거가 쓴다). 사용 순서는 README.md.
-- 시각은 DB now() 하나(정지 상태라 잠금 경합이 없다). 음수 감소는 V5 CHECK(cnt >= 0)가 문장을 실패시킨다 — 조용히 0으로 자르지 않는다.
BEGIN;

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
