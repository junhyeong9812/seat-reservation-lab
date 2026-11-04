-- ADR-015 §설계-4 R0 사전 검사 — 같은 좌석의 CONFIRMED 2건 이상. **0행이어야 배포한다**(V12 uq_reservation_confirmed_seat가 23505 없이 만들어진다).
-- 0행이 아니면 배포를 멈춘다 — 자동 복구하지 않는다(중복 확정 1건 = 결제한 사용자 둘 · 어느 쪽을 남길지는 사업 결정 → R1 · README).
-- 읽기만 한다(잠금 없음). 운영 크기 사본에서 먼저, 배포 직전 운영에서 한 번 더(R0과 V12 사이 새 중복은 V12가 23505로 막는다 — 무음 아님).
--   psql -X -v ON_ERROR_STOP=1 -f ops/confirm-unique-v12/r0-precheck.sql
SELECT seat_id, count(*) AS n, array_agg(id ORDER BY created_at, id) AS reservation_ids
FROM reservation WHERE status = 'CONFIRMED' GROUP BY seat_id HAVING count(*) > 1;
