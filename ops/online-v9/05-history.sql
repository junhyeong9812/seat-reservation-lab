-- ADR-013 §4.4 이력 확인 — flyway_schema_history의 V9 행(없으면 빈 배열 · 표가 없으면 apply.sh가 이 문장을 부르지 않는다).
SELECT coalesce(json_agg(json_build_object('installed_rank', installed_rank, 'version', version, 'description', description, 'type', type,
                                           'script', script, 'checksum', checksum, 'success', success) ORDER BY installed_rank), '[]')
FROM flyway_schema_history WHERE version = '9';
