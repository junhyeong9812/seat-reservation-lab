-- ADR-015 R15-15 복구 틀 — apply.sh 기록 ①(Flyway CLI · 트랜잭션 밖)이 class history-diff로 실패해 V9 밖 이력 행이 함께 남았을 때(README.md '기록 ① 뒤 history-diff 실패').
-- 사람이 확인한 뒤(README '확인할 것') 그 실행이 쓴 이력 행을 한 트랜잭션에서 지운다. 기본 = 미리보기(끝에서 ROLLBACK).
-- 자리표시자(psql 변수): versions = 지울 이력 버전 목록(배열 리터럴 — 보통 out JSON의 record.cli.history_diff 버전 전부 · V9 포함) · commit을 주면 COMMIT.
--   psql -X -v ON_ERROR_STOP=1 -v versions='{4,9}' -f ops/online-v9/06-history-diff-recover.sql              # 미리보기(ROLLBACK)
--   psql -X -v ON_ERROR_STOP=1 -v versions='{4,9}' -v commit=1 -f ops/online-v9/06-history-diff-recover.sql  # 적용(COMMIT)
-- 이력 표(Flyway 내부 표) 행만 지운다 — 스키마 객체(V9 열 · 함수 · 트리거 · 인덱스)는 건드리지 않는다(apply.sh 단계 1~3을 다시 돌리면 '있음'으로 건너뛴다).
-- 어느 확인이든 어기면 RAISE로 끝난다(ON_ERROR_STOP → psql exit 3 · 트랜잭션은 ROLLBACK — 아무것도 바뀌지 않는다).
\set ON_ERROR_STOP on
\if :{?versions}
\else
DO $$ BEGIN RAISE EXCEPTION 'history-diff 복구: -v versions=''{<지울 버전>,…}'' 가 필요하다(apply.sh out JSON의 record.cli.history_diff)'; END $$;
\endif

BEGIN;
-- 표 잠금(apply.sh 기록 ②와 같은 모드) — 확인 · 삭제 사이에 다른 Flyway 실행이 이력을 바꾸지 못하게
LOCK TABLE flyway_schema_history IN SHARE ROW EXCLUSIVE MODE;

-- ⓟ 미리보기 — 지울 행 전체(사람이 out JSON의 history_diff와 같은지 본다)
SELECT installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success
  FROM flyway_schema_history WHERE version = ANY(:'versions'::text[]) ORDER BY installed_rank;

-- ⓠ 입력 확인(틀 밖 오입력 방지): 비지 않음 · 중복 없음 · 버전마다 이력 행이 정확히 1개 ·
--    지울 행이 이력의 **맨 끝** 연속 행이다(그 실행이 마지막에 쓴 행 — 더 앞의 정상 이력(예: V13)을 지우지 않게)
SELECT
  cardinality(:'versions'::text[]) >= 1
  AND cardinality(:'versions'::text[]) = (SELECT count(DISTINCT x) FROM unnest(:'versions'::text[]) x)
  AND (SELECT count(*) FROM flyway_schema_history WHERE version = ANY(:'versions'::text[])) = cardinality(:'versions'::text[])
  AND (SELECT count(DISTINCT version) FROM flyway_schema_history WHERE version = ANY(:'versions'::text[])) = cardinality(:'versions'::text[])
  AND coalesce((SELECT min(installed_rank) FROM flyway_schema_history WHERE version = ANY(:'versions'::text[]))
               > (SELECT max(installed_rank) FROM flyway_schema_history WHERE version IS NULL OR NOT (version = ANY(:'versions'::text[]))), true)
  AS pick_ok \gset
\if :pick_ok
\else
DO $$ BEGIN RAISE EXCEPTION 'history-diff 복구: 고른 버전이 틀렸다(비지 않음 · 중복 없음 · 버전마다 이력 1행 · 이력 맨 끝 연속 행이어야 한다) — 미리보기 행을 다시 보라'; END $$;
\endif

-- ① 삭제 — 지운 행 수 = cardinality(versions)이어야 한다
WITH d AS (DELETE FROM flyway_schema_history WHERE version = ANY(:'versions'::text[]) RETURNING version)
SELECT count(*) AS deleted, count(*) = cardinality(:'versions'::text[]) AS delete_ok FROM d \gset
\if :delete_ok
\else
DO $$ BEGIN RAISE EXCEPTION 'history-diff 복구: 지운 행 수가 versions 수와 다르다'; END $$;
\endif

-- ② 남은 이력(같은 트랜잭션) — apply.sh 실행 전 이력과 같아야 한다(사람이 out JSON 실행 전 상태와 비교)
SELECT 'HISTORY ' || coalesce(string_agg(version || ':' || success, ' ' ORDER BY installed_rank), '') FROM flyway_schema_history;

\if :{?commit}
COMMIT;
\echo 'RECOVER COMMIT — 이력 행' :deleted '개 삭제 · 이어서 README 재개 방법(apply.sh를 고친 --locations로 다시)'
\else
ROLLBACK;
\echo 'RECOVER 미리보기 — ROLLBACK(바뀐 것 없음) · 적용하려면 -v commit=1'
\endif
